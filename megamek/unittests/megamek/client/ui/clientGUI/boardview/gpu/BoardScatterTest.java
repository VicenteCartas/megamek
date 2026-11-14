/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardScatterTest {
    @Test
    void wildBushesHaveOpenSilhouettesAndSeparateWoodAndLeaves() {
        for (var surface : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.ROCK)) {
            for (int variant = 0; variant < BoardScatter.BUSHES / 2; variant++) {
                var bush = BoardScatter.bush(surface, variant);
                assertTrue(bush.polygons().size() <= 800, "Keep these full-detail decorations bounded");
                assertTrue(bush.polygons().stream().anyMatch(p -> p.color().r > p.color().g * 1.2f),
                      "Authored brown stems must survive the CPU loader");
                assertTrue(bush.polygons().stream().anyMatch(p -> p.color().g > p.color().r),
                      "Leaf colour must remain distinct from the woody stems");
                float low = Float.POSITIVE_INFINITY;
                for (var face : bush.polygons()) {
                    for (var point : face.points()) {
                        assertTrue(Math.hypot(point.x, point.y) <= BoardScatter.BUSH_RADIUS, "Stay within placement clearance");
                        low = Math.min(low, point.z);
                    }
                }
                assertEquals(0, low, .00001f, "Rooted at ground level");
                assertEquals(2.4f, bush.height(), .00001f);
                // An overhead view must see through the bush rather than find another solid green rock.
                int hits = 0;
                for (int x = 0; x < 30; x++) {
                    for (int y = 0; y < 30; y++) {
                        Ray ray = new Ray(new Vector3(((x + .5f) / 30 - .5f) * 3,
                              ((y + .5f) / 30 - .5f) * 3, 3),
                              new Vector3(0, 0, -1));
                        if (bush.polygons().stream().anyMatch(p -> Intersector.intersectRayTriangle(ray,
                              p.points()[0], p.points()[1], p.points()[2], null))) { hits++; }
                    }
                }
                assertTrue(hits > 15 && hits < 450, surface + " overhead coverage: " + hits + "/900");
            }
        }
    }

    @Test
    void bothBushPathsStandAboveGrassButBelowHalfALevel() {
        var original = BoardGeometry.tuning();
        try {
            for (int level : new int[] { 1, 6, 18, 48 }) {
                for (float hexScale : new float[] { .5f, 1, 2 }) {
                    BoardGeometry.tune(new BoardGeometry.Tuning(hexScale, 1, 1, level, .8f));
                    for (var surface : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.ROCK)) {
                        for (var shape : List.of(BoardScatter.bush(surface, 0), BoardScatter.plant(surface))) {
                            for (float variation : new float[] { .7f, 1, 1.2f }) {
                                float scale = BoardScatter.bushScale(shape, variation);
                                float height = shape.height() * scale;
                                assertTrue(height <= BoardGeometry.level() * .45001f);
                                if (level >= BoardGeometry.MODEL_LEVEL_HEIGHT) {
                                    float grass = BoardGeometry.width() * GpuGroundCover.MAX_HEIGHT_FRACTION;
                                    assertTrue(height - .02f * scale > grass * 1.1f,
                                          "Even the smallest rooted bush stands above the tallest grass");
                                    assertTrue(height <= grass * 1.301f, "Keep bushes only slightly taller than grass");
                                }
                            }
                        }
                    }
                }
            }
        } finally { BoardGeometry.tune(original); }
    }

    @Test
    void naturalSurfacesKeepVariedClustersAndStayStableAcrossSnapshots() {
        for (String theme : List.of("grass", "lunar", "desert", "dirt", "snow", "mars", "volcanic")) {
            Hex hex = new Hex(0);
            hex.setTheme(theme);
            int occupied = 0;
            Set<Integer> sizes = new HashSet<>();
            Set<String> shapes = new HashSet<>();
            Set<Float> rotations = new HashSet<>();
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    Coords coords = new Coords(x, y);
                    var features = scatter(hex, coords);
                    assertTrue(features.size() <= 6);
                    assertEquals(features, scatter(hex, coords));
                    // Collectable limbs must not change the cosmetic ground layout.
                    hex.addTerrain(new Terrain(Terrains.ARMS, 1));
                    assertEquals(features, scatter(hex, coords));
                    hex.removeTerrain(Terrains.ARMS);
                    if (!features.isEmpty()) {
                        occupied++;
                        sizes.add(features.size());
                    }
                    for (var feature : features) {
                        shapes.add(feature.asset());
                        rotations.add(feature.rotation());
                        assertTrue(Math.hypot(feature.x(), feature.y()) + 4 * feature.scale() < 33,
                              "The entire detail stays clear of hex edges and cliffs");
                        assertTrue(Math.hypot(feature.x(), feature.y()) - 4 * feature.scale() > 10,
                              "Scatter leaves the unit standing area clear");
                        assertTrue(feature.height() < .24f, "Scatter cannot resemble gameplay-height obstacles");
                    }
                }
            }
            double baseline = switch (theme) {
                case "grass" -> .16;
                case "lunar", "volcanic" -> .18;
                case "dirt" -> .12;
                case "desert", "mars" -> .10;
                default -> .06;
            };
            double expected = Math.clamp(baseline * BoardScatter.DENSITY_MULTIPLIER, 0, 1);
            assertEquals(expected, occupied / 4096.0, .03, theme + ": " + occupied + " occupied of 4096 hexes");
            if (occupied == 0) {
                continue;
            }
            assertTrue(sizes.size() >= 3, "Clusters must vary in size");
            assertTrue(rotations.size() > occupied, "No small repeating rotation set");
            assertTrue(shapes.containsAll(List.of("scatter-rock", "scatter-slab")), theme);
            if (theme.equals("grass")) {
                assertTrue(shapes.contains("scatter-plant"));
            } else if (theme.equals("desert")) {
                assertTrue(shapes.contains("scatter-plant"));
            } else if (theme.equals("dirt")) {
                assertTrue(shapes.contains("scatter-dry-grass"));
            } else {
                assertEquals(Set.of("scatter-rock", "scatter-slab"), shapes);
            }
        }
    }

    @Test
    void tundraReplacesOnlyPlantsAndPreservesLooseStonePlacement() {
        Hex plain = new Hex(0);
        Hex tundra = new Hex(0);
        tundra.addTerrain(new Terrain(Terrains.TUNDRA, 1));
        int stones = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                var coords = new Coords(x, y);
                var expected = scatter(plain, coords).stream().filter(f -> BoardScatter.stone(f.asset())).toList();
                var actual = scatter(tundra, coords);
                assertEquals(expected, actual, "Keep loose stone positions, scales and rotations; replace only plants");
                stones += actual.size();
            }
        }
        assertTrue(stones > 0, "Retain the existing sparse rock and slab clusters");
    }

    @Test
    void structuresRoadsWaterAndExistingVegetationStayClear() {
        // Check every protected type over many coordinates, including hexes that would otherwise get scatter.
        for (int type : new int[] { Terrains.WATER, Terrains.ICE, Terrains.ROAD, Terrains.PAVEMENT, Terrains.BRIDGE,
              Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL, Terrains.FIELDS, Terrains.WOODS, Terrains.JUNGLE,
              Terrains.SPACE, Terrains.SKY, Terrains.MAGMA, Terrains.FIRE, Terrains.GEYSER, Terrains.SWAMP, Terrains.MUD,
              Terrains.HAZARDOUS_LIQUID, Terrains.FORTIFIED }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(type, 1));
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    assertTrue(scatter(hex, new Coords(x, y)).isEmpty(), "Protected terrain " + type);
                }
            }
        }
    }

    @Test
    void shallowWaterCanCaptureBushesWhileDeeperWaterStaysClear() {
        Hex shallow = new Hex(0);
        shallow.setTheme("grass");
        shallow.addTerrain(new Terrain(Terrains.WATER, 0));
        Hex deep = new Hex(0);
        deep.setTheme("grass");
        deep.addTerrain(new Terrain(Terrains.WATER, 1));
        Set<String> shapes = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                Coords coords = new Coords(x, y);
                scatter(shallow, coords).forEach(feature -> shapes.add(feature.asset()));
                assertTrue(scatter(deep, coords).isEmpty(), "Positive-depth water rejects cosmetics during capture");
            }
        }
        assertTrue(BoardScatter.DENSITY_MULTIPLIER <= 0 || shapes.contains("scatter-plant"),
              "Depth-zero water remains eligible for the ordinary bush population");
    }

    @Test
    void stoneAtlasPigmentSurvivesEveryThemeAndRotation() {
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT, BoardScene.Surface.SAND,
              BoardScene.Surface.DESERT, BoardScene.Surface.ROCK, BoardScene.Surface.LUNAR,
              BoardScene.Surface.MARS, BoardScene.Surface.SNOW, BoardScene.Surface.TROPICAL,
              BoardScene.Surface.VOLCANO)) {
            var tile = BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0, family, List.of(), List.of());
            var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
            var surface = new BoardSurface(scene, tile);
            for (int rotation : new int[] { 0, 137, 359 }) {
                for (String asset : List.of("scatter-rock", "scatter-slab")) {
                    var mesh = new MeshBuilder();
                    mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
                          | VertexAttributes.Usage.ColorUnpacked | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
                    GpuScatter.build(mesh, tile, surface, new BoardScene.Feature(asset, 0, 0, rotation, 1, .15f, 0,
                          BoardScene.FeatureKind.SCATTER));
                    assertEquals(24, mesh.getNumIndices(), "The material fix keeps the tiny eight-triangle stones");
                    int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                    int uv = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
                    int color = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorUnpacked).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(vertices, 0);
                    for (int i = 0; i < vertices.length; i += stride) {
                        for (int channel = 0; channel < 4; channel++) {
                            assertEquals(1, vertices[i + color + channel], 0,
                                  "Final stone pigment/opacity must not depend on theme or rotation: "
                                        + family + ", " + rotation + ", " + asset);
                        }
                        float u = vertices[i + uv], v = vertices[i + uv + 1];
                        if (family == BoardScene.Surface.MARS) {
                            assertTrue(u > .5f && u < 1 && v > .5f && v < .75f,
                                  "Mars bedrock occupies the sixth swatch of the four-row atlas");
                        } else if (family == BoardScene.Surface.SAND || family == BoardScene.Surface.DESERT) {
                            assertTrue(u > 0 && u < .5f && v > .25f && v < .5f,
                                  "Sandstone keeps the third swatch of the four-row atlas");
                        }
                    }
                }
            }
        }
    }

    @Test
    void bushTissueAndPigmentSurvivePlacementAndPackedVertices() throws Exception {
        var place = BoardRelief.class.getDeclaredMethod("place", List.class, BoardShape.class, Vector3.class,
              float.class, float.class, float.class, float.class, BoardRelief.Kind.class, int.class, boolean.class);
        place.setAccessible(true);
        var encode = GpuTerrain.class.getDeclaredMethod("sculptVertex", Vector3.class, BoardRelief.Shade.class,
              float.class, BoardSurface.class);
        encode.setAccessible(true);
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DESERT)) {
            var tile = BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0, family, List.of(), List.of());
            var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
            var surface = new BoardSurface(scene, tile);
            for (int variant = 0; variant < BoardScatter.BUSHES / 2; variant++) {
                var authored = BoardScatter.bush(family, variant);
                var polygons = new ArrayList<>(authored.polygons());
                var sample = polygons.getLast();
                polygons.add(new BoardShape.Polygon(sample.points(), sample.normal(), sample.color(), "unknown-tissue"));
                var bush = new BoardShape(polygons, authored.height());
                var faces = new ArrayList<BoardSurface.Face>();
                // Exercise the real placement/packing path with an unobstructed known transform.
                place.invoke(surface.relief, faces, bush, BoardGeometry.center(tile.coords(), 0), 0f,
                      1f, 1f, 1f, BoardRelief.Kind.PLANT, -1, false);
                assertEquals(polygons.size(), faces.size(), "Tissue tagging must not change the authored triangles");
                Map<Set<Vector3>, Vector3> leafSides = new HashMap<>();
                int leafFaces = 0, woodFaces = 0, reversePairs = 0;
                for (int index = 0; index < polygons.size(); index++) {
                    var polygon = polygons.get(index);
                    boolean leaf = Set.of("dry-leaf", "dry-tip", "green-leaf", "green-tip").contains(polygon.material());
                    var face = faces.get(index);
                    for (var point : List.of(face.a(), face.b(), face.c())) {
                        var shade = surface.relief.shade(point);
                        assertEquals(leaf ? BoardRelief.Kind.LEAF : BoardRelief.Kind.PLANT, shade.kind(), polygon.material());
                        assertEquals(polygon.color().r, shade.rim(), 0, "Keep authored red");
                        assertEquals(polygon.color().g, shade.foot(), 0, "Keep authored green");
                        assertEquals(polygon.color().b, shade.tint(), 0, "Keep authored blue");
                        var vertex = (com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo)
                              encode.invoke(null, point, shade, Float.NaN, surface);
                        int packed = Float.floatToRawIntBits(vertex.color.toFloatBits());
                        float kind = (packed >>> 16 & 255) / 255f;
                        assertTrue(kind >= .125f && kind < .375f, "Both tissues retain the existing plant material range");
                        assertEquals(leaf, kind > .325f, "GPU tissue identity must survive packed byte quantization");
                        assertEquals(polygon.color().r, vertex.uv.x, 0);
                        assertEquals(polygon.color().g, vertex.uv.y, 0);
                        assertEquals(polygon.color().b, vertex.color.a, 0);
                    }
                    if (leaf) {
                        leafFaces++;
                        var normal = surface.relief.shade(face.a()).normal();
                        var previous = leafSides.putIfAbsent(Set.of(face.a(), face.b(), face.c()), normal);
                        if (previous != null) {
                            assertEquals(-1, previous.dot(normal), .00001f, "Both real sides retain opposite leaf normals");
                            reversePairs++;
                        }
                    } else { woodFaces++; }
                }
                assertTrue(woodFaces > 1 && leafFaces > 0 && reversePairs > 0,
                      "Exercise woody stems, both real leaf sides, and the opaque unknown-role fallback");
            }
        }
    }

    @Test
    void capturedBushesPreserveTissueAndExistingPigmentVariation() {
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DESERT)) {
            var tile = BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0, family, List.of(), List.of());
            var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
            var surface = new BoardSurface(scene, tile);
            var polygons = BoardScatter.plant(family).polygons();
            for (int rotation : new int[] { 0, 137, 359 }) {
                var mesh = new MeshBuilder();
                mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
                      | VertexAttributes.Usage.ColorUnpacked | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
                GpuScatter.build(mesh, tile, surface, new BoardScene.Feature("scatter-plant", 0, 0, rotation, 1, .15f, 0,
                      BoardScene.FeatureKind.SCATTER));
                assertEquals(polygons.size() * 3, mesh.getNumIndices(), "Tissue preserves all real leaf/stem faces");
                int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                int uv = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
                int color = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorUnpacked).offset / Float.BYTES;
                float[] vertices = new float[mesh.getNumVertices() * stride];
                short[] indices = new short[mesh.getNumIndices()];
                mesh.getVertices(vertices, 0); mesh.getIndices(indices, 0);
                float variation = .88f + .24f * rotation / 360;
                for (int polygon = 0; polygon < polygons.size(); polygon++) {
                    var source = polygons.get(polygon);
                    boolean leaf = Set.of("dry-leaf", "dry-tip", "green-leaf", "green-tip").contains(source.material());
                    for (int corner = 0; corner < 3; corner++) {
                        int offset = Short.toUnsignedInt(indices[polygon * 3 + corner]) * stride;
                        assertEquals(leaf, vertices[offset + color + 2] > .325f);
                        assertEquals(source.color().r * variation, vertices[offset + uv], .000001f);
                        assertEquals(source.color().g * variation, vertices[offset + uv + 1], .000001f);
                        assertEquals(source.color().b * variation, vertices[offset + color + 3], .000001f);
                    }
                }
            }
        }
    }

    private static List<BoardScene.Feature> scatter(Hex hex, Coords coords) {
        return BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.SCATTER).toList();
    }
}
