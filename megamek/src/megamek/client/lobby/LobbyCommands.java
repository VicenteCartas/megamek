/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.BotClient;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.generator.RandomCallsignGenerator;
import megamek.client.generator.RandomGenderGenerator;
import megamek.client.generator.RandomNameGenerator;
import megamek.client.ui.clientGUI.UnitRecipients;
import megamek.common.Player;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.bays.Bay;
import megamek.common.board.BoardDimensions;
import megamek.common.enums.Gender;
import megamek.common.enums.VariableRangeTargetingMode;
import megamek.common.equipment.BombLoadout;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.Transporter;
import megamek.common.equipment.WeaponType;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.interfaces.ForceAssignable;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MapSetup;
import megamek.common.net.packets.InvalidPacketDataException;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.strategicBattleSystems.SBFFormationConverter;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;
import megamek.common.units.IBomber;
import megamek.common.units.LandAirMek;
import megamek.common.units.UnitType;
import megamek.common.util.C3Util;
import megamek.common.util.C3Util.C3CapacityException;
import megamek.common.util.C3Util.MismatchingC3MException;
import megamek.common.util.C3Util.MissingC3MException;
import megamek.common.util.CollectionUtil;
import megamek.logging.MMLogger;
import megamek.server.commands.PingCommand;

/**
 * The changes the lobby makes to players, units, forces and the map, and sends to the server, independent of any
 * view. Commands check that the local player may make a change and ask the player through {@link LobbyPrompts}
 * when needed. Updates for a local bot's units are sent through the bot's own client, as the server does not know
 * of local bots and rejects updates that are not for the sending client or its teammates.
 */
public class LobbyCommands {
    private static final MMLogger logger = MMLogger.create(LobbyCommands.class);

    /** The chat command that asks for the Game Master role, or gives it up. */
    public static final String GAME_MASTER_COMMAND = "/gm";
    /** The chat commands that vote for or against a Game Master request, and withdraw one's own request. */
    public static final String GAME_MASTER_ALLOW_COMMAND = "/allowGM";
    public static final String GAME_MASTER_DENY_COMMAND = "/denyGM";
    public static final String GAME_MASTER_WITHDRAW_COMMAND = "/cancelGM";
    /** The chat command that has the server send the game to this client, which writes it to a file. */
    public static final String LOCAL_SAVE_COMMAND = "/localsave";

    public static final int MIN_MAP_SIZE = 1;
    public static final int MAX_MAP_SIZE = 20;
    public static final int MIN_BOARD_SIZE = 5;
    public static final int MAX_BOARD_SIZE = 200;

    /** How a Mek is deployed. */
    public enum Stance { PRONE, HULL_DOWN, STANDING }

    /**
     * A camo chooser that answers later, such as one drawn in the lobby's own window rather than a modal Swing dialog.
     */
    @FunctionalInterface
    public interface CamoPicker {
        /**
         * Shows the chooser and returns at once; {@code chosen} runs on the caller's thread with the camo when the
         * player confirms one, and never when they cancel.
         *
         * @param initial           the camouflage to start from
         * @param hasIndividualCamo {@code true} when any of the units has a camouflage other than its owner's
         * @param units             the units to show the camouflage on
         * @param chosen            takes the chosen camouflage
         */
        void choose(Camouflage initial, boolean hasIndividualCamo, List<Entity> units, Consumer<Camouflage> chosen);
    }

    /**
     * A unit configuration editor that answers later, such as one drawn in the lobby's own window rather than a modal
     * Swing dialog; it sends what it changes through {@link #finishCustomization}.
     */
    @FunctionalInterface
    public interface UnitConfigurator {
        /**
         * Shows the editor and returns at once.
         *
         * @param client   the client of the units' owner, a local bot's for its units
         * @param units    the units, all of one owner and none a Battlefield Support asset
         * @param editable whether the player may change them
         */
        void configure(Client client, List<Entity> units, boolean editable);
    }

    private final LobbySession session;
    private final LobbyPrompts prompts;
    private final LobbyAsks blockingAsks;
    private volatile CamoPicker camoPicker;
    private volatile UnitConfigurator unitConfigurator;
    private volatile LobbyAsks nativeAsks;

    /**
     * @param session the local client, its game and bots
     * @param prompts how the player is told about refused commands and asked for choices
     */
    public LobbyCommands(LobbySession session, LobbyPrompts prompts) {
        this.session = session;
        this.prompts = prompts;
        blockingAsks = (prompts == null) ? null : LobbyAsks.of(prompts);
    }

    /**
     * Has the commands ask {@code asks} instead of the prompts for everything but camouflage and unit
     * configuration; null goes back to the prompts.
     */
    public void asks(@Nullable LobbyAsks asks) {
        nativeAsks = asks;
    }

    private LobbyAsks asks() {
        LobbyAsks asks = nativeAsks;
        return (asks != null) ? asks : blockingAsks;
    }

    /**
     * Has {@link #individualCamo} ask {@code picker} instead of {@link LobbyPrompts#chooseCamo}; null goes back to
     * the prompts.
     */
    public void camoPicker(@Nullable CamoPicker picker) {
        camoPicker = picker;
    }

    /**
     * Has {@link #customizeMek} and {@link #customizeMeks} open {@code configurator} instead of
     * {@link LobbyPrompts#customizeUnit} and {@link LobbyPrompts#customizeUnits}; null goes back to the prompts.
     */
    public void unitConfigurator(@Nullable UnitConfigurator configurator) {
        unitConfigurator = configurator;
    }

    // region Readiness

    /**
     * @return what keeps the local player and their bots from being ready; empty when they may be ready
     */
    public List<ReadyCheck.Blocker> readyBlockers() {
        List<Player> bots = localBots().values().stream().map(AbstractClient::getLocalPlayer).toList();
        return ReadyCheck.blockers(game(), localPlayer(), bots);
    }

    /**
     * Checks whether the local player and their bots may be ready, telling the player why not if they may not.
     *
     * @return {@code true} when they may be ready
     */
    public boolean checkReady() {
        List<ReadyCheck.Blocker> blockers = readyBlockers();
        if (!blockers.isEmpty()) {
            asks().readyBlocked(blockers.get(0));
            return false;
        }
        return true;
    }

    /**
     * Sends the done state for the local player and all local bots.
     *
     * @param done {@code true} when ready
     */
    public void sendDone(boolean done) {
        client().sendDone(done);
        for (AbstractClient botClient : localBots().values()) {
            botClient.sendDone(done);
        }
    }

    // endregion

    // region The game

    /**
     * Saves the game to {@code file}, as the Swing client's Save Game does: the server sends the game and this client
     * writes it there, adding ".sav.gz" unless the name has it. {@code saved} then gets the file written, or null when
     * it could not be.
     */
    public void saveGame(File file, Consumer<File> saved) {
        client().setSaveCompletionCallback(saved);
        client().setAwaitingSave(true);
        client().sendChat(localSaveCommand(file));
    }

    /** Saves the game to the quick save file in the save game folder, without asking; {@code saved} as above. */
    public void quickSave(Consumer<File> saved) {
        client().setSaveCompletionCallback(saved);
        client().sendChat(LOCAL_SAVE_COMMAND + ' ' + MMConstants.QUICKSAVE_FILE + ' ' + MMConstants.QUICKSAVE_PATH);
    }

    /**
     * The chat command that saves the game to {@code file}. The command's words are split on spaces, so a space in the
     * folder becomes the "|" the server turns back into one, and a space in the name an underscore.
     */
    static String localSaveCommand(File file) {
        File folder = file.getAbsoluteFile().getParentFile();
        String path = (folder == null) ? MMConstants.QUICKSAVE_PATH : folder.getPath();
        return LOCAL_SAVE_COMMAND + ' ' + file.getName().strip().replace(' ', '_') + ' ' + path.replace(' ', '|');
    }

    // endregion

    // region Players

    /**
     * Asks the server for the Game Master role, or gives it up when this player already holds it. The request goes
     * through the same command the chat uses, so the rules for taking the role live in one place.
     */
    public void toggleGameMaster() {
        client().sendChat(GAME_MASTER_COMMAND);
    }

    /**
     * Pings player {@code id}, as the Swing lobby's player menu did: another player is pinged alone, the local
     * player's own id pings everyone. A disconnected player, who could not hear it, is not pinged. The ping goes
     * through the {@code /ping} command, so the server applies the same checks and cooldown as when it is typed.
     */
    public void ping(int id) {
        Player target = game().getPlayer(id);
        if (target == null || target.isGhost()) {
            return;
        }
        Player local = client().getLocalPlayer();
        boolean everyone = local != null && local.getId() == target.getId();
        client().sendChat(PingCommand.commandText(everyone ? null : target));
    }

    /** Votes in the running Game Master vote: for the request when {@code allow}, else against it. */
    public void voteGameMaster(boolean allow) {
        client().sendChat(allow ? GAME_MASTER_ALLOW_COMMAND : GAME_MASTER_DENY_COMMAND);
    }

    /** Withdraws the local player's own request for the Game Master role, which ends its vote. */
    public void withdrawGameMaster() {
        client().sendChat(GAME_MASTER_WITHDRAW_COMMAND);
    }

