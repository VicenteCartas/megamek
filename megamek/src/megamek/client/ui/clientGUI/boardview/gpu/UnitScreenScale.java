/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;

/**
 * while a hex is at least {@link #THRESHOLD} HUD pixels wide on screen a unit is drawn at its size; zoomed out further it grows as the
 * hex shrinks, keeping the size it had on screen at the threshold, up to {@link #MAX} times. The growth multiplies the
 * placed instance on every axis, on top of {@link UnitFamilyScale}, so meshes keep their canonical size and
 * proportions, and picking, bounds, anchors, attack points and the route ghost follow the grown instance. The Tactical
 * View's icons keep their size in the hex.
 */
final class UnitScreenScale {
    /** Whether units grow when zoomed out; the tuning panel switches it while the board runs. */
    static final boolean ENABLED = true;
    /** The hex width on screen, in HUD pixels, below which units grow. */
    static final float THRESHOLD = 80;
    /** The largest growth, hud-v3's twice the size. */
    static final float MAX = 2;

    // The values in use: render-thread state that GpuBoardTuning's controls edit and its Defaults restores.
    static boolean enabled = ENABLED;
    static float threshold = THRESHOLD;
    static float max = MAX;

    private UnitScreenScale() { }

    /**
     * A hex's width on screen in HUD pixels, for a board camera at {@code zoom} world units per window pixel and a HUD
     * of {@code scale} window pixels per HUD pixel: BoardCamera keeps their product as the zoom across display scales.
     */
    static float hexPixels(float zoom, float scale) {
        return BoardGeometry.WIDTH / (zoom * scale);
    }

    /** The growth for a hex {@code hexPixels} HUD pixels wide, at the values in use. */
    static float factor(float hexPixels) {
        return factor(hexPixels, enabled, threshold, max);
    }

    /**
     * 1 while the growth is off or a hex is at least {@code threshold} pixels wide; below that, {@code threshold /
     * hexPixels}, which keeps a unit as large on screen as at the threshold, but at most {@code max} and never below 1.
     */
    static float factor(float hexPixels, boolean enabled, float threshold, float max) {
        if (!enabled || !(hexPixels < threshold)) {
            return 1;
        }
        return Math.max(1, Math.min(max, threshold / hexPixels));
    }

    /**
     * The growth of {@code unit} at {@code factor}: {@code factor}, but 1 for a sensor contact, one part of a large
     * unit and a unit across several hexes, which keep their size: their hexes show them. Its animation strides and
     * turns its wheels over that much more ground.
     */
    static float growth(BoardScene.Unit unit, float factor) {
        return unit.sensorContact() || unit.part() >= 0 || unit.footprint().size() > 1 ? 1 : factor;
    }

    /**
     * Grows a just-placed unit by its {@link #growth} on every axis about its placement origin, its feet, before its
     * ground contact settles, and returns its anchor: the top of its posed bounds above that origin, {@code anchor} as
     * placed, grows with it; a unit that keeps its size keeps {@code anchor} itself.
     */
    static Vector3 grow(BoardScene.Unit unit, ModelInstance instance, Vector3 anchor, float factor) {
        float growth = growth(unit, factor);
        if (growth == 1) {
            return anchor;
        }
        instance.transform.scale(growth, growth, growth);
        Vector3 origin = instance.transform.getTranslation(new Vector3());
        return new Vector3(anchor).sub(origin).scl(growth).add(origin);
    }
}
