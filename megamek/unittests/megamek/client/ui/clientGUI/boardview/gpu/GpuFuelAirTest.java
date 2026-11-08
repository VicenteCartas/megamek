/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.common.weapons.handlers.AreaEffectHelper.FuelAirStrength;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A fuel-air (FAE) round: it lights every hex in its radius a moment after landing, chars each one rather than digging
 * a crater, shows its terrain changes at that ignition, and follows the rules in thin air and vacuum.
 */
class GpuFuelAirTest {
    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void fuelAirBurnsAsTheAtmosphereAllows() {
        assertEquals(FuelAirStrength.FULL, AreaEffectHelper.fuelAirStrength(Atmosphere.STANDARD, false));
        assertEquals(FuelAirStrength.HALF, AreaEffectHelper.fuelAirStrength(Atmosphere.THIN, false));
        assertEquals(FuelAirStrength.NONE, AreaEffectHelper.fuelAirStrength(Atmosphere.TRACE, false));
        assertEquals(FuelAirStrength.NONE, AreaEffectHelper.fuelAirStrength(Atmosphere.VACUUM, false));
        assertEquals(FuelAirStrength.NONE, AreaEffectHelper.fuelAirStrength(Atmosphere.STANDARD, true), "Space");
    }

    @Test
    void fuelAirDamageFollowsItsOwnTableNotTheUsualFalloff() {
        assertEquals(List.of(20, 10, 5, 0), List.of(AreaEffectHelper.fuelAirRingDamage(2, 0),
              AreaEffectHelper.fuelAirRingDamage(2, 1), AreaEffectHelper.fuelAirRingDamage(2, 2),
              AreaEffectHelper.fuelAirRingDamage(2, 3)), "An Arrow IV's");
        assertEquals(10, AreaEffectHelper.fuelAirRingDamage(1, 0), "A Thumper's");
    }

    /** An Arrow IV's fuel-air round covers its R2: 19 hexes, each charred at ignition and none before. */
    @Test
    void itCharsEveryHexInItsRadiusAtIgnitionAndDigsNoCrater() {
        assertTrue(GpuArtilleryBlast.leavesNoCrater("ISArrowIV", fuelAir()));
        List<GpuGroundDamage.Impact> beforeIgnition = marks(FuelAirStrength.FULL,
              GpuArtilleryBlast.FUEL_AIR_IGNITION_SECONDS - .05f);
        assertEquals(0, beforeIgnition.size(), "Nothing is charred while the fuel is still spreading");
        List<GpuGroundDamage.Impact> charred = marks(FuelAirStrength.FULL, GpuArtilleryBlast.FUEL_AIR_IGNITION_SECONDS + .05f);
        assertEquals(19, charred.size(), "The target hex and both rings");
        for (GpuGroundDamage.Impact mark : charred) {
            assertEquals(GpuGroundDamage.Style.BURN, mark.style(), "It burns rather than digs");
        }
        float darkest = (float) charred.stream().mapToDouble(GpuGroundDamage.Impact::strength).max().orElseThrow();
        float lightest = (float) charred.stream().mapToDouble(GpuGroundDamage.Impact::strength).min().orElseThrow();
        assertEquals(1, darkest, .001f, "The target hex is charred black");
        assertTrue(lightest < darkest, "The outer ring is lighter, as its damage is lower");
    }

    @Test
    void inVacuumItIsADudAndInThinAirItCharsLighter() {
        assertEquals(0, marks(FuelAirStrength.NONE, 2).size(), "Vacuum: no fuel-air burn at all");
        float full = (float) marks(FuelAirStrength.FULL, 2).stream().mapToDouble(GpuGroundDamage.Impact::strength)
              .max().orElseThrow();
        float thin = (float) marks(FuelAirStrength.HALF, 2).stream().mapToDouble(GpuGroundDamage.Impact::strength)
              .max().orElseThrow();
        assertTrue(thin < full, "Thin air halves its damage, so it chars lighter: " + thin + " against " + full);
    }

    /**
     * The terrain a fuel-air round changes (cleared woods, fires, rubble) shows its change when the fuel lights, a
     * quarter second after landing, not at landing as another shell's does.
     */
    @Test
    void itsTerrainChangesShowAtIgnitionNotAtLanding() {
        ResolvedAttack.Shot highExplosive = new ResolvedAttack.Shot("", Set.of("M_STANDARD"), true, false, 1, 1, true,
              null, null, null, false, 20, false, false, false, null, 20);
        assertEquals(0, terrainShownAfterContact(highExplosive), .04f, "A high-explosive shell's show at landing");
        assertEquals(GpuArtilleryBlast.FUEL_AIR_IGNITION_SECONDS, terrainShownAfterContact(fuelAir()), .04f,
              "A fuel-air round's when its fuel lights");
    }

    /** Seconds of the attack's own clock after the round lands before the terrain it changed shows. */
    private static float terrainShownAfterContact(ResolvedAttack.Shot shot) {
        var attacker = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var before = UnitPlaybackFixture.scene(attacker, target);
        var tile = new BoardScene.Tile(new Coords(0, 0), 3, -1, false, 0, null, null, List.of(), List.of());
        var after = new BoardScene(0, 1, 1, List.of(tile), List.of(attacker, target), List.of(), -1, "OFFBOARD",
              List.of());
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
              new UnitLocation(target.id(), target.location().coords(), 0, 0, 0), Targetable.TYPE_HEX_ARTILLERY, 0,
              "ISArrowIV", 4, true, List.of(), shot);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(before), new BoardScene.Combat(result, attacker, target,
              target.location()), new BoardScene.SceneUpdate(after)), after, ignored -> false);
        for (int step = 0; step < 600; step++) {
            playback.advance(1 / 120f, UnitMotion.Speed.NORMAL);
            UnitAttack attack = playback.attack();
            boolean changed = playback.present(after).tiles().equals(after.tiles());
            if (attack != null && changed) {
                return attack.seconds - attack.contactSeconds;
            }
            assertFalse(changed, "The terrain changed before the attack began");
        }
        throw new AssertionError("The terrain never changed");
    }

    private static ResolvedAttack.Shot fuelAir() {
        return new ResolvedAttack.Shot("", Set.of("M_FAE"), true, false, 1, 1, true, null, null, null, false, 20, false,
              false, false, null, 20);
    }

    /** The marks an Arrow IV fuel-air round has left {@code seconds} after landing, in the given atmosphere. */
    private static List<GpuGroundDamage.Impact> marks(FuelAirStrength strength, float seconds) {
        var attack = GroundDamagePlaybackTest.shot("ISArrowIV", false, fuelAir());
        var blasts = new GpuArtilleryBlast();
        blasts.setFuelAirStrength(strength);
        blasts.detonate(BoardGeometry.center(attack.event.destination().coords(), 0), fuelAir(), attack, 9);
        blasts.advance(seconds);
        var marks = new ArrayList<GpuGroundDamage.Impact>();
        blasts.groundMarks(marks::add);

        return marks;
    }
}
