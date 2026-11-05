/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Erase's real hover and clicks on roofs and floors, with ground, building and hex-stamp brushes. */
@Tag("on-demand")
class GpuEditorEraseSmokeTest {
    private static final Coords BUILDING = new Coords(4, 4);
    private static final int GROUND = 3, HEIGHT = 5;

    @Test void roofsAndFloorsCanBeErasedWithoutChangingGroundHeight() throws Exception {
        var session = new BoardEditorSession();
        var setup = new FutureTask<GpuMapSource>(() -> {
            Board board = Board.createEmptyBoard(9, 9);
            board.setHex(BUILDING, new Hex(GROUND, "building:4;bldg_elev:" + HEIGHT + ";bldg_cf:120", ""));
            session.game().setBoard(board);
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true); view.boardCamera.setPerspective(false);
                        view.boardCamera.center(BoardGeometry.center(BUILDING, GROUND + 2));
                        view.boardCamera.zoom(.5f / view.boardCamera.camera.zoom);
                        settle(view, source);
                        // A ray from inside the building hits an actual interior floor, not its shell or roof.
                        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                        var terrain = (GpuTerrain) field.get(view);
                        var ray = new Ray(BoardGeometry.center(BUILDING, GROUND + 2.5f), new Vector3(0, 0, -1));
                        var floor = terrain.selectionHit(source.takeFrame().scene(), ray);
                        assertNotNull(floor);
                        assertEquals(BUILDING, floor.coords());
                        assertEquals("building", floor.receiver(), "Interior floors identify their building for ERASE");
                        float height = ray.getEndPoint(new Vector3(), (float) Math.sqrt(floor.distance())).z / BoardGeometry.level();
                        assertEquals(GROUND + 2, height, .1f);
                        for (String brush : List.of("ground", "building", "stamp")) {
                            SwingUtilities.invokeAndWait(() -> {
                                if (brush.equals("stamp")) {
                                    send(session, Action.TOOL, "", "SELECT");
                                    session.pointer(new Coords(1, 1), 0, 0, false); session.finishStroke();
                                    send(session, Action.SAMPLE, "", "");
                                } else { send(session, Action.CHOOSE_BRUSH, brush, brush.equals("ground") ? "clear" : "default"); }
                                send(session, Action.TOOL, "", "ERASE");
                            });
                            settle(view, source);
                            erase(view, source, session, GROUND + HEIGHT, brush + "-roof");
                            erase(view, source, session, GROUND + 2, brush + "-floor");
                        }
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Erase on roofs and floors", failure.get()); }
    }

    private static void erase(GpuBattleView view, GpuMapSource source, BoardEditorSession session,
          float level, String label) throws Exception {
        Vector3 point = view.boardCamera.camera.project(BoardGeometry.center(BUILDING, level));
        int x = Math.round(point.x), y = Gdx.graphics.getHeight() - Math.round(point.y);
        var input = Gdx.input.getInputProcessor();
        input.mouseMoved(x, y); settle(view, source);
        var preview = source.editorPreview();
        assertEquals(BUILDING, preview.at(), label + " targets its building hex");
        assertTrue(preview.erase(), label + " has an erase preview");
        assertTrue(preview.hexes().contains(BUILDING));
        assertFalse(preview.standIns().isEmpty(), "The building to erase is highlighted");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        GpuBoardTestUi.capture(new File(output, "erase-" + label + ".png"));
        GpuBoardTestUi.withModifiers(0, () -> {
            input.touchDown(x, y, 0, Input.Buttons.LEFT);
            input.touchUp(x, y, 0, Input.Buttons.LEFT);
        });
        settle(view, source);
        SwingUtilities.invokeAndWait(() -> {
            Hex erased = session.board().getHex(BUILDING);
            assertFalse(erased.containsTerrain(Terrains.BUILDING), label + " erases the building");
            assertEquals(GROUND, erased.getLevel(), "Erase keeps the hex's ground level");
            send(session, Action.UNDO, "", "");
            assertTrue(session.board().getHex(BUILDING).containsTerrain(Terrains.BUILDING));
        });
        settle(view, source);
    }

    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        GpuBoardTestUi.present(view);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
