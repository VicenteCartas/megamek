/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.MapSetupForm.Entry;
import megamek.client.lobby.MapSetupForm.Kind;
import megamek.client.lobby.MapSetupForm.Picture;
import megamek.client.lobby.MapSetupForm.Slot;
import megamek.client.lobby.MapSetupForm.Theme;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.loaders.MapSettings;

/**
 * The lobby's map setup, in the forces' place: a war table of the map's board slots, drawn as the boards they hold,
 * with the size controls at its top and load, save, preview and regeneration at its foot. The drawer beside it holds
 * a searchable board browser of thumbnails or rows, with Advanced beside the Boards heading. Each slot's compact
 * rotation, theme and level controls sit above its full preview. A click picks a slot (Ctrl-click several),
 * and Place, or a double-click, replaces the picked slots; Add to Stack retains their existing boards and mode.
 * Map type and random map settings remain in the toolbar above both panels.
 * <p>
 * GPU thread. Every change runs on the Swing thread through the {@link Host}, and the window sends the setup anew.
 */
final class LobbyMapSetupView {
    /** The map setup's centre view key. */
    static final String KEY = "mapsetup";

    /** What the view asks of the window, besides the form's actions. */
    enum Action { LOAD, SAVE, BOARD_EDITOR, RANDOM_MAP, CONDITIONS, HELP, ADVANCED_SEARCH, PREVIEW_3D }

    /** The window's side of the view. */
    interface Host {
        /** Runs {@code action} on the form on the Swing thread; a fresh picture follows. */
        void act(Consumer<MapSetupForm> action);

        /** Sets the boards across, warning of odd-sized boards when due. */
        void mapWidth(int width);

        /** Sets the custom board width, warning of odd-sized boards when due. */
        void boardWidth(int width);

        /** Replaces the set of browser thumbnails wanted by the current viewport. */
        void previews(List<String> boards);

        /** Loads an EDT-owned map source and delivers it on the GPU thread; the view closes it. Null on failure. */
        void inspect(String board, Consumer<BoardSource> ready);

        void run(Action action);
    }

    private static final int[] MEDIA = { MapSettings.MEDIUM_GROUND, MapSettings.MEDIUM_ATMOSPHERE,
                                         MapSettings.MEDIUM_SPACE };
    private static final float HEX_HEIGHT = (float) (Math.sqrt(3) / 2);

    private final UiKit kit;
    private final Host host;
    private final Table body = new Table();
    private final UiSegmented medium;
    private final UiButton conditions;
    private final UiButton randomMap;
    private final UiStepper mapWidth;
    private final UiStepper mapHeight;
    private final HorizontalGroup sizeBar = new HorizontalGroup();
    private final Table sizeCell = new Table();
    private UiSelect sizeSelect;
    private List<BoardDimensions> sizesShown = List.of();
    private final UiStepper boardWidth;
    private final UiStepper boardHeight;
    private final WarTable table = new WarTable();
    private final Table tablePanel = new Table();
    private final LobbyBoardPreview inspection;
    private int inspectionTicket;
    private final Label spaceHint;
    private final Table drawer = new Table();
    private final ScrollPane drawerScroll;
    private Cell<ScrollPane> drawerCell;
    private final Table inspector = new Table();
    private final UiButton viewToggle;
    private final TextTooltip viewTip;
    private final Label boardCount;
    private final UiKit.TokenSearch search;
    private final Table list = new Table() {
        @Override
        public void draw(Batch batch, float parentAlpha) {
            // ScrollPane has now installed this frame's culling area, including a scroll-bar jump.
            validate();
            requestVisibleBoards();
            super.draw(batch, parentAlpha);
        }
    };
    private final ScrollPane listScroll;
    private final Label pickHint;
    private final UiButton place;
    private final UiButton addToStack;
    private UiPopover stackMenu;
    private int stackMenuSlot = -1;
    private List<String> stackMenuBoards = List.of();
    private String stackMenuSelection = "";
    private final UiButton load;
    private final UiButton save;
    private final UiButton advancedSearch;
    private final UiButton preview3D;
    private final UiButton generate;
    private final TextTooltip generateTip;
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<String, Drawable> drawables = new HashMap<>();
    private final Map<String, BoardEntry> rows = new HashMap<>();
    private final Set<Integer> slots = new TreeSet<>();
    private final Set<String> picks = new LinkedHashSet<>();
    private List<Theme> themes = List.of();
    private List<Entry> listed = List.of();
    private @Nullable List<String> requestedBoards;
    private int requestedWidth;
    private int requestedHeight;
    private @Nullable Picture shown;
    private boolean syncing;
    private boolean thumbnails = true;

