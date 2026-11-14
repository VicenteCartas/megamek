/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuWaterWavesTest {
    @Test
    void lodRetainsTheWetOutlineAndSamplesOnlyTheInterior() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, 2, false, 0, BoardScene.Surface.SAND,
                      List.of(), List.of()));
            }
        }
        var scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        var surface = new BoardSurface(scene, scene.tile(new Coords(2, 2)));
        List<BoardSurface.Face> original = List.copyOf(surface.waterFaces);
        int previous = Integer.MAX_VALUE;
        for (TerrainLod lod : TerrainLod.values()) {
            var faces = GpuWaterWaves.faces(original, lod);
            assertEquals(area(original), area(faces), .01, "LOD may not add water across its shore");
            assertTrue(faces.size() <= previous, "Coarser LOD may not increase geometry");
            assertTrue(faces.size() < 400, "A hex must not explode into thousands of skinny triangles");
            previous = faces.size();
            var vertices = new HashSet<Vector3>();
            for (var face : faces) {
                assertTrue(cross(face) > 0, "Surface triangles must face up");
                vertices.addAll(List.of(face.a(), face.b(), face.c()));
            }
            if (lod != TerrainLod.DISTANT) {
                for (int edge = 0; edge < 6; edge++) {
                    for (Vector3 p : surface.waterBoundary(edge)) {
                        assertTrue(vertices.stream().anyMatch(v -> v.epsilonEquals(p, .0001f)),
                              "Shared boundary sample missing at " + lod + ": " + p);
                    }
                }
            } else { assertTrue(faces.size() <= 6, "Flat distant water needs only its hex corners"); }
        }
        assertEquals(original, surface.waterFaces, "Rendering must not mutate canonical geometry");
    }

    @Test
    void concaveBaysGainInteriorSamplesWithinTheirOutlineAndSlopesKeepTheirGeometry() {
        var a = new Vector3(0, 0, 0); var b = new Vector3(30, 0, 0);
        var c = new Vector3(30, 10, 0); var d = new Vector3(10, 10, 0);
        var e = new Vector3(10, 30, 0); var f = new Vector3(0, 30, 0);
        var faces = List.of(face(a, b, d), face(b, c, d), face(a, d, f), face(d, e, f));
        var resampled = GpuWaterWaves.faces(faces, TerrainLod.FULL);
        assertTrue(resampled.size() > faces.size(), "Open water in a bay needs interior samples to carry waves");
        assertEquals(area(faces), area(resampled), .01, "No triangle may reach across the bay's missing corner");
        var vertices = new HashSet<Vector3>();
        for (var face : resampled) {
            assertTrue(cross(face) > 0, "Surface triangles must face up");
            vertices.addAll(List.of(face.a(), face.b(), face.c()));
            float x = (face.a().x + face.b().x + face.c().x) / 3, y = (face.a().y + face.b().y + face.c().y) / 3;
            assertFalse(x > 10 && y > 10, "A triangle filled the notch outside the bank");
        }
        for (Vector3 corner : List.of(a, b, c, d, e, f)) { assertTrue(vertices.contains(corner), "Outline kept: " + corner); }
        assertSame(faces, GpuWaterWaves.faces(faces, TerrainLod.DISTANT), "Distant bays keep their canonical faces");
        c.z = 5;
        var slope = List.of(face(a, b, c));
        assertSame(slope, GpuWaterWaves.faces(slope, TerrainLod.FULL));
        assertEquals(5, c.z);
    }

    @Test
    void pageAtlasPreservesWorldCoordinatesAcrossOverlappingPadding() {
        var left = field(-4, -3, 8, 9);
        var right = field(1, -5, 7, 8);
        var atlas = GpuWaterShader.Field.atlas(List.of(left, right));
        for (var source : List.of(left, right)) {
            for (int y = 0; y < source.height(); y++) {
                for (int x = 0; x < source.width(); x++) {
                    int target = ((y + source.firstY() - atlas.firstY()) * atlas.width() + x + source.firstX() - atlas.firstX()) * 4;
                    for (int channel = 0; channel < 4; channel++) {
                        assertEquals(source.pixels()[(y * source.width() + x) * 4 + channel], atlas.pixels()[target + channel]);
                    }
                }
            }
        }
        assertNull(atlas.pools(), "A page must not retain terrain sampling graphs");
    }

    @Test
    void fetchStopsAtLandAndAtADifferentPoolLevelButRunsOnPastTheBoardEdge() {
        int width = 25, height = 9, land = Integer.MIN_VALUE;
        int[] levels = new int[width * height];
        for (int y = 0; y < height; y++) { levels[y * width + 12] = land; }
        byte[] fetch = GpuWaterExposure.fetch(levels, width, height, 20, 10);
        // The lattice's border repeats the board's edge hexes: open water there runs on beyond the board.
        assertEquals(255, Byte.toUnsignedInt(fetch[(4 * width) * 4]), "Water at the board edge continues beyond it");
        assertEquals(255, Byte.toUnsignedInt(fetch[(4 * width + 8) * 4]), "The sea's fetch reaches across the lake");
        assertEquals(0, Byte.toUnsignedInt(fetch[(4 * width + 13) * 4]), "Immediately behind the island is sheltered");
        assertTrue(Byte.toUnsignedInt(fetch[(4 * width + 22) * 4]) > Byte.toUnsignedInt(fetch[(4 * width + 16) * 4]));
        for (int y = 0; y < height; y++) { levels[y * width] = land; }
        fetch = GpuWaterExposure.fetch(levels, width, height, 20, 10);
        assertTrue(Byte.toUnsignedInt(fetch[(4 * width + 1) * 4]) < 10, "A coast on the edge shelters its lee");
        for (int y = 0; y < height; y++) { levels[y * width + 12] = 2; }
        fetch = GpuWaterExposure.fetch(levels, width, height, 20, 10);
        assertEquals(0, Byte.toUnsignedInt(fetch[(4 * width + 13) * 4]), "A waterfall does not carry ocean swell between levels");
    }

    @Test
    void aRiverLeavingTheBoardStaysNarrowBeyondIt() {
        int width = 9, height = 12, land = Integer.MIN_VALUE;
        int[] levels = new int[width * height];
        java.util.Arrays.fill(levels, land);
        // One texel wide, through both border rows, as the repeated edge hexes carry it past the board.
        for (int y = 0; y < height; y++) { levels[y * width + 4] = 0; }
        byte[] fetch = GpuWaterExposure.fetch(levels, width, height, 20, 10);
        for (int y : new int[] { 0, 1, height - 2, height - 1 }) {
            int i = (y * width + 4) * 4;
            assertEquals(0, Byte.toUnsignedInt(fetch[i]) + Byte.toUnsignedInt(fetch[i + 1]),
                  "Across the river the banks stay close, at the edge too: no ocean swell in a stream");
            assertEquals(255, Byte.toUnsignedInt(fetch[i + (y < 2 ? 3 : 2)]), "Along it, the river runs on");
        }
    }

    private static GpuWaterShader.Field.Prepared field(int firstX, int firstY, int width, int height) {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int channel = 0; channel < 4; channel++) {
                    pixels[(y * width + x) * 4 + channel] = (byte) ((x + firstX) * 5 + (y + firstY) * 13 + channel * 31);
                }
            }
        }
        return new GpuWaterShader.Field.Prepared(width, height, 4.5f, firstX, firstY, pixels, null);
    }

    private static BoardSurface.Face face(Vector3 a, Vector3 b, Vector3 c) { return new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP); }
    private static double cross(BoardSurface.Face f) { return (f.b().x - f.a().x) * (f.c().y - f.a().y) - (f.b().y - f.a().y) * (f.c().x - f.a().x); }
    private static double area(List<BoardSurface.Face> faces) { return faces.stream().mapToDouble(f -> Math.abs(cross(f)) * .5).sum(); }
}
