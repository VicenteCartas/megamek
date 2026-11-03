/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BuildingVolumesTest {
    @Test void dominantHeightUsesAreaWithTallerTiesAndNotTheTallestVertex() {
        Area hex = BuildingVolumes.hex(new Coords(0, 0));
        double center = HexTile.centerX(new Coords(0, 0), HexTile.MODEL_WIDTH);
        Area left = new Area(hex), right = new Area(hex);
        left.intersect(new Area(new Rectangle2D.Double(-100, -200, center + 100, 400)));
        right.subtract(left);
        assertEquals(BuildingVolumes.area(left), BuildingVolumes.area(right), 1e-5);
        assertEquals(72, BuildingVolumes.dominant(Map.of(36.0, left, 72.0, right), hex));
        Area narrow = new Area(right);
        narrow.intersect(new Area(new Rectangle2D.Double(center + 15, -200, 200, 400)));
        Area lower = new Area(hex); lower.subtract(narrow);
        assertEquals(36, BuildingVolumes.dominant(Map.of(36.0, lower, 72.0, narrow), hex));
    }

    @Test void realHpgUsesItsSteppedVolumesAndNotItsDishOrAerials() {
        var definition = BuildingVolumes.of("buildings/authored/hpg-headquarters");
        assertTrue(definition.present()); assertFalse(definition.modular());
        var solids = definition.assemble(20, 1);
        var roofs = BuildingVolumes.roofs(solids);
        // The marked reference is relative to the level-three plateau, not absolute map elevation.
        assertEquals(Set.of(18.0, 36.0, 54.0), roofs.keySet());
        var bounds = BoardModelBounds.bounds("buildings/authored/hpg-headquarters");
        assertTrue(bounds[5] > 300, "The radar can be tall without creating any extra building floors");
        assertTrue(bounds[3] - bounds[0] > 1050, "The full reference facility spans about eighteen staggered columns");
        double lower = BuildingVolumes.area(BuildingVolumes.section(solids, 9));
        double upper = BuildingVolumes.area(BuildingVolumes.section(solids, 45));
        assertTrue(lower > upper * 1.5);
        assertEquals(0, BuildingVolumes.area(BuildingVolumes.section(solids, 55)), .001,
              "The pedestal, bearing, reflector and aerials have no floors");
    }

    @Test void researchStationRepeatsItsAuthoredVolumesAndKeepsItsOpenCourtyard() {
        var definition = BuildingVolumes.of("buildings/authored/xenobiology-station");
        assertTrue(definition.present()); assertTrue(definition.modular());
        var shortBuilding = definition.assemble(2, 12);
        var tallBuilding = definition.assemble(5, 12);
        assertEquals(36.0, BuildingVolumes.roofs(shortBuilding).keySet().stream().mapToDouble(Double::doubleValue).max().orElseThrow(), .001);
        assertEquals(90.0, BuildingVolumes.roofs(tallBuilding).keySet().stream().mapToDouble(Double::doubleValue).max().orElseThrow(), .001);
        Area floor = BuildingVolumes.section(tallBuilding, 63);
        assertFalse(floor.contains(0, -20), "The courtyard is not filled by a bounding rectangle");
        assertTrue(floor.contains(-46, 0)); assertTrue(floor.contains(46, 0)); assertTrue(floor.contains(0, 30));
    }
}
