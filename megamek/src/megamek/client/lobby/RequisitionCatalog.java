/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;
import megamek.common.TechConstants;
import megamek.common.internationalization.I18n;
import megamek.common.loaders.MekSummary;
import megamek.common.options.IGameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitRole;
import megamek.common.units.UnitType;

/**
 * What the lobby's Requisition view offers: the units the game's rules allow, as the unit selector dialog
 * ({@code MegaMekUnitSelectorDialog}) reads them (its year, tech level, era-based levels, canon only and invalid units),
 * narrowed to a category, the Commander's filters and a search, sorted by name. It needs no toolkit, so it can run on
 * any thread; the view filters on a thread of its own.
 */
public final class RequisitionCatalog {

    /** The departments along the top of the Requisition view; All shows every unit the rules allow. */
    public enum Category {
        ALL("all", Set.of()),
        MEKS("meks", Set.of(UnitType.MEK)),
        VEHICLES("vehicles", Set.of(UnitType.TANK, UnitType.VTOL, UnitType.NAVAL, UnitType.GUN_EMPLACEMENT)),
        INFANTRY("infantry", Set.of(UnitType.INFANTRY)),
        BATTLE_ARMOR("battleArmor", Set.of(UnitType.BATTLE_ARMOR)),
        PROTOMEKS("protoMeks", Set.of(UnitType.PROTOMEK)),
        AEROSPACE("aerospace", Set.of(UnitType.AEROSPACE_FIGHTER, UnitType.CONV_FIGHTER, UnitType.SMALL_CRAFT,
              UnitType.AERO)),
        LARGE_CRAFT("largeCraft", Set.of(UnitType.DROPSHIP, UnitType.JUMPSHIP, UnitType.WARSHIP,
              UnitType.SPACE_STATION));

        private final String key;
        private final Set<Integer> types;

        Category(String key, Set<Integer> types) {
            this.key = key;
            this.types = types;
        }

        /** The message key of the category's tab. */
        public String messageKey() {
            return "Lobby.requisition.category." + key;
        }

        /** Whether a unit of the {@link UnitType} named {@code unitType} belongs here. */
        public boolean contains(String unitType) {
            return (this == ALL) || types.contains(UnitType.determineUnitTypeCode(unitType));
        }
    }

    /**
     * The game's limits on units, as the unit selector dialog reads them: none newer than {@code year}, none above the
     * simple tech level {@code techLevel} (judged in that year with {@code eraBased}), only canon ones with
     * {@code canonOnly}, and no invalid ones unless {@code allowInvalid}.
     */
    public record Rules(int year, boolean eraBased, int techLevel, boolean canonOnly, boolean allowInvalid) {

        /** No limits: every year, level and unit; for a lobby whose game is not known yet. */
        public static final Rules OPEN = new Rules(Integer.MAX_VALUE, false, TechConstants.T_SIMPLE_UNOFFICIAL,
              false, true);

        /** The rules of {@code options}, read from the same options the unit selector dialog reads. */
        public static Rules of(IGameOptions options) {
            return new Rules(options.intOption(OptionsConstants.ALLOWED_YEAR),
                  options.booleanOption(OptionsConstants.ALLOWED_ERA_BASED),
                  TechConstants.getSimpleLevel(options.stringOption(OptionsConstants.ALLOWED_TECH_LEVEL)),
                  options.booleanOption(OptionsConstants.ALLOWED_CANON_ONLY),
                  options.booleanOption(OptionsConstants.ALLOWED_ALLOW_ILLEGAL_UNITS));
        }

        /** The highest tech level code the game's simple tech level takes in, as the unit selector dialog has it. */
        public int maxTechCode() {
            return switch (techLevel) {
                case TechConstants.T_SIMPLE_INTRO -> TechConstants.T_INTRO_BOX_SET;
                case TechConstants.T_SIMPLE_STANDARD -> TechConstants.T_TW_ALL;
                case TechConstants.T_SIMPLE_ADVANCED -> TechConstants.T_CLAN_ADVANCED;
                case TechConstants.T_SIMPLE_EXPERIMENTAL -> TechConstants.T_CLAN_EXPERIMENTAL;
                default -> TechConstants.T_CLAN_UNOFFICIAL;
            };
        }

