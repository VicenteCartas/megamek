/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import megamek.client.ui.Messages;
import megamek.common.CriticalSlot;
import megamek.common.equipment.EquipmentMode;
import megamek.common.equipment.GunEmplacement;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.rules.SettableHeat;
import megamek.common.units.Aero;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.ConvFighter;
import megamek.common.units.Crew;
import megamek.common.units.DamageEditApplier;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadMek;
import megamek.common.units.QuadVee;
import megamek.common.units.Tank;
import megamek.common.units.VTOL;
import megamek.common.weapons.attacks.InfantryAttack;

/**
 * The core of a unit's damage for the lobby's own editor: each location's armor, rear armor and structure with its
 * equipment and system hits, and the unit's general state (crew hits, heat, fuel, conditions and the systems that sit
 * in no location), as the Swing {@code UnitEditorDialog} shows them. Read from the unit on the Swing thread, edited by
 * the view on its own, and written back by {@link #apply} as a {@link DamageEditSpec} that carries only what this form
 * edits; ammunition, equipment modes and the pre-existing damage roller stay in the Swing editor.
 */
public final class UnitDamageForm {
    /** The most hits a crew member can be given here, as in the Swing editor. */
    public static final int MAX_CREW_HITS = Crew.DEATH;

    /** How a value is edited. */
    public enum Kind {
        /** A number from 0 to its most. */
        STEPPER,
        /** Hit boxes: the value is how many of its most are hit. */
        BOXES,
        /** On (1) or off (0). */
        TOGGLE
    }

    /** What Repair all does to a value. */
    private enum Repair {
        FULL, CLEAR, KEEP
    }

    @FunctionalInterface
    private interface Writer {
        void write(DamageEditSpec spec, int value);
    }

    /** One edited value: its label, kind and bounds, the value it was read with and the value now. */
    public static final class Value {
        private final String label;
        private final Kind kind;
        private final int max;
        private final int original;
        private final Repair repair;
        private final Writer writer;
        private Consumer<Value> changed;
        private int value;

        private Value(String label, Kind kind, int value, int max, Repair repair, Writer writer) {
            this.label = label;
            this.kind = kind;
            this.max = Math.max(0, max);
            this.original = Math.clamp(value, 0, this.max);
            this.value = original;
            this.repair = repair;
            this.writer = writer;
        }

        public String label() {
            return label;
        }

        public Kind kind() {
            return kind;
        }

        public int max() {
            return max;
        }

        public int original() {
            return original;
        }

        public int value() {
            return value;
        }

        /** Sets the value, brought within 0 and its most. */
        public void value(int next) {
            int clamped = Math.clamp(next, 0, max);
            if (clamped != value) {
                value = clamped;
                if (changed != null) {
                    changed.accept(this);
                }
            }
        }

        public boolean on() {
            return value > 0;
        }

        private void repair() {
            switch (repair) {
                case FULL -> value(max);
                case CLEAR -> value(0);
                default -> {
                }
            }
        }
    }

    /** A location: its armor, rear armor and structure when it has them, then its toggles and hits in order. */
    public static final class Place {
        private final int location;
        private final String abbr;
        private final String name;
        private final List<Value> values = new ArrayList<>();
        private Value armor;
        private Value rear;
        private Value internal;

        private Place(int location, String abbr, String name) {
            this.location = location;
            this.abbr = abbr;
            this.name = name;
        }

        public int location() {
            return location;
        }

        /** The location's abbreviation, which is its paperdoll region's code. */
        public String abbr() {
            return abbr;
        }

        public String name() {
            return name;
        }

        /** The armor (front armor where there is rear armor), or null. */
        public Value armor() {
            return armor;
        }

        /** The rear armor, or null. */
        public Value rear() {
            return rear;
        }

        /** The structure, or null. */
        public Value internal() {
            return internal;
        }

        /** Every value of the location, armor first. */
        public List<Value> values() {
            return Collections.unmodifiableList(values);
        }
    }

    private final Entity entity;
    private final Map<Integer, Place> places = new TreeMap<>();
    private final List<Value> general = new ArrayList<>();
    private Value structuralIntegrity;

    private UnitDamageForm(Entity entity) {
        this.entity = entity;
        readLocations();
        readCrew();
        readHeat();
        readConditions();
        readEquipment();
        readSystems();
    }

