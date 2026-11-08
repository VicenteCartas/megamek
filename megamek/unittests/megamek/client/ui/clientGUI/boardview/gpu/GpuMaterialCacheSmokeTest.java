/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/** Live diagnostic counts follow actual GL allocation, incremental fills, invalidation and the A/B switch. */
@Tag("on-demand")
class GpuMaterialCacheSmokeTest {
    @Test
    void cacheWarmupCannotHoldACompleteMapBehindTheLoadingScreen() {
        UiTestStage.onGl(GpuBoardWindow.configuration(false), () -> {
            var cache = new GpuMaterialCache(() -> new GpuMaterialCache.Budget(64L << 20, "Test", false));
            try {
                var bounds = new BoundingBox(new Vector3(0, 0, 0), new Vector3(96, 96, 1));
                var camera = new OrthographicCamera(512, 512);
                camera.position.set(48, 48, 100);
                camera.update();
                GpuMaterialCache.Tops tops = (view, x0, y0, x1, y1) -> { };
                cache.rebuilt(bounds, null, 1);
                cache.allocate(true, false);
                var started = GpuMaterialCache.class.getDeclaredField("prewarmStarted");
                started.setAccessible(true);
                started.setLong(cache, System.nanoTime() - 1_000_000_000L);
                cache.prewarm(camera, true, tops, () -> cache.chunk(0, bounds, true, 0));
                assertTrue(cache.prewarmed(), "The loading gate opens even though no cache page has been built");
                assertEquals(0, cache.status().pages().occupied());
                for (int frame = 0; frame < 100 && !cache.complete(); frame++) {
                    cache.begin(camera, true, true, tops);
                    cache.chunk(0, bounds, true, 0);
                    cache.end();
                }
                assertTrue(cache.complete(), "Normal view frames finish the pending cache: " + cache.report());
                assertEquals(1, cache.status().cachedTopChunks());
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { cache.dispose(); }
        });
    }

    @Test
    void aSmallBudgetCompletesRegionsAndRecoversAfterSustainedMemoryHeadroom() {
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(512, 512);
        UiTestStage.onGl(configuration, () -> {
            var budget = new AtomicReference<>(new GpuMaterialCache.Budget(12L << 20, "Driver memory budget", true));
            var reads = new AtomicInteger();
            var cache = new GpuMaterialCache(() -> { reads.incrementAndGet(); return budget.get(); });
            try {
                var bounds = new BoundingBox(new Vector3(0, 0, 0), new Vector3(144, 144, 1));
                var camera = new OrthographicCamera(512, 512);
                camera.position.set(72, 72, 100);
                camera.zoom = .25f;
                camera.update();
                List<BoundingBox> chunks = new java.util.ArrayList<>();
                for (int x = 0; x < 2; x++) {
                    for (int y = 0; y < 2; y++) {
                        chunks.add(new BoundingBox(new Vector3(x * 72, y * 72, 0),
                              new Vector3((x + 1) * 72 - .01f, (y + 1) * 72 - .01f, 1)));
                    }
                }
                GpuMaterialCache.Tops tops = (view, x0, y0, x1, y1) -> { };
                Runnable frame = () -> {
                    cache.begin(camera, true, true, tops);
                    for (int i = 0; i < chunks.size(); i++) { cache.chunk(i, chunks.get(i), true, 0); }
                    cache.end();
                };
                cache.rebuilt(bounds, null, 1);
                cache.allocate(true, false);
                for (int i = 0; i < 20; i++) { frame.run(); }
                var partial = cache.status();
                assertEquals(12, partial.pages().capacity());
                assertTrue(partial.requested() > partial.admitted());
                assertTrue(partial.cachedTopChunks() > 0, "A constrained cache must complete a drawable region");
                assertTrue(partial.cachedTopChunks() < partial.topChunks());
                assertEquals(partial.pages().needed(), partial.pages().current());
                for (int i = 0; i < 50; i++) { frame.run(); }
                assertEquals(partial, cache.status(), "Settled partial coverage neither churns nor claims completeness");
                assertEquals(1, reads.get(), "No driver polling each frame");

                budget.set(new GpuMaterialCache.Budget(64L << 20, "Driver memory budget", true));
                GL30 original = Gdx.gl30;
                var refused = new AtomicInteger();
                var allocationError = new AtomicBoolean();
                try (var watched = new GpuGlWatch((proxy, method, args) -> {
                    // A missing higher mip can leave the base attachment complete: allocation errors must be checked.
                    if (method.getName().equals("glTexImage3D") && (int) args[1] == 1
                          && (int) args[5] > partial.pages().capacity()) {
                        refused.incrementAndGet();
                        allocationError.set(true);
                        return null;
                    }
                    if (method.getName().equals("glGetError") && allocationError.getAndSet(false)) {
                        return GL20.GL_OUT_OF_MEMORY;
                    }
                    return method.invoke(original, args);
                })) {
                    for (int i = 0; i < 400 && refused.get() == 0; i++) { frame.run(); }
                    assertTrue(refused.get() > 0, "The driver must refuse an attempted growth");
                    int attempts = refused.get();
                    for (int i = 0; i < 100; i++) {
                        frame.run();
                        assertEquals(partial.pages().capacity(), cache.status().pages().capacity());
                        assertTrue(cache.status().cachedTopChunks() >= partial.cachedTopChunks(),
                              "Failed growth must retain the readable cache and a working frame");
                    }
                    assertEquals(attempts, refused.get(), "Failed growth backs off until the next memory recheck");
                }
                for (int i = 0; i < 400; i++) {
                    frame.run();
                    assertTrue(cache.status().cachedTopChunks() >= partial.cachedTopChunks(),
                          "Growing storage must never lose the settled view's readable chunks");
                }
                assertTrue(cache.complete(), cache.report());
                assertTrue(cache.status().pages().capacity() > partial.pages().capacity());
                assertTrue(cache.status().pages().capacity() <= 24, "Growth follows this view, not all available VRAM");
                int settledReads = reads.get();
                var complete = cache.status();
                for (int i = 0; i < 400; i++) { frame.run(); }
                assertEquals(complete, cache.status());
                assertEquals(settledReads, reads.get(), "A covered view does not keep reallocating or polling");
                float scale = BoardCamera.renderScale;
                try {
                    BoardCamera.renderScale = scale / 2;
                    for (int i = 0; i < 100; i++) { frame.run(); }
                    assertTrue(cache.complete(), cache.report());
                    assertTrue(cache.status().pages().capacity() < complete.pages().capacity(),
                          "A settled render-resolution reduction can release excess storage");
                } finally { BoardCamera.renderScale = scale; }
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { cache.dispose(); }
        });
    }

    @Test
    void growingStoragePreservesEveryMipAndAnUnfinishedStaleRebuild() {
        UiTestStage.onGl(GpuBoardWindow.configuration(false), () -> {
            var pages = new GpuPageCache(8, 1, 3, 1, GL30.GL_SRGB8_ALPHA8, GL30.GL_RGBA8);
            int framebuffer = Gdx.gl.glGenFramebuffer();
            boolean srgb = Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB);
            try {
                Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
                pages.allocate(4);
                Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
                var targets = BufferUtils.newIntBuffer(2);
                targets.put(GL30.GL_COLOR_ATTACHMENT0).put(GL30.GL_COLOR_ATTACHMENT1).flip();
                Gdx.gl30.glDrawBuffers(2, targets);
                pages.begin();
                pages.need(0, 0, 0, true);
                pages.need(0, 1, 0, true);
                GpuPageCache.Filler paint = (level, x, y, layer, step) -> {
                    for (int mip = 0; mip < 3; mip++) {
                        pages.attach(layer, mip);
                        Gdx.gl.glClearColor((layer + 1) / 8f, (mip + 1) / 4f, .75f, .5f);
                        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                    }
                    return GpuPageCache.DONE;
                };
                pages.fill(1_000_000_000, Long.MAX_VALUE, paint);
                int held = pages.layer(0, 0, 0);
                pages.invalidate(0, 0, 0, 0, 0);
                pages.fill(1_000_000, Long.MAX_VALUE, (level, x, y, layer, step) -> {
                    paint.fill(level, x, y, layer, step);
                    return GpuPageCache.MORE;
                });
                var before = pages.status();
                assertEquals(1, before.building());
                assertEquals(1, before.stale());
                byte[][][] pixels = new byte[2][3][];
                for (int array = 0; array < 2; array++) {
                    for (int mip = 0; mip < 3; mip++) { pixels[array][mip] = pagePixels(pages, array, mip); }
                }
                long fills = pages.fills(), steps = pages.steps();
                Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                Gdx.gl.glScissor(0, 0, 1, 1);
                Gdx.gl.glColorMask(false, true, false, true);
                Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
                pages.grow(9);
                assertTrue(Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST));
                assertTrue(Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB));
                var mask = BufferUtils.newByteBuffer(4);
                Gdx.gl.glGetBooleanv(GL20.GL_COLOR_WRITEMASK, mask);
                assertEquals(0, mask.get(0)); assertEquals(1, mask.get(1));
                assertEquals(0, mask.get(2)); assertEquals(1, mask.get(3));
                var binding = BufferUtils.newIntBuffer(16);
                Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, binding);
                assertEquals(framebuffer, binding.get(0));
                assertEquals(held, pages.layer(0, 0, 0));
                assertEquals(before.occupied(), pages.status().occupied());
                assertEquals(before.current(), pages.status().current());
                assertEquals(before.building(), pages.status().building());
                assertEquals(before.stale(), pages.status().stale());
                assertEquals(fills, pages.fills()); assertEquals(steps, pages.steps());
                for (int array = 0; array < 2; array++) {
                    for (int mip = 0; mip < 3; mip++) {
                        org.junit.jupiter.api.Assertions.assertArrayEquals(pixels[array][mip],
                              Arrays.copyOf(pagePixels(pages, array, mip), pixels[array][mip].length),
                              "Every existing layer remains byte-identical at mip " + mip);
                    }
                }
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                if (srgb) { Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
                else { Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
                Gdx.gl.glColorMask(true, true, true, true);
                pages.fill(1_000_000_000, Long.MAX_VALUE, (level, x, y, layer, step) -> {
                    assertTrue(step > 0, "The unfinished rebuild resumes rather than restarting");
                    return paint.fill(level, x, y, layer, step);
                });
                assertEquals(2, pages.status().current());
                assertEquals(0, pages.status().stale());
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally {
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                if (srgb) { Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
                else { Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
                Gdx.gl.glColorMask(true, true, true, true);
                Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
                Gdx.gl.glDeleteFramebuffer(framebuffer);
                pages.dispose();
            }
        });
    }

    private static byte[] pagePixels(GpuPageCache pages, int array, int mip) {
        int size = 8 >> mip;
        byte[] result = new byte[pages.capacity() * size * size * 4];
        var pixels = BufferUtils.newByteBuffer(result.length);
        pages.array(array).bind();
        GL11.glGetTexImage(GL30.GL_TEXTURE_2D_ARRAY, mip, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
        pixels.get(result);
        return result;
    }

    @Test
    void pageCountsIncludePartialFillsStaleRebuildSparesAndRelease() {
        UiTestStage.onGl(GpuBoardWindow.configuration(false), () -> {
            var pages = new GpuPageCache(8, 1, 2, 1, GL30.GL_RGBA8);
            try {
                pages.allocate(4);
                assertEquals(4 * (8 * 8 + 4 * 4) * 4 + 2 * GpuPageCache.TABLE_BYTES, pages.allocatedBytes());
                pages.begin();
                pages.need(0, 0, 0, true);
                pages.need(0, 1, 0, true);
                // The first budget allows one step, deliberately leaving the first page incomplete.
                pages.fill(1_000_000, Long.MAX_VALUE, (level, x, y, layer, step) -> GpuPageCache.MORE);
                assertEquals(1, pages.status().building());
                assertEquals(0, pages.status().current());
                assertEquals(2, pages.status().needed());
                pages.fill(1_000_000_000, Long.MAX_VALUE, (level, x, y, layer, step) -> GpuPageCache.DONE);
                assertEquals(2, pages.status().current());
                assertEquals(2, pages.status().occupied());
                pages.invalidate(0, 0, 0, 0, 0);
                assertEquals(1, pages.status().stale());
                assertEquals(1, pages.status().neededStale());
                assertEquals(1, pages.status().current(), "A readable stale page does not count toward completion");
                int oldLayer = pages.layer(0, 0, 0);
                pages.fill(1_000_000_000, Long.MAX_VALUE, (level, x, y, layer, step) -> {
                    assertEquals(3, pages.status().occupied(), "A rebuild occupies a spare alongside its old page");
                    assertEquals(oldLayer, pages.layer(0, 0, 0), "The old page remains readable until completion");
                    return GpuPageCache.DONE;
                });
                assertEquals(0, pages.status().stale());
                assertEquals(2, pages.status().occupied());
                pages.invalidateAll();
                assertEquals(0, pages.status().current());
                pages.allocate(0);
                assertEquals(0, pages.allocatedBytes());
                assertEquals(new GpuPageCache.Status(0, 0, 0, 0, 0, 0, 0, 0), pages.status());
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { pages.dispose(); }
        });
    }

    @Test
    void cacheStatusSurvivesStillFramesAndDisablingRetainsStorage() {
        UiTestStage.onGl(GpuBoardWindow.configuration(false), () -> {
            var cache = new GpuMaterialCache();
            try {
                var bounds = new BoundingBox(new Vector3(0, 0, 0), new Vector3(96, 96, 1));
                var camera = new OrthographicCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                camera.position.set(48, 48, 100);
                camera.update();
                GpuMaterialCache.Tops tops = (view, x0, y0, x1, y1) -> { };
                cache.rebuilt(bounds, null, 1);
                cache.allocate(true, false);
                for (int frame = 0; frame < 100 && !cache.prewarmed(); frame++) {
                    cache.prewarm(camera, true, tops, () -> cache.chunk(0, bounds, true, 0));
                }
                assertTrue(cache.prewarmed());
                for (int frame = 0; frame < 3; frame++) {
                    cache.begin(camera, true, true, tops);
                    cache.chunk(0, bounds, true, 0);
                    cache.end();
                }
                var ready = cache.status();
                assertEquals("Ready", ready.state());
                assertTrue(ready.pages().needed() > 0);
                assertEquals(ready.pages().needed(), ready.pages().current());
                assertTrue(ready.allocatedBytes() > ready.pages().capacity() * ready.pageBytes(),
                      "Build targets and tables are included as well as page textures");
                cache.begin(camera, true, true, tops);
                cache.chunk(0, bounds, true, 0);
                cache.end();
                assertEquals(ready, cache.status(), "An unchanged frame reuses the completed plan's counts");

                cache.setEnabled(false);
                cache.begin(camera, true, true, tops);
                cache.chunk(0, bounds, true, 0);
                cache.end();
                assertFalse(cache.complete());
                assertEquals("Disabled - direct rendering", cache.status().state());
                assertEquals(ready.allocatedBytes(), cache.status().allocatedBytes());
                cache.invalidate(bounds);
                assertTrue(cache.status().pages().stale() > 0, "Disabled cache still tracks terrain invalidation");

                cache.setEnabled(true);
                cache.begin(camera, true, true, tops);
                cache.chunk(0, bounds, true, 0);
                cache.end();
                assertTrue(cache.complete(), cache.report());
                assertEquals("Ready", cache.status().state());
                assertEquals(ready.allocatedBytes(), cache.status().allocatedBytes());
                cache.dispose();
                assertEquals(0, cache.status().allocatedBytes());
                assertEquals(0, cache.status().pages().occupied());
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { cache.dispose(); }
        });
    }
}
