/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.Matrix4;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.Test;

/** Fixture positions must follow the same transformed, clipped ownership as the visible stadium. */
class GpuAuthoredNightLightsTest {
    @Test void sharedWindowMaterialsControlGeneratedKitsWithoutLightingTheirWalls() {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var data = new RigidGlb.Data();
        RigidGlb.boardMaterial(data, "windows", "structures/buildings/fortress-glass");
        RigidGlb.boardMaterial(data, "wall", "structures/common/cast-concrete");
        assertEquals(java.util.Set.of("windows"), data.nightEmission);
        float[] glow = data.surfaces.get("windows").glow();
        assertTrue(glow[0] > 0 && glow[0] > glow[2], "Shared glass keeps its warm emission");
        assertArrayEquals(new float[3], data.surfaces.get("wall").glow(), "Walls remain unlit");
        var inherited = new RigidGlb.Data(); inherited.inheritMaterials(data);
        assertEquals(data.nightEmission, inherited.nightEmission);
    }

    @Test void rotatingAndMirroringAStretchedBuildingKeepsItsLightReach() {
        for (int angle : new int[] {0, 45, 90, 165}) {
            var frame = new Matrix4().rotate(Vector3.Z, angle).rotate(Vector3.X, 35).scale(-2, 1, .8f);
            assertEquals(2, GpuAuthoredBuilding.lightScale(frame), .0001);
        }
    }

    @Test void transformedFixturesBelongToOneSliceAndClippedFixturesDisappear() {
        String asset = "buildings/authored/civic-arena";
        var object = new BoardDecoration("arena", "prop", asset, "", 0, 0, 90, true, 1.5,
              new BoardDecoration.Placement("absolute", 0.0, null, null), 0, false);
        var anchor = new Coords(4, 4);
        var members = List.of(new Coords(3, 4), anchor, new Coords(5, 4), new Coords(4, 3), new Coords(4, 5));
        var source = RigidGlb.nightLights(TilesetScenery.customBuildingFile(asset));
        assertEquals(8, source.size());
        List<Vector3> actual = new ArrayList<>();
        for (Coords cell : members) {
            var layout = new GpuAuthoredBuilding.Layout(cell, anchor, object, 2, 2, 1, BuildingInteriors.NO, members, 10, 10);
            for (var light : GpuAuthoredBuilding.nightLights(layout)) {
                assertEquals(60, light.size(), .001, "Finite reach scales with the building");
                actual.add(new Vector3(light.position()).add(HexTile.centerX(cell, HexTile.MODEL_WIDTH), HexTile.centerY(cell, HexTile.HEIGHT), 0));
            }
        }
        assertEquals(8, actual.size(), "No repeated lights across sliced hexes");
        for (var light : source) {
            // Mirror X, rotate +90 degrees, scale 1.5 and raise two levels, independently of the frame helper.
            var p = light.position();
            var expected = new Vector3(HexTile.centerX(anchor, HexTile.MODEL_WIDTH) - p.y * 1.5f,
                  HexTile.centerY(anchor, HexTile.HEIGHT) - p.x * 1.5f, p.z * 1.5f + 36);
            assertEquals(1, actual.stream().filter(v -> v.epsilonEquals(expected, .002f)).count());
        }
        var edge = new Coords(0, 0);
        var layout = new GpuAuthoredBuilding.Layout(edge, edge, object, 0, 0, 1, BuildingInteriors.NO, List.of(edge), 1, 1);
        assertTrue(GpuAuthoredBuilding.nightLights(layout).size() < source.size(), "The map edge removes off-board fixtures");
    }
}
