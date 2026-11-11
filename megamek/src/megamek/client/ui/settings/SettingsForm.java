/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.settings;

import java.util.List;
import java.util.Set;

import jakarta.annotation.Nullable;

/**
 * A settings screen without a toolkit: its pages, grouped into categories, their titled sections of rows, the search
 * over them, and the values the rows edit. The Game Options and the Client Settings are both one; the native settings
 * view draws any of them.
 * <p>
 * A page without a category is a landing page: it shows at the top level and stays in the navigation while no search
 * runs, even when it has no rows of its own.
 */
public abstract class SettingsForm {

    /** How a row is edited. */
    public enum Kind {
        BOOLEAN, INTEGER, INTEGER_CHOICE, FLOAT, STRING, CHOICE,
        /**
         * A whole number on a slider; the row's values are its minimum, maximum and optionally its step, and its one
         * label, if any, the unit its reading shows.
         */
        SLIDER,
        /** A file's path; the row's values are the extensions it takes, without the dot. */
        FILE,
        /**
         * A ranking of items, first first: the row's values are the items' ids and its labels their names, and the text
         * is every id once, joined by commas.
         */
        ORDER,
        /**
         * A key and the modifiers held with it, as AWT keeps them, in the text {@link #key(int, int)} makes; "0,0" while
         * nothing is bound.
         */
        KEY,
        /** A colour, as its text {@code #rrggbb} in lower case. */
        COLOR,
        /** A folder's path. */
        FOLDER,
        /** A list of phrases, each once, as their text one per line. */
        LINES
    }

    /** @return the phrases of a {@link Kind#LINES} row's text: each line stripped, without blank or repeated ones */
    public static List<String> lines(@Nullable String text) {
        return text == null ? List.of()
              : text.lines().map(String::strip).filter(line -> !line.isEmpty()).distinct().toList();
    }

