/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
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
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPictureCard;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.loaders.MapSettings;

/**
 * The lobby's map setup, in the forces' place: a war table of the map's board slots, drawn as the boards they hold,
 * with the map's size in boards and the boards' size above it, and a drawer beside it with the picked slot's rotation,
 * theme and level offset over a searchable board browser with a preview. A click picks a slot (Ctrl-click several), a
 * click on a board previews it and Place, or a double-click, puts it in the picked slots; Ctrl-clicked boards make a
 * random or a surprise board. Map type, load and save, the random map settings and generating the battlefield are in the
 * toolbar and the footer, as the Swing {@code MapSetupDialog} has them.
 * <p>
 * GPU thread. Every change runs on the Swing thread through the {@link Host}, and the window sends the setup anew.
 */
final class LobbyMapSetupView {
    /** The map setup's centre view key. */
    static final String KEY = "mapsetup";

    /** What the view asks of the window, besides the form's actions. */
    enum Action { LOAD, SAVE, RANDOM_MAP, CONDITIONS, HELP, ADVANCED_SEARCH, PREVIEW_3D }

    /** The window's side of the view. */
    interface Host {
        /** Runs {@code action} on the form on the Swing thread; a fresh picture follows. */
        void act(Consumer<MapSetupForm> action);

        /** Sets the boards across, warning of odd-sized boards when due. */
        void mapWidth(int width);

        /** Sets the custom board width, warning of odd-sized boards when due. */
        void boardWidth(int width);

        /** Draws a listed board's picture for the preview. */
        void preview(String board);

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
    private final Table sizeBar = new Table();
    private final Table sizeCell = new Table();
    private UiSelect sizeSelect;
    private List<BoardDimensions> sizesShown = List.of();
    private final UiStepper boardWidth;
    private final UiStepper boardHeight;
    private final WarTable table = new WarTable();
    private final Label spaceHint;
    private final Table drawer = new Table();
    private final ScrollPane drawerScroll;
    private Cell<ScrollPane> drawerCell;
    private final Table inspector = new Table();
    private final Label boardCount;
    private final UiKit.TokenSearch search;
    private final Table list = new Table();
    private final ScrollPane listScroll;
    private final UiPictureCard preview;
    private final Label pickHint;
    private final UiButton place;
    private final UiButton placeRotated;
    private final UiButton random;
    private final UiButton surprise;
    private final UiButton load;
    private final UiButton save;
    private final UiButton advancedSearch;
    private final UiButton preview3D;
    private final UiButton generate;
    private final TextTooltip generateTip;
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<String, Drawable> drawables = new HashMap<>();
    private final Map<String, UiButton> rows = new HashMap<>();
    private final Set<Integer> slots = new TreeSet<>();
    private final Set<String> picks = new LinkedHashSet<>();
    private List<Theme> themes = List.of();
    private List<Entry> listed = List.of();
    private @Nullable String focus;
    private @Nullable Picture shown;
    private boolean syncing;

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
        sizeBar.left();
        body.add(sizeBar).growX().padTop(space("md")).row();

        Table main = new Table();
        Stack tableStack = new Stack();
        Table tablePanel = new Table();
        tablePanel.setBackground(kit.skin.getDrawable("token-panel-plain"));
        tablePanel.add(table).grow().pad(space("lg"));
        tableStack.add(tablePanel);
        spaceHint = kit.typeLabel(Messages.getString("Lobby.map.spaceHint"), "body", "text.secondary");
        spaceHint.setWrap(true);
        spaceHint.setAlignment(Align.center);
        Table hintLayer = new Table();
        hintLayer.setTouchable(Touchable.disabled);
        hintLayer.add(spaceHint).width(size("dialog.sm"));
        tableStack.add(hintLayer);
        main.add(tableStack).grow().minWidth(0).minHeight(0);

