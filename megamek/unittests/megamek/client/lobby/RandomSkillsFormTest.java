/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.client.Client;
import megamek.client.generator.enums.SkillGeneratorMethod;
import megamek.client.generator.enums.SkillGeneratorType;
import megamek.client.generator.skillGenerators.AbstractSkillGenerator;
import megamek.common.Player;
import megamek.common.enums.SkillLevel;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

class RandomSkillsFormTest {

    private static Client client(String name, int playerId, AbstractSkillGenerator generator) {
        Client client = mock(Client.class);
        when(client.getName()).thenReturn(name);
        Player player = mock(Player.class);
        when(player.getId()).thenReturn(playerId);
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getSkillGenerator()).thenReturn(generator);
        return client;
    }

    @Test
    void needsAClient() {
        assertThrows(IllegalArgumentException.class, () -> new RandomSkillsForm(List.of()));
    }

    @Test
    void readsAndSavesTheShownClientsGenerator() {
        AbstractSkillGenerator generator = SkillGeneratorMethod.TOTAL_WARFARE.getGenerator();
        Client me = client("Me", 1, generator);
        RandomSkillsForm form = new RandomSkillsForm(List.of(me));
        assertEquals(List.of("Me"), form.clientNames());
        assertEquals(SkillGeneratorMethod.TOTAL_WARFARE, form.method());

        form.type(SkillGeneratorType.CLAN);
        form.level(SkillLevel.ELITE);
        form.forceClose(true);
        form.save();
        assertEquals(SkillGeneratorType.CLAN, generator.getType());
        assertEquals(SkillLevel.ELITE, generator.getLevel());
        assertTrue(generator.isForceClose());
        verify(me, never()).setSkillGenerator(any());

        form.method(SkillGeneratorMethod.CONSTANT);
        form.save();
        verify(me).setSkillGenerator(any());
    }

    @Test
    void selectingAnotherClientSavesTheFirst() {
        AbstractSkillGenerator mine = SkillGeneratorMethod.TOTAL_WARFARE.getGenerator();
        AbstractSkillGenerator bots = SkillGeneratorMethod.TOTAL_WARFARE.getGenerator();
        bots.setLevel(SkillLevel.GREEN);
        RandomSkillsForm form = new RandomSkillsForm(List.of(client("Me", 1, mine), client("Bot", 2, bots)));
        form.level(SkillLevel.VETERAN);
        form.select(1);
        assertEquals(SkillLevel.VETERAN, mine.getLevel());
        assertEquals(SkillLevel.GREEN, form.level());
        assertEquals(1, form.selected());
    }

    @Test
    void randomizeSendsOnlyTheClientsOwnUnits() {
        AbstractSkillGenerator generator = mock(AbstractSkillGenerator.class);
        when(generator.getMethod()).thenReturn(SkillGeneratorMethod.TOTAL_WARFARE);
        when(generator.getType()).thenReturn(SkillGeneratorType.INNER_SPHERE);
        when(generator.getLevel()).thenReturn(SkillLevel.REGULAR);
        Client me = client("Me", 1, generator);
        Entity own = mock(Entity.class);
        when(own.getOwnerId()).thenReturn(1);
        Entity other = mock(Entity.class);
        when(other.getOwnerId()).thenReturn(2);

        assertEquals(1, new RandomSkillsForm(List.of(me)).randomize(List.of(own, other)));
        verify(generator).setRandomSkills(own);
        verify(me).sendUpdateEntity(own);
        verify(me, never()).sendUpdateEntity(other);
    }
}
