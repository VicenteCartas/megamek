/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTheme;

/**
 * The developer GPU tuning utility (user correction 5, C.1 G17), which goes before release: its bracket utility
 * button, before Help and Menu in gameplay, and its panel below that row, 360 wide and ending at least 70 above the
 * window's bottom, whose pages scroll. The Board and Atmosphere pages show the two pages of the
 * {@link GpuBoardTuning} model row by row in the hud-v3 look: captions, sliders with their readings, checkboxes,
 * choices, the mode, preset and weather buttons, notes, and "Planetary conditions…", whose model button opens the
 * native conditions card through {@link GpuBoardSource#editPlanetaryConditions}. The Camera page holds the fixed sun and
 * the board camera's framing on selection and on movement. The model keeps every value and rule: an edit here goes to
 * the model's own control, which applies it, and each frame the open panel shows the model's state. Deleting this class
 * and its block in GpuHud removes the tool; the model keeps its defaults.
 */
final class GpuTuningPanel implements GpuHud.Component {
    private static final float WIDTH = 360;
    /** The panel ends at least 70 above the window's bottom. */
    private static final float BOTTOM = 70;
    /** A page's columns: a control's name or weather switch, its slider, the slider's reading. */
    private static final float NAME_WIDTH = 136;
    private static final float READING_WIDTH = 40;
    /** Model buttons whose caption here is a HUD message rather than the model's text. */
    private static final Map<String, String> KEYS = Map.of("tuning-planetary-conditions",
          "GpuBoard.hud.tuning.planetaryConditions");

    private final UiKit ui;
    private final GpuHudState state;
    private final BoardCamera camera;
    private final GpuBoardTuning tuning;
    private final UiFileDialog files;
    /** The window-sized root that places the panel; presses beside the panel reach the board. */
    private final Table root = new Table() {
        @Override
        public void act(float delta) {
            // Asset sync pauses board snapshots, but the HUD still draws and must show live progress.
            if (isVisible()) { shows.forEach(Runnable::run); }
            super.act(delta);
        }
    };
    private final UiButton button;
    /** Each copies the model's state into one shown widget; they run every frame while the panel is open. */
    private final List<Runnable> shows = new ArrayList<>();
    /** The panel's cell in the root, built when the panel first opens: until then the model costs nothing here. */
    private Cell<Table> panelCell;
    /** The open list of a choice. */
    private UiPopover choices;
    private Slider.SliderStyle sliders;
    private GpuHud.Metrics sized;
    /** The frame rate the header shows. */
    private int fpsShown = -1;

    /**
     * {@code tuning} is the model the board view reads, built with the source its "Planetary conditions…" button uses;
     * the panel edits it and the camera's framing flags.
     */
    GpuTuningPanel(GpuHudKit kit, BoardSource source, GpuHudState state, BoardCamera camera, GpuBoardTuning tuning) {
        ui = kit.ui;
        this.state = state;
        this.camera = camera;
        this.tuning = tuning;
        files = new UiFileDialog(ui);
        tuning.onChooseEnvironment(() -> {
            if (root.getStage() == null) { return; }
            files.show(root.getStage(), new UiFileDialog.Options(text("FileDialog.panoramaTitle"), UiFileDialog.Mode.OPEN,
                  "lighting-environments", java.nio.file.Path.of(System.getProperty("user.home", ".")), "",
                  new UiFileDialog.Filter(text("FileDialog.panoramaFilter"), "hdr", "png", "jpg", "jpeg")),
                  file -> { if (file != null) { tuning.loadEnvironment(file, source); } });
        });
        root.setName("tuning-panel");
        root.top().right();
        button = GpuUtilityBar.utility(ui, "tune", "GpuBoard.hud.tuning.title", "tuning-button");
        onChange(button, () -> state.toggle(GpuHudState.Dialog.TUNING));
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override public void dispose() {
        files.close();
        tuning.onChooseEnvironment(() -> { });
        if (choices != null) { choices.cancel(); }
    }

    /** The developer's Tuning utility. */
    Actor button() {
        return button;
    }

    /**
     * The utility, pressed while the panel is open; the open panel at its place, showing the model's state. Closing
     * the panel closes its open choice list.
     */
    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuHud.Metrics metrics = inputs.metrics();
        boolean open = state.dialog == GpuHudState.Dialog.TUNING;
        button.pressed(open);
        root.setVisible(open);
        if (!open) {
            if (choices != null) {
                choices.cancel();
            }
            return;
        }
        if (panelCell == null) {
            build();
        }
        if (!metrics.equals(sized)) {
            sized = metrics;
            float top = GpuUtilityBar.HEIGHT + 2 * metrics.gap();
            root.pad(top, 0, 0, metrics.gap());
            panelCell.width(Math.min(WIDTH, metrics.width() - 2 * metrics.gap()))
                  .maxHeight(metrics.height() - top - BOTTOM);
            root.invalidate();
        }
    }

