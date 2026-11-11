/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.settings.SettingsForm;
import megamek.client.ui.settings.SettingsForm.Kind;
import megamek.client.ui.settings.SettingsForm.Page;
import megamek.client.ui.settings.SettingsForm.Row;
import megamek.client.ui.settings.SettingsForm.Section;

/**
 * The settings screen the game options and the Client Settings share, built for the stage rather than after a Swing
 * form: the search on top of a rail of icon tiles, one per group of pages, with how many of its parts match while
 * searching; the pages of the chosen group as tabs above the page, every matching page while searching; the page as
 * cards, one per section, of switches, sliders with their reading, choices, fields, file pickers and keys to bind, each
 * with a dot
 * while it differs from where it started; a help strip at the foot that tells about the setting under the pointer; and
 * a preview beside the page when the {@link Flavor} has one. The values and rules are the {@link SettingsForm}'s; the
 * flavor brings the rest: the icons, the marks, what goes beside the rail and the page, what a change does at once and
 * how a file is picked. The footer is the caller's.
 * <p>
 * GPU thread.
 */
final class GpuSettingsView {
    /** The narrowest body that still sets the rail beside the page, and the narrowest that has room for a preview. */
    private static final float STACK_BELOW = 720;
    private static final float PREVIEW_BELOW = 1000;
    private static final float RAIL_WIDTH = 230;
    private static final float PREVIEW_WIDTH = 280;
    /** The widest a choice or text field grows; a switch, slider or file picker takes the card's width. */
    private static final float FIELD_WIDTH = 320;
    private static final float READING_WIDTH = 52;

    /** The search's texts: its placeholder and tooltip, what it says without and with matches, and the idle help. */
    record Texts(String search, String searchTip, String noMatches, String matches, String hint) { }

    /** What a settings screen brings besides its form; each part has a plain default. */
    abstract static class Flavor {
        /** The prefix of the screen's actor names, such as "options" for options-search and options-page-landing. */
        abstract String prefix();

        /** The prefix of a setting's control name, such as "option-"; its changed dot adds "-changed". */
        abstract String rowPrefix();

        abstract Texts texts();

        /** The rail tile's icon of a group ({@link Page#group()}). */
        String icon(String group) {
            return "settings";
        }

        /** Adds what goes under the rail, such as switches that show or hide parts of the form. */
        void navExtras(Table nav) {
        }

        /** Adds what goes beside the page's title, such as its marks or a reset. */
        void header(Page page, Table header) {
        }

        /** Adds what goes under the page's title, above its first card. */
        void intro(Page page, Table body) {
        }

        /** What goes under a setting in its card, such as what the choice means; null for nothing. */
        @Nullable Actor afterRow(Row row) {
            return null;
        }

        /** Adds the setting's marks after its control. */
        void marks(Row row, Table marks) {
        }

        /** What the help strip says about the setting while the pointer is over it. */
        String help(Row row) {
            return plain(row.tooltip());
        }

        /** What goes beside the page, such as a sound board; null for nothing. */
        @Nullable Actor preview(Page page) {
            return null;
        }

        /** The setting under the pointer, null once the pointer left it; for a preview that shows it. */
        void pointed(@Nullable Row row) {
        }

        /** After the form took a change from a control. */
        void changed(Row row) {
        }

        /**
         * Whether the setting's slider tells of each value as it is dragged; one that does not, such as a scale that
         * would move the slider under the pointer, tells only of where it is let go.
         */
        boolean live(Row row) {
            return true;
        }

        /** Asks for a file for a {@link Kind#FILE} setting; {@code then} gets the path, unless none was picked. */
        void pick(Row row, String current, Consumer<String> then) {
        }

        /** After the view showed the form anew, for the parts of the flavor and the footer. */
        void refreshed() {
        }

        /** Whether the screen waits on something, so no control takes a change. */
        boolean busy() {
            return false;
        }
    }

