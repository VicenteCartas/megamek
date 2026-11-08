/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Render sampling only: the board's dimensions never choose terrain quality. */
enum TerrainLod {
    FULL(6, 1, true),
    MEDIUM(3, 2, true),
    COARSE(2, 3, false),
    DISTANT(1, 6, false);

    static final int CHUNK_SIZE = 8;
    private static final TerrainLod[] LEVELS = values();
    record Tuning(int fullPixels, int mediumPixels) {
        Tuning {
            if (mediumPixels < 1 || fullPixels < mediumPixels) { throw new IllegalArgumentException("Invalid terrain detail"); }
        }

        float threshold(int level) {
            return switch (level) {
                case 0 -> fullPixels;
                case 1 -> mediumPixels;
                default -> mediumPixels * .25f;
            };
        }
    }
    static final Tuning DEFAULTS = new Tuning(64, 24);
    private static Tuning tuning = DEFAULTS;
    static Tuning tuning() { return tuning; }
    /** Test hook. Threshold changes select cached meshes; they do not change the terrain's shape or invalidate its geometry. */
    static void tune(Tuning next) { tuning = next; }
    final int steps;
    final int rowStride;
    final boolean dressing;

    TerrainLod(int steps, int rowStride, boolean dressing) {
        this.steps = steps;
        this.rowStride = rowStride;
        this.dressing = dressing;
    }

    /** Whether the level asks for medium detail late ({@link BoardQuality#detailScale}): coarse carries what medium shows. */
    static boolean lateDetail() {
        return BoardQuality.current.detailScale > 1;
    }

    /**
     * Whether this detail carries the ground's small formations (rocks, planting pits): from medium, or coarse on a late
     * level, whose first close zoom would otherwise wait for each chunk's rebuild. They stand in the same places at every
     * detail.
     */
    boolean formations() {
        return dressing || this == COARSE && lateDetail();
    }

    /** Nearest-point projected hex width, with separate refinement/coarsening thresholds to avoid oscillation. */
    static TerrainLod select(float pixels, TerrainLod current) {
        pixels /= BoardQuality.current.detailScale;
        int level = current == null ? 3 : current.ordinal();
        while (level > 0 && pixels >= tuning.threshold(level - 1) * (current == null ? 1 : 1.1f)) { level--; }
        while (level < 3 && pixels < tuning.threshold(level) * .9f) { level++; }
        return LEVELS[level];
    }

    static boolean sameChunk(Coords a, Coords b) {
        return a.getX() / CHUNK_SIZE == b.getX() / CHUNK_SIZE && a.getY() / CHUNK_SIZE == b.getY() / CHUNK_SIZE;
    }
}
