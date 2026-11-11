/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.settings;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.bot.princess.BehaviorSettingsFactory;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.KeyBindParser;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.SensorFamily;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceStore;
import megamek.common.preference.PreferenceManager;

/**
 * The Client Settings without a toolkit: the player's own preferences, which the native settings view shows as pages
 * of cards. A change takes effect at once, so the player hears or sees it ({@link #apply}); Cancel puts back what the
 * settings were when the form opened ({@link #revert}), and Defaults or a page's reset go back to the shipped values.
 * <p>
 * The form keeps its values as text: "true" or "false" for a switch, the number for a slider, the path for a file, and
 * the key and its modifiers for a key binding ({@link Kind#KEY}). It reads the preferences and the key bindings once,
 * when built, and writes them only through the {@link Runnable}s it hands out, which the caller runs on the Swing
 * thread, since the preferences' listeners update Swing views.
 * <p>
 * The Controls pages list every key binding but the two that scale the Swing interface, which the 3D board swallows:
 * the menus' scale is a slider on the Display page.
 */
public final class ClientSettingsForm extends SettingsForm {
    /** The pages' ids, which the old routes of the Swing dialog lead to. */
    public static final String AUDIO = "audio";
    public static final String DISPLAY = "display";
    /** The Controls category, its first page (the keyboard's timing) and the prefix of a key binding's name. */
    public static final String CONTROLS = "controls";
    public static final String KEYBOARD = "keyboard";
    public static final String BIND = "bind.";
    /** The start of a key binding page's id, which keeps it apart from the old Swing routes such as "overlays". */
    public static final String KEYS = "keys.";
    /** The Gameplay category and its pages. */
    public static final String GAMEPLAY = "gameplay";
    public static final String WARNINGS = "warnings";
    public static final String AUTOMATION = "automation";
    public static final String BOTS = "bots";
    public static final String UNITS = "units";
    /** The ProtoMek point codes' first letters, as the client preferences keep them: A, Alpha and alpha. */
    static final List<String> UNIT_START_CHARS = List.of("65", "913", "945");
    /** The key bindings the Controls pages leave out: they scale the Swing interface, and the 3D board ignores them. */
    static final Set<KeyCommandBind> DROPPED_BINDS = Set.of(KeyCommandBind.INC_GUI_SCALE,
          KeyCommandBind.DEC_GUI_SCALE);
    /** The languages MegaMek has texts in, as the client preferences keep them; the first is the default. */
    static final List<String> LOCALES = List.of("en", "de", "ru", "es");
    /** The Swing dialog's routes that lead to a native page under another id. */
    private static final Map<String, String> ROUTES = Map.of(CommonSettingsPane.SETTINGS_MAIN, DISPLAY,
          CommonSettingsPane.SETTINGS_MAIN + ".application", DISPLAY, CommonSettingsPane.SETTINGS_KEYBINDS, KEYBOARD,
          CommonSettingsPane.SETTINGS_AIDISPLAY, BOTS);
    /** The Controls pages of key bindings, in order, each with its sections and the commands each lists. */
    private static final List<BindPage> BIND_PAGES = List.of(
          new BindPage("camera",
                new BindSection("pan", KeyCommandBind.SCROLL_NORTH, KeyCommandBind.SCROLL_SOUTH,
                      KeyCommandBind.SCROLL_WEST, KeyCommandBind.SCROLL_EAST),
                new BindSection("orbit", KeyCommandBind.CAMERA_ROTATE_LEFT, KeyCommandBind.CAMERA_ROTATE_RIGHT,
                      KeyCommandBind.CAMERA_TILT_UP, KeyCommandBind.CAMERA_TILT_DOWN, KeyCommandBind.CAMERA_RESET,
                      KeyCommandBind.CAMERA_FIT_BOARD),
                new BindSection("focus", KeyCommandBind.ZOOM_IN, KeyCommandBind.ZOOM_OUT,
                      KeyCommandBind.ZOOM_OVERVIEW_TOGGLE, KeyCommandBind.CENTER_ON_SELECTED, KeyCommandBind.TOGGLE_ISO)),
          new BindPage("turn",
                new BindSection("units", KeyCommandBind.NEXT_UNIT, KeyCommandBind.PREV_UNIT,
                      KeyCommandBind.VIEW_ACTING_UNIT, KeyCommandBind.DEPLOY_TURN_UNIT,
                      KeyCommandBind.AUTO_ARTY_DEPLOYMENT_ZONE),
                new BindSection("orders", KeyCommandBind.DONE, KeyCommandBind.DONE_NO_ACTION, KeyCommandBind.CANCEL,
                      KeyCommandBind.UNDO_LAST_STEP, KeyCommandBind.UNDO_SINGLE_STEP, KeyCommandBind.CLEAR_ORDERS),
                new BindSection("clock", KeyCommandBind.EXTEND_TURN_TIMER, KeyCommandBind.PAUSE,
                      KeyCommandBind.UNPAUSE)),
          new BindPage("movement",
                new BindSection("mode", KeyCommandBind.MOVE_MODE_WALK, KeyCommandBind.MOVE_MODE_RUN,
                      KeyCommandBind.MOVE_MODE_JUMP, KeyCommandBind.TOGGLE_MOVE_MODE,
                      KeyCommandBind.TOGGLE_CONVERSION_MODE),
                new BindSection("steps", KeyCommandBind.MOVE_STEP_FORWARD, KeyCommandBind.MOVE_STEP_BACKWARD,
                      KeyCommandBind.TURN_LEFT, KeyCommandBind.TURN_RIGHT, KeyCommandBind.MOVE_BACKUP,
                      KeyCommandBind.MOVE_GO_PRONE, KeyCommandBind.MOVE_GETUP)),
          new BindPage("combat",
                new BindSection("weapons", KeyCommandBind.FIRE, KeyCommandBind.NEXT_WEAPON,
                      KeyCommandBind.PREV_WEAPON, KeyCommandBind.NEXT_MODE, KeyCommandBind.PREV_MODE),
                new BindSection("targets", KeyCommandBind.NEXT_TARGET, KeyCommandBind.PREV_TARGET,
                      KeyCommandBind.NEXT_TARGET_VALID, KeyCommandBind.PREV_TARGET_VALID,
                      KeyCommandBind.NEXT_TARGET_NO_ALLIES, KeyCommandBind.PREV_TARGET_NO_ALLIES,
                      KeyCommandBind.NEXT_TARGET_VALID_NO_ALLIES, KeyCommandBind.PREV_TARGET_VALID_NO_ALLIES),
                new BindSection("aim", KeyCommandBind.TWIST_LEFT, KeyCommandBind.TWIST_RIGHT,
                      KeyCommandBind.CALLED_SHOT_HIGH, KeyCommandBind.CALLED_SHOT_LOW,
                      KeyCommandBind.CALLED_SHOT_LEFT, KeyCommandBind.CALLED_SHOT_RIGHT),
                new BindSection("physical", KeyCommandBind.PHYS_PUNCH, KeyCommandBind.PHYS_KICK,
                      KeyCommandBind.PHYS_PUSH)),
          new BindPage("panels",
                new BindSection("panels", KeyCommandBind.GAME_MENU, KeyCommandBind.ROUND_REPORT,
                      KeyCommandBind.UNIT_OVERVIEW, KeyCommandBind.UNIT_DISPLAY, KeyCommandBind.FORCE_DISPLAY,
                      KeyCommandBind.FORCES_GRID, KeyCommandBind.MINIMAP, KeyCommandBind.TACTICAL_OVERLAYS,
                      KeyCommandBind.TURN_DETAILS, KeyCommandBind.PLANETARY_CONDITIONS, KeyCommandBind.BOT_COMMANDS,
                      KeyCommandBind.KEY_BINDS, KeyCommandBind.CLIENT_SETTINGS),
                new BindSection("unitTabs", KeyCommandBind.UD_GENERAL, KeyCommandBind.UD_PILOT,
                      KeyCommandBind.UD_ARMOR, KeyCommandBind.UD_WEAPONS, KeyCommandBind.UD_SYSTEMS,
                      KeyCommandBind.UD_EXTRAS),
                new BindSection("chat", KeyCommandBind.TOGGLE_CHAT, KeyCommandBind.TOGGLE_CHAT_CMD)),
          new BindPage("overlays",
                new BindSection("board", KeyCommandBind.MOVE_ENVELOPE, KeyCommandBind.MOD_ENVELOPE,
                      KeyCommandBind.FIELD_FIRE, KeyCommandBind.SENSOR_RANGE, KeyCommandBind.FOV_SPOTTING,
                      KeyCommandBind.LOS_SETTING, KeyCommandBind.SHOW_OBJECTS),
                new BindSection("labels", KeyCommandBind.SHOW_NAMEPLATES, KeyCommandBind.DRAW_LABELS,
                      KeyCommandBind.HEX_COORDS)),
          new BindPage("game",
                new BindSection("replay", KeyCommandBind.PLAYBACK_TOGGLE, KeyCommandBind.PLAYBACK_PREV,
                      KeyCommandBind.PLAYBACK_NEXT),
                new BindSection("reports", KeyCommandBind.REPORT_KEY_NEXT, KeyCommandBind.REPORT_KEY_PREV,
                      KeyCommandBind.REPORT_KEY_SELECT_NEXT, KeyCommandBind.REPORT_KEY_SELECT_PREVIOUS,
                      KeyCommandBind.REPORT_FILTER_KEY_SELECT_NEXT, KeyCommandBind.REPORT_KEY_FILTER),
                new BindSection("saves", KeyCommandBind.QUICK_SAVE, KeyCommandBind.QUICK_LOAD,
                      KeyCommandBind.LOCAL_SAVE, KeyCommandBind.LOCAL_LOAD, KeyCommandBind.REPLACE_PLAYER),
                new BindSection("editing", KeyCommandBind.UNDO, KeyCommandBind.REDO)));
    /** The extensions a notification sound can have: those Java plays. */
    static final List<String> SOUND_EXTENSIONS = List.of("wav", "aiff", "au");

