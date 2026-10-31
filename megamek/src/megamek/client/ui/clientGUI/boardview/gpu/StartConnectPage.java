/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.ConnectRequest;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;

/** The Connect page: the player, or a bot, joins a game hosted elsewhere. */
final class StartConnectPage {
    /** The Connect page's segment that has a bot join instead of the player. */
    static final int AS_BOT = 1;

    private final GpuStartScreen screen;
    private final StartServerInputs inputs;
    UiSegmented roleBar;
    private UiTextField addressInput;
    private UiFormField addressField;
    private Table addressRow;
    UiChoice sbfBox;
    private UiButton connectButton;
    private UiButton backButton;

    StartConnectPage(GpuStartScreen screen) {
        this.screen = screen;
        this.inputs = screen.inputs;
    }

    /** The Connect page's inputs, filled from the last game joined; the player joins unless Bot is chosen. */
    void buildConnectPage() {
        ClientPreferences client = PreferenceManager.getClientPreferences();
        roleBar = screen.kit.tokenSegmented(false, Messages.getString("MegaMek.GameConnection.player"),
              Messages.getString("MegaMek.GameConnection.bot"));
        // The segments share the column's width
        for (Cell<?> cell : roleBar.getCells()) {
            cell.expandX();
        }
        UiKit.onChange(roleBar, this::fillConnectColumn);

        inputs.nameInput = inputs.field(client.getLastPlayerName());
        inputs.nameField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.name"), inputs.nameInput);
        addressInput = inputs.field(client.getLastConnectAddr());
        addressField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.address"), addressInput);
        inputs.portInput = inputs.field(String.valueOf(client.getLastConnectPort()));
        inputs.portField = screen.kit.formField(Messages.getString("MegaMek.GameConnection.port"), inputs.portInput);
        addressRow = new Table();
        addressRow.top().left();
        addressRow.add(addressField).growX().minWidth(0).top();
        addressRow.add(inputs.portField).width(StartServerInputs.PORT_WIDTH).padLeft(12).top();

        sbfBox = screen.kit.check(Messages.getString("MegaMek.GameConnection.sbf"), false);
        sbfBox.left();
        connectButton = screen.kit.button("button-primary", null, Messages.getString("MegaMek.GameConnection.connect"),
              null);
        GpuStartScreen.leftAligned(connectButton);
        UiKit.onChange(connectButton, this::submitConnect);
        backButton = screen.backButton();
    }

    private boolean asBot() {
        return roleBar.getSelectedIndex() == AS_BOT;
    }

    /** Lays the Connect page's column out for the player or a bot; there is no Strategic BattleForce bot. */
    void fillConnectColumn() {
        boolean bot = asBot();
        inputs.nameField.helper(Messages.getString(bot ? "MegaMek.GameConnection.botNameHelper"
              : "MegaMek.GameConnection.nameHelper"));
        connectButton.setText(screen.kit.cased("button", Messages.getString(bot ? "MegaMek.GameConnection.connectBot"
              : "MegaMek.GameConnection.connect")));
        List<Actor> items = new ArrayList<>();
        items.add(screen.overline("StartScreen.connect"));
        items.add(roleBar);
        items.add(inputs.nameField);
        items.add(addressRow);
        if (!bot) {
            items.add(sbfBox);
        }
        items.add(connectButton);
        items.add(backButton);
        if (!screen.column.isEmpty() && screen.column.get(0) instanceof Label title) {
            items.set(0, title);
        }
        screen.setColumn(items);
    }

    /** Checks the Connect page; shows what is wrong under the inputs, or asks MegaMek to join the game. */
    void submitConnect() {
        if (connectButton.isDisabled()) {
            return;
        }
        String name = inputs.nameInput.getText().trim();
        String address = addressInput.getText().trim();
        int port = StartHostForm.port(inputs.portInput.getText());
        inputs.nameField.error(name.isEmpty() ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        addressField.error(address.isEmpty() ? Messages.getString("MegaMek.GameConnection.addressError") : null);
        inputs.portField.error(StartServerInputs.portError(port));
        Actor wrong = name.isEmpty() ? inputs.nameInput : address.isEmpty() ? addressInput
              : port < 0 ? inputs.portInput : null;
        if (wrong != null) {
            screen.stage.setKeyboardFocus(wrong);
            return;
        }
        boolean bot = asBot();
        screen.starting(connectButton, "StartScreen.connecting");
        screen.window.connect(new ConnectRequest(name, address, port, bot, !bot && sbfBox.isChecked()));
    }
}
