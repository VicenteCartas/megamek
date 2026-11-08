/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;

/** The calibration, forest and dome views the depth, occlusion and material smoke tests compare. */
final class GpuCalibrationScenes {
    private GpuCalibrationScenes() { }

    static final File CALIBRATION = new File("data/boards/Calibration/32x34 Terrain Material Calibration.board");

    static final File DOME_VENT = new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board");

    /** Wet rock at the foot of the calibration board's rock cliff bay, the middle and close views' centre. */
    static final Coords FOOT = new Coords(9, 22);

    /** The forest benchmark's noon. */
    static final BoardAtmosphere.Settings NOON = new BoardAtmosphere.Settings(13, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);

    static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));

    /** The specular anti-aliasing benchmark's conditions: wet ground in the afternoon sun. */
    static final BoardAtmosphere.Settings RAIN = new BoardAtmosphere.Settings(15, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, new BoardAtmosphere.Effects(.5f, 0, 0, 0, 0, 0, 0));

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

    static List<GpuCalibrationScenes.View> calibrationViews() {
        GpuCalibrationScenes.View middle = GpuCalibrationScenes.View.at("calibration middle", .5f, GpuCalibrationScenes.FOOT);
        return List.of(GpuCalibrationScenes.View.fitted("calibration fitted"), middle, GpuCalibrationScenes.View.at("calibration close", .2f, GpuCalibrationScenes.FOOT),
              middle.scaled(.75f));
    }

    static List<GpuCalibrationScenes.View> domeViews(BoardScene dome) {
        return List.of(GpuCalibrationScenes.View.fitted("dome vent fitted"),
              GpuCalibrationScenes.View.at("dome vent middle", .5f, new Coords(dome.width() / 2, dome.height() / 2)));
    }

    static List<GpuCalibrationScenes.View> forestViews(BoardScene forest) {
        Coords centre = new Coords(forest.width() / 2, forest.height() / 2);
        GpuCalibrationScenes.View middle = GpuCalibrationScenes.View.at("forest middle", 1, centre);
        return List.of(GpuCalibrationScenes.View.at("forest far", 3.2f, centre), middle, GpuCalibrationScenes.View.at("forest close", .25f, centre),
              middle.scaled(.75f));
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

    /** A board sheet as a game loads it, converted, as the board source's first frame shows it. */
    static BoardScene scene(File board) throws Exception {
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(board, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
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

    /** Points the camera at the view and sets its render resolution. */
    static void show(GpuCalibrationScenes.View view, BoardScene scene, BoardCamera camera) {
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
}
