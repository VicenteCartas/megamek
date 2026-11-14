/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoardRimTest {
    private static final Coords CENTER = new Coords(3, 3);
    private final BoardScene.Pixels ground = pixels(0xff505050);

    @AfterEach
    void restoreGeometry() { BoardGeometry.tune(BoardGeometry.DEFAULTS); }

    @Test
    void concurrentCompositionMatchesSerialArtwork() throws Exception {
        BoardRim parallel = new BoardRim(), serial = new BoardRim();
        BoardScene.Pixels mask = pixels(0xff606060);
        var scenes = java.util.stream.IntStream.range(0, 48)
              .mapToObj(i -> scene(i % 6, i % 2 == 0, 0)).toList();
        var expected = scenes.stream().map(scene -> serial.column(scene, scene.tile(CENTER), ground, mask, mask))
              .toList();
        TerrainSettings settings = TerrainSettings.capture();
        try (var workers = new java.util.concurrent.ForkJoinPool(2)) {
            var actual = workers.submit(() -> scenes.parallelStream().map(scene -> settings.call(() ->
                  parallel.column(scene, scene.tile(CENTER), ground, mask, mask))).toList()).get();
            assertEquals(expected, actual, "Parallel cache publication must preserve the exact composed pixels");
        }
    }

    @Test
    void rotatesTheRimOnAllSixEdgesAtDifferentScales() {
        BoardScene.Pixels mask = pixels(0xff606060);
        for (float scale : new float[] { 0.5f, 1, 2 }) {
            BoardGeometry.tune(new BoardGeometry.Tuning(scale, 0.7f, 1, 18, 0.8f));
            for (int edge = 0; edge < 6; edge++) {
                BoardScene scene = scene(edge, false, 0);
                BoardScene.Pixels art = new BoardRim().column(scene, scene.tile(CENTER), ground, mask, mask);
                assertEquals(Math.round(0x50 * shade(0x60, 1)), art.rgba(probe(edge, 0.5f, 9)) >>> 24, 1,
                      "The original mask shades the art by its lightness about mid gray");
                int centre = 36 * 84 + 42;
                assertEquals(ground.rgba(centre), art.rgba(centre));
            }
        }
    }

    @Test
    void midGrayPreservesTheArtAndTheMaskAlphaWeightsTheShade() {
        BoardScene scene = scene(0, false, 0);
        int index = probe(0, 0.5f, 9);
        BoardScene.Pixels transparent = pixels(0x00c03080), gray = pixels(0xff808080), faded = pixels(0x80606060);
        assertEquals(ground.rgba(index), new BoardRim().column(scene, scene.tile(CENTER), ground, transparent,
              transparent)
              .rgba(index), "Transparent pixels leave the art untouched");
        assertEquals(ground.rgba(index), new BoardRim().column(scene, scene.tile(CENTER), ground, gray,
              gray).rgba(index),
              "Mid gray leaves the art unchanged");
        assertEquals(Math.round(0x50 * shade(0x60, 0x80 / 255f)),
              new BoardRim().column(scene, scene.tile(CENTER), ground, faded, faded).rgba(index) >>> 24, 1,
              "Half coverage takes half the shade");
    }

    @Test
    void brightRimsSaturateEachChannelWithoutCorruptingColorOrCoverage() {
        BoardScene scene = scene(0, false, 0);
        BoardScene.Pixels white = pixels(0xffffffff);
        int pixel = new BoardRim().column(scene, scene.tile(CENTER), pixels(0x80f08020), white, white)
              .rgba(probe(0, 0.5f, 9));
        assertEquals(255, pixel >>> 24, "A highlight saturates red instead of wrapping to a dark value");
        assertEquals(Math.round(128 * shade(255, 1)), pixel >>> 16 & 255);
        assertEquals(Math.round(32 * shade(255, 1)), pixel >>> 8 & 255);
        assertEquals(128, pixel & 255, "RGB overflow must never set alpha bits");
    }

    /** The libGDX branch's lightness rule, weighted by the original mask's coverage and opacity. */
    private static float shade(int gray, float coverage) {
        return 1 + coverage * BoardRim.BLEND_OPACITY * (gray / 128f - 1);
    }

    @Test
    void tacticalColumnsShadeEachDropEdgeWithTheBoardsInclineSplit() {
        BoardScene.Pixels incline = pixels(0xff606060), high = pixels(0xffc0c0c0);
        int centre = 36 * 84 + 42;
        for (int edge = 0; edge < 6; edge++) {
            BoardScene gentle = scene(edge, false, 0);
            BoardScene.Pixels art = new BoardRim().column(gentle, gentle.tile(CENTER), ground, incline, high);
            assertEquals(Math.round(0x50 * shade(0x60, 1)), art.rgba(probe(edge, 0.5f, 9)) >>> 24, 1,
                  "A two-level drop wears the incline mask at edge " + edge);
            assertEquals(ground.rgba(centre), art.rgba(centre));
            BoardScene road = scene(edge, true, 0);
            BoardScene.Pixels ramp = new BoardRim().column(road, road.tile(CENTER), ground, incline, high);
            assertEquals(ground.rgba(probe(edge, 0.5f, 3)), ramp.rgba(probe(edge, 0.5f, 3)),
                  "A road ramping through the edge keeps its mouth clear at edge " + edge);
            // The mouth (BoardSurface.ROAD_MOUTH) leaves the rim only within a few pixels of each corner.
            assertNotEquals(ground.rgba(probe(edge, 0.05f, 3)), ramp.rgba(probe(edge, 0.05f, 3)),
                  "Beside the mouth the drop keeps its rim at edge " + edge);
            BoardScene steep = scene(edge, false, -1);
            assertEquals(Math.round(0x50 * shade(0xc0, 1)),
                  new BoardRim().column(steep, steep.tile(CENTER), ground, incline, high).rgba(probe(edge, 0.5f, 9)) >>> 24,
                  1, "A three-level drop wears the high mask at edge " + edge);
            BoardScene cliff = cliffTop(gentle, BoardGeometry.edgeDirection(edge));
            assertEquals(Math.round(0x50 * shade(0xc0, 1)),
                  new BoardRim().column(cliff, cliff.tile(CENTER), ground, incline, high).rgba(probe(edge, 0.5f, 9)) >>> 24,
                  1, "A two-level drop the map marks as a cliff side wears the high mask at edge " + edge);
        }
        BoardScene level = scene(0, false, 2);
        assertSame(ground, new BoardRim().column(level, level.tile(CENTER), ground, incline, high),
              "A column without a lower neighbour keeps its art");
    }

    /**
     * The Classic column's art bakes no cliff stroke: the Tactical View's ground layer strokes cliffs, in both graphics
     * (b11-scope: in Classic the stroke appears only in the Tactical View). Neutral rims isolate a stroke from the rims'
     * own shading, on every drop, with and without a manual cliff exit.
     */
    @Test
    void classicColumnsBakeNoCliffStroke() {
        BoardScene.Pixels mask = pixels(0xff808080);
        BoardRim rims = new BoardRim();
        for (int edge = 0; edge < 6; edge++) {
            int border = probe(edge, .5f, .75f);
            for (int drop = 1; drop <= 3; drop++) {
                BoardScene scene = scene(edge, false, 2 - drop);
                for (BoardScene board : List.of(scene, cliffTop(scene, BoardGeometry.edgeDirection(edge)))) {
                    BoardScene.Pixels art = rims.column(board, board.tile(CENTER), ground, mask, mask);
                    assertEquals(ground.rgba(border), art.rgba(border), "No stroke on a " + drop + "-level drop, edge " + edge);
                }
            }
        }
    }

    @Test
    void reusesArtForUnchangedInputsAndReleasesUnusedCombinations() {
        BoardScene.Pixels mask = pixels(0xfff0f0f0);
        BoardRim rims = new BoardRim();
        BoardScene scene = scene(0, false, 0);
        BoardScene.Pixels first = rims.column(scene, scene.tile(CENTER), ground, mask, mask);
        rims.retainUsed();
        BoardScene equivalent = scene(0, false, 0);
        assertSame(first, rims.column(equivalent, equivalent.tile(CENTER), ground, mask, mask));
        rims.retainUsed();
        rims.retainUsed();
        assertNotSame(first, rims.column(scene, scene.tile(CENTER), ground, mask, mask));
    }

    @Test
    void smallCustomArtKeepsTheRimsFullSize() {
        BoardScene.Pixels small = pixels(0xff505050, 2, 2), mask = pixels(0xff606060);
        BoardScene scene = scene(0, false, 0);
        BoardScene.Pixels art = new BoardRim().column(scene, scene.tile(CENTER), small, mask, mask);
        assertEquals(84, art.width());
        assertEquals(72, art.height());
        assertEquals(Math.round(0x50 * shade(0x60, 1)), art.rgba(probe(0, 0.5f, 9)) >>> 24, 1);
        assertEquals(small.rgba(0), art.rgba(36 * 84 + 42));
    }

    /** A level board except the one neighbor across {@code edge} sits at {@code neighborElevation}. */
    private BoardScene scene(int edge, boolean road, int neighborElevation) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        int direction = BoardGeometry.edgeDirection(edge);
        Coords low = CENTER.translated(direction);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean paved = road && coords.equals(CENTER);
                // Road exits make a road only with a kind of road (BoardRoad.rendered).
                tiles.add(BoardScenes.newTile(coords, coords.equals(low) ? neighborElevation : 2, -1, false,
                      paved ? 1 << direction : 0, BoardScene.Surface.GRASS, List.of(), List.of(),
                      BoardLiquid.NONE, null, paved ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The centre hex with its manual cliff-top exit toward {@code direction}. */
    private static BoardScene cliffTop(BoardScene scene, int direction) {
        BoardScene.Tile t = scene.tile(CENTER);
        List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
        tiles.set(CENTER.getX() * scene.height() + CENTER.getY(), BoardScenes.newTile(t.coords(), t.elevation(),
              t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), 1 << direction));
        return scene.withTiles(tiles);
    }

    private static int probe(int edge, float along, float inward) {
        Vector3 a = BoardGeometry.corner(CENTER, 2, edge), b = BoardGeometry.corner(CENTER, 2, edge + 1);
        Vector3 direction = b.cpy().sub(a).nor();
        Vector3 point = a.lerp(b, along).mulAdd(new Vector3(-direction.y, direction.x, 0), inward * BoardGeometry.HEX_SCALE);
        float u = 0.5f + (point.x - BoardGeometry.centerX(CENTER)) / BoardGeometry.WIDTH * BoardRim.GROUND_UV_SCALE;
        float v = 0.5f - (point.y - BoardGeometry.centerY(CENTER)) / BoardGeometry.HEIGHT * BoardRim.GROUND_UV_SCALE;
        return (int) (v * 72) * 84 + (int) (u * 84);
    }

    private static BoardScene.Pixels pixels(int color) {
        return pixels(color, 84, 72);
    }

    private static BoardScene.Pixels pixels(int color, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { image.setRGB(x, y, color); }
        }
        return new BoardScene.Pixels(image);
    }
}
