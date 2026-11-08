/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static com.badlogic.gdx.scenes.scene2d.actions.Actions.forever;
import static com.badlogic.gdx.scenes.scene2d.actions.Actions.run;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTree;
import megamek.common.battlefieldSupport.OverlayStyle;
import megamek.common.battlefieldSupport.StripeDirection;
import megamek.common.icons.Camouflage;

/**
 * The lobby's camo chooser in a {@link UiDialog}, as the Swing {@code CamoChooserDialog} offers it: the camo
 * categories and camos to browse ({@link LobbyIconBrowser}, the colour camos first), the units wearing the camo as a
 * preview, the rotation and scale, and for Battlefield Support Assets the marker overlay. OK confirms the camo, Reset
 * (for units with a camo of their own) goes back to the owner's, and Refresh reads the camo directory again. A double
 * click on a camo confirms it.
 * <p>
 * GPU thread. The pictures are read on {@code worker} and become textures here.
 */
final class LobbyCamoView {
    /** Where the chooser's camos and previews come from; called on the worker thread. */
    interface Source extends LobbyIconBrowser.Source<Camouflage> {
        /** The camo directory as the chooser offers it, read from disk again when {@code refresh}. */
        @Override
        CamoCatalog catalog(boolean refresh);

        /** The previewed units wearing {@code camo}, at most two; without units, the camo's own picture. */
        List<BufferedImage> preview(Camouflage camo);
    }

    private static final float PREVIEW = 96;

    private final UiKit kit;
    private final UiDialog dialog;
    private final Source source;
    private final CamoChoice choice;
    private final Executor worker;
    private final Consumer<Camouflage> confirmed;
    private final Runnable close;
    private final Queue<Runnable> arrived = new ConcurrentLinkedQueue<>();
    private final List<Texture> previewTextures = new ArrayList<>();
    private final LobbyIconBrowser<Camouflage> browser;
    private final Table previews = new Table();
    private final Label selectedName;
    private final Label rotationValue;
    private final Label scaleValue;
    private final Slider rotation;
    private final Slider scale;
    private final List<Table> swatches = new ArrayList<>();
    private boolean previewDue = true;
    private boolean previewBusy;
    private boolean disposed;

