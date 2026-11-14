/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.tooltip.HexTooltip;
import megamek.client.ui.clientGUI.tooltip.PilotToolTip;
import megamek.client.ui.clientGUI.tooltip.TipUtil;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.client.ui.clientGUI.unitDisplay.HeatEffects;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayData;
import megamek.client.ui.clientGUI.unitDisplay.UnitEquipmentActions;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.util.ViewFormatting;
import megamek.common.CriticalSlot;
import megamek.common.Hex;
import megamek.common.HitData;
import megamek.common.MPCalculationSetting;
import megamek.common.RecordSheetSlotNames;
import megamek.common.analysis.DamageProfile;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.DockingCollar;
import megamek.common.equipment.EquipmentMode;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.icons.Portrait;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.PilotingRollData;
import megamek.common.units.Aero;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Dropship;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;
import megamek.common.units.IAero;
import megamek.common.units.Infantry;
import megamek.common.units.Jumpship;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.MekWithArms;
import megamek.common.units.SmallCraft;
import megamek.common.units.Tank;
import megamek.common.units.VTOL;
import megamek.common.weapons.bayWeapons.BayWeapon;

/**
 * EDT service for the unit record of the card unit: what the unit panel shows (the Unit Display's content, the record
 * sheet's critical table, hit boxes and shields, and the unit's vitals), captured from MegaMek's own code into
 * immutable records, and its guarded unit actions.
 */
final class GpuUnitRecord {
    /** {@link SystemControl} ids. {@link #setSystem} takes the index of one of the control's choices. */
    static final String SENSORS = "sensors";
    static final String HEAT_SINKS = "heatSinks";
    static final String HIDDEN = "hidden";
    static final String WEAPON_ORDER = "weaponOrder";
    static final String CONSOLE_ROLES = "consoleRoles";

    /** HeatEffects repeats its last entry above its table (30, or 50 with TacOps heat); the search stops here. */
    private static final int HEAT_SEARCH_LIMIT = 100;

    /** A system with a hit box on a Mek's critical table. */
    enum Track { ENGINE, GYRO, SENSORS, LIFE_SUPPORT, AVIONICS, LANDING_GEAR }

    /** A group of {@link Vital}s; see {@link #vitals} for the codes of each. */
    enum VitalGroup { SYSTEM, MOTIVE, STABILIZER, TURRET, AERO, SHIP, FLIGHT, MP, TROOPERS, KIT, FIGHTER }

    /**
     * The section an {@link InfoRow} belongs to. CARRIER is the unit that carries this one, TRANSPORT each unit it
     * carries (both with the unit's link); PSR's label is the base piloting roll's target ("5", or MegaMek's text for
     * an impossible or automatic roll) and its text the roll's reasons; CREW is a crew seat's further values.
     */
    enum InfoSection {
        MOVEMENT, STATUS, SEEN_BY, SENSOR_RANGE, NETWORK, CARRIER, TRANSPORT, UNIT, REPAIRS, PSR, HEX, CREW
    }

    /**
     * A location: its armor, rear armor and structure (current and original; states below zero read as 0), whether it
     * is destroyed, blown off or breached, where its damage goes once it is destroyed and which location is lost with
     * it (abbreviations, "" for none), its BAR rating (0 without BAR armor) and aerospace damage threshold (0 for other
     * units), its CASE ("", "CASE" or "CASE II"), its number of critical slots and the slots: every slot of a Mek, the
     * filled ones of any other unit.
     */
    record Location(String abbr, String name, int armor, int maxArmor, int rear, int maxRear, int internal,
          int maxInternal, boolean destroyed, boolean blownOff, boolean breached, String transferTo, String dependent,
          int bar, int threshold, String caseTag, int slotCount, List<Slot> slots) {
        Location {
            slots = List.copyOf(slots);
        }
    }

    /**
     * A critical slot: its index in the location; its record sheet name ({@link RecordSheetSlotNames}: "Roll Again"
     * when empty); its equipment's number and that of a second one sharing the slot (-1: none); its system
     * ({@code CriticalSlot.getIndex}, such as {@code Mek.SYSTEM_ENGINE}; -1 for equipment and empty slots); whether it
     * took a critical hit ({@code CriticalSlot.isDamaged}); whether it or its (first) equipment is destroyed, missing
     * (blown off) or breached; whether it is an armored component ({@code isOriginalArmored}) and its armor was hit;
     * whether it holds equipment no critical hit can damage ({@code filler}: Endo Steel, CASE and the like); and for
     * ammunition, over both bins of a superheavy Mek's slot, the shots left, the shots when full, hot-loading and
     * dumping (-1 shots for any other slot).
     */
    record Slot(int index, String text, int eqNum, int eqNum2, int system, boolean hit, boolean destroyed,
          boolean missing, boolean breached, boolean armored, boolean armorHit, boolean filler, int shots,
          int fullShots, boolean hotLoaded, boolean dumping) {
        /** Whether the slot is empty ("Roll Again"). */
        boolean empty() {
            return (system < 0) && (eqNum < 0);
        }
    }

    /**
     * The hits of a Mek system with a hit box and the hits that destroy or disable it: the engine
     * ({@code Mek.engineHitsToDestroy}), the gyro ({@code gyroHitsToDestroy}), the sensors
     * ({@code sensorHitsToDestroy}), life support (its first hit disables it), a LAM's avionics
     * ({@code avionicsHitsToDestroy}) and landing gear (one box per landing gear slot: each hit worsens the landing
     * roll, and MegaMek destroys none).
     */
    record HitTrack(Track track, int hits, int capacity) { }

    /**
     * A shield on an arm: its absorption per hit and remaining damage capacity with their base values, and whether it
     * still works ({@code MekWithArms.isShieldActive}; the capacity getter does not see a blown-off arm).
     */
    record Shield(String location, int eqNum, String name, boolean active, int absorption, int baseAbsorption,
          int capacity, int baseCapacity) { }

    /**
     * A value of the unit that has no location: a hit count or a reading ({@code value}), its maximum where MegaMek has
     * one (-1 otherwise) and a text where the value is one.
     */
    record Vital(VitalGroup group, String code, int value, int max, String text) { }

    /**
     * A weapon of the Unit Display's weapon list: its description without state ({@code Mounted.getPlainDesc}); its
     * row in the weapon list ({@code row}: marks, location, shots, modes and the row's text); the ranges of
     * {@code WeaponType.getRanges} with its loaded ammunition (minimum, short, medium, long, extreme; 0 for no
     * minimum); whether it is out of action ({@code destroyed}: destroyed, missing or useless, or in a destroyed
     * location), jammed, or unusable for any reason ({@code crippled}: {@code Mounted.isCrippled}, which adds jams, an
     * empty magazine and a fired one-shot); whether it fired this round ({@code Mounted.isUsedThisRound}); and while
     * the sheet is open, the weapon display's statistics with its loaded ammunition (for no target, so a Centurion
     * Weapon System's longer range against a susceptible target is left out; null while the sheet is closed), a bay's
     * weapons, its quirks ("" without) and, for an own weapon that can switch ammunition, the bins it may load
     * ({@code WeaponDisplayData.ammoChoices}) and the loaded one's index among them (-1: none).
     */
    record RecordWeapon(int eqNum, String name, UnitDisplayData.RowParts row, List<Integer> ranges, boolean destroyed,
          boolean jammed, boolean crippled, boolean fired, @Nullable WeaponDisplayData.WeaponStats stats,
          List<String> bayMembers, String quirks, List<AmmoChoice> ammoChoices, int loadedAmmo) {
        RecordWeapon {
            ranges = List.copyOf(ranges);
            bayMembers = List.copyOf(bayMembers);
            ammoChoices = List.copyOf(ammoChoices);
        }

        String location() {
            return row.location();
        }

        /** The weapon list's shots, "loaded/total", or "" when the list shows none. */
        String shots() {
            return row.totalShots() < 0 ? "" : row.loadedShots() + "/" + row.totalShots();
        }
    }

