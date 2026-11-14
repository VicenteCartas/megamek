/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Input-to-install timings for a scale-seven projected decal on ordinary grass terrain; the edits keep every solid
 * mesh, re-plant only the grass of the hexes whose paint changed, and no blade grows through the decal.
 */
@Tag("on-demand")
class GpuEditorDecalLatencySmokeTest {
    @Test void reportsLargeDecalEditsAndKeepsDistantChunks() throws Exception {
        Coords owner = new Coords(10, 10);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(24, 24);
            board.setHex(owner, new Hex(0, "", "grass"));
            board.getHex(owner).setDecorations(List.of(new BoardDecoration("large", "decal", "decal/damage/rubble-light-path", null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false)));
            editor.game().setBoard(board); editor.pointer(owner, 0, 0, false, "large"); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1280, 800);
        // A hidden window's swap interval can be driver-throttled independently of the editor's work.
        config.useVsync(false); config.setForegroundFPS(60);
        List<Command> edits = List.of(new Command(Action.OBJECT_VALUE, "scale", "7"),
              new Command(Action.OBJECT_VALUE, "x", ".2"), new Command(Action.OBJECT_VALUE, "rotation", "35"),
              new Command(Action.REMOVE_OBJECT), new Command(Action.UNDO));
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 150_000_000_000L;
                int index;
                long started, revision, firstFrame;
                double maxFrame;
                Object distant;
                long warmUntil;
                List<Object> solidMeshes;
                final Map<Coords, FloatArray> unpainted = new java.util.HashMap<>();
                /**
                 * No installed blade on a recipient grows through the decal (its image's alpha), and once it is removed
                 * every hex it covered has the blades it had before, in their places and ranks.
                 */
                private String grass(GpuTerrain terrain, Map<?, ?> recipients) throws Exception {
                    // Blades place themselves on the ground map, which paint clears (GpuGroundMapSmokeTest).
                    return "blades place themselves";
                }
                /** Roots by place and rank: their height follows the installed detail level. */
                private List<List<Float>> placed(FloatArray roots) {
                    List<List<Float>> result = new java.util.ArrayList<>();
                    for (int i = 0; roots != null && i < roots.size; i += 4) {
                        result.add(List.of(roots.get(i), roots.get(i + 1), roots.get(i + 3)));
                    }
                    return result;
                }
                private Object field(Object object, String name) throws Exception {
                    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
                }
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                @Override public void render() {
                    try {
                        long before = System.nanoTime(); super.render();
                        if (started != 0) { maxFrame = Math.max(maxFrame, (System.nanoTime() - before) / 1e6); }
                        assertTrue(System.nanoTime() < deadline, "Large decal update stalled at " + index);
                        GpuTerrain terrain = terrain();
                        if (GpuBoardTestUi.loading(this) || terrain.busy()) { return; }
                        if (warmUntil == 0) {
                            GpuBoardTestUi.category("decals");
                            boardCamera.zoom(.45f); warmUntil = frames() + 90; return;
                        }
                        if (frames() < warmUntil) { return; }
                        if (started != 0) {
                            if (source.editorState().revision() == revision || frames() < firstFrame + 3) { return; }
                            var recipients = (Map<?, ?>) field(terrain, "installedPaint");
                            assertEquals(index == 3, recipients.isEmpty(), "Removing/restoring a decal updates its entire footprint");
                            if (index != 3) { assertTrue(recipients.size() > 30, "Scale seven spans many hexes"); }
                            assertSame(distant, ((List<?>) field(terrain, "chunks")).getLast());
                            List<Object> current = new java.util.ArrayList<>();
                            for (Object chunk : (List<?>) field(terrain, "chunks")) { current.addAll((List<?>) field(field(chunk, "ground"), "opaque")); }
                            assertEquals(solidMeshes, current, "Paint edits preserve every installed solid terrain mesh");
                            String grass = grass(terrain, (Map<?, ?>) recipients);
                            System.out.printf("DECAL %s recipients=%d input-to-install=%.1fms max-frame=%.1fms %s%n",
                                  edits.get(index), recipients.size(), (System.nanoTime() - started) / 1e6, maxFrame, grass);
                            index++; started = 0;
                            if (index == edits.size()) { Gdx.app.exit(); return; }
                        }
                        if (distant == null) {
                            distant = ((List<?>) field(terrain, "chunks")).getLast();
                            solidMeshes = new java.util.ArrayList<>();
                            for (Object chunk : (List<?>) field(terrain, "chunks")) { solidMeshes.addAll((List<?>) field(field(chunk, "ground"), "opaque")); }
                        }
                        revision = source.editorState().revision(); firstFrame = frames(); maxFrame = 0; started = System.nanoTime();
                        source.editorCommand(edits.get(index), source.takeFrame().boardGeneration());
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Large decal latency", failure.get()); }
    }
}
