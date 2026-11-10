/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.UnitRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.UnitRow.Line;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.PrephaseDisplay;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * Forces navigator in the left column (C.1 G2): the own and allied units, or the contacts, as grouped rows or as a grid
 * of tiles, with search, the grid's grouping and filter, and the phase's next-unit command; while the unit sheet is
 * open it shrinks to its strip. Unless pinned open (or in its grid, or searching) it folds to a rail at the window's
 * edge, which unfolds while the pointer rests on it. It shows the presented units and calls the HUD's selection rule,
 * the unit menu and the phase command; every rule stays with the client.
 */
final class GpuForcesPanel implements GpuHud.Component {
    /** The phase displays' next-unit commands, by the ids their buttons give the scene's phase commands (C10). */
    private static final Set<String> NEXT_COMMANDS = Set.of(MoveCommand.MOVE_NEXT.getCmd(),
          FiringDisplay.FiringCommand.FIRE_NEXT.getCmd(), PhysicalDisplay.PhysicalCommand.PHYSICAL_NEXT.getCmd(),
          DeploymentDisplay.DeployCommand.DEPLOY_NEXT.getCmd(),
          PrephaseDisplay.PrephaseCommand.PREPHASE_NEXT.getCmd());
    private static final String UNIDENTIFIED = "GpuBoard.hud.common.unidentified";
    /** The separator of a status line's parts. */
    private static final String SEPARATOR = " \u00B7 ";
    /** A group of at most this many units takes half the grid's width (.gg.half). */
    private static final int HALF_GROUP = 4;
    /** Grid tiles are at least 64 units wide, 5 apart (.tiles). */
    private static final float TILE_MIN = 64;
    private static final float TILE_GAP = 5;
    /** The rail's width, which the HUD gives the forces slot while the panel is folded to it. */
    static final float RAIL_WIDTH = 58;
    /** How long the panel stays expanded after the pointer left it. */
    static final long COLLAPSE_MILLIS = 350;

    /** The grid's groupings (.gsel), in the order its select face steps through them. */
    private enum Grouping {
        FORMATION("GpuBoard.hud.forces.byFormation"),
        READINESS("GpuBoard.hud.forces.byReadiness"),
        WEIGHT("GpuBoard.hud.forces.byWeightClass");

        private final String key;

        Grouping(String key) {
            this.key = key;
        }
    }

    /** The grid's filters (.gsel), in the order its select face steps through them. */
    private enum Filter {
        ALL("GpuBoard.hud.forces.allUnits"),
        NEEDS_ORDERS("GpuBoard.hud.state.needsOrders"),
        ACTED("GpuBoard.hud.state.acted"),
        DAMAGED("GpuBoard.hud.forces.damagedOrImpaired");

        private final String key;

        Filter(String key) {
            this.key = key;
        }
    }

    /**
     * Everything the list shows. The snapshots keep their identity while unchanged, so comparing this each frame is
     * cheap, and the list is rebuilt only when it differs. {@code card} is the unit the card shows
     * ({@link GpuHudState#cardUnit}); {@code revision} counts the collapsed groups' changes.
     */
    private record View(List<UnitStatus> units, GpuBattleStatus.Snapshot status, int focus, int card,
          boolean grid, boolean contacts, Grouping grouping, Filter filter, String query, int revision, float width,
          Map<Integer, Integer> drafted, int fireActor, int fireAttacks, List<GpuReportLog.CombatEvent> combat,
          List<GpuBoardSource.Bind> binds, GpuPlayers.Snapshot players) { }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final IntConsumer select;
    private final Table root;
    /** The panel's content, and its strip (the header row only) while the unit sheet is open (unit panel 3.3). */
    private final Table full = new Table();
    private final Table strip = new Table();
    private final Cell<Table> content;
    private final Label stripCount;
    private final UiButton overview;
    private final UiButton searchToggle;
    private final UiButton gridToggle;
    private final TextTooltip overviewTip;
    private final TextTooltip gridTip;
    private final Table selects = new Table();
    private final UiButton groupingSelect;
    private final UiButton filterSelect;
    private final Cell<Actor> selectsCell;
    private final UiKit.Segmented tabs;
    private final UiKit.SearchField search;
    private final Table searchRow = new Table();
    private final Cell<Actor> searchCell;
    private final Table list = new Table();
    private final Label footText;
    private final Container<Actor> footRight = new Container<>();
    private final UiButton next;
    private final UiButton stripNext;
    private final Label rightClick;
    /**
     * The rail at the window's edge, the panel's form until the pointer rests on it or it is pinned open: the own and
     * allied units as sprites, the pending count and Next pending.
     */
    private final Table rail = new Table();
    private final Table railList = new Table();
    private final Label railCount;
    private final UiButton railNext;
    private final UiButton pin;
    private final TextTooltip pinTip;
    private final Map<Integer, RailItem> railItems = new HashMap<>();
    private List<Integer> railShown = List.of();
    /** The pointer is over the panel, and when it last left, in TimeUtils milliseconds. */
    private boolean hovered;
    private long leftAt;
    private boolean folded;
    /** The units' rows and tiles, kept across rebuilds so that their pointer state stays with them. */
    private final Map<Integer, UnitRow> rows = new HashMap<>();
    private final Map<Integer, Tile> tiles = new HashMap<>();
    /** The collapsed groups, by name; list and grid share them, as in the prototype. */
    private final Set<String> collapsed = new HashSet<>();
    private boolean contacts;
    private boolean searching;
    private Grouping grouping = Grouping.FORMATION;
    private Filter filter = Filter.ALL;
    private int revision;
    private int pending;
    private GpuHud.Inputs inputs;
    private View shown;
    /** What the list's actors were laid out for: mode, width, query, collapse revision, groups and their units. */
    private List<Object> shownLayout;

