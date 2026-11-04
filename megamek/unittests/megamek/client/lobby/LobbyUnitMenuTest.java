/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbyUnitMenuTest {

    private Game game;
    private Client client;
    private LobbyCommands commands;
    private Player local;
    private Player enemy;
    private Player bot;

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        local = player(1, "Local", 1);
        enemy = player(2, "Enemy", 2);
        bot = player(3, "Princess", 1);
        bot.setBot(true);
        Map<String, AbstractClient> bots = new HashMap<>();
        bots.put(bot.getName(), mock(AbstractClient.class));
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenAnswer(call -> local);
        when(client.getBots()).thenReturn(bots);
        commands = new LobbyCommands(() -> client, mock(LobbyPrompts.class));
    }

    private Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        game.addPlayer(id, player);
        return player;
    }

    private Entity mek(Player owner, String chassis) {
        BipedMek mek = new BipedMek();
        mek.setChassis(chassis);
        mek.setModel("X");
        mek.setWeight(50);
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    private Force lance(String name, Player owner, Entity... units) {
        int id = game.getForces().addTopLevelForce(new Force(name, -1, new Camouflage()), owner);
        for (Entity unit : units) {
            game.getForces().addEntity(unit, id);
        }
        return game.getForces().getForce(id);
    }

    @Test
    void ownUnitsCanBeConfiguredAndDeleted() {
        Entity hunchback = mek(local, "Hunchback");

        List<String> commands = leafCommands(menu(List.of(hunchback), List.of(), false));

        assertTrue(commands.contains(LobbyUnitMenu.LMP_CONFIGURE + "|-1|" + hunchback.getId()), commands::toString);
        assertTrue(commands.stream().anyMatch(c -> c.startsWith(LobbyUnitMenu.LMP_DELETE + "|")));
        assertTrue(commands.stream().anyMatch(c -> c.startsWith(LobbyUnitMenu.LMP_VIEW + "|")));
    }

    @Test
    void localBotsMayTargetAnEnemyFirst() {
        Entity atlas = mek(enemy, "Atlas");

        List<String> commands = leafCommands(menu(List.of(atlas), List.of(), false));

        assertTrue(commands.contains(LobbyUnitMenu.LMP_PRIORITY_TARGET + "|Princess|" + atlas.getId()), commands::toString);
    }

    @Test
    void theGameMasterMayEditEnemyUnits() {
        local.setGameMaster(true);
        Entity atlas = mek(enemy, "Atlas");

        List<String> commands = leafCommands(menu(List.of(atlas), List.of(), false));

        assertTrue(commands.contains(LobbyUnitMenu.LMP_CONFIGURE + "|-1|" + atlas.getId()), commands::toString);
    }

    @Test
    void aBotLanceOffersFormationsRolesAndFacing() {
        Entity locust = mek(bot, "Locust");
        Entity stinger = mek(bot, "Stinger");
        Force lance = lance("Bot Lance", bot, locust, stinger);

        List<String> commands = leafCommands(menu(List.of(), List.of(lance), true));

        assertTrue(commands.stream().anyMatch(c -> c.startsWith(LobbyUnitMenu.LMP_BOT_FORMATION + "|")),
              commands::toString);
        assertTrue(commands.stream().anyMatch(c -> c.startsWith(LobbyUnitMenu.LMP_BOT_ROLE + "|")));
        LobbyUnitMenu.BotLance botLance = LobbyUnitMenu.botLance(game, lance);
        assertTrue(botLance != null && botLance.owner() == bot);
        assertTrue(botLance.unitIds().containsAll(List.of(locust.getId(), stinger.getId())));
    }

    @Test
    void aCardsIconsLeaveTheirItemsOutOfItsMenu() {
        Entity hunchback = mek(local, "Hunchback");
        Set<String> icons = Set.of(LobbyUnitMenu.LMP_VIEW, LobbyUnitMenu.LMP_CONFIGURE, LobbyUnitMenu.LMP_DELETE);

        LobbyUnitMenu menu = LobbyUnitMenu.of(client, commands, List.of(hunchback), List.of(), false, icons);
        List<String> names = leafCommands(menu).stream().map(LobbyUnitMenu::name).toList();

        assertTrue(names.stream().noneMatch(icons::contains), names::toString);
        assertTrue(names.contains(LobbyUnitMenu.LMP_INDI_CAMO), "what the card has no icon for stays");
        assertFalse(menu.entries().getFirst().separator(), "the menu does not start with a separator");
        assertFalse(menu.entries().getLast().separator());
    }

    @Test
    void aCardIconRunsTheMenusCommandOnItsUnit() {
        Entity hunchback = mek(local, "Hunchback");
        String command = LobbyUnitMenu.unitCommand(LobbyUnitMenu.LMP_VIEW, hunchback.getId());

        assertTrue(leafCommands(menu(List.of(hunchback), List.of(), false)).contains(command), command);
        assertEquals(LobbyUnitMenu.LMP_VIEW, LobbyUnitMenu.name(command));
        assertEquals(LobbyUnitMenu.LMP_DELETE, LobbyUnitMenu.name(LobbyUnitMenu.LMP_DELETE));
    }

    @Test
    void separatorsSubmenusAndBadPathsHaveNoCommand() {
        LobbyUnitMenu menu = menu(List.of(mek(local, "Hunchback")), List.of(), false);

        assertNull(menu.command(List.of()));
        assertNull(menu.command(List.of(999)));
        for (int index = 0; index < menu.entries().size(); index++) {
            LobbyMenuEntry entry = menu.entries().get(index);
            if (entry.submenu() || entry.separator()) {
                assertNull(menu.command(List.of(index)));
            }
        }
    }

    private LobbyUnitMenu menu(List<Entity> entities, List<Force> forces, boolean forceView) {
        return LobbyUnitMenu.of(client, commands, entities, forces, forceView);
    }

    private static List<String> leafCommands(LobbyUnitMenu menu) {
        List<String> result = new ArrayList<>();
        collect(menu, menu.entries(), new ArrayList<>(), result);
        return result;
    }

    private static void collect(LobbyUnitMenu menu, List<LobbyMenuEntry> entries, List<Integer> prefix,
          List<String> result) {
        for (int index = 0; index < entries.size(); index++) {
            List<Integer> path = new ArrayList<>(prefix);
            path.add(index);
            LobbyMenuEntry entry = entries.get(index);
            if (entry.submenu()) {
                collect(menu, entry.children(), path, result);
            } else {
                String command = menu.command(path);
                if (command != null) {
                    result.add(command);
                }
            }
        }
    }
}
