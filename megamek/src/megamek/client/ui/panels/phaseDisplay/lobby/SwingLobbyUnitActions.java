/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static megamek.client.lobby.LobbyUnitMenu.*;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import javax.imageio.ImageIO;
import javax.swing.JFrame;

import megamek.client.generator.ReconfigurationParameters;
import megamek.client.generator.TeamLoadOutGenerator;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.dialogs.BotCommands.BotFormationsMenuBuilder;
import megamek.client.ui.dialogs.BotCommands.BotOrderFacingDialog;
import megamek.client.ui.dialogs.iconChooser.CamoChooserDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.tileset.EntityImage;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.common.Player;
import megamek.common.Team;
import megamek.common.containers.MunitionTree;
import megamek.common.equipment.BombLoadout;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.net.packets.InvalidPacketDataException;
import megamek.common.options.OptionsConstants;
import megamek.common.orders.FormationShape;
import megamek.common.orders.UnitOrderAction;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.common.util.StringUtil;
import megamek.logging.MMLogger;
import megamek.server.commands.UnitOrderCommand;

/**
 * Runs the items of the lobby's unit and formation menu ({@link LobbyUnitMenu}), given as their command strings
 * {@code COMMAND|info|unit ids}, through the lobby commands; the few that need a Swing dialog of their own (the C3
 * manager, the sprite camouflage and the bot orders) open it on the client's frame, and the loadout and sprite files
 * are asked through the lobby's {@link LobbyFiles}.
 */
public final class SwingLobbyUnitActions {
    private static final MMLogger LOGGER = MMLogger.create(SwingLobbyUnitActions.class);

    private final ClientGUI clientGUI;
    private final LobbyCommands commands;
    private final LobbyFiles files;

    /** @param files the lobby's file routes, which the loadout and sprite items ask their files through */
    public SwingLobbyUnitActions(ClientGUI clientGUI, LobbyFiles files) {
        this.clientGUI = clientGUI;
        this.commands = clientGUI.lobbyCommands();
        this.files = files;
    }

    private Game game() {
        return clientGUI.getClient().getGame();
    }

    private JFrame frame() {
        return clientGUI.getFrame();
    }

    /** EDT: runs the menu item with this command string. */
    public void run(String commandString) {
        StringTokenizer st = new StringTokenizer(commandString, "|");
        String command = st.nextToken();
        // info need not be an int; loading uses the format "##:##"
        String info = st.nextToken();
        // The entities list may be empty
        Set<Entity> entities = LobbyUtility.getEntities(game(), st.nextToken());

        switch (command) {
            case LMP_CONFIGURE, LMP_TOW -> {
                if (!entities.isEmpty()) {
                    singleEntityAction(command, entities.iterator().next(), info);
                }
            }
            case LMP_MOVE_UP, LMP_MOVE_DOWN -> multiEntityAction(command, entities, info);
            case LMP_F_CREATE_SUB, LMP_F_ADD_TO, LMP_F_RENAME, LMP_F_CREATE_TOP, LMP_F_REMOVE, LMP_F_PROMOTE,
                  LMP_F_ASSIGN, LMP_F_ASSIGN_ONLY, LMP_F_CREATE_FROM, LMP_SBF_FORMATION, LMP_FC_DELETE_EMPTY ->
                  forceAction(command, entities, info);
            case LMP_SPRITE -> {
                if (!entities.isEmpty()) {
                    exportSprite(entities.iterator().next(), info);
                }
            }
            case LMP_BOT_FACING -> botFacing(entities);
            case LMP_BOT_FORMATION, LMP_BOT_FORMATION_OFF, LMP_BOT_ROLE -> botLanceAction(command, info);
            default -> {
                if (!entities.isEmpty()) {
                    multiEntityAction(command, entities, info);
                }
            }
        }
    }

