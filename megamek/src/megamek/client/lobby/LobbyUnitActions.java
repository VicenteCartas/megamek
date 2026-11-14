/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static megamek.client.lobby.LobbyUnitMenu.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.function.Consumer;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.generator.ReconfigurationParameters;
import megamek.client.generator.TeamLoadOutGenerator;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ui.dialogs.BotCommands.MoveOrderCommands;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.Team;
import megamek.common.containers.MunitionTree;
import megamek.common.equipment.BombLoadout;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.net.packets.InvalidPacketDataException;
import megamek.common.options.OptionsConstants;
import megamek.common.orders.FormationOrder;
import megamek.common.orders.FormationShape;
import megamek.common.orders.LanceRole;
import megamek.common.orders.LanceRoles;
import megamek.common.orders.UnitOrderAction;
import megamek.common.units.Entity;
import megamek.common.units.IBomber;
import megamek.common.util.StringUtil;
import megamek.logging.MMLogger;
import megamek.server.commands.UnitOrderCommand;

/**
 * Runs the items of the lobby's unit and formation menu ({@link LobbyUnitMenu}), given as their command strings
 * {@code COMMAND|info|unit ids}, through the lobby commands. The few items that ask something of their own (the bot
 * facing and lance role, the sprite's camouflage) ask it through the {@link Host}, and the loadout and sprite files
 * through the lobby's {@link LobbyFiles}. The readout, BV, cost and C3 manager items are the lobby's own views and do
 * not come here.
 * <p>
 * Swing thread.
 */
public final class LobbyUnitActions {
    private static final MMLogger LOGGER = MMLogger.create(LobbyUnitActions.class);

    /** What the unit actions need of the lobby's window. Each call is on the Swing thread, as are its answers. */
    public interface Host {
        /** Shows the facing card; {@code confirmed} runs once it is confirmed with the form's facings. */
        void chooseFacing(BotFacingForm form, Runnable confirmed);

        /** Shows the lance role card; {@code confirmed} runs once it is confirmed with the form's role. */
        void chooseLanceRole(LanceRoleForm form, Runnable confirmed);

        /** Asks the camouflage to draw the unit's sprite in; {@code chosen} gets it once it is chosen. */
        void chooseCamo(Entity unit, Consumer<Camouflage> chosen);

        /** Draws the unit's lobby sprite in the camouflage, damaged or not, into the PNG file. */
        void writeSprite(Entity unit, Camouflage camouflage, boolean damage, File file);
    }

    private final Client client;
    private final LobbyCommands commands;
    private final LobbyFiles files;
    private final Host host;

    public LobbyUnitActions(Client client, LobbyCommands commands, LobbyFiles files, Host host) {
        this.client = client;
        this.commands = commands;
        this.files = files;
        this.host = host;
    }

    private Game game() {
        return client.getGame();
    }

