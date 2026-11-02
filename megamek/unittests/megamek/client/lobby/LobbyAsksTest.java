/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

class LobbyAsksTest {
    private final LobbyPrompts prompts = mock(LobbyPrompts.class);
    private final LobbyAsks asks = LobbyAsks.of(prompts);
    private final List<Object> answers = new ArrayList<>();

    @Test
    void needsPrompts() {
        assertThrows(NullPointerException.class, () -> LobbyAsks.of(null));
    }

    @Test
    void confirmRunsYesOnlyOnAYes() {
        when(prompts.confirm("T", "Q")).thenReturn(false);
        asks.confirm("T", "Q", () -> answers.add("yes"));
        when(prompts.confirm("T", "Q")).thenReturn(true);
        asks.confirm("T", "Q", () -> answers.add("yes"));
        assertEquals(List.of("yes"), answers);
    }

    @Test
    void namesAreHandedOnUnlessBlankOrCancelled() {
        when(prompts.chooseSquadronDesignation()).thenReturn(Optional.of(" "), Optional.empty(), Optional.of("Red"));
        for (int i = 0; i < 3; i++) {
            asks.chooseSquadronDesignation(answers::add);
        }
        assertEquals(List.of("Red"), answers);
    }

    @Test
    void assetEditsRunTheirCallbackWhenApplied() {
        BattlefieldSupportAsset asset = mock(BattlefieldSupportAsset.class);
        when(prompts.configureAsset(asset, true)).thenReturn(true);
        when(prompts.editAssetDamage(asset)).thenReturn(false);
        asks.configureAsset(asset, true, () -> answers.add("crew"));
        asks.editAssetDamage(asset, () -> answers.add("damage"));
        assertEquals(List.of("crew"), answers);
    }

    @Test
    void trainOrderAndDamageArePassedThrough() {
        Entity head = mock(Entity.class);
        Entity trailer = mock(Entity.class);
        when(prompts.orderTrain(head, List.of(trailer))).thenReturn(Optional.of(List.of(trailer)));
        asks.orderTrain(head, List.of(trailer), answers::add);
        asks.editDamage(head, () -> answers.add("done"));
        verify(prompts).editDamage(head);
        assertEquals(List.of(List.of(trailer), "done"), answers);
    }
}