    LobbyMapSetupView(UiKit kit, Host host) {
        this.kit = kit;
        this.host = host;
        body.top().pad(space("md"), space("lg"), space("md"), space("lg"));

        Table toolbar = new Table();
        toolbar.left();
        toolbar.add(kit.roleTypeLabel("overline", colonless("ChatLounge.labMapType"), "text.secondary"))
              .padRight(space("sm"));
        medium = kit.tokenSegmented(true, Messages.getString("ChatLounge.butGroundMap"),
              Messages.getString("ChatLounge.name.lowAltitudeMap"), Messages.getString("ChatLounge.name.spaceMap"));
        medium.setName("map-medium");
        String[] mediumNames = { "map-medium-ground", "map-medium-atmosphere", "map-medium-space" };
        for (int index = 0; index < mediumNames.length; index++) {
            medium.option(index).setName(mediumNames[index]);
        }
        UiKit.onChange(medium, () -> {
            if (!syncing) {
                int chosen = MEDIA[medium.getSelectedIndex()];
                host.act(form -> form.medium(chosen));
            }
        });
        toolbar.add(medium);
        toolbar.add().growX();
        conditions = kit.button("button-icon" + UiTheme.COMPACT, "settings", null, null);
        conditions.setName("map-conditions");
        tip(conditions, Messages.getString("ChatLounge.butConditions"));
        UiKit.onChange(conditions, () -> host.run(Action.CONDITIONS));
        randomMap = toolButton("button-quiet", "tune", "Lobby.battlefield.randomMap", "map-random-settings",
              Action.RANDOM_MAP);
        UiButton help = kit.button("button-icon" + UiTheme.COMPACT, "help", null, null);
        help.setName("map-help");
        tip(help, Messages.getString("ChatLounge.map.title.mapAssemblyHelp"));
        UiKit.onChange(help, () -> host.run(Action.HELP));
        toolbar.add(conditions).padRight(space("xs"));
        toolbar.add(randomMap).padRight(space("xs"));
        toolbar.add(help);
        body.add(toolbar).growX().row();

        mapWidth = kit.stepper(Messages.getString("Lobby.map.across"), 1, LobbyCommands.MIN_MAP_SIZE,
              LobbyCommands.MAX_MAP_SIZE);
        mapWidth.setName("map-width");
        name(mapWidth, "map-width");
        UiKit.onChange(mapWidth, () -> {
            if (!syncing) {
                host.mapWidth(mapWidth.value());
            }
        });
        mapHeight = kit.stepper(Messages.getString("Lobby.map.down"), 1, LobbyCommands.MIN_MAP_SIZE,
              LobbyCommands.MAX_MAP_SIZE);
        name(mapHeight, "map-height");
        UiKit.onChange(mapHeight, () -> {
            if (!syncing) {
                int height = mapHeight.value();
                host.act(form -> form.mapHeight(height));
            }
        });
        boardWidth = kit.stepper(colonless("ChatLounge.labBoardWidth"), 16, LobbyCommands.MIN_BOARD_SIZE,
              LobbyCommands.MAX_BOARD_SIZE);
        name(boardWidth, "map-board-width");
        UiKit.onChange(boardWidth, () -> {
            if (!syncing) {
                host.boardWidth(boardWidth.value());
            }
        });
        boardHeight = kit.stepper(colonless("ChatLounge.labBoardHeight"), 17, LobbyCommands.MIN_BOARD_SIZE,
              LobbyCommands.MAX_BOARD_SIZE);
        name(boardHeight, "map-board-height");
        UiKit.onChange(boardHeight, () -> {
            if (!syncing) {
                int height = boardHeight.value();
                host.act(form -> form.boardHeight(height));
            }
        });
        sizeBar.wrap().left().rowLeft().space(space("lg")).wrapSpace(space("sm"));

        Table main = new Table();
        tablePanel.setName("map-layout-panel");
        tablePanel.setBackground(kit.skin.getDrawable("token-panel-plain"));
        tablePanel.pad(space("md"), space("lg"), space("md"), space("lg"));
        tablePanel.add(sizeBar).growX().minWidth(0).left().row();
        Stack tableStack = new Stack();
        tableStack.add(table);
        spaceHint = kit.typeLabel(Messages.getString("Lobby.map.spaceHint"), "body", "text.secondary");
        spaceHint.setWrap(true);
        spaceHint.setAlignment(Align.center);
        Table hintLayer = new Table();
        hintLayer.setTouchable(Touchable.disabled);
        hintLayer.add(spaceHint).width(size("dialog.sm"));
        tableStack.add(hintLayer);
        tablePanel.add(tableStack).grow().minWidth(0).minHeight(0).padTop(space("md")).row();
        inspection = new LobbyBoardPreview(kit, this::closeInspection);
        inspection.setVisible(false);
        main.add(new Stack(tablePanel, inspection)).grow().minWidth(0).minHeight(0);

        drawer.top();
        drawer.setName("map-drawer");
        drawer.setBackground(kit.skin.getDrawable("token-panel-plain"));
        drawer.pad(space("md"), space("lg"), space("md"), space("lg"));
        viewToggle = kit.button("button-icon" + UiTheme.COMPACT, "list", null, null);
        viewToggle.setName("map-board-view");
        viewTip = kit.tip(viewToggle, true);
        UiKit.onChange(viewToggle, () -> {
            thumbnails = !thumbnails;
            fillList();
            showPicks();
        });
        inspector.top().left();
        drawer.add(inspector).growX().row();
        Table browserHead = new Table();
        browserHead.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.map.boards"), "text.secondary"))
              .left();
        boardCount = kit.typeLabel("", "small", "text.muted");
        boardCount.setEllipsis(true);
        browserHead.add(boardCount).left().padLeft(space("sm")).growX().minWidth(0);
        advancedSearch = toolButton("button-quiet", "search", "Lobby.map.advanced",
              "map-advanced-search", Action.ADVANCED_SEARCH);
        browserHead.add(advancedSearch).right().padLeft(space("sm"));
        drawer.add(browserHead).growX().padTop(space("lg")).row();
        search = kit.tokenSearch(Messages.getString("Lobby.map.search"), true);
        search.setName("map-search");
        search.field.setName("map-search-field");
        tip(search.field, Messages.getString("Lobby.map.searchTip"));
        UiKit.onChange(search.field, () -> {
            if (!syncing) {
                String text = search.field.getText();
                host.act(form -> form.search(text));
            }
        });
        drawer.add(search).growX().padTop(space("sm")).row();
        list.top().left();
        listScroll = kit.scrollList(list, true, false);
        listScroll.setScrollingDisabled(true, false);
        listScroll.setName("map-board-list");
        // A few boards at least: on a short window the drawer scrolls rather than squeezing the list away
        float listMinimum = 4 * size("control.md");
        drawer.add(listScroll).grow().minHeight(listMinimum).prefHeight(listMinimum).padTop(space("sm")).row();
        pickHint = kit.typeLabel("", "small", "text.secondary");
        pickHint.setWrap(true);
        drawer.add(pickHint).growX().padTop(space("sm")).row();
        Table placing = new Table();
        place = kit.button("button-primary" + UiTheme.COMPACT, null, Messages.getString("Lobby.map.place"), null);
        place.setName("map-place");
        UiKit.onChange(place, this::placePicked);
        addToStack = kit.button("button-secondary" + UiTheme.COMPACT, "stack", Messages.getString("Lobby.map.addToStack"), null);
        addToStack.setName("map-add-to-stack");
        tip(addToStack, Messages.getString("Lobby.map.addToStackTip"));
        UiKit.onChange(addToStack, this::addPickedToStack);
        placing.defaults().growX().uniformX();
        placing.add(place).padRight(space("xs"));
        placing.add(addToStack);
        drawer.add(placing).growX().padTop(space("sm")).row();
        drawerScroll = kit.scrollList(drawer, true, false);
        drawerScroll.setScrollingDisabled(true, false);
        drawerScroll.setName("map-drawer-scroll");
        drawerCell = main.add(drawerScroll).width(size("dialog.sm")).growY().minHeight(0).padLeft(space("md"));
        body.add(main).grow().minHeight(0).padTop(space("md")).row();

        load = footButton("button-quiet", null, "Lobby.battlefield.loadMapSetup", "map-load", Action.LOAD);
        save = footButton("button-quiet", null, "Lobby.battlefield.saveMapSetup", "map-save", Action.SAVE);
        tip(save, Messages.getString("ChatLounge.map.saveMapSetupTip"));
        UiButton boardEditor = footButton("button-quiet", null, "Lobby.battlefield.boardEditor", "map-board-editor",
              Action.BOARD_EDITOR);
        Table files = new Table();
        files.add(load).padRight(space("xs"));
        files.add(save).padRight(space("xs"));
        files.add(boardEditor);
        preview3D = footButton("button-secondary", "cube", "BoardSelectionDialog.ViewGameBoard3D",
              "map-preview-3d", Action.PREVIEW_3D);
        generate = kit.button("button-primary" + UiTheme.COMPACT, "play",
              Messages.getString("Lobby.map.regenerate"), null);
        generate.setName("map-generate");
        generateTip = kit.tip(generate, true);
        UiKit.onChange(generate, () -> host.act(MapSetupForm::generate));
        Table previewActions = new Table();
        previewActions.add(preview3D).padRight(space("sm"));
        previewActions.add(generate);
        Table footer = new Table() {
            private boolean stacked;

            @Override
            public void layout() {
                boolean narrow = getWidth() < files.getPrefWidth() + previewActions.getPrefWidth() + space("md");
                if (narrow != stacked) {
                    stacked = narrow;
                    clearChildren();
                    add(files).left().expandX();
                    if (stacked) {
                        row();
                    }
                    add(previewActions).right().expandX().padLeft(stacked ? 0 : space("md"))
                          .padTop(stacked ? space("sm") : 0);
                }
                super.layout();
            }
        };
        footer.add(files).left().expandX();
        footer.add(previewActions).right().expandX().padLeft(space("md"));
        tablePanel.add(footer).growX().minWidth(0).padTop(space("md"));
    }

