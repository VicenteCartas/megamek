/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import javax.imageio.ImageIO;

import megamek.common.Configuration;
import megamek.common.board.BoardModelBounds;
import megamek.logging.MMLogger;

/**
 * Visual cover of the chosen plants, measured once when a scene is captured, independent of camera and LOD.
 * A plant's crown mask ({@code <name>_crown.png}, mm-data's tools/prepare_tree_lods.py) is its far mesh's coverage
 * from straight above, framed like its XY bounds here. Its alpha retains large crown gaps;
 * closing two-percent gaps groups tiny leaf holes into a crown. Cacti use 85% of their XY box dimensions,
 * allowing some space between arms without treating that entire empty rectangle as foliage.
 * Bare trees contribute about fifteen percent visual cover at their normal width; neither twig pixels nor their
 * entire branch envelope gives a readable population. Their contributions accumulate instead of filling twig gaps.
 */
final class BoardTreeCoverage {
    private static final MMLogger LOGGER = MMLogger.create(BoardTreeCoverage.class);
    private static final int GRID = 32;
    /** Matches the crown mask's margin in tools/prepare_tree_lods.py, about the plant's bounds. */
    private static final float PANEL_MARGIN = 1.04f;
    private static final Map<File, Crown> CROWNS = new ConcurrentHashMap<>();
    private final BitSet available = new BitSet(GRID * GRID);
    private final BitSet covered = new BitSet(GRID * GRID);
    private float credit;

    record Addition(BitSet pixels, float credit) {
        boolean isEmpty() { return pixels.isEmpty() && credit == 0; }
    }

    /** Reload both source geometry and its matching top projection before the next scene capture. */
    static void reload() { BoardModelBounds.reload(); CROWNS.clear(); BoardPlantFootprint.reload(); }

