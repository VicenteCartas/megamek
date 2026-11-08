/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual material interpolation, including the authored gradients on the shipped Grassland 3 board. */
@Tag("on-demand")
class GpuGroundTransitionSmokeTest {
    @Test
    void drawsAuthoredGrassToSandAndRockAndLunarCliffs() throws Exception {
        var ramp = new AtomicReference<BoardScene>();
        var grassland = new AtomicReference<BoardScene>();
        var controls = new BoardScene[2];
        SwingUtilities.invokeAndWait(() -> {
            Board board = Board.createEmptyBoard(7, 3);
            for (int x = 0; x < 7; x++) for (int y = 0; y < 3; y++) {
                board.setHex(new Coords(x, y), new Hex(0, x == 0 || x == 6 ? "" : "ground_fluff:1:" + x,
                      x == 6 ? "desert" : "grass"));
            }
            var game = new Game();
            game.setBoard(board);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { ramp.set(source.takeFrame().scene()); }
            for (int material = 0; material < controls.length; material++) {
                for (int x = 0; x < 7; x++) for (int y = 0; y < 3; y++) {
                    board.setHex(new Coords(x, y), new Hex(0, "", material == 0 ? "grass" : "desert"));
                }
                BoardSceneryLayouts.convert(game.getBoard());
                try (var source = new GpuMapSource(game, null, null)) { controls[material] = source.takeFrame().scene(); }
            }
            game.setBoard(BoardGroundCaptureTest.grassland(3));
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { grassland.set(source.takeFrame().scene()); }
        });
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var camera = new BoardCamera();
                camera.resize(1200, 900);
                var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "ground-transitions");
                try {
                    assertTrue(output.isDirectory() || output.mkdirs());
                    camera.setIsometric(false);
                    camera.fit(ramp.get());
                    terrain.setGrass(false);
                    draw(terrain, frame, camera, ramp.get());
                    GpuReviewFrame.save(new File(output, "grass-to-sand-strengths.png"));
                    var mixed = samples(camera);
                    draw(terrain, frame, camera, controls[0]);
                    var grass = samples(camera);
                    draw(terrain, frame, camera, controls[1]);
                    var sand = samples(camera);
                    // Compare each world position with its own pure controls, avoiding texture/lighting variation
                    // between the opposite ends of the board.
                    for (int x = 1; x <= 5; x++) {
                        Vector3 delta = sand[x].cpy().sub(grass[x]);
                        assertTrue(delta.len() > .08f, "The endpoint materials must have visibly different colors");
                        float fraction = mixed[x].cpy().sub(grass[x]).dot(delta) / delta.len2();
                        assertTrue(fraction > .025f && fraction < .975f,
                              "Step " + x + " must retain both material colors: " + fraction);
                        assertEquals(x / 6f, fraction, .18f,
                              "Lit color must follow the authored strength despite normals, roughness and display encoding");
                    }
                    var rock = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.ROCK,
                          c.getX() < 4 ? 3 : 0, -1, 0));
                    var lunar = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.LUNAR,
                          c.getX() < 4 ? 3 : 0, -1, 0));
                    camera.setIsometric(true);
                    camera.fit(rock);
                    draw(terrain, frame, camera, rock);
                    GpuReviewFrame.save(new File(output, "original-rock.png"));
                    draw(terrain, frame, camera, lunar);
                    GpuReviewFrame.save(new File(output, "independent-lunar.png"));
                    terrain.setGrass(true);
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.fit(grassland.get());
                        draw(terrain, frame, camera, grassland.get());
                        GpuReviewFrame.save(new File(output, "grassland3-" + (oblique ? "oblique" : "top") + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native terrain transitions", failure.get()); }
    }

    private static void draw(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        terrain.animate(0, List.of());
        for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
    }

    private static Vector3 sample(Pixmap image, BoardCamera camera, int x) {
        Vector3 center = camera.camera.project(BoardGeometry.center(new Coords(x, 1), 0));
        Vector3 sum = new Vector3();
        for (int dy = -10; dy <= 10; dy++) for (int dx = -10; dx <= 10; dx++) {
            int pixel = image.getPixel(Math.round(center.x) + dx, Math.round(center.y) + dy);
            sum.add(pixel >>> 24, (pixel >>> 16) & 255, (pixel >>> 8) & 255);
        }
        return sum.scl(1f / (255 * 21 * 21));
    }

    private static Vector3[] samples(BoardCamera camera) {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, 1200, 900);
        try {
            var result = new Vector3[7];
            for (int x = 0; x < result.length; x++) { result[x] = sample(pixels, camera, x); }
            return result;
        } finally { pixels.dispose(); }
    }
}