    /** An ammunition bin a weapon may load: its unit (a trailer shares its ammunition), number and list entry. */
    record AmmoChoice(int carrierId, int eqNum, String label) {
        /** A bin of the unit's ammunition list, named by its entry; the number is the bin's on its own carrier. */
        static AmmoChoice of(Entity entity, AmmoMounted bin) {
            return new AmmoChoice(bin.getEntity().getId(), bin.getEntity().getEquipmentNum(bin),
                  WeaponDisplayData.formatAmmo(entity, bin));
        }

        /** Whether this is the bin: its carrier and its number there (two carriers of a train number bins alike). */
        boolean is(AmmoMounted bin) {
            return (bin.getEntity().getId() == carrierId) && (bin.getEntity().getEquipmentNum(bin) == eqNum);
        }
    }

    /**
     * Equipment with a mode list: the labels of the Systems tab's mode list, the index of the entry that list selects
     * ({@code selected}: the queued mode, else the current one; {@code setMode} takes such an index), the current and
     * queued mode's position in the mount's own mode list ({@code Mounted.getMode}; -1: none queued) and whether it may
     * be switched now.
     */
    record Equipment(int eqNum, String name, String location, List<String> modes, int selected, int mode,
          int pendingMode, boolean changeable) {
        Equipment {
            modes = List.copyOf(modes);
        }
    }

    /**
     * A unit-wide choice: its choices, the one chosen ({@code selected}: it takes effect at the end of the turn for
     * sensors, heat sinks, hidden activation and console roles) and the one in effect now ({@code current}; a choice
     * is pending while they differ), and whether it may be changed now.
     */
    record SystemControl(String id, String label, List<String> choices, int selected, int current, boolean enabled) {
        SystemControl {
            choices = List.copyOf(choices);
        }
    }

    /**
     * A crew seat: its role, the crew member's names, skills, hits and status text ("" when unhurt), whether the seat
     * is active or empty ({@code missing}), whether its role may be swapped, the portrait as the Pilot tab shows it
     * (null for an empty seat), the RPG gunnery skills ("L/M/B", "" while that option is off) and the Pilot tab's
     * further values of the game's options (toughness, fatigue, initiative and command bonus) as label and value.
     */
    record CrewSeat(int position, String role, String name, String nickname, int gunnery, int piloting, int hits,
          String status, boolean active, boolean missing, boolean swappable, BoardScene.Pixels portrait,
          String gunneryRpg, List<InfoRow> details) {
        CrewSeat {
            details = List.copyOf(details);
        }
    }

    /**
     * An ammunition bin the Systems tab lists, or a weapon the owner may jettison: its shots and full load, whether it
     * dumps, is hot-loaded, is a jettisonable weapon or carried ammunition (for no weapon of the unit), and for the
     * owner whether it may be dumped now and else the reason's message key ({@code UnitEquipmentActions.dumpBlocker}; "" for
     * another player's unit, which cannot be dumped).
     */
    record AmmoBin(int eqNum, String name, String location, int shots, int fullShots, boolean dumping,
          boolean hotLoaded, boolean jettison, boolean carried, boolean canDump, String dumpBlocker) { }

    /** A line of the unit's details: its section, a label ("" when the text is the whole line) and a unit it names. */
    record InfoRow(InfoSection section, String label, String text, int unitLink) { }

