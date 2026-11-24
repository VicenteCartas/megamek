/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;

/**
 * Move and Clear over the planned route's destination: a shortcut that repeats the dock's Confirm move and Clear route
 * where the player looks, as the deployment's pair does over a placed unit (battle HUD layout, rule 2). It stands on
 * the destination's head, where the route's ghost carries the unit's pose (the Tactical View's base ring north), and
 * the destination tip hangs beside and above it. Between Move and Clear, the dock's context command while it answers
 * the unit's situation (Stand up, Unjam…); a plain stance stays in the dock. Beside the ghost, turn arrows repeat the
 * dock's Left and Right, and their tips teach the drag that faces any hex; they never rise into the chip, at any zoom.
 * A unit that changes elevation (a VTOL) also gets a stepper on the head, under the chip, as deployment's height row
 * under its pair: Go down, its elevation, Go up. Before the unit has a route the same controls stand on the unit
 * itself, the chip offering Hold (the dock's Hold position: Done for a walked-on unit, whose entry MegaMek has yet to
 * commit) and, for a walked-on unit, Clear to take the walk-on back; on its own hex the controls stand above the
  * unit's tag. A walk-on unit setting up its entry (walk-on step 1) gets Enter instead of Hold, and its changed setup
  * as an amber tag above the chip; once it entered, Clear takes it back to that step. The controls show only in the
  * player's own movement turn while the destination (or the unit)
 * is on the shown board and on screen, and MegaMek's Done (Hold position before a route) is enabled. The pair keeps
 * the deployment chip's size, so it is not missed.
 */
final class GpuMoveConfirm implements GpuHud.Component {
    /** The chip's distance above the destination's head, and from the window's edges. */
    private static final float LIFT = 4;
    private static final float MARGIN = 8;
    /** The buttons' height (and Clear's width), the gap between them, Move's width and the context's least width. */
    private static final float BUTTON = 28;
    private static final float GAP = 4;
    private static final float CONFIRM = 96;
    private static final float CONTEXT = 72;
    /** How far above the destination's head the chip reaches, with a gap: the destination tip stands over it. */
    static final float HEADROOM = LIFT + BUTTON + GAP;
    /** The elevation stepper's row between the head and the chip, which the destination tip also rises over. */
    static final float STEPPER_ROOM = BUTTON + GAP;
    /**
     * The turn arrows' distance from the ghost's sides; they stand at the ghost's middle (the Tactical ring's), at
     * least a clearance below the chip and a least reach from the middle, so a small ghost doesn't crowd them.
     */
    private static final float SIDE = 6;
    private static final float MIDDLE = .55f;
    private static final float CLEARANCE = 6;
    private static final float MIN_REACH = 14;
    /** The elevation stepper's read-out's least width: Go down, the elevation, Go up. */
    private static final float READOUT = 64;
    private static final String GO_UP = MoveCommand.MOVE_RAISE_ELEVATION.getCmd();
    private static final String GO_DOWN = MoveCommand.MOVE_LOWER_ELEVATION.getCmd();
    private static final String TURN = MoveCommand.MOVE_TURN.getCmd();

    private final GpuHudState state;
    private final GpuBoardSource source;
    private final BoardCamera camera;
    private final GpuNameplates nameplates;
    private final Group root = new Group();
    private final Table chip = new Table();
    /** A walk-on unit's setup tag while it sets up its entry, above the chip: deployment's {@link GpuSetupTag}. */
    private final GpuSetupTag tag;

    private final UiButton confirm;
    private final Label confirmTip;
    private final UiButton clear;
    private final Label clearTip;
    private final Cell<UiButton> clearCell;
    private final UiButton turnLeft;
    private final UiButton turnRight;
    private final Label turnLeftTip;
    private final Label turnRightTip;
    private final UiButton context;
    private final Cell<UiButton> contextCell;
    private final Table stepper = new Table();
    private final UiButton goUp;
    private final UiButton goDown;
    private final Label goUpTip;
    private final Label goDownTip;
    private final UiButton elevation;
    private GpuHud.Inputs inputs;
    private String contextId = "";
    private String mainId = "";
    /** Whether the chip's main button enters a walk-on unit (walk-on step 1) instead of MegaMek's command. */
    private boolean entering;

