/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTheme.EdgeBox;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay.ReportCommand;
import megamek.common.enums.GamePhase;

/**
 * Initiative card, centred in the initiative phase (C.1 G1, plan B5-B7): each side's reported roll with the winner
 * and the side that moves first, then the round's turn order in pages of eight.
 */
final class GpuInitiativeCard implements GpuHud.Component {
    private static final int PAGE = 8;
    private static final float SIDE_GAP = 10;
    private static final Color DIE = Color.valueOf("ECEAE4");
    private static final Color DARK = Color.valueOf("111111");
    private static final Color TAG_EDGE = Color.valueOf("5B6563");
    private static final float ACTION = 40;
    private static final String REROLL = ReportCommand.REPORT_REROLL_INITIATIVE.getCmd();

    /** What the card was built from; it is rebuilt when this changes. */
    private record Shown(int round, List<GpuBattleStatus.InitiativeSide> sides, List<GpuBattleStatus.Slot> turns,
          boolean hidden) { }

    private final UiKit ui;
    private final Texture white;
    private final Table root;
    private final Table body = new Table();
    private final Label caption;
    private final Table sides = new Table();
    private final Cell<Table> sidesCell;
    private final Table order = new Table();
    private final Label hidden;
    private final Cell<Actor> orderCell;
    private final UiButton previous;
    private final UiButton next;
    private final Label range;
    private final Label activations;
    private final Table slots = new Table();
    private final List<Table> boxes = new ArrayList<>();
    private final Table actions = new Table();
    private final Cell<Table> actionsCell;
    private final UiButton reroll;
    private final UiButton proceed;
    private List<BoardScene.Command> commands = List.of();
    private String doneId;
    private GpuBattleStatus.Snapshot status;
    private Shown shown;
    private int page;
    private int perRow;

    GpuInitiativeCard(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        white = ui.skin.get("white", Texture.class);
        root = ui.panel();
        root.setName("initiative-card");
        // #initcard padding 14 16, inside the 2-unit rails and the transparent side borders.
        root.pad(16, 18, 16, 18);
        // Where the HUD has less height for the card than it needs (small windows, many sides), the body scrolls
        // inside the card instead of running over the dock, as the other HUD lists do.
        ScrollPane scroll = ui.scrollList(body);
        scroll.setName("initiative-body");
        root.add(scroll).grow().row();
        // Before the first deployment the card is the whole screen's question, so it carries its own answer: Continue
        // (and the Tactical Genius reroll) at its foot instead of a second panel below it.
        reroll = ui.button("hud", null, UiTheme.upper(Messages.getString("GpuBoard.hud.dock.rerollInitiative")), null);
        reroll.setName("initiative-reroll");
        reroll.pad(0, 14, 0, 14);
        onChange(reroll, () -> run(REROLL));
        proceed = ui.button("hud-main", null, UiTheme.upper(Messages.getString("GpuBoard.hud.dock.continue")), null);
        proceed.setName("initiative-continue");
        onChange(proceed, () -> run(doneId));
        actions.add(reroll).height(ACTION).padRight(6);
        actions.add(proceed).growX().height(ACTION);
        actionsCell = root.add(actions).growX().padTop(14);
        body.top().left();
        body.defaults().left().growX();
        caption = ui.caption("");
        body.add(caption).height(12.5f).row();
        sidesCell = body.add(sides).padTop(10);
        body.row();

        previous = ui.button("hud-mini", null, "\u2039", null);
        next = ui.button("hud-mini", null, "\u203A", null);
        range = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        activations = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        previous.setName("turn-order-previous");
        next.setName("turn-order-next");
        range.setName("turn-order-range");
        onChange(previous, () -> turnPage(-1));
        onChange(next, () -> turnPage(1));
        Table heading = new Table();
        heading.add(ui.caption(Messages.getString("GpuBoard.hud.phase.turnOrder")));
        heading.add().expandX();
        // .b.mini is 24 wide around a single angle quote; the label alone ends at its ink.
        heading.add(previous).minWidth(24).padLeft(8);
        heading.add(range).padLeft(8);
        heading.add(next).minWidth(24).padLeft(8);
        heading.add(activations).padLeft(8);
        order.add(heading).growX().padTop(12).padBottom(6).row();
        order.add(slots).growX();
        hidden = ui.label(Messages.getString("GpuBoard.hud.phase.turnOrderHidden"), "hud-small", 11.5f,
              UiTheme.MUTED);
        orderCell = body.add((Actor) order);
    }

