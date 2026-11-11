/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.function.Predicate;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.panels.GameOptionsCatalog;
import megamek.client.ui.panels.GameOptionsPresentation;
import megamek.client.ui.settings.SettingsForm;
import megamek.client.ui.settings.SettingsRoute;
import megamek.codeUtilities.MathUtility;
import megamek.common.options.BasicOption;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.options.IOptionGroup;
import megamek.common.options.OptionsConstants;

/**
 * The game options without a toolkit, as the Swing {@code GameOptionsDialog} edits them in the lobby: every option of
 * the game's groups (less the groups the caller skips, the lobby's victory group) placed on the settings pages of
 * {@link GameOptionsPresentation}, with the dialog's search, the unofficial and legacy rule toggles, the editability
 * rules between options (recomputed from the current values), the clean-ups a switch makes in its dependants, the
 * Core/Total Warfare rules system, defaults, values loaded from a file and the changed options to send.
 */
public final class GameOptionsForm extends SettingsForm {
    /** The tag of a row carrying the important marker. */
    public static final String IMPORTANT = "important";
    /** The tag of an unofficial rule, hidden while the unofficial rules are. */
    public static final String UNOFFICIAL = "unofficial";
    /** The tag of a legacy rule, hidden while the legacy rules are. */
    public static final String LEGACY = "legacy";

    private final GameOptions options;
    private final boolean editable;
    private final List<Page> pages;
    private final Map<String, IOption> represented = new LinkedHashMap<>();
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final Set<String> skippedGroups;
    private final Set<String> locked = new HashSet<>();
    private String search = "";
    private String password = "";
    private Runnable grabBarsChanged = () -> {
    };

    /**
     * @param options       the game's options, read but never changed
     * @param skippedGroups the option groups not edited here (the lobby's victory group)
     * @param editable      whether anything can be changed; the lobby always edits
     */
    public GameOptionsForm(GameOptions options, Set<String> skippedGroups, boolean editable) {
        this.options = options;
        this.editable = editable;
        this.skippedGroups = Set.copyOf(skippedGroups);
        pages = buildPages();
        represented.values().forEach(this::readValue);
    }

    /** Called when the BattleArmor grab bars option switches, to refit the game's units as the Swing dialog does. */
    public void onGrabBarsChanged(Runnable listener) {
        grabBarsChanged = Objects.requireNonNull(listener);
    }

    public boolean editable() {
        return editable;
    }

    /**
     * Keeps options as they are, as a scenario locks the options it calls its mission: they show but can't be
     * changed, not even by Defaults, a loaded file or another option that would clear them.
     */
    public void lock(Set<String> names) {
        locked.addAll(names);
    }

    /** @return whether the option is locked by {@link #lock} */
    public boolean locked(String name) {
        return locked.contains(name);
    }

    // ---- structure

    @Override
    public List<Page> pages() {
        return pages;
    }

    /** @return whether the row's rule category is shown */
    @Override
    public boolean shown(Row row) {
        return !(row.tagged(UNOFFICIAL) && !unofficialShown()) && !(row.tagged(LEGACY) && !legacyShown());
    }

    // ---- values

    /** @return the option's current value: a Boolean, or text for every other kind */
    public Object value(String name) {
        IOption option = represented.get(name);
        if (option == null) {
            throw new IllegalArgumentException("Not an option of this form: " + name);
        }
        return option.getType() == IOption.BOOLEAN ? values.get(name) : texts.get(name);
    }

    @Override
    public boolean bool(String name) {
        return Boolean.TRUE.equals(values.get(name));
    }

    @Override
    public String text(String name) {
        return texts.getOrDefault(name, "");
    }

    /** Switches a boolean option and clears what only applies without it, as the Swing dialog's clicks do. */
    @Override
    public void bool(String name, boolean on) {
        IOption option = represented.get(name);
        if (option == null || option.getType() != IOption.BOOLEAN || bool(name) == on || locked(name)) {
            return;
        }
        values.put(name, on);
        switched(name, on);
    }

