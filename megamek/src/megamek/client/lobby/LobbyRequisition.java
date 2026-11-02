/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.UnitRecipients;
import megamek.common.Player;
import megamek.common.eras.Era;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.preference.PreferenceManager;

/**
 * What the Requisition view needs from the lobby: the game's rules on units, the players the local player may add units
 * for, as the unit selector dialog offers them, the skills its strip starts with, and the eras its Era filter names.
 *
 * @param rules        the game's year, tech level, canon and invalid-unit rules
 * @param recipients   the players units may be added for: the local player first, then their bots and, for a Game
 *                     Master, the others ({@link UnitRecipients#availableTo})
 * @param randomSkills whether the client prefers random skills for new units (the "average skills" preference)
 * @param eras         the eras of the BattleTech timeline, oldest first, each as its years
 */
public record LobbyRequisition(RequisitionCatalog.Rules rules, List<Recipient> recipients, boolean randomSkills,
      List<RequisitionCatalog.YearSpan> eras) {

    /** A player units may be added for, with the appearance key of their unit camouflage. */
    public record Recipient(int id, String name, boolean self, boolean bot, String camouflage) { }

    /** Identifies how units wear the camouflage, including its rotation, scale and overlay. */
    public static String camouflageKey(Camouflage camouflage) {
        return LobbySnapshot.camoKey(camouflage) + '|' + camouflage.getRotationAngle() + '|'
              + camouflage.getScale() + '|' + camouflage.getOverlayStyle() + '|'
              + camouflage.getOverlayDirection() + '|' + camouflage.getOverlayColor().getRGB();
    }

    /** Catalogue sprites are shared only when both the unit and its camouflage match. */
    public static String spriteKey(String unit, String camouflage) {
        return camouflage + '\0' + unit;
    }

    /** Nothing to requisition yet, as before the client has its game. */
    public static final LobbyRequisition EMPTY = new LobbyRequisition(RequisitionCatalog.Rules.OPEN, List.of(), false,
          List.of());

    public LobbyRequisition {
        Objects.requireNonNull(rules);
        recipients = List.copyOf(recipients);
        eras = List.copyOf(eras);
    }

    /** The recipient with the id {@code id}, or null. */
    public @Nullable Recipient recipient(int id) {
        return recipients.stream().filter(recipient -> recipient.id() == id).findFirst().orElse(null);
    }

    /**
     * Reads the Requisition's part of the lobby. Call it on the thread that changes the game.
     *
     * @param localBotNames the names of the bots running on this machine
     */
    public static LobbyRequisition of(Game game, @Nullable Player local, Set<String> localBotNames,
          List<RequisitionCatalog.YearSpan> eras) {
        List<Recipient> recipients = new ArrayList<>();
        if (local != null) {
            for (Player player : UnitRecipients.availableTo(local, game.getPlayersList(), localBotNames, false,
                  null)) {
                recipients.add(new Recipient(player.getId(), player.getName(), player.getId() == local.getId(),
                      player.isBot(), camouflageKey(player.getCamouflage())));
            }
        }
        return new LobbyRequisition(RequisitionCatalog.Rules.of(game.getOptions()), recipients,
              PreferenceManager.getClientPreferences().useAverageSkills(), eras);
    }

    /** The eras of {@code eras} as year spans, oldest first; the first starts at year 0, the last runs on. */
    public static List<RequisitionCatalog.YearSpan> spans(Collection<Era> eras) {
        List<RequisitionCatalog.YearSpan> spans = new ArrayList<>();
        int from = 0;
        for (Era era : eras) {
            LocalDate end = era.end();
            int to = (end == null || end.getYear() >= LocalDate.MAX.getYear()) ? Integer.MAX_VALUE : end.getYear();
            spans.add(new RequisitionCatalog.YearSpan(era.name(), from, to));
            if (to == Integer.MAX_VALUE) {
                break;
            }
            from = to + 1;
        }
        return spans;
    }
}
