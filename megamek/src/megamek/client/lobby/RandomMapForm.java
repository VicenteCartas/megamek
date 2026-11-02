/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.Configuration;
import megamek.common.loaders.MapSettings;
import megamek.common.util.BoardUtilities;

/**
 * The random map settings without a toolkit, the native counterpart of the Swing RandomMapDialog: the normal view's
 * levels (None to High) per terrain feature, the advanced view's every generator parameter, the generated boards'
 * size and theme, and map generator files. Edits change a working copy at once, as the dialog's panels read back
 * when switching views; {@link #apply} sends it. Runs on the Swing thread.
 */
public final class RandomMapForm {
    private static final String KEY = "RandomMapDialog.";
    /** The folder map generator files start in. */
    public static final String SETTINGS_FOLDER = "mapgen";
    public static final String EXTENSION = ".xml";
    /** The upper bound of the fields the dialog only requires to be positive. */
    public static final int POSITIVE_MAX = 999;
    private static final int PERCENT = 100;

    /** A normal view level. */
    public enum Level {
        NONE("uiNONE"), LOW("uiLow"), MEDIUM("uiMedium"), HIGH("uiHigh");

        private final String key;

        Level(String key) {
            this.key = key;
        }

        public String label() {
            return Messages.getString(KEY + key);
        }

        /** The dialog's reading of a setting: High from {@code high} on, Medium from {@code medium}, Low from low. */
        static Level of(int value, int low, int medium, int high) {
            if (value >= high) {
                return HIGH;
            } else if (value >= medium) {
                return MEDIUM;
            } else if (value >= low) {
                return LOW;
            }
            return NONE;
        }

        int percent() {
            return ordinal() * 25;
        }
    }

    /** The dialog's tabs, the themes both views group their settings by. */
    public enum Tab {
        CIVILIZATION("tabCivilization"), ELEVATION("tabElevation"), NATURAL("tabNatural"), WATER("tabWater"),
        EFFECTS("tabEffects");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        public String label() {
            return Messages.getString(KEY + key);
        }
    }

    /** A terrain feature of the normal view, in the dialog's order. */
    public enum Feature {
        FORTIFIED(Tab.CIVILIZATION, "labFortified", "fortifiedCombo"),
        PAVEMENT(Tab.CIVILIZATION, "labPavement", "pavementCombo"),
        PLANTED_FIELDS(Tab.CIVILIZATION, "labPlantedField", "plantedFieldsCombo"),
        ROADS(Tab.CIVILIZATION, "labRoads", "roadsCombo"),
        RUBBLE(Tab.CIVILIZATION, "labRubble", "rubbleCombo"),
        CLIFFS(Tab.ELEVATION, "labCliffs", "cliffsCombo"),
        CRATERS(Tab.ELEVATION, "labCraters", "cratersCombo"),
        HILLS(Tab.ELEVATION, "labElevation", "hillinessCombo"),
        MOUNTAINS(Tab.ELEVATION, "labMountain", "mountainCombo"),
        ROUGH(Tab.NATURAL, "labRough", "roughsCombo"),
        SAND(Tab.NATURAL, "labSand", "sandsCombo"),
        SWAMP(Tab.NATURAL, "labSwamp", "swampsCombo"),
        WOODS(Tab.NATURAL, "labWoods", "woodsCombo"),
        JUNGLE(Tab.NATURAL, "labJungle", "jungleCombo"),
        FOLIAGE(Tab.NATURAL, "labFoliage", "foliageCombo"),
        SNOW(Tab.NATURAL, "labSnow", "snowCombo"),
        TUNDRA(Tab.NATURAL, "labTundra", "tundraCombo"),
        LAKES(Tab.WATER, "labLakes", "lakesCombo"),
        RIVERS(Tab.WATER, "labRivers", "riversCombo"),
        ICE(Tab.WATER, "labIce", "iceCombo");

        private final Tab tab;
        private final String label;
        private final String tip;

        Feature(Tab tab, String label, String tip) {
            this.tab = tab;
            this.label = label;
            this.tip = tip;
        }

        public Tab tab() {
            return tab;
        }

        public String label() {
            return trimColon(Messages.getString(KEY + label));
        }

        public String tip() {
            return Messages.getString(KEY + tip + ".toolTip");
        }
    }

    /** A titled group of the advanced view. */
    public enum Section {
        GENERAL(Tab.ELEVATION, "borderGeneral"), MOUNTAIN(Tab.ELEVATION, "borderMountain"),
        CRATER(Tab.ELEVATION, "borderCrater"), WOODS(Tab.NATURAL, "borderWoods"),
        JUNGLE(Tab.NATURAL, "borderJungle"), FOLIAGE(Tab.NATURAL, "borderFoliage"),
        SWAMP(Tab.NATURAL, "borderSwamp"), SAND(Tab.NATURAL, "borderSand"), SNOW(Tab.NATURAL, "borderSnow"),
        TUNDRA(Tab.NATURAL, "borderTundra"), ROUGH(Tab.NATURAL, "borderRough"),
        FIELDS(Tab.CIVILIZATION, "borderFields"), RUBBLE(Tab.CIVILIZATION, "borderRubble"),
        ROAD(Tab.CIVILIZATION, "borderRoad"), PAVEMENT(Tab.CIVILIZATION, "borderPavement"),
        FORTIFIED(Tab.CIVILIZATION, "borderFortified"), CITY(Tab.CIVILIZATION, "borderCity"),
        RIVER(Tab.WATER, "borderRiver"), ICE(Tab.WATER, "borderIce"), LAKES(Tab.WATER, "borderLakes"),
        EFFECTS(Tab.EFFECTS, "tabEffects");