    /**
     * Where a setting is kept, and how. {@code inverted} shows a "mute" preference as a "play" switch, and
     * {@code percent} a share kept as a fraction, such as a scale of 1.5, as a whole percentage.
     */
    private record Setting(@Nullable IPreferenceStore store, String key, Kind kind, boolean inverted, boolean percent,
          @Nullable KeyCommandBind bind) {
        Setting(IPreferenceStore store, String key, Kind kind, boolean inverted, boolean percent) {
            this(store, key, kind, inverted, percent, null);
        }
    }

    /** A section of key bindings on a Controls page. */
    private record BindSection(String id, List<KeyCommandBind> binds) {
        BindSection(String id, KeyCommandBind... binds) {
            this(id, List.of(binds));
        }
    }

    private record BindPage(String id, List<BindSection> sections) {
        BindPage(String id, BindSection... sections) {
            this(id, List.of(sections));
        }
    }

    private final List<Page> pages = new ArrayList<>();
    private final Map<String, Setting> settings = new LinkedHashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> started = new HashMap<>();
    private final Runnable bindsChanged;

    /** A form over the preferences whose changed key bindings are not stored, as in the tests. */
    public ClientSettingsForm(IPreferenceStore gui, ClientPreferences client) {
        this(gui, client, () -> { });
    }

