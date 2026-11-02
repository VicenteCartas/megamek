/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.common.loaders.MekSummary;

/**
 * A random army rolled from a random assignment table, without a toolkit, as the Swing {@code RandomArmyDialog}'s RAT
 * tab rolls it, dealt as unit cards: Roll fills the hand to the unit count, rerolling every card that is not kept; a
 * card can be kept, rerolled alone or discarded. The cards go to the chosen player, one the local player may add units
 * to (the dialog's player chooser).
 */
public final class RandomArmyForm {

    /** The most units one roll deals. */
    public static final int MAX_UNITS = 24;

    /** Rolls units from a table. */
    @FunctionalInterface
    public interface Roller {
        List<MekSummary> roll(int count, String table);
    }

    /** One dealt unit. */
    public static final class Card {
        private MekSummary unit;
        private boolean kept;

        private Card(MekSummary unit) {
            this.unit = unit;
        }

        public MekSummary unit() {
            return unit;
        }

        public boolean kept() {
            return kept;
        }
    }

    private final Roller roller;
    private final List<String> tables;
    private final List<String> players;
    private final List<Card> cards = new ArrayList<>();
    private String table;
    private int count = 4;
    private int player;

    /**
     * @param tables  the random assignment tables offered
     * @param initial the table to start on, when offered
     * @param players the players the units may go to, the local player first
     */
    public RandomArmyForm(List<String> tables, @Nullable String initial, List<String> players, Roller roller) {
        this.tables = List.copyOf(tables);
        this.players = List.copyOf(players);
        this.roller = Objects.requireNonNull(roller);
        table = (initial != null && tables.contains(initial)) ? initial : (tables.isEmpty() ? null : tables.get(0));
    }

    public List<String> tables() {
        return tables;
    }

    public @Nullable String table() {
        return table;
    }

    public void table(String table) {
        if (tables.contains(table)) {
            this.table = table;
        }
    }

    public int count() {
        return count;
    }

    public void count(int count) {
        this.count = Math.clamp(count, 1, MAX_UNITS);
    }

    public List<String> players() {
        return players;
    }

    public int player() {
        return player;
    }

    public void player(int index) {
        if (index >= 0 && index < players.size()) {
            player = index;
        }
    }

    /** @return the name of the player the units go to, or null when there is none */
    public @Nullable String playerName() {
        return players.isEmpty() ? null : players.get(player);
    }

    public List<Card> cards() {
        return List.copyOf(cards);
    }

    /** Whether Roll can deal: there is a table. */
    public boolean canRoll() {
        return table != null;
    }

    /** Rerolls every card not kept, then deals new cards up to the unit count, or discards unkept ones over it. */
    public void roll() {
        if (!canRoll()) {
            return;
        }
        cards.removeIf(card -> !card.kept);
        int missing = count - cards.size();
        if (missing > 0) {
            for (MekSummary unit : roller.roll(missing, table)) {
                cards.add(new Card(unit));
            }
        }
    }

    /** Rerolls the card at {@code index}, kept or not. */
    public void reroll(int index) {
        if (!canRoll() || index < 0 || index >= cards.size()) {
            return;
        }
        List<MekSummary> rolled = roller.roll(1, table);
        if (!rolled.isEmpty()) {
            cards.get(index).unit = rolled.get(0);
        }
    }

    public void keep(int index, boolean kept) {
        if (index >= 0 && index < cards.size()) {
            cards.get(index).kept = kept;
        }
    }

    public void discard(int index) {
        if (index >= 0 && index < cards.size()) {
            cards.remove(index);
        }
    }

    public void clear() {
        cards.clear();
    }

    /** @return the dealt units, in order, to add to the game */
    public List<MekSummary> army() {
        List<MekSummary> army = new ArrayList<>();
        for (Card card : cards) {
            army.add(card.unit);
        }
        return army;
    }

    /** @return the army's total Battle Value */
    public int battleValue() {
        int total = 0;
        for (Card card : cards) {
            total += card.unit.getBV();
        }
        return total;
    }

    /** @return the army's total tonnage */
    public double tonnage() {
        double total = 0;
        for (Card card : cards) {
            total += card.unit.getTons();
        }
        return total;
    }
}
