/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import megamek.client.ui.tileset.MekTileset;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.equipment.EquipmentType;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UnitModelSelectionTest {
    @Test
    void resolvedIconAndActualSurvivingFieldGunsTravelInTheImmutableSelection() throws Exception {
        var infantry = new ConvInfantry();
        infantry.setChassis("Arbitrary custom name");
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        var tileset = mock(MekTileset.class);
        when(tileset.modelFor(infantry, -1)).thenReturn("units/modular/infantry.json");
        when(tileset.entryFor(infantry, -1)).thenReturn(tileset.new MekEntry("Infantry/Horse.png"));
        var first = UnitModelSelection.capture(infantry, -1, false, tileset).state().structure();
        assertEquals("Infantry/Horse.png", first.unitIcon());
        assertEquals(0, first.fieldGuns());
        var gun = infantry.addEquipment(EquipmentType.get("ISAC5"), ConvInfantry.LOC_FIELD_GUNS);
        infantry.addEquipment(EquipmentType.get("ISAC5"), ConvInfantry.LOC_FIELD_GUNS);
        infantry.addEquipment(EquipmentType.get("ISAC5 Ammo"), ConvInfantry.LOC_FIELD_GUNS);
        infantry.addEquipment(EquipmentType.get("InfantryAssaultRifle"), ConvInfantry.LOC_INFANTRY);
        var armed = UnitModelSelection.capture(infantry, -1, false, tileset).state().structure();
        assertEquals(2, armed.fieldGuns());
        assertNotEquals(first, armed, "Assembly cache must see changed field guns");
        gun.setDestroyed(true);
        var damaged = UnitModelSelection.capture(infantry, -1, false, tileset).state().structure();
        assertEquals(1, damaged.fieldGuns());
        assertEquals(2, armed.fieldGuns(), "A previously captured snapshot remains immutable");
        assertNotEquals(armed, damaged);
        when(tileset.entryFor(infantry, -1)).thenReturn(tileset.new MekEntry("Infantry/Elephant.png"));
        var changedIcon = UnitModelSelection.capture(infantry, -1, false, tileset).state().structure();
        assertEquals("Infantry/Elephant.png", changedIcon.unitIcon());
        assertNotEquals(damaged, changedIcon, "Assembly cache must also see changed icon overrides");
    }

    /**
     * The whole-body overlay follows the damage level that the board label's damage tile and the unit card show, not
     * the share of armor and structure lost.
     */
    @Test
    void wholeBodyDamageFollowsTheDamageLevelAndSurvivesCapture() {
        var mek = new megamek.common.units.BipedMek();
        for (int location = 0; location < mek.locations(); location++) {
            mek.initializeArmor(20, location);
            mek.initializeInternal(10, location);
            if (mek.hasRearArmor(location)) { mek.initializeRearArmor(10, location); }
        }
        assertNull(UnitModelState.capture(mek).appearance().bodyStage());
        mek.setArmor(0, Mek.LOC_CENTER_TORSO);
        mek.setArmor(0, Mek.LOC_CENTER_TORSO, true);
        mek.setInternal(5, Mek.LOC_CENTER_TORSO);
        assertEquals(Entity.DMG_HEAVY, mek.getDamageLevel(), "Torso structure damage, though only 35 of 270 points");
        assertEquals(UnitDamageDisplay.Stage.BODY_75, UnitModelState.capture(mek).appearance().bodyStage());
        mek.setDoomed(true);
        assertEquals(UnitDamageDisplay.Stage.BODY_100, UnitModelState.capture(mek).appearance().bodyStage(),
              "A lethal result reaches the final damage band");
    }

    @Test
    void aWholeBodyModelShowsItsDamageLevel() {
        Tank tank = mock(Tank.class);
        int[] levels = { Entity.DMG_NONE, Entity.DMG_LIGHT, Entity.DMG_MODERATE, Entity.DMG_HEAVY, Entity.DMG_CRIPPLED };
        UnitDamageDisplay.Stage[] stages = { null, UnitDamageDisplay.Stage.BODY_25, UnitDamageDisplay.Stage.BODY_50,
              UnitDamageDisplay.Stage.BODY_75, UnitDamageDisplay.Stage.BODY_100 };
        for (int index = 0; index < levels.length; index++) {
            when(tank.getDamageLevel()).thenReturn(levels[index]);
            assertEquals(UnitDamageDisplay.body(stages[index]), UnitModelSelection.damage(tank),
                  "damage level " + levels[index]);
        }
        assertSame(BoardScene.LocationDamage.NONE, UnitModelSelection.damage(new ConvInfantry()));
    }

    @Test
    void unidentifiedContactsNeverResolveModelIdentity() {
        Entity hidden = mock(Entity.class);
        MekTileset tileset = mock(MekTileset.class);
        assertNull(UnitModelSelection.capture(hidden, -1, true, tileset));
        verifyNoInteractions(hidden, tileset);
    }

    @Test
    void formationsUseSurvivingPersonnelAndStaySmall() {
        ConvInfantry infantry = new ConvInfantry();
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        MekTileset tileset = mock(MekTileset.class);
        when(tileset.modelFor(infantry, -1)).thenReturn("units/infantry/model.json");
        assertEquals(6, UnitModelSelection.capture(infantry, -1, false, tileset).figures());
        infantry.setInternal(4, ConvInfantry.LOC_INFANTRY);
        assertEquals(2, UnitModelSelection.capture(infantry, -1, false, tileset).figures());
        infantry.setInternal(0, ConvInfantry.LOC_INFANTRY);
        assertEquals(0, UnitModelSelection.capture(infantry, -1, false, tileset).figures());
        BattleArmor armor = mock(BattleArmor.class);
        when(armor.locations()).thenReturn(6);
        for (int loc = 1; loc <= 5; loc++) {
            when(armor.getInternal(loc)).thenReturn(1);
        }
        when(tileset.modelFor(armor, -1)).thenReturn("units/battle-armor/model.json");
        when(armor.getShortNameRaw()).thenReturn("Battle Armor");
        when(armor.getMovementMode()).thenReturn(EntityMovementMode.INF_JUMP);
        assertEquals(5, UnitModelSelection.capture(armor, -1, false, tileset).figures());
        assertEquals("Battle Armor", UnitModelSelection.capture(armor, -1, false, tileset).variant());
        when(armor.getInternal(3)).thenReturn(-1);
        assertEquals(4, UnitModelSelection.capture(armor, -1, false, tileset).figures());
        assertEquals(4, UnitModelSelection.figures(100, 4));
    }

    @ParameterizedTest
    @EnumSource(value = EntityMovementMode.class, names = {
          "INF_LEG", "INF_MOTORIZED", "INF_JUMP", "TRACKED", "WHEELED", "HOVER", "INF_UMU"
    })
    void conventionalInfantryAppearanceUsesItsMovementMode(EntityMovementMode movement) {
        ConvInfantry infantry = new ConvInfantry();
        infantry.setChassis("Renamed rifle platoon");
        infantry.setMovementMode(movement);
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        MekTileset tileset = mock(MekTileset.class);
        when(tileset.modelFor(infantry, -1)).thenReturn("units/infantry/model.json");
        var selection = UnitModelSelection.capture(infantry, -1, false, tileset);
        assertEquals(movement.name(), selection.variant());
        assertEquals(6, selection.figures());
        // Casualties change the compressed formation, not its vehicle/jump identity.
        infantry.setInternal(9, ConvInfantry.LOC_INFANTRY);
        selection = UnitModelSelection.capture(infantry, -1, false, tileset);
        assertEquals(movement.name(), selection.variant());
        assertEquals(3, selection.figures());
    }

    @Test
    void twistIsTheShortestTurnFromTheLegsToTheTorso() {
        assertEquals(0, UnitModelSelection.twist(2, 2));
        assertEquals(1, UnitModelSelection.twist(2, 3));
        assertEquals(-1, UnitModelSelection.twist(2, 1));
        // Across north: legs facing 5 with the torso at 0 is one hexside clockwise, and the reverse is one back.
        assertEquals(1, UnitModelSelection.twist(5, 0));
        assertEquals(-1, UnitModelSelection.twist(0, 5));
        assertEquals(2, UnitModelSelection.twist(4, 0));
        assertEquals(-2, UnitModelSelection.twist(0, 4));
        assertEquals(3, UnitModelSelection.twist(0, 3));
        // An undeployed unit has no facing yet.
        assertEquals(0, UnitModelSelection.twist(-1, 2));
        assertEquals(0, UnitModelSelection.twist(2, -1));
    }

    @Test
    void theTwistTravelsWithTheModelChoice() {
        ConvInfantry infantry = new ConvInfantry();
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        MekTileset tileset = mock(MekTileset.class);
        when(tileset.modelFor(infantry, -1)).thenReturn("units/infantry/model.json");
        assertEquals(0, UnitModelSelection.capture(infantry, -1, false, tileset).twist());
        assertEquals(-1, UnitModelSelection.capture(infantry, -1, false, tileset, -1).twist());
    }

    private static Mek bipedWithLocations() {
        Mek mek = mock(Mek.class);
        String[] abbreviations = { "HD", "CT", "RT", "LT", "RA", "LA", "RL", "LL" };
        when(mek.locations()).thenReturn(abbreviations.length);
        for (int location = 0; location < abbreviations.length; location++) {
            when(mek.getLocationAbbr(location)).thenReturn(abbreviations[location]);
        }
        when(mek.isArm(Mek.LOC_LEFT_ARM)).thenReturn(true);
        when(mek.isArm(Mek.LOC_RIGHT_ARM)).thenReturn(true);
        when(mek.getDependentLocation(anyInt())).thenReturn(Entity.LOC_NONE);
        when(mek.getDependentLocation(Mek.LOC_RIGHT_TORSO)).thenReturn(Mek.LOC_RIGHT_ARM);
        when(mek.getDependentLocation(Mek.LOC_LEFT_TORSO)).thenReturn(Mek.LOC_LEFT_ARM);
        return mek;
    }

    @Test
    void anIntactMekAndInfantryShowNoDamage() {
        assertSame(BoardScene.LocationDamage.NONE, UnitModelSelection.damage(bipedWithLocations()));
        assertSame(BoardScene.LocationDamage.NONE, UnitModelSelection.damage(new ConvInfantry()));
        assertTrue(BoardScene.LocationDamage.NONE.isNone());
    }

    @Test
    void blownOffLegsAndDestroyedArmsDisappearWhileAttachedDamageRemains() {
        Mek mek = bipedWithLocations();
        // A destroyed right torso takes the right arm with it; the left leg was blown off in an earlier phase.
        when(mek.isLocationTrulyDestroyed(Mek.LOC_RIGHT_TORSO)).thenReturn(true);
        when(mek.isLocationTrulyDestroyed(Mek.LOC_RIGHT_ARM)).thenReturn(true);
        when(mek.isLocationBlownOff(Mek.LOC_LEFT_LEG)).thenReturn(true);
        BoardScene.LocationDamage damage = UnitModelSelection.damage(mek);
        assertEquals(Set.of("RA", "LL"), damage.removed());
        assertEquals(Set.of("RT"), damage.wrecked());
    }

    @Test
    void aLostSideTorsoTakesItsArmEvenWhenTheArmWasNeverRecordedAsLost() {
        // The damage editor can zero a side torso and leave the arm's own numbers untouched.
        Mek mek = bipedWithLocations();
        when(mek.isLocationTrulyDestroyed(Mek.LOC_LEFT_TORSO)).thenReturn(true);
        BoardScene.LocationDamage damage = UnitModelSelection.damage(mek);
        assertEquals(Set.of("LA"), damage.removed());
        assertEquals(Set.of("LT"), damage.wrecked());
    }

    @Test
    void confirmedDetachmentsDisappearImmediatelyWithoutWaitingForPhaseCleanup() {
        Mek mek = bipedWithLocations();
        for (int location : new int[] { Mek.LOC_HEAD, Mek.LOC_LEFT_ARM, Mek.LOC_RIGHT_LEG }) {
            when(mek.isLocationBlownOff(location)).thenReturn(true);
            when(mek.isLocationBlownOffThisPhase(location)).thenReturn(true);
        }
        when(mek.isLocationTrulyDestroyed(Mek.LOC_LEFT_LEG)).thenReturn(true);
        var damage = UnitModelSelection.damage(mek);
        assertEquals(Set.of("HD", "LA", "RL"), damage.removed());
        assertEquals(Set.of("LL"), damage.wrecked());
        for (int location : new int[] { Mek.LOC_HEAD, Mek.LOC_LEFT_ARM, Mek.LOC_RIGHT_LEG }) {
            when(mek.isLocationBlownOffThisPhase(location)).thenReturn(false);
        }
        assertEquals(damage, UnitModelSelection.damage(mek));
    }
}
