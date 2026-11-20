/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static megamek.common.units.Terrains.BLDG_CF;
import static megamek.common.units.Terrains.BLDG_ELEV;
import static megamek.common.units.Terrains.BRIDGE;
import static megamek.common.units.Terrains.BRIDGE_CF;
import static megamek.common.units.Terrains.BRIDGE_ELEV;
import static megamek.common.units.Terrains.BUILDING;
import static megamek.common.units.Terrains.FUEL_TANK;
import static megamek.common.units.Terrains.FUEL_TANK_CF;
import static megamek.common.units.Terrains.FUEL_TANK_MAGN;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import jakarta.annotation.Nullable;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BehaviorSettingsFactory;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.PrincessException;
import megamek.client.bot.princess.PriorityTargetChoices;
import megamek.client.generator.RandomCallsignGenerator;
import megamek.client.ui.Messages;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.internationalization.I18n;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.common.units.IBuilding;
import megamek.logging.MMLogger;
import megamek.server.ServerBoardHelper;

/**
 * A bot's setup without a toolkit, as the Swing {@code BotConfigDialog} edits it: the bot's name (only for a new bot,
 * and not one a player has), its AI (chosen for a new bot, else locked), the behaviour sliders, combat posture and
 * switches, forced withdrawal and auto flee with their edges, the priority unit targets ({@link BotTargetPicker}) and
 * the strategic hex targets. The behaviour presets list the configuration the dialog opened with, the stored presets
 * and the other local bots' configurations; a stored preset loads only its preset fields (sliders, posture and
 * switches), while the opening configuration and another bot's load everything. The current preset fields can be
 * saved to the chosen stored preset or as a new one, and a stored preset deleted, through {@link Presets}.
 * <p>
 * {@link #behavior} makes the settings OK hands the bot, as the Swing dialog's do: a new {@link BehaviorSettings} with
 * the configuration's description. The name is checked as it is typed rather than when OK is pressed, and a blank
 * name is refused too.
 */
public final class BotSetupForm {
    private static final MMLogger LOGGER = MMLogger.create(BotSetupForm.class);
    /** The name a new bot starts with when no player has it. */
    public static final String DEFAULT_NAME = "Princess";

    /** A behaviour slider, in the Swing dialog's order. */
    public enum Slider {
        BRAVERY(0, 10, "braverySliderTitle", "braverySliderMin", "braverySliderMax", "braveryTooltip", false,
              BehaviorSettings::getBraveryIndex, BehaviorSettings::setBraveryIndex),
        SELF_PRESERVATION(0, 10, "selfPreservationSliderTitle", "selfPreservationSliderMin",
              "selfPreservationSliderMax", "selfPreservationTooltip", false,
              BehaviorSettings::getSelfPreservationIndex, BehaviorSettings::setSelfPreservationIndex),
        AGGRESSION(0, 10, "aggressionSliderTitle", "aggressionSliderMin", "aggressionSliderMax", "aggressionTooltip",
              false, BehaviorSettings::getHyperAggressionIndex, BehaviorSettings::setHyperAggressionIndex),
        MUTUAL_SUPPORT(0, 10, "mutualSupportSliderTitle", "mutualSupportSliderMin", "mutualSupportSliderMax",
              "mutualSupportToolTip", false, BehaviorSettings::getMutualSupportIndex,
              BehaviorSettings::setMutualSupportIndex),
        FALL_SHAME(0, 10, "fallShameSliderTitle", "fallShameSliderMin", "fallShameSliderMax", "fallShameToolTip",
              false, BehaviorSettings::getFallShameIndex, BehaviorSettings::setFallShameIndex),
        FACING_ENEMIES(BehaviorSettings.MIN_NUMBER_OF_ENEMIES_TO_CONSIDER_FACING,
              BehaviorSettings.MAX_NUMBER_OF_ENEMIES_TO_CONSIDER_FACING, "numberOfEnemiesToConsiderFacingTitle",
              "numberOfEnemiesToConsiderFacingMin", "numberOfEnemiesToConsiderFacingMax",
              "numberOfEnemiesToConsiderFacingToolTip", false, BehaviorSettings::getNumberOfEnemiesToConsiderFacing,
              BehaviorSettings::setNumberOfEnemiesToConsiderFacing),
        FACING_TOLERANCE(BehaviorSettings.MIN_ALLOW_FACING_TOLERANCE, BehaviorSettings.MAX_ALLOW_FACING_TOLERANCE,
              "allowFacingToleranceTitle", "allowFacingToleranceMin", "allowFacingToleranceMax",
              "allowFacingToleranceToolTip", false, BehaviorSettings::getAllowFacingTolerance,
              BehaviorSettings::setAllowFacingTolerance),
        ANTI_CROWDING(0, 10, "antiCrowdingTitle", "antiCrowdingSliderMin", "antiCrowdingSliderMax",
              "antiCrowdingToolTip", true, BehaviorSettings::getAntiCrowding, BehaviorSettings::setAntiCrowding),
        FAVOR_HIGHER_TMM(0, 10, "favorHigherTMMTitle", "favorHigherTMMSliderMin", "favorHigherTMMSliderMax",
              "favorHigherTMMToolTip", true, BehaviorSettings::getFavorHigherTMM, BehaviorSettings::setFavorHigherTMM);

