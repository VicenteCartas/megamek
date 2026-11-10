/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuLobbyWindow;
import megamek.client.ui.dialogs.randomArmy.RandomArmyDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekSummaryCache;
import org.junit.jupiter.api.Test;

class ClientGUIUnitLoadingTest {
    @Test
    void enteringLobbyDoesNotWaitForUnitsOrConstructUnitTools() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<ClientGUI> opened = new AtomicReference<>();
        FutureTask<Void> startup = new FutureTask<>(() -> {
            try (var sound = mockConstruction(SoundManager.class);
                  var inspector = mockConstruction(UnitDisplayPanel.class);
                  var inspectorDialog = mockConstruction(UnitDisplayDialog.class);
                  var cacheAccess = mockStatic(MekSummaryCache.class);
                  var application = mockStatic(MegaMekGUI.class);
                  // Keep real Swing presentation, but prevent a GL thread from outliving gui.die() and opening a
                  // failure dialog in later tests. This test exercises the routing, not native rendering.
                  var boardWindow = mockStatic(GpuBoardWindow.class, call ->
                        call.getMethod().getName().equals("open") ? null : call.callRealMethod());
                  // The lobby is a scene of the native window; the test checks what entering it builds, not the scene.
                  var lobbyWindow = mockStatic(GpuLobbyWindow.class)) {
                MekSummaryCache cache = mock(MekSummaryCache.class);
                cacheAccess.when(MekSummaryCache::getInstance).thenReturn(cache);
                MegaMekController controller = new MegaMekController();
                application.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                Game game = new Game();
                game.setPhase(GamePhase.LOUNGE);
                Player player = new Player(0, "Loading test");
                game.addPlayer(0, player);
                Client client = mock(Client.class);
                when(client.getName()).thenReturn(player.getName());
                when(client.getGame()).thenReturn(game);
                when(client.getLocalPlayer()).thenReturn(player);
                MapSettings settings = MapSettings.getInstance();
                settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_GENERATED));
                when(client.getMapSettings()).thenReturn(settings);
                ClientGUI gui = new ClientGUI(client, controller);
                opened.set(gui);
                try {
                    gui.initialize();
                    gui.switchPanel(GamePhase.LOUNGE);
                    lobbyWindow.verify(() -> GpuLobbyWindow.open(gui), atLeastOnce());
                    boardWindow.verify(() -> GpuBoardWindow.open(eq(gui), any()), never());
                    gui.setUnitDisplayVisible(true);
                    assertTrue(inspector.constructed().isEmpty());
                    assertTrue(inspectorDialog.constructed().isEmpty());
                    assertTrue(Arrays.stream(gui.getFrame().getOwnedWindows())
                          .noneMatch(window -> window instanceof RandomArmyDialog));
                    verify(cache, never()).getAllMeks();
                } finally {
                    gui.die();
                    opened.set(null);
                }
            }
            return null;
        });
        SwingUtilities.invokeLater(startup);
        try {
            startup.get(20, TimeUnit.SECONDS);
        } finally {
            // Also release a modal progress window if a regression makes initialize() wait indefinitely.
            SwingUtilities.invokeAndWait(() -> {
                ClientGUI gui = opened.get();
                if (gui != null) {
                    gui.getFrame().dispose();
                }
            });
        }
    }
}
