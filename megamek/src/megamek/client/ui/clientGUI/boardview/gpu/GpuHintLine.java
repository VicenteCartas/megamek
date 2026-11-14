/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Hint line above the dock: the mouse gestures of the phase and the view, with the current keys (C.1 G4, L11). In
 * windows too narrow for the line (W <= 1350) a bot order's hex pick shows in its chip instead, with its Done and
 * Cancel buttons.
 */
final class GpuHintLine implements GpuHud.Component {
    /** The line's text (#hint); the gesture names are white (#hint b). */
    private static final Color TEXT = new Color(236 / 255f, 240 / 255f, 238 / 255f, .75f);
    /** The line's text shadow (0 1px 3px rgba(0,0,0,.8)), drawn one unit lower. */
    private static final Color SHADOW = new Color(0, 0, 0, .8f);
    private static final float SIZE = 11;
    private static final float ITEM_GAP = 16;
    private static final float NAME_GAP = 5;
    /** How long the line stays after its gestures change, and how long it then takes to fade out. */
    private static final long HOLD_MILLIS = 4000;
    private static final long FADE_MILLIS = 600;

    private final UiKit ui;
    private final Table root = new Table() {
        @Override
        protected void drawChildren(Batch batch, float parentAlpha) {
            for (Actor child : getChildren()) {
                child.moveBy(0, -1);
                child.setColor(SHADOW);
            }
            super.drawChildren(batch, parentAlpha);
            for (Actor child : getChildren()) {
                child.moveBy(0, 1);
                child.setColor(Color.WHITE);
            }
            super.drawChildren(batch, parentAlpha);
        }
    };
    /** Gesture and action texts, alternating, of the line on screen. */
    private List<String> shown = List.of();
    /** When the shown gestures last changed, in TimeUtils milliseconds. */
    private long changedAt;
    /**
     * The pick's chip: its instructions, the picked hexes once there are some, Done and Cancel, as the Legacy 2D
     * board's picker dialog shows them.
     */
    private final Table chip;
    private final Label instructions;
    private final Label status;
    private final Cell<Label> statusCell;
    private final BoardCamera camera;
    private final GpuHudState state;
    private final Drawable plate;

    GpuHintLine(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera) {
        ui = kit.ui;
        this.camera = camera;
        this.state = state;
        root.setName("hint-line");
        // Presses pass through the line to the board (#hint: pointer-events none).
        root.setTouchable(Touchable.disabled);
        // A dark plate under the gestures, as the board's other floating texts have, so they read over bright ground.
        root.setBackground(ui.skin.getDrawable("panel-pop"));
        root.pad(5, 12, 5, 12);
        plate = root.getBackground();
        chip = ui.panel();
        chip.setName("pick-chip");
        chip.pad(7, 14, 7, 9);
        instructions = ui.label("", "hud-small", 12, UiTheme.TEXT);
        instructions.setEllipsis(true);
        instructions.setName("pick-instructions");
        status = ui.label("", "hud-small", 11, UiTheme.MUTED);
        status.setEllipsis(true);
        status.setName("pick-status");
        Table texts = new Table();
        texts.add(instructions).growX().minWidth(0).left().row();
        statusCell = texts.add(status).growX().minWidth(0).left();
        UiButton done = ui.button("hud-mini", null, text("BotCommandPanel.HexPicker.done"), null);
        done.setName("pick-done");
        UiButton cancel = ui.button("hud-mini", null, text("BotCommandPanel.HexPicker.cancel"), null);
        cancel.setName("pick-cancel");
        onChange(done, () -> source.players().endPick(true));
        onChange(cancel, () -> source.players().endPick(false));
        chip.add(texts).growX().minWidth(0);
        chip.add(done).padLeft(12);
        chip.add(cancel).padLeft(6);
    }

