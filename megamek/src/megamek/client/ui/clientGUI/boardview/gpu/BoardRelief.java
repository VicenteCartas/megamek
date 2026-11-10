/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.ConcreteFit;
import megamek.common.board.Coords;

/**
 * Canonical sculpting of dry terrain. Every vertex that two meshes share (hex corners, edge samples, cliff rows) is a
 * pure function of its world position and of the board data around it, so neighbouring tops, their cliffs, picking
 * and unit support agree exactly without exchanging meshes. Game data stays authoritative: a rim sits at its hex's
 * level, a cliff foot at the lower neighbour's level, and the unit anchor in each hex stays level.
 *
 * <p>Corners are identified on the integer corner lattice ({@link #cornerX}), so all three hexes around a corner
 * evaluate the same {@link Corner}. A cliff between two hexes is owned by the higher one; its foot row is also the
 * lower hex's boundary. Walls use rows at fixed absolute heights, so cliffs of different spans meeting on a corner
 * line share every vertex. Visual displacement is horizontal and bounded; it never changes a game elevation.</p>
 */
final class BoardRelief {
    /** Canonical cliff rows; coarser render meshes select subsets without changing their positions. */
    private static final float[] ROWS = { 0f, .11f, .31f, .55f, .78f, .935f };
    private static final int[] CORNER_DX = { 2, 1, -1, -2, -1, 1 };
    private static final int[] CORNER_DY = { 0, 1, 1, 0, -1, -1 };
    private static final float EPSILON = .0005f;
    /** The most of a neck's shared edge, in edges, that each of its ends recedes: at least 30 % stays a usable top. */
    private static final float NECK_END = .34f;
    /**
     * How far, in edges, a water hex's bank strips may stitch ahead of their parameter order: a third of an edge,
     * about as far as the steps through a corner slide the boundary's samples along it.
     */
    private static final float LEAD = 1 / 3f;
    /**
     * The farthest, in hex-scale units, the water's shore moves a corner (see {@link Corner#move}): far enough for the
     * water to run on past a land hex's corner to where the land's islet turns it, {@link #SHORE_ISLE} from the land's
     * centre, with the room beyond.
     */
    private static final float SHORE_SHIFT = 28;
    /**
     * The top, in hex-scale units, a hex keeps round its centre beyond a smooth step's band and round where the step
     * moves its corner toward it: the unit anchor's clearance, as rocks keep it (a fifth of the hex's width).
     */
    private static final float STEP_KEEP = 16.625f;
    /**
     * The room, in hex-scale units, a corner the shore moves keeps beyond the water where land lies at the water's
     * level: BoardSurface's bank of 4.5, a unit, and most of the reach of the bank's rounding, so the shore runs on
     * past the corner as its field lies instead of bending short of it.
     */
    private static final float SHORE_ROOM = 8;
    /**
     * Reach, in hex-scale units, of each hex centre's pull on the shore field (see {@link #shore}). The pull falls off
     * as (1 - d^2 / r^2)^3, about as a Gaussian 40 units wide does: the shore runs smooth over about a hex, so a bank
     * along a row of hexes runs straight, a river bends round its turns and a lake is one rounded body of water.
     */
    private static final float SHORE_REACH = 128;
    /**
     * How much harder a hex pulls for each of its six neighbours of the other kind, water or land: a river a hex wide
     * keeps its width and a spit of land its tip, where a plain sum would narrow both toward their middles.
     */
    private static final float SHORE_NARROW = .75f;
    /** The pull of land whose corners stay put (paving, special artwork, roads): it keeps the shore back from them. */
    private static final float SHORE_HARD = 2.5f;
    /**
     * Radii, in hex-scale units, of the water every water hex keeps round its centre and of the land every land hex
     * keeps round its own, blended in over {@link #SHORE_BLEND}: a lone water hex is a round pond, a lone land hex in a
     * lake an islet, and a unit always stands in its own hex's water or on its own hex's land.
     */
    static final float SHORE_POOL = 28;
    private static final float SHORE_ISLE = 22;
    static final float SHORE_BLEND = 10;
    /**
     * How far, in hex-scale units, the bank wanders to either side of the field's own line, and the noise cell it
     * wanders over: bends one and a half to three hexes long, as on the printed maps, so a long bank curves gently
     * instead of running like a ruler.
     */
    static final float SHORE_WANDER = 8;
    static final float WANDER_CELL = 140;
    /**
     * How far, in hex-scale units, every bank stands out past the shore field's own line into the land: how fat the
     * water is. A river a hex wide grows by twice this and a lake's shore moves out by it; negative slims both.
     */
    static final float SHORE_SPREAD = -3;
    /**
     * The share of itself every land hex keeps however far the water's shore would run into it: the shore moves the
     * land's corners no further than leaves it that much (see {@link #keep}).
     */
    static final float LAND_KEEP = .75f;
    /** Hexes on each side of this one whose pulls on the water's shore field are kept (see {@link #shoreWeight}). */
    private static final int SHORE_WINDOW = 6;
    /** Width, in hex-scale units, of a water hex's bank from its level lip down to the water. */
    static final float SHORE_LIP = 5;
    /** Halvings of {@link #shoreReach}'s first dry step of 7 hex-scale units: the turn to about a tenth of a unit. */
    private static final int REACH_HALVINGS = 5;

    /**
     * Shape of one ground family's exposed rock, in metres: jointed masses (cell size and relief), the dark fractures
     * between them, bedding, broad buttresses, how far the face has retreated behind the hex edge, and the caprock lip.
     * {@code round} is the fillet of every cliff corner as a fraction of the hex edge. Steps of up to two levels are
     * banks of the soil mantle: they keep {@code bank} of the rock's jointing and lean back by {@code lean} metres per
     * metre of depth. {@code stones} and {@code shrubs} are average loose ground-cover counts per open hex. Poured
     * concrete has no relief of its own (see {@link #profile}).
     */
    private record Rock(float cellWidth, float cellHeight, float cells, float fractures, float strata, float bedding,
          float buttress, float recess, float cap, float round, float bank, float lean, float stones, float shrubs) { }

    /** Meadows and tropical woodland share soil banks over the same bedrock. */
    private static final Rock EARTH = new Rock(7.5f, 6.2f, 1.4f, .70f, .40f, 3.4f, 1.6f, 1.0f, .85f, .5f, .3f, .42f, .8f, .6f);
    /** Sandstone mesas of jointed columns: tall masses split by deep vertical joints, faint bedding. */
    private static final Rock SANDSTONE = new Rock(7.5f, 18f, 1.8f, 1.6f, .3f, 3.6f, 1.2f, 1.2f, .8f, .45f, 1, .06f, 1, 3);
    /** Jointed bedrock. */
    private static final Rock BEDROCK = new Rock(7.0f, 6.0f, 1.25f, .75f, .45f, 2.9f, 1.45f, 1.1f, .7f, .42f, 1, .12f, 2.5f, .4f);

    /** Indexed by {@link BoardScene.Surface#ordinal()}. */
    private static final Rock[] ROCKS = {
          // GRASS: earth banks on low steps, blocky granite from three levels.
          EARTH,
          // DIRT: soft, gullied earth.
          new Rock(2.6f, 9.0f, .45f, .75f, .25f, 1.8f, .80f, .8f, .6f, .5f, .55f, .38f, 1.2f, 1.4f),
          SANDSTONE,
          BEDROCK,
          // CONCRETE: flat cast faces; only the corner fillet and the bed colour apply.
          new Rock(6.0f, 6.0f, 0, 0, 0, 3.0f, 0, 0, 0, .05f, 1, 0, 0, 0),
          // SNOW: rock under a snow mantle that buries the low steps.
          new Rock(7.0f, 6.0f, 1.2f, .60f, .40f, 3.2f, 1.55f, 1.0f, .85f, .5f, .5f, .3f, 1, 0),
          // LUNAR: finely jointed bedrock.
          new Rock(3.6f, 4.2f, 1.25f, .75f, .45f, 2.9f, 1.25f, 1.1f, 1, .42f, 1, .12f, 2.5f, .4f),
          // FUNGUS: exposed, rounded rock with fungal crust. Authored fungi supply the cosmetic ground cover.
          new Rock(7.0f, 6.0f, 1.25f, .75f, .45f, 2.9f, 1.45f, 1.1f, .7f, .42f, 1, .12f, 0, 0),
          // DESERT, MARS: the same sandstone; nothing grows on Mars.
          SANDSTONE,
          new Rock(7.5f, 18f, 1.8f, 1.6f, .3f, 3.6f, 1.2f, 1.2f, .8f, .45f, 1, .06f, 1, 0),
          // VOLCANO: tall basalt joints with an ash mantle.
          new Rock(6.0f, 14.0f, 1.4f, 1.0f, .2f, 3.6f, 1.2f, 1.1f, .7f, .35f, 1, .10f, 2, 0),
          EARTH,
    };

    /**
     * Metres of room that a step between natural grounds takes on each side of its edge when the tuning turns hex
     * transitions on: up to two levels a slope from the upper hex's ground down into the lower one, from three levels a
     * cliff standing back from the edge above a talus that spreads past it.
     */
    static final float TRANSITION = 4;
    /** Waterside cliffs keep their plateau and descend to the bed without an exposed beach or talus. */
    static final boolean DEFAULT_CLIFFS_INTO_WATER = false;

    /**
     * Whether a deep road cut into a natural slope keeps its toe behind a precast containment wall at the road's edge
     * (BoardRoadCut#containmentWalls), so the slope gives up less ground; otherwise the slope recedes from the road.
     */
    static final boolean DEFAULT_CONTAINMENT_WALLS = true;

    /** How a natural step between tops runs round a hill: see {@link StepOutline}. */
    static final StepOutline DEFAULT_STEP_OUTLINE = StepOutline.HEX;

    /**
     * The outline of the natural steps between hexes of different levels. HEX follows each hex's edges, rounding
     * their corners a little by the ground's own roundness; CORNERS rounds every corner as far as its edges allow;
     * SMOOTH also moves each corner of a two-level step, as the water's shore moves its own, to where a smoothed field
     * of the hexes above and below crosses its middle ({@link #stepField}), so a hill's edge along a row of hexes runs
     * as one curve instead of a hex's corners. Concrete, roads, water and three-level corners keep their places.
     */
    enum StepOutline {
        HEX("Hex edges"), CORNERS("Rounded corners"), SMOOTH("Smooth");

        private final String label;

        StepOutline(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    /** Visual landform controls. Fixed lattice, stitching and sampling rules remain shared by every hex. */
    record Tuning(boolean cliffsIntoWater, boolean containmentWalls, StepOutline stepOutline) {
        Tuning(boolean cliffsIntoWater, boolean containmentWalls) {
            this(cliffsIntoWater, containmentWalls, DEFAULT_STEP_OUTLINE);
        }
    }

    static final Tuning DEFAULTS = new Tuning(DEFAULT_CLIFFS_INTO_WATER, DEFAULT_CONTAINMENT_WALLS);
    private static Tuning tuning = DEFAULTS;

    static Tuning tuning() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? tuning : settings.relief();
    }

    static void tune(Tuning next) {
        if (next.equals(tuning)) { return; }
        tuning = next;
        BoardGeometry.terrainChanged();
    }

    /**
     * World units of room a step takes on each side of its edge: the fixed {@link #TRANSITION} of hex transitions, or
     * with padding half the gap the padding opens between the hexes, so each hex keeps its own flat top and the gap
     * holds the step; zero when neither is on.
     */
    static float stepRoom() {
        BoardGeometry.Tuning geometry = BoardGeometry.tuning();
        return geometry.padding() > 0 ? metres(geometry.padding() / 2) : geometry.transitions() ? metres(TRANSITION) : 0;
    }
    private static final int GRASS = BoardScene.Surface.GRASS.ordinal();
    private static final int CONCRETE = BoardScene.Surface.CONCRETE.ordinal();
    private static final int SAND = BoardScene.Surface.SAND.ordinal();

    /** Surface kinds written into the rendered vertex data. */
    enum Kind {
        GROUND, SUBMERGED_CLIFF, PLANT, LEAF, CLIFF, PIT_WALL, PIT, ROCK;

        boolean cliff() { return this == CLIFF || this == PIT_WALL; }
    }

    /**
     * Per-vertex presentation data. Ground: {@code level} is the owning game level and rim/foot are distances in
     * metres to the nearest drop and rise. Cliff: {@code level} is how much of a rock cliff the face is, from 0 (a bank
     * of up to two levels) to 1 (three levels or more), blended across corners where steps of different heights meet;
     * rim/foot are the height above the foot and depth below the rim, in metres. Rock: rim/foot are the height above
     * its root and below its top. {@code tint} in [0, 1] is the hardness of the bed a cliff vertex lies in (the same
     * beds its ledges follow), one rock's own variation, or for ground the height of the nearest step. Plants carry
     * their authored red/green/blue in rim/foot/tint, so woody stems retain their colour. A tree pit's
     * earth has its variation below one half and its kerb stone one. A submerged cliff uses ground data and water
     * optics, with rim measuring height above its bed so rock can blend into sediment at the foot. A finite
     * {@code projection} gives a wall's fixed texture azimuth, independently of its triangles' lighting normals.
     */
    record Shade(Vector3 normal, Kind kind, float occlusion, float level, float rim, float foot, float tint, float projection) {
        Shade(Vector3 normal, Kind kind, float occlusion, float level, float rim, float foot, float tint) {
            this(normal, kind, occlusion, level, rim, foot, tint, Float.NaN);
        }
    }

    /**
     * One hex as the sculpt sees it. A water hex's family is the ground of the land around it (see
     * {@link #waterFamily}). {@code abutments}: the sides whose bank this hex's built deck lands on, on an abutment
     * ({@link BoardBridgeFooting#abutted}), the widest {@code abutment} half wide.
     */
    record Site(Coords coords, int level, boolean sculpted, boolean detailed, int family, boolean ultraSublevel, boolean liquid, boolean molten,
          int depth, boolean road, int ramps, int cliffTopExits, int deckRoads, int abutments, float abutment, int ix, int iy) {
        float x() { return cornerX(ix); }

        float y() { return cornerY(iy); }

        boolean fixedOutline() { return ultraSublevel || liquid || family == CONCRETE; }

        /** Concrete road cuts retain the corridor's existing outline and retaining panels. */
        boolean fixedRoad() { return family == CONCRETE && (road || ramps != 0); }
    }

    private final BoardScene scene;
    private final BoardScene.Tile tile;
    private final TerrainLod detail;
    private final Site self;
    private final BoardRiver river;
    private final BoardConcrete coast;
    private final boolean sculpted;
    private final Map<Coords, Site> sites = new HashMap<>();
    private final Map<Coords, Slab> decks = new HashMap<>();
    private final Map<Coords, BoardRoad> roads = new HashMap<>();
    /** How far each edge's band recedes from roads, by edge and canonical parameter; see {@link #roadPush}. */
    private final Map<Edge, Map<Float, float[]>> pushes = new HashMap<>();
    /** Each edge's own push at its fixed stations, before smoothing; see {@link #ownPush}. */
    private final Map<Edge, Station[]> stationPushes = new HashMap<>();
    private final Map<Corner, Station> cornerPushes = new IdentityHashMap<>();
    private final Map<Corner, float[]> bayCorners = new IdentityHashMap<>();
    /** This hex's steps down to land as a water hex, by edge; -1 until asked. */
    private int rimEdges = -1;
    /** The ground under a road that ends on a step's top at a corner (#landingFloor). */
    private final Map<Corner, Float> landings = new IdentityHashMap<>();
    /** Whether each station or corner keeps its toe behind a containment wall; see {@link #walled(Object, Edge)}. */
    private final Map<Object, Boolean> walledRuns = new HashMap<>();
    /** Edges between neighbours, by their corners, so each is built once; see {@link #edge(Site, int)}. */
    private final Map<List<Long>, Edge> builtEdges = new HashMap<>();
    /** The tops of the containment walls round this hex, as segments {ax, ay, bx, by}; see {@link #walled(float, float)}. */
    private float[] wallLines;
    private List<BoardRoad> nearbyWays;
    private final Map<Coords, List<BoardTunnel>> portals = new HashMap<>();
    private final Map<Coords, List<BoardBridge.Approach>> bridgeApproaches = new HashMap<>();
    private final Map<Long, Corner> corners = new HashMap<>();
    private final Edge[] edges = new Edge[6];
    private final Map<Vector3, Shade> shades = new IdentityHashMap<>();
    /** This hex's rim samples per edge, including the closing corner; cliffs reuse them as their top row. */
    private final Vector3[][] rims = new Vector3[6][];
    /** The shared top boundary, also used to keep a bank inside the actual relief beside a cliff. */
    private List<Vector3> outline;
    private int[] outlineStarts;
    /** A water hex's waterline, counter-clockwise and {@link BoardSurface#SHORE_SEGMENTS} per edge; null on land. */
    private Vector3[] waterline;
    /** The edges a water hex falls over, as a bit mask by edge. */
    private int falls;
    private List<Site> candidates;
    /** Nearby visual entrances, derived once when this mesh places its decorative rocks. */
    private List<BoardTunnel> tunnels;
    private BoardObstacles obstacles;
    /** Separate liquid fields within {@link #SHORE_WINDOW} of this hex, NaN until computed for this scene. */
    private float[] shoreWeights, moltenShoreWeights;
    /**
     * Each step's pulls ({@link #stepPull}) on the hexes within {@link #SHORE_WINDOW} of this one, by the step's
     * levels; NaN until known.
     */
    private final Map<Long, float[]> stepPulls = new HashMap<>();
    /** Shares of the shore's moves the land hexes allow, by coordinates; see {@link #keep}. */
    private final Map<Coords, Float> keeps = new HashMap<>();
    private float[] drops;
    private float[] rises;
    private final float[] scratchA = new float[2];
    private final float[] scratchB = new float[2];
    private final float[] bandA = new float[2];
    private final float[] bandB = new float[2];

    BoardRelief(BoardScene scene, BoardScene.Tile tile, int ramps) {
        this(scene, tile, ramps, TerrainLod.FULL);
    }

    BoardRelief(BoardScene scene, BoardScene.Tile tile, int ramps, TerrainLod detail) {
        this.scene = scene;
        this.tile = tile;
        river = new BoardRiver(scene);
        coast = BoardConcrete.of(scene);
        this.detail = detail;
        self = site(scene, tile, ramps);
        sites.put(tile.coords(), self);
        // Roads are natural ground here; their carriageways reserve it afterwards (BoardRoadCut).
        sculpted = !tile.ultraSublevel() && self.sculpted();
    }

    /**
     * Supported roads share natural edge relief, while BoardSurface owns their level centres and graded approaches.
     * Unsupported artwork retains its outline. Volcanic ground and banks share the normal slopes and cliffs.
     * A liquid hex ignores roads, as its surface does.
     */
    private static Site site(BoardScene scene, BoardScene.Tile tile, int ramps) {
        boolean liquid = tile.liquid().present();
        boolean road = !liquid && BoardRoad.carriageway(tile);
        // Ground a road only approaches is natural ground too: its graded mesh follows the same rim.
        boolean shaped = !tile.backdrop().present() && !BoardFeatures.shaft(tile)
              && (liquid || road || tile.wayExits() == 0 && tile.road() == BoardRoad.Kind.NONE);
        BoardScene.Surface family = liquid ? waterFamily(scene, tile) : tile.surface();
        // The directions a built deck on this water carries its road off onto the next hex.
        int deckRoads = 0;
        if (liquid && BoardBridge.feature(tile) != null && BoardBridge.family(tile) != BoardBridge.Family.NATURAL) {
            for (int d = 0; d < 6; d++) {
                if (BoardBridge.road(tile, scene.tile(tile.coords().translated(d)), d)) { deckRoads |= 1 << d; }
            }
        }
        int abutments = 0;
        float abutment = 0;
        for (int d = 0; BoardBridge.feature(tile) != null && d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (next != null && BoardBridgeFooting.abutted(tile, next, d)) {
                abutments |= 1 << d;
                abutment = Math.max(abutment, BoardBridgeFooting.abutmentHalfWidth(tile, d));
            }
        }
        return new Site(tile.coords(), tile.groundLevel(), shaped, shaped && tile.detailedGround(), family.ordinal(),
              tile.ultraSublevel(), liquid, tile.liquid().molten(), depth(tile), road, liquid ? 0 : ramps,
              tile.cliffTopExits(), deckRoads, abutments, abutment, centerIx(tile.coords()), centerIy(tile.coords()));
    }

    /**
     * The fallback ground for a water hex's bed and unassigned walls: the commonest of its dry neighbours' (the first of
     * them on a tie), so a river on a desert map runs between sand, not the meadow a plain water hex would default to;
     * its own where no such neighbour exists.
     */
    private static BoardScene.Surface waterFamily(BoardScene scene, BoardScene.Tile tile) {
        int[] counts = new int[BoardScene.Surface.values().length];
        BoardScene.Surface best = null;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile land = scene.tile(tile.coords().translated(direction));
            // Buildings and roads can keep authored artwork while still standing on a concrete slab. Their banks
            // continue that material without changing the original land or its foundations.
            if (land == null || land.backdrop().present() || land.liquid().present()
                  || !land.detailedGround() && land.surface() != BoardScene.Surface.CONCRETE) { continue; }
            int count = ++counts[land.surface().ordinal()];
            if (best == null || count > counts[best.ordinal()]) { best = land.surface(); }
        }
        return best == null ? tile.surface() : best;
    }

    // ---- Units and lattice ---------------------------------------------------------------------------------

    /** The point-to-point hex footprint is 30 metres; elevation remains the user's board tuning(). */
    static float metres(float value) { return value * BoardGeometry.width() / 30; }

    /** Enlarged art scale kept for legacy material repeats; game distances still use {@link #metres}. */
    static float detailMetres(float value) { return value * BoardGeometry.width() / 5; }

    static int centerIx(Coords coords) { return 3 * coords.getX() + 2; }

    static int centerIy(Coords coords) { return -(2 * coords.getY() + (coords.getX() & 1) + 1); }

    static float cornerX(int ix) { return ix * (BoardGeometry.width() / 4); }

    static float cornerY(int iy) { return iy * (BoardGeometry.height() / 2); }

    /** Largest height any sculpted vertex reaches above its hex level: relief crowns and rim rocks. */
    static float headroom(BoardScene.Tile tile) {
        if (tile.backdrop().present()) { return 0; }
        return tile.detailedGround() && tile.surface() != BoardScene.Surface.CONCRETE
              ? BoardGeometry.width() * .12f : BoardGeometry.width() * .02f;
    }

    /** Largest height a tile's own ground decoration reaches above its level: relief crowns, rim rocks and scatter. */
    static float decoration(BoardScene.Tile tile) {
        float scatter = 0;
        for (BoardScene.Feature feature : tile.features()) {
            if (feature.kind() == BoardScene.FeatureKind.SCATTER || feature.kind() == BoardScene.FeatureKind.BOULDER
                  || feature.kind() == BoardScene.FeatureKind.ROUGH) {
                scatter = Math.max(scatter, feature.height() * BoardGeometry.level());
            }
        }
        return headroom(tile) + scatter;
    }

    /** Largest horizontal reach of sculpted geometry beyond a hex's own footprint. */
    static float overhang() {
        return BoardGeometry.width() * .18f + stepRoom();
    }

    boolean sculpted() { return sculpted; }

    /**
     * How far the step on edge e reaches into this hex at its own level, in world units: the foot of a slope down to it
     * or the rim of a slope down from it, laid out in the step's room. A slope down to a water hex's bed meets the water
     * on the edge. A water hex keeps its waterline beyond it.
     */
    float reach(int e) {
        Edge edge = edge(e);
        if (edge.room <= 0) { return 0; }
        float z = self.level() * BoardGeometry.level();
        float offset = band(edge.upper, edge.lower, z, (edge.a.x + edge.b.x) * .5f, (edge.a.y + edge.b.y) * .5f);
        return Math.max(0, edge.lower == self ? offset : -offset);
    }

    /**
     * Conservative inset for a formation on this top, measured from the original hex edge to its innermost rim
     * sample. The rendered rim already includes river-shifted corners, slope room and cliff notches. Keeping every
     * footprint inside these six limits also keeps it inside the top's star-shaped outline. Dry scenery needs that
     * same boundary before the surface has emitted its rim triangles.
     */
    float topInset(int e) {
        if (rims[e] == null && (!sculpted || self.liquid())) { return reach(e); }
        List<Vector3> boundary = topOutline();
        Vector3 a = BoardGeometry.corner(tile.coords(), 0, e);
        Vector3 b = BoardGeometry.corner(tile.coords(), 0, e + 1);
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        float inset = 0;
        for (int i = outlineStarts[e]; i <= outlineStarts[e + 1]; i++) {
            Vector3 point = boundary.get(i % boundary.size());
            inset = Math.max(inset, (dx * (point.y - a.y) - dy * (point.x - a.x)) / length);
        }
        return inset;
    }

    /**
     * Whether the step on edge e is a slope with room up from this hex, not a wall: in a water hex it runs on under the
     * water to the bed, and the waterline hugs it.
     */
    boolean slope(int e) {
        Edge edge = edge(e);
        return edge.room > 0 && edge.lower == self && !wall(edge.upper, edge.lower);
    }

    /** The water side of a cliff that continues directly down to the bed. */
    boolean wetCliff(int e) {
        return wetCliff(neighbor(self, e), self) || waterfallFoot(e);
    }

