/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.graphics.Color;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuRoadMasksTest {
    @Test
    void distantRoadMasksKeepTheirWorldBoundsAndCoverage() {
        int reduced = 0;
        for (int exits : new int[] { 1, 3, 5, 9, 21, 63 }) {
            var tile = BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS);
            var road = BoardRoad.clearance(BoardScenes.CENTER, exits);
            for (var patch : GpuRoads.patches(tile, road)) {
                if (patch.shape().isEmpty()) { continue; }
                var full = GpuRoads.mask(road, patch);
                int density = GpuRoads.maskDensity(patch, TerrainLod.COARSE);
                if (density < 4) { reduced++; }
                var distant = GpuRoads.mask(road, patch, density);
                assertEquals(full.x(), distant.x());
                assertEquals(full.y(), distant.y());
                assertEquals(full.width(), distant.width());
                assertEquals(full.height(), distant.height());
                assertEquals(full.pixels().width(), distant.pixels().width() * (4 / density));
                assertEquals(full.pixels().height(), distant.pixels().height() * (4 / density));
                assertEquals(4, GpuRoads.maskDensity(patch, TerrainLod.MEDIUM));
                assertEquals(4, GpuRoads.maskDensity(patch, TerrainLod.FULL));
                assertEquals(coverage(full), coverage(distant), .005,
                      "Distant masks retain the covered road area, including curves and junction markings");
            }
        }
        assertTrue(reduced > 0, "The fixtures exercise distant wear fields as well as preserved markings");
    }

    @Test
    void equalRoadsAnywhereShareOneMaskUnlessItReadsTheirPlace() {
        var far = new Coords(BoardScenes.CENTER.getX() + 7, BoardScenes.CENTER.getY() + 5);
        for (int exits : new int[] { 9, 21, 63 }) {
            var here = BoardRoad.clearance(BoardScenes.CENTER, exits);
            var there = BoardRoad.clearance(far, exits);
            var patches = GpuRoads.patches(BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS), here);
            var others = GpuRoads.patches(BoardScenes.tile(far, BoardRoad.Kind.PAVED, exits, 0, BoardScene.Surface.GRASS), there);
            assertEquals(patches.size(), others.size());
            for (int i = 0; i < patches.size(); i++) {
                if (patches.get(i).shape().isEmpty()) { continue; }
                assertEquals(GpuRoads.maskKey(here, patches.get(i), 4), GpuRoads.maskKey(there, others.get(i), 4));
                assertEquals(GpuRoads.mask(here, patches.get(i), 4), GpuRoads.mask(there, others.get(i), 4),
                      "One key means one mask, wherever its road lies");
            }
        }
        // A dead end's wear reads the world's noise (BoardRoad#endCoverage): it stays its own where it lies.
        var here = BoardRoad.layout(BoardScenes.CENTER, 1, BoardRoad.Kind.DIRT, direction -> BoardRoad.Kind.DIRT);
        var there = BoardRoad.layout(far, 1, BoardRoad.Kind.DIRT, direction -> BoardRoad.Kind.DIRT);
        var worn = GpuRoads.patches(BoardScenes.tile(BoardScenes.CENTER, BoardRoad.Kind.DIRT, 1, 0, BoardScene.Surface.GRASS), here).getFirst();
        var other = GpuRoads.patches(BoardScenes.tile(far, BoardRoad.Kind.DIRT, 1, 0, BoardScene.Surface.GRASS), there).getFirst();
        assertTrue(worn.fade().end() > 0);
        assertNotEquals(GpuRoads.maskKey(here, worn, 4), GpuRoads.maskKey(there, other, 4));
        assertNotEquals(GpuRoads.mask(here, worn, 4), GpuRoads.mask(there, other, 4));
    }

    private static double coverage(GpuRoads.MaskData mask) {
        var pixels = mask.pixels();
        long alpha = 0;
        int size = pixels.width() * pixels.height();
        for (int i = 0; i < size; i++) { alpha += pixels.rgba(i) & 255; }
        return alpha / (255.0 * size);
    }

    @Test
    void parallelRowsMatchSerialPixelsAndKeepTheCapturedTerrainSettings() throws Exception {
        var settings = TerrainSettings.capture();
        var patch = new GpuRoads.Patch(new Area(new Rectangle2D.Float(-60, -50, 120, 100)),
              "roads/asphalt", Color.WHITE, 1, 0, null);
        GpuRoads.MaskColor colour = (x, y, scratch) -> {
            assertSame(settings, TerrainSettings.current());
            return scratch.set((x + 61) / 122, (y + 51) / 102, .3f, .8f);
        };
        var serial = settings.call(() -> GpuRoads.mask(patch, colour));
        try (var workers = TerrainSettings.workers(4)) {
            var parallel = workers.submit(() -> settings.call(() -> GpuRoads.mask(patch, colour)));
            assertEquals(serial, parallel.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousTerrainWorkersBuildOneSharedMask() throws Exception {
        var masks = new GpuRoads.Masks();
        var result = mask(0xff778899);
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var builds = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<GpuRoads.MaskData>>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> masks.mask("road", () -> {
                    builds.incrementAndGet();
                    await(release);
                    return result;
                }, entered::countDown)));
            }
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            for (var future : results) { assertSame(result, future.get(5, TimeUnit.SECONDS)); }
            assertEquals(1, builds.get(), "Adjacent chunks must not rasterize the same road mask in parallel");
        }
    }

    @Test
    void forgettingWhileAMaskBuildsDoesNotOverwriteTheNewGeneration() throws Exception {
        var masks = new GpuRoads.Masks();
        var old = mask(0xff112233);
        var current = mask(0xff445566);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var oldBuild = workers.submit(() -> masks.mask("road", () -> {
                entered.countDown();
                await(release);
                return old;
            }, () -> { }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                masks.forget();
                assertSame(current, masks.mask("road", () -> current, () -> { }));
            } finally { release.countDown(); }
            assertSame(old, oldBuild.get(5, TimeUnit.SECONDS));
            assertSame(current, masks.mask("road", () -> { throw new AssertionError("Already built"); }, () -> { }));
        }
    }

    @Test
    void failuresCanRetryAndAStaleRequesterDoesNotPoisonReusablePixels() {
        var masks = new GpuRoads.Masks();
        assertThrows(IllegalStateException.class, () -> masks.mask("road", () -> {
            throw new IllegalStateException("Rasterization failed");
        }, () -> { }));
        var result = mask(0xffaabbcc);
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> masks.mask("road", () -> result, () -> {
            if (checks.incrementAndGet() > 1) { throw new CancellationException(); }
        }));
        assertSame(result, masks.mask("road", () -> { throw new AssertionError("Reusable after cancellation"); }, () -> { }));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static GpuRoads.MaskData mask(int argb) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        return new GpuRoads.MaskData(new BoardScene.Pixels(image), 0, 0, 1, 1);
    }
}