    /**
     * Whether the lobby's own editor covers the unit: Meks, ProtoMeks, vehicles and fighters, the units with a
     * paperdoll and no large-craft systems. Infantry, buildings, gun emplacements, capital fighters and large craft
     * stay in the Swing editor.
     */
    public static boolean supported(Entity entity) {
        if (entity == null || entity instanceof Infantry || entity instanceof GunEmplacement) {
            return false;
        }
        if (entity instanceof Mek || entity instanceof ProtoMek || entity instanceof Tank) {
            return true;
        }
        return (entity instanceof AeroSpaceFighter || entity instanceof ConvFighter) && !entity.isCapitalFighter();
    }

    /** Reads the unit's damage; call on the thread that owns the unit. */
    public static UnitDamageForm of(Entity entity) {
        if (!supported(entity)) {
            throw new IllegalArgumentException("Not edited by the lobby's damage editor: " + entity);
        }
        return new UnitDamageForm(entity);
    }

    public Entity entity() {
        return entity;
    }

    /** The locations with something to edit, in the unit's order. */
    public List<Place> places() {
        return List.copyOf(places.values());
    }

    /** The location whose paperdoll region has {@code code}, or null. */
    public Place place(String code) {
        return places.values().stream().filter(place -> place.abbr.equals(code)).findFirst().orElse(null);
    }

    /** The values that sit in no location: structural integrity, fuel, crew, heat, systems and conditions. */
    public List<Value> general() {
        return Collections.unmodifiableList(general);
    }

    /** An aerospace unit's structural integrity, or null. */
    public Value structuralIntegrity() {
        return structuralIntegrity;
    }

    /** Whether anything differs from the unit as read. */
    public boolean changed() {
        return values().stream().anyMatch(value -> value.value != value.original);
    }

    /** Puts every armor and structure value to full and clears every hit, mark, heat and crew hit. */
    public void repairAll() {
        values().forEach(Value::repair);
    }

    /** The edits as a spec that leaves everything this form does not show alone. */
    public DamageEditSpec spec() {
        DamageEditSpec spec = new DamageEditSpec();
        spec.entityId = entity.getId();
        int locations = entity.locations();
        spec.internal = new Integer[locations];
        spec.armor = new Integer[locations];
        spec.rearArmor = new Integer[locations];
        if (entity.getCrew() != null) {
            spec.crewHits = new Integer[entity.getCrew().getSlotCount()];
        }
        if (entity instanceof Mek) {
            spec.locationBreached = new Boolean[locations];
            spec.locationBlownOff = new Boolean[locations];
            boolean tripod = entity.hasETypeFlag(Entity.ETYPE_TRIPOD_MEK);
            spec.actuatorHits = tripod ? new Integer[5][4] : entity instanceof QuadVee ? new Integer[4][5]
                  : new Integer[4][4];
        } else if (entity instanceof Tank) {
            spec.stabilizerHits = new Integer[locations];
        } else if (entity instanceof ProtoMek) {
            spec.protoHits = new Integer[locations];
        }
        for (Value value : values()) {
            value.writer.write(spec, value.value);
        }
        return spec;
    }

    /** Writes the edits to the unit; call on the thread that owns the unit. */
    public void apply() {
        new DamageEditApplier(entity, spec()).applyToEntity();
    }

    private List<Value> values() {
        List<Value> all = new ArrayList<>();
        places.values().forEach(place -> all.addAll(place.values));
        all.addAll(general);
        return all;
    }

    // ------------------------------------------------------------------------------------------------ reading