    /** The receiving basin owns the submerged rock below a waterfall, as it does below a dry cliff. */
    boolean waterfallFoot(int e) {
        BoardScene.Tile upper = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(e)));
        return self.sculpted() && self.liquid() && upper != null && upper.liquid().connects(tile.liquid())
              && upper.elevation() > tile.elevation() && !upper.frozen() && !tile.frozen()
              && !BoardSurface.waterSlope(upper, tile);
    }

    /** Both water hexes sharing a mouth must agree when its end meets a cliff instead of a beach. */
    boolean wetCliffCorner(int k) {
        return wetCliffCorner(corner(self, k));
    }

    private static boolean wetCliffCorner(Corner corner) {
        boolean cliff = false;
        for (Site land : corner.around) {
            if (land == null || land.liquid()) { continue; }
            for (Site water : corner.around) {
                if (water == null || !water.liquid()) { continue; }
                if (!wetCliff(land, water)) { return false; }
                cliff = true;
            }
        }
        return cliff;
    }

    /** A cliff foot must not inherit the adjoining slope's wide corner apron at the water surface. */
    private static float wetFoot(Corner corner, float z) {
        if (!wetCliffCorner(corner)) { return 1; }
        float weight = 1;
        for (Site land : corner.around) {
            for (Site water : corner.around) {
                if (wetCliff(land, water)) {
                    weight = Math.min(weight, smooth(Math.abs(z - water.level() * BoardGeometry.level())
                          / (.5f * BoardGeometry.level())));
                }
            }
        }
        return weight;
    }

    /**
     * How far the steps through corner k move its line at this hex's level, in world units; over land, as far as the
     * outline there moves, roads' pushes with them. Water hexes that share an open mouth both compute it from the corner's
     * three hexes, so they place the mouth's ends alike.
     */
    float cornerReach(int k) {
        Corner corner = corner(self, k);
        float z = self.level() * BoardGeometry.level();
        if (waterAbove(corner)) {
            Edge edge = edge(k);
            Vector3 p = edgePoint(edge, edge.a == corner ? 0 : 1, z);
            return (float) Math.hypot(p.x - corner.x, p.y - corner.y);
        }
        float[] band = new float[2];
        bandOffset(corner, z, band);
        return (float) Math.hypot(band[0], band[1]);
    }

    /**
     * How far this hex's outline at its own level stands in from corner k toward the hex's centre, in world units: the
     * steps through the corner and its fillet move it. A water hex keeps its waterline beyond it.
     */
    float cornerInset(int k) {
        Corner corner = corner(self, k);
        float z = self.level() * BoardGeometry.level();
        float[] band = new float[2], fillet = new float[2];
        bandOffset(corner, z, band);
        filletOffset(corner, z, fillet);
        float ix = self.x() - corner.x, iy = self.y() - corner.y, length = (float) Math.hypot(ix, iy);
        return Math.max(0, ((band[0] + fillet[0]) * ix + (band[1] + fillet[1]) * iy) / length);
    }

    /**
     * Whether a hex is land of natural ground, whose corners the water's shore may move (see {@link Corner#move});
     * paving, special artwork and roads keep their corners.
     */
    /** Inputs to the long-range shore field; height and natural ground family do not change its pull. */
    static int shoreClass(BoardScene scene, BoardScene.Tile tile) {
        Site site = site(scene, tile, BoardSurface.ramps(scene, tile));
        return site.liquid() ? site.molten() ? 3 : 2 : site.sculpted() && shoreGround(site) ? 1 : 0;
    }

    private static boolean shoreGround(Site site) {
        return !site.liquid() && site.detailed() && site.family() != CONCRETE;
    }

    // ---- Shore field -----------------------------------------------------------------------------------------

    /**
     * The shore field at (x, y) of water, or of lava when {@code molten}, in world units: roughly how far the point
     * lies inside that liquid's banks, negative on land. Each hex centre within {@link #SHORE_REACH} pulls the field by
     * its {@link #shoreWeight}, the sum divided by its slope measures distance, the bank wanders a little, and every
     * hex keeps its own pond or islet round its centre. The field is the board's, whichever hex asks: the hexes that
     * reach a point follow from the point alone and are summed in the order of their coordinates, so every hex
     * evaluating a point gets the same value.
     */
    float shore(float x, float y, boolean molten) {
        float value = shoreBase(x, y, molten);
        if (molten) { return value; }
        float channel = river.field(x, y, value);
        if (channel != Float.NEGATIVE_INFINITY) { value = Math.min(value, channel); }
        float strip = river.strips(x, y);
        return strip == Float.POSITIVE_INFINITY ? value : smoothMin(value, strip, SHORE_BLEND * BoardGeometry.hexScale());
    }

    /**
     * The smoothed field of a step from {@code low} to {@code high} at (x, y) ({@link StepOutline#SMOOTH}): the shore's
     * kernel over the hex centres around, +1 for hexes at or above the step's top, -1 at or below its foot, normalised.
     * As the shore's, a hex pulls harder by {@link #SHORE_NARROW} for each neighbour on the other side, so a ridge or a
     * valley a hex wide keeps its width. Its zero is the step's smooth outline. The field is the board's, whichever hex
     * asks.
     */
    private float stepField(float x, float y, int low, int high) {
        float scale = BoardGeometry.hexScale(), r2 = square(SHORE_REACH * scale), field = 0, sum = 0;
        float width = BoardGeometry.width(), height = BoardGeometry.height(), step = .75f * width;
        float[] pull = new float[2];
        int column = Math.round((x - width / 2) / step);
        for (int cx = column - 2; cx <= column + 2; cx++) {
            float dx = x - (cx * step + width / 2);
            int row = Math.round((-y - height / 2 - (cx & 1) * height / 2) / height);
            for (int cy = row - 2; cy <= row + 2; cy++) {
                float dy = y + cy * height + (cx & 1) * height / 2 + height / 2, t = 1 - (dx * dx + dy * dy) / r2;
                if (t <= 0) { continue; }
                stepPull(cx, cy, low, high, pull);
                field += t * t * t * pull[0];
                sum += t * t * t * pull[1];
            }
        }
        return sum == 0 ? 0 : field / sum;
    }

    /**
     * The pull of the hex at column cx and row cy on the field of the step from {@code low} to {@code high}, into
     * {@code out} as {its side times its weight, its weight}: harder by {@link #SHORE_NARROW} for each neighbour on the
     * other side. Kept for the hexes within {@link #SHORE_WINDOW} of this one.
     */
    private void stepPull(int cx, int cy, int low, int high, float[] out) {
        int size = 2 * SHORE_WINDOW + 1;
        int ix = cx - tile.coords().getX() + SHORE_WINDOW, iy = cy - tile.coords().getY() + SHORE_WINDOW;
        int index = ix >= 0 && iy >= 0 && ix < size && iy < size ? 2 * (ix * size + iy) : -1;
        float[] pulls = index < 0 ? null : stepPulls.computeIfAbsent((long) low << 32 | high & 0xffffffffL, key -> {
            float[] values = new float[2 * size * size];
            Arrays.fill(values, Float.NaN);
            return values;
        });
        if (pulls != null && !Float.isNaN(pulls[index])) {
            out[0] = pulls[index];
            out[1] = pulls[index + 1];
            return;
        }
        Coords at = new Coords(cx, cy);
        int side = side(boardSite(at).level(), low, high), other = 0;
        for (int d = 0; d < 6 && side != 0; d++) {
            other += side(boardSite(at.translated(d)).level(), low, high) == -side ? 1 : 0;
        }
        float weight = 1 + SHORE_NARROW * other;
        out[0] = side * weight;
        out[1] = weight;
        if (pulls != null) {
            pulls[index] = out[0];
            pulls[index + 1] = out[1];
        }
    }

    /**
     * A rounded corner's offset along its bisector at u (0 to 1) of its round's length L from the corner, in units of
     * L: where the circle tangent to both edges at L meets the line from the edge's point along the bisector. c is the
     * cosine of half the angle between the edges; at the lattice's 120 degrees (c = .5) the bulge is 2 - sqrt 3. Zero
     * for a straight run.
     */
    private static float arc(float u, float c) {
        if (c < .02f) { return 0; }
        float s = (float) Math.sqrt(1 - c * c);
        return (1 - s * (float) Math.sqrt(1 - u * u * c * c)) / c - u * c;
    }

    /** An arc's share of its bulge at u of its round's length (see {@link #arc}). */
    private static float share(float u, float c) {
        float bulge = arc(0, c);
        return bulge <= 0 ? 0 : arc(u, c) / bulge;
    }

    /**
     * How fast an arc's offset changes along its edge at the corner, per unit of its bulge and of the edge's own
     * length: c per unit of the moved edge, which runs {@code stretch} times the edge's length; the arc runs
     * {@code length} along it.
     */
    private static float arcSlope(float c, float length, float stretch) {
        float bulge = length * arc(0, c);
        return bulge <= 0 ? 0 : c * stretch / bulge;
    }

    /** An edge's length as the corners' moves place its ends. */
    private static float movedLength(Edge edge) {
        float[] ma = edge.a.move(), mb = edge.b.move();
        return (float) Math.hypot(edge.b.x + mb[0] - edge.a.x - ma[0], edge.b.y + mb[1] - edge.a.y - ma[1]);
    }

    /** A level's side of a step from {@code low} to {@code high}: 1 at or above its top, -1 at or below its foot. */
    private static int side(int level, int low, int high) { return level >= high ? 1 : level <= low ? -1 : 0; }

    private float shoreBase(float x, float y, boolean molten) {
        float scale = BoardGeometry.hexScale(), r2 = square(SHORE_REACH * scale), f = 0, gx = 0, gy = 0;
        float wet = Float.POSITIVE_INFINITY, dry = Float.POSITIVE_INFINITY;
        float width = BoardGeometry.width(), height = BoardGeometry.height(), step = .75f * width;
        int column = Math.round((x - width / 2) / step);
        for (int cx = column - 2; cx <= column + 2; cx++) {
            float dx = x - (cx * step + width / 2);
            int row = Math.round((-y - height / 2 - (cx & 1) * height / 2) / height);
            for (int cy = row - 2; cy <= row + 2; cy++) {
                float dy = y + cy * height + (cx & 1) * height / 2 + height / 2, d2 = dx * dx + dy * dy;
                float weight = shoreWeight(cx, cy, molten);
                if (weight > 0) {
                    wet = Math.min(wet, d2);
                } else {
                    dry = Math.min(dry, d2);
                }
                float t = 1 - d2 / r2;
                if (t <= 0) { continue; }
                f += weight * t * t * t;
                gx += weight * t * t * dx;
                gy += weight * t * t * dy;
            }
        }
        float slope = 6 / r2 * (float) Math.hypot(gx, gy);
        float wander = Math.clamp(gradient(x / (WANDER_CELL * scale) + 3.1f, y / (WANDER_CELL * scale) - 1.7f), -1, 1);
        float value = f / Math.max(slope, 1e-4f / scale) + (SHORE_SPREAD + SHORE_WANDER * wander) * scale;
        value = -smoothMin(-value, (float) Math.sqrt(wet) - SHORE_POOL * scale, SHORE_BLEND * scale);
        return smoothMin(value, (float) Math.sqrt(dry) - SHORE_ISLE * scale, SHORE_BLEND * scale);
    }

    /**
     * How far from (x, y) along (ux, uy) this liquid's shore field turns to land, in world units, negative where it
     * turns before the point: within {@link #SHORE_SHIFT} either way. Search from this bank's corner, since the far
     * end of the ray can already lie on the opposite bank of a narrow channel, then bisect the local crossing.
     */
    private float shoreReach(float x, float y, float ux, float uy, boolean molten) {
        // Narrowing a stream changes the beach inside its existing banks, not the cliff and land topology.
        float span = SHORE_SHIFT * BoardGeometry.hexScale(), lo = 0, hi = Float.NaN, previous = 0;
        boolean wet = shoreBase(x, y, molten) > 0;
        float direction = wet ? 1 : -1;
        for (int i = 1; i <= 4 && Float.isNaN(hi); i++) {
            float s = direction * span * i / 4;
            if ((shoreBase(x + ux * s, y + uy * s, molten) > 0) != wet) {
                lo = Math.min(previous, s);
                hi = Math.max(previous, s);
            }
            previous = s;
        }
        if (Float.isNaN(hi)) { return direction * span; }
        for (int i = 0; i < REACH_HALVINGS; i++) {
            float s = (lo + hi) / 2;
            if (shoreBase(x + ux * s, y + uy * s, molten) > 0) {
                lo = s;
            } else {
                hi = s;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * The pull of the hex at column cx and row cy on the shore field of water, or of lava when {@code molten}: toward
     * that liquid for its own hexes, away from it for land, {@link #SHORE_HARD} times as hard for land whose corners
     * stay put, and harder by {@link #SHORE_NARROW} the more of its neighbours are of the other kind. Beyond the
     * board's edge a hex pulls as the nearest board hex does, so a river runs straight on into the edge.
     */
    private float shoreWeight(int cx, int cy, boolean molten) {
        int size = 2 * SHORE_WINDOW + 1;
        int ix = cx - tile.coords().getX() + SHORE_WINDOW, iy = cy - tile.coords().getY() + SHORE_WINDOW;
        int index = ix >= 0 && iy >= 0 && ix < size && iy < size ? ix * size + iy : -1;
        float[] weights = molten ? moltenShoreWeights : shoreWeights;
        if (index >= 0 && weights == null) {
            weights = new float[size * size];
            Arrays.fill(weights, Float.NaN);
            if (molten) { moltenShoreWeights = weights; } else { shoreWeights = weights; }
        }
        if (index >= 0 && !Float.isNaN(weights[index])) { return weights[index]; }
        Coords coords = new Coords(cx, cy);
        Site site = boardSite(coords);
        boolean wet = wet(site, molten);
        int other = 0;
        for (int direction = 0; direction < 6; direction++) {
            if (wet(boardSite(coords.translated(direction)), molten) != wet) { other++; }
        }
        float pull = wet ? 1 : site.sculpted() && shoreGround(site) ? -1 : -SHORE_HARD;
        // A broad body of water fills out its banks; a one-hex stream keeps its existing pull. Interior water
        // contributes gradually through the same shared field, so the shore bows across several hexes.
        if (wet && !molten) { pull += Math.max(0, 3 - other) / 3f; }
        float weight = pull * (1 + SHORE_NARROW * other / 6);
        if (index >= 0) { weights[index] = weight; }
        return weight;
    }

    /**
     * How much of the moves the water's shore wants of a land hex's corners the hex allows, from 0 to 1: all of them,
     * unless together they would take more than 1 - {@link #LAND_KEEP} of the hex, when each is cut back alike. A unit
     * of move toward the hex's centre takes about half the hex's height of its area, one along the edge between it and
     * another land hex half that.
     */
    private float keep(Site land) {
        Float known = keeps.get(land.coords());
        if (known != null) { return known; }
        float loss = 0, height = BoardGeometry.height();
        for (int k = 0; k < 6; k++) {
            Corner corner = corner(land, k);
            if (corner.want <= 0) { continue; }
            int lands = 0;
            for (Site site : corner.around) { lands += site.liquid() ? 0 : 1; }
            loss += corner.want * (lands == 1 ? height / 2 : height / 4);
        }
        float budget = (1 - LAND_KEEP) * .75f * BoardGeometry.width() * height;
        float result = loss > budget ? budget / loss : 1;
        keeps.put(land.coords(), result);
        return result;
    }

    /** The site at these coordinates, or beyond the board's edge the nearest board hex's. */
    private Site boardSite(Coords coords) {
        return site(new Coords(Math.clamp(coords.getX(), 0, scene.width() - 1),
              Math.clamp(coords.getY(), 0, scene.height() - 1)));
    }

    /** Whether a hex holds water, or lava when {@code molten}; both now share sculpted banks. */
    private static boolean wet(Site site, boolean molten) {
        return site.liquid() && site.molten() == molten;
    }

    /**
     * The seam of edge e at this hex's level, the fraction t along it from corner k, one of its ends: the outline the
     * relief lays there, which both hexes of the edge share.
     */
    Vector3 seam(int e, int k, float t) {
        Edge edge = edge(e);
        return edgePoint(edge, edge.a == corner(self, k) ? t : 1 - t, self.level() * BoardGeometry.level());
    }

    /** A water-side sample on the actual triangles of the dry cliff above its foot. */
    Vector3 wetCliffContact(int e, float t, float z) {
        Edge edge = edge(e);
        // A level quay has only the water hex's submerged wall, with no dry cliff grid above it.
        if (edge.upper == null) { return seam(e, e, t); }
        int count = samples(edge);
        float along = t * count;
        int column = Math.min((int) along, count - 1);
        float u = along - column;
        float[] heights = rows(edge.bottom(), edge.top());
        z = Math.clamp(z, edge.bottom(), edge.top());
        int row = 1;
        while (row < heights.length - 1 && heights[row] < z) { row++; }
        float v = (z - heights[row - 1]) / (heights[row] - heights[row - 1]);
        Vector3 low = edgePoint(edge, canonical(e, column, count), heights[row - 1]);
        Vector3 high = edgePoint(edge, canonical(e, column + 1, count), heights[row]);
        // The upper land builds the opposite edge: its quad diagonal runs from this lower-left to upper-right.
        Vector3 middle = v < u
              ? edgePoint(edge, canonical(e, column + 1, count), heights[row - 1])
              : edgePoint(edge, canonical(e, column, count), heights[row]);
        return low.scl(1 - Math.max(u, v)).mulAdd(middle, Math.abs(u - v)).mulAdd(high, Math.min(u, v));
    }

    /** How far the shore moves this hex's corner k, in world units (x, y): see {@link Corner#move}. */
    float[] shoreShift(int k) {
        return corner(self, k).move().clone();
    }

    /** The surface family this hex's sculpted ground takes, as a {@link BoardScene.Surface} ordinal. */
    int family() { return self.family(); }

    Shade shade(Vector3 vertex) { return shades.get(vertex); }

    /** A vertex cut inside a face takes the face's shading at its barycentric weights. */
    void interpolateShade(Vector3 a, Vector3 b, Vector3 c, float wa, float wb, float wc, Vector3 p) {
        Shade sa = shades.get(a), sb = shades.get(b), sc = shades.get(c);
        if (sa == null || sb == null || sc == null) { return; }
        Vector3 normal = new Vector3(sa.normal()).scl(wa).mulAdd(sb.normal(), wb).mulAdd(sc.normal(), wc).nor();
        float projection = Float.isNaN(sa.projection()) ? Float.NaN : sa.projection();
        shades.put(p, new Shade(normal, sa.kind(), sa.occlusion() * wa + sb.occlusion() * wb + sc.occlusion() * wc,
              sa.level() * wa + sb.level() * wb + sc.level() * wc, sa.rim() * wa + sb.rim() * wb + sc.rim() * wc,
              sa.foot() * wa + sb.foot() * wb + sc.foot() * wc, sa.tint() * wa + sb.tint() * wb + sc.tint() * wc,
              projection));
    }

    /**
     * A vertex a road's bank moved keeps its source's shading, with the bank's normal. Its distances below the rim and
     * above the foot follow it: a crest cut down to the road shades as the foot it now is, not as a lip.
     */
    void reshade(Vector3 from, Vector3 to, Vector3 normal) {
        Shade shade = shades.get(from);
        if (shade == null) { return; }
        float drop = (from.z - to.z) / metres(1);
        shades.put(to, new Shade(normal == null ? shade.normal() : normal, shade.kind(), shade.occlusion(), shade.level(),
              Math.max(0, shade.rim() + drop), Math.max(0, shade.foot() - drop), shade.tint(), shade.projection()));
    }

    /** A reserved road's surface: ground shading with the road's own normal. */
    void roadShade(Vector3 p, Vector3 normal) {
        Shade ground = groundShade(p);
        shades.put(p, new Shade(normal, Kind.GROUND, ground.occlusion(), ground.level(), ground.rim(), ground.foot(),
              ground.tint()));
    }

    /** Clipped portal vertices retain the native wall's normals and material blend. */
    void interpolateShade(Vector3 a, Vector3 b, float t, Vector3 p) {
        Shade sa = shades.get(a), sb = shades.get(b);
        if (sa == null || sb == null) { return; }
        shades.put(p, new Shade(new Vector3(sa.normal()).lerp(sb.normal(), t).nor(), sa.kind(),
              lerp(sa.occlusion(), sb.occlusion(), t), lerp(sa.level(), sb.level(), t),
              lerp(sa.rim(), sb.rim(), t), lerp(sa.foot(), sb.foot(), t), lerp(sa.tint(), sb.tint(), t),
              lerp(sa.projection(), sb.projection(), t)));
    }

    // ---- Board context ---------------------------------------------------------------------------------------

    private Site site(Coords coords) {
        if (coords == null) { return null; }
        Site known = sites.get(coords);
        if (known != null || sites.containsKey(coords)) { return known; }
        BoardScene.Tile other = scene.tile(coords);
        Site result = other == null ? null : site(scene, other, BoardSurface.ramps(scene, other));
        sites.put(coords, result);
        return result;
    }

    private Site neighbor(Site site, int edge) {
        if (site == null) { return null; }
        return site(site.coords().translated(BoardGeometry.edgeDirection(edge)));
    }

    /**
     * Hexes whose surfaces continue into each other without a cliff, bank or special artwork seam. A water hex's banks
     * are its own, so it joins no neighbour.
     */
    private static boolean joined(Site a, Site b) {
        return a != null && b != null && a.detailed() && b.detailed() && a.level() == b.level() && !a.liquid()
              && !b.liquid() && !a.road() && !b.road() && (a.family() == CONCRETE) == (b.family() == CONCRETE);
    }

    private List<Site> candidates() {
        if (candidates == null) {
            candidates = new ArrayList<>(7);
            candidates.add(self);
            for (int e = 0; e < 6; e++) {
                Site site = neighbor(self, e);
                if (site != null) { candidates.add(site); }
            }
        }
        return candidates;
    }

    // ---- Canonical corners -----------------------------------------------------------------------------------

    /** The three hexes around one lattice corner, in canonical order; every hex that shares it derives the same. */
    private final class Corner {
        final long key;
        final float x;
        final float y;
        final Site[] around;
        final boolean pinned;
        final int low;
        final int mid;
        final int high;
        final float variation;
        final Map<Integer, float[]> offsets = new HashMap<>();
        /** Transition band offsets per height; see {@link #bandOffset(Corner, float)}. */
        final Map<Integer, float[]> bands = new HashMap<>();
        /**
         * Whether hex transitions or padding are on and the adjoining hexes are natural ground, so the steps through this
         * corner take transition room and its relief leaves the apron to the band.
         */
        final boolean banded;
        /** Length of the corner's fillet along each edge, in world units; zero where the corner stays sharp. */
        final float fillet;
        /** Whether its round is a circle's arc ({@link StepOutline#CORNERS}, see {@link #arcFrame}), not the cubic. */
        final boolean arc;
        /** The arc as the moved step edges meet; see {@link #arcFrame}. Null until asked. */
        private float[] frame;
        /** The far ends of its two step edges, once {@link #arcFrame} has found them. */
        private Corner[] arcEnds;
        /** {@link #arcLength} per height. */
        private final Map<Integer, Float> arcLengths = new HashMap<>();
        /** A free waterfall end shares its rock corner with the adjoining dry cliff. */
        final boolean waterfall;
        /** A bank above a gentle liquid step keeps its mouth fixed through this height; NaN elsewhere. */
        final float gradedContact;
        /**
         * Fillet displacements (a sixth of the fillet length) toward the hex on the other side of the outline: the
         * lone high hex of a convex corner or the lone low hex of a concave one ({@code odd}); on a three-level corner
         * toward the high, middle and low hex for the upper span, the middle hex's own level and the lower span. An
         * arc's comes from its {@link #arcFrame}.
         */
        final float[] odd = new float[2];
        final float[] towardHigh = new float[2];
        final float[] towardMid = new float[2];
        final float[] towardLow = new float[2];
        /**
         * Where water meets land of natural ground at or above its level, the way away from the water, as a unit
         * vector: toward the land's centre between two water hexes, out along the edge between two land hexes past a
         * water hex's corner. Zero elsewhere.
         */
        private final float[] away = new float[2];
        /**
         * How far along {@link #away} the water's shore would move the corner, in world units, at every height of the
         * steps there, so the slopes and walls round the water follow its shore too: to where the shore field turns to
         * land, with room for the water's bank beyond where land lies at the water's level, at most
         * {@link #SHORE_SHIFT} either way, and at one level never toward the water. A step up from the water keeps its
         * own room: its foot reaches on into the water, which follows it there.
         */
        private float want;
        /** The move itself, once every land hex round the corner has kept its share; see {@link #move}. */
        private float[] move;

        Corner(int ix, int iy, Site[] around) {
            key = ((long) ix << 32) ^ (iy & 0xffffffffL);
            x = cornerX(ix);
            y = cornerY(iy);
            this.around = around;
            ConcreteFit.Shift constructed = coast.shift(key);
            if (constructed.x() != 0 || constructed.y() != 0) { move = new float[] { constructed.x(), constructed.y() }; }
            variation = hash(ix * 7 + 3, iy * 13 - 5);
            boolean anyPinned = false;
            // A pit's flat cap owns this corner. Every adjoining rim and wall must share that same vertical line,
            // including the ordinary slope between two neighbours of different heights.
            for (Site site : around) { anyPinned |= site == null || !site.sculpted() || site.ultraSublevel(); }
            boolean waterStep = false, fallingWater = false, slopingWater = false, naturalBank = false;
            int waterTop = Integer.MIN_VALUE, bankTop = Integer.MIN_VALUE;
            for (int i = 0; i < 3; i++) {
                Site p = around[i], q = around[(i + 1) % 3];
                boolean step = p != null && q != null && p.liquid() && q.liquid() && p.level() != q.level();
                waterStep |= step;
                if (step) {
                    BoardScene.Tile a = scene.tile(p.coords()), b = scene.tile(q.coords());
                    fallingWater |= !a.frozen() && !b.frozen() && !BoardSurface.waterSlope(a, b);
                    slopingWater |= BoardSurface.waterSlope(a, b);
                }
                boolean bank = p != null && !p.liquid() && p.detailed() && !p.road() && p.family() != CONCRETE;
                naturalBank |= bank;
                if (bank) { bankTop = Math.max(bankTop, p.level()); }
                if (p != null && p.liquid()) { waterTop = Math.max(waterTop, p.level()); }
            }
            waterfall = fallingWater && naturalBank && !anyPinned;
            gradedContact = slopingWater && bankTop > waterTop && !anyPinned
                  ? waterTop * BoardGeometry.level() : Float.NaN;
            // Joined curtains keep their shared crest. Free ends can round into dry rock between contact levels.
            anyPinned |= waterStep && !waterfall && !Float.isFinite(gradedContact);
            pinned = anyPinned;
            boolean natural = BoardGeometry.tuning().stepsBetweenTops();
            for (Site site : around) {
                if (site != null) { natural &= site.sculpted() && site.detailed() && !site.fixedRoad() && !site.ultraSublevel(); }
            }
            // At a dry board edge the slope and the cut face share one corner profile, kept on the board's outline
            // (boardBandOffset); beside a road the cut stays on the lattice, where the road leaving the board ends.
            banded = natural && (!pinned || around[1] != null && around[2] == null
                  && !around[0].liquid() && !around[1].liquid() && !around[0].road() && !around[1].road());
            if (pinned) {
                low = banded ? Math.min(around[0].level(), around[1].level()) : 0;
                high = banded ? Math.max(around[0].level(), around[1].level()) : 0;
                mid = low;
                fillet = 0;
                arc = false;
                return;
            }
            int a = around[0].level(), b = around[1].level(), c = around[2].level();
            low = Math.min(a, Math.min(b, c));
            high = Math.max(a, Math.max(b, c));
            mid = a + b + c - low - high;
            float round = 0;
            for (Site site : around) {
                if (!site.liquid() && site.family() == CONCRETE) { round = 0; break; }
                round += ROCKS[site.family()].round();
            }
            // A rounded two-level corner runs a circle's arc over half of each edge. Water's banks keep their own
            // shape, which its shore rounds, roads their reserved ground, and three-level corners their turn.
            StepOutline outline = tuning().stepOutline();
            boolean kept = mid != low && mid != high;
            for (Site site : around) { kept |= site.liquid() || site.road(); }
            arc = outline != StepOutline.HEX && banded && round > 0 && !kept;
            fillet = low == high ? 0 : (arc ? .5f : Math.min(.5f, round / 3 * (.8f + .4f * variation)))
                  * BoardGeometry.width() / 2;
            for (Site site : around) {
                float dx = site.x() - x, dy = site.y() - y;
                float scale = fillet / 6 / (float) Math.hypot(dx, dy);
                float[] target = site.level() == high && mid != high ? towardHigh
                      : site.level() == low && mid != low ? towardLow : towardMid;
                target[0] = dx * scale;
                target[1] = dy * scale;
            }
            float[] lone = mid == low ? towardHigh : towardLow;
            odd[0] = lone[0];
            odd[1] = lone[1];
            if (outline == StepOutline.SMOOTH) { smoothStep(); }
            wantShore();
        }

        /**
         * A smooth step's corner ({@link StepOutline#SMOOTH}): a natural slope of one or two levels moves it out from
         * its hill, or in toward it, to where {@link #stepField} turns from the hill to the ground below, but no nearer
         * the lone hex's centre than leaves that hex {@link #STEP_KEEP} of its top beyond the step's band and round at
         * its level, and away from it no more than a third of an edge. Water's corners follow its shore instead;
         * concrete, roads, cliffs and three-level corners keep their places.
         */
        private void smoothStep() {
            if (move != null || waterfall || !banded || low == high || mid != low && mid != high) { return; }
            for (int i = 0; i < 3; i++) {
                Site site = around[i], next = around[(i + 1) % 3];
                if (site.liquid() || site.road() || site.family() == CONCRETE
                      || site.level() > next.level() && wall(site, next) || next.level() > site.level() && wall(next, site)) {
                    return;
                }
            }
            Site lone = null;
            int highs = 0;
            for (Site site : around) { highs += site.level() == high ? 1 : 0; }
            for (Site site : around) {
                if (highs == 1 ? site.level() == high : site.level() == low) { lone = site; }
            }
            // Outward from the hill: from its lone hex through a convex corner, toward the lone lower hex of a concave
            // one.
            float ux = highs == 1 ? x - lone.x() : lone.x() - x, uy = highs == 1 ? y - lone.y() : lone.y() - y;
            float length = (float) Math.hypot(ux, uy);
            ux /= length;
            uy /= length;
            // Toward the lone hex its top at its level ends where the step's band and round reach into it from this
            // corner. Away from it the corner slides along the seam between the other two hexes, at most a third of
            // it, so the corners at the seam's two ends never meet.
            float[] band = new float[2];
            bandOffset(this, lone.level() * BoardGeometry.level(), band);
            float tx = (lone.x() - x) / length, ty = (lone.y() - y) / length;
            float rim = Math.max(0, band[0] * tx + band[1] * ty) + fillet * (2 - (float) Math.sqrt(3));
            float keep = STEP_KEEP * BoardGeometry.hexScale();
            boolean hill = stepField(x, y, low, high) > 0;
            float direction = hill ? 1 : -1, toward = highs == 1 ? -1 : 1;
            float span = direction == toward ? Math.max(0, length - keep - rim) : length / 3;
            float lo = 0, hi = Float.NaN, previous = 0, reach = direction * span;
            for (int i = 1; i <= 6 && Float.isNaN(hi); i++) {
                float s = direction * span * i / 6;
                if ((stepField(x + ux * s, y + uy * s, low, high) > 0) != hill) {
                    lo = Math.min(previous, s);
                    hi = Math.max(previous, s);
                }
                previous = s;
            }
            if (!Float.isNaN(hi)) {
                for (int i = 0; i < REACH_HALVINGS; i++) {
                    float s = (lo + hi) / 2;
                    if (stepField(x + ux * s, y + uy * s, low, high) > 0) { lo = s; } else { hi = s; }
                }
                reach = (lo + hi) / 2;
            }
            move = new float[] { ux * reach, uy * reach };
        }

        /**
         * A rounded corner's arc where its two step edges meet as the corners move them: {bisector x, y, c, length},
         * the bisector of their directions pointing into the lone hex's side, c the cosine of half the angle between
         * them, and the length its round runs along each, at most half the shorter edge (at each height, see
         * {@link #arcLength}). Unmoved, the corner is the lattice's 120 degrees (c = .5); where the moves straighten a
         * run (c near 0) little is left to round.
         */
        float[] arcFrame() {
            if (frame != null) { return frame; }
            int highs = 0;
            for (Site site : around) { highs += site.level() == high ? 1 : 0; }
            Site lone = null;
            for (Site site : around) {
                if (highs == 1 ? site.level() == high : site.level() == low) { lone = site; }
            }
            int k = 0;
            while (corner(lone, k) != this) { k++; }
            float[] own = move();
            float sx = 0, sy = 0, ex = 0, ey = 0, shortest = Float.POSITIVE_INFINITY;
            // The lone hex's corners either side of this one end its two step edges.
            arcEnds = new Corner[] { corner(lone, k - 1), corner(lone, k + 1) };
            for (Corner far : arcEnds) {
                float[] moved = far.move();
                float dx = far.x + moved[0] - x - own[0], dy = far.y + moved[1] - y - own[1];
                float length = (float) Math.hypot(dx, dy);
                ex = dx / length;
                ey = dy / length;
                sx += ex;
                sy += ey;
                shortest = Math.min(shortest, length);
            }
            float bisector = (float) Math.hypot(sx, sy);
            frame = bisector < 1e-4f ? new float[4] : new float[] { sx / bisector, sy / bisector,
                  (ex * sx + ey * sy) / bisector, Math.min(fillet, shortest / 2) };
            return frame;
        }

        /**
         * How far the arc runs along each of its step edges at height z: the frame's length, but at most half of either
         * edge as the moves and the band's slide carry it there (BoardRelief#naturalPoint), so the arcs at an edge's
         * two ends never meet and every edge through a corner starts exactly at it.
         */
        float arcLength(float z) {
            float length = arcFrame()[3];
            if (length <= 0 || !BoardGeometry.tuning().stepsBetweenTops()) { return length; }
            Float known = arcLengths.get(Float.floatToIntBits(z));
            if (known != null) { return known; }
            float[] own = move(), near = new float[2], far = new float[2];
            bandOffset(this, z, near);
            for (Corner end : arcEnds) {
                float[] moved = end.move();
                float dx = end.x + moved[0] - x - own[0], dy = end.y + moved[1] - y - own[1];
                float run = (float) Math.hypot(dx, dy);
                bandOffset(end, z, far);
                float speed = run + ((far[0] - near[0]) * dx + (far[1] - near[1]) * dy) / run;
                length = Math.min(length, Math.max(0, speed / 2));
            }
            arcLengths.put(Float.floatToIntBits(z), length);
            return length;
        }

        /** Sets {@link #away} and {@link #want}. */
        private void wantShore() {
            if (move != null || waterfall || Float.isFinite(gradedContact)) { return; }
            Site water = null, dry = null;
            int wet = 0;
            boolean flat = false;
            for (Site site : around) {
                if (site.liquid()) {
                    wet++;
                    water = site;
                } else {
                    dry = site;
                }
            }
            if (wet == 0 || wet == 3) { return; }
            for (Site site : around) {
                if (site.liquid()) { continue; }
                if (!shoreGround(site) || site.level() < water.level()) { return; }
                flat |= site.level() == water.level();
            }
            float dx = wet == 2 ? dry.x() - x : x - water.x(), dy = wet == 2 ? dry.y() - y : y - water.y();
            float length = (float) Math.hypot(dx, dy), span = SHORE_SHIFT * BoardGeometry.hexScale();
            away[0] = dx / length;
            away[1] = dy / length;
            float reach = shoreReach(x, y, away[0], away[1], water.molten())
                  + (flat ? SHORE_ROOM * BoardGeometry.hexScale() : 0);
            float most = span;
            if (wet == 2) {
                // A land's tip moves no nearer its centre than leaves its top, inside the steps' rim, its islet.
                float z = dry.level() * BoardGeometry.level();
                float[] band = new float[2], rounding = new float[2];
                bandOffset(this, z, band);
                filletOffset(this, z, rounding);
                float rim = (band[0] + rounding[0]) * away[0] + (band[1] + rounding[1]) * away[1];
                most = Math.clamp(length - rim - SHORE_ISLE * BoardGeometry.hexScale(), 0, span);
            }
            want = Math.clamp(reach, low == high ? 0 : -span, most);
        }

        /**
         * How far the shore moves this corner, in world units (x, y): {@link #want} along {@link #away}, but into land
         * no further than every land hex round it allows (see {@link #keep}).
         */
        float[] move() {
            if (move == null) {
                float length = want;
                for (Site site : around) {
                    if (length > 0 && !site.liquid()) { length = Math.min(length, want * keep(site)); }
                }
                move = new float[] { away[0] * length, away[1] * length };
            }
            return move;
        }

        boolean seamless() {
            return !pinned && BoardRelief.joined(around[0], around[1]) && BoardRelief.joined(around[1], around[2])
                  && BoardRelief.joined(around[0], around[2]);
        }

        /** The cliff span containing z: [from, to] levels, with a pin at an intermediate level of a three-way corner. */
        int[] span(float z) {
            float level = BoardGeometry.level();
            if (mid == low || mid == high) { return new int[] { low, high, 0 }; }
            return z >= mid * level ? new int[] { mid, high, 1 } : new int[] { low, mid, 2 };
        }

        /** Keep the existing mouths and ground contacts while rounding the rock between them. */
        float wallWeight(float z) {
            // A descending mouth needs the whole lower corner, not a pinned column up the exposed cliff.
            // The dry rim follows this same rounded corner; pinning it again would leave a pointed roof.
            if (Float.isFinite(gradedContact)) { return smooth((z - gradedContact) / metres(1.5f)); }
            if (!waterfall) { return 1; }
            float distance = Float.POSITIVE_INFINITY;
            for (Site site : around) {
                if (site != null) { distance = Math.min(distance, Math.abs(z - site.level() * BoardGeometry.level())); }
            }
            return smooth(distance / metres(1.5f));
        }
    }

    private Corner corner(Site site, int index) {
        int k = Math.floorMod(index, 6);
        int ix = site.ix() + CORNER_DX[k], iy = site.iy() + CORNER_DY[k];
        long key = ((long) ix << 32) ^ (iy & 0xffffffffL);
        Corner existing = corners.get(key);
        if (existing != null) { return existing; }
        Site[] around = { site, neighbor(site, k - 1), neighbor(site, k) };
        Arrays.sort(around, BoardRelief::compareSites);
        Corner result = new Corner(ix, iy, around);
        corners.put(key, result);
        return result;
    }

    private static int compareSites(Site p, Site q) {
        return p == null ? (q == null ? 0 : 1) : q == null ? -1
              : p.coords().getX() != q.coords().getX() ? Integer.compare(p.coords().getX(), q.coords().getX())
              : Integer.compare(p.coords().getY(), q.coords().getY());
    }

    /** Relief displacement of the vertical line through a corner, at height z (cached per height). */
    private void cornerOffset(Corner corner, float z, float[] out) {
        if (corner.pinned || corner.low == corner.high) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        float[] cached = corner.offsets.computeIfAbsent(Float.floatToIntBits(z), key -> computeCornerOffset(corner, z));
        float weight = corner.wallWeight(z);
        out[0] = cached[0] * weight;
        out[1] = cached[1] * weight;
    }

    private float[] computeCornerOffset(Corner corner, float z) {
        float level = BoardGeometry.level();
        int[] span = corner.span(z);
        int from = span[0], to = span[1];
        float bottom = from * level, top = to * level;
        if (z < bottom - EPSILON || z > top + EPSILON) { return new float[2]; }
        float[] direction = cornerDirection(corner, z);
        if (direction == null) { return new float[2]; }
        float pin = (span[2] == 1 ? smooth((z - bottom) / (.2f * level)) : 1)
              * (span[2] == 2 ? smooth((top - z) / (.2f * level)) : 1);
        // Water, concrete and pit caps keep their outlines at their own level, as along the edges (see Edge).
        boolean footPinned = false, rimPinned = false;
        float drop = 0;
        for (Site site : corner.around) {
            boolean fixed = site.fixedOutline();
            footPinned |= fixed && site.level() == from;
            rimPinned |= fixed && !(site.liquid() && waterAbove(corner)) && site.level() == to;
            for (Site other : corner.around) {
                if (site.level() == to && other.level() == from) { drop = Math.max(drop, drop(site, other)); }
            }
        }
        // Where the band moves the corner line, its relief is that of the place it stands.
        float[] band = new float[2];
        bandOffset(corner, z, band);
        // The corner's relief is the mean of its solid hexes' own, so walls of different grounds meet there.
        float d = 0, own = 0;
        int solids = 0, family = -1;
        for (Site site : corner.around) {
            if (site.level() < to) { continue; }
            if (site.family() != family) {
                family = site.family();
                own = profile(corner.x + band[0], corner.y + band[1], z, bottom, top, drop, family, footPinned,
                      rimPinned, corner.banded);
            }
            d += own;
            solids++;
        }
        d *= pin / solids;
        // Two tops joined beside a lower hex end in this corner: its relief slides it along their shared edge (see
        // cornerDirection), and however the rock falls the neck keeps the middle of that edge (NECK_END).
        if (solids == 2) {
            float[] fillet = new float[2];
            filletOffset(corner, z, fillet);
            float moved = (band[0] + fillet[0]) * direction[0] + (band[1] + fillet[1]) * direction[1];
            d = Math.max(d, -NECK_END * BoardGeometry.width() / 2 - moved);
        }
        // The bank must support the free end of a fall. Keep its rounded rock masses, but do not excavate the
        // shared shoulder from underneath the mouth; the adjoining faces retain their own recesses.
        if (corner.waterfall) { d = Math.max(0, d); }
        return new float[] { direction[0] * d, direction[1] * d };
    }

    /**
     * The transition band of a corner's vertical line at height z. Every seam through the corner asks for its own band
     * along its normal (a seam without one, for its line to stay), and the offset meets those requests in the weighted
     * least-squares sense. A seam asks throughout its span and strongly near its ends, where it bounds a hex's top, and
     * that strong request fades within a third of a level beyond them. So where two seams meet at 120 degrees their
     * offset lines cross exactly, each hex's top keeps an exact offset corner at its own level, and the line turns
     * smoothly between the levels of a three-level corner.
     */
    private void bandOffset(Corner corner, float z, float[] out) {
        // A corner that touches special artwork or a building keeps its place: the bands of the other steps
        // through it close there, so no outline that keeps its edge is ever pushed aside. One that touches water moves
        // with the slopes through it; the water hexes there keep clear of it (cornerReach, cornerInset).
        if (!corner.banded || corner.low == corner.high) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        // The cut below the slope continues from its foot, instead of returning to the old hex corner.
        float height = corner.pinned ? Math.clamp(z, corner.low * BoardGeometry.level(), corner.high * BoardGeometry.level()) : z;
        float[] cached = corner.bands.computeIfAbsent(Float.floatToIntBits(height), key -> computeBandOffset(corner, height));
        float weight = wetFoot(corner, z) * corner.wallWeight(z);
        out[0] = cached[0] * weight;
        out[1] = cached[1] * weight;
    }

    private float[] computeBandOffset(Corner corner, float z) {
        if (corner.around[2] == null) { return boardBandOffset(corner, z); }
        float[] result = new float[2];
        float level = BoardGeometry.level(), reach = .35f * level, a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0, widest = 0;
        for (int i = 0; i < 3; i++) {
            Site p = corner.around[i], q = corner.around[(i + 1) % 3];
            if (p == null || q == null) { continue; }
            if (p.level() == q.level() && p.detailed() && q.detailed() && !p.liquid() && !q.liquid()) { continue; }
            Site upper = p.level() >= q.level() ? p : q, lower = upper == p ? q : p;
            float bottom = lower.level() * level, top = upper.level() * level;
            boolean inside = z >= bottom - EPSILON * level && z <= top + EPSILON * level;
            float end = Math.max(0, 1 - Math.min(Math.abs(z - bottom), Math.abs(z - top)) / reach);
            float weight = (inside ? 1 : 0) + 8 * end * end;
            if (weight <= 0) { continue; }
            Vector3 normal = bandNormal(upper, lower);
            float nx = normal.x, ny = normal.y;
            float b = band(upper, lower, z, corner.x, corner.y);
            widest = Math.max(widest, room(upper, lower));
            a11 += weight * nx * nx;
            a12 += weight * nx * ny;
            a22 += weight * ny * ny;
            b1 += weight * nx * b;
            b2 += weight * ny * b;
        }
        float determinant = a11 * a22 - a12 * a12;
        float trace = a11 + a22;
        if (widest <= 0 || trace < 1e-4f) { return result; }
        if (determinant < 1e-4f * trace * trace) {
            // Fitting can put both incident edges on one line. Its offset needs no tangent displacement.
            result[0] = b1 / trace;
            result[1] = b2 / trace;
        } else {
            result[0] = (a22 * b1 - a12 * b2) / determinant;
            result[1] = (a11 * b2 - a12 * b1) / determinant;
        }
        // Seams asking for opposite bands on a narrow angle could otherwise throw the corner far out.
        float length = (float) Math.hypot(result[0], result[1]), limit = 1.5f * widest;
        if (length > limit) {
            result[0] *= limit / length;
            result[1] *= limit / length;
        }
        return result;
    }

    /**
     * The band of a corner on the board's edge, which only its two hexes share. Their step stays in the upper hex
     * there, its foot on the corner and its rim as far back as elsewhere, and the corner slides along the upper hex's
     * own edge of the board, so the board's cut keeps its hex outline at every height.
     */
    private float[] boardBandOffset(Corner corner, float z) {
        Site upper = corner.around[0].level() > corner.around[1].level() ? corner.around[0] : corner.around[1];
        Site lower = upper == corner.around[0] ? corner.around[1] : corner.around[0];
        float level = BoardGeometry.level(), b = band(upper, lower, z, corner.x, corner.y);
        float foot = band(upper, lower, lower.level() * level, corner.x, corner.y);
        float rim = band(upper, lower, upper.level() * level, corner.x, corner.y);
        if (foot > 0) { b = rim * (foot - b) / (foot - rim); }
        // The upper hex's edge of the board runs from this corner directly away from the lower hex's centre.
        float ux = corner.x - lower.x(), uy = corner.y - lower.y(), length = (float) Math.hypot(ux, uy);
        Vector3 normal = bandNormal(upper, lower);
        float slide = b * length / (ux * normal.x + uy * normal.y);
        return new float[] { ux / length * slide, uy / length * slide };
    }

    /** Slopes offset the shared fitted boundary, including edges that no longer follow the hex lattice. */
    private Vector3 bandNormal(Site upper, Site lower) {
        int e = Math.floorMod(1 - upper.coords().direction(lower.coords()), 6);
        Vector3 a = coast.corner(upper.coords(), e), b = coast.corner(upper.coords(), e + 1);
        Vector3 normal = new Vector3(b.y - a.y, a.x - b.x, 0).nor();
        if (normal.x * (lower.x() - upper.x()) + normal.y * (lower.y() - upper.y()) < 0) { normal.scl(-1); }
        return normal;
    }

    /**
     * The fillet displacement of a corner at height z. On a three-level corner it turns from the upper span's direction
     * to the middle hex's and on to the lower span's within a quarter level of the middle level, where the middle
     * hex's own outline turns.
     */
    private static void filletOffset(Corner corner, float z, float[] out) {
        float level = BoardGeometry.level();
        if (corner.fillet <= 0 || z < corner.low * level - EPSILON || z > corner.high * level + EPSILON) {
            out[0] = 0;
            out[1] = 0;
        } else if (corner.mid == corner.low || corner.mid == corner.high) {
            out[0] = corner.odd[0];
            out[1] = corner.odd[1];
        } else {
            float middle = corner.mid * level;
            float[] far = z >= middle ? corner.towardHigh : corner.towardLow;
            float f = smooth(Math.abs(z - middle) / (.25f * level));
            out[0] = lerp(corner.towardMid[0], far[0], f);
            out[1] = lerp(corner.towardMid[1], far[1], f);
        }
        if (corner.arc && (out[0] != 0 || out[1] != 0)) {
            float[] frame = corner.arcFrame();
            float bulge = corner.arcLength(z) * arc(0, frame[2]);
            out[0] = frame[0] * bulge;
            out[1] = frame[1] * bulge;
        }
        float weight = corner.wallWeight(z);
        out[0] *= weight;
        out[1] *= weight;
    }

    /** Outward direction of the rock at a corner and height, or null where the corner line is pinned. */
    private float[] cornerDirection(Corner corner, float z) {
        return cornerDirection(corner, z, false);
    }

    /** As above; a rim of the middle hex of a three-level corner faces away from that hex, whose outline turns there. */
    private float[] cornerDirection(Corner corner, float z, boolean rim) {
        if (corner.pinned || corner.low == corner.high) { return null; }
        if (rim && corner.mid != corner.low && corner.mid != corner.high
              && Math.abs(z - corner.mid * BoardGeometry.level()) < EPSILON * BoardGeometry.level()) {
            float length = (float) Math.hypot(corner.towardMid[0], corner.towardMid[1]);
            return length <= 0 ? null : new float[] { -corner.towardMid[0] / length, -corner.towardMid[1] / length };
        }
        int[] span = corner.span(z);
        Site solid = null, empty = null;
        int solids = 0;
        for (Site site : corner.around) {
            if (site.level() >= span[1]) {
                solids++;
                solid = site;
            } else if (site.level() <= span[0]) {
                empty = site;
            }
        }
        float ux, uy;
        if (solids == 1) {
            ux = corner.x - solid.x();
            uy = corner.y - solid.y();
        } else if (solids == 2 && empty != null) {
            ux = empty.x() - corner.x;
            uy = empty.y() - corner.y;
        } else {
            return null;
        }
        float length = (float) Math.sqrt(ux * ux + uy * uy);
        return new float[] { ux / length, uy / length };
    }

    // ---- Canonical edges -------------------------------------------------------------------------------------

    /** One geometric hex edge, oriented from the smaller corner key; both adjacent hexes build the same one. */
    private final class Edge {
        /** A road on a shore owns the edge even when there is no upper/lower terrain pair. */
        final Site shoreRoad;
        final Corner a;
        final Corner b;
        final Site upper;
        final Site lower;
        final float nx;
        final float ny;
        final Vector3 bandNormal;
        final float length;
        final boolean profiled;
        /** An internal seam between joined, level tops has no exposed relief of its own. */
        final boolean levelJoin;
        final boolean fixedSampling;
        /** Both sides and incident corners can share a single straight, poured edge at every height. */
        final boolean simpleConcrete;
        /** Match the unsplit edge of a flat road carrier, avoiding raster cracks at collinear T junctions. */
        final boolean flatRoad;
        /** Water and concrete keep their outlines where they meet a step: no relief at their foot or rim. */
        final boolean footPinned;
        final boolean rimPinned;
        /** The room of the step; see {@link #room(Site, Site)}. */
        final float room;
        /** How far the step's foot lies out from the edge in the lower hex; see {@link #band}. */
        final float footRoom;
        /** How far the step recedes under a deck that crosses it; see {@link #deckBay}. */
        final float bay;
        /** The step's height for its landforms; see {@link #drop(Site, Site)}. */
        final float drop;

        Edge(Corner first, Corner second, Site one, Site other) {
            levelJoin = joined(one, other);
            boolean shore = one != null && other != null && one.liquid() != other.liquid();
            shoreRoad = !shore ? null : one.road() ? one : other.road() ? other : null;
            // Coarse shores still need the samples that hold a road's full width at its shared gate.
            fixedSampling = shoreRoad != null || one != null && other != null && !TerrainLod.sameChunk(one.coords(), other.coords());
            boolean ordered = first.key < second.key;
            a = ordered ? first : second;
            b = ordered ? second : first;
            length = (float) Math.hypot(b.x - a.x, b.y - a.y);
            if (one != null && other != null && one.level() != other.level()) {
                upper = one.level() > other.level() ? one : other;
                lower = upper == one ? other : one;
            } else if (one != null && other == null) {
                upper = one;
                lower = null;
            } else {
                upper = null;
                lower = null;
            }
            float dx = (b.x - a.x) / length, dy = (b.y - a.y) / length;
            float px = dy, py = -dx;
            float sx = upper == null ? 0 : (lower == null ? (a.x + b.x) / 2 : lower.x()) - upper.x();
            float sy = upper == null ? 0 : (lower == null ? (a.y + b.y) / 2 : lower.y()) - upper.y();
            if (px * sx + py * sy < 0) {
                px = -px;
                py = -py;
            }
            nx = px;
            ny = py;
            footPinned = lower == null || !lower.sculpted() || lower.fixedOutline();
            rimPinned = upper != null && upper.fixedOutline() && !(upper.liquid() && lower != null && !lower.liquid());
            profiled = upper != null && lower != null && upper.sculpted() && (lower.sculpted() || lower.liquid());
            bandNormal = profiled ? bandNormal(upper, lower) : new Vector3(nx, ny, 0);
            room = profiled ? room(upper, lower) : 0;
            footRoom = room > 0 ? band(upper, lower, bottom(), (a.x + b.x) * .5f, (a.y + b.y) * .5f) : 0;
            bay = profiled ? deckBay(upper, lower) : 0;
            drop = upper != null && lower != null ? drop(upper, lower) : 0;
            simpleConcrete = simpleConcreteCorner(a) && simpleConcreteCorner(b);
            flatRoad = flatRoad(one) || flatRoad(other);
        }

        float bottom() { return lower == null ? Float.NEGATIVE_INFINITY : lower.level() * BoardGeometry.level(); }

        float top() { return upper == null ? Float.POSITIVE_INFINITY : upper.level() * BoardGeometry.level(); }
    }

    private Edge edge(int index) {
        int e = Math.floorMod(index, 6);
        if (edges[e] == null) {
            edges[e] = new Edge(corner(self, e), corner(self, e + 1), self, neighbor(self, e));
        }
        return edges[e];
    }

    /** The edge between any site here and its neighbour across edge e; this hex's own are {@link #edge(int)}. */
    private Edge edge(Site site, int e) {
        for (int k = 0; k < 6; k++) {
            if (site == self && k == Math.floorMod(e, 6) || site == neighbor(self, k) && neighbor(site, e) == self) {
                return edge(k);
            }
        }
        Corner first = corner(site, e), second = corner(site, e + 1);
        return builtEdges.computeIfAbsent(List.of(Math.min(first.key, second.key), Math.max(first.key, second.key)),
              key -> new Edge(first, second, site, neighbor(site, e)));
    }

    /** The edges through a corner: between each two of the hexes around it. */
    private List<Edge> edges(Corner corner) {
        List<Edge> result = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                Site one = corner.around[i], other = corner.around[j];
                for (int e = 0; one != null && other != null && e < 6; e++) {
                    if (neighbor(one, e) == other) { result.add(edge(one, e)); }
                }
            }
        }
        return result;
    }

    boolean naturalEdge(int e) { return self.sculpted() && edge(e).profiled; }

    /** Whether this hex's outline follows the relief's rim, rather than keeping its lattice edges. */
    boolean graded() { return self.sculpted(); }

    /** Whether a corner's water lies at its lowest level only, under the land's steps (see {@link #wet}). */
    private static boolean wetBelow(Corner corner) {
        boolean land = false;
        for (Site site : corner.around) {
            if (site == null || site.liquid() && site.level() != corner.low) { return false; }
            land |= !site.liquid();
        }
        return land;
    }

    private static boolean dry(Corner corner) {
        for (Site site : corner.around) {
            if (site == null || site.liquid()) { return false; }
        }
        return true;
    }

    /** A level land corner is straight; absent neighbours do not add relief to the board's cut edge. */
    private static boolean flatDry(Corner corner) {
        if (corner.low != corner.high) { return false; }
        for (Site site : corner.around) {
            if (site != null && site.liquid()) { return false; }
        }
        return true;
    }

    /** The relief's outline on edge e at this hex's parameter t, at height z. */
    Vector3 outlinePoint(int e, float t, float z) {
        Edge edge = edge(e);
        return edgePoint(edge, corner(self, e).key == edge.a.key ? t : 1 - t, z);
    }

    /** Canonical sample count of an edge: cliffs need finer columns than open ground. */
    private int samples(Edge edge) {
        if (edge.simpleConcrete || edge.flatRoad) { return 1; }
        int steps = edge.fixedSampling ? TerrainLod.FULL.steps : detail.steps;
        // Level land needs one shared segment, including across detail levels and at the board's cut edge.
        // Curved cliff and water contacts retain the samples needed by their actual outline.
        if (!edge.profiled && flatDry(edge.a) && flatDry(edge.b)) { return 1; }
        int count = edge.profiled && wall(edge.upper, edge.lower) ? 2 * steps : steps;
        // Road cut/fill corners can be concave. Preserve the outline resolution their graded carrier needs,
        // including the natural hex on the other side; this is independent of either chunk's detail.
        return edge.profiled && (edge.upper.road() || edge.lower.road())
              ? Math.max(count, wall(edge.upper, edge.lower) ? 24 : 12) : count;
    }

    /** Poured tops share their straight boundary with the adjoining surface. */
    private int topSamples(int e) {
        Site other = neighbor(self, e);
        return !self.liquid() && (planarConcrete(self)
              || other != null && other.level() == self.level() && planarConcrete(other))
              ? 1 : samples(edge(e));
    }

    private boolean flatRoad(Site site) {
        return site != null && site.road() && BoardSurface.flatRoadTop(scene, scene.tile(site.coords()), site.ramps());
    }

    /** No natural relief, road gate, water contact or intermediate level needs samples on this corner. */
    private boolean simpleConcreteCorner(Corner corner) {
        if (corner.pinned && corner.low != corner.high) { return false; }
        if (corner.low < corner.mid && corner.mid < corner.high) { return false; }
        for (Site site : corner.around) {
            if (site == null) { continue; }
            if (!planarConcrete(site)) { return false; }
        }
        return true;
    }

    /** Poured tops stay level; adjacent natural ground eases down to their straight edge. */
    private static boolean planarConcrete(Site site) {
        return site != null && !site.liquid() && !site.road() && site.sculpted() && site.family() == CONCRETE;
    }

    private float canonical(int index, int sample, int count) {
        // Integer arithmetic first: both hexes of an edge must produce the bit-identical parameter.
        return (edge(index).a == corner(self, index) ? sample : count - sample) / (float) count;
    }

    /**
     * Displaced point of an edge at canonical parameter t and height z (horizontal offset only). Each corner's fillet
     * moves the outline by its displacement times (1 - u/s)^3, u being the distance from the corner and s the fillet
     * length. A displacement of s/6 toward the far side makes the outlines meeting at the corner tangent-continuous,
     * so no corner keeps a crease; a rounded corner runs a circle's arc instead ({@link #arc}). Relief moves along the
     * face normal and blends into each corner's canonical relief, which every edge through that corner shares.
     */
    private Vector3 edgePoint(Edge edge, float t, float z) {
        // A board cut remains a straight section between its shared corners after bridge clearance, too.
        // Scaling individual stations independently can turn the lower section back through itself.
        if (edge.upper != null && edge.lower == null && t > 0 && t < 1) {
            return edgePoint(edge, 0, z).lerp(edgePoint(edge, 1, z), t);
        }
        Vector3 point = naturalPoint(edge, t, z);
        float[] push = roadPush(edge, t);
        if (push[0] == 0 && push[1] == 0) { return cutForBridges(edge, t, point); }
        // Where the step rises from the road, each height of the band below the road's headroom recedes only as far as
        // it needs to clear the road: the foot's talus steepens into the road's verge while the face above, which never
        // stood in the way, keeps its place, so the face never overhangs its foot. Above the headroom the slope keeps
        // its natural shape, easing back to it no faster than the band's own run allows, so no height overhangs the one
        // below (a smooth ease moves up to 1.5 times its share per run). A corner eases as its steepest step does, so
        // every edge through it moves it alike.
        float slope = lerp(slope(t < .5f ? edge.a : edge.b), slope(edge), smooth(Math.min(t, 1 - t) / CORNER_EASE));
        float fade = Math.max(.5f * BoardGeometry.level(), 1.5f * (float) Math.hypot(push[0], push[1]) * slope);
        float share = (z <= push[2] ? 1 : 1 - smooth((z - push[2]) / fade)) * wet(edge, t, z);
        // A drop below the road keeps one band, which fills out under the road's verge.
        float surface = push[2] - BoardRoadGrade.CLEARANCE * BoardGeometry.level();
        float middle = t == 0 ? (edge.a.low + edge.a.high) / 2f * BoardGeometry.level()
              : t == 1 ? (edge.b.low + edge.b.high) / 2f * BoardGeometry.level() : (edge.bottom() + edge.top()) / 2;
        if (surface < middle) { share = Math.min(share, needed(edge, t, point, push)); }
        // A road ending on a deep drop's top sits on a lip of rock brought out under its end, as deep as the road's cut
        // faces reach (BoardRoadCut#curtain); below it the rock keeps its place, so it overhangs the drop under the
        // road's end instead of sliding out over the ground below. Deep is the drop under the road's end, not a deeper
        // one beside it: a bank the road ends on comes out whole, its corners too where a cliff falls further beside.
        float overhang = OVERHANG * BoardGeometry.level(), lip = surface - (BoardRoadGrade.FILL + .1f) * BoardGeometry.level();
        // All along the edge the road ends on, easing in from a corner it ends at along the edges beside.
        float near = Math.max(endsOn(edge) && lip - edge.bottom() > overhang ? 1 : 0,
              Math.max(lip - landingFloor(edge.a) > overhang ? 1 - smooth(t / CORNER_EASE) : 0,
                    lip - landingFloor(edge.b) > overhang ? 1 - smooth((1 - t) / CORNER_EASE) : 0));
        share *= lerp(1, smooth((z - lip + overhang) / overhang), near);
        point.x += push[0] * share;
        point.y += push[1] * share;
        return cutForBridges(edge, t, point);
    }

    /** Height, in levels, below a road's end over which the rock under it overhangs the drop (#edgePoint). */
    private static final float OVERHANG = 1.5f;

    /**
     * The height of the ground under the end of a road that ends on a step's top at this corner (#landing), the lowest
     * where several do; infinite where none does.
     */
    private float landingFloor(Corner corner) {
        return landings.computeIfAbsent(corner, key -> {
            float floor = Float.POSITIVE_INFINITY;
            // The board ends at its edge: no road ends on a step there.
            if (Arrays.asList(key.around).contains(null)) { return floor; }
            for (Site site : key.around) {
                if (!site.road() || site.liquid() || site.family() == CONCRETE) { continue; }
                for (Site other : key.around) {
                    if (!other.coords().equals(site.coords()) && landing(site, other, false)) {
                        floor = Math.min(floor, other.level() * BoardGeometry.level());
                    }
                }
            }
            return floor;
        });
    }

    /** Whether the road of an edge's upper hex ends on this edge's step (#ends), with no abutment holding it. */
    private boolean endsOn(Edge edge) {
        if (edge.upper == null || edge.lower == null || !edge.upper.road() || abutted(edge.upper, edge.lower)) { return false; }
        for (int d = 0; d < 6; d++) {
            if ((scene.tile(edge.upper.coords()).wayExits() & 1 << d) != 0
                  && edge.upper.coords().translated(d).equals(edge.lower.coords())) { return ends(edge.upper, edge.lower, d); }
        }
        return false;
    }

    /** A step's height over its run; 0 without a profile. */
    private static float slope(Edge edge) {
        float height = edge.top() - edge.bottom(), run = edge.room + edge.footRoom;
        return Float.isFinite(height) && run > 0 ? height / run : 0;
    }

    /** The steepest step through a corner. */
    private float slope(Corner corner) {
        float result = 0;
        for (Edge edge : edges(corner)) { result = Math.max(result, slope(edge)); }
        return result;
    }

    /**
     * The least share of {@code push} that moves a point clear of the roads around the edge's corners, by bisection;
     * 1 where the whole push is needed or the point lies on a road's mouth, which the push left alone.
     */
    private float needed(Edge edge, float t, Vector3 point, float[] push) {
        Corner[] ends = t == 0 ? new Corner[] { edge.a } : t == 1 ? new Corner[] { edge.b } : new Corner[] { edge.a, edge.b };
        if (clear(ends, point.x, point.y)) { return 0; }
        if (!clear(ends, point.x + push[0], point.y + push[1])) { return 1; }
        float low = 0, high = 1;
        for (int i = 0; i < 8; i++) {
            float middle = (low + high) / 2;
            if (clear(ends, point.x + push[0] * middle, point.y + push[1] * middle)) { high = middle; } else { low = middle; }
        }
        return high;
    }

    /** Whether a world point lies clear of the verges of the roads around these corners. */
    private boolean clear(Corner[] ends, float x, float y) {
        float scale = BoardGeometry.hexScale(), clear = BoardRoad.SHOULDER + BoardRoadBank.VERGE;
        for (Corner end : ends) {
            for (Site site : end.around) {
                if (site == null || !site.road() || site.liquid() || site.family() == CONCRETE) { continue; }
                BoardRoad road = bed(site);
                if (road.distance((x - site.x()) / scale, (y - site.y()) / scale) < clear) { return false; }
            }
        }
        return true;
    }

    /** The ground a site's road is levelled and cleared for (BoardRoad#terrainBed): a loose road's track only. */
    private BoardRoad bed(Site site) {
        return roads.computeIfAbsent(site.coords(), at -> {
            var tile = scene.tile(at);
            return BoardRoad.way(scene, tile).terrainBed(scene, tile);
        });
    }

    /** Furthest a natural band recedes from a road, in tile pixels; a road crossing it is cut through instead. */
    private static final float ROAD_PUSH = 40;

    private static final float[] NO_PUSH = { 0, 0, Float.NEGATIVE_INFINITY };

    /**
     * How far, in world units, the band of a step moves at t so that it clears the carriageways beside it, and up to
     * what height: {x, y, top}. Everything below the road's headroom ({@link BoardRoadGrade#CLEARANCE}) moves alike, so
     * the slope's foot erodes back to the road's verge and keeps its shape; above it the slope stays natural. The band
     * moves away from the road, down its distance gradient at the edge; between its corners only across the edge, as
     * a smooth bay ({@link #ownPush}). Where a road crosses the edge itself it is a mouth, where the ground eases into the
     * road instead (BoardRoadBank). A corner takes only its own hexes' roads, so every edge through it moves it alike.
     */
    private float[] roadPush(Edge edge, float t) {
        if (t == 0 || t == 1) { return cornerPush(t == 0 ? edge.a : edge.b, edge, t); }
        if (!bayed(edge)) {
            // A straight seam between corners follows their moves, so it stays straight and joined.
            float[] a = cornerPush(edge.a, edge, 0), b = cornerPush(edge.b, edge, 1);
            return a == NO_PUSH && b == NO_PUSH ? NO_PUSH
                  : new float[] { lerp(a[0], b[0], t), lerp(a[1], b[1], t), Math.max(a[2], b[2]) };
        }
        return pushes.computeIfAbsent(edge, key -> new HashMap<>()).computeIfAbsent(t, key -> {
            float[] own = ownPush(edge, t);
            float[] first = cornerPush(edge.a, edge, 0), second = cornerPush(edge.b, edge, 1);
            float ex = (edge.b.x - edge.a.x) / edge.length, ey = (edge.b.y - edge.a.y) / edge.length;
            // Across the edge the bay departs from either corner's move by at most PUSH_SLOPE per run, as it does from
            // its own stations: where the band turns round a corner a steeper bay would fold it over itself. A deck's bay,
            // under the deck, may turn in at BAY_SLOPE.
            float across = own[0] * -ey + own[1] * ex, acrossA = first[0] * -ey + first[1] * ex;
            float slope = edge.bay > 0 ? BAY_SLOPE : PUSH_SLOPE;
            float acrossB = second[0] * -ey + second[1] * ex, runA = t * edge.length * slope;
            float runB = (1 - t) * edge.length * slope;
            float low = Math.max(acrossA - runA, acrossB - runB), high = Math.min(acrossA + runA, acrossB + runB);
            float bay = low > high ? lerp(acrossA, acrossB, t) : Math.clamp(across, low, high);
            // Easing from the nearer corner's move, the band never jumps between its corner and its middle. Along the
            // edge the bay itself never moves: the corners' moves blend straight from one to the other, so the outline
            // never runs back on itself.
            float share = smooth(Math.min(t, 1 - t) / CORNER_EASE);
            float[] end = t < .5f ? first : second;
            float side = lerp(end[0] * -ey + end[1] * ex, bay, share);
            float along = lerp(first[0] * ex + first[1] * ey, second[0] * ex + second[1] * ey, t);
            // The move's headroom; the corners' too where theirs shape it.
            float top = bay == across && share >= 1 && along == 0 ? own[2]
                  : Math.max(own[2], Math.max(first[2], second[2]));
            return new float[] { -ey * side + ex * along, ex * side + ey * along, top };
        });
    }

    /**
     * How far the bank of water recedes from a road below its surface that leaves a deck over the water: so its rim lies
     * behind the deck's ramp ({@link BoardRoadGrade#BRIDGE_START}), which passes over the slope. The road push carries it
     * ({@link #push}). None elsewhere.
     */
    private float deckBay(Site upper, Site lower) {
        if (!upper.liquid() || lower.liquid() || lower.level() >= upper.level()) { return 0; }
        var water = scene.tile(upper.coords());
        // A canal's ramp cuts straight into its concrete instead (BoardSurface#cutRamps).
        if (canal(water)) { return 0; }
        var bridge = BoardBridge.feature(water);
        boolean crosses = bridge != null && bridge.elevation() >= 0 && BoardBridge.family(water) != BoardBridge.Family.NATURAL
              && BoardBridge.road(water, scene.tile(lower.coords()), upper.coords().direction(lower.coords()));
        if (!crosses) { return 0; }
        return (1 - BoardRoadGrade.BRIDGE_START) * BoardGeometry.height() / 2 + metres(BAY_MARGIN);
    }

    /** Metres a bank's rim stays behind the high end of a deck's ramp. */
    private static final float BAY_MARGIN = .75f;

    /**
     * The share of a step's outward relief kept at {@code t} along its edge: none across the face of the abutment of a
     * deck landing on it, so the earth stays behind the concrete there; all of it beside, where the rock stands out over
     * the concrete's edges as it would round anything set into it (#abutted).
     */
    private static float held(Edge edge, float t) {
        if (!abutted(edge.upper, edge.lower)) { return 1; }
        return smooth((Math.abs(t - .5f) * edge.length - edge.lower.abutment()) / BoardGeometry.hexScale() + .5f);
    }

    /** Whether the built deck in {@code deck} lands on {@code bank} on an abutment ({@link BoardBridgeFooting#abutted}). */
    private static boolean abutted(Site bank, Site deck) {
        return bank != null && deck != null && (deck.abutments() & 1 << deck.coords().direction(bank.coords())) != 0;
    }

    /** Half the width of a deck's passage over this edge: the stretch of its bank the deck's ramp needs cleared. */
    private float deckLane(Site water, Site road) {
        return BoardBridge.passage(scene.tile(water.coords()), water.coords().direction(road.coords())) * BoardGeometry.hexScale();
    }

    /**
     * A corner of such a bank, {x, y, top}: as far across the edge as lets the bay fall off to it at {@link #PUSH_SLOPE},
     * along the water's next edge where land lies below the water there or the water runs on through a mouth, or toward
     * the hex's centre between two bays of one deck, and never so far along an edge that two corners of it would meet. Higher
     * ground or an outline that stays holds it in place, and the bay turns in more steeply from it ({@link #BAY_SLOPE}).
     * {@link #NO_PUSH} for every other corner.
     */
    private float[] bayCorner(Corner corner) {
        return bayCorners.computeIfAbsent(corner, key -> {
            for (Site water : key.pinned ? new Site[0] : key.around) {
                if (water == null || !water.liquid()) { continue; }
                for (Site road : key.around) {
                    float bay = road == null || road == water ? 0 : deckBay(water, road);
                    if (bay <= 0) { continue; }
                    float across = bay - PUSH_SLOPE * Math.max(0, BoardGeometry.width() / 4 - deckLane(water, road));
                    if (across <= 0) { continue; }
                    for (Site land : key.around) {
                        if (land == null || land == water || land == road || !recedes(water, land)) { continue; }
                        Edge side = edge(water, edgeTo(water, land));
                        Corner next = side.a == key ? side.b : side.a;
                        boolean between = deckBay(water, land) > 0;
                        float x = between ? water.x() - key.x : next.x - key.x, y = between ? water.y() - key.y : next.y - key.y;
                        float scale = (between ? across / COS_30 : Math.min(across / COS_30, BAY_CORNER * side.length)) / (float) Math.hypot(x, y);
                        return new float[] { x * scale, y * scale, (water.level() + BoardRoadGrade.CLEARANCE) * BoardGeometry.level() };
                    }
                }
            }
            return NO_PUSH;
        });
    }

    private static final float COS_30 = .8660254f;
    /** The most of an edge's length a bay moves a corner along it. */
    private static final float BAY_CORNER = .45f;
    /** Tile pixels a canal's lining rises from the water to its shoulder, across. */
    private static final float LINING_LIP = .3f;
    /** Most a deck's bay changes per run along its edge: it has to clear the ramp's whole lane even from a held corner. */
    private static final float BAY_SLOPE = 4;

    /** Whether a bay's corner may recede beside this hex: land below the water, or the same water through an open mouth. */
    private boolean recedes(Site water, Site beside) {
        if (beside.liquid()) {
            return beside.level() == water.level() && scene.tile(water.coords()).joins(scene.tile(beside.coords()),
                  water.coords().direction(beside.coords()));
        }
        return beside.sculpted() && !beside.fixedOutline() && beside.level() < water.level();
    }

    /** The edge index of {@code site} facing its neighbour {@code other}. */
    private int edgeTo(Site site, Site other) {
        for (int e = 0; e < 6; e++) {
            if (neighbor(site, e) == other) { return e; }
        }
        throw new IllegalArgumentException("Not neighbours");
    }

    /** Whether a corner's water stands at its highest level only, over land its steps come down to. */
    private static boolean waterAbove(Corner corner) {
        boolean land = false, water = false;
        for (Site site : corner.around) {
            if (site == null || site.liquid() && site.level() != corner.high) { return false; }
            land |= !site.liquid() && site.level() == corner.low;
            water |= site.liquid();
        }
        return water && land && corner.low != corner.high && !corner.pinned;
    }

    /** A waterline's slow wander in the width of a bank, in [-1, 1]. */
    static float bankWander(float x, float y) {
        return 2 * noise(x / metres(11) + 3.1f, y / metres(11) - 7.4f) - 1;
    }

    /** Whether water at corner k of this hex stands above land there. */
    boolean overLand(int k) { return waterAbove(corner(self, k)); }

    /** Whether a deck's ramp has moved the bank at corner k of this hex ({@link #bayCorner}). */
    boolean bayAt(int k) { return bayCorner(corner(self, k)) != NO_PUSH; }

    /**
     * How far inside the rims of this hex's steps down to land a point lies, whichever way roads have moved them, negative
     * outside them; {@code NaN} without such a step. The outline itself: what a water hex keeps its water inside.
     */
    float insideRim(float x, float y) {
        if (rimEdges < 0) {
            rimEdges = 0;
            for (int e = 0; e < 6; e++) {
                Edge edge = edge(e);
                if (self.liquid() && edge.upper == self && edge.lower != null && !edge.lower.liquid()) { rimEdges |= 1 << e; }
            }
        }
        if (rimEdges == 0) { return Float.NaN; }
        List<Vector3> boundary = topOutline();
        float nearest = Float.POSITIVE_INFINITY;
        for (int e = 0; e < 6; e++) {
            if ((rimEdges & 1 << e) == 0) { continue; }
            for (int i = outlineStarts[e]; i < outlineStarts[e + 1]; i++) {
                Vector3 p = boundary.get(i), q = boundary.get((i + 1) % boundary.size());
                nearest = Math.min(nearest, distance(x, y, p.x, p.y, q.x, q.y));
            }
        }
        boolean inside = false;
        for (int i = 0, last = boundary.size() - 1; i < boundary.size(); last = i++) {
            Vector3 p = boundary.get(i), q = boundary.get(last);
            inside ^= p.y > y != q.y > y && x < p.x + (q.x - p.x) * (y - p.y) / (q.y - p.y);
        }
        return inside ? nearest : -nearest;
    }

    /**
     * Whether an edge's band recedes between its corners on its own, as a bay; otherwise it follows its corners. A step
     * down into water moves too, above its waterline (see {@link #wet}), and water's own step down to land moves as land's.
     */
    private static boolean bayed(Edge edge) { return edge.profiled && !(edge.upper.liquid() && edge.lower.liquid()); }

    /**
     * How much of its push a band's point at height z takes beside water: none at the waterline, whose outline the
     * water keeps, all half a level above it, so a cliff over water comes out above its foot. A wet corner holds the
     * edges through it alike, easing off along them; water standing above the land there holds nothing, as its bank is land.
     */
    private float wet(Edge edge, float t, float z) {
        float share = 1;
        if (edge.lower != null && edge.lower.liquid()) { share = waterline(edge.lower.level(), z); }
        for (Corner end : new Corner[] { edge.a, edge.b }) {
            if (waterAbove(end)) { continue; }
            float water = Float.NEGATIVE_INFINITY;
            for (Site site : end.around) {
                if (site != null && site.liquid()) { water = Math.max(water, site.level()); }
            }
            if (water == Float.NEGATIVE_INFINITY) { continue; }
            float near = 1 - smooth((end == edge.a ? t : 1 - t) / CORNER_EASE);
            share = Math.min(share, lerp(1, waterline(water, z), near));
        }
        return share;
    }

    /** Water or dry ground that keeps its outline, paving: land above it takes its step on its own side (#band). */
    private static boolean kept(Site site) {
        return site != null && (site.liquid() || site.fixedOutline() && !site.ultraSublevel());
    }

    /** From none at a waterline at this level to all half a level above it. */
    private static float waterline(float level, float z) {
        return smooth((z - level * BoardGeometry.level()) / (.5f * BoardGeometry.level()));
    }

    /** Stations an edge's own push is found at. */
    private static final int PUSH_STATIONS = 24;
    /** Most an edge's push changes per run along it: its band recedes as a smooth bay and never folds. */
    private static final float PUSH_SLOPE = .5f;

    /**
     * An edge's own push at t: across the edge, the most any station needs less {@link #PUSH_SLOPE} times its distance
     * from t, so the band recedes smoothly round the stretch a road needs cleared, with that stretch's headroom.
     */
    private float[] ownPush(Edge edge, float t) {
        Station[] stations = stations(edge);
        float ex = -(edge.b.y - edge.a.y) / edge.length, ey = (edge.b.x - edge.a.x) / edge.length;
        float out = 0, in = 0, outTop = Float.NEGATIVE_INFINITY, inTop = Float.NEGATIVE_INFINITY;
        for (int k = 1; k < PUSH_STATIONS; k++) {
            float[] push = walled(new Station.Node(edge, k), edge) ? stations[k].walled() : stations[k].free();
            if (push == NO_PUSH) { continue; }
            float across = push[0] * ex + push[1] * ey, fall = Math.abs(t - k / (float) PUSH_STATIONS) * edge.length * PUSH_SLOPE;
            if (across - fall > out) {
                out = across - fall;
                outTop = push[2];
            } else if (-across - fall > in) {
                in = -across - fall;
                inTop = push[2];
            }
        }
        if (out == 0 && in == 0) { return NO_PUSH; }
        return new float[] { ex * (out - in), ey * (out - in), Math.max(outTop, inTop) };
    }

    private Station[] stations(Edge edge) {
        return stationPushes.computeIfAbsent(edge, key -> {
            Station[] result = new Station[PUSH_STATIONS + 1];
            for (int k = 1; k < PUSH_STATIONS; k++) {
                result[k] = push(edge, k / (float) PUSH_STATIONS, new Corner[] { edge.a, edge.b }, edge.bottom(),
                      edge.top(), true);
            }
            return result;
        });
    }

    /** Share of an edge, from each corner, over which a band eases from the corner's move to its own. */
    private static final float CORNER_EASE = .3f;

    /** A corner's move, from its own hexes and heights alone, so every edge through it moves it alike. */
    private float[] cornerPush(Corner corner, Edge edge, float t) {
        Station station = cornerStation(corner, edge, t);
        return walled(corner, edge) ? station.walled() : station.free();
    }

    private Station cornerStation(Corner corner, Edge edge, float t) {
        Station known = cornerPushes.get(corner);
        if (known != null) { return known; }
        float[] bay = bayCorner(corner);
        // Only a corner a step runs through has a slope to move; a level corner keeps its place.
        Station station = bay != NO_PUSH ? new Station(bay, bay, null)
              : (dry(corner) || wetBelow(corner) || waterAbove(corner)) && corner.low != corner.high ? push(edge, t,
              new Corner[] { corner }, corner.low * BoardGeometry.level(), corner.high * BoardGeometry.level(), false)
              : Station.UNMOVED;
        cornerPushes.put(corner, station);
        return station;
    }

    /**
     * A band's move to clear the roads around it at one station or corner, each {x, y, top} as {@link #roadPush} gives
     * it: free, as the slope recedes without a containment wall, and walled, as it recedes keeping its toe behind one at
     * the road's edge. Where a wall is worth it, the slope it retains runs from its foot up to the wall's top,
     * {footX, footY, topX, topY}; null elsewhere.
     */
    private record Station(float[] free, float[] walled, float[] wall) {
        static final Station UNMOVED = new Station(NO_PUSH, NO_PUSH, null);

        boolean wallable() { return wall != null; }

        /** A station between an edge's corners, by its index; the corners themselves are their own nodes. */
        private record Node(Edge edge, int k) { }
    }

    /**
     * The fewest panels a containment wall retains along (BoardRoadCut#PANEL), before the panel it runs on past either
     * end: along a shorter stretch the slope recedes freely instead, as a lone panel or two looks out of place.
     */
    private static final int WALL_PANELS = 2;

    /**
     * Whether containment walls are on and a station or corner keeps its toe behind one: a wall saves it ground, and so
     * do its neighbours along a run, through corners from edge to edge, at least {@link #WALL_PANELS} panels long. A
     * corner is reached only from an edge through it, which places it.
     */
    private boolean walled(Object start, Edge via) {
        if (!tuning().containmentWalls()) { return false; }
        Boolean known = walledRuns.get(start);
        if (known != null) { return known; }
        Station first = station(start, via);
        Map<Object, Station> run = new HashMap<>();
        run.put(start, first);
        ArrayDeque<Object> open = new ArrayDeque<>(List.of(start));
        float length = 0, enough = metres(WALL_PANELS * BoardRoadCut.PANEL);
        while (first.wallable() && !open.isEmpty() && length < enough) {
            Object node = open.poll();
            Station at = run.get(node);
            for (Object next : links(node)) {
                if (run.containsKey(next)) { continue; }
                Station station = station(next, node instanceof Station.Node(Edge edge, int k) ? edge : null);
                if (!station.wallable()) { continue; }
                run.put(next, station);
                open.add(next);
                length += (float) Math.hypot(station.wall()[2] - at.wall()[2], station.wall()[3] - at.wall()[3]);
            }
        }
        // A long run is long for every station on it; a short one was walked whole.
        boolean walled = first.wallable() && length >= enough;
        for (Object node : run.keySet()) { walledRuns.put(node, walled); }
        return walled;
    }

    private Station station(Object node, Edge via) {
        if (node instanceof Station.Node(Edge edge, int k)) { return bayed(edge) ? stations(edge)[k] : Station.UNMOVED; }
        Corner corner = (Corner) node;
        return cornerStation(corner, via, via.a == corner ? 0 : 1);
    }

    /** The stations and corners next to one along the steps: a corner leads on to every edge through it. */
    private List<Object> links(Object node) {
        if (node instanceof Station.Node(Edge edge, int k)) {
            return List.of(k == 1 ? edge.a : new Station.Node(edge, k - 1),
                  k == PUSH_STATIONS - 1 ? edge.b : new Station.Node(edge, k + 1));
        }
        Corner corner = (Corner) node;
        List<Object> result = new ArrayList<>(3);
        for (Edge edge : edges(corner)) {
            if (bayed(edge)) { result.add(new Station.Node(edge, edge.a == corner ? 1 : PUSH_STATIONS - 1)); }
        }
        return result;
    }

    /**
     * How fully the ground beside a road's edge at (x, y) stands behind a containment wall: 1 along the walled runs of
     * the steps round this hex, fading out within a verge past their ends (BoardRoadBank#VERGE); 0 without walls.
     */
    float walled(float x, float y) {
        if (!tuning().containmentWalls()) { return 0; }
        if (wallLines == null) { wallLines = wallLines(); }
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < wallLines.length; i += 4) {
            nearest = Math.min(nearest, distance(x, y, wallLines[i], wallLines[i + 1], wallLines[i + 2], wallLines[i + 3]));
        }
        float verge = BoardRoadBank.VERGE * BoardGeometry.hexScale();
        return 1 - smooth((nearest - 2 * verge) / verge);
    }

    /**
     * The slopes the walled runs retain along the edges through this hex's corners, its own and its neighbours' between
     * them, so the hexes either side of an edge see the same walls near it: each walled station's slope from foot to
     * top, joined to the next along its foot and its top.
     */
    private float[] wallLines() {
        Set<Edge> around = new LinkedHashSet<>();
        for (int k = 0; k < 6; k++) { around.addAll(edges(corner(self, k))); }
        List<Float> lines = new ArrayList<>();
        for (Edge edge : around) {
            if (!bayed(edge)) { continue; }
            Station previous = null;
            for (int k = 0; k <= PUSH_STATIONS; k++) {
                Object node = k == 0 ? edge.a : k == PUSH_STATIONS ? edge.b : new Station.Node(edge, k);
                Station station = station(node, edge);
                boolean walled = station.wallable() && walled(node, edge);
                if (walled) {
                    float[] wall = station.wall();
                    lines.addAll(List.of(wall[0], wall[1], wall[2], wall[3]));
                    if (previous != null) {
                        float[] last = previous.wall();
                        lines.addAll(List.of(last[0], last[1], wall[0], wall[1], last[2], last[3], wall[2], wall[3]));
                    }
                }
                previous = walled ? station : null;
            }
        }
        float[] result = new float[lines.size()];
        for (int i = 0; i < result.length; i++) { result[i] = lines.get(i); }
        return result;
    }

    /**
     * How far the band of an edge or corner moves at t to clear the roads around it, only across the edge if asked,
     * freely and behind a containment wall; see {@link Station}.
     */
    private Station push(Edge edge, float t, Corner[] ends, float bottom, float top, boolean across) {
        // Clear of the road's level verge, which the bank keeps at the road's height (BoardRoadBank#VERGE).
        float scale = BoardGeometry.hexScale(), clear = BoardRoad.SHOULDER + BoardRoadBank.VERGE, level = BoardGeometry.level();
        float px = t == 1 ? edge.b.x : lerp(edge.a.x, edge.b.x, t), py = t == 1 ? edge.b.y : lerp(edge.a.y, edge.b.y, t);
        if (across && edge.bay > 0 && Math.abs(t - .5f) * edge.length <= deckLane(edge.upper, edge.lower)) {
            float[] deck = { -edge.bandNormal.x * edge.bay, -edge.bandNormal.y * edge.bay, top + BoardRoadGrade.CLEARANCE * level };
            return new Station(deck, deck, null);
        }
        float[] free = NO_PUSH, walled = NO_PUSH;
        float rim = Float.NaN;
        // A wall retains only a natural slope rising from a road to a higher hex beside it, not one a road climbs onto,
        // and only an earth slope: a taller cliff recedes as rock. A dirt or gravel track cuts a natural bank instead.
        Site upper = across ? edge.upper : null;
        for (Site site : across ? new Site[0] : ends[0].around) {
            if (site != null && (upper == null || site.level() > upper.level())) { upper = site; }
        }
        for (Corner end : ends) {
            for (Site site : end.around) {
                if (site == null || !site.road() || site.liquid() || site.family() == CONCRETE) { continue; }
                BoardRoad road = bed(site);
                float bx = (px - site.x()) / scale, by = (py - site.y()) / scale;
                if (road.distance(bx, by) < clear && mouth(site, ends)) { continue; }
                // Away from the road: its distance gradient at the edge. On the road itself, where a wide corridor
                // reaches past a corner, there is none: back into the highest hex there, whose step stands over it.
                // Between corners the road crosses the edge there, up the step its own grade climbs, so it stays.
                float gx = road.distance(bx + .25f, by) - road.distance(bx - .25f, by);
                float gy = road.distance(bx, by + .25f) - road.distance(bx, by - .25f);
                // A road carried on by a bridge's deck past these corners ends on the step's top: the step comes out
                // under the deck's end, straight across the edge or away from the road's hex at a corner, as a
                // natural pillar or bank. Where a built deck lands level, its abutment holds the bank instead: the step it
                // lands on keeps its place (BoardBridgeFooting#abutted), its corners recede from the road as any corner
                // does, and the steps beside still come out under the road's sides.
                boolean landing = landing(site, ends);
                if (landing && across && abutted(edge.upper, edge.lower)) { continue; }
                if (landing && !across && landing(site, ends, true)) {
                    // Only along the abutment's face, never out in front of it: the concrete's side stays clear.
                    landing = false;
                    for (Site deck : end.around) {
                        if (!abutted(site, deck)) { continue; }
                        float dx = deck.x() - site.x(), dy = deck.y() - site.y(), between = (float) Math.hypot(dx, dy);
                        float out = Math.max(0, (gx * dx + gy * dy) / between);
                        gx -= out * dx / between;
                        gy -= out * dy / between;
                    }
                }
                if (landing && !across) {
                    gx = px - site.x();
                    gy = py - site.y();
                } else if (Math.hypot(gx, gy) < 1e-4f && !landing) {
                    if (across) { continue; }
                    Site high = site;
                    for (Site other : end.around) {
                        if (other != null && other.level() > high.level()) { high = other; }
                    }
                    gx = high.x() - px;
                    gy = high.y() - py;
                }
                float length = (float) Math.hypot(gx, gy);
                if (length < 1e-4f) { continue; }
                gx /= length;
                gy /= length;
                if (across) {
                    // Along an edge the band only recedes across it; a road running along the edge is its mouth's.
                    float nx = -(edge.b.y - edge.a.y) / edge.length, ny = (edge.b.x - edge.a.x) / edge.length;
                    float dot = landing ? nx * (px - site.x()) + ny * (py - site.y()) : gx * nx + gy * ny;
                    if (!landing && Math.abs(dot) < .5f) { continue; }
                    gx = Math.signum(dot) * nx;
                    gy = Math.signum(dot) * ny;
                }
                var grade = BoardRoadGrade.of(scene, scene.tile(site.coords()), true);
                // Up to the headroom; beside a road cut down onto an apron over the whole range it spans, as high as its
                // bank cuts there (BoardRoadBank).
                float headroom = grade == null ? (site.level() + BoardRoadGrade.CLEARANCE) * level
                      : grade.intoApron() ? Math.max(grade.clearanceTop(px, py), grade.high() + BoardRoadGrade.CLEARANCE * level)
                      : grade.clearanceTop(px, py);
                // Everything below the road's headroom recedes but a shallow drop below the road, which the road's bank
                // fills (BoardRoadBank) rather than moving it away. No bank fills a drop to water, which fades out at the
                // wet hex's outline: its rim comes out under the road's verge as a footing, its foot staying at the waterline.
                // Where a deck lands it needs none: the deck's abutment or a natural bridge's rock stands on the bank.
                float surface = headroom - BoardRoadGrade.CLEARANCE * level + .05f * level;
                boolean toWater = !landing && (across ? edge.lower != null && edge.lower.liquid() : !dry(ends[0]) && wetBelow(ends[0]));
                boolean shallow = !toWater && top - bottom <= (BoardRoadGrade.FILL + .01f) * level;
                // Every half level at least, so the band below a tall cliff's headroom is never missed.
                int steps = Math.max(6, (int) Math.ceil(2 * (top - bottom) / level)), count = 0;
                float[] heights = new float[steps + 1];
                for (int k = 0; k <= steps; k++) {
                    float z = lerp(bottom, top, k / (float) steps);
                    if (z <= headroom && !(shallow && z <= surface)) { heights[count++] = z; }
                }
                float moved = clearance(edge, t, site, road, gx, gy, heights, count);
                if (landing && (road.distance(bx, by) < clear || !across)) {
                    // Where the road crosses, and at the corners of the edge it ends on, its whole band comes out to the
                    // edge, so the road ends on its top however near the corners its reserved ground reaches.
                    Vector3 last = null;
                    for (int k = 0; k < count; k++) {
                        Vector3 q = naturalPoint(edge, t, heights[k]);
                        float out = Math.min(ROAD_PUSH, ((px - q.x) * gx + (py - q.y) * gy) / scale);
                        if (out > moved) {
                            moved = out;
                            last = q;
                        }
                    }
                    float[] side = across || last == null ? null : deckSide(site, ends[0], px, py);
                    float dx = side == null ? 0 : side[0] - last.x, dy = side == null ? 0 : side[1] - last.y;
                    float out = (float) Math.hypot(dx, dy);
                    if (out > 1e-4f) {
                        // A corner comes out to the deck's side on the edge it lands on (#deckSide), not to the hex's
                        // corner past it: no ledge beside the deck's end, and the steps beside keep their line.
                        gx = dx / out;
                        gy = dy / out;
                        moved = Math.min(ROAD_PUSH, out / scale);
                    }
                }
                // Behind a wall only the slope above the wall's top recedes, so the wall stays low.
                float wallTop = surface + BoardRoadBank.WALL * level, highest = Math.min(top, headroom);
                float kept = moved;
                if (upper != null && !upper.road() && upper.level() > site.level() && wallTop < highest
                      && top - bottom <= BoardRoadBank.WALLED_SLOPE * level
                      && !BoardRoad.trackBed(scene, scene.tile(site.coords()))) {
                    float[] retained = new float[5];
                    for (int k = 0; k < retained.length; k++) { retained[k] = lerp(wallTop, highest, k / 4f); }
                    kept = clearance(edge, t, site, road, gx, gy, retained, retained.length);
                }
                if (moved * scale > Math.hypot(free[0], free[1])) {
                    free = new float[] { gx * moved * scale, gy * moved * scale, headroom };
                    rim = wallTop;
                }
                if (kept * scale > Math.hypot(walled[0], walled[1])) {
                    walled = new float[] { gx * kept * scale, gy * kept * scale, headroom };
                }
            }
        }
        if (Math.hypot(free[0], free[1]) - Math.hypot(walled[0], walled[1]) < BoardRoadBank.VERGE * scale) {
            return new Station(free, free, null);
        }
        Vector3 foot = naturalPoint(edge, t, bottom), wall = naturalPoint(edge, t, rim);
        return new Station(free, walled, new float[] { foot.x + walled[0], foot.y + walled[1], wall.x + walled[0], wall.y + walled[1] });
    }

    /** How far, in tile pixels, a band moves along (gx, gy) for its points at these heights to clear a road's verge. */
    private float clearance(Edge edge, float t, Site site, BoardRoad road, float gx, float gy, float[] heights, int count) {
        float scale = BoardGeometry.hexScale(), clear = BoardRoad.SHOULDER + BoardRoadBank.VERGE;
        float[] xs = new float[count], ys = new float[count];
        for (int k = 0; k < count; k++) {
            Vector3 q = naturalPoint(edge, t, heights[k]);
            xs[k] = (q.x - site.x()) / scale;
            ys[k] = (q.y - site.y()) / scale;
        }
        float moved = 0;
        for (int pass = 0; pass < 3; pass++) {
            float need = 0;
            for (int k = 0; k < count; k++) { need = Math.max(need, clear - road.distance(xs[k] + gx * moved, ys[k] + gy * moved)); }
            if (need <= .01f) { break; }
            moved = Math.min(ROAD_PUSH, moved + need);
        }
        return moved;
    }

    /** Whether a site's road ends on the step's top towards a hex around these corners (see {@link #ends}). */
    private boolean landing(Site site, Corner[] ends) { return landing(site, ends, false); }

    /**
     * Whether a site's road ends on the step's top towards a hex around these corners; with {@code abutted}, onto a
     * built deck's level end, which stands on an abutment ({@link BoardBridgeFooting#abutted}).
     */
    private boolean landing(Site site, Corner[] ends, boolean abutted) {
        for (Corner end : ends) {
            // The board ends at its edge: no road ends on a step there.
            if (Arrays.asList(end.around).contains(null)) { continue; }
            for (Site other : end.around) {
                if (!other.coords().equals(site.coords()) && landing(site, other, abutted)) { return true; }
            }
        }
        return false;
    }

    /**
     * Where a site's road ending at {@code corner} (px, py) brings the corner out to: on the edge to a deck that lands on
     * it, half a metre past the deck's side ({@link BoardBridgeFooting#abutmentHalfWidth}), where the footing looks for
     * the bank under the deck's corners (BoardBridgeFooting#falls), so the bank still carries them; the corner itself
     * where the road ends over a drop with no deck. Null where it ends at no hex round the corner.
     */
    private float[] deckSide(Site site, Corner corner, float px, float py) {
        for (Site deck : corner.around) {
            if (deck == null || deck == site || !landing(site, deck, false)) { continue; }
            var tile = scene.tile(deck.coords());
            int toBank = deck.coords().direction(site.coords());
            if (BoardBridge.feature(tile) == null || !BoardBridge.bank(tile, scene.tile(site.coords()), toBank)) {
                return new float[] { px, py };
            }
            float mx = (site.x() + deck.x()) / 2, my = (site.y() + deck.y()) / 2;
            float ux = -(deck.y() - site.y()), uy = deck.x() - site.x(), length = (float) Math.hypot(ux, uy);
            float s = ((px - mx) * ux + (py - my) * uy) / length;
            float reach = Math.signum(s)
                  * Math.min(Math.abs(s), BoardBridgeFooting.abutmentHalfWidth(tile, toBank) + metres(.5f));
            return new float[] { mx + ux / length * reach, my + uy / length * reach };
        }
        return null;
    }

    /** Whether a site's road ends on the step's top towards its neighbour other (#landing(Site, Corner[], boolean)). */
    private boolean landing(Site site, Site other, boolean abutted) {
        int exits = scene.tile(site.coords()).wayExits();
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) != 0 && site.coords().translated(d).equals(other.coords())
                  && (abutted ? abutted(site, other) : ends(site, other, d))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a site's road, leaving it at d towards other, ends there on the step's top: a bridge's deck lands on it from
     * there, or the ground there lies further down than a road ramps (BoardRoadGrade#edgeLevel); either way only where
     * no road of its own runs below, from which the step recedes as usual.
     */
    private boolean ends(Site site, Site other, int d) {
        BoardScene.Tile tile = scene.tile(site.coords()), next = scene.tile(other.coords());
        return (!other.road() && BoardBridge.bank(next, tile, (d + 3) % 6)) || BoardRoadGrade.overDrop(tile, next, d);
    }

    /**
     * Whether a site's road leaves it towards roadless ground at another level around these corners: its mouth crosses
     * the step there, so the step is cut through rather than receding. A road merely passing a corner, or climbing to
     * its own next hex, leaves the step to recede.
     */
    private boolean mouth(Site site, Corner[] ends) {
        int exits = scene.tile(site.coords()).wayExits();
        for (Corner end : ends) {
            for (Site other : end.around) {
                if (other == null || other == site || other.level() == site.level() || other.road()) { continue; }
                for (int d = 0; d < 6; d++) {
                    // A road ending on the step's top has no mouth there: the step under its end stays a natural drop,
                    // which comes out to the road's end (see #landing).
                    if ((exits & 1 << d) != 0 && site.coords().translated(d).equals(other.coords())
                          && !ends(site, other, d)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The relief before any bridge deck or tunnel portal cuts it back; see {@link #cutForBridges}. */
    private Vector3 naturalPoint(Edge edge, float t, float z) {
        // Joined tops interpolate their displaced corners directly: independently easing both corners
        // can double back along a narrow plateau neck. Cast faces also keep their straight slab rims.
        boolean straight = edge.levelJoin || edge.upper != null && (edge.lower == null || edge.upper.family() == CONCRETE);
        if (t > 0 && t < 1 && straight) {
            return naturalPoint(edge, 0, z).lerp(naturalPoint(edge, 1, z), t);
        }
        float lx = edge.b.x - edge.a.x, ly = edge.b.y - edge.a.y;
        float px = edge.a.x + lx * t, py = edge.a.y + ly * t;
        float along = t * edge.length;
        filletOffset(edge.a, z, scratchA);
        filletOffset(edge.b, z, scratchB);
        float ua = edge.a.fillet > 0 ? Math.min(1, along / edge.a.fillet) : 1;
        float ub = edge.b.fillet > 0 ? Math.min(1, (edge.length - along) / edge.b.fillet) : 1;
        // An arc runs along its corner's two step edges only; across the level seam between the hexes on one side of
        // the step its bulge fades as the cubic's does, within half the seam, so the seam ends exactly at both corners.
        boolean step = edge.upper != null && edge.lower != null && edge.upper.level() != edge.lower.level();
        float[] frameA = edge.a.arc && step ? edge.a.arcFrame() : null;
        float[] frameB = edge.b.arc && step ? edge.b.arcFrame() : null;
        boolean bands = BoardGeometry.tuning().stepsBetweenTops();
        float ex = lx / edge.length, ey = ly / edge.length, sx = 0, sy = 0, slideA = 0, slideB = 0;
        // An arc runs along the edge as the corners' moves and the band's slide (below) carry it: from each corner, its
        // share of the bulge at each world unit of that run, so it meets both edges through the corner tangent.
        float moved = frameA != null || frameB != null ? movedLength(edge) : edge.length, speed = moved;
        if (bands) {
            bandOffset(edge.a, z, bandA);
            bandOffset(edge.b, z, bandB);
            if (frameA != null || frameB != null) {
                float[] ma = edge.a.move(), mb = edge.b.move();
                sx = (edge.b.x + mb[0] - edge.a.x - ma[0]) / moved;
                sy = (edge.b.y + mb[1] - edge.a.y - ma[1]) / moved;
            } else {
                sx = -edge.bandNormal.y;
                sy = edge.bandNormal.x;
                if (sx * ex + sy * ey < 0) { sx = -sx; sy = -sy; }
            }
            slideA = bandA[0] * sx + bandA[1] * sy;
            slideB = bandB[0] * sx + bandB[1] * sy;
            speed = moved + slideB - slideA;
        }
        float lengthA = frameA != null ? edge.a.arcLength(z) : 0, lengthB = frameB != null ? edge.b.arcLength(z) : 0;
        if (frameA != null) { ua = lengthA > 0 ? Math.min(1, t * speed / lengthA) : 1; }
        if (frameB != null) { ub = lengthB > 0 ? Math.min(1, (1 - t) * speed / lengthB) : 1; }
        float fa = frameA != null ? share(ua, frameA[2]) : (1 - ua) * (1 - ua) * (1 - ua);
        float fb = frameB != null ? share(ub, frameB[2]) : (1 - ub) * (1 - ub) * (1 - ub);
        float rx = scratchA[0] * fa + scratchB[0] * fb, ry = scratchA[1] * fa + scratchB[1] * fb;
        float blend = .3f * edge.length;
        float wa = 1 - smooth(along / blend), wb = 1 - smooth((edge.length - along) / blend), rest = 1 - wa - wb;
        boolean spans = edge.profiled && z >= edge.bottom() - EPSILON && z <= edge.top() + EPSILON;
        // The transition band offsets the outline as a polygon: along the edge's normal by its own band, turning into
        // each corner's band near that corner, and along the edge by a shift running evenly from one corner's to the
        // other's (along the moved edge where a corner rounds an arc). Where two seams meet, the corner's band is
        // where their offset lines cross, so the edge stays straight. Written, like the relief below, so that at a
        // corner every edge through it adds the corner's own band exactly.
        // The corner rounds also consume tangent travel; their largest slope is at the endpoint: 3 for the cubic, as
        // its frame gives it for an arc (arcSlope).
        float stretch = speed / edge.length;
        float roundedA = frameA != null ? arcSlope(frameA[2], lengthA, stretch) * (scratchA[0] * ex + scratchA[1] * ey)
              : edge.a.fillet > 0 ? 3 * (scratchA[0] * ex + scratchA[1] * ey) / edge.a.fillet : 0;
        float roundedB = frameB != null ? -arcSlope(frameB[2], lengthB, stretch) * (scratchB[0] * ex + scratchB[1] * ey)
              : edge.b.fillet > 0 ? -3 * (scratchB[0] * ex + scratchB[1] * ey) / edge.b.fillet : 0;
        float bx = 0, by = 0, tangentRoom = 1 - Math.max(0, Math.max(roundedA, roundedB));
        if (bands) {
            float own = spans && edge.room > 0 ? band(edge.upper, edge.lower, z, px, py) * rest : 0;
            tangentRoom += Math.min(0, (slideB - slideA) / edge.length);
            float slide = slideA * (1 - t) + slideB * t - slideA * wa - slideB * wb;
            bx = edge.bandNormal.x * own + bandA[0] * wa + bandB[0] * wb + sx * slide;
            by = edge.bandNormal.y * own + bandA[1] * wa + bandB[1] * wb + sy * slide;
        }
        cornerOffset(edge.a, z, scratchA);
        cornerOffset(edge.b, z, scratchB);
        float d = 0;
        if (rest > 0 && spans) {
            // The relief is a function of where the face stands, so a band moves it along instead of squeezing it.
            float fx = px + bx, fy = py + by;
            d = profile(fx, fy, z, edge.bottom(), edge.top(), edge.drop, edge.upper.family(), edge.footPinned,
                  edge.rimPinned, edge.room > 0);
            if (d > 0) { d *= held(edge, t); }
        }
        // A corner's relief slides the edges through it along themselves too. A band can shorten the available edge,
        // so its relief needs a longer fade to keep adjacent columns from reversing into a thin folded strip.
        // Keep each fade inside this edge so both shared corner positions remain exact.
        tangentRoom = Math.max(.01f, tangentRoom);
        float ta = scratchA[0] * ex + scratchA[1] * ey, tb = scratchB[0] * ex + scratchB[1] * ey;
        float sa = 1 - smooth(along / Math.min(edge.length, Math.max(blend, 1.6f * Math.abs(ta) / tangentRoom)));
        float sb = 1 - smooth((edge.length - along) / Math.min(edge.length, Math.max(blend, 1.6f * Math.abs(tb) / tangentRoom)));
        // A shore's move of a corner runs out evenly along each edge through it, so those edges stay straight.
        float[] ma = edge.a.move(), mb = edge.b.move();
        float qx = ma[0] * (1 - t) + mb[0] * t, qy = ma[1] * (1 - t) + mb[1] * t;
        // Local relief moves across the face, never along it: noisy radial/rounded normals can reverse adjacent
        // columns into a loop at a broken rim. Shared corners still supply their exact offsets and bounded slides.
        // Corners moved unevenly turn the face, and its normal with it; pushed along the edge's own, a deep recess
        // would run along the face beside the corner and draw a facet almost edge-on.
        float nx = edge.nx, ny = edge.ny;
        float run = d != 0 && (ma[0] != mb[0] || ma[1] != mb[1]) ? movedLength(edge) : 0;
        if (run > 0) {
            float mx = (lx + mb[0] - ma[0]) / run, my = (ly + mb[1] - ma[1]) / run;
            float side = Math.signum(edge.nx * my - edge.ny * mx);
            nx = my * side;
            ny = -mx * side;
        }
        return new Vector3(px + rx + bx + qx + nx * d * rest + scratchA[0] * wa + ta * ex * (sa - wa)
                    + scratchB[0] * wb + tb * ex * (sb - wb),
              py + ry + by + qy + ny * d * rest + scratchA[1] * wa + ta * ey * (sa - wa)
                    + scratchB[1] * wb + tb * ey * (sb - wb), z);
    }

    /**
     * Natural rims keep their formations, but no neighbouring hillside may protrude through a bridge deck, and the
     * face a tunnel portal is set into stays flat. Carriageways reserve their own ground (BoardRoadCut).
     */
    private Vector3 cutForBridges(Edge edge, float t, Vector3 point) {
        float lx = edge.b.x - edge.a.x, ly = edge.b.y - edge.a.y, z = point.z;
        float px = edge.a.x + lx * t, py = edge.a.y + ly * t;
        // Keep the constructed shared boundary; clearance trims only natural relief beyond that fitted base.
        var fitA = coast.shift(edge.a.key);
        var fitB = coast.shift(edge.b.key);
        px += lerp(fitA.x(), fitB.x(), t);
        py += lerp(fitA.y(), fitB.y(), t);
        float keep = 1, cast = Float.NEGATIVE_INFINITY;
        Set<BoardTunnel> walled = new HashSet<>();
        // A corner is shared by three edges and must move alike on each: there only the corner's own hexes decide.
        Corner at = t == 0 ? edge.a : t == 1 ? edge.b : null;
        for (Corner end : at != null ? new Corner[] { at } : new Corner[] { edge.a, edge.b }) {
            for (Site site : end.around) {
                if (site == null) { continue; }
                var source = scene.tile(site.coords());
                var bridge = BoardBridge.feature(source);
                // Cut a higher hillside down through deck height; retaining its toe at the road level
                // leaves a sloping triangle through the lane. The landing's own level bank stays intact.
                if (bridge != null && BoardBridge.family(source) != BoardBridge.Family.NATURAL
                      && !BoardBridge.guideway(source)
                      && z >= (source.elevation() + bridge.elevation()) * BoardGeometry.level() - EPSILON) {
                    float level = source.elevation() + bridge.elevation();
                    boolean reserve = false, higher = edge.upper != null && edge.upper.level() > level;
                    for (Site beside : at != null ? at.around : edge.a.around) {
                        if (beside == null || beside.level() <= level) { continue; }
                        higher |= at != null;
                        if (beside.family() == CONCRETE) { continue; }
                        if (at != null) { reserve = true; continue; }
                        for (Site other : edge.b.around) { reserve |= other == beside; }
                    }
                    if (reserve || higher) {
                        var deck = decks.computeIfAbsent(site.coords(), key -> slab(BoardBridge.deck(scene, source), key));
                        // The gate a deck's road lands across keeps its boundary: the bank's road
                        // reserves its mouth and the footing stands on it; moved out of the slab, it would run
                        // across the bank's ramp. A corner is the gate's where the road lands on any hex round it.
                        boolean gate = false;
                        Edge gateEdge = null;
                        for (Site bank : at != null ? at.around
                              : new Site[] { site == edge.lower ? edge.upper : site == edge.upper ? edge.lower : null }) {
                            if (bank == null || bank == site || !BoardBridge.road(source, scene.tile(bank.coords()),
                                  source.coords().direction(bank.coords()))) { continue; }
                            gate = true;
                            for (int e = 0; at != null && e < 6; e++) {
                                if (neighbor(site, e) == bank) { gateEdge = edge(site, e); }
                            }
                        }
                        // Wide decks can cross the nominal corner itself; reserve their full slab before
                        // trimming natural relief against that same canonical boundary on both sides. A gate's corner
                        // keeps that boundary on the gate's own line, so the gate runs on straight to it beside the
                        // slab, not across it.
                        if (reserve && (at != null || !gate)) {
                            var clear = cutBoundary(deck, site, px, py);
                            if (clear != null) {
                                px = clear.x;
                                py = clear.y;
                                if (gateEdge != null) {
                                    Corner from = gateEdge.a == at ? gateEdge.b : gateEdge.a;
                                    float gx = at.x - from.x, gy = at.y - from.y;
                                    float share = ((px - from.x) * gx + (py - from.y) * gy) / (gx * gx + gy * gy);
                                    px = from.x + gx * share;
                                    py = from.y + gy * share;
                                    keep = 0;
                                }
                            }
                        }
                        keep = Math.min(keep, roadClearance(deck.road(), deck.clear(), site.x(), site.y(), px, py,
                              point.x - px, point.y - py));
                    }
                }
                if (!site.road()) {
                    if (site.family() == CONCRETE) { cast = Math.max(cast, site.level() * BoardGeometry.level()); }
                    continue;
                }
                // A tunnel portal's wings stand on the lattice line: the face it is set into stays flat to its top, and
                // the rock above and beside it eases back to the natural face as a buttress round the portal.
                for (BoardTunnel portal : portals.computeIfAbsent(site.coords(), key -> BoardTunnel.entrances(scene, scene.tile(key)))) {
                    float above = (z - portal.origin().z) / BoardGeometry.hexScale();
                    keep = Math.min(keep, Math.max(smooth(portal.beside(px, py) / PORTAL_SIDE),
                          smooth((above - BoardTunnel.ROOF - 3) / PORTAL_RISE)));
                    walled.add(portal);
                }
            }
        }
        if (keep < 1) {
            point.x = lerp(px, point.x, keep);
            point.y = lerp(py, point.y, keep);
        }
        // Beside its approach natural ground stands behind a portal's wing walls; a cast wall stays straight.
        for (BoardTunnel portal : walled) {
            if (cast <= portal.origin().z) { portal.clearWings(point); }
        }
        return point;
    }

    /** Over how many tile pixels the rock round a tunnel portal eases back to its natural face, above and beside it. */
    private static final float PORTAL_RISE = 36, PORTAL_SIDE = 16;

    /** A deck's carriageway and how far natural relief stands back from it. */
    private record Slab(BoardRoad road, float clear) { }

    /**
     * A road and rail corridor is wider than its gates and runs out over their corners, so its curbs and rails take its
     * whole shoulder and the relief stands back from them; a plain deck keeps a road's verge.
     */
    private static Slab slab(BoardBridge.Deck deck, Coords coords) {
        return new Slab(deck.slab(coords),
              (deck.corridor().sides() != 0 ? BoardBridge.SHOULDER : BoardRoad.SHOULDER) + ROAD_MARGIN);
    }

    /** Move a shared bank outside its deck's slab. */
    private static Vector3 cutBoundary(Slab slab, Site site, float px, float py) {
        BoardRoad deck = slab.road();
        float scale = BoardGeometry.hexScale(), clear = slab.clear();
        float x = (px - site.x()) / scale, y = (py - site.y()) / scale;
        if (deck.distance(x, y) >= clear) { return null; }
        // Distance gradients also handle bent decks and their end planes; every incident edge obtains
        // the same answer at a shared corner, independently of which hillside asks for it.
        for (int step = 0; step < 3; step++) {
            float distance = deck.distance(x, y);
            if (distance >= clear - .0001f) { break; }
            float dx = deck.distance(x + .01f, y) - deck.distance(x - .01f, y);
            float dy = deck.distance(x, y + .01f) - deck.distance(x, y - .01f);
            float length = (float) Math.hypot(dx, dy);
            if (length < .00001f) { break; }
            x += dx / length * (clear - distance + .001f);
            y += dy / length * (clear - distance + .001f);
        }
        return new Vector3(site.x() + x * scale, site.y() + y * scale, 0);
    }

    private List<BoardBridge.Approach> bridgeApproaches(Coords coords) {
        return bridgeApproaches.computeIfAbsent(coords, key -> BoardBridge.approaches(scene, scene.tile(key)));
    }

    /**
     * Cut only material within {@code clear} of the road, its shoulder and verge (see {@link #ROAD_MARGIN}); slopes
     * receding away from the road, and all rock clear of it, keep their full natural relief.
     */
    static float roadClearance(BoardRoad road, float clear, float cx, float cy, float x, float y, float dx, float dy) {
        float scale = BoardGeometry.hexScale();
        float px = (x - cx) / scale, py = (y - cy) / scale;
        dx /= scale;
        dy /= scale;
        if (road.distance(px, py) <= clear) { return 0; }
        if (road.distance(px + dx, py + dy) >= clear) { return 1; }
        float low = 0, high = 1;
        for (int i = 0; i < 14; i++) {
            float mid = (low + high) * .5f;
            if (road.distance(px + dx * mid, py + dy * mid) >= clear) { low = mid; }
            else { high = mid; }
        }
        return low;
    }

    /** Hex-scale verge beyond a road's shoulder that no neighbouring rock may enter. */
    private static final float ROAD_MARGIN = 1;

    // ---- Wall profile ----------------------------------------------------------------------------------------

    /**
     * The height of a step for its landforms, in world units: from the upper hex's level, a water hex's surface, down
     * to the ground units stand on below it, which in a water hex is its bed, since tanks and meks do not walk on
     * water. Land a level above water a level deep takes a two-level slope; land two levels above it stands over a
     * three-level cliff, as does water three levels above land.
     * An authored cliff-top exit uses at least three levels for its landforms, so even a one-level drop has a cliff
     * profile and rock material. The actual rim and foot heights still come from the two sites' levels.
     */
    private static float drop(Site upper, Site lower) {
        return levels(upper.level(), lower.level(), lower.depth(), upper.cliffTopExits(), upper.coords(), lower.coords())
              * BoardGeometry.level();
    }

    /** {@link #drop} in levels: the two hexes' levels, the lower one's water depth and the upper one's cliff-top sides. */
    private static int levels(int upper, int lower, int depth, int cliffTopExits, Coords from, Coords to) {
        int levels = upper - lower + depth;
        if (upper > lower && cliffTopExits != 0 && (cliffTopExits & (1 << from.direction(to))) != 0) {
            levels = Math.max(3, levels);
        }
        return levels;
    }

    /** {@link #drop} in levels from {@code upper} down to the next hex {@code lower}: to its ground, or its bed under water. */
    static int levels(BoardScene.Tile upper, BoardScene.Tile lower) {
        return levels(upper.groundLevel(), lower.groundLevel(), depth(lower), upper.cliffTopExits(), upper.coords(),
              lower.coords());
    }

    /** A hex's water depth for its landforms: its bed lies that many levels below its surface. */
    private static int depth(BoardScene.Tile tile) {
        return tile.liquid().present() ? Math.max(0, tile.waterDepth()) : 0;
    }

    /** 0 for drops up to two levels, 1 from three levels: the user-visible prominence of rim formations. */
    private static float prominence(float drop) {
        return smooth((drop / BoardGeometry.level() - 2.15f) / .85f);
    }

    /**
     * Room a step takes on each side of its edge, in world units: none unless hex transitions or padding are on, and
     * only between detailed grounds, water among them. Special artwork, buildings and roads keep their outline
     * on the hex edge, natural ground carrying a road down onto a concrete one sloping down onto it there (a cliff beside
     * stands at the paving's edge); water keeps its own where
     * {@link #band} says. Pits have no ground to carry a talus apron. A bank a built deck lands on stands on its edge,
     * flush with the deck's abutment set into it (#abutted, #held).
     * Dry cliffs need less transition width than gentle slopes, leaving more of their plateau and ridge connections.
     */
    private static float room(Site upper, Site lower) {
        float width = BoardGeometry.tuning().stepsBetweenTops() && upper != null && lower != null
              && upper.level() != lower.level() && upper.detailed() && lower.detailed()
              && !(upper.liquid() && lower.liquid())
              && !upper.fixedRoad() && !(lower.fixedRoad() && (upper.family() == CONCRETE || !upper.road()))
              && !upper.ultraSublevel() && !lower.ultraSublevel()
              && !(upper.family() == CONCRETE && wall(upper, lower))
              && !wetCliff(upper, lower) && !canal(upper, lower) && !abutted(upper, lower)
              ? stepRoom() : 0;
        if (width > 0 && BoardGeometry.tuning().padding() == 0 && !upper.fixedOutline() && !lower.fixedOutline()) {
            // Keep the existing cliff relief and shared corners; reduce only the room spent on its inset and apron.
            width *= lerp(1, .6f, prominence(drop(upper, lower)));
        }
        return width;
    }

    /**
     * Whether this hex is a canal: water among paving that stands above some of it. It is lined in concrete round a
     * channel of one width (BoardSurface#canalField) and walled on its edges down to the lower paving ({@link #canal}).
     */
    boolean canalHex() { return canal(tile); }

    /** Whether the water of a hex is a canal (see {@link #canalHex}). */
    private boolean canal(BoardScene.Tile start) {
        return canals.computeIfAbsent(start.coords(), key -> {
            // The whole body of water or none of it: a canal's mouths meet only canals, at its one width.
            Set<Coords> seen = new HashSet<>(List.of(key));
            ArrayDeque<BoardScene.Tile> pending = new ArrayDeque<>(List.of(start));
            while (!pending.isEmpty()) {
                BoardScene.Tile water = pending.remove();
                if (!canalWater(water) || seen.size() > CANAL_REACH) { return false; }
                for (int d = 0; d < 6; d++) {
                    BoardScene.Tile next = scene.tile(water.coords().translated(d));
                    if (next != null && water.joins(next, d) && seen.add(next.coords())) { pending.add(next); }
                }
            }
            return true;
        });
    }

    /**
     * Shades a canal's lining and floor (BoardSurface#canalBed) as poured concrete ground under its water: each face keeps
     * its own normal, and the lining a fixed texture frame facing out of it, as walls have, so the concrete lies across
     * the slope instead of streaking down it.
     */
    void lining(List<BoardSurface.Face> faces) {
        for (BoardSurface.Face face : faces) {
            if (face.finish() != BoardSurface.Finish.BANK) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            float projection = normal.z > .99f ? Float.NaN : (float) Math.atan2(normal.y, normal.x);
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                shades.put(p, new Shade(normal, Kind.GROUND, 1, self.level(), 99, 99, 0, projection));
            }
        }
    }

    /** Shades the faces a canal deck's ramp cuts into its concrete (BoardSurface#cutRamps) as poured walls, as its own walls. */
    void castWalls(List<BoardSurface.Face> faces) {
        float m = metres(1);
        for (BoardSurface.Face face : faces) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            float projection = (float) Math.atan2(normal.y, normal.x);
            float bottom = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
            float top = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                shades.put(p, new Shade(normal, Kind.CLIFF, 1, 0, (p.z - bottom) / m, (top - p.z) / m, .5f, projection));
            }
        }
    }

    /** Whether a water hex among paving stands above some of it (see {@link #canalHex}). */
    private boolean canalWater(BoardScene.Tile water) {
        if (!water.liquid().present() || water.liquid().molten() || water.frozen()
              || waterFamily(scene, water) != BoardScene.Surface.CONCRETE) { return false; }
        for (int d = 0; d < 6; d++) {
            BoardScene.Tile next = scene.tile(water.coords().translated(d));
            if (next != null && !next.liquid().present() && next.surface() == BoardScene.Surface.CONCRETE
                  && next.groundLevel() < water.groundLevel()) { return true; }
        }
        return false;
    }

    /** The most hexes a canal's water takes; a larger body is a basin of open water. */
    private static final int CANAL_REACH = 64;
    /** Whether each water hex asked about is a canal. */
    private final Map<Coords, Boolean> canals = new HashMap<>();

    /**
     * Water standing above paving: a canal's wall on the edge. Where a deck's road comes down onto the paving, its bank
     * recedes under the deck instead ({@link #deckBay}).
     */
    private static boolean canal(Site upper, Site lower) {
        return upper != null && lower != null && upper.liquid() && !lower.liquid() && lower.family() == CONCRETE
              && upper.level() > lower.level() && (upper.deckRoads() & 1 << upper.coords().direction(lower.coords())) == 0;
    }

    private static boolean wetCliff(Site upper, Site lower) {
        return upper != null && lower != null && !upper.liquid() && lower.liquid()
              && upper.level() >= lower.level() && (upper.family() == CONCRETE
              || tuning().cliffsIntoWater() && upper.sculpted() && upper.detailed() && lower.sculpted() && lower.detailed()
              && upper.level() > lower.level() && wall(upper, lower));
    }

    /**
     * Whether a step uses a cliff profile: an authored cliff-top exit or a drop of three levels or more.
     */
    private static boolean wall(Site upper, Site lower) {
        return drop(upper, lower) > 2.5f * BoardGeometry.level();
    }

    /**
     * How far a step's face at height z stands out from its edge into the lower hex (negative: back in the upper one):
     * its room at the foot, following {@link #transition} up to its room back at the rim. A slope down to water runs
     * on under it to the bed: land a level above water a level deep takes a two-level slope, which meets the water on
     * the edge. Where a wall of three levels or more stands above water, or water three levels above land, the water
     * keeps its outline on the edge, so the land takes a step half as wide, all on its own side; so does land above
     * paving. Water up to two levels above land keeps its outline too, and the land takes an ordinary slope's whole width,
     * receding from the roads on it as any slope does.
     */
    private static float band(Site upper, Site lower, float z, float x, float y) {
        float room = room(upper, lower);
        if (room <= 0) { return 0; }
        boolean wall = wall(upper, lower);
        float bottom = (lower.level() - (wall ? 0 : lower.depth())) * BoardGeometry.level();
        float top = upper.level() * BoardGeometry.level();
        if (upper.family() == CONCRETE) {
            return room * (1 - 2 * Math.clamp((z - bottom) / (top - bottom), 0, 1));
        }
        float shoulder = noise(x / metres(35) + 11.7f, y / metres(35) - 5.2f);
        float t = transition(Math.clamp(z, bottom, top), bottom, top, drop(upper, lower), x, y, shoulder);
        if (!wall && !lower.liquid() && upper.family() == GRASS
              && upper.level() - lower.level() == 2) {
            // Two earth faces share a shallow grassy shoulder. Keep the canonical rim/foot and a monotone
            // profile: corners, support, picking and grass roots all follow this same finished surface. The faces
            // stay under about 75 degrees, nearer how soil stands: near-vertical ones cast the rim's shadow far down
            // the bank. A weaker shoulder would let them lean back further but grows too little grass.
            float height = Math.clamp((z - bottom) / (top - bottom), 0, 1);
            float bench = .2f + .35f * shoulder;
            t = 1 - 2 * (height + bench * (float) Math.sin(4 * Math.PI * height) / (4 * (float) Math.PI));
        }
        return wall && lower.liquid() || !lower.liquid() && kept(lower) ? room / 2 * (t - 1)
              : upper.liquid() ? (wall ? room / 2 : room) * (t + 1) : room * t;
    }

    /**
     * Where a transition puts a step's face at height z, as a fraction of its room: 1 at the foot, out in the lower hex,
     * and -1 at the rim, back in the upper one. Up to two levels a slope, gentler toward the rim and the foot than in its
     * middle; from three a concave talus that rises from its toe to a face standing the whole room back from the edge.
     * Broad variation changes the shoulder and deposit height without moving either endpoint or adding mesh rows.
     * {@code drop} is the step's height for its landforms (see {@link #drop(Site, Site)}).
     */
    private static float transition(float z, float bottom, float top, float drop, float x, float y, float shoulder) {
        float span = top - bottom;
        float t = Math.clamp((z - bottom) / span, 0, 1);
        float slope = 1 - 2 * (t + (.25f + .35f * shoulder) * (float) Math.sin(2 * Math.PI * t) / (2 * (float) Math.PI));
        float u = Math.clamp((z - bottom) / talusHeight(span, x, y), 0, 1);
        float cliff = 1 - 2 * (1 - (1 - u) * (1 - u));
        return lerp(slope, cliff, prominence(drop));
    }

    /** The deposit's height varies continuously along a cliff, in both its main slope and its rocky relief. */
    private static float talusHeight(float drop, float x, float y) {
        float cell = metres(9);
        return Math.min(.3f * drop, metres(6)) * (.7f + .6f * noise(x / cell - 3.3f, y / cell + 6.6f));
    }

    /**
     * Outward displacement of a cliff face at base point (px, py) and height z, for a cliff spanning [bottom, top]
     * whose landforms are those of a step {@code drop} high (see {@link #drop(Site, Site)}).
     * Positive values stand out into the lower hex. Caprock, talus, jointed rock masses, fractures, bedding and
     * broad buttresses are all functions of world position, so adjoining walls continue each other.
     */
    private static float profile(float px, float py, float z, float bottom, float top, float drop, int family,
          boolean footPinned, boolean rimPinned, boolean banded) {
        // Poured concrete keeps flat cast faces.
        if (family == CONCRETE) { return 0; }
        Rock g = ROCKS[family];
        float m = metres(1);
        float big = prominence(drop);
        float amplitude = .6f + .4f * smooth((drop / BoardGeometry.level() - 1) / 2);
        // Spend the existing displacement on broad formations; fine cracks remain in the material.
        float masses = banded ? 1 + .4f * big : 1;
        float h = z - bottom, d = top - z;
        float mx = px / m, my = py / m, mz = z / m;
        // One oblique joint system continues across hexes. Longer masses along its strike form ribs and fins,
        // rather than an unrelated set of circular lobes on each face. Reuse the same joints at the rim and foot.
        float structure = banded ? big : 0;
        float jx = lerp(mx, (.8f * mx + .6f * my) / 1.35f, structure);
        float jy = lerp(my, (.8f * my - .6f * mx) / .8f, structure);
        float[] cell = CELL.get();
        float joint = 0, column = 0;
        if (structure > 0) {
            // Major joints are wider than the secondary blocks and persist through several beds. Slow variation
            // with absolute height keeps tall walls from becoming identical extruded columns.
            cells(jx / (g.cellWidth() * 1.8f), jy / (g.cellWidth() * 1.8f), 3.5f + mz / (g.cellHeight() * 4), cell);
            column = cell[0];
            joint = (1 - smooth(cell[1] / .12f)) * structure;
        }
        // Resistant beds leave occasional ledges, not a continuous mushroom lid. A missing cap also loses its
        // undercut, so the same intact mass can continue all the way to the rim.
        float capScale = lerp(9, 19, structure);
        float capNoise = noise(mx / capScale + 17.3f, my / capScale - 4.1f);
        float capHeight = m * lerp(.9f, 1.5f, big) * lerp(1, .7f + .6f * capNoise, big);
        float capPresence = smooth((capNoise - lerp(.42f, .38f, big)) / lerp(.2f, .25f, big));
        float capOut = m * lerp(.28f, 1.1f, big) * capPresence
              * (.6f + .8f * capNoise) * g.cap() * (1 - .9f * joint);
        float under = m * lerp(.2f, .45f, big) * (.4f + capNoise) * g.cap()
              * (1 - .6f * joint) * lerp(1, capPresence, big);
        float t = d / capHeight;
        float cap = capOut * capShape(t) - under * bump((t - 1.6f) / .75f);
        // Soft ground's low steps are banks of the soil mantle: little jointing, leaning back from the rim. A
        // transition's low steps are slopes of every ground's mantle, with little of the rock showing through.
        float firm = lerp(banded ? Math.min(g.bank(), .35f) : g.bank(), 1, big);
        // A transition's band lays the bank back itself.
        float lean = banded ? 0 : g.lean() * (1 - big) * d;
        // The face stands back from the hex edge, further on taller cliffs; the talus apron fills the gap it leaves.
        float retreat = g.recess() * m * (.35f + .65f * noise(mx / 14 - 8.2f, my / 14 + 2.9f))
              * Math.clamp(drop / (12 * m), .5f, 1.6f) * (banded ? .4f : 1);
        // Talus: a concave apron of fallen rock from the retreated face out past the edge; lumpy above its contact line.
        float talusNoise = noise(mx / 6 + 5.1f, my / 6 + 9.7f);
        float talusHeight = talusHeight(drop, px, py);
        // A transition's band lays the apron itself; its relief keeps only the lumpy toe of the scree.
        float reach = Math.min(m * .9f + .1f * drop, m * 3.2f) * (.45f + .75f * talusNoise) * (banded ? .35f : 1);
        float slope = Math.max(0, 1 - h / talusHeight);
        float foot = 0;
        if (!footPinned && slope > 0) {
            float scree = 1 + .55f * gradient(mx / 3f + 2.2f, my / 3f - 7.1f, mz / 2.2f)
                  * smooth(h / Math.max(m * .5f, talusHeight * .3f));
            foot = (retreat + reach) * (float) Math.pow(slope, 1.6) * scree;
        }
        // Body: broad buttresses, jointed masses split by dark fractures, and bedding ledges.
        cells(jx / g.cellWidth(), jy / g.cellWidth(), mz / g.cellHeight(), cell);
        float body = (g.buttress() * lerp(1, 1.5f, structure)
              * gradient(jx / lerp(12, 28, structure) + 3.3f, jy / lerp(12, 22, structure) + .7f)
              + g.cells() * lerp(cell[0], column, .65f * structure)
              - g.fractures() * (1 - smooth(cell[1] / .16f)) * lerp(1, .65f, structure)) * masses
              + g.strata() * strata(mx, my, mz, g.bedding())
              + .18f * gradient(mx / 1.3f, my / 1.3f, mz / 1.3f);
        // The lip keeps part of the joint pattern, so the rim outline is broken rather than smooth; deep rims more so.
        float rimBody = lerp(.3f, banded ? 1 : .75f, big);
        float taper = smooth(h / Math.max(m * 1.0f, talusHeight * .6f))
              * (rimBody + (1 - rimBody) * smooth(d / (capHeight * 1.2f)));
        // Upper rock retreats gradually instead of balancing a broad lid on a narrower stem. This uses the same
        // mass field and remains bounded by the existing cliff envelope and its road/shore contacts.
        float upperRetreat = m * g.recess() * structure * (.4f + .6f * capNoise) * (1 - .35f * column)
              * smooth((h / (top - bottom) - .2f) / .8f);
        float result = cap + foot + lean + (body * m * amplitude * taper - retreat - upperRetreat) * firm;
        if (structure > 0) {
            // The same clefts break the cap, dissect the face and feed its talus. Quiet stretches retain a solid
            // shelf; a failed joint must not leave a continuous lid over the recess below it.
            float cut = joint * (.4f + .6f * noise(mx / 11 + 3.7f, my / 11 - 1.9f));
            result -= m * 2.2f * cut * smooth(h / Math.max(m, talusHeight * .8f))
                  * lerp(.5f, 1, smooth(d / (capHeight * 2)));
            result += m * 1.4f * cut * slope * slope;
        }
        if (footPinned) { result *= smooth(h / (m * 1.5f)); }
        if (rimPinned) { result *= smooth(d / (m * 1.5f)); }
        return softClamp(result, banded);
    }

    /** The smaller of a and b, with the corner between them rounded over a width k. */
    static float smoothMin(float a, float b, float k) {
        float h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k * .25f;
    }

    private static float capShape(float t) {
        if (t < 0) { return .5f; }
        if (t < .25f) { return lerp(.5f, 1, smooth(t / .25f)); }
        return lerp(1, 0, smooth((t - .25f) / 1.1f));
    }

    private static float bump(float t) {
        float x = Math.abs(t);
        return x >= 1 ? 0 : (1 - x * x) * (1 - x * x);
    }

    private static final ThreadLocal<float[]> CELL = ThreadLocal.withInitial(() -> new float[29]);

    /**
     * Jointed rock masses: a smoothed 3D Voronoi in cell units. out[0] is the blended protrusion of the nearest masses
     * in [-1, 1]; out[1] approximates the distance to the nearest joint between two masses. out[2..] is scratch.
     */
    private static void cells(float x, float y, float z, float[] out) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float nearest = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
        int n = 0;
        // Squared distances first: only the masses within the blend of the nearest need a root and a protrusion.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = ix + dx, cy = iy + dy, cz = iz + dz;
                    int h = hashInt(cx, cy, cz);
                    float fx = cx + .15f + .7f * ((h & 1023) / 1023f) - x;
                    float fy = cy + .15f + .7f * (((h >>> 10) & 1023) / 1023f) - y;
                    float fz = cz + .15f + .7f * (((h >>> 20) & 1023) / 1023f) - z;
                    float d2 = fx * fx + fy * fy + fz * fz;
                    out[2 + n++] = d2;
                    if (d2 < nearest) {
                        second = nearest;
                        nearest = d2;
                    } else if (d2 < second) {
                        second = d2;
                    }
                }
            }
        }
        nearest = (float) Math.sqrt(nearest);
        float reach = (nearest + .16f) * (nearest + .16f), sum = 0, weight = 0;
        for (int i = 0; i < n; i++) {
            if (out[2 + i] > reach) { continue; }
            float w = 1 - ((float) Math.sqrt(out[2 + i]) - nearest) / .16f;
            w *= w;
            w *= w;
            int g = hashInt(iz + i / 9 - 1, ix + i % 3 - 1, iy + i / 3 % 3 - 1);
            float r = ((g & 0xffff) / 32767.5f) - 1;
            // Bias toward the extremes: most masses stand clearly in or out.
            sum += w * r * (1.35f - .35f * Math.abs(r));
            weight += w;
        }
        out[0] = sum / weight;
        out[1] = ((float) Math.sqrt(second) - nearest) * .5f;
    }

    /** Irregular bedding: alternating hard and soft layers of varying thickness, gently undulating. */
    private static float strata(float x, float y, float z, float bedding) {
        float u = bedding(x, y, z, bedding);
        int bed = (int) Math.floor(u);
        float f = u - bed;
        float hardness = hash(bed * 17 + 3, 91) * 2 - 1;
        // Hard beds stand out with a steep lower edge and a sloping top where sand or snow can lie.
        float shape = smooth(f / .07f) * smooth((1 - f) / .3f);
        return hardness * shape;
    }

    /** Continuous bed coordinate at a point in metres: its integer part numbers the bed. */
    private static float bedding(float x, float y, float z, float bedding) {
        return z / bedding + .8f * gradient(x / 30, y / 30) + .2f * gradient(x / 7 + 3.1f, y / 7 - 1.7f);
    }

    /** The height of the bedding plane nearest a world point, each bed split into {@code split} layers. */
    static float bedPlane(Vector3 p, int family, int split) {
        float m = metres(1), b = ROCKS[family].bedding();
        float u = bedding(p.x / m, p.y / m, p.z / m, b) * split;
        return p.z + (Math.round(u) - u) * b / split * m;
    }

    /** The family's joint blocks against the coarsest cliffs': finer jointing breaks rock into smaller blocks. */
    static float jointScale(int family) { return Math.min(1, ROCKS[family].cellWidth() / 7.5f); }

    /** Hardness in [0, 1] of the bed holding a world point: the colour of the same bed whose ledge stands out. */
    static float bed(Vector3 p, int family) {
        float m = metres(1);
        return hash((int) Math.floor(bedding(p.x / m, p.y / m, p.z / m, ROCKS[family].bedding())) * 17 + 3, 91);
    }

    /** Bounds a wall's relief smoothly; a transition's clefts may cut two metres deeper. */
    private static float softClamp(float value, boolean banded) {
        float inward = BoardGeometry.width() * .15f + (banded ? metres(2) : 0), outward = BoardGeometry.width() * .12f;
        return value >= 0 ? outward * (float) Math.tanh(value / outward) : -inward * (float) Math.tanh(-value / inward);
    }

    // ---- Top surface -----------------------------------------------------------------------------------------

    /** Tops stay at the board level; fine surface relief belongs to the material, not the mesh. */
    float groundHeight(float x, float y) {
        return tile.elevation() * BoardGeometry.level();
    }

    Vector3 groundNormal(Vector3 point) {
        return new Vector3(0, 0, 1);
    }

    /**
     * Drops and rises along the edges of nearby hexes at this hex's level.
     * Arrays of [ax, ay, bx, by, levels, room] segments; levels is the step's
     * height and room how far a cliff's transition moves its rim and foot from the edge, so distances are measured to
     * the rim or foot itself.
     */
    private void segments() {
        List<float[]> drop = new ArrayList<>(), rise = new ArrayList<>();
        for (Site site : candidates()) {
            for (int e = 0; e < 6; e++) {
                int n = (e + 1) % 6;
                Site other = neighbor(site, e);
                boolean higher = other == null || site.level() > other.level();
                Site upper = higher ? site : other, lower = higher ? other : site;
                float levels = other == null ? 0 : drop(upper, lower) / BoardGeometry.level();
                // A cliff's rim and foot move with its band; a slope's ground effects stay near its edge line, so they
                // fade across the slope's own gentle rim and toe.
                float big = prominence(levels * BoardGeometry.level());
                Corner ca = corner(site, e), cb = corner(site, n);
                float mx = (ca.x + cb.x) * .5f, my = (ca.y + cb.y) * .5f;
                float rim = other == null ? 0 : -band(upper, lower, upper.level() * BoardGeometry.level(), mx, my) * big;
                float foot = other == null ? 0 : band(upper, lower, lower.level() * BoardGeometry.level(), mx, my) * big;
                // Seams at this level run between the corners as the shore moves them.
                float x0 = ca.x + ca.move()[0], y0 = ca.y + ca.move()[1];
                float x1 = cb.x + cb.move()[0], y1 = cb.y + cb.move()[1];
                // The board's own edge is a cut, not a landform: it has no rim material.
                if (site.level() == self.level() && other != null && other.level() != site.level()) {
                    boolean down = other.level() < site.level();
                    (down ? drop : rise).add(new float[] { x0, y0, x1, y1, levels, down ? rim : foot });
                }
            }
        }
        drops = flatten(drop);
        rises = flatten(rise);
    }

    private static float[] flatten(List<float[]> list) {
        float[] result = new float[list.size() * SEGMENT];
        for (int i = 0; i < list.size(); i++) { System.arraycopy(list.get(i), 0, result, i * SEGMENT, SEGMENT); }
        return result;
    }

    /** Floats per seam segment: its two ends, its step in levels and its transition room. */
    private static final int SEGMENT = 6;

    /** Distance from (x, y) to the nearest segment's own rim or foot: its line, moved by its transition room. */
    private static float nearest(float[] segments, float x, float y) {
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < segments.length; i += SEGMENT) {
            nearest = Math.min(nearest, distance(x, y, segments[i], segments[i + 1], segments[i + 2], segments[i + 3])
                  - segments[i + 5]);
        }
        return nearest;
    }

    /** The step height in levels of the segment nearest to (x, y); zero if there is none. */
    private static float nearestLevels(float[] segments, float x, float y) {
        float nearest = Float.POSITIVE_INFINITY, levels = 0;
        for (int i = 0; i < segments.length; i += SEGMENT) {
            float d = distance(x, y, segments[i], segments[i + 1], segments[i + 2], segments[i + 3]) - segments[i + 5];
            if (d < nearest) {
                nearest = d;
                levels = segments[i + 4];
            }
        }
        return levels;
    }

    /**
     * Replaces the flat top with the sculpted surface. A water hex keeps its bed and replaces its flat banks: waterline
     * is its full outline and mouths its open mouths by edge (see {@link BoardSurface#mouth}); crests the waterline
     * with each fall's mouth moved out to its crest, and falls the edges it falls over. Null and 0 on land.
     */
    void top(List<BoardSurface.Face> destination, Vector3[] waterline, int mouths, Vector3[] crests, int falls) {
        if (!sculpted) { return; }
        this.waterline = waterline;
        this.falls = falls;
        if (waterline == null) {
            destination.clear();
        } else {
            destination.removeIf(face -> face.finish() == BoardSurface.Finish.TOP
                  || face.finish() == BoardSurface.Finish.SHORE);
        }
        List<Vector3> boundary = topOutline();
        int[] starts = outlineStarts;
        for (int e = 0; e < 6; e++) {
            int count = starts[e + 1] - starts[e];
            rims[e] = new Vector3[count + 1];
            for (int i = 0; i <= count; i++) { rims[e][i] = boundary.get((starts[e] + i) % boundary.size()); }
        }
        for (int e = 0; e < 6; e++) { rimShades(e); }
        Vector3 center = new Vector3(BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()), 0);
        if (waterline != null) {
            bank(destination, boundary, starts, mouths);
            if (!canalHex()) {
                rocks(destination, center);
                fallRocks(destination, center, crests, falls, mouths);
            }
            return;
        }
        center.z = groundHeight(center.x, center.y);
        shades.put(center, groundShade(center));
        // Every top is planar: an ordinary hex needs six triangles at every detail level.
        // Keep the real cliff/coast outline; deep notches and narrow necks need the fallback below.
        boolean fan = canFan(center, boundary);
        boolean rounded = false;
        for (Vector3 point : boundary) {
            if (shades.get(point).normal().dst2(Vector3.Z) > .0001f) {
                rounded = true;
                break;
            }
        }
        List<BoardSurface.Face> top = fan && !rounded ? destination : new ArrayList<>();
        if (fan) {
            for (int j = 0; j < boundary.size(); j++) {
                addTriangle(top, center, boundary.get(j), boundary.get((j + 1) % boundary.size()), BoardSurface.Finish.TOP);
            }
            if (!rounded) {
                rocks(destination, center);
                pits(destination);
                return;
            }
        } else {
            BoardSurface.polygon(boundary.toArray(Vector3[]::new), BoardSurface.Finish.TOP, top);
        }
        // Rounded rims need interior shading samples even when a centre fan is geometrically valid. Otherwise
        // their normals and rock coverage extend across the flat top. Concave rims use the shore triangulator;
        // both paths refine shared diagonals without changing the outline or the playing height.
        Map<TopEdge, Vector3> splits = new HashMap<>();
        for (int i = 0; i < boundary.size(); i++) {
            Vector3 a = boundary.get(i), b = boundary.get((i + 1) % boundary.size());
            splits.put(new TopEdge(a, b), null);
            splits.put(new TopEdge(b, a), null);
        }
        // Work in shared passes so a new diagonal cannot recursively chase an unsplit boundary into slivers.
        float coordinateScale = Math.max(Math.abs(center.x), Math.abs(center.y)) + BoardGeometry.width();
        float minimumWidth = Math.max(metres(.0001f), 4 * Math.ulp(coordinateScale));
        for (int pass = 0; pass < 12; pass++) {
            // Protect only near-degenerate ears from rounding collapse. A narrow, metres-long interior triangle
            // still needs samples along its length, or it carries rim normals/material distances across the top.
            // Lock both sides before planning splits so an unsafe sliver cannot leave its neighbour unpaired.
            for (BoardSurface.Face face : top) {
                Vector3 a = face.a(), b = face.b(), c = face.c();
                float area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
                float longest = Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)));
                if (area * area > minimumWidth * minimumWidth * longest) { continue; }
                Vector3[] points = { a, b, c };
                for (int i = 0; i < 3; i++) {
                    Vector3 from = points[i], to = points[(i + 1) % 3];
                    splits.put(new TopEdge(from, to), null);
                    splits.put(new TopEdge(to, from), null);
                }
            }
            boolean split = false;
            for (BoardSurface.Face face : top) {
                split |= topSplit(face.a(), face.b(), splits) != null;
                split |= topSplit(face.b(), face.c(), splits) != null;
                split |= topSplit(face.c(), face.a(), splits) != null;
            }
            if (!split) { break; }
            List<BoardSurface.Face> refined = new ArrayList<>();
            for (BoardSurface.Face face : top) {
                topTriangle(refined, face.a(), face.b(), face.c(), splits);
            }
            top = refined;
        }
        for (BoardSurface.Face face : top) {
            addTriangle(destination, face.a(), face.b(), face.c(), BoardSurface.Finish.TOP);
        }
        rocks(destination, center);
        pits(destination);
    }

    /** Build the canonical boundary once, whether the shoreline or the top needs it first. */
    private List<Vector3> topOutline() {
        if (outline != null) { return outline; }
        float base = tile.elevation() * BoardGeometry.level();
        outline = new ArrayList<>();
        outlineStarts = new int[7];
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            boolean seamless = joined(self, neighbor(self, e));
            int count = topSamples(e);
            outlineStarts[e] = outline.size();
            for (int i = 0; i < count; i++) {
                Vector3 p = edgePoint(edge, canonical(e, i, count), base);
                if (i == 0 ? corner(self, e).seamless() : seamless) { p.z = groundHeight(p.x, p.y); }
                // No wall stands in the open water past a separated side's strip of land.
                if (separatedWater(e) || i == 0 && separatedWater((e + 5) % 6)) {
                    float water = BoardGeometry.waterZ(tile);
                    p.z = water + (p.z - water) * stripLand(p.x, p.y);
                }
                outline.add(p);
            }
        }
        outlineStarts[6] = outline.size();
        return outline;
    }

    /** Whether edge e is a side the author separated between two water hexes. */
    private boolean separatedWater(int e) {
        int direction = BoardGeometry.edgeDirection(e);
        BoardScene.Tile next = scene.tile(tile.coords().translated(direction));
        return next != null && tile.liquid().present() && tile.liquid().connects(next.liquid()) && tile.separated(next, direction);
    }

    /** See {@link BoardRiver#stripLand}. */
    float stripLand(float x, float y) { return river.stripLand(x, y); }

    /** See {@link BoardRiver#stripDepth}. */
    float stripDepth(float x, float y) { return river.stripDepth(x, y); }

    /** Distance from the pool centre to its actual dry boundary along this shoreline ray. */
    float bankRadius(Vector3 center, Vector3 target) {
        float dx = target.x - center.x, dy = target.y - center.y, length = (float) Math.hypot(dx, dy);
        return gap(topOutline(), center, dx / length, dy / length);
    }

    private boolean canFan(Vector3 center, List<Vector3> boundary) {
        // If the cliffs consume more than half of a shared edge, the neck needs interior lighting
        // samples even when a centre fan is geometrically valid. Otherwise rim normals span its top.
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            Vector3[] rim = rims[e];
            if (edge.levelJoin && rim[0].dst2(rim[rim.length - 1]) < edge.length * edge.length * .25f) { return false; }
        }
        for (int j = 0; j < boundary.size(); j++) {
            if (upward(center, boundary.get(j), boundary.get((j + 1) % boundary.size())) <= 0) { return false; }
        }
        return true;
    }

    private record TopEdge(Vector3 a, Vector3 b) { }

    /** {@link #topSplit}'s mark for an edge not looked at yet; never a point of the relief. */
    private static final Vector3 UNSPLIT = new Vector3();

    /** Refine only non-linear interior shading; both sides of a diagonal reuse its decision and sample. */
    private void topTriangle(List<BoardSurface.Face> destination, Vector3 a, Vector3 b, Vector3 c,
          Map<TopEdge, Vector3> splits) {
        Vector3[] points = { a, b, c };
        Vector3 middle = null;
        int edge = 0;
        float longest = 0;
        for (int i = 0; i < 3; i++) {
            Vector3 p = points[i], q = points[(i + 1) % 3];
            float length = p.dst2(q);
            if (length <= longest) { continue; }
            Vector3 sample = splits.get(new TopEdge(p, q));
            if (sample != null) { middle = sample; edge = i; longest = length; }
        }
        if (middle == null) {
            addTriangle(destination, a, b, c, BoardSurface.Finish.TOP);
            return;
        }
        topTriangle(destination, points[edge], middle, points[(edge + 2) % 3], splits);
        topTriangle(destination, middle, points[(edge + 1) % 3], points[(edge + 2) % 3], splits);
    }

    private Vector3 topSplit(Vector3 a, Vector3 b, Map<TopEdge, Vector3> splits) {
        TopEdge key = new TopEdge(a, b);
        // One lookup: an edge left whole is held as null.
        Vector3 held = splits.getOrDefault(key, UNSPLIT);
        if (held != UNSPLIT) { return held; }
        Vector3 middle = null;
        float spacing = metres(detail.dressing ? 1 : 2);
        if (a.dst2(b) > spacing * spacing) {
            Vector3 p = new Vector3(a).lerp(b, .5f);
            Shade sample = groundShade(p), sa = shades.get(a), sb = shades.get(b);
            boolean curved = new Vector3(sa.normal()).lerp(sb.normal(), .5f).dst2(sample.normal()) > .0001f
                  || Math.abs((sa.rim() + sb.rim()) * .5f - sample.rim()) > .5f
                  || Math.abs((sa.foot() + sb.foot()) * .5f - sample.foot()) > .5f;
            // A long straight boundary stays shared with its neighbour. Do not chase its interpolation into
            // ever thinner triangles immediately beside it.
            float margin = Float.POSITIVE_INFINITY;
            if (curved) {
                for (int i = 0; i < outline.size(); i++) {
                    Vector3 from = outline.get(i), to = outline.get((i + 1) % outline.size());
                    margin = Math.min(margin, distance(p.x, p.y, from.x, from.y, to.x, to.y));
                }
            }
            if (curved && margin > metres(.25f)) {
                shades.put(p, sample);
                middle = p;
            }
        }
        splits.put(key, middle);
        splits.put(new TopEdge(b, a), middle);
        return middle;
    }

    // ---- Water hex banks -------------------------------------------------------------------------------------

    /**
     * A water hex's banks: the ground between its canonical boundary and its waterline, grading down to the water.
     * An open mouth has none but for its short stretch from each corner to
     * where the waterline meets the mouth, which both hexes of the mouth build alike. The bed inside the waterline and
     * the water itself are {@link BoardSurface}'s.
     */
    private void bank(List<BoardSurface.Face> destination, List<Vector3> boundary, int[] starts, int mouths) {
        List<BoardSurface.Face> strip = new ArrayList<>();
        Map<Vector3, Float> cliffFeet = new IdentityHashMap<>();
        for (int e = 0; e < 6; e++) {
            if (!wetCliff(e)) { continue; }
            // Vertical faces have coincident horizontal outlines. Stitch matching parameters directly: the
            // upward-triangle search used by beaches cannot distinguish the two sides of a vertical strip.
            mouths |= 1 << e;
            int outerCount = starts[e + 1] - starts[e], innerCount = waterline.length / 6;
            int count = Math.max(outerCount, innerCount);
            // Keep the bank/bed vertex attributes separate from the wall, including its first column.
            Vector3 a = new Vector3(boundary.get(starts[e])), lowA = new Vector3(waterline[e * innerCount]);
            for (int i = 1; i <= count; i++) {
                float outer = i * outerCount / (float) count, inner = i * innerCount / (float) count;
                int o = (int) outer, n = (int) inner;
                Vector3 b = new Vector3(boundary.get((starts[e] + o) % boundary.size()))
                      .lerp(boundary.get((starts[e] + o + 1) % boundary.size()), outer - o);
                Vector3 lowB = new Vector3(waterline[(e * innerCount + n) % waterline.length])
                      .lerp(waterline[(e * innerCount + n + 1) % waterline.length], inner - n);
                addQuad(strip, a, b, lowB, lowA, BoardSurface.Finish.WALL, e);
                cliffFeet.put(a, lowA.z);
                cliffFeet.put(b, lowB.z);
                cliffFeet.put(lowA, lowA.z);
                cliffFeet.put(lowB, lowB.z);
                a = b;
                lowA = lowB;
            }
        }
        if (mouths == 0) {
            bankRun(strip, boundary, starts, 0, 6, false);
        } else {
            for (int e = 0; e < 6; e++) {
                // A run of banks starts after each mouth and ends before the next.
                if ((mouths & 1 << e) != 0 || (mouths & 1 << (e + 5) % 6) == 0) { continue; }
                int length = 1;
                while ((mouths & 1 << (e + length) % 6) == 0) { length++; }
                bankRun(strip, boundary, starts, e, length, true);
            }
        }
        // The bank's own vertices shade like the ground they are, facing as the bank does; the boundary keeps the
        // shades it shares with the neighbours' walls and tops.
        Map<Vector3, Vector3> normals = new IdentityHashMap<>(), byPosition = new HashMap<>();
        Set<Vector3> cliffVertices = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Vector3> cliffPositions = new HashSet<>();
        for (BoardSurface.Face face : strip) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (face.finish() == BoardSurface.Finish.WALL) {
                    cliffVertices.add(p);
                    cliffPositions.add(p);
                }
                if (!shades.containsKey(p)) {
                    normals.computeIfAbsent(p, key -> byPosition.computeIfAbsent(key, position -> new Vector3())).add(normal);
                }
            }
        }
        // Exposed shallow bars join the dry bank. Share their normal across the former waterline, which is no
        // longer an actual edge of the water and must not leave a lighting seam through continuous sand.
        for (BoardSurface.Face face : destination) {
            if (face.finish() != BoardSurface.Finish.BED) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                Vector3 shared = byPosition.get(p);
                if (shared != null && !cliffPositions.contains(p)) {
                    shared.add(normal);
                    normals.put(p, shared);
                }
            }
        }
        for (var entry : normals.entrySet()) {
            Shade ground = groundShade(entry.getKey());
            float rim = cliffVertices.contains(entry.getKey())
                  ? Math.max(0, entry.getKey().z - cliffFeet.get(entry.getKey())) / metres(1) : ground.rim();
            shades.put(entry.getKey(), new Shade(entry.getValue().nor(),
                  cliffVertices.contains(entry.getKey()) ? Kind.SUBMERGED_CLIFF : Kind.GROUND, ground.occlusion(),
                  ground.level(), rim, ground.foot(), ground.tint()));
        }
        destination.addAll(strip);
    }

    /**
     * One run of banks over {@code count} edges from edge {@code first}, strip by strip: the boundary, an intermediate lip and
     * the waterline. With {@code mouths} the run starts and ends where the waterline meets the open mouth before it and
     * after it, and takes in the stretch of each mouth from its corner to that point.
     */
    private void bankRun(List<BoardSurface.Face> destination, List<Vector3> boundary, int[] starts, int first,
          int count, boolean mouths) {
        int perEdge = waterline.length / 6;
        float base = self.level() * BoardGeometry.level();
        List<Vector3> outer = new ArrayList<>(), lip = new ArrayList<>(), inner = new ArrayList<>();
        List<Float> outerParameters = new ArrayList<>(), lipParameters = new ArrayList<>();
        List<Float> innerParameters = new ArrayList<>();
        Vector3 start = null, end = null;
        float from = 0, to = 0;
        if (mouths) {
            start = new Vector3(waterline[first * perEdge]);
            end = new Vector3(waterline[(first + count) % 6 * perEdge]);
            from = along(first + 5, start);
            to = along(first + count, end);
            outer.add(start);
            outerParameters.add(first - 1 + from);
            // A neighbouring cliff ends on one straight segment from boundary to bed. Only a real water mouth
            // has the intermediate bank-lip samples; adding them here would leave a wedge beside that segment.
            if (!wetCliff(first + 5)) {
                stub(outer, outerParameters, boundary, starts, first + 5, first - 1, from, true);
            }
        }
        for (int k = 0; k < count; k++) {
            int e = (first + k) % 6, samples = starts[e + 1] - starts[e];
            for (int i = 0; i < samples; i++) {
                outer.add(boundary.get(starts[e] + i));
                outerParameters.add(first + k + i / (float) samples);
            }
        }
        if (mouths) {
            // The corner that closes the run, and the mouth after it.
            outer.add(boundary.get(starts[(first + count) % 6]));
            outerParameters.add((float) (first + count));
            if (!wetCliff(first + count)) {
                stub(outer, outerParameters, boundary, starts, first + count, first + count, to, false);
            }
            outer.add(end);
            outerParameters.add(first + count + to);
            // The anchors end the boundary and the waterline, not the lip: each stub fans from a lip point inside the
            // bank, never from the anchor at its own end, which would fold over where a rounded corner bends it.
            inner.add(start);
            innerParameters.add(first - 1 + from);
        }
        float shore = SHORE_LIP * BoardGeometry.hexScale();
        boolean canal = canalHex();
        for (int k = mouths ? 1 : 0; k < count * perEdge; k++) {
            int index = (first * perEdge + k) % waterline.length, e = index / perEdge;
            Vector3 w = new Vector3(waterline[index]);
            // Interpolate the actual boundary segment. Snapping to its nearest sample would send the lip sideways
            // beside a sparse slope or a two-corner concrete panel and can fold the shore strip.
            int samples = starts[e + 1] - starts[e];
            float parameter = index % perEdge * samples / (float) perEdge;
            int sample = (int) parameter;
            Vector3 b = new Vector3(boundary.get((starts[e] + sample) % boundary.size()))
                  .lerp(boundary.get((starts[e] + sample + 1) % boundary.size()), parameter - sample);
            float gap = (float) Math.hypot(b.x - w.x, b.y - w.y);
            float room = shore;
            // Opposing banks of a tight bend must not push their lip rows across each other.
            for (int j = 0; j < waterline.length; j++) {
                int separation = Math.floorMod(j - index, waterline.length);
                if (separation <= 1 || separation >= waterline.length - 2) { continue; }
                Vector3 p = waterline[j], q = waterline[(j + 1) % waterline.length];
                float dx = q.x - p.x, dy = q.y - p.y, length2 = dx * dx + dy * dy;
                float t = length2 == 0 ? 0 : Math.clamp(((w.x - p.x) * dx + (w.y - p.y) * dy) / length2, 0, 1);
                room = Math.min(room, .4f * (float) Math.hypot(w.x - p.x - t * dx, w.y - p.y - t * dy));
            }
            float f = Math.min(.5f, (canal ? LINING_LIP * BoardGeometry.hexScale() : room) / Math.max(gap, 1e-4f));
            float top = separatedWater(e) ? b.z : base;
            // Grade the existing rows across the available bank instead of leaving a flat shelf and a tiny drop
            // at the water. This also gives the material room to lose dry cover before reaching the waterline. A canal's
            // shoulder stays level out to its lining, just above the water.
            lip.add(lip(boundary, waterline[(index + waterline.length - 1) % waterline.length], w,
                  waterline[(index + 1) % waterline.length], (b.x - w.x) * f, (b.y - w.y) * f,
                  tile.liquid().molten() || canal ? base : top - (top - w.z) * (1 - f)));
            lipParameters.add(first + k / (float) perEdge);
            inner.add(w);
            innerParameters.add(first + k / (float) perEdge);
        }
        if (mouths) {
            inner.add(end);
            innerParameters.add(first + count + to);
        } else {
            // The ring closes where it began. Its boundary starts at the sample nearest the first lip, so the strip
            // starts between points that face each other even where the steps through corner 0 slide its samples.
            int nearest = 0, n = outer.size();
            for (int i = 1; i < n; i++) {
                if (outer.get(i).dst2(lip.getFirst()) < outer.get(nearest).dst2(lip.getFirst())) { nearest = i; }
            }
            float shift = outerParameters.get(nearest) > 3 ? -6 : 0;
            List<Vector3> ring = new ArrayList<>(n + 1);
            List<Float> ringParameters = new ArrayList<>(n + 1);
            for (int i = nearest; i <= nearest + n; i++) {
                ring.add(outer.get(i % n));
                ringParameters.add(outerParameters.get(i % n) + shift + (i >= n ? 6 : 0));
            }
            outer = ring;
            outerParameters = ringParameters;
            lip.add(lip.getFirst());
            lipParameters.add(6f);
            inner.add(inner.getFirst());
            innerParameters.add(6f);
        }
        strip(destination, outer, outerParameters, lip, lipParameters, first, count, mouths);
        strip(destination, lip, lipParameters, inner, innerParameters, first, count, mouths);
    }

    /**
     * The boundary samples of mouth edge {@code e} between its corner and where the waterline meets it (at {@code t},
     * this hex's fraction of the edge), falling from the hex's level to the water over the available bank.
     * Both hexes of the mouth build them alike; a straight wall under a fall's
     * mouth tops out on them too.
     */
    private void stub(List<Vector3> outer, List<Float> parameters, List<Vector3> boundary, int[] starts, int e,
          int unwrapped, float t, boolean before) {
        int edge = Math.floorMod(e, 6), samples = starts[edge + 1] - starts[edge];
        float base = self.level() * BoardGeometry.level(), margin = .02f;
        float water = waterline[(before ? (edge + 1) % 6 : edge) * (waterline.length / 6)].z;
        Corner a = corner(self, edge), b = corner(self, edge + 1);
        float length = (float) Math.hypot(b.x + b.move()[0] - a.x - a.move()[0], b.y + b.move()[1] - a.y - a.move()[1]);
        float ramp = Math.max(1e-4f, (before ? 1 - t : t) * length);
        if (!gradedBankMouth(edge)) {
            ramp = Math.min(SHORE_LIP * BoardGeometry.hexScale(), ramp);
        }
        // A canal's shoulder stays level along the mouth's edge out to its lining (both canals alike).
        if (canalHex()) { ramp = Math.min(LINING_LIP * BoardGeometry.hexScale(), ramp); }
        for (int i = 1; i < samples; i++) {
            // Where the steps through a corner slide the samples along the mouth, each counts where it lies.
            Vector3 p = new Vector3(boundary.get(starts[edge] + i));
            float s = along(edge, p);
            if (before ? s <= t + margin : s >= t - margin) { continue; }
            p.z = base - (base - water) * Math.max(0, 1 - Math.abs(s - t) * length / ramp);
            outer.add(p);
            parameters.add(unwrapped + s);
        }
    }

    /** Both sides of a mouth retain the narrow lip where a molten bank needs its original profile. */
    boolean gradedBankMouth(int edge) {
        BoardScene.Tile across = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
        return !tile.liquid().molten() && (across == null || !across.liquid().molten());
    }

    /** Where p lies along this hex's edge e at its level, as its fraction from the edge's first corner. */
    private float along(int e, Vector3 p) {
        Corner a = corner(self, e), b = corner(self, e + 1);
        float ax = a.x + a.move()[0], ay = a.y + a.move()[1];
        float dx = b.x + b.move()[0] - ax, dy = b.y + b.move()[1] - ay, length2 = dx * dx + dy * dy;
        // A concrete fit can collapse an edge where two mitred walls meet on its corner: it has no length to run along.
        return length2 == 0 ? 0 : Math.clamp(((p.x - ax) * dx + (p.y - ay) * dy) / length2, 0, 1);
    }

    /**
     * The lip at offset (dx, dy) from waterline point w, turned where it would lean back over the waterline on either
     * side of w, from {@code before} to {@code after}, so the shore between them never folds; turned, it stays at most
     * halfway to the boundary.
     */
    private static Vector3 lip(List<Vector3> boundary, Vector3 before, Vector3 w, Vector3 after, float dx, float dy,
          float z) {
        float length = (float) Math.hypot(dx, dy);
        boolean turned = false;
        for (Vector3[] side : new Vector3[][] { { before, w }, { w, after } }) {
            float sx = side[1].x - side[0].x, sy = side[1].y - side[0].y, sl = (float) Math.hypot(sx, sy);
            float lean = sl > 0 ? (sx * dy - sy * dx) / sl + .1f * length : 0;
            if (lean > 0) {
                dx += lean * sy / sl;
                dy -= lean * sx / sl;
                turned = true;
            }
        }
        float turnedLength = (float) Math.hypot(dx, dy);
        if (turned && turnedLength > 0) {
            float scale = Math.min(length, gap(boundary, w, dx / turnedLength, dy / turnedLength) / 2) / turnedLength;
            dx *= scale;
            dy *= scale;
        }
        return new Vector3(w.x + dx, w.y + dy, z);
    }

    /** Distance from p out along (dx, dy) to the closed polygon, or zero where the ray meets none of it. */
    private static float gap(List<Vector3> polygon, Vector3 p, float dx, float dy) {
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Vector3 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            float ex = b.x - a.x, ey = b.y - a.y, denominator = dx * ey - dy * ex;
            if (Math.abs(denominator) < 1e-9f) { continue; }
            float qx = a.x - p.x, qy = a.y - p.y;
            float s = (qx * ey - qy * ex) / denominator, u = (qx * dy - qy * dx) / denominator;
            if (s > 0 && u >= 0 && u <= 1) { best = Math.min(best, s); }
        }
        return Float.isFinite(best) ? best : 0;
    }

    /**
     * Stitches two open chains that run the same way, sampled at increasing parameters, with upward triangles. Within
     * {@link #LEAD} of the parameter order it goes on along the chain whose next point makes the shorter new diagonal,
     * so each triangle joins points that face each other where the steps through a corner slide the boundary's samples.
     * First check which choices can finish the strip: a locally upward triangle can otherwise close off a concave
     * corner and leave only downward triangles to finish it.
     */
    private static void strip(List<BoardSurface.Face> out, List<Vector3> outer, List<Float> outerParameters,
          List<Vector3> inner, List<Float> innerParameters, int first, int count, boolean mouths) {
        boolean[][] reaches = stripReachable(outer, inner);
        int i = 0, j = 0;
        while (i + 1 < outer.size() || j + 1 < inner.size()) {
            boolean alongOuter = j + 1 >= inner.size() || i + 1 < outer.size()
                  && (outerParameters.get(i + 1) < innerParameters.get(j + 1) - LEAD
                  || outerParameters.get(i + 1) <= innerParameters.get(j + 1) + LEAD
                  && inner.get(j).dst2(outer.get(i + 1)) <= outer.get(i).dst2(inner.get(j + 1)));
            if (i + 1 < outer.size() && j + 1 < inner.size()) {
                float byOuter = upward(inner.get(j), outer.get(i), outer.get(i + 1));
                float byInner = upward(inner.get(j), outer.get(i), inner.get(j + 1));
                boolean outerValid = byOuter >= 0 && reaches[i + 1][j];
                boolean innerValid = byInner >= 0 && reaches[i][j + 1];
                if (outerValid != innerValid) {
                    alongOuter = outerValid;
                } else if ((alongOuter ? byOuter : byInner) < 0 && (alongOuter ? byInner : byOuter) > 0) {
                    alongOuter = !alongOuter;
                }
            }
            if (alongOuter) {
                bankTriangle(out, new BankPoint(inner.get(j), innerParameters.get(j)),
                      new BankPoint(outer.get(i), outerParameters.get(i)),
                      new BankPoint(outer.get(i + 1), outerParameters.get(i + 1)), first, count, mouths);
                i++;
            } else {
                bankTriangle(out, new BankPoint(inner.get(j), innerParameters.get(j)),
                      new BankPoint(outer.get(i), outerParameters.get(i)),
                      new BankPoint(inner.get(j + 1), innerParameters.get(j + 1)), first, count, mouths);
                j++;
            }
        }
    }

    private record BankPoint(Vector3 point, float parameter) {
        BankPoint at(BankPoint other, float parameter) {
            if (parameter == this.parameter) { return this; }
            if (parameter == other.parameter) { return other; }
            // Canonical interpolation keeps a cut shared by neighbouring triangles bit-for-bit identical.
            if (this.parameter > other.parameter) { return other.at(this, parameter); }
            return new BankPoint(new Vector3(point).lerp(other.point,
                  (parameter - this.parameter) / (other.parameter - this.parameter)), parameter);
        }
    }

    /** Split the existing bank triangles at material boundaries; keep their shape and tag each shore's owner. */
    private static void bankTriangle(List<BoardSurface.Face> out, BankPoint a, BankPoint b, BankPoint c,
          int first, int count, boolean mouths) {
        float minimum = Math.min(a.parameter, Math.min(b.parameter, c.parameter));
        float maximum = Math.max(a.parameter, Math.max(b.parameter, c.parameter));
        int from = (int) Math.floor(minimum), to = Math.max(from, (int) Math.ceil(maximum) - 1);
        if (mouths) {
            from = Math.clamp(from, first, first + count - 1);
            to = Math.clamp(to, first, first + count - 1);
        }
        if (from == to) {
            int owner = Math.floorMod(from, 6);
            addTriangle(out, a.point, b.point, c.point,
                  BoardSurface.Finish.TOP, owner);
            return;
        }
        for (int edge = from; edge <= to; edge++) {
            List<BankPoint> polygon = List.of(a, b, c);
            // Mouth stubs belong to the dry bank beside them, not the water across the mouth.
            // Do not subdivide their waterline: the bed and the adjoining water hex share that exact segment.
            if (!mouths || edge > first) { polygon = clipBank(polygon, edge, true); }
            if (!mouths || edge < first + count - 1) { polygon = clipBank(polygon, edge + 1, false); }
            int owner = Math.floorMod(edge, 6);
            for (int i = 1; i + 1 < polygon.size(); i++) {
                addTriangle(out, polygon.getFirst().point, polygon.get(i).point, polygon.get(i + 1).point,
                      BoardSurface.Finish.TOP, owner);
            }
        }
    }

    private static List<BankPoint> clipBank(List<BankPoint> polygon, float boundary, boolean above) {
        List<BankPoint> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            BankPoint a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            boolean keepA = above ? a.parameter >= boundary : a.parameter <= boundary;
            boolean keepB = above ? b.parameter >= boundary : b.parameter <= boundary;
            if (keepA) { result.add(a); }
            if (keepA != keepB) { result.add(a.at(b, boundary)); }
        }
        return result;
    }

    // ---- Rim formations and fallen rock ----------------------------------------------------------------------

    /** Authored Rough on an exposed ridge reads as attached bedrock, sharing the strike of nearby outcrops. */
    private boolean roughRidge() {
        if (!sculpted || !self.detailed() || self.liquid() || self.family() == CONCRETE) { return false; }
        int exposed = 0;
        for (int e = 0; e < 6; e++) {
            Site other = neighbor(self, e);
            // A board boundary alone must not turn the whole border into a geological landmark.
            if (other != null && !other.ultraSublevel() && self.level() - other.level() >= 3) { exposed++; }
        }
        return exposed >= 2;
    }

    /** Rough boulders use the rock kit and the hex's geology, rooted in its finished ground or riverbed. */
    void boulders(List<BoardSurface.Face> destination) {
        List<BoardScene.Feature> boulders = tile.features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.BOULDER).toList();
        if (boulders.isEmpty()) { return; }
        boolean ridge = roughRidge();
        BoardScene.Surface family = BoardScene.Surface.values()[self.family()];
        List<BoardSurface.Face> ground = new ArrayList<>(destination.stream()
              .filter(face -> face.finish() != BoardSurface.Finish.OUTCROP
                    && face.finish() != BoardSurface.Finish.DRESSING && face.finish() != BoardSurface.Finish.ICE).toList());
        // The top alone misses slopes and talus belonging to this or a higher neighbouring hex. Sample the same
        // canonical wall grid that is drawn there, so Rough merges into those slopes instead of disappearing.
        for (int e = 0; sculpted && e < 6; e++) {
            Edge edge = edge(e);
            if (edge.profiled) { wallFaces(wallGrid(e, edge), e, ground); }
        }
        for (int i = 0; i < boulders.size(); i++) {
            BoardScene.Feature feature = boulders.get(i);
            // Without gravity rough is bedrock (BoardScene.Tile.lunar): formations of the ground's own rock that
            // share the regional strike and keep their proportions.
            boolean outcrop = feature.asset().equals(BoardRocks.OUTCROP) || ridge;
            float size = BoardFeatures.ROUGH_BOULDER_WIDTH * feature.scale() * BoardGeometry.hexScale();
            float height = feature.height() * BoardGeometry.level();
            // Keep the captured road/bridge clearance; pulling a rock inward to fit a rim could invade that route.
            float[] spot = { self.x() + feature.x() * BoardGeometry.hexScale(),
                  self.y() + feature.y() * BoardGeometry.hexScale() };
            float room = margin(spot[0], spot[1], -1);
            // A large outcrop near the hex's edge shrinks to the room it has.
            if (outcrop && room < size * .55f && room > size * .3f) {
                height *= room / (size * .55f);
                size = room / .55f;
            } else if (room < size * .55f) { continue; }
            int variant = self.ix() * 31 + self.iy() * 17 + i;
            TerrainLod lod = tile.liquid().present() ? TerrainLod.FULL : detail;
            BoardShape rock = outcrop ? BoardRocks.outcrop(variant, lod) : BoardRocks.rock(family, variant, lod);
            // Bedding strikes alike across neighbouring hexes and turns slowly over the board.
            // Capture supplies the common fracture direction and each fragment's local variation.
            float turn = (float) Math.toRadians(feature.rotation());
            float c = (float) Math.cos(turn), s = (float) Math.sin(turn), across = outcrop ? 1 : .8f;
            // Each rock repeats corners across its polygons. Sample each corner once, against only the nearby
            // ground triangles, rather than rescanning a tall cliff's entire grid for every polygon vertex.
            List<Vector3> probes = new ArrayList<>();
            Set<Vector3> corners = Collections.newSetFromMap(new IdentityHashMap<>());
            float minX = spot[0], maxX = spot[0], minY = spot[1], maxY = spot[1];
            for (BoardShape.Polygon polygon : rock.polygons()) {
                for (Vector3 p : polygon.points()) {
                    if (!corners.add(p)) { continue; }
                    float x = spot[0] + (c * p.x - s * p.y * across) * size;
                    float y = spot[1] + (s * p.x + c * p.y * across) * size;
                    probes.add(new Vector3(x, y, 0));
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            float tolerance = .001f * BoardGeometry.hexScale();
            List<BoardSurface.Face> nearby = new ArrayList<>();
            for (BoardSurface.Face face : ground) {
                if (Math.max(face.a().x, Math.max(face.b().x, face.c().x)) >= minX - tolerance
                      && Math.min(face.a().x, Math.min(face.b().x, face.c().x)) <= maxX + tolerance
                      && Math.max(face.a().y, Math.max(face.b().y, face.c().y)) >= minY - tolerance
                      && Math.min(face.a().y, Math.min(face.b().y, face.c().y)) <= maxY + tolerance) { nearby.add(face); }
            }
            float support = BoardSurface.sampleHeight(nearby, spot[0], spot[1], Float.NaN);
            if (!Float.isFinite(support)) { continue; }
            float low = support;
            for (Vector3 p : probes) {
                float z = BoardSurface.sampleHeight(nearby, p.x, p.y, Float.NaN);
                if (Float.isFinite(z)) { low = Math.min(low, z); }
            }
            // A small boulder straddling a sheer drop must not grow a foundation the height of the cliff
            // (for example METEOR 1212). Embed it locally in the ledge that supports its centre.
            low = Math.max(low, support - height);
            Vector3 base;
            float top;
            if (outcrop) {
                // Bedrock keeps its authored proportions, a little steepened within the captured height.
                // Limit burial by the actual mesh rise, rather than the larger height bound; a small fragment
                // spanning a slope must still emerge from the ground at its centre.
                float rise = Math.min(rock.height() * size * 1.3f, height / .8f);
                float root = Math.max(support - rise * .35f, Math.min(support - rise * .2f, low));
                base = new Vector3(spot[0], spot[1], root);
                top = base.z + rise;
            } else {
                // Keep the summit near the local surface and bury the root below the downhill side. The uphill faces
                // intersect the slope naturally, without turning rim boulders into vertical pillars.
                base = new Vector3(spot[0], spot[1], low - height * .35f);
                top = support + height * .75f;
            }
            place(destination, rock, base, turn, size, size * across, (top - base.z) / rock.height(), Kind.ROCK, -1, false);
        }
    }

    /**
     * Rock kit placements, each derived from one canonical edge: formations standing on the rim of a drop (few and
     * small up to two levels, larger and more frequent from three) and fallen blocks on the talus and ground below a
     * rise. Picking keeps overhanging rim rocks with the cliff; fallen blocks at its foot follow the lower hex's
     * footprint. These formations never enter the unit anchor.
     */
    private void rocks(List<BoardSurface.Face> destination, Vector3 anchor) {
        // Geological rim and fallen rocks belong on banks and riverbeds, including positive-depth water.
        // Cosmetic field scatter has its own stricter water rule; coarse tiers carry these only on a late level.
        if (!detail.formations() || !self.detailed() || tile.liquid().volcanic()) { return; }
        float m = metres(1);
        BoardScene.Surface family = BoardScene.Surface.values()[self.family()];
        boolean paved = tile.surface() == BoardScene.Surface.CONCRETE;
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            boolean rim = edge.upper == self;
            boolean submergedFoot = wetCliff(e);
            // Cast faces keep clean rims and feet at every height.
            if (!edge.profiled || !rim && edge.lower != self || edge.upper.family() == CONCRETE) { continue; }
            float bank = ROCKS[edge.upper.family()].bank();
            float top = edge.top(), bottom = edge.bottom(), drop = top - bottom, big = prominence(edge.drop);
            Random random = new Random(edge.a.key * 1_000_003L + edge.b.key * 31 + (rim ? 7 : 13));
            float yaw = (float) Math.atan2(edge.b.y - edge.a.y, edge.b.x - edge.a.x);
            // Earth banks shed few blocks; rock cliffs many. A transition's wider talus gathers larger blocks.
            boolean banded = edge.room > 0;
            float firm = lerp(banded ? Math.min(bank, .35f) : bank, 1, big);
            float apron = !rim && banded ? 1 + .8f * big : 1;
            // Jointed sandstone breaks away in whole columns: its talus gathers more and larger fallen blocks.
            float fallen = !rim && (edge.upper.family() == SAND
                  || edge.upper.family() == BoardScene.Surface.DESERT.ordinal()
                  || edge.upper.family() == BoardScene.Surface.MARS.ordinal()) ? 1.8f : 1;
            int count = Math.round((rim ? lerp(.7f, 4.5f, big) : lerp(1.6f, 5.5f, big)) * firm * apron * fallen
                  * (.5f + random.nextFloat()));
            float outlet = !rim && !submergedFoot && big > .5f
                  && edge.upper.family() != BoardScene.Surface.LUNAR.ordinal() ? talusOutlet(edge) : Float.NaN;
            float t = random.nextFloat();
            for (int i = 0; i < count; i++) {
                // Rocks come in small groups: most follow the previous one closely.
                t = random.nextFloat() < .55f ? t + (random.nextFloat() - .3f) * .16f : random.nextFloat();
                t = .1f + .8f * (t - (float) Math.floor(t));
                // Most fallen pieces gather below an actual cleft. Keep some scattered farther along the apron,
                // with the same finite candidate count and clearance rules as before.
                if (Float.isFinite(outlet) && i % 3 != 0) {
                    t = Math.clamp(outlet + (t - .5f) * .35f, .1f, .9f);
                }
                // Mostly small pieces, now and then a large block.
                float r = random.nextFloat();
                float size = m * (rim ? lerp(.8f, 2.7f, big)
                      : lerp(.7f, 1.8f, big) * (1 + .3f * (apron - 1)) * (1 + .4f * (fallen - 1))) * (.35f + 1.15f * r * r);
                BoardShape rock = BoardRocks.rock(family, random.nextInt(64), detail);
                float length = size * (1f + .7f * random.nextFloat()), width = size * (.55f + .45f * random.nextFloat());
                float height = size * (.35f + .75f * random.nextFloat());
                float turn = yaw + (float) random.nextGaussian() * (rim ? .55f : .9f);
                Vector3 base;
                if (rim) {
                    // Standing on the rim, set back by part of its width, its root inside the caprock.
                    Vector3 p = edgePoint(edge, t, top);
                    float back = width * (.05f + .5f * random.nextFloat());
                    base = new Vector3(p.x - edge.nx * back, p.y - edge.ny * back, top - height * (.35f + .35f * random.nextFloat()));
                    height = Math.min(height, (headroom(tile) * .9f + top - base.z));
                } else if (submergedFoot) {
                    // The same fallen blocks collect at a drowned cliff's foot, rooted in its actual bed.
                    int per = waterline.length / 6;
                    float along = t * per;
                    int index = e * per + (int) along;
                    Vector3 p = new Vector3(waterline[index]).lerp(waterline[(index + 1) % waterline.length],
                          along - (int) along);
                    float out = width * .4f + m * (.2f + random.nextFloat());
                    base = new Vector3(p.x + edge.nx * out, p.y + edge.ny * out, 0);
                    float bed = BoardSurface.sampleHeight(destination, base.x, base.y, Float.NaN);
                    if (!Float.isFinite(bed)) { continue; }
                    height = Math.min(height, Math.max(0, BoardGeometry.waterZ(tile) - bed) * .6f);
                    if (height < m * .1f) { continue; }
                    base.z = bed - height * .25f;
                } else if (random.nextFloat() < .6f || paved) {
                    // On the talus apron; paved ground beyond it stays clear.
                    float h = talusHeight(drop, lerp(edge.a.x, edge.b.x, t), lerp(edge.a.y, edge.b.y, t))
                          * .45f * random.nextFloat();
                    Vector3 p = edgePoint(edge, t, bottom + h);
                    base = new Vector3(p.x + edge.nx * width * .2f, p.y + edge.ny * width * .2f, p.z - height * (.3f + .15f * random.nextFloat()));
                } else {
                    // Rolled out onto the ground beyond the apron: smaller.
                    size *= .6f;
                    length *= .6f;
                    width *= .6f;
                    height *= .6f;
                    Vector3 p = edgePoint(edge, t, bottom);
                    float out = m * (.3f + 2.4f * random.nextFloat());
                    base = new Vector3(p.x + edge.nx * out, p.y + edge.ny * out, 0);
                    base.z = topHeight(destination, base.x, base.y) - height * (.3f + .15f * random.nextFloat());
                }
                // Clear of the unit anchor, below the picking headroom, inside this hex but for the cliff itself, and
                // on its bank, except for the submerged rubble beside a sheer cliff.
                if (Math.hypot(base.x - anchor.x, base.y - anchor.y) - length < BoardGeometry.width() * .2f
                      || base.z + height > self.level() * BoardGeometry.level() + headroom(tile)
                      || margin(base.x, base.y, e) < Math.max(length, width) * .55f
                      || (submergedFoot ? !wet(base.x, base.y) : wet(base.x, base.y))) {
                    continue;
                }
                place(destination, rock, base, turn, length, width, height / rock.height(), Kind.ROCK, e, false);
            }
        }
    }

    /** Deepest recess relative to its adjacent cliff columns; reuse the shaped face, including road cuts. */
    private float talusOutlet(Edge edge) {
        float z = lerp(edge.bottom(), edge.top(), .45f);
        // Use the full cliff grid at every dressing LOD, so changing detail never moves a deposit.
        int columns = 2 * TerrainLod.FULL.steps;
        Vector3 before = edgePoint(edge, 1f / columns, z), at = edgePoint(edge, 2f / columns, z);
        float deepest = metres(.15f), outlet = Float.NaN;
        for (int column = 2; column <= columns - 2; column++) {
            Vector3 after = edgePoint(edge, (column + 1f) / columns, z);
            float recess = ((before.x + after.x) * .5f - at.x) * edge.nx
                  + ((before.y + after.y) * .5f - at.y) * edge.ny;
            if (recess > deepest) {
                deepest = recess;
                outlet = column / (float) columns;
            }
            before = at;
            at = after;
        }
        return outlet;
    }

    /**
     * The rock of a fall, of this hex's own ground: blocks straddling the lip of each fall this hex pours over, where
     * the water parts round them, and boulders in the pool at the foot of each fall that pours into it. None on the
     * unit anchor.
     */
    private void fallRocks(List<BoardSurface.Face> destination, Vector3 anchor, Vector3[] crests, int falls,
          int mouths) {
        if (!detail.formations() || !self.detailed() || tile.liquid().volcanic()) { return; }
        float m = metres(1);
        BoardScene.Surface family = BoardScene.Surface.values()[self.family()];
        int perEdge = crests.length / 6;
        List<BoardSurface.Face> bed = destination.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.BED).toList();
        for (int e = 0; e < 6; e++) {
            Edge edge = edge(e);
            Random random = new Random(edge.a.key * 1_000_003L + edge.b.key * 31 + 41);
            int n = (e + 1) % 6;
            float ax = cornerX(self.ix() + CORNER_DX[e]), ay = cornerY(self.iy() + CORNER_DY[e]);
            float bx = cornerX(self.ix() + CORNER_DX[n]), by = cornerY(self.iy() + CORNER_DY[n]);
            float length = (float) Math.hypot(bx - ax, by - ay), ox = (by - ay) / length, oy = -(bx - ax) / length;
            Site other = neighbor(self, e);
            boolean lip = (falls & 1 << e) != 0;
            boolean foot = (mouths & 1 << e) != 0 && other != null && other.liquid() && other.level() - self.level() >= 3;
            if (!lip && !foot) { continue; }
            // Two shoulder blocks where the lip meets its banks, a few breakers on its ledge, or boulders in the pool.
            int count = lip ? 3 + random.nextInt(3) : 3 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                float r = random.nextFloat();
                boolean shoulder = lip && i < 2;
                float size = m * (shoulder ? 2f + 1.5f * r : lip ? .7f + 1.6f * r * r : .8f + 2.4f * r * r);
                float along = shoulder ? i : .15f + .7f * random.nextFloat();
                Vector3 base;
                if (lip) {
                    float k = along * perEdge;
                    int j = Math.min(perEdge - 1, (int) k);
                    Vector3 p = new Vector3(crests[e * perEdge + j]).lerp(crests[(e * perEdge + j + 1) % crests.length],
                          k - j);
                    // A shoulder sits on the bank where the lip ends; a breaker on the ledge just behind the lip, the
                    // water parting round it.
                    float back = shoulder ? size * .35f : size * (.2f + .9f * random.nextFloat());
                    float seat = shoulder ? self.level() * BoardGeometry.level() : p.z - BoardGeometry.hexScale();
                    base = new Vector3(p.x - ox * back, p.y - oy * back, seat + size * .1f);
                } else {
                    // Out in the pool below the fall, on its bed.
                    float out = m * (2.5f + 3 * random.nextFloat());
                    base = new Vector3(ax + (bx - ax) * along - ox * out, ay + (by - ay) * along - oy * out, 0);
                    base.z = BoardSurface.sampleHeight(bed, base.x, base.y, self.level() * BoardGeometry.level());
                }
                BoardShape rock = BoardRocks.rock(family, random.nextInt(64), detail);
                float width = size * (.6f + .4f * random.nextFloat()), height = size * (.5f + .5f * random.nextFloat());
                base.z -= height * .3f;
                if (Math.hypot(base.x - anchor.x, base.y - anchor.y) - size < BoardGeometry.width() * .2f
                      || base.z + height > self.level() * BoardGeometry.level() + headroom(tile)
                      || margin(base.x, base.y, lip ? e : -1) < Math.max(size, width) * .55f) { continue; }
                place(destination, rock, base, (float) Math.atan2(by - ay, bx - ax) + (random.nextFloat() - .5f),
                      size * (1 + .5f * random.nextFloat()), width, height / rock.height(), Kind.ROCK, e, false);
            }
        }
    }

    /**
     * Sparse field cover: loose stones and low shrubs on open ground, from the hex's own seed and outside the unit
     * anchor. Hexes with features of their own (woods, buildings, rubble) keep only those, and biomes their own cover:
     * tundra's tussocks grow with its sward (GpuGroundCover). Placed on the finished top, after the road banks have
     * eased it, so each stands whole on the ground.
     */
    void field(List<BoardSurface.Face> destination) {
        // Cosmetic stones and shrubs share the full-detail tier; they never contribute gameplay cover.
        if (!sculpted || detail != TerrainLod.FULL || !self.detailed() || !BoardScatter.allowed(tile)
              || tile.surface() == BoardScene.Surface.FUNGUS || tile.biome() != BoardScene.Biome.NONE
              || !tile.features().isEmpty()) { return; }
        Vector3 anchor = new Vector3(BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()), 0);
        Random random = new Random(tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L ^ 0x5f1e1dL);
        float m = metres(1);
        int stones = Math.round(ROCKS[self.family()].stones() * (.3f + 1.4f * random.nextFloat()));
        int shrubs = Math.round(ROCKS[self.family()].shrubs() * (.3f + 1.4f * random.nextFloat()));
        // Nothing grows on airless ground (BoardScene.Tile.lunar): the same stones lie there alone.
        if (tile.barren()) { shrubs = 0; }
        for (int i = 0; i < stones + shrubs; i++) {
            boolean shrub = i >= stones;
            float angle = random.nextFloat() * (float) Math.PI * 2;
            float radius = BoardGeometry.width() * (.24f + .2f * random.nextFloat());
            float x = anchor.x + radius * (float) Math.cos(angle), y = anchor.y + radius * (float) Math.sin(angle);
            float r = random.nextFloat();
            float size = m * (shrub ? .7f + .5f * r : .35f + 1.1f * r * r);
            float turn = random.nextFloat() * (float) Math.PI * 2;
            BoardShape rock = shrub ? BoardScatter.bush(tile.surface(), random.nextInt(64))
                  : BoardScatter.rock(tile.surface(), random.nextInt(64), false);
            float scale = shrub ? BoardScatter.bushScale(rock, .7f + .5f * r) : size * .8f;
            // Wholly inside this hex and on its own top, so no neighbour's ground or picking ever meets half of it and
            // nothing hangs over a receding rim.
            float keep = shrub ? BoardScatter.BUSH_RADIUS * scale : size * .8f;
            // Clear of the unit anchor too, as the rock kit keeps it.
            if (margin(x, y, -1) < keep || clearance(x, y) < keep
                  || Math.hypot(x - anchor.x, y - anchor.y) - keep < BoardGeometry.width() * .2f) { continue; }
            // Airy bushes keep their authored proportions; stones remain half buried in the drawn top.
            float variation = random.nextFloat();
            float height = shrub ? scale * rock.height() : size * .8f * (.35f + .5f * variation);
            Vector3 base = new Vector3(x, y, topHeight(destination, x, y) - (shrub ? .02f * scale : height * .35f));
            float widthVariation = .7f + .3f * random.nextFloat();
            place(destination, rock, base, turn, scale, shrub ? scale : scale * widthVariation,
                  height / rock.height(), shrub ? Kind.PLANT : Kind.ROCK);
        }
    }

    /**
     * Planting pits for the trees of paved ground, as in a pavement: a square of loose earth inside a low kerb at each
     * tree's foot, lying on the slab. They are thin dressing: drawn and picked with the ground, but they never raise
     * what stands on it, so the tree and any unit keep the game level.
     */
    private void pits(List<BoardSurface.Face> destination) {
        if (!detail.formations() || !self.detailed() || tile.surface() != BoardScene.Surface.CONCRETE) { return; }
        float m = metres(1);
        List<BoardScene.Feature> trees = tile.features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE && !feature.authoredPlacement()).toList();
        // Every pit of a stand has the same size, small enough that no two of them touch.
        float spacing = Float.POSITIVE_INFINITY;
        for (int i = 0; i < trees.size(); i++) {
            for (int j = i + 1; j < trees.size(); j++) {
                spacing = Math.min(spacing, BoardGeometry.hexScale() * Math.max(Math.abs(trees.get(i).x() - trees.get(j).x()),
                      Math.abs(trees.get(i).y() - trees.get(j).y())));
            }
        }
        for (BoardScene.Feature feature : trees) {
            float x = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
            float y = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
            // Sized to the drawn crown, whose trunk is wider than life too.
            float outer = Math.min(m * (1 + .45f * feature.scale()), .46f * spacing), inner = outer - m * .25f;
            if (margin(x, y, -1) < outer * 1.45f) { continue; }
            float ground = topHeight(destination, x, y), kerb = ground + m * .12f, soil = ground + m * .05f;
            float[][] square = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
            for (int k = 0; k < 4; k++) {
                float[] a = square[k], b = square[(k + 1) % 4];
                // The kerb: its top, the face toward the pavement (its foot set into the slab) and the face toward the soil.
                dressing(destination, false, new Vector3(x + a[0] * outer, y + a[1] * outer, kerb),
                      new Vector3(x + b[0] * outer, y + b[1] * outer, kerb), new Vector3(x + b[0] * inner, y + b[1] * inner, kerb),
                      new Vector3(x + a[0] * inner, y + a[1] * inner, kerb));
                dressing(destination, false, new Vector3(x + a[0] * outer, y + a[1] * outer, ground - m * .02f),
                      new Vector3(x + b[0] * outer, y + b[1] * outer, ground - m * .02f),
                      new Vector3(x + b[0] * outer, y + b[1] * outer, kerb), new Vector3(x + a[0] * outer, y + a[1] * outer, kerb));
                dressing(destination, false, new Vector3(x + b[0] * inner, y + b[1] * inner, soil),
                      new Vector3(x + a[0] * inner, y + a[1] * inner, soil),
                      new Vector3(x + a[0] * inner, y + a[1] * inner, kerb), new Vector3(x + b[0] * inner, y + b[1] * inner, kerb));
                // The earth, mounded a little around the trunk.
                dressing(destination, true, new Vector3(x + a[0] * inner, y + a[1] * inner, soil),
                      new Vector3(x + b[0] * inner, y + b[1] * inner, soil), new Vector3(x, y, soil + m * .05f));
            }
        }
    }

    /** Dressing rests on the emitted ground or shallow bed, never on an already placed rock or shrub. */
    private float topHeight(List<BoardSurface.Face> destination, float x, float y) {
        return BoardSurface.sampleHeight(destination.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.TOP
                    || self.liquid() && face.finish() == BoardSurface.Finish.BED).toList(), x, y, groundHeight(x, y));
    }

    /** One flat face of a tree pit, its earth or its kerb, counter-clockwise from above or outside, own vertices. */
    private void dressing(List<BoardSurface.Face> destination, boolean earth, Vector3... points) {
        Vector3 normal = new Vector3(points[1]).sub(points[0]).crs(new Vector3(points[2]).sub(points[0])).nor();
        float tint = earth ? .45f * hash(Float.floatToIntBits(points[0].x), Float.floatToIntBits(points[0].y)) : 1;
        for (Vector3 p : points) {
            // Only the kerb's foot is shaded by the pavement around it.
            float occlusion = normal.z > .5f || p.z > self.level() * BoardGeometry.level() ? 1 : .8f;
            shades.put(p, new Shade(normal, Kind.PIT, occlusion, self.level(), 99, 99, tint));
        }
        for (int i = 1; i + 1 < points.length; i++) {
            destination.add(new BoardSurface.Face(points[0], points[i], points[i + 1], BoardSurface.Finish.DRESSING));
        }
    }

    /** Average rockiness of the steps through a corner (each pair of its hexes at different levels); else fallback. */
    private static float cornerRock(Corner corner, float fallback) {
        float sum = 0;
        int steps = 0;
        for (int i = 0; i < 3; i++) {
            Site p = corner.around[i], q = corner.around[(i + 1) % 3];
            if (p == null || q == null || p.level() == q.level()) { continue; }
            sum += prominence(p.level() > q.level() ? drop(p, q) : drop(q, p));
            steps++;
        }
        return steps == 0 ? fallback : sum / steps;
    }

    /** Adjoining slopes and cliffs share the corner's material span, including soil and debris deposits. */
    private Shade cornerShade(Corner corner, Vector3 p, float weight, Shade own) {
        // Fixed corners have no shared span: their low/mid/high placeholders are zero, not terrain heights.
        if (weight <= 0 || corner.pinned || self.family() == CONCRETE && !corner.waterfall) { return own; }
        int[] span = corner.span(p.z);
        float m = metres(1), level = BoardGeometry.level();
        float height = (p.z - span[0] * level) / m, depth = (span[1] * level - p.z) / m;
        float contact = 1 - .4f * (float) Math.exp(-Math.max(0, height) / 1.6f);
        float shelter = 1 - .25f * bump((depth - 2.2f) / 1.6f);
        float occlusion = corner.waterfall ? lerp(own.occlusion(), contact * shelter, weight) : own.occlusion();
        return new Shade(own.normal(), own.kind(), occlusion,
              lerp(own.level(), cornerRock(corner, own.level()), weight), lerp(own.rim(), height, weight),
              lerp(own.foot(), depth, weight), own.tint());
    }

    /**
     * The point nearest (x, y) on the way to the unit anchor that lies on this hex's own top at least {@code margin}
     * inside its outline, so trees and ground clutter stand on the ground, never over a receding rim or a step's slope.
     */
    float[] settle(float x, float y, float margin) {
        if (outline == null || clearance(x, y) >= margin) { return new float[] { x, y }; }
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        float inside = 0, outside = 1;
        for (int i = 0; i < 12; i++) {
            float f = (inside + outside) / 2;
            if (clearance(cx + (x - cx) * f, cy + (y - cy) * f) >= margin) {
                inside = f;
            } else {
                outside = f;
            }
        }
        return new float[] { cx + (x - cx) * inside, cy + (y - cy) * inside };
    }

    /** Signed distance from (x, y) to the outline of this hex's top, positive inside; infinite before it is built. */
    float clearance(float x, float y) {
        if (outline == null) { return Float.POSITIVE_INFINITY; }
        boolean inside = false;
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            Vector3 a = outline.get(i), b = outline.get(j);
            if (a.y > y != b.y > y && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) { inside = !inside; }
            nearest = Math.min(nearest, distance(x, y, a.x, a.y, b.x, b.y));
        }
        return inside ? nearest : -nearest;
    }

    /** Whether (x, y) lies inside a water hex's waterline. */
    private boolean wet(float x, float y) {
        if (waterline == null) { return false; }
        boolean inside = false;
        for (int i = 0, j = waterline.length - 1; i < waterline.length; j = i++) {
            Vector3 a = waterline[i], b = waterline[j];
            if (a.y > y != b.y > y && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) { inside = !inside; }
        }
        return inside;
    }

    /** Distance from (x, y) to this hex's boundary, ignoring edge {@code skip} (-1 for none); negative outside. */
    private float margin(float x, float y, int skip) {
        float result = Float.POSITIVE_INFINITY;
        for (int k = 0; k < 6; k++) {
            if (k == skip) { continue; }
            float ax = cornerX(self.ix() + CORNER_DX[k]), ay = cornerY(self.iy() + CORNER_DY[k]);
            float ex = cornerX(self.ix() + CORNER_DX[(k + 1) % 6]) - ax, ey = cornerY(self.iy() + CORNER_DY[(k + 1) % 6]) - ay;
            result = Math.min(result, (ex * (y - ay) - ey * (x - ax)) / (float) Math.hypot(ex, ey));
        }
        return result;
    }

    /** One rock or shrub mass, scaled and turned about z; flat faces with their own vertices, darker at the root. */
    private void place(List<BoardSurface.Face> destination, BoardShape rock, Vector3 base, float turn,
          float sx, float sy, float sz, Kind kind) {
        place(destination, rock, base, turn, sx, sy, sz, kind, -1);
    }

    private void place(List<BoardSurface.Face> destination, BoardShape rock, Vector3 base, float turn,
          float sx, float sy, float sz, Kind kind, int landEdge) {
        place(destination, rock, base, turn, sx, sy, sz, kind, landEdge, true);
    }

    private void place(List<BoardSurface.Face> destination, BoardShape rock, Vector3 base, float turn,
          float sx, float sy, float sz, Kind kind, int landEdge, boolean cosmetic) {
        float c = (float) Math.cos(turn), s = (float) Math.sin(turn), m = metres(1);
        float tint = hash(Float.floatToIntBits(base.x), Float.floatToIntBits(base.y));
        float height = rock.height() * sz;
        var bridgeApproaches = bridgeApproaches(tile.coords());
        if (tunnels == null) {
            tunnels = new ArrayList<>(BoardTunnel.entrances(scene, tile));
            for (int direction = 0; direction < 6; direction++) {
                var neighbor = scene.tile(tile.coords().translated(direction));
                if (neighbor != null) { tunnels.addAll(BoardTunnel.entrances(scene, neighbor)); }
            }
        }
        if (nearbyWays == null) { nearbyWays = BoardRoad.nearbyWays(scene, tile); }
        if (obstacles == null) { obstacles = new BoardObstacles(scene, tile); }
        Site adjoining = landEdge < 0 ? null : neighbor(self, landEdge);
        boolean pitBoundary = adjoining != null && adjoining.ultraSublevel();
        float radius = 0;
        if (pitBoundary || !nearbyWays.isEmpty() || !tunnels.isEmpty() || !bridgeApproaches.isEmpty() || !obstacles.isEmpty()) {
            for (var polygon : rock.polygons()) {
                for (var p : polygon.points()) { radius = Math.max(radius, (float) Math.hypot(p.x * sx, p.y * sy)); }
            }
        }
        if (pitBoundary) {
            // Keep the rock on the finished land rim, with its whole footprint supported above the opening.
            float[] spot = settle(base.x, base.y, radius);
            float burial = self.level() * BoardGeometry.level() - base.z;
            base = new Vector3(spot[0], spot[1], topHeight(destination, spot[0], spot[1]) - burial);
            if (clearance(base.x, base.y) < radius || Math.hypot(base.x - self.x(), base.y - self.y()) - radius
                  < BoardGeometry.width() * .2f) { return; }
        }
        for (var tunnel : tunnels) {
            if (tunnel.obstructs(base, radius, height)) { return; }
        }
        if (!bridgeApproaches.isEmpty()) {
            base = besideBridge(destination, base, radius, height);
            if (base == null) { return; }
        }
        // Rocks and shrubs may stand beside the road, never on its carriageway or shoulder.
        if (BoardRoad.distance(nearbyWays, base.x, base.y) < BoardRoad.SHOULDER + radius / BoardGeometry.hexScale()) { return; }
        if (self.liquid() && kind == Kind.ROCK) {
            // A lip's nominal level and a pool's waterline are not foundations. Extend the closed rock down into
            // the actual bank/bed across its footprint, keeping its visible summit where it was placed.
            List<BoardSurface.Face> ground = destination.stream().filter(face -> face.finish() != BoardSurface.Finish.OUTCROP
                  && face.finish() != BoardSurface.Finish.DRESSING && face.finish() != BoardSurface.Finish.ICE).toList();
            float foundation = Float.POSITIVE_INFINITY;
            // Flat-shaded polygons repeat their shared corners. Their support height only needs sampling once.
            Set<Vector3> sampled = new HashSet<>();
            for (BoardShape.Polygon polygon : rock.polygons()) {
                for (Vector3 point : polygon.points()) {
                    if (!sampled.add(point)) { continue; }
                    float x = base.x + c * point.x * sx - s * point.y * sy;
                    float y = base.y + s * point.x * sx + c * point.y * sy;
                    float groundZ = BoardSurface.sampleHeight(ground, x, y, Float.NaN);
                    if (Float.isFinite(groundZ)) { foundation = Math.min(foundation, groundZ - height * .3f); }
                }
            }
            if (!Float.isFinite(foundation)) { return; }
            foundation = Math.min(base.z, foundation);
            height += base.z - foundation;
            base = new Vector3(base.x, base.y, foundation);
            sz = height / rock.height();
        }
        if (pitBoundary && clearance(base.x, base.y) < radius
              || cosmetic && !visibleScatter(base, height) || obstructed(base, radius, height)) { return; }
        for (BoardShape.Polygon polygon : rock.polygons()) {
            // Preserve authored tissue identity once per polygon; wood and unknown roles stay opaque.
            Kind tissue = kind == Kind.PLANT && BoardScatter.leaf(polygon.material()) ? Kind.LEAF : kind;
            Vector3 n = polygon.normal();
            float nx = n.x / sx, ny = n.y / sy;
            Vector3 normal = new Vector3(c * nx - s * ny, s * nx + c * ny, n.z / sz).nor();
            Vector3[] points = new Vector3[polygon.points().length];
            for (int i = 0; i < points.length; i++) {
                Vector3 q = polygon.points()[i];
                float x = q.x * sx, y = q.y * sy, z = q.z * sz;
                points[i] = new Vector3(base.x + c * x - s * y, base.y + s * x + c * y, base.z + z);
                float occlusion = .45f + .55f * smooth(z / (height * .7f));
                if (kind == Kind.PLANT) {
                    var color = polygon.color();
                    shades.put(points[i], new Shade(normal, tissue, occlusion, self.level(), color.r, color.g, color.b));
                } else {
                    shades.put(points[i], new Shade(normal, kind, occlusion, self.level(), z / m, (height - z) / m, tint));
                }
            }
            for (int i = 1; i + 1 < points.length; i++) {
                destination.add(new BoardSurface.Face(points[0], points[i], points[i + 1],
                      BoardSurface.Finish.OUTCROP, landEdge, cosmetic));
            }
        }
    }

    /** Shared final placement check for baked dressing and captured scatter, including neighbouring structures. */
    boolean obstructed(Vector3 base, float radius, float height) {
        if (obstacles == null) { obstacles = new BoardObstacles(scene, tile); }
        return obstacles.obstructs(base, radius, height);
    }

    boolean visibleScatter(Vector3 base, float height) { return BoardScatter.visible(scene, tile, base, height); }

    /**
     * Move a rock's or shrub's complete footprint to the nearest supported side of a bridge passage; null where neither
     * side has room, as on a narrow promontory, since nothing may stand on the deck or its approach.
     */
    private Vector3 besideBridge(List<BoardSurface.Face> destination, Vector3 base, float radius, float height) {
        var bridgeApproaches = bridgeApproaches(tile.coords());
        if (bridgeApproaches.stream().noneMatch(approach -> approach.obstructs(base, radius, height))) { return base; }
        Vector3 nearest = null;
        for (var approach : bridgeApproaches) {
            for (int side : new int[] { -1, 1 }) {
                var point = new Vector3(base);
                // Settling on an irregular rim can pull a candidate back toward the passage; push out again.
                for (int i = 0; i < 8; i++) {
                    point = approach.beside(point, radius + metres(.5f) * i, side);
                    float[] spot = settle(point.x, point.y, radius * .65f);
                    point.set(spot[0], spot[1], base.z);
                    var candidate = point;
                    if (margin(point.x, point.y, -1) < radius * .55f
                          || tunnels.stream().anyMatch(tunnel -> tunnel.obstructs(candidate, radius, height))
                          || bridgeApproaches.stream().anyMatch(other -> other.obstructs(candidate, radius, height))) { continue; }
                    if (nearest == null || base.dst2(point) < base.dst2(nearest)) { nearest = new Vector3(point); }
                    break;
                }
            }
        }
        if (nearest == null) { return null; }
        var ground = destination.stream().filter(face -> face.finish() != BoardSurface.Finish.OUTCROP
              && face.finish() != BoardSurface.Finish.DRESSING && face.finish() != BoardSurface.Finish.ICE).toList();
        float oldGround = BoardSurface.sampleHeight(ground, base.x, base.y, groundHeight(base.x, base.y));
        nearest.z = BoardSurface.sampleHeight(ground, nearest.x, nearest.y, groundHeight(nearest.x, nearest.y))
              + Math.min(0, base.z - oldGround);
        return nearest;
    }

    /**
     * Shades of one edge's boundary samples. A cliff rim gets a normal rounded between the ground and the face below,
     * computed exactly as the cliff grid computes its own top row, which reuses these vertices.
     */
    private void rimShades(int e) {
        Edge edge = edge(e);
        Vector3[] points = rims[e];
        int count = points.length - 1;
        boolean drop = edge.upper == self && edge.profiled;
        boolean planar = planarConcrete(self);
        var bridge = drop ? BoardNaturalBridge.bankApproach(scene, tile, BoardGeometry.edgeDirection(e)) : null;
        Vector3[] below = null;
        if (drop && !planar) {
            float[] rows = rows(edge.bottom(), edge.top());
            float next = rows[rows.length - 2];
            below = new Vector3[count + 1];
            for (int i = 0; i <= count; i++) { below[i] = edgePoint(edge, canonical(e, i, count), next); }
        }
        for (int i = 0; i < count; i++) {
            Vector3 p = points[i];
            float[] seam = seamDistances(p.x, p.y);
            Edge previous = i == 0 ? edge((e + 5) % 6) : null;
            if (drop || previous != null && previous.upper == self && previous.profiled) {
                // The top and cliff meet at this sculpted rim, which can be metres from the
                // approximate segment used for interior distances. Both must start their cover here.
                seam[0] = 0;
            }
            Edge foot = edge.lower == self && edge.profiled ? edge
                  : previous != null && previous.lower == self && previous.profiled ? previous : null;
            if (foot != null) {
                // These vertices are the actual sculpted cliff foot. The approximate lattice distance
                // can be metres away and would cut off the cliff's debris exactly at the ground seam.
                seam[1] = 0;
            }
            Vector3 normal;
            if (planar) {
                normal = new Vector3(0, 0, 1);
            } else if (drop) {
                normal = gridNormal(edge, e, points, null, below, i, count).add(0, 0, 1.2f).nor();
            } else if (foot != null && foot.footRoom > 0) {
                normal = new Vector3(0, 0, 1);
            } else if (edge.upper == null || joined(self, neighbor(self, e))) {
                normal = groundNormal(p);
            } else {
                normal = new Vector3(0, 0, 1);
            }
            if (bridge != null) {
                float join = bridge.weight(p);
                normal.lerp(Vector3.Z, join).nor();
                seam[0] = lerp(seam[0], 99, join);
            }
            // At the foot of a step with room this ground and the step's lowest row take one occlusion, from the step.
            float occlusion = foot != null && foot.footRoom > 0 ? footOcclusion(foot, p)
                  : groundOcclusion(seam[1]);
            shades.putIfAbsent(p, new Shade(normal, Kind.GROUND, occlusion, self.level(), seam[0], seam[1],
                  cliffTint(p.x, p.y, seam)));
        }
    }

    /** The vertical component of a triangle's unit normal: 1 lying flat and facing up, negative when folded over. */
    private static float upward(Vector3 a, Vector3 b, Vector3 c) {
        float ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z, vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return nz / Math.max((float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-12f);
    }

    /** Only follow diagonals that can finish a shore strip without folding a concave rim. */
    private static boolean[][] stripReachable(List<Vector3> outer, List<Vector3> inner) {
        int n = outer.size() - 1, m = inner.size() - 1;
        boolean[][] reaches = new boolean[n + 1][m + 1];
        for (int i = n; i >= 0; i--) {
            for (int j = m; j >= 0; j--) {
                Vector3 a = inner.get(j % inner.size()), b = outer.get(i % outer.size());
                reaches[i][j] = i == n && j == m
                      || i < n && reaches[i + 1][j] && upward(a, b, outer.get((i + 1) % outer.size())) >= 0
                      || j < m && reaches[i][j + 1] && upward(a, b, inner.get((j + 1) % inner.size())) >= 0;
            }
        }
        return reaches;
    }

    private Shade groundShade(Vector3 p) {
        float[] seam = seamDistances(p.x, p.y);
        return new Shade(groundNormal(p), Kind.GROUND, groundOcclusion(seam[1]), self.level(), seam[0], seam[1],
              cliffTint(p.x, p.y, seam));
    }

    /**
     * Occlusion of the ground where a step with room meets it at its foot, from that one step, so the step's lowest row
     * and the lower hex's own boundary take exactly the same value there.
     */
    private static float footOcclusion(Edge edge, Vector3 p) {
        float reach = distance(p.x, p.y, edge.a.x, edge.a.y, edge.b.x, edge.b.y)
              - edge.footRoom * prominence(edge.drop);
        return groundOcclusion(Math.max(0, reach) / metres(1));
    }

    /**
     * The contact shade of ground in the corner at a rise's foot, with the decay of the wall's own foot
     * (wallOcclusion); open ground and rims are unoccluded. How much sky the rise hides from the ground further out
     * is the sky visibility's (GpuSkyVisibility), whose cells are 4 m. Poured ground takes the same per pixel
     * (terrain-sculpt.frag).
     */
    private static float groundOcclusion(float footMetres) {
        return 1 - .3f * (float) Math.exp(-footMetres / 1.6f);
    }

    /** Distances in metres to the nearest drop (rim) and rise (cliff foot) at this hex's level. */
    private float[] seamDistances(float x, float y) {
        if (drops == null) { segments(); }
        float m = metres(1);
        return new float[] { Math.clamp(nearest(drops, x, y) / m, 0, 99), Math.clamp(nearest(rises, x, y) / m, 0, 99) };
    }

    /**
     * Ground vertex tint: the height in levels of the nearer of the closest drop and rise, as .3 + .1 per level up to
     * seven. Linear, so it interpolates meaningfully; the shader tells earth banks from rock cliffs by it.
     */
    private float cliffTint(float x, float y, float[] seam) {
        float levels = seam[0] <= seam[1] ? nearestLevels(drops, x, y) : nearestLevels(rises, x, y);
        return .3f + .1f * Math.min(levels, 7);
    }

    // ---- Cliffs -----------------------------------------------------------------------------------------------

    /** Canonical rows of a wall spanning [bottom, top], including both ends. */
    private float[] rows(float bottom, float top) {
        float level = BoardGeometry.level();
        List<Float> result = new ArrayList<>();
        result.add(bottom);
        int first = (int) Math.floor(bottom / level) - 1, last = (int) Math.ceil(top / level) + 1;
        for (int l = first; l <= last; l++) {
            for (float fraction : ROWS) {
                float z = (l + fraction) * level;
                if (z > bottom + EPSILON * level && z < top - EPSILON * level) { result.add(z); }
            }
        }
        result.add(top);
        result.sort(Float::compare);
        float[] array = new float[result.size()];
        for (int i = 0; i < array.length; i++) { array[i] = result.get(i); }
        return array;
    }

    /**
     * Cliff faces for this hex's exposed sides. Water mouths constrain the same rock grid to their crest and
     * receiving bed. Road gates and the board's plinth retain their own boundaries.
     */
    List<BoardSurface.Face> walls(List<BoardSurface.Side> sides, Map<Coords, BoardSurface> neighbors) {
        List<BoardSurface.Face> result = new ArrayList<>();
        Map<Integer, List<BoardSurface.Side>> byEdge = new TreeMap<>();
        for (BoardSurface.Side side : sides) { byEdge.computeIfAbsent(side.edge(), key -> new ArrayList<>()).add(side); }
        for (var entry : byEdge.entrySet()) {
            int e = entry.getKey();
            Edge edge = self.sculpted() ? edge(e) : null;
            // Sides sample the unsculpted topography used for roads and water mouths. A narrow bank there can put
            // a side's foot at the waterline, below the sculpted bank's boundary. Dry cliffs must still use the
            // canonical grid, or a straight fallback pulls away from the adjoining sculpted cliff and its bank.
            if (edge != null && edge.upper == self && self.liquid()
                  && (edge.lower == null ? (falls & 1 << e) != 0 : edge.lower.liquid())) {
                canonicalWall(e, edge, entry.getValue(), result);
            } else if (edge != null && edge.upper == self && edge.profiled
                  && (!self.liquid() || !edge.lower.liquid() || spans(entry.getValue(), e, edge.bottom()))) {
                canonicalWall(e, edge, List.of(), result);
            } else if (edge != null && edge.upper == self && edge.lower == null
                  && (edge.a.low != edge.a.high || edge.b.low != edge.b.high)) {
                canonicalWall(e, edge, entry.getValue(), result);
            } else {
                for (BoardSurface.Side side : entry.getValue()) { straightWall(side, result); }
            }
        }
        result.replaceAll(face -> {
            int e = face.landEdge();
            boolean concrete = self.family() == CONCRETE;
            if (!concrete && neighbor(self, e) != null) { return face; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            Vector3 a = coast.corner(self.coords(), e), b = coast.corner(self.coords(), e + 1);
            float projection = (float) Math.atan2(a.x - b.x, b.y - a.y);
            return new BoardSurface.Face(wallVertex(face.a(), normal, projection), wallVertex(face.b(), normal, projection),
                  wallVertex(face.c(), normal, projection), face.finish(), e);
        });
        return result;
    }

    /** Poured faces and the board's cut keep their own normals and a fixed texture frame, separate from the ground. */
    private Vector3 wallVertex(Vector3 source, Vector3 normal, float projection) {
        Vector3 vertex = new Vector3(source);
        Shade shade = shades.get(source);
        shades.put(vertex, new Shade(normal, shade.kind(), shade.occlusion(), shade.level(),
              shade.rim(), shade.foot(), shade.tint(), projection));
        return vertex;
    }

    /** Whether the sides of one edge, as the shore moves its corners, cover it completely at one foot height. */
    private boolean spans(List<BoardSurface.Side> group, int e, float bottom) {
        float covered = 0;
        for (BoardSurface.Side side : group) {
            if (Math.abs(side.lowA() - bottom) > .01f || Math.abs(side.lowB() - bottom) > .01f) { return false; }
            covered += (float) Math.hypot(side.b().x - side.a().x, side.b().y - side.a().y);
        }
        Edge edge = edge(e);
        float length = (float) Math.hypot(edge.b.x + edge.b.move()[0] - edge.a.x - edge.a.move()[0],
              edge.b.y + edge.b.move()[1] - edge.a.y - edge.a.move()[1]);
        return Math.abs(covered - length) < .01f * length;
    }

    private void straightWall(BoardSurface.Side side, List<BoardSurface.Face> result) {
        int from = result.size();
        // Split at this hex's own boundary samples, so the face shares every vertex of the rim above it.
        Vector3 start = BoardGeometry.corner(tile.coords(), tile.elevation(), side.edge());
        Vector3 end = BoardGeometry.corner(tile.coords(), tile.elevation(), side.edge() + 1);
        float ta = along(side.edge(), side.a()), tb = along(side.edge(), side.b());
        int count = sculpted ? topSamples(side.edge()) : 1;
        TreeSet<Float> cuts = new TreeSet<>(List.of(ta, tb));
        for (int i = 1; i < count; i++) {
            float t = i / (float) count;
            if (t > ta + .0001f && t < tb - .0001f) { cuts.add(t); }
        }
        Vector3 previous = rimPoint(side, ta, side.a());
        Vector3 previousLow = new Vector3(previous.x, previous.y, side.lowA());
        for (float t : cuts.tailSet(ta, false)) {
            float u = (t - ta) / (tb - ta);
            Vector3 top = rimPoint(side, t, t == tb ? side.b() : new Vector3(start).lerp(end, t));
            top.z = side.a().z + (side.b().z - side.a().z) * u;
            Vector3 low = new Vector3(top.x, top.y, side.lowA() + (side.lowB() - side.lowA()) * u);
            addQuad(result, previous, previousLow, low, top, BoardSurface.Finish.WALL, side.edge());
            previous = top;
            previousLow = low;
        }
        shadeWall(side, result, from);
    }

    /** Retaining faces keep wall projection data even when a road cuts their rim or raises their foot. */
    private void shadeWall(BoardSurface.Side side, List<BoardSurface.Face> result, int from) {
        float ta = along(side.edge(), side.a()), tb = along(side.edge(), side.b()), m = metres(1);
        Map<Vector3, Vector3> normals = new IdentityHashMap<>();
        for (int i = from; i < result.size(); i++) {
            BoardSurface.Face face = result.get(i);
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(p, key -> new Vector3()).add(normal);
            }
        }
        normals.forEach((p, normal) -> {
            float t = Math.clamp((along(side.edge(), p) - ta) / (tb - ta), 0, 1);
            float rim = lerp(side.a().z, side.b().z, t), foot = lerp(side.lowA(), side.lowB(), t);
            shades.put(p, new Shade(normal.nor(), wallKind(neighbor(self, side.edge())), 1,
                  self.family() == CONCRETE && self.road() ? 0 : prominence(rim - foot),
                  Math.max(0, p.z - foot) / m, Math.max(0, rim - p.z) / m, bed(p, self.family())));
        });
    }

    /** A vertical face cut where a reserved carriageway meets ground at another height, facing (fx, fy), the lower side. */
    void cutWall(Vector3 topA, Vector3 lowA, Vector3 topB, Vector3 lowB, float fx, float fy,
          List<BoardSurface.Face> result) {
        if ((topB.y - topA.y) * fx - (topB.x - topA.x) * fy < 0) {
            Vector3 top = topA, low = lowA;
            topA = topB; lowA = lowB; topB = top; lowB = low;
        }
        Vector3 normal = new Vector3(fx, fy, 0).nor();
        float m = metres(1);
        for (var p : List.of(topA, lowA, topB, lowB)) {
            boolean first = p == topA || p == lowA;
            float low = first ? lowA.z : lowB.z, top = first ? topA.z : topB.z;
            shades.put(p, new Shade(normal, Kind.CLIFF, 1, 0, (p.z - low) / m, (top - p.z) / m, .5f));
        }
        addQuad(result, topA, lowA, lowB, topB, BoardSurface.Finish.WALL, -1);
    }

    /** The top of a straight face: this hex's own canonical boundary point where one exists, else the side's point. */
    private Vector3 rimPoint(BoardSurface.Side side, float t, Vector3 fallback) {
        if (!sculpted) { return new Vector3(fallback); }
        int count = topSamples(side.edge());
        float scaled = t * count;
        if (Math.abs(scaled - Math.round(scaled)) > .0005f) { return new Vector3(fallback); }
        return edgePoint(edge(side.edge()), canonical(side.edge(), Math.round(scaled), count), fallback.z);
    }

    /** Shared cliff geometry, also used to root Rough on either side of a step. */
    private Vector3[][] wallGrid(int e, Edge edge) {
        return wallGrid(e, edge, wallColumns(e, edge, List.of()), List.of());
    }

    /** Keep the native cliff samples and every point where a water mouth changes the exposed rim. */
    private float[] wallColumns(int e, Edge edge, List<BoardSurface.Side> contour) {
        TreeSet<Float> cuts = new TreeSet<>();
        int count = samples(edge);
        for (int i = 0; i <= count; i++) { cuts.add(i / (float) count); }
        for (BoardSurface.Side side : contour) {
            cuts.add(Math.clamp(along(e, side.a()), 0, 1));
            cuts.add(Math.clamp(along(e, side.b()), 0, 1));
        }
        List<Float> distinct = new ArrayList<>();
        for (float t : cuts) {
            if (distinct.isEmpty() || t - distinct.getLast() > .0001f) { distinct.add(t); }
        }
        float[] result = new float[distinct.size()];
        for (int i = 0; i < result.length; i++) { result[i] = distinct.get(i); }
        return result;
    }

    private BoardSurface.Side wallSide(int e, List<BoardSurface.Side> contour, float t) {
        BoardSurface.Side nearest = null;
        float distance = Float.POSITIVE_INFINITY;
        for (BoardSurface.Side side : contour) {
            float a = along(e, side.a()), b = along(e, side.b());
            if (t >= a - .0001f && t <= b + .0001f) { return side; }
            float gap = Math.max(a - t, t - b);
            if (gap < distance) {
                distance = gap;
                nearest = side;
            }
        }
        // Joined crests curve away from the lattice corner. Keep the rock underneath all the way to that
        // shared corner, using the nearest rim endpoint instead of dropping the remaining cliff columns.
        return (falls & 1 << e) != 0 ? nearest : null;
    }

    private Vector3[][] wallGrid(int e, Edge edge, float[] columns, List<BoardSurface.Side> contour) {
        float bottom = edge.bottom(), top = edge.top();
        if (!contour.isEmpty()) {
            bottom = Float.POSITIVE_INFINITY;
            top = Float.NEGATIVE_INFINITY;
            for (BoardSurface.Side side : contour) {
                bottom = Math.min(bottom, Math.min(side.lowA(), side.lowB()));
                top = Math.max(top, Math.max(side.a().z, side.b().z));
            }
        }
        float[] rows = edge.simpleConcrete ? new float[] { bottom, top } : rows(bottom, top);
        int last = rows.length - 1;
        Vector3[][] grid = new Vector3[rows.length][columns.length];
        // A coarse natural cliff draws only every stride-th row of its interior columns (see canonicalWall); the rows
        // between lie on those faces, so they are interpolated rather than sculpted.
        int stride = contour.isEmpty() && edge.lower != null && !edge.lower.liquid() && edge.upper.family() != CONCRETE
              && edge.lower.family() != CONCRETE ? detail.rowStride : 1;
        for (int i = 0; i < columns.length; i++) {
            float t = columns[i];
            // Match the top's integer-first sampling exactly. 1 - (i / count) rounds differently
            // from (count - i) / count and can open subpixel cracks along the cliff foot.
            int count = samples(edge), sample = Math.round(t * count);
            float parameter = Math.abs(t * count - sample) < .0001f ? canonical(e, sample, count)
                  : corner(self, e) == edge.a ? t : 1 - t;
            BoardSurface.Side side = wallSide(e, contour, t);
            boolean coarse = stride > 1 && i > 0 && i < columns.length - 1;
            for (int r = 0; r <= last; r++) {
                if (coarse && r > 0 && (last - r) % stride != 0) { continue; }
                // Ordinary cliffs retain their exact rim. A water mouth only constrains this same rock surface.
                Vector3 point = side != null ? self.liquid() ? waterWallPoint(e, edge, parameter, t, rows[r], side)
                      : gradedWallPoint(e, edge, parameter, t, rows[r], side)
                      : contour.isEmpty() && r == last && edge.upper == self && rims[e] != null
                            && rims[e].length == columns.length ? new Vector3(rims[e][i])
                            : edgePoint(edge, parameter, rows[r]);
                if (side == null && !contour.isEmpty()) { point.z = bottom; }
                grid[r][i] = point;
            }
            for (int r = 1; coarse && r < last; r++) {
                if (grid[r][i] != null) { continue; }
                int low = r - 1, high = r + 1;
                while (grid[high][i] == null) { high++; }
                grid[r][i] = new Vector3(grid[low][i]).lerp(grid[high][i], (rows[r] - rows[low]) / (rows[high] - rows[low]));
                grid[r][i].z = rows[r];
            }
        }
        return grid;
    }

    /** The side's own rim and foot constrain the contacts, while the exposed face keeps the canonical cliff profile. */
    private Vector3 gradedWallPoint(int e, Edge edge, float parameter, float t, float z, BoardSurface.Side side) {
        float a = along(e, side.a()), b = along(e, side.b());
        float u = Math.clamp((t - a) / Math.max(.00001f, b - a), 0, 1);
        float low = lerp(side.lowA(), side.lowB(), u), high = lerp(side.a().z, side.b().z, u);
        z = Math.clamp(z, low, high);
        float contact = Math.min(metres(1.5f), (high - low) * .5f);
        if (contact < EPSILON) { return outlinePoint(e, t, z); }
        Vector3 point = edgePoint(edge, parameter, z);
        // The two contact bands never overlap, even where the exposed wall tapers to nothing.
        float boundary = z - low < high - z ? low : high;
        float weight = 1 - smooth(Math.abs(z - boundary) / contact);
        if (weight > 0) {
            Vector3 rim = outlinePoint(e, t, boundary);
            if (weight == 1) { return rim; }
            Vector3 offset = rim.sub(edgePoint(edge, parameter, boundary));
            point.mulAdd(offset, weight);
        }
        return point;
    }

    /** Clip the shared cliff to the mouth's rim and bed; recess only rock that would enter the falling sheet. */
    private Vector3 waterWallPoint(int e, Edge edge, float parameter, float t, float z, BoardSurface.Side side) {
        float a = along(e, side.a()), b = along(e, side.b());
        float u = Math.clamp((t - a) / Math.max(.00001f, b - a), 0, 1);
        Vector3 rim = new Vector3(side.a()).lerp(side.b(), u);
        float low = lerp(side.lowA(), side.lowB(), u), m = metres(1);
        // A bank with no height left can round its low end a hair above the rim.
        if (rim.z - low < .0001f * m) { return rim; }
        z = Math.clamp(z, low, rim.z);
        // Narrow bank ends can be shorter than the two contact blends. Keep those blends disjoint:
        // applying both offsets at the same endpoint moves the wall away from its rim and opens a hole.
        float contact = Math.min(1.5f * m, (rim.z - low) * .5f);
        Vector3 point = edgePoint(edge, parameter, z);
        boolean fall = (falls & 1 << e) != 0;
        // The basin already meets the canonical foot. Other water steps retain their sampled bed contact.
        if (!fall || edge.lower == null || !edge.lower.sculpted()) {
            point.lerp(new Vector3(rim.x, rim.y, low), 1 - smooth((z - low) / contact));
        }
        // Apply only the lip's displacement; do not flatten the corner's native relief near the rim.
        Vector3 rimOffset = new Vector3(rim).sub(edgePoint(edge, parameter, rim.z));
        point.mulAdd(rimOffset, 1 - smooth((rim.z - z) / contact));
        if (fall && waterline != null) {
            int per = waterline.length / 6;
            float first = along(e, waterline[e * per]), last = along(e, waterline[((e + 1) % 6) * per]);
            if (t >= first && t <= last) {
                float clearance = metres(.35f) * smooth((z - low) / m) * smooth((rim.z - z) / m);
                float intrusion = (point.x - rim.x) * edge.nx + (point.y - rim.y) * edge.ny + clearance;
                // Each adjoining wall has its own normal. Recess their interiors, but retain the shared
                // corner column or the two clearances pull it apart behind the joined falling sheets.
                intrusion *= smooth(Math.min(t, 1 - t) / .3f);
                if (intrusion > 0) { point.add(-edge.nx * intrusion, -edge.ny * intrusion, 0); }
            }
        }
        point.z = z;
        return point;
    }

    private static void wallFaces(Vector3[][] grid, int edge, List<BoardSurface.Face> result) {
        int last = grid.length - 1, columns = grid[0].length - 1;
        for (int i = 0; i < columns; i++) {
            for (int r = last; r > 0; r--) {
                addQuad(result, grid[r][i], grid[r - 1][i], grid[r - 1][i + 1], grid[r][i + 1],
                      r == last ? BoardSurface.Finish.CAP : BoardSurface.Finish.WALL, edge);
            }
        }
    }

    /** Pit walls retain normal cliff material and projection; only their light fades into the sealed void. */
    private static Kind wallKind(Site lower) { return lower != null && lower.ultraSublevel() ? Kind.PIT_WALL : Kind.CLIFF; }

    private void canonicalWall(int e, Edge edge, List<BoardSurface.Side> contour, List<BoardSurface.Face> result) {
        float[] parameters = wallColumns(e, edge, contour);
        Vector3[][] grid = wallGrid(e, edge, parameters, contour);
        float bottom = edge.lower == null ? grid[0][0].z : edge.bottom(), top = edge.top();
        float[] rows = new float[grid.length];
        for (int r = 0; r < rows.length; r++) { rows[r] = grid[r][0].z; }
        int columns = grid[0].length - 1, last = grid.length - 1;
        float m = metres(1);
        boolean slopingBank = edge.room > 0 && edge.lower != null
              && edge.upper.family() != CONCRETE && !wall(edge.upper, edge.lower);
        boolean wetBank = slopingBank && edge.lower.liquid();
        // Bank or rock: this step's own height, blending into the average of every step through each corner, so a
        // cliff that grows from two to three levels turns from earth to rock gradually and both walls agree there.
        float own = prominence(edge.drop), atA = cornerRock(edge.a, own), atB = cornerRock(edge.b, own);
        float[] rock = new float[columns + 1];
        for (int i = 0; i <= columns; i++) {
            float along = (corner(self, e) == edge.a ? parameters[i] : 1 - parameters[i]) * edge.length, blend = .3f * edge.length;
            float wa = 1 - smooth(along / blend), wb = 1 - smooth((edge.length - along) / blend);
            rock[i] = own * (1 - wa - wb) + atA * wa + atB * wb;
        }
        // How far each vertex stands out, once: every occlusion compares a vertex with the 5x5 around it.
        float[][] outward = new float[last + 1][columns + 1];
        for (int r = 0; r <= last; r++) {
            for (int i = 0; i <= columns; i++) { outward[r][i] = outward(edge, grid[r][i]); }
        }
        for (int r = 0; r <= last; r++) {
            Vector3[] above = r == last ? null : grid[r + 1];
            Vector3[] below = grid[Math.max(0, r - 1)];
            for (int i = 0; i <= columns; i++) {
                Vector3 p = grid[r][i];
                Shade rim = contour.isEmpty() && (r == last || slopingBank) && rims[e] != null
                      ? shades.get(rims[e][Math.round(parameters[i] * (rims[e].length - 1))]) : null;
                // Where a step with room meets the ground at its foot, its lowest row faces up and is shaded like the
                // ground there, easing into the step's own shading within a metre and a half, so no seam shows.
                boolean toe = (edge.footRoom > 0 || wetBank) && r == 0;
                Vector3 normal = rim != null && r == last ? rim.normal() : toe ? new Vector3(0, 0, 1)
                      : gridNormal(edge, e, grid[r], above, below, i, columns);
                if (slopingBank) {
                    // Water-facing and land-facing slopes share these contacts. Feather both alike so their
                    // normals, and the material blend they control, remain continuous around the corner.
                    float low = grid[0][i].z, high = grid[last][i].z;
                    float contact = Math.min(1.5f * m, (high - low) * .5f);
                    if (contact > EPSILON && (p.z - low < contact || high - p.z < contact)) {
                        normal = new Vector3(normal).lerp(Vector3.Z, 1 - smooth((p.z - low) / contact));
                        if (rim != null) { normal.lerp(rim.normal(), 1 - smooth((high - p.z) / contact)); }
                        normal.nor();
                    }
                }
                float occlusion = wallOcclusion(edge, grid, outward, r, i, columns);
                if (edge.footRoom > 0) {
                    occlusion = lerp(footOcclusion(edge, grid[0][i]), occlusion, smooth((rows[r] - bottom) / (1.5f * m)));
                }
                Shade shade = new Shade(normal, wallKind(edge.lower), occlusion, rock[i], (p.z - bottom) / m, (top - p.z) / m,
                      bed(p, edge.upper.family()));
                float t = corner(self, e) == edge.a ? parameters[i] : 1 - parameters[i];
                shade = cornerShade(edge.a, p, 1 - smooth(t / .3f), shade);
                shade = cornerShade(edge.b, p, 1 - smooth((1 - t) / .3f), shade);
                shades.put(p, shade);
            }
        }
        // Cast slopes and cliffs use flat facets wherever their complete shared boundary permits it.
        int wallTop = last;
        if ((contour.isEmpty() || edge.lower == null) && self.family() == CONCRETE && castPanels(e, grid, result)) {
            return;
        }
        // Poured tops and feet use endpoints. Stitch the adjoining relief row to that straight boundary
        // without adding collinear T junctions.
        if ((contour.isEmpty() || edge.lower == null) && planarConcrete(edge.upper) && wallTop > 0) {
            collapsedWallBand(grid[wallTop], grid[wallTop - 1], true, e, result);
            wallTop--;
        }
        int wallBottom = 0;
        if (contour.isEmpty() && edge.lower != null && planarConcrete(edge.lower) && wallTop > 0) {
            collapsedWallBand(grid[1], grid[0], false, e, result);
            wallBottom = 1;
        }
        // Corner rows stay canonical even when adjacent cliffs span different levels or use different LoDs.
        // Interior columns skip rows and stitch to the complete corners without overlapping skirts.
        // Water contact solves against this canonical grid. Board sections also retain its rows: a coarse
        // interior stitched across a curved corner column can fold the artificial cut inward.
        int stride = edge.lower == null || edge.lower.liquid() ? 1 : detail.rowStride;
        int leftStride = simpleConcreteCorner(corner(self, e)) ? last : 1;
        int rightStride = simpleConcreteCorner(corner(self, e + 1)) ? last : 1;
        for (int i = 0; i < columns; i++) {
            if (!contour.isEmpty() && wallSide(e, contour, (parameters[i] + parameters[i + 1]) * .5f) == null) { continue; }
            int left = wallTop, right = wallTop;
            while (left > wallBottom || right > wallBottom) {
                int nextLeft = Math.max(wallBottom, left - (i == 0 ? leftStride : stride));
                int nextRight = Math.max(wallBottom, right - (i + 1 == columns ? rightStride : stride));
                BoardSurface.Finish finish = left == last || right == last ? BoardSurface.Finish.CAP : BoardSurface.Finish.WALL;
                if (nextLeft == nextRight) {
                    addQuad(result, grid[left][i], grid[nextLeft][i], grid[nextRight][i + 1], grid[right][i + 1], finish, e);
                    left = nextLeft;
                    right = nextRight;
                } else if (nextLeft > nextRight) {
                    addTriangle(result, grid[left][i], grid[nextLeft][i], grid[right][i + 1], finish, e);
                    left = nextLeft;
                } else {
                    addTriangle(result, grid[left][i], grid[nextRight][i + 1], grid[right][i + 1], finish, e);
                    right = nextRight;
                }
            }
        }
    }

    private static void collapsedWallBand(Vector3[] top, Vector3[] bottom, boolean flatTop, int e,
          List<BoardSurface.Face> result) {
        int last = top.length - 1;
        for (int i = 0; i < last; i++) {
            if (flatTop) { addTriangle(result, top[0], bottom[i], bottom[i + 1], BoardSurface.Finish.WALL, e); }
            else { addTriangle(result, bottom[0], top[i + 1], top[i], BoardSurface.Finish.WALL, e); }
        }
        if (flatTop) { addTriangle(result, top[0], bottom[last], top[last], BoardSurface.Finish.WALL, e); }
        else { addTriangle(result, bottom[0], bottom[last], top[last], BoardSurface.Finish.WALL, e); }
    }

    /** A cut face changes direction at a slope's foot; each straight span gets its own cast panel. */
    private boolean castPanels(int e, Vector3[][] grid, List<BoardSurface.Face> result) {
        if (castPanel(e, grid, true, true, result)) { return true; }
        TreeSet<Integer> cuts = new TreeSet<>(List.of(0, grid.length - 1));
        for (Corner corner : new Corner[] { corner(self, e), corner(self, e + 1) }) {
            for (int level : new int[] { corner.low, corner.mid, corner.high }) {
                for (int r = 1; r < grid.length - 1; r++) {
                    if (Math.abs(grid[r][0].z - level * BoardGeometry.level()) < EPSILON) { cuts.add(r); }
                }
            }
        }
        if (cuts.size() == 2) { return false; }
        List<BoardSurface.Face> panels = new ArrayList<>();
        int first = 0;
        for (int last : cuts) {
            if (last == 0) { continue; }
            if (!castPanel(e, Arrays.copyOfRange(grid, first, last + 1), first == 0, last == grid.length - 1, panels)) {
                return false;
            }
            first = last;
        }
        result.addAll(panels);
        return true;
    }

    /** Triangulate a straight-sided panel from its perimeter, retaining its shared boundary samples. */
    private boolean castPanel(int e, Vector3[][] grid, boolean foot, boolean rim, List<BoardSurface.Face> result) {
        int last = grid.length - 1, columns = grid[0].length - 1;
        Vector3 a = grid[last][0], b = grid[0][0], c = grid[0][columns], d = grid[last][columns];
        float tolerance = .001f * BoardGeometry.hexScale();
        // Testing the complete sampled panel also protects corners joining other landforms.
        for (int r = 0; r <= last; r++) {
            float v = (grid[r][0].z - b.z) / (a.z - b.z);
            for (int i = 0; i <= columns; i++) {
                float u = i / (float) columns;
                Vector3 expected = new Vector3(b).lerp(c, u).lerp(new Vector3(a).lerp(d, u), v);
                if (!grid[r][i].epsilonEquals(expected, tolerance)) { return false; }
            }
        }
        // A twisted junction uses two flat facets across the diagonal, instead of a fan of narrow strips.
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        if (normal.len2() < .5f) { return false; }
        List<Vector3> perimeter = new ArrayList<>();
        int leftStride = simpleConcreteCorner(corner(self, e)) ? last : 1;
        int rightStride = simpleConcreteCorner(corner(self, e + 1)) ? last : 1;
        for (int r = last; r > 0; r -= leftStride) { perimeter.add(grid[r][0]); }
        int bottomStart = perimeter.size();
        for (int i = 0; i < columns; i += foot && planarConcrete(edge(e).lower) ? columns : 1) { perimeter.add(grid[0][i]); }
        int rightStart = perimeter.size();
        for (int r = 0; r < last; r += rightStride) { perimeter.add(grid[r][columns]); }
        int topStart = perimeter.size();
        for (int i = columns; i > 0; i -= rim && topSamples(e) == 1 ? columns : 1) { perimeter.add(grid[last][i]); }
        if (perimeter.size() == 4) {
            addQuad(result, perimeter.get(0), perimeter.get(1), perimeter.get(2), perimeter.get(3), BoardSurface.Finish.CAP, e);
        } else {
            // Split along a-c. Fan each half from its first horizontal sample, then its opposite corner:
            // all collinear boundary vertices survive in n-2 triangles, even at the coarsest detail.
            Vector3 root = perimeter.get(bottomStart + 1);
            for (int i = 0; i < bottomStart; i++) {
                addTriangle(result, root, perimeter.get(i), perimeter.get(i + 1), BoardSurface.Finish.WALL, e);
            }
            for (int i = bottomStart + 1; i < rightStart; i++) {
                addTriangle(result, perimeter.get(0), perimeter.get(i), perimeter.get(i + 1), BoardSurface.Finish.WALL, e);
            }
            root = perimeter.get((topStart + 1) % perimeter.size());
            for (int i = rightStart; i < topStart; i++) {
                addTriangle(result, root, perimeter.get(i), perimeter.get(i + 1), BoardSurface.Finish.WALL, e);
            }
            for (int i = topStart + 1; i < perimeter.size(); i++) {
                addTriangle(result, perimeter.get(rightStart), perimeter.get(i), perimeter.get((i + 1) % perimeter.size()),
                      BoardSurface.Finish.CAP, e);
            }
        }
        return true;
    }

    /**
     * Normal of a cliff grid vertex from its neighbours in the grid (above may be null for the top row). The corner
     * columns, which other hexes' cliffs share, use the canonical outward direction of the corner line tilted by the
     * column's own slope.
     */
    private Vector3 gridNormal(Edge edge, int e, Vector3[] row, Vector3[] above, Vector3[] below, int i, int columns) {
        Vector3 up = above == null ? row[i] : above[i], down = below == null ? row[i] : below[i];
        Vector3 vertical = new Vector3(up).sub(down);
        if (vertical.len2() < 1e-8f) { vertical.set(0, 0, 1); }
        Corner end = i == 0 ? corner(self, e) : i == columns ? corner(self, e + 1) : null;
        float[] direction = end == null ? null : cornerDirection(end, row[i].z, above == null && edge.upper == self);
        Vector3 normal;
        if (direction != null) {
            Vector3 tangent = new Vector3(-direction[1], direction[0], 0);
            normal = tangent.crs(vertical).nor();
            if (normal.x * direction[0] + normal.y * direction[1] < 0) { normal.scl(-1); }
        } else {
            Vector3 along = new Vector3(row[Math.min(columns, i + 1)]).sub(row[Math.max(0, i - 1)]);
            normal = along.crs(vertical).nor();
            if (normal.x * edge.nx + normal.y * edge.ny < 0) { normal.scl(-1); }
        }
        if (Float.isNaN(normal.x) || normal.len2() < .5f) { normal.set(edge.nx, edge.ny, 0); }
        return normal;
    }

    /** Contact at the foot, shelter under the caprock, and recesses between standing masses. */
    private float wallOcclusion(Edge edge, Vector3[][] grid, float[][] outward, int r, int i, int columns) {
        float m = metres(1);
        Vector3 p = grid[r][i];
        float h = (p.z - grid[0][i].z) / m, d = (grid[grid.length - 1][i].z - p.z) / m;
        // A transition's slope opens out at its toe, but still shades it a little, so the step reads from above; a
        // cliff's foot meets the ground in a sheltered corner.
        float contact = edge.room > 0 ? .25f + .15f * prominence(edge.drop) : .4f;
        float foot = 1 - contact * (float) Math.exp(-h / 1.6f);
        float shelter = 1 - .25f * bump((d - 2.2f) / 1.6f);
        float offset = outward[r][i], around = 0;
        int n = 0;
        for (int dr = -2; dr <= 2; dr++) {
            for (int di = -2; di <= 2; di++) {
                int rr = Math.clamp(r + dr, 0, grid.length - 1), ii = Math.clamp(i + di, 0, columns);
                around += outward[rr][ii];
                n++;
            }
        }
        float cavity = 1 - .35f * smooth((around / n - offset) / (m * 1.2f));
        return Math.clamp(foot * shelter * cavity, 0, 1);
    }

    /** How far a wall vertex stands out from its edge, leaving out the local transition band. */
    private float outward(Edge edge, Vector3 p) {
        float band = edge.room > 0 ? band(edge.upper, edge.lower, p.z, p.x, p.y) : 0;
        return (p.x - edge.a.x) * edge.nx + (p.y - edge.a.y) * edge.ny - band;
    }

    /** A wall's quad, tagged with the edge it stands on. */
    private static void addQuad(List<BoardSurface.Face> out, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          BoardSurface.Finish finish, int edge) {
        addTriangle(out, a, b, c, finish, edge);
        addTriangle(out, a, c, d, finish, edge);
    }

    private static void addTriangle(List<BoardSurface.Face> out, Vector3 a, Vector3 b, Vector3 c,
          BoardSurface.Finish finish) {
        addTriangle(out, a, b, c, finish, -1);
    }

    private static void addTriangle(List<BoardSurface.Face> out, Vector3 a, Vector3 b, Vector3 c,
          BoardSurface.Finish finish, int edge) {
        float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z, acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
        float cx = aby * acz - abz * acy, cy = abz * acx - abx * acz, cz = abx * acy - aby * acx;
        // Coincident straight banks can differ by a float rounding unit. Do not emit microscopic slivers between
        // their independently sampled rows; their unstable normals would face alternately up and down.
        float tolerance = 1e-8f * BoardGeometry.hexScale() * BoardGeometry.hexScale() * Math.max(a.dst2(b), a.dst2(c));
        if (cx * cx + cy * cy + cz * cz > Math.max(1e-9f, tolerance)) {
            out.add(new BoardSurface.Face(a, b, c, finish, edge));
        }
    }

    // ---- Maths -----------------------------------------------------------------------------------------------

    static float smooth(float value) {
        float t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float square(float value) { return value * value; }

    static float distance(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float length = dx * dx + dy * dy;
        float t = length == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / length, 0, 1);
        float ex = x - ax - dx * t, ey = y - ay - dy * t;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0x00ffffff) / (float) 0x01000000;
    }

    private static int hashInt(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1440662683;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    /** Value noise in [0, 1]. */
    static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float u = x - ix, v = y - iy;
        u = u * u * (3 - 2 * u);
        v = v * v * (3 - 2 * v);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }

    /** 2D gradient noise, roughly in [-1, 1]. */
    static float gradient(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float u = fx * fx * fx * (fx * (fx * 6 - 15) + 10), v = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        float a = grad(hashInt(ix, iy, 0), fx, fy), b = grad(hashInt(ix + 1, iy, 0), fx - 1, fy);
        float c = grad(hashInt(ix, iy + 1, 0), fx, fy - 1), d = grad(hashInt(ix + 1, iy + 1, 0), fx - 1, fy - 1);
        return ((a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v) * 1.4f;
    }

    /** 3D gradient noise, roughly in [-1, 1]. */
    static float gradient(float x, float y, float z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        float u = fx * fx * fx * (fx * (fx * 6 - 15) + 10), v = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        float w = fz * fz * fz * (fz * (fz * 6 - 15) + 10);
        float x00 = lerp(grad(hashInt(ix, iy, iz), fx, fy, fz), grad(hashInt(ix + 1, iy, iz), fx - 1, fy, fz), u);
        float x10 = lerp(grad(hashInt(ix, iy + 1, iz), fx, fy - 1, fz), grad(hashInt(ix + 1, iy + 1, iz), fx - 1, fy - 1, fz), u);
        float x01 = lerp(grad(hashInt(ix, iy, iz + 1), fx, fy, fz - 1), grad(hashInt(ix + 1, iy, iz + 1), fx - 1, fy, fz - 1), u);
        float x11 = lerp(grad(hashInt(ix, iy + 1, iz + 1), fx, fy - 1, fz - 1),
              grad(hashInt(ix + 1, iy + 1, iz + 1), fx - 1, fy - 1, fz - 1), u);
        return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w) * 1.2f;
    }

    private static float grad(int hash, float x, float y) {
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }

    private static float grad(int hash, float x, float y, float z) {
        return switch (hash & 15) {
            case 0, 12 -> x + y;
            case 1, 13 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x + z;
            case 5 -> -x + z;
            case 6 -> x - z;
            case 7 -> -x - z;
            case 8 -> y + z;
            case 9, 14 -> -y + z;
            case 10 -> y - z;
            default -> -y - z;
        };
    }
}
