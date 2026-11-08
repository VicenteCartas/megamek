/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

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

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.IconCatalog;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiCardGrid;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.common.icons.AbstractIcon;

/**
 * The browsing half of the lobby's icon choosers (camos, portraits), as the Swing {@code abstractIconChooserPanel}
 * offers it: a search of three letters or more over every icon with "Include subcategories" beside it, the icon
 * categories as a tree, and the chosen category's icons as a grid of pictures (with those of its sub-categories while
 * "Include subcategories" is on). A click selects an icon and a double click confirms it.
 * <p>
 * GPU thread. The pictures are read on {@code worker} and become textures in {@link #drain}, which the owning view
 * calls each frame; only the grid's rows near the view exist ({@link UiCardGrid}), and at most {@link #THUMBNAILS}
 * pictures are kept. Its controls are named with the owner's prefix, such as "camo-tree" and "camo-item-3".
 *
 * @param <T> the kind of icon
 */
final class LobbyIconBrowser<T extends AbstractIcon> {
    /** Where the browser's icons come from; called on the worker thread. */
    interface Source<T extends AbstractIcon> {
        /** The icon directory as the chooser offers it, read from disk again when {@code refresh}. */
        IconCatalog<T> catalog(boolean refresh);

        /** The icon's picture, {@code size} pixels wide, or null. */
        @Nullable BufferedImage thumbnail(T icon, int size);
    }

    /** The most pictures kept as textures; the grid makes the others again as they scroll back. */
    static final int THUMBNAILS = 320;
    static final int THUMBNAIL_PIXELS = 84;
    private static final float CARD = 72;
    private static final float TREE_WIDTH = 220;
    /** Below this body width the tree goes above the grid, and a chooser's rows below go one under another. */
    static final float STACK_BELOW = 560;

    private final UiKit kit;
    private final String prefix;
    /** The tree's node names: the prefix and a colon before the category's tree path, such as "camo:Clans/". */
    private final String treePrefix;
    private final Source<T> source;
    private final Executor worker;
    private final Consumer<T> selected;
    private final Consumer<T> confirmed;
    private final Queue<Runnable> arrived = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<String, Texture> thumbnails = new LinkedHashMap<>(64, .75f, true);
    private final Set<String> asked = new HashSet<>();
    private final Map<String, Table> cards = new HashMap<>();
    private final Map<String, Image> pictures = new HashMap<>();
    private final UiTree tree;
    private final UiCardGrid grid;
    private final int columns;
    private final ScrollPane gridScroll;
    private final Label emptyLabel;
    private final UiKit.TokenSearch search;
    private final UiChoice nested;
    private final Table tools = new Table();
    private final Table browse = new Table();
    private IconCatalog<T> catalog;
    private String category;
    private @Nullable T selection;
    private List<T> items = List.of();
    private boolean disposed;

