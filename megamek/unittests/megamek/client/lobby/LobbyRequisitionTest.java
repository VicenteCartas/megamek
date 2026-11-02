/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Set;

import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import org.junit.jupiter.api.Test;

class LobbyRequisitionTest {
    @Test
    void recipientAppearanceIsSnapshotStateAndIncludesHowTheCamoIsApplied() {
        Game game = new Game();
        Player local = new Player(1, "Local");
        Player bot = new Player(2, "Princess");
        bot.setBot(true);
        local.setCamouflage(new Camouflage("Test/", "blue.png"));
        Camouflage camouflage = new Camouflage("Test/", "red.png");
        bot.setCamouflage(camouflage);
        game.addPlayer(local.getId(), local);
        game.addPlayer(bot.getId(), bot);

        LobbyRequisition before = LobbyRequisition.of(game, local, Set.of(bot.getName()), List.of());
        String first = before.recipient(bot.getId()).camouflage();
        assertNotEquals(before.recipient(local.getId()).camouflage(), first);
        camouflage.setRotationAngle(90);
        LobbyRequisition rotated = LobbyRequisition.of(game, local, Set.of(bot.getName()), List.of());
        assertNotEquals(first, rotated.recipient(bot.getId()).camouflage());
        assertEquals(first, before.recipient(bot.getId()).camouflage(), "the old snapshot keeps its appearance");

        camouflage.setScale(12);
        LobbyRequisition scaled = LobbyRequisition.of(game, local, Set.of(bot.getName()), List.of());
        assertNotEquals(rotated, scaled, "changing only the camo scale refreshes the recipient's previews too");
    }
}
