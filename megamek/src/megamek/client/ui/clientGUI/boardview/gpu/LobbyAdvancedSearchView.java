/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.lobby.RequisitionAdvancedSearch.Choice;
import megamek.client.lobby.RequisitionAdvancedSearch.Equipment;
import megamek.client.lobby.RequisitionAdvancedSearch.EquipmentChoice;
import megamek.client.lobby.RequisitionAdvancedSearch.Field;
import megamek.client.lobby.RequisitionAdvancedSearch.Flag;
import megamek.client.lobby.RequisitionAdvancedSearch.Level;
import megamek.client.lobby.RequisitionAdvancedSearch.Minimums;
import megamek.client.lobby.RequisitionAdvancedSearch.Movement;
import megamek.client.lobby.RequisitionAdvancedSearch.Picks;
import megamek.client.lobby.RequisitionAdvancedSearch.Range;
import megamek.client.lobby.RequisitionAdvancedSearch.Section;
import megamek.client.lobby.RequisitionAdvancedSearch.Term;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPickList;
import megamek.client.ui.gdx.UiRangeInput;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiVirtualList;

/**
 * The Requisition view's advanced search (gap analysis L11): the unit selector's Total Warfare, Alpha Strike and
 * Battlefield Support searches rebuilt from the UI kit, in the results' place. A header with the title, the three
 * tabs, the live count of matching units, Clear (this tab's criteria) and Done; under it the tab's criteria in titled
 * blocks that flow into as many columns as the width holds. Every edit goes straight to the search
 * ({@link RequisitionAdvancedSearch}) without rebuilding the controls, so a field keeps its focus; a change from
 * elsewhere (a tag's removal, Reset) rebuilds the page.
 */
final class LobbyAdvancedSearchView {
    /** The narrowest a block's column gets, in control.lg, before the blocks stack in fewer columns. */
    private static final float COLUMN_CONTROLS = 8;
    /** The most columns the blocks flow into. */
    private static final int MAX_COLUMNS = 3;
    /** The rows of the equipment list shown before it scrolls. */
    private static final int EQUIPMENT_ROWS = 8;

    /** What the view asks of the Requisition view. */
    interface Host {
        RequisitionAdvancedSearch criteria();

        void criteria(RequisitionAdvancedSearch next);

        void done();
    }

    /**
     * A titled block of a tab's criteria; {@code column} is where it goes when the page has {@link #MAX_COLUMNS}
     * columns, so the long blocks share the columns evenly, or -1 to go to the shortest column.
     */
    private record Block(String key, List<Field> fields, int column) {
        Block(String key, List<Field> fields) {
            this(key, fields, -1);
        }
    }

    private final UiKit kit;
    private final Host host;
    private final Table root = new Table() {
        @Override
        public void layout() {
            int fits = columnsFor(getWidth());
            if (fits != columns) {
                columns = fits;
                arrange();
            }
            super.layout();
        }
    };
    private final UiTabs tabs;
    private final Label matches;
    private final UiButton clear;
    private final Table page = new Table();
    private final ScrollPane scroll;
    private final List<Table> blocks = new ArrayList<>();
    private List<Block> shownBlocks = List.of();
    private final Map<Field, Actor> controls = new EnumMap<>(Field.class);
    private Section section = Section.TOTAL_WARFARE;
    private RequisitionAdvancedSearch shown = RequisitionAdvancedSearch.NONE;
    private int columns;
    private Equipment equipment = Equipment.EMPTY;
    private EquipmentEditor editor;

