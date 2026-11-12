/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiConfirmation;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay.DeployCommand;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay.FiringCommand;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay.PhysicalCommand;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay.ReportCommand;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * Command dock, bottom centre (C.1 G6): a head line, an option row, an action row and a foot line,
 * in one variant per phase and turn (plan C.2). It presents the snapshots and runs the phase's own commands, the
 * movement, fire and physical services, the playback history and the camera's follow flag; the client keeps every
 * rule.
 */
final class GpuCommandDock implements GpuHud.Component {
    /** The dock's variants (plan C.2); GENERIC is every phase and turn the prototype does not design. */
    private enum Variant { INITIATIVE, PLAN, WAITING, FIRE, PHYSICAL, NO_PHYSICAL, PLAYBACK, GENERIC, CHAT }

    /** The action awaiting an explicit choice beside its triggering button. */
    private enum Confirm {
        TWIST_LEFT, TWIST_RIGHT, RESOLVE, HOLD_ALL;

        boolean twist() {
            return this == TWIST_LEFT || this == TWIST_RIGHT;
        }
    }

    /**
     * What decides the dock's cells; they are rebuilt only when it changes. {@code buttons} are the ids of the option
     * row's physical options or phase commands; {@code turns} shows the plan's turn buttons; {@code standUp} puts
     * Stand up in Jump's place.
     */
    private record Shape(Variant variant, List<String> buttons, boolean more, boolean primary, boolean secondary,
          boolean stop, boolean legend, boolean steps, boolean backwards, boolean turns, boolean standUp) { }

    /**
     * The More popover's content (plan A.7 G14, A.8 H38, C.2), which G12 shows above the More button: a title and
     * subtitle, the dock's own items, and MegaMek's other phase commands after a separator (an unavailable one says so
     * in its detail). Movement instead groups all available actions before all unavailable actions and omits the
     * subtitle.
     */
    record More(String title, String subtitle, List<BoardScene.Command> items, List<BoardScene.Command> commands) {
        More {
            items = List.copyOf(items);
            commands = List.copyOf(commands);
        }
    }

    /** The gap between buttons and between rows (#dock .r1, .r2). */
    private static final float GAP = 6;
    /** A square button (.b.sq) and the action row's height (.b.main, .b.sec, .waiting). */
    private static final float SQUARE = 42;
    private static final float ACTION = 46;
    /** The option row's height: mode buttons with their sub-labels, and plain buttons (.b). */
    private static final float TALL = 46;
    private static final float PLAIN = 36;
    /** The torso read-out's width (#dock .r1 .torso). */
    private static final float TORSO = 76;
    /** The physical options the option row shows (the prototype's four limbs), and the generic phase commands. */
    private static final int OPTIONS = 4;
    private static final int COMMANDS = 5;
    /** Deployment's compact dock: its row's commands, and those only More offers. */
    private static final int DEPLOY_COMMANDS = 2;
    private static final Set<String> DEPLOY_MORE_ONLY = Set.of(DeployCommand.DEPLOY_NEXT.getCmd(),
          DeployCommand.DEPLOY_TURN.getCmd(), DeployCommand.DEPLOY_REMOVE.getCmd());
    private static final String SEPARATOR = " \u00B7 ";
    private static final Color WAITING = Color.valueOf("8A9593");
    private static final List<GpuMovePlan.Mode> MODES = List.of(GpuMovePlan.Mode.WALK, GpuMovePlan.Mode.RUN,
          GpuMovePlan.Mode.JUMP);
    private static final List<KeyCommandBind> MODE_KEYS = List.of(KeyCommandBind.MOVE_MODE_WALK,
          KeyCommandBind.MOVE_MODE_RUN, KeyCommandBind.MOVE_MODE_JUMP);
    /**
     * MegaMek commands that a control of the dock, the forces panel's "Next pending" or the weapons panel stands for;
     * More lists every other phase command.
     */
    private static final Set<String> MOVE_SHOWN = Stream.of(MoveCommand.MOVE_WALK, MoveCommand.MOVE_JUMP,
          MoveCommand.MOVE_BACK_UP, MoveCommand.MOVE_TURN, MoveCommand.MOVE_NEXT).map(MoveCommand::getCmd)
          .collect(Collectors.toUnmodifiableSet());
    private static final String GET_UP = MoveCommand.MOVE_GET_UP.getCmd();
    private static final Set<String> MOVE_SHOWN_DOWN = Stream.concat(MOVE_SHOWN.stream(), Stream.of(GET_UP))
          .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> FIRE_SHOWN = Stream.of(FiringCommand.FIRE_TWIST, FiringCommand.FIRE_FIRE,
          FiringCommand.FIRE_SKIP, FiringCommand.FIRE_NEXT, FiringCommand.FIRE_NEXT_TARG, FiringCommand.FIRE_MODE,
          FiringCommand.FIRE_CALLED, FiringCommand.FIRE_CANCEL).map(FiringCommand::getCmd)
          .collect(Collectors.toUnmodifiableSet());
    /**
     * The physical attacks the options stand for (E4) and Next. Dodge, laying explosives, clearing woods, the
     * searchlight and the twist take no target and no roll: they stay MegaMek commands in More.
     */
    private static final Set<String> PHYSICAL_SHOWN = Stream.of(PhysicalCommand.PHYSICAL_NEXT,
          PhysicalCommand.PHYSICAL_PUNCH, PhysicalCommand.PHYSICAL_KICK, PhysicalCommand.PHYSICAL_CLUB,
          PhysicalCommand.PHYSICAL_BRUSH_OFF, PhysicalCommand.PHYSICAL_THRASH, PhysicalCommand.PHYSICAL_PUSH,
          PhysicalCommand.PHYSICAL_TRIP, PhysicalCommand.PHYSICAL_GRAPPLE, PhysicalCommand.PHYSICAL_JUMP_JET,
          PhysicalCommand.PHYSICAL_PROTO, PhysicalCommand.PHYSICAL_VIBRO, PhysicalCommand.PHYSICAL_PHEROMONE,
          PhysicalCommand.PHYSICAL_TOXIN).map(PhysicalCommand::getCmd).collect(Collectors.toUnmodifiableSet());
    private static final String REROLL = ReportCommand.REPORT_REROLL_INITIATIVE.getCmd();

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final BoardCamera camera;
    private final GpuContextMenu menu;
    private final Table root;
    private final Table head = new Table();
    private final Label title;
    private final Label span;
    private final UiButton stop;
    private final Table row1 = new Table();
    private final Table row2 = new Table();
    private final Table foot = new Table();
    private final Cell<Table> headCell;
    private final Cell<Actor> row1Cell;
    private final Cell<Actor> row2Cell;
    private final UiButton chatButton;
    private final UiButton main;
    private final UiButton secondary;
    private final UiButton more;
    /** The More panel that slides out beside the dock, or above it where the window has no room beside it. */
    private final GpuMoreDrawer drawer;
    // Movement plan.
    private final List<UiButton> modes = new ArrayList<>();
    /** MegaMek's Get Up, in Jump's place while the planned unit is down: it cannot jump until it stands. */
    private final UiButton standUp;
    private final TextButton.TextButtonStyle plainStyle;
    private final TextButton.TextButtonStyle autoStyle;
    private final UiButton turnLeft;
    private final UiButton turnRight;
    private final UiButton undo;
    private final UiButton waypoint;
    private final UiKit.Checkbox backwards;
    private final UiButton clearRoute;
    private final UiButton holdAll;
    // Weapon declaration.
    private final UiButton twistLeft;
    private final UiButton twistRight;
    private final Table torso = new Table();
    private final Label torsoCaption;
    private final Label torsoValue;
    private final UiButton clear;
    private final UiButton resolve;
    private final UiConfirmation confirmation;
    // Another player's turn.
    private final Container<Label> waiting;
    // Playback and review.
    private final UiButton previous;
    private final UiButton play;
    private final UiButton next;
    private final Label counter;
    private final UiButton replay;
    private final UiButton follow;
    // Foot line.
    private final Table footLine = new Table();
    private final Label footBold;
    private final Label footText;
    private final Table legend = new Table();
    private final Label legendText;
    private final Table footRight = new Table();
    private final UiButton steps;
    /** The mode buttons' tooltips, each on its button only while that mode is chosen. */
    private final List<TextTooltip> modeTips = new ArrayList<>();
    private final TextTooltip stepsTip;
    /** The option row's physical options (PHYSICAL) or phase commands (GENERIC), rebuilt with the shape. */
    private final List<UiButton> choices = new ArrayList<>();
    /** The chosen physical options, for the actor and target they were chosen against. */
    private final Set<String> chosen = new LinkedHashSet<>();
    private int chosenActor = Entity.NONE;
    private int chosenTarget = Entity.NONE;
    private Confirm open;
    private int confirmActor = Entity.NONE;
    private int confirmTurn;
    private int confirmRound;
    private GamePhase confirmPhase;
    private GpuHud.Inputs inputs;
    private Variant variant = Variant.GENERIC;
    private Shape shown;

