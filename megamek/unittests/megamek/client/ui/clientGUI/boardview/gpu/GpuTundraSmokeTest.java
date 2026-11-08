/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native material compilation, terrain editing and paired base/tundra review across every natural theme. */
@Tag("on-demand")
class GpuTundraSmokeTest {
    @Test
    void terrestrialVariantsUseBothMaterialsWithoutChangingOtherTundraFamilies() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "tundra");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(900, 600);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> {
                        try { checkVariants(manager, output); }
                        catch (Throwable error) { failure.set(error); }
                    });
                } finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tundra variants", failure.get()); }
    }

    private static void checkVariants(GpuShaderManager manager, File output) throws Exception {
        String source = GpuShaderSource.readDisk("terrain-tundra.glsl");
        String boundary = "float tundraWetness() {";
        assertTrue(source.contains(boundary), "The diagnostic must change only the dry/wet carpet selection");
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try {
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.camera.zoom = .32f;
            camera.center(BoardGeometry.center(new Coords(5, 3), 0));
            for (String theme : List.of("grass", "fungus", "lunar")) {
                var scene = BoardScenes.capture(BoardTundraTest.board(theme, true));
                install(terrain, frame, camera, scene);
                frame.render(terrain, camera, scene);
                byte[] baseline = pixels(camera);
                byte[][] endpoints = new byte[2][];
                for (int endpoint = 0; endpoint < 2; endpoint++) {
                    var changed = manager.apply(Map.of("terrain-tundra.glsl",
                          source.replace(boundary, boundary + " return " + endpoint + ".0;")));
                    assertTrue(changed.success(), changed.message());
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    endpoints[endpoint] = pixels(camera);
                    GpuReviewFrame.save(new File(output, theme + "-variant-" + endpoint + ".png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), theme + " endpoint " + endpoint);
                }
                if (theme.equals("grass")) {
                    long difference = 0;
                    for (int i = 0; i < endpoints[0].length; i++) {
                        difference += Math.abs((endpoints[0][i] & 255) - (endpoints[1][i] & 255));
                    }
                    assertTrue(difference > endpoints[0].length,
                          "The dry heath and wet sedge carpets must visibly differ");
                } else {
                    assertArrayEquals(baseline, endpoints[0], theme + " must ignore the terrestrial mask");
                    assertArrayEquals(baseline, endpoints[1], theme + " must ignore the terrestrial mask");
                }
                var restored = manager.apply(Map.of());
                assertTrue(restored.success(), restored.message());
                GpuReviewFrame.settle(terrain, null, scene, camera);
                frame.render(terrain, camera, scene);
                assertArrayEquals(baseline, pixels(camera), theme + " must restore the landform wetness");
            }
        } finally {
            try { frame.dispose(); terrain.dispose(); }
            finally { assertTrue(manager.apply(Map.of()).success(), "Restore production tundra sources"); }
        }
    }

    @Test
    void rendersThemePreservingTundraInBothViewsAfterEdits() throws Exception {
        var before = new ArrayList<BoardScene>();
        var after = new ArrayList<BoardScene>();
        for (String theme : BoardTundraTest.THEMES) {
            before.add(BoardScenes.capture(BoardTundraTest.board(theme, false)));
            after.add(BoardScenes.capture(BoardTundraTest.board(theme, true)));
        }
        var mixedBoard = BoardTundraTest.board("grass", true);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 6; y++) {
            String cover = y == 5 ? "water:1" : y == 4 && x == 3 ? "swamp:1"
                  : y == 3 && x == 2 ? "pavement:1;tundra:1" : "tundra:1";
            mixedBoard.setHex(new Coords(x, y), new Hex(y < 2 ? 3 : 0, cover, x < 4 ? "grass" : "fungus"));
        }
        var mixed = BoardScenes.capture(mixedBoard);
        var terracesBefore = new ArrayList<BoardScene>();
        var terracesAfter = new ArrayList<BoardScene>();
        for (boolean concrete : new boolean[] { false, true }) {
            var board = BoardTundraTest.board("grass", false);
            for (boolean tundra : new boolean[] { false, true }) {
                for (int x = 0; x < 8; x++) for (int y = 0; y < 6; y++) {
                    String cover = concrete ? "pavement:1" : "";
                    if (tundra && x >= 4) { cover += ";tundra:1"; }
                    board.setHex(new Coords(x, y), new Hex(y < 2 ? 2 : y < 4 ? 1 : 0, cover, "grass"));
                }
                (tundra ? terracesAfter : terracesBefore).add(BoardScenes.capture(board));
            }
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "tundra");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    BoardScenes.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    for (int i = 0; i < before.size(); i++) {
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .46f;
                            camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                            install(terrain, frame, camera, before.get(i));
                            frame.render(terrain, camera, before.get(i));
                            float[] base = sample(camera);
                            install(terrain, frame, camera, after.get(i));
                            terrain.animate(.5f, List.of());
                            frame.render(terrain, camera, after.get(i));
                            float[] tundra = sample(camera);
                            float difference = 0;
                            for (int c = 0; c < 3; c++) { difference += Math.abs(base[c] - tundra[c]); }
                            String name = BoardTundraTest.THEMES.get(i) + (oblique ? "-oblique" : "-top");
                            GpuReviewFrame.save(new File(output, name + ".png"));
                            assertTrue(difference > .015f, name + " must visibly change after applying TUNDRA: " + difference);
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), name);
                        }
                    }
                    // Array-backed theme contacts and water use the same coverage stencil as plain dry ground.
                    camera.setIsometric(true);
                    install(terrain, frame, camera, mixed);
                    for (float zoom : new float[] { .22f, .80f }) {
                        camera.camera.zoom = zoom;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                        frame.render(terrain, camera, mixed);
                        GpuReviewFrame.save(new File(output, "mixed-wet-" + zoom + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Mixed themes, wetland and water");
                    }
                    for (int i = 0; i < terracesBefore.size(); i++) {
                        var baseScene = terracesBefore.get(i);
                        var tundraScene = terracesAfter.get(i);
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .32f;
                            camera.center(BoardGeometry.center(new Coords(4, 3), 1));
                            var at = new Coords(5, 3);
                            var surface = new BoardSurface(tundraScene, tundraScene.tile(at));
                            // Sample the bank itself, halfway between the lower floor and the upper terrace.
                            var slope = java.util.stream.Stream.concat(surface.faces.stream(),
                                        surface.walls(tundraScene, BoardGeometry.floor(tundraScene)).stream())
                                  .filter(f -> center(f).z > BoardGeometry.level() * .2f
                                        && center(f).z < BoardGeometry.level() * .8f)
                                  .max(java.util.Comparator.comparingDouble(f -> f.b().cpy().sub(f.a())
                                        .crs(f.c().cpy().sub(f.a())).len2())).orElseThrow();
                            var points = List.of(BoardGeometry.center(at, 1), center(slope));
                            install(terrain, frame, camera, baseScene);
                            frame.render(terrain, camera, baseScene);
                            var samples = points.stream().map(p -> sample(camera, p)).toList();
                            install(terrain, frame, camera, tundraScene);
                            frame.render(terrain, camera, tundraScene);
                            String name = (i == 0 ? "terraces" : "concrete-lichen") + (oblique ? "-oblique" : "-top");
                            GpuReviewFrame.save(new File(output, name + ".png"));
                            for (int p = 0; p < points.size(); p++) {
                                float[] painted = sample(camera, points.get(p));
                                float difference = 0;
                                for (int c = 0; c < 3; c++) { difference += Math.abs(samples.get(p)[c] - painted[c]); }
                                assertTrue(difference > .015f, name + (p == 0 ? " plateau" : " slope") + ": " + difference);
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), name);
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(original);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tundra material review", failure.get()); }
    }

    private static void install(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene)
          throws Exception {
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        GpuReviewFrame.settle(terrain, null, scene, camera);
    }

    private static byte[] pixels(BoardCamera camera) {
        var point = camera.camera.project(BoardGeometry.center(new Coords(5, 3), 0));
        return ScreenUtils.getFrameBufferPixels(Math.round(point.x) - 24, Math.round(point.y) - 24, 48, 48, false);
    }

    private static float[] sample(BoardCamera camera) {
        return sample(camera, BoardGeometry.center(new Coords(5, 3), 0));
    }

    private static Vector3 center(BoardSurface.Face face) {
        return face.a().cpy().add(face.b()).add(face.c()).scl(1f / 3);
    }

    private static float[] sample(BoardCamera camera, Vector3 world) {
        var point = camera.camera.project(world.cpy());
        var image = Pixmap.createFromFrameBuffer(Math.round(point.x) - 24, Math.round(point.y) - 24, 48, 48);
        float[] mean = new float[3];
        try {
            for (int x = 0; x < 48; x++) for (int y = 0; y < 48; y++) {
                int pixel = image.getPixel(x, y);
                mean[0] += (pixel >>> 24) & 255;
                mean[1] += (pixel >>> 16) & 255;
                mean[2] += (pixel >>> 8) & 255;
            }
            for (int c = 0; c < 3; c++) { mean[c] /= 48 * 48 * 255f; }
            return mean;
        } finally { image.dispose(); }
    }
}
