/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.bot.princess.CombatPosture;
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
import megamek.common.force.Force;
import megamek.common.interfaces.IStartingPositions;
import megamek.common.units.Entity;

/**
 * The forces list in the left column (battle HUD layout, zone 6): the own and allied units by formation, each a
 * three-line row (name and duplicate marker, pilot and movement, armor, structure and heat), with search and, for a
 * force of more than a company, filter chips. It folds to a rail at the window's edge, the same rows drawn narrow, and
 * unfolds while the pointer rests on it, so a row stays under the pointer as it unfolds; the pin keeps it open. While
 * the unit sheet is open it shrinks to its strip. It shows the presented units and calls the HUD's selection rule,
 * the unit menu and the phase command; every rule stays with the client.
 */
final class GpuForcesPanel implements GpuHud.Component {
    /** The phase displays' next-unit commands, by the ids their buttons give the scene's phase commands (C10). */
    private static final Set<String> NEXT_COMMANDS = Set.of(MoveCommand.MOVE_NEXT.getCmd(),
          FiringDisplay.FiringCommand.FIRE_NEXT.getCmd(), PhysicalDisplay.PhysicalCommand.PHYSICAL_NEXT.getCmd(),
          DeploymentDisplay.DeployCommand.DEPLOY_NEXT.getCmd(),
          PrephaseDisplay.PrephaseCommand.PREPHASE_NEXT.getCmd());
    /** The rail's width, which the HUD gives the forces slot while the panel is folded to it. */
    static final float RAIL_WIDTH = 128;
    /** Row, group header and panel header heights; the rail and the open list share them, so rows stay in place. */
    private static final float ROW_HEIGHT = 44;
    private static final float GROUP_HEIGHT = 22;
    /** An allied bot's header: its name with the Bot button, and its personality and posture under it. */
    private static final float BOT_HEIGHT = 40;
    /** A bot's lances and their chevrons sit under its name. */
    private static final float LANCE_INDENT = 22;
    /** A collapsed group: its header line, and its units' small sprites under it. */
    private static final float STRIP_HEIGHT = 20;
    private static final float HEAD_HEIGHT = 34;
    /** A force of more units than a company gets the filter chips. */
    private static final int COMPANY = 12;
    /** The pointer rests this long on the rail before it unfolds, and the list folds this long after it left. */
    private static final long UNFOLD_DELAY = 250;
    private static final long FOLD_DELAY = 400;
    /** The client's duplicate marker ({@code Entity.createShortName}): " #2", or " ID:17" with unit ids shown. */
    private static final Pattern MARKER = Pattern.compile(" (#\\d+|ID:\\d+)$");
    private static final String SEPARATOR = " \u00B7 ";
    private static final Color MOVED = Color.valueOf("BFEEE2");
    private static final Color HOT = Color.valueOf("FF8A5B");
    private static final Color INK = Color.valueOf("0B0F14");
    private static final Color SEPARATOR_LINE = new Color(43 / 255f, 56 / 255f, 71 / 255f, .45f);
    private static final Color GROUP_FILL = new Color(1, 1, 1, .03f);
    private static final Color HOVER = new Color(1, 1, 1, .07f);
    private static final Color INSPECTED = new Color(1, 1, 1, .05f);
    private static final Color SELECTED = new Color(UiTheme.MINT.r, UiTheme.MINT.g, UiTheme.MINT.b, .12f);
    private static final Color HEAT_TRACK = new Color(1, 1, 1, .08f);

    /** The filter chips of a force larger than a company. */
    private enum Filter {
        ALL("GpuBoard.hud.forces.filterAll"),
        PENDING("GpuBoard.hud.forces.filterPending"),
        DAMAGED("GpuBoard.hud.forces.filterDamaged");

        private final String key;

        Filter(String key) {
            this.key = key;
        }
    }

    /**
     * Everything the list shows. The snapshots keep their identity while unchanged, so comparing this each frame is
     * cheap, and the list is rebuilt only when it differs. {@code card} is the unit the card shows
     * ({@link GpuHudState#cardUnit}); {@code revision} counts the collapsed groups' changes. {@code bots} says which
     * units allied bots own and their lances; {@code lance} is the selected bot lance's group key, else empty;
     * {@code sheetBot} the bot the open bot sheet shows, else -1.
     */
    private record View(List<UnitStatus> units, GpuBattleStatus.Snapshot status, int focus, int card, Filter filter,
          String query, int revision, boolean folded, List<GpuBoardSource.Bind> binds,
          GpuPlayers.Snapshot players, GpuBots.Snapshot bots, String lance, int sheetBot) { }

    /** A bot lance's group: the allied bot and the lance's force. */
    private record BotGroup(int botId, int forceId) { }

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
    /**
     * The open list's header (title, unit count, search, pin, overview) and the rail's: the units still to act. The
     * pin is only on the open list: on the rail the pointer moving to it would unfold the list under it.
     */
    private final Table openHead = new Table();
    private final Table railHead = new Table();
    private final Cell<Table> headCell;
    private final Label summary;
    private final Label railCount;
    private final Container<Actor> railDisc = new Container<>();
    private final Container<Actor> openDisc = new Container<>();
    private final UiButton overview;
    private final TextTooltip overviewTip;
    private final UiButton searchToggle;
    private final UiButton pin;
    private final Table chips = new Table();
    private final Container<Actor> chipsSpacer = new Container<>();
    private final Map<Filter, UiButton> chipButtons = new HashMap<>();
    private final Cell<Actor> chipsCell;
    private final UiKit.SearchField search;
    private final Table searchRow = new Table();
    private final Cell<Actor> searchCell;
    private final Table list = new Table();
    private final UiButton stripNext;
    /** The units' rows and the groups' headers, kept across rebuilds so that their pointer state stays with them. */
    private final Map<Integer, ForceRow> rows = new HashMap<>();
    private final Map<String, GroupHeader> headers = new HashMap<>();
    private final Map<Integer, BotHeader> botHeaders = new HashMap<>();
    /** The listed groups that are an allied bot's lances, by group key. */
    private final Map<String, BotGroup> botGroups = new HashMap<>();
    private final Set<String> collapsed = new HashSet<>();
    private boolean folded;
    /** The open list's state while not pinned: unfolded by the resting pointer, folded a moment after it left. */
    private boolean unfolded;
    private boolean hovered;
    private long enteredAt;
    private long leftAt;
    private boolean searching;
    private Filter filter = Filter.ALL;
    private int revision;
    private int pending;
    private GpuHud.Inputs inputs;
    private View shown;
    /** What the list's actors were laid out for: fold, chips, query, filter, collapse revision, groups and units. */
    private List<Object> shownLayout;

