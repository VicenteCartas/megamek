/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JOptionPane;

import megamek.client.Client;
import megamek.client.event.BoardViewEvent;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay.DeployMinefieldCommand;
import megamek.client.ui.util.MegaMekController;
import megamek.common.board.Coords;
import megamek.common.equipment.Briefcase;
import megamek.common.equipment.Cargo;
import megamek.common.equipment.GroundObject;
import megamek.common.equipment.ICarryable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * A real phase display's list prompt (the cargo to place during minefield deployment) asked through the client's
 * input facade: the presented native window answers it and the chosen object itself is placed; without a presented
 * window the Swing JOptionPane is asked with the prompt's own arguments.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuListPromptBridgeTest {
    private static final Coords FIRST = new Coords(3, 3);
    private static final Coords SECOND = new Coords(4, 3);

    @Test
    void theCargoToPlaceIsChosenInTheNativeWindowWhilePresentedAndInSwingOtherwise() throws Exception {
        ClientGUI gui = routingClient();
        Client client = mock(Client.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(gui.getClient()).thenReturn(client);
            GroundObject crate = groundObject(new Cargo(), "Supply crate", 2);
            GroundObject briefcase = groundObject(new Briefcase(), "Briefcase", 1);
            GroundObject pallet = groundObject(new Cargo(), "Ammo pallet", 4);
            List<ICarryable> toPlace = fixture.player.getGroundObjectsToPlace();
            toPlace.addAll(List.of(crate, briefcase, pallet));
            DeployMinefieldDisplay display = placingCargo(gui);
            present(gui, fixture.view, fixture.source);
            try {
                Asked<List<ICarryable>> cancelled = ask(fixture.source, () -> placeAt(display, fixture, FIRST),
                      new DialogAnswer(1, List.of(), null, false, List.of()));
                DialogRequest request = cancelled.request();
                assertEquals(DialogKind.CHOICE, request.kind());
                assertEquals("Choose Cargo to Place", request.title());
                assertEquals("Choose the cargo to place:", request.message());
                assertEquals(List.of("Supply crate (2.0 tons)", "Briefcase (1.0 tons)", "Ammo pallet (4.0 tons)"),
                      request.rows().stream().map(DialogRow::label).toList());
                assertEquals(List.of(0), request.initiallySelected(), "The prompt's initial value, the first object");
                assertEquals(List.of(), cancelled.result(), "Cancel places nothing");
                assertEquals(List.of(crate, briefcase, pallet), toPlace);

                Asked<List<ICarryable>> placed = ask(fixture.source, () -> placeAt(display, fixture, FIRST),
                      new DialogAnswer(0, List.of(1), null, false, List.of()));
                assertEquals(List.of(briefcase), placed.result(), "The chosen object itself is placed");
                assertEquals(List.of(crate, pallet), toPlace);

                dismiss();
                List<ICarryable> swingPlaced = onSwing(() -> {
                    try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                        swing.when(() -> JOptionPane.showInputDialog(any(), eq("Choose the cargo to place:"),
                              eq("Choose Cargo to Place"), eq(JOptionPane.QUESTION_MESSAGE), isNull(),
                              aryEq(new Object[] { crate, pallet }), eq(crate))).thenReturn(pallet);
                        return placeAt(display, fixture, SECOND);
                    }
                });
                assertEquals(List.of(pallet), swingPlaced,
                      "Without a presented window the Swing JOptionPane answers, asked with the prompt's arguments");
                assertEquals(List.of(crate), toPlace);
            } finally {
                dismiss();
                onSwing(() -> {
                    display.removeAllListeners();
                    return null;
                });
            }
        }
    }

    /** A minefield deployment display of {@code gui}'s client that places the player's cargo with the next click. */
    static DeployMinefieldDisplay placingCargo(ClientGUI gui) throws Exception {
        return onSwing(() -> {
            // The key dispatcher is only used while the display registers its commands.
            try (MockedStatic<MegaMekGUI> keys = mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS)) {
                keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(mock(MegaMekController.class));
                DeployMinefieldDisplay created = new DeployMinefieldDisplay(gui);
                created.actionPerformed(new ActionEvent(created, ActionEvent.ACTION_PERFORMED,
                      DeployMinefieldCommand.DEPLOY_CARRYABLE.getCmd()));
                return created;
            }
        });
    }

    /** EDT: a left-button press on the hex, as the board view reports it; returns the objects on the hex after it. */
    static List<ICarryable> placeAt(DeployMinefieldDisplay display, GpuBoardFixture fixture, Coords hex) {
        display.hexMoused(new BoardViewEvent(fixture.view, hex, BoardViewEvent.BOARD_HEX_DRAGGED, 0,
              MouseEvent.BUTTON1));
        return List.copyOf(fixture.game.getGroundObjects(hex));
    }

    static GroundObject groundObject(GroundObject object, String name, double tons) {
        object.setName(name);
        object.setTonnage(tons);
        return object;
    }
}
