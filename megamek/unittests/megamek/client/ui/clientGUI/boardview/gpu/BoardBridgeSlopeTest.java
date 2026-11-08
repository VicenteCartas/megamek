/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardBridgeSlopeTest {

    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void connectionsUseAbsoluteDeckLevelsAndRequireReciprocalExits() {
        var at = new Coords(4, 4);
        for (int d = 0; d < 6; d++) {
            int reverse = (d + 3) % 6;
            var a = BridgeScenes.bridge(at, -2, 3, 1 << d);
            for (int step = -2; step <= 2; step++) {
                var b = BridgeScenes.bridge(at.translated(d), -1, 2 + step, 1 << reverse);
                assertEquals(Math.abs(step) <= 1, BoardBridge.connected(a, b, d));
                assertEquals(BoardBridge.connected(a, b, d), BoardBridge.connected(b, a, reverse));
                if (Math.abs(step) <= 1) {
                    assertEquals(1 + step / 2f, BoardBridge.edgeElevation(a, b, d));
                    assertEquals(BoardBridge.edgeElevation(a, b, d), BoardBridge.edgeElevation(b, a, reverse));
                }
            }
            assertFalse(BoardBridge.connected(a, BridgeScenes.bridge(at.translated(d), 0, 2, 1 << d), d));
            assertFalse(BoardBridge.connected(a, null, d));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 3, 5, 7, 9, 21, 27, 63 })
    void manufacturedTurnsAndJunctionsJoinTheirDecksRailsAndSoffits(int exits) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var scene = BridgeScenes.routeScene(at, exits, true);
            var tile = scene.tile(at);
            var deck = BoardBridge.deck(scene, tile);
            assertEquals(BoardRoad.Kind.PAVED, deck.kind(), "The road material traverses the stepped span");
            var shape = manufactured(scene, tile);
            var grade = BoardBridgeSlope.profile(tile, deck);
            var road = deck.road(at);
            var footprint = road.footprint(0);
            var slab = road.footprint(BoardBridge.SHOULDER);
            var center = BoardGeometry.center(at, 2);
            float lift = GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) == 0) { continue; }
                var next = scene.tile(at.translated(d));
                var other = manufactured(scene, next);
                var gate = BoardGeometry.center(next.coords(), 2).sub(center).scl(.5f);
                int edge = Math.floorMod(1 - d, 6);
                var across = BoardGeometry.corner(at, 0, edge + 1).sub(BoardGeometry.corner(at, 0, edge)).nor();
                float joint = (2 + BridgeScenes.step(d) / 2f) * BoardGeometry.level() + lift;
                for (float offset : new float[] { -16.5f, -12, 0, 12, 16.5f }) {
                    var p = new Vector3(center).add(gate).mulAdd(across, offset * BoardGeometry.hexScale());
                    var inside = new Vector3(p).mulAdd(gate, -.0001f);
                    var outside = new Vector3(p).mulAdd(gate, .0001f);
                    boolean rail = Math.abs(offset) > BoardRoad.Kind.PAVED.halfWidth;
                    float top = joint + (rail ? 2.5f * BoardGeometry.hexScale() : 0);
                    assertEquals(top, BridgeScenes.height(shape, inside, false), .005f, "First mouth, exit " + d);
                    assertEquals(top, BridgeScenes.height(other, outside, false), .005f, "Opposite mouth, exit " + d);
                    assertEquals(joint - 1.5f * BoardGeometry.hexScale(), BridgeScenes.height(shape, inside, true), .005f);
                    assertEquals(BridgeScenes.height(shape, inside, true), BridgeScenes.height(other, outside, true), .01f);
                }
                // Check the incline wherever the curved slab crosses these stations, including its rails.
                // The broad bend needs a small sampling inset from the kerb's 0.1-unit authoring approximation.
                float reach = exits == 5 ? 16 : 16.5f;
                for (float t : new float[] { .55f, .7f, .9f }) {
                    for (float offset : new float[] { -reach, 0, reach }) {
                        var p = new Vector3(center).mulAdd(gate, t).mulAdd(across, offset * BoardGeometry.hexScale());
                        float px = (p.x - center.x) / BoardGeometry.hexScale(), py = (p.y - center.y) / BoardGeometry.hexScale();
                        float actual = BridgeScenes.height(shape, p, false);
                        if (!slab.contains(px, py)) {
                            assertEquals(Float.NEGATIVE_INFINITY, actual, "No bridge outside the curved slab at " + p);
                            continue;
                        }
                        boolean lane = footprint.contains(px, py);
                        // Widened adjacent arms overlap in plan and cannot both impose their independent planes.
                        // The centre line retains the authored approach grade; off-axis junction points use the
                        // shared continuous profile (whose partition and seams are checked by WideProfileTest).
                        float rise = offset == 0 ? BridgeScenes.step(d) * BoardGeometry.level() * (t - .5f)
                              : BoardSurface.sampleHeight(grade, p.x, p.y, Float.NaN);
                        assertTrue(Float.isFinite(rise), "The entire widened crossing has a grade");
                        float expected = center.z + lift + rise
                              + (lane ? 0 : 2.5f * BoardGeometry.hexScale());
                        assertEquals(expected, actual, .01f, "Continuous grade at exit " + d);
                    }
                }
            }
            var carriageway = road.footprint(-.1f);
            for (var patch : GpuRoads.deckPatches(tile, deck, road)) {
                var paint = GpuRoads.deck(patch, shape, GpuRoads.SURFACE_LIFT);
                float offset = (patch.lift() + .01f - GpuRoads.SURFACE_LIFT) * BoardGeometry.hexScale();
                for (var face : paint) {
                    var p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    if (!carriageway.contains((p.x - center.x) / BoardGeometry.hexScale(),
                          (p.y - center.y) / BoardGeometry.hexScale()) || !BoardGeometry.contains(at, p.x, p.y)) { continue; }
                    assertEquals(BridgeScenes.height(shape, p, false) + offset, p.z, .005f,
                          "Road material and markings must follow the physical deck at " + p + " on " + face);
                }
            }
        }
    }

    @Test
    void naturalStepsShareTheirEntireOpenMouthAtMixedLods() {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var scene = BridgeScenes.routeScene(at, 63, false);
            var tile = scene.tile(at);
            var shape = BoardNaturalBridge.build(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, new HashMap<>());
            var center = BoardGeometry.center(at, 2);
            assertEquals(center.z, BridgeScenes.height(shape, center, false), .001f);
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(at.translated(d));
                var gate = BoardGeometry.center(next.coords(), 2).sub(center).scl(.5f);
                var seam = new Vector3(center).add(gate);
                var along = new Vector3(gate).nor();
                var points = shape.facets().stream().filter(f -> f.part() != BoardBridge.Part.SIDE)
                      .flatMap(f -> List.of(f.a(), f.b(), f.c()).stream())
                      .filter(p -> Math.abs(new Vector3(p).sub(seam).dot(along)) < .001f).distinct().toList();
                assertEquals(4, points.size());
                assertEquals((2 + BridgeScenes.step(d) / 2f) * BoardGeometry.level(),
                      points.stream().mapToDouble(p -> p.z).max().orElseThrow(), .001f);
                for (var lod : TerrainLod.values()) {
                    var other = BoardNaturalBridge.build(scene, next, BoardBridge.deck(scene, next), lod, new HashMap<>());
                    var vertices = other.facets().stream().flatMap(f -> List.of(f.a(), f.b(), f.c()).stream()).toList();
                    for (var p : points) {
                        assertTrue(vertices.stream().anyMatch(q -> q.epsilonEquals(p, .001f)), "Shared top and underside: " + lod);
                    }
                    assertFalse(other.facets().stream().anyMatch(f -> List.of(f.a(), f.b(), f.c()).stream()
                          .allMatch(p -> Math.abs(new Vector3(p).sub(seam).dot(along)) < .001f)),
                          "No end wall across connected natural bridges");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void roadApproachesShareTheSlopeAndSupportAcrossBothInsets(int direction) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var roadAt = at.translated(direction);
            int exits = (1 << direction) | (1 << ((direction + 3) % 6));
            for (int roadLevel : new int[] { 0, 2 }) {
                var scene = BoardScenes.scene(c -> c.equals(at) ? BridgeScenes.bridge(c, -1, 2, exits)
                      : BoardScenes.tile(c, c.equals(roadAt) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                            c.equals(roadAt) ? exits : 0, c.equals(roadAt) ? roadLevel : -1, BoardScene.Surface.GRASS));
                var tile = scene.tile(at);
                var center = BoardGeometry.center(at, 1);
                var roadCenter = BoardGeometry.center(roadAt, roadLevel);
                int edge = Math.floorMod(1 - direction, 6);
                var across = BoardGeometry.corner(at, 0, edge + 1).sub(BoardGeometry.corner(at, 0, edge)).nor();
                float lift = GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
                for (var lod : TerrainLod.values()) {
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                    var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
                    assertEquals(roadLevel == 0, (footing.solidExits() & (1 << direction)) != 0,
                          "A raised bridge owns a solid approach over the unchanged bank");
                    var road = surfaces.get(roadAt);
                    assertEquals(center.z + lift, BridgeScenes.height(shape, center, false), .01f);
                    assertEquals(roadCenter.z, road.height(roadCenter.x, roadCenter.y), .01f);
                    // One line from one inset to the other must describe both actual meshes; a solid ramp eases.
                    for (float t : new float[] { .3f, .4f, .499f, .501f, .6f, .7f }) {
                        for (float lateral : new float[] { -6, 0, 6 }) {
                            var p = new Vector3(center).lerp(roadCenter, t)
                                  .mulAdd(across, lateral * BoardGeometry.hexScale());
                            float expected = center.z
                                  + (roadCenter.z - center.z) * (roadLevel == 0 ? BridgeScenes.eased(t) : 2 * t - .5f);
                            if (t < .5f || roadLevel == 0) {
                                assertEquals(expected + lift, BridgeScenes.height(shape, p, false), roadLevel == 0 ? BridgeScenes.EASED : .015f,
                                      "Solid approach: " + lod);
                            } else {
                                assertEquals(expected, road.height(p.x, p.y), .015f, "Road inset: " + lod + " x=" + x
                                      + " roadLevel=" + roadLevel + " t=" + t + " lateral=" + lateral);
                            }
                        }
                    }
                    if (roadLevel == 0) {
                        // The ground: loose scatter stones stand on it at their own height.
                        assertTrue(road.foundation().stream().allMatch(f -> Math.max(f.a().z, Math.max(f.b().z, f.c().z)) <= lift),
                              "The raised block must not pull the bank or shoreline up into its sides");
                        var along = new Vector3(roadCenter).sub(center);
                        along.z = 0;
                        float length = along.len();
                        along.nor();
                        var outward = new Vector3(-along.y, along.x, 0);
                        assertEquals(length * .25f + BoardBridgeFooting.terminalLength() * BoardGeometry.hexScale(),
                              footing.lengths().get(direction) * BoardGeometry.hexScale(), .001f,
                              "The entrance wedge starts at the road inset and finishes on its flat hub");
                        for (float t : new float[] { .3f, .49f, .51f, .7f }) {
                            var p = new Vector3(center).lerp(roadCenter, t);
                            float grade = center.z + (roadCenter.z - center.z) * BridgeScenes.eased(t) + lift;
                            for (int side : new int[] { -1, 1 }) {
                                var rail = new Vector3(p).mulAdd(across, side * 16.5f * BoardGeometry.hexScale());
                                assertEquals(grade + 2.5f * BoardGeometry.hexScale(), BridgeScenes.height(shape, rail, false), BridgeScenes.EASED,
                                      "Both rails continue along the ramp without a terminal interrupting the grade");
                                var origin = new Vector3(p).mulAdd(outward, side * 30 * BoardGeometry.hexScale());
                                origin.z = (grade - BoardGeometry.level()) / 2;
                                assertTrue(Float.isFinite(footing.shape().hit(new Ray(origin, new Vector3(outward).scl(-side)))),
                                      "The single support closes both sides across the road/bridge boundary");
                            }
                        }
                        var base = new Vector3(center).lerp(roadCenter, .75f);
                        for (int side : new int[] { -1, 1 }) {
                            var cap = new Vector3(base).mulAdd(along,
                                        BoardBridgeFooting.terminalLength() * BoardGeometry.hexScale() - BoardRelief.metres(1.38f))
                                  .mulAdd(outward, side * (18 * BoardGeometry.hexScale() + BoardRelief.metres(.15f)));
                            assertTrue(BridgeScenes.height(shape, cap, false) > roadCenter.z + lift + 2.5f * BoardGeometry.hexScale(),
                                  "The authored entrance block is beyond the ramp, on the flat road");
                        }
                    } else {
                        assertFalse(road.faces.stream().anyMatch(road::bridgeSupport),
                              "The descending approach keeps its excavated natural bank");
                    }
                    assertFalse(road.faces.stream().filter(f -> f.a().z == roadCenter.z && f.b().z == roadCenter.z
                          && f.c().z == roadCenter.z).anyMatch(road::bridgeSupport), "The flat road hub keeps its ground");
                    // The bridge half has a real support down to the ground, while its central crossing stays open.
                    for (int side : new int[] { -1, 1 }) {
                        var p = new Vector3(center).lerp(roadCenter, .375f);
                        p.z = 0;
                        var origin = new Vector3(p).mulAdd(across, side * 30 * BoardGeometry.hexScale());
                        assertTrue(Float.isFinite(footing.shape().hit(new Ray(origin, new Vector3(across).scl(-side)))),
                              "Concrete fills the entire sloping bridge end: " + lod);
                    }
                    var under = new Vector3(center.x, center.y, 0).mulAdd(across, -30 * BoardGeometry.hexScale());
                    assertFalse(Float.isFinite(footing.shape().hit(new Ray(under, across))), "The level span remains open below");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void bareLowerBanksHaveTheSameRampAndSolidSupportAsRoads(int direction) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var bankAt = at.translated(direction);
            var center = BoardGeometry.center(at, 1);
            var bankCenter = BoardGeometry.center(bankAt, 0);
            var along = new Vector3(bankCenter).sub(center);
            along.z = 0;
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            float scale = BoardGeometry.hexScale();
            float lift = GpuRoads.SURFACE_LIFT * scale;
            for (var lod : TerrainLod.values()) {
                for (boolean paved : new boolean[] { false, true }) {
                    var scene = BridgeScenes.lowerBank(at, direction, paved);
                    var tile = scene.tile(at);
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                    var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
                    assertEquals(.5f, BoardBridge.edgeElevation(tile, scene.tile(bankAt), direction));
                    assertTrue((footing.solidExits() & 1 << direction) != 0,
                          "A real lower bank supports the ramp whether or not it has road paint");
                    for (float t : new float[] { .3f, .4f, .499f, .501f, .6f, .7f }) {
                        for (float lateral : new float[] { -3, 0, 3 }) {
                            var p = new Vector3(center).lerp(bankCenter, t).mulAdd(across, lateral * scale);
                            float expected = (1 - BridgeScenes.eased(t)) * BoardGeometry.level() + lift;
                            assertEquals(expected, BoardBridge.top(shape, p.x, p.y), BridgeScenes.EASED,
                                  "Continuous deck over both hexes: paved=" + paved + " lod=" + lod + " t=" + t);
                            assertEquals(expected, BridgeScenes.height(shape, p, false), BridgeScenes.EASED,
                                  "Picking must use the same sloping top as drawing");
                        }
                        var p = new Vector3(center).lerp(bankCenter, t);
                        p.z = -.25f * BoardGeometry.level();
                        for (int side : new int[] { -1, 1 }) {
                            var ray = new Ray(new Vector3(p).mulAdd(across, side * 30 * scale),
                                  new Vector3(across).scl(-side));
                            assertTrue(Float.isFinite(footing.shape().hit(ray)),
                                  "The ramp has real side support on both sides of the boundary");
                        }
                    }
                    var bank = surfaces.get(bankAt);
                    assertEquals(0, bank.height(bankCenter.x, bankCenter.y), .001f,
                          "The lower bank keeps its authored ground level");
                    var under = new Vector3(center.x, center.y, 0).mulAdd(across, -30 * scale);
                    assertFalse(Float.isFinite(footing.shape().hit(new Ray(under, across))),
                          "The centre of the span remains open underneath");
                }
            }
        }
    }

    @Test
    void bareBankRampsDoNotInventConnectionsOrAlterNaturalAndHigherBanks() {
        var at = new Coords(4, 4);
        int direction = 3;
        var bridge = BridgeScenes.bridge(at, -1, 2, 9);
        var bankAt = at.translated(direction);
        for (int level : new int[] { -2, -1, 1, 2, 3 }) {
            var bank = BoardScenes.tile(bankAt, BoardRoad.Kind.NONE, 0, level, BoardScene.Surface.GRASS);
            assertEquals(1, BoardBridge.edgeElevation(bridge, bank, direction),
                  "Only a one-level drop to an otherwise bare bank changes the deck grade");
        }
        var lower = BoardScenes.tile(bankAt, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS);
        var water = BoardScenes.tile(bankAt, BoardScene.Surface.GRASS, 0, 1, 0);
        assertEquals(1, BoardBridge.edgeElevation(bridge, water, direction), "Water cannot support a new ramp");
        assertEquals(1, BoardBridge.edgeElevation(bridge, null, direction), "The board edge cannot support a new ramp");
        assertEquals(1, BoardBridge.edgeElevation(BridgeScenes.bridge(at, -1, 2, 1), lower, direction),
              "A side without a bridge exit remains closed");
        var scene = BridgeScenes.typed(BridgeScenes.lowerBank(at, direction, false), HexAppearance.NATURAL_BRIDGE);
        assertEquals(1, BoardBridge.edgeElevation(scene.tile(at), scene.tile(bankAt), direction),
              "Natural bridges retain their existing bank geometry");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void trussFasciaAndRampConcreteNeverShareAnExposedFace(int direction) {
        float scale = BoardGeometry.hexScale();
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            var roadAt = at.translated(direction);
            int exits = (1 << direction) | (1 << ((direction + 3) % 6));
            for (int roadLevel : new int[] { 0, 2 }) {
                var scene = BridgeScenes.typed(BoardScenes.scene(c -> c.equals(at)
                      ? BridgeScenes.bridge(c, -1, 2, exits)
                      : BoardScenes.tile(c, c.equals(roadAt) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                            c.equals(roadAt) ? exits : 0, c.equals(roadAt) ? roadLevel : -1, BoardScene.Surface.GRASS)),
                      HexAppearance.TRUSS);
                var tile = scene.tile(at);
                var center = BoardGeometry.center(at, 1);
                var roadCenter = BoardGeometry.center(roadAt, roadLevel);
                var along = new Vector3(roadCenter).sub(center);
                along.z = 0;
                along.nor();
                var across = new Vector3(-along.y, along.x, 0);
                for (var lod : TerrainLod.values()) {
                    var footing = BoardBridgeFooting.build(scene, tile, lod, new HashMap<>());
                    var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
                    var steel = BoardBridge.shape(shape.surface(), shape.level(), shape.facets().stream()
                          .filter(f -> f.part() == BoardBridge.Part.STEEL).toList());
                    var concrete = BoardBridge.shape(shape.surface(), shape.level(), shape.facets().stream()
                          .filter(f -> f.part() != BoardBridge.Part.STEEL).toList());
                    for (float t : new float[] { .31f, .38f, .44f }) {
                        for (float below : new float[] { -.6f, .35f, 1.7f }) {
                            var p = new Vector3(center).lerp(roadCenter, t);
                            p.z = center.z + (roadCenter.z - center.z) * (2 * t - .5f)
                                  + (GpuRoads.SURFACE_LIFT - below) * scale;
                            for (int side : new int[] { -1, 1 }) {
                                var ray = new Ray(new Vector3(p).mulAdd(across, side * 30 * scale),
                                      new Vector3(across).scl(-side));
                                float steelDistance = (float) Math.sqrt(steel.hit(ray));
                                float concreteDistance = (float) Math.sqrt(concrete.hit(ray));
                                assertEquals((30 - BoardRoad.Kind.PAVED.halfWidth - BoardBridge.SHOULDER) * scale,
                                      Math.min(steelDistance, concreteDistance), .01f, "The ramp side stays closed");
                                assertFalse(Math.abs(steelDistance - concreteDistance) < .001f,
                                      "Steel and concrete z-fight: direction=" + direction + " road=" + roadLevel
                                            + " lod=" + lod + " t=" + t + " below=" + below);
                            }
                        }
                    }
                    var suspended = new Vector3(center).mulAdd(along, 3 * scale);
                    suspended.z += (GpuRoads.SURFACE_LIFT - 1.7f) * scale;
                    assertTrue(Float.isFinite(steel.hit(new Ray(suspended.mulAdd(across, 30 * scale),
                          new Vector3(across).scl(-1)))), "The unsupported centre retains its full steel fascia");
                }
            }
        }
    }

    static BoardBridge.Shape manufactured(BoardScene scene, BoardScene.Tile tile) {
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, new HashMap<>());
        return BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
    }
}
