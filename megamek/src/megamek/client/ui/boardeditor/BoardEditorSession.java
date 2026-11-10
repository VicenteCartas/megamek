/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.BoardFileFilter;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.RecentBoardList;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.dialogs.randomMap.RandomMapDialog;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.IndustrialElevator;
import megamek.common.board.Board;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardConnectivityCheck;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardGenerator;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BridgeSpan;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexIssue;
import megamek.common.board.HexTile;
import megamek.common.board.LaneJunction;
import megamek.common.board.RailRoute;
import megamek.common.board.SingleLane;
import megamek.common.board.TilesetScenery;
import megamek.common.enums.BuildingType;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import megamek.utilities.BoardClassifier;
import megamek.utilities.BoardsTagger;

/** Standalone editor authority, owned by the EDT. No BoardView, Swing editor panel or client is created. */
public final class BoardEditorSession {
    /** In toolbar, menu and key order: shape the ground, paint it, then clean up. */
    public enum Tool { SELECT, SCULPT, PAINT, ERASE }
    public enum Action {
        TOOL, COMPONENT, BRUSH, ADD_COMPONENT, REMOVE_COMPONENT, TERRAIN, REMOVE_TERRAIN, EDGE,
        ELEVATION, ELEVATOR, THEME, VARIANT, BLEND, ASSET, SELECT_OBJECT, OBJECT_VALUE, DUPLICATE_OBJECT, REMOVE_OBJECT, REORDER_OBJECT,
        COPY, PASTE, UNDO, REDO, NEW, OPEN, SAVE, SAVE_AS, SELECT_AT, MAP_THEME,
        CHOOSE_BRUSH, BRUSH_VALUE, SAMPLE, CLEAR_SELECTION, DELETE_SELECTION, GROUP, UNGROUP, GROUP_VALUE,
        /** Moves a {@link Level} of the inspected hex (target: its key) to an absolute level (value). */
        HEIGHT,
        /**
         * Puts the armed object brush in the inspected hex at "x,y,level" (value): on the target support's surface, or at
         * that fixed level when the target is empty.
         */
        PLACE,
        /**
         * Turns the selected bridge's piers ({@link HexAppearance#PILLARS}) on or off (value "true"/"false") for its whole
         * {@link megamek.common.board.BridgeSpan}, in one undo step; off leaves it a built bridge. On a selected maglev
         * route, its Pillars ({@link BoardDecoration#pillars}) for its whole connected run ({@link RailRoute#run}).
         */
        PILLARS,
        /**
         * Destroys the selected hex's building, fuel tank or bridge (value "true"), as a game leaves it: its ruin under
         * the rubble of its construction ({@link Hex#destroyStructure}), whose look it keeps; or stands it again as it
         * stood, its rubble gone (value "false"). Target "structure": the whole connected structure, standing or
         * destroyed, in one undo step.
         */
        DESTROYED,
        /**
         * Makes the selected bridge's whole {@link megamek.common.board.BridgeSpan} built or natural (value "built" or
         * "natural") in one undo step; a natural bridge has no piers, a built one keeps its Pillars toggle and design.
         */
        BRIDGE_TYPE,
        /**
         * Gives the selected built bridge's whole span one design (value "standard" or "truss", {@link HexAppearance#TRUSS})
         * in one undo step; each hex keeps its piers, a truss keeps the span's colours.
         */
        BRIDGE_DESIGN,
        /**
         * Paints the selected truss bridge's whole span (value "#rrggbb", or "" for the model's own colour) in one undo
         * step: its colour slot, stored per span like a placed object's colours.
         */
        BRIDGE_COLOUR,
        /** Tints the selected building's whole connected structure; an empty value restores its original colours. */
        BUILDING_COLOUR,
        BUILDING_INTERIORS,
        /** The selected hex's upper storeys: keep the ground footprint (straight, default) or step back (stepped). */
        BUILDING_PROFILE,
        SELECT_STRUCTURE,
        /**
         * Gives the selected building's whole connected structure ({@link #structure}) one {@link HexAppearance.Footprint}
         * (value "angled" or "square") in one undo step.
         */
        FOOTPRINT,
        BUILDING_PADDING,
        /**
         * Gives the selected building's whole connected structure ({@link #structure}) one variant of its generated kit
         * (value: the variant's name in the kit's JSON spec, {@link HexAppearance#kitVariant}) in one undo step.
         */
        KIT_VARIANT,
        /**
         * Gives the selected hangar's whole connected structure one roof where its kit's variant offers two (value:
         * {@code vaulted} or {@code flat}, {@link HexAppearance.Roof}) in one undo step.
         */
        BUILDING_ROOF,
        /**
         * Gives the selected building hex a large door, as a hangar has them, on one hexside (target: the side, 0 north,
         * clockwise; value: its height in levels, 0 for none; {@link HexAppearance#doors}).
         */
        BUILDING_DOOR,
        /**
         * Changes the board, not a hex (target: "arena" with value true/false, "tag" or "untag" with a manual
         * tag, "autotags"); see {@link #boardSetting(String, String)}. Not an undo step: the control is its own undo.
         */
        BOARD,
        /** Moves every hex by a number of levels (target "by", value the delta) or to one level ("to"), one undo step. */
        BOARD_LEVEL,
        /** Floods the board up to a surface level (value); target "remove" also clears the submerged hexes. One undo step. */
        FLOOD,
        /** Blanks every hex of the board, as Delete blanks a selected hex. One undo step. */
        CLEAR,
        /**
         * Grows or shrinks the board by "north,east,south,west" hexes (value; a negative amount cuts that edge), see
         * {@link #resize(String)}. One undo step.
         */
        RESIZE,
        /** Sets the inspected hex's deployment zone IDs (value "1, 3", each 1 to 31); none removes its deployment zone. */
        ZONES,
        /** Saves the board as a PNG image of its 2D tileset art, as the old editor's Save As Image did. */
        SAVE_IMAGE,
        /** Opens the document's saved file in the system's program for it (the old Open Board Source File). */
        SOURCE_FILE,
        /**
         * Removes every identical copy ({@link BoardDecoration#twin}) the issues list reports, keeping the first object
         * of each hex and its group. One undo step.
         */
        REMOVE_COPIES,
        /**
         * Turns the inspected hex's skin design ({@link BoardEditorBlueprint.Skin}, a container yard) of an owner (target)
         * to a clockwise rotation (value, degrees, snapped to 30-degree steps). One undo step.
         */
        DESIGN_ROTATION,
        /**
         * Sets the inspected pavement hex's {@link HexAppearance.Edges} (value "auto", "fitted" or "hex"), stored as its
         * pavement appearance (Auto as none). One undo step.
         */
        EDGES, WAY_LAYER, WAY_TRACKS, CORRIDOR,
        /**
         * Sets the inspected fortified hex's {@link HexAppearance.Fortification} (value "dug" or "sandbags"); pavement and
         * roads take sandbags only. One undo step.
         */
        FORTIFICATION,
        /** Sets a road layer's cosmetic junction controls (target "road" or "bridgeRoad", value "stops", "signals" or "gantry"). */
        TRAFFIC_CONTROL,
        /**
         * Narrows the inspected hex's paved ground road to a single, one-way lane or widens it back to two (target "road",
         * value "single" or "two"); a new single lane runs the way its single-lane neighbours do. One undo step.
         */
        LANES,
        /**
         * Makes the single-lane road joining the inspected hex across a side (target "road:" and the side, 0 to 5) its
         * entrance, an on-ramp whose traffic merges and gives way (value "entrance"), or its exit, an off-ramp traffic leaves
         * by (value "exit"): the whole single-lane run turns to match, its far end too ({@link SingleLane}). One undo step.
         */
        RAMP,
        /** Reverses the direction of travel of the inspected hex's whole single-lane run (target "road"). One undo step. */
        LANE_DIRECTION,
        /**
         * Which of the inspected two-lane road's approaches may take the exit (off-ramp) leaving it across a side (target
         * "road:" and the side): value "all", or one approach's side, 0 to 5, stored on that road
         * ({@link HexAppearance#exitFrom}). One undo step.
         */
        RAMP_FROM
    }
    /** Text values are parsed and validated here, never by a view that also owns game state. */
    public record Command(Action action, String target, String value) {
        public Command(Action action) { this(action, "", ""); }
        public Command(Action action, String value) { this(action, "", value); }
    }
    public record Property(String terrain, int value, int exits, boolean explicit) {
        /** The raw terrain code as the board file writes it ({@link Terrain#toString()}), for example "building:2:9". */
        public String code() { return new Terrain(Terrains.getType(terrain), value, explicit, exits).toString(); }
    }
    public record Selection(Coords coords, String object) { }
    /** How a box or a Ctrl-click changes the selection: replace it, add to it (Shift) or remove from it (Ctrl). */
    public enum SelectMode { REPLACE, ADD, REMOVE }
    /**
     * One height of the inspected hex, at its nominal level by the shared support rule ({@link BoardDecoration.Placement#level(Hex)}).
     * {@code key} is "ground" (the hex level, the water surface on a water hex), "water" (its bed), a blueprint component
     * with a height field (a bridge's deck, a structure's top, a canopy) or, when {@code object}, a placed object's id.
     * {@code receiver} is the support an object placed at this level uses, or "" when nothing is placed on it. A level
     * whose {@code min} equals its {@code max} cannot be moved.
     */
    public record Level(String key, boolean object, double level, double min, double max, String receiver) { }
    /**
     * {@code clearOnly}: the old editor's "Only overwrite clear hexes" for Paint and Sculpt; {@code fill}: Paint fills the
     * clicked hex's connected area instead of the radius (both {@link #brush(Coords)}).
     */
    public record Brush(String key, String label, String component, int elevation, String theme,
          List<Property> properties, Map<String, HexAppearance> appearance, List<BoardDecoration> objects, Coords sampledHex,
          boolean elevationEnabled, boolean themeEnabled, boolean precision, boolean magnetic,
          LevelSculpt.Mode sculpt, boolean slope, boolean clearOnly, boolean fill,
          boolean levelHeight, int heightOffset, boolean pillars) {
        /** A hex stamp's objects are contents of the stamp, not an individually placeable object brush. */
        public BoardDecoration object() { return sampledHex == null && !objects.isEmpty() ? objects.getFirst() : null; }
        public Property property(String name) { return BoardEditorSession.property(properties, name); }
        /** Whether this is a Theme card's brush ({@link #THEME_BRUSH}). */
        public boolean themeCard() { return key.startsWith(THEME_BRUSH); }
        public boolean way() {
            return sampledHex == null && (component.equals("road") || component.equals("bridge")
                  || RailRoute.isRoute(object()));
        }
    }
    /**
     * The key prefix of a Theme card's brush, "theme/" and its theme (CHOOSE_BRUSH with target "theme"): Paint gives each
     * hex the theme and the Clear ground look (no ground design) and keeps everything else, ground terrain included; it
     * has no Erase.
     */
    public static final String THEME_BRUSH = "theme/";
    private static Property property(List<Property> properties, String name) {
        return properties.stream().filter(p -> p.terrain().equals(name)).findFirst().orElse(null);
    }
    /**
     * Immutable data published at the EDT/render boundary; it contains no mutable Hex or Board. {@code groups} lists
     * the distinct groups of the selected objects, with "" for an ungrouped selected object. {@code groupSizes} gives
     * the board-wide member count of each group with a member in the selected hex. {@code issues} lists the
     * document's rules and placement problems in hex order, as of the last committed edit. {@code levels} are the
     * selected hex's heights, bottom to top. {@code tags} are the board's tags, sorted; {@code boardComponents} are the
     * blueprint components that the whole-board Remove offers, those some hex holds ({@link #boardComponents()}).
     * {@code drawsRoutes}: a Paint drag with the active brush
     * draws a route by hand, built on its release ({@link #pointer}). {@code copies}: the identical copies the issues
     * list reports, which {@link Action#REMOVE_COPIES} removes. {@code kitVariants}: the variants of the selected
     * building's generated kit ({@link TilesetScenery#kitVariants}), empty where it has none; {@code kitDrawn}: the one
     * it draws. {@code joinedSides}: for
     * the selected hex's water and pavement, the sides that join the same terrain beside it, by terrain name.
     * {@code rampSides}: the sides
     * of the selected two-lane road where a single-lane road joins it ({@link SingleLane#joined}), of which
     * {@code entranceSides} are its entrances (on-ramps) and the rest its exits (off-ramps); {@code laneRun}: the selected
     * hex is on a single-lane run whose direction {@link Action#LANE_DIRECTION} reverses; {@code exitFrom}, side by side,
     * the selected two-lane road's approaches that may take the exit leaving it there ({@link SingleLane#feeds}), 0 where
     * no exit leaves; {@code apronSides}, the sides where its ground road runs into roadless concrete, no roads of its
     * junction ({@link SingleLane#aprons}). The ramp sides leave out a neighbour's bridge deck, as the 3D board does
     * ({@link SingleLane#arms}). {@code junction}: the junction the selected hex's ground road forms with the single lanes
     * joining it, or as a single lane's hub ({@link SingleLane#junction}); null for none.
     */
    public record Snapshot(long revision, String title, boolean dirty, boolean canUndo, boolean canRedo,
          Tool tool, String component, String asset, int brush, Coords selected, int elevation, String theme,
          List<Property> properties, Map<String, HexAppearance> appearance, List<BoardDecoration> objects,
          String object, String message, int width, int height, Brush activeBrush, List<Selection> selection,
          List<Coords> movePreview, List<String> groups, Map<String, Integer> groupSizes, List<HexIssue> issues,
          List<Level> levels, List<String> tags, List<String> boardComponents, boolean drawsRoutes,
          int copies, List<String> kitVariants, List<String> kitVaults, String kitDrawn, boolean buildingConnected, BuildingVolumes.Reference buildingReference, int buildingInteriors,
          boolean buildingProfileAvailable, Map<String, Integer> joinedSides, List<String> destroyed, int rampSides,
          int entranceSides, boolean laneRun, List<Integer> exitFrom, int apronSides, LaneJunction junction) {
        /** Whether the board is a Solaris arena: the tag {@link BoardClassifier#ARENA_TAG}. */
        public boolean arena() { return tags.contains(BoardClassifier.ARENA_TAG); }
        public Property property(String name) { return BoardEditorSession.property(properties, name); }
        /** The group the whole selection forms, or "" when it is not exactly one group. */
        public String group() { return wholeGroup(groups, selection.size()); }
        /** Whether the object belongs to a group that still has another member; a lone member acts ungrouped. */
        public boolean grouped(BoardDecoration object) { return object.group() != null && groupSizes.getOrDefault(object.group(), 0) > 1; }
    }

    /**
     * What the next Paint or Erase pointer event would do ({@link #preview}): each hex it changes as the document holds it
     * now ({@code before}) and as the event and its stroke's completion would leave it ({@code after}), in the order the
     * event changes them. The hexes are copies the caller owns; {@code at} is the hex the event lands on. While a route is
     * drawn by hand, {@code path} is the waypoints a release at {@code at} builds (else empty), {@code notice} says why
     * the release would leave a hex or side of it out, and {@code next} is the step past the path's end that the preview
     * shows too, where the pointer heads (batch 10), but a release does not build (else null).
     */
    public record Preview(Coords at, Map<Coords, Hex> before, Map<Coords, Hex> after, List<Coords> path, String notice,
          Coords next, Map<Coords, WayLayer> layers) {
        public static final Preview NONE = new Preview(null, Map.of(), Map.of(), List.of(), "", null, Map.of());

        /**
         * The hexes where the event leaves a rail or maglev with a side that ends in a stub and did not before: a turn
         * sharper than a gentle pair ({@link RailRoute#stranded} of the sides the route builder draws), which the editor
         * marks red before the release. While a route is drawn by hand, those its release leaves: without the side only
         * the step to {@code next} draws.
         */
        public List<Coords> stubs() {
            List<Coords> result = new ArrayList<>();
            after.forEach((at, hex) -> {
                Hex was = before.get(at);
                int step = stepSide(at);
                for (BoardDecoration marker : RailRoute.derived(hex, "")) {
                    if (!RailRoute.isRoute(marker)) { continue; }
                    int had = RailRoute.derived(was, "").stream().filter(old -> old.id().equals(marker.id()))
                          .mapToInt(old -> RailRoute.drawnSides(old, was)).findFirst().orElse(0);
                    int sides = RailRoute.drawnSides(marker, hex) & ~(step & ~had);
                    if ((RailRoute.stranded(sides) & ~RailRoute.stranded(had)) != 0) {
                        result.add(at);
                        break;
                    }
                }
            });
            return result;
        }

        /** The side of {@code at} that only the step from the path's end to {@code next} draws, as a mask; else 0. */
        private int stepSide(Coords at) {
            if (next == null) { return 0; }
            Coords end = path.getLast(), other = at.equals(end) ? next : at.equals(next) ? end : null;
            if (other == null) { return 0; }
            for (int i = 1; i < path.size(); i++) {
                Coords from = path.get(i - 1), to = path.get(i);
                // The path steps between them itself, so it draws that side too.
                if (from.equals(end) && to.equals(next) || from.equals(next) && to.equals(end)) { return 0; }
            }
            return RailRoute.side(at.direction(other));
        }

        /** The objects the event adds or changes, each with the hex it belongs to afterwards. */
        public List<Map.Entry<Coords, BoardDecoration>> placed() {
            List<Map.Entry<Coords, BoardDecoration>> result = new ArrayList<>();
            after.forEach((at, hex) -> hex.getDecorations().stream().filter(object -> !before.get(at).getDecorations().contains(object))
                  .forEach(object -> result.add(Map.entry(at, object))));
            return result;
        }

        /** The objects the event removes: those whose id none of its hexes holds afterwards. */
        public List<Selection> removed() {
            Set<String> kept = new java.util.HashSet<>();
            after.values().forEach(hex -> hex.getDecorations().forEach(object -> kept.add(object.id())));
            List<Selection> result = new ArrayList<>();
            before.forEach((at, hex) -> hex.getDecorations().stream().filter(object -> !kept.contains(object.id()))
                  .forEach(object -> result.add(new Selection(at, object.id()))));
            return result;
        }
    }

    /** Selections always hold whole groups, except one drilled-in member, so several objects of one group are all of it. */
    private static String wholeGroup(List<String> groups, int selected) {
        return groups.size() == 1 && selected > 1 ? groups.getFirst() : "";
    }

    /**
     * One undo step: the hexes an edit changed, and their neighbours, before and after; or, for a resize ({@code resize}
     * not null), the whole board on each side.
     */
    private record Edit(Map<Coords, Hex> before, Map<Coords, Hex> after, long beforeId, long afterId, Resize resize) {
        Edit(Map<Coords, Hex> before, Map<Coords, Hex> after, long beforeId, long afterId) { this(before, after, beforeId, afterId, null); }
    }
    public enum WaySurface { GROUND, DECK, NEW, REFUSED }
    /** The one layer decision used by a route's write, preview, readout and eraser. */
    public record WayLayer(WaySurface surface, int level, String notice) {
        public boolean deck() { return surface == WaySurface.DECK || surface == WaySurface.NEW; }
        public boolean refused() { return surface == WaySurface.REFUSED; }
    }
    /** A resize's two boards and the import issues of each; its undo and redo install one of them as a whole. */
    private record Resize(Board before, Board after, List<HexIssue> importsBefore, List<HexIssue> importsAfter) { }
    private final Game game = new Game();
    private final BoardEditorBlueprint blueprint;
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<Coords, Hex> stroke = new LinkedHashMap<>();
    private final java.util.Set<Coords> painted = new java.util.HashSet<>();
    private final Map<Coords, WayLayer> wayLayers = new LinkedHashMap<>();
    /** The terrains whose sides the author can part from the same terrain beside them ({@link HexAppearance#separated}). */
    private static final int[] SEPARABLE = { Terrains.WATER, Terrains.PAVEMENT };
    /** Receivers observed by the pointer, retained when the hand-drawn path is replayed. */
    private final Map<Coords, String> wayReceivers = new LinkedHashMap<>();
    private boolean levelHeight, wayPillars;
    private int wayOffset;
    /** Why the current route stroke left a hex or side out (it ends open there); shown until the next stroke. */
    private String strokeNotice = "";
    private Tool tool = Tool.SELECT;
    private String component = "ground", selectedObject = "", message = Messages.getString("BoardEditor.selectHexToEdit");
    /** The brush starts as the Ground card's "clear" preset; its label is the blueprint's (set by the constructor). */
    private String brushComponent = "ground", brushKey = "ground/clear", brushLabel;
    private Hex paintTemplate = new Hex(0);
    /** Non-null for a complete hex stamp; the captured template remains independent of this source hex. */
    private Coords sampledHex;
    private boolean brushElevation, brushTheme, precision;
    private boolean magnetic = true;
    /** Paint and Sculpt leave every hex that holds terrain ({@link #overwrites}). */
    private boolean clearOnly;
    /** Paint fills the clicked hex's connected area ({@link #fillArea}). */
    private boolean fill;
    private final Set<Selection> selection = new LinkedHashSet<>();
    private final Map<Selection, BoardDecoration> movingObjects = new LinkedHashMap<>();
    /** A press on a member of a wholly selected group; the pointer release selects it alone unless the press dragged. */
    private List<Selection> pendingDrill;
    /** Members per group, derived from the board for the committed document version {@code groupsId}. */
    private Map<String, List<Selection>> groups = Map.of();
    private long groupsId = -1;
    /** {@link #boardComponents()} for the committed document version {@code boardComponentsId}. */
    private List<String> boardComponentList = List.of();
    private long boardComponentsId = -1;
    private Coords dragOrigin, moveTarget, routePaintFrom;
    private double dragX, dragY;
    /**
     * Where a route stroke's pointer entered {@link #routePaintFrom}, or took or came back into the last hex of
     * {@link #drawing} (or pressed in it), as {@link #pointer} takes it.
     */
    private double routeEntryX, routeEntryY;
    /** A route drawn by hand that is not released yet ({@link #draw}): the document holds none of it; null otherwise. */
    private RoutePath drawing;
    /**
     * The hex the last event of {@link #drawing} landed in, taken or not: the release builds the route on into it, as the
     * preview shows it ({@link #finishStroke}).
     */
    private Coords drawingAt;
    private Coords selected;
    private int brush = 1;
    private Path path;
    /** Last successful save in this session, retained across Open/New so returning to the lobby can refresh it. */
    private Path lastSavedPath;
    /** An assembled preview already contains the lobby's rotation, theme and elevation adjustments. */
    private boolean fromAssembledPreview;
    private boolean lastSaveFromAssembledPreview;
    /** The .board2 next to an imported legacy file: the import's name and Save's default while {@code path} is null. */
    private Path suggested;
    private Hex clipboard;
    /**
     * Where Copy also puts the hex as text ({@link Hex#getClipboardString}) and where Paste looks for another window's hex
     * first ({@link #pasted}); null keeps copies inside this session. The editor window gives it the system clipboard
     * ({@link #textClipboard(java.awt.datatransfer.Clipboard)}), so sessions that tools and tests create leave it alone.
     */
    private java.awt.datatransfer.Clipboard textClipboard;
    /** The text this session last put on {@link #textClipboard}: Paste then keeps using its own exact copy. */
    private String copiedText;
    private long revision, currentId, savedId, nextId;
    private Set<String> savedTags = Set.of();
    private boolean placed;
    private boolean continuousEdit;
    private LevelSculpt.Mode sculpt = LevelSculpt.Mode.RAISE;
    private boolean slope = true;
    /** Fixed by the first event of a sculpt stroke: the level-mode target and the (Ctrl-inverted) step. */
    private Integer sculptLevel;
    private int sculptStep;
    /** A whole group's members as a relative group edit found them; a live value drag transforms from them. */
    private Map<Selection, BoardDecoration> groupBase = Map.of();
    /** Problems per hex, re-validated for the hexes of every committed edit, undo and redo; never during a stroke. */
    private final Map<Coords, List<HexIssue>> issues = new java.util.HashMap<>();
    /** The identical copies ({@link #stacks}) per hex that holds any, validated with {@link #issues}. */
    private final Map<Coords, Integer> copies = new java.util.HashMap<>();
    private List<HexIssue> issueList = List.of();
    /**
     * What the document's conversions (at open and at each edit's commit) could not decode exactly; each stays listed
     * while its object stays in its hex.
     */
    private List<HexIssue> importIssues = List.of();
    /** Whether an object's model exists, per asset, so validation reads the disk once per asset. */
    private final Map<String, Boolean> modelFiles = new java.util.HashMap<>();
    /** Whether a decal's image exists, per asset; a decal may share a model's asset name. */
    private final Map<String, Boolean> decalImages = new java.util.HashMap<>();
    /** The random map settings, kept between uses of Random map as the old editor kept them; made on first use. */
    private MapSettings randomSettings;
    /**
     * During a preview's dry run ({@link #preview}), the live hexes it is about to change, by hex, each as the board held
     * it before the dry run first touched it; null outside one.
     */
    private Map<Coords, Hex> dryRun;
    /** The last preview and what it was made for: the document revision, the hex, the pointer and the support. */
    private Preview preview = Preview.NONE;
    private List<Object> previewFor = List.of();
    /** Moved less than this share of a hex width in its hex, a route stroke's pointer heads for no side ({@link #heading}). */
    private static final double HEADING = .2;
    /** How far from its centre towards its sides a hex's core reaches, as a share of the way ({@link #core}). */
    private static final double CORE = .6;
    /** The MAGMA level of liquid magma, lava (1 is its crust). */
    private static final int LAVA = 2;

    public BoardEditorSession() { this(BoardEditorBlueprint.get()); }
    public BoardEditorSession(BoardEditorBlueprint blueprint) {
        this.blueprint = blueprint;
        brushLabel = blueprint.component(brushComponent).presets().stream().filter(preset -> brushKey.equals(brushComponent + "/" + preset.id()))
              .map(BoardEditorBlueprint.Preset::label).findFirst().orElse("");
        game.setPhase(GamePhase.LOUNGE);
        replace(Board.createEmptyBoard(16, 17), null);
    }
    public Game game() { return game; }
    public Board board() { return game.getBoard(); }
    public Path path() { return path; }
    /** The current save target, or the native-format suggestion for an imported board. */
    public Path suggestedPath() { return path != null ? path : suggested; }
    public Path lastSavedPath() { return lastSavedPath; }
    public boolean lastSaveFromAssembledPreview() { return lastSaveFromAssembledPreview; }
    /** Copy and Paste also use {@code clipboard} for hexes as text (the editor window passes the system clipboard). */
    public void textClipboard(java.awt.datatransfer.Clipboard clipboard) { textClipboard = clipboard; }
    public boolean dirty() {
        return currentId != savedId || !stroke.isEmpty() || !savedTags.equals(board().getTags());
    }
    public String title() {
        return (dirty() ? "* " : "") + (path != null ? path.getFileName().toString()
              : suggested != null ? Messages.getString("BoardEditor.titleImported", suggested.getFileName().toString())
              : Messages.getString("BoardEditor.untitled"));
    }

    public Snapshot snapshot() {
        Hex hex = selected == null ? null : board().getHex(selected);
        Brush active = new Brush(brushKey, brushLabel, brushComponent, paintTemplate.getLevel(),
              paintTemplate.getTheme() == null ? "" : paintTemplate.getTheme(), properties(paintTemplate),
              paintTemplate.getAppearance(), paintTemplate.getDecorations(), sampledHex,
              brushElevation, brushTheme, precision, magnetic, sculpt, slope, clearOnly, fill,
              levelHeight, wayOffset, wayPillars);
        BoardDecoration prototype = active.object();
        Hex building = buildingSample();
        return new Snapshot(revision, title(), dirty(), !undo.isEmpty(), !redo.isEmpty(), tool, component,
              prototype == null ? "" : prototype.asset(), brush,
              selected, hex == null ? 0 : hex.getLevel(), hex == null || hex.getTheme() == null ? "" : hex.getTheme(),
              properties(hex), hex == null ? Map.of() : hex.getAppearance(), hex == null ? List.of() : contentsOrder(hex),
              selectedObject, message, board().getWidth(), board().getHeight(), active, List.copyOf(selection), movePreview(),
              selectionGroups(), groupSizes(hex), issueList, levels(hex), board().getTags().stream().sorted().toList(),
              boardComponents(), drawsRoutes(),
              copies.values().stream().mapToInt(Integer::intValue).sum(), hex == null ? List.of() : kitVariants(hex),
              hex == null ? List.of() : kitVaults(hex), hex == null ? null : kitDrawn(hex),
              TilesetScenery.buildingConnected(hex, selected, board()::getHex), hex == null ? null : hex.getBuildingReference(),
              building == null ? 0 : BuildingInteriors.choice(building).ordinal(),
              TilesetScenery.buildingProfileAvailable(hex, selected, board()::getHex), joinedSides(), destroyed(hex),
              hex == null || SingleLane.single(hex) ? 0
                    : SingleLane.joined(hex, selected, this::hexAt) & SingleLane.arms(hex, selected, this::hexAt, roadExits(hex)),
              hex == null ? 0 : entrances(hex), hex != null && SingleLane.run(this::hexAt, selected, -1) != null,
              exitFrom(hex), hex == null ? 0 : SingleLane.aprons(hex, selected, this::hexAt, roadExits(hex)),
              hex == null ? null : SingleLane.junction(hex, selected, this::hexAt));
    }

    /**
     * The selected hex's destroyed structures ({@link Hex#getRuins}): the blueprint components whose own terrain is a
     * ruin there. Its rubble is theirs: Layers shows them in its place.
     */
    private List<String> destroyed(Hex hex) {
        if (hex == null || !hex.hasRuins()) { return List.of(); }
        return blueprint.components().stream().filter(component -> !component.present().isEmpty()
              && component.present().stream().map(Terrains::getType)
                    .allMatch(type -> !hex.containsTerrain(type) && hex.getRuin(type) != null))
              .map(BoardEditorBlueprint.Component::id).toList();
    }

    /** The sides a hex's ground road leaves by; 0 without one. */
    private static int roadExits(Hex hex) {
        return hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0;
    }

    /** Side by side, the selected hex's approaches that may take the exit leaving it there; 0 where none leaves. */
    private List<Integer> exitFrom(Hex hex) {
        List<Integer> result = new ArrayList<>();
        for (int side = 0; side < 6; side++) { result.add(hex == null ? 0 : SingleLane.feeds(hex, selected, side, this::hexAt)); }
        return List.copyOf(result);
    }

    /** The hex at {@code at}, or null off the board, as the single-lane runs read the board. */
    private Hex hexAt(Coords at) { return board().contains(at) ? board().getHex(at) : null; }

    /** The selected hex's sides where a joining single-lane road is its entrance, an on-ramp ({@link SingleLane#entrance}). */
    private int entrances(Hex hex) {
        int result = 0;
        for (int side = 0; side < 6; side++) {
            if (SingleLane.entrance(hex, selected, side, this::hexAt)) { result |= 1 << side; }
        }
        return result;
    }

    /**
     * The blueprint components that the whole-board Remove offers: every palette component but Ground that a hex of the
     * board holds (any of its terrain fields), in blueprint order. One board scan per committed document version; a
     * stroke in progress reuses the last result, as {@link #groupSizes} does.
     */
    private List<String> boardComponents() {
        if (stroke.isEmpty() && boardComponentsId != currentId) {
            Map<Integer, String> owners = new java.util.HashMap<>();
            blueprint.components().stream().filter(c -> c.palette() && !c.id().equals("ground")).forEach(c ->
                  c.fields().forEach(field -> owners.putIfAbsent(Terrains.getType(field.terrain()), c.id())));
            Set<String> found = new java.util.HashSet<>();
            for (int y = 0; y < board().getHeight(); y++) {
                for (int x = 0; x < board().getWidth(); x++) {
                    for (int type : board().getHex(x, y).getTerrainTypes()) {
                        if (owners.containsKey(type)) { found.add(owners.get(type)); }
                    }
                }
            }
            boardComponentList = blueprint.components().stream().map(BoardEditorBlueprint.Component::id).filter(found::contains).toList();
            boardComponentsId = currentId;
        }
        return boardComponentList;
    }

