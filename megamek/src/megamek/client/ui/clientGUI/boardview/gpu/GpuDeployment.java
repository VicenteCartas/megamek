/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.interfaces.IStartingPositions;

/** What the deployment HUD tells of a unit: where it stands in deployment, and its facts and gauges. */
final class GpuDeployment {
    private GpuDeployment() { }

    /** The unit stands on the board and has no deployment turn left: deployed, not merely tried on a hex. */
    static boolean placed(UnitStatus unit) {
        return unit.position() != null && !unit.pending();
    }

    /** The unit deploys in a later round: it has neither a hex nor a turn in this one. */
    static boolean later(UnitStatus unit) {
        return unit.position() == null && !unit.pending() && !walksOnLater(unit);
    }

    /**
     * Under walk-on deployment the unit enters from its edge in a movement phase instead of being placed now: it has
     * no hex and this turn does not take it.
     */
    static boolean walksOnLater(UnitStatus unit) {
        return unit.walksOn() && unit.position() == null && !unit.canActNow() && !unit.pending();
    }

    /** "Walk-on W · R1", what the forces list says of a unit that walks on later: its edge and round. */
    static String walkOnLine(UnitStatus unit) {
        return text("GpuBoard.hud.deploy.walkOn", IStartingPositions.getDisplayName(unit.walkOnZone()),
              unit.walkOnRound());
    }

    /**
     * The unit deploys this round, but not in the local player's next turn, which takes other units (aerospace units
     * deploy after the ground units, infantry and ProtoMeks moving later after the player's others).
     */
    static boolean waits(UnitStatus unit) {
        return unit.deployWait() != GpuBattleStatus.Wait.NONE;
    }

    /**
     * In the local deployment turn, an own unit this turn cannot deploy: one that waits for a later turn or round,
     * walks on, rides a carrier or is placed. It is no unit to pick, so a click leaves the deployment's unit as it is;
     * the unit menu still views it.
     */
    static boolean unpickable(GpuBattleStatus.Snapshot status, UnitStatus unit) {
        return status.myTurn() && status.phase().isDeployment() && unit.side() == GpuBattleStatus.Side.OWN
              && !unit.destroyed() && !GpuHud.acts(status, unit);
    }

    /** A unit's chassis and model, as the map's peek heads it. */
    static String title(UnitStatus unit) {
        return unit.chassis() + (unit.model().isEmpty() ? "" : " " + unit.model());
    }

    /** A unit's tonnage, pilot, skills and MP in one line. */
    static String facts(UnitStatus unit) {
        return text("GpuBoard.hud.deploy.cardFacts", text("GpuBoard.hud.deploy.tons", Math.round(unit.tons())),
              unit.pilot(), unit.gunnery() + "/" + unit.piloting(), moves(unit));
    }

    /** Walk, run and jump MP as the unit card writes them: "4 / 6", "4 / 6 / 4" with jump jets. */
    static String moves(UnitStatus unit) {
        return unit.walk() + " / " + unit.run() + (unit.jump() > 0 ? " / " + unit.jump() : "");
    }

    /**
     * A unit's gauge on the map's peek: the share of armor or internal structure left over a faint track, in its whole
     * colour until damaged and its damaged one after; nothing while unknown.
     */
    static final class ArmorBar extends Widget {
        private final UiKit ui;
        private final Color whole;
        private final Color damaged;
        float fraction = -1;

        ArmorBar(UiKit ui, Color whole, Color damaged) {
            this.ui = ui;
            this.whole = whole;
            this.damaged = damaged;
        }

        static ArmorBar armor(UiKit ui) {
            return new ArmorBar(ui, UiTheme.MINT, UiTheme.AMBER);
        }

        static ArmorBar structure(UiKit ui) {
            return new ArmorBar(ui, UiTheme.TEXT, UiTheme.CORAL);
        }

        /** Shows {@code value}, a share of 0 to 1 or negative when unknown. */
        void set(double value) {
            fraction = value < 0 ? -1 : (float) value;
        }

        @Override
        public float getPrefHeight() {
            return 3;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (fraction < 0) {
                return;
            }
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            ui.fill(batch, UiTheme.TRACK, alpha, getX(), getY(), getWidth(), getHeight());
            ui.fill(batch, fraction < .995f ? damaged : whole, alpha, getX(), getY(),
                  getWidth() * Math.min(1, fraction), getHeight());
            batch.setPackedColor(previous);
        }
    }
}
