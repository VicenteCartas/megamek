/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import megamek.client.Client;
import megamek.common.Player;
import megamek.common.enums.Gender;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RandomNamesFormTest {
    private final List<Integer> ethnicitiesUsed = new ArrayList<>();
    private String faction = "General";
    private int percentFemale = 50;
    private final RandomNamesForm.Settings settings = new RandomNamesForm.Settings() {
        @Override
        public List<String> factions() {
            return List.of("General", "Clan");
        }

        @Override
        public String faction() {
            return faction;
        }

        @Override
        public void faction(String chosen) {
            faction = chosen;
        }

        @Override
        public Map<Integer, String> ethnicities() {
            return Map.of(1, "Anglo");
        }

        @Override
        public int percentFemale() {
            return percentFemale;
        }

        @Override
        public void percentFemale(int percent) {
            percentFemale = percent;
        }

        @Override
        public Gender gender() {
            return Gender.FEMALE;
        }

        @Override
        public RandomNamesForm.Namer namer() {
            return (gender, clan, ethnicity) -> {
                ethnicitiesUsed.add(ethnicity);
                return "Name " + ethnicitiesUsed.size();
            };
        }
    };
    private Client me;

    @BeforeEach
    void setUp() {
        me = mock(Client.class);
        when(me.getName()).thenReturn("Me");
        Player player = mock(Player.class);
        when(player.getId()).thenReturn(1);
        when(me.getLocalPlayer()).thenReturn(player);
    }

    @Test
    void needsAClient() {
        assertThrows(IllegalArgumentException.class, () -> new RandomNamesForm(List.of(), settings));
    }

    @Test
    void offersTheFactionMixFirstAndSavesTheSettings() {
        RandomNamesForm form = new RandomNamesForm(List.of(me), settings);
        assertEquals(2, form.ethnicities().size());
        assertEquals("Anglo", form.ethnicities().get(1));
        form.faction("Nowhere");
        assertEquals("General", form.faction());
        form.faction("Clan");
        form.percentFemale(120);
        assertEquals(100, form.percentFemale());
        form.save();
        assertEquals("Clan", faction);
        assertEquals(100, percentFemale);
    }

    @Test
    void randomizeNamesEveryCrewSlotOfOwnUnits() {
        RandomNamesForm form = new RandomNamesForm(List.of(me), settings);
        form.ethnicity(1);
        Entity own = mock(Entity.class);
        when(own.getOwnerId()).thenReturn(1);
        Crew crew = mock(Crew.class);
        when(crew.getSlotCount()).thenReturn(2);
        when(own.getCrew()).thenReturn(crew);
        Entity other = mock(Entity.class);
        when(other.getOwnerId()).thenReturn(2);

        assertEquals(1, form.randomize(List.of(own, other)));
        assertEquals(List.of(1, 1), ethnicitiesUsed);
        verify(crew).setName("Name 1", 0);
        verify(crew).setName("Name 2", 1);
        verify(crew).setGender(Gender.FEMALE, 0);
        verify(me).sendUpdateEntity(own);
        verify(me, never()).sendUpdateEntity(other);
    }
}