    private void readLocations() {
        boolean aero = entity.isAero();
        for (int location = 0; location < entity.locations(); location++) {
            int originalArmor = entity.getOArmor(location);
            boolean editsInternal = !aero && entity.getOInternal(location) > 0;
            if (originalArmor <= 0 && !editsInternal) {
                continue;
            }
            Place place = place(location);
            int at = location;
            if (originalArmor > 0) {
                boolean hasRear = entity.hasRearArmor(location);
                place.armor = add(place, text(hasRear ? "UnitEditorDialog.armorFront" : "UnitEditorDialog.armor"),
                      Kind.STEPPER, Math.max(entity.getArmor(location, false), 0), originalArmor, Repair.FULL,
                      (spec, value) -> spec.armor[at] = value);
                if (hasRear) {
                    place.rear = add(place, text("UnitEditorDialog.armorRear"), Kind.STEPPER,
                          Math.max(entity.getArmor(location, true), 0), Math.max(entity.getOArmor(location, true), 0),
                          Repair.FULL, (spec, value) -> spec.rearArmor[at] = value);
                }
            }
            if (editsInternal) {
                place.internal = add(place, text("UnitEditorDialog.internal"), Kind.STEPPER,
                      Math.max(entity.getInternal(location), 0), entity.getOInternal(location), Repair.FULL,
                      (spec, value) -> spec.internal[at] = value);
            }
            if (entity instanceof Mek mek) {
                add(place, text("UnitEditorDialog.location.breached"), Kind.TOGGLE,
                      entity.getLocationStatus(location) == ILocationExposureStatus.BREACHED ? 1 : 0, 1, Repair.CLEAR,
                      (spec, value) -> spec.locationBreached[at] = value > 0);
                if (mek.isArm(location) || mek.locationIsLeg(location)) {
                    Value blownOff = add(place, text("UnitEditorDialog.location.blownOff"), Kind.TOGGLE,
                          entity.isLocationBlownOff(location) ? 1 : 0, 1, Repair.CLEAR,
                          (spec, value) -> spec.locationBlownOff[at] = value > 0);
                    // a limb brought back comes back whole, as in the Swing editor
                    blownOff.changed = value -> {
                        if (!value.on()) {
                            for (Value layer : new Value[] { place.armor, place.rear, place.internal }) {
                                if (layer != null) {
                                    layer.value(layer.max);
                                }
                            }
                        }
                    };
                }
            }
        }
        if (aero) {
            Aero unit = (Aero) entity;
            structuralIntegrity = new Value(text("UnitEditorDialog.structuralIntegrity"), Kind.STEPPER,
                  Math.max(unit.getSI(), 0), unit.getOSI(), Repair.FULL, (spec, value) -> spec.internal[0] = value);
            general.add(structuralIntegrity);
            general.add(new Value(text("UnitEditorDialog.status.fuel"), Kind.STEPPER,
                  Math.max(unit.getCurrentFuel(), 0), Math.max(unit.getFuel(), unit.getCurrentFuel()), Repair.FULL,
                  (spec, value) -> spec.fuel = value));
        }
    }

    private void readCrew() {
        Crew crew = entity.getCrew();
        if (crew == null || crew.getSlotCount() < 1) {
            return;
        }
        for (int slot = 0; slot < crew.getSlotCount(); slot++) {
            if (crew.isMissing(slot)) {
                continue;
            }
            String label = crew.getSlotCount() > 1
                  ? String.format(text("UnitEditorDialog.crewHitsFor"), crew.getNameAndRole(slot))
                  : text("UnitEditorDialog.crewHits");
            int at = slot;
            target(entity instanceof Mek ? Mek.LOC_HEAD : Entity.LOC_NONE).add(new Value(label, Kind.STEPPER,
                  Math.min(crew.getHits(slot), MAX_CREW_HITS), MAX_CREW_HITS, Repair.CLEAR,
                  (spec, value) -> spec.crewHits[at] = value));
        }
    }

    private void readHeat() {
        if (!entity.tracksHeat()) {
            return;
        }
        int heat = Math.max(entity.heat, 0);
        target(entity instanceof Mek ? Mek.LOC_CENTER_TORSO : Entity.LOC_NONE).add(new Value(
              text("UnitEditorDialog.heat"), Kind.STEPPER, heat,
              SettableHeat.maximum(entity.getGame(), heat, entity.getHeatCapacityWithWater()), Repair.CLEAR,
              (spec, value) -> spec.heat = value));
    }

    private void readConditions() {
        general.add(toggle("UnitEditorDialog.status.shutdown", entity.isShutDown(),
              (spec, value) -> spec.shutdown = value > 0));
        boolean canLieDown = !entity.isAero() && !entity.isAirborne() && !entity.isAirborneVTOLorWIGE();
        if (canLieDown && entity.isMek()) {
            general.add(toggle("UnitEditorDialog.status.prone", entity.isProne(),
                  (spec, value) -> spec.prone = value > 0));
        }
        if (canLieDown && (entity.isMek() || entity.isVehicle())) {
            general.add(toggle("UnitEditorDialog.status.hullDown", entity.isHullDown(),
                  (spec, value) -> spec.hullDown = value > 0));
        }
        if (entity.canHide()) {
            general.add(toggle("UnitEditorDialog.status.hidden", entity.isHidden(),
                  (spec, value) -> spec.hidden = value > 0));
        }
        if (entity.hasStealth()) {
            boolean on = false;
            for (MiscMounted stealth : entity.getMiscEquipment(MiscType.F_STEALTH)) {
                on |= EquipmentMode.getMode("On").equals(stealth.curMode());
            }
            general.add(toggle("UnitEditorDialog.status.stealth", on, (spec, value) -> spec.stealth = value > 0));
        }
    }

