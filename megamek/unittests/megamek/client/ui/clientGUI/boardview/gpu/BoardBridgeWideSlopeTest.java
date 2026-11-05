/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A road-and-rail bridge grades its whole carrier, including the outside parapets and support faces. */
class BoardBridgeWideSlopeTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void outsideParapetsAndBothLanesShareTheRampGradeAtEveryOrientation() {
        float scale = BoardGeometry.hexScale();
        for (int direction = 0; direction < 6; direction++) {
            for (int parity = 0; parity < 2; parity++) {
                for (boolean beside : new boolean[] { false, true }) {
                    var at = new Coords(3 + parity, 4);
                    var scene = wideBank(at, direction, beside, false);
                    var tile = scene.tile(at);
                    // All four LODs are exercised on every axis without repeating the same grade at each LOD.
                    var lod = TerrainLod.values()[2 * parity + (beside ? 1 : 0)];
                    var footing = BoardBridgeFooting.build(scene, tile, lod, new HashMap<>());
                    var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
                    var center = BoardGeometry.center(at, 1);
                    var bank = BoardGeometry.center(at.translated(direction), 0);
                    var along = new Vector3(bank).sub(center);
                    along.z = 0;
                    along.nor();
                    var across = new Vector3(-along.y, along.x, 0);
                    int edge = Math.floorMod(1 - direction, 6);
                    var edgeVector = BoardGeometry.corner(at, 0, edge + 1).sub(BoardGeometry.corner(at, 0, edge));
                    var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
                    for (float t : new float[] { .3f, .4f, .49f }) {
                        var point = new Vector3(center).lerp(bank, t);
                        String context = "direction=" + direction + " parity=" + parity + " beside=" + beside
                              + " lod=" + lod + " t=" + t;
                        for (int side : new int[] { -1, 1 }) {
                            var parapet = new Vector3(point).mulAdd(across,
                                  side * (BoardRoad.CORRIDOR_HALF + BoardBridge.SHOULDER / 2) * scale);
                            assertEquals(grade(center, bank, parapet, normal) + 2.5f * scale,
                                  BoardBridgeSlopeTest.height(shape, parapet, false), BoardBridgeSlopeTest.EASED,
                                  "The outer parapet follows the same grade as the center: " + context);
                            var lane = new Vector3(point).mulAdd(across, side * 19.5f * scale);
                            float grade = grade(center, bank, lane, normal);
                            assertEquals(grade, BoardBridge.top(shape, lane.x, lane.y), BoardBridgeSlopeTest.EASED,
                                  "The carrier remains planar across its entire width: " + context);
                            assertEquals(grade, BoardBridgeSlopeTest.height(shape, lane, false),
                                  BoardBridgeSlopeTest.EASED,
                                  "Picking sees the drawn lane without a raised sliver: " + context);
                        }
                    }
                }
            }
        }
    }

    /** The slightly non-regular hex lattice grades across its real edge, not perpendicular to its center line. */
    private static float grade(Vector3 center, Vector3 bank, Vector3 point, Vector3 normal) {
        float span = (bank.x - center.x) * normal.x + (bank.y - center.y) * normal.y;
        float projected = ((point.x - center.x) * normal.x + (point.y - center.y) * normal.y) / span;
        return center.z + (bank.z - center.z) * BoardBridgeSlopeTest.eased(projected)
              + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
    }

    @Test
    void wideTrussFasciaDoesNotOverlapTheConcreteRampSides() {
        float scale = BoardGeometry.hexScale();
        for (int direction : new int[] { 1, 3 }) {
            var at = new Coords(direction == 1 ? 3 : 4, 4);
            var scene = wideBank(at, direction, true, true);
            var tile = scene.tile(at);
            var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, new HashMap<>());
            var shape = BoardBridgeSlope.build(tile, BoardBridge.deck(scene, tile), footing);
            var steel = BoardBridge.shape(shape.surface(), shape.level(), shape.facets().stream()
                  .filter(face -> face.part() == BoardBridge.Part.STEEL).toList());
            var concrete = BoardBridge.shape(shape.surface(), shape.level(), shape.facets().stream()
                  .filter(face -> face.part() != BoardBridge.Part.STEEL).toList());
            var center = BoardGeometry.center(at, 1);
            var bank = BoardGeometry.center(at.translated(direction), 0);
            var along = new Vector3(bank).sub(center);
            along.z = 0;
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            for (float t : new float[] { .31f, .44f }) {
                for (float below : new float[] { .35f, 1.7f }) {
                    var point = new Vector3(center).lerp(bank, t);
                    point.z = (1.5f - 2 * t) * BoardGeometry.level() + (GpuRoads.SURFACE_LIFT - below) * scale;
                    for (int side : new int[] { -1, 1 }) {
                        var ray = new Ray(new Vector3(point).mulAdd(across, side * 30 * scale),
                              new Vector3(across).scl(-side));
                        float steelDistance = (float) Math.sqrt(steel.hit(ray));
                        float concreteDistance = (float) Math.sqrt(concrete.hit(ray));
                        assertTrue(Float.isFinite(concreteDistance), "The broad ramp has a real support face");
                        assertEquals((30 - BoardRoad.CORRIDOR_HALF - BoardBridge.SHOULDER) * scale,
                              Math.min(steelDistance, concreteDistance), .02f, "The outside support remains closed");
                        assertFalse(Math.abs(steelDistance - concreteDistance) < .001f,
                              "No coplanar steel and concrete face at direction=" + direction + " t=" + t);
                    }
                }
            }
        }
    }

    private static BoardScene wideBank(Coords at, int direction, boolean beside, boolean truss) {
        var scene = BoardBridgeSlopeTest.lowerBank(at, direction, true);
        var t = scene.tile(at);
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        var corridor = new RailRoute.Corridor(exits, beside ? exits : 0, 0);
        var tile = new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(),
              t.surface(), t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(),
              t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(),
              t.cliffTopExits(), t.bare(), t.groundCover(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(),
              Map.of("bridge", truss ? HexAppearance.TRUSS : HexAppearance.BUILT_BRIDGE), t.cover(), t.barren(),
              t.deckRoad(), t.deckRoadExits(), t.deckMud(), t.railBed(), t.corridor(), corridor);
        return BoardScenes.replace(scene, tile);
    }
}