    GpuMoveConfirm(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera,
          GpuNameplates nameplates) {
        this.state = state;
        this.source = source;
        this.camera = camera;
        this.nameplates = nameplates;
        UiKit ui = kit.ui;
        root.setName("move-confirm");
        root.setTransform(false);
        root.setTouchable(Touchable.childrenOnly);
        confirm = ui.button("hud-confirm", "check", text("GpuBoard.hud.move.confirm"), null);
        confirm.setName("move-confirm-done");
        confirmTip = ui.tip(confirm).getActor();
        UiKit.onChange(confirm, this::confirm);
        context = ui.button("hud-confirm-step", null, "", null);
        context.setName("move-confirm-context");
        context.getLabel().setEllipsis(true);
        context.getCell(context.getLabel()).minWidth(0);
        UiKit.onChange(context, () -> run(contextId));
        clear = ui.button("hud-confirm-clear", "close", null, null);
        clear.setName("move-confirm-clear");
        clearTip = ui.tip(clear).getActor();
        UiKit.onChange(clear, () -> source.moves().clearOrWithdraw());
        chip.add(confirm).width(CONFIRM).height(BUTTON);
        contextCell = chip.add(context).minWidth(CONTEXT).height(BUTTON).padLeft(GAP);
        clearCell = chip.add(clear).size(BUTTON).padLeft(GAP);
        chip.setVisible(false);
        root.addActor(chip);
        tag = new GpuSetupTag(ui, "move-confirm-setup-tag", null);
        root.addActor(tag);
        turnLeft = ui.button("hud-confirm-step", "twist-left", null, null);
        turnLeft.setName("move-confirm-turn-left");
        turnLeftTip = ui.tip(turnLeft).getActor();
        UiKit.onChange(turnLeft, () -> source.moves().turn(-1));
        turnRight = ui.button("hud-confirm-step", "twist-right", null, null);
        turnRight.setName("move-confirm-turn-right");
        turnRightTip = ui.tip(turnRight).getActor();
        UiKit.onChange(turnRight, () -> source.moves().turn(1));
        for (UiButton turn : new UiButton[] { turnLeft, turnRight }) {
            turn.setSize(BUTTON, BUTTON);
            turn.setVisible(false);
            root.addActor(turn);
        }
        goUp = ui.button("hud-confirm-step", "chevron-up", null, null);
        goUp.setName("move-confirm-go-up");
        goUpTip = ui.tip(goUp).getActor();
        UiKit.onChange(goUp, () -> run(GO_UP));
        goDown = ui.button("hud-confirm-step", "chevron-down", null, null);
        goDown.setName("move-confirm-go-down");
        goDownTip = ui.tip(goDown).getActor();
        UiKit.onChange(goDown, () -> run(GO_DOWN));
        // The read-out is a step button, as deployment's height, so it reads as one control with its arrows.
        elevation = ui.button("hud-confirm-step", null, "", null);
        elevation.setName("move-confirm-elevation");
        ui.tip(elevation).getActor().setText(text("GpuBoard.hud.dock.elevationTip"));
        stepper.setName("move-confirm-stepper");
        stepper.add(goDown).size(BUTTON);
        stepper.add(elevation).minWidth(READOUT).height(BUTTON).padLeft(GAP);
        stepper.add(goUp).size(BUTTON).padLeft(GAP);
        stepper.pack();
        stepper.setVisible(false);
        root.addActor(stepper);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        Ghost ghost = ghost(inputs);
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        boolean flight = move.traits().flight() != null;
        // A flight turns by MegaMek's Turn, which only some aerospace units have (not a spheroid in atmosphere); a
        // ground unit turns where its facing matters (not infantry whose weapons all fire around it).
        BoardScene.Command turn = flight ? command(TURN) : null;
        boolean turns = ghost != null && (flight ? turn != null : move.facingMatters());
        chip.setVisible(ghost != null);
        turnLeft.setVisible(turns);
        turnRight.setVisible(turns);
        BoardScene.Command up = command(GO_UP);
        BoardScene.Command down = command(GO_DOWN);
        boolean elevates = ghost != null && steps(inputs.frame().scene(), move);
        stepper.setVisible(elevates);
        if (ghost == null) {
            tag.setVisible(false);
            entering = false;
            return;
        }
        GpuBoardSource.WalkOnEntry entry = source == null ? null : source.walkOnEntry();
        if (entry == null) {
            entry = GpuBoardSource.WalkOnEntry.NONE;
        }
        boolean ownEntry = entry.unitId() == move.entityId();
        // Walk-on step 1: Enter settles the entry hex, facing, height, mode and setup; step 2's ✕ goes back to it.
        entering = ownEntry && entry.stage() == GpuBoardSource.EntryStage.SETTING_UP && entry.placed();
        boolean entered = ownEntry && entry.stage() == GpuBoardSource.EntryStage.ENTERED;
        boolean staying = move.route().isEmpty() && !move.traits().converting();
        mainId = staying ? holdId() : doneId();
        confirm.setText(UiTheme.upper(text(entering ? "GpuBoard.hud.move.entry.enter"
              : staying ? "GpuBoard.hud.move.hold" : "GpuBoard.hud.move.confirm")));
        confirmTip.setText(entering ? text("GpuBoard.hud.move.entry.enterTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE))
              : staying ? text("GpuBoard.hud.move.holdTip") : text("GpuBoard.hud.move.confirmTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE)));
        turnLeftTip.setText(text(flight ? "GpuBoard.hud.move.turnLeftFlightTip" : "GpuBoard.hud.move.turnLeftTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.TURN_LEFT)));
        turnRightTip.setText(text(flight ? "GpuBoard.hud.move.turnRightFlightTip"
                    : "GpuBoard.hud.move.turnRightTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.TURN_RIGHT)));
        turnLeft.setDisabled(flight && !turn.enabled());
        turnRight.setDisabled(flight && !turn.enabled());
        BoardScene.Command pick = GpuMoveContext.pick(inputs.frame().scene().commands());
        boolean pressing = GpuMoveContext.pressing(pick);
        contextId = pressing ? pick.id() : "";
        context.setVisible(pressing);
        if (pressing) {
            context.setText(UiTheme.upper(GpuMoveContext.caption(pick)));
        }
        // Without a context command its cell closes, and Clear follows Move; a unit without a route has nothing to
        // clear, unless it walked on.
        contextCell.setActor(pressing ? context : null).minWidth(pressing ? CONTEXT : 0)
              .padLeft(pressing ? GAP : 0);
        boolean clears = !staying || move.walkedOn();
        clear.setVisible(clears);
        clearCell.setActor(clears ? clear : null).size(clears ? BUTTON : 0).padLeft(clears ? GAP : 0);
        clearTip.setText(text(!staying ? "GpuBoard.hud.dock.clearRoute" : entering
              ? "GpuBoard.hud.move.entry.withdrawTip" : entered ? "GpuBoard.hud.move.entry.setupTip"
              : "GpuBoard.hud.dock.withdrawWalkOn"));
        chip.invalidate();
        chip.pack();
        if (elevates) {
            elevation.setText(text(move.traits().altitude() ? "GpuBoard.hud.move.altitude" : move.traits().dives()
                  ? "GpuBoard.hud.move.depth" : "GpuBoard.hud.deploy.elevation", move.traits().shownLevel()));
            step(goUp, goUpTip, up);
            step(goDown, goDownTip, down);
            if (move.choosesEntryHeight()) {
                String kind = move.traits().altitude() ? "altitude" : move.traits().dives() ? "depth" : "elevation";
                goUpTip.setText(text("GpuBoard.hud.move.entry." + kind + "UpTip"));
                goDownTip.setText(text("GpuBoard.hud.move.entry." + kind + "DownTip"));
            }
            stepper.invalidate();
            stepper.pack();
        }
        Vector2 head = ghost.head();
        // On the unit's own hex its tag stands on the same head: the controls stand on the tag.
        float bottom = ghost.home() ? Math.max(head.y, nameplates.top(move.entityId(), head.y)) : head.y;
        // A unit that changes elevation has its stepper on the head, as deployment's height row, and the chip on it.
        place(chip, head.x - chip.getWidth() / 2, bottom + LIFT + (elevates ? STEPPER_ROOM : 0));
        // While the unit sets up its entry, a changed setup shows on the chip as deployment's does.
        GpuDeploySetup.Choice setup = entering ? source.setupChoice() : null;
        boolean tagged = setup != null && setup.unitId() == move.entityId() && !setup.tag().isEmpty();
        tag.setVisible(tagged);
        if (tagged) {
            tag.show(setup);
            place(tag, chip.getX() + (chip.getWidth() - tag.getWidth()) / 2, chip.getTop() + GAP);
        }
        Actor lowest = chip;
        if (elevates) {
            place(stepper, head.x - stepper.getWidth() / 2, chip.getY() - GAP - stepper.getHeight());
            lowest = stepper;
        }
        Vector2 middle = ghost.middle();
        float reach = Math.max(ghost.width / 2, MIN_REACH) + SIDE;
        // The arrows keep clear of the controls above them: a small ghost (zoomed out) or a chip held inside the
        // window would otherwise bring them together.
        float y = Math.min(middle.y - BUTTON / 2, lowest.getY() - CLEARANCE - BUTTON);
        place(turnLeft, middle.x - reach - BUTTON, y);
        place(turnRight, middle.x + reach, y);
    }

    private static void step(UiButton button, Label tip, BoardScene.Command command) {
        button.setDisabled(command == null || !command.enabled());
        if (command != null) {
            tip.setText(command.label());
        }
    }

    /** Places {@code actor}'s lower left corner at x, y, kept inside the window. */
    private void place(Actor actor, float x, float y) {
        Actor parent = root.getParent();
        float right = parent == null ? Float.MAX_VALUE : parent.getWidth() - MARGIN - actor.getWidth();
        float top = parent == null ? Float.MAX_VALUE : parent.getHeight() - MARGIN - actor.getHeight();
        actor.setPosition(Math.round(MathUtils.clamp(x, MARGIN, Math.max(MARGIN, right))),
              Math.round(MathUtils.clamp(y, MARGIN, Math.max(MARGIN, top))));
    }

    /**
     * The destination's ghost on screen (y up): its ground point, the unit's screen size and the view. The chip stands
     * on its head, as the destination tip anchors it, and the turn arrows at its sides.
     */
    private record Ghost(Vector2 ground, float width, float height, boolean tactical, boolean home) {
        Vector2 head() {
            return new Vector2(ground).add(0, (tactical ? GpuBoardLabels.RING_NORTH : GpuBoardLabels.TIP_HEIGHT) * height);
        }

        /** The ghost's middle: the Tactical View's ring centre, else .55 up the unit as the labels' middle. */
        Vector2 middle() {
            return tactical ? new Vector2(ground) : new Vector2(ground).add(0, MIDDLE * height);
        }
    }

    /**
     * The destination's ghost this frame, or the unit itself before it has a route, or null while there is nothing to
     * confirm: not the player's movement turn, a dialog or the forces overview over the board, no planned unit on the
     * shown board (or a walk-on unit not yet entered), the point off screen, or MegaMek's Done (Hold position without
     * a route) not enabled.
     */
    private Ghost ghost(GpuHud.Inputs inputs) {
        BoardScene scene = inputs.frame().scene();
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        GpuBoardSource.WalkOnEntry entry = source == null ? null : source.walkOnEntry();
        // A unit setting up its entry has the chip even outside the planner (a landed Fighter LAM).
        boolean settingUp = entry != null && entry.unitId() == move.entityId()
              && entry.stage() == GpuBoardSource.EntryStage.SETTING_UP;
        if (scene == null || camera == null || inputs.dialog() != null || state.overview || !status.myTurn()
              || !status.phase().isMovement() || state.turnLocked(status) || !move.active()
              || !move.planner() && move.traits().flight() == null && !settingUp
              || move.holdingRemaining() > 0) {
            return null;
        }
        GpuBattleStatus.UnitStatus unit = GpuHudState.unit(status, move.entityId());
        boolean onBoard = unit != null && unit.position() != null && unit.boardId() == scene.boardId();
        Coords coords;
        float level;
        boolean home;
        if (move.route().isEmpty()) {
            BoardScene.Unit drawn = drawn(scene, move.entityId());
            BoardScene.Command hold = command(move.traits().converting() ? doneId() : holdId());
            if (move.traits().entering() || !onBoard || drawn == null || hold == null || !hold.enabled()) {
                return null;
            }
            coords = drawn.location().coords();
            level = drawn.location().elevation();
            home = true;
        } else {
            GpuMovePlan.Step last = move.route().getLast();
            BoardScene.Command done = command(doneId());
            if (last.boardId() != scene.boardId() || done == null || !done.enabled()) {
                return null;
            }
            coords = last.coords();
            level = last.level();
            home = onBoard && coords.equals(unit.position());
        }
        BoardScene.Tile tile = scene.tile(coords);
        if (tile == null) {
            return null;
        }
        Vector3 world = inputs.view().tactical() || level == tile.elevation()
              ? new Vector3(BoardGeometry.centerX(coords), BoardGeometry.centerY(coords), BoardGeometry.surfaceZ(tile))
              : BoardGeometry.center(coords, level);
        Actor parent = root.getParent();
        Vector2 point = GpuNameplates.project(camera.camera, world);
        if (point == null || parent == null || parent.getWidth() <= 0) {
            return null;
        }
        point.scl(parent.getWidth() / camera.camera.viewportWidth, parent.getHeight() / camera.camera.viewportHeight);
        Rectangle rect = inputs.view().unitRects().get(move.entityId());
        return new Ghost(point, rect == null ? 0 : rect.width, rect == null ? 0 : rect.height,
              inputs.view().tactical(), home);
    }

    /** The scene's unit {@code id} (its first part), or null. */
    private static BoardScene.Unit drawn(BoardScene scene, int id) {
        for (BoardScene.Unit unit : scene.units()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }

    /** The dock's Hold position: the skip, or Done for a walked-on unit without a route. */
    private String holdId() {
        return inputs == null ? "" : inputs.frame().panels().phase().holdId(inputs.frame().panels().move());
    }

    /** Whether the controls carry the elevation stepper: a unit that changes elevation, with Go up or Go down. */
    static boolean steps(BoardScene scene, GpuMovePlan.Snapshot move) {
        return scene != null && move.traits().elevates() && scene.commands().stream()
              .anyMatch(command -> command.id().equals(GO_UP) || command.id().equals(GO_DOWN));
    }

    private String doneId() {
        return inputs == null ? "" : inputs.frame().panels().phase().doneId();
    }

    private BoardScene.Command command(String id) {
        BoardScene scene = inputs == null ? null : inputs.frame().scene();
        if (scene == null || id == null || id.isEmpty()) {
            return null;
        }
        for (BoardScene.Command command : scene.commands()) {
            if (command.id().equals(id)) {
                return command;
            }
        }
        return null;
    }

    /** The chip's main button: Enter for a walk-on unit setting up its entry, else Move or Hold. */
    private void confirm() {
        if (entering && inputs != null) {
            source.setEntered(inputs.frame().panels().move().entityId(), true);
        } else {
            run(mainId);
        }
    }

    private void run(String id) {
        BoardScene.Command command = command(id);
        if (command != null && command.enabled()) {
            command.action().run();
        }
    }
}
