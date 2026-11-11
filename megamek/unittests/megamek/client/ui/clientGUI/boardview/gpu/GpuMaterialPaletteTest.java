/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The material palette's orders and file details, without a GL context. */
class GpuMaterialPaletteTest {
    private record Item(GpuMaterialPalette.Material material, BufferedImage image) { }

    private static Item item(String name, long bytes, long modified, Color colour) {
        BufferedImage image = null;
        if (colour != null) {
            image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, 16, 16);
            graphics.setColor(colour);
            // The corner stays the backdrop, which brightness leaves out.
            graphics.fillRect(1, 1, 15, 15);
            graphics.dispose();
        }
        return new Item(new GpuMaterialPalette.Material(name, bytes, modified, 3, 0), image);
    }

    private static List<String> names(List<Item> items, GpuMaterialPalette.Order order) {
        return GpuMaterialPalette.order(items, order, Item::material, Item::image).stream()
              .map(item -> item.material().name()).toList();
    }

    @Test
    void ordersByFolderSizeAndChange() {
        List<Item> items = List.of(item("a/one", 10, 300, null), item("a/two", 30, 100, null),
              item("b/three", 20, 200, null));
        assertEquals(List.of("a/one", "a/two", "b/three"), names(items, GpuMaterialPalette.Order.FOLDER));
        assertEquals(List.of("a/two", "b/three", "a/one"), names(items, GpuMaterialPalette.Order.SIZE));
        assertEquals(List.of("a/one", "b/three", "a/two"), names(items, GpuMaterialPalette.Order.CHANGED));
    }

    @Test
    void ordersByLookPutDrawnSwatchesFirstAndTheRestByFolder() {
        List<Item> items = List.of(item("light", 1, 1, new Color(230, 230, 230)), item("undrawn", 1, 1, null),
              item("dark", 1, 1, new Color(40, 40, 40)), item("mid", 1, 1, new Color(128, 128, 128)),
              item("darkish", 1, 1, new Color(60, 60, 60)));
        assertEquals(List.of("dark", "darkish", "mid", "light", "undrawn"),
              names(items, GpuMaterialPalette.Order.BRIGHTNESS));
        List<String> similar = names(items, GpuMaterialPalette.Order.SIMILARITY);
        assertEquals("dark", similar.get(0), "the chain starts at the darkest");
        assertEquals("darkish", similar.get(1), "followed by the one it looks most like");
        assertEquals("undrawn", similar.get(similar.size() - 1));
    }

    @Test
    void detailShowsTheFiles() {
        var withAlbedo = new GpuMaterialPalette.Material("ground/grass", 2_500_000, 0, 4, 1024);
        String detail = withAlbedo.detail();
        assertTrue(detail.startsWith("ground/grass\n1024 px albedo, 2.5 MB in 4 maps, changed "), detail);
        assertEquals("grass", withAlbedo.title());
        var small = new GpuMaterialPalette.Material("rock", 1_499, 0, 1, 0);
        assertTrue(small.detail().startsWith("rock\n1 KB in 1 maps, changed "), small.detail());
    }
}
