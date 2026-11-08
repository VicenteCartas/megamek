/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.clientGUI.boardview.spriteHandler.MovementEnvelopeSpriteHandler;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.client.ui.util.MegaMekController;
import megamek.common.board.Coords;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.mockito.MockedStatic;

/**
 * A real MovementDisplay over the board fixture on the local player's movement turn, with a Sagittaire SGT-14D (walk 3,
 * run 5, jump 3) selected at (11, 11) facing north. Movement envelopes are switched on (the preference is restored on
 * close) and the client's envelope calls run their real code into a real MovementEnvelopeSpriteHandler, so the board
 * view holds the envelope sprites Swing draws. Prompts go through the routing client (GpuDialogRoutingTest). Build and
 * close it on the test thread; the display runs on the EDT.
 */
final class GpuMovementFixture implements AutoCloseable {
    static final Coords START = new Coords(11, 11);
    final GpuBoardFixture board;
    final ClientGUI gui = GpuDialogFixture.routingClient();
    final Client client = mock(Client.class);
    final Entity unit;
    final MovementDisplay display;
    private final MockedStatic<MegaMekGUI> keys;
    private final boolean envelopeShown = GUIPreferences.getInstance().getMoveEnvelope();

    private GpuMovementFixture(GpuBoardFixture board) throws Exception {
        this.board = board;
        MegaMekController controller = mock(MegaMekController.class);
        CommonMenuBar menu = mock(CommonMenuBar.class);
        when(client.getGame()).thenReturn(board.game);
        when(client.getLocalPlayer()).thenReturn(board.player);
        when(client.isMyTurn()).thenReturn(true);
        when(client.getMyTurn()).thenReturn(new GameTurn(board.player.getId()));
        when(gui.getClient()).thenReturn(client);
        when(menu.getComponents()).thenReturn(new Component[0]);
        when(gui.getMenuBar()).thenReturn(menu);
        when(gui.getUnitDisplayState()).thenReturn(mock(UnitDisplayState.class));
        when(gui.boardStates()).thenReturn(List.of(board.view));
        when(gui.getBoardState()).thenReturn(board.view);
        when(gui.getBoardState(any(Entity.class))).thenReturn(board.view);
        when(gui.getBoardState(anyInt())).thenReturn(board.view);
        gui.controller = controller;
        Field handler = ClientGUI.class.getDeclaredField("movementEnvelopeHandler");
        handler.setAccessible(true);
        handler.set(gui, new MovementEnvelopeSpriteHandler(gui, board.game));
        doCallRealMethod().when(gui).showMovementEnvelope(any(), any(), anyInt());
        doCallRealMethod().when(gui).clearMovementEnvelope();
        unit = new MekFileParser(new File("testresources/megamek/common/units/Sagittaire SGT-14D.mtf")).getEntity();
        unit.setId(2);
        unit.setOwner(board.player);
        unit.setPosition(START);
        unit.setFacing(0);
        unit.setSecondaryFacing(0);
        unit.setDeployed(true);
        // The key dispatcher mock is thread-local: the display registers and uses its keys on the EDT only.
        keys = onSwing(() -> mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS));
        try {
            display = onSwing(() -> {
                GUIPreferences.getInstance().setMoveEnvelope(true);
                keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                board.game.addEntity(unit, false);
                MovementDisplay created = new MovementDisplay(gui);
                board.view.addBoardViewListener(created);
                created.selectEntity(unit.getId());
                return created;
            });
        } catch (Exception | Error failure) {
            onSwing(() -> {
                keys.close();
                GUIPreferences.getInstance().setMoveEnvelope(envelopeShown);
                return null;
            });
            throw failure;
        }
    }

    static GpuMovementFixture create() throws Exception {
        GpuBoardFixture board = GpuBoardFixture.create();
        try {
            return new GpuMovementFixture(board);
        } catch (Exception | Error failure) {
            board.close();
            throw failure;
        }
    }

    /** Presses a movement button, as its Swing button does. */
    void command(MoveCommand command) throws Exception {
        onSwing(() -> {
            display.actionPerformed(new ActionEvent(display, ActionEvent.ACTION_PERFORMED, command.getCmd()));
            return null;
        });
    }

    /** EDT: the envelope sprites the board view holds now. */
    List<MovementEnvelopeSprite> envelopeSprites() {
        return board.view.getAllSprites().stream()
              .filter(MovementEnvelopeSprite.class::isInstance)
              .map(MovementEnvelopeSprite.class::cast)
              .toList();
    }

    @Override
    public void close() throws Exception {
        try {
            onSwing(() -> {
                board.view.removeBoardViewListener(display);
                display.removeAllListeners();
                keys.close();
                GUIPreferences.getInstance().setMoveEnvelope(envelopeShown);
                return null;
            });
        } finally {
            board.close();
        }
    }
}
