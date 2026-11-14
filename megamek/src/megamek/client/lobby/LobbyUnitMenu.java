/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static megamek.common.util.CollectionUtil.anyOneElement;
import static megamek.common.util.CollectionUtil.theElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.dialogs.BotCommands.MoveOrderCommands;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.battleArmor.ProtoMekClampMount;
import megamek.common.bays.Bay;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.Transporter;
import megamek.common.equipment.WeaponType;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.game.Game;
import megamek.common.interfaces.ForceAssignable;
import megamek.common.options.OptionsConstants;
import megamek.common.orders.FormationShape;
import megamek.common.orders.LanceRole;
import megamek.common.orders.LanceRoles;
import megamek.common.orders.OrderEligibility;
import megamek.common.rules.SettableHeat;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.FighterSquadron;
import megamek.common.units.Jumpship;
import megamek.common.units.ProtoMek;
import megamek.common.util.C3Util;
import megamek.logging.MMLogger;

/**
 * The lobby's unit and force menu for a selection of units and forces, built from the game as the Swing lobby built
 * its right-click menu, with the same items, submenus and enablement. Each item carries a command of the form
 * {@code COMMAND|INFO|ID1,ID2,...} (-1 where something is not needed), which the native lobby runs. It needs no
 * toolkit, so it can be tested on its own.
 */
public final class LobbyUnitMenu {
    private static final MMLogger logger = MMLogger.create(LobbyUnitMenu.class);

    public static final String LMP_SKILLS = "SKILLS";
    public static final String LMP_CALLSIGN = "CALLSIGN";
    public static final String LMP_NAME = "NAME";
    /** The Randomize submenu, by which a unit's card leaves it out of its menu. */
    public static final String LMP_RANDOMIZE = "RANDOMIZE";
    /** The C3 submenu, by which a unit's card leaves it out of its menu. */
    public static final String LMP_C3 = "C3";
    public static final String LMP_C3DISCONNECT = "C3DISCONNECT";
    public static final String LMP_DEPLOY = "DEPLOY";
    public static final String LMP_HEAT = "HEAT";
    public static final String LMP_HULL_DOWN = "HULLDOWN";
    public static final String LMP_PRONE = "PRONE";
    public static final String LMP_STAND = "STAND";
    public static final String LMP_NO_HIDE = "NOHIDE";
    public static final String LMP_HIDE = "HIDE";
    public static final String LMP_HIDDEN = "HIDDEN";
    public static final String LMP_SCAN_TARGET = "SCAN_TARGET";
    public static final String LMP_SCAN_WANTED = "SCAN_WANTED";
    public static final String LMP_SCAN_NOT_WANTED = "SCAN_NOT_WANTED";
    public static final String LMP_F_ASSIGN_ONLY = "FASSIGNONLY";
    public static final String LMP_F_ASSIGN = "FASSIGN";
    public static final String LMP_RAPID_FIRE_MG_OFF = "RAPIDFIREMG_OFF";
    public static final String LMP_RAPID_FIRE_MG_ON = "RAPIDFIREMG_ON";
    public static final String LMP_HOT_LOAD_OFF = "HOTLOAD_OFF";
    public static final String LMP_HOT_LOAD_ON = "HOTLOAD_ON";
    public static final String LMP_VRT_LONG = "VRT_LONG";
    public static final String LMP_VRT_SHORT = "VRT_SHORT";
    public static final String LMP_EI_ON = "EI_ON";
    public static final String LMP_EI_OFF = "EI_OFF";
    public static final String LMP_SWAP = "SWAP";
    public static final String LMP_ASSIGN = "ASSIGN";
    public static final String LMP_C3CONNECT = "C3CONNECT";
    public static final String LMP_C3JOIN = "C3JOIN";
    public static final String LMP_C3_FORM_NHC3 = "C3FORMNHC3";
    public static final String LMP_C3_FORM_C3 = "C3FORMC3";
    public static final String LMP_C3_MANAGER = "C3MANAGER";
    public static final String LMP_C3LM = "C3LM";
    public static final String LMP_C3CM = "C3CM";
    public static final String LMP_SQUADRON = "SQUADRON";
    public static final String LMP_LOAD = "LOAD";
    public static final String LMP_TOW = "TOW";
    public static final String LMP_F_ADD_TO = "FADDTO";
    public static final String LMP_F_REMOVE = "FREMOVE";
    public static final String LMP_F_PROMOTE = "FPROMOTE";
    public static final String LMP_F_RENAME = "FRENAME";
    public static final String LMP_F_CREATE_SUB = "FCREATESUB";
    public static final String LMP_F_CREATE_TOP = "FCREATETOP";
    public static final String LMP_F_CREATE_FROM = "FCREATEFROM";
    public static final String LMP_FC_DELETE_EMPTY = "FCDELETEEMPTY";
    public static final String LMP_SBF_FORMATION = "SBFFORMATION";
    public static final String LMP_DELETE = "DELETE";
    public static final String LMP_UNLOAD_ALL = "UNLOADALL";
    public static final String LMP_UNLOAD = "UNLOAD";
    public static final String LMP_DETACH_FROM_TRACTOR = "DETACHFROMTRACTOR";
    public static final String LMP_DETACH_TRAILER = "DETACHTRAILER";
    public static final String LMP_CONNECT_TRAIN = "CONNECTTRAIN";
    public static final String LMP_MOVE_DOWN = "MOVE_DOWN";
    public static final String LMP_INDI_CAMO = "INDI_CAMO";
    public static final String LMP_DAMAGE = "DAMAGE";
    public static final String LMP_CONFIGURE_ALL = "CONFIGURE_ALL";
    public static final String LMP_CONFIGURE = "CONFIGURE";
    public static final String LMP_BV = "BV";
    public static final String LMP_COST = "COST";
    public static final String LMP_VIEW = "VIEW";
    public static final String LMP_MOVE_UP = "MOVE_UP";
    public static final String LMP_PRIORITY_TARGET = "PRIO_TARGET";
    public static final String LMP_ALPHA_STRIKE = "ALPHASTRIKE";
    public static final String LMP_AUTOCONFIG = "AUTOCONFIG";
    public static final String LMP_RANDOM_CONFIG = "RANDOMCONFIG";
    public static final String LMP_SAVE_CONFIG = "SAVECONFIG";
    public static final String LMP_APPLY_CONFIG = "APPLYCONFIG";

    private static final String NO_INFO = "|-1";

    public static final String LMP_UNLOAD_ALL_FROM_BAY = "UNLOADALLFROMBAY";

    /** Heat values listed directly in the heat menu; higher values sit in its "More heat" submenu. */
    private static final int HEAT_WITHOUT_SUBMENU = 10;
    /** How many heat values each group of the "More heat" submenu holds. */
    private static final int HEAT_GROUP_SIZE = 10;

    /** Exports the selected unit's sprite; the info is one of the {@code SPRITE_} kinds. */
    public static final String LMP_SPRITE = "SPRITE";
    public static final String SPRITE_CAMO = "CAMO";
    public static final String SPRITE_DAMAGE = "DAMAGE";
    public static final String SPRITE_CAMO_DAMAGE = "CAMO_DAMAGE";
    public static final String SPRITE_CHOSEN_CAMO = "CHOSEN_CAMO";
    public static final String SPRITE_CHOSEN_CAMO_DAMAGE = "CHOSEN_CAMO_DAMAGE";
    /** Opens the bot facing dialog for the units. */
    public static final String LMP_BOT_FACING = "BOT_FACING";
    /** Puts a bot lance in formation; the info is the shape and the force id, as {@code SHAPE:forceId}. */
    public static final String LMP_BOT_FORMATION = "BOT_FORMATION";
    /** Takes a bot lance out of formation; the info is the force id. */
    public static final String LMP_BOT_FORMATION_OFF = "BOT_FORMATION_OFF";
    /** Sets a bot lance's role; the info is NONE, CONVOY or ESCORT and the force id, as {@code ROLE:forceId}. */
    public static final String LMP_BOT_ROLE = "BOT_ROLE";
    public static final String ROLE_NONE = "NONE";
    public static final String ROLE_CONVOY = "CONVOY";
    public static final String ROLE_ESCORT = "ESCORT";

    private final List<Node> nodes;

