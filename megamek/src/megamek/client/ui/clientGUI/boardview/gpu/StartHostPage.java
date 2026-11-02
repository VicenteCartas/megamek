/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartScreen.Page;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.HostRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.LoadRequest;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.net.NetworkAddresses;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;

/** The Host page, and the Resume page, which is the Host page for a chosen save. */
final class StartHostPage {
    private final GpuStartScreen screen;
    private final StartServerInputs inputs;
    UiButton onlineToggle;
    boolean onlineOpen;
    private UiChoice registerBox;
    private UiTextField metaserverInput;
    private UiFormField metaserverField;
    private Table addresses;
    private Label publicAddress;
    private UiButton showAddress;
    private UiButton hostButton;
    private UiButton backButton;
    /** The save the Resume page loads, what it says about itself, and the player the host takes. */
    StartSaves.Summary resume;
    private Table resumeHeader;
    UiSelect playAs;
    private UiFormField playAsField;

    StartHostPage(GpuStartScreen screen) {
        this.screen = screen;
        this.inputs = screen.inputs;
    }

    /** The Host page's inputs, filled from the last game's choices; the main button runs {@code submit}. */
    void buildHostPage(Runnable submit, Page back) {
        ClientPreferences client = PreferenceManager.getClientPreferences();
        inputs.nameInput = inputs.field(client.getLastPlayerName());
        inputs.nameField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.name"), inputs.nameInput)
              .helper(Messages.getString("MegaMek.GameConnection.nameHelper"));

        String lastPassword = client.getLastServerPass();
        onlineOpen = StartHostForm.onlineOpen(lastPassword, client.getLastServerPort());
        onlineToggle = screen.kit.button("button-quiet", onlineOpen ? "chevron-down" : "chevron-right",
              Messages.getString("MegaMek.GameConnection.online"), null);
        GpuStartScreen.leftAligned(onlineToggle);
        // A section heading: its chevron lines up with the fields under it
        onlineToggle.padLeft(0);
        UiKit.onChange(onlineToggle, () -> {
            onlineOpen = !onlineOpen;
            chevron(onlineToggle, onlineOpen);
            fillHostColumn();
        });

        inputs.buildServerInputs(lastPassword, client.getLastServerPort());

        registerBox = screen.kit.check(Messages.getString("MegaMek.GameConnection.register"), false);
        registerBox.left();
        UiKit.onChange(registerBox, this::fillHostColumn);
        metaserverInput = inputs.field(client.getMetaServerName());
        metaserverField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.metaserver"),
              metaserverInput);

