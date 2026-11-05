/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoadSlopeTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void grasslandRoadMouthSeamsDoNotGrowSoilFins(TerrainLod lod) throws Exception {
        var scene = GpuRoadSourceTest.scene("AGoAC Maps/16x17 Grassland 2.board");
        var at = new Coords(3, 10);
        var previous = BoardRelief.tuning();
        BoardRelief.tune(new BoardRelief.Tuning(previous.cliffsIntoWater(), true, previous.stepOutline()));
        try {
            var reserved = new Area();
            for (var tile : scene.tiles()) {
                var grade = BoardRoadGrade.of(scene, tile, true);
                if (grade != null) { reserved.add(grade.region()); }
            }
            var near = new HashMap<Coords, BoardSurface>();
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(new Coords(2, 10)) <= 2) {
                    near.put(tile.coords(), new BoardSurface(scene, tile, lod));
                }
            }
            var surface = near.get(at);
            int cuts = 0;
            for (var face : surface.walls(scene, BoardGeometry.floor(scene), near)) {
                if (face.landEdge() >= 0 || face.scatter()) { continue; }
                var middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
                if (middle.z <= .1f || middle.z >= BoardRoadGrade.CLEARANCE * BoardGeometry.level()) { continue; }
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                if (normal.len2() < 1e-10f || Math.abs(normal.z) > .001f * normal.len()) { continue; }
                normal.nor().scl(.02f * BoardGeometry.hexScale());
                // The 0311/0410 mouths leave a rounding-width slit through 0411's bank. The emitted cuts must
                // border actual ground, not run across road reserved on both sides of that internal seam.
                assertTrue(!reserved.contains(middle.x + normal.x, middle.y + normal.y)
                            || !reserved.contains(middle.x - normal.x, middle.y - normal.y),
                      lod + " soil cut inside the road reservation: " + face);
                cuts++;
            }
            assertTrue(cuts > 0, "Keep the actual exposed cuts behind the retaining wall");
            assertClosed(scene, at, lod, p -> Math.hypot(p.x - 205, p.y + 751) < 5);
        } finally {
            BoardRelief.tune(previous);
        }
    }

    @Test
    void bridgeRampFillDoesNotGrowGrass() {
        var at = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> c.equals(at.translated(0))
              ? BridgeScenes.bridge(c, 0, 1, 9)
              : BoardScenes.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                    c.equals(at) ? 9 : 0, 0, BoardScene.Surface.GRASS));
        var tile = scene.tile(at);
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.MEDIUM)) {
            var surface = new BoardSurface(scene, tile, lod);
            var support = BoardTacticalGeometry.Surface.of(surface, scene, BoardGeometry.floor(scene));
            var roots = GpuGroundCover.plant(scene, tile, support, null);
            assertTrue(roots.size > 0, "Grass remains on the untouched ground beside the approach");
            for (int i = 0; i < roots.size; i += 4) {
                assertTrue(roots.get(i + 2) < .001f, "Grass must not grow from the raised concrete fill: " + lod);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void raisedBridgeApproachesKeepTheirTerrainClosed(int direction) {
        var at = BoardScenes.CENTER;
        var bridge = at.translated(direction);
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        for (int depth : new int[] { -1, 0, 1 }) {
            var scene = BoardScenes.scene(c -> {
                if (c.equals(bridge)) {
                    var span = BridgeScenes.bridge(c, 0, 1, exits);
                    return new BoardScene.Tile(c, 0, depth, false, 0, BoardScene.Surface.GRASS, null, null,
                          span.features(), List.of(),
                          depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE);
                }
                return BoardScenes.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                      c.equals(at) ? exits : 0, 0, BoardScene.Surface.GRASS);
            });
            for (var lod : TerrainLod.values()) { assertClosed(scene, at, lod); }
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void koziceBridgeApproachesKeepTheirTerrainClosed(TerrainLod lod) {
        var scene = BoardScenes.scene(new File(
              "data/boards/unofficial/Strategoslevel3/32x17 (CDS) Kozice Valley Grain Mills.board"));
        for (var at : List.of(new Coords(19, 7), new Coords(19, 9))) { assertClosed(scene, at, lod); }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void motorwayCutsKeepClosedNaturalBanksBelowZero(TerrainLod lod) {
        var scene = BoardScenes.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x68/64x68 Motorway-M1-2.board"));
        var tile = scene.tile(new Coords(27, 25));
        assertEquals(-3, tile.elevation(), "Exercise the reported road bend below elevation zero");
        assertClosed(scene, tile.coords(), lod);
    }

    @Test
    void cityRoadCutsKeepClosedNaturalBanks() {
        var scene = BoardScenes.scene(new File(
              "data/boards/unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board"));
        for (var lod : TerrainLod.values()) {
            for (var at : List.of(new Coords(13, 8), new Coords(14, 9))) { assertClosed(scene, at, lod); }
        }
    }

    @Test
    void plaineGradedApproachDoesNotKeepTheOldFlatToePanel() throws Exception {
        var scene = GpuRoadSourceTest.scene("unofficial/Cartes/16x17 Plaine 1.board");
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var acrossLevelCut = new Ray(new Vector3(629.236f, -883.532f, 17.5f),
                  new Vector3(-.3368f, -.9415f, 0));
            var terrain = new ArrayList<BoardSurface.Face>();
            var near = new HashMap<Coords, BoardSurface>();
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(new Coords(9, 11)) <= 1) {
                    near.put(tile.coords(), new BoardSurface(scene, tile, lod));
                }
            }
            // The higher neighbor owns the natural bank; the removed panel belonged to the lower road.
            for (var surface : near.values()) {
                terrain.addAll(surface.groundFaces());
                terrain.addAll(surface.walls(scene, BoardGeometry.floor(scene), near));
            }
            assertTrue(terrain.stream().anyMatch(face -> Intersector.intersectRayTriangle(
                        acrossLevelCut, face.a(), face.b(), face.c(), new Vector3())),
                  "Removing the panel must keep the actual terrain bank behind it: " + lod);
            assertClosed(scene, new Coords(10, 12), lod);
        }
    }

    @Test
    void minesRoadBanksDoNotUndercutTheirSupportingRims() throws Exception {
        var scene = GpuRoadSourceTest.scene("Deserts/16x17 Mines 1.board");
        for (var lod : TerrainLod.values()) {
            for (int[] bank : new int[][] { {3, 7, 0}, {4, 8, 1} }) {
                var at = new Coords(bank[0], bank[1]);
                var tile = scene.tile(at);
                var surface = new BoardSurface(scene, tile, lod);
                float top = tile.elevation() * BoardGeometry.level();
                int checked = 0;
                // The level -1 roads border the bare level -2 hollow at 0508. Releasing road clearance
                // immediately below their rims used to leave downward-facing caps and thin bank spikes.
                for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                    float high = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
                    float low = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                    if (face.landEdge() != bank[2] || high < top - .001f) {
                        continue;
                    }
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    if (normal.len2() < .000001f) { continue; }
                    // Allow only the subpixel inset from triangulating the curved vertical boundary.
                    assertTrue(normal.z >= 0 || -normal.z * (high - low)
                                <= .01f * BoardGeometry.hexScale() * Math.hypot(normal.x, normal.y),
                          "The upper bank must support its road rim without an undercut: " + at + "/" + lod + " " + face);
                    checked++;
                }
                assertTrue(checked > 0, "Inspect the emitted upper bank at " + at + "/" + lod);
                // Supporting the curved cap must preserve its rim, foot, and adjoining cliff columns.
                assertClosed(scene, at, lod);
            }
        }
    }

    @Test
    void largeMountainRoadsKeepNativeRockBelowTheirSupportedApproaches() {
        var scene = BoardScenes.scene(new File("data/boards/Map Set 5/16x17 Large Mountain 2.board"));
        var natural = scene.withTiles(scene.tiles().stream().map(tile -> BoardScenes.tile(tile.coords(),
              BoardRoad.Kind.NONE, 0, tile.elevation(), tile.surface())).toList());
        for (int[] example : new int[][] { {8, 3, 1}, {9, 3, 0} }) {
            var at = new Coords(example[0], example[1]);
            // Road-adjacent cliffs retain their full shared sampling even at distant LOD.
            var nativeSurface = new BoardSurface(natural, natural.tile(at), TerrainLod.FULL);
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                var walls = surface.walls(scene, BoardGeometry.floor(scene));
                // Distant meshes intentionally omit these lower rock rows; verify the formation at full detail,
                // then check that every rendered detail still closes its support and adjoining gate below.
                for (float t : lod == TerrainLod.FULL ? new float[] { .25f, .5f, .75f } : new float[0]) {
                    // This lower rock lies beneath the neighboring level-two road's supporting bank as well.
                    // Level-one rock can still belong to that transition; the deeper formation must be intact.
                    var rock = nativeSurface.relief.wetCliffContact(example[2], t, .5f * BoardGeometry.level());
                    float nearest = Float.POSITIVE_INFINITY;
                    for (var face : walls) {
                        if (face.landEdge() != example[2]) { continue; }
                        var points = List.of(face.a(), face.b(), face.c());
                        for (int i = 0; i < 3; i++) {
                            var start = points.get(i);
                            var span = new Vector3(points.get((i + 1) % 3)).sub(start);
                            float u = span.len2() == 0 ? 0
                                  : Math.clamp(new Vector3(rock).sub(start).dot(span) / span.len2(), 0, 1);
                            nearest = Math.min(nearest, new Vector3(start).mulAdd(span, u).dst(rock));
                        }
                    }
                    assertTrue(nearest < .01f, at + "/" + lod + " lost native rock beneath the road by " + nearest);
                }
                // Recovering the deeper rock must not detach its cap, foot, or the adjoining ramp gates.
                assertClosed(scene, at, lod);
            }
        }
    }

    @Test
    void mesaCityRoadCutsKeepClosedBoundaries() {
        var scene = BoardScenes.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board"));
        for (Coords at : List.of(new Coords(27, 21), new Coords(27, 22), new Coords(26, 21))) {
            assertClosed(scene, at);
        }
    }

    @Test
    void mesaCityRoadCliffKeepsItsRimAtLargeCoordinates() {
        var scene = BoardScenes.scene(new File(
              "data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board"));
        var tile = scene.tile(new Coords(54, 33));
        assertTrue(BoardRoad.rendered(tile), "Use the same sculpted road metadata as the rendered map");
        GdxNativesLoader.load();
        var camera = new BoardCamera();
        camera.resize(1440, 1080);
        camera.setIsometric(true);
        camera.camera.zoom = .18f;
        var focus = scene.tile(new Coords(55, 33));
        camera.center(BoardGeometry.center(focus.coords(), focus.elevation()));
        var surface = new BoardSurface(scene, tile);
        var faces = new ArrayList<>(surface.groundFaces());
        faces.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
        // Rays through the reported blue wedge beside slab 5634. Float rounding at these world coordinates
        // previously made the top's untangler move two shared rim vertices while their cliff stayed put.
        // Neighbour cuts also add cliff columns between this top's original vertices. Both sides of the
        // road mouth must interpolate the actual emitted boundary, including these subpixel crack samples.
        for (float[] pixel : new float[][] { {737, 340}, {742, 340}, {747, 340},
              {763.5f, 333.5f}, {764.5f, 333.5f}, {742.5f, 343.5f}, {740.5f, 344.5f}, {738.5f, 345.5f},
              {612.5f, 388.5f}, {612.5f, 389.5f}, {613.5f, 389.5f}, {613.5f, 390.5f}, {614.5f, 390.5f},
              {614.5f, 391.5f}, {615.5f, 391.5f}, {615.5f, 392.5f}, {616.5f, 392.5f}, {615.5f, 393.5f},
              {616.5f, 393.5f}, {617.5f, 393.5f} }) {
            var origin = new Vector3(pixel[0] / 1440f * 2 - 1, 1 - pixel[1] / 1080f * 2, -1)
                  .prj(camera.camera.invProjectionView);
            var ray = new Ray(origin, camera.camera.direction);
            assertTrue(faces.stream().anyMatch(face -> {
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                return normal.dot(ray.direction) < 0
                      && Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), new Vector3());
            }), "The visible road and cliff must cover the rim instead of exposing sky at pixel "
                  + pixel[0] + "," + pixel[1]);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aGradedRimKeepsTheNativeCliffBelowTheRoadContact(int direction) {
        var at = BoardScenes.CENTER;
        var approach = at.translated(direction);
        var continuation = at.translated((direction + 1) % 6);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(at) ? (1 << direction) | (1 << ((direction + 1) % 6)) : 0,
              c.equals(at) || c.equals(continuation) ? 6 : c.equals(approach) ? 5 : 0, BoardScene.Surface.SAND));
        var natural = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.NONE, 0,
              scene.tile(c).elevation(), BoardScene.Surface.SAND));
        int edge = Math.floorMod(2 - direction, 6);
        for (var lod : TerrainLod.values()) {
            var surface = new BoardSurface(scene, scene.tile(at), lod);
            var nativeSurface = new BoardSurface(natural, natural.tile(at), lod);
            var walls = surface.walls(scene, BoardGeometry.floor(scene));
            var rendered = GpuTerrain.prepareWalls(scene, surface, BoardGeometry.floor(scene), Map.of());
            assertEquals(walls.stream().filter(face -> face.landEdge() == edge).toList(), rendered.entrySet().stream()
                  .filter(entry -> entry.getKey().edge() == edge).flatMap(entry -> entry.getValue().stream()).toList(),
                  "Rendering must reuse the completed cliff, without rebuilding overlapping walls per road segment");
            // The cliff below the road recedes from its verge as one band (BoardRelief#roadPush): the shared relief keeps
            // its native shape, moved alike at every height, and the emitted walls keep that relief.
            Vector3 shift = null;
            for (int level : new int[] { 2, 3, 4 }) {
                var nativePoint = nativeSurface.relief.wetCliffContact(edge, .5f, level * BoardGeometry.level());
                var point = surface.relief.wetCliffContact(edge, .5f, level * BoardGeometry.level());
                var moved = new Vector3(point).sub(nativePoint);
                if (shift == null) { shift = moved; }
                assertTrue(moved.dst(shift) < .01f, lod + " graded cliff lost its native shape by " + moved.dst(shift));
                float nearest = Float.POSITIVE_INFINITY;
                for (var face : walls) {
                    if (face.landEdge() != edge) { continue; }
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        nearest = Math.min(nearest, point.dst(p));
                    }
                }
                assertTrue(nearest < .01f, lod + " graded cliff departed from the shared relief by " + nearest);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aGradedExitKeepsTheSharedCliffProfileOnAnUnchangedEdge(int direction) {
        var at = BoardScenes.CENTER;
        var approach = at.translated(direction);
        var continuation = at.translated((direction + 1) % 6);
        for (var lod : TerrainLod.values()) {
            var paved = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? (1 << direction) | (1 << ((direction + 1) % 6)) : 0,
                  c.equals(at) || c.equals(continuation) ? 3 : c.equals(approach) ? 2 : 0, BoardScene.Surface.GRASS));
            var roadSurface = new BoardSurface(paved, paved.tile(at), lod);
            var actual = roadSurface.walls(paved, BoardGeometry.floor(paved));
            int opposite = Math.floorMod(1 - direction - 3, 6);
            for (int level : new int[] { 1, 2 }) {
                // Sample the shared relief engine, including its road clearance. An unrelated road grade
                // must not replace its rock ledges with a straight foot-to-rim retaining wall.
                var p = roadSurface.relief.wetCliffContact(opposite, .5f, level * BoardGeometry.level());
                float nearest = Float.POSITIVE_INFINITY;
                for (var face : actual) {
                    if (face.landEdge() != opposite) { continue; }
                    for (var q : List.of(face.a(), face.b(), face.c())) {
                        nearest = Math.min(nearest, p.dst(q));
                    }
                }
                assertTrue(nearest < .01f, lod + " road wall departed from shared relief by " + nearest + " at " + p);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = BoardScene.Surface.class, names = "CONCRETE", mode = EnumSource.Mode.EXCLUDE)
    void nativeSlopesJoinRoadsAboveAndBelowInEveryNaturalFamily(BoardScene.Surface family) {
        var at = BoardScenes.CENTER;
        for (int rise : new int[] { -2, -1, 1, 2 }) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 9 : 0,
                  c.equals(at.translated(2)) ? rise : 0, family));
            assertClosed(scene, at);
            var surface = new BoardSurface(scene, scene.tile(at));
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                assertTrue(normal.z > 0, family + " roadside top must never fold into the road");
            }
        }
    }

    @Test
    void levelRoadsOnFlatGroundDoNotAddTerrainSubdivisions() {
        var at = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED, 9, 0,
              BoardScene.Surface.SAND));
        var surface = new BoardSurface(scene, scene.tile(at));
        assertTrue(surface.faces.size() <= 6, "Flat road hexes use the existing small terrain carrier");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void nativeGroundMatchesTheUnsplitEdgeOfAFlatRoadAtEveryDetail(int edge) {
        var at = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 9 : 0, 0,
              BoardScene.Surface.SAND));
        var neighbor = scene.tile(at.translated(BoardGeometry.edgeDirection(edge)));
        var a = BoardGeometry.corner(at, 0, edge);
        var b = BoardGeometry.corner(at, 0, (edge + 1) % 6);
        for (var lod : TerrainLod.values()) {
            var surface = new BoardSurface(scene, neighbor, lod);
            boolean matched = false;
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) {
                    var p = points.get(i);
                    var q = points.get((i + 1) % 3);
                    matched |= p.epsilonEquals(a, .0001f) && q.epsilonEquals(b, .0001f)
                          || p.epsilonEquals(b, .0001f) && q.epsilonEquals(a, .0001f);
                }
            }
            assertTrue(matched, lod + " must share the road's whole edge; collinear T junctions can leave sky pixels");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void roadsBesideStepsKeepTheNativeSlopeOutsideTheirCorridor(int rise) {
        var at = BoardScenes.CENTER;
        var low = at.translated(0);
        var natural = scene(at, low, rise, false);
        var paved = scene(at, low, rise, true);
        var nativeSurface = new BoardSurface(natural, natural.tile(at));
        var roadSurface = new BoardSurface(paved, paved.tile(at));
        var original = nativeSurface.walls(natural, BoardGeometry.floor(natural));
        var actual = roadSurface.walls(paved, BoardGeometry.floor(paved));
        int compared = 0;
        for (var face : original) {
            if (face.landEdge() < 0 || BoardGeometry.edgeDirection(face.landEdge()) != 0) { continue; }
            for (var p : List.of(face.a(), face.b(), face.c())) {
                // The road is in the lower hex's far half; it must not squeeze this entire slope against the hex edge.
                float nearest = Float.POSITIVE_INFINITY;
                for (var candidate : actual) {
                    for (var q : List.of(candidate.a(), candidate.b(), candidate.c())) { nearest = Math.min(nearest, p.dst(q)); }
                }
                assertTrue(nearest < .01f, "An untouched native slope moved by " + nearest + " at " + p);
                compared++;
            }
        }
        assertTrue(compared > 0);
    }

    private static BoardScene scene(Coords at, Coords low, int rise, boolean road) {
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              road && c.equals(low) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              road && c.equals(low) ? 3 : 0, c.equals(at) ? rise : 0, BoardScene.Surface.SAND));
    }

    @ParameterizedTest
    @ValueSource(ints = { -2, 2 })
    void concreteRampsClipEachGradedFaceOnceAgainstTheCorridor(int rise) {
        // Clipping every corridor triangle against every graded face multiplied the two tessellations into
        // 8,000-14,000 slab triangles per ramp hex (MesaCity1 N); a face clipped once keeps the same surface.
        var at = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 9 : 0,
              c.equals(at.translated(0)) ? rise : 0, BoardScene.Surface.CONCRETE));
        var surface = new BoardSurface(scene, scene.tile(at));
        assertTrue(BoardSurface.ramps(scene, scene.tile(at)) != 0, "the slab must carry a ramp approach");
        long top = surface.groundFaces().stream().filter(face -> face.finish() == BoardSurface.Finish.TOP).count();
        // Non-ramp edges also retain the grade samples needed by a widened approach's vertical curve.
        assertTrue(top < 1600, "concrete ramp slab emitted " + top + " top triangles");
        assertClosed(scene, at);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadSlopesCloseTheirTopsFeetAndThreeHexCorners(int direction) {
        var at = BoardScenes.CENTER;
        var low = at.translated(direction);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(low) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(low) ? 1 << direction : 0, c.equals(at) ? 2 : 0, BoardScene.Surface.SAND));
        assertClosed(scene, at);
    }

    private record Segment(Vector3 a, Vector3 b, Coords owner, BoardSurface.Face face) { }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void climbingJunctionsCloseTheSurroundingNativeSlopes(int layout) {
        var at = BoardScenes.CENTER;
        int[] rises = layout == 0 ? new int[] { 2, 2, 2, 2, 2, 2 }
              : layout == 1 ? new int[] { -2, -2, -2, -2, -2, -2 } : new int[] { -2, 1, 2, -1, 0, 2 };
        assertClosed(junction(at, rises), at);
    }

    /** Six roads of the given rises meeting a six-way junction at level 2 on sand. */
    static BoardScene junction(Coords at, int[] rises) {
        return BoardScenes.scene(c -> {
            for (int d = 0; d < 6; d++) {
                if (c.equals(at.translated(d))) {
                    return BoardScenes.tile(c, BoardRoad.Kind.PAVED, 1 << ((d + 3) % 6), 2 + rises[d], BoardScene.Surface.SAND);
                }
            }
            return BoardScenes.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? 63 : 0, 2, BoardScene.Surface.SAND);
        });
    }

    /** A concrete junction with the given exits among neighbours of mixed rises. */
    static BoardScene concreteJunction(int exits) {
        var at = BoardScenes.CENTER;
        int[] rises = { -2, 1, 2, -1, 0, 2 };
        return BoardScenes.scene(c -> {
            for (int d = 0; d < 6; d++) {
                if (c.equals(at.translated(d))) {
                    return BoardScenes.tile(c, BoardRoad.Kind.PAVED, (exits & 1 << d) == 0 ? 0 : 1 << ((d + 3) % 6),
                          2 + rises[d], BoardScene.Surface.CONCRETE);
                }
            }
            return BoardScenes.tile(c, c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(at) ? exits : 0, 2, BoardScene.Surface.CONCRETE);
        });
    }

    /** Check the emitted mesh, including T junctions, rather than just comparing the boundary helper to itself. */
    private static void assertClosed(BoardScene scene, Coords at) {
        assertClosed(scene, at, TerrainLod.FULL);
    }

    static void assertClosed(BoardScene scene, Coords at, TerrainLod lod) {
        assertClosed(scene, at, lod, p -> true);
    }

    /** As {@link #assertClosed(BoardScene, Coords, TerrainLod)}, for the boundary points {@code where} accepts. */
    static void assertClosed(BoardScene scene, Coords at, TerrainLod lod, Predicate<Vector3> where) {
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        for (var tile : scene.tiles()) {
            if (at.distance(tile.coords()) <= 2) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
        }
        float level = BoardGeometry.level();
        Map<String, List<Segment>> edges = new HashMap<>();
        List<BoardSurface.Face> all = new ArrayList<>(), road = new ArrayList<>();
        for (var surface : surfaces.values()) {
            road.addAll(BoardSurface.road(scene, surface.tile));
            var faces = new ArrayList<>(surface.groundFaces());
            faces.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
            for (var face : faces) {
                if (face.finish() == BoardSurface.Finish.DRESSING || face.finish() == BoardSurface.Finish.OUTCROP) { continue; }
                all.add(face);
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) {
                    var a = points.get(i);
                    var b = points.get((i + 1) % 3);
                    String first = key(a), second = key(b);
                    // Retain short T-junction segments for coverage, excluding only a keyed vertex's self-edge.
                    if (first.equals(second)) { continue; }
                    String key = first.compareTo(second) < 0 ? first + "/" + second : second + "/" + first;
                    edges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Segment(a, b, surface.tile.coords(), face));
                }
            }
        }
        List<Segment> open = edges.values().stream().filter(list -> list.size() == 1).map(List::getFirst).toList();
        var center = BoardGeometry.center(at, 0);
        List<String> gaps = new ArrayList<>();
        for (var edge : open) {
            for (float t : new float[] { .25f, .5f, .75f }) {
                var p = new Vector3(edge.a()).lerp(edge.b(), t);
                if (Math.hypot(p.x - center.x, p.y - center.y) > BoardGeometry.width() * .7f || !where.test(p)) { continue; }
                boolean covered = false;
                for (var other : open) {
                    if (other == edge) { continue; }
                    var delta = new Vector3(other.b()).sub(other.a());
                    float u = Math.clamp(new Vector3(p).sub(other.a()).dot(delta) / delta.len2(), 0, 1);
                    if (p.dst2(new Vector3(other.a()).mulAdd(delta, u)) < .0001f) { covered = true; break; }
                }
                // An edge lying on another face is closed by it (a T junction with its interior); one under a road's
                // surface is covered by the road, whose edge is closed down to the ground.
                for (var face : all) {
                    if (covered) { break; }
                    covered = face != edge.face() && onFace(p, face) < .01f;
                }
                covered |= p.z <= over(road, p) + .01f * level;
                if (!covered && gaps.size() < 12) { gaps.add(p + " on " + edge); }
            }
        }
        assertTrue(gaps.isEmpty(), lod + " open terrain boundaries near " + at + ": " + gaps);
    }

    /** Distance from a point to a face along its normal, or infinity where the point lies outside the face. */
    private static float onFace(Vector3 p, BoardSurface.Face face) {
        var ab = new Vector3(face.b()).sub(face.a());
        var ac = new Vector3(face.c()).sub(face.a());
        var normal = new Vector3(ab).crs(ac);
        if (normal.len2() < 1e-12f) { return Float.POSITIVE_INFINITY; }
        normal.nor();
        float distance = new Vector3(p).sub(face.a()).dot(normal);
        var aq = new Vector3(p).mulAdd(normal, -distance).sub(face.a());
        float d00 = ab.dot(ab), d01 = ab.dot(ac), d11 = ac.dot(ac), d20 = aq.dot(ab), d21 = aq.dot(ac);
        float den = d00 * d11 - d01 * d01;
        float v = (d11 * d20 - d01 * d21) / den, w = (d00 * d21 - d01 * d20) / den;
        return v >= -1e-4f && w >= -1e-4f && v + w <= 1 + 1e-4f ? Math.abs(distance) : Float.POSITIVE_INFINITY;
    }

    /** The road's height over a point inside its plan, a little away from its edge; NaN elsewhere. */
    private static float over(List<BoardSurface.Face> road, Vector3 p) {
        float margin = .05f * BoardGeometry.hexScale(), height = Float.NaN;
        for (float[] offset : new float[][] { { 0, 0 }, { margin, 0 }, { -margin, 0 }, { 0, margin }, { 0, -margin } }) {
            float highest = Float.NaN;
            for (var face : road) {
                float h = face.height(p.x + offset[0], p.y + offset[1]);
                if (Float.isFinite(h) && !(h <= highest)) { highest = h; }
            }
            if (Float.isNaN(highest)) { return Float.NaN; }
            if (offset[0] == 0 && offset[1] == 0) { height = highest; }
        }
        return height;
    }

    private static String key(Vector3 p) {
        return Math.round(p.x * 1000) + ":" + Math.round(p.y * 1000) + ":" + Math.round(p.z * 1000);
    }

    @Test
    void connectedRoadsMeetWithoutAnExposedStepAndPickingFollowsTheCut() {
        BoardScene scene = BoardScenes.roadScene(false);
        BoardSurface high = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
        BoardSurface low = new BoardSurface(scene, scene.tile(BoardScenes.SECOND));
        Vector3 gate = BoardGeometry.corner(BoardScenes.FIRST, 0, 0).lerp(BoardGeometry.corner(BoardScenes.FIRST, 0, 1), 0.5f);
        assertEquals(BoardGeometry.LEVEL, high.height(gate.x, gate.y), 0.01f);
        assertEquals(high.height(gate.x, gate.y), low.height(gate.x, gate.y), 0.01f);
        Vector3 highCenter = BoardGeometry.center(BoardScenes.FIRST, 2);
        Vector3 lowCenter = BoardGeometry.center(BoardScenes.SECOND, 0);
        Vector3 cut = new Vector3(gate).lerp(highCenter, 0.15f);
        Vector3 ramp = new Vector3(gate).lerp(lowCenter, 0.15f);
        assertTrue(high.height(cut.x, cut.y) < highCenter.z);
        assertTrue(low.height(ramp.x, ramp.y) > lowCenter.z);
        assertEquals(BoardScenes.FIRST, BoardGeometry.pick(scene, new Ray(new Vector3(cut.x, cut.y, 200), new Vector3(0, 0, -1))));
        assertEquals(BoardScenes.SECOND, BoardGeometry.pick(scene, new Ray(new Vector3(ramp.x, ramp.y, 200), new Vector3(0, 0, -1))));
        UnitMotion movement = new UnitMotion(new BoardScene.Waypoint(BoardScenes.FIRST, 2, 1));
        movement.append(List.of(new BoardScene.Waypoint(BoardScenes.FIRST, 2, 1), new BoardScene.Waypoint(BoardScenes.SECOND, 0, 1)),
              EntityMovementType.MOVE_WALK, 0);
        movement.advance(movement.remainingSeconds() * 0.4, 1);
        Vector3 moving = movement.surfacePosition(scene);
        assertEquals(high.height(moving.x, moving.y), moving.z, 0.01f, "Walking must not sink into the upper road shoulder");
    }

    @Test
    void aRoadEndingAtUnpavedGroundCarriesTheWholeRampInEveryDirection() {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean roadOnHighSide : List.of(true, false)) {
                BoardScene scene = BoardScenes.roadScene(false, direction, roadOnHighSide, !roadOnHighSide, 2);
                Coords neighbor = BoardScenes.FIRST.translated(direction);
                BoardSurface high = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
                BoardSurface low = new BoardSurface(scene, scene.tile(neighbor));
                Vector3 highCenter = BoardGeometry.center(BoardScenes.FIRST, 2);
                Vector3 lowCenter = BoardGeometry.center(neighbor, 0);
                Vector3 gate = new Vector3(highCenter).lerp(lowCenter, 0.5f);
                // The road ramps all the way to the unpaved hex's level at their edge; the unpaved hex keeps its own.
                float unpaved = roadOnHighSide ? lowCenter.z : highCenter.z;
                assertEquals(unpaved, high.height(gate.x, gate.y), 0.01f);
                assertEquals(unpaved, low.height(gate.x, gate.y), 0.01f);
                Vector3 cut = new Vector3(gate).lerp(highCenter, 0.15f);
                Vector3 fill = new Vector3(gate).lerp(lowCenter, 0.15f);
                if (roadOnHighSide) {
                    assertTrue(high.height(cut.x, cut.y) < highCenter.z, "The road descends through its own hex");
                    assertEquals(lowCenter.z, low.height(fill.x, fill.y), 0.01f, "The unpaved hex below is not filled");
                } else {
                    assertTrue(low.height(fill.x, fill.y) > lowCenter.z, "The road climbs through its own hex");
                    assertEquals(highCenter.z, high.height(cut.x, cut.y), 0.01f, "The unpaved hex above is not cut");
                }
                UnitMotion movement = new UnitMotion(new BoardScene.Waypoint(BoardScenes.FIRST, 2, direction));
                movement.append(List.of(new BoardScene.Waypoint(BoardScenes.FIRST, 2, direction),
                      new BoardScene.Waypoint(neighbor, 0, direction)), EntityMovementType.MOVE_WALK, 0);
                double duration = movement.remainingSeconds();
                movement.advance(duration * 0.4, 1);
                Vector3 moving = movement.surfacePosition(scene);
                assertEquals(high.height(moving.x, moving.y), moving.z, 0.01f);
                movement.advance(duration * 0.2, 1);
                moving = movement.surfacePosition(scene);
                assertEquals(low.height(moving.x, moving.y), moving.z, 0.01f);
            }
        }
        BoardScene scene = BoardScenes.roadScene(false, 1, false, false, 2);
        BoardSurface high = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
        assertEquals(0, high.ramps, "Ground without an edge-reaching road retains the cliff");
        Vector3 center = BoardGeometry.center(BoardScenes.FIRST, 2);
        assertEquals(center.z, high.height(center.x, center.y), .001f,
              "An eroded cliff rim preserves the level interior and introduces no road ramp");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadsMeetBridgeDecksWithoutCuttingOrFillingTheGroundBelow(int direction) {
        for (boolean roadUnderBridge : List.of(false, true)) {
            BoardScene scene = bridgeScene(direction, (direction + 3) % 6, 2, roadUnderBridge);
            Coords neighbor = BoardScenes.FIRST.translated(direction);
            BoardSurface road = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
            BoardSurface belowBridge = new BoardSurface(scene, scene.tile(neighbor));
            Vector3 gate = BoardGeometry.center(BoardScenes.FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
            assertEquals(0, road.height(gate.x, gate.y), 0.01f,
                  "The road must stay level with the connecting bridge deck");
            assertEquals(-2 * BoardGeometry.LEVEL, belowBridge.height(gate.x, gate.y), 0.01f,
                  "The bridge approach must not raise the ground beneath the deck");
            Vector3 approach = new Vector3(gate).lerp(BoardGeometry.center(BoardScenes.FIRST, 0), 0.15f);
            BoardGeometry.Hit hit = BoardGeometry.hit(scene,
                  new Ray(new Vector3(approach.x, approach.y, 200), new Vector3(0, 0, -1)));
            assertNotNull(hit);
            assertEquals(BoardScenes.FIRST, hit.coords());
            assertEquals(200, Math.sqrt(hit.distance()), 0.01, "Picking must follow the flat bridge approach");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadsKeepGroundBelowRaisedBridgeRampsAndCutDownToLowerDecks(int direction) {
        for (int roadElevation : new int[] { 0, 1, 2 }) {
            for (boolean water : List.of(false, true)) {
                BoardScene scene = bridgeScene(direction, (direction + 3) % 6, 1, water, roadElevation, 0, water);
                Coords neighbor = BoardScenes.FIRST.translated(direction);
                BoardSurface road = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
                BoardSurface belowBridge = new BoardSurface(scene, scene.tile(neighbor));
                Vector3 center = BoardGeometry.center(BoardScenes.FIRST, roadElevation);
                Vector3 gate = new Vector3(center).lerp(BoardGeometry.center(neighbor, 1), 0.5f);
                assertEquals(roadElevation == 0 ? center.z : gate.z, road.height(gate.x, gate.y), 0.01f,
                      "Raised approaches are separate solids; only a descending approach cuts the bank");
                assertEquals(center.z, road.height(center.x, center.y), 0.01f);
                Vector3 hub = new Vector3(center).lerp(gate, .5f);
                hub.z = center.z;
                assertEquals(hub.z, road.height(hub.x, hub.y), .01f,
                      "Only the inset half of each connected hex is graded");
                Vector3 halfway = new Vector3(hub).lerp(gate, .5f);
                if (roadElevation > 0) {
                    assertEquals((hub.z + road.height(gate.x, gate.y)) / 2, road.height(halfway.x, halfway.y), .01f,
                          "The road retains the bridge's planar grade through the shared edge");
                }
                assertEquals(0, belowBridge.height(gate.x, gate.y), 0.01f,
                      "The bridge approach must not deform the ground beneath the deck");
                Vector3 bridgeCenter = BoardGeometry.center(neighbor, 0);
                assertEquals(BoardGeometry.groundZ(scene.tile(neighbor)),
                      belowBridge.height(bridgeCenter.x, bridgeCenter.y), 0.01f);
                Vector3 approach = new Vector3(gate).lerp(center, 0.25f);
                float height = roadElevation == 0 ? 0 : (3 * roadElevation + 1) * BoardGeometry.LEVEL / 4;
                assertEquals(height, road.height(approach.x, approach.y), 0.01f,
                      "The existing road corridor must slope between the unchanged hub and the deck");
                BoardGeometry.Hit hit = BoardGeometry.hit(scene,
                      new Ray(new Vector3(approach.x, approach.y, 200), new Vector3(0, 0, -1)));
                assertNotNull(hit);
                assertEquals(BoardScenes.FIRST, hit.coords());
                assertEquals(200 - height, Math.sqrt(hit.distance()), 0.01);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aConnectingLandRoadWinsOverABridgeAtADifferentLevel(int direction) {
        for (int roadElevation : new int[] { 0, 2 }) {
            for (int bridgeHeight : new int[] { 1, 3 }) {
                BoardScene scene = bridgeScene(direction, (direction + 3) % 6, bridgeHeight, true,
                      roadElevation, 0, false);
                Coords neighbor = BoardScenes.FIRST.translated(direction);
                Vector3 gate = BoardGeometry.center(BoardScenes.FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
                float height = roadElevation * BoardGeometry.LEVEL / 2;
                assertEquals(height, new BoardSurface(scene, scene.tile(BoardScenes.FIRST)).height(gate.x, gate.y), 0.01f);
                assertEquals(height, new BoardSurface(scene, scene.tile(neighbor)).height(gate.x, gate.y), 0.01f,
                      "Both ground roads must meet, even when an aligned bridge is within one level");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void aBridgeRampRequiresARoadExitTowardTheBridge(int direction) {
        for (int exits : new int[] { 0, 1 << ((direction + 1) % 6) }) {
            BoardScene scene = bridgeScene(direction, (direction + 3) % 6, 1, false, 0, 0, true);
            List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
            BoardScene.Tile tile = scene.tile(BoardScenes.FIRST);
            tiles.set(BoardScenes.FIRST.getX() * scene.height() + BoardScenes.FIRST.getY(), new BoardScene.Tile(BoardScenes.FIRST, 0, -1, false,
                  exits, tile.surface(), null, null, List.of(), List.of(), BoardLiquid.NONE,
                  null, false, exits == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED));
            scene = new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
            Vector3 gate = BoardGeometry.center(BoardScenes.FIRST, 0)
                  .lerp(BoardGeometry.center(BoardScenes.FIRST.translated(direction), 0), 0.5f);
            assertEquals(0, new BoardSurface(scene, scene.tile(BoardScenes.FIRST)).height(gate.x, gate.y), 0.01f);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void unrelatedBridgeArmsAndOverpassesKeepTheGroundRoadApproach(int direction) {
        int reverse = (direction + 3) % 6;
        for (int bridgeDirection = 0; bridgeDirection < 6; bridgeDirection++) {
            for (int bridgeHeight : new int[] { 0, 1, 2, 3, 4 }) {
                for (boolean roadUnderBridge : List.of(false, true)) {
                    if (bridgeDirection == reverse && Math.abs(bridgeHeight - 2) <= 1
                          && (bridgeHeight == 2 || !roadUnderBridge)) {
                        continue;
                    }
                    BoardScene scene = bridgeScene(direction, bridgeDirection, bridgeHeight, roadUnderBridge);
                    Coords neighbor = BoardScenes.FIRST.translated(direction);
                    Vector3 gate = BoardGeometry.center(BoardScenes.FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
                    // Two ground roads meet halfway; one ending at the unpaved ground carries the whole ramp down to it.
                    float approach = (roadUnderBridge ? -1 : -2) * BoardGeometry.LEVEL;
                    assertEquals(approach, new BoardSurface(scene, scene.tile(BoardScenes.FIRST)).height(gate.x, gate.y), 0.01f);
                    assertEquals(approach, new BoardSurface(scene, scene.tile(neighbor)).height(gate.x, gate.y), 0.01f);
                }
            }
        }
    }

    @Test
    void largeCliffsLeaveRoadEndsFlatUnlessBothRoadExitsConnect() {
        for (int direction = 0; direction < 6; direction++) {
            Coords neighbor = BoardScenes.FIRST.translated(direction);
            Vector3 gate = BoardGeometry.center(BoardScenes.FIRST, 0).lerp(BoardGeometry.center(neighbor, 0), 0.5f);
            for (int elevation : new int[] { -3, 3 }) {
                for (boolean roadOnFirstSide : List.of(true, false)) {
                    BoardScene scene = BoardScenes.roadScene(false, direction, roadOnFirstSide, !roadOnFirstSide, elevation);
                    BoardSurface first = new BoardSurface(scene, scene.tile(BoardScenes.FIRST));
                    BoardSurface second = new BoardSurface(scene, scene.tile(neighbor));
                    assertEquals(elevation * BoardGeometry.LEVEL, first.height(gate.x, gate.y), 0.01f,
                          "A road end at a large cliff must retain its own elevation");
                    assertEquals(0, second.height(gate.x, gate.y), 0.01f,
                          "The adjacent hex must also retain its flat surface");
                }
                BoardScene connected = BoardScenes.roadScene(false, direction, true, true, elevation);
                BoardSurface first = new BoardSurface(connected, connected.tile(BoardScenes.FIRST));
                BoardSurface second = new BoardSurface(connected, connected.tile(neighbor));
                assertEquals(elevation * BoardGeometry.LEVEL / 2, first.height(gate.x, gate.y), 0.01f);
                assertEquals(first.height(gate.x, gate.y), second.height(gate.x, gate.y), 0.01f,
                      "An actual connecting road still meets at the shared ramp height");
            }
        }
    }

    private static BoardScene bridgeScene(int direction, int bridgeDirection, int bridgeHeight, boolean roadUnderBridge) {
        return bridgeScene(direction, bridgeDirection, bridgeHeight, roadUnderBridge, 0, -2, false);
    }

    private static BoardScene bridgeScene(int direction, int bridgeDirection, int bridgeHeight, boolean roadUnderBridge,
          int roadElevation, int bridgeElevation, boolean water) {
        BoardScene scene = BoardScenes.roadScene(false, direction, true, roadUnderBridge, roadElevation);
        Coords coords = BoardScenes.FIRST.translated(direction);
        Hex hex = new Hex(bridgeElevation);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 1 << bridgeDirection));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, bridgeHeight));
        BoardScene.Tile tile = scene.tile(coords);
        List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
        tiles.set(coords.getX() * scene.height() + coords.getY(), new BoardScene.Tile(coords, hex.getLevel(),
              water ? 1 : -1, false, tile.roadExits(), tile.surface(), null, null,
              BoardFeatures.capture(hex, coords, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null,
              false, tile.road()));
        return new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    static Stream<Arguments> specimens() {
        var cases = new ArrayList<Arguments>();
        for (String id : List.of("level-toe-straight", "level-toe-curve")) {
            for (boolean concrete : new boolean[] { false, true }) {
                for (var panel : RoadGradeFixture.Panel.values()) {
                    for (int direction = 0; direction < 6; direction++) {
                        for (int parity = 0; parity < 2; parity++) {
                            cases.add(Arguments.of(id, concrete, panel, direction, parity));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, concrete={1}, {2}, direction={3}, parity={4}")
    @MethodSource("specimens")
    void equalLowerPlateausGrowNoFinOutsideTheReservedRoad(String id, boolean concrete,
          RoadGradeFixture.Panel panel, int direction, int parity) {
        var board = RoadGradeFixture.focused(concrete, panel, id, direction, parity);
        var scene = RoadGradeFixture.scene(board);
        var at = RoadGradeFixture.focusedCenter(parity);
        BoardRoadGradeFixtureTest.assertSupportedGradesAndMouths(scene, at, id);
        for (int side : new int[] { 1, 5 }) {
            var bank = at.translated((direction + side) % 6);
            var upper = at.translated(direction);
            boolean covered = false;
            for (int corner = 0; corner < 6; corner++) {
                var p = BoardGeometry.corner(bank, 0, corner);
                if (cornerOf(at, p) && cornerOf(upper, p)) { covered = covered(scene, bank, p); }
            }
            if (scene.tile(bank).wayExits() != 0 || covered) { continue; }
            for (var lod : TerrainLod.values()) { assertPlateau(scene, bank, lod); }
        }
    }

    @Test
    void reportedMinesAndCityLowerGroundKeepsItsAuthoredPlateau() {
        for (String path : List.of("Deserts/16x17 Mines 1.board", "Map Set 3/16x17 City Hills Residential 1.board")) {
            var board = new Board();
            board.load(new File("data/boards/" + path));
            var scene = RoadGradeFixture.scene(board);
            var banks = path.startsWith("Deserts") ? List.of(new Coords(3, 5), new Coords(4, 7))
                  : List.of(new Coords(14, 5));
            for (var bank : banks) {
                assertTrue(!coveredCorner(scene, bank), "The reported raised toe lies outside the actual road");
                for (var lod : TerrainLod.values()) {
                    assertPlateau(scene, bank, lod);
                    BoardRoadSlopeTest.assertClosed(scene, bank, lod);
                }
            }
        }
    }

    /**
     * The plateau keeps its level, moved only by the bank every hex shares near roads (Enhanced): an embankment of a
     * higher road beside it may round onto it, but nothing else raises it.
     */
    private static void assertPlateau(BoardScene scene, Coords at, TerrainLod lod) {
        var tile = scene.tile(at);
        var surface = new BoardSurface(scene, tile, lod);
        var bank = new BoardRoadBank(scene, tile, surface.relief);
        float level = tile.elevation() * BoardGeometry.level();
        for (var face : surface.groundFaces()) {
            if (face.finish() != BoardSurface.Finish.TOP) { continue; }
            for (var p : List.of(face.a(), face.b(), face.c())) {
                assertEquals(bank.active() ? bank.height(p.x, p.y, level) : level, p.z, .006f,
                      "An uncovered lower plateau must not grow a road-corner fin: " + at + "/" + lod + " " + p);
            }
        }
    }

    private static boolean coveredCorner(BoardScene scene, Coords at) {
        for (int corner = 0; corner < 6; corner++) {
            var p = BoardGeometry.corner(at, 0, corner);
            if (covered(scene, at, p)) { return true; }
        }
        return false;
    }

    private static boolean cornerOf(Coords at, Vector3 point) {
        for (int corner = 0; corner < 6; corner++) {
            if (BoardGeometry.corner(at, 0, corner).dst2(point) < .0001f) { return true; }
        }
        return false;
    }

    private static boolean covered(BoardScene scene, Coords at, Vector3 point) {
        for (var tile : scene.tiles()) {
            if (tile.coords().distance(at) > 1 || tile.wayExits() == 0) { continue; }
            var origin = BoardGeometry.center(tile.coords(), 0);
            var local = new Vector3(point).sub(origin).scl(1 / BoardGeometry.hexScale());
            if (BoardRoad.way(scene, tile).distance(local.x, local.y) <= BoardRoad.SHOULDER) { return true; }
        }
        return false;
    }
}