    private LobbyUnitMenu(Item popup, Set<String> omit) {
        nodes = nodes(popup.children.stream().filter(item -> {
            String name = (item.command == null) ? item.key : name(item.command);
            return name == null || !omit.contains(name);
        }).toList());
    }

    /**
     * The unit and force menu for the given selection; {@code forceView} adds the items that act on the force tree,
     * such as Move Up and Move Down, as the force view shows them.
     *
     * @param client   the local client
     * @param commands the lobby's commands, which say what the local player may edit
     */
    public static LobbyUnitMenu of(Client client, LobbyCommands commands, List<Entity> entities, List<Force> forces,
          boolean forceView) {
        return of(client, commands, entities, forces, forceView, Set.of());
    }

    /**
     * As {@link #of(Client, LobbyCommands, List, List, boolean)}, leaving out the top-level items whose commands are in
     * {@code omit}, such as those a unit's card already offers as icons.
     *
     * @param omit command names such as {@link #LMP_VIEW}
     */
    public static LobbyUnitMenu of(Client client, LobbyCommands commands, List<Entity> entities, List<Force> forces,
          boolean forceView, Set<String> omit) {
        return new LobbyUnitMenu(getPopup(client, commands, entities, forces, forceView), omit);
    }

    /** @return the command that runs the menu item {@code name}, such as {@link #LMP_VIEW}, on one unit */
    public static String unitCommand(String name, int unitId) {
        return name + NO_INFO + "|" + unitId;
    }

    /** @return the name of {@code command}, such as {@link #LMP_VIEW}, without its information and units */
    public static String name(String command) {
        int bar = command.indexOf('|');
        return bar < 0 ? command : command.substring(0, bar);
    }

    /** @return the menu's entries */
    public List<LobbyMenuEntry> entries() {
        return nodes.stream().map(Node::entry).toList();
    }

    /**
     * @param path indices into {@link #entries()} and their children
     *
     * @return the command of the enabled item at {@code path}, or null when there is none
     */
    public @Nullable String command(List<Integer> path) {
        List<Node> level = nodes;
        Node node = null;
        for (int index : path) {
            if (index < 0 || index >= level.size()) {
                return null;
            }
            node = level.get(index);
            level = node.children();
        }
        if (node == null || node.item() == null || !node.entry().enabled() || node.entry().submenu()) {
            return null;
        }
        return node.item().command;
    }

    /** An entry and the item behind it. */
    private record Node(LobbyMenuEntry entry, @Nullable Item item, List<Node> children) { }

    /** Reads items as the native lobby shows them: spacers become separators, never doubled or at either end. */
    private static List<Node> nodes(List<Item> items) {
        List<Node> nodes = new ArrayList<>();
        for (Item item : items) {
            if (!item.visible) {
                continue;
            }
            if (item.separator || (!item.menu && !item.enabled && item.text.isBlank())) {
                if (!nodes.isEmpty() && !nodes.getLast().entry().separator()) {
                    nodes.add(new Node(LobbyMenuEntry.SEPARATOR, null, List.of()));
                }
            } else if (item.menu) {
                List<Node> children = nodes(item.children);
                List<LobbyMenuEntry> entries = children.stream().map(Node::entry).toList();
                nodes.add(new Node(new LobbyMenuEntry(item.text.strip(), item.enabled && !children.isEmpty(), null,
                      item.tooltip.strip(), entries, false), item, children));
            } else {
                nodes.add(new Node(new LobbyMenuEntry(item.text.strip(), item.enabled, null, item.tooltip.strip(),
                      List.of(), false), item, List.of()));
            }
        }
        if (!nodes.isEmpty() && nodes.getLast().entry().separator()) {
            nodes.removeLast();
        }
        return nodes;
    }

    /** A menu item, submenu or separator as the menu is built. */
    static final class Item {
        private final String text;
        private final @Nullable String command;
        private final boolean menu;
        private final boolean separator;
        /** The name {@code omit} knows a submenu by, which has no command of its own, or null. */
        private @Nullable String key;
        private boolean enabled = true;
        private boolean visible = true;
        private String tooltip = "";
        private final List<Item> children = new ArrayList<>();

        private Item(String text, @Nullable String command, boolean menu, boolean separator) {
            this.text = text;
            this.command = command;
            this.menu = menu;
            this.separator = separator;
        }

        /** An item running {@code command}. */
        Item(String text, String command) {
            this(text, command, false, false);
        }

        static Item menu(String text) {
            return new Item(text, null, true, false);
        }

        /** A small empty item between groups. */
        static Item spacer() {
            Item spacer = new Item("", null, false, false);
            spacer.enabled = false;
            return spacer;
        }

        Item add(Item item) {
            children.add(item);
            return item;
        }

        void addSeparator() {
            children.add(new Item("", null, false, true));
        }

        int getItemCount() {
            return children.size();
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        void setVisible(boolean visible) {
            this.visible = visible;
        }

        void setToolTipText(String tooltip) {
            this.tooltip = tooltip;
        }
    }

    private static Item menuItem(String text, String command, boolean enabled) {
        Item item = new Item(text, command);
        item.setEnabled(enabled);
        return item;
    }

