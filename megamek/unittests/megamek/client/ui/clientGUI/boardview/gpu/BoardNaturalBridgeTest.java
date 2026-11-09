/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class BoardNaturalBridgeTest {

    @Test
    void classicCapIsAFullFlatHexWithTheSameOpenUnderpassAtEveryDetail() {
        for (int d = 0; d < 6; d++) {
            var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, true, false, BoardScenes.CENTER, d);
            var tile = scene.tile(BoardScenes.CENTER);
            var deck = BoardBridge.deck(scene, tile);
            var center = BoardGeometry.center(BoardScenes.CENTER, 3);
            for (var lod : TerrainLod.values()) {
                var shape = BoardNaturalBridge.build(scene, tile, deck, lod, new HashMap<>(), true);
                for (int edge = 0; edge < 6; edge++) {
                    var a = BoardGeometry.corner(BoardScenes.CENTER, 3, edge);
                    var b = BoardGeometry.corner(BoardScenes.CENTER, 3, edge + 1);
                    for (float along : new float[] { 0, .5f, 1 }) {
                        var point = new Vector3(a).lerp(b, along).lerp(center, .001f);
                        assertEquals(center.z, BoardBridge.top(shape, point.x, point.y), .001f,
                              "The normal hex's complete perimeter remains at deck height: " + d + "/" + lod);
                    }
                }
                assertTrue(shape.facets().stream().filter(f -> f.part() == BoardBridge.Part.TOP)
                      .allMatch(f -> f.normal().epsilonEquals(Vector3.Z, .001f)));
                assertTrue(shape.facets().stream().anyMatch(f -> f.part() == BoardBridge.Part.SOFFIT && f.normal().z < -.5f));
                assertEquals(Float.POSITIVE_INFINITY, shape.hit(new Ray(
                      BoardGeometry.center(BoardScenes.CENTER, 2).add(-100, 0, 0), Vector3.X)),
                      "A full hex cap does not fill the river passage below it");
            }
        }
    }

    @Test
    void classicCapGradesOntoLowerBanksWithOrWithoutRoadTerrain() {
        for (int direction = 0; direction < 6; direction++) {
            for (boolean paved : new boolean[] { false, true }) {
                var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, true, false, BoardScenes.CENTER, direction);
                int exits = (1 << direction) | (1 << ((direction + 3) % 6));
                for (int d : new int[] { direction, (direction + 3) % 6 }) {
                    var at = BoardScenes.CENTER.translated(d);
                    scene = BoardScenes.replace(scene, BoardScenes.tile(at, paved ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                          paved ? exits : 0, 2, BoardScene.Surface.GRASS));
                }
                var tile = scene.tile(BoardScenes.CENTER);
                var center = BoardGeometry.center(BoardScenes.CENTER, 3);
                for (var lod : TerrainLod.values()) {
                    var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod,
                          new HashMap<>(), true);
                    assertEquals(center.z, BoardBridge.top(shape, center.x, center.y), .001f);
                    for (int d : new int[] { direction, (direction + 3) % 6 }) {
                        var bank = scene.tile(BoardScenes.CENTER.translated(d));
                        var end = BoardGeometry.center(bank.coords(), 2);
                        var column = GpuTilesetTerrain.column(scene, bank, BoardGeometry.floor(scene));
                        for (float fraction : new float[] { .25f, .375f, .4999f, .5001f, .6f }) {
                            var p = new Vector3(center).lerp(end, fraction);
                            float height = fraction < .5f ? BoardBridge.top(shape, p.x, p.y)
                                  : BoardSurface.sampleHeight(column.top(), p.x, p.y, Float.NaN);
                            float expected = BoardGeometry.level() * (fraction <= .25f ? 3
                                  : fraction < .5f ? 4 - 4 * fraction : 2);
                            assertEquals(expected, height, .01f,
                                  "A continuous grade joins the cap to its actual bank: " + d + "/" + paved + "/" + lod);
                        }
                    }
                    var across = BoardGeometry.center(BoardScenes.CENTER.translated(direction), 0)
                          .sub(BoardGeometry.center(BoardScenes.CENTER, 0)).nor();
                    across.set(-across.y, across.x, 0);
                    var passage = BoardGeometry.center(BoardScenes.CENTER, 1).mulAdd(across, -100);
                    assertEquals(Float.POSITIVE_INFINITY, shape.hit(new Ray(passage, across)),
                          "The graded entrances leave the central river passage open");
                }
            }
        }
    }

    @Test
    void muddyShellDepthStartsAtItsActualTopEdgeOnDescendingDecks() {
        var scene = BridgeScenes.routeScene(BoardScenes.CENTER, 63, false);
        var tile = scene.tile(BoardScenes.CENTER);
        int loweredContacts = 0;
        for (var lod : TerrainLod.values()) {
            var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, new HashMap<>());
            var depths = GpuTerrain.naturalMudDepth(shape);
            var top = new HashSet<Vector3>();
            shape.facets().stream().filter(face -> face.part() == BoardBridge.Part.TOP)
                  .forEach(face -> { top.add(face.a()); top.add(face.b()); top.add(face.c()); });
            for (var entry : depths.entrySet()) {
                assertTrue(entry.getValue() >= 0 && entry.getValue() < 8, "The shell fits its local-depth encoding");
                if (top.contains(entry.getKey())) {
                    assertEquals(0, entry.getValue(), .001f, "Mud stays complete where TOP meets the draining shell");
                    if (shape.level() * BoardGeometry.level() - entry.getKey().z > BoardRelief.metres(1.1f)) {
                        loweredContacts++;
                    }
                }
            }
        }
        assertTrue(loweredContacts > 0, "Exercise edges below the nominal-level fade used before local depths");
    }


    @ParameterizedTest
    @EnumSource(value = BoardScene.Surface.class, names = { "GRASS", "DIRT", "SAND", "ROCK", "SNOW" })
    void bareSpansUseTheBanksSurfaceAndKeepTheirLowerPassageAtEveryLod(BoardScene.Surface family) {
        var scene = BridgeScenes.canyonScene(family, false, true);
        var tile = scene.tile(BoardScenes.CENTER);
        var deck = BoardBridge.deck(scene, tile);
        assertTrue(deck.natural(), "A road underneath is not a road attached to the deck");
        assertEquals(family, deck.surface());
        int previous = Integer.MAX_VALUE;
        var center = BoardGeometry.center(BoardScenes.CENTER, 3);
        var below = BoardGeometry.center(BoardScenes.CENTER, 2);
        for (var lod : TerrainLod.values()) {
            var shape = BoardNaturalBridge.build(scene, tile, deck, lod, new HashMap<>());
            assertTrue(shape.facets().size() < previous, "Each LOD must reduce submitted geometry");
            previous = shape.facets().size();
            assertEquals(100, shape.hit(new Ray(new Vector3(center).add(0, 0, 10), new Vector3(0, 0, -1))), .001f);
            assertEquals(Float.POSITIVE_INFINITY,
                  shape.hit(new Ray(new Vector3(below).add(-100, 0, 0), Vector3.X)),
                  "The legal whole levels beneath the deck stay clear of rock supports");
            assertTrue(shape.facets().stream().anyMatch(f -> f.part() == BoardBridge.Part.SOFFIT && f.normal().z < -.5f));
            for (var face : shape.facets()) {
                assertTrue(Float.isFinite(face.normal().len2()));
                assertEquals(1, face.normal().len(), .001f);
            }
        }
        // Exact bank-outline contacts and the rim's bank shoulders add bounded triangles: the five widened surface
        // families submit 138–178 at distance.
        assertTrue(previous <= 190,
              "A distant span needs only its silhouette and exact bank contacts: " + family + " submitted " + previous);
    }

    @Test
    void grassSpansAreBareRockThatTheirBanksCoverFadesOnto() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
        var tile = scene.tile(BoardScenes.CENTER);
        var deck = BoardBridge.deck(scene, tile);
        assertEquals(BoardScene.Surface.ROCK, BoardNaturalBridge.build(scene, tile, deck, TerrainLod.FULL, new HashMap<>()).surface());
        assertEquals(BoardScene.Surface.GRASS,
              BoardNaturalBridge.build(scene, tile, deck, TerrainLod.FULL, new HashMap<>(), true).surface(),
              "Classic keeps the meadow cap");
        var bank = scene.tile(BoardScenes.CENTER.translated(0));
        assertTrue(BoardSurfaceBlend.boundary(scene, bank), "A uniform meadow bank still blends onto the span's rock");
        var edge = BoardGeometry.center(BoardScenes.CENTER, 3).lerp(BoardGeometry.center(bank.coords(), 3), .5f);
        var inward = BoardGeometry.center(bank.coords(), 3).sub(edge).nor();
        var sampler = new BoardSurfaceBlend.Sampler(scene, bank);
        var lip = new Vector3(edge).mulAdd(inward, BoardRelief.metres(.5f));
        assertTrue(sampler.sample(lip.x, lip.y, lip.z).rock() > .95f, "The bank meets the span's top as its rock");
        var meadow = new Vector3(edge).mulAdd(inward, BoardRelief.metres(10));
        assertEquals(1, sampler.sample(meadow.x, meadow.y, meadow.z).grass(), .001f, "The fade stays a short band");
    }

    @Test
    void connectedDecksKeepTheSameOpenSeamAcrossDifferentLods() {
        for (int x : new int[] { 3, 4 }) {
            var start = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                var scene = BridgeScenes.straight(start, d, 2, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE,
                      BoardRoad.Kind.NONE);
                var a = scene.tile(start);
                var b = scene.tile(start.translated(d));
                var seam = BoardGeometry.center(start, 0).lerp(BoardGeometry.center(b.coords(), 0), .5f);
                var normal = BoardGeometry.center(b.coords(), 0).sub(BoardGeometry.center(start, 0)).nor();
                var full = BoardNaturalBridge.build(scene, a, BoardBridge.deck(scene, a), TerrainLod.FULL, new HashMap<>());
                var points = full.facets().stream().filter(f -> f.part() != BoardBridge.Part.SIDE)
                      .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream())
                      .filter(p -> Math.abs(new Vector3(p).sub(seam).dot(normal)) < .0001f).distinct().toList();
                assertEquals(4, points.size(), "Only the mouth's two top and two underside corners lie on the seam");
                var across = new Vector3(-normal.y, normal.x, 0);
                for (var point : points) {
                    float half = Math.abs(new Vector3(point).sub(seam).dot(across));
                    assertTrue(half >= BoardRelief.metres(8 * .96f) && half <= BoardRelief.metres(8 * 1.04f),
                          "Natural bridge mouths retain their doubled eight-metre half-width at every orientation");
                }
                for (var lod : TerrainLod.values()) {
                    var other = BoardNaturalBridge.build(scene, b, BoardBridge.deck(scene, b), lod, new HashMap<>());
                    var vertices = other.facets().stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                    for (var p : points) {
                        assertTrue(vertices.stream().anyMatch(q -> p.dst2(q) < .000001f), "No crack at a mixed-LOD join");
                    }
                    assertFalse(other.facets().stream().anyMatch(f -> List.of(f.a(), f.b(), f.c()).stream()
                          .allMatch(p -> Math.abs(new Vector3(p).sub(seam).dot(normal)) < .0001f)),
                          "Connected spans must not have an internal end wall");
                }
            }
        }
    }

    @Test
    void branchingLavaTubeSpansShareTheirEntireRockJoinAtEveryLod() throws Exception {
        var scene = BoardScenes.installedScene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        for (var tile : scene.tiles()) {
            if (BoardBridge.feature(tile) == null) { continue; }
            var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, new HashMap<>());
            var center = BoardGeometry.center(tile.coords(), shape.level());
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(tile.coords().translated(d));
                if (!BoardBridge.connected(tile, next, d)) { continue; }
                var gate = BoardGeometry.center(next.coords(), shape.level()).sub(center).scl(.5f);
                var seam = new Vector3(center).add(gate);
                var normal = new Vector3(gate).nor();
                // The mouth's corners, top and underside, or the hex corners that replace them where the joint closes
                // against its cliff. Those lie on the hex edge itself, a hair off the centres' midline, as the hex is
                // regular only to a 32nd. Beyond them, each span's own bank mouth meets the edge's line.
                var first = BoardGeometry.corner(tile.coords(), 0, Math.floorMod(1 - d, 6));
                var edge = BoardGeometry.corner(tile.coords(), 0, Math.floorMod(2 - d, 6)).sub(first).nor();
                float half = BoardRelief.metres(BoardBridge.NATURAL_HALF_WIDTH * 1.04f) + .001f;
                var points = shape.facets().stream().filter(f -> f.part() != BoardBridge.Part.SIDE)
                      .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream())
                      .filter(p -> (Math.abs(new Vector3(p).sub(seam).dot(normal)) < .0001f
                                  || Math.abs(edge.x * (p.y - first.y) - edge.y * (p.x - first.x)) < .001f)
                            && Vector3.dst(p.x, p.y, 0, seam.x, seam.y, 0) <= half).distinct().toList();
                assertTrue(points.size() >= 4, "The open mouth: " + points.size());
                for (var lod : TerrainLod.values()) {
                    var other = BoardNaturalBridge.build(scene, next, BoardBridge.deck(scene, next), lod, new HashMap<>());
                    var vertices = other.facets().stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                    for (var point : points) {
                        assertTrue(vertices.stream().anyMatch(p -> p.dst2(point) < .000001f),
                              "Branching arches must not split at the hex boundary: " + tile.coords().getBoardNum() + " " + lod);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "4, 4", "4, 3", "5, 4" })
    void raisedLavaTubeSpansCloseTheirJointWithoutHolesFoldsOrDips(int north, int south) throws Exception {
        var scene = BridgeScenes.raisedLavaTubes(north, south);
        float metre = BoardRelief.metres(1);
        var first = BoardGeometry.center(BridgeScenes.RAISED.get(0), 0);
        var middle = new Vector3(first).lerp(BoardGeometry.center(BridgeScenes.RAISED.get(1), 0), .5f);
        var along = BoardGeometry.center(BridgeScenes.RAISED.get(1), 0).sub(first).nor();
        var across = new Vector3(-along.y, along.x, 0);
        float deck = (north - 2) * BoardGeometry.level();
        for (var lod : TerrainLod.values()) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            var shapes = new ArrayList<BoardBridge.Shape>();
            for (var at : BridgeScenes.RAISED) {
                var tile = scene.tile(at);
                var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, surfaces);
                for (var face : shape.facets()) {
                    if (face.part() == BoardBridge.Part.TOP || face.part() == BoardBridge.Part.RIM) {
                        assertTrue(face.normal().z > 0, "Smoothing a stepped joint must not fold its top: " + lod);
                    }
                }
                shapes.add(shape);
            }
            var ground = new ArrayList<BoardSurface.Face>();
            for (var at : BridgeScenes.RAISED) {
                for (int d = 0; d < 6; d++) {
                    var bank = surfaces.get(at.translated(d));
                    if (bank != null && !BridgeScenes.RAISED.contains(at.translated(d))) { ground.addAll(bank.foundation()); }
                }
            }
            // Both spans rest on banks beside both ends of their shared edge, so the rock closes those corners: no
            // gap between the spans' outlines or their banks, and a level joint keeps one flat walking surface.
            int samples = 0;
            for (float u = -5; u <= 5; u += .25f) {
                for (float v = -12; v <= 12; v += .25f) {
                    var p = new Vector3(middle).mulAdd(along, u * metre).mulAdd(across, v * metre);
                    if (BridgeScenes.RAISED.stream().noneMatch(at -> BoardGeometry.contains(at, p.x, p.y))) { continue; }
                    float height = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NEGATIVE_INFINITY);
                    for (var shape : shapes) { height = Math.max(height, BoardBridge.top(shape, p.x, p.y)); }
                    assertTrue(Float.isFinite(height), "No hole beside the joint: " + north + "/" + south + " " + lod + " " + p);
                    if (north == south) {
                        assertEquals(deck, height, BoardRelief.metres(.05f), "One flat joint: " + lod + " at " + p);
                    }
                    samples++;
                }
            }
            assertTrue(samples > 1000);
        }
    }

    @Test
    void banksBesideAClosedJointShadeTheirWholeSupportedRimAsGround() throws Exception {
        var scene = BridgeScenes.raisedLavaTubes(4, 4);
        var surfaces = new HashMap<Coords, BoardSurface>();
        for (var at : BridgeScenes.RAISED) {
            var tile = scene.tile(at);
            BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, surfaces);
        }
        int rimSamples = 0;
        // 0911 and 1111 each stand beside both spans: their rims run from either mouth to the corner the spans close.
        for (var bankAt : List.of(new Coords(8, 10), new Coords(10, 10))) {
            var bank = surfaces.get(bankAt);
            var corner = BoardGeometry.center(BridgeScenes.RAISED.get(0), 0).add(BoardGeometry.center(BridgeScenes.RAISED.get(1), 0))
                  .add(BoardGeometry.center(bankAt, 0)).scl(1f / 3);
            for (var p : bank.foundation().stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).distinct().toList()) {
                if (Vector3.dst(p.x, p.y, 0, corner.x, corner.y, 0) > BoardRelief.metres(7)
                      || Math.abs(bank.relief.clearance(p.x, p.y)) > BoardRelief.metres(.001f)) { continue; }
                var shade = bank.relief.shade(p);
                if (shade == null) { continue; }
                assertTrue(shade.normal().z > .999f, "The closed corner's bank must not shade like an exposed cliff rim: "
                      + bankAt.getBoardNum() + " at " + p);
                rimSamples++;
            }
        }
        assertTrue(rimSamples > 0, "Exercise the banks' actual rims beside the closed corners");
    }

    @Test
    void groundRoadAndWaterGeometryRemainIndependentOfTheArch() {
        for (boolean water : new boolean[] { false, true }) {
            var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, water, true);
            var tile = scene.tile(BoardScenes.CENTER);
            var without = new BoardScene.Tile(tile.coords(), tile.elevation(), tile.waterDepth(), tile.frozen(),
                  tile.roadExits(), tile.surface(), null, null, List.of(), List.of(),
                  tile.liquid(), null, true, tile.road());
            var groundOnly = BoardScenes.replace(scene, without);
            var before = new BoardSurface(groundOnly, without);
            var after = new BoardSurface(scene, tile);
            sameGeometry(before.faces, after.faces, "The natural bridge must not fill or reshape the ground below");
            sameGeometry(before.waterFaces, after.waterFaces, "The river must remain below the separate bridge shell");
            assertEquals(BoardRoad.of(groundOnly, without).joins(), BoardRoad.of(scene, tile).joins());
            if (!water) {
                var at = BoardGeometry.center(BoardScenes.CENTER, 0);
                assertEquals(UnitLandingSupports.ground(groundOnly, at.x, at.y),
                      UnitLandingSupports.ground(scene, at.x, at.y), .001f, "A unit below must stay on the lower road");
            }
        }
    }

    /**
     * The same faces, finishes and vertices, to float rounding. A road under any bridge takes no bend at its borders
     * ({@code BoardRoad.bent}); a straight course computed that way differs from the plain one only in its last bits.
     */
    private static void sameGeometry(List<BoardSurface.Face> expected, List<BoardSurface.Face> actual, String message) {
        assertEquals(expected.size(), actual.size(), message);
        float tolerance = .001f * BoardGeometry.hexScale();
        for (int i = 0; i < expected.size(); i++) {
            var a = expected.get(i);
            var b = actual.get(i);
            assertEquals(a.finish(), b.finish(), message);
            assertEquals(a.landEdge(), b.landEdge(), message);
            assertTrue(a.a().epsilonEquals(b.a(), tolerance) && a.b().epsilonEquals(b.b(), tolerance)
                  && a.c().epsilonEquals(b.c(), tolerance), message + ": " + a + " became " + b);
        }
    }

    @Test
    void anApproachNeverTurnsANaturalSpanBuiltAndAnUntypedSpanIsACleanBuiltDeck() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.SAND, false, true);
        var tile = scene.tile(BoardScenes.CENTER);
        assertTrue(BoardBridge.deck(scene, tile).natural());
        var entrance = BoardScenes.CENTER.translated(0);
        var road = BoardScenes.tile(entrance, BoardRoad.Kind.GRAVEL, 9, 3, BoardScene.Surface.SAND);
        var edited = BoardScenes.replace(scene, road);
        assertTrue(BoardBridge.deck(edited, tile).natural(), "A stored natural span stays a rock arch beside a road");
        // An untyped span is built (the editor's stroke default); its deck carries only the road it stores.
        var untyped = BoardScenes.replace(edited, BridgeScenes.appearance(tile, Map.of()));
        var deck = BoardBridge.deck(untyped, untyped.tile(BoardScenes.CENTER));
        assertFalse(deck.natural());
        assertEquals(BoardRoad.Kind.NONE, deck.kind(), "An approach paints no deck");
    }

    @Test
    void naturalMouthsMeetTheScallopedBankAcrossThePassageAtEveryLod() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
        var tile = scene.tile(BoardScenes.CENTER);
        var center = BoardGeometry.center(BoardScenes.CENTER, 3);
        for (var lod : TerrainLod.values()) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            var bridge = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, surfaces);
            for (int d : new int[] { 0, 3 }) {
                var bank = surfaces.get(BoardScenes.CENTER.translated(d));
                var direction = BoardGeometry.center(bank.tile.coords(), 3).sub(center);
                var across = new Vector3(-direction.y, direction.x, 0).nor();
                var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList();
                for (int side = -4; side <= 4; side++) {
                    for (int step = 0; step <= 80; step++) {
                        var p = new Vector3(center).mulAdd(direction, .4f + step * .005f)
                              .mulAdd(across, BoardRelief.metres(.7f) * side);
                        float floor = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NEGATIVE_INFINITY);
                        var ray = new Ray(new Vector3(p).add(0, 0, 50), new Vector3(0, 0, -1));
                        float deck = ray.origin.z - (float) Math.sqrt(bridge.hit(ray));
                        assertTrue(Math.max(floor, deck) > center.z - BoardRelief.metres(.6f),
                              "Continuous bank contact, without relying on boulders: " + lod + ", direction " + d + ", " + p);
                    }
                }
            }
        }
    }

    @Test
    void levelBankPassagesHaveNoHeightStepOrCliffNormalAcrossTheirWalkingSurface() {
        int rimSamples = 0;
        for (int direction : new int[] { 0, 1 }) {
            var scene = BridgeScenes.canyonScene(BoardScene.Surface.SAND, true, false, BoardScenes.CENTER, direction);
            var tile = scene.tile(BoardScenes.CENTER);
            var center = BoardGeometry.center(BoardScenes.CENTER, 3);
            for (var lod : TerrainLod.values()) {
                var surfaces = new HashMap<Coords, BoardSurface>();
                var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, surfaces);
                for (int d : new int[] { direction, (direction + 3) % 6 }) {
                    var bank = surfaces.get(BoardScenes.CENTER.translated(d));
                    var along = BoardGeometry.center(bank.tile.coords(), 3).sub(center);
                    var unit = new Vector3(along).nor();
                    var across = new Vector3(-unit.y, unit.x, 0);
                    var ground = bank.foundation();
                    for (int side = -4; side <= 4; side++) {
                        var offset = new Vector3(center).mulAdd(across, BoardRelief.metres(.7f) * side);
                        float outside = 0, inside = 1;
                        for (int i = 0; i < 24; i++) {
                            float middle = (outside + inside) * .5f;
                            var p = new Vector3(offset).mulAdd(along, middle);
                            if (bank.relief.clearance(p.x, p.y) >= 0) { inside = middle; }
                            else { outside = middle; }
                        }
                        var contact = offset.mulAdd(along, inside);
                        // Sample both sides of the actual scalloped outline, not just the overlapping end vertices.
                        for (float distance : new float[] { -.15f, -.05f, .05f, .15f }) {
                            var p = new Vector3(contact).mulAdd(unit, BoardRelief.metres(distance));
                            float floor = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NEGATIVE_INFINITY);
                            float deck = BoardBridge.top(shape, p.x, p.y);
                            assertEquals(center.z, Math.max(floor, deck), BoardRelief.metres(.002f),
                                  "No bank ledge or depressed approach: " + lod + " exit " + d + " at " + p);
                            assertFalse(Float.isFinite(floor) && Float.isFinite(deck),
                                  "Exactly one carrier owns each side of the contact: " + lod + " exit " + d + " at " + p);
                        }
                    }
                    var bridgeTop = new Path2D.Float();
                    for (var face : shape.facets()) {
                        if (face.part() == BoardBridge.Part.TOP || face.part() == BoardBridge.Part.RIM) {
                            footprint(bridgeTop, face.a(), face.b(), face.c());
                        }
                    }
                    var bankTop = new Path2D.Float();
                    for (var face : ground) {
                        if (face.finish() == BoardSurface.Finish.TOP) { footprint(bankTop, face.a(), face.b(), face.c()); }
                    }
                    var overlap = new Area(bridgeTop);
                    overlap.intersect(new Area(bankTop));
                    float area = 0;
                    for (var face : BoardTacticalGeometry.flat(overlap, -1)) {
                        area += Math.abs(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z) * .5f;
                    }
                    assertTrue(area < BoardRelief.metres(.002f) * BoardRelief.metres(.5f),
                          "The bank and arch may share an edge, never overlapping top triangles");
                    for (var p : ground.stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).distinct().toList()) {
                        float lateral = Math.abs(new Vector3(p).sub(center).dot(across));
                        float progress = new Vector3(p).sub(center).dot(unit);
                        if (lateral > BoardRelief.metres(2.8f) || progress > along.len() || progress < 0
                              || Math.abs(bank.relief.clearance(p.x, p.y)) > BoardRelief.metres(.001f)) { continue; }
                        var shade = bank.relief.shade(p);
                        if (shade == null) { continue; }
                        assertTrue(shade.normal().z > .999f, "The joined bank must not shade like an exposed cliff rim");
                        rimSamples++;
                    }
                }
            }
        }
        assertTrue(rimSamples > 0, "Exercise the actual rim's rendered normals");
    }

    private static void footprint(Path2D path, Vector3 a, Vector3 b, Vector3 c) {
        path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c.x, c.y); path.closePath();
    }

    @Test
    void bridgeEntrancesRelocateRimAndFieldRocksWithoutRemovingThem() {
        int moved = 0;
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.ROCK, BoardScene.Surface.SAND)) {
            for (var kind : List.of(BoardRoad.Kind.NONE, BoardRoad.Kind.PAVED)) {
                for (int x : new int[] { 3, 4 }) {
                    for (int d = 0; d < 6; d++) {
                        moved += checkRocks(family, kind, new Coords(x, 4), d);
                    }
                }
            }
        }
        assertTrue(moved > 0, "The fixture must actually exercise obstructing boulders");
    }

    private static int checkRocks(BoardScene.Surface family, BoardRoad.Kind kind, Coords center, int direction) {
        int moved = 0;
        var scene = BridgeScenes.canyonScene(family, false, true, center, direction);
        // One approach makes an ordinary bridge; inspect the unchanged opposite bank in both cases.
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        if (kind != BoardRoad.Kind.NONE) {
            scene = BoardScenes.replace(scene, BoardScenes.tile(center.translated(direction), kind, exits, 3, family));
        }
        var tile = scene.tile(center.translated((direction + 3) % 6));
        int crossing = (1 << ((direction + 1) % 6)) | (1 << ((direction + 4) % 6));
        var without = BoardScenes.replace(scene, BoardScenes.tile(center, BoardRoad.Kind.GRAVEL, crossing, 0, family));
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.MEDIUM)) {
            var before = new BoardSurface(without, tile, lod);
            var after = new BoardSurface(scene, tile, lod);
            var oldRocks = rockFaces(before);
            var rocks = rockFaces(after);
            assertEquals(oldRocks.size(), rocks.size(), "An open bank has room beside the entrance for every rock it moves");
            var approaches = BoardBridge.approaches(scene, tile);
            for (int i = 0; i < rocks.size(); i++) {
                var old = oldRocks.get(i);
                var rock = rocks.get(i);
                assertEquals(old.a().dst2(old.b()), rock.a().dst2(rock.b()), .002f, "Move, do not shrink the boulders");
                if (!old.equals(rock)) { moved++; }
                for (var p : List.of(rock.a(), rock.b(), rock.c())) {
                    assertFalse(approaches.stream().anyMatch(a -> a.obstructs(p, 0, 0)),
                          "The whole rock must clear the entrance: " + family + ", " + kind + ", " + lod
                                + ", " + center + ", exit " + direction + ", " + p);
                }
            }
            assertEquals(before.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList(),
                  after.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList(),
                  "Relocation must not cut or fill the bank");
        }
        return moved;
    }

    private static List<BoardSurface.Face> rockFaces(BoardSurface surface) {
        return surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.OUTCROP
              && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.ROCK).toList();
    }

    @Test
    void everyAuthoredExitHasAnOpenTopAndUnsupportedEndsHaveClosedRockShells() {
        // Bank-anchored caps join the bank's own top; unsupported ends must retain a complete independent shell.
        var ground = BridgeScenes.canyonScene(BoardScene.Surface.ROCK, false, false);
        var tiles = ground.tiles().stream().map(tile -> BoardScenes.tile(tile.coords(), BoardRoad.Kind.NONE,
              0, 0, BoardScene.Surface.ROCK)).toList();
        var scene = new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
        for (int exits = 0; exits < 64; exits++) {
            var tile = BridgeScenes.naturalSpan(scene.tile(BoardScenes.CENTER), exits);
            var edited = BoardScenes.replace(scene, tile);
            var shape = BoardNaturalBridge.build(edited, tile, BoardBridge.deck(edited, tile), TerrainLod.FULL, new HashMap<>());
            assertTrue(shape.facets().size() < 1600, "Bounded geometry even for branching natural spans");
            Map<List<Vector3>, Integer> edges = new HashMap<>();
            for (var face : shape.facets()) {
                if (face.part() == BoardBridge.Part.TOP || face.part() == BoardBridge.Part.RIM) {
                    assertTrue(face.normal().z > 0, "Rounding an outline must not fold its top: exits=" + exits);
                }
                var points = List.of(face.a(), face.b(), face.c());
                for (int i = 0; i < 3; i++) { edges.merge(List.of(points.get(i), points.get((i + 1) % 3)), 1, Integer::sum); }
            }
            edges.forEach((edge, count) -> {
                assertEquals(1, count);
                assertEquals(1, edges.getOrDefault(edge.reversed(), 0), "No cracks or reversed faces in the arch shell");
            });
            for (int d = 0; d < 6; d++) {
                var p = BoardGeometry.center(BoardScenes.CENTER, 3).lerp(BoardGeometry.center(BoardScenes.CENTER.translated(d), 3), .49f);
                float hit = shape.hit(new Ray(p.add(0, 0, 20), new Vector3(0, 0, -1)));
                assertEquals((exits & (1 << d)) != 0, Float.isFinite(hit), "Only authored exits may reach a hex edge");
            }
        }
    }

    /**
     * A lone span with a free end is an overhang, a thick slab of beds rooted in its banks: Kozice 2609, whose exits
     * towards 2608 and 2709 lead out over open water. Joined spans, and spans whose exits all lead into ground or a pit
     * between banks, keep their arch: Kozice 1012 and 1013, and every span of Lava Tubes 1.
     */
    @Test
    void freeEndsMakeALoneSpanAnOverhang() throws Exception {
        var kozice = BoardScenes.installedScene("Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board");
        var overhang = kozice.tile(new Coords(25, 8));
        assertTrue(BoardNaturalBridge.free(kozice, overhang, 0) && BoardNaturalBridge.free(kozice, overhang, 1),
              "2609 runs out over the water towards 2608 and 2709");
        assertTrue(BoardNaturalBridge.overhang(kozice, overhang), "2609 is an overhang");
        for (var hex : List.of(new Coords(9, 11), new Coords(9, 12))) {
            assertFalse(BoardNaturalBridge.overhang(kozice, kozice.tile(hex)), "A joined arch: " + hex.getBoardNum());
        }
        var tubes = BoardScenes.installedScene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        for (var tile : tubes.tiles()) {
            var deck = BoardBridge.deck(tubes, tile);
            if (deck != null && deck.natural()) {
                assertFalse(BoardNaturalBridge.overhang(tubes, tile),
                      "Its exits lead into a pit: " + tile.coords().getBoardNum());
            }
        }
    }

    /**
     * The game collapses a bridge hex at a time and leaves its rubble (Board.collapseBuilding). A span left beside that
     * rubble keeps its arch: neither hex of the Kozice arch becomes an overhang once the other falls, nor does 1012
     * when it also has an exit of its own ({@code extra}) running out over the valley towards 0913.
     */
    @ParameterizedTest
    @CsvSource({ "1012, 1013, -1", "1013, 1012, -1", "1012, 1013, 4" })
    void aSpanLeftBesideACollapseNeverBecomesAnOverhang(String kept, String lost, int extra) throws Exception {
        var board = "Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board";
        var keep = new Coords(Integer.parseInt(kept.substring(0, 2)) - 1, Integer.parseInt(kept.substring(2)) - 1);
        var gone = new Coords(Integer.parseInt(lost.substring(0, 2)) - 1, Integer.parseInt(lost.substring(2)) - 1);
        Consumer<Board> exit = edited -> {
            if (extra >= 0) { edited.getHex(keep).getTerrain(Terrains.BRIDGE).setExit(extra, true); }
        };
        if (extra >= 0) {
            var whole = BoardScenes.installedScene(board, exit);
            assertTrue(BoardNaturalBridge.free(whole, whole.tile(keep), extra),
                  "The added exit runs out over the valley");
        }
        // A game converts its board when it loads it; the collapse comes later.
        var broken = BoardScenes.installedScene(board, edited -> {
            exit.accept(edited);
            BoardSceneryLayouts.convert(edited);
            edited.collapseBuilding(gone);
        });
        assertTrue(BoardBridge.standing(broken.tile(gone)) == null && BoardBridge.feature(broken.tile(gone)).ruined()
              && BoardBridge.standing(broken.tile(keep)) != null, "The game's own collapse takes the one hex, its ruin left");
        assertFalse(BoardNaturalBridge.overhang(broken, broken.tile(keep)), "No overhang: " + kept);
    }

    /**
     * A deck ramping up to a higher bank is one plane rising towards it, its free edges climbing with it: no top facet
     * of Kozice 2609 (ramping to the level-3 mesa 2510) or of the 1012/1013 arch (to its level-5 banks) is steeper than
     * 60 degrees at any detail, where a fan of spokes of very different lengths used to fold into a dark slot.
     */
    @ParameterizedTest
    @CsvSource({ "2609", "1012", "1013" })
    void rampsRiseWithoutFolds(String hex) throws Exception {
        var scene = BoardScenes.installedScene("Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board");
        var tile = scene.tile(new Coords(Integer.parseInt(hex.substring(0, 2)) - 1,
              Integer.parseInt(hex.substring(2)) - 1));
        for (var lod : TerrainLod.values()) {
            var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, new HashMap<>());
            for (var face : shape.facets()) {
                if (face.part() != BoardBridge.Part.TOP && face.part() != BoardBridge.Part.RIM) { continue; }
                assertTrue(face.normal().z >= .5f, "No fold in the top: " + hex + " " + lod + " " + face);
            }
        }
    }

    /**
     * Two joined spans enter a bank one level above their decks that marks a cliff top towards both: each mouth enters
     * the wall at deck height, and so does the corner they share beside it, at every detail, rather than rising to the
     * bank's top between them as a blade.
     */
    @Test
    void aJoinedCornerBesideAMarkedCliffTopStaysAtTheDeck() {
        var south = BoardScenes.CENTER;
        var north = south.translated(0);
        var cliff = south.translated(1);
        // The ground at level 2, the decks one level above it, level banks at both ends and the marked bank at 4.
        var scene = BridgeScenes.typed(BoardScenes.scene(c -> c.equals(south)
              ? BridgeScenes.bridge(c, 2, 1, 1 | 2 | 8)
              : c.equals(north) ? BridgeScenes.bridge(c, 2, 1, 1 | 4 | 8)
              : c.equals(cliff) ? cliffTop(BoardScenes.tile(c, BoardRoad.Kind.NONE, 0, 4, BoardScene.Surface.ROCK),
                    (1 << 4) | (1 << 5))
              : BoardScenes.tile(c, BoardRoad.Kind.NONE, 0,
                    c.equals(south.translated(3)) || c.equals(north.translated(0)) ? 3 : 2, BoardScene.Surface.ROCK)),
              HexAppearance.NATURAL_BRIDGE);
        float deck = 3 * BoardGeometry.level() + BoardRelief.metres(.05f);
        for (var lod : TerrainLod.values()) {
            for (var hex : List.of(south, north)) {
                var tile = scene.tile(hex);
                var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), lod, new HashMap<>());
                for (var face : shape.facets()) {
                    if (face.part() != BoardBridge.Part.TOP && face.part() != BoardBridge.Part.RIM) { continue; }
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(p.z <= deck, "No blade above the deck: " + hex.getBoardNum() + " " + lod + " " + p);
                    }
                }
            }
        }
    }

    /** The tile marking its sides in {@code exits} as cliff tops (CLIFF_TOP), as the board stores them. */
    private static BoardScene.Tile cliffTop(BoardScene.Tile t, int exits) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(), t.detailedGround(),
              t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), exits, t.bare(), t.groundCover(),
              t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), t.appearance());
    }

    /**
     * A natural shell is closed except inside its banks. Every edge of every face meets another face of the span along
     * the same edge, with no T-junction, or across a joint the joined span's face on that edge; else it lies at or below
     * the top of a bank the span rests on, which continues or buries it: a level bank's top where it owns the span's
     * top, or a taller bank around a buried tongue. Kozice 2609 rests on the level bank 2610 beside the higher 2510, so
     * its top climbs over the level bank: cutting that rise away left the open cut 2610 showed. The raised Lava Tubes
     * spans join over banks of unequal height, so a seam carries both spans' levels.
     */
    @Test
    void naturalShellsCloseEverywhereButInTheirBanks() throws Exception {
        var scenes = List.of(BoardScenes.installedScene("Battle of Tukayyid Pack/32x17 Kozice Valley (CDS).board"),
              BoardScenes.installedScene("Map Pack Volcanic/16x17 Lava Tubes 1.board"),
              BridgeScenes.raisedLavaTubes(5, 4), BridgeScenes.raisedLavaTubes(4, 3), BridgeScenes.raisedLavaTubes(4, 4));
        // The tongue check below admits edges beyond one corner of a joint only, never the shared edge itself.
        for (int d = 0; d < 6; d++) {
            var a = BoardGeometry.corner(BoardScenes.CENTER, 0, Math.floorMod(1 - d, 6));
            var b = BoardGeometry.corner(BoardScenes.CENTER, 0, Math.floorMod(2 - d, 6));
            assertEquals(1, BoardGeometry.share(BoardScenes.CENTER, d, a.x, a.y), BoardBankClip.SEAM);
            assertEquals(1, BoardGeometry.share(BoardScenes.CENTER, d, b.x, b.y), BoardBankClip.SEAM);
            assertFalse(beyond(BoardScenes.CENTER, d, a, b), "The shared edge, corner to corner");
            assertTrue(beyond(BoardScenes.CENTER, d, new Vector3(a).lerp(b, -.2f), a) && beyond(BoardScenes.CENTER, d, b, new Vector3(b).lerp(a, -.2f)),
                  "Beyond either corner");
        }
        int joints = 0, banks = 0, tongues = 0;
        for (var scene : scenes) {
            for (var lod : TerrainLod.values()) {
                // As a terrain chunk builds them, from shared prepared surfaces.
                var surfaces = new HashMap<Coords, BoardSurface>();
                Map<Coords, BoardBridge.Shape> shapes = new HashMap<>();
                for (var tile : scene.tiles()) {
                    var deck = BoardBridge.deck(scene, tile);
                    if (deck != null && deck.natural()) {
                        shapes.put(tile.coords(), BoardNaturalBridge.build(scene, tile, deck, lod, surfaces));
                    }
                }
                for (var span : shapes.entrySet()) {
                    var tile = scene.tile(span.getKey());
                    var own = edges(span.getValue());
                    for (var face : span.getValue().facets()) {
                        var points = List.of(face.a(), face.b(), face.c());
                        for (int i = 0; i < 3; i++) {
                            var p = points.get(i);
                            var q = points.get((i + 1) % 3);
                            if (own.contains(List.of(snap(q), snap(p))) || has(span.getValue(), q, p)) { continue; }
                            int joint = joint(scene, tile, p, q);
                            var joined = joint < 0 ? null : shapes.get(tile.coords().translated(joint));
                            if (joined != null && (edges(joined).contains(List.of(snap(q), snap(p))) || has(joined, q, p))) {
                                joints++;
                                continue;
                            }
                            var middle = new Vector3(p).add(q).scl(.5f);
                            if (buried(scene, tile, surfaces, middle, Math.max(p.z, q.z))) {
                                banks++;
                                continue;
                            }
                            // Beyond the hex's corners two joined spans' tongues meet on the joint's line, inside the bank
                            // between them, each with its own rings: the joined span's rock continues the edge there.
                            String where = tile.coords().getBoardNum() + " " + lod + " " + face.part() + " " + p + " " + q;
                            assertTrue(joined != null && beyond(tile.coords(), joint, p, q) && continued(joined, face, p, q),
                                  "The shell may end only in a bank, at or below its top: " + where);
                            tongues++;
                        }
                    }
                }
            }
        }
        assertTrue(joints > 0 && banks > 0 && tongues > 0, "Exercise joints, banks and tongues");
    }

    /**
     * A clipped top keeps every point its edges share with the faces beside them. A bank's corner touches the edge two
     * level faces share, which puts a cut there; a second bank clips the other face elsewhere, so that face keeps the edge
     * whole and straight, the stretch of outline Area drops such a point from.
     */
    @Test
    void clippedTopsKeepThePointsTheirEdgesShare() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.ROCK, false, false);
        float m = BoardRelief.metres(1), height = 3 * BoardGeometry.level();
        var o = BoardGeometry.center(BoardScenes.CENTER, 0);
        o.z = height;
        var a = new Vector3(o).add(-10 * m, 0, 0);
        var b = new Vector3(o).add(10 * m, 0, 0);
        var facets = new ArrayList<BoardBridge.Facet>();
        BoardBridge.triangle(facets, a, b, new Vector3(o).add(0, 8 * m, 0), BoardBridge.Part.TOP);
        BoardBridge.triangle(facets, b, a, new Vector3(o).add(0, -8 * m, 0), BoardBridge.Part.TOP);
        var banks = new Path2D.Float();
        var touch = new Vector3(o).add(3 * m, 0, 0);
        banks.moveTo(touch.x, touch.y);
        banks.lineTo(o.x + 5 * m, o.y - 5 * m);
        banks.lineTo(o.x + m, o.y - 5 * m);
        banks.closePath();
        banks.append(new java.awt.geom.Rectangle2D.Float(o.x - 2 * m, o.y + 6 * m, 4 * m, 4 * m), false);
        var clipped = BoardBankClip.outsideBanks(scene, scene.tile(BoardScenes.CENTER), facets, new Area(banks), height);
        var edges = new HashSet<List<Vector3>>();
        boolean[] sides = new boolean[2];
        for (var face : clipped) {
            var points = List.of(face.a(), face.b(), face.c());
            for (int i = 0; i < 3; i++) { edges.add(List.of(snap(points.get(i)), snap(points.get((i + 1) % 3)))); }
            if (points.stream().anyMatch(p -> p.dst(touch) < .001f * m)) {
                sides[(face.a().y + face.b().y + face.c().y) / 3 > o.y ? 0 : 1] = true;
            }
        }
        assertTrue(sides[0] && sides[1], "Both faces keep the cut where the bank touches their edge");
        for (var edge : edges) {
            if (Math.abs(edge.get(0).y - o.y) < .001f * m && Math.abs(edge.get(1).y - o.y) < .001f * m) {
                assertTrue(edges.contains(List.of(edge.get(1), edge.get(0))), "No T-junction along the shared edge: " + edge);
            }
        }
    }

    /**
     * A point near two edges of a thin face divides only the edge it lies on, and the bank around it stays out of the
     * face across the other. A bank's corner lies on the face's long edge 0.09 world units from its end, under a
     * millimetre from its other long edge; one bank reaches from it down and away, another up into the neighbour across
     * that other edge, and a third clips the far end. Every piece left faces up, none is there twice, and none of the
     * neighbour's lies under a bank.
     */
    @Test
    void thinClippedTopsTakeEachSharedPointOnce() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.ROCK, false, false);
        float unit = BoardGeometry.hexScale(), height = 3 * BoardGeometry.level();
        var o = BoardGeometry.center(BoardScenes.CENTER, 0);
        o.z = height;
        var a = new Vector3(o);
        var c = new Vector3(o).add(10 * unit, .1f * unit, 0);
        var facets = new ArrayList<BoardBridge.Facet>();
        BoardBridge.triangle(facets, a, new Vector3(o).add(10 * unit, 0, 0), c, BoardBridge.Part.TOP);
        BoardBridge.triangle(facets, a, c, new Vector3(o).add(5 * unit, 3 * unit, 0), BoardBridge.Part.TOP);
        assertTrue(facets.size() == 2 && facets.stream().allMatch(face -> face.normal().z > 0), "The faces themselves face up");
        var banks = new Path2D.Float();
        float px = o.x + .09f * unit, py = o.y;
        banks.moveTo(px, py);
        banks.lineTo(o.x + .5f * unit, o.y - unit);
        banks.lineTo(o.x - .3f * unit, o.y - unit);
        banks.closePath();
        banks.moveTo(px, py);
        banks.lineTo(o.x + .4f * unit, o.y + 2 * unit);
        banks.lineTo(o.x + .2f * unit, o.y + 2 * unit);
        banks.closePath();
        banks.append(new java.awt.geom.Rectangle2D.Float(o.x + 9.5f * unit, o.y - unit, 2 * unit, 2 * unit), false);
        var area = new Area(banks);
        var clipped = BoardBankClip.outsideBanks(scene, scene.tile(BoardScenes.CENTER), facets, area, height);
        assertFalse(clipped.isEmpty(), "The faces keep their near ends");
        var faces = new HashSet<java.util.Set<Vector3>>();
        float covered = 0;
        for (var face : clipped) {
            assertTrue(face.normal().z > 0, "Every piece faces up: " + face);
            assertTrue(faces.add(new HashSet<>(List.of(face.a(), face.b(), face.c()))), "No piece twice: " + face);
            var under = new Area(footprint(face.a(), face.b(), face.c()));
            under.intersect(area);
            covered += surface(under);
        }
        // To the third of a millimetre a corner may move along the bank's outline.
        assertTrue(covered < .001f * unit * 20 * unit, "No piece lies under a bank: " + covered);
    }

    /**
     * Clipping holds for any two level faces sharing an edge and any banks over them, seeded and biased to the hard
     * cases: thin and near-degenerate faces, bank corners at or near their edges and corners, and wedges reaching from
     * one face into the other. Every piece faces up, lies in its face and appears once; a face's pieces and the part of
     * it under the banks add up to the face, so no piece overlaps another or a bank; and along the shared edge each
     * side's pieces end only where the other side's do, or where a bank continues the side that ends. A point one side
     * has there and the other lacks may lie a third of a millimetre off the other's edge, no further.
     */
    @Test
    void clippedLevelTopsHoldForRandomFacesAndBanks() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.ROCK, false, false);
        var tile = scene.tile(BoardScenes.CENTER);
        float unit = BoardGeometry.hexScale(), height = 3 * BoardGeometry.level(), same = .001f * unit;
        var o = BoardGeometry.center(BoardScenes.CENTER, 0);
        o.z = height;
        var random = new java.util.Random(20261030);
        int cases = 0;
        for (int run = 0; run < 5000; run++) {
            float length = unit * (float) Math.pow(40, random.nextFloat()) / 2;
            float turn = random.nextBoolean() ? 0 : random.nextFloat() * 360;
            var a = new Vector3(o);
            var b = new Vector3(o).add(length, 0, 0);
            var c = apex(random, length, unit, 1);
            var d = apex(random, length, unit, -1);
            var points = new Vector3[] { a, b, c.add(o), d.add(o) };
            for (var point : points) { point.sub(o).rotate(Vector3.Z, turn).add(o); }
            // Each face starts at a random corner, so the shared edge comes first, second or last in it.
            var facets = new ArrayList<BoardBridge.Facet>();
            for (var corners : List.of(new Vector3[] { a, b, c }, new Vector3[] { b, a, d })) {
                int first = random.nextInt(3);
                BoardBridge.triangle(facets, corners[first], corners[(first + 1) % 3], corners[(first + 2) % 3],
                      BoardBridge.Part.TOP);
            }
            if (facets.size() != 2 || facets.stream().anyMatch(face -> face.normal().z <= 0)) { continue; }
            var shape = new Path2D.Float();
            float outline = 0;
            for (int bank = 1 + random.nextInt(3); bank > 0; bank--) {
                var near = near(random, points, same);
                float spread = unit * (.05f + 3 * random.nextFloat()), heading = random.nextFloat() * 360;
                float open = 5 + random.nextFloat() * 120;
                var first = new Vector3(spread, 0, 0).rotate(Vector3.Z, heading - open / 2).add(near);
                var second = new Vector3(spread, 0, 0).rotate(Vector3.Z, heading + open / 2).add(near);
                shape.moveTo(near.x, near.y);
                shape.lineTo(first.x, first.y);
                shape.lineTo(second.x, second.y);
                shape.closePath();
                outline += near.dst(first) + first.dst(second) + second.dst(near);
            }
            var banks = new Area(shape);
            String where = "run " + run + " length " + length + " turn " + turn + " c " + c + " d " + d;
            var clipped = BoardBankClip.outsideBanks(scene, tile, facets, banks, height);
            cases++;
            var pieces = List.of(new ArrayList<BoardBridge.Facet>(), new ArrayList<BoardBridge.Facet>());
            var seen = new HashSet<java.util.Set<Vector3>>();
            for (var piece : clipped) {
                assertTrue(piece.normal().z > 0, "Every piece faces up: " + where + " " + piece);
                assertTrue(seen.add(new HashSet<>(List.of(piece.a(), piece.b(), piece.c()))),
                      "No piece twice: " + where + " " + piece);
                // Its middle tells which face it belongs to, however thin; all of it lies in that face.
                var middle = new Vector3(piece.a()).add(piece.b()).add(piece.c()).scl(1 / 3f);
                int owner = within(facets.get(0), middle, 0) ? 0 : within(facets.get(1), middle, 0) ? 1 : -1;
                assertTrue(owner >= 0 && List.of(piece.a(), piece.b(), piece.c()).stream()
                      .allMatch(p -> within(facets.get(owner), p, same)),
                      "Every piece lies in its face: " + where + " " + piece);
                pieces.get(owner).add(piece);
            }
            for (int f = 0; f < 2; f++) {
                var face = facets.get(f);
                float kept = 0;
                for (var piece : pieces.get(f)) { kept += triangleArea(piece.a(), piece.b(), piece.c()); }
                var under = new Area(footprint(face.a(), face.b(), face.c()));
                under.intersect(banks);
                float whole = triangleArea(face.a(), face.b(), face.c());
                float perimeter = face.a().dst(face.b()) + face.b().dst(face.c()) + face.c().dst(face.a());
                // A corner may move a third of a millimetre along the outline, and a needle a rounding step wide drops.
                float slack = 2 * same * (perimeter + outline) + 2e-4f * perimeter * perimeter / 9;
                assertEquals(whole, kept + surface(under), slack, "The pieces and the banks make up face " + f + ": " + where);
            }
            assertTrue(closed(facets, pieces, a, b, banks, same), "The shared edge closes: " + where);
        }
        assertTrue(cases > 3000, "Exercise the clip: " + cases);
    }

    /** A random third corner of a face on {@code side} of the edge from the origin along x of {@code length}. */
    private static Vector3 apex(java.util.Random random, float length, float unit, int side) {
        float roll = random.nextFloat();
        if (roll < .4f) {
            // A thin face: its corner a hair to a few centimetres off the edge's line.
            float off = unit * (float) Math.pow(10, -3.3 + 2.5 * random.nextFloat());
            return new Vector3(length * (-.2f + 1.4f * random.nextFloat()), side * off, 0);
        }
        if (roll < .55f) {
            // A face whose other edge runs on from one of the edge's ends nearly in line with it.
            float off = unit * (float) Math.pow(10, -3.7 + 1.5 * random.nextFloat());
            float x = random.nextBoolean() ? length * (1.05f + random.nextFloat()) : -length * (.05f + random.nextFloat());
            return new Vector3(x, side * off, 0);
        }
        if (roll < .65f) {
            // A near-degenerate face: its corner beside one of the edge's ends.
            float x = random.nextBoolean() ? 0 : length;
            float off = unit * .004f * (.1f + random.nextFloat());
            return new Vector3(x + unit * .004f * (random.nextFloat() - .5f), side * off, 0);
        }
        return new Vector3(length * (-.3f + 1.6f * random.nextFloat()), side * length * (.1f + random.nextFloat()), 0);
    }

    /** A bank's corner at or near one of the faces' edges or corners, from on it to a few centimetres off. */
    private static Vector3 near(java.util.Random random, Vector3[] points, float same) {
        var corner = random.nextInt(3) == 0;
        int i = random.nextInt(4), j = (i + 1 + random.nextInt(3)) % 4;
        var at = corner ? new Vector3(points[i]) : new Vector3(points[i]).lerp(points[j], random.nextFloat());
        float roll = random.nextFloat();
        float off = roll < .3f ? 0 : roll < .8f ? same * 2 * random.nextFloat() : same * 60 * random.nextFloat();
        return at.add(new Vector3(off, 0, 0).rotate(Vector3.Z, random.nextFloat() * 360)).scl(1, 1, 0);
    }

    /** The point lies in the face's footprint, or within {@code reach} of it. */
    private static boolean within(BoardBridge.Facet face, Vector3 point, float reach) {
        var corners = List.of(face.a(), face.b(), face.c());
        for (int i = 0; i < 3; i++) {
            var p = corners.get(i);
            var q = corners.get((i + 1) % 3);
            float length = (float) Math.hypot(q.x - p.x, q.y - p.y);
            if (((q.x - p.x) * (point.y - p.y) - (q.y - p.y) * (point.x - p.x)) / length < -reach) { return false; }
        }
        return true;
    }

    /**
     * Along the edge from a to b, each face's pieces end only where the other face's do, to a third of a millimetre at
     * either end of a stretch. Where one face keeps a stretch the other lacks, the gap behind it on the other side, up to
     * where a bank or the other face's pieces continue, is one BoardBridge.stable rejects: a piece filling it would be a
     * needle of the face or a speck. Where both keep the edge itself, each point one side has on it the other has too, to
     * a third of a millimetre: no T-junction. A thin face's own corner a hair beside the edge is not on it.
     */
    private static boolean closed(List<BoardBridge.Facet> faces, List<? extends List<BoardBridge.Facet>> pieces, Vector3 a,
          Vector3 b, Area banks, float same) {
        float length = a.dst(b);
        var along = new Vector3(b).sub(a).scl(1 / length);
        List<List<float[]>> stretches = List.of(new ArrayList<>(), new ArrayList<>());
        List<List<float[]>> exact = List.of(new ArrayList<>(), new ArrayList<>());
        List<List<Vector3>> points = List.of(new ArrayList<>(), new ArrayList<>());
        for (int f = 0; f < 2; f++) {
            // Only each side's outline: a thin side's inner edges may run within a third of a millimetre of the edge.
            var inner = new HashSet<List<Vector3>>();
            for (var piece : pieces.get(f)) {
                inner.addAll(List.of(List.of(piece.a(), piece.b()), List.of(piece.b(), piece.c()),
                      List.of(piece.c(), piece.a())));
            }
            for (var piece : pieces.get(f)) {
                var corners = List.of(piece.a(), piece.b(), piece.c());
                for (int i = 0; i < 3; i++) {
                    var p = corners.get(i);
                    var q = corners.get((i + 1) % 3);
                    if (inner.contains(List.of(q, p))) { continue; }
                    float pOff = Math.abs(along.x * (p.y - a.y) - along.y * (p.x - a.x));
                    float qOff = Math.abs(along.x * (q.y - a.y) - along.y * (q.x - a.x));
                    if (pOff > same || qOff > same) { continue; }
                    float s = along.dot(p.x - a.x, p.y - a.y, 0), t = along.dot(q.x - a.x, q.y - a.y, 0);
                    stretches.get(f).add(new float[] { Math.min(s, t), Math.max(s, t) });
                    var face = faces.get(f);
                    if ((p.equals(a) || p.equals(b) || on(face, a, b, p, same))
                          && (q.equals(a) || q.equals(b) || on(face, a, b, q, same))) {
                        exact.get(f).add(new float[] { Math.min(s, t), Math.max(s, t) });
                    }
                    for (var point : List.of(p, q)) {
                        if (on(face, a, b, point, same)) { points.get(f).add(point); }
                    }
                }
            }
        }
        for (int f = 0; f < 2; f++) {
            for (var p : points.get(f)) {
                float s = along.dot(p.x - a.x, p.y - a.y, 0);
                boolean kept = s > same && s < length - same
                      && exact.get(1 - f).stream().anyMatch(r -> r[0] + same < s && s < r[1] - same);
                if (kept && points.get(1 - f).stream().noneMatch(q -> Math.hypot(p.x - q.x, p.y - q.y) <= same)) {
                    return false;
                }
            }
        }
        for (int f = 0; f < 2; f++) {
            var face = faces.get(1 - f);
            float longest = Math.max(face.a().dst(face.b()), Math.max(face.b().dst(face.c()), face.c().dst(face.a())));
            // The other face lies to the right of a to b for the first face, to the left for the second.
            var across = new Vector3(along.y, -along.x, 0).scl(f == 0 ? 1 : -1);
            for (var stretch : stretches.get(f)) {
                // The stretch's parts the other side leaves open, each checked at its middle.
                var open = new ArrayList<float[]>();
                open.add(new float[] { Math.max(stretch[0], 2 * same), Math.min(stretch[1], length - 2 * same) });
                for (var cover : stretches.get(1 - f)) {
                    var next = new ArrayList<float[]>();
                    for (var gap : open) {
                        if (cover[1] + 2 * same <= gap[0] || cover[0] - 2 * same >= gap[1]) { next.add(gap); continue; }
                        if (gap[0] < cover[0] - 2 * same) { next.add(new float[] { gap[0], cover[0] - 2 * same }); }
                        if (cover[1] + 2 * same < gap[1]) { next.add(new float[] { cover[1] + 2 * same, gap[1] }); }
                    }
                    open = next;
                }
                for (var gap : open) {
                    var on = new Vector3(a).mulAdd(along, (gap[0] + gap[1]) / 2);
                    float gapDepth = depth(face, on, across);
                    for (float reach = 0; reach < gapDepth; reach += same) {
                        var behind = new Vector3(on).mulAdd(across, reach);
                        if (banks.contains(behind.x, behind.y)
                              || pieces.get(1 - f).stream().anyMatch(piece -> within(piece, behind, 0))) {
                            gapDepth = reach;
                        }
                    }
                    // The pieces that would fill the gap are ones BoardBridge.stable rejects, to twice its bounds: a
                    // speck, its cross product (the gap's length times its depth) under the absolute floor, or a needle
                    // of the face, no deeper than a rounding step of the face's longest edge.
                    float span = gap[1] - gap[0];
                    if (span * gapDepth > 2e-4f && gapDepth > 2e-4f * longest) { return false; }
                }
            }
        }
        return true;
    }

    /**
     * The point lies on the edge from a to b itself, as a cut or a clip puts a point there: not a thin face's corner
     * beside it, nor a point of the face's other edge where that edge runs on within a hair of this one.
     */
    private static boolean on(BoardBridge.Facet face, Vector3 a, Vector3 b, Vector3 point, float same) {
        float length = a.dst(b);
        float off = Math.abs((b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)) / length;
        return off < same / 10 && !onOtherEdge(face, a, b, point, same / 10);
    }

    /** The point lies within {@code reach} of one of the face's edges other than the one from a to b, in the plane. */
    private static boolean onOtherEdge(BoardBridge.Facet face, Vector3 a, Vector3 b, Vector3 point, float reach) {
        var corners = List.of(face.a(), face.b(), face.c());
        for (int i = 0; i < 3; i++) {
            var p = corners.get(i);
            var q = corners.get((i + 1) % 3);
            if ((p == a || p == b) && (q == a || q == b)) { continue; }
            float length = p.dst(q), t = ((point.x - p.x) * (q.x - p.x) + (point.y - p.y) * (q.y - p.y)) / (length * length);
            float off = Math.abs((q.x - p.x) * (point.y - p.y) - (q.y - p.y) * (point.x - p.x)) / length;
            if (t >= 0 && t <= 1 && off < reach) { return true; }
        }
        return false;
    }

    /** How far the face reaches from a point on its edge in the direction {@code across}, in the plane. */
    private static float depth(BoardBridge.Facet face, Vector3 on, Vector3 across) {
        var corners = List.of(face.a(), face.b(), face.c());
        float depth = 0;
        for (int i = 0; i < 3; i++) {
            var p = corners.get(i);
            var q = corners.get((i + 1) % 3);
            float sx = q.x - p.x, sy = q.y - p.y, denominator = across.x * sy - across.y * sx;
            if (denominator == 0) { continue; }
            float t = ((p.x - on.x) * sy - (p.y - on.y) * sx) / denominator;
            float u = ((p.x - on.x) * across.y - (p.y - on.y) * across.x) / denominator;
            if (u >= 0 && u <= 1) { depth = Math.max(depth, t); }
        }
        return depth;
    }

    /** The area of a triangle's footprint. */
    private static float triangleArea(Vector3 a, Vector3 b, Vector3 c) {
        return Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2;
    }

    /** The area an Area covers, its holes taken out; each outline is summed from its own start, in double precision. */
    private static float surface(Area area) {
        double total = 0, startX = 0, startY = 0, x = 0, y = 0;
        double[] point = new double[6];
        for (var path = area.getPathIterator(null); !path.isDone(); path.next()) {
            int type = path.currentSegment(point);
            if (type == java.awt.geom.PathIterator.SEG_MOVETO) {
                startX = point[0];
                startY = point[1];
                x = y = 0;
                continue;
            }
            double toX = type == java.awt.geom.PathIterator.SEG_CLOSE ? 0 : point[0] - startX;
            double toY = type == java.awt.geom.PathIterator.SEG_CLOSE ? 0 : point[1] - startY;
            total += x * toY - toX * y;
            x = toX;
            y = toY;
        }
        return (float) Math.abs(total) / 2;
    }

    private static Path2D footprint(Vector3 a, Vector3 b, Vector3 c) {
        var path = new Path2D.Float();
        path.moveTo(a.x, a.y);
        path.lineTo(b.x, b.y);
        path.lineTo(c.x, c.y);
        path.closePath();
        return path;
    }

    /**
     * A piece with several bank islands joins each to its outline as earcut joins holes, the rightmost first and each
     * later one to the islands already joined too: no island is filled with rock. From the island Area lists first, the
     * second blocks both lower corners of the face and the third its upper one. Far out on a board too, where a hair
     * is below the float steps of its coordinates.
     */
    @ParameterizedTest
    @CsvSource({ "0, 0", "3000, 3000" })
    void clippedTopsBridgeEveryBankIsland(float x, float y) {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.ROCK, false, false);
        float unit = BoardGeometry.hexScale(), height = 3 * BoardGeometry.level();
        var o = x == 0 ? BoardGeometry.center(BoardScenes.CENTER, 0) : new Vector3(x * unit, y * unit, 0);
        o.z = height;
        var facets = new ArrayList<BoardBridge.Facet>();
        BoardBridge.triangle(facets, new Vector3(o).add(-10 * unit, -10 * unit, 0), new Vector3(o).add(10 * unit, -10 * unit, 0),
              new Vector3(o).add(0, 10 * unit, 0), BoardBridge.Part.TOP);
        var banks = new Path2D.Float();
        for (float[] island : new float[][] { { -1, -1, 1, 0, -1, .8f }, { -6, -5, 6, -5, 4, 1 }, { -1, 3, 1, 3, 0, 5 } }) {
            banks.moveTo(o.x + island[0] * unit, o.y + island[1] * unit);
            banks.lineTo(o.x + island[2] * unit, o.y + island[3] * unit);
            banks.lineTo(o.x + island[4] * unit, o.y + island[5] * unit);
            banks.closePath();
        }
        var area = new Area(banks);
        var clipped = BoardBankClip.outsideBanks(scene, scene.tile(BoardScenes.CENTER), facets, area, height);
        float kept = 0, covered = 0;
        for (var face : clipped) {
            assertTrue(face.normal().z > 0, "Every piece faces up: " + face);
            kept += triangleArea(face.a(), face.b(), face.c());
            var under = new Area(footprint(face.a(), face.b(), face.c()));
            under.intersect(area);
            covered += surface(under);
        }
        assertEquals(0, covered, .01f * unit * unit, "No rock over an island");
        assertEquals(200 * unit * unit - surface(area), kept, .01f * unit * unit, "The rest of the face stays");
    }

    /** Every face's edges, their ends to a third of a millimetre: a clipped piece takes its height from its face's plane. */
    private static java.util.Set<List<Vector3>> edges(BoardBridge.Shape shape) {
        var result = new HashSet<List<Vector3>>();
        for (var face : shape.facets()) {
            var a = snap(face.a());
            var b = snap(face.b());
            var c = snap(face.c());
            result.addAll(List.of(List.of(a, b), List.of(b, c), List.of(c, a)));
        }
        return result;
    }

    private static Vector3 snap(Vector3 p) {
        float grid = .001f * BoardGeometry.hexScale();
        return new Vector3(Math.round(p.x / grid) * grid, Math.round(p.y / grid) * grid, Math.round(p.z / grid) * grid);
    }

    /** A face of the shape has the edge from p to q, its ends to a millimetre: two spans round a seam's heights apart. */
    private static boolean has(BoardBridge.Shape shape, Vector3 p, Vector3 q) {
        float near = BoardRelief.metres(.001f);
        for (var face : shape.facets()) {
            var points = List.of(face.a(), face.b(), face.c());
            for (int i = 0; i < 3; i++) {
                if (points.get(i).dst(p) < near && points.get((i + 1) % 3).dst(q) < near) { return true; }
            }
        }
        return false;
    }

    /** The direction of a joined span whose shared edge, or that edge's line on into the ground, holds both points. */
    private static int joint(BoardScene scene, BoardScene.Tile tile, Vector3 p, Vector3 q) {
        for (int d = 0; d < 6; d++) {
            if (BoardBridge.connected(tile, scene.tile(tile.coords().translated(d)), d)
                  && Math.abs(BoardGeometry.share(tile.coords(), d, p.x, p.y) - 1) < BoardBankClip.SEAM
                  && Math.abs(BoardGeometry.share(tile.coords(), d, q.x, q.y) - 1) < BoardBankClip.SEAM) { return d; }
        }
        return -1;
    }

    /** Both points on the joint's line lie at or beyond the same corner of the edge the two spans share. */
    private static boolean beyond(Coords at, int joint, Vector3 p, Vector3 q) {
        for (int side : new int[] { 1, 5 }) {
            int d = (joint + side) % 6;
            if (BoardGeometry.share(at, d, p.x, p.y) > 1 - BoardBankClip.SEAM
                  && BoardGeometry.share(at, d, q.x, q.y) > 1 - BoardBankClip.SEAM) { return true; }
        }
        return false;
    }

    /** The joined span's rock lies a centimetre across the face's edge, within a centimetre of the edge's height. */
    private static boolean continued(BoardBridge.Shape joined, BoardBridge.Facet face, Vector3 p, Vector3 q) {
        var middle = new Vector3(p).add(q).scl(.5f);
        var across = new Vector3(-(q.y - p.y), q.x - p.x, 0).nor();
        var centre = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
        if (across.dot(centre.x - middle.x, centre.y - middle.y, 0) > 0) { across.scl(-1); }
        float metre = BoardRelief.metres(1), top = middle.z + metre;
        var probe = new Vector3(middle).mulAdd(across, .01f * metre);
        float distance = (float) Math.sqrt(joined.hit(new Ray(probe.set(probe.x, probe.y, top), new Vector3(0, 0, -1))));
        return Math.abs(top - distance - middle.z) <= .01f * metre;
    }

    /** The top of a bank the span rests on lies at or above {@code height} at the point, which may lie on its outline. */
    private static boolean buried(BoardScene scene, BoardScene.Tile tile, Map<Coords, BoardSurface> surfaces, Vector3 point,
          float height) {
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (!BoardBridge.abutment(tile, next, d)) { continue; }
            var top = surfaces.get(next.coords()).foundation().stream()
                  .filter(face -> face.finish() == BoardSurface.Finish.TOP).toList();
            // A cut follows the top's outline, to a centimetre of rounding.
            float reach = BoardRelief.metres(.01f);
            for (int k = 0; k <= 8; k++) {
                float x = point.x + (k == 8 ? 0 : reach * MathUtils.cosDeg(45 * k));
                float y = point.y + (k == 8 ? 0 : reach * MathUtils.sinDeg(45 * k));
                if (BoardSurface.sampleHeight(top, x, y, Float.NaN) >= height - BoardRelief.metres(.002f)) { return true; }
            }
        }
        return false;
    }
}
