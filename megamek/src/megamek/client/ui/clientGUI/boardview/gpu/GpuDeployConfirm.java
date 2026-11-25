/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.math.Rectangle;
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
 * Deploy and Clear over the unit the player just placed: a shortcut that repeats the dock's Deploy and Clear where the
 * player looks, above the unit's nameplate (battle HUD layout, rule 2). Deploy sends the placement and its facing
 * (MegaMek's Done); Clear takes the unit back off the board. They show only in the player's own deployment turn while
 * the placed unit is on screen and MegaMek's Done is enabled. A unit with a choice of heights in its hex (an
 * aerospace unit's altitude, a VTOL's elevation) shows that height on the unit, under them, stepped by its arrows, a
 * click on it or Ctrl+wheel, as the dock's stepper does. A unit whose starting setup differs from how it began shows an
 * amber tag ({@link GpuSetupTag}) above them naming the changes between the two, which opens upward into each change
 * under the pointer, so the board says what Deploy sends. A unit whose facing matters gets the board chip's turn arrows
 * at its sides, as a moving unit's, beside the drag that faces any hex and MegaMek's Turn in More. The column and the
 * arrows are the board chip's ({@link GpuBoardChip}), as a walk-on unit's entry chip stacks them.
 */
final class GpuDeployConfirm implements GpuHud.Component {
    private static final float BUTTON = GpuBoardChip.BUTTON;
    private static final float GAP = GpuBoardChip.GAP;
    private static final String CLEAR = DeployCommand.DEPLOY_CLEAR_DEPLOY.getCmd();

    private final GpuNameplates nameplates;
    private final GpuHudState state;
    private final Group root = new Group();
    private final Table pair = new Table();
    private final Label confirmTip;
    private final Cell<UiButton> confirmCell;
    private final GpuDeployHeight deployHeight;
    /** The placed unit's height (altitude) row on the unit, under the pair. */
    private final GpuBoardChip.HeightRow heights;
    private final GpuBoardSource source;
    /** The placed unit's setup tag: the changes in short, opening upward into each under the pointer. */
    private final GpuSetupTag tag;
    /** The placed unit's turn arrows, at its sides below the column. */
    private final GpuBoardChip.TurnArrows turns;

    private GpuHud.Inputs inputs;
    private int actorId = -1;

    GpuDeployConfirm(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuNameplates nameplates,
          GpuDeployHeight deployHeight) {
        this.source = source;
        this.nameplates = nameplates;
        this.state = state;
        this.deployHeight = deployHeight;
        UiKit ui = kit.ui;
        heights = new GpuBoardChip.HeightRow(ui, "deploy-height",
              (step, wrap) -> deployHeight.step(actorId, step, wrap));
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
        tag = new GpuSetupTag(ui, "deploy-setup-tag", null);
        root.addActor(tag);
        turns = new GpuBoardChip.TurnArrows(ui, root, "deploy-confirm-turn", step -> source.turnPlaced(actorId, step));
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
            tag.setVisible(false);
            turns.hide();
            return;
        }
        confirmTip.setText(text("GpuBoard.hud.deploy.confirmTip",
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.DONE)));
        float width = Math.max(GpuBoardChip.MIN_WIDTH, nameplates.width(actorId));
        confirmCell.width(width - BUTTON - GAP);
        pair.invalidate();
        pair.setSize(width, pair.getPrefHeight());
        GpuBoardSource.HeightChoice choice = deployHeight.choice(actorId);
        heights.setVisible(choice != null);
        if (choice != null) {
            heights.show(deployHeight.label(choice), text("GpuBoard.hud.deploy.heightDown"),
                  text("GpuBoard.hud.deploy.heightTip"), text("GpuBoard.hud.deploy.heightUp"), true, true, width);
        }
        GpuDeploySetup.Choice setup = source.setupChoice();
        boolean tagged = setup != null && setup.unitId() == actorId && !setup.tag().isEmpty();
        tag.setVisible(tagged);
        if (tagged) {
            tag.show(setup);
        }
        GpuBoardChip.stack(root.getParent(), at.x, at.y + GpuBoardChip.LIFT, heights, pair, tag);
        GpuBattleStatus.UnitStatus actor = GpuHudState.unit(inputs.frame().status(), actorId);
        Rectangle rect = inputs.view().unitRects().get(actorId);
        if (actor == null || !actor.turns() || rect == null) {
            turns.hide();
            return;
        }
        // The middle of the placed unit: the Tactical View's ring centre, else .55 up the unit, as a moving unit's.
        Vector2 middle = inputs.view().tactical() ? rect.getCenter(new Vector2())
              : new Vector2(rect.x + rect.width / 2, rect.y + GpuBoardChip.TurnArrows.MIDDLE * rect.height);
        turns.show(text("GpuBoard.hud.deploy.turnLeftTip",
                    GpuHintLine.key(inputs.preferences(), KeyCommandBind.TURN_LEFT)),
              text("GpuBoard.hud.deploy.turnRightTip",
                    GpuHintLine.key(inputs.preferences(), KeyCommandBind.TURN_RIGHT)),
              true, root.getParent(), middle, rect.width, GpuBoardChip.bottom(heights, pair));
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