    private UiButton toolButton(String style, String icon, String key, String name, Action action) {
        UiButton button = kit.button(style + UiTheme.COMPACT, icon, Messages.getString(key), null);
        button.setName(name);
        UiKit.onChange(button, () -> host.run(action));
        return button;
    }

    private UiButton footButton(String style, @Nullable String icon, String key, String name, Action action) {
        return toolButton(style, icon, key, name, action);
    }

    private static void name(UiStepper stepper, String name) {
        stepper.setName(name);
        stepper.down().setName(name + "-down");
        stepper.up().setName(name + "-up");
    }

    Table body() {
        return body;
    }

    @Nullable Picture shown() {
        return shown;
    }

    /** The picked slots' indices, for the probe and the tests. */
    List<Integer> pickedSlots() {
        return List.copyOf(slots);
    }

    /** The view's control named {@code name}, for the probe and the tests. */
    @Nullable Actor control(String name) {
        return body.findActor(name);
    }

    // ---- Showing the setup ----

    /** Shows the setup anew; the themes are those any slot can take. */
    void show(Picture picture, List<Theme> themes) {
        // Unrelated lobby events also refresh this view. Keep its live controls and open dropdowns when nothing changed.
        if (picture.equals(shown) && themes.equals(this.themes)) {
            return;
        }
        boolean first = shown == null;
        if (stackMenu != null && (picture.space() || first || picture.mapWidth() != shown.mapWidth()
              || picture.mapHeight() != shown.mapHeight() || picture.boardWidth() != shown.boardWidth()
              || picture.boardHeight() != shown.boardHeight())) {
            stackMenu.cancel();
        }
        shown = picture;
        this.themes = themes;
        if (picture.space()) {
            closeInspection();
            host.previews(List.of());
            requestedBoards = null;
        }
        int count = picture.slots().size();
        slots.removeIf(slot -> slot >= count);
        if (first && count > 0 && slots.isEmpty()) {
            slots.add(0);
        }
        syncing = true;
        try {
            for (int index = 0; index < MEDIA.length; index++) {
                if (MEDIA[index] == picture.medium() && medium.getSelectedIndex() != index) {
                    medium.select(index);
                }
            }
            mapWidth.value(picture.mapWidth());
            mapHeight.value(picture.mapHeight());
            boardWidth.value(picture.boardWidth());
            boardHeight.value(picture.boardHeight());
            showSizes(picture);
            if (!search.field.getText().trim().equals(picture.search())) {
                search.field.setText(picture.search());
            }
        } finally {
            syncing = false;
        }
        boolean space = picture.space();
        conditions.setDisabled(space);
        randomMap.setDisabled(space);
        load.setDisabled(space);
        save.setDisabled(space);
        advancedSearch.setDisabled(!picture.advancedSearch());
        generate.setDisabled(!picture.generable());
        generateTip.getActor().setText(plain(picture.generateTip()));
        drawerScroll.setVisible(!space);
        drawerCell.width(space ? 0 : size("dialog.sm")).padLeft(space ? 0 : space("md"));
        drawerScroll.invalidateHierarchy();
        spaceHint.setVisible(space);
        table.show(picture);
        if (!picture.boards().equals(listed)) {
            listed = picture.boards();
            picks.retainAll(listed.stream().map(Entry::name).toList());
            fillList();
        }
        boardCount.setText(MessageFormat.format(Messages.getString("Lobby.map.boardCount"),
              Math.max(0, listed.size() - (listed.isEmpty() || !listed.get(0).generated() ? 0 : 1))));
        fillInspector();
        showPicks();
    }