        /** The tech level code {@code unit} has in this game: in the game's year when levels are era-based. */
        public int techCode(MekSummary unit) {
            return eraBased ? unit.getType(year) : unit.getType();
        }

        /**
         * Whether the game allows {@code unit}. The dialog's tech level list runs from 0 to {@link #maxTechCode()}
         * without the two "all" levels, so a unit of any other level never matches it.
         */
        public boolean allows(MekSummary unit) {
            int type = techCode(unit);
            return (unit.getYear() <= year)
                  && (type >= 0) && (type <= maxTechCode())
                  && (type != TechConstants.T_IS_TW_ALL) && (type != TechConstants.T_TW_ALL)
                  && (!canonOnly || !unit.isNonCanonBySource())
                  && (allowInvalid || !"F".equals(unit.getLevel()));
        }

        /** The simple rules levels a unit may have here, for the Filters drawer: none above the game's level. */
        public List<Integer> levels() {
            List<Integer> levels = new ArrayList<>();
            for (int level = TechConstants.T_SIMPLE_INTRO; level <= Math.min(techLevel,
                  TechConstants.T_SIMPLE_UNOFFICIAL); level++) {
                levels.add(level);
            }
            return levels;
        }
    }

    /** The weight chips; Light takes in ultra-light units and Assault the super-heavy ones. */
    public enum Weight {
        LIGHT(EntityWeightClass.WEIGHT_ULTRA_LIGHT, EntityWeightClass.WEIGHT_LIGHT),
        MEDIUM(EntityWeightClass.WEIGHT_MEDIUM),
        HEAVY(EntityWeightClass.WEIGHT_HEAVY),
        ASSAULT(EntityWeightClass.WEIGHT_ASSAULT, EntityWeightClass.WEIGHT_SUPER_HEAVY);

        private final Set<Integer> classes;

        Weight(Integer... classes) {
            this.classes = Set.of(classes);
        }

        public String messageKey() {
            return "Lobby.requisition.weight." + name().toLowerCase(Locale.ROOT);
        }

        public boolean contains(int weightClass) {
            return classes.contains(weightClass);
        }
    }

    /** The tech bases a unit may be built on. */
    public enum TechBase {
        IS, CLAN, MIXED;

        /** The base a unit file's tech base text names. */
        public static TechBase of(@Nullable String techBase) {
            if (techBase == null) {
                return IS;
            } else if (techBase.startsWith("Mixed")) {
                return MIXED;
            }
            return techBase.contains("Clan") ? CLAN : IS;
        }

