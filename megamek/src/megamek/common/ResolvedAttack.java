/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import megamek.common.board.BoardLocation;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.units.Entity;
import megamek.common.units.IAero;
import megamek.common.units.UnitLocation;
import megamek.common.weapons.infantry.InfantryWeapon;

/**
 * A transient, server-confirmed visual event. It is not an attack order or another damage/rules calculation.
 * {@code collapses}: the structure hexes whose construction factor this attack's damage took to zero, which the rules
 * bring down at the phase's end. {@code cleared}: the hexes whose woods or jungle its damage thinned or cleared.
 */
public record ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
      int equipmentIndex, String equipmentName, int limb, boolean hit, List<Mount> mounts, Shot shot,
      List<Impact> impacts, boolean crewLoss, List<BoardLocation> collapses, List<BoardLocation> cleared)
      implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public ResolvedAttack {
        mounts = mounts == null ? singleMount(attacker, equipmentIndex) : List.copyOf(mounts);
        impacts = impacts == null ? List.of() : List.copyOf(impacts);
        collapses = collapses == null ? List.of() : List.copyOf(collapses);
        cleared = cleared == null ? List.of() : List.copyOf(cleared);
    }

    public ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
          int equipmentIndex, String equipmentName, int limb, boolean hit, List<Mount> mounts, Shot shot,
          List<Impact> impacts, boolean crewLoss, List<BoardLocation> collapses) {
        this(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit, mounts, shot, impacts,
              crewLoss, collapses, List.of());
    }

    public ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
          int equipmentIndex, String equipmentName, int limb, boolean hit, List<Mount> mounts, Shot shot,
          List<Impact> impacts, boolean crewLoss) {
        this(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit, mounts, shot, impacts,
              crewLoss, List.of());
    }

    public ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
          int equipmentIndex, String equipmentName, int limb, boolean hit, List<Mount> mounts, Shot shot,
          List<Impact> impacts) {
        this(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit, mounts, shot, impacts, false);
    }

    public ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
          int equipmentIndex, String equipmentName, int limb, boolean hit, List<Mount> mounts, Shot shot) {
        this(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit, mounts, shot, List.of());
    }

    /** Observed incoming locations; damage weights distribute visuals, never recalculate hits or damage. */
    public record Impact(String location, boolean rear, int weight) implements Serializable { }

    public ResolvedAttack withImpacts(List<Impact> locations) {
        return new ResolvedAttack(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit,
              mounts, shot, locations, crewLoss, collapses, cleared);
    }

    /** The server destroyed this unit because its crew died or left, rather than because its body was destroyed. */
    public ResolvedAttack withCrewLoss(boolean lost) {
        return new ResolvedAttack(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit,
              mounts, shot, impacts, lost, collapses, cleared);
    }

    /** The structure hexes this attack brought down ({@link #collapses}). */
    public ResolvedAttack withCollapses(List<BoardLocation> hexes) {
        return new ResolvedAttack(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit,
              mounts, shot, impacts, crewLoss, hexes, cleared);
    }

    /** The hexes whose woods or jungle this attack thinned or cleared ({@link #cleared}). */
    public ResolvedAttack withCleared(List<BoardLocation> hexes) {
        return new ResolvedAttack(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit,
              mounts, shot, impacts, crewLoss, collapses, hexes);
    }

    public ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
          int equipmentIndex, String equipmentName, int limb, boolean hit, List<Mount> mounts) {
        this(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit, mounts, null);
    }

    public ResolvedAttack(UUID id, Kind kind, UnitLocation attacker, UnitLocation target, int targetType,
          int equipmentIndex, String equipmentName, int limb, boolean hit) {
        this(id, kind, attacker, target, targetType, equipmentIndex, equipmentName, limb, hit, singleMount(attacker, equipmentIndex));
    }

    /** Identity of a physical gun, including the owning fighter when a squadron fires a logical group. */
    public record Mount(int entityId, int equipmentIndex, Shot shot) implements Serializable {
        public Mount(int entityId, int equipmentIndex) { this(entityId, equipmentIndex, null); }
    }

    /** Correlates authorized counter-fire with one incoming salvo, including a neighboring unit's APDS. */
    public record Interception(UUID id, int missiles) implements Serializable { }

    /** Observed firing configuration. Damage per hit is nominal projectile/beam damage, before target modifiers. */
    public record Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
          int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
          boolean ballistic, int rackSize, boolean ppc, boolean machineGun, boolean rapidFire,
          Interception interception, double damagePerHit, boolean plasma, Integer nuclearType) implements Serializable {
        public Shot { munitions = Set.copyOf(munitions); }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
              boolean ballistic, int rackSize, boolean ppc, boolean machineGun, boolean rapidFire,
              Interception interception, double damagePerHit, boolean plasma) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, damagePerHit, plasma, null);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
              boolean ballistic, int rackSize, boolean ppc, boolean machineGun, boolean rapidFire,
              Interception interception, double damagePerHit) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, damagePerHit, false);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
              boolean ballistic, int rackSize, boolean ppc, boolean machineGun, boolean rapidFire,
              Interception interception) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, 0);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
              boolean ballistic, int rackSize, boolean ppc, boolean machineGun, boolean rapidFire) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, null);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
              boolean ballistic, int rackSize, boolean ppc) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, false, false);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact,
              boolean ballistic, int rackSize) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact, ballistic, rackSize, false);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits, UnitLocation launch, UnitLocation impact) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact, false, 0);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect, Integer missileHits) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, null, null);
        }

        public Shot(String mode, Set<String> munitions, boolean artillery, boolean defensive, int shots,
              int missiles, boolean indirect) {
            this(mode, munitions, artillery, defensive, shots, missiles, indirect, null);
        }

        /** Null means the rules resolved attack value/damage, without an individual missile-hit count. */
        public Shot withResolution(AmmoType ammo, Integer hits) {
            return new Shot(mode, ammo == null ? munitions : ammo.getMunitionType().stream()
                  .map(Enum::name).collect(java.util.stream.Collectors.toSet()), artillery, defensive, shots,
                  missiles, indirect, hits, launch, impact, ballistic, rackSize, ppc, machineGun, rapidFire, interception,
                  damagePerHit, plasma, ammo == null ? nuclearType : nuclearType(ammo));
        }

        /** The same shot dealing {@code damage} per hit, such as an artillery shell's damage in an outer ring. */
        public Shot withDamagePerHit(double damage) {
            return new Shot(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, damage, plasma, nuclearType);
        }

        public Shot asDefensive() {
            return new Shot(mode, munitions, artillery, true, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, damagePerHit, plasma, nuclearType);
        }

        public Shot withInterception(UUID id, int count) {
            return new Shot(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, id == null ? null : new Interception(id, Math.max(0, count)), damagePerHit, plasma, nuclearType);
        }

        /** Artillery supplies its observed launch and resolved landing, including scatter. No client re-roll. */
        public Shot withTrajectory(UnitLocation origin, UnitLocation destination) {
            return new Shot(mode, munitions, true, defensive, shots, missiles, true, missileHits, origin, destination,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, damagePerHit, plasma, nuclearType);
        }

        /** The stock nuclear type observed by the server, not inferred from nominal weapon damage. */
        public Shot withNuclearType(int type) {
            return new Shot(mode, munitions, artillery, defensive, shots, missiles, indirect, missileHits, launch, impact,
                  ballistic, rackSize, ppc, machineGun, rapidFire, interception, damagePerHit, plasma, type);
        }

        private static Integer nuclearType(AmmoType ammo) {
            if (ammo == null) { return null; }
            if (ammo.getMunitionType().contains(AmmoType.Munitions.M_DAVY_CROCKETT_M)) { return 1; }
            if (ammo.getAmmoType() == AmmoType.AmmoTypeEnum.ALAMO) { return 2; }
            if (ammo.hasFlag(AmmoType.F_SANTA_ANNA)) { return 3; }
            if (ammo.hasFlag(AmmoType.F_PEACEMAKER)) { return 4; }
            return null;
        }

        public static Shot capture(Mounted<?> mount) {
            if (!(mount instanceof WeaponMounted weapon)) { return null; }
            var type = weapon.getType();
            var ammo = weapon.getLinkedAmmo() == null ? null : weapon.getLinkedAmmo().getType();
            Set<String> munitions = ammo != null ? ammo.getMunitionType().stream()
                  .map(Enum::name).collect(java.util.stream.Collectors.toSet()) : Set.of();
            boolean artillery = type.hasFlag(WeaponType.F_ARTILLERY);
            boolean missile = type.hasFlag(WeaponType.F_MISSILE) || type.hasFlag(WeaponType.F_CRUISE_MISSILE)
                  || type.hasFlag(WeaponType.F_ARROW_IV);
            int missiles = missile ? artillery || type.hasFlag(WeaponType.F_LARGE_MISSILE) ? 1 : Math.max(1, type.getRackSize()) : 0;
            // Cluster rack size counts projectiles; artillery rack size is the damage of one shell.
            // Thunderbolt warhead damage comes from its ammo, not its (single-projectile) rack size.
            double damage = type instanceof InfantryWeapon infantry ? infantry.getInfantryDamage()
                  : type.getDamage() == WeaponType.DAMAGE_ARTILLERY ? type.getRackSize() : type.getDamage(0);
            if (damage < 0) { damage = ammo != null ? ammo.getDamagePerShot() : missiles > 0 ? 1 : 0; }
            return new Shot(weapon.curMode().getName(), munitions, artillery,
                  type.hasFlag(WeaponType.F_AMS) || type.hasFlag(WeaponType.F_AMS_BAY), weapon.getCurrentShots(), missiles,
                  weapon.curMode().isIndirect(), null, null, null,
                  type.hasFlag(WeaponType.F_BALLISTIC), type.getRackSize(), type.hasFlag(WeaponType.F_PPC),
                  type.hasFlag(WeaponType.F_MG), weapon.isRapidFire(), null, damage,
                  type.hasFlag(WeaponType.F_PLASMA) || type.hasFlag(WeaponType.F_PLASMA_MFUK), nuclearType(ammo));
        }
    }

    private static List<Mount> singleMount(UnitLocation attacker, int equipmentIndex) {
        return equipmentIndex < 0 ? List.of() : List.of(new Mount(attacker.entityId(), equipmentIndex));
    }

    /** Resolve once while handling the confirmed attack. Presentation never rebuilds group membership. */
    public static List<Mount> captureMounts(Entity attacker, int equipmentIndex) {
        return physicalMounts(equipmentIndex < 0 ? null : attacker.getEquipment(equipmentIndex))
              .map(mount -> new Mount(mount.getEntity().getId(), mount.getEquipmentNum(), Shot.capture(mount))).distinct().toList();
    }

    private static Stream<? extends Mounted<?>> physicalMounts(Mounted<?> mount) {
        if (mount == null || mount.isInoperable()) {
            return Stream.empty();
        }
        if (mount instanceof WeaponMounted weapon) {
            if (weapon.isWeaponGroup() && mount.getEntity() instanceof IAero aero) {
                return aero.getWeaponGroupMembers(weapon).stream().flatMap(ResolvedAttack::physicalMounts);
            }
            if (!weapon.getBayWeapons().isEmpty()) {
                return weapon.getBayWeapons().stream().flatMap(ResolvedAttack::physicalMounts);
            }
        }
        return Stream.of(mount);
    }

    public enum Kind { SHOT, PUNCH, KICK, PUSH, CLUB, DEATH, BRUSH_OFF, SHAKE_OFF, STOP_SWARM, CHARGE, DFA, RAM }
}