    /** The panel: the header with its close button, the tabs, the chosen tab's page, and the footer with Defaults. */
    private void build() {
        sliders = sliderStyle();
        Table frame = ui.panel();
        frame.setName("tuning-frame");
        // It always lies over the right column's panels: the popover's opaque surface (.pop) keeps their text from
        // showing through, as the prototype's backdrop blur does under its translucent panels.
        frame.setBackground(ui.skin.getDrawable("panel-pop"));
        frame.setTouchable(Touchable.enabled);
        UiButton close = ui.closeButton(() -> state.dialog = GpuHudState.Dialog.NONE);
        close.setName("tuning-close");
        Table header = ui.header(text("GpuBoard.hud.tuning.title"), "", close);
        // The frame rate beside the title, in the count's smaller type.
        Label fps = header.findActor(UiKit.HEADER_COUNT);
        fps.setName("tuning-fps");
        shows.add(() -> {
            int rate = Gdx.graphics.getFramesPerSecond();
            if (rate != fpsShown) {
                fpsShown = rate;
                fps.setText(text("GpuBoard.hud.tuning.fps", String.valueOf(rate)));
            }
        });
        frame.add(header).growX().row();
        UiKit.Segmented tabs = ui.segmented("hud-tab", false, text("GpuBoard.hud.tuning.display"),
              text("GpuBoard.hud.tuning.board"), text("GpuBoard.hud.tuning.atmosphere"),
              text("GpuBoard.hud.tuning.terrain"), text("GpuBoard.hud.tuning.camera"));
        // Five tabs need a smaller gap than the toolkit's general-purpose tab row in this 360-unit panel.
        for (int i = 1; i < tabs.buttons.size(); i++) { tabs.getCell(tabs.buttons.get(i)).padLeft(14); }
        tabs.left().pad(2, 14, 0, 14);
        frame.add(tabs).growX().row();
        // Resolve the cache after the scene has drawn, only while its page is attached; before mirroring its labels.
        shows.add(() -> {
            if (root.findActor("tuning-display") != null) { tuning.showTerrainCacheStatus(); }
        });
        List<ScrollPane> pages = List.of(page("tuning-display", mirror(tuning.displayRows())),
              page("tuning-board", mirror(tuning.boardRows())),
              page("tuning-atmosphere", mirror(tuning.atmosphereRows())),
              page("tuning-terrain", mirror(tuning.terrainRows())), page("tuning-camera", cameraPage()));
        Cell<ScrollPane> shown = frame.add(pages.getFirst()).grow().minHeight(0);
        frame.row();
        for (int index = 0; index < pages.size(); index++) {
            int at = index;
            tabs.buttons.get(index).setName(pages.get(index).getName() + "-tab");
            // Each page keeps its scroll position while another one shows.
            onChange(tabs.buttons.get(index), () -> {
                choices.cancel();
                shown.setActor(pages.get(at));
                tabs.select(at);
            });
        }
        tabs.select(0);
        Table reload = new Table();
        reload.setName("tuning-reload-progress");
        UiKit.Meter progress = ui.meter("", false);
        Label count = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        count.setName("tuning-reload-count");
        count.setEllipsis(true);
        Label file = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        file.setName("tuning-reload-file");
        file.setEllipsis(true);
        var fileTip = ui.tip(file);
        reload.add(progress).growX().row();
        reload.add(count).minWidth(0).growX().left().padTop(5).row();
        reload.add(file).minWidth(0).growX().left().padTop(3);
        Cell<Table> reloadCell = frame.add((Table) null).growX();
        frame.row();
        shows.add(() -> {
            boolean active = !tuning.assetReloadPhase().isEmpty();
            if (active != (reloadCell.getActor() != null)) {
                reloadCell.setActor(active ? reload : null).pad(active ? 8 : 0, active ? 14 : 0,
                      active ? 8 : 0, active ? 14 : 0);
                frame.invalidateHierarchy();
            }
            if (!active) { return; }
            float fraction = tuning.assetReloadFraction();
            // Unknown totals pulse without inventing a percentage, including discovery and artwork capture.
            float filled = fraction < 0 ? .5f + .3f * (float) Math.sin(System.nanoTime() / 300_000_000.0) : fraction;
            progress.caption(tuning.assetReloadPhase()).set(fraction < 0 ? "…" : (int) (fraction * 100) + "%",
                  filled, UiTheme.MINT);
            count.setText(tuning.assetReloadStatus().getText());
            file.setText(tuning.assetReloadFile());
            fileTip.getActor().setText(tuning.assetReloadFile());
        });
        // The model's footer buttons: Defaults, Reload assets with its outcome, and the shader editor (M1 rows 8, 9).
        Table foot = ui.footer(frame);
        foot.add(button(tuning.defaults(), "hud-mini")).left();
        foot.add(button(tuning.reloadAssets(), "hud-mini")).padLeft(6);
        Label reloaded = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        reloaded.setEllipsis(true);
        shows.add(() -> reloaded.setText(tuning.assetReloadPhase().isEmpty() ? tuning.assetReloadStatus().getText() : ""));
        foot.add(reloaded).minWidth(0).growX().left().padLeft(6);
        foot.add(button(tuning.editShaders(), "hud-mini")).right();
        panelCell = root.add(frame);
        choices = new UiPopover(ui);
        choices.setName("tuning-choices");
        root.addActor(choices);
    }

