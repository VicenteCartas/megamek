/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;

import megamek.client.Client;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.ui.clientGUI.boardview.gpu.LobbyBattlefieldController.Command;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The native lobby's battlefield actions, without OpenGL or Swing: what reaches the Swing lobby, and what doesn't. */
class LobbyBattlefieldControllerTest {
    private Game game;
    private Client client;
    private MapSettings settings;
    private LobbyBattlefieldController.Lounge lounge;
    private LobbyBattlefieldController controller;
    private boolean showedAutoResolve;

    @BeforeEach
    void setUp() {
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        showedAutoResolve = preferences.getShowAutoResolvePanel();
        preferences.setShowAutoResolvePanel(true);
        game = new Game();
        client = mock(Client.class);
        settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setBoardsSelectedVector(List.of("16x17/Grassland 1.board"));
        when(client.getGame()).thenReturn(game);
        when(client.getMapSettings()).thenReturn(settings);
        when(client.getLocalPlayer()).thenReturn(new Player(1, "Local"));
        when(client.getBots()).thenReturn(new HashMap<>());
        lounge = mock(LobbyBattlefieldController.Lounge.class);
        when(lounge.autoResolve()).thenReturn(new LobbyBattlefield.AutoResolve(true, false, 10, 4, 8));
        controller = new LobbyBattlefieldController(() -> client, lounge);
    }

    @AfterEach
    void tearDown() {
        PreferenceManager.getClientPreferences().setShowAutoResolvePanel(showedAutoResolve);
    }

    @Test
    void theLobbyRunsTheButtons() {
        controller.run(Command.CONDITIONS);
        controller.run(Command.GAME_OPTIONS);
        controller.run(Command.VICTORY);

        verify(lounge).command(Command.CONDITIONS);
        verify(lounge).command(Command.GAME_OPTIONS);
        verify(lounge).command(Command.VICTORY);
    }

    @Test
    void theMapSetupAndTheEditorHaveTheirOwnWays() {
        controller.run(Command.MAP_SETUP);
        controller.run(Command.BOARD_EDITOR);

        verify(lounge).mapSetup();
        verify(lounge).boardEditor();
        verify(lounge, never()).command(any());
    }

    @Test
    void spaceHasNoConditionsRandomMapOrMapFiles() {
        settings.setMedium(MapSettings.MEDIUM_SPACE);

        for (Command command : List.of(Command.CONDITIONS, Command.RANDOM_MAP, Command.LOAD_MAP_SETUP,
              Command.SAVE_MAP_SETUP)) {
            assertFalse(controller.allows(command), command.name());
            controller.run(command);
        }
        assertTrue(controller.allows(Command.GAME_OPTIONS));
        verify(lounge, never()).command(any());
    }

    @Test
    void aSurpriseBoardIsNotGeneratedAhead() {
        assertTrue(controller.allows(Command.GENERATE));

        settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_SURPRISE + "a\nb"));
        controller.run(Command.GENERATE);

        verify(lounge, never()).command(Command.GENERATE);
    }

    @Test
    void autoResolveRunsOnlyWhereTheLobbyOffersIt() {
        controller.run(Command.RUN_AUTO_RESOLVE);
        verify(lounge).runAutoResolve();

        game.getOptions().getOption(OptionsConstants.BASE_REAL_BLIND_DROP).setValue(true);
        assertFalse(controller.allows(Command.RUN_AUTO_RESOLVE));

        game.getOptions().getOption(OptionsConstants.BASE_REAL_BLIND_DROP).setValue(false);
        PreferenceManager.getClientPreferences().setShowAutoResolvePanel(false);
        assertFalse(controller.allows(Command.RUN_AUTO_RESOLVE));
    }

    @Test
    void autoResolveSettingsAreKeptInBounds() {
        controller.autoResolve(true, 0, 99);
        verify(lounge).autoResolve(true, 1, 8);

        controller.autoResolve(false, 5000, 0);
        verify(lounge).autoResolve(false, LobbyBattlefield.AutoResolve.MAX_RUNS, 1);
    }
}
