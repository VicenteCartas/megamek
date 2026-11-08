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

    /** The most units a table roll deals or the hand takes one by one; a generator deals all it was asked for. */
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

        /** Whether its last deal stopped short of what it was asked for, at a limit of its own. */
        default boolean cutShort() {
            return false;
        }
    }

    /** One dealt unit. */
    public static final class Card {
        private MekSummary unit;
        private boolean kept;
        private @Nullable GenerationContext dealtFor;
        private long dealt;

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
    private long dealings;
    private boolean cutShort;

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
            List<MekSummary> units = dealer.deal(count, List.of());
            deal(units);
            return;
        }
        cards.removeIf(card -> !card.kept);
        int missing = count - cards.size();
        if (dealer != null && !dealer.usesCount()) {
            List<MekSummary> units = dealer.deal(Math.max(0, missing), army());
            deal(units);
        } else if (missing > 0) {
            List<MekSummary> units = dealer == null ? roller.roll(missing, table) : dealer.deal(missing, army());
            deal(units);
        }
    }

    /**
     * Adds every unit dealt: a generator deals what it was asked for, which may be more than the count a table rolls,
     * as Swing's do; none is silently left out.
     */
    private void deal(List<MekSummary> units) {
        cutShort = dealer != null && dealer.cutShort();
        GenerationContext context = dealerContext();
        for (MekSummary unit : units) {
            Card card = new Card(unit);
            note(card, context);
            cards.add(card);
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
            Card card = cards.get(index);
            card.unit = unit;
            note(card, dealerContext());
        }
    }

    /** Puts {@code unit} in the hand as a card of its own, as a table's row is taken; false when the hand is full. */
    public boolean take(MekSummary unit) {
        if (cards.size() >= MAX_UNITS) {
            return false;
        }
        Card card = new Card(Objects.requireNonNull(unit));
        note(card, dealerContext());
        cards.add(card);
        return true;
    }

    /**
     * What the hand's newest generator-dealt card was dealt for, or null when no card in the hand came from a
     * generator that knows; a context leaves with the last of its cards, so it never names units it did not deal.
     */
    public @Nullable GenerationContext context() {
        Card newest = null;
        for (Card card : cards) {
            if (card.dealtFor != null && (newest == null || card.dealt > newest.dealt)) {
                newest = card;
            }
        }
        return newest == null ? null : newest.dealtFor;
    }

    private @Nullable GenerationContext dealerContext() {
        return dealer == null ? null : dealer.context();
    }

    private void note(Card card, @Nullable GenerationContext context) {
        card.dealtFor = context;
        card.dealt = ++dealings;
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
        cutShort = false;
    }

    /** Whether the hand's last deal stopped short of what was asked for, at a limit of the generator's. */
    public boolean cutShort() {
        return cutShort && !cards.isEmpty();
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
