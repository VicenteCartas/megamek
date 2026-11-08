/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.AbstractUnitRecord;
import megamek.client.ratgenerator.C3NetworkConfigurator;
import megamek.client.ratgenerator.CarrierLoadingConfigurator;
import megamek.client.ratgenerator.ClanEnhancedImagingAugmentor;
import megamek.client.ratgenerator.CrewDescriptor;
import megamek.client.ratgenerator.ExistingLift;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.ForceComposition;
import megamek.client.ratgenerator.ForceDescriptor;
import megamek.client.ratgenerator.ForceEdits;
import megamek.client.ratgenerator.ForceNode;
import megamek.client.ratgenerator.FormationMixPreview;
import megamek.client.ratgenerator.FormationType;
import megamek.client.ratgenerator.GenerationContext;
import megamek.client.ratgenerator.InfantryClass;
import megamek.client.ratgenerator.ManeiDominiCrewAugmentor;
import megamek.client.ratgenerator.MissionRole;
import megamek.client.ratgenerator.ModelRecord;
import megamek.client.ratgenerator.RATGenerator;
import megamek.client.ratgenerator.Ruleset;
import megamek.client.ratgenerator.ShipReroller;
import megamek.client.ratgenerator.TOCNode;
import megamek.client.ratgenerator.TransportBranchMerger;
import megamek.client.ratgenerator.UnitTable;
import megamek.client.ratgenerator.ValueNode;
import megamek.client.ui.Messages;
import megamek.codeUtilities.MathUtility;
import megamek.common.Player;
import megamek.common.enums.SkillLevel;
import megamek.common.game.Game;
import megamek.common.loaders.MekSummary;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.totalwarfare.TWRulesManager;
import megamek.common.units.Entity;
import megamek.common.units.EntityListFile;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitType;
import megamek.common.universe.Ranks;
import megamek.logging.MMLogger;

/**
 * The Force Generator, free of any toolkit: the description of the force to roll - year, faction and command, unit
 * type, formation, rating, options, experience, weight, mission roles and transport - kept consistent as the faction
 * ruleset offers its choices, and the command the rolls build. Each roll folds into one command by echelon, which the
 * player edits - re-rolling a formation as another type, adding one, swapping a ship, leaving branches out - before
 * the units still included go to the game.
 *
 * <p>Generating and editing draw units and must run on the Swing thread with the other unit work; reading the
 * choices and the command is safe from the view between those.</p>
 */
public final class ForceGeneratorForm {
    private static final MMLogger LOGGER = MMLogger.create(ForceGeneratorForm.class);

    /** How many structure-only builds the formation mix averages; one build is not representative. */
    static final int MIX_SAMPLES = 15;
    /** The size quoted for a formation's requirements, being a standard lance. */
    private static final int NOMINAL_FORMATION_SIZE = 4;
    private static final List<Integer> WEIGHT_CLASSES = List.of(EntityWeightClass.WEIGHT_LIGHT,
          EntityWeightClass.WEIGHT_MEDIUM, EntityWeightClass.WEIGHT_HEAVY, EntityWeightClass.WEIGHT_ASSAULT);
    private static final Map<Integer, String> DEFAULT_RANK_TITLES = Map.ofEntries(Map.entry(12, "Sergeant"),
          Map.entry(32, "Lieutenant JG"), Map.entry(33, "Lieutenant"), Map.entry(34, "Captain"),
          Map.entry(35, "Major"), Map.entry(37, "Lt. Colonel"), Map.entry(38, "Colonel"),
          Map.entry(39, "Lt. General"), Map.entry(42, "Maj. General"), Map.entry(43, "General"),
          Map.entry(46, "Loremaster"), Map.entry(47, "saKhan"), Map.entry(48, "Khan"));

    /** A family of formations in the mix palette: its heading, or null when it is its only member, and members. */
    public record Family(@Nullable String heading, List<String> formations) {
        public Family {
            formations = List.copyOf(formations);
        }
    }

    /** The palette: the ground families, then the aerospace ones. */
    public record Palette(List<Family> ground, List<Family> aerospace) {
        public static final Palette EMPTY = new Palette(List.of(), List.of());

        public Palette {
            ground = List.copyOf(ground);
            aerospace = List.copyOf(aerospace);
        }

        public boolean empty() {
            return ground.isEmpty() && aerospace.isEmpty();
        }
    }

    /** Why an edit cannot be made to a node, or null when it can. */
    public enum Blocker {
        NO_SELECTION("Lobby.randomArmy.force.noBrush"),
        NOT_A_FORMATION("Lobby.randomArmy.force.notAFormation"),
        NO_TEMPLATE("Lobby.randomArmy.force.noTemplate"),
        UNKNOWN_FORMATION("Lobby.randomArmy.force.noBrush");

        private final String key;

        Blocker(String key) {
            this.key = key;
        }

        public String message() {
            return Messages.getString(key);
        }
    }

    private final ForceDescriptor description = new ForceDescriptor();
    private int year;
    private List<FactionRecord> factions = List.of();
    private @Nullable FactionRecord faction;
    private List<FactionRecord> commands = List.of();
    private @Nullable FactionRecord command;
    private final List<Integer> unitTypes = new ArrayList<>();
    private final Map<String, String> formations = new LinkedHashMap<>();
    private @Nullable String formation;
    private final Map<String, String> ratings = new LinkedHashMap<>();
    private final Map<String, String> flags = new LinkedHashMap<>();
    private @Nullable String flag;
    private @Nullable Integer experience;
    private @Nullable Integer weightClass;
    private @Nullable InfantryClass infantryClass;
    private final EnumSet<MissionRole> roles = EnumSet.noneOf(MissionRole.class);
    private boolean detachments = true;
    private int dropshipPercent;
    private int jumpshipPercent;
    private int warshipPercent;
    private int cargoPercent = 100;
    private boolean fighterComplement;

