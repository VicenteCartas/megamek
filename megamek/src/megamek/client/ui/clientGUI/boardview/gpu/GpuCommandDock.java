/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
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
import megamek.common.interfaces.IStartingPositions;
import megamek.common.units.Entity;

/**
 * Command dock, bottom centre (C.1 G6): a head line, an option row, an action row and a foot line,
 * in one variant per phase and turn (plan C.2). It presents the snapshots and runs the phase's own commands, the
 * movement, fire and physical services, the playback history and the camera's follow flag; the client keeps every
 * rule.
 */
final class GpuCommandDock implements GpuHud.Component {
    /**
     * The dock's variants (plan C.2); GENERIC is every phase and turn the prototype does not design. ENTRY is a walk-on
     * unit setting up its entry (walk-on step 1): its entry hex, facing, height, mode and setup, before it enters and
     * plans its move. STATUS holds the unit commands while the board plays a move, the local selection is cleared or
     * the forces overview is open, and says why in the waiting box. BOT_ORDERS gives orders to the selected allied bot
     * lance or unit (GpuBotDock), in any phase and turn.
     */
    private enum Variant {
        INITIATIVE, ENTRY, PLAN, FLIGHT, WAITING, FIRE, PHYSICAL, NO_PHYSICAL, PLAYBACK, GENERIC, STATUS, BOT_ORDERS
    }

    /** The action awaiting an explicit choice beside its triggering button. */
    private enum Confirm {
        TWIST_LEFT, TWIST_RIGHT, RESOLVE, HOLD_ALL;

        boolean twist() {
            return this == TWIST_LEFT || this == TWIST_RIGHT;
        }
    }

    /**
     * What decides the dock's cells; they are rebuilt only when it changes. {@code buttons} are the ids of the option
     * row's physical options or phase commands; {@code back} adds the Back gait and {@code context} the context slot
     * (GpuMoveContext); {@code jump} the Jump gait of a unit that has jump MP and
     * {@code elevation} the elevation stepper of a unit that flies or dives; {@code run} the Run gait of a unit faster
     * running than walking (not infantry, which move at one speed); {@code setup} the ids of the deploying unit's setup
     * tiles (GpuDeploySetup).
     */
    private record Shape(Variant variant, List<String> buttons, boolean more, boolean primary, boolean secondary,
          boolean stop, boolean legend, boolean steps, boolean back, boolean context,
          boolean heights, boolean jump, boolean elevation, boolean run, List<String> forms, List<String> setup) { }