    /**
     * Change the team of a controlled player (the local player or one of his bots).
     */
    public void changeTeam(Collection<Player> players, int team) {
        var toSend = new Vector<Player>();
        players.stream()
              .filter(this::isSelfOrLocalBot)
              .filter(p -> p.getTeam() != team)
              .forEach(toSend::add);
        client().sendChangeTeam(toSend, team);
    }

    /**
     * Sets the starting position of those of the given players that are the local player or a local bot and sends
     * their player info.
     */
    public void changeStartingPosition(Collection<Player> players, int startPos) {
        for (Player player : players) {
            if (isSelfOrLocalBot(player)) {
                if (client().isLocalBot(player)) {
                    // must use the bot's own player object:
                    client().getBotClient(player).getLocalPlayer().setStartingPos(startPos);
                    client().getBotClient(player).sendPlayerInfo();
                } else {
                    player.setStartingPos(startPos);
                    client().sendPlayerInfo();
                }
            }
        }
    }

    /**
     * Sets the camouflage of a player run by the given client and sends the player info.
     *
     * @param playerClient the client of the local player or a local bot
     * @param camouflage   the new camouflage
     */
    public void changePlayerCamo(Client playerClient, Camouflage camouflage) {
        playerClient.getLocalPlayer().setCamouflage(camouflage);
        playerClient.sendPlayerInfo();
    }

    /**
     * Puts a newly added bot on {@code team} once the server has given it its player, as adding a bot to a team in
     * the lobby needs: the bot joins on no particular team and is moved over by its own client. Call it until it
     * returns {@code true}.
     *
     * @param bot  the new bot's client
     * @param team the team to join; {@link Player#TEAM_UNASSIGNED} leaves the bot where the server put it
     * @return {@code true} when the bot has its player and is on its team (or no team was asked for), {@code false}
     *       while it still waits for its player
     */
    public boolean joinTeam(AbstractClient bot, int team) {
        Player player = bot.getLocalPlayer();
        if (player == null) {
            return false;
        }
        if (team != Player.TEAM_UNASSIGNED && player.getTeam() != team) {
            player.setTeam(team);
            bot.sendPlayerInfo();
        }
        return true;
    }

    /** @return the client of the bot that plays {@code player} on this machine, or null when it is no local bot */
    public @Nullable Client localBotClient(Player player) {
        return localBots().get(player.getName()) instanceof Client bot ? bot : null;
    }

    /**
     * Removes a bot that runs on this machine.
     *
     * @param botClient the bot's client, or {@code null} when the selected player is no local bot
     */
    public void removeBot(@Nullable Client botClient) {
        if (!localBots().containsValue(botClient)) {
            asks().error(LobbyError.ONLY_OWN_BOT);
            return;
        }
        // Don't delete the bot's forces, as that could also delete other players' entities
        botClient.die();
        localBots().remove(botClient.getName());
    }

    /** Adds the given entities as strategic targets for the given local bot. */
    public void setPriorityTarget(String botName, Collection<Entity> entities) {
        Map<String, AbstractClient> bots = localBots();
        if (!bots.containsKey(botName) || !(bots.get(botName) instanceof BotClient botClient)) {
            return;
        }
        BehaviorSettings behavior = botClient.getBehaviorSettings();
        entities.forEach(e -> behavior.addPriorityUnit(e.getId()));
    }

    // endregion

    // region Map

    /** Sends the map settings to the server. */
    public void sendMapSettings(MapSettings mapSettings) {
        client().sendMapSettings(mapSettings);
    }

    /** Changes only this sheet's session settings; source board files are never written. */
    public void changeBoardTheme(MapSettings mapSettings, int index, String theme) {
        mapSettings.setBoardTheme(index, theme);
        sendMapSettings(mapSettings);
    }

    /** Changes only this sheet's session settings; source board files are never written. */
    public void changeBoardLevelOffset(MapSettings mapSettings, int index, int offset) {
        mapSettings.setBoardLevelOffset(index, offset);
        sendMapSettings(mapSettings);
    }

    /**
     * Puts the given board into a board slot, sends the map settings and tells the other players.
     *
     * @param board the board, or several boards separated by newlines to pick one of at random
     */
    public void changeBoard(MapSettings mapSettings, int slot, String board) {
        if (board.contains("\n") && !board.startsWith(MapSettings.BOARD_SURPRISE)) {
            board = MapSettings.BOARD_SURPRISE + board;
        }
        mapSettings.getBoardsSelectedVector().set(slot, board);
        sendMapSettings(mapSettings);
        announceMapChange(board);
    }

    /**
     * Makes the map a single board of the given name and size, sends it and tells the other players.
     */
    public void selectSingleBoard(MapSettings mapSettings, String boardName, BoardDimensions size) {
        mapSettings.getBoardsSelectedVector().clear();
        mapSettings.setMapSize(1, 1);
        mapSettings.setBoardSize(size.width(), size.height());
        client().sendMapDimensions(mapSettings);
        mapSettings.getBoardsSelectedVector().set(0, boardName);
        sendMapSettings(mapSettings);
        announceMapChange(boardName);
    }

    private void announceMapChange(String board) {
        String msg = client().getLocalPlayer() + " changed map to: " + board;
        client().sendServerChat(Player.PLAYER_NONE, msg);
    }

    /**
     * Sets the medium (ground, atmosphere, space) of the map and sends it. Space maps are a single 50 x 50 board.
     */
    public void changeMedium(MapSettings mapSettings, int medium) {
        mapSettings.setMedium(medium);
        if (medium == MapSettings.MEDIUM_SPACE) {
            mapSettings.setBoardSize(50, 50);
            mapSettings.setMapSize(1, 1);
            client().sendMapDimensions(mapSettings);
        } else {
            sendMapSettings(mapSettings);
        }
    }

    /** Sets the number of boards across and down and sends the map dimensions. */
    public void resizeMap(MapSettings mapSettings, int width, int height) {
        mapSettings.setMapSize(width, height);
        client().sendMapDimensions(mapSettings);
    }





    /** Sets the size of each board and sends the map settings. */
    public void resizeBoards(MapSettings mapSettings, int width, int height) {
        mapSettings.setBoardSize(width, height);
        sendMapSettings(mapSettings);
    }



    /** Applies a saved map setup's map size, board size and boards and sends the map settings. */
    public void applyMapSetup(MapSettings mapSettings, MapSetup setup) {
        setup.applyTo(mapSettings);
        sendMapSettings(mapSettings);
    }

    // endregion

    // region Unit settings

