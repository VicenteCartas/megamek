/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.board.BoardFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The reported Shipyard failure crossed parallel terrain preparation, authored walls and the real loading screen. */
@Tag("on-demand")
class GpuShipyardSmokeTest {
    @Test
    void shippedShipyardLoadsAndPicksNativeWallsInBothCameraViews() throws Exception {
        var preferences = GUIPreferences.getInstance();
        var original = preferences.getBoardGraphics();
        var failure = new AtomicReference<Throwable>();
        try {
            SwingUtilities.invokeAndWait(() -> preferences.setBoardGraphics(BoardGraphics.ENHANCED));
            var board = BoardFile.loadSheet(new File("data/boards/unofficial/Morkath/100x78 Shipyard.board"),
                  false, true);
            assertEquals(100, board.getWidth());
            assertEquals(78, board.getHeight());
            try (var fixture = GpuBoardFixture.create(board)) {
                var configuration = GpuBoardWindow.configuration(false);
                configuration.setWindowedMode(1280, 900);
                configuration.setInitialVisible(false);
                new Lwjgl3Application(new ApplicationAdapter() {
                    @Override public void create() {
                        GpuBattleView view = null;
                        try {
                            view = new GpuBattleView(fixture.source);
                            view.create();
                            GpuBoardTestUi.present(view);
                            var terrain = (GpuTerrain) field(view, "terrain");
                            var scene = (BoardScene) field(view, "scene");
                            var camera = view.boardCamera;
                            assertFalse(terrain.columns());
                            var walls = scene.tiles().stream().filter(tile -> tile.features().stream()
                                  .anyMatch(feature -> BoardWall.supports(feature.asset()))).toList();
                            assertTrue(walls.size() > 100, "The shipped map exercises many simultaneous wall assemblies");
                            assertTrue(walls.stream().allMatch(BoardScene.Tile::detailedGround));
                            var wall = walls.get(walls.size() / 2);
                            var feature = wall.features().stream().filter(part -> BoardWall.supports(part.asset()))
                                  .findFirst().orElseThrow();
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                                  "walls");
                            assertTrue(output.isDirectory() || output.mkdirs());
                            for (boolean isometric : new boolean[] { true, false }) {
                                camera.setIsometric(isometric);
                                camera.center(BoardGeometry.center(wall.coords(), wall.elevation() + feature.height() / 2));
                                camera.camera.zoom = .65f;
                                camera.update();
                                GpuBoardTestUi.present(view);
                                assertTrue(terrain.ready(scene));
                                var ray = new Ray(BoardGeometry.center(wall.coords(), wall.elevation() + feature.height())
                                      .add(.3f, .2f, 100), new Vector3(0, 0, -1));
                                var hit = terrain.hit(scene, ray);
                                assertNotNull(hit);
                                assertEquals(wall.coords(), hit.coords());
                                assertNotNull(terrain.selectionHit(scene, ray));
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                                GpuBoardTestUi.capture(new File(output, isometric ? "shipyard-oblique.png" : "shipyard-top.png"));
                            }
                        } catch (Throwable error) {
                            failure.set(error);
                        } finally {
                            if (view != null) { view.dispose(); }
                            Gdx.app.exit();
                        }
                    }
                }, configuration);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setBoardGraphics(original));
        }
        if (failure.get() != null) { throw new AssertionError("The shipped Shipyard must finish loading", failure.get()); }
    }
}