        addresses = addresses();
        hostButton = screen.kit.button("button-primary", null, Messages.getString("MegaMek.GameConnection.host"), null);
        GpuStartScreen.leftAligned(hostButton);
        UiKit.onChange(hostButton, submit);
        backButton = screen.backButton(back);
    }

    void chevron(UiButton button, boolean open) {
        if (!button.icons.isEmpty() && button.icons.get(0) instanceof Image icon) {
            icon.setDrawable(screen.boardSkin.skin.getDrawable("icon-" + (open ? "chevron-down" : "chevron-right")));
        }
    }

    /** Lays the Host page's column out for the online options' and server browser's current state. */
    void fillHostColumn() {
        boolean resuming = screen.page == Page.RESUME;
        List<Actor> items = new ArrayList<>();
        items.add(screen.overline(resuming ? "StartScreen.loadGame" : "StartScreen.newGame"));
        if (resuming) {
            items.add(resumeHeader);
        }
        items.add(resuming && playAsField != null ? playAsField : inputs.nameField);
        if (resuming && watching()) {
            items.add(inputs.nameField);
        }
        items.add(onlineToggle);
        if (onlineOpen) {
            items.add(inputs.passwordRow);
            items.add(registerBox);
            if (registerBox.isChecked()) {
                items.add(metaserverField);
            }
            items.add(addresses);
        }
        items.add(hostButton);
        items.add(backButton);
        // Keep the title label: a new one would slide in again
        if (!screen.column.isEmpty() && screen.column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        screen.setColumn(items);
    }

    /**
     * The addresses friends type to join: the local one, found at once, and the public one, which is only asked of an
     * outside service when the player chooses to show it, since it would also show on a stream or a screenshot.
     */
    private Table addresses() {
        Table table = new Table();
        table.left().top();
        showAddress = screen.kit.button("button-quiet-compact", null, Messages.getString("StartScreen.showAddress"),
              null);
        // Rows keep the button's height so the page doesn't shift when the address replaces it
        table.defaults().left().minHeight(showAddress.getPrefHeight()).padBottom(4);
        Label local = screen.kit.typeLabel(Messages.getString("StartScreen.findingAddress"), "small", "text.primary");
        table.add(screen.kit.typeLabel(Messages.getString("StartScreen.localAddress"), "small", "text.muted"))
              .padRight(12);
        table.add(local).expandX().row();
        table.add(screen.kit.typeLabel(Messages.getString("StartScreen.publicAddress"), "small", "text.muted"))
              .padRight(12);
        publicAddress = screen.kit.typeLabel("", "small", "text.primary");
        Cell<Actor> publicCell = table.add((Actor) showAddress);
        UiKit.onChange(showAddress, () -> {
            publicAddress.setText(Messages.getString("StartScreen.findingAddress"));
            publicCell.setActor(publicAddress);
            int at = screen.focusable.indexOf(showAddress);
            if (at >= 0) {
                screen.focusable.remove(at);
            }
            if (screen.stage.getKeyboardFocus() == showAddress) {
                screen.stage.setKeyboardFocus(hostButton);
            }
            lookUp(NetworkAddresses::publicAddress, publicAddress);
        });
        lookUp(NetworkAddresses::localAddress, local);
        return table;
    }

    /** Finds an address off the GPU thread, then shows it, or that it wasn't found, in {@code label}. */
    private void lookUp(Supplier<String> address, Label label) {
        Application app = Gdx.app;
        Thread thread = new Thread(() -> {
            String found = address.get();
            app.postRunnable(() -> {
                if (!screen.disposed) {
                    label.setText(found == null ? Messages.getString("StartScreen.noAddress") : found);
                }
            });
        }, "MegaMek-start-address");
        thread.setDaemon(true);
        thread.start();
    }

    /** Checks the Host page; shows what is wrong under the inputs, or asks MegaMek to host the game. */
    void submitHost() {
        if (hostButton.isDisabled()) {
            return;
        }
        String name = inputs.nameInput.getText().trim();
        int port = StartHostForm.port(inputs.portInput.getText());
        inputs.nameField.error(name.isEmpty() ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        inputs.portField.error(StartServerInputs.portError(port));
        if (name.isEmpty()) {
            screen.stage.setKeyboardFocus(inputs.nameInput);
            return;
        }
        if (port < 0) {
            if (!onlineOpen) {
                onlineOpen = true;
                chevron(onlineToggle, true);
                fillHostColumn();
            }
            screen.stage.setKeyboardFocus(inputs.portInput);
            return;
        }
        boolean register = registerBox.isChecked();
        starting();
        screen.window.host(new HostRequest(name, inputs.passwordInput.getText(), port, register,
              register ? metaserverInput.getText().trim() : ""));
    }

    /** Locks the host form along with Start and Back until the existing start lifecycle restores them. */
    private void starting() {
        String text = hostButton.getText().toString();
        screen.starting(hostButton, "StartScreen.starting", () -> {
            controlsDisabled(false);
            hostButton.setDisabled(false);
            hostButton.setText(text);
        });
        controlsDisabled(true);
        screen.stage.setKeyboardFocus(null);
    }

    private void controlsDisabled(boolean disabled) {
        inputs.nameInput.setDisabled(disabled);
        onlineToggle.setDisabled(disabled);
        inputs.passwordInput.setDisabled(disabled);
        inputs.passwordBox.reveal.setDisabled(disabled);
        inputs.portInput.setDisabled(disabled);
        registerBox.setDisabled(disabled);
        metaserverInput.setDisabled(disabled);
        showAddress.setDisabled(disabled);
        if (playAs != null) {
            playAs.setDisabled(disabled);
        }
    }

    /**
     * The Resume page: the Host page for the chosen save, whose name field becomes a choice of the save's players
     * (the last name played, where the save has it), and whose main button loads it.
     */
    void buildResume() {
        hostButton.setText(screen.kit.cased("button", Messages.getString("StartScreen.loadGame")));
        resumeHeader = new Table();
        resumeHeader.left().top();
        resumeHeader.defaults().left().growX().minWidth(0);
        resumeHeader.add(screen.line(StartSaves.title(resume.file()), "body", "text.primary")).row();
        resumeHeader.add(screen.line(StartLoadPage.savedAt(resume.file()), "small", "text.muted"));
        playAs = null;
        playAsField = null;
        List<String> players = resume.players();
        if (!players.isEmpty()) {
            List<String> choices = new ArrayList<>(players);
            choices.add(Messages.getString("StartScreen.watch"));
            playAs = screen.kit.tokenSelect(false, choices.toArray(String[]::new));
            int last = players.indexOf(PreferenceManager.getClientPreferences().getLastPlayerName());
            playAs.select(Math.max(last, 0));
            UiKit.onChange(playAs, this::fillHostColumn);
            playAsField = screen.kit.formField(Messages.getString("StartScreen.playAs"), playAs)
                  .helper(Messages.getString("StartScreen.playAsHelper"));
            // Watching asks for a name, as the briefing does when the host takes no side
            inputs.nameField = screen.kit.formField(Messages.getString("StartScreen.observerName"), inputs.nameInput)
                  .helper(Messages.getString("StartScreen.loadObserverHelper"));
        }
    }

    /** Whether the Resume page's host watches the save rather than take one of its players. */
    private boolean watching() {
        return playAs != null && playAs.getSelectedIndex() == resume.players().size();
    }

    /** Checks the Resume page as the Host page; asks MegaMek to host the save as the chosen player, or to watch it. */
    void submitResume() {
        if (hostButton.isDisabled()) {
            return;
        }
        boolean watching = watching();
        String name = playAs != null && !watching ? playAs.getSelected() : inputs.nameInput.getText().trim();
        int port = StartHostForm.port(inputs.portInput.getText());
        String nameError = null;
        if (playAs == null || watching) {
            if (name.isEmpty()) {
                nameError = Messages.getString("MegaMek.GameConnection.nameError");
            } else if (watching && resume.players().contains(name)) {
                nameError = Messages.getString("StartScreen.observerNameTaken");
            }
            inputs.nameField.error(nameError);
        }
        inputs.portField.error(StartServerInputs.portError(port));
        if (nameError != null) {
            screen.stage.setKeyboardFocus(inputs.nameInput);
            return;
        }
        if (port < 0) {
            if (!onlineOpen) {
                onlineOpen = true;
                chevron(onlineToggle, true);
                fillHostColumn();
            }
            screen.stage.setKeyboardFocus(inputs.portInput);
            return;
        }
        boolean register = registerBox.isChecked();
        List<String> bots = resume.bots().stream().filter(bot -> watching || !bot.equals(name)).toList();
        starting();
        screen.window.load(new LoadRequest(resume.file(), new HostRequest(name, inputs.passwordInput.getText(), port,
              register, register ? metaserverInput.getText().trim() : ""), watching, bots));
    }
}