    BoardTreeCoverage(BiPredicate<Float, Float> clear) {
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                float px = sampleX(x), py = sampleY(y);
                if (inside(px, py, 0) && clear.test(px, py)) { available.set(y * GRID + x); }
            }
        }
    }

    static float target(int density) { return density >= 3 ? 1 : density == 2 ? .75f : .3f; }

    /** Population limits take priority over coverage targets; ordinary plants are never resized to meet either. */
    static int canopyLimit(int density) { return Math.clamp(density, 1, 3) * 3; }
    static int cactusLimit(int density) { return density >= 3 ? 12 : density == 2 ? 8 : 3; }

    /** Rigid colonies retain their independent collision-safe packing budget. */
    static int colonyLimit(int density) { return density >= 3 ? 36 : density == 2 ? 20 : 8; }

    static boolean bare(String asset) { return asset.startsWith("vegetation/tree-dead"); }
    static int bareLimit(int density) { return Math.clamp(density, 1, 3) * 2; }

    /** Whole authored crown at the supplied scale, before placement, overlap or hex-edge clipping. */
    static float crownFraction(String asset, float scale) {
        return crown(asset).area * scale * scale / (BoardGeometry.TILE_WIDTH * BoardGeometry.TILE_HEIGHT * .75f);
    }

    float fraction() { return fraction(covered.cardinality(), credit); }
    float with(Addition addition) { return fraction(covered.cardinality() + addition.pixels.cardinality(), credit + addition.credit); }
    private float fraction(int count, float sparse) { return available.isEmpty() ? 0 : Math.min(1, (float) count / available.cardinality() + sparse); }
    void add(Addition addition) { covered.or(addition.pixels); credit += addition.credit; }

    /** Leafy crowns count only new ground inside the hex; bare trees use a bounded visual contribution. */
    Addition addition(BoardScene.Feature plant) {
        var result = new BitSet(GRID * GRID);
        if (bare(plant.asset())) {
            float width = plant.scale() / 1.7f;
            return new Addition(result, available.isEmpty() ? 0 : .15f * width * width * (float) (plant.stretch().x() * plant.stretch().y()));
        }
        Crown crown = crown(plant.asset());
        float scale = scale(plant, crown.bounds[5] - crown.bounds[2]) * (plant.asset().startsWith("vegetation/cactus") ? .85f : 1);
        double angle = Math.toRadians(plant.rotation()), cos = Math.cos(angle), sin = Math.sin(angle);
        for (int i = available.nextSetBit(0); i >= 0; i = available.nextSetBit(i + 1)) {
            if (covered.get(i)) { continue; }
            float dx = sampleX(i % GRID) - plant.x(), dy = sampleY(i / GRID) - plant.y();
            float x = (float) ((dx * cos + dy * sin) / (scale * plant.stretch().x()));
            float y = (float) ((dy * cos - dx * sin) / (scale * plant.stretch().y()));
            if (crown.contains(x, y)) { result.set(i); }
        }
        return new Addition(result, 0);
    }

    /** XY scale in model hex units, shared with the renderer; Mars embeds six percent of its source height. */
    static float scale(BoardScene.Feature plant, float sourceHeight) {
        boolean coral = plant.asset().startsWith("mars/");
        return coral || plant.asset().startsWith("fungus/") || plant.asset().startsWith("vegetation/foliage-")
              ? plant.height() * BoardGeometry.level() / BoardGeometry.hexScale() / (sourceHeight * (coral ? .94f : 1)) : plant.scale();
    }

    private static boolean inside(float x, float y, float margin) {
        float w = BoardGeometry.TILE_WIDTH, h = BoardGeometry.TILE_HEIGHT;
        return Math.abs(y) <= h / 2 - margin
              && h / 2 * Math.abs(x) + w / 4 * Math.abs(y) <= w * h / 4 - margin * Math.hypot(h / 2, w / 4);
    }

    private static float sampleX(int column) { return ((column + .5f) / GRID - .5f) * BoardGeometry.TILE_WIDTH; }
    private static float sampleY(int row) { return ((row + .5f) / GRID - .5f) * BoardGeometry.TILE_HEIGHT; }

    private record Crown(float[] bounds, boolean[][] mask, float span, float area) {
        private Crown(float[] bounds, boolean[][] mask, float span) {
            this(bounds, mask, span, area(bounds, mask, span));
        }

        private static float area(float[] bounds, boolean[][] mask, float span) {
            if (mask == null) { return (bounds[3] - bounds[0]) * (bounds[4] - bounds[1]); }
            int pixels = 0;
            for (var row : mask) {
                for (boolean covered : row) { if (covered) { pixels++; } }
            }
            return span * span * pixels / (mask.length * mask.length);
        }

        boolean contains(float x, float y) {
            if (mask == null) { return x >= bounds[0] && x <= bounds[3] && y >= bounds[1] && y <= bounds[4]; }
            int px = (int) Math.floor(((x - (bounds[0] + bounds[3]) * .5f) / span + .5f) * mask.length);
            int py = (int) Math.floor((.5f - (y - (bounds[1] + bounds[4]) * .5f) / span) * mask.length);
            return px >= 0 && px < mask.length && py >= 0 && py < mask.length && mask[py][px];
        }
    }

    private static Crown crown(String asset) {
        File image = new File(Configuration.dataDir(), "models/board/textures/vegetation/impostors/"
              + asset.substring(asset.lastIndexOf('/') + 1) + "_crown.png").getAbsoluteFile();
        return CROWNS.computeIfAbsent(image, file -> read(asset, file));
    }

    private static Crown read(String asset, File file) {
        float[] bounds = BoardModelBounds.bounds(asset);
        if (bounds == null) { bounds = new float[] { -3, -3, 0, 3, 3, 30 }; }
        float span = Math.max(bounds[3] - bounds[0], bounds[4] - bounds[1]) * PANEL_MARGIN;
        if (asset.startsWith("vegetation/cactus")) { return new Crown(bounds, null, span); }
        try {
            var image = ImageIO.read(file);
            if (image == null || image.getWidth() != image.getHeight()) { throw new IOException("Expected a square mask"); }
            int size = image.getHeight();
            boolean[][] mask = new boolean[size][size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    mask[y][x] = alpha >= 128;
                }
            }
            int radius = Math.max(1, Math.round(size * .02f));
            return new Crown(bounds, filter(filter(mask, radius, true), radius, false), span);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Cannot measure crown {}: {}; using model XY bounds", asset, failure.getMessage());
            return new Crown(bounds, null, span);
        }
    }

    private static boolean[][] filter(boolean[][] source, int radius, boolean dilate) {
        int size = source.length;
        boolean[][] result = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean value = !dilate;
                search: for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        boolean pixel = source[Math.clamp(y + dy, 0, size - 1)][Math.clamp(x + dx, 0, size - 1)];
                        if (pixel == dilate) { value = dilate; break search; }
                    }
                }
                result[y][x] = value;
            }
        }
        return result;
    }
}
