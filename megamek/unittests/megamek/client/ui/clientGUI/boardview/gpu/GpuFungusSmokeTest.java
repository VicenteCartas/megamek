/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native review of fungal surfaces, night emission, hex haze and small wisps; cliff/scatter never emit. */
@Tag("on-demand")
class GpuFungusSmokeTest {
    @Test
    void rendersFungalCrevasseByDayAndNightWithSporesOnlyOnMatureColonies() throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/Alien Worlds/32x17 Fungal Crevasse.board"), false, true);
        BoardScene scene = capture(board);
        Board bare = Board.createEmptyBoard(6, 6);
        for (int x = 0; x < 6; x++) for (int y = 0; y < 6; y++) {
            bare.setHex(new Coords(x, y), new Hex(0, "", "fungus"));
        }
        BoardScene scatter = capture(bare);
        assertTrue(scatter.tiles().stream().flatMap(t -> t.features().stream())
              .anyMatch(f -> BoardFungus.SCATTER.contains(f.asset())));
        bare.setHex(new Coords(3, 3), new Hex(5, "", "fungus"));
        BoardScene cliffs = capture(bare);
        long occupiedHexes = scene.tiles().stream().filter(t -> t.features().stream()
              .anyMatch(f -> BoardFungus.COVER.contains(f.asset()))).count();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "fungus");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(light(13));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    GpuRiverTerrainSmokeTest.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    terrain.update(scene);
                    assertEquals(occupiedHexes, terrain.fungusClouds(), "One cloud per large-mushroom hex");
                    terrain.animate(2, List.of());
                    camera.setIsometric(true);
                    camera.camera.zoom = 1.1f;
                    camera.center(BoardGeometry.center(new Coords(15, 8), 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fungus-day-overview.png"));
                    camera.camera.zoom = .24f;
                    camera.center(BoardGeometry.center(new Coords(6, 11), 2));
                    frame.render(terrain, camera, scene);
                    assertTrue(terrain.fungusParticles() > 0, "Large mushrooms have hex haze and small wisps");
                    GpuReviewFrame.save(new File(output, "fungus-day-close.png"));
                    terrain.animate(2, List.of());
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fungus-day-close-later.png"));
                    frame.configure(light(0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fungus-night-close.png"));
                    assertTrue(warmPixels() > 50, "Fungal lips/gills remain visibly emissive without sunlight");
                    verifyGroundSpill(terrain, frame, camera, scene, output);
                    frame.configure(light(13));
                    camera.setIsometric(false);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fungus-day-top.png"));

                    camera.setIsometric(true);
                    camera.camera.zoom = .13f;
                    camera.center(BoardGeometry.center(new Coords(6, 12), 4));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fungus-cliff-attachment.png"));

                    terrain.update(cliffs);
                    camera.camera.zoom = .22f;
                    camera.center(BoardGeometry.center(new Coords(3, 3), 2));
                    frame.render(terrain, camera, cliffs);
                    assertEquals(0, terrain.fungusParticles(), "Cliff colonies never emit mist");
                    assertEquals(0, terrain.fungusClouds(), "Cliff-only hexes have no cloud");
                    GpuReviewFrame.save(new File(output, "fungus-cliffs-no-mist.png"));

                    // Replacement of the scene must retire the former plumes. The new board has only floor scatter.
                    terrain.update(scatter);
                    camera.setIsometric(true);
                    camera.camera.zoom = .25f;
                    camera.center(BoardGeometry.center(new Coords(2, 2), 0));
                    frame.configure(light(0));
                    frame.render(terrain, camera, scatter);
                    assertEquals(0, terrain.fungusParticles(), "Floor scatter never emits mist");
                    assertEquals(0, terrain.fungusClouds(), "Retire clouds when their mushrooms disappear");
                    GpuReviewFrame.save(new File(output, "fungus-scatter-night.png"));
                    animatedMist(camera);
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
        if (failure.get() != null) { throw new AssertionError("Fungal shader review", failure.get()); }
    }

    private static BoardAtmosphere.Settings light(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    private static BoardScene capture(Board board) throws Exception {
        var captured = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                captured.set(fixture.source.takeFrame().scene());
            });
        }
        return captured.get();
    }

    private static int warmPixels() {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        try {
            int count = 0;
            for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) {
                int pixel = pixels.getPixel(x, y), red = pixel >>> 24, green = (pixel >>> 16) & 255, blue = (pixel >>> 8) & 255;
                if (red > 170 && green > 40 && red > blue * 1.2) { count++; }
            }
            return count;
        } finally { pixels.dispose(); }
    }

    /** Toggle just the shared spill light: emissive meshes, mist, camera and time stay identical. */
    private static void verifyGroundSpill(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, File output) {
        var lighting = GpuEnvironmentLighting.required(terrain.environment());
        var lights = lighting.lavaLighting;
        Pixmap lit = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Pixmap unlit = null;
        try {
            lighting.lavaLighting = null;
            frame.render(terrain, camera, scene);
            GpuReviewFrame.save(new File(output, "fungus-night-without-spill.png"));
            unlit = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            int brighter = 0;
            for (int y = 0; y < lit.getHeight(); y++) for (int x = 0; x < lit.getWidth(); x++) {
                if ((lit.getPixel(x, y) >>> 24) - (unlit.getPixel(x, y) >>> 24) > 8) { brighter++; }
            }
            assertTrue(brighter > 300, "Fungal light must illuminate surrounding terrain, not only its own mesh");
        } finally {
            lighting.lavaLighting = lights;
            lit.dispose();
            if (unlit != null) { unlit.dispose(); }
        }
    }

    /** Isolate animation from terrain refinement and lighting: the shader must change the same plume over time. */
    private static void animatedMist(BoardCamera camera) {
        GpuFungus mist = new GpuFungus();
        Pixmap first = null;
        try (var output = new GpuSceneTestOutput()) {
            camera.camera.zoom = .08f;
            camera.center(new Vector3(0, 0, 30));
            var bounds = new BoundingBox(new Vector3(-10, -10, 0), new Vector3(10, 10, 30));
            for (String asset : BoardFungus.CLIFF) { assertNull(GpuFungus.emitter(asset, bounds)); }
            for (String asset : BoardFungus.SCATTER) { assertNull(GpuFungus.emitter(asset, bounds)); }
            for (String asset : BoardFungus.CUPS) {
                var cup = GpuFungus.emitter(asset, bounds);
                assertTrue(bounds.contains(cup.origin()) && cup.origin().z < bounds.max.z,
                      asset + " must emit below the rim, inside the bowl");
            }
            var source = GpuFungus.emitter(BoardFungus.CUPS.getFirst(), bounds);
            for (int step = 0; step < 2; step++) {
                output.begin(0, 0, 0, 1);
                mist.begin();
                mist.add(List.of(source), camera.camera);
                mist.render(camera.camera, step * 2, Vector3.Zero);
                assertTrue(mist.particles() > 0);
                output.display();
                Pixmap current = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                if (first == null) { first = current; }
                else {
                    int changed = 0;
                    for (int y = 0; y < current.getHeight(); y++) for (int x = 0; x < current.getWidth(); x++) {
                        if (Math.abs((first.getPixel(x, y) >>> 24) - (current.getPixel(x, y) >>> 24)) > 5) { changed++; }
                    }
                    current.dispose();
                    assertTrue(changed > 100, "Pink mist evolves with the shared animation clock");
                }
            }
        } finally {
            if (first != null) { first.dispose(); }
            mist.dispose();
        }
    }
}
