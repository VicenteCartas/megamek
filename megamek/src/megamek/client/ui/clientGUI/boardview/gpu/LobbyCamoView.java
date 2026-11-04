/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static com.badlogic.gdx.scenes.scene2d.actions.Actions.forever;
import static com.badlogic.gdx.scenes.scene2d.actions.Actions.run;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCardGrid;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.common.battlefieldSupport.OverlayStyle;
import megamek.common.battlefieldSupport.StripeDirection;
import megamek.common.icons.Camouflage;

/**
 * The lobby's camo chooser in a {@link UiDialog}, as the Swing {@code CamoChooserDialog} offers it: the camo
 * categories as a tree (the colour camos first), the chosen category's camos as a grid of pictures (with those of
 * its sub-categories while "Include subcategories" is on), a search of three letters or more over every camo, the
 * units wearing the camo as a preview, the rotation and scale, and for Battlefield Support Assets the marker overlay.
 * OK confirms the camo, Reset (for units with a camo of their own) goes back to the owner's, and Refresh reads the
 * camo directory again. A double click on a camo confirms it.
 * <p>
 * GPU thread. The pictures are read on {@code worker} and become textures here; only the grid's rows near the view
 * exist ({@link UiCardGrid}), and at most {@link #THUMBNAILS} pictures are kept.
 */
final class LobbyCamoView {
    /** Where the chooser's camos and previews come from; called on the worker thread. */
    interface Source {
        /** The camo directory as the chooser offers it, read from disk again when {@code refresh}. */
        CamoCatalog catalog(boolean refresh);

        /** The camo's picture, {@code size} pixels wide, or null. */
        @Nullable BufferedImage thumbnail(Camouflage camo, int size);

        /** The previewed units wearing {@code camo}, at most two; without units, the camo's own picture. */
        List<BufferedImage> preview(Camouflage camo);
    }

    /** The most camo pictures kept as textures; the grid makes the others again as they scroll back. */
    static final int THUMBNAILS = 320;
    static final int THUMBNAIL_PIXELS = 84;
    private static final String TREE_PREFIX = "camo:";
    private static final float CARD = 72;
    private static final float TREE_WIDTH = 220;
    private static final float PREVIEW = 96;
    /** Below this body width the tree goes above the grid. */
    private static final float STACK_BELOW = 560;

    private final UiKit kit;
    private final UiDialog dialog;
    private final Source source;
    private final CamoChoice choice;
    private final boolean assets;
    private final Executor worker;
    private final Consumer<Camouflage> confirmed;
    private final Runnable close;
    private final Queue<Runnable> arrived = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<String, Texture> thumbnails = new LinkedHashMap<>(64, .75f, true);
    private final Set<String> asked = new HashSet<>();
    private final Map<String, Table> cards = new HashMap<>();
    private final Map<String, Image> pictures = new HashMap<>();
    private final List<Texture> previewTextures = new ArrayList<>();
    private final UiTree tree;
    private final UiCardGrid grid;
    private final int columns;
    private final ScrollPane gridScroll;
    private final Label emptyLabel;
    private final Table previews = new Table();
    private final Label selectedName;
    private final Label rotationValue;
    private final Label scaleValue;
    private final Slider rotation;
    private final Slider scale;
    private final List<Table> swatches = new ArrayList<>();
    private final UiKit.TokenSearch search;
    private final UiChoice nested;
    private CamoCatalog catalog;
    private String category;
    private List<Camouflage> items = List.of();
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
        this.catalog = catalog;
        this.choice = choice;
        this.assets = assets;
        this.worker = worker;
        this.confirmed = confirmed;
        this.close = close;
        Table body = dialog.body();
        body.defaults().left();
        float gap = space("sm");
        boolean stacked = bodyWidth < STACK_BELOW;

        search = kit.tokenSearch(Messages.getString("Lobby.camo.search"), true);
        search.field.setName("camo-search");
        UiKit.onChange(search.field, this::showItems);
        nested = kit.check(Messages.getString("Lobby.camo.nested"), true);
        nested.setName("camo-nested");
        UiKit.onChange(nested, this::showItems);
        Table tools = new Table();
        tools.add(search).growX().minWidth(0);
        tools.add(nested).padLeft(space("md"));
        body.add(tools).growX().row();

