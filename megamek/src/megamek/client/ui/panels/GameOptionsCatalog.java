/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels;

import static megamek.client.ui.Messages.CLIENT_BUNDLE;
import static megamek.common.internationalization.I18n.getTextAt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.ui.settings.SettingsTextProvider;
import megamek.common.MMRandom;
import megamek.common.TechConstants;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import megamek.common.weapons.bayWeapons.capital.CapitalMissileBayWeapon;
import megamek.logging.MMLogger;

/**
 * What the game options editors know about the options beyond the options themselves, shared by the Swing
 * {@link GameOptionsPane} and the lobby's own: which are unofficial or legacy rules, their concise names and details,
 * the section texts, the bounds and choices of the number and text options, which options only apply while others
 * are on, and which need the Total Warfare rules system. Toolkit-free.
 */
public final class GameOptionsCatalog {
    private static final MMLogger LOGGER = MMLogger.create(GameOptionsCatalog.class);
    private static final SettingsTextProvider TEXT = SettingsTextProvider.megaMek();
    private static final int MAX_BRIDGE_CF = 1_000;
    private static final int MAX_GHOST_TARGET_PENALTY = 10;
    private static final int MAX_WOODS_BURN_DOWN_CF = 100;
    private static final int MAX_ARTILLERY_AUTOHIT_HEXES = 100;
    private static final int MAX_ARTILLERY_MAP_AREA_HEXES = 100_000;
    private static final int MAX_EXTERNAL_HEAT = 100;
    private static final int MAX_LANCE_SIZE = 100;

