/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.client.Client;
import megamek.client.lobby.LobbyCommands;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The lobby services' ready flow, auto-resolve bounds and menu bar shortcuts, which ChatLounge held before. */
class LobbyServicesTest {
    private final Game game = new Game();
    private final Player local = new Player(0, "Local");
    private ClientGUI gui;
    private LobbyCommands commands;
    private LobbyServices services;

    @BeforeEach
    void setUp() {
        game.addPlayer(0, local);
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        commands = mock(LobbyCommands.class);
        when(gui.lobbyCommands()).thenReturn(commands);
        services = LobbyServices.of(gui);
    }

    @AfterEach
    void tearDown() {
        LobbyServices.forget(gui);
    }

    @Test
    void oneClientKeepsItsServicesUntilItIsForgotten() {
        assertSame(services, LobbyServices.of(gui));
        LobbyServices.forget(gui);
        assertNotSame(services, LobbyServices.of(gui));
    }

    @Test
    void autoResolveSettingsStayWithinBounds() {
        var defaults = services.autoResolveSettings();
        assertEquals(LobbyServices.DEFAULT_RUNS, defaults.runs());
        assertFalse(defaults.chances());

        services.setAutoResolveSettings(true, 0, 0);
        var low = services.autoResolveSettings();
        assertEquals(1, low.runs());
        assertEquals(1, low.threads());

        services.setAutoResolveSettings(true, 5000, Integer.MAX_VALUE);
        var high = services.autoResolveSettings();
        assertEquals(LobbyServices.MAX_RUNS, high.runs());
        assertEquals(high.maxThreads(), high.threads());
        assertEquals(Math.max(2, Runtime.getRuntime().availableProcessors() * 2), high.maxThreads());
    }

    @Test
    void readyingThatFailsItsCheckStaysUnready() {
        when(commands.checkReady()).thenReturn(false);
        services.ready();
        verify(commands).checkReady();
        verify(commands, never()).sendDone(anyBoolean());
    }

    @Test
    void standingDownIsNeverChecked() {
        local.setDone(true);
        services.ready();
        verify(commands, never()).checkReady();
        verify(commands).sendDone(false);
    }

    @Test
    void theMenuBarShortcutsActOnlyOnEditableUnits() {
        Entity own = new BipedMek();
        Entity enemy = new BipedMek();
        own.setOwner(local);
        enemy.setOwner(local);
        game.addEntity(own);
        game.addEntity(enemy);
        when(commands.isEditable(own)).thenReturn(true);
        services.setAllBurstMg(true);
        verify(commands).toggleBurstMg(List.of(own), true);
        services.setAllHotLoad(false);
        verify(commands).toggleHotLoad(List.of(own), false);
    }
}
