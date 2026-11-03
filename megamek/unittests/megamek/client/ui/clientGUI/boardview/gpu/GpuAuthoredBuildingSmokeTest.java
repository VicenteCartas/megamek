/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The shipped examples exercise real slicing, material upload, interiors and anchor-hex collapse. */
@Tag("on-demand")
class GpuAuthoredBuildingSmokeTest {
    @Test void rendersBothExamplesAndKeepsTheRemainingSlicesAfterAnchorCollapse() throws Exception {
        File output = new File("build/gpu-board-review/authored-buildings");
        Files.createDirectories(output.toPath());
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(32, 24), null);
        Coords hpg = new Coords(13, 10), lab = new Coords(28, 10);
        place(editor, hpg, "hpg-headquarters", 19);
        place(editor, lab, "xenobiology-station", -12);
        editor.save(output.toPath().resolve("Authored Buildings.board2"));
        Board board = BoardFile.read(output.toPath().resolve("Authored Buildings.board2"));
        var original = capture(board);
        for (var policy : List.of(BuildingInteriors.NO_FLOORS, BuildingInteriors.NO)) {
            for (var tile : original.tiles()) {
                if (tile.cover().reference() != null) { BuildingInteriors.set(board.getHex(tile.coords()), policy); }
            }
            editor.open(board, null);
            editor.save(output.toPath().resolve(policy.id() + ".board2"));
        }
        BoardScene hallScene = capture(BoardFile.read(output.toPath().resolve("noFloors.board2")));
        BoardScene sealedScene = capture(BoardFile.read(output.toPath().resolve("no.board2")));
        board = BoardFile.read(output.toPath().resolve("Authored Buildings.board2"));
        var reference = board.getHex(hpg).getBuildingReference();
        assertNotNull(reference);
        var surviving = original.tiles().stream().filter(t -> t.cover().reference() != null
              && t.cover().reference().id().equals(reference.id()) && !t.coords().equals(hpg)).findFirst().orElseThrow();
        var feature = surviving.features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElseThrow();
        var stable = GpuAuthoredBuilding.layout(original, surviving, feature);
        board.collapseBuilding(hpg);
        assertFalse(board.getHex(hpg).containsTerrain(Terrains.BUILDING));
        var collapsed = capture(board);
        var remaining = collapsed.tile(surviving.coords());
        assertEquals(stable, GpuAuthoredBuilding.layout(collapsed, remaining,
              remaining.features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElseThrow()));
        assertTrue(collapsed.tile(hpg).features().stream().noneMatch(GpuAuthoredBuilding::supports));
        assertEquals(1, board.getHex(hpg).getDecorations().size());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuAssets assets = new GpuAssets();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var slice = assets.authoredBuilding(GpuAuthoredBuilding.prepare(stable, GpuAuthoredBuilding.load(stable.object().asset())));
                    assertFalse(slice.roof().isEmpty());
                    assertNotNull(slice.interior());
                    var bounds = slice.levels().getFirst().calculateBoundingBox(new BoundingBox());
                    assertTrue(bounds.isValid());
                    var hall = assets.authoredBuilding(GpuAuthoredBuilding.prepare(new GpuAuthoredBuilding.Layout(stable.cell(), stable.anchor(), stable.object(),
                          stable.base(), stable.ground(), stable.height(), BuildingInteriors.NO_FLOORS, stable.members()), GpuAuthoredBuilding.load(stable.object().asset())));
                    assertEquals(1, hall.interior().getNode("floors").parts.size);
                    var sealed = assets.authoredBuilding(GpuAuthoredBuilding.prepare(new GpuAuthoredBuilding.Layout(stable.cell(), stable.anchor(), stable.object(),
                          stable.base(), stable.ground(), stable.height(), BuildingInteriors.NO, stable.members()), GpuAuthoredBuilding.load(stable.object().asset())));
                    assertNull(sealed.interior());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1600, 1000);
                    camera.setIsometric(true);
                    camera.camera.zoom = 1.15f;
                    terrain.update(original);
                    for (Coords focus : List.of(hpg, lab)) {
                        camera.camera.zoom = focus.equals(hpg) ? 1.15f : .32f;
                        camera.center(BoardGeometry.center(focus, 1));
                        terrain.animate(1, List.of());
                        frame.render(terrain, camera, original);
                        GpuReviewFrame.save(new File(output, focus.equals(hpg) ? "hpg.png" : "station.png"));
                    }
                    camera.camera.zoom = .5f;
                    camera.center(BoardGeometry.center(hpg, 1));
                    terrain.animate(1, List.of(), .25f, hpg, (original.tile(hpg).cover().height() - 1) * BoardGeometry.level());
                    assertFalse(faded(terrain).isEmpty());
                    frame.render(terrain, camera, original);
                    GpuReviewFrame.save(new File(output, "interior.png"));
                    terrain.update(hallScene);
                    terrain.animate(1, List.of(), .25f, hpg, BoardGeometry.level());
                    assertFalse(faded(terrain).isEmpty());
                    for (Object prop : faded(terrain)) {
                        var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) field(prop, "instance");
                        var cut = instance.materials.first().get(GpuBuildingCutaway.class, GpuBuildingCutaway.TYPE);
                        assertNotNull(cut);
                        assertEquals(0, cut.floor);
                        assertEquals(Float.MAX_VALUE, cut.ceiling);
                    }
                    frame.render(terrain, camera, hallScene);
                    GpuReviewFrame.save(new File(output, "no-floors.png"));
                    terrain.update(sealedScene);
                    terrain.animate(1, List.of(), .25f, hpg, 0);
                    assertTrue(faded(terrain).isEmpty());
                    frame.render(terrain, camera, sealedScene);
                    GpuReviewFrame.save(new File(output, "no-interiors.png"));
                    terrain.update(collapsed);
                    terrain.animate(1, List.of());
                    frame.render(terrain, camera, collapsed);
                    GpuReviewFrame.save(new File(output, "collapsed.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); assets.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored building review", failure.get()); }
    }

    private static void place(BoardEditorSession editor, Coords at, String asset, int rotation) {
        editor.command(new Command(Action.ASSET, "buildings/authored/" + asset), null);
        editor.pointer(at, 0, 0, false); editor.finishStroke();
        editor.command(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, editor.board().getHex(at).getDecorations().getFirst().id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "rotation", Integer.toString(rotation)), null);
    }

    private static BoardScene capture(Board board) throws Exception {
        AtomicReference<BoardScene> scene = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> { fixture.source.refresh(); scene.set(fixture.source.takeFrame().scene()); });
        }
        return scene.get();
    }

    private static List<Object> faded(GpuTerrain terrain) throws Exception {
        List<Object> result = new java.util.ArrayList<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) { result.addAll((java.util.Set<?>) field(chunk, "faded")); }
        return result;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