    /** The options classified as unofficial rules. */
    public static final Set<String> UNOFFICIAL_OPTIONS = Set.of(
          OptionsConstants.BASE_INFANTRY_DAMAGE_HEAT,
          OptionsConstants.ADVANCED_COMBAT_FULL_ROTOR_HITS,
          OptionsConstants.ADVANCED_SINGLE_BLIND_BOTS,
          OptionsConstants.UNOFFICIAL_BRIDGE_REPAIR_ENGINEERS,
          OptionsConstants.ADVANCED_INCLUSIVE_SENSOR_RANGE,
          OptionsConstants.ADVANCED_SENSORS_DETECT_ALL,
          OptionsConstants.ADVANCED_MAG_SCAN_NO_HILLS,
          OptionsConstants.ADVANCED_WOODS_BURN_DOWN,
          OptionsConstants.ADVANCED_NO_IGNITE_CLEAR,
          OptionsConstants.ADVANCED_EXTREME_TEMPERATURE_SURVIVAL,
          OptionsConstants.ADVANCED_ARMED_MEKWARRIORS,
          OptionsConstants.ADVANCED_PILOTS_VISUAL_RANGE_ONE,
          OptionsConstants.ADVANCED_PILOTS_CANNOT_SPOT,
          OptionsConstants.ADVANCED_METAL_CONTENT,
          OptionsConstants.ADVANCED_BA_GRAB_BARS,
          OptionsConstants.ADVANCED_ALTERNATE_MASC,
          OptionsConstants.ADVANCED_ALTERNATE_MASC_ENHANCED,
          OptionsConstants.UNOFFICIAL_BACKHOE_CLEARS_RUBBLE,
          OptionsConstants.ADVANCED_COMBAT_HOT_LOAD_IN_GAME,
          OptionsConstants.ADVANCED_COMBAT_KIND_RAPID_AC,
          OptionsConstants.ADVANCED_COMBAT_MULTI_USE_AMS,
          OptionsConstants.ADVANCED_COMBAT_NO_TAC,
          OptionsConstants.ADVANCED_COMBAT_VTOL_STRAFING,
          OptionsConstants.ADVANCED_COMBAT_VEHICLES_SAFE_FROM_INFERNOS,
          OptionsConstants.ADVANCED_COMBAT_PROTOMEKS_SAFE_FROM_INFERNOS,
          OptionsConstants.ADVANCED_COMBAT_INDIRECT_ALWAYS_POSSIBLE,
          OptionsConstants.ADVANCED_COMBAT_INCREASED_AC_DMG,
          OptionsConstants.ADVANCED_COMBAT_UNJAM_UAC,
          OptionsConstants.ADVANCED_COMBAT_TAC_OPS_UAC_TWO_ROLLS,
          OptionsConstants.ADVANCED_COMBAT_CLUBS_PUNCH,
          OptionsConstants.ADVANCED_COMBAT_ON_MAP_PREDESIGNATE,
          OptionsConstants.ADVANCED_COMBAT_MAP_AREA_PREDESIGNATE,
          OptionsConstants.ADVANCED_COMBAT_NUM_HEXES_PREDESIGNATE,
          OptionsConstants.ADVANCED_COMBAT_FOREST_FIRES_NO_SMOKE,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_UNOFF_NO_IMMOBILE_VEHICLES,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_EJECTED_PILOTS_FLEE,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_AUTO_ABANDON_UNIT,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_NO_HOVER_CHARGE,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_NO_PRE_MOVE_VIBRA,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_FALLS_END_MOVEMENT,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_PSR_JUMP_HEAVY_WOODS,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_NO_NIGHT_MOVE_PEN,
          OptionsConstants.ADVANCED_AERO_RULES_STRATOPS_AA_FIRE,
          OptionsConstants.ADVANCED_AERO_RULES_STRATOPS_BEARINGS_ONLY_VELOCITY,
          OptionsConstants.ADVANCED_AERO_RULES_RETURN_FLYOVER,
          OptionsConstants.ADVANCED_AERO_RULES_CLIMB_OUT,
          OptionsConstants.ADVANCED_AERO_RULES_AA_MOVE_MOD,
          OptionsConstants.ADVANCED_AERO_RULES_SINGLE_NO_CAP,
          OptionsConstants.ADVANCED_AERO_RULES_ALLOW_LARGE_SQUADRONS,
          OptionsConstants.ADVANCED_AERO_RULES_AERO_SANITY,
          OptionsConstants.ADVANCED_AERO_RULES_AERO_ARTILLERY_MUNITIONS,
          OptionsConstants.ADVANCED_AERO_RULES_CRASHED_DROPSHIPS_SURVIVE,
          OptionsConstants.ADVANCED_AERO_RULES_EXPANDED_KF_DRIVE_DAMAGE,
          OptionsConstants.UNOFFICIAL_ADV_ATMOSPHERIC_CONTROL,
          OptionsConstants.INIT_INF_MOVE_EVEN,
          OptionsConstants.INIT_INF_DEPLOY_EVEN,
          OptionsConstants.INIT_INF_MOVE_LATER,
          OptionsConstants.INIT_INF_MOVE_MULTI,
          OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN,
          OptionsConstants.INIT_PROTOMEKS_MOVE_LATER,
          OptionsConstants.INIT_PROTOMEKS_MOVE_MULTI,
          OptionsConstants.INIT_INF_PROTO_MOVE_MULTI,
          OptionsConstants.INIT_SIMULTANEOUS_DEPLOYMENT,
          OptionsConstants.INIT_SIMULTANEOUS_TARGETING,
          OptionsConstants.INIT_SIMULTANEOUS_FIRING,
          OptionsConstants.INIT_SIMULTANEOUS_PHYSICAL,
          OptionsConstants.RPG_INDIVIDUAL_INITIATIVE,
          OptionsConstants.RPG_COMMAND_INIT,
          OptionsConstants.RPG_TOUGHNESS,
          OptionsConstants.RPG_CONDITIONAL_EJECTION,
          OptionsConstants.RPG_BEGIN_SHUTDOWN,
          OptionsConstants.ADVANCED_COMBAT_MAX_EXTERNAL_HEAT,
          OptionsConstants.ADVANCED_COMBAT_CASE_PILOT_DAMAGE,
          OptionsConstants.ADVANCED_COMBAT_NO_FORCED_PRIMARY_TARGETS);

    /** The options classified as legacy rules, kept for superseded rules. */
    public static final Set<String> LEGACY_OPTIONS = Set.of(
          OptionsConstants.ADVANCED_GHOST_TARGET_MODE,
          OptionsConstants.ADVANCED_GHOST_TARGET_MAX,
          OptionsConstants.ADVANCED_ASSAULT_DROP,
          OptionsConstants.ADVANCED_PARATROOPERS,
          OptionsConstants.ADVANCED_MAX_TECH_MOVEMENT_MODS,
          OptionsConstants.ADVANCED_GROUND_MOVEMENT_AUTO_ABANDON_UNIT,
          OptionsConstants.RPG_RPG_GUNNERY);

    /** Options that only apply while one of their controlling boolean options is on, mapped to those. */
    private static final Map<String, List<String>> EDITABLE_WHEN_ANY = new LinkedHashMap<>();