    /**
     * The heights of {@code hex} bottom to top: its ground, the water bed, each height field of a present component
     * (bounded by the field's range) and each placed object; a decal lies on its support and cannot be moved.
     */
    private List<Level> levels(Hex hex) {
        if (hex == null) { return List.of(); }
        int ground = hex.getLevel();
        List<Level> levels = new ArrayList<>();
        levels.add(new Level("ground", false, ground, -Double.MAX_VALUE, Double.MAX_VALUE, "ground"));
        if (hex.containsTerrain(Terrains.WATER)) {
            levels.add(new Level("water", false, ground - hex.terrainLevel(Terrains.WATER), -Double.MAX_VALUE, ground, ""));
        }
        for (var component : blueprint.components()) {
            for (var field : component.fields()) {
                Terrain value = hex.getTerrain(Terrains.getType(field.terrain()));
                if (!field.height() || value == null) { continue; }
                // The structures objects stand on; a canopy holds none.
                String receiver = Set.of("bridge", "building", "industrial", "fuelTank").contains(component.receiver())
                      ? component.receiver() : "";
                boolean derived = component.id().equals("building") && hex.getBuildingReference() != null;
                levels.add(new Level(component.id(), false, ground + value.getLevel(), derived ? ground + value.getLevel() : ground + field.min(),
                      derived ? ground + value.getLevel() : ground + Math.max(field.max(), value.getLevel()), receiver));
            }
        }
        for (BoardDecoration object : hex.getDecorations()) {
            double at = object.placement().level(hex);
            // An object whose support is missing shows at the ground, where the editor draws it.
            if (Double.isNaN(at)) { at = ground + object.placement().offset(); }
            // A decal lies on its support, and so does a rail (on the ground or its bridge's deck).
            boolean fixed = object.kind().equals("decal") || RailRoute.kind(object) == RailRoute.Kind.RAIL
                  || BuildingVolumes.of(object.asset()).present();
            levels.add(new Level(object.id(), true, at, fixed ? at : Math.min(at, BoardEditorSnapping.lowestLevel(hex)),
                  fixed ? at : Double.MAX_VALUE, ""));
        }
        levels.sort(Comparator.comparingDouble(Level::level));
        return List.copyOf(levels);
    }

    /**
     * Board-wide member counts of the groups in {@code hex}. A stroke in progress reuses the last committed index, since
     * moves keep membership and a drag's events should not rescan the board.
     */
    private Map<String, Integer> groupSizes(Hex hex) {
        if (hex == null || hex.getDecorations().stream().allMatch(object -> object.group() == null)) { return Map.of(); }
        var index = stroke.isEmpty() ? groups() : groups;
        Map<String, Integer> sizes = new LinkedHashMap<>();
        hex.getDecorations().stream().map(BoardDecoration::group).filter(java.util.Objects::nonNull)
              .forEach(group -> sizes.put(group, index.getOrDefault(group, List.of()).size()));
        return Map.copyOf(sizes);
    }

    /**
     * A hex stamp of {@code hex} named {@code key}, as Use as brush makes one with level and theme: the Assets panel's
     * category icons render through it.
     */
    public static Brush hexStamp(String key, Hex hex) {
        return new Brush(key, key, "ground", hex.getLevel(), hex.getTheme() == null ? "" : hex.getTheme(), properties(hex),
              hex.getAppearance(), hex.getDecorations(), new Coords(0, 0), true, true, false, false, LevelSculpt.Mode.RAISE,
              true, false, false, false, 0, false);
    }

    private Hex buildingSample() {
        if (selected == null) { return null; }
        if (selectedObject.isEmpty()) { return board().getHex(selected); }
        for (int x = 0; x < board().getWidth(); x++) {
            for (int y = 0; y < board().getHeight(); y++) {
                Hex hex = board().getHex(x, y);
                if (hex.getBuildingReference() != null && hex.getBuildingReference().id().equals(selectedObject)) { return hex; }
            }
        }
        return board().getHex(selected);
    }

    private static List<Property> properties(Hex hex) {
        List<Property> properties = new ArrayList<>();
        if (hex != null) {
            for (int type : hex.getTerrainTypes()) {
                Terrain terrain = hex.getTerrain(type);
                properties.add(new Property(Terrains.getName(type), terrain.getLevel(), terrain.getExits(), terrain.hasExitsSpecified()));
            }
            properties.sort(Comparator.comparing(Property::terrain));
        }
        return List.copyOf(properties);
    }