    /** The pick's chip, which GpuHud shows on the dock while a pick runs in a window without the hint line. */
    Actor chip() {
        return chip;
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        // While the camera flies, Free Flight's keys replace the board's gestures (the old GL UI's help line).
        List<String> items = camera.firstPerson() ? flight() : items(inputs, state.deployOverlay);
        GpuBattleStatus.Snapshot deploying = inputs.frame().status();
        GpuBattleStatus.UnitStatus waiting = deploying.myTurn() && deploying.phase().isDeployment()
              && state.inspected != deploying.actorId() ? state.presented(state.inspected) : null;
        boolean notDeploying = !camera.firstPerson() && waiting != null
              && waiting.side() == GpuBattleStatus.Side.OWN && waiting.position() == null;
        if (notDeploying) {
            // A card of an own unit the turn does not deploy: say why nothing follows the pointer.
            items = new ArrayList<>(items);
            items.addAll(0, List.of(waiting.name(), text(waiting.pending() ? "GpuBoard.hud.deploy.laterTurn"
                  : "GpuBoard.hud.deploy.notThisTurn")));
        }
        boolean overlaid = !camera.firstPerson() && state.deployOverlay != null
              && state.deployOverlay != GpuHudState.DeployOverlay.OFF && deploying.myTurn()
              && deploying.phase().isDeployment();
        if (overlaid && state.deployOverlay == GpuHudState.DeployOverlay.DAMAGE) {
            String legend = damageLegend(state.presentedRecord(deploying.actorId()));
            if (!legend.isEmpty()) {
                items = new ArrayList<>(items);
                items.addAll(0, List.of(text("GpuBoard.hud.deploy.overlay.DAMAGE"), legend));
            }
        }
        long now = TimeUtils.millis();
        if (!items.equals(shown)) {
            shown = items;
            changedAt = now;
            root.clearChildren();
            root.setBackground(items.isEmpty() ? null : plate);
            for (int index = 0; index < items.size(); index += 2) {
                root.add(ui.label(items.get(index), "hud-medium", SIZE, Color.WHITE))
                      .padLeft(index == 0 ? 0 : ITEM_GAP);
                root.add(ui.label(items.get(index + 1), "hud-small", SIZE, TEXT)).padLeft(NAME_GAP);
            }
        }
        GpuPlayers.Pick pick = inputs.frame().panels().players().pick();
        // The gestures fade once read and return when they change; a pick's steps, Free Flight's keys and a deployment
        // overlay's legend stay.
        boolean lasting = pick != null || camera.firstPerson() || overlaid || notDeploying;
        float faded = Math.min(1, Math.max(0, (now - changedAt - HOLD_MILLIS) / (float) FADE_MILLIS));
        root.getColor().a = lasting ? 1 : 1 - faded;
        if (pick != null) {
            instructions.setText(pick.instructions());
            status.setText(pick.status());
            statusCell.setActor(pick.status().isEmpty() ? null : status);
        }
    }

    /**
     * The Damage overlay's legend: the unit's peak expected damage, where it peaks and how far it reaches; empty
     * without a damage curve.
     */
    static String damageLegend(GpuUnitRecord.Snapshot record) {
        List<Float> damage = record == null ? List.of() : record.damage();
        int peakRange = -1, reach = 0;
        float peak = 0;
        for (int range = 1; range < damage.size(); range++) {
            float value = damage.get(range);
            if (value > peak) {
                peak = value;
                peakRange = range;
            }
            if (value > 0) {
                reach = range;
            }
        }
        return peakRange < 0 ? ""
              : text("GpuBoard.hud.deploy.damageLegend", Math.round(peak), peakRange, reach);
    }

    /**
     * The line's gestures and what each does now, alternating (the prototype's hintText, plan A.12 L11). While a bot
     * order picks hexes, the pick's: its instructions and picked hexes, Done and Cancel, as the Legacy 2D board's
     * picker dialog shows them.
     */
    static List<String> items(GpuHud.Inputs inputs) {
        return items(inputs, null);
    }

