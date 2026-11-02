/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review renders of multi-hex buildings (BoardMultiHexBuilding) on the shipped Lunar Base: the whole base
 * from above and at an angle, and close views of the biodome, a glass dome, the habitats (a connected kit), the radar
 * station and the tank halls.
 */
@Tag("on-demand")
class GpuMultiHexBuildingSmokeTest {
    private record View(String name, Coords at, float zoom, boolean isometric, float orbit) { }

    private static final List<View> VIEWS = List.of(
          new View("base-top", new Coords(15, 8), 2.0f, false, 0),
          new View("base-oblique", new Coords(15, 8), 1.45f, true, 0),
          new View("biodome", new Coords(24, 10), .55f, true, 20),
          new View("south-domes", new Coords(22, 13), .6f, true, 0),
          new View("glass-dome", new Coords(18, 13), .32f, true, -30),
          new View("habitat", new Coords(5, 10), .4f, true, 35),
          new View("habitats-north", new Coords(5, 3), .45f, true, 0),
          new View("radar", new Coords(27, 2), .22f, true, 0),
          new View("tank-halls", new Coords(10, 13), .32f, true, 0),
          new View("capsule-hall", new Coords(20, 2), .25f, true, 0),
          new View("light-well", new Coords(12, 3), .25f, true, 0),
          new View("solar-halls", new Coords(5, 10), .3f, true, 0),
          new View("colony-road", new Coords(20, 5), .2f, true, 25),
          new View("road-gates", new Coords(23, 12), .3f, true, -20),
          new View("annex-cap", new Coords(21, 11), .12f, true, -20),
          new View("podium-trim", new Coords(23, 11), .16f, true, -20));

    @Test
    void capturesLunarBase() throws Exception {
        float hour = Float.parseFloat(System.getProperty("megamek.gpu.boards.hour", "10"));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "multihex-buildings");
        Files.createDirectories(output.toPath());
        Board board = BoardFile.loadSheet(new File("data/boards", "Alien Worlds/32x17 Lunar Base.board2"), false, true);
        AtomicReference<BoardScene> scene = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(hour, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.update(scene.get());
                    terrain.animate(.5f, List.of());
                    for (View view : VIEWS) {
                        camera.setIsometric(view.isometric());
                        camera.orbit(view.orbit(), 0);
                        camera.camera.zoom = view.zoom();
                        camera.center(BoardGeometry.center(view.at(), scene.get().tile(view.at()).elevation()));
                        frame.render(terrain, camera, scene.get());
                        GpuReviewFrame.save(new File(output, String.format(Locale.ROOT, "%s-h%s.png", view.name(), hour)));
                        camera.orbit(-view.orbit(), 0);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Multi-hex building review", failure.get()); }
    }
}