    /** One shown setting: its control, its form field (null for a switch), its reading, picker and changed dot. */
    private record Control(Row row, Actor input, @Nullable UiFormField field, @Nullable Label reading,
          @Nullable UiButton picker, Actor changed, @Nullable KeyControl key) { }

    /**
     * A key binding's parts: the keycap that shows its keys and takes a new one on a click, its buttons back to the
     * default and to unbind, and its problem under the line, which shows only while it has one.
     */
    private static final class KeyControl {
        final UiButton cap;
        final Table caps = new Table();
        final UiButton restore;
        final UiButton unbind;
        final Label problem;
        Cell<Label> problemCell;
        /** What the keycap shows now, so it is built anew only when that changes. */
        String shown = "";

        KeyControl(UiButton cap, UiButton restore, UiButton unbind, Label problem) {
            this.cap = cap;
            this.restore = restore;
            this.unbind = unbind;
            this.problem = problem;
        }
    }

    private final UiKit kit;
    private final UiDialog dialog;
    private final Flavor flavor;
    private final boolean stacked;
    private final boolean previews;
    private final float fieldWidth;
    private final UiKit.TokenSearch search;
    private final Label matches;
    private final Table railColumn = new Table();
    private final HorizontalGroup railFlow = new HorizontalGroup();
    private final HorizontalGroup tabs = new HorizontalGroup();
    private final Table page = new Table();
    private final ScrollPane pageScroll;
    private final Container<Actor> preview = new Container<>();
    private Cell<HorizontalGroup> tabsCell;
    private Cell<ScrollPane> pageCell;
    private final Label help;
    private final Map<String, Control> controls = new LinkedHashMap<>();
    private SettingsForm form;
    private String pageId = "";
    private boolean syncing;
    /** The slider setting held down whose flavor has not heard of its change yet ({@link Flavor#live}). */
    private @Nullable Row held;
    /** The key binding waiting for its new key, and the stage that hears the keys meanwhile. */
    private @Nullable Row capturing;
    private @Nullable Stage captureStage;
    /** The modifier pressed alone while capturing, which binds when let go; -1 once a second joined it. */
    private int lonelyModifier;
    private final InputListener capture = new KeyCapture();