    /** The items, with deployment's overlay mode ({@code null} while Ctrl+wheel has not chosen one). */
    static List<String> items(GpuHud.Inputs inputs, GpuHudState.DeployOverlay overlay) {
        boolean planning = GpuHud.planning(inputs);
        GpuBoardSource.UiPreferences preferences = inputs.preferences();
        GpuPlayers.Pick pick = inputs.frame().panels().players().pick();
        if (pick != null) {
            return List.of(text("GpuBoard.hud.mouse.leftClick"),
                  pick.status().isEmpty() ? pick.instructions() : pick.instructions() + " · " + pick.status(),
                  key(preferences, KeyCommandBind.DONE), text("BotCommandPanel.HexPicker.done"),
                  key(preferences, KeyCommandBind.CANCEL), text("BotCommandPanel.HexPicker.cancel"));
        }
        // A measurement waiting for its second point takes the next left click (GpuHud.boardClick); in the local
        // planning turn it plans the move to a hex in reach, its unit's model too, and selects or inspects a unit
        // elsewhere, and in the charge, DFA and ram gears MegaMek's click chooses the target.
        int pending = inputs.frame().panels().los().pending();
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        if (pending == 0 && status.myTurn() && status.phase().isDeployment()) {
            // Deployment: place, face by dragging from the unit; the queue's chips show the overlays.
            List<String> deploying = new ArrayList<>(List.of(text("GpuBoard.hud.mouse.leftClick"),
                  text("GpuBoard.hud.hint.place"), text("GpuBoard.hud.mouse.leftDrag"),
                  text("GpuBoard.hud.hint.face"), key(preferences, KeyCommandBind.DONE),
                  text("GpuBoard.hud.deploy.confirm")));
            deploying.addAll(List.of(text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.pan"),
                  text("GpuBoard.hud.mouse.wheel"), text("GpuBoard.hud.hint.zoom")));
            return deploying;
        }
        boolean targeting = status.myTurn() && status.phase().isMovement()
              && inputs.frame().panels().move().mode() == GpuMovePlan.Mode.TARGET;
        List<String> items = new ArrayList<>(List.of(text("GpuBoard.hud.mouse.leftClick"),
              text(pending != 0 ? "GpuBoard.hud.hint.completeLos" : planning ? "GpuBoard.hud.hint.planMove"
                    : targeting ? "GpuBoard.hud.hint.chooseTarget" : "GpuBoard.hud.hint.select")));
        if (planning && pending == 0) {
            items.addAll(List.of(text("GpuBoard.hud.mouse.ctrlClick"), text("GpuBoard.hud.hint.waypoint"),
                  text("GpuBoard.hud.mouse.shiftClick"), text("GpuBoard.hud.hint.orientation")));
        }
        if (inputs.view().tactical()) {
            items.addAll(List.of(text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.panMap"),
                  text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.rotate"),
                  text("GpuBoard.hud.mouse.wheel"), text("GpuBoard.hud.hint.zoom"),
                  key(preferences, KeyCommandBind.TACTICAL_OVERLAYS), text("GpuBoard.hud.hint.overlays"),
                  key(preferences, KeyCommandBind.TOGGLE_ISO), text("GpuBoard.hud.util.backTo3d")));
        } else {
            items.addAll(List.of(text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.pan"),
                  text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.orbit")));
        }
        return items;
    }

    /** Free Flight's name and keys, from its help text ("Free Flight: WASD move | …"). */
    private static List<String> flight() {
        String help = text("GpuBoard.firstPersonHelp");
        int colon = help.indexOf(": ");
        return colon < 0 ? List.of(text("GpuBoard.firstPerson"), help)
              : List.of(help.substring(0, colon), help.substring(colon + 2));
    }

    /** The current key text of a bind ({@code KeyCommandBind.getDesc}), as the settings dialog shows it. */
    static String key(GpuBoardSource.UiPreferences preferences, KeyCommandBind command) {
        return preferences.binds().stream().filter(bind -> bind.command() == command)
              .map(GpuBoardSource.Bind::text).findFirst().orElse("");
    }
}
