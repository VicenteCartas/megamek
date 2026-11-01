/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.lobby.VictoryConditionsForm.Card;
import megamek.client.lobby.VictoryConditionsForm.Field;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;

/**
 * The lobby's victory conditions as objective cards: one card a master switch (Battle Value destroyed, ratio, turn
 * limit, kills, objectives) with its values, lit along its edge while it counts, and a General card; the server
 * password under them. The values and the rules are a {@link VictoryConditionsForm}'s.
 * <p>
 * GPU thread. OK sends the changed options on the Swing thread through {@link Host#send}.
 */
final class LobbyVictoryView {
    /** What the view asks of the window. */
    interface Host {
        /** Swing thread: sends the changed options and saves them as the last used ones. */
        void send(VictoryConditionsForm form);

        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);
    }

    /** A number range up to this many steps gets a stepper, a wider one a text field. */
    private static final int STEPPER_RANGE = 60;

    private final UiKit kit;
    private final VictoryConditionsForm form;
    private final Host host;
    private final Runnable close;
    private final Map<String, Actor> controls = new HashMap<>();
    private final List<Runnable> shows = new ArrayList<>();
    private final Label count;
    private final UiButton ok;
    private boolean busy;
    private boolean syncing;

    LobbyVictoryView(UiKit kit, UiDialog dialog, VictoryConditionsForm form, float bodyWidth, Host host,
          Runnable close) {
        this.kit = kit;
        this.form = form;
        this.host = host;
        this.close = close;
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        count = kit.typeLabel("", "title", "accent.primary");
        count.setName("victory-count");
        body.add(count).row();

        int columns = bodyWidth >= DesignTokens.shipped().size().get("dialog.md") ? 2 : 1;
        Table grid = new Table();
        grid.top().left();
        float gap = space("md");
        int index = 0;
        for (Card card : form.cards()) {
            boolean left = index % columns == 0;
            grid.add(card(card)).growX().uniformX().top().fillY().padTop(gap)
                  .padRight(columns > 1 && left ? gap / 2 : 0).padLeft(columns > 1 && !left ? gap / 2 : 0);
            if (++index % columns == 0) {
                grid.row();
            }
        }
        body.add(grid).row();

        UiTextField password = kit.password(true);
        password.setName("victory-password");
        UiKit.onChange(password, () -> form.password(password.getText()));
        body.add(kit.formField(Messages.getString("VictoryConditionsDialog.password").replace(":", "").trim(),
              password)).padTop(space("lg")).width(DesignTokens.shipped().size().get("dialog.sm") / 2).row();

        ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
        ok.setName("victory-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("victory-cancel");
        refresh();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static String name(String option) {
        return "victory-" + option.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** One objective card: its edge, title and switch, a line of its values, then each value's control. */
    private Table card(Card card) {
        Table frame = kit.card();
        frame.setName("victory-card-" + card.id().toLowerCase(Locale.ROOT).replace('_', '-'));
        frame.top().left();
        Image edge = new Image();
        frame.add(edge).width(space("xs")).growY().padRight(space("md"));
        Table content = new Table();
        content.top().left().defaults().left();
        frame.add(content).grow();

        Label title = kit.typeLabel(card.title(), "title", "text.primary");
        title.setWrap(true);
        Table head = new Table();
        head.add(title).growX().left().minWidth(0);
        if (card.master() != null) {
            String master = card.master().name();
            UiChoice toggle = kit.toggleSwitch("", form.bool(master));
            toggle.setName(name(master));
            tip(toggle, card.master().tooltip());
            UiKit.onChange(toggle, () -> {
                if (!syncing) {
                    form.bool(master, toggle.isChecked());
                    refresh();
                }
            });
            shows.add(() -> toggle.setChecked(form.bool(master)));
            controls.put(master, toggle);
            head.add(toggle).right().padLeft(space("sm"));
        }
        content.add(head).growX().row();
        Label summary = kit.typeLabel("", "small", "text.secondary");
        summary.setWrap(true);
        content.add(summary).growX().padTop(space("xxs")).row();
        shows.add(() -> {
            boolean active = form.active(card);
            summary.setText(form.summary(card));
            edge.setDrawable(kit.solid(active ? "accent.primary" : "border.subtle").getDrawable());
        });

        for (Field field : card.fields()) {
            content.add(field(field)).growX().padTop(space("sm")).row();
        }
        if (!card.note().isBlank()) {
            Label note = kit.typeLabel(card.note(), "small", "text.muted");
            note.setWrap(true);
            content.add(note).growX().padTop(space("sm")).row();
        }
        return frame;
    }

    private Actor field(Field field) {
        String option = field.name();
        if (field.kind() == VictoryConditionsForm.Kind.BOOLEAN) {
            UiChoice check = kit.check(field.label(), form.bool(option));
            check.setName(name(option));
            tip(check, field.tooltip());
            UiKit.onChange(check, () -> {
                if (!syncing) {
                    form.bool(option, check.isChecked());
                    refresh();
                }
            });
            shows.add(() -> {
                check.setChecked(form.bool(option));
                check.setDisabled(!form.enabled(option));
            });
            controls.put(option, check);
            Table row = new Table();
            row.add(check).left().expandX();
            return row;
        }
        Table row = new Table();
        Label label = kit.typeLabel(field.label(), "body", "text.secondary");
        label.setWrap(true);
        tip(label, field.tooltip());
        row.add(label).growX().left().minWidth(0).padRight(space("sm"));
        if (field.max() - field.min() <= STEPPER_RANGE) {
            UiStepper stepper = kit.stepper(form.number(option), field.min(), field.max());
            stepper.setName(name(option));
            stepper.up().setName(name(option) + "-up");
            stepper.down().setName(name(option) + "-down");
            UiKit.onChange(stepper, () -> {
                if (!syncing) {
                    form.number(option, stepper.value());
                    refresh();
                }
            });
            shows.add(() -> {
                stepper.value(form.number(option));
                stepper.up().setDisabled(!form.enabled(option) || stepper.value() >= field.max());
                stepper.down().setDisabled(!form.enabled(option) || stepper.value() <= field.min());
            });
            controls.put(option, stepper);
            row.add(stepper).right();
        } else {
            UiTextField input = kit.input(String.valueOf(form.number(option)), true);
            input.setName(name(option));
            input.setAlignment(Align.right);
            input.setTextFieldFilter((text, typed) -> Character.isDigit(typed));
            UiKit.onChange(input, () -> {
                if (!syncing) {
                    try {
                        form.number(option, Integer.parseInt(input.getText()));
                    } catch (NumberFormatException ignored) {
                        return;
                    }
                    refresh();
                }
            });
            shows.add(() -> input.setDisabled(!form.enabled(option)));
            controls.put(option, input);
            row.add(input).width(DesignTokens.shipped().size().get("control.lg") * 2).right();
        }
        return row;
    }

    private void tip(Actor actor, String text) {
        if (text != null && !text.isBlank()) {
            kit.tip(actor, true).getActor().setText(text);
        }
    }

    private void refresh() {
        syncing = true;
        try {
            shows.forEach(Runnable::run);
        } finally {
            syncing = false;
        }
        count.setText(Messages.getString("Lobby.victory.count", form.activeObjectives()));
        ok.setDisabled(busy);
    }

    private void confirm() {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        host.edt(() -> host.send(form), close);
    }

    // ---- For the probe and the tests ----

    VictoryConditionsForm form() {
        return form;
    }

    @Nullable Actor control(String option) {
        return controls.get(option);
    }
}