    /** A tab's page: its rows in a scrolling list, padded as a panel's list. */
    private ScrollPane page(String name, Table rows) {
        rows.top().left().pad(6, 14, 12, 14);
        ScrollPane page = ui.scrollList(rows);
        page.setName(name);
        return page;
    }

    /**
     * One of the model's pages in the hud-v3 look, cell by cell as the model lays it out: a heading becomes a caption,
     * a slider row the name (or weather switch), the slider and its reading, a row of buttons segments or buttons, and
     * each control the toolkit's widget for it, named as the model's control.
     */
    private Table mirror(Table rows) {
        Table page = new Table();
        Actor previous = null;
        for (Cell<?> cell : rows.getCells()) {
            Actor model = cell.getActor();
            int span = cell.getColspan();
            if (model instanceof Slider slider) {
                page.add(slider(slider)).colspan(span).growX().minWidth(0).pad(4, 8, 4, 8);
            } else if (model instanceof CheckBox box) {
                var shown = checkbox(box.getName(), box.getText().toString(), tooltip(box), box::isChecked,
                      value -> { if (!box.isDisabled()) { box.setChecked(value); } });
                shows.add(() -> shown.getColor().a = box.isDisabled() ? .5f : 1);
                page.add(shown).colspan(span).minWidth(shown.getPrefWidth()).left().pad(5, 0, 5, 0);
            } else if (model instanceof TextButton toggle) {
                var buttonCell = page.add(toggle(toggle)).colspan(span).left().pad(2, 0, 2, 0);
                if (span > 1) { buttonCell.growX(); } else { buttonCell.width(NAME_WIDTH); }
            } else if (model instanceof SelectBox<?> choice) {
                // A button is never narrower than its text; the face's long choice ends in an ellipsis instead.
                page.add(select(choice)).colspan(span).growX().minWidth(0).pad(3, 8, 3, 0);
            } else if (model instanceof Label reading && previous instanceof Slider) {
                Label shown = ui.label(reading.getText().toString(), "hud-small", 11.5f, UiTheme.ACCENT);
                shown.setAlignment(Align.right);
                shows.add(() -> shown.setText(reading.getText()));
                page.add(shown).colspan(span).width(READING_WIDTH).right();
            } else if (model instanceof Label note && span > 1) {
                Label shown = ui.label(note.getText().toString(), "hud-small", 11.5f, UiTheme.MUTED);
                shown.setName(note.getName());
                shown.setWrap(true);
                tip(shown, note);
                shows.add(() -> shown.setText(note.getText()));
                page.add(shown).colspan(span).growX().pad(2, 0, 4, 0);
            } else if (model instanceof Label name) {
                Label shown = ui.label(name.getText().toString(), "hud-body", 12, UiTheme.ACCENT);
                shown.setEllipsis(true);
                page.add(shown).colspan(span).width(NAME_WIDTH).left();
            } else if (model instanceof Table heading && heading.getChildren().first() instanceof Label title) {
                page.add(heading(heading, title)).colspan(span).growX().padTop(page.hasChildren() ? 12 : 0)
                      .padBottom(7);
            } else if (model instanceof Table buttons) {
                page.add(buttons(buttons)).colspan(span).growX().pad(2, 0, 6, 0);
            } else {
                throw new IllegalStateException("The tuning panel has no hud-v3 form of " + model);
            }
            if (cell.isEndRow()) {
                page.row();
            }
            previous = model;
        }
        return page;
    }

