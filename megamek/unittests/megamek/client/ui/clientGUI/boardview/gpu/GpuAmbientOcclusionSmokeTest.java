/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.client.ui.clientGUI.boardview.gpu.GpuCalibrationScenes.View;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 7's ambient occlusion (GpuAmbientOcclusion): switched off after being on, the frame is the frame drawn before it
 * was ever on, bit for bit; on, the cliff feet of the calibration board and the Box Canyon are darkened while their
 * open level ground keeps a visibility near 1. Captures each view off and on (ambient-occlusion-*.png, with the visibility alone) on the
 * calibration board, the Savannah Box Canyon, the 48 by 48 city and the forest. With
 * {@code -Dmegamek.gpu.measureAmbientOcclusion=true} it also times each view's stages off and on, alternating
 * (ambient-occlusion-cost.txt).
 */
@Tag("on-demand")
class GpuAmbientOcclusionSmokeTest {
    private static final File BOARDS = new File("data/boards");
    private static final boolean MEASURE = Boolean.getBoolean("megamek.gpu.measureAmbientOcclusion");
    private static final List<String> FAILURES = new ArrayList<>();

    @Test
    void darkensCreasesAndLeavesTheFrameUnchangedWhenOff() throws Exception {
        BoardScene calibration = GpuCalibrationScenes.scene(GpuCalibrationScenes.CALIBRATION);
        BoardScene canyon = GpuCalibrationScenes.scene(new File(BOARDS,
              "Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
        BoardScene city = GpuCalibrationScenes.scene(new File(BOARDS, "unofficial/VictorMorson/48x48.board"));
        BoardScene forest = GpuForestBenchmarkSmokeTest.forest();
        StringBuilder report = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(GpuCalibrationScenes.OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1920, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    FAILURES.clear();
                    report.append("Renderer: ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(", ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append("; window ")
                          .append(Gdx.graphics.getBackBufferWidth()).append(" x ")
                          .append(Gdx.graphics.getBackBufferHeight()).append('\n');
                    check(calibration, List.of(GpuCalibrationScenes.calibrationViews().get(1)), true,
                          report);
                    Coords canyonCentre = new Coords(canyon.width() / 2, canyon.height() / 2);
                    check(canyon, List.of(View.at("box canyon middle", .5f, canyonCentre)), true, report);
                    check(city, List.of(View.at("48x48 city middle", .5f, new Coords(city.width() / 2,
                          city.height() / 2))), false, report);
                    check(forest, List.of(GpuCalibrationScenes.forestViews(forest).get(1)), false, report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    assertTrue(FAILURES.isEmpty(), "Ambient occlusion: " + FAILURES);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    report.append("Failures: ").append(failure.get() == null && FAILURES.isEmpty() ? "none"
                          : FAILURES.isEmpty() ? String.valueOf(failure.get()) : String.join("; ", FAILURES)).append('\n');
                    BoardCamera.renderScale = 1;
                    Gdx.app.exit();
                }
            }
        }, config);
        System.out.print(report);
        Files.writeString(new File(GpuCalibrationScenes.OUTPUT, "ambient-occlusion.txt").toPath(), report);
        if (failure.get() != null) { throw new AssertionError("Ambient occlusion", failure.get()); }
    }

    /** Each view off (never on before), on, and off again; with {@code creases}, its cliff feet and open ground. */
    private static void check(BoardScene scene, List<View> views, boolean creases, StringBuilder report)
          throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
        var camera = new BoardCamera();
        try {
            GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
            for (View view : views) {
                String name = "ambient-occlusion-" + view.name().replace(' ', '-');
                GpuCalibrationScenes.show(view, scene, camera);
                frame.setAmbientOcclusion(false);
                byte[] off = draw(terrain, frame, camera, scene, new File(GpuCalibrationScenes.OUTPUT,
                      name + "-off.png"));
                frame.setAmbientOcclusion(true);
                draw(terrain, frame, camera, scene, new File(GpuCalibrationScenes.OUTPUT, name + "-on.png"));
                Visibility visibility = Visibility.read(frame.ambientOcclusion());
                visibility.save(new File(GpuCalibrationScenes.OUTPUT, name + "-visibility.png"));
                report.append(String.format(Locale.ROOT, "%s: pre-pass twin draws %d; visibility mean %.3f, 1st"
                            + " percentile %.3f%n", view.name(), terrain.prepassDraws(), visibility.mean(),
                      visibility.percentile(.01f)));
                if (creases) { checkCreases(view, scene, camera, visibility, report); }
                if (MEASURE) { measure(view, terrain, frame, camera, scene, report); }
                frame.setAmbientOcclusion(false);
                byte[] again = draw(terrain, frame, camera, scene, null);
                if (!Arrays.equals(off, again)) {
                    FAILURES.add(view.name() + ": the frame with ambient occlusion off again differs from the frame"
                          + " before it was on");
                }
                report.append(view.name()).append(": off again ")
                      .append(Arrays.equals(off, again) ? "identical" : "DIFFERS").append('\n');
            }
        } finally {
            frame.dispose();
            terrain.dispose();
            BoardCamera.renderScale = 1;
        }
    }

