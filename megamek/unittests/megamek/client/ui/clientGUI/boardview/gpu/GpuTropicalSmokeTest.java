/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual Racice map plus exposed material, low foliage, banks, water and neighboring terrain treatments. */
@Tag("on-demand")
class GpuTropicalSmokeTest {
    @Test
    void rendersTropicalRaciceAndMaterialContactsInBothViews() throws Exception {
        var racice = BoardScenes.capture(BoardTropicalTest.racice());
        var study = Board.createEmptyBoard(9, 7);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 7; y++) {
            String theme = x < 2 ? "grass" : x > 6 ? "desert" : "tropical";
            String cover = y == 1 && x > 1 && x < 7 ? "woods:1;foliage_elev:2" : "";
            if (x == 3 && y == 3) { cover = "woods:2;foliage_elev:1"; }
            if (x == 5 && y == 3) { cover = "jungle:2;foliage_elev:2;road:1:9"; }
            if (y == 5) { cover = "water:1"; }
            if (x == 1 && y == 3) { cover = "sand:1"; }
            if (x == 6 && y == 3) { cover = "snow:1;woods:1;foliage_elev:2"; }
            if (x == 7 && y == 3) { cover = "magma:1"; }
            if (x == 8 && y == 3) { cover = "magma:2"; }
            study.setHex(new Coords(x, y), new Hex(y < 2 ? 2 : 0, cover, theme));
        }
        var contacts = BoardScenes.capture(study);
        var themes = List.of("grass", "tropical", "desert", "snow", "fungus", "volcano");
        var gradients = Board.createEmptyBoard(themes.size() * 2, 5);
        for (int x = 0; x < themes.size() * 2; x++) for (int y = 0; y < 5; y++) {
            gradients.setHex(new Coords(x, y), new Hex(0, "", themes.get(x / 2)));
        }
        var themeContacts = BoardScenes.capture(gradients);
        var tropical = Board.createEmptyBoard(7, 5);
        for (int x = 0; x < 7; x++) for (int y = 0; y < 5; y++) {
            tropical.setHex(new Coords(x, y), new Hex(x < 2 ? 1 : 0,
                  (x + y) % 3 == 0 ? "woods:1;foliage_elev:2" : "", "tropical"));
        }
        var pure = BoardScenes.capture(tropical);
        var materials = new ArrayList<BoardScene>();
        for (String type : List.of("desert", "tropical", "mixed")) {
            var material = Board.createEmptyBoard(3, 3);
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                material.setHex(new Coords(x, y), new Hex(0, type.equals("mixed") ? "ground_fluff:1:3" : "",
                      type.equals("desert") ? "desert" : "tropical"));
            }
            materials.add(BoardScenes.capture(material));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "tropical");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
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
                    camera.setIsometric(false);
                    camera.camera.zoom = .12f;
                    camera.center(BoardGeometry.center(new Coords(1, 1), 0));
                    for (int index = 0; index < materials.size(); index++) {
                        renderReady(terrain, frame, camera, materials.get(index));
                        GpuReviewFrame.save(new File(output, "material-" + index + ".png"));
                    }
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .32f;
                        camera.center(BoardGeometry.center(new Coords(3, 2), 0));
                        renderReady(terrain, frame, camera, pure);
                        GpuReviewFrame.save(new File(output, oblique ? "pure-tropical-oblique.png" : "pure-tropical-top.png"));
                    }
                    frame.prepare(terrain, camera, racice);
                    terrain.update(racice);
                    terrain.animate(.5f, List.of());
                    var ray = new Ray(BoardGeometry.center(new Coords(21, 1), 0).add(0, 0, 1000),
                          new Vector3(0, 0, -1));
                    BoardGeometry.Hit picked = null;
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = 1.55f;
                        camera.center(BoardGeometry.center(new Coords(15, 8), 0));
                        renderReady(terrain, frame, camera, racice);
                        GpuReviewFrame.save(new File(output, oblique ? "racice-oblique.png" : "racice-top.png"));
                        camera.camera.zoom = .28f;
                        camera.center(BoardGeometry.center(new Coords(21, 1), 0));
                        renderReady(terrain, frame, camera, racice);
                        GpuReviewFrame.save(new File(output, oblique ? "racice-close-oblique.png" : "racice-close-top.png"));
                        var hit = terrain.selectionHit(racice, ray);
                        assertNotNull(hit);
                        if (picked == null) { picked = hit; } else { assertEquals(picked, hit); }
                    }
                    terrain.update(contacts);
                    terrain.animate(.5f, List.of());
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .48f;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                        renderReady(terrain, frame, camera, contacts);
                        GpuReviewFrame.save(new File(output, oblique ? "contacts-oblique.png" : "contacts-top.png"));
                    }
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .7f;
                        camera.center(BoardGeometry.center(new Coords(5, 2), 0));
                        renderReady(terrain, frame, camera, themeContacts);
                        GpuReviewFrame.save(new File(output, oblique ? "theme-gradients-oblique.png" : "theme-gradients-top.png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tropical terrain review", failure.get()); }
        verifyMaterialMixture(output);
    }

    /** A mixed tropical floor must retain visible pieces of both sources instead of fading entirely to tan. */
    private static void verifyMaterialMixture(File output) throws Exception {
        var desert = ImageIO.read(new File(output, "material-0.png"));
        var tropical = ImageIO.read(new File(output, "material-1.png"));
        var mixed = ImageIO.read(new File(output, "material-2.png"));
        int compared = 0, tropicalPatches = 0, desertPatches = 0;
        for (int y = 100; y < mixed.getHeight() - 100; y++) {
            for (int x = 100; x < mixed.getWidth() - 100; x++) {
                int a = desert.getRGB(x, y), b = tropical.getRGB(x, y), c = mixed.getRGB(x, y);
                int contrast = colorDistance(a, b);
                if (contrast <= 6400) { continue; } // Ignore sky, grid and similarly colored source pixels.
                compared++;
                if (colorDistance(c, b) < contrast * .16) { tropicalPatches++; }
                if (colorDistance(c, a) < contrast * .16) { desertPatches++; }
            }
        }
        assertTrue(compared > 100000, "The comparison must include the exposed ground");
        assertTrue(tropicalPatches > compared * .1, "Tropical litter must remain visible in the authored 50/50 mixture");
        assertTrue(desertPatches > compared * .1, "The mixture must also retain its authored desert cover");
    }

    private static int colorDistance(int a, int b) {
        int r = (a >> 16 & 255) - (b >> 16 & 255);
        int g = (a >> 8 & 255) - (b >> 8 & 255);
        int blue = (a & 255) - (b & 255);
        return r * r + g * g + blue * blue;
    }

    private static void renderReady(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        frame.render(terrain, camera, scene);
        // The first shadow pass can introduce a shader variant; finish its upload before inspecting the frame.
        terrain.update(scene);
        assertTrue(terrain.ready(scene));
        frame.render(terrain, camera, scene);
    }
}
