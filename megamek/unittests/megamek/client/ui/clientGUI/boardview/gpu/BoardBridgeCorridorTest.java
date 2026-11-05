/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Road and rail share one bridge slab, including where the track ends beside a continuing road. */
class BoardBridgeCorridorTest {
    private static final Coords AT = new Coords(4, 4);

    @Test
    void anIndependentDeckRailPreventsAnIslandInTheRoadAndItsSupportingSlab() {
        for (boolean rail : new boolean[] { false, true }) {
            Hex hex = new Hex(0, "bridge:1:63;bridge_cf:40;bridge_elev:2;bridge_road:1:31"
                  + (rail ? ";bridge_rail:1:32" : ""), "");
            var tile = BoardBridgeMaterialsTest.withDeckRoad(BoardBridgeSlopeTest.bridge(AT, 0, 2, 63),
                  BoardRoad.Kind.PAVED, 31).withFeatures(BoardFeatures.capture(hex, AT, Map.of()));
            var scene = BoardScenes.scene(at -> at.equals(AT) ? tile
                  : BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS));
            var deck = BoardBridge.deck(scene, tile);
            assertEquals(0, deck.corridor().sides(), "The independent rail has no shared road approach");
            assertEquals(rail, deck.road(AT).footprint(0).contains(0, 0),
                  "A rail-bearing road hub must not grow a roundabout island");
            assertEquals(rail, deck.slab(AT).footprint(0).contains(0, 0),
                  "The structural bridge deck must carry the independent track across its hub");
            assertEquals(rail ? 5 : 0, deck.road(AT).stopApproaches(31).size());
        }
    }

    @Test
    void aPlainBridgeHasThirtyPixelCarriagewayAndThreePixelShoulders() {
        for (boolean truss : new boolean[] { false, true }) {
            var shape = shape(-1, RailRoute.Corridor.NONE, truss);
            assertEquals(36, shape.bounds().getWidth(), .001f, "The former 18px deck is twice as wide");
            for (float x : new float[] { -14.9f, 0, 14.9f }) { assertEquals(0, height(shape, x, 0), .001f); }
            for (float x : new float[] { -16.5f, 16.5f }) { assertTrue(height(shape, x, 0) > 1); }
        }
    }

    @Test
    void movingTheTrackBesideTheRoadKeepsOneWideDeckWithOnlyOutsideBarriers() {
        for (boolean truss : new boolean[] { false, true }) {
            for (int beside : new int[] { 0, 9 }) {
                for (int right : new int[] { 0, 9 }) {
                    var shape = shape(9, new RailRoute.Corridor(9, beside, right), truss);
                    for (float y : new float[] { -30, -15, 0, 15, 30 }) {
                        for (float x : new float[] { -20.5f, -RailRoute.BESIDE_RAIL, -2, 0, 2, RailRoute.BESIDE_RAIL, 20.5f }) {
                            assertEquals(0, height(shape, x, y), .001f,
                                  "The road, rail and space between them belong to one unobstructed deck");
                        }
                    }
                    float barrier = BoardRoad.CORRIDOR_HALF + BoardBridge.SHOULDER / 2;
                    for (float x : new float[] { -barrier, barrier }) {
                        assertTrue(height(shape, x, 0) > 1, "The outside bridge barriers are retained");
                    }
                }
            }
        }
    }

    @Test
    void northSouthSeamsSupportTheFullBallastAndCarsWhileShouldersOverhangUnusedHexes() {
        for (boolean truss : new boolean[] { false, true }) {
            for (var corridor : List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 0),
                  new RailRoute.Corridor(9, 9, 9))) {
                var shape = shape(9, corridor, truss);
                float rail = RailRoute.BESIDE_RAIL * corridor.lateral(0);
                float road = RailRoute.BESIDE_ROAD * corridor.lateral(0);
                float lane = corridor.beside() == 0 ? (BoardRoad.MIDDLE_HALF + BoardRoad.MEDIAN_HALF) / 2
                      : BoardRoad.Kind.PAVED.halfWidth / 2;
                for (int end : new int[] { -1, 1 }) {
                    for (float x : new float[] { rail - 5.6f, rail - 3.8f, rail, rail + 3.8f, rail + 5.6f,
                          road - lane - 3, road - lane + 3, road + lane - 3, road + lane + 3 }) {
                        assertEquals(0, height(shape, x, end * 35.9f), .001f,
                              "Ballast, sleepers and scale-1 cars retain a flat carrier past the 42px edge corners");
                        var crossing = new Ray(new Vector3(x, end * 35.75f, 1), new Vector3(0, end, 0));
                        assertTrue(shape.hit(crossing) > .6f * .6f, "No seam barrier across the occupied width");
                    }
                    float barrier = BoardRoad.CORRIDOR_HALF + BoardBridge.SHOULDER / 2;
                    assertTrue(height(shape, barrier, end * 35.9f) > 1,
                          "The outer shoulder continues into the unused intermediate hex instead of narrowing at the corner");
                }
            }
        }
    }

    @Test
    void trackTerminationDoesNotBuildAWallBetweenTheTrackAndContinuingRoad() {
        for (boolean truss : new boolean[] { false, true }) {
            for (int right : new int[] { 0, 8 }) {
                var shape = shape(8, new RailRoute.Corridor(8, 8, right), truss);
                for (float y : new float[] { -30, -15, 0, 4 }) {
                    for (float x : new float[] { -18.5f, -RailRoute.BESIDE_RAIL, -2, 0, 2, RailRoute.BESIDE_RAIL, 18.5f }) {
                        assertEquals(0, height(shape, x, y), .001f,
                              "The rail stop must not grow an internal parapet or rounded truss enclosure");
                    }
                }
                for (float y : new float[] { 12, 24, 34 }) {
                    assertEquals(0, height(shape, 0, y), .001f, "The road continues past the track end");
                }
            }
        }
    }

    @Test
    void taperedTrussKeepsOpenPanelsWithoutCuttingAcrossItsCarriageway() {
        var corridor = new RailRoute.Corridor(8, 8, 8);
        var shape = shape(8, corridor, true);
        var carriageway = BoardRoad.layout(AT, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED)
              .bridgeCorridor(corridor).footprint(-.05f);
        for (var face : shape.facets()) {
            if (face.part() != BoardBridge.Part.STEEL) { continue; }
            for (var point : List.of(face.a(), face.b(), face.c())) {
                if (point.z > 2) {
                    assertFalse(carriageway.contains(point.x, point.y), "Steel intrudes into the road at " + point);
                }
            }
        }
        int covered = 0, longest = 0;
        for (float y = .125f; y < 35; y += .25f) {
            float hit = shape.hit(new Ray(new Vector3(-30, y, 6), new Vector3(1, 0, 0)));
            // Only the nearer truss: a hit beyond the center belongs to the far side.
            covered = hit < 30 * 30 ? covered + 1 : 0;
            longest = Math.max(longest, covered);
        }
        assertTrue(longest * .25f < 4, "The taper must keep open Warren panels rather than a dense steel wall");
    }

    private static BoardBridge.Shape shape(int railExits, RailRoute.Corridor corridor, boolean truss) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        if (railExits >= 0) {
            hex.setDecorations(List.of(new BoardDecoration("track", "prop", RailRoute.Kind.RAIL.asset, null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.on("bridge", 0), 0).withConnections(railExits)));
        }
        var tile = BoardBridgeSlopeTest.bridge(AT, 0, 1, 9)
              .withFeatures(BoardFeatures.capture(hex, AT, Map.of()));
        var deck = new BoardBridge.Deck(BoardRoad.Kind.PAVED, 9, 9, false,
              Collections.nCopies(6, BoardRoad.Kind.PAVED), BoardScene.Surface.CONCRETE,
              Collections.nCopies(6, 0f), false, truss ? BoardDecoration.Colours.of("#732417") : null, 9, corridor);
        return BoardBridge.shape(deck.surface(), 0, BoardBridgeMesh.build(tile, deck));
    }

    private static float height(BoardBridge.Shape shape, float x, float y) {
        return 30 - (float) Math.sqrt(shape.hit(new Ray(new Vector3(x, y, 30), new Vector3(0, 0, -1))));
    }
}
