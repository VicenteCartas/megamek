/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.common.ResolvedAttack;
import megamek.common.TagInfo;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A TAG shot draws as a laser designator, held on a target it hits, and every unit a TAG has hit carries a red marker
 * until the tags are cleared at the next round's Initiative.
 */
class GpuTagDesignatorTest {
    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void aTagShotDrawsAsADesignatorAndOtherWeaponsDoNot() {
        UnitAttack tag = shot("ISTAG", true);
        assertEquals("designator", tag.effect(tag.event.entityId(), tag.event.result().equipmentIndex()));
        UnitAttack laser = shot("ISMediumLaser", true);
        assertEquals("laser", laser.effect(laser.event.entityId(), laser.event.result().equipmentIndex()));
    }

    /** A hit holds its line on the target a little longer, so the player sees what it marked; a miss does not. */
    @Test
    void aTagThatHitsHoldsItsDesignatorLonger() {
        UnitAttack hit = shot("ISTAG", true);
        UnitAttack miss = shot("ISTAG", false);
        assertEquals(UnitAttack.DESIGNATOR_HOLD_SECONDS, hit.duration - miss.duration, .001f);
        UnitAttack laser = shot("ISMediumLaser", true);
        assertEquals(miss.duration, laser.duration, .001f, "Other weapons keep their timing");
    }

    @Test
    void aTaggedUnitCarriesTheMarkerUntilTheTagsAreCleared() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            assertEquals(0, tagged(fixture).size());
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.addTagInfo(new TagInfo(99, Targetable.TYPE_ENTITY, fixture.entity, false));
                fixture.source.refresh();
            });
            List<BoardMarker> tagged = tagged(fixture);
            assertEquals(1, tagged.size(), "The tagged unit is marked");
            assertEquals(fixture.entity.getPosition(), tagged.getFirst().coords(), "where it stands");
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.resetTagInfo();
                fixture.source.refresh();
            });
            assertEquals(0, tagged(fixture).size(), "Gone once the tags are cleared at Initiative");
        }
    }

    @Test
    void aMissedTagMarksNothing() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.addTagInfo(new TagInfo(99, Targetable.TYPE_ENTITY, fixture.entity, true));
                fixture.source.refresh();
            });
            assertEquals(0, tagged(fixture).size());
        }
    }

    /**
     * A TAG that hit keeps its designator line on the target through the Firing phase and its report, when
     * semi-guided and homing rounds use it, and drops it once the Physical phase begins.
     */
    @Test
    void aHitTagKeepsItsDesignatorLineUntilTheFiringPhaseEnds() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> fixture.game.addTagInfo(
                  new TagInfo(fixture.entity.getId(), Targetable.TYPE_ENTITY, fixture.entity, false)));
            for (GamePhase phase : List.of(GamePhase.OFFBOARD_REPORT, GamePhase.FIRING, GamePhase.FIRING_REPORT)) {
                assertEquals(1, designations(fixture, phase).size(), phase.name());
            }
            assertEquals(0, designations(fixture, GamePhase.PHYSICAL).size(), "Gone after the Firing phase");
            assertEquals(0, designations(fixture, GamePhase.MOVEMENT).size());
        }
    }

    @Test
    void aMissedTagLeavesNoDesignatorLine() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> fixture.game.addTagInfo(
                  new TagInfo(fixture.entity.getId(), Targetable.TYPE_ENTITY, fixture.entity, true)));
            assertEquals(0, designations(fixture, GamePhase.FIRING).size());
        }
    }

    private static List<BoardScene.Designation> designations(GpuBoardFixture fixture, GamePhase phase)
          throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.setPhase(phase);
            fixture.source.refresh();
        });
        return fixture.source.takeFrame().scene().designations();
    }

    private static List<BoardMarker> tagged(GpuBoardFixture fixture) {
        return fixture.source.takeFrame().scene().markers().stream()
              .filter(marker -> marker.kind() == BoardMarker.Kind.TAGGED).toList();
    }

    /** A shot from one unit at another with the named weapon, as the shared playback fixture builds one. */
    private static UnitAttack shot(String weaponName, boolean hit) {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var original = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, hit).result();
        var event = new ResolvedAttack(UUID.randomUUID(), original.kind(), original.attacker(), original.target(),
              original.targetType(), 0, weaponName, 4, hit, original.mounts(), original.shot());
        return new UnitAttack(new BoardScene.Combat(event, source, target, target.location()));
    }
}