    /**
     * {@code menu} opens this component's unit menus and select lists (C13, SelectField). {@code select} selects or
     * inspects a unit ({@link GpuHud#select}).
     */
    GpuForcesPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu, IntConsumer select) {
        this.kit = kit;
        ui = kit.ui;
        this.state = state;
        this.menu = menu;
        this.select = select;
        root = ui.panel();
        root.setName("forces-panel");
        full.top();
        content = root.add(full).grow();

        overview = ui.button("hud-icon", "expand", null, null);
        overview.setName("forces-overview");
        overviewTip = ui.tip(overview);
        onChange(overview, () -> state.overview = !state.overview);
        searchToggle = ui.button("hud-icon", "search", null, null);
        searchToggle.setName("forces-search");
        ui.tip(searchToggle).getActor().setText(text("GpuBoard.hud.forces.searchTip"));
        onChange(searchToggle, this::toggleSearch);
        gridToggle = ui.button("hud-icon", "grid", null, null);
        gridToggle.setName("forces-grid");
        gridTip = ui.tip(gridToggle);
        onChange(gridToggle, () -> state.forcesGrid = !state.forcesGrid);
        pin = ui.button("hud-icon", "unlock", null, null);
        pin.setName("forces-pin");
        pinTip = ui.tip(pin);
        onChange(pin, () -> state.forcesPinned = !state.forcesPinned);
        full.add(ui.header(text("GpuBoard.hud.forces.title"), null, overview, searchToggle, gridToggle, pin)).growX()
              .row();

        // Grid only: the grouping and filter selects. G12 opens their lists; until then a click steps to the next.
        groupingSelect = ui.select("", false);
        groupingSelect.setName("forces-grouping");
        onChange(groupingSelect,
              () -> grouping = Grouping.values()[(grouping.ordinal() + 1) % Grouping.values().length]);
        filterSelect = ui.select("", false);
        filterSelect.setName("forces-filter");
        onChange(filterSelect, () -> filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length]);
        for (UiButton face : List.of(groupingSelect, filterSelect)) {
            // The mock's .fsel is a native select: 29 units tall, its text 13.5 in and its arrow near the edge.
            face.pad(7.5f, 13.5f, 7.5f, 6);
        }
        selects.pad(0, 14, 8, 14);
        selects.defaults().growX().uniformX().minWidth(0);
        selects.add(groupingSelect).padRight(8);
        selects.add(filterSelect);
        selectsCell = full.add((Actor) null).growX();
        full.row();

        tabs = ui.segmented("hud-tab", false, "", "");
        tabs.left().pad(2, 14, 0, 14);
        tabs.buttons.get(0).setName("forces-tab-friendly");
        tabs.buttons.get(1).setName("forces-tab-contacts");
        onChange(tabs.buttons.get(0), () -> contacts = false);
        onChange(tabs.buttons.get(1), () -> contacts = true);
        full.add(tabs).growX().row();

        search = ui.search(text("GpuBoard.hud.forces.searchPlaceholder"));
        search.field.setName("forces-search-field");
        searchRow.pad(0, 14, 8, 14);
        searchRow.add(search).growX();
        searchCell = full.add((Actor) null).growX();
        full.row();

        list.top();
        ScrollPane scroll = ui.scrollList(list);
        scroll.setName("forces-list");
        full.add(scroll).growX().row();

        Table foot = ui.footer(full);
        foot.setName("forces-footer");
        footText = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        footText.setEllipsis(true);
        foot.add(footText).growX().minWidth(0).left();
        foot.add(footRight).padLeft(10);
        next = nextButton("forces-next");
        rightClick = ui.label(text("GpuBoard.hud.forces.rightClickHint"), "hud-small", 11.5f, UiTheme.MUTED);

        // The strip (unit panel design 3.3): the title, the pending count, Next pending and ⌄, which closes the sheet.
        stripNext = nextButton("forces-strip-next");
        UiButton restore = ui.button("hud-icon", "chevron-down", null, null);
        restore.setName("forces-restore");
        ui.tip(restore).getActor().setText(text("GpuBoard.hud.forces.restoreTip"));
        onChange(restore, () -> state.recordOpen = false);
        strip.setName("forces-strip");
        strip.pad(0, 12, 0, 6);
        strip.add(ui.label(UiTheme.upper(text("GpuBoard.hud.forces.title")), "hud-title", 16, UiTheme.TEXT))
              .height(40).padRight(8);
        stripCount = ui.label("", "hud-body", 12, UiTheme.MUTED);
        stripCount.setEllipsis(true);
        strip.add(stripCount).minWidth(0).growX().left();
        strip.add(stripNext).padLeft(8);
        strip.add(restore).size(30).padLeft(4);

        rail.setName("forces-rail");
        rail.top().pad(6, 0, 6, 0);
        UiButton expand = ui.button("hud-icon", "chevron-right", null, null);
        expand.setName("forces-expand");
        ui.tip(expand).getActor().setText(text("GpuBoard.hud.forces.pinTip"));
        onChange(expand, () -> state.forcesPinned = true);
        railCount = ui.label("", "hud-medium", 11, Tone.WARN.color);
        railCount.setAlignment(Align.center);
        railCount.setName("forces-rail-count");
        railList.top();
        ScrollPane railScroll = ui.scrollList(railList);
        railScroll.setName("forces-rail-list");
        railNext = ui.button("hud-icon", "skip", null, null);
        railNext.setName("forces-rail-next");
        ui.tip(railNext).getActor().setText(text("GpuBoard.hud.forces.nextPending"));
        onChange(railNext, this::runNext);
        rail.add(expand).size(30).row();
        rail.add(railCount).growX().height(14).row();
        rail.add(railScroll).growX().minHeight(0).padTop(4).row();
        rail.add(railNext).size(30).padTop(4);

        // The pointer resting on the panel unfolds the rail; it folds again a moment after the pointer left.
        root.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1 && (fromActor == null || !fromActor.isDescendantOf(root))) {
                    hovered = true;
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(root))) {
                    hovered = false;
                    leftAt = TimeUtils.millis();
                }
            }
        });
    }

    /** Whether the panel shows as its rail this frame, which the HUD gives {@link #RAIL_WIDTH}. */
    boolean folded() {
        return folded;
    }

    /** The phase's next-unit command, as the Next pending buttons run it. */
    private void runNext() {
        BoardScene.Command command = nextCommand();
        if (command != null && command.enabled()) {
            // The next unit is the selected one, also when MegaMek's current unit comes again.
            state.restoreSelection();
            command.action().run();
        }
    }

    /** The phase's next-unit command as a mini button (Next pending ›), in the footer and in the strip. */
    private UiButton nextButton(String name) {
        UiButton button = ui.button("hud-mini", null, text("GpuBoard.hud.forces.nextPending"), null);
        button.setName(name);
        onChange(button, this::runNext);
        return button;
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        boolean grid = state.forcesGrid;
        selectsCell.setActor(grid ? selects : null);
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        View view = new View(state.presentedUnits(), inputs.frame().status(), state.focus(), state.cardUnit(), grid,
              contacts, grouping, filter, searching ? search.field.getText().strip().toLowerCase(Locale.ROOT) : "",
              revision, grid ? inputs.metrics().grid() : inputs.metrics().forces(), fire.drafted(),
              fire.active() ? fire.actorId() : Entity.NONE, fire.attacks().size(), state.history.playedAttacks(),
              inputs.preferences().binds(), inputs.frame().panels().players());
        if (!view.equals(shown)) {
            shown = view;
            rebuild();
        }
        // The phase's own command decides, as in MegaMek; the scene changes more often than the list.
        BoardScene.Command command = nextCommand();
        next.setDisabled(pending == 0 || command == null || !command.enabled());
        stripNext.setDisabled(next.isDisabled());
        railNext.setDisabled(next.isDisabled());
        pin.pressed(state.forcesPinned);
        pin.icons.forEach(icon -> ((Image) icon).setDrawable(ui.skin.getDrawable(state.forcesPinned ? "icon-lock"
              : "icon-unlock")));
        pinTip.getActor().setText(text(state.forcesPinned ? "GpuBoard.hud.forces.unpinTip"
              : "GpuBoard.hud.forces.pinTip"));
        boolean open = state.forcesPinned || state.forcesGrid || searching || hovered
              || TimeUtils.timeSinceMillis(leftAt) < COLLAPSE_MILLIS;
        folded = !open && !GpuRecordSheet.open(state);
        Table wanted = GpuRecordSheet.open(state) ? strip : folded ? rail : full;
        if (content.getActor() != wanted) {
            content.setActor(wanted);
        }
    }

    /** The phase display's next-unit command among the scene's phase commands, or null. */
    private BoardScene.Command nextCommand() {
        BoardScene scene = inputs == null ? null : inputs.frame().scene();
        return scene == null ? null : scene.commands().stream().filter(command -> NEXT_COMMANDS.contains(command.id()))
              .findFirst().orElse(null);
    }

    /** The search toggle (.fq): opens an empty field with the keyboard focus, or closes and clears it. */
    private void toggleSearch() {
        searching = !searching;
        search.field.setText("");
        searchToggle.pressed(searching);
        searchCell.setActor(searching ? searchRow : null);
        if (searching && root.getStage() != null) {
            root.getStage().setKeyboardFocus(search.field);
        }
    }

    private void rebuild() {
        List<UnitStatus> units = shown.units();
        gridToggle.icons.forEach(icon -> ((Image) icon).setDrawable(ui.skin.getDrawable(shown.grid() ? "icon-list"
              : "icon-grid")));
        // The binds' key texts; the view holds the binds, so a changed key rebuilds the tooltips.
        GpuBoardSource.UiPreferences preferences = inputs.preferences();
        gridTip.getActor().setText(text(shown.grid() ? "GpuBoard.hud.forces.listViewTip"
              : "GpuBoard.hud.forces.gridViewTip", GpuHintLine.key(preferences, KeyCommandBind.FORCES_GRID)));
        overviewTip.getActor().setText(text("GpuBoard.hud.forces.overviewTip",
              GpuHintLine.key(preferences, KeyCommandBind.UNIT_OVERVIEW)));
        groupingSelect.setText(text(shown.grouping().key));
        filterSelect.setText(text(shown.filter().key));
        long hostile = units.stream().filter(unit -> unit.side() == Side.ENEMY).count();
        tabs.buttons.get(0).setText(text("GpuBoard.hud.forces.friendlyTab", units.size() - hostile));
        tabs.buttons.get(1).setText(text("GpuBoard.hud.forces.contactsTab", hostile));
        tabs.select(shown.contacts() ? 1 : 0);

        // The footer counts the local player's own units, the ones this client gives orders to.
        List<UnitStatus> own = units.stream().filter(unit -> unit.side() == Side.OWN).toList();
        pending = (int) own.stream().filter(UnitStatus::pending).count();
        long operational = own.stream().filter(unit -> !unit.destroyed()).count();
        stripCount.setText(pending > 0 ? text("GpuBoard.hud.forces.stripPending", pending) : "");
        if (shown.contacts()) {
            footText.setText(text("GpuBoard.hud.forces.unidentifiedCount",
                  units.stream().filter(UnitStatus::sensorContact).count()));
            footRight.setActor(rightClick);
        } else {
            footText.setText(pending > 0 ? text("GpuBoard.hud.forces.pendingCount", pending, own.size())
                  : text("GpuBoard.hud.forces.operationalCount", operational, own.size()));
            footRight.setActor(next);
        }

        // The rail lists the own and allied units; its count is the pending ones.
        railCount.setText(pending > 0 ? String.valueOf(pending) : "");
        List<UnitStatus> railed = units.stream().filter(unit -> unit.side() != Side.ENEMY).toList();
        List<Integer> railIds = railed.stream().map(UnitStatus::id).toList();
        if (railIds.equals(railShown)) {
            railed.forEach(this::railItem);
        } else {
            railShown = railIds;
            railList.clearChildren();
            railItems.keySet().retainAll(railIds);
            railed.forEach(unit -> railList.add(railItem(unit)).size(RailItem.WIDTH, RailItem.HEIGHT).padBottom(4)
                  .row());
        }

        List<UnitStatus> listed = units.stream().filter(unit -> (unit.side() == Side.ENEMY) == shown.contacts())
              .filter(this::matches).filter(unit -> !shown.grid() || passes(unit)).toList();
        Map<String, List<UnitStatus>> groups = groups(listed, shown.grid() ? shown.grouping() : Grouping.FORMATION);
        // While the same groups list the same units, only the rows' and tiles' contents change: re-adding them would
        // cancel a press on one (Scene2D unfocuses removed actors), and status updates arrive during the enemy's turn.
        List<Object> layout = List.of(shown.grid(), shown.width(), shown.query(), revision, groups.entrySet().stream()
              .map(group -> List.of(group.getKey(), group.getValue().stream().map(UnitStatus::id).toList())).toList());
        if (layout.equals(shownLayout)) {
            groups.forEach((name, members) -> {
                if (!collapsed.contains(name)) {
                    members.forEach(shown.grid() ? this::tile : this::row);
                }
            });
            return;
        }
        shownLayout = layout;
        list.clearChildren();
        list.pad(0);
        if (listed.isEmpty()) {
            ui.empty(list, shown.query().isEmpty() ? text("GpuBoard.hud.forces.noUnits")
                  : text("GpuBoard.hud.forces.noMatch", shown.query()));
        } else if (shown.grid()) {
            grid(groups);
        } else {
            groups.forEach((name, members) -> {
                list.add(groupHeader(name, members.size())).growX().row();
                if (!collapsed.contains(name)) {
                    // A row is a Button, whose minimum width is its preferred one; the cell lets it shrink.
                    members.forEach(unit -> list.add(row(unit)).growX().minWidth(0).pad(0, 10, 6, 10).row());
                }
            });
        }
        Set<Integer> ids = new HashSet<>(listed.stream().map(UnitStatus::id).toList());
        rows.keySet().retainAll(ids);
        tiles.keySet().retainAll(ids);
    }

    /**
     * Groups in the order the units come, each unit in its group in that order; by weight class in the order of their
     * heaviest units, the unidentified last ({@link GpuHudKit#HEAVIEST_FIRST}).
     */
    private Map<String, List<UnitStatus>> groups(List<UnitStatus> units, Grouping by) {
        return GpuHudKit.groups(units, unit -> group(unit, by),
              by == Grouping.WEIGHT ? GpuHudKit.HEAVIEST_FIRST : (first, second) -> 0);
    }

    /**
     * A unit's group (C4, C8): its formation as the lists name it ({@link GpuHudKit#formation}), its readiness, or its
     * weight class. Contacts are "Unidentified".
     */
    private String group(UnitStatus unit, Grouping by) {
        if (unit.sensorContact()) {
            return text(UNIDENTIFIED);
        }
        return switch (by) {
            case FORMATION -> formation(unit);
            case READINESS -> text(unit.pending() ? "GpuBoard.hud.state.needsOrders"
                  : unit.done() ? "GpuBoard.hud.state.acted" : "GpuBoard.hud.state.waiting");
            case WEIGHT -> unit.weightClass();
        };
    }

    private String formation(UnitStatus unit) {
        return GpuHudKit.formation(unit, shown.status(), shown.players());
    }

    /** The search (C3) by the formation the list shows ({@link GpuHudKit#matches}). */
    private boolean matches(UnitStatus unit) {
        return GpuHudKit.matches(unit, formation(unit), shown.query());
    }

    /** The grid's filter (C8); damaged or impaired includes heat effects, as the prototype's "Running hot". */
    private boolean passes(UnitStatus unit) {
        return switch (shown.filter()) {
            case ALL -> true;
            case NEEDS_ORDERS -> unit.pending();
            case ACTED -> unit.done();
            case DAMAGED -> unit.destroyed() || unit.damageLevel() > Entity.DMG_NONE || impaired(unit)
                  || !unit.destroyedLocations().isEmpty() || hot(unit);
        };
    }

    /**
     * The board label warns or cautions about the unit, e.g. prone or shut down; its precautions and notes, such as
     * hidden, hull down or evading, impair nothing.
     */
    private static boolean impaired(UnitStatus unit) {
        return unit.statusWords().stream().anyMatch(word -> word.severity() == UnitStatusWords.Severity.WARNING
              || word.severity() == UnitStatusWords.Severity.CAUTION);
    }

    /** A group's header (.grp): chevron, name and unit count; a click collapses or opens it. */
    private Table groupHeader(String name, int count) {
        Table header = new Table();
        header.setName("forces-group-" + name);
        header.pad(9, 14, 5, 14);
        header.add(ui.icon(collapsed.contains(name) ? "chevron-right" : "chevron-down", 12, UiTheme.MUTED))
              .padRight(6);
        Label label = ui.label(name, "hud-medium", 11.5f, UiTheme.ACCENT);
        label.setEllipsis(true);
        header.add(label).growX().minWidth(0);
        header.add(ui.label(text("GpuBoard.hud.forces.groupCount", count), "hud-medium", 11, UiTheme.MUTED))
              .padLeft(6);
        header.setTouchable(Touchable.enabled);
        header.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!collapsed.remove(name)) {
                    collapsed.add(name);
                }
                revision++;
            }
        });
        return header;
    }

    /**
     * The grid (.ggrid): groups wrap two to a row when they hold at most four units, each with its tiles in as many
     * columns of at least 64 units as fit (.tiles). The column count comes from the panel width the HUD gives it.
     */
    private void grid(Map<String, List<UnitStatus>> groups) {
        // The panel's 2-unit side borders and the grid's 4-unit padding.
        float width = shown.width() - 12;
        list.pad(0, 4, 0, 4);
        boolean open = false;
        for (Map.Entry<String, List<UnitStatus>> group : groups.entrySet()) {
            boolean half = group.getValue().size() <= HALF_GROUP;
            if (!half && open) {
                list.row();
                open = false;
            }
            float groupWidth = half ? width / 2 : width;
            Cell<Table> cell = list.add(gridGroup(group.getKey(), group.getValue(), groupWidth)).width(groupWidth)
                  .top().left();
            if (half) {
                if (open) {
                    list.row();
                }
                open = !open;
            } else {
                cell.colspan(2);
                list.row();
            }
        }
    }

    private Table gridGroup(String name, List<UnitStatus> members, float width) {
        Table group = new Table();
        group.top().left();
        group.add(groupHeader(name, members.size())).growX().row();
        if (collapsed.contains(name)) {
            return group;
        }
        Table grid = new Table();
        grid.pad(2, 8, 8, 8).top().left();
        grid.defaults().space(TILE_GAP);
        float inner = width - 16;
        int columns = Math.max(1, (int) ((inner + TILE_GAP) / (TILE_MIN + TILE_GAP)));
        float tileWidth = (inner - TILE_GAP * (columns - 1)) / columns;
        for (int index = 0; index < members.size(); index++) {
            grid.add(tile(members.get(index))).width(tileWidth);
            if (index % columns == columns - 1) {
                grid.row();
            }
        }
        group.add(grid).left();
        return group;
    }

    private UnitRow row(UnitStatus unit) {
        UnitRow row = rows.computeIfAbsent(unit.id(), id -> {
            UnitRow created = kit.unitRow(40, 34);
            created.setName("forces-unit-" + id);
            onChange(created, () -> select.accept(id));
            created.addListener(UnitRow.menuOpener(menu, id, this::unit));
            return created;
        });
        // The one highlighted unit is the card's: the focus pressed in mint, an
        // inspected unit with its edges. The acting unit keeps its line, "Acting now", without the highlight.
        boolean card = unit.id() == shown.card();
        row.show(unit, line(unit), shown.status().phase(), card && unit.id() != shown.focus(), unit(acting()));
        row.pressed(card && unit.id() == shown.focus());
        return row;
    }

    private Tile tile(UnitStatus unit) {
        Tile tile = tiles.computeIfAbsent(unit.id(), Tile::new);
        tile.show(unit, line(unit));
        return tile;
    }

    private RailItem railItem(UnitStatus unit) {
        RailItem item = railItems.computeIfAbsent(unit.id(), RailItem::new);
        item.show(unit, line(unit));
        return item;
    }

    /** The prototype's acting() (C.5), whose row reads "Acting now" or "Up next". */
    private int acting() {
        return UnitRow.acting(shown.status(), shown.focus());
    }

    private static boolean attacking(GamePhase phase) {
        return phase.isFiring() || phase.isTargeting() || phase.isOffboard();
    }

    private UnitStatus unit(int id) {
        return shown.units().stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /**
     * The status line of the phase (r1 section 3.3, plan C6) from the unit's snapshot, the fire orders' drafts and
     * the attacks the playback has presented; nothing is recomputed.
     */
    private Line line(UnitStatus unit) {
        GpuBattleStatus.Snapshot status = shown.status();
        if (unit.side() == Side.ENEMY || unit.sensorContact() || unit.destroyed()) {
            // The contacts list shows the same lines.
            return UnitRow.contactLine(unit, status);
        }
        GamePhase phase = status.phase();
        String moved = UnitRow.moved(unit, phase);
        if (UnitRow.shutDown(unit)) {
            return new Line(join(text("GpuBoard.hud.state.shutDown"), moved), Tone.BAD);
        }
        boolean acting = unit.id() == acting();
        boolean actingNow = acting && status.myTurn();
        if (phase.isMovement()) {
            if (unit.done()) {
                return new Line(text("GpuBoard.hud.status.moved", moved), Tone.NORMAL);
            } else if (acting) {
                return actingLine(actingNow);
            }
            return waiting(unit, text("GpuBoard.hud.status.pending"));
        } else if (attacking(phase)) {
            if (unit.done()) {
                return new Line(join(unit.declaredAttacks() > 0
                      ? text("GpuBoard.hud.status.declared", unit.declaredAttacks())
                      : text("GpuBoard.hud.status.holdingFire"), moved), Tone.NORMAL);
            }
            // The actor's queued attacks, or another unit's saved drafts (the mock's drafts, plan H33).
            int drafted = unit.id() == shown.fireActor() ? shown.fireAttacks()
                  : shown.drafted().getOrDefault(unit.id(), 0);
            Line state;
            if (actingNow) {
                state = new Line(drafted > 0 ? text("GpuBoard.hud.status.actingDrafted", drafted)
                      : text("GpuBoard.hud.status.actingNow"), Tone.OK);
            } else if (acting) {
                state = actingLine(false);
            } else {
                state = waiting(unit, drafted > 0 ? text("GpuBoard.hud.status.pendingDrafted", drafted)
                      : text("GpuBoard.hud.status.pending"));
            }
            return new Line(join(state.text(), moved), state.tone());
        } else if (phase.isPhysical()) {
            if (unit.done() && unit.declaredAttacks() > 0) {
                return new Line(text("GpuBoard.hud.status.declared", unit.declaredAttacks()), Tone.NORMAL);
            } else if (unit.pending() && !unit.done()) {
                // A physical turn of the phase accepts the unit. That says nothing of an adjacent target (without
                // the skip-ineligible option every Mek gets one), and the status holds none, so the line names none.
                return acting ? actingLine(actingNow) : waiting(unit, text("GpuBoard.hud.status.pending"));
            }
            return new Line(join(text("GpuBoard.hud.status.noPhysicalAttack"), moved), Tone.NORMAL);
        } else if ((phase.isReport() && !phase.isInitiativeReport()) || phase.isEnd()) {
            return review(unit, moved);
        }
        return new Line(unit.heat() > 0 ? text("GpuBoard.hud.status.readyHeat", unit.heat())
              : text("GpuBoard.hud.status.ready"), Tone.NORMAL);
    }

    /** The unit the local player acts with, or the own focus unit before its turn (C.5). */
    private static Line actingLine(boolean now) {
        return new Line(text(now ? "GpuBoard.hud.status.actingNow" : "GpuBoard.hud.status.upNext"), Tone.OK);
    }

    /** A unit that has not acted: amber while a remaining turn of the phase accepts it, else waiting. */
    private static Line waiting(UnitStatus unit, String pendingText) {
        return unit.pending() ? new Line(pendingText, Tone.WARN)
              : new Line(text("GpuBoard.hud.state.waiting"), Tone.NORMAL);
    }

    /**
     * The review line of the report phases: the unit's attacks of the round that the playback has presented and their
     * hits (C.6: none counts before its shot lands), else its movement, and in the end phase its heat, amber while it
     * has heat effects.
     */
    private Line review(UnitStatus unit, String moved) {
        int round = shown.status().round();
        List<GpuReportLog.CombatEvent> attacks = shown.combat().stream()
              .filter(event -> event.round() == round && event.attackerId() == unit.id()).toList();
        long hits = attacks.stream().filter(GpuReportLog.CombatEvent::hit).count();
        GamePhase phase = shown.status().phase();
        boolean heat = (phase.isEnd() || phase.isEndReport()) && unit.heat() > 0;
        String text;
        if (!attacks.isEmpty()) {
            text = heat ? text("GpuBoard.hud.status.attacksHeat", attacks.size(), hits, unit.heat())
                  : text("GpuBoard.hud.status.attacks", attacks.size(), hits);
        } else {
            String summary = moved.isEmpty() ? text("GpuBoard.hud.status.noAttacks") : moved;
            text = heat ? join(summary, text("GpuBoard.hud.status.heat", unit.heat())) : summary;
        }
        return new Line(text, heat && hot(unit) ? Tone.WARN : Tone.NORMAL);
    }

    /**
     * The unit's heat has effects (the prototype's "running hot"): the status carries the heat table's text only for
     * a heat level with effects.
     */
    private static boolean hot(UnitStatus unit) {
        return !unit.heatEffects().isEmpty();
    }

    private static String join(String first, String second) {
        return second.isEmpty() ? first : first + SEPARATOR + second;
    }

    /**
     * A grid tile (.tile): sprite, name, the status line's first part and a 2-unit armor bar along the bottom. It
     * selects like a row, and opens the same menu.
     */
    private final class Tile extends Table {
        private final GpuHudKit.UnitSprite sprite = kit.sprite(36, 30);
        private final Label name = ui.label("", "hud-medium", 11, UiTheme.TEXT);
        private final Label status = ui.label("", "hud-small", 10, Color.WHITE);
        private final Drawable up = ui.skin.getDrawable("row");
        private final Drawable over = ui.skin.getDrawable("row-over");
        // A mint border over a faint mint fill, without the row's inset bar (.tile.sel).
        private final Drawable picked = ui.skin.getDrawable("field-focused");
        // An inspected unit's edges, as its row has them: mint for a friendly unit, coral for an enemy.
        private final Drawable friend = ui.skin.getDrawable("row-friend");
        private final Drawable foe = ui.skin.getDrawable("row-foe");
        private final Drawable bar = ui.skin.getDrawable("white");
        private final ClickListener pointer;
        /** The tile's highlight while the card shows its unit, else null. */
        private Drawable highlight;
        private float armor;
        private Color barColor = UiTheme.MINT;

        private Tile(int id) {
            setName("forces-tile-" + id);
            // The 1-unit border and .tile's padding.
            pad(7, 5, 8, 5);
            setBackground(up);
            setTouchable(Touchable.enabled);
            // A contact's "?" at the tile's small size (.tile span outranks .q).
            sprite.setActor(ui.label("?", "hud-heading", 10, Color.WHITE));
            name.setEllipsis(true);
            name.setAlignment(Align.center);
            status.setEllipsis(true);
            status.setAlignment(Align.center);
            add(sprite).row();
            // Line heights: the name's font shorthand resets it to Roboto's normal 1.17, the status keeps 1.35.
            add(name).growX().minWidth(0).height(13).padTop(3).row();
            add(status).growX().minWidth(0).height(13.5f);
            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    select.accept(id);
                }
            };
            addListener(pointer);
            addListener(UnitRow.menuOpener(menu, id, GpuForcesPanel.this::unit));
        }

        void show(UnitStatus unit, Line line) {
            sprite.set(unit.sensorContact() ? null : unit.icon(),
                  unit.sensorContact() ? UiTheme.ACCENT : UnitRow.spriteColor(unit));
            // The prototype's tile names a contact "Contact"; "Sensor contact" would not fit.
            name.setText(unit.sensorContact() ? text("GpuBoard.hud.forces.contactTile") : unit.chassis());
            int end = line.text().indexOf(SEPARATOR);
            status.setText(end < 0 ? line.text() : line.text().substring(0, end));
            status.setColor(line.tone().color);
            // The card's unit, as the rows highlight it: the focus selected, an inspected unit with its edges.
            highlight = unit.id() != shown.card() ? null : unit.id() == shown.focus() ? picked
                  : unit.side() == Side.ENEMY ? foe : friend;
            armor = unit.sensorContact() ? 0 : (float) Math.max(0, unit.armor());
            barColor = unit.side() == Side.ENEMY ? UiTheme.CORAL : UiTheme.MINT;
            getColor().a = unit.destroyed() ? .4f : 1;
        }

        @Override
        public void act(float delta) {
            setBackground(highlight != null ? highlight : pointer.isOver() ? over : up);
            super.act(delta);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);
            // .tile i: 6 in from the left and 3 up inside the border, the armor share of the width, 12 short of it.
            float inside = getWidth() - 2;
            float width = Math.min(inside * armor, inside - 12);
            if (width > 0) {
                float previous = batch.getPackedColor();
                batch.setColor(barColor.r, barColor.g, barColor.b, barColor.a * getColor().a * parentAlpha);
                bar.draw(batch, getX() + 7, getY() + 4, width, 2);
                batch.setPackedColor(previous);
            }
        }
    }

    /**
     * A unit on the rail: its sprite on a tile with the card's highlight, a bar at its left edge in the status line's
     * tone while the unit acts or awaits orders, and its armor along the bottom. The tooltip names it and its status;
     * it selects and opens the menu as a row does.
     */
    private final class RailItem extends Table {
        static final float WIDTH = 46;
        static final float HEIGHT = 40;
        private final GpuHudKit.UnitSprite sprite = kit.sprite(36, 30);
        private final Drawable up = ui.skin.getDrawable("row");
        private final Drawable over = ui.skin.getDrawable("row-over");
        private final Drawable picked = ui.skin.getDrawable("field-focused");
        private final Drawable friend = ui.skin.getDrawable("row-friend");
        private final Drawable bar = ui.skin.getDrawable("white");
        private final ClickListener pointer;
        private final Label tip;
        private Drawable highlight;
        private float armor;
        private Color accent;

        private RailItem(int id) {
            setName("forces-rail-" + id);
            setBackground(up);
            setTouchable(Touchable.enabled);
            add(sprite);
            tip = ui.tip(this).getActor();
            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    select.accept(id);
                }
            };
            addListener(pointer);
            addListener(UnitRow.menuOpener(menu, id, GpuForcesPanel.this::unit));
        }

        void show(UnitStatus unit, Line line) {
            sprite.set(unit.icon(), UnitRow.spriteColor(unit));
            tip.setText(unit.chassis() + "\n" + line.text());
            highlight = unit.id() != shown.card() ? null : unit.id() == shown.focus() ? picked : friend;
            armor = (float) Math.max(0, unit.armor());
            accent = line.tone() == Tone.WARN || line.tone() == Tone.OK ? line.tone().color : null;
            getColor().a = unit.destroyed() ? .4f : 1;
        }

        @Override
        public void act(float delta) {
            setBackground(highlight != null ? highlight : pointer.isOver() ? over : up);
            super.act(delta);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            if (accent != null) {
                batch.setColor(accent.r, accent.g, accent.b, accent.a * alpha);
                bar.draw(batch, getX() + 1, getY() + 3, 2, getHeight() - 6);
            }
            float width = Math.min((getWidth() - 2) * armor, getWidth() - 10);
            if (width > 0) {
                batch.setColor(UiTheme.MINT.r, UiTheme.MINT.g, UiTheme.MINT.b, UiTheme.MINT.a * alpha);
                bar.draw(batch, getX() + 5, getY() + 3, width, 2);
            }
            batch.setPackedColor(previous);
        }
    }
}
