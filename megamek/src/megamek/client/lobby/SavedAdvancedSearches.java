/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.JsonParseException;
import com.formdev.flatlaf.extras.components.FlatTriStateCheckBox.State;
import jakarta.annotation.Nullable;
import megamek.client.lobby.RequisitionAdvancedSearch.Choice;
import megamek.client.lobby.RequisitionAdvancedSearch.Equipment;
import megamek.client.lobby.RequisitionAdvancedSearch.EquipmentChoice;
import megamek.client.lobby.RequisitionAdvancedSearch.Field;
import megamek.client.lobby.RequisitionAdvancedSearch.Flag;
import megamek.client.lobby.RequisitionAdvancedSearch.Level;
import megamek.client.lobby.RequisitionAdvancedSearch.Minimums;
import megamek.client.lobby.RequisitionAdvancedSearch.Movement;
import megamek.client.lobby.RequisitionAdvancedSearch.Picks;
import megamek.client.lobby.RequisitionAdvancedSearch.Range;
import megamek.client.lobby.RequisitionAdvancedSearch.Term;
import megamek.client.lobby.RequisitionAdvancedSearch.Value;
import megamek.client.ui.dialogs.advancedsearch.ASSearchFilter;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.AsState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.BfsState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.MiscState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.QuirksState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.TriStateItemListState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.TwState;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState.UnitTypeState;
import megamek.client.ui.dialogs.advancedsearch.AdvancedSearchEquipmentClass;
import megamek.client.ui.dialogs.advancedsearch.AdvancedSearchFiles;
import megamek.client.ui.dialogs.advancedsearch.FilterToken;
import megamek.common.alphaStrike.ASDamage;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.battlefieldSupport.BFSArtilleryType;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.battlefieldSupport.BFSSpecialType;
import megamek.common.equipment.EquipmentType;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.UnitRole;
import megamek.logging.MMLogger;

/**
 * The lobby's saved advanced searches: named {@link RequisitionAdvancedSearch} criteria kept in the unit selector
 * dialog's save files ({@link AdvancedSearchFiles}), so the dialog, the lobby, MegaMekLab and MekHQ share one set of
 * searches and one recently-used list. The lobby saves what the dialog can read and adds what only it has (the run
 * span, more than two Alpha Strike specials) as fields the dialog ignores. A dialog search may hold criteria the lobby
 * has no place for (transport bays, turrets, arms and hands, source, rules references, MUL id, canon); loading it
 * leaves them out and says so ({@link Loaded#partial()}).
 */
public final class SavedAdvancedSearches {
    private static final MMLogger LOGGER = MMLogger.create(SavedAdvancedSearches.class);
    private static final String EXTENSION = ".json";

    /** A saved search: its name and its file. */
    public record Saved(String name, Path file) { }

    /** A loaded search: its name, its criteria, and whether some of its criteria were left out. */
    public record Loaded(String name, RequisitionAdvancedSearch search, boolean partial) { }

    private final Path folder;
    private final Path recents;

    /** The searches in the dialog's folder and recently-used list. */
    public SavedAdvancedSearches() {
        this(Path.of(AdvancedSearchFiles.SEARCH_FOLDER), Path.of(AdvancedSearchFiles.RECENT_SEARCH_STORE));
    }

    /** The searches saved in {@code folder}, with the recently used ones listed in {@code recents}. */
    public SavedAdvancedSearches(Path folder, Path recents) {
        this.folder = folder;
        this.recents = recents;
    }

    /** The folder the searches are saved to. */
    public Path folder() {
        return folder;
    }

