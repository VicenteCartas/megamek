/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every ordered surface pair, including within-family roles, constructed edges and volcanic banks. */
class BoardSurfaceContactTest {
    @Test
    void lowBridgeBanksKeepTheirClearanceAcrossHexesAndCachedWater() throws Exception {
        var scene = BoardScenes.installedScene("AGoAC Maps/16x17 Grassland 2 (Low Bridges).board");
        for (var at : List.of(new Coords(6, 7), new Coords(6, 8))) {
            var surface = new BoardSurface(scene, scene.tile(at));
            var cached = surface.waterGeometry().surface(scene);
            for (var bank : List.of(surface, cached)) {
                assertTrue(bank.fixedTessellation(new Vector3(443.5398f, -576, -1)),
                      "Both banks preserve the reported bridge contact");
                assertTrue(bank.fixedTessellation(new Vector3(391.23663f, -576, -1)),
                      "The shared shore edge stays closed despite its different shading normals");
                assertTrue(bank.fixedTessellation(new Vector3(415.625f, -612, -18)),
                      "Submerged bed fans keep the same support as their clipped neighbours");
            }
        }
        var bank = new BoardSurface(scene, scene.tile(6, 7));
        assertFalse(bank.fixedTessellation(new Vector3(390.24344f, -569.46924f, -.82514054f)),
              "A bank above its water, away from rigid contacts, retains its curvature");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 2, 5 })
    void exposedPlateausMeetTheirCliffAtTheActualSculptedRim(int family) {
        var at = BoardScenes.CENTER;
        for (int rise : new int[] { 1, 2, 3, 5 }) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, family,
                  c.equals(at) ? rise : 0));
            for (var lod : TerrainLod.values()) {
                var surface = new BoardSurface(scene, scene.tile(at), lod);
                var rim = surface.walls(scene, BoardGeometry.floor(scene)).stream()
                      .flatMap(w -> List.of(w.a(), w.b(), w.c()).stream())
                      .filter(p -> Math.abs(p.z - rise * BoardGeometry.level()) < .001f).toList();
                int compared = 0;
                for (var face : surface.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    for (var p : List.of(face.a(), face.b(), face.c())) {
                        if (rim.stream().noneMatch(r -> r.epsilonEquals(p, .001f))) { continue; }
                        assertEquals(0, surface.relief.shade(p).rim(), .001f,
                              "Ground cover must meet the emitted cliff rim: " + family + "/" + rise + "/" + lod);
                        compared++;
                    }
                }
                assertTrue(compared > 0, "Compare shared top/cliff vertices at every detail level");
            }
        }
    }

    @Test
    void minesCliffDebrisContinuesOntoTheReceivingGround() {
        var scene = BoardScenes.scene(new File("data/boards/Deserts/16x17 Mines 1.board"));
        int compared = 0;
        for (Coords at : List.of(new Coords(12, 3), new Coords(12, 4))) {
            var high = new BoardSurface(scene, scene.tile(at));
            var walls = high.walls(scene, BoardGeometry.floor(scene));
            for (int direction = 0; direction < 6; direction++) {
                var tile = scene.tile(at.translated(direction));
                if (tile == null || tile.elevation() != -1) { continue; }
                var low = new BoardSurface(scene, tile);
                for (var face : low.groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                    for (var point : List.of(face.a(), face.b(), face.c())) {
                        boolean shared = walls.stream().flatMap(w -> List.of(w.a(), w.b(), w.c()).stream())
                              .anyMatch(p -> p.epsilonEquals(point, .001f));
                        if (!shared) { continue; }
                        assertEquals(0, low.relief.shade(point).foot(), .001f,
                              "Debris must start at the actual shared cliff foot: " + tile.coords() + " " + point);
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 20, "Compare the real emitted cliff-to-ground seam");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 5 })
    void soilAndRockUseTheSameMaterialInputsOnBothSidesOfACliffCorner(int family) {
        var at = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, family,
              c.equals(at) ? 3 : c.equals(at.translated(0)) ? 1 : 0));
        for (var lod : TerrainLod.values()) {
            var surface = new BoardSurface(scene, scene.tile(at), lod);
            Map<String, BoardRelief.Shade> shades = new HashMap<>();
            Map<String, Integer> edges = new HashMap<>();
            int compared = 0;
            for (var face : surface.walls(scene, BoardGeometry.floor(scene))) {
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    String key = Math.round(p.x * 1000) + ":" + Math.round(p.y * 1000) + ":" + Math.round(p.z * 1000);
                    var shade = surface.relief.shade(p);
                    if (shades.containsKey(key) && edges.get(key) != face.landEdge()) {
                        var other = shades.get(key);
                        assertEquals(other.level(), shade.level(), .0001f, "Corner rock coverage: " + lod);
                        assertEquals(other.rim(), shade.rim(), .0001f, "Corner deposit distance: " + lod);
                        assertEquals(other.foot(), shade.foot(), .0001f, "Corner soil mantle depth: " + lod);
                        assertEquals(other.tint(), shade.tint(), .0001f, "Corner rock tint: " + lod);
                        compared++;
                    }
                    shades.put(key, shade);
                    edges.put(key, face.landEdge());
                }
            }
            assertTrue(compared > 0, "Actual shared mesh vertices must be compared at " + lod);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7 })
    void receivingGroundSharesNaturalFootCoverageAndKeepsConstructedEdges(int upper) {
        var at = BoardScenes.CENTER;
        for (int lower = 0; lower < BoardSurfaceBlend.FAMILIES; lower++) {
            int receiving = lower;
            for (int rise : new int[] { 1, 2, 3, 4 }) {
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                      c.equals(at) ? upper : receiving, c.equals(at) ? rise : 0));
                var high = scene.tile(at);
                // Lava melts snow and burns turf beside it: the upper cover may show as what it leaves.
                int melted = lower >= BoardSurfaceBlend.CRUST ? BoardSurfaceBlend.melted(upper) : upper;
                for (int direction = 0; direction < 6; direction++) {
                    var low = scene.tile(at.translated(direction));
                    var foot = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(low.coords(), 0), .5f);
                    var cover = BoardSurfaceBlend.sampleCliff(scene, high, foot.x, foot.y, foot.z);
                    var receiver = BoardSurfaceBlend.sample(scene, low, foot.x, foot.y, foot.z);
                    boolean slab = lower == BoardScene.Surface.CONCRETE.ordinal()
                          || upper == BoardScene.Surface.CONCRETE.ordinal();
                    if (slab) {
                        assertEquals(BoardSurfaceBlend.solid(upper), cover, "The slab keeps its constructed edge");
                        assertEquals(BoardSurfaceBlend.solid(lower), receiver);
                    } else {
                        assertEquals(cover, receiver,
                              upper + " -> " + lower + " rise " + rise + " direction " + direction);
                        // Natural contacts meander: their mixed portion need not lie exactly on the lattice edge.
                        var across = BoardGeometry.center(low.coords(), 0).sub(BoardGeometry.center(at, 0)).nor();
                        boolean mixed = false;
                        for (int sample = -4; sample <= 4; sample++) {
                            var p = foot.cpy().mulAdd(across, sample * BoardRelief.metres(BoardSurfaceBlend.WIDTH_METRES) / 4);
                            var near = BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z);
                            float shown = near.weight(upper) + (melted == upper ? 0 : near.weight(melted));
                            mixed |= shown > .05f && near.weight(lower) > .05f;
                        }
                        assertTrue(mixed, "A natural contact must contain a blend of both materials");
                    }
                    float sum = 0;
                    for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
                        float weight = cover.weight(family);
                        assertTrue(Float.isFinite(weight) && weight >= 0 && weight <= 1);
                        if (family != upper && family != lower && family != melted) { assertEquals(0, weight); }
                        sum += weight;
                    }
                    assertEquals(1, sum, .00001f);
                    foot.z += BoardRelief.metres(BoardSurfaceBlend.FOOT_METRES + .1f);
                    var above = BoardSurfaceBlend.sampleCliff(scene, high, foot.x, foot.y, foot.z);
                    assertEquals(0, lower == upper ? 0 : above.weight(lower), "Receiving terrain stays near the foot");
                    assertEquals(1, above.weight(upper) + (melted == upper ? 0 : above.weight(melted)), .00001f);
                }
            }
        }
    }
}