    private void turnPage(int step) {
        page += step;
        showPage();
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuBattleStatus.Snapshot next = inputs.frame().status();
        GamePhase phase = next.phase();
        root.setVisible(phase.isInitiative() || phase.isInitiativeReport());
        if (!root.isVisible()) {
            return;
        }
        if (next != status) {
            status = next;
            Shown now = new Shown(next.round(), next.initiative(), next.turns(), next.turnOrderHidden());
            if (!now.equals(shown)) {
                page = shown == null || shown.round() != now.round() ? 0 : page;
                shown = now;
                build();
            }
        }
        flow();
        boolean staging = staging(next);
        BoardScene scene = inputs.frame().scene();
        commands = scene == null ? List.of() : scene.commands();
        doneId = inputs.frame().panels().phase().doneId();
        actionsCell.setActor(staging ? actions : null).padTop(staging ? 14 : 0);
        if (staging) {
            BoardScene.Command done = command(doneId);
            BoardScene.Command again = command(REROLL);
            proceed.setDisabled(done == null || !done.enabled());
            reroll.setVisible(again != null && again.enabled());
            actions.getCell(reroll).width(reroll.isVisible() ? reroll.getPrefWidth() : 0)
                  .padRight(reroll.isVisible() ? 6 : 0);
        }
    }

    /** Before the first deployment: the deployment order is all there is, and the card answers it itself. */
    static boolean staging(GpuBattleStatus.Snapshot status) {
        return status.round() == 0 && (status.phase().isInitiative() || status.phase().isInitiativeReport());
    }

    private BoardScene.Command command(String id) {
        return id == null || id.isEmpty() ? null
              : commands.stream().filter(command -> command.id().equals(id)).findFirst().orElse(null);
    }

    private void run(String id) {
        BoardScene.Command command = command(id);
        if (command != null && command.enabled()) {
            command.action().run();
        }
    }

    private void build() {
        // The start-of-game deployment's initiative belongs to no round.
        caption.setText(UiTheme.upper(shown.round() > 0
              ? Messages.getString("GpuBoard.hud.initiative.caption", shown.round())
              : Messages.getString("GpuBoard.hud.phase.deploymentOrder")));
        boxes.clear();
        GpuBattleStatus.InitiativeSide winner = GpuBattleStatus.winner(shown.sides());
        // Under double blind the turn order stays hidden, including who moves first (plan D4).
        GpuBattleStatus.InitiativeSide first = GpuBattleStatus.movesFirst(status);
        shown.sides().forEach(side -> boxes.add(side(side, side == winner, side == first)));
        sidesCell.padTop(boxes.isEmpty() ? 0 : 10);
        perRow = 0;
        orderCell.setActor(shown.hidden() ? hidden : order).padTop(shown.hidden() ? 12 : 0);
        showPage();
    }

    /**
     * One side (.iside): its name, its kept dice with the total, or one tile holding the total where the client has no
     * dice (plan B5), the result tag, and the roll line when the dice alone do not explain the total.
     */
    private Table side(GpuBattleStatus.InitiativeSide side, boolean winner, boolean first) {
        Color tone = GpuPhaseHeader.sideColor(side.side());
        Table box = new Table();
        box.setName("initiative-side");
        box.setBackground(new EdgeBox(white, UiTheme.alpha(tone, .06f), tone, 2, 0, 0, 0));
        box.pad(12, 12, 10, 12);
        box.defaults().left();
        Label name = ui.label(UiTheme.upper(side.side() == GpuBattleStatus.Side.OWN
              ? Messages.getString("GpuBoard.hud.common.yourForce") : side.name()), "hud-main", 14,
              UiTheme.TEXT);
        name.setEllipsis(true);
        box.add(name).height(16).growX().minWidth(0).row();
        Table dice = new Table();
        if (side.dice().isEmpty()) {
            // No kept pair, as on every client (E1): the one tile holds the total, the roll line below explains it.
            dice.add(die(side.total())).minWidth(34).height(34);
        } else {
            side.dice().forEach(value -> dice.add(die(value)).size(34).padRight(6));
            // Labels end at their ink, the browser's boxes at the advance: the gaps widen to the prototype's.
            dice.add(ui.label("=", "hud-small", 22, UiTheme.MUTED)).padLeft(2).padRight(10);
            dice.add(ui.label(String.valueOf(side.total()), "hud-phase", 32,
                  winner ? Color.WHITE : UiTheme.MUTED));
        }
        dice.add().expandX();
        if (winner || first) {
            dice.add(tag(winner)).padLeft(8);
        }
        box.add(dice).height(34).growX().padTop(8).row();
        if (side.dice().isEmpty() || side.bonus() != 0 || !side.tieBreaks().isEmpty()) {
            box.add(ui.label(roll(side), "hud-small", 11.5f, UiTheme.MUTED)).padTop(6);
        }
        return box;
    }