        public String messageKey() {
            return "Lobby.requisition.tech." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** A yes-or-no filter that may also be left open. */
    public enum Either {
        ANY, YES, NO;

        public boolean allows(boolean value) {
            return (this == ANY) || (value == (this == YES));
        }
    }

    /** A unit type within a category, for the Filters drawer's Type row. */
    public enum ExactType {
        TANK(Category.VEHICLES, UnitType.TANK),
        VTOL(Category.VEHICLES, UnitType.VTOL),
        NAVAL(Category.VEHICLES, UnitType.NAVAL),
        GUN_EMPLACEMENT(Category.VEHICLES, UnitType.GUN_EMPLACEMENT),
        SUPPORT_VEHICLE(Category.VEHICLES, -1),
        AEROSPACE_FIGHTER(Category.AEROSPACE, UnitType.AEROSPACE_FIGHTER),
        CONV_FIGHTER(Category.AEROSPACE, UnitType.CONV_FIGHTER),
        SMALL_CRAFT(Category.AEROSPACE, UnitType.SMALL_CRAFT),
        DROPSHIP(Category.LARGE_CRAFT, UnitType.DROPSHIP),
        JUMPSHIP(Category.LARGE_CRAFT, UnitType.JUMPSHIP),
        WARSHIP(Category.LARGE_CRAFT, UnitType.WARSHIP),
        SPACE_STATION(Category.LARGE_CRAFT, UnitType.SPACE_STATION);

        private final Category category;
        private final int unitType;

        ExactType(Category category, int unitType) {
            this.category = category;
            this.unitType = unitType;
        }

        /** The types a category's Type row offers, none when the category is one type. */
        public static List<ExactType> of(Category category) {
            List<ExactType> types = new ArrayList<>();
            for (ExactType type : values()) {
                if (type.category == category) {
                    types.add(type);
                }
            }
            return types;
        }

        public Category category() {
            return category;
        }

        public String messageKey() {
            return "Lobby.requisition.type." + name().toLowerCase(Locale.ROOT);
        }

        /** Whether {@code unit} is of this type; a support vehicle is one whatever its motive type. */
        public boolean matches(MekSummary unit) {
            return (unitType < 0) ? unit.isSupport()
                  : (UnitType.determineUnitTypeCode(unit.getUnitType()) == unitType);
        }
    }

    /**
     * The years a unit was introduced in, both ends included, such as an era's.
     *
     * @param name the span's name, as an era's
     * @param from the first year
     * @param to   the last year, or {@link Integer#MAX_VALUE} for an open end
     */
    public record YearSpan(String name, int from, int to) {
        public boolean contains(int year) {
            return (year >= from) && (year <= to);
        }
    }

    /**
     * The Commander's own narrowing on top of the game's rules: weight chips, and the Filters drawer's type, tech bases,
     * rules levels (simple tech levels), roles, canon, record sheet, era and source book, and the advanced search's
     * criteria. Empty sets leave that filter open; a null type, era or source takes every one.
     */
    public record Filters(Set<Weight> weights, @Nullable ExactType type, Set<TechBase> bases, Set<Integer> levels,
          Set<UnitRole> roles, Either canon, Either recordSheet, @Nullable YearSpan era, @Nullable String source,
          RequisitionAdvancedSearch advanced) {

        public static final Filters NONE = new Filters(Set.of(), null, Set.of(), Set.of(), Set.of(), Either.ANY,
              Either.ANY, null, null, RequisitionAdvancedSearch.NONE);

        public Filters {
            Objects.requireNonNull(advanced);
            weights = Set.copyOf(weights);
            bases = Set.copyOf(bases);
            levels = Set.copyOf(levels);
            roles = Set.copyOf(roles);
        }

        /** Whether the weight chips apply to {@code category}; they mean nothing to DropShips and WarShips. */
        public static boolean weighs(Category category) {
            return category != Category.LARGE_CRAFT;
        }

        public Filters withWeights(Set<Weight> next) {
            return new Filters(next, type, bases, levels, roles, canon, recordSheet, era, source, advanced);
        }

        public Filters withType(@Nullable ExactType next) {
            return new Filters(weights, next, bases, levels, roles, canon, recordSheet, era, source, advanced);
        }

        public Filters withBases(Set<TechBase> next) {
            return new Filters(weights, type, next, levels, roles, canon, recordSheet, era, source, advanced);
        }

        public Filters withLevels(Set<Integer> next) {
            return new Filters(weights, type, bases, next, roles, canon, recordSheet, era, source, advanced);
        }

        public Filters withRoles(Set<UnitRole> next) {
            return new Filters(weights, type, bases, levels, next, canon, recordSheet, era, source, advanced);
        }

        public Filters withCanon(Either next) {
            return new Filters(weights, type, bases, levels, roles, next, recordSheet, era, source, advanced);
        }

        public Filters withRecordSheet(Either next) {
            return new Filters(weights, type, bases, levels, roles, canon, next, era, source, advanced);
        }

        public Filters withEra(@Nullable YearSpan next) {
            return new Filters(weights, type, bases, levels, roles, canon, recordSheet, next, source, advanced);
        }

        public Filters withSource(@Nullable String next) {
            return new Filters(weights, type, bases, levels, roles, canon, recordSheet, era, next, advanced);
        }

        public Filters withAdvanced(RequisitionAdvancedSearch next) {
            return new Filters(weights, type, bases, levels, roles, canon, recordSheet, era, source, next);
        }

        /** These filters without the drawer's, keeping the weight chips. */
        public Filters withoutDrawer() {
            return NONE.withWeights(weights);
        }

        /** How many of the drawer's filters are at work, the weight chips aside. */
        public int drawerCount() {
            return ((type == null) ? 0 : 1) + bases.size() + levels.size() + roles.size()
                  + ((canon == Either.ANY) ? 0 : 1) + ((recordSheet == Either.ANY) ? 0 : 1)
                  + ((era == null) ? 0 : 1) + ((source == null) ? 0 : 1) + advanced.count();
        }

        /**
         * The filters kept as a preference from game to game, the weights, tech bases and rules levels, as one line
         * such as {@code W:LIGHT,HEAVY;B:CLAN;L:1,2}, which {@link #withKept} reads back.
         */
        public String kept() {
            return "W:" + names(weights) + ";B:" + names(bases) + ";L:"
                  + levels.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        }

        /** {@link #kept()} with the category tab the Commander last looked at, which {@link #keptCategory} reads. */
        public String kept(Category category) {
            return "C:" + category.name() + ";" + kept();
        }

        /** The category tab {@code kept} names, or {@link Category#ALL} when it names none this version knows. */
        public static Category keptCategory(@Nullable String kept) {
            for (String part : (kept == null) ? new String[0] : kept.split(";")) {
                String trimmed = part.strip();
                if (trimmed.startsWith("C:")) {
                    try {
                        return Category.valueOf(trimmed.substring(2).strip());
                    } catch (IllegalArgumentException unknown) {
                        return Category.ALL;
                    }
                }
            }
            return Category.ALL;
        }

        private static String names(Set<? extends Enum<?>> values) {
            return values.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
        }

        /**
         * These filters with the weights, tech bases and rules levels {@code kept} names, as {@link #kept} wrote
         * them; the rest stay. Levels outside {@code offered}, the game's, and names this version no longer knows are
         * dropped; a null or blank line keeps nothing.
         */
        public Filters withKept(@Nullable String kept, Collection<Integer> offered) {
            Set<Weight> keptWeights = EnumSet.noneOf(Weight.class);
            Set<TechBase> keptBases = EnumSet.noneOf(TechBase.class);
            Set<Integer> keptLevels = new HashSet<>();
            for (String part : (kept == null) ? new String[0] : kept.split(";")) {
                int colon = part.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = part.substring(0, colon).strip();
                for (String item : part.substring(colon + 1).split(",")) {
                    String name = item.strip();
                    if (name.isEmpty()) {
                        continue;
                    }
                    try {
                        switch (key) {
                            case "W" -> keptWeights.add(Weight.valueOf(name));
                            case "B" -> keptBases.add(TechBase.valueOf(name));
                            case "L" -> {
                                int level = Integer.parseInt(name);
                                if (offered.contains(level)) {
                                    keptLevels.add(level);
                                }
                            }
                            default -> {
                                // A part a later version added
                            }
                        }
                    } catch (IllegalArgumentException ignored) {
                        // A choice this version doesn't know is dropped
                    }
                }
            }
            return withWeights(keptWeights).withBases(keptBases).withLevels(keptLevels);
        }

        public boolean allows(MekSummary unit, Rules rules, Category category) {
            if (!weights.isEmpty() && weighs(category)
                  && weights.stream().noneMatch(weight -> weight.contains(unit.getWeightClass()))) {
                return false;
            }
            if ((type != null) && (type.category() == category) && !type.matches(unit)) {
                return false;
            }
            if (!bases.isEmpty() && !bases.contains(TechBase.of(unit.getTechBase()))) {
                return false;
            }
            if (!levels.isEmpty()) {
                int code = rules.techCode(unit);
                int simple = ((code < 0) || (code >= TechConstants.SIZE)) ? TechConstants.T_TECH_UNKNOWN
                      : TechConstants.convertFromNormalToSimple(code);
                if (!levels.contains(simple)) {
                    return false;
                }
            }
            if (!roles.isEmpty() && !roles.contains(roleOf(unit))) {
                return false;
            }
            if ((era != null) && !era.contains(unit.getYear())) {
                return false;
            }
            if ((source != null) && !source.equals(sourceOf(unit))) {
                return false;
            }
            return (rules.canonOnly() || canon.allows(!unit.isNonCanonBySource()))
                  && recordSheet.allows(unit.hasPublishedRecordSheet());
        }
    }

    /**
     * One unit as the search reads it: its words without case or accents, the key it sorts by, and its Battlefield
     * Support asset form (or null) for the advanced search.
     */
    private record Entry(MekSummary unit, String text, String sortKey, @Nullable MekSummary asset) { }

    /**
     * The catalogue made ready to search: the units in name order with their search text worked out once, so a search
     * over thousands of units only compares text.
     */
    public static final class Index {
        private final List<Entry> entries;

        private Index(List<Entry> entries) {
            this.entries = entries;
        }

        public int size() {
            return entries.size();
        }
    }

    /** An empty catalogue, as before the unit cache has loaded. */
    public static final Index EMPTY = new Index(List.of());

    private RequisitionCatalog() {
    }

    /** {@code unit}'s role, {@link UnitRole#UNDETERMINED} when it has none. */
    public static UnitRole roleOf(MekSummary unit) {
        UnitRole role = unit.getRole();
        return (role == null) ? UnitRole.UNDETERMINED : role;
    }

    /** The source book {@code unit} comes from, stripped; empty when it names none. */
    public static String sourceOf(MekSummary unit) {
        String source = unit.getSource();
        return (source == null) ? "" : source.strip();
    }

    /** The name a unit sorts by: lower case, without leading quotes and other marks, so 'Astrolux' files under A. */
    public static String sortKey(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        int start = 0;
        while ((start < key.length()) && !Character.isLetterOrDigit(key.charAt(start))) {
            start++;
        }
        return key.substring(start);
    }

    /**
     * Readies {@code all} for searching, in name order. {@code skip} drops units listed elsewhere, as an asset shown
     * under its base unit; {@code assetOf} gives a unit's Battlefield Support asset form, whose card title and subtitle
     * the search also reads, as the unit selector dialog's does, or null.
     */
    public static Index index(@Nullable MekSummary[] all, Predicate<MekSummary> skip,
          Function<MekSummary, MekSummary> assetOf) {
        if (all == null) {
            return EMPTY;
        }
        List<Entry> entries = new ArrayList<>(all.length);
        for (MekSummary unit : all) {
            if ((unit != null) && !skip.test(unit)) {
                MekSummary asset = assetOf.apply(unit);
                entries.add(new Entry(unit, searchText(unit, asset), sortKey(unit.getName()), asset));
            }
        }
        entries.sort(Comparator.comparing(Entry::sortKey).thenComparing(entry -> entry.unit().getName()));
        return new Index(List.copyOf(entries));
    }

    /**
     * The units in {@code index} that {@code rules} allow, in {@code category}, passing {@code filters} and matching
     * {@code search}, in name order.
     */
    public static List<MekSummary> offer(Index index, Rules rules, Category category, Filters filters,
          @Nullable String search) {
        List<MekSummary> offered = new ArrayList<>();
        String[] words = words(search);
        for (Entry entry : index.entries) {
            MekSummary unit = entry.unit();
            if (matches(entry.text(), words) && category.contains(unit.getUnitType()) && rules.allows(unit)
                  && filters.allows(unit, rules, category) && filters.advanced().matches(unit, entry.asset())) {
                offered.add(unit);
            }
        }
        return offered;
    }

    /** The source books of the units in {@code index} that {@code rules} allow, sorted, for the Source filter. */
    public static List<String> sources(Index index, Rules rules) {
        Set<String> sources = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry entry : index.entries) {
            String source = sourceOf(entry.unit());
            if (!source.isEmpty() && rules.allows(entry.unit())) {
                sources.add(source);
            }
        }
        return List.copyOf(sources);
    }

    /** The search split into lower-case words without accents; none for a blank search. */
    public static String[] words(@Nullable String search) {
        if ((search == null) || search.isBlank()) {
            return new String[0];
        }
        return I18n.normalizeTextToASCII(search.strip(), false).toLowerCase(Locale.ROOT).split("\\s+");
    }

    /**
     * The text the search reads for {@code unit}: chassis and model, and its asset form's card title and subtitle,
     * without case or accents, as the unit selector dialog's text filter reads it.
     */
    public static String searchText(MekSummary unit, @Nullable MekSummary asset) {
        String suffix = "";
        if (asset != null) {
            suffix = "###" + nonNull(asset.getBfsCardTitle()) + "###" + nonNull(asset.getBfsCardSubtitle());
        }
        return I18n.normalizeTextToASCII(unit.getName() + "###" + unit.getModel() + suffix, true)
              .toLowerCase(Locale.ROOT);
    }

    /** Whether every one of {@code words} is part of {@code text}. */
    public static boolean matches(String text, String[] words) {
        for (String word : words) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static String nonNull(@Nullable String text) {
        return (text == null) ? "" : text;
    }
}