    /**
     * The Camera page: the fixed sun, the board camera's framing animations on selection and on movement, and the
     * model's camera rows (Free Flight, the wireframe view, the field of view), then the camera's live
     * {@link BoardCamera#readout() readout}.
     */
    private Table cameraPage() {
        Table page = new Table();
        page.defaults().left().pad(5, 0, 5, 0);
        page.add(checkbox("camera-fixed-sun", text("GpuBoard.fixedSun"), text("GpuBoard.fixedSunHelp"),
              tuning::fixedSun, tuning::setFixedSun)).row();
        page.add(checkbox("camera-animate-selection", text("GpuBoard.animateSelection"),
              text("GpuBoard.cameraAnimationHelp"), () -> camera.animateOnSelectionChange,
              value -> camera.animateOnSelectionChange = value)).row();
        page.add(checkbox("camera-animate-movement", text("GpuBoard.animateMovement"),
              text("GpuBoard.cameraAnimationHelp"), () -> camera.animateOnMove,
              value -> camera.animateOnMove = value)).row();
        page.add(checkbox("camera-return-playback", text("GpuBoard.returnAfterPlayback"),
              text("GpuBoard.returnAfterPlaybackHelp"), camera::returnAfterPlayback,
              camera::setReturnAfterPlayback)).row();
        page.add(mirror(tuning.cameraRows())).growX().padTop(6).row();
        // The live view as one line, which reproduces it in a report, and its Copy.
        Label view = ui.label("", "hud-small", 11.5f, UiTheme.ACCENT);
        view.setName("camera-view");
        view.setWrap(true);
        // Only while the Camera page shows: the readout picks the terrain at the view's centre.
        shows.add(() -> {
            if (view.getStage() != null) { view.setText(camera.readout()); }
        });
        UiButton copy = ui.button("hud-mini", null, text("GpuBoard.hud.log.copy"), null);
        copy.setName("camera-view-copy");
        onChange(copy, () -> Gdx.app.getClipboard().setContents(camera.readout()));
        Table readout = new Table();
        readout.add(view).growX().minWidth(0);
        readout.add(copy).top().padLeft(8);
        page.add(readout).growX().padTop(6).row();
        return page;
    }

    /** A heading's title as a caption (.cap), with the model buttons beside it, such as "Planetary conditions…". */
    private Table heading(Table model, Label title) {
        Table heading = new Table();
        heading.add(ui.caption(title.getText().toString())).expandX().left();
        for (Actor actor : model.getChildren()) {
            if (actor != title) {
                heading.add(button((TextButton) actor, "hud-mini")).padLeft(8);
            }
        }
        return heading;
    }

    /**
     * A row of model buttons broken as the model breaks it: the modes of a group as segments (.seg), presets as
     * buttons that share their row (.sizes .b, 12 units).
     */
    private Table buttons(Table model) {
        Table buttons = new Table();
        buttons.defaults().growX().uniformX().space(6);
        for (Cell<?> cell : model.getCells()) {
            TextButton each = (TextButton) cell.getActor();
            UiButton shown = button(each, each.getButtonGroup() != null ? "hud-seg" : "hud");
            if (each.getButtonGroup() == null) {
                UiKit.size(shown.getLabel(), "hud-button", 12);
            }
            buttons.add(shown);
            if (cell.isEndRow()) {
                buttons.row();
            }
        }
        return buttons;
    }

    /**
     * A model button in a hud style, with its tooltip, pressed and enabled as the model's; a click chooses a mode of a
     * group or runs the button's action.
     */
    private UiButton button(TextButton model, String style) {
        String key = KEYS.get(model.getName());
        UiButton shown = ui.button(style, null, key == null ? model.getText().toString() : text(key), null);
        shown.setName(model.getName());
        tip(shown, model);
        onChange(shown, () -> press(model));
        shows.add(() -> {
            shown.pressed(model.isChecked());
            shown.setDisabled(model.isDisabled());
        });
        return shown;
    }

    /** A weather switch ("Rain On"), pressed while its slider is above zero; a click switches the slider. */
    private UiButton toggle(TextButton model) {
        UiButton shown = button(model, "hud-mini");
        shows.add(() -> shown.setText(UiTheme.upper(model.getText().toString())));
        return shown;
    }

