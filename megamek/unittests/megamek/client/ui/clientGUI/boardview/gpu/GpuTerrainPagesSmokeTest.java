/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL ownership and culling across page reuse and replacement of one source chunk. */
@Tag("on-demand")
class GpuTerrainPagesSmokeTest {
    @Test
    void pagesPreservePixelsCullingAndSourceOwnership() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain page lifecycle", failure.get()); }
    }

    private record Source(Model owner, Array<Renderable> parts) { }

    private static Source source(int index, float elevation) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        var mesh = builder.part("ground", GL20.GL_TRIANGLES,
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal,
              new Material(ColorAttribute.createDiffuse(Color.YELLOW)));
        float x = index * 240 - 800;
        mesh.rect(new Vector3(x, -120, elevation), new Vector3(x + 180, -120, elevation),
              new Vector3(x + 180, 120, elevation), new Vector3(x, 120, elevation), Vector3.Z);
        Model model = builder.end();
        return new Source(model, GpuTerrainDepth.snapshot(List.of(new ModelInstance(model))));
    }

    private static void check() {
        var camera = new BoardCamera();
        camera.resize(640, 480);
        camera.setIsometric(false);
        camera.orbit(45, 54.73561f);
        camera.camera.zoom = 5;
        camera.center(new Vector3());
        ModelBatch batch = new ModelBatch();
        GpuTerrainPages pages = new GpuTerrainPages(new GpuTerrainBatch(material -> true)::eligible);
        List<Source> owned = new ArrayList<>(), current = new ArrayList<>();
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        try {
            for (int i = 0; i < 8; i++) { current.add(source(i, 0)); }
            owned.addAll(current);
            parity(batch, pages, camera, current, 0b00001110);
            settle(batch, pages, camera, current, -1);
            assertEquals(2, pages.rebuilds());
            byte[] full = parity(batch, pages, camera, current, -1);
            int visible = 0;
            for (int i = 0; i < full.length; i += 4) {
                if (Byte.toUnsignedInt(full[i]) > 100) { visible++; }
            }
            assertTrue(visible > 500, "The parity image must contain actual geometry");

            GL20 originalGl = Gdx.gl20;
            profiler.enable();
            draw(batch, pages, camera, current, -1);
            assertEquals(2, profiler.getDrawCalls(), "Eight chunk draws merge into two spatial pages");
            GpuStageTimings.stopCounting(profiler, originalGl);
            parity(batch, pages, camera, current, 1);
            parity(batch, pages, camera, current, 0);
            parity(batch, pages, camera, current, 0b10101110);
            rawDraws(batch, pages, camera, current, 0b00001110, profiler, 1);
            rawDraws(batch, pages, camera, current, 0b00001010, profiler, 2);
            parity(batch, pages, camera, current, -1);
            assertEquals(2, pages.rebuilds(), "Partial visibility and returning to overview cannot rebuild geometry");

            Material cachedMaterial = new Material(ColorAttribute.createDiffuse(Color.GREEN));
            GpuTerrainPages.Materials mixed = (region, material) -> region < 2 ? cachedMaterial : material;
            GpuTerrainPages.Materials allCached = (region, material) -> cachedMaterial;
            parity(batch, pages, camera, current, -1, mixed);
            rawDraws(batch, pages, camera, current, -1, profiler, 3, mixed);
            rawDraws(batch, pages, camera, current, -1, profiler, 2, allCached);
            rawDraws(batch, pages, camera, current, 0b00001011, profiler, 2, mixed);
            for (Source source : current) {
                assertEquals(Color.YELLOW, source.parts().first().material.get(ColorAttribute.class,
                      ColorAttribute.Diffuse).color, "Selecting a cache material never mutates a source");
            }
            assertEquals(2, pages.rebuilds(), "Cache coverage changes neither buffers nor merge ownership");

            camera.zoom(.1f);
            parity(batch, pages, camera, current, -1);
            rawDraws(batch, pages, camera, current, -1, profiler, 2);
            assertEquals(2, pages.rebuilds(), "Close-range grass density must not disable or rebuild terrain pages");
            camera.zoom(10);

            Source replacement = source(0, 12);
            owned.add(replacement);
            current.set(0, replacement);
            parity(batch, pages, camera, current, -1);
            parity(batch, pages, camera, current, -1, mixed);
            settle(batch, pages, camera, current, -1);
            assertEquals(3, pages.rebuilds(), "Only the edited chunk's page rebuilds");
            Source hiddenReplacement = source(4, 24);
            owned.add(hiddenReplacement);
            current.set(4, hiddenReplacement);
            parity(batch, pages, camera, current, 0b00001111);
            settle(batch, pages, camera, current, 0b00001111);
            assertEquals(3, pages.rebuilds(), "An edited invisible page waits until it is needed");
            // Revisiting part of an edited page draws its new geometry at once (parity), and merges it once settled.
            parity(batch, pages, camera, current, 0b00010000);
            settle(batch, pages, camera, current, 0b00010000);
            assertEquals(4, pages.rebuilds(), "A revisited edited page merges again");
            Source unindexed = source(1, 0);
            owned.add(unindexed);
            unindexed.parts().first().meshPart.mesh.setIndices(new short[0]);
            unindexed.parts().first().meshPart.size = 3;
            current.set(1, unindexed);
            parity(batch, pages, camera, current, -1, mixed);
            byte[] beforeDispose = parity(batch, pages, camera, current, -1);
            pages.dispose();
            pages.setEnabled(false);
            draw(batch, pages, camera, current, -1);
            assertArrayEquals(beforeDispose, ScreenUtils.getFrameBufferPixels(0, 0, 640, 480, false));
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Disposing pages must leave source meshes usable");
        } finally {
            if (profiler.isEnabled()) { profiler.disable(); }
            pages.dispose();
            batch.dispose();
            owned.forEach(value -> value.owner().dispose());
        }
    }

    private static byte[] parity(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible) {
        return parity(batch, pages, camera, sources, visible, null);
    }

    private static byte[] parity(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible, GpuTerrainPages.Materials materials) {
        pages.setEnabled(false);
        draw(batch, pages, camera, sources, visible, materials);
        byte[] original = ScreenUtils.getFrameBufferPixels(0, 0, 640, 480, false);
        pages.setEnabled(true);
        draw(batch, pages, camera, sources, visible, materials);
        assertArrayEquals(original, ScreenUtils.getFrameBufferPixels(0, 0, 640, 480, false));
        return original;
    }

    /** Frames until every page unchanged for GpuMeshPage.SETTLE_FRAMES has merged. */
    private static void settle(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible) {
        for (int frame = 0; frame <= GpuMeshPage.SETTLE_FRAMES; frame++) {
            draw(batch, pages, camera, sources, visible);
        }
    }

    private static void draw(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible) {
        draw(batch, pages, camera, sources, visible, null);
    }

    private static void draw(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible, GpuTerrainPages.Materials materials) {
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(.05f, .05f, .05f, 1, true);
        pages.begin(camera.camera);
        for (int i = 0; i < sources.size(); i++) {
            pages.add(sources.get(i).parts(), i / 4, i, visible < 0 || (visible & (1 << i)) != 0);
        }
        batch.begin(camera.camera);
        // No wall clock: a settled page merges within the frame.
        pages.render(batch, null, Long.MAX_VALUE, materials);
        batch.end();
    }

    private static void rawDraws(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible, GLProfiler profiler, int expected) {
        rawDraws(batch, pages, camera, sources, visible, profiler, expected, null);
    }

    private static void rawDraws(ModelBatch batch, GpuTerrainPages pages, BoardCamera camera,
          List<Source> sources, int visible, GLProfiler profiler, int expected, GpuTerrainPages.Materials materials) {
        parity(batch, pages, camera, sources, visible, materials);
        GL20 originalGl = Gdx.gl20;
        profiler.enable();
        profiler.reset();
        try {
            draw(batch, pages, camera, sources, visible, materials);
            assertEquals(expected, profiler.getDrawCalls(), "Only consecutive visible ranges can share a draw");
        } finally { GpuStageTimings.stopCounting(profiler, originalGl); }
    }
}
