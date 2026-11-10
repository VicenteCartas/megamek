/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/**
 * Clustered reeds in persistent per-chunk instanced batches, and the planting of a field's crops. Terrain workers plant
 * both with each chunk's finished support ({@link BoardPlants}); this class only shapes, batches and draws the reeds. A
 * field's crops are plants among the grass blades ({@link GpuGroundCover}), planted here ({@link #plantStalks}),
 * thinning out as they shrink while each survivor widens by as much, until the ground takes the stand's colour
 * (terrain-biome.glsl).
 */
final class GpuBiomeVegetation implements Disposable {
    /**
     * Sedge, with its finish: its material's (GpuAssets.authoredFinish) as the means of its maps, since its artwork is
     * composited at run time; the emission is the constant glow plus the map's mean times its tint. The value is the
     * batch's level of detail.
     */
    static final class Kind extends FloatAttribute {
        static final long TYPE = register("boardBiomeVegetation");
        final float[] surface, glow;

        Kind(int lod) {
            super(TYPE, lod);
            var finish = GpuAssets.authoredFinish(GpuMaterialMaps.board("vegetation/marsh-sedge"));
            surface = finish.surface().clone();
            glow = finish.glow().clone();
            if (finish.emission() != null) {
                float[] mean = GpuMaterialMaps.meanColor(finish.emission());
                for (int channel = 0; channel < 3; channel++) { glow[channel] += finish.tint()[channel] * mean[channel]; }
            }
        }

        private Kind(float lod, float[] surface, float[] glow) {
            super(TYPE, lod);
            this.surface = surface;
            this.glow = glow;
        }

        @Override
        public Kind copy() { return new Kind(value, surface, glow); }

        @Override
        public int hashCode() { return 31 * (31 * super.hashCode() + Arrays.hashCode(surface)) + Arrays.hashCode(glow); }

        @Override
        public int compareTo(com.badlogic.gdx.graphics.g3d.Attribute other) {
            int order = super.compareTo(other);
            if (order != 0 || type != other.type) { return order; }
            order = Arrays.compare(surface, ((Kind) other).surface);
            return order != 0 ? order : Arrays.compare(glow, ((Kind) other).glow);
        }
    }

    private static final float[] DENSITY = { 1, .35f, .08f };
    // Cumulative reed coverage: fine plants replace medium plants, which replace the distant stems. The same intervals
    // drive conservative CPU submission and the per-root GPU cross-fade.
    private static final float[] START_PIXELS = { 240, 64, 12 }, FULL_PIXELS = { 480, 128, 36 };
    private static final int STRIDE = 4;
    /** A crop plant's mean height in levels, to its tip, wheat and maize alike. */
    static final float CROP_HEIGHT = .9f;
    // A crop needs this much of the shared coverage field.
    private static final float CULTIVATED = .60f;
    // Obstacle clearance in metres: the tallest reed or crop with its sway.
    private static final float PLANT_HEIGHT = 2;
    /** Metres a maize plant's leaves reach across its row, which clears its parcel's headland, roads and obstacles. */
    static final float ROW_HALF_WIDTH = 1.44f;
    /** Metres between maize plants along a planted row. */
    static final float CROP_SPACING = 1.25f;
    /** Metres a wheat stalk's ear reaches from its root. */
    static final float STALK_REACH = .3f;
    /**
     * Projected pixels per hex, wheat's then maize's: every plant of a field stands above its keep; below, fewer stand
     * as they shrink, each survivor widening by as much (terrain-crops.glsl).
     */
    static final float[] CROP_KEEP = { 550, 160 };
    /**
     * Projected pixels per hex over which a field's plants fade into the ground's crop colour: they stand on medium and
     * full terrain detail (BoardPlants), which a chunk keeps down to TerrainLod's 24, and on a late level's coarse.
     */
    static final float[] CROP_FADE = { 24, 40 };

    /**
     * The shared crop source (terrain-crops.glsl) with the plants' height and hand-overs: every stage drawing a crop (its
     * plants and the ground beyond) includes it.
     */
    static String cropSource() {
        return "#define CROP_HEIGHT " + CROP_HEIGHT + "\n#define CROP_HEX_METRES "
              + BoardGeometry.width() / BoardRelief.metres(1) + "\n#define CROP_KEEP " + pair(CROP_KEEP)
              + "\n#define CROP_FADE " + pair(CROP_FADE) + "\n#define CROP_MEDIUM_SCALE " + BoardQuality.MEDIUM.detailScale
              + "\n" + GpuShaderSource.read("terrain-crops.glsl");
    }

