/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.GenerationContext;
import megamek.common.loaders.MekSummary;

/**
 * A random army, without a toolkit, dealt as unit cards: Roll fills the hand to the unit count, rerolling every card
 * that is not kept; a card can be kept, rerolled alone or discarded. The cards go to the chosen player, one the local
 * player may add units to (the dialog's player chooser).
 * <p>
 * The hand is dealt from a random assignment table, as the Swing {@code RandomArmyDialog}'s RAT tab rolls it, until a
 * {@link Dealer} takes over: one of the dialog's other generators, such as the Battle Value army or the formation
 * builder.
 */
public final class RandomArmyForm {

    /** The most units one roll deals. */
    public static final int MAX_UNITS = 24;

    /** Rolls units from a table. */
    @FunctionalInterface
    public interface Roller {
        List<MekSummary> roll(int count, String table);
    }

    /** A generator the hand is dealt from instead of a table. */
    public interface Dealer {
        /** Whether it can deal now, with what it has been asked for. */
        boolean canDeal();

        /**
         * Deals the units to go with {@code kept}, the cards the hand keeps: {@code count} of them, unless the dealer
         * knows better (a Battle Value army deals what its counts and budget ask for).
         */
        List<MekSummary> deal(int count, List<MekSummary> kept);

        /** A unit to take the place of {@code unit}, or null when there is none; {@code rest} are the other cards. */
        @Nullable MekSummary replace(MekSummary unit, List<MekSummary> rest);

        /**
         * Whether a roll deals a whole hand that holds together, as a formation does: Roll deals every card anew and
         * no card is kept or rerolled alone.
         */
        default boolean wholeHand() {
            return false;
        }

        /** Whether the hand's size is the form's unit count; a dealer with counts of its own says no. */
        default boolean usesCount() {
            return true;
        }

        /** What the hand was dealt for, to record with the units, or null when the dealer knows nothing of that. */
        default @Nullable GenerationContext context() {
            return null;
        }
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
    private @Nullable Dealer dealer;
    private @Nullable GenerationContext dealtFor;

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

    /** The generator the hand is dealt from, or null for the chosen table. */
    public @Nullable Dealer dealer() {
        return dealer;
    }

    /** Deals the hand from {@code dealer} from now on, or from the chosen table for null; the cards stay. */
    public void dealer(@Nullable Dealer dealer) {
        this.dealer = dealer;
    }

    /** Whether a card can be kept or rerolled alone, which a formation's cards cannot. */
    public boolean cardsApart() {
        return dealer == null || !dealer.wholeHand();
    }

    /** Whether Roll can deal: there is a table, or the dealer can deal. */
    public boolean canRoll() {
        return dealer == null ? table != null : dealer.canDeal();
    }

    /**
     * Rerolls every card not kept, then deals new cards up to the unit count, or discards unkept ones over it. A
     * dealer of whole hands deals every card anew.
     */
    public void roll() {
        if (!canRoll()) {
            return;
        }
        if (dealer != null && dealer.wholeHand()) {
            cards.clear();
            deal(dealer.deal(count, List.of()));
            noteContext();
            return;
        }
        cards.removeIf(card -> !card.kept);
        int missing = count - cards.size();
        if (dealer != null && !dealer.usesCount()) {
            deal(dealer.deal(Math.max(0, missing), army()));
        } else if (missing > 0) {
            deal(dealer == null ? roller.roll(missing, table) : dealer.deal(missing, army()));
        }
        noteContext();
    }

    private void deal(List<MekSummary> units) {
        for (MekSummary unit : units) {
            if (cards.size() >= MAX_UNITS) {
                return;
            }
            cards.add(new Card(unit));
        }
    }

    /** Rerolls the card at {@code index}, kept or not. */
    public void reroll(int index) {
        if (!canRoll() || !cardsApart() || index < 0 || index >= cards.size()) {
            return;
        }
        MekSummary unit = null;
        if (dealer == null) {
            List<MekSummary> rolled = roller.roll(1, table);
            unit = rolled.isEmpty() ? null : rolled.get(0);
        } else {
            List<MekSummary> rest = army();
            rest.remove(index);
            unit = dealer.replace(cards.get(index).unit, rest);
        }
        if (unit != null) {
            cards.get(index).unit = unit;
        }
    }

    /** Puts {@code unit} in the hand as a card of its own, as a table's row is taken; false when the hand is full. */
    public boolean take(MekSummary unit) {
        if (cards.size() >= MAX_UNITS) {
            return false;
        }
        cards.add(new Card(Objects.requireNonNull(unit)));
        noteContext();
        return true;
    }

    /** What the hand was last dealt for by a generator that knows, or null; cleared with the hand. */
    public @Nullable GenerationContext context() {
        return dealtFor;
    }

    /** Notes what the dealer dealt for; a table's roll leaves a generator's note as it is, as Swing's does. */
    private void noteContext() {
        GenerationContext context = dealer == null ? null : dealer.context();
        if (context != null) {
            dealtFor = context;
        }
    }

    public void keep(int index, boolean kept) {
        if (cardsApart() && index >= 0 && index < cards.size()) {
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
        dealtFor = null;
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
