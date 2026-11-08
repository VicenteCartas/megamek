/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native material, passage, picking, edit and chunk-LOD integration for the separate natural bridge shell. */
@Tag("on-demand")
class GpuNaturalBridgeSmokeTest {
    @Test
    void lavaTubeArchesJoinTheirCliffsFromAboveAndBelow() throws Exception {
        var scene = BoardScenes.installedScene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "natural-bridges");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var skin = new GpuBoardSkin();
                var labels = new GpuHexText();
                var grid = new GpuHexGrid();
                var batch = new SpriteBatch();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    terrain.animate(0, List.of());
                    for (var at : List.of(new Coords(6, 6), new Coords(9, 9))) {
                        camera.camera.zoom = .16f;
                        for (float tilt : new float[] { 0, 55, 75 }) {
                            camera.setIsometric(false);
                            camera.orbit(110, tilt);
                            camera.center(BoardGeometry.center(at, -.5f));
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "lava-tubes-" + at.getBoardNum() + "-" + (int) tilt + ".png"));
                            grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE);
                            labels.update(scene, skin.skin.getFont("default-font"),
                                  skin.skin.getFont(GpuBoardSkin.OUTLINE_FONT), terrain::roofBounds);
                            labels.render(batch, camera, frame.depthTexture(), 0);
                            GpuReviewFrame.save(new File(output,
                                  "lava-tubes-" + at.getBoardNum() + "-" + (int) tilt + "-labels.png"));
                        }
                    }
                    assertEquals(0, scene.tiles().stream().flatMap(t -> BoardTunnel.entrances(scene, t).stream()).count());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    batch.dispose(); grid.dispose(); labels.dispose(); skin.dispose();
                    frame.dispose(); terrain.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Lava tube arch contacts", failure.get()); }
    }

    /** The spans 1010 and 1011 raised onto their banks: one flat joint at 4 and 4, a smooth step at 4 and 3. */
    @Test
    void raisedLavaTubeJointsCloseOntoTheirBanks() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "natural-bridges");
        Files.createDirectories(output.toPath());
        var scenes = List.of(BridgeScenes.raisedLavaTubes(4, 4), BridgeScenes.raisedLavaTubes(4, 3));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 1024);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    var joint = BoardGeometry.center(new Coords(9, 9), 2).lerp(BoardGeometry.center(new Coords(9, 10), 2), .5f);
                    for (var scene : scenes) {
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        String name = "raised-lava-tubes-" + (scene == scenes.getFirst() ? "4-4" : "4-3");
                        for (float rotation : new float[] { 60, 240, 330 }) {
                            camera.camera.zoom = .12f;
                            camera.setIsometric(false);
                            camera.orbit(rotation, 55);
                            camera.center(joint);
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, name + "-" + (int) rotation + ".png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Raised lava tube joints", failure.get()); }
    }

    @Test
    void naturalSpansRespectTheLowerTerrainAndChangeWithTheirApproaches() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "natural-bridges");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                var frame = new GpuReviewFrame(weather);
                var at = BoardScenes.CENTER;
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW)) {
                        var scene = BridgeScenes.canyonScene(family, false, true);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        camera.camera.zoom = .16f;
                        for (boolean oblique : new boolean[] { true, false }) {
                            camera.setIsometric(oblique);
                            if (oblique) { camera.orbit(75, 10); }
                            camera.center(BoardGeometry.center(at, 1.5f));
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            String name = family.name().toLowerCase(java.util.Locale.ROOT);
                            GpuReviewFrame.save(new File(output, name + (oblique ? "-oblique.png" : "-top.png")));
                            var top = BoardGeometry.center(at, 3).add(0, 0, 10);
                            var hit = terrain.hit(scene, new Ray(top, new Vector3(0, 0, -1)));
                            assertNotNull(hit);
                            assertEquals(at, hit.coords());
                            assertEquals(100, hit.distance(), .01f, "Picking follows the rock's actual deck");
                            var below = BoardGeometry.center(at, 2);
                            var crossing = terrain.hit(scene, new Ray(new Vector3(below).add(-30, 0, 0), Vector3.X));
                            assertTrue(crossing == null || !at.equals(crossing.coords()), "The lower passage stays hollow");
                        }
                    }
                    var river = BridgeScenes.canyonScene(BoardScene.Surface.ROCK, true, false);
                    frame.prepare(terrain, camera, river);
                    terrain.update(river);
                    camera.setIsometric(true);
                    camera.orbit(75, 10);
                    camera.center(BoardGeometry.center(at, 1.5f));
                    GpuReviewFrame.settle(terrain, null, river, camera);
                    frame.render(terrain, camera, river);
                    GpuReviewFrame.save(new File(output, "river-oblique.png"));

                    var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    camera.setIsometric(false);
                    camera.center(BoardGeometry.center(at, 3));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    int natural = sample(camera, at);
                    var approach = BoardScenes.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, 3, BoardScene.Surface.GRASS);
                    // The road-attached span is built and carries the road over its exits (as a converted board stores
                    // it); the bare one stays natural.
                    var built = BridgeScenes.typed(BoardScenes.replace(scene, approach),
                          HexAppearance.BUILT_BRIDGE);
                    var edited = BoardScenes.replace(built,
                          BridgeScenes.withDeckRoad(built.tile(at), BoardRoad.Kind.PAVED, 9));
                    var footing = BoardBridgeFooting.build(edited, edited.tile(at), TerrainLod.FULL, new HashMap<>());
                    var gravelTip = BoardGeometry.center(at, 3).lerp(BoardGeometry.center(at.translated(3), 3), .5f)
                          .add(0, -footing.lengths().get(3) * BoardGeometry.hexScale() - BoardRelief.metres(5), 0);
                    int bareBank = sample(camera, gravelTip);
                    frame.prepare(terrain, camera, edited);
                    terrain.update(edited);
                    GpuReviewFrame.settle(terrain, null, edited, camera);
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(output, "road-bridge-top.png"));
                    assertTrue(difference(natural, sample(camera, at)) > 10, "Adding a road replaces the natural shell with asphalt");
                    assertTrue(difference(bareBank, sample(camera, gravelTip)) > 5,
                          "The visible gravel landing continues after the asphalt has ended");
                    var extension = BoardGeometry.center(at, 3).lerp(BoardGeometry.center(at.translated(3), 3), .5f)
                          .add(0, -BoardRelief.metres(2), 0);
                    var contact = terrain.hit(edited, new Ray(new Vector3(extension).add(0, 0, 10), new Vector3(0, 0, -1)));
                    assertNotNull(contact);
                    assertEquals(at.translated(3), contact.coords(), "An extension is picked in the bank hex it occupies");
                    assertEquals(100, contact.distance(), 3, "The rendered extension closes the old bank gap at deck height");
                    camera.setIsometric(true);
                    camera.orbit(75, 10);
                    camera.center(BoardGeometry.center(at, 1.5f));
                    GpuReviewFrame.settle(terrain, null, edited, camera);
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(output, "road-bridge-oblique.png"));
                    var landing = BoardGeometry.center(at, 3).lerp(BoardGeometry.center(at.translated(3), 3), .5f)
                          .add(0, -BoardRelief.metres(5), 0);
                    camera.camera.zoom = .065f;
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        if (oblique) { camera.orbit(-15, 5); }
                        camera.center(landing);
                        GpuReviewFrame.settle(terrain, null, edited, camera);
                        frame.render(terrain, camera, edited);
                        GpuReviewFrame.save(new File(output, "bridge-landing-" + (oblique ? "oblique.png" : "top.png")));
                    }
                    camera.camera.zoom = .16f;
                    camera.setIsometric(false);
                    camera.center(BoardGeometry.center(at, 3));
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    assertTrue(difference(natural, sample(camera, at)) < 1, "Removing the road restores the natural material");
                    camera.camera.zoom = 12;
                    camera.center(BoardGeometry.center(at, 3));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    camera.camera.zoom = .16f;
                    camera.center(BoardGeometry.center(at, 3));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    assertTrue(difference(natural, sample(camera, at)) < 1, "Returning from distant LOD restores the same surface");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Natural bridge", failure.get()); }
    }

    private static int sample(BoardCamera camera, megamek.common.board.Coords at) {
        return sample(camera, BoardGeometry.center(at, 3).add(4 * BoardGeometry.hexScale(), 0, 0));
    }

    private static int sample(BoardCamera camera, Vector3 point) {
        var screen = camera.camera.project(new Vector3(point));
        var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try { return pixels.getPixel(Math.round(screen.x), Math.round(screen.y)); }
        finally { pixels.dispose(); }
    }

    private static float difference(int a, int b) {
        float result = 0;
        for (int shift : new int[] { 24, 16, 8 }) { result += Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)); }
        return result / 3;
    }
}
