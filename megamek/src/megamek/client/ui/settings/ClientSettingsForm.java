/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.panels.CommonSettingsPane;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceStore;
import megamek.common.preference.PreferenceManager;

/**
 * The Client Settings without a toolkit: the player's own preferences, which the native settings view shows as pages
 * of cards. A change takes effect at once, so the player hears or sees it ({@link #apply}); Cancel puts back what the
 * settings were when the form opened ({@link #revert}), and Defaults or a page's reset go back to the shipped values.
 * <p>
 * The form keeps its values as text: "true" or "false" for a switch, the number for a slider, the path for a file.
 * It reads the preferences once, when built, and writes them only through the {@link Runnable}s it hands out, which
 * the caller runs on the Swing thread, since the preferences' listeners update Swing views.
 */
public final class ClientSettingsForm extends SettingsForm {
    /** The pages' ids, which the old routes of the Swing dialog lead to. */
    public static final String AUDIO = "audio";
    public static final String DISPLAY = "display";
    /** The languages MegaMek has texts in, as the client preferences keep them; the first is the default. */
    static final List<String> LOCALES = List.of("en", "de", "ru", "es");
    /** The Swing dialog's routes that lead to a native page under another id. */
    private static final Map<String, String> ROUTES = Map.of(CommonSettingsPane.SETTINGS_MAIN, DISPLAY,
          CommonSettingsPane.SETTINGS_MAIN + ".application", DISPLAY);
    /** The extensions a notification sound can have: those Java plays. */
    static final List<String> SOUND_EXTENSIONS = List.of("wav", "aiff", "au");

    /**
     * Where a setting is kept, and how. {@code inverted} shows a "mute" preference as a "play" switch, and
     * {@code percent} a share kept as a fraction, such as a scale of 1.5, as a whole percentage.
     */
    private record Setting(IPreferenceStore store, String key, Kind kind, boolean inverted, boolean percent) { }

    private final List<Page> pages = new ArrayList<>();
    private final Map<String, Setting> settings = new LinkedHashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> started = new HashMap<>();

    /**
     * @param gui    the GUI preferences, {@link GUIPreferences#getInstance()} outside the tests
     * @param client the client preferences, {@link PreferenceManager#getClientPreferences()} outside the tests
     */
    public ClientSettingsForm(IPreferenceStore gui, ClientPreferences client) {
        pages.add(audio(gui));
        pages.add(display(gui, client));
        for (Map.Entry<String, Setting> entry : settings.entrySet()) {
            String value = read(entry.getValue());
            values.put(entry.getKey(), value);
            started.put(entry.getKey(), value);
        }
    }

    /** The form over the player's preferences; Swing thread. */
    public static ClientSettingsForm current() {
        return new ClientSettingsForm(GUIPreferences.getInstance(), PreferenceManager.getClientPreferences());
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

    private Section section(String page, String id, String key, Row... rows) {
        String title = Messages.getString(key + ".title");
        String summary = Messages.getString(key + ".summary");
        return new Section(page + '.' + id, title, summary, List.of(rows), searchText(title, summary));
    }

    private Row row(IPreferenceStore store, String key, Kind kind, boolean inverted, String labelKey, String helpKey,
          List<String> parameters) {
        return row(new Setting(store, key, kind, inverted, false), labelKey, helpKey, parameters, List.of());
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
            for (Setting setting : all) {
                String value = written.get(setting.key());
                if (value != null && !value.equals(read(setting))) {
                    write(setting, value);
                }
            }
        };
    }

    private Row row(String name) {
        return pages.stream().flatMap(page -> page.sections().stream()).flatMap(section -> section.rows().stream())
              .filter(row -> row.name().equals(name)).findFirst().orElseThrow();
    }

    private static String read(Setting setting) {
        IPreferenceStore store = setting.store();
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
        IPreferenceStore store = setting.store();
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
        IPreferenceStore store = setting.store();
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

    /** The language of a kept locale such as "de" or "en_US"; one MegaMek has no texts in reads as the default. */
    private static String language(String locale) {
        String language = locale.split("_", 2)[0].strip();
        return LOCALES.contains(language) ? language : LOCALES.getFirst();
    }
}
