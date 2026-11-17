/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.advancedsearch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;

/**
 * The saved advanced searches on disk: {@link AdvSearchState} JSON files, by default in {@link #SEARCH_FOLDER}, and the
 * list of the recently used ones in {@link #RECENT_SEARCH_STORE}. The unit selector's dialog and the lobby's advanced
 * search share them, so a search saved in one loads in the other.
 */
public final class AdvancedSearchFiles {
    public static final String SEARCH_FOLDER = "mmconf/searches";
    public static final String RECENT_SEARCH_STORE = "mmconf/recent-advanced-searches.json";

    static final ObjectMapper MAPPER = JsonMapper.builder()
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

    private AdvancedSearchFiles() { }

    /**
     * {@code name} without the characters a file name may not hold, or {@code null} when nothing is left.
     *
     * @throws IllegalArgumentException when the name is {@code null} or blank
     */
    public static @Nullable String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Cannot create a filename from a null or empty name");
        }
        String sanitized = name;
        sanitized = sanitized.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "");
        sanitized = sanitized.replaceAll("[. ]+$", "");
        sanitized = sanitized.replaceAll("^_|_$", "");
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized;
    }

    /** Whether {@code file} is a saved advanced search. */
    public static boolean isAdvancedSearchFile(File file) {
        try {
            JsonNode root = MAPPER.readTree(file);
            JsonNode contentNode = (root == null) ? null : root.get("content");
            return contentNode != null && contentNode.asText().equals(AdvSearchState.CONTENT);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The search saved in {@code file}.
     *
     * @throws IllegalArgumentException when the file is not a saved advanced search
     */
    public static AdvSearchState load(File file) throws IOException {
        if (!isAdvancedSearchFile(file)) {
            throw new IllegalArgumentException("The specified file is not an advanced search file.");
        }
        return MAPPER.readValue(file, AdvSearchState.class);
    }

    public static void save(File file, AdvSearchState state) throws IOException {
        MAPPER.writeValue(file, state);
    }

    /** The search's name as saved in {@code file}, or {@code null} when it has none or cannot be read. */
    public static @Nullable String readName(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            JsonNode nameNode = (root == null) ? null : root.get("name");
            return nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Writes the search in {@code from} to {@code to} under {@code name}, keeping everything else it holds, even what
     * the reader of this version does not know.
     */
    public static void copyRenamed(Path from, Path to, String name) throws IOException {
        if (!(MAPPER.readTree(from.toFile()) instanceof ObjectNode root)) {
            throw new IllegalArgumentException("The specified file is not an advanced search file.");
        }
        root.put("name", name);
        MAPPER.writeValue(to.toFile(), root);
    }

    /** Whether {@code substate} holds nothing but default values, so it is not saved. */
    public static boolean isDefault(Object substate) {
        JsonNode node = MAPPER.valueToTree(substate);
        return (node == null) || node.isEmpty();
    }

    /** The recently used search files in {@code store}, the most recent first. */
    public static List<Path> recentFiles(Path store) throws IOException {
        return new RecentFilesStore(store).getRecentFiles();
    }

    /** Moves {@code file} to the top of the recently used search files in {@code store}. */
    public static void touch(Path store, Path file) throws IOException {
        new RecentFilesStore(store).touch(file);
    }
}
