/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.awaitDialog;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.Client;
import megamek.client.ui.Base64Image;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.dialogs.MMDialogs.MMNarrativeStoryDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.common.Player;
import megamek.common.event.GameScriptedMessageEvent;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A scenario's story message (MMNarrativeStoryDialog) over the GPU battle window: a native message with its image
 * beside its text, which waits while another request is pending, as the client queues stories behind a modal dialog.
 * The test thread answers as the render thread would; the client is a real ClientGUI.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuStoryDialogTest {
    private static final String TEXT = "<p>The convoy is <b>in sight</b>.</p><p>Hold the ridge.</p>";
    private static final String SHOWN = "The convoy is in sight.\nHold the ridge.";

    @Test
    void aStoryShowsItsImageAndTextNativelyOnceThePendingRequestIsAnswered() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "The client builds its Swing frame");
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientGUI gui = onSwing(GpuStoryDialogTest::client);
            try {
                present(gui, fixture.view, fixture.source);
                FutureTask<Integer> confirm = new FutureTask<>(() -> gui.confirm("Flee?", "Confirm",
                      JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE));
                SwingUtilities.invokeLater(confirm);
                DialogRequest pending = awaitDialog(fixture.source, "Flee?");

                // The story arrives while the question is pending: it waits instead of opening on top of it.
                onSwing(() -> showScriptedMessage(gui, story()));
                Thread.sleep(800);
                assertSame(pending, fixture.source.dialog(), "the story waits behind the pending request");

                fixture.source.answer(pending.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
                assertEquals(JOptionPane.YES_OPTION, confirm.get(20, SECONDS));
                DialogRequest shown = awaitDialog(fixture.source, SHOWN);
                assertEquals(DialogKind.MESSAGE, shown.kind());
                assertEquals("Ambush", shown.title());
                assertEquals(List.of(Messages.getString("Ok.text")), shown.buttons());
                assertEquals(0, shown.defaultButton(), "Enter presses OK, the story dialog's default button");
                assertEquals(-1, shown.cancelButton(), "Esc closes it, as the story dialog's close action does");
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder()
                      .decode(shown.image())));
                assertEquals(List.of(30, 40, Color.ORANGE.getRGB()),
                      List.of(image.getWidth(), image.getHeight(), image.getRGB(15, 20)),
                      "the story's image goes with it as an image file");

                fixture.source.answer(shown.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
                awaitNoDialog(fixture.source);
                assertTrue(Arrays.stream(Window.getWindows()).noneMatch(window -> window.isShowing()
                      && window instanceof MMNarrativeStoryDialog), "no Swing story dialog showed");
            } finally {
                dismiss();
                onSwing(() -> {
                    gui.die();
                    return null;
                });
            }
        }
    }

    @Test
    void withoutAPresentedWindowTheStoryStaysItsSwingDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows the Swing story dialog");
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientGUI gui = onSwing(GpuStoryDialogTest::client);
            try {
                set(present(gui, fixture.view, fixture.source), "presented", false);
                AtomicBoolean swing = new AtomicBoolean();
                AtomicBoolean asked = new AtomicBoolean();
                onSwing(() -> {
                    // The modal story dialog holds the Swing thread in its loop until this timer closes it.
                    Timer close = new Timer(50, event -> Arrays.stream(Window.getWindows())
                          .filter(window -> window instanceof MMNarrativeStoryDialog && window.isShowing())
                          .forEach(window -> {
                              swing.set(true);
                              asked.set(fixture.source.dialog() != null);
                              window.dispose();
                          }));
                    close.start();
                    try {
                        showScriptedMessage(gui, story());
                    } finally {
                        close.stop();
                    }
                    return null;
                });
                assertTrue(swing.get(), "the Swing story dialog showed");
                assertFalse(asked.get(), "no native story without a presented window");
                assertFalse(GpuBoardWindow.dialogPendingFor(gui));
            } finally {
                dismiss();
                onSwing(() -> {
                    gui.die();
                    return null;
                });
            }
        }
    }

    /** A scripted message with a 30 x 40 orange image, as the server sends it. */
    private static GameScriptedMessageEvent story() {
        BufferedImage image = new BufferedImage(30, 40, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(0, 0, 30, 40);
        } finally {
            graphics.dispose();
        }
        return new GameScriptedMessageEvent(GpuStoryDialogTest.class, "Ambush", TEXT, new Base64Image(image));
    }

    /** EDT: what the client's game listener does with a scripted message. */
    private static Void showScriptedMessage(ClientGUI gui, GameScriptedMessageEvent event) throws Exception {
        Method show = AbstractClientGUI.class.getDeclaredMethod("showScriptedMessage",
              GameScriptedMessageEvent.class);
        show.setAccessible(true);
        show.invoke(gui, event);
        return null;
    }

    /** EDT: a real client of an empty game, its unit display and sounds stubbed. */
    private static ClientGUI client() {
        try (var display = mockConstruction(UnitDisplayPanel.class);
              var sound = mockConstruction(SoundManager.class)) {
            Game game = new Game();
            Player player = new Player(0, "Story reader");
            game.addPlayer(0, player);
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(player);
            return new ClientGUI(client, null);
        }
    }

    private static void awaitNoDialog(GpuBoardSource source) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(20);
        while (source.dialog() != null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertNull(source.dialog(), "no other request follows");
    }
}