    /**
     * @param gui          the GUI preferences, {@link GUIPreferences#getInstance()} outside the tests
     * @param client       the client preferences, {@link PreferenceManager#getClientPreferences()} outside the tests
     * @param bindsChanged runs, on the Swing thread, after a write changed any key binding: outside the tests
     *                     {@link KeyBindParser#writeKeyBindings()}, which stores them and tells the views
     */
    public ClientSettingsForm(IPreferenceStore gui, ClientPreferences client, Runnable bindsChanged) {
        this.bindsChanged = bindsChanged;
        pages.add(audio(gui));
        pages.add(display(gui, client));
        pages.add(keyboard(gui));
        BIND_PAGES.forEach(page -> pages.add(binds(page)));
        pages.add(warnings(gui));
        pages.add(automation(gui, client));
        pages.add(bots(gui, client));
        pages.add(units(client));
        for (Map.Entry<String, Setting> entry : settings.entrySet()) {
            String value = read(entry.getValue());
            if (entry.getValue().kind() == Kind.ORDER) {
                value = order(value, row(entry.getKey()).values());
            }
            values.put(entry.getKey(), value);
            started.put(entry.getKey(), value);
        }
    }

    /** The form over the player's preferences; Swing thread. */
    public static ClientSettingsForm current() {
        return new ClientSettingsForm(GUIPreferences.getInstance(), PreferenceManager.getClientPreferences(),
              KeyBindParser::writeKeyBindings);
    }

    /**
     * @return the page an old Swing route opens ({@code CommonSettingsPane.SETTINGS_*}), or null for the first; a route
     *       whose page is not native yet opens the first page
     */
    public @Nullable String pageOf(@Nullable String route) {
        if (route == null) {
            return null;
        }
        String page = ROUTES.getOrDefault(route, route);
        return page(page) != null ? page : null;
    }

    // ---- The pages

