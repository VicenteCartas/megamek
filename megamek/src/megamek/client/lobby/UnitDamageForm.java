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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import megamek.client.ui.Messages;
import megamek.common.CriticalSlot;
import megamek.common.bays.Bay;
import megamek.common.compute.damage.CritAssignment;
import megamek.common.compute.damage.PreExistingDamageApplier;
import megamek.common.compute.damage.PreExistingDamageLevel;
import megamek.common.compute.damage.PreExistingDamageResult;
import megamek.common.enums.ChargeLevel;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.DockingCollar;
import megamek.common.equipment.EquipmentMode;
import megamek.common.equipment.GunEmplacement;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.SettableHeat;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Aero;
import megamek.common.units.Crew;
import megamek.common.units.DamageEditApplier;
import megamek.common.units.DamageEditSpec;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;
import megamek.common.units.Jumpship;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadMek;
import megamek.common.units.QuadVee;
import megamek.common.units.SmallCraft;
import megamek.common.units.Tank;
import megamek.common.units.VTOL;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.attacks.InfantryAttack;

/**
 * A unit's damage for the lobby's own editor, for every kind of unit the Swing {@code UnitEditorDialog} edits: each
 * location's armor, rear armor and structure with its equipment, ammunition and system hits, a conventional platoon's
 * troopers left, a large craft's bays, and the unit's general state (crew hits, heat, fuel, conditions and the systems
 * that sit in no location). Read from the unit on the Swing thread, edited by the view on its own, written back by
 * {@link #apply} as a {@link DamageEditSpec} that carries only what this form edits, and rolled with pre-existing
 * damage by {@link #roll}.
 */
public final class UnitDamageForm {
    /** The most hits a crew member can be given here, as in the Swing editor. */
    public static final int MAX_CREW_HITS = Crew.DEATH;
    /** The most turns a building's gunners can be left stunned here, as in the Swing editor. */
    private static final int MAX_STUNNED_TURNS = 20;
    /** The level of a building location that stands for its whole hex. */
    private static final int GROUND_FLOOR = 0;
    /** The latest round a unit can be held back to, as the lobby's Deploy menu offered. */
    private static final int LATEST_DEPLOY_ROUND = 40;

    /** How a value is edited. */
    public enum Kind {
        /** A number from 0 to its most. */
        STEPPER,
        /** Hit boxes: the value is how many of its most are hit. */
        BOXES,
        /** On (1) or off (0). */
        TOGGLE,
        /** One of its {@link Value#choices()}: the value is the chosen one's index. */
        CHOICE
    }

