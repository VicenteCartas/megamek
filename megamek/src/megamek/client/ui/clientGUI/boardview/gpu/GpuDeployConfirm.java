/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.List;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay.DeployCommand;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Deploy and Clear over the unit the player just placed (deployment HUD design): the placement is the question, so
 * its answer stands where the player looks, above the unit's nameplate. Deploy sends the placement and its facing
 * (MegaMek's Done); Clear takes the unit back off the board. They show only in the player's own deployment turn while
 * the placed unit is on screen and MegaMek's Done is enabled. A unit with a choice of heights in its hex (an
 * aerospace unit's altitude, a VTOL's elevation) shows that height above them, stepped by its arrows, a click on it or
 * Ctrl+wheel, instead of a dialog on each placement.
 */
final class GpuDeployConfirm implements GpuHud.Component {
    /** The pair's distance above the nameplate, and from the window's edges. */
    private static final float LIFT = 4;
    private static final float MARGIN = 8;
    /** The buttons' height (and Clear's width), the gap between them, and the pair's least width. */
    private static final float BUTTON = 22;
    private static final float GAP = 3;
    private static final float MIN_WIDTH = 92;
    private static final String CLEAR = DeployCommand.DEPLOY_CLEAR_DEPLOY.getCmd();
    /** How long a height asked for shows before MegaMek's placed unit takes it. */
    private static final long ASK_MILLIS = 600;

    private final GpuNameplates nameplates;
    private final GpuHudState state;
    private final Group root = new Group();
    private final Table pair = new Table();
    private final Label confirmTip;
    private final Cell<UiButton> confirmCell;
    private final GpuBoardSource source;
    /** The placed unit's height (altitude) row above the pair: lower, the height, higher. */
    private final Table heights = new Table();
    private final UiButton height;
    private final Cell<UiButton> heightCell;
    private GpuHud.Inputs inputs;
    private int actorId = -1;
    /** The height asked for last, shown until MegaMek's placed unit takes it; its time. */
    private int asked;
    private long askedAt;
    /** The wheel's notches not yet turned into a height step. */
    private float wheelLeft;

