/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BuildingVolumesTest {
    @Test void debugBuildingHasTwoFixedHeightsAndPreservesBothNotches() {
        var definition = BuildingVolumes.of("buildings/debug/debug-authored");
        assertTrue(definition.present()); assertFalse(definition.modular());
        assertEquals(2, definition.solids().size());
        var solids = definition.assemble(9, 12);
        assertEquals(Set.of(36.0, 72.0), BuildingVolumes.roofs(solids).keySet(), "Fixed wings are not stacked modules");
        for (double z : new double[] {9, 27, 45, 63}) {
            var floor = BuildingVolumes.section(solids, z);
            assertEquals(z < 36, floor.contains(0, 0));
            assertTrue(floor.contains(68, 29));
            assertFalse(floor.contains(-20, 5), "The low wing's side notch stays open");
            assertFalse(floor.contains(59, 55), "The tower's end notch stays open");
        }
        assertEquals(0, BuildingVolumes.area(BuildingVolumes.section(solids, 72.5)), .001,
              "The roof slab and height label do not create another gameplay level");
    }

    @Test void areaLimitUsesTheWholeStructuralProjectionForTiltedSourceData() {
        var tilted = new BoardDecoration("tilted", "prop", "buildings/authored/civic-church", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.surface("ground", "top", 0), 0, false, 17, 23);
        assertEquals(1, BuildingVolumes.onBoardFraction(new Coords(8, 8), tilted, 16, 16), 1e-8);
    }

    @Test void boardPerimeterMatchesTheUnionOfItsHexesIncludingNarrowBoardsAndStaggeredRecesses() {
        for (int width : new int[] {1, 2, 3, 8}) {
            for (int height : new int[] {1, 2, 7}) {
                Area cells = new Area();
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++) { cells.add(BuildingVolumes.hex(new Coords(x, y))); }
                }
                var outline = BuildingVolumes.boardOutline(width, height);
                assertEquals(outline.size(), Set.copyOf(outline).size());
                cells.exclusiveOr(BuildingVolumes.polygon(outline));
                assertEquals(0, BuildingVolumes.area(cells), 1e-7, width + " x " + height);
            }
        }
    }

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
        Area first = BuildingVolumes.section(solids, 9);
        Area second = BuildingVolumes.section(solids, 27);
        Area third = BuildingVolumes.section(solids, 45);
        // Exposed one-level terraces in the registered source map. The last
        // point is inside the partially covered 0802 hex, just north of its center.
        for (double[] point : new double[][] {{-561.09375, 108}, {-561.09375, -108},
              {-561.09375, -174}, {124.6875, 72}, {249.375, 72}}) {
            assertTrue(first.contains(point[0], point[1]), "The source terrace is present");
            assertFalse(second.contains(point[0], point[1]), "A terrace remains one level high");
        }
        // Opposing circular service bays occupy 1014/1214/1414 and 1002/1202/1402.
        for (double x : new double[] {-436.40625, -311.71875, -187.03125}) {
            for (double y : new double[] {-180, 180}) {
                assertTrue(second.contains(x, y), "The service bay follows the source hex lattice");
                assertFalse(third.contains(x, y), "The service bay is two levels high");
            }
        }
    }

    @Test void repeatedFloorAssemblyKeepsAnOpenCourtyard() {
        // Isolated module fixture using the arena's closed ring footprint.
        var solids = new ArrayList<BuildingVolumes.Solid>();
        for (String role : new String[] {"base0", "floor0"}) {
            for (var solid : BuildingVolumes.of("buildings/authored/civic-arena").solids()) {
                solids.add(new BuildingVolumes.Solid(role, solid.triangles()));
            }
        }
        var definition = new BuildingVolumes.Definition(Map.of("base0", 0.0, "floor0", 0.0, "roof0", 0.0), solids);
        assertTrue(definition.present()); assertTrue(definition.modular());
        var shortBuilding = definition.assemble(2, 12);
        var tallBuilding = definition.assemble(5, 12);
        assertEquals(36.0, BuildingVolumes.roofs(shortBuilding).keySet().stream().mapToDouble(Double::doubleValue).max().orElseThrow(), .001);
        assertEquals(90.0, BuildingVolumes.roofs(tallBuilding).keySet().stream().mapToDouble(Double::doubleValue).max().orElseThrow(), .001);
        Area floor = BuildingVolumes.section(tallBuilding, 63);
        assertFalse(floor.contains(0, 0), "The courtyard is not filled by a bounding rectangle");
        assertTrue(floor.contains(-60, 0)); assertTrue(floor.contains(60, 0)); assertTrue(floor.contains(0, 90));
    }

    @Test void civicBuildingsRespectTheirSmallFootprintsAndSeparatePropsFromFloors() {
        var church = BuildingVolumes.of("buildings/authored/civic-church");
        assertTrue(church.present()); assertFalse(church.modular());
        var solids = church.assemble(8, 0);
        assertEquals(Set.of(18.0, 36.0, 54.0), BuildingVolumes.roofs(solids).keySet());
        assertTrue(BuildingVolumes.section(solids, 45).contains(0, -15));
        assertFalse(BuildingVolumes.section(solids, 45).contains(0, 50));
        assertEquals(0, BuildingVolumes.area(BuildingVolumes.section(solids, 55)), .001);
        var churchBounds = BoardModelBounds.bounds("buildings/authored/civic-church");
        assertTrue(churchBounds[3] - churchBounds[0] <= HexTile.MODEL_WIDTH);
        assertTrue(churchBounds[4] - churchBounds[1] <= 2 * HexTile.HEIGHT);
        var arena = BuildingVolumes.of("buildings/authored/civic-arena");
        assertTrue(arena.present()); assertFalse(arena.modular());
        var ring = arena.assemble(8, 0);
        assertEquals(Set.of(18.0), BuildingVolumes.roofs(ring).keySet());
        assertFalse(BuildingVolumes.section(ring, 9).contains(0, 0), "The field has no building floors");
        assertTrue(BuildingVolumes.section(ring, 9).contains(60, 0));
        assertEquals(0, BuildingVolumes.area(BuildingVolumes.section(ring, 19)), .001);
        var arenaBounds = BoardModelBounds.bounds("buildings/authored/civic-arena");
        assertTrue(arenaBounds[3] - arenaBounds[0] <= 2 * HexTile.MODEL_WIDTH);
        assertTrue(arenaBounds[4] - arenaBounds[1] < 3.2 * HexTile.HEIGHT);
    }
}
