/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A cluster round bursts, and a minefield round's dispenser opens, where the round itself is: its flight ends where it
 * comes down to its opening height, rather than diving on into the ground below a burst that appears above it.
 */
class GpuAirOpeningTest {
    /** A long artillery flight, as a round fired from beyond 17 hexes or from off the board has. */
    private static final int RANGE = 20;

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void theOpeningIsWhereTheFlightComesDownToItsHeight() {
        var origin = new Vector3(0, 0, 0);
        var aim = new Vector3(BoardGeometry.height() * RANGE, 0, 0);
        float height = BoardGeometry.height();
        float progress = GpuArtilleryBlast.openingProgress(
              t -> UnitAttack.projectile(origin, aim, (float) t, true, new Vector3()), aim, height);
        assertTrue(progress > .5f && progress < 1, "On the way down: " + progress);
        assertEquals(height, UnitAttack.projectile(origin, aim, progress, true, new Vector3()).z, .5f);
    }

    @Test
    void aRoundThatNeverClimbsThatHighOrHasNoOpeningOpensOnTheGround() {
        var origin = new Vector3(0, 0, 0);
        var aim = new Vector3(BoardGeometry.height() * RANGE, 0, 0);
        assertEquals(-1, GpuArtilleryBlast.openingProgress(
              t -> UnitAttack.projectile(origin, aim, (float) t, false, new Vector3()), aim, BoardGeometry.height()),
              "A flat flight never comes down from above");
        assertEquals(-1, GpuArtilleryBlast.openingProgress(
              t -> UnitAttack.projectile(origin, aim, (float) t, true, new Vector3()), aim, 0), "A ground burst");
    }

    @Test
    void onlyClusterAndMinefieldRoundsOpenInTheAir() {
        assertEquals(BoardGeometry.height() * 1.3f,
              GpuArtilleryBlast.openingHeight("ISArrowIV", shot("M_CLUSTER")), .01f);
        assertEquals(BoardGeometry.height() * .9f,
              GpuArtilleryBlast.openingHeight("ISArrowIV", shot("M_FASCAM")), .01f);
        assertEquals(0, GpuArtilleryBlast.openingHeight("ISArrowIV", shot("M_STANDARD")));
        assertEquals(0, GpuArtilleryBlast.openingHeight("ISLRM20", shot("M_STANDARD")));
    }

    /**
     * The Arrow IV cluster missile bursts where it comes down to the burst height, short of and above its target, then
     * its spent body falls on to the ground without flame or smoke, and vanishes there.
     */
    @Test
    void anArrowIVClusterMissileBurstsThenItsBodyFallsToTheGround() {
        var launch = missileLaunch("ISArrowIV", shot("M_CLUSTER"));
        assertTrue(GpuMissileEffects.openedInTheAir(launch, 0));
        float above = GpuMissileEffects.openingPoint(launch, 0).z - launch.aims()[0].z;
        assertEquals(BoardGeometry.height() * 1.3f, above, 1, "It bursts at the burst height");
        assertTrue(launch.poweredProgress(0) < 1, "No flame or smoke after the burst");
        assertEquals(1, launch.endProgress(0), "The spent body flies on to the ground");
        assertEquals(launch.aims()[0].z, launch.targets()[0].z, .01f, "and comes to rest on it");
        assertTrue(launch.openingSeconds(0) < launch.endSeconds(0), "It bursts before the body lands");
    }

    @Test
    void anArrowIVMinefieldMissileOpensAtItsDispenserThenItsBodyFallsToTheGround() {
        var launch = missileLaunch("ISArrowIV", shot("M_FASCAM"));
        assertTrue(GpuMissileEffects.openedInTheAir(launch, 0));
        float above = GpuMissileEffects.openingPoint(launch, 0).z - launch.aims()[0].z;
        assertEquals(BoardGeometry.height() * .9f, above, 1, "It opens at the dispenser height");
        assertEquals(1, launch.endProgress(0), "The spent body flies on to the ground");
    }

    @Test
    void aHighExplosiveArrowIVStillFliesIntoTheGround() {
        var launch = missileLaunch("ISArrowIV", shot("M_STANDARD"));
        assertFalse(GpuMissileEffects.openedInTheAir(launch, 0));
    }

    /** Tube artillery's shell stops where its cluster burst opens, as the Arrow IV missile does. */
    @Test
    void aLongTomClusterShellEndsItsFlightAtItsBurst() {
        var shot = attack("ISLongTom", shell("M_CLUSTER"));
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        effects.groundImpacts(List.of(), ignored -> { });
        List<GpuAttackEffects.Trace> flights = effects.emissions(shot);
        assertEquals(1, flights.size());
        GpuAttackEffects.Trace flight = flights.getFirst();
        assertTrue(flight.opening());
        assertEquals(BoardGeometry.height() * 1.3f, flight.target().z - flight.aim().z, 1);
        assertTrue(flight.progress() < 1);
    }

    @Test
    void aHighExplosiveLongTomShellStillFliesIntoTheGround() {
        var shot = attack("ISLongTom", shell("M_STANDARD"));
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        effects.groundImpacts(List.of(), ignored -> { });
        assertFalse(effects.emissions(shot).getFirst().opening());
    }

    private static GpuMissileEffects.Launch missileLaunch(String weaponName, ResolvedAttack.Shot round) {
        var shot = attack(weaponName, round);
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        // The flight is captured when the effects are prepared, as the ground-mark pass does every frame.
        effects.groundImpacts(List.of(), ignored -> { });
        return effects.missileLaunches(shot).getFirst();
    }

    /** An Arrow IV round, which flies as one missile. */
    private static ResolvedAttack.Shot shot(String munition) {
        return round(munition, 1);
    }

    /** A tube artillery shell, which flies as a shell rather than a missile. */
    private static ResolvedAttack.Shot shell(String munition) {
        return round(munition, 0);
    }

    static ResolvedAttack.Shot round(String munition, int missiles) {
        return new ResolvedAttack.Shot("", Set.of(munition), true, false, 1, missiles, true, null, null, null, true, 20,
              false, false, false, null, 20);
    }

    /** An artillery shot across {@link #RANGE} hexes of level ground, like the shared shot fixture but further. */
    static UnitAttack attack(String weaponName, ResolvedAttack.Shot round) {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, RANGE);
        var original = UnitPlaybackFixture.attack(source, target, ResolvedAttack.Kind.SHOT, true).result();
        var event = new ResolvedAttack(new UUID(7, weaponName.hashCode() ^ round.munitions().hashCode()),
              original.kind(), original.attacker(), original.target(), original.targetType(), 0, weaponName, 4, true,
              original.mounts(), round);
        var attack = new UnitAttack(new BoardScene.Combat(event, source, target, target.location()));
        attack.landscape = ray -> {
            if (ray.direction.z >= 0) {
                return null;
            }
            float distance = -ray.origin.z / ray.direction.z;
            return distance < 0 ? null : new BoardGeometry.Hit(new Coords(0, RANGE), distance * distance);
        };
        attack.seconds = attack.contactSeconds;
        return attack;
    }
}
