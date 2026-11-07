/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ratgenerator.GenerationContext;
import megamek.common.loaders.MekSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RandomArmyFormTest {
    private final List<String> rolledFrom = new ArrayList<>();
    private int serial;
    private RandomArmyForm form;

    private List<MekSummary> roll(int count, String table) {
        List<MekSummary> units = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rolledFrom.add(table);
            MekSummary unit = new MekSummary();
            serial++;
            unit.setName("Unit " + serial);
            unit.setBV(100 * serial);
            unit.setTons(5 * serial);
            units.add(unit);
        }
        return units;
    }

    @BeforeEach
    void setUp() {
        form = new RandomArmyForm(List.of("A", "B"), "B", List.of("Me", "Ally"), this::roll);
    }

    @Test
    void startsOnTheInitialTableOrTheFirst() {
        assertEquals("B", form.table());
        assertEquals("A", new RandomArmyForm(List.of("A"), "X", List.of(), this::roll).table());
        RandomArmyForm empty = new RandomArmyForm(List.of(), null, List.of(), this::roll);
        assertNull(empty.table());
        assertFalse(empty.canRoll());
        empty.roll();
        assertTrue(empty.cards().isEmpty());
        assertNull(empty.playerName());
    }

    @Test
    void rollDealsTheCountFromTheTable() {
        form.count(3);
        form.roll();
        assertEquals(3, form.cards().size());
        assertEquals(List.of("B", "B", "B"), rolledFrom);
        assertEquals(600, form.battleValue());
        assertEquals(30, form.tonnage());
    }

    @Test
    void rollKeepsKeptCardsAndRerollsTheRest() {
        form.count(3);
        form.roll();
        MekSummary kept = form.cards().get(1).unit();
        form.keep(1, true);
        form.roll();
        assertEquals(3, form.cards().size());
        assertSame(kept, form.cards().get(0).unit());
        assertTrue(form.cards().get(0).kept());
        assertEquals(5, rolledFrom.size());
    }

    @Test
    void rerollDiscardAndClearWorkOnOneCard() {
        form.count(2);
        form.roll();
        MekSummary first = form.cards().get(0).unit();
        form.reroll(0);
        assertFalse(first == form.cards().get(0).unit());
        form.discard(1);
        assertEquals(1, form.army().size());
        form.discard(5);
        assertEquals(1, form.army().size());
        form.clear();
        assertTrue(form.army().isEmpty());
    }

    @Test
    void countAndPlayerStayInRange() {
        form.count(0);
        assertEquals(1, form.count());
        form.count(100);
        assertEquals(RandomArmyForm.MAX_UNITS, form.count());
        form.player(1);
        assertEquals("Ally", form.playerName());
        form.player(4);
        assertEquals(1, form.player());
        form.table("Z");
        assertEquals("B", form.table());
    }

    /** A dealer of numbered units that notes what it is asked for. */
    private final class Stub implements RandomArmyForm.Dealer {
        boolean whole;
        boolean counts = true;
        boolean deals = true;
        int dealt = 3;
        GenerationContext context;
        final List<Integer> asked = new ArrayList<>();

        @Override
        public boolean canDeal() {
            return deals;
        }

        @Override
        public List<MekSummary> deal(int count, List<MekSummary> kept) {
            asked.add(count);
            return roll(counts ? count : dealt, "dealer");
        }

        @Override
        public MekSummary replace(MekSummary unit, List<MekSummary> rest) {
            return roll(1, "dealer").get(0);
        }

        @Override
        public boolean wholeHand() {
            return whole;
        }

        @Override
        public boolean usesCount() {
            return counts;
        }

        @Override
        public GenerationContext context() {
            return context;
        }
    }

    @Test
    void aDealerDealsInsteadOfTheTable() {
        Stub dealer = new Stub();
        form.dealer(dealer);
        form.count(2);
        form.roll();
        assertEquals(2, form.cards().size());
        assertTrue(rolledFrom.stream().allMatch("dealer"::equals), "the table is not rolled on");
        form.keep(0, true);
        form.roll();
        assertEquals(List.of(2, 1), dealer.asked, "only the cards not kept are dealt again");
        dealer.deals = false;
        assertFalse(form.canRoll(), "Roll follows the dealer");
        form.dealer(null);
        assertTrue(form.canRoll(), "back on the table");
    }

    @Test
    void aDealerWithCountsOfItsOwnDealsItsWholeArmy() {
        Stub dealer = new Stub();
        dealer.counts = false;
        form.dealer(dealer);
        form.count(1);
        form.roll();
        assertEquals(3, form.cards().size(), "the dealer's counts, not the form's");
    }

    @Test
    void aWholeHandIsDealtAnewAndHeldTogether() {
        Stub dealer = new Stub();
        dealer.whole = true;
        form.dealer(dealer);
        form.count(3);
        form.roll();
        MekSummary first = form.cards().get(0).unit();
        assertFalse(form.cardsApart());
        form.keep(0, true);
        assertFalse(form.cards().get(0).kept(), "no card is kept alone");
        form.reroll(0);
        assertSame(first, form.cards().get(0).unit(), "no card is rerolled alone");
        form.roll();
        assertEquals(3, form.cards().size());
        assertFalse(first == form.cards().get(0).unit(), "Roll deals every card anew");
    }

    @Test
    void theContextIsWhatTheHandsGeneratorCardsWereDealtFor() {
        Stub dealer = new Stub();
        dealer.context = new GenerationContext("FS", 3050, "A", GenerationContext.Source.RAT_GENERATOR);
        form.dealer(dealer);
        form.count(2);
        form.roll();
        assertEquals(dealer.context, form.context());
        form.keep(0, true);
        form.dealer(null);
        form.roll();
        assertEquals(dealer.context, form.context(), "a kept generator card keeps its note");
        form.keep(0, false);
        form.roll();
        assertNull(form.context(), "replacing every generator card drops its note");
        form.dealer(dealer);
        form.roll();
        form.discard(1);
        form.discard(0);
        assertNull(form.context(), "discarding every generator card drops its note");
        MekSummary unit = new MekSummary();
        assertTrue(form.take(unit));
        assertSame(unit, form.cards().get(form.cards().size() - 1).unit());
        assertEquals(dealer.context, form.context(), "a taken unit notes the dealer's context");
        form.clear();
        assertNull(form.context(), "cleared with the hand");
    }

    @Test
    void theNewestGeneratorNoteWins() {
        GenerationContext first = new GenerationContext("FS", 3050, "A", GenerationContext.Source.RAT_GENERATOR);
        GenerationContext later = new GenerationContext("DC", 3050, "B", GenerationContext.Source.RAT_GENERATOR);
        Stub dealer = new Stub();
        dealer.context = first;
        form.dealer(dealer);
        form.count(2);
        form.roll();
        form.keep(0, true);
        dealer.context = later;
        form.roll();
        assertEquals(later, form.context());
        dealer.context = null;
        form.reroll(1);
        assertEquals(first, form.context(), "back to the kept card's note once the later card is replaced");
    }

    @Test
    void aGeneratorDealsEverythingItWasAskedFor() {
        Stub dealer = new Stub();
        dealer.counts = false;
        dealer.dealt = RandomArmyForm.MAX_UNITS + 6;
        form.dealer(dealer);
        form.roll();
        assertEquals(RandomArmyForm.MAX_UNITS + 6, form.cards().size(), "no dealt unit is silently dropped");
    }
}
