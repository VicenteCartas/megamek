/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.design.DesignTokens;

/**
 * A card that sums something up and opens it when clicked, such as the lobby's planetary conditions: the token card
 * ({@link UiTheme#TOKEN_PICTURE_CARD}), its edge in border.strong under the pointer, with the overline title and a ›
 * chevron on its first row and the summary, wrapped, under them. The whole card is the button, so there is no Change
 * button beside a title to find. A disabled card mutes its summary and drops its chevron.
 */
public class UiSummaryCard extends Button {
    private final Label summary;
    private final UiKit.Icon chevron;

    public UiSummaryCard(UiKit kit, String title) {
        super(kit.skin, UiTheme.TOKEN_PICTURE_CARD);
        DesignTokens tokens = DesignTokens.shipped();
        float icon = tokens.size().get("icon.sm");
        pad(tokens.space().get("sm"), tokens.space().get("md"), tokens.space().get("sm"), tokens.space().get("sm"));
        Table top = new Table();
        top.add(kit.roleTypeLabel("overline", title, "text.muted")).left().growX();
        chevron = kit.icon("chevron-right", icon, UiTheme.tokenColor("text.secondary"));
        top.add(chevron).size(icon).right();
        add(top).growX().row();
        summary = kit.typeLabel("", "body", "text.secondary");
        summary.setWrap(true);
        add(summary).growX().minWidth(0).padTop(tokens.space().get("xs"));
    }

    /** Shows {@code text} as the summary. */
    public void summary(String text) {
        summary.setText(text);
    }

    /** The summary shown. */
    public String summary() {
        return summary.getText().toString();
    }

    @Override
    public void setDisabled(boolean disabled) {
        super.setDisabled(disabled);
        if (chevron != null) {
            chevron.setVisible(!disabled);
            summary.getStyle().fontColor = UiTheme.tokenColor(disabled ? "text.muted" : "text.secondary");
        }
    }
}
