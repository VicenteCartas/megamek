/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.panels.VictoryOptionLayout;
import megamek.common.options.BasicOption;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.OptionsConstants;

/**
 * The victory options without a toolkit, as the lobby's victory conditions edit them, laid out as objective
 * cards: one card for each master switch (Battle Value destroyed, Battle Value ratio, turn limit, kill count,
 * objectives) holding the values that only count while it is on, and a General card for the rest. The number bounds
 * and the switch dependencies are {@link VictoryOptionLayout}'s, as in the Swing dialogs. OK sends the
 * {@link #changedOptions()} and saves {@link #allOptions()} as the last used game options.
 */
public final class VictoryConditionsForm {

    /** The name of the victory option group, which the lobby edits apart from the other game options. */
    public static final String GROUP = "victory";
    /** The General card's id. */
    public static final String GENERAL = "general";
    private static final int DEFAULT_MAX = VictoryOptionLayout.MAX_COUNT;

    /** How a field is edited. */
    public enum Kind {
        BOOLEAN, NUMBER
    }

    /**
     * One victory option.
     *
     * @param min the smallest number accepted, for a number
     * @param max the largest number accepted, for a number
     */
    public record Field(String name, String label, String tooltip, Kind kind, int min, int max) {
    }

    /**
     * One objective card.
     *
     * @param id     the card's stable id: its master switch's name, or {@link #GENERAL}
     * @param master the switch that arms the card's fields, or null for the General card
     * @param fields the fields it holds besides its switch
     * @param note   a note shown under the fields, blank for none
     */
    public record Card(String id, String title, @Nullable Field master, List<Field> fields, String note) {
    }

    private final GameOptions options;
    private final List<Card> cards;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, Object> initial = new HashMap<>();
    private String password = "";

    /** @param options the game's options, read but never changed */
    public VictoryConditionsForm(GameOptions options) {
        this.options = Objects.requireNonNull(options);
        cards = buildCards();
    }

    private List<Card> buildCards() {
        Map<String, String> dependsOn = VictoryOptionLayout.dependencies();
        List<IOption> group = new ArrayList<>();
        for (Enumeration<IOptionGroup> groups = options.getGroups(); groups.hasMoreElements(); ) {
            IOptionGroup optionGroup = groups.nextElement();
            if (GROUP.equals(optionGroup.getName())) {
                for (Enumeration<IOption> each = optionGroup.getOptions(); each.hasMoreElements(); ) {
                    group.add(each.nextElement());
                }
            }
        }
        Map<String, List<Field>> held = new LinkedHashMap<>();
        held.put(GENERAL, new ArrayList<>());
        for (IOption option : group) {
            Field field = field(option);
            fields.put(field.name(), field);
            Object value = (field.kind() == Kind.BOOLEAN) ? (Object) option.booleanValue() : option.intValue();
            values.put(field.name(), value);
            initial.put(field.name(), value);
            if (dependsOn.containsValue(field.name())) {
                held.putIfAbsent(field.name(), new ArrayList<>());
            } else if (dependsOn.containsKey(field.name())) {
                held.computeIfAbsent(dependsOn.get(field.name()), master -> new ArrayList<>()).add(field);
            } else {
                held.get(GENERAL).add(field);
            }
        }
        List<Card> built = new ArrayList<>();
        held.forEach((id, list) -> {
            Field master = fields.get(id);
            if (GENERAL.equals(id)) {
                if (!list.isEmpty()) {
                    built.add(new Card(id, Messages.getString("Lobby.victory.general"), null, List.copyOf(list), ""));
                }
            } else if (master != null) {
                String note = OptionsConstants.VICTORY_USE_OBJECTIVES.equals(id)
                      ? Messages.getString("VictoryConditionsDialog.objectivesNote") : "";
                built.add(new Card(id, master.label(), master, List.copyOf(list), note));
            }
        });
        return List.copyOf(built);
    }

