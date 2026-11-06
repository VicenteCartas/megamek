/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The real inspector switches one raised hex's shell and floors together and hides the choice on its lower neighbour. */
@Tag("on-demand")
class GpuBuildingProfileSmokeTest {
    private static final Coords TALL = new Coords(2, 2), OTHER = new Coords(5, 2);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void inspectorChangesOneHexAndBothProfilesEncloseTheirFloors(boolean habitat) throws Exception {
        int tallHeight = habitat ? 4 : 9, otherHeight = habitat ? 2 : 4;
        var setup = new FutureTask<GpuMapSource>(() -> {
            Board board = Board.createEmptyBoard(8, 7);
            for (Coords at : List.of(TALL, OTHER)) {
                int exits = habitat && at.equals(TALL) ? 10 : 8;
                board.setHex(at, new Hex(0, "building:1:" + exits + ";bldg_elev:" + (at.equals(TALL) ? tallHeight : otherHeight) + ";bldg_cf:15", "grass"));
                board.setHex(at.translated(3), new Hex(0, "building:1:1;bldg_elev:1;bldg_cf:15", "grass"));
                if (habitat) {
                    for (Coords hex : List.of(at, at.translated(3))) {
                        board.getHex(hex).setAppearance(Map.of("building", new HexAppearance("building/lunar-habitat", null, null, null)));
                    }
                }
            }
            if (habitat) {
                Hex neighbour = new Hex(0, "building:1:16;bldg_elev:1;bldg_cf:15", "grass");
                neighbour.setAppearance(Map.of("building", new HexAppearance("building/lunar-habitat", null, null, null)));
                board.setHex(TALL.translated(1), neighbour);
            }
            var editor = new BoardEditorSession(); editor.open(board, null);
            editor.command(new Command(Action.TOOL, "SELECT"), null);
            editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
            editor.command(new Command(Action.COMPONENT, "building"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1400, 1000); config.setInitialVisible(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                long deadline, nextFrame;
                int step;
                boolean framed;

                @Override public void create() {
                    super.create(); deadline = System.nanoTime() + 120_000_000_000L;
                }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Profile editor timed out: " + GpuBoardTestUi.loadingState(this));
                        if (GpuBoardTestUi.loading(this) || GpuBoardTestUi.busy(this) || frames() < nextFrame) { return; }
                        if (habitat && !framed) {
                            boardCamera.camera.zoom = .3f; boardCamera.center(BoardGeometry.center(TALL, 2));
                            framed = true; nextFrame = frames() + 10; return;
                        }
                        var control = GpuBoardTestUi.stage().getRoot().findActor("editor-building-profile");
                        GpuTerrain terrain = (GpuTerrain) field(this, GpuBattleView.class, "terrain");
                        if (step == 0) { inspectBatches(terrain); }
                        long generation = source.takeFrame().boardGeneration();
                        if (step == 2) {
                            assertNull(control, "The shorter hex has no upper-floor choice");
                            command(source, new Command(Action.UNDO), generation);
                            command(source, new Command(Action.SELECT_AT, "2,2", ""), generation);
                            command(source, new Command(Action.COMPONENT, "building"), generation);
                        } else {
                            assertNotNull(control);
                            assertTrue(GpuBoardTestUi.texts(control).contains(step == 1 ? "Step back" : "Keep footprint"),
                                  "Profile label at step " + step + ": " + GpuBoardTestUi.texts(control));
                            if (step == 0 || step == 1) {
                                File capture = new File("build/gpu-board-review/" + (habitat ? "habitat" : "building")
                                      + "-profile-" + (step == 0 ? "keep" : "stepped") + ".png");
                                assertTrue(capture.getParentFile().isDirectory() || capture.getParentFile().mkdirs());
                                GpuBoardTestUi.capture(capture);
                            }
                            floors(assembly(terrain, TALL), tallHeight, step == 1);
                            floors(assembly(terrain, OTHER), otherHeight, false);
                            if (step == 0) { command(source, new Command(Action.BUILDING_PROFILE, "stepped"), generation); }
                            else if (step == 1) {
                                command(source, new Command(Action.SELECT_AT, "2,3", ""), generation);
                                command(source, new Command(Action.COMPONENT, "building"), generation);
                            } else { assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError()); Gdx.app.exit(); }
                        }
                        step++; nextFrame = frames() + 10;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Building profiles", failure.get()); }
    }

    private static void floors(GpuBuilding.Assembly building, int height, boolean stepped) {
        assertNotNull(building); assertNotNull(building.interior());
        assertEquals(stepped, building.recipe().stepped());
        var triangles = GpuTerrain.triangles(building.interior().getNode("floors"));
        for (int level = 0; level < height; level++) {
            var floor = GpuBuildingInterior.plane(triangles, (level == 0 ? .002f : level) * 18, true);
            assertFalse(floor.isEmpty(), "Each occupied level needs its floor");
            var outline = GpuBuildingInterior.area(floor);
            assertTrue(outline.contains(0, 0), "The floor must cover its center at storey " + level);
            assertEquals(!stepped || level == 0, outline.contains(0, -34), "Connection footprint at storey " + level);
        }
    }

    private static GpuBuilding.Assembly assembly(GpuTerrain terrain, Coords at) throws Exception {
        for (Object chunk : (List<?>) field(terrain, terrain.getClass(), "chunks")) {
            for (Object prop : (List<?>) field(chunk, chunk.getClass(), "props")) {
                if (at.equals(field(prop, prop.getClass(), "coords"))
                      && field(prop, prop.getClass(), "building") instanceof GpuBuilding.Assembly building) { return building; }
            }
        }
        return null;
    }

    private static Object field(Object object, Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private static void command(GpuMapSource source, Command command, long generation) throws Exception {
        SwingUtilities.invokeAndWait(() -> source.editorCommand(command, generation));
    }

    private static void inspectBatches(GpuTerrain terrain) throws Exception {
        Object trees = field(terrain, terrain.getClass(), "trees");
        var batches = (java.util.Map<?, ?>) field(trees, trees.getClass(), "sharedParts");
        for (var entry : batches.entrySet()) {
            for (Object batch : (Object[]) entry.getValue()) {
                if (batch == null) { continue; }
                var mesh = (com.badlogic.gdx.graphics.Mesh) field(batch, batch.getClass(), "mesh");
                var box = mesh.calculateBoundingBox();
                short[] indices = new short[mesh.getNumIndices()]; mesh.getIndices(indices);
                for (short index : indices) {
                    assertTrue(Short.toUnsignedInt(index) < mesh.getNumVertices(), "Instanced index outside its vertex buffer");
                }
                for (Object value : (List<?>) field(batch, batch.getClass(), "parts")) {
                    var part = ((com.badlogic.gdx.graphics.g3d.Renderable) value).meshPart;
                    assertTrue(part.offset + part.size <= mesh.getNumIndices(), "Instanced draw outside its index buffer");
                }
                if (Math.abs(box.min.x) > 60 || Math.abs(box.max.x) > 60 || Math.abs(box.min.y) > 60 || Math.abs(box.max.y) > 60) {
                    throw new AssertionError("Corrupt instanced part " + entry.getKey() + " bounds " + box);
                }
            }
        }
    }
}