    GpuDeployConfirm(GpuHudKit kit, GpuHudState state, GpuNameplates nameplates, GpuBoardSource source) {
        this.nameplates = nameplates;
        this.state = state;
        this.source = source;
        UiKit ui = kit.ui;
        UiButton lower = ui.button("hud-confirm-step", "chevron-down", null, null);
        lower.setName("deploy-height-down");
        ui.tip(lower).getActor().setText(text("GpuBoard.hud.deploy.heightDown"));
        UiKit.onChange(lower, () -> step(-1, false));
        height = ui.button("hud-confirm-step", null, "", null);
        height.setName("deploy-height");
        ui.tip(height).getActor().setText(text("GpuBoard.hud.deploy.heightTip"));
        UiKit.onChange(height, () -> step(1, true));
        UiButton higher = ui.button("hud-confirm-step", "chevron-up", null, null);
        higher.setName("deploy-height-up");
        ui.tip(higher).getActor().setText(text("GpuBoard.hud.deploy.heightUp"));
        UiKit.onChange(higher, () -> step(1, false));
        heights.add(lower).size(BUTTON);
        heightCell = heights.add(height).height(BUTTON).padLeft(GAP);
        heights.add(higher).size(BUTTON).padLeft(GAP);
        heights.setVisible(false);
        root.addActor(heights);
        root.setName("deploy-confirm");
        root.setTransform(false);
        root.setTouchable(Touchable.childrenOnly);
        UiButton confirm = ui.button("hud-confirm", "check", text("GpuBoard.hud.deploy.confirm"), null);
        confirm.setName("deploy-confirm-done");
        confirmTip = ui.tip(confirm).getActor();
        UiKit.onChange(confirm, () -> run(doneId()));
        UiButton clear = ui.button("hud-confirm-clear", "close", null, null);
        clear.setName("deploy-confirm-clear");
        ui.tip(clear).getActor().setText(text("GpuBoard.hud.deploy.clearTip"));
        UiKit.onChange(clear, () -> run(CLEAR));
        confirmCell = pair.add(confirm).height(BUTTON);
        pair.add(clear).size(BUTTON).padLeft(GAP);
        pair.setVisible(false);
        root.addActor(pair);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        Vector2 at = anchor(inputs);
        pair.setVisible(at != null);
        if (at == null) {
            heights.setVisible(false);
            return;
        }
        confirmTip.setText(text("GpuBoard.hud.deploy.confirmTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE)));
        float width = Math.max(MIN_WIDTH, nameplates.width(actorId));
        confirmCell.width(width - BUTTON - GAP);
        pair.invalidate();
        pair.setSize(width, pair.getPrefHeight());
        Actor parent = root.getParent();
        float right = parent == null ? Float.MAX_VALUE : parent.getWidth() - MARGIN - pair.getWidth();
        float top = parent == null ? Float.MAX_VALUE : parent.getHeight() - MARGIN - pair.getHeight();
        pair.setPosition(MathUtils.clamp(at.x - pair.getWidth() / 2, MARGIN, Math.max(MARGIN, right)),
              MathUtils.clamp(at.y + LIFT, MARGIN, Math.max(MARGIN, top)));
        GpuBoardSource.HeightChoice choice = choice();
        heights.setVisible(choice != null);
        if (choice != null) {
            int shown = shownHeight(choice);
            height.setText(choice.altitude() ? (shown == 0 ? text("GpuBoard.hud.deploy.landed")
                  : text("GpuBoard.hud.deploy.altitude", shown)) : text("GpuBoard.hud.deploy.elevation", shown));
            heightCell.width(width - 2 * (BUTTON + GAP));
            heights.invalidate();
            heights.setSize(width, heights.getPrefHeight());
            // Above the pair, unless the window's top pushes it below.
            float above = pair.getY() + pair.getHeight() + GAP;
            heights.setPosition(pair.getX(), above + heights.getHeight() <= (parent == null ? Float.MAX_VALUE
                  : parent.getHeight() - MARGIN) ? above : pair.getY() - GAP - heights.getHeight());
        }
    }

    /** The placed unit's height choice while the pair shows and it has one, else null. */
    private GpuBoardSource.HeightChoice choice() {
        GpuBoardSource.HeightChoice choice = source == null ? null : source.heightChoice();
        return pair.isVisible() && choice != null && choice.unitId() == actorId && choice.heights().size() > 1
              ? choice : null;
    }

    /** The height to show: the one asked for last until MegaMek's unit takes it, else the unit's. */
    private int shownHeight(GpuBoardSource.HeightChoice choice) {
        boolean waiting = askedAt != 0 && System.currentTimeMillis() - askedAt < ASK_MILLIS && asked != choice.height()
              && choice.heights().contains(asked);
        if (!waiting) {
            askedAt = 0;
        }
        return waiting ? asked : choice.height();
    }

    /**
     * Steps the placed unit's height {@code step} up (1) or down (-1); {@code wrap} goes from the highest back to the
     * lowest, as a click on the height itself does.
     */
    private void step(int step, boolean wrap) {
        GpuBoardSource.HeightChoice choice = choice();
        if (choice == null) {
            return;
        }
        int from = shownHeight(choice);
        List<Integer> options = choice.heights();
        int at = options.indexOf(from);
        int next = at < 0 ? choice.height()
              : wrap && at + step >= options.size() ? options.getFirst()
              : options.get(MathUtils.clamp(at + step, 0, options.size() - 1));
        if (next != from) {
            asked = next;
            askedAt = System.currentTimeMillis();
            source.setPlacedHeight(choice.unitId(), next);
        }
    }

    /** Ctrl+wheel over the board: a notch away (up) raises the placed unit; false while it has no height choice. */
    boolean wheel(float amountY) {
        if (choice() == null) {
            wheelLeft = 0;
            return false;
        }
        wheelLeft -= amountY;
        int notches = (int) wheelLeft;
        wheelLeft -= notches;
        for (int i = 0; i < Math.abs(notches); i++) {
            step(Integer.signum(notches), false);
        }
        return true;
    }

    /**
     * Where the pair stands this frame, centred above the placed unit's plate (y up), or null while it has nothing to
     * confirm: not the player's deployment turn, a dialog or the forces overview over the board, the acting unit not
     * placed or off screen, or MegaMek's Done not enabled.
     */
    private Vector2 anchor(GpuHud.Inputs inputs) {
        BoardScene scene = inputs.frame().scene();
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        if (scene == null || inputs.dialog() != null || state.overview || !status.myTurn()
              || !status.phase().isDeployment() || state.turnLocked(status)) {
            return null;
        }
        GpuBattleStatus.UnitStatus actor = GpuHudState.unit(status, status.actorId());
        if (actor == null || actor.position() == null || actor.boardId() != scene.boardId()) {
            return null;
        }
        BoardScene.Command done = command(doneId());
        Vector2 head = inputs.view().unitHeads().get(actor.id());
        if (done == null || !done.enabled() || head == null) {
            return null;
        }
        actorId = actor.id();
        return new Vector2(head.x, nameplates.top(actor.id(), head.y));
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
