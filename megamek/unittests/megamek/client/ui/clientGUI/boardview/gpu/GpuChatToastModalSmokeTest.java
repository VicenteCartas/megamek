/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.FieldKind;
import megamek.client.ui.clientGUI.boardview.overlay.BoardToastOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.VictoryHexPropertiesPane;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.equipment.ObjectiveScoringScheme.SchemePreset;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The chat panel, the toast stack and the native modal dialog (C.1 G15) in the real HUD and in the component harness:
 * the chat beside shot 16 with Enter, Esc, the draft and the unread dot; MegaMek's five-deep toast stack with its
 * levels and durations; every kind of modal request drawn and answered exactly once, also before a board exists and
 * through the real modal bridge, newest dialog first; a message's image, a form's live fields and a bot order's hex
 * pick on the hint line (N7c).
 */
@Tag("on-demand")
class GpuChatToastModalSmokeTest {
    private static final String DRAFT = "Moving Alpha to the ridge";
    private static final String MOCK = "16-chat.jpg";
    /** Shot 16's chat panel (right 20, bottom 66, 340 x 320) and Chat button (left of Home), top-left corners. */
    private static final int MOCK_PANEL_X = 1560;
    private static final int MOCK_PANEL_Y = 694;
    private static final int MOCK_BUTTON_X = 1740;
    private static final int MOCK_BUTTON_Y = 1026;
    /** The toast slot of the HUD: 640 wide, from 90 below the top to 232 above the bottom. */
    private static final float TOAST_WIDTH = 640;
    private static final float TOAST_BOTTOM = 232;
    private static final float TOAST_TOP = 90;
    /** The time step of the toast clock, as a frame of a 50 Hz window. */
    private static final float STEP = .02f;
    private static final long WAIT_SECONDS = 20;

    /** The real HUD over a mocked source, one stage unit per back-buffer pixel at the harness's size. */
    private static final class Hud implements AutoCloseable {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuChat chat = mock(GpuChat.class);
        final SpriteBatch batch = new SpriteBatch();
        final GpuHud hud;

