/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Grassy two-level banks at 10:38, their rim blades checked across the real boards, junctions included: Grassland 2's
 * bank from 1006/1107 down to the road hex 1007 and the bank from 1309 to 1310, and Grassland 3's busiest bank, each
 * close, at mid and at play zoom, from the lower side and from above. Writes bank-rim/{label}/*.png
 * ({@code megamek.gpu.bankRimLabel}, default "capture"), and with {@code megamek.gpu.bankRimFrames} times each side view.
 */
@Tag("on-demand")
class GpuBankRimSmokeTest {
    private static final BoardAtmosphere.Settings MORNING = new BoardAtmosphere.Settings(10 + 38 / 60f, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);

    /** A bank from its upper hex down to its lower one. */
    private record Bank(String name, int board, Coords upper, Coords lower) { }

    private static final List<Bank> BANKS = List.of(new Bank("g2-1006", 2, new Coords(9, 5), new Coords(9, 6)),
          new Bank("g2-1310", 2, new Coords(12, 8), new Coords(12, 9)),
          new Bank("g3-bank", 3, null, null));

    @Test
    void capturesGrassyBankRims() throws Exception {
        var scenes = new BoardScene[] { GpuCalibrationScenes.scene(
              new File("data/boards/AGoAC Maps/16x17 Grassland 2.board")),
              GpuCalibrationScenes.scene(new File("data/boards/AGoAC Maps/16x17 Grassland 3.board")) };
        var output = new File(GpuCalibrationScenes.OUTPUT,
              "bank-rim/" + System.getProperty("megamek.gpu.bankRimLabel", "capture"));
        assertTrue(output.isDirectory() || output.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.useVsync(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(MORNING);
                GpuTerrain terrain = null;
                BoardScene built = null;
                try {
                    System.out.println("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                    for (Bank bank : BANKS) {
                        BoardScene scene = scenes[bank.board() - 2];
                        if (scene != built) {
                            if (terrain != null) { terrain.dispose(); }
                            terrain = new GpuTerrain();
                            GpuCalibrationScenes.prepare(terrain, frame, new BoardCamera(), scene);
                            built = scene;
                        }
                        Coords upper = bank.upper(), lower = bank.lower();
                        if (upper == null) {
                            var busiest = busiestBank(scene);
                            upper = busiest[0];
                            lower = busiest[1];
                        }
                        var top = scene.tile(upper);
                        var centre = BoardGeometry.center(upper, top.elevation());
                        var outward = BoardGeometry.center(lower, top.elevation()).sub(centre).nor();
                        var target = centre.cpy().mulAdd(outward, BoardGeometry.width() * .45f);
                        target.z -= BoardGeometry.level();
                        for (float zoom : new float[] { .1f, .28f, .6f }) {
                            String size = zoom < .2f ? "close" : zoom < .5f ? "mid" : "play";
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            camera.camera.zoom = zoom;
                            camera.camera.near = 1;
                            camera.camera.far = 4000;
                            camera.camera.up.set(Vector3.Z);
                            camera.camera.position.set(target).mulAdd(outward, 650).add(0, 0, 420);
                            camera.camera.lookAt(target);
                            camera.camera.update();
                            GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                                  new File(output, bank.name() + "-" + size + "-side.png"));
                            System.out.printf(Locale.ROOT, "%s %s: %.0f px per hex%n", bank.name(), size,
                                  BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera.camera, target));
                            time(terrain, frame, camera, scene, bank.name() + "-" + size + "-side");
                            if (zoom < .2f) { checkRims(terrain, scene, upper); }
                            camera.camera.position.set(target).add(0, 0, 900);
                            camera.camera.up.set(0, 1, 0);
                            camera.camera.lookAt(target);
                            camera.camera.update();
                            GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                                  new File(output, bank.name() + "-" + size + "-top.png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (terrain != null) { terrain.dispose(); }
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Bank rim review", failure.get()); }
    }

    /**
     * With {@code megamek.gpu.bankRimFrames} set, the settled view's frame time: after ten warm-up frames, that many
     * frames each finished on the GPU; prints the median and the 10th to 90th percentile.
     */
    private static void time(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, String name) {
        int frames = Integer.getInteger("megamek.gpu.bankRimFrames", 0);
        if (frames <= 0) { return; }
        double[] millis = new double[frames];
        for (int i = -10; i < frames; i++) {
            long start = System.nanoTime();
            frame.render(terrain, camera, scene);
            Gdx.gl.glFinish();
            if (i >= 0) { millis[i] = (System.nanoTime() - start) / 1e6; }
        }
        Arrays.sort(millis);
        System.out.printf(Locale.ROOT, "PERF bank-rim %s: median %.2f ms, p10-p90 %.2f-%.2f ms%n", name,
              millis[frames / 2], millis[frames / 10], millis[frames * 9 / 10]);
    }

    /** Every rim planted on the board, junctions included, is drawable, and the bank under review grows one. */
    private static void checkRims(GpuTerrain terrain, BoardScene scene, Coords upper) {
        for (var tile : scene.tiles()) {
            var planted = terrain.planted(tile.coords());
            if (planted != null && planted.rim() != null) { BoardGrassBankTest.checkRim(planted.rim()); }
        }
        var planted = terrain.planted(upper);
        assertTrue(planted != null && planted.rim() != null, "The bank under review grows rim blades: " + upper);
    }

    /** The grass hex with the most two-level drops to dry ground, and one of its lower neighbours. */
    private static Coords[] busiestBank(BoardScene scene) {
        Coords[] best = null;
        int most = 0;
        for (var tile : scene.tiles()) {
            if (!BoardSurfaceBlend.natural(tile) || tile.surface() != BoardScene.Surface.GRASS) { continue; }
            int drops = 0;
            Coords lower = null;
            for (int direction = 0; direction < 6; direction++) {
                var next = scene.tile(tile.coords().translated(direction));
                if (next != null && !next.liquid().present() && tile.elevation() - next.elevation() == 2) {
                    drops++;
                    lower = next.coords();
                }
            }
            if (drops > most) {
                most = drops;
                best = new Coords[] { tile.coords(), lower };
            }
        }
        return best;
    }
}