        drawer.top();
        drawer.setName("map-drawer");
        drawer.setBackground(kit.skin.getDrawable("token-panel-plain"));
        drawer.pad(space("md"), space("lg"), space("md"), space("lg"));
        inspector.top().left();
        drawer.add(inspector).growX().row();
        Table browserHead = new Table();
        browserHead.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.map.boards"), "text.secondary"))
              .left();
        boardCount = kit.typeLabel("", "small", "text.muted");
        browserHead.add(boardCount).left().padLeft(space("sm")).growX();
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
        preview = kit.pictureCard("", 1);
        preview.setName("map-preview");
        preview.maxHeight(size("dialog.sm") / 3);
        drawer.add(preview).growX().padTop(space("md")).row();
        pickHint = kit.typeLabel("", "small", "text.secondary");
        pickHint.setWrap(true);
        drawer.add(pickHint).growX().padTop(space("sm")).row();
        Table placing = new Table();
        place = kit.button("button-primary" + UiTheme.COMPACT, null, Messages.getString("Lobby.map.place"), null);
        place.setName("map-place");
        UiKit.onChange(place, () -> placePicked(false));
        placeRotated = kit.button("button-secondary" + UiTheme.COMPACT, "twist-right",
              Messages.getString("Lobby.map.placeRotated"), null);
        placeRotated.setName("map-place-rotated");
        tip(placeRotated, Messages.getString("ChatLounge.MapPreviewButton.rotateTooltip"));
        UiKit.onChange(placeRotated, () -> placePicked(true));
        random = kit.button("button-secondary" + UiTheme.COMPACT, null, "", null);
        random.setName("map-random");
        tip(random, Messages.getString("Lobby.map.randomTip"));
        UiKit.onChange(random, this::placeRandom);
        surprise = kit.button("button-secondary" + UiTheme.COMPACT, "hidden", "", null);
        surprise.setName("map-surprise");
        tip(surprise, Messages.getString("Lobby.map.surpriseTip"));
        UiKit.onChange(surprise, this::placeSurprise);
        placing.defaults().growX().uniformX();
        placing.add(place).padRight(space("xs"));
        placing.add(placeRotated).row();
        placing.add(random).padRight(space("xs")).padTop(space("xs"));
        placing.add(surprise).padTop(space("xs"));
        drawer.add(placing).growX().padTop(space("sm")).row();
        drawerScroll = kit.scrollList(drawer, true, false);
        drawerScroll.setScrollingDisabled(true, false);
        drawerScroll.setName("map-drawer-scroll");
        drawerCell = main.add(drawerScroll).width(size("dialog.sm")).growY().minHeight(0).padLeft(space("md"));
        body.add(main).grow().minHeight(0).padTop(space("md")).row();