    /**
     * {@code menu} opens this component's unit menus (C13). {@code select} selects or inspects a unit
     * ({@link GpuHud#select}).
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
        pin = ui.button("hud-icon", "pin", null, null);
        pin.setName("forces-pin");
        ui.tip(pin).getActor().setText(text("GpuBoard.hud.forces.pinTip"));
        onChange(pin, () -> {
            state.forcesPinned = !state.forcesPinned;
            unfolded = state.forcesPinned || hovered;
        });

        openHead.setName("forces-head");
        openHead.pad(0, 12, 0, 6);
        openHead.add(ui.label(UiTheme.upper(text("GpuBoard.hud.forces.yourForce")), "hud-title", 14, UiTheme.TEXT))
              .padRight(8);
        summary = ui.label("", "hud-medium", 12, UiTheme.MUTED);
        summary.setName("forces-summary");
        openHead.add(openDisc).padRight(5);
        openHead.add(summary).growX().left();
        openHead.add(searchToggle).size(28).padLeft(2);
        openHead.add(pin).size(28).padLeft(2);
        openHead.add(overview).size(28).padLeft(2);
        railHead.setName("forces-rail-head");
        railHead.left().padLeft(12);
        railCount = ui.label("", "hud-medium", 12, UiTheme.MUTED);
        railHead.add(railDisc).padRight(5);
        railHead.add(railCount);
        headCell = full.add(openHead).growX().height(HEAD_HEIGHT);
        full.row();
        full.add(ui.solid(UiTheme.LINE)).growX().height(1).row();

        // A force larger than a company: All, Pending and Damaged.
        chips.setName("forces-chips");
        chips.pad(7, 10, 7, 10).left();
        for (Filter each : Filter.values()) {
            UiButton chip = ui.button("hud-mini", null, "", null);
            chip.setName("forces-chip-" + each.name().toLowerCase(Locale.ROOT));
            onChange(chip, () -> filter = each);
            chipButtons.put(each, chip);
            chips.add(chip).padRight(6);
        }
        chipsCell = full.add((Actor) null).growX();
        full.row();

        search = ui.search(text("GpuBoard.hud.forces.searchPlaceholder"));
        search.field.setName("forces-search-field");
        searchRow.pad(8, 10, 8, 10);
        searchRow.add(search).growX();
        searchCell = full.add((Actor) null).growX();
        full.row();

        list.top().left();
        ScrollPane scroll = ui.scrollList(list);
        scroll.setName("forces-list");
        full.add(scroll).grow().minHeight(0).row();

        // The strip (unit panel design 3.3): the title, the pending count, Next pending and ⌄, which closes the sheet.
        stripNext = ui.button("hud-mini", null, text("GpuBoard.hud.forces.nextPending"), null);
        stripNext.setName("forces-strip-next");
        onChange(stripNext, this::runNext);
        UiButton restore = ui.button("hud-icon", "chevron-down", null, null);
        restore.setName("forces-restore");
        ui.tip(restore).getActor().setText(text("GpuBoard.hud.forces.restoreTip"));
        onChange(restore, () -> state.recordOpen = false);
        strip.setName("forces-strip");
        strip.pad(0, 12, 0, 6);
        strip.add(ui.label(UiTheme.upper(text("GpuBoard.hud.forces.yourForce")), "hud-title", 16, UiTheme.TEXT))
              .height(40).padRight(8);
        stripCount = ui.label("", "hud-body", 12, UiTheme.MUTED);
        stripCount.setEllipsis(true);
        strip.add(stripCount).minWidth(0).growX().left();
        strip.add(stripNext).padLeft(8);
        strip.add(restore).size(30).padLeft(4);

        // The pointer resting on the panel unfolds the rail; it folds again a moment after the pointer left.
        root.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1 && !hovered && (fromActor == null || !fromActor.isDescendantOf(root))) {
                    hovered = true;
                    enteredAt = TimeUtils.millis();
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

    /** The phase's next-unit command, as the strip's Next pending runs it. */
    private void runNext() {
        BoardScene.Command command = nextCommand();
        if (command != null && command.enabled()) {
            // The next unit is the selected one, also when MegaMek's current unit comes again.
            state.restoreSelection();
            command.action().run();
        }
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        long now = TimeUtils.millis();
        if (state.forcesPinned || searching) {
            unfolded = true;
        } else if (hovered) {
            unfolded |= now - enteredAt >= UNFOLD_DELAY;
        } else if (unfolded && !menu.isOpen() && now - leftAt >= FOLD_DELAY) {
            unfolded = false;
        }
        boolean sheet = GpuRecordSheet.open(state);
        folded = !unfolded && !sheet;
        GpuBots.Lance lance = state.selectedLance();
        View view = new View(state.presentedUnits(), inputs.frame().status(), state.focus(), state.cardUnit(), filter,
              searching ? search.field.getText().strip().toLowerCase(Locale.ROOT) : "", revision, folded,
              inputs.preferences().binds(), inputs.frame().panels().players(), state.bots(),
              lance == null ? "" : botKey(state.selectedLanceBot(), lance.forceId()),
              state.dialog == GpuHudState.Dialog.BOTS ? state.botSheetId : -1);
        if (!view.equals(shown)) {
            shown = view;
            rebuild();
        }
        BoardScene.Command command = nextCommand();
        stripNext.setDisabled(pending == 0 || command == null || !command.enabled());
        pin.pressed(state.forcesPinned);
        Table wanted = sheet ? strip : full;
        if (content.getActor() != wanted) {
            content.setActor(wanted);
        }
        Table head = folded ? railHead : openHead;
        if (headCell.getActor() != head) {
            headCell.setActor(head);
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
        overviewTip.getActor().setText(text("GpuBoard.hud.forces.overviewTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.UNIT_OVERVIEW)));
        GamePhase phase = shown.status().phase();
        // The list holds the own and allied units; the enemy's are the contacts' and the target card's.
        List<UnitStatus> force = shown.units().stream().filter(unit -> unit.side() != Side.ENEMY).toList();
        pending = (int) force.stream().filter(unit -> unit.side() == Side.OWN && unit.pending()).count();
        String verb = verb(phase);
        // Both headers count the units still to act (the amber disc of their rows), else the force.
        boolean waiting = verb != null && pending > 0;
        String count = String.valueOf(waiting ? pending : force.size());
        for (Label label : List.of(summary, railCount)) {
            label.setText(count);
            label.setColor(waiting ? UiTheme.TEXT : UiTheme.MUTED);
        }
        railDisc.setActor(waiting ? disc(UiTheme.AMBER) : null);
        openDisc.setActor(waiting ? disc(UiTheme.AMBER) : null);
        stripCount.setText(pending > 0 ? text("GpuBoard.hud.forces.stripPending", pending) : "");

        boolean big = force.size() > COMPANY;
        if (big) {
            chipButtons.get(Filter.ALL).setText(text(Filter.ALL.key, force.size()));
            chipButtons.get(Filter.PENDING).setText(text(Filter.PENDING.key,
                  force.stream().filter(UnitStatus::pending).count()));
            chipButtons.get(Filter.DAMAGED).setText(text(Filter.DAMAGED.key,
                  force.stream().filter(GpuForcesPanel::damaged).count()));
            chipButtons.forEach((each, chip) -> chip.pressed(each == shown.filter()));
            chipsSpacer.height(chips.getPrefHeight());
        }
        Actor chipsShown = !big ? null : shown.folded() ? chipsSpacer : chips;
        if (chipsCell.getActor() != chipsShown) {
            chipsCell.setActor(chipsShown);
        }
        Filter active = big ? shown.filter() : Filter.ALL;
        List<UnitStatus> listed = force.stream().filter(this::matches).filter(unit -> passes(unit, active)).toList();
        // The own formations and allied players first, then each allied bot's lances in the bot's order.
        Map<String, List<UnitStatus>> groups = GpuHudKit.groups(listed, this::groupKey,
              Comparator.comparingInt(this::rank));
        botGroups.clear();
        listed.forEach(unit -> {
            GpuBots.BotRow bot = shown.bots().alliedOwnerOf(unit.id());
            if (bot != null) {
                GpuBots.Lance lance = bot.lanceOf(unit.id());
                int forceId = lance == null ? Force.NO_FORCE : lance.forceId();
                botGroups.putIfAbsent(botKey(bot.id(), forceId), new BotGroup(bot.id(), forceId));
            }
        });
        // While the same groups list the same units, only the rows' and headers' contents change: re-adding them would
        // cancel a press on one (Scene2D unfocuses removed actors), and status updates arrive during the enemy's turn.
        // A force not split into formations is one unassigned group: it lists its units without a header, as the
        // panel's header already counts them.
        boolean bare = groups.size() == 1 && groups.containsKey(text("GpuBoard.hud.forces.unassigned"));
        List<Object> layout = List.of(shown.folded(), big, shown.query(), active, revision, groups.entrySet().stream()
              .map(group -> List.of(group.getKey(), group.getValue().stream().map(UnitStatus::id).toList())).toList());
        if (!layout.equals(shownLayout)) {
            shownLayout = layout;
            list.clearChildren();
            if (listed.isEmpty()) {
                ui.empty(list, shown.query().isEmpty() ? text("GpuBoard.hud.forces.noUnits")
                      : text("GpuBoard.hud.forces.noMatch", shown.query()));
            }
            headers.keySet().retainAll(bare ? Set.of() : groups.keySet());
            botHeaders.keySet().retainAll(botGroups.values().stream().map(BotGroup::botId).toList());
            int[] lastBot = { -1 };
            groups.forEach((key, members) -> {
                BotGroup botGroup = botGroups.get(key);
                if (botGroup != null && botGroup.botId() != lastBot[0]) {
                    lastBot[0] = botGroup.botId();
                    boolean botClosed = collapsed.contains(botKey(botGroup.botId()));
                    list.add(botHeaders.computeIfAbsent(botGroup.botId(), BotHeader::new)).growX().minWidth(0)
                          .height(botClosed ? BOT_HEIGHT + STRIP_HEIGHT : BOT_HEIGHT).row();
                }
                if (botGroup != null && collapsed.contains(botKey(botGroup.botId()))) {
                    return;
                }
                if (!bare) {
                    GroupHeader header = headers.computeIfAbsent(key, GroupHeader::new);
                    list.add(header).growX().minWidth(0)
                          .height(collapsed.contains(key) ? GROUP_HEIGHT + STRIP_HEIGHT : GROUP_HEIGHT).row();
                }
                if (bare || !collapsed.contains(key)) {
                    members.forEach(unit -> list.add(rows.computeIfAbsent(unit.id(), ForceRow::new)).growX()
                          .minWidth(0).height(ROW_HEIGHT).row());
                }
            });
            rows.keySet().retainAll(listed.stream().map(UnitStatus::id).toList());
        }
        Map<Integer, List<UnitStatus>> botMembers = new HashMap<>();
        groups.forEach((key, members) -> {
            BotGroup botGroup = botGroups.get(key);
            if (botGroup != null) {
                botMembers.computeIfAbsent(botGroup.botId(), id -> new ArrayList<>()).addAll(members);
                if (collapsed.contains(botKey(botGroup.botId()))) {
                    return;
                }
            }
            if (!bare) {
                headers.get(key).show(members, verb, botGroup);
            }
            if (bare || !collapsed.contains(key)) {
                members.forEach(unit -> rows.get(unit.id()).show(unit));
            }
        });
        botMembers.forEach((botId, members) -> botHeaders.get(botId).show(members));
    }

    /** A bot lance's group key, which no formation name takes. */
    private static String botKey(int botId, int forceId) {
        return "\u0001bot:" + botId + ":" + forceId;
    }

    /** A bot's own collapse key. */
    private static String botKey(int botId) {
        return "\u0001bot:" + botId;
    }

    /** The unit's group: its allied bot's lance, else its formation as {@link GpuHudKit#formation} names it. */
    private String groupKey(UnitStatus unit) {
        GpuBots.BotRow bot = shown.bots().alliedOwnerOf(unit.id());
        if (bot == null) {
            return formation(unit);
        }
        GpuBots.Lance lance = bot.lanceOf(unit.id());
        return botKey(bot.id(), lance == null ? Force.NO_FORCE : lance.forceId());
    }

    /** The group order: 0 for the own and allied players' formations, then by bot and by the bot's lance. */
    private int rank(UnitStatus unit) {
        GpuBots.BotRow bot = shown.bots().alliedOwnerOf(unit.id());
        if (bot == null) {
            return 0;
        }
        GpuBots.Lance lance = bot.lanceOf(unit.id());
        return 1 + shown.bots().bots().indexOf(bot) * 1000 + (lance == null ? 999 : bot.lances().indexOf(lance));
    }

    /** The phase's verb for the units still to act ("to move"), or null in a phase in which none acts in turn. */
    private static String verb(GamePhase phase) {
        if (phase.isDeployment()) {
            return "GpuBoard.hud.forces.verbDeploy";
        } else if (phase.isMovement()) {
            return "GpuBoard.hud.forces.verbMove";
        } else if (phase.isFiring() || phase.isTargeting() || phase.isOffboard()) {
            return "GpuBoard.hud.forces.verbFire";
        } else if (phase.isPhysical() || phase.isPremovement() || phase.isPreFiring()) {
            return "GpuBoard.hud.forces.verbAct";
        }
        return null;
    }

    private String formation(UnitStatus unit) {
        return GpuHudKit.formation(unit, shown.status(), shown.players());
    }

    /**
     * The search (C3) by the formation the list shows ({@link GpuHudKit#matches}); an allied bot's unit by its bot's
     * and its lance's names.
     */
    private boolean matches(UnitStatus unit) {
        GpuBots.BotRow bot = shown.bots().alliedOwnerOf(unit.id());
        GpuBots.Lance lance = bot == null ? null : bot.lanceOf(unit.id());
        String formation = bot == null ? formation(unit) : lance == null ? bot.name() : bot.name() + " " + lance.name();
        return GpuHudKit.matches(unit, formation, shown.query());
    }

    private static boolean passes(UnitStatus unit, Filter filter) {
        return switch (filter) {
            case ALL -> true;
            case PENDING -> unit.pending();
            case DAMAGED -> damaged(unit);
        };
    }

    /** Damaged or impaired, heat effects included (the prototype's "Running hot"). */
    private static boolean damaged(UnitStatus unit) {
        return unit.destroyed() || unit.damageLevel() > Entity.DMG_NONE || !unit.destroyedLocations().isEmpty()
              || !unit.heatEffects().isEmpty() || unit.statusWords().stream()
              .anyMatch(word -> word.severity() == UnitStatusWords.Severity.WARNING
                    || word.severity() == UnitStatusWords.Severity.CAUTION);
    }

    /** The client's duplicate marker of the unit's name ("#2"), empty for the first of its kind. */
    static String marker(UnitStatus unit) {
        Matcher matcher = MARKER.matcher(unit.name());
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * The rail's short name: the model code when it is a designation (letters and digits, such as STU-K10), else the
     * chassis' first word: a vehicle's "(SL)" or a ProtoMek's "B" tells nothing on its own.
     */
    static String code(UnitStatus unit) {
        String model = unit.model().strip();
        if (designation(model)) {
            return model.toUpperCase(Locale.ROOT);
        }
        String chassis = unit.chassis().strip();
        int space = chassis.indexOf(' ');
        String word = space < 0 ? chassis : chassis.substring(0, space);
        return word.substring(0, Math.min(7, word.length())).toUpperCase(Locale.ROOT);
    }

    /** A model name that reads as a designation: at most 7 characters with a letter and a digit, not in brackets. */
    private static boolean designation(String model) {
        return model.length() <= 7 && !model.startsWith("(") && model.chars().anyMatch(Character::isLetter)
              && model.chars().anyMatch(Character::isDigit);
    }

    /** The unit the local player acts with, or the own focus unit before its turn (C.5). */
    private int acting() {
        return UnitRow.acting(shown.status(), shown.focus());
    }

    private UnitStatus unit(int id) {
        return shown.units().stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /** The unit's state, which its tag (open) and dot (rail) show. */
    /** WAITING: it deploys this round, but not in the local player's next turn (GpuDeployment#waits). */
    private enum State { NONE, NOW, PENDING, WAITING, DONE, DESTROYED }

    private State stateOf(UnitStatus unit) {
        GamePhase phase = shown.status().phase();
        if (unit.destroyed()) {
            return State.DESTROYED;
        } else if (unit.id() == acting() && shown.status().myTurn() && !unit.done()) {
            return State.NOW;
        } else if (UnitRow.acted(unit, phase) || (phase.isDeployment() && GpuDeployment.placed(unit))) {
            return State.DONE;
        } else if (GpuDeployment.waits(unit)) {
            return State.WAITING;
        } else if (unit.pending() && verb(phase) != null) {
            return State.PENDING;
        }
        return State.NONE;
    }

    /**
     * The second line's right part: what keeps the unit from acting (shut down, prone, deploying later, walking on),
     * else its movement this round with its target modifier, else its movement points.
     */
    private Line detail(UnitStatus unit) {
        GamePhase phase = shown.status().phase();
        if (unit.destroyed()) {
            return new Line("", Tone.NORMAL);
        } else if (UnitRow.shutDown(unit)) {
            return new Line(text("GpuBoard.hud.state.shutDown"), Tone.BAD);
        } else if (UnitRow.prone(unit)) {
            return new Line(text("GpuBoard.hud.state.prone"), Tone.BAD);
        }
        boolean acting = unit.id() == acting();
        if (phase.isMovement() && !acting && GpuDeployment.waits(unit)) {
            // An aerospace unit moves, or walks on, only after every ground unit: it is no unit to pick now.
            return new Line(text("GpuBoard.hud.deploy.wait." + unit.deployWait().name()), Tone.NORMAL);
        }
        if (unit.walksOn() && unit.position() == null && !acting) {
            // Under walk-on deployment the unit enters from its edge in a movement phase instead of being placed.
            return phase.isMovement() && unit.pending()
                  ? new Line(text("GpuBoard.hud.status.walkOnNow", IStartingPositions.getDisplayName(
                        unit.walkOnZone())), Tone.WARN)
                  : new Line(GpuDeployment.walkOnLine(unit), Tone.NORMAL);
        }
        if (phase.isDeployment() && !acting && !GpuDeployment.placed(unit)) {
            if (GpuDeployment.later(unit)) {
                return new Line(text("GpuBoard.hud.deploy.later"), Tone.NORMAL);
            } else if (GpuDeployment.waits(unit)) {
                return new Line(text("GpuBoard.hud.deploy.wait." + unit.deployWait().name()), Tone.NORMAL);
            }
        }
        if (!unit.moved().isEmpty()) {
            String moved = text("GpuBoard.hud.forces.moved", unit.moved(), unit.hexesMoved());
            return new Line(unit.tmmShown() ? moved + SEPARATOR + String.format(Locale.ROOT, "%+d", unit.tmm())
                  : moved, Tone.OK);
        }
        return new Line(text("GpuBoard.hud.forces.mp", unit.walk(), unit.run(), unit.jump()), Tone.NORMAL);
    }

    private static String join(String first, String second) {
        return second.isEmpty() ? first : first + SEPARATOR + second;
    }

    /** The state disc of the rail's dot and the open row's pending tag. */
    private Actor disc(Color color) {
        Image disc = new Image(ui.skin.getDrawable("unit-disc-6"));
        disc.setColor(color);
        return disc;
    }

    /** Collapses the group or bot {@code key}, or opens it again. */
    private void toggleCollapsed(String key) {
        if (!collapsed.remove(key)) {
            collapsed.add(key);
        }
        revision++;
    }

    /** A collapsed group's or bot's units as small sprites on a line. */
    private final class SpriteStrip extends Table {
        private final Map<Integer, GpuHudKit.UnitSprite> miniSprites = new HashMap<>();

        private SpriteStrip() {
            left();
            setClip(true);
        }

        void show(List<UnitStatus> members, float indent) {
            clearChildren();
            padLeft(indent);
            miniSprites.keySet().retainAll(members.stream().map(UnitStatus::id).toList());
            for (UnitStatus unit : members) {
                GpuHudKit.UnitSprite sprite = miniSprites.computeIfAbsent(unit.id(), id -> kit.sprite(18, 14));
                sprite.set(unit.icon(), UnitRow.spriteColor(unit));
                sprite.getColor().a = unit.destroyed() ? .4f : .8f;
                add(sprite).padRight(3);
            }
        }
    }

    /**
     * An allied bot's header over its lances: the bot icon, its name and the Bot button, which opens its bot sheet,
     * and under them its personality and posture. A click elsewhere collapses the bot to its units' sprites. On the
     * rail it shows the same under the name.
     */
    private final class BotHeader extends Table {
        private final int botId;
        private final UiButton open;
        private final Label name = ui.label("", "hud-name", 13.5f, UiTheme.TEXT);
        private final Label railName = ui.label("", "hud-caption", 10.5f, UiTheme.TEXT);
        private final Label about = ui.label("", "hud-small", 11, UiTheme.MUTED);
        private final SpriteStrip sprites = new SpriteStrip();

        private BotHeader(int botId) {
            this.botId = botId;
            setName("forces-bot-" + botId);
            setTouchable(Touchable.enabled);
            name.setEllipsis(true);
            railName.setEllipsis(true);
            about.setEllipsis(true);
            open = ui.button("hud-mini", "bot", text("GpuBoard.hud.bots.chip"), null);
            open.setName("forces-bot-open-" + botId);
            ui.tip(open).getActor().setText(text("GpuBoard.hud.forces.botTip"));
            onChange(open, () -> state.toggleBot(botId));
            addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (!event.getTarget().isDescendantOf(open)) {
                        toggleCollapsed(botKey(botId));
                    }
                }
            });
        }

        void show(List<UnitStatus> members) {
            clearChildren();
            GpuBots.BotRow bot = shown.bots().bot(botId);
            if (bot == null) {
                return;
            }
            boolean closed = collapsed.contains(botKey(botId));
            name.setText(bot.name());
            about.setText(about(bot));
            Table top = new Table();
            Table bottom = new Table();
            if (shown.folded()) {
                railName.setText(UiTheme.upper(bot.name()));
                top.pad(0, 10, 0, 8);
                top.add(ui.icon("bot", 13, UiTheme.tokenColor("accent.primary"))).padRight(5);
                top.add(railName).growX().minWidth(0).left();
                bottom.pad(0, 28, 0, 8);
                bottom.add(about).growX().minWidth(0).left();
            } else {
                top.pad(0, 10, 0, 6);
                top.add(ui.icon(closed ? "chevron-right" : "chevron-down", 12, UiTheme.MUTED));
                top.add(ui.icon("bot", 16, UiTheme.tokenColor("accent.primary"))).padLeft(6);
                top.add(name).minWidth(0).padLeft(6).left();
                top.add().growX();
                open.pressed(shown.sheetBot() == botId);
                top.add(open).padLeft(6);
                bottom.pad(0, 50, 0, 10);
                bottom.add(about).growX().minWidth(0).left();
            }
            if (about.getText().isEmpty()) {
                // Before the bot's first report there is nothing to say under its name.
                add(top).growX().minWidth(0).height(22).padTop(3).padBottom(3);
            } else {
                add(top).growX().minWidth(0).height(22).padTop(3).row();
                add(bottom).growX().minWidth(0).height(15);
            }
            if (closed) {
                row();
                sprites.show(members, shown.folded() ? 6 : 28);
                add(sprites).growX().minWidth(0).height(STRIP_HEIGHT).top();
            }
        }

        /**
         * The bot's personality once it reported it, and its posture when the player set one: Auto, the default,
         * decides each round and says nothing more.
         */
        private String about(GpuBots.BotRow bot) {
            GpuBots.Behavior behavior = bot.behavior();
            if (behavior == null) {
                return "";
            }
            String personality = behavior.personality() == null ? text("GpuBoard.hud.bots.custom")
                  : behavior.personality().label();
            return behavior.posture() == CombatPosture.AUTO ? personality
                  : personality + SEPARATOR + behavior.posture();
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            ui.fill(batch, GROUP_FILL, alpha, getX(), getY(), getWidth(), getHeight());
            ui.fill(batch, SEPARATOR_LINE, alpha, getX(), getY(), getWidth(), 1);
            batch.setPackedColor(previous);
            super.draw(batch, parentAlpha);
        }
    }

    /**
     * A formation's header: chevron, name and how many of its units are still to act; a click collapses it, and a
     * collapsed one lists its units as small sprites on a line under it. On the rail it shows the name alone. An
     * allied bot's lance sits indented under its bot: a CASPAR lance shows its orders and a click selects it for the
     * card (its chevron still folds it); Princess takes orders unit by unit, so its lance only folds.
     */
    private final class GroupHeader extends Table {
        private final String key;
        private final Label count = ui.label("", "hud-small", 11, UiTheme.MUTED);
        private final SpriteStrip sprites = new SpriteStrip();
        private final ClickListener pointer;
        private BotGroup botGroup;
        private boolean selectable;
        private boolean selected;

        private GroupHeader(String key) {
            this.key = key;
            BotGroup group = botGroups.get(key);
            setName(group == null ? "forces-group-" + key : "forces-lance-" + group.botId() + "-" + group.forceId());
            setTouchable(Touchable.enabled);
            count.setEllipsis(true);
            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (selectable && (shown.folded() || x > LANCE_INDENT + 16)) {
                        state.selectLance(botGroup.botId(), botGroup.forceId());
                    } else {
                        toggleCollapsed(key);
                    }
                }
            };
            addListener(pointer);
        }

        void show(List<UnitStatus> members, String verb, BotGroup group) {
            clearChildren();
            botGroup = group;
            GpuBots.BotRow bot = group == null ? null : shown.bots().bot(group.botId());
            GpuBots.Lance lance = bot == null ? null : bot.lances().stream()
                  .filter(each -> each.forceId() == group.forceId()).findFirst().orElse(null);
            selectable = lance != null && bot.lancesSelectable();
            selected = selectable && key.equals(shown.lance());
            String name = lance != null ? lance.name() : key;
            boolean closed = collapsed.contains(key);
            long left = members.stream().filter(UnitStatus::pending).count();
            boolean waiting = verb != null && left > 0;
            Table line = new Table();
            float indent = group == null ? 10 : LANCE_INDENT;
            if (shown.folded()) {
                // The rail names the group only: a chevron or a count would read as a control, and the pointer
                // resting on the rail unfolds it anyway.
                Label label = ui.label(UiTheme.upper(name), "hud-caption", 10, selected ? UiTheme.TEXT : UiTheme.MUTED);
                label.setEllipsis(true);
                line.pad(0, indent, 0, 8);
                line.add(label).growX().minWidth(0).left();
                add(line).growX().minWidth(0).height(GROUP_HEIGHT);
            } else {
                line.add(ui.icon(closed ? "chevron-right" : "chevron-down", 12, UiTheme.MUTED));
                line.pad(0, indent, 0, 10);
                if (group != null) {
                    line.add(ui.icon("account-tree", 12, selected ? UiTheme.MINT : UiTheme.MUTED)).padLeft(6);
                }
                Label label = ui.label(UiTheme.upper(name), "hud-caption", 10.5f,
                      selected ? UiTheme.TEXT : UiTheme.MUTED);
                label.setEllipsis(true);
                line.add(label).minWidth(0).padLeft(6).left();
                if (selectable) {
                    count.setText(lance.orders());
                    count.setColor(selected ? UiTheme.TEXT : UiTheme.MUTED);
                    line.add(count).growX().minWidth(0).padLeft(8).right();
                } else {
                    line.add().growX();
                    count.setText(verb == null ? text("GpuBoard.hud.forces.groupCount", members.size())
                          : text("GpuBoard.hud.forces.pendingOf", left, members.size(), text(verb)));
                    count.setColor(waiting ? UiTheme.MINT : UiTheme.MUTED);
                    line.add(count).padLeft(6);
                }
                add(line).growX().minWidth(0).height(GROUP_HEIGHT);
            }
            if (!closed) {
                return;
            }
            row();
            sprites.show(members, shown.folded() ? 6 : indent + 18);
            add(sprites).growX().minWidth(0).height(STRIP_HEIGHT).top();
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            ui.fill(batch, GROUP_FILL, alpha, getX(), getY(), getWidth(), getHeight());
            if (selected) {
                ui.fill(batch, INSPECTED, alpha, getX(), getY(), getWidth(), getHeight());
                ui.fill(batch, UiTheme.MINT, alpha * .6f, getX(), getY(), 2, getHeight());
            }
            if (selectable && pointer.isOver()) {
                ui.fill(batch, HOVER, alpha, getX(), getY(), getWidth(), getHeight());
            }
            ui.fill(batch, SEPARATOR_LINE, alpha, getX(), getY(), getWidth(), 1);
            batch.setPackedColor(previous);
            super.draw(batch, parentAlpha);
        }
    }