    /**
     * The record of one unit: whether the local player owns it and whether it has left the game ({@code removed}), its
     * paperdoll family ({@code GpuPaperdolls.family}), its damage level, the Unit Display's armor, systems, weapons,
     * pilot and extras content with the critical table's data, and for an own unit its controls. {@code conditions} is
     * the Extras tab's "Affected by" list, {@code carried} its "Carrying" lines with the searchlight, {@code abilities}
     * the crew's special abilities by group, {@code readoutRows} and {@code readout} the unit readout's rows and text.
     * While the unit sheet is closed ({@link #setSheetOpen}) the weapons have no statistics, bay weapons, quirks or
     * ammunition choices, and {@code info} holds only the carrier and the carried units. {@code damage} is the unit's
     * expected damage by range in hexes from 0 ({@link #damageCurve}), empty without a usable weapon.
     */
    record Snapshot(int unitId, boolean own, boolean removed, String paperdoll, String damageLevel,
          List<Location> locations, List<Vital> vitals, List<HitTrack> hitTracks, List<Shield> shields,
          List<Integer> heatTicks, List<String> heatTickEffects, int heatScale, WeaponDisplayData.HeatBuildup heatBuildup,
          List<RecordWeapon> weapons, List<Equipment> equipment, List<SystemControl> systems, List<CrewSeat> crew,
          List<TipUtil.OptionGroup> abilities, List<AmmoBin> ammo, List<String> conditions, List<String> carried,
          String unused, String lastTarget, List<InfoRow> info, List<EntityReadout.Row> readoutRows, String readout,
          List<Float> damage) {
        static final Snapshot EMPTY = new Snapshot(Entity.NONE, false, List.of(), List.of(), 0, List.of(), List.of(),
              List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", "", "");

        Snapshot {
            locations = List.copyOf(locations);
            vitals = List.copyOf(vitals);
            hitTracks = List.copyOf(hitTracks);
            shields = List.copyOf(shields);
            heatTicks = List.copyOf(heatTicks);
            heatTickEffects = List.copyOf(heatTickEffects);
            weapons = List.copyOf(weapons);
            equipment = List.copyOf(equipment);
            systems = List.copyOf(systems);
            crew = List.copyOf(crew);
            abilities = List.copyOf(abilities);
            ammo = List.copyOf(ammo);
            conditions = List.copyOf(conditions);
            carried = List.copyOf(carried);
            info = List.copyOf(info);
            readoutRows = List.copyOf(readoutRows);
            damage = List.copyOf(damage);
        }

        /**
         * The short form with the E9 record's parts only, the others empty: {@link #EMPTY} and GpuUnitPanelTest's
         * records use it.
         */
        Snapshot(int unitId, boolean own, List<Location> locations, List<Integer> heatTicks, int heatScale,
              List<RecordWeapon> weapons, List<Equipment> equipment, List<SystemControl> systems, List<CrewSeat> crew,
              List<TipUtil.OptionGroup> abilities, List<AmmoBin> ammo, List<String> conditions, List<String> carried,
              String unused, String lastTarget, String readout) {
            this(unitId, own, false, "", "", locations, List.of(), List.of(), List.of(), heatTicks, List.of(),
                  heatScale, new WeaponDisplayData.HeatBuildup(0, 0, ""), weapons, equipment, systems, crew, abilities, ammo,
                  conditions, carried, unused, lastTarget, List.of(), List.of(), readout, List.of());
        }

        /** The record with the parts that are costly to build: the readout (text and rows) and the battle value. */
        Snapshot withReadout(String text, List<EntityReadout.Row> rows, List<InfoRow> battleValue) {
            List<InfoRow> allInfo = new ArrayList<>(info);
            allInfo.addAll(battleValue);
            return new Snapshot(unitId, own, removed, paperdoll, damageLevel, locations, vitals, hitTracks, shields,
                  heatTicks, heatTickEffects, heatScale, heatBuildup, weapons, equipment, systems, crew, abilities,
                  ammo, conditions, carried, unused, lastTarget, allInfo, rows, text, damage);
        }
    }

    private final GpuBoardSource source;
    // GL thread writes, EDT reads at the next capture, as GpuBoardSource's card unit.
    private volatile boolean sheetOpen;
    // EDT state. The readout and the battle value are the capture's costly parts, so they have their own change key:
    // the unit instance, which the client replaces on every server update, and the rest of the record, which local
    // actions change. Portraits are shared by portrait, as they rarely change.
    private Snapshot snapshot = Snapshot.EMPTY;
    private Entity readoutUnit;
    private Snapshot readoutKey = Snapshot.EMPTY;
    private final Map<Portrait, BoardScene.Pixels> portraits = new HashMap<>();

    GpuUnitRecord(GpuBoardSource source) {
        this.source = source;
    }

    /**
     * GL thread: whether the unit sheet is open. Only then does the capture include the details that only the sheet
     * shows (the weapons' statistics, bay weapons, quirks and ammunition choices, and the unit's details other than its
     * carrier and carried units), which cost more than the rest of the record; the next capture follows the change.
     */
    void setSheetOpen(boolean open) {
        sheetOpen = open;
    }

    /**
     * EDT: the record of an identified unit, {@code Entity.NONE} for none; the previous instance while nothing changed.
     * A unit that has left the game is found among the game's removed units and marked {@code removed}. Every unit's
     * record has the Unit Display's content; the controls are filled for own units only. Nothing here touches the Unit
     * Display: its hidden weapon panel is the firing display's weapon selection.
     */
    Snapshot capture(int unitId) {
        GpuBoardSource.requireSwingThread();
        Game game = source.currentView().game;
        Entity entity = game.getEntity(unitId);
        boolean removed = false;
        if ((entity == null) && (unitId != Entity.NONE)) {
            entity = game.getEntityFromAllSources(unitId);
            removed = entity != null;
        }
        ClientGUI gui = source.currentView().getClientgui();
        boolean sheet = sheetOpen;
        Snapshot next = entity == null ? Snapshot.EMPTY
              : record(game, entity, removed, source.owned(entity), gui, portraits, sheet);
        if ((entity != readoutUnit) || !next.equals(readoutKey)) {
            readoutUnit = entity;
            readoutKey = next;
            if (entity != null) {
                EntityReadout readout = EntityReadout.createReadout(entity, false, false, entity.isUncrewed());
                next = next.withReadout(readout.getFullReadout(ViewFormatting.NONE), readout.getRows(),
                      sheet ? battleValue(game, entity, gui) : List.of());
            }
            if (!next.equals(snapshot)) {
                snapshot = next;
            }
        }
        return snapshot;
    }

    /**
     * The record without its costly parts, and without the sheet's details while the sheet is closed ({@code sheet}).
     * The controls are actions, which need the client that sends them, so an own unit has them only with a client
     * ({@code gui}).
     */
    private static Snapshot record(Game game, Entity entity, boolean removed, boolean own, ClientGUI gui,
          Map<Portrait, BoardScene.Pixels> portraits, boolean sheet) {
        boolean actions = own && (gui != null) && !removed;
        List<Integer> ticks = heatTicks(game, entity);
        return new Snapshot(entity.getId(), own, removed, GpuPaperdolls.family(entity),
              UnitToolTip.getDamageLevelDesc(entity, false), locations(entity), vitals(entity), hitTracks(entity),
              shields(entity), ticks, heatTickEffects(game, entity, ticks), heatScale(ticks),
              WeaponDisplayData.heatBuildup(game, entity), weapons(game, entity, actions, sheet),
              actions ? equipment(game, entity) : List.of(), actions ? systems(entity) : List.of(),
              crew(game, entity, actions, portraits),
              PilotToolTip.crewAbilities(entity), ammo(actions ? gui.getClient() : null, entity),
              UnitDisplayData.affectedBy(game, entity), carried(game, entity), entity.getUnusedString().strip(),
              lastTarget(entity), info(game, entity, gui, sheet), List.of(), "", damageCurve(game, entity));
    }

    /** The longest range the damage curve follows: artillery's map sheets reach far past any board's overlay. */
    private static final int DAMAGE_REACH_LIMIT = 30;
    /** The curves by unit instance, which the client replaces on every server update (EDT only). */
    private static final Map<Entity, List<Float>> DAMAGE_CURVES = new java.util.WeakHashMap<>();

    /**
     * A unit's expected damage at each range from 0 to its longest reach (DamageProfile: its working weapons weighted by
     * the crew's chance to hit, the best ammunition at each range), at most {@link #DAMAGE_REACH_LIMIT} hexes; empty
     * without a usable weapon.
     */
    static List<Float> damageCurve(Game game, Entity entity) {
        return DAMAGE_CURVES.computeIfAbsent(entity, unit -> {
            DamageProfile profile = DamageProfile.of(unit,
                  game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RANGE));
            if (!profile.hasWeapons()) {
                return List.of();
            }
            int reach = Math.min(profile.maxRange(), DAMAGE_REACH_LIMIT);
            List<Float> curve = new ArrayList<>(reach + 1);
            for (int range = 0; range <= reach; range++) {
                curve.add((float) profile.expectedDamage(range));
            }
            return List.copyOf(curve);
        });
    }

    /**
     * EDT: the armor part of a unit's record for a paperdoll shown apart from the card (the aimed shot dialog's): its
     * paperdoll family, locations and mounted shields as the record reads them, every other part empty.
     */
    static Snapshot armor(Entity entity) {
        return new Snapshot(entity.getId(), false, false, GpuPaperdolls.family(entity), "", locations(entity),
              List.of(), List.of(), shields(entity), List.of(), List.of(), 0,
              new WeaponDisplayData.HeatBuildup(0, 0, ""), List.of(), List.of(), List.of(), List.of(), List.of(),
              List.of(), List.of(), List.of(), "", "", List.of(), List.of(), "", List.of());
    }