    /** The view settled with the frame's setting (a switch rebuilds the programs), drawn, saved and read back. */
    static byte[] draw(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          File file) throws InterruptedException {
        GpuCalibrationScenes.settle(terrain, frame, camera, scene);
        frame.render(terrain, camera, scene, BoardCamera.renderScale);
        if (file != null) { GpuReviewFrame.save(file); }
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        ByteBuffer buffer = BufferUtils.newByteBuffer(width * height * 4);
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buffer);
        byte[] colour = new byte[buffer.capacity()];
        buffer.get(colour);
        return colour;
    }

    /**
     * Cliff feet against open ground. A foot: the darkest visibility around points on the lower hex's level a fifth to
     * three fifths of the way from the edge of a rise of three levels or more toward the lower hex's centre, where the
     * surface shown lies within a level of the point (not behind the cliff). Open ground: the centres of level, dry hexes
     * whose neighbours are level and dry too, without trees or buildings on any of them.
     */
    private static void checkCreases(View view, BoardScene scene, BoardCamera camera, Visibility visibility,
          StringBuilder report) {
        List<Float> feet = new ArrayList<>(), open = new ArrayList<>();
        int rises = 0, levels = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            boolean level = clear(tile);
            int neighbours = 0;
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile next = scene.tile(tile.coords().translated(direction));
                if (next == null) { continue; }
                neighbours++;
                level &= next.elevation() == tile.elevation() && clear(next);
                if (next.elevation() - tile.elevation() < 3 || tile.waterDepth() > 0) { continue; }
                rises++;
                Vector3 centre = BoardGeometry.center(tile.coords(), tile.elevation());
                Vector3 edge = BoardGeometry.center(next.coords(), tile.elevation()).add(centre).scl(.5f);
                float darkest = Float.NaN;
                for (float inward : new float[] { .2f, .4f, .6f }) {
                    float value = visibility.darkest(camera, edge.cpy().lerp(centre, inward), BoardGeometry.width() / 8,
                          BoardGeometry.level());
                    if (!Float.isNaN(value)) { darkest = Float.isNaN(darkest) ? value : Math.min(darkest, value); }
                }
                if (!Float.isNaN(darkest)) { feet.add(darkest); }
            }
            if (level && neighbours == 6) {
                levels++;
                float centre = visibility.at(camera, BoardGeometry.center(tile.coords(), tile.elevation()));
                if (!Float.isNaN(centre)) { open.add(centre); }
            }
        }
        feet.sort(Float::compare);
        open.sort(Float::compare);
        report.append(String.format(Locale.ROOT, "%s: %d rises of three levels or more, %d feet shown, median darkest"
                    + " visibility %.3f; %d open level hexes, %d shown, median %.3f, lowest %.3f%n", view.name(), rises,
              feet.size(), median(feet), levels, open.size(), median(open), open.isEmpty() ? Float.NaN : open.getFirst()));
        if (feet.size() < 3 || !(median(feet) <= .85f)) {
            FAILURES.add(view.name() + ": cliff feet are not darkened: " + feet.size() + " shown, median " + median(feet));
        }
        if (open.size() < 2 || !(median(open) >= .97f)) {
            FAILURES.add(view.name() + ": open level ground is occluded: " + open.size() + " shown, median "
                  + median(open));
        }
    }

    /** Dry, without a tree or a building on it. */
    private static boolean clear(BoardScene.Tile tile) {
        return tile.waterDepth() <= 0 && tile.features().stream().noneMatch(feature
              -> feature.kind() == BoardScene.FeatureKind.TREE || feature.kind() == BoardScene.FeatureKind.BUILDING
              || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL);
    }

    private static float median(List<Float> values) {
        return values.isEmpty() ? Float.NaN : values.get(values.size() / 2);
    }

    /** Each view's stages, ambient occlusion off and on in alternating rounds, at the view's render resolution. */
    private static void measure(View view, GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, StringBuilder report) throws InterruptedException {
        try (var timings = new GpuStageTimings()) {
            for (boolean enabled : new boolean[] { false, true, true, false }) {
                frame.setAmbientOcclusion(enabled);
                GpuCalibrationScenes.settle(terrain, frame, camera, scene);
                GpuAmbientOcclusion occlusion = frame.ambientOcclusion();
                for (int i = -60; i < 240; i++) {
                    timings.beginFrame(i >= 0);
                    frame.render(terrain, camera, scene, timings, BoardCamera.renderScale, () -> {
                        terrain.render(camera.camera, () -> timings.stage("depth pre-pass"), () -> {
                            timings.stage("ambient occlusion");
                            occlusion.render(camera.camera, terrain, frame.depthTexture());
                            timings.stage("opaque colour");
                        });
                        occlusion.end();
                    });
                    Gdx.gl.glFinish();
                }
                timings.appendReport(report, view.name() + (enabled ? " on" : " off"));
            }
        }
    }

    /** The occlusion target read back: visibility and camera distance per half-resolution texel. */
    private record Visibility(float[] values, int width, int height) {
        static Visibility read(GpuAmbientOcclusion occlusion) {
            FrameBuffer target = occlusion.target();
            assertTrue(target != null, "The occlusion was drawn");
            int width = target.getWidth(), height = target.getHeight();
            FloatBuffer buffer = BufferUtils.newFloatBuffer(width * height * 2);
            target.bind();
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 4);
            Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_RG, GL20.GL_FLOAT, buffer);
            FrameBuffer.unbind();
            float[] values = new float[buffer.capacity()];
            buffer.get(values);
            for (int i = 1; i < values.length; i += 2) { values[i] += occlusion.rangeOrigin(); }
            return new Visibility(values, width, height);
        }

        float mean() {
            double sum = 0;
            for (int i = 0; i < values.length; i += 2) { sum += values[i]; }
            return (float) (sum / (values.length / 2));
        }

        float percentile(float fraction) {
            float[] visible = new float[values.length / 2];
            for (int i = 0; i < visible.length; i++) { visible[i] = values[2 * i]; }
            Arrays.sort(visible);
            return visible[(int) (fraction * (visible.length - 1))];
        }

        /** The texel coordinates of a world point, its z its window depth. */
        private Vector3 project(BoardCamera camera, Vector3 world) {
            return camera.camera.project(world.cpy(), 0, 0, width, height);
        }

        /**
         * Whether a world point is the surface its texel shows: on screen, at the camera distance stored there within
         * {@code tolerance}, not behind a nearer surface.
         */
        private boolean shown(BoardCamera camera, Vector3 world, Vector3 texel, float tolerance) {
            if (texel.x < 0 || texel.y < 0 || texel.x >= width || texel.y >= height || texel.z < 0 || texel.z > 1) {
                return false;
            }
            float range = world.cpy().sub(camera.camera.position).dot(camera.camera.direction);
            return Math.abs(values[2 * ((int) texel.y * width + (int) texel.x) + 1] - range) < tolerance;
        }

        /** The visibility at a world point, or NaN where it is not shown. */
        float at(BoardCamera camera, Vector3 world) {
            Vector3 texel = project(camera, world);
            return shown(camera, world, texel, BoardGeometry.level()) ? values[2 * ((int) texel.y * width + (int) texel.x)] : Float.NaN;
        }

        /**
         * The lowest visibility within a world radius of a world point on screen, or NaN where the surface shown there
         * lies farther than {@code tolerance} from the point along the view.
         */
        float darkest(BoardCamera camera, Vector3 world, float radius, float tolerance) {
            Vector3 texel = project(camera, world);
            if (!shown(camera, world, texel, tolerance)) { return Float.NaN; }
            float darkest = 1;
            int reach = Math.max(1, (int) project(camera, world.cpy().add(radius, 0, 0)).dst(texel.x, texel.y, texel.z));
            for (int y = Math.max(0, (int) texel.y - reach); y <= Math.min(height - 1, (int) texel.y + reach); y++) {
                for (int x = Math.max(0, (int) texel.x - reach); x <= Math.min(width - 1, (int) texel.x + reach); x++) {
                    darkest = Math.min(darkest, values[2 * (y * width + x)]);
                }
            }
            return darkest;
        }

        /** The visibility as grey, black for none and white for all of the sky. */
        void save(File file) {
            Pixmap image = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            try {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int grey = Math.round(Math.clamp(values[2 * (y * width + x)], 0f, 1f) * 255);
                        // Rows read bottom up; the PNG writer flips them.
                        image.drawPixel(x, y, grey << 24 | grey << 16 | grey << 8 | 255);
                    }
                }
                PixmapIO.writePNG(new FileHandle(file), image, -1, true);
            } finally { image.dispose(); }
        }
    }
}