    /**
     * @param bodyWidth  the width the body has, which decides whether the rail goes beside the page and whether a
     *                   preview has room
     * @param paneHeight the height of the page and of the rail beside it
     */
    GpuSettingsView(UiKit kit, UiDialog dialog, SettingsForm form, float bodyWidth, float paneHeight, Flavor flavor) {
        this.kit = kit;
        this.dialog = dialog;
        this.form = form;
        this.flavor = flavor;
        stacked = bodyWidth < STACK_BELOW;
        previews = bodyWidth >= PREVIEW_BELOW;
        float gap = space("lg");
        float pageWidth = bodyWidth - (stacked ? 0 : RAIL_WIDTH + gap) - (previews ? PREVIEW_WIDTH + gap : 0);
        fieldWidth = Math.min(FIELD_WIDTH, Math.max(0, pageWidth - 4 * space("lg")));
        String prefix = flavor.prefix();
        Texts texts = flavor.texts();

        search = kit.tokenSearch(texts.search(), true);
        search.field.setName(prefix + "-search");
        kit.tip(search.field, true).getActor().setText(texts.searchTip());
        UiKit.onChange(search.field, () -> {
            if (!syncing) {
                this.form.search(search.field.getText());
                showAll();
            }
        });
        matches = kit.typeLabel("", "small", "text.muted");
        matches.setName(prefix + "-matches");

        railColumn.top().left().defaults().growX().left();
        railColumn.setName(prefix + "-rail");
        railFlow.wrap().space(space("xs")).wrapSpace(space("xs")).left();
        railFlow.setName(prefix + "-rail");
        tabs.wrap().space(space("xs")).wrapSpace(space("xs")).left();
        tabs.setName(prefix + "-tabs");

        Table nav = new Table();
        nav.top().left().defaults().left().growX();
        nav.add(search).minWidth(0).row();
        nav.add(matches).padTop(space("xs")).row();
        if (stacked) {
            nav.add(railFlow).padTop(space("sm")).row();
        } else {
            ScrollPane railScroll = kit.scrollList(railColumn, true);
            railScroll.setScrollingDisabled(true, false);
            nav.add(railScroll).expandY().fill().padTop(space("sm")).row();
        }
        flavor.navExtras(nav);

        page.top().left().defaults().left().growX();
        page.setName(prefix + "-page");
        pageScroll = kit.scrollList(page, true);
        pageScroll.setScrollingDisabled(true, false);
        pageScroll.setFadeScrollBars(false);
        Table column = new Table();
        column.top().left().defaults().left().growX();
        tabsCell = column.add(tabs);
        column.row();
        pageCell = column.add(pageScroll).grow();
        column.row();

        help = kit.typeLabel(texts.hint(), "small", "text.secondary");
        help.setWrap(true);
        help.setAlignment(Align.topLeft);
        help.setName(prefix + "-help");
        Table strip = kit.card();
        strip.add(kit.icon("info", size("icon.sm"), UiTheme.tokenColor("accent.primary"))).top().padRight(space("sm"));
        strip.add(help).growX().minHeight(2.6f * help.getStyle().font.getLineHeight()).top();

        preview.fill().top();
        Table body = dialog.body();
        body.defaults().left().top();
        if (stacked) {
            body.add(nav).growX().row();
            body.add(column).growX().height(paneHeight).padTop(gap).row();
        } else {
            body.add(nav).width(RAIL_WIDTH).height(paneHeight);
            body.add(column).grow().height(paneHeight).padLeft(gap);
            if (previews) {
                body.add(preview).width(PREVIEW_WIDTH).height(paneHeight).padLeft(gap);
            }
            body.row();
        }
        body.add(strip).colspan(stacked ? 1 : previews ? 3 : 2).growX().padTop(space("md")).row();
    }

    /** Shows the form, on {@code pageId} when it has that page, otherwise on its first. */
    void open(@Nullable String pageId) {
        this.pageId = pageId != null && form.page(pageId) != null ? pageId
              : form.pages().isEmpty() ? "" : form.pages().getFirst().id();
        showAll();
    }

    /** Shows a fresh form, on the same page, search and scroll as far as they still apply. */
    void form(SettingsForm fresh) {
        fresh.search(form.search());
        form = fresh;
        float scroll = pageScroll.getScrollY();
        showAll();
        pageScroll.layout();
        pageScroll.setScrollY(scroll);
    }

    SettingsForm form() {
        return form;
    }

    UiDialog dialog() {
        return dialog;
    }

    String pageId() {
        return pageId;
    }

    /** The shown setting's control, or null when its page does not show. */
    @Nullable Actor control(String name) {
        Control control = controls.get(name);
        return control == null ? null : control.input();
    }

    // ---- Navigation

    /** Shows the rail, the tabs and the page anew, as the search or the shown parts of the form changed. */
    void showAll() {
        List<Page> visible = form.visiblePages();
        if (visible.stream().noneMatch(each -> each.id().equals(pageId)) && !visible.isEmpty()) {
            pageId = visible.getFirst().id();
        }
        showRail(visible);
        showTabs(visible);
        Texts texts = flavor.texts();
        if (form.searching()) {
            int groups = visible.stream().mapToInt(each -> form.visibleSections(each).size()).sum();
            matches.setText(groups == 0 ? texts.noMatches() : String.format(texts.matches(), groups));
        } else {
            matches.setText("");
        }
        showPage();
    }