        tree = new UiTree(kit, UiTheme.TOKEN);
        tree.setName("camo-tree");
        tree.nodes(List.of(node(catalog.tree())));
        tree.onSelect(id -> {
            category = id.substring(TREE_PREFIX.length());
            if (!search.field.getText().isEmpty()) {
                search.field.setText("");
            }
            showItems();
        });
        ScrollPane treeScroll = kit.scrollList(tree, true);
        treeScroll.setScrollingDisabled(true, false);

        float gridWidth = stacked ? bodyWidth : bodyWidth - TREE_WIDTH - gap;
        columns = Math.max(2, (int) ((gridWidth + gap) / (CARD + gap)));
        grid = new UiCardGrid(columns, CARD + space("xl"), 0, gap);
        grid.setName("camo-grid");
        gridScroll = kit.scrollList(grid, true);
        gridScroll.setScrollingDisabled(true, false);
        gridScroll.setFadeScrollBars(false);
        emptyLabel = kit.typeLabel(Messages.getString("Lobby.camo.empty"), "small", "text.muted");
        emptyLabel.setAlignment(Align.center);
        emptyLabel.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
        com.badlogic.gdx.scenes.scene2d.ui.Stack gridStack = new com.badlogic.gdx.scenes.scene2d.ui.Stack(gridScroll,
              emptyLabel);