    /**
     * Makes {@code board} the document, converted first ({@link BoardSceneryLayouts#convert}): by its content, not a
     * file name, so a legacy board gets its scenery as objects, every untyped bridge gets its type, and a converted board
     * is never decoded again. The board is then native: every editor document is a .board2 document, where road level 2
     * is the Alley finish. It holds no token the conversion took ({@link BoardSceneryLayouts#withoutTakenTokens}) and its
     * roads hold their connections as stored ones, so the document is exactly what its saved .board2 holds, and its 2D
     * Preview shows what a 2D player of that file sees.
     */
    private void replace(Board board, Path path) {
        List<HexIssue> converted = BoardSceneryLayouts.convert(board);
        List<Coords> every = new ArrayList<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                every.add(new Coords(x, y));
                Terrain road = board.getHex(x, y).getTerrain(Terrains.ROAD);
                if (road != null && !road.hasExitsSpecified()) {
                    board.getHex(x, y).addTerrain(new Terrain(Terrains.ROAD, road.getLevel(), true, road.getExits()));
                }
            }
        }
        dropTakenTokens(board, every);
        this.path = path; suggested = null; importIssues = converted;
        fromAssembledPreview = false;
        undo.clear(); redo.clear();
        currentId = savedId = ++nextId;
        install(board);
        savedTags = Set.copyOf(board.getTags());
    }

    /**
     * Makes {@code board} the game's board: for Open and New ({@link #replace}), a resize and its undo and redo. The views
     * follow {@code game.setBoard} as for any new board (the 3D views and the 2D Preview); the selection and any stroke
     * are reset and every hex is validated.
     */
    private void install(Board board) {
        stroke.clear(); painted.clear(); wayLayers.clear(); wayReceivers.clear(); strokeNotice = ""; drawing = null;
        selected = null; selectedObject = ""; placed = false;
        selection.clear(); movingObjects.clear(); dragOrigin = moveTarget = null; pendingDrill = null;
        game.setBoard(board);
        validateAll();
        revision++;
    }

    /**
     * Opens a board file. A board that breaks the game's rules opens too ({@link BoardFile#read(Path, boolean)}, as the
     * old editor opened one), and the issues list shows its errors; games refuse it until they are fixed.
     */
    public void open(Path file) throws IOException {
        Board loaded = BoardFile.read(file, true); // Failure leaves the document, history and path intact.
        boolean reopeningAssembled = lastSaveFromAssembledPreview && java.nio.file.Files.exists(lastSavedPath)
              && java.nio.file.Files.isSameFile(file, lastSavedPath);
        openDocument(loaded, file);
        fromAssembledPreview = reopeningAssembled;
    }

    /** Reopens a file preview's source sheet, or copies an assembled preview that has no source file. */
    public void open(Board preview, Path file) {
        if (file != null) {
            // A finished preview closes outer building exits; the editable sheet must retain its authored connections.
            try { open(file); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            return;
        }
        openDocument(BoardUtilities.resize(preview, 0, 0, 0, 0), null);
        fromAssembledPreview = true;
    }

    private void openDocument(Board loaded, Path file) {
        if (file == null) {
            replace(loaded, null);
            currentId = ++nextId; // A generated preview has never been saved.
            message = "";
            return;
        }
        // The editor saves only .board2, so another file is never Save's target; the conversion decides by content.
        boolean legacy = !BoardFile.isNativeName(file.toString());
        replace(loaded, legacy ? null : file);
        // Games refuse only the rules errors, not the hex's road and bridge issues or its objects' issues (validate).
        long errors = issues.keySet().stream().mapToLong(at -> board().hexErrors(at).size()).sum();
        String rules = errors == 0 ? "" : " " + Messages.getString("BoardEditor.rulesErrors", errors);
        if (!legacy) { message = Messages.getString("BoardEditor.opened", file.getFileName().toString()) + rules; return; }
        suggested = file.resolveSibling(BoardFile.withoutExtension(file.getFileName().toString()) + BoardFile.EXTENSION);
        long objects = 0;
        Set<String> groups = new java.util.HashSet<>();
        for (int y = 0; y < loaded.getHeight(); y++) {
            for (int x = 0; x < loaded.getWidth(); x++) {
                for (BoardDecoration object : loaded.getHex(x, y).getDecorations()) {
                    objects++;
                    if (object.group() != null) { groups.add(object.group()); }
                }
            }
        }
        message = Messages.getString("BoardEditor.imported", file.getFileName().toString(), objects, groups.size()) + rules;
    }

    public void save(Path file) throws IOException {
        finishStroke();
        // A board without leading comments of its own (a new one) gets the license header, as the old editor wrote it
        // when the Client Settings ask for it; an imported board keeps its own.
        if (board().getSourceHeader() == null && GUIPreferences.getInstance().getBoardSaveIncludeLicense()) {
            board().setSourceHeader(board().licenseHeader());
        }
        BoardFile.save(board(), file);
        lastSavedPath = file;
        lastSaveFromAssembledPreview = fromAssembledPreview;
        path = file;
        savedId = currentId;
        savedTags = Set.copyOf(board().getTags());
        message = Messages.getString("BoardEditor.saved", file.getFileName().toString());
        revision++;
    }

    public void command(Command command, Window owner) {
        command(command, owner, false);
    }

    /** A slider or section drag shares the same undo boundary as a board brush stroke. */
    public void command(Command command, Window owner, boolean continuous) {
        if (!continuous) { finishStroke(); }
        continuousEdit = continuous;
        try {
            switch (command.action()) {
                case TOOL -> tool = Tool.valueOf(command.value());
                case COMPONENT -> {
                    blueprint.component(command.value()); component = command.value(); selectedObject = "";
                    // A destroyed structure's rubble is its own: choosing it chooses the structure, Destroyed shown.
                    var ruined = selected == null ? List.<String>of() : destroyed(board().getHex(selected));
                    if (component.equals("rubble") && !ruined.isEmpty()) { component = ruined.getFirst(); }
                    selection.clear(); if (selected != null) { selection.add(new Selection(selected, "")); }
                }
                case BRUSH -> brush = Math.max(1, Math.min(20, Integer.parseInt(command.value())));
                case CHOOSE_BRUSH -> chooseBrush(command.target(), command.value());
                case BRUSH_VALUE -> configureBrush(command.target(), command.value());
                case SAMPLE -> sampleBrush();
                case CLEAR_SELECTION -> { selection.clear(); selected = null; selectedObject = ""; }
                case DELETE_SELECTION -> deleteSelection();
                case ADD_COMPONENT -> edit(hex -> addComponent(hex, blueprint.component(command.value())));
                case REMOVE_COMPONENT -> {
                    var definition = blueprint.component(command.value());
                    // Target "board": from every hex (Settings â†’ Whole board â†’ Remove), else from the selected hex.
                    if (!command.target().equals("board")) { edit(hex -> removeComponent(hex, definition)); }
                    else {
                        int changed = everyHex(hex -> removeComponent(hex, definition));
                        message = changed == 0 ? Messages.getString("BoardEditor.nothingChanged")
                              : Messages.getString("BoardEditor.removedComponent", definition.label(), changed);
                    }
                }
                case TERRAIN -> edit(hex -> setTerrain(hex, command.target(), Integer.parseInt(command.value())));
                case REMOVE_TERRAIN -> edit(hex -> {
                    int type = Terrains.getType(command.value());
                    if (type == Terrains.BRIDGE) { removeDeck(hex); }
                    else if (type == Terrains.BUILDING) { removeComponent(hex, blueprint.component("building")); }
                    else { removeWay(hex, type); }
                    if (!hex.containsAnyTerrainOf(Terrains.WOODS, Terrains.JUNGLE)) { hex.removeTerrain(Terrains.FOLIAGE_ELEV); }
                });
                case EDGE -> editEdge(command.target(), Integer.parseInt(command.value()));
                case ELEVATION -> edit(hex -> hex.setLevel(Integer.parseInt(command.value())));
                case ELEVATOR -> edit(hex -> {
                    Terrain old = hex.getTerrain(Terrains.INDUSTRIAL_ELEVATOR);
                    require(old != null, Messages.getString("BoardEditor.IndustrialElevatorDialog.addFirst"));
                    int top = IndustrialElevator.shaftTop(old.getExits()), capacity = IndustrialElevator.capacityTons(old.getExits());
                    if (command.target().equals("top")) { top = Integer.parseInt(command.value()); }
                    else if (command.target().equals("capacity")) { capacity = Integer.parseInt(command.value()); }
                    else { throw new IllegalArgumentException("Unknown elevator property"); }
                    require(top >= 0 && top <= IndustrialElevator.CAPACITY_MASK, Messages.getString("BoardEditor.IndustrialElevatorDialog.shaftTopRange"));
                    require(capacity >= 0 && capacity <= 2550 && capacity % 10 == 0,
                          Messages.getString("BoardEditor.IndustrialElevatorDialog.capacityRange"));
                    // The shared rule (Terrain#isValid) refuses a top below the shaft bottom.
                    Terrain next = new Terrain(Terrains.INDUSTRIAL_ELEVATOR, old.getLevel(), true, IndustrialElevator.exits(top, capacity));
                    List<String> errors = new ArrayList<>(); require(next.isValid(errors), String.join("\n", errors));
                    hex.addTerrain(next);
                });
                case THEME -> edit(hex -> hex.setTheme(command.value()));
                case MAP_THEME -> {
                    everyHex(hex -> hex.setTheme(command.value()));
                    message = Messages.getString("BoardEditor.themeApplied");
                }
                case BOARD -> boardSetting(command.target(), command.value());
                case BOARD_LEVEL -> {
                    int value = Integer.parseInt(command.value());
                    require(command.target().equals("by") || command.target().equals("to"), "Move the board by or to a level.");
                    boolean to = command.target().equals("to");
                    int changed = everyHex(hex -> hex.shiftLevel(to ? value - hex.getLevel() : value));
                    message = changed == 0 ? Messages.getString("BoardEditor.nothingChanged") : to
                          ? Messages.getString("BoardEditor.boardLevelTo", value) : Messages.getString("BoardEditor.boardLevelBy", value);
                }
                case FLOOD -> {
                    int surface = Integer.parseInt(command.value());
                    int changed = flood(surface, command.target().equals("remove"));
                    message = changed == 0 ? Messages.getString("BoardEditor.nothingChanged")
                          : Messages.getString("FloodDialog.done", changed, surface);
                }
                case CLEAR -> {
                    everyHex(hex -> copyInto(new Hex(0), hex));
                    message = Messages.getString("BoardEditor.cleared");
                }
                case RESIZE -> resize(command.value());
                case ZONES -> {
                    int mask = zoneMask(command.value());
                    edit(hex -> setZones(hex, mask));
                }
                case VARIANT -> edit(hex -> setVariant(hex, command.target(), command.value()));
                case BLEND -> edit(hex -> {
                    var styles = new LinkedHashMap<>(hex.getAppearance()); var current = styles.get("ground");
                    require(current != null && blueprint.variant(current.variant()) != null
                          && blueprint.variant(current.variant()).blend(), Messages.getString("BoardEditor.blendFirst"));
                    styles.put("ground", new HexAppearance(current.variant(), null, null, Double.parseDouble(command.value())));
                    hex.setAppearance(styles);
                });
                case DESIGN_ROTATION -> edit(hex -> rotate(hex, command.target(), command.value(),
                      TilesetScenery.buildingConnected(hex, hex.getCoords(), board()::getHex)));
                case EDGES -> edit(hex -> setEdges(hex, command.value()));
                case FORTIFICATION -> edit(hex -> setFortification(hex, command.value()));
                case TRAFFIC_CONTROL -> edit(hex -> setTrafficControl(hex, command.target(), command.value()));
                case LANES -> edit(hex -> setLanes(hex, command.target(), command.value()));
                case RAMP -> ramp(command.target(), command.value());
                case LANE_DIRECTION -> reverseLane(command.target());
                case RAMP_FROM -> rampFrom(command.target(), command.value());
                case ASSET -> {
                    var design = blueprint.asset(command.value()); require(design != null, "Unknown object design");
                    paintTemplate = new Hex(0); sampledHex = null;
                    // A stamp brush keeps only its id; each press stamps the layout's members (BoardSceneryLayouts.stamp).
                    if (design.layout() == null) {
                        // An alias entry (a pond drawn as a pool model) keeps its palette label as the object's name.
                        paintTemplate.setDecorations(List.of(new BoardDecoration("brush", design.kind(), design.model(),
                              design.model().equals(design.id()) ? null : design.label(),
                              0, 0, 0, false, 1, BoardDecoration.Placement.surface("ground", "top",
                                    // A maglev is suspended a level up, a billboard's panel on its legs; a rail lies on the ground.
                                    megamek.common.board.RailRoute.Kind.of(design.id()) == megamek.common.board.RailRoute.Kind.MAGLEV ? 1
                                          : BoardBillboard.ASSET.equals(design.model()) ? BoardBillboard.DEFAULT_LEVELS : 0), 0, false)
                              .withColours(design.colours())
                              .withBillboard(BoardBillboard.ASSET.equals(design.model())
                                    ? BoardBillboard.DEFAULT.with("decal0", BoardBillboard.RANDOM) : BoardBillboard.DEFAULT)));
                    }
                    if (BuildingVolumes.of(design.model()).present()) {
                        var building = paintTemplate.getDecorations().getFirst();
                        paintTemplate = new Hex(0, "building:2;bldg_cf:60;bldg_elev:3;bldg_basement_type:1", null);
                        paintTemplate.setDecorations(List.of(building.withLevels(BuildingVolumes.of(design.model()).modular() ? 3 : 0)));
                    }
                    levelHeight = false; wayOffset = RailRoute.Kind.of(design.id()) == RailRoute.Kind.MAGLEV ? 1 : 0;
                    wayPillars = wayOffset > 0;
                    brushComponent = ""; brushKey = design.id(); brushLabel = design.label(); tool = Tool.PAINT;
                    message = "";
                }
                case SELECT_OBJECT -> {
                    tool = Tool.SELECT;
                    if (command.target().isEmpty()) {
                        selectedObject = command.value(); selection.clear(); selection.add(new Selection(selected, selectedObject));
                    } else {
                        // A group target selects the whole group; the inspected hex's first member stays the primary.
                        var members = groups().getOrDefault(command.target(), List.of());
                        require(!members.isEmpty(), Messages.getString("BoardEditor.groupGone"));
                        selection.clear(); selection.addAll(members);
                        var primary = members.stream().filter(item -> item.coords().equals(selected)).findFirst().orElse(members.getFirst());
                        selected = primary.coords(); selectedObject = primary.object();
                    }
                }
                case OBJECT_VALUE -> editObject(command.target(), command.value());
                case WAY_LAYER -> edit(hex -> moveWay(hex, command.target(), command.value().equals("deck")));
                case CORRIDOR -> corridor(command.target(), command.value());
                case WAY_TRACKS -> edit(hex -> {
                    boolean deck = command.target().equals("bridge_rail");
                    int type = deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
                    Terrain rail = hex.getTerrain(type);
                    int tracks = Integer.parseInt(command.value());
                    require(rail != null && RailRoute.valid(rail.getExits(), tracks), Messages.getString("BoardEditor.junctionInvalid"));
                    RailRoute.lay(hex, deck, rail.getExits(), RailRoute.layout(rail.getExits(), tracks),
                          rail.getLevel() == 2 ? RailRoute.Kind.MAGLEV : RailRoute.Kind.RAIL);
                });
                case HEIGHT -> height(command.target(), Double.parseDouble(command.value()));
                case PLACE -> place(command.target(), command.value());
                case PILLARS -> {
                    boolean on = Boolean.parseBoolean(command.value());
                    requireBridge();
                    var truss = spanTruss(selected);
                    var type = HexAppearance.built(truss != null, on, truss);
                    bridgeSpan(selected, current -> {
                        if (current != null && HexAppearance.implicit(Map.of("bridge", current))) {
                            return on ? HexAppearance.IMPLICIT_PILLARS : HexAppearance.IMPLICIT_BRIDGE;
                        }
                        return type;
                    });
                    if (!continuousEdit) { finishStroke(); }
                }
                case SELECT_STRUCTURE -> {
                    var reference = selected == null ? null : board().getHex(selected).getBuildingReference();
                    if (reference != null) {
                        selected = reference.anchor(); selectedObject = reference.id(); selection.clear();
                        selection.add(new Selection(selected, selectedObject)); tool = Tool.SELECT;
                    }
                }
                case BUILDING_INTERIORS -> {
                    if (selected != null) {
                        var choice = BuildingInteriors.values()[Integer.parseInt(command.value())];
                        var object = selectedObject.isEmpty() ? null : object(board().getHex(selected));
                        String id = object != null && BuildingVolumes.of(object.asset()).present() ? object.id() : null;
                        if (id == null) { edit(hex -> BuildingInteriors.set(hex, choice)); }
                        else {
                            for (int x = 0; x < board().getWidth(); x++) {
                                for (int y = 0; y < board().getHeight(); y++) {
                                    Coords cell = new Coords(x, y);
                                    var ref = board().getHex(cell).getBuildingReference();
                                    if (ref != null && ref.id().equals(id)) { change(cell, hex -> BuildingInteriors.set(hex, choice)); }
                                }
                            }
                            if (!continuousEdit) { finishStroke(); }
                        }
                    }
                }
                case BUILDING_PROFILE -> {
                    require(selected != null && TilesetScenery.buildingProfileAvailable(board().getHex(selected), selected, board()::getHex),
                          "Select a modular building hex with a shorter connected neighbour.");
                    require(command.value().equals("straight") || command.value().equals("stepped"), "Unknown building profile");
                    change(selected, hex -> appearance(hex, "buildingProfile",
                          command.value().equals("stepped") ? HexAppearance.STEPPED_BUILDING : null));
                    if (!continuousEdit) { finishStroke(); }
                }
                case BUILDING_COLOUR -> {
                    require(selected != null && board().getHex(selected).containsTerrain(Terrains.BUILDING),
                          Messages.getString("BoardEditor.selectBuildingFirst"));
                    var colours = BoardDecoration.Colours.NONE.with(0, command.value().isEmpty() ? null : command.value());
                    for (Coords cell : structure(selected, Terrains.BUILDING)) {
                        change(cell, hex -> appearance(hex, "building", HexAppearance.withColours(hex.getAppearance().get("building"), colours)));
                    }
                    if (!continuousEdit) { finishStroke(); }
                }
                case FOOTPRINT -> {
                    require(selected != null && board().getHex(selected).containsTerrain(Terrains.BUILDING),
                          Messages.getString("BoardEditor.selectBuildingFirst"));
                    var footprint = HexAppearance.Footprint.valueOf(command.value().toUpperCase(java.util.Locale.ROOT));
                    for (Coords cell : structure(selected, Terrains.BUILDING)) { change(cell, hex -> footprint(hex, footprint)); }
                    if (!continuousEdit) { finishStroke(); }
                }
                case BUILDING_PADDING -> {
                    require(selected != null && board().getHex(selected).containsTerrain(Terrains.BUILDING),
                          Messages.getString("BoardEditor.selectBuildingFirst"));
                    double padding = Double.parseDouble(command.value());
                    edit(hex -> appearance(hex, "building", HexAppearance.withPadding(hex.getAppearance().get("building"), padding)));
                    if (!continuousEdit) { finishStroke(); }
                }
                case KIT_VARIANT -> {
                    require(selected != null && board().getHex(selected).containsTerrain(Terrains.BUILDING),
                          Messages.getString("BoardEditor.selectBuildingFirst"));
                    require(kitVariants(board().getHex(selected)).contains(command.value()),
                          "Choose one of the building's variants.");
                    for (Coords cell : structure(selected, Terrains.BUILDING)) {
                        change(cell, hex -> kitVariant(hex, command.value()));
                    }
                    if (!continuousEdit) { finishStroke(); }
                }
                case BUILDING_ROOF -> {
                    require(selected != null && board().getHex(selected).containsTerrain(Terrains.BUILDING),
                          Messages.getString("BoardEditor.selectBuildingFirst"));
                    require(List.of("vaulted", "flat").contains(command.value()), "Choose a vaulted or a flat roof.");
                    var roof = HexAppearance.of(command.value().equals("vaulted") ? HexAppearance.Roof.VAULTED : HexAppearance.Roof.FLAT);
                    for (Coords cell : structure(selected, Terrains.BUILDING)) {
                        change(cell, hex -> appearance(hex, "roof", roof));
                    }
                    if (!continuousEdit) { finishStroke(); }
                }
                case BUILDING_DOOR -> {
                    require(selected != null && board().getHex(selected).containsTerrain(Terrains.BUILDING),
                          Messages.getString("BoardEditor.selectBuildingFirst"));
                    int side = Integer.parseInt(command.target()), height = (int) Math.round(Double.parseDouble(command.value()));
                    require(side >= 0 && side < 6 && height >= 0, "A door stands on one of the six hexsides.");
                    require(height == 0 || !board().getHex(selected).containsTerrainExit(Terrains.BUILDING, side),
                          Messages.getString("BoardEditor.doorNeedsWall"));
                    edit(hex -> {
                        int[] doors = HexAppearance.doors(hex.getAppearance());
                        doors[side] = Math.min(height, Math.max(1, hex.terrainLevel(Terrains.BLDG_ELEV)));
                        appearance(hex, "doors", HexAppearance.ofDoors(doors));
                    });
                    if (!continuousEdit) { finishStroke(); }
                }
                case DESTROYED -> {
                    require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
                    int type = structureKind(board().getHex(selected));
                    require(type != 0, Messages.getString("BoardEditor.selectStructureFirst"));
                    boolean destroy = Boolean.parseBoolean(command.value());
                    var cells = command.target().equals("structure") ? connected(selected, type, hex -> standingOrRuin(hex, type))
                          : List.of(selected);
                    for (Coords cell : cells) {
                        change(cell, hex -> {
                            if (!destroy) { hex.restoreRuins(); }
                            else if (hex.containsTerrain(type)) { hex.destroyStructure(construction(hex)); }
                        });
                    }
                    if (!continuousEdit) { finishStroke(); }
                }
                case BRIDGE_TYPE -> {
                    requireBridge();
                    require(command.value().equals("built") || command.value().equals("natural"), "Choose built or natural.");
                    typeSpan(selected, command.value().equals("built"), null);
                    if (!continuousEdit) { finishStroke(); }
                }
                case BRIDGE_DESIGN -> {
                    requireBridge();
                    require(command.value().equals("standard") || command.value().equals("truss"), "Choose standard or truss.");
                    require(Boolean.TRUE.equals(HexAppearance.bridgeBuilt(board().getHex(selected).getAppearance())),
                          Messages.getString("BoardEditor.builtFirst"));
                    var truss = command.value().equals("truss") ? Objects.requireNonNullElse(spanTruss(selected),
                          BoardDecoration.Colours.NONE) : null;
                    bridgeSpan(selected, current -> HexAppearance.built(truss != null, piers(current), truss));
                    if (!continuousEdit) { finishStroke(); }
                }
                case BRIDGE_COLOUR -> {
                    requireBridge();
                    require(spanTruss(selected) != null, Messages.getString("BoardEditor.trussFirst"));
                    var truss = BoardDecoration.Colours.NONE.with(0, command.value().isEmpty() ? null : command.value());
                    bridgeSpan(selected, current -> HexAppearance.built(true, piers(current), truss));
                    if (!continuousEdit) { finishStroke(); }
                }
                case GROUP -> group();
                case UNGROUP -> ungroup();
                case GROUP_VALUE -> editGroup(command.target(), command.value());
                case REORDER_OBJECT -> edit(hex -> reorderObject(hex, command.target(), command.value()));
                case DUPLICATE_OBJECT -> {
                    if (!wholeGroup().isEmpty()) { duplicateGroup(); }
                    else {
                        edit(hex -> {
                            require(!megamek.common.board.RailRoute.isRoute(object(hex)), Messages.getString("BoardEditor.routeExtend"));
                            BoardDecoration copy = object(hex).duplicate();
                            var objects = new ArrayList<>(hex.getDecorations()); objects.add(copy); hex.setDecorations(objects); selectedObject = copy.id();
                        });
                    }
                }
                case REMOVE_OBJECT -> {
                    String id = command.target().isEmpty() ? selectedObject : command.target();
                    require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
                    change(selected, hex -> hex.setDecorations(hex.getDecorations().stream().filter(d -> !d.id().equals(id)).toList()));
                    unlinkRoutes(List.of(selected));
                    if (!continuousEdit) { finishStroke(); }
                    selection.removeIf(item -> item.object().equals(id));
                    if (selectedObject.equals(id)) { selectedObject = ""; }
                }
                case COPY -> {
                    require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
                    copy(board().getHex(selected)); message = Messages.getString("BoardEditor.copied");
                }
                case PASTE -> {
                    Hex source = pasted();
                    require(source != null, Messages.getString("BoardEditor.copyHexFirst"));
                    require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
                    change(selected, hex -> copyInto(source, hex));
                    keepJoinedSides(List.of(selected));
                    if (!continuousEdit) { finishStroke(); }
                }
                case UNDO -> history(undo, redo, false);
                case REDO -> history(redo, undo, true);
                case OPEN -> {
                    // A value opens that file (a Recent Boards entry); none asks for one.
                    if (confirmDiscard(owner)) {
                        if (command.value().isEmpty()) { chooseOpen(owner); }
                        else { openChosen(Path.of(command.value())); }
                    }
                }
                case SAVE, SAVE_AS -> chooseSave(owner, command.action() == Action.SAVE_AS);
                case SAVE_IMAGE -> chooseSaveImage(owner);
                case SOURCE_FILE -> openSourceFile();
                case REMOVE_COPIES -> removeCopies();
                case NEW -> {
                    String[] size = command.value().split("[,x ]+");
                    require(size.length == 2, Messages.getString("BoardEditor.newSizeFormat"));
                    int width = Integer.parseInt(size[0]), height = Integer.parseInt(size[1]);
                    require(width > 0 && height > 0 && (long) width * height <= 1_000_000, Messages.getString("BoardEditor.newSizeInvalid"));
                    // Target "random": the old editor's New, a board from the random map generator.
                    if (confirmDiscard(owner)) {
                        if (command.target().equals("random")) { chooseRandom(owner, width, height); }
                        else { replace(Board.createEmptyBoard(width, height), null); message = Messages.getString("BoardEditor.newBoardDone", width, height); }
                    }
                }
                case SELECT_AT -> {
                    // An issue's hex, and its object when the issue concerns one that is still there.
                    String[] cell = command.target().split(",");
                    require(cell.length == 2, "Choose a hex as x,y.");
                    Coords at = new Coords(Integer.parseInt(cell[0].trim()), Integer.parseInt(cell[1].trim()));
                    require(board().contains(at), Messages.getString("BoardEditor.outsideBoard"));
                    boolean present = board().getHex(at).getDecorations().stream().anyMatch(d -> d.id().equals(command.value()));
                    tool = Tool.SELECT; selected = at; selectedObject = present ? command.value() : ""; pendingDrill = null;
                    if (selectedObject.isEmpty()) { component = "ground"; }
                    selection.clear(); selection.add(new Selection(at, selectedObject));
                }
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            message = failure.getMessage() == null ? Messages.getString("BoardEditor.invalidValue") : failure.getMessage();
        } finally { continuousEdit = false; }
        revision++;
    }

    /** A document key: its key code and exact modifiers, its action and the help text that names it. */
    public record DocumentKey(int key, int modifiers, Action action, String help) { }

    /** The document's keys, the one table {@link #key} acts on and the editor's Help lists (in Help's order). */
    public static final List<DocumentKey> DOCUMENT_KEYS = List.of(
          new DocumentKey(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, Action.UNDO, "BoardEditor.help.undo"),
          new DocumentKey(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, Action.REDO, "BoardEditor.help.redo"),
          new DocumentKey(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, Action.REDO,
                "BoardEditor.help.redo"),
          new DocumentKey(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, Action.COPY, "BoardEditor.help.copy"),
          new DocumentKey(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK, Action.PASTE, "BoardEditor.help.paste"),
          new DocumentKey(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK, Action.SAVE, "BoardEditor.help.save"),
          new DocumentKey(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK, Action.OPEN, "BoardEditor.help.open"),
          new DocumentKey(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK, Action.GROUP, "BoardEditor.help.group"),
          new DocumentKey(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, Action.UNGROUP,
                "BoardEditor.help.ungroup"),
          new DocumentKey(KeyEvent.VK_DELETE, 0, Action.DELETE_SELECTION, "BoardEditor.help.delete"));

    public void key(int key, int modifiers, Window owner) {
        DOCUMENT_KEYS.stream().filter(binding -> binding.key() == key && binding.modifiers() == modifiers)
              .map(DocumentKey::action).filter(action -> action != Action.DELETE_SELECTION || !selection.isEmpty())
              .findFirst().ifPresent(action -> command(new Command(action), owner));
    }

    public boolean confirmDiscard(Window owner) {
        finishStroke();
        if (!dirty()) { return true; }
        int answer = JOptionPane.showConfirmDialog(owner, Messages.getString("BoardEditor.unsavedMessage"),
              Messages.getString("BoardEditor.unsavedTitle"), JOptionPane.YES_NO_CANCEL_OPTION);
        return answer == JOptionPane.NO_OPTION || answer == JOptionPane.YES_OPTION && chooseSave(owner, false);
    }

    public boolean chooseOpen(Window owner) throws IOException {
        JFileChooser chooser = new JFileChooser(Configuration.boardsDir()); chooser.setFileFilter(new BoardFileFilter());
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) { return false; }
        openChosen(chooser.getSelectedFile().toPath()); return true;
    }

    /** User-facing Open, shared by the Swing and Scene2D document dialogs. */
    public void openChosen(Path file) throws IOException { open(file); remember(file); }

    /** User-facing Save, shared by the Swing and Scene2D document dialogs. */
    public void saveChosen(Path file) throws IOException { save(file); remember(file); }

    /**
     * Adds a file the user opened or saved to the Recent Boards list that the launcher and the editor's menu show. Only
     * the user's own Open, Save and Recent Boards choices call it, not {@link #open}/{@link #save} themselves, so tests
     * and tools that use those leave the list alone.
     */
    private static void remember(Path file) {
        RecentBoardList.addBoard(file.toAbsolutePath().normalize().toString());
    }

    public boolean chooseSave(Window owner, boolean saveAs) {
        // As in the old editor: the rules errors are listed, then the board is saved anyway.
        List<String> errors = new ArrayList<>();
        if (!board().isValid(errors)) { showRulesErrors(owner, errors); }
        Path target = path;
        if (saveAs || target == null) {
            Path initial = path != null ? path : suggested;
            JFileChooser chooser = new JFileChooser(initial == null ? Configuration.boardsDir() : initial.toFile().getParentFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(Messages.getString("BoardEditor.fileFilter"), "board2"));
            chooser.setSelectedFile(initial == null ? new java.io.File(Messages.getString("BoardEditor.untitledFile") + BoardFile.EXTENSION)
                  : initial.toFile());
            if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) { return false; }
            target = chooser.getSelectedFile().toPath();
            if (!BoardFile.isNativeName(target.toString())) {
                target = target.resolveSibling(BoardFile.withoutExtension(target.getFileName().toString()) + BoardFile.EXTENSION);
            }
            if (java.nio.file.Files.exists(target) && JOptionPane.showConfirmDialog(owner,
                  Messages.getString("BoardEditor.replaceFile", target.getFileName().toString()), Messages.getString("BoardEditor.saveTitle"),
                  JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return false; }
        }
        try { saveChosen(target); return true; }
        catch (IOException failure) {
            message = Messages.getString("BoardEditor.saveFailed", String.valueOf(failure.getMessage())); revision++; return false;
        }
    }

    /**
     * The old editor's validation report before a save: the board's rules errors ({@link Board#isValid}) in a scrolling
     * list, and that the board is saved anyway but games refuse it until they are fixed.
     */
    private static void showRulesErrors(Window owner, List<String> errors) {
        javax.swing.JTextArea text = new javax.swing.JTextArea(Messages.getString("BoardEditor.invalidBoard.report")
              + String.join("\n", errors) + "\n\n" + Messages.getString("BoardEditor.invalidBoard.saved"));
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setCaretPosition(0);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(text);
        scroll.setPreferredSize(new java.awt.Dimension(560, 320));
        JOptionPane.showMessageDialog(owner, scroll, Messages.getString("BoardEditor.invalidBoard.title"), JOptionPane.WARNING_MESSAGE);
    }

    /**
     * The old editor's Save As Image: a PNG file chosen next to the board, then {@link #saveImage}. Cancel saves nothing.
     */
    private void chooseSaveImage(Window owner) throws IOException {
        Path near = path != null ? path : suggested;
        JFileChooser chooser = new JFileChooser(near == null ? new java.io.File(".") : near.toFile().getParentFile());
        chooser.setDialogTitle(Messages.getString("BoardEditor.saveAsImage"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG (*.png)", "png"));
        if (near != null) { chooser.setSelectedFile(new java.io.File(BoardFile.withoutExtension(near.getFileName().toString()) + ".png")); }
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) { return; }
        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            target = target.resolveSibling(target.getFileName() + ".png");
        }
        saveImage(target);
    }

    /**
     * Writes the board as the old editor's Save As Image did: {@link BoardArtwork#printable}, the 2D tileset art of every
     * hex with its labels, one 84 Ã— 72 tile per hex. Placed objects and decals are 3D art and do not show.
     */
    public void saveImage(Path file) throws IOException {
        finishStroke();
        megamek.client.ui.tileset.TilesetManager tileset = new megamek.client.ui.tileset.TilesetManager(game);
        try {
            tileset.loadNeededImages(game);
            if (!javax.imageio.ImageIO.write(BoardArtwork.printable(board(), tileset, coords -> null), "png", file.toFile())) {
                throw new IOException("No PNG writer");
            }
        } finally { tileset.close(); }
        message = Messages.getString("BoardEditor.imageSaved", file.getFileName());
    }

    /** The old editor's Open Board Source File: the saved file in the program the system opens it with. */
    private void openSourceFile() {
        if (path == null) { return; }
        try { java.awt.Desktop.getDesktop().open(path.toFile()); }
        catch (IOException | RuntimeException failure) {
            message = Messages.getString("BoardEditor.OpenFileError", path) + " " + failure.getMessage();
        }
    }

    /** Copy: this session's own exact copy, and the hex as text on {@link #textClipboard} for other windows. */
    private void copy(Hex hex) {
        clipboard = hex.duplicate();
        if (textClipboard == null) { return; }
        copiedText = hex.getClipboardString();
        try { textClipboard.setContents(new java.awt.datatransfer.StringSelection(copiedText), null); }
        catch (IllegalStateException busy) { copiedText = null; } // Another program holds the clipboard; keep the own copy.
    }

    /**
     * What Paste puts down: a hex another MegaMek window or a text editor put on {@link #textClipboard} since this session's
     * last Copy ({@link Hex#parseClipboardString}, the old editor's text format or the .board2 hex), otherwise this
     * session's own copy; null when there is neither.
     */
    private Hex pasted() {
        if (textClipboard != null) {
            try {
                if (textClipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                    String text = (String) textClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    Hex foreign = text == null || text.equals(copiedText) ? null : Hex.parseClipboardString(text);
                    if (foreign != null) { return foreign; }
                }
            } catch (RuntimeException | IOException | java.awt.datatransfer.UnsupportedFlavorException unavailable) {
                // A busy clipboard, text that changed meanwhile or a hex text with unknown terrain: paste the own copy.
            }
        }
        return clipboard;
    }

    /**
     * The old editor's New: the random map dialog ({@link RandomMapDialog}) with the chosen size, then its board through
     * New's path ({@link #newRandom}). Cancel keeps the document.
     */
    private void chooseRandom(Window owner, int width, int height) {
        if (randomSettings == null) { randomSettings = MapSettings.getInstance(); }
        randomSettings.setBoardSize(width, height);
        Window frame = owner;
        while (frame != null && !(frame instanceof JFrame)) { frame = frame.getOwner(); }
        RandomMapDialog dialog = new RandomMapDialog((JFrame) frame, settings -> randomSettings = settings, null, randomSettings);
        try {
            if (dialog.activateDialog(new LinkedHashSet<>(BoardArtwork.themes()))) { return; }
        } finally { dialog.dispose(); }
        newRandom(randomSettings);
    }

    /**
     * A board from the random map generator ({@link BoardUtilities#generateRandom}) becomes the document, as New's blank
     * board does: converted and validated, without a file, history or unsaved changes.
     */
    void newRandom(MapSettings settings) {
        Board board = BoardUtilities.generateRandom(settings);
        replace(board, null);
        message = Messages.getString("BoardEditor.randomBoardDone", board.getWidth(), board.getHeight());
    }

    /**
     * Grows or shrinks the board by "north,east,south,west" hexes ({@link BoardUtilities#resize}): every hex keeps its
     * terrain, appearance and objects with their groups, and the board its tags, options, description and notes. One
     * undo step that swaps the whole board; Save's file stays the same and the document is unsaved.
     */
    private void resize(String value) {
        String[] edges = value.split(",");
        require(edges.length == 4, Messages.getString("BoardEditor.resizeInvalid"));
        int north = Integer.parseInt(edges[0].trim()), east = Integer.parseInt(edges[1].trim());
        int south = Integer.parseInt(edges[2].trim()), west = Integer.parseInt(edges[3].trim());
        long width = (long) board().getWidth() + west + east, height = (long) board().getHeight() + north + south;
        require(width > 0 && height > 0 && width * height <= 1_000_000, Messages.getString("BoardEditor.resizeInvalid"));
        if (north == 0 && east == 0 && south == 0 && west == 0) { message = Messages.getString("BoardEditor.nothingChanged"); return; }
        Board before = board(), after = BoardUtilities.resize(before, north, east, south, west);
        // An import issue stays with its object, which moved with its hex.
        List<HexIssue> imports = importIssues.stream().map(issue -> new HexIssue(BoardUtilities.resized(issue.coords(), north, west),
              issue.object(), issue.text())).filter(issue -> after.contains(issue.coords())).toList();
        long id = ++nextId;
        undo.push(new Edit(Map.of(), Map.of(), currentId, id, new Resize(before, after, importIssues, imports)));
        while (undo.size() > 200) { undo.removeLast(); }
        redo.clear();
        currentId = id; importIssues = imports;
        install(after);
        message = Messages.getString("BoardEditor.resized", after.getWidth(), after.getHeight());
    }

    /**
     * The board settings that are no undo steps (Settings â†’ Map's tags, Save's license header) stay as
     * they are now when a resize's undo or redo swaps in the other board.
     */
    private static void keepSettings(Board from, Board to) {
        List.copyOf(to.getTags()).forEach(to::removeTag);
        from.getTags().forEach(to::addTag);
        to.setSourceHeader(from.getSourceHeader());
    }

    /**
     * The hexes a stroke at {@code center} changes, as its preview shows them: the radius (with Fill, the connected area),
     * without those it may not overwrite.
     */
    public List<Coords> brush(Coords center) {
        if (center == null || !board().contains(center)) { return List.of(); }
        if (fill && tool == Tool.PAINT) { return fillArea(center).stream().filter(this::overwrites).toList(); }
        List<Coords> result = new ArrayList<>();
        for (int x = Math.max(0, center.getX() - brush + 1); x <= Math.min(board().getWidth() - 1, center.getX() + brush - 1); x++) {
            for (int y = Math.max(0, center.getY() - brush + 1); y <= Math.min(board().getHeight() - 1, center.getY() + brush - 1); y++) {
                Coords at = new Coords(x, y); if (center.distance(at) < brush && overwrites(at)) { result.add(at); }
            }
        }
        return List.copyOf(result);
    }

    /** The paint bucket's area: {@code center} and every hex joined to it through neighbours that match it ({@link #fillMatch}). */
    private Set<Coords> fillArea(Coords center) {
        Object match = fillMatch(board().getHex(center));
        Set<Coords> area = new LinkedHashSet<>(List.of(center));
        var open = new ArrayDeque<>(area);
        while (!open.isEmpty()) {
            Coords at = open.poll();
            for (int direction = 0; direction < 6; direction++) {
                Coords next = at.translated(direction);
                if (board().contains(next) && !area.contains(next) && match.equals(fillMatch(board().getHex(next)))) {
                    area.add(next); open.add(next);
                }
            }
        }
        return area;
    }

    /** What the paint bucket compares: level, theme and each terrain with its level (automatic markers do not count). */
    private static Object fillMatch(Hex hex) {
        Map<Integer, Integer> terrain = new LinkedHashMap<>();
        for (int type : hex.getTerrainTypesSet()) {
            if (!Terrains.AUTOMATIC.contains(type)) { terrain.put(type, hex.terrainLevel(type)); }
        }
        return List.of(hex.getLevel(), Objects.toString(hex.getTheme(), ""), terrain);
    }

    /**
     * The old editor's "Only overwrite clear hexes": while it is on, Paint and Sculpt change only hexes without terrain
     * (automatic markers, level and theme do not count; this is not the rules' {@link Hex#isClear}). Select and Erase
     * are not filtered.
     */
    private boolean overwrites(Coords at) {
        return !clearOnly || tool != Tool.PAINT && tool != Tool.SCULPT
              || Terrains.AUTOMATIC.containsAll(board().getHex(at).getTerrainTypesSet());
    }

    /** What a Sculpt click on {@code hexes} footprint hexes at {@code hover} does without Ctrl, e.g. "Raise Â· 7 hexes". */
    public String sculptHint(Coords hover, int hexes) {
        if (tool != Tool.SCULPT || hover == null || !board().contains(hover)) { return ""; }
        if (sculpt == LevelSculpt.Mode.LEVEL) {
            return Messages.getString("BoardEditor.sculptHint.level", sculptLevel == null ? board().getHex(hover).getLevel() : sculptLevel);
        }
        return Messages.getString("BoardEditor.sculptHint", Messages.getString(sculpt == LevelSculpt.Mode.RAISE
              ? "BoardEditor.sculpt.raise" : "BoardEditor.sculpt.lower"), hexes);
    }

    /** Pointer coordinates are normalized against the hex footprint by the shared renderer's picking geometry. */
    public void pointer(Coords at, double x, double y, boolean drag) {
        pointer(at, x, y, drag, null);
    }

    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject) {
        pointer(at, x, y, drag, pickedObject, false, "ground");
    }

    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive) {
        pointer(at, x, y, drag, pickedObject, additive, "ground");
    }

    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive, String receiver) {
        pointer(at, x, y, drag, pickedObject, additive, receiver, false);
    }

    /**
     * {@code invert} (Ctrl at the press) swaps Sculpt's raise and lower; other tools ignore it. Paint with a rail, maglev or
     * road brush draws the route by hand ({@link #draw}); every other event applies at once.
     */
    public void pointer(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive, String receiver,
          boolean invert) {
        if (at == null || !board().contains(at)) { return; }
        if (drawsRoutes()) { draw(at, x, y, drag, receiver); }
        else { event(at, x, y, drag, pickedObject, additive, receiver, invert); }
    }

    /**
     * Whether a Paint drag draws a road, bridge, rail or building path. Erase applies each event at once.
     */
    private boolean drawsRoutes() { return tool == Tool.PAINT && (wayBrush() || buildingBrush()); }

    private boolean wayBrush() {
        return sampledHex == null && stampLayout() == null && (brushComponent.equals("road")
              || brushComponent.equals("bridge") || !paintTemplate.getDecorations().isEmpty()
              && RailRoute.isRoute(paintTemplate.getDecorations().getFirst()));
    }

    /**
     * A pointer event of a route drawn by hand: the press starts its path, and each hex the drag enters is its next
     * waypoint ({@link RoutePath#enter}: a skipped hex filled by a straight line, a step back into the previous hex taking
     * the last waypoint back), once the pointer is in its core ({@link #core}), so grazing a hex's corner adds no
     * waypoint and no sharp turn. A hex the pointer crossed only by its border on its way to a hex two from the path's
     * end is taken when the pointer lands there, so the route bends through the hex the pointer went through. The
     * document is untouched until the release builds the route as one undo step ({@link #finishStroke}), so the user can
     * retrace to fix it first; {@link #preview} shows it meanwhile, and {@link #cancelStroke} drops it.
     */
    private void draw(Coords at, double x, double y, boolean drag, String receiver) {
        RoutePath was = drawing;
        if (!drag || drawing == null) {
            // A press while a route is pending (no release in between) builds that one into the stroke first.
            buildDrawing();
            wayReceivers.clear();
            drawing = RoutePath.start(at);
        } else {
            drawing = crossed(at);
            if (!at.equals(drawing.end()) && (buildingBrush() || core(x, y))) { drawing = drawing.enter(at, board()::contains); }
        }
        // The preview's heading is the pointer's movement since it took, or came back into, the path's last hex (heading).
        if (at.equals(drawing.end()) && (drawing != was || !at.equals(drawingAt))) { routeEntryX = x; routeEntryY = y; }
        wayReceivers.put(at, receiver == null ? "ground" : receiver);
        drawingAt = at;
        if (drawing != was) { revision++; }
    }

    /**
     * The route drawn by hand with the pointer landing in {@code at}, before it takes that hex: where {@code at} lies two
     * from the path's end, the hex of the drag's last event beside both is the one the pointer crossed on its way there,
     * so it is taken first (else the path as it is).
     */
    private RoutePath crossed(Coords at) {
        Coords end = drawing.end();
        return drawingAt != null && end.distance(at) == 2 && end.distance(drawingAt) == 1 && drawingAt.distance(at) == 1
              ? drawing.enter(drawingAt, board()::contains) : drawing;
    }

    /** Builds the route drawn by hand as its preview shows it: on into the hex its pointer is in, taken or not. */
    private void buildDrawing() {
        if (drawing != null && drawingAt != null) { drawing = drawing.enter(drawingAt, board()::contains); }
        drawPath();
    }

    /** Builds the pending route drawn by hand into the stroke: its waypoints as one press and its drag, as before. */
    private void drawPath() {
        if (drawing == null) { return; }
        List<Coords> hexes = drawing.hexes();
        drawing = null;
        if (buildingBrush()) { paintBuildings(hexes); }
        else { paintWays(hexes); }
    }

    /** Picks the height and layer once. Dry ground following is deliberately allowed to climb arbitrary hills. */
    private WayLayer wayLayer(Coords at, String receiver, WayLayer previous, int pressed, boolean bridge, boolean bank) {
        Hex hex = board().getHex(at);
        if (tool == Tool.PAINT && !overwrites(at)) { return refused(hex.getLevel(), "BoardEditor.way.clearOnly"); }
        boolean dry = hex.terrainLevel(Terrains.WATER) < 1 || hex.containsTerrain(Terrains.MAGMA);
        int ground = hex.getLevel();
        // A bridge card stands on the ground, under any water; the other routes ride above the surface.
        int footing = bridge ? hex.floor() : ground;
        int target = levelHeight ? pressed + wayOffset : wayOffset > 0 ? footing + wayOffset
              : previous == null ? pressed : previous.level();
        boolean up = levelHeight || wayOffset > 0 || previous != null && previous.deck();
        boolean deck = hex.hasBridge();
        int deckLevel = deck ? BridgeSpan.deck(hex) : ground;
        if (bridge) {
            if (bank && dry && !deck && Math.abs(ground - target) <= 1) {
                return new WayLayer(WaySurface.GROUND, ground, "");
            }
            if (hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK)) {
                return refused(target, "BoardEditor.way.structure");
            }
            if (deck && Math.abs(deckLevel - target) > 1) { return deckRefusal(at, deckLevel, target); }
            if (footing > target) { return refused(target, "BoardEditor.way.rising"); }
            return new WayLayer(deck ? WaySurface.DECK : WaySurface.NEW, deck ? deckLevel : target, "");
        }
        if (deck && Math.abs(deckLevel - target) <= 1 && ("bridge".equals(receiver) || up || !dry)) {
            return new WayLayer(WaySurface.DECK, deckLevel, "");
        }
        if (dry && (levelHeight ? ground == target
              : wayOffset == 0 && (!up || Math.abs(ground - target) <= 1))) {
            String notice = deck && Math.abs(deckLevel - target) > 1
                  ? Messages.getString("BoardEditor.way.outOfReach", deckLevel, ground) : "";
            return new WayLayer(WaySurface.GROUND, ground, notice);
        }
        if (!levelHeight && wayOffset == 0 && !dry) { target = Math.max(target, ground); }
        if (deck) { return deckRefusal(at, deckLevel, target); }
        if (hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK)) {
            return refused(target, "BoardEditor.way.structure");
        }
        if (!dry || ground < target) { return new WayLayer(WaySurface.NEW, target, ""); }
        return refused(target, "BoardEditor.way.rising");
    }

    private static WayLayer refused(int level, String key) {
        return new WayLayer(WaySurface.REFUSED, level, Messages.getString(key));
    }

    private static WayLayer deckRefusal(Coords at, int deck, int target) {
        return new WayLayer(WaySurface.REFUSED, target, Messages.getString("BoardEditor.way.oneDeck", at.getBoardNum(), deck));
    }

    private int pressLevel(Coords at, String receiver) {
        Hex hex = board().getHex(at);
        return "bridge".equals(receiver) && hex.hasBridge() ? BridgeSpan.deck(hex) : hex.getLevel();
    }

    /** Bridge cards and every way replay the same path, with its actual pointer receivers and one undo boundary. */
    private void paintWays(List<Coords> path) {
        if (path.isEmpty()) { return; }
        boolean bridge = brushComponent.equals("bridge");
        int pressed = pressLevel(path.getFirst(), wayReceivers.get(path.getFirst()));
        WayLayer previous = null;
        wayLayers.clear();
        for (int i = 0; i < path.size(); i++) {
            Coords at = path.get(i);
            WayLayer layer = wayLayer(at, wayReceivers.get(at), previous, pressed, bridge,
                  path.size() > 1 && (i == 0 || i == path.size() - 1));
            wayLayers.put(at, layer);
            if (!layer.refused()) { previous = layer; }
        }
        // Ways may use only exits already drawn on an explicit bridge. Validate before writing either end.
        for (int i = 0; i < path.size(); i++) {
            Coords at = path.get(i);
            WayLayer layer = wayLayers.get(at);
            Hex hex = board().getHex(at);
            if (bridge || !layer.deck() || !hex.hasBridge() || HexAppearance.implicit(hex.getAppearance())) { continue; }
            for (int j : new int[] { i - 1, i + 1 }) {
                if (j < 0 || j >= path.size()) { continue; }
                Coords other = path.get(j);
                WayLayer next = wayLayers.get(other);
                if (next.refused() || at.distance(other) != 1 || Math.abs(layer.level() - next.level()) > 1) { continue; }
                int side = at.direction(other);
                if (!hex.containsTerrainExit(Terrains.BRIDGE, side)) {
                    wayLayers.put(at, new WayLayer(WaySurface.REFUSED, layer.level(),
                          Messages.getString("BoardEditor.way.missingExit", at.getBoardNum(), sideName(side))));
                    break;
                }
            }
        }
        Hex continued = null;
        int count = 0, made = 0, carried = 0;
        for (int i = 0; i < path.size(); i++) {
            Coords at = path.get(i);
            WayLayer layer = wayLayers.get(at);
            if (layer.refused()) { strokeNotice = layer.notice(); continue; }
            Hex hex = board().getHex(at);
            int sides = 0;
            for (int j : new int[] { i - 1, i + 1 }) {
                if (j < 0 || j >= path.size()) { continue; }
                Coords other = path.get(j);
                WayLayer next = wayLayers.get(other);
                if (!next.refused() && at.distance(other) == 1
                      && (!layer.deck() && !next.deck() || Math.abs(layer.level() - next.level()) <= 1)) {
                    sides |= 1 << at.direction(other);
                }
            }
            if (bridge && !layer.deck()) { continue; }
            String defaults = continued != null ? "bridge:" + continued.terrainLevel(Terrains.BRIDGE)
                  + ";bridge_cf:" + continued.terrainLevel(Terrains.BRIDGE_CF)
                  : blueprint.component(brushComponent.equals("road") || bridge ? "bridge" : "ways").defaults();
            boolean pillars = wayPillars || continued != null && HexAppearance.pillars(continued.getAppearance());
            int exits = brushComponent.equals("road") && !layer.deck() ? sides | landings(at) : sides;
            // The way traffic on a new single lane runs: from the hex painted before it; a stroke's own ends keep the lanes
            // of a road already there, so a ramp drawn from a two-lane road leaves that road two lanes.
            int back = i > 0 && (sides & 1 << at.direction(path.get(i - 1))) != 0 ? at.direction(path.get(i - 1)) : -1;
            boolean end = path.size() > 1 && (i == 0 || i == path.size() - 1);
            change(at, h -> {
                if (bridge) { paintBridge(h, layer.level(), exits, pillars); }
                else {
                    paintWay(h, layer.deck(), exits, back, end);
                    if (layer.deck()) {
                        BridgeSpan.carry(h, layer.level() - h.getLevel(), defaults);
                        if (pillars && HexAppearance.implicit(h.getAppearance())) { appearance(h, "bridge", HexAppearance.IMPLICIT_PILLARS); }
                    }
                }
            });
            if (layer.deck()) { continued = board().getHex(at); }
            else { continued = null; }
            count++;
            if (layer.surface() == WaySurface.NEW) { made++; }
            else if (layer.deck()) { carried++; }
        }
        if (bridge) { path.forEach(this::joinLanding); }
        selected = path.getLast(); selectedObject = "";
        component = board().getHex(selected).hasBridge() && wayLayers.get(selected).deck() ? "bridge"
              : brushComponent.equals("road") ? "road" : "ground";
        selection.clear(); selection.add(new Selection(selected, ""));
        message = strokeNotice.isEmpty() ? Messages.getString("BoardEditor.way.painted", brushLabel, count, made, carried) : strokeNotice;
    }

    /** The sides where a bridge deck already exits back to a ground road at {@code at}. */
    private int landings(Coords at) {
        Hex hex = board().getHex(at);
        int sides = 0;
        for (int direction = 0; direction < 6; direction++) {
            if (hex.bridgeLandsFrom(board().getHex(at.translated(direction)), direction)) { sides |= 1 << direction; }
        }
        return sides;
    }

    /** A road already on a bridge card's landing joins the deck just drawn from it. */
    private void joinLanding(Coords at) {
        Terrain road = board().getHex(at).getTerrain(Terrains.ROAD);
        if (road == null) { return; }
        int exits = road.getExits() | landings(at);
        if (exits != road.getExits()) { change(at, hex -> hex.addTerrain(new Terrain(Terrains.ROAD, road.getLevel(), true, exits))); }
    }

    private void paintBridge(Hex hex, int level, int sides, boolean pillars) {
        int kept = hex.hasBridge() ? hex.getTerrain(Terrains.BRIDGE).getExits() : 0;
        hex.addTerrain(new Terrain(Terrains.BRIDGE, paintTemplate.terrainLevel(Terrains.BRIDGE), true, kept | sides));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, paintTemplate.terrainLevel(Terrains.BRIDGE_CF)));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, level - hex.getLevel()));
        HexAppearance type = paintTemplate.getAppearance().getOrDefault("bridge", HexAppearance.BUILT_BRIDGE);
        boolean built = !Boolean.FALSE.equals(HexAppearance.bridgeBuilt(Map.of("bridge", type)));
        appearance(hex, "bridge", built ? HexAppearance.built(HexAppearance.truss(Map.of("bridge", type)),
              pillars || HexAppearance.pillars(hex.getAppearance()), type.colours()) : HexAppearance.NATURAL_BRIDGE);
    }

    /** The drawn path supplies explicit connections, retaining branches already drawn, as {@link #paintBuildings} does. */
    private void paintWay(Hex hex, boolean deck, int sides, int back, boolean end) {
        boolean road = brushComponent.equals("road");
        int type = road ? deck ? Terrains.BRIDGE_ROAD : Terrains.ROAD : deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
        Terrain old = hex.getTerrain(type);
        int exits = sides | (old == null ? 0 : old.getExits());
        if (road) {
            HexAppearance was = hex.getAppearance().get("road");
            hex.addTerrain(new Terrain(type, paintTemplate.terrainLevel(Terrains.ROAD), true, exits));
            HexAppearance look = colonyFinish(hex, paintTemplate.getAppearance().get("road"));
            if (!deck) {
                // A road already there keeps its lanes at the stroke's ends, and a single lane its way anywhere: Reverse
                // direction and the ramp choice turn it, not painting over it again.
                boolean single = was != null && was.lanes() != null && old != null;
                if (end && old != null || single && look != null && look.lanes() != null) { look = HexAppearance.keepLanes(look, was); }
                else if (look != null && look.lanes() != null) { look = SingleLane.directed(look, back < 0 ? 0 : 1 << back); }
                // A two-lane road painted on keeps which of its approaches may take its exits.
                else if (old != null && was != null && was.exitFrom() != null) { look = HexAppearance.withExitFrom(look, was.exitFrom()); }
                look = roadLanes(hex, "road", look, look);
            }
            // Shape selectors belong to the ground; a deck keeps the chosen surface and junction controls.
            appearance(hex, deck ? "bridgeRoad" : "road", deck ? roadSurface(look) : look);
        } else {
            String owner = deck ? "bridgeRail" : "rail";
            int laid = old == null ? 0 : HexAppearance.tracks(hex.getAppearance(), owner);
            if (old != null && laid == 0) { laid = RailRoute.pieces(old.getExits()); }
            RailRoute.lay(hex, deck, exits, RailRoute.layout(exits, laid | RailRoute.pieces(sides)),
                  RailRoute.kind(paintTemplate.getDecorations().getFirst()));
        }
    }

    private static void appearance(Hex hex, String owner, HexAppearance value) {
        var looks = new LinkedHashMap<>(hex.getAppearance());
        if (value == null) { looks.remove(owner); } else { looks.put(owner, value); }
        hex.setAppearance(looks);
    }

    /** A road moved to another height keeps its surface and controls; ground shape selectors do not move with it. */
    private static HexAppearance roadSurface(HexAppearance look) {
        if (look == null) { return null; }
        HexAppearance surface = look.material() == null ? null : new HexAppearance(null, null, look.material(), null);
        return HexAppearance.withTrafficControl(surface, look.trafficControl());
    }

    private static String wayOwner(int type) {
        return switch (type) {
            case Terrains.ROAD -> "road";
            case Terrains.RAIL -> "rail";
            case Terrains.BRIDGE_ROAD -> "bridgeRoad";
            case Terrains.BRIDGE_RAIL -> "bridgeRail";
            default -> "";
        };
    }

    private static void removeWay(Hex hex, int type) {
        hex.removeTerrain(type);
        if (!wayOwner(type).isEmpty()) { appearance(hex, wayOwner(type), null); }
    }

    private static void removeDeck(Hex hex) {
        hex.removeBridge();
        appearance(hex, "bridge", null); appearance(hex, "bridgeRoad", null); appearance(hex, "bridgeRail", null);
    }

    /** An explicit layer correction, including a submerged way beneath a bridge. */
    private static void moveWay(Hex hex, String name, boolean deck) {
        int source = Terrains.getType(name);
        require(source == Terrains.ROAD || source == Terrains.BRIDGE_ROAD
              || source == Terrains.RAIL || source == Terrains.BRIDGE_RAIL, "Choose a road or rail.");
        boolean road = source == Terrains.ROAD || source == Terrains.BRIDGE_ROAD;
        int target = road ? deck ? Terrains.BRIDGE_ROAD : Terrains.ROAD : deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
        if (source == target) { return; }
        Terrain way = hex.getTerrain(source);
        require(way != null, Messages.getString("BoardEditor.addTerrainFirst"));
        require(!hex.containsTerrain(target), Messages.getString("BoardEditor.way.layerOccupied"));
        require(!deck || hex.hasBridge(), Messages.getString("BoardEditor.addBridgeFirst"));
        require(!deck || HexAppearance.implicit(hex.getAppearance())
              || (way.getExits() & ~hex.getTerrain(Terrains.BRIDGE).getExits()) == 0,
              Messages.getString("BoardEditor.way.exitOutside"));
        HexAppearance look = hex.getAppearance().get(wayOwner(source));
        removeWay(hex, source);
        hex.addTerrain(new Terrain(target, way.getLevel(), true, way.getExits()));
        appearance(hex, wayOwner(target), road ? roadSurface(look) : look);
    }

    /** One corridor run, one edit: middle, beside, or the other side. */
    private void corridor(String owner, String choice) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        require(owner.equals("rail") || owner.equals("bridgeRail"), "Choose a rail layer.");
        boolean deck = owner.equals("bridgeRail");
        Hex hex = board().getHex(selected);
        BoardDecoration marker = RailRoute.derived(hex, "").stream().filter(route -> RailRoute.onDeck(route) == deck)
              .findFirst().orElseThrow(() -> new IllegalArgumentException(Messages.getString("BoardEditor.addTerrainFirst")));
        require(Set.of("middle", "beside", "swap").contains(choice), "Choose middle, beside or swap.");
        var run = RailRoute.corridorRun(board()::getHex, selected, marker);
        require(!run.isEmpty(), Messages.getString("BoardEditor.way.noCorridor"));
        var sides = RailRoute.besideBits(board()::getHex, run, new RailRoute.Member(selected, marker));
        for (var member : run) {
            boolean onDeck = RailRoute.onDeck(member.marker());
            change(member.at(), h -> {
                int stored = HexAppearance.beside(h.getAppearance(), onDeck ? "bridgeRail" : "rail");
                int value = choice.equals("middle") ? 0 : choice.equals("swap") && stored != 0
                      ? stored ^ member.marker().connections() : sides.getOrDefault(member, 64);
                RailRoute.beside(h, onDeck, value);
            });
        }
        // Existing vehicles follow the moved centreline, as newly painted vehicles do.
        for (var member : run) {
            change(member.at(), h -> h.setDecorations(h.getDecorations().stream()
                  .map(object -> RailRoute.vehicle(object.asset()) ? ride(member.at(), object) : object).toList()));
        }
        if (!continuousEdit) { finishStroke(); }
    }

    /** Erase follows the same pointer layer decision; a deck never takes the road or rail beneath it. */
    private void eraseWay(Coords at, String receiver) {
        if (!painted.add(at)) { return; }
        Hex hex = board().getHex(at);
        boolean bridge = brushComponent.equals("bridge");
        wayReceivers.putIfAbsent(at, receiver == null ? "ground" : receiver);
        Coords press = wayReceivers.keySet().iterator().next();
        Hex first = stroke.getOrDefault(press, board().getHex(press));
        int pressed = "bridge".equals(wayReceivers.get(press)) && first.hasBridge() ? BridgeSpan.deck(first) : first.getLevel();
        WayLayer previous = wayLayers.get(routePaintFrom);
        WayLayer layer = wayLayer(at, receiver, previous, pressed, false, false);
        if (bridge && hex.hasBridge()) { layer = new WayLayer(WaySurface.DECK, BridgeSpan.deck(hex), ""); }
        wayLayers.put(at, layer);
        if (layer.refused()) { strokeNotice = layer.notice(); return; }
        int type = bridge ? Terrains.BRIDGE : brushComponent.equals("road")
              ? layer.deck() ? Terrains.BRIDGE_ROAD : Terrains.ROAD : layer.deck() ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
        if (!hex.containsTerrain(type)) { return; }
        if ((type == Terrains.RAIL || type == Terrains.BRIDGE_RAIL)
              && hex.terrainLevel(type) != (RailRoute.kind(paintTemplate.getDecorations().getFirst()) == RailRoute.Kind.MAGLEV ? 2 : 1)) { return; }
        change(at, h -> { if (bridge) { removeDeck(h); } else { removeWay(h, type); } });
        cutWayNeighbours(at, hex, type);
        routePaintFrom = at;
    }

    /** Only the removed layer's neighbours lose their facing sides; automatic roads re-derive their own exits. */
    private void cutWayNeighbours(Coords at, Hex before, int type) {
        for (int d = 0; d < 6; d++) {
            Coords next = at.translated(d);
            Hex neighbour = board().getHex(next);
            if (neighbour == null) { continue; }
            int back = 1 << ((d + 3) % 6);
            if (type == Terrains.RAIL || type == Terrains.BRIDGE_RAIL || type == Terrains.BRIDGE) {
                boolean onDeck = type != Terrains.RAIL;
                var oldRail = RailRoute.derived(before, "").stream().filter(route -> RailRoute.onDeck(route) == onDeck)
                      .findFirst().orElse(null);
                var joined = oldRail == null ? null : RailRoute.joined(before, oldRail, d, neighbour);
                if (joined != null) {
                    int sides = joined.connections() & ~back;
                    change(next, h -> RailRoute.lay(h, RailRoute.onDeck(joined), sides,
                          RailRoute.layout(sides, joined.tracks()), RailRoute.kind(joined)));
                }
                if (type != Terrains.BRIDGE) { continue; }
            }
            boolean deck = type == Terrains.BRIDGE || type == Terrains.BRIDGE_ROAD || type == Terrains.BRIDGE_RAIL;
            if (deck && (!neighbour.hasBridge() || Math.abs(BridgeSpan.deck(before) - BridgeSpan.deck(neighbour)) > 1)) { continue; }
            int[] types = type == Terrains.BRIDGE ? new int[] { Terrains.BRIDGE, Terrains.BRIDGE_ROAD, Terrains.BRIDGE_RAIL }
                  : new int[] { type };
            change(next, h -> {
                for (int cut : types) {
                    Terrain value = h.getTerrain(cut);
                    if (value != null && (value.getExits() & back) != 0) {
                        h.addTerrain(new Terrain(cut, value.getLevel(), true, value.getExits() & ~back));
                        if (cut == Terrains.RAIL || cut == Terrains.BRIDGE_RAIL) {
                            RailRoute.lay(h, cut == Terrains.BRIDGE_RAIL, value.getExits() & ~back,
                                  RailRoute.layout(value.getExits() & ~back, HexAppearance.tracks(h.getAppearance(), wayOwner(cut))),
                                  value.getLevel() == 2 ? RailRoute.Kind.MAGLEV : RailRoute.Kind.RAIL);
                        }
                    }
                }
            });
        }
    }

    /** Reconcile implicit decks and freeze bridge exits only in the edit's footprint and its recorded neighbours. */
    private void wayDecks() {
        for (Coords at : List.copyOf(stroke.keySet())) {
            Hex current = board().getHex(at), before = stroke.get(at);
            // A destroyed span keeps its exits in its ruin, as a game's collapse leaves its neighbours' exits: only a
            // way removed for good cuts them.
            if (before.hasBridge() && !current.hasBridge() && current.getRuin(Terrains.BRIDGE) == null) {
                cutWayNeighbours(at, before, Terrains.BRIDGE);
            }
            for (int way : new int[] { Terrains.ROAD, Terrains.RAIL, Terrains.BRIDGE_ROAD, Terrains.BRIDGE_RAIL }) {
                if (before.containsTerrain(way) && !current.containsTerrain(way) && current.getRuin(way) == null) {
                    cutWayNeighbours(at, before, way);
                }
            }
            change(at, hex -> {
                BridgeSpan.carry(hex, hex.hasBridge() ? hex.terrainLevel(Terrains.BRIDGE_ELEV) : 0, blueprint.component("bridge").defaults());
                // A deck laid again over a fallen span replaces its ruin, with the rubble that is its own.
                if (hex.hasBridge() && hex.getRuin(Terrains.BRIDGE) != null) { hex.removeRuins(); }
                Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
                if (bridge != null && !bridge.hasExitsSpecified()) {
                    hex.addTerrain(new Terrain(Terrains.BRIDGE, bridge.getLevel(), true, bridge.getExits()));
                }
            });
        }
    }

    /** The hovered route result, including whether erasing its way also removes an implicit deck. */
    public String wayHint(Coords at, Preview result) {
        if (!wayBrush() || at == null || !board().contains(at)) { return ""; }
        WayLayer layer = result.layers().get(at);
        if (layer == null) { return ""; }
        if (layer.refused()) { return Messages.getString("BoardEditor.way.refused", layer.notice()); }
        if (tool == Tool.ERASE && layer.deck() && !brushComponent.equals("bridge")) {
            Hex hex = board().getHex(at);
            int other = brushComponent.equals("road") ? Terrains.BRIDGE_RAIL : Terrains.BRIDGE_ROAD;
            return Messages.getString(HexAppearance.implicit(hex.getAppearance()) && !hex.containsTerrain(other)
                  ? "BoardEditor.way.eraseImplicit" : "BoardEditor.way.eraseExplicit", brushLabel);
        }
        if (!layer.notice().isEmpty()) { return layer.notice(); }
        return Messages.getString(layer.surface() == WaySurface.GROUND ? "BoardEditor.way.ground"
              : layer.surface() == WaySurface.NEW ? "BoardEditor.way.new" : "BoardEditor.way.deck", layer.level());
    }

    /**
     * Drops the route being drawn by hand (Esc or a right click during its drag): nothing of it was built, so the document
     * stays as it is. Any other stroke is left to its release.
     */
    public void cancelStroke() {
        if (drawing == null) { return; }
        drawing = null; wayReceivers.clear();
        revision++;
    }

    /** One pointer event, applied to the document at once (a route drawn by hand applies its waypoints on release). */
    private void event(Coords at, double x, double y, boolean drag, String pickedObject, boolean additive, String receiver,
          boolean invert) {
        // Why a route stroke left a hex or side out (it ends open there): kept for the whole stroke, so the drag's later
        // events do not wipe it.
        String notice = strokeNotice;
        try {
            if (tool == Tool.SELECT) {
                selectPointer(at, x, y, drag, pickedObject, additive);
            } else if (tool == Tool.SCULPT) {
                // A fast drag also covers the hexes it skipped since the stroke's previous event.
                var centres = drag && sculptLevel != null && selected != null ? Coords.intervening(selected, at) : List.of(at);
                selected = at; selectedObject = "";
                if (sculptLevel == null) {
                    sculptLevel = board().getHex(at).getLevel();
                    sculptStep = (sculpt == LevelSculpt.Mode.LOWER) != invert ? -1 : 1;
                }
                // Each footprint hex moves once per stroke, when the stroke first enters it.
                List<Coords> cells = centres.stream().flatMap(centre -> brush(centre).stream()).filter(painted::add).toList();
                var levels = sculpt == LevelSculpt.Mode.LEVEL ? LevelSculpt.level(board(), cells, sculptLevel)
                      : LevelSculpt.raise(board(), cells, sculptStep, slope);
                // Slope steps neighbours too; with Only overwrite clear hexes, a neighbour holding terrain stays.
                levels.keySet().removeIf(cell -> !overwrites(cell));
                setLevels(levels);
            } else if (wayBrush()) {
                selected = at; selectedObject = ""; component = brushComponent.isEmpty() ? "ground" : brushComponent;
                var cells = drag && routePaintFrom != null ? Coords.intervening(routePaintFrom, at) : List.of(at);
                for (Coords cell : cells) { if (board().contains(cell)) { eraseWay(cell, cell.equals(at) ? receiver : "ground"); } }
                notice = strokeNotice;
            } else if (sampledHex != null) {
                selected = at; selectedObject = ""; component = brushComponent;
                Hex sample = tool == Tool.ERASE ? new Hex(0) : paintTemplate;
                for (Coords spot : brush(at)) {
                    if (!painted.add(spot)) { continue; }
                    change(spot, hex -> {
                        int level = hex.getLevel(); String theme = hex.getTheme();
                        copyInto(sample, hex);
                        if (tool == Tool.ERASE || !brushElevation) { hex.setLevel(level); }
                        if (tool == Tool.PAINT && !brushTheme) { hex.setTheme(theme); }
                    });
                }
            } else if (!paintTemplate.getDecorations().isEmpty()) {
                BoardDecoration prototype = paintTemplate.getDecorations().getFirst();
                if (tool == Tool.ERASE) {
                    selected = at; selectedObject = "";
                    for (Coords spot : brush(at)) {
                        if (painted.add(spot)) { change(spot, hex -> hex.setDecorations(hex.getDecorations().stream()
                              .filter(d -> !d.asset().equals(prototype.asset())).toList())); }
                    }
                } else if (!placed) {
                    BoardDecoration object = brushCopy(prototype, precision ? x : 0, precision ? y : 0, onSupport(board().getHex(at), receiver, prototype));
                    if (precision && magnetic) { object = BoardEditorSnapping.snap(board(), blueprint, at, object, Set.of()); }
                    object = ride(at, object);
                    Selection twin = addObject(at, object);
                    // One object per press. A click where its twin stands selects that instead; a drag skips it silently.
                    if (twin == null || !drag) { placed = true; }
                    if (twin != null && !drag) { notice = selectPlaced(List.of(twin)); }
                }
            } else if (stampLayout() != null) {
                require(tool == Tool.PAINT, Messages.getString("BoardEditor.stampGroup"));
                if (!placed) {
                    var stamped = BoardSceneryLayouts.stamp(stampLayout(), board().getHex(at), at);
                    var riding = ride(at, stamped);
                    // All or nothing: a stamp whose every member has its twin in its hex is there already; a click selects
                    // those twins, a drag skips them silently. Any other stamp is placed whole.
                    var twins = riding.stream().map(member -> twin(member.getKey(), member.getValue())).toList();
                    if (!twins.isEmpty() && twins.stream().allMatch(Objects::nonNull)) {
                        if (!drag) { notice = selectPlaced(twins); placed = true; }
                    } else {
                        List<Selection> members = new ArrayList<>();
                        // A train on a track places only the vehicles that fit; the notice says how many.
                        long vehicles = stamped.stream().filter(member -> RailRoute.vehicle(member.asset())).count();
                        long fitting = riding.stream().filter(member -> RailRoute.vehicle(member.getValue().asset())).count();
                        if (fitting < vehicles) { notice = Messages.getString("BoardEditor.vehiclesPlaced", fitting, vehicles); }
                        // Each member belongs to the stamp's hex, a vehicle that rides a route to the hex of the track it stands on.
                        for (var member : riding) {
                            change(member.getKey(), hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.add(member.getValue()); hex.setDecorations(objects); });
                            members.add(new Selection(member.getKey(), member.getValue().id()));
                        }
                        selected = members.isEmpty() ? at : members.getFirst().coords();
                        selectedObject = members.isEmpty() ? "" : members.getFirst().object(); placed = true;
                        // The new stamp is selected as its whole group, so a later Select click and drag moves all of it.
                        selection.clear();
                        if (members.isEmpty()) { selection.add(new Selection(at, "")); }
                        selection.addAll(members);
                    }
                }
            } else if (tool == Tool.PAINT && !brushHexes().isEmpty()) {
                // A multi-hex blueprint (the DropShip), once per press: the clicked hex and its six neighbours each get the
                // component's fields of their terrain, without its design; they keep their level, theme, ground and objects.
                if (!placed) {
                    placed = true; selected = at; selectedObject = ""; component = brushComponent;
                    var definition = blueprint.component(brushComponent);
                    Map<Coords, Hex> targets = new LinkedHashMap<>();
                    targets.put(at, paintTemplate);
                    brushHexes().forEach((direction, terrain) -> targets.put(at.translated(direction), new Hex(0, terrain, "")));
                    require(targets.keySet().stream().allMatch(board()::contains), Messages.getString("BoardEditor.blueprintOffBoard"));
                    targets.forEach((spot, template) -> change(spot, hex -> {
                        paintFields(hex, template, definition);
                        var styles = new LinkedHashMap<>(hex.getAppearance()); styles.remove(brushComponent); hex.setAppearance(styles);
                    }));
                    selection.clear(); selection.add(new Selection(at, ""));
                }
            } else {
                // A Theme card paints no ground field: the hex keeps its sand, mud, swamp and the rest.
                boolean theme = brushKey.startsWith(THEME_BRUSH);
                require(!theme || tool == Tool.PAINT, Messages.getString("BoardEditor.themeNoErase"));
                // A ground eraser on a roof or floor removes the pointed building, keeping the ground beneath it.
                String target = tool == Tool.ERASE && brushComponent.equals("ground") && "building".equals(receiver)
                      ? "building" : brushComponent;
                selected = at; selectedObject = ""; component = target;
                var definition = blueprint.component(target);
                List<Coords> spots = new ArrayList<>();
                for (Coords spot : brush(at)) {
                    if (!painted.add(spot)) { continue; }
                    spots.add(spot);
                    change(spot, hex -> {
                        if (!theme) { paintFields(hex, paintTemplate, definition); }
                        var styles = new LinkedHashMap<>(hex.getAppearance());
                        if (tool != Tool.ERASE && paintTemplate.getAppearance().containsKey(brushComponent)) {
                            // A Built or Truss bridge card keeps the hex's piers.
                            var style = paintTemplate.getAppearance().get(brushComponent);
                            if (brushComponent.equals("road")) { style = colonyFinish(hex, style); }
                            styles.put(brushComponent, brushComponent.equals("bridge") ? retype(style).apply(styles.get("bridge")) : style);
                        } else { styles.remove(target); }
                        hex.setAppearance(styles);
                        if (tool != Tool.ERASE && brushComponent.equals("ground")) {
                            if (brushTheme) { hex.setTheme(paintTemplate.getTheme()); }
                            if (brushElevation) { hex.setLevel(paintTemplate.getLevel()); }
                        }
                    });
                }

            }
            // What a press placed or found placed stays selected for its stroke: an object, a stamp's or a twin's group.
            if (tool != Tool.SELECT && !placed) {
                selection.clear();
                if (selected != null) { selection.add(new Selection(selected, selectedObject)); }
            }
            message = strokeNotice = notice;
        } catch (IllegalArgumentException failure) { message = failure.getMessage(); }
        revision++;
    }

    /**
     * What the next Paint or Erase pointer event at {@code at} (as {@link #pointer} takes it) would change, for the
     * editor's hover preview. It is computed by that event itself and its stroke's completion ({@link #completeStroke}),
     * run on the document without telling the board's listeners ({@link Board#quietly}) and undone at once with every
     * stroke field, so the preview is exactly what the event does and nothing else sees it. Before a press it previews
     * the press. While a route is drawn by hand, it previews the release of the route as drawn ({@code path}) with its
     * next step: into {@code at} where the pointer has not taken that hex yet (a release there builds it too), else into
     * the neighbour it heads for ({@link #heading}: its movement since it took, or came back into, the path's last hex)
     * unless that step goes back. While an Erase route stroke is drawn, the step into the neighbour its pointer heads
     * for. During any other stroke, and for the other tools, nothing. The result is kept until the document, the brush,
     * the path, the hex or the pointer the event depends on changes.
     */
    public Preview preview(Coords at, double x, double y, String receiver) {
        boolean routing = routePaintFrom != null;
        boolean idle = drawing == null && stroke.isEmpty() && painted.isEmpty() && !placed && movingObjects.isEmpty()
              && dragOrigin == null;
        if (at == null || !board().contains(at) || tool != Tool.PAINT && tool != Tool.ERASE
              || drawing == null && !routing && !idle) {
            return Preview.NONE;
        }
        Coords target = at, step = null;
        List<Coords> path = List.of(), events;
        if (drawing != null) {
            // The route as a release builds it, into the hex the pointer is in; in its last hex, the step it heads for too.
            path = crossed(at).enter(at, board()::contains).hexes();
            events = path;
            int side = at.equals(drawing.end()) ? heading(x - routeEntryX, y - routeEntryY) : -1;
            Coords next = side < 0 ? null : at.translated(side);
            if (next != null && board().contains(next) && (path.size() < 2 || !next.equals(path.get(path.size() - 2)))) {
                events = new ArrayList<>(path);
                events.add(next);
                step = next;
            }
            target = events.getLast();
        } else {
            if (routing && at.equals(routePaintFrom)) {
                int side = heading(x - routeEntryX, y - routeEntryY);
                target = side < 0 ? null : at.translated(side);
                if (target == null || !board().contains(target)) { return Preview.NONE; }
            }
            events = List.of(target);
        }
        // Only a precisely painted object depends on the pointer's place within its hex (Erase never does).
        boolean exact = tool == Tool.PAINT && precision && sampledHex == null && !paintTemplate.getDecorations().isEmpty()
              && RailRoute.kind(paintTemplate.getDecorations().getFirst()) == null;
        double pointX = exact ? x : 0, pointY = exact ? y : 0;
        String support = receiver == null ? "ground" : receiver;
        List<Object> key = List.of(revision, events, pointX, pointY, support);
        if (key.equals(previewFor)) { return preview; }
        Saved saved = save();
        Map<Coords, Hex> originals = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        Coords event = target;
        List<Coords> drawn = events;
        String[] notice = { "" };
        Map<Coords, WayLayer> layers = new LinkedHashMap<>();
        dryRun = originals;
        try {
            board().quietly(() -> {
                try {
                    // A route drawn by hand is built as its release builds it; any other event is the event itself.
                    if (drawsRoutes()) { wayReceivers.put(at, support); drawing = new RoutePath(drawn); drawPath(); }
                    else { pointer(event, pointX, pointY, routing, null, false, support); }
                    if (!stroke.isEmpty()) { completeStroke(); }
                    notice[0] = strokeNotice;
                    layers.putAll(wayLayers);
                    originals.keySet().forEach(cell -> after.put(cell, board().getHex(cell).duplicate()));
                } finally {
                    dryRun = null;
                    Map<megamek.common.board.BoardLocation, Hex> restored = new LinkedHashMap<>();
                    originals.forEach((cell, hex) -> restored.put(megamek.common.board.BoardLocation.of(cell, board().getBoardId()), hex));
                    if (!restored.isEmpty()) { board().setHexes(restored); }
                }
            });
        } finally { restore(saved); }
        Map<Coords, Hex> before = new LinkedHashMap<>(), changed = new LinkedHashMap<>();
        after.forEach((cell, hex) -> {
            Hex original = originals.get(cell);
            // Automatic exits are omitted from the file form, but change a neighbour's previewed junction.
            boolean exitsChanged = java.util.stream.IntStream.of(Terrains.ROAD, Terrains.BRIDGE_ROAD).anyMatch(type ->
                  hex.containsTerrain(type) && original.containsTerrain(type)
                        && hex.getTerrain(type).getExits() != original.getTerrain(type).getExits());
            if (exitsChanged || !BoardFile.hexNode(hex).equals(BoardFile.hexNode(original))) {
                before.put(cell, originals.get(cell).duplicate());
                changed.put(cell, hex);
            }
        });
        // A route drawn over track it does not change still shows its path.
        preview = changed.isEmpty() && path.isEmpty() && layers.isEmpty() ? Preview.NONE : new Preview(target,
              java.util.Collections.unmodifiableMap(before), java.util.Collections.unmodifiableMap(changed), path, notice[0],
              step, Map.copyOf(layers));
        previewFor = key;
        return preview;
    }

    /**
     * Whether the pointer at {@code x, y} (hex widths and heights from its hex's centre, as {@link #pointer} takes them)
     * lies in its hex's core: no farther than {@link #CORE} of the way from the centre to any side. A route drawn by hand
     * takes a hex there ({@link #draw}).
     */
    static boolean core(double x, double y) {
        double px = x * HexTile.MODEL_WIDTH, py = y * HexTile.HEIGHT;
        for (int direction = 0; direction < 6; direction++) {
            float[] border = RailRoute.border(direction);
            if ((px * border[0] + py * border[1]) / (border[0] * border[0] + border[1] * border[1]) > CORE) { return false; }
        }
        return true;
    }

    /**
     * The side of its hex a route stroke's pointer heads for, having moved {@code x, y} (hex widths and heights, as
     * {@link #pointer} takes them) since it entered that hex: the one whose border midpoint lies most in the movement's
     * direction; -1 for a movement under {@link #HEADING}, where it heads nowhere yet. Its place alone would mislead:
     * just inside the hex it still lies in the half it came in by.
     */
    static int heading(double x, double y) {
        double px = x * HexTile.MODEL_WIDTH, py = y * HexTile.HEIGHT;
        if (Math.hypot(px, py) < HEADING * HexTile.MODEL_WIDTH) { return -1; }
        int side = -1;
        double most = 0;
        for (int direction = 0; direction < 6; direction++) {
            float[] border = RailRoute.border(direction);
            double along = px * border[0] + py * border[1];
            if (along > most) { most = along; side = direction; }
        }
        return side;
    }

    /** The session's stroke and selection state, which a preview's dry run changes and {@link #restore} puts back. */
    private record Saved(Coords selected, String selectedObject, String component, String message, String strokeNotice,
          long revision, List<Selection> selection, Set<Coords> painted, Map<Coords, WayLayer> wayLayers,
          Map<Coords, String> wayReceivers, Coords routePaintFrom, double routeEntryX, double routeEntryY, boolean placed,
          Map<Coords, Hex> stroke, List<HexIssue> importIssues, RoutePath drawing) { }

    private Saved save() {
        // Hash copies, not Set.copyOf or Map.copyOf, whose probing is slow for runs of Coords (finishStroke).
        return new Saved(selected, selectedObject, component, message, strokeNotice, revision, new ArrayList<>(selection),
              new java.util.HashSet<>(painted), new LinkedHashMap<>(wayLayers), new LinkedHashMap<>(wayReceivers), routePaintFrom,
              routeEntryX, routeEntryY, placed, new LinkedHashMap<>(stroke), importIssues, drawing);
    }

    private void restore(Saved saved) {
        selected = saved.selected(); selectedObject = saved.selectedObject(); component = saved.component();
        message = saved.message(); strokeNotice = saved.strokeNotice(); revision = saved.revision();
        selection.clear(); selection.addAll(saved.selection());
        painted.clear(); painted.addAll(saved.painted());
        wayLayers.clear(); wayLayers.putAll(saved.wayLayers());
        wayReceivers.clear(); wayReceivers.putAll(saved.wayReceivers());
        routePaintFrom = saved.routePaintFrom(); placed = saved.placed();
        routeEntryX = saved.routeEntryX(); routeEntryY = saved.routeEntryY();
        stroke.clear(); stroke.putAll(saved.stroke());
        importIssues = saved.importIssues(); drawing = saved.drawing();
    }


    /**
     * A rail or maglev vehicle placed on a hex that holds a route of its kind rides it: onto the nearest point of the
     * track's centre line, turned along the track (the way nearer its own turn), at the route's ride height
     * ({@link RailRoute#ride}); of several maglevs, onto the nearest track. Any other object, or a hex without such a
     * route, places as it is.
     */
    private BoardDecoration ride(Coords at, BoardDecoration object) {
        if (!RailRoute.vehicle(object.asset())) { return object; }
        var track = track(at, object);
        if (track == null) { return object; }
        var spot = track.getValue();
        return object.transform(spot.x() / HexTile.MODEL_WIDTH, spot.y() / HexTile.HEIGHT, spot.rotation(), object.mirror(),
              object.scale(), RailRoute.ride(track.getKey()));
    }

    /** Plan distances (model px) within which two tracks count as equally near a vehicle: where crossing maglevs meet. */
    private static final double SAME_SPOT = .5;

    /**
     * The route on {@code hex} that {@code vehicle} rides and its spot there, or null: the track that passes nearest it in
     * plan; of tracks as near (where maglevs cross), the one whose level is nearest the vehicle's.
     */
    private Map.Entry<BoardDecoration, RailRoute.Spot> track(Coords at, BoardDecoration vehicle) {
        Hex hex = board().getHex(at);
        float x = (float) vehicle.x() * HexTile.MODEL_WIDTH, y = (float) vehicle.y() * HexTile.HEIGHT;
        double level = vehicle.placement().level(hex), gap = Double.POSITIVE_INFINITY, rise = Double.POSITIVE_INFINITY;
        Map.Entry<BoardDecoration, RailRoute.Spot> result = null;
        for (var marker : RailRoute.markers(hex, RailRoute.rides(vehicle.asset()))) {
            var spot = RailRoute.snap(hex, marker, x, y, (float) vehicle.rotation(), RailRoute.corridor(hex, at, board()::getHex, RailRoute.onDeck(marker)));
            double markerGap = Math.hypot(spot.x() - x, spot.y() - y), markerRise = Math.abs(marker.placement().level(hex) - level);
            if (markerGap < gap - SAME_SPOT || markerGap <= gap + SAME_SPOT && markerRise < rise) {
                result = Map.entry(marker, spot);
                gap = markerGap;
                rise = markerRise;
            }
        }
        return result;
    }

    /**
     * A stamped train on a hex that holds a route of its kind rides it: its centre snaps onto the track nearest the hex
     * centre, and each vehicle stands its offset along the train further along the track, round curves and across
     * joined hexes ({@link RailRoute#follow}), turned along it, on the marker it stands on and in that marker's hex
     * (a wagon that runs onto a deck rides the deck). Only the vehicles that fit on the track are placed:
     * a vehicle reaches half the way to the nearest other car of the train on each side of its centre (a
     * coupler, which rides between two cars, is no measure of their length), and both those ends must lie on the
     * track. Returns each placed member with the hex it belongs to.
     */
    private List<Map.Entry<Coords, BoardDecoration>> ride(Coords at, List<BoardDecoration> members) {
        var first = members.stream().filter(member -> RailRoute.vehicle(member.asset())).findFirst().orElse(null);
        var track = first == null ? null : track(at, first.transform(0, 0, 0, false, 1, first.placement()));
        if (track == null) { return members.stream().map(member -> Map.entry(at, member)).toList(); }
        var route = track.getKey();
        var start = track.getValue();
        // A train's rows run along its +y.
        List<Float> offsets = members.stream().filter(member -> RailRoute.vehicle(member.asset())
                    && !member.asset().equals(RailRoute.COUPLER))
              .map(member -> (float) member.y() * HexTile.HEIGHT).toList();
        List<Map.Entry<Coords, BoardDecoration>> result = new ArrayList<>();
        for (BoardDecoration member : members) {
            if (!RailRoute.vehicle(member.asset())) { result.add(Map.entry(at, member)); continue; }
            float offset = (float) member.y() * HexTile.HEIGHT;
            float half = (float) offsets.stream().filter(other -> other != offset)
                  .mapToDouble(other -> Math.abs(other - offset) / 2).min().orElse(0);
            if (RailRoute.follow(board()::getHex, at, route, start, offset - half).beyond() > FITS
                  || RailRoute.follow(board()::getHex, at, route, start, offset + half).beyond() > FITS) { continue; }
            var onTrack = RailRoute.follow(board()::getHex, at, route, start, offset);
            var spot = onTrack.spot();
            result.add(Map.entry(onTrack.at(), member.transform(spot.x() / HexTile.MODEL_WIDTH, spot.y() / HexTile.HEIGHT,
                  spot.rotation() + member.rotation(), member.mirror(), member.scale(), RailRoute.ride(onTrack.marker()))));
        }
        return result;
    }

    /** How far (model px) a stamped vehicle's end may lie past the track's end and still fit: rounding only. */
    private static final float FITS = .01f;

    /** A side's short name, N to NW. */
    private static String sideName(int direction) { return Messages.getString("BoardEditor.sides").split(",")[direction]; }

    /**
     * A press on an already selected item grabs the whole selection, and its drag moves it (whole hexes commit
     * atomically on release); a press elsewhere selects without grabbing, so only selected hexes or objects ever move.
     * A grouped object selects its whole group; pressing an item of a larger selection and releasing without a drag
     * selects that item (with its group) alone, pressing a member of a wholly selected group that way selects the member
     * alone, and while drilled in, a sibling's click selects only the sibling. Shift-click
     * toggles; Shift drags do nothing here (the view boxes them).
     */
    private void selectPointer(Coords at, double x, double y, boolean drag, String object, boolean additive) {
        String pickedRoute = object;
        var rail = RailRoute.derived(board().getHex(at), "").stream()
              .filter(route -> route.id().equals(pickedRoute)).findFirst().orElse(null);
        if (rail != null) { object = ""; }
        Selection hit = new Selection(at, object == null ? "" : object);
        // A board click selects the ground unless it hit an object; a prior Layers choice belongs to that inspection.
        if (!drag && hit.object().isEmpty()) { component = rail != null && RailRoute.onDeck(rail) ? "bridge" : "ground"; }
        if (drag) { pendingDrill = null; }
        if (additive && drag) { return; }
        // Only presses need the group, so a drag's move events never consult the board's groups.
        List<Selection> items = drag || hit.object().isEmpty() ? List.of(hit) : expand(hit);
        if (additive) {
            if (!selection.isEmpty() && selection.iterator().next().object().isEmpty() != hit.object().isEmpty()) { selection.clear(); }
            if (selection.containsAll(items)) { items.forEach(selection::remove); }
            else { selection.addAll(items); }
            selected = at; selectedObject = selection.isEmpty() ? "" : hit.object(); return;
        }
        if (!drag) {
            pendingDrill = null;
            boolean grabbed = selection.contains(hit);
            // A click (no drag) on part of a larger selection narrows it: to the item's group, or within a wholly
            // selected group to the member.
            if (grabbed && selection.size() > items.size()) { pendingDrill = items; }
            else if (grabbed && items.size() > 1 && selection.containsAll(items)) { pendingDrill = List.of(hit); }
            else if (!grabbed && items.size() > 1 && selection.size() == 1 && items.contains(selection.iterator().next())) {
                selection.clear(); selection.add(hit);
            } else if (!grabbed) { selection.clear(); selection.addAll(items); }
            // An authored building's member hex is inspected, never dragged off: an emptied member removes the building.
            boolean member = hit.object().isEmpty() && board().getHex(at).getBuildingReference() != null;
            selected = at; selectedObject = hit.object(); dragOrigin = grabbed && !member ? at : null; dragX = x; dragY = y;
            movingObjects.clear();
            // Connected routes are edited by painting/erasing hexes, so a moving selection leaves its route markers in place.
            for (Selection item : selection) {
                if (grabbed && !item.object().isEmpty()) {
                    decoration(item).filter(d -> !megamek.common.board.RailRoute.isRoute(d)).ifPresent(d -> movingObjects.put(item, d));
                }
            }
        } else if (dragOrigin != null) {
            require(!movingObjects.isEmpty() || selectedObject.isEmpty(), Messages.getString("BoardEditor.routeChange"));
            if (movingObjects.isEmpty()) { moveTarget = at; }
            else {
                double dx = x - dragX, dy = y - dragY;
                if (magnetic) {
                    var grabbed = movingObjects.entrySet().stream().filter(entry -> entry.getValue().id().equals(selectedObject)).findFirst()
                          .orElse(movingObjects.entrySet().iterator().next());
                    var original = grabbed.getValue();
                    var candidate = original.transform(original.x() + dx, original.y() + dy, original.rotation(), original.mirror(), original.scale(), original.placement());
                    var snapped = BoardEditorSnapping.snap(board(), blueprint, grabbed.getKey().coords(), candidate,
                          movingObjects.values().stream().map(BoardDecoration::id).collect(java.util.stream.Collectors.toSet()));
                    dx += snapped.x() - candidate.x(); dy += snapped.y() - candidate.y();
                }
                Map<Selection, BoardDecoration> moved = new LinkedHashMap<>();
                for (var entry : movingObjects.entrySet()) {
                    BoardDecoration original = entry.getValue();
                    BoardDecoration next = original.transform(original.x() + dx, original.y() + dy, original.rotation(),
                          original.mirror(), original.scale(), original.placement());
                    // Resolve support at the future owner, while keeping ownership stable until release.
                    var destination = positioned(entry.getKey().coords(), next);
                    next = next.transform(next.x(), next.y(), next.rotation(), next.mirror(), next.scale(), destination.getValue().placement());
                    moved.put(entry.getKey(), next);
                }
                moveObjects(moved);
            }
        }
    }

    /**
     * On {@code receiver}'s surface of {@code hex} at the brush's offset. Ice lies on its hex's ground or water; an object
     * put down there stands on the ice.
     */
    private static BoardDecoration.Placement onSupport(Hex hex, String receiver, BoardDecoration prototype) {
        String support = receiver.equals("ground") && hex.containsTerrain(Terrains.ICE) ? "ice" : receiver;
        double offset = prototype.placement().offset() == null ? 0 : prototype.placement().offset();
        return BoardDecoration.Placement.on(support, offset);
    }

    /** A new copy of the brush's object at {@code x, y} on {@code placement}. */
    private static BoardDecoration brushCopy(BoardDecoration prototype, double x, double y, BoardDecoration.Placement placement) {
        return pickArt(prototype.duplicate().transform(x, y, prototype.rotation(), prototype.mirror(), prototype.scale(), placement), BoardBillboard.DEFAULT);
    }

    /**
     * {@code object} with each {@link BoardBillboard#RANDOM} panel of a billboard given a pick of the billboard art,
     * preferring a picture its other panels and {@code shown} (the billboard as it was) do not show; a blank panel without
     * any art. The board stores the picks, so they stay the same for every player until chosen again.
     */
    private static BoardDecoration pickArt(BoardDecoration object, BoardBillboard shown) {
        var billboard = object.billboard();
        if (!billboard.decals().contains(BoardBillboard.RANDOM)) { return object; }
        var art = billboard.art();
        var decals = new ArrayList<>(billboard.decals());
        for (int panel = 0; panel < decals.size(); panel++) {
            if (!decals.get(panel).equals(BoardBillboard.RANDOM)) { continue; }
            var fresh = new ArrayList<>(art);
            fresh.removeAll(decals);
            fresh.removeAll(shown.decals());
            var from = fresh.isEmpty() ? art : fresh;
            decals.set(panel, from.isEmpty() ? "" : from.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(from.size())));
        }
        return object.withBillboard(billboard.withDecals(decals));
    }

    /**
     * Adds a new object to the hex its anchor lies in, seen from {@code owner}, and makes it the selection; unless that hex
     * holds its twin ({@link #twin}), a hidden copy: then it adds nothing and returns the twin.
     */
    private Selection addObject(Coords owner, BoardDecoration object) {
        var destination = positioned(owner, object);
        Coords at = destination.getKey().coords();
        BoardDecoration placedObject = destination.getValue();
        Selection twin = twin(at, placedObject);
        if (twin != null) { return twin; }
        selected = at;
        change(selected, hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.add(placedObject); hex.setDecorations(objects); });
        selectedObject = object.id();
        selection.clear(); selection.add(new Selection(selected, selectedObject));
        return null;
    }

    /** The object of the hex at {@code at} that {@code object} would be a twin of ({@link BoardDecoration#twin}), or null. */
    private Selection twin(Coords at, BoardDecoration object) {
        return board().getHex(at).getDecorations().stream().filter(object::twin).findFirst()
              .map(found -> new Selection(at, found.id())).orElse(null);
    }

    /**
     * Selects {@code twins}, the objects a placement found already placed, with their groups, as a Select click would;
     * the tool stays. Returns the notice that says so.
     */
    private String selectPlaced(List<Selection> twins) {
        selection.clear();
        twins.forEach(twin -> selection.addAll(expand(twin)));
        selected = twins.getFirst().coords(); selectedObject = twins.getFirst().object();
        return Messages.getString("BoardEditor.alreadyPlaced");
    }

    /**
     * Puts a copy of the armed object brush at {@code value} "x,y,level" of the inspected hex (x and y in hex widths and
     * heights): on {@code receiver}'s surface, or at the fixed level when {@code receiver} is empty. One undo step; the
     * new object becomes the selection. Where its twin stands it adds nothing and selects the twin with its group.
     */
    private void place(String receiver, String value) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        require(sampledHex == null, Messages.getString("BoardEditor.chooseObjectCard"));
        BoardDecoration prototype = paintTemplate.getDecorations().stream().findFirst().orElse(null);
        require(prototype != null && !megamek.common.board.RailRoute.isRoute(prototype), Messages.getString("BoardEditor.chooseObjectCard"));
        String[] spot = value.split(",");
        require(spot.length == 3, "Place at x,y,level.");
        Hex hex = board().getHex(selected);
        BoardDecoration.Placement placement;
        if (receiver.isEmpty()) {
            require(prototype.kind().equals("prop"), Messages.getString("BoardEditor.decalNeedsSurface"));
            placement = BoardDecoration.Placement.absolute(Double.parseDouble(spot[2]));
        } else {
            require(supports(hex, receiver), Messages.getString("BoardEditor.nothingToPlaceOn"));
            placement = onSupport(hex, receiver, prototype);
        }
        Selection twin = addObject(selected, brushCopy(prototype, Double.parseDouble(spot[0]), Double.parseDouble(spot[1]), placement));
        if (twin != null) { message = selectPlaced(List.of(twin)); }
        finishStroke();
    }

    /**
     * Moves the inspected hex's {@link Level} {@code key} to {@code value}, an absolute level clamped to the level's range:
     * the ground (the hex level), the water bed (the depth), a bridge's deck or a structure's top over the whole
     * connected structure (each hex at its own height for the same top), a canopy, or a placed object (its offset above
     * its support, or its fixed level). A live drag is one undo step.
     */
    private void height(String key, double value) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        Hex hex = board().getHex(selected);
        Level level = levels(hex).stream().filter(l -> l.key().equals(key)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException(Messages.getString("BoardEditor.nothingToMove", key)));
        require(level.min() < level.max(), Messages.getString("BoardEditor.decalOnSurface"));
        double target = Math.max(level.min(), Math.min(level.max(), value));
        int whole = (int) Math.round(target);
        if (level.object()) {
            BoardDecoration object = hex.getDecorations().stream().filter(d -> d.id().equals(key)).findFirst().orElseThrow();
            boolean fixed = object.placement().mode().equals("absolute");
            BoardDecoration next = objectValue(object, fixed ? "level" : "offset",
                  Double.toString(fixed ? target : target - (level.level() - object.placement().offset())));
            checkBuildingGeometry(selected, object, next);
            change(selected, h -> h.setDecorations(h.getDecorations().stream().map(d -> d.id().equals(key) ? next : d).toList()));
        } else if (key.equals("ground")) {
            change(selected, h -> h.setLevel(whole));
        } else if (key.equals("water")) {
            change(selected, h -> setTerrain(h, "water", hex.getLevel() - whole));
        } else {
            var component = blueprint.component(key);
            var field = component.fields().stream().filter(BoardEditorBlueprint.Field::height).findFirst().orElseThrow();
            // A structure whose own terrain differs from its height field joins its neighbours through that terrain's exits.
            String structure = component.fields().getFirst().terrain();
            List<Coords> cells = component.category().equals("Structures") && !structure.equals(field.terrain())
                  ? structure(selected, Terrains.getType(structure)) : List.of(selected);
            for (Coords cell : cells) {
                change(cell, h -> setTerrain(h, field.terrain(), (int) Math.max(field.min(),
                      Math.min(Math.max(field.max(), h.terrainLevel(Terrains.getType(field.terrain()))), whole - h.getLevel()))));
            }
        }
        if (!continuousEdit) { finishStroke(); }
    }

    private void requireBridge() {
        require(selected != null && board().getHex(selected).containsTerrain(Terrains.BRIDGE), Messages.getString("BoardEditor.selectBridgeFirst"));
    }

    /** Retypes each hex of the bridge span at {@code start} ({@link #structure}) from its stored type ({@code type}). */
    private void bridgeSpan(Coords start, java.util.function.UnaryOperator<HexAppearance> type) {
        for (Coords cell : structure(start, Terrains.BRIDGE)) {
            change(cell, hex -> {
                if (!hex.containsTerrain(Terrains.BRIDGE)) { return; }
                var styles = new LinkedHashMap<>(hex.getAppearance());
                styles.put("bridge", type.apply(styles.get("bridge")));
                hex.setAppearance(styles);
            });
        }
    }

    /**
     * Makes the bridge span at {@code start} built or natural, as the Edit panel's type and a bridge card's stroke do: a
     * built span gets piers ({@link HexAppearance#PILLARS}) on every hex when any of its hexes has them, so its Pillars
     * toggle stays uniform, and one design: a truss ({@code truss} TRUE, a Truss card) or the standard deck (FALSE, the
     * Built card), or the span's own ({@code null}, {@link #spanTruss}); a truss keeps the span's colours. A natural
     * span has neither.
     */
    private void typeSpan(Coords start, boolean built, Boolean truss) {
        boolean piers = built && structure(start, Terrains.BRIDGE).stream()
              .anyMatch(at -> HexAppearance.pillars(board().getHex(at).getAppearance()));
        var colours = spanTruss(start);
        if (truss != null) { colours = truss ? Objects.requireNonNullElse(colours, BoardDecoration.Colours.NONE) : null; }
        var type = built ? HexAppearance.built(colours != null, piers, colours) : HexAppearance.NATURAL_BRIDGE;
        bridgeSpan(start, current -> type);
    }

    /** The bridge span's truss colours ({@link megamek.common.board.BridgeSpan#truss}), null for the standard design. */
    private BoardDecoration.Colours spanTruss(Coords start) {
        return megamek.common.board.BridgeSpan.truss(at -> board().getHex(at).getAppearance(), structure(start, Terrains.BRIDGE));
    }

    /** Whether a hex's stored bridge type has piers. */
    private static boolean piers(HexAppearance type) { return type != null && HexAppearance.pillars(Map.of("bridge", type)); }

    /**
     * A bridge type as a choice for a hex: a built card keeps the hex's piers ({@link HexAppearance#PILLARS}); its own
     * design and colours stay the card's, which {@link #typeSpan} then gives the whole span.
     */
    private static java.util.function.UnaryOperator<HexAppearance> retype(HexAppearance type) {
        Map<String, HexAppearance> card = Map.of("bridge", type);
        if (!Boolean.TRUE.equals(HexAppearance.bridgeBuilt(card))) { return current -> type; }
        return current -> HexAppearance.built(HexAppearance.truss(card), HexAppearance.pillars(card) || piers(current),
              type.colours());
    }

    /**
     * The editor's half of the one legacy conversion, run before a stroke is committed. The document holds no token the
     * conversion takes, so a stroke hex that holds one now got it in this stroke (a {@code TERRAIN} command's Fluff
     * value or a pasted foreign hex text): it gets that token's objects
     * ({@link BoardSceneryLayouts#convert(Board, java.util.Collection)}) and loses the token ({@link #dropTakenTokens}),
     * in the same undo step, so Undo returns the hex as it was before the value was set.
     * The conversion writes only the hexes it decodes and their neighbours (the lane road's exits, a set's emblem on its
     * centre hex), so the stroke records their before-state first.
     */
    private void convertTokens() {
        List<Coords> typed = stroke.keySet().stream()
              .filter(at -> BoardSceneryLayouts.withoutTakenTokens(board().getHex(at)) != board().getHex(at)).toList();
        if (typed.isEmpty()) { return; }
        typed.forEach(this::beginHexEdit);
        List<HexIssue> found = BoardSceneryLayouts.convert(board(), typed);
        dropTakenTokens(board(), typed);
        if (!found.isEmpty()) { importIssues = java.util.stream.Stream.concat(importIssues.stream(), found.stream()).toList(); }
    }

    /** Removes the tokens the conversion took from {@code hexes} ({@link BoardSceneryLayouts#withoutTakenTokens}). */
    private static void dropTakenTokens(Board board, List<Coords> hexes) {
        Map<megamek.common.board.BoardLocation, Hex> kept = new LinkedHashMap<>();
        for (Coords at : hexes) {
            Hex hex = board.getHex(at), next = BoardSceneryLayouts.withoutTakenTokens(hex);
            if (next != hex) { kept.put(megamek.common.board.BoardLocation.of(at, board.getBoardId()), next); }
        }
        if (!kept.isEmpty()) { board.setHexes(kept); }
    }

    /**
     * The hexes of the structure at {@code start}: a bridge's {@link megamek.common.board.BridgeSpan}, the one span of
     * its type, piers and deck height; another structure's hexes joined through the exits of its {@code type} terrain,
     * either hex's.
     */
    private List<Coords> structure(Coords start, int type) {
        if (type == Terrains.BRIDGE) { return megamek.common.board.BridgeSpan.of(board()::getHex, start); }
        return connected(start, type, hex -> hex.getTerrain(type));
    }

    /**
     * The hexes joined to {@code start} by {@code type}'s exits, as each hex's {@code terrain} of that type gives them:
     * one structure.
     */
    private List<Coords> connected(Coords start, int type, java.util.function.Function<Hex, Terrain> terrain) {
        Set<Coords> found = new LinkedHashSet<>(List.of(start));
        ArrayDeque<Coords> open = new ArrayDeque<>(found);
        while (!open.isEmpty()) {
            Coords at = open.pop();
            Terrain own = terrain.apply(board().getHex(at));
            for (int direction = 0; direction < 6; direction++) {
                Coords next = at.translated(direction);
                Terrain other = board().contains(next) ? terrain.apply(board().getHex(next)) : null;
                if (other != null && (own != null && (own.getExits() & (1 << direction)) != 0
                      || (other.getExits() & (1 << ((direction + 3) % 6))) != 0) && found.add(next)) { open.add(next); }
            }
        }
        return List.copyOf(found);
    }

    /** The kind of structure standing or destroyed in the hex: BUILDING, FUEL_TANK or BRIDGE; 0 for none. */
    private static int structureKind(Hex hex) {
        for (int type : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.BRIDGE }) {
            if (standingOrRuin(hex, type) != null) { return type; }
        }
        return 0;
    }

    /** The hex's terrain of this type, standing or the ruin of one destroyed; null for neither. */
    private static Terrain standingOrRuin(Hex hex, int type) {
        Terrain standing = hex.getTerrain(type);
        return standing != null ? standing : hex.getRuin(type);
    }

    /** The construction of the structure standing in the hex, which its rubble takes (Board#collapseBuilding). */
    private static int construction(Hex hex) {
        return Math.max(hex.terrainLevel(Terrains.BUILDING), Math.max(hex.terrainLevel(Terrains.BRIDGE),
              hex.terrainLevel(Terrains.FUEL_TANK)));
    }

    /** The layout of the chosen stamp brush, or null when the brush is not a stamp. */
    private String stampLayout() {
        var asset = brushComponent.isEmpty() && paintTemplate.getDecorations().isEmpty() ? blueprint.asset(brushKey) : null;
        return asset == null ? null : asset.layout();
    }

    private List<Coords> movePreview() {
        if (!movingObjects.isEmpty()) {
            return selection.stream().filter(item -> !item.object().isEmpty()).map(item -> board().getHex(item.coords()).getDecorations().stream()
                  .filter(object -> object.id().equals(item.object())).findFirst().map(object -> positioned(item.coords(), object).getKey().coords()).orElse(item.coords())).toList();
        }
        if (dragOrigin == null || moveTarget == null) { return List.of(); }
        var delta = moveTarget.toCube().subtract(dragOrigin.toCube());
        return selection.stream().map(item -> item.coords().toCube().add(delta).toOffset()).toList();
    }

    private void moveHexes() {
        if (moveTarget == null || dragOrigin == null || moveTarget.equals(dragOrigin)) { return; }
        var delta = moveTarget.toCube().subtract(dragOrigin.toCube());
        List<Coords> targets = movePreview();
        if (targets.stream().anyMatch(at -> !board().contains(at))) { message = Messages.getString("BoardEditor.selectionOutside"); return; }
        Map<Coords, Hex> moved = new LinkedHashMap<>();
        for (Selection item : selection) { moved.put(item.coords().toCube().add(delta).toOffset(), board().getHex(item.coords()).duplicate()); }
        for (Selection item : selection) {
            if (!moved.containsKey(item.coords())) {
                Hex empty = new Hex(0); empty.setTheme(board().getHex(item.coords()).getTheme());
                change(item.coords(), hex -> copyInto(empty, hex));
            }
        }
        moved.forEach((at, source) -> change(at, hex -> { copyInto(source, hex); hex.setDecorations(source.getDecorations()); }));
        Set<Coords> touched = new java.util.HashSet<>(moved.keySet());
        selection.forEach(item -> touched.add(item.coords()));
        keepJoinedSides(touched);
        selected = moveTarget; selection.clear(); targets.forEach(at -> selection.add(new Selection(at, "")));
    }

    private void deleteSelection() {
        for (Selection item : selection) {
            change(item.coords(), hex -> {
                if (item.object().isEmpty()) { copyInto(new Hex(0), hex); }
                else { hex.setDecorations(hex.getDecorations().stream().filter(d -> !d.id().equals(item.object())).toList()); }
            });
        }
        unlinkRoutes(selection.stream().map(Selection::coords).toList());
        selectedObject = ""; selection.clear(); finishStroke();
    }

    /** Clears a moved route's sides that no route on the same layer returns. */
    private void unjoined(Coords at, int direction) {
        Hex hex = board().getHex(at), neighbor = board().getHex(at.translated(direction));
        for (var route : RailRoute.derived(hex, "")) {
            if ((route.connections() & RailRoute.side(direction)) != 0
                  && RailRoute.joined(hex, route, direction, neighbor) == null) {
                boolean deck = RailRoute.onDeck(route);
                int sides = route.connections() & ~RailRoute.side(direction);
                change(at, h -> {
                    RailRoute.lay(h, deck, sides, RailRoute.layout(sides, route.tracks()), RailRoute.kind(route));
                });
            }
        }
    }

    /** Clears the sides that neighbouring markers keep towards {@code cells} that no marker there returns any more. */
    private void unlinkRoutes(List<Coords> cells) {
        for (Coords at : cells) {
            if (at == null || !board().contains(at)) { continue; }
            for (int direction = 0; direction < 6; direction++) {
                Coords neighbor = at.translated(direction);
                if (board().contains(neighbor)) { unjoined(neighbor, (direction + 3) % 6); }
            }
        }
    }

    /**
     * Clears every route side across the edges of {@code cells} that the marker on the other side does not return, so a
     * moved, pasted or vacated hex neither keeps open ends nor leaves its old neighbours pointing at it.
     */
    private void keepJoinedSides(java.util.Collection<Coords> cells) {
        for (Coords at : cells) {
            for (int direction = 0; direction < 6; direction++) {
                Coords neighbor = at.translated(direction);
                if (!board().contains(neighbor)) { continue; }
                unjoined(at, direction);
                unjoined(neighbor, (direction + 3) % 6);
            }
        }
    }

    /**
     * Ends a pointer gesture after its stroke: a press on part of a larger selection that never dragged narrows the
     * selection to that item (its group), or drills into a wholly selected group's member.
     */
    public void release() {
        if (pendingDrill != null && selection.containsAll(pendingDrill)) {
            selection.clear(); selection.addAll(pendingDrill);
            selected = pendingDrill.getFirst().coords(); selectedObject = pendingDrill.getFirst().object();
            revision++;
        }
        pendingDrill = null;
    }

    /**
     * A box (or a Ctrl-click) over {@code items}, the objects and hexes it covers. It takes the objects, or the hexes
     * when it covers no object; adding to or removing from a selection takes the selection's own kind. Unknown objects
     * are ignored, and an object counts with its whole group.
     */
    public void select(List<Selection> items, SelectMode mode) {
        pendingDrill = null;
        // A box leaves route markers out: they are edited by painting, and would otherwise pin every box it touches.
        List<Selection> objects = items.stream().filter(item -> !item.object().isEmpty() && board().contains(item.coords())
              && decoration(item).filter(d -> mode == SelectMode.REMOVE || !megamek.common.board.RailRoute.isRoute(d)).isPresent())
              .flatMap(item -> expand(item).stream()).distinct().toList();
        List<Selection> hexes = items.stream().filter(item -> item.object().isEmpty() && board().contains(item.coords())).distinct().toList();
        boolean kept = mode != SelectMode.REPLACE && !selection.isEmpty();
        List<Selection> found = kept ? (selection.iterator().next().object().isEmpty() ? hexes : objects)
              : objects.isEmpty() ? hexes : objects;
        if (!kept) { selection.clear(); }
        if (mode == SelectMode.REMOVE) { found.forEach(selection::remove); } else { selection.addAll(found); }
        if (mode != SelectMode.REMOVE && !found.isEmpty()) { selected = found.getFirst().coords(); selectedObject = found.getFirst().object(); }
        else if (!selection.isEmpty() && !selection.contains(new Selection(selected, selectedObject))) {
            selected = selection.iterator().next().coords(); selectedObject = selection.iterator().next().object();
        } else if (selection.isEmpty()) { selectedObject = ""; }
        if (selectedObject.isEmpty()) { component = "ground"; }
        tool = Tool.SELECT; message = "";
        revision++;
    }

    private java.util.Optional<BoardDecoration> decoration(Selection item) {
        return board().getHex(item.coords()).getDecorations().stream().filter(d -> d.id().equals(item.object())).findFirst();
    }

    /** The whole group of a grouped object, or the object alone; a group with one remaining member acts ungrouped. */
    private List<Selection> expand(Selection item) {
        String group = decoration(item).map(BoardDecoration::group).orElse(null);
        List<Selection> members = group == null ? List.of() : groups().getOrDefault(group, List.of());
        return members.size() > 1 ? members : List.of(item);
    }

    /**
     * Members per group, from one board scan per committed document version: edits, undo, redo and replace change
     * {@code currentId}, pointer events do not. An unfinished stroke may have moved members, so it is not cached.
     */
    private Map<String, List<Selection>> groups() {
        if (groupsId != currentId || !stroke.isEmpty()) {
            Map<String, List<Selection>> index = new LinkedHashMap<>();
            for (int y = 0; y < board().getHeight(); y++) {
                for (int x = 0; x < board().getWidth(); x++) {
                    Coords at = new Coords(x, y);
                    for (BoardDecoration object : board().getHex(at).getDecorations()) {
                        if (object.group() != null) {
                            index.computeIfAbsent(object.group(), ignored -> new ArrayList<>()).add(new Selection(at, object.id()));
                        }
                    }
                }
            }
            groups = index; groupsId = stroke.isEmpty() ? currentId : -1;
        }
        return groups;
    }

    /** The distinct groups of the selected objects, with "" for an ungrouped object; O(selection). */
    private List<String> selectionGroups() {
        return selection.stream().filter(item -> !item.object().isEmpty()).map(this::decoration).flatMap(java.util.Optional::stream)
              .map(object -> object.group() == null ? "" : object.group()).distinct().toList();
    }

    private String wholeGroup() { return wholeGroup(selectionGroups(), selection.size()); }

    /** One new group for the selected objects and every member of their groups, as one undo step. */
    private void group() {
        long objects = selection.stream().filter(item -> !item.object().isEmpty()).count();
        require(objects >= 2, Messages.getString("BoardEditor.groupTooFew"));
        require(wholeGroup().isEmpty(), Messages.getString("BoardEditor.groupAlready"));
        Set<Selection> members = new LinkedHashSet<>();
        selection.stream().filter(item -> !item.object().isEmpty()).forEach(item -> members.addAll(expand(item)));
        // A route marker belongs to its hexes' route, never to a group.
        members.removeIf(item -> decoration(item).filter(megamek.common.board.RailRoute::isRoute).isPresent());
        require(members.size() >= 2, Messages.getString("BoardEditor.groupNoRoutes"));
        setGroup(members, UUID.randomUUID().toString());
        // Merged groups may add members beyond the selection; the selection becomes the whole new group.
        selection.clear(); selection.addAll(members);
        message = Messages.getString("BoardEditor.grouped", members.size());
    }

    private void ungroup() {
        var chosen = selectionGroups().stream().filter(group -> !group.isEmpty()).toList();
        require(!chosen.isEmpty(), Messages.getString("BoardEditor.ungroupNone"));
        List<Selection> members = chosen.stream().flatMap(group -> groups().getOrDefault(group, List.of()).stream()).toList();
        setGroup(members, null);
        message = Messages.getString("BoardEditor.ungrouped", members.size());
    }

    private void setGroup(java.util.Collection<Selection> members, String group) {
        for (Selection item : members) {
            change(item.coords(), hex -> hex.setDecorations(hex.getDecorations().stream()
                  .map(object -> object.id().equals(item.object()) ? object.withGroup(group) : object).toList()));
        }
        finishStroke();
    }

    /** Copies of every member in one new group, overlapping the originals; the copies become the selection. */
    private void duplicateGroup() {
        List<Selection> members = List.copyOf(selection);
        List<BoardDecoration> originals = members.stream().map(item -> decoration(item).orElseThrow()).toList();
        require(originals.stream().noneMatch(megamek.common.board.RailRoute::isRoute), Messages.getString("BoardEditor.routeExtend"));
        List<BoardDecoration> copies = BoardDecoration.copies(originals);
        selection.clear();
        for (int i = 0; i < members.size(); i++) {
            Selection member = members.get(i);
            BoardDecoration copy = copies.get(i);
            change(member.coords(), hex -> { var objects = new ArrayList<>(hex.getDecorations()); objects.add(copy); hex.setDecorations(objects); });
            selection.add(new Selection(member.coords(), copy.id()));
            if (member.object().equals(selectedObject)) { selected = member.coords(); selectedObject = copy.id(); }
        }
        finishStroke();
    }

    /**
     * Moves, turns, scales or mirrors a whole group as one transform relative to the group as the edit found it: x and y
     * move by hex widths and heights, rotation turns by degrees, scale multiplies and mirror (true) reflects; turns,
     * scales and mirrors pivot about the members' centroid in metric space. A live value drag keeps transforming the
     * members it started from, so each of its values is the whole change so far.
     */
    private void editGroup(String property, String value) {
        require(Set.of("x", "y", "rotation", "scale", "mirror", "colour0", "colour1", "colour2", "colour3").contains(property),
              "Unknown group property " + property);
        require(!wholeGroup().isEmpty(), Messages.getString("BoardEditor.selectGroupFirst"));
        if (property.startsWith("colour")) {
            // A group repaints the members like the inspected one at once, in one undo step: for a train's vehicle every
            // vehicle of its route kind (a stamped train's cars, whatever their model), else every placed model of the
            // same file. Parked cars beside a converted maglev train keep theirs, as do routes and decals.
            var chosen = decoration(new Selection(selected, selectedObject)).orElseThrow();
            var train = RailRoute.vehicle(chosen.asset()) ? RailRoute.rides(chosen.asset()) : null;
            for (Selection item : selection) {
                var member = decoration(item).orElseThrow();
                boolean alike = train != null ? RailRoute.vehicle(member.asset()) && RailRoute.rides(member.asset()) == train
                      : member.asset().equals(chosen.asset());
                if (!alike || !member.kind().equals("prop") || RailRoute.isRoute(member)) { continue; }
                change(item.coords(), hex -> hex.setDecorations(hex.getDecorations().stream()
                      .map(d -> d.id().equals(member.id()) ? objectValue(d, property, value) : d).toList()));
            }
            if (!continuousEdit) { finishStroke(); }
            return;
        }
        if (groupBase.isEmpty()) {
            Map<Selection, BoardDecoration> found = new LinkedHashMap<>();
            selection.forEach(item -> found.put(item, decoration(item).orElseThrow()));
            require(found.values().stream().noneMatch(megamek.common.board.RailRoute::isRoute), Messages.getString("BoardEditor.routeCentred"));
            groupBase = found;
        }
        Map<Selection, BoardDecoration> members = groupBase;
        double centreX = 0, centreY = 0;
        for (var entry : members.entrySet()) {
            centreX += BoardEditorSnapping.globalX(entry.getKey().coords(), entry.getValue().x()) / members.size();
            centreY += BoardEditorSnapping.globalY(entry.getKey().coords(), entry.getValue().y()) / members.size();
        }
        double number = property.equals("mirror") ? 0 : Double.parseDouble(value);
        boolean mirror = property.equals("mirror") && Boolean.parseBoolean(value);
        double turn = property.equals("rotation") ? number : 0;
        double factor = property.equals("scale") ? number : 1;
        require(factor > 0 && Double.isFinite(factor), Messages.getString("BoardEditor.scalePositive"));
        double cos = Math.cos(Math.toRadians(turn)), sin = Math.sin(Math.toRadians(turn));
        double width = HexTile.MODEL_WIDTH, height = HexTile.HEIGHT;
        Map<Selection, BoardDecoration> moved = new LinkedHashMap<>();
        for (var entry : members.entrySet()) {
            Coords owner = entry.getKey().coords();
            BoardDecoration object = mirror ? entry.getValue().flip(true, false) : entry.getValue();
            double dx = (BoardEditorSnapping.globalX(owner, entry.getValue().x()) - centreX) * width;
            double dy = (BoardEditorSnapping.globalY(owner, entry.getValue().y()) - centreY) * height;
            double gx = centreX + (mirror ? -dx : (dx * cos - dy * sin) * factor) / width;
            double gy = centreY + (mirror ? dy : (dx * sin + dy * cos) * factor) / height;
            if (property.equals("x")) { gx += number; }
            if (property.equals("y")) { gy += number; }
            BoardDecoration next = object.transform(gx - BoardEditorSnapping.globalX(owner, 0), gy - BoardEditorSnapping.globalY(owner, 0),
                  turn == 0 ? object.rotation() : Math.IEEEremainder(object.rotation() + turn, 360), object.mirror(),
                  object.scale() * factor, object.placement());
            var destination = positioned(owner, next);
            moved.put(destination.getKey(), destination.getValue());
        }
        moveObjects(moved);
        if (!continuousEdit) { finishStroke(); }
    }

    /**
     * Whether {@code hex} offers the {@code receiver} surface: the ground always does, ice needs ice, others need their
     * component.
     */
    private boolean supports(Hex hex, String receiver) {
        if (receiver.equals("ice")) { return hex.containsTerrain(Terrains.ICE); }
        return receiver.equals("ground") || offers(receiver, hex::containsTerrain);
    }

    /** Whether a component offering the {@code receiver} surface is in the hex, as {@code present} finds its terrains. */
    private boolean offers(String receiver, java.util.function.IntPredicate present) {
        return blueprint.components().stream().filter(definition -> definition.receiver().equals(receiver))
              .anyMatch(definition -> definition.isPresent(name -> present.test(Terrains.getType(name))));
    }

    /** Re-validates every hex, as after loading or creating a document. */
    void validateAll() {
        issues.clear(); copies.clear();
        List<Coords> cells = new ArrayList<>(board().getWidth() * board().getHeight());
        for (int y = 0; y < board().getHeight(); y++) {
            for (int x = 0; x < board().getWidth(); x++) { cells.add(new Coords(x, y)); }
        }
        validate(cells);
    }

    /**
     * Re-validates {@code cells}: the game's own hex and building-exit rules ({@link Board#hexErrors}, the per-hex step
     * of the board validation), the roads and bridges that do not join up ({@link BoardConnectivityCheck}, each with its
     * recommended fix), each placed object's art and support, and identical copies ({@link #stacks}), one issue per
     * copied object at that object. Both checks read only the hex and its neighbours. Issues never block saving.
     */
    private void validate(java.util.Collection<Coords> cells) {
        for (Coords at : cells) {
            if (!board().contains(at)) { continue; }
            List<HexIssue> found = new ArrayList<>();
            board().hexErrors(at).forEach(text -> found.add(new HexIssue(at, "", text)));
            for (var issue : BoardConnectivityCheck.findIssuesAt(board(), at)) {
                found.add(new HexIssue(at, "", Messages.getString("BoardEditor.issueRoadBridge", issue.message(), issue.fix())));
            }
            Hex hex = board().getHex(at);
            laneIssues(hex, at).forEach(text -> found.add(new HexIssue(at, "", text)));
            for (BoardDecoration object : hex.getDecorations()) {
                if (!assetExists(object)) { found.add(new HexIssue(at, object.id(), Messages.getString("BoardEditor.issueArtMissing", object.asset()))); }
                var receiver = object.placement().receiver();
                // What stood on a destroyed structure stands again with it.
                if (receiver != null && !supports(hex, receiver.terrain())
                      && !offers(receiver.terrain(), type -> hex.getRuin(type) != null)) {
                    String support = blueprint.components().stream().filter(definition -> definition.receiver().equals(receiver.terrain()))
                          .map(definition -> definition.label().toLowerCase(java.util.Locale.ROOT)).findFirst().orElse(receiver.terrain());
                    found.add(new HexIssue(at, object.id(), Messages.getString("BoardEditor.issueNoSupport", receiver.surface(), support)));
                }
            }
            int copied = 0;
            for (List<BoardDecoration> stack : stacks(hex)) {
                found.add(new HexIssue(at, stack.getFirst().id(),
                      Messages.getString("BoardEditor.issueCopies", stack.size() - 1, label(stack.getFirst()))));
                copied += stack.size() - 1;
            }
            if (copied == 0) { copies.remove(at); } else { copies.put(at, copied); }
            if (found.isEmpty()) { issues.remove(at); } else { issues.put(at, List.copyOf(found)); }
        }
        var imported = importIssues.stream().filter(issue -> board().contains(issue.coords()) && board().getHex(issue.coords())
              .getDecorations().stream().anyMatch(object -> object.id().equals(issue.object())));
        issueList = java.util.stream.Stream.concat(issues.values().stream().flatMap(List::stream), imported)
              .sorted(Comparator.comparingInt((HexIssue issue) -> issue.coords().getX()).thenComparingInt(issue -> issue.coords().getY()))
              .toList();
    }

    /**
     * {@code cells} and the hexes just beyond both ends of every single-lane run through them: the junctions a run's
     * direction decides, however far along it they lie; and across every single-lane fork or merge among them or at
     * those ends.
     */
    private java.util.Collection<Coords> withRunEnds(java.util.Collection<Coords> cells) {
        java.util.Set<Coords> result = new java.util.LinkedHashSet<>(cells);
        List<SingleLane.Run> runs = new ArrayList<>();
        for (Coords at : cells) {
            Hex hex = hexAt(at);
            if (hex == null || !SingleLane.single(hex)) { continue; }
            // A fork or merge the edit reached: its open sides follow which of its neighbours are single lanes.
            addRunEnd(result, at);
            if (runs.stream().anyMatch(run -> run.contains(at))) { continue; }
            SingleLane.Run run = SingleLane.run(this::hexAt, at, -1);
            if (run == null || run.loop()) { continue; }
            runs.add(run);
            var first = run.steps().getFirst();
            var last = run.steps().getLast();
            if (first.back() >= 0) { addRunEnd(result, first.at().translated(first.back())); }
            if (last.ahead() >= 0) { addRunEnd(result, last.at().translated(last.ahead())); }
        }
        return result;
    }

    /**
     * The hex beyond a run's end, and where it is a single-lane fork or merge, which stores no direction, its neighbours:
     * its arms towards them run as this run does, so the junctions across them change with it.
     */
    private void addRunEnd(java.util.Set<Coords> result, Coords end) {
        result.add(end);
        Hex hex = hexAt(end);
        if (hex == null || !SingleLane.single(hex)) { return; }
        int exits = roadExits(hex);
        if (Integer.bitCount(exits) <= 2) { return; }
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) != 0) { result.add(end.translated(d)); }
        }
    }

    /**
     * The single-lane layouts at {@code at} that no road authority would build or that strand traffic (LaneJunction#issues),
     * and a one-way lane ending, or starting, in a dead end. Each with its fix; the editor never refuses them. Beside
     * roadless concrete (an apron, a car park), whose traffic may come and go by any of the junction's lanes, only lanes
     * running on the left are reported.
     */
    private List<String> laneIssues(Hex hex, Coords at) {
        if (!hex.containsTerrain(Terrains.ROAD)) { return List.of(); }
        boolean narrow = SingleLane.single(hex);
        int exits = roadExits(hex);
        List<String> result = new ArrayList<>();
        if (narrow && Integer.bitCount(exits) == 1) {
            String kind = (SingleLane.inbound(hex, at, this::hexAt) & exits) != 0 ? "DEAD_END" : "FROM_NOWHERE";
            result.add(laneIssue(kind));
        }
        if (narrow ? Integer.bitCount(exits) < 3 : SingleLane.joined(hex, at, this::hexAt) == 0) { return result; }
        LaneJunction junction = SingleLane.junction(hex, at, this::hexAt);
        if (junction == null || Integer.bitCount(junction.arms()) < 2) { return result; }
        String[] sides = Messages.getString("BoardEditor.sides").split(",");
        boolean aprons = SingleLane.aprons(hex, at, this::hexAt, exits) != 0;
        for (var issue : junction.issues()) {
            var kind = issue.kind();
            if (aprons && kind != LaneJunction.Kind.REVERSED_SPLIT && kind != LaneJunction.Kind.REVERSED_PAIR) { continue; }
            // At a hub no two-lane road's directions choose an exit (Taken by).
            String fix = kind == LaneJunction.Kind.UNFED_EXIT && junction.hub() ? "UNFED_EXIT.hub" : kind.name();
            result.add(laneIssue(kind.name(), fix, issue.side() < 0 ? "" : sides[issue.side()],
                  issue.other() < 0 ? "" : sides[issue.other()]));
        }
        return result;
    }

    private static String laneIssue(String kind) { return laneIssue(kind, kind); }

    private static String laneIssue(String kind, String fix, Object... sides) {
        return Messages.getString("BoardEditor.issueLane", Messages.getString("BoardEditor.issueLane." + kind, sides),
              Messages.getString("BoardEditor.issueLane." + fix + ".fix"));
    }

    /**
     * The hex's identical copies: each object that has twins after it ({@link BoardDecoration#twin}), first, with them, in
     * the hex's order; a copy counts with the first object it repeats. Grouped by asset and sorted by position, so a hex
     * of thousands of copies stays fast.
     */
    private static List<List<BoardDecoration>> stacks(Hex hex) {
        // Per asset, the stacks so far by their first object's x.
        Map<String, java.util.TreeMap<Double, List<List<BoardDecoration>>>> firsts = new java.util.HashMap<>();
        List<List<BoardDecoration>> stacks = new ArrayList<>();
        for (BoardDecoration object : hex.getDecorations()) {
            var byX = firsts.computeIfAbsent(object.asset(), asset -> new java.util.TreeMap<>());
            var stack = byX.subMap(object.x() - BoardDecoration.TWIN_DISTANCE, true, object.x() + BoardDecoration.TWIN_DISTANCE, true)
                  .values().stream().flatMap(List::stream).filter(candidate -> candidate.getFirst().twin(object)).findFirst();
            if (stack.isPresent()) { stack.get().add(object); continue; }
            List<BoardDecoration> started = new ArrayList<>(List.of(object));
            byX.computeIfAbsent(object.x(), x -> new ArrayList<>()).add(started);
            stacks.add(started);
        }
        return stacks.stream().filter(stack -> stack.size() > 1).toList();
    }

    /** An object as the issues list names it: its own name, else its palette entry's label, else its art. */
    private String label(BoardDecoration object) {
        var entry = blueprint.asset(object.asset());
        return object.name() != null ? object.name() : entry != null ? entry.label() : object.asset();
    }

    /**
     * Removes every identical copy ({@link #stacks}), keeping the first object of each stack, with its group, in one undo
     * step; only the hexes the issues list reports copies in are visited.
     */
    private void removeCopies() {
        int removed = copies.values().stream().mapToInt(Integer::intValue).sum();
        for (Coords at : List.copyOf(copies.keySet())) {
            change(at, hex -> {
                Set<BoardDecoration> repeated = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                stacks(hex).forEach(stack -> repeated.addAll(stack.subList(1, stack.size())));
                hex.setDecorations(hex.getDecorations().stream().filter(object -> !repeated.contains(object)).toList());
            });
        }
        finishStroke();
        deselectRemoved();
        message = removed == 0 ? Messages.getString("BoardEditor.nothingChanged") : Messages.getString("BoardEditor.copiesRemoved", removed);
    }

    /**
     * Whether the renderer has the object's art: a decal's image, a stamp's catalog entry, or else the model the
     * renderer draws for the object's own asset, a file or a generator's, such as a route marker's track
     * (BoardGenerator#exists; a palette alias such as a pond has no art of its own).
     */
    private boolean assetExists(BoardDecoration object) {
        if (object.kind().equals("decal")) {
            return decalImages.computeIfAbsent(object.asset(), name -> megamek.common.board.BoardDecalArt.image(name).isFile());
        }
        var entry = blueprint.asset(object.asset());
        return entry != null && entry.layout() != null || modelFiles.computeIfAbsent(object.asset(),
              name -> megamek.common.board.BoardGenerator.exists(name, megamek.common.board.BoardGenerator.OBJECT));
    }

    /** Saved member heights cannot be rebaked without the placement's structural geometry. */
    private void checkBuildingGeometry(Coords owner, BoardDecoration original, BoardDecoration object) {
        if ((original == null || !BuildingVolumes.sameGeometry(original, object)) && BuildingVolumes.of(object.asset()).present()) {
            require(BuildingVolumes.onBoardFraction(owner, object, board().getWidth(), board().getHeight()) >= .1 - 1e-6,
                  Messages.getString("BoardEditor.buildingOutside"));
        }
        if (original != null && !BuildingVolumes.sameGeometry(original, object) && !BuildingVolumes.of(object.asset()).present()) {
            for (int x = 0; x < board().getWidth(); x++) {
                for (int y = 0; y < board().getHeight(); y++) {
                    var ref = board().getHex(x, y).getBuildingReference();
                    require(ref == null || !ref.id().equals(object.id()), Messages.getString("BoardEditor.buildingAssetMissing"));
                }
            }
        }
    }

    /** XY is normalized to the same odd-column lattice as the board renderer, with north-positive Y. */
    private Map.Entry<Selection, BoardDecoration> positioned(Coords owner, BoardDecoration object) {
        var original = board().getHex(owner).getDecorations().stream().filter(o -> o.id().equals(object.id())).findFirst().orElse(null);
        checkBuildingGeometry(owner, original, object);
        var cube = owner.toCube();
        double q = cube.q() + object.x() / .75, r = cube.r() - object.y() - object.x() / 1.5;
        Coords target = new megamek.common.board.CubeCoords(q, r, -q - r).roundToNearestHex().toOffset();
        // The in-map owner retains an authored building whose actual pivot has crossed the edge.
        if (!board().contains(target) && BuildingVolumes.of(object.asset()).present()) { target = owner; }
        require(board().contains(target), Messages.getString("BoardEditor.objectOutside"));
        double x = object.x() + (owner.getX() - target.getX()) * .75;
        double y = object.y() + (target.getY() - owner.getY()) + ((target.getX() & 1) - (owner.getX() & 1)) * .5;
        var placement = object.placement();
        if (placement.receiver() != null && !supports(board().getHex(target), placement.receiver().terrain())) {
            placement = BoardDecoration.Placement.surface("ground", "top", placement.offset());
        }
        return Map.entry(new Selection(target, object.id()), object.transform(x, y, object.rotation(), object.mirror(), object.scale(), placement));
    }

    /** All destinations are validated before any change; a drag retains IDs and is one undoable stroke. */
    private void moveObjects(Map<Selection, BoardDecoration> moved) {
        Set<String> ids = moved.values().stream().map(BoardDecoration::id).collect(java.util.stream.Collectors.toSet());
        Map<Coords, List<BoardDecoration>> contents = new LinkedHashMap<>();
        for (Coords at : selection.stream().filter(s -> ids.contains(s.object())).map(Selection::coords).distinct().toList()) {
            contents.put(at, new ArrayList<>(board().getHex(at).getDecorations().stream()
                  .filter(d -> !ids.contains(d.id()) || moved.containsKey(new Selection(at, d.id()))).toList()));
        }
        selection.removeIf(item -> ids.contains(item.object()));
        moved.forEach((item, object) -> {
            var objects = contents.computeIfAbsent(item.coords(), at -> new ArrayList<>(board().getHex(at).getDecorations()));
            int index = -1;
            for (int i = 0; i < objects.size(); i++) { if (objects.get(i).id().equals(object.id())) { index = i; break; } }
            // Transforming an existing object must not change its authored contents position.
            if (index < 0) { objects.add(object); } else { objects.set(index, object); }
            selection.add(item);
            if (object.id().equals(selectedObject)) { selected = item.coords(); }
        });
        contents.forEach((at, objects) -> change(at, hex -> hex.setDecorations(objects)));
    }

    private void editObject(String property, String value) {
        require(selected != null, Messages.getString("BoardEditor.selectObjectFirst"));
        BoardDecoration current = object(board().getHex(selected));
        BoardDecoration next = property.equals("asset") ? familyMember(current, value)
              : pickArt(objectValue(current, property, value), current.billboard());
        if (property.equals("offset") || property.equals("level")) {
            next = next.transform(next.x(), next.y(), next.rotation(), next.mirror(), next.scale(),
                  BoardEditorSnapping.clamp(board().getHex(selected), next.placement()));
        }
        // A cropped structure can retain its center outside the board, owned by a surviving member.
        boolean samePosition = current.x() == next.x() && current.y() == next.y();
        if (samePosition) { checkBuildingGeometry(selected, current, next); }
        var destination = samePosition
              ? Map.entry(new Selection(selected, next.id()), next) : positioned(selected, next);
        selection.add(new Selection(selected, selectedObject));
        moveObjects(Map.of(destination.getKey(), destination.getValue()));
        if (!continuousEdit) { finishStroke(); }
    }

    /**
     * {@code object} as the member {@code key} of its palette family (Edit's Type row): the same object with the member's
     * model. Its name is the member's label when the member is an alias (a pond), else its own unless that was the old
     * alias's label; its colours are the member's when it defines some, else its own unless they were the old member's.
     */
    private BoardDecoration familyMember(BoardDecoration object, String key) {
        var from = blueprint.assetOf(object);
        var to = blueprint.asset(key);
        var fromEntry = from == null ? null : blueprint.entry(from.id());
        var toEntry = to == null ? null : blueprint.entry(key);
        require(fromEntry != null && toEntry != null && blueprint.card(fromEntry) == blueprint.card(toEntry)
              && blueprint.card(toEntry).picker() && to.layout() == null, Messages.getString("BoardEditor.familyMemberOnly"));
        boolean fromAlias = !from.model().equals(from.id());
        String name = !to.model().equals(to.id()) ? to.label() : fromAlias && from.label().equals(object.name()) ? null : object.name();
        var colours = !to.colours().slots().isEmpty() ? to.colours()
              : object.colours().equals(from.colours()) ? BoardDecoration.Colours.NONE : object.colours();
        return new BoardDecoration(object.id(), object.kind(), to.model(), name, object.x(), object.y(), object.rotation(),
              object.mirror(), object.scale(), object.placement(), object.drawOrder(), object.clipToHex(), object.rotationX(),
              object.rotationY(), object.group(), object.bare(), object.connections(), object.stretch(), colours,
              object.tracks(), object.pillars(), to.model().equals(object.asset()) ? object.uid() : null, object.levels());
    }

    private void setVariant(Hex hex, String owner, String value) {
        Map<String, HexAppearance> styles = new LinkedHashMap<>(hex.getAppearance());
        var stored = styles.get(owner);
        var control = stored == null ? null : stored.trafficControl();
        if (value.isEmpty()) {
            HexAppearance retained = HexAppearance.withTrafficControl(null, control);
            if (owner.equals("building") && stored != null && stored.rotation() != null) {
                retained = HexAppearance.withRotation(retained, stored.rotation());
            }
            if (owner.equals("building") && stored != null) { retained = HexAppearance.withColours(retained, stored.colours()); }
            retained = roadLanes(hex, owner, retained, stored);
            if (retained == null) { styles.remove(owner); } else { styles.put(owner, retained); }
        }
        else {
            var variant = blueprint.variant(value);
            require(variant != null && variant.owner().equals(owner), "Unknown appearance choice");
            var ownerComponent = blueprint.component(variant.owner());
            require(ownerComponent.isPresent(name -> hex.containsTerrain(Terrains.getType(name))),
                  Messages.getString("BoardEditor.addBeforeDesign", ownerComponent.label()));
            HexAppearance current = styles.get(owner);
            if (!variant.material().isEmpty()) {
                // Material-bearing choices define the road rule and its finish together. Preserve an already
                // compatible choice (for example Alley) when an older caller chooses its matching finish.
                for (var field : ownerComponent.fields()) {
                    var compatible = field.choices().stream().filter(choice -> variant.material().equals(choice.material())).toList();
                    if (!compatible.isEmpty() && compatible.stream().noneMatch(choice -> choice.value() == hex.terrainLevel(Terrains.getType(field.terrain())))) {
                        setTerrain(hex, field.terrain(), compatible.getFirst().value());
                    }
                }
                styles.put(owner, roadLanes(hex, owner, HexAppearance.withTrafficControl(new HexAppearance(
                      current == null ? null : current.variant(), null, variant.material(), null), control), current));
            } else {
                // A building's pivot and a skin's heading survive a change of design.
                var was = current == null || current.variant() == null ? null : blueprint.variant(current.variant());
                boolean skins = variant.skin() != null && was != null && was.skin() != null;
                styles.put(owner, roadLanes(hex, owner, HexAppearance.withTrafficControl(new HexAppearance(variant.id(), null,
                      current == null ? null : current.material(), variant.blend() ? .5 : null,
                      owner.equals("building") && current != null ? current.colours() : BoardDecoration.Colours.NONE,
                      (skins || owner.equals("building")) && current != null ? current.rotation() : null), control), current));
            }
        }
        if (owner.equals("building") && stored != null && stored.padding() != null) {
            styles.put(owner, HexAppearance.withPadding(styles.get(owner), stored.padding()));
        }
        hex.setAppearance(styles);
    }

    /**
     * Turns an isolated building in whole degrees, or a skin in 30-degree steps, clockwise: the Edit panel's and the
     * Brush's Rotation row. Connections keep buildings aligned to the board.
     */
    private void rotate(Hex hex, String owner, String degrees, boolean connected) {
        HexAppearance current = hex.getAppearance().get(owner);
        var variant = current == null || current.variant() == null ? null : blueprint.variant(current.variant());
        boolean building = owner.equals("building") && hex.containsTerrain(Terrains.BUILDING);
        require(building || variant != null && variant.skin() != null, Messages.getString("BoardEditor.skinFirst"));
        require(!building || !connected, Messages.getString("BoardEditor.buildingRotationConnected"));
        int step = building ? 1 : 30;
        int rotation = Math.floorMod((int) Math.round(Double.parseDouble(degrees) / step) * step, 360);
        appearance(hex, owner, HexAppearance.withRotation(current, rotation));
    }

    /**
     * Gives a pavement hex its {@link HexAppearance.Edges} ({@code value} "auto", "fitted" or "hex"): the Edit panel's
     * and the Brush's Edges row.
     */
    /** Stores {@code footprint} in {@code hex}'s appearance ({@link HexAppearance#of(HexAppearance.Footprint)}). */
    private static void footprint(Hex hex, HexAppearance.Footprint footprint) {
        Map<String, HexAppearance> styles = new LinkedHashMap<>(hex.getAppearance());
        if (footprint == HexAppearance.Footprint.ANGLED) { styles.remove("footprint"); }
        else { styles.put("footprint", HexAppearance.of(footprint)); }
        hex.setAppearance(styles);
    }

    /** The variants of the generated kit {@code hex}'s building draws ({@link TilesetScenery#kitVariants}); none: empty. */
    private List<String> kitVariants(Hex hex) {
        return hex.containsTerrain(Terrains.BUILDING)
              ? TilesetScenery.kitVariants(TilesetScenery.structureModels(hex,
                    TilesetScenery.buildingConnected(hex, hex.getCoords(), board()::getHex)).get(Terrains.BUILDING)) : List.of();
    }

    /** The variant of its generated kit that {@code hex}'s building draws ({@link TilesetScenery#drawnVariant}); none: null. */
    private String kitDrawn(Hex hex) {
        return hex.containsTerrain(Terrains.BUILDING)
              ? TilesetScenery.drawnVariant(HexAppearance.kitVariant(hex.getAppearance()), TilesetScenery.structureModels(hex,
                    TilesetScenery.buildingConnected(hex, hex.getCoords(), board()::getHex)).get(Terrains.BUILDING),
                    hex.terrainLevel(Terrains.BLDG_CLASS)) : null;
    }

    /** Those of {@link #kitVariants} that offer a roof that spans their hall beside the flat one ({@link TilesetScenery#kitVaults}). */
    private List<String> kitVaults(Hex hex) {
        return hex.containsTerrain(Terrains.BUILDING)
              ? TilesetScenery.kitVaults(TilesetScenery.structureModels(hex,
                    TilesetScenery.buildingConnected(hex, hex.getCoords(), board()::getHex)).get(Terrains.BUILDING)) : List.of();
    }

    /** Stores the kit variant {@code name} in {@code hex}'s appearance ({@link HexAppearance#kitVariant}); null: none. */
    private static void kitVariant(Hex hex, String name) {
        Map<String, HexAppearance> styles = new LinkedHashMap<>(hex.getAppearance());
        if (name == null) { styles.remove("kitVariant"); } else { styles.put("kitVariant", HexAppearance.ofKitVariant(name)); }
        hex.setAppearance(styles);
    }

    private void setEdges(Hex hex, String value) {
        require(hex.containsTerrain(Terrains.PAVEMENT), Messages.getString("BoardEditor.pavementFirst"));
        var edges = HexAppearance.Edges.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        Map<String, HexAppearance> styles = new LinkedHashMap<>(hex.getAppearance());
        HexAppearance look = HexAppearance.withSeparated(HexAppearance.of(edges), HexAppearance.separated(styles, "pavement"));
        if (look == null) { styles.remove("pavement"); } else { styles.put("pavement", look); }
        hex.setAppearance(styles);
    }

    private static void setFortification(Hex hex, String value) {
        require(hex.containsTerrain(Terrains.FORTIFIED), Messages.getString("BoardEditor.addTerrainFirst"));
        var fortification = HexAppearance.Fortification.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        require(fortification == HexAppearance.Fortification.SANDBAGS || !HexAppearance.sandbagsOnly(hex::containsTerrain),
              Messages.getString("BoardEditor.fortification.sandbagsOnly"));
        appearance(hex, "fortification", HexAppearance.of(fortification));
    }

    private static void setTrafficControl(Hex hex, String owner, String value) {
        require(owner.equals("road") || owner.equals("bridgeRoad"), "Choose a road layer.");
        require(hex.containsTerrain(owner.equals("road") ? Terrains.ROAD : Terrains.BRIDGE_ROAD),
              Messages.getString("BoardEditor.addTerrainFirst"));
        var control = HexAppearance.TrafficControl.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        appearance(hex, owner, HexAppearance.withTrafficControl(hex.getAppearance().get(owner), control));
    }

    /** Narrows a paved ground road to a single lane (value "single") or widens it to the two-lane default ("two"). */
    private static void setLanes(Hex hex, String owner, String value) {
        require(owner.equals(SingleLane.OWNER), "Only a ground road has a single-lane choice.");
        require(value.equals("single") || value.equals("two"), "Choose a single lane or two lanes.");
        require(hex.containsTerrain(Terrains.ROAD), Messages.getString("BoardEditor.addTerrainFirst"));
        boolean single = value.equals("single");
        require(!single || SingleLane.paved(hex), Messages.getString("BoardEditor.lanesPaved"));
        require(!single || RailRoute.groundSides(hex) == 0, Messages.getString("BoardEditor.lanesRail"));
        var current = hex.getAppearance().get(owner);
        if (single == HexAppearance.singleLane(hex.getAppearance(), owner)) { return; }
        // A new single lane takes its direction from its neighbours when its stroke completes (#singleLaneRuns).
        appearance(hex, owner, HexAppearance.withLanes(current, single, null));
    }

    /**
     * The look a road edit leaves on {@code hex}'s {@code owner}: {@code look}, keeping the lanes, direction and exit
     * approaches of {@code from} on a paved ground road; any other road, and every other owner, has none.
     */
    private static HexAppearance roadLanes(Hex hex, String owner, HexAppearance look, HexAppearance from) {
        if (owner.equals(SingleLane.OWNER) && SingleLane.paved(hex)) { return HexAppearance.keepLanes(look, from); }
        return look == null || look.lanes() == null && look.exitFrom() == null ? look : HexAppearance.withLanes(look, false, null, null);
    }

    /** One run, one edit: the single-lane road across {@code target}'s side becomes the selected hex's entrance or exit. */
    private void ramp(String target, String value) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        require(target.startsWith(SingleLane.OWNER + ":"), "Choose a ground road's side.");
        int side = Integer.parseInt(target.substring(SingleLane.OWNER.length() + 1));
        require(side >= 0 && side < 6, "A hex has six sides.");
        require(value.equals("entrance") || value.equals("exit"), "Choose an entrance or an exit.");
        require((SingleLane.joined(board().getHex(selected), selected, this::hexAt) & 1 << side) != 0,
              Messages.getString("BoardEditor.rampNone"));
        var run = SingleLane.run(this::hexAt, selected.translated(side), (side + 3) % 6);
        require(run != null, Messages.getString("BoardEditor.rampFork"));
        // The run starts across the shared border: traffic running along it leaves the selected hex, an exit.
        direct(run, value.equals("exit"));
        if (!continuousEdit) { finishStroke(); }
    }

    /**
     * One edit: which of the selected two-lane road's approaches may take the exit leaving it across {@code target}'s side
     * ("all", or one approach's side), stored on that road, which drops what it stored for sides no ramp joins any longer.
     */
    private void rampFrom(String target, String value) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        require(target.startsWith(SingleLane.OWNER + ":"), "Choose a ground road's side.");
        int side = Integer.parseInt(target.substring(SingleLane.OWNER.length() + 1));
        require(side >= 0 && side < 6, "A hex has six sides.");
        Hex hex = board().getHex(selected);
        require(SingleLane.feeds(hex, selected, side, this::hexAt) != 0, Messages.getString("BoardEditor.rampFromNone"));
        int approaches = hex.getTerrain(Terrains.ROAD).getExits() & 63 & ~SingleLane.joined(hex, selected, this::hexAt);
        int chosen = 0;
        if (!value.equals("all")) {
            int from = Integer.parseInt(value);
            require(from >= 0 && from < 6 && (approaches & 1 << from) != 0, Messages.getString("BoardEditor.rampFromApproach"));
            chosen = 1 << from;
        }
        // A ramp still joining keeps its choice, even while it is an entrance; a side no ramp joins any longer drops it.
        Long stored = HexAppearance.exitFrom(hex.getAppearance(), SingleLane.OWNER);
        int ramps = SingleLane.joined(hex, selected, this::hexAt);
        long kept = 0;
        for (int exit = 0; exit < 6; exit++) {
            if (exit != side && stored != null && (ramps & 1 << exit) != 0) { kept |= stored & 63L << 6 * exit; }
        }
        long result = kept | (long) chosen << 6 * side;
        change(selected, junction -> appearance(junction, SingleLane.OWNER,
              HexAppearance.withExitFrom(junction.getAppearance().get(SingleLane.OWNER), result)));
        if (!continuousEdit) { finishStroke(); }
    }

    /** One run, one edit: the selected hex's single-lane run reverses its direction of travel. */
    private void reverseLane(String owner) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        require(owner.equals(SingleLane.OWNER), "Only a ground road has a single-lane choice.");
        var run = SingleLane.run(this::hexAt, selected, -1);
        require(run != null, Messages.getString("BoardEditor.laneRunNone"));
        direct(run, !SingleLane.forward(this::hexAt, run, at -> true));
        if (!continuousEdit) { finishStroke(); }
    }

    /** Stores one direction of travel along every hex of {@code run}: from its start to its end, or the other way. */
    private void direct(SingleLane.Run run, boolean forward) {
        for (var step : run.steps()) {
            change(step.at(), hex -> {
                if (SingleLane.single(hex)) {
                    appearance(hex, SingleLane.OWNER, SingleLane.directed(hex.getAppearance().get(SingleLane.OWNER),
                          step.inbound(forward)));
                }
            });
        }
    }

    /**
     * Every single-lane run a stroke reached runs one way along its whole length ({@link SingleLane#forward}): as the
     * hexes it already had run, else as the stroke drew it. A single-lane fork or merge stores no direction of its own:
     * each of its arms runs as the run beyond it does ({@link SingleLane#inbound}).
     */
    private void singleLaneRuns() {
        // A hex's vote is weighty where it was a single lane before the stroke and keeps the way it ran: an extension
        // joined to a run does not outvote it, even where the run's end gains an exit.
        Set<Coords> fresh = new HashSet<>();
        boolean touched = false;
        for (var entry : stroke.entrySet()) {
            Hex before = entry.getValue(), after = board().getHex(entry.getKey());
            touched |= !java.util.Objects.equals(before.getAppearance().get(SingleLane.OWNER),
                  after.getAppearance().get(SingleLane.OWNER)) || before.containsTerrain(Terrains.ROAD) != after.containsTerrain(Terrains.ROAD)
                  || after.containsTerrain(Terrains.ROAD)
                        && before.getTerrain(Terrains.ROAD).getExits() != after.getTerrain(Terrains.ROAD).getExits();
            if (!SingleLane.single(before) || !java.util.Objects.equals(HexAppearance.inbound(before.getAppearance(), SingleLane.OWNER),
                  HexAppearance.inbound(after.getAppearance(), SingleLane.OWNER))) {
                fresh.add(entry.getKey());
            }
        }
        if (!touched) { return; }
        List<SingleLane.Run> runs = new ArrayList<>();
        for (Coords at : List.copyOf(stroke.keySet())) {
            Hex hex = board().getHex(at);
            if (SingleLane.single(hex) && Integer.bitCount(hex.getTerrain(Terrains.ROAD).getExits() & 63) > 2
                  && HexAppearance.inbound(hex.getAppearance(), SingleLane.OWNER) != null) {
                change(at, h -> appearance(h, SingleLane.OWNER, HexAppearance.withLanes(h.getAppearance().get(SingleLane.OWNER), true, null)));
            }
            for (var run : SingleLane.runsAround(this::hexAt, at)) {
                if (runs.stream().noneMatch(existing -> existing.contains(run.steps().getFirst().at()))) { runs.add(run); }
            }
        }
        for (var run : runs) { direct(run, SingleLane.forward(this::hexAt, run, at -> !fresh.contains(at))); }
    }

    /** Arms a card: {@code owner}'s preset or design {@code key}, or for owner "theme" the Theme card of theme {@code key}. */
    private void chooseBrush(String owner, String key) {
        if (owner.equals("theme")) {
            require(!key.isBlank(), "Unknown brush choice");
            paintTemplate = new Hex(0, "", key); sampledHex = null; brushComponent = "ground"; brushElevation = false; brushTheme = true;
            brushKey = THEME_BRUSH + key; brushLabel = BoardEditorBlueprint.themeLabel(key);
            tool = Tool.PAINT; message = "";
            return;
        }
        var definition = blueprint.component(owner);
        var preset = definition.presets().stream().filter(p -> p.id().equals(key)).findFirst().orElse(null);
        var variant = blueprint.variant(key);
        require(preset != null || variant != null && variant.owner().equals(owner) || key.isEmpty(), "Unknown brush choice");
        // A skin card paints the cover its look shows (Scattered containers: foliage).
        Hex next = new Hex(0, preset != null ? preset.terrain() : variant != null && variant.skin() != null
              ? variant.skin().terrain() : definition.defaults(), paintTemplate.getTheme());
        if (variant != null) { setVariant(next, owner, key); }
        // The template's own theme is not the board's: a colony's roads take their finish where they are painted.
        applyChoiceMaterials(next, definition, false);
        paintTemplate = next; sampledHex = null; brushComponent = owner; brushElevation = false; brushTheme = false;
        brushKey = variant == null ? owner + "/" + key : key;
        brushLabel = preset != null ? preset.label() : variant != null ? variant.label() : definition.label();
        levelHeight = owner.equals("bridge"); wayOffset = 0;
        wayPillars = HexAppearance.pillars(next.getAppearance());
        tool = Tool.PAINT; message = "";
    }

    private void configureBrush(String target, String value) {
        Hex next = paintTemplate.duplicate();
        // An empty value removes an optional field the card offers (Water's Current: None).
        if (target.startsWith("terrain:") && value.isEmpty()) { next.removeTerrain(Terrains.getType(target.substring(8))); }
        else if (target.startsWith("terrain:")) { setTerrain(next, target.substring(8), Integer.parseInt(value), false); }
        else if (target.startsWith("variant:")) { setVariant(next, target.substring(8), value); }
        else if (target.startsWith("rotation:")) {
            rotate(next, target.substring(9), value, TilesetScenery.buildingConnected(next, null, null));
        }
        else if (target.equals("edges")) { setEdges(next, value); }
        else if (target.equals("trafficControl")) { setTrafficControl(next, "road", value); }
        else if (target.equals("lanes")) { setLanes(next, "road", value); }
        else if (target.startsWith("object:")) {
            require(!next.getDecorations().isEmpty(), Messages.getString("BoardEditor.chooseObjectBrush"));
            next.setDecorations(List.of(objectValue(next.getDecorations().getFirst(), target.substring(7), value)));
        } else if (target.equals("theme")) { next.setTheme(value); brushTheme = true; }
        else if (target.equals("elevation")) { next.setLevel(Integer.parseInt(value)); brushElevation = true; }
        else if (target.equals("applyElevation")) { brushElevation = Boolean.parseBoolean(value); }
        else if (target.equals("applyTheme")) { brushTheme = Boolean.parseBoolean(value); }
        else if (target.equals("precision")) { precision = Boolean.parseBoolean(value); }
        else if (target.equals("magnetic")) { magnetic = Boolean.parseBoolean(value); }
        else if (target.equals("sculpt")) { sculpt = LevelSculpt.Mode.valueOf(value); }
        else if (target.equals("slope")) { slope = Boolean.parseBoolean(value); }
        else if (target.equals("clearOnly")) { clearOnly = Boolean.parseBoolean(value); }
        else if (target.equals("fill")) { fill = Boolean.parseBoolean(value); }
        else if (target.equals("wayHeight")) {
            require(value.equals("follow") || value.equals("level"), "Choose Follow or Level.");
            levelHeight = value.equals("level");
        } else if (target.equals("wayOffset")) {
            int offset = Integer.parseInt(value);
            require(offset >= 0 && offset <= 50, "Height offset must be between 0 and 50.");
            wayOffset = offset;
        } else if (target.equals("wayPillars")) { wayPillars = Boolean.parseBoolean(value); }
        else if (target.equals("zone")) { setZones(next, zoneMask(value)); }
        else { throw new IllegalArgumentException("Unknown brush setting " + target); }
        var object = next.getDecorations().stream().findFirst().orElse(null);
        if (object != null && BuildingVolumes.of(object.asset()).present()) {
            BuildingInteriors.set(next, BuildingInteriors.forAuthored(object, BuildingInteriors.choice(next)));
        }
        paintTemplate = next;
    }

    private void sampleBrush() {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        Hex hex = board().getHex(selected);
        if (!selectedObject.isEmpty()) {
            BoardDecoration object = object(hex);
            paintTemplate = new Hex(0); paintTemplate.setDecorations(List.of(object));
            if (BuildingVolumes.of(object.asset()).present()) {
                Hex building = buildingSample();
                BuildingInteriors.set(paintTemplate, BuildingInteriors.forAuthored(object,
                      building == null ? BuildingInteriors.DEFAULT : BuildingInteriors.choice(building)));
            }
            sampledHex = null;
            brushComponent = ""; brushKey = object.asset();
            var asset = blueprint.asset(object.asset()); brushLabel = asset == null ? object.asset() : asset.label();
        } else {
            paintTemplate = hex.duplicate(); sampledHex = selected;
            brushElevation = true; brushTheme = true;
            brushComponent = component; brushKey = "hex/sample"; brushLabel = Messages.getString("BoardEditor.hexStamp", selected.getBoardNum());
        }
        tool = Tool.PAINT;
    }

    /** Ctrl+wheel directly adjusts the hovered hex in every tool, independently of brush and Sculpt settings. */
    public void adjustElevation(Coords at, int levels) {
        if (levels == 0 || at == null || !board().contains(at)) { return; }
        change(at, hex -> hex.setLevel(hex.getLevel() + levels));
        revision++;
    }

    private void setLevels(Map<Coords, Integer> levels) {
        levels.forEach((at, level) -> change(at, hex -> hex.setLevel(level)));
    }

    private void edit(Consumer<Hex> action) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        change(selected, action);
        if (!continuousEdit) { finishStroke(); }
    }

    private void change(Coords at, Consumer<Hex> action) {
        Hex next = edited(at, action);
        if (next != null) { board().setHex(at, next); }
    }

    /** A copy of the hex at {@code at} changed by {@code action}, its before-state recorded; null when nothing changed. */
    private Hex edited(Coords at, Consumer<Hex> action) {
        Hex original = board().getHex(at), next = original.duplicate();
        action.accept(next);
        if (BoardFile.hexNode(original).equals(BoardFile.hexNode(next))) { return null; }
        beginHexEdit(at);
        return next;
    }

    /**
     * Runs {@code action} on every hex of the board as ONE undo step: the changed hexes and their neighbours are
     * recorded, installed together ({@link Board#setHexes}, which derives cliffs and exits only once all are in place,
     * so a level change keeps manual cliff edges) and re-validated as a committed stroke. The whole-board commands
     * (theme, level change, flatten, flood, remove, clear) use it. Returns the number of changed hexes.
     */
    private int everyHex(Consumer<Hex> action) {
        Map<megamek.common.board.BoardLocation, Hex> changed = new LinkedHashMap<>();
        for (int y = 0; y < board().getHeight(); y++) {
            for (int x = 0; x < board().getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex next = edited(at, action);
                if (next != null) { changed.put(megamek.common.board.BoardLocation.of(at, board().getBoardId()), next); }
            }
        }
        if (!changed.isEmpty()) { board().setHexes(changed); }
        finishStroke();
        deselectRemoved();
        return changed.size();
    }

    /** A selected object the last command removed is no longer selected. */
    private void deselectRemoved() {
        selection.removeIf(item -> !item.object().isEmpty() && decoration(item).isEmpty());
        if (selected != null && board().getHex(selected).getDecorations().stream().noneMatch(o -> o.id().equals(selectedObject))) {
            selectedObject = "";
        }
    }

    /**
     * Floods the board up to {@code surface}, by the old editor's rules. A hex below the surface (submerged) gets water
     * up to it on top of the water it had (depth = surface - level + depth) and its level becomes the surface; a hex at
     * or above it stays. A bridge's deck stays where it is over the same ground, so the water can rise past it: its height
     * becomes deck - surface. With {@code remove} a submerged hex also loses every other component with its appearance, and
     * its objects, except a bridge (it keeps its appearance and the objects on it) and its deployment zones, which are
     * scenario data. Without it, structures and woods stay on the same ground as the water rises around them, as their
     * heights count from the floor in 3D. One undo step; returns the number of submerged hexes.
     */
    private int flood(int surface, boolean remove) {
        return everyHex(hex -> {
            if (hex.getLevel() >= surface) { return; }
            int depth = hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : 0;
            // A destroyed bridge keeps its ruin over the same ground as a standing one keeps its deck, and its rubble.
            Terrain height = standingOrRuin(hex, Terrains.BRIDGE_ELEV);
            boolean bridge = standingOrRuin(hex, Terrains.BRIDGE) != null && height != null;
            boolean ruined = bridge && !hex.containsTerrain(Terrains.BRIDGE);
            if (remove) {
                Terrain zones = hex.getTerrain(Terrains.DEPLOYMENT_ZONE);
                for (var component : blueprint.components()) {
                    if (!component.id().equals("water") && !(bridge && (component.id().equals("bridge") || component.id().equals("ways")
                          || ruined && component.id().equals("rubble")))) {
                        removeComponent(hex, component);
                    }
                }
                if (bridge) {
                    removeWay(hex, Terrains.RAIL);
                }
                if (zones != null) { hex.addTerrain(zones); }
                hex.setDecorations(hex.getDecorations().stream().filter(object -> bridge && object.placement().receiver() != null
                      && object.placement().receiver().terrain().equals("bridge")).toList());
            }
            if (bridge) {
                var rebased = new Terrain(Terrains.BRIDGE_ELEV, hex.getLevel() + height.getLevel() - surface,
                      height.hasExitsSpecified(), height.getExits());
                if (ruined) { hex.addRuin(rebased); } else { hex.addTerrain(rebased); }
            }
            hex.addTerrain(new Terrain(Terrains.WATER, surface - hex.getLevel() + depth));
            hex.setLevel(surface);
        });
    }

    /**
     * Paint or Erase of {@code definition}'s fields on {@code hex}, each as {@code template} holds it. Paint also gives a
     * hex that lacks one the template's terrain outside those fields: a preset's own, as Hazardous liquid's water. Water
     * and lava (liquid magma on the water's depth, {@link HexTile#liquid}) replace each other, and the hex shows the
     * painted liquid: Lava makes the magma liquid and takes a hazardous liquid it would hide, Water takes any magma
     * (crust hides water too), and the Water component's Erase takes liquid magma with the depth; crust stays.
     */
    private void paintFields(Hex hex, Hex template, BoardEditorBlueprint.Component definition) {
        // Paint or Erase of a destroyed structure replaces its ruin, with the rubble that is its own (Action#DESTROYED).
        if (definition.present().stream().anyMatch(type -> hex.getRuin(Terrains.getType(type)) != null)) { hex.removeRuins(); }
        if (tool == Tool.ERASE && definition.id().equals("building")) {
            removeComponent(hex, definition);
            return;
        }
        Set<Integer> fields = new java.util.HashSet<>();
        for (var field : definition.fields()) {
            int type = Terrains.getType(field.terrain());
            fields.add(type);
            Terrain value = template.getTerrain(type);
            if (!definition.replaceExisting() && value == null) { continue; }
            // The Deployment zone card adds its zone, and Erase removes it; the hex keeps its other zones.
            if (type == Terrains.DEPLOYMENT_ZONE && value != null) { zones(hex, value.getExits(), tool != Tool.ERASE); }
            else if (tool == Tool.ERASE || value == null) { hex.removeTerrain(type); }
            else { hex.addTerrain(new Terrain(value)); }
        }
        if (definition.id().equals("water")) {
            if (tool != Tool.ERASE && template.terrainLevel(Terrains.MAGMA) == LAVA) {
                hex.addTerrain(new Terrain(Terrains.MAGMA, LAVA));
                hex.removeTerrain(Terrains.HAZARDOUS_LIQUID);
            } else if (tool != Tool.ERASE || hex.terrainLevel(Terrains.MAGMA) == LAVA) { hex.removeTerrain(Terrains.MAGMA); }
        }
        if (tool == Tool.ERASE) { return; }
        for (int type : template.getTerrainTypes()) {
            if (!fields.contains(type) && !hex.containsTerrain(type)) { hex.addTerrain(new Terrain(template.getTerrain(type))); }
        }
    }

    /** The armed preset's other hexes by direction, when it is a multi-hex blueprint (the DropShip); none otherwise. */
    private Map<Integer, String> brushHexes() {
        var entry = sampledHex == null ? blueprint.entry(brushKey) : null;
        return entry == null || entry.preset() == null ? Map.of() : entry.preset().hexes();
    }

    /**
     * Adds ({@code add}) or removes the deployment zones of {@code mask} in {@code hex} and keeps its other zones; the
     * terrain goes when no zone is left. A deployment zone's IDs are its exits mask, read as the game reads them
     * ({@link Board#exitsAsIntList}, which {@code Board.initializeDeploymentZones} uses): zone n is bit n - 1.
     */
    private static void zones(Hex hex, int mask, boolean add) {
        Terrain old = hex.getTerrain(Terrains.DEPLOYMENT_ZONE);
        int current = old == null ? 0 : old.getExits();
        setZones(hex, add ? current | mask : current & ~mask);
    }

    /** Gives {@code hex} exactly the deployment zones of {@code mask}, with its exits specified; none removes the terrain. */
    private static void setZones(Hex hex, int mask) {
        Terrain old = hex.getTerrain(Terrains.DEPLOYMENT_ZONE);
        if (mask == 0) { hex.removeTerrain(Terrains.DEPLOYMENT_ZONE); }
        else { hex.addTerrain(new Terrain(Terrains.DEPLOYMENT_ZONE, old == null ? 0 : old.getLevel(), true, mask)); }
    }

    /**
     * The deployment zone mask of IDs such as "1, 3" ({@link Board#IntListAsExits}); each ID is 1 to
     * {@link Board#MAX_DEPLOYMENT_ZONE_NUMBER}, and an empty text is no zone.
     */
    static int zoneMask(String ids) {
        List<Integer> zones = new ArrayList<>();
        for (String id : ids.split("[,;\\s]+")) {
            if (id.isEmpty()) { continue; }
            int zone;
            try { zone = Integer.parseInt(id); } catch (NumberFormatException notANumber) { zone = 0; }
            require(zone >= 1 && zone <= Board.MAX_DEPLOYMENT_ZONE_NUMBER,
                  Messages.getString("BoardEditor.deploymentZoneRange", Board.MAX_DEPLOYMENT_ZONE_NUMBER));
            zones.add(zone);
        }
        return Board.IntListAsExits(zones);
    }

    /** The deployment zone IDs of {@code mask} as the editor shows them, for example "1, 3" ({@link #zoneMask} reads it). */
    public static String zoneIds(int mask) {
        return String.join(", ", Board.exitsAsIntList(mask).stream().map(String::valueOf).toList());
    }

    /** Removes a component and its appearance; detaching an authored building removes its placement on commit. */
    private static void removeComponent(Hex hex, BoardEditorBlueprint.Component definition) {
        // A destroyed structure goes with its ruin and the rubble that is its own (Action#DESTROYED).
        if (definition.present().stream().anyMatch(type -> hex.getRuin(Terrains.getType(type)) != null)) { hex.removeRuins(); }
        if (definition.id().equals("bridge")) { removeDeck(hex); return; }
        definition.fields().forEach(field -> removeWay(hex, Terrains.getType(field.terrain())));
        var appearance = new LinkedHashMap<>(hex.getAppearance());
        appearance.remove(definition.id());
        if (definition.id().equals("building")) {
            appearance.keySet().removeAll(Set.of("footprint", "kitVariant", "buildingProfile", "doors", "roof"));
            hex.setBuildingReference(null);
        }
        hex.setAppearance(appearance);
    }

    /**
     * Changes the board itself, as the old editor's board settings did: the Arena flag ("arena", value true or false: the
     * tag {@link BoardClassifier#ARENA_TAG}), a manual tag ("tag" adds the value, "untag" removes it) or the automatic tags
     * ("autotags": {@link BoardsTagger#updateAutoTags}, the board tagger's own rule). These are not undo steps; the
     * control is its own undo, and {@link #dirty} compares the tags with the saved ones.
     */
    private void boardSetting(String target, String value) {
        switch (target) {
            case "arena" -> {
                if (Boolean.parseBoolean(value)) { board().addTag(BoardClassifier.ARENA_TAG); }
                else { board().removeTag(BoardClassifier.ARENA_TAG); }
            }
            case "tag", "untag" -> {
                String tag = value.strip();
                require(!tag.isEmpty(), Messages.getString("BoardEditor.tagEmpty"));
                // Automatic tags change only through "autotags", so a later tagger run never drops a manual tag.
                require(!BoardsTagger.isAutoTag(tag), Messages.getString("BoardEditor.tagAutomatic"));
                if (target.equals("tag")) { board().addTag(tag); } else { board().removeTag(tag); }
            }
            case "autotags" -> BoardsTagger.updateAutoTags(board());
            default -> throw new IllegalArgumentException("Unknown board setting " + target);
        }
    }

    /**
     * Records the before-state of {@code at} and its neighbours in the stroke's undo step; during a preview's dry run also
     * the live hexes, since every write of a stroke (the conversion's too) is preceded by this, to put them back.
     */
    private void beginHexEdit(Coords at) {
        stroke.putIfAbsent(at, board().getHex(at).duplicate());
        if (dryRun != null) { dryRun.putIfAbsent(at, board().getHex(at)); }
        for (int direction = 0; direction < 6; direction++) {
            Coords neighbor = at.translated(direction);
            if (board().contains(neighbor)) {
                stroke.putIfAbsent(neighbor, board().getHex(neighbor).duplicate());
                if (dryRun != null) { dryRun.putIfAbsent(neighbor, board().getHex(neighbor)); }
            }
        }
    }

    /**
     * Ends the stroke as one undo step; a route drawn by hand is built now, on its release ({@link #draw}), as its preview
     * shows it.
     */
    public void finishStroke() {
        buildDrawing();
        if (!movingObjects.isEmpty()) {
            Map<Selection, BoardDecoration> destinations = new LinkedHashMap<>();
            for (Selection item : movingObjects.keySet()) {
                board().getHex(item.coords()).getDecorations().stream().filter(object -> object.id().equals(item.object())).findFirst().ifPresent(object -> {
                    var destination = positioned(item.coords(), object);
                    destinations.put(destination.getKey(), destination.getValue());
                });
            }
            moveObjects(destinations);
        }
        moveHexes(); dragOrigin = moveTarget = null; movingObjects.clear();
        if (!stroke.isEmpty()) { completeStroke(); }
        if (!stroke.isEmpty()) {
            Map<Coords, Hex> after = new LinkedHashMap<>();
            stroke.keySet().forEach(at -> after.put(at, board().getHex(at).duplicate()));
            long id = ++nextId;
            // Hash maps, not Map.copyOf: Coords hashes collide in runs, and the probing of Map.copyOf made a whole-board
            // step on Valencia (24,576 hexes) take 2.2 s instead of 0.26 s (one Flood, measured in the unit test JVM).
            undo.push(new Edit(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stroke)),
                  java.util.Collections.unmodifiableMap(after), currentId, id));
            // The stroke holds every changed hex and its neighbours: the hexes whose checks can change, but for the
            // junctions at the far ends of the single-lane runs it reaches, whose direction it may turn.
            validate(withRunEnds(stroke.keySet()));
            currentId = id; redo.clear(); stroke.clear();
            // Bound retained stroke history, not the number of cells a legitimate stroke can edit.
            while (undo.size() > 200) { undo.removeLast(); }
            revision++;
        }
        painted.clear(); wayLayers.clear(); wayReceivers.clear(); placed = false; routePaintFrom = null; sculptLevel = null; groupBase = Map.of();
        strokeNotice = "";
    }

    /** What a stroke's commit ({@link #finishStroke}) runs before it records the stroke; a preview's dry run runs it too. */
    private void completeStroke() {
        // Reconcile after all stamped hexes are installed, so adjacent copies can keep their joined route sides.
        if (sampledHex != null && (tool == Tool.PAINT || tool == Tool.ERASE)) { keepJoinedSides(painted); }
        if (wallBrush() && tool == Tool.ERASE) { disconnectWalls(); }
        // Resolve any legacy tokens pasted by an older brush before reconciling the authored layers.
        convertTokens();
        wayDecks();
        openSeparatedSides();
        authoredBuildings();
        // Also constrain direct member edits and pasted terrain, not only object-transform commands.
        for (Coords at : List.copyOf(stroke.keySet())) {
            Hex hex = board().getHex(at);
            var reference = hex.getBuildingReference();
            if (reference == null || !board().contains(reference.anchor()) || !hex.containsTerrain(Terrains.BUILDING)) { continue; }
            var object = board().getHex(reference.anchor()).getDecorations().stream()
                  .filter(o -> o.id().equals(reference.id())).findFirst().orElse(null);
            if (object == null) { continue; }
            var choice = BuildingInteriors.forAuthored(object, BuildingInteriors.choice(hex));
            if (choice != BuildingInteriors.choice(hex)) { change(at, current -> BuildingInteriors.set(current, choice)); }
        }
        joinBuildingLooks();
        connectedBuildingLooks();
        singleLaneRuns();
    }

    /** Placement or ground edits rebake coverage; appearance edits and collapse keep the saved gameplay state. */
    private void authoredBuildings() {
        // Erasing any authored member removes the owning placement too. A collapsed member retains its reference,
        // so gameplay damage and cosmetic edits never become deletion requests.
        for (Coords at : List.copyOf(stroke.keySet())) {
            var reference = stroke.get(at).getBuildingReference();
            if (reference != null && !reference.equals(board().getHex(at).getBuildingReference())
                  && board().contains(reference.anchor())) {
                change(reference.anchor(), hex -> hex.setDecorations(hex.getDecorations().stream()
                      .filter(object -> !object.id().equals(reference.id())).toList()));
            }
        }
        Map<String, Map.Entry<Coords, BoardDecoration>> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        Set<String> groundChanges = new LinkedHashSet<>();
        for (Coords at : List.copyOf(stroke.keySet())) {
            for (var object : stroke.get(at).getDecorations()) {
                before.put(object.id(), Map.entry(at, object));
            }
            for (var object : board().getHex(at).getDecorations()) {
                after.put(object.id(), Map.entry(at, object));
            }
            if (stroke.get(at).getLevel() != board().getHex(at).getLevel()) {
                board().getHex(at).getDecorations().stream().filter(o -> BuildingVolumes.of(o.asset()).present())
                      .forEach(o -> groundChanges.add(o.id()));
                var ref = board().getHex(at).getBuildingReference();
                if (ref != null && board().contains(ref.anchor())) {
                    board().getHex(ref.anchor()).getDecorations().stream().filter(o -> o.id().equals(ref.id())).findFirst().ifPresent(object -> {
                        groundChanges.add(object.id());
                        after.put(object.id(), Map.entry(ref.anchor(), object));
                    });
                }
            }
        }
        Set<String> changed = new LinkedHashSet<>(before.keySet()); changed.addAll(after.keySet());
        changed.removeIf(id -> Objects.equals(before.get(id), after.get(id)));
        changed.addAll(groundChanges);
        if (changed.isEmpty()) { return; }
        Map<String, Map<Coords, Hex>> old = new LinkedHashMap<>();
        for (int x = 0; x < board().getWidth(); x++) {
            for (int y = 0; y < board().getHeight(); y++) {
                Coords at = new Coords(x, y); Hex hex = board().getHex(at);
                var ref = hex.getBuildingReference();
                if (ref != null && changed.contains(ref.id())) {
                    old.computeIfAbsent(ref.id(), ignored -> new LinkedHashMap<>()).put(at, hex.duplicate());
                }
            }
        }
        var definition = blueprint.component("building");
        Set<Coords> freed = new LinkedHashSet<>();
        Set<String> rebuilt = new LinkedHashSet<>();
        // Removed placements first: the hexes they free are open to the placements after them.
        for (String id : changed.stream().sorted(Comparator.comparing(id -> after.containsKey(id))).toList()) {
            var members = old.getOrDefault(id, Map.of());
            var next = after.get(id);
            var previousObject = before.get(id);
            if (next != null) {
                if (!BuildingVolumes.of(next.getValue().asset()).present()) { continue; }
                if (!groundChanges.contains(id) && previousObject != null && previousObject.getKey().equals(next.getKey())
                      && BuildingVolumes.sameGeometry(previousObject.getValue(), next.getValue())) {
                    // A cosmetic edit still refreshes all rendered shares, without recreating collapsed terrain.
                    members.keySet().forEach(at -> { beginHexEdit(at); board().setHex(at, board().getHex(at).duplicate()); });
                    continue;
                }
            }
            Hex template = members.values().stream().filter(h -> h.containsTerrain(Terrains.BUILDING)).findFirst()
                  .orElse(paintTemplate.containsTerrain(Terrains.BUILDING) ? paintTemplate
                        : new Hex(0, "building:2;bldg_cf:60;bldg_elev:3;bldg_basement_type:1", null));
            for (Coords at : members.keySet()) {
                change(at, hex -> {
                    // A destroyed member keeps its ruin and rubble where it fell; only its reference goes.
                    if (hex.getRuin(Terrains.BUILDING) != null) { hex.setBuildingReference(null); return; }
                    removeComponent(hex, definition);
                    hex.removeTerrain(Terrains.BLDG_INTERIORS);
                });
            }
            freed.addAll(members.keySet());
            if (next == null) { continue; }
            claimBuildingHexes(id, next.getKey(), next.getValue(), members, template, null, rebuilt);
        }
        reclaimBuildingHexes(freed, rebuilt);
        authoredExits(rebuilt);
    }

    private Map<Coords, BuildingVolumes.Cover> covers(Coords anchor, BoardDecoration object) {
        return BuildingVolumes.covers(anchor, object, board().getWidth(), board().getHeight(), board()::getHex);
    }

    /**
     * Gives an authored placement the hexes it covers ({@code only}: of these, or all). Placements may overlap: a hex
     * another one holds goes to whichever has more of its roof over it, on equal shares the taller, then the holder.
     * The other keeps its model and its remaining hexes.
     */
    private void claimBuildingHexes(String id, Coords anchor, BoardDecoration object, Map<Coords, Hex> members, Hex template,
          Set<Coords> only, Set<String> rebuilt) {
        var definition = blueprint.component("building");
        rebuilt.add(id);
        for (var member : covers(anchor, object).entrySet()) {
            Coords at = member.getKey();
            if (only != null && !only.contains(at)) { continue; }
            var holder = board().getHex(at).getBuildingReference();
            if (holder != null && !holder.id().equals(id)) {
                if (!outweighs(member.getValue(), holder, at)) { continue; }
                rebuilt.add(holder.id());
            }
            Hex previous = members.get(at), source = previous != null && previous.containsTerrain(Terrains.BUILDING) ? previous : template;
            change(at, hex -> {
                if (previous == null || previous.containsTerrain(Terrains.BUILDING)) {
                    paintFields(hex, source, definition);
                    BuildingInteriors.set(hex, BuildingInteriors.choice(source));
                    hex.addTerrain(new Terrain(Terrains.BUILDING, source.terrainLevel(Terrains.BUILDING), true, 0));
                    hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, member.getValue().height()));
                }
                hex.setBuildingReference(new BuildingVolumes.Reference(id, anchor));
            });
        }
    }

    private boolean outweighs(BuildingVolumes.Cover cover, BuildingVolumes.Reference holder, Coords at) {
        var object = board().getHex(holder.anchor()).getDecorations().stream()
              .filter(candidate -> candidate.id().equals(holder.id())).findFirst().orElse(null);
        var held = object == null ? null : covers(holder.anchor(), object).get(at);
        if (held == null) { return true; }
        boolean equalShares = Math.abs(cover.area() - held.area()) <= Math.max(cover.area(), held.area()) * 1e-3;
        return equalShares ? cover.height() > held.height() : cover.area() > held.area();
    }

    /** Hexes a moved or removed placement let go of return to whichever other placement covers most of them. */
    private void reclaimBuildingHexes(Set<Coords> freed, Set<String> rebuilt) {
        freed.removeIf(at -> board().getHex(at).containsTerrain(Terrains.BUILDING) || board().getHex(at).getBuildingReference() != null);
        if (freed.isEmpty()) { return; }
        for (int x = 0; x < board().getWidth(); x++) {
            for (int y = 0; y < board().getHeight(); y++) {
                Coords anchor = new Coords(x, y);
                for (var object : board().getHex(anchor).getDecorations()) {
                    if (!BuildingVolumes.of(object.asset()).present()) { continue; }
                    claimBuildingHexes(object.id(), anchor, object, Map.of(), memberSample(object.id()), freed, rebuilt);
                }
            }
        }
    }

    /** The building terrain a placement's hexes share, for a hex it gains. */
    private Hex memberSample(String id) {
        for (int x = 0; x < board().getWidth(); x++) {
            for (int y = 0; y < board().getHeight(); y++) {
                Hex hex = board().getHex(x, y);
                var reference = hex.getBuildingReference();
                if (reference != null && reference.id().equals(id) && hex.containsTerrain(Terrains.BUILDING)) { return hex.duplicate(); }
            }
        }
        return paintTemplate.containsTerrain(Terrains.BUILDING) ? paintTemplate
              : new Hex(0, "building:2;bldg_cf:60;bldg_elev:3;bldg_basement_type:1", null);
    }

    /** A placement's hexes connect to its own other hexes, whichever ones its neighbours won. */
    private void authoredExits(Set<String> rebuilt) {
        for (int x = 0; x < board().getWidth(); x++) {
            for (int y = 0; y < board().getHeight(); y++) {
                Coords at = new Coords(x, y);
                Hex hex = board().getHex(at);
                var reference = hex.getBuildingReference();
                var building = hex.getTerrain(Terrains.BUILDING);
                if (reference == null || building == null || !rebuilt.contains(reference.id())) { continue; }
                int sides = 0;
                for (int side = 0; side < 6; side++) {
                    Coords next = at.translated(side);
                    var other = board().contains(next) ? board().getHex(next).getBuildingReference() : null;
                    if (other != null && other.id().equals(reference.id())) { sides |= 1 << side; }
                }
                int exits = sides, level = building.getLevel();
                if (building.getExits() != exits || !building.hasExitsSpecified()) {
                    change(at, current -> current.addTerrain(new Terrain(Terrains.BUILDING, level, true, exits)));
                }
            }
        }
    }

    /** Connecting a building drops its pivot and replaces core-only authored artwork with its generic modular kit. */
    private void connectedBuildingLooks() {
        for (Coords at : List.copyOf(stroke.keySet())) {
            Hex hex = board().getHex(at);
            if (hex.getBuildingReference() != null || !TilesetScenery.buildingConnected(hex, at, board()::getHex)) { continue; }
            var design = hex.getAppearance().get("building");
            if (design == null) { continue; }
            String asset = TilesetScenery.structureModels(hex, false).get(Terrains.BUILDING);
            var next = TilesetScenery.singleHexBuilding(asset) ? HexAppearance.withColours(null, design.colours())
                  : HexAppearance.withRotation(design, 0);
            if (!java.util.Objects.equals(design, next)) { change(at, changed -> appearance(changed, "building", next)); }
        }
    }

    /**
     * A building the stroke changed keeps one footprint and one kit variant: the ones most of the hexes the stroke left
     * alone hold (Square on a tie; the first such hex's variant, none or an unlisted name counting as the kit's first)
     * go to all its hexes, so a joined hex, or a joined building, takes them.
     */
    private void joinBuildingLooks() {
        Set<Coords> done = new java.util.HashSet<>();
        java.util.function.Predicate<Coords> changed = at -> stroke.containsKey(at)
              && !BoardFile.hexNode(stroke.get(at)).equals(BoardFile.hexNode(board().getHex(at)));
        for (Coords at : List.copyOf(stroke.keySet())) {
            if (board().getHex(at).getBuildingReference() != null || done.contains(at) || !board().getHex(at).containsTerrain(Terrains.BUILDING) || !changed.test(at)) { continue; }
            List<Coords> building = structure(at, Terrains.BUILDING);
            done.addAll(building);
            var kept = building.stream().filter(changed.negate())
                  .map(cell -> HexAppearance.footprint(board().getHex(cell).getAppearance())).toList();
            if (kept.isEmpty()) { continue; }
            long square = kept.stream().filter(HexAppearance.Footprint.SQUARE::equals).count();
            var joined = 2 * square >= kept.size() ? HexAppearance.Footprint.SQUARE : HexAppearance.Footprint.ANGLED;
            Map<String, Integer> variants = new LinkedHashMap<>();
            List<String> listed = kitVariants(board().getHex(at));
            building.stream().filter(changed.negate()).forEach(cell -> {
                String stored = HexAppearance.kitVariant(board().getHex(cell).getAppearance());
                variants.merge(stored != null && listed.contains(stored) ? stored : listed.isEmpty() ? "" : listed.getFirst(), 1,
                      Integer::sum);
            });
            String variant = java.util.Collections.max(variants.entrySet(), Map.Entry.comparingByValue()).getKey();
            Map<BoardDecoration.Colours, Integer> colours = new LinkedHashMap<>();
            building.stream().filter(changed.negate()).forEach(cell -> {
                var design = board().getHex(cell).getAppearance().get("building");
                colours.merge(design == null ? BoardDecoration.Colours.NONE : design.colours(), 1, Integer::sum);
            });
            var tint = java.util.Collections.max(colours.entrySet(), Map.Entry.comparingByValue()).getKey();
            // One roof too: the commoner of the stored choices, none where most store none.
            Map<String, Integer> roofs = new LinkedHashMap<>();
            building.stream().filter(changed.negate()).forEach(cell -> {
                var stored = board().getHex(cell).getAppearance().get("roof");
                roofs.merge(stored == null ? "" : stored.variant(), 1, Integer::sum);
            });
            String roof = java.util.Collections.max(roofs.entrySet(), Map.Entry.comparingByValue()).getKey();
            building.forEach(cell -> change(cell, hex -> {
                footprint(hex, joined);
                kitVariant(hex, variant.isEmpty() ? null : variant);
                appearance(hex, "roof", roof.isEmpty() ? null : new HexAppearance(roof, null, null, null));
                appearance(hex, "building", HexAppearance.withColours(hex.getAppearance().get("building"), tint));
            }));
        }
    }

    private boolean buildingBrush() {
        return sampledHex == null && brushComponent.equals("building")
              && (tool == Tool.PAINT || tool == Tool.ERASE);
    }

    private boolean wallBrush() {
        return buildingBrush() && paintTemplate.terrainLevel(Terrains.BUILDING) == BuildingType.WALL.getTypeValue();
    }

    /** The shared drawing path supplies explicit connections, retaining branches already drawn at its endpoints. */
    private void paintBuildings(List<Coords> path) {
        int weight = paintTemplate.terrainLevel(Terrains.BUILDING);
        Set<Coords> allowed = new java.util.HashSet<>();
        for (Coords at : path) {
            Hex hex = board().getHex(at);
            if (overwrites(at) && !hex.containsAnyTerrainOf(Terrains.FUEL_TANK, Terrains.BRIDGE)
                  && (!hex.containsTerrain(Terrains.BUILDING) || hex.terrainLevel(Terrains.BUILDING) == weight)) {
                allowed.add(at);
            } else { strokeNotice = Messages.getString("BoardEditor.way.structure"); }
        }
        HexAppearance dome = path.size() > 1 ? connectingDome(path, allowed) : null;
        for (int i = 0; i < path.size(); i++) {
            Coords at = path.get(i);
            if (!allowed.contains(at)) { continue; }
            Terrain existing = board().getHex(at).getTerrain(Terrains.BUILDING);
            boolean keepDome = dome != null && domed(board().getHex(at));
            int sides = existing == null ? 0 : existing.getExits();
            for (int j : new int[] { i - 1, i + 1 }) {
                if (j >= 0 && j < path.size() && allowed.contains(path.get(j)) && at.distance(path.get(j)) == 1) {
                    sides |= 1 << at.direction(path.get(j));
                }
            }
            int exits = sides;
            change(at, hex -> {
                if (!keepDome) { paintFields(hex, paintTemplate, blueprint.component("building")); }
                hex.addTerrain(new Terrain(Terrains.BUILDING, weight, true, exits));
                if (!keepDome) {
                    appearance(hex, "building", dome == null ? paintTemplate.getAppearance().get("building") : dome);
                }
            });
        }
        if (!path.isEmpty()) {
            selected = path.getLast(); selectedObject = ""; component = "building";
            selection.clear(); selection.add(new Selection(selected, ""));
        }
        message = strokeNotice;
    }

    /** A connecting stroke extends the dome design, retaining the existing domes' appearance and height. */
    private HexAppearance connectingDome(List<Coords> path, Set<Coords> allowed) {
        for (Coords at : path) {
            Hex original = board().getHex(at);
            if (!allowed.contains(at) || !domed(original)) { continue; }
            var look = original.getAppearance().get("building");
            return look != null ? look : new HexAppearance(null, TilesetScenery.structureModels(original).get(Terrains.BUILDING), null, null);
        }
        return null;
    }

    private static boolean domed(Hex hex) {
        return BoardGenerator.of(TilesetScenery.structureModels(hex).get(Terrains.BUILDING)) == BoardGenerator.MULTI_HEX_BUILDING;
    }

    /** Erasing a wall clears the neighbours' back edges in the same preview and undo step. */
    private void disconnectWalls() {
        int wall = BuildingType.WALL.getTypeValue();
        for (Coords at : painted) {
            Hex before = stroke.get(at);
            if (before == null || before.terrainLevel(Terrains.BUILDING) != wall) { continue; }
            for (int direction = 0; direction < 6; direction++) {
                Coords next = at.translated(direction);
                Hex neighbour = board().getHex(next);
                if (neighbour == null || neighbour.terrainLevel(Terrains.BUILDING) != wall) { continue; }
                int exits = neighbour.getTerrain(Terrains.BUILDING).getExits() & ~(1 << ((direction + 3) % 6));
                change(next, hex -> hex.addTerrain(new Terrain(Terrains.BUILDING, wall, true, exits)));
            }
        }
    }

    private void history(ArrayDeque<Edit> from, ArrayDeque<Edit> to, boolean forward) {
        if (from.isEmpty()) { return; }
        Edit edit = from.pop();
        if (edit.resize() != null) {
            // The history is linear, so the board installed here is exactly as this step left it.
            Board next = forward ? edit.resize().after() : edit.resize().before();
            keepSettings(board(), next);
            importIssues = forward ? edit.resize().importsAfter() : edit.resize().importsBefore();
            currentId = forward ? edit.afterId() : edit.beforeId(); to.push(edit);
            install(next);
            return;
        }
        Map<megamek.common.board.BoardLocation, Hex> restored = new LinkedHashMap<>();
        (forward ? edit.after() : edit.before()).forEach((at, hex) -> restored.put(
              megamek.common.board.BoardLocation.of(at, board().getBoardId()), hex.duplicate()));
        board().setHexes(restored);
        validate(withRunEnds(edit.before().keySet()));
        currentId = forward ? edit.afterId() : edit.beforeId(); to.push(edit);
        selection.clear();
        Hex selectedHex = selected == null ? null : board().getHex(selected);
        if (selectedHex == null || selectedHex.getDecorations().stream().noneMatch(object -> object.id().equals(selectedObject))) {
            selectedObject = "";
        }
    }

    private void addComponent(Hex hex, BoardEditorBlueprint.Component definition) {
        if (!definition.present().isEmpty() && definition.isPresent(name -> hex.containsTerrain(Terrains.getType(name)))) { return; }
        for (String token : definition.defaults().split(";")) {
            if (!token.isBlank()) { Terrain terrain = new Terrain(token); if (!hex.containsTerrain(terrain.getType())) { hex.addTerrain(terrain); } }
        }
        if (definition.id().equals("bridge")) {
            require(!hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK), Messages.getString("BoardEditor.way.structure"));
            Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
            hex.addTerrain(new Terrain(Terrains.BRIDGE, bridge.getLevel(), true, 0));
            appearance(hex, "bridge", HexAppearance.BUILT_BRIDGE);
        }
        if (definition.id().equals("road")) {
            Terrain road = hex.getTerrain(Terrains.ROAD);
            hex.addTerrain(new Terrain(Terrains.ROAD, road.getLevel(), true, road.getExits()));
        }
        applyChoiceMaterials(hex, definition, true);
    }

    private void setTerrain(Hex hex, String name, int value) { setTerrain(hex, name, value, true); }

    /** {@code colony}: a board hex, whose ground decides a new road's finish; false for a brush's template. */
    private void setTerrain(Hex hex, String name, int value, boolean colony) {
        int type = Terrains.getType(name); require(type > 0, "Unknown terrain " + name);
        if (type == Terrains.BUILDING && (hex.terrainLevel(Terrains.BUILDING) == BuildingType.WALL.getTypeValue()
              || value == BuildingType.WALL.getTypeValue())) { return; }
        if (type == Terrains.BLDG_ELEV && hex.getBuildingReference() != null) { return; }
        Terrain old = hex.getTerrain(type);
        // A new industrial elevator's shaft reaches at least the surface and carries 100 t, the old editor's default. A new
        // deployment zone is zone 1 at level 0, as the old editor wrote it (its level is never read).
        Terrain next = old != null ? new Terrain(type, value, old.hasExitsSpecified(), old.getExits())
              : type == Terrains.INDUSTRIAL_ELEVATOR ? new Terrain(type, value, true, IndustrialElevator.exits(Math.max(0, value), 100))
              : type == Terrains.DEPLOYMENT_ZONE ? new Terrain(type, 0, true, zoneMask("1"))
              : new Terrain(type, value);
        List<String> errors = new ArrayList<>(); require(next.isValid(errors), String.join("\n", errors));
        if (type == Terrains.FOLIAGE_ELEV) {
            Hex candidate = hex.duplicate(); candidate.addTerrain(next);
            require(candidate.isValid(errors), String.join("\n", errors));
        }
        hex.addTerrain(next);
        if (type == Terrains.WOODS || type == Terrains.JUNGLE) {
            hex.removeTerrain(type == Terrains.WOODS ? Terrains.JUNGLE : Terrains.WOODS);
            // Keep the low-canopy option; choose a valid normal canopy through the shared Hex rules.
            if (hex.terrainLevel(Terrains.FOLIAGE_ELEV) != 1) {
                for (int candidate : new int[] { 2, 3 }) {
                    hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, candidate));
                    if (hex.isValid(null)) { break; }
                }
            }
        }
        blueprint.components().stream().filter(owner -> owner.fields().stream().anyMatch(field -> field.terrain().equals(name)))
              .findFirst().ifPresent(owner -> applyChoiceMaterials(hex, owner, colony));
    }

    /**
     * A road brush's look as {@code hex} takes it: without a finish card chosen (a card of a shape only counts as
     * none), its ground's finish (BoardSceneryLayouts#roadFinish); a chosen finish stays.
     */
    private HexAppearance colonyFinish(Hex hex, HexAppearance look) {
        var chosen = blueprint.variant(brushKey);
        if (look == null || chosen != null && !chosen.material().isEmpty()) { return look; }
        return HexAppearance.keepLanes(new HexAppearance(look.variant(), look.asset(), BoardSceneryLayouts.roadFinish(hex, look.material()),
              look.strength(), look.colours(), look.rotation(), look.tracks(), look.beside(), look.trafficControl()), look);
    }

    /**
     * The finishes the terrain's choices define on {@code hex}; on a {@code colony} board hex a road without a finish
     * of its own is laid as its ground has it (BoardSceneryLayouts#roadFinish).
     */
    private static void applyChoiceMaterials(Hex hex, BoardEditorBlueprint.Component owner, boolean colony) {
        for (var field : owner.fields()) {
            for (var choice : field.choices()) {
                if (choice.material().isEmpty() || hex.terrainLevel(Terrains.getType(field.terrain())) != choice.value()) { continue; }
                var styles = new LinkedHashMap<>(hex.getAppearance());
                String key = field.terrain().equals("bridge_road") ? "bridgeRoad" : owner.id();
                var old = styles.get(key);
                boolean fresh = old == null || old.material() == null || old.material().isBlank();
                // A colony's slabs are a paved finish: a paved choice keeps them.
                boolean slabs = !fresh && old.material().equals(BoardSceneryLayouts.COLONY_SLABS)
                      && choice.material().equals("asphalt");
                String material = slabs ? old.material() : colony && fresh && (key.equals("road") || key.equals("bridgeRoad"))
                      ? BoardSceneryLayouts.roadFinish(hex, choice.material()) : choice.material();
                styles.put(key, roadLanes(hex, key, HexAppearance.withTrafficControl(new HexAppearance(old == null ? null : old.variant(),
                      old == null ? null : old.asset(), material, old == null ? null : old.strength()),
                      old == null ? null : old.trafficControl()), old));
                hex.setAppearance(styles);
            }
        }
    }

    /** The selected hex's sides that join the same water or pavement beside it, by terrain name. */
    private Map<String, Integer> joinedSides() {
        Hex hex = selected == null ? null : board().getHex(selected);
        if (hex == null) { return Map.of(); }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int type : SEPARABLE) {
            if (!hex.containsTerrain(type)) { continue; }
            int sides = 0;
            for (int direction = 0; direction < 6; direction++) {
                Hex next = board().getHex(selected.translated(direction));
                if (next != null && next.containsTerrain(type) && !apart(type, hex, next, direction)) { sides |= 1 << direction; }
            }
            result.put(Terrains.getName(type), sides);
        }
        return Map.copyOf(result);
    }

    /** Whether a separated side parts {@code hex}'s terrain from {@code next}'s, the neighbour in {@code direction}. */
    private static boolean apart(int type, Hex hex, Hex next, int direction) {
        String owner = Terrains.getName(type);
        return hex.getLevel() == next.getLevel() && ((HexAppearance.separated(hex.getAppearance(), owner) >> direction
              | HexAppearance.separated(next.getAppearance(), owner) >> (direction + 3) % 6) & 1) != 0;
    }

    /** Separates or rejoins the selected hex's water or pavement and the same terrain across {@code direction}. */
    private void toggleSeparated(int type, int direction) {
        Coords at = selected.translated(direction);
        Hex hex = board().getHex(selected), next = board().getHex(at);
        require(hex.containsTerrain(type), Messages.getString("BoardEditor.addTerrainFirst"));
        require(next != null && next.containsTerrain(type) && next.getLevel() == hex.getLevel(),
              Messages.getString("BoardEditor.connectedSidesRefused"));
        boolean apart = !apart(type, hex, next, direction);
        change(selected, current -> separated(current, type, direction, apart));
        change(at, current -> separated(current, type, (direction + 3) % 6, apart));
    }

    private static void separated(Hex hex, int type, int direction, boolean apart) {
        String owner = Terrains.getName(type);
        int sides = HexAppearance.separated(hex.getAppearance(), owner);
        Map<String, HexAppearance> styles = new LinkedHashMap<>(hex.getAppearance());
        HexAppearance look = HexAppearance.withSeparated(styles.get(owner), apart ? sides | 1 << direction : sides & ~(1 << direction));
        if (look == null) { styles.remove(owner); } else { styles.put(owner, look); }
        hex.setAppearance(styles);
    }

    /** Terrain gone from a hex takes its separated sides with it, so what is painted there again joins its neighbours. */
    private void openSeparatedSides() {
        for (Coords at : List.copyOf(stroke.keySet())) {
            for (int type : SEPARABLE) {
                if (!stroke.get(at).containsTerrain(type) || board().getHex(at).containsTerrain(type)) { continue; }
                for (int direction = 0; direction < 6; direction++) {
                    Coords next = at.translated(direction);
                    int back = (direction + 3) % 6;
                    if (board().contains(next) && (HexAppearance.separated(board().getHex(next).getAppearance(), Terrains.getName(type)) >> back & 1) != 0) {
                        change(next, current -> separated(current, type, back, false));
                    }
                }
            }
        }
    }

    private void editEdge(String name, int direction) {
        require(selected != null, Messages.getString("BoardEditor.selectHexFirst"));
        int type = Terrains.getType(name);
        if (type == Terrains.WATER || type == Terrains.PAVEMENT) {
            toggleSeparated(type, direction);
            if (!continuousEdit) { finishStroke(); }
            return;
        }
        Hex before = board().getHex(selected);
        BoardDecoration route = RailRoute.derived(before, "").stream()
              .filter(value -> RailRoute.onDeck(value) == (type == Terrains.BRIDGE_RAIL)).findFirst().orElse(null);
        change(selected, hex -> edge(hex, name, direction));
        if ((type == Terrains.RAIL || type == Terrains.BRIDGE_RAIL) && route != null) {
            Coords next = selected.translated(direction);
            Hex neighbour = board().getHex(next);
            if (neighbour != null) {
                boolean on = board().getHex(selected).containsTerrainExit(type, direction);
                int back = (direction + 3) % 6;
                var partner = !on ? RailRoute.joined(before, route, direction, neighbour)
                      : RailRoute.derived(neighbour, "").stream().filter(value -> RailRoute.kind(value) == RailRoute.kind(route))
                            .filter(value -> Float.isFinite(RailRoute.joinScore(route, RailRoute.level(before, route),
                                  value, RailRoute.level(neighbour, value))))
                            .filter(value -> !RailRoute.onDeck(value) || HexAppearance.implicit(neighbour.getAppearance())
                                  || neighbour.containsTerrainExit(Terrains.BRIDGE, back))
                            .min(Comparator.comparingDouble(value -> RailRoute.joinScore(route, RailRoute.level(before, route),
                                  value, RailRoute.level(neighbour, value)))).orElse(null);
                if (partner != null) {
                    int sides = on ? partner.connections() | 1 << back : partner.connections() & ~(1 << back);
                    change(next, hex -> RailRoute.lay(hex, RailRoute.onDeck(partner), sides,
                          RailRoute.layout(sides, partner.tracks()), RailRoute.kind(partner)));
                }
            }
        }
        if (!continuousEdit) { finishStroke(); }
    }

    private void edge(Hex hex, String name, int direction) {
        require(direction >= 0 && direction < 6, "A hex has six sides.");
        int type = Terrains.getType(name); Terrain old = hex.getTerrain(type);
        if (old == null && type == Terrains.CLIFF_TOP) { old = new Terrain(type, 1, true, 0); }
        require(old != null, Messages.getString("BoardEditor.addTerrainFirst"));
        require(old.getExits() >= 0 && old.getExits() <= 63, Messages.getString("BoardEditor.legacyEdgeMask"));
        int exits = old.getExits() ^ (1 << direction);
        if (type == Terrains.BRIDGE && (exits & (1 << direction)) == 0) {
            require(!hex.containsTerrainExit(Terrains.BRIDGE_ROAD, direction) && !hex.containsTerrainExit(Terrains.BRIDGE_RAIL, direction),
                  Messages.getString("BoardEditor.way.exitUsed"));
        }
        if (type == Terrains.BRIDGE_ROAD || type == Terrains.BRIDGE_RAIL) {
            require(hex.hasBridge() && (HexAppearance.implicit(hex.getAppearance())
                  || (exits & ~hex.getTerrain(Terrains.BRIDGE).getExits()) == 0),
                  Messages.getString("BoardEditor.way.exitOutside"));
        }
        if (type == Terrains.CLIFF_TOP && (exits & (1 << direction)) != 0) {
            Hex neighbor = board().getHex(selected.translated(direction));
            require(hex.canHaveCliffTopTowards(neighbor), Messages.getString("BoardEditor.cliffEdgeDrop"));
        }
        if (type == Terrains.CLIFF_TOP && exits == 0) { hex.removeTerrain(type); }
        else {
            hex.addTerrain(new Terrain(type, old.getLevel(), true, exits));
            if (type == Terrains.RAIL || type == Terrains.BRIDGE_RAIL) {
                RailRoute.lay(hex, type == Terrains.BRIDGE_RAIL, exits,
                      RailRoute.layout(exits, HexAppearance.tracks(hex.getAppearance(), wayOwner(type))),
                      old.getLevel() == 2 ? RailRoute.Kind.MAGLEV : RailRoute.Kind.RAIL);
            }
        }
    }

    private BoardDecoration object(Hex hex) {
        return hex.getDecorations().stream().filter(d -> d.id().equals(selectedObject)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException(Messages.getString("BoardEditor.selectObjectFirst")));
    }

    /** Contents keep authored list positions; decal slots show the same top-to-bottom order as the renderer. */
    private static List<BoardDecoration> contentsOrder(Hex hex) {
        var paint = hex.getDecorations().stream().filter(d -> d.kind().equals("decal"))
              .sorted(Comparator.comparingInt(BoardDecoration::drawOrder).thenComparing(BoardDecoration::id).reversed()).iterator();
        return hex.getDecorations().stream().map(d -> d.kind().equals("decal") ? paint.next() : d).toList();
    }

    private void reorderObject(Hex hex, String id, String destination) {
        int split = destination.indexOf(':');
        require(split > 0, "Choose a position in the contents list.");
        boolean before = destination.substring(0, split).equals("before");
        require(before || destination.substring(0, split).equals("after"), "Choose before or after the target.");
        String targetId = destination.substring(split + 1);
        if (id.equals(targetId)) { return; }
        var objects = new ArrayList<>(contentsOrder(hex));
        var moving = objects.stream().filter(d -> d.id().equals(id)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException(Messages.getString("BoardEditor.reorderGone")));
        var target = objects.stream().filter(d -> d.id().equals(targetId)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException(Messages.getString("BoardEditor.reorderTargetGone")));
        require(Objects.equals(moving.placement().receiver(), target.placement().receiver()), Messages.getString("BoardEditor.reorderSurface"));
        objects.remove(moving); objects.add(objects.indexOf(target) + (before ? 0 : 1), moving);
        if (moving.kind().equals("decal")) {
            var paint = objects.stream().filter(d -> d.kind().equals("decal")
                  && Objects.equals(d.placement().receiver(), moving.placement().receiver())).toList();
            long order = Math.max(paint.stream().mapToInt(BoardDecoration::drawOrder).max().orElse(0),
                  (long) Integer.MIN_VALUE + paint.size() - 1);
            for (var decal : paint) {
                objects.set(objects.indexOf(decal), objectValue(decal, "order", Long.toString(order--)));
            }
        }
        hex.setDecorations(objects);
    }
    private void replaceObject(Hex hex, java.util.function.UnaryOperator<BoardDecoration> transform) {
        BoardDecoration object = object(hex), next = transform.apply(object);
        hex.setDecorations(hex.getDecorations().stream().map(d -> d.id().equals(object.id()) ? next : d).toList());
    }

    private BoardDecoration objectValue(BoardDecoration d, String property, String value) {
        require(!RailRoute.isRoute(d), Messages.getString("BoardEditor.routeExtend"));
        if (property.startsWith("flag.")) { return d.withFlag(d.flag().with(property.substring(5), value)); }
        if (property.startsWith("billboard.")) { return d.withBillboard(d.billboard().with(property.substring(10), value)); }
        if (property.equals("levels")) { return d.withLevels(Math.max(1, Integer.parseInt(value))); }
        double x = d.x(), y = d.y(), rotation = d.rotation(), scale = d.scale();
        double rotationX = d.rotationX(), rotationY = d.rotationY();
        boolean clipToHex = d.clipToHex();
        boolean mirror = d.mirror(), bare = d.bare(); var placement = d.placement(); int order = d.drawOrder(); String name = d.name();
        var stretch = d.stretch();
        var colours = d.colours();
        switch (property) {
            case "x" -> x = Double.parseDouble(value);
            case "y" -> y = Double.parseDouble(value);
            case "rotation" -> rotation = Double.parseDouble(value);
            case "rotationX" -> rotationX = Double.parseDouble(value);
            case "rotationY" -> rotationY = Double.parseDouble(value);
            case "scale" -> scale = Double.parseDouble(value);
            // Width, length and height factors in the object's own axes; the inspector shows them beside the scale.
            case "stretchX" -> stretch = new BoardDecoration.Stretch(Double.parseDouble(value), stretch.y(), stretch.z());
            case "stretchY" -> stretch = new BoardDecoration.Stretch(stretch.x(), Double.parseDouble(value), stretch.z());
            case "stretchZ" -> stretch = new BoardDecoration.Stretch(stretch.x(), stretch.y(), Double.parseDouble(value));
            // A colour slot's replacement (#rrggbb); empty returns the slot to the model's own colour.
            case "colour0", "colour1", "colour2", "colour3" ->
                  colours = colours.with(property.charAt(6) - '0', value.isEmpty() ? null : value);
            case "clipToHex" -> clipToHex = Boolean.parseBoolean(value);
            case "mirror" -> mirror = Boolean.parseBoolean(value);
            case "bare" -> bare = Boolean.parseBoolean(value);
            case "name" -> name = value;
            case "order" -> order = Integer.parseInt(value);
            case "level" -> placement = BoardDecoration.Placement.absolute(Double.parseDouble(value));
            case "offset" -> placement = BoardDecoration.Placement.surface(placement.receiver() == null ? "ground" : placement.receiver().terrain(),
                  placement.receiver() == null ? "top" : placement.receiver().surface(), Double.parseDouble(value));
            case "receiver" -> {
                if (value.equals("absolute")) { placement = BoardDecoration.Placement.absolute(selected == null ? 0 : board().getHex(selected).getLevel()); }
                else {
                    String[] parts = value.split("/");
                    require(parts.length == 2, "Choose a valid supporting surface.");
                    placement = BoardDecoration.Placement.surface(parts[0], parts[1], 0);
                }
            }
            default -> throw new IllegalArgumentException("Unknown object property " + property);
        }
        return new BoardDecoration(d.id(), d.kind(), d.asset(), name, x, y, rotation, mirror, scale, placement, order, clipToHex,
              rotationX, rotationY, d.group(), bare, d.connections(), stretch, colours, d.tracks(), d.pillars(), d.uid(), d.levels(), d.flag(), d.billboard());
    }

    private static void copyInto(Hex source, Hex target) {
        target.removeRuins();
        target.removeAllTerrains(); for (int type : source.getTerrainTypes()) { target.addTerrain(new Terrain(source.getTerrain(type))); }
        source.getRuins().forEach(ruin -> target.addRuin(new Terrain(ruin)));
        target.setLevel(source.getLevel()); target.setTheme(source.getTheme()); target.setAppearance(source.getAppearance());
        target.setDecorations(BoardDecoration.copies(source.getDecorations()));
        target.setBuildingReference(null);
    }
    private static void require(boolean valid, String message) { if (!valid) { throw new IllegalArgumentException(message); } }
}