        private final int min;
        private final int max;
        private final String title;
        private final String minText;
        private final String maxText;
        private final String tip;
        private final boolean experimental;
        private final ToIntFunction<BehaviorSettings> read;
        private final BiConsumer<BehaviorSettings, Integer> write;

        Slider(int min, int max, String title, String minText, String maxText, String tip, boolean experimental,
              ToIntFunction<BehaviorSettings> read, BiConsumer<BehaviorSettings, Integer> write) {
            this.min = min;
            this.max = max;
            this.title = "BotConfigDialog." + title;
            this.minText = "BotConfigDialog." + minText;
            this.maxText = "BotConfigDialog." + maxText;
            this.tip = "BotConfigDialog." + tip;
            this.experimental = experimental;
            this.read = read;
            this.write = write;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        /** Whether it shows only with the experimental bot features on, as the Swing dialog has it. */
        public boolean experimental() {
            return experimental;
        }

        /** Its title with the value, such as "Bravery 5". */
        public String title(int value) {
            return Messages.getString(title, value);
        }

        public String minText() {
            return this == FACING_ENEMIES ? Messages.getString(minText, min) : Messages.getString(minText);
        }

        public String maxText() {
            return this == FACING_ENEMIES ? Messages.getString(maxText, max) : Messages.getString(maxText);
        }

        /** The tooltip, which may hold HTML. */
        public String tip() {
            return this == FACING_TOLERANCE ? Messages.getString(tip, max) : Messages.getString(tip);
        }

        /** Its value in {@code settings}. */
        public int of(BehaviorSettings settings) {
            return read.applyAsInt(settings);
        }

        private int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** An on/off setting; the preset ones are stored in presets. */
    public enum Flag {
        EXCLUSIVE_MUTUAL_SUPPORT("exclusiveMutualSupportCheck", "exclusiveMutualSupportCheckToolTip", true, false,
              BehaviorSettings::isExclusiveMutualSupport, BehaviorSettings::setExclusiveMutualSupport),
        PIRATE("iAmAPirateCheck", "iAmAPirateCheckToolTip", true, false, BehaviorSettings::iAmAPirate,
              BehaviorSettings::setIAmAPirate),
        IGNORE_DAMAGE_OUTPUT("ignoreDamageOutput", "ignoreDamageOutputToolTip", true, false,
              BehaviorSettings::isIgnoreDamageOutput, BehaviorSettings::setIgnoreDamageOutput),
        EXPERIMENTAL("experimentalCheck", "experimentalCheckToolTip", true, true, BehaviorSettings::isExperimental,
              BehaviorSettings::setExperimental),
        FORCED_WITHDRAWAL("forcedWithdrawalCheck", "forcedWithdrawalTooltip", false, false,
              BehaviorSettings::isForcedWithdrawal, BehaviorSettings::setForcedWithdrawal),
        AUTO_FLEE("autoFleeCheck", "autoFleeTooltip", false, false, BehaviorSettings::shouldAutoFlee,
              BehaviorSettings::setAutoFlee);

        private final String label;
        private final String tip;
        private final boolean preset;
        private final boolean experimental;
        private final Predicate<BehaviorSettings> read;
        private final BiConsumer<BehaviorSettings, Boolean> write;

        Flag(String label, String tip, boolean preset, boolean experimental, Predicate<BehaviorSettings> read,
              BiConsumer<BehaviorSettings, Boolean> write) {
            this.label = "BotConfigDialog." + label;
            this.tip = "BotConfigDialog." + tip;
            this.preset = preset;
            this.experimental = experimental;
            this.read = read;
            this.write = write;
        }

        public String label() {
            return Messages.getString(label);
        }

        /** The tooltip, which may hold HTML. */
        public String tip() {
            return Messages.getString(tip);
        }

        /** Whether presets store it. */
        public boolean preset() {
            return preset;
        }

        /** Whether it shows only with the experimental bot features on. */
        public boolean experimental() {
            return experimental;
        }
    }

    /** Where a preset comes from. */
    public enum PresetKind {
        /** The configuration the dialog opened with. */
        PREVIOUS,
        /** A stored behaviour preset. */
        STORED,
        /** Another local bot's configuration. */
        BOT
    }

    /**
     * A personality the setup offers first, as a card: a friendly name and a line about it over a stored preset, whose
     * name stays as it is because campaigns and scenarios refer to it. Only the personalities whose presets are stored
     * show.
     */
    public enum Personality {
        BALANCED(BehaviorSettingsFactory.DEFAULT_BEHAVIOR_DESCRIPTION),
        CAUTIOUS(BehaviorSettingsFactory.COWARDLY_BEHAVIOR_DESCRIPTION),
        SNIPER("Sniper (pPT)"),
        SKIRMISHER("Skirmisher (pPT)"),
        BRAWLER("Brawler (pPT)"),
        STRIKER("Striker (pPT)"),
        BERSERKER(BehaviorSettingsFactory.BERSERK_BEHAVIOR_DESCRIPTION),
        PIRATE(BehaviorSettingsFactory.PIRATE_BEHAVIOR_DESCRIPTION);

        private final String preset;

        Personality(String preset) {
            this.preset = preset;
        }

        /** The stored preset's name. */
        public String preset() {
            return preset;
        }

        public String label() {
            return Messages.getString("Lobby.bot.personality." + key());
        }

        /** One line about how the bot fights. */
        public String blurb() {
            return Messages.getString("Lobby.bot.personality." + key() + ".blurb");
        }

        /** Its name for actors and message keys, such as "striker". */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The personality of the preset named {@code presetName}, if there is one. */
        public static @Nullable Personality of(@Nullable String presetName) {
            for (Personality each : values()) {
                if (each.preset.equals(presetName == null ? null : presetName.strip())) {
                    return each;
                }
            }
            return null;
        }
    }

    /** What the bot does about leaving the board: the forced withdrawal and auto flee switches as one choice. */
    public enum Retreat {
        /** Neither: every unit fights on. */
        FIGHT,
        /** Forced withdrawal: crippled units head for the retreat edge. */
        WITHDRAW,
        /** Auto flee: every unit heads for the edge and leaves. */
        LEAVE
    }

    /** A line of the presets list: its name (the stored preset's or bot's) and kind. */
    public record Preset(String name, PresetKind kind) {
        public String label() {
            return switch (kind) {
                case PREVIOUS -> Messages.getString("BotConfigDialog.previousConfig");
                case STORED -> shownName(name);
                case BOT -> name;
            };
        }

        /**
         * A stored preset's name as players read it: a shipped preset's tidy name, such as "LRM Missile Boat" for
         * "LRM Missile Boat (pPT)", or else the name as it was saved. The stored name stays, as campaigns refer to it.
         */
        public static String shownName(String stored) {
            String key = "Lobby.bot.presetLabel." + stored.strip().toLowerCase(Locale.ROOT)
                  .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            return Messages.keyExists(key) ? Messages.getString(key) : stored;
        }
    }

    /** The stored behaviour presets. */
    public interface Presets {
        /** The presets' names, sorted. */
        List<String> names();

        @Nullable BehaviorSettings get(String name);

        /** Stores {@code preset} under its description, replacing one of the same name, and writes the file. */
        void save(BehaviorSettings preset);

        /** Deletes a preset and writes the file. */
        void remove(String name);

        /** The presets of {@link BehaviorSettingsFactory}, written to the user's behaviour file. */
        static Presets factory() {
            BehaviorSettingsFactory factory = BehaviorSettingsFactory.getInstance();
            return new Presets() {
                @Override
                public List<String> names() {
                    return Arrays.asList(factory.getBehaviorNames());
                }

                @Override
                public @Nullable BehaviorSettings get(String name) {
                    return factory.getBehavior(name);
                }

                @Override
                public void save(BehaviorSettings preset) {
                    factory.addBehavior(preset);
                    factory.saveBehaviorSettings(false);
                }

                @Override
                public void remove(String name) {
                    factory.removeBehavior(name);
                    factory.saveBehaviorSettings(false);
                }
            };
        }
    }

    /** A strategic hex target and how it reads: valid when the hex holds a building, fuel tank or bridge. */
    public record HexTarget(Coords coords, String text, boolean valid) { }

    private final boolean newBot;
    private String name;
    private final Set<String> playerNames;
    private final List<AIType> types;
    private final @Nullable AIType lockedType;
    private AIType type;
    private final boolean experimentalFeatures;
    private final EnumMap<Slider, Integer> sliders = new EnumMap<>(Slider.class);
    private final EnumMap<Flag, Boolean> flags = new EnumMap<>(Flag.class);
    private CombatPosture posture = CombatPosture.ATTACK;
    private CardinalEdge retreatEdge = CardinalEdge.NORTH;
    private CardinalEdge fleeEdge = CardinalEdge.NORTH;
    private String description = "";
    private final BehaviorSettings previous;
    private final Presets presets;
    private final Map<String, BehaviorSettings> otherBots;
    private final List<Preset> presetList = new ArrayList<>();
    private @Nullable Preset selected;
    private @Nullable BehaviorSettings chosenPreset;
    private @Nullable Personality basedOn;
    private final @Nullable Board board;
    private final List<Coords> hexTargets = new ArrayList<>();
    private final BotTargetPicker picker;

    /**
     * @param fixedName    the existing bot's name, or null for a new bot
     * @param freeName     the name a new bot starts with
     * @param playerNames  the names of the game's players, which a new bot may not take
     * @param behavior     the configuration to start from, listed as the previous configuration
     * @param lockedType   the AI of an existing bot, or null to choose one
     * @param caspar       whether the CASPAR AI is offered to a new bot
     * @param experimental whether the experimental bot features show
     * @param otherBots    the other local bots' configurations by name, copies
     * @param board        the board the hex targets are read against, or null when there is none
     * @param picker       the unit targets, starting from {@code behavior}'s
     */
    public BotSetupForm(@Nullable String fixedName, String freeName, Set<String> playerNames, BehaviorSettings behavior,
          @Nullable AIType lockedType, boolean caspar, boolean experimental, Presets presets,
          Map<String, BehaviorSettings> otherBots, @Nullable Board board, BotTargetPicker picker) {
        newBot = fixedName == null;
        name = newBot ? freeName : fixedName;
        this.playerNames = Set.copyOf(playerNames);
        this.lockedType = lockedType;
        List<AIType> offered = new ArrayList<>();
        for (AIType each : AIType.values()) {
            // A locked type shows even when the preference that offers it is off: it is what the seat is
            if (each != AIType.CASPAR || caspar || each == lockedType) {
                offered.add(each);
            }
        }
        types = List.copyOf(offered);
        type = lockedType != null ? lockedType : AIType.PRINCESS;
        experimentalFeatures = experimental;
        this.presets = presets;
        this.otherBots = new LinkedHashMap<>(otherBots);
        this.board = board;
        this.picker = picker;
        previous = behavior;
        loadAll(behavior);
        basedOn = shown(Personality.of(description));
        refreshPresets();
    }

    /**
     * Swing thread: the setup of the local bot {@code fixedName} (null for a new bot), read from the client as the
     * Swing dialog reads it.
     */
    public static BotSetupForm of(Client client, @Nullable String fixedName, BehaviorSettings behavior,
          @Nullable AIType lockedType) {
        Game game = client.getGame();
        Set<String> names = new HashSet<>();
        Player bot = null;
        for (Player player : game.getPlayersList()) {
            names.add(player.getName());
            if (player.getName().equals(fixedName)) {
                bot = player;
            }
        }
        Map<String, BehaviorSettings> others = new TreeMap<>();
        for (Map.Entry<String, AbstractClient> entry : client.getBots().entrySet()) {
            if (!entry.getKey().equals(fixedName) && entry.getValue() instanceof BotClient botClient) {
                try {
                    others.put(entry.getKey(), botClient.getBehaviorSettings().getCopy());
                } catch (PrincessException | RuntimeException failure) {
                    LOGGER.error(failure, "The bot setup could not copy the settings of {}", entry.getKey());
                }
            }
        }
        Map<Integer, String> unitNames = new HashMap<>();
        for (Entity entity : game.getEntitiesVector()) {
            unitNames.put(entity.getId(), entity.getShortNameRaw());
        }
        Map<Integer, Integer> priorities = new HashMap<>();
        for (int unitId : behavior.getPriorityUnitTargets()) {
            priorities.put(unitId, behavior.getTargetPriority(unitId));
        }
        BotTargetPicker picker = new BotTargetPicker(true, PriorityTargetChoices.targetsFor(game, bot), unitNames,
              priorities);
        Board board;
        try {
            board = game.getPhase().isLounge()
                  ? ServerBoardHelper.getPossibleGameBoard(client.getMapSettings(), true) : client.getBoard();
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The bot setup could not read the board for the hex targets");
            board = null;
        }
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        return new BotSetupForm(fixedName, fixedName == null ? freeName(names) : fixedName, names, behavior,
              lockedType, preferences.getUseCASPAR(), preferences.getEnableExperimentalBotFeatures(),
              Presets.factory(), others, board, picker);
    }

    /** "Princess", or when a player has it, "Princess-" and a random callsign, as the Swing dialog names a new bot. */
    public static String freeName(Set<String> taken) {
        String name = DEFAULT_NAME;
        int tries = 0;
        while (taken.contains(name) && tries < 1000) {
            tries++;
            name = DEFAULT_NAME + "-" + I18n.normalizeTextToASCII(RandomCallsignGenerator.getInstance().generate(),
                  true).replace(" ", "-");
        }
        return name;
    }

    // ---- Name and AI ----

    /** Whether the bot is new, so its name and AI may be chosen. */
    public boolean newBot() {
        return newBot;
    }

    public String name() {
        return name;
    }

    public void name(String value) {
        if (newBot) {
            name = value == null ? "" : value;
        }
    }

    /** The message key of what is wrong with the name, if anything. */
    public Optional<String> nameError() {
        if (!newBot) {
            return Optional.empty();
        }
        if (name.isBlank()) {
            return Optional.of("Lobby.bot.nameBlank");
        }
        return playerNames.contains(name) ? Optional.of("ChatLounge.AlertExistsBot.message") : Optional.empty();
    }

    public boolean valid() {
        return nameError().isEmpty();
    }

    /** The AIs to choose from. */
    public List<AIType> types() {
        return types;
    }

    /** Whether the AI is fixed: an existing bot cannot change its kind. */
    public boolean typeLocked() {
        return lockedType != null;
    }

    public AIType type() {
        return type;
    }

    public void type(AIType value) {
        if (lockedType == null && types.contains(value)) {
            type = value;
        }
    }

    // ---- Behaviour ----

    /** Whether the experimental sliders and switch show. */
    public boolean experimentalFeatures() {
        return experimentalFeatures;
    }

    /** The sliders that show. */
    public List<Slider> sliders() {
        return Arrays.stream(Slider.values()).filter(slider -> experimentalFeatures || !slider.experimental())
              .toList();
    }

    public int value(Slider slider) {
        return sliders.get(slider);
    }

    public void value(Slider slider, int value) {
        sliders.put(slider, slider.clamp(value));
    }

    /** The preset switches that show. */
    public List<Flag> presetFlags() {
        return Arrays.stream(Flag.values()).filter(flag -> flag.preset()
              && (experimentalFeatures || !flag.experimental())).toList();
    }

    public boolean flag(Flag flag) {
        return flags.get(flag);
    }

    public void flag(Flag flag, boolean on) {
        flags.put(flag, on);
    }

    public CombatPosture posture() {
        return posture;
    }

    public void posture(CombatPosture value) {
        posture = value;
    }

    /** The edges a bot may withdraw or flee to: every edge but none. */
    public static List<CardinalEdge> edges() {
        return Arrays.stream(CardinalEdge.values()).filter(edge -> edge != CardinalEdge.NONE).toList();
    }

    public CardinalEdge retreatEdge() {
        return retreatEdge;
    }

    public void retreatEdge(CardinalEdge edge) {
        if (edge != CardinalEdge.NONE) {
            retreatEdge = edge;
        }
    }

    public CardinalEdge fleeEdge() {
        return fleeEdge;
    }

    public void fleeEdge(CardinalEdge edge) {
        if (edge != CardinalEdge.NONE) {
            fleeEdge = edge;
        }
    }

    /** The retreat switches read as one choice: auto flee first, as it moves every unit. */
    public Retreat retreat() {
        if (flags.get(Flag.AUTO_FLEE)) {
            return Retreat.LEAVE;
        }
        return flags.get(Flag.FORCED_WITHDRAWAL) ? Retreat.WITHDRAW : Retreat.FIGHT;
    }

    /** Sets both retreat switches for {@code retreat}, keeping the edge shown ({@link #retreatTo}). */
    public void retreat(Retreat retreat) {
        CardinalEdge edge = retreatTo();
        flags.put(Flag.FORCED_WITHDRAWAL, retreat != Retreat.FIGHT);
        flags.put(Flag.AUTO_FLEE, retreat == Retreat.LEAVE);
        retreatTo(edge);
    }

    /** The edge the retreat choice heads for: the flee edge when the bot leaves, else the retreat edge. */
    public CardinalEdge retreatTo() {
        return retreat() == Retreat.LEAVE ? fleeEdge : retreatEdge;
    }

    /** Sets the edge the retreat choice heads for; leaving sets the flee edge too, so crippled units go the same way. */
    public void retreatTo(CardinalEdge edge) {
        if (retreat() == Retreat.LEAVE) {
            fleeEdge(edge);
        }
        retreatEdge(edge);
    }

    // ---- Personalities ----

    /** The personalities whose presets are stored, in their order. */
    public List<Personality> personalities() {
        List<String> names = presets.names();
        List<Personality> shown = new ArrayList<>();
        for (Personality each : Personality.values()) {
            if (names.contains(each.preset())) {
                shown.add(each);
            }
        }
        return shown;
    }

    /** The stored preset of {@code personality}, or null when it is gone. */
    public @Nullable BehaviorSettings preset(Personality personality) {
        return presets.get(personality.preset());
    }

    /**
     * Loads {@code personality}'s preset as {@link #select} loads a stored one, but keeps the combat posture, which is
     * chosen beside the personalities.
     */
    public void choose(Personality personality) {
        CombatPosture kept = posture;
        select(new Preset(personality.preset(), PresetKind.STORED));
        posture = kept;
    }

    /** The personality whose preset the settings match, the combat posture aside, if any. */
    public Optional<Personality> personality() {
        for (Personality each : personalities()) {
            BehaviorSettings stored = presets.get(each.preset());
            if (stored != null && sameStyle(stored)) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }

    /**
     * The personality the settings started from: the last one loaded, or the one the opening configuration is named
     * after; null when they came from elsewhere. Reset goes back to it.
     */
    public @Nullable Personality basedOn() {
        return basedOn;
    }

    /** Whether the settings started from a personality and no longer match it. */
    public boolean modified() {
        return basedOn != null && personality().filter(basedOn::equals).isEmpty();
    }

    /** {@code personality} when its preset is stored, so it has a card; else null. */
    private @Nullable Personality shown(@Nullable Personality personality) {
        return personality != null && presets.get(personality.preset()) != null ? personality : null;
    }

    /** Whether the preset fields but the posture match {@code stored}. */
    private boolean sameStyle(BehaviorSettings stored) {
        for (Slider slider : Slider.values()) {
            if (slider.clamp(slider.read.applyAsInt(stored)) != sliders.get(slider)) {
                return false;
            }
        }
        for (Flag flag : Flag.values()) {
            if (flag.preset() && flag.read.test(stored) != flags.get(flag)) {
                return false;
            }
        }
        return true;
    }

    // ---- Presets ----

    /** The presets list: the previous configuration, the stored presets, then the other local bots. */
    public List<Preset> presets() {
        return List.copyOf(presetList);
    }

    /** The chosen line of the presets list, or null. */
    public @Nullable Preset selected() {
        return selected;
    }

    /**
     * Loads a line of the presets list: the previous configuration or another bot's loads all its settings, a stored
     * preset only its preset fields.
     */
    public void select(Preset preset) {
        selected = preset;
        switch (preset.kind()) {
            case PREVIOUS -> {
                chosenPreset = null;
                loadAll(previous);
            }
            case BOT -> {
                chosenPreset = null;
                BehaviorSettings other = otherBots.get(preset.name());
                if (other != null) {
                    loadAll(other);
                }
            }
            case STORED -> {
                BehaviorSettings stored = presets.get(preset.name());
                chosenPreset = stored;
                BehaviorSettings loaded = stored == null ? new BehaviorSettings() : stored;
                description = loaded.getDescription();
                loadPresetFields(loaded);
            }
        }
        basedOn = shown(Personality.of(description));
    }

    /** Whether a stored preset is chosen and the preset fields now differ from it, which Save writes. */
    public boolean changedPreset() {
        BehaviorSettings chosen = chosenPreset;
        if (chosen == null) {
            return false;
        }
        for (Slider slider : Slider.values()) {
            if (slider.read.applyAsInt(chosen) != sliders.get(slider)) {
                return true;
            }
        }
        for (Flag flag : Flag.values()) {
            if (flag.preset() && flag.read.test(chosen) != flags.get(flag)) {
                return true;
            }
        }
        return chosen.getCombatPosture() != posture;
    }

    /** Swing thread: writes the preset fields to the chosen stored preset. */
    public void savePreset() {
        Preset chosen = selected;
        if (chosen != null && chosen.kind() == PresetKind.STORED && writePreset(chosen.name())) {
            refreshPresets();
            chosenPreset = presets.get(chosen.name());
        }
    }

    /** The message key of what is wrong with a new preset's name, if anything; a blank name is just not ready. */
    public Optional<String> newPresetError(String presetName) {
        return presetName != null && presets.names().contains(presetName.strip())
              ? Optional.of("BotConfigDialog.saveNewTaken") : Optional.empty();
    }

    /** Whether a new preset may be saved under this name. */
    public boolean canSaveNew(String presetName) {
        return presetName != null && !presetName.isBlank() && newPresetError(presetName).isEmpty();
    }

    /** Swing thread: writes the preset fields as a new stored preset and chooses it; false when the name won't do. */
    public boolean saveNewPreset(String presetName) {
        if (!canSaveNew(presetName)) {
            return false;
        }
        String stripped = presetName.strip();
        if (!writePreset(stripped)) {
            return false;
        }
        refreshPresets();
        select(new Preset(stripped, PresetKind.STORED));
        return true;
    }

    /** Swing thread: deletes a stored preset. */
    public void deletePreset(String presetName) {
        presets.remove(presetName);
        if (selected != null && selected.kind() == PresetKind.STORED && selected.name().equals(presetName)) {
            selected = null;
            chosenPreset = null;
        }
        refreshPresets();
    }

    private boolean writePreset(String presetName) {
        BehaviorSettings preset = new BehaviorSettings();
        try {
            preset.setDescription(presetName);
        } catch (PrincessException failure) {
            return false;
        }
        writePresetFields(preset);
        presets.save(preset);
        return true;
    }

    private void refreshPresets() {
        presetList.clear();
        presetList.add(new Preset("", PresetKind.PREVIOUS));
        presets.names().forEach(each -> presetList.add(new Preset(each, PresetKind.STORED)));
        otherBots.keySet().forEach(each -> presetList.add(new Preset(each, PresetKind.BOT)));
    }

    // ---- Targets ----

    /** The priority unit targets. */
    public BotTargetPicker picker() {
        return picker;
    }

    /** The strategic hex targets, each read against the board. */
    public List<HexTarget> hexTargets() {
        return hexTargets.stream().map(coords -> new HexTarget(coords, describe(coords, board), valid(coords,
              board))).toList();
    }

    /** The board's building, fuel tank and bridge hexes that can be added, with their descriptions. */
    public List<HexTarget> buildingHexes() {
        if (board == null) {
            return List.of();
        }
        List<HexTarget> hexes = new ArrayList<>();
        for (IBuilding building : board.getBuildingsVector()) {
            for (Coords coords : building.getCoordsList()) {
                String text = Messages.getString("BotConfigDialog.hexListIntro", coords.getX() + 1, coords.getY() + 1);
                if (board.getHex(coords) != null) {
                    text += buildingInfo(coords, board);
                }
                hexes.add(new HexTarget(coords, text, true));
            }
        }
        return hexes;
    }

    /** Adds a hex target unless it is there already. */
    public void addHex(Coords coords) {
        if (!hexTargets.contains(coords)) {
            hexTargets.add(coords);
        }
    }

    /**
     * Adds the hex typed as "x, y", counted from 1 as the board shows it; false when the text is not two whole
     * numbers.
     */
    public boolean addHex(String typed) {
        Optional<Coords> coords = parseHex(typed);
        coords.ifPresent(this::addHex);
        return coords.isPresent();
    }

    public void removeHex(Coords coords) {
        hexTargets.remove(coords);
    }

    /** The hex typed as "x, y", counted from 1; empty when the text is not two whole numbers. */
    public static Optional<Coords> parseHex(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String[] parts = typed.split(",");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Coords(Integer.parseInt(parts[0].strip()) - 1,
                  Integer.parseInt(parts[1].strip()) - 1));
        } catch (NumberFormatException failure) {
            return Optional.empty();
        }
    }

