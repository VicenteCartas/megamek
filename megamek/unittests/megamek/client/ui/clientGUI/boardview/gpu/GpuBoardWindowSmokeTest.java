/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.BoardViewsContainer;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.overlay.UnitOverviewOverlay;
import megamek.client.ui.dialogs.advancedSearchMap.AdvancedSearchMapDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayPanel;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.dialogs.unitDisplay.WeaponPanel;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.StartingScenarioPanel;
import megamek.client.ui.panels.WaitingForServerPanel;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyBoardPreviews;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyServices;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.Report;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.units.Entity;
import megamek.common.units.Terrains;
import megamek.utilities.BoardClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Exercises the real Swing/native window handoff and the shared menu through Scene2D input. */
@Tag("on-demand")
class GpuBoardWindowSmokeTest {

    private static final List<String> WINDOW_BOUNDS = List.of(GUIPreferences.GPU_BOARD_POS_X, GUIPreferences.GPU_BOARD_POS_Y,
          GUIPreferences.GPU_BOARD_SIZE_WIDTH, GUIPreferences.GPU_BOARD_SIZE_HEIGHT);
    private final java.util.Map<String, Integer> savedBounds = new java.util.HashMap<>();
    private boolean savedMaximized;

    @BeforeEach
    void saveBoardStyle() {
        var prefs = GUIPreferences.getInstance();
        // The native window reopens at its last saved bounds; every case starts from the maximized default.
        for (String key : WINDOW_BOUNDS) { savedBounds.put(key, prefs.getInt(key)); }
        savedMaximized = prefs.getBoolean(GUIPreferences.GPU_BOARD_MAXIMIZED);
        prefs.setValue(GUIPreferences.GPU_BOARD_MAXIMIZED, true);
    }

    @AfterEach
    void restoreBoardStyle() throws Exception {
        // The next suite's own window must not start while the host's thread still shuts GLFW down
        await(() -> !GpuHost.isOpen());
        var prefs = GUIPreferences.getInstance();
        for (String key : WINDOW_BOUNDS) { prefs.setValue(key, savedBounds.get(key)); }
        prefs.setValue(GUIPreferences.GPU_BOARD_MAXIMIZED, savedMaximized);
    }

    @Test
    void nativeGameplayRendersWithoutConstructingALegacy2DBoardOrInspector() throws Exception {
        var legacy2D = GpuTestSupport.onSwing(() -> mockConstruction(BoardView.class));
        var panels = GpuTestSupport.onSwing(() -> mockConstruction(BoardViewPanel.class));
        var inspector = GpuTestSupport.onSwing(() -> mockConstruction(UnitDisplayPanel.class));
        var inspectorDialog = GpuTestSupport.onSwing(() -> mockConstruction(UnitDisplayDialog.class));
        var weapons = GpuTestSupport.onSwing(() -> mockConstruction(WeaponPanel.class));
        var session = GpuTestSupport.onSwing(GpuGameplayStateTest.Session::create);
        JFrame owner = GpuTestSupport.onSwing(JFrame::new);
        CommonMenuBar menus = GpuTestSupport.onSwing(CommonMenuBar::getMenuBarForGame);
        ClientGUI gui = session.state().getClientgui();
        try {
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> {
                session.source().close();
                when(gui.getFrame()).thenReturn(owner);
                when(gui.getMenuBar()).thenReturn(menus);
                menus.setPhase(GamePhase.MOVEMENT);
                GpuBoardWindow.open(session.state(), gui::getMainPanel);
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> onGl(() -> frames() > 3));
            for (boolean isometric : new boolean[] { false, true }) {
                onGl(() -> {
                    GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
                    battle.boardCamera.setIsometric(isometric);
                    return null;
                });
                awaitNavigation();
                onGl(() -> {
                    File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(directory.isDirectory() || directory.mkdirs());
                    GpuBoardTestUi.capture(new File(directory, "native-state-" + (isometric ? "iso" : "top") + ".png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    return null;
                });
            }
            GpuTestSupport.onSwing(() -> {
                assertTrue(legacy2D.constructed().isEmpty());
                assertTrue(panels.constructed().isEmpty());
                assertTrue(inspector.constructed().isEmpty());
                assertTrue(inspectorDialog.constructed().isEmpty());
                assertTrue(weapons.constructed().isEmpty());
                assertFalse(session.state().isClosed());
                return null;
            });
        } finally {
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.closeFor(gui); return null; });
            await(() -> !GpuBoardWindow.isActiveFor(gui));
            GpuTestSupport.onSwing(() -> {
                session.close();
                owner.dispose();
                menus.die();
                panels.close();
                weapons.close();
                inspectorDialog.close();
                inspector.close();
                legacy2D.close();
                return null;
            });
        }
    }
    /** {@code gpuChoice} is View > Renderer's item for the current graphics. */
    private record ClientWindow(JFrame frame, CommonMenuBar menus, BoardView view, JMenuItem gpuChoice,
          UnitOverviewOverlay overview) { }

    private static void clickBoard(Coords coords, int button) {
        clickBoard(coords, button, 0);
    }

