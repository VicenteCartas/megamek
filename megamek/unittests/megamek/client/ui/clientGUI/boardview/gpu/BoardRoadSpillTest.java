/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRoadSpillTest {
    private static final Coords AT = new Coords(4, 4);

    @Test
    void paintKeepsToDryGroundAtItsLevelWhileClearanceAlsoReachesRampsAndRails() {
        var scene = BoardScenes.scene(c -> {
            if (c.equals(AT.translated(4))) {
                return BridgeScenes.routeTile(c, false, RailRoute.Kind.RAIL, 0, 9, false, 0);
            }
            boolean wet = c.equals(AT.translated(2));
            int level = c.equals(AT.translated(1)) ? 1 : 0;
            return new BoardScene.Tile(c, level, wet ? 1 : -1, false, 9, BoardScene.Surface.GRASS, null, null,
                  List.of(), List.of(), wet ? BoardLiquid.WATER : BoardLiquid.NONE,
                  null, !c.equals(AT.translated(3)), BoardRoad.Kind.PAVED);
        });
        var roads = BoardRoad.nearby(scene, scene.tile(AT));
        assertEquals(List.of(AT, AT.translated(0), AT.translated(3), AT.translated(5)),
              roads.stream().map(BoardRoad::coords).toList(), "A Classic-artwork neighbor still contributes its native road");
        var ways = BoardRoad.nearbyWays(scene, scene.tile(AT));
        // A graded road's ramp reaches a neighbour of another level (BoardRoad.nearby); a rail clears plants too.
        assertEquals(List.of(AT, AT.translated(0), AT.translated(1), AT.translated(2), AT.translated(3), AT.translated(4),
                    AT.translated(5)),
              ways.stream().map(BoardRoad::coords).toList(), "Clearance reaches every neighbour's way, under water too");
        assertEquals(List.of(AT.translated(2)), BoardRoad.nearby(scene, scene.tile(AT.translated(2))).stream().map(BoardRoad::coords).toList(),
              "A road a level under water lies on its bed, a level below the roads around it");
    }

    @Test
    void adjacentMembersContributeOneSharedRoundaboutWithTheCorrectWorldOrigin() {
        var scene = triangle(BoardScene.Surface.GRASS);
        Coords owner = BoardRoad.of(scene, scene.tile(AT)).owner();
        for (Coords at : List.of(AT, AT.translated(2), AT.translated(3))) {
            var nearby = BoardRoad.nearby(scene, scene.tile(at));
            assertEquals(at, nearby.getFirst().coords(), "The receiving tile's own road remains first");
            assertEquals(1, nearby.stream().filter(road -> road.owner().equals(owner)).count());
            BoardRoad ring = BoardRoad.of(scene, scene.tile(at));
            float x = BoardGeometry.centerX(at), y = BoardGeometry.centerY(at);
            assertEquals(ring.distance(0, 0), BoardRoad.distance(List.of(ring), x, y), .0001f);
        }
    }

    @Test
    void grassAndScatteredRocksStayClearOfTheRingInIntermediateHexes() {
        var scene = triangle(BoardScene.Surface.GRASS);
        var dry = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS));
        List<BoardRoad> allRoads = scene.tiles().stream().filter(BoardRoad::rendered).map(tile -> BoardRoad.of(scene, tile)).toList();
        int removed = 0, kept = 0, stones = 0;
        for (Coords at : intermediate(scene)) {
            var surface = new BoardSurface(dry, dry.tile(at));
            var support = BoardTacticalGeometry.Surface.of(surface, dry, BoardGeometry.floor(dry));
            var before = roots(GpuGroundCover.plant(dry, dry.tile(at), support, null));
            var after = roots(GpuGroundCover.plant(scene, scene.tile(at), support, null));
            assertTrue(before.containsAll(after), "Clearing a neighboring road keeps every surviving root's position and rank");
            for (var root : before) {
                float distance = BoardRoad.distance(allRoads, root.get(0), root.get(1));
                assertEquals(distance >= BoardRoad.SHOULDER + 1, after.contains(root),
                      "Grass follows the world-space road and shoulder even without ROAD terrain on its own hex");
            }
            removed += before.size() - after.size();
            kept += after.size();
            for (var face : new BoardSurface(scene, scene.tile(at)).faces) {
                if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
                for (var point : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(BoardRoad.distance(allRoads, point.x, point.y) >= BoardRoad.SHOULDER - .01f,
                          "A neighboring road also clears the complete rock/shrub footprint");
                    stones++;
                }
            }
        }
        assertTrue(removed > 0, "The shared ring must reach intermediate hexes in this fixture");
        assertTrue(kept > 1000 && stones > 0, "The surrounding meadow and scattered cover remain populated");
    }

    private static BoardScene triangle(BoardScene.Surface surface) {
        Map<Coords, Integer> roads = new HashMap<>();
        roads.put(AT, 13);
        roads.put(AT.translated(2), 50);
        roads.put(AT.translated(3), 19);
        roads.put(AT.translated(0), 8);
        roads.put(AT.translated(2).translated(1), 16);
        roads.put(AT.translated(3).translated(4), 2);
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              roads.containsKey(c) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, roads.getOrDefault(c, 0), 0, surface));
    }

    private static Set<Coords> intermediate(BoardScene scene) {
        Set<Coords> result = new LinkedHashSet<>();
        for (Coords member : List.of(AT, AT.translated(2), AT.translated(3))) {
            for (int direction = 0; direction < 6; direction++) {
                var tile = scene.tile(member.translated(direction));
                if (tile != null && !BoardRoad.rendered(tile)) { result.add(tile.coords()); }
            }
        }
        assertFalse(result.isEmpty());
        return result;
    }

    private static Set<List<Float>> roots(FloatArray roots) {
        Set<List<Float>> result = new HashSet<>();
        for (int i = 0; i < roots.size; i += 4) { result.add(List.of(roots.get(i), roots.get(i + 1), roots.get(i + 2), roots.get(i + 3))); }
        return result;
    }
}
