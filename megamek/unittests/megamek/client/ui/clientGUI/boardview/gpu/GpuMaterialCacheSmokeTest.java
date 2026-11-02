/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live diagnostic counts follow actual GL allocation, incremental fills, invalidation and the A/B switch. */
@Tag("on-demand")
class GpuMaterialCacheSmokeTest {
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
                    cache.prewarm(camera, true, true, tops, () -> cache.chunk(0, bounds, true, 0));
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