    private static String pair(float[] values) { return "vec2(" + values[0] + ", " + values[1] + ")"; }

    /** The share of a crop's plants by rank that stands at this projected size, as terrain-crops.glsl draws them. */
    static float cropDensity(BoardFields.Crop crop, float pixels) {
        BoardQuality quality = BoardQuality.current;
        // Plants stand on the detail levels their quality takes at larger hexes (TerrainLod.select): they fade there.
        float fade = Math.clamp((pixels / quality.detailScale - CROP_FADE[0]) / (CROP_FADE[1] - CROP_FADE[0]), 0, 1);
        return quality.cropShare * Math.clamp(pixels / CROP_KEEP[crop.ordinal()], .001f, 1) * fade * fade * (3 - 2 * fade);
    }

    /** Plant a marsh hex's reeds up to the share of the lattice its chunk's detail draws, as (x, y, z, seed). */
    static FloatArray plantReeds(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface, float density,
          BoardDecals.Opacity paint) {
        var patch = new Patch(scene, tile, surface, paint);
        patch.prepare(scene, tile, density);
        patch.roots.shrink();
        return patch.roots;
    }

    /**
     * A field's plants, drawn by {@link GpuGroundCover}: (x, y, z, rank) in rank order, for one crop. Wheat stalks stand
     * anywhere in their parcels, as many candidates per hex as the grass's; maize plants stand on their parcel's planted
     * rows, {@link #CROP_SPACING} metres apart and staggered between rows, each with its own rank. A plant grows clear of its parcel's headland by its reach, where the field is cultivated and level and no
     * road, obstacle or paint is. Candidates are placed before any rejection, so a plant keeps its place and rank at
     * every detail. Null when none grows.
     */
    static FloatArray plantStalks(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface,
          BoardDecals.Opacity paint, BoardFields.Crop crop) {
        float metre = BoardRelief.metres(1);
        Coords coords = tile.coords();
        float[] xs = new float[6], ys = new float[6];
        for (int corner = 0; corner < 6; corner++) {
            var point = BoardGeometry.corner(coords, 0, corner);
            xs[corner] = point.x / metre;
            ys[corner] = point.y / metre;
        }
        long[] parcels = Arrays.stream(BoardFields.parcels(xs, ys))
              .filter(parcel -> BoardFields.crop(BoardFields.code(parcel)) == crop).toArray();
        if (parcels.length == 0) { return null; }
        var faces = support(tile, surface);
        if (faces.isEmpty()) { return null; }
        // Candidates as (x, y, rank), before any rejection.
        var candidates = new FloatArray();
        float centerX = BoardGeometry.centerX(coords), centerY = BoardGeometry.centerY(coords);
        if (crop == BoardFields.Crop.WHEAT) {
            float halfWidth = BoardGeometry.width() / 2, halfHeight = BoardGeometry.height() / 2;
            var random = new Random(coords.getX() * 0x2545F4914F6CDD1DL ^ coords.getY() * 0x9E3779B97F4A7C15L);
            for (int rank = 0; rank < GpuGroundCover.ROOTS_PER_HEX; rank++) {
                float x, y;
                do {
                    x = centerX + (random.nextFloat() * 2 - 1) * halfWidth;
                    y = centerY + (random.nextFloat() * 2 - 1) * halfHeight;
                } while (!BoardGeometry.contains(coords, x, y));
                candidates.addAll(x, y, rank);
            }
        } else {
            for (long parcel : parcels) {
                int code = BoardFields.code(parcel);
                float rowX = BoardFields.acrossX(code), rowY = BoardFields.acrossY(code);
                float u = (centerX * rowX + centerY * rowY) / metre, v = (centerY * rowX - centerX * rowY) / metre;
                // The planted rows are the ground's alternate furrows; the plants along them stagger by half a step.
                int first = (int) Math.floor((u - 18) / BoardBiome.ROW_METRES);
                first += first & 1;
                for (int ix = first; ix * BoardBiome.ROW_METRES <= u + 18; ix += 2) {
                    float stagger = ((ix / 2) & 1) * CROP_SPACING / 2;
                    for (int iy = (int) Math.floor((v - 18) / CROP_SPACING); iy * CROP_SPACING <= v + 18; iy++) {
                        float a = ix * BoardBiome.ROW_METRES + (BoardRelief.hash(ix * 7 + 1, iy * 3 - 5) - .5f) * .2f;
                        float b = iy * CROP_SPACING + stagger + (BoardRelief.hash(ix * 5 - 3, iy * 7 + 2) - .5f) * .3f;
                        float x = (a * rowX - b * rowY) * metre, y = (a * rowY + b * rowX) * metre;
                        // Each parcel plants only itself: a neighbouring maize parcel's rows run another way.
                        if (BoardGeometry.contains(coords, x, y) && BoardFields.parcel(x / metre, y / metre) == parcel) {
                            candidates.addAll(x, y, (int) (BoardRelief.hash(ix * 11 + 4, iy * 13 - 9) * GpuGroundCover.ROOTS_PER_HEX));
                        }
                    }
                }
            }
        }
        var ground = new Support(faces);
        var roads = BoardRoad.nearbyRoads(scene, tile);
        var obstacles = new BoardObstacles(scene, tile, true);
        float levelZ = BoardGeometry.groundZ(tile);
        var kind = BoardBiome.plantKind(scene, tile);
        boolean uniform = BoardVegetation.uniform(scene, tile, kind);
        float reach = crop == BoardFields.Crop.WHEAT ? STALK_REACH : ROW_HALF_WIDTH;
        var roots = new FloatArray();
        var root = new Vector3();
        for (int i = 0; i < candidates.size; i += 3) {
            float x = candidates.items[i], y = candidates.items[i + 1];
            long parcel = BoardFields.parcel(x / metre, y / metre);
            if (BoardFields.crop(BoardFields.code(parcel)) != crop
                  || BoardFields.inside(parcel, x / metre, y / metre) < BoardFields.HEADLAND + reach) { continue; }
            var face = ground.face(x, y);
            if (face == null || !level(face)) { continue; }
            float z = face.height(x, y);
            float cover = uniform && Math.abs(z - levelZ) <= .15f * metre ? 1 : BoardBiome.coverage(scene, kind, x, y, z);
            if (paint != null && cover > 0) { cover *= 1 - paint.at(x, y); }
            if (cover < CULTIVATED || BoardRoad.distance(roads, x, y) < BoardRoad.SHOULDER + 1) { continue; }
            if (!obstacles.isEmpty() && obstacles.obstructs(root.set(x, y, z), reach * metre, PLANT_HEIGHT * metre)) {
                continue;
            }
            roots.addAll(x, y, z - .018f * metre, candidates.items[i + 2]);
        }
        if (roots.isEmpty()) { return null; }
        // Wheat's candidates come in rank order already.
        if (crop == BoardFields.Crop.MAIZE) { sortByRank(roots); }
        roots.shrink();
        return roots;
    }