    /**
     * The heat levels at which a Mek's heat effects change (the {@link HeatEffects} text differs from the level below),
     * ascending. Empty for other units, whose heat the Unit Display does not describe with these effects.
     */
    static List<Integer> heatTicks(Game game, Entity entity) {
        if (!(entity instanceof Mek mek)) {
            return List.of();
        }
        boolean tacOpsHeat = game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HEAT);
        boolean tsm = mek.hasTSM(false);
        List<Integer> ticks = new ArrayList<>();
        String previous = HeatEffects.getHeatEffects(0, tacOpsHeat, tsm);
        for (int heat = 1; heat < HEAT_SEARCH_LIMIT; heat++) {
            String effects = HeatEffects.getHeatEffects(heat, tacOpsHeat, tsm);
            if (!effects.equals(previous)) {
                ticks.add(heat);
            }
            previous = effects;
        }
        return ticks;
    }

    /** The heat effects ({@link HeatEffects}) that begin at each of the heat ticks. */
    private static List<String> heatTickEffects(Game game, Entity entity, List<Integer> ticks) {
        if (!(entity instanceof Mek mek)) {
            return List.of();
        }
        boolean tacOpsHeat = game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HEAT);
        return ticks.stream().map(tick -> HeatEffects.getHeatEffects(tick, tacOpsHeat, mek.hasTSM(false))).toList();
    }

    /** The heat bar's length: one past the highest tick, so every level with its own effects fits; 0 without ticks. */
    static int heatScale(List<Integer> ticks) {
        return ticks.isEmpty() ? 0 : ticks.getLast() + 1;
    }

    private static List<Location> locations(Entity entity) {
        List<Location> locations = new ArrayList<>();
        for (int loc = 0; loc < entity.locations(); loc++) {
            boolean rear = entity.hasRearArmor(loc);
            String caseTag = entity.hasCASEII(loc) ? "CASE II" : (entity.locationHasCase(loc) ? "CASE" : "");
            // A building and a handheld weapon name the hit location itself: its damage goes nowhere else
            int transfer = entity.getTransferLocation(new HitData(loc)).getLocation();
            locations.add(new Location(entity.getLocationAbbr(loc), entity.getLocationName(loc),
                  points(entity.getArmor(loc)), points(entity.getOArmor(loc)),
                  rear ? points(entity.getArmor(loc, true)) : 0, rear ? points(entity.getOArmor(loc, true)) : 0,
                  points(entity.getInternal(loc)), points(entity.getOInternal(loc)), entity.isLocationBad(loc),
                  entity.isLocationBlownOff(loc), entity.getLocationStatus(loc) == ILocationExposureStatus.BREACHED,
                  (transfer == loc) ? "" : abbr(entity, transfer), abbr(entity, entity.getDependentLocation(loc)),
                  entity.hasBARArmor(loc) ? entity.getBARRating(loc) : 0,
                  (entity instanceof Aero aero) ? aero.getThresh(loc) : 0, caseTag,
                  entity.getNumberOfCriticalSlots(loc), slots(entity, loc)));
        }
        return locations;
    }

    /** Values below zero are states (no armor, doomed, destroyed); the sheet shows them as no points. */
    private static int points(int value) {
        return Math.max(0, value);
    }

    /** A location's abbreviation, "" for none ({@code LOC_NONE}, {@code LOC_DESTROYED}). */
    private static String abbr(Entity entity, int loc) {
        return ((loc >= 0) && (loc < entity.locations())) ? entity.getLocationAbbr(loc) : "";
    }

    /**
     * The location's critical slots: every slot of a Mek, whose critical table shows its empty ones; only the filled
     * ones of other units, which have many (vehicles 25, aerospace units 100 per location).
     */
    private static List<Slot> slots(Entity entity, int loc) {
        List<Slot> slots = new ArrayList<>();
        for (int index = 0; index < entity.getNumberOfCriticalSlots(loc); index++) {
            CriticalSlot slot = entity.getCritical(loc, index);
            if (slot == null) {
                if (entity instanceof Mek) {
                    slots.add(new Slot(index, RecordSheetSlotNames.EMPTY, -1, -1, -1, false, false, false, false,
                          false, false, false, -1, -1, false, false));
                }
                continue;
            }
            boolean system = slot.getType() == CriticalSlot.TYPE_SYSTEM;
            Mounted<?> mounted = system ? null : slot.getMount();
            Mounted<?> second = system ? null : slot.getMount2();
            int shots = -1;
            int fullShots = -1;
            if ((mounted != null) && (mounted.getType() instanceof AmmoType)) {
                // A superheavy Mek's slot may hold two bins, whose shots count together (as MegaMekLab counts them)
                shots = mounted.getBaseShotsLeft();
                fullShots = mounted.getFullShots();
                if ((second != null) && (second.getType() instanceof AmmoType)) {
                    shots += second.getBaseShotsLeft();
                    fullShots += second.getFullShots();
                }
            }
            slots.add(new Slot(index, RecordSheetSlotNames.slotName(entity, loc, index), equipmentNum(entity, mounted),
                  equipmentNum(entity, second), system ? slot.getIndex() : -1, slot.isDamaged(),
                  slot.isDestroyed() || ((mounted != null) && mounted.isDestroyed()),
                  slot.isMissing() || ((mounted != null) && mounted.isMissing()),
                  slot.isBreached() || ((mounted != null) && mounted.isBreached()), slot.isOriginalArmored(),
                  slot.isOriginalArmored() && !slot.isArmored(),
                  (mounted != null) && !mounted.getType().isHittable(), shots, fullShots,
                  (mounted != null) && (shots >= 0) && mounted.isHotLoaded(),
                  (mounted != null) && (shots >= 0) && (mounted.isPendingDump() || mounted.isDumping())));
        }
        return slots;
    }

    private static int equipmentNum(Entity entity, Mounted<?> mounted) {
        return mounted == null ? -1 : entity.getEquipmentNum(mounted);
    }

    /** A Mek's hit boxes, for the systems it has (a {@code GYRO_NONE} Mek has no gyro box). */
    private static List<HitTrack> hitTracks(Entity entity) {
        if (!(entity instanceof Mek mek)) {
            return List.of();
        }
        List<HitTrack> tracks = new ArrayList<>();
        tracks.add(new HitTrack(Track.ENGINE, mek.getEngineHits(), mek.engineHitsToDestroy()));
        if ((mek.getGyroType() != Mek.GYRO_NONE) && (systemSlots(mek, Mek.SYSTEM_GYRO) > 0)) {
            tracks.add(new HitTrack(Track.GYRO, mek.getGyroHits(), mek.gyroHitsToDestroy()));
        }
        if (systemSlots(mek, Mek.SYSTEM_SENSORS) > 0) {
            tracks.add(new HitTrack(Track.SENSORS, systemHits(mek, Mek.SYSTEM_SENSORS), mek.sensorHitsToDestroy()));
        }
        if (systemSlots(mek, Mek.SYSTEM_LIFE_SUPPORT) > 0) {
            // Life support fails at its first hit (the heat and breathing checks test for any hit)
            tracks.add(new HitTrack(Track.LIFE_SUPPORT, systemHits(mek, Mek.SYSTEM_LIFE_SUPPORT), 1));
        }
        if (mek instanceof LandAirMek lam) {
            tracks.add(new HitTrack(Track.AVIONICS, lam.getAvionicsHits(), lam.avionicsHitsToDestroy()));
            int gearHits = 0;
            for (int loc = 0; loc < lam.locations(); loc++) {
                gearHits += lam.getBadCriticalSlots(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_LANDING_GEAR, loc);
            }
            tracks.add(new HitTrack(Track.LANDING_GEAR, gearHits, systemSlots(lam, LandAirMek.LAM_LANDING_GEAR)));
        }
        return tracks;
    }

    private static int systemSlots(Mek mek, int system) {
        int slots = 0;
        for (int loc = 0; loc < mek.locations(); loc++) {
            slots += mek.getNumberOfCriticalSlots(CriticalSlot.TYPE_SYSTEM, system, loc);
        }
        return slots;
    }

    private static int systemHits(Mek mek, int system) {
        int hits = 0;
        for (int loc = 0; loc < mek.locations(); loc++) {
            hits += mek.getHitCriticalSlots(CriticalSlot.TYPE_SYSTEM, system, loc);
        }
        return hits;
    }

    /** Every shield on an arm of a biped or tripod Mek. */
    private static List<Shield> shields(Entity entity) {
        if (!(entity instanceof MekWithArms mek)) {
            return List.of();
        }
        List<Shield> shields = new ArrayList<>();
        for (MiscMounted misc : mek.getMisc()) {
            int loc = misc.getLocation();
            if (misc.getType().hasFlag(MiscType.F_SHIELD)
                  && ((loc == Mek.LOC_LEFT_ARM) || (loc == Mek.LOC_RIGHT_ARM))) {
                shields.add(new Shield(mek.getLocationAbbr(loc), mek.getEquipmentNum(misc), misc.getName(),
                      mek.isShieldActive(misc), misc.getDamageAbsorption(mek, loc),
                      misc.getBaseDamageAbsorptionRate(), misc.getCurrentDamageCapacity(mek, loc),
                      misc.getBaseDamageCapacity()));
            }
        }
        return shields;
    }

    /**
     * The unit's values that have no location, by group:
     * <ul>
     *     <li>SYSTEM (vehicles): ENGINE and SENSORS hits of their boxes ({@code UnitToolTip.systemCrits}), COMMANDER
     *     and DRIVER hit (1 of 1);</li>
     *     <li>MOTIVE: a ground vehicle's MINOR_MOTIVE, MODERATE_MOTIVE and HEAVY_MOTIVE damage (1 of 1 each, as the
     *     record sheet checks each box), a VTOL's ROTOR damage (the cruising MP it costs, of the original cruising MP,
     *     which immobilize the VTOL);</li>
     *     <li>STABILIZER and TURRET (vehicles): per location abbreviation, 1 when the stabilizer is hit or the turret
     *     locked;</li>
     *     <li>AERO (aerospace units): SI of the original, ENGINE hits of those that destroy it, AVIONICS, FCS and
     *     SENSORS hits; small craft and large craft also THRUST_LEFT and THRUST_RIGHT hits and LIFE_SUPPORT hit (1 of
     *     1), units that can land GEAR hit (1 of 1), JumpShips, WarShips and space stations CIC hits;</li>
     *     <li>SHIP (large craft): K-F drive (KF) and SAIL integrity of the original, docking collars (DC) intact of
     *     all (a unit with docking collars), a DropShip's KF_BOOM and COLLAR damage (1 of 1);</li>
     *     <li>FLIGHT (aerospace units and LAMs): VELOCITY, ALTITUDE, FUEL of the original;</li>
     *     <li>MP: MP, the walking (safe thrust) MP of the original, with the original walking, running and jumping MP
     *     as text; then each cause the unit tooltip marks for MP that differ from the original
     *     ({@code UnitToolTip.movementCauses}) by its name: HEAT with the walking MP it costs, GRAVITY with the gravity
     *     as text, the others 0;</li>
     *     <li>TROOPERS: active troopers of the original (battle armor, conventional infantry);</li>
     *     <li>KIT (conventional infantry): ARMOR, the Unit Display's armor text (damage divisor, "E" when
     *     encumbering, and its specials);</li>
     *     <li>FIGHTER (fighter squadrons): per fighter id, its armor of the original with its name as text.</li>
     * </ul>
     */
    private static List<Vital> vitals(Entity entity) {
        List<Vital> vitals = new ArrayList<>();
        if (entity instanceof Tank tank) {
            for (UnitToolTip.SystemCrit crit : UnitToolTip.systemCrits(tank)) {
                if (!crit.present()) {
                    continue;
                }
                int total = crit.good() + crit.hits();
                switch (crit.code()) {
                    case "STABILIZER" -> vitals.add(new Vital(VitalGroup.STABILIZER,
                          tank.getLocationAbbr(crit.location()), crit.hits(), total, ""));
                    case "TURRET_LOCKED" -> vitals.add(new Vital(VitalGroup.TURRET,
                          tank.getLocationAbbr(crit.location()), crit.hits(), total, ""));
                    case "MINOR_MOTIVE", "MODERATE_MOTIVE", "HEAVY_MOTIVE" -> vitals.add(new Vital(VitalGroup.MOTIVE,
                          crit.code(), crit.hits(), total, ""));
                    default -> vitals.add(new Vital(VitalGroup.SYSTEM, crit.code(), crit.hits(), total, ""));
                }
            }
            if (tank instanceof VTOL) {
                vitals.add(new Vital(VitalGroup.MOTIVE, "ROTOR", tank.getMotiveDamage(), tank.getOriginalWalkMP(),
                      ""));
            }
            vitals.add(new Vital(VitalGroup.SYSTEM, "COMMANDER", tank.isCommanderHit() ? 1 : 0, 1, ""));
            vitals.add(new Vital(VitalGroup.SYSTEM, "DRIVER", tank.isDriverHit() ? 1 : 0, 1, ""));
        }
        if (entity instanceof Aero aero) {
            vitals.add(new Vital(VitalGroup.AERO, "SI", aero.getSI(), aero.getOSI(), ""));
            vitals.add(new Vital(VitalGroup.AERO, "ENGINE", aero.getEngineHits(), aero.getMaxEngineHits(), ""));
            vitals.add(new Vital(VitalGroup.AERO, "AVIONICS", aero.getAvionicsHits(), -1, ""));
            vitals.add(new Vital(VitalGroup.AERO, "FCS", aero.getFCSHits(), -1, ""));
            vitals.add(new Vital(VitalGroup.AERO, "SENSORS", aero.getSensorHits(), -1, ""));
            // The critical hits each unit type can take (the potential criticals of Aero, SmallCraft and Jumpship)
            boolean craft = (aero instanceof SmallCraft) || (aero instanceof Jumpship);
            if (craft) {
                vitals.add(new Vital(VitalGroup.AERO, "THRUST_LEFT", aero.getLeftThrustHits(), -1, ""));
                vitals.add(new Vital(VitalGroup.AERO, "THRUST_RIGHT", aero.getRightThrustHits(), -1, ""));
                vitals.add(new Vital(VitalGroup.AERO, "LIFE_SUPPORT", aero.hasLifeSupport() ? 0 : 1, 1, ""));
            }
            if (!(aero instanceof Jumpship)) {
                vitals.add(new Vital(VitalGroup.AERO, "GEAR", aero.isGearHit() ? 1 : 0, 1, ""));
            }
            if (aero instanceof Jumpship ship) {
                vitals.add(new Vital(VitalGroup.AERO, "CIC", ship.getCICHits(), -1, ""));
                vitals.add(new Vital(VitalGroup.SHIP, "KF", ship.getKFIntegrity(), ship.getOKFIntegrity(), ""));
                vitals.add(new Vital(VitalGroup.SHIP, "SAIL", ship.getSailIntegrity(), ship.getOSailIntegrity(), ""));
            }
            List<DockingCollar> collars = aero.getDockingCollars();
            if (!collars.isEmpty()) {
                vitals.add(new Vital(VitalGroup.SHIP, "DC",
                      (int) collars.stream().filter(collar -> !collar.isDamaged()).count(), collars.size(), ""));
            }
            if (aero instanceof Dropship ship) {
                vitals.add(new Vital(VitalGroup.SHIP, "KF_BOOM", ship.isKFBoomDamaged() ? 1 : 0, 1, ""));
                vitals.add(new Vital(VitalGroup.SHIP, "COLLAR", ship.isDockCollarDamaged() ? 1 : 0, 1, ""));
            }
            if (aero instanceof FighterSquadron squadron) {
                for (Entity fighter : squadron.getSubEntities()) {
                    vitals.add(new Vital(VitalGroup.FIGHTER, Integer.toString(fighter.getId()),
                          fighter.getTotalArmor(), fighter.getTotalOArmor(), fighter.getShortName()));
                }
            }
        }
        if (entity instanceof IAero flight) {
            vitals.add(new Vital(VitalGroup.FLIGHT, "VELOCITY", flight.getCurrentVelocity(), -1, ""));
            vitals.add(new Vital(VitalGroup.FLIGHT, "ALTITUDE", flight.getAltitude(), -1, ""));
            vitals.add(new Vital(VitalGroup.FLIGHT, "FUEL", flight.getCurrentFuel(), flight.getFuel(), ""));
        }
        if (entity instanceof BattleArmor armor) {
            vitals.add(new Vital(VitalGroup.TROOPERS, "TROOPERS", armor.getNumberActiveTroopers(),
                  armor.getOriginalTrooperCount(), ""));
        } else if (entity instanceof Infantry infantry) {
            vitals.add(new Vital(VitalGroup.TROOPERS, "TROOPERS", infantry.getActiveTroopers(),
                  infantry.getOriginalTrooperCount(), ""));
        }
        if (entity instanceof ConvInfantry infantry) {
            vitals.add(new Vital(VitalGroup.KIT, "ARMOR", 0, -1, infantry.getArmorDesc()));
        }
        vitals.add(new Vital(VitalGroup.MP, "MP", entity.getWalkMP(), entity.getOriginalWalkMP(),
              entity.getOriginalWalkMP() + "/" + entity.getOriginalRunMP() + "/" + entity.getOriginalJumpMP()));
        if (!entity.isBuildingEntityOrGunEmplacement()) {
            for (UnitToolTip.MovementCause cause : UnitToolTip.movementCauses(entity)) {
                vitals.add(new Vital(VitalGroup.MP, cause.name(), (cause == UnitToolTip.MovementCause.HEAT)
                      ? entity.getWalkMP(MPCalculationSetting.NO_HEAT) - entity.getWalkMP() : 0, -1,
                      (cause == UnitToolTip.MovementCause.GRAVITY)
                            ? String.valueOf(entity.getGame().getPlanetaryConditions().getGravity()) : ""));
            }
        }
        return vitals;
    }

    /**
     * The weapons in the order and selection of the Unit Display's weapon list; while the sheet is open ({@code sheet})
     * with the weapon display's statistics for their loaded ammunition, a bay's weapons, the quirks and, for the
     * owner's weapons ({@code actions}), the ammunition they may load.
     */
    private static List<RecordWeapon> weapons(Game game, Entity entity, boolean actions, boolean sheet) {
        return WeaponDisplayData.listedWeapons(entity).stream().map(weapon -> {
            Entity carrier = weapon.getEntity();
            int loc = weapon.getLocation();
            // A weapon without a minimum range has a negative one (WeaponType.WEAPON_NA); the record shows 0.
            List<Integer> ranges = Arrays.stream(weapon.getType().getRanges(weapon)).map(GpuUnitRecord::points)
                  .boxed().toList();
            List<AmmoChoice> choices = List.of();
            int loaded = -1;
            if (actions && sheet) {
                WeaponMounted member = ammoWeapon(weapon);
                List<AmmoMounted> bins = WeaponDisplayData.ammoChoices(entity, weapon, member).ammo();
                choices = bins.stream().map(bin -> AmmoChoice.of(entity, bin)).toList();
                loaded = (member.getLinkedAmmo() == null) ? -1 : bins.indexOf(member.getLinkedAmmo());
            }
            return new RecordWeapon(entity.getEquipmentNum(weapon), weapon.getPlainDesc(),
                  UnitDisplayData.rowParts(game, weapon), ranges,
                  weapon.isInoperable() || ((loc >= 0) && carrier.isLocationBad(loc)),
                  weapon.isJammed(), weapon.isCrippled(), weapon.isUsedThisRound(),
                  sheet ? WeaponDisplayData.stats(game, entity, weapon, weapon.getLinkedAmmo(), false) : null,
                  sheet ? weapon.getBayWeapons().stream().map(Mounted::getDesc).toList() : List.of(),
                  sheet ? weapon.getQuirkList(", ") : "", choices, loaded);
        }).toList();
    }

    /**
     * The weapon whose ammunition a listed weapon offers: a bay's first, as the Unit Display's lists at first; the
     * record and the fire orders load bins through it.
     */
    static WeaponMounted ammoWeapon(WeaponMounted weapon) {
        return ((weapon.getType() instanceof BayWeapon) && !weapon.getBayWeapons().isEmpty())
              ? weapon.getBayWeapon(0) : weapon;
    }

    /** Every piece of equipment the Systems tab lists with a mode list, as it offers it to the owner. */
    private static List<Equipment> equipment(Game game, Entity entity) {
        List<Equipment> equipment = new ArrayList<>();
        for (Mounted<?> mounted : entity.getEquipment()) {
            if (!switchable(entity, mounted)) {
                continue;
            }
            UnitEquipmentActions.ModeChoices choices = UnitEquipmentActions.modeChoices(game, entity, mounted);
            if (!choices.labels().isEmpty()) {
                equipment.add(new Equipment(entity.getEquipmentNum(mounted), mounted.getName(),
                      location(entity, mounted), choices.labels(), choices.selectedIndex(),
                      modeIndex(mounted, mounted.curMode()),
                      // MegaMek's rule for a queued switch (SystemPanel): a pending mode other than "None"
                      mounted.pendingMode().equals(Mounted.MODE_NONE) ? -1 : modeIndex(mounted, mounted.pendingMode()),
                      choices.enabled()));
            }
        }
        return equipment;
    }

    /** Whether the Systems tab can offer the equipment's mode list: it has modes and the tab lists it. */
    private static boolean switchable(Entity entity, Mounted<?> mounted) {
        return (mounted.getType() != null) && mounted.hasModes() && UnitEquipmentActions.isListed(entity, mounted);
    }

    /** The index of a mode in the equipment's mode list, -1 for none (no queued switch). */
    private static int modeIndex(Mounted<?> mounted, EquipmentMode mode) {
        for (int index = 0; index < mounted.getModesCount(); index++) {
            if (mounted.getMode(index).equals(mode)) {
                return index;
            }
        }
        return -1;
    }

    /** The unit-wide choices of the Unit Display's Extras, Weapons and Pilot tabs, with their current values. */
    private static List<SystemControl> systems(Entity entity) {
        List<SystemControl> systems = new ArrayList<>();
        if (!entity.getSensors().isEmpty()) {
            systems.add(new SystemControl(SENSORS, Messages.getString("MekDisplay.CurrentSensors"),
                  UnitDisplayData.sensorLabels(entity), UnitDisplayData.nextSensorIndex(entity), activeSensorIndex(entity),
                  true));
        }
        if (entity instanceof Mek mek) {
            List<String> sinks = new ArrayList<>();
            for (int count = 0; count <= mek.getNumberOfSinks(); count++) {
                sinks.add(UnitDisplayData.activeSinksText(mek, count));
            }
            systems.add(new SystemControl(HEAT_SINKS, Messages.getString("MekDisplay.activeSinksLabel"), sinks,
                  mek.getActiveSinksNextRound(), mek.getActiveSinks(), true));
        }
        if (entity.isHidden()) {
            // Choice 0 is "stop activating": the unit stays hidden unless another is chosen
            systems.add(new SystemControl(HIDDEN, Messages.getString("MekDisplay.ActivateHidden.Label"),
                  UnitDisplayData.HIDDEN_ACTIVATION_PHASES.stream().map(UnitDisplayData::hiddenActivationLabel).toList(),
                  UnitDisplayData.HIDDEN_ACTIVATION_PHASES.indexOf(entity.getHiddenActivationPhase()), 0, true));
        }
        int order = entity.getWeaponSortOrder().ordinal();
        systems.add(new SystemControl(WEAPON_ORDER, Messages.getString("MekDisplay.WeaponSortOrder.label"),
              Stream.of(WeaponSortOrder.values()).map(WeaponSortOrder::toString).toList(), order, order, true));
        if (entity.getCrew().getCrewType().equals(CrewType.COMMAND_CONSOLE)) {
            // Choice 1 swaps the roles at the end of the turn
            systems.add(new SystemControl(CONSOLE_ROLES, Messages.getString("PilotMapSet.swapRoles.text"),
                  List.of(Messages.getString("PilotMapSet.keepRoles.text"),
                        Messages.getString("PilotMapSet.swapRoles.text")),
                  entity.getCrew().getSwapConsoleRoles() ? 1 : 0, 0, UnitDisplayData.canSwapConsoleRoles(entity)));
        }
        return systems;
    }

    /**
     * The index of the sensor in use, by the Extras tab's rule for the chosen one ({@code UnitDisplayData.nextSensorIndex}:
     * the last sensor of its type); -1 before the game has set one.
     */
    private static int activeSensorIndex(Entity entity) {
        int index = -1;
        for (int sensor = 0; sensor < entity.getSensors().size(); sensor++) {
            if ((entity.getActiveSensor() != null)
                  && (entity.getSensors().get(sensor).type() == entity.getActiveSensor().type())) {
                index = sensor;
            }
        }
        return index;
    }

    /**
     * Every crew seat with its portrait (shared by portrait) and the Pilot tab's values of the game's options; for an
     * own unit with a client, the command console's two seats swap (CONSOLE_ROLES).
     */
    private static List<CrewSeat> crew(Game game, Entity entity, boolean actions,
          Map<Portrait, BoardScene.Pixels> portraits) {
        Crew crew = entity.getCrew();
        boolean swappable = actions && UnitDisplayData.canSwapConsoleRoles(entity);
        boolean rpgGunnery = game.getOptions().booleanOption(OptionsConstants.RPG_RPG_GUNNERY);
        List<CrewSeat> seats = new ArrayList<>();
        for (int position = 0; position < crew.getSlotCount(); position++) {
            boolean missing = crew.isMissing(position);
            List<InfoRow> details = new ArrayList<>();
            if (!missing) {
                seatDetail(details, game, OptionsConstants.RPG_TOUGHNESS, "PilotMapSet.toughBL",
                      crew.getToughness(position));
                seatDetail(details, game, OptionsConstants.ADVANCED_TAC_OPS_FATIGUE, "PilotMapSet.fatigueBL",
                      crew.getCrewFatigue(position));
                seatDetail(details, game, OptionsConstants.RPG_INDIVIDUAL_INITIATIVE, "PilotMapSet.initBL",
                      crew.getInitBonus());
                seatDetail(details, game, OptionsConstants.RPG_COMMAND_INIT, "PilotMapSet.commandBL",
                      crew.getCommandBonus());
            }
            String gunneryRpg = (rpgGunnery && !missing) ? crew.getGunneryL(position) + "/"
                  + crew.getGunneryM(position) + "/" + crew.getGunneryB(position) : "";
            seats.add(new CrewSeat(position, crew.getCrewType().getRoleName(position), crew.getName(position),
                  crew.getNickname(position), crew.getGunnery(position), crew.getPiloting(position),
                  crew.getHits(position), crew.getStatusDesc(position), crew.isActive(position), missing,
                  swappable && (position < 2), missing ? null : portrait(crew.getPortrait(position), portraits),
                  gunneryRpg, details));
        }
        return seats;
    }

    /** A Pilot tab value that its game option shows. */
    private static void seatDetail(List<InfoRow> details, Game game, String option, String labelKey, int value) {
        if (game.getOptions().booleanOption(option)) {
            details.add(new InfoRow(InfoSection.CREW, Messages.getString(labelKey), Integer.toString(value),
                  Entity.NONE));
        }
    }

    /**
     * The portrait's pixels as the Pilot tab shows it (72 pixels wide; MegaMek's default portrait or its missing-image
     * picture when the file is not found), loaded once per portrait file.
     */
    private static BoardScene.Pixels portrait(Portrait portrait, Map<Portrait, BoardScene.Pixels> portraits) {
        return portraits.computeIfAbsent(portrait.clone(), key -> BoardScene.Pixels.copy(key.getImage()));
    }

    /**
     * Every ammunition bin the Systems tab lists (in a critical slot; the ammunition of a one-shot launcher or of a
     * support vehicle's infantry weapon has none), and every weapon the owner may jettison now (weapon packs, battle
     * armor launchers); only with the client of an own unit ({@code client} not null) can a bin be dumped.
     */
    private static List<AmmoBin> ammo(Client client, Entity entity) {
        List<AmmoBin> bins = new ArrayList<>();
        for (Mounted<?> mounted : entity.getEquipment()) {
            // Only ammunition and weapons can be dumped (UnitEquipmentActions.dumpBlocker)
            if (!((mounted.getType() instanceof AmmoType) || (mounted.getType() instanceof WeaponType))
                  || !UnitEquipmentActions.isListed(entity, mounted)) {
                continue;
            }
            String blocker = (client == null) ? "" : UnitEquipmentActions.dumpBlocker(client, entity, mounted);
            boolean canDump = (client != null) && blocker.isEmpty();
            boolean isAmmo = mounted.getType() instanceof AmmoType;
            if (canDump || isAmmo) {
                bins.add(new AmmoBin(entity.getEquipmentNum(mounted), mounted.getName(), location(entity, mounted),
                      mounted.getUsableShotsLeft(), mounted.getFullShots(),
                      mounted.isPendingDump() || mounted.isDumping(), mounted.isHotLoaded(), !isAmmo,
                      (mounted instanceof AmmoMounted bin) && !UnitToolTip.feedsAWeaponOnThisUnit(entity, bin),
                      canDump, blocker));
            }
        }
        return bins;
    }

    /** The Extras tab's "Carrying" lines, then its searchlight state when the unit has a searchlight. */
    private static List<String> carried(Game game, Entity entity) {
        List<String> carried = new ArrayList<>(UnitDisplayData.carried(game, entity));
        String searchlight = UnitDisplayData.searchlightText(entity);
        if (!searchlight.isEmpty()) {
            carried.add(searchlight);
        }
        return carried;
    }

    /** The name of the unit's last target, "" for none. */
    private static String lastTarget(Entity entity) {
        return entity.getLastTarget() == Entity.NONE ? ""
              : Objects.requireNonNullElse(entity.getLastTargetDisplayName(), "");
    }

    /**
     * The unit's carrier and carried units (with links), and while the sheet is open ({@code sheet}) its details from
     * the unit tooltip's sections (as plain text lines), its base piloting roll and its hex.
     */
    private static List<InfoRow> info(Game game, Entity entity, ClientGUI gui, boolean sheet) {
        List<InfoRow> info = new ArrayList<>();
        Entity carrier = game.getEntity(entity.getTransportId());
        if (carrier != null) {
            info.add(new InfoRow(InfoSection.CARRIER, "", carrier.getShortName(), carrier.getId()));
        }
        for (Entity loaded : entity.getLoadedUnits()) {
            info.add(new InfoRow(InfoSection.TRANSPORT, "", loaded.getShortName(), loaded.getId()));
        }
        if (!sheet) {
            return info;
        }
        boolean gunEmplacement = entity.isBuildingEntityOrGunEmplacement();
        if (!gunEmplacement) {
            lines(info, InfoSection.MOVEMENT, UnitToolTip.getMovementInfo(game, entity));
        }
        lines(info, InfoSection.STATUS, UnitToolTip.getUnitStatus(game, entity, gunEmplacement));
        lines(info, InfoSection.STATUS, UnitToolTip.getFortificationStatus(entity));
        lines(info, InfoSection.STATUS, UnitToolTip.getVariableRangeTargetingInfo(entity));
        lines(info, InfoSection.SEEN_BY, UnitToolTip.getSeenByInfo(game, game.getOptions(), entity));
        lines(info, InfoSection.SENSOR_RANGE, UnitToolTip.getSensorInfo(game.getOptions(), entity,
              game.getPlanetaryConditions()));
        lines(info, InfoSection.NETWORK, UnitToolTip.c3Info(entity, true).toString());
        if (entity.partOfForce()) {
            lines(info, InfoSection.UNIT, UnitToolTip.getForceInfo(entity));
        }
        lines(info, InfoSection.UNIT, UnitToolTip.getQuirks(entity, game, true));
        lines(info, InfoSection.REPAIRS, UnitToolTip.getPartialRepairs(entity, true));
        PilotingRollData roll = entity.getBasePilotingRoll();
        info.add(new InfoRow(InfoSection.PSR, roll.getValueAsString(), roll.getDesc(), Entity.NONE));
        // The Unit Display's summary shows the unit's hex the same way (SummaryPanel)
        Hex hex = game.getHex(entity.getBoardLocation());
        if (hex != null) {
            lines(info, InfoSection.HEX, HexTooltip.getTerrainTip(hex, entity.getBoardId(), game));
            lines(info, InfoSection.HEX, HexTooltip.getHexTip(hex, (gui == null) ? null : gui.getClient(),
                  entity.getBoardId()));
        }
        return info;
    }

    /**
     * The unit tooltip's battle value line (current and initial; an enemy's is hidden as the unit tooltip hides it
     * under double blind), costly enough to be built with the readout.
     */
    private static List<InfoRow> battleValue(Game game, Entity entity, ClientGUI gui) {
        List<InfoRow> info = new ArrayList<>();
        lines(info, InfoSection.UNIT, UnitToolTip.getBvInfo(game.getOptions(), entity,
              (gui == null) ? null : gui.getClient().getLocalPlayer(), true));
        return info;
    }

    /** Adds each line of a unit tooltip section, as plain text, to the section. */
    private static void lines(List<InfoRow> info, InfoSection section, String html) {
        for (String line : GpuBoardWindow.plainText(html).split("\n")) {
            if (!line.isBlank()) {
                info.add(new InfoRow(section, "", line.strip(), Entity.NONE));
            }
        }
    }

    /**
     * The equipment's location abbreviation, with the second location of a split mount and " (R)" for a rear mount;
     * "" for equipment in no location, such as the heat sinks inside an engine. The weapon list shows the rear mark and
     * further mount marks (turrets, directional mount) after the name instead ({@code Mounted.getDesc}).
     */
    private static String location(Entity entity, Mounted<?> mounted) {
        if (mounted.getLocation() == Entity.LOC_NONE) {
            return "";
        }
        String location = entity.getLocationAbbr(mounted.getLocation());
        if (mounted.isSplit()) {
            location += "/" + entity.getLocationAbbr(mounted.getSecondLocation());
        }
        return mounted.isRearMounted() ? location + " (R)" : location;
    }

    // GL-safe commands. Each names the unit of the record the view shows, is posted through the source's input guard,
    // then rechecks that the local player owns that unit and that the Unit Display would offer the action now, and runs
    // the Unit Display's own action.

    /**
     * Switches equipment of the unit to a mode, by its index in {@link Equipment#modes()}. As the Systems tab's mode
     * list acts only on a changed selection, the entry it selects already sends nothing.
     */
    void setMode(int unitId, int eqNum, int mode) {
        source.command(() -> mode(unitId, eqNum, mode));
    }

    /** Picks one of the choices of a {@link SystemControl} of the unit; an unchanged choice sends nothing. */
    void setSystem(int unitId, String id, int choice) {
        source.command(() -> system(unitId, id, choice));
    }

    /**
     * Moves a weapon of the unit by {@code delta} places in its listed order, which becomes the unit's custom order. As
     * with the Unit Display's list drag, the phase display sends the changed order later.
     */
    void moveWeapon(int unitId, int eqNum, int delta) {
        source.command(() -> move(unitId, eqNum, delta));
    }

    /** Starts or cancels dumping an ammunition bin (or jettisoning a weapon) of the unit, once confirmed. */
    void setDumping(int unitId, int ammoEqNum, boolean dump) {
        source.command(() -> dumping(unitId, ammoEqNum, dump));
    }

    /**
     * Loads one of the bins a weapon of the unit may load ({@link RecordWeapon#ammoChoices}), as picking it in the Unit
     * Display's ammunition list does ({@code WeaponDisplayData.loadAmmo}); a bin the list no longer offers, or the one
     * loaded, sends nothing. A queued attack keeps the bin it was declared with: the local firing actor's weapons
     * change their bin through the fire orders, which declare the attack again.
     */
    void setAmmo(int unitId, int weaponEqNum, AmmoChoice choice) {
        source.command(() -> ammo(unitId, weaponEqNum, choice));
    }

    private void mode(int unitId, int eqNum, int mode) {
        Entity entity = ownUnit(unitId);
        Mounted<?> mounted = entity == null ? null : entity.getEquipment(eqNum);
        if ((mounted != null) && switchable(entity, mounted)) {
            UnitEquipmentActions.ModeChoices choices = UnitEquipmentActions.modeChoices(source.currentView().game, entity, mounted);
            if (choices.enabled() && (mode >= 0) && (mode < choices.labels().size())
                  && (mode != choices.selectedIndex())) {
                UnitEquipmentActions.changeMode(gui(), entity, mounted, mode);
            }
        }
    }

    private void system(int unitId, String id, int choice) {
        Entity entity = ownUnit(unitId);
        SystemControl control = entity == null ? null : systems(entity).stream()
              .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
        if ((control == null) || !control.enabled() || (choice < 0) || (choice >= control.choices().size())
              || (choice == control.selected())) {
            return;
        }
        ClientGUI gui = gui();
        switch (id) {
            case SENSORS -> UnitDisplayData.setNextSensor(gui, entity, choice);
            case HEAT_SINKS -> UnitDisplayData.setActiveSinks(gui, (Mek) entity, choice);
            case HIDDEN -> gui.getClient().sendActivateHidden(entity.getId(),
                  UnitDisplayData.HIDDEN_ACTIVATION_PHASES.get(choice));
            case WEAPON_ORDER -> {
                entity.setWeaponSortOrder(WeaponSortOrder.values()[choice]);
                WeaponDisplayData.sendWeaponOrder(gui.getClient(), entity);
            }
            case CONSOLE_ROLES -> UnitDisplayData.swapConsoleRoles(gui.getClient(), entity, choice == 1);
            default -> { }
        }
    }

    private void move(int unitId, int eqNum, int delta) {
        Entity entity = ownUnit(unitId);
        if ((entity == null) || (eqNum < 0)) {
            return;
        }
        List<WeaponMounted> order = new ArrayList<>(WeaponDisplayData.listedWeapons(entity));
        int from = -1;
        for (int index = 0; index < order.size(); index++) {
            if (entity.getEquipmentNum(order.get(index)) == eqNum) {
                from = index;
            }
        }
        int to = from < 0 ? from : Math.clamp((long) from + delta, 0, order.size() - 1);
        if (to != from) {
            WeaponDisplayData.moveWeapon(entity, order.get(from), order.get(to));
        }
    }

    private void dumping(int unitId, int ammoEqNum, boolean dump) {
        Entity entity = ownUnit(unitId);
        Mounted<?> mounted = entity == null ? null : entity.getEquipment(ammoEqNum);
        if ((mounted != null) && (mounted.isPendingDump() != dump) && UnitEquipmentActions.isListed(entity, mounted)
              && UnitEquipmentActions.canDump(gui().getClient(), entity, mounted)) {
            UnitEquipmentActions.toggleDump(gui(), entity, mounted);
        }
    }

    private void ammo(int unitId, int weaponEqNum, AmmoChoice choice) {
        Entity entity = ownUnit(unitId);
        if ((entity == null) || !(entity.getEquipment(weaponEqNum) instanceof WeaponMounted weapon)
              || !WeaponDisplayData.listedWeapons(entity).contains(weapon)) {
            return;
        }
        WeaponMounted member = ammoWeapon(weapon);
        AmmoMounted bin = WeaponDisplayData.ammoChoices(entity, weapon, member).ammo().stream().filter(choice::is)
              .findFirst().orElse(null);
        if ((bin != null) && (bin != member.getLinkedAmmo())) {
            WeaponDisplayData.loadAmmo(gui().getClient(), entity, weapon, member, bin);
        }
    }

    /** EDT: the unit while it is in the game and the local player, who has a client, owns it; else null. */
    private Entity ownUnit(int unitId) {
        Entity entity = source.currentView().game.getEntity(unitId);
        return (entity != null) && (gui() != null) && source.owned(entity) ? entity : null;
    }

    private ClientGUI gui() {
        return source.currentView().getClientgui();
    }
}