        private final Tab tab;
        private final String key;

        Section(Tab tab, String key) {
            this.tab = tab;
            this.key = key;
        }

        public Tab tab() {
            return tab;
        }

        public String label() {
            return Messages.getString(KEY + key);
        }
    }

    /**
     * A number of the advanced view. A parameter whose label it shares with the one before is that one's maximum;
     * {@link #rows} pairs them.
     */
    public enum Param {
        ALGORITHM(Section.GENERAL, "labAlgorithmToUse", "elevationAlgorithmField", 0,
              BoardUtilities.getAmountElevationGenerators() - 1, MapSettings::getAlgorithmToUse),
        HILLINESS(Section.GENERAL, "labHilliness", "hillinessField", 0, 99, MapSettings::getHilliness),
        RANGE(Section.GENERAL, "labRange", "elevationRangeField", 0, POSITIVE_MAX, MapSettings::getRange),
        CLIFFS(Section.GENERAL, "labCliffs", "elevationCliffsField", 0, PERCENT, MapSettings::getCliffs),
        INVERSION(Section.GENERAL, "labProbInvert", "elevationInversionField", 0, 99, MapSettings::getProbInvert),
        PEAKS(Section.MOUNTAIN, "labMountainPeaks", "elevationPeaksField", 0, POSITIVE_MAX,
              MapSettings::getMountainPeaks),
        MOUNTAIN_HEIGHT_MIN(Section.MOUNTAIN, "labMountainHeight", "mountainHeightMinField", 0, POSITIVE_MAX,
              MapSettings::getMountainHeightMin),
        MOUNTAIN_HEIGHT_MAX(Section.MOUNTAIN, "labMountainHeight", "mountainHeightMaxField", 0, POSITIVE_MAX,
              MapSettings::getMountainHeightMax),
        MOUNTAIN_WIDTH_MIN(Section.MOUNTAIN, "labMountainWidth", "mountainWidthMinField", 0, POSITIVE_MAX,
              MapSettings::getMountainWidthMin),
        MOUNTAIN_WIDTH_MAX(Section.MOUNTAIN, "labMountainWidth", "mountainWidthMaxField", 0, POSITIVE_MAX,
              MapSettings::getMountainWidthMax),
        CRATER_CHANCE(Section.CRATER, "labProbCrater", "craterChanceField", 0, PERCENT, MapSettings::getProbCrater),
        CRATERS_MIN(Section.CRATER, "labMaxCraters", "craterAmountMinField", 0, POSITIVE_MAX,
              MapSettings::getMinCraters),
        CRATERS_MAX(Section.CRATER, "labMaxCraters", "craterAmountMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxCraters),
        CRATER_RADIUS_MIN(Section.CRATER, "labRadius", "craterSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinRadius),
        CRATER_RADIUS_MAX(Section.CRATER, "labRadius", "craterSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxRadius),
        WOODS_MIN(Section.WOODS, "labForestSpots", "woodsMinField", 0, POSITIVE_MAX, MapSettings::getMinForestSpots),
        WOODS_MAX(Section.WOODS, "labForestSpots", "woodsMaxField", 0, POSITIVE_MAX, MapSettings::getMaxForestSpots),
        WOODS_SIZE_MIN(Section.WOODS, "labForestSize", "woodsMinSizeField", 0, POSITIVE_MAX,
              MapSettings::getMinForestSize),
        WOODS_SIZE_MAX(Section.WOODS, "labForestSize", "woodsMaxSizeField", 0, POSITIVE_MAX,
              MapSettings::getMaxForestSize),
        WOODS_HEAVY(Section.WOODS, "labProbHeavy", "woodsHeavyChanceField", 0, PERCENT, MapSettings::getProbHeavy),
        WOODS_ULTRA(Section.WOODS, "labProbUltra", "woodsUltraChanceField", 0, PERCENT, MapSettings::getProbUltra),
        JUNGLE_MIN(Section.JUNGLE, "labJungleSpots", "jungleMinField", 0, POSITIVE_MAX,
              MapSettings::getMinJungleSpots),
        JUNGLE_MAX(Section.JUNGLE, "labJungleSpots", "jungleMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxJungleSpots),
        JUNGLE_SIZE_MIN(Section.JUNGLE, "labJungleSize", "jungleMinSizeField", 0, POSITIVE_MAX,
              MapSettings::getMinJungleSize),
        JUNGLE_SIZE_MAX(Section.JUNGLE, "labJungleSize", "jungleMaxSizeField", 0, POSITIVE_MAX,
              MapSettings::getMaxJungleSize),
        JUNGLE_HEAVY(Section.JUNGLE, "labProbHeavyJungle", "jungleHeavyChanceField", 0, PERCENT,
              MapSettings::getProbHeavyJungle),
        JUNGLE_ULTRA(Section.JUNGLE, "labProbUltraJungle", "jungleUltraChanceField", 0, PERCENT,
              MapSettings::getProbUltraJungle),
        FOLIAGE_MIN(Section.FOLIAGE, "labFoliageSpots", "foliageMinField", 0, POSITIVE_MAX,
              MapSettings::getMinFoliageSpots),
        FOLIAGE_MAX(Section.FOLIAGE, "labFoliageSpots", "foliageMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxFoliageSpots),
        FOLIAGE_SIZE_MIN(Section.FOLIAGE, "labFoliageSize", "foliageMinSizeField", 0, POSITIVE_MAX,
              MapSettings::getMinFoliageSize),
        FOLIAGE_SIZE_MAX(Section.FOLIAGE, "labFoliageSize", "foliageMaxSizeField", 0, POSITIVE_MAX,
              MapSettings::getMaxFoliageSize),
        FOLIAGE_HEAVY(Section.FOLIAGE, "labProbHeavy", "foliageHeavyChanceField", 0, PERCENT,
              MapSettings::getProbFoliageHeavy),
        SWAMP_MIN(Section.SWAMP, "labSwampSpots", "swampsMinField", 0, POSITIVE_MAX, MapSettings::getMinSwampSpots),
        SWAMP_MAX(Section.SWAMP, "labSwampSpots", "swampsMaxField", 0, POSITIVE_MAX, MapSettings::getMaxSwampSpots),
        SWAMP_SIZE_MIN(Section.SWAMP, "labSwampSize", "swampsMinSizeField", 0, POSITIVE_MAX,
              MapSettings::getMinSwampSize),
        SWAMP_SIZE_MAX(Section.SWAMP, "labSwampSize", "swampsMaxSizeField", 0, POSITIVE_MAX,
              MapSettings::getMaxSwampSize),
        SAND_MIN(Section.SAND, "labSandSpots", "sandsMinField", 0, POSITIVE_MAX, MapSettings::getMinSandSpots),
        SAND_MAX(Section.SAND, "labSandSpots", "sandsMaxField", 0, POSITIVE_MAX, MapSettings::getMaxSandSpots),
        SAND_SIZE_MIN(Section.SAND, "labSandSize", "sandsSizeMinField", 0, POSITIVE_MAX, MapSettings::getMinSandSize),
        SAND_SIZE_MAX(Section.SAND, "labSandSize", "sandsSizeMaxField", 0, POSITIVE_MAX, MapSettings::getMaxSandSize),
        SNOW_MIN(Section.SNOW, "labSnowSpots", "snowMinField", 0, POSITIVE_MAX, MapSettings::getMinSnowSpots),
        SNOW_MAX(Section.SNOW, "labSnowSpots", "snowMaxField", 0, POSITIVE_MAX, MapSettings::getMaxSnowSpots),
        SNOW_SIZE_MIN(Section.SNOW, "labSnowSize", "snowSizeMinField", 0, POSITIVE_MAX, MapSettings::getMinSnowSize),
        SNOW_SIZE_MAX(Section.SNOW, "labSnowSize", "snowSizeMaxField", 0, POSITIVE_MAX, MapSettings::getMaxSnowSize),
        TUNDRA_MIN(Section.TUNDRA, "labTundraSpots", "tundraMinField", 0, POSITIVE_MAX,
              MapSettings::getMinTundraSpots),
        TUNDRA_MAX(Section.TUNDRA, "labTundraSpots", "tundraMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxTundraSpots),
        TUNDRA_SIZE_MIN(Section.TUNDRA, "labTundraSize", "tundraSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinTundraSize),
        TUNDRA_SIZE_MAX(Section.TUNDRA, "labTundraSize", "tundraSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxTundraSize),
        ROUGH_MIN(Section.ROUGH, "labRoughSpots", "roughsMinField", 0, POSITIVE_MAX, MapSettings::getMinRoughSpots),
        ROUGH_MAX(Section.ROUGH, "labRoughSpots", "roughsMaxField", 0, POSITIVE_MAX, MapSettings::getMaxRoughSpots),
        ROUGH_SIZE_MIN(Section.ROUGH, "labRoughSize", "roughsMinSizeField", 0, POSITIVE_MAX,
              MapSettings::getMinRoughSize),
        ROUGH_SIZE_MAX(Section.ROUGH, "labRoughSize", "roughsMaxSizeField", 0, POSITIVE_MAX,
              MapSettings::getMaxRoughSize),
        ROUGH_ULTRA(Section.ROUGH, "labProbUltraRough", "roughsUltraChanceField", 0, PERCENT,
              MapSettings::getProbUltraRough),
        FIELDS_MIN(Section.FIELDS, "labPlantedFieldSpots", "fieldsMinField", 0, POSITIVE_MAX,
              MapSettings::getMinPlantedFieldSpots),
        FIELDS_MAX(Section.FIELDS, "labPlantedFieldSpots", "fieldsMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxPlantedFieldSpots),
        FIELDS_SIZE_MIN(Section.FIELDS, "labPlantedFieldSize", "fieldSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinPlantedFieldSize),
        FIELDS_SIZE_MAX(Section.FIELDS, "labPlantedFieldSize", "fieldSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxPlantedFieldSize),
        RUBBLE_MIN(Section.RUBBLE, "labRubbleSpots", "rubbleMinField", 0, POSITIVE_MAX,
              MapSettings::getMinRubbleSpots),
        RUBBLE_MAX(Section.RUBBLE, "labRubbleSpots", "rubbleMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxRubbleSpots),
        RUBBLE_SIZE_MIN(Section.RUBBLE, "labRubbleSize", "rubbleSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinRubbleSize),
        RUBBLE_SIZE_MAX(Section.RUBBLE, "labRubbleSize", "rubbleSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxRubbleSize),
        RUBBLE_ULTRA(Section.RUBBLE, "labProbUltraRubble", "rubbleUltraChanceField", 0, POSITIVE_MAX,
              MapSettings::getProbUltraRubble),
        ROAD_CHANCE(Section.ROAD, "labProbRoad", "roadChanceField", 0, PERCENT, MapSettings::getProbRoad),
        PAVEMENT_MIN(Section.PAVEMENT, "labPavementSpots", "pavementMinField", 0, POSITIVE_MAX,
              MapSettings::getMinPavementSpots),
        PAVEMENT_MAX(Section.PAVEMENT, "labPavementSpots", "pavementMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxPavementSpots),
        PAVEMENT_SIZE_MIN(Section.PAVEMENT, "labPavementSize", "pavementSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinPavementSize),
        PAVEMENT_SIZE_MAX(Section.PAVEMENT, "labPavementSize", "pavementSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxPavementSize),
        FORTIFIED_MIN(Section.FORTIFIED, "labFortifiedSpots", "fortifiedMinField", 0, POSITIVE_MAX,
              MapSettings::getMinFortifiedSpots),
        FORTIFIED_MAX(Section.FORTIFIED, "labFortifiedSpots", "fortifiedMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxFortifiedSpots),
        FORTIFIED_SIZE_MIN(Section.FORTIFIED, "labFortifiedSize", "fortifiedSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinFortifiedSize),
        FORTIFIED_SIZE_MAX(Section.FORTIFIED, "labFortifiedSize", "fortifiedSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxFortifiedSize),
        CITY_BLOCKS(Section.CITY, "labCityBlocks", "cityBlocks", 0, POSITIVE_MAX, MapSettings::getCityBlocks),
        CITY_CF_MIN(Section.CITY, "labCityCF", "cityCFMinField", 1, 150, MapSettings::getCityMinCF),
        CITY_CF_MAX(Section.CITY, "labCityCF", "cityCFMaxField", 1, 150, MapSettings::getCityMaxCF),
        CITY_FLOORS_MIN(Section.CITY, "labCityFloors", "cityFloorsMinField", 1, PERCENT,
              MapSettings::getCityMinFloors),
        CITY_FLOORS_MAX(Section.CITY, "labCityFloors", "cityFloorsMaxField", 1, PERCENT,
              MapSettings::getCityMaxFloors),
        CITY_DENSITY(Section.CITY, "labCityDensity", "cityDensityField", 1, PERCENT, MapSettings::getCityDensity),
        TOWN_SIZE(Section.CITY, "labTownSize", "townSizeField", 1, PERCENT, MapSettings::getTownSize),
        RIVER_CHANCE(Section.RIVER, "labProbRiver", "riverChanceField", 0, PERCENT, MapSettings::getProbRiver),
        ICE_MIN(Section.ICE, "labIceSpots", "iceMinField", 0, POSITIVE_MAX, MapSettings::getMinIceSpots),
        ICE_MAX(Section.ICE, "labIceSpots", "iceMaxField", 0, POSITIVE_MAX, MapSettings::getMaxIceSpots),
        ICE_SIZE_MIN(Section.ICE, "labIceSize", "iceSizeMinField", 0, POSITIVE_MAX, MapSettings::getMinIceSize),
        ICE_SIZE_MAX(Section.ICE, "labIceSize", "iceSizeMaxField", 0, POSITIVE_MAX, MapSettings::getMaxIceSize),
        FREEZE_CHANCE(Section.ICE, "labProbFreeze", "freezeChanceField", 0, PERCENT, MapSettings::getProbFreeze),
        LAKES_MIN(Section.LAKES, "labWaterSpots", "lakesMinField", 0, POSITIVE_MAX, MapSettings::getMinWaterSpots),
        LAKES_MAX(Section.LAKES, "labWaterSpots", "lakesMaxField", 0, POSITIVE_MAX, MapSettings::getMaxWaterSpots),
        LAKES_SIZE_MIN(Section.LAKES, "labWaterSize", "lakeSizeMinField", 0, POSITIVE_MAX,
              MapSettings::getMinWaterSize),
        LAKES_SIZE_MAX(Section.LAKES, "labWaterSize", "lakeSizeMaxField", 0, POSITIVE_MAX,
              MapSettings::getMaxWaterSize),
        DEEP_CHANCE(Section.LAKES, "labProbDeep", "deepChanceField", 0, PERCENT, MapSettings::getProbDeep),
        DROUGHT_CHANCE(Section.EFFECTS, "labProbDrought", "droughtChanceField", 0, PERCENT,
              MapSettings::getProbDrought),
        FIRE_CHANCE(Section.EFFECTS, "labProbFire", "fireChanceField", 0, PERCENT, MapSettings::getProbForestFire),
        FLOOD_CHANCE(Section.EFFECTS, "labProbFlood", "floodChanceField", 0, PERCENT, MapSettings::getProbFlood),
        FX_MOD(Section.EFFECTS, "labFxMod", "specialFxField", -POSITIVE_MAX, POSITIVE_MAX, MapSettings::getFxMod);

        private final Section section;
        private final String label;
        private final String tip;
        private final int min;
        private final int max;
        private final ToIntFunction<MapSettings> reader;

        Param(Section section, String label, String tip, int min, int max, ToIntFunction<MapSettings> reader) {
            this.section = section;
            this.label = label;
            this.tip = tip;
            this.min = min;
            this.max = max;
            this.reader = reader;
        }

        public Section section() {
            return section;
        }

        public String label() {
            return trimColon(Messages.getString(KEY + label));
        }

        public String tip() {
            return Messages.getString(KEY + tip + ".toolTip");
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        int read(MapSettings settings) {
            return reader.applyAsInt(settings);
        }

        /** The minimum this parameter is the maximum of, if it is one. */
        @Nullable
        Param lower() {
            return (ordinal() > 0) && values()[ordinal() - 1].label.equals(label) ? values()[ordinal() - 1] : null;
        }

        @Nullable
        Param upper() {
            Param[] all = values();
            return (ordinal() + 1 < all.length) && all[ordinal() + 1].label.equals(label) ? all[ordinal() + 1] : null;
        }
    }

    /** A row of the advanced view: one parameter, or a minimum and its maximum. */
    public record Row(Param first, @Nullable Param second) {
        public String label() {
            return first.label();
        }
    }

    /** The mountain styles of the advanced view, in the dialog's order: the code and the label key. */
    public enum MountainStyle {
        PLAIN(MapSettings.MOUNTAIN_PLAIN, "mountainPlain"),
        VOLCANO_EXTINCT(MapSettings.MOUNTAIN_VOLCANO_EXTINCT, "volcanoExtinct"),
        VOLCANO_DORMANT(MapSettings.MOUNTAIN_VOLCANO_DORMANT, "volcanoDormant"),
        VOLCANO_ACTIVE(MapSettings.MOUNTAIN_VOLCANO_ACTIVE, "volcanoActive"),
        SNOW_CAPPED(MapSettings.MOUNTAIN_SNOW_CAPPED, "mountainSnowcapped"),
        LAKE(MapSettings.MOUNTAIN_LAKE, "mountainLake");

        private final int code;
        private final String key;

        MountainStyle(int code, String key) {
            this.code = code;
            this.key = key;
        }

        public int code() {
            return code;
        }

        public String label() {
            return Messages.getString(KEY + key);
        }

        public static MountainStyle of(int code) {
            for (MountainStyle style : values()) {
                if (style.code == code) {
                    return style;
                }
            }
            return PLAIN;
        }
    }

    /** The city types both views offer, as the generator reads them. */
    public static final List<String> CITY_TYPES = List.of("NONE", "GRID", "HUB", "METRO", "TOWN");

    private final Consumer<MapSettings> send;
    private MapSettings settings;
    private final Map<Param, Integer> values = new EnumMap<>(Param.class);
    private boolean advanced;
    private int boardWidth;
    private int boardHeight;
    private String theme;

    /**
     * @param source the map settings to start from, copied
     * @param send   sends applied settings to the server
     */
    public RandomMapForm(MapSettings source, Consumer<MapSettings> send) {
        this.send = send;
        settings = MapSettings.getInstance(source);
        boardWidth = settings.getBoardWidth();
        boardHeight = settings.getBoardHeight();
        theme = settings.getTheme();
        readValues();
    }

    private void readValues() {
        for (Param param : Param.values()) {
            values.put(param, param.read(settings));
        }
    }

    private static String trimColon(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
    }

    /** The working copy; views must not change it. */
    public MapSettings settings() {
        return settings;
    }

    public boolean advanced() {
        return advanced;
    }

    public void advanced(boolean advanced) {
        this.advanced = advanced;
    }

    public static List<Feature> features(Tab tab) {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (feature.tab == tab) {
                result.add(feature);
            }
        }
        return result;
    }

    public static List<Section> sections(Tab tab) {
        List<Section> result = new ArrayList<>();
        for (Section section : Section.values()) {
            if (section.tab == tab) {
                result.add(section);
            }
        }
        return result;
    }

    /** The section's rows, a minimum and its maximum sharing one. */
    public static List<Row> rows(Section section) {
        List<Row> result = new ArrayList<>();
        for (Param param : Param.values()) {
            if ((param.section == section) && (param.lower() == null)) {
                result.add(new Row(param, param.upper()));
            }
        }
        return result;
    }

    // region Normal view

    /** The feature's level as the dialog reads it back from the settings. */
    public Level level(Feature feature) {
        MapSettings s = settings;
        return switch (feature) {
            case LAKES -> Level.of(s.getProbDeep() + 5 * s.getMinWaterSize() + 10 * s.getMinWaterSpots(), 20, 65,
                  100);
            case RIVERS -> percentLevel(s.getProbRiver());
            case ICE -> Level.of(s.getMinIceSize() + s.getMinIceSpots(), 2, 4, 6);
            case ROUGH -> Level.of(s.getMinRoughSize() + s.getMinRoughSpots(), 2, 4, 6);
            case SAND -> Level.of(s.getMinSandSize() + s.getMinSandSpots(), 2, 4, 6);
            case SWAMP -> Level.of(s.getMinSwampSize() + s.getMinSwampSpots(), 2, 4, 6);
            case WOODS -> woodsLevel(s.getMinForestSize(), s.getMinForestSpots(), s.getProbHeavy());
            case JUNGLE -> woodsLevel(s.getMinJungleSize(), s.getMinJungleSpots(), s.getProbHeavyJungle());
            case FOLIAGE -> woodsLevel(s.getMinFoliageSize(), s.getMinFoliageSpots(), s.getProbFoliageHeavy());
            case SNOW -> Level.of(s.getMinSnowSize() + s.getMinSnowSpots(), 2, 4, 6);
            case TUNDRA -> Level.of(s.getMinTundraSize() + s.getMinTundraSpots(), 2, 4, 6);
            case CLIFFS -> percentLevel(s.getCliffs());
            case CRATERS -> Level.of(s.getProbCrater(), 15, 40, 60);
            case HILLS -> percentLevel(s.getHilliness());
            case MOUNTAINS -> mountainLevel(s);
            case FORTIFIED -> Level.of(s.getMinFortifiedSize() + s.getMinFortifiedSpots(), 2, 3, 5);
            case PAVEMENT -> Level.of(s.getMinPavementSize() + s.getMinPavementSpots(), 2, 4, 6);
            case PLANTED_FIELDS -> Level.of(s.getMinPlantedFieldSize() + s.getMinPlantedFieldSpots(), 2, 4, 6);
            case ROADS -> percentLevel(s.getProbRoad());
            case RUBBLE -> Level.of(s.getMinRubbleSize() + s.getMinRubbleSpots(), 2, 4, 6);
        };
    }

    private static Level percentLevel(int percent) {
        return Level.of(percent, 15, 40, 60);
    }

    private static Level woodsLevel(int size, int spots, int heavy) {
        return Level.of(heavy + 5 * size + 6 * spots, 50, 65, 100);
    }

    private static Level mountainLevel(MapSettings s) {
        if (s.getMountainStyle() == MapSettings.MOUNTAIN_SNOW_CAPPED) {
            return Level.HIGH;
        } else if (s.getMountainWidthMin() >= 10) {
            return Level.HIGH;
        } else if (s.getMountainHeightMin() >= 8) {
            return Level.HIGH;
        } else if (s.getMountainHeightMin() >= 6) {
            return Level.MEDIUM;
        } else if (s.getMountainPeaks() < 1) {
            return Level.NONE;
        }
        return Level.LOW;
    }

    /**
     * Sets a feature to a level as the dialog's normal view does: its parameters take the level's preset and, as for
     * any normal view change, the algorithm, special effects and inversion are reset.
     */
    public void level(Feature feature, Level level) {
        int i = level.ordinal();
        MapSettings s = settings;
        switch (feature) {
            case HILLS -> s.setElevationParams(level.percent(), new int[] { 0, 3, 5, 8 }[i], 0);
            case CLIFFS -> s.setCliffParam(level.percent());
            case ROADS -> s.setRoadParam(level.percent());
            case RIVERS -> s.setRiverParam(level.percent());
            case MOUNTAINS -> {
                int[][] presets = { { 0, 7, 10, 4, 6 }, { 1, 7, 10, 4, 6 }, { 2, 7, 10, 6, 8 }, { 3, 9, 14, 8, 10 } };
                int[] p = presets[i];
                s.setMountainParams(p[0], p[1], p[2], p[3], p[4],
                      level == Level.HIGH ? MapSettings.MOUNTAIN_SNOW_CAPPED : 0);
            }
            case CRATERS -> {
                int[] p = new int[][] { { 0, 0, 0, 0, 0 }, { 25, 1, 3, 1, 3 }, { 50, 3, 8, 1, 5 },
                                        { 75, 6, 14, 3, 9 } }[i];
                s.setCraterParam(p[0], p[1], p[2], p[3], p[4]);
            }
            case ICE -> {
                int[] p = new int[][] { { 0, 0, 0, 0 }, { 2, 6, 1, 2 }, { 2, 5, 3, 8 }, { 5, 10, 3, 7 } }[i];
                s.setIceParams(p[0], p[1], p[2], p[3]);
            }
            case FORTIFIED -> {
                int[] p = new int[][] { { 0, 0, 0, 0 }, { 1, 2, 1, 2 }, { 2, 4, 2, 3 }, { 2, 4, 3, 6 } }[i];
                s.setFortifiedParams(p[0], p[1], p[2], p[3]);
            }
            case RUBBLE -> {
                int[] p = SPOTS[i];
                s.setRubbleParams(p[0], p[1], p[2], p[3], 0);
            }
            case ROUGH -> {
                int[] p = SPOTS[i];
                s.setRoughParams(p[0], p[1], p[2], p[3], 0);
            }
            case PAVEMENT -> s.setPavementParams(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2], SPOTS[i][3]);
            case SWAMP -> s.setSwampParams(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2], SPOTS[i][3]);
            case PLANTED_FIELDS -> s.setPlantedFieldParams(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2], SPOTS[i][3]);
            case SAND -> s.setSandParams(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2], SPOTS[i][3]);
            case SNOW -> s.setSnowParams(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2], SPOTS[i][3]);
            case TUNDRA -> s.setTundraParams(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2], SPOTS[i][3]);
            case LAKES -> {
                int[] p = new int[][] { { 0, 0, 0, 0, 0 }, { 1, 5, 1, 5, 20 }, { 2, 5, 6, 10, 30 },
                                        { 3, 6, 8, 15, 45 } }[i];
                s.setWaterParams(p[0], p[1], p[2], p[3], p[4]);
            }
            case WOODS -> {
                int[] p = FORESTS[i];
                s.setForestParams(p[0], p[1], p[2], p[3], p[4], p[5]);
            }
            case JUNGLE -> {
                int[] p = FORESTS[i];
                s.setJungleParams(p[0], p[1], p[2], p[3], p[4], p[5]);
            }
            case FOLIAGE -> {
                int[] p = FORESTS[i];
                s.setFoliageParams(p[0], p[1], p[2], p[3], p[4]);
            }
        }
        normalViewChanged();
    }

    /** The spot presets most features share: min and max spots, min and max size. */
    private static final int[][] SPOTS = { { 0, 0, 0, 0 }, { 2, 6, 1, 2 }, { 3, 8, 2, 5 }, { 5, 10, 3, 7 } };
    /** The forest presets: spots, size, heavy and ultra chances. */
    private static final int[][] FORESTS = { { 0, 0, 0, 0, 0, 0 }, { 3, 6, 3, 6, 20, 0 }, { 4, 8, 3, 10, 30, 0 },
                                             { 6, 10, 8, 13, 45, 5 } };

    private void normalViewChanged() {
        settings.setAlgorithmToUse(0);
        settings.setSpecialFX(0, 0, 0, 0, 0);
        settings.setInvertNegativeTerrain(0);
        readValues();
    }

    /** The city type, one of {@link #CITY_TYPES}. */
    public String cityType() {
        for (String type : CITY_TYPES) {
            if (type.equalsIgnoreCase(settings.getCityType())) {
                return type;
            }
        }
        return CITY_TYPES.get(0);
    }

    public static String cityTypeLabel(String type) {
        return "NONE".equals(type) ? Level.NONE.label() : type;
    }

    /** Sets the city type; the normal view also sets the city's preset, as the dialog's does. */
    public void cityType(String type) {
        if (advanced) {
            settings.setCityParams(value(Param.CITY_BLOCKS), type, value(Param.CITY_CF_MIN),
                  value(Param.CITY_CF_MAX), value(Param.CITY_FLOORS_MIN), value(Param.CITY_FLOORS_MAX),
                  value(Param.CITY_DENSITY), value(Param.TOWN_SIZE));
            return;
        }
        if ("NONE".equals(type)) {
            settings.setCityParams(0, type, 0, 0, 0, 0, 0, 0);
        } else {
            settings.setCityParams(16, type, 10, 100, 1, 6, 75, 60);
        }
        normalViewChanged();
    }

    // endregion

    // region Advanced view

    public int value(Param param) {
        return values.get(param);
    }

    /**
     * Sets an advanced parameter within its bounds; a minimum above its maximum raises that, a maximum below its
     * minimum lowers that, so the pair stays valid.
     */
    public void value(Param param, int value) {
        int bounded = Math.max(param.min, Math.min(param.max, value));
        values.put(param, bounded);
        Param upper = param.upper();
        Param lower = param.lower();
        if ((upper != null) && (values.get(upper) < bounded)) {
            values.put(upper, bounded);
        } else if ((lower != null) && (values.get(lower) > bounded)) {
            values.put(lower, bounded);
        }
        store();
    }

    public MountainStyle mountainStyle() {
        return MountainStyle.of(settings.getMountainStyle());
    }

    public void mountainStyle(MountainStyle style) {
        settings.setMountainParams(value(Param.PEAKS), value(Param.MOUNTAIN_WIDTH_MIN),
              value(Param.MOUNTAIN_WIDTH_MAX), value(Param.MOUNTAIN_HEIGHT_MIN), value(Param.MOUNTAIN_HEIGHT_MAX),
              style.code());
    }

    public boolean invertNegative() {
        return settings.getInvertNegativeTerrain() == 1;
    }

    public void invertNegative(boolean invert) {
        settings.setInvertNegativeTerrain(invert ? 1 : 0);
    }

    /** Writes every advanced number, as the dialog's advanced view reads back its fields. */
    private void store() {
        MapSettings s = settings;
        s.setAlgorithmToUse(value(Param.ALGORITHM));
        s.setElevationParams(value(Param.HILLINESS), value(Param.RANGE), value(Param.INVERSION));
        s.setCliffParam(value(Param.CLIFFS));
        s.setMountainParams(value(Param.PEAKS), value(Param.MOUNTAIN_WIDTH_MIN), value(Param.MOUNTAIN_WIDTH_MAX),
              value(Param.MOUNTAIN_HEIGHT_MIN), value(Param.MOUNTAIN_HEIGHT_MAX), s.getMountainStyle());
        s.setCraterParam(value(Param.CRATER_CHANCE), value(Param.CRATERS_MIN), value(Param.CRATERS_MAX),
              value(Param.CRATER_RADIUS_MIN), value(Param.CRATER_RADIUS_MAX));
        s.setRoughParams(value(Param.ROUGH_MIN), value(Param.ROUGH_MAX), value(Param.ROUGH_SIZE_MIN),
              value(Param.ROUGH_SIZE_MAX), value(Param.ROUGH_ULTRA));
        s.setSandParams(value(Param.SAND_MIN), value(Param.SAND_MAX), value(Param.SAND_SIZE_MIN),
              value(Param.SAND_SIZE_MAX));
        s.setSnowParams(value(Param.SNOW_MIN), value(Param.SNOW_MAX), value(Param.SNOW_SIZE_MIN),
              value(Param.SNOW_SIZE_MAX));
        s.setTundraParams(value(Param.TUNDRA_MIN), value(Param.TUNDRA_MAX), value(Param.TUNDRA_SIZE_MIN),
              value(Param.TUNDRA_SIZE_MAX));
        s.setSwampParams(value(Param.SWAMP_MIN), value(Param.SWAMP_MAX), value(Param.SWAMP_SIZE_MIN),
              value(Param.SWAMP_SIZE_MAX));
        s.setForestParams(value(Param.WOODS_MIN), value(Param.WOODS_MAX), value(Param.WOODS_SIZE_MIN),
              value(Param.WOODS_SIZE_MAX), value(Param.WOODS_HEAVY), value(Param.WOODS_ULTRA));
        s.setJungleParams(value(Param.JUNGLE_MIN), value(Param.JUNGLE_MAX), value(Param.JUNGLE_SIZE_MIN),
              value(Param.JUNGLE_SIZE_MAX), value(Param.JUNGLE_HEAVY), value(Param.JUNGLE_ULTRA));
        s.setFoliageParams(value(Param.FOLIAGE_MIN), value(Param.FOLIAGE_MAX), value(Param.FOLIAGE_SIZE_MIN),
              value(Param.FOLIAGE_SIZE_MAX), value(Param.FOLIAGE_HEAVY));
        s.setPlantedFieldParams(value(Param.FIELDS_MIN), value(Param.FIELDS_MAX), value(Param.FIELDS_SIZE_MIN),
              value(Param.FIELDS_SIZE_MAX));
        s.setFortifiedParams(value(Param.FORTIFIED_MIN), value(Param.FORTIFIED_MAX),
              value(Param.FORTIFIED_SIZE_MIN), value(Param.FORTIFIED_SIZE_MAX));
        s.setPavementParams(value(Param.PAVEMENT_MIN), value(Param.PAVEMENT_MAX), value(Param.PAVEMENT_SIZE_MIN),
              value(Param.PAVEMENT_SIZE_MAX));
        s.setRubbleParams(value(Param.RUBBLE_MIN), value(Param.RUBBLE_MAX), value(Param.RUBBLE_SIZE_MIN),
              value(Param.RUBBLE_SIZE_MAX), value(Param.RUBBLE_ULTRA));
        s.setRoadParam(value(Param.ROAD_CHANCE));
        s.setCityParams(value(Param.CITY_BLOCKS), s.getCityType(), value(Param.CITY_CF_MIN),
              value(Param.CITY_CF_MAX), value(Param.CITY_FLOORS_MIN), value(Param.CITY_FLOORS_MAX),
              value(Param.CITY_DENSITY), value(Param.TOWN_SIZE));
        s.setWaterParams(value(Param.LAKES_MIN), value(Param.LAKES_MAX), value(Param.LAKES_SIZE_MIN),
              value(Param.LAKES_SIZE_MAX), value(Param.DEEP_CHANCE));
        s.setRiverParam(value(Param.RIVER_CHANCE));
        s.setIceParams(value(Param.ICE_MIN), value(Param.ICE_MAX), value(Param.ICE_SIZE_MIN),
              value(Param.ICE_SIZE_MAX));
        s.setSpecialFX(value(Param.FX_MOD), value(Param.FIRE_CHANCE), value(Param.FREEZE_CHANCE),
              value(Param.FLOOD_CHANCE), value(Param.DROUGHT_CHANCE));
    }

    /**
     * The first advanced field out of its bounds or minimum above its maximum, as a message, or {@code null}; a file
     * can hold such values.
     */
    @Nullable
    public String problem() {
        if ((boardWidth <= 0) || (boardHeight <= 0)) {
            return Messages.getString(KEY + "BoardSizeWarn");
        }
        for (Param param : Param.values()) {
            if (param == Param.FX_MOD) {
                continue;
            }
            int value = value(param);
            boolean onlyPositive = param.max == POSITIVE_MAX;
            if ((value < param.min) || (!onlyPositive && (value > param.max))) {
                return param.label() + ": " + value;
            }
            Param lower = param.lower();
            if ((lower != null) && (value(lower) > value)) {
                return Messages.getString("Lobby.map.random.minAboveMax", param.label());
            }
        }
        return null;
    }

    // endregion

    // region Board size and theme

    public int boardWidth() {
        return boardWidth;
    }

    public int boardHeight() {
        return boardHeight;
    }

    public void boardSize(int width, int height) {
        boardWidth = width;
        boardHeight = height;
    }

    public String theme() {
        return theme;
    }

    public void theme(String theme) {
        this.theme = theme;
    }

    // endregion

    /**
     * Sends the settings with the board size and theme, as the dialog's OK.
     *
     * @return {@code false} when a {@link #problem} keeps them from being sent
     */
    public boolean apply() {
        if (problem() != null) {
            return false;
        }
        settings.setBoardSize(boardWidth, boardHeight);
        settings.setTheme(theme);
        send.accept(MapSettings.getInstance(settings));
        return true;
    }

    /** The folder map generator files start in. */
    public static File settingsFolder() {
        return new File(Configuration.dataDir(), SETTINGS_FOLDER);
    }

    /** The file with the XML extension, added unless it has it. */
    public static File withExtension(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? file
              : new File(file.getPath() + EXTENSION);
    }

    /**
     * Reads map generator settings, keeping the map's size, boards and their themes and level offsets, as the
     * dialog's Load does.
     */
    public void load(File file) throws IOException {
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getPath());
        }
        MapSettings loaded;
        try (InputStream in = new FileInputStream(file)) {
            loaded = MapSettings.getInstance(in);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
        if (loaded == null) {
            throw new IOException("Not map settings: " + file);
        }
        loaded.setMapSize(settings.getMapWidth(), settings.getMapHeight());
        loaded.setBoardsSelectedVector(settings.getBoardsSelectedVector());
        for (int i = 0; i < settings.getBoardsSelectedVector().size(); i++) {
            loaded.setBoardTheme(i, settings.getBoardTheme(i));
            loaded.setBoardLevelOffset(i, settings.getBoardLevelOffset(i));
        }
        settings = loaded;
        theme = settings.getTheme();
        readValues();
    }

    /**
     * Applies the settings and writes them, as the dialog's Apply and Save.
     *
     * @return {@code false} when a {@link #problem} kept them from being applied and saved
     */
    public boolean applyAndSave(File file) throws IOException {
        if (!apply()) {
            return false;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            settings.save(out);
        }
        return true;
    }
}