    /** Roots as (x, y, z, rank) in rank order, as the blades draw a prefix by rank. */
    private static void sortByRank(FloatArray roots) {
        int count = roots.size / 4;
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) { order[i] = i; }
        float[] items = roots.items;
        Arrays.sort(order, (a, b) -> Float.compare(items[a * 4 + 3], items[b * 4 + 3]));
        float[] sorted = new float[roots.size];
        for (int i = 0; i < count; i++) { System.arraycopy(items, order[i] * 4, sorted, i * 4, 4); }
        System.arraycopy(sorted, 0, items, 0, roots.size);
    }

    /** Whether a face is level enough to cultivate, as the ground's crop rows fade on steeper faces (terrain-biome.glsl). */
    private static boolean level(BoardSurface.Face face) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y);
        float ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
        float nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        return Math.abs(nz) >= .92f * (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    /** CPU-only reed roots are also useful for verifying ground contact and pool/road exclusion. */
    static final class Patch {
        // Obstacle clearance in metres: a reed clump's reach.
        private static final float REED_RADIUS = .3f;
        // The reed lattice's fixed direction, 20 degrees from the x axis.
        private static final float REED_X = .9396926f, REED_Y = .3420201f;
        final BoardVegetation.Key key;
        final List<BoardSurface.Face> ground;
        final Support support, water;
        final BoardScene.Biome kind;
        final boolean uniform;
        final float levelZ;
        final FloatArray roots = new FloatArray();
        final List<BoardRoad> roads;
        // The paint on this hex's ground, or null: no plant grows through it.
        final BoardDecals.Opacity paint;
        // Structures, objects, piers and low decks, which no plant grows through (as blades: GpuGroundCover); null: none.
        final BoardObstacles obstacles;
        private final Vector3 root = new Vector3();
        final float across, along;
        // The reed lattice: its index ranges and the next root to consider.
        final int minX, minY, maxX, maxY;
        int x, y;
        int tier = 2;
        float prepared;

        Patch(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface, BoardDecals.Opacity paint) {
            this.paint = paint;
            key = BoardVegetation.key(scene, tile);
            ground = support(tile, surface);
            kind = BoardBiome.plantKind(scene, tile);
            support = new Support(ground);
            water = tile.liquid().present() ? new Support(surface.water()) : null;
            levelZ = BoardGeometry.groundZ(tile);
            uniform = key.sites().stream().allMatch(site -> site != null && site.biome() == kind
                  && site.elevation() == tile.elevation());
            roads = BoardRoad.nearbyRoads(scene, tile);
            var nearby = new BoardObstacles(scene, tile, true);
            obstacles = nearby.isEmpty() ? null : nearby;
            across = BoardBiome.ROW_METRES;
            along = 1.15f;
            float cx = BoardGeometry.centerX(tile.coords()) / BoardRelief.metres(1);
            float cy = BoardGeometry.centerY(tile.coords()) / BoardRelief.metres(1);
            float u = cx * REED_X + cy * REED_Y;
            float v = -cx * REED_Y + cy * REED_X;
            minX = (int) Math.floor((u - 21) / across);
            maxX = (int) Math.ceil((u + 21) / across);
            x = minX;
            minY = (int) Math.floor((v - 21) / along);
            y = minY;
            maxY = (int) Math.ceil((v + 21) / along);
        }

        /** Roots up to this share of the reed lattice, extending an earlier call's distant subset without moving it. */
        void prepare(BoardScene scene, BoardScene.Tile tile, float density) {
            float metre = BoardRelief.metres(1);
            // The distant subset first: a chunk at coarser terrain detail plants only the tiers it can draw, and a
            // finer replacement extends the same lattice.
            while (prepared < density) {
                if (y > maxY) {
                    prepared = DENSITY[tier--];
                    x = minX; y = minY;
                    continue;
                }
                int ix = x++, iy = y;
                if (x > maxX) { x = minX; y++; }
                // Thin the same world-space lattice at every LOD, independently of wetland coverage and plant height.
                // Reject before sampling support geometry, so omitted roots also avoid preparation and buffer cost.
                if (BoardRelief.hash(ix + 379, iy - 827) >= .46f) { continue; }
                float seed = BoardRelief.hash(ix, iy);
                if (seed >= DENSITY[tier] || (tier < 2 && seed < DENSITY[tier + 1])) { continue; }
                float u = (ix + (BoardRelief.hash(ix + 37, iy) - .5f) * .7f) * across;
                float v = (iy + (BoardRelief.hash(ix, iy + 17) - .5f) * .55f) * along;
                float px = (u * REED_X - v * REED_Y) * metre;
                float py = (u * REED_Y + v * REED_X) * metre;
                if (BoardGeometry.tile(scene, px, py) != tile) { continue; }
                // Water's tactical top includes the water plane. Roots instead follow the actual bank/bar mesh.
                float z = support.height(px, py);
                if (!Float.isFinite(z)) { continue; }
                // An interior plateau has unit coverage. Boundary and sloping samples still use the shared field.
                float cover = uniform && Math.abs(z - levelZ) <= .15f * metre ? 1
                      : BoardBiome.coverage(scene, kind, px, py, z);
                if (paint != null && cover > 0) { cover *= 1 - paint.at(px, py); }
                if (seed > .88f * BoardRelief.smooth(cover)) { continue; }
                if (water != null) {
                    if (z <= water.height(px, py) + .015f * metre) { continue; }
                }
                if (BoardRoad.distance(roads, px, py) < BoardRoad.SHOULDER + 1) { continue; }
                if (obstructed(px, py, z, REED_RADIUS * metre)) { continue; }
                float wet = BoardBiome.wetness(px / metre, py / metre);
                if (wet < .48f || wet > .80f) { continue; }
                roots.addAll(px, py, z - .018f * metre, seed);
            }
        }

        /** Whether a plant rooted at (x, y, z), {@code radius} wide and {@link #PLANT_HEIGHT} tall, meets an obstacle. */
        private boolean obstructed(float x, float y, float z, float radius) {
            return obstacles != null && obstacles.obstructs(root.set(x, y, z), radius, PLANT_HEIGHT * BoardRelief.metres(1));
        }
    }

    /**
     * Small CPU index of the published triangles, not a second height field. No per-root whole-mesh scan: a grid of
     * about two faces per cell, each cell's faces stored with the bounds their own height test rejects by. A rim's
     * contour asks it some sixty times per blade, so a query reads no settings and touches only the faces it might hit.
     */
    static final class Support {
        private final int side;
        private final float minX, minY, scaleX, scaleY, tolerance;
        /** Cell {@code c}'s entries are {@code start[c]} up to {@code start[c + 1]}, in the faces' order. */
        private final int[] start;
        private final BoardSurface.Face[] entries;
        /** Each entry's face bounds widened by the tolerance: left, right, bottom, top. */
        private final float[] bounds;

        Support(List<BoardSurface.Face> faces) {
            float left = Float.POSITIVE_INFINITY, bottom = left, right = Float.NEGATIVE_INFINITY, top = right;
            for (var face : faces) {
                left = Math.min(left, Math.min(face.a().x, Math.min(face.b().x, face.c().x)));
                bottom = Math.min(bottom, Math.min(face.a().y, Math.min(face.b().y, face.c().y)));
                right = Math.max(right, Math.max(face.a().x, Math.max(face.b().x, face.c().x)));
                top = Math.max(top, Math.max(face.a().y, Math.max(face.b().y, face.c().y)));
            }
            minX = left; minY = bottom;
            side = Math.clamp((int) Math.ceil(Math.sqrt(faces.size() / 2.0)), 8, 64);
            scaleX = side / Math.max(right - left, .001f); scaleY = side / Math.max(top - bottom, .001f);
            tolerance = .001f * BoardGeometry.hexScale();
            float[] box = new float[faces.size() * 4];
            start = new int[side * side + 1];
            for (int i = 0; i < faces.size(); i++) {
                var face = faces.get(i);
                // As BoardSurface.Face.height bounds its test, so a skipped face is one it would reject.
                box[4 * i] = Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - tolerance;
                box[4 * i + 1] = Math.max(face.a().x, Math.max(face.b().x, face.c().x)) + tolerance;
                box[4 * i + 2] = Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - tolerance;
                box[4 * i + 3] = Math.max(face.a().y, Math.max(face.b().y, face.c().y)) + tolerance;
                for (int y = row(box[4 * i + 2]); y <= row(box[4 * i + 3]); y++) {
                    for (int x = column(box[4 * i]); x <= column(box[4 * i + 1]); x++) { start[y * side + x + 1]++; }
                }
            }
            for (int cell = 0; cell < side * side; cell++) { start[cell + 1] += start[cell]; }
            entries = new BoardSurface.Face[start[side * side]];
            bounds = new float[entries.length * 4];
            int[] next = Arrays.copyOf(start, side * side);
            for (int i = 0; i < faces.size(); i++) {
                for (int y = row(box[4 * i + 2]); y <= row(box[4 * i + 3]); y++) {
                    for (int x = column(box[4 * i]); x <= column(box[4 * i + 1]); x++) {
                        int entry = next[y * side + x]++;
                        entries[entry] = faces.get(i);
                        System.arraycopy(box, 4 * i, bounds, 4 * entry, 4);
                    }
                }
            }
        }

        private int column(float x) { return Math.clamp((int) ((x - minX) * scaleX), 0, side - 1); }
        private int row(float y) { return Math.clamp((int) ((y - minY) * scaleY), 0, side - 1); }
        private int cell(float x, float y) { return row(y) * side + column(x); }

        float height(float x, float y) {
            int cell = cell(x, y);
            return BoardSurface.sampleHeight(Arrays.asList(entries).subList(start[cell], start[cell + 1]), x, y, Float.NaN);
        }

        /** The highest indexed face holding (x, y), or null where none does. */
        BoardSurface.Face face(float x, float y) {
            int entry = highest(x, y);
            return entry < 0 ? null : entries[entry];
        }

        /** {@link #face}'s height at (x, y), or NaN where no face holds it. */
        float top(float x, float y) {
            int entry = highest(x, y);
            return entry < 0 ? Float.NaN : entries[entry].height(x, y, tolerance);
        }

        private int highest(float x, float y) {
            int cell = cell(x, y), highest = -1;
            float top = Float.NEGATIVE_INFINITY;
            for (int entry = start[cell]; entry < start[cell + 1]; entry++) {
                int b = 4 * entry;
                if (x < bounds[b] || x > bounds[b + 1] || y < bounds[b + 2] || y > bounds[b + 3]) { continue; }
                float height = entries[entry].height(x, y, tolerance);
                if (height > top) { top = height; highest = entry; }
            }
            return highest;
        }
    }

    private static List<BoardSurface.Face> support(BoardScene.Tile tile, BoardTacticalGeometry.Surface surface) {
        return tile.liquid().present() ? surface.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP
              || face.finish() == BoardSurface.Finish.SHORE || face.finish() == BoardSurface.Finish.BED).toList() : surface.top();
    }

    private static final class Batch implements Disposable {
        final int lod;
        // Roots are replaced or extended, never edited in place: identity and size identify unchanged contents.
        // A terrain chunk prepared again can replant identical reeds; equal contents need no upload either.
        final List<FloatArray> current = new ArrayList<>(), previous = new ArrayList<>();
        final IntArray sizes = new IntArray(), previousSizes = new IntArray();
        final FloatArray data = new FloatArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(int lod) { this.lod = lod; }
        void begin() { current.clear(); sizes.clear(); }
        void add(FloatArray roots) {
            if (roots.size > 0) { current.add(roots); sizes.add(roots.size); }
        }

        /** Each root still cross-fades on the GPU; a chunk's projected range only selects contributing templates. */
        boolean contributes(float nearPixels, float farPixels) {
            return nearPixels > START_PIXELS[lod] && (lod == 0 || farPixels < FULL_PIXELS[lod - 1]);
        }

        private boolean unchanged() {
            if (current.size() != previous.size() || !sizes.equals(previousSizes)) { return false; }
            boolean same = true;
            for (int i = 0; i < current.size() && same; i++) {
                if (current.get(i) != previous.get(i)) { same = current.get(i).equals(previous.get(i)); }
            }
            if (same) { remember(); }
            return same;
        }

        private void remember() {
            previous.clear(); previous.addAll(current);
            previousSizes.clear(); previousSizes.addAll(sizes);
        }

        ModelInstance upload(Texture texture) {
            if (current.isEmpty()) { previous.clear(); previousSizes.clear(); return null; }
            if (unchanged()) { return data.size == 0 ? null : instance; }
            data.clear();
            for (FloatArray roots : current) {
                for (int i = 0; i < roots.size; i += STRIDE) {
                    if (roots.items[i + 3] < DENSITY[lod]) { data.addAll(roots.items, i, STRIDE); }
                }
            }
            remember();
            if (data.size == 0) { return null; }
            if (mesh == null) {
                Mesh template = template(lod);
                try { mesh = new GpuInstancedMesh(template); } finally { template.dispose(); }
                var builder = new ModelBuilder();
                builder.begin();
                builder.part("reeds", mesh, GL20.GL_TRIANGLES, material(lod, texture));
                builder.manage(mesh);
                instance = new ModelInstance(builder.end());
            }
            int count = data.size / STRIDE;
            if (count > capacity) {
                if (capacity > 0) { mesh.disableInstancedRendering(); }
                capacity = Math.max(count, Math.max(256, capacity * 2));
                mesh.enableInstancedRendering(false, capacity, instanceAttribute());
            }
            mesh.setInstanceData(data.items, 0, data.size);
            uploads++;
            return instance;
        }

        @Override
        public void dispose() {
            if (instance != null) { instance.model.dispose(); }
            mesh = null; instance = null; capacity = 0;
            begin(); previous.clear(); previousSizes.clear(); data.clear();
        }
    }

    private static Material material(int lod, Texture texture) {
        return new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE),
              new Kind(lod), TextureAttribute.createDiffuse(texture));
    }

    private static VertexAttribute instanceAttribute() {
        return new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverRoot");
    }

    /**
     * One terrain chunk's two nearer reed tiers. Its buffers persist, so panning selects chunks instead of rebuilding
     * instance data. The coarsest reed tier shares a board-wide batch instead.
     */
    private static final class Chunk implements Disposable {
        final Batch[] batches = { new Batch(0), new Batch(1) };
        final List<FloatArray> reeds = new ArrayList<>();
        // Projected hex widths of the chunk's plant hexes in view. No hex in view leaves the chunk undrawn.
        float nearPixels, farPixels;
        long frame;
        // Plants place themselves from their roots. The batches' transforms only let the renderable sorter draw nearer
        // chunks first, so their rows can reject the rows behind them before shading.
        final Vector3 centre;

        Chunk(Coords chunk) {
            centre = BoardGeometry.center(new Coords(chunk.getX() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2,
                  chunk.getY() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2), 0);
        }

        void begin(long next) {
            frame = next;
            reeds.clear();
            nearPixels = 0; farPixels = Float.POSITIVE_INFINITY;
        }

        @Override
        public void dispose() { for (Batch batch : batches) { batch.dispose(); } }
    }

    private final Map<Coords, Chunk> chunks = new HashMap<>();
    // The coarsest reed tier in one board-wide draw: few enough to leave the view and detail band to the shader.
    private final Batch distantReeds = new Batch(2);
    private long frame, uploads;
    private List<BoardScene.Tile> tiles;
    // Derived once per immutable terrain snapshot, including marsh fringe hexes, in board order.
    private final Set<Coords> marshes = new LinkedHashSet<>();
    private int revision = -1, boardId = -1;
    // The reeds share one mipmapped cutout across their batches.
    private Texture sedge;
    /** Render-owned submission snapshot; installed plants still invalidate a stationary view. */
    private record View(float[] projectionView, float viewportPixels, List<BoardScene.Tile> candidates,
          Map<Coords, BoardPlants> sources, List<ModelInstance> instances) { }
    private View view;

    static String vertex(String source) {
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        String plant = GpuShaderSource.read("terrain-biome-vegetation.glsl");
        for (int lod = 0; lod < DENSITY.length; lod++) {
            plant = plant.replace("@START" + lod + "@", Float.toString(START_PIXELS[lod]))
                  .replace("@FULL" + lod + "@", Float.toString(FULL_PIXELS[lod]));
        }
        // A plant outside this template's band or the view leaves every vertex of its triangles outside the clip volume.
        return GpuShaderSource.coverVertex(source.replace("void main() {",
              wind + plant + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "if (!biomePlant(a_position, a_normal, a_color, coverPosition, coverNormal, coverColor)) {\n"
                    + "gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }\n"));
    }

    /** Orthographic detail has one scale everywhere: below the coarsest tier's start no hex shows reeds. */
    static boolean visibleAtScale(Camera camera) {
        return camera.projection.val[Matrix4.M33] == 0
              || BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, camera.position) > START_PIXELS[2];
    }

    /**
     * The candidate tiles' installed reeds, as persistent per-chunk batches plus one board-wide batch for the coarsest
     * tier. Reeds appear and change together with their terrain chunk.
     */
    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardPlants> plants) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            forgetBoard(); revision = BoardGeometry.revision(); boardId = scene.boardId();
        }
        if (tiles != scene.tiles()) {
            tiles = scene.tiles(); view = null;
            marshes.clear();
            for (var tile : tiles) {
                if (BoardBiome.plantKind(scene, tile) == BoardScene.Biome.MARSH) { marshes.add(tile.coords()); }
            }
        }
        float viewportPixels = Gdx.graphics == null ? camera.viewportHeight : camera.viewportHeight
              * Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale / Math.max(1f, Gdx.graphics.getHeight());
        if (view != null && view.viewportPixels() == viewportPixels
              && Arrays.equals(view.projectionView(), camera.combined.val) && view.candidates().equals(candidates)) {
            boolean current = true;
            for (var entry : view.sources().entrySet()) {
                if (plants.apply(entry.getKey()) != entry.getValue()) { current = false; break; }
            }
            if (current) { return view.instances(); }
        }
        frame++;
        // The installed plants each drawn hex used; a stationary view reuses them while unchanged.
        Map<Coords, BoardPlants> sources = new HashMap<>();
        boolean distantMarsh = false;
        for (var tile : candidates) {
            if (!marshes.contains(tile.coords())) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .8f;
            float nearPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera,
                  new Vector3(center).mulAdd(camera.direction, -radius));
            if (nearPixels <= START_PIXELS[2]) { continue; }
            Chunk chunk = chunks.computeIfAbsent(new Coords(tile.coords().getX() / TerrainLod.CHUNK_SIZE,
                  tile.coords().getY() / TerrainLod.CHUNK_SIZE), Chunk::new);
            if (chunk.frame != frame) { chunk.begin(frame); }
            // Every candidate stays in its chunk's buffers, including hexes without installed plants: their chunk's
            // installation must refresh a stationary view. Hexes in view choose whether and how the chunk draws.
            BoardPlants planted = plants.apply(tile.coords());
            if (planted != null && planted.reeds() != null) { chunk.reeds.add(planted.reeds()); }
            if (!camera.frustum.sphereInFrustum(center, radius)) { continue; }
            sources.put(tile.coords(), planted);
            float farPixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera,
                  new Vector3(center).mulAdd(camera.direction, radius));
            chunk.nearPixels = Math.max(chunk.nearPixels, nearPixels);
            chunk.farPixels = Math.min(chunk.farPixels, farPixels);
            distantMarsh |= farPixels < FULL_PIXELS[1];
        }
        List<ModelInstance> result = new ArrayList<>();
        for (var iterator = chunks.values().iterator(); iterator.hasNext(); ) {
            Chunk chunk = iterator.next();
            if (chunk.frame != frame) {
                chunk.dispose();
                iterator.remove();
                continue;
            }
            for (Batch batch : chunk.batches) {
                // An idle template keeps its uploaded roots for a return to its scale or into view.
                batch.begin();
                if (chunk.nearPixels == 0 || !batch.contributes(chunk.nearPixels, chunk.farPixels)) { continue; }
                for (FloatArray reeds : chunk.reeds) { batch.add(reeds); }
                submit(batch, result, chunk.centre);
            }
        }
        // Chunk replacements, not panning, change these; board order keeps an unchanged membership recognizable.
        distantReeds.begin();
        if (distantMarsh) {
            for (Coords marsh : marshes) {
                BoardPlants planted = plants.apply(marsh);
                if (planted != null && planted.reeds() != null) { distantReeds.add(planted.reeds()); }
            }
            submit(distantReeds, result, BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
        }
        view = new View(camera.combined.val.clone(), viewportPixels, List.copyOf(candidates), sources, result);
        return result;
    }

    private void submit(Batch batch, List<ModelInstance> result, Vector3 centre) {
        Texture texture = batch.current.isEmpty() ? null : sedge();
        long before = batch.uploads;
        ModelInstance instance = batch.upload(texture);
        uploads += batch.uploads - before;
        if (instance != null) {
            instance.transform.setToTranslation(centre);
            result.add(instance);
        }
    }

    private Texture sedge() {
        if (sedge == null) { sedge = plantTexture("marsh-sedge"); }
        return sedge;
    }

    long uploads() { return uploads; }

    private static Texture filteredTexture(GpuLinearImage pixels) {
        var texture = pixels.texture(true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return texture;
    }

    /** One shared 512px reed cutout; keep the full-resolution source as the editable asset. */
    private static Texture plantTexture(String name) {
        var source = GpuMaterialMaps.color("vegetation/" + name);
        try {
            var pixels = new GpuLinearImage(512, 512);
            pixels.drawCutout(new GpuLinearImage(source), 0, 0, source.getWidth(), source.getHeight(), 0, 0, 512, 512);
            return filteredTexture(pixels);
        } finally { source.dispose(); }
    }

    /** A reed clump at its tier's detail. */
    private static Mesh template(int lod) {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
              | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
        int cards = lod == 2 ? 1 : 2;
        int segments = lod == 0 ? 2 : 1;
        float height = 1.1f, width = .58f;
        for (int card = 0; card < cards; card++) {
            float angle = card * (float) Math.PI / cards;
            Vector3 side = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
            Vector3 outward = new Vector3(-side.y, side.x, 0);
            for (int row = 0; row < segments; row++) {
                float low = row / (float) segments, high = (row + 1) / (float) segments;
                Vector3 a = new Vector3(outward).scl((float) Math.sin(low * Math.PI) * .28f).add(0, 0, low * height);
                Vector3 b = new Vector3(outward).scl((float) Math.sin(high * Math.PI) * .28f).add(0, 0, high * height);
                Vector3 normal = new Vector3(side).crs(new Vector3(b).sub(a)).nor();
                float bottom = .98f - low * .98f, top = .98f - high * .98f;
                mesh.rect(vertex(new Vector3(a).mulAdd(side, -width), normal, Color.WHITE).setUV(0, bottom),
                      vertex(new Vector3(a).mulAdd(side, width), normal, Color.WHITE).setUV(1, bottom),
                      vertex(new Vector3(b).mulAdd(side, width), normal, Color.WHITE).setUV(1, top),
                      vertex(new Vector3(b).mulAdd(side, -width), normal, Color.WHITE).setUV(0, top));
            }
        }
        return mesh.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(color);
    }

    /**
     * Drops one board's reeds. The sedge cutout is the same on every board and stays.
     */
    private void forgetBoard() {
        for (Chunk chunk : chunks.values()) { chunk.dispose(); }
        chunks.clear();
        distantReeds.dispose();
        marshes.clear(); tiles = null; view = null;
    }

    @Override
    public void dispose() {
        forgetBoard();
        if (sedge != null) { sedge.dispose(); sedge = null; }
    }
}
