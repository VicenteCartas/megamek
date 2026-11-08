/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.game.GameTurn;
import megamek.common.moves.MovePath;
import megamek.common.moves.MoveStep;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * A real MovementDisplay whose piloting nag is answered through the native modal bridge: the move sent is exactly
 * the path the player confirmed, and board input or plan commands arriving while the nag waits are dropped.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuMoveNagBridgeTest {
    private static final Coords RUBBLE = new Coords(5, 4);
    private static final Coords BEYOND = new Coords(5, 3);

    @Test
    void aPilotingNagAnsweredNativelySendsExactlyTheConfirmedPath() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean nagForPsr = preferences.getNagForPSR();
        Client client = mock(Client.class);
        ClientGUI gui = GpuDialogFixture.routingClient();
        CommonMenuBar menu = mock(CommonMenuBar.class);
        MegaMekController controller = mock(MegaMekController.class);
        UnitDisplayState unitDisplay = mock(UnitDisplayState.class);
        AtomicReference<MockedStatic<MegaMekGUI>> keys = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(client.getMyTurn()).thenReturn(new GameTurn(fixture.player.getId()));
            when(gui.getClient()).thenReturn(client);
            when(menu.getComponents()).thenReturn(new Component[0]);
            when(gui.getMenuBar()).thenReturn(menu);
            when(gui.getUnitDisplayState()).thenReturn(unitDisplay);
            when(gui.boardStates()).thenReturn(List.of(fixture.view));
            when(gui.getBoardState()).thenReturn(fixture.view);
            when(gui.getBoardState(any(Entity.class))).thenReturn(fixture.view);
            when(gui.getBoardState(anyInt())).thenReturn(fixture.view);
            gui.controller = controller;
            MovementDisplay movement = GpuTestSupport.onSwing(() -> {
                keys.set(mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS));
                keys.get().when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                preferences.setNagForPSR(true);
                Hex rubble = fixture.game.getBoard().getHex(RUBBLE);
                rubble.addTerrain(new Terrain(Terrains.RUBBLE, 1));
                MovementDisplay display = new MovementDisplay(gui);
                fixture.view.addBoardViewListener(display);
                display.selectEntity(fixture.entity.getId());
                return display;
            });
            GpuBoardSource source = GpuTestSupport.onSwing(() -> new GpuBoardSource(fixture.view,
                  () -> movement));
            GpuDialogFixture.present(gui, fixture.view, source);
            try {
                // Without the refresh timer, only a command that runs republishes the scene.
                SwingUtilities.invokeAndWait(() -> timer(source).stop());
                source.hover(RUBBLE, 0);
                SwingUtilities.invokeAndWait(() -> { });

                // No, with the box ticked: nothing is sent, and as with ConfirmDialog the box only counts with Yes.
                GpuDialogFixture.swingYesNo("OS yes", "OS no");
                Asked<Boolean> declined = GpuDialogFixture.ask(source, () -> {
                    movement.ready();
                    return true;
                }, 1, true);
                assertEquals(Messages.getString("MovementDisplay.areYouSure"), declined.request().title());
                assertEquals(List.of(Messages.getString("Yes"), Messages.getString("No")),
                      declined.request().buttons(), "ConfirmDialog's texts, not Swing's");
                assertTrue(declined.request().message().startsWith(
                      Messages.getString("MovementDisplay.ConfirmPilotingRoll")), declined.request().message());
                assertEquals(Messages.getString("ConfirmDialog.dontBother"), declined.request().checkbox());
                assertEquals(-1, declined.request().defaultButton(),
                      "ConfirmDialog with its box has no default button: Enter must not answer the nag");
                verify(client, never()).moveEntity(anyInt(), any());
                assertTrue(preferences.getNagForPSR());

                FutureTask<Void> ready = new FutureTask<>(() -> {
                    movement.ready();
                    return null;
                });
                SwingUtilities.invokeLater(ready);
                DialogRequest shown = GpuDialogFixture.awaitDialog(source);
                assertEquals(declined.request().message(), shown.message());
                // While the nag waits, a board drag, a click and a plan command arrive from the GL side.
                BoardScene before = source.takeFrame().scene();
                source.hover(BEYOND, 0);
                source.click(BEYOND, false, 0);
                source.moves().planTo(BEYOND, 0, false);
                SwingUtilities.invokeAndWait(() -> { });
                assertSame(before, source.takeFrame().scene(), "The plan command did not run inside the nag's loop");
                assertFalse(ready.isDone());
                source.answer(shown.id(), new DialogAnswer(0, List.of(), null, true, List.of()));
                ready.get(20, SECONDS);

                ArgumentCaptor<MovePath> sent = ArgumentCaptor.forClass(MovePath.class);
                verify(client).moveEntity(eq(fixture.entity.getId()), sent.capture());
                List<MoveStep> steps = sent.getValue().getStepVector();
                assertEquals(1, steps.size(), "Only the confirmed step into the rubble is sent");
                assertEquals(RUBBLE, sent.getValue().getFinalCoords());
                assertFalse(preferences.getNagForPSR(), "Yes with the box ticked turns the nag off");
                assertNull(source.dialog());

                // Control: the same drag, accepted while no dialog waits, does change the path that is sent.
                SwingUtilities.invokeAndWait(() -> movement.selectEntity(fixture.entity.getId()));
                source.hover(BEYOND, 0);
                SwingUtilities.invokeAndWait(movement::ready);
                ArgumentCaptor<MovePath> both = ArgumentCaptor.forClass(MovePath.class);
                verify(client, times(2)).moveEntity(eq(fixture.entity.getId()), both.capture());
                assertEquals(BEYOND, both.getAllValues().get(1).getFinalCoords());
            } finally {
                GpuDialogFixture.swingYesNo(null, null);
                GpuDialogFixture.dismiss();
                SwingUtilities.invokeAndWait(() -> {
                    source.close();
                    fixture.view.removeBoardViewListener(movement);
                    movement.removeAllListeners();
                    keys.get().close();
                    preferences.setNagForPSR(nagForPsr);
                });
            }
        }
    }

    private static Timer timer(GpuBoardSource source) {
        try {
            Field field = GpuBoardSource.class.getDeclaredField("timer");
            field.setAccessible(true);
            return (Timer) field.get(source);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