    /**
     * Sets a deployment round for the given entities. Sends an update to the server.
     */
    public void applyDeployment(Collection<Entity> entities, int newRound) {
        if (!validateUpdate(entities)) {
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            if (entity.getDeployRound() != newRound) {
                entity.setDeployRound(newRound);
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /** Sets starting heat for the given entities. Sends an update to the server. */
    public void applyHeat(Collection<Entity> entities, int heat) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (entities.stream().anyMatch(e -> !e.tracksHeat())) {
            asks().error(LobbyError.HEAT_TRACKING);
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            if (entity.getHeat() != heat) {
                entity.heat = heat;
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Sets/removes hidden deployment for the given entities. Sends an update to the server. Units that cannot hide
     * (see {@link Entity#canHide()}) are left visible, the same as in the unit's configuration dialog.
     */
    public void applyHidden(Collection<Entity> entities, boolean newHidden) {
        if (!validateUpdate(entities)) {
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            if (newHidden && !entity.canHide()) {
                continue;
            }
            if (entity.isHidden() != newHidden) {
                entity.setHidden(newHidden);
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Marks the given units as ones the mission wants scanned, or takes the marking off, and tells the server.
     * Only a game master gets here, because only a game master sees the menu and only a game master may edit
     * another side's units. Once any unit in the game is marked, only marked units are worth scanning, so this is
     * how a convoy becomes the objective and its escort does not (Objectives series).
     *
     * @param entities the units to mark
     * @param wanted   {@code true} to ask for them to be scanned, {@code false} to drop the request
     */
    public void applyScanTarget(Collection<Entity> entities, boolean wanted) {
        if (!validateUpdate(entities)) {
            logger.debug("[Scan] Scan target change refused for {} unit(s): the lobby would not allow the update",
                  entities.size());
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            if (entity.isDesignatedScanTarget() != wanted) {
                entity.setDesignatedScanTarget(wanted);
                updateCandidates.add(entity);
                // a local bot's unit is sent through the bot's own client, so name the sender for a playtest log
                Client sender = correctSender(entity);
                logger.info("[Scan] {} {} wanted for scanning, owned by {}, sent by {}", entity.getShortName(),
                      wanted ? "marked" : "unmarked", entity.getOwner().getName(),
                      (sender == null) ? "nobody" : sender.getLocalPlayer().getName());
            }
        }
        sendUpdates(updateCandidates);
    }

    /** Sets deploy prone or hull down for the given entities. Sends an update to the server. */
    public void applyProne(Collection<Entity> entities, Stance stance) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (entities.stream().anyMatch(e -> e.getUnitType() != UnitType.MEK)) {
            asks().error(LobbyError.ONLY_MEKS);
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        boolean goProne = stance == Stance.PRONE;
        boolean goHullDown = stance == Stance.HULL_DOWN;
        boolean stand = !goProne && !goHullDown;
        for (Entity entity : entities) {
            if ((goProne && !entity.isProne()) || (goHullDown && !entity.isHullDown())
                  || (stand && (entity.isProne() || entity.isHullDown()))) {
                entity.setProne(goProne);
                entity.setHullDown(goHullDown);
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /** Shows the editor which allows adding pre-existing damage to units. */
    public void configureDamage(Collection<Entity> entities) {
        if (entities.size() != 1) {
            asks().error(LobbyError.SINGLE_UNIT, "assign damage");
            return;
        }
        if (!validateUpdate(entities)) {
            return;
        }
        Entity entity = CollectionUtil.anyOneElement(entities);
        boolean damage = canEditDamage(client(), entity);
        // An asset has no armor/structure/criticals; its only persistent damage is a lowered Destroy Check.
        if (entity instanceof BattlefieldSupportAsset asset) {
            if (!damage) {
                asks().error(LobbyError.CANNOT_EDIT_DAMAGE);
                return;
            }
            asks().editAssetDamage(asset, () -> sendUpdates(entities));
            return;
        }

        // Any unit the local player may configure opens: its deployment is its owner's to set, while its damage is
        // the gamemaster's when one is present, so the editor shows the damage read-only to anyone else.
        GameOptions gameOptions = client().getGame().getOptions();
        UnitDamageForm.Options options = new UnitDamageForm.Options(damage,
              Game.rulesManager.getRulesGame().isWalkOnDeployment(), LobbyUnitMenu.isScanTargetMenuUseful(client()),
              gameOptions.booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN),
              gameOptions.booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS));
        asks().editDamage(entity, options, () -> sendUpdates(entities));
    }

    /**
     * Returns true when the local player of the given client may edit damage on the given unit. When any player
     * holds the Game Master role, only the Game Master may edit damage. Without a Game Master, players may edit
     * damage only on their own units and the units of their local bots.
     *
     * @param client the client asking to edit damage
     * @param entity the unit to edit damage on
     *
     * @return true when the client's local player may edit damage on the unit
     */
    public static boolean canEditDamage(Client client, Entity entity) {
        Player localPlayer = client.getLocalPlayer();
        boolean ownUnit = (entity.getOwnerId() == localPlayer.getId())
              || client.getBots().containsKey(entity.getOwner().getName());
        for (Player player : client.getGame().getPlayersList()) {
            if (player.isGameMaster()) {
                // when a gamemaster is present, only the gamemaster may edit damage; everyone else loses access
                return localPlayer.isGameMaster();
            }
        }
        return ownUnit;
    }

    /**
     * Shows a camouflage chooser to choose an individual camo for the given entities. The camo will only be applied
     * to units configurable by the local player, i.e. his own units or those of his bots.
     */
    public void individualCamo(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }

        // The chooser is preset to a random entity's settings
        Entity entity = CollectionUtil.anyOneElement(entities);
        boolean hasIndividualCamo = entities.stream().anyMatch(e -> !e.getCamouflage().hasDefaultCategory());
        CamoPicker picker = camoPicker;
        if (picker != null) {
            List<Entity> units = new ArrayList<>(entities);
            picker.choose(entity.getCamouflageOrElseOwners(), hasIndividualCamo, units,
                  chosen -> applyIndividualCamo(units, entity, chosen));
            return;
        }
        Optional<Camouflage> chosen = prompts.chooseCamo(entity.getCamouflageOrElseOwners(), hasIndividualCamo,
              new ArrayList<>(entities));
        chosen.ifPresent(camo -> applyIndividualCamo(entities, entity, camo));
    }

    private void applyIndividualCamo(Collection<Entity> entities, Entity entity, Camouflage selectedItem) {
        // Choosing the player camo resets the units to have no individual camo. The asset marker overlay rides on
        // the camo, so it is carried automatically (including on a reset back to the player camo).
        Camouflage ownerCamo = entity.getOwner().getCamouflage();
        boolean noIndividualCamo = selectedItem.equals(ownerCamo);

        // Update all allowed entities with the camo
        for (final Entity ent : entities) {
            ent.setCamouflage(noIndividualCamo ? ownerCamo : selectedItem);
        }
        sendUpdates(entities);
    }

    /**
     * Shows the unit configuration editor for the given units.
     *
     * @param entities the units to configure
     */
    public void customizeMeks(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (!LobbyRules.haveSingleOwner(entities)) {
            asks().error(LobbyError.SINGLE_OWNER);
            return;
        }
        // Assets use their own minimal crew editor. A mixed selection must run both configuration flows rather than
        // choosing one based on an arbitrary element from the collection.
        List<Entity> assets = entities.stream().filter(BattlefieldSupportAsset.class::isInstance).toList();
        assets.forEach(this::customizeBattlefieldSupportAsset);

        List<Entity> standardUnits = entities.stream()
              .filter(entity -> !(entity instanceof BattlefieldSupportAsset))
              .toList();
        if (standardUnits.isEmpty()) {
            return;
        }

        Entity oneSelected = CollectionUtil.anyOneElement(standardUnits);
        Client client = clientForCustomization(oneSelected);
        boolean editable = allowCustomization(oneSelected);

        UnitConfigurator configurator = unitConfigurator;
        if (configurator != null) {
            configurator.configure(client, new ArrayList<>(standardUnits), editable);
            return;
        }
        LobbyPrompts.CustomizeResult result = prompts.customizeUnits(client, new ArrayList<>(standardUnits),
              editable);
        if (editable && result.confirmed()) {
            sendCustomizationUpdate(standardUnits);
        }
        if (result.confirmed() && (result.next() != null)) {
            customizeMek(result.next());
        }
    }

    /**
     * Shows the minimal crew configuration editor for a Battlefield Support Asset (name and Regular/Veteran grade)
     * and, if confirmed and editable, sends the update to the server.
     *
     * @param entity the asset to configure
     */
    private void customizeBattlefieldSupportAsset(Entity entity) {
        boolean editable = allowCustomization(entity);
        asks().configureAsset((BattlefieldSupportAsset) entity, editable,
              () -> sendCustomizationUpdate(List.of(entity)));
    }

    /**
     * Shows the unit configuration editor for the given unit, and for the next or previous ones as long as the
     * player asks for them.
     *
     * @param entity the unit to configure
     */
    public void customizeMek(Entity entity) {
        if (!validateUpdate(List.of(entity))) {
            return;
        }

        // Assets have no equipment/quirks/weapon tabs; only a crew name and Regular/Veteran grade to configure.
        if (entity instanceof BattlefieldSupportAsset) {
            customizeBattlefieldSupportAsset(entity);
            return;
        }

        Client client = clientForCustomization(entity);
        boolean editable = allowCustomization(entity);

        UnitConfigurator configurator = unitConfigurator;
        if (configurator != null) {
            configurator.configure(client, List.of(entity), editable);
            return;
        }
        while (entity != null) {
            LobbyPrompts.CustomizeResult result = prompts.customizeUnit(client, entity, editable);
            if (editable && result.confirmed()) {
                sendCustomizationUpdate(List.of(entity));
            }
            entity = result.confirmed() ? result.next() : null;
        }
    }

    private Optional<Client> localBotOwner(Entity entity) {
        return Optional.ofNullable((Client) localBots().get(entity.getOwner().getName()));
    }

    private Client clientForCustomization(Entity entity) {
        return localBotOwner(entity).orElse(client());
    }

    private boolean allowCustomization(Entity entity) {
        boolean ownerIsLocalPlayer = entity.getOwner().getId() == localPlayer().getId();
        return localBotOwner(entity).isPresent() || ownerIsLocalPlayer || localPlayer().isGameMaster();
    }

    /**
     * Sends the units a {@link UnitConfigurator} changed, with the units that depend on them, as the editor's OK does.
     *
     * @param entities the units that were configured
     */
    public void finishCustomization(Collection<Entity> entities) {
        sendCustomizationUpdate(entities);
    }

    /**
     * For use after customization in the unit configuration editor. Checks the given list of units for dependents
     * (transported/contained units/C3 connections) and sends an update for all to the Server.
     *
     * @param entities The units that were configured
     */
    private void sendCustomizationUpdate(Collection<Entity> entities) {
        Set<Entity> updateCandidates = new HashSet<>(entities);
        for (Entity entity : entities) {
            // If a LAM with mechanized BA was changed to non-mek mode, unload the BA.
            if ((entity instanceof LandAirMek)
                  && entity.getConversionMode() != LandAirMek.CONV_MODE_MEK) {
                for (Entity loaded : entity.getLoadedUnits()) {
                    entity.unload(loaded);
                    loaded.setTransportId(Entity.NONE);
                    updateCandidates.add(loaded);
                }
            }

            // Changing state to a transporting unit can update state of
            // transported units, so update those as well
            for (Transporter transport : entity.getTransports()) {
                updateCandidates.addAll(transport.getLoadedUnits());
            }

            // Customizations to a Squadron can effect the fighters
            if (entity instanceof FighterSquadron) {
                updateCandidates.addAll(entity.getSubEntities());
            }

            updateCandidates.addAll(C3Util.broadcastChanges(game(), entity));
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Adds {@code entities}, read from their unit files, to the game for {@code requested}, as the Requisition's Add
     * does: Clan pilots for Clan units, a regular crew for a Battlefield Support asset, the skills {@code skills} names,
     * random names when the client generates them, then the units go to the server over the local connection and the
     * chat says who selected them for whom. Once the game is under way they arrive as reinforcements next round
     * ({@link Reinforcements#prepare}). The units go to {@code requested} when they are in the game and the local
     * player may add units for them, else to the local player.
     *
     * @return the player who got the units, or null when there were none to add
     */
    public @Nullable Player addUnits(List<Entity> entities, @Nullable Player requested, RequisitionSkills skills) {
        if (entities.isEmpty()) {
            return null;
        }
        Player local = localPlayer();
        Player owner = recipient(requested);
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        for (Entity entity : entities) {
            Crew crew = entity.getCrew();
            Arrays.fill(crew.getClanPilots(), entity.isClan());
            if (entity instanceof BattlefieldSupportAsset asset) {
                asset.setVeteranCrew(false);
            } else {
                switch (skills.mode()) {
                    case RANDOM -> client().getSkillGenerator().setRandomSkills(entity);
                    case FIXED -> {
                        for (int slot = 0; slot < crew.getSlotCount(); slot++) {
                            // The RPG gunnery skills are what attacks use when that option is on
                            crew.setGunnery(skills.gunnery(), slot);
                            crew.setGunneryL(skills.gunnery(), slot);
                            crew.setGunneryM(skills.gunnery(), slot);
                            crew.setGunneryB(skills.gunnery(), slot);
                            crew.setPiloting(skills.piloting(), slot);
                        }
                    }
                    case DEFAULT -> { }
                }
            }
            if (preferences.generateNames()) {
                for (int slot = 0; slot < crew.getSlotCount(); slot++) {
                    Gender gender = RandomGenderGenerator.generate();
                    crew.setGender(gender, slot);
                    crew.setName(RandomNameGenerator.getInstance().generate(gender, crew.isClanPilot(slot),
                          owner.getName()), slot);
                }
            }
        }
        Reinforcements.prepare(entities, owner, game());
        client().sendAddEntity(entities);
        String message = local + " selected " + ((entities.size() == 1) ? "a unit" : entities.size() + " units")
              + " for player: " + owner.getName();
        client().sendServerChat(Player.PLAYER_NONE, message);
        return owner;
    }

    /**
     * The player added units go to: {@code requested} when they are still in the game and the local player may add
     * units for them ({@link UnitRecipients#mayAddUnitsTo}), else the local player.
     */
    public Player recipient(@Nullable Player requested) {
        Player local = localPlayer();
        if (requested == null || requested.getId() == local.getId()) {
            return local;
        }
        Player current = game().getPlayer(requested.getId());
        if (current == null || !UnitRecipients.mayAddUnitsTo(local, current, localBots().keySet())) {
            logger.warn("Units for {} go to {}: they may not be added for them", requested.getName(),
                  local.getName());
            return local;
        }
        return current;
    }

    /**
     * Sets random skills for the given entities, as far as they can be configured by the local player.
     */
    public void setRandomSkills(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        for (final Entity entity : entities) {
            final Client client = clientFor(entity);
            client.getSkillGenerator().setRandomSkills(entity);
        }
        sendUpdates(entities);
    }

    /**
     * Sets random names for the given entities' pilots, as far as they can be configured by the local player.
     */
    public void setRandomNames(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        for (Entity e : entities) {
            for (int i = 0; i < e.getCrew().getSlotCount(); i++) {
                Gender gender = RandomGenderGenerator.generate();
                e.getCrew().setGender(gender, i);
                e.getCrew().setName(RandomNameGenerator.getInstance().generate(gender, e.getCrew().isClanPilot(i),
                      e.getOwner().getName()), i);
            }
        }
        sendUpdates(entities);
    }

    /**
     * Sets random call signs for the given entities' pilots, as far as they can be configured by the local player.
     */
    public void setRandomCallSigns(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        for (Entity e : entities) {
            for (int i = 0; i < e.getCrew().getSlotCount(); i++) {
                e.getCrew().setNickname(RandomCallsignGenerator.getInstance().generate(), i);
            }
        }
        sendUpdates(entities);
    }

    /**
     * Toggles burst MG fire for the given entities to the state given as burstOn
     */
    public void toggleBurstMg(Collection<Entity> entities, boolean burstOn) {
        if (!validateUpdate(entities)) {
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            for (Mounted<?> mounted : entity.getWeaponList()) {
                if (mounted.getType().hasFlag(WeaponType.F_MG)) {
                    mounted.setRapidFire(burstOn);
                    updateCandidates.add(entity);
                }
            }
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Toggles hot loading LRMs for the given entities to the state given as hotLoadOn
     */
    public void toggleHotLoad(Collection<Entity> entities, boolean hotLoadOn) {
        if (!validateUpdate(entities)) {
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            for (Mounted<?> m : entity.getAmmo()) {
                // setHotLoad checks the Ammo to see if it can be hot loaded
                m.setHotLoad(hotLoadOn);
                // TODO: The following should ideally be part of setHotLoad in Mounted
                if (hotLoadOn) {
                    m.setMode("HotLoad");
                } else if (m.hasModeType("HotLoad")) {
                    m.setMode("");
                }
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Sets Variable Range Targeting mode for the given entities. Only affects entities that have the Variable Range
     * Targeting quirk.
     *
     * @param entities the entities to update
     * @param longMode true for LONG mode, false for SHORT mode
     */
    public void setVRTMode(Collection<Entity> entities, boolean longMode) {
        if (!validateUpdate(entities)) {
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        VariableRangeTargetingMode mode = longMode
              ? VariableRangeTargetingMode.LONG
              : VariableRangeTargetingMode.SHORT;
        for (Entity entity : entities) {
            if (entity.hasVariableRangeTargeting()) {
                entity.setVariableRangeTargetingMode(mode);
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Toggles Enhanced Imaging (EI) mode for the given entities. Only affects entities that have an EI Interface.
     *
     * @param entities the entities to update
     * @param enableEI true to turn EI ON, false to turn EI OFF (shutdown)
     */
    public void toggleEI(Collection<Entity> entities, boolean enableEI) {
        if (!validateUpdate(entities)) {
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity entity : entities) {
            if (entity.hasEiCockpit()) {
                // setEiShutdown(true) = OFF, setEiShutdown(false) = ON
                entity.setEiShutdown(!enableEI);
                updateCandidates.add(entity);
            }
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Swaps pilots between the given entity and another entity of the given id
     */
    public void swapPilots(Collection<Entity> entities, int targetId) {
        Entity target = game().getEntity(targetId);
        if (target == null) {
            return;
        }
        if (entities.size() != 1) {
            asks().error(LobbyError.SINGLE_UNIT, "swap pilots");
            return;
        }
        Entity selected = CollectionUtil.anyOneElement(entities);
        if (!validateUpdate(Arrays.asList(target, selected))) {
            return;
        }
        Crew temp = target.getCrew();
        target.setCrew(selected.getCrew());
        selected.setCrew(temp);
        sendUpdates(Arrays.asList(target, selected));
    }

    /**
     * Change the given entities' controller to the player with ID newOwnerId. If the given forceList is not empty, an
     * error message will be shown.
     */
    public void changeOwner(Collection<Entity> entities, Collection<Force> forceList, int newOwnerId)
          throws InvalidPacketDataException {
        if (entities.isEmpty() || game().getPlayer(newOwnerId) == null) {
            return;
        } else if (!forceList.isEmpty()) {
            asks().error(LobbyError.ENTITY_OR_FORCE);
            return;
        }
        if (!validateUpdate(entities)) {
            return;
        }
        client().sendChangeOwner(entities, newOwnerId);
    }

    /**
     * Shows the Strategic BattleForce stats of the given forces.
     */
    public void showSbfView(Collection<Force> forces) {
        if (forces.stream().anyMatch(f -> !SBFFormationConverter.canConvertToSbfFormation(f, game()))) {
            asks().error(LobbyError.SBF_CONVERSION);
            return;
        }
        asks().showSbfStats(forces, game());
    }

    /**
     * Shows the Alpha Strike stats of the given entities.
     */
    public void showAlphaStrikeView(Collection<Entity> entities) {
        asks().showAlphaStrikeStats(entities);
    }

    // endregion

    // region Forces

    /**
     * Attaches the given force as a sub force to the given new parent. Does NOT work for newParentId == NO_FORCE. Use
     * promoteForce to do this. Does not allow attaching a force to one of its own subForces.
     */
    public void forceAttach(int forceId, int newParentId) {
        Forces forces = game().getForces();
        if (!forces.contains(forceId) || !forces.contains(newParentId)
              || (forceId == newParentId)) {
            return;
        }

        Force force = forces.getForce(forceId);
        Force newParent = forces.getForce(newParentId);
        List<Force> subForces = forces.getFullSubForces(force);
        Player owner = forces.getOwner(force);
        Player newParentOwner = forces.getOwner(newParent);

        if (owner.isEnemyOf(newParentOwner)) {
            asks().error(LobbyError.ONLY_TEAM);
            return;
        }
        if (subForces.contains(newParent)) {
            asks().error(LobbyError.FORCE_ATTACH_TO_SUB_FORCE);
            return;
        }
        if (!isEditable(force)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }
        var forceList = new ArrayList<>(List.of(force));
        client().sendForceParent(forceList, newParentId);
    }

    /**
     * Makes the given forces top-level, detaching them from any former parents.
     */
    public void forcePromote(Collection<Integer> forceIds) {
        var forces = game().getForces();
        if (forceIds.stream().anyMatch(id -> !forces.contains(id))) {
            return;
        }
        Set<Force> forceList = forceIds.stream().map(forces::getForce).collect(toSet());
        if (!areForcesEditable(forceList)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }
        client().sendForceParent(forceList, Force.NO_FORCE);
    }

    /**
     * Moves a force or entity within another force by one position. If up is true, moves upward, otherwise downward.
     */
    public void forceMove(Collection<Force> forceList, Collection<Entity> entityList, boolean up) {
        // May only move a single force or a single entity
        if (forceList.size() + entityList.size() != 1) {
            asks().error(LobbyError.SINGLE_UNIT_OR_FORCE);
            return;
        }
        if (!entityList.isEmpty() && !validateUpdate(entityList)) {
            return;
        }
        if (!forceList.isEmpty() && !areForcesEditable(forceList)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }
        var forces = game().getForces();
        var changedForce = new HashSet<Force>();
        if (up) {
            if (!forceList.isEmpty()) {
                changedForce.addAll(forces.moveUp(CollectionUtil.anyOneElement(forceList)));
            } else if (!entityList.isEmpty()) {
                changedForce.addAll(forces.moveUp(CollectionUtil.anyOneElement(entityList)));
            }
        } else {
            if (!forceList.isEmpty()) {
                changedForce.addAll(forces.moveDown(CollectionUtil.anyOneElement(forceList)));
            } else if (!entityList.isEmpty()) {
                changedForce.addAll(forces.moveDown(CollectionUtil.anyOneElement(entityList)));
            }
        }

        if (!changedForce.isEmpty()) {
            client().sendUpdateForce(changedForce);
        }
    }

    /**
     * Asks for a name and creates a new top-level force of that name.
     */
    public void forceCreateEmpty() {
        // Ask for a name, offering ones that suit the side and read well on the radio
        Player owner = localPlayer();
        chooseForceName(owner, null,
              name -> client().sendAddForce(Force.createToplevelForce(name, owner), new ArrayList<>()));
    }

    /**
     * deletes empty force
     */
    public void forceDeleteEmpty(int forceId) {
        if (forceId == Force.NO_FORCE) {
            return;
        }

        Force force = client().getGame().getForces().getForce(forceId);

        if (force.getChildCount() != 0) {
            return;
        }

        List<Force> toDelete = new ArrayList<>();
        toDelete.add(force);
        client().sendDeleteForces(toDelete);
    }

    /**
     * Asks for a name and creates a new top-level force of that name with the selected entities in it.
     */
    public void forceCreateFrom(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (!areAllied(entities)) {
            asks().error(LobbyError.ONLY_TEAM);
            return;
        }
        // Ask for a name, offering ones that suit the side and read well on the radio
        Player owner = CollectionUtil.anyOneElement(entities).getOwner();
        chooseForceName(owner, null,
              name -> client().sendAddForce(Force.createToplevelForce(name, owner), entities));
    }

    /**
     * Asks for a name and creates a new sub force of that name for the force given as the parentId.
     */
    public void forceCreateSub(int parentId) {
        if (parentId == Force.NO_FORCE) {
            return;
        }
        // Ask for a name, offering ones that suit the side and read well on the radio
        chooseForceName(game().getForces().getOwner(parentId), null,
              name -> client().sendAddForce(Force.createSubforce(name, game().getForces().getForce(parentId)),
                    new ArrayList<>()));
    }

    /** Asks for a new name for the provided forceId and applies it. */
    public void forceRename(int forceId) {
        if (forceId == Force.NO_FORCE) {
            return;
        }
        Forces forces = game().getForces();
        if (!forces.contains(forceId)) {
            return;
        }
        Force force = forces.getForce(forceId);
        if (!isEditable(force)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }
        // Ask for a name, offering ones that suit the side and read well on the radio
        chooseForceName(forces.getOwner(forceId), force.getName(), name -> {
            if (!forces.contains(forceId)) {
                return;
            }
            forces.renameForce(name, forceId);
            var forceList = new ArrayList<>(List.of(force)); // must be mutable
            client().sendUpdateForce(forceList);
        });
    }

    private void chooseForceName(@Nullable Player owner, @Nullable String currentName, Consumer<String> chosen) {
        asks().chooseForceName(ForceNameRequest.of(game(), owner, currentName), chosen);
    }

    /**
     * Deletes the given forces and entities. Asks for confirmation if confirm is true.
     */
    public void delete(Collection<Force> foDelete, Collection<Entity> enDelete, boolean confirm) {
        Forces forces = game().getForces();
        // Remove redundant forces = subForces of other forces in the list
        Set<Force> allSubForces = new HashSet<>();
        foDelete.forEach(f -> allSubForces.addAll(forces.getFullSubForces(f)));
        foDelete.removeIf(allSubForces::contains);
        Set<Force> finalFoDelete = new HashSet<>(foDelete);
        // Remove redundant entities = entities in the given forces
        Set<Entity> inForces = new HashSet<>();
        foDelete.stream().map(forces::getFullEntities).map(ForceAssignable::filterToEntityList)
              .forEach(inForces::addAll);
        enDelete.removeIf(inForces::contains);
        Set<Entity> finalEnDelete = new HashSet<>(enDelete);

        if (!enDelete.isEmpty() && !validateUpdate(finalEnDelete)) {
            return;
        }
        if (!areForcesEditable(finalFoDelete)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }

        if (confirm) {
            int foCount = finalFoDelete.size();
            int enCount = finalEnDelete.size() + inForces.size();
            String question = "Really delete ";
            if (foCount > 0) {
                question += (foCount == 1 ? "one force" : foCount + " forces");
            }
            if (enCount > 0) {
                question += foCount > 0 ? " and " : "";
                question += (enCount == 1 ? "one unit" : enCount + " units");
            }
            question += "?";
            asks().confirm("Delete Units...", question, () -> sendDeletes(finalFoDelete, finalEnDelete));
            return;
        }
        sendDeletes(finalFoDelete, finalEnDelete);
    }

    private void sendDeletes(Set<Force> finalFoDelete, Set<Entity> finalEnDelete) {
        // Send a command to remove the forceless entities
        Set<Client> senders = finalEnDelete.stream().map(this::correctSender).collect(toSet());
        for (Client sender : senders) {
            // Gather the entities for this sending client;
            // Serialization doesn't like the toList() result, therefore the new ArrayList
            List<Integer> ids = finalEnDelete.stream()
                  .filter(e -> Objects.equals(correctSender(e), sender))
                  .map(Entity::getId)
                  .collect(Collectors.toList());
            sender.sendDeleteEntities(ids);
        }

        // Send a command to remove the forces (with entities)
        senders = finalFoDelete.stream().map(this::correctSender).collect(toSet());
        for (Client sender : senders) {
            List<Force> foList = finalFoDelete.stream()
                  .filter(f -> Objects.equals(correctSender(f), sender)).collect(Collectors.toList());
            sender.sendDeleteForces(foList);
        }
    }

    /**
     * Removes the given entities from their force(s), making them force-less. Entities must have a single owner and be
     * editable (local units or local bot's units) (Having multiple owners makes sending updates correctly for one's own
     * bots difficult)
     */
    public void forceRemoveEntity(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        client().sendAddEntitiesToForce(entities, Force.NO_FORCE);
    }

    /**
     * Add the entities to the force if admissible the entities must all be editable by the local player and be allied
     * to the force's owner.
     */
    public void forceAddEntity(Collection<Entity> entities, int forceId) {
        if (forceId == Force.NO_FORCE) {
            return;
        }
        Forces forces = game().getForces();
        if (!validateUpdate(entities) || !forces.contains(forceId)) {
            return;
        }
        var forceOwner = forces.getOwner(forceId);
        if (entities.stream().anyMatch(e -> e.getOwner().isEnemyOf(forceOwner))) {
            asks().error(LobbyError.ONLY_TEAM);
            return;
        }
        client().sendAddEntitiesToForce(entities, forceId);
    }

    /**
     * Changes the owner of the given forces to a different player without affecting force structure. When assigning the
     * force only to an enemy, it would dislodge that force from its parent and dislodge all units from it and leave it
     * an empty force for the enemy. That seems useless. Therefore, this is restricted to only assign to team members of
     * the former owner.
     */
    public void forceAssignOnly(Collection<Force> forceList, int newOwnerId) {
        if (newOwnerId == Player.PLAYER_NONE) {
            return;
        }
        Player newOwner = game().getPlayer(newOwnerId);
        if (newOwner == null) {
            return;
        }
        if (!areForcesEditable(forceList)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }
        Forces forces = game().getForces();
        if (forceList.stream().anyMatch(f -> newOwner.isEnemyOf(forces.getOwner(f)))) {
            asks().error(LobbyError.ONLY_TEAMMATE);
            return;
        }
        List<Force> changedForces = new ArrayList<>();
        for (Force force : forceList) {
            changedForces.addAll(forces.assignForceOnly(force, newOwner));
        }
        client().sendUpdateForce(changedForces);
    }

    /**
     * Changes the owner of the given forces to a different player together with all subForces and units.
     */
    public void forceAssignFull(Collection<Force> forceList, int newOwnerId) {
        if (newOwnerId == Player.PLAYER_NONE) {
            return;
        }
        Player newOwner = game().getPlayer(newOwnerId);
        if (newOwner == null) {
            return;
        }
        if (!areForcesEditable(forceList)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return;
        }
        client().sendAssignForceFull(forceList, newOwnerId);
    }

    // endregion

    // region C3

    /**
     * Disconnects the passed entities from their C3 network, if any. Due to the way C3 networks are represented in
     * Entity, units cannot disconnect from a C3 network with an id that is the entity's own id.
     */
    public void c3DisconnectFromNetwork(Collection<Entity> entities) {
        // Check if any entity is the master unit of its network (C3i/NC3/Nova CEWS)
        for (Entity entity : entities) {
            if (entity.hasNhC3()) {
                String networkId = entity.getC3NetId();
                if (networkId != null && networkId.endsWith(Entity.C3_NETWORK_ID_SEPARATOR + entity.getId())) {
                    asks().error(LobbyError.CANNOT_DISCONNECT_MASTER);
                    return;
                }
            }
        }

        if (!validateUpdate(entities)) {
            return;
        }

        sendUpdates(C3Util.disconnectFromNetwork(game(), entities));
    }

    /** Sets the entities' C3M to act as a Company Master. */
    public void c3SetCompanyMaster(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        try {
            C3Util.setCompanyMaster(entities);
        } catch (MissingC3MException e) {
            asks().error(LobbyError.ONLY_C3M);
            return;
        }
        sendUpdates(entities);
    }

    /** Sets the entities' C3M to act as a Lance Master (aka normal mode). */
    public void c3SetLanceMaster(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        try {
            C3Util.setLanceMaster(entities);
        } catch (MissingC3MException e) {
            asks().error(LobbyError.ONLY_C3M);
            return;
        }
        sendUpdates(entities);
    }

    /**
     * Connects the passed entities to a non-hierarchic C3 (NC3, C3i or Nova CEWS) identified by masterID.
     */
    public void c3JoinNh(Collection<Entity> entities, int masterID, boolean disconnectFirst) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (!areAllied(entities)) {
            asks().error(LobbyError.ONLY_TEAM);
            return;
        }
        try {
            C3Util.joinNh(game(), entities, masterID, disconnectFirst);
        } catch (MismatchingC3MException e) {
            asks().error(LobbyError.SAME_C3);
            return;
        } catch (C3CapacityException e) {
            asks().error(LobbyError.EXCEED_C3_CAPACITY);
            return;
        }
        sendUpdates(entities);
    }

    /**
     * Connects the passed entities to a standard C3M identified by masterID.
     */
    public void c3Connect(Collection<Entity> entities, int masterID, boolean disconnectFirst) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (!areAllied(entities)) {
            asks().error(LobbyError.ONLY_TEAM);
            return;
        }
        Set<Entity> updateCandidates;
        try {
            updateCandidates = C3Util.connect(game(), entities, masterID, disconnectFirst);
        } catch (MismatchingC3MException e) {
            asks().error(LobbyError.SAME_C3);
            return;
        } catch (C3CapacityException e) {
            asks().error(LobbyError.EXCEED_C3_CAPACITY);
            return;
        }
        sendUpdates(updateCandidates);
    }

    // endregion

    // region Transport

    /**
     * Loads the given units onto the loader, into the given bay.
     *
     * @param selEntities the units to load
     * @param loaderId    the id of the carrier
     * @param bayNumber   the bay, or the transporter for ProtoMek clamps
     * @param loadRear    {@code true} to load a ProtoMek onto the rear mag clamp
     */
    public void load(Collection<Entity> selEntities, int loaderId, int bayNumber, boolean loadRear) {
        Entity loader = game().getEntity(loaderId);
        // Remove those entities from the candidates that are already carried by that loader
        Collection<Entity> entities = new HashSet<>(selEntities);
        entities.removeIf(e -> e.getTransportId() == loaderId);
        if (entities.isEmpty()) {
            return;
        }

        // If a unit of the selected units is currently loaded onto another, 2nd unit of the selected units, do not
        // continue. The player should unload units first. This would require a server update offloading that second
        // unit AND embarking it. Currently not possible as a single server update and updates for one unit shouldn't
        // be chained.
        Set<Entity> carriers = entities.stream()
              .filter(e -> e.getTransportId() != Entity.NONE)
              .map(e -> game().getEntity(e.getTransportId())).collect(toSet());
        if (!Collections.disjoint(entities, carriers)) {
            asks().error(LobbyError.NO_DUAL_LOAD);
            return;
        }

        StringBuilder errorMsg = new StringBuilder();
        if (!LobbyRules.validateLobbyLoad(entities, loader, bayNumber, loadRear, errorMsg)) {
            asks().error(LobbyError.LOADING_BAY, errorMsg.toString());
            return;
        }

        for (Entity entity : entities) {
            if (entity.getTransportId() != Entity.NONE) {
                Entity formerLoader = game().getEntity(entity.getTransportId());
                if (formerLoader != null) {
                    Set<Entity> updateCandidates = new HashSet<>();
                    disembark(entity, updateCandidates);
                    if (!updateCandidates.isEmpty()) {
                        clientFor(formerLoader).sendUpdateEntity(formerLoader);
                    }
                }
            }
            loadOnto(entity, loaderId, bayNumber);
        }
    }

    /**
     * Embarks the given carried Entity onto the carrier given as carrierId.
     */
    public void loadOnto(Entity carried, int carrierId, int bayNumber) {
        Entity carrier = game().getEntity(carrierId);
        if (carrier == null || !isLoadable(carried, carrier)) {
            return;
        }

        // We need to make sure our current bomb choices fit onto the new fighter
        if (carrier instanceof FighterSquadron fSquad) {
            // We can't use Aero.getBombPoints() because the bombs haven't been loaded yet, only selected, so we have
            // to count the choices
            BombLoadout bombChoices = fSquad.getBombChoices();
            // We can't load all the squadrons bombs
            if (bombChoices.getTotalBombCost() > ((IBomber) carried).getMaxBombPoints()) {
                asks().error(LobbyError.SQUADRON_BOMBS);
                return;
            }
        }

        clientFor(carried).sendLoadEntity(carried.getId(), carrierId, bayNumber);
        // TODO: it would probably be a good idea to disable some settings for loaded units in customMekDialog
    }

    /**
     * Has the given trailer towed by the tractor of the given id.
     *
     * @param trailer  Entity to be towed
     * @param tractorId the id of the tractor
     */
    public void tow(Entity trailer, int tractorId) {
        Entity tractor = game().getEntity(tractorId);
        // Remove those entities from the candidates
        // that are already carried by that tractor/trailer
        if (trailer == null || tractor == null || trailer.getTractor() == tractorId
              || tractor.getTowing() != Entity.NONE || tractor.getTractor() == trailer.getId()) {
            return;
        }

        // If a unit of the selected units is currently
        // towed by another, 2nd unit of the selected
        // units, do not continue. The player should
        // detach units first. This would require a
        // server update detaching that second unit AND
        // re-attaching it. Currently not possible as a single
        // server update and updates for one unit shouldn't be chained.
        if (tractor.getTowing() != Entity.NONE && trailer.getTractor() != Entity.NONE) {
            asks().error(LobbyError.NO_DUAL_TOW);
        }

        towBy(trailer, tractorId);
    }

    /**
     * Set the provided trailer to be towed by the tractor.
     */
    public void towBy(Entity trailer, int tractorId) {
        Entity tractor = game().getEntity(tractorId);
        if (tractor == null || !tractor.canTow(trailer.getId())) {
            return;
        }

        clientFor(trailer).sendTowEntity(trailer.getId(), tractor.getId());
        // TODO: it would probably be a good idea to disable some settings for loaded units in customMekDialog
    }

    /**
     * Connects a multi-unit selection into a tractor-and-trailer train in one operation, after asking the player what
     * order the trailers should sit in.
     * <p>
     * Order is load-bearing rather than cosmetic: a unit draws ammunition only from its immediate neighbours, so the
     * carriage placed directly behind the gun is the only one that can feed it. The server validates and applies the
     * whole chain, so a rejected request leaves every unit unattached.
     * </p>
     */
    public void connectTrain(Collection<Entity> entities) {
        List<Entity> editable = new ArrayList<>(entities);
        editable.removeIf(this::isNotEditable);
        if (editable.size() != entities.size()) {
            logger.info("[Train] ignoring {} unit(s) in the selection that this player cannot edit",
                  entities.size() - editable.size());
        }

        List<Entity> candidateHeads = new ArrayList<>();
        List<Entity> trailers = new ArrayList<>();
        for (Entity entity : editable) {
            if (entity.getTractor() != Entity.NONE) {
                logger.info("[Train] cannot connect: {} is already part of a train", entity.getShortName());
                asks().error(LobbyError.ONLY_TRAIN_MEMBERS);
                return;
            }
            if (entity.isTrailer()) {
                trailers.add(entity);
            } else if (entity.isTractor()) {
                if (entity.getTowing() != Entity.NONE) {
                    // The server builds a train from an unattached tractor. Catch it here so the player gets a
                    // message instead of a dialog that quietly does nothing.
                    logger.info("[Train] cannot connect: {} already tows a trailer", entity.getShortName());
                    asks().error(LobbyError.SINGLE_TRACTOR_REQUIRED);
                    return;
                }
                candidateHeads.add(entity);
            } else {
                logger.info("[Train] cannot connect: {} is neither a tractor nor a trailer", entity.getShortName());
                asks().error(LobbyError.ONLY_TRAIN_MEMBERS);
                return;
            }
        }

        if (candidateHeads.size() != 1) {
            logger.info("[Train] cannot connect: found {} possible tractors, need exactly one",
                  candidateHeads.size());
            asks().error(LobbyError.SINGLE_TRACTOR_REQUIRED);
            return;
        }
        if (trailers.isEmpty()) {
            logger.info("[Train] cannot connect: no trailers in the selection");
            asks().error(LobbyError.ONLY_TRAIN_MEMBERS);
            return;
        }

        Entity head = candidateHeads.get(0);
        asks().orderTrain(head, trailers, ordered -> {
            List<Integer> orderedTrailerIds = new ArrayList<>();
            for (Entity trailer : ordered) {
                orderedTrailerIds.add(trailer.getId());
            }
            logger.info("[Train] requesting train: {} + {} trailer(s)", head.getShortName(),
                  orderedTrailerIds.size());
            clientFor(head).sendBuildTrain(head.getId(), orderedTrailerIds);
        });
    }

    /** Unloads all units from the given bay of the single given carrier. */
    public void unloadFromBay(Collection<Entity> entities, int bayId) {
        if (entities.size() != 1) {
            asks().error(LobbyError.SINGLE_UNIT, "offload from bays");
            return;
        }
        Entity carrier = CollectionUtil.anyOneElement(entities);
        if (!validateUpdate(Collections.singletonList(carrier))) {
            return;
        }
        Bay bay = carrier.getBayById(bayId);
        if (bay == null) {
            asks().error(LobbyError.NO_SUCH_BAY);
            return;
        }
        Set<Entity> updateCandidates = new HashSet<>();
        for (Entity loaded : bay.getLoadedUnits()) {
            disembark(loaded, updateCandidates);
        }
        sendUpdates(updateCandidates);
    }

    /**
     * Have the given entity disembark if it is carried by another unit. Entities that are modified and need an update
     * to be sent to the server are added to the given updateCandidates.
     */
    public void disembark(Entity entity, Collection<Entity> updateCandidates) {
        if (entity.getTransportId() == Entity.NONE) {
            return;
        }
        Entity carrier = game().getEntity(entity.getTransportId());
        if (carrier != null) {
            carrier.unload(entity);
            entity.setTransportId(Entity.NONE);
            updateCandidates.add(entity);
            updateCandidates.add(carrier);
        }
    }

    /**
     * Disembarks all given entities from any transports they are in.
     */
    public void disembarkAll(Collection<Entity> entities) {
        Set<Entity> updateCandidates = new HashSet<>();
        entities.stream().filter(this::isDirectlyEditable).forEach(e -> disembark(e, updateCandidates));
        sendUpdate(updateCandidates);
    }

    /**
     * Have the given entities offload all the units they are carrying. Entities that are modified are added to the
     * given updateCandidates.
     */
    public void offloadAll(Collection<Entity> entities, Collection<Entity> updateCandidates) {
        for (Entity carrier : directlyEditableEntities(entities)) {
            offloadFrom(carrier, updateCandidates);
        }
    }

    /**
     * Have the given entity offload all the units it is carrying. Entities that are modified are added to the given
     * updateCandidates.
     */
    public void offloadFrom(Entity entity, Collection<Entity> updateCandidates) {
        if (isDirectlyEditable(entity)) {
            for (Entity carriedUnit : entity.getLoadedUnits()) {
                disembark(carriedUnit, updateCandidates);
            }
        }
    }

    /**
     * Have the given entities detach from their tractors if it's a trailer. Entities that are modified and need an
     * update to be sent to the server are added to the given updateCandidates.
     */
    public void detachFromTractors(Set<Entity> trailers, Collection<Entity> updateCandidates) {
        for (Entity trailer : trailers) {
            detachFromTractor(trailer, updateCandidates);
        }
    }

    /**
     * Have the given entity detach from its tractor if it's a trailer. Entities that are modified and need an update to
     * be sent to the server are added to the given updateCandidates.
     */
    public void detachFromTractor(Entity trailer, Collection<Entity> updateCandidates) {
        if (trailer.getTowedBy() == Entity.NONE) {
            return;
        }
        // Detaching mid-train affects the whole train, so work from the tractor heading it rather than from the
        // trailer immediately in front, which would leave the rest of the train out of the update.
        Entity tractor = game().getEntity(trailer.getTractor());
        disconnectTrain(tractor, trailer, updateCandidates);
    }

    /**
     * Have the given entities detach their towed trailers. Entities that are modified and need an update to be sent to
     * the server are added to the given updateCandidates.
     */
    public void detachTrailers(Set<Entity> tractors, Collection<Entity> updateCandidates) {
        for (Entity tractor : tractors) {
            detachTrailer(tractor, updateCandidates);
        }
    }

    /**
     * Have the given entity detach a towed trailer. Entities that are modified and need an update to be sent to the
     * server are added to the given updateCandidates.
     */
    public void detachTrailer(Entity tractor, Collection<Entity> updateCandidates) {
        if (tractor.getTowing() == Entity.NONE) {
            return;
        }
        Entity trailer = game().getEntity(tractor.getTowing());
        disconnectTrain(tractor, trailer, updateCandidates);
    }

    private void disconnectTrain(Entity tractor, Entity trailer, Collection<Entity> updateCandidates) {
        if (tractor != null && trailer != null) {
            // An off board train stays hooked up. There is no board position to drop a trailer at, and the train is
            // the only thing holding the trailer's place off the map edge.
            if (tractor.isOffBoard() || trailer.isOffBoard()) {
                asks().error(LobbyError.NO_DETACH_OFF_BOARD);
                return;
            }
            // Copy the ids: disconnectUnit drops the detached trailers from this list as it works, and walking
            // the live view afterwards fails with a ConcurrentModificationException.
            List<Integer> otherTowedUnitIds = new ArrayList<>(tractor.getAllTowedUnits());
            tractor.disconnectUnit(trailer.getId());
            updateCandidates.add(trailer);
            updateCandidates.add(tractor);
            for (int otherTowedUnitId : otherTowedUnitIds) {
                Entity otherTowedUnit = game().getEntity(otherTowedUnitId);
                if (otherTowedUnit != null) {
                    updateCandidates.add(otherTowedUnit);
                }
            }
        }
    }

    /**
     * Creates a fighter squadron from the given list of entities. Checks if all entities are fighters and if the number
     * of entities does not exceed squadron capacity. Asks for a squadron name.
     */
    public void createSquadron(Collection<Entity> entities) {
        if (!validateUpdate(entities)) {
            return;
        }
        if (entities.stream().anyMatch(e -> !e.isFighter() || e instanceof FighterSquadron)) {
            asks().error(LobbyError.ONLY_FIGHTERS);
            return;
        }
        if (!areAllied(entities)) {
            asks().error(LobbyError.LOAD_ONLY_ALLIED);
            return;
        }
        boolean largeSquadrons = game().getOptions()
              .booleanOption(OptionsConstants.ADVANCED_AERO_RULES_ALLOW_LARGE_SQUADRONS);
        if ((!largeSquadrons && entities.size() > FighterSquadron.MAX_SIZE)
              || entities.size() > FighterSquadron.ALTERNATE_MAX_SIZE) {
            asks().error(LobbyError.SQUADRON_TOO_MANY);
        }

        // Ask for a squadron name, then actually create the squadron
        asks().chooseSquadronDesignation(name -> {
            FighterSquadron fs = new FighterSquadron(name);
            fs.setOwner(createSquadronOwner(entities));
            List<Integer> fighterIds = entities.stream().map(Entity::getId).collect(Collectors.toList());
            Objects.requireNonNull(correctSender(fs)).sendAddSquadron(fs, fighterIds);
        });
    }

    /**
     * Returns a likely owner client; if any of the fighter belongs to the local player, returns the local player. If
     * not, returns a local bot if any of the fighters belongs to that; finally, returns the owner of a random one of
     * the fighters.
     */
    private Player createSquadronOwner(Collection<Entity> entities) {
        if (entities.stream().anyMatch(e -> e.getOwner().equals(localPlayer()))) {
            return localPlayer();
        } else {
            for (Entry<String, AbstractClient> en : localBots().entrySet()) {
                Player bot = en.getValue().getLocalPlayer();
                if (entities.stream().anyMatch(e -> e.getOwner().equals(bot))) {
                    return en.getValue().getLocalPlayer();
                }
            }
        }

        Optional<Player> owner = entities.stream().map(Entity::getOwner).findAny();
        return owner.orElse(null);
    }

    // endregion

    // region Sending

    /**
     * Sends the entities in the given Collection to the Server. Sends only those that can be edited, i.e. the player's
     * own or his bots' units. Will separate the units into update packets for the local player and any local bots so
     * that the server accepts all changes as the server does not know of local bots and rejects updates that are not
     * for the sending client or its teammates.
     */
    public void sendUpdates(Collection<Entity> entities) {
        // Gather the necessary sending clients; this list may contain null if some units cannot be affected at all,
        // i.e. are enemies to local player and all his bots
        List<Client> senders = entities.stream().map(this::correctSender).distinct().toList();
        for (Client sender : senders) {
            if (sender == null) {
                continue;
            }
            sender.sendUpdateEntity(
                  new ArrayList<>(entities.stream()
                        .filter(e -> Objects.equals(correctSender(e), sender))
                        .collect(toList())));
        }
    }

    /**
     * Sends the entities in the given Collection to the Server one by one. Sends only those that the local player
     * owns or runs as a local bot, or all of them for a Game Master; see {@link #isDirectlyEditable(Entity)}.
     */
    public void sendUpdate(Collection<Entity> updateCandidates) {
        for (Entity e : directlyEditableEntities(updateCandidates)) {
            clientFor(e).sendUpdateEntity(e);
        }
    }

    /** Sends the given entities through the client of the given player, the local player or a local bot. */
    public void sendProxyUpdates(Collection<Entity> updateCandidates, Player player) {
        clientFor(player).sendUpdateEntity(updateCandidates);
    }

    /**
     * Returns the best sending client for an update of the given entity or {@code null} if none can be found (entity
     * is an enemy to the local player and all his bots, and the local player is not a gamemaster)
     */
    public @Nullable Client correctSender(Entity entity) {
        Player owner = entity.getOwner();
        if (localPlayer().equals(owner)) {
            return client();
        } else if (localBots().containsKey(owner.getName())) {
            return (Client) localBots().get(owner.getName());
        } else if (!localPlayer().isEnemyOf(owner)) {
            return client();
        } else {
            for (AbstractClient bot : localBots().values()) {
                if (!bot.getLocalPlayer().isEnemyOf(owner)) {
                    return (Client) bot;
                }
            }
        }

        // A gamemaster may update any unit; the server accepts GM updates sent from the local client
        if (localPlayer().isGameMaster()) {
            return client();
        }
        return null;
    }

    /**
     * Returns the best sending client for an update of the given force or null if none can be found (force is an enemy
     * to the local player and all his bots)
     */
    public @Nullable Client correctSender(Force force) {
        Player owner = game().getForces().getOwner(force);
        if (localPlayer().equals(owner)) {
            return client();
        } else if (localBots().containsKey(owner.getName())) {
            return (Client) localBots().get(owner.getName());
        } else if (!localPlayer().isEnemyOf(owner) || isEditable(force)) {
            return client();
        } else {
            for (AbstractClient bot : localBots().values()) {
                if (!bot.getLocalPlayer().isEnemyOf(owner)) {
                    return (Client) bot;
                }
            }
        }
        return null;
    }

    /**
     * Returns the Client associated with a given entity that may be configured by the local player (his own unit or one
     * of his bot's units). For a unit that cannot be configured (owned by a remote player) the client of the local
     * player is returned.
     */
    public Client clientFor(Entity entity) {
        return clientFor(entity.getOwner());
    }

    /**
     * Returns the client of the given player when it is a local bot, otherwise the local player's client.
     */
    public Client clientFor(Player player) {
        if (localBots().containsKey(player.getName())) {
            return (Client) localBots().get(player.getName());
        } else {
            return client();
        }
    }

    // endregion

    // region Permissions

    /**
     * Performs standard checks for updates (units must be present, visible, editable, and player is not done) and
     * returns false if that's not the case. Also tells the player why.
     */
    private boolean validateUpdate(Collection<Entity> entities) {
        if (entities.isEmpty()) {
            return false;
        }
        if (!isEditable(entities)) {
            asks().error(LobbyError.CANNOT_CONFIG_ENEMIES);
            return false;
        }
        if (!canSeeAll(entities)) {
            asks().error(LobbyError.CANNOT_VIEW_HIDDEN);
            return false;
        }

        if (localPlayer().isDone()) {
            asks().error(LobbyError.PLAYER_DONE);
            return false;
        }
        return true;
    }

    /**
     * Returns true when the given entity may be configured by the local player, i.e. if it is his own unit or one of
     * his bot's units, belongs to a force of his or his bots', or he is the Game Master.
     * <p>
     * Note that this is more restrictive than the Server is. The Server accepts entity changes also for teammates so
     * that entity updates that signal transporting a teammate's unit don't get rejected. I think it's important to
     * generally limit entity changes by other players to avoid collisions of updates.
     */
    public boolean isEditable(Entity entity) {
        boolean localGM = localPlayer().isGameMaster();
        return localGM
              || localBots().containsKey(entity.getOwner().getName())
              || (entity.getOwnerId() == localPlayer().getId())
              || (entity.partOfForce() && isSelfOrLocalBot(game().getForces().getOwner(entity.getForceId())))
              || (entity.partOfForce() && isEditable(game().getForces().getForce(entity)));
    }

    /**
     * Returns true when the given entity may NOT be configured by the local player.
     *
     * @see #isEditable(Entity)
     */
    public boolean isNotEditable(Entity entity) {
        return !isEditable(entity);
    }

    /**
     * Returns true when all given entities may be configured by the local player.
     *
     * @see #isEditable(Entity)
     */
    public boolean isEditable(Collection<Entity> entities) {
        return entities.stream().noneMatch(this::isNotEditable);
    }

    /**
     * Returns true when the given entity is the local player's own or a local bot's unit, or the local player is the
     * Game Master. Unlike {@link #isEditable(Entity)}, units of others in the local player's forces do not count.
     */
    public boolean isDirectlyEditable(Entity entity) {
        boolean localGM = localPlayer().isGameMaster();
        return localGM || localBots().containsKey(entity.getOwner().getName())
              || (entity.getOwnerId() == localPlayer().getId());
    }

    private Set<Entity> directlyEditableEntities(Collection<Entity> entities) {
        return entities.stream().filter(this::isDirectlyEditable).collect(Collectors.toSet());
    }

    /**
     * Returns true if the given carrier and carried can be edited to have the carrier transport the given carried
     * entity. That is the case when they are teammates and one of the entities can be edited by the local player. Note:
     * this method does NOT check if the loading is rules-valid.
     */
    private boolean isLoadable(Entity carried, Entity carrier) {
        return !carrier.getOwner().isEnemyOf(carried.getOwner())
              && (isDirectlyEditable(carrier) || isDirectlyEditable(carried));
    }

    /**
     * Returns true if the local player can see all the given entities. This is true except when a blind drop option is
     * active and one or more of the entities are not on his team.
     */
    public boolean canSeeAll(Collection<Entity> entities) {
        boolean localGM = localPlayer().isGameMaster();
        if (localGM || (!LobbyRules.isBlindDrop(game()) && !LobbyRules.isRealBlindDrop(game()))) {
            return true;
        }
        return entities.stream().noneMatch(this::isLocalEnemy);
    }

    /** Returns true when the player is the local player or one of the local bots. */
    public boolean isSelfOrLocalBot(Player player) {
        return localBots().containsKey(player.getName()) || localPlayer().equals(player);
    }

    /** Returns true if the entity is an enemy of the local player. */
    public boolean isLocalEnemy(Entity entity) {
        return localPlayer().isEnemyOf(entity.getOwner());
    }

    /**
     * A force is editable to the local player if any forces in its force chain (this includes the force itself) is
     * owned by the local player or one of the local bots. This allows editing forces of other players if they are a sub
     * force of a local/bot force.
     */
    public boolean isEditable(Force force) {
        List<Force> chain = game().getForces().forceChain(force);
        return chain.stream().map(f -> game().getForces().getOwner(f)).anyMatch(this::isSelfOrLocalBot);
    }

    /** Returns true when all the given forces are editable by the local player. */
    public boolean areForcesEditable(Collection<Force> forces) {
        return forces.stream().allMatch(this::isEditable);
    }

    /**
     * Returns true if no two of the given entities are enemies. This is true when all entities belong to a single
     * player. If they belong to different players, it is true when all belong to the same team and that team is one of
     * Teams 1 through 5 (not "No Team").
     * <p>
     * Returns true when entities is empty or has only one entity. The case of entities being empty should be considered
     * by the caller.
     */
    public boolean areAllied(Collection<Entity> entities) {
        if (entities.isEmpty()) {
            logger.warn(
                  "Empty collection of entities received, cannot determine if no entities are all allied. Returning true.");
            return true;
        }
        Entity randomEntry = entities.stream().findAny().get();
        return entities.stream().noneMatch(e -> e.isEnemyOf(randomEntry));
    }

    // endregion

    private Game game() {
        return session.game();
    }

    private Client client() {
        return session.client();
    }

    private Player localPlayer() {
        return session.localPlayer();
    }

    private Map<String, AbstractClient> localBots() {
        return session.localBots();
    }
}
