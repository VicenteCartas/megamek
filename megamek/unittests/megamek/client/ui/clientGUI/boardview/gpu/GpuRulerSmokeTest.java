/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuRulerSmokeTest {
    @Test
    void interveningWoodsTurnTheRayYellowUntilAnyLaterBlockerInBothViews() throws Exception {
        Board map = Board.createEmptyBoard(10, 10);
        Coords from = new Coords(5, 2), to = new Coords(5, 8), woods = new Coords(5, 4);
        map.getHex(woods).addTerrain(new Terrain(Terrains.WOODS, 1));
        map.getHex(woods).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        AtomicReference<BoardScene> modified = new AtomicReference<>(), blocked = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(map)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(from);
                var ruler = fixture.source.los().model();
                ruler.measure(from, to, fixture.entity.getId(), 2);
                fixture.source.refresh();
                modified.set(fixture.source.takeFrame().scene());
                assertEquals(woods, modified.get().tactical().ruler().modifierAt());
                map.setHex(new Coords(5, 6), new Hex(3));
                fixture.source.refresh();
                blocked.set(fixture.source.takeFrame().scene());
                assertEquals(3, blocked.get().tile(new Coords(5, 6)).elevation());
            });
        }
        GpuHudTestStage.run(hud -> {
            GpuTactical ruler = new GpuTactical();
            try {
                for (boolean obstructed : new boolean[] { false, true }) {
                    BoardScene scene = obstructed ? blocked.get() : modified.get();
                    GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
                    try {
                        ruler.update(scene);
                        for (boolean tactical : new boolean[] { false, true }) {
                            board.view(tactical);
                            board.draw(ruler::renderLabels);
                            Pixmap shot = hud.captureBackBuffer("ruler-modified-" + (obstructed ? "blocked-" : "")
                                  + (tactical ? "tactical" : "3d"));
                            try {
                                assertTrue(count(shot, GpuTactical.RULER_CLEAR, 0, 0, shot.getWidth(), shot.getHeight()) > 100);
                                assertTrue(count(shot, GpuTactical.RULER_MODIFIED, 0, 0, shot.getWidth(), shot.getHeight()) > 100);
                                int red = count(shot, GpuTactical.RULER_BLOCKED, 0, 0, shot.getWidth(), shot.getHeight());
                                assertTrue(obstructed ? red > 100 : red < 10, "Red pixels: " + red);
                                var before = board.screen(BoardGeometry.center(new Coords(5, 3), 2));
                                var after = board.screen(BoardGeometry.center(new Coords(5, 5), 2));
                                assertTrue(count(shot, GpuTactical.RULER_CLEAR,
                                      Math.round(before.x) - 8, Math.round(before.y) - 8, 17, 17) > 0);
                                assertTrue(count(shot, GpuTactical.RULER_MODIFIED,
                                      Math.round(after.x) - 8, Math.round(after.y) - 8, 17, 17) > 0,
                                      "The ray stays yellow after leaving the woods");
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            } finally {
                                shot.dispose();
                            }
                        }
                    } finally {
                        board.dispose();
                    }
                }
            } finally {
                ruler.dispose();
            }
        });
    }

    @Test
    void sharedRulerIsStraightAndRemainsVisibleThroughTheBlockingHillInBothViews() throws Exception {
        Board map = Board.createEmptyBoard(10, 10);
        Coords from = new Coords(5, 2), to = new Coords(5, 8), hill = new Coords(5, 5);
        map.getHex(hill).setLevel(3);
        AtomicReference<BoardScene> blocked = new AtomicReference<>(), clear = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(map)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(from);
                var ruler = fixture.source.los().model();
                ruler.measure(from, to, fixture.entity.getId(), 2);
                fixture.source.refresh();
                blocked.set(fixture.source.takeFrame().scene());
                assertEquals(hill, blocked.get().tactical().ruler().blockedAt());
                ruler.height(true, 5); ruler.height(false, 5);
                fixture.source.refresh();
                clear.set(fixture.source.takeFrame().scene());
            });
        }
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(blocked.get());
            GpuTactical ruler = new GpuTactical();
            try {
                for (boolean tactical : new boolean[] { false, true }) {
                    board.view(tactical);
                    board.camera.setIsometric(!tactical);
                    for (boolean obstructed : new boolean[] { true, false }) {
                        ruler.update(obstructed ? blocked.get() : clear.get());
                        board.draw(ruler::renderLabels);
                        Pixmap shot = hud.captureBackBuffer("ruler-" + (obstructed ? "blocked-" : "clear-")
                              + (tactical ? "tactical" : "3d"));
                        try {
                            assertTrue(count(shot, GpuTactical.RULER_CLEAR, 0, 0, shot.getWidth(), shot.getHeight()) > 100);
                            assertEquals(0, count(shot, GpuTactical.RULER_MODIFIED, 0, 0, shot.getWidth(), shot.getHeight()));
                            int red = count(shot, GpuTactical.RULER_BLOCKED, 0, 0, shot.getWidth(), shot.getHeight());
                            assertTrue(obstructed ? red > 100 : red < 10, "Red pixels: " + red);
                            if (obstructed) {
                                var covered = board.screen(BoardGeometry.center(hill, 2));
                                assertTrue(count(shot, GpuTactical.RULER_BLOCKED,
                                      Math.round(covered.x) - 12, Math.round(covered.y) - 12, 25, 25) > 0,
                                      "The red ray remains visible inside the blocking hill");
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        } finally {
                            shot.dispose();
                        }
                    }
                }
            } finally {
                ruler.dispose();
                board.dispose();
            }
        });
    }

    private static int count(Pixmap image, int rgba, int x, int y, int width, int height) {
        int count = 0;
        for (int row = Math.max(0, y); row < Math.min(image.getHeight(), y + height); row++) {
            for (int column = Math.max(0, x); column < Math.min(image.getWidth(), x + width); column++) {
                int actual = image.getPixel(column, row);
                if (Math.abs((actual >>> 24) - (rgba >>> 24)) <= 3
                      && Math.abs(((actual >>> 16) & 255) - ((rgba >>> 16) & 255)) <= 3
                      && Math.abs(((actual >>> 8) & 255) - ((rgba >>> 8) & 255)) <= 3) { count++; }
            }
        }
        return count;
    }
}
