/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A freshly painted habitat has no stored kit variant; the real inspector must show the kit's default. */
@Tag("on-demand")
class GpuEditorBuildingSmokeTest {
    @Test void newlyPaintedLunarHabitatCanBeInspectedAndItsVariantChanged() throws Exception {
        Coords at = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.open(Board.createEmptyBoard(5, 5), null);
            editor.command(new Command(Action.CHOOSE_BRUSH, "building", "building/lunar-habitat"), null);
            editor.pointer(at, 0, 0, false);
            editor.finishStroke();
            editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
            editor.pointer(new Coords(1, 4), 0, 0, false); editor.finishStroke();
            editor.command(new Command(Action.DESIGN_ROTATION, "building", "45"), null);
            editor.command(new Command(Action.BUILDING_COLOUR, "#d6ccab"), null);
            editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
            editor.command(new Command(Action.BRUSH_VALUE, "terrain:building", "3"), null);
            editor.command(new Command(Action.BRUSH_VALUE, "terrain:bldg_elev", "3"), null);
            editor.pointer(new Coords(4, 3), 0, 0, false); editor.finishStroke();
            editor.command(new Command(Action.TOOL, "SELECT"), null);
            editor.command(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), null);
            editor.command(new Command(Action.COMPONENT, "building"), null);
            assertNull(HexAppearance.kitVariant(editor.snapshot().appearance()));
            assertEquals(2, editor.snapshot().kitVariants().size());
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        config.setInitialVisible(false);
        try {
            // Warm this test's building materials; the full palette also builds every unrelated terrain and theme.
            new Lwjgl3Application(new GpuBattleView(source) {
                long deadline;
                int step;
                long nextFrame;

                @Override public void create() {
                    super.create();
                    deadline = System.nanoTime() + 120_000_000_000L;
                }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline,
                              () -> "Habitat inspector timed out at step " + step + ": " + GpuBoardTestUi.loadingState(this));
                        if (GpuBoardTestUi.loading(this) || GpuBoardTestUi.busy(this) || frames() < nextFrame) { return; }
                        var state = source.editorState();
                        var names = state.kitVariants();
                        var texts = GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("editor-details"));
                        assertTrue(texts.contains(names.get(step == 1 ? 1 : 0)), "Step " + step + ": " + texts);
                        if (step == 0) {
                            assertNull(HexAppearance.kitVariant(state.appearance()));
                            source.editorCommand(new Command(Action.KIT_VARIANT, names.get(1)), source.takeFrame().boardGeneration());
                        } else if (step == 1) {
                            assertEquals(names.get(1), HexAppearance.kitVariant(state.appearance()));
                            source.editorCommand(new Command(Action.UNDO), source.takeFrame().boardGeneration());
                            // Undo clears terrain selection; select the habitat again to inspect its restored default.
                            source.editorCommand(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""),
                                  source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.COMPONENT, "building"), source.takeFrame().boardGeneration());
                        } else if (step == 2) {
                            assertNull(HexAppearance.kitVariant(state.appearance()));
                            source.editorCommand(new Command(Action.DESIGN_ROTATION, "building", "45"), source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.BUILDING_COLOUR, "#72949b"), source.takeFrame().boardGeneration());
                        } else {
                            assertEquals(45, state.appearance().get("building").rotation());
                            assertEquals("#72949b", state.appearance().get("building").colours().slot(0));
                            var feature = source.takeFrame().scene().tiles().stream().filter(tile -> tile.coords().equals(at))
                                  .flatMap(tile -> tile.features().stream()).filter(f -> f.kind() == BoardScene.FeatureKind.BUILDING)
                                  .findFirst().orElseThrow();
                            assertEquals(-45, feature.rotation());
                            assertEquals("#72949b", feature.colours().slot(0));
                            File capture = new File("build/gpu-board-review/building-editor-v3.png");
                            assertTrue(capture.getParentFile().isDirectory() || capture.getParentFile().mkdirs());
                            GpuBoardTestUi.capture(capture);
                            Gdx.app.exit();
                        }
                        step++; nextFrame = frames() + 10;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Habitat inspector failed", failure.get()); }
    }

}
