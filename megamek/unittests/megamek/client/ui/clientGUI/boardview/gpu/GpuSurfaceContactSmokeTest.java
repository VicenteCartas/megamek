/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Render every ordered material pair and the road/soil/cliff junction through the production LOD path. */
@Tag("on-demand")
class GpuSurfaceContactSmokeTest {
    @Test
    void contactsCompileRenderAndRefineAcrossAllFamilies() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1024, 768);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var output = new File(System.getProperty("megamek.gpu.screenshots"), "surface-contacts");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(1024, 768);
                    camera.setIsometric(true);
                    for (int upper = 0; upper < BoardSurfaceBlend.FAMILIES; upper++) {
                        int top = upper;
                        for (int lower = 0; lower < BoardSurfaceBlend.FAMILIES; lower++) {
                            int bottom = lower;
                            for (int rise : new int[] { 0, 4 }) {
                                var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                                      c.getY() < 4 ? top : bottom, c.getY() < 4 ? rise : 0));
                                capture(scene, "pair-" + upper + "-" + lower + "-" + rise, .20f,
                                      terrain, frame, camera, output);
                            }
                        }
                    }
                    for (var family : BoardScene.Surface.values()) {
                        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                              c.getX() == 4 && c.getY() < 4 ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                              c.getX() == 4 && c.getY() < 4 ? 9 : 0,
                              c.getY() >= 4 ? 0 : c.getX() < 4 || c.getY() < 2 ? 2 : 3, family));
                        var tiers = EnumSet.noneOf(TerrainLod.class);
                        for (float zoom : new float[] { .16f, 2f, 6f, 30f }) {
                            tiers.add(capture(scene, family + "-road-" + zoom, zoom, terrain, frame, camera, output));
                        }
                        assertEquals(EnumSet.allOf(TerrainLod.class), tiers, family + " must render every installed tier");
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Surface contact matrix", failure.get()); }
    }

    private static TerrainLod capture(BoardScene scene, String name, float zoom, GpuTerrain terrain,
          GpuReviewFrame frame, BoardCamera camera, File output) throws Exception {
        camera.camera.zoom = zoom;
        camera.center(BoardGeometry.center(new Coords(4, 3), 1));
        terrain.update(scene, camera.camera);
        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
        assertTrue(terrain.ready(scene));
        terrain.animate(.5f, List.of());
        frame.render(terrain, camera, scene);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), name);
        GpuReviewFrame.save(new File(output, name + ".png"));
        var chunks = GpuTerrain.class.getDeclaredField("chunks");
        chunks.setAccessible(true);
        var chunk = ((List<?>) chunks.get(terrain)).getFirst();
        var detail = chunk.getClass().getDeclaredField("lod");
        detail.setAccessible(true);
        return (TerrainLod) detail.get(chunk);
    }
}
