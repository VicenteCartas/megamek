/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.ResolvedAttack;
import megamek.common.actions.EnemyArtilleryInbound;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.loaders.MekFileParser;
import megamek.common.rolls.PilotingRollData;
import megamek.common.rolls.Roll;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Crew;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.common.weapons.Weapon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GpuReportLogTest {
    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void informationLinksRetainTheirExactTextAndExplanationsAfterHtmlCleanup() {
        var text = GpuReportLog.linkedText("<b>Laser</b> at <a href='#entity:42'>Atlas &amp; Co</a> needs "
              + "<a href=\"#tooltip:4 (pilot's skill)<br>+ 3 (target movement)\">7</a>, rolls "
              + "<a href='#tooltip:Dice: &lt;4 + 5&gt;'>9</a> : hits.<br>"
              + "<a href='#entity:99'>????</a>");
        assertEquals("Laser at Atlas & Co needs 7, rolls 9 : hits.\n????", text.text());
        assertEquals(3, text.links().size(), "Obscured identities must not become active links");
        var unit = text.links().getFirst();
        assertEquals(42, unit.unitId());
        assertEquals("Atlas & Co", text.text().substring(unit.start(), unit.end()));
        var need = text.links().get(1);
        assertEquals("7", text.text().substring(need.start(), need.end()));
        assertEquals("4 (pilot's skill)\n+ 3 (target movement)", need.detail());
        assertEquals("Dice: <4 + 5>", text.links().getLast().detail());
        var heat = GpuReportLog.linkedText(new Report(3150)
              .addDataWithTooltip("12", "Weapon's heat: 10<br>Movement: 2").text());
        assertEquals("Weapon's heat: 10\nMovement: 2", heat.links().getFirst().detail(),
              "Existing reports put unescaped apostrophes and HTML inside single-quoted tooltip attributes");
    }

    @Test
    void thumbnailsAreCapturedOnlyForDisclosedUnitsAndRetainedWithTheirHistory() {
        var log = new GpuReportLog();
        var atlas = unit(42, "Atlas");
        var pixels = new BoardScene.Pixels(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        var requests = new AtomicInteger();
        List<List<Report>> history = new ArrayList<>(List.of(new ArrayList<>(List.of(
              new Report(3000), new Report(6065).addDesc(atlas).add(10).add("Left Torso")))));
        var first = log.capture(history, 1, GamePhase.FIRING_REPORT, List.of(), id -> {
            assertEquals(42, id);
            requests.incrementAndGet();
            return pixels;
        });
        assertSame(pixels, first.icons().get(42));
        var next = log.capture(history, 1, GamePhase.PHYSICAL, List.of(), id -> {
            requests.incrementAndGet();
            return null;
        });
        assertSame(pixels, next.icons().get(42));
        assertEquals(1, requests.get());
        history.clear();
        assertTrue(log.capture(history, 0, GamePhase.LOUNGE).icons().isEmpty());
    }

    @Test
    void receivedReportsKeepShooterTargetRollsAndDamageTogetherWithoutMixingTargets() throws Exception {
        Entity shooter = unit(7, "Timber Wolf Prime");
        Entity target = unit(42, "Atlas AS7-D");
        Entity other = unit(43, "Locust LCT-1V");
        List<Report> reports = new ArrayList<>(List.of(new Report(3000), new Report(3100).addDesc(shooter)));
        reports.addAll(attack(target, "ER Large Laser", true));
        reports.addAll(attack(other, "LRM 20", false));
        // Exercise the actual wire boundary: subject is transient and must not be used as a client-side identity.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(reports);
        }
        List<Report> received;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked") List<Report> restored = (List<Report>) in.readObject();
            received = restored;
        }
        var entries = GpuReportLog.format(3, received).entries();
        assertEquals(2, entries.size());
        var hit = entries.getFirst();
        assertTrue(hit.matches(3, GamePhase.FIRING.localizedName(), 42, "laser"));
        assertTrue(hit.matches(0, "", 7, ""));
        assertFalse(hit.matches(3, "", 43, ""));
        assertFalse(entries.getLast().matches(0, "", 42, ""));
        assertTrue(hit.text().contains("needs 7, rolls 9 : hits"), hit.text());
        assertTrue(hit.text().contains("takes 10 damage to Left Torso"));
        assertTrue(hit.rolls().contains("gunnery"));
        assertTrue(hit.rolls().contains("target movement"));
        assertTrue(hit.matches(0, "", -1, "target movement"));
        assertFalse(hit.text().contains("<"));
        // The same wire copies classify: weapon attacks by the shooter at each target, not critical.
        assertEquals(List.of(GpuReportLog.Kind.WEAPON, GpuReportLog.Kind.WEAPON),
              entries.stream().map(GpuReportLog.Entry::kind).toList());
        assertEquals(List.of(7, 7), entries.stream().map(GpuReportLog.Entry::attackerId).toList());
        assertEquals(List.of(42, 43), entries.stream().map(GpuReportLog.Entry::targetId).toList());
        assertTrue(hit.text().startsWith("ER Large Laser at Atlas AS7-D"), hit.text());
        assertFalse(hit.critical());
    }

    @Test
    void phaseBoundariesResetTheAttackerAndRepeatedEventsAreRetained() {
        Entity shooter = unit(7, "Timber Wolf Prime");
        Entity target = unit(42, "Atlas AS7-D");
        List<Report> reports = new ArrayList<>(List.of(new Report(3000), new Report(3100).addDesc(shooter)));
        reports.addAll(attack(target, "ER Large Laser", false));
        reports.addAll(attack(target, "ER Large Laser", false));
        reports.add(new Report(5000));
        reports.add(new Report(6065).addDesc(target).add(5).add("Right Arm"));
        var entries = GpuReportLog.format(2, reports).entries();
        assertEquals(3, entries.size());
        assertEquals(entries.get(0).text(), entries.get(1).text());
        assertEquals("", entries.getLast().heading());
        assertEquals(List.of(42), entries.getLast().units().stream().map(GpuReportLog.Unit::id).toList());
        assertFalse(entries.getLast().phase().equals(entries.getFirst().phase()));
    }

    @Test
    void plainPhysicalTargetsUseDisclosedNamesWithoutConfusingDuplicateChassis() {
        Entity shooter = unit(7, "Timber Wolf Prime");
        Entity first = unit(42, "Atlas AS7-D");
        Entity second = unit(43, "Atlas AS7-D #2");
        Report punch = new Report(4010).add("Left arm").add("Atlas AS7-D #2 (OpFor)");
        punch.newlines = 0;
        var entries = GpuReportLog.format(1, List.of(new Report(4000), new Report(4005).addDesc(shooter), punch,
              new Report(4035), new Report(5000), new Report(6065).addDesc(first).add(5).add("Right Arm"),
              new Report(6065).addDesc(second).add(5).add("Right Arm"))).entries();
        assertTrue(entries.getFirst().matches(0, "", 43, ""));
        assertFalse(entries.getFirst().matches(0, "", 42, ""));
        assertEquals(GpuReportLog.Kind.PHYSICAL, entries.getFirst().kind());
        assertEquals(7, entries.getFirst().attackerId());
        assertEquals(43, entries.getFirst().targetId());
    }

    @Test
    void obscuredReportsDoNotInventIdentitiesFromTransientSubjectsOrSpriteMarkers() {
        Report hidden = new Report(3115).add("Laser").add("????").add("????");
        hidden.subject = 123;
        var entry = GpuReportLog.format(1, List.of(new Report(3000), hidden)).entries().getFirst();
        assertTrue(entry.units().isEmpty());
        assertTrue(entry.text().contains("????"));
        assertFalse(entry.matches(0, "", 123, ""));
        assertEquals(Entity.NONE, entry.targetId());
    }

    @Test
    void cacheRefreshesAppendedAndReplacedHistoryAndRetiresProvisionalReports() {
        GpuReportLog log = new GpuReportLog();
        List<Report> round = new ArrayList<>(List.of(new Report(2000), new Report(2165).add("Locust")));
        List<List<Report>> history = new ArrayList<>(List.of(round));
        var first = log.capture(history, 1, GamePhase.MOVEMENT);
        assertSame(first, log.capture(history, 1, GamePhase.MOVEMENT));
        log.live(new Report(2165).add("Atlas").text(), 1, GamePhase.MOVEMENT);
        var live = log.capture(history, 1, GamePhase.MOVEMENT);
        assertEquals(2, live.entries().size());
        log.live(new Report(2165).add("Atlas").text(), 1, GamePhase.MOVEMENT);
        assertSame(live, log.capture(history, 1, GamePhase.MOVEMENT));
        round.add(new Report(2165).add("Atlas"));
        var finalReport = log.capture(history, 1, GamePhase.MOVEMENT_REPORT);
        assertTrue(finalReport.entries().stream().noneMatch(entry -> entry.heading().equals("Live resolution")));
        assertTrue(finalReport.entries().stream().anyMatch(entry -> entry.text().contains("Atlas")));
        history.set(0, new ArrayList<>(List.of(new Report(3000), new Report(3220))));
        var replacement = log.capture(history, 1, GamePhase.FIRING_REPORT);
        assertNotSame(finalReport, replacement);
        assertFalse(replacement.entries().stream().anyMatch(entry -> entry.text().contains("Atlas")));
        history.clear();
        assertTrue(log.capture(history, 0, GamePhase.LOUNGE).entries().isEmpty());
    }

    /** The PSR lines exactly as TWGameManager writes them (doSkillCheckWhileMoving, resolvePilotingRolls). */
    @Test
    void pilotingRollsBecomeItemsWithTheirUnitTargetNumberRollResultAndReasons() {
        Entity atlas = unit(42, "Atlas AS7-D");
        Entity locust = unit(43, "Locust LCT-1V");
        Entity hidden = unit(44, "Commando COM-2D");
        PilotingRollData rubble = new PilotingRollData(43, 5, "Base piloting skill");
        rubble.addModifier(0, "entering Rubble");
        Report moving = new Report(2195).addDesc(locust);
        moving.add("0505", true);
        moving.add(rubble.getLastPlainDesc(), true);
        Report rubbleRoll = new Report(2185).add(rubble.getValueAsString()).add(rubble.getDesc()).add(dice(4))
              .choose(false);
        List<Report> reports = new ArrayList<>(List.of(new Report(2000), moving, rubbleRoll,
              new Report(2310).addDesc(locust).add("front").add(3), new Report(3000), new Report(3900)));
        reports.addAll(psrBlock(atlas, "took 20+ damage; was kicked", new int[] {5, 7}, new int[] {6, 8}));
        // Double blind: a unit the player has not seen, with its name, reasons and roll number hidden.
        List<Report> ghost = psrBlock(hidden, "reactor shutdown", new int[] {4, 2});
        ghost.forEach(GpuReportLogTest::unseen);
        reports.addAll(ghost);
        reports.add(new Report(2275).addDesc(locust).add(1).add("leg destroyed"));

        var round = GpuReportLog.format(3, reports);

        assertEquals(List.of(
                    "43 MOVEMENT 5 4 false entering Rubble",
                    "42 FIRING 5 7 true took 20+ damage; was kicked",
                    "42 FIRING 6 8 true took 20+ damage; was kicked",
                    "43 FIRING " + TargetRoll.AUTOMATIC_FAIL + " 0 false leg destroyed"),
              round.psr().stream().map(item -> item.entityId() + " " + item.phase() + " " + item.targetNumber() + " "
                    + item.roll() + " " + item.passed() + " " + item.reasons()).toList(),
              "The undisclosed unit's roll gives no item");
        var entries = round.entries();
        assertEquals(List.of("PSR true [43]", "PSR false [42]", "PSR true []", "PSR true [43]"),
              entries.stream().map(entry -> entry.kind() + " " + entry.critical() + " "
                    + entry.units().stream().map(GpuReportLog.Unit::id).toList()).toList(),
              "The hidden unit's failed block is an entry of its own and never makes the Atlas critical");
        long base = 3L << 32;
        assertEquals(List.of(List.of(base | 2), List.of(base | 9, base | 11), List.of(), List.of(base | 16)),
              entries.stream().map(GpuReportLog.Entry::psr).toList(), "Each entry lists its item ids");
        assertEquals(List.of(base | 2, base | 9, base | 11, base | 16),
              round.psr().stream().map(GpuReportLog.PsrItem::id).toList());
        // capture() formats a round again when its reports grow; the items shown before keep their ids.
        reports.addAll(psrBlock(atlas, "was kicked", new int[] {5, 6}));
        assertEquals(List.of(base | 2, base | 9, base | 11, base | 16, base | 20),
              GpuReportLog.format(3, reports).psr().stream().map(GpuReportLog.PsrItem::id).toList());

        // TWGameManager.doTryUnstuck: the break-free line names the unit, the roll follows.
        Report unstuck = new Report(2190).add("6").add("Base piloting skill").add(dice(7)).choose(true);
        var freed = GpuReportLog.format(3, List.of(new Report(2340).addDesc(locust), unstuck));
        assertEquals("PSR false [" + (base | 1) + "]", freed.entries().getFirst().kind() + " "
              + freed.entries().getFirst().critical() + " " + freed.entries().getFirst().psr());
        assertEquals(List.of("43 6 7 true"), freed.psr().stream().map(item -> item.entityId() + " "
              + item.targetNumber() + " " + item.roll() + " " + item.passed()).toList());

        // Mek.doCheckEngineStallRoll after the failed PSR of an ICE-engine Mek: a piloting roll that stalls the engine.
        PilotingRollData skill = new PilotingRollData(43, 5, "Base piloting skill");
        Report stallNumber = new Report(2290).add(1).add(skill.getPlainDesc());
        stallNumber.indent();
        stallNumber.newlines = 0;
        Report stallRoll = new Report(2300).add(skill).add(dice(4)).choose(false);
        stallRoll.newlines = 0;
        var stall = GpuReportLog.format(3, List.of(
              new Report(2280).addDesc(locust).add(1).add("ICE-Engine Mek failed a PSR"),
              new Report(2285).add(skill.getValueAsString()).add(skill.getDesc()), stallNumber, stallRoll,
              new Report(2303)));
        assertEquals("PSR true [" + (base | 3) + "]", stall.entries().getFirst().kind() + " "
              + stall.entries().getFirst().critical() + " " + stall.entries().getFirst().psr());
        assertEquals(List.of("43 5 4 false ICE-Engine Mek failed a PSR"), stall.psr().stream().map(item ->
              item.entityId() + " " + item.targetNumber() + " " + item.roll() + " " + item.passed() + " "
                    + item.reasons()).toList());
    }

    @Test
    void criticalHitsBecomeItemsOfTheUnitTheyHitWithTheirLocationAndWhatTheyHit() {
        Entity shooter = unit(7, "Timber Wolf Prime");
        Entity atlas = unit(42, "Atlas AS7-D");
        Entity tank = unit(43, "Demolisher Heavy Tank");
        Entity hidden = unit(44, "Commando COM-2D");
        List<Report> reports = new ArrayList<>(List.of(new Report(3000), new Report(3100).addDesc(shooter)));
        // TWGameManager.criticalEntity after the damage line: the check, its roll, the slot.
        reports.addAll(withCriticals(attack(atlas, "ER Large Laser", true), check("LT"), roll(9),
              new Report(6315), new Report(6225).add("Heat Sink")));
        // criticalTank: the check and roll, a result of its own line, and a weapon the result destroyed (no roll).
        reports.addAll(withCriticals(attack(tank, "Gauss Rifle", true), check("FR"), roll(11), new Report(6600),
              new Report(6305).add("Medium Laser")));
        // Double blind: the damage line does not name the unit, so its critical hit belongs to no one.
        List<Report> unseen = withCriticals(attack(hidden, "LRM 20", true), check("RT"), roll(8),
              new Report(6315), new Report(6225).add("Engine"));
        unseen.forEach(GpuReportLogTest::unseen);
        reports.addAll(unseen);

        var round = GpuReportLog.format(3, reports);

        assertEquals(List.of("42 FIRING LT Heat Sink", "43 FIRING FR Driver injured!", "43 FIRING FR Medium Laser"),
              round.crits().stream().map(crit -> crit.entityId() + " " + crit.phase() + " " + crit.location() + " "
                    + crit.text()).toList());
        assertEquals(round.crits().stream().map(GpuReportLog.CritItem::id).distinct().count(), round.crits().size());
    }

    /** The attack's damage line continues with the critical hit lines, which end the attack's entry. */
    private static List<Report> withCriticals(List<Report> attack, Report... criticals) {
        List<Report> reports = new ArrayList<>(attack);
        reports.getLast().newlines = 1;
        reports.addAll(List.of(criticals));
        reports.getLast().newlines = 2;
        return reports;
    }

    private static Report check(String location) {
        Report check = new Report(6305).add(location);
        check.newlines = 0;
        return check;
    }

    private static Report roll(int roll) {
        Report report = new Report(6310).add(Integer.toString(roll));
        report.newlines = 0;
        return report;
    }

    @Test
    void heatAlertsAreFailedHeatChecksAndShutdownRangeLinesAndWarningsAreCritical() {
        Entity cool = unit(41, "Locust LCT-1V");
        Entity warm = unit(42, "Atlas AS7-D");
        Entity hot = unit(43, "Awesome AWS-8Q");
        Entity restarted = unit(44, "Commando COM-2D");
        Entity avoided = unit(45, "Griffin GRF-1N");
        Entity overheated = unit(46, "Stalker STK-3F");
        Entity hidden = unit(47, "Wolverine WVR-6R");
        Entity fighter = unit(48, "Shilone SL-17");
        Entity stalled = unit(49, "Hunchback HBK-4G");
        // Shut down without an active pilot, so no shutdown-range line: only the ammunition check can alert (5065).
        Entity exploded = unit(50, "Marauder MAD-3R");
        Entity spared = unit(51, "Warhammer WHM-6R");
        Report startUp = new Report(5050).addDesc(restarted).add(4).add(dice(9)).choose(true);
        // HeatResolver: a unit still shut down at heat 18 needs 6+ to start up.
        Report failedStartUp = new Report(5050).addDesc(stalled).add(6).add(dice(5)).choose(false);
        Report critical = new Report(6225).add("Left Torso");
        List<Report> reports = new ArrayList<>(List.of(new Report(1000), new Report(1015).add("Team 1").add(9),
              new Report(2000), new Report(2100).addDesc(cool).add(1), new Report(3000),
              new Report(3100).addDesc(warm)));
        reports.addAll(attack(cool, "Medium Laser", true));
        reports.add(reports.size() - 1, critical);
        // Double blind: a unit the player has not seen, with all its obscured data hidden.
        reports.addAll(List.of(new Report(5000), heat(cool, 4), unseen(heat(hidden, 15)),
              unseen(shutdownCheck(hidden, 2)), heat(warm, 5), heat(hot, 15), shutdownCheck(hot, 3), startUp,
              heat(stalled, 18), failedStartUp, heat(avoided, 14), shutdownCheck(avoided, 8), heat(overheated, 30),
              new Report(5055).addDesc(overheated), heat(exploded, 20), new Report(5049).addDesc(exploded),
              ammoCheck(exploded, 3), heat(spared, 19), new Report(5049).addDesc(spared), ammoCheck(spared, 9),
              new Report(5001), new Report(9310).addDesc(fighter).add(1).add("stall")));

        var entries = GpuReportLog.format(1, reports).entries();

        assertEquals(List.of("INITIATIVE false false", "MOVE false false", "WEAPON true false", "HEAT false false",
                    "HEAT true true", "HEAT false false", "HEAT true true", "HEAT false false", "HEAT true true",
                    "HEAT true true", "HEAT true true", "HEAT true true", "HEAT false false", "OTHER false false"),
              entries.stream().map(entry -> entry.kind() + " " + entry.critical() + " " + entry.heatAlert()).toList(),
              "A heat alert is a failed heat check (the ammunition explosion check included) or a shutdown-range line "
                    + "(the mock's heat 14+), never heat alone, and it is critical; the aerospace control rolls end "
                    + "the heat section");
        assertEquals(List.of(List.of(), List.of(41), List.of(42, 41), List.of(41), List.of(), List.of(42), List.of(43),
                    List.of(44), List.of(49), List.of(45), List.of(46), List.of(50), List.of(51), List.of(48)),
              entries.stream().map(entry -> entry.units().stream().map(GpuReportLog.Unit::id).toList()).toList(),
              "The hidden unit's heat lines never join the Locust's entry");
        assertEquals(List.of("null null null null null", "null null null null null", "null null null 7 9",
                    "4 6 2 null null", "null null null 4 2", "5 7 2 null null", "15 17 2 4 3", "null null null 4 9",
                    "18 20 2 6 5", "14 16 2 4 8", "30 32 2 null null", "20 22 2 4 3", "19 21 2 4 9",
                    "null null null null null"),
              entries.stream().map(entry -> entry.heat() + " " + entry.heatGained() + " " + entry.heatSunk() + " "
                    + entry.targetNumber() + " " + entry.roll()).toList(),
              "Heat, heat gained and sunk, and the first check's target number and roll; the unseen unit's heat is "
                    + "hidden");
        assertEquals("Control Rolls", entries.getLast().phase());
    }

    /** A round of the real Atlas AS7-D (IS): a pointblank shot, two Medium Lasers and the LRM 20, then a punch. */
    @Test
    void linksMatchAScriptedVolleyInOrderWhicheverArrivesFirst() throws Exception {
        Entity atlas = atlas();
        Entity locust = unit(43, "Locust LCT-1V");
        Entity commando = unit(44, "Commando COM-2D");
        Entity griffin = unit(45, "Griffin GRF-1N");
        Entity infantry = unit(46, "Foot Platoon");
        Entity union = unit(47, "Union");
        Targetable building = mock(Targetable.class);
        when(building.getId()).thenReturn(1_000_605);
        when(building.getTargetType()).thenReturn(Targetable.TYPE_BUILDING);
        // TacOps manual AMS: the Commando's AMS counters missiles on its own, the Griffin's is used as a weapon.
        mount(commando, 2, "ISAntiMissileSystem", true);
        mount(griffin, 3, "ISAntiMissileSystem", false);
        // A point defense bay's member weapon: the "Point Defense" mode is the bay's, so it does not fire on its own.
        mount(union, 5, "ISSmallLaser", false);
        when(griffin.getLocationAbbr(Mek.LOC_CENTER_TORSO)).thenReturn("CT");
        Map<Integer, Entity> units = Map.of(7, atlas, 43, locust, 44, commando, 45, griffin, 47, union);
        Map<Integer, Targetable> targets = new HashMap<>(units);
        targets.put(building.getId(), building);
        List<List<Report>> history = new ArrayList<>(List.of(new ArrayList<>(List.of(new Report(2000))),
              volley(atlas, locust, commando, griffin, infantry)));
        var leftLaser = shot(atlas, locust, weapon(atlas, "Medium Laser", Mek.LOC_LEFT_ARM), true,
              List.of(new ResolvedAttack.Impact("LT", false, 5)), null);
        var rightLaser = shot(atlas, locust, weapon(atlas, "Medium Laser", Mek.LOC_RIGHT_ARM), false, List.of(), null);
        var lrm = shot(atlas, commando, weapon(atlas, "LRM 20", Mek.LOC_LEFT_TORSO), true,
              List.of(new ResolvedAttack.Impact("CT", false, 5), new ResolvedAttack.Impact("RA", false, 5),
                    new ResolvedAttack.Impact("LL", false, 2)),
              new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, false, 12));
        var punch = attack(ResolvedAttack.Kind.PUNCH, atlas, locust, -1, Mek.LOC_LEFT_ARM, true,
              List.of(new ResolvedAttack.Impact("RA", false, 3)), null);
        // WeaponHandler.reportCounterAnimation: the Commando's AMS engages the LRM, as a defensive hit on the Atlas.
        var counter = attack(ResolvedAttack.Kind.SHOT, commando, atlas, 2, -1, true, List.of(),
              new ResolvedAttack.Shot("On", Set.of(), false, true, 1, 0, false));
        // WeaponHandler.calcCounterAV: a point defense bay counters with each member weapon, also as a defensive hit.
        var pointDefense = attack(ResolvedAttack.Kind.SHOT, union, atlas, 5, -1, true, List.of(),
              new ResolvedAttack.Shot("", Set.of(), false, true, 1, 0, false));
        // WeaponHandler at a building: the event's target is no unit; the damage reaches the infantry inside.
        var atBuilding = shot(atlas, building, weapon(atlas, "AC/20", Mek.LOC_RIGHT_TORSO), true, List.of(), null);
        // WeaponHandler.reportAttackAnimation: Shot.capture marks every AMS shot defensive, whatever its mode.
        var aimedAms = attack(ResolvedAttack.Kind.SHOT, griffin, locust, 3, Mek.LOC_CENTER_TORSO, true,
              List.of(new ResolvedAttack.Impact("CT", false, 2)),
              new ResolvedAttack.Shot(Weapon.MODE_AMS_MANUAL, Set.of(), false, true, 1, 0, false));
        var death = attack(ResolvedAttack.Kind.DEATH, commando, commando, -1, -1, true, List.of(), null);

        GpuReportLog reportsFirst = new GpuReportLog();
        assertTrue(reportsFirst.capture(history, 2, GamePhase.FIRING_REPORT).entries().stream()
              .allMatch(entry -> entry.attack() == null));
        for (var event : List.of(leftLaser, rightLaser, counter, lrm, pointDefense, atBuilding, death, aimedAms)) {
            reportsFirst.combat(event, units.get(event.attacker().entityId()),
                  targets.get(event.target().entityId()), 2, GamePhase.FIRING);
        }
        reportsFirst.combat(punch, atlas, locust, 2, GamePhase.PHYSICAL);
        var linked = reportsFirst.capture(history, 2, GamePhase.PHYSICAL_REPORT);

        var attacks = linked.entries().stream().filter(entry -> entry.round() == 2
              && (entry.kind() == GpuReportLog.Kind.WEAPON || entry.kind() == GpuReportLog.Kind.PHYSICAL)).toList();
        assertEquals(List.of("Medium Laser 43", "Medium Laser 43", "Medium Laser 43", "Medium Laser 43",
                    "LRM 20 (Swarm ammo) 44", "Medium Laser 45", "LRM 20 45", "AC/20 " + Entity.NONE,
                    "Anti-Missile System 43", "Punch 43"),
              attacks.stream().map(entry -> entry.text().split(" at ")[0].replace(" (Left Arm)", "") + " "
                    + entry.targetId()).toList(),
              "An attack at a building has no target unit, although its damage names the infantry inside");
        assertEquals(Arrays.asList(null, null, leftLaser.id(), rightLaser.id(), lrm.id(), null, null, atBuilding.id(),
                    aimedAms.id(), punch.id()),
              attacks.stream().map(GpuReportLog.Entry::attack).toList(),
              "An event links only in its own phase, never to an impossible attack, and the same weapon at the same "
                    + "target in order; an attack the client did not animate stays unlinked");
        assertEquals(List.of("7 9 null null false false", "null null Target not in arc null false false",
                    "7 9 null null false false", "7 9 null null false false", "7 9 null Swarm true true",
                    "7 9 null null false false", "null null target out of range null false false",
                    "7 9 null null false false", "7 9 null null false false", "8 10 null null false false"),
              attacks.stream().map(entry -> entry.targetNumber() + " " + entry.roll() + " " + entry.notFired() + " "
                    + entry.ammo() + " " + entry.locationDestroyed() + " " + entry.unitDestroyed()).toList(),
              "The to-hit numbers, the reason an attack was impossible, the ammunition and the destructions, from "
                    + "the report ids");
        assertEquals(List.of("Medium Laser LA true 5 null 0", "Medium Laser RA false 0 null 0",
                    "LRM 20 LT true 12 12 20", "AC/20 RT true 0 null 0", "Anti-Missile System CT true 2 null 0",
                    " LA true 3 null 0"),
              linked.combat().stream().map(event -> event.weapon() + " " + event.weaponLocation() + " "
                    + event.hit() + " " + event.damage() + " " + event.missileHits() + " " + event.missiles())
                    .toList(),
              "The AMS and point defense counters belong to the LRM attack and a destruction is no attack; an AMS "
                    + "used as a weapon is");
        assertEquals(List.of("Target not in arc", "target out of range"),
              linked.notFired().stream().map(GpuReportLog.Entry::notFired).toList());
        assertEquals(8, linked.reviewable(), "Six attacks with an event and the two impossible ones");

        GpuReportLog eventsFirst = new GpuReportLog();
        eventsFirst.combat(rightLaser, atlas, locust, 2, GamePhase.FIRING);
        eventsFirst.combat(leftLaser, atlas, locust, 2, GamePhase.FIRING);
        var reversed = eventsFirst.capture(history, 2, GamePhase.FIRING_REPORT).entries().stream()
              .filter(entry -> entry.kind() == GpuReportLog.Kind.WEAPON).map(GpuReportLog.Entry::attack).toList();
        assertEquals(Arrays.asList(null, null, rightLaser.id(), leftLaser.id(), null, null, null, null, null),
              reversed, "Events link in their arrival order, which the server keeps equal to the report order");
    }

    /**
     * The physical attacks a unit makes with its body, as TWGameManager reports them: a punch, then a charge, a death
     * from above and a ram, which MegaMek resolves after it. Each links to its event, so its log card offers Replay.
     */
    @Test
    void chargesDeathsFromAboveAndRamsLinkToTheirEvents() {
        Entity crab = unit(7, "King Crab KGC-009C");
        Entity commando = unit(44, "Commando COM-2D");
        Entity griffin = unit(45, "Griffin GRF-1N");
        Entity lightning = unit(46, "Lightning LTN-G15");
        Entity shilone = unit(47, "Shilone SL-17");
        Report steel = new Report(9020).add(dice(12));
        steel.choose(true);
        List<Report> reports = new ArrayList<>(List.of(new Report(4000)));
        reports.addAll(body(crab, new Report(4010).add("Left Arm").add("Commando COM-2D"), commando));
        reports.addAll(body(commando, new Report(4210).add("King Crab KGC-009C"), crab));
        reports.addAll(body(griffin, new Report(4246).add("Commando COM-2D"), commando));
        List<Report> ram = body(lightning, new Report(9030).add("Shilone SL-17"), shilone);
        ram.add(1, steel);
        reports.addAll(ram);
        var punch = attack(ResolvedAttack.Kind.PUNCH, crab, commando, -1, Mek.LOC_LEFT_ARM, true, List.of(), null);
        var charge = attack(ResolvedAttack.Kind.CHARGE, commando, crab, -1, Entity.LOC_NONE, true, List.of(), null);
        var dfa = attack(ResolvedAttack.Kind.DFA, griffin, commando, -1, Entity.LOC_NONE, true, List.of(), null);
        var collision = attack(ResolvedAttack.Kind.RAM, lightning, shilone, -1, Entity.LOC_NONE, true, List.of(), null);
        Map<Integer, Entity> units = Map.of(7, crab, 44, commando, 45, griffin, 46, lightning, 47, shilone);
        GpuReportLog log = new GpuReportLog();
        for (var event : List.of(punch, charge, dfa, collision)) {
            log.combat(event, units.get(event.attacker().entityId()), units.get(event.target().entityId()), 2,
                  GamePhase.PHYSICAL);
        }
        var physical = log.capture(List.of(new ArrayList<>(List.of(new Report(2000))), reports), 2,
              GamePhase.PHYSICAL_REPORT).entries().stream().filter(entry -> entry.kind() == GpuReportLog.Kind.PHYSICAL)
              .toList();
        assertEquals(List.of("7 44 " + punch.id(), "44 7 " + charge.id(), "45 44 " + dfa.id(), "46 47 " + collision.id()),
              physical.stream().map(entry -> entry.attackerId() + " " + entry.targetId() + " " + entry.attack()).toList());
    }

    /** One unit's physical attack as TWGameManager reports it: its header, the attack, the roll, a hit and damage. */
    private static List<Report> body(Entity attacker, Report attack, Entity target) {
        attack.indent();
        attack.newlines = 0;
        Report roll = new Report(4025).addDataWithTooltip("5", "5 (piloting skill)").add(dice(10));
        roll.newlines = 0;
        return new ArrayList<>(List.of(new Report(4005).addDesc(attacker), attack, roll, new Report(4040),
              new Report(6065).addDesc(target).add(10).add("Center Torso")));
    }

    @Test
    void myForceKeepsEntriesThatNameAnOwnUnitAsAttackerTargetOrSubject() throws Exception {
        Entity atlas = atlas();
        Entity locust = unit(43, "Locust LCT-1V");
        Entity commando = unit(44, "Commando COM-2D");
        List<Report> reports = new ArrayList<>(List.of(new Report(3000), new Report(3100).addDesc(atlas)));
        reports.addAll(attack(locust, "Medium Laser", true));
        reports.add(new Report(3100).addDesc(locust));
        reports.addAll(attack(atlas, "Medium Laser", false));
        reports.add(new Report(3100).addDesc(commando));
        reports.addAll(attack(locust, "Medium Laser", false));
        reports.add(new Report(3900));
        reports.addAll(psrBlock(atlas, "was kicked", new int[] {5, 8}));
        reports.addAll(psrBlock(locust, "was kicked", new int[] {5, 8}));
        reports.add(new Report(5000));
        reports.add(heat(commando, 9));

        var entries = GpuReportLog.format(1, reports).entries();

        assertEquals(List.of(true, true, false, true, false, false),
              entries.stream().map(entry -> entry.involves(Set.of(7))).toList());
        assertEquals(List.of(true, true, true, false, true, true),
              entries.stream().map(entry -> entry.involves(Set.of(43, 44))).toList());
    }

    /**
     * MegaMek reports an impossible attack as its start and ", but the shot (punch, ...) is impossible (reason)"
     * (WeaponHandler 3135, TWGameManager.resolvePunchAttack 4015) and sends no event for it.
     */
    @Test
    void reviewableCountsThisRoundsAttacksNotFiredAttacksAndPsrItemsAndANewRoundStartsEmpty() throws Exception {
        Entity atlas = atlas();
        Entity locust = unit(43, "Locust LCT-1V");
        Entity ghost = unit(44, "Commando COM-2D");
        GpuReportLog log = new GpuReportLog();
        List<Report> earlier = new ArrayList<>(List.of(new Report(3000), new Report(3100).addDesc(atlas)));
        earlier.addAll(impossible(locust, "Medium Laser", "Target in dead zone"));
        List<Report> round = new ArrayList<>(List.of(new Report(3000), new Report(3100).addDesc(atlas)));
        round.addAll(attack(locust, "Medium Laser", true));
        round.addAll(impossible(locust, "Medium Laser", "Target in dead zone"));
        // Double blind: the impossible shot of a unit the player has not seen, its data hidden as the server sends it.
        round.add(unseen(new Report(3100).addDesc(ghost)));
        impossible(atlas, "Medium Laser", "Target in dead zone").forEach(report -> round.add(unseen(report)));
        round.add(new Report(3900));
        round.addAll(psrBlock(locust, "took 20+ damage", new int[] {5, 3}));
        Report punch = new Report(4010);
        punch.indent();
        punch.add("Left Arm").add("Locust LCT-1V (OpFor)");
        punch.newlines = 0;
        round.addAll(List.of(new Report(4000), new Report(4005).addDesc(atlas), punch,
              new Report(4015).add("Weapons fired from arm this turn")));
        List<List<Report>> history = new ArrayList<>(List.of(earlier, round));
        var laser = shot(atlas, locust, weapon(atlas, "Medium Laser", Mek.LOC_LEFT_ARM), true,
              List.of(new ResolvedAttack.Impact("LT", false, 5)), null);
        log.combat(laser, atlas, locust, 2, GamePhase.FIRING);
        // TWGameManager.destroyEntity sends the destruction as a hit of the unit on itself.
        log.combat(attack(ResolvedAttack.Kind.DEATH, locust, locust, -1, -1, true, List.of(), null), locust,
              locust, 2, GamePhase.FIRING);
        atlas.mpUsed = 5;
        atlas.delta_distance = 3;
        log.moved(atlas, new UnitLocation(7, new Coords(4, 4), 0, 0, 0),
              List.of(new UnitLocation(7, new Coords(4, 5), 0, 0, 0), new UnitLocation(7, new Coords(5, 6), 1, 0, 0)),
              EntityMovementType.MOVE_RUN, 2);

        var snapshot = log.capture(history, 2, GamePhase.FIRING_REPORT);

        assertEquals(List.of(laser.id()), snapshot.combat().stream().map(GpuReportLog.CombatEvent::id).toList(),
              "A destruction is no attack");
        assertEquals(List.of("7 Target in dead zone", Entity.NONE + " ", "7 Weapons fired from arm this turn"),
              snapshot.entries().stream().filter(entry -> entry.round() == 2 && entry.notFired() != null)
                    .map(entry -> entry.attackerId() + " " + entry.notFired()).toList(),
              "The log keeps every impossible attack; the unseen unit's has no attacker and its reason is hidden");
        assertEquals(List.of("WEAPON 7 43 Target in dead zone", "PHYSICAL 7 43 Weapons fired from arm this turn"),
              snapshot.notFired().stream().map(entry -> entry.kind() + " " + entry.attackerId() + " "
                    + entry.targetId() + " " + entry.notFired()).toList(),
              "Review shows this round's attacks of disclosed units that were not fired, not the last round's");
        assertEquals(4, snapshot.reviewable(), "The laser, the two attacks not fired and the PSR");
        assertEquals(List.of("1 2 7 Ran 5 3 (4, 4) (5, 6) 1"), snapshot.moves().stream().map(move -> move.sequence()
              + " " + move.round() + " " + move.entityId() + " " + move.type() + " " + move.mpUsed() + " "
              + move.hexes() + " " + coords(move.from()) + " " + coords(move.to()) + " " + move.facing()).toList());
        assertSame(snapshot, log.capture(history, 2, GamePhase.FIRING_REPORT));

        history.add(new ArrayList<>(List.of(new Report(1000))));
        var next = log.capture(history, 3, GamePhase.INITIATIVE_REPORT);
        assertEquals(0, next.reviewable());
        assertTrue(next.combat().isEmpty() && next.psr().isEmpty() && next.moves().isEmpty());
    }

    /**
     * User item 30: when nothing deploys at the start, MegaMek's round 0 is already a combat round ("Initiative Phase
     * for Round #0"), and GameReports keeps it and round 1 in one report list. The log shows them as rounds 1 and 2:
     * the list's entries split at the second initiative, round 1's events are gone in round 2 as in any new round, and
     * an event links only to an entry of its own round.
     */
    @Test
    void megameksRoundsZeroAndOneStayApartWhenNothingDeploysAtTheStart() throws Exception {
        Entity atlas = atlas();
        Entity locust = unit(43, "Locust LCT-1V");
        int laser = weapon(atlas, "Medium Laser", Mek.LOC_LEFT_ARM);
        List<Report> first = new ArrayList<>(List.of(new Report(1000).add(0), new Report(3000),
              new Report(3100).addDesc(atlas)));
        first.addAll(attack(locust, "Medium Laser", true));
        List<List<Report>> history = List.of(first);
        GpuReportLog log = new GpuReportLog();
        log.capture(history, 0, GamePhase.INITIATIVE_REPORT);
        var hit = shot(atlas, locust, laser, true, List.of(new ResolvedAttack.Impact("LT", false, 5)), null);
        log.combat(hit, atlas, locust, 0, GamePhase.FIRING);
        var roundOne = log.capture(history, 0, GamePhase.FIRING_REPORT);
        assertEquals(1, roundOne.round());
        assertEquals(List.of("1 " + hit.id()), weaponEntries(roundOne));

        // Round 1's reports join the same list.
        first.addAll(List.of(new Report(1000).add(1), new Report(3000), new Report(3100).addDesc(atlas)));
        first.addAll(attack(locust, "Medium Laser", false));
        log.capture(history, 1, GamePhase.INITIATIVE_REPORT);
        var miss = shot(atlas, locust, laser, false, List.of(), null);
        log.combat(miss, atlas, locust, 1, GamePhase.FIRING);
        var roundTwo = log.capture(history, 1, GamePhase.FIRING_REPORT);
        assertEquals(2, roundTwo.round());
        assertEquals(List.of(miss.id()), roundTwo.combat().stream().map(GpuReportLog.CombatEvent::id).toList());
        assertEquals(List.of(2), roundTwo.combat().stream().map(GpuReportLog.CombatEvent::round).toList());
        assertEquals(List.of("1 null", "2 " + miss.id()), weaponEntries(roundTwo),
              "The hit stays in round 1, unlinked once its event is gone; the miss is round 2's");
    }

    /** The round and linked event of each weapon entry, in report order. */
    private static List<String> weaponEntries(GpuReportLog.Snapshot log) {
        return log.entries().stream().filter(entry -> entry.kind() == GpuReportLog.Kind.WEAPON)
              .map(entry -> entry.round() + " " + entry.attack()).toList();
    }

    @Test
    void roundsInTheAirArePublishedAndOnlyAChangeReplacesTheSnapshot() {
        GpuReportLog log = new GpuReportLog();
        List<List<Report>> history = List.of(List.of(new Report(3000)));
        var own = new RoundsInAirDialog.Row(2, "Team 1", "Raven's Nest", "Long Tom Artillery Vehicle", "2 turn(s)",
              "0607", "Homing");
        var enemy = new RoundsInAirDialog.Row(1, "Team 2", "OpFor", "Thumper Artillery Vehicle", "1 turn(s)",
              "Unknown", "Unknown");

        var inbound = log.capture(history, 1, GamePhase.FIRING, List.of(enemy, own), id -> null);

        assertEquals(List.of(enemy, own), inbound.inFlight());
        assertSame(inbound, log.capture(history, 1, GamePhase.FIRING, new ArrayList<>(List.of(enemy, own)),
              id -> null), "Equal rows keep the published snapshot");
        assertEquals(List.of(own), log.capture(history, 1, GamePhase.FIRING, List.of(own), id -> null).inFlight());
        assertTrue(log.capture(history, 1, GamePhase.FIRING).inFlight().isEmpty());
    }

    /** The board source captures the log with the Rounds in the Air rows of its game (K13). */
    @Test
    void theBoardSourcePublishesTheRoundsInTheAirOfItsGame() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Player enemy = new Player(1, "OpFor");
                enemy.setTeam(2);
                fixture.game.addPlayer(enemy.getId(), enemy);
                // An enemy round as the server sends it: no aim, and here from a unit the client does not know.
                fixture.game.setEnemyArtilleryInbound(List.of(new EnemyArtilleryInbound(99, enemy.getId(), 2)));
                fixture.source.refresh();
            });

            assertEquals(List.of(new RoundsInAirDialog.Row(2, "Team 2", "OpFor", "(unknown unit)", "2 turn(s)",
                  "Unknown", "Unknown")), fixture.source.takeFrame().reports().inFlight());
        }
    }

    static Entity unit(int id, String name) {
        Entity unit = mock(Mek.class);
        when(unit.getId()).thenReturn(id);
        when(unit.getShortName()).thenReturn(name);
        when(unit.getOwner()).thenReturn(new Player(id, id == 7 ? "Raven's Nest" : "OpFor"));
        Crew crew = mock(Crew.class);
        when(unit.getCrew()).thenReturn(crew);
        when(crew.getNickname()).thenReturn("");
        return unit;
    }

    /** One weapon attack as WeaponHandler reports it: indented start, to-hit, roll, result and damage. */
    static List<Report> attack(Entity target, String weapon, boolean hit) {
        Report need = new Report(3150).addDataWithTooltip("7", "4 (gunnery)<br>+ 3 (target movement)");
        need.newlines = 0;
        Report roll = new Report(3155).add(9);
        roll.newlines = 0;
        List<Report> result = new ArrayList<>(List.of(fire(target, weapon, null), need, roll,
              new Report(hit ? 3390 : 3220)));
        if (hit) {
            result.add(new Report(6065).addDesc(target).add(10).add("Left Torso"));
            result.add(new Report(6085).add(12));
        }
        result.getLast().newlines = 2;
        return result;
    }

    /** The indented start of a weapon attack; MissileWeaponHandler names a non-standard {@code ammo} (else null). */
    private static Report fire(Entity target, String weapon, String ammo) {
        Report fire = new Report(ammo == null ? 3115 : 3116);
        fire.indent();
        fire.add(weapon);
        if (ammo != null) {
            fire.add(ammo);
        }
        fire.addDesc(target);
        fire.newlines = 0;
        return fire;
    }

    /** An attack start and WeaponHandler's ", but the shot is impossible (reason)", which ends the attack. */
    private static List<Report> impossible(Entity target, String weapon, String reason) {
        Report impossible = new Report(3135).add(reason);
        impossible.newlines = 2;
        return List.of(fire(target, weapon, null), impossible);
    }

    /** Gives a mocked unit a weapon mount at {@code index}; an AMS not firing automatically is "Use as Weapon". */
    private static void mount(Entity unit, int index, String type, boolean automatic) {
        WeaponMounted weapon = mock(WeaponMounted.class);
        when(weapon.getType()).thenReturn((WeaponType) EquipmentType.get(type));
        when(weapon.firesAutomatically()).thenReturn(automatic);
        doReturn(weapon).when(unit).getEquipment(index);
    }

    /** What TWGameManager.filterReport sends a player who has not seen the report's unit: obscured data hidden. */
    private static Report unseen(Report report) {
        for (int index = 0; index < report.dataCount(); index++) {
            if (report.isValueObscured(index)) {
                report.hideData(index);
            }
        }
        return report;
    }

    /** One unit's end-of-phase block from resolvePilotingRolls; each roll is {target number, dice}. */
    private static List<Report> psrBlock(Entity unit, String reasons, int[]... rolls) {
        Report header = new Report(2280).addDesc(unit).add(rolls.length);
        header.add(reasons);
        List<Report> block = new ArrayList<>(List.of(header,
              new Report(2285).add(new PilotingRollData(unit.getId(), 5, "Base piloting skill"))));
        for (int index = 0; index < rolls.length; index++) {
            Report number = new Report(2291).add(index + 1);
            number.newlines = 0;
            block.add(number);
            block.add(new Report(2299).add(new PilotingRollData(unit.getId(), rolls[index][0], "Base piloting skill"))
                  .add(dice(rolls[index][1])).choose(rolls[index][1] >= rolls[index][0]));
        }
        return block;
    }

    /** The heat line of HeatResolver: buildup, dissipation and the resulting heat in its heat colour. */
    private static Report heat(Entity unit, int heat) {
        Report report = new Report(5035).addDesc(unit).add(heat + 2).add(2);
        return report.add(Report.bold(report.fgColor(Color.ORANGE, String.valueOf(heat))));
    }

    /** HeatResolver's shutdown check against 4+: the target roll, the dice and the result. */
    private static Report shutdownCheck(Entity unit, int roll) {
        return new Report(5060).addDesc(unit).add(new TargetRoll(4, "heat")).add(dice(roll)).choose(roll >= 4);
    }

    /** HeatResolver's ammunition explosion check at heat 19 to 22: it needs 4+ (5065). */
    private static Report ammoCheck(Entity unit, int roll) {
        return new Report(5065).addDesc(unit).add(4).add(dice(roll)).choose(roll >= 4);
    }

    private static Roll dice(int value) {
        Roll roll = mock(Roll.class);
        when(roll.getIntValue()).thenReturn(value);
        when(roll.getReport()).thenReturn("Dice: " + value);
        return roll;
    }

    private static Entity atlas() throws Exception {
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        atlas.setId(7);
        atlas.setOwner(new Player(7, "Raven's Nest"));
        return atlas;
    }

    private static int weapon(Entity unit, String name, int location) {
        return unit.getWeaponList().stream()
              .filter(weapon -> weapon.getName().equals(name) && weapon.getLocation() == location)
              .map(unit::getEquipmentNum).findFirst().orElseThrow();
    }

    /**
     * Round 2 of the volley, as the server reports it: a pointblank shot in the movement phase that the client did not
     * animate, then an impossible shot, three shots (the LRM fires Swarm ammo and destroys a location and the
     * Commando), one the client did not animate, one impossible, one at a building with infantry inside, the Griffin's
     * AMS used as a weapon, and a punch.
     */
    private static List<Report> volley(Entity atlas, Entity locust, Entity commando, Entity griffin,
          Entity infantry) {
        List<Report> reports = new ArrayList<>(List.of(new Report(2000), new Report(3102).addDesc(atlas)));
        reports.addAll(attack(locust, "Medium Laser", true));
        reports.addAll(List.of(new Report(3000), new Report(3100).addDesc(atlas)));
        reports.addAll(impossible(locust, "Medium Laser", "Target not in arc"));
        reports.addAll(attack(locust, "Medium Laser", true));
        reports.addAll(attack(locust, "Medium Laser", false));
        List<Report> lrm = attack(commando, "LRM 20", true);
        lrm.set(0, fire(commando, "LRM 20", "Swarm"));
        lrm.addAll(lrm.size() - 1, List.of(new Report(6115), new Report(6365).addDesc(commando).add("damage")));
        reports.addAll(lrm);
        reports.addAll(attack(griffin, "Medium Laser", false));
        reports.addAll(impossible(griffin, "LRM 20", "target out of range"));
        // WeaponHandler names a building by its display name (3120); TWGameManager.damageInfantryIn then passes the
        // damage on to the infantry inside (6450) and reports it (6065).
        List<Report> atBuilding = attack(infantry, "AC/20", true);
        Report start = new Report(3120).add("AC/20").add("Hex 0605 of Medium Building", true);
        start.indent();
        start.newlines = 0;
        atBuilding.set(0, start);
        atBuilding.add(4, new Report(6450).add(10).add("Foot Platoon (OpFor)"));
        reports.addAll(atBuilding);
        reports.add(new Report(3100).addDesc(griffin));
        reports.addAll(attack(locust, "Anti-Missile System", true));
        Report punch = new Report(4010);
        punch.indent();
        punch.add("Left Arm").add("Locust LCT-1V");
        punch.newlines = 0;
        Report punchRoll = new Report(4025).addDataWithTooltip("8", "4 (piloting skill)<br>+ 4 (target movement)")
              .add(dice(10));
        punchRoll.newlines = 0;
        reports.addAll(List.of(new Report(4000), new Report(4005).addDesc(atlas), punch, punchRoll,
              new Report(4040)));
        reports.getLast().newlines = 2;
        return reports;
    }

    private static ResolvedAttack shot(Entity attacker, Targetable target, int weapon, boolean hit,
          List<ResolvedAttack.Impact> impacts, ResolvedAttack.Shot shot) {
        return attack(ResolvedAttack.Kind.SHOT, attacker, target, weapon, attacker.getEquipment(weapon).getLocation(),
              hit, impacts, shot);
    }

    private static ResolvedAttack attack(ResolvedAttack.Kind kind, Entity attacker, Targetable target, int equipment,
          int limb, boolean hit, List<ResolvedAttack.Impact> impacts, ResolvedAttack.Shot shot) {
        return new ResolvedAttack(UUID.randomUUID(), kind,
              new UnitLocation(attacker.getId(), new Coords(5, 5), 0, 0, 0),
              new UnitLocation(target.getId(), new Coords(5, 4), 3, 0, 0), target.getTargetType(), equipment, "",
              limb, hit, List.of(), shot, impacts);
    }

    private static String coords(Coords coords) {
        return "(" + coords.getX() + ", " + coords.getY() + ")";
    }
}