    /**
     * What the lobby lets the local player do in the editor.
     *
     * @param damage     whether the damage may be edited; when not, it shows read-only and only the deployment edits
     * @param preGame    whether a unit may deploy before the game, walking on (the rules' walk-on deployment)
     * @param scanTarget whether the unit can be marked as wanted for scanning: a game master's, in a game with
     *                   objectives
     * @param hullDown   whether the game's rules let units start hull-down (TacOps hull-down)
     * @param hidden     whether the game's rules let units start hidden (hidden units)
     * @param inPlay     whether a Game Master edits the unit in a running game: the edits go to the server, the
     *                   unit's heat and stance are part of them instead of its deployment, and the card offers the
     *                   {@link GameMasterUnitTools}
     */
    public record Options(boolean damage, boolean preGame, boolean scanTarget, boolean hullDown, boolean hidden,
          boolean inPlay) {
        /** Everything editable that every game offers, as for a unit's owner with no game master present. */
        public static final Options ALL = new Options(true, false, false);

        /** Options for the lobby. */
        public Options(boolean damage, boolean preGame, boolean scanTarget, boolean hullDown, boolean hidden) {
            this(damage, preGame, scanTarget, hullDown, hidden, false);
        }

        /** Options for a game without the optional hull-down and hidden-unit rules. */
        public Options(boolean damage, boolean preGame, boolean scanTarget) {
            this(damage, preGame, scanTarget, false, false);
        }

        /** A Game Master's options in the running {@code game}. */
        public static Options inPlay(Game game) {
            return new Options(true, false, false,
                  game.getOptions().booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN),
                  game.getOptions().booleanOption(OptionsConstants.ADVANCED_HIDDEN_UNITS), true);
        }
    }

    /** What Repair all does to a value. */
    private enum Repair {
        FULL, CLEAR, KEEP
    }

    @FunctionalInterface
    private interface Writer {
        void write(DamageEditSpec spec, int value);
    }

    /** Writes a deployment value straight to the unit, as the lobby's Deploy commands do. */
    @FunctionalInterface
    private interface Setter {
        void set(Entity entity, int value);
    }

    /** One edited value: its label, kind and bounds, the value it was read with and the value now. */
    public static final class Value {
        private final String label;
        private final Kind kind;
        private final int max;
        private final int original;
        private final Repair repair;
        private final Writer writer;
        private final List<Value> flags = new ArrayList<>();
        private Setter setter;
        private List<String> choices = List.of();
        private Consumer<Value> changed;
        private Value hits;
        private String tip;
        private int value;
        private int explosive = -1;

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

        /** The hit boxes that go with this number, such as an ammunition bin's, or null. */
        public Value hits() {
            return hits;
        }

        /** The switches of a weapon's state that go with its hit boxes: jammed, fired and a locked mount. */
        public List<Value> flags() {
            return Collections.unmodifiableList(flags);
        }

        /** Whether the number is held at nothing by its hits: a bin that is hit holds no ammunition. */
        public boolean locked() {
            return hits != null && hits.on();
        }

        /** What the value means, for a tooltip, or null. */
        public String tip() {
            return tip;
        }

        /** A choice's options, in the order of their indices; empty for another kind. */
        public List<String> choices() {
            return choices;
        }

        /**
         * The number of the equipment the Game Master can explode from this row in play, as a critical hit would set
         * it off; -1 when there is none.
         */
        public int explosive() {
            return explosive;
        }

        /** Back to the value it was read with, without the change hook: a roll starts from the unit as read. */
        private void restore() {
            value = original;
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
    private final Options options;
    private final Map<Integer, Place> places = new TreeMap<>();
    private final List<Value> general = new ArrayList<>();
    /** How the unit starts the game: heat, stance, hidden, scan target and deployment round. */
    private final List<Value> deployment = new ArrayList<>();
    /** The hit values a pre-existing damage roll can mark, by what the roll names. */
    private final Map<String, Value> keyed = new HashMap<>();
    private Value structuralIntegrity;
    /** How many transport bays the unit's bay values cover. */
    private int bays;
    /** The Game Master's in-play tools, or null outside play. */
    private final GameMasterUnitTools gameMaster;

    private UnitDamageForm(Entity entity, Options options) {
        this.entity = entity;
        this.options = options;
        if (entity.isConventionalInfantry()) {
            readInfantry((Infantry) entity);
        } else {
            readLocations();
            readCrew();
            readConditions();
            readEquipment();
            readSystems();
        }
        readDeployment();
        gameMaster = options.inPlay() ? new GameMasterUnitTools(this) : null;
    }

    /**
     * Whether the lobby's own editor covers the unit: every unit, as the Swing editor does. Conventional infantry
     * edits its troopers left, as the Swing editor's infantry panel does; everything else edits its locations.
     */
    public static boolean supported(Entity entity) {
        return entity != null;
    }

    /** Reads the unit's damage, all of it editable; call on the thread that owns the unit. */
    public static UnitDamageForm of(Entity entity) {
        return of(entity, Options.ALL);
    }

    /** Reads the unit's damage and deployment for what {@code options} allow; call on the thread that owns the unit. */
    public static UnitDamageForm of(Entity entity, Options options) {
        if (!supported(entity)) {
            throw new IllegalArgumentException("Not edited by the lobby's damage editor: " + entity);
        }
        return new UnitDamageForm(entity, options);
    }

    public Entity entity() {
        return entity;
    }

    /** Whether the damage may be edited; when not, only the {@link #deployment()} values are. */
    public boolean damageEditable() {
        return options.damage();
    }

    /**
     * The Game Master's in-play tools, whose {@link GameMasterUnitTools#commit} sends the edits to the server instead
     * of {@link #apply}; null outside play.
     */
    public GameMasterUnitTools gameMaster() {
        return gameMaster;
    }

    /** The locations with something to edit, in the unit's order. */
    public List<Place> places() {
        return List.copyOf(places.values());
    }

    /** The location whose paperdoll region has {@code code}, or null. */
    public Place place(String code) {
        return places.values().stream().filter(place -> place.abbr.equals(code)).findFirst().orElse(null);
    }

    /** The values that sit in no location: structural integrity, fuel, crew, systems and conditions. */
    public List<Value> general() {
        return Collections.unmodifiableList(general);
    }

    /**
     * How the unit starts the game: its heat, stance, hidden deployment, scan target and deployment round. These are
     * its owner's to set even where the damage is the game master's, and neither Restore Unit nor a roll touches them.
     */
    public List<Value> deployment() {
        return Collections.unmodifiableList(deployment);
    }

    /** An aerospace unit's structural integrity, or null. */
    public Value structuralIntegrity() {
        return structuralIntegrity;
    }

    /** Whether anything differs from the unit as read. */
    public boolean changed() {
        return values().stream().anyMatch(value -> value.value != value.original)
              || deployment.stream().anyMatch(value -> value.value != value.original);
    }

    /** Puts every armor and structure value to full and clears every hit, mark and crew hit. */
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
        if (bays > 0) {
            // every bay gets a capacity value, but a bay without doors has no door value to write its zero
            spec.bayCapacityRemaining = new Double[bays];
            spec.bayDoorHits = new Integer[bays];
            Arrays.fill(spec.bayDoorHits, 0);
        }
        for (Value value : values()) {
            value.writer.write(spec, value.value);
        }
        if (gameMaster != null) {
            gameMaster.write(spec);
        }
        return spec;
    }

    /** Whether the pre-existing damage rules (First Succession War, p.144) cover the unit, so it can be rolled. */
    public boolean rollable() {
        return PreExistingDamageApplier.isSupported(entity);
    }

    /**
     * Rolls pre-existing damage at {@code level} into the form, as the Swing editor's roller does: each roll starts
     * again from the unit as read, and None, or no level, just goes back to it. The unit itself is only read.
     */
    public void roll(PreExistingDamageLevel level) {
        values().forEach(Value::restore);
        if (level == null || level == PreExistingDamageLevel.NONE || !rollable()) {
            return;
        }
        PreExistingDamageResult result = PreExistingDamageApplier.simulate(entity, level);
        for (Place place : places.values()) {
            int location = place.location;
            if (place.armor != null) {
                place.armor.value(result.armor()[location]);
            }
            if (place.rear != null) {
                place.rear.value(result.rearArmor()[location]);
            }
            if (place.internal != null) {
                place.internal.value(result.internal()[location]);
            }
        }
        if (structuralIntegrity != null) {
            structuralIntegrity.value(result.structuralIntegrity());
        }
        for (CritAssignment assignment : result.critAssignments()) {
            Value hit = keyed.get(key(assignment));
            if (hit != null) {
                hit.value(hit.value + 1);
            }
        }
    }

    /** What the hit value a rolled critical hit marks is keyed by, as the Swing roller maps it. */
    private String key(CritAssignment assignment) {
        return switch (assignment) {
            case CritAssignment.EquipmentCrit(int number) -> "eq:" + number;
            case CritAssignment.MekSystemCrit(int system, int location) -> mekKey(system, location);
            case CritAssignment.VehicleCrit(CritAssignment.VehicleCritKind kind, int location) -> switch (kind) {
                case TURRET_LOCK -> "turretLock";
                case SENSORS -> "sensor";
                case MOTIVE -> "motive";
                case STABILIZER -> entity instanceof VTOL && location == VTOL.LOC_ROTOR ? "flightStabilizer"
                      : "stabilizer:" + location;
            };
            case CritAssignment.AeroFighterCrit(CritAssignment.AeroFighterCritKind kind) -> switch (kind) {
                case AVIONICS -> "avionics";
                case FIRE_CONTROL_SYSTEM -> "fcs";
                case SENSORS -> "sensor";
                case ENGINE -> "engine";
                case LANDING_GEAR -> "gear";
            };
        };
    }

    private String mekKey(int system, int location) {
        if (entity instanceof LandAirMek && system == LandAirMek.LAM_AVIONICS) {
            return "lamAvionics:" + location;
        }
        if (entity instanceof LandAirMek && system == LandAirMek.LAM_LANDING_GEAR) {
            return "lamGear:" + location;
        }
        if (entity instanceof QuadVee && system == QuadVee.SYSTEM_CONVERSION_GEAR) {
            return "conversion:" + location;
        }
        return switch (system) {
            case Mek.SYSTEM_ENGINE -> "engine:" + (location == Mek.LOC_LEFT_TORSO || location == Mek.LOC_RIGHT_TORSO
                  ? location : Mek.LOC_CENTER_TORSO);
            case Mek.SYSTEM_GYRO -> "gyro";
            case Mek.SYSTEM_SENSORS -> "sensor";
            case Mek.SYSTEM_LIFE_SUPPORT -> "lifeSupport";
            default -> "actuator:" + location + ":" + system;
        };
    }

    /**
     * Writes the edits to the unit; call on the thread that owns the unit. The damage is written only where it may be
     * edited, and the deployment values only where they changed.
     */
    public void apply() {
        if (options.damage()) {
            new DamageEditApplier(entity, spec()).applyToEntity();
        }
        for (Value value : deployment) {
            if (value.value != value.original) {
                value.setter.set(entity, value.value);
            }
        }
    }

    private List<Value> values() {
        List<Value> shown = new ArrayList<>();
        places.values().forEach(place -> shown.addAll(place.values));
        shown.addAll(general);
        List<Value> all = new ArrayList<>();
        for (Value value : shown) {
            // a number's hits come first, so repairing them gives back the number before it is filled
            if (value.hits != null) {
                all.add(value.hits);
            }
            all.add(value);
            all.addAll(value.flags);
        }
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
        // a building's crew is a garrison of gunners, stunned or killed a hex at a time instead of wounded
        if (crew == null || crew.getSlotCount() < 1 || entity instanceof AbstractBuildingEntity) {
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

    private void readDeployment() {
        boolean play = options.inPlay();
        if (entity.tracksHeat()) {
            int heat = Math.max(entity.heat, 0);
            state(new Value(text("UnitEditorDialog.heat"), Kind.STEPPER, heat,
                  SettableHeat.maximum(entity.getGame(), heat, entity.getHeatCapacityWithWater()), Repair.KEEP,
                  play ? (spec, value) -> spec.heat = value : null), (unit, value) -> unit.heat = value);
        }
        boolean canLieDown = !entity.isAero() && !entity.isAirborne() && !entity.isAirborneVTOLorWIGE()
              && !entity.isConventionalInfantry();
        if (canLieDown && entity.isMek()) {
            state(toggle("UnitEditorDialog.status.prone", entity.isProne(),
                  play ? (spec, value) -> spec.prone = value > 0 : null), (unit, value) -> unit.setProne(value > 0));
        }
        // Optional rules gate these; a unit already in the state still shows it, so it can be cleared
        boolean hullDownCapable = entity.isMek() || (entity instanceof Tank tank && tank.isHullDownCapable());
        if (canLieDown && hullDownCapable && (options.hullDown() || entity.isHullDown())) {
            state(toggle("UnitEditorDialog.status.hullDown", entity.isHullDown(),
                        play ? (spec, value) -> spec.hullDown = value > 0 : null),
                  (unit, value) -> unit.setHullDown(value > 0));
        }
        if (entity.canHide() && (options.hidden() || entity.isHidden())) {
            state(toggle("UnitEditorDialog.status.hidden", entity.isHidden(),
                        play ? (spec, value) -> spec.hidden = value > 0 : null),
                  (unit, value) -> unit.setHidden(value > 0));
        }
        if (play) {
            // in play the unit is on the field: there is no deployment round left to set, nor a scan target to mark
            return;
        }
        if (options.scanTarget()) {
            deployment.add(deploy(toggle("Lobby.status.scanTarget", entity.isDesignatedScanTarget(), null),
                  (unit, value) -> unit.setDesignatedScanTarget(value > 0)));
        }
        List<Integer> rounds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (options.preGame() || entity.getDeployRound() == Entity.DEPLOY_ROUND_PRE_GAME) {
            rounds.add(Entity.DEPLOY_ROUND_PRE_GAME);
            names.add(text("ChatLounge.deploysPreGame"));
        }
        for (int round = 0; round <= Math.max(LATEST_DEPLOY_ROUND, entity.getDeployRound()); round++) {
            rounds.add(round);
            names.add(round == 0 ? text("Lobby.status.atStart") : Messages.getString("Lobby.status.beforeRound",
                  round));
        }
        int at = Math.max(0, rounds.indexOf(entity.getDeployRound()));
        Value round = new Value(text("Lobby.status.deployRound"), Kind.CHOICE, at, rounds.size() - 1, Repair.KEEP,
              null);
        round.choices = List.copyOf(names);
        deployment.add(deploy(round, (unit, value) -> unit.setDeployRound(rounds.get(value))));
    }

    private static Value deploy(Value value, Setter setter) {
        value.setter = setter;
        return value;
    }

    /**
     * Adds a value of how the unit stands: to the deployment, written by its setter, before the game; to the general
     * values, written into the spec the server applies, in play.
     */
    private void state(Value value, Setter setter) {
        if (options.inPlay()) {
            general.add(value);
        } else {
            deployment.add(deploy(value, setter));
        }
    }

    private void readConditions() {
        // a building is shut down exactly when its power is off, which its own switch sets
        if (!(entity instanceof AbstractBuildingEntity)) {
            general.add(toggle("UnitEditorDialog.status.shutdown", entity.isShutDown(),
                  (spec, value) -> spec.shutdown = value > 0));
        }
        if (entity.hasStealth()) {
            boolean on = false;
            for (MiscMounted stealth : entity.getMiscEquipment(MiscType.F_STEALTH)) {
                on |= EquipmentMode.getMode("On").equals(stealth.curMode());
            }
            general.add(toggle("UnitEditorDialog.status.stealth", on, (spec, value) -> spec.stealth = value > 0));
        }
        dugIn();
    }

    /** Dug In, for infantry that can dig in: mechanized infantry cannot (TO:AR p.106). */
    private void dugIn() {
        if (entity instanceof Infantry infantry && !infantry.isMechanized()) {
            general.add(toggle("UnitEditorDialog.status.dugIn", infantry.getDugIn() != Infantry.DUG_IN_NONE,
                  (spec, value) -> spec.dugIn = value > 0));
        }
    }

    /**
     * Conventional infantry: how many troopers are left, as the Swing editor's infantry panel has it, and whether
     * they are dug in.
     */
    private void readInfantry(Infantry infantry) {
        general.add(new Value(text("UnitEditorDialog.troopersLeft"), Kind.STEPPER,
              Math.max(infantry.getShootingStrength(), 0), infantry.getSquadCount() * infantry.getSquadSize(),
              Repair.FULL, (spec, value) -> spec.internal[0] = value));
        dugIn();
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
            Value crit = mark("eq:" + number, new Value(label, Kind.BOXES, hits, Math.max(slots, 1), Repair.CLEAR,
                  (spec, value) -> spec.equipmentHits.put(number, value)));
            Value row = crit;
            if (mounted instanceof AmmoMounted bin) {
                row = shots(bin, number, crit);
                target(mounted.getLocation()).add(row);
            } else {
                target(mounted.getLocation()).add(crit);
            }
            if (mounted instanceof WeaponMounted weapon) {
                flags(weapon, number, crit);
                if (entity instanceof AbstractBuildingEntity building && building.isTurretMounted(weapon)) {
                    crit.flags.add(flag("UnitEditorDialog.building.turretLocked", building.isTurretLocked(weapon),
                          (spec, value) -> spec.buildingTurretLocked.put(number, value > 0)));
                }
            }
            if (options.inPlay()) {
                settings(mounted, number, row);
                // a building's damage path has not been tried with an explosion, as in the Swing editor
                if (!(entity instanceof AbstractBuildingEntity) && mounted.wouldExplodeWhenHit()) {
                    row.explosive = number;
                }
            }
        }
    }

    /**
     * An ammunition bin's shots, with its hit boxes: a hit empties the bin and clearing the hit fills it back to what
     * it held, as in the Swing editor; a bin read as hit holds nothing and comes back full.
     */
    private Value shots(AmmoMounted bin, int number, Value crit) {
        int full = bin.isOneShot() ? 1 : bin.getOriginalShots() > 0 ? bin.getOriginalShots() : bin.getType().getShots();
        full = Math.max(full, bin.getBaseShotsLeft());
        Value shots = new Value(crit.label, Kind.STEPPER, crit.on() ? 0 : Math.min(bin.getBaseShotsLeft(), full), full,
              Repair.FULL, (spec, value) -> spec.ammoShots.put(number, value));
        shots.hits = crit;
        int[] before = { crit.on() ? full : shots.value };
        crit.changed = hit -> {
            if (hit.on() && shots.value > 0) {
                before[0] = shots.value;
                shots.value(0);
            } else if (!hit.on() && shots.value == 0) {
                shots.value(before[0]);
            }
        };
        return shots;
    }

    /** A weapon's state switches beside its hit boxes, where a rule can put it in that state. */
    private static void flags(WeaponMounted weapon, int number, Value crit) {
        if (weapon.canJam()) {
            crit.flags.add(flag("UnitEditorDialog.weaponJammed", weapon.isJammed(),
                  (spec, value) -> spec.weaponJammed.put(number, value > 0)));
        }
        if (weapon.isOneShot()) {
            crit.flags.add(flag("UnitEditorDialog.weaponFired", weapon.isFired(),
                  (spec, value) -> spec.weaponFired.put(number, value > 0)));
        }
        if (weapon.hasDirectionalTorsoMount()) {
            crit.flags.add(flag("UnitEditorDialog.mountLocked", weapon.isDirectionalMountLocked(),
                  (spec, value) -> spec.directionalMountLocked.put(number, value > 0)));
        }
    }

    private static Value flag(String key, boolean on, Writer writer) {
        Value flag = new Value(text(key), Kind.TOGGLE, on ? 1 : 0, 1, Repair.CLEAR, writer);
        flag.tip = text(key + ".tooltip");
        return flag;
    }

    /**
     * The Game Master's in-play settings of a piece of equipment, under its row as the Swing editor appends them: a
     * machine gun's burst fire and a bin's hot-loading under their optional rules, then a charge, an on/off switch or
     * a mode, whichever comes first. None of them changes with Restore Unit or a roll. In the lobby the Configure
     * dialog owns these settings.
     */
    private void settings(Mounted<?> mounted, int number, Value row) {
        Game game = entity.getGame();
        if (game == null) {
            return;
        }
        if (mounted.getType() instanceof WeaponType weapon && weapon.hasFlag(WeaponType.F_MG)
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_BURST)) {
            row.flags.add(setting("UnitEditorDialog.mgBurst", mounted.isRapidFire(), "UnitEditorDialog.mgBurst.tooltip",
                  (spec, value) -> spec.mgBurst.put(number, value > 0)));
        }
        if (mounted instanceof AmmoMounted bin && bin.getType().hasFlag(AmmoType.F_HOTLOAD)
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HOT_LOAD)) {
            row.flags.add(setting("UnitEditorDialog.hotLoad", bin.isHotLoaded(), "UnitEditorDialog.hotLoad.tooltip",
                  (spec, value) -> spec.hotLoadedAmmo.put(number, value > 0)));
        }
        boolean stealth = mounted.getType() instanceof MiscType misc && misc.hasFlag(MiscType.F_STEALTH);
        int modes = mounted.getType().getModesCount(mounted);
        String off = offMode(mounted);
        if (chargeable(mounted)) {
            boolean charged = mounted.getType() instanceof MiscType
                  ? mounted.curMode().equals(Mounted.MODE_CAPACITOR_CHARGE)
                  : mounted.getChargeState() == ChargeLevel.CHARGED;
            row.flags.add(setting("UnitEditorDialog.equipCharged", charged, "UnitEditorDialog.equipCharge.tooltip",
                  (spec, value) -> spec.equipmentCharged.put(number, value > 0)));
        } else if (mounted.isModeSwitchable() && !stealth && modes == 2 && off != null) {
            String active = off;
            for (int index = 0; index < modes; index++) {
                String name = mounted.getType().getMode(index).getName();
                if (!name.equals(off)) {
                    active = name;
                    break;
                }
            }
            String on = active;
            Value onOff = new Value(text("MekDisplay.modeLabel"), Kind.CHOICE,
                  mounted.curMode().getName().equals(off) ? 0 : 1, 1, Repair.KEEP,
                  (spec, value) -> spec.equipmentMode.put(number, value > 0 ? on : off));
            onOff.choices = List.of(text("UnitEditorDialog.equipOff"), text("UnitEditorDialog.equipOn"));
            onOff.tip = text("UnitEditorDialog.equipOnOff.tooltip");
            row.flags.add(onOff);
        } else if (mounted.isModeSwitchable() && !(mounted instanceof AmmoMounted) && !stealth && modes >= 2) {
            List<String> names = new ArrayList<>();
            List<String> shown = new ArrayList<>();
            for (int index = 0; index < modes; index++) {
                EquipmentMode mode = mounted.getType().getMode(index);
                names.add(mode.getName());
                shown.add(mode.getDisplayableName(true));
            }
            Value mode = new Value(text("MekDisplay.modeLabel"), Kind.CHOICE,
                  Math.max(0, names.indexOf(mounted.curMode().getName())), modes - 1, Repair.KEEP,
                  (spec, value) -> spec.equipmentMode.put(number, names.get(value)));
            mode.choices = List.copyOf(shown);
            mode.tip = text("UnitEditorDialog.equipMode.tooltip");
            row.flags.add(mode);
        }
    }

    private static Value setting(String key, boolean on, String tipKey, Writer writer) {
        Value setting = new Value(text(key), Kind.TOGGLE, on ? 1 : 0, 1, Repair.KEEP, writer);
        setting.tip = text(tipKey);
        return setting;
    }

    /** Whether the Game Master can set the equipment's charge: a bombast laser or a PPC capacitor. */
    private static boolean chargeable(Mounted<?> mounted) {
        if (mounted.getType() instanceof WeaponType weapon && weapon.hasFlag(WeaponType.F_BOMBAST_LASER)) {
            return true;
        }
        return mounted.getType() instanceof MiscType misc && misc.hasFlag(MiscType.F_PPC_CAPACITOR);
    }

    /** The equipment's switched-off mode: "Off", or a gauss rifle's "Powered Down"; null when it has none. */
    private static String offMode(Mounted<?> mounted) {
        if (mounted.getType().hasModeType(Mounted.MODE_OFF)) {
            return Mounted.MODE_OFF;
        }
        if (mounted.getType().hasModeType(Weapon.MODE_GAUSS_POWERED_DOWN)) {
            return Weapon.MODE_GAUSS_POWERED_DOWN;
        }
        return null;
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
            readAeroSystems(aero);
        } else if (entity instanceof AbstractBuildingEntity building) {
            readBuildingSystems(building);
        }
    }

    private void readAeroSystems(Aero aero) {
        boolean jumpship = aero instanceof Jumpship;
        general.add(mark("avionics", boxes("UnitEditorDialog.avionics", aero.getAvionicsHits(), 3,
              (spec, value) -> spec.avionicsHits = value)));
        if (jumpship) {
            general.add(boxes("UnitEditorDialog.cic", aero.getCICHits(), 3, (spec, value) -> spec.cicHits = value));
        } else {
            general.add(mark("fcs", boxes("UnitEditorDialog.fcs", aero.getFCSHits(), 3,
                  (spec, value) -> spec.fcsHits = value)));
        }
        general.add(mark("sensor", boxes("UnitEditorDialog.sensor", aero.getSensorHits(), 3,
              (spec, value) -> spec.sensorHits = value)));
        general.add(mark("engine", boxes("UnitEditorDialog.engine", aero.getEngineHits(),
              aero instanceof Dropship || jumpship ? 6 : 3, (spec, value) -> spec.engineHits = value)));
        if (!jumpship) {
            general.add(mark("gear", boxes("UnitEditorDialog.landingGear", aero.isGearHit() ? 1 : 0, 1,
                  (spec, value) -> spec.gearHits = value)));
        }
        general.add(boxes("UnitEditorDialog.lifeSupport", aero.hasLifeSupport() ? 0 : 1, 1,
              (spec, value) -> spec.lifeSupportHits = value));
        if (aero instanceof SmallCraft || jumpship) {
            general.add(boxes("UnitEditorDialog.leftThruster", aero.getLeftThrustHits(), 4,
                  (spec, value) -> spec.leftThrusterHits = value));
            general.add(boxes("UnitEditorDialog.rightThruster", aero.getRightThrustHits(), 4,
                  (spec, value) -> spec.rightThrusterHits = value));
        }
        if (aero instanceof Jumpship ship) {
            readJumpshipSystems(ship);
        }
        if (aero instanceof Dropship dropship) {
            general.add(boxes("UnitEditorDialog.dropshipCollar", dropship.isDockCollarDamaged() ? 1 : 0, 1,
                  (spec, value) -> spec.dockCollarHits = value));
            general.add(boxes("UnitEditorDialog.kfBoom", dropship.isKFBoomDamaged() ? 1 : 0, 1,
                  (spec, value) -> spec.kfBoomHits = value));
        }
        if (aero instanceof SmallCraft || jumpship) {
            readBays(aero);
        }
    }

    /** A JumpShip's grav decks, docking collars, K-F drive and jump sail. */
    private void readJumpshipSystems(Jumpship ship) {
        general.add(boxes("UnitEditorDialog.gravDecks", ship.getTotalDamagedGravDeck(), ship.getTotalGravDeck(),
              (spec, value) -> spec.gravDeckHits = value));
        int collars = ship.getDockingCollars().size();
        int damaged = (int) ship.getDockingCollars().stream().filter(DockingCollar::isDamaged).count();
        general.add(new Value(text("UnitEditorDialog.dockingCollars"), Kind.STEPPER, collars - damaged, collars,
              Repair.FULL, (spec, value) -> spec.workingDockingCollars = value));
        general.add(new Value(text("UnitEditorDialog.kfIntegrity"), Kind.STEPPER, ship.getKFIntegrity(),
              ship.getOKFIntegrity(), Repair.FULL, (spec, value) -> spec.kfIntegrity = value));
        Game game = ship.getGame();
        if (game != null
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_AERO_RULES_EXPANDED_KF_DRIVE_DAMAGE)) {
            general.add(boxes("UnitEditorDialog.driveCoil", ship.getKFDriveCoilHit() ? 1 : 0, 1,
                  (spec, value) -> spec.driveCoilHits = value));
            general.add(boxes("UnitEditorDialog.chargingSystem", ship.getKFChargingSystemHit() ? 1 : 0, 1,
                  (spec, value) -> spec.chargingSystemHits = value));
            general.add(boxes("UnitEditorDialog.fieldInitiator", ship.getKFFieldInitiatorHit() ? 1 : 0, 1,
                  (spec, value) -> spec.fieldInitiatorHits = value));
            general.add(boxes("UnitEditorDialog.driveController", ship.getKFDriveControllerHit() ? 1 : 0, 1,
                  (spec, value) -> spec.driveControllerHits = value));
            general.add(boxes("UnitEditorDialog.heliumTank", ship.getKFHeliumTankHit() ? 1 : 0, 1,
                  (spec, value) -> spec.heliumTankHits = value));
            if (ship.hasLF()) {
                general.add(boxes("UnitEditorDialog.lfBattery", ship.getLFBatteryHit() ? 1 : 0, 1,
                      (spec, value) -> spec.lfBatteryHits = value));
            }
        }
        general.add(new Value(text("UnitEditorDialog.sailIntegrity"), Kind.STEPPER, ship.getSailIntegrity(),
              ship.getOSailIntegrity(), Repair.FULL, (spec, value) -> spec.sailIntegrity = value));
    }

    /**
     * The transport bays, on a place of their own after the locations so a large craft's general values stay short:
     * each bay's capacity left in whole units with its doors' hits. A cargo bay counts in half tons in the Swing
     * editor; here a half ton the bay was read with is kept when its number is left alone, and a full bay is its
     * whole capacity.
     */
    private void readBays(Aero aero) {
        List<Bay> transport = aero.getTransportBays();
        if (transport.isEmpty()) {
            return;
        }
        bays = transport.size();
        int key = entity.locations();
        Place place = places.computeIfAbsent(key, at -> new Place(at, "", text("Lobby.damage.bays")));
        for (int index = 0; index < transport.size(); index++) {
            Bay bay = transport.get(index);
            int at = index;
            double capacity = bay.getCapacity();
            double left = Math.clamp(capacity - bay.getBayDamage(), 0, capacity);
            int full = (int) Math.ceil(capacity);
            int read = (int) Math.floor(left);
            add(place, String.format(text("UnitEditorDialog.bayCrit"), bay.getTransporterType(),
                  bay.getBayNumber()), Kind.STEPPER, read, full, Repair.FULL,
                  (spec, number) -> spec.bayCapacityRemaining[at] = number == read ? left
                        : number >= full ? capacity : (double) number);
            if (bay.getDoors() > 0) {
                add(place, text("UnitEditorDialog.bayDoors"), Kind.BOXES, bay.getDoors() - bay.getCurrentDoors(),
                      bay.getDoors(), Repair.CLEAR, (spec, hits) -> spec.bayDoorHits[at] = hits);
            }
        }
    }

    /**
     * An Advanced Building's own state (TO:AR p. 119): its power switch and how long its gunners stay stunned, and
     * whether each hex has lost its gunners, on the hex's ground floor.
     */
    private void readBuildingSystems(AbstractBuildingEntity building) {
        Value power = toggle("UnitEditorDialog.building.powerOff", building.isPowerSwitchedOff(),
              (spec, value) -> spec.buildingPowerSwitchedOff = value > 0);
        general.add(power);
        Value stunned = new Value(text("UnitEditorDialog.building.gunnersStunned"), Kind.STEPPER,
              Math.max(building.getStunnedTurns(), 0), MAX_STUNNED_TURNS, Repair.CLEAR,
              (spec, value) -> spec.buildingStunnedTurns = value);
        stunned.tip = text("UnitEditorDialog.building.gunnersStunned.tooltip");
        general.add(stunned);
        for (int location = 0; location < building.locations(); location++) {
            if (building.getLocationLevel(location) != GROUND_FLOOR) {
                continue;
            }
            int at = location;
            Value killed = new Value(text("UnitEditorDialog.building.gunnersKilled"), Kind.TOGGLE,
                  building.hasDeadGunners(location) ? 1 : 0, 1, Repair.CLEAR,
                  (spec, value) -> spec.buildingGunnersKilled.put(at, value > 0));
            killed.tip = text("UnitEditorDialog.building.gunnersKilled.tooltip");
            target(location).add(killed);
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
        target(Mek.LOC_CENTER_TORSO).add(mark("engine:" + Mek.LOC_CENTER_TORSO, boxes(engine, hits[0], slots[0],
              (spec, value) -> spec.centerEngineHits = value)));
        if (entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_ENGINE, Mek.LOC_RIGHT_TORSO) > 0) {
            target(Mek.LOC_LEFT_TORSO).add(mark("engine:" + Mek.LOC_LEFT_TORSO, boxes(engine, hits[1], slots[1],
                  (spec, value) -> spec.leftEngineHits = value)));
            target(Mek.LOC_RIGHT_TORSO).add(mark("engine:" + Mek.LOC_RIGHT_TORSO, boxes(engine, hits[2], slots[2],
                  (spec, value) -> spec.rightEngineHits = value)));
        }
        target(Mek.LOC_CENTER_TORSO).add(mark("gyro", boxes("UnitEditorDialog.gyro", hits[3], slots[3],
              (spec, value) -> spec.gyroHits = value)));
        target(Mek.LOC_HEAD).add(mark("sensor", boxes("UnitEditorDialog.sensor", hits[4], slots[4],
              (spec, value) -> spec.sensorHits = value)));
        target(Mek.LOC_HEAD).add(mark("lifeSupport", boxes("UnitEditorDialog.lifeSupport", hits[5], slots[5],
              (spec, value) -> spec.lifeSupportHits = value)));
        target(Mek.LOC_HEAD).add(boxes("UnitEditorDialog.cockpit", hits[6], slots[6],
              (spec, value) -> spec.cockpitHits = value));
        if (entity instanceof LandAirMek) {
            for (int location = 0; location < entity.locations(); location++) {
                int at = location;
                int avionics = entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_AVIONICS,
                      location);
                if (avionics > 0) {
                    target(location).add(mark("lamAvionics:" + location, boxes("UnitEditorDialog.avionics",
                          entity.getBadCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_AVIONICS, location),
                          avionics, (spec, value) -> spec.lamAvionicsHits.put(at, value))));
                }
                int gear = entity.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_LANDING_GEAR,
                      location);
                if (gear > 0) {
                    target(location).add(mark("lamGear:" + location, boxes("UnitEditorDialog.landingGear",
                          entity.getBadCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_LANDING_GEAR, location),
                          gear, (spec, value) -> spec.lamLandingGearHits.put(at, value))));
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
                target(location).add(mark("actuator:" + location + ":" + actuator, new Value(
                      mek.getSystemName(actuator), Kind.BOXES,
                      entity.getDamagedCriticalSlots(CriticalSlot.TYPE_SYSTEM, actuator, location), 1, Repair.CLEAR,
                      (spec, value) -> spec.actuatorHits[row][column] = value)));
            }
            if (entity instanceof QuadVee) {
                target(location).add(mark("conversion:" + location, new Value(
                      mek.getSystemName(QuadVee.SYSTEM_CONVERSION_GEAR), Kind.BOXES,
                      entity.getDamagedCriticalSlots(CriticalSlot.TYPE_SYSTEM, QuadVee.SYSTEM_CONVERSION_GEAR,
                            location), 1, Repair.CLEAR,
                      (spec, value) -> spec.actuatorHits[row][DamageEditSpec.CONVERSION_GEAR_INDEX] = value)));
            }
        }
    }

    private void readTankSystems(Tank tank) {
        target(tank.hasNoTurret() ? Entity.LOC_NONE : tank.getLocTurret()).add(mark("turretLock",
              boxes("UnitEditorDialog.turretLock", tank.isTurretLocked(0) ? 1 : 0, 1,
                    (spec, value) -> spec.turretLockHits = value)));
        // an emplacement has no engine and does not move, so only its turret and sensors can be hit
        boolean emplacement = tank instanceof GunEmplacement;
        if (!emplacement) {
            general.add(boxes("UnitEditorDialog.engine", tank.getEngineHits(), 1,
                  (spec, value) -> spec.engineHits = value));
        }
        general.add(mark("sensor", boxes("UnitEditorDialog.sensor", tank.getSensorHits(), Tank.CRIT_SENSOR_MAX,
              (spec, value) -> spec.sensorHits = value)));
        if (emplacement) {
            return;
        }
        int motive = tank.isImmobile(false) ? 4 : tank.hasHeavyMovementDamage() ? 3
              : tank.hasModerateMovementDamage() ? 2 : tank.hasMinorMovementDamage() ? 1 : 0;
        general.add(mark("motive", boxes("UnitEditorDialog.motiveDamage", motive, 4,
              (spec, value) -> spec.motiveHits = value)));
        stabilizers(tank, tank.getLocTurret(), tank.getLocTurret2());
    }

    private void readVtolSystems(VTOL vtol) {
        target(VTOL.LOC_ROTOR).add(mark("flightStabilizer", boxes("UnitEditorDialog.flightStabilizer",
              vtol.isStabiliserHit(VTOL.LOC_ROTOR) ? 1 : 0, 1, (spec, value) -> spec.flightStabilizerHits = value)));
        general.add(boxes("UnitEditorDialog.engine", vtol.getEngineHits(), 1,
              (spec, value) -> spec.engineHits = value));
        general.add(mark("sensor", boxes("UnitEditorDialog.sensor", vtol.getSensorHits(), Tank.CRIT_SENSOR_MAX,
              (spec, value) -> spec.sensorHits = value)));
        stabilizers(vtol, VTOL.LOC_ROTOR, VTOL.LOC_ROTOR);
    }

    private void stabilizers(Tank tank, int skip, int skipToo) {
        for (int location = 0; location < tank.locations(); location++) {
            if (location == Tank.LOC_BODY || location == skip || location == skipToo) {
                continue;
            }
            int at = location;
            target(location).add(mark("stabilizer:" + location, boxes("UnitEditorDialog.stabilizer",
                  tank.isStabiliserHit(location) ? 1 : 0, 1, (spec, value) -> spec.stabilizerHits[at] = value)));
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

    /** Keys {@code value} for the hits a pre-existing damage roll names {@code key}. */
    private Value mark(String key, Value value) {
        keyed.put(key, value);
        return value;
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
