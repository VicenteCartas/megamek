/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.TilesetScenery;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A hex's large doors stand in the wall towards their hexside, join along a straight wall and open the wall there. */
class KitDoorsTest {
    private static final String HANGAR = "buildings/standard/hangar-light";
    private static final double A = KitOutline.A, H = KitOutline.H;

    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    private static ConnectedKit.Kit square() {
        return GpuBuilding.kit(TilesetScenery.footprint(HANGAR, HexAppearance.Footprint.SQUARE));
    }

    @Test
    void aLoneSquareHexsNorthDoorTakesItsWholeNorthWall() {
        var slices = KitDoors.slices(square().doors(), 0, new int[] { 3, 0, 0, 0, 0, 0 }, new int[6], 5);
        assertEquals(1, slices.size());
        var door = slices.getFirst();
        // Along the wall with the building on its left: the north wall runs east to west.
        assertArrayEquals(new double[] { A, A }, door.p(), 1e-6);
        assertArrayEquals(new double[] { -A, A }, door.q(), 1e-6);
        assertEquals(3, door.levels());
        assertTrue(door.cornerStart() && door.cornerEnd(), "Both ends are the wall's corners");
    }

    @Test
    void aColumnsEastDoorsAreTheHalvesOfItsWallAndADoorIsNoHigherThanItsHex() {
        int north = 1, south = 1 << 3;
        var slices = KitDoors.slices(square().doors(), north | south, new int[] { 2, 9, 9, 0, 0, 0 }, new int[6], 4);
        // The north side continues the building: no wall, no door.
        assertEquals(2, slices.size());
        var northEast = slices.get(0);
        var southEast = slices.get(1);
        assertArrayEquals(new double[] { A, 0 }, northEast.p(), 1e-6);
        assertArrayEquals(new double[] { A, H }, northEast.q(), 1e-6);
        assertArrayEquals(new double[] { A, -H }, southEast.p(), 1e-6);
        assertArrayEquals(new double[] { A, 0 }, southEast.q(), 1e-6);
        assertEquals(4, northEast.levels());
        // The wall goes on in the hexes north and south: its ends on the hex's outline are no corners.
        assertFalse(northEast.cornerEnd() || southEast.cornerStart());
    }

    @Test
    void aDoorOpensItsWallAndStandsInIt() {
        var kit = square();
        var placements = ConnectedKit.placements(kit, 0, 2, new int[6], 0, 7);
        var plain = KitDoors.model(kit, 0, placements, List.of(), "plain");
        // The lone hex's south wall, one level high: from (-A, -A) to (A, -A), both ends corners.
        var door = new KitDoors.Cut((float) -A, (float) -A, (float) A, (float) -A, 1, false, false, true, true);
        var doored = KitDoors.model(kit, 0, placements, List.of(door), "doored");
        assertTrue(doored.nodes.first().id.startsWith("doored-storey") && doored.nodes.peek().id.equals("doored-doors"));
        // Mid-height at the wall's middle, through the wall's depth, the kit's wall is gone.
        assertTrue(count(plain, 8) > 0, "The wall stands there without a door");
        assertEquals(0, count(doored, 8), "The door's opening is clear of the kit's wall");
        // The storey above keeps its wall.
        assertEquals(count(plain, 26), count(doored, 26));
    }

    /** How many triangles of the kit's own nodes reach into the middle of the south wall, through its depth, about height {@code z}. */
    private static int count(RigidGlb.Data data, double z) {
        int count = 0;
        for (int index = 0; index < data.nodes.size; index++) {
            var node = data.nodes.get(index);
            if (node.id.endsWith("-doors")) { continue; }
            List<Vector3> points = ConnectedKit.triangles(data, node, new com.badlogic.gdx.math.Matrix4());
            for (int at = 0; at < points.size(); at += 3) {
                Vector3 a = points.get(at), b = points.get(at + 1), c = points.get(at + 2);
                boolean x = Math.min(a.x, Math.min(b.x, c.x)) <= 1 && Math.max(a.x, Math.max(b.x, c.x)) >= -1;
                boolean y = Math.min(a.y, Math.min(b.y, c.y)) <= -A + 3.1 && Math.max(a.y, Math.max(b.y, c.y)) >= -A - .3;
                boolean level = Math.min(a.z, Math.min(b.z, c.z)) <= z + 1 && Math.max(a.z, Math.max(b.z, c.z)) >= z - 1;
                if (x && y && level) { count++; }
            }
        }
        return count;
    }
}
