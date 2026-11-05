/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * The Display page's Quality levels: one row of what each level changes. Anything new that differs between levels gets a
 * column here, and its code reads the column instead of asking which level it is. Quality leaves the render resolution
 * alone: that is the player's own choice.
 */
enum BoardQuality {
    //      label     shading options detail anisotropy                     meadow crops  half-rate waves
    LOW(    "Low",    true,   false,  3,     2,                             0,     .75f,  true),
    NORMAL( "Normal", true,   false,  3,     2,                             .6f,   1,     true),
    HIGH(   "High",   false,  true,   1,     GpuAssets.MATERIAL_ANISOTROPY, 1,     1,     false);

    /**
     * The level the open view draws at: BoardDisplaySettings.apply sets it each frame before terrain preparation, and the
     * view leaves High behind when it closes.
     */
    static BoardQuality current = HIGH;

    private final String label;
    /**
     * Cheaper light and materials compiled in as normalQualityFlag, with the relief depth pre-pass they rely on. A switch
     * that changes this is a lighting change, behind the loading stage. Levels that agree here share their programs, so
     * they must also agree on {@link #detailScale} (terrain-crops.glsl); their other columns need no new program.
     */
    final boolean cheaperShading;
    /**
     * Whether the player's High shadows, specular anti-aliasing, parallax, tessellation and ambient occlusion count. Without
     * them the controls are disabled and keep their choices (BoardDisplaySettings, GpuBoardTuning).
     */
    final boolean costlyOptions;
    /** Terrain takes each detail level at this many times the hex's size (TerrainLod.select): small triangles shade the most. */
    final float detailScale;
    /** The sculpted terrain's material filtering; the board's isometric view asks for about 2x. */
    final float terrainAnisotropy;
    /** The share of a meadow's blades drawn, each survivor by its root wider. At 0 no grass or bank rim draws at all. */
    final float meadowShare;
    /** The share of a field's plants drawn, each survivor wider by as much (terrain-grass.glsl). */
    final float cropShare;
    /** Waves advance every other frame, and not at all while no water shows; they follow the clock. */
    final boolean halfRateWaves;

    BoardQuality(String label, boolean cheaperShading, boolean costlyOptions, float detailScale, float terrainAnisotropy,
          float meadowShare, float cropShare, boolean halfRateWaves) {
        this.label = label;
        this.cheaperShading = cheaperShading;
        this.costlyOptions = costlyOptions;
        this.detailScale = detailScale;
        this.terrainAnisotropy = terrainAnisotropy;
        this.meadowShare = meadowShare;
        this.cropShare = cropShare;
        this.halfRateWaves = halfRateWaves;
    }

    /** The level a saved preference names; an unknown or missing one is Normal, the game's default. */
    static BoardQuality parse(String saved) {
        for (BoardQuality level : values()) {
            if (level.name().equalsIgnoreCase(saved)) { return level; }
        }
        return NORMAL;
    }

    /** As the Quality choice lists it. */
    @Override
    public String toString() {
        return label;
    }
}