    private void showRail(List<Page> visible) {
        railColumn.clearChildren();
        railFlow.clearChildren();
        Map<String, List<Page>> groups = groups(visible);
        String current = current() == null ? "" : current().group();
        for (Map.Entry<String, List<Page>> group : groups.entrySet()) {
            Page first = group.getValue().getFirst();
            String title = first.isLanding() ? first.title() : first.categoryTitle();
            UiButton tile = kit.button("button-toggle", flavor.icon(group.getKey()), title, null);
            tile.setName(flavor.prefix() + "-group-" + group.getKey());
            tile.left();
            tile.pressed(group.getKey().equals(current));
            if (form.searching()) {
                int found = group.getValue().stream().mapToInt(each -> form.visibleSections(each).size()).sum();
                tile.badge(Integer.toString(found));
            }
            UiKit.onChange(tile, () -> navigate(first.id()));
            if (stacked) {
                railFlow.addActor(tile);
            } else {
                railColumn.add(tile).padTop(railColumn.getCells().isEmpty() ? 0 : space("xs")).row();
            }
        }
    }

    /** The tabs: the pages of the shown group when it has more than one, every matching page while searching. */
    private void showTabs(List<Page> visible) {
        tabs.clearChildren();
        Page shown = current();
        List<Page> listed = form.searching() ? visible : shown == null ? List.of()
              : groups(visible).getOrDefault(shown.group(), List.of());
        boolean tabsShown = listed.size() >= 2 || form.searching();
        // A hidden row would still take its height, and the page would sit lower than the rail and the preview
        tabsCell.setActor(tabsShown ? tabs : null);
        pageCell.padTop(tabsShown ? space("sm") : 0);
        if (!tabsShown) {
            return;
        }
        for (Page each : listed) {
            UiButton tab = kit.button("button-toggle" + UiTheme.COMPACT, null, each.title(), null);
            tab.setName(flavor.prefix() + "-page-" + each.id());
            tab.pressed(each.id().equals(pageId));
            UiKit.onChange(tab, () -> navigate(each.id()));
            tabs.addActor(tab);
        }
    }

    private static Map<String, List<Page>> groups(List<Page> pages) {
        Map<String, List<Page>> groups = new LinkedHashMap<>();
        for (Page each : pages) {
            groups.computeIfAbsent(each.group(), key -> new ArrayList<>()).add(each);
        }
        return groups;
    }

    private void navigate(String id) {
        if (syncing) {
            return;
        }
        pageId = id;
        List<Page> visible = form.visiblePages();
        showRail(visible);
        showTabs(visible);
        showPage();
    }

    private @Nullable Page current() {
        return form.visiblePages().stream().filter(each -> each.id().equals(pageId)).findFirst().orElse(null);
    }

    // ---- The page

    private void showPage() {
        endCapture();
        controls.clear();
        page.clearChildren();
        Page shown = current();
        preview.setActor(null);
        help.setText(flavor.texts().hint());
        if (shown == null) {
            refresh();
            return;
        }
        Table header = new Table();
        header.left();
        header.add(kit.typeLabel(shown.title(), "title", "text.primary")).left().minWidth(0);
        flavor.header(shown, header);
        header.add().expandX();
        page.add(header).row();
        if (!shown.categoryTitle().isBlank()) {
            page.add(kit.typeLabel(shown.categoryTitle(), "small", "text.muted")).row();
        }
        flavor.intro(shown, page);
        boolean first = true;
        for (Section section : form.visibleSections(shown)) {
            page.add(card(shown, section)).padTop(space(first ? "lg" : "md")).row();
            first = false;
        }
        if (previews) {
            Actor shownPreview = flavor.preview(shown);
            preview.setActor(shownPreview);
            // The preview sits in the same rounded frame as the rail and the page
            preview.setBackground(shownPreview == null ? null
                  : kit.skin.get(UiTheme.TOKEN_LIST, ScrollPane.ScrollPaneStyle.class).background);
            preview.pad(shownPreview == null ? 0 : space("md"));
        }
        pageScroll.layout();
        pageScroll.setScrollY(0);
        refresh();
    }