    private @Nullable ForceDescriptor top;
    private @Nullable ForceDescriptor lastRoll;
    private final Map<ForceDescriptor, String> ids = new IdentityHashMap<>();
    private final Map<String, ForceDescriptor> byId = new HashMap<>();
    private int nextId;
    private @Nullable String brush;
    private FormationMixPreview mix = FormationMixPreview.EMPTY;
    private @Nullable String mixShape;

    /** A form for {@code year}, with the Inner Sphere chosen; the generator data must be loaded. */
    public ForceGeneratorForm(int year) {
        if (!Ruleset.isInitialized()) {
            Ruleset.loadData();
        }
        this.year = clampYear(year);
        description.setYear(this.year);
        refreshFactions();
    }

    /** A form whose command is {@code top}, offering no choices: the command's work needs no generator data. */
    ForceGeneratorForm(ForceDescriptor top) {
        this.top = top;
    }

    private static int clampYear(int year) {
        RATGenerator generator = RATGenerator.getInstance();
        if (generator.getEraSet().isEmpty()) {
            return year;
        }
        return Math.max(generator.getEraSet().first(), Math.min(generator.getEraSet().last(), year));
    }

    // ---- The description

    public int year() {
        return year;
    }

    /** The earliest year the generator has eras for. */
    public static int earliestYear() {
        RATGenerator generator = RATGenerator.getInstance();
        return generator.getEraSet().isEmpty() ? 0 : generator.getEraSet().first();
    }

    /** The latest year the generator has eras for. */
    public static int latestYear() {
        RATGenerator generator = RATGenerator.getInstance();
        return generator.getEraSet().isEmpty() ? 9999 : generator.getEraSet().last();
    }

    /** Sets the year, kept within the eras the generator knows, and offers the factions active in it. */
    public void year(int year) {
        this.year = clampYear(year);
        RATGenerator.getInstance().loadYear(this.year);
        description.setYear(this.year);
        refreshFactions();
    }

    /** The factions active in the year whose rules offer any unit types, by name. */
    public List<FactionRecord> factions() {
        return factions;
    }

    public @Nullable FactionRecord faction() {
        return faction;
    }

    /** Chooses a faction by key, offering its commands and the choices that follow. */
    public void faction(String key) {
        for (FactionRecord record : factions) {
            if (record.getKey().equals(key)) {
                faction = record;
                command = null;
                description.setFaction(key);
                refreshCommands();
                return;
            }
        }
    }

    /** The faction's whole commands active in the year, by name; none chosen is the faction in general. */
    public List<FactionRecord> commands() {
        return commands;
    }

    public @Nullable FactionRecord command() {
        return command;
    }

    /** Chooses a command by key, or the faction in general for null. */
    public void command(@Nullable String key) {
        command = null;
        for (FactionRecord record : commands) {
            if (record.getKey().equals(key)) {
                command = record;
            }
        }
        description.setFaction(factionKey());
        refreshUnitTypes();
    }

    /** The key the force is rolled for: the command when one is chosen, else the faction. */
    public String factionKey() {
        if (command != null) {
            return command.getKey();
        }
        return (faction == null) ? FactionRecord.IS_GENERAL_KEY : faction.getKey();
    }

    /** A faction's name in the year. */
    public String name(FactionRecord record) {
        return record.getName(year);
    }

    /** The unit types the rules offer and the tables stock, in menu order; null is combined arms. */
    public List<Integer> unitTypes() {
        return Collections.unmodifiableList(unitTypes);
    }

    public @Nullable Integer unitType() {
        return description.getUnitType();
    }

    public void unitType(@Nullable Integer unitType) {
        description.setUnitType(unitType);
        refreshUnitTypeOptions();
        refreshFormations();
    }

    public static String unitTypeName(@Nullable Integer unitType) {
        return (unitType == null) ? Messages.getString("ForceGeneratorDialog.combined")
              : UnitType.getTypeDisplayableName(unitType);
    }

    /** The formation sizes offered, as ruleset codes such as "4" or "5+". */
    public List<String> formations() {
        return List.copyOf(formations.keySet());
    }

    public @Nullable String formation() {
        return formation;
    }

    public void formation(String code) {
        if (formations.containsKey(code)) {
            applyFormation(code);
            refreshRatings();
        }
    }

    public String formationName(String code) {
        return formations.getOrDefault(code, code);
    }

    /** The equipment ratings offered, as codes. */
    public List<String> ratings() {
        return List.copyOf(ratings.keySet());
    }

    public @Nullable String rating() {
        return description.getRating();
    }

    public void rating(String code) {
        description.setRating(code);
        refreshFlags();
    }

    public String ratingName(String code) {
        return ratings.getOrDefault(code, code);
    }

    /** The faction options offered, as codes; none chosen is the plain force. */
    public List<String> flags() {
        return List.copyOf(flags.keySet());
    }

    public @Nullable String flag() {
        return flag;
    }

    public void flag(@Nullable String code) {
        flag = (code != null) && flags.containsKey(code) ? code : null;
        description.getFlags().clear();
        if (flag != null) {
            description.getFlags().add(flag);
        }
    }

    public String flagName(String code) {
        return flags.getOrDefault(code, code);
    }

    /** The experience aimed at, 0 Green to 3 Elite, or null for random. */
    public @Nullable Integer experience() {
        return experience;
    }

    public void experience(@Nullable Integer experience) {
        this.experience = experience;
        description.setExperience(experience);
        refreshFlags();
    }

    /** The experience names, Random first then Green to Elite. */
    public static List<String> experienceNames() {
        return List.of(Messages.getString("ForceGeneratorDialog.random"),
              Messages.getString("ForceGeneratorDialog.green"), Messages.getString("ForceGeneratorDialog.regular"),
              Messages.getString("ForceGeneratorDialog.veteran"), Messages.getString("ForceGeneratorDialog.elite"));
    }

    /** Light to Assault. */
    public static List<Integer> weightClasses() {
        return WEIGHT_CLASSES;
    }

    public @Nullable Integer weightClass() {
        return weightClass;
    }

    public void weightClass(@Nullable Integer weightClass) {
        this.weightClass = weightPicksUnits() ? weightClass : null;
    }