    /** Sets the text of a number, text or choice option. */
    @Override
    public void text(String name, String text) {
        IOption option = represented.get(name);
        if (option == null || option.getType() == IOption.BOOLEAN || locked(name)) {
            return;
        }
        texts.put(name, text == null ? "" : text);
    }

    /** @return whether the option can be changed now */
    @Override
    public boolean editable(String name) {
        if (!editable || !represented.containsKey(name) || locked(name)) {
            return false;
        }
        boolean indiv = bool(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE);
        boolean rule = switch (name) {
            case OptionsConstants.INIT_INF_DEPLOY_EVEN -> !indiv && bool(OptionsConstants.INIT_INF_MOVE_EVEN);
            case OptionsConstants.INIT_INF_MOVE_MULTI -> !indiv && noneOf(OptionsConstants.INIT_INF_MOVE_EVEN,
                  OptionsConstants.INIT_INF_MOVE_LATER);
            case OptionsConstants.INIT_INF_MOVE_EVEN -> !indiv && noneOf(OptionsConstants.INIT_INF_MOVE_MULTI,
                  OptionsConstants.INIT_INF_MOVE_LATER);
            case OptionsConstants.INIT_INF_MOVE_LATER -> !indiv && noneOf(OptionsConstants.INIT_INF_MOVE_EVEN,
                  OptionsConstants.INIT_INF_MOVE_MULTI);
            case OptionsConstants.INIT_PROTOMEKS_MOVE_MULTI -> !indiv
                  && noneOf(OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN, OptionsConstants.INIT_PROTOMEKS_MOVE_LATER);
            case OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN -> !indiv
                  && noneOf(OptionsConstants.INIT_PROTOMEKS_MOVE_MULTI, OptionsConstants.INIT_PROTOMEKS_MOVE_LATER);
            case OptionsConstants.INIT_PROTOMEKS_MOVE_LATER -> !indiv
                  && noneOf(OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN, OptionsConstants.INIT_PROTOMEKS_MOVE_MULTI);
            case OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_FALLING_EXPANDED ->
                  bool(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1 ->
                  !bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES ->
                  !bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS_RANGE ->
                  bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RANGE);
            case OptionsConstants.ADVANCED_COMBAT_KIND_RAPID_AC -> bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RAPID_AC);
            case OptionsConstants.ADVANCED_ALTERNATE_MASC_ENHANCED -> bool(OptionsConstants.ADVANCED_ALTERNATE_MASC);
            case OptionsConstants.ADVANCED_GHOST_TARGET_MODE -> bool(OptionsConstants.ADVANCED_TAC_OPS_GHOST_TARGET);
            case OptionsConstants.ADVANCED_GHOST_TARGET_MAX -> bool(OptionsConstants.ADVANCED_TAC_OPS_GHOST_TARGET)
                  && OptionsConstants.GHOST_TARGET_MODE_LEGACY.equals(text(OptionsConstants.ADVANCED_GHOST_TARGET_MODE));
            case OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT,
                 OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT -> !indiv;
            case OptionsConstants.RPG_INDIVIDUAL_INITIATIVE -> noneOf(
                  OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT,
                  OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT);
            case "fire" -> !bool("vacuum");
            default -> true;
        };
        List<String> controllers = GameOptionsCatalog.editableWhenAny().get(name);
        if (controllers != null && controllers.stream().allMatch(represented::containsKey)
              && controllers.stream().noneMatch(this::bool)) {
            return false;
        }
        if (GameOptionsCatalog.CORE_RULES_DISABLED_OPTIONS.contains(name) && !totalWarfare()) {
            return false;
        }
        return rule;
    }

    private boolean noneOf(String... names) {
        for (String name : names) {
            if (bool(name)) {
                return false;
            }
        }
        return true;
    }

    /** @return whether the Total Warfare rules system is chosen, which enables the options Core rules override */
    public boolean totalWarfare() {
        Object rules = represented.containsKey(OptionsConstants.RULES_SYSTEM)
              ? text(OptionsConstants.RULES_SYSTEM)
              : valueOf(options.getOption(OptionsConstants.RULES_SYSTEM));
        return OptionsConstants.RULES_TW.equals(GameOptionsCatalog.normalizeRulesSystem(rules));
    }

    /** @return the guidance shown under the rules system choice for the chosen system */
    public String rulesSystemDescription() {
        return GameOptionsCatalog.text(GameOptionsCatalog.rulesSystemDescriptionKey(
              represented.containsKey(OptionsConstants.RULES_SYSTEM) ? text(OptionsConstants.RULES_SYSTEM) : null));
    }

    /** @return the problem with the option's text, or null when it is fine (booleans and choices always are) */
    @Override
    public @Nullable String error(String name) {
        IOption option = represented.get(name);
        if (option == null) {
            return null;
        }
        String text = text(name).strip();
        if (option.getType() == IOption.INTEGER && GameOptionsCatalog.integerChoices(name).isEmpty()) {
            Integer number = parseInteger(text);
            if (number == null) {
                return Messages.getString("Lobby.settings.wholeNumber");
            }
            int[] bounds = GameOptionsCatalog.integerBounds(name);
            if (bounds != null && (number < bounds[0] || number > bounds[1])) {
                return bounds[1] == Integer.MAX_VALUE
                      ? Messages.getString("Lobby.gameOptions.atLeast", String.valueOf(bounds[0]))
                      : Messages.getString("Lobby.gameOptions.between", String.valueOf(bounds[0]),
                            String.valueOf(bounds[1]));
            }
        } else if (option.getType() == IOption.FLOAT && !text.isEmpty() && parseFloat(text) == null) {
            return Messages.getString("Lobby.gameOptions.number");
        }
        return null;
    }

    /** @return whether no option's text has a problem */
    @Override
    public boolean valid() {
        return represented.keySet().stream().allMatch(name -> error(name) == null);
    }

    /** @return whether the option differs from the game's value */
    @Override
    public boolean changed(String name) {
        IOption option = represented.get(name);
        return option != null && !Objects.equals(option.getValue(), typedValue(option));
    }

    /** @return whether any option differs from the game's values */
    @Override
    public boolean modified() {
        return represented.keySet().stream().anyMatch(this::changed);
    }

    /** @return the options to send: those that differ from the game's values */
    public Vector<IBasicOption> changedOptions() {
        Vector<IBasicOption> changed = new Vector<>();
        for (IOption option : represented.values()) {
            if (changed(option.getName())) {
                changed.add(new BasicOption(option.getName(), typedValue(option)));
            }
        }
        return changed;
    }

    /** @return every option with the form's values, and the game's values for those not edited here, to save */
    public Vector<IBasicOption> allOptions() {
        Vector<IBasicOption> all = new Vector<>();
        for (IOption option : represented.values()) {
            all.add(new BasicOption(option.getName(), typedValue(option)));
        }
        for (Enumeration<IOptionGroup> groups = options.getGroups(); groups.hasMoreElements(); ) {
            for (Enumeration<IOption> groupOptions = groups.nextElement().getOptions();
                  groupOptions.hasMoreElements(); ) {
                IOption option = groupOptions.nextElement();
                if (!represented.containsKey(option.getName())) {
                    all.add(option);
                }
            }
        }
        return all;
    }

    /** Resets every option to its default, keeping the rule toggles in step. */
    @Override
    public void defaults() {
        for (IOption option : represented.values()) {
            if (locked(option.getName())) {
                continue;
            }
            if (option.getType() == IOption.BOOLEAN) {
                values.put(option.getName(), Boolean.TRUE.equals(option.getDefault()));
            } else {
                texts.put(option.getName(), String.valueOf(option.getDefault()));
            }
        }
    }

    /**
     * Takes the values of options loaded from a file. Unlike the Swing dialog this never touches the game's options:
     * the caller loads the file into a copy, and the values that differ are sent on OK.
     */
    public void load(GameOptions loaded) {
        for (IOption option : represented.values()) {
            IOption source = loaded.getOption(option.getName());
            if (source == null || locked(option.getName())) {
                continue;
            }
            if (option.getType() == IOption.BOOLEAN) {
                values.put(option.getName(), source.booleanValue());
            } else {
                texts.put(option.getName(), textOf(option, source.getValue()));
            }
        }
    }

    /** @return a copy of the game's options to load a file into */
    public GameOptions copyOfOptions() {
        GameOptions copy = new GameOptions();
        for (IOption option : options.getOptionsList()) {
            IOption target = copy.getOption(option.getName());
            if (target != null) {
                target.setValue(option.getValue());
            }
        }
        return copy;
    }

    public String password() {
        return password;
    }

    public void password(String text) {
        password = text == null ? "" : text;
    }

    // ---- unofficial and legacy rules

    /** @return whether the unofficial rules can be shown or hidden here */
    public boolean hasUnofficialToggle() {
        return represented.containsKey(OptionsConstants.BASE_HIDE_UNOFFICIAL);
    }

    /** @return whether the legacy rules can be shown or hidden here */
    public boolean hasLegacyToggle() {
        return represented.containsKey(OptionsConstants.BASE_HIDE_LEGACY);
    }

    public boolean unofficialShown() {
        return !hasUnofficialToggle() || !bool(OptionsConstants.BASE_HIDE_UNOFFICIAL);
    }

    public boolean legacyShown() {
        return !hasLegacyToggle() || !bool(OptionsConstants.BASE_HIDE_LEGACY);
    }

    /** Shows or hides the unofficial rules; hiding (which needs the player's confirmation) also turns them off. */
    public void unofficialShown(boolean shown) {
        categoryShown(OptionsConstants.BASE_HIDE_UNOFFICIAL, shown, GameOptionsCatalog::isUnofficial);
    }

    /** Shows or hides the legacy rules; hiding (which needs the player's confirmation) also turns them off. */
    public void legacyShown(boolean shown) {
        categoryShown(OptionsConstants.BASE_HIDE_LEGACY, shown, GameOptionsCatalog::isLegacy);
    }

    private void categoryShown(String hideOption, boolean shown, Predicate<String> category) {
        if (!represented.containsKey(hideOption) || locked(hideOption)) {
            return;
        }
        bool(hideOption, !shown);
        if (!shown) {
            for (IOption option : represented.values()) {
                if (!category.test(option.getName())) {
                    continue;
                }
                if (option.getType() == IOption.BOOLEAN) {
                    bool(option.getName(), false);
                } else if (OptionsConstants.ADVANCED_GHOST_TARGET_MODE.equals(option.getName())) {
                    text(option.getName(), OptionsConstants.GHOST_TARGET_MODE_STANDARD);
                }
            }
        }
    }

    // ---- internals

    private void switched(String name, boolean on) {
        switch (name) {
            case OptionsConstants.INIT_INF_MOVE_EVEN -> clear(OptionsConstants.INIT_INF_DEPLOY_EVEN);
            case OptionsConstants.RPG_INDIVIDUAL_INITIATIVE -> clear(OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN,
                  OptionsConstants.INIT_PROTOMEKS_MOVE_MULTI, OptionsConstants.INIT_PROTOMEKS_MOVE_LATER,
                  OptionsConstants.INIT_INF_DEPLOY_EVEN, OptionsConstants.INIT_INF_MOVE_EVEN,
                  OptionsConstants.INIT_INF_MOVE_MULTI, OptionsConstants.INIT_INF_MOVE_LATER,
                  OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT,
                  OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT);
            case OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT,
                 OptionsConstants.ADVANCED_GROUND_MOVEMENT_VEHICLE_LANCE_MOVEMENT ->
                  clear(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE);
            case "vacuum" -> clear("fire");
            case OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN ->
                  clear(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_FALLING_EXPANDED);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES -> clear(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1 -> clear(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RANGE ->
                  clear(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS_RANGE);
            case OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RAPID_AC -> clear(OptionsConstants.ADVANCED_COMBAT_KIND_RAPID_AC);
            case OptionsConstants.ADVANCED_ALTERNATE_MASC -> clear(OptionsConstants.ADVANCED_ALTERNATE_MASC_ENHANCED);
            case OptionsConstants.ADVANCED_BA_GRAB_BARS -> grabBarsChanged.run();
            default -> {
            }
        }
    }

    private void clear(String... names) {
        for (String name : names) {
            bool(name, false);
        }
    }

    private void readValue(IOption option) {
        if (option.getType() == IOption.BOOLEAN) {
            values.put(option.getName(), option.booleanValue());
        } else {
            texts.put(option.getName(), textOf(option, option.getValue()));
        }
    }

    private static String textOf(IOption option, Object value) {
        if (option.getType() == IOption.FLOAT && value instanceof Number number) {
            return Float.toString(number.floatValue());
        }
        return String.valueOf(value);
    }

    private static @Nullable Object valueOf(@Nullable IOption option) {
        return option == null ? null : option.getValue();
    }

    private Object typedValue(IOption option) {
        String name = option.getName();
        return switch (option.getType()) {
            case IOption.BOOLEAN -> bool(name);
            case IOption.INTEGER -> {
                Integer number = parseInteger(text(name).strip());
                yield number == null ? 0 : number;
            }
            case IOption.FLOAT -> {
                Float number = parseFloat(text(name).strip());
                yield number == null ? 0f : number;
            }
            default -> text(name);
        };
    }

    private static @Nullable Integer parseInteger(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static @Nullable Float parseFloat(String text) {
        if (text.isEmpty()) {
            return 0f;
        }
        float number = MathUtility.parseFloat(text, Float.NaN);
        return Float.isNaN(number) ? null : number;
    }

    private List<Page> buildPages() {
        Map<GameOptionsPresentation.PageDefinition, PageSeed> seeds = new LinkedHashMap<>();
        for (Enumeration<IOptionGroup> groups = options.getGroups(); groups.hasMoreElements(); ) {
            IOptionGroup group = groups.nextElement();
            if (skippedGroups.contains(group.getName())) {
                continue;
            }
            for (Enumeration<IOption> groupOptions = group.getOptions(); groupOptions.hasMoreElements(); ) {
                IOption option = groupOptions.nextElement();
                represented.put(option.getName(), option);
                if (OptionsConstants.BASE_HIDE_UNOFFICIAL.equals(option.getName())
                      || OptionsConstants.BASE_HIDE_LEGACY.equals(option.getName())) {
                    continue;
                }
                GameOptionsPresentation.Location location = GameOptionsPresentation.locationOrFallback(
                      group.getName(), option.getName());
                seeds.computeIfAbsent(location.page(), PageSeed::new).add(group, option, location);
            }
        }
        List<PageSeed> ordered = new ArrayList<>(seeds.values());
        ordered.sort(Comparator.comparingInt(seed -> seed.definition.order()));
        return ordered.stream().map(PageSeed::page).toList();
    }

    private static Row row(IOption option) {
        String name = option.getName();
        String label = GameOptionsCatalog.displayName(option);
        String details = GameOptionsCatalog.details(option);
        String tooltip = details != null ? details : Objects.requireNonNullElse(option.getDescription(), "");
        Kind kind;
        List<String> choiceValues = List.of();
        List<String> choiceLabels = List.of();
        Map<Integer, String> integerChoices = GameOptionsCatalog.integerChoices(name);
        switch (option.getType()) {
            case IOption.BOOLEAN -> kind = Kind.BOOLEAN;
            case IOption.INTEGER -> {
                if (integerChoices.isEmpty()) {
                    kind = Kind.INTEGER;
                } else {
                    kind = Kind.INTEGER_CHOICE;
                    choiceValues = integerChoices.keySet().stream().map(String::valueOf).toList();
                    choiceLabels = List.copyOf(integerChoices.values());
                }
            }
            case IOption.FLOAT -> kind = Kind.FLOAT;
            case IOption.CHOICE -> {
                kind = Kind.CHOICE;
                choiceValues = GameOptionsCatalog.choices(name);
                if (choiceValues.isEmpty()) {
                    choiceValues = List.of(option.stringValue());
                }
                choiceLabels = choiceValues;
            }
            default -> kind = Kind.STRING;
        }
        String extra = OptionsConstants.RULES_SYSTEM.equals(name)
              ? ' ' + GameOptionsCatalog.text("GameOptionsDialog.page.landing.rulesSystem.core") + ' '
                    + GameOptionsCatalog.text("GameOptionsDialog.page.landing.rulesSystem.totalWarfare")
              : "";
        String searchText = SettingsRoute.normalizeSearchText(
              name + ' ' + option.getDisplayableName() + ' ' + label + extra);
        Set<String> tags = new HashSet<>();
        if (GameOptionsCatalog.isImportant(option)) {
            tags.add(IMPORTANT);
        }
        if (GameOptionsCatalog.isUnofficial(name)) {
            tags.add(UNOFFICIAL);
        }
        if (GameOptionsCatalog.isLegacy(name)) {
            tags.add(LEGACY);
        }
        return new Row(name, label, tooltip, kind, choiceValues, choiceLabels, tags, searchText);
    }

    private static final class PageSeed {
        private final GameOptionsPresentation.PageDefinition definition;
        private final Map<String, String> sourceGroups = new LinkedHashMap<>();
        private final Map<String, Integer> sectionOrders = new HashMap<>();
        private final Map<String, List<Placed>> sections = new LinkedHashMap<>();

        private PageSeed(GameOptionsPresentation.PageDefinition definition) {
            this.definition = definition;
        }

        private void add(IOptionGroup group, IOption option, GameOptionsPresentation.Location location) {
            sourceGroups.put(group.getName(), group.getDisplayableName());
            sectionOrders.putIfAbsent(location.sectionId(), location.sectionOrder());
            sections.computeIfAbsent(location.sectionId(), id -> new ArrayList<>())
                  .add(new Placed(row(option), location.optionOrder()));
        }

        private Page page() {
            List<String> aliases = new ArrayList<>();
            sourceGroups.forEach((id, displayName) -> {
                aliases.add(id);
                aliases.add(displayName);
            });
            String pageText = SettingsRoute.normalizeSearchText(String.join(" ", aliases) + ' '
                  + String.join(" ", definition.path(sourceGroups)));
            List<String> ids = new ArrayList<>(sections.keySet());
            ids.sort(Comparator.comparingInt(sectionOrders::get));
            List<Section> built = new ArrayList<>();
            for (String id : ids) {
                List<Placed> placed = new ArrayList<>(sections.get(id));
                placed.sort(Comparator.comparingInt(Placed::order));
                String title = GameOptionsCatalog.sectionTitle(id);
                String summary = GameOptionsCatalog.sectionSummary(id);
                built.add(new Section(id, title, summary, placed.stream().map(Placed::row).toList(),
                      SettingsRoute.normalizeSearchText(title + ' ' + summary)));
            }
            String categoryTitle = definition.isDirect() ? "" : definition.path(sourceGroups).get(0);
            return new Page(definition.id(), definition.title(sourceGroups), definition.categoryId(), categoryTitle,
                  definition.advanced(), Collections.unmodifiableList(built), pageText);
        }
    }

    private record Placed(Row row, int order) {
    }

    /** @return the names of every option the form edits, the two rule toggles included */
    Set<String> representedNames() {
        return new HashSet<>(represented.keySet());
    }
}