    private Table card(Page shown, Section section) {
        Table card = kit.card();
        card.pad(space("md"));
        card.top();
        card.defaults().left().growX();
        card.add(kit.caption(section.title(), true)).row();
        if (!section.summary().isBlank()) {
            card.add(wrapped(plain(section.summary()), "small", "text.muted")).padTop(space("xs")).row();
        }
        for (Row row : form.visibleRows(shown, section)) {
            card.add(row(row)).padTop(space("md")).row();
            Actor after = flavor.afterRow(row);
            if (after != null) {
                card.add(after).padTop(space("xs")).row();
            }
        }
        return card;
    }

    Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    private Table row(Row row) {
        Table line = new Table();
        line.left().top();
        String name = flavor.rowPrefix() + row.name();
        Actor input;
        UiFormField field = null;
        Label reading = null;
        UiButton picker = null;
        KeyControl key = null;
        switch (row.kind()) {
            case BOOLEAN -> {
                UiChoice toggle = kit.toggleSwitch(row.label(), form.bool(row.name()));
                UiKit.onChange(toggle, () -> changed(row, () -> form.bool(row.name(), toggle.isChecked())));
                input = toggle;
                line.add(toggle).left().minWidth(0);
            }
            case INTEGER_CHOICE, CHOICE -> {
                UiSelect select = kit.tokenSelect(true, row.labels().toArray(String[]::new));
                int index = row.values().indexOf(form.text(row.name()));
                if (index >= 0) {
                    select.select(index);
                }
                UiKit.onChange(select, () -> changed(row, () -> form.text(row.name(),
                      row.values().get(select.getSelectedIndex()))));
                input = select;
                field = kit.formField(row.label(), select);
                line.add(field).width(fieldWidth).left();
            }
            case SLIDER -> {
                Slider slider = new Slider(row.min(), row.max(), row.step(), false, kit.tokenSliderStyle());
                new UiSliderInput(slider);
                slider.setValue(number(form.text(row.name()), row.min()));
                UiKit.onChange(slider, () -> {
                    Runnable change = () -> form.text(row.name(), Integer.toString(Math.round(slider.getValue())));
                    if (slider.isDragging() && !flavor.live(row)) {
                        held(row, change);
                    } else {
                        changed(row, change);
                    }
                });
                slider.addListener(new InputListener() {
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                        return true;
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                        if (row.equals(held)) {
                            held = null;
                            changed(row, () -> { });
                        }
                    }
                });
                reading = kit.typeLabel(form.text(row.name()) + row.unit(), "body", "text.primary");
                reading.setAlignment(Align.right);
                Table bar = new Table();
                bar.add(slider).growX().minWidth(0);
                bar.add(reading).width(READING_WIDTH).padLeft(space("sm"));
                input = slider;
                field = kit.formField(row.label(), bar);
                line.add(field).growX().minWidth(0);
            }
            case FILE -> {
                UiTextField path = kit.input(form.text(row.name()), true);
                UiKit.onChange(path, () -> changed(row, () -> form.text(row.name(), path.getText())));
                picker = kit.button("button-secondary" + UiTheme.COMPACT, "folder", null, null);
                picker.setName(name + "-pick");
                UiKit.onChange(picker, () -> {
                    if (!flavor.busy() && form.editable(row.name())) {
                        flavor.pick(row, form.text(row.name()), picked -> changed(row,
                              () -> form.text(row.name(), picked)));
                    }
                });
                Table pick = new Table();
                pick.add(path).growX().minWidth(0);
                pick.add(picker).padLeft(space("xs"));
                input = path;
                field = kit.formField(row.label(), pick);
                line.add(field).growX().minWidth(0);
            }
            default -> {
                UiTextField text = kit.input(form.text(row.name()), true);
                if (row.kind() == Kind.INTEGER) {
                    text.setTextFieldFilter((current, typed) -> Character.isDigit(typed) || typed == '-');
                } else if (row.kind() == Kind.FLOAT) {
                    text.setTextFieldFilter((current, typed) -> Character.isDigit(typed) || typed == '-'
                          || typed == '.');
                }
                UiKit.onChange(text, () -> changed(row, () -> form.text(row.name(), text.getText())));
                input = text;
                field = kit.formField(row.label(), text);
                line.add(field).width(fieldWidth).left();
            }
            case KEY -> {
                key = keyControl(row, name);
                input = key.cap;
                line.add(wrapped(row.label(), "body", "text.primary")).growX().minWidth(0);
                line.add(key.cap).padLeft(space("sm"));
                line.add(key.restore).padLeft(space("xs"));
                line.add(key.unbind).padLeft(space("xxs"));
            }
        }
        input.setName(name);
        Table marks = new Table();
        flavor.marks(row, marks);
        Image changed = new Image(kit.skin.getDrawable("token-dot"));
        changed.setName(name + "-changed");
        kit.tip(changed, true).getActor().setText(Messages.getString("Lobby.gameOptions.changedMark"));
        marks.add(changed).padLeft(space("sm"));
        // Beside a field's input rather than its label
        line.add(marks).left().top().padTop(field == null ? size("control.sm") / 2 - changed.getPrefHeight() / 2
              : size("control.sm") / 2 + space("xs"));
        if (row.kind() != Kind.SLIDER && row.kind() != Kind.FILE && row.kind() != Kind.KEY) {
            line.add().expandX();
        }
        if (key != null) {
            line.row();
            key.problemCell = line.add((Label) null).colspan(line.getColumns()).left().growX();
        }
        line.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        line.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, @Nullable Actor fromActor) {
                if (pointer == -1) {
                    help.setText(flavor.help(row));
                    flavor.pointed(row);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, @Nullable Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(line))) {
                    help.setText(flavor.texts().hint());
                    flavor.pointed(null);
                }
            }
        });
        controls.put(row.name(), new Control(row, input, field, reading, picker, changed, key));
        return line;
    }

    // ---- Key bindings

    /** A key binding's keycap, which takes the next key on a click, and its buttons back to the default and to unbind. */
    private KeyControl keyControl(Row row, String name) {
        UiButton cap = kit.button("button-secondary" + UiTheme.COMPACT, null, null, null);
        kit.tip(cap, true).getActor().setText(Messages.getString("ClientSettings.controls.capture.tooltip"));
        UiKit.onChange(cap, () -> {
            if (row.equals(capturing)) {
                endCapture();
                refresh();
            } else {
                capture(row);
            }
        });
        UiButton restore = kit.button("button-quiet" + UiTheme.COMPACT, "restart", null, null);
        restore.setName(name + "-restore");
        kit.tip(restore, true).getActor().setText(Messages.getString("ClientSettings.controls.restore"));
        UiKit.onChange(restore, () -> {
            String shipped = form.defaultText(row.name());
            if (shipped != null) {
                changed(row, () -> form.text(row.name(), shipped));
            }
        });
        UiButton unbind = kit.button("button-quiet" + UiTheme.COMPACT, "close", null, null);
        unbind.setName(name + "-unbind");
        kit.tip(unbind, true).getActor().setText(Messages.getString("ClientSettings.controls.unbind"));
        UiKit.onChange(unbind, () -> changed(row, () -> form.text(row.name(), SettingsForm.key(0, 0))));
        Label problem = wrapped("", "small", "status.danger");
        problem.setName(name + "-problem");
        KeyControl key = new KeyControl(cap, restore, unbind, problem);
        cap.add(key.caps);
        return key;
    }

    /** Shows the binding as the form has it: its keys, or that it waits for one, with its problem and buttons. */
    private void showKey(Control control, boolean disabled) {
        KeyControl key = control.key();
        String name = control.row().name();
        String value = form.text(name);
        String problem = form.error(name);
        boolean waiting = control.row().equals(capturing);
        String shown = waiting ? "?" : value + '|' + problem;
        if (!shown.equals(key.shown)) {
            key.shown = shown;
            key.caps.clearChildren();
            if (waiting) {
                key.caps.add(kit.edgeChip(Messages.getString("ClientSettings.controls.press"), "accent"));
            } else if (SettingsForm.keyCode(value) == 0) {
                key.caps.add(kit.typeLabel(Messages.getString("ClientSettings.controls.unbound"), "small",
                      "text.muted"));
            } else {
                for (String part : keyParts(SettingsForm.keyCode(value), SettingsForm.keyModifiers(value))) {
                    key.caps.add(kit.edgeChip(part, problem == null ? "neutral" : "danger"))
                          .padLeft(key.caps.getCells().isEmpty() ? 0 : space("xxs"));
                }
            }
            key.problem.setText(problem == null ? "" : problem);
            key.problemCell.setActor(problem == null ? null : key.problem);
            key.problemCell.padTop(problem == null ? 0 : space("xxs"));
        }
        key.cap.setDisabled(disabled);
        String shipped = form.defaultText(name);
        key.restore.setVisible(!disabled && shipped != null && !shipped.equals(value));
        key.unbind.setVisible(!disabled && SettingsForm.keyCode(value) != 0);
    }

    /** The keys of a binding as a keycap shows them, one cap each: its modifiers in a fixed order, then its key. */
    static List<String> keyParts(int code, int modifiers) {
        List<String> parts = new ArrayList<>();
        for (int mask : new int[] { KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK, KeyEvent.SHIFT_DOWN_MASK,
                                    KeyEvent.META_DOWN_MASK }) {
            if ((modifiers & mask) != 0) {
                parts.add(KeyEvent.getModifiersExText(mask));
            }
        }
        parts.add(KeyEvent.getKeyText(code));
        return parts;
    }

    /** Waits for the binding's new key: the stage's keys come here first until one binds, Esc or a click elsewhere. */
    private void capture(Row row) {
        Control control = controls.get(row.name());
        Stage stage = page.getStage();
        if (control == null || stage == null || flavor.busy() || !form.editable(row.name())) {
            return;
        }
        endCapture();
        capturing = row;
        lonelyModifier = 0;
        captureStage = stage;
        stage.addCaptureListener(capture);
        // Typed characters go to the keycap rather than to the search
        stage.setKeyboardFocus(control.input());
        refresh();
    }

    /** Stops waiting for a key, without a change. */
    private void endCapture() {
        if (captureStage != null) {
            captureStage.removeCaptureListener(capture);
        }
        captureStage = null;
        capturing = null;
    }

    /** The key the binding waited for: it takes it. */
    private void captured(int code, int modifiers) {
        Row row = capturing;
        endCapture();
        if (row != null) {
            changed(row, () -> form.text(row.name(), SettingsForm.key(code, modifiers)));
        }
    }

    /**
     * The keys while a binding waits for one, ahead of every other listener of the stage, so neither Esc closes the
     * card nor Tab moves the focus: a key with the modifiers held binds; a modifier let go alone binds itself; Esc
     * keeps the binding as it was. A click outside the keycap stops waiting too, and goes on to what it hit.
     */
    private final class KeyCapture extends InputListener {
        @Override
        public boolean keyDown(InputEvent event, int keycode) {
            if (capturing == null) {
                return false;
            }
            event.cancel();
            if (keycode == Keys.ESCAPE) {
                endCapture();
                refresh();
                return true;
            }
            int awt = GpuBattleView.awtKey(keycode);
            if (modifier(awt)) {
                lonelyModifier = lonelyModifier == 0 ? awt : -1;
            } else if (awt != KeyEvent.VK_UNDEFINED) {
                captured(awt, GpuBattleView.modifiers());
            }
            return true;
        }

        @Override
        public boolean keyUp(InputEvent event, int keycode) {
            if (capturing == null) {
                return false;
            }
            event.cancel();
            int awt = GpuBattleView.awtKey(keycode);
            if (modifier(awt) && awt == lonelyModifier) {
                captured(awt, 0);
            }
            return true;
        }

        @Override
        public boolean keyTyped(InputEvent event, char character) {
            if (capturing == null) {
                return false;
            }
            event.cancel();
            return true;
        }

        @Override
        public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
            Control control = capturing == null ? null : controls.get(capturing.name());
            if (control != null && !event.getTarget().isDescendantOf(control.input())) {
                endCapture();
                refresh();
            }
            return false;
        }

        private static boolean modifier(int awt) {
            return awt == KeyEvent.VK_SHIFT || awt == KeyEvent.VK_CONTROL || awt == KeyEvent.VK_ALT
                  || awt == KeyEvent.VK_META;
        }
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** A change of a slider's setting while it is held: the form takes it and the page shows it, but not the flavor. */
    private void held(Row row, Runnable change) {
        if (syncing || flavor.busy()) {
            return;
        }
        change.run();
        held = row;
        refresh();
    }

    /** A change of a setting by its control: the form takes it, the flavor hears of it, and the page shows it. */
    private void changed(Row row, Runnable change) {
        if (syncing || flavor.busy()) {
            return;
        }
        change.run();
        flavor.changed(row);
        refresh();
    }

    /** Shows the form: every shown setting's value, whether it can change, its problem and dot; then the flavor's. */
    void refresh() {
        syncing = true;
        try {
            boolean busy = flavor.busy();
            for (Control control : controls.values()) {
                String name = control.row().name();
                boolean disabled = !form.editable(name) || busy;
                switch (control.input()) {
                    case UiChoice check -> {
                        if (check.isChecked() != form.bool(name)) {
                            check.setChecked(form.bool(name));
                        }
                        check.setDisabled(disabled);
                    }
                    case UiSelect select -> {
                        int index = control.row().values().indexOf(form.text(name));
                        if (index >= 0 && index != select.getSelectedIndex()) {
                            select.select(index);
                        }
                        select.setDisabled(disabled);
                    }
                    case UiTextField text -> {
                        if (!text.getText().equals(form.text(name))) {
                            text.setText(form.text(name));
                        }
                        text.setDisabled(disabled);
                    }
                    case Slider slider -> {
                        int value = number(form.text(name), control.row().min());
                        if (!slider.isDragging() && Math.round(slider.getValue()) != value) {
                            slider.setProgrammaticChangeEvents(false);
                            slider.setValue(value);
                            slider.setProgrammaticChangeEvents(true);
                        }
                        slider.setDisabled(disabled);
                    }
                    default -> {
                    }
                }
                if (control.reading() != null) {
                    control.reading().setText(form.text(name) + control.row().unit());
                }
                if (control.picker() != null) {
                    control.picker().setDisabled(disabled);
                }
                if (control.field() != null) {
                    control.field().error(form.error(name));
                }
                if (control.key() != null) {
                    showKey(control, disabled);
                }
                control.changed().setVisible(form.changed(name));
            }
            if (!search.field.getText().equals(form.search())) {
                search.field.setText(form.search());
            }
            flavor.refreshed();
        } finally {
            syncing = false;
        }
    }

    /** Whether the view is showing the form, so a control's change event is its own rather than the player's. */
    boolean syncing() {
        return syncing;
    }

    /** Runs {@code change} as the view's own: the controls it touches fire no change of the form. */
    void sync(Runnable change) {
        boolean was = syncing;
        syncing = true;
        try {
            change.run();
        } finally {
            syncing = was;
        }
    }

    static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /** Swing tooltips and texts carry HTML line breaks and styling; the Stage shows plain lines. */
    static String plain(String text) {
        return text == null ? "" : text.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").strip();
    }
}
