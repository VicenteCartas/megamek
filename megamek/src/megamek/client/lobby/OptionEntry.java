/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import megamek.client.ui.Messages;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;

/**
 * One option of a unit as the unit bay edits it: a pilot ability, a unit or weapon quirk or a partial repair, read
 * from its {@link IOption} with its value, and written back as the Swing {@code DialogOptionComponentYPanel} rows of
 * {@code CustomMekDialog} write theirs.
 */
public final class OptionEntry {
    /** How the option is set. */
    public enum Kind {
        /** On or off. */
        BOOLEAN,
        /** A whole number, such as the edge points. */
        INTEGER,
        /** One of {@link #choices()}. */
        CHOICE,
        /** Several of {@link #LOCATIONS}, the directional torso mount quirks. */
        LOCATIONS,
        /** Free text. */
        TEXT
    }

    /** The locations a directional torso mount may face, as the Swing multi-select lists them. */
    public static final List<String> LOCATIONS = List.of("H", "LT", "RT", "CT");

    /** The highest whole number offered. */
    public static final int MAX_NUMBER = 99;

    private final String name;
    private final String label;
    private final String description;
    private final Kind kind;
    private final List<String> choices;
    private final Object initial;
    private final boolean dimmed;
    private Object value;

    private OptionEntry(IOption option, String suffix, List<String> choices, boolean dimmed) {
        name = option.getName();
        label = option.getDisplayableName() + suffix;
        description = Objects.requireNonNullElse(option.getDescription(), "");
        this.dimmed = dimmed;
        this.choices = List.copyOf(choices);
        if (isTorsoMount(name)) {
            kind = Kind.LOCATIONS;
            value = locationValue(parse(option.stringValue()));
        } else {
            kind = switch (option.getType()) {
                case IOption.BOOLEAN -> Kind.BOOLEAN;
                case IOption.INTEGER -> Kind.INTEGER;
                case IOption.CHOICE -> Kind.CHOICE;
                default -> Kind.TEXT;
            };
            value = switch (kind) {
                case BOOLEAN -> option.booleanValue();
                case INTEGER -> option.intValue();
                case CHOICE -> this.choices.contains(option.stringValue()) ? option.stringValue()
                      : (this.choices.isEmpty() ? "" : this.choices.get(0));
                default -> Objects.requireNonNullElse(option.stringValue(), "");
            };
        }
        initial = value;
    }

    /** An entry for {@code option}, its name followed by {@code suffix}, choosing from {@code choices} if any. */
    static OptionEntry of(IOption option, String suffix, List<String> choices, boolean dimmed) {
        return new OptionEntry(option, suffix, choices, dimmed);
    }

    private static boolean isTorsoMount(String name) {
        return name.equals(OptionsConstants.QUIRK_POS_DIRECTIONAL_TORSO_MOUNT)
              || name.equals(OptionsConstants.QUIRK_POS_DIRECTIONAL_TORSO_MOUNT_360);
    }

    private static Set<String> parse(String value) {
        Set<String> selected = new LinkedHashSet<>();
        if (value != null) {
            for (String token : value.split("[ ,]+")) {
                if (!token.isBlank()) {
                    selected.add(token.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return selected;
    }

    private static String locationValue(Set<String> selected) {
        List<String> ordered = new ArrayList<>();
        for (String location : LOCATIONS) {
            if (selected.contains(location)) {
                ordered.add(location);
            }
        }
        return String.join(" ", ordered);
    }

    /** The option's internal name. */
    public String name() {
        return name;
    }

    /** The option's name to show, with its implementation marker. */
    public String label() {
        return label;
    }

    /** The option's rules text, empty when there is none. */
    public String description() {
        return description;
    }

    /** How the option is set. */
    public Kind kind() {
        return kind;
    }

    /** The values of a {@link Kind#CHOICE} option. */
    public List<String> choices() {
        return choices;
    }

    /** Whether MegaMek gives the option no game effect, so it is shown subdued. */
    public boolean dimmed() {
        return dimmed;
    }

    /** Whether a {@link Kind#BOOLEAN} option is on. */
    public boolean on() {
        return Boolean.TRUE.equals(value);
    }

    void on(boolean on) {
        value = on;
    }

    /** A {@link Kind#INTEGER} option's value. */
    public int number() {
        return (value instanceof Integer number) ? number : 0;
    }

    /** Sets a {@link Kind#INTEGER} option, held within 0 and {@link #MAX_NUMBER}. */
    public void number(int number) {
        value = Math.max(0, Math.min(MAX_NUMBER, number));
    }

    /** The index of a {@link Kind#CHOICE} option's value in {@link #choices()}. */
    public int choice() {
        return Math.max(0, choices.indexOf(String.valueOf(value)));
    }

    /** Chooses a {@link Kind#CHOICE} option's value by its index. */
    public void choice(int index) {
        if (index >= 0 && index < choices.size()) {
            value = choices.get(index);
        }
    }

    /** Whether a {@link Kind#LOCATIONS} option faces {@code location}. */
    public boolean location(String location) {
        return parse(text()).contains(location);
    }

    /** Sets whether a {@link Kind#LOCATIONS} option faces {@code location}. */
    public void location(String location, boolean on) {
        Set<String> selected = parse(text());
        if (on) {
            selected.add(location);
        } else {
            selected.remove(location);
        }
        value = locationValue(selected);
    }

    /** A {@link Kind#TEXT} or {@link Kind#LOCATIONS} option's value. */
    public String text() {
        return String.valueOf(value);
    }

    /** Sets a {@link Kind#TEXT} option. */
    public void text(String text) {
        value = Objects.requireNonNullElse(text, "");
    }

    /** Whether the option is taken: on, a number, a choice other than none or some text. */
    public boolean isSet() {
        return switch (kind) {
            case BOOLEAN -> on();
            case INTEGER -> number() != 0;
            case CHOICE -> !text().isEmpty() && !text().equals(choices.isEmpty() ? "" : choices.get(0));
            default -> !text().isBlank();
        };
    }

    /** Whether the value differs from the unit's. */
    public boolean changed() {
        return !Objects.equals(initial, value);
    }

    /** Whether {@code filter}, in lower case, is found in the name or the rules text; a blank filter matches all. */
    public boolean matches(String filter) {
        String wanted = filter.trim().toLowerCase(Locale.ROOT);
        return wanted.isEmpty() || label.toLowerCase(Locale.ROOT).contains(wanted)
              || description.toLowerCase(Locale.ROOT).contains(wanted);
    }

    /** Writes the value to {@code option}, the "None" choice as {@code "None"}, as the Swing rows do. */
    void write(IOption option) {
        if (Messages.getString("CustomMekDialog.None").equals(value)) {
            option.setValue((Object) "None");
        } else {
            option.setValue(value);
        }
    }
}
