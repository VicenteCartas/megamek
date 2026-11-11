/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.settings;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.LabelDisplayStyle;
import megamek.client.ui.clientGUI.boardview.NameplateMode;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Configuration;
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
 * The form keeps its values as text: "true" or "false" for a switch, the number for a slider, the path for a file, the
 * colour as "#rrggbb" ({@link Kind#COLOR}), and the key and its modifiers for a key binding ({@link Kind#KEY}). It reads
 * the preferences and the key bindings once,
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
    /** The Battlefield category and its pages: how the 3D board draws the terrain, the units and the battle's marks. */
    public static final String BATTLEFIELD = "battlefield";
    public static final String BOARD = "board";
    public static final String LABELS = "labels";
    public static final String MARKS = "marks";
    public static final String TRACE = "trace";
    /** The Colours category and its pages. */
    public static final String COLOURS = "colours";
    public static final String PALETTE = "palette";
    public static final String FORCES = "forces";
    public static final String TACTICAL = "tactical";
    public static final String MAP_TEXT = "mapText";
    /** The Messages category and its pages: the board's toasts and the round report's presets. */
    public static final String MESSAGES = "messages";
    public static final String NOTIFICATIONS = "notifications";
    public static final String REPORTS = "reports";
    /** The Files category and its pages: the logs MegaMek writes and the folders and programs it uses. */
    public static final String FILES = "files";
    public static final String LOGS = "logs";
    public static final String FOLDERS = "folders";
    /** The extension of the 3D board's tileset files. */
    static final String TILESET = ".tileset";
    /** The images the board editor can trace over the board: those libGDX reads. */
    static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "bmp");
    /** The ProtoMek point codes' first letters, as the client preferences keep them: A, Alpha and alpha. */
    static final List<String> UNIT_START_CHARS = List.of("65", "913", "945");
    /** The key bindings the Controls pages leave out: they scale the Swing interface, and the 3D board ignores them. */
    static final Set<KeyCommandBind> DROPPED_BINDS = Set.of(KeyCommandBind.INC_GUI_SCALE,
          KeyCommandBind.DEC_GUI_SCALE);
    /** The languages MegaMek has texts in, as the client preferences keep them; the first is the default. */
    static final List<String> LOCALES = List.of("en", "de", "ru", "es");
    /** The Swing dialog's routes that lead to a native page under another id. */
    private static final Map<String, String> ROUTES = Map.ofEntries(
          Map.entry(CommonSettingsPane.SETTINGS_MAIN, DISPLAY),
          Map.entry(CommonSettingsPane.SETTINGS_MAIN + ".application", DISPLAY),
          Map.entry(CommonSettingsPane.SETTINGS_KEYBINDS, KEYBOARD),
          Map.entry(CommonSettingsPane.SETTINGS_AIDISPLAY, BOTS),
          Map.entry(CommonSettingsPane.SETTINGS_GAMEBOARD, BOARD),
          Map.entry(CommonSettingsPane.SETTINGS_OVERLAYS, TRACE),
          Map.entry(CommonSettingsPane.SETTINGS_REPORT, REPORTS),
          Map.entry(CommonSettingsPane.SETTINGS_UNITDISPLAY, LABELS),
          Map.entry(CommonSettingsPane.SETTINGS_MINIMAP, BOARD),
          Map.entry(CommonSettingsPane.SETTINGS_BUTTONORDER, KEYBOARD),
          Map.entry(CommonSettingsPane.SETTINGS_AUTODISPLAY, AUTOMATION),
          Map.entry(CommonSettingsPane.SETTINGS_ADVANCED, LOGS));
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
     * Where a setting is kept, and how. {@code inverted} shows a "mute" preference as a "play" switch,
     * {@code percent} a share kept as a fraction, such as a scale of 1.5, as a whole percentage, and {@code alpha} an
     * opacity kept from 0 to 255 as a percentage.
     */
    private record Setting(@Nullable IPreferenceStore store, String key, Kind kind, boolean inverted, boolean percent,
          boolean alpha, @Nullable KeyCommandBind bind) {
        Setting(IPreferenceStore store, String key, Kind kind, boolean inverted, boolean percent) {
            this(store, key, kind, inverted, percent, false, null);
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
    private final boolean userDirOverridden;

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
        userDirOverridden = client.isUserDirOverridden();
        pages.add(audio(gui));
        pages.add(display(gui, client));
        pages.add(keyboard(gui));
        BIND_PAGES.forEach(page -> pages.add(binds(page)));
        pages.add(warnings(gui));
        pages.add(automation(gui, client));
        pages.add(bots(gui, client));
        pages.add(units(client));
        pages.add(board(gui, client));
        pages.add(labels(gui));
        pages.add(marks(gui));
        pages.add(trace(gui));
        pages.add(palette(gui));
        pages.add(forces(gui));
        pages.add(tactical(gui));
        pages.add(mapText(gui));
        pages.add(notifications(gui));
        pages.add(reports(client));
        pages.add(logs(client));
        pages.add(folders(client));
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
        sections.add(section(BOTS, "hosting", "ClientSettings.gameplay.bots.hosting",
              row(client, ClientPreferences.IP_ADDRESSES_IN_CHAT, Kind.BOOLEAN, false,
                    "ClientSettings.gameplay.ipInChat", "CommonSettingsDialog.showIPAddressesInChat.tooltip",
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

    /** How the 3D board draws its terrain and the labels on its hexes. */
    private Page board(IPreferenceStore gui, ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        List<String> tilesets = tilesets(client.getMapTileset());
        sections.add(section(BOARD, "terrain", "ClientSettings.battlefield.board.terrain",
              row(gui, GUIPreferences.BOARD_GRAPHICS, Kind.CHOICE, "ClientSettings.battlefield.graphics",
                    Arrays.stream(BoardGraphics.values()).map(Enum::name).toList(),
                    List.of(Messages.getString("CommonMenuBar.viewGraphicsClassic"),
                          Messages.getString("CommonMenuBar.viewGraphicsEnhanced"))),
              row(client, ClientPreferences.MAP_TILESET, Kind.CHOICE, "ClientSettings.battlefield.tileset",
                    tilesets, tilesets.stream().map(name -> name.substring(0, name.length() - TILESET.length()))
                          .toList()),
              flag(gui, GUIPreferences.SHOW_WRECKS, "wrecks")));
        sections.add(section(BOARD, "hexes", "ClientSettings.battlefield.board.hexes",
              flag(gui, GUIPreferences.SHOW_COORDS, "coords"),
              flag(gui, GUIPreferences.SHOW_HEX_TYPES, "hexTypes"),
              flag(gui, GUIPreferences.HEX_LABEL_OUTLINE, "outline"),
              flag(gui, GUIPreferences.SHOW_MAP_SHEETS, "mapSheets")));
        return battlefieldPage(BOARD, sections);
    }

    /** How the units are named and marked on the board. */
    private Page labels(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        List<LabelDisplayStyle> styles = List.of(LabelDisplayStyle.values());
        List<NameplateMode> modes = List.of(NameplateMode.values());
        sections.add(section(LABELS, "names", "ClientSettings.battlefield.labels.names",
              row(gui, GUIPreferences.UNIT_LABEL_STYLE, Kind.CHOICE, "ClientSettings.battlefield.labelStyle",
                    styles.stream().map(Enum::name).toList(), styles.stream()
                          .map(style -> Messages.getString("ClientSettings.battlefield.labelStyle." + style.name()))
                          .toList()),
              row(gui, GUIPreferences.NAMEPLATE_MODE, Kind.CHOICE, "ClientSettings.battlefield.nameplates",
                    modes.stream().map(Enum::name).toList(), modes.stream().map(NameplateMode::toString).toList())));
        sections.add(section(LABELS, "status", "ClientSettings.battlefield.labels.status",
              flag(gui, GUIPreferences.TEAM_COLORING, "teamColours"),
              flag(gui, GUIPreferences.SHOW_DAMAGE_LEVEL, "damage")));
        return battlefieldPage(LABELS, sections);
    }

    /** What the battle leaves on the board: where shells and bombs fell, charges, vectors and jamming. */
    private Page marks(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(MARKS, "strikes", "ClientSettings.battlefield.marks.strikes",
              flag(gui, GUIPreferences.SHOW_ARTILLERY_MISSES, "artilleryMisses"),
              flag(gui, GUIPreferences.SHOW_ARTILLERY_DRIFTS, "artilleryDrifts"),
              flag(gui, GUIPreferences.SHOW_ARTILLERY_DRIFT_ARROWS, "driftArrows"),
              flag(gui, GUIPreferences.SHOW_BOMB_MISSES, "bombMisses"),
              flag(gui, GUIPreferences.SHOW_BOMB_DRIFTS, "bombDrifts"),
              flag(gui, GUIPreferences.BOARD_DEMOLITION_CHARGE_HAZARD_OUTLINE, "charges")));
        sections.add(section(MARKS, "strength", "ClientSettings.battlefield.marks.strength",
              alpha(gui, GUIPreferences.BOARD_ATTACK_ARROW_TRANSPARENCY, "ClientSettings.battlefield.vectors",
                    "ClientSettings.battlefield.vectors.help"),
              alpha(gui, GUIPreferences.BOARD_ECM_TRANSPARENCY, "ClientSettings.battlefield.ecm",
                    "ClientSettings.battlefield.ecm.help")));
        return battlefieldPage(MARKS, sections);
    }

    /** The board editor's trace image: a scanned map laid over the board to trace. */
    private Page trace(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(TRACE, "image", "ClientSettings.battlefield.trace.image",
              row(gui, GUIPreferences.SHOW_TRACE_OVERLAY, Kind.BOOLEAN, false, "ClientSettings.battlefield.traceShown",
                    "ClientSettings.battlefield.traceShown.help", List.of()),
              row(gui, GUIPreferences.TRACE_OVERLAY_IMAGE_FILE, Kind.FILE, false,
                    "CommonSettingsDialog.TraceOverlayImageFile", "CommonSettingsDialog.TraceOverlayImageFile.tooltip",
                    IMAGE_EXTENSIONS),
              alpha(gui, GUIPreferences.TRACE_OVERLAY_TRANSPARENCY, "CommonSettingsDialog.TraceOverlayTransparency",
                    "CommonSettingsDialog.TraceOverlayTransparency.tooltip")));
        sections.add(section(TRACE, "placement", "ClientSettings.battlefield.trace.placement",
              row(gui, GUIPreferences.TRACE_OVERLAY_SCALE, Kind.SLIDER, false, "CommonSettingsDialog.TraceOverlayScale",
                    "CommonSettingsDialog.TraceOverlayScale.tooltip", List.of("30", "150"), List.of("%")),
              row(gui, GUIPreferences.TRACE_OVERLAY_ORIGIN_X, Kind.SLIDER, false,
                    "CommonSettingsDialog.TraceOverlayOriginX", "CommonSettingsDialog.TraceOverlayOriginX.tooltip",
                    List.of("-1000", "2000", "5"), List.of("px")),
              row(gui, GUIPreferences.TRACE_OVERLAY_ORIGIN_Y, Kind.SLIDER, false,
                    "CommonSettingsDialog.TraceOverlayOriginY", "CommonSettingsDialog.TraceOverlayOriginY.tooltip",
                    List.of("-1000", "2000", "5"), List.of("px"))));
        return battlefieldPage(TRACE, sections);
    }

    /** A Battlefield switch; its texts are {@code ClientSettings.battlefield.<name>} and its ".help". */
    private Row flag(IPreferenceStore gui, String key, String name) {
        String labelKey = "ClientSettings.battlefield." + name;
        return row(gui, key, Kind.BOOLEAN, false, labelKey, labelKey + ".help", List.of());
    }

    /** An opacity kept from 0 to 255, on a slider in percent. */
    private Row alpha(IPreferenceStore gui, String key, String labelKey, String helpKey) {
        return row(new Setting(gui, key, Kind.SLIDER, false, false, true, null), labelKey, helpKey,
              List.of("0", "100"), List.of("%"));
    }

    /** A Battlefield page; its id picks its texts. */
    private Page battlefieldPage(String id, List<Section> sections) {
        String title = Messages.getString("ClientSettings.battlefield." + id);
        String category = Messages.getString("ClientSettings.battlefield");
        return new Page(id, title, BATTLEFIELD, category, false, List.copyOf(sections),
              searchText(BATTLEFIELD, category, title, Messages.getString("ClientSettings.battlefield.keywords")));
    }

    /** The players' colours, which their units, labels, reports and chat show. */
    private Page palette(IPreferenceStore gui) {
        Row[] colours = Arrays.stream(PlayerColour.values())
              .map(colour -> colour(gui, colour.getText(), colour.toString(), "ClientSettings.colours.player.help"))
              .toArray(Row[]::new);
        return coloursPage(PALETTE, List.of(section(PALETTE, "players", "ClientSettings.colours.palette.players",
              colours), section(PALETTE, "camo", "ClientSettings.colours.palette.camo",
              row(gui, GUIPreferences.USE_CAMO_OVERLAY, Kind.BOOLEAN, false, "ClientSettings.colours.camoOverlay",
                    "ClientSettings.colours.camoOverlay.help", List.of()))));
    }

    /**
     * @return the tileset files the 3D board can draw: those MegaMek ships and those in the user's data folder, with
     *       {@code current} too when it is in neither, so the choice keeps it
     */
    static List<String> tilesets(@Nullable String current) {
        Set<String> names = new LinkedHashSet<>();
        for (File folder : List.of(Configuration.hexesDir(),
              new File(Configuration.userDataDir(), Configuration.hexesDir().toString()))) {
            String[] found = folder.list((directory, name) -> name.endsWith(TILESET));
            if (found != null) {
                Arrays.stream(found).sorted().forEach(names::add);
            }
        }
        if (current != null && current.endsWith(TILESET)) {
            names.add(current);
        }
        return List.copyOf(names);
    }

    // ---- Messages

    /** The board's toasts: whether they show, which, and for how long. */
    private Page notifications(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(NOTIFICATIONS, "toasts", "ClientSettings.messages.notifications.toasts",
              row(gui, GUIPreferences.TOAST_ENABLED, Kind.BOOLEAN, false, "ClientSettings.messages.toasts",
                    "CommonSettingsDialog.toastEnabled.tooltip", List.of()),
              row(gui, GUIPreferences.TOAST_REPORT_EVENTS, Kind.BOOLEAN, false, "ClientSettings.messages.reportToasts",
                    "CommonSettingsDialog.toastReportEvents.tooltip", List.of())));
        sections.add(section(NOTIFICATIONS, "timing", "ClientSettings.messages.notifications.timing",
              row(gui, GUIPreferences.TOAST_DURATION_SECONDS, Kind.SLIDER, false, "ClientSettings.messages.duration",
                    "CommonSettingsDialog.toastDurationSeconds.tooltip",                     List.of("1", "10"), List.of("s")),
              row(gui, GUIPreferences.TOAST_DRIP_SECONDS, Kind.SLIDER, false, "ClientSettings.messages.drip",
                                        "CommonSettingsDialog.toastDripSeconds.tooltip", List.of("1", "10"), List.of("s"))));
        return messagesPage(NOTIFICATIONS, sections);
    }

    /** The round report's search and filter presets. */
    private Page reports(ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(REPORTS, "search", "ClientSettings.messages.reports.search",
              row(client, ClientPreferences.REPORT_KEYWORDS, Kind.LINES, false,
                    "ClientSettings.messages.searchPresets", "CommonSettingsDialog.reportKeywords.tooltip", List.of())));
        sections.add(section(REPORTS, "filters", "ClientSettings.messages.reports.filters",
              row(client, ClientPreferences.REPORT_FILTER_KEYWORDS, Kind.LINES, false,
                    "ClientSettings.messages.filterPresets", "CommonSettingsDialog.reportFilterKeywords.tooltip",
                    List.of())));
        return messagesPage(REPORTS, sections);
    }

    /** A Messages page; its id picks its texts. */
    private Page messagesPage(String id, List<Section> sections) {
        String title = Messages.getString("ClientSettings.messages." + id);
        String category = Messages.getString("ClientSettings.messages");
        return new Page(id, title, MESSAGES, category, false, List.copyOf(sections),
              searchText(MESSAGES, category, title, Messages.getString("ClientSettings.messages.keywords")));
    }

    // ---- Files

    /** The logs MegaMek writes, and the date and time their names and the saved games' can carry. */
    private Page logs(ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(LOGS, "game", "ClientSettings.files.logs.game",
              row(client, ClientPreferences.GAME_LOG_KEEP, Kind.BOOLEAN, false, "ClientSettings.files.keepLog",
                    "ClientSettings.files.keepLog.help", List.of()),
              row(client, ClientPreferences.GAME_LOG_FILENAME, Kind.STRING, false, "ClientSettings.files.logName",
                    "ClientSettings.files.logName.help", List.of()),
              row(client, ClientPreferences.AUTO_RESOLVE_GAME_LOG_FILENAME, Kind.STRING, false,
                    "ClientSettings.files.autoResolveName", "ClientSettings.files.autoResolveName.help", List.of()),
              row(client, ClientPreferences.DATA_LOGGING, Kind.BOOLEAN, false, "ClientSettings.files.dataLog",
                    "ClientSettings.files.dataLog.help", List.of())));
        sections.add(section(LOGS, "stamps", "ClientSettings.files.logs.stamps",
              row(client, ClientPreferences.STAMP_FILENAMES, Kind.BOOLEAN, false, "ClientSettings.files.stamp",
                    "ClientSettings.files.stamp.help", List.of()),
              row(client, ClientPreferences.STAMP_FORMAT, Kind.STRING, false, "ClientSettings.files.stampFormat",
                    "ClientSettings.files.stampFormat.help", List.of())));
        return filesPage(LOGS, sections);
    }

    /** The folder of the player's own files, and MegaMekLab, which prints unit lists. */
    private Page folders(ClientPreferences client) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(FOLDERS, "data", "ClientSettings.files.folders.data",
              row(client, ClientPreferences.USER_DIR, Kind.FOLDER, false, "ClientSettings.files.userDir",
                    "CommonSettingsDialog.userDir.tooltip", List.of())));
        sections.add(section(FOLDERS, "programs", "ClientSettings.files.folders.programs",
              row(client, ClientPreferences.MML_PATH, Kind.FILE, false, "ClientSettings.files.mml",
                    "CommonSettingsDialog.mmlPath.tooltip", List.of())));
        return filesPage(FOLDERS, sections);
    }

    /** A Files page; its id picks its texts. */
    private Page filesPage(String id, List<Section> sections) {
        String title = Messages.getString("ClientSettings.files." + id);
        String category = Messages.getString("ClientSettings.files");
        return new Page(id, title, FILES, category, false, List.copyOf(sections),
              searchText(FILES, category, title, Messages.getString("ClientSettings.files.keywords")));
    }

    /** The sides' colours and the status colours of damage and warnings. */
    private Page forces(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(FORCES, "sides", "ClientSettings.colours.forces.sides",
              colour(gui, GUIPreferences.MY_UNIT_COLOR, "mine"),
              colour(gui, GUIPreferences.ALLY_UNIT_COLOR, "ally"),
              colour(gui, GUIPreferences.ENEMY_UNIT_COLOR, "enemy")));
        sections.add(section(FORCES, "status", "ClientSettings.colours.forces.status",
              colour(gui, GUIPreferences.OK_COLOR, "ok"),
              colour(gui, GUIPreferences.PRECAUTION_COLOR, "precaution"),
              colour(gui, GUIPreferences.CAUTION_COLOR, "caution"),
              colour(gui, GUIPreferences.WARNING_COLOR, "warning")));
        return coloursPage(FORCES, sections);
    }

    /** The colours of planned moves and of weapon and sensor ranges. */
    private Page tactical(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(TACTICAL, "movement", "ClientSettings.colours.tactical.movement",
              colour(gui, GUIPreferences.BOARD_MOVE_DEFAULT_COLOR, "walk"),
              colour(gui, GUIPreferences.BOARD_MOVE_RUN_COLOR, "run"),
              colour(gui, GUIPreferences.BOARD_MOVE_JUMP_COLOR, "jump"),
              colour(gui, GUIPreferences.BOARD_MOVE_BACK_COLOR, "back"),
              colour(gui, GUIPreferences.BOARD_MOVE_MASC_COLOR, "boost"),
              colour(gui, GUIPreferences.BOARD_MOVE_SPRINT_COLOR, "sprint"),
              colour(gui, GUIPreferences.BOARD_MOVE_ILLEGAL_COLOR, "illegal")));
        sections.add(section(TACTICAL, "ranges", "ClientSettings.colours.tactical.ranges",
              colour(gui, GUIPreferences.BOARD_FIELD_OF_FIRE_MIN_COLOR, "minimum"),
              colour(gui, GUIPreferences.BOARD_FIELD_OF_FIRE_SHORT_COLOR, "short"),
              colour(gui, GUIPreferences.BOARD_FIELD_OF_FIRE_MEDIUM_COLOR, "medium"),
              colour(gui, GUIPreferences.BOARD_FIELD_OF_FIRE_LONG_COLOR, "long"),
              colour(gui, GUIPreferences.BOARD_FIELD_OF_FIRE_EXTREME_COLOR, "extreme"),
              colour(gui, GUIPreferences.BOARD_SENSOR_RANGE_COLOR, "sensor"),
              colour(gui, GUIPreferences.BOARD_VISUAL_RANGE_COLOR, "visual")));
        return coloursPage(TACTICAL, sections);
    }

    /** The colours of what is written and drawn on the map itself. */
    private Page mapText(IPreferenceStore gui) {
        List<Section> sections = new ArrayList<>();
        sections.add(section(MAP_TEXT, "text", "ClientSettings.colours.mapText.text",
              colour(gui, GUIPreferences.BOARD_TEXT_COLOR, "boardText"),
              colour(gui, GUIPreferences.BOARD_SPACE_TEXT_COLOR, "spaceText"),
              colour(gui, GUIPreferences.BOARD_BUILDING_TEXT_COLOR, "buildingText"),
              colour(gui, GUIPreferences.BOARD_LOW_FOLIAGE_COLOR, "lowFoliage")));
        sections.add(section(MAP_TEXT, "marks", "ClientSettings.colours.mapText.marks",
              colour(gui, GUIPreferences.BOARD_MAP_SHEET_COLOR, "mapSheet"),
              colour(gui, GUIPreferences.BOARD_DEMOLITION_CHARGE_COLOR, "charge")));
        return coloursPage(MAP_TEXT, sections);
    }

    /** A colour; its texts are {@code ClientSettings.colours.<name>} and its ".help". */
    private Row colour(IPreferenceStore gui, String key, String name) {
        String labelKey = "ClientSettings.colours." + name;
        return row(gui, key, Kind.COLOR, false, labelKey, labelKey + ".help", List.of());
    }

    private Row colour(IPreferenceStore gui, String key, String label, String helpKey) {
        Setting setting = new Setting(gui, key, Kind.COLOR, false, false);
        settings.put(key, setting);
        String help = Messages.getString(helpKey);
        return new Row(key, label, help, Kind.COLOR, List.of(), List.of(), Set.of(), searchText(key, label, help));
    }

    /** A Colours page; its id picks its texts. */
    private Page coloursPage(String id, List<Section> sections) {
        String title = Messages.getString("ClientSettings.colours." + id);
        String category = Messages.getString("ClientSettings.colours");
        return new Page(id, title, COLOURS, category, false, List.copyOf(sections),
              searchText(COLOURS, category, title, Messages.getString("ClientSettings.colours.keywords")));
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
        Setting setting = new Setting(null, BIND + bind.cmd, Kind.KEY, false, false, false, bind);
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
        } else if (setting.kind() == Kind.LINES) {
            text = String.join("\n", lines(text));
        } else if (setting.kind() == Kind.COLOR) {
            int rgb = rgb(text);
            if (rgb < 0) {
                return;
            }
            text = color(rgb);
        }
        values.put(name, text == null ? "" : text);
    }

    /**
     * Whether the setting can change now: the toasts' details only while toasts show (and the gap between report
     * toasts only while those show), the game log's name only while it is kept, the stamp's format only while files
     * are stamped, and the user's folder not while the launcher sets it for this run.
     */
    @Override
    public boolean editable(String name) {
        if (!settings.containsKey(name)) {
            return false;
        }
        return switch (name) {
            case GUIPreferences.TOAST_REPORT_EVENTS, GUIPreferences.TOAST_DURATION_SECONDS ->
                  bool(GUIPreferences.TOAST_ENABLED);
            case GUIPreferences.TOAST_DRIP_SECONDS -> bool(GUIPreferences.TOAST_ENABLED)
                  && bool(GUIPreferences.TOAST_REPORT_EVENTS);
            case ClientPreferences.GAME_LOG_FILENAME -> bool(ClientPreferences.GAME_LOG_KEEP);
            case ClientPreferences.STAMP_FORMAT -> bool(ClientPreferences.STAMP_FILENAMES);
            case ClientPreferences.USER_DIR -> !userDirOverridden;
            default -> true;
        };
    }

    /** @return whether the launcher sets the user's folder for this run, so the form cannot change it */
    public boolean userDirOverridden() {
        return userDirOverridden;
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
     * A setting's problem: a log's name must not be blank, nor the stamp's format other than a date and time pattern.
     * A key binding's: Ctrl+C and Ctrl+V stay copy and paste, and a key an exclusive or menu command has cannot be
     * another command's too, as only one of them would act.
     */
    @Override
    public @Nullable String error(String name) {
        Setting setting = settings.get(name);
        if (setting == null) {
            return null;
        }
        if (setting.bind() == null) {
            return problem(name, values.get(name));
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

    /** @return the problem of a preference's {@code value}, which is then not written, or null */
    private static @Nullable String problem(String key, @Nullable String value) {
        String text = Objects.requireNonNullElse(value, "");
        return switch (key) {
            case ClientPreferences.GAME_LOG_FILENAME, ClientPreferences.AUTO_RESOLVE_GAME_LOG_FILENAME ->
                  text.isBlank() ? Messages.getString("ClientSettings.files.logName.error") : null;
            case ClientPreferences.STAMP_FORMAT -> stamp(text) == null
                  ? Messages.getString("ClientSettings.files.stampFormat.error") : null;
            default -> null;
        };
    }

    /** @return the date and time stamp {@code format} gives now, or null when it is no date and time pattern */
    public static @Nullable String stamp(String format) {
        try {
            return DateTimeFormatter.ofPattern(format).format(LocalDateTime.now());
        } catch (IllegalArgumentException | DateTimeException ex) {
            return null;
        }
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

    /** Resets the page's settings to their shipped defaults, but for a user's folder the launcher sets. */
    public void defaults(Page page) {
        page.sections().stream().flatMap(section -> section.rows().stream())
              .filter(row -> !(userDirOverridden && ClientPreferences.USER_DIR.equals(row.name())))
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
                if (value != null && !value.equals(read(setting))
                      && (setting.bind() != null || problem(setting.key(), value) == null)) {
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
        if (store instanceof ClientPreferences client && ClientPreferences.USER_DIR.equals(setting.key())) {
            // The folder in effect, which the launcher may set for this run
            return client.getUserDir();
        }
        if (setting.percent()) {
            return Integer.toString(Math.round(store.getFloat(setting.key()) * 100));
        }
        if (setting.alpha()) {
            return Integer.toString(percentOf(store.getInt(setting.key())));
        }
        return switch (setting.kind()) {
            case BOOLEAN -> Boolean.toString(store.getBoolean(setting.key()) != setting.inverted());
            case SLIDER, INTEGER, INTEGER_CHOICE -> Integer.toString(store.getInt(setting.key()));
            case COLOR -> colorOf(store.getString(setting.key()), store.getDefaultString(setting.key()));
            case LINES -> String.join("\n", lines(store.getString(setting.key())));
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
        if (setting.alpha()) {
            return Integer.toString(percentOf(store.getDefaultInt(setting.key())));
        }
        return switch (setting.kind()) {
            case BOOLEAN -> Boolean.toString(store.getDefaultBoolean(setting.key()) != setting.inverted());
            case SLIDER, INTEGER, INTEGER_CHOICE -> Integer.toString(store.getDefaultInt(setting.key()));
            case COLOR -> colorOf(store.getDefaultString(setting.key()), null);
            case LINES -> String.join("\n", lines(store.getDefaultString(setting.key())));
            default -> Objects.requireNonNullElse(store.getDefaultString(setting.key()), "");
        };
    }

    /** @return the share of 255 an opacity is, in whole percent */
    private static int percentOf(int alpha) {
        return Math.round(Math.clamp(alpha, 0, 255) * 100 / 255f);
    }

    /**
     * @return the colour the preferences keep as its red, green and blue ("255 128 0", as {@link GUIPreferences} writes
     *       it) or as a hex text, as the form's text; {@code fallback}'s colour, or black, when it is neither
     */
    static String colorOf(@Nullable String kept, @Nullable String fallback) {
        String text = Objects.requireNonNullElse(kept, "").strip();
        String[] parts = text.split("\\s+");
        if (parts.length == 3) {
            try {
                int red = Math.clamp(Integer.parseInt(parts[0]), 0, 255);
                int green = Math.clamp(Integer.parseInt(parts[1]), 0, 255);
                int blue = Math.clamp(Integer.parseInt(parts[2]), 0, 255);
                return color(red << 16 | green << 8 | blue);
            } catch (NumberFormatException ignored) {
                // Not three numbers; try it as a hex text
            }
        }
        int rgb = rgb(text);
        if (rgb >= 0) {
            return color(rgb);
        }
        return fallback == null ? color(0) : colorOf(fallback, null);
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
        if (store instanceof ClientPreferences client && ClientPreferences.USER_DIR.equals(setting.key())) {
            // Without trailing separators, and never the launcher's folder as the player's own
            client.setUserDir(value);
            return;
        }
        if (store instanceof ClientPreferences client && ClientPreferences.MML_PATH.equals(setting.key())) {
            client.setMmlPath(value);
            return;
        }
        if (setting.percent()) {
            store.setValue(setting.key(), Integer.parseInt(value) / 100f);
            return;
        }
        if (setting.alpha()) {
            store.setValue(setting.key(), Math.round(Integer.parseInt(value) * 255 / 100f));
            return;
        }
        switch (setting.kind()) {
            case BOOLEAN -> store.setValue(setting.key(), Boolean.parseBoolean(value) != setting.inverted());
            case SLIDER, INTEGER, INTEGER_CHOICE -> store.setValue(setting.key(), Integer.parseInt(value));
            case COLOR -> {
                // As GUIPreferences keeps a colour: its red, green and blue
                int rgb = rgb(value);
                store.setValue(setting.key(), (rgb >> 16 & 0xFF) + " " + (rgb >> 8 & 0xFF) + " " + (rgb & 0xFF));
            }
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
