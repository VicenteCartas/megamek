/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import megamek.client.bot.princess.BehaviorChange;
import megamek.client.bot.princess.BotOrder;
import megamek.client.bot.princess.CombatPosture;
import megamek.common.Player;
import megamek.common.game.BotStatus;
import megamek.common.game.Game;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests that the server passes typed bot commands only to a bot the sender may command - its own side, or any bot for
 * a game master - and relays and keeps the bots' status reports.
 */
class BotCommandForwardingTest {

    private static final int BOT_ID = 1;
    private static final int ALLY_ID = 2;
    private static final int ENEMY_ID = 3;
    private static final int ALLY_HUMAN_ID = 4;

    private Game game;
    private TWGameManager gameManager;
    private Player bot;
    private Player ally;
    private Player enemy;
    private Player allyHuman;

    @BeforeEach
    void setUp() {
        game = new Game();
        bot = player(BOT_ID, "Bot", 1, true);
        ally = player(ALLY_ID, "Ally", 1, false);
        enemy = player(ENEMY_ID, "Enemy", 2, false);
        allyHuman = player(ALLY_HUMAN_ID, "Wingman", 1, false);
        gameManager = spy(new TWGameManager());
        doNothing().when(gameManager).send(any(Packet.class));
        doNothing().when(gameManager).send(anyInt(), any(Packet.class));
        gameManager.setGame(game);
    }

    private Player player(int id, String name, int team, boolean isBot) {
        Player player = new Player(id, name);
        player.setTeam(team);
        player.setBot(isBot);
        game.addPlayer(id, player);
        return player;
    }

    @Test
    void anAllyOrAGameMasterMayCommandABot() {
        assertTrue(TWGameManager.canCommandBot(ally, bot));
        assertFalse(TWGameManager.canCommandBot(enemy, bot));
        enemy.setGameMaster(true);
        assertTrue(TWGameManager.canCommandBot(enemy, bot));
    }

    @Test
    void onlyABotCanBeCommanded() {
        assertFalse(TWGameManager.canCommandBot(ally, allyHuman));
        assertFalse(TWGameManager.canCommandBot(ally, null));
        assertFalse(TWGameManager.canCommandBot(null, bot));
    }

    @Test
    void anAllysOrderIsPassedToTheBot() {
        Packet order = new Packet(PacketCommand.BOT_ORDER, BOT_ID, new BotOrder.Hold(true));

        gameManager.handlePacket(ALLY_ID, order);

        verify(gameManager).send(BOT_ID, order);
    }

    @Test
    void anAllysBehaviorChangeIsPassedToTheBot() {
        ArrayList<BehaviorChange> changes = new ArrayList<>(List.of(new BehaviorChange.Posture(CombatPosture.DEFEND)));
        Packet change = new Packet(PacketCommand.BOT_BEHAVIOR_CHANGE, BOT_ID, changes);

        gameManager.handlePacket(ALLY_ID, change);

        verify(gameManager).send(BOT_ID, change);
    }

    @Test
    void anEnemysCommandIsDropped() {
        gameManager.handlePacket(ENEMY_ID, new Packet(PacketCommand.BOT_ORDER, BOT_ID, new BotOrder.Hold(true)));
        gameManager.handlePacket(ENEMY_ID, new Packet(PacketCommand.BOT_BEHAVIOR_CHANGE, BOT_ID,
              new ArrayList<>(List.of(new BehaviorChange.ClearIgnored()))));

        verify(gameManager, never()).send(anyInt(), any(Packet.class));
    }

    @Test
    void aCommandToAHumanIsDropped() {
        gameManager.handlePacket(ALLY_ID, new Packet(PacketCommand.BOT_ORDER, ALLY_HUMAN_ID, new BotOrder.Hold(true)));

        verify(gameManager, never()).send(anyInt(), any(Packet.class));
    }

    @Test
    void aMalformedCommandIsDropped() {
        gameManager.handlePacket(ALLY_ID, new Packet(PacketCommand.BOT_BEHAVIOR_CHANGE, BOT_ID,
              new ArrayList<>(List.of("not a change"))));
        gameManager.handlePacket(ALLY_ID, new Packet(PacketCommand.BOT_ORDER, BOT_ID, "not an order"));

        verify(gameManager, never()).send(anyInt(), any(Packet.class));
    }

    @Test
    void aBotsStatusIsKeptAndRelayedWithItsId() {
        BotStatus status = new BotStatus(true, Set.of(7), Set.of());

        gameManager.handlePacket(BOT_ID, new Packet(PacketCommand.PRINCESS_STATUS, status));

        assertEquals(status, game.getBotStatus(BOT_ID));
        ArgumentCaptor<Packet> relayed = ArgumentCaptor.forClass(Packet.class);
        verify(gameManager).send(relayed.capture());
        assertEquals(PacketCommand.PRINCESS_STATUS, relayed.getValue().command());
        assertEquals(BOT_ID, relayed.getValue().getObject(0));
        assertEquals(status, relayed.getValue().getObject(1));
    }

    @Test
    void aStatusFromAHumanIsDropped() {
        gameManager.handlePacket(ALLY_ID, new Packet(PacketCommand.PRINCESS_STATUS,
              new BotStatus(true, Set.of(), Set.of())));

        assertEquals(BotStatus.NONE, game.getBotStatus(ALLY_ID));
        verify(gameManager, never()).send(any(Packet.class));
    }
}
