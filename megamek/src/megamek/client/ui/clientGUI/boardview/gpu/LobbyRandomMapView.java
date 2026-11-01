/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RandomMapForm.Feature;
import megamek.client.lobby.RandomMapForm.Level;
import megamek.client.lobby.RandomMapForm.MountainStyle;
import megamek.client.lobby.RandomMapForm.Param;
import megamek.client.lobby.RandomMapForm.Row;
import megamek.client.lobby.RandomMapForm.Section;
import megamek.client.lobby.RandomMapForm.Tab;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTabs;

/**
 * The lobby's random map settings in a {@link UiDialog}, as the Swing {@code RandomMapDialog}: the normal view sets
 * each terrain feature to none, low, medium or high, the advanced view each of the generator's numbers, both grouped
 * by the dialog's themes (civilization, elevation, nature, water, effects) as tabs; the board size and theme above
 * them, and Load, Apply and Save and Apply below. The values and their checks are a {@link RandomMapForm}'s.
 * <p>
 * GPU thread. The form is changed here and read on the Swing thread only through the {@link Host}, while the view
 * waits for it.
 */
final class LobbyRandomMapView {
    /** What the view asks of the window. */
    interface Host {
        /** Swing thread: remembers the normal or the advanced view, as the dialog does. */
        void advanced(boolean advanced);

        /** Asks for a settings file and loads it into the form; {@code then} runs back on the GPU thread. */
        void load(RandomMapForm form, Runnable then);