    /** The size bar: the boards across and down off space; the board size, and its custom fields, or space's size. */
    private void showSizes(Picture picture) {
        if (!picture.sizes().equals(sizesShown) || sizeSelect == null) {
            sizesShown = picture.sizes();
            String[] items = new String[sizesShown.size() + 1];
            for (int index = 0; index < sizesShown.size(); index++) {
                items[index] = MessageFormat.format(Messages.getString("Lobby.map.size"), sizesShown.get(index).w(),
                      sizesShown.get(index).h());
            }
            items[sizesShown.size()] = Messages.getString("ChatLounge.CustomMapSize");
            sizeSelect = kit.tokenSelect(true, items);
            sizeSelect.setName("map-board-size");
            UiKit.onChange(sizeSelect, () -> {
                if (syncing) {
                    return;
                }
                int index = sizeSelect.getSelectedIndex();
                if (index < sizesShown.size()) {
                    BoardDimensions size = sizesShown.get(index);
                    host.act(form -> form.boardSize(size));
                } else {
                    host.act(MapSetupForm::customSize);
                }
            });
            sizeCell.clearChildren();
            sizeCell.add(kit.typeLabel(colonless("ChatLounge.labBoardSize"), "small", "text.secondary"))
                  .padRight(UiStepper.gap());
            sizeCell.add(sizeSelect).width(size("dialog.sm") / 2);
        }
        int index = picture.sizeIndex() < 0 ? sizesShown.size() : picture.sizeIndex();
        if (sizeSelect.getSelectedIndex() != index) {
            sizeSelect.select(index);
        }
        sizeBar.clearChildren();
        if (!picture.space()) {
            sizeBar.addActor(mapWidth);
            sizeBar.addActor(mapHeight);
            sizeBar.addActor(sizeCell);
        }
        if (picture.space() || picture.customSize()) {
            sizeBar.addActor(boardWidth);
            sizeBar.addActor(boardHeight);
        }
    }

    /** The drawer's head: the picked slots and the browser's view toggle. */
    private void fillInspector() {
        inspector.clearChildren();
        Picture picture = shown;
        if (picture == null) {
            return;
        }
        List<Slot> picked = picked();
        Table head = new Table();
        String title = picked.isEmpty() ? Messages.getString("Lobby.map.noSlot")
              : picked.size() == 1 ? MessageFormat.format(Messages.getString("Lobby.map.slot"),
                    picked.get(0).index() + 1, slotName(picked.get(0)))
              : MessageFormat.format(Messages.getString("Lobby.map.slots"), picked.size());
        Label titleLabel = kit.typeLabel(title, "body.strong", "text.primary");
        titleLabel.setEllipsis(true);
        titleLabel.setName("map-inspector-title");
        head.add(titleLabel).growX().minWidth(0).left();
        head.add(viewToggle).padLeft(space("sm"));
        inspector.add(head).growX().row();
    }

    private String slotName(Slot slot) {
        return switch (slot.kind()) {
            case EMPTY -> Messages.getString("Lobby.map.empty");
            case SURPRISE -> MessageFormat.format(Messages.getString("Lobby.map.surpriseBoards"),
                  slot.stack().size());
            default -> slot.label();
        };
    }

    private List<Slot> picked() {
        Picture picture = shown;
        if (picture == null) {
            return List.of();
        }
        return slots.stream().filter(index -> index < picture.slots().size()).map(picture.slots()::get).toList();
    }

