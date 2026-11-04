/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the real inspector and selection overlays for fixed and assembled authored buildings. */
@Tag("on-demand")
class GpuAuthoredEditorSmokeTest {
    @Test void inspectsAndEditsBothAuthoredBuildings() throws Exception {
        Coords hpg = new Coords(13, 7), lab = new Coords(28, 7);
        String[] ids = new String[2];
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(32, 24), null);
            for (int i = 0; i < 2; i++) {
                Coords at = i == 0 ? hpg : lab;
                editor.command(new Command(Action.ASSET, "buildings/authored/" + (i == 0 ? "hpg-headquarters" : "civic-arena")), null);
                editor.pointer(at, 0, 0, false); editor.finishStroke();
                ids[i] = editor.board().getHex(at).getDecorations().getFirst().id();
            }
            editor.command(new Command(Action.TOOL, "SELECT"), null);
            editor.command(new Command(Action.SELECT_AT, "13,7", ""), null);
            editor.command(new Command(Action.SELECT_OBJECT, ids[0]), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review/authored-buildings"); Files.createDirectories(output.toPath());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000); config.setInitialVisible(false);
        try (var previews = mockStatic(GpuEditorTerrainPreviews.class, CALLS_REAL_METHODS)) {
            previews.when(GpuEditorTerrainPreviews::kinds).thenAnswer(ignored -> List.of(source.takeFrame().scene()));
            new Lwjgl3Application(new GpuBattleView(source) {
                long deadline, nextFrame; int step, aimed = -1;
                @Override public void create() { super.create(); deadline = System.nanoTime() + 120_000_000_000L; }
                @Override public void render() {
                    try {
                        super.render();
                        if (step >= 3) {
                            var at = new Coords(step == 3 ? 13 : 28, 18);
                            if (aimed != step) { boardCamera.center(BoardGeometry.center(at, 0)); aimed = step; }
                            var pointer = boardCamera.camera.project(BoardGeometry.center(at, 0));
                            Gdx.input.getInputProcessor().mouseMoved(Math.round(pointer.x), Gdx.graphics.getHeight() - Math.round(pointer.y));
                        }
                        assertTrue(System.nanoTime() < deadline, () -> "Editor step " + step + ": " + GpuBoardTestUi.loadingState(this) + ", tool " + source.editorState().tool() + ", preview " + source.editorPreview().at() + " " + source.editorPreview().label());
                        if (GpuBoardTestUi.loading(this) || GpuBoardTestUi.busy(this) || frames() < nextFrame) { return; }
                        var texts = GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("editor-details"));
                        if (step == 0) {
                            assertTrue(texts.contains(Messages.getString("BoardEditor.authoredHeight")), texts.toString());
                            assertTrue(texts.stream().anyMatch(text -> text.startsWith(Messages.getString("BoardEditor.scale"))), texts.toString());
                            assertTrue(texts.contains(Messages.getString("BoardEditor.stretch")), texts.toString());
                            for (String axis : List.of("w", "l", "h")) {
                                assertTrue(texts.contains(Messages.getString("BoardEditor.stretch." + axis)), texts.toString());
                            }
                            GpuBoardTestUi.capture(new File(output, "hpg-editor.png"));
                            source.editorCommand(new Command(Action.SELECT_AT, "28,7", ""), source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.SELECT_OBJECT, ids[1]), source.takeFrame().boardGeneration());
                        } else if (step == 1) {
                            assertTrue(texts.contains(Messages.getString("BoardEditor.authoredHeight")), texts.toString());
                            source.editorCommand(new Command(Action.OBJECT_VALUE, "stretchZ", "2"), source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.BUILDING_INTERIORS, "2"), source.takeFrame().boardGeneration());
                        } else if (step == 2) {
                            assertEquals(2, source.editorState().buildingInteriors());
                            var members = source.takeFrame().scene().tiles().stream().filter(tile -> tile.cover().reference() != null
                                  && tile.cover().reference().id().equals(ids[1])).toList();
                            assertFalse(members.isEmpty());
                            assertTrue(members.stream().allMatch(tile -> tile.cover().height() == 2));
                            GpuBoardTestUi.capture(new File(output, "arena-editor.png"));
                            source.editorCommand(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), source.takeFrame().boardGeneration());
                        } else {
                            var preview = field(this, "editorPreview");
                            if (source.editorState().tool() != BoardEditorSession.Tool.PAINT || source.editorPreview().routes().isEmpty()) { return; }
                            if (field(preview, "built") != source.editorPreview() || field(preview, "comparing") != null) { return; }
                            assertFalse(((java.util.Collection<?>) field(preview, "ghosts")).isEmpty(), "Authored paint needs sliced ghost geometry: "
                                  + source.editorPreview().label() + ", hexes " + source.editorPreview().hexes() + ", routes " + source.editorPreview().routes().keySet());
                            GpuBoardTestUi.capture(new File(output, step == 3 ? "hpg-ghost.png" : step == 4 ? "arena-ghost.png" : "small-arena-ghost.png"));
                            if (step == 3) {
                                source.editorCommand(new Command(Action.ASSET, "buildings/authored/civic-arena"), source.takeFrame().boardGeneration());
                            } else if (step == 4) {
                                source.editorCommand(new Command(Action.BRUSH_VALUE, "object:scale", ".05"), source.takeFrame().boardGeneration());
                            } else if (step == 5) {
                                assertTrue(source.editorPreview().routes().values().stream().allMatch(tile -> tile.cover().reference() == null));
                                Gdx.app.exit();
                            } else { Gdx.app.exit(); }
                        }
                        step++; nextFrame = frames() + 15; deadline = System.nanoTime() + (step >= 3 ? 30_000_000_000L : 120_000_000_000L);
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Authored editor", failure.get()); }
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { /* The view fields live in its superclass. */ }
        }
        throw new NoSuchFieldException(name);
    }
}
