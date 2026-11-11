/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuSettingsView.plain;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuSettingsView.space;

import java.util.Map;
import java.util.Vector;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.settings.SettingsForm.Page;
import megamek.client.ui.settings.SettingsForm.Row;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;

/**
 * The lobby's game options on the shared settings screen ({@link GpuSettingsView}): a rail tile per category, the
 * pages as tabs and their sections as cards; under the rail the unofficial and legacy rule switches and the password;
 * the landing page with the rules system's guidance; each option with its locked, important, unofficial and legacy
 * marks. The footer has Defaults, Save and Load, and OK, which sends the changed options and saves them all as the
 * last used, and Cancel. The values and rules are a {@link GameOptionsForm}'s; OK stays disabled while a number is
 * wrong.
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

    /** The rail's icons by category; the landing page is its own group. */
    private static final Map<String, String> ICONS = Map.of("landing", "flag", "general", "settings", "rules", "gavel",
          "combat", "target", "movement", "move", "aerospace", "flight", "initiativeAndPilots", "military-tech");

    private final UiKit kit;
    private final LobbyNativeDialogs dialogs;
    private final Host host;
    private final Runnable close;
    private final GpuSettingsView view;
    private UiChoice unofficial;
    private UiChoice legacy;
    private UiTextField password;
    private final UiButton ok;
    private final UiButton defaults;
    private final UiButton load;
    private final UiButton save;
    private GameOptionsForm form;
    private Label rulesDescription;
    private boolean busy;

    /**
     * @param bodyWidth  the width the body has, which decides whether the rail goes beside the page or above it
     * @param paneHeight the height of the page and of the rail beside it
     */
    LobbyGameOptionsView(UiKit kit, UiDialog dialog, LobbyNativeDialogs dialogs, GameOptionsForm form, float bodyWidth,
          float paneHeight, Host host, Runnable close) {
        this.kit = kit;
        this.dialogs = dialogs;
        this.form = form;
        this.host = host;
        this.close = close;
        view = new GpuSettingsView(kit, dialog, form, bodyWidth, paneHeight, new Flavor());

        defaults = dialog.tool(Messages.getString("GameOptionsDialog.Defaults"), () -> {
            if (!busy) {
                this.form.defaults();
                view.showAll();
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

        bind();
        view.open(null);
    }

    private void bind() {
        form.onGrabBarsChanged(() -> host.edt(host::grabBars, () -> { }));
    }

    /** The game options' parts of the settings screen. */
    private final class Flavor extends GpuSettingsView.Flavor {
        @Override
        String prefix() {
            return "options";
        }

        @Override
        String rowPrefix() {
            return "option-";
        }

        @Override
        GpuSettingsView.Texts texts() {
            return new GpuSettingsView.Texts(Messages.getString("GameOptionsDialog.Search"),
                  Messages.getString("GameOptionsDialog.SearchToolTip"),
                  Messages.getString("GameOptionsDialog.SearchNoMatches"),
                  Messages.getString("GameOptionsDialog.SearchMatches"),
                  Messages.getString("Lobby.gameOptions.hint"));
        }

        @Override
        String icon(String group) {
            return ICONS.getOrDefault(group, "settings");
        }

        @Override
        void navExtras(Table nav) {
            unofficial = ruleSwitch("options-unofficial", "GameOptionsDialog.Unofficial", true);
            legacy = ruleSwitch("options-legacy", "GameOptionsDialog.Legacy", false);
            password = kit.input(form.password(), true);
            password.setName("options-password");
            UiKit.onChange(password, () -> form.password(password.getText()));
            String label = Messages.getString("GameOptionsDialog.Password");
            nav.add(unofficial).fill(false, false).left().padTop(space("md")).row();
            nav.add(legacy).fill(false, false).left().padTop(space("sm")).row();
            nav.add(kit.formField(label.endsWith(":") ? label.substring(0, label.length() - 1) : label, password))
                  .padTop(space("md")).row();
        }

        @Override
        void header(Page page, Table header) {
            if (page.advanced()) {
                Actor advanced = kit.chip(Messages.getString("Lobby.gameOptions.advancedMark"), UiKit.Tone.WARN, true);
                kit.tip(advanced, true).getActor().setText(Messages.getString("GameOptionsDialog.legend.advanced"));
                header.add(advanced).padLeft(space("md"));
            }
        }

        @Override
        void intro(Page page, Table body) {
            if (page.isLanding()) {
                body.add(view.wrapped(Messages.getString("GameOptionsDialog.page.landing.intro"), "body",
                      "text.secondary")).padTop(space("md")).row();
                body.add(view.wrapped(plain(Messages.getString("GameOptionsDialog.page.landing.quote")), "small",
                      "text.muted")).padTop(space("md")).row();
            }
        }

        @Override
        @Nullable Actor afterRow(Row row) {
            if (!OptionsConstants.RULES_SYSTEM.equals(row.name())) {
                return null;
            }
            rulesDescription = view.wrapped("", "small", "text.secondary");
            rulesDescription.setName("options-rules-description");
            return rulesDescription;
        }

        @Override
        void marks(Row row, Table marks) {
            if (form.locked(row.name())) {
                mark(marks, "Lobby.gameOptions.lockedMark", "GameOptionsDialog.lockedByScenario");
            }
            if (row.tagged(GameOptionsForm.IMPORTANT)) {
                mark(marks, "Lobby.gameOptions.importantMark", "GameOptionsDialog.legend.important");
            }
            if (row.tagged(GameOptionsForm.UNOFFICIAL)) {
                mark(marks, "Lobby.gameOptions.unofficialMark", "GameOptionsDialog.legend.unofficial");
            }
            if (row.tagged(GameOptionsForm.LEGACY)) {
                mark(marks, "Lobby.gameOptions.legacyMark", "GameOptionsDialog.legend.legacy");
            }
        }

        private void mark(Table marks, String key, String legend) {
            Actor mark = kit.chip(Messages.getString(key), UiKit.Tone.NORMAL, true);
            kit.tip(mark, true).getActor().setText(Messages.getString(legend));
            marks.add(mark).padLeft(space("sm"));
        }

        @Override
        String help(Row row) {
            String help = plain(row.tooltip());
            if (form.locked(row.name())) {
                String reason = Messages.getString("GameOptionsDialog.lockedByScenario");
                help = help.isBlank() ? reason : help + "\n" + reason;
            }
            return help.isBlank() ? row.label() : help;
        }

        @Override
        boolean busy() {
            return busy;
        }

        @Override
        void refreshed() {
            showRest();
        }
    }

    private UiChoice ruleSwitch(String name, String key, boolean isUnofficial) {
        UiChoice toggle = kit.toggleSwitch(Messages.getString(key), true);
        toggle.setName(name);
        kit.tip(toggle, true).getActor().setText(Messages.getString(key + ".tooltip"));
        UiKit.onChange(toggle, () -> {
            if (view.syncing()) {
                return;
            }
            if (toggle.isChecked()) {
                shown(isUnofficial, true);
                return;
            }
            // Hiding turns the hidden rules off, so it asks first, as the Swing dialog does
            view.sync(() -> toggle.setChecked(true));
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
        view.showAll();
    }

    /** Shows what the settings screen leaves to the game options: the switches, the password and the footer. */
    private void showRest() {
        if (ok == null) {
            return;
        }
        boolean editable = form.editable();
        if (rulesDescription != null) {
            rulesDescription.setText(plain(form.rulesSystemDescription()));
        }
        unofficial.setVisible(form.hasUnofficialToggle());
        unofficial.setChecked(form.unofficialShown());
        unofficial.setDisabled(!editable || busy);
        legacy.setVisible(form.hasLegacyToggle());
        legacy.setChecked(form.legacyShown());
        legacy.setDisabled(!editable || busy);
        if (!password.getText().equals(form.password())) {
            password.setText(form.password());
        }
        password.setDisabled(!editable);
        defaults.setVisible(editable);
        load.setVisible(editable);
        defaults.setDisabled(busy);
        load.setDisabled(busy);
        save.setDisabled(busy || !form.valid());
        ok.setDisabled(busy || !editable || !form.valid());
    }

    // ---- Files and OK

    private void busy(boolean value) {
        busy = value;
        view.refresh();
    }

    private void saveFile() {
        if (busy || !form.valid()) {
            return;
        }
        Vector<IBasicOption> all = form.allOptions();
        busy(true);
        host.save(all, () -> busy(false));
    }

    private void loadFile() {
        if (busy) {
            return;
        }
        GameOptions copy = form.copyOfOptions();
        busy(true);
        host.load(copy, loaded -> {
            busy = false;
            if (loaded) {
                form.load(copy);
                view.showAll();
            } else {
                view.refresh();
            }
        });
    }

    private void confirm() {
        if (busy || !form.editable() || !form.valid()) {
            return;
        }
        busy(true);
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
        fresh.password(form.password());
        form = fresh;
        bind();
        view.form(fresh);
    }

    // ---- For the probe and the tests ----

    GameOptionsForm form() {
        return form;
    }

    UiDialog dialog() {
        return view.dialog();
    }

    /** The shown option's control, or null when its page does not show. */
    @Nullable Actor control(String name) {
        return view.control(name);
    }

    String pageId() {
        return view.pageId();
    }
}