    /** Runs the menu item with this command string. */
    public void run(String commandString) {
        StringTokenizer st = new StringTokenizer(commandString, "|");
        String command = st.nextToken();
        // info need not be an int; loading uses the format "##:##"
        String info = st.nextToken();
        // The entities list may be empty
        Set<Entity> entities = units(game(), st.hasMoreTokens() ? st.nextToken() : "");

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

    /** The units with these comma-separated ids, leaving out those not in the game. */
    public static Set<Entity> units(Game game, String idList) {
        Set<Entity> result = new HashSet<>();
        for (int id : ids(idList)) {
            Entity entity = game.getEntity(id);
            if (entity != null) {
                result.add(entity);
            }
        }
        return result;
    }

    /** The forces with these comma-separated ids, leaving out those not in the game. */
    public static Set<Force> forces(Game game, String idList) {
        Set<Force> result = new HashSet<>();
        for (int id : ids(idList)) {
            Force force = game.getForces().getForce(id);
            if (force != null) {
                result.add(force);
            }
        }
        return result;
    }

    private static List<Integer> ids(String idList) {
        List<Integer> ids = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(idList, ",");
        while (st.hasMoreTokens()) {
            ids.add(Integer.parseInt(st.nextToken().trim()));
        }
        return ids;
    }

    private void forceAction(String command, Set<Entity> entities, String info) {
        switch (command) {
            case LMP_F_CREATE_SUB -> commands.forceCreateSub(StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_ADD_TO -> commands.forceAddEntity(entities, StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_RENAME -> commands.forceRename(StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_CREATE_TOP -> commands.forceCreateEmpty();
            case LMP_FC_DELETE_EMPTY -> commands.forceDeleteEmpty(StringUtil.toInt(info, Force.NO_FORCE));
            case LMP_F_CREATE_FROM -> commands.forceCreateFrom(entities);
            case LMP_F_REMOVE -> commands.forceRemoveEntity(entities);
            case LMP_F_PROMOTE -> {
                Set<Integer> forceIds = new HashSet<>();
                StringTokenizer fst = new StringTokenizer(info, ",");
                while (fst.hasMoreTokens()) {
                    forceIds.add(StringUtil.toInt(fst.nextToken(), Force.NO_FORCE));
                }
                commands.forcePromote(forceIds);
            }
            case LMP_F_ASSIGN, LMP_F_ASSIGN_ONLY -> {
                StringTokenizer st = new StringTokenizer(info, ":");
                int newOwnerId = StringUtil.toInt(st.nextToken(), Player.PLAYER_NONE);
                Set<Force> forces = forces(game(), st.nextToken());
                if (command.equals(LMP_F_ASSIGN)) {
                    commands.forceAssignFull(forces, newOwnerId);
                } else {
                    commands.forceAssignOnly(forces, newOwnerId);
                }
            }
            case LMP_SBF_FORMATION -> commands.showSbfView(forces(game(), info));
            default -> LOGGER.warn("Unknown lobby force command {}", command);
        }
    }

    private void multiEntityAction(String command, Set<Entity> entities, String info) {
        Set<Entity> updateCandidates = new HashSet<>();
        try {
            switch (command) {
                case LMP_INDI_CAMO -> commands.individualCamo(entities);
                case LMP_CONFIGURE_ALL -> commands.customizeMeks(entities);
                case LMP_DELETE -> commands.delete(forces(game(), info), entities, true);
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
                    commands.changeOwner(entities, forces(game(), st.nextToken()), newOwnerId);
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
                case LMP_DAMAGE -> commands.configureDamage(entities);
                case LMP_SWAP -> commands.swapPilots(entities, Integer.parseInt(info));
                case LMP_C3DISCONNECT -> commands.c3DisconnectFromNetwork(entities);
                case LMP_C3CM -> commands.c3SetCompanyMaster(entities);
                case LMP_C3LM -> commands.c3SetLanceMaster(entities);
                case LMP_C3JOIN -> commands.c3JoinNh(entities, Integer.parseInt(info), false);
                case LMP_C3CONNECT -> commands.c3Connect(entities, Integer.parseInt(info), false);
                case LMP_C3_FORM_C3 -> commands.c3Connect(entities, Integer.parseInt(info), true);
                case LMP_C3_FORM_NHC3 -> commands.c3JoinNh(entities, Integer.parseInt(info), true);
                case LMP_UNLOAD_ALL_FROM_BAY -> commands.unloadFromBay(entities, Integer.parseInt(info));
                case LMP_MOVE_DOWN -> commands.forceMove(forces(game(), info), entities, false);
                case LMP_MOVE_UP -> commands.forceMove(forces(game(), info), entities, true);
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
                resetBombChoices(commands, game(), entityArrayList);
                tlg.reconfigureEntities(entityArrayList, faction, munitionTree, reconfigurationParameters, null);
                reconfigured = true;
            }
            case LMP_RANDOM_CONFIG -> {
                munitionTree = TeamLoadOutGenerator.generateRandomizedMT();
                resetBombChoices(commands, game(), entityArrayList);
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
                    resetBombChoices(commands, game(), entityArrayList);
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
    public static void resetBombChoices(LobbyCommands commands, Game game, List<Entity> units) {
        List<Entity> resetBombers = new ArrayList<>();
        for (Entity entity : units) {
            if (entity.isBomber() && !entity.isVehicle()) {
                IBomber bomber = (IBomber) entity;
                bomber.setIntBombChoices(new BombLoadout());
                bomber.setExtBombChoices(new BombLoadout());
                resetBombers.add(entity);
            }
        }
        if (!resetBombers.isEmpty()) {
            commands.sendProxyUpdates(resetBombers, game.getPlayer(units.getFirst().getOwnerId()));
        }
    }

    // ---- Bot orders

    /**
     * Asks the facings for bot units: the "when stopped" facing is the one each unit deploys in and holds whenever it
     * stops, the "while moving" one it keeps on the move. The first unit by id shows its facings.
     */
    private void botFacing(Collection<Entity> selectedUnits) {
        if (selectedUnits.isEmpty()) {
            return;
        }
        List<Entity> units = new ArrayList<>(selectedUnits);
        units.sort(Comparator.comparingInt(Entity::getId));
        Entity first = units.getFirst();
        BotFacingForm form = new BotFacingForm(first.getDisplayName(), first.getUnitOrders().getFacingWhileMoving(),
              first.getUnitOrders().getFacingWhenStopped());
        host.chooseFacing(form, () -> {
            for (Entity unit : units) {
                client.sendChat(UnitOrderCommand.commandText(unit.getId(), UnitOrderAction.FACING,
                      UnitOrderCommand.FACING_WHILE_MOVING + '=' + form.whileMoving(),
                      UnitOrderCommand.FACING_WHEN_STOPPED + '=' + form.whenStopped()));
            }
            LOGGER.info("[BotOrders] lobby facing: moving {}, stopped {} for {} unit(s)", form.whileMoving(),
                  form.whenStopped(), units.size());
        });
    }

    /**
     * The Formation and Role items of a bot lance, or the Formation items of a parent force with bot lances under it;
     * the info is {@code [choice:]forceId}.
     */
    private void botLanceAction(String command, String info) {
        String[] parts = info.split(":");
        Force force = game().getForces().getForce(StringUtil.toInt(parts[parts.length - 1], Force.NO_FORCE));
        if (force == null) {
            return;
        }
        LobbyUnitMenu.BotLance lance = botLance(game(), force);
        List<List<Integer>> lances = (lance != null) ? List.of(lance.unitIds())
              : MoveOrderCommands.formableLances(parentLances(game(), force));
        if (lances.isEmpty()) {
            return;
        }
        switch (command) {
            case LMP_BOT_FORMATION -> sendFormation(force, lances, FormationShape.valueOf(parts[0]));
            case LMP_BOT_FORMATION_OFF -> sendFormation(force, lances, null);
            case LMP_BOT_ROLE -> {
                // a role is a lance's job: a parent force's Role item only says to set it on each lance
                if (lance != null) {
                    chooseRole(lance, force, switch (parts[0]) {
                        case ROLE_CONVOY -> Boolean.TRUE;
                        case ROLE_ESCORT -> Boolean.FALSE;
                        default -> null;
                    });
                }
            }
            default -> {
            }
        }
    }

    /**
     * Puts each bot lance in formation, or takes it out of formation for a {@code null} shape: spacing
     * {@link FormationOrder#DEFAULT_SPACING}, each lance's first unit leading and keeping together, a convoy keeping
     * its Run pace and Push through (see {@link MoveOrderCommands#lobbyFormationCommands(Game, List, FormationShape)}).
     */
    private void sendFormation(Force force, List<List<Integer>> lances, @Nullable FormationShape shape) {
        // the role is read when the shape is picked, so a role set after the menu opened still counts
        for (String command : MoveOrderCommands.lobbyFormationCommands(game(), lances, shape)) {
            client.sendChat(command);
        }
        LOGGER.info("[BotOrders] lobby formation {} for {} ({} lance(s))", (shape == null) ? "off" : shape,
              force.getName(), lances.size());
    }

    /**
     * Sets a bot lance's role: {@code null} clears it, and a convoy or escort role asks the role card on that choice
     * first. An escort needs a lance on its side to guard.
     *
     * @param convoy {@code null} for None, else whether the lance is to be a convoy ({@code true}) or an escort
     */
    private void chooseRole(LobbyUnitMenu.BotLance lance, Force force, @Nullable Boolean convoy) {
        List<Entity> units = new ArrayList<>();
        for (int unitId : lance.unitIds()) {
            Entity unit = game().getEntity(unitId);
            if (unit != null) {
                units.add(unit);
            }
        }
        if (convoy == null) {
            sendRole(force, units, null);
            return;
        }
        LanceRole current = LanceRoles.roleOf(units);
        OffBoardDirection defaultExitEdge = LanceRoles.defaultExitEdge(units.isEmpty() ? null : units.get(0));
        LanceRoleForm form;
        if (convoy) {
            form = new LanceRoleForm(force.getName(), List.of(), defaultExitEdge,
                  ((current != null) && current.isConvoy()) ? current : LanceRole.convoy(defaultExitEdge));
        } else {
            List<LanceRoles.ConvoyChoice> convoys = LanceRoles.convoyChoices(game(), lance.owner(), force.getId());
            if (convoys.isEmpty()) {
                return;
            }
            form = new LanceRoleForm(force.getName(), convoys, defaultExitEdge,
                  ((current != null) && current.isEscort()) ? current
                        : LanceRole.defaultEscort(convoys.get(0).forceId()));
        }
        host.chooseLanceRole(form, () -> {
            if (!form.complete()) {
                return;
            }
            form.lanceToMakeConvoy().ifPresent(guarded -> {
                // the lance given an escort that was not a convoy yet becomes one
                for (String command : MoveOrderCommands.makeConvoyCommands(
                      LanceRoles.unitsOf(game(), guarded.forceId()), guarded.newExitEdge())) {
                    client.sendChat(command);
                }
                LOGGER.info("[BotOrders] lobby: {} made a convoy, leaving by the {} edge, to be escorted by {}",
                      guarded.name(), guarded.newExitEdge(), force.getName());
            });
            sendRole(force, units, form.role());
        });
    }

    private void sendRole(Force lance, List<Entity> units, @Nullable LanceRole role) {
        for (String command : MoveOrderCommands.roleCommands(units, role)) {
            client.sendChat(command);
        }
        boolean isAnyInFormation = false;
        for (Entity unit : units) {
            isAnyInFormation |= unit.getUnitOrders().getFormation().isPresent();
        }
        List<String> columnCommands = MoveOrderCommands.convoyColumnCommands(units, role);
        for (String command : columnCommands) {
            client.sendChat(command);
        }
        String formationNote = "";
        if (!columnCommands.isEmpty()) {
            formationNote = isAnyInFormation ? ", keeping its shape at a convoy's Run pace and Push through"
                  : ", put in a Column to travel";
        }
        LOGGER.info("[BotOrders] lobby role {} for {} ({} units){}", (role == null) ? LanceRole.NONE_TEXT : role,
              lance.getName(), units.size(), formationNote);
    }

    // ---- Sprites

    /** Exports the unit's sprite; {@code kind} is one of the {@code SPRITE_} kinds. */
    private void exportSprite(Entity entity, String kind) {
        switch (kind) {
            case SPRITE_CAMO -> exportSprite(entity, entity.getCamouflageOrElseOwners(), false);
            case SPRITE_DAMAGE -> exportSprite(entity, new Camouflage(), true);
            case SPRITE_CAMO_DAMAGE -> exportSprite(entity, entity.getCamouflageOrElseOwners(), true);
            case SPRITE_CHOSEN_CAMO, SPRITE_CHOSEN_CAMO_DAMAGE -> host.chooseCamo(entity,
                  camo -> exportSprite(entity, camo, kind.equals(SPRITE_CHOSEN_CAMO_DAMAGE)));
            default -> LOGGER.warn("Unknown sprite export {}", kind);
        }
    }

    private void exportSprite(Entity entity, Camouflage camouflage, boolean showDamage) {
        files.exportSprite(entity.getDisplayName(), file -> host.writeSprite(entity, camouflage, showDamage, file));
    }
}
