/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The same finished shoulder supports rim blades, blade roots and surface queries at both planted detail levels. */
class BoardGrassBankTest {
    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void convertedMountainLakeGrowsGrassOnIncomingSlopeToes(TerrainLod lod) throws Exception {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
                  "Map Set 2/16x17 Mountain Lake.board"), false, true);
            var scene = BoardScenes.scene(board);
            var owners = List.of(new Coords(5, 14), new Coords(5, 15), new Coords(5, 16));
            var tiles = owners.stream().map(scene::tile).toList();
            float floor = BoardGeometry.floor(scene), sink = BoardGeometry.width() * .001f;
            Map<Coords, BoardSurface> sources = new HashMap<>();
            for (var tile : tiles) { sources.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
            sources = BoardVegetation.prepare(scene, tiles, lod, floor, sources);
            Set<List<Float>> positions = new HashSet<>();
            for (var tile : tiles) {
                var surface = BoardTacticalGeometry.Surface.of(sources.get(tile.coords()), scene, floor, sources);
                var planted = GpuGroundCover.plant(scene, tile, surface, null);
                var rebuilt = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile, lod), scene, floor);
                assertEquals(planted, GpuGroundCover.plant(scene, tile, rebuilt, null),
                      "An individual cache miss and shared chunk preparation use the same finished support");
                // Picking and draping read a miss's shape alone, without the neighbours its ground cover borrows.
                assertEquals(shape(rebuilt), BoardTacticalGeometry.Surface.shape(new BoardSurface(scene, tile, lod),
                      scene, floor), "Walls borrow only the neighbours' edges");
                var ground = surface.vegetation();
                assertNotNull(ground);
                for (var face : ground.faces()) {
                    for (var vertex : List.of(face.a(), face.b(), face.c())) {
                        var normal = ground.normals().get(vertex);
                        assertNotNull(normal, "Every support vertex carries its rendered normal");
                        assertEquals(1, normal.len(), .002f, "Support normals are finite and normalized");
                    }
                }
                var own = new HashSet<>(surface.top());
                own.addAll(surface.slopes());
                var support = new GpuBiomeVegetation.Support(ground.faces());
                int incoming = 0;
                for (int i = 0; i < planted.size; i += 4) {
                    float x = planted.get(i), y = planted.get(i + 1), z = planted.get(i + 2);
                    assertTrue(BoardGeometry.contains(tile.coords(), x, y), "A blade stays in its owning hex");
                    assertTrue(positions.add(List.of(x, y)), "Adjacent owners cannot duplicate a blade");
                    var face = support.face(x, y);
                    assertNotNull(face);
                    assertEquals(face.height(x, y), z + sink, .002f,
                          "The root sits on the installed surface, including a neighbour's slope");
                    if (!own.contains(face)) { incoming++; }
                }
                if (!tile.coords().equals(owners.getFirst())) {
                    assertTrue(incoming > 20, lod + " restores the incoming green toe at " + tile.coords() + ": " + incoming);
                }
            }
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void grassDensityFollowsInterpolatedSlopeInsteadOfTriangleBoundaries() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var tile = scene.tile(BoardScenes.CENTER);
        var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
        var meadow = roots(GpuGroundCover.plant(scene, tile, surface, null));
        float left = BoardGeometry.centerX(tile.coords()) - BoardGeometry.width() / 2;
        // Keep the root support fixed while its shading turns gradually from level turf to a steep slope.
        Map<Vector3, Vector3> normals = new IdentityHashMap<>();
        for (var face : surface.vegetation().faces()) {
            for (var vertex : List.of(face.a(), face.b(), face.c())) {
                float up = 1 - .45f * (vertex.x - left) / BoardGeometry.width();
                normals.put(vertex, new Vector3((float) Math.sqrt(1 - up * up), 0, up));
            }
        }
        var slope = new BoardTacticalGeometry.Surface(surface.top(), surface.slopes(), surface.faces(), surface.water(),
              surface.walls(), surface.waterfalls(), surface.frozenFalls(), surface.highestTop(),
              new BoardVegetation.Ground(surface.vegetation().faces(), normals));
        var faded = roots(GpuGroundCover.plant(scene, tile, slope, null));
        assertTrue(meadow.containsAll(faded), "The fade changes density without moving or reranking surviving blades");
        int[] candidates = new int[3], kept = new int[3];
        for (var root : meadow) {
            int band = Math.min(2, (int) (3 * (root.getFirst() - left) / BoardGeometry.width()));
            candidates[band]++;
            if (faded.contains(root)) { kept[band]++; }
        }
        float previous = 1.2f;
        for (int band = 0; band < 3; band++) {
            assertTrue(candidates[band] > 500 && kept[band] > 0, "Each slope band has a populated sample");
            float retained = kept[band] / (float) candidates[band];
            assertTrue(previous - retained > .15f, "Density decreases gradually towards steep ground: " + retained);
            if (band == 0) { assertTrue(retained > .8f, "Level turf retains dense grass"); }
            if (band == 2) { assertTrue(retained < .3f, "The steep end is sparse instead of a solid blade edge"); }
            previous = retained;
        }
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void grassThinsTowardsOpenWaterWithoutMovingSurvivingBlades(TerrainLod lod) {
        var dry = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var at = BoardScenes.CENTER;
        var tile = dry.tile(at);
        var support = BoardTacticalGeometry.Surface.of(new BoardSurface(dry, tile, lod), dry, BoardGeometry.floor(dry));
        var meadow = roots(GpuGroundCover.plant(dry, tile, support, null));
        float metre = BoardRelief.metres(1);
        for (int edge = 0; edge < 6; edge++) {
            var water = at.translated(BoardGeometry.edgeDirection(edge));
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS,
                  0, c.equals(water) ? 1 : -1, 0));
            // Keep the published support fixed to isolate density from changes in the shore's geometry.
            var planted = GpuGroundCover.plant(scene, scene.tile(at), support, null);
            assertEquals(planted, GpuGroundCover.plant(scene, scene.tile(at), support, null),
                  "Rebuilding the bank keeps the same blades");
            var shore = roots(planted);
            assertTrue(meadow.containsAll(shore), "Thinning preserves each surviving root's position, height and rank");
            var a = BoardGeometry.corner(at, 0, edge);
            var b = BoardGeometry.corner(at, 0, edge + 1);
            int[] candidates = new int[3], kept = new int[3];
            int inland = 0;
            for (var root : meadow) {
                float distance = Intersector.distanceSegmentPoint(a.x, a.y, b.x, b.y, root.get(0), root.get(1)) / metre;
                if (distance >= 3) {
                    assertTrue(shore.contains(root), "The inland meadow keeps every blade");
                    inland++;
                } else {
                    int band = (int) distance;
                    candidates[band]++;
                    if (shore.contains(root)) { kept[band]++; }
                }
            }
            float previous = 0;
            for (int band = 0; band < 3; band++) {
                assertTrue(candidates[band] > 50 && kept[band] > 0, "Each metre of shore has a populated sample");
                float retained = kept[band] / (float) candidates[band];
                if (band == 0) { assertTrue(retained < .3f, "Grass is sparse beside the shore: " + retained); }
                else { assertTrue(retained > previous + .15f, "Grass grows progressively denser inland"); }
                previous = retained;
            }
            assertTrue(previous > .7f && inland > 1000, "The fade rejoins a dense, unchanged meadow");
        }
    }

    @Test
    void inlandRaisedAndFrozenWaterDoNotClearTheMeadow() {
        var at = BoardScenes.CENTER;
        var dry = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var support = BoardTacticalGeometry.Surface.of(new BoardSurface(dry, dry.tile(at)), dry, BoardGeometry.floor(dry));
        var meadow = GpuGroundCover.plant(dry, dry.tile(at), support, null);
        for (int level : new int[] { -3, -1, 0, 1 }) {
            var water = at.translated(0);
            var scene = BoardScenes.scene(c -> c.equals(water)
                  ? new BoardScene.Tile(c, level, 1, level == 0, 0, BoardScene.Surface.GRASS,
                        null, null, List.of(), List.of(), BoardLiquid.WATER, null, true)
                  : dry.tile(c));
            assertEquals(meadow, GpuGroundCover.plant(scene, scene.tile(at), support, null),
                  "A frozen shore or water at another elevation does not thin this meadow: " + level);
            for (var tile : List.of(scene.tile(at), scene.tile(water))) {
                float floor = BoardGeometry.floor(scene);
                assertEquals(shape(BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, floor)),
                      BoardTacticalGeometry.Surface.shape(new BoardSurface(scene, tile), scene, floor),
                      "A shore's shape keeps its waterline beside water at level " + level);
            }
        }
        var distant = at.translated(0, 2);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS,
              0, c.equals(distant) ? 1 : -1, 0));
        assertEquals(meadow, GpuGroundCover.plant(scene, scene.tile(at), support, null),
              "Water beyond the adjoining shore cannot remove inland grass");
    }

    /** A support's finished shape, without the ground cover support that picking and draping never read. */
    static BoardTacticalGeometry.Surface shape(BoardTacticalGeometry.Surface support) {
        return new BoardTacticalGeometry.Surface(support.top(), support.slopes(), support.faces(), support.water(),
              support.walls(), support.waterfalls(), support.frozenFalls(), support.highestTop(), null);
    }

    /** Each root's place and whole rank; the rank's fraction carries its ground's slope (GpuGroundCover.slopeCode). */
    private static Set<List<Float>> roots(FloatArray roots) {
        Set<List<Float>> result = new HashSet<>();
        for (int i = 0; i < roots.size; i += 4) {
            result.add(List.of(roots.get(i), roots.get(i + 1), roots.get(i + 2), (float) Math.floor(roots.get(i + 3))));
        }
        return result;
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void twoLevelBanksSupportGrassBetweenTheirEarthFaces(TerrainLod lod) {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS,
              c.getX() < 4 ? 2 : 0, -1, 0));
        var tile = scene.tile(new Coords(3, 4));
        var surface = new BoardSurface(scene, tile, lod);
        var finished = BoardTacticalGeometry.Surface.of(surface, scene, BoardGeometry.floor(scene));
        var roots = GpuGroundCover.plant(scene, tile, finished, null);
        var rim = GpuGroundCover.plantRim(scene, tile, finished, null);
        assertNotNull(rim, "The bank's rims grow blades");
        assertEquals(rim, GpuGroundCover.plantRim(scene, tile, finished, null), "Rebuilding the same bank keeps the same roots");
        checkRim(rim);
        float level = BoardGeometry.level(), m = BoardRelief.metres(1);
        List<BoardSurface.Face> ground = new ArrayList<>(finished.top());
        ground.addAll(finished.walls());
        int lip = 0, shoulder = 0, standing = 0, off = 0;
        for (int i = 0; i < rim.size; i += 4) {
            float x = rim.get(i), y = rim.get(i + 1), z = rim.get(i + 2);
            if (z > 1.5f * level) { lip++; } else if (z > .5f * level) { shoulder++; }
            // The rank's fraction packs what the blade shader reads: the contour, the lean and the heading.
            int code = Math.round((rim.get(i + 3) - (float) Math.floor(rim.get(i + 3))) * 4096);
            assertEquals(z < 1.5f * level, code >= 2048, "The packed contour is the root's");
            if ((code >> 8 & 7) < 7) { standing++; continue; }
            // A hanging blade leans down a bank that falls away under it.
            double heading = 2 * Math.PI * (code & 255) / 256;
            float below = BoardSurface.sampleHeight(ground, x + (float) Math.cos(heading) * m,
                  y + (float) Math.sin(heading) * m, Float.NaN);
            // The packed heading is 1.4 degrees coarse: at a corner of the hex's triangles it can step off them.
            if (Float.isNaN(below)) { off++; continue; }
            assertTrue(below < z - .3f * m, "A hanging rim blade hangs over a falling bank: " + (z - below) / m + " m");
        }
        assertTrue(off * 50 < rim.size / 4, lod + " hanging blades off their support: " + off + " of " + rim.size / 4);
        assertTrue(lip > 10 && shoulder > 10, lod + " must grow rim blades on both contours: " + lip + ", " + shoulder);
        assertTrue(standing > 10 && standing < rim.size / 4 - 10, lod + " rims lean from standing to hanging: " + standing);
        int middle = 0;
        for (int i = 0; i < roots.size; i += 4) {
            float z = roots.get(i + 2);
            if (z > .85f * level && z < 1.15f * level) {
                middle++;
                assertEquals(z + BoardGeometry.width() * .001f,
                      BoardSurface.sampleHeight(finished.slopes(), roots.get(i), roots.get(i + 1), Float.NaN), .002f,
                      "Roots must sit on the installed shoulder, not a separate cosmetic shelf");
            }
        }
        assertTrue(middle > 10, lod + " must grow grass at the intermediate level: " + middle);
        var center = BoardGeometry.center(tile.coords(), tile.elevation());
        assertEquals(2 * level, surface.height(center.x, center.y), .002f, "The unit anchor keeps its game elevation");
    }

    @Test
    void rimBladesAreLimitedToDryTwoLevelGrassBanks() {
        for (var family : new BoardScene.Surface[] { BoardScene.Surface.GRASS, BoardScene.Surface.DIRT, BoardScene.Surface.SAND }) {
            for (int levels : new int[] { 1, 2, 4 }) {
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c, family, c.getX() < 4 ? levels : 0, -1, 0));
                var tile = scene.tile(new Coords(3, 4));
                var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
                var rim = GpuGroundCover.plantRim(scene, tile, surface, null);
                assertEquals(family == BoardScene.Surface.GRASS && levels == 2, rim != null, family + " " + levels);
            }
        }
        var wet = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS,
              c.getX() < 4 ? 2 : 0, c.getX() < 4 ? -1 : 1, 0));
        var tile = wet.tile(new Coords(3, 4));
        assertNull(GpuGroundCover.plantRim(wet, tile,
              BoardTacticalGeometry.Surface.of(new BoardSurface(wet, tile), wet, BoardGeometry.floor(wet)), null));
    }

    /**
     * A hex's rim roots as the blade engine draws them: finite, in rank order for its prefix selection, every rank
     * within the hex's candidates. Returns their number.
     */
    static int checkRim(FloatArray rim) {
        assertEquals(0, rim.size % 4);
        float previous = -1;
        for (int at = 0; at < rim.size; at += 4) {
            for (int axis = 0; axis < 3; axis++) { assertTrue(Float.isFinite(rim.get(at + axis))); }
            float rank = rim.get(at + 3);
            assertTrue(rank > previous && rank < GpuGroundCover.ROOTS_PER_HEX, "Rim roots in rank order: " + rank);
            previous = rank;
        }
        return rim.size / 4;
    }

    @ParameterizedTest
    @CsvSource({ "FULL,true", "FULL,false", "MEDIUM,true", "MEDIUM,false" })
    void waterfallGrassFollowsExposedBanksAcrossHexOwnership(TerrainLod lod, boolean transitions) throws Exception {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            var defaults = BoardGeometry.DEFAULTS;
            BoardGeometry.tune(new BoardGeometry.Tuning(defaults.hexScale(), defaults.unitScale(), defaults.unitHeightScale(),
                  defaults.levelHeight(), defaults.gridShade(), defaults.multiHexUnitScale(), transitions, 0));
            BoardRelief.tune(BoardRelief.DEFAULTS);
            var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
                  "unofficial/Lanceman/16x17 Waterfall.board"), false, true);
            var scene = BoardScenes.scene(board);
            var owners = List.of(new Coords(3, 7), new Coords(4, 7), new Coords(3, 8), new Coords(4, 8),
                  new Coords(3, 9), new Coords(4, 9), new Coords(3, 10), new Coords(4, 10));
            var tiles = owners.stream().map(scene::tile).toList();
            float floor = BoardGeometry.floor(scene), sink = BoardGeometry.width() * .001f;
            Map<Coords, BoardSurface> sources = new HashMap<>();
            for (var tile : tiles) { sources.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
            sources = BoardVegetation.prepare(scene, tiles, lod, floor, sources);
            Set<List<Float>> positions = new HashSet<>();
            Map<Coords, Integer> shoreRoots = new HashMap<>();
            int borrowed = 0;
            for (var tile : tiles) {
                assertTrue(GpuGroundCover.grows(scene, tile), "The reported coast accepts grass at " + tile.coords());
                var source = sources.get(tile.coords());
                var surface = BoardTacticalGeometry.Surface.of(source, scene, floor, sources);
                var plants = BoardPlants.plant(scene, tile, surface, lod, List.of());
                assertNotNull(plants);
                assertNotNull(plants.grass());
                var ground = surface.vegetation();
                assertNotNull(ground);
                var support = new GpuBiomeVegetation.Support(ground.faces());
                var roots = plants.grass();
                var rebuilt = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile, lod), scene, floor);
                assertEquals(roots, BoardPlants.plant(scene, tile, rebuilt, lod, List.of()).grass(),
                      "A single-hex rebuild and shared chunk preparation keep the same roots");
                assertEquals(BoardGrassBankTest.shape(rebuilt), BoardTacticalGeometry.Surface.shape(
                      new BoardSurface(scene, tile, lod), scene, floor), "A coast's shape keeps its waterline walls");
                for (int i = 0; i < roots.size; i += 4) {
                    float x = roots.get(i), y = roots.get(i + 1), z = roots.get(i + 2);
                    assertTrue(BoardGeometry.contains(tile.coords(), x, y), "Roots stay in their canonical owner");
                    assertTrue(positions.add(List.of(x, y)), "Adjacent owners never duplicate a root");
                    var face = support.face(x, y);
                    assertNotNull(face);
                    assertEquals(face.height(x, y), z + sink, .002f, "The root follows the rendered ground");
                    if (face.finish() != BoardSurface.Finish.SHORE && face.finish() != BoardSurface.Finish.BED) {
                        continue;
                    }
                    var water = new BoardSurface.Face(waterVertex(ground, face.a()), waterVertex(ground, face.b()),
                          waterVertex(ground, face.c()), BoardSurface.Finish.TOP);
                    assertTrue(z > water.height(x, y),
                          "A shore blade's sunken root must remain above the local water surface");
                    shoreRoots.merge(tile.coords(), 1, Integer::sum);
                    if (!tile.liquid().present() && !source.faces.contains(face)) { borrowed++; }
                }
            }
            assertTrue(borrowed > 40, lod + " restores the curved shore inside land hexes: " + shoreRoots);
            assertTrue(shoreRoots.getOrDefault(new Coords(3, 8), 0) > 0
                  && shoreRoots.getOrDefault(new Coords(3, 9), 0) > 0,
                  "Both marked banks beside 0409 and 0410 carry shore grass: " + shoreRoots);
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void descendingShallowStreamKeepsRootsAboveItsDrawnWater(TerrainLod lod) {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS,
              c.getY() < 4 ? 1 : 0, c.getX() == 4 ? 0 : -1, 0));
        var tiles = scene.tiles().stream().filter(tile -> tile.coords().getX() >= 3 && tile.coords().getX() <= 5
              && tile.coords().getY() >= 2 && tile.coords().getY() <= 5).toList();
        float floor = BoardGeometry.floor(scene);
        Map<Coords, BoardSurface> sources = new HashMap<>();
        for (var tile : tiles) { sources.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
        sources = BoardVegetation.prepare(scene, tiles, lod, floor, sources);
        var pools = sources.values().stream().filter(source -> source.tile.liquid().present()).toList();
        int gradedRoots = 0;
        for (var tile : tiles) {
            var surface = BoardTacticalGeometry.Surface.of(sources.get(tile.coords()), scene, floor, sources);
            var ground = surface.vegetation();
            assertNotNull(ground);
            var support = new GpuBiomeVegetation.Support(ground.faces());
            var roots = GpuGroundCover.plant(scene, tile, surface, null);
            for (int i = 0; i < roots.size; i += 4) {
                float x = roots.get(i), y = roots.get(i + 1), z = roots.get(i + 2);
                var face = support.face(x, y);
                for (var pool : pools) {
                    float water = BoardSurface.sampleHeight(pool.waterFaces, x, y, Float.NEGATIVE_INFINITY);
                    if (z <= water) {
                        fail("No root may be submerged by the descending stream's actual triangles: owner="
                              + tile.coords() + ", root=" + new Vector3(x, y, z) + ", waterOwner=" + pool.tile.coords()
                              + ", water=" + water + ", face=" + face + ", faceSources="
                              + sources.values().stream().filter(source -> source.faces.contains(face))
                                    .map(source -> source.tile.coords()).toList()
                              + ", wetReferences=" + ground.waterHeights().get(face.a()) + ", "
                              + ground.waterHeights().get(face.b()) + ", " + ground.waterHeights().get(face.c()));
                    }
                }
                if (!ground.waterHeights().containsKey(face.a())) { continue; }
                var water = new BoardSurface.Face(waterVertex(ground, face.a()), waterVertex(ground, face.b()),
                      waterVertex(ground, face.c()), BoardSurface.Finish.TOP);
                assertTrue(z > water.height(x, y), "Grass also clears the wet reference on exposed graded banks");
                float low = Math.min(water.a().z, Math.min(water.b().z, water.c().z));
                float high = Math.max(water.a().z, Math.max(water.b().z, water.c().z));
                if (high - low > .1f * BoardGeometry.hexScale()) { gradedRoots++; }
            }
        }
        assertTrue(gradedRoots > 10, "Exercise populated banks whose local water reference slopes: " + gradedRoots);
    }

    @Test
    void shallowWaterUsesNeighbouringLandInsteadOfItsNominalGrassFamily() {
        var grass = coast(BoardScene.Surface.GRASS, 0, false, BoardLiquid.WATER);
        assertTrue(GpuGroundCover.grows(grass, grass.tile(BoardScenes.CENTER)));
        for (var family : List.of(BoardScene.Surface.DESERT, BoardScene.Surface.SAND, BoardScene.Surface.CONCRETE)) {
            var scene = coast(family, 0, false, BoardLiquid.WATER);
            assertFalse(GpuGroundCover.grows(scene, scene.tile(BoardScenes.CENTER)),
                  "A water tile's nominal grass family cannot plant a " + family + " coast");
        }
    }

    @Test
    void deepFrozenAndMoltenWaterDoNotBecomeGrassSupport() {
        var center = BoardScenes.CENTER;
        for (var scene : List.of(coast(BoardScene.Surface.GRASS, 1, false, BoardLiquid.WATER),
              coast(BoardScene.Surface.GRASS, 0, true, BoardLiquid.WATER),
              coast(BoardScene.Surface.GRASS, 0, false, new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0)))) {
            assertFalse(GpuGroundCover.grows(scene, scene.tile(center)));
        }
    }

    @Test
    void shallowWetlandBanksRemainOwnedByReeds() {
        var scene = BoardScenes.scene(c -> c.equals(BoardScenes.CENTER)
              ? BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, 0, 0)
              : new BoardScene.Tile(c, 0, -1, false, 0, BoardScene.Surface.GRASS, null, null,
                    List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE, BoardFireSmoke.NONE,
                    BoardScene.Biome.MARSH, false));
        var tile = scene.tile(BoardScenes.CENTER);
        assertEquals(BoardScene.Biome.MARSH, BoardBiome.plantKind(scene, tile));
        assertFalse(GpuGroundCover.grows(scene, tile), "New shallow grass cannot duplicate the existing wetland plants");
    }

    @Test
    void changingShallowDepthInvalidatesItsOwnAndNeighbouringGrassInputs() {
        var shallow = coast(BoardScene.Surface.GRASS, 0, false, BoardLiquid.WATER);
        var deep = coast(BoardScene.Surface.GRASS, 1, false, BoardLiquid.WATER);
        for (var at : List.of(BoardScenes.CENTER, BoardScenes.CENTER.translated(5))) {
            assertNotEquals(BoardVegetation.key(shallow, shallow.tile(at)), BoardVegetation.key(deep, deep.tile(at)),
                  "Depth changes grass eligibility and the adjacent bank fade");
        }
    }

    private static Vector3 waterVertex(BoardVegetation.Ground ground, Vector3 vertex) {
        Float height = ground.waterHeights().get(vertex);
        assertNotNull(height, "Finished shore vertices publish the same local water reference as their material");
        return new Vector3(vertex.x, vertex.y, height);
    }

    private static BoardScene coast(BoardScene.Surface land, int depth, boolean frozen, BoardLiquid liquid) {
        return BoardScenes.scene(c -> c.equals(BoardScenes.CENTER)
              ? new BoardScene.Tile(c, 0, depth, frozen, 0, BoardScene.Surface.GRASS, null, null,
                    List.of(), List.of(), liquid, null, true)
              : BoardScenes.tile(c, land, 0, -1, 0));
    }
}
