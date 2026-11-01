/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The views step 5's depth pre-pass is checked and timed on (GpuDepthPrepassSmokeTest, GpuDepthPrepassBenchmarkSmokeTest),
 * and each one's frame without the pre-pass: the composited window's colour (PNG) and depth (gzipped little-endian
 * floats after the width and height), under a label (-Dmegamek.gpu.prepassBaseline=<label>), with the pre-pass turned
 * off: depth_prepass_baseline.py compares two labels' frames, for a change to the terrain's programs that must not move
 * a pixel or a depth unnoticed. Each view is drawn twice: an unsteady view cannot be judged.
 */
@Tag("on-demand")
class GpuDepthPrepassBaselineSmokeTest {
    static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
    static final File CALIBRATION = new File("data/boards/Calibration/32x34 Terrain Material Calibration.board");
    static final File DOME_VENT = new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board");
    /** The specular anti-aliasing benchmark's conditions: wet ground in the afternoon sun. */
    static final BoardAtmosphere.Settings RAIN = new BoardAtmosphere.Settings(15, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, new BoardAtmosphere.Effects(.5f, 0, 0, 0, 0, 0, 0));
    /** The forest benchmark's noon. */
    static final BoardAtmosphere.Settings NOON = new BoardAtmosphere.Settings(13, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    /** Wet rock at the foot of the calibration board's rock cliff bay, the middle and close views' centre. */
    static final Coords FOOT = new Coords(9, 22);

    /**
     * A view: fitted to the board without a centre, else that zoom around the centre; free flight then looks across the
     * board from there. The scene renders at the render resolution {@code scale}.
     */
    record View(String name, float zoom, Coords centre, float scale, boolean freeFlight) {
        static View fitted(String name) { return new View(name, 0, null, 1, false); }

        static View at(String name, float zoom, Coords centre) { return new View(name, zoom, centre, 1, false); }

        View scaled(float renderScale) { return new View(name + " at " + renderScale, zoom, centre, renderScale, false); }

        View flying() { return new View(name + " in free flight", zoom, centre, scale, true); }
    }

    @Test
    void writesEachViewWithoutThePrepass() throws Exception {
        String label = System.getProperty("megamek.gpu.prepassBaseline", "capture");
        BoardScene calibration = scene(CALIBRATION), dome = scene(DOME_VENT);
        BoardScene forest = GpuForestBenchmarkSmokeTest.forest();
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1920, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(", ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append("; label ").append(label).append('\n');
                    capture(calibration, RAIN, calibrationViews(), label, report);
                    List<View> forestViews = new ArrayList<>(forestViews(forest));
                    forestViews.add(forestViews.get(1).flying());
                    capture(forest, NOON, forestViews, label, report);
                    capture(dome, NOON, domeViews(dome), label, report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    BoardCamera.renderScale = 1;
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.writeString(new File(OUTPUT, "depth-prepass-baseline-" + label + ".txt").toPath(), report);
        if (failure.get() != null) { throw new AssertionError("Depth pre-pass baseline", failure.get()); }
    }

    static List<View> calibrationViews() {
        View middle = View.at("calibration middle", .5f, FOOT);
        return List.of(View.fitted("calibration fitted"), middle, View.at("calibration close", .2f, FOOT),
              middle.scaled(.75f));
    }

    static List<View> forestViews(BoardScene forest) {
        Coords centre = new Coords(forest.width() / 2, forest.height() / 2);
        View middle = View.at("forest middle", 1, centre);
        return List.of(View.at("forest far", 3.2f, centre), middle, View.at("forest close", .25f, centre),
              middle.scaled(.75f));
    }

    static List<View> domeViews(BoardScene dome) {
        return List.of(View.fitted("dome vent fitted"),
              View.at("dome vent middle", .5f, new Coords(dome.width() / 2, dome.height() / 2)));
    }

    /** A board sheet as a game loads it, converted, as the board source's first frame shows it. */
    static BoardScene scene(File board) throws Exception {
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(board, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    /** Each view settled and drawn twice; the second frame's window colour and depth are written. */
    private static void capture(BoardScene scene, BoardAtmosphere.Settings settings, List<View> views, String label,
          StringBuilder report) throws Exception {
        Set<GpuTerrain.Prepass> previous = GpuTerrain.depthPrepass;
        GpuTerrain.depthPrepass = EnumSet.noneOf(GpuTerrain.Prepass.class);
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(settings);
        try {
            var camera = new BoardCamera();
            prepare(terrain, frame, camera, scene);
            int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
            for (View view : views) {
                show(view, scene, camera);
                settle(terrain, frame, camera, scene);
                frame.render(terrain, camera, scene, view.scale());
                float[] first = depth(width, height);
                byte[] firstColour = colour(width, height);
                frame.render(terrain, camera, scene, view.scale());
                float[] depth = depth(width, height);
                boolean steady = Arrays.equals(first, depth) && Arrays.equals(firstColour, colour(width, height));
                String name = "depth-prepass-baseline-" + label + "-" + view.name().replace(' ', '-');
                GpuReviewFrame.save(new File(OUTPUT, name + ".png"));
                ByteBuffer bytes = ByteBuffer.allocate(8 + depth.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                bytes.putInt(width).putInt(height).asFloatBuffer().put(depth);
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(new File(OUTPUT, name + ".depth.gz")
                      .toPath()))) {
                    out.write(bytes.array());
                }
                report.append(String.format(Locale.ROOT, "%s: %s; %s%n", view.name(), name, steady ? "steady"
                      : "UNSTEADY: its two frames differ, so a comparison of this view cannot be judged"));
            }
        } finally {
            frame.dispose();
            terrain.dispose();
            BoardCamera.renderScale = 1;
            GpuTerrain.depthPrepass = previous;
        }
    }

    private static float[] depth(int width, int height) {
        FloatBuffer buffer = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, buffer);
        float[] values = new float[buffer.capacity()];
        buffer.get(values);
        return values;
    }

    private static byte[] colour(int width, int height) {
        ByteBuffer buffer = BufferUtils.newByteBuffer(width * height * 4);
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buffer);
        byte[] values = new byte[buffer.capacity()];
        buffer.get(values);
        return values;
    }

    /** The board built for the frame's shader flags, isometric and fitted, its clocks advanced once. */
    static void prepare(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        frame.prepare(terrain, camera, scene);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
    }

    /** Points the camera at the view and sets its render resolution. */
    static void show(View view, BoardScene scene, BoardCamera camera) {
        camera.setIsometric(true);
        if (view.centre() == null) {
            camera.fit(scene);
        } else {
            camera.camera.zoom = view.zoom();
            camera.center(BoardGeometry.center(view.centre(), scene.tile(view.centre()).elevation()));
        }
        // From the isometric eye, free flight turns to look obliquely across the board.
        if (view.freeFlight()) {
            camera.setFirstPerson(true);
            camera.orbit(25, 0);
        }
        BoardCamera.renderScale = view.scale();
    }

    /**
     * The view's detail installed and its programs built, family programs (and with the pre-pass its twins) included, as
     * the loading stage waits for them; then drawn until nothing builds and refinement twice has nothing left (detail,
     * tree levels and scatter visibility settle with drawn frames) and the mesh pages have merged, and given a fresh
     * shadow map, which otherwise keeps what it showed when it was drawn.
     */
    static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene)
          throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
        frame.prepare(terrain, camera, scene);
        while (terrain.refine(camera.camera) | terrain.busy()) {
            assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
            Thread.sleep(5);
        }
        do {
            terrain.refine(camera.camera);
            frame.render(terrain, camera, scene);
            assertTrue(System.nanoTime() < deadline, "Family programs must finish");
        } while (terrain.familyPrograms().pending() > 0);
        // Deferred work waits for unchanged frames: stale mesh pages merge, a little each frame, and refined chunks
        // redraw the cached shadow. A merge reorders draws, so pixels where surfaces tie in depth change when it lands.
        // The merges take a few dozen frames; one that the frame's merge share never reaches (submissions outlasting it
        // in a large view) is not waited for past ten times the settling frames.
        for (int quiet = 0; quiet < GpuMeshPage.SETTLE_FRAMES + 2
              || terrain.pagesMerging() && quiet < 10 * GpuMeshPage.SETTLE_FRAMES; ) {
            frame.render(terrain, camera, scene, BoardCamera.renderScale);
            quiet = terrain.refine(camera.camera) | terrain.busy() ? 0 : quiet + 1;
            assertTrue(System.nanoTime() < deadline, () -> "Terrain must settle: " + terrain.buildDetails());
        }
        terrain.refreshShadows();
        frame.render(terrain, camera, scene, BoardCamera.renderScale);
    }
}