    /**
     * {@code camera} holds the playback's "Follow camera" flag (J6); {@code menu} shows the More popover above the
     * More button (A.7 G14, G12).
     */
    GpuCommandDock(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera, GpuContextMenu menu,
          UiButton chatButton) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.camera = camera;
        this.menu = menu;
        this.chatButton = chatButton;
        root = ui.panel();
        root.setName("command-dock");
        // #dock padding 9 12 8, inside the 2-unit rails and the transparent side borders.
        root.pad(11, 14, 10, 14);

        // .dh: the upper-case title, and the span at the right. A Label's color tints its style's, so the colors
        // these two change per variant are set on white styles.
        title = ui.label("", "hud-title", 13, Color.WHITE);
        title.setEllipsis(true);
        title.setName("dock-title");
        span = ui.label("", "hud-small", 11.5f, Color.WHITE);
        span.setEllipsis(true);
        span.setName("dock-span");
        stop = button("hud-mini", null, text("GpuBoard.hud.dock.stop"), null, "dock-stop");
        onChange(stop, this::stop);
        head.pad(0, 2, 0, 2);

        main = button("hud-main", null, "", null, "dock-main");
        onChange(main, this::main);
        secondary = button("hud", null, "", null, "dock-secondary");
        secondary.pad(0, 14, 0, 14);
        onChange(secondary, this::secondary);
        more = button("hud", "more", null, null, "dock-more");
        more.pad(0, 8, 0, 8);
        onChange(more, this::openMore);
        drawer = new GpuMoreDrawer(ui);

        // Movement: .b.mode buttons at 14 units with their MP, the tall squares and the undo square.
        plainStyle = ui.skin.get("hud", TextButton.TextButtonStyle.class);
        autoStyle = new TextButton.TextButtonStyle(plainStyle);
        // .b.mode.auto: the route's effective mode, outlined in white without a fill.
        autoStyle.up = ui.skin.getDrawable("button-auto");
        autoStyle.fontColor = Color.WHITE;
        for (GpuMovePlan.Mode mode : MODES) {
            UiButton button = button("hud", null, modeName(mode), "",
                  "dock-mode-" + mode.name().toLowerCase(Locale.ROOT));
            UiKit.size(button.getLabel(), "hud-button", 14);
            onChange(button, () -> source.moves().setMode(mode));
            modes.add(button);
            modeTips.add(ui.tip(button));
        }
        standUp = button("hud", null, text("GpuBoard.hud.dock.standUp"), "", "dock-stand-up");
        // Outlined in white like the route's mode, so the one thing a fallen unit needs stands out.
        standUp.setStyle(autoStyle);
        UiKit.size(standUp.getLabel(), "hud-button", 14);
        onChange(standUp, () -> run(GET_UP));
        ui.tip(standUp).getActor().setText(text("GpuBoard.hud.dock.standUpTip"));
        turnLeft = turn("twist-left", text("Left"), -1);
        turnRight = turn("twist-right", text("Right"), 1);
        undo = button("hud", "undo", null, null, "dock-undo");
        undo.pad(0, 8, 0, 8);
        onChange(undo, () -> source.moves().undo());
        waypoint = button("hud-mini", null, text("GpuBoard.hud.dock.waypoint"), null, "dock-waypoint");
        onChange(waypoint, () -> source.moves().pinDestination());
        ui.tip(waypoint).getActor().setText(text("GpuBoard.hud.dock.waypointTip"));
        backwards = ui.checkbox(text("GpuBoard.hud.dock.walkBackwards"), false);
        backwards.setName("dock-backwards");
        onChange(backwards, () -> source.moves().setMode(GpuMovePlan.Mode.BACK));
        clearRoute = button("hud-mini", null, "X", null, "dock-clear-route");
        onChange(clearRoute, () -> source.moves().clearRoute());
        ui.tip(clearRoute).getActor().setText(text("GpuBoard.hud.dock.clearRoute"));
        holdAll = button("hud-mini", null, text("GpuBoard.hud.dock.holdAllButton"), null, "dock-hold-all");
        onChange(holdAll, () -> requestConfirmation(Confirm.HOLD_ALL));
        ui.tip(holdAll).getActor().setText(text("GpuBoard.hud.dock.holdAll"));

        // Weapons: .b.wide with the icon before (left) or after (right) the caption, 7 units apart, the torso
        // read-out between them.
        twistLeft = button("hud", "twist-left", text("GpuBoard.hud.dock.twistLeft"), null, "dock-twist-left");
        twistLeft.getCell(twistLeft.getLabel()).padLeft(7);
        twistRight = button("hud", null, text("GpuBoard.hud.dock.twistRight"), null, "dock-twist-right");
        trailingIcon(twistRight, "twist-right", 17, 7);
        onChange(twistLeft, () -> twist(-1));
        onChange(twistRight, () -> twist(1));
        torsoCaption = ui.label("", "hud-caption", 9.5f, UiTheme.MUTED);
        torsoCaption.setName("dock-torso");
        torsoValue = ui.label("", "hud-title", 13, UiTheme.MINT);
        torsoValue.setName("dock-torso-value");
        torso.add(torsoCaption).row();
        torso.add(torsoValue);
        clear = button("hud", null, text("GpuBoard.hud.dock.clear"), null, "dock-clear");
        clear.pad(0, 12, 0, 12);
        onChange(clear, this::clearOrders);
        resolve = button("hud", null, text("GpuBoard.hud.dock.resolvePhase"), null, "dock-resolve");
        resolve.pad(0, 14, 0, 14);
        onChange(resolve, this::resolve);
        ui.tip(resolve).getActor().setText(text("GpuBoard.hud.dock.resolvePhaseTip"));

        // A floating card on the popover layer, separate from the dock's layout.
        confirmation = new UiConfirmation(ui, "dock-confirm", () -> open = null);

        Label waitingLabel = ui.label(UiTheme.upper(text("GpuBoard.hud.dock.waitingForOpponent")), "hud-main",
              17, WAITING);
        waitingLabel.setEllipsis(true);
        waitingLabel.setAlignment(Align.center);
        waiting = new Container<>(waitingLabel).fillX().minWidth(0);
        waiting.setName("dock-waiting");
        waiting.setBackground(ui.skin.getDrawable("waiting"));

        // Playback: the transport over the round's playback history (G11), which also takes the playback keys.
        previous = button("hud", null, "\u2039", null, "dock-previous");
        next = button("hud", null, "\u203A", null, "dock-next");
        onChange(previous, () -> state.history.step(-1));
        onChange(next, () -> state.history.step(1));
        play = button("hud", "play", "", null, "dock-play");
        play.getCell(play.getLabel()).padLeft(7);
        onChange(play, state.history::togglePaused);
        counter = ui.label("", "hud-medium", 12, UiTheme.MUTED);
        counter.setEllipsis(true);
        counter.setName("dock-counter");
        replay = button("hud", "rewind", text("GpuBoard.hud.common.replay"), null, "dock-replay");
        replay.pad(0, 12, 0, 12);
        // The dock's Replay replays the current playback, or in the review the whole round (J8).
        onChange(replay, () -> state.history.replay(null));
        follow = button("hud-mini", null, text("GpuBoard.hud.dock.followCamera"), null, "dock-follow");
        onChange(follow, () -> camera.animateCombatPlayback = !camera.animateCombatPlayback);