    /**
     * Fills {@code dialog}'s body and footer.
     *
     * @param catalog   the camo directory as first read
     * @param choice    the camo being chosen; its {@link CamoChoice#individual} offers Reset
     * @param assets    whether a previewed unit is a Battlefield Support Asset, which shows the marker overlay
     * @param bodyWidth  the width the body has, which decides the columns
     * @param gridHeight the height the tree and the grid share, as the window leaves
     * @param confirmed  takes the confirmed camo
     * @param close      closes the dialog
     */
    LobbyCamoView(UiKit kit, UiDialog dialog, Source source, CamoCatalog catalog, CamoChoice choice, boolean assets,
          Executor worker, float bodyWidth, float gridHeight, Consumer<Camouflage> confirmed, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.source = source;
        this.choice = choice;
        this.worker = worker;
        this.confirmed = confirmed;
        this.close = close;
        Table body = dialog.body();
        body.defaults().left();

        browser = new LobbyIconBrowser<>(kit, "camo", Messages.getString("Lobby.camo.search"),
              Messages.getString("Lobby.camo.empty"), source, catalog, choice.selected(), worker, bodyWidth,
              gridHeight, this::select, camo -> confirm());
        body.add(browser.tools()).growX().row();
        body.add(browser.browse()).growX().padTop(space("md")).row();

        selectedName = kit.typeLabel("", "body", "text.primary");
        selectedName.setEllipsis(true);
        selectedName.setName("camo-selected");
        rotation = new Slider(CamoChoice.MIN_ROTATION, CamoChoice.MAX_ROTATION, CamoChoice.ROTATION_STEP, false,
              kit.tokenSliderStyle());
        rotation.setName("camo-rotation");
        rotation.setValue(choice.rotation());
        rotation.addListener(new UiSliderInput(rotation));
        rotationValue = kit.typeLabel("", "small", "text.secondary");
        UiKit.onChange(rotation, () -> {
            choice.rotation(Math.round(rotation.getValue()));
            refreshValues();
            previewDue = true;
        });
        scale = new Slider(CamoChoice.MIN_SCALE, CamoChoice.MAX_SCALE, 1, false, kit.tokenSliderStyle());
        scale.setName("camo-scale");
        scale.setValue(choice.scale());
        scale.addListener(new UiSliderInput(scale));
        scaleValue = kit.typeLabel("", "small", "text.secondary");
        UiKit.onChange(scale, () -> {
            choice.scale(Math.round(scale.getValue()));
            refreshValues();
            previewDue = true;
        });

        Table settings = new Table();
        settings.top().left().defaults().left();
        settings.add(selectedName).growX().minWidth(0).colspan(2).row();
        settings.add(slider("CamoChoiceDialog.rotation", rotation, rotationValue)).growX().colspan(2)
              .padTop(space("sm")).row();
        settings.add(slider("CamoChoiceDialog.scale", scale, scaleValue)).growX().colspan(2).padTop(space("sm"))
              .row();
        if (assets) {
            settings.add(overlay()).growX().colspan(2).padTop(space("md")).row();
        }
        previews.setName("camo-preview");
        Table lower = new Table();
        if (bodyWidth < LobbyIconBrowser.STACK_BELOW) {
            lower.add(previews).left().row();
            lower.add(settings).growX().padTop(space("md"));
        } else {
            lower.add(previews).top().left().minWidth(PREVIEW);
            lower.add(settings).growX().top().padLeft(space("lg"));
        }
        body.add(kit.caption(Messages.getString("Lobby.camo.preview"), true)).padTop(space("lg")).row();
        body.add(lower).growX().padTop(space("sm")).row();

        dialog.primary(Messages.getString("Ok.text"), this::confirm).setName("camo-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("camo-cancel");
        if (choice.individual()) {
            UiButton reset = dialog.tool(Messages.getString("CamoChoiceDialog.btnParent.text"), this::reset);
            reset.setName("camo-reset");
            kit.tip(reset, true).getActor().setText(Messages.getString("CamoChoiceDialog.btnParent.toolTipText"));
        }
        UiButton refresh = dialog.tool(Messages.getString("RefreshDirectory.text"), browser::refresh);
        refresh.setName("camo-refresh");
        kit.tip(refresh, true).getActor().setText(Messages.getString("RefreshDirectory.toolTipText"));

        refreshValues();
        // An actor of its own: the layer's actions are its fade, which showing it clears
        Actor pump = new Actor();
        pump.addAction(forever(run(this::drain)));
        dialog.layer().addActor(pump);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }
    private Table slider(String key, Slider slider, Label value) {
        Table row = new Table();
        row.add(kit.typeLabel(Messages.getString(key), "small", "text.secondary")).left().colspan(2).row();
        row.add(slider).growX().padTop(space("xs"));
        row.add(value).width(space("xxxl") + space("lg")).right().padLeft(space("sm"));
        return row;
    }

    /** The marker overlay of Battlefield Support Assets: its colour (a swatch row), style and stripe direction. */
    private Table overlay() {
        Table overlay = new Table();
        overlay.left().defaults().left();
        overlay.add(kit.typeLabel(Messages.getString("CamoChoiceDialog.overlayLabel"), "small", "text.secondary"))
              .colspan(2).row();
        Table palette = new Table();
        palette.left();
        List<Camouflage> colours = choice.palette();
        float swatch = DesignTokens.shipped().size().get("control.sm");
        for (int index = 0; index < colours.size(); index++) {
            java.awt.Color colour = colours.get(index).getOverlayColor();
            Table cell = new Table();
            cell.setName("camo-overlay-colour-" + index);
            cell.add(kit.solid(new Color(colour.getRGB() << 8 | 0xFF))).grow().pad(space("xxs"));
            int chosen = index;
            cell.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
            cell.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    choice.overlayColour(chosen);
                    refreshSwatches();
                    previewDue = true;
                }
            });
            swatches.add(cell);
            palette.add(cell).size(swatch).padRight(space("xs"));
        }
        refreshSwatches();
        overlay.add(kit.typeLabel(Messages.getString("Lobby.camo.overlayColour"), "small", "text.muted")).colspan(2)
              .padTop(space("xs")).row();
        overlay.add(palette).colspan(2).padTop(space("xxs")).row();

        OverlayStyle[] styles = OverlayStyle.values();
        String[] styleNames = new String[styles.length];
        for (int index = 0; index < styles.length; index++) {
            styleNames[index] = Messages.getString("CamoChoiceDialog.overlayStyle." + styles[index].name());
        }
        UiSelect style = kit.tokenSelect(true, styleNames);
        style.setName("camo-overlay-style");
        style.select(choice.style().ordinal());
        kit.tip(style, true).getActor().setText(Messages.getString("CamoChoiceDialog.overlayStyle.tooltip"));
        UiKit.onChange(style, () -> {
            choice.style(styles[style.getSelectedIndex()]);
            previewDue = true;
        });
        StripeDirection[] directions = StripeDirection.values();
        String[] directionNames = new String[directions.length];
        for (int index = 0; index < directions.length; index++) {
            directionNames[index] = Messages.getString("CamoChoiceDialog.overlayDirection."
                  + directions[index].name());
        }
        UiSelect direction = kit.tokenSelect(true, directionNames);
        direction.setName("camo-overlay-direction");
        direction.select(choice.direction().ordinal());
        UiKit.onChange(direction, () -> {
            choice.direction(directions[direction.getSelectedIndex()]);
            previewDue = true;
        });
        overlay.add(kit.formField(Messages.getString("Lobby.camo.overlayStyle"), style)).growX().padTop(space("sm"));
        overlay.add(kit.formField(Messages.getString("Lobby.camo.overlayDirection"), direction)).growX()
              .padTop(space("sm")).padLeft(space("md")).row();
        return overlay;
    }

    private void refreshSwatches() {
        for (int index = 0; index < swatches.size(); index++) {
            swatches.get(index).setBackground(index == choice.overlayColour()
                  ? kit.skin.getDrawable("token-card-selected") : null);
        }
    }

    private void refreshValues() {
        rotationValue.setText(choice.rotation() + "\u00b0");
        scaleValue.setText(String.format(java.util.Locale.ROOT, "%.1f\u00d7", choice.scale() / 10f));
        Camouflage selected = choice.selected();
        selectedName.setText(selected == null ? Messages.getString("Lobby.camo.none") : name(selected));
    }


    private static String name(Camouflage camo) {
        return LobbyIconBrowser.name(camo);
    }

    private void select(Camouflage camo) {
        choice.select(camo);
        refreshValues();
        previewDue = true;
    }

    // ---- The preview ----

    /** Each frame: takes what the worker has finished, and asks for a preview when the camo changed meanwhile. */
    private void drain() {
        browser.drain();
        for (Runnable done = arrived.poll(); done != null; done = arrived.poll()) {
            if (!disposed) {
                done.run();
            }
        }
        if (previewDue && !previewBusy && !disposed) {
            previewDue = false;
            previewBusy = true;
            Camouflage camo = choice.result();
            worker.execute(() -> {
                List<BufferedImage> images = disposed ? List.of() : source.preview(camo);
                arrived.add(() -> previewArrived(images));
            });
        }
    }

    private void previewArrived(List<BufferedImage> images) {
        previewBusy = false;
        previewTextures.forEach(Texture::dispose);
        previewTextures.clear();
        previews.clearChildren();
        for (BufferedImage image : images) {
            Texture texture = GpuStartScreen.texture(image, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            previewTextures.add(texture);
            Image picture = new Image(new TextureRegionDrawable(texture), Scaling.fit);
            previews.add(picture).size(PREVIEW).padRight(space("sm"));
        }
        if (images.isEmpty()) {
            previews.add(kit.solid("surface.input")).size(PREVIEW);
        }
    }

    // ---- The answers ----

    private void confirm() {
        Camouflage camo = choice.result();
        close.run();
        confirmed.accept(camo);
    }

    private void reset() {
        Camouflage camo = choice.ownerResult();
        close.run();
        confirmed.accept(camo);
    }

    /** GPU thread, once the dialog has closed: gives the textures back; what the worker still finishes is dropped. */
    void dispose() {
        disposed = true;
        arrived.clear();
        browser.dispose();
        previewTextures.forEach(Texture::dispose);
        previewTextures.clear();
    }

    // ---- For the probe and the tests ----

    UiTree tree() {
        return browser.tree();
    }

    /** The card of the grid's camo {@code index}, once the grid has made it, or null. */
    @Nullable Actor item(int index) {
        return browser.item(index);
    }

    List<Camouflage> items() {
        return browser.items();
    }

    CamoChoice choice() {
        return choice;
    }

    UiDialog dialog() {
        return dialog;
    }

    boolean previewShown() {
        return !previewTextures.isEmpty() || previews.hasChildren();
    }
}
