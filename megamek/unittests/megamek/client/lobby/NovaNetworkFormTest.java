/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import megamek.client.lobby.NovaNetworkForm.Partner;
import megamek.client.lobby.NovaNetworkForm.Unit;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NovaNetworkFormTest {
    private final Player me = new Player(1, "Me");
    private final Player ally = new Player(2, "Ally");
    private final Player enemy = new Player(3, "Enemy");
    private final List<Entity> entities = new ArrayList<>();
    private Game game;

    @BeforeEach
    void setUp() {
        me.setTeam(1);
        ally.setTeam(1);
        enemy.setTeam(2);
        game = mock(Game.class);
        when(game.getPlayer(1)).thenReturn(me);
        when(game.getEntitiesVector()).thenReturn(entities);
    }

    private Entity unit(int id, String name, Player owner, boolean nova, String network) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getShortName()).thenReturn(name);
        when(entity.getOwner()).thenReturn(owner);
        when(entity.getOwnerId()).thenReturn(owner.getId());
        when(entity.hasNovaCEWS()).thenReturn(nova);
        when(entity.getC3NetId()).thenReturn(network);
        entities.add(entity);
        return entity;
    }

    @Test
    void splitsTheLocalPlayersNovaUnitsFromTheAlliesAndLeavesOutEnemiesAndOthers() {
        unit(1, "Mine", me, true, "Nova.1");
        unit(2, "Friend", ally, true, "Nova.2");
        unit(3, "Foe", enemy, true, "Nova.3");
        unit(4, "Plain", me, false, null);

        NovaNetworkForm form = NovaNetworkForm.of(game, 1);

        assertEquals(List.of("Mine"), form.yours().stream().map(Unit::name).toList());
        assertNull(form.yours().get(0).owner());
        assertEquals(List.of("Friend"), form.allied().stream().map(Unit::name).toList());
        assertEquals("Ally", form.allied().get(0).owner());
        assertFalse(form.isEmpty());
    }

    @Test
    void aUnitIsLinkedWithTheOtherNovaUnitsOnItsNetwork() {
        unit(1, "Alpha", me, true, "Nova.1");
        unit(2, "Bravo", me, true, "Nova.1");
        unit(3, "Charlie", ally, true, "Nova.1");
        unit(4, "Delta", me, true, "Nova.4");
        // Shares the network id but carries no Nova CEWS, so it is no partner
        unit(5, "Echo", me, false, "Nova.4");

        NovaNetworkForm form = NovaNetworkForm.of(game, 1);

        Unit alpha = form.yours().get(0);
        assertEquals(List.of(new Partner(2, "Bravo"), new Partner(3, "Charlie")), alpha.partners());
        assertTrue(alpha.linked());
        assertEquals(3, alpha.networkSize());
        assertTrue(alpha.full());

        Unit delta = form.yours().get(2);
        assertEquals("Delta", delta.name());
        assertFalse(delta.linked(), "alone on its network");
        assertEquals(1, delta.networkSize());
        assertFalse(delta.full());
    }

    @Test
    void aUnitWithoutANetworkIsUnlinked() {
        unit(1, "Alpha", me, true, null);
        unit(2, "Bravo", me, true, null);

        NovaNetworkForm form = NovaNetworkForm.of(game, 1);

        assertTrue(form.yours().stream().noneMatch(Unit::linked), "no network id links nobody");
    }

    @Test
    void isEmptyWithoutNovaUnits() {
        unit(1, "Plain", me, false, null);
        unit(2, "Foe", enemy, true, "Nova.2");

        assertTrue(NovaNetworkForm.of(game, 1).isEmpty());
    }
}
