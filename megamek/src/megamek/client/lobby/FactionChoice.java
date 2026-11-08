/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.FactionRecord;

/**
 * The year, faction, command and equipment rating a RAT Generator table or a formation is built for, without a
 * toolkit, as the Swing {@code ForceGenerationOptionsPanel} offers them: the factions active in the year, major ones
 * unless minor ones are asked for; the commands of the faction active then; the faction's ratings, best first.
 */
public final class FactionChoice {

    public static final int EARLIEST_YEAR = 2398;
    public static final int LATEST_YEAR = 3160;
    /** The faction chosen when the one asked for is not offered: the Inner Sphere at large. */
    public static final String DEFAULT_FACTION = "IS";

    private final List<FactionRecord> records;
    private int year;
    private boolean showMinor;
    private List<FactionRecord> factions = List.of();
    private List<FactionRecord> commands = List.of();
    private List<String> ratings = List.of();
    private @Nullable FactionRecord faction;
    private @Nullable FactionRecord command;
    private @Nullable String rating;

    /** @param records every faction and command there is, as the RAT Generator lists them */
    public FactionChoice(List<FactionRecord> records, int year) {
        this.records = List.copyOf(records);
        this.year = Math.clamp(year, EARLIEST_YEAR, LATEST_YEAR);
        refreshFactions();
    }

    public int year() {
        return year;
    }

    /** Moves to {@code year}, keeping the faction and command when they are still active then. */
    public void year(int year) {
        int clamped = Math.clamp(year, EARLIEST_YEAR, LATEST_YEAR);
        if (clamped != this.year) {
            this.year = clamped;
            refreshFactions();
        }
    }

    public boolean showMinor() {
        return showMinor;
    }

    public void showMinor(boolean showMinor) {
        if (showMinor != this.showMinor) {
            this.showMinor = showMinor;
            refreshFactions();
        }
    }

    /** The factions offered, by their name in the year. */
    public List<FactionRecord> factions() {
        return factions;
    }

    public @Nullable FactionRecord faction() {
        return faction;
    }

    /** Chooses the faction with {@code key} when it is offered; its commands and ratings follow. */
    public void faction(String key) {
        FactionRecord found = find(factions, key);
        if (found != null && found != faction) {
            faction = found;
            command = null;
            refreshCommands();
        }
    }

    /** The commands of the faction offered, by name; none is the faction's general command. */
    public List<FactionRecord> commands() {
        return commands;
    }

    public @Nullable FactionRecord command() {
        return command;
    }

    /** Chooses the command with {@code key}, or the general command for null or a key not offered. */
    public void command(@Nullable String key) {
        FactionRecord found = key == null ? null : find(commands, key);
        if (found != command) {
            command = found;
            refreshRatings();
        }
    }

    /** The ratings offered, best first; none when the faction has a single rating or none. */
    public List<String> ratings() {
        return ratings;
    }

    public @Nullable String rating() {
        return rating;
    }

    public void rating(String rating) {
        if (ratings.contains(rating)) {
            this.rating = rating;
        }
    }

    /** The faction or command a table is built for: the command, or the faction for its general command. */
    public @Nullable FactionRecord record() {
        return command != null ? command : faction;
    }

    /** The key of {@link #record()}, as the RAT Generator takes it, or null when no faction is offered. */
    public @Nullable String key() {
        FactionRecord record = record();
        return record == null ? null : record.getKey();
    }

    private void refreshFactions() {
        String old = faction == null ? DEFAULT_FACTION : faction.getKey();
        List<FactionRecord> offered = new ArrayList<>();
        for (FactionRecord record : records) {
            if ((showMinor || !record.isMinor()) && !record.getKey().contains(".") && record.isActiveInYear(year)) {
                offered.add(record);
            }
        }
        offered.sort(Comparator.comparing(record -> record.getName(year)));
        factions = List.copyOf(offered);
        FactionRecord kept = find(factions, old);
        if (kept == null) {
            kept = find(factions, DEFAULT_FACTION);
        }
        if (kept == null && !factions.isEmpty()) {
            kept = factions.get(0);
        }
        if (kept != faction) {
            faction = kept;
        }
        refreshCommands();
    }

    private void refreshCommands() {
        String old = command == null ? null : command.getKey();
        List<FactionRecord> offered = new ArrayList<>();
        if (faction != null) {
            String prefix = faction.getKey() + ".";
            for (FactionRecord record : records) {
                if (record.getKey().startsWith(prefix) && record.isActiveInYear(year)) {
                    offered.add(record);
                }
            }
            offered.sort(Comparator.comparing(record -> record.getName(year)));
        }
        commands = List.copyOf(offered);
        command = old == null ? null : find(commands, old);
        refreshRatings();
    }

    private void refreshRatings() {
        FactionRecord record = record();
        List<String> levels = List.of();
        if (record != null) {
            levels = record.getRatingLevels();
            if (levels.isEmpty() && !record.getParentFactions().isEmpty()) {
                levels = record.getRatingLevelSystem();
            }
        }
        List<String> offered = new ArrayList<>();
        if (levels.size() > 1) {
            for (int i = levels.size() - 1; i >= 0; i--) {
                offered.add(levels.get(i));
            }
        }
        ratings = List.copyOf(offered);
        int index = rating == null ? -1 : ratings.indexOf(rating);
        rating = ratings.isEmpty() ? null : ratings.get(Math.max(0, index));
    }

    private static @Nullable FactionRecord find(List<FactionRecord> records, @Nullable String key) {
        for (FactionRecord record : records) {
            if (Objects.equals(record.getKey(), key)) {
                return record;
            }
        }
        return null;
    }
}
