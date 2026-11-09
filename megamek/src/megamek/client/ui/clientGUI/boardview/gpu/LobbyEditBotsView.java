/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.lobby.EditBotsForm;
import megamek.client.lobby.EditBotsForm.Row;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;

/**
 * The game's bots and dropped-out players as a card over the battle, the native counterpart of the Swing Edit Bots
 * dialog: each seat shows who runs it, the actions it offers as a segmented bar, and its bot settings, which open the
 * bot setup over this card. Apply hands what was chosen over and closes.
 * <p>
 * GPU thread.
 */
final class LobbyEditBotsView {
    /** What the card asks of the window. */
    interface Host {
        /**
         * Opens the bot setup for {@code row} over this card; {@code configured} takes the confirmed settings on the
         * GPU thread.
         */
        void settings(Row row, Consumer<BehaviorSettings> configured);

        /** Takes what was chosen; the card has closed. */
        void apply(EditBotsForm.Result result);
    }

    private final EditBotsForm form;
    private final List<Runnable> refreshes = new ArrayList<>();
    private final UiButton apply;

    LobbyEditBotsView(UiKit kit, UiDialog dialog, EditBotsForm form, Host host, Runnable close) {
        this.form = form;
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        Label intro = kit.typeLabel(Messages.getString("EditBotsDialog.native.intro"), "small", "text.secondary");
        intro.setWrap(true);
        body.add(intro).row();
        if (form.rows().isEmpty()) {
            Label empty = kit.typeLabel(Messages.getString("EditBotsDialog.native.empty"), "body", "text.primary");
            empty.setWrap(true);
            empty.setName("bots-empty");
            body.add(empty).padTop(space("md")).row();
        }
        for (int index = 0; index < form.rows().size(); index++) {
            body.add(seat(kit, form.rows().get(index), index, host)).padTop(space(index == 0 ? "md" : "sm")).row();
        }
        apply = dialog.primary(Messages.getString("EditBotsDialog.native.apply"), () -> {
            EditBotsForm.Result result = form.result();
            close.run();
            host.apply(result);
        });
        apply.setName("bots-apply");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("bots-cancel");
        refresh();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** One seat: its player and type tag on top, then its actions, bot settings and saved settings. */
    private Table seat(UiKit kit, Row row, int index, Host host) {
        Table card = kit.panel(true);
        card.setName("bots-seat-" + index);
        card.defaults().left();
        Table top = new Table();
        top.add(kit.typeLabel(row.label(), "title", "text.primary")).left().growX();
        top.add(kit.chip(row.typeText(), tone(row.kind()), true)).right();
        card.add(top).growX().row();
        if (row.actions().isEmpty()) {
            return card;
        }
        Table controls = new Table();
        controls.defaults().left();
        UiSegmented actions = kit.tokenSegmented(true, row.actions().stream()
              .map(action -> Messages.getString(action.key())).toArray(String[]::new));
        actions.setName("bots-action-" + index);
        actions.select(row.actions().indexOf(row.action()));
        controls.add(actions).growX();
        UiButton settings = null;
        UiButton restore = null;
        TextTooltip settingsTip = null;
        if (row.kind() != EditBotsForm.Kind.REMOTE_BOT) {
            settings = kit.button("button-secondary", null, Messages.getString("EditBotsDialog.config"), null);
            settings.setName("bots-settings-" + index);
            controls.add(settings).padLeft(space("sm"));
            UiKit.onChange(settings, () -> {
                if (row.configurable()) {
                    host.settings(row, chosen -> {
                        row.configured(chosen);
                        actions.select(row.actions().indexOf(row.action()));
                        refresh();
                    });
                }
            });
            settingsTip = kit.tip(settings, true);
        }
        if (settings != null && row.hasSaved()) {
            restore = kit.button("button-secondary", null, Messages.getString("EditBotsDialog.configAvailable"),
                  null);
            restore.setName("bots-restore-" + index);
            controls.add(restore).padLeft(space("xs"));
            UiKit.onChange(restore, () -> {
                row.restore();
                refresh();
            });
        }
        card.add(controls).growX().padTop(space("sm")).row();
        UiKit.onChange(actions, () -> {
            int chosen = actions.getSelectedIndex();
            if (chosen >= 0 && chosen < row.actions().size()) {
                row.action(row.actions().get(chosen));
            }
            refresh();
        });
        UiButton settingsButton = settings;
        UiButton restoreButton = restore;
        TextTooltip tip = settingsTip;
        refreshes.add(() -> {
            if (settingsButton != null) {
                settingsButton.setDisabled(!row.configurable());
                tip.getActor().setText(Messages.getString(row.configurable() ? "EditBotsDialog.native.settingsTip"
                      : "EditBotsDialog.native.settingsOff"));
            }
            if (restoreButton != null) {
                restoreButton.setDisabled(!row.restorable());
            }
        });
        return card;
    }

    private static UiKit.Tone tone(EditBotsForm.Kind kind) {
        return switch (kind) {
            case GHOST -> UiKit.Tone.WARN;
            case LOCAL_BOT -> UiKit.Tone.OK;
            default -> UiKit.Tone.NORMAL;
        };
    }

    private void refresh() {
        refreshes.forEach(Runnable::run);
    }

    // ---- For the probe and the tests ----

    EditBotsForm form() {
        return form;
    }

    UiButton applyButton() {
        return apply;
    }
}
