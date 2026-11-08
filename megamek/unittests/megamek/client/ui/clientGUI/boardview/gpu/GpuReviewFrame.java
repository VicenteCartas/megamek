/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.utils.Disposable;

/**
 * Review captures drawn the way the board draws a frame (see {@link GpuBattleView}): the terrain through the atmosphere's
 * scene target, fog and composite, then read back from the screen. Sun glare is left out, so the captures show the light
 * alone. Owns its atmosphere; create it on the GL thread.
 */
final class GpuReviewFrame implements Disposable {
    private static final GpuAtmosphere.Options OPTIONS = new GpuAtmosphere.Options(GpuAtmosphere.Options.DEFAULTS.rays(),
          false, GpuClouds.MIN_SHADOW_STRENGTH, GpuClouds.MAX_SHADOW_STRENGTH, 0);
    private final GpuAtmosphere atmosphere = new GpuAtmosphere();
    /** Off unless a review turns it on (setAmbientOcclusion), as on the board's Display page. */
    private final GpuAmbientOcclusion ambientOcclusion = new GpuAmbientOcclusion();
    private boolean occlusion;
    /** Each frame's elapsed seconds; 0, a still capture, unless a review plays frames in time (setFrameSeconds). */
    private float frameSeconds;
    /** The relief's sky occlusion, on as on the board unless a review turns it off (setSkyVisibility). */
    private final GpuSkyVisibility skyVisibility = new GpuSkyVisibility();
    private float skyStrength = 1;

    GpuReviewFrame(BoardAtmosphere.Settings settings) {
        atmosphere.setOptions(OPTIONS);
        configure(settings);
    }

    /** Changes the conditions for the next frames, so a series of captures shares one atmosphere. */
    void configure(BoardAtmosphere.Settings settings) {
        atmosphere.configure(settings);
    }

    BoardAtmosphere.Lighting lighting() {
        return atmosphere.lighting();
    }

    /** Borrow the completed scene depth when a review also draws board labels. */
    Texture depthTexture() {
        return atmosphere.depthTexture();
    }

    /** Borrowed owner for post-composite overlay review; this frame retains its lifetime. */
    GpuAtmosphere atmosphere() { return atmosphere; }

    /** The board's ambient occlusion for the next frames; a switch rebuilds the terrain's programs (settle again). */
    void setAmbientOcclusion(boolean enabled) {
        occlusion = enabled;
    }

    void setFrameSeconds(float seconds) { frameSeconds = seconds; }

    /** The sky occlusion by the board's relief for the next frames (GpuSkyVisibility), 0 to 1. */
    void setSkyVisibility(float strength) { skyStrength = strength; }

    /** Borrowed: the occlusion this frame draws with. */
    GpuAmbientOcclusion ambientOcclusion() { return ambientOcclusion; }

    /** Loading must see the same shader flags as the eventual visible frame. */
    void prepare(GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        ambientOcclusion.configure(terrain.environment(), occlusion);
        // A capture shows the board's volume at once, not the neutral one the board shows while it is searched.
        skyVisibility.update(terrain.environment(), scene, skyStrength);
        skyVisibility.finish();
    }

    /** One frame of the scene from the camera, lit, shadowed and composited as on the board. */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        render(terrain, camera, scene, true);
    }

    /** Hiding the water exposes the bed and bank joins for geometry reviews. */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene, boolean water) {
        render(terrain, camera, scene, water, List.of(), null, 1);
    }

    /** Review standard unit/prop materials in the same lit scene target as the terrain. */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene, List<ModelInstance> objects, ModelBatch batch) {
        render(terrain, camera, scene, true, objects, batch, 1);
    }

    /** Exercise the window's existing render-resolution path without changing camera framing or display size. */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene, float scale) {
        render(terrain, camera, scene, true, List.of(), null, scale);
    }

    private void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene, boolean water,
          List<ModelInstance> objects, ModelBatch batch, float scale) {
        prepare(terrain, camera, scene);
        terrain.renderShadows(camera.camera, objects);
        atmosphere.prepareClouds(terrain, scene, 0);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, scale, frameSeconds);
        terrain.render(camera.camera, null,
              () -> ambientOcclusion.render(camera.camera, terrain, atmosphere.depthTexture()));
        if (!objects.isEmpty()) {
            batch.begin(camera.camera);
            batch.render(objects, terrain.environment());
            batch.end();
        }
        ambientOcclusion.end();
        if (water) { terrain.renderTransparent(camera.camera); }
        atmosphere.renderWeather(camera.camera, scene);
        atmosphere.end(camera.camera, terrain, scene, 0);
    }

    /** The same frame with its stages marked on a native benchmark's timings. */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene, GpuStageTimings timings) {
        render(terrain, camera, scene, timings, 1, () -> terrain.render(camera.camera));
    }

    /**
     * The timed frame at a render resolution {@code scale}, with the opaque stage's drawing given: a measurement marks its
     * own stages in it or reads the scene target after it (the depth pre-pass tests).
     */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene, GpuStageTimings timings, float scale,
          Runnable opaque) {
        timings.stage("light and shadows");
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.renderShadows(camera.camera, List.of());
        timings.stage("clouds");
        atmosphere.prepareClouds(terrain, scene, 0);
        timings.stage("opaque terrain and plants");
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, scale, 0);
        opaque.run();
        timings.stage("transparent");
        terrain.renderTransparent(camera.camera);
        timings.stage("weather");
        atmosphere.renderWeather(camera.camera, scene);
        timings.stage("atmosphere composite");
        atmosphere.end(camera.camera, terrain, scene, 0);
        timings.stage(null);
    }

    /** Saves the screen as an opaque PNG. */
    static void save(File file) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            ByteBuffer pixels = image.getPixels();
            for (int i = 3; i < pixels.limit(); i += 4) { pixels.put(i, (byte) 255); }
            PixmapIO.writePNG(new FileHandle(file), image, -1, true);
        } finally {
            image.dispose();
        }
    }

    @Override
    public void dispose() {
        ambientOcclusion.dispose();
        skyVisibility.dispose();
        atmosphere.dispose();
    }

    static void settle(GpuTerrain terrain, GpuTactical tactical, BoardScene scene, BoardCamera camera) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        double maximum = 0;
        while (true) {
            long start = System.nanoTime();
            boolean changed = terrain.refine(camera.camera);
            double elapsed = (System.nanoTime() - start) / 1e6;
            maximum = Math.max(maximum, elapsed);
            if (changed) { System.out.printf("LOD install %.3f ms%n", elapsed); }
            if (tactical != null && terrain.ready(scene)) { tactical.update(terrain.presentation(scene), changed); }
            if (!changed && !terrain.busy() && terrain.ready(scene)) {
                System.out.printf("LOD maximum render-thread handoff %.3f ms%n", maximum);
                return;
            }
            assertTrue(System.nanoTime() < deadline, () -> "Visible terrain detail must settle: " + terrain.buildDetails());
            Thread.sleep(5);
        }
    }
}
