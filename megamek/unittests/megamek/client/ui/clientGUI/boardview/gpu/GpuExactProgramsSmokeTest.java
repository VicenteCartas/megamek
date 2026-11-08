/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/**
 * Sculpted ground without the biome stage draws exactly what the complete programs draw on boards without biomes:
 * Grassland 3 from the middle and Dome Vent 2 from its overview, colour and depth bit for bit. Editor samples
 * drawn with the board's ambient-occlusion configuration, whose programs they then share, match the samples drawn
 * without it. Captures go to build/gpu-board-review/exact-programs.
 */
@Tag("on-demand")
class GpuExactProgramsSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "exact-programs");

    @Test
    void prunedTerrainProgramsDrawTheCompleteProgramsFrames() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        BoardScene grass = scene("AGoAC Maps/16x17 Grassland 3.board"), vent = scene("Map Pack Volcanic/16x17 Dome Vent 2.board");
        assertFalse(GpuBiomeSurface.active(grass), "Grassland 3 has no biome: its programs leave the stage out");
        run(() -> {
            for (var board : List.of(new Object[] { "grass-middle", grass, true }, new Object[] { "vent-overview", vent, false })) {
                String name = (String) board[0];
                BoardScene scene = (BoardScene) board[1];
                GpuTerrain.pruning = false;
                Frame complete = frame(scene, (boolean) board[2], name + "-complete");
                // Control: the complete programs twice, or the comparison below cannot be judged.
                same(name + " complete twice", complete, frame(scene, (boolean) board[2], name + "-control"), 0);
                GpuTerrain.pruning = true;
                // Two different programs: Intel's compiler may round a value's last level differently.
                same(name + " complete against pruned", complete, frame(scene, (boolean) board[2], name + "-pruned"), 1);
            }
        });
    }

    @Test
    void editorSamplesWithTheBoardsOcclusionMatchThoseWithout() throws Exception {
        run(() -> {
            var lender = new GpuTerrain();
            var occlusion = new GpuAmbientOcclusion();
            // The palette drops samples whose image has left the stage.
            var stage = new Stage(new ScreenViewport());
            try {
                byte[][] without = { sample(lender, stage, ""), sample(lender, stage, "ground") };
                occlusion.configure(lender.environment(), true);
                byte[][] with = { sample(lender, stage, ""), sample(lender, stage, "ground") };
                assertArrayEquals(without[0], with[0], "The theme sample");
                assertArrayEquals(without[1], with[1], "The ground sample");
            } finally {
                stage.dispose();
                occlusion.dispose();
                lender.dispose();
            }
        });
    }

    private interface Body { void run() throws Exception; }

    private static void run(Body body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    body.run();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuTerrain.pruning = true;
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static BoardScene scene(String board) throws Exception {
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(new File("data/boards/" + board), false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    private record Frame(byte[] pixels, float[] depth) { }

    private static void same(String label, Frame expected, Frame actual, int tolerance) {
        int differing = 0, largest = 0, first = -1;
        for (int i = 0; i < expected.pixels().length; i++) {
            int difference = Math.abs((expected.pixels()[i] & 255) - (actual.pixels()[i] & 255));
            if (difference > 0 && first < 0) { first = i; }
            differing += difference > 0 ? 1 : 0;
            largest = Math.max(largest, difference);
        }
        int depths = 0;
        for (int i = 0; i < expected.depth().length; i++) { depths += expected.depth()[i] != actual.depth()[i] ? 1 : 0; }
        int width = Gdx.graphics.getBackBufferWidth();
        System.out.printf("P1 %s: %d colour values differ (largest %d, first at pixel %d,%d), %d depths differ%n", label,
              differing, largest, first < 0 ? -1 : first / 4 % width, first < 0 ? -1 : first / 4 / width, depths);
        assertTrue(largest <= tolerance, label + ": colour and alpha differ by " + largest);
        assertArrayEquals(expected.depth(), actual.depth(), label + ": depth");
    }

    /** A fresh terrain settled on the board with its family programs, from the middle or the fitted overview. */
    private static Frame frame(BoardScene scene, boolean middle, String name) throws InterruptedException {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        try {
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(true);
            camera.fit(scene);
            if (middle) {
                camera.camera.zoom *= .5f;
                camera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
            }
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            terrain.animate(.5f, List.of());
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            for (int quiet = 0; quiet < 2; ) {
                frame.render(terrain, camera, scene);
                boolean busy = terrain.refine(camera.camera) | terrain.busy() || terrain.familyPrograms().pending() > 0;
                quiet = busy ? 0 : quiet + 1;
                assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
            }
            terrain.refreshShadows();
            frame.render(terrain, camera, scene);
            int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
            Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
            byte[] pixels;
            try {
                ByteBuffer buffer = image.getPixels();
                pixels = new byte[buffer.remaining()];
                buffer.get(pixels);
            } finally { image.dispose(); }
            FloatBuffer depths = BufferUtils.newFloatBuffer(width * height);
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depths);
            float[] depth = new float[width * height];
            depths.get(depth);
            GpuReviewFrame.save(new File(OUTPUT, name + ".png"));
            return new Frame(pixels, depth);
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    /** A palette sample's pixels: a grass theme ("") or a ground component, drawn by fresh sample renderers. */
    private static byte[] sample(GpuTerrain lender, Stage stage, String component) {
        var previews = new GpuEditorTerrainPreviews();
        try {
            var image = new Image();
            stage.addActor(image);
            previews.add("grass", component, "", image);
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (!"Ready".equals(image.getUserObject())) {
                previews.update(lender);
                assertTrue(System.nanoTime() < deadline, "The sample must render");
                LockSupport.parkNanos(1_000_000);
            }
            Texture texture = ((TextureRegionDrawable) image.getDrawable()).getRegion().getTexture();
            ByteBuffer pixels = BufferUtils.newByteBuffer(texture.getWidth() * texture.getHeight() * 4);
            texture.bind();
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            byte[] result = new byte[pixels.remaining()];
            pixels.get(result);
            return result;
        } finally { previews.dispose(); }
    }
}
