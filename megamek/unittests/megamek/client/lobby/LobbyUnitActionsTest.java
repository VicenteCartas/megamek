/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.orders.UnitOrderAction;
import megamek.common.orders.UnitOrders;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.server.commands.UnitOrderCommand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The unit menu's actions the lobby runs itself, asking through its {@link LobbyUnitActions.Host}. */
class LobbyUnitActionsTest {
    private Game game;
    private Client client;
    private LobbyFiles files;
    private LobbyUnitActions.Host host;
    private LobbyUnitActions actions;
    private Player bot;

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        Player local = new Player(1, "Local");
        game.addPlayer(1, local);
        bot = new Player(2, "Princess");
        bot.setBot(true);
        game.addPlayer(2, bot);
        game.recordBotType(bot.getName(), AIType.CASPAR);
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        files = mock(LobbyFiles.class);
        host = mock(LobbyUnitActions.Host.class);
        actions = new LobbyUnitActions(client, new LobbyCommands(() -> client, mock(LobbyPrompts.class)), files,
              host);
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
    void stanceReadsTheMenusInfo() {
        assertEquals(LobbyCommands.Stance.PRONE, LobbyUnitActions.stance(LobbyUnitMenu.LMP_PRONE));
        assertEquals(LobbyCommands.Stance.HULL_DOWN, LobbyUnitActions.stance(LobbyUnitMenu.LMP_HULL_DOWN));
        assertEquals(LobbyCommands.Stance.STANDING, LobbyUnitActions.stance(LobbyUnitMenu.LMP_STAND));
    }

    @Test
    void idListsLeaveOutWhatIsNotInTheGame() {
        Entity atlas = mek(bot, "Atlas");
        Force alpha = lance("Alpha", bot, atlas);
        assertEquals(Set.of(atlas), LobbyUnitActions.units(game, atlas.getId() + ", 999"));
        assertEquals(Set.of(), LobbyUnitActions.units(game, ""));
        assertEquals(Set.of(alpha), LobbyUnitActions.forces(game, alpha.getId() + ",999"));
    }

    @Test
    void facingAsksOnceAndOrdersEveryUnitWhenConfirmed() {
        Entity atlas = mek(bot, "Atlas");
        Entity locust = mek(bot, "Locust");
        actions.run(LobbyUnitMenu.LMP_BOT_FACING + "|0|" + locust.getId() + ',' + atlas.getId());

        ArgumentCaptor<BotFacingForm> form = ArgumentCaptor.forClass(BotFacingForm.class);
        ArgumentCaptor<Runnable> confirmed = ArgumentCaptor.forClass(Runnable.class);
        verify(host).chooseFacing(form.capture(), confirmed.capture());
        assertEquals(atlas.getDisplayName(), form.getValue().unitName(), "the first unit by id shows its facings");
        verify(client, never()).sendChat(anyString());

        form.getValue().whileMoving(2);
        form.getValue().whenStopped(UnitOrders.FACING_AUTO);
        confirmed.getValue().run();
        for (Entity unit : List.of(atlas, locust)) {
            verify(client).sendChat(UnitOrderCommand.commandText(unit.getId(), UnitOrderAction.FACING,
                  UnitOrderCommand.FACING_WHILE_MOVING + "=2",
                  UnitOrderCommand.FACING_WHEN_STOPPED + '=' + UnitOrders.FACING_AUTO));
        }
    }

    @Test
    void convoyRoleAsksTheCardAndSendsOnlyWhenConfirmed() {
        Entity truck = mek(bot, "Truck");
        Force alpha = lance("Alpha", bot, truck);
        actions.run(LobbyUnitMenu.LMP_BOT_ROLE + '|' + LobbyUnitMenu.ROLE_CONVOY + ':' + alpha.getId());

        ArgumentCaptor<LanceRoleForm> form = ArgumentCaptor.forClass(LanceRoleForm.class);
        ArgumentCaptor<Runnable> confirmed = ArgumentCaptor.forClass(Runnable.class);
        verify(host).chooseLanceRole(form.capture(), confirmed.capture());
        assertEquals("Alpha", form.getValue().lanceName());
        verify(client, never()).sendChat(anyString());

        form.getValue().choice(LanceRoleForm.Choice.CONVOY);
        confirmed.getValue().run();
        ArgumentCaptor<String> chat = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).sendChat(chat.capture());
        assertTrue(chat.getAllValues().stream().anyMatch(text -> text.contains(String.valueOf(truck.getId()))));
    }

    @Test
    void noRoleClearsWithoutAsking() {
        Entity truck = mek(bot, "Truck");
        Force alpha = lance("Alpha", bot, truck);
        actions.run(LobbyUnitMenu.LMP_BOT_ROLE + '|' + LobbyUnitMenu.ROLE_NONE + ':' + alpha.getId());
        verify(host, never()).chooseLanceRole(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void spriteInAChosenCamoAsksTheCamoThenTheFile() {
        Entity atlas = mek(bot, "Atlas");
        actions.run(LobbyUnitMenu.LMP_SPRITE + '|' + LobbyUnitMenu.SPRITE_CHOSEN_CAMO + '|' + atlas.getId());

        ArgumentCaptor<Consumer<Camouflage>> chosen = ArgumentCaptor.forClass(Consumer.class);
        verify(host).chooseCamo(eq(atlas), chosen.capture());
        Camouflage camo = new Camouflage("Clans", "Wolf.png");
        chosen.getValue().accept(camo);

        ArgumentCaptor<Consumer<File>> write = ArgumentCaptor.forClass(Consumer.class);
        verify(files).exportSprite(eq(atlas.getDisplayName()), write.capture());
        File file = new File("atlas.png");
        write.getValue().accept(file);
        verify(host).writeSprite(atlas, camo, false, file);
    }
}