    /** @return the text of a {@link Kind#COLOR} row for the colour's red, green and blue */
    public static String color(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    /** @return the red, green and blue of a {@link Kind#COLOR} row's text, or -1 when it is no colour */
    public static int rgb(@Nullable String text) {
        String hex = text == null ? "" : text.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** The text of a {@link Kind#KEY} row: the AWT key code and modifier mask, "0,0" for none. */
    public static String key(int code, int modifiers) {
        return code == 0 ? "0,0" : code + "," + modifiers;
    }

    /** @return the AWT key code of a {@link Kind#KEY} row's text, 0 when unbound or unreadable */
    public static int keyCode(String text) {
        return keyPart(text, 0);
    }

    /** @return the AWT modifier mask of a {@link Kind#KEY} row's text */
    public static int keyModifiers(String text) {
        return keyPart(text, 1);
    }

    private static int keyPart(String text, int part) {
        String[] parts = text == null ? new String[0] : text.split(",", 2);
        try {
            return parts.length == 2 ? Integer.parseInt(parts[part].strip()) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * One setting on a page.
     *
     * @param name       the setting's stable name
     * @param label      its concise display name
     * @param tooltip    its details, the help the view shows for it
     * @param kind       how it is edited
     * @param values     the values a choice offers, in order (the numbers as text for an integer choice), a slider's
     *                   minimum and maximum, or a file's extensions
     * @param labels     the shown names of a choice's values, or a slider's unit
     * @param tags       the form's own marks for the row, such as the Game Options' important or unofficial rules
     * @param searchText the normalized text a search matches against
     */
    public record Row(String name, String label, String tooltip, Kind kind, List<String> values, List<String> labels,
          Set<String> tags, String searchText) {
        public Row {
            values = List.copyOf(values);
            labels = List.copyOf(labels);
            tags = Set.copyOf(tags);
        }

        /** @return whether the row carries the mark */
        public boolean tagged(String tag) {
            return tags.contains(tag);
        }

        /** @return a slider's minimum */
        public int min() {
            return Integer.parseInt(values.get(0));
        }

        /** @return a slider's maximum */
        public int max() {
            return Integer.parseInt(values.get(1));
        }

        /** @return the distance between a slider's values, 1 unless it has its own */
        public int step() {
            return values.size() > 2 ? Integer.parseInt(values.get(2)) : 1;
        }

        /** @return the unit a slider's reading shows after its value, such as "%", or blank */
        public String unit() {
            return labels.isEmpty() ? "" : labels.getFirst();
        }
    }

    /** A titled group of rows on a page. */
    public record Section(String id, String title, String summary, List<Row> rows, String searchText) {
    }

    /**
     * One settings page.
     *
     * @param categoryId    the navigation category, blank for a page shown at the top level
     * @param categoryTitle the category's title, blank without one
     * @param advanced      whether the page holds advanced settings
     */
    public record Page(String id, String title, String categoryId, String categoryTitle, boolean advanced,
          List<Section> sections, String searchText) {
        public boolean isLanding() {
            return categoryId.isBlank();
        }

        /** @return the navigation group the page belongs to: its category, or the page itself at the top level */
        public String group() {
            return isLanding() ? id : categoryId;
        }
    }

    private String search = "";

    // ---- structure and search

    /** @return every page, in navigation order */
    public abstract List<Page> pages();

    /** @return the page with the id, or null */
    public @Nullable Page page(String id) {
        return pages().stream().filter(page -> page.id().equals(id)).findFirst().orElse(null);
    }

    public String search() {
        return search;
    }

    public void search(String text) {
        search = text == null ? "" : text;
    }

    /** @return whether a search is active */
    public boolean searching() {
        return !SettingsRoute.normalizeSearchText(search).isBlank();
    }

    /** @return the rows of the section shown now: neither hidden by the form nor filtered out by the search */
    public List<Row> visibleRows(Page page, Section section) {
        String filter = SettingsRoute.normalizeSearchText(search);
        return section.rows().stream()
              .filter(this::shown)
              .filter(row -> matches(filter, page, section, row))
              .toList();
    }

    /** @return the sections of the page with any row shown now */
    public List<Section> visibleSections(Page page) {
        return page.sections().stream().filter(section -> !visibleRows(page, section).isEmpty()).toList();
    }

    /** @return the pages to navigate: with a row shown, and the landing pages unless a search is active */
    public List<Page> visiblePages() {
        return pages().stream()
              .filter(page -> (page.isLanding() && !searching()) || !visibleSections(page).isEmpty())
              .toList();
    }

    /** @return whether the row shows at all, before the search; every row does unless the form hides some */
    public boolean shown(Row row) {
        return true;
    }

    private static boolean matches(String filter, Page page, Section section, Row row) {
        if (filter.isBlank()) {
            return true;
        }
        String text = page.searchText() + ' ' + section.searchText() + ' ' + row.searchText();
        for (String token : filter.split("\\s+")) {
            if (!text.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** @return the normalized search text of the parts, for a row, section or page */
    protected static String searchText(String... parts) {
        return SettingsRoute.normalizeSearchText(String.join(" ", parts));
    }

    // ---- values

    /** @return whether the form's values can be changed at all */
    public boolean editable() {
        return true;
    }

    public abstract boolean bool(String name);

    /** Switches a boolean setting. */
    public abstract void bool(String name, boolean on);

    /** @return the text of any other kind of setting: a number, a choice's value, a text or a file's path */
    public abstract String text(String name);

    /** Sets the text of a setting that is not a boolean. */
    public abstract void text(String name, String text);

    /** @return whether the setting can be changed now */
    public abstract boolean editable(String name);

    /** @return whether the setting is kept as it is, as a scenario locks the game options it calls its mission */
    public boolean locked(String name) {
        return false;
    }

    /** @return the problem with the setting's text, or null when it is fine */
    public @Nullable String error(String name) {
        return null;
    }

    /** @return whether no setting's text has a problem */
    public boolean valid() {
        return pages().stream().flatMap(page -> page.sections().stream()).flatMap(section -> section.rows().stream())
              .allMatch(row -> error(row.name()) == null);
    }

    /** @return the setting's shipped value as its text, or null when the form keeps no default for it */
    public @Nullable String defaultText(String name) {
        return null;
    }

    /** @return whether the setting differs from the value the form started from */
    public abstract boolean changed(String name);

    /** @return whether any setting differs from the values the form started from */
    public boolean modified() {
        return pages().stream().flatMap(page -> page.sections().stream()).flatMap(section -> section.rows().stream())
              .anyMatch(row -> changed(row.name()));
    }

    /** Resets every setting to its default. */
    public abstract void defaults();
}
