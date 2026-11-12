/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * Context menus and select lists in one popover (C.1 G12; r1 3.17, r2 8.1, shot 13): the unit menus at the pointer
 * (M2-M4), the hex menu with MegaMek's own map menu in sections (M5, M10), the weapon row and queued attack menus
 * (H36, H37), the dock's More above its button (A.7 G14, H38) and the select lists of select faces (C.3). A group
 * opens its submenu beside its item, as a desktop menu does: the menus before it
 * stay open, a press outside them all closes them, and Esc closes the deepest. Items run existing commands only: the
 * HUD's selection rule, the source's locate and centre requests, and the movement, fire and line-of-sight services.
 * Opening a menu never changes orders.
 */
final class GpuContextMenu implements GpuHud.Component {
    /** The dock's More opens this far left of its button (app.js pop:more). */
    private static final float MORE_SHIFT = -150;
    static final String SEPARATOR = " \u00B7 ";

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final IntConsumer select;
    private final Table root = new Table();
    private final UiPopover popover;
    /** The submenus by depth, from 1, made the first time a menu opens that deep; each lies beside its item. */
    private final List<UiPopover> submenus = new ArrayList<>();
    private GpuHud.Inputs inputs;
    /**
     * The hex whose menu is open, or the hex of the unit whose menu is open; its map menu, and a hex menu's terrain
     * and structure lines, come with later frames. Null otherwise.
     */
    private Coords hex;
    /** The unit whose open menu lists MegaMek's map menu of its hex; null for a hex menu or none. */
    private UnitStatus menuUnit;
    /** Whether the open hex or unit menu lists MegaMek's map menu, which a later frame brings. */
    private boolean mapListed;
    /** The published turn and board that own the open context menu; a change closes it before another actor can act. */
    private GpuBattleStatus.Snapshot menuOwner;
    private long menuBoardGeneration;
    /** The world height of the terrain the pointer hit where the open menu opened; NaN for none. */
    private float pointedZ = Float.NaN;
    /** The frame parts the open hex menu last showed; a capture publishes new ones. */
    private String shownTooltip;
    private BoardScene.Context shownContext;