    private static Field field(IOption option) {
        String label = option.getDisplayableName();
        if (label == null || label.isBlank()) {
            label = option.getName();
        }
        String tooltip = Objects.requireNonNullElse(option.getDescription(), "");
        if (option.getType() == IOption.BOOLEAN) {
            return new Field(option.getName(), label, tooltip, Kind.BOOLEAN, 0, 1);
        }
        int[] bounds = VictoryOptionLayout.boundsFor(option.getName());
        int min = (bounds == null) ? 0 : bounds[0];
        int max = (bounds == null) ? DEFAULT_MAX : bounds[1];
        return new Field(option.getName(), label, tooltip, Kind.NUMBER, min, max);
    }

    /** @return the cards, General first, then each master switch in option order */
    public List<Card> cards() {
        return cards;
    }

    /** @return the field of the option, or null when it is not a victory option */
    public @Nullable Field field(String name) {
        return fields.get(name);
    }

    public boolean bool(String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    public void bool(String name, boolean on) {
        Field field = fields.get(name);
        if (field != null && field.kind() == Kind.BOOLEAN && enabled(name)) {
            values.put(name, on);
        }
    }

    public int number(String name) {
        return (values.get(name) instanceof Integer number) ? number : 0;
    }

    /** Sets a number, kept within its bounds. */
    public void number(String name, int number) {
        Field field = fields.get(name);
        if (field != null && field.kind() == Kind.NUMBER && enabled(name)) {
            values.put(name, Math.clamp(number, field.min(), field.max()));
        }
    }

    /** @return whether the option takes effect now: it has no master switch or its switch is on */
    public boolean enabled(String name) {
        String master = VictoryOptionLayout.dependencies().get(name);
        return master == null || bool(master);
    }

    /** @return whether the card counts towards victory: the General card always, any other while its switch is on */
    public boolean active(Card card) {
        return card.master() == null || bool(card.master().name());
    }

    /** @return the card's fields with their values in one line, for the card's face, or "Off" */
    public String summary(Card card) {
        if (!active(card)) {
            return Messages.getString("Lobby.victory.off");
        }
        List<String> parts = new ArrayList<>();
        for (Field field : card.fields()) {
            if (field.kind() == Kind.NUMBER) {
                parts.add(String.valueOf(number(field.name())));
            } else if (bool(field.name())) {
                parts.add(field.label());
            }
        }
        return parts.isEmpty() ? Messages.getString("Lobby.victory.on") : String.join(" \u00B7 ", parts);
    }

    /** @return how many cards count towards victory, the General card not counted */
    public int activeObjectives() {
        int count = 0;
        for (Card card : cards) {
            if (card.master() != null && active(card)) {
                count++;
            }
        }
        return count;
    }

    public boolean changed(String name) {
        return !Objects.equals(values.get(name), initial.get(name));
    }

    public boolean modified() {
        for (String name : fields.keySet()) {
            if (changed(name)) {
                return true;
            }
        }
        return false;
    }

    /** @return the options changed here, to send to the server; empty when nothing changed */
    public Vector<IBasicOption> changedOptions() {
        Vector<IBasicOption> changed = new Vector<>();
        for (String name : fields.keySet()) {
            if (changed(name)) {
                changed.add(new BasicOption(name, values.get(name)));
            }
        }
        return changed;
    }

    /** @return every game option, the victory ones as edited here, to save as the last used options */
    public Vector<IBasicOption> allOptions() {
        Vector<IBasicOption> all = new Vector<>();
        for (Enumeration<IOptionGroup> groups = options.getGroups(); groups.hasMoreElements(); ) {
            for (Enumeration<IOption> each = groups.nextElement().getOptions(); each.hasMoreElements(); ) {
                IOption option = each.nextElement();
                Object value = values.containsKey(option.getName()) ? values.get(option.getName()) : option.getValue();
                all.add(new BasicOption(option.getName(), value));
            }
        }
        return all;
    }

    /** @return the server password typed, which the server checks only when it has one */
    public String password() {
        return password;
    }

    public void password(String text) {
        password = Objects.requireNonNullElse(text, "");
    }
}
