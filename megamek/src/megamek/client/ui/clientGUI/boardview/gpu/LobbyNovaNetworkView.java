/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.stream.Collectors;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.NovaNetworkForm;
import megamek.client.lobby.NovaNetworkForm.Unit;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;

/**
 * The Nova CEWS networks as a card over the battle, read only, the native counterpart of the Swing Nova network view:
 * the local player's Nova units, then the allied ones, each a panel with its link state as a chip and the units it is
 * linked with.
 * <p>
 * GPU thread.
 */
final class LobbyNovaNetworkView {
    private final NovaNetworkForm form;
    private final UiButton close;

    LobbyNovaNetworkView(UiKit kit, UiDialog dialog, NovaNetworkForm form, Runnable close) {
        this.form = form;
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        Label intro = kit.typeLabel(Messages.getString("NovaNetworkViewDialog.native.intro"), "small",
              "text.secondary");
        intro.setWrap(true);
        body.add(intro).row();
        if (form.isEmpty()) {
            Label empty = kit.typeLabel(Messages.getString("NovaNetworkViewDialog.native.empty"), "body",
                  "text.primary");
            empty.setWrap(true);
            empty.setName("nova-empty");
            body.add(empty).padTop(space("md")).row();
        }
        section(kit, body, "yours", Messages.getString("NovaNetworkViewDialog.yourUnits"), form.yours());
        section(kit, body, "allied", Messages.getString("NovaNetworkViewDialog.alliedUnits"), form.allied());
        this.close = dialog.primary(Messages.getString("Close"), close);
        this.close.setName("nova-close");
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** A heading and one panel per unit; nothing when {@code units} is empty. */
    private static void section(UiKit kit, Table body, String key, String title, List<Unit> units) {
        if (units.isEmpty()) {
            return;
        }
        Label heading = kit.roleTypeLabel("overline", title, "text.muted");
        heading.setName("nova-" + key);
        body.add(heading).padTop(space("lg")).row();
        for (int index = 0; index < units.size(); index++) {
            body.add(unit(kit, units.get(index), key + "-" + index)).padTop(space(index == 0 ? "xs" : "sm")).row();
        }
    }

    /** One unit: its name and ID with its link chip, then whom it is linked with and, for an ally's, who commands it. */
    private static Table unit(UiKit kit, Unit unit, String key) {
        Table card = kit.panel(true);
        card.setName("nova-unit-" + key);
        card.defaults().left();
        Table top = new Table();
        top.add(kit.roleTypeLabel("name", unit.name(), "text.primary")).left();
        top.add(kit.typeLabel(Messages.getString("NovaNetworkViewDialog.native.id", unit.id()), "small",
              "text.muted")).left().padLeft(space("sm")).growX();
        top.add(kit.chip(chipText(unit), unit.linked() ? UiKit.Tone.OK : UiKit.Tone.NORMAL, true)).right();
        card.add(top).growX().row();
        Label partners = kit.typeLabel(partnersText(unit), "small", "text.secondary");
        partners.setWrap(true);
        partners.setName("nova-partners-" + key);
        card.add(partners).growX().padTop(space("xs")).row();
        if (unit.owner() != null) {
            card.add(kit.typeLabel(Messages.getString("NovaNetworkViewDialog.native.owner", unit.owner()), "small",
                  "text.muted")).growX().padTop(space("xs")).row();
        }
        return card;
    }

    /** The link chip's text: unlinked, linked with the network's size, or full. */
    static String chipText(Unit unit) {
        if (!unit.linked()) {
            return Messages.getString("NovaNetworkViewDialog.native.unlinked");
        }
        return unit.full() ? Messages.getString("NovaNetworkViewDialog.native.full")
              : Messages.getString("NovaNetworkViewDialog.native.linked", unit.networkSize());
    }

    /** The units {@code unit} is linked with by name and ID, or that it is linked with none. */
    static String partnersText(Unit unit) {
        if (!unit.linked()) {
            return Messages.getString("NovaNetworkViewDialog.native.alone");
        }
        return Messages.getString("NovaNetworkViewDialog.native.partners", unit.partners().stream()
              .map(partner -> Messages.getString("NovaNetworkViewDialog.native.partner", partner.name(),
                    partner.id()))
              .collect(Collectors.joining(", ")));
    }

    // ---- For the probe and the tests ----

    NovaNetworkForm form() {
        return form;
    }

    UiButton closeButton() {
        return close;
    }
}
