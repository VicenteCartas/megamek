/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class BoardPixelsTest {
    @Test
    void compactMasksPreserveEveryChannelAndCompareWithUncompressedArtwork() {
        for (boolean sparse : new boolean[] { true, false }) {
            BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, sparse && (x < 30 || x > 50) ? 0x00ffffff
                          : ((y * 3) << 24) | (x << 16) | (y << 8) | 0xa5);
                }
            }
            var original = new BoardScene.Pixels(image);
            var compact = original.compact();
            assertEquals(original, compact);
            assertEquals(compact, original);
            assertEquals(original.hashCode(), compact.hashCode());
            assertEquals(1, new HashSet<>(List.of(original, compact)).size());
            for (int i = 0; i < image.getWidth() * image.getHeight(); i++) {
                assertEquals(original.rgba(i), compact.rgba(i), "Mask channels must survive exact compression");
            }
            // Full uploads and bordered row uploads can start or end inside a compressed run.
            for (var pixels : List.of(original, compact)) {
                for (int[] range : new int[][] { { 0, 6048 }, { 29, 24 }, { 84, 84 }, { 6047, 1 }, { 6048, 0 } }) {
                    var bytes = ByteBuffer.allocateDirect((range[1] + 2) * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
                    var target = bytes.asIntBuffer();
                    target.put(0x12345678);
                    pixels.writeRgba(target, range[0], range[1]);
                    assertEquals(range[1] + 1, target.position());
                    assertEquals(0x12345678, target.get(0), "Upload must preserve preceding buffer data");
                    for (int i = 0; i < range[1]; i++) {
                        int argb = image.getRGB((range[0] + i) % 84, (range[0] + i) / 84);
                        assertEquals(argb >>> 16 & 255, bytes.get((i + 1) * 4) & 255);
                        assertEquals(argb >>> 8 & 255, bytes.get((i + 1) * 4 + 1) & 255);
                        assertEquals(argb & 255, bytes.get((i + 1) * 4 + 2) & 255);
                        assertEquals(argb >>> 24, bytes.get((i + 1) * 4 + 3) & 255);
                    }
                }
            }
            image.setRGB(0, 0, 0xff123456);
            assertEquals(original.rgba(0), compact.rgba(0), "Compacted pixels own their immutable data");
        }
    }

    @Test
    void copiesSubimageStridesAndConvertsOtherImageFormatsExactly() {
        for (int type : new int[] { BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE,
              BufferedImage.TYPE_4BYTE_ABGR }) {
            BufferedImage page = new BufferedImage(20, 12, type);
            for (int y = 0; y < page.getHeight(); y++) {
                for (int x = 0; x < page.getWidth(); x++) {
                    page.setRGB(x, y, 0x80400000 | (x << 8) | y);
                }
            }
            BufferedImage region = page.getSubimage(7, 3, 5, 6);
            BoardScene.Pixels pixels = new BoardScene.PixelPool().capture(region, null);
            for (int y = 0; y < 6; y++) {
                for (int x = 0; x < 5; x++) {
                    int argb = page.getRGB(x + 7, y + 3);
                    assertEquals((argb << 8) | ((argb >>> 24) & 255), pixels.rgba(y * 5 + x));
                }
            }
        }
    }

    @Test
    void sharesExactArtworkAndOwnsPixelsAcrossImageAndScratchBufferChanges() {
        BoardScene.PixelPool pool = new BoardScene.PixelPool();
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(20, 20, 0xff123456);
        BoardScene.Pixels first = pool.capture(image, null);
        BufferedImage copy = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        copy.setRGB(20, 20, 0xff123456);
        assertSame(first, pool.capture(copy, null));
        image.setRGB(20, 20, 0xff654321);
        BoardScene.Pixels changed = pool.capture(image, first);
        assertNotSame(first, changed);
        assertEquals(0x123456ff, first.rgba(20 * 84 + 20));
        assertEquals(0x654321ff, changed.rgba(20 * 84 + 20));
        assertSame(first, pool.capture(copy, null));
        pool.retain(List.of());
        assertNotSame(first, pool.capture(copy, null), "Unreferenced artwork must leave the pool");
        assertNull(pool.captureOverlay(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB), null));
    }

    @Test void overlayVisibilityFollowsPixelContentAcrossRepaintAndRetirement() {
        var pool = new BoardScene.PixelPool();
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        assertNull(pool.captureOverlay(image, null));
        image.setRGB(20, 10, 0x01000000);
        var shown = pool.captureOverlay(image, null);
        assertNotNull(shown); assertSame(shown, pool.captureOverlay(image, null));
        image.setRGB(20, 10, 0x0000FFFF);
        assertNull(pool.captureOverlay(image, shown), "RGB in transparent pixels is not visible");
        pool.retain(List.of());
        image.setRGB(20, 10, 0xFF123456);
        var repainted = pool.captureOverlay(image, null);
        assertNotNull(repainted); assertNotEquals(shown, repainted);
        pool.clear();
        assertEquals(repainted, pool.captureOverlay(image, null));
    }
}