    /** The board browser, in thumbnail tiles or rows, Generated first. */
    private void fillList() {
        requestedBoards = null;
        list.clearChildren();
        rows.clear();
        viewToggle.icons.forEach(icon -> ((Image) icon).setDrawable(kit.skin.getDrawable(
              thumbnails ? "icon-list" : "icon-grid")));
        viewTip.getActor().setText(Messages.getString(thumbnails ? "Lobby.map.listView" : "Lobby.map.thumbnailView"));
        for (int index = 0; index < listed.size(); index++) {
            Entry entry = listed.get(index);
            BoardEntry row = new BoardEntry(entry);
            row.setName("map-board-" + index);
            if (row.inspect != null) {
                row.inspect.setName("map-board-inspect-" + index);
            }
            row.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (row.inspect != null && event.getTarget().isDescendantOf(row.inspect)) {
                        return;
                    }
                    boolean several = Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                          || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT));
                    pick(entry, several);
                    if (getTapCount() == 2 && !several) {
                        if (inspection.isVisible()) {
                            if (!entry.generated()) { inspect(entry); }
                        } else {
                            placePicked();
                        }
                    }
                }
            });
            rows.put(entry.name(), row);
            Cell<BoardEntry> cell = list.add(row).growX().minWidth(0);
            if (thumbnails) {
                cell.uniformX().space(space("sm"));
            }
            if (!thumbnails || index % 2 == 1) {
                list.row();
            }
        }
        if (listed.isEmpty()) {
            kit.empty(list, Messages.getString("Lobby.map.noBoards"), true);
        }
    }

    private void requestVisibleBoards() {
        Picture picture = shown;
        Rectangle visible = list.getCullingArea();
        if (picture == null || picture.space() || visible == null) {
            return;
        }
        List<String> wanted = new ArrayList<>();
        for (Actor child : list.getChildren()) {
            if (child instanceof BoardEntry row && !row.entry.generated()
                  && row.getY() + row.getHeight() >= visible.y && row.getY() <= visible.y + visible.height) {
                wanted.add(row.entry.name());
            }
        }
        if (!wanted.equals(requestedBoards) || requestedWidth != picture.boardWidth()
              || requestedHeight != picture.boardHeight()) {
            requestedBoards = List.copyOf(wanted);
            requestedWidth = picture.boardWidth();
            requestedHeight = picture.boardHeight();
            host.previews(requestedBoards);
        }
    }

    /** A click on a board: picks it alone or, with {@code several}, along with the others. */
    void pick(Entry entry, boolean several) {
        if (several) {
            if (!picks.remove(entry.name())) {
                picks.add(entry.name());
            }
        } else {
            picks.clear();
            picks.add(entry.name());
        }
        showPicks();
    }

    /** The entries' picked look and pictures, and which placing actions apply. */
    private void showPicks() {
        rows.forEach((name, row) -> {
            row.pressed(picks.contains(name));
            row.showPicture();
        });
        Picture picture = shown;
        boolean target = !slots.isEmpty() && picture != null && !picture.space();
        String one = picks.size() == 1 ? picks.iterator().next() : null;
        place.setDisabled(!target || one == null);
        boolean already = target && !picks.isEmpty() && picked().stream().allMatch(slot -> picks.stream()
              .allMatch(board -> slot.stack().contains(MapSettings.stackBoardName(board))));
        addToStack.setText(kit.cased("button", Messages.getString(already ? "Lobby.map.alreadyAdded" : "Lobby.map.addToStack")));
        addToStack.setDisabled(!target || picks.isEmpty() || already);
        pickHint.setText(inspection.isVisible() ? Messages.getString("Lobby.map.inspectHint")
              : !target ? Messages.getString("Lobby.map.noSlot")
              : Messages.getString("Lobby.map.pickHint"));
    }

    private static float aspect(Picture picture) {
        return (picture.boardWidth() * .75f + .25f) / Math.max(1, (picture.boardHeight() + .5f) * HEX_HEIGHT);
    }

    // ---- Placing boards ----

    private void placePicked() {
        if (picks.size() != 1 || slots.isEmpty()) {
            return;
        }
        String board = picks.iterator().next();
        List<Integer> targets = List.copyOf(slots);
        host.act(form -> {
            targets.forEach(slot -> form.assign(slot, board));
            if (MapSettings.BOARD_GENERATED.equals(board)) {
                form.generate();
            }
        });
    }

    private void addPickedToStack() {
        if (picks.isEmpty() || slots.isEmpty()) {
            return;
        }
        List<String> boards = List.copyOf(picks);
        List<Integer> targets = List.copyOf(slots);
        host.act(form -> targets.forEach(slot -> form.addToStack(slot, boards)));
    }

    private void openStack(int index, Actor trigger) {
        if (shown == null || index >= shown.slots().size() || shown.slots().get(index).stack().size() < 2) { return; }
        if (stackMenu != null && stackMenu.isVisible() && stackMenuSlot == index) { stackMenu.cancel(); return; }
        if (stackMenu == null) {
            stackMenu = new UiPopover(kit, () -> stackMenuSlot = -1, UiTheme.TOKEN);
            stackMenu.setName("map-stack-menu");
            body.getStage().addActor(stackMenu);
        }
        stackMenuSlot = index;
        stackMenuBoards = List.of();
        stackContents(shown.slots().get(index));
        stackMenu.openedFrom(trigger);
        Vector2 at = trigger.localToStageCoordinates(new Vector2(0, 0));
        stackMenu.showAt(at.x, at.y);
    }

    private void stackContents(Slot slot) {
        if (slot.stack().equals(stackMenuBoards) && slot.board().equals(stackMenuSelection)) { return; }
        stackMenuBoards = slot.stack();
        stackMenuSelection = slot.board();
        if (slot.stack().isEmpty()) { stackMenu.cancel(); return; }
        Table contents = new Table();
        contents.defaults().growX();
        for (int index = 0; index < slot.stack().size(); index++) {
            String board = slot.stack().get(index);
            Table row = new Table();
            boolean selected = slot.kind() != Kind.SURPRISE && board.equals(MapSettings.stackBoardName(slot.board()));
            UiButton choice = kit.menuRow(UiTheme.TOKEN_MENU_ROW,
                  MapSetupForm.label(board, shown.boardWidth(), shown.boardHeight()), null, selected, false).pressed(selected);
            choice.setName("map-stack-select-" + slot.index() + "-" + index);
            choice.getLabel().setEllipsis(true);
            tip(choice, board);
            UiKit.onChange(choice, () -> {
                stackMenu.cancel();
                host.act(form -> form.selectFromStack(slot.index(), board));
            });
            UiButton remove = kit.button("button-icon" + UiTheme.COMPACT, "close", null, null);
            remove.setName("map-stack-remove-" + slot.index() + "-" + index);
            tip(remove, Messages.getString("Lobby.map.stackRemoveTip"));
            remove.setDisabled(slot.stack().size() == 1 && MapSettings.BOARD_GENERATED.equals(board));
            UiKit.onChange(remove, () -> host.act(form -> form.removeFromStack(slot.index(), board, new java.util.Random())));
            row.add(choice).width(250).minWidth(0).growX().height(size("control.sm"));
            row.add(remove).padLeft(space("sm"));
            contents.add(row).row();
        }
        stackMenu.header(MessageFormat.format(Messages.getString("Lobby.map.stackTitle"), slot.index() + 1,
              slot.stack().size()), null).content(contents);
    }

    /** A click on a slot: picks it alone or, with {@code several}, along with the others. */
    void pickSlot(int index, boolean several) {
        if (several) {
            if (!slots.remove(index)) {
                slots.add(index);
            }
        } else {
            slots.clear();
            slots.add(index);
        }
        refreshSelection();
    }

    private void refreshSelection() {
        Picture picture = shown;
        if (picture != null) {
            table.show(picture);
        }
        fillInspector();
        showPicks();
    }

    // ---- Pictures ----

    /** Takes a board's picture, by {@link LobbyMapSetupController#pictureKey}, and shows it where it is due. */
    void picture(String key, @Nullable BufferedImage image) {
        Texture old = textures.remove(key);
        drawables.remove(key);
        if (old != null) {
            old.dispose();
        }
        if (image != null) {
            Texture texture = GpuStartScreen.texture(image, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            textures.put(key, texture);
            drawables.put(key, new TextureRegionDrawable(texture));
        }
        Picture picture = shown;
        if (picture != null) {
            table.show(picture);
            rows.values().forEach(BoardEntry::showPicture);
        }
    }

    /** The view left the centre: closes inspection and forgets the picks; pictures stay until {@link #dispose}. */
    void closed() {
        if (stackMenu != null) { stackMenu.cancel(); }
        closeInspection();
        host.previews(List.of());
        requestedBoards = null;
        shown = null;
        slots.clear();
        picks.clear();
        listed = List.of();
    }

    /** Gives the pictures' textures back. */
    void dispose() {
        if (stackMenu != null) { stackMenu.cancel(); stackMenu.remove(); stackMenu = null; }
        closeInspection();
        textures.values().forEach(Texture::dispose);
        textures.clear();
        drawables.clear();
        table.clearChildren();
        list.clearChildren();
        rows.clear();
    }

    private void inspect(Entry entry) {
        if (stackMenu != null) { stackMenu.cancel(); }
        int ticket = ++inspectionTicket;
        tablePanel.setVisible(false);
        inspection.setVisible(true);
        inspection.loading(entry.label());
        showPicks();
        host.inspect(entry.name(), source -> {
            if (ticket == inspectionTicket && shown != null) {
                inspection.show(source);
            } else if (source != null) {
                source.close();
            }
        });
    }

    private void closeInspection() {
        inspectionTicket++;
        inspection.setVisible(false);
        inspection.dispose();
        tablePanel.setVisible(true);
        showPicks();
    }

    // ---- Helpers ----

    private void tip(Actor actor, String text) {
        kit.tip(actor, true).getActor().setText(text);
    }

    private static String colonless(String key) {
        String text = Messages.getString(key).trim();
        return text.endsWith(":") ? text.substring(0, text.length() - 1).trim() : text;
    }

    /** A Swing tooltip's text without its HTML. */
    static String plain(String text) {
        return text.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    /** A browser entry keeps its click listener while its preview arrives, so a double-click stays one gesture. */
    private final class BoardEntry extends UiButton {
        private final Entry entry;
        private final Image image = new Image();
        private final Table placeholder = new Table();
        private final @Nullable UiButton inspect;

        BoardEntry(Entry entry) {
            super(kit, UiTheme.TOKEN_ROW);
            this.entry = entry;
            image.setScaling(Scaling.fit);
            image.setName("map-board-picture");
            placeholder.add(kit.icon(entry.generated() ? "hex" : "map", size("icon.lg"),
                  UiTheme.tokenColor("text.muted")));
            Stack face = new Stack(image, placeholder);
            inspect = entry.generated() ? null : kit.button("button-icon" + UiTheme.COMPACT, "cube", null, null);
            if (inspect != null) {
                tip(inspect, Messages.getString("Lobby.map.inspect"));
                UiKit.onChange(inspect, () -> inspect(entry));
            }
            Label label = getLabel();
            label.setText(entry.label());
            label.setEllipsis(true);
            if (thumbnails) {
                TextButtonStyle style = new TextButtonStyle(getStyle());
                style.up = kit.skin.getDrawable("token-card");
                style.over = kit.skin.getDrawable("token-card-over");
                style.down = style.over;
                style.checked = kit.skin.getDrawable("token-card-selected");
                style.checkedOver = style.checked;
                style.checkedDown = style.checked;
                setStyle(style);
                pad(space("sm"));
                if (inspect != null) {
                    Table inspectLayer = new Table();
                    inspectLayer.top().right().add(inspect);
                    face.add(inspectLayer);
                }
                add(face).growX().minWidth(0).prefWidth(0).height(size("dialog.sm") / 3).row();
                label.setAlignment(Align.center);
                add(label).growX().minWidth(0).padTop(space("sm"));
            } else {
                add(face).size(size("control.lg")).padRight(space("sm"));
                Table text = new Table();
                label.setAlignment(Align.left);
                text.add(label).growX().minWidth(0).row();
                if (!entry.folder().isBlank()) {
                    Label folder = kit.typeLabel(entry.folder(), "small", "text.muted");
                    folder.setEllipsis(true);
                    text.add(folder).growX().minWidth(0).padTop(space("xxs"));
                }
                add(text).growX().minWidth(0);
                if (inspect != null) {
                    add(inspect).padLeft(space("xs"));
                }
            }
            tip(this, entry.folder().isBlank() ? entry.label() : entry.folder() + "/" + entry.label());
            showPicture();
        }

        private @Nullable String pictureKey() {
            Picture picture = shown;
            return picture == null || entry.generated() ? null
                  : LobbyMapSetupController.pictureKey(entry.name(), false, picture.boardWidth(),
                        picture.boardHeight(), null, 0);
        }

        void showPicture() {
            Drawable drawable = drawables.get(pictureKey());
            image.setDrawable(drawable);
            placeholder.setVisible(drawable == null);
        }

    }

    /**
     * The war table: the map's board slots in their columns and rows, as large as the room allows at the boards'
     * shape, each a card drawn as its board.
     */
    private final class WarTable extends WidgetGroup {
        private int columns = 1;
        private int lines = 1;
        private float aspect = 1;
        private boolean space;

        void show(Picture picture) {
            columns = Math.max(1, picture.mapWidth());
            lines = Math.max(1, picture.mapHeight());
            aspect = aspect(picture);
            if (space != picture.space() || getChildren().size != (picture.space() ? 1 : picture.slots().size())) {
                clearChildren();
            }
            space = picture.space();
            if (picture.space()) {
                columns = 1;
                lines = 1;
                if (getChildren().isEmpty()) {
                    Table tile = new Table();
                    tile.setBackground(kit.skin.getDrawable("token-card"));
                    tile.setName("map-space");
                    addActor(tile);
                }
            } else {
                for (Slot slot : picture.slots()) {
                    if (slot.index() >= getChildren().size) {
                        addActor(new SlotTile(slot.index()));
                    }
                    ((SlotTile) getChildren().get(slot.index())).show(slot, picture);
                }
            }
            invalidate();
        }

        @Override
        public void layout() {
            float gap = space("sm");
            float width = Math.max(0, getWidth() - gap * (columns - 1));
            float height = Math.max(0, getHeight() - gap * (lines - 1));
            float tileWidth = width / columns;
            float tileHeight = tileWidth / aspect;
            if (space) {
                tileWidth = Math.min(tileWidth, height / lines * aspect);
                tileHeight = tileWidth / aspect;
            } else {
                // Fit the image and the controls, remeasuring when a narrower header wraps onto another line.
                for (int pass = 0; pass < 4; pass++) {
                    float chrome = 0;
                    for (Actor child : getChildren()) {
                        chrome = Math.max(chrome, ((SlotTile) child).chromeHeight(tileWidth));
                    }
                    float inset = 2 * space("xs");
                    tileHeight = Math.max(1, tileWidth - inset) / aspect + chrome;
                    if (tileHeight <= height / lines || pass == 3) {
                        break;
                    }
                    tileWidth = Math.max(inset + 1, (height / lines - chrome) * aspect + inset);
                }
            }
            float totalWidth = tileWidth * columns + gap * (columns - 1);
            float totalHeight = tileHeight * lines + gap * (lines - 1);
            float left = (getWidth() - totalWidth) / 2;
            float top = (getHeight() + totalHeight) / 2;
            int index = 0;
            for (Actor child : getChildren()) {
                int column = index % columns;
                int line = index / columns;
                child.setBounds(left + column * (tileWidth + gap), top - (line + 1) * tileHeight - line * gap,
                      tileWidth, tileHeight);
                if (child instanceof com.badlogic.gdx.scenes.scene2d.utils.Layout layout) {
                    layout.validate();
                }
                index++;
            }
        }
    }

    /** A slot keeps its controls during image arrivals and server echoes, including an open theme dropdown. */
    private final class SlotTile extends Table {
        private final Image image = new Image();
        private final Table placeholder = new Table();
        private final Label caption = kit.typeLabel("", "small", "text.primary");
        private final UiButton rotate;
        private final UiStepper offset;
        private final UiButton reset;
        private final UiButton stackButton;
        private final UiButton roll;
        private final UiButton surpriseMode;
        private final Table stackTools = new Table();
        private boolean wrappedTools;
        private final HorizontalGroup controls = new HorizontalGroup();
        private final Table bar = new Table();
        private final Table themeBox = new Table() {
            @Override
            public float getPrefWidth() {
                return Math.min(156, Math.max(40, SlotTile.this.getWidth() - size("control.sm") - 6 * space("xs")));
            }
        };
        private UiSelect theme;
        private TextTooltip detail;
        private List<Theme> offered = List.of();

        SlotTile(int index) {
            setName("map-slot-" + index);
            pad(space("xs"));
            image.setScaling(Scaling.fit);
            image.setName("map-slot-picture-" + index);
            Stack face = new Stack(image, placeholder);
            rotate = kit.button("button-icon" + UiTheme.COMPACT, "twist-right", null, null);
            rotate.setName("map-slot-rotate-" + index);
            tip(rotate, Messages.getString("ChatLounge.MapPreviewButton.rotateTooltip"));
            UiKit.onChange(rotate, () -> host.act(form -> form.toggleRotation(index)));
            controls.wrap().left().rowLeft().space(space("xs"));
            controls.addActor(rotate);
            controls.addActor(themeBox);
            offset = kit.stepper(Messages.getString("Lobby.map.level"), 0, MapSettings.MIN_BOARD_LEVEL_OFFSET,
                  MapSettings.MAX_BOARD_LEVEL_OFFSET);
            name(offset, "map-level-offset-" + index);
            tip(offset, MessageFormat.format(Messages.getString("ChatLounge.MapPreviewButton.levelOffsetHelp"),
                  MapSettings.MIN_BOARD_LEVEL_OFFSET, MapSettings.MAX_BOARD_LEVEL_OFFSET));
            UiKit.onChange(offset, () -> {
                int value = offset.value();
                host.act(form -> form.levelOffset(index, value));
            });
            reset = kit.button("button-icon" + UiTheme.COMPACT, "undo", null, null);
            reset.setName("map-level-offset-reset-" + index);
            tip(reset, Messages.getString("ChatLounge.MapPreviewButton.resetLevelOffset"));
            UiKit.onChange(reset, () -> host.act(form -> form.levelOffset(index, 0)));
            Table level = new Table();
            level.add(offset);
            level.add(reset);
            controls.addActor(level);
            stackButton = kit.button("button-quiet" + UiTheme.COMPACT, "stack", "", null);
            Actor stackIcon = stackButton.icons.getFirst();
            stackButton.clearChildren();
            stackButton.add(stackButton.getLabel());
            stackButton.add(stackIcon).padLeft(space("xs"));
            stackButton.setName("map-stack-" + index);
            tip(stackButton, Messages.getString("Lobby.map.stackTip"));
            UiKit.onChange(stackButton, () -> openStack(index, stackButton));
            roll = kit.button("button-icon" + UiTheme.COMPACT, "die", null, null);
            roll.setName("map-stack-roll-" + index);
            tip(roll, Messages.getString("Lobby.map.stackRollTip"));
            UiKit.onChange(roll, () -> host.act(form -> form.rollStack(index, new java.util.Random())));
            surpriseMode = kit.button("button-icon" + UiTheme.COMPACT, "show", null, null);
            surpriseMode.setName("map-stack-surprise-" + index);
            tip(surpriseMode, Messages.getString("Lobby.map.stackSurpriseTip"));
            UiKit.onChange(surpriseMode, () -> host.act(form -> form.toggleSurprise(index, new java.util.Random())));
            stackTools.add(stackButton);
            stackTools.add(roll);
            stackTools.add(surpriseMode);
            bar.setBackground(kit.skin.getDrawable("token-panel-plain"));
            bar.pad(space("xxs"));
            bar.add(controls).growX().minWidth(0);
            bar.add(stackTools).right().top().padLeft(space("xs"));
            add(bar).growX().minWidth(0).padBottom(space("xxs")).row();
            add(face).grow().minWidth(0).minHeight(0).row();
            caption.setEllipsis(true);
            add(caption).growX().minWidth(0).left().padTop(space("xxs"));
            setTouchable(Touchable.enabled);
            addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (event.getTarget().isDescendantOf(bar)) {
                        return;
                    }
                    boolean several = Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                          || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT));
                    pickSlot(index, several);
                }
            });
        }

        float chromeHeight(float width) {
            setWidth(width);
            float available = width - getPadLeft() - getPadRight() - bar.getPadLeft() - bar.getPadRight();
            float stackWidth = stackTools.isVisible() ? stackTools.getPrefWidth() + space("xs") : 0;
            boolean wrap = stackTools.isVisible() && available - stackWidth < 180;
            if (wrap != wrappedTools || (bar.getCell(stackTools) != null) != stackTools.isVisible()) {
                wrappedTools = wrap;
                bar.clearChildren();
                bar.add(controls).growX().minWidth(0);
                if (wrap) { bar.row(); }
                if (stackTools.isVisible()) { bar.add(stackTools).right().top().padLeft(wrap ? 0 : space("xs")); }
            }
            controls.setWidth(Math.max(1, available - (wrap ? 0 : stackWidth)));
            controls.invalidate();
            bar.invalidate();
            return getPadTop() + getPadBottom() + bar.getPrefHeight() + caption.getPrefHeight() + 2 * space("xxs");
        }

        @Override
        protected void sizeChanged() {
            super.sizeChanged();
            if (controls != null) {
                controls.invalidate();
                themeBox.invalidateHierarchy();
            }
        }

        void show(Slot slot, Picture picture) {
            setBackground(kit.skin.getDrawable(slots.contains(slot.index()) ? "token-card-selected"
                  : slot.kind() == Kind.EMPTY ? "token-row-slot" : "token-card"));
            Drawable drawable = drawables.get(LobbyMapSetupController.pictureKey(slot, picture));
            image.setDrawable(slot.kind() == Kind.SURPRISE ? null : drawable);
            placeholder.clearChildren();
            String icon = switch (slot.kind()) {
                case EMPTY -> "add";
                case SURPRISE -> "hidden";
                case GENERATED -> drawable == null ? "hex" : null;
                case FIXED -> drawable == null ? "map" : null;
            };
            if (icon != null) {
                placeholder.add(kit.icon(icon, size("icon.lg"), UiTheme.tokenColor("text.secondary")));
            }
            caption.setText(slotName(slot));
            String hint = slot.kind() == Kind.SURPRISE ? Messages.getString("Lobby.map.stackSurpriseTip")
                  : slot.kind() == Kind.FIXED && LobbyMapSetupController.serverSide(slot.file())
                        ? Messages.getString("Lobby.map.serverSide") : "";
            if (!hint.isEmpty()) {
                if (detail == null) {
                    detail = kit.tip(this, true);
                }
                detail.getActor().setText(hint);
            } else if (detail != null) {
                detail.hide();
                removeListener(detail);
                detail = null;
            }
            rotate.setDisabled(!slot.canRotate());
            stackTools.setVisible(!picture.space() && slot.stack().size() >= 2);
            stackButton.setText(Integer.toString(slot.stack().size()));
            stackButton.setDisabled(picture.space() || slot.stack().isEmpty());
            roll.setDisabled(picture.space() || slot.stack().size() < 2);
            surpriseMode.setDisabled(picture.space() || slot.stack().isEmpty());
            surpriseMode.pressed(slot.kind() == Kind.SURPRISE);
            if (stackMenuSlot == slot.index() && stackMenu != null && stackMenu.isVisible()) { stackContents(slot); }
            rotate.pressed(slot.rotated());
            offset.value(slot.levelOffset());
            offset.down().setDisabled(!slot.adjustable() || slot.levelOffset() <= MapSettings.MIN_BOARD_LEVEL_OFFSET);
            offset.up().setDisabled(!slot.adjustable() || slot.levelOffset() >= MapSettings.MAX_BOARD_LEVEL_OFFSET);
            reset.setDisabled(!slot.adjustable() || slot.levelOffset() == 0);
            List<Theme> choices = new ArrayList<>(themes);
            if (slot.theme() != null && choices.stream().noneMatch(value -> slot.theme().equals(value.theme()))) {
                choices.add(new Theme(slot.theme(), BoardEditorBlueprint.themeLabel(slot.theme())));
            }
            if (theme == null || !offered.equals(choices)) {
                offered = List.copyOf(choices);
                themeBox.clearChildren();
                theme = kit.tokenSelectInline(offered.stream().map(value -> value.theme() == null
                      ? Messages.getString("Lobby.map.defaultTheme") : value.label()).toArray(String[]::new));
                theme.setName("map-theme-" + slot.index());
                tip(theme, Messages.getString("ChatLounge.MapPreviewButton.theme"));
                UiKit.onChange(theme, () -> {
                    String chosen = offered.get(theme.getSelectedIndex()).theme();
                    host.act(form -> form.theme(slot.index(), chosen));
                });
                themeBox.add(theme).growX().minWidth(0);
            }
            for (int index = 0; index < offered.size(); index++) {
                if (Objects.equals(offered.get(index).theme(), slot.theme())) {
                    theme.select(index);
                }
            }
            theme.setDisabled(!slot.adjustable());
        }
    }
}
