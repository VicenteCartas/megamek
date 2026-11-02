/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.Nullable;
import megamek.client.generator.RandomUnitGenerator.RatTreeNode;

/**
 * The random assignment tables as a browser, without a toolkit: thousands of tables in folders by source, faction,
 * command and unit type, opened a folder at a time, or searched across every table by words of its name and folders.
 * The random army shows it beside the dealt hand instead of one list of every table.
 */
public final class RatBrowser {

    /** The most search results shown; {@link #matches()} counts them all. */
    public static final int SEARCH_LIMIT = 80;

    private static final Pattern ERA = Pattern.compile("^\\(([^)]*)\\)\\s*-?\\s*(.+)$");
    private static final Pattern YEARS = Pattern.compile("(\\d{4})(?:\\D+(\\d{4}))?");
    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    /** A folder of tables and folders. */
    public record Folder(String name, List<Folder> folders, List<String> tables) {
        public Folder {
            folders = List.copyOf(folders);
            tables = List.copyOf(tables);
        }
    }

    /**
     * One row: a folder, with how many tables it holds in all, or a table; {@code era} is a folder's leading years
     * ("2750–2765"), {@code title} its name without them, and {@code path} where a search result lives.
     */
    public record Entry(String name, String title, @Nullable String era, boolean folder, int tables, String path) { }

    private record Indexed(String table, String path, String haystack, List<Folder> trail, int from, int to) { }

    private final Folder root;
    private final Map<Folder, Integer> counts = new IdentityHashMap<>();
    private final List<Indexed> index = new ArrayList<>();
    private final List<Folder> trail = new ArrayList<>();
    private final List<Entry> results = new ArrayList<>();
    private String query = "";
    private int matches;

    public RatBrowser(Folder root) {
        this.root = root;
        count(root);
        index(root, new ArrayList<>());
    }

    /** The generator's tree as folders: a node without children is a table, as empty folders are pruned. */
    public static Folder of(RatTreeNode node) {
        List<Folder> folders = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        for (RatTreeNode child : node.children) {
            if (child.children.isEmpty()) {
                tables.add(child.name);
            } else {
                folders.add(of(child));
            }
        }
        return new Folder(node.name, folders, tables);
    }

    private int count(Folder folder) {
        int total = folder.tables().size();
        for (Folder child : folder.folders()) {
            total += count(child);
        }
        counts.put(folder, total);
        return total;
    }

    private void index(Folder folder, List<Folder> path) {
        String where = pathText(path);
        int[] years = years(path);
        StringBuilder names = new StringBuilder();
        for (Folder step : path) {
            names.append(' ').append(step.name());
        }
        for (String table : folder.tables()) {
            index.add(new Indexed(table, where, (table + names).toLowerCase(Locale.ROOT), List.copyOf(path),
                  years[0], years[1]));
        }
        for (Folder child : folder.folders()) {
            path.add(child);
            index(child, path);
            path.remove(path.size() - 1);
        }
    }

    /** The years of the innermost folder on {@code path} that has some: "3076–3100", "3130 on" or "3039". */
    private static int[] years(List<Folder> path) {
        for (int at = path.size() - 1; at >= 0; at--) {
            String era = split(path.get(at).name())[0];
            if (era != null) {
                Matcher matcher = YEARS.matcher(era);
                if (matcher.find()) {
                    int from = Integer.parseInt(matcher.group(1));
                    int to = matcher.group(2) != null ? Integer.parseInt(matcher.group(2))
                          : era.contains("on") ? Integer.MAX_VALUE : from;
                    return new int[] { from, to };
                }
            }
        }
        return new int[] { -1, -1 };
    }

    private static String pathText(List<Folder> path) {
        List<String> names = new ArrayList<>();
        for (Folder folder : path) {
            names.add(split(folder.name())[1]);
        }
        return String.join(" \u203A ", names);
    }