    private Page audio(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(AUDIO, "volume", "CommonSettingsDialog.section.audio.volume",
              row(gui, GUIPreferences.MASTER_VOLUME, Kind.SLIDER, false, "ClientSettings.audio.volume",
                    "CommonSettingsDialog.masterVolumeTT", List.of("0", "100"))));
        sections.add(section(AUDIO, "chat", "CommonSettingsDialog.section.audio.chat",
              row(gui, GUIPreferences.SOUND_MUTE_CHAT, Kind.BOOLEAN, true, "ClientSettings.audio.playChat",
                    "ClientSettings.audio.playChat.help", List.of()),
              row(gui, GUIPreferences.SOUND_BING_FILENAME_CHAT, Kind.FILE, false, "ClientSettings.audio.file",
                    "ClientSettings.audio.file.help", SOUND_EXTENSIONS)));
        sections.add(section(AUDIO, "myTurn", "CommonSettingsDialog.section.audio.myTurn",
              row(gui, GUIPreferences.SOUND_MUTE_MY_TURN, Kind.BOOLEAN, true, "ClientSettings.audio.playMyTurn",
                    "ClientSettings.audio.playMyTurn.help", List.of()),
              row(gui, GUIPreferences.SOUND_BING_FILENAME_MY_TURN, Kind.FILE, false, "ClientSettings.audio.file",
                    "ClientSettings.audio.file.help", SOUND_EXTENSIONS),
              row(gui, GUIPreferences.SOUND_PROMPT_SUPPRESS, Kind.BOOLEAN, false, "ClientSettings.audio.noPrompt",
                    "ClientSettings.audio.noPrompt.help", List.of())));
        sections.add(section(AUDIO, "otherTurns", "CommonSettingsDialog.section.audio.otherTurns",
              row(gui, GUIPreferences.SOUND_MUTE_OTHERS_TURN, Kind.BOOLEAN, true, "ClientSettings.audio.playOthers",
                    "ClientSettings.audio.playOthers.help", List.of()),
              row(gui, GUIPreferences.SOUND_BING_FILENAME_OTHERS_TURN, Kind.FILE, false, "ClientSettings.audio.file",
                    "ClientSettings.audio.file.help", SOUND_EXTENSIONS)));
        String title = Messages.getString("CommonSettingsDialog.audio");
        return new Page(AUDIO, title, "", "", false, List.copyOf(sections),
              searchText(AUDIO, title, Messages.getString("ClientSettings.audio.keywords")));
    }

    private Page display(IPreferenceStore gui, ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(DISPLAY, "scale", "ClientSettings.display.scale",
              percent(gui, GUIPreferences.GUI_SCALE, "ClientSettings.display.guiScale", List.of("70", "240", "10")),
              percent(gui, GUIPreferences.BOARD_UI_SCALE, "ClientSettings.display.hudScale",
                    List.of("50", "100", "5"))));
        List<String> languages = List.of(Messages.getString("CommonSettingsDialog.locale.English"),
              Messages.getString("CommonSettingsDialog.locale.Deutsch"),
              Messages.getString("CommonSettingsDialog.locale.Russian"),
              Messages.getString("CommonSettingsDialog.locale.Spanish"));
        sections.add(section(DISPLAY, "language", "ClientSettings.display.language",
              row(client, ClientPreferences.LOCALE, Kind.CHOICE, "ClientSettings.display.language", LOCALES,
                    languages)));
        sections.add(section(DISPLAY, "access", "ClientSettings.display.access",
              row(gui, GUIPreferences.COLOR_BLIND_PALETTE, Kind.BOOLEAN, false,
                    "CommonSettingsDialog.colorBlindPalette", "ClientSettings.display.colorBlind.help", List.of()),
              row(gui, GUIPreferences.REDUCE_MOTION, Kind.BOOLEAN, false, "CommonSettingsDialog.reduceMotion",
                    "ClientSettings.display.reduceMotion.help", List.of())));
        String title = Messages.getString("ClientSettings.display");
        return new Page(DISPLAY, title, "", "", false, List.copyOf(sections),
              searchText(DISPLAY, title, Messages.getString("ClientSettings.display.keywords")));
    }

    private Page keyboard(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(KEYBOARD, "repeat", "ClientSettings.controls.repeat",
              row(gui, GUIPreferences.ADVANCED_KEY_REPEAT_DELAY, Kind.SLIDER, false,
                    "ClientSettings.controls.repeatDelay", "AdvancedOptions.KeyRepeatDelay.tooltip",
                    List.of("0", "1000", "10"), List.of("ms")),
              row(gui, GUIPreferences.ADVANCED_KEY_REPEAT_RATE, Kind.SLIDER, false,
                    "ClientSettings.controls.repeatRate", "AdvancedOptions.KeyRepeatRate.tooltip",
                    List.of("0", "100"), List.of("/s"))));
        sections.add(section(KEYBOARD, "tooltips", "ClientSettings.controls.tooltips",
              row(gui, GUIPreferences.TOOLTIP_DELAY, Kind.SLIDER, false, "ClientSettings.controls.tooltipDelay",
                    "ClientSettings.controls.tooltipDelay.help", List.of("0", "3000", "100"), List.of("ms"))));
        return controlsPage(KEYBOARD, KEYBOARD, sections);
    }

    /** The questions MegaMek asks before a move, an attack or a step it thinks the player may regret. */
    private Page warnings(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(WARNINGS, "deployment", "ClientSettings.gameplay.warnings.deployment",
              ask(gui, GUIPreferences.NAG_FOR_DOOMED, "doomed", "CommonSettingsDialog.nagForDoomedDeployment.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_AUTO_EJECT, "autoEject", "CommonSettingsDialog.nagForAutoEject.tooltip")));
        sections.add(section(WARNINGS, "movement", "ClientSettings.gameplay.warnings.movement",
              ask(gui, GUIPreferences.NAG_FOR_PSR, "psr", null),
              ask(gui, GUIPreferences.NAG_FOR_MASC, "masc", null),
              ask(gui, GUIPreferences.NAG_FOR_SPRINT, "sprint", null),
              ask(gui, GUIPreferences.NAG_FOR_MECHANICAL_FALL_DAMAGE, "jumpFall",
                    "CommonSettingsDialog.nagForMechanicalJumpFallDamage.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_CRUSHING_BUILDINGS, "buildings",
                    "CommonSettingsDialog.nagForCrushingBuildings.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_DOOMED_MOVE, "water", "CommonSettingsDialog.nagForDoomedMove.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_WIGE_LANDING, "wige", "CommonSettingsDialog.nagForWiGELanding.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_LAUNCH_DOORS, "launch", "CommonSettingsDialog.nagForLaunchDoors.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_NO_UNJAM_RAC, "jammed", "CommonSettingsDialog.nagForUnJamRAC.tooltip")));
        sections.add(section(WARNINGS, "combat", "ClientSettings.gameplay.warnings.combat",
              ask(gui, GUIPreferences.NAG_FOR_NO_ACTION, "idle", "CommonSettingsDialog.nagForNoAction.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_OVERHEAT, "overheat", "CommonSettingsDialog.nagForOverheat.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_DISHONOR, "dishonor", "CommonSettingsDialog.nagForDishonor.tooltip"),
              ask(gui, GUIPreferences.NAG_FOR_NO_ARTILLERY_DESIGNATION, "artillery",
                    "CommonSettingsDialog.nagForNoArtilleryDesignation.tooltip")));
        sections.add(section(WARNINGS, "table", "ClientSettings.gameplay.warnings.table",
              ask(gui, GUIPreferences.NAG_FOR_ODD_SIZED_BOARD, "oddBoard",
                    "CommonSettingsDialog.nagForOddSizedBoard.tooltip"),
              row(gui, GUIPreferences.ADVANCED_NO_SAVE_NAG, Kind.BOOLEAN, true, "ClientSettings.gameplay.ask.save",
                    "AdvancedOptions.NoSaveNag.tooltip", List.of()),
              ask(gui, GUIPreferences.NAG_FOR_README, "welcome", null)));
        return gameplayPage(WARNINGS, sections);
    }

    /** A question MegaMek asks; its label is {@code ClientSettings.gameplay.ask.<name>}, and so is its help without one. */
    private Row ask(IPreferenceStore gui, String key, String name, @Nullable String helpKey) {
        String labelKey = "ClientSettings.gameplay.ask." + name;
        return row(gui, key, Kind.BOOLEAN, false, labelKey, helpKey == null ? labelKey + ".help" : helpKey,
              List.of());
    }

    /** What MegaMek does for the player: in a turn, with a unit's weapons and sensors, and with the lobby. */
    private Page automation(IPreferenceStore gui, ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(AUTOMATION, "turn", "ClientSettings.gameplay.automation.turn",
              row(gui, GUIPreferences.AUTO_SELECT_NEXT, Kind.BOOLEAN, false, "ClientSettings.gameplay.autoSelect",
                    "CommonSettingsDialog.useAutoSelectNext.tooltip", List.of()),
              row(gui, GUIPreferences.AUTO_END_FIRING, Kind.BOOLEAN, false, "ClientSettings.gameplay.autoEndFiring",
                    "ClientSettings.gameplay.autoEndFiring.help", List.of()),
              row(gui, GUIPreferences.AUTO_DECLARE_SEARCHLIGHT, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.searchlight", "CommonSettingsDialog.autoDeclareSearchlight.tooltip",
                    List.of())));
        sections.add(section(AUTOMATION, "paths", "ClientSettings.gameplay.automation.paths",
              row(gui, GUIPreferences.BOARD_MOVE_DEFAULT_CLIMB_MODE, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.climb", "ClientSettings.gameplay.climb.help", List.of()),
              row(client, ClientPreferences.MAX_PATHFINDER_TIME, Kind.SLIDER, false,
                    "ClientSettings.gameplay.pathfinder", "ClientSettings.gameplay.pathfinder.help",
                    List.of("100", "10000", "100"), List.of("ms"))));
        List<WeaponSortOrder> sorts = Arrays.stream(WeaponSortOrder.values()).filter(sort -> !sort.isCustom())
              .toList();
        List<SensorFamily> sensors = SensorFamily.defaultOrder();
        sections.add(section(AUTOMATION, "gear", "ClientSettings.gameplay.automation.gear",
              row(gui, GUIPreferences.DEFAULT_WEAPON_SORT_ORDER, Kind.CHOICE, "ClientSettings.gameplay.weaponSort",
                    sorts.stream().map(Enum::name).toList(), sorts.stream().map(WeaponSortOrder::toString).toList()),
              row(gui, GUIPreferences.SENSOR_PREFERENCE_ORDER, Kind.ORDER, "ClientSettings.gameplay.sensors",
                    sensors.stream().map(Enum::name).toList(),
                    sensors.stream().map(SensorFamily::getDisplayName).toList())));
        sections.add(section(AUTOMATION, "lobby", "ClientSettings.gameplay.automation.lobby",
              row(gui, GUIPreferences.ADVANCED_SAVE_LOBBY_ON_START, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.lobbyBackup", "AdvancedOptions.SaveLobbyOnStart.tooltip", List.of())));
        return gameplayPage(AUTOMATION, sections);
    }

    /** Princess, the bot: how a new one plays, and games where only bots fight. */
    private Page bots(IPreferenceStore gui, ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        List<String> behaviors = new ArrayList<>(BehaviorSettingsFactory.getInstance().getBehaviorNameList());
        String favorite = client.getFavoritePrincessBehaviorSetting();
        if (favorite != null && !favorite.isBlank() && !behaviors.contains(favorite)) {
            behaviors.add(favorite);
        }
        sections.add(section(BOTS, "princess", "ClientSettings.gameplay.bots.princess",
              row(client, ClientPreferences.FAVORITE_PRINCESS_BEHAVIOR_SETTING, Kind.CHOICE,
                    "ClientSettings.gameplay.behavior", List.copyOf(behaviors), List.copyOf(behaviors)),
              row(client, ClientPreferences.ENABLE_EXPERIMENTAL_BOT_FEATURES, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.experimental", "CommonSettingsDialog.enableExperimentalBotFeatures.tooltip",
                    List.of())));
        sections.add(section(BOTS, "watch", "ClientSettings.gameplay.bots.watch",
              row(gui, GUIPreferences.BOT_COMMANDS_PAUSE_AT_START, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.pauseBots", "CommonSettingsDialog.pauseBotGamesAtStart.tooltip", List.of()),
              row(client, ClientPreferences.SHOW_AUTO_RESOLVE_PANEL, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.autoResolve", "CommonSettingsDialog.showAutoResolvePanel.tooltip",
                    List.of())));
        return gameplayPage(BOTS, sections);
    }

    /** How a unit added in the lobby starts: its crew, its ejection and its codes. */
    private Page units(ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(UNITS, "crews", "ClientSettings.gameplay.units.crews",
              row(client, ClientPreferences.GENERATE_NAMES, Kind.BOOLEAN, false, "ClientSettings.gameplay.names",
                    "ClientSettings.gameplay.names.help", List.of()),
              row(client, ClientPreferences.USE_AVERAGE_SKILLS, Kind.BOOLEAN, false, "ClientSettings.gameplay.skills",
                    "ClientSettings.gameplay.skills.help", List.of()),
              row(client, ClientPreferences.DEFAULT_AUTO_EJECT_DISABLED, Kind.BOOLEAN, true,
                    "ClientSettings.gameplay.eject", "ClientSettings.gameplay.eject.help", List.of())));
        sections.add(section(UNITS, "labels", "ClientSettings.gameplay.units.labels",
              row(client, ClientPreferences.UNIT_START_CHAR, Kind.INTEGER_CHOICE, "ClientSettings.gameplay.protoCodes",
                    UNIT_START_CHARS, List.of("A, B, C, D\u2026", "\u0391, \u0392, \u0393, \u0394\u2026",
                          "\u03B1, \u03B2, \u03B3, \u03B4\u2026")),
              row(client, ClientPreferences.SHOW_UNIT_ID, Kind.BOOLEAN, false, "ClientSettings.gameplay.unitId",
                    "ClientSettings.gameplay.unitId.help", List.of())));
        return gameplayPage(UNITS, sections);
    }

    /** A Gameplay page; its id picks its texts. */
    private Page gameplayPage(String id, List<Section> sections) {
        String title = Messages.getString("ClientSettings.gameplay." + id);
        String category = Messages.getString("ClientSettings.gameplay");
        return new Page(id, title, GAMEPLAY, category, false, List.copyOf(sections),
              searchText(GAMEPLAY, category, title, Messages.getString("ClientSettings.gameplay.keywords")));
    }

    /** A Controls page of key bindings, each a row named {@link #BIND} and its command. */
    private Page binds(BindPage page) {
        List<Section> sections = new ArrayList<>();
        for (BindSection section : page.sections()) {
            sections.add(section(page.id(), section.id(), "ClientSettings.controls." + page.id() + '.' + section.id(),
                  section.binds().stream().map(this::bind).toArray(Row[]::new)));
        }
        if ("game".equals(page.id())) {
            // A command added later shows here until it has a place of its own, rather than not at all
            List<Row> others = unlisted().stream().map(this::bind).toList();
            if (!others.isEmpty()) {
                sections.add(section(page.id(), "other", "ClientSettings.controls.game.other",
                      others.toArray(Row[]::new)));
            }
        }
        return controlsPage(KEYS + page.id(), page.id(), sections);
    }

    /** @return the commands no Controls page lists, but for those it leaves out on purpose */
    static List<KeyCommandBind> unlisted() {
        Set<KeyCommandBind> listed = new HashSet<>(DROPPED_BINDS);
        BIND_PAGES.forEach(page -> page.sections().forEach(section -> listed.addAll(section.binds())));
        return Arrays.stream(KeyCommandBind.values()).filter(bind -> !listed.contains(bind)).toList();
    }

    /** A Controls page; {@code name} picks its texts. */
    private Page controlsPage(String id, String name, List<Section> sections) {
        String title = Messages.getString("ClientSettings.controls." + name);
        String category = Messages.getString("ClientSettings.controls");
        return new Page(id, title, CONTROLS, category, false, List.copyOf(sections),
              searchText(CONTROLS, category, title, Messages.getString("ClientSettings.controls.keywords")));
    }

    private Row bind(KeyCommandBind bind) {
        Setting setting = new Setting(null, BIND + bind.cmd, Kind.KEY, false, false, bind);
        settings.put(setting.key(), setting);
        String label = Messages.getString("KeyBinds.cmdNames." + bind.cmd);
        String help = Messages.getString("KeyBinds.cmdDesc." + bind.cmd);
        return new Row(setting.key(), label, help, Kind.KEY, List.of(), List.of(), Set.of(),
              searchText(bind.cmd, label, help, KeyCommandBind.getDesc(bind)));
    }

    private Section section(String page, String id, String key, Row... rows) {
        String title = Messages.getString(key + ".title");
        String summary = Messages.getString(key + ".summary");
        return new Section(page + '.' + id, title, summary, List.of(rows), searchText(title, summary));
    }

    private Row row(IPreferenceStore store, String key, Kind kind, boolean inverted, String labelKey, String helpKey,
          List<String> parameters) {
        return row(new Setting(store, key, kind, inverted, false), labelKey, helpKey, parameters, List.of());
    }

    private Row row(IPreferenceStore store, String key, Kind kind, boolean inverted, String labelKey, String helpKey,
          List<String> parameters, List<String> labels) {
        return row(new Setting(store, key, kind, inverted, false), labelKey, helpKey, parameters, labels);
    }

    /** A choice; its label's key with ".help" is its help's. */
    private Row row(IPreferenceStore store, String key, Kind kind, String labelKey, List<String> values,
          List<String> labels) {
        return row(new Setting(store, key, kind, false, false), labelKey, labelKey + ".help", values, labels);
    }

    /** A slider of a fraction kept as a float, shown as a percentage; its label's key with ".help" is its help's. */
    private Row percent(IPreferenceStore store, String key, String labelKey, List<String> range) {
        return row(new Setting(store, key, Kind.SLIDER, false, true), labelKey, labelKey + ".help", range,
              List.of("%"));
    }

    private Row row(Setting setting, String labelKey, String helpKey, List<String> parameters, List<String> labels) {
        settings.put(setting.key(), setting);
        String label = Messages.getString(labelKey);
        String help = Messages.getString(helpKey);
        return new Row(setting.key(), label, help, setting.kind(), parameters, labels, Set.of(),
              searchText(setting.key(), label, help, String.join(" ", labels)));
    }

    // ---- Values

    @Override
    public List<Page> pages() {
        return pages;
    }

    @Override
    public boolean bool(String name) {
        return Boolean.parseBoolean(values.get(name));
    }

    @Override
    public void bool(String name, boolean on) {
        Setting setting = settings.get(name);
        if (setting != null && setting.kind() == Kind.BOOLEAN) {
            values.put(name, Boolean.toString(on));
        }
    }

    @Override
    public String text(String name) {
        return values.getOrDefault(name, "");
    }

    @Override
    public void text(String name, String text) {
        Setting setting = settings.get(name);
        if (setting == null || setting.kind() == Kind.BOOLEAN) {
            return;
        }
        if (setting.kind() == Kind.SLIDER) {
            Row row = row(name);
            int number;
            try {
                number = Integer.parseInt(Objects.requireNonNullElse(text, "").strip());
            } catch (NumberFormatException ex) {
                return;
            }
            int step = row.step();
            number = row.min() + Math.round((number - row.min()) / (float) step) * step;
            text = Integer.toString(Math.clamp(number, row.min(), row.max()));
        } else if (setting.kind() == Kind.CHOICE && !row(name).values().contains(text)) {
            return;
        } else if (setting.kind() == Kind.KEY) {
            text = key(keyCode(text), keyModifiers(text));
        } else if (setting.kind() == Kind.ORDER) {
            text = order(text, row(name).values());
        }
        values.put(name, text == null ? "" : text);
    }

    @Override
    public boolean editable(String name) {
        return settings.containsKey(name);
    }

    @Override
    public boolean changed(String name) {
        return !Objects.equals(values.get(name), started.get(name));
    }

    /** @return whether the setting differs from its shipped default */
    public boolean customized(String name) {
        Setting setting = settings.get(name);
        return setting != null && !Objects.equals(values.get(name), defaultOf(setting));
    }

    @Override
    public @Nullable String defaultText(String name) {
        Setting setting = settings.get(name);
        return setting == null ? null : defaultOf(setting);
    }

    /**
     * A key binding's problem: Ctrl+C and Ctrl+V stay copy and paste, and a key an exclusive or menu command has
     * cannot be another command's too, as only one of them would act.
     */
    @Override
    public @Nullable String error(String name) {
        Setting setting = settings.get(name);
        if (setting == null || setting.bind() == null) {
            return null;
        }
        String value = values.get(name);
        int code = keyCode(value);
        int modifiers = keyModifiers(value);
        if (code == 0) {
            return null;
        }
        if (modifiers == InputEvent.CTRL_DOWN_MASK && (code == KeyEvent.VK_C || code == KeyEvent.VK_V)) {
            return Messages.getString("ClientSettings.controls.copyPaste");
        }
        for (KeyCommandBind other : KeyCommandBind.values()) {
            String shared = bound(other);
            if (other != setting.bind() && keyCode(shared) == code && keyModifiers(shared) == modifiers
                  && (exclusive(setting.bind()) || exclusive(other))) {
                return Messages.getString("ClientSettings.controls.clash",
                      Messages.getString("KeyBinds.cmdNames." + other.cmd));
            }
        }
        return null;
    }

    /** @return the key of the command as the form has it, or as it is bound for one the form leaves out */
    private String bound(KeyCommandBind bind) {
        String value = values.get(BIND + bind.cmd);
        return value != null ? value : key(bind.key, bind.modifiers);
    }

    private static boolean exclusive(KeyCommandBind bind) {
        return bind.isExclusive || bind.isMenuBar;
    }

    /** @return whether any setting on the page differs from its shipped default */
    public boolean customized(Page page) {
        return page.sections().stream().flatMap(section -> section.rows().stream())
              .anyMatch(row -> customized(row.name()));
    }

    @Override
    public void defaults() {
        pages.forEach(this::defaults);
    }

    /** Resets the page's settings to their shipped defaults. */
    public void defaults(Page page) {
        page.sections().stream().flatMap(section -> section.rows().stream())
              .forEach(row -> values.put(row.name(), defaultOf(settings.get(row.name()))));
    }

    /**
     * @return what writes the form's values into the preferences as they are now, to run on the Swing thread; a value
     *       the preferences already hold is not written again, so nothing hears of it
     */
    public Runnable apply() {
        return writer(Map.copyOf(values));
    }

    /** @return what writes back the values the form started from, to run on the Swing thread */
    public Runnable revert() {
        return writer(Map.copyOf(started));
    }

    // ---- Internals

    private Runnable writer(Map<String, String> written) {
        List<Setting> all = List.copyOf(settings.values());
        return () -> {
            boolean binds = false;
            for (Setting setting : all) {
                String value = written.get(setting.key());
                if (value != null && !value.equals(read(setting))) {
                    write(setting, value);
                    binds |= setting.bind() != null;
                }
            }
            if (binds) {
                bindsChanged.run();
            }
        };
    }

    private Row row(String name) {
        return pages.stream().flatMap(page -> page.sections().stream()).flatMap(section -> section.rows().stream())
              .filter(row -> row.name().equals(name)).findFirst().orElseThrow();
    }

    private static String read(Setting setting) {
        KeyCommandBind bind = setting.bind();
        if (bind != null) {
            return key(bind.key, bind.modifiers);
        }
        IPreferenceStore store = Objects.requireNonNull(setting.store());
        if (store instanceof ClientPreferences client && ClientPreferences.LOCALE.equals(setting.key())) {
            return language(client.getLocaleString());
        }
        if (setting.percent()) {
            return Integer.toString(Math.round(store.getFloat(setting.key()) * 100));
        }
        return switch (setting.kind()) {
            case BOOLEAN -> Boolean.toString(store.getBoolean(setting.key()) != setting.inverted());
            case SLIDER, INTEGER, INTEGER_CHOICE -> Integer.toString(store.getInt(setting.key()));
            default -> Objects.requireNonNullElse(store.getString(setting.key()), "");
        };
    }

    private static String defaultOf(Setting setting) {
        KeyCommandBind bind = setting.bind();
        if (bind != null) {
            return key(bind.keyDefault, bind.modifiersDefault);
        }
        IPreferenceStore store = Objects.requireNonNull(setting.store());
        if (store instanceof ClientPreferences && ClientPreferences.LOCALE.equals(setting.key())) {
            return LOCALES.getFirst();
        }
        if (setting.percent()) {
            return Integer.toString(Math.round(store.getDefaultFloat(setting.key()) * 100));
        }
        return switch (setting.kind()) {
            case BOOLEAN -> Boolean.toString(store.getDefaultBoolean(setting.key()) != setting.inverted());
            case SLIDER, INTEGER, INTEGER_CHOICE -> Integer.toString(store.getDefaultInt(setting.key()));
            default -> Objects.requireNonNullElse(store.getDefaultString(setting.key()), "");
        };
    }

    /** Writes with the preference's own type, as its listeners expect the old and new values typed. */
    private static void write(Setting setting, String value) {
        KeyCommandBind bind = setting.bind();
        if (bind != null) {
            bind.key = keyCode(value);
            bind.modifiers = keyModifiers(value);
            return;
        }
        IPreferenceStore store = Objects.requireNonNull(setting.store());
        if (store instanceof ClientPreferences client && ClientPreferences.LOCALE.equals(setting.key())) {
            // Through the client preferences, which keep the locale they hand out as well
            client.setLocale(value);
            return;
        }
        if (setting.percent()) {
            store.setValue(setting.key(), Integer.parseInt(value) / 100f);
            return;
        }
        switch (setting.kind()) {
            case BOOLEAN -> store.setValue(setting.key(), Boolean.parseBoolean(value) != setting.inverted());
            case SLIDER, INTEGER, INTEGER_CHOICE -> store.setValue(setting.key(), Integer.parseInt(value));
            default -> store.setValue(setting.key(), value);
        }
    }

    /**
     * A ranking as the form keeps it: the known ids of {@code text} in their order, each once, then the ids it left out
     * in their shipped order, so a sensor added since the ranking was kept still has a place.
     */
    static String order(@Nullable String text, List<String> ids) {
        Set<String> ranked = new LinkedHashSet<>();
        for (String id : Objects.requireNonNullElse(text, "").split(",")) {
            if (ids.contains(id.strip())) {
                ranked.add(id.strip());
            }
        }
        ranked.addAll(ids);
        return String.join(",", ranked);
    }

    /** The language of a kept locale such as "de" or "en_US"; one MegaMek has no texts in reads as the default. */
    private static String language(String locale) {
        String language = locale.split("_", 2)[0].strip();
        return LOCALES.contains(language) ? language : LOCALES.getFirst();
    }
}