    /** A click on a model button: a mode becomes its group's choice, any other button runs its action. */
    private static void press(TextButton model) {
        if (model.isDisabled()) {
            return;
        }
        if (model.getButtonGroup() != null) {
            model.setChecked(true);
        } else {
            ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
            model.fire(event);
            Pools.free(event);
        }
    }

    /** A checkbox (.tog) of a model value, ticked as the value is; a click sets the value. */
    private UiKit.Checkbox checkbox(String name, String label, String help, BooleanSupplier value,
          Consumer<Boolean> set) {
        UiKit.Checkbox box = ui.checkbox(label, value.getAsBoolean());
        box.setName(name);
        if (help != null) {
            ui.tip(box).getActor().setText(help);
        }
        onChange(box, () -> set.accept(box.isTicked()));
        shows.add(() -> box.ticked(value.getAsBoolean()));
        return box;
    }

    /**
     * A slider of the model's range and step, enabled as the model's and at its value, except while it is dragged; a
     * drag or an arrow-key step sets the model's value.
     */
    private Slider slider(Slider model) {
        Slider slider = new Slider(model.getMinValue(), model.getMaxValue(), model.getStepSize(), false, sliders);
        new UiSliderInput(slider);
        slider.setName(model.getName());
        tip(slider, model);
        onChange(slider, () -> model.setValue(slider.getValue()));
        shows.add(() -> {
            // Showing the model's value fires no ChangeEvent, so it is never written back.
            slider.setProgrammaticChangeEvents(false);
            if (slider.getMinValue() != model.getMinValue() || slider.getMaxValue() != model.getMaxValue()) {
                slider.setRange(model.getMinValue(), model.getMaxValue());
            }
            if (!slider.isDragging()) {
                slider.setValue(model.getValue());
            }
            slider.setProgrammaticChangeEvents(true);
            slider.setDisabled(model.isDisabled());
        });
        return slider;
    }

    /** A choice's face (.fsel) showing the model's choice; it opens the choices, and the one picked is the model's. */
    private UiButton select(SelectBox<?> model) {
        UiButton face = ui.select(String.valueOf(model.getSelected()), false);
        face.setName(model.getName());
        tip(face, model);
        onChange(face, () -> open(model, face));
        shows.add(() -> {
            face.setText(String.valueOf(model.getSelected()));
            face.setDisabled(model.isDisabled());
        });
        return face;
    }

    /** The choices of {@code model} in a list under its face, the current one checked. */
    private <T> void open(SelectBox<T> model, UiButton face) {
        UiMenuList list = new UiMenuList(ui);
        for (T item : model.getItems()) {
            list.item(String.valueOf(item), null, item == model.getSelected(), false, true, () -> {
                model.setSelected(item);
                choices.cancel();
            });
        }
        choices.content(list).openedFrom(face);
        Vector2 corner = face.localToStageCoordinates(new Vector2());
        choices.showAt(corner.x, corner.y);
    }

    /** The model control's tooltip on the shown widget, when it has one. */
    private void tip(Actor shown, Actor model) {
        String help = tooltip(model);
        if (help != null) {
            ui.tip(shown).getActor().setText(help);
        }
    }

    /** The text of the model control's tooltip, or null. */
    private static String tooltip(Actor model) {
        for (EventListener listener : model.getListeners()) {
            if (listener instanceof TextTooltip tooltip) {
                return tooltip.getActor().getText().toString();
            }
        }
        return null;
    }

    /**
     * The sliders' look, this panel's own (one user): the meter's 4-unit track (.meter .bar), mint up to a 4 x 14
     * knob in the pressed fill, white under the pointer; dim while disabled.
     */
    private Slider.SliderStyle sliderStyle() {
        Slider.SliderStyle style = new Slider.SliderStyle(bar(UiTheme.TRACK, 0, 4), bar(UiTheme.FILL, 4, 14));
        style.knobBefore = bar(UiTheme.MINT, 0, 4);
        style.knobOver = bar(Color.WHITE, 4, 14);
        style.knobDown = style.knobOver;
        style.disabledBackground = bar(UiTheme.alpha(UiTheme.TRACK, .05f), 0, 4);
        style.disabledKnobBefore = bar(UiTheme.DISABLED, 0, 4);
        style.disabledKnob = bar(UiTheme.DISABLED, 4, 14);
        return style;
    }

    /** A flat rectangle of the skin's white texel at a minimum size. */
    private Drawable bar(Color color, float width, float height) {
        Drawable bar = ui.skin.newDrawable("white", color);
        bar.setMinWidth(width);
        bar.setMinHeight(height);
        return bar;
    }
}