    static {
        VictoryOptionLayout.dependencies().forEach((dependent, master) ->
              EDITABLE_WHEN_ANY.put(dependent, List.of(master)));
        EDITABLE_WHEN_ANY.put(OptionsConstants.ADVANCED_WOODS_BURN_DOWN_AMOUNT,
              List.of(OptionsConstants.ADVANCED_WOODS_BURN_DOWN));
        EDITABLE_WHEN_ANY.put(OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT_NUMBER,
              List.of(OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT));
        EDITABLE_WHEN_ANY.put(OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT_NUMBER,
              List.of(OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT));
        EDITABLE_WHEN_ANY.put(OptionsConstants.ADVANCED_ALTERNATE_MASC_ENHANCED,
              List.of(OptionsConstants.ADVANCED_ALTERNATE_MASC));
        EDITABLE_WHEN_ANY.put(OptionsConstants.ADVANCED_AERO_RULES_CLIMB_OUT,
              List.of(OptionsConstants.ADVANCED_AERO_RULES_RETURN_FLYOVER));
        EDITABLE_WHEN_ANY.put(OptionsConstants.INIT_INF_PROTO_MOVE_MULTI,
              List.of(OptionsConstants.INIT_INF_MOVE_MULTI, OptionsConstants.INIT_PROTOMEKS_MOVE_MULTI));
        EDITABLE_WHEN_ANY.put(OptionsConstants.ADVANCED_PARATROOPERS, List.of(OptionsConstants.ADVANCED_ASSAULT_DROP));
    }

    private GameOptionsCatalog() {
    }

    public static boolean isUnofficial(String optionName) {
        return UNOFFICIAL_OPTIONS.contains(optionName);
    }

    public static boolean isLegacy(String optionName) {
        return LEGACY_OPTIONS.contains(optionName);
    }

    /** Whether the option has a concise name of its own for the settings pages. */
    public static boolean hasShortLabel(IOption option) {
        return TEXT.containsKey(shortLabelKey(option));
    }

    /** The option's name on the settings pages: its concise name if any, without the category markers. */
    public static String displayName(IOption option) {
        String displayName = hasShortLabel(option) ? TEXT.getText(shortLabelKey(option)) : option.getDisplayableName();
        if (isUnofficial(option.getName()) || isLegacy(option.getName())) {
            displayName = stripCategoryMarker(displayName);
        }
        return displayName.replaceFirst("(?i)^\\(Legacy(?:\\s+MaxTech)?\\)\\s*", "")
              .replaceFirst("(?i)\\s*\\(Legacy(?:\\s+only)?\\)\\s*", " ")
              .replaceFirst("(?i)\\s*\\[Legacy]\\s*", " ")
              .trim();
    }

    static String stripCategoryMarker(String displayName) {
        return displayName.replaceFirst("^\\([^)]*\\)\\s*", "")
              .replaceFirst("(?i)^(?:Unofficial|Legacy):\\s*", "");
    }

    private static String shortLabelKey(IOption option) {
        return "GameOptionsDialog.option." + option.getName() + ".shortName";
    }

    /** Whether the option has a longer explanation of its own. */
    public static boolean hasDetails(IOption option) {
        return TEXT.containsKey(detailsKey(option));
    }

    /** The option's longer explanation, or null without one. */
    public static @Nullable String details(IOption option) {
        return hasDetails(option) ? TEXT.getText(detailsKey(option)) : null;
    }

    private static String detailsKey(IOption option) {
        return "GameOptionsDialog.option." + option.getName() + ".details";
    }

    /** Whether the option carries the important marker: it has a concise name or details. */
    public static boolean isImportant(IOption option) {
        return hasShortLabel(option) || hasDetails(option);
    }

    public static String sectionTitle(String sectionId) {
        return getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.section." + sectionId + ".title");
    }

    public static String sectionSummary(String sectionId) {
        return getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.section." + sectionId + ".summary");
    }

    /**
     * The range a whole-number option accepts, as {@code {min, max}} with {@link Integer#MAX_VALUE} for no upper bound,
     * or null when it takes any number.
     */
    public static @Nullable int[] integerBounds(String optionName) {
        // the victory numbers share their bounds with the lobby's Victory Conditions dialog
        int[] victoryBounds = VictoryOptionLayout.boundsFor(optionName);
        if (victoryBounds != null) {
            return victoryBounds;
        }
        return switch (optionName) {
            case OptionsConstants.BASE_TURN_TIMER_TARGETING,
                 OptionsConstants.BASE_TURN_TIMER_MOVEMENT,
                 OptionsConstants.BASE_TURN_TIMER_FIRING,
                 OptionsConstants.BASE_TURN_TIMER_PHYSICAL -> new int[] { 0, Integer.MAX_VALUE };
            case OptionsConstants.BASE_DUMPING_FROM_ROUND,
                 OptionsConstants.BASE_MAX_NUMBER_ROUND_SAVES -> new int[] { 1, Integer.MAX_VALUE };
            case OptionsConstants.BASE_BRIDGE_CF -> new int[] { 0, MAX_BRIDGE_CF };
            case OptionsConstants.ADVANCED_GHOST_TARGET_MAX -> new int[] { 0, MAX_GHOST_TARGET_PENALTY };
            case OptionsConstants.ADVANCED_WOODS_BURN_DOWN_AMOUNT -> new int[] { 1, MAX_WOODS_BURN_DOWN_CF };
            case OptionsConstants.ADVANCED_COMBAT_NUM_HEXES_PREDESIGNATE -> new int[] { 0, MAX_ARTILLERY_AUTOHIT_HEXES };
            case OptionsConstants.ADVANCED_COMBAT_MAP_AREA_PREDESIGNATE -> new int[] { 1, MAX_ARTILLERY_MAP_AREA_HEXES };
            case OptionsConstants.ADVANCED_COMBAT_MAX_EXTERNAL_HEAT -> new int[] { 0, MAX_EXTERNAL_HEAT };
            case OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT_NUMBER,
                 OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT_NUMBER,
                 OptionsConstants.INIT_INF_PROTO_MOVE_MULTI -> new int[] { 1, MAX_LANCE_SIZE };
            case OptionsConstants.ADVANCED_AERO_RULES_STRATOPS_BEARINGS_ONLY_VELOCITY -> new int[] {
                  CapitalMissileBayWeapon.CAPITAL_MISSILE_MIN_VELOCITY,
                  CapitalMissileBayWeapon.CAPITAL_MISSILE_MAX_VELOCITY };
            default -> null;
        };
    }

