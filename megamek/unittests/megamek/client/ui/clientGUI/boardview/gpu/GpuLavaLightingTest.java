/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/**
 * The local light list as lava-lighting.glsl reads it: each cell lists every emitter whose reach touches its square, a
 * point as two texels and a segment (a beam) as four, so a long beam is listed all along its length.
 */
class GpuLavaLightingTest {
    private static final int SAMPLES = 17;

    @Test void buildingLightsFadeWithoutDependingOnTerrainModeOrReuploadingUnchangedSources() throws Exception {
        var lights = new GpuLavaLighting(null);
        var fixture = new GpuLavaLighting.Source(0, new Vector3(0, 0, 27), 1.8f, new Vector3(.8f, .9f, 1), 40);
        lights.setBuildings(List.of(fixture));
        var tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.ROCK, null,
              null, List.of(), List.of(), BoardLiquid.NONE);
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
        lights.update(scene, false, 0);
        assertEquals(List.of(), GpuMixedUnitBenchmarkSmokeTest.field(lights, "binned"), "Daylight switches floodlights off");
        lights.update(scene, false, .5f);
        var binned = (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(lights, "binned");
        assertEquals(1, binned.size(), "Classic mode retains building illumination");
        assertEquals(.9f, ((GpuLavaLighting.Source) binned.getFirst()).power(), .0001f);
        Object revision = GpuMixedUnitBenchmarkSmokeTest.field(lights, "revision");
        lights.setBuildings(List.of(fixture)); lights.update(scene, false, .5f);
        assertEquals(revision, GpuMixedUnitBenchmarkSmokeTest.field(lights, "revision"), "An idle stadium does not rebin/reupload lights");
        lights.setBuildings(List.of()); lights.update(scene, false, 1);
        assertEquals(List.of(), GpuMixedUnitBenchmarkSmokeTest.field(lights, "binned"), "Removing the geometry removes its illumination");
    }

    @Test
    void cellsListEveryEmitterTheirSquareReachesAndSegmentsAllAlongTheirLength() throws Exception {
        float hex = BoardGeometry.width(), size = hex * .35f;
        var start = new Vector3(0, 0, 10);
        var end = new Vector3(9.4f * hex, 3.3f * hex, 14);
        var beam = new GpuLavaLighting.Source(-1, start, .45f, new Vector3(2, .1f, .05f), size, end, .65f);
        var point = new GpuLavaLighting.Source(-1, new Vector3(4 * hex, -2 * hex, 5), .8f, new Vector3(1, 1, 1), size);
        var lights = new GpuLavaLighting(null);
        lights.setEffects(List.of(beam, point));
        var tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.ROCK, null,
              null, List.of(), List.of(), BoardLiquid.NONE);
        lights.update(new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of()));

        float[] list = (float[]) GpuMixedUnitBenchmarkSmokeTest.field(lights, "list");
        int columns = (int) GpuMixedUnitBenchmarkSmokeTest.field(lights, "columns");
        int rows = (int) GpuMixedUnitBenchmarkSmokeTest.field(lights, "rows");
        float originX = (float) GpuMixedUnitBenchmarkSmokeTest.field(lights, "originX");
        float originY = (float) GpuMixedUnitBenchmarkSmokeTest.field(lights, "originY");
        float cell = 1 / (float) GpuMixedUnitBenchmarkSmokeTest.field(lights, "cellsPerUnit");
        float reach = 2 * size, spacing = cell / (SAMPLES - 1);
        int beamCells = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int header = (row * columns + column) * 4, first = (int) list[header], last = (int) list[header + 1];
                assertEquals(0, first % 2, "Entries start on an even texel, so a pair shares a row");
                boolean listsBeam = false, listsPoint = false;
                for (int at = first; at < last; ) {
                    int texel = at * 4;
                    if (list[texel + 3] < 0) {
                        assertEquals(-.45f, list[texel + 3], "A segment flags itself by its negated power");
                        assertEquals(start.x, list[texel]);
                        assertEquals(size, list[texel + 7]);
                        assertEquals(end.x, list[texel + 8]);
                        assertEquals(end.y, list[texel + 9]);
                        assertEquals(end.z, list[texel + 10]);
                        assertEquals(.65f / size, list[texel + 11], 1e-7, "Its tube radius, in sizes");
                        listsBeam = true;
                        at += 4;
                    } else {
                        assertEquals(.8f, list[texel + 3], "A point keeps its two texels and its power");
                        listsPoint = true;
                        at += 2;
                    }
                }
                float left = originX + column * cell, bottom = originY + row * cell;
                float toBeam = sampled(left, bottom, cell, start.x, start.y, end.x, end.y);
                float toPoint = sampled(left, bottom, cell, point.position().x, point.position().y,
                      point.position().x, point.position().y);
                assertTrue(listsBeam || toBeam > reach, "A cell the beam reaches lists it");
                assertTrue(listsPoint || toPoint > reach, "A cell the point reaches lists it");
                // Sampling overestimates the distance to the square by at most half a sample diagonal.
                assertTrue(!listsBeam || toBeam <= reach * 1.001f + spacing, "Only cells the beam reaches list it");
                assertTrue(!listsPoint || toPoint <= reach * 1.001f + spacing, "Only cells the point reaches list it");
                if (listsBeam) { beamCells++; }
            }
        }
        // The beam crosses ten hexes: its capsule covers far more cells than the two at its ends.
        assertTrue(beamCells >= 20, "The beam is listed all along its length: " + beamCells + " cells");
    }

    /** The least distance on the board plane from a sampled cell square to a segment. */
    private static float sampled(float left, float bottom, float cell, float x, float y, float endX, float endY) {
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            for (int j = 0; j < SAMPLES; j++) {
                float px = left + cell * i / (SAMPLES - 1), py = bottom + cell * j / (SAMPLES - 1);
                float alongX = endX - x, alongY = endY - y, length2 = alongX * alongX + alongY * alongY;
                float t = length2 == 0 ? 0
                      : Math.max(0, Math.min(1, ((px - x) * alongX + (py - y) * alongY) / length2));
                float dx = px - x - t * alongX, dy = py - y - t * alongY;
                nearest = Math.min(nearest, (float) Math.sqrt(dx * dx + dy * dy));
            }
        }
        return nearest;
    }
}
