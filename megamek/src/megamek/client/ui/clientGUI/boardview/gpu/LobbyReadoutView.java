/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTabs;

/**
 * The lobby's unit readout as a dossier: the unit's sprite beside its name and key figures, then a page at a time, the
 * summary readout, the Technical Readout, the Battle Value and the cost calculations, as the Swing readout, BV and cost
 * dialogs show them; Previous and Next go through several units. Also shows a help document, a single page of rows.
 * <p>
 * GPU thread. The sprites' textures are given back once it closes.
 */
final class LobbyReadoutView {
    /** The pages of a dossier, in their tabs' order. */
    enum Page {
        SUMMARY("Lobby.readout.summary"), TRO("Lobby.readout.tro"), BV("Lobby.readout.bv"), COST("Lobby.readout.cost");

        private final String key;

        Page(String key) {
            this.key = key;
        }
    }

    /**
     * One unit's dossier, read on the Swing thread.
     *
     * @param line   the unit's key figures, under its name
     * @param sprite its sprite in its camouflage, or null
     * @param pages  the rows of each {@link Page}, in order
     */
    record Dossier(String name, String line, @Nullable BufferedImage sprite, List<List<EntityReadout.Row>> pages) {
    }

    private final UiKit kit;
    private final boolean motion;
    private final List<Dossier> dossiers;
    private final List<Texture> textures = new ArrayList<>();
    private final Image sprite = new Image((TextureRegionDrawable) null, Scaling.fit);
    private final Label name;
    private final Label line;
    private final Table page = new Table();
    private final @Nullable UiTabs tabs;
    private @Nullable UiButton previous;
    private @Nullable UiButton next;
    private int shown;

    LobbyReadoutView(UiKit kit, UiDialog dialog, List<Dossier> dossiers, boolean motion, Runnable close) {
        this.kit = kit;
        this.motion = motion;
        this.dossiers = List.copyOf(dossiers);
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        boolean single = dossiers.size() == 1 && dossiers.get(0).pages().size() == 1;

        Table head = kit.card();
        head.left();
        sprite.setName("readout-sprite");
        name = kit.typeLabel("", "headline", "text.primary");
        name.setName("readout-name");
        name.setWrap(true);
        line = kit.typeLabel("", "body", "text.secondary");
        line.setWrap(true);
        if (!single) {
            head.add(sprite).size(DesignTokens.shipped().size().get("control.lg") * 3).padRight(space("lg"));
            Table titles = new Table();
            titles.left().defaults().left().growX();
            titles.add(name).minWidth(0).row();
            titles.add(line).minWidth(0).padTop(space("xs")).row();
            head.add(titles).growX().minWidth(0);
            if (dossiers.size() > 1) {
                previous = kit.button("button-icon", "chevron-left", null, null);
                previous.setName("readout-prev");
                kit.tip(previous, true).getActor().setText(Messages.getString("Previous"));
                UiKit.onChange(previous, () -> show(shown - 1));
                next = kit.button("button-icon", "chevron-right", null, null);
                next.setName("readout-next");
                kit.tip(next, true).getActor().setText(Messages.getString("Next"));
                UiKit.onChange(next, () -> show(shown + 1));
                head.add(previous).padLeft(space("sm"));
                head.add(next).padLeft(space("xs"));
            }
            body.add(head).row();
            String[] labels = new String[Page.values().length];
            for (Page each : Page.values()) {
                labels[each.ordinal()] = Messages.getString(each.key);
            }
            tabs = kit.tokenTabs(true, labels);
            tabs.setName("readout-tabs");
            UiKit.onChange(tabs, this::page);
            body.add(tabs).padTop(space("lg")).row();
        } else {
            tabs = null;
        }
        page.top().left();
        body.add(page).padTop(space("md")).row();
        dialog.primary(Messages.getString("Close"), close).setName("readout-close");
        show(0);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private void show(int index) {
        if (index < 0 || index >= dossiers.size()) {
            return;
        }
        shown = index;
        Dossier dossier = dossiers.get(index);
        name.setText(dossier.name());
        line.setText(dossier.line());
        TextureRegionDrawable drawable = null;
        if (dossier.sprite() != null) {
            Texture texture = GpuStartScreen.texture(dossier.sprite(), true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            textures.add(texture);
            drawable = new TextureRegionDrawable(texture);
        }
        sprite.setDrawable(drawable);
        if (previous != null && next != null) {
            previous.setDisabled(index == 0);
            next.setDisabled(index == dossiers.size() - 1);
        }
        page();
    }

    /** Shows the dossier's page that the tabs pick. */
    private void page() {
        int at = (tabs == null) ? 0 : Math.max(0, tabs.getSelectedIndex());
        List<List<EntityReadout.Row>> pages = dossiers.get(shown).pages();
        List<EntityReadout.Row> rows = at < pages.size() ? pages.get(at) : List.of();
        page.clearChildren();
        if (rows.isEmpty()) {
            page.add(kit.typeLabel(Messages.getString("Lobby.readout.none"), "body", "text.muted")).left();
        } else {
            page.add(LobbyReadoutRows.of(kit, motion, rows)).growX().minWidth(0);
        }
    }

    /** Turns to {@code shown}'s tab; returns this. */
    LobbyReadoutView page(Page shown) {
        if (tabs != null) {
            tabs.select(shown.ordinal());
            page();
        }
        return this;
    }

    /** Gives the sprites' textures back. */
    void dispose() {
        textures.forEach(Texture::dispose);
        textures.clear();
    }

    // ---- For the probe and the tests ----

    int shown() {
        return shown;
    }

    @Nullable UiTabs tabs() {
        return tabs;
    }

    @Nullable Actor sprite() {
        return sprite;
    }
}