        Table footer = new Table();
        load = footButton("button-quiet", null, "Lobby.battlefield.loadMapSetup", "map-load", Action.LOAD);
        save = footButton("button-quiet", null, "Lobby.battlefield.saveMapSetup", "map-save", Action.SAVE);
        tip(save, Messages.getString("ChatLounge.map.saveMapSetupTip"));
        advancedSearch = footButton("button-quiet", "search", "AdvancedSearchMapDialog.title",
              "map-advanced-search", Action.ADVANCED_SEARCH);
        footer.add(load).padRight(space("xs"));
        footer.add(save).padRight(space("xs"));
        footer.add(advancedSearch);
        footer.add().growX();
        preview3D = footButton("button-secondary", "cube", "BoardSelectionDialog.ViewGameBoard3D",
              "map-preview-3d", Action.PREVIEW_3D);
        footer.add(preview3D).padRight(space("sm"));
        generate = kit.button("button-primary" + UiTheme.COMPACT, "play",
              Messages.getString("ChatLounge.GenerateBattlefield"), null);
        generate.setName("map-generate");
        generateTip = kit.tip(generate, true);
        UiKit.onChange(generate, () -> host.act(MapSetupForm::generate));
        footer.add(generate);
        body.add(footer).growX().padTop(space("md")).row();
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
        boolean first = shown == null;
        shown = picture;
        this.themes = themes;
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
            sizeBar.add(mapWidth).padRight(space("lg"));
            sizeBar.add(mapHeight).padRight(space("xl"));
            sizeBar.add(sizeCell).padRight(space("lg"));
        }
        if (picture.space() || picture.customSize()) {
            sizeBar.add(boardWidth).padRight(space("lg"));
            sizeBar.add(boardHeight);
        }
        sizeBar.add().growX();
    }

    /** The drawer's head: the picked slots, with the one slot's rotation, theme and level offset. */
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
        UiButton all = kit.button("button-quiet" + UiTheme.COMPACT, null, Messages.getString("Lobby.map.selectAll"),
              null);
        all.setName("map-select-all");
        UiKit.onChange(all, () -> {
            for (int index = 0; index < picture.slots().size(); index++) {
                slots.add(index);
            }
            refreshSelection();
        });
        all.setDisabled(picture.slots().size() < 2 || picked.size() == picture.slots().size());
        head.add(all).padLeft(space("sm"));
        inspector.add(head).growX().row();
        List<Slot> adjustable = picked.stream().filter(Slot::adjustable).toList();
        if (adjustable.isEmpty()) {
            return;
        }
        Slot lead = adjustable.get(0);
        Table grid = new Table();
        grid.left();
        List<Slot> turnable = picked.stream().filter(Slot::canRotate).toList();
        UiChoice rotate = kit.check(Messages.getString("ChatLounge.MapPreviewButton.rotate"),
              !turnable.isEmpty() && turnable.get(0).rotated());
        rotate.setName("map-rotate");
        rotate.setDisabled(turnable.isEmpty());
        tip(rotate, Messages.getString("ChatLounge.MapPreviewButton.rotateTooltip"));
        UiKit.onChange(rotate, () -> {
            if (syncing) {
                return;
            }
            boolean on = rotate.isChecked();
            List<Integer> indices = turnable.stream().filter(slot -> slot.rotated() != on).map(Slot::index)
                  .toList();
            host.act(form -> indices.forEach(form::toggleRotation));
        });
        grid.add(rotate).colspan(2).left().row();

        List<Theme> offered = new ArrayList<>(themes);
        if (lead.theme() != null && offered.stream().noneMatch(theme -> lead.theme().equals(theme.theme()))) {
            offered.add(new Theme(lead.theme(), BoardEditorBlueprint.themeLabel(lead.theme())));
        }
        String[] labels = offered.stream().map(Theme::label).toArray(String[]::new);
        UiSelect theme = kit.tokenSelect(true, labels);
        theme.setName("map-theme");
        for (int index = 0; index < offered.size(); index++) {
            String value = offered.get(index).theme();
            if (value == null ? lead.theme() == null : value.equals(lead.theme())) {
                theme.select(index);
            }
        }
        List<Integer> indices = adjustable.stream().map(Slot::index).toList();
        UiKit.onChange(theme, () -> {
            String chosen = offered.get(theme.getSelectedIndex()).theme();
            host.act(form -> indices.forEach(index -> form.theme(index, chosen)));
        });
        grid.add(kit.typeLabel(Messages.getString("ChatLounge.MapPreviewButton.theme"), "small", "text.secondary"))
              .left().padTop(space("sm")).padRight(space("md"));
        grid.add(theme).growX().padTop(space("sm")).row();

        UiStepper offset = kit.stepper(null, lead.levelOffset(), MapSettings.MIN_BOARD_LEVEL_OFFSET,
              MapSettings.MAX_BOARD_LEVEL_OFFSET);
        name(offset, "map-level-offset");
        tip(offset, MessageFormat.format(Messages.getString("ChatLounge.MapPreviewButton.levelOffsetHelp"),
              MapSettings.MIN_BOARD_LEVEL_OFFSET, MapSettings.MAX_BOARD_LEVEL_OFFSET));
        UiKit.onChange(offset, () -> {
            int value = offset.value();
            host.act(form -> indices.forEach(index -> form.levelOffset(index, value)));
        });
        UiButton reset = kit.button("button-quiet" + UiTheme.COMPACT, "undo",
              Messages.getString("ChatLounge.MapPreviewButton.resetLevelOffset"), null);
        reset.setName("map-level-offset-reset");
        reset.setDisabled(adjustable.stream().allMatch(slot -> slot.levelOffset() == 0));
        UiKit.onChange(reset, () -> host.act(form -> indices.forEach(index -> form.levelOffset(index, 0))));
        Table offsetRow = new Table();
        offsetRow.add(offset);
        offsetRow.add(reset).padLeft(space("sm"));
        offsetRow.add().growX();
        grid.add(kit.typeLabel(Messages.getString("Lobby.map.levelOffset"), "small", "text.secondary")).left()
              .padTop(space("sm")).padRight(space("md"));
        grid.add(offsetRow).growX().padTop(space("sm")).row();
        inspector.add(grid).growX().padTop(space("sm")).row();
    }

    private String slotName(Slot slot) {
        return switch (slot.kind()) {
            case EMPTY -> Messages.getString("Lobby.map.empty");
            case SURPRISE -> MessageFormat.format(Messages.getString("Lobby.map.surpriseBoards"),
                  slot.surprise().size());
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

    /** The board list, one row a board, Generated first. */
    private void fillList() {
        list.clearChildren();
        rows.clear();
        for (int index = 0; index < listed.size(); index++) {
            Entry entry = listed.get(index);
            UiButton row = kit.button(UiTheme.TOKEN_ROW, null, entry.label(), null);
            row.setName("map-board-" + index);
            row.getLabel().setAlignment(Align.left);
            row.getLabel().setEllipsis(true);
            row.getCell(row.getLabel()).growX().minWidth(0);
            if (!entry.folder().isBlank()) {
                Label folder = kit.typeLabel(entry.folder(), "small", "text.muted");
                folder.setEllipsis(true);
                row.add(folder).padLeft(space("sm")).maxWidth(size("dialog.sm") / 3).minWidth(0);
            }
            row.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    boolean several = Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                          || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT));
                    pick(entry, several);
                    if (getTapCount() == 2 && !several) {
                        placePicked(false);
                    }
                }
            });
            rows.put(entry.name(), row);
            list.add(row).growX().row();
        }
        if (listed.isEmpty()) {
            kit.empty(list, Messages.getString("Lobby.map.noBoards"), true);
        }
    }

    /** A click on a board: previews it, and picks it alone or, with {@code several}, along with the others. */
    void pick(Entry entry, boolean several) {
        if (several) {
            if (!picks.remove(entry.name())) {
                picks.add(entry.name());
            }
        } else {
            picks.clear();
            picks.add(entry.name());
        }
        focus = entry.name();
        if (!entry.generated()) {
            host.preview(entry.name());
        }
        showPicks();
    }

    /** The rows' picked look, the preview of the focused board and which placing actions apply. */
    private void showPicks() {
        rows.forEach((name, row) -> row.pressed(picks.contains(name)));
        Picture picture = shown;
        boolean target = !slots.isEmpty() && picture != null && !picture.space();
        String one = picks.size() == 1 ? picks.iterator().next() : null;
        Entry entry = one == null ? null : listed.stream().filter(e -> e.name().equals(one)).findFirst()
              .orElse(null);
        place.setDisabled(!target || one == null);
        placeRotated.setDisabled(!target || entry == null || entry.generated() || picture == null
              || picture.boardWidth() % 2 != 0);
        boolean several = picks.size() > 1;
        random.setText(kit.cased("button", MessageFormat.format(Messages.getString("Lobby.map.random"), picks.size())));
        surprise.setText(kit.cased("button",
              MessageFormat.format(Messages.getString("Lobby.map.surprise"), picks.size())));
        random.setDisabled(!target || !several);
        surprise.setDisabled(!target || !several);
        pickHint.setText(!target ? Messages.getString("Lobby.map.noSlot")
              : several ? "" : Messages.getString("Lobby.map.pickHint"));
        showPreview();
    }

    private void showPreview() {
        Picture picture = shown;
        String board = focus;
        if (board == null || picture == null) {
            preview.picture(null, 1, Messages.getString("Lobby.map.previewHint"));
            return;
        }
        Entry entry = listed.stream().filter(e -> e.name().equals(board)).findFirst().orElse(null);
        String label = entry == null ? board : entry.label();
        if (entry != null && entry.generated()) {
            preview.picture(null, aspect(picture), Messages.getString("BoardSelectionDialog.GeneratedMapSettings"));
            return;
        }
        Drawable drawable = drawables.get(LobbyMapSetupController.pictureKey(board, false, picture.boardWidth(),
              picture.boardHeight(), null, 0));
        preview.picture(drawable, aspect(picture), drawable == null ? Messages.getString(
              "Lobby.battlefield.drawing") : label);
    }

    private static float aspect(Picture picture) {
        return (picture.boardWidth() * .75f + .25f) / Math.max(1, (picture.boardHeight() + .5f) * HEX_HEIGHT);
    }

    // ---- Placing boards ----

    private void placePicked(boolean rotated) {
        if (picks.size() != 1 || slots.isEmpty()) {
            return;
        }
        String board = picks.iterator().next();
        List<Integer> targets = List.copyOf(slots);
        host.act(form -> targets.forEach(slot -> {
            if (rotated) {
                form.assignRotated(slot, board);
            } else {
                form.assign(slot, board);
            }
        }));
    }

    private void placeRandom() {
        if (picks.size() < 2 || slots.isEmpty()) {
            return;
        }
        List<String> boards = List.copyOf(picks);
        List<Integer> targets = List.copyOf(slots);
        host.act(form -> {
            java.util.Random dice = new java.util.Random();
            targets.forEach(slot -> form.assignRandom(slot, boards, dice));
        });
    }

    private void placeSurprise() {
        if (picks.size() < 2 || slots.isEmpty()) {
            return;
        }
        List<String> boards = List.copyOf(picks);
        List<Integer> targets = List.copyOf(slots);
        host.act(form -> targets.forEach(slot -> form.assignSurprise(slot, boards)));
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
    void picture(String key, BufferedImage image) {
        Texture texture = GpuStartScreen.texture(image, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        Texture old = textures.put(key, texture);
        if (old != null) {
            old.dispose();
        }
        drawables.put(key, new TextureRegionDrawable(texture));
        Picture picture = shown;
        if (picture != null) {
            table.show(picture);
            showPreview();
        }
    }

    /** The view left the centre: forgets the picks; the pictures stay until {@link #dispose}. */
    void closed() {
        shown = null;
        slots.clear();
        picks.clear();
        focus = null;
        listed = List.of();
    }

    /** Gives the pictures' textures back. */
    void dispose() {
        textures.values().forEach(Texture::dispose);
        textures.clear();
        drawables.clear();
        table.clearChildren();
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

    /**
     * The war table: the map's board slots in their columns and rows, as large as the room allows at the boards'
     * shape, each a card drawn as its board.
     */
    private final class WarTable extends WidgetGroup {
        private int columns = 1;
        private int lines = 1;
        private float aspect = 1;

        void show(Picture picture) {
            clearChildren();
            columns = Math.max(1, picture.mapWidth());
            lines = Math.max(1, picture.mapHeight());
            aspect = aspect(picture);
            if (picture.space()) {
                columns = 1;
                lines = 1;
                Table tile = new Table();
                tile.setBackground(kit.skin.getDrawable("token-card"));
                tile.setName("map-space");
                addActor(tile);
            } else {
                for (Slot slot : picture.slots()) {
                    addActor(tile(slot, picture));
                }
            }
            invalidate();
        }

        @Override
        public void layout() {
            float gap = space("sm");
            float width = Math.max(0, getWidth() - gap * (columns - 1));
            float height = Math.max(0, getHeight() - gap * (lines - 1));
            float tileWidth = Math.min(width / columns, height / lines * aspect);
            float tileHeight = tileWidth / aspect;
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

    /** A slot's card: its board's picture, or what it holds, its name and its marks. */
    private Table tile(Slot slot, Picture picture) {
        Table tile = new Table();
        tile.setName("map-slot-" + slot.index());
        boolean picked = slots.contains(slot.index());
        tile.setBackground(kit.skin.getDrawable(picked ? "token-card-selected"
              : slot.kind() == Kind.EMPTY ? "token-row-slot" : "token-card"));
        tile.pad(space("xs"));
        String key = LobbyMapSetupController.pictureKey(slot, picture);
        Drawable drawable = key == null ? null : drawables.get(key);
        Stack face = new Stack();
        if (drawable != null) {
            Image image = new Image(drawable, Scaling.fit);
            if (slot.kind() == Kind.SURPRISE) {
                image.getColor().a = .35f;
            }
            face.add(image);
        }
        String icon = switch (slot.kind()) {
            case EMPTY -> "add";
            case GENERATED -> "hex";
            case SURPRISE -> "hidden";
            case FIXED -> drawable == null ? "map" : null;
        };
        if (icon != null) {
            Table mark = new Table();
            mark.add(kit.icon(icon, size("icon.lg"), UiTheme.tokenColor("text.secondary")));
            face.add(mark);
        }
        tile.add(face).grow().minHeight(0).row();
        Table caption = new Table();
        Label name = kit.typeLabel(slotName(slot), "small", slot.kind() == Kind.EMPTY ? "text.muted"
              : "text.primary");
        name.setEllipsis(true);
        caption.add(name).growX().minWidth(0).left();
        if (slot.rotated() && slot.canRotate()) {
            caption.add(kit.icon("twist-right", size("icon.sm"), UiTheme.tokenColor("text.secondary")))
                  .padLeft(space("xxs"));
        }
        if (slot.theme() != null) {
            caption.add(kit.icon("palette", size("icon.sm"), UiTheme.tokenColor("text.secondary")))
                  .padLeft(space("xxs"));
        }
        if (slot.levelOffset() != 0) {
            caption.add(kit.typeLabel((slot.levelOffset() > 0 ? "+" : "") + slot.levelOffset(), "small",
                  "text.secondary")).padLeft(space("xxs"));
        }
        tile.add(caption).growX().padTop(space("xxs"));
        if (slot.kind() == Kind.FIXED && LobbyMapSetupController.serverSide(slot.file())) {
            tip(tile, Messages.getString("Lobby.map.serverSide"));
        } else if (slot.kind() == Kind.SURPRISE) {
            tip(tile, String.join("\n", slot.surprise()));
        }
        tile.setTouchable(Touchable.enabled);
        tile.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                boolean several = Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                      || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT));
                pickSlot(slot.index(), several);
            }
        });
        return tile;
    }
}
