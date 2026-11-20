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
import megamek.client.ui.util.KeyCommandBind;

/**
 * Move and Clear over the planned route's destination: a shortcut that repeats the dock's Confirm move and Clear route
 * where the player looks, as the deployment's pair does over a placed unit (battle HUD layout, rule 2). It stands on
 * the destination's head, where the route's ghost carries the unit's pose (the Tactical View's base ring north), and
 * the destination tip hangs beside and above it. Between Move and Clear, the dock's context command while it answers
 * the unit's situation (Stand up, Unjam…); a plain stance stays in the dock. Beside the ghost, turn arrows repeat the
 * dock's Left and Right, and their tips teach the drag that faces any hex. The chip shows only in the player's own
 * movement turn while the plotted route ends on the shown board, on screen, and MegaMek's Done is enabled. The pair
 * keeps the deployment chip's size, so it is not missed.
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
    /** The turn arrows' distance from the ghost's sides; they stand at the ghost's middle (the Tactical ring's). */
    private static final float SIDE = 6;
    private static final float MIDDLE = .55f;

    private final GpuHudState state;
    private final BoardCamera camera;
    private final Group root = new Group();
    private final Table chip = new Table();
    private final Label confirmTip;
    private final UiButton turnLeft;
    private final UiButton turnRight;
    private final Label turnLeftTip;
    private final Label turnRightTip;
    private final UiButton context;
    private final Cell<UiButton> contextCell;
    private GpuHud.Inputs inputs;
    private String contextId = "";

    GpuMoveConfirm(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera) {
        this.state = state;
        this.camera = camera;
        UiKit ui = kit.ui;
        root.setName("move-confirm");
        root.setTransform(false);
        root.setTouchable(Touchable.childrenOnly);
        UiButton confirm = ui.button("hud-confirm", "check", text("GpuBoard.hud.move.confirm"), null);
        confirm.setName("move-confirm-done");
        confirmTip = ui.tip(confirm).getActor();
        UiKit.onChange(confirm, () -> run(doneId()));
        context = ui.button("hud-confirm-step", null, "", null);
        context.setName("move-confirm-context");
        context.getLabel().setEllipsis(true);
        context.getCell(context.getLabel()).minWidth(0);
        UiKit.onChange(context, () -> run(contextId));
        UiButton clear = ui.button("hud-confirm-clear", "close", null, null);
        clear.setName("move-confirm-clear");
        ui.tip(clear).getActor().setText(text("GpuBoard.hud.dock.clearRoute"));
        UiKit.onChange(clear, () -> source.moves().clearOrWithdraw());
        chip.add(confirm).width(CONFIRM).height(BUTTON);
        contextCell = chip.add(context).minWidth(CONTEXT).height(BUTTON).padLeft(GAP);
        chip.add(clear).size(BUTTON).padLeft(GAP);
        chip.setVisible(false);
        root.addActor(chip);
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
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        Ghost ghost = ghost(inputs);
        chip.setVisible(ghost != null);
        turnLeft.setVisible(ghost != null);
        turnRight.setVisible(ghost != null);
        if (ghost == null) {
            return;
        }
        confirmTip.setText(text("GpuBoard.hud.move.confirmTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE)));
        turnLeftTip.setText(text("GpuBoard.hud.move.turnLeftTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.TURN_LEFT)));
        turnRightTip.setText(text("GpuBoard.hud.move.turnRightTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.TURN_RIGHT)));
        BoardScene.Command pick = GpuMoveContext.pick(inputs.frame().scene().commands());
        boolean pressing = GpuMoveContext.pressing(pick);
        contextId = pressing ? pick.id() : "";
        context.setVisible(pressing);
        if (pressing) {
            context.setText(UiTheme.upper(GpuMoveContext.caption(pick)));
        }
        // Without a context command its cell closes, and Clear follows Move.
        contextCell.setActor(pressing ? context : null).minWidth(pressing ? CONTEXT : 0)
              .padLeft(pressing ? GAP : 0);
        chip.invalidate();
        chip.pack();
        Vector2 head = ghost.head();
        place(chip, head.x - chip.getWidth() / 2, head.y + LIFT);
        Vector2 middle = ghost.middle();
        float reach = ghost.width / 2 + SIDE;
        place(turnLeft, middle.x - reach - BUTTON, middle.y - BUTTON / 2);
        place(turnRight, middle.x + reach, middle.y - BUTTON / 2);
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
    private record Ghost(Vector2 ground, float width, float height, boolean tactical) {
        Vector2 head() {
            return new Vector2(ground).add(0, (tactical ? GpuBoardLabels.RING_NORTH : GpuBoardLabels.TIP_HEIGHT) * height);
        }

        /** The ghost's middle: the Tactical View's ring centre, else .55 up the unit as the labels' middle. */
        Vector2 middle() {
            return tactical ? new Vector2(ground) : new Vector2(ground).add(0, MIDDLE * height);
        }
    }

    /**
     * The destination's ghost this frame, or null while there is nothing to confirm: not the player's movement turn, a
     * dialog or the forces overview over the board, no plotted route of a planned unit on the shown board, the
     * destination off screen, or MegaMek's Done not enabled.
     */
    private Ghost ghost(GpuHud.Inputs inputs) {
        BoardScene scene = inputs.frame().scene();
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuMovePlan.Snapshot move = inputs.frame().panels().move();
        if (scene == null || camera == null || inputs.dialog() != null || state.overview || !status.myTurn()
              || !status.phase().isMovement() || state.turnLocked(status) || !move.active() || !move.planner()
              || move.route().isEmpty() || move.holdingRemaining() > 0) {
            return null;
        }
        GpuMovePlan.Step last = move.route().getLast();
        BoardScene.Tile tile = scene.tile(last.coords());
        BoardScene.Command done = command(doneId());
        if (last.boardId() != scene.boardId() || tile == null || done == null || !done.enabled()) {
            return null;
        }
        Vector3 world = inputs.view().tactical() || last.level() == tile.elevation()
              ? new Vector3(BoardGeometry.centerX(last.coords()), BoardGeometry.centerY(last.coords()),
                    BoardGeometry.surfaceZ(tile))
              : BoardGeometry.center(last.coords(), last.level());
        Actor parent = root.getParent();
        Vector2 point = GpuNameplates.project(camera.camera, world);
        if (point == null || parent == null || parent.getWidth() <= 0) {
            return null;
        }
        point.scl(parent.getWidth() / camera.camera.viewportWidth, parent.getHeight() / camera.camera.viewportHeight);
        Rectangle rect = inputs.view().unitRects().get(move.entityId());
        return new Ghost(point, rect == null ? 0 : rect.width, rect == null ? 0 : rect.height,
              inputs.view().tactical());
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

    private void run(String id) {
        BoardScene.Command command = command(id);
        if (command != null && command.enabled()) {
            command.action().run();
        }
    }
}
