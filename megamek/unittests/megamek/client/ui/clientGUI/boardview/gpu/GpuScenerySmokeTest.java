/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuScenerySmokeTest {
    @Test
    void rendersSceneryDecalsAndFccwThroughTheSharedScene() throws Exception {
        var catalog = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            catalog.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        List<String> variants = List.of("fluff:7:0", "fluff:6:0", "fluff:8:0", "fluff:6:6", "fluff:6:12",
              "road:1:9;fluff:5:2", "woods:1;fluff:80:1", "woods:1;fluff:81:1", "fluff:9:7", "fluff:50:1",
              "fluff:14:0", "fluff:91:1", "fluff:4:0", "fluff:93:6", "fluff:13:1", "fluff:10:9",
              "fluff:2:1", "fluff:2:3", "fortified:1", "geyser:2", "fluff:7:1", "fluff:7:2",
              "road:2:9", "rubble:3");
        for (int i = 0; i < variants.size(); i++) {
            catalog.setHex(new Coords(1 + i % 6, 1 + i / 6), new Hex(0, "pavement:1;" + variants.get(i), ""));
        }
        var details = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            details.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        List<String> detailVariants = List.of("fluff:5:0", "fluff:5:1", "fluff:5:2", "fluff:5:6", "fluff:5:7", "fluff:5:98",
              "woods:1;fluff:80:1", "woods:1;fluff:80:8", "woods:1;fluff:80:16", "woods:1;fluff:80:23",
              "fluff:93:11", "fluff:92:3", "fluff:13:3", "fluff:13:3", "fluff:13:3", "fluff:13:3", "fluff:13:3", "fluff:13:3",
              "fluff:7:3", "fluff:7:4", "fluff:7:5", "fluff:8:6", "fluff:4:6", "fluff:30:1",
              "fluff:6:18", "fluff:92:1", "fluff:92:2", "fluff:92:3", "fluff:92:4", "fluff:92:5",
              "fluff:94:1", "fluff:94:2", "fluff:94:3", "fluff:94:4", "fluff:94:5");
        for (int i = 0; i < detailVariants.size(); i++) {
            details.setHex(new Coords(1 + i % 6, 1 + i / 6), new Hex(0, "pavement:1;" + detailVariants.get(i), ""));
        }
        var corrections = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            corrections.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        List<String> correctedVariants = List.of("fluff:4:0", "fluff:4:1", "fluff:4:2", "fluff:4:3", "fluff:4:4", "fluff:4:5",
              "fluff:7:0", "fluff:7:1", "fluff:7:2", "fluff:13:2", "fluff:13:2", "fluff:13:2",
              "fluff:13:2", "fluff:13:2", "fluff:13:2",
              "fluff:13:1", "fluff:13:1", "fluff:13:1", "fluff:13:1", "fluff:13:1", "fluff:13:1",
              "fluff:13:0", "fluff:13:0", "fluff:13:0", "fluff:13:0", "fluff:13:0", "fluff:13:0",
              "fluff:13:4", "fluff:13:4");
        for (int i = 0; i < correctedVariants.size(); i++) {
            corrections.setHex(new Coords(1 + i % 6, 1 + i / 6), new Hex(0, "pavement:1;" + correctedVariants.get(i), ""));
        }
        var port = Board.createEmptyBoard(20, 26);
        for (int x = 0; x < 20; x++) for (int y = 0; y < 26; y++) {
            port.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        // Source artwork's four container-row families each contain four groups of crane headings.
        int[][] headings = { { 1, 2, 4, 5 }, { 2, 3, 5, 0 }, { 0, 1, 3, 4 }, { 1, 2, 4, 5 } };
        for (int i = 0; i < 48; i++) {
            Coords base = new Coords(2 + i % 6 * 3, 2 + i / 6 * 3);
            int direction = headings[i / 12][i % 12 / 3];
            port.setHex(base, new Hex(0, "pavement:1;woods:1;fluff:81:" + (i + 1), ""));
            port.setHex(base.translated(direction), new Hex(0, "pavement:1;fluff:82:" + (direction + 1), ""));
        }
        var geothermal = Board.createEmptyBoard(9, 8);
        for (int x = 0; x < 9; x++) for (int y = 0; y < 8; y++) {
            geothermal.setHex(new Coords(x, y), new Hex(0, "", ""));
        }
        for (int state = 1; state <= 3; state++) {
            geothermal.setHex(new Coords(state * 2, 3), new Hex(0, "geyser:" + state, ""));
        }
        var demolition = Board.createEmptyBoard(13, 10);
        for (int x = 0; x < 13; x++) for (int y = 0; y < 10; y++) {
            demolition.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        for (int type = 1; type <= 5; type++) {
            demolition.setHex(new Coords(type * 2, 3), new Hex(0, "pavement:1;rubble:" + type, ""));
            demolition.setHex(new Coords(type * 2, 6), new Hex(0, "pavement:1;ground_fluff:2000;fluff:" + (2000 + type), ""));
        }
        demolition.setHex(new Coords(6, 8), new Hex(0, "fortified:1", ""));
        var fccw = BoardFile.loadSheet(new File("data/boards/unofficial/Aokarasu/45x45 FCCW 4-1.board"), false, true);
        assertEquals(45, fccw.getWidth());
        var fccwNoBasement = BoardFile.loadSheet(new File("data/boards/buildingsnobasement/45x45 FCCW 4-1 (No Basement).board"), false, true);
        assertEquals(45, fccwNoBasement.getWidth());
        var ledges = Board.createEmptyBoard(8, 7);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 7; y++) {
            ledges.setHex(new Coords(x, y), new Hex(0, "pavement:1", ""));
        }
        for (int variant = 0; variant < 6; variant++) {
            ledges.setHex(new Coords(variant + 1, 2), new Hex(0, "pavement:1;fluff:8:" + variant, ""));
            ledges.setHex(new Coords(variant + 1, 4), new Hex(0, "pavement:1;fluff:6:" + (12 + variant), ""));
        }
        // Every board converted as a load site converts it (legacy art becomes placed objects; geysers, rubble and
        // fortified stay terrain-owned rows), captured as every 3D view captures it.
        var scenes = new ArrayList<BoardScene>();
        for (Board board : List.of(catalog, fccw, details, corrections, port, geothermal, demolition, fccwNoBasement, ledges)) {
            BoardSceneryLayouts.convert(board);
            scenes.add(BoardScenes.scene(board));
        }
        var fccwScene = scenes.get(1);
        assertTrue(fccwScene.tiles().stream().filter(t -> t.features().stream()
              .anyMatch(f -> f.decoration() != null)).count() >= 25);
        // Herds convert to one object per animal; a herd's hex is the one holding its first animal where the row puts it.
        java.util.function.BiFunction<BoardScene, String, Coords> herd = (scene, key) -> {
            var first = BoardSceneryLayouts.layout("scenery/fluff/" + key).components().getFirst();
            return scene.tiles().stream().filter(t -> t.features().stream().anyMatch(f -> f.asset().equals(first.asset())
                  && Math.abs(f.x() - first.x()) < 1e-3 && Math.abs(f.y() - first.y()) < 1e-3)).findFirst()
                  .orElseThrow(() -> new AssertionError(key)).coords();
        };
        herd.apply(scenes.get(3), "horses1");
        herd.apply(scenes.get(3), "horses2");
        // Each crane is its yard look's quay crane, its boom over the tip hex, which holds no object: the look draws the tip.
        for (int i = 0; i < 48; i++) {
            Coords base = new Coords(2 + i % 6 * 3, 2 + i / 6 * 3), tip = base.translated(headings[i / 12][i % 12 / 3]);
            var crane = scenes.get(4).tile(base).features();
            assertTrue(crane.stream().anyMatch(f -> f.asset().equals("scenery/seaport/yard/quay-crane")), "81:" + (i + 1));
            assertTrue(crane.stream().anyMatch(f -> f.asset().equals("scenery/seaport/yard/crane-boom")), "81:" + (i + 1));
            assertTrue(port.getHex(tip).getDecorations().isEmpty(), "The tip of 81:" + (i + 1));
        }
        // ledge1 (fluff:8:0) decodes to the unrotated row of the canonical ledge.
        Predicate<BoardScene.Feature> ledge1 = f -> f.asset().equals("scenery/roofs/ledge") && f.rotation() == 0;
        assertEquals(11, scenes.get(7).tiles().stream().flatMap(t -> t.features().stream()).filter(ledge1).count());
        assertTrue(scenes.get(7).tile(new Coords(13, 44)).features().stream()
              .anyMatch(ledge1), "Review the reported ledge beside the helipad");
        assertEquals(12, scenes.get(8).tiles().stream().flatMap(t -> t.features().stream())
              .filter(f -> f.asset().equals("scenery/roofs/ledge") || f.asset().equals("scenery/roofs/bevel"))
              .map(f -> f.asset() + "@" + f.rotation()).distinct().count(), "Six ledge and six bevel orientations");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "scenery");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1100);
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
                    for (int i = 0; i < scenes.size(); i++) {
                        var scene = scenes.get(i);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        Coords focus = i == 7 ? new Coords(13, 43) : i == 4 ? new Coords(9, 12) : i != 1 ? new Coords(4, 3) : scene.tiles().stream()
                              .filter(t -> t.features().stream().anyMatch(f -> f.asset().equals("scenery/construction/crawler-crane")))
                              .findFirst().orElseThrow().coords();
                        var ray = new Ray(BoardGeometry.center(focus, 0).add(0, 0, 1000), new Vector3(0, 0, -1));
                        BoardGeometry.Hit picked = null;
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = i == 7 ? .32f : i == 4 ? 1.55f : i != 1 ? .48f : .35f;
                            camera.center(BoardGeometry.center(focus, scene.tile(focus).elevation()));
                            frame.render(terrain, camera, scene);
                            var hit = terrain.hit(scene, ray);
                            assertTrue(hit != null);
                            if (picked == null) { picked = hit; } else { assertEquals(picked, hit); }
                            GpuReviewFrame.save(new File(output, List.of("catalog", "fccw-4-1", "variants", "corrections", "cranes", "geysers", "demolition", "fccw-no-basement-ledges", "ledge-rotations").get(i)
                                  + (oblique ? "-oblique.png" : "-top.png")));
                        }
                        if (i == 7 || i == 8) {
                            camera.orbit(180, 0);
                            frame.render(terrain, camera, scene);
                            assertEquals(picked, terrain.hit(scene, ray));
                            GpuReviewFrame.save(new File(output, (i == 7 ? "fccw-no-basement-ledges" : "ledge-rotations")
                                  + "-reverse.png"));
                        }
                        if (i == 2) {
                            camera.camera.zoom = .13f;
                            camera.center(BoardGeometry.center(new Coords(3, 3), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "chickens-in-game.png"));
                            camera.camera.zoom = .29f;
                            camera.center(BoardGeometry.center(new Coords(3, 5), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "pools-in-game.png"));
                        }
                        if (i == 3) {
                            for (String key : List.of("horses1", "horses2", "cattle1", "cattle2", "cattle3", "pigs1", "pigs2",
                                  "bison1")) {
                                camera.camera.zoom = .12f;
                                camera.center(BoardGeometry.center(herd.apply(scene, key), 0));
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, key + "-in-game.png"));
                            }
                        }
                        if (i == 4) {
                            for (int sample : new int[] { 0, 3, 6, 9, 15, 21 }) {
                                Coords base = new Coords(2 + sample % 6 * 3, 2 + sample / 6 * 3);
                                int direction = headings[sample / 12][sample % 12 / 3];
                                Vector3 midpoint = BoardGeometry.center(base, 0).lerp(
                                      BoardGeometry.center(base.translated(direction), 0), .5f).add(0, 0, 10);
                                camera.camera.zoom = .18f;
                                camera.center(midpoint);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "crane-" + (direction + 1) + "-in-game.png"));
                            }
                        }
                        if (i == 5) {
                            assertTrue(terrain.geyserParticles() > 50);
                            for (int state = 1; state <= 3; state++) {
                                camera.camera.zoom = .08f;
                                camera.center(BoardGeometry.center(new Coords(state * 2, 3), 0).add(0, 0, state == 2 ? 12 : 0));
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "geyser-" + state + "-in-game.png"));
                                if (state == 2) {
                                    terrain.animate(.73f, List.of());
                                    frame.render(terrain, camera, scene);
                                    GpuReviewFrame.save(new File(output, "geyser-2-animated-in-game.png"));
                                }
                            }
                        }
                        if (i == 6) {
                            for (int type = 1; type <= 5; type++) {
                                for (int row : new int[] { 3, 6 }) {
                                    camera.camera.zoom = .09f;
                                    camera.center(BoardGeometry.center(new Coords(type * 2, row), 0));
                                    frame.render(terrain, camera, scene);
                                    GpuReviewFrame.save(new File(output, "rubble-" + type
                                          + (row == 6 ? "-cleared" : "") + "-in-game.png"));
                                }
                            }
                            camera.camera.zoom = .09f;
                            camera.center(BoardGeometry.center(new Coords(6, 8), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "fortified-in-game.png"));
                        }
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
        if (failure.get() != null) { throw new AssertionError("Scenery and FCCW review", failure.get()); }
    }
}