    /** How a hex target reads, as the Swing dialog's list shows it. */
    public static String describe(Coords coords, @Nullable Board board) {
        String text = Messages.getString("BotConfigDialog.hexListIntro", coords.getX() + 1, coords.getY() + 1);
        if (board == null) {
            return text + Messages.getString("BotConfigDialog.hexListNoMp");
        } else if (!board.contains(coords)) {
            return text + Messages.getString("BotConfigDialog.hexListOuts");
        } else if (!board.getHex(coords).containsAnyTerrainOf(BUILDING, FUEL_TANK, BRIDGE)) {
            return text + Messages.getString("BotConfigDialog.hexListNoBg");
        }
        return text + buildingInfo(coords, board);
    }

    private static boolean valid(Coords coords, @Nullable Board board) {
        return board != null && board.contains(coords)
              && board.getHex(coords).containsAnyTerrainOf(BUILDING, FUEL_TANK, BRIDGE);
    }

    /** The building, fuel tank or bridge at {@code coords}, as the target lists describe it. */
    public static String buildingInfo(Coords coords, Board board) {
        Hex hex = board.getHex(coords);
        IBuilding building = board.getBuildingAt(coords);
        if (hex.containsTerrain(BUILDING)) {
            return Messages.getString("BotConfigDialog.hexListBldg", building.getBuildingType().toString(),
                  IBuilding.className(building.getBldgClass()), hex.terrainLevel(BLDG_ELEV), hex.terrainLevel(BLDG_CF));
        } else if (hex.containsTerrain(FUEL_TANK)) {
            return Messages.getString("BotConfigDialog.hexListFuel", hex.terrainLevel(FUEL_TANK_CF),
                  hex.terrainLevel(FUEL_TANK_MAGN));
        }
        return Messages.getString("BotConfigDialog.hexListBrdg", building.getBuildingType().toString(),
              hex.terrainLevel(BRIDGE_ELEV), hex.terrainLevel(BRIDGE_CF));
    }