    LobbyAdvancedSearchView(UiKit kit, Host host) {
        this.kit = kit;
        this.host = host;
        root.setName("lobby-requisition-advanced");
        root.top().left();
        root.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_PANEL));
        root.pad(space("md"));

        String[] names = new String[Section.values().length];
        for (Section each : Section.values()) {
            names[each.ordinal()] = Messages.getString(each.messageKey());
        }
        tabs = kit.tokenTabs(true, names);
        tabs.setName("lobby-requisition-advanced-tabs");
        UiKit.onChange(tabs, () -> {
            section = Section.values()[tabs.getSelectedIndex()];
            build();
        });
        matches = kit.typeLabel("", "small", "text.secondary");
        matches.setName("lobby-requisition-advanced-matches");
        clear = kit.button("button-quiet" + UiTheme.COMPACT, "undo",
              Messages.getString("Lobby.requisition.advanced.clear"), null);
        kit.tip(clear, true).getActor().setText(Messages.getString("Lobby.requisition.advanced.clearTip"));
        UiKit.onChange(clear, () -> {
            set(host.criteria().without(section));
            build();
        });
        UiButton done = kit.button("button-primary" + UiTheme.COMPACT, "check",
              Messages.getString("Lobby.requisition.advanced.done"), null);
        done.setName("lobby-requisition-advanced-done");
        UiKit.onChange(done, host::done);

        Table head = new Table();
        head.add(kit.roleTypeLabel("title", Messages.getString("Lobby.requisition.advanced.title"), "text.primary"))
              .left().padRight(space("lg"));
        head.add(matches).left().growX().minWidth(0);
        head.add(clear).right().padLeft(space("sm"));
        head.add(done).right().padLeft(space("sm"));
        root.add(head).growX().minWidth(0).row();
        root.add(kit.scrollStrip(tabs)).growX().minWidth(0).padTop(space("sm")).row();
        root.add(kit.typeLabel(Messages.getString("Lobby.requisition.advanced.combine"), "small", "text.muted"))
              .left().padTop(space("xs")).row();
        page.top().left();
        scroll = kit.scrollList(page, true, false);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        // No height of its own: the page scrolls in what the view leaves, rather than squeezing the strips above
        root.add(scroll).grow().minHeight(0).prefHeight(0).padTop(space("sm"));
        build();
    }

    /** The view's actor, which takes the results' place. */
    Table actor() {
        return root;
    }

    /** The columns the blocks flow into now. */
    int columns() {
        return columns;
    }

    /** Scrolls the page so {@code actor}, on it, shows; a probe does so before it clicks. */
    void reveal(Actor actor) {
        com.badlogic.gdx.math.Vector2 at = actor.localToAscendantCoordinates(page,
              new com.badlogic.gdx.math.Vector2(0, 0));
        scroll.layout();
        scroll.scrollTo(at.x, at.y, actor.getWidth(), actor.getHeight());
        scroll.updateVisualScroll();
    }

    /** The tabs; a probe switches them. */
    UiTabs tabs() {
        return tabs;
    }

    /** The control of {@code field} on the shown tab, or null; a probe and a test click it. */
    Actor control(Field field) {
        return controls.get(field);
    }

    /** The equipment editor of the Total Warfare tab, or null on the other tabs. */
    EquipmentEditor equipmentEditor() {
        return section == Section.TOTAL_WARFARE ? editor : null;
    }

    /** Shows the count of units the search finds now. */
    void matches(int count) {
        matches.setText(Messages.getString("Lobby.requisition.advanced.matches",
              NumberFormat.getIntegerInstance().format(count)));
    }

    /** Shows {@code criteria} after a change from outside, rebuilding the page unless it is the one shown. */
    void sync(RequisitionAdvancedSearch criteria) {
        if (!criteria.equals(shown)) {
            build();
        }
        refreshHead();
    }

    private void set(RequisitionAdvancedSearch next) {
        shown = next;
        host.criteria(next);
        refreshHead();
    }

    private void update(Field field, RequisitionAdvancedSearch.Value value) {
        set(host.criteria().with(field, value));
    }

    private void refreshHead() {
        clear.setDisabled(host.criteria().count(section) == 0);
    }

    // ---- Building ----

    private void build() {
        shown = host.criteria();
        controls.clear();
        blocks.clear();
        editor = null;
        shownBlocks = blocksOf(section);
        for (Block block : shownBlocks) {
            blocks.add(block(block));
        }
        arrange();
        scroll.setScrollY(0);
        refreshHead();
    }

    private static List<Block> blocksOf(Section section) {
        return switch (section) {
            // The unit flags are the longest block, so they share a column with the short quirks
            case TOTAL_WARFARE -> List.of(
                  new Block("movement", List.of(Field.WALK, Field.RUN, Field.JUMP, Field.TONS, Field.YEAR, Field.BV,
                        Field.ARMOR), 0),
                  new Block("types", List.of(Field.UNIT_TYPE, Field.TECH_BASE, Field.TECH_LEVEL, Field.MOVE_MODE),
                        1),
                  new Block("quirks", List.of(Field.QUIRKS, Field.WEAPON_QUIRKS), 2),
                  new Block("construction", List.of(Field.ARMOR_TYPE, Field.INTERNALS, Field.ENGINE, Field.COCKPIT,
                        Field.GYRO), 0),
                  new Block("equipment", List.of(Field.EQUIPMENT), 1),
                  new Block("flags", Field.of(Section.TOTAL_WARFARE).stream()
                        .filter(field -> field.kind() == RequisitionAdvancedSearch.Kind.FLAG).toList(), 2));
            case ALPHA_STRIKE -> List.of(
                  new Block("asUnit", List.of(Field.AS_TYPE, Field.AS_SIZE, Field.AS_ROLE, Field.AS_MOVE,
                        Field.AS_TMM)),
                  new Block("asStats", List.of(Field.AS_ARMOR, Field.AS_STRUCTURE, Field.AS_THRESHOLD, Field.AS_OV,
                        Field.AS_PV)),
                  new Block("asDamage", List.of(Field.AS_DAMAGE_S, Field.AS_DAMAGE_M, Field.AS_DAMAGE_L,
                        Field.AS_DAMAGE_E)),
                  new Block("asSpecials", List.of(Field.AS_SPECIALS)));
            case BATTLEFIELD_SUPPORT -> List.of(
                  new Block("bfAsset", List.of(Field.BF_TYPE, Field.BF_MOVE_MODE, Field.BF_ARTILLERY)),
                  new Block("bfStats", List.of(Field.BF_MP, Field.BF_TMM, Field.BF_SKILL, Field.BF_DAMAGE,
                        Field.BF_DAMAGE_PER_HIT, Field.BF_DAMAGE_HITS, Field.BF_LONG_RANGE, Field.BF_DESTROY_CHECK,
                        Field.BF_THRESHOLD, Field.BF_BSP, Field.BF_BV)),
                  new Block("bfSpecials", List.of(Field.BF_SPECIALS, Field.BF_NUMERIC_SPECIALS)));
        };
    }

    private static int columnsFor(float width) {
        float column = size("control.lg") * COLUMN_CONTROLS;
        return Math.max(1, Math.min(MAX_COLUMNS, (int) ((width + space("lg")) / (column + space("lg")))));
    }

    /** Lays the blocks into the columns: where a full page places them, otherwise each to the shortest column. */
    private void arrange() {
        page.clearChildren();
        int count = Math.max(1, columns);
        List<Table> columnTables = new ArrayList<>();
        float[] heights = new float[count];
        for (int i = 0; i < count; i++) {
            Table column = new Table();
            column.top().left();
            columnTables.add(column);
            page.add(column).top().growX().uniformX().minWidth(0).padLeft(i == 0 ? 0 : space("lg"));
        }
        for (int index = 0; index < blocks.size(); index++) {
            Table block = blocks.get(index);
            int placed = count == MAX_COLUMNS ? shownBlocks.get(index).column() : -1;
            if (placed < 0) {
                placed = 0;
                for (int i = 1; i < count; i++) {
                    if (heights[i] < heights[placed] - 1) {
                        placed = i;
                    }
                }
            }
            Table column = columnTables.get(placed);
            column.add(block).growX().minWidth(0).padBottom(space("lg")).row();
            heights[placed] += block.getPrefHeight() + space("lg");
        }
    }

    private Table block(Block block) {
        Table table = new Table();
        table.top().left();
        table.defaults().left();
        table.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.requisition.advanced.section."
              + block.key()), "text.muted")).colspan(2).padBottom(space("xs")).row();
        for (Field field : block.fields()) {
            Actor control = makeControl(field);
            controls.put(field, control);
            if (field.kind() == RequisitionAdvancedSearch.Kind.EQUIPMENT) {
                table.add(control).colspan(2).growX().minWidth(0).padTop(space("xs")).row();
                continue;
            }
            Label label = kit.typeLabel(RequisitionAdvancedSearch.label(field), "small", "text.secondary");
            label.setWrap(true);
            UiKit.formRow(table, label, control, size("control.lg") * 2.5f).padTop(space("xs"));
            table.getCell(label).padTop(space("sm"));
        }
        return table;
    }

    private Actor makeControl(Field field) {
        RequisitionAdvancedSearch.Value value = shown.value(field);
        return switch (field.kind()) {
            case RANGE -> range(field, value instanceof Range range ? range : null);
            case LEVEL -> level(field, value instanceof Level level ? level.index() : 0);
            case FLAG -> flag(field, value instanceof Flag flag ? flag : null);
            case PICKS -> picks(field, value instanceof Picks picks ? picks : null);
            case DAMAGE -> damage(field, value instanceof Range range ? range : null);
            case MOVEMENT -> movement(field, value instanceof Movement movement ? movement : null);
            case MINIMUMS -> minimums(field, value instanceof Minimums minimums ? minimums : null);
            case EQUIPMENT -> {
                equipment = value instanceof Equipment terms ? terms : Equipment.EMPTY;
                editor = new EquipmentEditor();
                yield editor.root;
            }
        };
    }

    private UiRangeInput range(Field field, Range value) {
        UiRangeInput input = kit.rangeInput(Messages.getString("Lobby.requisition.advanced.min"),
              Messages.getString("Lobby.requisition.advanced.max"));
        input.setName("lobby-advanced-" + field.name());
        if (value != null) {
            input.set(value.min(), value.max());
        }
        UiKit.onChange(input, () -> update(field, new Range(input.min(), input.max())));
        return input;
    }

    private UiSelect level(Field field, int index) {
        List<Choice> choices = RequisitionAdvancedSearch.choices(field);
        UiSelect select = kit.tokenSelect(true, choices.stream().map(Choice::label).toArray(String[]::new));
        select.select(Math.max(0, Math.min(choices.size() - 1, index)));
        UiKit.onChange(select, () -> update(field, new Level(select.getSelectedIndex())));
        return select;
    }

    private UiSegmented flag(Field field, Flag value) {
        UiSegmented segmented = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.advanced.any"),
              Messages.getString("Lobby.requisition.advanced.yes"),
              Messages.getString("Lobby.requisition.advanced.no"));
        segmented.select(value == null ? 0 : (value.yes() ? 1 : 2));
        UiKit.onChange(segmented, () -> {
            int index = segmented.getSelectedIndex();
            update(field, index == 0 ? null : new Flag(index == 1));
        });
        return segmented;
    }

    /** Few choices that can only be picked in: filter chips; otherwise a pick list. */
    private Actor picks(Field field, Picks value) {
        List<Choice> choices = RequisitionAdvancedSearch.choices(field);
        Picks current = value == null ? new Picks(List.of(), List.of(), false) : value;
        if (!field.excludes() && choices.size() <= 10) {
            return chips(field, choices, value);
        }
        UiPickList list = kit.pickList(Messages.getString("Lobby.requisition.advanced.add"),
              choices.stream().map(Choice::label).toList(), field.excludes());
        if (field.excludes()) {
            list.chipTip(Messages.getString("Lobby.requisition.advanced.pickTip"));
        }
        Map<Integer, Boolean> states = new LinkedHashMap<>();
        for (int i = 0; i < choices.size(); i++) {
            Boolean state = current.state(choices.get(i).value());
            if (state != null) {
                states.put(i, state);
            }
        }
        list.set(states);
        UiSegmented anyOrAll;
        if (field.anyOrAll()) {
            anyOrAll = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.advanced.matchAll"),
                  Messages.getString("Lobby.requisition.advanced.matchAny"));
            anyOrAll.select(current.any() ? 1 : 0);
        } else {
            anyOrAll = null;
        }
        Runnable changed = () -> {
            List<Object> in = new ArrayList<>();
            List<Object> out = new ArrayList<>();
            list.picks().forEach((index, state) -> (state ? in : out).add(choices.get(index).value()));
            update(field, new Picks(in, out, anyOrAll != null && anyOrAll.getSelectedIndex() == 1));
        };
        UiKit.onChange(list, changed);
        if (anyOrAll == null) {
            return list;
        }
        UiKit.onChange(anyOrAll, changed);
        Table row = new Table();
        row.top().left();
        row.add(list).growX().minWidth(0).top().left();
        row.add(anyOrAll).top().padLeft(space("sm"));
        return row;
    }

    private HorizontalGroup chips(Field field, List<Choice> choices, Picks value) {
        HorizontalGroup group = chipGroup();
        List<UiFilterChip> chips = new ArrayList<>();
        UiFilterChip anyType;
        if (field.emptyAllowed()) {
            // Battlefield Support artillery: of any type, or of the types checked
            anyType = kit.filterChip(Messages.getString("Lobby.requisition.advanced.any"));
            anyType.setChecked(value != null && value.in().isEmpty());
            group.addActor(anyType);
        } else {
            anyType = null;
        }
        for (Choice choice : choices) {
            UiFilterChip chip = kit.filterChip(choice.label());
            chip.setChecked(value != null && value.in().contains(choice.value()));
            chips.add(chip);
            group.addActor(chip);
        }
        Consumer<UiFilterChip> changed = source -> {
            if (anyType != null && source == anyType && anyType.isChecked()) {
                chips.forEach(chip -> chip.setChecked(false));
            } else if (anyType != null && source != anyType && source.isChecked()) {
                anyType.setChecked(false);
            }
            List<Object> in = new ArrayList<>();
            for (int i = 0; i < chips.size(); i++) {
                if (chips.get(i).isChecked()) {
                    in.add(choices.get(i).value());
                }
            }
            boolean any = anyType != null && anyType.isChecked();
            update(field, in.isEmpty() && !any ? null : new Picks(in, List.of(), false));
        };
        if (anyType != null) {
            UiKit.onChange(anyType, () -> changed.accept(anyType));
        }
        for (UiFilterChip chip : chips) {
            UiKit.onChange(chip, () -> changed.accept(chip));
        }
        if (choices.size() > 1 && choices.stream().allMatch(choice -> choice.label().length() <= 3)) {
            List<UiFilterChip> all = new ArrayList<>(chips);
            if (anyType != null) {
                all.add(anyType);
            }
            UiFilterChip.sameWidth(all);
        }
        return group;
    }

    private Table damage(Field field, Range value) {
        List<String> labels = new ArrayList<>();
        labels.add(Messages.getString("Lobby.requisition.advanced.any"));
        labels.addAll(RequisitionAdvancedSearch.damageLabels());
        UiSelect from = kit.tokenSelect(true, labels.toArray(String[]::new));
        UiSelect to = kit.tokenSelect(true, labels.toArray(String[]::new));
        from.select(value == null || value.min() == null ? 0 : value.min() + 1);
        to.select(value == null || value.max() == null ? 0 : value.max() + 1);
        Runnable changed = () -> update(field, new Range(index(from), index(to)));
        UiKit.onChange(from, changed);
        UiKit.onChange(to, changed);
        Table row = new Table();
        row.left();
        row.add(caption("Lobby.requisition.advanced.from.label")).padRight(space("xs"));
        row.add(from).growX().uniformX().minWidth(size("control.lg") * 1.5f);
        row.add(caption("Lobby.requisition.advanced.to.label")).padLeft(space("md")).padRight(space("xs"));
        row.add(to).growX().uniformX().minWidth(size("control.lg") * 1.5f);
        return row;
    }

    private static Integer index(UiSelect select) {
        return select.getSelectedIndex() <= 0 ? null : select.getSelectedIndex() - 1;
    }

    private Table movement(Field field, Movement value) {
        List<Choice> modes = RequisitionAdvancedSearch.choices(field);
        UiSelect mode = kit.tokenSelect(true, modes.stream().map(Choice::label).toArray(String[]::new));
        int at = 0;
        for (int i = 0; i < modes.size(); i++) {
            if (value != null && modes.get(i).value().equals(value.mode())) {
                at = i;
            }
        }
        mode.select(at);
        UiRangeInput span = kit.rangeInput(Messages.getString("Lobby.requisition.advanced.min"),
              Messages.getString("Lobby.requisition.advanced.max"));
        span.setName("lobby-advanced-" + field.name());
        if (value != null) {
            span.set(value.range().min(), value.range().max());
        }
        Runnable changed = () -> {
            Range range = new Range(span.min(), span.max());
            boolean plain = mode.getSelectedIndex() == 0 && span.min() == null && span.max() == null;
            update(field, plain ? null : new Movement((String) modes.get(mode.getSelectedIndex()).value(), range));
        };
        UiKit.onChange(mode, changed);
        UiKit.onChange(span, changed);
        Table table = new Table();
        table.left().defaults().left();
        table.add(mode).growX().minWidth(size("control.lg") * 2).row();
        table.add(span).growX().padTop(space("xs"));
        return table;
    }

    private Table minimums(Field field, Minimums value) {
        List<Choice> choices = RequisitionAdvancedSearch.choices(field);
        Map<Object, Integer> current = new LinkedHashMap<>(value == null ? Map.of() : value.values());
        UiSelect which = kit.tokenSelect(true, choices.stream().map(Choice::label).toArray(String[]::new))
              .placeholder(Messages.getString("Lobby.requisition.advanced.add"));
        UiStepper amount = kit.stepper(1, 1, 99);
        UiButton add = kit.button("button-quiet" + UiTheme.COMPACT, "add",
              Messages.getString("Lobby.requisition.advanced.addButton"), null);
        add.setDisabled(true);
        UiKit.onChange(which, () -> add.setDisabled(which.getSelectedIndex() < 0));
        HorizontalGroup tags = chipGroup();
        Table table = new Table();
        Runnable[] show = new Runnable[1];
        show[0] = () -> {
            tags.clearChildren();
            current.forEach((key, at) -> {
                Button tag = removable(Messages.getString("Lobby.requisition.advanced.atLeast",
                      RequisitionAdvancedSearch.labelOf(field, key), String.valueOf(at)));
                UiKit.onChange(tag, () -> {
                    current.remove(key);
                    update(field, new Minimums(current));
                    show[0].run();
                });
                tags.addActor(tag);
            });
            table.invalidateHierarchy();
        };
        UiKit.onChange(add, () -> {
            int index = which.getSelectedIndex();
            if (index < 0) {
                return;
            }
            current.put(choices.get(index).value(), amount.value());
            update(field, new Minimums(current));
            which.select(-1);
            add.setDisabled(true);
            show[0].run();
        });
        show[0].run();
        table.left().defaults().left();
        Table row = new Table();
        row.add(which).growX().minWidth(size("dialog.sm") / 3);
        row.add(amount).padLeft(space("sm"));
        row.add(add).padLeft(space("sm"));
        table.add(row).growX().row();
        table.add(tags).growX().minWidth(0).padTop(space("xs"));
        return table;
    }

    private Button removable(String text) {
        Button tag = kit.actingChip(text, UiKit.Tone.NORMAL);
        float icon = size("icon.sm");
        tag.add(kit.icon("close", icon, UiTheme.tokenColor("text.secondary"))).size(icon).padLeft(space("xs"));
        kit.tip(tag, true).getActor().setText(Messages.getString("Lobby.requisition.removeFilter"));
        return tag;
    }

    private HorizontalGroup chipGroup() {
        HorizontalGroup group = new HorizontalGroup();
        group.wrap(true).space(space("sm")).wrapSpace(space("xs")).rowLeft();
        return group;
    }

    private Label caption(String key) {
        return kit.typeLabel(Messages.getString(key), "small", "text.secondary");
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /**
     * The weapon and equipment expression, as the dialog's: a filtered list of the groups, weapons and equipment, a
     * count with "at least" or "fewer than", and the buttons that add the item, "and", "or" and brackets, undo the
     * last term or clear them all. The expression shows as text; it applies once it is complete.
     */
    final class EquipmentEditor {
        private final Table root = new Table();
        private final UiKit.TokenSearch find;
        private final UiVirtualList<EquipmentChoice> list;
        private final UiStepper count;
        private final UiSegmented comparison;
        private final UiButton addItem;
        private final UiButton and;
        private final UiButton or;
        private final UiButton open;
        private final UiButton close;
        private final UiButton undo;
        private final UiButton clearAll;
        private final Label text;
        private final Label note;

        private EquipmentEditor() {
            root.top().left();
            root.defaults().left();
            find = kit.tokenSearch(Messages.getString("Lobby.requisition.advanced.equipmentSearch"), true);
            float rowHeight = size("control.sm");
            list = new UiVirtualList<>(kit, rowHeight, () -> {
                Table row = new Table();
                row.left().padLeft(space("md")).padRight(space("md"));
                Label name = kit.typeLabel("", "body", "text.primary");
                name.setEllipsis(true);
                row.add(name).growX().minWidth(0).left();
                row.add(kit.typeLabel("", "small", "text.muted")).right().padLeft(space("sm"));
                return row;
            }, (row, item) -> {
                Table table = (Table) row;
                ((Label) table.getChildren().get(0)).setText(item.label());
                ((Label) table.getChildren().get(1)).setText(Messages.getString(
                      "Lobby.requisition.advanced.group." + item.group().name()));
            });
            list.setName("lobby-advanced-equipment-list");
            list.onSelect((item, index) -> refresh());
            list.onActivate((item, index) -> addItem());
            ScrollPane listScroll = kit.scrollList(list, true, true);
            listScroll.setFadeScrollBars(false);
            UiKit.onChange(find.field, this::filter);
            count = kit.stepper(1, 0, 99);
            comparison = kit.tokenSegmented(true, Messages.getString("Lobby.requisition.advanced.atLeastOp"),
                  Messages.getString("Lobby.requisition.advanced.fewerOp"));
            addItem = kit.button("button-primary" + UiTheme.COMPACT, "add",
                  Messages.getString("Lobby.requisition.advanced.addItem"), null);
            addItem.setName("lobby-advanced-equipment-add");
            UiKit.onChange(addItem, this::addItem);
            and = operator(Messages.getString("Lobby.requisition.advanced.and").toUpperCase(Locale.ROOT), Term.AND);
            or = operator(Messages.getString("Lobby.requisition.advanced.or").toUpperCase(Locale.ROOT), Term.OR);
            open = operator("(", Term.OPEN);
            close = operator(")", Term.CLOSE);
            undo = kit.button("button-quiet" + UiTheme.COMPACT, "undo",
                  Messages.getString("Lobby.requisition.advanced.undo"), null);
            UiKit.onChange(undo, () -> change(equipment.back()));
            clearAll = kit.button("button-quiet" + UiTheme.COMPACT, null,
                  Messages.getString("Lobby.requisition.advanced.clear"), null);
            UiKit.onChange(clearAll, () -> change(Equipment.EMPTY));
            text = kit.typeLabel("", "body", "text.primary");
            text.setWrap(true);
            note = kit.typeLabel("", "small", "text.muted");
            note.setWrap(true);

            root.add(find).growX().minWidth(0).row();
            root.add(listScroll).growX().minWidth(0).height(rowHeight * EQUIPMENT_ROWS).padTop(space("xs")).row();
            Table item = new Table();
            item.add(caption("Lobby.requisition.advanced.mode")).padRight(space("xs"));
            item.add(comparison);
            item.add(count).padLeft(space("sm"));
            item.add(addItem).padLeft(space("sm"));
            root.add(item).padTop(space("sm")).row();
            HorizontalGroup operators = chipGroup();
            for (Actor each : List.of(and, or, open, close, undo, clearAll)) {
                operators.addActor(each);
            }
            root.add(operators).growX().minWidth(0).padTop(space("xs")).row();
            root.add(text).growX().minWidth(0).padTop(space("sm")).row();
            root.add(note).growX().minWidth(0).padTop(space("xxs")).row();
            filter();
            refresh();
        }

        private UiButton operator(String label, Term term) {
            UiButton button = kit.button("button-secondary" + UiTheme.COMPACT, null, label, null);
            UiKit.onChange(button, () -> change(equipment.plus(term)));
            return button;
        }

        private void filter() {
            String typed = find.field.getText().trim().toLowerCase(Locale.ROOT);
            List<EquipmentChoice> all = RequisitionAdvancedSearch.equipmentChoices();
            list.setItems(typed.isEmpty() ? all : all.stream()
                  .filter(choice -> choice.label().toLowerCase(Locale.ROOT).contains(typed)).toList());
            refresh();
        }

        private void addItem() {
            EquipmentChoice chosen = list.selectedItem();
            if (chosen != null) {
                change(equipment.plus(Term.item(chosen.key(), chosen.label(), count.value(),
                      comparison.getSelectedIndex() == 0)));
            }
        }

        private void change(Equipment next) {
            equipment = next;
            update(Field.EQUIPMENT, next);
            refresh();
        }

        private void refresh() {
            addItem.setDisabled(!equipment.canAddItem() || list.selectedItem() == null);
            and.setDisabled(!equipment.canAddOperator());
            or.setDisabled(!equipment.canAddOperator());
            open.setDisabled(!equipment.canAddItem());
            close.setDisabled(!equipment.canClose());
            undo.setDisabled(equipment.terms().isEmpty());
            clearAll.setDisabled(equipment.terms().isEmpty());
            boolean empty = equipment.terms().isEmpty();
            text.setText(empty ? "" : equipment.text());
            text.setVisible(!empty);
            note.setText(empty ? Messages.getString("Lobby.requisition.advanced.expressionEmpty")
                  : (equipment.complete() ? "" : Messages.getString("Lobby.requisition.advanced.expressionIncomplete")));
        }

        /** The list of equipment; a probe selects a row in it. */
        UiVirtualList<EquipmentChoice> list() {
            return list;
        }

        /** The search field over the list. */
        UiKit.TokenSearch find() {
            return find;
        }

        /** The button that adds the selected item. */
        UiButton addButton() {
            return addItem;
        }

        Label expression() {
            return text;
        }
    }
}
