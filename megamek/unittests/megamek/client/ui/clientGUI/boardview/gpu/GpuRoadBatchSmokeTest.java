/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Compare shared road draws with the same tile ranges submitted separately, including reused atlas slots. */
@Tag("on-demand")
class GpuRoadBatchSmokeTest {
    @Test
    void batchingPreservesRoadPixelsAndReducesActualDrawsAfterAnEdit() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                var lod = TerrainLod.tuning();
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, BoardAtmosphere.Effects.NONE);
                var frame = new GpuReviewFrame(weather);
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                GL20 raw = Gdx.gl20;
                try {
                    TerrainLod.tune(new TerrainLod.Tuning(1, 1));
                    terrain.setGrass(false);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(960, 720);
                    for (boolean edited : new boolean[] { false, true, false }) {
                        BoardScene scene = GpuRoadSmokeTest.roads(edited);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        List<Overlay> overlays = splitRoads(terrain);
                        for (boolean perspective : new boolean[] { false, true }) {
                            camera.setPerspective(perspective);
                            for (float tilt : new float[] { 0, 55, 78 }) {
                                camera.setIsometric(false);
                                camera.orbit(45 - camera.azimuth(), tilt);
                                camera.camera.zoom = .55f;
                                camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                                // Warm the programs and lazy uploads before taking either image/count.
                                frame.render(terrain, camera, scene);
                                profiler.enable(); profiler.reset();
                                frame.render(terrain, camera, scene);
                                int batched = profiler.getDrawCalls();
                                GpuStageTimings.stopCounting(profiler, raw);
                                byte[] before = ScreenUtils.getFrameBufferPixels(false);
                                overlays.forEach(o -> o.install(o.separate()));
                                int separate;
                                byte[] after;
                                try {
                                    profiler.enable(); profiler.reset();
                                    frame.render(terrain, camera, scene);
                                    separate = profiler.getDrawCalls();
                                    GpuStageTimings.stopCounting(profiler, raw);
                                    after = ScreenUtils.getFrameBufferPixels(false);
                                } finally { overlays.forEach(o -> o.install(o.batched())); }
                                int changed = 0, maximum = 0;
                                for (int i = 0; i < before.length; i += 4) {
                                    int delta = 0;
                                    for (int c = 0; c < 3; c++) {
                                        delta = Math.max(delta, Math.abs(Byte.toUnsignedInt(before[i + c])
                                              - Byte.toUnsignedInt(after[i + c])));
                                    }
                                    maximum = Math.max(maximum, delta);
                                    if (delta > 2) { changed++; }
                                }
                                System.out.printf("Road batches edited=%s perspective=%s tilt=%.0f draws=%d/%d pixels=%d max=%d%n",
                                      edited, perspective, tilt, batched, separate, changed, maximum);
                                // Reordered transparent joins can differ at a handful of edge pixels in perspective.
                                assertTrue(changed <= 32, "Batching must preserve road coverage, coats and atlas slots");
                                assertTrue(batched < separate, "Road batching must reduce actual GL draws");
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    if (profiler.isEnabled()) { GpuStageTimings.stopCounting(profiler, raw); }
                    terrain.dispose(); frame.dispose(); TerrainLod.tune(lod); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road batch parity", failure.get()); }
    }

    private record Overlay(List<ModelInstance> target, List<ModelInstance> batched, List<ModelInstance> separate) {
        void install(List<ModelInstance> instances) { target.clear(); target.addAll(instances); }
    }

    @SuppressWarnings("unchecked")
    private static List<Overlay> splitRoads(GpuTerrain terrain) throws ReflectiveOperationException {
        List<Overlay> result = new ArrayList<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            List<ModelInstance> target = (List<ModelInstance>) GpuTestSupport.field(chunk, "overlays");
            List<ModelInstance> batched = List.copyOf(target);
            Array<Renderable> parts = new Array<>();
            for (Renderable part : GpuTerrainDepth.snapshot(target)) {
                if (!part.material.has(GpuRoads.Mask.TYPE)) { parts.add(part); }
            }
            for (Object tile : ((java.util.Map<?, ?>) GpuTestSupport.field(chunk, "tileMeshes")).values()) {
                for (Object range : (List<?>) GpuTestSupport.field(tile, "ranges")) {
                    Material material = (Material) GpuTestSupport.field(range, "material");
                    if (!material.has(GpuRoads.Mask.TYPE)) { continue; }
                    Renderable part = new Renderable();
                    part.material = material;
                    part.meshPart.set("road-range", (Mesh) GpuTestSupport.field(range, "mesh"), (int) GpuTestSupport.field(range, "offset"),
                          (int) GpuTestSupport.field(range, "count"), GL20.GL_TRIANGLES);
                    part.meshPart.update();
                    parts.add(part);
                }
            }
            // Borrow existing meshes; this reference owns no GL resources and is never disposed as a model.
            ModelInstance reference = new ModelInstance(new Model()) {
                @Override
                public void getRenderables(Array<Renderable> output, Pool<Renderable> pool) {
                    for (Renderable part : parts) { output.add(pool.obtain().set(part)); }
                }
            };
            result.add(new Overlay(target, batched, List.of(reference)));
        }
        return result;
    }
}