        // .foot: a muted line with a white condensed part (the route's cost) or the route legend, minis at the right.
        footBold = ui.label("", "hud-phase", 11.5f, Color.WHITE);
        footBold.setEllipsis(true);
        footText = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        footText.setEllipsis(true);
        footBold.setName("dock-foot-bold");
        footText.setName("dock-foot");
        footLine.add(footBold).minWidth(0);
        footLine.add(footText).growX().minWidth(0);
        legendText = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        legendText.setEllipsis(true);
        legendText.setName("dock-legend");
        legend(text("GpuBoard.hud.dock.walk"), UiTheme.MINT);
        legend(text("GpuBoard.hud.dock.run"), UiTheme.AMBER);
        legend.add(legendText).growX().minWidth(0);
        steps = button("hud-mini", null, text("GpuBoard.hud.dock.steps"), null, "dock-steps");
        trailingIcon(steps, "chevron-down", 13, 5);
        // The steps are read-only lines, which the tooltip lists (plan C.2 "Steps").
        stepsTip = ui.tip(steps);
        foot.pad(6, 2, 0, 2);
        footRight.defaults().minWidth(0);

        headCell = root.add(head).growX().padBottom(7);
        root.row();
        row1Cell = root.add((Actor) null).growX();
        root.row();
        row2Cell = root.add((Actor) null).growX();
        root.row();
        root.add(foot).growX().minHeight(26);
    }

    /** A named button whose caption and sub-label end in an ellipsis instead of widening the dock. */
    private UiButton button(String style, String icon, String text, String sub, String name) {
        UiButton button = ui.button(style, icon, text, sub);
        button.setName(name);
        if (text != null) {
            button.getLabel().setEllipsis(true);
            button.getCell(button.getLabel()).minWidth(0);
        }
        for (Label detail : button.details) {
            detail.setEllipsis(true);
            button.getCell(detail).minWidth(0);
        }
        return button;
    }

    /** A tall square (.b.sq.tall): the turn icon over a 9-unit "Left" or "Right". */
    private UiButton turn(String icon, String text, int direction) {
        UiButton button = button("hud", icon, null, text, direction < 0 ? "dock-turn-left"
              : "dock-turn-right");
        button.pad(0, 8, 0, 8);
        UiKit.size(button.details.getFirst(), "hud-sub", 9);
        onChange(button, () -> source.moves().turn(direction));
        return button;
    }

    /** An icon after the caption, in the caption's color. */
    private void trailingIcon(UiButton button, String icon, float size, float gap) {
        UiKit.Icon image = ui.icon(icon, size, Color.WHITE);
        button.icons.add(image);
        button.add(image).padLeft(gap);
    }

    /** One legend sample (.leg): a 14-unit dashed stroke and its word, in the band's color. */
    private void legend(String text, Color color) {
        Table stroke = new Table();
        stroke.add(new Image(ui.skin.newDrawable("white", color))).size(5, 2);
        stroke.add(new Image(ui.skin.newDrawable("white", color))).size(5, 2).padLeft(4);
        legend.add(stroke).padRight(5);
        legend.add(ui.label(text, "hud-small", 11.5f, color)).padRight(10);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        variant = variant();
        forget();
        GpuHudData panels = inputs.frame().panels();
        GamePhase phase = inputs.frame().status().phase();
        List<String> buttons = switch (variant) {
            case PHYSICAL -> rowOptions().stream().map(GpuPhysicalOptions.Option::id).toList();
            case GENERIC -> rowCommands().stream().map(BoardScene.Command::id).toList();
            default -> List.of();
        };
        boolean secondaryShown = switch (variant) {
            case INITIATIVE -> enabled(command(REROLL));
            case PLAN, PHYSICAL -> true;
            // Without MegaMek's Skip, its Done both moves and holds: the main button is that command already.
            case GENERIC -> skip() != null && !skipIsDone();
            default -> false;
        };
        boolean stopShown = phase.isMovement() && panels.move().holdingRemaining() > 0
              || (phase.isFiring() || phase.isTargeting()) && panels.fire().autoDeclareRemaining() > 0;
        Shape shape = new Shape(variant, buttons, variant != Variant.CHAT && more() != null,
              variant != Variant.CHAT && variant != Variant.WAITING
              && (variant != Variant.GENERIC || done() != null), secondaryShown, stopShown,
              variant == Variant.PLAN && panels.move().route().isEmpty(), stepsShown(),
              variant == Variant.PLAN && enabled(command(MoveCommand.MOVE_BACK_UP.getCmd())),
              variant == Variant.PLAN && panels.move().facingMatters(), standUpShown());
        if (!shape.equals(shown)) {
            shown = shape;
            layout(shape);
        }
        switch (variant) {
            case INITIATIVE -> showInitiative();
            case PLAN -> showPlan();
            case WAITING -> showWaiting();
            case FIRE -> showFire();
            case PHYSICAL -> showPhysical();
            case NO_PHYSICAL -> showNoPhysical();
            case PLAYBACK -> showPlayback();
            case GENERIC -> showGeneric();
            case CHAT -> {
                footBold.setText("");
                footText.setText("");
            }
        }
        // The hold and auto-declare modes (A.7 G15, A.8 H6) replace the span with their count, beside Stop.
        if (phase.isMovement() && panels.move().holdingRemaining() > 0) {
            span(text("GpuBoard.hud.dock.holding", panels.move().holdingRemaining()), UiTheme.AMBER);
        } else if (stopShown) {
            span(text("GpuBoard.hud.dock.autoDeclaring", panels.fire().autoDeclareRemaining()), UiTheme.AMBER);
        }
        if (shape.steps()) {
            stepsTip.getActor().setText(String.join("\n", panels.phase().turnDetails()));
        }
        // Wrapped labels (an unavailable option's reason) measure their height at their
        // width: laid out at its current width, the dock gives the HUD its settled height in this very frame.
        if (root.getWidth() > 0) {
            root.validate();
        }
        if (variant == Variant.CHAT) { drawer.close(); }
        drawer.update(drawer.isOpen() ? more() : null);
    }

    /** The variant of the phase and turn (plan C.2). */
    private Variant variant() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuHudData panels = inputs.frame().panels();
        GamePhase phase = playbackPhase();
        if (state.overview || state.turnLocked(status) && !phase.isReport()) { return Variant.CHAT; }
        boolean turns = phase.isMovement() || phase.isFiring() || phase.isTargeting() || phase.isPhysical();
        if (phase.isInitiativeReport()) {
            return Variant.INITIATIVE;
        } else if (phase.isFiringReport() || phase.isPhysicalReport() || phase.isEndReport()) {
            return Variant.PLAYBACK;
        } else if (turns && !status.myTurn()) {
            return current() == null ? Variant.GENERIC : Variant.WAITING;
        } else if (phase.isMovement() && panels.move().active() && panels.move().planner()) {
            return Variant.PLAN;
        } else if ((phase.isFiring() || phase.isTargeting()) && panels.fire().active() && panels.fire().editable()) {
            return Variant.FIRE;
        } else if (phase.isPhysical() && panels.physical().active()) {
            return panels.physical().adjacentTargets().isEmpty() ? Variant.NO_PHYSICAL : Variant.PHYSICAL;
        }
        return Variant.GENERIC;
    }

    /**
     * The phase the dock shows: a running report plays under its transport, while the rest of the HUD holds the phase before
     * it until the playback ends (GpuHudState#presented).
     */
    private GamePhase playbackPhase() {
        GamePhase latest = state.latestPhase();
        return state.history.running() && (latest.isFiringReport() || latest.isPhysicalReport() || latest.isEndReport())
              ? latest : inputs.frame().status().phase();
    }

    /** The turn slot that acts now, or null. */
    private GpuBattleStatus.Slot current() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        int index = status.turnIndex();
        return index >= 0 && index < status.turns().size() ? status.turns().get(index) : null;
    }

    /**
     * Drops a confirmation and chosen options that no longer belong to the shown actor, target and orders: a twist
     * without queued attacks, the resolve disclosure without another unit to declare, or Hold all when the
     * movement turn changes or holding becomes unavailable.
     */
    private void forget() {
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        // The Tuning panel stays open beside play; the other dialogs cover the board.
        boolean dialog = state.dialog != GpuHudState.Dialog.NONE && state.dialog != GpuHudState.Dialog.TUNING;
        if (open != null && (inputs.dialog() != null || dialog || state.overview
              || inputs.frame().status().turnIndex() != confirmTurn
              || inputs.frame().status().round() != confirmRound
              || inputs.frame().status().phase() != confirmPhase)) {
            cancel();
        }
        if (open == Confirm.HOLD_ALL) {
            if (variant != Variant.PLAN || move.entityId() != confirmActor || !enabled(skip())
                  || move.holdingRemaining() > 0) {
                cancel();
            }
        } else if (variant != Variant.FIRE || fire.actorId() != confirmActor
              || open != null && (open.twist() ? fire.attacks().isEmpty() : othersToDeclare(fire) == 0)) {
            cancel();
        }
        GpuPhysicalOptions.Snapshot physical = inputs.frame().panels().physical();
        if (variant != Variant.PHYSICAL || physical.actorId() != chosenActor || physical.targetId() != chosenTarget) {
            chosen.clear();
        }
        chosenActor = physical.actorId();
        chosenTarget = physical.targetId();
        chosen.removeIf(id -> physical.options().stream().noneMatch(option -> option.id().equals(id)
              && option.available()));
    }

    /** Rebuilds the cells from the shape: the head's parts, the variant's rows, their dynamic buttons and the foot. */
    private void layout(Shape shape) {
        headCell.setActor(shape.variant() == Variant.CHAT ? null : head)
              .padBottom(shape.variant() == Variant.CHAT ? 0 : 7);
        head.clearChildren();
        head.add(title).left().minWidth(0);
        head.add().expandX();
        head.add(span).right().padLeft(10).minWidth(0);
        if (shape.backwards()) {
            head.add(backwards).padLeft(10);
        }
        if (shape.stop()) {
            head.add(stop).padLeft(8);
        }
        row1.clearChildren();
        row2.clearChildren();
        choices.clear();
        row1.defaults().padLeft(GAP);
        row2.defaults().padLeft(GAP).height(ACTION);
        switch (shape.variant()) {
            case PLAN -> {
                // A unit down (prone or hull down) stands first: Stand up leads, and takes Jump's place.
                if (shape.standUp()) {
                    row1.add(standUp).growX().uniformX().minWidth(0).height(TALL);
                }
                for (int i = 0; i < MODES.size(); i++) {
                    if (!shape.standUp() || MODES.get(i) != GpuMovePlan.Mode.JUMP) {
                        row1.add(modes.get(i)).growX().uniformX().minWidth(0).height(TALL);
                    }
                }
                if (shape.turns()) {
                    row1.add(turnLeft).width(SQUARE).height(TALL);
                    row1.add(turnRight).width(SQUARE).height(TALL);
                }
                row2.add(undo).width(SQUARE);
            }
            case FIRE -> {
                row1.add(twistLeft).growX().uniformX().minWidth(0).height(PLAIN);
                row1.add(torso).minWidth(TORSO).height(PLAIN);
                row1.add(twistRight).growX().uniformX().minWidth(0).height(PLAIN);
                row2.add(clear).minWidth(0);
            }
            case PHYSICAL -> {
                for (GpuPhysicalOptions.Option option : rowOptions()) {
                    UiButton button = button("hud", null, option.label(), "",
                          "dock-option-" + option.id());
                    // The sub-label of an unavailable option wraps under its label (#dock .r1.opts .b.opt small).
                    Label sub = button.details.getFirst();
                    sub.setEllipsis(false);
                    sub.setWrap(true);
                    sub.setAlignment(Align.center);
                    button.getCell(sub).growX();
                    onChange(button, () -> choose(option.id()));
                    choices.add(button);
                    row1.add(button).growX().uniformX().minWidth(0).minHeight(ACTION).fillY();
                }
            }
            case PLAYBACK -> {
                row1.add(previous).width(SQUARE).height(PLAIN);
                // The play button takes the rest (.b.wide: flex 1); the speeds live in the phase header (the user's
                // decision of 2026-10-03).
                row1.add(play).growX().height(PLAIN);
                row1.add(next).width(SQUARE).height(PLAIN);
                row1.add(counter).minWidth(0).height(PLAIN).padLeft(GAP + 6).padRight(6);
                row2.add(replay).minWidth(0);
            }
            case WAITING -> row2.add(waiting).growX().minWidth(0);
            case GENERIC -> {
                for (BoardScene.Command command : rowCommands()) {
                    UiButton button = button("hud", null, command.label(), null,
                          "dock-command-" + command.id());
                    onChange(button, () -> run(command.id()));
                    choices.add(button);
                    // MegaMek's labels differ in length: each button takes its caption's width and a share of the rest.
                    row1.add(button).growX().minWidth(0).height(PLAIN);
                }
            }
            default -> { }
        }
        // Without an option row (no adjacent enemy), More closes the action row.
        boolean moreInRow2 = shape.variant() == Variant.NO_PHYSICAL;
        if (shape.more() && !moreInRow2) {
            row1.add(more).width(SQUARE).fillY();
        }
        if (shape.primary()) {
            row2.add(main).growX().minWidth(0);
        }
        if (shape.secondary()) {
            row2.add(secondary).minWidth(0);
        }
        if (shape.variant() == Variant.FIRE) {
            row2.add(resolve).minWidth(0);
        }
        if (shape.more() && moreInRow2) {
            row2.add(more).width(SQUARE);
        }
        // The first button of a row starts at its edge.
        for (Table row : List.of(row1, row2)) {
            if (row.getCells().notEmpty()) {
                row.getCells().first().padLeft(0);
            }
        }
        row1Cell.setActor(row1.getCells().isEmpty() ? null : row1).padBottom(row1.getCells().isEmpty() ? 0 : GAP);
        row2Cell.setActor(row2.getCells().isEmpty() ? null : row2);

        foot.clearChildren();
        footRight.clearChildren();
        foot.add(shape.legend() ? legend : footLine).growX().minWidth(0);
        if (shape.variant() == Variant.PLAN) {
            footRight.add(holdAll).padLeft(8);
            footRight.add(waypoint).padLeft(8);
            footRight.add(clearRoute).padLeft(8);
        } else if (shape.variant() == Variant.PLAYBACK) {
            footRight.add(follow).padLeft(8);
        }
        if (shape.steps()) {
            footRight.add(steps).padLeft(8);
        }
        footRight.add(chatButton).minWidth(chatButton.getPrefWidth()).padLeft(8);
        foot.add(footRight).minWidth(chatButton.getPrefWidth() + 8);
    }

    // ------------------------------------------------------------------ variants

    /** INITIATIVE_REPORT (B8, B10): who won and who moves first; Continue and the Tactical Genius reroll. */
    private void showInitiative() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        // The start-of-game deployment's initiative belongs to no round.
        head(status.round() > 0 ? text("GpuBoard.hud.dock.initiativeHead", status.round())
              : text("GpuBoard.hud.phase.deploymentOrder"), false);
        span(initiativeResult(status), UiTheme.MINT);
        main(text("GpuBoard.hud.dock.continue"), enabled(done()));
        secondary(text("GpuBoard.hud.dock.rerollInitiative"), true);
        foot("", "");
    }

    /**
     * The initiative result, "{winner} won, {first} moves first" with "you" for the local side (B8), by the status's
     * one winner and moves-first rules. Empty while the turn order is hidden (double blind) or not known.
     */
    private static String initiativeResult(GpuBattleStatus.Snapshot status) {
        GpuBattleStatus.InitiativeSide winner = GpuBattleStatus.winner(status.initiative());
        GpuBattleStatus.InitiativeSide first = GpuBattleStatus.movesFirst(status);
        if (winner == null || first == null) {
            return "";
        }
        boolean youWon = winner.side() == GpuBattleStatus.Side.OWN;
        boolean youFirst = first.side() == GpuBattleStatus.Side.OWN;
        String prefix = "GpuBoard.hud.dock." + (status.round() > 0 ? "initiative" : "deployment");
        if (youWon && youFirst) {
            return text(prefix + "YouWonFirst");
        } else if (youWon) {
            return text(prefix + "YouWon", first.name());
        } else if (youFirst) {
            return text(prefix + "YouFirst", winner.name());
        }
        return text(prefix + "Result", winner.name(), first.name());
    }

    /** The local movement plan (A.7 G2, G9-G16): modes, facing, undo, confirm, hold and the route's foot line. */
    private void showPlan() {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        GpuBattleStatus.UnitStatus unit = state.presented(move.entityId());
        boolean route = !move.route().isEmpty();
        head(text("GpuBoard.hud.dock.movementPlan", name(unit)), false);
        span("", UiTheme.MUTED);
        backwards.ticked(move.mode() == GpuMovePlan.Mode.BACK);
        GpuMovePlan.Band band = route ? move.route().getLast().band() : null;
        for (int i = 0; i < MODES.size(); i++) {
            GpuMovePlan.Mode mode = MODES.get(i);
            UiButton button = modes.get(i);
            String mp = unit == null ? "" : switch (mode) {
                case WALK -> String.valueOf(unit.walk());
                case RUN -> unit.run();
                default -> String.valueOf(unit.jump());
            };
            button.details.getFirst().setText(text("GpuBoard.hud.dock.mp", mp));
            boolean explicit = move.explicit() && move.mode() == mode;
            button.pressed(explicit);
            button.setDisabled(mode == GpuMovePlan.Mode.JUMP && (unit == null || unit.jump() <= 0));
            // The route's last band is outlined (plan G2); Run covers boosted running and sprinting.
            boolean auto = !move.explicit() && band != null && switch (mode) {
                case WALK -> band == GpuMovePlan.Band.WALK || band == GpuMovePlan.Band.BACK;
                case RUN -> band == GpuMovePlan.Band.RUN || band == GpuMovePlan.Band.BOOST
                      || band == GpuMovePlan.Band.SPRINT;
                default -> band == GpuMovePlan.Band.JUMP;
            };
            TextButton.TextButtonStyle style = auto ? autoStyle : plainStyle;
            if (button.getStyle() != style) {
                button.setStyle(style);
            }
            // Only the chosen mode has a tooltip: a second click returns to the automatic mode.
            TextTooltip tip = modeTips.get(i);
            if (explicit) {
                tip.getActor().setText(text("GpuBoard.hud.dock.modeAutoTip",
                      GpuHintLine.key(inputs.preferences(), MODE_KEYS.get(i))));
                button.addListener(tip);
            } else if (button.removeListener(tip)) {
                tip.hide();
            }
        }
        undo.setDisabled(!move.canUndo());
        if (shown.standUp()) {
            standUp.details.getFirst().setText(unit == null ? "" : GpuHudKit.UnitRow.prone(unit)
                  ? text("GpuBoard.hud.state.prone") : GpuHudKit.UnitRow.hullDown(unit)
                  ? text("GpuBoard.hud.dock.hullDown") : "");
        }
        main(text("GpuBoard.hud.dock.confirmMove"), route && enabled(done()));
        secondary(text("GpuBoard.hud.dock.holdPosition"), enabled(skip()));
        waypoint.setDisabled(!move.canPin());
        clearRoute.setDisabled(!route);
        holdAll.setDisabled(!enabled(skip()) || move.holdingRemaining() > 0);
        if (open == Confirm.HOLD_ALL) {
            confirmation.text(text("GpuBoard.hud.dock.holdAllTitle"), text("GpuBoard.hud.dock.holdAllConfirm"),
                  text("GpuBoard.hud.dock.holdAllButton"), text("Cancel"));
        }
        // The plan's facing: the route's last, or the unit's own without a route (E2b).
        String facing = GpuHudKit.facing(move.facing());
        if (route) {
            String type = move.auto() ? text("GpuBoard.hud.dock.modeAuto", move.typeLabel()) : move.typeLabel();
            foot(text("GpuBoard.hud.dock.moveCost", move.cost(), move.budget()),
                  SEPARATOR + text("GpuBoard.hud.dock.moveFoot", type, facing, GpuHudKit.signed(move.heat())));
        } else {
            // The explicit mode's own word (E2b gearLabel): "walk", "run", "jump" or "Walk backwards".
            legendText.setText(move.explicit() ? text("GpuBoard.hud.dock.legendOnly", move.gearLabel(), facing)
                  : text("GpuBoard.hud.dock.legendAuto", facing));
        }
    }

    /**
     * Another player's turn (A.7 G17, and the same for firing and physical attacks): the waiting box. During another
     * player's firing turn the own focus unit's draft is shown read-only (H33, E3c), and the foot names it.
     */
    private void showWaiting() {
        GpuBattleStatus.Slot slot = current();
        GpuBattleStatus.UnitStatus unit = state.presented(slot.entityId());
        head(inputs.frame().status().phase().isMovement()
              ? text("GpuBoard.hud.dock.opponentMoving", slot.playerName())
              : text("GpuBoard.hud.phase.opponentTurn", slot.playerName()), slot.side() == GpuBattleStatus.Side.ENEMY);
        span(name(unit), UiTheme.MINT);
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        foot(fire.active() && !fire.editable() ? text("GpuBoard.hud.dock.weaponAttacks",
              name(state.presented(fire.actorId())), fire.attacks().size()) : "", "");
    }

    /**
     * The local weapon declaration (A.8 H3-H7): the orders' count, the twist and its read-out, Clear, Fire weapons or
     * Hold fire, Resolve phase, the confirmation prompt and the units left to declare.
     */
    private void showFire() {
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        int attacks = fire.attacks().size();
        int targets = fire.targets().size();
        String name = name(state.presented(fire.actorId()));
        head(targets > 0 ? text("GpuBoard.hud.dock.weaponAttacksTargets", name, attacks, targets)
              : text("GpuBoard.hud.dock.weaponAttacks", name, attacks), false);
        span("", UiTheme.MINT);
        twistLeft.setDisabled(!fire.canTwistLeft());
        twistRight.setDisabled(!fire.canTwistRight());
        // The read-out's word is the service's ("Torso", or "Turret" for a turret); its value follows the twist.
        torsoCaption.setText(UiTheme.upper(fire.torsoLabel().isBlank() ? text("GpuBoard.hud.dock.torso")
              : fire.torsoLabel()));
        torsoValue.setText(UiTheme.upper(twistWord(fire.twist())));
        clear.setDisabled(attacks == 0);
        // Fire weapons is Done; Hold fire is the turn without an action, which Resolve also presses (E3c).
        main(text(attacks > 0 ? "GpuBoard.hud.dock.fireWeapons" : "GpuBoard.hud.dock.holdFire"),
              enabled(attacks > 0 ? done() : noAction()));
        resolve.setDisabled(fire.autoDeclareRemaining() > 0);
        int others = othersToDeclare(fire);
        if (open != null) {
            confirmation.text(text(open.twist() ? "GpuBoard.hud.dock.twistTitle" : "GpuBoard.hud.dock.resolveTitle"),
                  open.twist() ? text("GpuBoard.hud.dock.twistConfirm", attacks)
                        : text("GpuBoard.hud.dock.resolveConfirm", others),
                  text(open.twist() ? "GpuBoard.hud.dock.twistAnyway" : "GpuBoard.hud.dock.resolve"),
                  text(open.twist() ? "GpuBoard.hud.dock.keepAttacks" : "Cancel"));
        }
        foot("", text("GpuBoard.hud.dock.fireFoot", others));
    }

    /** The local physical attack (A.9 I3-I6): one button per option, declare or no attack, and the consequence. */
    private void showPhysical() {
        GpuPhysicalOptions.Snapshot physical = inputs.frame().panels().physical();
        GpuBattleStatus.UnitStatus target = state.presented(physical.targetId());
        head(text("GpuBoard.hud.dock.physicalHead", name(state.presented(physical.actorId())),
              target == null ? "\u2014" : name(target)), false);
        span(text(physical.adjacentTargets().size() > 1 ? "GpuBoard.hud.dock.switchTarget"
              : "GpuBoard.hud.dock.adjacent"), UiTheme.MINT);
        List<GpuPhysicalOptions.Option> row = rowOptions();
        for (int i = 0; i < choices.size() && i < row.size(); i++) {
            GpuPhysicalOptions.Option option = row.get(i);
            UiButton button = choices.get(i);
            button.setText(UiTheme.upper(option.label()));
            button.details.getFirst().setText(odds(option));
            button.setDisabled(!option.available());
            button.pressed(chosen.contains(option.id()));
        }
        List<GpuPhysicalOptions.Option> picked = physical.options().stream()
              .filter(option -> chosen.contains(option.id())).toList();
        main(picked.isEmpty() ? text("GpuBoard.hud.dock.chooseAttack")
              : picked.size() > 1 ? text("GpuBoard.hud.dock.declarePunches")
              : text("GpuBoard.hud.dock.declare", attack(picked.getFirst())), !picked.isEmpty());
        secondary(text("GpuBoard.hud.dock.noAttack"), enabled(noAction()));
        foot("", picked.isEmpty() ? "" : consequence(picked.getFirst()));
    }

    /**
     * The physical phase's turn without an adjacent identified enemy (A.9 I7): the unit ends its turn without an
     * attack; More keeps MegaMek's dodge, laying explosives and clearing woods reachable.
     */
    private void showNoPhysical() {
        head(text("GpuBoard.hud.phase.physical"), false);
        span(text("GpuBoard.hud.dock.nonePossible"), UiTheme.MUTED);
        main(text("GpuBoard.hud.dock.continueEndPhase"), enabled(noAction()));
        foot("", text("GpuBoard.hud.dock.noAdjacentFoot"));
    }

    /**
     * Weapon and physical playback and the round review (A.10 J3-J6) over the round's playback history (G11). The
     * server has resolved every attack when the report phase begins; the history knows which of the phase's attacks
     * the board has presented ("{i} / {n} resolved"), the review cursor among the presented steps, the pause, the
     * replay and the speed. While the live playback runs, the main button skips to the results. The foot names the
     * event under the cursor in the log's words (GpuEventLine).
     */
    private void showPlayback() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GamePhase phase = playbackPhase();
        GpuPlaybackHistory history = state.history;
        boolean running = history.running();
        if (phase.isEndReport()) {
            head(text("GpuBoard.hud.dock.roundReview", status.round()), false);
            span(text("GpuBoard.hud.dock.nextInitiative"), UiTheme.MINT);
        } else {
            // The phase's attacks: weapon fire in its report, every other attack kind in the physical one.
            boolean weapons = phase.isFiringReport();
            Predicate<GpuReportLog.CombatEvent> ofPhase = event -> event.round() == status.round()
                  && (event.kind() == ResolvedAttack.Kind.SHOT) == weapons;
            long attacks = inputs.frame().reports().combat().stream().filter(ofPhase).count();
            long resolved = history.playedAttacks().stream().filter(ofPhase).count();
            head(text(weapons ? "GpuBoard.hud.dock.weaponFire" : "GpuBoard.hud.dock.physicalResolved", resolved,
                  attacks), false);
            String word = history.replaying() ? "GpuBoard.hud.dock.replaying"
                  : !running ? "GpuBoard.hud.dock.resolved"
                  : history.paused() ? "GpuBoard.hud.dock.paused" : "GpuBoard.hud.dock.playing";
            span(text(word), running ? UiTheme.AMBER : UiTheme.MINT);
        }
        boolean playing = history.replaying() || running && !history.paused();
        play.setText(UiTheme.upper(text(playing ? "GpuBoard.hud.dock.pause" : "GpuBoard.hud.dock.play")));
        ((Image) play.icons.getFirst()).setDrawable(ui.skin.getDrawable(playing ? "icon-pause" : "icon-play"));
        // The review cursor among the presented steps, 0 without one (the history's lists take no null).
        List<GpuPlaybackHistory.Step> played = history.played();
        GpuPlaybackHistory.Step current = history.current();
        counter.setText(text("GpuBoard.hud.dock.reviewable", current == null ? 0 : played.indexOf(current) + 1,
              played.size()));
        replay.setDisabled(played.isEmpty());
        if (phase.isEndReport()) {
            main(text("GpuBoard.hud.dock.readyNextRound"), enabled(done()));
        } else if (running) {
            main(text("GpuBoard.hud.dock.skipToResults"), true);
        } else {
            main(text(phase.isFiringReport() ? "GpuBoard.hud.dock.continuePhysical"
                  : "GpuBoard.hud.dock.continueEndPhase"), enabled(done()));
        }
        follow.pressed(camera.animateCombatPlayback);
        // The event under the cursor, as the log's card names it (J6).
        foot("", GpuEventLine.current(inputs.frame().reports(), history));
    }

    /**
     * Every other phase and turn (plan C.2, A.7 G20): the phase's name and status, its first phase commands, and Done
     * and Skip with MegaMek's labels ("Continue" in a report phase); movement without the planner names its unit.
     */
    private void showGeneric() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GamePhase phase = status.phase();
        String[] lines = inputs.frame().panels().phase().status().split("\n", -1);
        GpuBattleStatus.UnitStatus actor = status.myTurn() ? state.presented(status.actorId()) : null;
        // The phase header names the phase; the dock names the unit it commands and the phase display's status line.
        // Deployment's unit card stands beside the dock: its status line is enough.
        String headline = actor == null || phase.isDeployment() ? ""
              : phase.isMovement() ? text("GpuBoard.hud.dock.movement", name(actor)) : name(actor);
        String line = lines[0].strip();
        boolean deploying = phase.isDeployment() && status.myTurn();
        if (deploying) {
            // The phase strip names the turn; the compact dock only says what lets Deploy go.
            line = "";
        }
        head(headline, false);
        span(line, UiTheme.MINT);
        boolean headed = !headline.isEmpty() || !line.isEmpty();
        if ((headCell.getActor() == head) != headed) {
            headCell.setActor(headed ? head : null).padBottom(headed ? 7 : 0);
        }
        List<BoardScene.Command> row = rowCommands();
        for (int i = 0; i < choices.size() && i < row.size(); i++) {
            choices.get(i).setText(UiTheme.upper(row.get(i).label()));
        }
        BoardScene.Command done = done();
        if (done != null) {
            main(phase.isReport() ? text("GpuBoard.hud.dock.continue") : done.label(), done.enabled());
        }
        BoardScene.Command skip = skip();
        if (skip != null) {
            secondary(skip.label(), skip.enabled());
        }
        if (deploying) {
            // Placing is local until Deploy (Done) sends the unit, so the foot names the confirmation once it can.
            foot("", done != null && done.enabled()
                  ? text("GpuBoard.hud.deploy.footReady", UiTheme.upper(done.label()),
                        GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE))
                  : text("GpuBoard.hud.deploy.footPlace"));
        } else {
            foot("", lines.length > 1 ? lines[1].strip() : "");
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * The dock's main button and the DONE key: what it does depends on the variant. The movement plan without a route
     * says how to go on instead (A.7 G12); a playback that still runs skips to its results (J5, J7).
     */
    void main() {
        if (inputs == null) {
            return;
        }
        GpuHudData panels = inputs.frame().panels();
        if (variant == Variant.PLAN && panels.move().route().isEmpty()) {
            source.toasts().post(ToastLevel.INFO, text("GpuBoard.hud.move.plotFirst"));
            return;
        }
        if (main.isDisabled()) {
            return;
        }
        switch (variant) {
            case INITIATIVE, PLAN, GENERIC -> run(panels.phase().doneId());
            case NO_PHYSICAL -> run(noAction());
            case PLAYBACK -> {
                if (!playbackPhase().isEndReport() && state.history.running()) {
                    state.history.skip();
                } else {
                    run(panels.phase().doneId());
                }
            }
            case FIRE -> run(panels.fire().attacks().isEmpty() ? noAction() : done());
            case PHYSICAL -> source.physical().declare(List.copyOf(chosen));
            default -> { }
        }
    }

    /** The action row's secondary button: reroll, hold position, no attack or MegaMek's skip. */
    private void secondary() {
        switch (variant) {
            case INITIATIVE -> run(REROLL);
            case PLAN, GENERIC -> run(inputs.frame().panels().phase().skipId());
            case PHYSICAL -> run(noAction());
            default -> { }
        }
    }

    /** Resolve phase (A.8 H6): at once when no other unit declares, else behind the disclosure (E3c's mode). */
    private void resolve() {
        if (othersToDeclare(inputs.frame().panels().fire()) == 0) {
            source.fire().resolvePhase();
        } else {
            requestConfirmation(Confirm.RESOLVE);
        }
    }

    /**
     * Torso twist (TWIST_LEFT -1, TWIST_RIGHT +1) through confirmation while attacks are queued (A.8 H5); at the
     * limit a toast says so.
     */
    void twist(int direction) {
        if (inputs == null || variant != Variant.FIRE) {
            return;
        }
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        if (direction < 0 ? !fire.canTwistLeft() : !fire.canTwistRight()) {
            source.toasts().post(ToastLevel.INFO, text("GpuBoard.hud.fire.twistLimit"));
        } else if (fire.attacks().isEmpty()) {
            source.fire().twist(direction);
        } else {
            requestConfirmation(direction < 0 ? Confirm.TWIST_LEFT : Confirm.TWIST_RIGHT);
        }
    }

    /** The shared non-modal prompt, hosted in the HUD's popover layer. */
    UiConfirmation confirmation() {
        return confirmation;
    }

    private UiButton confirmAnchor(Confirm action) {
        return switch (action) {
            case HOLD_ALL -> holdAll;
            case TWIST_LEFT -> twistLeft;
            case TWIST_RIGHT -> twistRight;
            case RESOLVE -> resolve;
        };
    }

    private void requestConfirmation(Confirm action) {
        menu.cancel();
        if (!confirmation.toggle(confirmAnchor(action), () -> confirmed(action))) {
            return;
        }
        open = action;
        confirmActor = action == Confirm.HOLD_ALL ? inputs.frame().panels().move().entityId()
              : inputs.frame().panels().fire().actorId();
        confirmTurn = inputs.frame().status().turnIndex();
        confirmRound = inputs.frame().status().round();
        confirmPhase = inputs.frame().status().phase();
        if (action == Confirm.HOLD_ALL) {
            showPlan();
        } else {
            showFire();
        }
    }

    /** Called after the HUD places the dock, including after resize. Opening a card never reflows the dock. */
    void layoutConfirmation(float width, float height) {
        if (open != null && confirmAnchor(open).isDisabled()) {
            cancel();
        }
        confirmation.resize(width, height);
    }

    /** Runs the explicitly confirmed action, closing the card before invoking the existing game command. */
    private void confirmed(Confirm action) {
        switch (action) {
            case TWIST_LEFT -> source.fire().twist(-1);
            case TWIST_RIGHT -> source.fire().twist(1);
            case RESOLVE -> source.fire().resolvePhase();
            case HOLD_ALL -> source.moves().holdAll();
        }
    }

    /** Cancels without acting; the shared prompt also handles outside clicks and trigger toggling. */
    boolean cancel() {
        return confirmation.cancel();
    }

    /** Clear and the CLEAR_ORDERS key (A.8 H6, Delete): every queued attack, and the armed weapon with them (r2 5). */
    void clearOrders() {
        source.fire().clearAll();
        state.armedWeapon = -1;
    }

    /** Stop ends the hold or the auto-declare mode. */
    private void stop() {
        if (inputs.frame().status().phase().isMovement()) {
            source.moves().stopHolding();
        } else {
            source.fire().stopResolve();
        }
    }

    /** Chooses or releases a physical option: the two punches combine, every other option is chosen alone (I4). */
    private void choose(String id) {
        List<GpuPhysicalOptions.Option> options = inputs.frame().panels().physical().options();
        GpuPhysicalOptions.Option option = options.stream().filter(candidate -> candidate.id().equals(id))
              .findFirst().orElse(null);
        if (option == null || !option.available() || chosen.remove(id)) {
            return;
        }
        boolean together = option.combinable() && options.stream().filter(other -> chosen.contains(other.id()))
              .allMatch(GpuPhysicalOptions.Option::combinable);
        if (!together) {
            chosen.clear();
        }
        chosen.add(id);
    }

    /** Esc's step in the physical attack: releases the chosen options, undeclared; false when none is chosen. */
    boolean releaseChosen() {
        if (chosen.isEmpty()) {
            return false;
        }
        chosen.clear();
        return true;
    }

    /** Runs the phase command with this id, as the frame last showed it, while it is enabled. */
    private void run(String id) {
        run(command(id));
    }

    private static void run(BoardScene.Command command) {
        if (enabled(command)) {
            command.action().run();
        }
    }

    // ------------------------------------------------------------------ More

    /** The More button: the shown variant's More (A.7 G14) in the panel that slides out of the dock. */
    private void openMore() {
        More content = more();
        if (content == null) {
            drawer.close();
        } else {
            menu.cancel();
            drawer.toggle(content);
        }
    }

    /** The More panel, which the HUD places beside or above the dock. */
    GpuMoreDrawer drawer() {
        return drawer;
    }

    /**
     * The More popover of the shown variant, or null without one: the remaining movement commands grouped by
     * availability, the weapon declaration's MegaMek extras (H38), the physical options beyond the option row and the
     * physical phase's other commands, or the phase commands beyond the generic row (C.2). Firing and physical turns
     * also offer the unit's record (unit panel design 2.2).
     */
    More more() {
        if (inputs == null || !hasMore()) {
            return null;
        }
        List<BoardScene.Command> items = new ArrayList<>();
        More more = switch (variant) {
            case PLAN -> {
                items.addAll(others(standUpShown() ? MOVE_SHOWN_DOWN : MOVE_SHOWN).map(GpuCommandDock::entry)
                      .toList());
                yield new More(text("GpuBoard.hud.dock.moreMovement"), null,
                      items.stream().filter(BoardScene.Command::enabled).toList(),
                      items.stream().filter(command -> !command.enabled()).toList());
            }
            case FIRE -> {
                int actor = inputs.frame().panels().fire().actorId();
                items.add(unitRecord(actor));
                yield new More(text("GpuBoard.hud.common.more"), name(state.presented(actor)), items,
                      others(FIRE_SHOWN).map(GpuCommandDock::entry).toList());
            }
            case PHYSICAL, NO_PHYSICAL -> {
                GpuPhysicalOptions.Snapshot physical = inputs.frame().panels().physical();
                List<GpuPhysicalOptions.Option> row = variant == Variant.PHYSICAL ? rowOptions() : List.of();
                physical.options().stream().filter(option -> !row.contains(option)).forEach(option ->
                      items.add(item("dock.option." + option.id(), option.label(), odds(option), option.available(),
                            () -> choose(option.id()))));
                items.add(unitRecord(physical.actorId()));
                yield new More(text("GpuBoard.hud.common.more"), name(state.presented(physical.actorId())), items,
                      others(PHYSICAL_SHOWN).map(GpuCommandDock::entry).toList());
            }
            default -> {
                List<BoardScene.Command> row = rowCommands();
                yield new More(text("GpuBoard.hud.common.more"), inputs.frame().status().phase().localizedName(),
                      items, others(Set.of()).filter(command -> !row.contains(command)).map(GpuCommandDock::entry)
                      .toList());
            }
        };
        // Without a command to use now, the dock shows no More button.
        return GpuMoreDrawer.usable(more).isEmpty() ? null : more;
    }

    /**
     * Whether the shown variant offers More: movement and weapon declaration always; the turn without an adjacent
     * enemy only while one of MegaMek's other physical commands (dodge, explosives, clear woods) can be used.
     */
    private boolean hasMore() {
        return switch (variant) {
            case PLAN, FIRE -> true;
            case PHYSICAL -> inputs.frame().panels().physical().options().size() > rowOptions().size()
                  || others(PHYSICAL_SHOWN).findAny().isPresent();
            case NO_PHYSICAL -> others(PHYSICAL_SHOWN).anyMatch(BoardScene.Command::enabled);
            case GENERIC -> others(Set.of()).count() > rowCommands().size();
            default -> false;
        };
    }

    private static BoardScene.Command item(String id, String label, String detail, boolean enabled, Runnable action) {
        return new BoardScene.Command(id, label, detail, enabled, false, List.of(), action);
    }

    /** "Unit record": the sheet with the unit's record, as the card's button and UNIT_DISPLAY open it. */
    private BoardScene.Command unitRecord(int unitId) {
        int acting = GpuHudKit.UnitRow.acting(inputs.frame().status(), state.focus());
        return item("dock.unitRecord", text("GpuBoard.hud.common.unitRecord"),
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.UNIT_DISPLAY), unitId != Entity.NONE,
              () -> GpuRecordSheet.showUnit(state, unitId, acting));
    }

    /** A MegaMek command in More: its shortcut as the detail, or "unavailable" (A.7 G14). */
    private static BoardScene.Command entry(BoardScene.Command command) {
        return new BoardScene.Command(command.id(), command.label(), command.enabled() ? command.shortcut()
              : text("GpuBoard.hud.dock.unavailable"), command.enabled(), command.commit(), command.boardTool(),
              command.children(), command.action(), command.shortcut(), command.selected());
    }

    /**
     * The phase commands no control of the dock stands for, in MegaMek's order. Done, skip and clear are the action
     * row's; groups (the old weapon list) are the weapons panel's.
     */
    private Stream<BoardScene.Command> others(Set<String> shown) {
        GpuBoardActions.PhaseInfo phase = inputs.frame().panels().phase();
        List<String> actions = List.of(phase.doneId(), phase.skipId(), phase.clearId());
        return commands().stream().filter(command -> command.children().isEmpty() && !actions.contains(command.id())
              && !shown.contains(command.id()));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The option row's physical options (A.9 I4, E4): the first four available ones, filled up with unavailable ones,
     * in the service's order (the limbs first). A Mek thus shows its punches and kicks, an unavailable one with its
     * reason, and a unit that cannot punch or kick shows what it can do; More lists the rest.
     */
    private List<GpuPhysicalOptions.Option> rowOptions() {
        List<GpuPhysicalOptions.Option> options = inputs.frame().panels().physical().options();
        List<GpuPhysicalOptions.Option> row = Stream.concat(options.stream().filter(option -> option.available()),
              options.stream().filter(option -> !option.available())).limit(OPTIONS).toList();
        return options.stream().filter(row::contains).toList();
    }

    /**
     * The generic option row: the first five phase commands in MegaMek's order that are available now. Deployment's
     * compact dock keeps two: its next unit is a card of the force, turning a drag, and Remove (which takes the unit
     * out of the game) stays in More.
     */
    private List<BoardScene.Command> rowCommands() {
        boolean deployment = inputs.frame().status().phase().isDeployment();
        return others(Set.of()).filter(BoardScene.Command::enabled)
              .filter(command -> !deployment || !DEPLOY_MORE_ONLY.contains(command.id()))
              .limit(deployment ? DEPLOY_COMMANDS : COMMANDS).toList();
    }

    private List<BoardScene.Command> commands() {
        BoardScene scene = inputs.frame().scene();
        return scene == null ? List.of() : scene.commands();
    }

    /** The phase command with this id, or null. */
    private BoardScene.Command command(String id) {
        return id == null || id.isEmpty() ? null
              : commands().stream().filter(command -> command.id().equals(id)).findFirst().orElse(null);
    }

    private BoardScene.Command done() {
        return command(inputs.frame().panels().phase().doneId());
    }

    private BoardScene.Command skip() {
        return command(inputs.frame().panels().phase().skipId());
    }

    /**
     * The phase command that ends the turn without an action: the skip, else Done, which in an attack phase with
     * MegaMek's "nag for no action" off (Skip hidden) holds while nothing is declared.
     */
    private BoardScene.Command noAction() {
        BoardScene.Command skip = skip();
        return skip != null ? skip : done();
    }

    /** MegaMek's Done also holds the unit, because the phase display hides its Skip (GpuBoardActions.skipButton). */
    private boolean skipIsDone() {
        GpuBoardActions.PhaseInfo phase = inputs.frame().panels().phase();
        return !phase.skipId().isEmpty() && phase.skipId().equals(phase.doneId());
    }

    private static boolean enabled(BoardScene.Command command) {
        return command != null && command.enabled();
    }

    /**
     * Stand up shows in the movement plan while MegaMek offers Get Up: the unit, or the plan's end, is prone or hull
     * down.
     */
    private boolean standUpShown() {
        return variant == Variant.PLAN && enabled(command(GET_UP));
    }

    /** The "Steps" mini: the turn-details preference is on and the local turn has steps (plan C.2, P6). */
    private boolean stepsShown() {
        return inputs.preferences().turnDetails() && !inputs.frame().panels().phase().turnDetails().isEmpty();
    }

    /** The own units other than the actor that still declare attacks in this phase (A.8 H6, H7; E3c). */
    private static int othersToDeclare(GpuFireOrders.Snapshot fire) {
        return Math.max(0, fire.pendingUnits() - 1);
    }

    /** The twist read-out's direction (H4): Center, Left or Right, and Rear for a turret turned around. */
    private static String twistWord(int twist) {
        return twist == 0 ? text("GpuBoard.hud.dock.center") : twist == 3 ? text("GpuBoard.hud.dock.rear")
              : text(twist < 0 ? "Left" : "Right");
    }

    /** A unit's name in the dock, the chassis, as the unit card names it. */
    private static String name(GpuBattleStatus.UnitStatus unit) {
        return unit == null ? "" : unit.chassis();
    }

    /** An option's sub-label (A.9 I4): its roll, odds (0-100) and damage, or why it is unavailable. */
    private static String odds(GpuPhysicalOptions.Option option) {
        return option.available() ? text("GpuBoard.hud.physical.odds", GpuHudKit.shown(option.value()),
              Math.round(option.odds()), option.damage()) : option.reason();
    }

    /** The attack's word in "Declare {attack}": the label without its limb, so "Kick L" declares a kick. */
    private static String attack(GpuPhysicalOptions.Option option) {
        String label = option.label();
        return option.limb().isEmpty() || !label.endsWith(option.limb()) ? label
              : label.substring(0, label.length() - option.limb().length()).strip();
    }

    /**
     * The foot of a chosen option (A.9 I6): its hit table and the piloting rolls a miss or hit calls for, as of now.
     * Without a roll MegaMek's shared helper knows (E4), the table alone: never "a miss has no effect".
     */
    private static String consequence(GpuPhysicalOptions.Option option) {
        List<String> parts = new ArrayList<>();
        if (!option.table().isEmpty()) {
            parts.add(option.table());
        }
        parts.addAll(option.consequences());
        return String.join(SEPARATOR, parts);
    }

    private static String modeName(GpuMovePlan.Mode mode) {
        return text(switch (mode) {
            case RUN -> "GpuBoard.hud.dock.run";
            case JUMP -> "GpuBoard.hud.dock.jump";
            default -> "GpuBoard.hud.dock.walk";
        });
    }

    private void head(String text, boolean foe) {
        title.setText(UiTheme.upper(text));
        title.setColor(foe ? UiTheme.CORAL : UiTheme.TEXT);
    }

    private void span(String text, Color color) {
        span.setText(text);
        span.setColor(color);
    }

    private void main(String text, boolean enabled) {
        main.setText(UiTheme.upper(text));
        main.setDisabled(!enabled);
    }

    private void secondary(String text, boolean enabled) {
        secondary.setText(UiTheme.upper(text));
        secondary.setDisabled(!enabled);
    }

    private void foot(String bold, String text) {
        footBold.setText(bold);
        footText.setText(text);
    }
}
