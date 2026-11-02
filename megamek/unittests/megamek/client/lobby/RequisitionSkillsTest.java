/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import megamek.client.lobby.RequisitionCatalog.YearSpan;
import megamek.client.lobby.RequisitionSkills.Mode;
import megamek.common.eras.Era;
import megamek.common.force.Force;
import org.junit.jupiter.api.Test;

class RequisitionSkillsTest {

    @Test
    void skillsStayInTheStripsRange() {
        RequisitionSkills skills = new RequisitionSkills(Mode.FIXED, -3, 12);
        assertEquals(RequisitionSkills.MIN_SKILL, skills.gunnery());
        assertEquals(RequisitionSkills.MAX_SKILL, skills.piloting());
        assertEquals(RequisitionSkills.MAX_SKILL, skills.withGunnery(9).gunnery());
    }

    @Test
    void theStripOpensWithTheSkillsTheClientPrefers() {
        assertEquals(Mode.RANDOM, RequisitionSkills.initial(true).mode());
        assertEquals(new RequisitionSkills(Mode.DEFAULT, 4, 5), RequisitionSkills.initial(false));
    }

    @Test
    void onlySetSkillsChangeTheBattleValue() {
        RequisitionSkills regular = new RequisitionSkills(Mode.FIXED, 4, 5);
        assertEquals(1000, regular.bv(1000));
        assertTrue(regular.withGunnery(2).bv(1000) > 1000);
        assertTrue(regular.withPiloting(7).bv(1000) < 1000);
        assertEquals(1000, regular.withGunnery(2).withMode(Mode.RANDOM).bv(1000));
        assertEquals(1000, regular.withGunnery(2).withMode(Mode.DEFAULT).bv(1000));
    }

    // ---- RequisitionVisit ----

    @Test
    void aVisitCountsTheNewUnitsOfThePlayersItAddedFor() {
        RequisitionVisit visit = new RequisitionVisit(List.of(1));
        visit.sentFor(10);
        LobbyForces forces = forces(unit(1, 10, 50, 1000), unit(2, 10, 70, 1500), unit(3, 20, 20, 400),
              unit(4, 10, 25.5, 600));

        assertEquals(List.of(2, 4), visit.added(forces).stream().map(LobbyUnit::id).toList());
        assertEquals(new RequisitionVisit.Totals(2, 2100, 95.5), RequisitionVisit.totals(visit.added(forces)));

        visit.forget(2);
        assertEquals(List.of(4), visit.added(forces).stream().map(LobbyUnit::id).toList());
        visit.sentFor(20);
        assertEquals(List.of(3, 4), visit.added(forces).stream().map(LobbyUnit::id).toList());
    }

    @Test
    void aVisitBeginsWithTheLobbysUnits() {
        RequisitionVisit visit = RequisitionVisit.of(forces(unit(1, 10, 50, 1000)));
        visit.sentFor(10);
        assertTrue(visit.added(forces(unit(1, 10, 50, 1000))).isEmpty());
        assertEquals(RequisitionVisit.Totals.NONE, RequisitionVisit.totals(List.of()));
    }

    // ---- LobbyRequisition ----

    @Test
    void erasBecomeYearSpansThatMeet() {
        List<YearSpan> spans = LobbyRequisition.spans(List.of(era("Star League", 2780), era("Succession Wars", 3049),
              era("ilClan", null), era("Never", 4000)));
        assertEquals(List.of(new YearSpan("Star League", 0, 2780), new YearSpan("Succession Wars", 2781, 3049),
              new YearSpan("ilClan", 3050, Integer.MAX_VALUE)), spans);
        assertTrue(spans.get(2).contains(3150));
    }

    @Test
    void aRecipientIsFoundByItsId() {
        LobbyRequisition requisition = new LobbyRequisition(RequisitionCatalog.Rules.OPEN,
              List.of(new LobbyRequisition.Recipient(1, "Me", true, false, ""),
                    new LobbyRequisition.Recipient(2, "Bot", false, true, "")), false, List.of());
        assertEquals("Bot", requisition.recipient(2).name());
        assertNull(requisition.recipient(3));
        assertTrue(LobbyRequisition.EMPTY.recipients().isEmpty());
    }

    private static Era era(String name, Integer endYear) {
        Era era = mock(Era.class);
        when(era.name()).thenReturn(name);
        when(era.end()).thenReturn((endYear == null) ? null : LocalDate.of(endYear, 12, 31));
        return era;
    }

    static LobbyUnit unit(int id, int owner, double tons, int bv) {
        return new LobbyUnit(id, owner, "Unit " + id, "", "", tons, bv, "", "", "", List.of(), 0,
              LobbyUnit.Link.NONE, false, null, Force.NO_FORCE, true, false, null);
    }

    static LobbyForces forces(LobbyUnit... units) {
        return new LobbyForces(List.of(units), List.of(), List.of(), List.of(), List.of(), -1);
    }
}
