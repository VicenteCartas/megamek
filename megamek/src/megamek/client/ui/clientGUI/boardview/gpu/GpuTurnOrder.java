/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

/**
 * The turns after the current one as the deployment strip names them, in order: consecutive turns of one player fold
 * into a run ("Princess ×2"), the first few runs show and the turns after them are only counted ("+11").
 */
final class GpuTurnOrder {
    /** One player's consecutive turns. */
    record Run(GpuBattleStatus.Slot slot, int count) { }

    /** The {@code next} runs the strip names and how many turns come after them. */
    record Upcoming(List<Run> next, int more) {
        static final Upcoming EMPTY = new Upcoming(List.of(), 0);
    }

    private GpuTurnOrder() { }

    /** The turns after {@code index} (the current turn), with at most {@code maxRuns} runs named. */
    static Upcoming upcoming(List<GpuBattleStatus.Slot> turns, int index, int maxRuns) {
        if (index < 0 || index + 1 >= turns.size()) {
            return Upcoming.EMPTY;
        }
        List<Run> runs = new ArrayList<>();
        int more = 0;
        for (GpuBattleStatus.Slot slot : turns.subList(index + 1, turns.size())) {
            Run last = runs.isEmpty() ? null : runs.getLast();
            if (more == 0 && last != null && last.slot().playerId() == slot.playerId()) {
                runs.set(runs.size() - 1, new Run(last.slot(), last.count() + 1));
            } else if (more == 0 && runs.size() < maxRuns) {
                runs.add(new Run(slot, 1));
            } else {
                more++;
            }
        }
        return new Upcoming(List.copyOf(runs), more);
    }
}
