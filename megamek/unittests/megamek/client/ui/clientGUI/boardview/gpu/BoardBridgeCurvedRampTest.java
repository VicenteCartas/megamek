/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardBridgeCurvedRampTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void curvedDeckRampSupportStaysInsideItsActualSlab() {
        for (int direction = 0; direction < 6; direction++) {
            for (int turn : new int[] { 2, 4 }) {
                for (int x : new int[] { 3, 4 }) {
                    var at = new Coords(x, 4);
                    var scene = scene(at, direction, turn);
                    var tile = scene.tile(at);
                    var deck = BoardBridge.deck(scene, tile);
                    var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.values()[direction % 4], new HashMap<>());
                    var slab = deck.slab(at).footprint(BoardBridge.SHOULDER + .15f);
                    var center = BoardGeometry.center(at, 0);
                    float scale = BoardGeometry.hexScale();
                    int sampled = 0;
                    for (var face : footing.shape().facets()) {
                        var centroid = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                        for (var point : List.of(face.a(), face.b(), face.c(), centroid)) {
                            if (!BoardGeometry.contains(at, point.x, point.y)) { continue; }
                            sampled++;
                            assertTrue(slab.contains((point.x - center.x) / scale, (point.y - center.y) / scale),
                                  "No straight support wedge outside curved slab: exit=" + direction + " turn=" + turn
                                        + " parity=" + (x & 1) + " point=" + point + " part=" + face.part());
                        }
                    }
                    assertTrue(sampled > 0, "The bridge half retains a real solid approach");
                }
            }
        }
    }

    @Test
    void curvedCarriagewayAndItsPaintMeetTheSupportedBankOnTheSharedGrade() {
        float scale = BoardGeometry.hexScale();
        for (int direction = 0; direction < 6; direction++) {
            for (int turn : new int[] { 2, 4 }) {
                for (int x : new int[] { 3, 4 }) {
                    var at = new Coords(x, 4);
                    var scene = scene(at, direction, turn);
                    var tile = scene.tile(at);
                    var deck = BoardBridge.deck(scene, tile);
                    var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.values()[direction % 4], new HashMap<>());
                    var shape = BoardBridgeSlope.build(tile, deck, footing);
                    var profile = BoardBridgeSlope.profile(tile, deck);
                    float drop = BoardBridgeSlope.easedRise(deck, footing.easedExits());
                    var center = BoardGeometry.center(at, 0);
                    var road = deck.road(at);
                    var lane = road.footprint(-2);
                    String context = "exit=" + direction + " turn=" + turn + " parity=" + (x & 1);
                    int sampled = 0;
                    for (float px = -38; px <= 38; px += 2) {
                        for (float py = -34; py <= 34; py += 2) {
                            float wx = center.x + px * scale, wy = center.y + py * scale;
                            if (!lane.contains(px, py) || !BoardGeometry.contains(at, wx, wy)) { continue; }
                            float rise = BoardSurface.sampleHeight(profile, wx, wy, Float.NaN);
                            assertTrue(Float.isFinite(rise), "The entire curved lane has a grade: " + context);
                            assertEquals(BoardBridge.deckZ(tile) + BoardBridgeSlope.eased(rise, drop),
                                  BoardBridge.top(shape, wx, wy), BoardBridgeSlopeTest.EASED,
                                  "The curved lane cannot acquire a hole or a straight ramp ledge: " + context
                                        + " local=" + px + "," + py);
                            sampled++;
                        }
                    }
                    assertTrue(sampled > 50, "Sample the curved carriageway, not only its straight mouth");

                    var deckCenter = BoardGeometry.center(at, 1);
                    var bank = BoardGeometry.center(at.translated(direction), 0);
                    var across = new Vector3(bank).sub(deckCenter);
                    across.set(-across.y, across.x, 0).nor();
                    int edge = Math.floorMod(1 - direction, 6);
                    var a = BoardGeometry.corner(at, 0, edge);
                    var b = BoardGeometry.corner(at, 0, edge + 1);
                    var normal = new Vector3(-(b.y - a.y), b.x - a.x, 0).nor();
                    float span = (bank.x - deckCenter.x) * normal.x + (bank.y - deckCenter.y) * normal.y;
                    for (float t : new float[] { .499f, .501f, .6f, .7f }) {
                        for (float lateral : new float[] { -10, 0, 10 }) {
                            var point = new Vector3(deckCenter).lerp(bank, t).mulAdd(across, lateral * scale);
                            float distance = ((point.x - deckCenter.x) * normal.x
                                  + (point.y - deckCenter.y) * normal.y) / span;
                            float expected = deckCenter.z
                                  + (bank.z - deckCenter.z) * BoardBridgeSlopeTest.eased(distance)
                                  + GpuRoads.SURFACE_LIFT * scale;
                            assertEquals(expected, BoardBridge.top(shape, point.x, point.y), BoardBridgeSlopeTest.EASED,
                                  "The slab and solid bank half meet without a gap or height step: " + context);
                        }
                    }
                    for (var patch : GpuRoads.deckPatches(tile, deck, road)) {
                        for (var face : GpuRoads.deck(patch, shape, GpuRoads.SURFACE_LIFT)) {
                            var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                            float px = (point.x - center.x) / scale, py = (point.y - center.y) / scale;
                            if (!lane.contains(px, py) || !patch.shape().contains(px, py)
                                  || !BoardGeometry.contains(at, point.x, point.y)) { continue; }
                            float rise = BoardSurface.sampleHeight(profile, point.x, point.y, Float.NaN);
                            float coat = (patch.lift() + .01f - GpuRoads.SURFACE_LIFT) * scale;
                            assertEquals(BoardBridge.deckZ(tile) + BoardBridgeSlope.eased(rise, drop) + coat, point.z,
                                  BoardBridgeSlopeTest.EASED,
                                  "Road paint follows the curved physical grade: " + context);
                        }
                    }
                }
            }
        }
    }

    @Test
    void cityJunctionParapetsKeepTheApproachGradeUntilTheyReachTheLevelDeck() throws Exception {
        var scene = GpuRoadSourceTest.scene("unofficial/Lanceman/16x17 City Hills Residential 2 (with Structures).board");
        var at = new Coords(8, 1);
        var tile = scene.tile(at);
        var deck = BoardBridge.deck(scene, tile);
        var center = BoardGeometry.center(at, tile.elevation());
        var bank = scene.tile(at.translated(4));
        var bankCenter = BoardGeometry.center(bank.coords(), bank.elevation());
        var a = BoardGeometry.corner(at, 0, 3);
        var b = BoardGeometry.corner(at, 0, 4);
        var normal = new Vector3(-(b.y - a.y), b.x - a.x, 0).nor();
        float span = new Vector3(bankCenter).sub(center).dot(normal);
        float scale = BoardGeometry.hexScale();
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var footing = BoardBridgeFooting.build(scene, tile, lod, new HashMap<>());
            var shape = BoardBridgeSlope.build(tile, deck, footing);
            int sampled = 0;
            // Sample the actual parapet caps, including their curved elbows, using the installed picking shape.
            for (var face : BoardBridgeMesh.build(tile, deck)) {
                if (face.part() != BoardBridge.Part.STRUCTURE || face.normal().z < .99f
                      || Math.abs(face.a().z - 2.5f) > .001f) { continue; }
                var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(scale / 3).add(center);
                if (!BoardGeometry.contains(at, point.x, point.y)) { continue; }
                float projected = new Vector3(point).sub(center).dot(normal) / span;
                float rise = Math.max(0, (bankCenter.z - center.z) * (2 * projected - .5f));
                assertEquals(BoardBridge.deckZ(tile) + rise + 2.5f * scale,
                      BoardBridgeSlopeTest.height(shape, point, false), .02f,
                      "A curved parapet must not acquire a diagonal ridge beside the level junction: " + lod + " " + point);
                sampled++;
            }
            assertTrue(sampled > 20, "Sample the curved parapet as well as its straight mouths");
        }
    }

    private static BoardScene scene(Coords at, int direction, int turn) {
        int other = (direction + turn) % 6;
        int exits = 1 << direction | 1 << other;
        int straight = 1 << direction | 1 << (direction + 3) % 6;
        var bank = at.translated(direction);
        var span = at.translated(other);
        return BoardScenes.scene(c -> {
            if (c.equals(at) || c.equals(span)) {
                int sides = c.equals(at) ? exits : 1 << other | 1 << (other + 3) % 6;
                return BoardBridgeMaterialsTest.withDeckRoad(BoardBridgeSlopeTest.bridge(c, -1, 2, sides),
                      BoardRoad.Kind.PAVED, sides);
            }
            return BoardScenes.tile(c, c.equals(bank) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.equals(bank) ? straight : 0, c.equals(bank) ? 0 : -1, BoardScene.Surface.GRASS);
        });
    }
}