    // ---- The result ----

    /** The settings as they stand, a new {@link BehaviorSettings} with the loaded configuration's description. */
    public BehaviorSettings behavior() {
        BehaviorSettings behavior = new BehaviorSettings();
        try {
            behavior.setDescription(description);
        } catch (PrincessException ignored) {
            // A blank description stays the default, as the Swing dialog leaves it
        }
        writePresetFields(behavior);
        for (Flag flag : Flag.values()) {
            if (!flag.preset()) {
                flag.write.accept(behavior, flags.get(flag));
            }
        }
        behavior.setRetreatEdge(retreatEdge);
        behavior.setDestinationEdge(fleeEdge);
        hexTargets.forEach(coords -> behavior.addStrategicTarget(coords.toString()));
        picker.picked().forEach(behavior::setPriorityUnit);
        return behavior;
    }

    /** The configuration's description: the stored preset's name or the loaded configuration's. */
    public String description() {
        return description;
    }

    private void loadAll(BehaviorSettings behavior) {
        description = behavior.getDescription();
        loadPresetFields(behavior);
        flags.put(Flag.FORCED_WITHDRAWAL, behavior.isForcedWithdrawal());
        flags.put(Flag.AUTO_FLEE, behavior.shouldAutoFlee());
        retreatEdge(behavior.getRetreatEdge());
        fleeEdge(behavior.getDestinationEdge());
        hexTargets.clear();
        for (String target : behavior.getStrategicBuildingTargets()) {
            int begin = target.indexOf('(') + 1;
            int end = target.indexOf(')');
            if (begin > 0 && end > begin) {
                parseHex(target.substring(begin, end)).map(coords -> new Coords(coords.getX() + 1,
                      coords.getY() + 1)).ifPresent(this::addHex);
            }
        }
        Map<Integer, Integer> priorities = new HashMap<>();
        for (int unitId : behavior.getPriorityUnitTargets()) {
            priorities.put(unitId, behavior.getTargetPriority(unitId));
        }
        picker.picked(priorities);
    }

    private void loadPresetFields(BehaviorSettings behavior) {
        for (Slider slider : Slider.values()) {
            sliders.put(slider, slider.clamp(slider.read.applyAsInt(behavior)));
        }
        for (Flag flag : Flag.values()) {
            if (flag.preset()) {
                flags.put(flag, flag.read.test(behavior));
            }
        }
        posture = behavior.getCombatPosture() == null ? CombatPosture.ATTACK : behavior.getCombatPosture();
    }

    private void writePresetFields(BehaviorSettings behavior) {
        for (Slider slider : Slider.values()) {
            slider.write.accept(behavior, sliders.get(slider));
        }
        for (Flag flag : Flag.values()) {
            if (flag.preset()) {
                flag.write.accept(behavior, flags.get(flag));
            }
        }
        behavior.setCombatPosture(posture);
    }
}
