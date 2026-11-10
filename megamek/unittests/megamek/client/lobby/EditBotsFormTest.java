/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.Princess;
import megamek.client.lobby.EditBotsForm.Action;
import megamek.client.lobby.EditBotsForm.Kind;
import megamek.client.lobby.EditBotsForm.Row;
import megamek.client.lobby.EditBotsForm.Seat;
import megamek.common.Player;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;

class EditBotsFormTest {

    private static Seat ghost(String name, BehaviorSettings saved, AIType savedType) {
        return new Seat(name, name, Kind.GHOST, new BehaviorSettings(), saved, savedType, null);
    }

    private static Seat localBot(String name, BehaviorSettings current, AIType type) {
        return new Seat(name, name, Kind.LOCAL_BOT, current, null, type, null);
    }

    private static Seat remoteBot(String name) {
        return new Seat(name, name, Kind.REMOTE_BOT, new BehaviorSettings(), null, null, null);
    }

    private static BehaviorSettings settings(int bravery) {
        BehaviorSettings settings = new BehaviorSettings();
        settings.setBraveryIndex(bravery);
        return settings;
    }

    @Test
    void offersEachKindItsSwingActionsAndChangesNothingUntilChosen() {
        EditBotsForm form = new EditBotsForm(List.of(ghost("Gone", null, null),
              localBot("Here", settings(3), AIType.PRINCESS), remoteBot("There")), false);

        List<Row> rows = form.rows();
        assertEquals(List.of(Action.KEEP_GHOST, Action.REPLACE_PRINCESS), rows.get(0).actions());
        assertEquals(List.of(Action.KEEP, Action.KICK, Action.EDIT), rows.get(1).actions());
        assertEquals(List.of(Action.KEEP, Action.KICK), rows.get(2).actions());
        assertTrue(form.result().isEmpty());
        assertFalse(rows.get(0).configurable(), "a ghost kept as a ghost takes no bot settings");
        assertTrue(rows.get(1).configurable());
        assertFalse(rows.get(2).configurable(), "a remote bot's settings are not ours to change");
    }

    @Test
    void offersCasparOnlyWhenItIsSwitchedOn() {
        EditBotsForm form = new EditBotsForm(List.of(ghost("Gone", null, null)), true);
        Row row = form.rows().get(0);

        assertEquals(List.of(Action.KEEP_GHOST, Action.REPLACE_PRINCESS, Action.REPLACE_CASPAR), row.actions());
        row.action(Action.REPLACE_CASPAR);
        assertEquals(AIType.CASPAR, row.configuredType());
        assertEquals(Map.of("Gone", AIType.CASPAR), form.result().newBotTypes());

        EditBotsForm without = new EditBotsForm(List.of(ghost("Gone", null, null)), false);
        without.rows().get(0).action(Action.REPLACE_CASPAR);
        assertEquals(Action.KEEP_GHOST, without.rows().get(0).action(), "an action the seat lacks is ignored");
    }

    @Test
    void replacesAGhostThatWasABotWithTheSavedKindOfBotAndACopyOfItsSettings() {
        BehaviorSettings saved = settings(8);
        EditBotsForm form = new EditBotsForm(List.of(ghost("Princess", saved, AIType.PRINCESS),
              ghost("Old", settings(2), null)), true);

        Row princess = form.rows().get(0);
        assertEquals(Action.REPLACE_PRINCESS, princess.action(), "the save remembers Princess");
        assertEquals(8, princess.settings().getBraveryIndex());
        assertNotSame(saved, princess.settings(), "the saved settings are never changed");
        assertEquals(Action.REPLACE_CASPAR, form.rows().get(1).action(),
              "an older save with CASPAR on falls back on CASPAR");

        EditBotsForm.Result result = form.result();
        assertEquals(Set.of("Princess", "Old"), result.newBots().keySet());
        assertEquals(AIType.PRINCESS, result.newBotTypes().get("Princess"));
        assertEquals(AIType.CASPAR, result.newBotTypes().get("Old"));
    }

    @Test
    void editsALocalBotOnceItsSettingsAreConfirmedAndRestoresTheSavedOnes() {
        Seat seat = new Seat("Here", "Here", Kind.LOCAL_BOT, settings(3), settings(9), AIType.PRINCESS, null);
        EditBotsForm form = new EditBotsForm(List.of(seat), false);
        Row row = form.rows().get(0);
        BehaviorSettings chosen = settings(6);

        row.configured(chosen);

        assertEquals(Action.EDIT, row.action(), "confirming the settings sets the bot to Edit, as Swing did");
        assertSame(chosen, form.result().changedBots().get("Here"));
        assertTrue(row.restorable());
        row.restore();
        assertEquals(9, form.result().changedBots().get("Here").getBraveryIndex());
        row.action(Action.KICK);
        assertFalse(row.restorable(), "a bot being kicked takes no settings");
        assertEquals(Set.of("Here"), form.result().kickBots());
        assertTrue(form.result().changedBots().isEmpty());
    }

    @Test
    void kicksARemoteBot() {
        EditBotsForm form = new EditBotsForm(List.of(remoteBot("There")), false);
        form.rows().get(0).action(Action.KICK);

        assertEquals(Set.of("There"), form.result().kickBots());
    }

    @Test
    void readsTheGhostsAndBotsFromTheClientInPlayerOrder() {
        Client client = mock(Client.class);
        Game game = mock(Game.class);
        when(client.getGame()).thenReturn(game);
        Player human = new Player(1, "Human");
        Player remote = new Player(4, "Remote");
        remote.setBot(true);
        Player gone = new Player(2, "Gone");
        gone.setGhost(true);
        Player local = new Player(3, "Local");
        local.setBot(true);
        Princess princess = mock(Princess.class);
        when(princess.getBehaviorSettings()).thenReturn(settings(4));
        when(princess.getAIType()).thenReturn(AIType.PRINCESS);
        when(game.getPlayersList()).thenReturn(List.of(human, remote, gone, local));
        when(game.getBotSettings()).thenReturn(Map.of("Gone", settings(7)));
        when(game.getBotTypes()).thenReturn(Map.of());
        when(client.isLocalBot(local)).thenReturn(true);
        when(client.getBotClient(local)).thenReturn(princess);

        EditBotsForm form = EditBotsForm.of(client, false);

        List<Row> rows = form.rows();
        assertEquals(List.of("Gone", "Local", "Remote"), rows.stream().map(Row::name).toList());
        assertEquals(List.of(Kind.GHOST, Kind.LOCAL_BOT, Kind.REMOTE_BOT), rows.stream().map(Row::kind).toList());
        assertEquals(Action.REPLACE_PRINCESS, rows.get(0).action());
        assertEquals(7, rows.get(0).settings().getBraveryIndex());
        assertEquals(4, rows.get(1).settings().getBraveryIndex());
    }
}
