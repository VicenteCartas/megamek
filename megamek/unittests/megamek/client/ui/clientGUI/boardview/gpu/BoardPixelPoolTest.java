/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

class BoardPixelPoolTest {
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
