/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.GameOptionsForm.Kind;
import megamek.client.lobby.GameOptionsForm.Page;
import megamek.client.lobby.GameOptionsForm.Row;
import megamek.client.lobby.GameOptionsForm.Section;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;

/**
 * The lobby's game options in a wide {@link UiDialog}, as the Swing {@code GameOptionsDialog} edits them in the lobby:
 * on the left the search, the tree of settings pages by category (the landing page on top), the number of matching
 * groups, the unofficial and legacy rule switches and the password; on the right the chosen page, its sections and
 * their options (check boxes, number and text fields that show their problems, and choices), each with its details as
 * a tooltip and its unofficial, legacy, important and changed marks; the landing page with the rules system's guidance.
 * The footer has Defaults, Save and Load, and OK, which sends the changed options and saves them all as the last used,
 * and Cancel. The values and rules are a {@link GameOptionsForm}'s; OK stays disabled while a number is wrong.
 * <p>
 * GPU thread. The game and the files are read and written on the Swing thread through the {@link Host}.
 */
final class LobbyGameOptionsView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: sends the changed options, when there are any, and saves all of them as the last used. */
        void send(String password, Vector<IBasicOption> changed, Vector<IBasicOption> all);

        /** Asks for a file and saves the options to it; {@code then} runs back on the GPU thread once done. */
        void save(Vector<IBasicOption> all, Runnable then);

        /**
         * Asks for a file and loads it into {@code into}; {@code then} gets, back on the GPU thread, whether one was
         * loaded.
         */
        void load(GameOptions into, Consumer<Boolean> then);

        /** Swing thread: refits the game's units after the BattleArmor grab bars option switched. */
        void grabBars();
    }

    /** The navigation column's width, and the narrowest body that still sets it beside the page. */
    private static final float NAV_WIDTH = 260;
    private static final float STACK_BELOW = 720;
    /** The widest a number, text or choice field grows; a check box takes the row. */
    private static final float FIELD_WIDTH = 320;
    private static final String PAGE_PREFIX = "options-page-";
    private static final String CATEGORY_PREFIX = "options-category-";

    private final UiKit kit;
    private final UiDialog dialog;
    private final LobbyNativeDialogs dialogs;
    private final Host host;
    private final Runnable close;
    private final float bodyWidth;
    private final UiKit.TokenSearch search;
    private final Label matches;
    private final UiTree tree;
    private final UiChoice unofficial;
    private final UiChoice legacy;
    private final UiTextField password;
    private final Table page = new Table();
    private final ScrollPane pageScroll;
    private final UiButton ok;
    private final UiButton defaults;
    private final UiButton load;
    private final UiButton save;
    /** The shown options' controls by option name, rebuilt with the page. */
    private final Map<String, Control> controls = new LinkedHashMap<>();
    private GameOptionsForm form;
    private String pageId;
    private Label rulesDescription;
    private boolean syncing;
    private boolean busy;

    /** One shown option: its control, its form field (null for a check box) and its changed mark. */
    private record Control(Row row, Actor input, @Nullable UiFormField field, Actor changed) { }

    /**
     * @param bodyWidth  the width the body has, which decides whether the navigation goes beside the page or above it
     * @param paneHeight the height of the page and of the navigation beside it
     */
    LobbyGameOptionsView(UiKit kit, UiDialog dialog, LobbyNativeDialogs dialogs, GameOptionsForm form, float bodyWidth,
          float paneHeight, Host host, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.dialogs = dialogs;
        this.form = form;
        this.host = host;
        this.close = close;
        this.bodyWidth = bodyWidth;
        float gap = space("md");
        boolean stacked = bodyWidth < STACK_BELOW;

        search = kit.tokenSearch(Messages.getString("GameOptionsDialog.Search"), true);
        search.field.setName("options-search");
        kit.tip(search.field, true).getActor().setText(Messages.getString("GameOptionsDialog.SearchToolTip"));
        UiKit.onChange(search.field, () -> {
            if (!syncing) {
                this.form.search(search.field.getText());
                showNavigation();
                showPage();
            }
        });
        matches = kit.typeLabel("", "small", "text.muted");
        matches.setName("options-matches");

        tree = new UiTree(kit, UiTheme.TOKEN);
        tree.setName("options-tree");
        tree.empty(Messages.getString("GameOptionsDialog.SearchNoMatches"));
        tree.onSelect(this::navigate);
        ScrollPane treeScroll = kit.scrollList(tree, true);
        treeScroll.setScrollingDisabled(true, false);

        unofficial = ruleSwitch("options-unofficial", "GameOptionsDialog.Unofficial", true);
        legacy = ruleSwitch("options-legacy", "GameOptionsDialog.Legacy", false);

        password = kit.input(form.password(), true);
        password.setName("options-password");
        UiKit.onChange(password, () -> this.form.password(password.getText()));
        String passwordLabel = Messages.getString("GameOptionsDialog.Password");

        Table nav = new Table();
        nav.top().left().defaults().left().growX();
        nav.add(search).minWidth(0).row();
        nav.add(matches).padTop(space("xs")).row();
        float treeHeight = stacked ? paneHeight / 2 : paneHeight - 4 * size("control.md") - 4 * gap;
        nav.add(treeScroll).height(Math.max(size("control.lg") * 2, treeHeight)).padTop(space("xs")).row();
        nav.add(unofficial).fill(false, false).left().padTop(gap).row();
        nav.add(legacy).fill(false, false).left().padTop(space("sm")).row();
        nav.add(kit.formField(passwordLabel.endsWith(":") ? passwordLabel.substring(0, passwordLabel.length() - 1)
              : passwordLabel, password)).padTop(gap).row();

        page.top().left().defaults().left().growX();
        page.setName("options-page");
        pageScroll = kit.scrollList(page, true);
        pageScroll.setScrollingDisabled(true, false);
        pageScroll.setFadeScrollBars(false);

        Table body = dialog.body();
        body.defaults().left();
        if (stacked) {
            body.add(nav).growX().row();
            body.add(pageScroll).growX().height(paneHeight).padTop(gap).row();
        } else {
            body.add(nav).width(NAV_WIDTH).top();
            body.add(pageScroll).grow().height(paneHeight).padLeft(space("lg"));
            body.row();
        }

        defaults = dialog.tool(Messages.getString("GameOptionsDialog.Defaults"), () -> {
            if (!busy) {
                this.form.defaults();
                showAll();
            }
        });
        defaults.setName("options-defaults");
        save = dialog.tool(Messages.getString("GameOptionsDialog.Save"), this::saveFile);
        save.setName("options-save");
        load = dialog.tool(Messages.getString("GameOptionsDialog.Load"), this::loadFile);
        load.setName("options-load");
        ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
        ok.setName("options-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("options-cancel");

        pageId = form.pages().isEmpty() ? "" : form.pages().get(0).id();
        bind();
        showAll();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /** Swing tooltips and texts carry HTML line breaks and styling; the Stage shows plain lines. */
    static String plain(String text) {
        return text == null ? "" : text.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").strip();
    }

    private void bind() {
        form.onGrabBarsChanged(() -> host.edt(host::grabBars, () -> { }));
    }

    // ---- Navigation

    private UiChoice ruleSwitch(String name, String key, boolean isUnofficial) {
        UiChoice toggle = kit.toggleSwitch(Messages.getString(key), true);
        toggle.setName(name);
        kit.tip(toggle, true).getActor().setText(Messages.getString(key + ".tooltip"));
        UiKit.onChange(toggle, () -> {
            if (syncing) {
                return;
            }
            if (toggle.isChecked()) {
                shown(isUnofficial, true);
                return;
            }
            // Hiding turns the hidden rules off, so it asks first, as the Swing dialog does
            syncing = true;
            toggle.setChecked(true);
            syncing = false;
            dialogs.confirm(Messages.getString("GameOptionsDialog.warning.title"),
                  Messages.getString("GameOptionsDialog.HideWarning"), () -> shown(isUnofficial, false));
        });
        return toggle;
    }

    private void shown(boolean isUnofficial, boolean shown) {
        if (isUnofficial) {
            form.unofficialShown(shown);
        } else {
            form.legacyShown(shown);
        }
        showAll();
    }

    private void navigate(String id) {
        if (id.startsWith(PAGE_PREFIX)) {
            pageId = id.substring(PAGE_PREFIX.length());
        } else if (id.startsWith(CATEGORY_PREFIX)) {
            String category = id.substring(CATEGORY_PREFIX.length());
            form.visiblePages().stream().filter(each -> each.categoryId().equals(category)).findFirst()
                  .ifPresent(first -> pageId = first.id());
            tree.expand(id, true);
        }
        tree.select(PAGE_PREFIX + pageId);
        showPage();
    }

    /** The tree of the pages shown now, by category; a page outside one goes at the top level. */
    private void showNavigation() {
        List<Page> visible = form.visiblePages();
        Map<String, List<UiTree.Node>> categories = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (Page each : visible) {
            UiTree.Node node = new UiTree.Node(PAGE_PREFIX + each.id(), each.title());
            if (each.isLanding()) {
                order.add(node);
            } else {
                if (!categories.containsKey(each.categoryId())) {
                    order.add(each.categoryId());
                    titles.put(each.categoryId(), each.categoryTitle());
                }
                categories.computeIfAbsent(each.categoryId(), key -> new ArrayList<>()).add(node);
            }
        }
        List<UiTree.Node> roots = new ArrayList<>();
        for (Object each : order) {
            if (each instanceof UiTree.Node node) {
                roots.add(node);
            } else {
                String category = (String) each;
                roots.add(new UiTree.Node(CATEGORY_PREFIX + category, titles.get(category))
                      .children(categories.get(category)));
            }
        }
        tree.nodes(roots);
        if (visible.stream().noneMatch(each -> each.id().equals(pageId)) && !visible.isEmpty()) {
            pageId = visible.get(0).id();
        }
        tree.select(visible.isEmpty() ? "" : PAGE_PREFIX + pageId);
        if (form.searching()) {
            tree.expandAll();
            int groups = visible.stream().mapToInt(each -> form.visibleSections(each).size()).sum();
            matches.setText(groups == 0 ? Messages.getString("GameOptionsDialog.SearchNoMatches")
                  : String.format(Messages.getString("GameOptionsDialog.SearchMatches"), groups));
        } else {
            matches.setText("");
        }
    }

    // ---- The page

    private void showAll() {
        showNavigation();
        showPage();
    }

    private void showPage() {
        controls.clear();
        page.clearChildren();
        rulesDescription = null;
        Page shown = form.visiblePages().stream().filter(each -> each.id().equals(pageId)).findFirst()
              .orElse(null);
        if (shown == null) {
            refresh();
            return;
        }
        Table header = new Table();
        header.left();
        header.add(kit.typeLabel(shown.title(), "title", "text.primary")).left();
        if (shown.advanced()) {
            Actor advanced = kit.chip(Messages.getString("Lobby.gameOptions.advancedMark"), UiKit.Tone.WARN, true);
            kit.tip(advanced, true).getActor().setText(Messages.getString("GameOptionsDialog.legend.advanced"));
            header.add(advanced).padLeft(space("md"));
        }
        header.add().expandX();
        page.add(header).row();
        if (!shown.categoryTitle().isBlank()) {
            page.add(kit.typeLabel(shown.categoryTitle(), "small", "text.muted")).row();
        }
        if (shown.isLanding()) {
            page.add(wrapped(Messages.getString("GameOptionsDialog.page.landing.intro"), "body", "text.secondary"))
                  .padTop(space("md")).row();
            page.add(wrapped(plain(Messages.getString("GameOptionsDialog.page.landing.quote")), "small",
                  "text.muted")).padTop(space("md")).row();
        }
        boolean first = true;
        for (Section section : form.visibleSections(shown)) {
            page.add(kit.caption(section.title(), true)).padTop(space(first && !shown.isLanding() ? "lg" : "xl"))
                  .row();
            first = false;
            if (!section.summary().isBlank()) {
                page.add(wrapped(plain(section.summary()), "small", "text.muted")).padTop(space("xs")).row();
            }
            for (Row row : form.visibleRows(shown, section)) {
                page.add(row(row)).padTop(space("sm")).row();
                if (OptionsConstants.RULES_SYSTEM.equals(row.name())) {
                    rulesDescription = wrapped("", "small", "text.secondary");
                    rulesDescription.setName("options-rules-description");
                    page.add(rulesDescription).padTop(space("xs")).row();
                }
            }
        }
        pageScroll.layout();
        pageScroll.setScrollY(0);
        refresh();
    }

    private Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    private Table row(Row row) {
        Table line = new Table();
        line.left().top();
        Actor input;
        UiFormField field = null;
        String name = "option-" + row.name();
        switch (row.kind()) {
            case BOOLEAN -> {
                UiChoice check = kit.check(row.label(), form.bool(row.name()));
                UiKit.onChange(check, () -> changed(() -> form.bool(row.name(), check.isChecked())));
                input = check;
                line.add(check).left().minWidth(0);
            }
            case INTEGER_CHOICE, CHOICE -> {
                UiSelect select = kit.tokenSelect(true, row.labels().toArray(String[]::new));
                int index = row.values().indexOf(form.text(row.name()));
                if (index >= 0) {
                    select.select(index);
                }
                UiKit.onChange(select, () -> changed(() -> form.text(row.name(),
                      row.values().get(select.getSelectedIndex()))));
                input = select;
                field = kit.formField(row.label(), select);
                line.add(field).width(Math.min(FIELD_WIDTH, bodyWidth)).left();
            }
            default -> {
                UiTextField text = kit.input(form.text(row.name()), true);
                if (row.kind() == Kind.INTEGER) {
                    text.setTextFieldFilter((current, typed) -> Character.isDigit(typed) || typed == '-');
                } else if (row.kind() == Kind.FLOAT) {
                    text.setTextFieldFilter((current, typed) -> Character.isDigit(typed) || typed == '-'
                          || typed == '.');
                }
                UiKit.onChange(text, () -> changed(() -> form.text(row.name(), text.getText())));
                input = text;
                field = kit.formField(row.label(), text);
                line.add(field).width(Math.min(FIELD_WIDTH, bodyWidth)).left();
            }
        }
        input.setName(name);
        String tooltip = plain(row.tooltip());
        if (form.locked(row.name())) {
            String reason = Messages.getString("GameOptionsDialog.lockedByScenario");
            tooltip = tooltip.isBlank() ? reason : tooltip + "\n\n" + reason;
        }
        if (!tooltip.isBlank()) {
            kit.tip(input, true).getActor().setText(tooltip);
        }
        Table marks = new Table();
        if (form.locked(row.name())) {
            Actor lock = kit.chip(Messages.getString("Lobby.gameOptions.lockedMark"), UiKit.Tone.NORMAL, true);
            kit.tip(lock, true).getActor().setText(Messages.getString("GameOptionsDialog.lockedByScenario"));
            marks.add(lock).padLeft(space("sm"));
        }
        if (row.important()) {
            Actor important = kit.chip(Messages.getString("Lobby.gameOptions.importantMark"), UiKit.Tone.NORMAL, true);
            kit.tip(important, true).getActor().setText(Messages.getString("GameOptionsDialog.legend.important"));
            marks.add(important).padLeft(space("sm"));
        }
        if (row.unofficial()) {
            Actor mark = kit.chip(Messages.getString("Lobby.gameOptions.unofficialMark"), UiKit.Tone.NORMAL, true);
            kit.tip(mark, true).getActor().setText(Messages.getString("GameOptionsDialog.legend.unofficial"));
            marks.add(mark).padLeft(space("sm"));
        }
        if (row.legacy()) {
            Actor mark = kit.chip(Messages.getString("Lobby.gameOptions.legacyMark"), UiKit.Tone.NORMAL, true);
            kit.tip(mark, true).getActor().setText(Messages.getString("GameOptionsDialog.legend.legacy"));
            marks.add(mark).padLeft(space("sm"));
        }
        Actor changed = kit.chip(Messages.getString("Lobby.gameOptions.changedMark"), UiKit.Tone.OK, true);
        changed.setName(name + "-changed");
        marks.add(changed).padLeft(space("sm"));
        // Next to a field's input rather than its label
        line.add(marks).left().top().padTop(field == null ? 0 : size("control.sm") / 2 + space("xs"));
        line.add().expandX();
        controls.put(row.name(), new Control(row, input, field, changed));
        return line;
    }

    /** A change of an option by its control: the form takes it, and the page shows what follows from it. */
    private void changed(Runnable change) {
        if (syncing || busy) {
            return;
        }
        change.run();
        refresh();
    }

    /** Shows the form: every shown option's value, whether it can change, its problem and mark; and the buttons. */
    private void refresh() {
        syncing = true;
        try {
            boolean editable = form.editable();
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
                    default -> {
                    }
                }
                if (control.field() != null) {
                    control.field().error(form.error(name));
                }
                control.changed().setVisible(form.changed(name));
            }
            if (rulesDescription != null) {
                rulesDescription.setText(plain(form.rulesSystemDescription()));
            }
            unofficial.setVisible(form.hasUnofficialToggle());
            unofficial.setChecked(form.unofficialShown());
            unofficial.setDisabled(!editable || busy);
            legacy.setVisible(form.hasLegacyToggle());
            legacy.setChecked(form.legacyShown());
            legacy.setDisabled(!editable || busy);
            if (!search.field.getText().equals(form.search())) {
                search.field.setText(form.search());
            }
            if (!password.getText().equals(form.password())) {
                password.setText(form.password());
            }
            password.setDisabled(!editable);
        } finally {
            syncing = false;
        }
        defaults.setVisible(form.editable());
        load.setVisible(form.editable());
        defaults.setDisabled(busy);
        load.setDisabled(busy);
        save.setDisabled(busy || !form.valid());
        ok.setDisabled(busy || !form.editable() || !form.valid());
    }

    // ---- Files and OK

    private void saveFile() {
        if (busy || !form.valid()) {
            return;
        }
        Vector<IBasicOption> all = form.allOptions();
        busy = true;
        refresh();
        host.save(all, () -> {
            busy = false;
            refresh();
        });
    }

    private void loadFile() {
        if (busy) {
            return;
        }
        GameOptions copy = form.copyOfOptions();
        busy = true;
        refresh();
        host.load(copy, loaded -> {
            busy = false;
            if (loaded) {
                form.load(copy);
                showAll();
            }
            refresh();
        });
    }

    private void confirm() {
        if (busy || !form.editable() || !form.valid()) {
            return;
        }
        busy = true;
        refresh();
        String pass = form.password();
        Vector<IBasicOption> changed = form.changedOptions();
        Vector<IBasicOption> all = form.allOptions();
        host.edt(() -> host.send(pass, changed, all), close);
    }

    /**
     * Shows the game's options anew as the server changed them, as the Swing dialog does: the edits are dropped, the
     * page, the search and the password stay.
     */
    void reload(GameOptionsForm fresh) {
        if (busy) {
            return;
        }
        fresh.search(form.search());
        fresh.password(form.password());
        form = fresh;
        bind();
        showAll();
    }

    // ---- For the probe and the tests ----

    GameOptionsForm form() {
        return form;
    }

    UiDialog dialog() {
        return dialog;
    }

    UiTree tree() {
        return tree;
    }

    /** The shown option's control, or null when its page does not show. */
    @Nullable Actor control(String name) {
        Control control = controls.get(name);
        return control == null ? null : control.input();
    }

    String pageId() {
        return pageId;
    }
}