    /** A folder's name split into its leading years, or null, and the rest: "(3039) - War of 39" gives 3039. */
    static String[] split(String name) {
        Matcher matcher = ERA.matcher(name);
        if (matcher.matches()) {
            String years = matcher.group(1).trim().replaceAll("\\s*-\\s*", "\u2013");
            return new String[] { years, matcher.group(2).trim() };
        }
        return new String[] { null, name };
    }

    /** How many tables there are in all. */
    public int size() {
        return counts.get(root);
    }

    /** The open folders' names from the top, empty at the top. */
    public List<String> path() {
        return trail.stream().map(folder -> split(folder.name())[1]).toList();
    }

    /** Whether a folder is open, so {@link #up()} has somewhere to go. */
    public boolean canGoUp() {
        return !trail.isEmpty();
    }

    /** Opens the shown folder named {@code name}; a search ends, as the folder is browsed. */
    public void open(String name) {
        for (Folder folder : current().folders()) {
            if (folder.name().equals(name)) {
                trail.add(folder);
                query = "";
                return;
            }
        }
    }

    /** Goes back to the folder holding the open one. */
    public void up() {
        if (!trail.isEmpty()) {
            trail.remove(trail.size() - 1);
        }
    }

    /** Goes back to the top. */
    public void top() {
        trail.clear();
        query = "";
    }

    /** Opens the folder that holds {@code table}; false when no folder does. */
    public boolean locate(@Nullable String table) {
        for (Indexed indexed : index) {
            if (indexed.table().equals(table)) {
                trail.clear();
                trail.addAll(indexed.trail());
                query = "";
                return true;
            }
        }
        return false;
    }

    public String query() {
        return query;
    }

    /**
     * Searches every table for all the words of {@code text}, in its name or its folders'; a year such as 3067 also
     * matches the tables of a source whose years cover it. Blank browses again.
     */
    public void search(String text) {
        query = text == null ? "" : text.strip();
        results.clear();
        matches = 0;
        if (query.isEmpty()) {
            return;
        }
        String[] words = query.toLowerCase(Locale.ROOT).split("\\s+");
        for (Indexed indexed : index) {
            if (matchesAll(indexed, words)) {
                matches++;
                if (results.size() < SEARCH_LIMIT) {
                    results.add(new Entry(indexed.table(), indexed.table(), null, false, 1, indexed.path()));
                }
            }
        }
    }

    /** Whether the rows are search results rather than a folder's contents. */
    public boolean searching() {
        return !query.isEmpty();
    }

    /** How many tables the search found, of which the rows show at most {@link #SEARCH_LIMIT}. */
    public int matches() {
        return searching() ? matches : 0;
    }

    /** The rows: the open folder's folders then its tables, or the search's results with their folders. */
    public List<Entry> entries() {
        if (searching()) {
            return List.copyOf(results);
        }
        List<Entry> entries = new ArrayList<>();
        Folder folder = current();
        String where = pathText(trail);
        for (Folder child : folder.folders()) {
            String[] parts = split(child.name());
            entries.add(new Entry(child.name(), parts[1], parts[0], true, counts.get(child), where));
        }
        for (String table : folder.tables()) {
            entries.add(new Entry(table, table, null, false, 1, where));
        }
        return entries;
    }

    /** Every word is in the table's name or folders; a year also matches a source whose years cover it. */
    private static boolean matchesAll(Indexed indexed, String[] words) {
        for (String word : words) {
            if (!indexed.haystack().contains(word) && !(YEAR.matcher(word).matches()
                  && indexed.from() >= 0 && Integer.parseInt(word) >= indexed.from()
                  && Integer.parseInt(word) <= indexed.to())) {
                return false;
            }
        }
        return true;
    }

    /** Where {@code table} lives, as "Source › Faction › …", or empty when it is not in any folder. */
    public String pathOf(@Nullable String table) {
        for (Indexed indexed : index) {
            if (indexed.table().equals(table)) {
                return indexed.path();
            }
        }
        return "";
    }

    private Folder current() {
        return trail.isEmpty() ? root : trail.get(trail.size() - 1);
    }
}
