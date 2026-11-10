/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardHud.GAP;
import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.common.board.BoardEditorBlueprint.themeLabel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCardGrid;
import megamek.client.ui.gdx.UiChoiceGrid;
import megamek.client.ui.gdx.UiHexSides;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiNumber;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Configuration;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFlag;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.board.HexIssue;
import megamek.common.board.RailRoute;
import megamek.logging.MMLogger;

/** Native UIKit workspace. All document changes are commands to the EDT-owned editor session. */
final class GpuBoardEditor implements com.badlogic.gdx.utils.Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardEditor.class);
    static final float LIBRARY_WIDTH = 260, INSPECTOR_WIDTH = 280, TOOLBAR_HEIGHT = 34;
    /**
     * The corner panels over the board area: the Brush panel at most this wide (its controls wrap inside it), the side
     * view this wide when open, and its one-line bar at most this wide when collapsed. When the board area is narrower
     * than an open side view beside a full Brush panel, the side view starts collapsed.
     */
    private static final float BRUSH_WIDTH = 340, SIDE_WIDTH = 380, SIDE_BAR_WIDTH = 300;
    /** The smallest Layers or Edit region of the right column. */
    private static final float SPLIT_MIN = 60;
    /** Layers take their natural height up to this share of the right column until the divider is dragged. */
    private static final float SPLIT_NATURAL = .45f;
    private static final int PAGE_SIZE = 18;
    /** The prefixes of the Layers node ids: a component's row, an object's row and a group's row. */
    private static final String COMPONENT_ROW = "editor-component-", OBJECT_ROW = "editor-content-", GROUP_ROW = "editor-group-";
    /**
     * Layers type icons, one table: components by id, objects by their asset's first palette section, else by its
     * category (see {@link #layerIcon}); "vegetation" is both the component and the category.
     */
    private static final Map<String, String> LAYER_ICONS = Map.ofEntries(
          Map.entry("ground", "hex"), Map.entry("pavement", "hex"), Map.entry("water", "water"), Map.entry("vegetation", "tree"),
          Map.entry("road", "road"), Map.entry("building", "building"), Map.entry("bridge", "bridge"),
          Map.entry("fuelTank", "fuel-tank"), Map.entry("industry", "factory"),
          Map.entry("transportation/maglev-stock", "train"), Map.entry("transportation/rail-stock", "train"),
          Map.entry("transportation/rail-maglev", "train"), Map.entry("transportation/airfields", "star"),
          Map.entry("transportation", "car"), Map.entry("props/animals", "animal"));
    /** The Assets panel's page: the start page (no category), a category's or subcategory's sections, or a gallery card's designs. */
    private record Page(String category, String subcategory, String gallery) { }
    private static final Page START = new Page(null, null, null);
    /** The page last shown, where the panel reopens when the editor is built again in this session. */
    private static Page lastPage = START;
    /** A grid card: a blueprint card (an entry, a picker family or a gallery), the Theme card of {@code theme}, or the one that opens {@code subcategory}. */
    private record Tile(BoardEditorBlueprint.Card card, String theme, String subcategory) {
        Tile(BoardEditorBlueprint.Card card, String theme) { this(card, theme, null); }
    }
    /** A grid section: the end of its header's name, its caption (null: no header) and its cards. */
    private record Shelf(String id, String title, List<Tile> tiles) { }
    /** A grid section's header: an upper-case caption over a hairline. */
    private static final float SHELF_HEIGHT = 30;
    /** A tool's bare key (positional, US-named) and icon; the session's Tool stays a plain enum. */
    private record ToolStyle(int key, String icon) {
        String keyText() { return Input.Keys.toString(key); }
    }
    private static final Map<BoardEditorSession.Tool, ToolStyle> TOOLS = new EnumMap<>(Map.of(
          BoardEditorSession.Tool.SELECT, new ToolStyle(Input.Keys.Z, "tool-select"),
          BoardEditorSession.Tool.SCULPT, new ToolStyle(Input.Keys.X, "tool-sculpt"),
          BoardEditorSession.Tool.PAINT, new ToolStyle(Input.Keys.C, "tool-paint"),
          BoardEditorSession.Tool.ERASE, new ToolStyle(Input.Keys.V, "tool-erase")));
    /** The issues triangle's plain red: the delete icon's hover red. */
    private static final com.badlogic.gdx.graphics.Color ISSUE_RED = com.badlogic.gdx.graphics.Color.valueOf("FF554C");
    private static final float ISSUE_ICON = 24;
    /** How long something must load before the spinner shows, and stay done before it goes: quick work never flashes it. */
    private static final float PREPARING_SHOW = .3f, PREPARING_HIDE = .25f;
    /** Issue rows the dropdown lists; a longer list ends with a count of the rest. */
    private static final int ISSUE_ROWS = 200;
    /** Camera binds as Help lists them: those the board view's camera handles in the editor too. */
    private static final List<KeyCommandBind> HELP_CAMERA = List.of(KeyCommandBind.SCROLL_NORTH, KeyCommandBind.SCROLL_WEST,
          KeyCommandBind.SCROLL_SOUTH, KeyCommandBind.SCROLL_EAST, KeyCommandBind.CAMERA_ROTATE_LEFT,
          KeyCommandBind.CAMERA_ROTATE_RIGHT, KeyCommandBind.CAMERA_TILT_UP, KeyCommandBind.CAMERA_TILT_DOWN,
          KeyCommandBind.ZOOM_IN, KeyCommandBind.ZOOM_OUT, KeyCommandBind.CAMERA_RESET, KeyCommandBind.CAMERA_FIT_BOARD,
          KeyCommandBind.TOGGLE_ISO);
    /** Mouse gestures as Help lists them: the message keys of each gesture and of what it does. */
    private static final List<List<String>> HELP_MOUSE = List.of(
          List.of("GpuBoard.hud.mouse.leftClick", "BoardEditor.help.click"),
          List.of("BoardEditor.help.mouse.drag", "BoardEditor.help.drag"),
          List.of("GpuBoard.hud.mouse.shiftClick", "BoardEditor.help.shiftClick"),
          List.of("BoardEditor.help.mouse.shiftDrag", "BoardEditor.help.shiftDrag"),
          List.of("GpuBoard.hud.mouse.ctrlClick", "BoardEditor.help.ctrlClick"),
          List.of("BoardEditor.help.mouse.ctrlDrag", "BoardEditor.help.ctrlDrag"),
          List.of("BoardEditor.help.mouse.ctrlWheel", "BoardEditor.help.ctrlWheel"),
          List.of("BoardEditor.help.mouse.brushPicker", "BoardEditor.help.brushPicker"),
          List.of("GpuBoard.hud.mouse.altClick", "GpuBoard.hud.help.altClick"),
          List.of("GpuBoard.hud.mouse.rightDrag", "GpuBoard.hud.hint.pan"),
          List.of("GpuBoard.hud.mouse.orbitDrag", "GpuBoard.hud.help.orbit"),
          List.of("GpuBoard.hud.mouse.wheel", "GpuBoard.hud.help.wheel"),
          List.of("GpuBoard.hud.mouse.shortRightClick", "GpuBoard.hud.help.shortRightClick"));
    private final BoardSource source;
    private final UiKit ui;
    private final Stage stage;
    private final Supplier<GpuTerrain> terrain;
    private final BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
    private final Table toolbar, library, inspector, detail = new Table();
    /**
     * The Assets panel: its header (the title on the start page; else back and the category's drop-down, or the
     * gallery's name), the start page's category tiles, built once and only hidden while another page shows, so their
     * previews stay cached, and each tile's mark of the active brush's category.
     */
    private final Table libraryHead = new Table(), tiles = new Table();
    private final Map<String, Actor> categoryMarks = new LinkedHashMap<>();
    private Page page = lastPage;
    /** The category name's drop-down face on a category page, under which the category list opens. */
    private UiButton categoryFace;
    /** The right column: Layers above Edit, each with its own scroll, and a draggable divider between them. */
    private final SplitPane split;
    /** The divider's share as the user last dragged it, or negative while Layers take their natural height. */
    private float userSplit = GUIPreferences.getInstance().getBoardEditorLayersSplit();
    /** The side view in the board area's bottom-right corner: a header that collapses it, over the hex section. */
    private final Table sideView;
    private final Label sideSummary;
    private final UiButton sideToggle;
    private final com.badlogic.gdx.scenes.scene2d.ui.Cell<Table> sideBody;
    /** The section's Reveal toggle, in the header while the side view is open. */
    private final UiButton sectionReveal;
    private final com.badlogic.gdx.scenes.scene2d.ui.Cell<UiButton> sideRevealCell;
    /** The direction the open header names ("looking N"); it follows the camera's yaw. */
    private String sideLooking = "";
    /**
     * The item the pointer is over in Layers, and on the board in the inspected hex: a {@link BoardEditorSession.Level}
     * key. With the side view's own hover it is highlighted in all three places; see {@link #hovered()}.
     */
    private String layersHover = "", boardHover = "", shownHover;
    /** The items of the shown hover (a shared pill stands for several), and those of them the 3D view outlines. */
    private List<String> shownHovers = List.of(), outlined = List.of();
    private boolean sideOpen;
    /** Shown only while the document has issues; a click lists them, and an issue frames and selects its hex. */
    private final UiKit.Icon issues;
    /** Turns beside the issues triangle while something asked for still loads, which its tip names. */
    private final UiKit.Icon preparing;
    private final com.badlogic.gdx.scenes.scene2d.ui.TextTooltip preparingTip;
    private int skippedDraws;
    private float preparingFor, preparedFor;
    private final com.badlogic.gdx.scenes.scene2d.ui.TextTooltip issuesTip;
    /** The open issue list, kept current while it is shown. */
    private UiMenuList issueMenu;
    private final Consumer<Coords> frame;
    /** An issue's hex waiting to be framed until its selection has applied, and when that wait ends (nanoTime). */
    private megamek.common.board.Coords pendingFrame;
    private long pendingFrameUntil;
    private List<HexIssue> shownIssues, sessionIssues;
    /** The installed fit's objects inside the walls of Fitted pavement hexes; listed after the session's issues. */
    private List<BoardConcrete.Cut> shownCuts = List.of();
    private final Label status, documentName;
    private final Table documentStatus = new Table();
    private final ScrollPane inspectorScroll, layersScroll, libraryScroll, tilesScroll;
    private final UiKit.SearchField librarySearch, themeFind;
    private TextField searchField;
    private final AssetManager thumbnails = new AssetManager();
    private final GpuEditorModelPreviews models = new GpuEditorModelPreviews();
    /** The junction card's and gallery's layouts; the library clears its own queue whenever it changes. */
    private final GpuEditorModelPreviews junctions = new GpuEditorModelPreviews();
    private final GpuEditorTerrainPreviews samples = new GpuEditorTerrainPreviews();
    private record Preview(Image image, String file) { }
    /** A card's "Preview…" label, shown until its preview image has a drawable. */
    private record Loading(Image image, Label label) { }
    private final List<Preview> previews = new ArrayList<>();
    private final List<Loading> loading = new ArrayList<>();
    private final List<UiButton> tools = new ArrayList<>();
    private final UiButton undo, redo;
    /**
     * Layers: the inspected hex's components with the objects on them, the other objects, and its groups with their
     * members. Node ids are the rows' actor names: {@link #COMPONENT_ROW}, {@link #OBJECT_ROW} or {@link #GROUP_ROW}
     * and the item's id. What a row or its action does, by node id or action name, as of the shown rows.
     */
    private final UiTree contents;
    private final Map<String, Runnable> layerCommands = new java.util.HashMap<>();
    /** The hex Layers show; another hex starts with every row expanded. */
    private Coords layersHex;
    private final Table sectionPanel, settingsLayer = new Table(), settingsBody = new Table();
    private final Table themeLayer = new Table(), themeGallery;
    private final UiChoiceGrid themeCards;
    private final UiCardGrid libraryGrid = new UiCardGrid(2, 116, SHELF_HEIGHT, 6);
    private final ScrollPane themeScroll;
    private final TextField themeSearch;
    private Consumer<String> themeCommit;
    private String selectedTheme = "", themeQuery = "", libraryTheme = "";
    private final List<UiButton> libraryCards = new ArrayList<>();
    private final Label hexTitle;
    private final UiPopover choices;
    private final GpuHexSection section;
    private final List<UiNumber> numbers = new ArrayList<>(), settingsNumbers = new ArrayList<>();
    private int newWidth = 16, newHeight = 17;
    /**
     * The Settings dialog's status line under its sections: the session's message whenever it changes while the dialog
     * shows (a whole-board command's result, a refused tag), and the message it last showed, null to show the next one.
     */
    private final Label settingsResult;
    private String settingsMessage;
    /** The Settings dialog's Map section, and the board settings it shows (tags and road option), to rebuild it on change. */
    private final Table mapSettings = new Table();
    private List<String> mapState = List.of();
    /** Asks before Clear Board empties every hex. */
    private final megamek.client.ui.gdx.UiConfirmation clearConfirm;
    /** The Settings dialog's scroll, which the Menu's New scrolls to the Size section. */
    private final ScrollPane settingsScroll;
    /** Menu → Help: the editor's keys and gestures ({@link #openHelp}). */
    private final Table helpLayer = new Table(), helpBody = new Table();
    private final Table brushPanel = new Table();
    private final List<UiNumber> brushNumbers = new ArrayList<>();
    private final List<List<Actor>> brushRows = new ArrayList<>();
    private float brushLayoutWidth = -1;
    /** What the Brush panel shows: the tool, its radius and the active brush, compared by value on a new snapshot. */
    private record BrushState(BoardEditorSession.Tool tool, int radius, BoardEditorSession.Brush brush) { }
    private BrushState brushState, nextBrush;
    /** The Sculpt tray's hovered-hex hint; null while another tool's tray is shown. */
    private Label sculptHint;
    private final Table designLayer = new Table(), designDialog;
    private final UiChoiceGrid designCards;
    /** The gallery's title: the design gallery's, or the junction card's ({@link #openJunctions}). */
    private final Label designHeading;
    private final ScrollPane designScroll;
    private final UiKit.SearchField designSearch;
    private final Table designPages = new Table();
    private String designOwner = "", designQuery = "";
    /** The gallery's cards while it shows the billboard art, else null. */
    private List<GalleryCard> billboardCards;
    private int designPage;
    private float width, height, utilityWidth, utilityHeight;
    private String shownSelection = "";
    private BoardEditorSession.Snapshot snapshot;
    private long generation;
    /** The search, lower case: while it holds text, the grid shows its results over every category. */
    private String query = "";
    /**
     * What Layers and Edit show, compared by value when a new snapshot or a newly installed anchor arrives; they are
     * rebuilt only when it changed and no edit, list drag, open choice or board drag is in progress.
     */
    private record DetailState(long revision, Coords selected, String component,
          List<BoardEditorSession.Property> properties, Map<String, ?> appearance, List<BoardDecoration> objects,
          String object, int elevation, String theme, List<Anchor> anchors) { }
    private record Anchor(String id, float level) { }
    private DetailState detailState, nextDetail;
    /** A press on the board until its button is released: Layers and Edit wait for its end. */
    private boolean boardGesture;
    /** One render-thread snapshot of installed placement geometry, shared by the section and its labels. */
    private List<GpuTerrain.EditorObject> installedObjects = List.of();
    /** The terrain {@link #installedObjects} were read from, and whether it was building last frame. */
    private GpuTerrain installedFrom;
    private boolean terrainBuilding;
    /** The terrain and the outlined objects that {@link GpuTerrain#keepShown} last received. */
    private GpuTerrain pinnedBoard;
    private List<String> pinnedOutlined;
    /** Reads of the installed objects so far; an idle editor makes none. */
    int installedReads;

    /** {@code frame} turns the camera to a hex, as an issue click does; the side view looks along {@code azimuth}. */
    GpuBoardEditor(BoardSource source, UiKit ui, Stage stage, Supplier<GpuTerrain> terrain,
          Consumer<Coords> frame, java.util.function.DoubleSupplier azimuth) {
        this.source = source; this.ui = ui; this.stage = stage; this.terrain = terrain; this.frame = frame;
        themeCards = ui.choiceGrid(148, 6);
        choices = new UiPopover(ui); stage.addActor(choices);
        thumbnails.setErrorListener((asset, failure) -> {
            LOGGER.warn(failure, "No editor preview from {}", asset.fileName);
            previews.stream().filter(preview -> preview.file().equals(asset.fileName))
                  .forEach(preview -> preview.image().setUserObject(UiKit.text("BoardEditor.noPreview")));
        });
        toolbar = ui.panel(); toolbar.setName("editor-toolbar"); toolbar.pad(4);
        for (BoardEditorSession.Tool tool : BoardEditorSession.Tool.values()) {
            ToolStyle style = TOOLS.get(tool);
            String label = toolName(tool);
            UiButton button = button(style.icon(), label, () -> send(Action.TOOL, tool.name()));
            Label key = ui.label(style.keyText(), "hud-small", 10, UiTheme.MUTED);
            button.details.add(key); button.add(key).padLeft(5);
            ui.tip(button).getActor().setText(UiKit.text("BoardEditor.toolTip", label, style.keyText())
                  + (tool == BoardEditorSession.Tool.SELECT ? " · " + UiKit.text("BoardEditor.selectHint") : ""));
            button.setName("editor-tool-" + tool.name().toLowerCase(Locale.ROOT));
            toolbar.add(button).padRight(3); tools.add(button);
        }
        undo = button(UiKit.text("CommonMenuBar.boardUndo"), () -> send(Action.UNDO));
        redo = button(UiKit.text("CommonMenuBar.boardRedo"), () -> send(Action.REDO));
        toolbar.add(undo).padLeft(8); toolbar.add(redo).padLeft(3);
        toolbar.add(button(UiKit.text("BoardEditor.butBoardOpen"), () -> send(Action.OPEN))).padLeft(8);
        toolbar.add(button(UiKit.text("BoardEditor.butBoardSave"), () -> send(Action.SAVE))).padLeft(3);
        toolbar.add(button(UiKit.text("BoardEditor.butBoardSaveAs"), () -> send(Action.SAVE_AS))).padLeft(3);
        stage.addActor(toolbar);

        // Assets: a full-height column with its page's header, one search over every category, and the start page's
        // category tiles or the page's sections of cards in a vertical two-column grid.
        library = ui.panel(); library.setName("editor-library"); library.defaults().growX().minWidth(0);
        library.add(libraryHead).row();
        librarySearch = ui.search(UiKit.text("BoardEditor.searchAll")); searchField = librarySearch.field; searchField.setName("editor-search");
        onChange(searchField, () -> {
            String next = searchField.getText().trim().toLowerCase(Locale.ROOT);
            if (next.equals(query)) { return; }
            boolean header = next.isEmpty() != query.isEmpty();
            query = next;
            if (header) { showHead(); }
            showGrid(false);
        });
        // A focused field takes every key: Esc clears its text, and a second Esc leaves the field.
        searchField.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int key) {
                if (key != Input.Keys.ESCAPE) { return false; }
                if (searchField.getText().isEmpty()) { stage.setKeyboardFocus(null); } else { searchField.setText(""); }
                return true;
            }
        });
        library.add(librarySearch).pad(0, 10, 6, 10).row();
        // A button's least width is its preferred width (Button): the two columns take half the width each instead.
        tiles.top(); tiles.defaults().growX().uniformX().minWidth(0).height(98).padBottom(6);
        for (var category : blueprint.categories()) {
            Image icon = new Image();
            // The Theme tile shows its theme's sample; the others render their icon hex as a hex stamp.
            if (category.themes() != null) { samples.add(category.icon().getTheme(), "", "", icon); }
            else { samples.add(BoardEditorSession.hexStamp("icon/" + category.id(), category.icon()), icon); }
            String id = category.id();
            UiButton tile = visualCard(categoryName(id), icon, 66, () -> show(new Page(id, null, null)));
            tile.setName("editor-category-" + id);
            Image mark = new Image(ui.skin.newDrawable("white", UiTheme.MINT)); mark.setVisible(false);
            mark.setName("editor-category-mark-" + id); categoryMarks.put(id, mark);
            overlay(tile, mark, 7, com.badlogic.gdx.utils.Align.topRight);
            boolean left = categoryMarks.size() % 2 == 1;
            tiles.add(tile).padRight(left ? 3 : 0).padLeft(left ? 0 : 3);
            if (!left) { tiles.row(); }
        }
        tilesScroll = ui.scrollList(tiles); tilesScroll.setName("editor-category-scroll");
        libraryScroll = ui.scrollList(libraryGrid); libraryScroll.setName("editor-choice-scroll");
        libraryScroll.setFadeScrollBars(false);
        // The bar beside the cards, not over the right column's edge.
        tilesScroll.setScrollbarsOnTop(false); libraryScroll.setScrollbarsOnTop(false);
        library.add(new Stack(tilesScroll, libraryScroll)).grow().minSize(0).pad(0, 8, 8, 4);
        stage.addActor(library);
        // Brush: the bottom-left corner of the board area, for the tools with options (Paint, Sculpt, Erase).
        ui.panel(brushPanel); brushPanel.pad(6, 8, 6, 8); brushPanel.setName("editor-brush"); stage.addActor(brushPanel);

        // The right column: the hex title and Contents tools over Layers and Edit, split by a draggable divider.
        inspector = ui.panel(); inspector.setName("editor-inspector"); inspector.defaults().growX().minWidth(0);
        inspector.setVisible(false);
        hexTitle = ui.label(UiKit.text("BoardEditor.hexInspector"), "hud-title", 16, UiTheme.TEXT);
        inspector.add(hexTitle).pad(12, 12, 6, 12).row();
        Table contentsHead = new Table(); contentsHead.add(ui.caption(UiKit.text("BoardEditor.layers"))).growX().left();
        contentsHead.add(button(UiKit.text("BoardEditor.copyButton"), () -> send(Action.COPY)));
        contentsHead.add(button(UiKit.text("BoardEditor.pasteButton"), () -> send(Action.PASTE))).padLeft(3);
        inspector.add(contentsHead).pad(4, 10, 4, 10).row();
        contents = new UiTree(ui); contents.setName("editor-contents");
        contents.empty(UiKit.text("BoardEditor.layersEmpty"));
        contents.onSelect(this::layerCommand);
        contents.onAction((row, action) -> layerCommand(action));
        // Hovering a component's or an object's row highlights its item in the side view and the 3D view.
        contents.onHover(row -> layersHover = row.startsWith(OBJECT_ROW) ? row.substring(OBJECT_ROW.length())
              : row.startsWith(COMPONENT_ROW) ? row.substring(COMPONENT_ROW.length()) : "");
        contents.reorderable(UiKit.text("BoardEditor.layersReorder"),
              (moved, target, before) -> send(Action.REORDER_OBJECT, moved.substring(OBJECT_ROW.length()),
                    (before ? "before:" : "after:") + target.substring(OBJECT_ROW.length())));
        Table layers = new Table(); layers.top(); layers.add(contents).growX().minWidth(0).pad(0, 6, 6, 6);
        layersScroll = ui.scrollList(layers); layersScroll.setName("editor-layers-scroll");
        detail.setName("editor-details");
        inspectorScroll = ui.scrollList(detail); inspectorScroll.setName("editor-inspector-scroll");
        split = new SplitPane(layersScroll, inspectorScroll, true, ui.skin, "hud-split"); split.setName("editor-split");
        split.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                // The divider lies between Edit's top and Layers' bottom; follow only a drag that starts there.
                return event.getTarget() == split && y >= inspectorScroll.getTop() - 1 && y <= layersScroll.getY() + 1;
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                // Remember where the user left the divider.
                userSplit = split.getSplitAmount(); float value = userSplit;
                javax.swing.SwingUtilities.invokeLater(() -> GUIPreferences.getInstance().setBoardEditorLayersSplit(value));
            }
        });
        inspector.add(split).grow().minSize(0);
        documentName = ui.label("", "hud-small", 12, UiTheme.TEXT); documentName.setEllipsis(true);
        documentName.setName("editor-document-name");
        status = ui.label("", "hud-small", 10, UiTheme.MUTED); status.setEllipsis(true);
        documentStatus.setName("editor-document-status"); documentStatus.setTouchable(Touchable.disabled);
        documentStatus.pad(3, 0, 3, 0);
        documentStatus.add(documentName).growX().minWidth(0).left().row();
        documentStatus.add(status).growX().minWidth(0).left();
        stage.addActor(inspector);
        stage.addActor(documentStatus);
        issues = ui.icon("warn", ISSUE_ICON, ISSUE_RED); issues.setName("editor-issues"); issues.setVisible(false);
        issues.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { showIssues(); }
        });
        issuesTip = ui.tip(issues);
        stage.addActor(issues);
        preparing = ui.icon("progress", ISSUE_ICON, UiTheme.MUTED); preparing.setName("editor-preparing"); preparing.setVisible(false);
        preparing.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.forever(
              com.badlogic.gdx.scenes.scene2d.actions.Actions.rotateBy(-360, 1)));
        preparingTip = ui.tip(preparing);
        stage.addActor(preparing);
        section = new GpuHexSection(source, terrain, ui, azimuth, this::objectLabel);
        sectionPanel = new Table(); sectionPanel.setName("editor-section-panel");
        sectionPanel.add(section).growX().minWidth(0).height(section.getPrefHeight());
        sectionReveal = button(UiKit.text("BoardEditor.reveal"), () -> { });
        sectionReveal.setName("editor-section-reveal");
        onChange(sectionReveal, () -> { sectionReveal.pressed(!sectionReveal.isChecked()); section.reveal(sectionReveal.isChecked()); });
        ui.tip(sectionReveal).getActor().setText(UiKit.text("BoardEditor.reveal.tooltip"));
        // Side view: the bottom-right corner of the board area; its header collapses it to a one-line summary.
        sideView = ui.panel(); sideView.setName("editor-side-view"); sideView.pad(4, 8, 6, 8); sideView.defaults().growX().minWidth(0);
        sideToggle = ui.button("hud-mini", "chevron-down", "", null); sideToggle.setName("editor-side-view-toggle");
        sideToggle.clearChildren(); sideToggle.pad(0); sideToggle.icons.forEach(icon -> sideToggle.add(icon).size(18));
        onChange(sideToggle, () -> { stage.setKeyboardFocus(null); openSideView(!sideOpen); });
        ui.tip(sideToggle).getActor().setText(UiKit.text("BoardEditor.sideView.tooltip"));
        sideSummary = ui.label("", "hud-small", 11.5f, UiTheme.MUTED); sideSummary.setEllipsis(true);
        sideSummary.setName("editor-side-view-summary");
        Table sideHeader = new Table(); sideHeader.add(ui.caption(UiKit.text("BoardEditor.hexSection"))).left().padRight(8);
        sideHeader.add(sideSummary).growX().minWidth(0).left();
        sideRevealCell = sideHeader.add(sectionReveal).height(22).padLeft(6);
        sideHeader.add(sideToggle).size(24).padLeft(6);
        sideView.add(sideHeader).height(26).row();
        sideBody = sideView.add(sectionPanel);
        stage.addActor(sideView);
        openSideView(true);
        designLayer.setFillParent(true); designLayer.setName("editor-design-gallery");
        designLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .85f)));
        designLayer.setTouchable(Touchable.enabled); designLayer.setVisible(false);
        designDialog = ui.dialog(UiKit.text("BoardEditor.designTitle"), this::closeDesigns);
        designHeading = (Label) ((Table) designDialog.getCells().first().getActor()).getCells().first().getActor();
        designDialog.setBackground(ui.skin.getDrawable("panel-pop"));
        designSearch = ui.search(UiKit.text("BoardEditor.findDesign"));
        onChange(designSearch.field, () -> { designQuery = designSearch.field.getText().toLowerCase(Locale.ROOT); designPage = 0; showDesigns(); });
        designDialog.add(designSearch).growX().row();
        designCards = ui.choiceGrid(140, 6); designScroll = ui.scrollList(designCards);
        designDialog.add(designScroll).growX().row(); designDialog.add(designPages).growX().row();
        designLayer.add(designDialog); stage.addActor(designLayer);
        settingsLayer.setFillParent(true); settingsLayer.setName("editor-settings");
        settingsLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .75f)));
        settingsLayer.setTouchable(Touchable.enabled); settingsLayer.setVisible(false);
        Table dialog = ui.dialog(UiKit.text("BoardEditor.settings"), this::closeSettings); dialog.setBackground(ui.skin.getDrawable("panel-pop"));
        settingsScroll = ui.scrollList(settingsBody); settingsScroll.setName("editor-settings-scroll");
        dialog.add(settingsScroll).growX().maxHeight(480).row();
        settingsResult = ui.label("", "hud-small", 11.5f, UiTheme.MUTED); settingsResult.setWrap(true);
        settingsResult.setName("editor-settings-result");
        dialog.add(settingsResult).growX().minWidth(0).padTop(6);
        settingsLayer.add(dialog).width(480); stage.addActor(settingsLayer);
        clearConfirm = new megamek.client.ui.gdx.UiConfirmation(ui, "editor-clear-confirm", () -> { });
        stage.addActor(clearConfirm.actor());
        helpLayer.setFillParent(true); helpLayer.setName("editor-help");
        helpLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .75f)));
        helpLayer.setTouchable(Touchable.enabled); helpLayer.setVisible(false);
        Table help = ui.dialog(UiKit.text("BoardEditor.help.title"), this::closeHelp);
        help.setBackground(ui.skin.getDrawable("panel-pop"));
        ScrollPane helpScroll = ui.scrollList(helpBody); helpScroll.setName("editor-help-scroll"); helpScroll.setFadeScrollBars(false);
        help.add(helpScroll).growX().maxHeight(520);
        helpLayer.add(help).width(600); stage.addActor(helpLayer);
        themeLayer.setFillParent(true); themeLayer.setName("editor-theme-gallery");
        themeLayer.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.PANEL, .85f)));
        themeLayer.setTouchable(Touchable.enabled); themeLayer.setVisible(false);
        themeGallery = ui.dialog(UiKit.text("BoardEditor.themeTitle"), this::closeThemes);
        themeGallery.setBackground(ui.skin.getDrawable("panel-pop"));
        themeFind = ui.search(UiKit.text("BoardEditor.findTheme")); themeSearch = themeFind.field; themeSearch.setName("editor-theme-search");
        onChange(themeSearch, () -> { themeQuery = themeSearch.getText().toLowerCase(Locale.ROOT); showThemes(); });
        themeGallery.add(themeFind).growX().row();
        themeScroll = ui.scrollList(themeCards); themeScroll.setName("editor-theme-scroll");
        themeGallery.add(themeScroll).growX().pad(0, 6, 0, 6).row();
        themeLayer.add(themeGallery); stage.addActor(themeLayer);
        showPage();
    }

    /** A tool's name as the toolbar, the Brush panel and the context menu show it. */
    private static String toolName(BoardEditorSession.Tool tool) {
        return UiKit.text("BoardEditor.tool." + tool.name().toLowerCase(Locale.ROOT));
    }

    /** A palette category's name. */
    private static String categoryName(String category) { return UiKit.text("BoardEditor.palette." + category); }

    private UiButton button(String label, Runnable action) { return button(null, label, action); }
    private UiButton button(String icon, String label, Runnable action) {
        UiButton button = ui.button("hud-mini", icon, label, null);
        onChange(button, () -> { stage.setKeyboardFocus(null); action.run(); }); return button;
    }
    private UiButton removeButton(String label, Runnable action) {
        UiButton button = ui.button(label.isEmpty() ? "hud-delete-icon" : "hud-danger", label.isEmpty() ? "close" : null, UiTheme.upper(label), null);
        if (label.isEmpty()) { button.pad(2); }
        onChange(button, () -> { stage.setKeyboardFocus(null); action.run(); });
        return UiKit.ownEvents(button);
    }

    private void send(Action action) { send(action, "", ""); }
    private void send(Action action, String value) { send(action, "", value); }
    private void send(Action action, String target, String value) { source.editorCommand(new Command(action, target, value), generation); }

    void contextTools(UiMenuList menu, Runnable close) {
        for (var tool : BoardEditorSession.Tool.values()) {
            menu.item(UiTheme.upper(toolName(tool)), TOOLS.get(tool).keyText(), snapshot != null && snapshot.tool() == tool, false, true,
                  () -> { close.run(); send(Action.TOOL, tool.name()); }).setName("editor-context-" + tool.name().toLowerCase(Locale.ROOT));
        }
        menu.separator();
    }

    void contextActions(UiMenuList menu, Runnable close, Runnable settings) {
        menu.separator();
        menu.item(UiKit.text("CommonMenuBar.boardUndo"), UiKit.text("BoardEditor.shortcut.undo"), null, false,
              snapshot != null && snapshot.canUndo(), () -> { close.run(); send(Action.UNDO); }).setName("editor-context-undo");
        menu.item(UiKit.text("CommonMenuBar.boardRedo"), UiKit.text("BoardEditor.shortcut.redo"), null, false,
              snapshot != null && snapshot.canRedo(),
              () -> { close.run(); send(Action.REDO); }).setName("editor-context-redo");
        menu.separator();
        // The right-click does not change the selection, so both act on what is selected.
        boolean group = snapshot != null && snapshot.group().isEmpty()
              && snapshot.selection().stream().filter(item -> !item.object().isEmpty()).count() > 1;
        menu.item(UiKit.text("BoardEditor.group"), UiKit.text("BoardEditor.shortcut.group"), null, false, group,
              () -> { close.run(); send(Action.GROUP); }).setName("editor-context-group");
        menu.item(UiKit.text("BoardEditor.ungroup"), UiKit.text("BoardEditor.shortcut.ungroup"), null, false,
              snapshot != null && snapshot.groups().stream().anyMatch(g -> !g.isEmpty()),
              () -> { close.run(); send(Action.UNGROUP); }).setName("editor-context-ungroup");
        menu.separator();
        menu.item(UiKit.text("BoardEditor.sideView"), "", sideOpen, false, true,
              () -> { close.run(); openSideView(!sideOpen); }).setName("editor-context-side-view");
        menu.item(UiKit.text("BoardEditor.settings"), "", null, false, true,
              () -> { close.run(); settings.run(); }).setName("editor-context-settings");
    }

    void layout(float width, float height, float utilityWidth, float utilityHeight) {
        this.width = width; this.height = height; this.utilityWidth = utilityWidth; this.utilityHeight = utilityHeight;
        float toolbarWidth = toolbar.getPrefWidth();
        boolean twoRows = width < toolbarWidth + utilityWidth + 3 * GAP;
        float toolbarTop = twoRows ? utilityHeight + 2 * GAP : GAP;
        float top = twoRows ? toolbarTop + TOOLBAR_HEIGHT + GAP : Math.max(TOOLBAR_HEIGHT, utilityHeight) + 2 * GAP;
        toolbar.setBounds(GAP, height - toolbarTop - TOOLBAR_HEIGHT, toolbarWidth, TOOLBAR_HEIGHT);
        float statusLeft = toolbar.getRight() + GAP;
        float statusRight = twoRows ? width - GAP : width - utilityWidth - 2 * GAP;
        issues.setBounds(statusRight - ISSUE_ICON, toolbar.getY() + (TOOLBAR_HEIGHT - ISSUE_ICON) / 2, ISSUE_ICON, ISSUE_ICON);
        statusRight -= ISSUE_ICON + GAP;
        preparing.setBounds(statusRight - ISSUE_ICON, issues.getY(), ISSUE_ICON, ISSUE_ICON);
        preparing.setOrigin(ISSUE_ICON / 2, ISSUE_ICON / 2);
        statusRight -= ISSUE_ICON + GAP;
        documentStatus.setBounds(statusLeft, toolbar.getY(), Math.max(0, statusRight - statusLeft), TOOLBAR_HEIGHT);
        library.setBounds(GAP, GAP, LIBRARY_WIDTH, Math.max(120, height - top - GAP));
        inspector.setBounds(width - INSPECTOR_WIDTH - GAP, GAP, INSPECTOR_WIDTH, Math.max(120, height - top - GAP));
        inspector.validate();
        fitSplit();
        layoutCorners();
        if (designLayer.isVisible()) { layoutDesigns(); }
        layoutThemes();
        clearConfirm.resize(width, height);
    }

    private float boardAreaWidth() { return inspector.getX() - library.getRight() - 2 * GAP; }

    /**
     * The corner panels sized to their content: the side view right-aligned at its fixed width (its bar shrinks for the
     * Brush panel), the Brush panel left-aligned in what remains, wrapping its controls there, or above the side view
     * when what remains is too narrow.
     */
    private void layoutCorners() {
        float area = boardAreaWidth();
        float sideWidth = sideOpen ? Math.min(SIDE_WIDTH, area) : Math.min(SIDE_BAR_WIDTH, Math.max(160, area - BRUSH_WIDTH - GAP));
        sideView.setWidth(sideWidth); sideView.invalidate(); sideView.validate();
        sideView.setBounds(inspector.getX() - GAP - sideWidth, GAP, sideWidth, sideView.getPrefHeight());
        sideView.validate();
        // Too narrow for the Brush panel beside the side view: it stands above the side view instead of under it.
        float beside = area - sideWidth - GAP;
        if (beside < 160) { layoutBrush(Math.min(BRUSH_WIDTH, area), sideView.getTop() + GAP); }
        else { layoutBrush(Math.min(BRUSH_WIDTH, beside), GAP); }
    }

    /** Related controls share a row, not column widths with unrelated rows. Wrap whole controls on small viewports. */
    private void layoutBrush(float available, float bottom) {
        brushPanel.setVisible(!brushRows.isEmpty());
        if (brushLayoutWidth != available) {
            brushLayoutWidth = available;
            float inner = available - 16;
            brushPanel.clearChildren(); brushPanel.left(); brushPanel.defaults().left();
            for (List<Actor> controls : brushRows) {
                Table row = new Table(); row.left();
                float used = 0;
                for (Actor control : controls) {
                    if (control instanceof Label label && label.getWrap()) {
                        // A wrapped hint takes the panel's width on a row of its own.
                        if (used > 0) { brushPanel.add(row).pad(2).row(); row = new Table(); row.left(); used = 0; }
                        // Its wrapped height follows from its width, which must be known before the panel is measured.
                        label.setWidth(inner - 4); label.invalidate();
                        row.add(control).width(inner - 4); used = inner;
                        continue;
                    }
                    float preferred = control instanceof com.badlogic.gdx.scenes.scene2d.utils.Layout layout
                          ? layout.getPrefWidth() : control.getWidth();
                    float cellWidth = Math.min(preferred, inner - 4);
                    if (used > 0 && used + 8 + cellWidth > inner - 4) {
                        brushPanel.add(row).pad(2).row(); row = new Table(); row.left(); used = 0;
                    }
                    row.add(control).width(cellWidth).padLeft(used == 0 ? 0 : 8);
                    used += cellWidth + (used == 0 ? 0 : 8);
                }
                brushPanel.add(row).pad(2).row();
            }
        }
        brushPanel.invalidate();
        brushPanel.setBounds(library.getRight() + GAP, bottom, Math.min(available, brushPanel.getPrefWidth()), brushPanel.getPrefHeight());
        brushPanel.validate();
    }

    private void brushRow(Actor... controls) { brushRows.add(List.of(controls)); }

    /** Applies the complete side view state, both at startup and when toggled. */
    private void openSideView(boolean open) {
        sideOpen = open;
        sideBody.setActor(open ? sectionPanel : null);
        sideRevealCell.setActor(open ? sectionReveal : null);
        if (snapshot != null) { sideSummary.setText(sideSummary(snapshot)); }
        ((UiKit.Icon) sideToggle.icons.getFirst()).setDrawable(ui.skin.getDrawable(open ? "icon-chevron-down" : "icon-chevron-up"));
        if (width > 0) { layoutCorners(); }
    }

    /** The divider where the user left it, or Layers at their natural height up to their share of the column. */
    private void fitSplit() {
        float available = split.getHeight() - split.getStyle().handle.getMinHeight();
        if (available <= 2 * SPLIT_MIN) { return; }
        float least = SPLIT_MIN / available;
        split.setMinSplitAmount(least); split.setMaxSplitAmount(1 - least);
        float natural = (contents.getPrefHeight() + 6) / available;
        split.setSplitAmount(MathUtils.clamp(userSplit >= 0 ? userSplit : Math.min(natural, SPLIT_NATURAL), least, 1 - least));
    }

    private void layoutThemes() {
        ui.fitSearch(themeGallery.getCell(themeFind), false);
        ui.fitChoices(themeGallery, themeScroll, themeCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
        if (!themeFind.isVisible() && themeCards.getPrefHeight() > themeGallery.getCell(themeScroll).getPrefHeight() + .5f) {
            ui.fitSearch(themeGallery.getCell(themeFind), true);
            ui.fitChoices(themeGallery, themeScroll, themeCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
        }
    }
    /** The board area's clearance above the corner panels, which explicit framing keeps its target above. */
    float bottomInset() { return Math.max(brushPanel.isVisible() ? brushPanel.getTop() : 0, sideView.getTop()) + GAP; }
    float topInset() { return height - library.getTop(); }

    /** A press on the board: Layers and Edit keep their rows until its button is released. */
    void boardPress() { boardGesture = true; }

    /**
     * One frame. Work is driven by changes: a new snapshot (compared by identity, then by value), a newly installed
     * anchor, a finished preview or a released board drag. Nothing is rebuilt, laid out or keyed while they stay the same.
     */
    void update(long generation) {
        this.generation = generation;
        if (boardGesture && !com.badlogic.gdx.Gdx.input.isButtonPressed(Input.Buttons.LEFT)) { boardGesture = false; }
        var next = source.editorState();
        if (next == null) { return; }
        boolean changed = next != snapshot;
        snapshot = next;
        if (changed) { refresh(next); }
        if (sculptHint != null && !sculptHint.textEquals(source.editorHint())) { sculptHint.setText(source.editorHint()); }
        // Installed objects change only with a new snapshot or while the terrain builds; the frame after a build reads its result.
        GpuTerrain board = terrain.get();
        boolean building = board != null && board.busy();
        var installed = board == null ? List.<GpuTerrain.EditorObject>of() : installedObjects;
        if (board != null && (changed || building || terrainBuilding || board != installedFrom)) {
            installed = board.editorObjects(next.selected()); installedReads++;
        }
        terrainBuilding = building; installedFrom = board;
        var cuts = board == null ? List.<BoardConcrete.Cut>of() : board.concreteCuts();
        if (cuts != shownCuts) { shownCuts = cuts; issuesChanged(); }
        boolean placed = !sameAnchors(installed, installedObjects);
        installedObjects = installed;
        section.update(next, generation, installedObjects, placed);
        // The open header follows the camera's yaw; the hovered item is highlighted in Layers, the side view and 3D.
        if (sideOpen && next.selected() != null && !section.looking().equals(sideLooking)) { sideSummary.setText(sideSummary(next)); }
        String hover = hovered();
        boolean side = sideOpen && !section.hovered().isEmpty();
        // The side view's keys keep their identity until its hover changes.
        if (!hover.equals(shownHover) || side && section.hoveredKeys() != shownHovers) {
            shownHovers = side ? section.hoveredKeys() : hover.isEmpty() ? List.of() : List.of(hover);
            // The board's own hover is already under the pointer there; Layers and the side view outline theirs.
            outlined = !side && layersHover.isEmpty() ? List.of() : shownHovers.stream()
                  .filter(key -> next.objects().stream().anyMatch(object -> object.id().equals(key))).toList();
            // An item's Layers row: an object's, or a component's.
            contents.highlight(shownHovers.stream().map(key -> next.objects().stream().anyMatch(object -> object.id().equals(key))
                  ? OBJECT_ROW + key : COMPONENT_ROW + key).toList());
            section.highlight(hover); shownHover = hover;
        }
        // The selected objects and those hovered in Layers or the side view draw and pick at every zoom.
        if (board != null && (changed || board != pinnedBoard || outlined != pinnedOutlined)) {
            java.util.Set<String> ids = new java.util.HashSet<>(outlined);
            for (var item : next.selection()) { if (!item.object().isEmpty()) { ids.add(item.object()); } }
            board.keepShown(ids);
            pinnedBoard = board;
            pinnedOutlined = outlined;
        }
        if (changed || placed) {
            nextDetail = new DetailState(next.revision(), next.selected(), next.component(), next.properties(), next.appearance(),
                  next.objects(), next.object(), next.elevation(), next.theme(),
                  installedObjects.stream().map(object -> new Anchor(object.id(), object.anchorLevel())).toList());
        }
        boolean editing = stage.getKeyboardFocus() instanceof TextField;
        // Keep an in-progress numeric edit intact; a completed command also refreshes rejected values.
        if (nextBrush != null && !nextBrush.equals(brushState) && !editing && brushNumbers.stream().noneMatch(UiNumber::editing)) {
            brushState = nextBrush; showBrush();
        }
        // An issue's hex is framed after its selection switched to Select, so the insets are those of Select's panels.
        if (pendingFrame != null && (System.nanoTime() > pendingFrameUntil || pendingFrame.equals(next.selected())
              && next.tool() == BoardEditorSession.Tool.SELECT && java.util.Objects.equals(nextBrush, brushState))) {
            if (System.nanoTime() <= pendingFrameUntil) { frame.accept(pendingFrame); }
            pendingFrame = null;
        }
        if (nextDetail != null && !nextDetail.equals(detailState) && !editing && !boardGesture
              && numbers.stream().noneMatch(UiNumber::editing) && !contents.busy()
              && !section.dragging() && !choices.isVisible() && !themeLayer.isVisible() && !designLayer.isVisible()) {
            detailState = nextDetail; showContents(); showDetails();
            if (userSplit < 0) { fitSplit(); }
        }
        if (previews.removeIf(preview -> preview.image().getStage() == null)) {
            for (String file : thumbnails.getAssetNames()) {
                if (previews.stream().noneMatch(preview -> preview.file().equals(file))) { thumbnails.unload(file); }
            }
        }
        // Give document edits the GL upload budget before constructing more palette previews.
        if (terrain.get() == null || !terrain.get().busy()) {
            thumbnails.update(2);
            models.update();
            junctions.update();
            samples.update(terrain.get());
        }
        showPreparing();
        stage.getViewport().apply();
        for (Preview preview : previews) {
            if (!"Ready".equals(preview.image().getUserObject()) && thumbnails.isLoaded(preview.file(), Texture.class)) {
                preview.image().setDrawable(new TextureRegionDrawable(new TextureRegion(thumbnails.get(preview.file(), Texture.class))));
                preview.image().setUserObject("Ready");
            }
        }
        loading.removeIf(card -> {
            if (card.label().getStage() == null || card.image().getDrawable() != null) { card.label().setVisible(false); return true; }
            Object note = card.image().getUserObject();
            if (note != null && !card.label().textEquals(note.toString())) { card.label().setText(note.toString()); }
            return false;
        });
    }

    /** What the editor asked for and still waits for, as the draws, previews and edit themselves report it. */
    private void showPreparing() {
        int skipped = GpuShaderProvider.unlinkedDraws();
        boolean shaders = skipped != skippedDraws;
        skippedDraws = skipped;
        boolean edit = terrain.get() != null && terrain.get().editPending();
        int cards = samples.pending() + models.pending() + junctions.pending();
        float delta = com.badlogic.gdx.Gdx.graphics.getDeltaTime();
        if (shaders || edit || cards > 0) {
            preparingFor += delta;
            preparedFor = 0;
            var names = new java.util.ArrayList<String>();
            if (edit) { names.add(UiKit.text("BoardEditor.preparing.edit")); }
            if (shaders) { names.add(UiKit.text("BoardEditor.preparing.shaders")); }
            if (cards > 0) { names.add(UiKit.text("BoardEditor.preparing.previews", cards)); }
            preparingTip.getActor().setText(UiKit.text("BoardEditor.preparing", String.join(", ", names)));
        } else if ((preparedFor += delta) > PREPARING_HIDE) {
            preparingFor = 0;
        }
        preparing.setVisible(preparingFor > PREPARING_SHOW);
    }

    /** The hovered item as a level key of the inspected hex: the side view's, else a Layers row's, else the board's. */
    private String hovered() {
        String own = sideOpen ? section.hovered() : "";
        return !own.isEmpty() ? own : !layersHover.isEmpty() ? layersHover : boardHover;
    }

    /** The objects hovered in the side view (all of a shared pill's) or Layers, which the 3D view outlines. */
    List<String> hoveredObjects() { return outlined; }

    /** The object under the board pointer in the inspected hex, or "". */
    void boardHover(String object) { boardHover = object; }

    /** Whether two installed-object lists resolve the same objects at the same anchor levels. */
    private static boolean sameAnchors(List<GpuTerrain.EditorObject> a, List<GpuTerrain.EditorObject> b) {
        if (a.size() != b.size()) { return false; }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id().equals(b.get(i).id()) || a.get(i).anchorLevel() != b.get(i).anchorLevel()) { return false; }
        }
        return true;
    }

    /** Undo can keep an object inspected after clearing the movable selection; explicit deselection clears both. */
    private boolean inspecting() {
        return snapshot != null && snapshot.selected() != null && (!snapshot.selection().isEmpty() || !snapshot.object().isEmpty());
    }

    /** What a new snapshot changes outside Layers and Edit: toolbar, document line, issues, side view and cards. */
    private void refresh(BoardEditorSession.Snapshot next) {
        boolean visible = inspecting();
        if (!visible && inspector.isVisible()) { stage.unfocus(inspector); }
        inspector.setVisible(visible);
        // Cards preview in the theme the brush paints; the shown page keeps its place. A brush that does not apply its
        // theme (Water after a Theme card keeps that card's) leaves each hex its own, so its cards show the default.
        String theme = next.activeBrush().themeEnabled() ? next.activeBrush().theme() : "";
        if (!libraryTheme.equals(theme)) { libraryTheme = theme; showGrid(true); }
        nextBrush = new BrushState(next.tool(), next.brush(), next.activeBrush());
        for (int i = 0; i < tools.size(); i++) { tools.get(i).pressed(i == next.tool().ordinal()); }
        undo.setDisabled(!next.canUndo()); redo.setDisabled(!next.canRedo());
        if (next.issues() != sessionIssues) {
            // The session publishes a new list only when validation changed it.
            sessionIssues = next.issues();
            issuesChanged();
        }
        // Hexes are written by their board number everywhere in the editor: "0807".
        String coords = next.selected() == null ? "" : next.selected().getBoardNum();
        documentName.setText(next.title() + (coords.isEmpty() ? "" : " (" + coords + ")"));
        // The session's opening prompt stays out of the status line.
        String feedback = next.message().equals(UiKit.text("BoardEditor.selectHexToEdit")) ? "" : next.message();
        if (!status.textEquals(feedback)) {
            status.setText(feedback); status.setVisible(!feedback.isEmpty());
            documentStatus.getCell(status).height(feedback.isEmpty() ? 0 : status.getPrefHeight());
        }
        sideSummary.setText(sideSummary(next));
        if (settingsLayer.isVisible() && !next.message().equals(settingsMessage)) {
            settingsMessage = next.message(); settingsResult.setText(next.message());
        }
        // The Map section follows the board's settings while they show, so a chip or checkbox is its own undo.
        if (settingsLayer.isVisible() && !mapState.equals(next.tags())
              && !(stage.getKeyboardFocus() instanceof TextField field && field.isDescendantOf(mapSettings))) {
            showMapSettings(next);
        }
        libraryCards.removeIf(card -> card.getStage() == null);
        String active = next.activeBrush().key(), category = activeCategory(active);
        for (UiButton card : libraryCards) { card.pressed(((java.util.Set<?>) card.getUserObject()).contains(active)); }
        categoryMarks.forEach((id, mark) -> mark.setVisible(id.equals(category)));
    }

    /**
     * The side view's header line: while open the hex and the direction the section looks along ("0807 · looking N"),
     * collapsed the hex, its ground level and what stands on it.
     */
    private String sideSummary(BoardEditorSession.Snapshot next) {
        if (next.selected() == null) { return UiKit.text("BoardEditor.noHex"); }
        sideLooking = section.looking();
        if (sideOpen) { return next.selected().getBoardNum() + " · " + sideLooking; }
        int objects = next.objects().size();
        return next.selected().getBoardNum() + " · " + UiKit.text("BoardEditor.groundLevel", next.elevation())
              + (objects == 0 ? "" : " · " + UiKit.text("BoardEditor.objectCount", objects));
    }

    /** Shows {@code next}, remembered for the panel built next; the search is cleared. */
    private void show(Page next) {
        page = lastPage = next;
        // The search's own change event finds the query already empty.
        query = ""; searchField.setText("");
        showPage();
    }

    /**
     * One step back: from a gallery to the page that opened it, from a subcategory to its category, from a category to
     * the start page; false on the start page.
     */
    private boolean back() {
        if (page.category() == null) { return false; }
        show(page.gallery() != null ? new Page(page.category(), page.subcategory(), null)
              : page.subcategory() != null ? new Page(page.category(), null, null) : START);
        return true;
    }

    /** The shown page's header, then its grid. A page whose category, subcategory or gallery the blueprint no longer has is the start page. */
    private void showPage() {
        if (page.category() != null && (category(page.category()) == null || page.gallery() != null && gallery() == null
              || page.subcategory() != null && category(page.category()).sections(page.subcategory()).isEmpty())) {
            page = lastPage = START;
        }
        showHead();
        showGrid(false);
    }

    /**
     * The Assets header: the title on the start page; while the search holds text, back (which clears it) and "Search
     * results", since the results span every category; else back and the category's drop-down, or the subcategory's
     * or gallery's name.
     */
    private void showHead() {
        libraryHead.clearChildren(); categoryFace = null;
        boolean searching = !query.isEmpty();
        if (page.category() == null && !searching) {
            libraryHead.add(ui.header(UiKit.text("BoardEditor.assets"), null)).growX();
        } else {
            Table head = new Table(); head.pad(8, 8, 8, 14); head.left();
            UiButton previous = ui.button("hud-mini", "chevron-left", "", null); previous.setName("editor-back");
            previous.clearChildren(); previous.pad(0); previous.icons.forEach(icon -> previous.add(icon).size(18));
            onChange(previous, () -> { stage.setKeyboardFocus(null); escapeAssets(); });
            ui.tip(previous).getActor().setText(UiKit.text("BoardEditor.back"));
            head.add(previous).size(28);
            String name = searching ? UiKit.text("BoardEditor.searchResults")
                  : page.gallery() != null ? page.gallery()
                  : page.subcategory() != null ? sectionName(page.category(), page.subcategory()) : categoryName(page.category());
            Label title = new Label(UiTheme.upper(name), ui.skin, "hud-title");
            title.setEllipsis(true); title.setName("editor-library-title");
            if (searching || page.gallery() != null || page.subcategory() != null) { head.add(title).minWidth(0).padLeft(10); }
            else {
                // The category's name is a drop-down for a jump to another category.
                categoryFace = new UiButton(ui, "hud-icon"); categoryFace.setName("editor-category");
                categoryFace.add(title).minWidth(0).padLeft(6);
                var chevron = ui.icon("chevron-down", 14, UiTheme.MUTED); categoryFace.icons.add(chevron);
                categoryFace.add(chevron).padLeft(6).padRight(6);
                onChange(categoryFace, this::showCategories);
                head.add(categoryFace).minWidth(0).padLeft(4);
            }
            head.add().expandX();
            libraryHead.add(head).growX();
        }
    }

    private BoardEditorBlueprint.Category category(String id) {
        return blueprint.categories().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    /** The shown gallery's card, found by its family in the page's category; null if there is none. */
    private BoardEditorBlueprint.Card gallery() {
        return category(page.category()).sections().stream().flatMap(s -> s.cards().stream())
              .filter(card -> card.gallery() && card.label().equals(page.gallery())).findFirst().orElse(null);
    }

    /** Opens the category list under the category's name: every category, the shown one ticked; a choice opens it. */
    private void showCategories() {
        stage.setKeyboardFocus(null); numbers.forEach(UiNumber::dismiss);
        UiMenuList list = new UiMenuList(ui); list.setName("editor-category-list");
        for (var category : blueprint.categories()) {
            String id = category.id();
            list.item(categoryName(id), null, id.equals(page.category()), false, true, () -> { choices.cancel(); show(new Page(id, null, null)); })
                  .setName("editor-category-item-" + id);
        }
        choices.header(null, null).content(list).openedFrom(categoryFace);
        var corner = categoryFace.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        choices.showAt(corner.x, corner.y - 2);
    }

    /**
     * The grid of the shown page, or of the search's results while it holds text; the start page shows its tiles
     * instead. {@code keep}: the grid keeps its scroll (the same cards in another theme).
     */
    private void showGrid(boolean keep) {
        boolean start = page.category() == null && query.isEmpty();
        tilesScroll.setVisible(start); libraryScroll.setVisible(!start);
        float scroll = libraryScroll.getScrollY();
        libraryCards.clear(); models.clear();
        List<Shelf> shelves = start ? List.of() : query.isEmpty() ? shelves() : results();
        List<Tile> shown = new ArrayList<>();
        List<UiCardGrid.Section> sections = new ArrayList<>();
        for (Shelf shelf : shelves) {
            sections.add(new UiCardGrid.Section(shelf.title() == null ? null : shelfHeader(shelf), shelf.tiles().size()));
            shown.addAll(shelf.tiles());
        }
        if (!start && shown.isEmpty()) {
            Label none = ui.label(UiKit.text("BoardEditor.noResults"), "hud-small", 12, UiTheme.MUTED); none.setName("editor-no-results");
            sections.add(new UiCardGrid.Section(none, 0));
        }
        libraryGrid.items(sections, index -> card(shown.get(index)));
        libraryScroll.validate();
        libraryScroll.setScrollY(keep ? scroll : 0); libraryScroll.updateVisualScroll();
    }

    /** The shown page's sections: a gallery's designs, a subcategory's sections, or its category's subcategory cards and own sections. */
    private List<Shelf> shelves() {
        var category = category(page.category());
        if (page.gallery() != null) {
            return List.of(new Shelf(category.id(), null, gallery().members().stream().map(member -> new Tile(single(member), null)).toList()));
        }
        if (page.subcategory() != null) {
            var sections = category.sections(page.subcategory());
            // A lone section's caption would only repeat the page's name.
            return sections.stream().map(section -> sections.size() > 1 ? shelf(section) : new Shelf(section.path(), null, shelf(section).tiles())).toList();
        }
        if (category.themes() != null) { return shelves(category); }
        List<Shelf> shelves = new ArrayList<>();
        var subcategories = category.subcategories(null);
        if (!subcategories.isEmpty()) {
            shelves.add(new Shelf(category.id(), null, subcategories.stream().map(id -> new Tile(null, null, id)).toList()));
        }
        for (var section : category.sections(null)) {
            // A subcategory placed in a section ("Looks": Civic, Utopia, Frontier) leads that section's cards.
            Shelf shelf = shelf(section);
            List<Tile> tiles = new ArrayList<>(category.subcategories(section.id()).stream().map(id -> new Tile(null, null, id)).toList());
            tiles.addAll(shelf.tiles());
            shelves.add(new Shelf(shelf.id(), shelf.title(), tiles));
        }
        return shelves;
    }

    /** All of a category's sections, the subcategories' too; for the Theme category its themes and, apart, its special ones. */
    private List<Shelf> shelves(BoardEditorBlueprint.Category category) {
        if (category.themes() == null) { return category.sections().stream().map(GpuBoardEditor::shelf).toList(); }
        var special = category.themes();
        String key = "BoardEditor.palette." + category.id();
        return List.of(new Shelf(category.id() + "/themes", UiKit.text(key + ".themes"), themeOptions().stream()
                    .filter(theme -> !special.contains(theme.id())).map(theme -> new Tile(null, theme.id())).toList()),
              new Shelf(category.id() + "/special", UiKit.text(key + ".special"), special.stream().map(theme -> new Tile(null, theme)).toList()));
    }

    /**
     * The search's results: per category, in start-page order, its cards that match by their label, a member's label or
     * key, or their section's name. A gallery's designs are listed one by one, as a subcategory's lone family ({@link #shelf});
     * any other picker family is one card.
     */
    private List<Shelf> results() {
        List<Shelf> results = new ArrayList<>();
        for (var category : blueprint.categories()) {
            java.util.Set<Tile> found = new java.util.LinkedHashSet<>();
            for (Shelf shelf : shelves(category)) {
                for (Tile tile : shelf.tiles()) {
                    var card = tile.card();
                    if (card == null) {
                        if (matches(shelf.title(), themeLabel(tile.theme()))) { found.add(tile); }
                    } else if (card.gallery()) {
                        card.members().stream().filter(member -> matches(shelf.title(), card.label(), member.label(), member.key()))
                              .forEach(member -> found.add(new Tile(single(member), null)));
                    } else if (matches(shelf.title(), card.label()) || card.members().stream().anyMatch(member -> matches(member.label(), member.key()))) {
                        found.add(tile);
                    }
                }
            }
            if (!found.isEmpty()) { results.add(new Shelf(category.id(), categoryName(category.id()), List.copyOf(found))); }
        }
        return results;
    }

    private boolean matches(String... texts) {
        for (String text : texts) { if (text.toLowerCase(Locale.ROOT).contains(query)) { return true; } }
        return false;
    }

    /** A section's cards; in a subcategory a section of one picker family has the room to list its members. */
    private static Shelf shelf(BoardEditorBlueprint.Section section) {
        var cards = section.cards();
        boolean members = section.subcategory() != null && cards.size() == 1 && cards.getFirst().picker();
        return new Shelf(section.path(), sectionName(section.category(), section.id()), members
              ? cards.getFirst().members().stream().map(member -> new Tile(single(member), null)).toList()
              : cards.stream().map(card -> new Tile(card, null)).toList());
    }

    /** The name of a section or a subcategory. */
    private static String sectionName(String category, String id) {
        return UiKit.text("BoardEditor.palette." + category + "." + id);
    }

    /** A gallery's design as a card of its own. */
    private static BoardEditorBlueprint.Card single(BoardEditorBlueprint.Entry member) {
        return new BoardEditorBlueprint.Card(member.label(), List.of(member));
    }

    /** A section's header: its caption over a hairline, named after the section. */
    private Table shelfHeader(Shelf shelf) {
        Table header = new Table(); header.setName("editor-section-" + shelf.id()); header.bottom().left();
        header.add(ui.caption(shelf.title())).left().padLeft(2).padBottom(3).row();
        header.add(new Image(ui.skin.newDrawable("white", UiTheme.LINE))).growX().height(1);
        return header;
    }

    /**
     * A grid card: a Theme card arms its theme's brush; a subcategory's card opens it and a gallery card its designs; any
     * other card arms its entry, a picker family its first member. Its user object holds the brush keys that highlight it.
     */
    private UiButton card(Tile tile) {
        Image preview = new Image();
        UiButton card;
        if (tile.subcategory() != null) {
            String category = page.category(), id = tile.subcategory();
            var members = category(category).sections(id).stream().flatMap(section -> section.cards().stream())
                  .flatMap(shown -> shown.members().stream()).toList();
            preview(members.getFirst(), preview);
            card = visualCard(sectionName(category, id) + " ›", preview, 70, () -> show(new Page(category, id, null)));
            card.setName("editor-subcategory-" + id);
            card.setUserObject(members.stream().map(BoardEditorBlueprint.Entry::key).collect(java.util.stream.Collectors.toSet()));
        } else if (tile.theme() != null) {
            String theme = tile.theme();
            samples.add(theme, "", "", preview);
            card = visualCard(themeLabel(theme), preview, 70, () -> send(Action.CHOOSE_BRUSH, "theme", theme));
            card.setName("editor-library-theme/" + theme); card.setUserObject(java.util.Set.of(BoardEditorSession.THEME_BRUSH + theme));
        } else {
            var shown = tile.card();
            var first = shown.members().getFirst();
            preview(first, preview);
            if (shown.gallery()) {
                card = visualCard(shown.label() + " ›", preview, 70, () -> show(new Page(page.category(), page.subcategory(), shown.label())));
                card.setName("editor-gallery-" + shown.label());
            } else {
                card = visualCard(shown.label(), preview, 70, () -> arm(first));
                card.setName("editor-library-" + first.key());
                // A family's members, shown one by one, share long names ("Legacy building art 52"): two lines show what tells them apart.
                if (blueprint.card(first).family() && !shown.family()) {
                    card.getLabel().setEllipsis(false); card.getLabel().setWrap(true); card.getCell(card.getLabel()).height(32);
                }
            }
            card.setUserObject(shown.members().stream().map(BoardEditorBlueprint.Entry::key).collect(java.util.stream.Collectors.toSet()));
            if (first.blueprint()) {
                // The chip on a dark plate, legible over any preview.
                Table plate = new Table(); plate.setBackground(ui.skin.newDrawable("white", UiTheme.alpha(UiTheme.POP, .85f)));
                plate.add(ui.chip(UiKit.text("BoardEditor.blueprint"), UiKit.Tone.NORMAL));
                overlay(card, plate, 0, com.badlogic.gdx.utils.Align.topLeft);
            }
        }
        // The whole name, which a long one's card cuts short.
        ui.tip(card).getActor().setText(card.getText());
        if (snapshot != null) { card.pressed(((java.util.Set<?>) card.getUserObject()).contains(snapshot.activeBrush().key())); }
        libraryCards.add(card);
        return card;
    }

    /** Arms the brush with a palette entry: a preset or design as a terrain brush, an asset as an object or stamp brush. */
    private void arm(BoardEditorBlueprint.Entry entry) {
        if (entry.asset() != null) { send(Action.ASSET, entry.key()); }
        else { send(Action.CHOOSE_BRUSH, entry.owner(), entry.preset() != null ? entry.preset().id() : entry.variant().id()); }
    }

    /** The category of a brush key (its entry's first section, or the Theme category's); null for none, as a hex stamp. */
    private String activeCategory(String key) {
        if (key.startsWith(BoardEditorSession.THEME_BRUSH)) { return blueprint.themeCategory() == null ? null : blueprint.themeCategory().id(); }
        var entry = blueprint.entry(key);
        return entry == null ? null : BoardEditorBlueprint.categoryOf(entry.sections().getFirst());
    }

    /** {@code badge} over the card's preview in its {@code align} corner; {@code size} 0 keeps the badge's own size. */
    private static void overlay(UiButton card, Actor badge, float size, int align) {
        Table corner = new Table(); corner.setTouchable(Touchable.disabled); corner.align(align);
        var cell = corner.add(badge).pad(4);
        if (size > 0) { cell.size(size); }
        ((Stack) card.getCells().first().getActor()).add(corner);
    }

    /** An entry's preview: a preset's sample, a design's, an object's model or decal, or a stamp's thumbnail. */
    private void preview(BoardEditorBlueprint.Entry entry, Image image) {
        if (entry.preset() != null) { samples.add(libraryTheme, entry.owner(), "preset:" + entry.preset().id(), image); }
        else if (entry.variant() != null) { preview(entry.variant(), image); }
        else if (!entry.asset().thumbnail().isEmpty()) { thumbnail(entry.asset().thumbnail(), image); }
        else if (entry.asset().kind().equals("prop")) { models.add(entry.asset().model(), entry.asset().colours(), image); }
        else { thumbnail(BoardDecalArt.image(entry.asset().id()), image); }
    }

    /** A design's preview: its thumbnail, else its model, else (a generated building's too) its sample. */
    private void preview(BoardEditorBlueprint.Variant variant, Image image) {
        if (!variant.thumbnail().isEmpty()) { thumbnail(variant.thumbnail(), image); }
        else if (!variant.asset().isEmpty() && !BoardMultiHexBuilding.supports(variant.asset())) {
            models.add(variant.asset(), image);
        }
        else { samples.add(libraryTheme, variant.owner(), variant.id(), image); }
    }

    private void thumbnail(String relative, Image image) {
        thumbnail(new java.io.File(Configuration.dataDir(), relative), image);
    }

    private void thumbnail(java.io.File source, Image image) {
        String file = source.getAbsolutePath();
        if (source.isFile()) {
            if (!thumbnails.contains(file)) { thumbnails.load(file, Texture.class); }
            previews.add(new Preview(image, file));
        } else { image.setUserObject(UiKit.text("BoardEditor.noPreview")); }
    }

    /**
     * The Brush panel follows the active tool: Paint shows the active brush's options, Sculpt its mode, Erase the radius;
     * each ends with the tool's gesture hint. Select has no brush options, and the panel hides.
     */
    private void showBrush() {
        brushNumbers.forEach(UiNumber::close); brushNumbers.clear(); brushRows.clear(); brushLayoutWidth = -1;
        var brush = snapshot.activeBrush();
        var tool = snapshot.tool();
        sculptHint = null;
        if (tool != BoardEditorSession.Tool.SELECT) {
            String name = toolName(tool);
            Label title = ui.label(tool == BoardEditorSession.Tool.SCULPT ? name : name + " · " + brush.label(), "hud-title", 12, UiTheme.TEXT);
            title.setName("editor-brush-title"); title.setEllipsis(true); brushRow(title);
            // A stamp brush keeps only its id: it has no options, and each click places its layout as one new group. A
            // multi-hex blueprint (the DropShip) has none either: each click builds it on the hex and its neighbours; its
            // Erase is its component's, over the radius.
            boolean stamp = brush.object() == null && brush.component().isEmpty();
            var entry = blueprint.entry(brush.key());
            boolean hexes = entry != null && entry.preset() != null && !entry.preset().hexes().isEmpty();
            if (tool == BoardEditorSession.Tool.SCULPT) { showSculpt(brush); }
            else if (stamp || hexes && tool == BoardEditorSession.Tool.PAINT) {
                familyRow(brush);
                brushRow(hint(UiKit.text(stamp ? "BoardEditor.hint.stamp" : "BoardEditor.hint.hexBlueprint")));
            }
            else if (brush.themeCard() && tool == BoardEditorSession.Tool.ERASE) { brushRow(hint(UiKit.text("BoardEditor.themeNoErase"))); }
            else if (tool == BoardEditorSession.Tool.ERASE && brush.way()) {
                wayHeight(brush);
                brushRow(hint(UiKit.text("BoardEditor.way.eraseHint")));
            } else if (tool == BoardEditorSession.Tool.ERASE) {
                UiNumber radius = brushNumber(UiKit.text("BoardEditor.radius"), snapshot.brush(), 1, 20, 1, Action.BRUSH, "");
                var zone = zoneBrush(brush);
                if (zone == null) { brushRow(radius); } else { brushRow(radius, zoneNumber(zone)); }
                brushRow(hint(UiKit.text(brush.sampledHex() != null ? "BoardEditor.hint.eraseHexes"
                      : brush.object() == null ? "BoardEditor.hint.eraseComponent" : "BoardEditor.hint.eraseObjectsInRadius")));
            } else if (brush.object() != null) { objectControls(brush.object()); }
            else {
                if (brush.sampledHex() != null) {
                    Image preview = new Image(); preview.setName("editor-hex-stamp-preview"); samples.add(brush, preview);
                    Table face = new Table(); face.add(previewImage(preview)).growX().minWidth(0).prefWidth(256).height(160);
                    brushRow(face);
                }
                componentControls(brush);
            }
        }
        if (width > 0) { layoutCorners(); }
    }

    /**
     * A terrain brush's radius and the options its component offers for painting: Level and Theme for ground and a hex
     * stamp (a Theme card, which is its theme, has Level only), and, except on a hex stamp, a row per brush field the
     * brush holds (a number or a choice of its levels) and per optional field its card offers ({@code settings}), whose
     * None leaves the field out.
     */
    private void componentControls(BoardEditorSession.Brush brush) {
        List<Actor> controls = new ArrayList<>();
        if (brush.way()) { wayHeight(brush); }
        else if (!snapshot.drawsRoutes()) { controls.add(brushNumber(UiKit.text("BoardEditor.radius"), snapshot.brush(), 1, 20, 1, Action.BRUSH, "")); }
        var definition = blueprint.component(brush.component());
        if (brush.sampledHex() != null || definition.id().equals("ground")) {
            controls.add(brushNumber(UiKit.text("BoardEditor.levelLabel"), brush.elevation(), -10, 30, 1, Action.BRUSH_VALUE, "elevation")
                  .withCheckbox(brush.elevationEnabled(), checked -> send(Action.BRUSH_VALUE, "applyElevation", Boolean.toString(checked))));
            if (!brush.themeCard()) {
                var theme = ui.checkbox(UiKit.text("BoardEditor.labTheme"), brush.themeEnabled());
                onChange(theme, () -> send(Action.BRUSH_VALUE, "applyTheme", Boolean.toString(theme.isTicked())));
                Table themeControl = new Table(); themeControl.setName("editor-brush-theme"); themeControl.add(theme).padRight(4);
                themeControl.add(choice("", themeLabel(brush.theme()), themeOptions(), value -> send(Action.BRUSH_VALUE, "theme", value)))
                      .width(125).height(27); controls.add(themeControl);
            }
        }
        var entry = blueprint.entry(brush.key());
        List<String> settings = entry == null || entry.preset() == null ? List.of() : entry.preset().settings();
        boolean wall = brush.property("building") != null && brush.property("building").value() == 5;
        for (var field : brush.sampledHex() != null ? List.<BoardEditorBlueprint.Field>of() : definition.fields()) {
            if (wall && field.terrain().equals("building")) {
                var weight = brush.property("bldg_weight");
                controls.add(wallWeight(weight == null ? 4 : weight.value(), chosen -> send(Action.BRUSH_VALUE, "terrain:bldg_weight", chosen)));
                continue;
            }
            if (wallField(field.terrain(), wall)) { continue; }
            var value = brush.property(field.terrain());
            boolean offered = settings.contains(field.terrain());
            if (!field.brush() || value == null && !offered || brush.way() && field.terrain().equals("bridge_elev")) { continue; }
            String target = "terrain:" + field.terrain();
            if (field.choices().isEmpty()) {
                if (value != null) { controls.add(brushNumber(field.label(), value.value(), field.min(), field.max(), field.step(), Action.BRUSH_VALUE, target)); }
                continue;
            }
            List<Option> options = new ArrayList<>();
            if (offered) { options.add(new Option(UiKit.text("BoardEditor.none"), "")); }
            field.choices().forEach(choice -> options.add(new Option(choice.label(), Integer.toString(choice.value()))));
            String current = value == null ? UiKit.text("BoardEditor.none") : field.choices().stream().filter(c -> c.value() == value.value())
                  .map(BoardEditorBlueprint.Choice::label).findFirst().orElse(UiKit.text("BoardEditor.value", Integer.toString(value.value())));
            Table row = choice(field.label(), current, options, chosen -> send(Action.BRUSH_VALUE, target, chosen));
            row.setName("editor-brush-" + target); controls.add(row);
        }
        // A skin look turns as one: every hex of a stroke gets the brush's rotation.
        var style = brush.sampledHex() != null ? null : brush.appearance().get(definition.id());
        var design = style == null || style.variant() == null ? null : blueprint.variant(style.variant());
        if (design != null && design.skin() != null) {
            controls.add(brushNumber(UiKit.text("BoardEditor.designRotation"), style.rotation() == null ? 0 : style.rotation(),
                  0, 330, 30, Action.BRUSH_VALUE, "rotation:" + definition.id()));
        }
        if (brush.sampledHex() == null && definition.id().equals("building") && !wall) {
            var interior = brush.property("bldg_interiors");
            controls.add(interiors(interior == null ? 0 : interior.value(), chosen -> send(Action.BRUSH_VALUE, "terrain:bldg_interiors", chosen)));
            controls.add(brushNumber(UiKit.text("BoardEditor.designRotation"),
                  style == null || style.rotation() == null ? 0 : style.rotation(),
                  0, 359, 1, Action.BRUSH_VALUE, "rotation:building"));
        }
        if (brush.sampledHex() == null && definition.id().equals("scorch")) {
            controls.add(scorchSeverity(brush.appearance(), chosen -> send(Action.BRUSH_VALUE, "variant:scorch", chosen)));
        }
        if (brush.sampledHex() == null && definition.id().equals("pavement")) {
            List<Option> options = new ArrayList<>();
            for (var edges : megamek.common.board.HexAppearance.Edges.values()) {
                options.add(new Option(UiKit.text(edgesKey(edges)), edges.name().toLowerCase(Locale.ROOT)));
            }
            var current = megamek.common.board.HexAppearance.edges(brush.appearance());
            Table row = choice(UiKit.text("BoardEditor.pavementEdges"), UiKit.text(edgesKey(current)), options,
                  chosen -> send(Action.BRUSH_VALUE, "edges", chosen));
            row.setName("editor-brush-edges"); controls.add(row);
        }
        if (brush.sampledHex() == null && definition.id().equals("road")) {
            controls.add(trafficControlChoice(brush.appearance(), "road", 0,
                  chosen -> send(Action.BRUSH_VALUE, "trafficControl", chosen)));
            var surface = brush.property("road");
            if (surface != null && pavedRoad(surface.value())) {
                controls.add(lanesChoice(brush.appearance(), chosen -> send(Action.BRUSH_VALUE, "lanes", chosen)));
            }
        }
        var zone = zoneBrush(brush);
        if (zone != null) { controls.add(zoneNumber(zone)); }
        controls.add(clearOnly(brush));
        var fill = ui.checkbox(UiKit.text("BoardEditor.fill"), brush.fill()); fill.setName("editor-fill");
        onChange(fill, () -> send(Action.BRUSH_VALUE, "fill", Boolean.toString(fill.isTicked())));
        ui.tip(fill).getActor().setText(UiKit.text("BoardEditor.fill.tooltip"));
        if (!snapshot.drawsRoutes()) { controls.add(fill); }
        brushRows.add(controls);
        brushRow(hint(UiKit.text(brush.sampledHex() != null ? "BoardEditor.hint.paintStamp"
              : brush.themeCard() ? "BoardEditor.hint.theme" : zone != null ? "BoardEditor.deploymentZoneHint"
              : snapshot.drawsRoutes() ? brush.component().equals("building") ? "BoardEditor.hint.wall" : "BoardEditor.hint.road"
              : "BoardEditor.hint.paint")));
    }

    /** The old editor's "Only overwrite clear hexes", for Paint and Sculpt: hexes that hold terrain are left as they are. */
    private Actor clearOnly(BoardEditorSession.Brush brush) {
        var box = ui.checkbox(UiKit.text("BoardEditor.ButtonOOCTT"), brush.clearOnly()); box.setName("editor-clear-only");
        onChange(box, () -> send(Action.BRUSH_VALUE, "clearOnly", Boolean.toString(box.isTicked())));
        ui.tip(box).getActor().setText(UiKit.text("BoardEditor.ButtonOOCTT.tooltip"));
        return box;
    }

    /** The Deployment zone card's zone terrain, or null for any other brush (a hex stamp carries its zones whole). */
    private static BoardEditorSession.Property zoneBrush(BoardEditorSession.Brush brush) {
        return brush.sampledHex() == null ? brush.property("deployment_zone") : null;
    }

    /** The Deployment zone card's zone, 1 to 31: Paint adds it to the hexes, Erase removes it and keeps their other zones. */
    private UiNumber zoneNumber(BoardEditorSession.Property zone) {
        var ids = megamek.common.board.Board.exitsAsIntList(zone.exits());
        return brushNumber(UiKit.text("BoardEditor.deploymentZone"), ids.isEmpty() ? 1 : ids.getFirst(), 1,
              megamek.common.board.Board.MAX_DEPLOYMENT_ZONE_NUMBER, 1, Action.BRUSH_VALUE, "zone");
    }

    /** A gesture hint: muted, wrapped to the Brush panel's width on a row of its own. */
    private Label hint(String text) {
        Label hint = ui.label(text, "hud-small", 11.5f, UiTheme.MUTED); hint.setWrap(true); hint.setName("editor-brush-hint");
        return hint;
    }

    private BoardDecoration selectedObject() {
        return snapshot.objects().stream().filter(object -> object.id().equals(snapshot.object())).findFirst().orElse(null);
    }

    /**
     * A picker family's Type row (Brush): the family's members, the armed one shown; choosing one arms it (as its card
     * would, so the brush's transform starts again).
     */
    private void familyRow(BoardEditorSession.Brush brush) {
        var entry = blueprint.entry(brush.key());
        var card = entry == null ? null : blueprint.card(entry);
        if (card != null && card.picker()) {
            brushRow(choice(UiKit.text("BoardEditor.familyType"), entry.label(), members(card), key -> send(Action.ASSET, key)));
        }
    }

    private static List<Option> members(BoardEditorBlueprint.Card card) {
        return card.members().stream().map(member -> new Option(member.label(), member.key())).toList();
    }

    /** The paint template of an object brush; the inspector edits a placed instance with the same values. */
    private void objectControls(BoardDecoration object) {
        familyRow(snapshot.activeBrush());
        Action action = Action.BRUSH_VALUE;
        String prefix = "object:";
        if (RailRoute.isRoute(object)) {
            wayHeight(snapshot.activeBrush());
            boolean maglev = RailRoute.kind(object) == RailRoute.Kind.MAGLEV;
            brushRow(hint(UiKit.text(maglev ? "BoardEditor.hint.route" : "BoardEditor.hint.rail")));
            return;
        }
        var brush = snapshot.activeBrush();
        var precision = ui.checkbox(UiKit.text("BoardEditor.precision"), brush.precision()); precision.setName("editor-precision");
        onChange(precision, () -> send(Action.BRUSH_VALUE, "precision", Boolean.toString(precision.isTicked())));
        ui.tip(precision).getActor().setText(UiKit.text("BoardEditor.precision.tooltip"));
        brushRow(precision);
        if (BuildingVolumes.of(object.asset()).present()) {
            brushRow(rotations(object, true, action), brushNumber(UiKit.text("BoardEditor.scale"), object.scale(), .05, 8, .05, action, prefix + "scale"));
            brushRow(stretch(object, true, action));
            if (BuildingVolumes.of(object.asset()).modular()) {
                brushRow(brushNumber(UiKit.text("BoardEditor.assembledLevels"), Math.max(1, object.levels()), 1, 20, 1, action, prefix + "levels"));
            } else { brushRow(hint(UiKit.text("BoardEditor.authoredHeight"))); }
            var policy = brush.property("bldg_interiors");
            brushRow(interiors(policy == null ? 0 : policy.value(), object, chosen -> send(Action.BRUSH_VALUE, "terrain:bldg_interiors", chosen)));
            brushRow(hint(UiKit.text("BoardEditor.hint.place")));
            return;
        }
        if (BoardBillboard.ASSET.equals(object.asset())) {
            // With legs, their height is the placement's height.
            if (object.billboard().legs()) { brushRow(rotations(object, true, action)); }
            else { brushRow(rotations(object, true, action), brushNumber(UiKit.text("BoardEditor.heightOffset"), heightOffset(object), -10, 30, .1, action, prefix + "offset")); }
            billboard(object, true);
            brushRow(hint(UiKit.text("BoardEditor.billboard.hint")));
            brushRow(hint(UiKit.text("BoardEditor.hint.place")));
            return;
        }
        brushRow(rotations(object, true, action), brushNumber(UiKit.text("BoardEditor.scale"), object.scale(), .05, 8, .05, action, prefix + "scale"));
        // A brush sampled from a stretched object places stretched copies: it shows the stretch, which can be reset there.
        if (!object.stretch().equals(BoardDecoration.Stretch.NONE)) { brushRow(stretch(object, true, action)); }
        var mirror = ui.checkbox(UiKit.text("BoardEditor.mirror"), object.mirror()); mirror.setName("editor-tray-mirror");
        onChange(mirror, () -> send(action, prefix + "mirror", Boolean.toString(mirror.isTicked())));
        if (object.kind().equals("prop")) {
            brushRow(brushNumber(UiKit.text("BoardEditor.heightOffset"), heightOffset(object), -10, 30, .1, action, prefix + "offset"), mirror);
        } else {
            var span = ui.checkbox(UiKit.text("BoardEditor.spanHexes"), !object.clipToHex()); span.setName("editor-tray-span");
            onChange(span, () -> send(action, prefix + "clipToHex", Boolean.toString(!span.isTicked())));
            brushRow(span, mirror, brushNumber(UiKit.text("BoardEditor.paintOrder"), object.drawOrder(), -20, 20, 1, action, prefix + "order"));
        }
        if (BoardFlag.ASSET.equals(object.asset())) { flag(object, true); }
        var asset = blueprint.asset(object.asset());
        if (asset != null && asset.snap() != null) {
            var magnetic = ui.checkbox(UiKit.text("BoardEditor.magnetic"), brush.magnetic()); magnetic.setName("editor-magnetic");
            onChange(magnetic, () -> send(Action.BRUSH_VALUE, "magnetic", Boolean.toString(magnetic.isTicked())));
            ui.tip(magnetic).getActor().setText(UiKit.text("BoardEditor.magnetic.tooltip"));
            brushRow(magnetic);
        }
        brushRow(hint(UiKit.text("BoardEditor.hint.place")));
    }

    private double heightOffset(BoardDecoration object) {
        var placement = object.placement();
        return placement.offset() == null ? placement.level() - snapshot.elevation() : placement.offset();
    }

    /** All six route cards share the document's height policy. */
    private void wayHeight(BoardEditorSession.Brush brush) {
        UiKit.Segmented mode = ui.segmented("hud-seg", false, UiKit.text("BoardEditor.way.follow"), UiKit.text("BoardEditor.way.level"));
        mode.buttons.get(0).setName("editor-way-follow"); mode.buttons.get(1).setName("editor-way-level");
        onChange(mode.buttons.get(0), () -> send(Action.BRUSH_VALUE, "wayHeight", "follow"));
        onChange(mode.buttons.get(1), () -> send(Action.BRUSH_VALUE, "wayHeight", "level"));
        mode.select(brush.levelHeight() ? 1 : 0);
        var pillars = ui.checkbox(UiKit.text("BoardEditor.pillars"), brush.pillars());
        pillars.setName("editor-way-pillars");
        onChange(pillars, () -> send(Action.BRUSH_VALUE, "wayPillars", Boolean.toString(pillars.isTicked())));
        brushRow(mode, brushNumber(UiKit.text("BoardEditor.way.height"), brush.heightOffset(), 0, 50, 1,
              Action.BRUSH_VALUE, "wayOffset"), pillars);
        sculptHint = ui.label(source.editorHint(), "hud-small", 12, UiTheme.TEXT);
        sculptHint.setName("editor-way-readout"); sculptHint.setWrap(true);
        brushRow(sculptHint);
    }

    /** Sculpt's options share the tray: mode, Slope and the common radius, plus the hovered hex's hint. */
    private void showSculpt(BoardEditorSession.Brush brush) {
        var modes = megamek.client.ui.boardeditor.LevelSculpt.Mode.values();
        UiKit.Segmented mode = ui.segmented("hud-seg", false, UiKit.text("BoardEditor.sculpt.raise"),
              UiKit.text("BoardEditor.sculpt.lower"), UiKit.text("BoardEditor.sculpt.level"));
        for (int i = 0; i < modes.length; i++) {
            var value = modes[i];
            mode.buttons.get(i).setName("editor-sculpt-" + value.name().toLowerCase(Locale.ROOT));
            onChange(mode.buttons.get(i), () -> send(Action.BRUSH_VALUE, "sculpt", value.name()));
        }
        mode.select(brush.sculpt().ordinal());
        var slope = ui.checkbox(UiKit.text("BoardEditor.slope"), brush.slope()); slope.setName("editor-sculpt-slope");
        onChange(slope, () -> send(Action.BRUSH_VALUE, "slope", Boolean.toString(slope.isTicked())));
        ui.tip(slope).getActor().setText(UiKit.text("BoardEditor.slope.tooltip"));
        brushRow(brushNumber(UiKit.text("BoardEditor.radius"), snapshot.brush(), 1, 20, 1, Action.BRUSH, ""), mode, slope, clearOnly(brush));
        sculptHint = ui.label(source.editorHint(), "hud-small", 12, UiTheme.TEXT); sculptHint.setName("editor-sculpt-hint");
        Table hint = new Table(); hint.add(sculptHint).width(130).left();
        String gesture = UiKit.text("BoardEditor.hint." + brush.sculpt().name().toLowerCase(Locale.ROOT));
        brushRow(hint);
        brushRow(hint(UiKit.text("BoardEditor.hint.sculpt", gesture)));
    }

    private UiNumber brushNumber(String label, double value, double min, double max, double step, Action action, String target) {
        long editGeneration = generation;
        UiNumber number = new UiNumber(ui, stage, label, value, min, max, step,
              (next, finished) -> source.editorValue(new Command(action, target, next), finished, editGeneration)).fieldSize(56, 27);
        number.setName("editor-brush-" + target); brushNumbers.add(number); return number;
    }

    private void openDesigns(String owner) {
        stage.setKeyboardFocus(null); choices.cancel(); numbers.forEach(UiNumber::dismiss);
        designOwner = owner; designPage = 0; designQuery = ""; designSearch.field.setText("");
        designHeading.setText(UiTheme.upper(UiKit.text("BoardEditor.designTitle")));
        showDesigns(); designLayer.setVisible(true); designLayer.toFront();
    }

    private List<BoardEditorBlueprint.Variant> designVariants(String owner) {
        boolean linkedMaterial = blueprint.component(owner).fields().stream().flatMap(f -> f.choices().stream()).anyMatch(c -> !c.material().isEmpty());
        return blueprint.variants(owner).stream().filter(v -> !linkedMaterial || v.material().isEmpty()).toList();
    }

    /** One card of the gallery: its title, what the search looks in, how its preview fills and what choosing it does. */
    private record GalleryCard(String label, String search, Consumer<Image> preview, Runnable select) { }

    private void showDesigns() {
        if (billboardCards != null) { showCards(billboardCards); return; }
        if (designOwner.isEmpty()) { return; }
        // The owner's default look first, then its designs.
        String owner = designOwner, standard = UiKit.text("BoardEditor.defaultDesign");
        List<GalleryCard> cards = new ArrayList<>();
        cards.add(new GalleryCard(standard, standard, image -> samples.add(libraryTheme, owner, "", image), () -> send(Action.VARIANT, owner, "")));
        for (var design : designVariants(owner)) {
            cards.add(new GalleryCard(design.label(), design.label() + " " + design.id(), image -> preview(design, image),
                  () -> send(Action.VARIANT, owner, design.id())));
        }
        showCards(cards);
    }

    private void showCards(List<GalleryCard> cards) {
        designCards.clearChildren(); designPages.clearChildren();
        var matching = cards.stream().filter(card -> card.search().toLowerCase(Locale.ROOT).contains(designQuery)).toList();
        int last = Math.max(0, (matching.size() - 1) / PAGE_SIZE); designPage = Math.min(designPage, last);
        for (var card : matching.subList(Math.min(matching.size(), designPage * PAGE_SIZE), Math.min(matching.size(), (designPage + 1) * PAGE_SIZE))) {
            Image image = new Image();
            card.preview().accept(image);
            UiButton face = visualCard(card.label(), image, 80, () -> { card.select().run(); closeDesigns(); });
            face.setName("editor-gallery-" + card.search());
            designCards.addActor(face);
        }
        if (last > 0) {
            UiButton previous = button(UiKit.text("Previous"), () -> { designPage--; showDesigns(); }); previous.setDisabled(designPage == 0);
            UiButton next = button(UiKit.text("Next"), () -> { designPage++; showDesigns(); }); next.setDisabled(designPage == last);
            designPages.add(previous); designPages.add(ui.caption((designPage + 1) + " / " + (last + 1))).growX(); designPages.add(next);
        }
        designDialog.getCell(designPages).height(last > 0 ? designPages.getPrefHeight() : 0);
        ui.fitSearch(designDialog.getCell(designSearch), last > 0); layoutDesigns();
    }

    private void layoutDesigns() {
        ui.fitChoices(designDialog, designScroll, designCards, width - UiKit.DIALOG_MARGIN, height - UiKit.DIALOG_MARGIN);
    }

    private void closeDesigns() {
        stage.setKeyboardFocus(null); designLayer.setVisible(false); designCards.clearChildren(); billboardCards = null;
    }

    private UiButton visualCard(String title, Image preview, float imageHeight, Runnable select) {
        UiButton card = ui.button("hud-plain", null, title, null); card.clearChildren(); card.pad(4);
        card.add(previewImage(preview)).grow().minWidth(0).minHeight(imageHeight).prefHeight(imageHeight).row();
        card.getLabel().setEllipsis(true); card.getLabel().setAlignment(com.badlogic.gdx.utils.Align.center);
        card.add(card.getLabel()).growX().minWidth(0).height(22).padTop(2);
        onChange(card, () -> { stage.setKeyboardFocus(null); select.run(); });
        return card;
    }

    private Stack previewImage(Image preview) {
        if (preview.getName() == null) { preview.setName("editor-card-preview"); }
        preview.setScaling(com.badlogic.gdx.utils.Scaling.fit); preview.setTouchable(Touchable.disabled);
        Label waiting = ui.label(UiKit.text("BoardEditor.previewLoading"), "hud-small", 11, UiTheme.MUTED);
        waiting.setAlignment(com.badlogic.gdx.utils.Align.center); waiting.setTouchable(Touchable.disabled);
        // update() hides it once the preview has an image, or shows why there is none.
        if (preview.getDrawable() == null) { loading.add(new Loading(preview, waiting)); } else { waiting.setVisible(false); }
        Table backdrop = new Table(); backdrop.setBackground(ui.skin.newDrawable("white", UiTheme.POP));
        return new Stack(backdrop, preview, waiting);
    }

    private String assetLabel(String key) { var asset = blueprint.asset(key); return asset == null ? key : asset.label(); }
    /** A named object's name, else its asset's label with its first replaced colour, e.g. "Car · Blue". */
    private String objectLabel(BoardDecoration object) {
        if (object.name() != null) { return object.name(); }
        var slots = colourSlots(object.asset());
        for (int index = 0; index < Math.min(slots.size(), object.colours().slots().size()); index++) {
            String colour = object.colours().slot(index);
            if (colour == null) { continue; }
            String preset = slots.get(index).presets().stream().filter(p -> p.colour().equalsIgnoreCase(colour))
                  .map(RigidGlb.ColourSlot.Preset::label).findFirst().orElse(colour);
            return assetLabel(object.asset()) + " · " + preset;
        }
        return assetLabel(object.asset());
    }
    /** The type icon of a component's Layers row; the Ground row keeps the hexagon, other rules terrain shows "rules". */
    private static String layerIcon(BoardEditorBlueprint.Component component) { return LAYER_ICONS.getOrDefault(component.id(), "rules"); }
    /**
     * The type icon of an object's Layers row: decals and routes by kind, then by its asset's first palette section or
     * that section's category; decoration without rules shows a star.
     */
    private String layerIcon(BoardDecoration object) {
        if (object.kind().equals("decal")) { return "decal"; }
        if (RailRoute.isRoute(object)) { return "train"; }
        var asset = blueprint.asset(object.asset());
        if (asset == null || asset.sections().isEmpty()) { return "star"; }
        String section = asset.sections().getFirst();
        return LAYER_ICONS.getOrDefault(section, LAYER_ICONS.getOrDefault(BoardEditorBlueprint.categoryOf(section), "star"));
    }

    /** Runs what a Layers row or its action does, by node id or action name. */
    private void layerCommand(String key) {
        Runnable command = layerCommands.get(key);
        if (command != null) { command.run(); }
    }

    /**
     * Layers as a tree: each listed component with the objects on its surface (a reorderable stack), the objects on no
     * listed surface (a stack per surface), then each group with all its members in the hex. The selection is the
     * session's: the whole group, else the object, else the component. Another hex starts expanded; on the same hex
     * the rows the user collapsed stay collapsed, except that a newly selected member expands its group.
     */
    private void showContents() {
        layerCommands.clear();
        if (!java.util.Objects.equals(snapshot.selected(), layersHex)) { layersHex = snapshot.selected(); contents.expandAll(); }
        if (snapshot.selected() == null) {
            hexTitle.setText(UiKit.text("BoardEditor.hexInspector"));
            // Layers keep their region; the tree's empty line says why.
            contents.nodes(List.of()); contents.select(""); return;
        }
        hexTitle.setText(UiKit.text("BoardEditor.hexTitle", snapshot.selected().getBoardNum()));
        List<UiTree.Node> rows = new ArrayList<>();
        for (var component : blueprint.components()) {
            if (!listed(component)) { continue; }
            // A destroyed structure's rubble is its own: its row stands for both.
            if (component.id().equals("rubble") && !snapshot.destroyed().isEmpty()) { continue; }
            boolean destroyed = snapshot.destroyed().contains(component.id());
            String id = COMPONENT_ROW + component.id();
            layerCommands.put(id, () -> send(Action.COMPONENT, component.id()));
            var row = new UiTree.Node(id, component.label()).icon(layerIcon(component)).tooltip(codes(component))
                  .detail(component.id().equals("ground") ? UiKit.text("GpuBoard.map.level", snapshot.elevation())
                        : destroyed ? UiKit.text("BoardEditor.destroyed") : null)
                  .children(objectRows(snapshot.objects().stream().filter(object -> !snapshot.grouped(object)
                        && object.placement().receiver() != null && component.receiver().equals(object.placement().receiver().terrain()))
                        .toList(), component.receiver()));
            if (!component.present().isEmpty() || component.fields().isEmpty()) {
                String remove = id + "-remove";
                layerCommands.put(remove, () -> send(Action.REMOVE_COMPONENT, component.id()));
                row = row.action(new UiTree.Action(remove, "close", null, true));
            }
            rows.add(row);
        }
        // Objects on a destroyed structure stay under its row, as they stand again with it.
        var detached = snapshot.objects().stream().filter(object -> !snapshot.grouped(object) && (object.placement().receiver() == null
              || blueprint.components().stream().noneMatch(c -> c.receiver().equals(object.placement().receiver().terrain())
                    && (c.isPresent(name -> snapshot.property(name) != null) || snapshot.destroyed().contains(c.id()))))).toList();
        detached.stream().map(d -> d.placement().receiver()).distinct().forEach(receiver -> rows.addAll(objectRows(detached.stream()
              .filter(d -> java.util.Objects.equals(receiver, d.placement().receiver())).toList(), String.valueOf(receiver))));
        // Grouped members stay out of the reorderable surface stacks: one row per group, all its members in the hex below it.
        var grouped = snapshot.objects().stream().filter(snapshot::grouped).toList();
        grouped.stream().map(BoardDecoration::group).distinct().forEach(group -> {
            var members = grouped.stream().filter(object -> object.group().equals(group)).toList();
            int size = snapshot.groupSizes().get(group);
            String id = GROUP_ROW + group, delete = id + "-delete";
            layerCommands.put(id, () -> send(Action.SELECT_OBJECT, group, ""));
            layerCommands.put(delete, () -> { send(Action.SELECT_OBJECT, group, ""); send(Action.DELETE_SELECTION); });
            rows.add(new UiTree.Node(id, members.size() < size ? UiKit.text("BoardEditor.groupHere", size, members.size())
                        : UiKit.text("BoardEditor.groupSize", size))
                  .icon("stack").action(new UiTree.Action(delete, "close", UiKit.text("BoardEditor.deleteGroup", size), true))
                  .children(objectRows(members, null)));
        });
        contents.nodes(rows);
        contents.select(!inspecting() ? "" : !snapshot.group().isEmpty() ? GROUP_ROW + snapshot.group()
              : snapshot.object().isEmpty() ? COMPONENT_ROW + snapshot.component() : OBJECT_ROW + snapshot.object());
    }

    /**
     * A Layers row's tooltip, the old editor's raw terrain codes as the board file writes them ("building:2:9"): the
     * Ground row lists every terrain of the hex, any other row the terrains of its own component.
     */
    private String codes(BoardEditorBlueprint.Component component) {
        var own = component.fields().stream().map(BoardEditorBlueprint.Field::terrain).toList();
        return snapshot.properties().stream().filter(p -> component.id().equals("ground") || own.contains(p.terrain()))
              .map(BoardEditorSession.Property::code).collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * Whether Layers lists {@code component} for the inspected hex: Ground always, a component by its own terrain, one
     * with no fields at all (a look such as Scorched ground) by its appearance, and an additive one without terrain of
     * its own (Hazards, Weather, Scenario features, Salvage, Elevators) while any of its fields is in the hex. An
     * additive row has no remove button: each field has its own in Edit.
     */
    private boolean listed(BoardEditorBlueprint.Component component) {
        if (component.id().equals("ground") || snapshot.destroyed().contains(component.id())) { return true; }
        if (!component.present().isEmpty()) { return component.isPresent(name -> snapshot.property(name) != null); }
        if (component.fields().isEmpty()) { return snapshot.appearance().containsKey(component.id()); }
        return component.palette() && component.fields().stream().anyMatch(field -> snapshot.property(field.terrain()) != null);
    }

    /**
     * The Layers rows of objects: each with its height and resolved level, its type icon and its remove ×. Rows of one
     * {@code stack} (the objects on one surface) reorder by dragging; null for none (a group's members).
     */
    private List<UiTree.Node> objectRows(List<BoardDecoration> objects, String stack) {
        List<UiTree.Node> rows = new ArrayList<>();
        for (BoardDecoration object : objects) {
            String level = height(object);
            if (terrain.get() != null) {
                var placed = installedObjects.stream().filter(o -> o.id().equals(object.id())).findFirst();
                if (placed.isPresent()) { level += " · " + UiKit.text("BoardEditor.level", UiNumber.format(placed.get().anchorLevel())); }
            }
            String id = OBJECT_ROW + object.id(), remove = "editor-remove-" + object.id();
            layerCommands.put(id, () -> send(Action.SELECT_OBJECT, object.id()));
            layerCommands.put(remove, () -> send(Action.REMOVE_OBJECT, object.id(), ""));
            rows.add(new UiTree.Node(id, objectLabel(object)).detail(level).icon(layerIcon(object)).stack(stack)
                  .action(new UiTree.Action(remove, "close", null, true)));
        }
        return rows;
    }
    private void showDetails() {
        String selection = snapshot.selected() + ":" + snapshot.object() + ":" + snapshot.component();
        boolean selectionChanged = !selection.equals(shownSelection);
        float scroll = selectionChanged ? 0 : inspectorScroll.getScrollY(); shownSelection = selection;
        // Edit starts at its top for a new selection; Layers scroll the selected row into view themselves (UiTree.select).
        numbers.forEach(UiNumber::close); numbers.clear();
        detail.clearChildren(); detail.top(); detail.defaults().growX().minWidth(0).pad(3, 12, 3, 12);
        if (!inspecting()) { return; }
        var object = snapshot.objects().stream().filter(d -> d.id().equals(snapshot.object())).findFirst();
        if (!snapshot.group().isEmpty() && object.isPresent()) {
            heading(UiKit.text("BoardEditor.groupSize", snapshot.selection().size()));
            text(UiKit.text("BoardEditor.groupHint"));
            Table group = new Table(); group.add(button(UiKit.text("BoardEditor.ungroup"), () -> send(Action.UNGROUP))).growX();
            group.add(button(UiKit.text("BoardEditor.duplicate"), () -> send(Action.DUPLICATE_OBJECT))).growX().padLeft(4);
            group.add(removeButton(UiKit.text("BoardEditor.delete"), () -> send(Action.DELETE_SELECTION))).growX().padLeft(4); detail.add(group).row();
            groupTransform();
            // The selected member's colour slots repaint every member at once (a train's Body and Trim).
            var slots = object.get().kind().equals("prop") ? colourSlots(object.get().asset()) : List.<RigidGlb.ColourSlot>of();
            if (!slots.isEmpty()) {
                heading(UiKit.text("BoardEditor.groupColours"));
                colourRows(slots, object.get().colours(), (slot, value) -> send(Action.GROUP_VALUE, "colour" + slot, value));
            }
            inspectorScroll.validate(); inspectorScroll.setScrollY(scroll); return;
        }
        if (snapshot.selection().size() > 1) {
            heading(UiKit.text("BoardEditor.selectedCount", snapshot.selection().size()));
            text(UiKit.text("BoardEditor.selectionHint", UiKit.text("BoardEditor.selectHint")));
            Table group = new Table(); group.add(button(UiKit.text("BoardEditor.clearSelection"), () -> send(Action.CLEAR_SELECTION))).growX();
            group.add(button(UiKit.text("BoardEditor.deleteSelected"), () -> send(Action.DELETE_SELECTION))).growX(); detail.add(group).row();
        }
        if (object.isPresent()) { object(object.get()); }
        else { component(); }
        inspectorScroll.validate(); inspectorScroll.setScrollY(scroll);
    }
    private void component() {
        var definition = blueprint.component(snapshot.component());
        // Edit only shows components that have a row in Layers; absent terrain has no inspector controls.
        if (!listed(definition)) { return; }
        heading(definition.label());
        boolean destroyed = snapshot.destroyed().contains(definition.id());
        if (destroyed || STRUCTURES.contains(definition.id()) && snapshot.property(definition.present().getFirst()) != null) {
            destroyedRow(destroyed);
        }
        // A destroyed structure keeps its look and settings in its ruin, as a game leaves it: nothing to edit until it
        // stands again.
        if (destroyed) { return; }
        if (definition.id().equals("ground")) {
            number(UiKit.text("BoardEditor.groundLevelLabel"), snapshot.elevation(), -10, 30, 1, Action.ELEVATION, "");
        }
        Table appearance = new Table(); appearance.defaults().growX().minWidth(0).uniformX().top().pad(0, 2, 0, 2);
        if (definition.id().equals("ground")) { appearance.add(themeChoice(UiKit.text("BoardEditor.hexTheme"), snapshot.theme(), v -> send(Action.THEME, v))); }
        // A bridge's designs are its type: the Built and Natural buttons below; a scorch's its Severity row.
        if (!designVariants(definition.id()).isEmpty() && !definition.id().equals("bridge") && !definition.id().equals("scorch")) {
            var style = snapshot.appearance().get(definition.id());
            var variant = style == null ? null : blueprint.variant(style.variant());
            String designId = variant == null ? blueprint.variants(definition.id()).stream()
                  .filter(v -> style != null && !v.material().isEmpty() && v.material().equals(style.material()))
                  .map(BoardEditorBlueprint.Variant::id).findFirst().orElse("") : variant.id();
            Image design = new Image(); samples.add(snapshot.theme(), definition.id(), designId, design);
            Table visual = new Table(); visual.defaults().growX().minWidth(0);
            visual.add(ui.caption(UiKit.text("BoardEditor.visualDesign"))).left().padBottom(5).row();
            visual.add(visualCard(variant == null || !designVariants(definition.id()).contains(variant) ? UiKit.text("BoardEditor.defaultDesign")
                  : variant.label(), design, 64, () -> openDesigns(definition.id())));
            appearance.add(visual);
            detail.add(appearance).row();
            if (variant != null && variant.blend()) {
                number(UiKit.text("BoardEditor.blendStrength"), style.strength() == null ? .5 : style.strength(), 0, 1, .05, Action.BLEND, "");
            }
            if (variant != null && variant.skin() != null) {
                number(UiKit.text("BoardEditor.designRotation"), style.rotation() == null ? 0 : style.rotation(), 0, 330, 30,
                      Action.DESIGN_ROTATION, definition.id());
            }
        } else if (appearance.hasChildren()) { detail.add(appearance).row(); }
        for (var field : definition.fields()) { if (!field.optional() || snapshot.property(field.terrain()) != null) { field(field); } }
        if (definition.id().equals("pavement")) { pavementEdges(); }
        if (definition.id().equals("scenario") && snapshot.property("fortified") != null) { fortification(); }
        if (definition.id().equals("building") && snapshot.property("building") != null) {
            if (snapshot.buildingProfileAvailable()) {
                var profiles = List.of(new Option(UiKit.text("BoardEditor.buildingProfile.straight"), "straight"),
                      new Option(UiKit.text("BoardEditor.buildingProfile.stepped"), "stepped"));
                var profile = choice(UiKit.text("BoardEditor.buildingProfile"),
                      profiles.get(megamek.common.board.HexAppearance.steppedBuilding(snapshot.appearance()) ? 1 : 0).label(),
                      profiles, chosen -> send(Action.BUILDING_PROFILE, chosen));
                profile.setName("editor-building-profile");
                detail.add(profile).growX().row();
            }
            if (snapshot.property("building").value() != 5) {
                detail.add(interiors(snapshot.buildingInteriors(), chosen -> send(Action.BUILDING_INTERIORS, chosen))).growX().row();
            }
            if (snapshot.buildingReference() != null) {
                detail.add(button(UiKit.text("BoardEditor.selectStructure"), () -> send(Action.SELECT_STRUCTURE))).growX().row();
            } else {
            buildingFootprint();
            buildingVariant();
            buildingRoof();
            buildingDoors();
            if (!snapshot.buildingConnected()) {
                if (!snapshot.kitVariants().isEmpty()) {
                    number(UiKit.text("BoardEditor.buildingPadding"), megamek.common.board.HexAppearance.padding(snapshot.appearance()),
                          0, 80, 1, Action.BUILDING_PADDING, "building");
                }
                var design = snapshot.appearance().get("building");
                number(UiKit.text("BoardEditor.designRotation"), design == null || design.rotation() == null ? 0 : design.rotation(),
                      0, 359, 1, Action.DESIGN_ROTATION, "building");
            }
            var design = snapshot.appearance().get("building");
            heading(UiKit.text("BoardEditor.colours"));
            var presets = new ArrayList<RigidGlb.ColourSlot.Preset>();
            presets.add(new RigidGlb.ColourSlot.Preset(UiKit.text("BoardEditor.defaultDesign"), "#ffffff"));
            presets.addAll(BoardBridgeTruss.BODY.presets());
            colourRows(List.of(new RigidGlb.ColourSlot(UiKit.text("BoardEditor.buildingTint"), "#ffffff", presets)),
                  design == null ? BoardDecoration.Colours.NONE : design.colours(), (slot, value) -> send(Action.BUILDING_COLOUR, value));
            }
        }
        if (definition.id().equals("road")) { trafficControl("road"); }
        if (definition.id().equals("scorch")) {
            detail.add(scorchSeverity(snapshot.appearance(), chosen -> send(Action.VARIANT, "scorch", chosen))).growX().row();
        }
        // Deck mud is offered only where it plays: on a natural raised deck (Hex#deckMud).
        var deck = snapshot.property("bridge_elev");
        boolean deckMud = Boolean.FALSE.equals(megamek.common.board.HexAppearance.bridgeBuilt(snapshot.appearance()))
              && deck != null && deck.value() > 0;
        var optional = definition.fields().stream().filter(f -> f.optional() && !f.terrain().contains("fluff") && !f.terrain().equals("cliff_top")
              && !wallField(f.terrain(), snapshot.property("building") != null && snapshot.property("building").value() == 5)
              && (deckMud || !f.terrain().equals("bridge_mud")) && snapshot.property(f.terrain()) == null).toList();
        if (!optional.isEmpty()) {
            choose(UiKit.text("BoardEditor.addProperty"), UiKit.text("BoardEditor.choose"), optional.stream().map(f -> new Option(f.label(), f.terrain())).toList(), key -> {
                var field = optional.stream().filter(f -> f.terrain().equals(key)).findFirst().orElseThrow();
                send(Action.TERRAIN, key, Integer.toString(field.choices().isEmpty() ? 1 : field.choices().getFirst().value()));
            });
        }
        if (definition.id().equals("ground") || definition.id().equals("cliff")) { heading(UiKit.text("BoardEditor.cliffEdges")); edges("cliff_top"); }
        if (definition.id().equals("bridge")) {
            if (megamek.common.board.HexAppearance.implicit(snapshot.appearance())) { text(UiKit.text("BoardEditor.way.implicit")); }
            // This hex's state; a click sets the whole connected bridge.
            Boolean built = megamek.common.board.HexAppearance.bridgeBuilt(snapshot.appearance());
            Table type = new Table(); type.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
            for (boolean choice : new boolean[] { true, false }) {
                String typeKey = choice ? "BoardEditor.bridgeBuilt" : "BoardEditor.bridgeNatural";
                UiButton button = button(UiKit.text(typeKey), () -> send(Action.BRIDGE_TYPE, choice ? "built" : "natural"));
                button.setName(choice ? "editor-bridge-built" : "editor-bridge-natural");
                button.pressed(Boolean.valueOf(choice).equals(built));
                ui.tip(button).getActor().setText(UiKit.text(typeKey + ".tooltip"));
                type.add(button);
            }
            detail.add(type).growX().padTop(8).row();
            // Only a built bridge has piers and a design.
            if (Boolean.TRUE.equals(built)) {
                boolean truss = megamek.common.board.HexAppearance.truss(snapshot.appearance());
                Table design = new Table(); design.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
                for (boolean choice : new boolean[] { false, true }) {
                    String designKey = choice ? "BoardEditor.bridgeTruss" : "BoardEditor.bridgeStandard";
                    UiButton button = button(UiKit.text(designKey), () -> send(Action.BRIDGE_DESIGN, choice ? "truss" : "standard"));
                    button.setName(choice ? "editor-bridge-truss" : "editor-bridge-standard");
                    button.pressed(choice == truss);
                    ui.tip(button).getActor().setText(UiKit.text(designKey + ".tooltip"));
                    design.add(button);
                }
                detail.add(design).growX().padTop(8).row();
                boolean on = megamek.common.board.HexAppearance.pillars(snapshot.appearance());
                UiButton pillars = button(UiKit.text("BoardEditor.pillars"), () -> send(Action.PILLARS, Boolean.toString(!on)));
                pillars.setName("editor-bridge-pillars"); pillars.pressed(on);
                ui.tip(pillars).getActor().setText(UiKit.text("BoardEditor.pillars.tooltip"));
                detail.add(pillars).padTop(8).row();
                // The truss's Body slot, chosen like a placed object's colour: one choice for the whole span.
                if (truss) {
                    heading(UiKit.text("BoardEditor.bridgeColour"));
                    colourRows(List.of(BoardBridgeTruss.BODY), snapshot.appearance().get("bridge").colours(),
                          (slot, value) -> send(Action.BRIDGE_COLOUR, value));
                }
            }
        }
        if (definition.id().equals("ground")) {
            heading(UiKit.text("BoardEditor.way.groundSection"));
            wayRow("road", false); wayRow("rail", false);
        } else if (definition.id().equals("bridge")) {
            heading(UiKit.text("BoardEditor.way.deckSection"));
            wayRow("bridge_road", true); wayRow("bridge_rail", true);
        } else if (definition.id().equals("road") && snapshot.property("bridge") != null) {
            wayLayerButtons("road", false);
        }
        UiButton sample = button(UiKit.text("BoardEditor.useAsBrush"), () -> send(Action.SAMPLE)); sample.setName("editor-use-hex-brush");
        ui.tip(sample).getActor().setText(UiKit.text("BoardEditor.useAsBrush.tooltip"));
        detail.add(sample).padTop(8).row();
        if (!definition.present().isEmpty() || definition.fields().isEmpty()) {
            detail.add(removeButton(UiKit.text("BoardEditor.removeComponent"), () -> send(Action.REMOVE_COMPONENT, definition.id()))).row();
        }
    }

    /** The structures a hex can hold destroyed (BoardEditorSession.Action#DESTROYED). */
    private static final java.util.Set<String> STRUCTURES = java.util.Set.of("building", "bridge", "fuelTank");

    /**
     * Destroyed: this hex's structure as a game leaves it, under the rubble of its construction, which works as rubble
     * does in play; cleared, it stands again as it stood. Whole structure does the same to every hex it joins.
     */
    private void destroyedRow(boolean destroyed) {
        Table row = new Table(); row.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
        UiButton hex = button(UiKit.text("BoardEditor.destroyed"), () -> send(Action.DESTROYED, "", Boolean.toString(!destroyed)));
        hex.setName("editor-structure-destroyed"); hex.pressed(destroyed);
        ui.tip(hex).getActor().setText(UiKit.text("BoardEditor.destroyed.tooltip"));
        row.add(hex);
        UiButton whole = button(UiKit.text(destroyed ? "BoardEditor.restoreStructure" : "BoardEditor.destroyStructure"),
              () -> send(Action.DESTROYED, "structure", Boolean.toString(!destroyed)));
        whole.setName("editor-structure-destroyed-whole");
        ui.tip(whole).getActor().setText(UiKit.text("BoardEditor.destroyStructure.tooltip"));
        row.add(whole);
        detail.add(row).growX().padTop(8).row();
    }

    /** A scorched hex's Severity (HexAppearance.Scorch): its named levels, the stored one shown. */
    private Table scorchSeverity(Map<String, megamek.common.board.HexAppearance> appearance, Consumer<String> choose) {
        var style = appearance.get("scorch");
        var current = style == null ? null : blueprint.variant(style.variant());
        Table row = choice(UiKit.text("BoardEditor.scorchSeverity"), current == null ? UiKit.text("BoardEditor.none") : current.label(),
              blueprint.variants("scorch").stream().map(variant -> new Option(variant.label(), variant.id())).toList(), choose);
        row.setName("editor-scorch-severity");
        return row;
    }

    /** The pavement hex's Edges ({@link megamek.common.board.HexAppearance.Edges}): one row of Auto, Fitted and Hex. */
    private void pavementEdges() {
        heading(UiKit.text("BoardEditor.pavementEdges"));
        var current = megamek.common.board.HexAppearance.edges(snapshot.appearance());
        Table row = new Table(); row.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
        for (var edges : megamek.common.board.HexAppearance.Edges.values()) {
            String value = edges.name().toLowerCase(Locale.ROOT), key = edgesKey(edges);
            UiButton button = button(UiKit.text(key), () -> send(Action.EDGES, value));
            button.setName("editor-pavement-edges-" + value);
            button.pressed(edges == current);
            ui.tip(button).getActor().setText(UiKit.text(key + ".tooltip"));
            row.add(button);
        }
        detail.add(row).growX().row();
    }

    /**
     * The fortified hex's strongpoint ({@link megamek.common.board.HexAppearance.Fortification}): one row of Dug in and
     * Sandbags. Pavement and roads take sandbags only, so Dug in is off there.
     */
    private void fortification() {
        heading(UiKit.text("BoardEditor.fortification"));
        java.util.function.IntPredicate has = type -> snapshot.property(megamek.common.units.Terrains.getName(type)) != null;
        var current = megamek.common.board.HexAppearance.fortification(snapshot.appearance(), has);
        boolean sandbagsOnly = megamek.common.board.HexAppearance.sandbagsOnly(has);
        Table row = new Table(); row.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
        for (var look : megamek.common.board.HexAppearance.Fortification.values()) {
            String value = look.name().toLowerCase(Locale.ROOT), key = "BoardEditor.fortification." + value;
            boolean locked = sandbagsOnly && look == megamek.common.board.HexAppearance.Fortification.DUG;
            UiButton button = button(UiKit.text(key), () -> send(Action.FORTIFICATION, value));
            button.setName("editor-fortification-" + value);
            button.pressed(look == current);
            button.setDisabled(locked);
            ui.tip(button).getActor().setText(UiKit.text(locked ? "BoardEditor.fortification.sandbagsOnly" : key + ".tooltip"));
            row.add(button);
        }
        detail.add(row).growX().row();
    }

    /**
     * The building's Footprint ({@link megamek.common.board.HexAppearance.Footprint}): one row of Angled and Square; a
     * click sets the whole connected building.
     */
    private void buildingFootprint() {
        heading(UiKit.text("BoardEditor.footprint"));
        var current = megamek.common.board.HexAppearance.footprint(snapshot.appearance());
        Table row = new Table(); row.defaults().growX().uniformX().minWidth(0).pad(0, 2, 0, 2);
        for (var footprint : megamek.common.board.HexAppearance.Footprint.values()) {
            String value = footprint.name().toLowerCase(Locale.ROOT);
            String key = "BoardEditor.footprint" + footprint.name().charAt(0) + value.substring(1);
            UiButton button = button(UiKit.text(key), () -> send(Action.FOOTPRINT, value));
            button.setName("editor-building-footprint-" + value);
            button.pressed(footprint == current);
            ui.tip(button).getActor().setText(UiKit.text(key + ".tooltip"));
            row.add(button);
        }
        detail.add(row).growX().row();
    }

    /**
     * The building's kit variant ({@link megamek.common.board.HexAppearance#kitVariant}) where its generated kit has more
     * than one: a choice of the names its JSON spec gives them; a choice sets the whole connected building.
     */
    private void buildingVariant() {
        var names = snapshot.kitVariants();
        if (names.size() < 2) { return; }
        String drawn = snapshot.kitDrawn();
        choose(UiKit.text("BoardEditor.kitVariant"), drawn,
              names.stream().map(name -> new Option(name, name)).toList(), name -> send(Action.KIT_VARIANT, name));
    }

    /**
     * A hangar's roof ({@link megamek.common.board.HexAppearance.Roof}) where its kit's variant offers one that spans its
     * hall beside its flat one: vaulted until told otherwise; a choice sets the whole connected building. Shown for a
     * building of the hangar class alone: any other keeps its variant's roof.
     */
    private void buildingRoof() {
        var buildingClass = snapshot.property("bldg_class");
        int value = buildingClass == null ? 0 : buildingClass.value();
        String drawn = snapshot.kitDrawn();
        if (value != megamek.common.units.IBuilding.HANGAR || drawn == null || !snapshot.kitVaults().contains(drawn)) { return; }
        boolean vaulted = megamek.common.board.TilesetScenery.vaulted(megamek.common.board.HexAppearance.roof(snapshot.appearance()), value);
        choose(UiKit.text("BoardEditor.buildingRoof"), vaulted ? "vaulted" : "flat",
              List.of(new Option(UiKit.text("BoardEditor.buildingRoof.vaulted"), "vaulted"),
                    new Option(UiKit.text("BoardEditor.buildingRoof.flat"), "flat")), id -> send(Action.BUILDING_ROOF, id));
    }

    /**
     * A hangar's large doors ({@link megamek.common.board.HexAppearance#doors}): the hex's sides, each a door or none (a
     * click on a side adds one as high as the building, or takes it away), and each door's height in levels. Shown for
     * a building of the hangar class, and for any that has doors.
     */
    private void buildingDoors() {
        int[] doors = megamek.common.board.HexAppearance.doors(snapshot.appearance());
        var buildingClass = snapshot.property("bldg_class");
        int mask = 0;
        for (int side = 0; side < 6; side++) { if (doors[side] > 0) { mask |= 1 << side; } }
        if (mask == 0 && (buildingClass == null || buildingClass.value() != megamek.common.units.IBuilding.HANGAR)) { return; }
        var storeys = snapshot.property("bldg_elev");
        int height = storeys == null ? 1 : Math.max(1, storeys.value());
        heading(UiKit.text("BoardEditor.doors"));
        UiHexSides sides = new UiHexSides(ui, mask, side -> send(Action.BUILDING_DOOR, Integer.toString(side),
              Integer.toString(doors[side] > 0 ? 0 : height)));
        sides.setName("editor-building-doors");
        ui.tip(sides).getActor().setText(UiKit.text("BoardEditor.doors.tooltip"));
        detail.add(sides).row();
        for (int side = 0; side < 6; side++) {
            if (doors[side] > 0) {
                number(UiKit.text("BoardEditor.doorHeight", UiKit.text("BoardEditor.doorSide." + side)), Math.min(doors[side], height),
                      1, height, 1, Action.BUILDING_DOOR, Integer.toString(side));
            }
        }
    }

    private static String edgesKey(megamek.common.board.HexAppearance.Edges edges) {
        String name = edges.name();
        return "BoardEditor.edges" + name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    private Table trafficControlChoice(Map<String, megamek.common.board.HexAppearance> appearance, String owner, int exits,
          Consumer<String> choose) {
        return trafficControlChoice(appearance, owner, exits, 0, 0, null, choose);
    }

    /**
     * As above, where single-lane ramps join the ground road across {@code ramps}: the default counts neither them nor its
     * {@code aprons} as roads, and is the {@code junction}'s own (SingleLane#control), as on the 3D board. Where no road
     * keeps priority there, stops are no signs at all: priority to the right.
     */
    private Table trafficControlChoice(Map<String, megamek.common.board.HexAppearance> appearance, String owner, int exits,
          int ramps, int aprons, megamek.common.board.LaneJunction junction, Consumer<String> choose) {
        var current = ramps == 0 ? megamek.common.board.HexAppearance.trafficControl(appearance, owner, exits)
              : megamek.common.board.SingleLane.control(appearance, exits, ramps, aprons, junction);
        String stops = junction != null && junction.state() == megamek.common.board.LaneJunction.State.PRIORITY_RIGHT
              ? "BoardEditor.trafficPriorityRight" : "BoardEditor.trafficStops";
        List<Option> options = List.of(new Option(UiKit.text(stops), "stops"),
              new Option(UiKit.text("BoardEditor.trafficSignals"), "signals"),
              new Option(UiKit.text("BoardEditor.trafficGantry"), "gantry"));
        String label = switch (current) {
            case STOPS -> stops;
            case SIGNALS -> "BoardEditor.trafficSignals";
            case GANTRY -> "BoardEditor.trafficGantry";
        };
        Table row = choice(UiKit.text("BoardEditor.trafficControl"), UiKit.text(label), options, choose);
        row.setName("editor-traffic-control-" + owner);
        return row;
    }

    private void trafficControl(String owner) {
        var way = snapshot.property(owner.equals("bridgeRoad") ? "bridge_road" : "road");
        int exits = way == null ? 0 : way.exits();
        var bridge = snapshot.property("bridge");
        if (owner.equals("bridgeRoad") && bridge != null) { exits &= bridge.exits(); }
        boolean ground = owner.equals("road") && way != null && pavedRoad(way.value());
        // The junction single lanes form here, its aprons (roadless concrete it runs into) no roads of it, as on the 3D board.
        var junction = ground ? snapshot.junction() : null;
        if (junction != null && (!junction.controlled() || junction.hub())) {
            // Nothing here needs controls, or only single lanes meet, which are never signalled: no choice changes what
            // is drawn. A stored choice is kept for when that changes.
            boolean merge = junction.controlled() && junction.waiting() != 0;
            String text = merge ? "BoardEditor.trafficMerge" : junction.controlled() ? "BoardEditor.trafficPriorityRight"
                  : "BoardEditor.trafficNone";
            Table row = new Table();
            row.add(ui.label(UiKit.text("BoardEditor.trafficControl"), "hud-small", 12, UiTheme.MUTED)).left().padRight(8);
            Label none = ui.label(UiKit.text(text), "hud-small", 12, UiTheme.MUTED);
            none.setWrap(true);
            row.add(none).growX().minWidth(0);
            row.setName("editor-traffic-control-" + owner);
            ui.tip(row).getActor().setText(UiKit.text(merge ? "BoardEditor.trafficMerge.tooltip"
                  : junction.controlled() ? "BoardEditor.trafficPriorityRight.tooltip" : "BoardEditor.trafficNone.tooltip"));
            detail.add(row).growX().row();
        } else {
            int ramps = junction == null || junction.hub() ? 0 : exits & ~junction.twoWay() & ~snapshot.apronSides() & 63;
            detail.add(trafficControlChoice(snapshot.appearance(), owner, exits, ramps, ground ? snapshot.apronSides() : 0,
                  junction, chosen -> send(Action.TRAFFIC_CONTROL, owner, chosen))).growX().row();
        }
        if (owner.equals("road") && way != null && pavedRoad(way.value())) { lanes(); }
    }

    /** Paved roads (not an alley, dirt or gravel) may be narrowed to a single, one-way lane (SingleLane#paved). */
    private static boolean pavedRoad(int level) {
        return level != 2 && level != megamek.common.units.Terrains.ROAD_LVL_DIRT
              && level != megamek.common.units.Terrains.ROAD_LVL_GRAVEL;
    }

    private Table lanesChoice(Map<String, megamek.common.board.HexAppearance> appearance, Consumer<String> choose) {
        boolean single = megamek.common.board.HexAppearance.singleLane(appearance, "road");
        List<Option> options = List.of(new Option(UiKit.text("BoardEditor.lanesTwo"), "two"),
              new Option(UiKit.text("BoardEditor.lanesSingle"), "single"));
        Table row = choice(UiKit.text("BoardEditor.lanes"), UiKit.text(single ? "BoardEditor.lanesSingle" : "BoardEditor.lanesTwo"),
              options, choose);
        row.setName("editor-lanes-road");
        ui.tip(row).getActor().setText(UiKit.text("BoardEditor.lanes.tooltip"));
        return row;
    }

    /**
     * The inspected paved road's lanes; on a single-lane run, a button reversing its direction of travel; on a two-lane
     * road, per side a single-lane road joins it, whether that road is its entrance (an on-ramp) or its exit (off-ramp).
     */
    private void lanes() {
        detail.add(lanesChoice(snapshot.appearance(), chosen -> send(Action.LANES, "road", chosen))).growX().row();
        if (snapshot.laneRun()) {
            var reverse = button(UiKit.text("BoardEditor.laneReverse"), () -> send(Action.LANE_DIRECTION, "road", "reverse"));
            reverse.setName("editor-lane-reverse");
            ui.tip(reverse).getActor().setText(UiKit.text("BoardEditor.laneReverse.tooltip"));
            detail.add(reverse).row();
        }
        if (snapshot.rampSides() == 0) { return; }
        heading(UiKit.text("BoardEditor.ramps"));
        List<Option> options = List.of(new Option(UiKit.text("BoardEditor.rampEntrance"), "entrance"),
              new Option(UiKit.text("BoardEditor.rampExit"), "exit"));
        for (int side = 0; side < 6; side++) {
            if ((snapshot.rampSides() & 1 << side) == 0) { continue; }
            int d = side;
            boolean entrance = (snapshot.entranceSides() & 1 << side) != 0;
            Table row = choice(UiKit.text("BoardEditor.ramp", UiKit.text("BoardEditor.sides").split(",")[side]),
                  UiKit.text(entrance ? "BoardEditor.rampEntrance" : "BoardEditor.rampExit"), options,
                  chosen -> send(Action.RAMP, "road:" + d, chosen));
            row.setName("editor-ramp-" + side);
            ui.tip(row).getActor().setText(UiKit.text("BoardEditor.ramp.tooltip"));
            detail.add(row).growX().row();
            if (!entrance) { exitFrom(side); }
        }
    }

    /**
     * Under an exit (off-ramp) leaving the inspected two-lane road across {@code side}, which of the road's approaches may
     * take it: each on its own, or all of them (both, for a road through).
     */
    private void exitFrom(int side) {
        var road = snapshot.property("road");
        int approaches = road == null ? 0 : road.exits() & 63 & ~snapshot.rampSides();
        int fed = snapshot.exitFrom().get(side);
        if (Integer.bitCount(approaches) < 2 || fed == 0) { return; }
        String[] sides = UiKit.text("BoardEditor.sides").split(",");
        String all = UiKit.text(Integer.bitCount(approaches) == 2 ? "BoardEditor.rampFromBoth" : "BoardEditor.rampFromAll");
        List<Option> options = new ArrayList<>(List.of(new Option(all, "all")));
        for (int from = 0; from < 6; from++) {
            if ((approaches & 1 << from) != 0) {
                options.add(new Option(UiKit.text("BoardEditor.rampFromSide", sides[from]), Integer.toString(from)));
            }
        }
        String current = (fed & approaches) == approaches ? all : Integer.bitCount(fed) == 1
              ? UiKit.text("BoardEditor.rampFromSide", sides[Integer.numberOfTrailingZeros(fed)])
              : String.join(", ", java.util.stream.IntStream.range(0, 6).filter(from -> (fed & 1 << from) != 0)
                    .mapToObj(from -> UiKit.text("BoardEditor.rampFromSide", sides[from])).toList());
        Table row = choice(UiKit.text("BoardEditor.rampFrom"), current, options,
              chosen -> send(Action.RAMP_FROM, "road:" + side, chosen));
        row.setName("editor-ramp-from-" + side);
        ui.tip(row).getActor().setText(UiKit.text("BoardEditor.rampFrom.tooltip"));
        detail.add(row).growX().row();
    }

    private void wayRow(String terrain, boolean deck) {
        var owner = blueprint.component(terrain.equals("road") ? "road" : "ways");
        var field = owner.fields().stream().filter(item -> item.terrain().equals(terrain)).findFirst().orElseThrow();
        if (snapshot.property(terrain) == null) { text(field.label() + " · " + UiKit.text("BoardEditor.none")); return; }
        field(field);
        if (snapshot.property("bridge") != null) { wayLayerButtons(terrain, deck); }
        if (terrain.equals("road") || terrain.equals("bridge_road")) { trafficControl(deck ? "bridgeRoad" : "road"); }
        if (terrain.equals("rail") || terrain.equals("bridge_rail")) {
            var rail = snapshot.property(terrain);
            var kind = rail.value() == 2 ? RailRoute.Kind.MAGLEV : RailRoute.Kind.RAIL;
            int tracks = megamek.common.board.HexAppearance.tracks(snapshot.appearance(), deck ? "bridgeRail" : "rail");
            int current = tracks == 0 ? RailRoute.pieces(rail.exits()) : tracks;
            var layouts = RailRoute.junctions(rail.exits());
            if (layouts.size() > 1 || current != layouts.getFirst().tracks()) {
                Image preview = new Image(); junctions.add(kind, rail.exits(), current, preview);
                var chosen = layouts.stream().filter(layout -> layout.tracks() == current).findFirst().orElse(null);
                var card = visualCard(junctionLabel(chosen), preview, 64,
                      () -> openJunctions(kind, rail.exits(), current, terrain));
                card.setName("editor-junction"); detail.add(card).row();
            }
            String road = deck ? "bridge_road" : "road";
            if (snapshot.property(road) != null) {
                String key = deck ? "bridgeRail" : "rail";
                int beside = megamek.common.board.HexAppearance.beside(snapshot.appearance(), key);
                choose(UiKit.text("BoardEditor.way.corridor"), UiKit.text(beside == 0 ? "BoardEditor.way.middle" : "BoardEditor.way.beside"),
                      List.of(new Option(UiKit.text("BoardEditor.way.middle"), "middle"), new Option(UiKit.text("BoardEditor.way.beside"), "beside")),
                      value -> send(Action.CORRIDOR, key, value));
                if (beside != 0) { detail.add(button(UiKit.text("BoardEditor.way.swap"), () -> send(Action.CORRIDOR, key, "swap"))).row(); }
            }
        }
    }

    private void wayLayerButtons(String terrain, boolean deck) {
        Table layers = new Table();
        for (boolean target : new boolean[] { true, false }) {
            UiButton button = button(UiKit.text(target ? "BoardEditor.railOnDeck" : "BoardEditor.railBeneath"),
                  () -> send(Action.WAY_LAYER, terrain, target ? "deck" : "ground"));
            button.pressed(deck == target); button.setName("editor-way-" + terrain + (target ? "-deck" : "-beneath"));
            layers.add(button).growX().uniformX();
        }
        detail.add(layers).growX().row();
    }

    private static boolean wallField(String field, boolean wall) {
        return field.equals("bldg_weight") || wall && List.of("building", "bldg_class", "bldg_basement_type", "bldg_base_collapsed", "bldg_interiors").contains(field);
    }

    private Table wallWeight(int weight, Consumer<String> choose) {
        var field = blueprint.component("building").fields().stream().filter(f -> f.terrain().equals("building")).findFirst().orElseThrow();
        var options = field.choices().stream().map(c -> new Option(c.label(), Integer.toString(c.value()))).toList();
        return choice(field.label(), options.get(weight - 1).label(), options, choose);
    }

    private void field(BoardEditorBlueprint.Field field) {
        boolean wall = snapshot.property("building") != null && snapshot.property("building").value() == 5;
        if (wallField(field.terrain(), wall)) {
            if (field.terrain().equals("building")) {
                var weight = snapshot.property("bldg_weight");
                detail.add(wallWeight(weight == null ? 4 : weight.value(), chosen -> send(Action.TERRAIN, "bldg_weight", chosen))).growX().row();
                edges("building");
            }
            return;
        }
        var value = snapshot.property(field.terrain());
        if (field.terrain().contains("fluff") || field.terrain().equals("cliff_top") || field.terrain().equals("bldg_interiors")) { return; }
        if (field.terrain().equals("bldg_elev") && snapshot.buildingReference() != null) {
            if (value != null) { text(UiKit.text("BoardEditor.derivedBuildingHeight", Integer.toString(value.value()))); }
            return;
        }
        if (value == null) {
            if (field.optional()) {
                int initial = field.choices().isEmpty() ? 1 : field.choices().getFirst().value();
                detail.add(button("+ " + field.label(), () -> send(Action.TERRAIN, field.terrain(), Integer.toString(initial)))).row();
            }
            return;
        }
        Table row = new Table(); row.defaults().minWidth(0);
        if (field.choices().stream().anyMatch(c -> !c.material().isEmpty())) {
            UiChoiceGrid cards = ui.choiceGrid(120, 4).fit(INSPECTOR_WIDTH - 24, Float.MAX_VALUE);
            for (var choice : field.choices()) {
                Image image = new Image();
                // Each finish's sample: the component's preview hex at that level in its material.
                boolean road = field.terrain().equals("road") || field.terrain().equals("bridge_road");
                var owner = blueprint.component(road ? "road" : snapshot.component());
                var sample = new megamek.common.Hex(owner.previewLevel(), owner.preview(), snapshot.theme());
                int type = megamek.common.units.Terrains.getType(road ? "road" : field.terrain());
                var shown = sample.getTerrain(type);
                sample.addTerrain(new megamek.common.units.Terrain(type, choice.value(), shown != null && shown.hasExitsSpecified(),
                      shown == null ? 0 : shown.getExits()));
                sample.setAppearance(Map.of(owner.id(), new megamek.common.board.HexAppearance(null, null, choice.material(), null)));
                samples.add(BoardEditorSession.hexStamp(owner.id() + "/" + field.terrain() + "/" + choice.value(), sample), image);
                UiButton card = visualCard(choice.label(), image, 48, () -> send(Action.TERRAIN, field.terrain(), Integer.toString(choice.value())));
                card.setName("editor-surface-" + choice.value()); card.pressed(value.value() == choice.value()); cards.addActor(card);
            }
            row.add(cards).growX();
        } else if (field.terrain().equals("deployment_zone")) {
            // Its IDs, not its level, which the game never reads.
            row.add(ui.label(UiKit.text("BoardEditor.deploymentZoneIDs"), "hud-small", 12, UiTheme.MUTED)).left().padRight(8);
            TextField ids = textField("zone-ids", BoardEditorSession.zoneIds(value.exits()), text -> send(Action.ZONES, text));
            ui.tip(ids).getActor().setText(UiKit.text("BoardEditor.deploymentZoneIDs.tooltip"));
            row.add(ids).growX().minWidth(0).height(29);
        } else if (field.choices().isEmpty()) {
            String label = field.terrain().equals("industrial_elevator")
                  ? UiKit.text("BoardEditor.IndustrialElevatorDialog.shaftBottom") : field.label();
            row.add(numeric(label, value.value(), field.min(), field.max(), field.step(), Action.TERRAIN, field.terrain())).growX();
        } else if (field.choices().size() == 1 && field.choices().getFirst().value() == value.value()) {
            row.add(ui.label(field.label(), "hud-small", 12, UiTheme.TEXT)).growX().left();
        } else {
            String current = field.choices().stream().filter(c -> c.value() == value.value()).map(BoardEditorBlueprint.Choice::label)
                  .findFirst().orElse(UiKit.text("BoardEditor.value", Integer.toString(value.value())));
            row.add(choice(field.label(), current, field.choices().stream().map(c -> new Option(c.label(), Integer.toString(c.value()))).toList(),
                  chosen -> send(Action.TERRAIN, field.terrain(), chosen))).growX();
        }
        if (field.optional()) { row.add(removeButton("", () -> send(Action.REMOVE_TERRAIN, field.terrain()))).width(26).padLeft(4); }
        detail.add(row).minHeight(28).row();
        if (field.edges()) { edges(field.terrain()); }
        if (snapshot.joinedSides().containsKey(field.terrain())) { connectedSides(field.terrain()); }
        if (field.terrain().equals("solaris_elevator")) {
            heading(UiKit.text("BoardEditor.dieRolls"));
            Table rolls = new Table();
            for (int side = 0; side < 6; side++) {
                int roll = side;
                UiButton button = button(Integer.toString(side + 1), () -> send(Action.EDGE, field.terrain(), Integer.toString(roll)));
                button.pressed((value.exits() & (1 << side)) != 0); rolls.add(button).growX();
            }
            detail.add(rolls).row();
        } else if (field.terrain().equals("industrial_elevator")) {
            String dialog = "BoardEditor.IndustrialElevatorDialog.";
            UiNumber top = numeric(UiKit.text(dialog + "shaftTop"), megamek.common.IndustrialElevator.shaftTop(value.exits()),
                  0, 255, 1, Action.ELEVATOR, "top");
            ui.tip(top).getActor().setText(UiKit.text(dialog + "shaftTop.tooltip")); detail.add(top).row();
            UiNumber capacity = numeric(UiKit.text(dialog + "capacity"), megamek.common.IndustrialElevator.capacityTons(value.exits()),
                  0, 2550, 10, Action.ELEVATOR, "capacity");
            ui.tip(capacity).getActor().setText(UiKit.text(dialog + "capacity.tooltip")); detail.add(capacity).row();
            text(UiKit.text(dialog + "help"));
        }
    }

    /** Which sides of this water or pavement join the same terrain beside it; a side turned off shows ground in 3D. */
    private void connectedSides(String terrain) {
        heading(UiKit.text("BoardEditor.connectedSides"));
        UiHexSides sides = new UiHexSides(ui, snapshot.joinedSides().get(terrain),
              side -> send(Action.EDGE, terrain, Integer.toString(side)));
        sides.setName("editor-sides-" + terrain);
        ui.tip(sides).getActor().setText(UiKit.text("BoardEditor.connectedSides.tooltip"));
        detail.add(sides).row();
    }

    /** The kit's north-up edge diagram shares the document's clockwise N..NW mask. */
    private void edges(String terrain) {
        var value = snapshot.property(terrain);
        if (value != null && (value.exits() > 63 || value.exits() < 0)) {
            text(UiKit.text("BoardEditor.legacySelector", Integer.toString(value.exits()))); return;
        }
        UiHexSides edges = new UiHexSides(ui, value == null ? 0 : value.exits(),
              side -> send(Action.EDGE, terrain, Integer.toString(side)));
        edges.setName("editor-edges-" + terrain);
        detail.add(edges).row();
    }

    private static String height(BoardDecoration object) {
        var p = object.placement();
        return p.mode().equals("absolute") ? UiKit.text("BoardEditor.absoluteLevel", UiNumber.format(p.level()))
              : p.receiver().terrain() + (p.offset() == 0 ? "" : " + " + UiNumber.format(p.offset()));
    }
    private void object(BoardDecoration object) {
        heading(objectLabel(object));
        boolean authored = BuildingVolumes.of(object.asset()).present();
        // An object of a picker family swaps to another member in place: Type.
        var asset = blueprint.assetOf(object);
        var entry = asset == null ? null : blueprint.entry(asset.id());
        var family = entry == null ? null : blueprint.card(entry);
        if (family != null && family.picker()) {
            choose(UiKit.text("BoardEditor.familyType"), entry.label(), members(family), key -> send(Action.OBJECT_VALUE, "asset", key));
        }
        text(UiKit.text(authored ? "BoardEditor.authoredBuilding" : object.kind().equals("prop") ? "BoardEditor.visualProp" : "BoardEditor.surfaceDecal"));
        var placement = object.placement();
        String support = placement.receiver() == null ? "fixed" : switch (placement.receiver().terrain()) {
            case "bridge", "building", "industrial", "fuelTank", "ice" -> placement.receiver().terrain();
            default -> "ground";
        };
        text(UiKit.text("BoardEditor.support." + support));
        if (authored) {
            if (BuildingVolumes.of(object.asset()).modular()) {
                number(UiKit.text("BoardEditor.assembledLevels"), Math.max(1, object.levels()), 1, 20, 1, Action.OBJECT_VALUE, "levels");
            } else { text(UiKit.text("BoardEditor.authoredHeight")); }
            detail.add(interiors(snapshot.buildingInteriors(), object, chosen -> send(Action.BUILDING_INTERIORS, chosen))).growX().row();
        } else if (object.kind().equals("prop")) {
            // Scrubbing stops at the surface; a typed value may still sink the object into it.
            // A billboard on legs shows this height as its legs' (billboard).
            if (!BoardBillboard.ASSET.equals(object.asset()) || !object.billboard().legs()) {
                number(UiKit.text("BoardEditor.heightAboveSurface"), heightOffset(object), 0, 30, .1, Action.OBJECT_VALUE, "offset");
            }
        } else { number(UiKit.text("BoardEditor.paintOrder"), object.drawOrder(), -20, 20, 1, Action.OBJECT_VALUE, "order"); }
        if (object.kind().equals("prop") && terrain.get() != null) {
            var placed = installedObjects.stream().filter(o -> o.id().equals(object.id())).findFirst();
            text(placed.isEmpty() ? UiKit.text("BoardEditor.anchorWaiting")
                  : UiKit.text("BoardEditor.anchorResolved", UiNumber.format(placed.get().anchorLevel())));
        }
        heading(UiKit.text("BoardEditor.transform"));
        number(UiKit.text("BoardEditor.offsetEast"), object.x(), -4, 4, .01, Action.OBJECT_VALUE, "x");
        number(UiKit.text("BoardEditor.offsetNorth"), object.y(), -4, 4, .01, Action.OBJECT_VALUE, "y");
        detail.add(rotations(object, false, Action.OBJECT_VALUE)).row();
        // A billboard's size is its panel's: a scale or stretch would size its supports and lamps with it.
        boolean billboard = BoardBillboard.ASSET.equals(object.asset());
        if (!billboard) {
            number(UiKit.text("BoardEditor.scale"), object.scale(), .05, 8, .05, Action.OBJECT_VALUE, "scale");
            detail.add(stretch(object, false, Action.OBJECT_VALUE)).row();
        }
        if (object.kind().equals("decal")) {
            UiButton overflow = button(UiKit.text("BoardEditor.spanNeighbours"),
                  () -> send(Action.OBJECT_VALUE, "clipToHex", Boolean.toString(!object.clipToHex())));
            overflow.setName("editor-object-span"); overflow.pressed(!object.clipToHex()); detail.add(overflow).row();
        }
        UiButton mirror = button(UiKit.text("BoardEditor.mirror"), () -> send(Action.OBJECT_VALUE, "mirror", Boolean.toString(!object.mirror())));
        if (!authored && !billboard) { mirror.setName("editor-object-mirror"); mirror.pressed(object.mirror()); detail.add(mirror).row(); }
        if (authored) {
            heading(UiKit.text("BoardEditor.colours"));
            colourRows(List.of(new RigidGlb.ColourSlot(UiKit.text("BoardEditor.buildingTint"), "#ffffff", BoardBridgeTruss.BODY.presets())),
                  object.colours(), (slot, value) -> send(Action.OBJECT_VALUE, "colour0", value));
        } else { colours(object); }
        if (BoardFlag.ASSET.equals(object.asset())) { flag(object, false); }
        if (billboard) { billboard(object, false); }
        if (BoardFeatures.hasSnowForm(object.asset())) {
            // On by default: the tree takes its winter form on a snow surface; off keeps this tree bare there.
            UiButton snow = button(UiKit.text("BoardEditor.snow"), () -> send(Action.OBJECT_VALUE, "bare", Boolean.toString(!object.bare())));
            snow.setName("editor-object-snow"); snow.pressed(!object.bare());
            ui.tip(snow).getActor().setText(UiKit.text("BoardEditor.snow.tooltip"));
            detail.add(snow).row();
        }
        input(UiKit.text("BoardEditor.name"), object.name() == null ? "" : object.name(), v -> send(Action.OBJECT_VALUE, "name", v));
        detail.add(button(UiKit.text("BoardEditor.useAsBrush"), () -> send(Action.SAMPLE))).row();
        Table commands = new Table(); commands.add(button(UiKit.text("BoardEditor.duplicate"), () -> send(Action.DUPLICATE_OBJECT))).growX();
        commands.add(removeButton(UiKit.text("BoardEditor.remove"), () -> send(Action.REMOVE_OBJECT))).growX().padLeft(4); detail.add(commands).padTop(12).row();
        text(UiKit.text("BoardEditor.objectHint", UiKit.text("BoardEditor.selectHint")));
    }

    /**
     * A junction layout's descriptive name ({@link RailRoute#junctions}: "Crossing", "Turnout to SE", ...), naming its
     * side where it has one; one drawn by strokes that is no listed layout (null) is "As drawn".
     */
    private static String junctionLabel(RailRoute.Junction layout) {
        if (layout == null) { return UiKit.text("BoardEditor.junctionDrawn"); }
        if (layout.name().equals("default")) { return UiKit.text("BoardEditor.defaultDesign"); }
        return UiKit.text("BoardEditor.junction." + layout.name(),
              layout.side() < 0 ? "" : UiKit.text("BoardEditor.sides").split(",")[layout.side()]);
    }

    /**
     * The junction card's gallery, in the design gallery's layer: each sensible layout of the route's sides seen from
     * above, the default first; a pick stores it as the route's tracks, one undo step.
     */
    private void openJunctions(RailRoute.Kind kind, int sides, int current, String terrain) {
        stage.setKeyboardFocus(null); choices.cancel(); numbers.forEach(UiNumber::dismiss);
        designOwner = ""; designHeading.setText(UiTheme.upper(UiKit.text("BoardEditor.junctionTitle")));
        designCards.clearChildren(); designPages.clearChildren();
        var layouts = RailRoute.junctions(sides);
        for (int index = 0; index < layouts.size(); index++) {
            int layout = layouts.get(index).tracks();
            Image image = new Image(); junctions.add(kind, sides, layout, image);
            UiButton card = visualCard(junctionLabel(layouts.get(index)) + (layout == current ? "  ✓" : ""), image, 80, () -> {
                send(Action.WAY_TRACKS, terrain, Integer.toString(layout)); closeDesigns();
            });
            card.setName("editor-junction-" + index);
            designCards.addActor(card);
        }
        designDialog.getCell(designPages).height(0);
        ui.fitSearch(designDialog.getCell(designSearch), false); layoutDesigns();
        designLayer.setVisible(true); designLayer.toFront();
    }
    private Table interiors(int current, java.util.function.Consumer<String> changed) {
        return interiors(current, null, changed);
    }
    private Table interiors(int current, BoardDecoration object, java.util.function.Consumer<String> changed) {
        var labels = List.of("default", "yes", "noFloors", "no");
        List<Option> options = new ArrayList<>();
        boolean tilted = object != null && BuildingVolumes.tilted(object);
        for (int i = 0; i < labels.size(); i++) {
            if (tilted && i < 2) { continue; }
            options.add(new Option(UiKit.text("BoardEditor.interiors." + labels.get(i)), Integer.toString(i)));
        }
        var policy = BuildingInteriors.values()[Math.max(0, Math.min(3, current))];
        if (object != null) { policy = BuildingInteriors.forAuthored(object, policy); }
        Table result = choice(UiKit.text("BoardEditor.interiors"), UiKit.text("BoardEditor.interiors." + labels.get(policy.ordinal())), options, changed);
        if (tilted) { ui.tip(result).getActor().setText(UiKit.text("BoardEditor.buildingTilt")); }
        return result;
    }

    private record Option(String label, String id) { }

    /** Each model's colour slots, read from its file once. */
    private final Map<String, List<RigidGlb.ColourSlot>> colourSlots = new java.util.HashMap<>();

    /**
     * One row per colour slot of the object's model: its preset swatches, then its colour as #rrggbb to type any other.
     * A decal painted in one colour (lettering) has one Paint slot. Each choice is the session's object value; the
     * model's own colour stores nothing.
     */
    private void colours(BoardDecoration object) {
        String paint = object.kind().equals("decal") ? BoardDecalArt.footprint(object.asset()).paint() : null;
        var slots = paint != null ? List.of(new RigidGlb.ColourSlot(UiKit.text("BoardEditor.paint"), paint, List.of()))
              : object.kind().equals("prop") ? colourSlots(object.asset()) : List.<RigidGlb.ColourSlot>of();
        if (slots.isEmpty()) { return; }
        heading(UiKit.text("BoardEditor.colours"));
        colourRows(slots, object.colours(), (slot, value) -> send(Action.OBJECT_VALUE, "colour" + slot, value));
    }

    /**
     * One row per colour slot: its preset swatches, then its colour as #rrggbb to type any other. {@code chosen} gets the
     * slot and the chosen colour, "" for the slot's own (which stores nothing). The colours of an object, a group's
     * members, a truss bridge and a building.
     */
    private void colourRows(List<RigidGlb.ColourSlot> slots, BoardDecoration.Colours colours,
          java.util.function.BiConsumer<Integer, String> chosen) {
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            int slotIndex = index;
            String current = colours.slot(index) == null ? slot.colour() : colours.slot(index);
            Consumer<String> choose = value -> chosen.accept(slotIndex, value.equalsIgnoreCase(slot.colour()) ? "" : value);
            Table swatches = new Table();
            swatches.left();
            for (var preset : slot.presets()) {
                Actor swatch = swatch(preset, preset.colour().equals(current), choose);
                swatch.setName("editor-colour-" + index + "-" + swatches.getCells().size);
                swatches.add(swatch).left();
            }
            detail.add(swatches).left().row();
            input(slot.name(), current, choose);
        }
    }

    private void flag(BoardDecoration object, boolean brush) {
        var flag = object.flag();
        Action action = brush ? Action.BRUSH_VALUE : Action.OBJECT_VALUE;
        String prefix = brush ? "object:" : "";
        List<Actor> controls = new ArrayList<>();
        Consumer<Actor> add = actor -> { if (brush) { controls.add(actor); } else { detail.add(actor).growX().row(); } };
        if (!brush) { heading(UiKit.text("BoardEditor.flag")); }
        add.accept(hint(UiKit.text("BoardEditor.flag.anchor")));
        add.accept(choice(UiKit.text("BoardEditor.flag.pattern"), UiKit.text("BoardEditor.flag.pattern." + flag.pattern()),
              BoardFlag.PATTERNS.stream().map(pattern ->
                    new Option(UiKit.text("BoardEditor.flag.pattern." + pattern), pattern)).toList(),
              value -> send(action, prefix + "flag.pattern", value)));
        List<Option> emblems = new ArrayList<>();
        emblems.add(new Option(UiKit.text("BoardEditor.flag.none"), ""));
        blueprint.assets().stream().filter(asset -> asset.id().startsWith("decal/emblems/"))
              .forEach(asset -> emblems.add(new Option(asset.label(), asset.id())));
        String selected = emblems.stream().filter(option -> option.id().equals(flag.emblem())).map(Option::label)
              .findFirst().orElse(flag.emblem());
        add.accept(choice(UiKit.text("BoardEditor.flag.emblem"), selected, emblems,
              value -> send(action, prefix + "flag.emblem", value)));
        String[] properties = { "x", "y", "scale" };
        double[] values = { flag.x(), flag.y(), flag.scale() };
        for (int i = 0; i < properties.length; i++) {
            String label = UiKit.text("BoardEditor.flag." + properties[i]), target = prefix + "flag." + properties[i];
            double min = i == 2 ? .05 : -1, max = i == 2 ? 4 : 1, step = i == 2 ? .05 : .01;
            add.accept(brush ? brushNumber(label, values[i], min, max, step, action, target)
                  : numeric(label, values[i], min, max, step, action, target));
        }
        add.accept(angle(UiKit.text("BoardEditor.flag.rotation"), flag.rotation(), brush, action, prefix + "flag.rotation"));
        if (brush) {
            var slots = colourSlots(object.asset());
            for (int i = 0; i < slots.size(); i++) {
                int index = i;
                String colour = object.colours().slot(i) == null ? slots.get(i).colour() : object.colours().slot(i);
                add.accept(inputRow(slots.get(i).name(), colour, value -> send(action, prefix + "colour" + index, value)));
            }
            brushRows.add(controls);
        }
    }

    /**
     * A billboard's aspect ratio, scale and panel count, one picture card per panel (each opens the picker on its ratio's
     * pictures), its legs and their height, and its lamps, on the brush or a placed object.
     */
    private void billboard(BoardDecoration object, boolean brush) {
        var billboard = object.billboard();
        Action action = brush ? Action.BRUSH_VALUE : Action.OBJECT_VALUE;
        String prefix = brush ? "object:billboard." : "billboard.";
        int panels = billboard.panels();
        List<Actor> cards = new ArrayList<>();
        for (int i = 0; i < panels; i++) {
            int panel = i;
            String decal = billboard.decals().get(i);
            Image preview = new Image();
            billboardArt(decal, preview);
            UiButton art = visualCard((panels > 1 ? (i + 1) + " · " : "") + billboardLabel(decal), preview, 64,
                  () -> openBillboards(billboard, decal, panels > 1 ? panel + 1 : 0, action, prefix + "decal" + panel));
            art.setName("editor-billboard-art-" + i);
            cards.add(art);
        }
        Table ratio = choice(UiKit.text("BoardEditor.billboard.ratio"), ratioLabel(billboard.ratio()),
              BoardBillboard.RATIOS.stream().map(value -> new Option(ratioLabel(value), value)).toList(),
              value -> send(action, prefix + "ratio", value));
        String count = UiKit.text("BoardEditor.billboard.panels"), scaleLabel = UiKit.text("BoardEditor.billboard.scale");
        UiNumber panelCount = brush ? brushNumber(count, panels, 1, BoardBillboard.MAX_PANELS, 1, action, prefix + "panels")
              : numeric(count, panels, 1, BoardBillboard.MAX_PANELS, 1, action, prefix + "panels");
        UiNumber scale = brush ? brushNumber(scaleLabel, billboard.scale(), .1, 20, .05, action, prefix + "scale")
              : numeric(scaleLabel, billboard.scale(), .1, 20, .05, action, prefix + "scale");
        var legs = ui.checkbox(UiKit.text("BoardEditor.billboard.legs"), billboard.legs());
        legs.setName("editor-billboard-legs");
        onChange(legs, () -> send(action, prefix + "legs", Boolean.toString(legs.isTicked())));
        var lamps = ui.checkbox(UiKit.text("BoardEditor.billboard.lights"), billboard.lights());
        lamps.setName("editor-billboard-lights");
        onChange(lamps, () -> send(action, prefix + "lights", Boolean.toString(lamps.isTicked())));
        UiNumber legHeight = billboard.legs() ? legHeight(object, brush, action) : null;
        if (brush) {
            brushRow(ratio, scale, panelCount);
            if (legHeight != null) { brushRow(legs, legHeight, lamps); } else { brushRow(legs, lamps); }
            brushRow(cards.stream().map(card -> {
                Table holder = new Table();
                holder.add(card).size(panels > 1 ? 112 : 150, 96);
                return (Actor) holder;
            }).toArray(Actor[]::new));
            return;
        }
        heading(UiKit.text("BoardEditor.billboard"));
        detail.add(ratio).growX().row();
        detail.add(scale).row();
        detail.add(panelCount).row();
        cards.forEach(card -> detail.add(card).growX().row());
        detail.add(legs).left().row();
        if (legHeight != null) { detail.add(legHeight).row(); }
        detail.add(lamps).left().row();
        text(UiKit.text("BoardEditor.billboard.hint"));
    }

    /** The placement's height (in levels) shown and scrubbed in metres, as the height of the billboard's legs. */
    private UiNumber legHeight(BoardDecoration object, boolean brush, Action action) {
        long editGeneration = generation;
        String target = (brush ? "object:" : "") + "offset";
        UiNumber number = new UiNumber(ui, stage, UiKit.text("BoardEditor.billboard.legHeight"),
              Math.round(heightOffset(object) * BoardBillboard.LEVEL_METRES * 10) / 10.0, 0, 60, .5, (next, finished) -> source.editorValue(new Command(action, target,
                    Double.toString(Double.parseDouble(next) / BoardBillboard.LEVEL_METRES)), finished, editGeneration));
        number.setName("editor-billboard-leg-height");
        (brush ? brushNumbers : numbers).add(number);
        return number;
    }

    /**
     * Opens the gallery on the billboard's art for one panel ({@code panel}: its number in a stack, else 0): Random and
     * Blank first, then every picture of its ratio's folder.
     */
    private void openBillboards(BoardBillboard billboard, String current, int panel, Action action, String target) {
        stage.setKeyboardFocus(null); choices.cancel(); numbers.forEach(UiNumber::dismiss);
        List<String> art = new ArrayList<>(List.of(BoardBillboard.RANDOM, ""));
        art.addAll(billboard.art());
        billboardCards = art.stream().map(decal -> new GalleryCard(billboardLabel(decal) + (decal.equals(current) ? "  ✓" : ""),
              billboardLabel(decal), image -> billboardArt(decal, image), () -> send(action, target, decal))).toList();
        designOwner = ""; designPage = 0; designQuery = ""; designSearch.field.setText("");
        designHeading.setText(UiTheme.upper(UiKit.text("BoardEditor.billboard.title") + (panel > 0 ? " " + panel : "")
              + " · " + ratioLabel(billboard.ratio())));
        showDesigns(); designLayer.setVisible(true); designLayer.toFront();
    }

    /** A ratio's folder name as it reads, {@code 3_1} as 3:1. */
    private static String ratioLabel(String ratio) { return ratio.replace('_', ':'); }

    private void billboardArt(String decal, Image image) {
        if (decal.startsWith("decal/")) { thumbnail(BoardDecalArt.image(decal), image); }
        else { image.setUserObject(UiKit.text(decal.isEmpty() ? "BoardEditor.billboard.blank.note" : "BoardEditor.billboard.random.note")); }
    }

    private static String billboardLabel(String decal) {
        if (decal.isEmpty()) { return UiKit.text("BoardEditor.billboard.blank"); }
        if (decal.equals(BoardBillboard.RANDOM)) { return UiKit.text("BoardEditor.billboard.random"); }
        String name = decal.substring(decal.lastIndexOf('/') + 1).replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private List<RigidGlb.ColourSlot> colourSlots(String model) {
        if (BoardFlag.ASSET.equals(model)) {
            return java.util.stream.IntStream.range(0, 4).mapToObj(index -> new RigidGlb.ColourSlot(
                  UiKit.text("BoardEditor.flag.colour", Integer.toString(index + 1)),
                  BoardFlag.COLOURS.get(index), BoardBridgeTruss.BODY.presets())).toList();
        }
        return colourSlots.computeIfAbsent(model, asset -> {
            var source = RigidGlb.source(new java.io.File(Configuration.dataDir(), "models/board"), asset);
            try { return source.file().isFile() ? RigidGlb.colourSlots(source.file()) : List.of(); }
            catch (IllegalArgumentException unreadable) { return List.of(); }
        });
    }

    private Actor swatch(RigidGlb.ColourSlot.Preset preset, boolean chosen, Consumer<String> choose) {
        Table frame = new Table();
        frame.setBackground(ui.skin.newDrawable("white", chosen ? UiTheme.MINT : com.badlogic.gdx.graphics.Color.CLEAR));
        frame.add(new Image(ui.skin.newDrawable("white", com.badlogic.gdx.graphics.Color.valueOf(preset.colour())))).size(16).pad(2);
        frame.setTouchable(Touchable.enabled);
        frame.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) { stage.setKeyboardFocus(null); choose.accept(preset.colour()); }
        });
        ui.tip(frame).getActor().setText(preset.label() + " · " + preset.colour());
        return frame;
    }

    /**
     * A whole group's transform, relative to the group as it is: moves from 0, a turn from 0, a scale from 1 and a
     * mirror that flips it. Each commit starts again from these values; offsets and tilt stay per member.
     */
    private void groupTransform() {
        heading(UiKit.text("BoardEditor.groupTransform"));
        text(UiKit.text("BoardEditor.groupTransform.hint"));
        number(UiKit.text("BoardEditor.moveEast"), 0, -4, 4, .01, Action.GROUP_VALUE, "x");
        number(UiKit.text("BoardEditor.moveNorth"), 0, -4, 4, .01, Action.GROUP_VALUE, "y");
        detail.add(angle(UiKit.text("BoardEditor.turn"), 0, false, Action.GROUP_VALUE, "rotation")).row();
        number(UiKit.text("BoardEditor.scaleFactor"), 1, .05, 8, .05, Action.GROUP_VALUE, "scale");
        detail.add(button(UiKit.text("BoardEditor.mirror"), () -> send(Action.GROUP_VALUE, "mirror", "true"))).row();
    }


    private void choose(String label, String selected, List<Option> options, Consumer<String> commit) {
        detail.add(choice(label, selected, options, commit)).row();
    }
    private Table choice(String label, String selected, List<Option> options, Consumer<String> commit) {
        Table row = new Table(); row.add(ui.label(label, "hud-small", 12, UiTheme.MUTED)).left().padRight(8);
        UiButton face = ui.select(selected, false); row.add(face).growX().minWidth(0);
        face.setName("editor-choice-" + label);
        onChange(face, () -> {
            stage.setKeyboardFocus(null); UiMenuList list = new UiMenuList(ui);
            for (Option option : options) {
                list.item(option.label(), null, null, false, true, () -> { choices.cancel(); face.setText(option.label()); commit.accept(option.id()); });
            }
            choices.header(label, null).content(list).openedFrom(face); choices.showAbove(face, 0);
        });
        return row;
    }
    /** The tilesets' themes and the Theme category's special ones (Dirt, Rock), as every theme choice lists them. */
    private List<Option> themeOptions() {
        List<Option> themes = new ArrayList<>();
        java.util.stream.Stream.of(java.util.stream.Stream.of("grass"), source.editorThemes().stream(), blueprint.specialThemes().stream())
              .flatMap(stream -> stream).filter(value -> !value.isEmpty()).distinct().sorted()
              .forEach(value -> themes.add(new Option(themeLabel(value), value)));
        return themes;
    }
    private Table themeChoice(String label, String selected, Consumer<String> commit) {
        Table row = new Table(); row.defaults().growX();
        row.add(ui.caption(label)).left().padBottom(5).row();
        Image preview = new Image(); samples.add(selected, "", "", preview);
        String[] value = { selected };
        UiButton face = visualCard(themeLabel(selected), preview, 64, () -> { });
        face.setName("editor-choice-" + label);
        onChange(face, () -> openThemes(value[0], next -> {
            value[0] = next; face.setText(themeLabel(next));
            preview.setDrawable(null); preview.setUserObject(null); samples.add(next, "", "", preview); commit.accept(next);
        }));
        row.add(face).minWidth(0); return row;
    }
    private void openThemes(String selected, Consumer<String> commit) {
        stage.cancelTouchFocus(); stage.setKeyboardFocus(null); choices.cancel();
        numbers.forEach(UiNumber::dismiss); settingsNumbers.forEach(UiNumber::dismiss);
        selectedTheme = selected.isEmpty() ? "grass" : selected; themeCommit = commit; themeQuery = ""; themeSearch.setText("");
        showThemes(); themeLayer.setVisible(true); themeLayer.toFront();
    }
    private void showThemes() {
        themeCards.clearChildren();
        var themes = themeOptions().stream().filter(option -> option.label().toLowerCase(Locale.ROOT).contains(themeQuery)).toList();
        for (var theme : themes) {
            Image preview = new Image(); samples.add(theme.id(), "", "", preview);
            preview.setName("editor-theme-preview-" + theme.id());
            UiButton card = visualCard(theme.label() + (theme.id().equals(selectedTheme) ? "  ✓" : ""), preview, 84, () -> {
                Consumer<String> commit = themeCommit; closeThemes(); if (commit != null) { commit.accept(theme.id()); }
            });
            card.setName("editor-theme-" + theme.id()); card.pressed(theme.id().equals(selectedTheme));
            themeCards.addActor(card);
        }
        if (themes.isEmpty()) { themeCards.addActor(ui.label(UiKit.text("BoardEditor.noThemes"), "hud-small", 12, UiTheme.MUTED)); }
        layoutThemes();
    }
    private void closeThemes() {
        stage.setKeyboardFocus(null); themeLayer.setVisible(false); themeCards.clearChildren(); themeCommit = null;
    }
    private void number(String label, double value, double min, double max, double step, Action action, String target) {
        detail.add(numeric(label, value, min, max, step, action, target)).row();
    }
    private Table rotations(BoardDecoration object, boolean compact, Action action) {
        String prefix = action == Action.BRUSH_VALUE ? "object:" : "";
        Table row = new Table();
        row.add(ui.label(UiKit.text("BoardEditor.rotation"), "hud-small", 12, UiTheme.TEXT)).colspan(3).left().padBottom(3).row();
        String[] axes = {"X", "Y", "Z"}, keys = {"rotationX", "rotationY", "rotation"};
        String[] descriptions = {UiKit.text("BoardEditor.rotation.pitch"), UiKit.text("BoardEditor.rotation.roll"),
              UiKit.text("BoardEditor.rotation.yaw")};
        double[] values = {object.rotationX(), object.rotationY(), object.rotation()};
        for (int i = 0; i < 3; i++) {
            UiNumber number = angle(axes[i], values[i], compact, action, prefix + keys[i]).compactCaption().fieldSize(48, 27);
            ui.tip(number).getActor().setText(descriptions[i]);
            row.add(number).growX().minWidth(0).padRight(i == 2 ? 0 : 7);
        }
        return row;
    }
    /**
     * Stretch along the object's own width, length and height, on top of its scale (a decal has no height). Each is the
     * session's object value, so a slider drag is one undo step.
     */
    private Table stretch(BoardDecoration object, boolean compact, Action action) {
        Table row = new Table();
        boolean prop = object.kind().equals("prop");
        row.add(ui.label(UiKit.text("BoardEditor.stretch"), "hud-small", 12, UiTheme.TEXT)).colspan(3).left().padBottom(3).row();
        String[] labels = {UiKit.text("BoardEditor.stretch.w"), UiKit.text("BoardEditor.stretch.l"), UiKit.text("BoardEditor.stretch.h")};
        String[] keys = {"stretchX", "stretchY", "stretchZ"};
        String[] descriptions = {UiKit.text("BoardEditor.stretch.width"), UiKit.text("BoardEditor.stretch.length"),
              UiKit.text("BoardEditor.stretch.height")};
        double[] values = {object.stretch().x(), object.stretch().y(), object.stretch().z()};
        String prefix = action == Action.BRUSH_VALUE ? "object:" : "";
        for (int i = 0; i < (prop ? 3 : 2); i++) {
            UiNumber number = (compact ? brushNumber(labels[i], values[i], .1, 8, .05, action, prefix + keys[i])
                  : numeric(labels[i], values[i], .1, 8, .05, action, prefix + keys[i])).compactCaption().fieldSize(48, 27);
            ui.tip(number).getActor().setText(descriptions[i]);
            row.add(number).growX().minWidth(0).padRight(i == 2 ? 0 : 7);
        }
        // A decal has no height: an empty third column keeps W and L in the rotation row's columns.
        if (!prop) { row.add().growX(); }
        return row;
    }
    /**
     * An input for one of the board's rotations. The board turns counter-clockwise (right-handed, seen from the axis's
     * positive end: Z from above), the dial clockwise like a compass, so a drag to the right turns the needle and the
     * object the same way. The input shows the negated value and sends the negation of what it shows.
     */
    private UiNumber angle(String label, double degrees, boolean compact, Action action, String target) {
        long editGeneration = generation;
        UiNumber number = new UiNumber(ui, stage, label, -degrees, -180, 180, 1, (next, finished) -> source.editorValue(
              new Command(action, target, UiNumber.format(-Double.parseDouble(next))), finished, editGeneration)).angle();
        if (compact) { number.setName("editor-brush-" + target); brushNumbers.add(number); } else { numbers.add(number); }
        return number;
    }
    private UiNumber numeric(String label, double value, double min, double max, double step, Action action, String target) {
        long editGeneration = generation;
        UiNumber number = new UiNumber(ui, stage, label, value, min, max, step,
              (next, finished) -> source.editorValue(new Command(action, target, next), finished, editGeneration));
        numbers.add(number); return number;
    }
    private TextField textField(String label, String value, Consumer<String> commit) {
        TextField field = new TextField(value, ui.skin, "hud"); field.setName("editor-" + label);
        final String[] previous = { value };
        field.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused && !field.getText().trim().equals(previous[0])) { previous[0] = field.getText().trim(); commit.accept(previous[0]); }
            }
        });
        field.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int key) {
                if (key == Input.Keys.ESCAPE) { field.setText(previous[0]); stage.setKeyboardFocus(null); return true; }
                if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) { stage.setKeyboardFocus(null); return true; }
                return false;
            }
        });
        return field;
    }
    private void input(String label, String value, Consumer<String> commit) {
        detail.add(inputRow(label, value, commit)).row();
    }
    private Table inputRow(String label, String value, Consumer<String> commit) {
        Table row = new Table(); row.add(ui.label(label, "hud-small", 12, UiTheme.MUTED)).left().padRight(8);
        row.add(textField(label, value, commit)).growX().minWidth(0).height(29); return row;
    }
    /**
     * Board settings in three sections: Map (the board's own settings: Arena, exit roads to pavement, tags), Whole board
     * (commands over every hex, each one undo step: theme, level change, flatten, flood, remove, clear) and Size (a new
     * board). Every control only sends a session command; the session's messages show in the status line under the
     * sections.
     */
    void openSettings() { openSettings(false); }

    /** {@code atSize}: scrolled to the Size section, as the Menu's New opens it. */
    void openSettings(boolean atSize) {
        if (snapshot == null) { return; }
        closeHelp();
        stage.cancelTouchFocus(); numbers.forEach(UiNumber::dismiss); choices.cancel(); clearConfirm.cancel();
        stage.setKeyboardFocus(null); settingsNumbers.forEach(UiNumber::close); settingsNumbers.clear();
        settingsBody.clearChildren(); settingsBody.defaults().growX().pad(5, 6, 5, 6);
        settingsBody.add(ui.caption(UiKit.text("BoardEditor.sectionMap"))).row();
        mapSettings.setName("editor-settings-map"); settingsBody.add(mapSettings).row();
        showMapSettings(snapshot);
        Label wholeBoard = ui.caption(UiKit.text("BoardEditor.sectionWholeBoard")); wholeBoard.setName("editor-settings-whole-board");
        settingsBody.add(wholeBoard).padTop(20).row();
        settingsBody.add(settingsHint(UiKit.text("BoardEditor.globalTheme.hint"))).row();
        String[] theme = { snapshot.theme() };
        settingsMessage = snapshot.message(); settingsResult.setText("");
        settingsBody.add(themeChoice(UiKit.text("BoardEditor.globalTheme"), theme[0], value -> theme[0] = value)).row();
        settingsBody.add(wholeBoardButton(UiKit.text("BoardEditor.applyTheme"), Action.MAP_THEME, "", () -> theme[0])).row();
        settingsBody.add(ui.label(UiKit.text("LCDialog.title"), "hud-small", 12, UiTheme.TEXT)).padTop(10).row();
        int[] levels = { 1 };
        UiNumber raise = settingsNumber(UiKit.text("CommonMenuBar.boardRaise"), levels[0], -30, 30, value -> levels[0] = value)
              .names("editor-settings-raise");
        Table raiseRow = new Table(); raiseRow.add(raise).growX().minWidth(0);
        raiseRow.add(wholeBoardButton(UiKit.text("BoardEditor.apply"), Action.BOARD_LEVEL, "by", () -> Integer.toString(levels[0])))
              .padLeft(6);
        settingsBody.add(raiseRow).row();
        settingsBody.add(settingsHint(UiKit.text("LCDialog.info"))).row();
        settingsBody.add(wholeBoardButton(UiKit.text("CommonMenuBar.boardFlatten"), Action.BOARD_LEVEL, "to", () -> "0")).row();
        settingsBody.add(ui.label(UiKit.text("FloodDialog.title"), "hud-small", 12, UiTheme.TEXT)).padTop(10).row();
        int[] surface = { 0 };
        UiNumber water = settingsNumber(UiKit.text("FloodDialog.surface"), surface[0], -30, 30, value -> surface[0] = value)
              .names("editor-settings-flood-surface");
        settingsBody.add(water).row();
        settingsBody.add(settingsHint(UiKit.text("FloodDialog.info"))).row();
        var remove = ui.checkbox(UiKit.text("FloodDialog.remove"), false); remove.setName("editor-settings-flood-remove"); remove.left();
        ui.tip(remove).getActor().setText(UiKit.text("FloodDialog.remove.tooltip"));
        settingsBody.add(remove).left().row();
        settingsBody.add(settingsHint(UiKit.text("FloodDialog.removeInfo"))).row();
        // The target is read when the button is pressed: "remove" while the box is ticked.
        UiButton flood = wholeBoardButton(UiKit.text("FloodDialog.title"), Action.FLOOD, () -> remove.isTicked() ? "remove" : "",
              () -> Integer.toString(surface[0]));
        flood.setName("editor-settings-flood");
        settingsBody.add(flood).padTop(4).row();
        Table removeRow = new Table(); removeRow.defaults().growX().uniformX().minWidth(0);
        UiButton removeFace = ui.select(UiKit.text("CommonMenuBar.boardRemove"), false); removeFace.setName("editor-settings-remove");
        onChange(removeFace, () -> showRemoveChoices(removeFace));
        removeRow.add(removeFace).height(30);
        UiButton[] clear = new UiButton[1];
        clear[0] = removeButton(UiKit.text("CommonMenuBar.boardClear"), () -> {
            clearConfirm.text(UiKit.text("BoardEditor.clearTitle"), UiKit.text("BoardEditor.clearMsg"),
                  UiKit.text("CommonMenuBar.boardClear"), UiKit.text("Cancel"));
            clearConfirm.actor().toFront();
            clearConfirm.toggle(clear[0], () -> { settingsMessage = null; send(Action.CLEAR); });
        });
        clear[0].setName("editor-settings-clear");
        removeRow.add(clear[0]).height(30).padLeft(6);
        settingsBody.add(removeRow).padTop(10).row();
        Label size = ui.caption(UiKit.text("BoardEditor.sectionSize", snapshot.width(), snapshot.height()));
        size.setName("editor-settings-size"); settingsBody.add(size).padTop(20).row();
        // Resize: the old Resize dialog's four edges, a negative amount cuts that edge; one undo step.
        settingsBody.add(ui.label(UiKit.text("BoardEditor.butExpandMap"), "hud-small", 12, UiTheme.TEXT)).row();
        int[] edges = new int[4];
        Label result = settingsHint(resizeResult(edges)); result.setName("editor-settings-resize-result");
        Table edgeGrid = new Table(); edgeGrid.defaults().growX().uniformX().minWidth(0).padBottom(4);
        String[] sides = { "North", "East", "South", "West" };
        for (int i = 0; i < sides.length; i++) {
            int side = i;
            UiNumber edge = settingsNumber(UiKit.text("ExpandMapDialog.label" + sides[i]), 0, -100, 100, value -> {
                edges[side] = value; result.setText(resizeResult(edges));
            }).names("editor-settings-resize-" + sides[i].toLowerCase(Locale.ROOT));
            ui.tip(edge).getActor().setText(UiKit.text("ExpandMapDialog.map" + sides[i] + "Field.toolTip"));
            edgeGrid.add(edge).padRight(i % 2 == 0 ? 6 : 0);
            if (i % 2 == 1) { edgeGrid.row(); }
        }
        settingsBody.add(edgeGrid).row();
        settingsBody.add(settingsHint(UiKit.text("ExpandMapDialog.mapWestField.note"))).row();
        settingsBody.add(result).row();
        UiButton resize = button(UiKit.text("BoardEditor.butExpandMap"), () -> {
            send(Action.RESIZE, "", edges[0] + "," + edges[1] + "," + edges[2] + "," + edges[3]); closeSettings();
        });
        resize.setName("editor-settings-resize"); settingsBody.add(resize).row();
        settingsBody.add(ui.label(UiKit.text("BoardEditor.newBoardDimensions"), "hud-small", 12, UiTheme.TEXT)).padTop(10).row();
        newWidth = snapshot.width(); newHeight = snapshot.height();
        UiNumber w = new UiNumber(ui, stage, UiKit.text("BoardEditor.labWidth"), newWidth, 1, 100, 1, (v, done) -> newWidth = Integer.parseInt(v));
        UiNumber h = new UiNumber(ui, stage, UiKit.text("BoardEditor.labHeight"), newHeight, 1, 100, 1, (v, done) -> newHeight = Integer.parseInt(v));
        settingsNumbers.add(w); settingsNumbers.add(h); settingsBody.add(w).row(); settingsBody.add(h).row();
        // A blank board, or the old editor's New: the random map dialog for a board of this size.
        Table create = new Table(); create.defaults().growX().uniformX().minWidth(0);
        create.add(button(UiKit.text("BoardEditor.createBoard"), () -> { send(Action.NEW, newWidth + "x" + newHeight); closeSettings(); }));
        UiButton random = button(UiKit.text("BoardEditor.randomBoard"), () -> {
            send(Action.NEW, "random", newWidth + "x" + newHeight); closeSettings();
        });
        random.setName("editor-settings-random"); ui.tip(random).getActor().setText(UiKit.text("BoardEditor.randomBoard.tooltip"));
        create.add(random).padLeft(6);
        settingsBody.add(create).row();
        settingsLayer.setVisible(true); settingsLayer.toFront();
        if (atSize) {
            settingsLayer.validate();
            settingsScroll.setScrollY(settingsBody.getHeight() - size.getY() - size.getHeight());
            settingsScroll.updateVisualScroll();
        }
    }

    /**
     * The Map section from {@code next}: the Arena checkbox, the manual tags as removable chips beside an
     * Add tag field, and the automatic tags, read-only, with Update automatic tags. Rebuilt whenever the board's tags
     * change while the settings show ({@link #refresh}).
     */
    private void showMapSettings(BoardEditorSession.Snapshot next) {
        mapState = next.tags();
        mapSettings.clearChildren(); mapSettings.defaults().growX().minWidth(0).padBottom(5);
        var arena = ui.checkbox(UiKit.text("BoardEditor.cheArena"), next.arena()); arena.setName("editor-settings-arena"); arena.left();
        ui.tip(arena).getActor().setText(UiKit.text("BoardEditor.cheArena.tooltip"));
        onChange(arena, () -> send(Action.BOARD, "arena", Boolean.toString(arena.isTicked())));
        mapSettings.add(arena).left().row();
        mapSettings.add(ui.label(UiKit.text("BoardEditor.tags"), "hud-small", 12, UiTheme.TEXT)).padTop(6).row();
        // Arena has its own checkbox; automatic tags are listed apart, read-only.
        var manual = next.tags().stream().filter(tag -> !tag.equals(megamek.utilities.BoardClassifier.ARENA_TAG)
              && !megamek.utilities.BoardsTagger.isAutoTag(tag)).toList();
        var automatic = next.tags().stream().filter(megamek.utilities.BoardsTagger::isAutoTag).toList();
        if (manual.isEmpty()) { mapSettings.add(settingsHint(UiKit.text("BoardEditor.none"))).row(); }
        else {
            com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup chips = new com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup();
            chips.wrap().rowLeft().space(4).wrapSpace(4); chips.setName("editor-settings-tags");
            for (String tag : manual) {
                Table chip = new Table(); chip.setBackground(ui.skin.getDrawable("chip")); chip.pad(1, 8, 1, 2);
                chip.add(ui.label(tag, "hud-small", 11.5f, UiTheme.TEXT));
                UiButton untag = removeButton("", () -> send(Action.BOARD, "untag", tag)); untag.setName("editor-settings-untag-" + tag);
                chip.add(untag).size(20).padLeft(4);
                chips.addActor(chip);
            }
            mapSettings.add(chips).row();
        }
        TextField addTag = textField("add-tag", "", tag -> { if (!tag.isEmpty()) { send(Action.BOARD, "tag", tag); } });
        addTag.setMessageText(UiKit.text("BoardEditor.addTag"));
        mapSettings.add(addTag).height(29).row();
        mapSettings.add(ui.label(UiKit.text("BoardEditor.autoTags"), "hud-small", 12, UiTheme.TEXT)).padTop(6).row();
        Label auto = settingsHint(automatic.isEmpty() ? UiKit.text("BoardEditor.none") : String.join(", ", automatic));
        auto.setName("editor-settings-auto-tags"); mapSettings.add(auto).row();
        UiButton update = button(UiKit.text("BoardEditor.updateAutoTags"), () -> send(Action.BOARD, "autotags", ""));
        update.setName("editor-settings-update-auto-tags");
        ui.tip(update).getActor().setText(UiKit.text("BoardEditor.updateAutoTags.tooltip"));
        mapSettings.add(update).row();
    }

    /** The whole-board Remove's list: each blueprint component some hex holds; choosing one removes it everywhere. */
    private void showRemoveChoices(UiButton face) {
        stage.setKeyboardFocus(null); UiMenuList list = new UiMenuList(ui); list.setName("editor-settings-remove-list");
        for (String id : snapshot.boardComponents()) {
            list.item(blueprint.component(id).label(), null, null, false, true, () -> {
                choices.cancel(); settingsMessage = null; send(Action.REMOVE_COMPONENT, "board", id);
            }).setName("editor-settings-remove-" + id);
        }
        if (snapshot.boardComponents().isEmpty()) { list.item(UiKit.text("BoardEditor.none"), null, null, false, false, () -> { }); }
        choices.header(UiKit.text("CommonMenuBar.boardRemove"), null).content(list).openedFrom(face);
        choices.showAbove(face, 0);
    }

    /** A Settings button that sends a whole-board command; its message shows in the dialog's status line. */
    private UiButton wholeBoardButton(String label, Action action, String target, Supplier<String> value) {
        return wholeBoardButton(label, action, () -> target, value);
    }

    private UiButton wholeBoardButton(String label, Action action, Supplier<String> target, Supplier<String> value) {
        return button(label, () -> { settingsMessage = null; send(action, target.get(), value.get()); });
    }

    private UiNumber settingsNumber(String label, int value, int min, int max, java.util.function.IntConsumer commit) {
        UiNumber number = new UiNumber(ui, stage, label, value, min, max, 1,
              (v, done) -> commit.accept((int) Math.round(Double.parseDouble(v))));
        settingsNumbers.add(number); return number;
    }

    private Label settingsHint(String text) {
        Label hint = ui.label(text, "hud-small", 11.5f, UiTheme.MUTED); hint.setWrap(true); return hint;
    }

    /** The size Resize gives the board for the {@code edges} north, east, south and west, for example "Result: 18 × 20". */
    private String resizeResult(int[] edges) {
        return UiKit.text("BoardEditor.resizeResult", snapshot.width() + edges[1] + edges[3], snapshot.height() + edges[0] + edges[2]);
    }

    void closeSettings() {
        closeThemes(); stage.setKeyboardFocus(null); choices.cancel(); clearConfirm.cancel();
        settingsNumbers.forEach(UiNumber::close); settingsNumbers.clear(); settingsLayer.setVisible(false);
    }

    /**
     * The editor's own Menu entries, which open its native dialogs, among the document's: New (Settings at its Size
     * section) before Open, and Help before About.
     */
    List<BoardScene.Command> menu(List<BoardScene.Command> commands) {
        List<BoardScene.Command> menu = new ArrayList<>();
        for (BoardScene.Command command : commands) {
            if (command.id().equals("fileBoardOpen")) {
                menu.add(new BoardScene.Command("fileBoardNew", UiKit.text("CommonMenuBar.fileBoardNew"), "", true, false, false,
                      List.of(), () -> openSettings(true)));
            } else if (command.id().equals("helpAbout")) {
                menu.add(new BoardScene.Command("boardEditorHelp", UiKit.text("BoardEditor.help"), "", true, false, false,
                      List.of(), this::openHelp));
            }
            menu.add(command);
        }
        return menu;
    }

    /**
     * Menu → Help: the editor's keys and gestures, the old editor's help and readme in one reference. The tool keys come
     * from {@link #TOOLS}, the camera keys from the key bindings as they are set now and the document keys from the
     * session's table ({@link BoardEditorSession#DOCUMENT_KEYS}), which its key handling reads too.
     */
    void openHelp() {
        closeSettings(); choices.cancel();
        helpBody.clearChildren(); helpBody.top().left();
        helpCaption("BoardEditor.help.tools");
        for (BoardEditorSession.Tool tool : BoardEditorSession.Tool.values()) {
            helpRow(TOOLS.get(tool).keyText(), "BoardEditor.help." + tool.name().toLowerCase(Locale.ROOT));
        }
        helpCaption("GpuBoard.hud.help.mouse");
        HELP_MOUSE.forEach(gesture -> helpRow(UiKit.text(gesture.get(0)), gesture.get(1)));
        helpCaption("BoardEditor.help.keys");
        // The session's own key table; keys sharing a help text share its row ("Ctrl+Y · Ctrl+Shift+Z").
        Map<String, String> documentKeys = new LinkedHashMap<>();
        for (var binding : BoardEditorSession.DOCUMENT_KEYS) {
            documentKeys.merge(binding.help(), KeyCommandBind.getDesc(binding.key(), binding.modifiers()),
                  (first, other) -> first + " · " + other);
        }
        documentKeys.forEach((help, keys) -> helpRow(keys, help));
        helpRow(KeyCommandBind.getDesc(java.awt.event.KeyEvent.VK_ESCAPE, 0), "BoardEditor.help.escape");
        helpCaption("GpuBoard.hud.help.camera");
        var binds = source.uiPreferences().binds();
        for (KeyCommandBind command : HELP_CAMERA) {
            binds.stream().filter(bind -> bind.command() == command && bind.keyCode() != 0).findFirst()
                  .ifPresent(bind -> helpRow(bind.text(), "KeyBinds.cmdNames." + command.cmd));
        }
        helpLayer.setVisible(true); helpLayer.toFront();
    }

    void closeHelp() { helpLayer.setVisible(false); }

    private void helpCaption(String key) {
        helpBody.add(ui.caption(UiKit.text(key))).colspan(2).left().padTop(helpBody.hasChildren() ? 14 : 2).padBottom(4).row();
    }

    /** A key or gesture and what it does, as the game's controls reference shows them: the action, then the key badge. */
    private void helpRow(String keys, String description) {
        Label text = ui.label(UiKit.text(description), "hud-body", 12, UiTheme.TEXT); text.setWrap(true);
        Label badge = ui.label(keys, "hud-name", 11.5f, UiTheme.ACCENT);
        badge.getStyle().background = ui.skin.getDrawable("chip"); badge.setAlignment(com.badlogic.gdx.utils.Align.center);
        badge.setWrap(true);
        helpBody.add(text).growX().minWidth(0).minHeight(26).padTop(2).padBottom(2).padRight(12);
        helpBody.add(badge).width(160).padTop(3).padBottom(3).padRight(10).row();
    }
    /**
     * The triangle's list: the session's issues, then each object inside a wall of a Fitted pavement hex, which only the
     * installed 3D fit knows.
     */
    private void issuesChanged() {
        if (sessionIssues == null) { return; }
        List<HexIssue> merged = new ArrayList<>(sessionIssues);
        for (var cut : shownCuts) {
            merged.add(new HexIssue(cut.at(), cut.object(), UiKit.text("BoardEditor.issueInsideWall", assetLabel(cut.asset()))));
        }
        shownIssues = List.copyOf(merged);
        issues.setVisible(!shownIssues.isEmpty());
        issuesTip.getActor().setText(UiKit.text("BoardEditor.issuesTip", UiKit.text("BoardEditor.issueCount", shownIssues.size())));
        if (issueMenu != null && choices.isVisible() && issueMenu.isDescendantOf(choices)) {
            if (shownIssues.isEmpty()) { choices.cancel(); } else { showIssues(); }
        }
    }

    /** Lists the issues under the triangle; choosing one frames its hex and selects it, and its object if it has one. */
    private void showIssues() {
        if (shownIssues == null || shownIssues.isEmpty()) { return; }
        stage.setKeyboardFocus(null); numbers.forEach(UiNumber::dismiss);
        issueMenu = new UiMenuList(ui); issueMenu.setName("editor-issue-list");
        for (var issue : shownIssues.subList(0, Math.min(ISSUE_ROWS, shownIssues.size()))) {
            var at = issue.coords();
            issueMenu.item(at.getBoardNum() + " · " + issue.text(), null, null, false, true, () -> {
                choices.cancel();
                // Framed once the switch to Select has laid out its corner panels; see update.
                pendingFrame = at; pendingFrameUntil = System.nanoTime() + 3_000_000_000L;
                send(Action.SELECT_AT, at.getX() + "," + at.getY(), issue.object());
            }).setName("editor-issue-" + at.getBoardNum());
        }
        if (shownIssues.size() > ISSUE_ROWS) {
            issueMenu.item(UiKit.text("BoardEditor.issuesMore", shownIssues.size() - ISSUE_ROWS), null, null, false, false, () -> { });
        }
        if (snapshot.copies() > 0) {
            issueMenu.separator();
            issueMenu.item(UiKit.text("BoardEditor.removeCopies"), null, null, false, true, () -> { choices.cancel(); send(Action.REMOVE_COPIES); })
                  .setName("editor-remove-copies");
        }
        choices.header(UiKit.text("BoardEditor.issueCount", shownIssues.size()), UiKit.text("BoardEditor.issuesSaveHint"))
              .content(issueMenu);
        var corner = issues.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
        choices.showAt(corner.x + issues.getWidth(), corner.y - 4);
    }

    /** A bare tool key switches tools; it is consumed, but does nothing, while a gallery, dialog or choice list is open. */
    boolean toolKey(int key) {
        var tool = TOOLS.entrySet().stream().filter(entry -> entry.getValue().key() == key).map(Map.Entry::getKey).findFirst();
        if (tool.isEmpty()) { return false; }
        if (!themeLayer.isVisible() && !designLayer.isVisible() && !settingsLayer.isVisible() && !helpLayer.isVisible()
              && !choices.isVisible()) {
            send(Action.TOOL, tool.get().name());
        }
        return true;
    }

    boolean escape() {
        if (clearConfirm.cancel()) { return true; }
        if (themeLayer.isVisible()) { closeThemes(); return true; }
        if (designLayer.isVisible()) { closeDesigns(); return true; }
        if (choices.cancel()) { return true; }
        if (settingsLayer.isVisible()) { closeSettings(); return true; }
        if (helpLayer.isVisible()) { closeHelp(); return true; }
        return false;
    }

    /** Esc's selection step: clears the selection, as a click beside the board does; false while nothing is selected. */
    boolean clearSelection() {
        if (snapshot == null || snapshot.selected() == null && snapshot.selection().isEmpty()) { return false; }
        send(Action.CLEAR_SELECTION);
        return true;
    }

    /**
     * Esc's step after the selection's: the Assets panel's search clears, a gallery goes back to the page that opened
     * it, a subcategory to its category, a category to the start page.
     */
    boolean escapeAssets() {
        if (!query.isEmpty()) { query = ""; searchField.setText(""); showHead(); showGrid(false); return true; }
        return back();
    }
    private void heading(String text) { Label label = ui.caption(text); label.setWrap(true); detail.add(label).growX().minWidth(0).padTop(12).row(); }
    private void text(String text) {
        Label label = ui.label(text, "hud-small", 11.5f, UiTheme.MUTED); label.setWrap(true); detail.add(label).row();
    }
    @Override public void dispose() {
        numbers.forEach(UiNumber::close); settingsNumbers.forEach(UiNumber::close); choices.cancel();
        brushNumbers.forEach(UiNumber::close);
        thumbnails.dispose(); models.dispose(); junctions.dispose(); samples.dispose(); section.dispose();
    }
}
