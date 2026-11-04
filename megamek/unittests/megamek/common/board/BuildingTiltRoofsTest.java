/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.BuildingVolumes.Point;
import megamek.common.board.BuildingVolumes.Solid;
import megamek.common.board.BuildingVolumes.Triangle;
import org.junit.jupiter.api.Test;

class BuildingTiltRoofsTest {
    @Test void slopingUpperFaceHasExactProjectedBandsAndHigherSolidsOccludeIt() {
        var ramp = quad(new Point(0, 0, 0), new Point(108, 0, 108), new Point(108, 72, 108), new Point(0, 72, 0));
        var roofs = BuildingVolumes.roofs(List.of(ramp));
        assertEquals(Set.of(18.0, 36.0, 54.0, 72.0, 90.0, 108.0), roofs.keySet());
        for (double z : roofs.keySet()) {
            assertEquals(18 * 72, BuildingVolumes.area(roofs.get(z)), 1e-6);
            assertTrue(roofs.get(z).contains(z - 9, 36));
            assertFalse(roofs.get(z).contains(z + 1, 36));
        }
        var higher = quad(new Point(18, 0, 144), new Point(54, 0, 144), new Point(54, 72, 144), new Point(18, 72, 144));
        var combined = BuildingVolumes.roofs(List.of(ramp, higher));
        assertTrue(combined.get(36.0).isEmpty()); assertTrue(combined.get(54.0).isEmpty());
        assertEquals(36 * 72, BuildingVolumes.area(combined.get(144.0)), 1e-6);
        Area footprint = new Area(); combined.values().forEach(footprint::add);
        assertEquals(108 * 72, BuildingVolumes.area(footprint), 1e-6);
        assertEquals(144, BuildingVolumes.dominant(combined, new Area(new Rectangle2D.Double(18, 0, 36, 72))));
    }

    @Test void formerWallsBecomeRoofsAndDownwardFacesNeverDo() {
        // After a quarter-turn around Y, a formerly vertical west wall is horizontal and faces upwards.
        var wall = quad(new Point(0, 0, 0), new Point(0, 0, 72), new Point(0, 36, 72), new Point(0, 36, 0));
        var turned = new Solid("", wall.triangles().stream().map(t -> new Triangle(turn(t.a()), turn(t.b()), turn(t.c()))).toList());
        var roofs = BuildingVolumes.roofs(List.of(turned));
        assertEquals(Set.of(18.0), roofs.keySet());
        assertEquals(72 * 36, BuildingVolumes.area(roofs.get(18.0)), 1e-6);
        var underside = new Solid("", turned.triangles().stream().map(t -> new Triangle(t.a(), t.c(), t.b())).toList());
        assertTrue(BuildingVolumes.roofs(List.of(underside)).isEmpty());
    }

    @Test void fullTurnsPreserveFloorsButTiltCannotOverrideClosedInteriors() {
        var upright = object(360, -720, 0);
        assertFalse(BuildingVolumes.tilted(upright));
        assertEquals(BuildingInteriors.YES, BuildingInteriors.forAuthored(upright, BuildingInteriors.YES));
        for (var tilted : List.of(object(45, 0, 0), object(0, -45, 0), object(90, 90, 0), object(180, 0, 0))) {
            assertTrue(BuildingVolumes.tilted(tilted));
            assertEquals(BuildingInteriors.NO_FLOORS, BuildingInteriors.forAuthored(tilted, BuildingInteriors.YES));
            assertEquals(BuildingInteriors.NO_FLOORS, BuildingInteriors.forAuthored(tilted, BuildingInteriors.DEFAULT));
            assertEquals(BuildingInteriors.NO, BuildingInteriors.forAuthored(tilted, BuildingInteriors.NO));
        }
    }

    @Test void buriedTiltedVolumesDoNotCreatePhantomBuildingHexes() {
        var anchor = new Coords(8, 8);
        assertFalse(BuildingVolumes.coverage(anchor, object(45, -30, 2), 16, 16, at -> new Hex(0)).isEmpty());
        assertTrue(BuildingVolumes.coverage(anchor, object(45, -30, -20), 16, 16, at -> new Hex(0)).isEmpty());
    }

    private static BoardDecoration object(double x, double y, double level) {
        return new BoardDecoration("tilt", "prop", "buildings/debug/debug-authored", null, 0, 0, 0,
              false, 2, BoardDecoration.Placement.absolute(level), 0, false, x, y);
    }

    private static Point turn(Point p) { return new Point(p.z(), p.y(), 18 - p.x()); }

    private static Solid quad(Point a, Point b, Point c, Point d) {
        return new Solid("", List.of(new Triangle(a, b, c), new Triangle(a, c, d)));
    }
}
