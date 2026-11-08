/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.util.List;
import javax.swing.JOptionPane;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.panels.phaseDisplay.SelectArtyAutoHitHexDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameTurnChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * Done in the phase that designates artillery auto-hit hexes ends it for good, so with none designated it asks first.
 * Its own preference turns it off, apart from the routine "no action this turn" questions.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuArtilleryDesignationSkipTest {
    private final boolean originalNag = GUIPreferences.getInstance().getNagForNoArtilleryDesignation();
    private GpuBoardFixture board;
    private ClientGUI gui;
    private Client client;
    private SelectArtyAutoHitHexDisplay display;

    @BeforeEach
    void startTheDesignationTurn() throws Exception {
        board = GpuBoardFixture.create();
        gui = mock(ClientGUI.class);
        client = mock(Client.class);
        CommonMenuBar menu = mock(CommonMenuBar.class);
        when(menu.getComponents()).thenReturn(new Component[0]);
        when(client.getGame()).thenReturn(board.game);
        when(client.getLocalPlayer()).thenReturn(board.player);
        when(client.isMyTurn()).thenReturn(true);
        when(gui.getClient()).thenReturn(client);
        when(gui.getMenuBar()).thenReturn(menu);
        when(gui.boardStates()).thenReturn(List.of());
        gui.controller = mock(MegaMekController.class);
        GUIPreferences.getInstance().setNagForNoArtilleryDesignation(true);
        display = onSwing(() -> {
            board.game.setPhase(GamePhase.SET_ARTILLERY_AUTO_HIT_HEXES);
            SelectArtyAutoHitHexDisplay created;
            // The display's constructor registers keys with the static dispatcher, as the Targeting fixture's does.
            try (MockedStatic<MegaMekGUI> keys = mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS)) {
                keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(mock(MegaMekController.class));
                created = new SelectArtyAutoHitHexDisplay(gui);
            }
            created.gameTurnChange(new GameTurnChangeEvent(board.game, board.player, board.player.getId()));
            return created;
        });
    }

    @AfterEach
    void restore() throws Exception {
        GUIPreferences.getInstance().setNagForNoArtilleryDesignation(originalNag);
        board.close();
    }

    @Test
    void doneWithNoHexesAsksAndNoKeepsTheTurn() throws Exception {
        when(gui.askYesNo(anyString(), anyString(), any())).thenReturn(JOptionPane.NO_OPTION);
        onSwing(() -> {
            display.ready();
            return null;
        });
        verify(gui, times(1)).askYesNo(anyString(), anyString(), any());
        verify(client, never()).sendArtyAutoHitHexes(any());
    }

    @Test
    void doneWithNoHexesAndYesEndsThePhase() throws Exception {
        when(gui.askYesNo(anyString(), anyString(), any())).thenReturn(JOptionPane.YES_OPTION);
        onSwing(() -> {
            display.ready();
            return null;
        });
        verify(client, times(1)).sendArtyAutoHitHexes(any());
    }

    /**
     * The routine "no action this turn" question being off does not silence this one: it has its own preference, as
     * it comes once a game and cannot be undone (a player with that question off skipped the phase unasked).
     */
    @Test
    void itAsksEvenWithTheNoActionQuestionTurnedOff() throws Exception {
        boolean noActionNag = GUIPreferences.getInstance().getNagForNoAction();
        GUIPreferences.getInstance().setNagForNoAction(false);
        try {
            when(gui.askYesNo(anyString(), anyString(), any())).thenReturn(JOptionPane.NO_OPTION);
            onSwing(() -> {
                display.ready();
                return null;
            });
            verify(gui, times(1)).askYesNo(anyString(), anyString(), any());
            verify(client, never()).sendArtyAutoHitHexes(any());
        } finally {
            GUIPreferences.getInstance().setNagForNoAction(noActionNag);
        }
    }

    @Test
    void withTheNagTurnedOffDoneEndsThePhaseWithoutAsking() throws Exception {
        GUIPreferences.getInstance().setNagForNoArtilleryDesignation(false);
        onSwing(() -> {
            display.ready();
            return null;
        });
        verify(gui, never()).askYesNo(anyString(), anyString(), any());
        verify(client, times(1)).sendArtyAutoHitHexes(any());
    }
}