    /** Whether the weight picks units: VTOLs and conventional fighters draw from one table whatever it says. */
    public boolean weightPicksUnits() {
        Integer type = unitType();
        return (type == null) || ((type != UnitType.VTOL) && (type != UnitType.CONV_FIGHTER));
    }

    /** Whether the force is conventional infantry, which picks an infantry class in place of a weight. */
    public boolean infantry() {
        Integer type = unitType();
        return (type != null) && (type == UnitType.INFANTRY);
    }

    public @Nullable InfantryClass infantryClass() {
        return infantryClass;
    }

    public void infantryClass(@Nullable InfantryClass infantryClass) {
        this.infantryClass = infantry() ? infantryClass : null;
    }

    /** The mission roles the unit type can be filtered by. */
    public List<MissionRole> roleChoices() {
        Integer type = unitType();
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            case UnitType.MEK, UnitType.TANK -> List.of(MissionRole.ARTILLERY, MissionRole.MISSILE_ARTILLERY,
                  MissionRole.CARGO, MissionRole.ENGINEER);
            case UnitType.INFANTRY, UnitType.BATTLE_ARMOR -> List.of(MissionRole.FIELD_GUN, MissionRole.ARTILLERY,
                  MissionRole.MISSILE_ARTILLERY, MissionRole.ENGINEER, MissionRole.FIELDWORKS,
                  MissionRole.BRIDGE_LAYER, MissionRole.DEMOLITION, MissionRole.FIREFIGHTER);
            case UnitType.AEROSPACE_FIGHTER, UnitType.CONV_FIGHTER -> List.of(MissionRole.RECON,
                  MissionRole.GROUND_SUPPORT, MissionRole.INTERCEPTOR, MissionRole.ASSAULT, MissionRole.CARGO);
            default -> List.of();
        };
    }

    public boolean role(MissionRole role) {
        return roles.contains(role);
    }

    public void role(MissionRole role, boolean chosen) {
        if (chosen) {
            roles.add(role);
        } else {
            roles.remove(role);
        }
    }

    public static String roleName(MissionRole role) {
        return Messages.getString("MissionRole." + role.toString().toLowerCase());
    }

    public static String roleTip(MissionRole role) {
        return Messages.getString("MissionRole." + role.toString().toLowerCase() + ".tooltip");
    }

    public boolean detachments() {
        return detachments;
    }

    public void detachments(boolean detachments) {
        this.detachments = detachments;
    }

    public int dropshipPercent() {
        return dropshipPercent;
    }

    public void dropshipPercent(int percent) {
        dropshipPercent = Math.max(0, percent);
    }

    public int jumpshipPercent() {
        return jumpshipPercent;
    }

    public void jumpshipPercent(int percent) {
        jumpshipPercent = Math.max(0, percent);
    }

    public int warshipPercent() {
        return warshipPercent;
    }

    public void warshipPercent(int percent) {
        warshipPercent = Math.max(0, percent);
    }

    public int cargoPercent() {
        return cargoPercent;
    }

    public void cargoPercent(int percent) {
        cargoPercent = Math.max(0, percent);
    }

    /** Whether a carried fighter complement could fill anything: the roll brings ships, or is ships. */
    public boolean fighterComplementOffered() {
        if ((dropshipPercent > 0) || (jumpshipPercent > 0) || (warshipPercent > 0)) {
            return true;
        }
        Integer type = unitType();
        return (type != null) && ((type == UnitType.DROPSHIP) || (type == UnitType.JUMPSHIP)
              || (type == UnitType.WARSHIP) || (type == UnitType.SPACE_STATION));
    }

    public boolean fighterComplement() {
        return fighterComplement;
    }

    public void fighterComplement(boolean fighterComplement) {
        this.fighterComplement = fighterComplement;
    }

    /** The force to roll, as the description stands, starting from the ships {@code lift} already has. */
    public ForceDescriptor build(ExistingLift lift) {
        ForceDescriptor force = new ForceDescriptor();
        force.setTopLevel(true);
        force.setYear(year);
        force.setFaction(description.getFaction());
        force.setUnitType(description.getUnitType());
        force.setEchelon(description.getEchelon());
        force.setAugmented(description.isAugmented());
        force.setSizeMod(description.getSizeMod());
        force.getFlags().addAll(description.getFlags());
        force.setRating(description.getRating());
        force.setExperience((experience != null) ? experience : CrewDescriptor.randomExperienceLevel());
        force.setWeightClass(weightPicksUnits() && !infantry() ? weightClass : null);
        force.setInfantryClass(infantry() ? infantryClass : null);
        force.setAttachments(detachments);
        for (MissionRole role : roleChoices()) {
            if (roles.contains(role)) {
                force.getRoles().add(role);
            }
        }
        force.setDropshipPct(dropshipPercent * 0.01);
        force.setJumpshipPct(jumpshipPercent * 0.01);
        force.setWarshipPct(warshipPercent * 0.01);
        force.setCargoPct(cargoPercent);
        force.setFighterComplement(fighterComplement && fighterComplementOffered());
        force.setExistingLift(lift);
        return force;
    }

    // ---- The choice chain, as the Swing generator refreshes it

    private void refreshFactions() {
        List<FactionRecord> active = new ArrayList<>();
        for (FactionRecord record : RATGenerator.getInstance().getFactionList()) {
            if (record.getKey().contains(".") || !record.isActiveInYear(year)) {
                continue;
            }
            // A faction whose rules offer no unit types could only show empty menus
            TOCNode menus = Ruleset.findTOCNode(Ruleset.findRuleset(record.getKey()));
            if ((menus != null) && menus.offersUnitTypes(record.getKey(), year)) {
                active.add(record);
            }
        }
        active.sort(Comparator.comparing(record -> record.getName(year)));
        factions = List.copyOf(active);
        FactionRecord kept = null;
        for (FactionRecord record : factions) {
            if ((faction != null) && record.getKey().equals(faction.getKey())) {
                kept = record;
            }
        }
        if (kept == null) {
            for (FactionRecord record : factions) {
                if (record.getKey().equals(FactionRecord.IS_GENERAL_KEY)) {
                    kept = record;
                }
            }
        }
        if ((kept == null) && !factions.isEmpty()) {
            kept = factions.get(0);
        }
        faction = kept;
        description.setFaction(factionKey());
        refreshCommands();
    }

    private void refreshCommands() {
        List<FactionRecord> found = new ArrayList<>();
        if (faction != null) {
            String prefix = faction.getKey() + ".";
            // Subunits are left out: every regiment of every command would swell the list by hundreds
            for (FactionRecord record : RATGenerator.getInstance().getFactionList()) {
                if (record.getKey().startsWith(prefix) && !record.isSubunit() && record.isActiveInYear(year)) {
                    found.add(record);
                }
            }
        }
        found.sort(Comparator.comparing(record -> record.getName(year)));
        commands = List.copyOf(found);
        if ((command != null) && !commands.contains(command)) {
            command = null;
        }
        description.setFaction(factionKey());
        refreshUnitTypes();
    }

    private @Nullable TOCNode toc() {
        return Ruleset.findTOCNode(Ruleset.findRuleset(description));
    }

    private void refreshUnitTypes() {
        Integer current = description.getUnitType();
        unitTypes.clear();
        TOCNode toc = toc();
        ValueNode offered = (toc == null) ? null : toc.findUnitTypes(description);
        if (offered == null) {
            unitTypes.add(null);
        } else {
            List<Integer> listed = new ArrayList<>();
            for (String unitType : offered.getContent().split(",")) {
                listed.add(unitType.equals("null") ? null : AbstractUnitRecord.parseUnitType(unitType));
            }
            unitTypes.addAll(unitTypesWithUnits(listed));
        }
        Integer chosen = current;
        if (!unitTypes.contains(current)) {
            Ruleset ruleset = Ruleset.findRuleset(description.getFaction());
            chosen = (ruleset == null) ? null : ruleset.getDefaultUnitType(description);
            if ((chosen == null || !unitTypes.contains(chosen)) && !unitTypes.isEmpty()) {
                chosen = unitTypes.get(0);
            }
        }
        description.setUnitType(chosen);
        refreshUnitTypeOptions();
        refreshFormations();
    }

    /** The offered types the faction's tables stock, plus combined arms; all of them when none is stocked. */
    private List<Integer> unitTypesWithUnits(List<Integer> offered) {
        List<Integer> stocked = new ArrayList<>();
        boolean anyStocked = false;
        for (Integer unitType : offered) {
            if (unitType == null) {
                stocked.add(null);
            } else if (hasUnits(unitType)) {
                stocked.add(unitType);
                anyStocked = true;
            }
        }
        return anyStocked ? stocked : offered;
    }

    private boolean hasUnits(int unitType) {
        FactionRecord record = RATGenerator.getInstance().getFaction(description.getFaction());
        if ((record == null) || !RATGenerator.getInstance().isInitialized()) {
            return true;
        }
        return UnitTable.findTable(record, unitType, year, null, new ArrayList<>(), ModelRecord.NETWORK_NONE,
              new ArrayList<>(), new ArrayList<>(), 0).getNumEntries() > 0;
    }

    /** Resets what the new unit type cannot use: a weight it ignores, a hidden weight or class, other roles. */
    private void refreshUnitTypeOptions() {
        if (!weightPicksUnits() || infantry()) {
            weightClass = null;
        }
        if (!infantry()) {
            infantryClass = null;
        }
    }

    private void refreshFormations() {
        formations.clear();
        TOCNode toc = toc();
        Ruleset ruleset = Ruleset.findRuleset(description);
        ValueNode offered = (toc == null) ? null : toc.findEchelons(description);
        if (offered != null) {
            for (String code : offered.getContent().split(",")) {
                formations.put(code, echelonName(ruleset, code));
            }
        }
        String chosen = formation;
        if ((chosen == null) || !formations.containsKey(chosen)) {
            Ruleset factionRules = Ruleset.findRuleset(description.getFaction());
            chosen = (factionRules == null) ? null : factionRules.getDefaultEschelon(description);
            if (((chosen == null) || !formations.containsKey(chosen)) && !formations.isEmpty()) {
                chosen = formations.keySet().iterator().next();
            }
        }
        formation = null;
        if (chosen != null) {
            applyFormation(chosen);
        }
        refreshRatings();
    }

    private String echelonName(@Nullable Ruleset start, String code) {
        int echelon = MathUtility.parseInt(code.replaceAll("[^0-9]", ""), 0);
        ForceNode node = null;
        Ruleset ruleset = start;
        while ((node == null) && (ruleset != null)) {
            node = ruleset.findForceNode(description, echelon, code.endsWith("^"));
            if (node == null) {
                ruleset = (ruleset.getParent() == null) ? null : Ruleset.findRuleset(ruleset.getParent());
            }
        }
        String name = (node != null) ? node.getEchelonName() : code;
        if (code.endsWith("+")) {
            name = Messages.getString("ForceGeneratorDialog.reinforced", name);
        }
        if (code.endsWith("-")) {
            name = Messages.getString("ForceGeneratorDialog.understrength", name);
        }
        return name;
    }

    private void applyFormation(String code) {
        formation = code;
        description.setEchelon(MathUtility.parseInt(code.replaceAll("[^0-9]", ""), 0));
        description.setAugmented(code.contains("^"));
        description.setSizeMod(code.endsWith("+") ? 1 : (code.endsWith("-") ? -1 : 0));
    }

    private void refreshRatings() {
        ratings.clear();
        TOCNode toc = toc();
        ValueNode offered = (toc == null) ? null : toc.findRatings(description);
        if ((offered != null) && (offered.getContent() != null)) {
            for (String rating : offered.getContent().split(",")) {
                String code = rating;
                String name = null;
                if (rating.contains(":")) {
                    String[] fields = rating.split(":");
                    code = fields[0];
                    name = fields[1];
                } else {
                    String text = Messages.getString("ForceGeneratorDialog.rating." + code);
                    name = text.startsWith("!") ? null : text;
                }
                ratings.put(code, (name == null) ? code : name + " (" + code + ")");
            }
        }
        Ruleset ruleset = Ruleset.findRuleset(description.getFaction());
        String rating = (ruleset == null) ? null : ruleset.getDefaultRating(description);
        if ((rating == null) && !ratings.isEmpty()) {
            rating = ratings.keySet().iterator().next();
        }
        if (!ratings.isEmpty() && !ratings.containsKey(rating)) {
            rating = ratings.keySet().iterator().next();
        }
        if (rating != null) {
            description.setRating(rating);
        }
        refreshFlags();
    }

    private void refreshFlags() {
        flags.clear();
        TOCNode toc = toc();
        ValueNode offered = (toc == null) ? null : toc.findFlags(description);
        if ((offered != null) && (offered.getContent() != null)) {
            for (String option : offered.getContent().split(",")) {
                if (option.contains(":")) {
                    String[] fields = option.split(":");
                    flags.put(fields[0], fields[1]);
                } else {
                    flags.put(option, option);
                }
            }
        }
        flag(null);
    }

    // ---- Generating

    /**
     * Generates {@code rolled}, a force {@link #build} described: its units drawn, its crews augmented. It touches
     * nothing of the form, so it runs off the Swing thread, as the Swing generator's does.
     */
    public static void process(ForceDescriptor rolled, GameOptions options, Ruleset.ProgressListener progress) {
        Ruleset.findRuleset(rolled).processRoot(rolled, progress);
        // Fitted as the force is generated, so the crews carry their implants wherever it goes
        ManeiDominiCrewAugmentor.augment(rolled, options);
        FactionRecord record = RATGenerator.getInstance().getFaction(rolled.getFaction());
        ClanEnhancedImagingAugmentor.augment(rolled, (record != null) && record.isClan(), options);
    }

    /**
     * Folds a {@link #process processed} force into the command; Swing thread.
     *
     * @return whether it has any units; when it has none, the command is as it was
     */
    public boolean take(ForceDescriptor rolled) {
        if (!rolled.hasLineUnits()) {
            LOGGER.info("[ForceGen] no units for faction={} year={} unitType={} echelon={}", rolled.getFaction(),
                  rolled.getYear(), rolled.getUnitType(), rolled.getEchelon());
            return false;
        }
        accept(rolled);
        return true;
    }

    /** What the player is told when a roll found no units, as title and text. */
    public static List<String> insufficientData() {
        return List.of(Messages.getString("ForceGeneratorDialog.insufficientData.title"),
              plainText(Messages.getString("ForceGeneratorDialog.insufficientData.text")));
    }

    /** Folds a rolled force into the command, as each Generate does. */
    void accept(ForceDescriptor rolled) {
        ForceEdits.ensureDescriptiveName(rolled);
        top = ForceEdits.accumulate(top, rolled);
        // Every roll brings its own ships; the command lists them in one place
        TransportBranchMerger.foldInto(top, rolled);
        lastRoll = rolled;
        mixShape = null;
    }

    /** The command as it stands, or null before the first roll. */
    public @Nullable ForceDescriptor top() {
        return top;
    }

    public boolean empty() {
        return top == null;
    }

    /** Discards the command, so the next roll starts a fresh one. */
    public void clear() {
        top = null;
        lastRoll = null;
        ids.clear();
        byId.clear();
        brush = null;
        mix = FormationMixPreview.EMPTY;
        mixShape = null;
    }

    /** The organisation the last roll was, such as "Battalion", or blank. */
    public String organization() {
        if (lastRoll == null) {
            return "";
        }
        Ruleset ruleset = Ruleset.findRuleset(lastRoll);
        Integer type = lastRoll.getUnitType();
        String name = (ruleset == null) ? null
              : ruleset.getEschelonNames((type == null) ? "" : UnitType.getTypeName(type))
                    .get(lastRoll.getEchelonCode());
        return (name == null) ? "" : name;
    }

    /** The faction the last roll was for, by name, or blank. */
    public String rolledFaction() {
        if (lastRoll == null) {
            return "";
        }
        FactionRecord record = RATGenerator.getInstance().getFaction(lastRoll.getFaction());
        return (record == null) ? lastRoll.getFaction() : record.getName(lastRoll.getYear());
    }

    /** The last roll's experience and rating, such as "Regular/C", or blank. */
    public String rolledRating() {
        if ((lastRoll == null) || (lastRoll.getExperience() == null)) {
            return "";
        }
        int index = lastRoll.getExperience() + SkillLevel.GREEN.ordinal();
        String skill = ((index >= 0) && (index < SkillLevel.values().length))
              ? SkillLevel.values()[index].toString() : "";
        return skill + ((lastRoll.getRating() == null) ? "" : "/" + lastRoll.getRating());
    }

    /** What the units were rolled for, for the reports, or null before the first roll. */
    public @Nullable GenerationContext context() {
        ForceDescriptor root = (top == null) ? null : top;
        if (root == null) {
            return null;
        }
        return new GenerationContext(root.getFaction(), root.getYear(), root.getRating(),
              GenerationContext.Source.FORCE_GENERATOR);
    }

    // ---- The command's nodes

    /** A node's id, the same for as long as the node is in the command. */
    public String id(ForceDescriptor node) {
        String id = ids.get(node);
        if (id == null) {
            id = "fg-" + nextId++;
            ids.put(node, id);
            byId.put(id, node);
        }
        return id;
    }

    /** The node with {@code id}, or null when it has left the command. */
    public @Nullable ForceDescriptor node(@Nullable String id) {
        return (id == null) ? null : byId.get(id);
    }

    /** A node's children as the tree shows them: none for a single unit. */
    public static List<ForceDescriptor> children(ForceDescriptor node) {
        List<ForceDescriptor> children = new ArrayList<>();
        Integer echelon = node.getEchelon();
        if ((echelon != null) && (echelon == 0)) {
            return children;
        }
        for (Object child : node.getAllChildren()) {
            if (child instanceof ForceDescriptor descriptor) {
                children.add(descriptor);
            }
        }
        return children;
    }

    /** A node's first line: the unit, ship-first for large craft, or the formation's name. */
    public static String label(ForceDescriptor node) {
        if (node.isElement()) {
            Entity entity = node.getEntity();
            if ((entity != null) && entity.isLargeCraft()) {
                String shipName = node.getFluffName();
                return ((shipName != null) && !shipName.isBlank()) ? shipName + ", " + entity.getShortName()
                      : entity.getShortName();
            }
            return String.valueOf(node.getModelName());
        }
        String name = node.parseName();
        if ((name == null) || name.isBlank()) {
            name = node.getDescription();
        }
        return (name == null) ? "" : name;
    }

    /** A node's second line: the crew and role of a unit, or the formation's description and commanders. */
    public static String detail(ForceDescriptor node) {
        List<String> parts = new ArrayList<>();
        if (node.isElement()) {
            parts.add((node.getCo() == null) ? Messages.getString("ForceGeneratorDialog.noCrew")
                  : node.getCo().getName() + " (" + node.getCo().getGunnery() + "/" + node.getCo().getPiloting()
                        + ")");
            Entity entity = node.getEntity();
            String fluff = node.getFluffName();
            if ((fluff != null) && !fluff.isBlank() && ((entity == null) || !entity.isLargeCraft())) {
                parts.add(fluff);
            }
            String role = role(entity);
            if (role != null) {
                parts.add(role);
            }
        } else {
            String name = node.parseName();
            String description = node.getDescription();
            if ((name != null) && !name.isBlank() && (description != null) && !description.isBlank()) {
                parts.add(description);
            }
            if (node.getCo() != null) {
                parts.add(commanderPrefix(node.getCo(), "CO") + node.getCo().getName());
            }
            if (node.getXo() != null) {
                parts.add(commanderPrefix(node.getXo(), "XO") + node.getXo().getName());
            }
        }
        return String.join(" · ", parts);
    }

    /** The words besides its lines that find a node: chassis and model, crew and ship names. */
    public static List<String> keywords(ForceDescriptor node) {
        List<String> words = new ArrayList<>();
        Entity entity = node.getEntity();
        if (entity != null) {
            words.add(entity.getChassis());
            words.add(entity.getModel());
        }
        if (node.getFluffName() != null) {
            words.add(node.getFluffName());
        }
        if (node.getCo() != null) {
            words.add(node.getCo().getName());
        }
        if (node.getXo() != null) {
            words.add(node.getXo().getName());
        }
        words.removeIf(Objects::isNull);
        return words;
    }

    private static @Nullable String role(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        MekSummary summary = MekSummaryCache.getInstance().getMek(entity.getShortNameRaw());
        return ((summary == null) || (summary.getRole() == null)) ? null : summary.getRole().toString();
    }

    /** "Captain " or "CO: ": the ruleset's title, the faction's rank, a generic rank, or the role. */
    static String commanderPrefix(CrewDescriptor crew, String role) {
        String title = crew.getTitle();
        if ((title != null) && !title.isBlank()) {
            return title.endsWith(" ") ? title : title + " ";
        }
        Integer rankSystem = (crew.getAssignment() == null) ? null : crew.getAssignment().getRankSystem();
        String rank = Ranks.getInstance().resolveRankName(rankSystem, crew.getRank()).orElse(null);
        if ((rank != null) && !rank.isBlank()) {
            return rank + " ";
        }
        String generic = DEFAULT_RANK_TITLES.get(crew.getRank());
        return (generic != null) ? generic + " " : role + ": ";
    }

    // ---- Editing

    /** Leaves a branch out of what goes to the game, or brings it back. */
    public void toggleIncluded(ForceDescriptor node) {
        node.setIncludedRecursively(!node.isIncluded());
    }

    public static boolean ship(ForceDescriptor node) {
        return ShipReroller.isShip(node);
    }

    /** Swaps a ship for another design that does the same job; Swing thread. Whether it changed. */
    public boolean rerollShip(ForceDescriptor ship) {
        return rerollShip(ship, node -> ShipReroller.reroll(node) != null);
    }

    /** Swaps a ship with {@code reroll}, forgetting the ids of all that was under it before. */
    boolean rerollShip(ForceDescriptor ship, Predicate<ForceDescriptor> reroll) {
        // The reroll takes the old fighters off the ship, out of reach once it is done
        List<ForceDescriptor> before = new ArrayList<>();
        subtree(ship, before);
        boolean changed = reroll.test(ship);
        if (changed) {
            before.forEach(this::forgetOnly);
        }
        return changed;
    }

    private static void subtree(ForceDescriptor node, List<ForceDescriptor> nodes) {
        nodes.add(node);
        for (Object child : node.getAllChildren()) {
            if (child instanceof ForceDescriptor descriptor) {
                subtree(descriptor, nodes);
            }
        }
    }

    /** Whether a node holds a formation the brush could re-roll it as. */
    public static boolean formationNode(ForceDescriptor node) {
        return !node.getEligibleFormations().isEmpty();
    }

    /** Whether a formation could be added under a node. */
    public static boolean holdsFormations(ForceDescriptor node) {
        return ForceEdits.subForceTemplate(node) != null;
    }

    /** Why the brush cannot re-roll {@code node}, or null when it can. */
    public @Nullable Blocker changeBlocker(ForceDescriptor node) {
        if (brush == null) {
            return Blocker.NO_SELECTION;
        }
        if (!formationNode(node)) {
            return Blocker.NOT_A_FORMATION;
        }
        return (FormationType.getFormationType(brush) == null) ? Blocker.UNKNOWN_FORMATION : null;
    }

    /** Why a formation of the brush cannot be added under {@code node}, or null when it can. */
    public @Nullable Blocker addBlocker(ForceDescriptor node) {
        if (brush == null) {
            return Blocker.NO_SELECTION;
        }
        if (!holdsFormations(node)) {
            return Blocker.NO_TEMPLATE;
        }
        return (FormationType.getFormationType(brush) == null) ? Blocker.UNKNOWN_FORMATION : null;
    }

    /** Whether the node's own rules offer the brush, else re-rolling it is an unusual choice. */
    public boolean offers(ForceDescriptor node) {
        return (brush != null) && node.getEligibleFormations().containsKey(brush);
    }

    /** The question asked before the brush re-rolls {@code node}. */
    public String changeQuestion(ForceDescriptor node) {
        return Messages.getString(offers(node) ? "ForceGeneratorDialog.changeFormation.confirm"
              : "ForceGeneratorDialog.changeFormation.confirmUnoffered", node.parseName(), brushName());
    }

    /** The question asked before a formation of the brush is added under {@code node}. */
    public String addQuestion(ForceDescriptor node) {
        return Messages.getString("ForceGeneratorDialog.addFormation.confirm", brushName(), node.parseName());
    }

    /** Re-rolls {@code node} as the brush; Swing thread, after {@link #changeBlocker} said it can. */
    public ForceEdits.Result changeFormation(ForceDescriptor node) {
        FormationType type = Objects.requireNonNull(FormationType.getFormationType(brush));
        forgetBelow(node);
        ForceEdits.Result result = ForceEdits.changeFormation(node, type);
        LOGGER.info("[ForceGen][ChangeFormation] '{}' as {}: {}", node.parseName(), type.getName(), result);
        return result;
    }

    /** Adds a formation of the brush under {@code node}; Swing thread, after {@link #addBlocker} said it can. */
    public ForceEdits.Result addFormation(ForceDescriptor node) {
        FormationType type = Objects.requireNonNull(FormationType.getFormationType(brush));
        ForceEdits.Result result = ForceEdits.addSubForce(node, Objects.requireNonNull(ForceEdits.subForceTemplate(
              node)), type);
        LOGGER.info("[ForceGen][AddFormation] {} under '{}': {}", type.getName(), node.parseName(), result);
        return result;
    }

    /** What to tell the player after an edit, as title and text, or null when it went as asked. */
    public @Nullable List<String> outcome(ForceEdits.Result result, ForceDescriptor node) {
        return switch (result) {
            case CHANGED -> null;
            case INSUFFICIENT -> insufficientData();
            case DROPPED -> List.of(Messages.getString("ForceGeneratorDialog.formationDropped.title"),
                  Messages.getString("ForceGeneratorDialog.formationDropped", brushName(), node.getFaction(),
                        String.valueOf(node.getYear())));
        };
    }

    /** Forgets the ids under a node whose units an edit replaces, so they do not keep stale entities. */
    private void forgetBelow(ForceDescriptor node) {
        for (ForceDescriptor child : children(node)) {
            forget(child);
        }
    }

    private void forget(ForceDescriptor node) {
        forgetOnly(node);
        forgetBelow(node);
    }

    private void forgetOnly(ForceDescriptor node) {
        String id = ids.remove(node);
        if (id != null) {
            byId.remove(id);
        }
    }

    // ---- The formation mix

    /** The formation picked to apply to the tree's nodes, or null. */
    public @Nullable String brush() {
        return brush;
    }

    public void brush(@Nullable String formationName) {
        brush = formationName;
    }

    public String brushName() {
        FormationType type = (brush == null) ? null : FormationType.getFormationType(brush);
        return (type == null) ? String.valueOf(brush) : type.getName();
    }

    /**
     * Samples which formations a force shaped like {@code context} - its unit type, echelon and augmentation, the
     * rest from the description - is offered; the settings' own shape for null. Rebuilt only when the shape changes;
     * Swing thread.
     */
    public void sampleMix(@Nullable ForceDescriptor context) {
        String shape = (context == null) ? "settings:" + description.getUnitType() + "/" + description.getEchelon()
              : context.getUnitType() + "/" + context.getEchelon() + "/" + context.isAugmented();
        if (shape.equals(mixShape)) {
            return;
        }
        mixShape = shape;
        Ruleset ruleset = Ruleset.findRuleset(probe(context));
        if (ruleset == null) {
            mix = FormationMixPreview.EMPTY;
            return;
        }
        List<FormationMixPreview> samples = new ArrayList<>();
        for (int sample = 0; sample < MIX_SAMPLES; sample++) {
            ForceDescriptor probe = probe(context);
            ruleset.buildStructureOnly(probe);
            samples.add(FormationMixPreview.of(probe));
        }
        mix = FormationMixPreview.merged(samples);
    }

    private ForceDescriptor probe(@Nullable ForceDescriptor context) {
        ForceDescriptor probe = build(ExistingLift.of(List.of()));
        if (context != null) {
            probe.setUnitType(context.getUnitType());
            if (context.getEchelon() != null) {
                probe.setEchelon(context.getEchelon());
            }
            probe.setAugmented(context.isAugmented());
        }
        return probe;
    }

    /** The sampled formations grouped into families, ground first, as the palette shows them. */
    public Palette palette() {
        Map<String, Set<String>> ground = new TreeMap<>();
        Map<String, Set<String>> aerospace = new TreeMap<>();
        for (String name : mix.offeredFormations()) {
            FormationType type = FormationType.getFormationType(name);
            String family = (type == null) ? name : type.getCategory();
            FormationType familyType = FormationType.getFormationType(family);
            boolean isGround = (familyType != null) ? familyType.isGround() : ((type == null) || type.isGround());
            (isGround ? ground : aerospace).computeIfAbsent(family, ignored -> new TreeSet<>()).add(name);
        }
        return new Palette(families(ground), families(aerospace));
    }

    private static List<Family> families(Map<String, Set<String>> grouped) {
        List<Family> families = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            Set<String> members = entry.getValue();
            boolean ownHeading = !((members.size() == 1) && members.contains(entry.getKey()));
            families.add(new Family(ownHeading ? entry.getKey() : null, new ArrayList<>(members)));
        }
        return families;
    }

    /** A formation's name in the palette. */
    public static String formationTypeName(String name) {
        FormationType type = FormationType.getFormationType(name);
        return (type == null) ? name : type.getNameWithFaction();
    }

    /** What a formation is, how usual it is here and what it asks of its units, in plain text. */
    public String describe(String name) {
        FormationType type = FormationType.getFormationType(name);
        List<String> lines = new ArrayList<>();
        lines.add(formationTypeName(name));
        if ((type != null) && Messages.keyExists(type.getTooltipKey())) {
            lines.add(plainText(Messages.getString(type.getTooltipKey())));
        }
        lines.add(Messages.getString("ForceGeneratorDialog.formationMix.usuallyBuilt", mix.typicalLancesFor(name)));
        if (type != null) {
            List<String> requirements = new ArrayList<>();
            if (type.getMainDescription() != null) {
                requirements.add(Messages.getString("ForceGeneratorDialog.formationMix.requirement.all",
                      type.getMainDescription()));
            }
            type.getOtherCriteria().forEachRemaining(constraint -> requirements.add(Messages.getString(
                  "ForceGeneratorDialog.formationMix.requirement.some",
                  constraint.getMinimum(NOMINAL_FORMATION_SIZE), constraint.getDescription())));
            lines.add(plainText(requirements.isEmpty()
                  ? Messages.getString("ForceGeneratorDialog.formationMix.requirement.none")
                  : Messages.getString("ForceGeneratorDialog.formationMix.requirements", NOMINAL_FORMATION_SIZE,
                        String.join("; ", requirements))));
            lines.add(plainText(Messages.getString("ForceGeneratorDialog.formationMix.idealRole", type.getIdealRole())));
        }
        int placeable = mix.maximumLancesFor(name);
        lines.add(placeable <= 0 ? Messages.getString("ForceGeneratorDialog.formationMix.ceiling.none")
              : Messages.getString("ForceGeneratorDialog.formationMix.ceiling", placeable, mix.tweakableNodes()));
        return String.join("\n", lines);
    }

    /** {@code text} without its HTML: line breaks kept, tags dropped. */
    public static String plainText(String text) {
        return text.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
    }

    // ---- The summary and the units

    /** The command's composition, by unit type and weight class. */
    public List<ForceComposition.Row> composition() {
        return ForceComposition.rows(top);
    }

    /** The units still included, in tree order, each once. */
    public List<Entity> included() {
        List<Entity> units = new ArrayList<>();
        if (top != null) {
            collect(top, units, new HashSet<>());
        }
        return units;
    }

    private static void collect(ForceDescriptor node, List<Entity> units, Set<String> seen) {
        if (!node.isIncluded()) {
            return;
        }
        if (node.isElement() && (node.getEntity() != null)
              && seen.add(node.getEntity().getExternalIdAsString())) {
            units.add(node.getEntity());
        }
        for (ForceDescriptor child : node.getSubForces()) {
            collect(child, units, seen);
        }
        for (ForceDescriptor child : node.getAttached()) {
            collect(child, units, seen);
        }
    }

    /** The units going to the game, their Battle Value and tonnage. */
    public record Stats(int units, int battleValue, double tons) {
        public static final Stats NONE = new Stats(0, 0, 0);
    }

    /** The included units' count, Battle Value and tonnage; Swing thread, as the Battle Value is calculated. */
    public Stats stats() {
        int battleValue = 0;
        double tons = 0;
        List<Entity> units = included();
        for (Entity unit : units) {
            battleValue += unit.calculateBattleValue();
            tons += unit.getWeight();
        }
        return new Stats(units.size(), battleValue, tons);
    }

    /** Every unit of the command, included or not. */
    public List<Entity> allUnits() {
        ArrayList<Entity> units = new ArrayList<>();
        if (top != null) {
            top.addAllEntities(units);
        }
        return units;
    }

    /**
     * Readies {@code units}, the included ones, to go to the game: their C3 networks wired among themselves and the
     * command's force strings numbered as it stands. Owners are set after this.
     */
    public void wire(List<Entity> units) {
        if (top == null) {
            return;
        }
        C3NetworkConfigurator.configure(top, among(units));
        top.refreshForceStrings();
    }

    /** Loads {@code units} into their carriers; after the owners are set, before they are sent. */
    public void loadCarriers(List<Entity> units) {
        if (top != null) {
            CarrierLoadingConfigurator.configure(top, among(units));
        }
    }

    /** Whether a unit is one of {@code units}: by identity, as every generated unit has the same id until sent. */
    static Predicate<Entity> among(List<Entity> units) {
        Set<Entity> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
        chosen.addAll(units);
        return chosen::contains;
    }

    /** The lift the command's own ships have spare, which a further roll starts from. */
    public ExistingLift commandLift() {
        return ExistingLift.of(top);
    }

    /** The file name the command exports as, without its extension. */
    public String exportName() {
        String name = (top == null) ? "" : String.valueOf(top.parseName()).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "force" : name;
    }

    /**
     * Writes the units still included to {@code file} as a unit list, their force strings numbered as the command
     * stands and their C3 networks wired among themselves, through a game of its own so it is not added to the real
     * one.
     *
     * @return how many units were written
     */
    public int export(File file, GameOptions options) throws IOException {
        List<Entity> units = included();
        if (units.isEmpty() || (top == null)) {
            return 0;
        }
        if (Game.rulesManager instanceof TWRulesManager) {
            options.getOption(OptionsConstants.RULES_SYSTEM).setValue(OptionsConstants.RULES_TW);
        }
        Game game = new Game();
        Player observer = new Player(1, "Observer");
        game.addPlayer(1, observer);
        game.setOptions(options);
        for (Entity unit : units) {
            unit.setOwner(observer);
            unit.setId(game.getNextEntityId());
            game.addEntity(unit);
        }
        C3NetworkConfigurator.configure(top, among(units));
        // Each roll numbered its own force strings; a list read back would merge their duplicate force ids
        top.refreshForceStrings();
        EntityListFile.saveTo(file, new ArrayList<>(units));
        return units.size();
    }
}
