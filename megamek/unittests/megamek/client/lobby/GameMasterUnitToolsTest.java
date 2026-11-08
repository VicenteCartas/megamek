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
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import megamek.common.Player;
import megamek.common.enums.ForcedWithdrawalOrder;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;

class GameMasterUnitToolsTest {
    private static final String UNITS = "testresources/megamek/common/units/";

    /** A unit owned by the first of {@code players}, each on a team, in a new game. */
    private static Entity load(String file, Player... players) throws Exception {
        Entity entity = new MekFileParser(new File(UNITS + file)).getEntity();
        Game game = new Game();
        for (Player player : players) {
            game.addPlayer(player.getId(), player);
        }
        entity.setGame(game);
        entity.setId(game.getNextEntityId());
        if (entity.getCrew() == null) {
            entity.setCrew(new Crew(CrewType.SINGLE));
        }
        if (players.length > 0) {
            entity.setOwner(players[0]);
        }
        game.addEntity(entity);
        return entity;
    }

    private static Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        return player;
    }

    private static GameMasterUnitTools tools(Entity entity) {
        UnitDamageForm form = UnitDamageForm.of(entity, UnitDamageForm.Options.inPlay((Game) entity.getGame()));
        assertNotNull(form.gameMaster());
        return form.gameMaster();
    }

    /** Records what the tools send. */
    private static final class Sent implements GameMasterUnitTools.Host {
        final List<DamageEditSpec> edits = new ArrayList<>();
        final List<String> commands = new ArrayList<>();

        @Override
        public void sendDamageEdit(DamageEditSpec spec) {
            edits.add(spec);
        }

        @Override
        public void sendChat(String command) {
            commands.add(command);
        }
    }

    @Test
    void theLobbyFormHasNoGameMasterTools() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        UnitDamageForm lobby = UnitDamageForm.of(atlas, new UnitDamageForm.Options(true, true, false));
        assertNull(lobby.gameMaster());
        assertFalse(lobby.deployment().isEmpty());
        for (UnitDamageForm.Place place : lobby.places()) {
            for (UnitDamageForm.Value value : place.values()) {
                assertEquals(-1, value.explosive(), value.label());
            }
        }
    }

    @Test
    void inPlayTheUnitsStateIsAnEditAndNotADeployment() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        UnitDamageForm form = UnitDamageForm.of(atlas, UnitDamageForm.Options.inPlay((Game) atlas.getGame()));
        assertTrue(form.deployment().isEmpty());
        DamageEditSpec spec = form.spec();
        assertNotNull(spec.heat);
        assertNotNull(spec.prone);
    }

    @Test
    void explosiveEquipmentIsNumberedForTheExplodeCommand() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        GameMasterUnitTools tools = tools(atlas);
        UnitDamageForm form = UnitDamageForm.of(atlas, UnitDamageForm.Options.inPlay((Game) atlas.getGame()));
        Optional<UnitDamageForm.Value> bin = form.places().stream().flatMap(place -> place.values().stream())
              .filter(value -> value.explosive() >= 0).findFirst();
        assertTrue(bin.isPresent());
        int number = bin.get().explosive();
        assertTrue(atlas.getEquipment(number).wouldExplodeWhenHit());
        assertEquals("/explode " + atlas.getId() + " " + number, tools.explodeCommand(number));
        assertTrue(tools.explodeConfirmation(number).contains(atlas.getEquipment(number).getName()));
    }

    @Test
    void withdrawAndDestroyAreServerCommands() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        GameMasterUnitTools tools = tools(atlas);
        assertEquals("/rescue " + atlas.getId(), tools.withdrawCommand());
        assertEquals("/kill " + atlas.getId() + " false", tools.destroyCommand(false));
        assertEquals("/kill " + atlas.getId() + " " + atlas.canEjectCrew(), tools.destroyCommand(true));
        assertEquals(atlas.canEjectCrew(), tools.canEjectCrew());
    }

    @Test
    void aCrewThatCannotEjectIsNeverEjected() throws Exception {
        // vehicles eject only under their game option, which a new game leaves off
        Entity tank = load("Bulldog Medium Tank.blk", player(0, "Alice", 1));
        GameMasterUnitTools tools = tools(tank);
        assertFalse(tools.canEjectCrew());
        assertEquals("/kill " + tank.getId() + " false", tools.destroyCommand(true));
    }

    @Test
    void theOwnerChangesOnlyAmongTwoOrMorePlayersOnTeams() throws Exception {
        assertTrue(tools(load("Atlas AS7-D.mtf", player(0, "Alice", 1))).ownerNames().isEmpty());
        assertTrue(tools(load("Atlas AS7-D.mtf", player(0, "Alice", 1),
              player(1, "Bob", Player.TEAM_UNASSIGNED))).ownerNames().isEmpty());

        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1), player(1, "Bob", 2));
        GameMasterUnitTools tools = tools(atlas);
        assertEquals(List.of("Alice", "Bob"), tools.ownerNames());
        assertEquals(0, tools.owner());
        assertTrue(tools.changeOwnerCommand(0).isEmpty());
        assertEquals(Optional.of("/changeOwner " + atlas.getId() + " 1"), tools.changeOwnerCommand(1));
        assertTrue(tools.ownerConfirmation(1).contains("Bob"));
    }

    @Test
    void onlyABotsUnitTakesAWithdrawalOrder() throws Exception {
        assertTrue(tools(load("Atlas AS7-D.mtf", player(0, "Alice", 1))).orderNames().isEmpty());

        Player bot = player(0, "Princess", 1);
        bot.setBot(true);
        Entity atlas = load("Atlas AS7-D.mtf", bot);
        GameMasterUnitTools tools = tools(atlas);
        assertEquals(ForcedWithdrawalOrder.values().length, tools.orderNames().size());
        assertTrue(tools.withdrawOrderCommand().isEmpty());
        ForcedWithdrawalOrder other = ForcedWithdrawalOrder.values()[(tools.order() + 1)
              % ForcedWithdrawalOrder.values().length];
        tools.order(other.ordinal());
        assertEquals(Optional.of("/withdrawOrder " + atlas.getId() + " " + other.name()),
              tools.withdrawOrderCommand());

        Sent sent = new Sent();
        tools.commit(sent);
        assertEquals(1, sent.edits.size());
        assertEquals(List.of("/withdrawOrder " + atlas.getId() + " " + other.name()), sent.commands);
    }

    @Test
    void theModifiersFillTheSpec() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        GameMasterUnitTools tools = tools(atlas);
        List<GameMasterUnitTools.Modifier> modifiers = tools.modifiers();
        // gunnery, piloting and the target modifier; initiative waits for individual initiative
        assertEquals(3, modifiers.size());
        GameMasterUnitTools.Modifier gunnery = modifiers.get(0);
        GameMasterUnitTools.Modifier piloting = modifiers.get(1);
        GameMasterUnitTools.Modifier target = modifiers.get(2);
        assertTrue(gunnery.timed());
        assertFalse(target.timed());
        assertNotNull(target.note());
        gunnery.value(2);
        gunnery.rounds(5);
        piloting.value(1);
        piloting.permanent(true);
        target.value(1);

        Sent sent = new Sent();
        tools.commit(sent);
        DamageEditSpec spec = sent.edits.get(0);
        assertEquals(2, spec.gunneryModifier);
        assertEquals(5, spec.gunneryRounds);
        assertFalse(spec.gunneryPermanent);
        assertEquals(1, spec.pilotingModifier);
        assertTrue(spec.pilotingPermanent);
        assertEquals(1, spec.targetModifier);
        assertNull(spec.initiativeModifier);
        assertTrue(sent.commands.isEmpty());
    }

    @Test
    void individualInitiativeAddsTheInitiativeModifier() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        ((Game) atlas.getGame()).getOptions().getOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE).setValue(true);
        GameMasterUnitTools tools = tools(atlas);
        assertEquals(4, tools.modifiers().size());
        tools.modifiers().get(2).value(-3);
        Sent sent = new Sent();
        tools.commit(sent);
        assertEquals(-3, sent.edits.get(0).initiativeModifier);
    }

    @Test
    void theEjectionSettingsFillTheSpec() throws Exception {
        Entity atlas = load("Atlas AS7-D.mtf", player(0, "Alice", 1));
        ((Mek) atlas).setAutoEject(true);
        GameMasterUnitTools tools = tools(atlas);
        List<GameMasterUnitTools.Setting> ejection = tools.ejection();
        // without conditional ejection only the switch that turns automatic ejection off
        assertEquals(1, ejection.size());
        assertFalse(ejection.get(0).on());
        ejection.get(0).on(true);
        Sent sent = new Sent();
        tools.commit(sent);
        assertEquals(Boolean.FALSE, sent.edits.get(0).autoEject);
    }
}