        Hud(GpuHudTestStage harness) {
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            when(source.chat()).thenReturn(chat);
            hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(), mock(GpuBoardTuning.class),
                  new GpuPlaybackHistory(new UnitPlayback()));
            float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
            hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density), 1 / density);
        }

        /** One frame of the window: the snapshots and the pending dialog, then a draw, which lays the HUD out. */
        void show(GpuBoardSource.Frame frame, DialogRequest dialog) {
            hud.update(frame, GpuHud.HudView.EMPTY, dialog, GpuHudInputTest.preferences());
            ScreenUtils.clear(.1f, .13f, .13f, 1, true);
            hud.draw();
        }

        /** A key press and release, as the board view passes them on; true when the HUD consumed the press. */
        boolean key(int key, int awt) {
            boolean consumed = hud.keyDown(key, awt, 0);
            hud.keyUp(key, awt);
            return consumed;
        }

        void type(String text) {
            for (char character : text.toCharArray()) {
                hud.keyTyped(character);
            }
        }

        <T extends Actor> T find(String name) {
            return hud.stage.getRoot().findActor(name);
        }

        Actor focus() {
            return hud.stage.getKeyboardFocus();
        }

        @Override
        public void close() {
            hud.dispose();
            batch.dispose();
        }
    }

    // ---------------------------------------------------------------- chat

    @Test
    void chatOpensWithEnterSendsTheDraftKeepsItWhileClosedAndMatchesShot16() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                String system = Messages.getString("GpuBoard.hud.chat.system");
                List<GpuChat.ChatLine> lines = new ArrayList<>(List.of(
                      new GpuChat.ChatLine(1, system, "Local chat preview. Messages stay in this page.", true, 0),
                      new GpuChat.ChatLine(2, system, Messages.getString("GpuBoard.hud.chat.initiativeLine", 3, 3,
                            10, "Princess"), true, 0)));
                hud.show(frame(GpuChat.Snapshot.EMPTY), null);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                UiButton button = hud.find("chat-button");
                Actor panel = hud.find("chat-panel");
                TextField field = hud.find("chat-input");
                assertFalse(shown(panel), "the chat starts collapsed");
                assertTrue(dot(button), "lines nobody read light the button's dot");

                // TOGGLE_CHAT (Enter) opens the chat with its field focused; the newline GLFW types next is swallowed.
                assertTrue(hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER));
                hud.hud.keyTyped('\n');
                hud.type(DRAFT);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                assertTrue(shown(panel));
                assertTrue(button.isChecked(), "the button is pressed while the chat is open");
                assertFalse(dot(button), "opening the chat reads its lines");
                assertEquals(DRAFT, field.getText());
                assertSame(field, hud.focus());
                assertEquals(List.of(system, lines.get(0).text(), system, lines.get(1).text()), messages(panel));
                Actor dock = hud.find("command-dock");
                Rectangle dockBounds = GpuHudTestStage.bounds(dock);
                assertEquals(new Rectangle(dockBounds.x, dockBounds.y + dockBounds.height + 12, dockBounds.width, 320),
                      GpuHudTestStage.bounds(panel), "chat shares the dock's width with the standard panel gap");
                Rectangle place = GpuHudTestStage.bounds(button);
                assertTrue(button.isDescendantOf(dock));
                assertTrue(dockBounds.contains(place), "the icon button stays inside the footer");
                assertTrue(button.getText().isEmpty(), "chat uses only its icon");
                UiTestStage.assertTexts(panel);
                UiTestStage.assertTexts(button);
                Pixmap shot = UiTestStage.capture("chat-hud", harness.width(), harness.height());
                try {
                    UiTestStage.compare("chat-panel", shot, panel, MOCK, MOCK_PANEL_X, MOCK_PANEL_Y);
                    UiTestStage.compare("chat-button", shot, button, MOCK, MOCK_BUTTON_X, MOCK_BUTTON_Y);
                } finally {
                    shot.dispose();
                }

                // The first Esc ends the typing and the second closes the chat; the draft stays in the field.
                assertTrue(hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE));
                assertNull(hud.focus());
                assertTrue(hud.hud.state.chatOpen);
                assertTrue(hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE));
                assertFalse(hud.hud.state.chatOpen);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                assertFalse(shown(panel));
                assertFalse(button.isChecked());
                click(hud.hud.stage, button);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                assertTrue(shown(panel), "the button opens the chat again");
                assertEquals(DRAFT, field.getText(), "with the draft kept");
                assertSame(field, hud.focus(), "and the field focused");

                // Enter sends the draft once through the board chat's send and keeps the focus; a blank line is never
                // sent. Send posts the same way.
                assertTrue(hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER));
                verify(hud.chat).send(DRAFT);
                assertEquals("", field.getText());
                assertSame(field, hud.focus());
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                hud.type("   ");
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                verify(hud.chat, times(1)).send(any());
                field.setText("");
                hud.type("Roger");
                click(hud.hud.stage, hud.find("chat-send"));
                verify(hud.chat).send("Roger");
                assertEquals("", field.getText());
                assertSame(field, hud.focus(), "Send gives the focus back to the field");

                // The game echoes the lines: the earlier lines stay, the newest is last and in view.
                lines.add(new GpuChat.ChatLine(3, "Player", DRAFT, false, 0xFF2D6CC4));
                for (int id = 4; id <= 14; id++) {
                    lines.add(new GpuChat.ChatLine(id, "Player", "Line " + id, false, 0xFF2D6CC4));
                }
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                List<String> shown = messages(panel);
                assertEquals(lines.size() + 3, shown.size(), "two system headers and one for consecutive player messages");
                assertEquals(List.of(system, lines.get(0).text(), system, lines.get(1).text(), "Player", DRAFT),
                      shown.subList(0, 6), "the history stays, oldest first");
                assertEquals(List.of("Line 13", "Line 14"), shown.subList(shown.size() - 2, shown.size()));
                ScrollPane scroll = all(panel, ScrollPane.class).getFirst();
                assertTrue(scroll.getMaxY() > 0, "the lines overflow the panel");
                assertEquals(scroll.getMaxY(), scroll.getScrollY(), .5f, "the newest line is in view");
                Label sender = all(panel, Label.class).stream().filter(label -> label.textEquals("Player"))
                      .findFirst().orElseThrow();
                assertEquals(UiTheme.MINT, sender.getStyle().fontColor, "a player's name is mint");
                Label notice = all(panel, Label.class).stream().filter(label -> label.textEquals(system))
                      .findFirst().orElseThrow();
                assertEquals(UiTheme.MUTED, notice.getStyle().fontColor, "MegaMek's own lines are muted");
                UiTestStage.capture("chat-grouped-messages", harness.width(), harness.height()).dispose();

                // A line that arrives while the chat is closed lights the dot until the chat opens.
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                assertFalse(dot(button), "every line was read");
                lines.add(new GpuChat.ChatLine(15, system, "Princess has disconnected.", true, 0));
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                assertTrue(dot(button));
                // After Scene2D's 0.1 s visual press of the earlier click, the capture shows the button at rest.
                Thread.sleep(150);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                UiTestStage.capture("chat-unread", harness.width(), harness.height()).dispose();
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                hud.show(frame(new GpuChat.Snapshot(lines)), null);
                assertFalse(dot(button));

                // TOGGLE_CHAT_CMD (/) opens the chat focused, and its "/" starts the message.
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                field.setText("");
                assertTrue(hud.key(Input.Keys.SLASH, KeyEvent.VK_SLASH));
                hud.type("/who");
                assertTrue(hud.hud.state.chatOpen);
                assertEquals("/who", field.getText());
            }
        });
    }

    // ---------------------------------------------------------------- toasts

    @Test
    void toastsStackFiveDeepNewestBelowInTheirLevelColoursForTheirDurations() {
        GpuHudTestStage.run(harness -> {
            GpuBoardSource source = mock(GpuBoardSource.class);
            GpuToasts service = new GpuToasts(source);
            GpuToastStack stack = toastStack(harness, source);
            List<ToastLevel> levels = List.of(ToastLevel.values());
            GpuToasts.Snapshot five = GpuDialogRoutingTest.onSwing(() -> {
                levels.forEach(level -> service.add(level, level + " toast", null));
                return service.capture();
            });
            assertEquals(BoardToastOverlay.MAX_VISIBLE, five.toasts().size());
            stack.update(inputs(harness, frame(five), null));
            settle(harness, .3f);
            List<Actor> rows = rows(stack);
            assertEquals(levels.stream().map(level -> level + " toast").toList(), toastTexts(rows));
            // The newest stands at the bottom of the slot, each older one 6 above the next, all centred.
            Rectangle newest = GpuHudTestStage.bounds(rows.getLast());
            assertEquals(TOAST_BOTTOM, newest.y, .51f);
            for (int index = 0; index + 1 < rows.size(); index++) {
                Rectangle above = GpuHudTestStage.bounds(rows.get(index));
                Rectangle below = GpuHudTestStage.bounds(rows.get(index + 1));
                assertEquals(6, above.y - below.y - below.height, .51f, "6 between toasts");
                assertEquals(harness.width() / 2f, above.x + above.width / 2, 1, "centred");
            }
            Pixmap shot = harness.capture("toast-stack");
            try {
                // The left bar of each level (plan O5).
                List<Color> bars = List.of(UiTheme.ACCENT, UiTheme.MINT, UiTheme.AMBER, UiTheme.CORAL, UiTheme.VIOLET);
                for (int index = 0; index < rows.size(); index++) {
                    Rectangle row = GpuHudTestStage.bounds(rows.get(index));
                    Color pixel = new Color(shot.getPixel(Math.round(row.x + 1), Math.round(row.y + row.height / 2)));
                    assertColor(bars.get(index), pixel, levels.get(index) + " bar");
                }
            } finally {
                shot.dispose();
            }
            Vector2 middle = rows.get(2).localToStageCoordinates(new Vector2(20, 10));
            Actor pressed = harness.stage.hit(middle.x, middle.y, true);
            assertTrue(pressed == null || !pressed.isDescendantOf(stack.actor().getParent()),
                  "presses pass through the toasts: " + pressed);

            // A sixth toast pushes the oldest out of the full stack at once.
            GpuToasts.Snapshot six = GpuDialogRoutingTest.onSwing(() -> {
                service.add(ToastLevel.INFO, "sixth toast", null);
                return service.capture();
            });
            stack.update(inputs(harness, frame(six), null));
            settle(harness, .3f);
            List<String> expected = new ArrayList<>(levels.stream().skip(1).map(level -> level + " toast").toList());
            expected.add("sixth toast");
            assertEquals(expected, toastTexts(rows(stack)));

            // Each toast stays for its level's duration between two fades of .2 s, then leaves.
            GpuToastStack timed = toastStack(harness, source);
            timed.update(inputs(harness, frame(five), null));
            int shortest = five.toasts().stream().mapToInt(GpuToasts.Toast::durationMs).min().orElseThrow();
            int longest = five.toasts().stream().mapToInt(GpuToasts.Toast::durationMs).max().orElseThrow();
            assertTrue(longest >= shortest + 2000, "an ERROR outlasts an INFO by two seconds");
            float seconds = shortest / 1000f + .7f;
            settle(harness, seconds);
            assertEquals(five.toasts().stream().filter(toast -> toast.durationMs() / 1000f + .4f > seconds)
                  .map(GpuToasts.Toast::text).toList(), toastTexts(rows(timed)));
            settle(harness, (longest - shortest) / 1000f);
            assertTrue(rows(timed).isEmpty(), "every toast has left");

            // A long text wraps at the stack's width; a unit's toast shows its sprite before the text.
            BoardScene.Pixels atlas = GpuHudFixtures.sprite("Atlas");
            harness.sprites.add(atlas);
            GpuToastStack shapes = toastStack(harness, source);
            String wordy = "Atlas cannot reach that hex: ".repeat(12).strip();
            shapes.update(inputs(harness, frame(new GpuToasts.Snapshot(List.of(
                  new GpuToasts.Toast(1, ToastLevel.WARNING, wordy, null, 4000),
                  new GpuToasts.Toast(2, ToastLevel.INFO, "Short", null, 3000),
                  new GpuToasts.Toast(3, ToastLevel.SUCCESS, "Atlas destroyed the Locust", atlas, 3000)))), null));
            settle(harness, .3f);
            List<Actor> shaped = rows(shapes);
            Rectangle wrapped = GpuHudTestStage.bounds(shaped.getFirst());
            Rectangle line = GpuHudTestStage.bounds(shaped.get(1));
            assertTrue(wrapped.width <= TOAST_WIDTH + .5f, "at most 640 wide: " + wrapped);
            assertTrue(wrapped.height > 2 * line.height, "a long text wraps: " + wrapped + " vs " + line);
            assertEquals(1, all(shaped.getLast(), GpuHudKit.UnitSprite.class).size(), "the unit's sprite");
            harness.capture("toast-stack-shapes").dispose();

            // The prototype's own toast (r1 3.23, an amber bar) for a side-by-side with its HTML render.
            harness.window.clearChildren();
            GpuToastStack single = toastStack(harness, source);
            single.update(inputs(harness, frame(new GpuToasts.Snapshot(List.of(new GpuToasts.Toast(1,
                  ToastLevel.WARNING, "Plot a route first, or use Hold position", null, 4000)))), null));
            settle(harness, .3f);
            harness.capture("toast-mock").dispose();
        });
    }

    // ---------------------------------------------------------------- modal dialog

    @Test
    void messagesAnswerWithTheirButtonsEnterOrEscExactlyOnce() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                // A bother nag: no default button, Esc is the close box, the checkbox as the player left it.
                DialogRequest nag = message(11, "Are you sure?", "This unit will make a piloting skill roll:\r\n"
                      + "- entering rubble\n\tfrom the north.", List.of("Yes", "No"), -1, "Do not ask me again");
                hud.show(frame, nag);
                hud.show(frame, nag);
                Actor modal = hud.find("modal-dialog");
                assertTrue(shown(modal));
                assertSame(modal, hud.focus(), "the dialog takes the keyboard");
                List<String> texts = texts(modal);
                assertTrue(texts.contains("ARE YOU SURE?"), "the title: " + texts);
                assertTrue(texts.contains("This unit will make a piloting skill roll:\n- entering rubble\n    from the "
                      + "north."), "one break for a Windows line break, spaces for a tab: " + texts);
                assertTrue(texts.contains("Do not ask me again"));
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-message", harness.width(), harness.height()).dispose();
                assertTrue(hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER));
                verify(hud.source, never()).answer(anyLong(), any());
                assertTrue(hud.key(Input.Keys.SPACE, KeyEvent.VK_SPACE), "Space ticks the checkbox");
                assertTrue(hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE));
                answered(hud.source, 11, new DialogAnswer(-1, List.of(), null, true, List.of()));
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                click(hud.hud.stage, button(modal, "YES"));
                answered(hud.source, 11, new DialogAnswer(-1, List.of(), null, true, List.of()));
                assertTrue(button(modal, "YES").isDisabled(), "an answered dialog takes no more input");

                // A yes/no question: Enter presses Yes, its default.
                DialogRequest question = message(12, "Charge", "Charge the Atlas?", List.of("Yes", "No"), 0, "");
                hud.show(frame, question);
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 12, new DialogAnswer(0, List.of(), null, false, List.of()));
                // Options: a click presses its button; the close button is the close box.
                DialogRequest options = message(13, "Deployment", "This unit cannot survive there.",
                      List.of("Deploy", "Leave it", "Cancel"), 2, "");
                hud.show(frame, options);
                click(hud.hud.stage, button(hud.find("modal-dialog"), "LEAVE IT"));
                answered(hud.source, 13, new DialogAnswer(1, List.of(), null, false, List.of()));
                DialogRequest closed = message(14, "Deployment", "This unit cannot survive there.",
                      List.of("Deploy", "Leave it", "Cancel"), 2, "");
                hud.show(frame, closed);
                click(hud.hud.stage, all(hud.find("modal-dialog"), UiButton.class).getFirst());
                answered(hud.source, 14, new DialogAnswer(-1, List.of(), null, false, List.of()));

                hud.show(frame, null);
                assertFalse(shown(hud.find("modal-dialog")));
                assertNull(hud.focus());
            }
        });
    }

    @Test
    void listsChooseOrTickRowsRefuseTicksBeyondTheirMaximumAndAnswerOnce() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                // Unit rows as the choice dialogs build them: summaries of two lines, tooltip glyphs, a disabled row.
                List<DialogRow> units = List.of(
                      new DialogRow("Atlas AS7-D (100t)\nArmor 304 / 304; Internal 152 / 152 UNDAMAGED",
                            "Pilot R. Hayes\nGunnery 3, Piloting 4", null, true),
                      new DialogRow("Locust LCT-1V (20t)", "", null, false),
                      new DialogRow("Archer ARC-2R (70t)", "", null, true),
                      new DialogRow("Marauder MAD-3R (75t)  "
                            + "\u25a3\u25a3\u25a3\u2b1b \u27d0 \u22ef \u2b1d", "", null, true));
                DialogRequest choice = list(21, DialogKind.CHOICE, units, List.of(0), null);
                hud.show(frame, choice);
                hud.show(frame, choice);
                Actor modal = hud.find("modal-dialog");
                List<UiButton> rows = rows(modal);
                assertTrue(rows.getFirst().getHeight() > rows.get(2).getHeight() + 10, "a row of two lines is taller");
                assertTrue(rows.getFirst().isChecked(), "the initial row is the choice");
                assertTrue(rows.get(1).isDisabled());
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-choice", harness.width(), harness.height()).dispose();
                hud.key(Input.Keys.DOWN, KeyEvent.VK_DOWN);
                assertTrue(rows.get(2).isChecked(), "Down skips the row that cannot be chosen");
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 21, new DialogAnswer(0, List.of(2), null, false, List.of()));
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 21, new DialogAnswer(0, List.of(2), null, false, List.of()));

                // A double click confirms its row; Esc answers the cancel button without a row.
                DialogRequest twice = list(22, DialogKind.CHOICE, units, List.of(), null);
                hud.show(frame, twice);
                hud.show(frame, twice);
                UiButton archer = rows(hud.find("modal-dialog")).get(2);
                click(hud.hud.stage, archer);
                click(hud.hud.stage, archer);
                answered(hud.source, 22, new DialogAnswer(0, List.of(2), null, false, List.of()));
                hud.show(frame, list(23, DialogKind.CHOICE, units, List.of(0), null));
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                answered(hud.source, 23, new DialogAnswer(1, List.of(), null, false, List.of()));

                // A long list (a JOptionPane list of 20 or more values) opens with its initial row in view.
                List<DialogRow> hexes = new ArrayList<>();
                for (int row = 1; row <= 40; row++) {
                    hexes.add(new DialogRow(String.format("Hex 08%02d", row), "", null, true));
                }
                DialogRequest far = list(24, DialogKind.CHOICE, hexes, List.of(33), null);
                hud.show(frame, far);
                hud.show(frame, far);
                hud.show(frame, far);
                ScrollPane scroll = all(modal, ScrollPane.class).stream()
                      .filter(pane -> pane.getActor() instanceof UiMenuList).findFirst().orElseThrow();
                Rectangle view = GpuHudTestStage.bounds(scroll);
                Rectangle chosen = GpuHudTestStage.bounds(rows(modal).get(33));
                assertTrue(scroll.getMaxY() > 0, "the list scrolls");
                assertTrue(chosen.y >= view.y - .5f && chosen.y + chosen.height <= view.y + view.height + .5f,
                      "the initial row " + chosen + " is in view " + view);
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                answered(hud.source, 24, new DialogAnswer(1, List.of(), null, false, List.of()));

                // MULTI with max 2: a third tick is refused until one is cleared; Select All is not offered.
                List<DialogRow> bays = List.of(new DialogRow("Bay 1: Atlas", "", null, true),
                      new DialogRow("Bay 2: Archer", "", null, true), new DialogRow("Bay 3: Locust", "", null, true),
                      new DialogRow("Bay 4: empty", "", null, false));
                DialogRequest multi = list(31, DialogKind.MULTI, bays, List.of(), 2);
                hud.show(frame, multi);
                hud.show(frame, multi);
                rows = rows(modal);
                assertTrue(button(modal, "SELECT ALL").isDisabled(), "all three rows would exceed the maximum");
                click(hud.hud.stage, rows.get(0));
                click(hud.hud.stage, rows.get(1));
                assertTrue(ticked(rows.get(0)) && ticked(rows.get(1)));
                assertTrue(rows.get(2).isDisabled(), "a third tick is refused");
                click(hud.hud.stage, rows.get(2));
                assertFalse(ticked(rows.get(2)));
                // A clicked button shows pressed for 0.1 s (Scene2D's visual press); the capture shows the rows after.
                Thread.sleep(150);
                hud.show(frame, multi);
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-multi", harness.width(), harness.height()).dispose();
                assertTrue(hud.key(Input.Keys.SPACE, KeyEvent.VK_SPACE), "Space clears the highlighted row");
                assertFalse(ticked(rows.get(1)));
                assertFalse(rows.get(2).isDisabled());
                click(hud.hud.stage, rows.get(2));
                click(hud.hud.stage, button(modal, "OK"));
                answered(hud.source, 31, new DialogAnswer(0, List.of(0, 2), null, false, List.of()));
                DialogRequest cleared = list(32, DialogKind.MULTI, bays, List.of(0, 1), null);
                hud.show(frame, cleared);
                hud.show(frame, cleared);
                assertFalse(button(modal, "SELECT ALL").isDisabled());
                click(hud.hud.stage, button(modal, "SELECT ALL"));
                assertTrue(ticked(rows(modal).get(2)) && !ticked(rows(modal).get(3)), "every row that can be ticked");
                click(hud.hud.stage, button(modal, "CLEAR ALL"));
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 32, new DialogAnswer(0, List.of(), null, false, List.of()));
            }
        });
    }

    @Test
    void inputsTakeOnlyTheirRangeAndFormsAnswerEveryField() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                DialogRequest setting = new DialogRequest(41, DialogKind.INPUT, "Vibrabomb setting",
                      "Select a setting (10-200 tons):", false, List.of("OK"), 0, -1, List.of(), List.of(), "", false,
                      "10", 10, 200, List.of());
                hud.show(frame, setting);
                hud.show(frame, setting);
                Actor modal = hud.find("modal-dialog");
                TextField field = all(modal, TextField.class).getFirst();
                assertSame(field, hud.focus(), "the field takes the keyboard");
                Label range = all(modal, Label.class).stream().filter(label -> label.textEquals("10\u2013200"))
                      .findFirst().orElseThrow(() -> new AssertionError("no range: " + texts(modal)));
                hud.type("5");
                assertEquals("5", field.getText(), "typing replaces the initial value");
                assertTrue(button(modal, "OK").isDisabled(), "5 is out of the range");
                assertEquals(UiTheme.CORAL, range.getColor());
                hud.show(frame, setting);
                UiTestStage.capture("modal-input-invalid", harness.width(), harness.height()).dispose();
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                verify(hud.source, never()).answer(anyLong(), any());
                hud.type("x0");
                assertEquals("50", field.getText(), "numbers only");
                assertFalse(button(modal, "OK").isDisabled());
                assertEquals(UiTheme.MUTED, range.getColor());
                hud.show(frame, setting);
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-input", harness.width(), harness.height()).dispose();
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 41, new DialogAnswer(0, List.of(), "50", false, List.of()));
                DialogRequest name = new DialogRequest(42, DialogKind.INPUT, "Save game", "File name:", false,
                      List.of("OK", "Cancel"), 0, 1, List.of(), List.of(), "", false, "savegame.sav.gz", null, null,
                      List.of());
                hud.show(frame, name);
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                answered(hud.source, 42, new DialogAnswer(1, List.of(), null, false, List.of()));

                // A FORM answers each field's value: Tab moves between the fields, a choice opens its list.
                DialogRequest form = new DialogRequest(51, DialogKind.FORM, "Minefield", "Set the minefield.", false,
                      List.of("OK", "Cancel"), 0, 1, List.of(), List.of(), "", false, null, null, null, List.of(
                      new DialogField("Density", FieldKind.INTEGER, List.of(), 5, 35, "20"),
                      new DialogField("Name", FieldKind.TEXT, List.of(), 0, 0, "Gate"),
                      new DialogField("Facing", FieldKind.CHOICE, List.of("N", "NE", "SE"), 0, 0, "NE"),
                      new DialogField("Armed", FieldKind.CHECKBOX, List.of(), 0, 0, "false")));
                hud.show(frame, form);
                hud.show(frame, form);
                hud.type("30");
                hud.key(Input.Keys.TAB, KeyEvent.VK_TAB);
                hud.type("North");
                List<TextField> fields = all(modal, TextField.class);
                assertEquals(List.of("30", "North"), fields.stream().map(TextField::getText).toList());
                UiButton facing = button(modal, "NE");
                click(hud.hud.stage, facing);
                hud.show(frame, form);
                UiPopover choices = all(modal, UiPopover.class).getFirst();
                assertTrue(choices.isVisible(), "the choice opens its list");
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-form", harness.width(), harness.height()).dispose();
                hud.key(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                assertFalse(choices.isVisible(), "Esc closes the open list first");
                verify(hud.source, times(1)).answer(eq(42L), any());
                verify(hud.source, never()).answer(eq(51L), any());
                click(hud.hud.stage, facing);
                hud.show(frame, form);
                click(hud.hud.stage, button(choices, "SE"));
                assertFalse(choices.isVisible());
                assertEquals("SE", facing.getText().toString());
                click(hud.hud.stage, all(modal, Label.class).stream().filter(label -> label.textEquals("Armed"))
                      .findFirst().orElseThrow());
                click(hud.hud.stage, button(modal, "OK"));
                answered(hud.source, 51, new DialogAnswer(0, List.of(), null, false, List.of("30", "North", "SE",
                      "true")));
            }
        });
    }

    @Test
    void theModalDrawsBeforeTheBoardExistsAndTakesEveryPress() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBoardSource.Frame noBoard = new GpuBoardSource.Frame(null, List.of(), null, List.of(), "",
                      null, 0, "", null, GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY,
                      GpuHudData.EMPTY);
                DialogRequest alert = new DialogRequest(61, DialogKind.MESSAGE, "Save failed",
                      "Could not save \"game.sav\":\n  disk full", true, List.of("OK"), 0, -1, List.of(), List.of(),
                      "", false, null, null, null, List.of());
                hud.show(noBoard, alert);
                hud.show(noBoard, alert);
                Actor modal = hud.find("modal-dialog");
                assertTrue(shown(modal), "the dialog shows without a board");
                assertTrue(texts(modal).contains("Could not save \"game.sav\":\n  disk full"));
                assertTrue(hud.hud.hit(10, 10), "the scrim takes every press");
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-no-board", harness.width(), harness.height()).dispose();
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 61, new DialogAnswer(0, List.of(), null, false, List.of()));
                hud.show(noBoard, null);
                assertFalse(shown(modal));
                assertNull(hud.focus());
            }
        });
    }

    @Test
    void theBridgeReturnsWhatTheModalAnswersNewestDialogFirst() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuHudTestStage.run(harness -> {
                GpuModalDialog modal = new GpuModalDialog(harness.kit, fixture.source, new GpuHudState(new GpuPlaybackHistory(new UnitPlayback())));
                Actor root = modal.actor();
                root.setTouchable(Touchable.enabled);
                harness.window.addActor(root);
                root.setBounds(0, 0, harness.width(), harness.height());
                GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                DialogRequest setting = new DialogRequest(0, DialogKind.INPUT, "Vibrabomb setting",
                      "Select a setting (10-200 tons):", false, List.of("OK"), 0, -1, List.of(), List.of(), "", false,
                      "10", 10, 200, List.of());
                FutureTask<DialogAnswer> outer = ask(fixture.source, setting);
                modal.update(inputs(harness, frame, await(fixture.source, setting.message())));
                harness.draw();
                harness.stage.keyTyped('3');

                // A prompt that arrives inside the first one's wait is shown on top and answered first.
                DialogRequest target = list(0, DialogKind.CHOICE, List.of(new DialogRow("Atlas", "", null, true),
                      new DialogRow("Archer", "", null, true)), List.of(0), null);
                FutureTask<DialogAnswer> inner = ask(fixture.source, target);
                modal.update(inputs(harness, frame, await(fixture.source, target.message())));
                harness.draw();
                harness.stage.keyDown(Input.Keys.DOWN);
                harness.stage.keyDown(Input.Keys.ENTER);
                assertEquals(new DialogAnswer(0, List.of(1), null, false, List.of()), inner.get(WAIT_SECONDS, SECONDS));

                // The first prompt shows again, as new, and its answer is what the bridge returns.
                modal.update(inputs(harness, frame, await(fixture.source, setting.message())));
                harness.draw();
                for (char digit : "35".toCharArray()) {
                    harness.stage.keyTyped(digit);
                }
                harness.stage.keyDown(Input.Keys.ENTER);
                harness.stage.keyDown(Input.Keys.ENTER);
                assertEquals(new DialogAnswer(0, List.of(), "35", false, List.of()), outer.get(WAIT_SECONDS, SECONDS));
                assertNull(fixture.source.dialog());
                modal.update(inputs(harness, frame, null));
                assertFalse(((Group) root).hasChildren(), "no dialog is left");
            });
        }
    }

    /** N7c: a story's MESSAGE shows its image left of its text, from the image file, and frees its texture. */
    @Test
    void aMessageShowsItsImageBesideItsTextAndFreesItsTextureWhenItGoes() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                DialogRequest story = new DialogRequest(71, DialogKind.MESSAGE, "Ambush",
                      "The convoy is in sight.\nHold the ridge before the second lance arrives.", false,
                      List.of("OK"), 0, -1, List.of(), List.of(), "", false, null, null, null, List.of(), portrait());
                hud.show(frame, story);
                hud.show(frame, story);
                Actor modal = hud.find("modal-dialog");
                Image picture = hud.find("modal-image");
                Texture texture = ((TextureRegionDrawable) picture.getDrawable()).getRegion().getTexture();
                assertEquals(List.of(120, 160), List.of(texture.getWidth(), texture.getHeight()), "the file's size");
                Rectangle shown = GpuHudTestStage.bounds(picture);
                assertEquals(120, shown.width, .51f, "an image within its box keeps its size");
                assertEquals(160, shown.height, .51f);
                Label text = all(modal, Label.class).stream()
                      .filter(label -> label.textEquals(story.message())).findFirst().orElseThrow();
                assertTrue(GpuHudTestStage.bounds(text).x >= shown.x + shown.width, "the text right of the image");
                UiTestStage.assertTexts(modal);
                Pixmap shot = UiTestStage.capture("modal-story", harness.width(), harness.height());
                try {
                    assertColor(Color.ORANGE, new Color(shot.getPixel(Math.round(shown.x + 60),
                          Math.round(shown.y + 120))), "the image's upper half");
                    assertColor(Color.TEAL, new Color(shot.getPixel(Math.round(shown.x + 60),
                          Math.round(shown.y + 40))), "the image's lower half");
                } finally {
                    shot.dispose();
                }
                hud.key(Input.Keys.ENTER, KeyEvent.VK_ENTER);
                answered(hud.source, 71, new DialogAnswer(0, List.of(), null, false, List.of()));
                hud.show(frame, null);
                assertEquals(0, texture.getTextureObjectHandle(), "the dialog freed its image");

                // An image that does not decode leaves the text.
                String notAnImage = Base64.getEncoder().encodeToString("not an image".getBytes());
                DialogRequest broken = new DialogRequest(72, DialogKind.MESSAGE, "Ambush", "Hold the ridge.", false,
                      List.of("OK"), 0, -1, List.of(), List.of(), "", false, null, null, null, List.of(), notAnImage);
                hud.show(frame, broken);
                assertTrue(texts(hud.find("modal-dialog")).contains("Hold the ridge."));
                assertNull(hud.find("modal-image"), "no image");
            }
        });
    }

    /**
     * N7c: changing a live FORM field answers the form at once with CHANGED and its values as left, so the asker can
     * show the form again as the change makes it; other fields wait for the buttons.
     */
    @Test
    void aLiveFieldsChangeAnswersTheFormAtOnceWithItsValues() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                List<String> options = List.of("Okay", "Remove Flag", "Cancel");
                DialogRequest form = new DialogRequest(81, DialogKind.FORM, "Objective 0505 Properties",
                      "What it does: both sides score each turn they control a point.", false, options, 0, -1,
                      List.of(), List.of(), "", false, null, null, null, List.of(
                      new DialogField("Scoring scheme:", FieldKind.CHOICE, List.of("Standard", "Hold"), 0, 0,
                            "Standard", true),
                      new DialogField("Starts controlled by:", FieldKind.CHOICE, List.of("No one", "Princess"), 0, 0,
                            "No one"),
                      new DialogField("Keep control when empty:", FieldKind.CHECKBOX, List.of(), 0, 0, "false", true),
                      new DialogField("Victory points:", FieldKind.INTEGER, List.of(), 1, 99, "1")));
                hud.show(frame, form);
                hud.show(frame, form);
                Actor modal = hud.find("modal-dialog");
                hud.type("5");
                click(hud.hud.stage, button(modal, "No one"));
                hud.show(frame, form);
                click(hud.hud.stage, button(all(modal, UiPopover.class).getFirst(), "Princess"));
                UiButton scheme = button(modal, "Standard");
                click(hud.hud.stage, scheme);
                hud.show(frame, form);
                click(hud.hud.stage, button(all(modal, UiPopover.class).getFirst(), "Standard"));
                verify(hud.source, never()).answer(anyLong(), any());
                hud.show(frame, form);
                UiTestStage.assertTexts(modal);
                UiTestStage.capture("modal-live-form", harness.width(), harness.height()).dispose();

                click(hud.hud.stage, scheme);
                hud.show(frame, form);
                click(hud.hud.stage, button(all(modal, UiPopover.class).getFirst(), "Hold"));
                answered(hud.source, 81, new DialogAnswer(DialogAnswer.CHANGED, List.of(), null, false,
                      List.of("Hold", "Princess", "false", "5")));
                assertTrue(button(modal, "OKAY").isDisabled(), "the answered form waits for the next one");

                DialogRequest next = new DialogRequest(82, DialogKind.FORM, form.title(), form.message(), false,
                      options, 0, -1, List.of(), List.of(), "", false, null, null, null, List.of(
                      new DialogField("Scoring scheme:", FieldKind.CHOICE, List.of("Standard", "Hold"), 0, 0, "Hold",
                            true),
                      new DialogField("Turns to secure:", FieldKind.INTEGER, List.of(), 1, 99, "10"),
                      new DialogField("Keep control when empty:", FieldKind.CHECKBOX, List.of(), 0, 0, "false", true),
                      new DialogField("Victory points:", FieldKind.INTEGER, List.of(), 1, 99, "5")));
                hud.show(frame, next);
                Actor rebuilt = hud.find("modal-dialog");
                click(hud.hud.stage, all(rebuilt, Label.class).stream()
                      .filter(label -> label.textEquals("Keep control when empty:")).findFirst().orElseThrow());
                answered(hud.source, 82, new DialogAnswer(DialogAnswer.CHANGED, List.of(), null, false,
                      List.of("Hold", "10", "true", "5")));
            }
        });
    }

    /**
     * N7c: the Victory Setup phase's control point editor as the real native form over the real bridge: choosing
     * Defend in it asks again with Defend's rows and description; Esc cancels the edit.
     */
    @Test
    void theControlPointEditorIsANativeFormThatFollowsItsScheme() throws Exception {
        ClientGUI gui = GpuDialogRoutingTest.routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuDialogRoutingTest.present(gui, fixture.view, fixture.source);
            JFrame owner = GpuChoiceRoutingTest.clientFrame(gui);
            try {
                ObjectiveMarker marker = new ObjectiveMarker();
                marker.setName("Objective 0505");
                FutureTask<VictoryHexPropertiesPane.Result> edit = new FutureTask<>(() ->
                      VictoryHexPropertiesPane.edit(owner, marker, GpuVictoryHexFormTest.players(), false));
                SwingUtilities.invokeLater(edit);
                GpuHudTestStage.run(harness -> {
                    GpuModalDialog modal = new GpuModalDialog(harness.kit, fixture.source,
                          new GpuHudState(new GpuPlaybackHistory(new UnitPlayback())));
                    Actor root = modal.actor();
                    root.setTouchable(Touchable.enabled);
                    harness.window.addActor(root);
                    root.setBounds(0, 0, harness.width(), harness.height());
                    GpuBoardSource.Frame frame = frame(GpuChat.Snapshot.EMPTY);
                    modal.update(inputs(harness, frame, await(fixture.source,
                          Messages.getString("VictoryHex.describe.standard"))));
                    harness.draw();
                    harness.draw();
                    UiTestStage.assertTexts(root);
                    harness.capture("victory-form-standard").dispose();
                    click(harness.stage, button(root, Messages.getString("VictoryHex.scheme.standard")));
                    harness.draw();
                    click(harness.stage, button(all(root, UiPopover.class).getFirst(),
                          Messages.getString("VictoryHex.scheme.defend")));
                    modal.update(inputs(harness, frame, await(fixture.source,
                          Messages.getString("VictoryHex.describe.defend", 10, 1))));
                    harness.draw();
                    harness.draw();
                    List<String> texts = texts(root);
                    assertTrue(texts.containsAll(List.of(Messages.getString("VictoryHex.startingGrip"),
                          Messages.getString("VictoryHex.gripDrainPerTurn"))), "Defend's own rows: " + texts);
                    assertFalse(texts.contains(Messages.getString("VictoryHex.counting")), "Hold's row is not shown");
                    harness.capture("victory-form-defend").dispose();
                    modal.cancel();
                });
                assertEquals(VictoryHexPropertiesPane.Result.CANCELLED, edit.get(WAIT_SECONDS, SECONDS));
                assertEquals(SchemePreset.STANDARD, marker.getScoringScheme().getPreset(), "Esc changes nothing");
            } finally {
                GpuDialogRoutingTest.dismiss();
            }
        }
    }

    /** N7c: while a bot order picks hexes, the hint line shows the pick's instructions, picked hexes and keys. */
    @Test
    void aBotOrdersHexPickShowsItsInstructionsAndKeysOnTheHintLine() throws Exception {
        GpuBoardSource.UiPreferences preferences = GpuDialogRoutingTest.onSwing(GpuBoardSource.UiPreferences::capture);
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                String instructions = Messages.getString("BotCommandPanel.HexPicker.instructions",
                      Messages.getString("BotCommandPanel.StrategicTarget.title"));
                String status = Messages.getString("BotCommandPanel.HexPicker.status", 2, "0707, 0807");
                GpuHudData picking = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
                      GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
                      RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null,
                      new GpuPlayers.Pick(instructions, status)));
                GpuBoardSource.Frame frame = GpuHudInputTest.frame(GpuHudInputTest.status(3, GamePhase.MOVEMENT,
                      false, Entity.NONE, 0), picking);
                for (int draw = 0; draw < 2; draw++) {
                    hud.hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                    ScreenUtils.clear(.1f, .13f, .13f, 1, true);
                    hud.hud.draw();
                }
                Actor hint = hud.find("hint-line");
                assertTrue(shown(hint));
                assertEquals(List.of(Messages.getString("GpuBoard.hud.mouse.leftClick"), instructions + " · " + status,
                      GpuHintLine.key(preferences, KeyCommandBind.DONE),
                      Messages.getString("BotCommandPanel.HexPicker.done"),
                      GpuHintLine.key(preferences, KeyCommandBind.CANCEL),
                      Messages.getString("BotCommandPanel.HexPicker.cancel")), texts(hint));
                UiTestStage.capture("hex-pick-hint", harness.width(), harness.height()).dispose();
            }
        });
    }

    /**
     * Where the window has no room for the hint line (W <= 1350), a bot order's hex pick shows in its chip, which
     * stands on the dock: the pick's instructions and picked hexes with Done and Cancel, whose clicks end the pick with
     * and without the order; at 1280 x 720 and at the 900 x 600 window minimum (N7c's hand-off). Writes
     * hex-pick-chip-{size}.png.
     */
    @Test
    void aBotOrdersHexPickShowsInItsChipOnTheDockWhereTheHintLineIsHidden() throws Exception {
        GpuBoardSource.UiPreferences preferences = GpuDialogRoutingTest.onSwing(GpuBoardSource.UiPreferences::capture);
        String instructions = Messages.getString("BotCommandPanel.HexPicker.instructions",
              Messages.getString("BotCommandPanel.StrategicTarget.title"));
        String status = Messages.getString("BotCommandPanel.HexPicker.status", 2, "0707, 0807");
        GpuHudData picking = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, new GpuPlayers.Snapshot(List.of(), null,
              new GpuPlayers.Pick(instructions, status)));
        GpuBoardSource.Frame frame = GpuHudInputTest.frame(GpuHudInputTest.status(3, GamePhase.MOVEMENT, false,
              Entity.NONE, 0), picking);
        GpuHudTestStage.run(harness -> {
            for (int[] size : new int[][] { { 1280, 720 }, { 900, 600 } }) {
                harness.size(size[0], size[1]);
                try (Hud hud = new Hud(harness)) {
                    GpuPlayers players = mock(GpuPlayers.class);
                    when(hud.source.players()).thenReturn(players);
                    for (int draw = 0; draw < 2; draw++) {
                        hud.hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                        ScreenUtils.clear(.1f, .13f, .13f, 1, true);
                        hud.hud.draw();
                    }
                    Actor chip = hud.find("pick-chip");
                    assertTrue(shown(chip), "the pick's chip at " + size[0] + " x " + size[1]);
                    assertFalse(shown(hud.find("hint-line")));
                    assertEquals(List.of(instructions, status,
                          UiTheme.upper(Messages.getString("BotCommandPanel.HexPicker.done")),
                          UiTheme.upper(Messages.getString("BotCommandPanel.HexPicker.cancel"))), texts(chip));
                    Rectangle area = UiTestStage.bounds(chip);
                    Rectangle dock = UiTestStage.bounds(hud.find("command-dock"));
                    assertTrue(area.y >= dock.y + dock.height, "the chip " + area + " stands on the dock " + dock);
                    assertTrue(area.x >= 0 && area.x + area.width <= size[0] && area.y + area.height <= size[1],
                          "the chip " + area + " lies in the window");
                    UiTestStage.assertTexts(hud.hud.stage.getRoot());
                    UiTestStage.capture("hex-pick-chip-" + size[0] + "x" + size[1], harness.width(),
                          harness.height()).dispose();
                    click(hud.hud.stage, hud.find("pick-done"));
                    verify(players).endPick(true);
                    click(hud.hud.stage, hud.find("pick-cancel"));
                    verify(players).endPick(false);
                }
            }
        });
    }

    // ---------------------------------------------------------------- fixtures

    /** A 120 x 160 portrait as an image file, base64 encoded: orange above, teal below. */
    private static String portrait() {
        BufferedImage image = new BufferedImage(120, 160, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            // libGDX's ORANGE and TEAL
            graphics.setColor(new java.awt.Color(255, 165, 0));
            graphics.fillRect(0, 0, 120, 80);
            graphics.setColor(new java.awt.Color(0, 128, 128));
            graphics.fillRect(0, 80, 120, 80);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A frame of a movement turn without units, with the given chat lines. */
    private static GpuBoardSource.Frame frame(GpuChat.Snapshot chat) {
        return GpuHudInputTest.frame(GpuHudInputTest.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0),
              panels(chat, GpuToasts.Snapshot.EMPTY));
    }

    /** A frame with the given toasts. */
    private static GpuBoardSource.Frame frame(GpuToasts.Snapshot toasts) {
        return GpuHudInputTest.frame(GpuHudInputTest.status(3, GamePhase.MOVEMENT, false, Entity.NONE, 0),
              panels(GpuChat.Snapshot.EMPTY, toasts));
    }

    private static GpuHudData panels(GpuChat.Snapshot chat, GpuToasts.Snapshot toasts) {
        return new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY,
              GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE, chat,
              toasts, RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
    }

    private static GpuHud.Inputs inputs(GpuHudTestStage harness, GpuBoardSource.Frame frame, DialogRequest dialog) {
        return new GpuHud.Inputs(frame, GpuHud.HudView.EMPTY, dialog, GpuHudInputTest.preferences(),
              GpuHud.Metrics.of(harness.width(), harness.height()), List.of());
    }

    /** A MESSAGE request; an empty {@code checkbox} is none, and Esc is the close box. */
    private static DialogRequest message(long id, String title, String text, List<String> buttons, int defaultButton,
          String checkbox) {
        return new DialogRequest(id, DialogKind.MESSAGE, title, text, false, buttons, defaultButton, -1, List.of(),
              List.of(), checkbox, false, null, null, null, List.of());
    }

    /** A CHOICE or MULTI request as ClientGUI.askRows builds it: OK confirms, Cancel and Esc cancel. */
    private static DialogRequest list(long id, DialogKind kind, List<DialogRow> rows, List<Integer> selected,
          Integer max) {
        return new DialogRequest(id, kind, "Load unit", "Which unit do you want to load?", false,
              List.of("OK", "Cancel"), 0, 1, rows, selected, "", false, null, null, max, List.of());
    }

    /** The toast stack in the harness, placed as GpuHud's toast slot: centred, its stack at the slot's bottom. */
    private static GpuToastStack toastStack(GpuHudTestStage harness, GpuBoardSource source) {
        GpuToastStack stack = new GpuToastStack(harness.kit, source, new GpuHudState(new GpuPlaybackHistory(new UnitPlayback())));
        Container<Actor> slot = new Container<>(stack.actor()).bottom();
        slot.setTouchable(Touchable.childrenOnly);
        harness.window.addActor(slot);
        slot.setBounds((harness.width() - TOAST_WIDTH) / 2, TOAST_BOTTOM, TOAST_WIDTH,
              harness.height() - TOAST_TOP - TOAST_BOTTOM);
        return stack;
    }

    /** Runs the toast clock for {@code seconds} in frames of STEP, then draws. */
    private static void settle(GpuHudTestStage harness, float seconds) {
        for (int step = 0; step < Math.round(seconds / STEP); step++) {
            harness.stage.act(STEP);
        }
        harness.draw();
    }

    // ---------------------------------------------------------------- reading and acting

    /** The shown toasts, oldest (top) first. */
    private static List<Actor> rows(GpuToastStack stack) {
        List<Actor> rows = new ArrayList<>();
        ((Group) stack.actor()).getChildren().forEach(rows::add);
        return rows;
    }

    private static List<String> toastTexts(List<Actor> rows) {
        return rows.stream().map(row -> all(row, Label.class).getLast().getText().toString()).toList();
    }

    /** The chat's sender headings and message texts, oldest first. */
    private static List<String> messages(Actor panel) {
        ScrollPane scroll = all(panel, ScrollPane.class).getFirst();
        return texts(scroll.getActor());
    }

    /** The rows of the dialog's list. */
    private static List<UiButton> rows(Actor modal) {
        UiMenuList list = all(modal, UiMenuList.class).getFirst();
        return all(list, UiButton.class);
    }

    /** A MULTI row's check mark shows. */
    private static boolean ticked(UiButton row) {
        return row.icons.getFirst().isVisible();
    }

    /** The button's unread dot shows. */
    private static boolean dot(UiButton button) {
        return all(button, Image.class).stream()
              .anyMatch(image -> image.isVisible() && image.getDrawable() == button.getSkin().getDrawable("dot"));
    }

    private static boolean shown(Actor actor) {
        for (Actor node = actor; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return actor.getStage() != null;
    }

    /** A left click in the middle of the actor, through the stage as the window delivers it. */
    private static void click(Stage stage, Actor actor) {
        Vector2 point = stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    private static UiButton button(Actor actor, String text) {
        return all(actor, UiButton.class).stream().filter(button -> button.getText().toString().equals(text))
              .findFirst().orElseThrow(() -> new AssertionError("No button " + text + " in " + texts(actor)));
    }

    /** Every actor of the type at or below {@code actor}, depth first. */
    private static <T extends Actor> List<T> all(Actor actor, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(actor, type, found);
        return found;
    }

    private static <T extends Actor> void collect(Actor actor, Class<T> type, List<T> found) {
        if (type.isInstance(actor)) {
            found.add(type.cast(actor));
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, type, found));
        }
    }

    /** The texts of the labels at or below {@code actor}, depth first. */
    private static List<String> texts(Actor actor) {
        return all(actor, Label.class).stream().map(label -> label.getText().toString())
              .filter(text -> !text.isEmpty()).toList();
    }

    /** Request {@code id} was answered exactly once, with {@code expected}. */
    private static void answered(GpuBoardSource source, long id, DialogAnswer expected) {
        ArgumentCaptor<DialogAnswer> answer = ArgumentCaptor.forClass(DialogAnswer.class);
        verify(source, times(1)).answer(eq(id), answer.capture());
        assertEquals(expected, answer.getValue());
    }

    /** EDT: the source asks, waiting in its nested loop as a modal dialog does. */
    private static FutureTask<DialogAnswer> ask(GpuBoardSource source, DialogRequest request) {
        FutureTask<DialogAnswer> task = new FutureTask<>(() -> source.ask(request));
        SwingUtilities.invokeLater(task);
        return task;
    }

    /** The source's newest pending dialog, once it is the one with {@code message}. */
    private static DialogRequest await(GpuBoardSource source, String message) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            DialogRequest shown = source.dialog();
            if (shown != null && shown.message().equals(message)) {
                return shown;
            }
            Thread.sleep(5);
        }
        return fail("Dialog not shown: " + message);
    }

    private static void assertColor(Color expected, Color actual, String what) {
        assertTrue(Math.abs(expected.r - actual.r) < .02f && Math.abs(expected.g - actual.g) < .02f
              && Math.abs(expected.b - actual.b) < .02f, what + ": expected " + expected + " but was " + actual);
    }
}
