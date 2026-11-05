/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.imageio.ImageIO;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardModelBounds;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardTreeCoverageTest {
    @BeforeAll
    static void loadNativeTextures() { GdxNativesLoader.load(); }

    @Test
    void assetReloadReplacesBothCrownPixelsAndModelBounds(@TempDir Path directory) throws Exception {
        File original = Configuration.dataDir();
        Path source = original.toPath().resolve("models/board/vegetation");
        Path model = directory.resolve("models/board/vegetation/tree-coverage-reload.glb");
        Path atlas = directory.resolve("models/board/textures/vegetation/impostors/tree-coverage-reload.png");
        Files.createDirectories(model.getParent());
        Files.createDirectories(atlas.getParent());
        Files.copy(source.resolve("birch-young.glb"), model);
        var pixels = new BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 64; x < 96; x++) { pixels.setRGB(x, y, 0xffffffff); }
        }
        ImageIO.write(pixels, "png", atlas.toFile());
        var plant = new BoardScene.Feature("vegetation/tree-coverage-reload", 0, 0, 0, .9f, 2, 0, BoardScene.FeatureKind.TREE);
        try {
            Configuration.setDataDir(directory.toFile());
            float first = coverage(List.of(plant));
            assertTrue(first > 0);
            ImageIO.write(new BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB), "png", atlas.toFile());
            assertEquals(first, coverage(List.of(plant)), "An existing scene keeps its cached source until reload");
            BoardTreeCoverage.reload();
            assertEquals(0, coverage(List.of(plant)));
            Files.copy(source.resolve("willow-broad.glb"), model, StandardCopyOption.REPLACE_EXISTING);
            ImageIO.write(pixels, "png", atlas.toFile());
            BoardTreeCoverage.reload();
            assertTrue(coverage(List.of(plant)) > first * 5, "Reload also picks up changed GLB bounds");
        } finally {
            Configuration.setDataDir(original);
            BoardTreeCoverage.reload();
        }
    }

    static float coverage(List<BoardScene.Feature> trees) {
        var coverage = new BoardTreeCoverage((x, y) -> true);
        for (var tree : trees) { coverage.add(coverage.addition(tree)); }
        return coverage.fraction();
    }

    @Test
    void overlappingCrownsCountOnceAndRealShapesHaveDifferentAreas() {
        var small = new BoardScene.Feature("vegetation/birch-young", 0, 0, 0, .9f, 2, 0, BoardScene.FeatureKind.TREE);
        var wide = new BoardScene.Feature("vegetation/willow-broad", 0, 0, 0, .9f, 2, 0, BoardScene.FeatureKind.TREE);
        float area = coverage(List.of(wide));
        assertTrue(area > coverage(List.of(small)) * 5, "Shipped crowns must drive the measured area");
        assertEquals(area, coverage(List.of(wide, wide)), .0001f, "Duplicate coverage adds no ground");
        var offset = new BoardScene.Feature(wide.asset(), 22, 0, 0, .9f, 2, 0, BoardScene.FeatureKind.TREE);
        assertTrue(coverage(List.of(wide, offset)) > area * 1.35f);
        assertTrue(coverage(List.of(wide, offset)) < area * 2, "Overlap and off-hex foliage are clipped");
    }

    @Test
    void densityChoosesFullerCrownsWithinTheSameRegionalFamily() {
        var choices = BoardTreeDistributionTest.BASIC_TREES;
        var random = new java.util.Random(731);
        var means = new double[3];
        var accents = new java.util.HashSet<String>();
        for (int sample = 0; sample < 512; sample++) {
            long seed = random.nextLong();
            String selected = megamek.common.board.BoardTreeDistribution.species(choices,
                  new Coords(sample % 32, sample / 32), 0, 0, seed);
            float previous = 0;
            for (int density = 1; density <= 3; density++) {
                String variant = BoardFeatures.canopyVariant(selected, density, sample % 3, seed);
                assertTrue(choices.contains(variant));
                assertTrue(megamek.common.board.BoardTreeDistribution.sameFamily(selected, variant),
                      "Density must not reroll a regional birch/pine/willow/broadleaf family");
                float area = BoardTreeCoverage.crownFraction(variant, variant.startsWith("vegetation/pine") ? 1.7f : .9f);
                assertTrue(area >= previous, "Increasing density favours an equally full or fuller form for the same draw");
                if (density == 1) {
                    assertFalse(List.of("vegetation/tree-broad", "vegetation/tree-forked", "vegetation/willow-broad").contains(variant));
                    if (sample % 3 < 2) { assertTrue(area >= .08, "The first two light canopies must have useful volume"); }
                    if (area < .08) { accents.add(variant); }
                } else {
                    assertTrue(area >= .08, "Sparse accents must not fill a heavy/ultra stand");
                }
                means[density - 1] += area;
                previous = area;
            }
        }
        assertTrue(accents.size() >= 3, "Light retains a variety of occasional narrow crown forms");
        assertTrue(means[1] > means[0] * 1.1 && means[2] > means[1], "Denser terrain selects fuller crowns overall");
        for (String asset : List.of("vegetation/cactus", "vegetation/tree-dead",
              "vegetation/orchard-round", "vegetation/foliage-temperate", "fungus/cup-forked", "mars/finger-crown")) {
            for (int density = 1; density <= 3; density++) {
                assertEquals(asset, BoardFeatures.canopyVariant(asset, density, 0, 73));
            }
        }
    }

    @Test
    void palmsOfferSeveralLightCrownsAndReserveTheLargestForDenserCover() {
        var light = new java.util.HashSet<String>();
        var heavy = new java.util.HashSet<String>();
        var random = new java.util.Random(902);
        for (int sample = 0; sample < 512; sample++) {
            long seed = random.nextLong();
            String sparse = BoardFeatures.canopyVariant("vegetation/palm-bent", 1, 0, seed);
            String dense = BoardFeatures.canopyVariant("vegetation/palm-bent", 2, 0, seed);
            light.add(sparse);
            heavy.add(dense);
            assertTrue(sparse.startsWith("vegetation/palm") && dense.startsWith("vegetation/palm"));
            assertTrue(BoardTreeCoverage.crownFraction(dense, 1.7f) >= BoardTreeCoverage.crownFraction(sparse, 1.7f));
        }
        assertTrue(light.containsAll(List.of("vegetation/palm", "vegetation/palm-compact", "vegetation/palm-arching")));
        assertFalse(light.contains("vegetation/palm-bent"), "The largest palm is reserved for heavy/ultra cover");
        assertTrue(heavy.contains("vegetation/palm-bent"));
    }

    @Test
    void tallLightWetlandUsesSeveralNormalCrownsInsteadOfOneBroadWillow() {
        var at = new Coords(19, 14);
        var trees = BoardTreeDistributionTest.capture(new Hex(0, "woods:1;foliage_elev:3;mud:1", "", at), at, true);
        assertTrue(trees.size() >= 2 && trees.size() <= 3);
        assertTrue(trees.stream().noneMatch(tree -> tree.asset().equals("vegetation/willow-broad")));
        assertTrue(trees.stream().allMatch(tree -> tree.height() >= 3), "Compact crowns retain the required cover height");
        assertEquals(.3f, coverage(trees), .10f);
    }

    @Test
    void cactiDiscountEmptyBoxAreaWithoutChangingWithRotation() {
        for (String name : List.of("cactus", "cactus-flowers", "cactus-young", "cactus-old", "cactus-organ")) {
            float[] bounds = BoardModelBounds.bounds("vegetation/" + name);
            float expected = (bounds[3] - bounds[0]) * (bounds[4] - bounds[1]) * 1.7f * 1.7f
                  / (BoardGeometry.TILE_WIDTH * BoardGeometry.TILE_HEIGHT * .75f) * .85f * .85f;
            for (int angle : new int[] { 0, 31, 73 }) {
                var plant = new BoardScene.Feature("vegetation/" + name, 0, 0, angle, 1.7f, 2, 0, BoardScene.FeatureKind.TREE);
                assertEquals(expected, coverage(List.of(plant)), .02f, "Rotation does not inflate an axis-aligned box");
            }
        }
    }

    @Test
    void minesHeavyCactiHaveMoreBodiesThanTheAdjacentPalmLight() {
        var lightAt = new Coords(1, 10);
        var heavyAt = new Coords(1, 11);
        var light = BoardTreeDistributionTest.capture(new Hex(0, "woods:1;foliage_elev:2", "desert", lightAt), lightAt, true);
        var heavy = BoardTreeDistributionTest.capture(new Hex(0, "woods:2;foliage_elev:2", "desert", heavyAt), heavyAt, true);
        assertTrue(light.stream().anyMatch(t -> t.asset().startsWith("vegetation/palm")));
        assertTrue(heavy.stream().filter(t -> t.asset().startsWith("vegetation/cactus")).count() >= 7,
              "Sparse cactus arms cannot receive the same credit as a full palm crown");
        assertTrue(heavy.size() >= light.size() * 2, "The heavy cactus stand needs a clearly denser population");
    }

    @Test
    void bareStandsUseTwoFourSixTreesSpreadAcrossTheHex() {
        for (boolean natural : new boolean[] { false, true }) {
            for (int height : new int[] { 2, 3 }) {
                for (var at : List.of(new Coords(3, 3), new Coords(11, 7), new Coords(19, 14))) {
                    List<BoardScene.Feature> previous = List.of();
                    for (int density = 1; density <= 3; density++) {
                        var trees = BoardTreeDistributionTest.capture(new Hex(0,
                              "woods:" + density + ";foliage_elev:" + height, "lunar", at), at, natural);
                        assertEquals(density * 2, trees.size(), "Bare branches need a readable stand, not twig-gap infill");
                        assertEquals(previous, trees.subList(0, previous.size()));
                        assertEquals(density * .3f, coverage(trees), .04f,
                              "The bare-tree budget remains 2/4/6 even when general coverage targets rise");
                        for (int i = 0; i < trees.size(); i++) {
                            for (int j = i + 1; j < trees.size(); j++) {
                                assertTrue(Math.hypot(trees.get(i).x() - trees.get(j).x(), trees.get(i).y() - trees.get(j).y()) > 12,
                                      "Bare crowns must spread across the hex instead of piling into a knot");
                            }
                        }
                        previous = trees;
                    }
                }
            }
        }
    }

    @Test
    void densityFormsKeepDeterministicPlacementAndIndependentPopulationLimits() {
        for (String theme : List.of("", "snow", "volcanic", "desert", "lunar", "tropical", "fungus", "mars")) {
            for (int height = 1; height <= 3; height++) {
                for (int seed = 0; seed < 6; seed++) {
                    var at = new Coords(seed * 3, seed + 2);
                    List<BoardScene.Feature> previous = List.of();
                    for (int density = 1; density <= 3; density++) {
                        var trees = BoardTreeDistributionTest.capture(new Hex(0,
                              "woods:" + density + ";foliage_elev:" + height, theme, at), at, true);
                        assertFalse(trees.isEmpty(), theme);
                        assertEquals(trees, BoardTreeDistributionTest.capture(new Hex(0,
                              "woods:" + density + ";foliage_elev:" + height, theme, at), at, true));
                        if (height == 1 || List.of("lunar", "fungus", "mars").contains(theme)) {
                            assertTrue(trees.size() >= previous.size(), theme);
                            assertEquals(previous, trees.subList(0, previous.size()), "Separate cover rules retain their existing sequence");
                        }
                        assertTrue(coverage(trees) >= coverage(previous), theme + " h" + height + " density " + density + " at " + at);
                        long canopy = trees.stream().filter(t -> t.height() >= 1 && !BoardFeatures.rigid(t.asset())
                              && !BoardTreeCoverage.bare(t.asset())).count();
                        assertTrue(canopy <= density * 3, "Ordinary canopy plants stay within 3/6/9, including mixed stands");
                        assertTrue(trees.stream().filter(t -> BoardFeatures.solid(t.asset())).count()
                              <= BoardTreeCoverage.colonyLimit(density), "Rigid colonies retain their independent packing budget");
                        assertTrue(trees.stream().filter(t -> t.asset().startsWith("vegetation/cactus")).count()
                              <= List.of(3, 8, 12).get(density - 1), "Cactus caps also apply in mixed stands");
                        assertTrue(trees.stream().filter(t -> BoardTreeCoverage.bare(t.asset())).count()
                              <= BoardTreeCoverage.bareLimit(density), "Bare-tree caps also apply in mixed stands");
                        assertTrue(trees.stream().allMatch(t -> BoardFeatures.solid(t.asset())
                              ? t.stretch().x() >= .799 && t.stretch().y() >= .799 && t.stretch().z() == 1
                              : t.stretch().x() == 1 && t.stretch().y() == 1));
                        previous = trees;
                    }
                }
            }
        }
    }

    @Test
    void alienAndVolcanicPalettesTakePriorityOverTheOrchardMarker() {
        var at = new Coords(3, 3);
        for (String theme : List.of("fungus", "mars", "volcano")) {
            var plain = new Hex(0, "woods:2;foliage_elev:2", theme, at);
            var marked = new Hex(0, "woods:2;foliage_elev:2;fluff:12", theme, at);
            assertEquals(BoardTreeDistributionTest.capture(plain, at, true), BoardTreeDistributionTest.capture(marked, at, true));
        }
    }

    @Test
    void solidColoniesNeverIntersectActualMeshSurfacesIncludingAcrossHexEdges() {
        var meshes = new HashMap<String, List<Vector3[]>>();
        for (String theme : List.of("fungus", "mars", "desert")) {
            for (int height = 1; height <= 3; height++) {
                for (int density : new int[] { 3 }) { // Earlier tiers are already tested to be prefixes of this packing.
                    var at = new Coords(6, 6);
                    var hex = new Hex(0, "woods:" + density + ";foliage_elev:" + height, theme, at);
                    for (int direction = -1; direction < 6; direction++) {
                        var occupied = new HashMap<Cell, List<Face>>();
                        var tile = direction < 0 ? at : at.translated(direction);
                        var trees = BoardTreeDistributionTest.capture(hex, tile, true);
                        assertFalse(trees.isEmpty());
                        for (var tree : trees) {
                            if (!BoardFeatures.solid(tree.asset()) && !tree.asset().startsWith("vegetation/cactus")) { continue; }
                            float[] bounds = BoardModelBounds.bounds(tree.asset());
                            float scale = BoardTreeCoverage.scale(tree, bounds[5] - bounds[2]);
                            float vertical = tree.asset().startsWith("mars/") ? scale
                                  : tree.height() * BoardGeometry.level() / BoardGeometry.hexScale() / (bounds[5] - bounds[2]);
                            List<Face> faces = new ArrayList<>();
                            double angle = Math.toRadians(tree.rotation()), cos = Math.cos(angle), sin = Math.sin(angle);
                            float inset = tree.asset().startsWith("mars/") ? (bounds[2] + (bounds[5] - bounds[2]) * .06f) * scale : 0;
                            for (var source : meshes.computeIfAbsent(tree.asset(), BoardTreeCoverageTest::meshTriangles)) {
                                Vector3[] triangle = new Vector3[3];
                                for (int i = 0; i < 3; i++) {
                                    double x = source[i].x * scale * tree.stretch().x(), y = source[i].y * scale * tree.stretch().y();
                                    triangle[i] = new Vector3((float) (tree.x() + x * cos - y * sin),
                                          (float) (tree.y() + x * sin + y * cos), source[i].z * vertical - inset);
                                    if (triangle[i].z > 0) {
                                        double px = Math.abs(triangle[i].x), py = Math.abs(triangle[i].y);
                                        double w = BoardGeometry.TILE_WIDTH, h = BoardGeometry.TILE_HEIGHT;
                                        assertTrue(py <= h / 2 + .001 && h / 2 * px + w / 4 * py <= w * h / 4 + .01,
                                              "Actual visible meshes stay inside their own hex, including along shared edges");
                                    }
                                }
                                var face = new Face(triangle);
                                if (face.high.z <= 0) { continue; }
                                faces.add(face);
                                for (Cell cell : face.cells()) {
                                    for (Face other : occupied.getOrDefault(cell, List.of())) {
                                        assertFalse(face.crosses(other), theme + " h" + height + " at " + tile + " " + tree.asset());
                                    }
                                }
                            }
                            // Faces belonging to one plant (including its alternative LODs) are not separate bodies.
                            for (var face : faces) {
                                for (Cell cell : face.cells()) { occupied.computeIfAbsent(cell, key -> new ArrayList<>()).add(face); }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void cactusLodsPreserveTheExpandedFootprintAndHeightWithinTheirBudgets() {
        File root = new File(Configuration.dataDir(), "models/board");
        for (String stem : List.of("cactus", "cactus-old", "cactus-young", "cactus-organ", "cactus-flowers")) {
            String name = "vegetation/" + stem;
            var levels = RigidGlb.loadLods(RigidGlb.source(root, name), root.toPath());
            assertEquals(4, levels.size());
            float[] near = BoardModelBounds.bounds(name);
            for (int lod = 0; lod < 3; lod++) {
                int triangles = 0;
                var bounds = new com.badlogic.gdx.math.collision.BoundingBox();
                for (var mesh : levels.get(lod).meshes) {
                    for (var part : mesh.parts) { triangles += part.indices.length / 3; }
                    for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                        bounds.ext(mesh.vertices[i], mesh.vertices[i + 1], mesh.vertices[i + 2]);
                    }
                }
                assertTrue(triangles > 0 && triangles <= List.of(3600, 950, 420).get(lod));
                assertEquals(30, bounds.max.z, .01f);
                assertEquals(0, bounds.min.z, .01f);
                assertEquals(near[3] - near[0], bounds.getWidth(), (near[3] - near[0]) * .1f);
                assertEquals(near[4] - near[1], bounds.getHeight(), (near[4] - near[1]) * .1f);
            }
        }
    }

    /** Independent runtime-loader reference includes lower-detail faces that can bridge small notches. */
    static List<Vector3[]> meshTriangles(String name) {
        File root = new File(Configuration.dataDir(), "models/board");
        List<Vector3[]> result = new ArrayList<>();
        for (var model : RigidGlb.loadLods(RigidGlb.source(root, name), root.toPath()).subList(0, 3)) {
            for (var mesh : model.meshes) {
                for (var part : mesh.parts) {
                    for (int i = 0; i < part.indices.length; i += 3) {
                        int a = Short.toUnsignedInt(part.indices[i]) * RigidGlb.STRIDE;
                        int b = Short.toUnsignedInt(part.indices[i + 1]) * RigidGlb.STRIDE;
                        int c = Short.toUnsignedInt(part.indices[i + 2]) * RigidGlb.STRIDE;
                        float[] v = mesh.vertices;
                        result.add(new Vector3[] { new Vector3(v[a], v[a + 1], v[a + 2]),
                              new Vector3(v[b], v[b + 1], v[b + 2]), new Vector3(v[c], v[c + 1], v[c + 2]) });
                    }
                }
            }
        }
        return result;
    }

    /** Also used by the native gallery to check the final rendered transforms after terrain support is resolved. */
    static void assertBodiesDoNotIntersect(List<List<Vector3[]>> bodies) {
        var occupied = new HashMap<Cell, List<Face>>();
        for (var body : bodies) {
            List<Face> faces = new ArrayList<>();
            for (var triangle : body) {
                var face = new Face(triangle);
                if (face.high.z <= 0) { continue; }
                faces.add(face);
                for (Cell cell : face.cells()) {
                    for (Face other : occupied.getOrDefault(cell, List.of())) { assertFalse(face.crosses(other)); }
                }
            }
            for (var face : faces) {
                for (Cell cell : face.cells()) { occupied.computeIfAbsent(cell, key -> new ArrayList<>()).add(face); }
            }
        }
    }

    private record Cell(int x, int y, int z) { }

    /** Segment/triangle intersections, spatially indexed; independent of the production box enclosure. */
    private static final class Face {
        final Vector3[] points;
        final Vector3 low = new Vector3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        final Vector3 high = new Vector3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

        Face(Vector3[] points) {
            this.points = points;
            for (var p : points) {
                low.set(Math.min(low.x, p.x), Math.min(low.y, p.y), Math.min(low.z, p.z));
                high.set(Math.max(high.x, p.x), Math.max(high.y, p.y), Math.max(high.z, p.z));
            }
        }

        List<Cell> cells() {
            List<Cell> result = new ArrayList<>();
            for (int x = (int) Math.floor(low.x / 4); x <= (int) Math.floor(high.x / 4); x++) {
                for (int y = (int) Math.floor(low.y / 4); y <= (int) Math.floor(high.y / 4); y++) {
                    for (int z = Math.max(0, (int) Math.floor(low.z / 4)); z <= (int) Math.floor(high.z / 4); z++) {
                        result.add(new Cell(x, y, z));
                    }
                }
            }
            return result;
        }

        boolean crosses(Face other) {
            if (high.x < other.low.x || low.x > other.high.x || high.y < other.low.y || low.y > other.high.y
                  || high.z < other.low.z || low.z > other.high.z || Math.max(high.z, other.high.z) <= .001f) { return false; }
            Vector3 normal = points[1].cpy().sub(points[0]).crs(points[2].cpy().sub(points[0]));
            if (normal.len2() > .000001f) {
                normal.nor();
                boolean coplanar = true;
                for (var p : other.points) { coplanar &= Math.abs(normal.dot(p.cpy().sub(points[0]))) < .00001f; }
                if (coplanar) {
                    int axis = Math.abs(normal.x) > Math.abs(normal.y) ? 0 : 1;
                    if (Math.abs(normal.z) > (axis == 0 ? Math.abs(normal.x) : Math.abs(normal.y))) { axis = 2; }
                    return Intersector.overlapConvexPolygons(project(points, axis), project(other.points, axis));
                }
            }
            for (int i = 0; i < 3; i++) {
                if (segment(points[i], points[(i + 1) % 3], other.points)
                      || segment(other.points[i], other.points[(i + 1) % 3], points)) { return true; }
            }
            return false;
        }

        private static Polygon project(Vector3[] triangle, int axis) {
            float[] points = new float[6];
            for (int i = 0; i < 3; i++) {
                points[i * 2] = axis == 0 ? triangle[i].y : triangle[i].x;
                points[i * 2 + 1] = axis == 2 ? triangle[i].y : triangle[i].z;
            }
            return new Polygon(points);
        }

        private static boolean segment(Vector3 a, Vector3 b, Vector3[] face) {
            var hit = new Vector3();
            var ray = new Ray(a, b.cpy().sub(a));
            return Intersector.intersectRayTriangle(ray, face[0], face[1], face[2], hit) && hit.z > .001f
                  && a.dst2(hit) > .000001f && a.dst2(hit) < a.dst2(b) - .000001f;
        }
    }

    @Test
    void leafyStandsApproachCoverageUntilTheirPopulationLimit() {
        boolean limited = false;
        for (String theme : List.of("", "volcanic", "rock", "snow")) {
            for (int density = 1; density <= 3; density++) {
                var at = new Coords(3, 3);
                var trees = BoardTreeDistributionTest.capture(new Hex(0,
                      "woods:" + density + ";foliage_elev:2", theme, at), at, true);
                limited |= assertCoverageOrPopulationLimit(trees, density, theme);
            }
        }
        assertTrue(limited, "Sparse crowns exercise the cap before reaching the coverage target");
    }

    @Test
    void largeNextCrownsDoNotPrematurelyEndLightOrHeavyCover() {
        for (String terrain : List.of("woods", "jungle")) {
            for (int density = 1; density <= 3; density++) {
                for (String kind : List.of("tropical", "park", "wetland")) {
                    var at = kind.equals("wetland") ? new Coords(3, 3) : new Coords(19, 14);
                    String extra = kind.equals("park") ? ";pavement:1" : kind.equals("wetland") ? ";mud:1" : "";
                    var trees = BoardTreeDistributionTest.capture(new Hex(0,
                          terrain + ":" + density + ";foliage_elev:3" + extra, kind.equals("tropical") ? kind : "", at), at, true)
                          .stream().filter(t -> t.height() >= 1).toList();
                    assertCoverageOrPopulationLimit(trees, density, kind);
                }
            }
        }
    }

    private static boolean assertCoverageOrPopulationLimit(List<BoardScene.Feature> trees, int density, String kind) {
        float target = List.of(.3f, .75f, 1f).get(density - 1), actual = coverage(trees);
        if (actual < target - .10f) {
            long bare = trees.stream().filter(tree -> BoardTreeCoverage.bare(tree.asset())).count();
            assertTrue(trees.size() - bare == density * 3 || bare == density * 2,
                  "A population cap takes priority over filling the remaining crown gaps: " + kind + " " + density);
            return true;
        }
        assertEquals(target, actual, .10f, kind + " " + density);
        return false;
    }

    @Test
    void rigidDensityUsesPackingCapacityAndPreservesDistinctStablePrefixes() {
        for (String theme : List.of("fungus", "mars")) {
            for (int height = 1; height <= 3; height++) {
                for (var at : List.of(new Coords(3, 3), new Coords(11, 7), new Coords(19, 14))) {
                    List<BoardScene.Feature> previous = List.of();
                    for (int density = 1; density <= 3; density++) {
                        var trees = BoardTreeDistributionTest.capture(new Hex(0,
                              "woods:" + density + ";foliage_elev:" + height, theme, at), at, true);
                        assertTrue(trees.size() > previous.size(), theme + " h" + height + " " + at + " d" + density);
                        assertEquals(previous, trees.subList(0, previous.size()));
                        assertTrue(coverage(trees) > coverage(previous));
                        previous = trees;
                    }
                }
            }
        }
    }
}