    /**
     * Builds the {@link #tools} (search and "Include subcategories") and the {@link #browse} area (tree and grid) for
     * the owner to place.
     *
     * @param prefix     the controls' name prefix, such as "camo"
     * @param searchHint the search field's hint
     * @param emptyText  what the grid says when it holds no icon
     * @param catalog    the icon directory as first read
     * @param initial    the icon selected at first, or null; its category is the one shown
     * @param bodyWidth  the width the browser has, which decides the columns
     * @param gridHeight the height the tree and the grid share
     * @param selected   takes an icon a click selects
     * @param confirmed  takes an icon a double click confirms
     */
    LobbyIconBrowser(UiKit kit, String prefix, String searchHint, String emptyText, Source<T> source,
          IconCatalog<T> catalog, @Nullable T initial, Executor worker, float bodyWidth, float gridHeight,
          Consumer<T> selected, Consumer<T> confirmed) {
        this.kit = kit;
        this.prefix = prefix;
        this.treePrefix = prefix + ":";
        this.source = source;
        this.catalog = catalog;
        this.selection = initial;
        this.worker = worker;
        this.selected = selected;
        this.confirmed = confirmed;
        float gap = space("sm");
        boolean stacked = bodyWidth < STACK_BELOW;

        search = kit.tokenSearch(searchHint, true);
        search.field.setName(prefix + "-search");
        UiKit.onChange(search.field, this::showItems);
        nested = kit.check(Messages.getString("Lobby.icon.nested"), true);
        nested.setName(prefix + "-nested");
        UiKit.onChange(nested, this::showItems);
        tools.add(search).growX().minWidth(0);
        tools.add(nested).padLeft(space("md"));

        tree = new UiTree(kit, UiTheme.TOKEN);
        tree.setName(prefix + "-tree");
        tree.nodes(List.of(node(catalog.tree())));
        tree.onSelect(id -> {
            category = id.substring(treePrefix.length());
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
        grid.setName(prefix + "-grid");
        gridScroll = kit.scrollList(grid, true);
        gridScroll.setFadeScrollBars(false);
        emptyLabel = kit.typeLabel(emptyText, "small", "text.muted");
        emptyLabel.setAlignment(Align.center);
        emptyLabel.setTouchable(Touchable.disabled);
        Stack gridStack = new Stack(gridScroll, emptyLabel);

        if (stacked) {
            float treeHeight = gridHeight / 3;
            browse.add(treeScroll).growX().height(treeHeight).row();
            browse.add(gridStack).grow().height(gridHeight - treeHeight - gap).padTop(gap);
        } else {
            browse.add(treeScroll).width(TREE_WIDTH).height(gridHeight).top();
            browse.add(gridStack).grow().height(gridHeight).padLeft(gap);
        }

        category = catalog.categoryOf(initial);
        // Categories start folded: only the root and the way to the current icon are open
        tree.collapseAll();
        tree.expand(treePrefix, true);
        tree.select(treePrefix + category);
        showItems();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private UiTree.Node node(IconCatalog.Node node) {
        List<UiTree.Node> children = new ArrayList<>();
        for (IconCatalog.Node child : node.children()) {
            children.add(node(child));
        }
        return new UiTree.Node(treePrefix + node.id(), node.label()).children(children);
    }

    /** The icon's name as the chooser shows it: its file name without the extension. */
    static String name(AbstractIcon icon) {
        String file = icon.getFilename();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** The search and "Include subcategories", for the owner to place above the {@link #browse} area. */
    Table tools() {
        return tools;
    }

    /** The tree and the grid. */
    Table browse() {
        return browse;
    }

    // ---- The grid ----

    /** Shows the icons the search finds, or else the chosen category's. */
    private void showItems() {
        String text = search.field.getText();
        items = text.trim().length() >= IconCatalog.MIN_SEARCH ? catalog.search(text)
              : catalog.items(category, nested.isChecked());
        cards.clear();
        pictures.clear();
        grid.items(List.of(new UiCardGrid.Section(null, items.size())), this::card);
        gridScroll.setScrollY(0);
        gridScroll.updateVisualScroll();
        emptyLabel.setVisible(items.isEmpty());
        int shown = items.indexOf(selection);
        if (shown >= 0) {
            scrollTo(shown);
        }
    }

    /** Scrolls the grid so that icon {@code index}'s row shows, once the dialog has its size. */
    private void scrollTo(int index) {
        float rowHeight = CARD + space("xl") + space("sm");
        float top = (index / columns) * rowHeight;
        gridScroll.addAction(run(() -> {
            gridScroll.layout();
            gridScroll.scrollTo(0, grid.getHeight() - top - rowHeight + space("sm"), 1, rowHeight, false, false);
        }));
    }

    private Actor card(int index) {
        T icon = items.get(index);
        String key = IconCatalog.key(icon);
        Table card = new Table();
        card.setName(prefix + "-item-" + index);
        card.setTouchable(Touchable.enabled);
        Image picture = new Image();
        picture.setScaling(Scaling.fit);
        Texture texture = thumbnails.get(key);
        if (texture != null) {
            picture.setDrawable(new TextureRegionDrawable(texture));
        } else {
            ask(icon, key);
        }
        card.add(picture).size(CARD - 2 * space("xs")).pad(space("xs")).row();
        Label label = kit.typeLabel(name(icon), "small", "text.secondary");
        label.setEllipsis(true);
        label.setAlignment(Align.center);
        card.add(label).growX().minWidth(0).padLeft(space("xxs")).padRight(space("xxs"));
        kit.tip(card, true).getActor().setText(name(icon));
        card.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                select(icon);
                selected.accept(icon);
                if (getTapCount() >= 2) {
                    confirmed.accept(icon);
                }
            }
        });
        cards.put(key, card);
        pictures.put(key, picture);
        styleCard(card, key);
        return card;
    }

    private void styleCard(Table card, String key) {
        boolean chosen = key.equals(IconCatalog.key(selection));
        card.setBackground(kit.skin.getDrawable(chosen ? "token-card-selected" : "token-card"));
    }

    /** Marks {@code icon} as the selected one in the grid. */
    void select(@Nullable T icon) {
        selection = icon;
        cards.forEach((key, card) -> styleCard(card, key));
    }

    private void ask(T icon, String key) {
        if (!asked.add(key)) {
            return;
        }
        worker.execute(() -> {
            BufferedImage image = disposed ? null : source.thumbnail(icon, THUMBNAIL_PIXELS);
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

    /** Each frame, from the owning view: takes the pictures and directory the worker has finished. */
    void drain() {
        for (Runnable done = arrived.poll(); done != null; done = arrived.poll()) {
            if (!disposed) {
                done.run();
            }
        }
    }

    /** Reads the icon directory again on the worker, then shows it afresh. */
    void refresh() {
        worker.execute(() -> {
            IconCatalog<T> fresh = disposed ? null : source.catalog(true);
            arrived.add(() -> {
                if (fresh == null) {
                    return;
                }
                catalog = fresh;
                thumbnails.values().forEach(Texture::dispose);
                thumbnails.clear();
                asked.clear();
                tree.nodes(List.of(node(catalog.tree())));
                showItems();
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
    }

    // ---- For the probe and the tests ----

    UiTree tree() {
        return tree;
    }

    /** The card of the grid's icon {@code index}, once the grid has made it, or null. */
    @Nullable Actor item(int index) {
        return grid.findActor(prefix + "-item-" + index);
    }

    List<T> items() {
        return List.copyOf(items);
    }
}