    private static Item getPopup(Client client, LobbyCommands commands, List<Entity> entities, List<Force> forces,
          boolean forceView) {
        Game game = client.getGame();
        var opts = game.getOptions();

        boolean optBurstMG = opts.booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_BURST);
        boolean optLRMHotLoad = opts.booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HOT_LOAD);
        boolean optCapFighters = opts.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_STRATOPS_CAPITAL_FIGHTER);

        // A set of all selected entities and all entities in selected forces and their
        // subForces
        Set<Entity> joinedEntities = new HashSet<>(entities);
        for (Force force : forces) {
            joinedEntities.addAll(ForceAssignable.filterToEntityList(game.getForces().getFullEntities(force)));
        }

        // Find certain unit features among all units the player can access
        // Used to hide some menu items entirely like "Form Squadron" when there's no
        // fighter in the game
        HashSet<Entity> accessibleEntities = new HashSet<>(game.getEntitiesVector());
        accessibleEntities.removeIf(commands::isNotEditable);
        boolean accessibleFighters = accessibleEntities.stream().anyMatch(Entity::isFighter);
        boolean accessibleTransportBays = accessibleEntities.stream().anyMatch(e -> !e.getTransportBays().isEmpty());
        boolean accessibleCarriers = accessibleEntities.stream().anyMatch(e -> !e.getLoadedUnits().isEmpty());
        boolean accessibleTractors = accessibleEntities.stream().anyMatch(e -> e.getTowing() != Entity.NONE);
        boolean accessibleTrailers = accessibleEntities.stream().anyMatch(e -> e.getTowedBy() != Entity.NONE);

        // Find what can be done with the selected entities incl. those in selected
        // forces
        boolean anyCarrier = joinedEntities.stream().anyMatch(e -> !e.getLoadedUnits().isEmpty());
        boolean noneEmbarked = joinedEntities.stream().allMatch(e -> e.getTransportId() == Entity.NONE);
        boolean anyTractor = joinedEntities.stream().anyMatch(e -> e.getTowing() != Entity.NONE);
        boolean anyTrailer = joinedEntities.stream().anyMatch(e -> e.getTowedBy() != Entity.NONE);

        boolean allProtoMeks = !joinedEntities.isEmpty()
              && joinedEntities.stream().allMatch(e -> e instanceof ProtoMek);
        boolean anyRFMGOn = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasRapidFireMG);
        boolean anyRFMGOff = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasNormalFireMG);
        boolean anyHLOn = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasHotLoaded);
        boolean anyHLOff = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasNonHotLoaded);
        boolean anyVRTLong = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasVRTLong);
        boolean anyVRTShort = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasVRTShort);
        boolean anyVRT = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasVRT);
        boolean anyEIOn = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasEIOn);
        boolean anyEIOff = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasEIOff);
        boolean anyEI = joinedEntities.stream().anyMatch(LobbyUnitMenu::hasEI);

        boolean oneSelected = entities.size() == 1;
        boolean hasJoinedEntities = !joinedEntities.isEmpty();
        boolean joinedOneEntitySelected = oneSelected && forces.isEmpty();

        Item popup = Item.menu("");

        // All command strings should follow the layout COMMAND|INFO|ID1,ID2,I3...
        // and use -1 when something is not needed (COMMAND|-1|-1)
        String eId = "|" + (entities.isEmpty() ? "-1" : entities.getFirst().getId());
        String eIds = enToken(entities);
        String seIds = enToken(joinedEntities);

        popup.add(menuItem("View...", LMP_VIEW + NO_INFO + seIds, hasJoinedEntities));
        popup.add(Item.spacer());

        if (joinedOneEntitySelected) {
            popup.add(
                  menuItem("Configure...", LMP_CONFIGURE + NO_INFO + eId, hasJoinedEntities));
        } else {
            popup.add(menuItem("Configure...", LMP_CONFIGURE_ALL + NO_INFO + seIds, hasJoinedEntities));
        }
        boolean canEditDamage = hasJoinedEntities && joinedEntities.stream()
              .allMatch(entity -> LobbyCommands.canEditDamage(client, entity));
        Item damageItem = menuItem(Messages.getString("Lobby.status.menu"), LMP_DAMAGE + NO_INFO + seIds,
              hasJoinedEntities);
        if (!canEditDamage) {
            // say why the damage shows but cannot be changed
            damageItem.setToolTipText(Messages.getString("Lobby.status.readOnly"));
        }
        popup.add(damageItem);
        popup.add(menuItem("Set individual camo...", LMP_INDI_CAMO + NO_INFO + seIds, hasJoinedEntities));

        if (forceView) {
            Item moveUp = menuItem("Move Up", LMP_MOVE_UP + "|" + foToken(forces) + eIds,
                  !forces.isEmpty() || !entities.isEmpty());
            popup.add(moveUp);
            Item moveDn = menuItem("Move Down", LMP_MOVE_DOWN + "|" + foToken(forces) + eIds,
                  !forces.isEmpty() || !entities.isEmpty());
            popup.add(moveDn);
        }

        popup.add(deployMenu(client, hasJoinedEntities, joinedEntities));
        popup.add(randomizeMenu(hasJoinedEntities, seIds));
        popup.add(munitionsConfigMenu(hasJoinedEntities, joinedEntities));
        popup.add(swapPilotMenu(hasJoinedEntities, joinedEntities, client));
        popup.add(priorityTargetMenu(client, hasJoinedEntities, joinedEntities));

        if (optBurstMG || optLRMHotLoad || anyVRT || anyEI) {
            popup.add(equipMenu(anyRFMGOn, anyRFMGOff, anyHLOn, anyHLOff, anyVRTLong, anyVRTShort, anyVRT,
                  anyEIOn, anyEIOff, anyEI, optLRMHotLoad, optBurstMG, seIds));
        }

        popup.add(Item.spacer());
        popup.add(changeOwnerMenu(!entities.isEmpty() || !forces.isEmpty(), client, entities, forces));
        popup.add(loadMenu(client, true, joinedEntities));
        if (entities.size() == 1) {
            popup.add(towMenu(client, true, entities.getFirst()));
        }

        // Connecting several units at once. Offered whenever the selection could plausibly form a train; the exact
        // ordering is chosen in the dialog and the server has the final say on legality.
        boolean anyFreeTrailerSelected = joinedEntities.stream()
              .anyMatch(entity -> entity.isTrailer() && (entity.getTractor() == Entity.NONE));
        boolean anyFreeTractorSelected = joinedEntities.stream()
              .anyMatch(entity -> entity.isTractor() && (entity.getTractor() == Entity.NONE)
                    && (entity.getTowing() == Entity.NONE));
        if ((joinedEntities.size() > 1) && anyFreeTrailerSelected && anyFreeTractorSelected) {
            popup.add(menuItem(Messages.getString("ChatLounge.ConnectAsTrain"),
                  LMP_CONNECT_TRAIN + NO_INFO + seIds, true));
        }

        if (accessibleCarriers) {
            popup.add(
                  menuItem("Disembark / leave from carriers", LMP_UNLOAD + NO_INFO + seIds, !noneEmbarked));
            popup.add(menuItem("Offload all carried units", LMP_UNLOAD_ALL + NO_INFO + seIds, anyCarrier));
        }

        if (accessibleTrailers) {
            popup.add(menuItem("Detach from Tractor", LMP_DETACH_FROM_TRACTOR + NO_INFO + seIds, anyTrailer));
        }

        if (accessibleTractors) {
            popup.add(menuItem("Detach Trailer", LMP_DETACH_TRAILER + NO_INFO + seIds, anyTractor));
        }

        if (accessibleTransportBays) {
            popup.add(offloadBayMenu(anyCarrier, joinedEntities));
        }

        if (accessibleFighters && optCapFighters) {
            popup.add(squadronMenu(client, true, joinedEntities));
        }

        if (allProtoMeks) {
            popup.add(protoMenu(client, true, joinedEntities));
        }

        popup.add(c3Menu(hasJoinedEntities, joinedEntities, client));
        popup.add(forceMenu(commands, game, entities, forces));
        if (forceView && (forces.size() == 1)) {
            addBotFormationMenu(popup, game, forces.getFirst());
        }
        addBotFacingItem(popup, game, joinedEntities);

        popup.add(Item.spacer());
        popup.add(menuItem("View AlphaStrike Stats", LMP_ALPHA_STRIKE + NO_INFO + seIds, true));

        if (oneSelected) {
            popup.add(exportEntitySpriteMenu(entities.getFirst()));
        }

        popup.add(menuItem("Convert to SBF Formation", LMP_SBF_FORMATION + "|" + foToken(forces) + eIds,
              forceView));
        popup.add(Item.spacer());
        // Enabled whenever anything is selected, forces included: the handler deletes selected forces with their
        // units after asking, exactly as the Delete key does, so the menu must not stay greyed out in the force
        // view while the key works.
        popup.add(menuItem("Delete", LMP_DELETE + "|" + foToken(forces) + seIds,
              !entities.isEmpty() || !forces.isEmpty()));

        return popup;
    }

    /** A bot's units in a lance, in lance order, and the bot. */
    public record BotLance(Player owner, List<Integer> unitIds) { }

    /**
     * The units placed directly in {@code force} that take unit orders, all of the first such unit's owner, in lance
     * order, or null when none does. Only CASPAR bots take unit orders; Princess takes none (see
     * {@link OrderEligibility#takesUnitOrders}). Decided by who owns the units, not the force: a lance a player builds
     * and then hands to a bot still belongs to the player, and a lance may mix the player's units with the bot's
     * (HammerGS, 2026-10-02).
     */
    public static @Nullable BotLance botLance(Game game, Force force) {
        Player owner = null;
        List<Integer> unitIds = new ArrayList<>();
        for (int unitId : force.getEntities()) {
            Entity unit = game.getEntity(unitId);
            if ((unit == null) || !OrderEligibility.takesUnitOrders(game, unit.getOwner())) {
                continue;
            }
            if (owner == null) {
                owner = unit.getOwner();
            }
            if (unit.getOwner().getId() == owner.getId()) {
                unitIds.add(unitId);
            }
        }
        return owner == null ? null : new BotLance(owner, unitIds);
    }

    /**
     * The order-taking units of each lance under a parent force, a company with no order-taking units of its own,
     * each lance as {@link #botLance} gives it; empty when no lance under it takes orders.
     */
    public static List<List<Integer>> parentLances(Game game, Force parent) {
        List<List<Integer>> lances = new ArrayList<>();
        for (Force subForce : game.getForces().getFullSubForces(parent)) {
            BotLance lance = botLance(game, subForce);
            if (lance != null) {
                lances.add(lance.unitIds());
            }
        }
        return lances;
    }

    /**
     * Adds the Formation and Role menus for a lance with a CASPAR bot's units in it, so they start the game in
     * formation and knowing their job. Only the bot's units are ordered; a player's own units in the lance are left
     * alone. Princess bots take no unit orders, so their lances get no menus.
     *
     * <p>A parent force, a company with no units of its own but lances under it that take orders, gets a Formation
     * menu that sets every such lance, each on its own leader, and a Role item saying to set the role on each lance:
     * the tester right-clicked the company and concluded the menus did not exist (QA playtest, #9526).</p>
     */
    private static void addBotFormationMenu(Item popup, Game game, Force force) {
        BotLance lance = botLance(game, force);
        if (lance == null) {
            addParentFormationMenu(popup, game, force);
            return;
        }
        List<Integer> unitIds = lance.unitIds();
        logger.info("[BotOrders] lobby menus for {}: Formation and Role for {} unit(s) of {} ({} in the lance)",
              force.getName(), unitIds.size(), lance.owner().getName(), force.getEntities().size());
        String forceId = String.valueOf(force.getId());
        popup.add(formationMenu(forceId, unitIds.size() >= 2));

        // None, Convoy... or Escort..., the last two opening the Role dialog on that choice
        List<Entity> units = new ArrayList<>();
        for (int unitId : unitIds) {
            Entity unit = game.getEntity(unitId);
            if (unit != null) {
                units.add(unit);
            }
        }
        LanceRole current = LanceRoles.roleOf(units);
        String currentName = Messages.getString("BotCommandPanel.Role." + ((current == null) ? "NONE"
              : current.getKind().name()));
        Item role = Item.menu(Messages.getString("BotCommandPanel.Role.lobby", currentName));
        role.setEnabled(!units.isEmpty());
        role.add(new Item(Messages.getString("BotCommandPanel.Role.NONE"),
              LMP_BOT_ROLE + "|" + ROLE_NONE + ":" + forceId + NO_INFO));
        role.add(new Item(Messages.getString("BotCommandPanel.Role.lobbyConvoy"),
              LMP_BOT_ROLE + "|" + ROLE_CONVOY + ":" + forceId + NO_INFO));
        boolean convoys = !LanceRoles.convoyChoices(game, lance.owner(), force.getId()).isEmpty();
        Item escort = menuItem(Messages.getString("BotCommandPanel.Role.lobbyEscort"),
              LMP_BOT_ROLE + "|" + ROLE_ESCORT + ":" + forceId + NO_INFO, convoys);
        if (!convoys) {
            escort.setToolTipText(Messages.getString("BotCommandPanel.Role.escort.noLance"));
        }
        role.add(escort);
        popup.add(role);
    }

    private static void addParentFormationMenu(Item popup, Game game, Force parent) {
        List<List<Integer>> lances = parentLances(game, parent);
        if (lances.isEmpty()) {
            logger.info("[BotOrders] lobby menus for {}: no Formation or Role, none of its units is a CASPAR bot's",
                  parent.getName());
            return;
        }
        logger.info("[BotOrders] lobby menus for {}: Formation for {} lance(s) under it", parent.getName(),
              lances.size());
        popup.add(formationMenu(String.valueOf(parent.getId()),
              !MoveOrderCommands.formableLances(lances).isEmpty()));
        // a role is a lance's job and a company holds lances with different ones, so it is set on each lance
        popup.add(menuItem(Messages.getString("BotCommandPanel.Role.lobbyParent"), LMP_BOT_ROLE + NO_INFO + NO_INFO, false));
    }

    /** The Formation menu: one item per shape, the first unit of each lance leading, plus Formation off. */
    private static Item formationMenu(String forceId, boolean enabled) {
        Item formation = Item.menu(Messages.getString("BotCommandPanel.Formations.lobby"));
        formation.setEnabled(enabled);
        for (FormationShape shape : FormationShape.values()) {
            formation.add(new Item(Messages.getString("BotCommandPanel.Formations.shape." + shape),
                  LMP_BOT_FORMATION + "|" + shape.name() + ":" + forceId + NO_INFO));
        }
        formation.addSeparator();
        formation.add(new Item(Messages.getString("BotCommandPanel.Formations.off"),
              LMP_BOT_FORMATION_OFF + "|" + forceId + NO_INFO));
        return formation;
    }

    /**
     * Adds a Bot facing item for units owned by a CASPAR bot. It opens the turret-style facing dialog: the "when stopped"
     * facing is the one each unit deploys in and holds whenever it stops, the "while moving" one it keeps on the move.
     * Auto leaves the choice to the bot, which deploys facing the enemy's deployment zone.
     */
    private static void addBotFacingItem(Item popup, Game game, Collection<Entity> selectedUnits) {
        if (selectedUnits.isEmpty()) {
            return;
        }
        List<Entity> units = new ArrayList<>(selectedUnits);
        units.sort(Comparator.comparingInt(Entity::getId));
        for (Entity unit : units) {
            if (!OrderEligibility.takesUnitOrders(game, unit.getOwner())) {
                return;
            }
        }
        popup.add(new Item(Messages.getString("BotCommandPanel.Orders.lobbyFacing"),
              LMP_BOT_FACING + NO_INFO + enToken(units)));
    }

    /**
     * Returns the "Force" submenu, allowing assignment to forces
     */
    private static Item forceMenu(LobbyCommands commands, Game game, List<Entity> entities, List<Force> forces) {
        Item menu = Item.menu("Force");
        if (!forces.isEmpty() || !entities.isEmpty()) {
            menu.add(menuItem("Create Force from...",
                  LMP_F_CREATE_FROM + "|" + foToken(forces) + enToken(entities),
                  true));
        }
        menu.add(menuItem("Add empty Force...", LMP_F_CREATE_TOP + NO_INFO + NO_INFO, true));

        // If exactly one force is selected, offer force options
        if ((forces.size() == 1) && entities.isEmpty()) {
            Force force = forces.getFirst();
            boolean editable = commands.isEditable(force);
            String fId = "|" + force.getId();
            menu.add(menuItem("Add Sub force...", LMP_F_CREATE_SUB + fId + NO_INFO, editable));
            menu.add(menuItem("Rename", LMP_F_RENAME + fId + NO_INFO, editable));
            menu.add(menuItem("Promote to Top-Level Force", LMP_F_PROMOTE + fId + NO_INFO,
                  editable && !force.isTopLevel()));
        }

        // If entities are selected but no forces, offer entity options
        if (forces.isEmpty() && !entities.isEmpty() && LobbyRules.haveSingleOwner(entities)) {
            // Add to force menu tree
            Item addMenu = Item.menu("Add to...");
            for (Force force : game.getForces().getTopLevelForces()) {
                addMenu.add(forceTreeMenu(force, game, enToken(entities)));
            }
            menu.add(addMenu);
            menu.add(menuItem("Remove from Force", LMP_F_REMOVE + NO_INFO + enToken(entities), true));
        }

        // If exactly one force is selected, offer force options
        if ((forces.size() == 1) && entities.isEmpty()) {
            Force force = forces.getFirst();
            boolean editable = commands.isEditable(force);
            menu.add(menuItem("Delete empty Force...", LMP_FC_DELETE_EMPTY + "|" + foToken(forces) + NO_INFO,
                  (editable && force.getChildCount() == 0)));
        }

        menu.setEnabled(menu.getItemCount() > 0);
        return menu;
    }

    private static Item forceTreeMenu(Force force, Game game, String enToken) {
        Item result;
        String item = force.getName() + idString(game, force.getId());
        if (force.getSubForces().isEmpty()) {
            result = menuItem(item, LMP_F_ADD_TO + "|" + force.getId() + enToken, true);
        } else {
            result = Item.menu(item);
            for (Integer subForceId : force.getSubForces()) {
                result.add(forceTreeMenu(game.getForces().getForce(subForceId), game, enToken));
            }
        }
        return result;
    }

    static String idString(Game game, int id) {
        return " [" + id + "]";
    }

    /**
     * Returns the "Load" submenu, allowing general embarking
     */
    private static Item loadMenu(Client client, boolean enabled, Collection<Entity> entities) {
        Game game = client.getGame();
        Item menu = Item.menu("Load onto");
        if (enabled && !entities.isEmpty()) {
            // DropShip -> JumpShip loading gives free collars info
            if (entities.stream().allMatch(e -> e instanceof Dropship)) {
                game.getEntitiesVector().stream()
                      .filter(e -> e instanceof Jumpship)
                      .filter(e -> !entities.contains(e))
                      .filter(e -> canLoadAll(e, entities))
                      .forEach(e -> menu.add(menuItem(
                            e.getShortNameRaw() + idString(game, e.getId()) + " (Free Collars: "
                                  + ((Jumpship) e).getFreeDockingCollars() + ")",
                            LMP_LOAD + "|" + e.getId() + ":-1" + enToken(entities), true)));
            } else if (entities.size() == 1) {
                Entity transportedUnit = entities.iterator().next();
                // Standard loading, not ProtoMeks, not DropShip -> JumpShip
                game.getEntitiesVector().stream()
                      .filter(e -> !e.isCapitalFighter())
                      .filter(e -> !entities.contains(e))
                      .filter(e -> canLoadAll(e, entities))
                      .forEach(e -> {
                          Item loaderMenu = Item.menu(e.getShortNameRaw() + idString(game, e.getId()));
                          e.getTransports().forEach(t -> {
                              if (t.canLoad(transportedUnit)) {
                                  // FIXME #7640: Update once we can properly specify any transporter an entity has, and properly load into that transporter.
                                  loaderMenu.add(menuItem(
                                        "Onto " + t,
                                        LMP_LOAD + "|" + e.getId() + ":" + (Integer.MAX_VALUE
                                              - e.getTransports().indexOf(t)) + enToken(entities),
                                        true));
                              }
                          });
                          if (loaderMenu.getItemCount() > 0) {
                              menu.add(loaderMenu);
                          }
                      });
            } else if (entities.stream().noneMatch(e -> e.hasETypeFlag(Entity.ETYPE_PROTOMEK))) {
                // Standard loading, not ProtoMeks, not DropShip -> JumpShip
                game.getEntitiesVector().stream()
                      .filter(e -> !e.isCapitalFighter())
                      .filter(e -> !entities.contains(e))
                      .filter(e -> canLoadAll(e, entities))
                      .forEach(e -> menu.add(menuItem(
                            e.getShortNameRaw() + idString(game, e.getId()),
                            LMP_LOAD + "|" + e.getId() + ":-1" + enToken(entities), true)));
            }
        }
        menu.setEnabled(enabled && (menu.getItemCount() > 0));
        return menu;
    }

    /**
     * Returns the "Tow" submenu, allowing towing
     */
    private static Item towMenu(Client client, boolean enabled, Entity entity) {
        Game game = client.getGame();
        Item menu = Item.menu("Towed by");
        menu.setVisible(false);
        if (enabled && entity.isTrailer()) {
            menu.setVisible(true);
            game.getEntitiesVector().stream()
                  .filter(tractor -> tractor.getTowing() == Entity.NONE)
                  .filter(tractor -> tractor.canTow(entity.getId()))
                  .filter(tractor -> !tractor.equals(entity))
                  .forEach(tractor -> menu.add(menuItem(tractor.getShortNameRaw() + idString(game,
                              tractor.getId()),
                        LMP_TOW + "|" + tractor.getId() + ":-1|" + entity.getId(), true

                  )));
        }
        menu.setEnabled(enabled && (menu.getItemCount() > 0));

        return menu;
    }

    /**
     * Returns the "Load ProtoMek" submenu
     */
    private static Item protoMenu(Client client, boolean enabled, Collection<Entity> entities) {
        Item menu = Item.menu("Load ProtoMek");
        if (!(enabled || entities.stream().allMatch(e -> e.hasETypeFlag(Entity.ETYPE_PROTOMEK)))) {
            return menu;
        }

        Game game = client.getGame();

        Optional<Entity> optionalEntity = entities.stream()
              .filter(e -> e.hasETypeFlag(Entity.ETYPE_PROTOMEK))
              .findAny();
        Entity entity = optionalEntity.orElse(null);
        List<Entity> loaders = game.getEntitiesVector();

        // Handle front and rear Magnetic Clamp Mounts
        for (Entity loader : loaders) {
            if (!loader.hasETypeFlag(Entity.ETYPE_MEK)) {
                continue;
            }
            Transporter front = null;
            Transporter rear = null;
            for (Transporter t : loader.getTransports()) {
                if (t instanceof ProtoMekClampMount) {
                    if (((ProtoMekClampMount) t).isRear()) {
                        rear = t;
                    } else {
                        front = t;
                    }
                }
            }
            Item loaderMenu = Item.menu(loader.getShortName());
            if ((entity != null) && (front != null) && front.canLoad(entity)
                  && ((entity.getWeightClass() < EntityWeightClass.WEIGHT_SUPER_HEAVY)
                  || (rear == null) || rear.getLoadedUnits().isEmpty())) {
                loaderMenu.add(menuItem("Onto Front", LMP_LOAD + "|" + loader.getId() + ":0" + enToken(entities),
                      enabled));
            }
            boolean frontUltra = (front != null)
                  && front.getLoadedUnits().stream()
                  .anyMatch(l -> l.getWeightClass() == EntityWeightClass.WEIGHT_SUPER_HEAVY);
            if ((rear != null) && rear.canLoad(entity) && !frontUltra) {
                loaderMenu.add(menuItem("Onto Rear", LMP_LOAD + "|" + loader.getId() + ":1" + enToken(entities),
                      enabled));
            }
            if (loaderMenu.getItemCount() > 0) {
                menu.add(loaderMenu);
            }
        }

        // Handle all other valid loaders, such as Dropships
        loaders.stream()
              .filter(e -> !e.isCapitalFighter())
              .filter(e -> !entities.contains(e))
              .filter(e -> canLoadAll(e, entities))
              .forEach(e -> menu.add(menuItem(
                    e.getShortNameRaw() + idString(game, e.getId()),
                    LMP_LOAD + "|" + e.getId() + ":-1" + enToken(entities), enabled)));

        menu.setEnabled(enabled && (menu.getItemCount() > 0));
        return menu;
    }

    /**
     * Returns the "Fighter Squadron" submenu, allowing to assign units to or create a fighter squadron
     */
    private static Item squadronMenu(Client client, boolean enabled, Collection<Entity> entities) {
        Item menu = Item.menu("Fighter Squadrons");
        boolean hasFighter = entities.stream().anyMatch(Entity::isFighter);
        if (enabled && hasFighter) {
            menu.add(menuItem("Form Fighter Squadron", LMP_SQUADRON + NO_INFO + enToken(entities), true));

            // Join [Squadron] menu items
            client.getGame().getEntitiesVector().stream()
                  .filter(e -> e instanceof FighterSquadron)
                  .filter(e -> !entities.contains(e))
                  .filter(e -> canLoadAll(e, entities))
                  .forEach(e -> menu.add(menuItem("Join " + e.getShortName(),
                        LMP_LOAD + "|" + e.getId() + ":-1" + enToken(entities), true)));
        }
        menu.setEnabled(enabled && (menu.getItemCount() > 0));
        return menu;
    }

    /**
     * Returns the "Priority Target" submenu, allowing to assign units as a strategic target to a local Princess bot.
     */
    private static Item priorityTargetMenu(Client client, boolean enabled, Collection<Entity> entities) {
        Item menu = Item.menu("Set Priority Target for");
        if (enabled && !client.getBots().isEmpty()) {
            for (String bot : client.getBots().keySet()) {
                menu.add(menuItem(bot, LMP_PRIORITY_TARGET + "|" + bot + enToken(entities), true));
            }
        }
        menu.setEnabled(enabled && (menu.getItemCount() > 0));
        return menu;
    }

    /**
     * Returns the "Heat at start" submenu. It reaches the top of the heat scale in play plus the dissipation of the
     * selected unit that sinks the most, since the first Heat Phase takes the dissipation off before it checks heat
     * effects (see {@link SettableHeat}). Heat past 10 is grouped by tens so the long range stays easy to scan.
     *
     * @param game     the game in the lobby
     * @param entities the selected units, which all get the chosen heat
     * @param eIds     the selected units' command token
     *
     * @return the submenu
     */
    private static Item heatMenu(Game game, Collection<Entity> entities, String eIds) {
        int maximumHeat = SettableHeat.maximum(game, entities);
        Item heatMenu = Item.menu(Messages.getString("ChatLounge.heat.menu"));
        heatMenu.add(menuItem(Messages.getString("ChatLounge.heat.none"), LMP_HEAT + "|0" + eIds, true));
        for (int heat = 1; heat <= HEAT_WITHOUT_SUBMENU; heat++) {
            heatMenu.add(heatItem(heat, eIds));
        }
        Item moreHeatMenu = Item.menu(Messages.getString("ChatLounge.heat.more"));
        for (int groupStart = HEAT_WITHOUT_SUBMENU + 1; groupStart <= maximumHeat; groupStart += HEAT_GROUP_SIZE) {
            int groupEnd = Math.min(groupStart + HEAT_GROUP_SIZE - 1, maximumHeat);
            Item groupMenu = Item.menu(Messages.getString("ChatLounge.heat.range", groupStart, groupEnd));
            for (int heat = groupStart; heat <= groupEnd; heat++) {
                groupMenu.add(heatItem(heat, eIds));
            }
            moreHeatMenu.add(groupMenu);
        }
        heatMenu.add(moreHeatMenu);
        return heatMenu;
    }

    private static Item heatItem(int heat, String eIds) {
        return menuItem(Messages.getString("ChatLounge.heat.value", heat), LMP_HEAT + "|" + heat + eIds, true);
    }

    /**
     * Returns true when the loader can load all the given entities (under lobby conditions).
     */
    private static boolean canLoadAll(Entity loader, Collection<Entity> entities) {
        return entities.stream().allMatch(e -> loader.canLoad(e, false));
    }

    /**
     * The mission's scan targets are a game master's to set, and only matter in a game that uses objectives, so the
     * submenu is offered to nobody else.
     */
    static boolean isScanTargetMenuUseful(Client client) {
        Player personAtTheKeyboard = client.getLocalPlayer();
        if ((personAtTheKeyboard == null) || !personAtTheKeyboard.isGameMaster()) {
            logger.debug("[Scan] Scan target items hidden: {} is not a game master",
                  (personAtTheKeyboard == null) ? "no local player" : personAtTheKeyboard.getName());
            return false;
        }
        if (!client.getGame().getOptions().booleanOption(OptionsConstants.VICTORY_USE_OBJECTIVES)) {
            logger.debug("[Scan] Scan target items hidden: the game does not use objectives");
            return false;
        }
        return true;
    }

    /**
     * Returns the "Deploy" submenu, allowing late deployment
     */
    private static Item deployMenu(Client client, boolean enabled, Set<Entity> entities) {
        String eIds = enToken(entities);
        Item menu = Item.menu("Deploy");
        // a unit's card offers all of this in its Unit Status dialog, so the card's own menu can leave it out
        menu.key = LMP_DEPLOY;
        if (enabled) {
            // Hidden, Prone, Hull-down
            if (client.getGame().getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS)) {
                boolean anyHidden = entities.stream().anyMatch(Entity::isHidden);
                boolean anyNotHidden = entities.stream().anyMatch(e -> !e.isHidden());
                menu.add(menuItem("Hidden", LMP_HIDDEN + "|" + LMP_HIDE + eIds, anyNotHidden));
                menu.add(menuItem("Not Hidden", LMP_HIDDEN + "|" + LMP_NO_HIDE + eIds, anyHidden));
                menu.add(Item.spacer());
            }

            // The mission's scan targets are a game master's to set, and only matter in a game with objectives
            if (isScanTargetMenuUseful(client)) {
                boolean anyWanted = false;
                boolean anyNotWanted = false;
                for (Entity entity : entities) {
                    if (entity.isDesignatedScanTarget()) {
                        anyWanted = true;
                    } else {
                        anyNotWanted = true;
                    }
                }
                menu.add(menuItem(Messages.getString("ChatLounge.ScanTarget.wanted"),
                      LMP_SCAN_TARGET + "|" + LMP_SCAN_WANTED + eIds, anyNotWanted));
                menu.add(menuItem(Messages.getString("ChatLounge.ScanTarget.notWanted"),
                      LMP_SCAN_TARGET + "|" + LMP_SCAN_NOT_WANTED + eIds, anyWanted));
                menu.add(Item.spacer());
            }

            menu.add(menuItem("Standing", LMP_STAND + "|" + LMP_STAND + eIds, true));
            menu.add(menuItem("Prone", LMP_STAND + "|" + LMP_PRONE + eIds, true));
            if (client.getGame().getOptions()
                  .booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN)) {
                menu.add(menuItem("Hull-Down", LMP_STAND + "|" + LMP_HULL_DOWN + eIds, true));
            }
            menu.add(Item.spacer());

            menu.add(heatMenu(client.getGame(), entities, eIds));
            menu.add(Item.spacer());

            // Late deployment
            Item lateMenu = Item.menu("Deployment round");
            if (Game.rulesManager.getRulesGame().isWalkOnDeployment()) {
                lateMenu.add(menuItem(Messages.getString("ChatLounge.deploysPreGame"),
                      LMP_DEPLOY + "|" + Entity.DEPLOY_ROUND_PRE_GAME + eIds,
                      true));
            }
            lateMenu.add(menuItem("At game start", LMP_DEPLOY + "|0" + eIds, true));
            for (int i = 1; i < 11; i++) {
                lateMenu.add(menuItem("Before round " + i, LMP_DEPLOY + "|" + i + eIds, true));
            }
            Item veryLateMenu = Item.menu("Later");
            for (int i = 11; i < 41; i++) {
                veryLateMenu.add(menuItem("Before round " + i, LMP_DEPLOY + "|" + i + eIds, true));
            }

            lateMenu.add(veryLateMenu);
            menu.add(lateMenu);
        }
        menu.setEnabled(enabled);
        return menu;
    }

    /**
     * Returns the "Randomize" submenu, allowing to randomly assign name, callsign and skills
     */
    private static Item randomizeMenu(boolean enabled, String eIds) {
        Item menu = Item.menu("Randomize");
        // a unit's card offers this as its die icon, so the card's own menu can leave it out
        menu.key = LMP_RANDOMIZE;
        menu.setEnabled(enabled);

        menu.add(menuItem("Name", LMP_NAME + NO_INFO + eIds, enabled));
        menu.add(menuItem("Callsign", LMP_CALLSIGN + NO_INFO + eIds, enabled));
        menu.add(menuItem("Skills", LMP_SKILLS + NO_INFO + eIds, enabled));
        return menu;
    }

    /**
     * Returns the "Configure Munitions" submenu.
     */
    private static Item munitionsConfigMenu(boolean enabled, Collection<Entity> entityCol) {
        Item menu = Item.menu("Configure Munitions");
        Entity first = (entityCol.isEmpty()) ? null : entityCol.iterator().next();
        enabled &= (first != null && entityCol.stream().noneMatch(e -> e.isEnemyOf(first)));
        menu.setEnabled(enabled);
        String eIds = enToken(entityCol);

        menu.add(menuItem("Autoconfig", LMP_AUTOCONFIG + NO_INFO + eIds, enabled));
        menu.add(menuItem("Randomize", LMP_RANDOM_CONFIG + NO_INFO + eIds, enabled));
        menu.add(menuItem("Save config", LMP_SAVE_CONFIG + NO_INFO + eIds, enabled));
        menu.add(menuItem("Apply config", LMP_APPLY_CONFIG + NO_INFO + eIds, enabled));

        return menu;
    }

    /** Returns the C3 computer submenu. */
    private static Item c3Menu(boolean enabled, Collection<Entity> entities, Client client) {
        Item menu = Item.menu("C3");
        // a C3 unit's card offers the network manager as its icon, so the card's own menu can leave this out
        menu.key = LMP_C3;

        if (entities.stream().anyMatch(Entity::hasAnyC3System)) {

            // The primary entry: the manager shows the selected units and every network as a tree
            menu.add(menuItem("Open C3 Network Manager...", LMP_C3_MANAGER + NO_INFO + enToken(entities),
                  enabled));
            menu.addSeparator();

            menu.add(menuItem("Disconnect", LMP_C3DISCONNECT + NO_INFO + enToken(entities), enabled));

            if (entities.stream().anyMatch(e -> e.hasC3MM() || e.hasC3M())) {
                boolean allCM = entities.stream().allMatch(Entity::isC3CompanyCommander);
                menu.add(menuItem("Set as C3 Company Master",
                      LMP_C3CM + NO_INFO + enToken(entities),
                      !allCM));
                boolean allLM = entities.stream().allMatch(Entity::isC3IndependentMaster);
                menu.add(menuItem("Set as C3 Lance Master", LMP_C3LM + NO_INFO + enToken(entities), !allLM));
            }

            // Special treatment if exactly a C3SSSM is selected
            if (entities.size() == 4) {
                long countM = entities.stream().filter(Entity::hasC3M).count();
                long countS = entities.stream().filter(Entity::hasC3S).count();
                if (countM == 1 && countS == 3) {
                    Optional<Entity> optionalMaster = entities.stream().filter(Entity::hasC3M).findAny();
                    optionalMaster.ifPresent(master -> menu.add(menuItem("Form C3 Lance",
                          LMP_C3_FORM_C3 + "|" + master.getId() + enToken(entities),
                          true)));
                }
            }

            // Special treatment if a group of NhC3 is selected
            if (entities.size() > 1 && entities.size() <= 6) {
                Entity master = anyOneElement(entities);
                if (entities.stream().allMatch(e -> C3Util.sameNhC3System(master, e))) {
                    menu.add(menuItem("Form C3 Lance",
                          LMP_C3_FORM_NHC3 + "|" + master.getId() + enToken(entities),
                          true));
                }
            }

            Optional<Entity> c3Entity = entities.stream().filter(Entity::hasAnyC3System).findAny();

            Entity entity = null;

            if (c3Entity.isPresent()) {
                entity = entities.stream().filter(e -> e.hasC3S() || e.hasNhC3()).findAny().orElse(c3Entity.get());
            }

            // ideally, find one slave or C3i/NC3/Nova to get some connection options
            Game game = client.getGame();
            ArrayList<String> usedNetIds = new ArrayList<>();

            if (entity != null) {
                for (Entity other : client.getEntitiesVector()) {
                    // ignore enemies and self; only link the same type of C3
                    if (entity.isEnemyOf(other) || entity.equals(other)
                          || (entity.hasC3i() != other.hasC3i())
                          || (entity.hasNavalC3() != other.hasNavalC3())
                          || (entity.hasNovaCEWS() != other.hasNovaCEWS())
                          || !other.hasAnyC3System() || other.hasC3S()) {
                        continue;
                    }
                    // maximum depth of a c3 network is 2 levels.
                    Entity eCompanyMaster = other.getC3Master();
                    if ((eCompanyMaster != null) && (eCompanyMaster.getC3Master() != eCompanyMaster)) {
                        continue;
                    }
                    int nodes = other.calculateFreeC3Nodes();
                    if (entity.hasC3M() && other.C3MasterIs(other)) {
                        // A master joining a company commander occupies a company-level master link, so show
                        // that pool - also for single-computer company masters (CR p.198, Configuration 1)
                        nodes = other.calculateFreeC3MNodes();
                    }
                    if (entity.C3MasterIs(other)) {
                        nodes++;
                    }
                    if ((entity.hasNhC3()) && entity.onSameC3NetworkAs(other)) {
                        nodes++;
                    }

                    if (other.hasNhC3()) {
                        // Don't add the following checks to the line above
                        if (!entity.onSameC3NetworkAs(other) && !usedNetIds.contains(other.getC3NetId())) {
                            String item = "Join " + other.getShortNameRaw() + idString(game, other.getId());
                            item += " (" + other.getC3NetId() + ")";
                            item += (nodes == 0 ? " - full" : " - " + nodes + " free spots");
                            menu.add(menuItem(item, LMP_C3JOIN + "|" + other.getId() + enToken(entities), nodes != 0));
                            usedNetIds.add(other.getC3NetId());
                        }

                    } else if (other.isC3CompanyCommander() && other.hasC3MM()) {
                        String item = "Connect to " + other.getShortNameRaw() + idString(game, other.getId());
                        item += " (" + other.getC3NetId() + ")";
                        item += (nodes == 0 ? " - full" : " - " + nodes + " free spots");
                        menu.add(menuItem(item, LMP_C3CONNECT + "|" + other.getId() + enToken(entities), nodes != 0));

                    } else if (!entity.isC3CompanyCommander()
                          && (entity.hasC3M() ? lanceRolesCompatible(game, entity, other)
                          : other.isC3IndependentMaster())) {
                        // Slaves connect to lance masters; masters connect to company commanders or - forming an
                        // All-C3-Master lance (CR p.199) - to lance masters whose dependents are all masters too.
                        String item = "Connect to " + other.getShortNameRaw() + idString(game, other.getId());
                        item += " (" + other.getC3NetId() + ")";
                        if (entity.C3MasterIs(other)) {
                            item += " - already connected";
                            nodes = 0;
                        } else {
                            item += (nodes == 0 ? " - full" : " - " + nodes + " free spots");
                        }
                        menu.add(menuItem(item, LMP_C3CONNECT + "|" + other.getId() + enToken(entities), nodes != 0));
                    }
                }

            }
        }
        menu.setEnabled(enabled && menu.getItemCount() > 0);
        return menu;
    }

    /**
     * Returns true when the joining unit's role fits the dependents already connected to the given master. A lance is
     * homogeneous (CR p.199): all C3 Slaves, or - under the All-C3-Master rule - all C3 Masters in slave roles, so a
     * master may not join a lance of slaves and vice versa.
     */
    private static boolean lanceRolesCompatible(Game game, Entity joiningUnit, Entity master) {
        boolean joinerIsMaster = joiningUnit.hasC3M();
        for (Entity other : game.getEntitiesVector()) {
            if (!other.equals(master) && !other.equals(joiningUnit) && other.C3MasterIs(master)
                  && (other.hasC3M() != joinerIsMaster)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the "Change Unit Owner" submenu.
     */
    private static Item changeOwnerMenu(boolean enabled, Client client,
          Collection<Entity> entities,
          Collection<Force> forces) {
        Item menu = Item.menu(Messages.getString("ChatLounge.ChangeOwner"));
        menu.setEnabled(enabled);

        Game game = client.getGame();
        Forces gameForces = game.getForces();

        if (!entities.isEmpty()) {
            for (Player player : game.getPlayersList()) {
                String command = LMP_ASSIGN + "|" + player.getId() + ":" + foToken(forces) + enToken(entities);
                menu.add(menuItem(player.getName(), command, enabled));
            }
        }

        if (entities.isEmpty() && !forces.isEmpty()) {
            Item assignMenu = Item.menu("Assign to");
            Item fOnlyMenu = Item.menu("Force only");
            Item fFullMenu = Item.menu("Everything in the Force");
            assignMenu.add(fOnlyMenu);
            assignMenu.add(fFullMenu);
            Force force = anyOneElement(forces);
            for (Player player : game.getPlayersList()) {
                if (!player.isEnemyOf(gameForces.getOwner(force))) {
                    String command = LMP_F_ASSIGN_ONLY + "|" + player.getId() + ":" + foToken(forces) + NO_INFO;
                    fOnlyMenu.add(menuItem(player.getName(), command, true));
                }
                String command = LMP_F_ASSIGN + "|" + player.getId() + ":" + foToken(forces) + NO_INFO;
                fFullMenu.add(menuItem(player.getName(), command, true));
            }
            assignMenu.setEnabled(enabled && assignMenu.getItemCount() > 0);
            menu.add(assignMenu);
        }

        return menu;
    }

    /**
     * @return the "Equipment" submenu, allowing hot loading LRMs, setting MGs to rapid fire mode, Variable Range
     *       Targeting mode selection, and Enhanced Imaging mode selection
     */
    private static Item equipMenu(boolean anyRFOn, boolean anyRFOff, boolean anyHLOn,
          boolean anyHLOff, boolean anyVRTLong, boolean anyVRTShort, boolean anyVRT,
          boolean anyEIOn, boolean anyEIOff, boolean anyEI,
          boolean optHL, boolean optRF, String eIds) {
        Item menu = Item.menu(Messages.getString("ChatLounge.Equipment"));
        menu.setEnabled(anyRFOff || anyRFOn || anyHLOff || anyHLOn || anyVRT || anyEI);
        if (optRF) {
            menu.add(menuItem(Messages.getString("ChatLounge.RapidFireToggleOn"), LMP_RAPID_FIRE_MG_ON + NO_INFO + eIds,
                  anyRFOff));
            menu.add(menuItem(Messages.getString("ChatLounge.RapidFireToggleOff"),
                  LMP_RAPID_FIRE_MG_OFF + NO_INFO + eIds,
                  anyRFOn));
        }
        if (optHL) {
            menu.add(menuItem(Messages.getString("ChatLounge.HotLoadToggleOn"), LMP_HOT_LOAD_ON + NO_INFO + eIds,
                  anyHLOff));
            menu.add(menuItem(Messages.getString("ChatLounge.HotLoadToggleOff"), LMP_HOT_LOAD_OFF + NO_INFO + eIds,
                  anyHLOn));
        }
        if (anyVRT) {
            menu.add(menuItem(Messages.getString("ChatLounge.VRTSetLong"), LMP_VRT_LONG + NO_INFO + eIds,
                  anyVRTShort));
            menu.add(menuItem(Messages.getString("ChatLounge.VRTSetShort"), LMP_VRT_SHORT + NO_INFO + eIds,
                  anyVRTLong));
        }
        if (anyEI) {
            menu.add(menuItem(Messages.getString("ChatLounge.EIToggleOn"), LMP_EI_ON + NO_INFO + eIds,
                  anyEIOff));
            menu.add(menuItem(Messages.getString("ChatLounge.EIToggleOff"), LMP_EI_OFF + NO_INFO + eIds,
                  anyEIOn));
        }
        return menu;
    }

    /**
     * Returns the "Offload from" submenu, allowing to offload units from a specific bay of the given entity
     */
    private static Item offloadBayMenu(boolean enabled, Collection<Entity> entities) {
        Item menu = Item.menu("Offload All From...");
        if (enabled && entities.size() == 1) {
            Entity entity = theElement(entities);
            for (Bay bay : entity.getTransportBays()) {
                if (!bay.getLoadedUnits().isEmpty()) {
                    String label = "Bay #" + bay.getBayNumber() + " (" + bay.getLoadedUnits().size() + " units)";
                    menu.add(menuItem(label, LMP_UNLOAD_ALL_FROM_BAY + "|" + bay.getBayNumber() + enToken(entities),
                          true));
                }
            }
        }
        menu.setEnabled(enabled && menu.getItemCount() > 0);
        return menu;
    }

    /**
     * Returns the "Swap Pilot" submenu, allowing to swap the unit pilot with a pilot of an equivalent unit. Does work
     * with multiple selected units but expects the Lobby to issue an error message as only one unit can swap pilot with
     * one other unit.
     */
    private static Item swapPilotMenu(boolean enabled, Collection<Entity> entities, Client client) {
        Game game = client.getGame();

        Item menu = Item.menu("Swap pilots with");
        if (!entities.isEmpty()) {
            // use a random selected unit to determine the targets
            Entity entity = anyOneElement(entities);
            for (Entity swapper : game.getEntitiesVector()) {
                // only swap your own pilots and with the same unit and crew type
                if (swapper.getOwnerId() == entity.getOwnerId()
                      && swapper.getId() != entity.getId()
                      && swapper.getUnitType() == entity.getUnitType()
                      && swapper.getCrew().getCrewType() == entity.getCrew().getCrewType()) {

                    String item = swapper.getShortNameRaw() + idString(game, swapper.getId());
                    String command = LMP_SWAP + "|" + swapper.getId() + enToken(entities);
                    menu.add(menuItem(item, command, enabled));
                }
            }
        }
        menu.setEnabled(enabled && menu.getItemCount() > 0);
        return menu;
    }

    private static Item exportEntitySpriteMenu(final Entity entity) {
        final Item exportUnitSpriteMenu = Item.menu(Messages.getString("exportUnitSpriteMenu.title"));
        exportUnitSpriteMenu.setToolTipText(Messages.getString("exportUnitSpriteMenu.toolTipText"));
        exportUnitSpriteMenu.add(spriteItem(entity, "miCurrentCamouflage", SPRITE_CAMO));
        exportUnitSpriteMenu.add(spriteItem(entity, "miCurrentDamage", SPRITE_DAMAGE));
        exportUnitSpriteMenu.add(spriteItem(entity, "miCurrentCamouflageAndDamage", SPRITE_CAMO_DAMAGE));
        if (!(entity instanceof FighterSquadron)) {
            exportUnitSpriteMenu.add(spriteItem(entity, "miSelectedCamouflage", SPRITE_CHOSEN_CAMO));
            exportUnitSpriteMenu.add(spriteItem(entity, "miSelectedCamouflageAndCurrentDamage",
                  SPRITE_CHOSEN_CAMO_DAMAGE));
        }
        return exportUnitSpriteMenu;
    }

    private static Item spriteItem(Entity entity, String key, String kind) {
        Item item = new Item(Messages.getString(key + ".text"), LMP_SPRITE + "|" + kind + "|" + entity.getId());
        item.setToolTipText(Messages.getString(key + ".toolTipText"));
        return item;
    }

    /**
     * Returns a command string token containing the IDs of the given entities and a leading | E.g. |2,14,44,22
     */
    private static String enToken(Collection<Entity> entities) {
        if (entities.isEmpty()) {
            return "|-1";
        }
        List<String> ids = entities.stream().map(Entity::getId).map(Object::toString).collect(Collectors.toList());
        return "|" + String.join(",", ids);
    }

    /**
     * Returns a command string token containing the IDs of the given forces E.g. 2,14,44,22
     */
    private static String foToken(Collection<Force> forces) {
        if (forces.isEmpty()) {
            return "-1";
        }
        List<String> ids = forces.stream().map(Force::getId).map(Object::toString).collect(Collectors.toList());
        return String.join(",", ids);
    }

    /**
     * @return true when the entity has an MG set to rapid fire.
     */
    private static boolean hasRapidFireMG(Entity entity) {
        // Battle armor cannot use burst-fire MGs per errata
        if (entity instanceof BattleArmor) {
            return false;
        }
        for (Mounted<?> m : entity.getWeaponList()) {
            EquipmentType etype = m.getType();
            if (etype.hasFlag(WeaponType.F_MG) && m.isRapidFire()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when the entity has an MG set to normal (non-rapid) fire. */
    private static boolean hasNormalFireMG(Entity entity) {
        // Battle armor cannot use burst-fire MGs per errata
        if (entity instanceof BattleArmor) {
            return false;
        }
        for (Mounted<?> m : entity.getWeaponList()) {
            EquipmentType etype = m.getType();
            if (etype.hasFlag(WeaponType.F_MG) && !m.isRapidFire()) {
                return true;
            }
        }
        return false;
    }

    /** Returns true when the entity has a weapon with ammo set to hot-loaded. */
    private static boolean hasHotLoaded(Entity entity) {
        for (Mounted<?> ammo : entity.getAmmo()) {
            AmmoType etype = (AmmoType) ammo.getType();
            if (etype.hasFlag(AmmoType.F_HOTLOAD) && ammo.isHotLoaded()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when the entity has a weapon with ammo set to non-hot-loaded.
     */
    private static boolean hasNonHotLoaded(Entity entity) {
        for (Mounted<?> ammo : entity.getAmmo()) {
            AmmoType etype = (AmmoType) ammo.getType();
            if (etype.hasFlag(AmmoType.F_HOTLOAD) && !ammo.isHotLoaded()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when the entity has Variable Range Targeting quirk set to LONG mode.
     */
    private static boolean hasVRTLong(Entity entity) {
        return entity.hasVariableRangeTargeting() && entity.getVariableRangeTargetingMode().isLong();
    }

    /**
     * Returns true when the entity has Variable Range Targeting quirk set to SHORT mode.
     */
    private static boolean hasVRTShort(Entity entity) {
        return entity.hasVariableRangeTargeting() && entity.getVariableRangeTargetingMode().isShort();
    }

    /**
     * Returns true when the entity has the Variable Range Targeting quirk.
     */
    private static boolean hasVRT(Entity entity) {
        return entity.hasVariableRangeTargeting();
    }

    /**
     * Returns true when the entity has EI Interface and it's currently active (On mode).
     */
    private static boolean hasEIOn(Entity entity) {
        return entity.hasEiCockpit() && !entity.isEiShutdown();
    }

    /**
     * Returns true when the entity has EI Interface and it's currently shut down (Off mode).
     */
    private static boolean hasEIOff(Entity entity) {
        return entity.hasEiCockpit() && entity.isEiShutdown();
    }

    /**
     * Returns true when the entity has EI Interface equipment.
     */
    private static boolean hasEI(Entity entity) {
        return entity.hasEiCockpit();
    }
}
