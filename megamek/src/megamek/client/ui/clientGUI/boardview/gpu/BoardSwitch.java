/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * The board view's quality switches: every feature whose cost or look is a choice, defined once with its label, help,
 * range, its value in each preset ({@link BoardSwitches.Preset}) and how a change reaches the GPU. A set of values is a
 * {@link BoardSwitches}; Low, Medium and High are named sets, any other is Custom. Code reads a switch from the set it was
 * handed, never a quality level. The constants' order is the Display page's order and the order of the defines in a
 * program's prefix (GpuCloudShadow.prefix).
 */
enum BoardSwitch {
    // label, min, max, step, its value in Low, Medium and High, how a change applies, its define, help.
    HIGH_SHADOWS("High shadows", 0, 1, 1, 0, 0, 0, Applies.LIVE, null,
          "The sun's shadow map follows the screen: about one shadow texel to a rendered pixel. High doubles that for "
                + "sharper shadow edges under rims, rocks and plants; it uses more video memory and takes longer to redraw "
                + "the shadows when the view zooms."),
    SPECULAR_ANTI_ALIASING("Specular anti-aliasing", 0, 1, 1, 0, 0, 1, Applies.DEFINE, "specularAntialiasingFlag",
          "Widens highlights where a surface's normals turn within a pixel, so fine relief does not sparkle. Switching "
                + "it rebuilds the board's shaders."),
    TERRAIN_PARALLAX("Terrain parallax", 0, 1, 1, 0, 0, 1, Applies.LIVE, null,
          "Moves the sculpted terrain's materials with the view by their heightmaps, so stones, cracks and joints stand "
                + "out of the ground as the camera turns. Independent of Normal maps; off is a little faster on rocky "
                + "ground and cliffs. With Cheaper shading on, natural ground takes none; pavement, rubble and the rock "
                + "kit still do."),
    TESSELLATION("Tessellation", 0, 1, 1, 0, 0, 0, Applies.BUILD, null,
          "Rounds the sculpted banks and cliffs between their mesh points, so their outlines and the shadows they cast "
                + "curve as the ground does. Up close it costs an integrated graphics chip a few milliseconds a frame. "
                + "Needs OpenGL 4.0. Switching it rebuilds the board."),
    AMBIENT_OCCLUSION("Ambient occlusion", 0, 1, 1, 0, 0, 0, Applies.PROGRAMS, null,
          "Darkens the sky light where the ground and the trees hide the sky: cliff feet, hollows, the ground around "
                + "trunks. Sunlight is unchanged; buildings and units take no part. Switching it rebuilds the board's "
                + "shaders."),
    CHEAPER_SHADING("Cheaper shading", 0, 1, 1, 1, 1, 0, Applies.DEFINE, "mediumQualityFlag",
          "Medium's cheaper light and ground materials: one shadow comparison, an analytic sky and highlight, the "
                + "ground's first repeat and the mean finish of its maps. Switching it rebuilds the board's shaders."),
    RELIEF_PREPASS("Relief depth pre-pass", 0, 1, 1, 1, 1, 0, Applies.PROGRAMS, null,
          "Draws the relief's depth before its colour where hexes are small on screen and in perspective views, so the "
                + "ground nearer hides is not shaded. Switching it rebuilds the board's shaders."),
    DETAIL_SCALE("Terrain detail scale", 1, 4, .5f, 3, 3, 1, Applies.LIVE, null,
          "Terrain takes each detail level at this many times a hex's size on screen: higher draws coarser terrain "
                + "sooner, which is faster. Chunks take a new level as they come back into view."),
    TERRAIN_ANISOTROPY("Terrain filtering", 1, 16, 1, 2, 2, GpuAssets.MATERIAL_ANISOTROPY, Applies.LIVE, null,
          "Anisotropic filtering of the sculpted ground's materials: sharper at grazing angles, slower when higher."),
    MEADOW_SHARE("Meadow grass", 0, 1, .05f, 0, .6f, 1, Applies.LIVE, null,
          "The share of a meadow's grass blades drawn, each one wider by the root of the rest. At 0 no grass blade or "
                + "bank rim draws at all."),
    CROP_SHARE("Field plants", 0, 1, .05f, .75f, 1, 1, Applies.LIVE, null,
          "The share of a field's plants drawn, each one wider by as much."),
    HALF_RATE_WAVES("Waves at half rate", 0, 1, 1, 1, 1, 0, Applies.LIVE, null,
          "Waves advance every other frame, and not at all while no water shows; they follow the clock.");

    /** How a change reaches the GPU. */
    enum Applies {
        /** Compiled in as {@link #define}: part of the lighting's program key, so a change goes behind the loading stage. */
        DEFINE,
        /** No define, but it changes which programs draws take (depth twins, a pass's inputs): part of the key too. */
        PROGRAMS,
        /** Rebuilds the terrain behind the loading stage. */
        BUILD,
        /** A uniform or a CPU value: the next frame. */
        LIVE
    }

    final String label;
    final String help;
    final float min, max, step;
    /** The value in Low, Medium and High, by {@link BoardSwitches.Preset} ordinal. */
    final float[] presets;
    final Applies applies;
    /** The define a {@link Applies#DEFINE} switch compiles in while on; null for the others. */
    final String define;

    BoardSwitch(String label, float min, float max, float step, float low, float medium, float high, Applies applies,
          String define, String help) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.presets = new float[] { low, medium, high };
        this.applies = applies;
        this.define = define;
        this.help = help;
    }

    /** On or off, rather than a value. */
    boolean toggle() { return min == 0 && max == 1 && step == 1; }

    /** Whether a change selects other programs: the switch is part of a program key (BoardSwitches#programKey). */
    boolean keyed() { return applies == Applies.DEFINE || applies == Applies.PROGRAMS; }
}