    private static void clickBoard(Coords coords, int button, int modifiers) {
        Vector3 point = ((GpuBattleView) GpuHost.sceneListener()).screenPosition(coords);
        var processor = Gdx.input.getInputProcessor();
        Input original = Gdx.input;
        Input keys = mock(Input.class);
        when(keys.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        Gdx.input = keys;
        try {
            processor.touchDown(Math.round(point.x), Math.round(point.y), 0, button);
            processor.touchUp(Math.round(point.x), Math.round(point.y), 0, button);
        } finally {
            Gdx.input = original;
        }
    }

    @Test
    void nativeCloseWhileEditorLoadsStopsWorkersAndAllowsReopening() throws Exception {
        var file = Files.createTempFile("native-editor-loading-", ".board2");
        megamek.common.board.BoardFile.save(Board.createEmptyBoard(64, 64), file);
        byte[] saved = Files.readAllBytes(file);
        JFrame launcher = GpuTestSupport.onSwing(() -> {
            var frame = new JFrame("Editor launcher");
            frame.setSize(400, 300);
            frame.setVisible(true);
            return frame;
        });
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                Application previous = Gdx.app;
                GpuTestSupport.onSwing(() -> { GpuBoardWindow.openEditor(launcher, file.toFile(), false); return null; });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> previewSource() != null);
                BoardSource source = previewSource();
                var workers = new AtomicReference<ExecutorService>();
                await(() -> onGl(() -> {
                    var view = (GpuBattleView) GpuHost.sceneListener();
                    var terrainField = GpuBattleView.class.getDeclaredField("terrain");
                    terrainField.setAccessible(true);
                    var terrain = (GpuTerrain) terrainField.get(view);
                    if (terrain == null || terrain.buildDetails().stream()
                          .noneMatch(status -> status.section() > 0)) { return false; }
                    assertFalse(terrain.ready(source.takeFrame().scene()), "Close must happen during map loading");
                    var field = GpuTerrain.class.getDeclaredField("detailWorker");
                    field.setAccessible(true);
                    workers.set((ExecutorService) field.get(terrain));
                    long window = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
                    var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                    GLFW.glfwSetWindowCloseCallback(window, callback);
                    callback.invoke(window);
                    return true;
                }));
                await(() -> GpuTestSupport.onSwing(launcher::isShowing));
                await(() -> !GpuHost.isOpen());
                assertTrue(workers.get().isTerminated(), "No terrain workers may outlive their native window");
                assertTrue(source.isClosed());
                assertNull(GpuTestSupport.onSwing(() -> savePrompt(launcher)), "Cancelling loading must not report a startup failure");
                assertArrayEquals(saved, Files.readAllBytes(file), "Closing the loader must not change the board file");
            }
        } finally {
            GpuTestSupport.onSwing(() -> {
                GpuBoardWindow.closeFor((ClientGUI) null);
                for (Window owned : launcher.getOwnedWindows()) { owned.dispose(); }
                launcher.dispose();
                return null;
            });
            Files.deleteIfExists(file);
        }
    }

    @Test
    void standaloneEditorOwnsItsCommandsAndNeverConstructsA2DView() throws Exception {
        var file = Files.createTempFile("native-editor-", ".board2");
        megamek.common.board.BoardFile.save(Board.createEmptyBoard(8, 8), file);
        JFrame launcher = GpuTestSupport.onSwing(() -> { var frame = new JFrame("Editor launcher"); frame.setSize(400, 300); frame.setVisible(true); return frame; });
        Application previous = Gdx.app;
        try {
            GpuTestSupport.onSwing(() -> {
                try (var views = mockConstruction(BoardView.class)) {
                    GpuBoardWindow.openEditor(launcher, file.toFile(), false);
                    assertTrue(views.constructed().isEmpty());
                }
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            // The loading window exists before the EDT attaches the standalone document.
            await(() -> previewSource() != null);
            BoardSource source = previewSource();
            await(() -> previewReady(source)); awaitNavigation();
            var field = GpuMapSource.class.getDeclaredField("editor"); field.setAccessible(true);
            var editor = (megamek.client.ui.boardeditor.BoardEditorSession) field.get(source);
            Coords first = new Coords(3, 3), second = new Coords(3, 4);
            GpuTestSupport.onSwing(() -> {
                editor.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                      megamek.client.ui.boardeditor.BoardEditorSession.Action.CHOOSE_BRUSH, "vegetation", "woods-1"), launcher);
                editor.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                      megamek.client.ui.boardeditor.BoardEditorSession.Action.TOOL, "PAINT"), launcher);
                source.refresh(); return null;
            });
            awaitNavigation();
            input(() -> editorStroke(List.of(first, second), 0, true));
            GpuTestSupport.onSwing(() -> {
                assertTrue(editor.board().getHex(first).containsTerrain(Terrains.WOODS),
                      () -> "Expected woods at the press: " + editor.snapshot());
                assertTrue(editor.board().getHex(second).containsTerrain(Terrains.WOODS));
                assertTrue(editor.dirty()); return null;
            });
            pressShortcut(KeyCommandBind.UNDO);
            assertFalse(GpuTestSupport.onSwing(() -> editor.board().getHex(second).containsTerrain(Terrains.WOODS)));
            pressShortcut(KeyCommandBind.REDO);
            assertTrue(GpuTestSupport.onSwing(() -> editor.board().getHex(second).containsTerrain(Terrains.WOODS)));
            input(() -> editorWheel(first, true, true, -.4f, -.6f));
            assertEquals(1, GpuTestSupport.onSwing(() -> editor.board().getHex(first).getLevel()));
            input(() -> {
                assertCameraDrag(Input.Buttons.RIGHT, false, false);
                assertCameraDrag(Input.Buttons.MIDDLE, false, true);
            });
            long generation = source.takeFrame().boardGeneration();
            GpuTestSupport.onSwing(() -> { editor.save(file); editor.open(file); source.refresh(); return null; });
            assertNotEquals(generation, source.takeFrame().boardGeneration());
            source.adjustEditorElevation(first, 9, generation); source.endEditorStroke();
            assertEquals(1, GpuTestSupport.onSwing(() -> editor.board().getHex(first).getLevel()));
            assertFalse(GpuTestSupport.onSwing(() -> launcher.isShowing()));
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); return null; });
            await(source::isClosed);
            await(() -> GpuTestSupport.onSwing(launcher::isShowing));
            assertTrue(GpuTestSupport.onSwing(() -> launcher.isShowing()), "Closing returns to the launcher, not a hidden 2D editor");
            assertTrue(GpuTestSupport.onSwing(() -> editor.game().getGameListeners().isEmpty()));
        } finally {
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); launcher.dispose(); return null; });
            Files.deleteIfExists(file);
        }
    }

    private static void assertCameraDrag(int button, boolean shift, boolean orbit) {
        BoardCamera camera = ((GpuBattleView) GpuHost.sceneListener()).boardCamera;
        Vector3 direction = camera.camera.direction.cpy();
        Vector3 focus = camera.focus.cpy();
        Input original = Gdx.input;
        Input keyboard = mock(Input.class);
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn(shift);
        Gdx.input = keyboard;
        try {
            int x = Gdx.graphics.getWidth() / 3, y = Gdx.graphics.getHeight() / 2;
            original.getInputProcessor().touchDown(x, y, 0, button);
            original.getInputProcessor().touchDragged(x + 80, y + 35, 0);
            original.getInputProcessor().touchUp(x + 80, y + 35, 0, button);
            assertEquals(orbit, !direction.epsilonEquals(camera.camera.direction, 0.001f),
                  "Orbit changes the viewing angle; pan preserves it");
            assertEquals(!orbit, !focus.epsilonEquals(camera.focus, 0.001f),
                  "Pan moves the focus; orbit preserves it");
        } finally {
            Gdx.input = original;
        }
    }

    /** Dispatch wheel events through the native input multiplexer, including fractional trackpad deltas. */
    private static void editorWheel(Coords coords, boolean control, boolean release, float... amounts) {
        Input original = Gdx.input;
        Input pointer = mock(Input.class);
        Vector3 screen = coords == null ? new Vector3(10, 10, 0)
              : ((GpuBattleView) GpuHost.sceneListener()).screenPosition(coords);
        when(pointer.getX()).thenReturn(Math.round(screen.x));
        when(pointer.getY()).thenReturn(Math.round(screen.y));
        when(pointer.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(control);
        Gdx.input = pointer;
        try {
            for (float amount : amounts) {
                original.getInputProcessor().scrolled(0, amount);
            }
            if (release) {
                original.getInputProcessor().keyUp(Input.Keys.CONTROL_LEFT);
            }
        } finally {
            Gdx.input = original;
        }
    }

    /** Real native picking and drag dispatch, including releases over the toolbar. */
    private static void editorStroke(List<Coords> hexes, int modifiers, boolean releaseOverToolbar) {
        Input original = Gdx.input;
        Input keyboard = mock(Input.class);
        when(keyboard.getInputProcessor()).thenReturn(original.getInputProcessor());
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
        Gdx.input = keyboard;
        try {
            GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
            Point last = null;
            for (Coords coords : hexes) {
                // Pick against the frame actually on screen, not a newer Swing snapshot awaiting rendering.
                Vector3 point = battle.screenPosition(coords);
                Point screen = new Point(Math.round(point.x), Math.round(point.y));
                var stage = GpuBoardTestUi.stage();
                var stagePoint = stage.screenToStageCoordinates(new com.badlogic.gdx.math.Vector2(screen.x, screen.y));
                assertNull(stage.hit(stagePoint.x, stagePoint.y, true), "Paint gesture must reach the board at " + coords);
                if (last == null) {
                    original.getInputProcessor().touchDown(screen.x, screen.y, 0, Input.Buttons.LEFT);
                } else {
                    original.getInputProcessor().touchDragged(screen.x, screen.y, 0);
                }
                last = screen;
            }
            original.getInputProcessor().touchUp(releaseOverToolbar ? 10 : last.x,
                  releaseOverToolbar ? 10 : last.y, 0, Input.Buttons.LEFT);
        } finally {
            Gdx.input = original;
        }
    }

    @Test
    void mapPreviewReusesItsWindowWhileBrowsingAndReleasesResourcesWhenClosed() throws Exception {
        AtomicInteger browserReturned = new AtomicInteger();
        JDialog browser = GpuTestSupport.onSwing(() -> {
            JFrame frame = new JFrame("Lobby");
            frame.setSize(800, 600);
            frame.setVisible(true);
            JDialog dialog = new JDialog(frame, "Map browser", true);
            dialog.setSize(600, 400);
            SwingUtilities.invokeLater(() -> {
                dialog.setVisible(true);
                browserReturned.incrementAndGet();
            });
            return dialog;
        });
        try {
            await(() -> GpuTestSupport.onSwing(browser::isShowing));
            assertEquals(0, browserReturned.get(), "The map browser starts a modal session");
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> {
                GpuBoardWindow.openPreview(browser, new File("data/boards/AGoAC Maps/16x17 Grassland 3.board"));
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> onGl(() -> frames() >= 5));
            BoardSource first = previewSource();
            GpuTestSupport.onSwing(() -> {
                assertTrue(browser.isShowing());
                assertEquals(0, browserReturned.get(), "Preview must not accept or dismiss the map picker");
                assertEquals(16, first.takeFrame().scene().width());
                assertEquals(17, first.takeFrame().scene().height());
                assertTrue(first.takeFrame().scene().units().isEmpty());
                return null;
            });
            onGl(() -> {
                File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                assertTrue(output.isDirectory() || output.mkdirs());
                GpuBoardTestUi.capture(new File(output, "map-browser-preview.png"));
                long window = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
                var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                GLFW.glfwSetWindowCloseCallback(window, callback);
                callback.invoke(window);
                return null;
            });
            await(() -> !GpuBoardWindow.isActiveFor(null));
            assertTrue(first.isClosed());
            assertTrue(GpuTestSupport.onSwing(browser::isShowing));
            assertEquals(0, browserReturned.get());

            Application firstApplication = Gdx.app;
            GpuTestSupport.onSwing(() -> {
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(6, 8));
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != firstApplication);
            await(() -> onGl(() -> frames() >= 5));
            BoardSource second = previewSource();
            Application secondApplication = Gdx.app;
            GpuTestSupport.onSwing(() -> {
                assertEquals(6, second.takeFrame().scene().width());
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(8, 10));
                return null;
            });
            await(() -> previewReady(second));
            assertSame(secondApplication, Gdx.app, "Browsing another map retains the GL context and assets");
            assertFalse(second.isClosed());
            BoardSource third = previewSource();
            assertSame(second, third, "The same source follows the authoritative game's new board");
            long generation = third.takeFrame().boardGeneration();
            GpuTestSupport.onSwing(() -> {
                // Same dimensions still mean a new board. Rapid choices must converge to the final one.
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(8, 10));
                Board last = Board.createEmptyBoard(8, 10);
                last.setHex(3, 4, new Hex(3));
                GpuBoardWindow.openPreview(browser, last);
                return null;
            });
            await(() -> third.takeFrame().boardGeneration() > generation && previewReady(third));
            assertSame(secondApplication, Gdx.app);
            assertEquals(3, third.takeFrame().scene().tile(new Coords(3, 4)).elevation());
            GpuTestSupport.onSwing(() -> {
                assertEquals(8, third.takeFrame().scene().width());
                assertEquals(0, browserReturned.get());
                browser.setVisible(false);
                return null;
            });
            await(() -> !GpuBoardWindow.isActiveFor(null));
            assertTrue(third.isClosed());
        } finally {
            GpuTestSupport.onSwing(() -> { browser.getOwner().dispose(); return null; });
        }
    }

    @Test
    void lobbyPreviewUsesAndFollowsPlanetaryConditionsWithoutReplacingItsBoard() throws Exception {
        JFrame owner = GpuTestSupport.onSwing(() -> {
            var frame = new JFrame("Lobby preview conditions");
            frame.setSize(600, 400);
            frame.setVisible(true);
            return frame;
        });
        Game game = new Game();
        game.setPhase(GamePhase.LOUNGE);
        Board board = Board.createEmptyBoard(6, 5);
        game.setBoard(board);
        Client client = mock(Client.class);
        ClientGUI gui = mock(ClientGUI.class);
        when(client.getGame()).thenReturn(game);
        when(gui.getClient()).thenReturn(client);
        when(gui.getFrame()).thenReturn(owner);
        try {
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> {
                var conditions = new PlanetaryConditions();
                conditions.setLight(Light.FULL_MOON);
                game.setPlanetaryConditions(conditions);
                LobbyServices.of(gui);
                LobbyBoardPreviews.preview3D(gui);
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> previewSource() != null);
            BoardSource source = previewSource();
            await(() -> previewReady(source));
            awaitNavigation();
            var night = source.takeFrame().scenarioAtmosphere();
            assertTrue(night.hour() >= 20 || night.hour() <= 4, "the full-map preview starts with the lobby's night");
            long generation = source.takeFrame().boardGeneration();
            BoardCamera camera = onGl(() -> {
                BoardCamera result = ((GpuBattleView) GpuHost.sceneListener()).boardCamera;
                result.setIsometric(false);
                result.zoom(.7f);
                result.pan(20, 15);
                return result;
            });
            float zoom = onGl(() -> camera.camera.zoom);
            Vector3 focus = onGl(() -> camera.focus.cpy());
            GpuTestSupport.onSwing(() -> {
                var conditions = new PlanetaryConditions(game.getPlanetaryConditions());
                conditions.setLight(Light.DAY);
                conditions.setWeather(Weather.HEAVY_RAIN);
                conditions.setFog(Fog.FOG_HEAVY);
                // The same game event as the server's conditions packet drives the lobby services' refresh.
                game.setPlanetaryConditions(conditions);
                return null;
            });
            await(() -> source.takeFrame().scenarioAtmosphere().effects().rain() > 0);
            await(() -> onGl(() -> {
                var field = GpuBattleView.class.getDeclaredField("atmosphere");
                field.setAccessible(true);
                var atmosphere = (GpuAtmosphere) field.get(GpuHost.sceneListener());
                return atmosphere.lighting().daylight() > .9f;
            }));
            var changed = source.takeFrame().scenarioAtmosphere();
            assertTrue(changed.fog() > 0);
            assertEquals(0, night.effects().rain(), "previous render snapshots remain immutable");
            assertEquals(generation, source.takeFrame().boardGeneration(), "conditions don't replace the map");
            assertTrue(GpuTestSupport.onSwing(() -> GpuBoardWindow.showsPreview(owner, board)));
            onGl(() -> {
                assertEquals(0, camera.tilt(), .001f);
                assertEquals(zoom, camera.camera.zoom, .001f);
                assertTrue(focus.epsilonEquals(camera.focus, .001f), "live conditions preserve the camera");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                return null;
            });
        } finally {
            GpuTestSupport.onSwing(() -> {
                LobbyServices.forget(gui);
                owner.dispose();
                return null;
            });
            await(() -> !GpuBoardWindow.isActiveFor(null));
        }
    }

    @Test
    void startingTheGameClosesItsMapPreviewBeforeOpeningTheBattleWindow() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(Board.createEmptyBoard(10, 10))) {
            ClientWindow ui = GpuTestSupport.onSwing(() -> createClientWindow(fixture));
            try {
                Application previous = Gdx.app;
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.openPreview(ui.frame(), fixture.game.getBoard());
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != previous);
                awaitBoardFrames(5);
                BoardSource preview = previewSource();
                Application previewApplication = Gdx.app;
                GpuTestSupport.onSwing(() -> {
                    assertTrue(preview instanceof GpuMapSource, "Preview uses the model directly");
                    assertTrue(preview.takeFrame().scene().units().isEmpty());
                    GpuBoardWindow.open(ui.view().getClientState(), () -> fixture.panel);
                    ui.frame().setVisible(false);
                    return null;
                });
                await(() -> GpuBoardWindow.isActiveFor(ui.view().getClientgui()));
                await(() -> Gdx.app != null && Gdx.app != previewApplication);
                awaitBoardFrames(5);
                assertTrue(preview.isClosed());
                assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()));
                assertSame(fixture.game, ((GpuBoardSource) previewSource()).currentView().game);
            } finally {
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientgui());
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    return null;
                });
            }
        }
    }

    @Test
    void previewMenuOpensCurrentFileIn3DEditorWithoutChangingPreviewBoard() throws Exception {
        assertPreviewEditorHandoff(false, false, false);
    }

    @Test
    void previewMenuEditsGeneratedReplacementWithoutKeepingPreviousFile() throws Exception {
        assertPreviewEditorHandoff(true, false, false);
    }

    @Test
    void savedPreviewReturnsToTheLobbyWithTheSavedMap() throws Exception {
        assertPreviewEditorHandoff(true, true, false);
    }

    @Test
    void savedBrowserPreviewDismissesTheModalBrowserAndReturnsDirectlyToLobby() throws Exception {
        assertPreviewEditorHandoff(false, true, true);
    }

    private static void assertPreviewEditorHandoff(boolean replaceFile, boolean saveBeforeClose, boolean modalBrowser)
          throws Exception {
        var directory = Files.createTempDirectory("preview-editor-handoff-");
        var file = directory.resolve("preview.board2");
        var savedFile = file.resolveSibling(file.getFileName() + "-edited.board2");
        var originalBoards = Configuration.boardsDir();
        BoardFile.save(Board.createEmptyBoard(8, 8), file);
        byte[] saved = Files.readAllBytes(file);
        Coords at = new Coords(3, 4);
        var client = mock(ClientGUI.class);
        var lobby = client;
        doAnswer(call -> {
            assertTrue(SwingUtilities.isEventDispatchThread(), "The lobby refresh belongs to Swing");
            return null;
        }).when(lobby).boardEditorClosed(any(), anyBoolean());
        JFrame launcher = GpuTestSupport.onSwing(() -> {
            var frame = new JFrame("Preview launcher");
            frame.getRootPane().putClientProperty(ClientGUI.class, client);
            frame.setSize(400, 300);
            frame.setVisible(true);
            return frame;
        });
        try {
            AtomicReference<DialogResult> browserResult = new AtomicReference<>();
            Window previewOwner = launcher;
            if (modalBrowser) {
                Configuration.setBoardsDir(directory.toFile());
                BoardClassifier.invalidate();
                previewOwner = GpuTestSupport.onSwing(() -> {
                    var browser = new AdvancedSearchMapDialog(launcher);
                    browser.setSize(800, 600);
                    SwingUtilities.invokeLater(() -> browserResult.set(browser.showDialog()));
                    return browser;
                });
                Window browser = previewOwner;
                await(() -> GpuTestSupport.onSwing(browser::isShowing));
                assertNull(browserResult.get(), "The real map browser is in its modal showDialog call");
            }
            Window owner = previewOwner;
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.openPreview(owner, file.toFile()); return null; });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> previewSource() != null);
            BoardSource preview = previewSource();
            await(() -> previewReady(preview));
            Application previewApplication = Gdx.app;
            if (replaceFile) {
                long generation = preview.takeFrame().boardGeneration();
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.openPreview(launcher, Board.createEmptyBoard(7, 9));
                    return null;
                });
                await(() -> preview.takeFrame().boardGeneration() > generation && previewReady(preview));
                assertSame(previewApplication, Gdx.app, "Browsing retains the existing preview window");
            }
            var gameField = GpuMapSource.class.getDeclaredField("game");
            gameField.setAccessible(true);
            Board shown = GpuTestSupport.onSwing(() -> {
                Board board = ((megamek.common.game.Game) gameField.get(preview)).getBoard();
                // The handoff must copy the displayed state, even when it differs from the file on disk.
                board.setHex(at, new Hex(3));
                preview.refresh();
                return board;
            });
            await(() -> previewReady(preview));
            awaitNavigation();
            input(() -> GpuBoardTestUi.click("utility-menu"));
            onGl(() -> { GpuBoardTestUi.click("/boardEdit3D"); return null; });
            await(() -> Gdx.app != null && Gdx.app != previewApplication);
            await(() -> previewSource() != null);
            BoardSource editorSource = previewSource();
            await(() -> previewReady(editorSource));
            awaitNavigation();
            var editorField = GpuMapSource.class.getDeclaredField("editor");
            editorField.setAccessible(true);
            BoardEditorSession editor = (BoardEditorSession) editorField.get(editorSource);
            assertNotNull(editor, "The preview menu opens the standalone editor");
            assertTrue(preview.isClosed(), "The handoff releases the old preview");
            if (modalBrowser) {
                await(() -> browserResult.get() != null);
                assertEquals(DialogResult.CANCELLED, browserResult.get(),
                      "Editing dismisses the browser without applying its old selection");
                assertFalse(GpuTestSupport.onSwing(owner::isDisplayable), "The browser cannot reopen without its original caller");
            }
            GpuTestSupport.onSwing(() -> {
                assertNotSame(shown, editor.board(), "The editor owns a separate document");
                assertEquals(shown.getWidth(), editor.board().getWidth());
                assertEquals(shown.getHeight(), editor.board().getHeight());
                assertEquals(3, editor.board().getHex(at).getLevel());
                assertEquals(replaceFile ? null : file, editor.path(),
                      "Only the currently displayed file supplies the editor's save path");
                assertEquals(replaceFile, editor.dirty(), "Generated maps have never been saved");
                assertFalse(launcher.isShowing());
                return null;
            });

            input(() -> editorWheel(at, true, true, -1));
            GpuTestSupport.onSwing(() -> {
                assertEquals(4, editor.board().getHex(at).getLevel(), "Native input edits the opened map");
                assertEquals(3, shown.getHex(at).getLevel(), "Editing must not mutate the preview's board");
                assertTrue(editor.dirty());
                return null;
            });
            assertArrayEquals(saved, Files.readAllBytes(file), "Opening and editing do not implicitly save the file");
            if (saveBeforeClose) {
                GpuTestSupport.onSwing(() -> { editor.save(savedFile); editorSource.refresh(); return null; });
                verify(lobby, never()).boardEditorClosed(any(), anyBoolean());
                input(() -> GpuBoardTestUi.click("utility-menu"));
                onGl(() -> { GpuBoardTestUi.click("/close"); return null; });
            } else {
                pressShortcut(KeyCommandBind.UNDO);
                assertEquals(replaceFile, GpuTestSupport.onSwing(editor::dirty));
                GpuTestSupport.onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); return null; });
            }
            await(editorSource::isClosed);
            await(() -> GpuTestSupport.onSwing(launcher::isShowing));
            if (saveBeforeClose) {
                verify(lobby).boardEditorClosed(savedFile, replaceFile);
                assertEquals(4, BoardFile.read(savedFile).getHex(at).getLevel());
            } else {
                verify(lobby, never()).boardEditorClosed(any(), anyBoolean());
            }
            if (modalBrowser) {
                assertFalse(GpuTestSupport.onSwing(owner::isShowing), "Closing the editor returns directly to the lobby");
                assertFalse(GpuTestSupport.onSwing(owner::isDisplayable), "The dismissed browser remains disposed");
            }
            assertTrue(GpuTestSupport.onSwing(() -> editor.game().getGameListeners().isEmpty()));
        } finally {
            GpuTestSupport.onSwing(() -> {
                GpuBoardWindow.closeFor((ClientGUI) null);
                for (Window owned : launcher.getOwnedWindows()) { owned.dispose(); }
                launcher.dispose();
                return null;
            });
            if (modalBrowser) {
                Configuration.setBoardsDir(originalBoards);
                BoardClassifier.invalidate();
            }
            Files.deleteIfExists(file);
            Files.deleteIfExists(savedFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void previewMenuSwitchesBothWaysWithoutRegeneratingTheBoard() throws Exception {
        JFrame launcher = GpuTestSupport.onSwing(() -> { var frame = new JFrame("Preview launcher"); frame.setSize(400, 300); frame.setVisible(true); return frame; });
        Board board = Board.createEmptyBoard(7, 9);
        board.setHex(3, 4, new Hex(3));
        try {
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.openPreview(launcher, board); return null; });
            await(() -> Gdx.app != null && Gdx.app != previous);
            for (int round = 0; round < 2; round++) {
                await(() -> previewSource() != null);
                BoardSource source = previewSource();
                await(() -> previewReady(source)); awaitNavigation();
                // Each round opens a new window, whose View and Renderer groups start closed.
                input(() -> GpuBoardTestUi.click("utility-menu"));
                input(() -> GpuBoardTestUi.click("/view"));
                input(() -> GpuBoardTestUi.click("/view/viewRenderer"));
                captureMenu("preview-view-switch-menu.png");
                onGl(() -> { GpuBoardTestUi.click("/view/viewRenderer/viewLegacy2D"); return null; });
                await(() -> GpuTestSupport.onSwing(() -> mapWindow("board-preview-2d") != null));
                assertTrue(source.isClosed());
                JDialog legacy2D = GpuTestSupport.onSwing(() -> (JDialog) mapWindow("board-preview-2d"));
                captureLegacy2D(legacy2D, "preview-view", "preview-view-switch-2d.png");
                Application nativeView = Gdx.app;
                String graphics = GUIPreferences.getInstance().getBoardGraphics() == BoardGraphics.CLASSIC
                      ? ClientGUI.VIEW_GRAPHICS_CLASSIC : ClientGUI.VIEW_GRAPHICS_ENHANCED;
                GpuTestSupport.onSwing(() -> {
                    assertTrue(java.util.Arrays.stream(legacy2D.getJMenuBar().getMenu(0).getMenuComponents())
                          .anyMatch(item -> item instanceof JMenuItem entry
                                && entry.getText().equals(Messages.getString("CommonMenuBar.PrintMenu"))),
                          "The Legacy 2D preview's File menu offers Print, as the 3D preview's Menu does");
                    JMenu view = (JMenu) namedButton(legacy2D, "preview-view");
                    JMenu renderer = (JMenu) namedButton(view.getPopupMenu(), "preview-renderer");
                    assertTrue(rendererItem(renderer, ClientGUI.VIEW_LEGACY_2D).isSelected());
                    rendererItem(renderer, graphics).doClick(0);
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != nativeView);
                await(() -> previewSource() != null);
                BoardSource resumed = previewSource();
                await(() -> previewReady(resumed));
                assertFalse(GpuTestSupport.onSwing(legacy2D::isDisplayable), "Each switch releases the old Legacy 2D renderer");
                var field = GpuMapSource.class.getDeclaredField("game"); field.setAccessible(true);
                assertSame(board, ((megamek.common.game.Game) field.get(resumed)).getBoard(), "Switching keeps the exact preview board");
                assertEquals(3, resumed.takeFrame().scene().tile(new Coords(3, 4)).elevation());
            }
        } finally {
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); launcher.dispose(); return null; });
        }
    }

    /**
     * The lobby's 3D preview is 3D only: its View > Renderer has no Legacy 2D, a lobby update replaces its board, and the
     * lobby closes it.
     */
    @Test
    void lobbyPreviewIsThreeDOnlyAndFollowsTheLobby() throws Exception {
        JFrame lobby = GpuTestSupport.onSwing(() -> { var frame = new JFrame("Lobby"); frame.setSize(800, 600); frame.setVisible(true); return frame; });
        Board board = Board.createEmptyBoard(8, 10);
        try {
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.openLobbyPreview(lobby, board, new PlanetaryConditions()); return null; });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> previewSource() != null);
            BoardSource source = previewSource();
            await(() -> previewReady(source)); awaitNavigation();
            assertTrue(GpuTestSupport.onSwing(() -> GpuBoardWindow.showsPreview(lobby, board)));
            input(() -> GpuBoardTestUi.click("utility-menu"));
            input(() -> GpuBoardTestUi.click("/view"));
            input(() -> GpuBoardTestUi.click("/view/viewRenderer"));
            assertNull(onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("/view/viewRenderer/viewLegacy2D")),
                  "The lobby's preview offers no Legacy 2D");
            assertNotNull(onGl(() -> GpuBoardTestUi.stage().getRoot()
                  .findActor("/view/viewRenderer/" + ClientGUI.VIEW_GRAPHICS_CLASSIC)));

            Board update = Board.createEmptyBoard(6, 6);
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.updatePreview(lobby, update); return null; });
            assertTrue(GpuTestSupport.onSwing(() -> GpuBoardWindow.showsPreview(lobby, update)), "A lobby update replaces the board");
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.closePreview(lobby); return null; });
            await(() -> !GpuBoardWindow.isActiveFor(null));
        } finally {
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); lobby.dispose(); return null; });
        }
    }
    /**
     * The editor's 2D Preview is one live window beside the running editor, owned by the editor's owner window: it shows
     * the session's board as the 2D board draws it, repaints what the session paints and undoes and follows a replaced
     * document without being reopened, comes to the front on a second request, and closes with the editor.
     */
    @Test
    void editorMenuOpensOneLive2DPreviewThatClosesWithTheEditor() throws Exception {
        File file = new File(megamek.common.Configuration.boardsDir(), "unofficial/VictorMorson/48x48.board");
        Coords at = new Coords(2, 2);
        JFrame launcher = GpuTestSupport.onSwing(() -> { var frame = new JFrame("Editor launcher"); frame.setSize(400, 300); frame.setVisible(true); return frame; });
        try {
            Application previous = Gdx.app;
            GpuTestSupport.onSwing(() -> { GpuBoardWindow.openEditor(launcher, file, false); return null; });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> previewSource() != null);
            BoardSource source = previewSource();
            var editorField = GpuMapSource.class.getDeclaredField("editor"); editorField.setAccessible(true);
            BoardEditorSession session = (BoardEditorSession) editorField.get(source);
            await(() -> previewReady(source)); awaitNavigation();
            input(() -> GpuBoardTestUi.click("utility-menu"));
            onGl(() -> { GpuBoardTestUi.click("/boardPreview2D"); return null; });
            await(() -> GpuTestSupport.onSwing(() -> mapWindow("board-editor-preview-2d") != null));
            JDialog preview = GpuTestSupport.onSwing(() -> (JDialog) mapWindow("board-editor-preview-2d"));
            assertFalse(source.isClosed(), "The editor keeps running beside its 2D Preview");
            assertSame(launcher, preview.getOwner(), "The editor's owner window owns the 2D Preview");
            assertFalse(preview.isModal());
            BoardView view = GpuTestSupport.onSwing(() -> boardView(preview));
            assertSame(session.board(), GpuTestSupport.onSwing(view::getBoard), "It shows the session's own board, not a copy");
            await(() -> GpuTestSupport.onSwing(view::isTileImagesLoaded));
            awaitNavigation();
            captureEditorWithPreview(preview, "b9-2d-preview.png");

            // Painting through the session repaints the hex in 2D; Undo paints it back.
            Rectangle hex = GpuTestSupport.onSwing(() -> hexArea(view, at));
            assertNotNull(hex, "The painted hex is in view");
            BufferedImage before = GpuTestSupport.onSwing(() -> viewportImage(view));
            assertFalse(GpuTestSupport.onSwing(() -> session.board().getHex(at).containsTerrain(Terrains.WATER)));
            GpuTestSupport.onSwing(() -> {
                session.pointer(at, 0, 0, false);
                session.command(new BoardEditorSession.Command(BoardEditorSession.Action.TERRAIN, "water", "2"), launcher);
                assertTrue(session.board().getHex(at).containsTerrain(Terrains.WATER));
                return null;
            });
            await(() -> GpuTestSupport.onSwing(() -> changedPixels(before, viewportImage(view), hex) > hex.width * hex.height / 4));
            GpuTestSupport.onSwing(() -> { session.command(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), launcher); return null; });
            await(() -> GpuTestSupport.onSwing(() -> changedPixels(before, viewportImage(view), hex) == 0));

            // A second request brings the same window to the front instead of opening another.
            input(() -> GpuBoardTestUi.click("utility-menu"));
            onGl(() -> { GpuBoardTestUi.click("/boardPreview2D"); return null; });
            GpuTestSupport.onSwing(() -> null);
            assertEquals(1L, GpuTestSupport.onSwing(() -> Arrays.stream(Window.getWindows())
                  .filter(window -> "board-editor-preview-2d".equals(window.getName()) && window.isDisplayable()).count()));
            assertSame(preview, GpuTestSupport.onSwing(() -> mapWindow("board-editor-preview-2d")));

            // A replaced document (New, Open) is the game's new board, which the view follows.
            GpuTestSupport.onSwing(() -> {
                session.command(new BoardEditorSession.Command(BoardEditorSession.Action.NEW, "12x9"), launcher);
                assertSame(session.board(), view.getBoard());
                assertEquals(12, view.getBoard().getWidth());
                return null;
            });

            // Closing the editor's window closes the 2D Preview and releases its view.
            onGl(() -> {
                long window = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
                var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                GLFW.glfwSetWindowCloseCallback(window, callback);
                callback.invoke(window);
                return null;
            });
            await(source::isClosed);
            await(() -> GpuTestSupport.onSwing(launcher::isShowing));
            assertFalse(GpuTestSupport.onSwing(preview::isDisplayable), "The 2D Preview closes with the editor");
            assertTrue(GpuTestSupport.onSwing(() -> session.game().getGameListeners().isEmpty()), "Its board view no longer listens");
        } finally {
            GpuTestSupport.onSwing(() -> {
                GpuBoardWindow.closeFor((ClientGUI) null);
                for (Window owned : launcher.getOwnedWindows()) { owned.dispose(); }
                launcher.dispose(); return null;
            });
        }
    }

    /** The board view a 2D window shows. */
    private static BoardView boardView(java.awt.Container parent) throws ReflectiveOperationException {
        for (var component : parent.getComponents()) {
            if (component instanceof BoardViewPanel panel) {
                var field = BoardViewPanel.class.getDeclaredField("boardView");
                field.setAccessible(true);
                return (BoardView) field.get(panel);
            }
            if (component instanceof java.awt.Container child) {
                BoardView view = boardView(child);
                if (view != null) { return view; }
            }
        }
        return null;
    }

    /** What the view's viewport shows now, painted from the board's current state. */
    private static BufferedImage viewportImage(BoardView view) {
        var viewport = ((javax.swing.JScrollPane) view.getComponent(true)).getViewport();
        var image = new BufferedImage(viewport.getWidth(), viewport.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { viewport.printAll(graphics); } finally { graphics.dispose(); }
        return image;
    }

    /** The viewport pixels the view maps to {@code at}, as their bounding box, or null when the hex is out of view. */
    private static Rectangle hexArea(BoardView view, Coords at) {
        var viewport = ((javax.swing.JScrollPane) view.getComponent(true)).getViewport();
        Point origin = viewport.getViewPosition();
        Rectangle area = null;
        // Every 4th pixel is enough to find a hex; the box is the samples' bounding box.
        for (int y = 0; y < viewport.getHeight(); y += 4) {
            for (int x = 0; x < viewport.getWidth(); x += 4) {
                if (at.equals(view.getCoordsAt(new Point(x + origin.x, y + origin.y)))) {
                    area = area == null ? new Rectangle(x, y, 1, 1) : area.union(new Rectangle(x, y, 1, 1));
                }
            }
        }
        return area;
    }

    private static int changedPixels(BufferedImage before, BufferedImage after, Rectangle area) {
        int changed = 0;
        for (int y = area.y; y < area.y + area.height; y++) {
            for (int x = area.x; x < area.x + area.width; x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) { changed++; }
            }
        }
        return changed;
    }

    /**
     * The editor's frame (a native frame grab) with the 2D Preview window's own paint at its lower right. A screen grab
     * would also show whatever else is on the desktop.
     */
    private static void captureEditorWithPreview(Window preview, String name) throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        File frame = File.createTempFile("editor-frame-", ".png");
        try {
            onGl(() -> GpuBoardTestUi.capture(frame));
            BufferedImage editor = ImageIO.read(frame);
            // Swing draws at the display's scale, as the native frame does: paint the window in device pixels.
            double scale = GpuTestSupport.onSwing(() -> preview.getGraphicsConfiguration().getDefaultTransform().getScaleX());
            BufferedImage window = GpuTestSupport.onSwing(() -> {
                var image = new BufferedImage((int) Math.round(preview.getWidth() * scale),
                      (int) Math.round(preview.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { graphics.scale(scale, scale); preview.printAll(graphics); } finally { graphics.dispose(); }
                return image;
            });
            var image = new BufferedImage(editor.getWidth(), editor.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < editor.getHeight(); y++) {
                // The frame grab's alpha is not what the window shows; the colour is.
                for (int x = 0; x < editor.getWidth(); x++) { image.setRGB(x, y, editor.getRGB(x, y) | 0xFF000000); }
            }
            var graphics = image.createGraphics();
            try {
                int x = image.getWidth() - window.getWidth() - 24, y = image.getHeight() - window.getHeight() - 24;
                graphics.drawImage(window, x, y, null);
                graphics.setColor(java.awt.Color.GRAY);
                graphics.drawRect(x - 1, y - 1, window.getWidth() + 1, window.getHeight() + 1);
            } finally { graphics.dispose(); }
            ImageIO.write(image, "png", new File(output, name));
        } finally {
            Files.deleteIfExists(frame.toPath());
        }
    }

    private static Window mapWindow(String name) {
        return Arrays.stream(Window.getWindows()).filter(window -> name.equals(window.getName()) && window.isShowing())
              .findFirst().orElse(null);
    }

    private static void captureLegacy2D(Window window, String buttonName, String filename) throws Exception {
        GpuTestSupport.onSwing(() -> {
            AbstractButton button = namedButton(window, buttonName);
            assertTrue(button.isShowing(), "The return control is visible in the Legacy 2D view");
            assertTrue(button.getVisibleRect().width > 0 && button.getVisibleRect().height > 0);
            var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { window.printAll(graphics); } finally { graphics.dispose(); }
            File output = new File("build/gpu-board-review");
            output.mkdirs();
            ImageIO.write(image, "png", new File(output, filename));
            return null;
        });
    }

    private static AbstractButton namedButton(java.awt.Container parent, String name) {
        for (var component : parent.getComponents()) {
            if (component instanceof AbstractButton button && name.equals(button.getName())) { return button; }
            if (component instanceof java.awt.Container child) {
                AbstractButton button = namedButton(child, name);
                if (button != null) { return button; }
            }
        }
        return null;
    }

    private static boolean previewReady(BoardSource source) throws Exception {
        return onGl(() -> {
            var view = (GpuBattleView) GpuHost.sceneListener();
            var terrainField = GpuBattleView.class.getDeclaredField("terrain");
            terrainField.setAccessible(true);
            var terrain = (GpuTerrain) terrainField.get(view);
            var generationField = GpuBattleView.class.getDeclaredField("boardGeneration");
            generationField.setAccessible(true);
            return terrain != null && generationField.getLong(view) == source.takeFrame().boardGeneration()
                  && terrain.ready(source.takeFrame().scene());
        });
    }

    private static BoardSource previewSource() throws Exception {
        return onGl(() -> {
            var field = GpuBattleView.class.getDeclaredField("source");
            field.setAccessible(true);
            return (BoardSource) field.get(GpuHost.sceneListener());
        });
    }

    @Test
    void startsDirectlyInThreeDimensionsWithoutConstructingTheLegacy2DViewport() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = GpuTestSupport.onSwing(() -> createClientWindow(fixture, false));
            try {
                GpuTestSupport.onSwing(() -> {
                    ui.view().centerOn(fixture.entity);
                    assertEquals(fixture.entity.getId(), ui.view().getClientState().getCenterRequest().entityId());
                    assertNull(ui.view().getPanel().getParent());
                    return null;
                });
                openNative(ui);
                await(() -> onGl(() -> frames() >= 5));
                GpuTestSupport.onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    assertNull(ui.view().getPanel().getParent());
                    assertFalse(ui.frame().isVisible());
                    assertTrue(GpuBoardWindow.isActiveFor(ui.view().getClientgui()));
                    GpuBoardWindow.showLegacy2D(ui.view().getClientgui());
                    return null;
                });
                await(() -> GpuTestSupport.onSwing(() -> ui.frame().isVisible()));
                GpuTestSupport.onSwing(() -> {
                    verify(ui.view()).getComponent();
                    assertTrue(ui.view().getPanel().isShowing(), "The legacy viewport is created on explicit return");
                    return null;
                });
            } finally {
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    ui.frame().dispose();
                    ui.view().dispose();
                    GUIPreferences.getInstance().removePreferenceChangeListener(ui.menus());
                    return null;
                });
            }
        }
    }


    @Test
    void nativeStartupShowsPhaseMessagesBeforeTheFirstMapArrives() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = GpuTestSupport.onSwing(() -> createClientWindow(fixture, false));
            ClientGUI gui = ui.view().getClientgui();
            AtomicReference<JComponent> phase = new AtomicReference<>();
            try {
                Application previous = Gdx.app;
                GpuTestSupport.onSwing(() -> {
                    phase.set(new StartingScenarioPanel());
                    when(gui.getCurrentBoardState()).thenReturn(Optional.empty());
                    GpuBoardWindow.open(gui, phase::get);
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> onGl(() -> {
                    var label = GpuBoardTestUi.stage().getRoot().findActor("board-loading-message");
                    return label instanceof com.badlogic.gdx.scenes.scene2d.ui.Label message
                          && message.getText().toString().contains("Starting Scenario");
                }));
                assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()));
                long window = onGl(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                GpuTestSupport.onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    phase.set(new WaitingForServerPanel());
                    return null;
                });
                await(() -> onGl(() -> {
                    com.badlogic.gdx.scenes.scene2d.ui.Label message = GpuBoardTestUi.stage().getRoot()
                          .findActor("board-loading-message");
                    return message.getText().toString().equals(Messages.getString("ClientGUI.waitingOnTheServer"));
                }));
                GpuTestSupport.onSwing(() -> {
                    doReturn(Optional.of(ui.view().getClientState())).when(gui).getCurrentBoardState();
                    return null;
                });
                await(() -> onGl(() -> frames() >= 5));
                // With the board, the HUD's phase header names the server wait until a phase display replaces it.
                String waiting = UiTheme.upper(Messages.getString("ClientGUI.waitingOnTheServer"));
                await(() -> onGl(() -> phaseHeaderTexts().contains(waiting)));
                onGl(() -> {
                    assertEquals(window, ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                    assertNull(GpuBoardTestUi.stage().getRoot().findActor("board-loading-message"));
                    return null;
                });
                GpuTestSupport.onSwing(() -> { phase.set(fixture.panel); return null; });
                await(() -> onGl(() -> !phaseHeaderTexts().contains(waiting)));
                GpuTestSupport.onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    assertFalse(ui.frame().isVisible());
                    return null;
                });
                BoardView replacement = GpuTestSupport.onSwing(() -> {
                    BoardView view = new BoardView(fixture.game, null, gui, 0);
                    view.setLocalPlayer(fixture.player);
                    ui.view().dispose();
                    fixture.view.close();
                    doReturn(Optional.of(view.getClientState())).when(gui).getCurrentBoardState();
                    return view;
                });
                try {
                    assertTrue(GpuBoardWindow.isActiveFor(gui), "Replacing the map must not dispose the client's window");
                    onGl(() -> {
                        assertEquals(window, ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                        return null;
                    });
                    Application restarting = Gdx.app;
                    GpuTestSupport.onSwing(() -> {
                        GpuBoardWindow.showLegacy2D(gui);
                        GpuBoardWindow.open(gui, phase::get);
                        return null;
                    });
                    await(() -> Gdx.app != null && Gdx.app != restarting);
                    await(() -> onGl(() -> frames() > 0));
                    assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()), "A queued native restart never flashes 2D");
                } finally {
                    GpuTestSupport.onSwing(() -> {
                        GpuBoardWindow.closeFor(gui);
                        replacement.dispose();
                        return null;
                    });
                }
            } finally {
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.closeFor(gui);
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    return null;
                });
            }
        }
    }

    @Test
    void nativeCloseUsesTheSavePromptAndCancellationKeepsTheSameWindow() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean noSaveNag = preferences.getNoSaveNag();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = GpuTestSupport.onSwing(() -> createClientWindow(fixture, false));
            ClientGUI gui = ui.view().getClientgui();
            AtomicInteger quit = new AtomicInteger();
            try {
                GpuTestSupport.onSwing(() -> {
                    preferences.setValue(GUIPreferences.ADVANCED_NO_SAVE_NAG, false);
                    doCallRealMethod().when(gui).handleExit();
                    doAnswer(invocation -> {
                        quit.incrementAndGet();
                        GpuBoardWindow.closeFor(gui);
                        ui.frame().dispose();
                        return null;
                    }).when(gui).die();
                    return null;
                });
                openNative(ui);
                long window = onGl(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                for (int response : new int[] { JOptionPane.CANCEL_OPTION, JOptionPane.YES_OPTION, JOptionPane.NO_OPTION }) {
                    onGl(() -> {
                        // Invoke LWJGL's installed OS close callback; closeWindow() would bypass its confirmation hook.
                        var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                        GLFW.glfwSetWindowCloseCallback(window, callback);
                        callback.invoke(window);
                        return null;
                    });
                    await(() -> GpuTestSupport.onSwing(() -> savePrompt(ui.frame()) != null));
                    GpuTestSupport.onSwing(() -> {
                        JOptionPane prompt = savePrompt(ui.frame());
                        assertEquals(Messages.getString("ClientGUI.gameSaveDialogMessage"), prompt.getMessage());
                        assertTrue(GpuBoardWindow.raisedOverBoard(
                              (java.awt.Dialog) SwingUtilities.getWindowAncestor(prompt)));
                        prompt.setValue(response);
                        return null;
                    });
                    GpuTestSupport.onSwing(() -> null);
                    if (response != JOptionPane.NO_OPTION) {
                        assertEquals(0, quit.get(), "Cancelling or failing to save must keep the game open");
                        assertTrue(GpuBoardWindow.isActiveFor(gui));
                        assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()));
                        assertEquals(window, onGl(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle()));
                    }
                }
                await(() -> !GpuBoardWindow.isActiveFor(gui));
                assertEquals(1, quit.get());
                assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()));
            } finally {
                GpuTestSupport.onSwing(() -> {
                    for (Window owned : ui.frame().getOwnedWindows()) {
                        owned.dispose();
                    }
                    GpuBoardWindow.closeFor(gui);
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    preferences.setValue(GUIPreferences.ADVANCED_NO_SAVE_NAG, noSaveNag);
                    return null;
                });
            }
        }
    }

    /** The texts of the HUD's phase header, on the GL thread. */
    private static List<String> phaseHeaderTexts() {
        return GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("phase-header"));
    }

    private static JOptionPane savePrompt(JFrame frame) {
        for (Window window : frame.getOwnedWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()) {
                for (var child : dialog.getContentPane().getComponents()) {
                    if (child instanceof JOptionPane pane) {
                        return pane;
                    }
                }
            }
        }
        return null;
    }

    @Test
    void swingReportStaysHiddenUntilReturningFromTheNativeBoard() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean enabled = preferences.getMiniReportEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = GpuTestSupport.onSwing(() -> createClientWindow(fixture));
            ClientGUI gui = ui.view().getClientgui();
            MiniReportDisplayDialog report = GpuTestSupport.onSwing(() -> {
                MiniReportDisplayDialog dialog = new MiniReportDisplayDialog(ui.frame(), gui);
                when(gui.getMiniReportDisplayDialog()).thenReturn(dialog);
                when(gui.getMiniReportDisplay()).thenReturn(mock(MiniReportDisplayPanel.class));
                doAnswer(invocation -> {
                    if (GpuBoardWindow.isActiveFor(gui)) {
                        invocation.callRealMethod();
                    } else {
                        // The fixture has no Swing split panes. Observe the restored visibility request instead.
                        dialog.setVisible(invocation.getArgument(0));
                    }
                    return null;
                }).when(gui).setMiniReportLocation(anyBoolean());
                preferences.setMiniReportEnabled(true);
                dialog.setVisible(true);
                return dialog;
            });
            try {
                openNative(ui);
                await(() -> GpuTestSupport.onSwing(() -> !ui.frame().isVisible()));
                GpuTestSupport.onSwing(() -> {
                    assertTrue(GpuBoardWindow.isActiveFor(gui));
                    assertFalse(report.isVisible(), "Hide an already open Swing report during the handoff");
                    fixture.game.setPhase(GamePhase.FIRING_REPORT);
                    fixture.game.setAllReports(List.of(List.of(new Report(3000),
                          new Report(6065).addDesc(fixture.entity).add(10).add("Left Torso"))));
                    gui.setMiniReportLocation(true);
                    assertFalse(report.isVisible(), "Phase and preference updates must not reopen the legacy dialog");
                    assertTrue(preferences.getMiniReportEnabled(), "Suppressing the old window preserves 2D preferences");
                    return null;
                });
                // The report phase opens the HUD's log, which stands for the Swing report.
                await(() -> onGl(() -> GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("log-panel"))));
                GpuTestSupport.onSwing(() -> {
                    assertFalse(report.isVisible(), "The native log must not revive the old report");
                    GpuBoardWindow.showLegacy2D(gui);
                    return null;
                });
                await(() -> GpuTestSupport.onSwing(() -> ui.frame().isVisible() && report.isVisible()));
                assertFalse(GpuTestSupport.onSwing(() -> GpuBoardWindow.isActiveFor(gui)));
            } finally {
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    report.dispose();
                    ui.frame().dispose();
                    ui.view().dispose();
                    preferences.removePreferenceChangeListener(ui.menus());
                    preferences.setMiniReportEnabled(enabled);
                    return null;
                });
            }
        }
    }

    @Test
    void switchesExclusiveWindowsThroughMenusAndNeverRestoresLegacy2DOnClientDisposal() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = GpuTestSupport.onSwing(() -> createClientWindow(fixture));
            Coords position = fixture.entity.getPosition();
            GUIPreferences preferences = GUIPreferences.getInstance();
            float originalScale = preferences.getBoardUiScale();
            try {
                GpuTestSupport.onSwing(() -> {
                    ui.view().centerOnHex(position);
                    return null;
                });
                openNative(ui);
                await(() -> GpuTestSupport.onSwing(() -> !ui.frame().isVisible()));
                awaitMaximizedWindow();
                await(() -> onGl(() -> frames() >= 5));
                await(() -> onGl(() -> ((GpuBattleView) GpuHost.sceneListener()).boardCamera.entranceOpacity() == 1));
                onGl(() -> {
                    BoardCamera camera = ((GpuBattleView) GpuHost.sceneListener()).boardCamera;
                    assertTrue(camera.isIsometric());
                    for (var tile : fixture.source.takeFrame().scene().tiles()) {
                        for (int corner = 0; corner < 6; corner++) {
                            Vector3 point = camera.camera.project(BoardGeometry.corner(tile.coords(), tile.elevation(), corner),
                                  0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
                            assertTrue(point.x > 0 && point.x < camera.camera.viewportWidth
                                  && point.y > 0 && point.y < camera.camera.viewportHeight,
                                  "Opening the 3D board must fit the whole map despite earlier 2D focus requests");
                        }
                    }
                    return null;
                });
                GpuTestSupport.onSwing(() -> { ui.view().centerOnHex(position); return null; });
                await(() -> onGl(() -> unitIsCentered(fixture)));
                assertTrue(GpuTestSupport.onSwing(() -> ui.frame().isDisplayable()), "Switching must preserve the original client");
                // The Swing window is hidden, so an allowed client dialog, here the bug report, must be raised above
                // the native window.
                JDialog probe = GpuTestSupport.onSwing(() -> {
                    JDialog dialog = new JOptionPane("GPU dialog probe").createDialog(ui.frame(),
                          new megamek.client.ui.BugReportMessages().get("title"));
                    dialog.setModal(false);
                    dialog.setVisible(true);
                    return dialog;
                });
                await(() -> GpuTestSupport.onSwing(() -> GpuBoardWindow.raisedOverBoard(probe)));
                GpuTestSupport.onSwing(() -> { probe.dispose(); return null; });
                input(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().restoreWindow());
                for (int[] size : new int[][] { { 900, 600 }, { 2043, 1200 }, { 2560, 1600 }, { 3840, 2160 }, { 1280, 800 } }) {
                    GpuTestSupport.onSwing(() -> { preferences.setBoardUiScale(size[0] == 2560 ? .5f : 1f); return null; });
                    input(() -> assertTrue(Gdx.graphics.setWindowedMode(size[0], size[1])));
                    long resizedFrame = onGl(() -> frames());
                    // Allow the EDT to publish the resized overlay and upload it on the render thread.
                    await(() -> onGl(() -> frames() > resizedFrame + 15));
                    assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()),
                          "Resizing must not fall back to the Legacy 2D board");
                    // A client's request to centre on the unit, as Center camera gives it, keeps zoom and angle.
                    float zoom = onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
                        battle.boardCamera.setIsometric(size[0] != 900);
                        battle.boardCamera.pan(140, -100);
                        assertFalse(unitIsCentered(fixture));
                        return battle.boardCamera.camera.zoom;
                    });
                    Vector3 direction = onGl(() -> new Vector3(((GpuBattleView) GpuHost.sceneListener())
                          .boardCamera.camera.direction));
                    Callable<Long> centerOnUnit = () -> {
                        ui.view().centerOn(fixture.entity);
                        var request = ui.view().getClientState().getCenterRequest();
                        assertEquals(fixture.entity.getId(), request.entityId());
                        return request.sequence();
                    };
                    awaitCenterRequest(GpuTestSupport.onSwing(centerOnUnit));
                    Vector3 settled = onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
                        assertEquals(zoom, battle.boardCamera.camera.zoom, 0.001f, "Centering preserves zoom");
                        assertTrue(direction.epsilonEquals(battle.boardCamera.camera.direction, 0.001f), "Centering preserves the view angle");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        GpuBoardTestUi.capture(new File(output, "resize-" + size[0] + ".png"));
                        return battle.boardCamera.focus.cpy();
                    });
                    awaitCenterRequest(GpuTestSupport.onSwing(centerOnUnit));
                    onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
                        assertTrue(settled.epsilonEquals(battle.boardCamera.focus, .001f),
                              "A second request must retain the same framing, not snap to the unit's hex");
                        return null;
                    });
                }
                // The HUD's Menu opens the menu bar's commands as pages; Esc goes back a page, then closes it.
                input(() -> GpuBoardTestUi.click("utility-menu"));
                input(() -> GpuBoardTestUi.click("GpuBoard.hud.menu.game"));
                captureMenu("menu-game.png");
                input(() -> Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE));
                input(() -> Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE));
                input(() -> GpuBoardTestUi.click("utility-menu"));
                input(() -> GpuBoardTestUi.click("GpuBoard.hud.menu.display"));
                captureMenu("menu-display.png");
                onGl(() -> {
                    // Swing's GUI scale is Swing's alone, and the HUD moves the camera itself: the page leaves both out.
                    List<String> texts = GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot());
                    assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor(GpuGameMenu.BACK)),
                          "Display is open");
                    for (String key : List.of("CommonMenuBar.viewIncGUIScale", "CommonMenuBar.viewDecGUIScale",
                          "CommonMenuBar.viewZoomIn")) {
                        assertFalse(texts.contains(Messages.getString(key)), key);
                    }
                    return null;
                });
                input(() -> Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE));
                input(() -> Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE));
                // A game has no Legacy 2D board to switch to: Renderer offers only the 3D board's graphics.
                assertTrue(GpuTestSupport.onSwing(() -> ui.gpuChoice().isSelected()), "Renderer shows the 3D board's graphics");
                GpuTestSupport.onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    ui.frame().dispose();
                    return null;
                });
                await(() -> !GpuHost.isOpen());
                GpuTestSupport.onSwing(() -> {
                    assertFalse(ui.frame().isDisplayable());
                    assertFalse(ui.frame().isVisible());
                    return null;
                });
            } finally {
                GpuTestSupport.onSwing(() -> {
                    preferences.setBoardUiScale(originalScale);
                    preferences.removePreferenceChangeListener(ui.overview());
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    ui.frame().dispose();
                    ui.menus().die();
                    return null;
                });
                await(() -> !GpuHost.isOpen());
            }
        }
    }

    private static void awaitMaximizedWindow() throws Exception {
        await(() -> onGl(() -> {
            long handle = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
            return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE
                  && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        }));
        onGl(() -> {
            long handle = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
            assertEquals(GLFW.GLFW_TRUE, GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED),
                  "The maximized board must retain its title bar and window controls");
            assertFalse(Gdx.graphics.isFullscreen(), "The board must remain a normal desktop window");
            return null;
        });
    }

    private static boolean unitIsCentered(GpuBoardFixture fixture) {
        Coords position = fixture.entity.getPosition();
        Vector3 expected = BoardGeometry.center(position, fixture.game.getBoard().getHex(position).getLevel());
        return ((GpuBattleView) GpuHost.sceneListener()).boardCamera.focus.epsilonEquals(expected, 0.01f);
    }

    private ClientWindow createClientWindow(GpuBoardFixture fixture) throws Exception {
        return createClientWindow(fixture, true);
    }

    private ClientWindow createClientWindow(GpuBoardFixture fixture, boolean legacy2D) throws Exception {
        fixture.source.close();
        ClientGUI gui = mock(ClientGUI.class, invocation -> switch (invocation.getMethod().getName()) {
            case "refreshAuxiliaryWindows", "setMapVisible", "setBotCommandsLocation",
                 "setPlayerListVisible", "setRoundsInAirVisible" -> invocation.callRealMethod();
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        BoardViewsContainer container = mock(BoardViewsContainer.class);
        when(container.isLegacy2DEnabled()).thenAnswer(invocation -> !GpuBoardWindow.isActiveFor(gui));
        setField(AbstractClientGUI.class, gui, "boardViewsContainer", container);
        setField(AbstractClientGUI.class, gui, "miniMaps", new HashMap<>());
        Client client = mock(Client.class);
        JFrame frame = new JFrame("MegaMek - Legacy 2D board switch test");
        CommonMenuBar menus = CommonMenuBar.getMenuBarForGame();
        menus.setPhase(GamePhase.MOVEMENT);
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(gui.getFrame()).thenReturn(frame);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        fixture.view.close();
        fixture.view = new BoardClientState(fixture.game, null, gui, 0, null);
        fixture.view.setLocalPlayer(fixture.player);
        BoardView renderer = new BoardView(fixture.view, null, gui);
        if (legacy2D) {
            frame.add(renderer.getComponent());
        }
        BoardView view = spy(renderer);
        // BoardViewPanel retains its original owner; let that owner create its viewport on demand too.
        doAnswer(invocation -> renderer.getComponent()).when(view).getComponent();
        doReturn(gui).when(view).getClientgui();
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(gui.getFrame()).thenReturn(frame);
        doAnswer(invocation -> {
            frame.getContentPane().removeAll();
            if (invocation.getArgument(0, Boolean.class)) {
                frame.add(view.getComponent());
            } else {
                renderer.releaseLegacy2DView();
                view.releaseLegacy2DView();
            }
            frame.validate();
            return null;
        }).when(gui).setLegacy2DViewEnabled(anyBoolean());
        when(gui.getMenuBar()).thenReturn(menus);
        doReturn(Optional.of(fixture.view)).when(gui).getCurrentBoardState();
        when(gui.boardViews()).thenReturn(List.of(view));
        doReturn(List.of(fixture.view)).when(gui).boardStates();
        doReturn(fixture.view).when(gui).getBoardState();
        doReturn(fixture.view).when(gui).getBoardState(any(BoardLocation.class));
        doReturn(fixture.view).when(gui).getBoardState(any(Entity.class));
        when(gui.getBoardView()).thenReturn(view);
        when(gui.getBoardView(any(BoardLocation.class))).thenReturn(view);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        doCallRealMethod().when(gui).centerOnUnit(any());
        doAnswer(invocation -> {
            BoardLocation location = invocation.getArgument(0);
            if (fixture.game.hasBoardLocation(location)) {
                view.centerOnHex(location.coords());
            }
            return null;
        }).when(gui).centerOnHex(any());
        UnitOverviewOverlay overview = new UnitOverviewOverlay(gui);
        // ClientGUI's board switch; the menu bar has saved the Renderer choice before.
        menus.addActionListener(event -> {
            if (event.getActionCommand().equals(ClientGUI.VIEW_GRAPHICS_CLASSIC)
                  || event.getActionCommand().equals(ClientGUI.VIEW_GRAPHICS_ENHANCED)) {
                GpuBoardWindow.open(fixture.view, () -> fixture.panel);
            }
        });
        frame.setJMenuBar(menus);
        frame.setSize(960, 700);
        frame.setVisible(legacy2D);
        JMenu viewMenu = (JMenu) java.util.Arrays.stream(menus.getComponents())
              .filter(component -> component instanceof JMenu menu
                    && menu.getText().equals(Messages.getString("CommonMenuBar.ViewMenu"))).findFirst().orElseThrow();
        JMenu rendererMenu = (JMenu) java.util.Arrays.stream(viewMenu.getMenuComponents())
              .filter(component -> component instanceof JMenu menu
                    && menu.getText().equals(Messages.getString("CommonMenuBar.viewRenderer"))).findFirst().orElseThrow();
        // The 3D board in its current graphics, so a case does not change the saved graphics.
        String graphics = GUIPreferences.getInstance().getBoardGraphics() == BoardGraphics.CLASSIC
              ? ClientGUI.VIEW_GRAPHICS_CLASSIC : ClientGUI.VIEW_GRAPHICS_ENHANCED;
        JMenuItem gpuChoice = rendererItem(rendererMenu, graphics);
        return new ClientWindow(frame, menus, view, gpuChoice, overview);
    }

    private static JMenuItem rendererItem(JMenu renderer, String command) {
        return java.util.Arrays.stream(renderer.getMenuComponents())
              .filter(component -> component instanceof JMenuItem item && command.equals(item.getActionCommand()))
              .map(JMenuItem.class::cast).findFirst().orElseThrow();
    }

    private static void openNative(ClientWindow ui) throws Exception {
        Application previous = Gdx.app;
        GpuTestSupport.onSwing(() -> {
            // A case that restored the window to a plain size saved that; the reopened board must be maximized again.
            GUIPreferences.getInstance().setValue(GUIPreferences.GPU_BOARD_MAXIMIZED, true);
            ui.gpuChoice().doClick(0);
            assertTrue(GpuBoardWindow.isActiveFor(ui.view().getClientgui()), "Native ownership includes startup");
            return null;
        });
        await(() -> Gdx.app != null && Gdx.app != previous);
        awaitMaximizedWindow();
        await(() -> onGl(() -> frames() > 0));
        assertFalse(GpuTestSupport.onSwing(() -> ui.frame().isVisible()), "The native window replaces the old window");
    }

    private static void awaitNavigation() throws Exception {
        long previous = onGl(() -> frames());
        await(() -> onGl(() -> {
            GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
            return battle.frames() > previous + 2 && !battle.boardCamera.isFraming();
        }));
    }

    /**
     * Waits until the render thread has applied the client's center request {@code sequence} (or a later one) and its
     * framing has ended. The source publishes the request with its next frame (its timer runs every 100 ms, later
     * under load), so a few rendered frames alone do not show that the request arrived.
     */
    private static void awaitCenterRequest(long sequence) throws Exception {
        var applied = GpuBattleView.class.getDeclaredField("centerSequence");
        applied.setAccessible(true);
        await(() -> onGl(() -> {
            GpuBattleView battle = (GpuBattleView) GpuHost.sceneListener();
            return applied.getLong(battle) >= sequence && !battle.boardCamera.isFraming();
        }));
    }

    private static <T> T onGl(Callable<T> action) throws Exception {
        Application app = Gdx.app;
        FutureTask<T> task = new FutureTask<>(action);
        app.postRunnable(task);
        // A cold native startup uploads the board and models before servicing queued input.
        return task.get(30, TimeUnit.SECONDS);
    }

    /** GL thread: the shown board's frame count, or -1 until the host has started the board's scene. */
    private static long frames() {
        return GpuHost.sceneListener() instanceof GpuBattleView battle ? battle.frames() : -1;
    }

    private static void captureMenu(String name) throws Exception {
        await(() -> onGl(() -> GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("menu-panel"))));
        onGl(() -> {
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            GpuBoardTestUi.capture(new File(output, name));
            return null;
        });
    }

    private static void pressShortcut(KeyCommandBind bind) throws Exception {
        input(() -> GpuBoardTestUi.press(bind));
        GpuTestSupport.onSwing(() -> null);
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void input(Runnable action) throws Exception {
        long previous = onGl(() -> {
            action.run();
            return frames();
        });
        await(() -> onGl(() -> frames() > previous));
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        await(45, () -> "Timed out waiting for the board window handoff", condition);
    }

    /**
     * Waits until the active native window has drawn {@code count} frames of its board. They follow its loading screen,
     * so this allows the loading time of {@link GpuBoardTestUi#present}, not a window handoff's, and a timeout names
     * the loading state it stopped in.
     */
    private static void awaitBoardFrames(int count) throws Exception {
        await(GpuBoardTestUi.LOADING_SECONDS, () -> "Timed out waiting for the board to load: " + loadingState(),
              () -> onGl(() -> frames() >= count));
    }

    /** The active native window's {@link GpuBoardTestUi#loadingState}, read on its GL thread. */
    private static String loadingState() {
        try {
            return onGl(() -> GpuBoardTestUi.loadingState((GpuBattleView) GpuHost.sceneListener()));
        } catch (Exception failure) {
            return "unavailable (" + failure + ")";
        }
    }

    private static void await(int seconds, Supplier<String> timeout, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < deadline, timeout);
            Thread.sleep(25);
        }
    }
}
