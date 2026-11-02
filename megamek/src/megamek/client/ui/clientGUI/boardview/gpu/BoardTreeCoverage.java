/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import javax.imageio.ImageIO;

import com.badlogic.gdx.math.Polygon;
import megamek.common.Configuration;
import megamek.common.board.BoardModelBounds;
import megamek.common.board.ConcreteFit;
import megamek.logging.MMLogger;

/**
 * Visual cover of the chosen plants, measured once when a scene is captured, independent of camera and LOD.
 * The existing impostor's third panel is an unlit top projection baked from LOD0. Its alpha retains large crown gaps;
 * closing two-percent gaps groups tiny leaf holes into a crown. Cacti use their rotated model XY box instead.
 * Bare trees contribute about fifteen percent visual cover at their normal width; neither twig pixels nor their
 * entire branch envelope gives a readable population. Their contributions accumulate instead of filling twig gaps.
 */
final class BoardTreeCoverage {
    private static final MMLogger LOGGER = MMLogger.create(BoardTreeCoverage.class);
    private static final int GRID = 32;
    /** Matches the panel margin in tools/prepare_tree_lods.py; dimensions come from the same LOD0 model. */
    private static final float PANEL_MARGIN = 1.04f;
    private static final Map<File, Crown> CROWNS = new ConcurrentHashMap<>();
    private final BitSet available = new BitSet(GRID * GRID);
    private final BitSet covered = new BitSet(GRID * GRID);
    private float branches;

    record Addition(BitSet pixels, float branches) {
        boolean isEmpty() { return pixels.isEmpty() && branches == 0; }
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

    static float target(int density) { return density >= 3 ? .9f : density == 2 ? .6f : .3f; }

    /** Caps bound both scene cost and the population of sparse, narrow forms; they never resize an ordinary plant. */
    static int limit(int density, boolean cactus) {
        return cactus ? (density >= 3 ? 20 : density == 2 ? 12 : 6) : (density >= 3 ? 36 : density == 2 ? 20 : 8);
    }

    static boolean bare(String asset) { return asset.startsWith("vegetation/tree-dead"); }
    static int bareLimit(int density) { return Math.clamp(density, 1, 3) * 2; }

    float fraction() { return fraction(covered.cardinality(), branches); }
    float with(Addition addition) { return fraction(covered.cardinality() + addition.pixels.cardinality(), branches + addition.branches); }
    private float fraction(int count, float bare) { return available.isEmpty() ? 0 : Math.min(1, (float) count / available.cardinality() + bare); }
    void add(Addition addition) { covered.or(addition.pixels); branches += addition.branches; }