    /**
     * A unit's row. Open: its sprite, then the name with its duplicate marker, the model and the state tag; the pilot
     * with gunnery/piloting and the movement; the armor and structure bars and the heat. On the rail: the TMM once it
     * moved, the sprite, the state dot, the short name with its marker, mini armor and structure bars and a heat strip
     * along the right edge. Both forms are as tall, so the rail unfolds without moving a row. A click selects, a right
     * click opens the unit menu.
     */
    private final class ForceRow extends Table {
        private final ClickListener pointer;
        private final Cell<Table> face;
        // Open form.
        private final Table open = new Table();
        private final GpuHudKit.UnitSprite sprite = kit.sprite(36, 28);
        private final Label name = ui.label("", "hud-name", 13.5f, UiTheme.TEXT);
        private final Label marker = ui.label("", "hud-name", 13.5f, UiTheme.MINT);
        private final Label model = ui.label("", "hud-small", 11, UiTheme.MUTED);
        /** "Leader" on the formation leader of an allied bot's lance. */
        private final Label leader = ui.label("", "hud-caption", 10, UiTheme.MUTED);
        private final Container<Actor> tag = new Container<>();
        private final Label pilot = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        private final Label skills = ui.label("", "hud-medium", 11.5f, UiTheme.TEXT);
        private final Label detail = ui.label("", "hud-medium", 11.5f, UiTheme.TEXT);
        private final GpuHudKit.MeterBar armor = kit.bar(66, 5);
        private final GpuHudKit.MeterBar structure = kit.bar(46, 5);
        private final Label structureCaption = ui.label("S", "hud-caption", 10, UiTheme.MUTED);
        private final Label heat = ui.label("", "hud-medium", 11.5f, UiTheme.AMBER);
        private final UiKit.Icon flame = ui.icon("flame", 12, UiTheme.AMBER);
        // Rail form.
        private final Table rail = new Table();
        private final GpuHudKit.UnitSprite railSprite = kit.sprite(36, 28);
        private final Label tmm = ui.label("", "hud-medium", 10, MOVED);
        private final Container<Actor> dot = new Container<>();
        private final Label code = ui.label("", "hud-name", 10.5f, UiTheme.TEXT);
        private final Label codeMarker = ui.label("", "hud-name", 10.5f, UiTheme.MINT);
        private final GpuHudKit.MeterBar railArmor = kit.bar(40, 3);
        private final GpuHudKit.MeterBar railStructure = kit.bar(40, 3);
        private final Cell<GpuHudKit.MeterBar> railStructureCell;
        private final Color heatColor = new Color();
        private float heatShare;
        /** The card's highlight: 0 none, 1 the selected focus, 2 an inspected unit. */
        private int highlight;
        /** Why the unit waits for a later deployment turn, while it does; else null. */
        private TextTooltip waitTip;
        /** Whether a click picks the unit: not in the local deployment turn while it cannot deploy now. */
        private boolean pickable = true;

