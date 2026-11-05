/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Batch 10's converted legacy art in the native render (b10-design 2.4-2.7): black and white letters from one white
 * decal and a paint colour, which the decal material multiplies; a helipad where its row places it; a landing-pad ring
 * without its centre; an elevator's level plate as its terrain's decal. Captures go to
 * {@code build/gpu-board-review/b10-s1/}.
 */
@Tag("on-demand")
class GpuLegacyArtSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "b10-s1");
    private static final Coords BLACK = new Coords(1, 1), WHITE = new Coords(3, 1), HELIPAD = new Coords(5, 1),
          ELEVATOR = new Coords(7, 1), RING = new Coords(3, 4);

    /** A pavement board with one hex of each art, converted as every load site converts it. */
    private static Board board() {
        Map<Coords, String> art = new LinkedHashMap<>();
        art.put(BLACK, "fluff:70:1");
        art.put(WHITE, "fluff:71:1");
        art.put(HELIPAD, "fluff:2:1");
        art.put(ELEVATOR, "solaris_elevator:3");
        // The red cross's pieces around RING, its centre piece missing (fluff:62:7..13 without 62:10).
        int[] pieces = { 7, 9, 12, 13, 11, 8 };
        for (int direction = 0; direction < 6; direction++) { art.put(RING.translated(direction), "fluff:62:" + pieces[direction]); }
        Board board = Board.createEmptyBoard(9, 7);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                Coords at = new Coords(x, y);
                board.setHex(at, new Hex(0, "pavement:1" + (art.containsKey(at) ? ";" + art.get(at) : ""), ""));
            }
        }
        BoardSceneryLayouts.convert(board);
        return board;
    }

    @Test
    void convertedLegacyArtRendersInItsColoursAndPlaces() throws Exception {
        Board board = board();
        BoardScene scene = BoardScenes.scene(board);
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(800, 800);
        config.setInitialVisible(false);
        Map<String, Coords> shots = new LinkedHashMap<>();
        shots.put("letter-black", BLACK);
        shots.put("letter-white", WHITE);
        shots.put("helipad", HELIPAD);
        shots.put("elevator-plate", ELEVATOR);
        shots.put("pad-ring-without-centre", RING);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    for (var shot : shots.entrySet()) {
                        camera.setIsometric(false);
                        camera.center(BoardGeometry.center(shot.getValue(), 0));
                        camera.camera.zoom = shot.getKey().startsWith("pad-ring") ? .5f : .2f;
                        camera.update();
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, shot.getKey() + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Legacy art review", failure.get()); }
        // The decal material multiplies the slot-0 paint: the black letter is drawn darker than its concrete, the white
        // one lighter, from the same white image.
        int[] black = luminance(new File(OUTPUT, "letter-black.png")), white = luminance(new File(OUTPUT, "letter-white.png"));
        System.out.printf("LEGACY ART letter luminance: black min %d max %d, white min %d max %d%n", black[0], black[1],
              white[0], white[1]);
        assertTrue(black[0] < 50, "A black letter shows black paint: " + black[0]);
        assertTrue(white[1] > 200, "A white letter shows white paint: " + white[1]);
        assertTrue(black[1] < white[1] - 40, "Only the paint colour differs: black max " + black[1] + ", white max " + white[1]);
        // The ring's pieces paint their parts of the cross around the empty centre, which paints nothing.
        var ring = ImageIO.read(new File(OUTPUT, "pad-ring-without-centre.png"));
        int red = 0, centre = 0;
        for (int y = 0; y < ring.getHeight(); y++) {
            for (int x = 0; x < ring.getWidth(); x++) {
                int rgb = ring.getRGB(x, y);
                if ((rgb >> 16 & 255) > 150 && (rgb >> 8 & 255) < 60 && (rgb & 255) < 60) {
                    red++;
                    if (Math.abs(x - ring.getWidth() / 2) < 20 && Math.abs(y - ring.getHeight() / 2) < 20) { centre++; }
                }
            }
        }
        assertTrue(red > 1000, "The pieces show the cross: " + red);
        assertEquals(0, centre, "The missing centre piece paints nothing");
    }

    /** The darkest and brightest luminance in the middle quarter of a capture (the centred hex's letter). */
    private static int[] luminance(File file) throws Exception {
        var image = ImageIO.read(file);
        int min = 255, max = 0;
        for (int y = image.getHeight() * 3 / 8; y < image.getHeight() * 5 / 8; y++) {
            for (int x = image.getWidth() * 3 / 8; x < image.getWidth() * 5 / 8; x++) {
                int rgb = image.getRGB(x, y);
                int value = (int) Math.round(.299 * (rgb >> 16 & 255) + .587 * (rgb >> 8 & 255) + .114 * (rgb & 255));
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return new int[] { min, max };
    }
}
