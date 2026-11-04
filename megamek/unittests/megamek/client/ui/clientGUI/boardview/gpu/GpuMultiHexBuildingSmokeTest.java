/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.client.ui.boardeditor.BoardEditorSession;
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
    private record View(String name, Coords at, float zoom, boolean isometric, float orbit, float tilt) {
        View(String name, Coords at, float zoom, boolean isometric, float orbit) { this(name, at, zoom, isometric, orbit, 0); }
    }

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
          new View("junction-top", new Coords(22, 12), .22f, false, 0),
          new View("junction-oblique", new Coords(21, 11), .16f, true, -20),
          new View("corner-gate", new Coords(23, 12), .08f, true, -20),
          new View("corner-pillars", new Coords(21, 11), .09f, true, -20),
          new View("corner-courtyard", new Coords(22, 12), .12f, true, -20),
          new View("ring-roof", new Coords(23, 10), .4f, false, 0),
          new View("biodome-east-join", new Coords(25, 11), .18f, true, 0),
          new View("biodome-north-join", new Coords(23, 6), .18f, true, 130),
          new View("podium-trim", new Coords(23, 11), .16f, true, -20),
          new View("review-front", new Coords(22, 12), .19f, true, -20),
          new View("review-east", new Coords(22, 12), .19f, true, 70),
          new View("review-back", new Coords(22, 12), .22f, true, 160),
          new View("review-west", new Coords(22, 12), .19f, true, 250),
          new View("review-low-front", new Coords(22, 12), .16f, true, -20, 20),
          new View("review-low-side", new Coords(22, 12), .16f, true, 70, 20),
          new View("review-gate-left", new Coords(23, 12), .09f, true, 40, 10),
          new View("review-gate-right", new Coords(23, 12), .09f, true, -80, 10),
          new View("review-ring-east", new Coords(25, 11), .18f, true, 70),
          new View("review-ring-north", new Coords(23, 6), .18f, true, 160),
          new View("review-ring-top", new Coords(23, 10), .27f, false, 0),
          new View("review-roof-detail", new Coords(22, 12), .045f, false, 0),
          new View("review-roof-oblique", new Coords(22, 12), .055f, true, -20, -10));

    @Test
    void capturesLunarBase() throws Exception {
        float hour = Float.parseFloat(System.getProperty("megamek.gpu.boards.hour", "10"));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "multihex-buildings");
        Files.createDirectories(output.toPath());
        Board board = BoardFile.loadSheet(new File("data/boards", "Alien Worlds/32x17 Lunar Base.board2"), false, true);
        var capture = new FutureTask<BoardScene>(() -> {
            var editor = new BoardEditorSession(); editor.open(board, null);
            try (var source = new GpuMapSource(editor.game(), null, editor)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(capture);
        BoardScene scene = capture.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(hour, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    checkLunarMaterialResolution();
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    for (View view : VIEWS) {
                        if (view.name().startsWith("review-")) { continue; }
                        camera.setIsometric(view.isometric());
                        camera.orbit(view.orbit(), view.tilt());
                        camera.camera.zoom = view.zoom();
                        camera.center(BoardGeometry.center(view.at(), scene.tile(view.at()).elevation()));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, String.format(Locale.ROOT, "%s-h%s.png", view.name(), hour)));
                        camera.orbit(-view.orbit(), -view.tilt());
                    }
                    // Inspect the physical materials separately from projected legacy paint. Missing legacy art in
                    // headless test installations otherwise covers the roof with a placeholder and hides its UVs.
                    BoardScene materials = materials(scene);
                    terrain.update(materials);
                    for (View view : VIEWS) {
                        if (!view.name().startsWith("junction") && !view.name().equals("ring-roof")
                              && !view.name().startsWith("corner-") && !view.name().startsWith("review-")) { continue; }
                        camera.setIsometric(view.isometric()); camera.orbit(view.orbit(), view.tilt());
                        camera.camera.zoom = view.zoom();
                        camera.center(BoardGeometry.center(view.at(), materials.tile(view.at()).elevation()));
                        frame.render(terrain, camera, materials);
                        GpuReviewFrame.save(new File(output, view.name() + "-materials.png"));
                        camera.orbit(-view.orbit(), -view.tilt());
                    }
                    frame.configure(new BoardAtmosphere.Settings(22, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    for (View lamps : VIEWS) {
                        if (!List.of("corner-pillars", "review-low-front", "review-east", "review-back").contains(lamps.name())) { continue; }
                        camera.setIsometric(true); camera.orbit(lamps.orbit(), lamps.tilt()); camera.camera.zoom = lamps.zoom();
                        camera.center(BoardGeometry.center(lamps.at(), materials.tile(lamps.at()).elevation()));
                        frame.render(terrain, camera, materials);
                        GpuReviewFrame.save(new File(output, lamps.name() + "-night.png"));
                        camera.orbit(-lamps.orbit(), -lamps.tilt());
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

    private static void checkLunarMaterialResolution() {
        var assets = new GpuAssets();
        try {
            for (String material : List.of(BoardMultiHexBuilding.WALL, BoardMultiHexBuilding.ROOF,
                  BoardMultiHexBuilding.PIER, BoardMultiHexBuilding.WINDOW)) {
                for (var texture : List.of(assets.material(material), assets.materialNormal(material))) {
                    var level = BufferUtils.newIntBuffer(1);
                    texture.bind();
                    Gdx.gl.glGetTexParameteriv(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, level);
                    assertEquals(0, level.get(0), material + " must retain its authored detail rather than force a 128px mip");
                }
            }
        } finally {
            assets.dispose();
        }
    }

    private static BoardScene materials(BoardScene scene) {
        var tiles = scene.tiles().stream().map(t -> new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(),
              t.roadExits(), t.surface(), null, null, t.features(), t.text(), t.liquid(), t.tileset(), t.detailedGround(),
              t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(), t.groundCover(),
              t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), t.appearance(), t.cover(), t.barren(), t.deckRoad(),
              t.deckRoadExits(), t.deckMud(), t.railBed(), t.corridor(), t.deckCorridor(), t.backdrop(), t.minimap())).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, scene.units(), scene.plannedPath(),
              scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(), scene.rangeBorders(),
              scene.markers(), scene.tactical(), scene.rangeLabels(), scene.fieldOfView(), scene.electronicWarfare(),
              scene.designations(), scene.starShells());
    }
}