        private ForceRow(int id) {
            setName("forces-unit-" + id);
            setTouchable(Touchable.enabled);
            face = add(open).grow();

            name.setEllipsis(true);
            model.setEllipsis(true);
            pilot.setEllipsis(true);
            detail.setEllipsis(true);
            Table line1 = new Table();
            line1.add(name).minWidth(0);
            line1.add(marker).padLeft(4);
            line1.add(model).growX().minWidth(0).padLeft(6).left();
            line1.add(leader).padLeft(6);
            line1.add(tag).padLeft(6);
            Table line2 = new Table();
            line2.add(pilot).minWidth(0).left();
            line2.add(skills).padLeft(5);
            line2.add().growX();
            line2.add(detail).minWidth(0).padLeft(6).right();
            Table line3 = new Table();
            line3.add(ui.label("A", "hud-caption", 10, UiTheme.MUTED)).padRight(4);
            line3.add(armor).padRight(8);
            line3.add(structureCaption).padRight(4);
            line3.add(structure);
            line3.add().growX();
            line3.add(flame).padRight(2);
            line3.add(heat);
            Table text = new Table();
            text.defaults().growX().minWidth(0);
            text.add(line1).height(15).row();
            text.add(line2).height(14).row();
            text.add(line3).height(11);
            open.pad(0, 8, 0, 10);
            open.add(sprite).width(42).padRight(8);
            open.add(text).growX().minWidth(0);

            // The sprite sits where the open row has it, so the list unfolds around it without moving it.
            code.setEllipsis(true);
            Table codeLine = new Table();
            codeLine.add(code).minWidth(0);
            codeLine.add(codeMarker).padLeft(2);
            codeLine.add().growX();
            codeLine.add(dot).padLeft(3);
            Table side = new Table();
            side.add(codeLine).growX().minWidth(0).height(15).row();
            side.add(railArmor).growX().height(3).padTop(4).row();
            railStructureCell = side.add(railStructure).growX().height(3).padTop(2);
            rail.pad(0, 8, 0, 9);
            rail.add(railSprite).width(42).padRight(6);
            rail.add(side).growX().minWidth(0);

            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    select.accept(id);
                }
            };
            addListener(pointer);
            addListener(UnitRow.menuOpener(menu, id, GpuForcesPanel.this::unit));
        }

        void show(UnitStatus unit) {
            Table wanted = shown.folded() ? rail : open;
            if (face.getActor() != wanted) {
                face.setActor(wanted);
            }
            State state = stateOf(unit);
            boolean hasStructure = unit.marks().structureArgb() != 0 || unit.marks().equals(GpuBattleStatus.Marks.NONE);
            int structureArgb = unit.marks().structureArgb();
            if (shown.folded()) {
                railSprite.set(unit.icon(), UnitRow.spriteColor(unit));
                code.setText(code(unit));
                codeMarker.setText(marker(unit));
                // A moved unit's dot gives way to its target movement modifier.
                boolean showTmm = state == State.DONE && !unit.moved().isEmpty() && unit.tmmShown();
                tmm.setText(showTmm ? String.format(Locale.ROOT, "%+d", unit.tmm()) : "");
                dot.setActor(showTmm ? tmm : dot(state));
                railArmor.set(unit.armor(), unit.marks().armorArgb());
                railStructure.set(unit.structure(), structureArgb);
                railStructure.setVisible(hasStructure);
                railStructureCell.height(hasStructure ? 3 : 0).padTop(hasStructure ? 2 : 0);
                heatShare = unit.heat() <= 0 ? 0 : Math.min(1, unit.heat() / 30f);
                heatColor.set(unit.heatEffects().isEmpty() ? UiTheme.AMBER : HOT);
            } else {
                sprite.set(unit.icon(), UnitRow.spriteColor(unit));
                name.setText(UiTheme.upper(unit.chassis()));
                marker.setText(marker(unit));
                model.setText(unit.model());
                GpuBots.BotRow bot = shown.bots().alliedOwnerOf(unit.id());
                GpuBots.Order order = bot == null ? null : bot.orders().get(unit.id());
                leader.setText(order != null && order.leader() ? UiTheme.upper(text("GpuBoard.hud.forces.leader"))
                      : "");
                tag.setActor(tag(state));
                pilot.setText(unit.pilot());
                skills.setText(unit.gunnery() + "/" + unit.piloting());
                Line line = detail(unit);
                detail.setText(line.text());
                detail.setColor(line.tone() == Tone.OK ? MOVED : line.tone() == Tone.NORMAL ? UiTheme.TEXT
                      : line.tone().color);
                armor.set(unit.armor(), unit.marks().armorArgb());
                structure.set(unit.structure(), structureArgb);
                structure.setVisible(hasStructure);
                structureCaption.setVisible(hasStructure);
                boolean hot = !unit.heatEffects().isEmpty();
                flame.setVisible(unit.heat() > 0);
                flame.setColor(hot ? HOT : UiTheme.AMBER);
                heat.setText(unit.heat() > 0 ? String.valueOf(unit.heat()) : "");
                heat.setColor(hot ? HOT : UiTheme.AMBER);
                heatShare = 0;
            }
            highlight = unit.id() != shown.card() ? 0 : unit.id() == shown.focus() ? 1 : 2;
            pickable = !GpuDeployment.unpickable(shown.status(), unit);
            getColor().a = unit.destroyed() ? .38f : state == State.DONE ? .62f
                  : state == State.WAITING || !pickable ? .5f : 1;
            String tips = shown.status().phase().isMovement() ? "GpuBoard.hud.move.waitTip."
                  : "GpuBoard.hud.deploy.waitTip.";
            showWaitTip(state == State.WAITING ? text(tips + unit.deployWait().name()) : null);
        }

        /** The row's tooltip tells why the unit waits; {@code tip} null removes it. */
        private void showWaitTip(String tip) {
            if (tip == null) {
                if (waitTip != null) {
                    waitTip.hide();
                    removeListener(waitTip);
                    waitTip = null;
                }
                return;
            }
            if (waitTip == null) {
                waitTip = ui.tip(this);
            }
            waitTip.getActor().setText(tip);
        }

        private Actor tag(State state) {
            return switch (state) {
                case DESTROYED -> ui.label(UiTheme.upper(text("GpuBoard.hud.state.destroyed")), "hud-mini", 10,
                      UiTheme.CORAL);
                case NOW -> {
                    Container<Label> chip = new Container<>(ui.label(UiTheme.upper(text("GpuBoard.hud.forces.now")),
                          "hud-mini", 10, INK));
                    chip.setBackground(ui.skin.newDrawable("white", UiTheme.MINT));
                    chip.pad(0, 5, 0, 5);
                    yield chip;
                }
                case DONE -> ui.icon("check", 14, UiTheme.MUTED);
                case PENDING -> disc(UiTheme.AMBER);
                case WAITING, NONE -> null;
            };
        }

        private Actor dot(State state) {
            return switch (state) {
                case DESTROYED -> ui.icon("close", 11, UiTheme.CORAL);
                case NOW -> disc(UiTheme.MINT);
                case DONE -> ui.icon("check", 11, UiTheme.MUTED);
                case PENDING -> disc(UiTheme.AMBER);
                case WAITING, NONE -> null;
            };
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = parentAlpha;
            if (highlight == 1) {
                ui.fill(batch, SELECTED, alpha, getX(), getY(), getWidth(), getHeight());
                ui.fill(batch, UiTheme.MINT, alpha, getX(), getY(), 3, getHeight());
            } else if (highlight == 2) {
                ui.fill(batch, INSPECTED, alpha, getX(), getY(), getWidth(), getHeight());
                ui.fill(batch, UiTheme.MINT, alpha * .6f, getX(), getY(), 2, getHeight());
            }
            if (pointer.isOver() && pickable) {
                ui.fill(batch, HOVER, alpha, getX(), getY(), getWidth(), getHeight());
            }
            ui.fill(batch, SEPARATOR_LINE, alpha, getX(), getY(), getWidth(), 1);
            if (heatShare > 0) {
                float top = getY() + getHeight() - 6;
                float bottom = getY() + 6;
                float x = getX() + getWidth() - 5;
                ui.fill(batch, HEAT_TRACK, alpha, x, bottom, 3, top - bottom);
                ui.fill(batch, heatColor, alpha * getColor().a, x, bottom, 3, (top - bottom) * heatShare);
            }
            batch.setPackedColor(previous);
            super.draw(batch, parentAlpha);
        }
    }
}