    /** The named values of a whole-number option chosen from a list, in order, or an empty map. */
    public static Map<Integer, String> integerChoices(String optionName) {
        if (!OptionsConstants.BASE_RNG_TYPE.equals(optionName)) {
            return Map.of();
        }
        Map<Integer, String> choices = new LinkedHashMap<>();
        choices.put(MMRandom.R_SUN, getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.rngType.sunRandom"));
        choices.put(MMRandom.R_CRYPTO, getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.rngType.cryptoRandom"));
        choices.put(MMRandom.R_POOL36, getTextAt(CLIENT_BUNDLE, "GameOptionsDialog.rngType.pool36Random"));
        return Collections.unmodifiableMap(choices);
    }

    /** The values a choice option offers, in order, or an empty list for one this does not know. */
    public static List<String> choices(String optionName) {
        return switch (optionName) {
            case OptionsConstants.ALLOWED_TECH_LEVEL -> List.of(TechConstants.T_SIMPLE_NAMES);
            case OptionsConstants.ADVANCED_NEURAL_INTERFACE_MODE -> List.of(
                  OptionsConstants.NEURAL_INTERFACE_MODE_OFF,
                  OptionsConstants.NEURAL_INTERFACE_MODE_PILOT_ONLY,
                  OptionsConstants.NEURAL_INTERFACE_MODE_FULL_TRACKING);
            case OptionsConstants.GAME_MASTER_VOTE_THRESHOLD -> List.of(
                  OptionsConstants.GAME_MASTER_VOTE_UNANIMOUS,
                  OptionsConstants.GAME_MASTER_VOTE_MAJORITY);
            case OptionsConstants.RULES_SYSTEM -> List.of(OptionsConstants.RULES_CORE, OptionsConstants.RULES_TW);
            case OptionsConstants.ADVANCED_GHOST_TARGET_MODE -> List.of(
                  OptionsConstants.GHOST_TARGET_MODE_LEGACY,
                  OptionsConstants.GHOST_TARGET_MODE_STANDARD);
            default -> List.of();
        };
    }

    /** Options that only apply while any of their controlling boolean options is on, mapped to those. */
    public static Map<String, List<String>> editableWhenAny() {
        return Collections.unmodifiableMap(EDITABLE_WHEN_ANY);
    }

    /** The rules system named by {@code value}, Core for anything else. */
    public static String normalizeRulesSystem(@Nullable Object value) {
        if (value instanceof String rulesSystem
              && (OptionsConstants.RULES_CORE.equals(rulesSystem) || OptionsConstants.RULES_TW.equals(rulesSystem))) {
            return rulesSystem;
        }
        LOGGER.debug("Unknown rules system '{}'; using '{}'.", value, OptionsConstants.RULES_CORE);
        return OptionsConstants.RULES_CORE;
    }

    /** The message key of the guidance shown under the rules system choice for {@code value}. */
    public static String rulesSystemDescriptionKey(@Nullable Object value) {
        if (OptionsConstants.RULES_TW.equals(value)) {
            return "GameOptionsDialog.page.landing.rulesSystem.totalWarfare";
        }
        if (!OptionsConstants.RULES_CORE.equals(value)) {
            LOGGER.debug("Unknown rules system '{}'; showing Core rules guidance.", value);
        }
        return "GameOptionsDialog.page.landing.rulesSystem.core";
    }

    /** The text of the settings message {@code key}. */
    public static String text(String key) {
        return TEXT.getText(key);
    }
}