        Table browse = new Table();
        if (stacked) {
            float treeHeight = gridHeight / 3;
            browse.add(treeScroll).growX().height(treeHeight).row();
            browse.add(gridStack).grow().height(gridHeight - treeHeight - gap).padTop(gap);
        } else {
            browse.add(treeScroll).width(TREE_WIDTH).height(gridHeight).top();
            browse.add(gridStack).grow().height(gridHeight).padLeft(gap);
        }
        body.add(browse).growX().padTop(space("md")).row();

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
        if (stacked) {
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
        UiButton refresh = dialog.tool(Messages.getString("RefreshDirectory.text"), this::refresh);
        refresh.setName("camo-refresh");
        kit.tip(refresh, true).getActor().setText(Messages.getString("RefreshDirectory.toolTipText"));

        category = catalog.categoryOf(choice.selected());
        // Categories start folded: only the root and the way to the current camo are open
        tree.collapseAll();
        tree.expand(TREE_PREFIX, true);
        tree.select(TREE_PREFIX + category);
        refreshValues();
        showItems();
        // An actor of its own: the layer's actions are its fade, which showing it clears
        Actor pump = new Actor();
        pump.addAction(forever(run(this::drain)));
        dialog.layer().addActor(pump);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static UiTree.Node node(CamoCatalog.Node node) {
        List<UiTree.Node> children = new ArrayList<>();
        for (CamoCatalog.Node child : node.children()) {
            children.add(node(child));
        }
        return new UiTree.Node(TREE_PREFIX + node.id(), node.label()).children(children);
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
        String file = camo.getFilename();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    // ---- The grid ----

    /** Shows the camos the search finds, or else the chosen category's. */
    private void showItems() {
        String text = search.field.getText();
        items = text.trim().length() >= CamoCatalog.MIN_SEARCH ? catalog.search(text)
              : catalog.items(category, nested.isChecked());
        cards.clear();
        pictures.clear();
        grid.items(List.of(new UiCardGrid.Section(null, items.size())), this::card);
        gridScroll.setScrollY(0);
        gridScroll.updateVisualScroll();
        emptyLabel.setVisible(items.isEmpty());
        int selected = items.indexOf(choice.selected());
        if (selected >= 0) {
            scrollTo(selected);
        }
    }

    /** Scrolls the grid so that camo {@code index}'s row shows, once the dialog has its size. */
    private void scrollTo(int index) {
        float rowHeight = CARD + space("xl") + space("sm");
        float top = (index / columns) * rowHeight;
        gridScroll.addAction(run(() -> {
            gridScroll.layout();
            gridScroll.scrollTo(0, grid.getHeight() - top - rowHeight + space("sm"), 1, rowHeight, false, false);
        }));
    }

    private Actor card(int index) {
        Camouflage camo = items.get(index);
        String key = LobbySnapshot.camoKey(camo);
        Table card = new Table();
        card.setName("camo-item-" + index);
        card.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        Image picture = new Image();
        picture.setScaling(Scaling.fit);
        Texture texture = thumbnails.get(key);
        if (texture != null) {
            picture.setDrawable(new TextureRegionDrawable(texture));
        } else {
            ask(camo, key);
        }
        card.add(picture).size(CARD - 2 * space("xs")).pad(space("xs")).row();
        Label label = kit.typeLabel(name(camo), "small", "text.secondary");
        label.setEllipsis(true);
        label.setAlignment(Align.center);
        card.add(label).growX().minWidth(0).padLeft(space("xxs")).padRight(space("xxs"));
        kit.tip(card, true).getActor().setText(name(camo));
        card.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                select(camo);
                if (getTapCount() >= 2) {
                    confirm();
                }
            }
        });
        cards.put(key, card);
        pictures.put(key, picture);
        styleCard(card, key);
        return card;
    }

    private void styleCard(Table card, String key) {
        boolean selected = key.equals(LobbySnapshot.camoKey(choice.selected()));
        card.setBackground(kit.skin.getDrawable(selected ? "token-card-selected" : "token-card"));
    }

    private void select(Camouflage camo) {
        choice.select(camo);
        cards.forEach((key, card) -> styleCard(card, key));
        refreshValues();
        previewDue = true;
    }

    private void ask(Camouflage camo, String key) {
        if (!asked.add(key)) {
            return;
        }
        worker.execute(() -> {
            BufferedImage image = disposed ? null : source.thumbnail(camo, THUMBNAIL_PIXELS);
            arrived.add(() -> thumbnailArrived(key, image));
        });
    }

    private void thumbnailArrived(String key, @Nullable BufferedImage image) {
        if (image == null) {
            return;
        }
        Texture texture = GpuStartScreen.texture(image, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        Texture old = thumbnails.put(key, texture);
        if (old != null) {
            old.dispose();
        }
        Image picture = pictures.get(key);
        if (picture != null) {
            picture.setDrawable(new TextureRegionDrawable(texture));
        }
        Iterator<Map.Entry<String, Texture>> eldest = thumbnails.entrySet().iterator();
        while (thumbnails.size() > THUMBNAILS && eldest.hasNext()) {
            Map.Entry<String, Texture> entry = eldest.next();
            eldest.remove();
            asked.remove(entry.getKey());
            Image shown = pictures.remove(entry.getKey());
            if (shown != null) {
                shown.setDrawable(null);
            }
            entry.getValue().dispose();
        }
    }

    // ---- The preview ----

    /** Each frame: takes what the worker has finished, and asks for a preview when the camo changed meanwhile. */
    private void drain() {
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

    private void refresh() {
        worker.execute(() -> {
            CamoCatalog fresh = source.catalog(true);
            arrived.add(() -> {
                catalog = fresh;
                thumbnails.values().forEach(Texture::dispose);
                thumbnails.clear();
                asked.clear();
                tree.nodes(List.of(node(catalog.tree())));
                showItems();
                previewDue = true;
            });
        });
    }

    /** GPU thread, once the dialog has closed: gives the textures back; what the worker still finishes is dropped. */
    void dispose() {
        disposed = true;
        arrived.clear();
        pictures.values().forEach(picture -> picture.setDrawable(null));
        thumbnails.values().forEach(Texture::dispose);
        thumbnails.clear();
        previewTextures.forEach(Texture::dispose);
        previewTextures.clear();
    }

    // ---- For the probe and the tests ----

    UiTree tree() {
        return tree;
    }

    /** The card of the grid's camo {@code index}, once the grid has made it, or null. */
    @Nullable Actor item(int index) {
        return grid.findActor("camo-item-" + index);
    }

    List<Camouflage> items() {
        return List.copyOf(items);
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