    /**
     * The More popover's content (plan A.7 G14, A.8 H38, C.2), which G12 shows above the More button: a title and
     * subtitle, the dock's own items, and MegaMek's other phase commands after a separator (an unavailable one says so
     * in its detail). Movement instead groups all available actions before all unavailable actions and omits the
     * subtitle.
     */
    record More(String title, String subtitle, List<BoardScene.Command> items, List<BoardScene.Command> commands,
          List<GpuDeploySetup.Tile> setup) {
        More {
            items = List.copyOf(items);
            commands = List.copyOf(commands);
            setup = List.copyOf(setup);
        }

        More(String title, String subtitle, List<BoardScene.Command> items, List<BoardScene.Command> commands) {
            this(title, subtitle, items, commands, List.of());
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
    /**
     * The gap between the gaits of the segmented control, and the context slot's fixed width: a tool like the twists,
     * a little wider for "Drop cargo", and narrower than a gait.
     */
    private static final float SEGMENT = 2;
    private static final float CONTEXT = 60;
    /** The torso read-out's width (#dock .r1 .torso). */
    private static final float TORSO = 76;
    /** The physical options the option row shows (the prototype's four limbs), and the generic phase commands. */
    private static final int OPTIONS = 4;
    private static final int COMMANDS = 5;
    /**
     * Deployment's compact dock: its row's commands, and those the row leaves to More (Clear, also the secondary
     * button, among them).
     */
    private static final int DEPLOY_COMMANDS = 2;
    /**
     * The deploying unit's setup tiles the option row shows (two fewer beside the height stepper), the rest in More,
     * and a tile's height: a caption over its value.
     */
    private static final int SETUP_TILES = 5;
    private static final float SETUP_TILE = GpuSetupTile.HEIGHT;
    private static final String DEPLOY_CLEAR = DeployCommand.DEPLOY_CLEAR_DEPLOY.getCmd();
    private static final Set<String> DEPLOY_MORE_ONLY = Set.of(DeployCommand.DEPLOY_NEXT.getCmd(),
          DeployCommand.DEPLOY_TURN.getCmd(), DeployCommand.DEPLOY_REMOVE.getCmd(), DEPLOY_CLEAR);
    /**
     * Deployment commands More leaves out: MegaMek's Turn (a click-to-face mode the board's arrows, drag and More's
     * Turn left and right stand for) and Clear, the dock's secondary button.
     */
    private static final Set<String> DEPLOY_SHOWN = Set.of(DeployCommand.DEPLOY_TURN.getCmd(), DEPLOY_CLEAR);
    private static final String SEPARATOR = " \u00B7 ";
    private static final Color WAITING = Color.valueOf("8A9593");
    /** The gait segments; Back only for a unit with MegaMek's back-up command. Back has no key of its own. */
    private static final List<GpuMovePlan.Mode> MODES = List.of(GpuMovePlan.Mode.WALK, GpuMovePlan.Mode.RUN,
          GpuMovePlan.Mode.JUMP, GpuMovePlan.Mode.BACK);
    private static final List<KeyCommandBind> MODE_KEYS = List.of(KeyCommandBind.MOVE_MODE_WALK,
          KeyCommandBind.MOVE_MODE_RUN, KeyCommandBind.MOVE_MODE_JUMP);
    private static final String BACK_UP = MoveCommand.MOVE_BACK_UP.getCmd();
    private static final String JUMP = MoveCommand.MOVE_JUMP.getCmd();
    private static final String GO_UP = MoveCommand.MOVE_RAISE_ELEVATION.getCmd();
    private static final String GO_DOWN = MoveCommand.MOVE_LOWER_ELEVATION.getCmd();
    private static final String ACCELERATE = MoveCommand.MOVE_ACC.getCmd();
    private static final String DECELERATE = MoveCommand.MOVE_DEC.getCmd();
    private static final String TURN = MoveCommand.MOVE_TURN.getCmd();
    /** The velocity read-out's least width, wide enough for "3→4". */
    private static final float VELOCITY = 44;
    /** The elevation read-out's least width, for its short caption, and the spinner's width beside it. */
    private static final float ELEVATION = 34;
    private static final float SPINNER = 30;
    /**
     * MegaMek commands that a control of the dock, the forces panel's "Next pending" or the weapons panel stands for;
     * More lists every other phase command but the context slot's.
     */
    private static final Set<String> MOVE_SHOWN = Stream.of(MoveCommand.MOVE_WALK, MoveCommand.MOVE_JUMP,
          MoveCommand.MOVE_BACK_UP, MoveCommand.MOVE_TURN, MoveCommand.MOVE_NEXT).map(MoveCommand::getCmd)
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
    /** Walk-on step 2's way back to step 1: the head's Setup mini, in its cell after the span. */
    private final UiButton entrySetup;
    private Cell<UiButton> entrySetupCell;
    private final Table row1 = new Table();
    private final Table row2 = new Table();
    private final Table foot = new Table();
    private final Cell<Table> headCell;
    private final Cell<Actor> row1Cell;
    private final Cell<Actor> row2Cell;
    private final UiButton main;
    private final UiButton secondary;
    private final UiButton more;
    /** The More panel that slides out beside the dock, or above it where the window has no room beside it. */
    private final GpuMoreDrawer drawer;
    // Movement plan.
    private final List<UiButton> modes = new ArrayList<>();
    /**
     * The context slot (GpuMoveContext): one MegaMek command for the unit's situation or stance, Stand up while it is
     * down, as a glyph over a short caption like the twists. Its width is fixed, so a change of command moves nothing.
     */
    private final UiButton context;
    private final TextTooltip contextTip;
    private final TextButton.TextButtonStyle plainStyle;
    private final TextButton.TextButtonStyle autoStyle;
    /**
     * The elevation stepper of a unit that flies or dives: MegaMek's Go down and Go up beside the plan's elevation, as
     * the torso read-out sits between the twists.
     */
    private final UiButton goDown;
    private final UiButton goUp;
    private final TextTooltip goDownTip;
    private final TextTooltip goUpTip;
    private final Table elevation = new Table();
    private final Label elevationCaption;
    private final TextTooltip elevationTip;
    private final Label elevationValue;
    /**
     * Flight (FLIGHT): MegaMek's Decelerate and Accelerate beside the velocity, as the elevation stepper sits beside
     * its read-out; the altitude stepper is the plan's, and its turns are in More.
     */
    private final UiButton decelerate;
    private final UiButton accelerate;
    private final TextTooltip decelerateTip;
    private final TextTooltip accelerateTip;
    private final Table velocity = new Table();
    private final Label velocityValue;
    private final UiButton undo;
    private final UiButton waypoint;
    private final UiButton clearRoute;
    private final TextTooltip clearRouteTip;
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
    // Deployment and walk-on step 1: the placed unit's height stepper.
    private final GpuDeployHeight deployHeight;
    private final UiButton heightDown;
    private final UiButton height;
    private final UiButton heightUp;
    private final TextTooltip heightDownTip;
    private final TextTooltip heightTip;
    private final TextTooltip heightUpTip;
    /**
     * Deployment: the setup tiles of the shape (GpuDeploySetup), a changed one framed in amber, and the main button's
     * sub-line, where the unit stands and how many setup choices changed.
     */
    private final Map<String, GpuSetupTile> setupTiles = new LinkedHashMap<>();
    private final Label mainLine;
    private final Cell<Label> mainLineCell;
    // Another player's turn.
    private final Container<Label> waiting;
    private final Label waitingLabel;
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
    private final Label legendWalk;
    private final Label legendRun;
    /** Run's legend sample and word, closed for a unit without a run (infantry). */
    private final Actor legendRunStroke;
    private final Cell<Actor> legendRunStrokeCell;
    private final Cell<Actor> legendRunCell;
    private final Label legendText;
    private final Table footRight = new Table();
    private final UiButton steps;
    /** The mode buttons' tooltips, each on its button only while that mode is chosen. */
    private final List<TextTooltip> modeTips = new ArrayList<>();
    private final TextTooltip stepsTip;
    /** The option row's physical options (PHYSICAL) or phase commands (GENERIC), rebuilt with the shape. */
    private final List<UiButton> choices = new ArrayList<>();
    /** The head's mode switch of a unit that converts, in the order of the plan's forms. */
    private final List<UiButton> forms = new ArrayList<>();
    /** The mode switch's tooltips: why a mode is disabled, or what choosing it costs. */
    private final List<TextTooltip> formTips = new ArrayList<>();
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
          GpuDeployHeight deployHeight) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.camera = camera;
        this.menu = menu;
        this.deployHeight = deployHeight;
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
        entrySetup = button("hud-mini", null, text("GpuBoard.hud.move.entry.setup"), null, "dock-entry-setup");
        onChange(entrySetup, () -> {
            if (inputs != null) {
                source.setEntered(inputs.frame().panels().move().entityId(), false);
            }
        });
        ui.tip(entrySetup).getActor().setText(text("GpuBoard.hud.move.entry.setupTip"));
        head.pad(0, 2, 0, 2);

        main = button("hud-main", null, "", "", "dock-main");
        mainLine = main.details.getFirst();
        mainLine.setName("dock-main-line");
        mainLineCell = main.getCell(mainLine);
        mainLineCell.height(0).padTop(0);

        onChange(main, this::mainButton);
        secondary = button("hud", null, "", null, "dock-secondary");
        secondary.pad(0, 14, 0, 14);
        onChange(secondary, this::secondary);
        more = button("hud", "more", null, null, "dock-more");
        more.pad(0, 8, 0, 8);
        onChange(more, this::openMore);
        drawer = new GpuMoreDrawer(ui, this::chooseSetup);

        // Movement: .b.mode buttons at 14 units with their MP, the tall squares and the undo square.
        plainStyle = ui.skin.get("hud", TextButton.TextButtonStyle.class);
        autoStyle = new TextButton.TextButtonStyle(plainStyle);
        // .b.mode.auto: the route's effective mode, outlined in white without a fill.
        autoStyle.up = ui.skin.getDrawable("button-auto");
        autoStyle.fontColor = Color.WHITE;
        for (GpuMovePlan.Mode mode : MODES) {
            UiButton button = button("hud", null, modeName(mode, false), "",
                  "dock-mode-" + mode.name().toLowerCase(Locale.ROOT));
            UiKit.size(button.getLabel(), "hud-button", 14);
            onChange(button, () -> source.moves().setMode(mode));
            modes.add(button);
            modeTips.add(ui.tip(button));
        }
        context = button("hud", GpuMoveContext.icon(GpuMoveContext.GET_UP), null, "", "dock-context");
        context.pad(0, 4, 0, 4);
        UiKit.size(context.details.getFirst(), "hud-sub", 9);
        onChange(context, () -> run(contextCommand()));
        contextTip = ui.tip(context);
        goDown = button("hud", "chevron-down", null, null, "dock-go-down");
        goDown.pad(0);
        goDownTip = ui.tip(goDown);
        onChange(goDown, () -> run(GO_DOWN));
        goUp = button("hud", "chevron-up", null, null, "dock-go-up");
        goUp.pad(0);
        goUpTip = ui.tip(goUp);
        onChange(goUp, () -> run(GO_UP));
        elevationCaption = ui.label(UiTheme.upper(text("GpuBoard.hud.dock.elevationShort")), "hud-caption",
              9.5f, UiTheme.MUTED);
        elevationValue = ui.label("", "hud-title", 13, UiTheme.MINT);
        elevationValue.setName("dock-elevation-value");
        elevation.setName("dock-elevation");
        elevation.setTouchable(Touchable.enabled);
        elevation.add(elevationCaption).row();
        elevation.add(elevationValue);
        elevationTip = ui.tip(elevation);
        elevationTip.getActor().setText(text("GpuBoard.hud.dock.elevationTip"));
        decelerate = button("hud", null, text("GpuBoard.hud.dock.decelerate"), null, "dock-decelerate");
        decelerate.pad(0, 6, 0, 6);
        decelerateTip = ui.tip(decelerate);
        onChange(decelerate, () -> run(DECELERATE));
        accelerate = button("hud", null, text("GpuBoard.hud.dock.accelerate"), null, "dock-accelerate");
        accelerate.pad(0, 6, 0, 6);
        accelerateTip = ui.tip(accelerate);
        onChange(accelerate, () -> run(ACCELERATE));
        Label velocityCaption = ui.label(UiTheme.upper(text("GpuBoard.hud.dock.velocityShort")), "hud-caption",
              9.5f, UiTheme.MUTED);
        velocityValue = ui.label("", "hud-title", 13, UiTheme.MINT);
        velocityValue.setName("dock-velocity-value");
        velocity.setName("dock-velocity");
        velocity.setTouchable(Touchable.enabled);
        velocity.add(velocityCaption).row();
        velocity.add(velocityValue);
        ui.tip(velocity).getActor().setText(text("GpuBoard.hud.dock.velocityTip"));
        undo = button("hud", "undo", null, null, "dock-undo");
        undo.pad(0, 8, 0, 8);
        onChange(undo, () -> source.moves().undo());
        waypoint = button("hud-mini", null, text("GpuBoard.hud.dock.waypoint"), null, "dock-waypoint");
        onChange(waypoint, () -> source.moves().pinDestination());
        ui.tip(waypoint).getActor().setText(text("GpuBoard.hud.dock.waypointTip"));
        clearRoute = button("hud-mini", null, "X", null, "dock-clear-route");
        onChange(clearRoute, () -> source.moves().clearOrWithdraw());
        clearRouteTip = ui.tip(clearRoute);
        clearRouteTip.getActor().setText(text("GpuBoard.hud.dock.clearRoute"));
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

        // Deployment, and walk-on step 1: the placed unit's height, lower and higher beside it; a click on it steps up
        // and wraps.
        heightDown = button("hud", "chevron-down", null, null, "dock-height-down");
        heightDown.pad(0, 8, 0, 8);
        heightDownTip = ui.tip(heightDown);
        onChange(heightDown, () -> deployHeight.step(placingActor(), -1, false));
        height = button("hud", null, "", null, "dock-height");
        heightTip = ui.tip(height);
        onChange(height, () -> deployHeight.step(placingActor(), 1, true));
        heightUp = button("hud", "chevron-up", null, null, "dock-height-up");
        heightUp.pad(0, 8, 0, 8);
        heightUpTip = ui.tip(heightUp);
        onChange(heightUp, () -> deployHeight.step(placingActor(), 1, false));

        waitingLabel = ui.label(UiTheme.upper(text("GpuBoard.hud.dock.waitingForOpponent")), "hud-main",
              17, WAITING);
        waitingLabel.setEllipsis(true);
        waitingLabel.setName("dock-waiting-text");
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
        legendWalk = legend(text("GpuBoard.hud.dock.walk"), UiTheme.MINT);
        legendRun = legend(text("GpuBoard.hud.dock.run"), UiTheme.AMBER);
        legendRunCell = legend.getCell((Actor) legendRun);
        legendRunStrokeCell = legend.getCells().get(legend.getCells().indexOf(legendRunCell, true) - 1);
        legendRunStroke = legendRunStrokeCell.getActor();
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

    /** An icon after the caption, in the caption's color. */
    private void trailingIcon(UiButton button, String icon, float size, float gap) {
        UiKit.Icon image = ui.icon(icon, size, Color.WHITE);
        button.icons.add(image);
        button.add(image).padLeft(gap);
    }

    /** One legend sample (.leg): a 14-unit dashed stroke and its word, in the band's color. Returns the word. */
    private Label legend(String text, Color color) {
        Table stroke = new Table();
        stroke.add(new Image(ui.skin.newDrawable("white", color))).size(5, 2);
        stroke.add(new Image(ui.skin.newDrawable("white", color))).size(5, 2).padLeft(4);
        legend.add(stroke).padRight(5);
        Label word = ui.label(text, "hud-small", 11.5f, color);
        legend.add(word).padRight(10);
        return word;
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
            case BOT_ORDERS -> botRow().stream().map(GpuBotDock.Action::id).toList();
            default -> List.of();
        };
        boolean secondaryShown = switch (variant) {
            case INITIATIVE -> enabled(command(REROLL));
            case ENTRY, PLAN, PHYSICAL, BOT_ORDERS -> true;
            // A flying unit rarely may hold: MegaMek's Skip only while it allows one.
            case FLIGHT -> enabled(skip()) && !skipIsDone();
            // Without MegaMek's Skip, its Done both moves and holds: the main button is that command already.
            // Deployment has no Skip: Clear takes its place.
            case GENERIC -> deployClear() != null || skip() != null && !skipIsDone();
            default -> false;
        };
        boolean stopShown = phase.isMovement() && panels.move().holdingRemaining() > 0
              || (phase.isFiring() || phase.isTargeting()) && panels.fire().autoDeclareRemaining() > 0;
        // The movement plan and walk-on step 1 keep their More in place, disabled while nothing in it can be used, so
        // their row never moves.
        More content = variant == Variant.STATUS ? null : more();
        boolean planned = variant == Variant.PLAN || variant == Variant.FLIGHT;
        boolean entry = variant == Variant.ENTRY;
        GpuDeploySetup.Choice setup = variant == Variant.GENERIC || entry ? setupChoice() : null;
        Shape shape = new Shape(variant, buttons, planned || entry && hasMore() || content != null,
              variant != Variant.STATUS && variant != Variant.WAITING
              && (variant != Variant.GENERIC || done() != null), secondaryShown, stopShown,
              variant == Variant.PLAN && panels.move().route().isEmpty(), stepsShown(),
              variant == Variant.PLAN && command(BACK_UP) != null,
              variant == Variant.PLAN && contextCommand() != null,
              (variant == Variant.GENERIC || entry) && deployHeight.choice(placingActor()) != null,
              variant == Variant.PLAN && jumpShown(), planned && elevationShown(),
              variant == Variant.PLAN && panels.move().traits().runs(),
              planned ? panels.move().traits().forms().stream().map(GpuMovePlan.Form::label).toList()
                    : (variant == Variant.GENERIC || entry) && startForms() != null ? startForms().labels()
                    : List.of(),
              setup == null ? List.of() : setup.tiles().stream().map(GpuDeploySetup.Tile::id).toList());
        if (!shape.equals(shown)) {
            shown = shape;
            layout(shape);
        }
        more.setDisabled(content == null);
        if (variant != Variant.GENERIC && !entry) {
            mainLine("");
        }
        switch (variant) {
            case INITIATIVE -> showInitiative();
            case ENTRY -> showEntry();
            case PLAN -> showPlan();
            case FLIGHT -> showFlight();
            case WAITING -> showWaiting();
            case FIRE -> showFire();
            case PHYSICAL -> showPhysical();
            case NO_PHYSICAL -> showNoPhysical();
            case PLAYBACK -> showPlayback();
            case GENERIC -> showGeneric();
            case STATUS -> showStatus();
            case BOT_ORDERS -> showBotOrders();
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
        if (variant == Variant.STATUS) { drawer.close(); }
        drawer.update(drawer.isOpen() ? more() : null);
    }

    /** The variant of the phase and turn (plan C.2). */
    private Variant variant() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuHudData panels = inputs.frame().panels();
        GamePhase phase = playbackPhase();
        // Orders for an allied bot go out at once, outside the local turn too, but not while a move plays.
        if (!state.playbackBusy() && botTarget() != null) { return Variant.BOT_ORDERS; }
        if (state.overview || state.turnLocked(status) && !phase.isReport()) { return Variant.STATUS; }
        boolean turns = phase.isMovement() || phase.isFiring() || phase.isTargeting() || phase.isPhysical();
        if (phase.isInitiativeReport()) {
            return Variant.INITIATIVE;
        } else if (phase.isFiringReport() || phase.isPhysicalReport() || phase.isEndReport()) {
            return Variant.PLAYBACK;
        } else if (turns && !status.myTurn()) {
            return current() == null ? Variant.GENERIC : Variant.WAITING;
        } else if (phase.isMovement() && panels.move().active() && panels.move().traits().flight() == null
              && settingUpEntry(panels.move().entityId())) {
            // A walk-on unit sets up its entry first: its entry hex, facing, height, mode and setup are free. An
            // aerospace unit too, though MegaMek's clicks, not the planner, plot its flight once it entered.
            return Variant.ENTRY;
        } else if (phase.isMovement() && panels.move().active()
              && (panels.move().planner() || panels.move().traits().entering()
                    && panels.move().traits().flight() == null)) {
            // A walk-on unit before its entry hex has the plan's layout, so nothing moves once it enters.
            return Variant.PLAN;
        } else if (phase.isMovement() && panels.move().active() && panels.move().traits().flight() != null) {
            return Variant.FLIGHT;
        } else if ((phase.isFiring() || phase.isTargeting()) && panels.fire().active() && panels.fire().editable()) {
            return Variant.FIRE;
        } else if (phase.isPhysical() && panels.physical().active()) {
            return panels.physical().adjacentTargets().isEmpty() ? Variant.NO_PHYSICAL : Variant.PHYSICAL;
        }
        return Variant.GENERIC;
    }

