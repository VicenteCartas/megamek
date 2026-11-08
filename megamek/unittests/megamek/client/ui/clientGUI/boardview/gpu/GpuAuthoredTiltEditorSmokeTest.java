/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiNumber;
import megamek.common.board.Board;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real controls, scene publication and GL upload for an authored building with pitch and roll. */
@Tag("on-demand")
class GpuAuthoredTiltEditorSmokeTest {
    @Test void inspectorAndBrushUseThreeRadialControlsAndTiltKeepsOnlyRoofSupports() throws Exception {
        var at = new Coords(6, 6);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(12, 12), null);
            editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
            editor.command(new Command(Action.BRUSH_VALUE, "object:scale", "2"), null);
            editor.pointer(at, 0, 0, false); editor.finishStroke();
            String id = editor.board().getHex(at).getDecorations().getFirst().id();
            editor.command(new Command(Action.SELECT_AT, "6,6", ""), null);
            editor.command(new Command(Action.SELECT_OBJECT, id), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review/authored-tilt"); Files.createDirectories(output.toPath());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1400, 950); config.setInitialVisible(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                long deadline, nextFrame; int step;
                @Override public void create() { super.create(); deadline = System.nanoTime() + 180_000_000_000L; }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Tilt editor step " + step + ": " + GpuBoardTestUi.loadingState(this));
                        if (GpuBoardTestUi.loading(this) || GpuBoardTestUi.busy(this) || frames() < nextFrame) { return; }
                        Group details = GpuBoardTestUi.stage().getRoot().findActor("editor-details");
                        if (step == 0) {
                            axes(details, false);
                            type(details.findActor("editor-X"), "-45");
                        } else if (step == 1) {
                            assertEquals(45, source.editorState().objects().getFirst().rotationX());
                            type(details.findActor("editor-Y"), "31");
                        } else if (step == 2) {
                            var object = source.editorState().objects().getFirst();
                            assertEquals(45, object.rotationX()); assertEquals(-31, object.rotationY());
                            var members = source.takeFrame().scene().tiles().stream().filter(t -> t.cover().reference() != null).toList();
                            assertFalse(members.isEmpty());
                            assertTrue(members.stream().allMatch(t -> t.cover().interiors() == BuildingInteriors.NO_FLOORS));
                            assertTrue(GpuBoardTestUi.texts(details).contains(Messages.getString("BoardEditor.interiors.noFloors")));
                            GpuBoardTestUi.capture(new File(output, "inspector.png"));
                            source.editorCommand(new Command(Action.SAMPLE), source.takeFrame().boardGeneration());
                        } else if (step == 3) {
                            Group root = GpuBoardTestUi.stage().getRoot();
                            axes(root, true);
                            UiNumber number = root.findActor("editor-brush-object:rotation");
                            type(number.findActor("editor-Z"), "-37");
                        } else {
                            var brush = source.editorState().activeBrush();
                            assertEquals(37, brush.object().rotation());
                            assertEquals(45, brush.object().rotationX()); assertEquals(-31, brush.object().rotationY());
                            assertEquals(2, brush.property("bldg_interiors").value());
                            GpuBoardTestUi.capture(new File(output, "brush.png"));
                            com.badlogic.gdx.Gdx.app.exit();
                        }
                        step++; nextFrame = frames() + 20; deadline = System.nanoTime() + 120_000_000_000L;
                    } catch (Throwable error) { failure.set(error); com.badlogic.gdx.Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Authored tilt editor", failure.get()); }
    }

    private static void axes(Group scope, boolean brush) {
        Float y = null;
        for (String axis : List.of("X", "Y", "Z")) {
            Group number = brush ? scope.findActor("editor-brush-object:rotation" + (axis.equals("Z") ? "" : axis))
                  : ((TextField) scope.findActor("editor-" + axis)).getParent();
            assertInstanceOf(UiNumber.class, number);
            assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-dial-" + axis), "Every axis has a radial dial");
            TextField field = number.findActor("editor-" + axis);
            Vector2 position = field.localToStageCoordinates(new Vector2());
            if (y != null) { assertEquals(y, position.y, 1, "XYZ inputs stay on the same row"); }
            y = position.y;
            assertTrue(field.getWidth() >= 45, "The rotation value must not be squeezed out");
        }
    }

    private static void type(TextField field, String value) {
        assertNotNull(field);
        var stage = GpuBoardTestUi.stage(); stage.setKeyboardFocus(field); field.setText(value); stage.setKeyboardFocus(null);
    }
}
