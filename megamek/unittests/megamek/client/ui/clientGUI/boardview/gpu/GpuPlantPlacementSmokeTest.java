/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Plant roots follow terrain edits, independently of camera-driven terrain tessellation. */
@Tag("on-demand")
class GpuPlantPlacementSmokeTest {
    private static final Coords AT = new Coords(3, 3);

    @Test
    void cameraCannotMovePlantsButTerrainEditsCan() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setInitialVisible(false);
        configuration.setWindowedMode(1536, 1536);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    for (String asset : List.of("vegetation/tree-broad", "vegetation/cactus-old", "fungus/cup-forked",
                          "fungus/spore-round", "fungus/spore-pear", "fungus/spore-cluster",
                          BoardFeatures.MARS_CORALS.getFirst())) {
                        boolean captured = false;
                        var camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setIsometric(true);
                        List<String> previous = List.of();
                        for (int elevation : new int[] { 1, 3, 1 }) {
                            var scene = scene(asset, elevation);
                            terrain.update(scene);
                            camera.center(BoardGeometry.center(AT, elevation));
                            var lods = new HashSet<String>();
                            List<String> original = null;
                            for (float zoom : new float[] { .08f, 1, 3, 12, 48, .08f }) {
                                camera.camera.zoom = zoom;
                                camera.camera.update();
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                var current = placements(terrain, lods);
                                assertTrue(!current.isEmpty(), "The supported plant must remain present");
                                if (BoardFungus.COVER.contains(asset)) {
                                    assertEquals(BoardFungus.vents(asset).size() + 2, current.size(),
                                          "One plant, its authored emitters and one hex cloud");
                                }
                                if (original == null) { original = current; }
                                assertEquals(original, current, asset + ": camera/LOD must preserve every transform");
                                if (!captured && BoardFungus.COVER.contains(asset) && elevation == 1 && zoom == .08f) {
                                    frame.prepare(terrain, camera, scene);
                                    for (int step = 0; step < 4; step++) {
                                        frame.render(terrain, camera, scene);
                                        GpuReviewFrame.save(new java.io.File(System.getProperty("megamek.gpu.screenshots"),
                                              "fungal-" + asset.substring("fungus/".length()) + "-" + step + ".png"));
                                        terrain.animate(2, List.of());
                                    }
                                    captured = true;
                                }
                            }
                            assertEquals(TerrainLod.values().length, lods.size(), "Exercise every terrain LOD: " + lods);
                            assertNotEquals(previous, original, "A changed terrain height must update root support");
                            previous = original;
                        }
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        assertNull(failure.get(), String.valueOf(failure.get()));
    }

    private static BoardScene scene(String asset, int elevation) {
        var plant = new BoardScene.Feature(asset, 27, 8, 37, 1, BoardFungus.SPORES.contains(asset) ? 1.1f : 2,
              0, BoardScene.FeatureKind.TREE);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                var at = new Coords(x, y);
                tiles.add(new BoardScene.Tile(at, at.equals(AT) ? elevation : 0, -1, false, 0,
                      asset.startsWith("fungus/") ? BoardScene.Surface.FUNGUS : BoardScene.Surface.GRASS,
                      null, null, at.equals(AT) ? List.of(plant) : List.of(),
                      List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static List<String> placements(GpuTerrain terrain, Set<String> lods) throws Exception {
        var result = new ArrayList<String>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                if (!AT.equals(field(prop, "coords")) || !(boolean) field(prop, "cover")) { continue; }
                result.add(java.util.Arrays.toString(((ModelInstance) field(prop, "instance")).transform.val));
                lods.add(String.valueOf(field(chunk, "lod")));
            }
            for (Object emitter : (List<?>) field(chunk, "spores")) { result.add(emitter.toString()); }
        }
        result.sort(String::compareTo);
        return result;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
