/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GpuWaterCoverageTest {
    @ParameterizedTest
    @MethodSource("riverBankCases")
    void exposedRiverBanksKeepTheWaterlineWithoutChangingTheirGeometry(BoardScene.Surface family, boolean frozen) {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            for (int depth : new int[] { 0, 1 }) {
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 7; x++) {
                    for (int y = 0; y < 7; y++) {
                        boolean water = x == 3 && y > 0 && y < 6;
                        tiles.add(new BoardScene.Tile(new Coords(x, y), 0, water ? depth : -1, frozen && water, 0,
                              family, null, null, List.of(), List.of(),
                              water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
                    }
                }
                var scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                var river = new BoardSurface(scene, scene.tile(new Coords(3, 3)));
                float waterline = BoardGeometry.waterZ(river.tile);
                int exposed = 0, slopes = 0;
                for (var face : river.faces) {
                    if (face.finish() != BoardSurface.Finish.TOP && face.finish() != BoardSurface.Finish.SHORE) { continue; }
                    var mesh = mesh();
                    GpuWaterCover.coveredFace(mesh, river, face, List.of(river));
                    assertEquals(3, mesh.getNumVertices(), "An exposed bank needs no additional triangles");
                    assertEquals(3, mesh.getNumIndices());
                    int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                    int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                    int normalOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.Normal).offset / Float.BYTES;
                    int uvOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(vertices, 0);
                    for (int i = 0; i < vertices.length; i += stride) {
                        Vector3 point = point(vertices, i);
                        Vector3 original = List.of(face.a(), face.b(), face.c()).stream().filter(point::equals).findFirst()
                              .orElseThrow(() -> new AssertionError("The bank shape stays unchanged"));
                        Color color = new Color();
                        Color.abgr8888ToColor(color, vertices[i + colorOffset]);
                        assertTrue(color.b < .125f && color.a < 16f / 255,
                              "Grass, sand and snow banks retain clear-water ownership above the wet footprint");
                        float encodedWater = groundWaterHeight(vertices[i + colorOffset]);
                        assertEquals(waterline, encodedWater, .001f,
                              "The bank transition must use the actual waterline, not its own ground height");
                        var shade = river.relief.shade(original);
                        assertNotNull(shade);
                        assertEquals(depth == 0, vertices[i + uvOffset + 1] < 0,
                              "Only depth-zero water carries the shallow material flag");
                        assertEquals(shade.foot(), vertices[i + uvOffset], .001f);
                        assertEquals(shade.rim(), shoreRim(vertices[i + uvOffset + 1]), .001f,
                              "The shallow flag preserves the exposed bank's rim distance");
                        assertEquals(shade.normal(), point(vertices, i + normalOffset),
                              "The existing lighting normals remain intact");
                        if (point.z > waterline + .001f) { exposed++; }
                        if (point.z > waterline + .001f && point.z < -.001f) { slopes++; }
                    }
                }
                assertTrue(exposed > 0 && slopes > 0, "Exercise the dry shelf and the emerged sloping bank");
                if (frozen) {
                    var waterGeometry = river.waterGeometry();
                    int banks = 0, supports = 0;
                    for (int edge = 0; edge < 6; edge++) {
                        var land = scene.tile(river.tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                        if (land.liquid().present()) { continue; }
                        var outer = new Vector3(waterGeometry.shoreCorners()[edge])
                              .lerp(waterGeometry.shoreCorners()[(edge + 1) % 6], .5f);
                        var inner = waterGeometry.waterline()[edge * BoardSurface.SHORE_SEGMENTS
                              + BoardSurface.SHORE_SEGMENTS / 2];
                        var point = new Vector3(outer).lerp(inner, .25f);
                        assertTrue(river.height(point.x, point.y) < -.05f,
                              "A frozen bank must begin grading across its outer shelf, before the narrow water lip");
                        banks++;
                    }
                    for (var face : river.faces) {
                        if (face.finish() != BoardSurface.Finish.ICE) { continue; }
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            assertEquals(BoardGeometry.surfaceZ(river.tile), point.z, .00001f,
                                  "The ice support plane stays at its game level");
                            supports++;
                        }
                    }
                    assertTrue(banks > 0 && supports > 0, "Exercise both the frozen bank and the ice support");
                    assertFalse(river.iceSlab.isEmpty());
                    for (var face : river.iceSlab) {
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            assertEquals(BoardGeometry.surfaceZ(river.tile), point.z, .00001f,
                                  "The visible ice slab retains its original height");
                        }
                    }
                }
            }
        } finally {
            BoardGeometry.tune(geometry);
            BoardRelief.tune(relief);
        }
    }

    private static Stream<Arguments> riverBankCases() {
        return Stream.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW,
                    BoardScene.Surface.DESERT, BoardScene.Surface.MARS)
              .flatMap(family -> Stream.of(Arguments.of(family, false), Arguments.of(family, true)));
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void raisedPoolDoesNotTintItsExposedCliffs(TerrainLod lod) {
        var scene = raisedPool();
        var pool = new BoardSurface(scene, scene.tile(new Coords(4, 4)), lod);
        var lower = new BoardSurface(scene, scene.tile(new Coords(4, 3)), lod);
        int exposed = 0;
        for (var face : pool.walls(scene, BoardGeometry.floor(scene))) {
            if (Math.min(face.a().z, Math.min(face.b().z, face.c().z))
                  <= BoardGeometry.waterZ(lower.tile) + BoardGeometry.hexScale()) { continue; }
            var mesh = mesh();
            GpuWaterCover.coveredFace(mesh, pool, face, List.of(pool, lower));
            int stride = mesh.getAttributes().vertexSize / Float.BYTES;
            int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices, 0);
            for (int i = 0; i < vertices.length; i += stride) {
                var color = new Color();
                Color.abgr8888ToColor(color, vertices[i + colorOffset]);
                assertTrue(color.b >= .125f && color.b < .875f || color.a >= .25f,
                      "The pool must not paint water down the outside of its supporting cliff at " + point(vertices, i));
                exposed++;
            }
        }
        assertTrue(exposed > 0, "The fixture must exercise the exposed cliff above the receiving pool");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void waterMetadataKeepsGroundRimsAndCliffHeightsInTheirShaderChannels(int depth) {
        var scene = raisedPool(depth);
        var surface = new BoardSurface(scene, scene.tile(new Coords(4, 4)));
        Vector3 point = BoardGeometry.center(surface.tile.coords(), surface.tile.elevation());
        for (var kind : new BoardRelief.Kind[] { BoardRelief.Kind.GROUND, BoardRelief.Kind.SUBMERGED_CLIFF }) {
            var shade = new BoardRelief.Shade(Vector3.Z, kind, .6f, surface.tile.elevation(), 2.25f, 7.5f, .4f);
            var dry = GpuTerrain.sculptVertex(point, shade, Float.NaN, surface);
            var wet = GpuTerrain.sculptVertex(point, shade, GpuWaterShader.palette(surface.tile.liquid()), surface);
            assertEquals(shade.rim(), dry.uv.x, 0);
            assertEquals(shade.foot(), dry.uv.y, 0);
            boolean ground = kind == BoardRelief.Kind.GROUND;
            assertEquals(ground ? shade.foot() : shade.rim(), wet.uv.x, 0,
                  "Water-tagged ground supplies distance to the cliff foot; a wall keeps height above its root");
            assertEquals(depth == 0, wet.uv.y < 0, "Only depth-zero water carries the shallow material flag");
            assertEquals(ground ? shade.rim() : shade.foot(), shoreRim(wet.uv.y), 0,
                  "Water-tagged ground supplies distance to the rim; a wall keeps depth below its top");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void clippingGroundIntoWaterPreservesItsRimAndFootDistances(int depth) {
        var scene = raisedPool(depth);
        Coords coords = new Coords(4, 4);
        var water = new BoardSurface(scene, scene.tile(coords));
        var landTile = new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS, null, null, List.of(),
              List.of(), BoardLiquid.NONE, null, true);
        var tiles = scene.tiles().stream().map(tile -> tile.coords().equals(coords) ? landTile : tile).toList();
        var landScene = new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
        var land = new BoardSurface(landScene, landTile);
        int wet = 0, dry = 0, cuts = 0;
        for (var face : land.faces) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            var a = land.relief.shade(face.a());
            var b = land.relief.shade(face.b());
            var c = land.relief.shade(face.c());
            if (a == null || b == null || c == null || a.kind() != BoardRelief.Kind.GROUND
                  || b.kind() != a.kind() || c.kind() != a.kind() || area(face.a(), face.b(), face.c()) < .001f) { continue; }
            var mesh = mesh();
            GpuWaterCover.coveredFace(mesh, land, face, List.of(water));
            int stride = mesh.getAttributes().vertexSize / Float.BYTES;
            int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
            int uvOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices, 0);
            for (int i = 0; i < vertices.length; i += stride) {
                Vector3 point = point(vertices, i), weights = barycentric(face, point);
                float rim = weights.dot(a.rim(), b.rim(), c.rim()), foot = weights.dot(a.foot(), b.foot(), c.foot());
                Color color = new Color();
                Color.abgr8888ToColor(color, vertices[i + colorOffset]);
                boolean covered = color.b < .125f && color.a < .25f;
                assertEquals(covered ? foot : rim, vertices[i + uvOffset], .002f,
                      "Clipping must preserve the shader's foot/rim interpretation on both sides of water");
                assertEquals(covered && depth == 0, vertices[i + uvOffset + 1] < 0,
                      "The shallow flag comes from the covering water, never the dry ground's tile");
                assertEquals(covered ? rim : foot, shoreRim(vertices[i + uvOffset + 1]), .002f);
                if (Math.abs(rim - foot) > .01f) {
                    if (covered) { wet++; } else { dry++; }
                }
                if (List.of(face.a(), face.b(), face.c()).stream().noneMatch(p -> p.epsilonEquals(point, .001f))) { cuts++; }
            }
        }
        assertTrue(wet > 0 && dry > 0 && cuts > 0,
              "Exercise distinct rim/foot distances on dry, submerged and newly clipped ground vertices");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void unshadedBedVerticesKeepTheShallowFlagAndTheirNeutralRim(int depth) throws ReflectiveOperationException {
        var scene = raisedPool(depth);
        var surface = new BoardSurface(scene, scene.tile(new Coords(4, 4)));
        Vector3 point = BoardGeometry.center(surface.tile.coords(), surface.tile.elevation() - depth);
        assertNull(surface.relief.shade(point), "Exercise a bed point without a ground shade");
        var encode = GpuTerrain.class.getDeclaredMethod("bedVertex", Vector3.class, BoardSurface.class, Map.class, float.class);
        encode.setAccessible(true);
        var wet = (com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder.VertexInfo) encode.invoke(null,
              point, surface, Map.of(point, Vector3.Z), GpuWaterShader.palette(surface.tile.liquid()));
        assertEquals(depth == 0, wet.uv.y < 0);
        assertEquals(99, wet.uv.x, 0);
        assertEquals(99, shoreRim(wet.uv.y), 0, "Encoding depth must not create a rim on the flat bed");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void rockPigmentStaysSeparateFromOcclusionAndWaterOptics(int depth) {
        var scene = raisedPool(depth);
        var surface = new BoardSurface(scene, scene.tile(new Coords(4, 4)));
        Vector3 point = BoardGeometry.center(surface.tile.coords(), surface.tile.elevation());
        Color water = null;
        for (float tint : new float[] { 0, .5f, 1 }) {
            var shade = new BoardRelief.Shade(Vector3.Z, BoardRelief.Kind.ROCK, .6f, 5, 2.25f, 7.5f, tint);
            var dry = GpuTerrain.sculptVertex(point, shade, Float.NaN, surface);
            var wet = GpuTerrain.sculptVertex(point, shade, GpuWaterShader.palette(surface.tile.liquid()), surface);
            assertEquals(shade.occlusion(), dry.color.r, 0);
            assertEquals(shade.occlusion(), wet.color.r, 0, "Wet rock pigment must not attenuate geometric occlusion");
            assertEquals(tint, dry.color.a, 0);
            assertEquals(shade.rim(), dry.uv.x, 0);
            assertEquals(shade.rim(), wet.uv.x, 0, "Snow caps retain their height above the root");
            assertEquals(shade.foot(), dry.uv.y, 0, "Dry zero-gravity bedrock still needs its depth below the top");
            assertEquals(tint, wet.uv.y, 0, "Only wet rocks move pigment variation into the spare UV channel");
            assertEquals(1, dry.color.b, 0);
            assertTrue(wet.color.b >= .875f && wet.color.b < .999f);
            assertTrue(wet.color.a < .25f);
            if (water == null) { water = new Color(wet.color); }
            else {
                assertEquals(water.g, wet.color.g, 0, "Pigment must not change the water level");
                assertEquals(water.b, wet.color.b, 0, "Pigment must not change the fractional water level");
                assertEquals(water.a, wet.color.a, 0, "Pigment must not change the water palette");
            }
        }
    }

    /** The raised depth-one pool and lower receiving pool at 0505/0504 in test_board2.board. */
    private static BoardScene raisedPool() {
        return raisedPool(1);
    }

    private static BoardScene raisedPool(int depth) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                Coords coords = new Coords(x, y);
                boolean high = x == 4 && y == 4, low = x == 4 && y == 3;
                int level = high ? 5 : low ? -1 : x == 3 && y == 3 ? 2 : y == 4 || y == 5 ? 1 : 0;
                tiles.add(new BoardScene.Tile(coords, level, high || low ? depth : -1, false, 0,
                      BoardScene.Surface.GRASS, null, null, List.of(), List.of(),
                      high || low ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void verticalWaterbedWallsKeepTheirWaterMaterialAndBlendIntoTheBedAtTheirFoot() {
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            BoardScene scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
            int feet = 0, high = 0, rubble = 0, sheer = 0;
            for (Coords coords : List.of(new Coords(7, 14), new Coords(8, 14), new Coords(9, 13))) {
                var surface = new BoardSurface(scene, scene.tile(coords));
                for (var face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.OUTCROP
                          && Math.max(face.a().z, Math.max(face.b().z, face.c().z)) < BoardGeometry.waterZ(surface.tile)) {
                        rubble++;
                    }
                    if (!bedBoundary(surface, face)) { continue; }
                    MeshBuilder mesh = mesh();
                    GpuWaterCover.coveredFace(mesh, surface, face, List.of(surface));
                    int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                    int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                    int uvOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(vertices, 0);
                    assertEquals(3, mesh.getNumVertices());
                    for (int i = 0; i < vertices.length; i += stride) {
                        assertTrue(List.of(face.a(), face.b(), face.c()).contains(point(vertices, i)),
                              "Shading must preserve the cliff's geometry");
                        Color color = new Color();
                        Color.abgr8888ToColor(color, vertices[i + colorOffset]);
                        assertTrue(color.b < .125f && color.a < .25f,
                              "Vertical pool boundaries must keep waterbed shading even on the exact shoreline");
                        assertTrue(vertices[i + uvOffset] >= 0);
                        if (vertices[i + uvOffset] == 0) { feet++; }
                        if (vertices[i + uvOffset] > 2) { high++; }
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    if (Math.abs(normal.z) < .15f) { sheer++; }
                }
            }
            assertTrue(feet > 0 && high > 0, "The wall supplies a continuous height above its bed for sediment blending");
            assertTrue(rubble > 0, "Fallen rock details should continue below the waterline");
            assertTrue(sheer > 0, "The scene must exercise vertical cliff faces");
        } finally {
            BoardRelief.tune(original);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void clippingPreservesTheWallAndTintsOnlyRockCoveredByTheStream(boolean cliffs) {
        var original = BoardRelief.tuning();
        try {
            BoardScenes.tune(cliffs);
            BoardScene scene = BoardScenes.mapScene(BoardScene.Surface.SAND);
            BoardSurface water = new BoardSurface(scene, scene.tile(new Coords(9, 9)));
            BoardSurface upper = new BoardSurface(scene, scene.tile(new Coords(8, 9)));
            BoardSurface land = new BoardSurface(scene, scene.tile(new Coords(8, 10)));
            List<BoardSurface> waters = List.of(water, upper);
            int wetCount = 0, dryCount = 0, wetRocks = 0, wetCliffs = 0;
            for (BoardSurface surface : List.of(land, water)) {
                List<BoardSurface.Face> faces = new ArrayList<>(surface.walls(scene, -100));
                faces.addAll(surface.faces);
                for (BoardSurface.Face face : faces) {
                    if ((surface == land && face.landEdge() != 0)
                          || face.finish() == BoardSurface.Finish.BED) { continue; }
                    MeshBuilder mesh = mesh();
                    GpuWaterCover.coveredFace(mesh, surface, face, waters);
                    int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                    int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    short[] indices = new short[mesh.getNumIndices()];
                    mesh.getVertices(vertices, 0);
                    mesh.getIndices(indices, 0);
                    int[] channels = clippedRockChannels(surface, face, mesh, vertices, stride, colorOffset);
                    wetRocks += channels[0];
                    wetCliffs += channels[1];
                    float area = 0, tintedOutside = 0, dryUnderwater = 0;
                    for (int i = 0; i < indices.length; i += 3) {
                        int a = Short.toUnsignedInt(indices[i]) * stride;
                        int b = Short.toUnsignedInt(indices[i + 1]) * stride;
                        int c = Short.toUnsignedInt(indices[i + 2]) * stride;
                        Vector3 p = point(vertices, a), q = point(vertices, b), r = point(vertices, c);
                        float pieceArea = area(p, q, r);
                        area += pieceArea;
                        // Coincident cuts can leave rounding slivers with unstable centroids; their area still counts.
                        if (pieceArea < .001f) { continue; }
                        Vector3 middle = new Vector3(p).add(q).add(r).scl(1f / 3);
                        Color color = new Color();
                        Color.abgr8888ToColor(color, vertices[a + colorOffset]);
                        boolean wet = color.b >= .875f && color.b < .999f;
                        if (color.b < .125f && color.a < .25f) {
                            // A bank carries its water-height reference above the wet footprint too. Only its
                            // submerged part gets absorption; clipped rock/cliff tags still require full coverage.
                            float reference = (groundWaterHeight(vertices[a + colorOffset])
                                  + groundWaterHeight(vertices[b + colorOffset])
                                  + groundWaterHeight(vertices[c + colorOffset])) / 3;
                            wet = middle.z < reference - .003f;
                        }
                        float height = Float.NEGATIVE_INFINITY;
                        for (BoardSurface body : waters) {
                            for (BoardSurface.Face top : body.waterFaces) {
                                // The support sampler deliberately accepts points just outside an edge. Shading
                                // coverage needs the actual footprint, without that picking tolerance.
                                if (Intersector.isPointInTriangle(middle.x, middle.y, top.a().x, top.a().y,
                                      top.b().x, top.b().y, top.c().x, top.c().y)) {
                                    height = Math.max(height, top.height(middle.x, middle.y));
                                }
                            }
                        }
                        if (wet) {
                            wetCount++;
                            // The pool's vertical boundary is shaded by height, including undercuts behind the outline.
                            if (!bedBoundary(surface, face) && (!Float.isFinite(height) || height < middle.z - .003f)) {
                                tintedOutside += pieceArea;
                            }
                        } else {
                            dryCount++;
                            if (height > middle.z + .003f) { dryUnderwater += pieceArea; }
                        }
                    }
                    float expected = area(face.a(), face.b(), face.c());
                    assertEquals(expected, area, Math.max(.003f, expected * .0005f),
                          "Clipping must preserve the cliff without missing or overlapping polygons");
                    // World-coordinate float rounding leaves subpixel slivers at coincident boundaries.
                    float tolerance = .005f + expected * .0005f;
                    assertTrue(tintedOutside < tolerance, "Tinted area outside the stream: " + tintedOutside);
                    assertTrue(dryUnderwater < tolerance, "Untinted area below the stream: " + dryUnderwater);
                }
            }
            assertTrue(wetCount > 0 && dryCount > 0);
            assertTrue(wetRocks > 0, "The clipped geometry must exercise real rock pigment");
            if (cliffs) {
                assertTrue(wetCliffs > 0, "Converted cliff faces must keep neutral rock pigment");
                assertFalse(water.cliffWater.isEmpty());
                var pools = new GpuWaterShader.Pools(scene, Map.of(), Map.of(water.tile.coords(), water));
                for (var face : water.cliffWater) {
                    Vector3 point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    assertNotNull(pools.sample(point.x, point.y, new float[4]),
                          "The flow and depth field must cover the surface inside cliff recesses");
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    /** Check the actual clipped vertex channels against the original triangle's affine data. */
    private static int[] clippedRockChannels(BoardSurface surface, BoardSurface.Face face, MeshBuilder mesh,
          float[] vertices, int stride, int colorOffset) {
        var a = surface.relief.shade(face.a());
        var b = surface.relief.shade(face.b());
        var c = surface.relief.shade(face.c());
        if (a == null || b == null || c == null || a.kind() != b.kind() || a.kind() != c.kind()
              || area(face.a(), face.b(), face.c()) < .001f) { return new int[2]; }
        boolean rock = a.kind() == BoardRelief.Kind.ROCK;
        if (!rock && a.kind() != BoardRelief.Kind.CLIFF) { return new int[2]; }
        int uvOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
        int wet = 0;
        for (int i = 0; i < vertices.length; i += stride) {
            Color color = new Color();
            Color.abgr8888ToColor(color, vertices[i + colorOffset]);
            boolean covered = color.b >= .875f && color.b < .999f;
            Vector3 weights = barycentric(face, point(vertices, i));
            float occlusion = weights.dot(a.occlusion(), b.occlusion(), c.occlusion());
            assertEquals(occlusion, color.r, 1.1f / 255, "Clipping preserves geometric occlusion independently of pigment");
            float rim = weights.dot(a.rim(), b.rim(), c.rim());
            assertEquals(rim, vertices[i + uvOffset], .001f, "Clipping retains the actual height above the root");
            if (covered) {
                float tint = rock ? weights.dot(a.tint(), b.tint(), c.tint()) : 1;
                assertEquals(tint, vertices[i + uvOffset + 1], .001f,
                      "Wet rock keeps its pigment; a converted cliff has no extra population tint");
                wet++;
            } else {
                float foot = weights.dot(a.foot(), b.foot(), c.foot());
                assertEquals(foot, vertices[i + uvOffset + 1], .001f,
                      "Dry pieces retain the below-top coordinate used by bedrock and cliffs");
            }
        }
        return rock ? new int[] { wet, 0 } : new int[] { 0, wet };
    }

    private static Vector3 barycentric(BoardSurface.Face face, Vector3 point) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
        float denominator = normal.len2();
        float a = new Vector3(face.b()).sub(point).crs(new Vector3(face.c()).sub(point)).dot(normal) / denominator;
        float b = new Vector3(face.c()).sub(point).crs(new Vector3(face.a()).sub(point)).dot(normal) / denominator;
        return new Vector3(a, b, 1 - a - b);
    }

    private static boolean bedBoundary(BoardSurface surface, BoardSurface.Face face) {
        return List.of(face.a(), face.b(), face.c()).stream().allMatch(p -> {
            var shade = surface.relief.shade(p);
            return shade != null && shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
        });
    }

    private static MeshBuilder mesh() {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.ColorPacked, GL20.GL_TRIANGLES);
        return mesh;
    }

    private static Vector3 point(float[] vertices, int at) {
        return new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]);
    }

    private static float groundWaterHeight(float packedColor) {
        Color color = new Color();
        Color.abgr8888ToColor(color, packedColor);
        return (Math.round(color.g * 255) - 64 + color.b * 8) * BoardGeometry.level() - BoardGeometry.hexScale();
    }

    private static float shoreRim(float encoded) {
        return encoded < 0 ? -1 - encoded : encoded;
    }

    private static float area(Vector3 a, Vector3 b, Vector3 c) {
        return new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len() * .5f;
    }
}