        /** Asks for a file, then applies the settings and saves them; {@code then} gets whether they were. */
        void save(RandomMapForm form, java.util.function.Consumer<Boolean> then);

        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);
    }

    private static final int SLIDER_RANGE = 100;

    private final UiKit kit;
    private final UiDialog dialog;
    private final RandomMapForm form;
    private final Host host;
    private final Runnable close;
    private final UiSegmented mode;
    private final UiTabs tabs;
    private final UiStepper boardWidth;
    private final UiStepper boardHeight;
    private final UiSelect theme;
    private final List<String> themes;
    private final Table page = new Table();
    private final Label problem;
    private final UiButton ok;
    private final UiButton save;
    private final UiButton load;
    private final List<Runnable> cityRows = new ArrayList<>();
    private boolean busy;

    /**
     * @param themes the tileset's themes, as the dialog lists them
     * @param close  closes the dialog
     */
    LobbyRandomMapView(UiKit kit, UiDialog dialog, RandomMapForm form, List<String> themes, Host host,
          Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.form = form;
        this.host = host;
        this.close = close;
        List<String> offered = new ArrayList<>(themes);
        if (form.theme() != null && !offered.contains(form.theme())) {
            offered.add(0, form.theme());
        }
        this.themes = List.copyOf(offered);
        Table body = dialog.body();
        body.top().left();

        Table head = new Table();
        mode = kit.tokenSegmented(true, Messages.getString("RandomMapDialog.Normal"),
              Messages.getString("RandomMapDialog.Advanced"));
        mode.setName("random-mode");
        mode.option(0).setName("random-normal");
        mode.option(1).setName("random-advanced");
        mode.select(form.advanced() ? 1 : 0);
        UiKit.onChange(mode, () -> {
            boolean advanced = mode.getSelectedIndex() == 1;
            form.advanced(advanced);
            host.edt(() -> host.advanced(advanced), () -> { });
            fillPage();
        });
        head.add(mode).left();
        head.add().growX();
        boardWidth = kit.stepper(Messages.getString("Lobby.map.random.width"), form.boardWidth(),
              LobbyCommands.MIN_BOARD_SIZE, LobbyCommands.MAX_BOARD_SIZE);
        name(boardWidth, "random-board-width");
        boardHeight = kit.stepper(Messages.getString("Lobby.map.random.height"), form.boardHeight(),
              LobbyCommands.MIN_BOARD_SIZE, LobbyCommands.MAX_BOARD_SIZE);
        name(boardHeight, "random-board-height");
        Runnable size = () -> {
            form.boardSize(boardWidth.value(), boardHeight.value());
            showProblem();
        };
        UiKit.onChange(boardWidth, size);
        UiKit.onChange(boardHeight, size);
        theme = kit.tokenSelect(true, this.themes.stream().map(LobbyRandomMapView::themeLabel)
              .toArray(String[]::new));
        theme.setName("random-theme");
        int themeIndex = this.themes.indexOf(form.theme());
        if (themeIndex >= 0) {
            theme.select(themeIndex);
        }
        tip(theme, Messages.getString("RandomMapDialog.mapThemeField.toolTip"));
        UiKit.onChange(theme, () -> form.theme(this.themes.get(theme.getSelectedIndex())));
        head.add(kit.typeLabel(trimColon(Messages.getString("RandomMapDialog.labTheme")), "small",
              "text.secondary")).padRight(space("sm"));
        head.add(theme).width(size("dialog.sm") / 2);
        body.add(head).growX().row();
        Table sizeRow = new Table();
        sizeRow.left();
        sizeRow.add(kit.typeLabel(trimColon(Messages.getString("RandomMapDialog.BoardSize")), "small",
              "text.secondary")).padRight(space("md"));
        sizeRow.add(boardWidth).padRight(space("lg"));
        sizeRow.add(boardHeight);
        body.add(sizeRow).growX().padTop(space("md")).row();

        String[] tabLabels = new String[Tab.values().length];
        for (Tab tab : Tab.values()) {
            tabLabels[tab.ordinal()] = tab.label();
        }
        tabs = kit.tokenTabs(true, tabLabels);
        tabs.setName("random-tabs");
        for (Tab tab : Tab.values()) {
            tabs.option(tab.ordinal()).setName("random-tab-" + tab.name().toLowerCase(Locale.ROOT));
        }
        tabs.select(Tab.ELEVATION.ordinal());
        UiKit.onChange(tabs, this::fillPage);
        body.add(tabs).left().padTop(space("lg")).row();
        page.top().left();
        body.add(page).growX().top().padTop(space("md")).row();
        problem = kit.typeLabel("", "small", "status.danger");
        problem.setWrap(true);
        problem.setName("random-problem");
        body.add(problem).growX().padTop(space("sm")).row();

        load = dialog.tool(Messages.getString("RandomMapDialog.Load"), this::load);
        load.setName("random-load");
        save = dialog.tool(Messages.getString("RandomMapDialog.Save"), this::save);
        save.setName("random-save");
        ok = dialog.primary(Messages.getString("Okay"), this::apply);
        ok.setName("random-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("random-cancel");
        fillPage();
    }

    RandomMapForm form() {
        return form;
    }

    /** The control named {@code name}, for the probe and the tests. */
    @Nullable Actor control(String name) {
        return dialog.card().findActor(name);
    }

    // ---- The page ----

    private void fillPage() {
        page.clearChildren();
        cityRows.clear();
        Tab tab = Tab.values()[Math.max(0, tabs.getSelectedIndex())];
        if (form.advanced()) {
            fillAdvanced(tab);
        } else {
            fillNormal(tab);
        }
        showProblem();
    }

    /** A feature to a row: its name, and None, Low, Medium and High; the civilization's city type below them. */
    private void fillNormal(Tab tab) {
        Table grid = new Table();
        grid.top().left();
        String[] levels = new String[Level.values().length];
        for (Level level : Level.values()) {
            levels[level.ordinal()] = level.label();
        }
        for (Feature feature : RandomMapForm.features(tab)) {
            Label label = kit.typeLabel(feature.label(), "body", "text.primary");
            tip(label, feature.tip());
            grid.add(label).left().padRight(space("xl")).padTop(space("sm"));
            UiSegmented levelBar = kit.tokenSegmented(true, levels);
            String name = "random-" + feature.name().toLowerCase(Locale.ROOT).replace('_', '-');
            levelBar.setName(name);
            for (Level level : Level.values()) {
                levelBar.option(level.ordinal()).setName(name + "-" + level.name().toLowerCase(Locale.ROOT));
            }
            levelBar.select(form.level(feature).ordinal());
            UiKit.onChange(levelBar, () -> {
                form.level(feature, Level.values()[levelBar.getSelectedIndex()]);
                showProblem();
            });
            grid.add(levelBar).left().padTop(space("sm")).row();
        }
        if (tab == Tab.CIVILIZATION) {
            Label label = kit.typeLabel(trimColon(Messages.getString("RandomMapDialog.labCity")), "body",
                  "text.primary");
            tip(label, Messages.getString("RandomMapDialog.cityTypeCombo.toolTip"));
            grid.add(label).left().padRight(space("xl")).padTop(space("sm"));
            grid.add(citySelect(false)).left().width(size("dialog.sm") / 2).padTop(space("sm")).row();
        }
        page.add(grid).left().top();
    }

    private UiSelect citySelect(boolean advanced) {
        UiSelect city = kit.tokenSelect(true, RandomMapForm.CITY_TYPES.stream().map(RandomMapForm::cityTypeLabel)
              .toArray(String[]::new));
        city.setName("random-city-type");
        city.select(RandomMapForm.CITY_TYPES.indexOf(form.cityType()));
        UiKit.onChange(city, () -> {
            form.cityType(RandomMapForm.CITY_TYPES.get(city.getSelectedIndex()));
            if (advanced) {
                cityRows.forEach(Runnable::run);
            }
            showProblem();
        });
        return city;
    }

    /** The tab's sections side by side in two columns, each a captioned list of its numbers. */
    private void fillAdvanced(Tab tab) {
        List<Section> sections = RandomMapForm.sections(tab);
        Table columns = new Table();
        columns.top().left();
        Table[] column = { new Table(), new Table() };
        for (Table each : column) {
            each.top().left();
            columns.add(each).top().growX().uniformX().padRight(space("xl"));
        }
        for (int index = 0; index < sections.size(); index++) {
            Table target = column[sections.size() == 1 ? 0 : index % 2];
            section(target, sections.get(index));
        }
        page.add(columns).growX().top();
    }

    private void section(Table target, Section section) {
        target.add(kit.caption(section.label(), true)).left().padTop(target.hasChildren() ? space("xl") : 0)
              .row();
        Table grid = new Table();
        grid.top().left();
        if (section == Section.GENERAL) {
            UiChoice invert = kit.check(trimColon(Messages.getString("RandomMapDialog.labInvertNegative")),
                  form.invertNegative());
            invert.setName("random-invert-negative");
            tip(invert, Messages.getString("RandomMapDialog.invertNegativeCheck.toolTip"));
            UiKit.onChange(invert, () -> form.invertNegative(invert.isChecked()));
            grid.add(invert).colspan(2).left().padTop(space("sm")).row();
        }
        if (section == Section.MOUNTAIN) {
            UiSelect style = kit.tokenSelect(true, java.util.Arrays.stream(MountainStyle.values())
                  .map(MountainStyle::label).toArray(String[]::new));
            style.setName("random-mountain-style");
            style.select(form.mountainStyle().ordinal());
            UiKit.onChange(style, () -> form.mountainStyle(MountainStyle.values()[style.getSelectedIndex()]));
            Label label = kit.typeLabel(trimColon(Messages.getString("RandomMapDialog.labMountainStyle")), "small",
                  "text.secondary");
            tip(label, Messages.getString("RandomMapDialog.mountainStyleCombo.toolTip"));
            grid.add(label).left().padRight(space("md")).padTop(space("sm"));
            grid.add(style).growX().padTop(space("sm")).row();
        }
        if (section == Section.CITY) {
            Label label = kit.typeLabel(trimColon(Messages.getString("RandomMapDialog.labCity")), "small",
                  "text.secondary");
            tip(label, Messages.getString("RandomMapDialog.cityTypeCombo.toolTip"));
            grid.add(label).left().padRight(space("md")).padTop(space("sm"));
            grid.add(citySelect(true)).growX().padTop(space("sm")).row();
        }
        for (Row row : RandomMapForm.rows(section)) {
            Label label = kit.typeLabel(row.label(), "small", "text.secondary");
            tip(label, row.first().tip());
            grid.add(label).left().padRight(space("md")).padTop(space("sm"));
            Table controls = new Table();
            controls.left();
            List<Runnable> enables = new ArrayList<>();
            enables.add(control(controls, row.first()));
            if (row.second() != null) {
                controls.add(kit.typeLabel("–", "body", "text.muted")).padLeft(space("sm")).padRight(space("sm"));
                enables.add(control(controls, row.second()));
            }
            grid.add(controls).growX().left().padTop(space("sm")).row();
            if (section == Section.CITY) {
                Runnable enable = () -> enables.forEach(Runnable::run);
                cityRows.add(enable);
                enable.run();
            }
        }
        target.add(grid).growX().left().row();
    }

    /**
     * A number's control: a slider with its value for a short range, else a stepper.
     *
     * @return what enables or disables it by the city type, for the city's numbers
     */
    private Runnable control(Table controls, Param param) {
        String name = "random-" + param.name().toLowerCase(Locale.ROOT).replace('_', '-');
        boolean city = param.section() == Section.CITY;
        if (param.max() - param.min() <= SLIDER_RANGE) {
            Slider slider = new Slider(param.min(), param.max(), 1, false, kit.tokenSliderStyle());
            slider.setName(name);
            slider.setValue(form.value(param));
            slider.addListener(new UiSliderInput(slider));
            Label value = kit.typeLabel(String.valueOf(form.value(param)), "small", "text.primary");
            value.setAlignment(com.badlogic.gdx.utils.Align.right);
            UiKit.onChange(slider, () -> {
                form.value(param, Math.round(slider.getValue()));
                value.setText(String.valueOf(form.value(param)));
                showProblem();
            });
            controls.add(slider).width(size("dialog.sm") / 3);
            controls.add(value).width(size("control.md")).padLeft(space("xs"));
            return () -> slider.setDisabled(city && "NONE".equals(form.cityType()));
        }
        UiStepper stepper = kit.stepper(form.value(param), param.min(), param.max());
        name(stepper, name);
        UiKit.onChange(stepper, () -> {
            form.value(param, stepper.value());
            showProblem();
        });
        controls.add(stepper);
        return () -> {
            boolean off = city && "NONE".equals(form.cityType());
            stepper.down().setDisabled(off);
            stepper.up().setDisabled(off);
        };
    }

    private void showProblem() {
        String text = form.problem();
        problem.setText(text == null ? "" : text);
        ok.setDisabled(busy || text != null);
        save.setDisabled(busy || text != null);
        load.setDisabled(busy);
    }

    // ---- Footer ----

    private void load() {
        busy(true);
        host.load(form, () -> {
            busy(false);
            reread();
        });
    }

    private void save() {
        busy(true);
        host.save(form, saved -> {
            busy(false);
            if (saved) {
                close.run();
            }
        });
    }

    private void apply() {
        busy(true);
        boolean[] applied = new boolean[1];
        host.edt(() -> applied[0] = form.apply(), () -> {
            busy(false);
            if (applied[0]) {
                close.run();
            } else {
                showProblem();
            }
        });
    }

    private void busy(boolean value) {
        busy = value;
        showProblem();
    }

    /** Shows the form anew, as after loading a file. */
    void reread() {
        boardWidth.value(form.boardWidth());
        boardHeight.value(form.boardHeight());
        int themeIndex = themes.indexOf(form.theme());
        if (themeIndex >= 0) {
            theme.select(themeIndex);
        }
        fillPage();
    }

    // ---- Helpers ----

    private static String themeLabel(String theme) {
        return theme == null || theme.isBlank() ? Messages.getString("ChatLounge.MapPreviewButton.defaultTheme")
              : theme;
    }

    private static void name(UiStepper stepper, String name) {
        stepper.setName(name);
        stepper.down().setName(name + "-down");
        stepper.up().setName(name + "-up");
    }

    private void tip(Actor actor, String text) {
        kit.tip(actor, true).getActor().setText(LobbyMapSetupView.plain(text));
    }

    private static String trimColon(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }
}
