/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.MegaMekController;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Swing input routing and the real clipboard, with the game's global shortcut handlers present. */
@Tag("on-demand")
class GpuShaderEditorInputSmokeTest {
    @Test
    void wheelOverTheCodeScrollsAndControlWheelOnlyZooms() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = editor(("// " + "long shader line ".repeat(20) + "\n").repeat(160));
            try {
                editor.open("fixture.frag");
                editor.show();
                ((JFrame) field(editor, "window")).validate();
                var text = text(editor);
                var scroll = (RTextScrollPane) SwingUtilities.getAncestorOfClass(RTextScrollPane.class, text);
                int font = text.getFont().getSize();
                wheel(text, 0, 1);
                assertTrue(scroll.getVerticalScrollBar().getValue() > 0, "Wheel over code must reach the scroll pane");
                assertEquals(font, text.getFont().getSize());
                wheel(text, InputEvent.SHIFT_DOWN_MASK, 1);
                assertTrue(scroll.getHorizontalScrollBar().getValue() > 0, "Shift+wheel scrolls long lines horizontally");
                scroll.getVerticalScrollBar().setValue(0);
                wheel(text, InputEvent.CTRL_DOWN_MASK, -1);
                assertEquals(font + 1, text.getFont().getSize());
                assertEquals(0, scroll.getVerticalScrollBar().getValue(), "Zoom must not also scroll");
            } catch (Exception error) { throw new AssertionError(error); }
            finally { editor.close(); }
        });
    }

    @Test
    void clipboardUndoAndTypingStayInTheEditorInsteadOfReachingGameCommands() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = editor("initial source");
            var gameKeys = new TestController();
            var commands = new AtomicInteger();
            for (var bind : new KeyCommandBind[] { KeyCommandBind.FIRE, KeyCommandBind.UNDO, KeyCommandBind.REDO }) {
                gameKeys.registerKeyCommandBind(bind);
                gameKeys.registerCommandAction(bind, () -> true, commands::incrementAndGet, () -> { });
            }
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            var previous = clipboard.getContents(null);
            try {
                editor.open("fixture.frag");
                var text = text(editor);
                text.selectAll();
                clipboard.setContents(new StringSelection("shader clipboard text"), null);
                press(text, gameKeys, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
                assertEquals("shader clipboard text", text.getText());
                press(text, gameKeys, KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK);
                clipboard.setContents(new StringSelection("copy did not run"), null);
                press(text, gameKeys, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK);
                assertEquals(text.getText(), clipboard.getData(DataFlavor.stringFlavor));
                press(text, gameKeys, KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK);
                assertEquals("", text.getText());
                press(text, gameKeys, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
                assertEquals("shader clipboard text", text.getText());
                press(text, gameKeys, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
                assertEquals("", text.getText());
                press(text, gameKeys, KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
                assertEquals("shader clipboard text", text.getText());
                press(text, gameKeys, KeyEvent.VK_F, 0);
                dispatch(text, gameKeys, new KeyEvent(text, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'f'));
                assertEquals("shader clipboard textf", text.getText());
                assertEquals(0, commands.get(), "Typing and undo must not fire weapons or undo game orders");
                assertTrue(gameKeys.dispatchKeyEvent(key(new JPanel(), KeyCommandBind.FIRE.key, KeyCommandBind.FIRE.modifiers)));
                assertEquals(1, commands.get(), "The board's own commands still work");
            } catch (Exception error) { throw new AssertionError(error); }
            finally {
                editor.close();
                gameKeys.close();
                clipboard.setContents(previous == null ? new StringSelection("") : previous, null);
            }
        });
    }

    private static GpuShaderEditor editor(String source) {
        return new GpuShaderEditor(Map.of("fixture.frag", new GpuShaderSource.FileSource(source, Path.of("fixture.frag"), false)),
              "input test", (sources, completed) -> { }, callback -> { });
    }

    private static RSyntaxTextArea text(GpuShaderEditor editor) throws Exception {
        var documents = (Map<?, ?>) field(editor, "documents");
        return (RSyntaxTextArea) field(documents.get("fixture.frag"), "text");
    }

    private static void wheel(RSyntaxTextArea text, int modifiers, int rotation) {
        text.dispatchEvent(new MouseWheelEvent(text, MouseWheelEvent.MOUSE_WHEEL, 0, modifiers,
              20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation));
    }

    private static KeyEvent key(java.awt.Component source, int code, int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static void press(RSyntaxTextArea text, TestController game, int code, int modifiers) {
        dispatch(text, game, key(text, code, modifiers));
        dispatch(text, game, new KeyEvent(text, KeyEvent.KEY_RELEASED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED));
    }

    private static void dispatch(RSyntaxTextArea text, TestController game, KeyEvent event) {
        assertFalse(game.dispatchKeyEvent(event), "Game shortcuts must leave text events alone");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(text, event);
    }

    private static class TestController extends MegaMekController {
        void close() { stopAllRepeating(); keyRepeatTimer.cancel(); }
    }
}