    private void readEquipment() {
        List<Integer> bayMembers = new ArrayList<>();
        for (WeaponMounted bay : entity.getWeaponBayList()) {
            for (WeaponMounted member : bay.getBayWeapons()) {
                bayMembers.add(entity.getEquipmentNum(member));
            }
        }
        for (Mounted<?> mounted : entity.getEquipment()) {
            int number = entity.getEquipmentNum(mounted);
            if (mounted.getLocation() == Entity.LOC_NONE || !mounted.getType().isHittable()
                  || mounted.isWeaponGroup() || mounted.getType() instanceof InfantryAttack
                  || bayMembers.contains(number)) {
                continue;
            }
            int slots = mounted.getNumCriticalSlots();
            int hits = entity.getDamagedCriticalSlots(CriticalSlot.TYPE_EQUIPMENT, number, mounted.getLocation());
            if (mounted.isSplit()) {
                hits += entity.getDamagedCriticalSlots(CriticalSlot.TYPE_EQUIPMENT, number,
                      mounted.getSecondLocation());
            }
            if (mounted.getType() instanceof MiscType && mounted.getType().hasFlag(MiscType.F_PARTIAL_WING)) {
                hits = entity.getDamagedCriticalSlots(CriticalSlot.TYPE_EQUIPMENT, number, Mek.LOC_LEFT_TORSO)
                      + entity.getDamagedCriticalSlots(CriticalSlot.TYPE_EQUIPMENT, number, Mek.LOC_RIGHT_TORSO);
            }
            if (!(entity instanceof Mek)) {
                slots = 1;
                hits = Math.min(hits, 1);
            }
            String label = mounted.getName();
            if (mounted.isSplit()) {
                label += " (" + entity.getLocationAbbr(mounted.getLocation()) + "/"
                      + entity.getLocationAbbr(mounted.getSecondLocation()) + ")";
            }
            target(mounted.getLocation()).add(new Value(label, Kind.BOXES, hits, Math.max(slots, 1), Repair.CLEAR,
                  (spec, value) -> spec.equipmentHits.put(number, value)));
        }
    }

    private void readSystems() {
        if (entity instanceof Mek) {
            readMekSystems();
        } else if (entity instanceof VTOL vtol) {
            readVtolSystems(vtol);
        } else if (entity instanceof Tank tank) {
            readTankSystems(tank);
        } else if (entity instanceof ProtoMek) {
            readProtoSystems();
        } else if (entity instanceof Aero aero) {
            general.add(boxes("UnitEditorDialog.avionics", aero.getAvionicsHits(), 3,
                  (spec, value) -> spec.avionicsHits = value));
            general.add(boxes("UnitEditorDialog.fcs", aero.getFCSHits(), 3, (spec, value) -> spec.fcsHits = value));
            general.add(boxes("UnitEditorDialog.sensor", aero.getSensorHits(), 3,
                  (spec, value) -> spec.sensorHits = value));
            general.add(boxes("UnitEditorDialog.engine", aero.getEngineHits(), 3,
                  (spec, value) -> spec.engineHits = value));
            general.add(boxes("UnitEditorDialog.landingGear", aero.isGearHit() ? 1 : 0, 1,
                  (spec, value) -> spec.gearHits = value));
            general.add(boxes("UnitEditorDialog.lifeSupport", aero.hasLifeSupport() ? 0 : 1, 1,
                  (spec, value) -> spec.lifeSupportHits = value));
        }
    }

