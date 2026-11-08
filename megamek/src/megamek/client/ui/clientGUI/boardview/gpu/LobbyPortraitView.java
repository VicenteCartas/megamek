/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static com.badlogic.gdx.scenes.scene2d.actions.Actions.forever;
import static com.badlogic.gdx.scenes.scene2d.actions.Actions.run;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.PortraitCatalog;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTree;
import megamek.common.icons.Portrait;

/**
 * The lobby's portrait chooser in a {@link UiDialog}, as the Swing {@code PortraitChooserDialog} offers it: the
 * portrait categories and portraits to browse ({@link LobbyIconBrowser}), and the selected portrait large beside its
 * name. OK confirms the portrait, Refresh reads the portrait directory again, and a double click on a portrait
 * confirms it.
 * <p>
 * GPU thread. The pictures are read on {@code worker} and become textures here.
 */
final class LobbyPortraitView {
    /** Where the chooser's portraits come from; called on the worker thread. */
    interface Source extends LobbyIconBrowser.Source<Portrait> {
        /** The portrait directory as the chooser offers it, read from disk again when {@code refresh}. */
        @Override
        PortraitCatalog catalog(boolean refresh);
    }

    /** The selected portrait's picture, in pixels. */
    static final int PREVIEW_PIXELS = 128;
    private static final float PREVIEW = 128;

    private final UiDialog dialog;
    private final Source source;
    private final Executor worker;
    private final Consumer<Portrait> confirmed;
    private final Runnable close;
    private final Queue<Runnable> arrived = new ConcurrentLinkedQueue<>();
    private final LobbyIconBrowser<Portrait> browser;
    private final Image preview = new Image();
    private final Label selectedName;
    private @Nullable Portrait selected;
    private @Nullable Texture previewTexture;
    private boolean previewDue = true;
    private boolean previewBusy;
    private boolean disposed;

    /**
     * Fills {@code dialog}'s body and footer.
     *
     * @param catalog    the portrait directory as first read
     * @param initial    the portrait the crew member has, selected at first, or null
     * @param bodyWidth  the width the body has, which decides the columns
     * @param gridHeight the height the tree and the grid share, as the window leaves
     * @param confirmed  takes the confirmed portrait
     * @param close      closes the dialog
     */
    LobbyPortraitView(UiKit kit, UiDialog dialog, Source source, PortraitCatalog catalog, @Nullable Portrait initial,
          Executor worker, float bodyWidth, float gridHeight, Consumer<Portrait> confirmed, Runnable close) {
        this.dialog = dialog;
        this.source = source;
        this.worker = worker;
        this.confirmed = confirmed;
        this.close = close;
        this.selected = initial;
        Table body = dialog.body();
        body.defaults().left();

        browser = new LobbyIconBrowser<>(kit, "portrait", Messages.getString("Lobby.portrait.search"),
              Messages.getString("Lobby.portrait.empty"), source, catalog, initial, worker, bodyWidth, gridHeight,
              this::select, this::confirm);
        body.add(browser.tools()).growX().row();
        body.add(browser.browse()).growX().padTop(space("md")).row();

        preview.setScaling(Scaling.fit);
        preview.setName("portrait-preview");
        selectedName = kit.typeLabel("", "body", "text.primary");
        selectedName.setEllipsis(true);
        selectedName.setName("portrait-selected");
        Table lower = new Table();
        lower.add(preview).size(PREVIEW).top().left();
        lower.add(selectedName).growX().minWidth(0).top().left().padLeft(space("lg"));
        body.add(kit.caption(Messages.getString("Lobby.camo.preview"), true)).padTop(space("lg")).row();
        body.add(lower).growX().padTop(space("sm")).row();

        dialog.primary(Messages.getString("Ok.text"), () -> {
            if (selected != null) {
                confirm(selected);
            }
        }).setName("portrait-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("portrait-cancel");
        UiButton refresh = dialog.tool(Messages.getString("RefreshDirectory.text"), browser::refresh);
        refresh.setName("portrait-refresh");
        kit.tip(refresh, true).getActor().setText(Messages.getString("RefreshDirectory.toolTipText"));

        refreshName();
        // An actor of its own: the layer's actions are its fade, which showing it clears
        Actor pump = new Actor();
        pump.addAction(forever(run(this::drain)));
        dialog.layer().addActor(pump);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private void select(Portrait portrait) {
        selected = portrait;
        refreshName();
        previewDue = true;
    }

    private void refreshName() {
        selectedName.setText(selected == null ? Messages.getString("Lobby.portrait.none")
              : LobbyIconBrowser.name(selected));
    }

    // ---- The preview ----

    /** Each frame: takes what the worker has finished, and asks for the preview when the portrait changed. */
    private void drain() {
        browser.drain();
        for (Runnable done = arrived.poll(); done != null; done = arrived.poll()) {
            if (!disposed) {
                done.run();
            }
        }
        if (previewDue && !previewBusy && !disposed) {
            previewDue = false;
            Portrait portrait = selected;
            if (portrait == null) {
                return;
            }
            previewBusy = true;
            worker.execute(() -> {
                BufferedImage image = disposed ? null : source.thumbnail(portrait, PREVIEW_PIXELS);
                arrived.add(() -> previewArrived(image));
            });
        }
    }

    private void previewArrived(@Nullable BufferedImage image) {
        previewBusy = false;
        if (previewTexture != null) {
            previewTexture.dispose();
            previewTexture = null;
        }
        if (image == null) {
            preview.setDrawable(null);
            return;
        }
        previewTexture = GpuStartScreen.texture(image, true);
        previewTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        preview.setDrawable(new TextureRegionDrawable(previewTexture));
    }

    // ---- The answers ----

    private void confirm(Portrait portrait) {
        close.run();
        confirmed.accept(portrait);
    }

    /** GPU thread, once the dialog has closed: gives the textures back; what the worker still finishes is dropped. */
    void dispose() {
        disposed = true;
        arrived.clear();
        browser.dispose();
        preview.setDrawable(null);
        if (previewTexture != null) {
            previewTexture.dispose();
            previewTexture = null;
        }
    }

    // ---- For the probe and the tests ----

    UiTree tree() {
        return browser.tree();
    }

    /** The card of the grid's portrait {@code index}, once the grid has made it, or null. */
    @Nullable Actor item(int index) {
        return browser.item(index);
    }

    List<Portrait> items() {
        return browser.items();
    }

    @Nullable Portrait selected() {
        return selected;
    }

    UiDialog dialog() {
        return dialog;
    }

    boolean previewShown() {
        return previewTexture != null;
    }
}
