/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Camera sorting used to leave an active magma sampler on an array texture, rejecting entire terrain draws. */
@Tag("on-demand")
class GpuDomeVentSmokeTest {
    @Test
    void rotatingDomeVentKeepsItsInteriorOpaque() throws Exception {
        var board = BoardFile.loadSheet(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"), false, true);
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "dome-orbit");
        Files.createDirectories(output.toPath());
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            var failure = new AtomicReference<Throwable>();
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(960, 960);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var original = BoardGeometry.tuning();
                    var view = new GpuBattleView(fixture.source);
                    try {
                        BoardGeometry.tune(BoardGeometry.DEFAULTS);
                        // Exercise the real passes, shaders and sorting: terrain-only stills missed the invalid draws.
                        view.create();
                        GpuCamouflageReview.renderReady(view);
                        GpuBoardTestUi.present(view);
                        var atmosphere = GpuTestSupport.field(view, "atmosphere");
                        var scene = (BoardScene) GpuTestSupport.field(view, "scene");
                        var camera = view.boardCamera;
                        camera.setIsometric(true);
                        camera.camera.zoom = .35f;
                        camera.center(BoardGeometry.center(new Coords(13, 9), 3));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Board preparation");
                        for (int angle = 0; angle < 360; angle += 2) {
                            if (angle > 0) { camera.orbit(2, 0); }
                            view.render();
                            int error = Gdx.gl.glGetError();
                            var target = (FrameBuffer) GpuTestSupport.field(atmosphere, "sceneColor");
                            int openings = openings(target, camera, scene);
                            if (error != GL20.GL_NO_ERROR || openings > 0 || angle == 174 || angle == 354) {
                                GpuReviewFrame.save(new File(output, "dome-vent-" + angle + ".png"));
                            }
                            assertEquals(GL20.GL_NO_ERROR, error, "Terrain draw at angle " + angle);
                            assertTrue(openings == 0, "Terrain exposes " + openings + " sky blocks at angle " + angle);
                        }
                        System.out.println("Dome Vent: 180 orbit frames, no GL errors or interior sky blocks");
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        view.dispose();
                        BoardGeometry.tune(original);
                        Gdx.app.exit();
                    }
                }
            }, config);
            if (failure.get() != null) { throw new AssertionError("Dome Vent rotation", failure.get()); }
        }
    }

    private static int openings(FrameBuffer target, BoardCamera camera, BoardScene scene) {
        target.begin();
        var pixels = ScreenUtils.getFrameBufferPixmap(0, 0, target.getWidth(), target.getHeight());
        try {
            int width = pixels.getWidth(), height = pixels.getHeight(), openings = 0;
            Vector3 origin = new Vector3(), end = new Vector3();
            for (int y = 0; y + 1 < height; y++) {
                for (int x = 0; x + 1 < width; x++) {
                    // Scene alpha is zero for sky. Ignore isolated float rasterization samples on triangle edges;
                    // a missing draw exposes connected areas, including many fully transparent 2x2 blocks.
                    if ((pixels.getPixel(x, y) & 255) != 0 || (pixels.getPixel(x + 1, y) & 255) != 0
                          || (pixels.getPixel(x, y + 1) & 255) != 0 || (pixels.getPixel(x + 1, y + 1) & 255) != 0) {
                        continue;
                    }
                    origin.set((x + .5f) * 2 / width - 1, (y + .5f) * 2 / height - 1, -1)
                          .prj(camera.camera.invProjectionView);
                    end.set((x + .5f) * 2 / width - 1, (y + .5f) * 2 / height - 1, 1)
                          .prj(camera.camera.invProjectionView);
                    origin.lerp(end, (BoardGeometry.floor(scene) - origin.z) / (end.z - origin.z));
                    var tile = BoardGeometry.tile(scene, origin.x, origin.y);
                    // Every ray through the inner footprint must meet the closed terrain above its base plane.
                    if (tile != null && tile.coords().getX() > 1 && tile.coords().getX() < scene.width() - 2
                          && tile.coords().getY() > 1 && tile.coords().getY() < scene.height() - 2) { openings++; }
                }
            }
            return openings;
        } finally {
            pixels.dispose();
            target.end();
        }
    }

    @Test
    void descendingLavaBankAndTheFallsCliffOccludeTheBackground() throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"), false, true);
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            scene = fixture.source.takeFrame().scene();
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                BoardGeometry.Tuning original = BoardGeometry.tuning();
                GpuTerrain terrain = new GpuTerrain();
                try (var frame = new GpuSceneTestOutput()) {
                    frame.begin(1, 0, 1, 1);
                    frame.display();
                    // The shared display shoulder transforms the sentinel before comparison.
                    Pixmap background = ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getBackBufferWidth(),
                          Gdx.graphics.getBackBufferHeight());
                    try {
                        assertAll("Dome Vent 2 seams",
                              () -> lavaBank(frame, background, terrain, scene, new File(output, "lava-bank-seams")),
                              () -> fallsCliff(frame, background, terrain, scene, new File(output, "waterfall-seams")));
                    } finally { background.dispose(); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Dome Vent seams", failure.get()); }
    }

    /** The descending lava bank must close against the cliff: no connected opening in the 320 x 320 centre patch. */
    private static void lavaBank(GpuSceneTestOutput frame, Pixmap background, GpuTerrain terrain, BoardScene scene,
          File output) {
        BoardGeometry.tune(BoardGeometry.DEFAULTS);
        terrain.update(scene);
        terrain.animate(.5f, List.of());
        assertTrue(output.isDirectory() || output.mkdirs());
        for (boolean perspective : new boolean[] { false, true }) {
            for (int angle : new int[] { -90, -60, -30 }) {
                BoardCamera camera = camera(perspective, angle, -20, .06f);
                camera.center(new Vector3(693, -288, 63));
                render(frame, terrain, camera);
                GpuBoardTestUi.capture(new File(output, "bank-" + angle
                      + (perspective ? "-perspective" : "-ortho") + ".png"));
                int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                Pixmap pixels = ScreenUtils.getFrameBufferPixmap(width / 2 - 160, height / 2 - 160, 320, 320);
                try {
                    // Float rasterization can leave an isolated sample on a shared triangle edge.
                    // The reported missing wall produces a connected, multi-pixel opening.
                    int left = width / 2 - 160, bottom = height / 2 - 160;
                    int holes = holes(pixels, background, left, bottom);
                    assertTrue(holes <= 2, "The lava bank must close against the cliff at angle " + angle
                          + ": " + holes + " background pixels");
                    for (int y = 1; y + 1 < pixels.getHeight(); y++) {
                        for (int x = 1; x + 1 < pixels.getWidth(); x++) {
                            if (pixels.getPixel(x, y) != background.getPixel(left + x, bottom + y)) { continue; }
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    if (dx == 0 && dy == 0) { continue; }
                                    assertTrue(pixels.getPixel(x + dx, y + dy)
                                                != background.getPixel(left + x + dx, bottom + y + dy),
                                          "No connected opening may remain in the bank");
                                }
                            }
                        }
                    }
                } finally { pixels.dispose(); }
            }
        }
    }

    /** The cliff behind the falls, below the rim in every view, must occlude the background with lava and with water. */
    private static void fallsCliff(GpuSceneTestOutput frame, Pixmap background, GpuTerrain terrain, BoardScene scene,
          File output) {
        BoardGeometry.Tuning defaults = BoardGeometry.DEFAULTS;
        BoardGeometry.tune(new BoardGeometry.Tuning(defaults.hexScale(), defaults.unitScale(),
              defaults.unitHeightScale(), 18, defaults.gridShade(), defaults.multiHexUnitScale(), true));
        assertTrue(output.isDirectory() || output.mkdirs());
        for (boolean water : new boolean[] { false, true }) {
            terrain.update(water ? BoardWaterfallTest.withWater(scene) : scene);
            terrain.animate(.5f, List.of());
            for (boolean perspective : new boolean[] { false, true }) {
                for (int angle : new int[] { -15, 0, 15 }) {
                    BoardCamera camera = camera(perspective, angle, 25, .1f);
                    camera.center(BoardGeometry.corner(new Coords(11, 2), 5.5f, 4));
                    render(frame, terrain, camera);
                    GpuBoardTestUi.capture(new File(output, "dome-vent-" + (water ? "water-" : "lava-") + angle
                          + (perspective ? "-perspective" : "-ortho") + ".png"));
                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                    Pixmap pixels = ScreenUtils.getFrameBufferPixmap(width / 2 - 60, height / 2 - 80, 120, 160);
                    try {
                        assertEquals(0, holes(pixels, background, width / 2 - 60, height / 2 - 80),
                              "The cliff behind the waterfall must occlude the background");
                    } finally { pixels.dispose(); }
                }
            }
        }
    }

    private static BoardCamera camera(boolean perspective, int angle, int tilt, float zoom) {
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setPerspective(perspective);
        camera.setIsometric(true);
        camera.orbit(angle, tilt);
        camera.camera.zoom = zoom;
        return camera;
    }

    private static void render(GpuSceneTestOutput frame, GpuTerrain terrain, BoardCamera camera) {
        terrain.renderShadows(camera.camera, List.of());
        frame.begin(1, 0, 1, 1);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        frame.display();
    }

    private static int holes(Pixmap pixels, Pixmap background, int left, int bottom) {
        int holes = 0;
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                if (pixels.getPixel(x, y) == background.getPixel(left + x, bottom + y)) { holes++; }
            }
        }
        return holes;
    }
}