    private void readMekSystems() {
        int[] hits = new int[7];
        int[] slots = new int[7];
        int[] systems = { Mek.SYSTEM_ENGINE, Mek.SYSTEM_ENGINE, Mek.SYSTEM_ENGINE, Mek.SYSTEM_GYRO,
              Mek.SYSTEM_SENSORS, Mek.SYSTEM_LIFE_SUPPORT, Mek.SYSTEM_COCKPIT };
        int[] engines = { Mek.LOC_CENTER_TORSO, Mek.LOC_LEFT_TORSO, Mek.LOC_RIGHT_TORSO };
        for (int location = 0; location < entity.locations(); location++) {
            for (int index = 0; index < systems.length; index++) {
                if (index < 3 && location != engines[index]) {
                    continue;
                }
                hits[index] += entity.getDamagedCriticalSlots(CriticalSlot.TYPE_SYSTEM, systems[index], location);
                slots[index] += entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, systems[index], location);
            }
        }
        String engine = "UnitEditorDialog.engine";
        target(Mek.LOC_CENTER_TORSO).add(boxes(engine, hits[0], slots[0],
              (spec, value) -> spec.centerEngineHits = value));
        if (entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_ENGINE, Mek.LOC_RIGHT_TORSO) > 0) {
            target(Mek.LOC_LEFT_TORSO).add(boxes(engine, hits[1], slots[1],
                  (spec, value) -> spec.leftEngineHits = value));
            target(Mek.LOC_RIGHT_TORSO).add(boxes(engine, hits[2], slots[2],
                  (spec, value) -> spec.rightEngineHits = value));
        }
        target(Mek.LOC_CENTER_TORSO).add(boxes("UnitEditorDialog.gyro", hits[3], slots[3],
              (spec, value) -> spec.gyroHits = value));
        target(Mek.LOC_HEAD).add(boxes("UnitEditorDialog.sensor", hits[4], slots[4],
              (spec, value) -> spec.sensorHits = value));
        target(Mek.LOC_HEAD).add(boxes("UnitEditorDialog.lifeSupport", hits[5], slots[5],
              (spec, value) -> spec.lifeSupportHits = value));
        target(Mek.LOC_HEAD).add(boxes("UnitEditorDialog.cockpit", hits[6], slots[6],
              (spec, value) -> spec.cockpitHits = value));
        if (entity instanceof LandAirMek) {
            for (int location = 0; location < entity.locations(); location++) {
                int at = location;
                int avionics = entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_AVIONICS,
                      location);
                if (avionics > 0) {
                    target(location).add(boxes("UnitEditorDialog.avionics", entity.getBadCriticalSlots(
                                CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_AVIONICS, location), avionics,
                          (spec, value) -> spec.lamAvionicsHits.put(at, value)));
                }
                int gear = entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_LANDING_GEAR,
                      location);
                if (gear > 0) {
                    target(location).add(boxes("UnitEditorDialog.landingGear", entity.getBadCriticalSlots(
                                CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_LANDING_GEAR, location), gear,
                          (spec, value) -> spec.lamLandingGearHits.put(at, value)));
                }
            }
        }
        Mek mek = (Mek) entity;
        boolean tripod = entity.hasETypeFlag(Entity.ETYPE_TRIPOD_MEK);
        for (int location = Mek.LOC_RIGHT_ARM; location <= (tripod ? Mek.LOC_CENTER_LEG : Mek.LOC_LEFT_LEG);
              location++) {
            boolean leg = location >= Mek.LOC_RIGHT_LEG || entity instanceof QuadMek;
            int start = leg ? Mek.ACTUATOR_HIP : Mek.ACTUATOR_SHOULDER;
            int end = leg ? Mek.ACTUATOR_FOOT : Mek.ACTUATOR_HAND;
            int row = location - Mek.LOC_RIGHT_ARM;
            for (int actuator = start; actuator <= end; actuator++) {
                if (!entity.hasSystem(actuator, location)) {
                    continue;
                }
                int column = actuator - start;
                target(location).add(new Value(mek.getSystemName(actuator), Kind.BOXES,
                      entity.getDamagedCriticalSlots(CriticalSlot.TYPE_SYSTEM, actuator, location), 1, Repair.CLEAR,
                      (spec, value) -> spec.actuatorHits[row][column] = value));
            }
            if (entity instanceof QuadVee) {
                target(location).add(new Value(mek.getSystemName(QuadVee.SYSTEM_CONVERSION_GEAR), Kind.BOXES,
                      entity.getDamagedCriticalSlots(CriticalSlot.TYPE_SYSTEM, QuadVee.SYSTEM_CONVERSION_GEAR,
                            location), 1, Repair.CLEAR,
                      (spec, value) -> spec.actuatorHits[row][DamageEditSpec.CONVERSION_GEAR_INDEX] = value));
            }
        }
    }

    private void readTankSystems(Tank tank) {
        target(tank.hasNoTurret() ? Entity.LOC_NONE : tank.getLocTurret()).add(boxes("UnitEditorDialog.turretLock",
              tank.isTurretLocked(0) ? 1 : 0, 1, (spec, value) -> spec.turretLockHits = value));
        general.add(boxes("UnitEditorDialog.engine", tank.getEngineHits(), 1,
              (spec, value) -> spec.engineHits = value));
        general.add(boxes("UnitEditorDialog.sensor", tank.getSensorHits(), Tank.CRIT_SENSOR_MAX,
              (spec, value) -> spec.sensorHits = value));
        int motive = tank.isImmobile(false) ? 4 : tank.hasHeavyMovementDamage() ? 3
              : tank.hasModerateMovementDamage() ? 2 : tank.hasMinorMovementDamage() ? 1 : 0;
        general.add(boxes("UnitEditorDialog.motiveDamage", motive, 4, (spec, value) -> spec.motiveHits = value));
        stabilizers(tank, tank.getLocTurret(), tank.getLocTurret2());
    }

    private void readVtolSystems(VTOL vtol) {
        target(VTOL.LOC_ROTOR).add(boxes("UnitEditorDialog.flightStabilizer",
              vtol.isStabiliserHit(VTOL.LOC_ROTOR) ? 1 : 0, 1, (spec, value) -> spec.flightStabilizerHits = value));
        general.add(boxes("UnitEditorDialog.engine", vtol.getEngineHits(), 1,
              (spec, value) -> spec.engineHits = value));
        general.add(boxes("UnitEditorDialog.sensor", vtol.getSensorHits(), Tank.CRIT_SENSOR_MAX,
              (spec, value) -> spec.sensorHits = value));
        stabilizers(vtol, VTOL.LOC_ROTOR, VTOL.LOC_ROTOR);
    }

    private void stabilizers(Tank tank, int skip, int skipToo) {
        for (int location = 0; location < tank.locations(); location++) {
            if (location == Tank.LOC_BODY || location == skip || location == skipToo) {
                continue;
            }
            int at = location;
            target(location).add(boxes("UnitEditorDialog.stabilizer", tank.isStabiliserHit(location) ? 1 : 0, 1,
                  (spec, value) -> spec.stabilizerHits[at] = value));
        }
    }

    private void readProtoSystems() {
        for (int location = 0; location < entity.locations(); location++) {
            int system = switch (location) {
                case ProtoMek.LOC_LEFT_ARM, ProtoMek.LOC_RIGHT_ARM -> ProtoMek.SYSTEM_ARM_CRIT;
                case ProtoMek.LOC_LEG -> ProtoMek.SYSTEM_LEG_CRIT;
                case ProtoMek.LOC_HEAD -> ProtoMek.SYSTEM_HEAD_CRIT;
                case ProtoMek.LOC_TORSO -> ProtoMek.SYSTEM_TORSO_CRIT;
                default -> -1;
            };
            if (system < 0) {
                continue;
            }
            int at = location;
            target(location).add(boxes("UnitEditorDialog.crits",
                  entity.getDamagedCriticalSlots(CriticalSlot.TYPE_SYSTEM, system, location),
                  location == ProtoMek.LOC_LEG ? 3 : 2, (spec, value) -> spec.protoHits[at] = value));
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Place place(int location) {
        return places.computeIfAbsent(location,
              at -> new Place(at, entity.getLocationAbbr(at), entity.getLocationName(at)));
    }

    /** The values of a location shown in the editor, or the general values for {@code LOC_NONE} or a hidden one. */
    private List<Value> target(int location) {
        Place place = location >= 0 ? places.get(location) : null;
        return place != null ? place.values : general;
    }

    private static Value add(Place place, String label, Kind kind, int value, int max, Repair repair,
          Writer writer) {
        Value added = new Value(label, kind, value, max, repair, writer);
        place.values.add(added);
        return added;
    }

    private static Value boxes(String key, int hits, int max, Writer writer) {
        return new Value(text(key), Kind.BOXES, hits, max, Repair.CLEAR, writer);
    }

    private static Value toggle(String key, boolean on, Writer writer) {
        return new Value(text(key), Kind.TOGGLE, on ? 1 : 0, 1, Repair.KEEP, writer);
    }

    private static String text(String key) {
        return Messages.getString(key);
    }
}