    /** Whether the walk-on unit {@code unitId} is setting up its entry (walk-on step 1). */
    private boolean settingUpEntry(int unitId) {
        GpuBoardSource.WalkOnEntry entry = walkOnEntry();
        return entry.stage() == GpuBoardSource.EntryStage.SETTING_UP && entry.unitId() == unitId;
    }

    /** Where the walk-on unit moving now stands in its entry, NONE without a source that knows. */
    private GpuBoardSource.WalkOnEntry walkOnEntry() {
        GpuBoardSource.WalkOnEntry entry = source == null ? null : source.walkOnEntry();
        return entry == null ? GpuBoardSource.WalkOnEntry.NONE : entry;
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
        // The Tuning panel and the bot sheet stay open beside play; the other dialogs cover the board.
        boolean dialog = state.dialog != GpuHudState.Dialog.NONE && state.dialog != GpuHudState.Dialog.TUNING
              && state.dialog != GpuHudState.Dialog.BOTS;
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
        headCell.setActor(head).padBottom(7);
        head.clearChildren();
        head.add(title).left().minWidth(0);
        head.add().expandX();
        head.add(span).right().minWidth(0);
        // Walk-on step 2: Setup takes the unit back to setting up its entry; showPlan fills the cell.
        entrySetupCell = head.add((UiButton) null).padLeft(0);
        // A unit that converts (a LAM, a QuadVee, a Mek with tracks) chooses its mode in the head, before it moves,
        // at the head's right edge, in line with More below.
        forms.clear();
        formTips.clear();
        for (int i = 0; i < shape.forms().size(); i++) {
            UiButton button = button("hud-mini", null, UiTheme.upper(shape.forms().get(i)), null, "dock-form-" + i);
            int index = i;
            onChange(button, () -> convert(index));
            forms.add(button);
            formTips.add(ui.tip(button));
            head.add(button).padLeft(i == 0 ? 10 : SEGMENT);
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
                // The gaits as one segmented control, the elevation stepper, then the context slot at a fixed width.
                // The unit turns on the board (its arrows, a drag) or from More.
                boolean first = true;
                for (int i = 0; i < MODES.size(); i++) {
                    GpuMovePlan.Mode mode = MODES.get(i);
                    if ((mode != GpuMovePlan.Mode.BACK || shape.back())
                          && (mode != GpuMovePlan.Mode.RUN || shape.run())
                          && (mode != GpuMovePlan.Mode.JUMP || shape.jump())) {
                        row1.add(modes.get(i)).growX().uniformX().minWidth(0).height(TALL)
                              .padLeft(first ? GAP : SEGMENT);
                        first = false;
                    }
                }
                if (shape.elevation()) {
                    // A compact spinner: the elevation, then Go up over Go down.
                    row1.add(elevation).minWidth(ELEVATION).height(TALL);
                    Table spinner = new Table();
                    spinner.add(goUp).width(SPINNER).height((TALL - SEGMENT) / 2).row();
                    spinner.add(goDown).width(SPINNER).height((TALL - SEGMENT) / 2).padTop(SEGMENT);
                    row1.add(spinner).width(SPINNER).height(TALL).padLeft(SEGMENT);
                }
                if (shape.context()) {
                    row1.add(context).width(CONTEXT).height(TALL);
                }
                row2.add(undo).width(SQUARE);
            }
            case FLIGHT -> {
                // Decelerate and Accelerate around the velocity, then the altitude stepper; Turn is in More.
                row1.add(decelerate).growX().uniformX().minWidth(0).height(TALL);
                row1.add(velocity).minWidth(VELOCITY).height(TALL);
                row1.add(accelerate).growX().uniformX().minWidth(0).height(TALL);
                if (shape.elevation()) {
                    row1.add(elevation).minWidth(ELEVATION).height(TALL);
                    Table spinner = new Table();
                    spinner.add(goUp).width(SPINNER).height((TALL - SEGMENT) / 2).row();
                    spinner.add(goDown).width(SPINNER).height((TALL - SEGMENT) / 2).padTop(SEGMENT);
                    row1.add(spinner).width(SPINNER).height(TALL).padLeft(SEGMENT);
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
                // The play button takes the rest (.b.wide: flex 1); the speeds live in the utility row (the user's
                // decision of 2026-11-14).
                row1.add(play).growX().height(PLAIN);
                row1.add(next).width(SQUARE).height(PLAIN);
                row1.add(counter).minWidth(0).height(PLAIN).padLeft(GAP + 6).padRight(6);
                row2.add(replay).minWidth(0);
            }
            case WAITING, STATUS -> row2.add(waiting).growX().minWidth(0);
            case BOT_ORDERS -> {
                // The bot's actions; they go out at once. Move to and Add waypoint start a pick on the board.
                for (GpuBotDock.Action action : botRow()) {
                    UiButton button = button("hud", null, action.label(), null, "dock-bot-" + action.id());
                    onChange(button, () -> botAction(action.id()));
                    choices.add(button);
                    row1.add(button).growX().uniformX().minWidth(0).height(PLAIN);
                }
            }
            case ENTRY, GENERIC -> {
                // The placement row, deployment's and walk-on step 1's alike (battle HUD layout, zone 2): the height
                // stepper (lower, the height, higher) among the heights the unit could deploy at there, then its
                // starting setup, a tile per choice, the rest in More. Beside setup tiles it is as high as they are.
                // Another phase's generic row holds its first commands.
                float rowHeight = shape.setup().isEmpty() ? PLAIN : SETUP_TILE;
                if (shape.heights()) {
                    row1.add(heightDown).width(SQUARE).height(rowHeight);
                    row1.add(height).growX().minWidth(0).height(rowHeight);
                    row1.add(heightUp).width(SQUARE).height(rowHeight);
                }
                addSetupTiles(shape.setup(), setupRoom(shape.heights()));
                for (BoardScene.Command command : shape.variant() == Variant.GENERIC ? rowCommands()
                      : List.<BoardScene.Command>of()) {
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
        // Without an option row (no adjacent enemy), or a placement or generic row with nothing in it, More closes the
        // action row.
        boolean moreInRow2 = shape.variant() == Variant.NO_PHYSICAL
              || (shape.variant() == Variant.GENERIC || shape.variant() == Variant.ENTRY) && shape.buttons().isEmpty()
              && !shape.heights() && shape.setup().isEmpty();
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
        } else if (shape.variant() == Variant.FLIGHT) {
            footRight.add(clearRoute).padLeft(8);
        } else if (shape.variant() == Variant.PLAYBACK) {
            footRight.add(follow).padLeft(8);
        }
        if (shape.steps()) {
            footRight.add(steps).padLeft(8);
        }
        foot.add(footRight);
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

    /** The local movement plan (A.7 G2, G9-G16): modes, undo, confirm, hold and the route's foot line. */
    private void showPlan() {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        GpuBattleStatus.UnitStatus unit = state.presented(move.entityId());
        boolean route = !move.route().isEmpty();
        head(text("GpuBoard.hud.dock.movementPlan", name(unit)), false);
        // A walk-on unit enters first: MegaMek's click on an entry hex places it, then the gaits plan.
        boolean entering = move.traits().entering();
        // A walk-on unit that entered names its entry hex, and goes back to setting it up with Setup.
        boolean entered = walkOnEntry().stage() == GpuBoardSource.EntryStage.ENTERED
              && walkOnEntry().unitId() == move.entityId();
        span(entering ? walkOnPrompt(unit) : entered ? entryHex(unit) : "",
              entering ? UiTheme.AMBER : UiTheme.MUTED);
        if ((entrySetupCell.getActor() != null) != entered) {
            entrySetupCell.setActor(entered ? entrySetup : null).padLeft(entered ? 8 : 0);
        }
        GpuMovePlan.Band band = route ? move.route().getLast().band() : null;
        BoardScene.Command jump = command(JUMP);
        boolean vehicle = move.traits().vehicle();
        for (int i = 0; i < MODES.size(); i++) {
            GpuMovePlan.Mode mode = MODES.get(i);
            UiButton button = modes.get(i);
            button.setText(UiTheme.upper(modeName(mode, vehicle)));
            String mp = unit == null ? "" : switch (mode) {
                case WALK, BACK -> String.valueOf(move.traits().walk() >= 0 ? move.traits().walk() : unit.walk());
                case RUN -> unit.run();
                default -> String.valueOf(unit.jump());
            };
            button.details.getFirst().setText(text("GpuBoard.hud.dock.mp", mp));
            boolean explicit = move.explicit() && move.mode() == mode;
            button.pressed(explicit);
            // A gait MegaMek refuses now (a unit down cannot jump) is disabled in its place, unless it is chosen.
            button.setDisabled(entering || !explicit && switch (mode) {
                case JUMP -> unit == null || unit.jump() <= 0 || jump != null && !jump.enabled();
                case BACK -> !enabled(command(BACK_UP));
                default -> false;
            });
            // The route's last band is outlined (plan G2); Run covers boosted running and sprinting.
            boolean auto = !move.explicit() && band != null && switch (mode) {
                case WALK -> band == GpuMovePlan.Band.WALK;
                case BACK -> band == GpuMovePlan.Band.BACK;
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
                tip.getActor().setText(i < MODE_KEYS.size() ? text("GpuBoard.hud.dock.modeAutoTip",
                      GpuHintLine.key(inputs.preferences(), MODE_KEYS.get(i)))
                      : text("GpuBoard.hud.dock.modeAutoTipNoKey"));
                button.addListener(tip);
            } else if (button.removeListener(tip)) {
                tip.hide();
            }
        }
        undo.setDisabled(!move.canUndo());
        showForms(move, entering);
        if (shown.elevation()) {
            showElevation(move);
        }
        if (shown.context()) {
            showContext();
        }
        main(text("GpuBoard.hud.dock.confirmMove"), (route || move.traits().converting()) && enabled(done()));
        secondary(text("GpuBoard.hud.dock.holdPosition"), enabled(command(holdId())));
        waypoint.setDisabled(!move.canPin());
        clearRoute.setDisabled(!route && !move.walkedOn());
        clearRouteTip.getActor().setText(text(route ? "GpuBoard.hud.dock.clearRoute"
              : entered ? "GpuBoard.hud.move.entry.setupTip"
              : move.walkedOn() ? "GpuBoard.hud.dock.withdrawWalkOn" : "GpuBoard.hud.dock.clearRoute"));
        holdAll.setDisabled(!enabled(skip()) || move.holdingRemaining() > 0);
        if (open == Confirm.HOLD_ALL) {
            confirmation.text(text("GpuBoard.hud.dock.holdAllTitle"), text("GpuBoard.hud.dock.holdAllConfirm"),
                  text("GpuBoard.hud.dock.holdAllButton"), text("Cancel"));
        }
        // The plan's facing: the route's last, or the unit's own without a route (E2b).
        String facing = GpuHudKit.facing(move.facing());
        legendWalk.setText(modeName(GpuMovePlan.Mode.WALK, vehicle));
        legendRun.setText(modeName(GpuMovePlan.Mode.RUN, vehicle));
        boolean runs = move.traits().runs();
        legendRunStrokeCell.setActor(runs ? legendRunStroke : null).padRight(runs ? 5 : 0);
        legendRunCell.setActor(runs ? legendRun : null).padRight(runs ? 10 : 0);
        if (route) {
            String type = move.auto() ? text("GpuBoard.hud.dock.modeAuto", move.typeLabel()) : move.typeLabel();
            // A unit without heat (a vehicle) has no heat to show.
            String rest = unit != null && unit.heatCapacity().isEmpty()
                  ? text("GpuBoard.hud.dock.moveFootNoHeat", type, facing)
                  : text("GpuBoard.hud.dock.moveFoot", type, facing, GpuHudKit.signed(move.heat()));
            foot(text("GpuBoard.hud.dock.moveCost", move.cost(), move.budget()), SEPARATOR + rest);
        } else {
            // The explicit mode's own word (E2b gearLabel): "walk", "run", "jump" or "Walk backwards".
            legendText.setText(move.explicit() ? text("GpuBoard.hud.dock.legendOnly", move.gearLabel(), facing)
                  : text("GpuBoard.hud.dock.legendAuto", facing));
        }
    }

    /**
     * Walk-on step 1 (Core Rulebook p. 224): the unit picks its entry hex, then turns, changes its height, mode and
     * setup, all free; Enter settles them and the plan takes over. Clear takes it back off its entry hex.
     */
    private void showEntry() {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        GpuBattleStatus.UnitStatus unit = state.presented(move.entityId());
        boolean placed = walkOnEntry().placed();
        String edge = unit == null || !unit.walksOn() ? ""
              : text("GpuBoard.hud.move.entry.from", IStartingPositions.getDisplayName(unit.walkOnZone()));
        // Enter follows Done: a setup that makes the entry step illegal can't enter.
        showPlacement(new Placement(unit, false, placed, placed ? edge : walkOnPrompt(unit),
              text("GpuBoard.hud.move.entry.enter"),
              placed && enabled(command(holdId())), text("GpuBoard.hud.move.entry.clear"), placed,
              text("GpuBoard.hud.move.entry.then"), placed ? text("GpuBoard.hud.move.entry.footReady",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE)) : text("GpuBoard.hud.move.entry.footPick")));
    }

    /**
     * What a placement says, deployment's or walk-on step 1's: the unit, whether it deploys (else enters), whether it
     * stands on the board yet, the head's span (a prompt in amber until placed), the main button and whether it is ready, Clear and whether it clears, what follows the
     * main button once placed (empty for nothing) and the foot.
     */
    private record Placement(GpuBattleStatus.UnitStatus unit, boolean deploying, boolean placed, String span,
          String main, boolean ready, String clear, boolean clears, String then, String foot) { }

    /**
     * The placement's dock, deployment's and walk-on step 1's alike: the head ("ATLAS · Deploy", "ATLAS · Enter") and
     * its span, the height stepper, the start forms and the setup tiles; the main button and Clear; the placed hex, its facing and the setup's changes under the main button, then
     * what follows; the setup's consequences in amber in the foot, else how to go on.
     */
    private void showPlacement(Placement placement) {
        headCell.setActor(head).padBottom(7);
        head(text(placement.deploying() ? "GpuBoard.hud.deploy.head" : "GpuBoard.hud.move.entry.head",
              name(placement.unit())), false);
        span(placement.span(), placement.placed() ? UiTheme.MUTED : UiTheme.AMBER);
        showHeight(placement.deploying());
        showStartForms(startForms());
        GpuDeploySetup.Choice setup = shown.setup().isEmpty() ? null : setupChoice();
        showSetup(setup);
        main(placement.main(), placement.ready());
        secondary(placement.clear(), placement.clears());
        String line = deployLine(placement.unit(), setup);
        String then = placement.placed() ? placement.then() : "";
        mainLine(then.isEmpty() ? line : line.isEmpty() ? then : line + SEPARATOR + then);
        if (setup != null && !setup.consequences().isEmpty()) {
            // What the setup costs the unit, such as heat each turn, in amber.
            foot("", "\u25B2 " + String.join(SEPARATOR, setup.consequences()));
            footText.getStyle().fontColor = UiTheme.AMBER;
        } else {
            foot("", placement.foot());
        }
    }

    /**
     * An airborne aerospace unit's flight (TW aerospace movement): MegaMek plots the path from board clicks, and the
     * dock sets its velocity, altitude and confirmation (its turns in More). A walk-on unit enters first, as in the plan.
     */
    private void showFlight() {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        GpuMovePlan.Flight flight = move.traits().flight();
        GpuBattleStatus.UnitStatus unit = state.presented(move.entityId());
        boolean entering = move.traits().entering();
        head(text("GpuBoard.hud.dock.flightPlan", name(unit)), false);
        span(entering ? walkOnPrompt(unit) : flightHint(flight),
              entering ? UiTheme.AMBER : UiTheme.MUTED);
        flightStep(decelerate, decelerateTip, command(DECELERATE), entering, move.canUndo());
        flightStep(accelerate, accelerateTip, command(ACCELERATE), entering, move.canUndo());
        velocityValue.setText(flight.start() == flight.velocity() ? String.valueOf(flight.velocity())
              : flight.start() + "\u2192" + flight.velocity());
        undo.setDisabled(!move.canUndo());
        showForms(move, entering);
        if (shown.elevation()) {
            showElevation(move);
        }
        main(text("GpuBoard.hud.dock.confirmMove"), !entering && enabled(done()));
        secondary(text("GpuBoard.hud.dock.holdPosition"), enabled(skip()));
        clearRoute.setDisabled(!move.canUndo() && !move.walkedOn());
        clearRouteTip.getActor().setText(text(!move.canUndo() && move.walkedOn()
              ? "GpuBoard.hud.dock.withdrawWalkOn" : "GpuBoard.hud.dock.clearRoute"));
        foot(text("GpuBoard.hud.dock.flightCost", flight.thrust(), flight.safe()),
              SEPARATOR + text("GpuBoard.hud.dock.flightFoot", flight.max(), GpuHudKit.facing(move.facing())));
    }

    /**
     * What the flight needs next: velocity to fly at all, then the hexes it still has to fly (TW: a fighter flies its
     * whole velocity), then only the confirmation.
     */
    private static String flightHint(GpuMovePlan.Flight flight) {
        if (flight.velocity() == 0) {
            return text("GpuBoard.hud.dock.flightAccelerate");
        }
        return flight.togo() > 0 ? text("GpuBoard.hud.dock.flightToGo", flight.togo())
              : text("GpuBoard.hud.dock.flightFlown");
    }

    /**
     * A flight button: MegaMek's command, its label in the tooltip; disabled while the unit has not entered. Velocity
     * changes come before any other step (TW), so once the path has one, the tooltip says why it is disabled.
     */
    private static void flightStep(UiButton button, TextTooltip tip, BoardScene.Command command, boolean entering,
          boolean moved) {
        step(button, tip, command);
        button.setDisabled(entering || button.isDisabled());
        if (command != null && !entering && moved && button.isDisabled()) {
            tip.getActor().setText(text("GpuBoard.hud.dock.velocityFirst", command.label()));
        }
    }

    /**
     * The context slot: the command's glyph over its short caption, MegaMek's full label in the tooltip. A command for
     * the unit's situation is outlined as the route's gait is, so what the unit needs stands out.
     */
    private void showContext() {
        BoardScene.Command command = contextCommand();
        if (command == null) {
            return;
        }
        boolean getUp = command.id().equals(GpuMoveContext.GET_UP);
        ((Image) context.icons.getFirst()).setDrawable(ui.skin.getDrawable("icon-"
              + GpuMoveContext.icon(command.id())));
        context.details.getFirst().setText(GpuMoveContext.caption(command));
        context.setDisabled(!command.enabled());
        TextButton.TextButtonStyle style = GpuMoveContext.pressing(command) ? autoStyle : plainStyle;
        if (context.getStyle() != style) {
            context.setStyle(style);
        }
        contextTip.getActor().setText(getUp ? text("GpuBoard.hud.dock.standUpTip")
              : command.enabled() ? command.label() : text("GpuBoard.hud.dock.unavailable"));
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
        waitingLabel.setText(UiTheme.upper(text("GpuBoard.hud.dock.waitingForOpponent")));
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        foot(fire.active() && !fire.editable() ? text("GpuBoard.hud.dock.weaponAttacks",
              name(state.presented(fire.actorId())), fire.attacks().size()) : "", "");
    }

    /**
     * Unit commands held (STATUS): the waiting box says why. While a move plays, the head names its player and the
     * span its unit; with the selection cleared in the local turn, the box asks for a unit; under the forces overview
     * or other playback, the head is the turn's, as the waiting box shows it.
     */
    private void showStatus() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuBattleStatus.UnitStatus mover = state.playbackBusy() ? state.presented(state.history.moverId()) : null;
        GpuBattleStatus.Slot slot = current();
        String box;
        if (mover != null) {
            head(mover.side() == GpuBattleStatus.Side.OWN ? text("GpuBoard.hud.dock.youMoving")
                  : text("GpuBoard.hud.dock.opponentMoving", playerName(status, mover.ownerId())),
                  mover.side() == GpuBattleStatus.Side.ENEMY);
            span(name(mover), UiTheme.MINT);
            box = "GpuBoard.hud.dock.resolvingMove";
        } else if (!status.myTurn() && slot != null) {
            head(status.phase().isMovement() ? text("GpuBoard.hud.dock.opponentMoving", slot.playerName())
                  : text("GpuBoard.hud.phase.opponentTurn", slot.playerName()),
                  slot.side() == GpuBattleStatus.Side.ENEMY);
            span(name(state.presented(slot.entityId())), UiTheme.MINT);
            box = state.playbackBusy() ? "GpuBoard.hud.dock.resolving" : "GpuBoard.hud.dock.waitingForOpponent";
        } else {
            head(text(status.myTurn() ? "GpuBoard.hud.phase.yourTurn" : "GpuBoard.hud.dock.resolving"), false);
            span("", UiTheme.MINT);
            box = state.playbackBusy() || !status.myTurn() ? "GpuBoard.hud.dock.resolving"
                  : state.overview ? "GpuBoard.hud.dock.closeOverview" : "GpuBoard.hud.dock.selectUnit";
        }
        waitingLabel.setText(UiTheme.upper(text(box)));
        foot("", "");
    }

    /**
     * Orders for the selected allied bot lance or unit (BOT_ORDERS): the head names it, the option row holds the bot's
     * actions, Move to and Add waypoint pick hexes on the board, and the foot says which units an order applies to.
     */
    private void showBotOrders() {
        GpuBotDock.Target target = botTarget();
        head(GpuBotDock.head(target, state), false);
        span(text("GpuBoard.hud.dock.bot.sent"), UiTheme.MUTED);
        List<GpuBotDock.Action> row = botRow();
        for (int i = 0; i < choices.size() && i < row.size(); i++) {
            choices.get(i).setText(UiTheme.upper(row.get(i).label()));
            choices.get(i).setDisabled(!row.get(i).enabled());
        }
        main(text("GpuBoard.hud.dock.bot.moveTo"), source != null);
        secondary(text("GpuBoard.hud.dock.bot.addWaypoint"), source != null);
        String note = GpuBotDock.note(target);
        foot(GpuBotDock.scope(target, state), note.isEmpty() ? "" : SEPARATOR + note);
    }

    /** The selected allied bot lance or inspected allied bot unit, or null. */
    private GpuBotDock.Target botTarget() {
        return GpuBotDock.target(state);
    }

    /** The bot's option row for the shown target, empty without one. */
    private List<GpuBotDock.Action> botRow() {
        GpuBotDock.Target target = botTarget();
        return target == null ? List.of() : GpuBotDock.row(target, source == null ? null : source.botOrders());
    }

    /** Runs the bot action with {@code id} as the target has it now, while it is enabled. */
    private void botAction(String id) {
        GpuBotDock.Target target = botTarget();
        GpuBotDock.Action action = target == null || source == null ? null
              : GpuBotDock.action(target, source.botOrders(), id);
        if (action != null && action.enabled()) {
            action.run().run();
        }
    }

    /** The name of the player with {@code playerId} as the turn order lists it, or "Opponent". */
    private static String playerName(GpuBattleStatus.Snapshot status, int playerId) {
        return status.turns().stream().filter(slot -> slot.playerId() == playerId).map(GpuBattleStatus.Slot::playerName)
              .filter(name -> !name.isBlank()).findFirst().orElse(text("GpuBoard.hud.dock.opponent"));
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
        BoardScene.Command done = done();
        BoardScene.Command clear = deployClear();
        if (deploying) {
            // Placing is local until Deploy (Done) sends the unit, so the foot names the confirmation once it can.
            boolean turns = actor == null || actor.turns();
            boolean ready = done != null && done.enabled();
            showPlacement(new Placement(actor, true, actor != null && actor.position() != null, "",
                  done == null ? "" : done.label(), ready, clear == null ? "" : clear.label(),
                  clear != null && clear.enabled(), "", ready
                  ? text(turns ? "GpuBoard.hud.deploy.footReady" : "GpuBoard.hud.deploy.footReadyFixed",
                        UiTheme.upper(done.label()), GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE))
                  : text(turns ? "GpuBoard.hud.deploy.footPlace" : "GpuBoard.hud.deploy.footPlaceFixed")));
            return;
        }
        head(headline, false);
        span(line, UiTheme.MINT);
        boolean headed = !headline.isEmpty() || !line.isEmpty() || !forms.isEmpty();
        if ((headCell.getActor() == head) != headed) {
            headCell.setActor(headed ? head : null).padBottom(headed ? 7 : 0);
        }
        List<BoardScene.Command> row = rowCommands();
        for (int i = 0; i < choices.size() && i < row.size(); i++) {
            choices.get(i).setText(UiTheme.upper(row.get(i).label()));
        }
        if (done != null) {
            main(phase.isReport() ? text("GpuBoard.hud.dock.continue") : done.label(), done.enabled());
        }
        BoardScene.Command skip = skip();
        if (clear != null) {
            secondary(clear.label(), clear.enabled());
        } else if (skip != null) {
            secondary(skip.label(), skip.enabled());
        }
        showStartForms(startForms());
        mainLine("");
        foot("", lines.length > 1 ? lines[1].strip() : "");
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
        if (variant == Variant.PLAN && panels.move().route().isEmpty() && !panels.move().traits().converting()) {
            source.toasts().post(ToastLevel.INFO, text("GpuBoard.hud.move.plotFirst"));
            return;
        }
        if (main.isDisabled()) {
            return;
        }
        switch (variant) {
            case INITIATIVE, PLAN, FLIGHT, GENERIC -> run(panels.phase().doneId());
            case ENTRY -> source.setEntered(panels.move().entityId(), true);
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

    /** The main button: BOT_ORDERS starts Move to's pick, which the DONE key does not. */
    private void mainButton() {
        if (variant == Variant.BOT_ORDERS) {
            GpuBotDock.Target target = botTarget();
            if (target != null && source != null) {
                GpuBotDock.moveTo(target, source.botOrders(), state);
            }
        } else {
            main();
        }
    }

    /** The action row's secondary button: reroll, hold position, no attack, deployment's Clear or MegaMek's skip. */
    private void secondary() {
        switch (variant) {
            case INITIATIVE -> run(REROLL);
            case ENTRY -> source.moves().clearOrWithdraw();
            case GENERIC -> run(deployClear() != null ? DEPLOY_CLEAR : inputs.frame().panels().phase().skipId());
            case PLAN -> run(holdId());
            case FLIGHT -> run(inputs.frame().panels().phase().skipId());
            case PHYSICAL -> run(noAction());
            case BOT_ORDERS -> {
                GpuBotDock.Target target = botTarget();
                if (target != null && source != null) {
                    GpuBotDock.addWaypoints(target, source.botOrders(), state);
                }
            }
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

    /** Esc's step in the player's deployment turn: takes the placed unit back off the board, as Clear does. */
    boolean clearPlacement() {
        BoardScene.Command clear = deployClear();
        if (!enabled(clear)) {
            return false;
        }
        run(clear);
        return true;
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
     * physical phase's other commands, or the phase commands beyond the generic row (C.2); a plan, flight or entry leads with
     * Turn left and Turn right. Firing and physical turns
     * also offer the unit's record (unit panel design 2.2).
     */
    More more() {
        if (inputs == null || !hasMore()) {
            return null;
        }
        List<BoardScene.Command> items = new ArrayList<>();
        More more = switch (variant) {
            case PLAN, FLIGHT -> {
                items.addAll(turnItems());
                items.addAll(others(moveShown()).map(GpuCommandDock::entry).toList());
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
            // The turns, and the setup tiles the option row has no room for, as the same tiles.
            case ENTRY -> new More(text("GpuBoard.hud.common.more"),
                  name(state.presented(inputs.frame().panels().move().entityId())), turnItems(), List.of(),
                  setupOverflow());
            case BOT_ORDERS -> {
                GpuBotDock.Target target = botTarget();
                yield new More(text("GpuBoard.hud.dock.bot.more", GpuBotDock.name(target, state)), null,
                      GpuBotDock.more(target, source.botOrders()), List.of());
            }
            default -> {
                List<BoardScene.Command> row = rowCommands();
                items.addAll(turnItems());
                yield new More(text("GpuBoard.hud.common.more"), inputs.frame().status().phase().localizedName(),
                      items, others(deployingActor() < 0 ? Set.of() : DEPLOY_SHOWN)
                      .filter(command -> !row.contains(command)).map(GpuCommandDock::entry).toList(),
                      setupOverflow());
            }
        };
        // Without a command or a setup choice to use now, the dock shows no More button.
        return GpuMoreDrawer.usable(more).isEmpty() && more.setup().isEmpty() ? null : more;
    }

    /**
     * Whether the shown variant offers More: movement and weapon declaration always; the turn without an adjacent
     * enemy only while one of MegaMek's other physical commands (dodge, explosives, clear woods) can be used.
     */
    private boolean hasMore() {
        return switch (variant) {
            case PLAN, FLIGHT, FIRE -> true;
            case PHYSICAL -> inputs.frame().panels().physical().options().size() > rowOptions().size()
                  || others(PHYSICAL_SHOWN).findAny().isPresent();
            case NO_PHYSICAL -> others(PHYSICAL_SHOWN).anyMatch(BoardScene.Command::enabled);
            case GENERIC -> others(Set.of()).count() > rowCommands().size() || !setupOverflow().isEmpty()
                  || !turnItems().isEmpty();
            case ENTRY -> inputs.frame().panels().move().facingMatters() || !setupOverflow().isEmpty();
            case BOT_ORDERS -> source != null && botTarget() != null && botTarget().caspar();
            default -> false;
        };
    }

    /**
     * Turn left and Turn right, one hexside each, as the unit's arrows on the board and a drag turn it: the plan's and
     * walk-on step 1's while facing matters (once the unit stands on its entry hex), a flight's with MegaMek's Turn,
     * and a deploying unit's that turns once it is placed.
     */
    private List<BoardScene.Command> turnItems() {
        int deploying = deployingActor();
        if (deploying >= 0) {
            GpuBattleStatus.UnitStatus actor = state.presented(deploying);
            if (actor == null || !actor.turns()) {
                return List.of();
            }
            boolean placed = actor.position() != null;
            return List.of(turnItem(-1, placed, () -> source.turnPlaced(deploying, -1)),
                  turnItem(1, placed, () -> source.turnPlaced(deploying, 1)));
        }
        if (variant == Variant.GENERIC) {
            return List.of();
        }
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        boolean shown;
        boolean usable;
        if (variant == Variant.FLIGHT) {
            BoardScene.Command turn = command(TURN);
            shown = turn != null;
            usable = !move.traits().entering() && enabled(turn);
        } else {
            shown = move.facingMatters();
            usable = variant == Variant.ENTRY ? walkOnEntry().placed() : !move.traits().entering();
        }
        if (!shown) {
            return List.of();
        }
        return List.of(turnItem(-1, usable, () -> source.moves().turn(-1)),
              turnItem(1, usable, () -> source.moves().turn(1)));
    }

    private BoardScene.Command turnItem(int direction, boolean usable, Runnable turn) {
        boolean left = direction < 0;
        return item(left ? "dock.turnLeft" : "dock.turnRight", text(left ? "GpuBoard.hud.dock.turnLeft"
              : "GpuBoard.hud.dock.turnRight"), usable ? GpuHintLine.key(inputs.preferences(), left
              ? KeyCommandBind.TURN_LEFT : KeyCommandBind.TURN_RIGHT) : text("GpuBoard.hud.dock.unavailable"),
              usable, turn);
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
     * out of the game) stays in More. A unit with a starting setup shows its setup tiles instead, its commands in More.
     */
    private List<BoardScene.Command> rowCommands() {
        boolean deployment = inputs.frame().status().phase().isDeployment();
        if (deployment && setupChoice() != null) {
            return List.of();
        }
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

    /** Hold position's command: the skip, or Done for a walked-on unit without a route (PhaseInfo.holdId). */
    private String holdId() {
        GpuHudData panels = inputs.frame().panels();
        return panels.phase().holdId(panels.move());
    }

    /** Deployment's Clear (MegaMek's "clear deployment"), the secondary button in the player's own turn, or null. */
    private BoardScene.Command deployClear() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        return status.phase().isDeployment() && status.myTurn() ? command(DEPLOY_CLEAR) : null;
    }

    /** The unit the player deploys now, or -1. */
    private int deployingActor() {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        return status.phase().isDeployment() && status.myTurn() ? status.actorId() : -1;
    }

    /** The unit whose height the stepper places: the deploying one, or the walk-on unit setting up its entry. */
    private int placingActor() {
        return inputs == null ? -1 : variant == Variant.ENTRY ? inputs.frame().panels().move().entityId()
              : deployingActor();
    }

    /** The height stepper's read-out and tips, a deployment's or a walk-on entry's, while the shape has it. */
    private void showHeight(boolean deploying) {
        GpuBoardSource.HeightChoice choice = shown.heights() ? deployHeight.choice(placingActor()) : null;
        if (choice == null) {
            return;
        }
        height.setText(UiTheme.upper(deployHeight.label(choice)));
        String prefix = deploying ? "GpuBoard.hud.deploy." : "GpuBoard.hud.move.entry.";
        heightDownTip.getActor().setText(text(prefix + "heightDown"));
        heightTip.getActor().setText(text(prefix + "heightTip"));
        heightUpTip.getActor().setText(text(prefix + "heightUp"));
    }

    // ------------------------------------------------------------------ deployment setup

    /**
     * The acting unit's starting setup (Core Rulebook p. 224) while it has choices, or null: the deploying unit's, or
     * the walk-on unit's while it sets up its entry.
     */
    private GpuDeploySetup.Choice setupChoice() {
        GpuDeploySetup.Choice choice = source == null || inputs == null ? null : source.setupChoice();
        int actor = placingActor();
        return choice != null && actor >= 0 && choice.unitId() == actor && !choice.tiles().isEmpty() ? choice : null;
    }

    /** How many setup tiles the option row has room for, beside the height stepper or not. */
    private static int setupRoom(boolean heights) {
        return heights ? SETUP_TILES - 2 : SETUP_TILES;
    }

    /** Adds the option row's setup tiles, at most {@code room}; More lists the rest. */
    private void addSetupTiles(List<String> ids, int room) {
        setupTiles.clear();
        for (int i = 0; i < ids.size() && i < room; i++) {
            String id = ids.get(i);
            GpuSetupTile tile = new GpuSetupTile(ui, "dock-setup-" + id, face -> chooseSetup(id, face));
            setupTiles.put(id, tile);
            row1.add(tile.button()).growX().uniformX().minWidth(0).height(GpuSetupTile.HEIGHT);
        }
    }

    /** The setup tiles beyond the option row's room, which More lists. */
    private List<GpuDeploySetup.Tile> setupOverflow() {
        GpuDeploySetup.Choice choice = variant == Variant.GENERIC || variant == Variant.ENTRY ? setupChoice() : null;
        if (choice == null) {
            return List.of();
        }
        int room = setupRoom(deployHeight.choice(placingActor()) != null);
        return choice.tiles().size() > room ? choice.tiles().subList(room, choice.tiles().size()) : List.of();
    }

    /** The tiles' captions, states and marks; a changed tile is framed in amber with an amber value. */
    private void showSetup(GpuDeploySetup.Choice choice) {
        setupTiles.forEach((id, shown) -> {
            GpuDeploySetup.Tile tile = choice == null ? null : choice.tile(id);
            if (tile != null) {
                shown.show(tile);
            }
        });
    }

    /** A tile's click, in the option row or in More: a switch flips, a list or a count's slider opens above it. */
    private void chooseSetup(String id, Actor face) {
        GpuDeploySetup.Choice choice = setupChoice();
        GpuDeploySetup.Tile tile = choice == null ? null : choice.tile(id);
        if (tile == null || tile.isLocked()) {
            return;
        }
        int unit = choice.unitId();
        String detail = tile.tip().isEmpty() ? null : tile.tip();
        switch (tile.kind()) {
            case TOGGLE -> source.setSetup(unit, id, tile.on() ? tile.off() : 1 - tile.off());
            case PICK -> menu.choices(face, tile.caption(), detail, tile.values(), tile.selected(),
                  index -> source.setSetup(unit, id, index));
            case STEP -> menu.slider(face, tile.caption(), detail, tile.selected(), tile.values().size() - 1,
                  count -> source.setSetup(unit, id, count));
        }
    }


    /** Deploy's sub-line: the hex and, when it means anything, facing the unit is placed at, and the setup changes. */
    private static String deployLine(GpuBattleStatus.UnitStatus actor, GpuDeploySetup.Choice setup) {
        List<String> parts = new ArrayList<>();
        if (actor != null && actor.position() != null) {
            parts.add(actor.position().getBoardNum());
            String facing = actor.turns() ? GpuHudKit.facing(actor.facing()) : "";
            if (!facing.isEmpty()) {
                parts.add(facing);
            }
        }
        if (setup != null && setup.changes() > 0) {
            parts.add(GpuDeploySetup.text("changeCount", setup.changes()));
        }
        return String.join(SEPARATOR, parts);
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

    /** The context slot's command in the movement plan (GpuMoveContext), or null. */
    private BoardScene.Command contextCommand() {
        return variant == Variant.PLAN ? GpuMoveContext.pick(commands()) : null;
    }

    /**
     * The head's mode switch: the planned mode pressed, a mode the unit can't take now (it has moved, or a damaged LAM)
     * disabled.
     */
    private void showForms(GpuMovePlan.Snapshot move, boolean entering) {
        GpuBoardSource.FormChoice starting = startForms();
        if (starting != null) {
            showStartForms(starting);
            return;
        }
        List<GpuMovePlan.Form> planned = move.traits().forms();
        for (int i = 0; i < forms.size() && i < planned.size(); i++) {
            GpuMovePlan.Form form = planned.get(i);
            UiButton button = forms.get(i);
            button.pressed(form.planned());
            boolean disabled = entering || !form.enabled() && !form.planned();
            button.setDisabled(disabled);
            String tip = disabled ? (form.why() != null ? form.why()
                  : text(entering ? "GpuBoard.hud.dock.form.entering" : "GpuBoard.hud.dock.form.moved"))
                  : form.planned() ? null
                  : form.cost() > 0 ? text("GpuBoard.hud.dock.form.convertCost", form.label(), form.cost())
                  : text("GpuBoard.hud.dock.form.convert", form.label());
            TextTooltip formTip = i < formTips.size() ? formTips.get(i) : null;
            if (formTip == null) {
                continue;
            }
            if (tip != null) {
                formTip.getActor().setText(tip);
                if (!button.getListeners().contains(formTip, true)) {
                    button.addListener(formTip);
                }
            } else if (button.removeListener(formTip)) {
                formTip.hide();
            }
        }
    }

    /**
     * The acting unit's starting mode choice (a LAM's, a QuadVee's): while it deploys, or walks on until its first
     * step; null while it has none.
     */
    private GpuBoardSource.FormChoice startForms() {
        GpuBoardSource.FormChoice choice = source == null ? null : source.formChoice();
        GpuBattleStatus.Snapshot status = inputs == null ? null : inputs.frame().status();
        boolean choosing = status != null && status.myTurn()
              && (status.phase().isDeployment() || status.phase().isMovement());
        return choice != null && choosing && choice.unitId() == status.actorId() && choice.modes().size() > 1
              ? choice : null;
    }

    /**
     * The head's mode switch while the unit chooses the mode it starts in: that mode pressed, each other one a click
     * away, free.
     */
    private void showStartForms(GpuBoardSource.FormChoice choice) {
        boolean entering = inputs.frame().status().phase().isMovement();
        for (int i = 0; i < forms.size(); i++) {
            UiButton button = forms.get(i);
            boolean current = choice != null && i < choice.modes().size() && choice.modes().get(i) == choice.mode();
            button.pressed(current);
            button.setDisabled(choice == null);
            TextTooltip formTip = i < formTips.size() ? formTips.get(i) : null;
            if (formTip == null) {
                continue;
            }
            if (!current && choice != null && i < choice.labels().size()) {
                formTip.getActor().setText(text(entering ? "GpuBoard.hud.move.entry.form" : "GpuBoard.hud.deploy.form",
                      choice.labels().get(i)));
                if (!button.getListeners().contains(formTip, true)) {
                    button.addListener(formTip);
                }
            } else if (button.removeListener(formTip)) {
                formTip.hide();
            }
        }
    }

    /**
     * A mode of the head's switch: MegaMek converts to it, or back to the starting mode; while the unit chooses its
     * starting mode (deploying, or walking on before its first step), it starts in it instead.
     */
    private void convert(int index) {
        GpuBoardSource.FormChoice starting = startForms();
        if (starting != null) {
            if (index < starting.modes().size() && starting.modes().get(index) != starting.mode()) {
                source.setStartingMode(starting.unitId(), starting.modes().get(index));
            }
            return;
        }
        List<GpuMovePlan.Form> planned = inputs == null ? List.of() : inputs.frame().panels().move().traits().forms();
        if (index < planned.size() && !planned.get(index).planned()) {
            source.moves().convert(planned.get(index).command());
        }
    }

    /** The movement commands the dock shows: its own controls' and the context slot's. */
    private Set<String> moveShown() {
        Set<String> shown = new HashSet<>(MOVE_SHOWN);
        BoardScene.Command shownContext = contextCommand();
        if (shownContext != null) {
            shown.add(shownContext.id());
        }
        if ((variant == Variant.PLAN || variant == Variant.FLIGHT)
              && !inputs.frame().panels().move().traits().forms().isEmpty()) {
            shown.add(MoveCommand.MOVE_MODE_CONVERT.getCmd());
        }
        if ((variant == Variant.PLAN || variant == Variant.FLIGHT) && elevationShown()) {
            shown.add(GO_UP);
            shown.add(GO_DOWN);
        }
        // A walk-on unit setting up its entry chooses its height with the stepper, among those it could deploy at.
        if (variant == Variant.ENTRY) {
            shown.add(GO_UP);
            shown.add(GO_DOWN);
        }
        if (variant == Variant.FLIGHT) {
            shown.add(ACCELERATE);
            shown.add(DECELERATE);
        }
        return shown;
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

    /** The walk-on prompt: the edge the unit enters from, when the battle status knows it. */
    private static String walkOnPrompt(GpuBattleStatus.UnitStatus unit) {
        return unit == null || !unit.walksOn() ? text("GpuBoard.hud.dock.walkOnPick")
              : text("GpuBoard.hud.dock.walkOnPickEdge", IStartingPositions.getDisplayName(unit.walkOnZone()));
    }

    /** Walk-on step 2's span: the hex and facing the unit entered at. */
    private static String entryHex(GpuBattleStatus.UnitStatus unit) {
        if (unit == null || unit.position() == null) {
            return "";
        }
        String facing = GpuHudKit.facing(unit.facing());
        return text("GpuBoard.hud.move.entry.entered", unit.position().getBoardNum()
              + (facing.isEmpty() ? "" : SEPARATOR + facing));
    }

    /** A gait's name: a vehicle cruises and flanks (TW) where a Mek walks and runs. */
    private static String modeName(GpuMovePlan.Mode mode, boolean vehicle) {
        return text(switch (mode) {
            case RUN -> vehicle ? "GpuBoard.hud.dock.flank" : "GpuBoard.hud.dock.run";
            case JUMP -> "GpuBoard.hud.dock.jump";
            case BACK -> vehicle ? "GpuBoard.hud.dock.backUp" : "GpuBoard.hud.dock.back";
            default -> vehicle ? "GpuBoard.hud.dock.cruise" : "GpuBoard.hud.dock.walk";
        });
    }

    /** The jump gait in the plan: the unit has the command and jump MP, or a booster, to use it. */
    private boolean jumpShown() {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        return command(JUMP) != null && move != null && move.traits().jumps();
    }

    /**
     * The elevation stepper in the plan: a unit that flies, glides or dives (VTOL, WiGE, submarine) and has the
     * commands. A Mek keeps Go up and Go down in the drawer, where they are rare.
     */
    private boolean elevationShown() {
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        return move != null && move.traits().elevates() && (command(GO_UP) != null || command(GO_DOWN) != null);
    }

    private void showElevation(GpuMovePlan.Snapshot move) {
        boolean flight = move.traits().flight() != null;
        String kind = move.traits().altitude() ? "altitude" : move.traits().dives() ? "depth" : "elevation";
        elevationCaption.setText(UiTheme.upper(text("GpuBoard.hud.dock." + kind + "Short")));
        elevationTip.getActor().setText(text("GpuBoard.hud.dock." + kind + "Tip"));
        // A height-0 aerospace unit or flier is landed, named as deployment's stepper names it.
        GpuDeployHeight.Kind heightKind = GpuDeployHeight.Kind.of(move.traits());
        int level = move.traits().shownLevel();
        elevationValue.setText(GpuDeployHeight.landed(heightKind, level)
              ? UiTheme.upper(GpuDeployHeight.label(heightKind, level)) : String.valueOf(level));
        // A walk-on unit enters at its deployment elevation, so the stepper waits for its entry hex.
        boolean entering = move.traits().entering() && !flight;
        step(goUp, goUpTip, command(GO_UP), entering);
        step(goDown, goDownTip, command(GO_DOWN), entering);
    }

    private static void step(UiButton button, TextTooltip tip, BoardScene.Command command) {
        step(button, tip, command, false);
    }

    private static void step(UiButton button, TextTooltip tip, BoardScene.Command command, boolean held) {
        button.setDisabled(held || !enabled(command));
        if (command != null) {
            tip.getActor().setText(command.label());
        }
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
        footText.getStyle().fontColor = UiTheme.MUTED;
    }

    /** The main button's sub-line; none closes it, so the caption stays centred. */
    private void mainLine(String text) {
        boolean wasShown = mainLine.getText().length() > 0;
        mainLine.setText(text);
        if (wasShown != !text.isEmpty()) {
            mainLineCell.height(text.isEmpty() ? Value.zero : Value.prefHeight).padTop(text.isEmpty() ? 0 : 1);
            main.invalidateHierarchy();
        }
    }
}
