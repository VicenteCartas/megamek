/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real drawing path, scene publication and rendering keep both domes and connect them with one corridor. */
@Tag("on-demand")
class GpuDomeConnectionsSmokeTest {
    private static final Coords LARGE = new Coords(3, 6), SMALL = new Coords(3, 2);
    private static final String DOME = BoardMultiHexBuilding.Design.DOME.asset;

    @Test
    void connectingDomesPreservesTheirHeightsAndSurvivesUndoAndReopening(@TempDir Path directory) throws Exception {
        var setup = new FutureTask<List<BoardScene>>(() -> {
            var editor = new BoardEditorSession(); editor.open(board(), null);
            var source = new GpuMapSource(editor.game(), null, editor);
            try {
                List<BoardScene> scenes = new ArrayList<>();
                BoardScene before = source.takeFrame().scene(); scenes.add(before);
                var large = fits(before, LARGE).getFirst(); var small = fits(before, SMALL).getFirst();
                editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
                List<Coords> path = List.of(LARGE.translated(0), new Coords(3, 4), new Coords(3, 3), SMALL);
                var original = BoardFile.hexNode(editor.board().getHex(SMALL));
                for (int index = 0; index < path.size(); index++) { editor.pointer(path.get(index), 0, 0, index != 0); }
                var preview = editor.preview(SMALL, 0, 0, "ground");
                assertEquals(original, BoardFile.hexNode(editor.board().getHex(SMALL)), "Preview must leave the document untouched");
                assertEquals(2, preview.after().get(SMALL).terrainLevel(Terrains.BLDG_ELEV));
                editor.finishStroke(); source.refresh();
                assertEquals(2, editor.board().getHex(SMALL).terrainLevel(Terrains.BLDG_ELEV));
                assertEquals("building/glass-dome", editor.board().getHex(SMALL).getAppearance().get("building").variant());
                BoardScene joined = source.takeFrame().scene(); scenes.add(joined);
                assertEquals(Set.of(small, large), Set.copyOf(fits(joined, LARGE)));
                var connector = joined.tile(new Coords(3, 3));
                assertFalse(BoardMultiHexBuilding.layout(joined, connector, connector.features().getFirst()).tunnels().isEmpty());
                Path file = directory.resolve("domes.board2"); editor.save(file);
                assertFalse(Files.readString(file).contains("domeFootprint"), "The layout is derived, with no extra placement markers");
                editor.command(new Command(Action.UNDO), null); source.refresh();
                assertEquals(List.of(small), fits(source.takeFrame().scene(), SMALL));
                assertFalse(editor.board().getHex(3, 3).containsTerrain(Terrains.BUILDING));
                editor.command(new Command(Action.REDO), null); source.refresh();
                assertEquals(fits(joined, LARGE), fits(source.takeFrame().scene(), LARGE));
                editor.open(file); source.refresh();
                BoardScene reloaded = source.takeFrame().scene(); scenes.add(reloaded);
                assertEquals(fits(joined, LARGE), fits(reloaded, SMALL));
                return scenes;
            } finally { source.close(); }
        });
        SwingUtilities.invokeAndWait(setup);
        var scenes = setup.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1500, 1100); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera(); camera.resize(1500, 1100);
                    File output = new File("build/gpu-board-review/dome-corridors"); Files.createDirectories(output.toPath());
                    for (int index = 0; index < scenes.size(); index++) {
                        var scene = scenes.get(index); terrain.update(scene); terrain.animate(.5f, List.of());
                        for (boolean isometric : List.of(false, true)) {
                            camera.setIsometric(isometric); camera.camera.zoom = .65f;
                            camera.center(BoardGeometry.center(new Coords(3, 5), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, List.of("before", "joined", "reopened").get(index)
                                  + (isometric ? "-oblique.png" : "-top.png")));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Dome corridors", failure.get()); }
    }

    private static List<BoardMultiHexBuilding.Fit> fits(BoardScene scene, Coords at) {
        return BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(scene, scene.tile(at), DOME));
    }

    private static Board board() {
        Board board = Board.createEmptyBoard(9, 12);
        Map<Coords, Integer> large = new LinkedHashMap<>(), small = new LinkedHashMap<>();
        large.put(LARGE, 1);
        for (int direction = 0; direction < 6; direction++) { large.put(LARGE.translated(direction), 1); }
        Coords arm = LARGE.translated(3);
        for (int step = 0; step < 3; step++) { arm = arm.translated(3); large.put(arm, 1); }
        for (Coords at : List.of(SMALL, SMALL.translated(0), SMALL.translated(1))) { small.put(at, 2); }
        for (var group : List.of(large, small)) {
            group.forEach((at, height) -> {
                int exits = 0;
                for (int direction = 0; direction < 6; direction++) { if (group.containsKey(at.translated(direction))) { exits |= 1 << direction; } }
                Hex hex = new Hex(0, "building:1:" + exits + ";bldg_cf:15;bldg_elev:" + height, "grass");
                hex.setAppearance(Map.of("building", new HexAppearance("building/glass-dome", null, null, null)));
                board.setHex(at, hex);
            });
        }
        return board;
    }
}
