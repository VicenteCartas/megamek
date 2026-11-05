/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Screen-pixel budgets for the shared orthographic board, independent of orbit and camera distance. Trees have four
 * levels: the near mesh, two simpler meshes and, below the last threshold, the impostor cards. Buildings and effect
 * volumes keep the three-level thresholds.
 */
final class TreeLod {
    private static final float[] PIXELS = { 80, 48, 24 };
    static final float[] PROPS = { 80, 24 };
    /** Placed decorative objects whose world-box diagonal is under this many hex widths are small (GpuTerrain.Prop.small). */
    static final float SMALL_OBJECT = .25f;
    /** Small objects hide while a hex is narrower than this on screen, in framebuffer pixels; {@link #level} adds ±10 %. */
    static final float[] SMALL_OBJECTS = { 12 };
    /**
     * Building shells join the depth pre-pass while a hex is at least this wide on screen (GpuTerrain.twin): further out
     * their twins cost more than the shading they save. On an Iris Xe at 1080p, 29-pixel hexes (the 48 by 48 city
     * fitted) saved 0.5 to 0.9 ms, within the rounds' spread, and 9.5-pixel ones (Valencia City fitted) cost 0.7 ms of
     * pre-pass and saved nothing; this lies near their geometric mean, untuned.
     */
    static final float[] PREPASS_OBJECTS = { 16 };
    /** Normal quality's relief joins the depth pre-pass while a hex is narrower than this on screen (GpuTerrain.twin). */
    static final float[] PREPASS_RELIEF = { 96 };
    static final int LEVELS = PIXELS.length + 1;
    private static final float HYSTERESIS = 0.1f;

    private TreeLod() { }

    /** A conservative projected bounding diameter, measured in framebuffer pixels. */
    static int level(float pixels, int previous) {
        return level(PIXELS, pixels, previous);
    }

    /** The level for a diameter against descending thresholds, changing only past each threshold's hysteresis. */
    static int level(float[] thresholds, float pixels, int previous) {
        int level = previous;
        while (level < thresholds.length && pixels < thresholds[level] * (1 - HYSTERESIS)) {
            level++;
        }
        while (level > 0 && pixels > thresholds[level - 1] * (1 + HYSTERESIS)) {
            level--;
        }
        return level;
    }

    static String asset(String name, int level) {
        return MeshLod.name(name, level);
    }
}