    /** A die tile (.die): the number in a 34-unit light square. */
    private Container<Label> die(int value) {
        Label digit = ui.label(String.valueOf(value), "hud-phase", 20, DARK);
        digit.setAlignment(Align.center);
        Container<Label> die = new Container<>(digit);
        die.setBackground(ui.skin.newDrawable("letter-primary", UiTheme.tint(DIE, UiTheme.MAIN, 1)));
        return die;
    }

    /** The result tag (.res): "Wins" filled, else "Deploys first" or "Moves first" outlined. */
    private Label tag(boolean winner) {
        Label tag = ui.label(UiTheme.upper(Messages.getString(winner ? "GpuBoard.hud.initiative.wins"
              : shown.round() > 0 ? "GpuBoard.hud.initiative.movesFirst" : "GpuBoard.hud.initiative.deploysFirst")),
              "hud-main", 11, winner ? DARK : UiTheme.MUTED);
        Label.LabelStyle style = tag.getStyle();
        // padding 4 8 inside a 1-unit border; the label's line box is a unit taller than the browser's
        style.background = UiTheme.pad(new EdgeBox(white, winner ? UiTheme.MAIN : null,
              winner ? Color.WHITE : TAG_EDGE, 1, 1, 1, 1), 4, 9);
        tag.setStyle(style);
        return tag;
    }

    /** The roll line of plan B5: the roll, the signed bonus and any tie-break totals. */
    private static String roll(GpuBattleStatus.InitiativeSide side) {
        String bonus = (side.bonus() >= 0 ? "+" : "") + side.bonus();
        return side.tieBreaks().isEmpty() ? Messages.getString("GpuBoard.hud.initiative.roll", side.roll(), bonus)
              : Messages.getString("GpuBoard.hud.initiative.rollTieBreak", side.roll(), bonus,
                    side.tieBreaks().stream().map(String::valueOf).collect(Collectors.joining(", ")));
    }

    /**
     * Places the sides two per row, or one per row when two do not fit the width the HUD gave the card in the last
     * layout.
     */
    private void flow() {
        float widest = boxes.stream().map(Table::getPrefWidth).max(Float::compare).orElse(0f);
        int wanted = body.getWidth() <= 0 || 2 * widest + SIDE_GAP <= body.getWidth() ? 2 : 1;
        if (wanted == perRow) {
            return;
        }
        perRow = wanted;
        sides.clearChildren();
        for (int i = 0; i < boxes.size(); i++) {
            Cell<Table> cell = sides.add(boxes.get(i)).growX().uniformX().fillY();
            cell.padLeft(i % perRow == 0 ? 0 : SIDE_GAP).padTop(i < perRow ? 0 : SIDE_GAP);
            if (i % perRow == perRow - 1) {
                sides.row();
            }
        }
    }

    /** The current page of the turn order (.slots), and the pager when there is more than one page. */
    private void showPage() {
        List<GpuBattleStatus.Slot> turns = shown.turns();
        int pages = Math.max(1, (turns.size() + PAGE - 1) / PAGE);
        page = Math.clamp(page, 0, pages - 1);
        slots.clearChildren();
        for (int column = 0; column < PAGE; column++) {
            int index = page * PAGE + column;
            Actor slot = index < turns.size() ? slot(index, turns.get(index)) : new Actor();
            slots.add(slot).growX().uniformX().fillY().padLeft(column == 0 ? 0 : 4);
        }
        boolean paged = pages > 1;
        previous.setVisible(paged);
        next.setVisible(paged);
        range.setVisible(paged);
        previous.setDisabled(page == 0);
        next.setDisabled(page == pages - 1);
        range.setText(Messages.getString("GpuBoard.hud.initiative.pageRange", page * PAGE + 1,
              Math.min(turns.size(), page * PAGE + PAGE)));
        activations.setText(Messages.getString("GpuBoard.hud.initiative.activations", turns.size()));
    }

    /** One activation (.slots .slot): its number and "You" or its player, over its side's color. */
    private Table slot(int index, GpuBattleStatus.Slot turn) {
        Color tone = GpuPhaseHeader.turnColor(turn);
        Table slot = new Table();
        slot.setName("turn-order-slot");
        slot.setBackground(new EdgeBox(white, UiTheme.alpha(tone, .12f), tone, 0, 0, 2, 0));
        // padding 5 0 4 above the 2-unit bottom border
        slot.pad(5, 2, 6, 2);
        slot.add(ui.label(String.valueOf(index + 1), "hud-phase", 15, UiTheme.TEXT)).height(18).row();
        Label player = ui.label(UiTheme.upper(turn.side() == GpuBattleStatus.Side.OWN
              ? Messages.getString("GpuBoard.hud.initiative.you") : turn.playerName()), "hud-caption", 9.5f,
              UiTheme.ACCENT);
        player.setEllipsis(true);
        player.setAlignment(Align.center);
        slot.add(player).height(11).growX().minWidth(0);
        return slot;
    }
}
