/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.PreferenceManager;

/**
 * The lobby's battlefield as its column sums it up: the map, the planetary conditions, the rules that matter most, the
 * victory conditions and the auto-resolve settings. Read on the Swing thread with the rest of a
 * {@link LobbySnapshot}.
 *
 * @param mapLine      the map's size in hexes, its sheets when more than one, and whether it is rolled at launch
 * @param conditions   the light, then the weather, wind, fog, atmosphere, gravity and temperature that aren't
 *                     ordinary
 * @param space        whether the map is in space, where the Swing lobby offers no planetary conditions, random map
 *                     or map setup files
 * @param generable    whether the server can build the battlefield now: no surprise board and no unset sheet
 * @param rolled       whether any sheet is generated, random, a surprise or unset, so the map is rolled at launch
 * @param rulesLine    the year and the tech level
 * @param notableRules the short names of the rules that are on and change how the battle plays
 * @param victory      the conditions that end the battle
 * @param autoResolve  the auto-resolve settings, and whether the lobby offers them
 */
public record LobbyBattlefield(String mapLine, String conditions, boolean space, boolean generable,
      boolean rolled, String rulesLine, List<String> notableRules, String victory, AutoResolve autoResolve) {

    /** The battlefield before the client has its game. */
    public static final LobbyBattlefield EMPTY = new LobbyBattlefield("", "", false, false, false, "", List.of(), "",
          AutoResolve.DEFAULT);

    private static final String SEPARATOR = " \u00b7 ";

    /** A rule that changes how the battle plays, and the message key of its short name. */
    private record NotableRule(String option, String key) { }

    private static final List<NotableRule> NOTABLE_RULES = List.of(
          new NotableRule(OptionsConstants.ADVANCED_DOUBLE_BLIND, "Lobby.battlefield.rule.doubleBlind"),
          new NotableRule(OptionsConstants.BASE_REAL_BLIND_DROP, "Lobby.battlefield.rule.realBlindDrop"),
          new NotableRule(OptionsConstants.BASE_BLIND_DROP, "Lobby.battlefield.rule.blindDrop"),
          new NotableRule(OptionsConstants.ADVANCED_TAC_OPS_SENSORS, "Lobby.battlefield.rule.sensors"),
          new NotableRule(OptionsConstants.RPG_PILOT_ADVANTAGES, "Lobby.battlefield.rule.abilities"),
          new NotableRule(OptionsConstants.EDGE, "Lobby.battlefield.rule.edge"));

    public LobbyBattlefield {
        Objects.requireNonNull(mapLine);
        Objects.requireNonNull(conditions);
        Objects.requireNonNull(rulesLine);
        notableRules = List.copyOf(notableRules);
        Objects.requireNonNull(victory);
        Objects.requireNonNull(autoResolve);
    }

    /**
     * The Swing lobby's auto-resolve settings.
     *
     * @param shown      whether the lobby offers auto-resolve: the client settings show it and no real blind drop
     *                   hides the forces it would fight with
     * @param chances    whether the chances are worked out and shown before the battle is resolved; the runs and
     *                   parallelism count only then
     * @param runs       how many battles the chances are worked out from, 1 to {@link #MAX_RUNS}
     * @param threads    how many run at once, 1 to {@code maxThreads}
     * @param maxThreads the most that may run at once
     */
    public record AutoResolve(boolean shown, boolean chances, int runs, int threads, int maxThreads) {
        /** The most battles the chances are worked out from. */
        public static final int MAX_RUNS = 1000;
        /** The Swing lobby's settings as it starts. */
        public static final AutoResolve DEFAULT = new AutoResolve(false, false, 10,
              Runtime.getRuntime().availableProcessors(),
              Math.max(2, Runtime.getRuntime().availableProcessors() * 2));

        /** @return these settings, shown or not */
        public AutoResolve shown(boolean value) {
            return new AutoResolve(value, chances, runs, threads, maxThreads);
        }

        /** @return the number of runs within its bounds */
        public static int clampRuns(int runs) {
            return Math.clamp(runs, 1, MAX_RUNS);
        }

        /** @return the number of threads within 1 and {@code maxThreads} */
        public int clampThreads(int value) {
            return Math.clamp(value, 1, Math.max(1, maxThreads));
        }
    }

    /**
     * Reads the battlefield. Call it on the thread that changes the game (the Swing thread).
     *
     * @param autoResolve the Swing lobby's auto-resolve settings; whether they are shown is read here
     */
    public static LobbyBattlefield of(@Nullable GameOptions options, @Nullable PlanetaryConditions conditions,
          @Nullable MapSettings settings, AutoResolve autoResolve) {
        boolean shown = options != null && autoResolveShown(options);

        return new LobbyBattlefield(settings == null ? "" : mapLine(settings),
              conditions == null ? "" : conditions(conditions),
              settings != null && settings.getMedium() == MapSettings.MEDIUM_SPACE,
              settings != null && generable(settings), settings != null && rolled(settings),
              options == null ? "" : rulesLine(options), options == null ? List.of() : notableRules(options),
              options == null ? "" : victory(options), autoResolve.shown(shown));
    }

    /** Whether the lobby offers auto-resolve, as the Swing lobby's auto-resolve panel shows: the client settings. */
    public static boolean autoResolveShown(GameOptions options) {
        return autoResolveShown(PreferenceManager.getClientPreferences().getShowAutoResolvePanel(), options);
    }

    static boolean autoResolveShown(boolean clientShows, GameOptions options) {
        return clientShows && !options.booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP);
    }

    /** The map's size in hexes, how many sheets it is made of when more than one, and whether any is rolled. */
    static String mapLine(MapSettings settings) {
        List<String> parts = new ArrayList<>();
        if (settings.getMedium() == MapSettings.MEDIUM_SPACE) {
            parts.add(Messages.getString("Lobby.battlefield.space"));
        } else if (settings.getMedium() == MapSettings.MEDIUM_ATMOSPHERE) {
            parts.add(Messages.getString("Lobby.battlefield.atmosphere"));
        }
        parts.add(Messages.getString("Lobby.battlefield.size",
              String.valueOf(settings.getBoardWidth() * settings.getMapWidth()),
              String.valueOf(settings.getBoardHeight() * settings.getMapHeight())));
        int sheets = settings.getMapWidth() * settings.getMapHeight();
        if (sheets > 1) {
            parts.add(Messages.getString("Lobby.battlefield.sheets", String.valueOf(sheets)));
        }
        if (rolled(settings)) {
            parts.add(Messages.getString("Lobby.battlefield.rolled"));
        }
        return String.join(SEPARATOR, parts);
    }

    /** Whether any sheet is generated, random, a surprise or not yet chosen, so the battle's map is rolled at launch. */
    static boolean rolled(MapSettings settings) {
        for (String board : settings.getBoardsSelectedVector()) {
            if (board == null || board.startsWith(MapSettings.BOARD_GENERATED)
                  || board.startsWith(MapSettings.BOARD_RANDOM) || board.startsWith(MapSettings.BOARD_SURPRISE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the server can build the battlefield, as the Swing lobby's Generate battlefield button has it: a
     * surprise board would give the surprise away, and an unset sheet can't be built.
     */
    public static boolean generable(MapSettings settings) {
        return settings.getBoardsSelectedVector().stream()
              .noneMatch(board -> board == null || board.startsWith(MapSettings.BOARD_SURPRISE));
    }

    /** The light, then whatever of the weather, wind, fog, air, gravity and temperature isn't ordinary. */
    static String conditions(PlanetaryConditions conditions) {
        List<String> parts = new ArrayList<>();
        parts.add(conditions.getLight().toString());
        if (!conditions.getWeather().isClear()) {
            parts.add(conditions.getWeather().toString());
        }
        if (!conditions.getWind().isCalm()) {
            parts.add(conditions.getWind().toString());
        }
        if (!conditions.getFog().isFogNone()) {
            parts.add(conditions.getFog().toString());
        }
        if (!conditions.getAtmosphere().isStandard()) {
            parts.add(conditions.getAtmosphere().toString());
        }
        if (conditions.getGravity() != 1.0f) {
            parts.add(Messages.getString("Lobby.battlefield.gravity", String.valueOf(conditions.getGravity())));
        }
        if (conditions.isExtremeTemperature()) {
            parts.add(Messages.getString("Lobby.battlefield.temperature",
                  String.valueOf(conditions.getTemperature())));
        }
        return String.join(SEPARATOR, parts);
    }

    /** The year and the tech level. */
    static String rulesLine(GameOptions options) {
        IOption year = options.getOption(OptionsConstants.ALLOWED_YEAR);
        IOption techLevel = options.getOption(OptionsConstants.ALLOWED_TECH_LEVEL);
        List<String> parts = new ArrayList<>();
        if (year != null) {
            parts.add(String.valueOf(year.intValue()));
        }
        if (techLevel != null) {
            parts.add(Messages.getString("Lobby.battlefield.techLevel", techLevel.stringValue()));
        }
        return String.join(SEPARATOR, parts);
    }

    /** The short names of the rules that are on and change how the battle plays; real blind drop hides blind drop. */
    static List<String> notableRules(GameOptions options) {
        List<String> names = new ArrayList<>();
        for (NotableRule rule : NOTABLE_RULES) {
            if (rule.option().equals(OptionsConstants.BASE_BLIND_DROP)
                  && options.booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP)) {
                continue;
            }
            if (options.booleanOption(rule.option())) {
                names.add(Messages.getString(rule.key()));
            }
        }
        return names;
    }

    /** The conditions that end the battle, or the last side standing when none is set. */
    static String victory(GameOptions options) {
        List<String> parts = new ArrayList<>();
        if (options.booleanOption(OptionsConstants.VICTORY_USE_BV_DESTROYED)) {
            parts.add(Messages.getString("Lobby.battlefield.victory.bvDestroyed",
                  number(options, OptionsConstants.VICTORY_BV_DESTROYED_PERCENT)));
        }
        if (options.booleanOption(OptionsConstants.VICTORY_USE_BV_RATIO)) {
            parts.add(Messages.getString("Lobby.battlefield.victory.bvRatio",
                  number(options, OptionsConstants.VICTORY_BV_RATIO_PERCENT)));
        }
        if (options.booleanOption(OptionsConstants.VICTORY_USE_KILL_COUNT)) {
            parts.add(Messages.getString("Lobby.battlefield.victory.kills",
                  number(options, OptionsConstants.VICTORY_GAME_KILL_COUNT)));
        }
        if (options.booleanOption(OptionsConstants.VICTORY_COMMANDER_KILLED)) {
            parts.add(Messages.getString("Lobby.battlefield.victory.commander"));
        }
        if (options.booleanOption(OptionsConstants.VICTORY_USE_OBJECTIVES)) {
            parts.add(Messages.getString("Lobby.battlefield.victory.objectives"));
        }
        if (options.booleanOption(OptionsConstants.VICTORY_USE_SENSOR_CHECK)) {
            parts.add(Messages.getString("Lobby.battlefield.victory.sensors"));
        }
        String ending = parts.isEmpty() ? Messages.getString("Lobby.battlefield.victory.lastStanding")
              : String.join(SEPARATOR, parts);
        IOption needed = options.getOption(OptionsConstants.VICTORY_ACHIEVE_CONDITIONS);
        if (parts.size() > 1 && needed != null && needed.intValue() > 1 && needed.intValue() < parts.size()) {
            ending = Messages.getString("Lobby.battlefield.victory.anyOf", String.valueOf(needed.intValue()), ending);
        }
        if (options.booleanOption(OptionsConstants.VICTORY_USE_GAME_TURN_LIMIT)) {
            ending += SEPARATOR + Messages.getString("Lobby.battlefield.victory.turns",
                  number(options, OptionsConstants.VICTORY_GAME_TURN_LIMIT));
        }
        return ending;
    }

    private static String number(GameOptions options, String name) {
        IOption option = options.getOption(name);
        return option == null ? "?" : String.valueOf(option.intValue());
    }
}
