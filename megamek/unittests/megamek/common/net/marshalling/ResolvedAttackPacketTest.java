/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.net.marshalling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.units.Tank;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResolvedAttackPacketTest {
    @Test
    void crewLossSurvivesImpactCopiesAndTheNetwork() throws Exception {
        var location = new UnitLocation(1, new Coords(2, 3), 0, 0, 0);
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.DEATH, location, location,
              Targetable.TYPE_ENTITY, -1, "", 0, true).withCrewLoss(true)
              .withImpacts(List.of(new ResolvedAttack.Impact("HD", false, 1)));
        var bytes = new ByteArrayOutputStream();
        var marshaller = new NativeSerializationMarshaller();
        marshaller.marshall(new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result), bytes);
        var received = (ResolvedAttack) marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray())).getObject(0);
        assertEquals(result, received);
        assertTrue(received.crewLoss());
    }

    @ParameterizedTest
    @CsvSource({
          "ISSmallLaser,       ,                      3,  0",
          "ISLargeLaser,       ,                      8,  0",
          "ISPPC,              ,                     10,  0",
          "ISLightPlasmaRifle,  ISLightPlasmaRifleAmmo,  4,  0",
          "ISPlasmaRifle,       ISPlasmaRifleAmmo,      10,  0",
          "ISHeavyPlasmaRifle,  ISHeavyPlasmaRifleAmmo, 12,  0",
          "CLPlasmaCannon,      CLPlasmaCannonAmmo,      0,  0",
          "ISAC2,              ,                      2,  0",
          "ISAC20,             ,                     20,  0",
          "ISLRM5,             IS Ammo LRM-5,          1,  5",
          "ISLRM15,            IS Ammo LRM-15,         1, 15",
          "ISSRM2,             IS Ammo SRM-2,          2,  2",
          "ISSRM6,             IS Ammo SRM-6,          2,  6",
          "ISThunderbolt5,      IS Ammo Thunderbolt-5, 5,  1",
          "ISThunderbolt20,     IS Ammo Thunderbolt-20,20,  1",
          "ISThumper,          ,                     15,  0",
          "ISSniper,           ,                     20,  0",
          "ISLongTom,          ISLongTomAmmo,         25,  0",
          "ISArrowIV,          ISArrowIVAmmo,         20,  1",
          "ISCruiseMissile50,   ,                     50,  1",
          "ISCruiseMissile120,  ,                    120,  1",
          "Flamer,             ,                      2,  0",
          "CLLightTAG,          ,                      0,  0"
    })
    void nominalDamageAndProjectileCountSurviveResolutionAndSerialization(String weapon, String ammoName,
          double damage, int missiles) throws Exception {
        var owner = new Tank();
        var gun = Mounted.createMounted(owner, EquipmentType.get(weapon));
        if (ammoName != null) { gun.setLinked(Mounted.createMounted(owner, EquipmentType.get(ammoName))); }
        var shot = ResolvedAttack.Shot.capture(gun);
        assertEquals(damage, shot.damagePerHit());
        assertEquals(missiles, shot.missiles());
        boolean plasma = weapon.contains("Plasma");
        assertEquals(plasma, shot.plasma());
        var origin = new UnitLocation(1, new Coords(2, 3), 0, 0, 0);
        var landing = new UnitLocation(2, new Coords(2, 1), 0, 0, 0);
        var ammo = ammoName == null ? null : (AmmoType) EquipmentType.get(ammoName);
        shot = shot.withResolution(ammo, 0).withInterception(UUID.randomUUID(), 0)
              .asDefensive().withTrajectory(origin, landing);
        assertEquals(damage, shot.damagePerHit());
        assertEquals(missiles, shot.missiles());
        assertEquals(plasma, shot.plasma(), "Weapon identity survives every resolution copy");
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT, origin, landing, Targetable.TYPE_ENTITY,
              0, weapon, 0, false, List.of(new ResolvedAttack.Mount(1, 0, shot)), shot);
        var bytes = new ByteArrayOutputStream();
        var marshaller = new NativeSerializationMarshaller();
        marshaller.marshall(new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result), bytes);
        assertEquals(result, marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray())).getObject(0));
    }

    @ParameterizedTest
    @CsvSource({
          "Davy Crockett-M Long Tom Ammo, 1",
          "Ammo Alamo,                    2",
          "Ammo Santa Anna,               3",
          "Ammo AR10 Santa Anna,          3",
          "Ammo Peacemaker,               4",
          "Ammo AR10 Peacemaker,          4"
    })
    void nuclearAmmoIdentitySurvivesCaptureResolutionCopiesAndNetwork(String ammoName, int nuclearType) throws Exception {
        var owner = new Tank();
        var gun = Mounted.createMounted(owner, EquipmentType.get("ISLongTom"));
        var ammo = (AmmoType) EquipmentType.get(ammoName);
        assertNotNull(ammo, "Use registered ammunition, including its real flags and munition set");
        gun.setLinked(Mounted.createMounted(owner, ammo));
        var captured = ResolvedAttack.Shot.capture(gun);
        assertEquals(nuclearType, captured.nuclearType());

        var ordinary = (AmmoType) EquipmentType.get("ISLongTomAmmo");
        gun.setLinked(Mounted.createMounted(owner, ordinary));
        var standard = ResolvedAttack.Shot.capture(gun);
        assertNull(standard.nuclearType());
        assertEquals(nuclearType, captured.nuclearType(), "Later ammo selection cannot change the captured shot");
        assertNull(captured.withResolution(ordinary, 1).nuclearType(), "Actual ordinary ammo clears a nuclear profile");
        assertEquals(nuclearType, captured.withResolution(null, null).nuclearType(), "Missing resolution ammo preserves capture");

        var origin = new UnitLocation(1, new Coords(2, 3), 0, 0, 0);
        var landing = new UnitLocation(-1, new Coords(2, 1), 0, 0, 0);
        var resolved = standard.withResolution(ammo, 0);
        assertEquals(nuclearType, resolved.nuclearType(), "Resolved ammo overrides the earlier ordinary selection");
        var copied = resolved.withDamagePerHit(0).asDefensive().withInterception(UUID.randomUUID(), 0)
              .withTrajectory(origin, landing).withResolution(null, null);
        assertEquals(nuclearType, copied.nuclearType(), "All presentation copies retain the observed nuclear type");
        var scheduled = standard.withNuclearType(nuclearType).withResolution(null, null);
        assertEquals(nuclearType, scheduled.nuclearType(), "A scheduled detonation's explicit type survives resolution");
        assertNull(scheduled.withResolution(ordinary, null).nuclearType());

        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT, origin, landing, Targetable.TYPE_HEX_CLEAR,
              0, "ISLongTom", 0, true, List.of(new ResolvedAttack.Mount(1, 0, captured),
                    new ResolvedAttack.Mount(1, 1, standard), new ResolvedAttack.Mount(1, 2, scheduled)), copied);
        var bytes = new ByteArrayOutputStream();
        var marshaller = new NativeSerializationMarshaller();
        marshaller.marshall(new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result), bytes);
        assertEquals(result, marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray())).getObject(0),
              "Both primary and per-mount nuclear profiles survive the actual network filter");
    }

    @Test
    void machineGunRapidFireIsCapturedBeforePlaybackAndSurvivesTheNetworkFilter() throws Exception {
        var gun = Mounted.createMounted(new Tank(), EquipmentType.get("ISMG"));
        gun.setRapidFire(true);
        var shot = ResolvedAttack.Shot.capture(gun);
        gun.setRapidFire(false);
        assertEquals(true, shot.machineGun());
        assertEquals(true, shot.rapidFire());
        var origin = new UnitLocation(1, new Coords(2, 3), 0, 0, 0);
        var landing = new UnitLocation(2, new Coords(2, 1), 0, 0, 0);
        shot = shot.withResolution(null, null).asDefensive().withTrajectory(origin, landing);
        assertEquals(true, shot.machineGun());
        assertEquals(true, shot.rapidFire());
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT, origin, landing, Targetable.TYPE_ENTITY,
              0, "ISMG", 0, true, List.of(new ResolvedAttack.Mount(1, 0, shot)), shot);
        var bytes = new ByteArrayOutputStream();
        var marshaller = new NativeSerializationMarshaller();
        marshaller.marshall(new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result), bytes);
        assertEquals(result, marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray())).getObject(0));
    }

    @Test
    void resultSurvivesTheActualNetworkSerializationFilter() throws Exception {
        var marshaller = new NativeSerializationMarshaller();
        for (var kind : ResolvedAttack.Kind.values()) {
            var result = new ResolvedAttack(UUID.randomUUID(), kind,
                  new UnitLocation(1, new Coords(2, 3), 4, 0, 0),
                  new UnitLocation(2, new Coords(3, 4), 1, 0, 0), Targetable.TYPE_ENTITY, 5, "ISPPC", 4, true,
                  List.of(new ResolvedAttack.Mount(11, 0), new ResolvedAttack.Mount(12, 3,
                        new ResolvedAttack.Shot("Indirect", Set.of("M_STANDARD"), false, false, 1, 20, true, 12))),
                  new ResolvedAttack.Shot("Indirect", Set.of("M_STANDARD"), false, false, 1, 20, true, 12)
                        .withInterception(UUID.randomUUID(), 5)
                        .withTrajectory(new UnitLocation(1, new Coords(1, 2), 0, 0, 0),
                              new UnitLocation(-1, new Coords(4, 5), 0, 2, 0)))
                  .withImpacts(List.of(new ResolvedAttack.Impact("LA", false, 5), new ResolvedAttack.Impact("RT", true, 7)));
            var bytes = new ByteArrayOutputStream();
            marshaller.marshall(new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result), bytes);
            var packet = marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(PacketCommand.ENTITY_ATTACK_RESOLVED, packet.command());
            assertEquals(result, packet.getObject(0));
        }
    }

    @Test
    void cannonCalibreAndPpcIdentitySurviveResolutionAndTheNetworkFilter() throws Exception {
        var cannon = ResolvedAttack.Shot.capture(Mounted.createMounted(new Tank(), EquipmentType.get("ISLongTomCannon")));
        var origin = new UnitLocation(1, new Coords(2, 3), 0, 0, 0);
        var landing = new UnitLocation(2, new Coords(2, 1), 0, 0, 0);
        var shot = cannon.withResolution(null, null).withTrajectory(origin, landing).asDefensive();
        assertEquals(true, shot.ballistic());
        assertEquals(20, shot.rackSize());
        var ppc = ResolvedAttack.Shot.capture(Mounted.createMounted(new Tank(), EquipmentType.get("ISPPC")))
              .withResolution(null, null).asDefensive();
        assertEquals(true, ppc.ppc());
        assertEquals(false, ppc.ballistic());
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT, origin, landing, Targetable.TYPE_ENTITY,
              0, "ISLongTomCannon", 0, true, List.of(new ResolvedAttack.Mount(1, 0, shot), new ResolvedAttack.Mount(1, 1, ppc)), shot);
        var marshaller = new NativeSerializationMarshaller();
        var bytes = new ByteArrayOutputStream();
        marshaller.marshall(new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result), bytes);
        assertEquals(result, marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray())).getObject(0));
    }
}
