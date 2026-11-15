/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.FieldKind;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;
import megamek.logging.MMLogger;

/**
 * Native modal dialog over a scrim (C.1 G15, plan A.18 R1, swing-inventory 4.4): the newest request of the modal
 * bridge ({@link GpuBoardSource#dialog()}) on the HUD's top layer, which needs no board. The prototype's dialog (.dlg)
 * shows the request's title and message and a body by kind: MESSAGE the text alone, or with its image on the left (a
 * story dialog's); CHOICE a list whose highlighted row is the choice (a double click confirms it); MULTI a list of
 * ticks, at most {@code max}, with Select All and Clear All and, under the list, the request's own button, which
 * clears the ticks of its rows; INPUT a text field, which takes only an integer of its range, shown beside it, when
 * it is bounded; FORM one control per field. An optional checkbox follows. Enter presses
 * the default button, and Esc and the close button the cancel button (-1: the close box); button 0 confirms the
 * selection, the text or the values. A FORM answers one value per field: the text of an INTEGER or TEXT field, the
 * chosen text of a CHOICE field (it starts on the field's initial text, else on its first choice), "true" or "false"
 * for a CHECKBOX (it starts as its initial text parses); changing a live field answers at once with
 * {@link DialogAnswer#CHANGED} and those values. Each request is answered exactly once through
 * {@link GpuBoardSource#answer}; until the bridge shows the next request, the answered dialog stays with its controls
 * disabled. The dialog owns the texture of its image and frees it when it leaves.
 */
final class GpuModalDialog implements GpuHud.Component {
    private static final MMLogger LOGGER = MMLogger.create(GpuModalDialog.class);
    /** A MESSAGE's image fits this box left of the text, never above its own size, as a story dialog's portrait. */
    private static final float IMAGE_WIDTH = 240;
    private static final float IMAGE_HEIGHT = 320;
    private static final float IMAGE_GAP = 16;
    /** A text field (.field with the chat's 34) and a button of the button row (.b: min-height 36, 12.5 captions). */
    private static final float FIELD_HEIGHT = 34;
    private static final float BUTTON_HEIGHT = 36;
    private static final float BUTTON_WIDTH = 88;
    private static final float CAPTION = 12.5f;
    /** The fonts draw no tab; prompts indent continuation lines with one. */
    private static final String TAB = "    ";

    private final UiKit ui;
    private final GpuBoardSource source;
    /** Shares UIKit's single backdrop with any Controls or Menu dialog underneath this request. */
    private final UiModal root;
    /** The request on screen with its controls, or null. */
    private Prompt prompt;
    private Cell<Table> dialogCell;
    private GpuHud.Metrics sized;

