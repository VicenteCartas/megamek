/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL compilation, selection and rendering in both shared camera views, including a shipped aerospace map. */
@Tag("on-demand")
class GpuBackdropSmokeTest {
    @Test
    void aerospaceBackdropsRenderNativelyWithBridgesAndPlatforms() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        BoardGraphics saved = preferences.getBoardGraphics();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardGraphics(BoardGraphics.ENHANCED);
            });
            render(mixedBoard(), "backdrop-space-sky", failure);
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            render(BoardFile.loadSheet(new File(
                  "data/boards/unofficial/Derv_Maps/25x25 Zero Gravity.board"), false, true),
                  "backdrop-zero-gravity", failure);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardGraphics(saved);
            });
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static void render(Board board, String name, AtomicReference<Throwable> failure) throws Exception {
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
                        var atmosphere = (GpuAtmosphere) field(view, "atmosphere");
                        assertFalse(terrain.columns());
                        long backdrops = scene.tiles().stream().filter(tile -> tile.backdrop().present()).count();
                        assertTrue(backdrops > 0);
                        assertTrue(scene.tiles().stream().allMatch(BoardScene.Tile::detailedGround));
                        assertTrue(atmosphere.spaceBackdrop(scene));
                        var selectable = scene.tiles().stream().filter(tile -> tile.backdrop() == BoardBackdrop.SPACE
                              && tile.features().isEmpty()).findFirst().orElseThrow();
                        var center = BoardGeometry.center(selectable.coords(), 0);
                        assertEquals(selectable.coords(), terrain.pick(scene,
                              new Ray(center.add(0, 0, 1000), new Vector3(0, 0, -1))));
                        boolean nativeMaterial = false;
                        for (Object chunk : (List<?>) field(terrain, "chunks")) {
                            for (Object object : (List<?>) field(chunk, "opaque")) {
                                for (var material : ((ModelInstance) object).materials) {
                                    var backdrop = material.get(GpuBackdrop.class, GpuBackdrop.TYPE);
                                    nativeMaterial |= backdrop != null;
                                    assertTrue(backdrop == null || backdrop.value == BoardBackdrop.SKY.ordinal(),
                                          "SPACE has no opaque ground colour or depth mesh");
                                }
                            }
                            Map<?, ?> tiles = (Map<?, ?>) field(chunk, "tileMeshes");
                            Object emptySpace = tiles.get(new Coords(0, 0));
                            if (emptySpace != null) {
                                for (Object range : (List<?>) field(emptySpace, "ranges")) {
                                    assertFalse((int) field(range, "layer") == 0,
                                          "The empty corner SPACE tile retains no solid ground range");
                                }
                            }
                            var shadows = new Array<Renderable>();
                            ((GpuTerrainDepth) field(chunk, "depthTerrain")).shadows.getRenderables(shadows, null);
                            for (var shadow : shadows) {
                                assertFalse(shadow.material.has(GpuBackdrop.TYPE),
                                      "Space and sky do not cast solid shadows across neighboring platforms");
                            }
                        }
                        assertEquals(scene.tiles().stream().anyMatch(tile -> tile.backdrop() == BoardBackdrop.SKY),
                              nativeMaterial, "Only SKY retains the native opaque backdrop shader");
                        for (boolean isometric : new boolean[] { false, true }) {
                            view.boardCamera.setIsometric(isometric);
                            view.boardCamera.fit(scene);
                            GpuBoardTestUi.present(view);
                            view.render();
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            if (!isometric) {
                                readableSpace(view, selectable);
                                if (name.equals("backdrop-space-sky")) { readableInk(atmosphere); }
                            }
                            File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            assertTrue(directory.isDirectory() || directory.mkdirs());
                            GpuBoardTestUi.capture(new File(directory, name + (isometric ? "-iso" : "-top") + ".png"));
                        }
                        if (name.equals("backdrop-space-sky")) {
                            verifyLightModes(view, atmosphere, scene, name);
                            verifyShaderConstants(view, atmosphere, scene, selectable, name);
                            measureBackground(view, atmosphere);
                            verifySpaceToggle(view, fixture, atmosphere, name);
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
    }

    private static void readableInk(GpuAtmosphere atmosphere) throws Exception {
        FrameBuffer ink = (FrameBuffer) field(atmosphere, "contrastRead");
        ink.begin();
        Pixmap sample = null;
        try {
            sample = Pixmap.createFromFrameBuffer(0, 0, ink.getWidth(), ink.getHeight());
            assertTrue((sample.getPixel(3, 1) >>> 24) > 240, "Empty SPACE uses light labels");
            assertTrue((sample.getPixel(3, 5) >>> 24) < 15, "Pale platforms keep dark labels");
            assertTrue((sample.getPixel(6, 2) >>> 24) < 15, "Opaque SKY keeps dark labels");
        } finally {
            if (sample != null) { sample.dispose(); }
            ink.end();
        }
    }

    private static void verifyLightModes(GpuBattleView view, GpuAtmosphere atmosphere, BoardScene scene,
          String name) throws Exception {
        var tuning = GpuBoardTestUi.tuning(view);
        boolean previous = tuning.fixedSun();
        try {
            for (boolean fixed : new boolean[] { false, true }) {
                tuning.setFixedSun(fixed);
                view.boardCamera.setIsometric(true);
                view.boardCamera.fit(scene);
                GpuBoardTestUi.present(view);
                capture(view, name + (fixed ? "-fixed-light" : "-world-light"));
                assertEquals(fixed, field(atmosphere, "cameraRelativeLight"));
                Vector3 direction = atmosphere.lighting().direction().cpy();
                Vector3 screen = direction.cpy().rot(view.boardCamera.camera.view);
                view.boardCamera.orbit(60, 0);
                capture(view, name + (fixed ? "-fixed-light-rotated" : "-world-light-rotated"));
                Vector3 rotated = atmosphere.lighting().direction().cpy();
                if (fixed) {
                    assertTrue(screen.epsilonEquals(rotated.rot(view.boardCamera.camera.view), .0001f),
                          "The sky's fixed-source mode uses the same screen bearing as actual terrain lighting");
                } else {
                    assertTrue(direction.epsilonEquals(rotated, .0001f),
                          "The world sky keeps the actual light at the same world bearing");
                }
                verifySlowMotion(view, atmosphere, scene, name + (fixed ? "-fixed" : "-world"));
            }
        } finally {
            tuning.setFixedSun(previous);
            view.boardCamera.setIsometric(false);
            view.boardCamera.fit(scene);
            GpuBoardTestUi.present(view);
        }
    }

    private static void verifySlowMotion(GpuBattleView view, GpuAtmosphere atmosphere, BoardScene scene,
          String name) throws Exception {
        view.boardCamera.setIsometric(false);
        view.boardCamera.fit(scene);
        GpuBoardTestUi.present(view);
        var time = GpuAtmosphere.class.getDeclaredField("spaceTime");
        time.setAccessible(true);
        double previous = time.getDouble(atmosphere);
        Pixmap before = null, after = null;
        try {
            time.setDouble(atmosphere, 0);
            capture(view, name + "-motion-0s");
            before = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            time.setDouble(atmosphere, 30);
            capture(view, name + "-motion-30s");
            after = Pixmap.createFromFrameBuffer(0, 0, before.getWidth(), before.getHeight());
            assertTrue(spaceDifference(view, scene, before, after) > 10,
                  "Thirty simulated seconds visibly move the background behind SPACE hexes");
        } finally {
            if (before != null) { before.dispose(); }
            if (after != null) { after.dispose(); }
            time.setDouble(atmosphere, previous);
        }
    }

    private static void verifyShaderConstants(GpuBattleView view, GpuAtmosphere atmosphere, BoardScene scene,
          BoardScene.Tile empty, String name) throws Exception {
        var tuning = GpuBoardTestUi.tuning(view);
        boolean oldFixed = tuning.fixedSun();
        var time = GpuAtmosphere.class.getDeclaredField("spaceTime");
        time.setAccessible(true);
        double oldTime = time.getDouble(atmosphere);
        var program = GpuAtmosphere.class.getDeclaredField("compositeShader");
        program.setAccessible(true);
        ShaderProgram original = (ShaderProgram) program.get(atmosphere), sparseShader = null, denseShader = null;
        Pixmap sparse = null, dense = null, paused = null;
        try {
            for (boolean fixed : new boolean[] { false, true }) {
                tuning.setFixedSun(fixed);
                atmosphere.setOptions(tuning.atmosphereOptions());
                time.setDouble(atmosphere, 0);
                FrameBuffer buffer = (FrameBuffer) field(atmosphere, "sceneColor");
                atmosphere.beginPixels(buffer.getWidth(), buffer.getHeight(), .05f);
                buffer.end();
                assertEquals(.05, time.getDouble(atmosphere), .000001, "Both lighting modes advance animation time");
            }
            String fragment = original.getFragmentShaderSource().replaceAll(
                  "const\\s+float\\s+SPACE_DRIFT_SPEED\\s*=\\s*[^;]+;", "const float SPACE_DRIFT_SPEED = 0.0;");
            assertFalse(fragment.equals(original.getFragmentShaderSource()), "The shader exposes its drift constant");
            sparseShader = GpuGlsl.compile("space density zero", original.getVertexShaderSource(), fragment.replaceAll(
                  "const\\s+float\\s+SPACE_DENSITY\\s*=\\s*[^;]+;", "const float SPACE_DENSITY = 0.0;"));
            denseShader = GpuGlsl.compile("space density double", original.getVertexShaderSource(), fragment.replaceAll(
                  "const\\s+float\\s+SPACE_DENSITY\\s*=\\s*[^;]+;", "const float SPACE_DENSITY = 2.0;"));
            program.set(atmosphere, sparseShader);
            time.setDouble(atmosphere, 0);
            capture(view, name + "-density-zero");
            sparse = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            program.set(atmosphere, denseShader);
            capture(view, name + "-density-double");
            dense = Pixmap.createFromFrameBuffer(0, 0, sparse.getWidth(), sparse.getHeight());
            assertTrue(spaceDifference(view, scene, sparse, dense) > 100,
                  "Editing the shader density changes the star and nebula population behind SPACE cells");
            time.setDouble(atmosphere, 30);
            capture(view, name + "-shader-paused-30s");
            paused = Pixmap.createFromFrameBuffer(0, 0, sparse.getWidth(), sparse.getHeight());
            assertEquals(0, spaceDifference(view, scene, dense, paused),
                  "A zero shader drift constant freezes the background while animation time continues");
            readableSpace(view, empty);
            readableInk(atmosphere);
        } finally {
            program.set(atmosphere, original);
            if (sparseShader != null) { sparseShader.dispose(); }
            if (denseShader != null) { denseShader.dispose(); }
            if (sparse != null) { sparse.dispose(); }
            if (dense != null) { dense.dispose(); }
            if (paused != null) { paused.dispose(); }
            tuning.setFixedSun(oldFixed);
            time.setDouble(atmosphere, oldTime);
            view.render();
        }
    }

    private static int spaceDifference(GpuBattleView view, BoardScene scene, Pixmap before, Pixmap after) {
        int changed = 0;
        float pixels = BoardCamera.pixelsPerUnit(view.boardCamera.camera);
        int radius = Math.max(3, Math.round(BoardGeometry.height() * pixels * .25f));
        for (var tile : scene.tiles()) {
            if (tile.backdrop() != BoardBackdrop.SPACE || !tile.features().isEmpty()) { continue; }
            Vector3 point = BoardGeometry.center(tile.coords(), tile.elevation());
            view.boardCamera.camera.project(point, 0, 0, before.getWidth(), before.getHeight());
            for (int x = Math.max(0, Math.round(point.x) - radius); x < Math.min(before.getWidth(), point.x + radius); x++) {
                for (int y = Math.max(0, Math.round(point.y) - radius); y < Math.min(before.getHeight(), point.y + radius); y++) {
                    int a = before.getPixel(x, y), b = after.getPixel(x, y);
                    int difference = Math.abs((a >>> 24) - (b >>> 24))
                          + Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255))
                          + Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255));
                    if (difference > 3) { changed++; }
                }
            }
        }
        return changed;
    }

    private static void readableSpace(GpuBattleView view, BoardScene.Tile tile) {
        Vector3 point = BoardGeometry.center(tile.coords(), tile.elevation());
        point.add(BoardGeometry.width() * .08f, 0, 0);
        view.boardCamera.camera.project(point, 0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Pixmap sample = Pixmap.createFromFrameBuffer(Math.round(point.x) - 3, Math.round(point.y) - 3, 7, 7);
        try {
            double luminance = 0;
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 7; y++) {
                    int pixel = sample.getPixel(x, y);
                    luminance += .2126 * (pixel >>> 24) + .7152 * ((pixel >>> 16) & 255) + .0722 * ((pixel >>> 8) & 255);
                }
            }
            assertTrue(luminance / (49 * 255) < .25, "The skybox stays dark behind readable light labels");
        } finally { sample.dispose(); }
    }

    /** Same geometry, camera and compiled composite program; this measures completed full frames, not isolated GPU time. */
    private static void measureBackground(GpuBattleView view, GpuAtmosphere atmosphere) throws Exception {
        var enabled = GpuAtmosphere.class.getDeclaredField("spaceBackdrop");
        enabled.setAccessible(true);
        try {
            double[] times = new double[2];
            for (int variant = 0; variant < 2; variant++) {
                enabled.setBoolean(atmosphere, variant == 1);
                for (int warm = 0; warm < 8; warm++) { view.render(); }
                Gdx.gl.glFinish();
                long start = System.nanoTime();
                for (int frame = 0; frame < 30; frame++) { view.render(); }
                Gdx.gl.glFinish();
                times[variant] = (System.nanoTime() - start) / 30_000_000.0;
            }
            System.out.printf(java.util.Locale.ROOT,
                  "Backdrop same-scene full-frame mean: ordinary %.3f ms, space %.3f ms%n", times[0], times[1]);
        } finally { enabled.setBoolean(atmosphere, true); }
    }

    private static void verifySpaceToggle(GpuBattleView view, GpuBoardFixture fixture, GpuAtmosphere atmosphere,
          String name) throws Exception {
        Map<Coords, Hex> previous = new LinkedHashMap<>();
        SwingUtilities.invokeAndWait(() -> {
            Board board = fixture.game.getBoard();
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    Coords at = new Coords(x, y);
                    Hex hex = board.getHex(at);
                    if (!hex.containsTerrain(Terrains.SPACE)) { continue; }
                    previous.put(at, hex.duplicate());
                    Hex ground = hex.duplicate();
                    ground.removeTerrain(Terrains.SPACE);
                    board.setHex(at, ground);
                }
            }
            fixture.source.refresh();
        });
        GpuBoardTestUi.present(view);
        assertFalse(atmosphere.spaceBackdrop((BoardScene) field(view, "scene")),
              "Removing the last SPACE marker restores the ordinary background");
        capture(view, name + "-removed");
        SwingUtilities.invokeAndWait(() -> {
            previous.forEach(fixture.game.getBoard()::setHex);
            fixture.source.refresh();
        });
        GpuBoardTestUi.present(view);
        assertTrue(atmosphere.spaceBackdrop((BoardScene) field(view, "scene")));
        capture(view, name + "-restored");
    }

    private static void capture(GpuBattleView view, String name) {
        view.render();
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name + ".png"));
    }

    private static Board mixedBoard() {
        int width = 10, height = 8;
        Hex[] hexes = new Hex[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                String terrain = x < 5 ? "space:1" : "sky:1";
                if (x >= 3 && x <= 6 && y >= 4 && y <= 6) { terrain = "pavement:1"; }
                if (x == 2 && y >= 2 && y <= 5) {
                    terrain = "space:1;bridge:1:9;bridge_cf:200;bridge_elev:1";
                }
                hexes[y * width + x] = new Hex(0, terrain, "lunar");
            }
        }
        return new Board(width, height, hexes);
    }
}