    /**
     * The saved searches: the recently used ones first, most recent first, then the rest of the folder's by name.
     * Files that are gone or are not searches are left out.
     */
    public List<Saved> list() {
        List<Saved> saved = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        try {
            for (Path file : AdvancedSearchFiles.recentFiles(recents)) {
                add(saved, seen, file);
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not read the recent advanced searches: {}", failure.getMessage());
        }
        List<Saved> rest = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + EXTENSION)) {
                for (Path file : files) {
                    add(rest, seen, file);
                }
            } catch (IOException | DirectoryIteratorException failure) {
                LOGGER.warn("Could not list the saved advanced searches: {}", failure.getMessage());
            }
        }
        rest.sort(Comparator.comparing(Saved::name, String.CASE_INSENSITIVE_ORDER));
        saved.addAll(rest);
        return saved;
    }

    private static void add(List<Saved> list, Set<Path> seen, Path file) {
        if (seen.add(file.toAbsolutePath().normalize()) && Files.isRegularFile(file)
              && AdvancedSearchFiles.isAdvancedSearchFile(file.toFile())) {
            list.add(new Saved(nameOf(file), file));
        }
    }

    private static String nameOf(Path file) {
        String name = AdvancedSearchFiles.readName(file);
        if ((name == null) || name.isBlank()) {
            name = file.getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
                name = name.substring(0, name.length() - EXTENSION.length());
            }
        }
        return name;
    }

    /** The file a search named {@code name} is saved to, or {@code null} when the name makes no file name. */
    public @Nullable Path fileFor(String name) {
        if ((name == null) || name.isBlank()) {
            return null;
        }
        String fileName = AdvancedSearchFiles.sanitizeFileName(name.strip());
        return (fileName == null) ? null : folder.resolve(fileName + EXTENSION);
    }

    /** Whether saving under {@code name} replaces a saved search. */
    public boolean exists(String name) {
        Path file = fileFor(name);
        return (file != null) && Files.exists(file);
    }

    /** Whether {@code saved} is in the searches folder, so it may be renamed or deleted here. */
    public boolean editable(Saved saved) {
        Path parent = saved.file().toAbsolutePath().normalize().getParent();
        return (parent != null) && parent.equals(folder.toAbsolutePath().normalize());
    }

    /** Saves {@code search} under {@code name}, replacing a search saved under it. */
    public Saved save(String name, RequisitionAdvancedSearch search) throws IOException {
        return save(name, search, null);
    }

    /**
     * Saves {@code search} under {@code name}, replacing a search saved under it. When that is {@code loadedFrom}, the
     * file the search was loaded from, the criteria this search has no controls for stay in it (see
     * {@link Loaded#partial()}), so replacing a unit selector's search keeps what only the unit selector shows.
     */
    public Saved save(String name, RequisitionAdvancedSearch search, @Nullable Path loadedFrom) throws IOException {
        Path file = requireFile(name);
        Files.createDirectories(folder);
        AdvSearchState state = toState(name.strip(), search);
        if ((loadedFrom != null) && Files.exists(file) && Files.exists(loadedFrom)
              && Files.isSameFile(file, loadedFrom)) {
            keepHidden(AdvancedSearchFiles.load(file.toFile()), state);
        }
        AdvancedSearchFiles.save(file.toFile(), state);
        touch(file);
        return new Saved(name.strip(), file);
    }

    /** Copies to {@code to} the criteria of {@code from} that this search has no controls for. */
    private static void keepHidden(AdvSearchState from, AdvSearchState to) {
        to.twState.transportsState = from.twState.transportsState;
        MiscState was = from.twState.miscState;
        MiscState kept = to.twState.miscState;
        kept.canon = was.canon;
        kept.startTankTurrets = was.startTankTurrets;
        kept.endTankTurrets = was.endTankTurrets;
        kept.startLowerArms = was.startLowerArms;
        kept.endLowerArms = was.endLowerArms;
        kept.startHands = was.startHands;
        kept.endHands = was.endHands;
        kept.source = was.source;
        kept.rulesRefs = was.rulesRefs;
        kept.mulId = was.mulId;
    }

    /**
     * Loads the search in {@code file} and makes it the most recent.
     *
     * @throws IllegalArgumentException when the file is not a saved advanced search
     */
    public Loaded load(Path file) throws IOException {
        Loaded loaded = fromState(AdvancedSearchFiles.load(file.toFile()));
        touch(file);
        return ((loaded.name() == null) || loaded.name().isBlank())
              ? new Loaded(nameOf(file), loaded.search(), loaded.partial()) : loaded;
    }

    /**
     * Renames {@code saved} to {@code name}, moving it to the name's file and keeping all it holds.
     *
     * @throws FileAlreadyExistsException when another search is saved under the name
     */
    public Saved rename(Saved saved, String name) throws IOException {
        Path to = requireFile(name);
        boolean same = Files.exists(to) && Files.isSameFile(to, saved.file());
        if (Files.exists(to) && !same) {
            throw new FileAlreadyExistsException(to.toString());
        }
        Path target = same ? saved.file() : to;
        AdvancedSearchFiles.copyRenamed(saved.file(), target, name.strip());
        if (!same) {
            Files.deleteIfExists(saved.file());
        }
        touch(target);
        return new Saved(name.strip(), target);
    }

    /** Deletes the file of {@code saved}. */
    public void delete(Saved saved) throws IOException {
        Files.deleteIfExists(saved.file());
    }

    private Path requireFile(String name) {
        Path file = fileFor(name);
        if (file == null) {
            throw new IllegalArgumentException("No file name can be made from \"" + name + "\"");
        }
        return file;
    }

    private void touch(Path file) {
        try {
            AdvancedSearchFiles.touch(recents, file);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not update the recent advanced searches: {}", failure.getMessage());
        }
    }

    // ---- Converting ----

    /** Writes {@code from} and {@code to} of a range and marks it in use. */
    @FunctionalInterface
    private interface RangeWriter<S> {
        void write(S state, String from, String to);
    }

    /** A text range of a saved state: whether it is in use, its ends, and how to write it. */
    private record TextRange<S>(Predicate<S> used, Function<S, String> from, Function<S, String> to,
          RangeWriter<S> writer) { }

    /** A saved state's slot for one value. */
    private record Slot<S, T>(Function<S, T> get, BiConsumer<S, T> set) { }

    private static <S, T> Slot<S, T> slot(Function<S, T> get, BiConsumer<S, T> set) {
        return new Slot<>(get, set);
    }

    private static final Map<Field, TextRange<MiscState>> TW_RANGES = Map.of(
          Field.WALK, new TextRange<MiscState>(s -> true, s -> s.startWalk, s -> s.endWalk,
                (s, from, to) -> {
                    s.startWalk = from;
                    s.endWalk = to;
                }),
          Field.RUN, new TextRange<MiscState>(s -> true, s -> s.startRun, s -> s.endRun,
                (s, from, to) -> {
                    s.startRun = from;
                    s.endRun = to;
                }),
          Field.JUMP, new TextRange<MiscState>(s -> true, s -> s.startJump, s -> s.endJump,
                (s, from, to) -> {
                    s.startJump = from;
                    s.endJump = to;
                }),
          Field.TONS, new TextRange<MiscState>(s -> true, s -> s.startTons, s -> s.endTons,
                (s, from, to) -> {
                    s.startTons = from;
                    s.endTons = to;
                }),
          Field.YEAR, new TextRange<MiscState>(s -> true, s -> s.startYear, s -> s.endYear,
                (s, from, to) -> {
                    s.startYear = from;
                    s.endYear = to;
                }),
          Field.BV, new TextRange<MiscState>(s -> true, s -> s.startBV, s -> s.endBV,
                (s, from, to) -> {
                    s.startBV = from;
                    s.endBV = to;
                }));

    /** The dialog's unit type switches by the lobby's unit type value. */
    private static final Map<String, Slot<UnitTypeState, State>> UNIT_TYPES = Map.ofEntries(
          Map.entry("Mek", slot(s -> s.mek, (s, v) -> s.mek = v)),
          Map.entry("BipedMek", slot(s -> s.bipedMek, (s, v) -> s.bipedMek = v)),
          Map.entry("Quad", slot(s -> s.quad, (s, v) -> s.quad = v)),
          Map.entry("Tripod", slot(s -> s.tripod, (s, v) -> s.tripod = v)),
          Map.entry("QuadVee", slot(s -> s.quadVee, (s, v) -> s.quadVee = v)),
          Map.entry("LAM", slot(s -> s.lam, (s, v) -> s.lam = v)),
          Map.entry("ProtoMek", slot(s -> s.protoMek, (s, v) -> s.protoMek = v)),
          Map.entry("Tank", slot(s -> s.tank, (s, v) -> s.tank = v)),
          Map.entry("SupportTank", slot(s -> s.supportTank, (s, v) -> s.supportTank = v)),
          Map.entry("LargeSupportTank", slot(s -> s.largeSupportTank, (s, v) -> s.largeSupportTank = v)),
          Map.entry("SuperHeavyTank", slot(s -> s.superHeavyTank, (s, v) -> s.superHeavyTank = v)),
          Map.entry("VTOL", slot(s -> s.vtol, (s, v) -> s.vtol = v)),
          Map.entry("SupportVTOL", slot(s -> s.supportVTOL, (s, v) -> s.supportVTOL = v)),
          Map.entry("GunEmplacement", slot(s -> s.gunEmplacement, (s, v) -> s.gunEmplacement = v)),
          Map.entry("Infantry", slot(s -> s.infantry, (s, v) -> s.infantry = v)),
          Map.entry("BattleArmor", slot(s -> s.battleArmor, (s, v) -> s.battleArmor = v)),
          Map.entry("Aero", slot(s -> s.aero, (s, v) -> s.aero = v)),
          Map.entry("AerospaceFighter", slot(s -> s.aerospaceFighter, (s, v) -> s.aerospaceFighter = v)),
          Map.entry("ConvFighter", slot(s -> s.convFighter, (s, v) -> s.convFighter = v)),
          Map.entry("FixedWingSupport", slot(s -> s.fixedWingSupport, (s, v) -> s.fixedWingSupport = v)),
          Map.entry("SmallCraft", slot(s -> s.smallCraft, (s, v) -> s.smallCraft = v)),
          Map.entry("Dropship", slot(s -> s.dropship, (s, v) -> s.dropship = v)),
          Map.entry("Jumpship", slot(s -> s.jumpship, (s, v) -> s.jumpship = v)),
          Map.entry("Warship", slot(s -> s.warship, (s, v) -> s.warship = v)),
          Map.entry("SpaceStation", slot(s -> s.spaceStation, (s, v) -> s.spaceStation = v)));

    /** The flags the dialog keeps as unit type switches: on is "must be", half-on "must not be". */
    private static final Map<Field, Slot<UnitTypeState, State>> UNIT_FLAGS = Map.ofEntries(
          Map.entry(Field.OMNI, slot(s -> s.omni, (s, v) -> s.omni = v)),
          Map.entry(Field.MILITARY, slot(s -> s.military, (s, v) -> s.military = v)),
          Map.entry(Field.INDUSTRIAL, slot(s -> s.industrial, (s, v) -> s.industrial = v)),
          Map.entry(Field.MOUNTED_INFANTRY, slot(s -> s.mountedInfantry, (s, v) -> s.mountedInfantry = v)),
          Map.entry(Field.WATER_ONLY, slot(s -> s.waterOnly, (s, v) -> s.waterOnly = v)),
          Map.entry(Field.SUPPORT_VEHICLE, slot(s -> s.supportVehicle, (s, v) -> s.supportVehicle = v)),
          Map.entry(Field.FRANKEN_MEK, slot(s -> s.frankenMek, (s, v) -> s.frankenMek = v)),
          Map.entry(Field.DOOMED_ON_GROUND, slot(s -> s.doomedOnGround, (s, v) -> s.doomedOnGround = v)),
          Map.entry(Field.DOOMED_IN_ATMOSPHERE,
                slot(s -> s.doomedInAtmosphere, (s, v) -> s.doomedInAtmosphere = v)),
          Map.entry(Field.DOOMED_IN_SPACE, slot(s -> s.doomedInSpace, (s, v) -> s.doomedInSpace = v)),
          Map.entry(Field.DOOMED_IN_EXTREME_TEMP,
                slot(s -> s.doomedInExtremeTemp, (s, v) -> s.doomedInExtremeTemp = v)),
          Map.entry(Field.DOOMED_IN_VACUUM, slot(s -> s.doomedInVacuum, (s, v) -> s.doomedInVacuum = v)));

    /** The flags the dialog keeps as choices: 0 any, 1 "must be", 2 "must not be". */
    private static final Map<Field, Slot<MiscState, Integer>> MISC_FLAGS = Map.of(
          Field.OFFICIAL, slot(s -> s.official, (s, v) -> s.official = v),
          Field.PATCHWORK, slot(s -> s.patchwork, (s, v) -> s.patchwork = v),
          Field.INVALID, slot(s -> s.invalid, (s, v) -> s.invalid = v),
          Field.FAILED_EQUIPMENT, slot(s -> s.failedToLoadEquipment, (s, v) -> s.failedToLoadEquipment = v),
          Field.CLAN_ENGINE, slot(s -> s.clanEngine, (s, v) -> s.clanEngine = v));

    /** The dialog's lists of names, each name on or half-on (must not). */
    private static final Map<Field, Slot<MiscState, TriStateItemListState>> TW_LISTS = Map.of(
          Field.TECH_BASE, slot(s -> s.techBase, (s, v) -> s.techBase = v),
          Field.TECH_LEVEL, slot(s -> s.techLevel, (s, v) -> s.techLevel = v),
          Field.MOVE_MODE, slot(s -> s.moveMode, (s, v) -> s.moveMode = v),
          Field.ARMOR_TYPE, slot(s -> s.armorType, (s, v) -> s.armorType = v),
          Field.INTERNALS, slot(s -> s.internalsType, (s, v) -> s.internalsType = v),
          Field.ENGINE, slot(s -> s.engineType, (s, v) -> s.engineType = v),
          Field.COCKPIT, slot(s -> s.cockpitType, (s, v) -> s.cockpitType = v),
          Field.GYRO, slot(s -> s.gyroType, (s, v) -> s.gyroType = v));

    private static final Map<Field, TextRange<AsState>> AS_RANGES = Map.of(
          Field.AS_ARMOR, new TextRange<AsState>(s -> s.armorUse, s -> s.armorFromText, s -> s.armorToText,
                (s, from, to) -> {
                    s.armorUse = true;
                    s.armorFromText = from;
                    s.armorToText = to;
                }),
          Field.AS_STRUCTURE, new TextRange<AsState>(s -> s.structureUse, s -> s.structureFromText,
                s -> s.structureToText, (s, from, to) -> {
                    s.structureUse = true;
                    s.structureFromText = from;
                    s.structureToText = to;
                }),
          Field.AS_THRESHOLD, new TextRange<AsState>(s -> s.thresholdUse, s -> s.thresholdFromText,
                s -> s.thresholdToText, (s, from, to) -> {
                    s.thresholdUse = true;
                    s.thresholdFromText = from;
                    s.thresholdToText = to;
                }),
          Field.AS_PV, new TextRange<AsState>(s -> s.pvUse, s -> s.pvFromText, s -> s.pvToText,
                (s, from, to) -> {
                    s.pvUse = true;
                    s.pvFromText = from;
                    s.pvToText = to;
                }));

    /** An Alpha Strike damage span of the dialog: whether it is in use and its ends. */
    private record DamageSlot(Predicate<AsState> used, Function<AsState, ASDamage> from,
          Function<AsState, ASDamage> to, DamageWriter writer) { }

    @FunctionalInterface
    private interface DamageWriter {
        void write(AsState state, ASDamage from, ASDamage to);
    }

    private static final Map<Field, DamageSlot> AS_DAMAGE = Map.of(
          Field.AS_DAMAGE_S, new DamageSlot(s -> s.damageSUse, s -> s.damageSFromValue, s -> s.damageSToValue,
                (s, from, to) -> {
                    s.damageSUse = true;
                    s.damageSFromValue = from;
                    s.damageSToValue = to;
                }),
          Field.AS_DAMAGE_M, new DamageSlot(s -> s.damageMUse, s -> s.damageMFromValue, s -> s.damageMToValue,
                (s, from, to) -> {
                    s.damageMUse = true;
                    s.damageMFromValue = from;
                    s.damageMToValue = to;
                }),
          Field.AS_DAMAGE_L, new DamageSlot(s -> s.damageLUse, s -> s.damageLFromValue, s -> s.damageLToValue,
                (s, from, to) -> {
                    s.damageLUse = true;
                    s.damageLFromValue = from;
                    s.damageLToValue = to;
                }),
          Field.AS_DAMAGE_E, new DamageSlot(s -> s.damageEUse, s -> s.damageEFromValue, s -> s.damageEToValue,
                (s, from, to) -> {
                    s.damageEUse = true;
                    s.damageEFromValue = from;
                    s.damageEToValue = to;
                }));

    private static final Map<Field, TextRange<BfsState>> BF_RANGES = Map.ofEntries(
          Map.entry(Field.BF_MP, new TextRange<BfsState>(s -> s.mpUse, s -> s.mpFromText, s -> s.mpToText,
                (s, from, to) -> {
                    s.mpUse = true;
                    s.mpFromText = from;
                    s.mpToText = to;
                })),
          Map.entry(Field.BF_TMM, new TextRange<BfsState>(s -> s.tmmUse, s -> s.tmmFromText, s -> s.tmmToText,
                (s, from, to) -> {
                    s.tmmUse = true;
                    s.tmmFromText = from;
                    s.tmmToText = to;
                })),
          Map.entry(Field.BF_SKILL, new TextRange<BfsState>(s -> s.skillUse, s -> s.skillFromText,
                s -> s.skillToText, (s, from, to) -> {
                    s.skillUse = true;
                    s.skillFromText = from;
                    s.skillToText = to;
                })),
          Map.entry(Field.BF_DAMAGE, new TextRange<BfsState>(s -> s.damageUse, s -> s.damageFromText,
                s -> s.damageToText, (s, from, to) -> {
                    s.damageUse = true;
                    s.damageFromText = from;
                    s.damageToText = to;
                })),
          Map.entry(Field.BF_DAMAGE_PER_HIT, new TextRange<BfsState>(s -> s.damagePerHitUse,
                s -> s.damagePerHitFromText, s -> s.damagePerHitToText, (s, from, to) -> {
                    s.damagePerHitUse = true;
                    s.damagePerHitFromText = from;
                    s.damagePerHitToText = to;
                })),
          Map.entry(Field.BF_DAMAGE_HITS, new TextRange<BfsState>(s -> s.damageHitsUse, s -> s.damageHitsFromText,
                s -> s.damageHitsToText, (s, from, to) -> {
                    s.damageHitsUse = true;
                    s.damageHitsFromText = from;
                    s.damageHitsToText = to;
                })),
          Map.entry(Field.BF_LONG_RANGE, new TextRange<BfsState>(s -> s.longRangeUse, s -> s.longRangeFromText,
                s -> s.longRangeToText, (s, from, to) -> {
                    s.longRangeUse = true;
                    s.longRangeFromText = from;
                    s.longRangeToText = to;
                })),
          Map.entry(Field.BF_DESTROY_CHECK, new TextRange<BfsState>(s -> s.destroyCheckUse,
                s -> s.destroyCheckFromText, s -> s.destroyCheckToText, (s, from, to) -> {
                    s.destroyCheckUse = true;
                    s.destroyCheckFromText = from;
                    s.destroyCheckToText = to;
                })),
          Map.entry(Field.BF_THRESHOLD, new TextRange<BfsState>(s -> s.thresholdUse, s -> s.thresholdFromText,
                s -> s.thresholdToText, (s, from, to) -> {
                    s.thresholdUse = true;
                    s.thresholdFromText = from;
                    s.thresholdToText = to;
                })),
          Map.entry(Field.BF_BSP, new TextRange<BfsState>(s -> s.bspUse, s -> s.bspFromText, s -> s.bspToText,
                (s, from, to) -> {
                    s.bspUse = true;
                    s.bspFromText = from;
                    s.bspToText = to;
                })),
          Map.entry(Field.BF_BV, new TextRange<BfsState>(s -> s.bvUse, s -> s.bvFromText, s -> s.bvToText,
                (s, from, to) -> {
                    s.bvUse = true;
                    s.bvFromText = from;
                    s.bvToText = to;
                })));

    /** {@code search} as the dialog saves a search named {@code name}. */
    static AdvSearchState toState(String name, RequisitionAdvancedSearch search) {
        AdvSearchState state = new AdvSearchState();
        state.name = name;
        search.values().forEach((field, value) -> {
            switch (field.section()) {
                case TOTAL_WARFARE -> saveTotalWarfare(state.twState, field, value);
                case ALPHA_STRIKE -> saveAlphaStrike(state.asState, field, value);
                case BATTLEFIELD_SUPPORT -> saveBattlefieldSupport(state.bfsState, field, value);
            }
        });
        return state;
    }

    private static String text(@Nullable Integer number) {
        return (number == null) ? "" : String.valueOf(number);
    }

    private static void saveTotalWarfare(TwState state, Field field, Value value) {
        switch (value) {
            case Range range -> {
                TextRange<MiscState> slot = TW_RANGES.get(field);
                if (slot != null) {
                    slot.writer().write(state.miscState, text(range.min()), text(range.max()));
                }
            }
            case Level level -> state.miscState.armor = level.index();
            case Flag flag -> {
                if (UNIT_FLAGS.containsKey(field)) {
                    UNIT_FLAGS.get(field).set().accept(state.unitTypeState,
                          flag.yes() ? State.SELECTED : State.INDETERMINATE);
                } else if (MISC_FLAGS.containsKey(field)) {
                    MISC_FLAGS.get(field).set().accept(state.miscState, flag.yes() ? 1 : 2);
                }
            }
            case Picks picks -> savePicks(state, field, picks);
            case Equipment equipment -> {
                try {
                    for (Term term : equipment.terms()) {
                        state.equipmentState.filterTokens.add(FilterToken.parse(term.token()));
                    }
                } catch (JsonParseException failure) {
                    LOGGER.warn("The equipment expression {} could not be saved", equipment.text());
                    state.equipmentState.filterTokens.clear();
                }
            }
            default -> { }
        }
    }

    private static void savePicks(TwState state, Field field, Picks picks) {
        switch (field) {
            case UNIT_TYPE -> {
                for (Object type : picks.in()) {
                    setUnitType(state.unitTypeState, type, State.SELECTED);
                }
                for (Object type : picks.out()) {
                    setUnitType(state.unitTypeState, type, State.INDETERMINATE);
                }
            }
            case QUIRKS -> {
                state.quirksState.chassisInclude = picks.any() ? 1 : 0;
                saveItems(state.quirksState.chassisQuirks, field, picks);
            }
            case WEAPON_QUIRKS -> {
                state.quirksState.weaponInclude = picks.any() ? 1 : 0;
                saveItems(state.quirksState.weaponQuirks, field, picks);
            }
            default -> {
                Slot<MiscState, TriStateItemListState> slot = TW_LISTS.get(field);
                if (slot != null) {
                    saveItems(slot.get().apply(state.miscState), field, picks);
                }
            }
        }
    }

    private static void setUnitType(UnitTypeState state, Object type, State value) {
        Slot<UnitTypeState, State> slot = UNIT_TYPES.get(String.valueOf(type));
        if (slot != null) {
            slot.set().accept(state, value);
        }
    }

    private static void saveItems(TriStateItemListState list, Field field, Picks picks) {
        for (Object value : picks.in()) {
            list.items.put(RequisitionAdvancedSearch.labelOf(field, value), State.SELECTED);
        }
        for (Object value : picks.out()) {
            list.items.put(RequisitionAdvancedSearch.labelOf(field, value), State.INDETERMINATE);
        }
    }

    private static void saveAlphaStrike(AsState state, Field field, Value value) {
        switch (value) {
            case Picks picks -> {
                switch (field) {
                    case AS_TYPE -> {
                        state.unitTypeUse = true;
                        state.unitTypeSelected = typed(picks, ASUnitType.class);
                    }
                    case AS_ROLE -> {
                        state.unitRoleUse = true;
                        state.unitRoleSelected = typed(picks, UnitRole.class);
                    }
                    case AS_SIZE -> {
                        state.sizeUse = true;
                        state.sizeSelected = typed(picks, Integer.class);
                    }
                    case AS_TMM -> {
                        state.tmmUse = true;
                        state.tmmSelected = typed(picks, Integer.class);
                    }
                    case AS_OV -> {
                        state.ovUse = true;
                        state.ovSelected = typed(picks, Integer.class);
                    }
                    case AS_SPECIALS -> {
                        List<BattleForceSUA> specials = typed(picks, BattleForceSUA.class);
                        state.abilitiesSelected = specials;
                        if (!specials.isEmpty()) {
                            state.ability1Use = true;
                            state.ability1Value = specials.get(0);
                        }
                        if (specials.size() > 1) {
                            state.ability2Use = true;
                            state.ability2Value = specials.get(1);
                        }
                    }
                    default -> { }
                }
            }
            case Range range when AS_DAMAGE.containsKey(field) -> {
                List<ASDamage> damage = ASSearchFilter.DAMAGE_VALUES;
                AS_DAMAGE.get(field).writer().write(state, damage.get(clamp(range.min(), 0)),
                      damage.get(clamp(range.max(), damage.size() - 1)));
            }
            case Range range when AS_RANGES.containsKey(field) ->
                  AS_RANGES.get(field).writer().write(state, text(range.min()), text(range.max()));
            case Movement movement -> {
                state.mvUse = true;
                state.mvModeText = movement.mode();
                state.mvFromText = text(movement.range().min());
                state.mvToText = text(movement.range().max());
            }
            default -> { }
        }
    }

    private static int clamp(@Nullable Integer index, int fallback) {
        int last = ASSearchFilter.DAMAGE_VALUES.size() - 1;
        return (index == null) ? fallback : Math.max(0, Math.min(last, index));
    }

    private static void saveBattlefieldSupport(BfsState state, Field field, Value value) {
        switch (value) {
            case Picks picks -> {
                switch (field) {
                    case BF_TYPE -> {
                        state.assetTypeUse = true;
                        state.assetTypeSelected = typed(picks, BFSAssetType.class);
                    }
                    case BF_MOVE_MODE -> {
                        state.moveModeUse = true;
                        state.moveModeSelected = typed(picks, EntityMovementMode.class);
                    }
                    case BF_SPECIALS -> {
                        state.specialsUse = true;
                        state.specialsSelected = typed(picks, BFSSpecialType.class);
                    }
                    case BF_ARTILLERY -> {
                        state.artilleryTypeUse = true;
                        state.artilleryTypeSelected = typed(picks, BFSArtilleryType.class);
                    }
                    default -> { }
                }
            }
            case Minimums minimums -> minimums.values().forEach((type, minimum) -> {
                state.numericSpecialSelected.add((BFSSpecialType) type);
                state.numericSpecialMinTexts.put((BFSSpecialType) type, String.valueOf(minimum));
            });
            case Range range when BF_RANGES.containsKey(field) ->
                  BF_RANGES.get(field).writer().write(state, text(range.min()), text(range.max()));
            default -> { }
        }
    }

    private static <T> List<T> typed(Picks picks, Class<T> type) {
        List<T> list = new ArrayList<>();
        for (Object value : picks.in()) {
            list.add(type.cast(value));
        }
        return list;
    }

    /** The criteria of a search the dialog saved, and whether some had no place here and were left out. */
    static Loaded fromState(AdvSearchState state) {
        Reader reader = new Reader();
        reader.totalWarfare(state.twState);
        reader.alphaStrike(state.asState);
        reader.battlefieldSupport(state.bfsState);
        return new Loaded(state.name, reader.search, reader.partial);
    }

    /** Builds the criteria of a saved state, noting what it leaves out. */
    private static final class Reader {
        private RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE;
        private boolean partial;

        private void put(Field field, Value value) {
            search = search.with(field, value);
        }

        private static Range range(String from, String to) {
            return new Range(RequisitionAdvancedSearch.number(from), RequisitionAdvancedSearch.number(to));
        }

        private void totalWarfare(TwState state) {
            MiscState misc = state.miscState;
            TW_RANGES.forEach((field, slot) -> put(field, range(slot.from().apply(misc), slot.to().apply(misc))));
            if (misc.armor > 0) {
                if (misc.armor < RequisitionAdvancedSearch.choices(Field.ARMOR).size()) {
                    put(Field.ARMOR, new Level(misc.armor));
                } else {
                    partial = true;
                }
            }
            UNIT_FLAGS.forEach((field, slot) -> {
                State flag = slot.get().apply(state.unitTypeState);
                if (flag != State.UNSELECTED) {
                    put(field, new Flag(flag == State.SELECTED));
                }
            });
            MISC_FLAGS.forEach((field, slot) -> {
                int flag = slot.get().apply(misc);
                if ((flag == 1) || (flag == 2)) {
                    put(field, new Flag(flag == 1));
                }
            });
            unitTypes(state.unitTypeState);
            TW_LISTS.forEach((field, slot) -> put(field, items(field, slot.get().apply(misc), false)));
            QuirksState quirks = state.quirksState;
            quirks(Field.QUIRKS, quirks.chassisQuirks, quirks.chassisInclude, quirks.chassisExclude);
            quirks(Field.WEAPON_QUIRKS, quirks.weaponQuirks, quirks.weaponInclude, quirks.weaponExclude);
            equipment(state.equipmentState.filterTokens);

            partial |= !AdvancedSearchFiles.isDefault(state.transportsState)
                  || (misc.canon != 0)
                  || !misc.startTankTurrets.isBlank() || !misc.endTankTurrets.isBlank()
                  || !misc.startLowerArms.isBlank() || !misc.endLowerArms.isBlank()
                  || !misc.startHands.isBlank() || !misc.endHands.isBlank()
                  || !misc.source.isBlank() || !misc.rulesRefs.isEmpty() || !misc.mulId.isBlank();
        }

        private void unitTypes(UnitTypeState state) {
            List<Object> in = new ArrayList<>();
            List<Object> out = new ArrayList<>();
            for (Choice choice : RequisitionAdvancedSearch.choices(Field.UNIT_TYPE)) {
                Slot<UnitTypeState, State> slot = UNIT_TYPES.get(String.valueOf(choice.value()));
                State type = (slot == null) ? State.UNSELECTED : slot.get().apply(state);
                if (type == State.SELECTED) {
                    in.add(choice.value());
                } else if (type == State.INDETERMINATE) {
                    out.add(choice.value());
                }
            }
            put(Field.UNIT_TYPE, new Picks(in, out, false));
        }

        /** The picks of a list of names, in the order of the field's choices. */
        private Picks items(Field field, TriStateItemListState list, boolean any) {
            List<Object> in = new ArrayList<>();
            List<Object> out = new ArrayList<>();
            Set<String> found = new HashSet<>();
            for (Choice choice : RequisitionAdvancedSearch.choices(field)) {
                State item = list.items.get(choice.label());
                if (item == State.SELECTED) {
                    in.add(choice.value());
                } else if (item == State.INDETERMINATE) {
                    out.add(choice.value());
                } else {
                    continue;
                }
                found.add(choice.label());
            }
            for (Map.Entry<String, State> item : list.items.entrySet()) {
                if ((item.getValue() != State.UNSELECTED) && !found.contains(item.getKey())) {
                    partial = true;
                }
            }
            return new Picks(in, out, any);
        }

        private void quirks(Field field, TriStateItemListState list, int include, int exclude) {
            Picks picks = items(field, list, include == 1);
            // The lobby leaves out a unit with any of the "must not" quirks; the dialog may ask for all of them
            if ((exclude != 1) && (picks.out().size() > 1)) {
                partial = true;
            }
            put(field, picks);
        }

        private void equipment(List<FilterToken> tokens) {
            List<Term> terms = new ArrayList<>();
            for (FilterToken token : tokens) {
                Term term = term(token);
                if (term == null) {
                    partial = true;
                    return;
                }
                terms.add(term);
            }
            put(Field.EQUIPMENT, new Equipment(terms));
        }

        private static @Nullable Term term(FilterToken token) {
            String text = token.toJson();
            switch (text) {
                case "(" -> {
                    return Term.OPEN;
                }
                case ")" -> {
                    return Term.CLOSE;
                }
                case "and" -> {
                    return Term.AND;
                }
                case "or" -> {
                    return Term.OR;
                }
                default -> { }
            }
            int operator = text.lastIndexOf(FilterToken.TOKEN_SEPARATOR);
            int count = (operator > 0) ? text.lastIndexOf(FilterToken.TOKEN_SEPARATOR, operator - 1) : -1;
            if (count <= 0) {
                return null;
            }
            String key = text.substring(0, count);
            try {
                int quantity = Integer.parseInt(text.substring(count + FilterToken.TOKEN_SEPARATOR.length(),
                      operator));
                boolean atLeast = text.substring(operator + FilterToken.TOKEN_SEPARATOR.length())
                      .equals(FilterToken.TOKEN_ATLEAST);
                return Term.item(key, equipmentLabel(key), quantity, atLeast);
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }

        private static String equipmentLabel(String key) {
            for (EquipmentChoice choice : RequisitionAdvancedSearch.equipmentChoices()) {
                if (choice.key().equals(key)) {
                    return choice.label();
                }
            }
            try {
                return AdvancedSearchEquipmentClass.valueOf(key).toString();
            } catch (IllegalArgumentException notAClass) {
                EquipmentType type = EquipmentType.get(key);
                return (type == null) ? key : type.getName();
            }
        }

        private void alphaStrike(AsState state) {
            if (state.unitTypeUse) {
                put(Field.AS_TYPE, picks(state.unitTypeSelected));
            }
            if (state.unitRoleUse) {
                put(Field.AS_ROLE, picks(state.unitRoleSelected));
            }
            if (state.sizeUse) {
                put(Field.AS_SIZE, picks(state.sizeSelected));
            }
            if (state.tmmUse) {
                put(Field.AS_TMM, picks(state.tmmSelected));
            }
            if (state.ovUse) {
                put(Field.AS_OV, picks(state.ovSelected));
            }
            AS_RANGES.forEach((field, slot) -> {
                if (slot.used().test(state)) {
                    put(field, range(slot.from().apply(state), slot.to().apply(state)));
                }
            });
            AS_DAMAGE.forEach((field, slot) -> {
                if (slot.used().test(state)) {
                    int from = damageIndex(slot.from().apply(state));
                    int to = damageIndex(slot.to().apply(state));
                    if ((from < 0) || (to < 0)) {
                        partial = true;
                    } else {
                        put(field, new Range(from, to));
                    }
                }
            });
            if (state.mvUse) {
                if (ASSearchFilter.MOVE_MODES.contains(state.mvModeText)) {
                    put(Field.AS_MOVE, new Movement(state.mvModeText, range(state.mvFromText, state.mvToText)));
                } else {
                    partial = true;
                }
            }
            List<BattleForceSUA> specials = new ArrayList<>();
            if (!state.abilitiesSelected.isEmpty()) {
                specials.addAll(state.abilitiesSelected);
            } else {
                if (state.ability1Use) {
                    specials.add(state.ability1Value);
                }
                if (state.ability2Use && !specials.contains(state.ability2Value)) {
                    specials.add(state.ability2Value);
                }
            }
            put(Field.AS_SPECIALS, picks(specials));
        }

        private static int damageIndex(@Nullable ASDamage damage) {
            List<ASDamage> values = ASSearchFilter.DAMAGE_VALUES;
            if (damage != null) {
                for (int i = 0; i < values.size(); i++) {
                    if ((values.get(i).damage == damage.damage) && (values.get(i).minimal == damage.minimal)) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static Picks picks(List<?> values) {
            List<Object> in = new ArrayList<>();
            for (Object value : values) {
                if ((value != null) && !in.contains(value)) {
                    in.add(value);
                }
            }
            return new Picks(in, List.of(), false);
        }

        private void battlefieldSupport(BfsState state) {
            if (state.assetTypeUse) {
                put(Field.BF_TYPE, picks(state.assetTypeSelected));
            }
            if (state.moveModeUse) {
                put(Field.BF_MOVE_MODE, picks(state.moveModeSelected));
            }
            if (state.specialsUse) {
                put(Field.BF_SPECIALS, picks(state.specialsSelected));
            }
            if (state.artilleryTypeUse) {
                put(Field.BF_ARTILLERY, picks(state.artilleryTypeSelected));
            }
            BF_RANGES.forEach((field, slot) -> {
                if (slot.used().test(state)) {
                    put(field, range(slot.from().apply(state), slot.to().apply(state)));
                }
            });
            Map<Object, Integer> minimums = new LinkedHashMap<>();
            for (BFSSpecialType type : state.numericSpecialSelected) {
                // The dialog reads a blank minimum as "has the special at all"
                Integer minimum = RequisitionAdvancedSearch.number(state.numericSpecialMinTexts.get(type));
                minimums.put(type, (minimum == null) ? 0 : minimum);
            }
            put(Field.BF_NUMERIC_SPECIALS, new Minimums(minimums));
        }
    }
}