    /**
     * {@code select} selects or inspects a unit ({@link GpuHud#select}). Both "Center camera" items go through the
     * client's centre request, which the board view frames with the HUD's camera width.
     */
    GpuContextMenu(GpuHudKit kit, GpuBoardSource source, GpuHudState state, IntConsumer select) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.select = select;
        root.setName("context-menu");
        popover = new UiPopover(ui);
        popover.setName("context-menu-popover");
        root.addActor(popover);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Keeps the frame the menus read; an open hex menu takes its terrain line and map menu from it. */
    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        if (!popover.isVisible()) {
            closed();
        } else if (hex != null || menuUnit != null) {
            GpuBattleStatus.Snapshot now = inputs.frame().status();
            if (menuOwner != null && (menuOwner.round() != now.round() || menuOwner.phase() != now.phase()
                  || menuOwner.myTurn() != now.myTurn() || menuOwner.turnIndex() != now.turnIndex()
                  || menuOwner.actorId() != now.actorId() || menuBoardGeneration != inputs.frame().boardGeneration())) {
                cancel();
            } else {
                showHex();
            }
        }
    }

    /** Whether a menu or select list is open. */
    boolean isOpen() {
        return popover.isVisible();
    }

    /** A press outside it, or another menu: closes the open menu with its submenus; true when one was open. */
    boolean cancel() {
        boolean open = popover.cancel();
        closed();
        return open;
    }

    /** One Esc step (C.4): closes the open menu's deepest submenu, or the menu; true when one was open. */
    boolean back() {
        boolean open = popover.back();
        if (!popover.isVisible()) {
            closed();
        }
        return open;
    }

    /** Forgets a closed hex or unit menu and the hex context it asked the source for. */
    private void closed() {
        if (hex != null || menuUnit != null) {
            hex = null;
            menuUnit = null;
            source.inspect(null);
        }
        menuOwner = null;
    }

    /**
     * Opens the menu of the unit, or of the hex without one, at stage point (x, y): an own or allied unit (M2), an
     * identified enemy (M3), a sensor contact (M4) or the hex (M5). Unit and game actions come from {@link MapMenu}.
     */
    void open(Coords coords, int unitId, float x, float y) {
        open(coords, unitId, x, y, Float.NaN);
    }

    /**
     * As {@link #open(Coords, int, float, float)}, from the board, where the pointer hit terrain at world height
     * {@code pointedZ} (NaN for none): its line of sight measures to that height.
     */
    void open(Coords coords, int unitId, float x, float y, float pointedZ) {
        if (inputs == null) {
            return;
        }
        cancel();
        this.pointedZ = pointedZ;
        UnitStatus unit = state.presented(unitId);
        if (unit == null && (coords == null || inputs.frame().scene() == null)) {
            return;
        }
        menuOwner = inputs.frame().status();
        menuBoardGeneration = inputs.frame().boardGeneration();
        if (unit == null) {
            hexMenu(coords);
        } else {
            unitMenu(coords, unit);
        }
        popover.showAt(x, y);
    }

    /**
     * A unit's header and the shared map menu, including roster units on another board or without a position.
     */
    private void unitMenu(Coords coords, UnitStatus unit) {
        menuUnit = unit;
        hex = coords;
        source.inspect(hex, unit.id(), state.turnLocked(inputs.frame().status()));
        shownTooltip = null;
        shownContext = null;
        if (unit.sensorContact()) {
            show(text("GpuBoard.hud.common.sensorContact"), unit.position() == null
                  ? text("GpuBoard.hud.common.unidentified")
                  : text("GpuBoard.hud.context.unidentifiedHex", unit.position().getBoardNum()), boardItems());
        } else if (unit.side() == GpuBattleStatus.Side.ENEMY) {
            int distance = distance(state.presented(acting()), unit);
            show(title(unit), distance < 0 ? text("GpuBoard.hud.context.visualContact")
                  : text("GpuBoard.hud.context.visualContactHex", distance), boardItems());
        } else {
            String subtitle = unit.side() != GpuBattleStatus.Side.OWN ? text("GpuBoard.hud.forces.allies", owner(unit))
                  : text(unit.done() ? "GpuBoard.hud.context.yourUnitActed" : "GpuBoard.hud.context.yourUnit");
            show(title(unit), subtitle, boardItems());
        }
    }

    /**
     * The complete shared board menu. Wait for the capture before offering any
     * actions, so late-arriving shared entries cannot replace an actionable row under the pointer.
     */
    private UiMenuList boardItems() {
        UiMenuList list = new UiMenuList(ui);
        mapListed = context() != null;
        if (!mapListed) {
            item(list, text("UnitLoadingDialog.pleaseWait"), null, false, () -> { });
            return list;
        }
        sections(list, mapActions());
        return list;
    }

    /**
     * The ids of the units the scene draws in that hex (a multi-hex unit in each hex of its footprint), once each, in
     * the scene's order: the units a click there selects or inspects in turn ({@code GpuHud.cycled}) and the unit
     * menus list. Empty without a scene or hex.
     */
    static List<Integer> stack(BoardScene scene, Coords coords) {
        return scene == null || coords == null ? List.of() : scene.units().stream()
              .filter(drawn -> hexes(drawn).contains(coords)).map(BoardScene.Unit::id).distinct().toList();
    }

    /** The hexes a drawn unit or part stands in: its footprint, or its location's hex without one. */
    private static List<Coords> hexes(BoardScene.Unit drawn) {
        return drawn.footprint().isEmpty() ? List.of(drawn.location().coords()) : drawn.footprint();
    }

    /**
     * M5: a hex. In the local planning turn, plan to it or pin it (a jump has one landing hex); centre on it; line of
     * sight; and MegaMek's map menu for it (M10), which the source captures once asked for this hex.
     */
    private void hexMenu(Coords coords) {
        hex = coords;
        source.inspect(coords, Entity.NONE, state.turnLocked(inputs.frame().status()));
        shownTooltip = null;
        shownContext = null;
        popover.header(text("GpuBoard.hud.context.hex", coords.getBoardNum()), null).content(boardItems());
        showHex();
    }

    /**
     * MegaMek's map menu (M10) in the hex and unit menus: each group's commands in a section of their own and the other
     * commands together, each section after a separator; a group within a section opens as a submenu. A disabled
     * group's commands cannot run, so it shows none.
     */
    private void sections(UiMenuList list, List<BoardScene.Command> commands) {
        boolean loose = false;
        for (BoardScene.Command command : commands) {
            boolean group = !command.children().isEmpty();
            if (group && !command.enabled()) {
                continue;
            }
            if (list.hasChildren() && (group || !loose)) {
                list.separator();
            }
            loose = !group;
            commands(popover, list, group ? command.children() : List.of(command), true);
        }
    }

    /**
     * The open hex menu's subtitle and map menu, or the open unit menu's map menu, from the newest capture: its tooltip
     * and context, compared by identity because each capture publishes new ones. The map menu is listed once it
     * arrives; a unit menu keeps its unit's header.
     */
    private void showHex() {
        String tooltip = inputs.frame().tooltip();
        BoardScene.Context context = inputs.frame().context();
        if (tooltip != shownTooltip || context != shownContext) {
            shownTooltip = tooltip;
            shownContext = context;
            if (!mapListed && context() != null) {
                popover.content(boardItems());
            }
            if (menuUnit != null) {
                return;
            }
            popover.header(text("GpuBoard.hud.context.hex", hex.getBoardNum()), hexLines(inputs.frame(), hex));
        }
    }

    /**
     * The hex's lines in its menu's header and its hover card: the terrain line ({@link #subtitle}), then the hex
     * tooltip's lines of the hex below its terrain (structures with their CF, minefields, works), which the same
     * capture publishes. Null while the frame's tooltip shows another hex or no terrain line.
     */
    static String hexLines(BoardSource.Frame frame, Coords hex) {
        BoardScene.Tile tile = frame.scene() == null ? null : frame.scene().tile(hex);
        String terrain = tile == null ? null : subtitle(frame.tooltip(), hex, tile.elevation());
        return terrain == null || frame.hexDetails().isEmpty() ? terrain : terrain + "\n" + frame.hexDetails();
    }

    /** MegaMek's map menu of the open hex (M10), once the source captured that hex's context. */
    private List<BoardScene.Command> mapActions() {
        BoardScene.Context context = context();
        return context == null ? List.of() : context.commands();
    }

    /** Match the requested hex, explicit unit and HUD selection state, even without a position. */
    private BoardScene.Context context() {
        BoardScene.Context context = inputs.frame().context();
        int unitId = menuUnit == null ? Entity.NONE : menuUnit.id();
        return context != null && context.unitId() == unitId && Objects.equals(context.coords(), hex)
              && context.selectionCleared() == state.turnLocked(inputs.frame().status()) ? context : null;
    }

    /**
     * The hex's subtitle (M5): MegaMek's terrain and the level, from the tooltip's line for that hex
     * (HexTooltip.getTerrainTip: "Hex: {xxyy} - Level: {n}", then each terrain after a dot spacer); "Clear" when it
     * names no terrain. Null while the tooltip shows another hex or no terrain line (the map hex tooltip setting).
     */
    static String subtitle(String tooltip, Coords hex, int level) {
        for (String key : List.of("BoardView1.Tooltip.Hex", "BoardView1.Tooltip.HexAlt")) {
            String start = Messages.getString(key, hex.getBoardNum(), level);
            for (String line : tooltip == null ? new String[0] : tooltip.split("\n")) {
                String terrain = line.strip();
                if (terrain.equals(start) || terrain.startsWith(start + UIUtil.DOT_SPACER)) {
                    List<String> parts = Arrays.stream(terrain.substring(start.length())
                          .split(UIUtil.DOT_SPACER.strip())).map(String::strip).filter(part -> !part.isEmpty())
                          .toList();
                    return text("GpuBoard.hud.context.hexLevel",
                          parts.isEmpty() ? text("GpuBoard.hud.context.clearTerrain") : String.join(SEPARATOR, parts),
                          level);
                }
            }
        }
        return null;
    }

    /**
     * Opens the weapon row menu (H36) of the local actor's weapon {@code eqNum} at stage point (x, y): assign it to
     * each target or to the focused enemy (disabled where the orders know it has no shot), remove its attack, show or
     * hide its solution and arc ({@code solutionShown}; {@code solution} is the weapons panel's toggle, the row name's
     * click), and MegaMek's mode, called shot, aimed shot and ammunition choices.
     */
    void weapon(int eqNum, boolean solutionShown, Runnable solution, float x, float y) {
        GpuFireOrders.Snapshot fire = inputs == null ? GpuFireOrders.Snapshot.EMPTY : inputs.frame().panels().fire();
        GpuFireOrders.WeaponRow row = row(fire, eqNum);
        if (row == null) {
            return;
        }
        cancel();
        TargetKey assigned = fire.attacks().stream().filter(attack -> attack.eqNum() == eqNum)
              .map(GpuFireOrders.Attack::target).findFirst().orElse(TargetKey.NONE);
        UiMenuList list = new UiMenuList(ui);
        if (fire.editable()) {
            for (GpuFireOrders.Target target : fire.targets().stream()
                  .sorted(Comparator.comparing(GpuFireOrders.Target::letter)).toList()) {
                item(list, text("GpuBoard.hud.context.assignToTarget", target.letter(), target.name()),
                      target.key().equals(assigned) ? text("GpuBoard.hud.context.assigned") : null,
                      shot(row, target.key()), () -> assign(eqNum, assigned, target.key()));
            }
            GpuFireOrders.Focus focus = fire.focus();
            if (!focus.key().equals(TargetKey.NONE)
                  && fire.targets().stream().noneMatch(target -> target.key().equals(focus.key()))) {
                item(list, text("GpuBoard.hud.weapons.assignTo", focus.name()), null, shot(row, focus.key()),
                      () -> assign(eqNum, assigned, focus.key()));
            }
            if (!assigned.equals(TargetKey.NONE)) {
                item(list, text("GpuBoard.hud.context.removeThisAttack"), null, true,
                      () -> source.fire().remove(eqNum));
            }
            list.separator();
        }
        item(list, text(solutionShown ? "GpuBoard.hud.context.hideSolution" : "GpuBoard.hud.context.showSolution"),
              null, true, solution);
        if (fire.editable()) {
            settings(list, row, fire.actorId(), !assigned.equals(TargetKey.NONE));
        }
        show(row.name(), GpuWeaponsPanel.detail(row), list);
        popover.showAt(x, y);
    }

    /**
     * MegaMek's weapon settings: its next and previous mode for a weapon with modes, the called shot while the phase
     * offers it, the aimed shot dialog with the location aimed at when the weapon may aim (R4; open while it has an
     * attack, {@code queued}, or may be assigned), and each ammunition when it has several (the loaded one says so and
     * stays loaded). {@code actorId}: the unit whose weapon it is.
     */
    private void settings(UiMenuList list, GpuFireOrders.WeaponRow row, int actorId, boolean queued) {
        int eqNum = row.eqNum();
        if (row.mode() != null && !row.mode().isEmpty()) {
            item(list, text("GpuBoard.hud.context.nextMode"), row.mode(), true,
                  () -> source.fire().cycleMode(eqNum, true));
            item(list, text("GpuBoard.hud.context.previousMode"), null, true,
                  () -> source.fire().cycleMode(eqNum, false));
        }
        BoardScene scene = inputs.frame().scene();
        if (scene != null && scene.commands().stream().anyMatch(command -> command.enabled()
              && command.id().equals(FiringCommand.FIRE_CALLED.getCmd()))) {
            item(list, text("GpuBoard.hud.context.calledShot"), null, true, () -> source.fire().calledShot(eqNum));
        }
        if (row.aimable()) {
            item(list, text("GpuBoard.hud.context.aimAtLocation"), row.aimedAt().isEmpty() ? null : row.aimedAt(),
                  queued || shot(row, row.target()),
                  () -> state.aiming = new GpuFireOrders.AimTarget(actorId, eqNum, row.target()));
        }
        if (row.ammo().size() > 1) {
            for (int index = 0; index < row.ammo().size(); index++) {
                GpuUnitRecord.AmmoChoice ammo = row.ammo().get(index);
                boolean loaded = index == row.loadedAmmo();
                // The bin's Unit Display entry, as the row's select lists it: it names its carrier and shots.
                item(list, ammo.label(), loaded ? text("GpuBoard.hud.context.loaded") : null, true, () -> {
                    if (!loaded) {
                        source.fire().setAmmo(eqNum, ammo);
                    }
                });
            }
        }
    }

    /**
     * Assigns the weapon to the target (H15): a weapon that attacks another target is retargeted in its place (H18);
     * its own target is only focused.
     */
    private void assign(int eqNum, TargetKey assigned, TargetKey target) {
        if (assigned.equals(target)) {
            source.fire().focusTarget(target);
        } else if (!assigned.equals(TargetKey.NONE)) {
            source.fire().retarget(eqNum, target);
        } else {
            source.fire().assign(eqNum, target);
        }
    }

    /**
     * Whether the orders allow the weapon a shot at the target: it can fire, and the target its to-hit column shows
     * ({@code targetId}: its attack's, else the focus) has no reason against it. Other targets' numbers are not in the
     * orders; an assignment the rules refuse is answered by the fire service's refusal toast (H17).
     */
    private static boolean shot(GpuFireOrders.WeaponRow row, TargetKey target) {
        return row.usable() && (!row.target().equals(target) || row.reason() == null || row.reason().isBlank());
    }

    /**
     * Opens the queued attack menu (H37) of the local actor's weapon {@code eqNum} at stage point (x, y): fire it
     * earlier or later among the attacks on its target, or remove it.
     */
    void attack(int eqNum, float x, float y) {
        GpuFireOrders.Snapshot fire = inputs == null ? GpuFireOrders.Snapshot.EMPTY : inputs.frame().panels().fire();
        List<GpuFireOrders.Attack> attacks = fire.attacks();
        int index = IntStream.range(0, attacks.size()).filter(at -> attacks.get(at).eqNum() == eqNum).findFirst()
              .orElse(-1);
        if (index < 0) {
            return;
        }
        cancel();
        GpuFireOrders.Attack attack = attacks.get(index);
        boolean earlier = index > 0 && attacks.get(index - 1).target().equals(attack.target());
        boolean later = index + 1 < attacks.size() && attacks.get(index + 1).target().equals(attack.target());
        UiMenuList list = new UiMenuList(ui);
        item(list, text("GpuBoard.hud.context.fireEarlier"), text("GpuBoard.hud.context.altUp"),
              fire.editable() && earlier, () -> source.fire().move(eqNum, -1));
        item(list, text("GpuBoard.hud.context.fireLater"), text("GpuBoard.hud.context.altDown"),
              fire.editable() && later, () -> source.fire().move(eqNum, 1));
        item(list, text("GpuBoard.hud.context.removeAttack"), null, fire.editable(), () -> source.fire().remove(eqNum));
        show(attack.weapon(), text("GpuBoard.hud.context.declaredAttack"), list);
        popover.showAt(x, y);
    }

    /**
     * Shows the dock's More popover above its {@code button}, 150 units to its left: the dock's two groups, each
     * command with its detail, and a separator when both groups have items.
     */
    void more(GpuCommandDock.More more, Actor button) {
        cancel();
        UiMenuList list = new UiMenuList(ui);
        commands(popover, list, more.items(), false);
        if (!more.items().isEmpty() && !more.commands().isEmpty()) {
            list.separator();
        }
        commands(popover, list, more.commands(), false);
        popover.header(more.title(), more.subtitle()).content(list);
        popover.showAbove(button, MORE_SHIFT);
    }

    /**
     * Opens the select list of a select face (C.3) below it: the choices, a check mark at {@code selected}; choosing
     * one calls {@code choose} with its index.
     */
    void list(Actor face, List<String> choices, int selected, IntConsumer choose) {
        cancel();
        UiMenuList list = new UiMenuList(ui);
        for (int index = 0; index < choices.size(); index++) {
            int choice = index;
            item(list, choices.get(index), null, index == selected, true, () -> choose.accept(choice));
        }
        popover.header(null, null).content(list).openedFrom(face);
        Vector2 corner = face.localToStageCoordinates(new Vector2());
        popover.showAt(corner.x, corner.y);
    }

    /**
     * Commands as items of {@code menu}'s list with their detail, or their shortcut ({@code shortcuts}, for MegaMek's
     * map menu, whose details are tooltips); a group opens its commands as a submenu beside its item.
     */
    private void commands(UiPopover menu, UiMenuList list, List<BoardScene.Command> commands, boolean shortcuts) {
        for (BoardScene.Command command : commands) {
            String detail = shortcuts ? command.shortcut() : command.detail();
            if (command.children().isEmpty()) {
                item(list, command.label(), detail, command.selected(), command.enabled(), () -> run(command));
            } else {
                submenu(menu, list, command.label(), detail, command.selected(), command.enabled(),
                      (submenu, children) -> commands(submenu, children, command.children(), shortcuts));
            }
        }
    }

    /** Adapt shared unit actions to the HUD's existing controllers; labels and availability belong to MapMenu. */
    private void run(BoardScene.Command command) {
        MapMenu.UnitAction action = command.unitAction();
        if (action == null) {
            command.action().run();
            return;
        }
        BoardScene.Context current = inputs.frame().context();
        if (current == null || !available(current.commands(), command.id())) {
            return;
        }
        UnitStatus unit = state.presented(action.entityId());
        if (action.entityId() != Entity.NONE && unit == null) {
            return;
        }
        switch (action.type()) {
            case SELECT -> select.accept(unit.id());
            case VIEW, INSPECT_SENSOR -> state.inspected = unit.id();
            case READOUT -> {
                if (!unit.sensorContact()) {
                    GpuRecordSheet.showUnit(state, unit.id(), acting());
                }
            }
            case TARGET -> {
                if (inputs.frame().panels().fire().active()) {
                    focusTarget(source, state, TargetKey.unit(unit.id()));
                } else {
                    command.action().run();
                }
            }
            case CENTER_UNIT -> source.locateUnit(unit.id());
            case CENTER_HEX -> source.centerOnHex(action.location().coords());
            case LINE_OF_SIGHT -> source.los().lineOfSight(unit.id(), action.location().coords(), pointedZ);
            case PLAN_MOVE -> source.moves().planTo(action.location().coords(), action.location().boardId(), false);
            case PIN_MOVE -> source.moves().planTo(action.location().coords(), action.location().boardId(), true);
            case MAKE_PRIMARY -> source.fire().setPrimary(TargetKey.unit(unit.id()));
            case REMOVE_TARGET -> source.fire().removeTarget(TargetKey.unit(unit.id()));
        }
    }

    /** Recheck the newest shared capture without moving menu rows while the pointer is over them. */
    private static boolean available(List<BoardScene.Command> commands, String id) {
        return commands.stream().anyMatch(command -> command.enabled()
              && (command.id().equals(id) || available(command.children(), id)));
    }

    /**
     * An item of {@code menu}'s list that opens a submenu beside it, as a desktop menu does: {@code fill} fills the
     * submenu's list, {@code menu} stays open, and a submenu it opened before closes.
     */
    private void submenu(UiPopover menu, UiMenuList list, String text, String detail, Boolean checked,
          boolean enabled, BiConsumer<UiPopover, UiMenuList> fill) {
        UiButton[] item = new UiButton[1];
        item[0] = list.item(text, detail, checked, true, enabled, () -> {
            UiPopover submenu = level(depth(menu) + 1);
            UiMenuList children = new UiMenuList(ui);
            fill.accept(submenu, children);
            submenu.header(null, null).content(children);
            menu.cascade(submenu, item[0]);
        });
    }

    /** The submenu popover {@code depth} levels below the menu, from 1, made the first time a menu opens that deep. */
    private UiPopover level(int depth) {
        while (submenus.size() < depth) {
            UiPopover submenu = new UiPopover(ui);
            submenu.setName("context-submenu-" + (submenus.size() + 1));
            root.addActor(submenu);
            submenus.add(submenu);
        }
        return submenus.get(depth - 1);
    }

    private int depth(UiPopover menu) {
        return menu == popover ? 0 : submenus.indexOf(menu) + 1;
    }

    /**
     * As a left click on the target in the local declaration (H15, H16): focus it, and assign the armed weapon to it
     * once.
     */
    static void focusTarget(GpuBoardSource source, GpuHudState state, TargetKey target) {
        source.fire().focusTarget(target);
        if (state.armedWeapon >= 0) {
            source.fire().assign(state.armedWeapon, target);
            state.armedWeapon = -1;
        }
    }

    /** An item that closes the menu before it runs {@code action}. */
    private void item(UiMenuList list, String text, String detail, boolean enabled, Runnable action) {
        item(list, text, detail, null, enabled, action);
    }

    private void item(UiMenuList list, String text, String detail, Boolean checked, boolean enabled, Runnable action) {
        list.item(text, detail, checked, false, enabled, () -> {
            cancel();
            action.run();
        });
    }

    /** A unit menu's content. */
    private void show(String title, String subtitle, UiMenuList list) {
        popover.header(title, subtitle).content(list);
    }

    /** The acting or own focus unit (C.5), absent while the player has cleared the selection. */
    private int acting() {
        return state.turnLocked(inputs.frame().status()) ? Entity.NONE
              : GpuHudKit.UnitRow.acting(inputs.frame().status(), state.focus());
    }

    private static GpuFireOrders.WeaponRow row(GpuFireOrders.Snapshot fire, int eqNum) {
        return fire.weapons().stream().filter(row -> row.eqNum() == eqNum).findFirst().orElse(null);
    }

    /** The prototype's "{name} {variant}" header. */
    private static String title(UnitStatus unit) {
        return unit.chassis().isEmpty() ? unit.name() : (unit.chassis() + " " + unit.model()).strip();
    }

    /** The allied player's name, as the forces list groups allies, or the formation while the list lacks it. */
    private String owner(UnitStatus unit) {
        return inputs.frame().panels().players().players().stream().filter(row -> row.id() == unit.ownerId())
              .map(GpuPlayers.PlayerRow::name).findFirst().orElse(unit.formation());
    }

    /** Hexes between two units on the same board, or -1 when either is missing or elsewhere. */
    private static int distance(UnitStatus from, UnitStatus to) {
        return from == null || from.position() == null || to.position() == null || from.boardId() != to.boardId()
              ? -1 : from.position().distance(to.position());
    }
}
