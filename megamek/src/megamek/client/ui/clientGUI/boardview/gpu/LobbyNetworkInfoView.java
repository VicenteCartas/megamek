/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.NetworkInformationForm.Kind;
import megamek.client.lobby.NetworkInformationForm.Row;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;

/**
 * This computer's addresses and the game's host as a card over the battle, the native counterpart of the Swing Network
 * Information dialog. Each address is a read-only field that keeps it hidden until its eye shows it, as the Swing
 * dialog's Show buttons did; a shown address can be selected and copied. The addresses arrive once they have been
 * looked up ({@link #addresses}); until then the fields say so.
 * <p>
 * GPU thread.
 */
final class LobbyNetworkInfoView {
    private final Map<Kind, UiKit.TokenPassword> fields = new EnumMap<>(Kind.class);
    private final UiButton close;

    LobbyNetworkInfoView(UiKit kit, UiDialog dialog, List<Row> rows, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        Label intro = kit.typeLabel(Messages.getString("NetworkInformation.native.intro"), "small",
              "text.secondary");
        intro.setWrap(true);
        body.add(intro).row();
        for (Row row : rows) {
            UiKit.TokenPassword field = kit.tokenPassword(false);
            field.field.readOnly(true);
            field.field.setFocusTraversal(false);
            field.setName("network-" + row.kind().name().toLowerCase());
            fields.put(row.kind(), field);
            body.add(kit.formField(row.kind().label(), field)).padTop(space("md")).row();
        }
        this.close = dialog.primary(Messages.getString("Close"), close);
        this.close.setName("network-close");
        addresses(rows);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /**
     * Shows {@code rows}: a found address hidden until its eye shows it, and anything else (still looking, or not
     * found) as plain, disabled text without an eye.
     */
    void addresses(List<Row> rows) {
        for (Row row : rows) {
            UiKit.TokenPassword field = fields.get(row.kind());
            if (field == null) {
                continue;
            }
            boolean wasShown = field.shown() && !field.field.isDisabled();
            field.field.setText(row.text());
            field.field.setDisabled(!row.found());
            field.shown(!row.found() || wasShown);
        }
    }

    // ---- For the probe and the tests ----

    UiKit.TokenPassword field(Kind kind) {
        return fields.get(kind);
    }

    UiButton closeButton() {
        return close;
    }
}