    /** Leafy crowns count only new ground inside the hex; bare trees use a bounded visual contribution. */
    Addition addition(BoardScene.Feature plant) {
        var result = new BitSet(GRID * GRID);
        if (bare(plant.asset())) {
            float width = plant.scale() / 1.7f;
            return new Addition(result, available.isEmpty() ? 0 : .15f * width * width * (float) (plant.stretch().x() * plant.stretch().y()));
        }
        Crown crown = crown(plant.asset());
        float scale = scale(plant, crown.bounds[5] - crown.bounds[2]);
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

    /** Conservative solid-body outline, including the raster edge margin, for rigid fungal and Martian forms. */
    static Polygon outline(BoardScene.Feature plant) {
        Crown crown = crown(plant.asset());
        Polygon polygon = new Polygon(crown.outline.clone());
        float scale = scale(plant, crown.bounds[5] - crown.bounds[2]);
        polygon.setScale(scale * (float) plant.stretch().x(), scale * (float) plant.stretch().y());
        polygon.setRotation(plant.rotation());
        polygon.setPosition(plant.x(), plant.y());
        return polygon;
    }

    /** Keeping a rigid outline inside its own hex also prevents intersections with neighbouring hexes. */
    static boolean inside(Polygon polygon) {
        float[] points = polygon.getTransformedVertices();
        for (int i = 0; i < points.length; i += 2) {
            if (!inside(points[i], points[i + 1], .3f)) { return false; }
        }
        return true;
    }

    private static boolean inside(float x, float y, float margin) {
        float w = BoardGeometry.TILE_WIDTH, h = BoardGeometry.TILE_HEIGHT;
        return Math.abs(y) <= h / 2 - margin
              && h / 2 * Math.abs(x) + w / 4 * Math.abs(y) <= w * h / 4 - margin * Math.hypot(h / 2, w / 4);
    }

    private static float sampleX(int column) { return ((column + .5f) / GRID - .5f) * BoardGeometry.TILE_WIDTH; }
    private static float sampleY(int row) { return ((row + .5f) / GRID - .5f) * BoardGeometry.TILE_HEIGHT; }

    private record Crown(float[] bounds, boolean[][] mask, float span, float[] outline) {
        boolean contains(float x, float y) {
            if (mask == null) { return x >= bounds[0] && x <= bounds[3] && y >= bounds[1] && y <= bounds[4]; }
            int px = (int) Math.floor(((x - (bounds[0] + bounds[3]) * .5f) / span + .5f) * mask.length);
            int py = (int) Math.floor((.5f - (y - (bounds[1] + bounds[4]) * .5f) / span) * mask.length);
            return px >= 0 && px < mask.length && py >= 0 && py < mask.length && mask[py][px];
        }
    }

    private static Crown crown(String asset) {
        File image = new File(Configuration.dataDir(), "models/board/textures/vegetation/impostors/"
              + asset.substring(asset.lastIndexOf('/') + 1) + ".png").getAbsoluteFile();
        return CROWNS.computeIfAbsent(image, file -> read(asset, file));
    }

    private static Crown read(String asset, File file) {
        float[] bounds = BoardModelBounds.bounds(asset);
        if (bounds == null) { bounds = new float[] { -3, -3, 0, 3, 3, 30 }; }
        float[] box = { bounds[0], bounds[1], bounds[3], bounds[1], bounds[3], bounds[4], bounds[0], bounds[4] };
        float span = Math.max(bounds[3] - bounds[0], bounds[4] - bounds[1]) * PANEL_MARGIN;
        boolean cactus = asset.startsWith("vegetation/cactus");
        try {
            var image = ImageIO.read(file);
            if (image == null || image.getWidth() != image.getHeight() * 3) { throw new IOException("Expected three square panels"); }
            int size = image.getHeight();
            boolean[][] mask = new boolean[size][size];
            List<ConcreteFit.Point> edge = new ArrayList<>();
            for (int y = 0; y < size; y++) {
                int left = size, right = -1;
                for (int x = 0; x < size; x++) {
                    int alpha = image.getRGB(x + size * 2, y) >>> 24;
                    mask[y][x] = alpha >= 128;
                    if (alpha > 0) { left = Math.min(left, x); right = x; }
                }
                if (right < left || !BoardFeatures.solid(asset) && !cactus) { continue; }
                // Include full pixels plus one pixel outside the antialiased silhouette, not just pixel centres.
                for (float x : new float[] { left - 1, right + 2 }) {
                    for (float row : new float[] { y - 1, y + 2 }) {
                        edge.add(new ConcreteFit.Point((bounds[0] + bounds[3]) * .5f + (x / size - .5f) * span,
                              (bounds[1] + bounds[4]) * .5f + (.5f - row / size) * span));
                    }
                }
            }
            float[] outline = box;
            if (!edge.isEmpty()) {
                var hull = ConcreteFit.hull(edge);
                outline = new float[hull.size() * 2];
                for (int i = 0; i < hull.size(); i++) { outline[i * 2] = hull.get(i).x(); outline[i * 2 + 1] = hull.get(i).y(); }
            }
            int radius = Math.max(1, Math.round(size * .02f));
            return new Crown(bounds, cactus ? null : filter(filter(mask, radius, true), radius, false), span, outline);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Cannot measure crown {}: {}; using model XY bounds", asset, failure.getMessage());
            return new Crown(bounds, null, span, box);
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