    private void forceAction(String command, Set<Entity> entities, String info) {
        StringTokenizer stringTokenizer;
        int newOwnerId;
        switch (command) {
            case LMP_F_CREATE_SUB -> commands.forceCreateSub(StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_ADD_TO -> commands.forceAddEntity(entities, StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_RENAME -> commands.forceRename(StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_CREATE_TOP -> commands.forceCreateEmpty();
            case LMP_FC_DELETE_EMPTY -> commands.forceDeleteEmpty(StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_CREATE_FROM -> commands.forceCreateFrom(entities);
            case LMP_F_REMOVE -> commands.forceRemoveEntity(entities);
            case LMP_F_PROMOTE -> {
                StringTokenizer fst = new StringTokenizer(info, ",");
                Set<Integer> forceIds = new HashSet<>();
                while (fst.hasMoreTokens()) {
                    forceIds.add(StringUtil.toInt(fst.nextToken(), Force.NO_FORCE));
                }
                commands.forcePromote(forceIds);
            }
            case LMP_F_ASSIGN -> {
                stringTokenizer = new StringTokenizer(info, ":");
                newOwnerId = StringUtil.toInt(stringTokenizer.nextToken(), Player.PLAYER_NONE);
                commands.forceAssignFull(LobbyUtility.getForces(game(), stringTokenizer.nextToken()), newOwnerId);
            }
            case LMP_F_ASSIGN_ONLY -> {
                stringTokenizer = new StringTokenizer(info, ":");
                newOwnerId = StringUtil.toInt(stringTokenizer.nextToken(), Player.PLAYER_NONE);
                commands.forceAssignOnly(LobbyUtility.getForces(game(), stringTokenizer.nextToken()), newOwnerId);
            }
            case LMP_SBF_FORMATION -> commands.showSbfView(LobbyUtility.getForces(game(), info));
            default -> LOGGER.warn("Unknown lobby force command {}", command);
        }
    }

    private void multiEntityAction(String command, Set<Entity> entities, String info) {
        Set<Entity> updateCandidates = new HashSet<>();
        try {
            switch (command) {
                case LMP_INDI_CAMO -> commands.individualCamo(entities);
                case LMP_CONFIGURE_ALL -> commands.customizeMeks(entities);
                case LMP_DELETE -> commands.delete(LobbyUtility.getForces(game(), info), entities, true);
                case LMP_SKILLS -> commands.setRandomSkills(entities);
                case LMP_NAME -> commands.setRandomNames(entities);
                case LMP_CALLSIGN -> commands.setRandomCallSigns(entities);
                case LMP_RAPID_FIRE_MG_ON, LMP_RAPID_FIRE_MG_OFF ->
                      commands.toggleBurstMg(entities, command.equals(LMP_RAPID_FIRE_MG_ON));
                case LMP_HOT_LOAD_ON, LMP_HOT_LOAD_OFF ->
                      commands.toggleHotLoad(entities, command.equals(LMP_HOT_LOAD_ON));
                case LMP_VRT_LONG, LMP_VRT_SHORT -> commands.setVRTMode(entities, command.equals(LMP_VRT_LONG));
                case LMP_EI_ON, LMP_EI_OFF -> commands.toggleEI(entities, command.equals(LMP_EI_ON));
                case LMP_SQUADRON -> commands.createSquadron(entities);
                case LMP_LOAD -> load(entities, info);
                case LMP_UNLOAD -> commands.disembarkAll(entities);
                case LMP_UNLOAD_ALL -> {
                    commands.offloadAll(entities, updateCandidates);
                    commands.sendUpdate(updateCandidates);
                }
                case LMP_ASSIGN -> {
                    StringTokenizer st = new StringTokenizer(info, ":");
                    int newOwnerId = Integer.parseInt(st.nextToken());
                    commands.changeOwner(entities, LobbyUtility.getForces(game(), st.nextToken()), newOwnerId);
                }
                case LMP_DETACH_TRAILER -> {
                    commands.detachTrailers(entities, updateCandidates);
                    commands.sendUpdate(updateCandidates);
                }
                case LMP_CONNECT_TRAIN -> commands.connectTrain(entities);
                case LMP_DETACH_FROM_TRACTOR -> {
                    commands.detachFromTractors(entities, updateCandidates);
                    commands.sendUpdate(updateCandidates);
                }
                case LMP_DEPLOY -> commands.applyDeployment(entities, Integer.parseInt(info));
                case LMP_HEAT -> commands.applyHeat(entities, Integer.parseInt(info));
                case LMP_HIDDEN -> commands.applyHidden(entities, info.equals(LMP_HIDE));
                case LMP_SCAN_TARGET -> commands.applyScanTarget(entities, info.equals(LMP_SCAN_WANTED));
                case LMP_STAND -> commands.applyProne(entities, stance(info));
                case LMP_VIEW -> LobbyUtility.liveEntityReadoutAction(entities.stream().map(Entity::getId).toList(),
                      commands.canSeeAll(entities), frame(), game());
                case LMP_BV -> LobbyUtility.mekBVAction(entities, commands.canSeeAll(entities), false, frame());
                case LMP_COST -> LobbyUtility.mekCostAction(entities, commands.canSeeAll(entities), false, frame());
                case LMP_DAMAGE -> commands.configureDamage(entities);
                case LMP_SWAP -> commands.swapPilots(entities, Integer.parseInt(info));
                case LMP_C3DISCONNECT -> commands.c3DisconnectFromNetwork(entities);
                case LMP_C3CM -> commands.c3SetCompanyMaster(entities);
                case LMP_C3LM -> commands.c3SetLanceMaster(entities);
                case LMP_C3JOIN -> commands.c3JoinNh(entities, Integer.parseInt(info), false);
                case LMP_C3CONNECT -> commands.c3Connect(entities, Integer.parseInt(info), false);
                case LMP_C3_FORM_C3 -> commands.c3Connect(entities, Integer.parseInt(info), true);
                case LMP_C3_FORM_NHC3 -> commands.c3JoinNh(entities, Integer.parseInt(info), true);
                case LMP_C3_MANAGER -> new C3NetworkManagerDialog(clientGUI, entities).setVisible(true);
                case LMP_UNLOAD_ALL_FROM_BAY -> commands.unloadFromBay(entities, Integer.parseInt(info));
                case LMP_MOVE_DOWN -> commands.forceMove(LobbyUtility.getForces(game(), info), entities, false);
                case LMP_MOVE_UP -> commands.forceMove(LobbyUtility.getForces(game(), info), entities, true);
                case LMP_PRIORITY_TARGET -> commands.setPriorityTarget(info, entities);
                case LMP_ALPHA_STRIKE -> commands.showAlphaStrikeView(entities);
                case LMP_AUTOCONFIG, LMP_RANDOM_CONFIG, LMP_SAVE_CONFIG, LMP_APPLY_CONFIG ->
                      runMunitionConfigCMD(entities, command);
                default -> LOGGER.warn("Unknown lobby unit command {}", command);
            }
        } catch (InvalidPacketDataException e) {
            LOGGER.error("Invalid packet data:", e);
        }
    }

    /** Deploy prone, hull down or standing, as given by the menu's info string. */
    static LobbyCommands.Stance stance(String info) {
        if (info.equals(LMP_PRONE)) {
            return LobbyCommands.Stance.PRONE;
        } else if (info.equals(LMP_HULL_DOWN)) {
            return LobbyCommands.Stance.HULL_DOWN;
        }
        return LobbyCommands.Stance.STANDING;
    }

    /** @param info the loader id, the bay number and optionally whether to load onto the rear clamp, by colons */
    private void load(Collection<Entity> entities, String info) {
        StringTokenizer stLoad = new StringTokenizer(info, ":");
        int loaderId = Integer.parseInt(stLoad.nextToken());
        int bayNumber = Integer.parseInt(stLoad.nextToken());
        boolean loadRear = stLoad.hasMoreTokens() && Boolean.parseBoolean(stLoad.nextToken());
        commands.load(entities, loaderId, bayNumber, loadRear);
    }

    private void singleEntityAction(String command, Entity entity, String info) {
        if (command.equals(LMP_CONFIGURE)) {
            commands.customizeMek(entity);
        } else if (command.equals(LMP_TOW)) {
            commands.tow(entity, Integer.parseInt(new StringTokenizer(info, ":").nextToken()));
        }
    }

    private void runMunitionConfigCMD(Set<Entity> entities, String command) {
        TeamLoadOutGenerator tlg = new TeamLoadOutGenerator(game());
        MunitionTree munitionTree = new MunitionTree();
        ArrayList<Entity> entityArrayList = new ArrayList<>(entities);
        Team team = game().getTeamForPlayer(entityArrayList.getFirst().getOwner());
        String faction = (team != null) ? team.getFaction() : FactionRecord.IS_GENERAL_KEY;

        // Parameters are generated _from_ the teams' information, _for_ the selected entities
        ReconfigurationParameters reconfigurationParameters = tlg.generateParameters(entityArrayList, faction, team);
        // Extra nuke controls don't apply in the context menu; rely on game option!
        reconfigurationParameters.nukesBannedForMe = game().getOptions()
              .booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AT2_NUKES);
        // Reduce Pirate ammo somewhat; others get full loadouts
        reconfigurationParameters.isPirate = faction.equalsIgnoreCase("PIR");
        reconfigurationParameters.binFillPercent = (reconfigurationParameters.isPirate)
              ? TeamLoadOutGenerator.UNSET_FILL_RATIO : 1.0f;

        boolean reconfigured = false;
        switch (command) {
            case LMP_AUTOCONFIG -> {
                munitionTree = TeamLoadOutGenerator.generateMunitionTree(reconfigurationParameters, entityArrayList,
                      "");
                resetBombChoices(clientGUI, game(), entityArrayList);
                tlg.reconfigureEntities(entityArrayList, faction, munitionTree, reconfigurationParameters, null);
                reconfigured = true;
            }
            case LMP_RANDOM_CONFIG -> {
                munitionTree = TeamLoadOutGenerator.generateRandomizedMT();
                resetBombChoices(clientGUI, game(), entityArrayList);
                tlg.reconfigureEntities(entityArrayList, faction, munitionTree, reconfigurationParameters, null);
                reconfigured = true;
            }
            case LMP_SAVE_CONFIG -> {
                munitionTree.loadEntityList(entityArrayList);
                files.saveLoadout(munitionTree, () -> { });
            }
            case LMP_APPLY_CONFIG -> files.loadLoadout(loaded -> {
                if (loaded != null) {
                    // Use the unlimited availability map (all munitions allowed in any amount)
                    resetBombChoices(clientGUI, game(), entityArrayList);
                    HashMap<String, Object> availMap = TeamLoadOutGenerator.createUnlimitedAllMunitionsMap();
                    tlg.reconfigureEntities(entityArrayList, faction, loaded, reconfigurationParameters, availMap);
                    commands.sendProxyUpdates(entityArrayList,
                          game().getPlayer(entityArrayList.getFirst().getOwnerId()));
                }
            });
            default -> {
            }
        }
        if (reconfigured) {
            // Have to send reconfig as controlling player
            commands.sendProxyUpdates(entityArrayList, game().getPlayer(entityArrayList.getFirst().getOwnerId()));
        }
    }

    /** Clears the bomb choices of the bombers among the units and sends them as their owner's. */
    public static void resetBombChoices(ClientGUI clientgui, Game game, List<Entity> el) {
        ArrayList<Entity> resetBombers = new ArrayList<>();
        for (Entity entity : el) {
            if (entity.isBomber() && !entity.isVehicle()) {
                IBomber bomber = (IBomber) entity;
                bomber.setIntBombChoices(new BombLoadout());
                bomber.setExtBombChoices(new BombLoadout());
                resetBombers.add(entity);
            }
        }
        if (!resetBombers.isEmpty()) {
            clientgui.lobbyCommands().sendProxyUpdates(resetBombers, game.getPlayer(el.getFirst().getOwnerId()));
        }
    }

    /**
     * Opens the turret-style facing dialog for bot units: the "when stopped" facing is the one each unit deploys in
     * and holds whenever it stops, the "while moving" one it keeps on the move.
     */
    private void botFacing(Collection<Entity> selectedUnits) {
        if (selectedUnits.isEmpty()) {
            return;
        }
        List<Entity> units = new ArrayList<>(selectedUnits);
        units.sort(Comparator.comparingInt(Entity::getId));
        Entity first = units.getFirst();
        BotOrderFacingDialog dialog = new BotOrderFacingDialog(frame(), clientGUI, first,
              first.getUnitOrders().getFacingWhileMoving(), first.getUnitOrders().getFacingWhenStopped());
        if (dialog.showDialog() != DialogResult.CONFIRMED) {
            return;
        }
        for (Entity unit : units) {
            clientGUI.getClient().sendChat(UnitOrderCommand.commandText(unit.getId(), UnitOrderAction.FACING,
                  UnitOrderCommand.FACING_WHILE_MOVING + '=' + dialog.getFacingWhileMoving(),
                  UnitOrderCommand.FACING_WHEN_STOPPED + '=' + dialog.getFacingWhenStopped()));
        }
        LOGGER.info("[BotOrders] lobby facing: moving {}, stopped {} for {} unit(s)",
              dialog.getFacingWhileMoving(), dialog.getFacingWhenStopped(), units.size());
    }

    /** The Formation and Role items of a bot lance; the info is {@code [choice:]forceId}. */
    private void botLanceAction(String command, String info) {
        String[] parts = info.split(":");
        Force force = game().getForces().getForce(StringUtil.toInt(parts[parts.length - 1], Force.NO_FORCE));
        LobbyUnitMenu.BotLance lance = (force == null) ? null : botLance(game(), force);
        if (lance == null) {
            return;
        }
        switch (command) {
            case LMP_BOT_FORMATION -> BotFormationsMenuBuilder.sendFormation(clientGUI.getClient(), force,
                  lance.unitIds(), FormationShape.valueOf(parts[0]));
            case LMP_BOT_FORMATION_OFF -> BotFormationsMenuBuilder.sendFormationOff(clientGUI.getClient(),
                  lance.unitIds());
            case LMP_BOT_ROLE -> BotFormationsMenuBuilder.chooseLobbyRole(clientGUI.getClient(), frame(), game(),
                  lance.owner(), force, lance.unitIds(), switch (parts[0]) {
                      case ROLE_CONVOY -> Boolean.TRUE;
                      case ROLE_ESCORT -> Boolean.FALSE;
                      default -> null;
                  });
            default -> {
            }
        }
    }

    /** Exports the unit's sprite; {@code kind} is one of the {@code SPRITE_} kinds. */
    private void exportSprite(Entity entity, String kind) {
        switch (kind) {
            case SPRITE_CAMO -> exportSprite(entity, entity.getCamouflageOrElseOwners(), false);
            case SPRITE_DAMAGE -> exportSprite(entity, new Camouflage(), true);
            case SPRITE_CAMO_DAMAGE -> exportSprite(entity, entity.getCamouflageOrElseOwners(), true);
            case SPRITE_CHOSEN_CAMO, SPRITE_CHOSEN_CAMO_DAMAGE -> {
                CamoChooserDialog camoChooserDialog = new CamoChooserDialog(frame(),
                      entity.getCamouflageOrElseOwners());
                try {
                    if (camoChooserDialog.showDialog().isConfirmed()) {
                        exportSprite(entity, camoChooserDialog.getSelectedItem(),
                              kind.equals(SPRITE_CHOSEN_CAMO_DAMAGE));
                    }
                } finally {
                    camoChooserDialog.dispose();
                }
            }
            default -> LOGGER.warn("Unknown sprite export {}", kind);
        }
    }

    private void exportSprite(Entity entity, Camouflage camouflage, boolean showDamage) {
        files.exportSprite(entity.getDisplayName(), file -> {
            Image base = MMStaticDirectoryManager.getMekTileset().imageFor(entity);
            Image sprite = EntityImage.createLobbyIcon(base, camouflage, entity).loadPreviewImage(showDamage);
            try {
                ImageIO.write((BufferedImage) sprite, "png", file);
            } catch (Exception ex) {
                LOGGER.error(ex, "Failed to export to file");
            }
        });
    }
}