    GpuModalDialog(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.source = source;
        root = new UiModal(ui, this::cancel, false);
        root.setName("modal-dialog");
        // The HUD keeps the keyboard focus on the dialog or one of its fields while a request is pending.
        root.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return prompt != null && prompt.keyDown(event, keycode);
            }
        });
    }

    @Override
    public Actor actor() {
        return root;
    }

    /**
     * Shows the pending request: a new one replaces the dialog and takes the keyboard, null removes it. The dialog is
     * centred, as wide and high as the prototype's dialogs at most; its body scrolls.
     */
    @Override
    public void update(GpuHud.Inputs inputs) {
        DialogRequest request = inputs.dialog();
        root.open(request != null);
        if (prompt != null && (request == null || prompt.request.id() != request.id())) {
            prompt.close();
            prompt = null;
            root.clearChildren();
        }
        if (request == null) {
            return;
        }
        if (prompt == null) {
            prompt = new Prompt(request);
            dialogCell = root.add(prompt.dialog);
            sized = null;
        }
        GpuHud.Metrics metrics = inputs.metrics();
        if (!metrics.equals(sized)) {
            sized = metrics;
            dialogCell.width(Math.min(UiKit.DIALOG_WIDTH, metrics.width() - 2 * metrics.gap()))
                  .maxHeight(metrics.height() - UiKit.DIALOG_MARGIN);
            root.invalidate();
        }
        prompt.reveal();
        focus();
    }

    /** Also called after HUD layout makes this slot visible; a higher modal retains its own text field. */
    void focus() {
        Stage stage = root.getStage();
        if (stage == null || UiModal.active(stage) != root) { return; }
        root.focus();
        if (prompt != null && prompt.first != null && !prompt.answered && stage.getKeyboardFocus() == root) {
            stage.setKeyboardFocus(prompt.first);
        }
    }

    /** CANCEL while a dialog is pending: closes an open choice list, otherwise presses the cancel button. */
    void cancel() {
        if (prompt != null) {
            prompt.cancel();
        }
    }

    /** Frees the image of a dialog still on screen when the HUD goes. */
    @Override
    public void dispose() {
        if (prompt != null) {
            prompt.close();
        }
    }

    /** A text as the fonts draw it: a Windows line break as one break and a tab as spaces. */
    private static String plain(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", TAB);
    }

    /** A wrapped paragraph of body text, from its top left. */
    private Label paragraph(String text) {
        Label label = ui.label(plain(text), "hud-body", 13, UiTheme.TEXT);
        label.setWrap(true);
        label.setAlignment(Align.topLeft);
        return label;
    }

    /** One request on screen: its dialog and controls, and whether it was answered. */
    private final class Prompt {
        final DialogRequest request;
        final Table dialog;
        /** The first text field, which takes the keyboard; null when the dialog itself takes it. */
        final TextField first;
        private final List<UiButton> buttons = new ArrayList<>();
        /** CHOICE and MULTI: the list, its rows and the ticks of MULTI. */
        private final List<UiButton> rows = new ArrayList<>();
        private final boolean[] ticks;
        private UiMenuList list;
        private ScrollPane listScroll;
        private UiButton selectAll;
        /** MULTI: the request's own button and the rows whose ticks it clears; null and empty without one. */
        private UiButton clearRows;
        private List<Integer> ownRows = List.of();
        /** INPUT and FORM: the text fields in order, the bounded ones, and each FORM field's value. */
        private final List<TextField> fields = new ArrayList<>();
        private final List<Bounded> bounded = new ArrayList<>();
        private final List<Supplier<String>> values = new ArrayList<>();
        /** FORM: the open list of a CHOICE field. */
        private UiPopover choices;
        private UiKit.Checkbox checkbox;
        /** MESSAGE: its image, which this dialog owns; null without one. */
        private Texture texture;
        private boolean answered;
        private boolean revealed;

        Prompt(DialogRequest request) {
            this.request = request;
            ticks = new boolean[request.rows().size()];
            dialog = ui.dialog(request.title(), this::cancel);
            switch (request.kind()) {
                case MESSAGE -> body(request.image() == null ? paragraph(request.message()) : picture());
                case CHOICE, MULTI -> items();
                case INPUT -> input();
                case FORM -> form();
            }
            if (!request.checkbox().isEmpty()) {
                checkbox = ui.checkbox(plain(request.checkbox()), request.checkboxInitial());
                dialog.add(checkbox).left().padTop(12).row();
            }
            dialog.add(buttons()).growX().padTop(16);
            first = fields.isEmpty() ? null : fields.getFirst();
            if (first != null) {
                first.selectAll();
            }
            check();
        }

        /** The body, which scrolls when the dialog is too high for the window; returns its cell. */
        private Cell<ScrollPane> body(Actor body) {
            Cell<ScrollPane> cell = dialog.add(ui.scrollList(body)).grow().minHeight(0);
            dialog.row();
            return cell;
        }

        /**
         * A MESSAGE with an image: the image, fitted into its box, left of the text. An image that does not decode
         * leaves the text alone.
         */
        private Actor picture() {
            Label text = paragraph(request.message());
            try {
                byte[] bytes = Base64.getDecoder().decode(request.image());
                Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
                try {
                    texture = new Texture(pixmap, true);
                } finally {
                    pixmap.dispose();
                }
            } catch (RuntimeException unreadable) {
                LOGGER.warn(unreadable, "The dialog \"{}\" shows its text without its image, which does not decode",
                      request.title());
                return text;
            }
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            float scale = Math.min(1, Math.min(IMAGE_WIDTH / texture.getWidth(), IMAGE_HEIGHT / texture.getHeight()));
            Image image = new Image(new TextureRegionDrawable(texture));
            image.setName("modal-image");
            Table row = new Table();
            row.add(image).top().size(texture.getWidth() * scale, texture.getHeight() * scale).padRight(IMAGE_GAP);
            row.add(text).grow().top();
            return row;
        }

        /** The message above a list or a field, when there is one. */
        private void message() {
            if (!request.message().isEmpty()) {
                dialog.add(paragraph(request.message())).growX().padBottom(10).row();
            }
        }

        /** CHOICE and MULTI: the rows, each with its detail as the tooltip; MULTI rows carry a check mark. */
        private void items() {
            boolean multi = request.kind() == DialogKind.MULTI;
            message();
            list = new UiMenuList(ui);
            for (int index = 0; index < ticks.length; index++) {
                DialogRow row = request.rows().get(index);
                int at = index;
                ticks[index] = multi && request.initiallySelected().contains(index);
                UiButton item = list.item(plain(row.label()), null, multi ? ticks[index] : null, false,
                      row.enabled(), () -> pick(at));
                if (!row.detail().isEmpty()) {
                    ui.tip(item).getActor().setText(plain(row.detail()));
                }
                if (!multi) {
                    // As a JOptionPane list, a double click confirms the row.
                    item.addListener(new ClickListener() {
                        @Override
                        public void clicked(InputEvent event, float x, float y) {
                            if (getTapCount() == 2) {
                                press(0);
                            }
                        }
                    });
                }
                rows.add(item);
            }
            listScroll = body(list).getActor();
            if (multi && request.clearRows() != null) {
                ownRows = request.clearRows().rows().stream().filter(row -> row >= 0 && row < ticks.length).toList();
                clearRows = ui.button("hud-mini", null, plain(request.clearRows().label()), null);
                if (!request.clearRows().tip().isEmpty()) {
                    ui.tip(clearRows).getActor().setText(plain(request.clearRows().tip()));
                }
                UiKit.onChange(clearRows, () -> {
                    ownRows.forEach(row -> ticks[row] = false);
                    check();
                });
                dialog.add(clearRows).left().padTop(8).row();
            }
            if (!multi && !request.initiallySelected().isEmpty()) {
                int row = request.initiallySelected().getFirst();
                if (row >= 0 && row < rows.size()) {
                    list.highlight(rows.get(row));
                }
            }
        }

        /** A click on a row: it becomes the choice, or MULTI ticks or clears it (refused beyond {@code max}). */
        private void pick(int index) {
            if (request.kind() == DialogKind.MULTI) {
                tick(index);
            }
            list.highlight(rows.get(index));
        }

        private void tick(int index) {
            if (ticks[index] || !full()) {
                ticks[index] = !ticks[index];
                check();
            }
        }

        private boolean full() {
            int ticked = 0;
            for (boolean tick : ticks) {
                ticked += tick ? 1 : 0;
            }
            return request.max() != null && ticked >= request.max();
        }

        /** INPUT: the message, then the field with its range beside it when it is bounded. */
        private void input() {
            if (!request.message().isEmpty()) {
                body(paragraph(request.message())).padBottom(10);
            }
            dialog.add(field(Objects.requireNonNullElse(request.initialText(), ""), request.min(), request.max()))
                  .growX().row();
        }

        /** FORM: the message, then each field: a label beside its control, a checkbox on its own. */
        private void form() {
            message();
            Table form = new Table();
            form.top().left();
            form.defaults().left().padTop(8);
            for (DialogField field : request.fields()) {
                switch (field.kind()) {
                    case INTEGER, TEXT -> {
                        form.add(ui.label(plain(field.label()), "hud-body", 13, UiTheme.ACCENT)).padRight(12);
                        boolean integer = field.kind() == FieldKind.INTEGER;
                        form.add(field(field.initial(), integer ? field.min() : null, integer ? field.max() : null))
                              .growX().row();
                        TextField text = fields.getLast();
                        values.add(text::getText);
                    }
                    case CHOICE -> {
                        form.add(ui.label(plain(field.label()), "hud-body", 13, UiTheme.ACCENT)).padRight(12);
                        form.add(choice(field)).growX().row();
                    }
                    case CHECKBOX -> {
                        UiKit.Checkbox box = ui.checkbox(plain(field.label()), Boolean.parseBoolean(field.initial()));
                        form.add(box).colspan(2).row();
                        values.add(() -> Boolean.toString(box.isTicked()));
                        if (field.live()) {
                            UiKit.onChange(box, () -> answer(DialogAnswer.CHANGED));
                        }
                    }
                }
            }
            body(form);
        }

        /**
         * A text field; with {@code min} or {@code max} it takes digits (and a minus sign when negative values are
         * allowed) and shows its range beside it, which turns coral while the text is no integer of the range.
         */
        private Table field(String initial, Integer min, Integer max) {
            TextField field = new TextField(Objects.requireNonNullElse(initial, ""), ui.skin, "hud");
            // The HUD's other fields lie in other panels: Tab moves between this dialog's fields only (keyDown), and
            // the tab it types enters no field.
            field.setFocusTraversal(false);
            boolean integer = min != null || max != null;
            boolean negative = min == null || min < 0;
            field.setTextFieldFilter((text, character) -> integer
                  ? Character.isDigit(character) || negative && character == '-' : character != '\t');
            fields.add(field);
            UiKit.onChange(field, this::check);
            Table line = new Table();
            line.add(field).growX().height(FIELD_HEIGHT);
            if (integer) {
                String range = (min == null ? "" : min.toString()) + "\u2013" + (max == null ? "" : max.toString());
                Label label = ui.label(range, "hud-small", 11.5f, Color.WHITE);
                bounded.add(new Bounded(field, min, max, label));
                line.add(label).padLeft(10);
            }
            return line;
        }

        /** A FORM choice: a drop-down face that opens the choices in a list over the dialog. */
        private Actor choice(DialogField field) {
            String[] value = { field.choices().contains(field.initial()) ? field.initial()
                  : field.choices().isEmpty() ? "" : field.choices().getFirst() };
            values.add(() -> value[0]);
            UiButton face = ui.select(value[0], false);
            UiKit.onChange(face, () -> {
                if (choices == null) {
                    choices = new UiPopover(ui);
                    root.addActor(choices);
                }
                UiMenuList options = new UiMenuList(ui);
                for (String choice : field.choices()) {
                    options.item(choice, null, choice.equals(value[0]), false, true, () -> {
                        boolean changed = !choice.equals(value[0]);
                        value[0] = choice;
                        face.setText(choice);
                        choices.cancel();
                        if (changed && field.live()) {
                            answer(DialogAnswer.CHANGED);
                        }
                    });
                }
                choices.content(options).openedFrom(face);
                Vector2 corner = face.localToStageCoordinates(new Vector2());
                choices.showAt(corner.x, corner.y);
            });
            return face;
        }

        /**
         * The button row: Select All and Clear All for MULTI, then the request's buttons; the default is the filled
         * commit button (.b.main), at the quiet buttons' height and caption size.
         */
        private Table buttons() {
            Table row = new Table();
            if (request.kind() == DialogKind.MULTI) {
                selectAll = ui.button("hud-mini", null, text("ChoiceDialog.SelectAll"), null);
                UiKit.onChange(selectAll, () -> setAll(true));
                UiButton clearAll = ui.button("hud-mini", null, text("ChoiceDialog.ClearAll"), null);
                UiKit.onChange(clearAll, () -> setAll(false));
                row.add(selectAll);
                row.add(clearAll).padLeft(6);
            }
            row.add().expandX();
            for (int index = 0; index < request.buttons().size(); index++) {
                int at = index;
                boolean main = index == request.defaultButton();
                UiButton button = ui.button(main ? "hud-main" : "hud", null, request.buttons().get(index), null);
                if (main) {
                    UiKit.size(button.getLabel(), "hud-main", CAPTION);
                }
                UiKit.onChange(button, () -> press(at));
                buttons.add(button);
                row.add(button).minWidth(BUTTON_WIDTH).height(BUTTON_HEIGHT).padLeft(8);
            }
            return row;
        }

        /** Select All ticks every enabled row (only offered when they fit in {@code max}); Clear All clears all. */
        private void setAll(boolean ticked) {
            for (int index = 0; index < ticks.length; index++) {
                ticks[index] = ticked && request.rows().get(index).enabled();
            }
            check();
        }

        /**
         * Shows the state: the ticks, the rows a full MULTI refuses, the ranges a field misses, and button 0, which
         * confirms only valid values.
         */
        private void check() {
            if (answered) {
                return;
            }
            boolean full = full();
            long enabled = request.rows().stream().filter(DialogRow::enabled).count();
            for (int index = 0; index < rows.size(); index++) {
                UiButton row = rows.get(index);
                if (request.kind() == DialogKind.MULTI) {
                    // The menu row's first icon is its check mark.
                    row.icons.getFirst().setVisible(ticks[index]);
                }
                row.setDisabled(!request.rows().get(index).enabled() || full && !ticks[index]);
            }
            if (selectAll != null) {
                selectAll.setDisabled(request.max() != null && request.max() < enabled);
            }
            if (clearRows != null) {
                clearRows.setDisabled(ownRows.stream().noneMatch(row -> ticks[row]));
            }
            boolean valid = true;
            for (Bounded field : bounded) {
                boolean ok = field.valid();
                field.range().setColor(ok ? UiTheme.MUTED : UiTheme.CORAL);
                valid &= ok;
            }
            if (!buttons.isEmpty()) {
                buttons.getFirst().setDisabled(!valid);
            }
        }

        /** Enter, the list keys, Space for a tick or the checkbox and Tab between fields; a choice list goes first. */
        boolean keyDown(InputEvent event, int key) {
            if (choices != null && event.getTarget().isDescendantOf(choices)) {
                return false;
            }
            if (answered) {
                return true;
            }
            boolean typing = event.getTarget() instanceof TextField;
            if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) {
                press(request.defaultButton());
            } else if (key == Input.Keys.TAB) {
                next(UIUtils.shift() ? -1 : 1, event.getTarget());
            } else if (typing) {
                return false;
            } else if (list != null && (key == Input.Keys.UP || key == Input.Keys.DOWN || key == Input.Keys.HOME
                  || key == Input.Keys.END)) {
                list.keyDown(key);
            } else if (key == Input.Keys.SPACE) {
                toggle();
            } else {
                return false;
            }
            return true;
        }

        /** Space: ticks or clears the highlighted MULTI row, otherwise flips the checkbox. */
        private void toggle() {
            int highlighted = rows.indexOf(rows.stream().filter(UiButton::isChecked).findFirst().orElse(null));
            if (request.kind() == DialogKind.MULTI && highlighted >= 0) {
                tick(highlighted);
            } else if (checkbox != null) {
                checkbox.toggle();
            }
        }

        /** Tab and Shift+Tab: the next or previous text field of this dialog. */
        private void next(int step, Actor from) {
            if (fields.isEmpty()) {
                return;
            }
            int at = fields.indexOf(from);
            TextField next = at < 0 ? fields.get(step > 0 ? 0 : fields.size() - 1)
                  : fields.get(Math.floorMod(at + step, fields.size()));
            root.getStage().setKeyboardFocus(next);
            next.selectAll();
        }

        /** Presses button {@code index} unless there is none (a default of -1) or it is disabled. */
        private void press(int index) {
            if (index >= 0 && index < buttons.size() && !buttons.get(index).isDisabled()) {
                answer(index);
            }
        }

        /** Esc and the close button: an open choice list closes first, otherwise the cancel button answers. */
        void cancel() {
            if (choices != null && choices.cancel()) {
                return;
            }
            answer(request.cancelButton());
        }

        /**
         * Answers once: button 0 with the selection, the text or the values, a live field's change with the values,
         * any other button (-1 being the close box) without them; the checkbox as the player left it. The dialog
         * then takes no more input.
         */
        private void answer(int button) {
            if (answered) {
                return;
            }
            answered = true;
            boolean confirmed = button == 0;
            List<Integer> selected = new ArrayList<>();
            for (int index = 0; confirmed && index < rows.size(); index++) {
                if (request.kind() == DialogKind.MULTI ? ticks[index] : rows.get(index).isChecked()) {
                    selected.add(index);
                }
            }
            String typed = confirmed && request.kind() == DialogKind.INPUT ? fields.getFirst().getText() : null;
            List<String> answers = confirmed || button == DialogAnswer.CHANGED
                  ? values.stream().map(Supplier::get).toList() : List.of();
            boolean checked = checkbox == null ? request.checkboxInitial() : checkbox.isTicked();
            source.answer(request.id(), new DialogAnswer(button, selected, typed, checked, answers));
            dialog.setTouchable(Touchable.disabled);
            buttons.forEach(each -> each.setDisabled(true));
            fields.forEach(each -> each.setDisabled(true));
        }

        /** Once the dialog has its size, the list opens on the chosen row, without scrolling to it. */
        void reveal() {
            if (!revealed && list != null && dialog.getHeight() > 0) {
                revealed = true;
                rows.stream().filter(UiButton::isChecked).findFirst().ifPresent(row -> {
                    list.highlight(row);
                    listScroll.updateVisualScroll();
                });
            }
        }

        /** The dialog leaves the screen: its open choice list closes and its image is freed. */
        void close() {
            if (choices != null) {
                choices.cancel();
            }
            if (texture != null) {
                texture.dispose();
                texture = null;
            }
        }
    }

    /** A bounded text field and its range label: valid while its text is an integer of the range. */
    private record Bounded(TextField field, Integer min, Integer max, Label range) {
        boolean valid() {
            try {
                int value = Integer.parseInt(field.getText());
                return (min == null || value >= min) && (max == null || value <= max);
            } catch (NumberFormatException notAnInteger) {
                return false;
            }
        }
    }
}
