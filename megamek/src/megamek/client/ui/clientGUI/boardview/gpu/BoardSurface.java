/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;

import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.ConcreteFit;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;

/** The actual topography of one hex. Rendering, road continuity, banks and picking share it. */
final class BoardSurface {
    /** GL-view-owned geometry. Artwork snapshots retain surfaces; shape changes are checked once per generation. */
    static final class Cache {
        private static final int CAPACITY = 256;
        private static final class Entry {
            final Key key;
            final BoardSurface surface;
            long generation;

            Entry(Key key, BoardSurface surface, long generation) {
                this.key = key;
                this.surface = surface;
                this.generation = generation;
            }
        }

        private List<BoardScene.Tile> tiles;
        private int revision = -1;
        private int boardId = -1;
        private int width;
        private int height;
        private long generation;
        private boolean columns;
        private final Map<Coords, Entry> surfaces = new LinkedHashMap<>(32, .75f, true);

        BoardSurface get(BoardScene scene, BoardScene.Tile tile) {
            if (boardId != scene.boardId() || width != scene.width() || height != scene.height()
                  || revision != BoardGeometry.revision()) {
                clear();
                boardId = scene.boardId();
                width = scene.width();
                height = scene.height();
                revision = BoardGeometry.revision();
            }
            if (tiles != scene.tiles()) {
                tiles = scene.tiles();
                generation++;
            }
            Entry entry = surfaces.get(tile.coords());
            if (entry == null || entry.generation != generation) {
                Key key = geometryKey(scene, tile);
                if (entry == null || !entry.key.equals(key)) {
                    entry = new Entry(key, columns ? BoardSurface.columns(scene, tile, TerrainLod.FULL)
                          : new BoardSurface(scene, tile), generation);
                    surfaces.put(tile.coords(), entry);
                } else {
                    entry.generation = generation;
                }
            }
            if (surfaces.size() > CAPACITY) { surfaces.remove(surfaces.keySet().iterator().next()); }
            return entry.surface;
        }

        /** The owning view switches this at its installed graphics-mode boundary. */
        void columns(boolean value) {
            if (columns != value) { columns = value; clear(); }
        }

        void clear() { surfaces.clear(); tiles = null; }
    }

    /** Only inputs that alter topology; the ramp mask also captures second-ring road/bridge approaches. */
    record Geometry(int elevation, int waterDepth, boolean frozen, int roadExits, BoardScene.Surface surface,
          BoardLiquid liquid, boolean detailedGround, List<BoardScene.Feature> features, int ramps,
          List<ConcreteFit.Shift> coast, BoardRoad.Kind road, BoardScene.Biome biome, int cliffTopExits, boolean bare,
          boolean barren, BoardSurfaceBlend.Cover groundCover, boolean ultraSublevel, boolean naturalBridge,
          RailRoute.Corridor corridor, RailRoute.Corridor deckCorridor, BoardBackdrop backdrop, int pavementSides) { }

    /** What of a hex further out can reach a hex's shape: through the water's shore, its level, liquid and ground. */
    record Shape(int elevation, int waterDepth, boolean frozen, BoardLiquid liquid, boolean detailedGround, BoardScene.Surface surface,
          int roadExits, BoardRoad.Kind road, int cliffTopExits, boolean ultraSublevel, BoardBackdrop backdrop) { }

    /** A hex's own geometry and its six neighbours', and the shapes of the hexes out to {@link #SHORE_RINGS}. */
    record Key(List<Geometry> near, List<Shape> far) { }

    /**
     * How many hexes away a hex's shape can move another's water shore, its banks or the corners the shore moves (see
     * {@link BoardRelief#shore}): the field reaches about two hexes, each hex's pull depends on its neighbours, and the
     * land round a corner allows its move by the moves of all its own corners.
     */
    static final int SHORE_RINGS = 6;
    /** Ordinary carriageway and shoulder; shared rail corridors derive their larger gate below. */
    static final float ROAD_MOUTH = HexTile.ROAD_HALF_WIDTH + BoardRoad.SHOULDER;
    /** Both sides of a gate reserve the same road/rail envelope, including a connected bridge's shoulder. */
    static float roadMouth(BoardScene.Tile tile, BoardScene.Tile neighbor, int direction) {
        float width = Math.max(roadMouth(tile, direction), roadMouth(neighbor, (direction + 3) % 6));
        if (tile != null && neighbor != null && connectingBridge(tile, neighbor, direction) != null) {
            width = Math.max(width, ((neighbor.deckCorridor().sides() & 1 << ((direction + 3) % 6)) != 0
                  ? BoardRoad.CORRIDOR_HALF : HexTile.ROAD_HALF_WIDTH) + BoardBridge.SHOULDER);
        }
        return width == 0 ? ROAD_MOUTH : width;
    }

    private static float roadMouth(BoardScene.Tile tile, int direction) {
        if (tile == null) { return 0; }
        if ((tile.corridor().sides() & 1 << direction) != 0) { return BoardRoad.CORRIDOR_HALF + BoardRoad.SHOULDER; }
        if (tile.road() != BoardRoad.Kind.NONE && (tile.roadExits() & 1 << direction) != 0) { return ROAD_MOUTH; }
        return (tile.railExits() & 1 << direction) != 0 ? RailRoute.BALLAST_HALF_WIDTH + BoardRoad.SHOULDER : 0;
    }

    /**
     * Own shape followed by the six neighboring shapes, a missing board neighbor as a null entry; then the shapes of the
     * hexes out to {@link #SHORE_RINGS}, beyond the board's edge the nearest board hex's, as the shore takes them.
     */
    static Key geometryKey(BoardScene scene, BoardScene.Tile tile) {
        List<Geometry> near = new ArrayList<>(7);
        near.add(geometry(scene, tile));
        for (int direction = 0; direction < 6; direction++) {
            near.add(geometry(scene, scene.tile(tile.coords().translated(direction))));
        }
        List<Shape> far = new ArrayList<>();
        Coords at = tile.coords();
        for (int x = at.getX() - SHORE_RINGS; x <= at.getX() + SHORE_RINGS; x++) {
            for (int y = at.getY() - SHORE_RINGS - 1; y <= at.getY() + SHORE_RINGS + 1; y++) {
                int distance = at.distance(x, y);
                if (distance < 2 || distance > SHORE_RINGS) { continue; }
                BoardScene.Tile other = scene.tile(new Coords(Math.clamp(x, 0, scene.width() - 1),
                      Math.clamp(y, 0, scene.height() - 1)));
                far.add(shape(other, other.waterDepth()));
            }
        }
        return new Key(Collections.unmodifiableList(near), List.copyOf(far));
    }

    /** The hex's shape as a hex further out reads it, with the given water depth. */
    static Shape shape(BoardScene.Tile tile, int waterDepth) {
        return new Shape(tile.elevation(), waterDepth, tile.frozen(), tile.liquid(), tile.detailedGround(), tile.surface(),
              tile.wayExits(), tile.road(), tile.cliffTopExits(), tile.ultraSublevel(), tile.backdrop());
    }

    private static Geometry geometry(BoardScene scene, BoardScene.Tile tile) {
        return tile == null ? null : new Geometry(tile.elevation(), tile.waterDepth(), tile.frozen(), tile.wayExits(),
              tile.surface(), tile.liquid(), tile.detailedGround(), tile.features(), ramps(scene, tile),
              BoardConcrete.of(scene).corners(tile.coords()), tile.road(), tile.biome(), tile.cliffTopExits(), tile.bare(),
              tile.barren(), tile.groundCover(), tile.ultraSublevel(),
              BoardBridge.feature(tile) != null && BoardBridge.family(tile) == BoardBridge.Family.NATURAL,
              tile.corridor(), tile.deckCorridor(), tile.backdrop(), tile.pavementSides());
    }

    /** The edges this hex's carriageway ramps down to (see {@link BoardRoadGrade#ramps}). */
    static int ramps(BoardScene scene, BoardScene.Tile tile) {
        return BoardRoadGrade.ramps(scene, tile);
    }

    /** Waterline points per edge; crests, the walls beneath them and the sheets poured over them share them. */
    static final int SHORE_SEGMENTS = 12;
    /**
     * The wet margin, in hex-scale units, the water keeps beyond any land that reaches into its hex: the foot of a
     * slope, the steps and fillet through a corner, and a mouth's ends. The river hugs the slopes it runs between.
     */
    private static final float HUG = 1.5f;
    /** How far, in hex-scale units, a bank keeps the water in from its edge where no slope runs up from the water. */
    private static final float BEACH = 8;
    /**
     * A canal's channel in tile pixels: its half-width at the water, the least half-width its floor keeps, and the radius
     * of a basin with no mouth. Its lining falls {@link #CANAL_BATTER} across per depth.
     */
    private static final float CANAL_HALF = 15, CANAL_FLOOR = 4, CANAL_BASIN = 22, CANAL_BATTER = .27f;
    /** Hex-scale units a riverside's width wanders either way along a bank over lower land. */
    private static final float BANK_WANDER = 3;
    /** How far, in hex-scale units, pavement stops short of a separated side: half the ground showing between two slabs. */
    private static final float PAVEMENT_GAP = 6;
    /** How far, in hex-scale units, a pool swells out round the foot of a fall that pours into it. */
    private static final float PLUNGE_POOL = 6;
    /** The bank, in hex-scale units, the water keeps inside its outline beside land at its own level. */
    private static final float SHORE_BANK = 4.5f;
    /** Rounding, in hex-scale units, at bank corners and across a bank beside higher natural ground. */
    private static final float SHORE_ROUND = 6;
    /** The shore's search along a ray: even steps to the first dry one, then halvings of that step. */
    private static final int SHORE_STEPS = 8;
    private static final int SHORE_HALVINGS = 4;
    /**
     * How much of a beach, in hex-scale units, an open mouth gives up beside a bank where no shore moves the corner,
     * and below a fall, where the pool reaches nearly to the corners so the whole sheet lands in water.
     */
    private static final float MOUTH_OPENING = 4;
    private static final float PLUNGE_OPENING = 6.5f;
    /** How far a fall's lip juts out unevenly over the pool below, at most, in hex-scale units. */
    private static final float LIP_JUT = 3.5f;
    /** A fall's lip: the water stops this far short of its crest so the sheet can curve down over it. */
    private static final float FALL_LIP_WIDTH = .045f;
    private static final float FALL_LIP_DROP = .5f;
    /** Share of each ray from the waterline to the hex centre over which a bed descends; the rest is its plateau. */
    private static final float PLATEAU = .75f;
    /**
     * Water depth over a fall's lip, in hex-scale units: the bed rises to a ledge the water pours over, so the wall
     * beneath the fall closes up to just under the sheet. No pool is shallower, so both sides of a corner agree.
     */
    private static final float LIP_DEPTH = 1;
    /**
     * How far out over the pool below the crest bows where the falls of two neighbouring pools meet around it, along
     * the edge the pools share, in hex-scale units: the crest rounds the corner instead of folding into a V. Far enough
     * that the crests beside it never pass inside their hexes; no crest reaches further out.
     */
    static final float VALLEY = 7;

    /**
     * DRESSING is thin detail on the ground, such as tree pits: drawn and picked, never raising what stands there. DUG is
     * ground a feature's model digs and draws itself, as a trench's floor ({@link BoardGroundCut}): it supports and
     * picks, and the terrain never draws it.
     */
    enum Finish { TOP, RIM, CAP, WALL, OUTCROP, SHORE, BED, BANK, ICE, DRESSING, ROUGH, DUG }
    /**
     * landEdge is the edge a bank or wall face stands on (bank artwork takes the hex across it); else -1.
     * Scatter marks cosmetic cover for rendering only; the canonical geometry retains its support/picking behavior.
     */
    record Face(Vector3 a, Vector3 b, Vector3 c, Finish finish, int landEdge, boolean scatter) {
        Face(Vector3 a, Vector3 b, Vector3 c, Finish finish, int landEdge) {
            this(a, b, c, finish, landEdge, false);
        }

        Face(Vector3 a, Vector3 b, Vector3 c, Finish finish) {
            this(a, b, c, finish, -1);
        }

        /** Height of the face above a point, or negative infinity when the point lies outside it. */
        float height(float x, float y) {
            return height(x, y, .001f * BoardGeometry.hexScale());
        }

        /** {@link #height(float, float)} with the tolerance its caller read once from the settings. */
        float height(float x, float y, float tolerance) {
            if (x < Math.min(a.x, Math.min(b.x, c.x)) - tolerance
                  || x > Math.max(a.x, Math.max(b.x, c.x)) + tolerance
                  || y < Math.min(a.y, Math.min(b.y, c.y)) - tolerance
                  || y > Math.max(a.y, Math.max(b.y, c.y)) + tolerance) { return Float.NEGATIVE_INFINITY; }
            float denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
            if (Math.abs(denominator) < 0.00001f) {
                return Float.NEGATIVE_INFINITY;
            }
            float u = ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / denominator;
            float v = ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / denominator;
            float w = 1 - u - v;
            // World-space tolerance keeps rounded edge samples on thin bank triangles even far from the origin.
            // A fixed barycentric tolerance can miss the bank and drop the exposed wall to the riverbed.
            float squared = tolerance * tolerance / (denominator * denominator);
            if ((u >= 0 || u * u <= squared * (square(b.x - c.x) + square(b.y - c.y)))
                  && (v >= 0 || v * v <= squared * (square(c.x - a.x) + square(c.y - a.y)))
                  && (w >= 0 || w * w <= squared * (square(a.x - b.x) + square(a.y - b.y)))) {
                u = Math.max(0, u);
                v = Math.max(0, v);
                w = Math.max(0, w);
                return (u * a.z + v * b.z + w * c.z) / (u + v + w);
            }
            return Float.NEGATIVE_INFINITY;
        }
    }
    record Side(Vector3 a, Vector3 b, float lowA, float lowB, int edge) { }

    /**
     * The crest a fall pours over, at the pool's surface: a smooth curve across its mouth from {@code a} to {@code b}.
     * A free end meets its bank on the edge and leaves along it. Where the next fall carries on, both crests pass one
     * point with one tangent (see {@link #join}): the hex's own corner at a prow, and {@link #VALLEY} out over the pool
     * below where two pools' falls meet around it. A fall that runs on over several edges so curves round them without
     * a corner, and the curve never passes inside its hex: it only ever bows out over the pool it falls into, whose hex
     * it stays in. {@code corner} and {@code outward} are the edge's start and outward normal; {@code jut} how far a
     * free-standing fall's lip may jut out unevenly over the pool below, in world units.
     */
    record Crest(Vector3 a, Vector3 b, Vector3 startTangent, Vector3 endTangent, Vector3 corner, Vector3 outward,
          float jut, float level) {
        /** The point at {@code s}, from 0 at {@code a} to 1 at {@code b}. */
        Vector3 point(float s) {
            if (s <= 0) { return new Vector3(a); }
            if (s >= 1) { return new Vector3(b); }
            float chord = chord(), s2 = s * s, s3 = s2 * s;
            Vector3 p = new Vector3(a).scl(2 * s3 - 3 * s2 + 1).mulAdd(startTangent, chord * (s3 - 2 * s2 + s))
                  .mulAdd(b, 3 * s2 - 2 * s3).mulAdd(endTangent, chord * (s3 - s2));
            p.z = mouthDepth(p, a, a.z, b, b.z, level);
            if (jut > 0) {
                // The lip juts out unevenly over the pool below, as ledges break away from a real one; its ends, on
                // the banks, stay put.
                float m = BoardRelief.metres(1);
                float broken = .6f * BoardRelief.gradient(p.x / (3 * m) + 7.1f, p.y / (3 * m) - 2.3f)
                      + .4f * BoardRelief.gradient(p.x / (1.2f * m) - 4.4f, p.y / (1.2f * m) + 1.9f);
                p.mulAdd(normal(s), jut * Math.clamp(.5f + .6f * broken, 0, 1) * 16 * s2 * (1 - s) * (1 - s));
            }
            float inside = new Vector3(corner.x - p.x, corner.y - p.y, 0).dot(outward);
            return inside > 0 ? p.mulAdd(outward, inside) : p;
        }

        /** The unit direction of the crest at {@code s}, from {@code a} toward {@code b}. */
        Vector3 tangent(float s) {
            if (s <= 0) { return new Vector3(startTangent); }
            if (s >= 1) { return new Vector3(endTangent); }
            float chord = chord(), s2 = s * s;
            Vector3 t = new Vector3(a).scl(6 * s2 - 6 * s).mulAdd(startTangent, chord * (3 * s2 - 4 * s + 1))
                  .mulAdd(b, 6 * s - 6 * s2).mulAdd(endTangent, chord * (3 * s2 - 2 * s));
            t.z = 0;
            return t.nor();
        }

        /** The unit normal of the crest at {@code s}, level and away from the pool: the way the water falls. */
        Vector3 normal(float s) {
            Vector3 t = tangent(s);
            return new Vector3(t.y, -t.x, 0);
        }

        private float chord() {
            return (float) Math.hypot(b.x - a.x, b.y - a.y);
        }
    }

    final BoardScene.Tile tile;
    private final BoardScene scene;
    private final int boardCutEdges;
    private final BoardSurface[] receivingWater = new BoardSurface[6];
    final List<Face> faces = new ArrayList<>();
    /** Rough cover is part of the drawn mesh; ordinary units may clip it, infantry can seek footing on it. */
    final List<Face> rough = new ArrayList<>();
    /**
     * A frozen lake as drawn: its slab; the broken pieces round it (cut edges, the ragged margin over open water,
     * floes and shore rubble); and the open leads along ice-free shores. Support and picking use the ICE faces.
     */
    final List<Face> iceSlab = new ArrayList<>(), iceBroken = new ArrayList<>(), iceLeads = new ArrayList<>();
    private List<BoardRough.Placement> roughModels = List.of();
    /** Where troops resting here stand, as the scenery dug into the hex marks it ({@link BoardGroundCut}). */
    private List<BoardGroundCut.Stand> stands = List.of();
    /** Whether scenery dug into the hex's top ({@link Finish#DUG}). */
    private boolean dug;

    List<BoardRough.Placement> roughModels() { return roughModels; }

    List<BoardGroundCut.Stand> stands() { return stands; }

    boolean dug() { return dug; }
    final List<Vector3> water = new ArrayList<>();
    /** The surface overlap inside cliff recesses; these same faces also belong to waterFaces. */
    final List<Face> cliffWater = new ArrayList<>();
    /**
     * The water's full outline within its hex, before a falling mouth pulls its surface back to the crest. Segments on
     * open mouths continue into the next pool; every other segment is a bank.
     */
    final List<Vector3> outline = new ArrayList<>();
    final List<Face> waterFaces = new ArrayList<>();
    /** Extra contacts where the solid cliff's triangle edges bend the constructed water outline. */
    private final Map<Integer, List<Vector3>> waterContacts = new LinkedHashMap<>();
    /** The water's cut face where the board's edge cuts it off, facing out; drawn like its surface. */
    final List<Face> cutFaces = new ArrayList<>();
    final List<Side> waterfalls = new ArrayList<>();
    /** Finished on the terrain worker, then borrowed by rendering and picking without retaining this builder. */
    private List<GpuFrozenWaterfall.Geometry> frozenFalls;

    List<GpuFrozenWaterfall.Geometry> frozenFalls() {
        if (frozenFalls == null) {
            frozenFalls = tile.frozen()
                  ? waterfalls.stream().map(drop -> GpuFrozenWaterfall.geometry(this, drop, lod)).toList() : List.of();
        }
        return frozenFalls;
    }
    final int ramps;
    /** Whether carriageways cut this column's top (see {@link #reservedTop}); its edges then have discontinuous roofs. */
    private boolean roadCut;
    /** Engineered walls inside a concrete hex, between its level slab and a cut or raised road. */
    final List<Face> retainingWalls = new ArrayList<>();
    /** Precast containment walls retaining the cuts roads make in natural slopes; see {@link BoardRelief#DEFAULT_CONTAINMENT_WALLS}. */
    final List<Face> containmentWalls = new ArrayList<>();
    /**
     * The cut faces natural ground leaves beside a road built in concrete ({@link BoardRoadGrade#poured}), drawn as a
     * concrete hex's own: the top's first, then its walls'.
     */
    final List<Face> pouredWalls = new ArrayList<>();
    /** How many of the poured walls are the top's. */
    private int pouredTop;
    /** The reserved road surface this hex emits, which cut faces in its walls meet. */
    private List<Face> reservedRoad = List.of();
    /** {@link #reservedRoad}'s faces, by identity (#carries). */
    private final Set<Face> reservedFaces = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The engineered ground carrying rigid rails, including a neighbour's track bed. */
    private List<BoardRoadGrade> railGrades = List.of();
    /** Canonical passages of nearby rigid bridge decks, shared by both sides of a terrain contact. */
    private List<BoardBridge.Approach> bridgePassages = List.of();
    /** Shared water-bank boundaries whose separate meshes can have different shading normals. */
    private int waterSeamEdges;
    /** The roads around and, on natural ground, its easing into them (Enhanced); null where none reaches. */
    private BoardRoadBank bank;

    final BoardRelief relief;
    /**
     * What the cliff pass asks of a neighbour's relief, settled as the relief is made: each corner's shore shift and
     * whether each edge's cliff stands into this hex's water. That pass runs every hex on its own thread, and the
     * relief's caches are its own hex's.
     */
    private final float[][] shoreShifts = new float[6][];
    private final boolean[] wetCliffs = new boolean[6];
    private final TerrainLod lod;
    private final boolean columns;
    private final List<Face> edgeTopography = new ArrayList<>();
    private List<Face> wallFaces;
    private float wallFloor = Float.NaN;
    /**
     * Open mouths by edge: connected liquid continues there, so no bank rises across that edge and a wall exposed
     * on it starts at the bed rather than at the hex's own top.
     */
    private int openMouths;
    /** Whether this water is a canal ({@link BoardRelief#canalHex}): a lined channel of one width. */
    private boolean canal;
    /** A canal deck's ramps, which cut into its concrete. */
    private List<RampCut> deckCuts = List.of();
    private boolean gradedWater;
    /** The faces of a sculpted bed that stay clear of the hex outline, as a range of {@link #faces}. */
    private int interiorFrom, interiorTo;
    /**
     * Per falling edge, the crest's normal at its first and last corner where another fall of the same drop carries
     * on, from this pool or from the neighbouring one; null where the end is free. See {@link #join}.
     */
    private final Vector3[] joinA = new Vector3[6];
    private final Vector3[] joinB = new Vector3[6];
    /** Per falling edge, the crest its water pours over; null elsewhere. */
    private final Crest[] crests = new Crest[6];
    /**
     * The bed's outer ring, along the waterline and out along the crests: the top of the walls that stand under falls.
     */
    private Vector3[] bedOutline;
    /** Optional render-only bed. Optical depth, support, rocks and neighbouring walls always use {@link #faces}. */
    private List<Face> renderBed;
    /** Whether a separated side of this water hex ends in open water ({@link #stripTip}). */
    private boolean openEnds;
    /** The ground showing where this hex's pavement stops short of a separated side ({@link #pavementGaps}). */
    private final java.util.Set<Face> gaps = new java.util.LinkedHashSet<>();
    /** A water hex's waterline, frozen or not, which its sculpted banks run down to; null on land. */
    private Vector3[] waterline;
    /** The waterline with each falling mouth moved out to its crest; null on land. */
    private Vector3[] crestLine;
    private final Vector3 center;
    private final Vector3[] corners = new Vector3[6];
    /** A water hex's outline corners at its level: its corners as the shore moves them; null on land. */
    private Vector3[] shoreCorners;
    /** The bank and corner clearances that keep a water hex's shore away from the surrounding land. */
    private List<Bank> banks;

    /** A bank's inward clearance. The shared stream field supplies the curve across hex boundaries. */
    private record Bank(float x, float y, float nx, float ny, float width) {
        float clearance(float px, float py) {
            float dx = px - x, dy = py - y;
            return dx * nx + dy * ny - width;
        }
    }

    /** Finished water geometry only: no scene, procedural relief cache or neighbouring surface is retained. */
    record WaterGeometry(Coords coords, TerrainLod lod, int ramps, List<Face> faces, List<Vector3> water,
          List<Face> cliffWater, List<Vector3> outline, List<Face> waterFaces, Map<Integer, List<Vector3>> contacts,
          List<Face> cutFaces, List<Side> waterfalls, List<GpuFrozenWaterfall.Geometry> frozenFalls, List<Face> edgeTopography,
          int openMouths,
          List<Face> rough, List<BoardRough.Placement> roughModels, List<Face> iceSlab, List<Face> iceBroken,
          List<Face> iceLeads,
          boolean graded, Vector3[] joinsA, Vector3[] joinsB, Crest[] crests, Vector3[] bedOutline,
          Vector3[] waterline, Vector3[] crestLine, Vector3[] shoreCorners) {
        BoardSurface surface(BoardScene scene) { return new BoardSurface(scene, this); }
    }

    WaterGeometry waterGeometry() {
        return new WaterGeometry(tile.coords(), lod, ramps, List.copyOf(faces), List.copyOf(water),
              List.copyOf(cliffWater), List.copyOf(outline), List.copyOf(waterFaces), Map.copyOf(waterContacts),
              List.copyOf(cutFaces), List.copyOf(waterfalls), frozenFalls(), List.copyOf(edgeTopography), openMouths,
              List.copyOf(rough), roughModels, List.copyOf(iceSlab), List.copyOf(iceBroken), List.copyOf(iceLeads),
              gradedWater, joinA.clone(), joinB.clone(), crests.clone(), bedOutline, waterline, crestLine, shoreCorners);
    }

    /** Fresh query context around unchanged finished water; rebuilding its many bed/cliff triangles is unnecessary. */
    private BoardSurface(BoardScene scene, WaterGeometry geometry) {
        this.scene = scene;
        columns = false;
        tile = scene.tile(geometry.coords());
        boardCutEdges = boardEdges(scene, tile);
        lod = geometry.lod();
        ramps = geometry.ramps();
        center = BoardGeometry.center(tile.coords(), tile.elevation());
        for (int edge = 0; edge < 6; edge++) { corners[edge] = BoardGeometry.corner(tile.coords(), tile.elevation(), edge); }
        relief = new BoardRelief(scene, tile, ramps, lod);
        settleRelief();
        faces.addAll(geometry.faces());
        rough.addAll(geometry.rough());
        roughModels = geometry.roughModels();
        iceSlab.addAll(geometry.iceSlab());
        iceBroken.addAll(geometry.iceBroken());
        iceLeads.addAll(geometry.iceLeads());
        water.addAll(geometry.water());
        cliffWater.addAll(geometry.cliffWater());
        outline.addAll(geometry.outline());
        waterFaces.addAll(geometry.waterFaces());
        waterContacts.putAll(geometry.contacts());
        cutFaces.addAll(geometry.cutFaces());
        waterfalls.addAll(geometry.waterfalls());
        frozenFalls = geometry.frozenFalls();
        edgeTopography.addAll(geometry.edgeTopography());
        openMouths = geometry.openMouths();
        gradedWater = geometry.graded();
        System.arraycopy(geometry.joinsA(), 0, joinA, 0, 6);
        System.arraycopy(geometry.joinsB(), 0, joinB, 0, 6);
        System.arraycopy(geometry.crests(), 0, crests, 0, 6);
        bedOutline = geometry.bedOutline();
        waterline = geometry.waterline();
        crestLine = geometry.crestLine();
        shoreCorners = geometry.shoreCorners();
    }

    BoardSurface(BoardScene scene, BoardScene.Tile tile) {
        this(scene, tile, TerrainLod.FULL);
    }

    BoardSurface(BoardScene scene, BoardScene.Tile tile, TerrainLod lod) {
        this(scene, tile, true, lod);
    }

    TerrainLod lod() { return lod; }

    /** Classic routes keep their course and end heights in an otherwise sharp, unmoved column, on straight ramps. */
    static BoardSurface columns(BoardScene scene, BoardScene.Tile tile, TerrainLod lod) {
        return new BoardSurface(scene, tile, false, lod, true);
    }

    boolean columns() { return columns; }

    private BoardSurface(BoardScene scene, BoardScene.Tile tile, boolean detailed, TerrainLod lod) {
        this(scene, tile, detailed, lod, false);
    }

    private BoardSurface(BoardScene scene, BoardScene.Tile tile, boolean detailed, TerrainLod lod, boolean columns) {
        this.lod = lod;
        this.columns = columns;
        this.scene = scene;
        this.tile = tile;
        boardCutEdges = boardEdges(scene, tile);
        center = BoardGeometry.center(tile.coords(), columns ? tile.elevation() : tile.groundLevel());
        for (int edge = 0; edge < 6; edge++) {
            corners[edge] = BoardGeometry.corner(tile.coords(), columns ? tile.elevation() : tile.groundLevel(), edge);
        }
        ramps = ramps(scene, tile);
        // The relief first: a water hex lays out its waterline round the steps the relief puts beside it.
        relief = new BoardRelief(scene, tile, ramps, lod);
        settleRelief();
        if (columns) {
            if (tile.liquid().present()) {
                if (!tile.frozen()) {
                    for (var corner : corners) { corner.z = BoardGeometry.waterZ(tile); }
                    fan(corners, BoardGeometry.waterZ(tile), Finish.TOP);
                    waterFaces.addAll(faces);
                    faces.clear();
                }
                float solid = tile.frozen() ? BoardGeometry.surfaceZ(tile) : BoardGeometry.groundZ(tile);
                for (var corner : corners) { corner.z = solid; }
                fan(corners, solid, tile.frozen() ? Finish.ICE : Finish.BED);
            } else if (!reservedTop()) { fan(corners, center.z, Finish.TOP); }
            edgeTopography.addAll(faces);
            return;
        }
        if (tile.backdrop().present()) {
            // Aerospace cells retain one flat selectable plane; there is no ground to sculpt or bank.
            fan(corners, center.z, Finish.TOP);
        } else if (tile.ultraSublevel()) {
            // A closed, flat cap below the mouth; neighbouring columns supply the pit's cliffs.
            fan(corners, center.z, Finish.TOP);
        } else if (tile.liquid().present()) {
            river(scene);
        } else {
            // As the shore moves its corners, which a flat top stands in for when its neighbours seek their walls.
            Vector3[] outline = new Vector3[6];
            for (int k = 0; k < 6; k++) { outline[k] = moved(k); }
            fan(outline, center.z, Finish.TOP);
        }
        for (int i = 0; i < faces.size(); i++) {
            // Only faces reaching the hex outline can meet a neighbour's: a bed's inner rings never do.
            if (i < interiorFrom || i >= interiorTo) { edgeTopography.add(faces.get(i)); }
        }
        if (detailed && !tile.ultraSublevel() && !tile.backdrop().present()) {
            int falls = 0;
            for (int edge = 0; edge < 6; edge++) { falls |= crests[edge] == null ? 0 : 1 << edge; }
            // Banks meet the actual ground rim, including emerged shallow bars, while the water keeps its level.
            relief.top(faces, bedOutline, openMouths, crestLine, falls);
            if (canal) {
                List<Face> cuts = cutRamps();
                relief.lining(faces);
                relief.castWalls(cuts);
            }
            // Past a separated side's nose the bank has no width: it would only stand as a sheet in the water.
            if (openEnds) {
                faces.removeIf(face -> face.finish() != Finish.BED && relief.stripLand(face.a().x, face.a().y) == 0
                      && relief.stripLand(face.b().x, face.b().y) == 0 && relief.stripLand(face.c().x, face.c().y) == 0);
            }
            meetWetCliffs();
            pavementGaps();
            reserveRoads();
            dig();
            relief.field(faces);
            int roughStart = faces.size();
            relief.boulders(faces);
            roughModels = BoardRough.place(this);
            rough.addAll(faces.subList(roughStart, faces.size()));
        }
    }

    /**
     * A cliff's submerged foot may lean toward the inset bed. Water meets that face at the surface height, not
     * vertically above the bed's edge. Move only the outer ring: the bed and the stream's rounded interior stay put.
     */
    private void meetWetCliffs() {
        if (waterFaces.isEmpty() || tile.frozen()) { return; }
        float base = tile.elevation() * BoardGeometry.level();
        boolean adjusted = false;
        for (int edge = 0; edge < 6; edge++) {
            if (!relief.wetCliff(edge)) { continue; }
            adjusted = true;
            final int cliffEdge = edge;
            List<Face> wall = faces.stream().filter(f -> f.finish() == Finish.WALL && f.landEdge() == cliffEdge).toList();
            Map<Float, List<ContactSegment>> sections = new LinkedHashMap<>();
            Vector3 toward = BoardGeometry.center(neighbor(scene, edge).coords(), 0)
                  .sub(BoardGeometry.center(tile.coords(), 0)).nor();
            // Keep the shared mouth endpoints and the bank joins exactly where the neighbouring hex puts them.
            for (int i = 1; i < SHORE_SEGMENTS; i++) {
                Vector3 point = waterline[edge * SHORE_SEGMENTS + i];
                float height = point.z;
                if (point.z >= base) {
                    point.set(relief.wetCliffContact(edge, i / (float) SHORE_SEGMENTS, point.z));
                } else {
                    point.set(contactPoint(point, toward, sections.computeIfAbsent(height, z -> contactSection(wall, z))));
                }
                point.z = height;
            }
            refineCliffContact(edge, wall, base, sections);
        }
        if (adjusted && !gradedWater) {
            // A flat pool's old ears can cross its widened contour at a cliff/bank corner.
            List<Vector3> contour = new ArrayList<>();
            for (int edge = 0; edge < 6; edge++) {
                List<Vector3> points = waterBoundary(edge);
                // A falling lip keeps its pulled-back water vertices.
                for (int i = 0; i < points.size() - 1; i++) {
                    contour.add(waterContacts.containsKey(edge) ? points.get(i) : water.get(edge * SHORE_SEGMENTS + i));
                }
            }
            waterFaces.clear();
            polygon(contour.toArray(Vector3[]::new), Finish.TOP, waterFaces);
        }
        if (adjusted && gradedWater) {
            // Carry the contact sheet a short distance inside the rock. The cliff hides this overlap; its
            // undercuts then contain water too, instead of exposing an air gap behind the surface's outline.
            for (int edge = 0; edge < 6; edge++) {
                if (!relief.wetCliff(edge)) { continue; }
                Vector3 into = BoardGeometry.center(neighbor(scene, edge).coords(), 0)
                      .sub(BoardGeometry.center(tile.coords(), 0)).nor().scl(BoardRelief.metres(2));
                List<Vector3> boundary = waterBoundary(edge);
                for (int i = 0; i + 1 < boundary.size(); i++) {
                    Vector3 a = boundary.get(i), b = boundary.get(i + 1);
                    if (Math.max(a.z, b.z) <= base) { continue; }
                    if ((b.x - a.x) * into.y - (b.y - a.y) * into.x >= 0) { continue; }
                    Vector3 outerA = new Vector3(a).add(into), outerB = new Vector3(b).add(into);
                    cliffWater.add(new Face(b, a, outerA, Finish.TOP));
                    cliffWater.add(new Face(b, outerA, outerB, Finish.TOP));
                }
            }
            waterFaces.addAll(cliffWater);
        }
    }

    /** The drawn outline of one edge, including both ends; mouths retain their shared construction samples. */
    /** Whether this hex's waterline from a to b along an edge is a bank: no mouth, nor the open stretch of a separated side. */
    boolean bankAlong(int edge, Vector3 a, Vector3 b) {
        return !mouth(edge) && (relief.stripLand(a.x, a.y) > 0 || relief.stripLand(b.x, b.y) > 0);
    }

    List<Vector3> waterBoundary(int edge) {
        List<Vector3> contact = waterContacts.get(edge);
        if (contact != null) { return contact; }
        List<Vector3> points = new ArrayList<>(SHORE_SEGMENTS + 1);
        for (int i = 0; i <= SHORE_SEGMENTS; i++) { points.add(waterline[(edge * SHORE_SEGMENTS + i) % waterline.length]); }
        return points;
    }

    /** Preserve the bends across a wall quad's diagonal, especially where its bed rises into a dry bank. */
    private void refineCliffContact(int edge, List<Face> wall, float base, Map<Float, List<ContactSegment>> sections) {
        List<Vector3> points = waterBoundary(edge), refined = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            Vector3 a = points.get(i), b = points.get(i + 1);
            List<Vector3> path = Math.abs(a.z - b.z) < .0001f && a.z < base
                  ? contactPath(a, b, sections.computeIfAbsent(a.z, z -> contactSection(wall, z))) : List.of(a, b);
            refined.addAll(path.subList(0, path.size() - 1));
            if (gradedWater && path.size() > 2) {
                // Replace just the outer triangle; the remaining rings retain the stream's bulge.
                for (int f = 0, count = waterFaces.size(); f < count; f++) {
                    Face face = waterFaces.get(f);
                    if (face.a() != a || face.b() != b) { continue; }
                    waterFaces.remove(f);
                    for (int k = 0; k < path.size() - 1; k++) {
                        waterFaces.add(new Face(path.get(k), path.get(k + 1), face.c(), Finish.TOP));
                    }
                    break;
                }
            }
        }
        refined.add(points.getLast());
        waterContacts.put(edge, refined);
    }

    private record ContactSegment(Vector3 a, Vector3 b) { }

    /** Nearest wall contact along a horizontal line; all scratch data belongs to this surface's build. */
    private static Vector3 contactPoint(Vector3 point, Vector3 toward, List<ContactSegment> section) {
        Vector3 contact = new Vector3(point);
        float nearest = Float.POSITIVE_INFINITY;
        for (ContactSegment segment : section) {
            Vector3 a = segment.a(), b = segment.b();
            float cross = toward.x * (b.y - a.y) - toward.y * (b.x - a.x);
            if (Math.abs(cross) < .000001f) { continue; }
            float t = ((a.x - point.x) * toward.y - (a.y - point.y) * toward.x) / cross;
            if (t < 0 || t > 1) { continue; }
            Vector3 hit = new Vector3(a).lerp(b, t);
            if (hit.dst2(point) < nearest) {
                nearest = hit.dst2(point);
                contact.set(hit);
            }
        }
        return contact;
    }

    /** Horizontal sections through the actual submerged wall triangles, without assuming planar quads. */
    private static List<ContactSegment> contactSection(List<Face> wall, float z) {
        List<ContactSegment> result = new ArrayList<>();
        for (Face face : wall) {
            Vector3[] corners = { face.a(), face.b(), face.c() };
            Vector3 first = null;
            for (int i = 0; i < 3; i++) {
                Vector3 a = corners[i], b = corners[(i + 1) % 3];
                if ((a.z <= z) == (b.z <= z)) { continue; }
                Vector3 p = new Vector3(a).lerp(b, (z - a.z) / (b.z - a.z));
                p.z = z;
                if (first == null) { first = p; }
                else if (first.dst2(p) > .000001f) { result.add(new ContactSegment(first, p)); }
            }
        }
        return result;
    }

    /** Walk either way along a connected section, starting and ending inside a segment when necessary. */
    private static List<Vector3> contactPath(Vector3 a, Vector3 b, List<ContactSegment> section) {
        for (int first = 0; first < section.size(); first++) {
            ContactSegment start = section.get(first);
            if (!onContact(a, start.a, start.b)) { continue; }
            for (Vector3 end : List.of(start.a, start.b)) {
                List<Vector3> path = new ArrayList<>(List.of(a));
                Vector3 from = a, to = end;
                int previous = first;
                for (int step = 0; step <= section.size(); step++) {
                    if (onContact(b, from, to)) {
                        if (path.size() > 1 && path.getLast().epsilonEquals(b, .002f)) { path.set(path.size() - 1, b); }
                        else { path.add(b); }
                        return path;
                    }
                    if (!path.getLast().epsilonEquals(to, .002f)) { path.add(to); }
                    int next = -1;
                    for (int k = 0; k < section.size(); k++) {
                        if (k == previous) { continue; }
                        ContactSegment candidate = section.get(k);
                        if (to.epsilonEquals(candidate.a, .002f) || to.epsilonEquals(candidate.b, .002f)) {
                            next = k;
                            break;
                        }
                    }
                    if (next < 0) { break; }
                    from = to;
                    ContactSegment candidate = section.get(next);
                    to = from.epsilonEquals(candidate.a, .002f) ? candidate.b : candidate.a;
                    previous = next;
                }
            }
        }
        return List.of(a, b);
    }

    private static boolean onContact(Vector3 p, Vector3 a, Vector3 b) {
        float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
        float t = length == 0 ? 0 : Math.clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / length, 0, 1);
        return square(p.x - a.x - t * dx) + square(p.y - a.y - t * dy) < .000004f;
    }

    /**
     * The level at which a road meets its edge in this direction: a connected bridge deck's approach height, else the
     * carriageway's grade ({@link BoardRoadGrade#edgeLevel}).
     */
    static float roadEdgeElevation(BoardScene.Tile tile, BoardScene.Tile neighbor, int direction) {
        if (neighbor == null || tile.ultraSublevel() || neighbor.ultraSublevel()) { return BoardRoadGrade.ground(tile); }
        var bridge = connectingBridge(tile, neighbor, direction);
        if (bridge == null) { return BoardRoadGrade.edgeLevel(tile, neighbor, direction); }
        // A raised bridge owns its solid approach, leaving the bank and its shoreline intact underneath.
        if (neighbor.elevation() + bridge.elevation() > BoardRoadGrade.ground(tile)) { return BoardRoadGrade.ground(tile); }
        return BoardBridge.edgeElevation(neighbor, tile, (direction + 3) % 6);
    }

    /** Presentation only: a road end can meet unpaved ground across at most two levels. */
    static boolean hasRoadApproach(BoardScene.Tile tile, BoardScene.Tile neighbor, int direction) {
        if (neighbor == null || tile.ultraSublevel() || neighbor.ultraSublevel()
              || !BoardRoad.groundShown(tile) || !BoardRoad.groundShown(neighbor)) {
            return false;
        }
        boolean exit = (tile.wayExits() & (1 << direction)) != 0;
        int reverse = (direction + 3) % 6;
        boolean continuation = (neighbor.wayExits() & (1 << reverse)) != 0;
        // A bridge approach changes only the road hex, leaving the ground beneath the deck intact.
        if (connectingBridge(tile, neighbor, direction) != null
              || connectingBridge(neighbor, tile, reverse) != null) {
            return false;
        }
        return HexTile.roadApproach(exit, continuation, Math.abs(tile.floor() - neighbor.floor()));
    }

    static BoardScene.Feature connectingBridge(BoardScene.Tile road, BoardScene.Tile bridge, int direction) {
        if (!BoardRoad.groundShown(road) || (road.wayExits() & (1 << direction)) == 0) {
            return null;
        }
        int reverse = (direction + 3) % 6;
        boolean continuation = !bridge.liquid().present() && (bridge.wayExits() & (1 << reverse)) != 0;
        // A level deck wins over a sloped ground road; a sloped deck is the last connected-road fallback.
        return bridge.features().stream().filter(feature -> feature.asset().equals("bridge")
              && HexTile.bridgeApproach(road.floor(), bridge.elevation(), bridge.elevation() + feature.elevation(),
                    continuation)
              && (feature.bridgeExits() & (1 << reverse)) != 0).findFirst().orElse(null);
    }

    private void river(BoardScene scene) {
        float[] shore = new float[6];
        float[] lip = new float[6];
        // Levels the land beside each bank rises above this hex; none at a mouth or the board's edge.
        float[] rise = new float[6];
        // Mouths a fall pours in over: the pool below opens wide and deep round its foot.
        boolean[] plunge = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            boolean bank = neighbor != null && !tile.joins(neighbor, BoardGeometry.edgeDirection(edge));
            // A bank; where a slope beside it reaches into the hex, only a wet margin where the water meets it. A slope
            // up from the water runs on under it to the bed.
            shore[edge] = !bank ? 0 : least(edge);
            rise[edge] = shore[edge] > 0 ? neighbor.elevation() - tile.elevation() : 0;
            if (shore[edge] == 0) {
                openMouths |= 1 << edge;
                // A fall owns the last stretch of its mouth: the water stops short so the sheet can curve down. Water
                // that runs on into the board's edge is cut off there with the board.
                if (neighbor != null && tile.elevation() > neighbor.elevation() && !waterSlope(tile, neighbor)) {
                    lip[edge] = fallLip(BoardGeometry.surfaceZ(tile), BoardGeometry.surfaceZ(neighbor));
                }
                plunge[edge] = neighbor != null && neighbor.elevation() > tile.elevation()
                      && !waterSlope(tile, neighbor);
            }
        }
        canal = !tile.frozen() && !columns && relief.canalHex();
        deckCuts = canal ? rampCuts(scene) : List.of();
        waterline = shoreline(scene, shore, rise, plunge, BoardGeometry.waterZ(tile));
        gradeWaterline(scene, shore);
        for (int edge = 0; edge < 6; edge++) {
            if (lip[edge] > 0) {
                joinA[edge] = join(scene, shore, lip, edge, (edge + 5) % 6);
                joinB[edge] = join(scene, shore, lip, edge, (edge + 1) % 6);
            }
        }
        // The pool reaches out to the crests its falls pour over, which curve round the corners the falls share.
        crestLine = waterline.clone();
        for (int edge = 0; edge < 6; edge++) {
            if (lip[edge] > 0) {
                crests[edge] = crest(waterline, lip, edge);
                for (int i = 0; i <= SHORE_SEGMENTS; i++) {
                    crestLine[(edge * SHORE_SEGMENTS + i) % crestLine.length] =
                          crests[edge].point(i / (float) SHORE_SEGMENTS);
                }
            }
        }
        Vector3[] surface = pulledBack(crestLine, lip);
        basin(scene, waterline, crestLine, surface, shore, rise, plunge);
        for (int edge = 0; edge < 6; edge++) {
            if (shore[edge] == 0) {
                continue; // An open river mouth has no bank or wall across it.
            }
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                int index = edge * SHORE_SEGMENTS + segment;
                int next = (index + 1) % waterline.length;
                Vector3 a = new Vector3(shoreCorners[edge]).lerp(shoreCorners[(edge + 1) % 6],
                      segment / (float) SHORE_SEGMENTS);
                Vector3 b = new Vector3(shoreCorners[edge]).lerp(shoreCorners[(edge + 1) % 6],
                      (segment + 1f) / SHORE_SEGMENTS);
                Vector3 lipA = shoreLip(bedOutline[index], a, index);
                Vector3 lipB = shoreLip(bedOutline[next], b, next);
                quad(a, b, lipB, lipA, Finish.TOP, edge);
                quad(lipA, lipB, bedOutline[next], bedOutline[index], Finish.SHORE, edge);
            }
        }
        if (tile.frozen()) {
            outline.addAll(List.of(waterline));
            freeze(scene, shore, surface, plunge);
        } else {
            // The water recedes from each crest; the sheet's lip curves back down over it.
            outline.addAll(List.of(waterline));
            water.addAll(List.of(surface));
            if (!tile.liquid().molten()) {
                if (gradedWater) { slopingSurface(surface); }
                else { polygon(surface, Finish.TOP, waterFaces); }
            }
        }
        // Frozen falls retain the same crest, cliff and ownership as liquid falls; their renderer supplies solid ice.
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            if (crests[edge] != null) {
                float bottom = BoardGeometry.surfaceZ(other);
                waterfalls.add(new Side(new Vector3(crests[edge].a()), new Vector3(crests[edge].b()), bottom,
                      bottom, edge));
            } else if (other == null && !tile.frozen()) {
                cut(edge);
            }
        }
    }

    /** Tile pixels a lake slab reaches below open water; its freeboard is the pixel between the water and its level. */
    private static final float ICE_DRAFT = 1.5f;
    /** Tile pixels a frozen edge breaks out over open water at most. */
    private static final float ICE_MARGIN = 6;
    /** Tile pixels a slab breaks off short of an ice-free shore at most, opening a lead along it. */
    private static final float ICE_LEAD = 4;

    /**
     * A frozen lake's slab. Its top is the level units stand on, one pixel of freeboard above open water. It runs on
     * through mouths into frozen neighbours at its level and up banks into frozen land. Toward open water it breaks
     * out raggedly over the neighbour's water; along an ice-free shore it breaks off short of the waterline, leaving
     * a lead with rubble. It shows its thickness wherever it ends. All of that is drawn only: support and picking
     * keep the waterline.
     */
    private void freeze(BoardScene scene, float[] shore, Vector3[] surface, boolean[] plunge) {
        float scale = BoardGeometry.hexScale(), top = BoardGeometry.surfaceZ(tile), water = BoardGeometry.waterZ(tile);
        float bottom = water - ICE_DRAFT * scale;
        int n = waterline.length;
        Vector3[] slab = new Vector3[n];
        float area = 0;
        for (int i = 0; i < n; i++) {
            slab[i] = new Vector3(waterline[i].x, waterline[i].y, top);
            Vector3 next = waterline[(i + 1) % n];
            area += waterline[i].x * next.y - next.x * waterline[i].y;
        }
        // Outward is to the right of the contour's direction when it runs counter-clockwise.
        float outward = Math.signum(area);
        boolean[] ragged = new boolean[6], continuous = new boolean[6], iced = new boolean[6], broken = new boolean[6];
        List<Vector3[]> impact = new ArrayList<>();
        boolean[] impacted = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            boolean level = shore[edge] == 0 && other != null && other.elevation() == tile.elevation();
            continuous[edge] = level && other.frozen();
            ragged[edge] = level && !other.frozen();
            // Frozen land's ice runs down the bank to the slab; a cut edge there would split the sheet.
            iced[edge] = shore[edge] > 0 && other != null && other.frozen() && !other.liquid().present();
            // The board's edge cuts the slab straight, as it cuts all water.
            broken[edge] = other != null && crests[edge] == null && !plunge[edge]
                  && !continuous[edge] && !ragged[edge] && !iced[edge];
            if (plunge[edge] && !other.frozen() && !other.liquid().molten()) {
                // Reuse the actual crest and completed sheet, including joined corners and stream bulges.
                // The open source needs only its existing river layout, not detailed cliff/ground meshes.
                BoardSurface upper = new BoardSurface(scene, other, false, lod);
                for (Side fall : upper.waterfalls) {
                    if (!other.coords().translated(BoardGeometry.edgeDirection(fall.edge())).equals(tile.coords())) { continue; }
                    Vector3[][] grid = GpuWaterfall.grid(upper, fall);
                    Vector3[] landing = new Vector3[grid.length];
                    for (int column = 0; column < grid.length; column++) { landing[column] = grid[column][grid[column].length - 1]; }
                    impact.add(landing);
                    impacted[edge] = true;
                }
            }
        }
        // Support: the waterline, straight edges by their corners alone.
        List<Vector3> support = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (turns(slab[(i + n - 1) % n], slab[i], slab[(i + 1) % n])) { support.add(slab[i]); }
        }
        polygon(support.toArray(Vector3[]::new), Finish.ICE, faces);
        // The drawn slab ends where the frozen curtain starts. Its logical support remains the original waterline.
        for (int i = 0; i < n; i++) { slab[i] = new Vector3(surface[i].x, surface[i].y, top); }
        // Drawn: the waterline's samples, which the ragged margins share, and twice as many along broken shores.
        List<Vector3> base = new ArrayList<>();
        List<Integer> edges = new ArrayList<>();
        List<Boolean> onShore = new ArrayList<>();
        int[] samples = new int[n];
        for (int i = 0; i < n; i++) {
            int edge = i / SHORE_SEGMENTS;
            samples[i] = base.size();
            base.add(slab[i]);
            edges.add(edge);
            onShore.add(broken[edge] && (i % SHORE_SEGMENTS != 0 || broken[(edge + 5) % 6]));
            if (broken[edge] || impacted[edge]) {
                base.add(new Vector3(slab[i]).lerp(slab[(i + 1) % n], .5f));
                edges.add(edge);
                onShore.add(broken[edge]);
            }
        }
        int m = base.size();
        float[] recess = shoreBreak(onShore, scale);
        Vector3[] drawn = new Vector3[m];
        boolean[] opened = new boolean[m];
        for (int k = 0; k < m; k++) {
            Vector3 before = base.get((k + m - 1) % m), after = base.get((k + 1) % m);
            Vector3 inward = new Vector3(before.y - after.y, after.x - before.x, 0).nor().scl(outward);
            drawn[k] = recess[k] == 0 ? base.get(k) : new Vector3(base.get(k)).mulAdd(inward, recess[k]);
            Vector3 cleared = clearIceImpact(drawn[k], impact);
            opened[k] = cleared != drawn[k];
            drawn[k] = cleared;
        }
        List<Vector3> outline = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            if (ragged[edges.get(k)] || recess[k] > 0 || opened[k]
                  || turns(drawn[(k + m - 1) % m], drawn[k], drawn[(k + 1) % m])) {
                outline.add(drawn[k]);
            }
        }
        if (impact.isEmpty()) { polygon(outline.toArray(Vector3[]::new), Finish.ICE, iceSlab); }
        else {
            // A narrow impact can cross a pre-existing ragged shore corner. Normalize that contour with the
            // shared area tessellator; simple ear clipping can otherwise bridge across the whole opening.
            Path2D.Float boundary = new Path2D.Float();
            for (int i = 0; i < outline.size(); i++) {
                Vector3 p = outline.get(i);
                if (i == 0) { boundary.moveTo(p.x, p.y); } else { boundary.lineTo(p.x, p.y); }
            }
            boundary.closePath();
            for (var triangle : BoardTacticalGeometry.flat(boundary, -1)) {
                triangle.a().z = triangle.b().z = triangle.c().z = top;
                iceFace(iceSlab, triangle.a(), triangle.b(), triangle.c(), Vector3.Z);
            }
        }
        Random random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL);
        for (int k = 0; k < m; k++) {
            int edge = edges.get(k), next = (k + 1) % m;
            boolean pocket = opened[k] || opened[next];
            if (!pocket && (continuous[edge] || iced[edge] || ragged[edge] || crests[edge] != null || plunge[edge])) { continue; }
            iceSkirt(iceBroken, drawn[k], drawn[next], bottom, outward);
            if (pocket || recess[k] > 0 || recess[next] > 0) {
                Vector3 a = new Vector3(drawn[k].x, drawn[k].y, water), b = new Vector3(drawn[next].x, drawn[next].y, water);
                Vector3 c = new Vector3(base.get(next).x, base.get(next).y, water);
                Vector3 d = new Vector3(base.get(k).x, base.get(k).y, water);
                List<Face> opening = pocket ? waterFaces : iceLeads;
                iceFace(opening, a, b, c, Vector3.Z);
                iceFace(opening, a, c, d, Vector3.Z);
            }
            // Rubble of the broken edge floats in the wider stretches of the lead.
            if (!pocket && recess[k] > 2.2f * scale && random.nextFloat() < .4f) {
                Vector3 centre = new Vector3(drawn[k]).lerp(base.get(k), .5f);
                piece(centre, Math.min(.3f * recess[k], 1.4f * scale) * (.6f + .4f * random.nextFloat()),
                      water + .35f * scale, water - .8f * scale, random);
            }
        }
        for (int i = 0; i < n; i++) { slab[i] = drawn[samples[i]]; }
        for (int edge = 0; edge < 6; edge++) {
            if (!ragged[edge]) { continue; }
            int first = edge * SHORE_SEGMENTS;
            Vector3 start = slab[first], end = slab[(first + SHORE_SEGMENTS) % n];
            Vector3 out = new Vector3(end.y - start.y, start.x - end.x, 0).nor().scl(outward);
            Vector3 opening = cornerBreak(scene, edge, edge, ragged, continuous);
            Vector3 closing = cornerBreak(scene, edge, edge + 1, ragged, continuous);
            // A fracture line: straight runs between irregular breaks.
            Random front = new Random(Float.floatToIntBits(start.x + end.x) * 0x9E3779B97F4A7C15L
                  ^ Float.floatToIntBits(start.y + end.y));
            int breaks = 7;
            float[] at = new float[breaks + 1], reach = new float[breaks + 1];
            for (int k = 0; k <= breaks; k++) {
                at[k] = k == 0 || k == breaks ? k / (float) breaks : (k + (front.nextFloat() - .5f) * .7f) / breaks;
                reach[k] = ICE_MARGIN * scale * (.2f + .8f * front.nextFloat());
            }
            // Two margin samples per waterline sample carry the breaks. Near each corner the line eases into the
            // point it shares with the front beyond that corner, or into the corner itself where the front ends.
            Vector3[] margin = new Vector3[2 * SHORE_SEGMENTS + 1];
            for (int j = 0, k = 0; j < margin.length; j++) {
                float t = j / (float) (margin.length - 1);
                Vector3 point = j % 2 == 0 ? slab[(first + j / 2) % n]
                      : new Vector3(slab[(first + j / 2) % n]).lerp(slab[(first + j / 2 + 1) % n], .5f);
                while (k < breaks - 1 && at[k + 1] < t) { k++; }
                float along = Math.clamp((t - at[k]) / Math.max(at[k + 1] - at[k], 1e-4f), 0, 1);
                float from = 1 - smooth(t / .3f), to = 1 - smooth((1 - t) / .3f);
                margin[j] = new Vector3(point).mulAdd(out, BoardRelief.lerp(reach[k], reach[k + 1], along) * (1 - from - to))
                      .mulAdd(opening, from).mulAdd(closing, to);
                margin[j] = clearIceImpact(margin[j], impact);
            }
            for (int i = 0; i < SHORE_SEGMENTS; i++) {
                Vector3 a = slab[(first + i) % n], b = slab[(first + i + 1) % n];
                iceFace(iceBroken, a, margin[2 * i], margin[2 * i + 1], Vector3.Z);
                iceFace(iceBroken, a, margin[2 * i + 1], b, Vector3.Z);
                iceFace(iceBroken, b, margin[2 * i + 1], margin[2 * i + 2], Vector3.Z);
            }
            for (int j = 0; j + 1 < margin.length; j++) { iceSkirt(iceBroken, margin[j], margin[j + 1], bottom, outward); }
            // Leave the broken-open mouth clear rather than putting decorative floes back under its fall.
            if (!opened[samples[first]] && !opened[samples[(first + SHORE_SEGMENTS) % n]]) { floes(margin, out, front); }
        }
    }

    /** Keep the ordered ice boundary just beyond the actual landing, with less than one tile pixel of clearance. */
    private Vector3 clearIceImpact(Vector3 point, List<Vector3[]> impact) {
        float dx = center.x - point.x, dy = center.y - point.y;
        float length = (float) Math.hypot(dx, dy), fraction = 0, scale = BoardGeometry.hexScale();
        if (length <= .0001f) { return point; }
        float buffer = scale * (.5f + .3f * BoardRelief.noise(point.x / (5 * scale), point.y / (5 * scale)));
        for (Vector3[] landing : impact) {
            for (int index = 0; index + 1 < landing.length; index++) {
                Vector3 a = landing[index], b = landing[index + 1];
                float sx = b.x - a.x, sy = b.y - a.y, cross = dx * sy - dy * sx;
                if (Math.abs(cross) <= .000001f) { continue; }
                float ax = a.x - point.x, ay = a.y - point.y;
                float along = (ax * dy - ay * dx) / cross;
                float inward = (ax * sy - ay * sx) / cross;
                // Close the narrow opening around the finite sheet ends too; otherwise the next untouched
                // contour sample cuts a diagonal wedge back across the endpoint.
                float cap = scale / (float) Math.hypot(sx, sy);
                if (along >= (index == 0 ? -cap : 0) && along <= (index + 2 == landing.length ? 1 + cap : 1)
                      && inward > 0 && inward < 1) {
                    fraction = Math.max(fraction, Math.min(1, inward + buffer / length));
                }
            }
        }
        return fraction == 0 ? point : new Vector3(point).lerp(new Vector3(center.x, center.y, point.z), fraction);
    }

    private static boolean turns(Vector3 a, Vector3 b, Vector3 c) { return turns(a.x, a.y, b.x, b.y, c.x, c.y); }

    /** Whether a run turns at b, beyond the hair by which rounding bends samples of one straight edge. */
    static boolean turns(float ax, float ay, float bx, float by, float cx, float cy) {
        float turn = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
        return Math.abs(turn) > 1e-4f * (float) Math.hypot(bx - ax, by - ay) * (float) Math.hypot(cx - bx, cy - by);
    }

    /**
     * How far the slab breaks off short of each drawn point on an ice-free shore: straight runs between irregular
     * breaks, easing onto the waterline over three points wherever the shore meets another kind of edge.
     */
    private float[] shoreBreak(List<Boolean> onShore, float scale) {
        int m = onShore.size();
        Random random = new Random(tile.coords().getX() * 0xC2B2AE3D27D4EB4FL ^ tile.coords().getY() * 0x165667B19E3779F9L);
        float[] knots = new float[m], recess = new float[m];
        for (int k = 0; k < m; k++) { knots[k] = ICE_LEAD * scale * (.1f + .9f * random.nextFloat()); }
        // Points along the ring to the nearest point off the shore, both ways round.
        int[] away = new int[m];
        Arrays.fill(away, m);
        for (int pass = 0; pass < 2; pass++) {
            for (int step = 0; step < 2 * m; step++) {
                int k = pass == 0 ? step % m : (2 * m - 1 - step) % m;
                int previous = pass == 0 ? (k + m - 1) % m : (k + 1) % m;
                away[k] = onShore.get(k) ? Math.min(away[k], away[previous] + 1) : 0;
            }
        }
        for (int k = 0; k < m; k++) {
            if (!onShore.get(k)) { continue; }
            int from = k - k % 3, span = Math.min(3, m - from);
            recess[k] = BoardRelief.lerp(knots[from], knots[(from + span) % m], (k - from) / (float) span)
                  * Math.min(1, away[k] / 3f);
        }
        return recess;
    }

    /**
     * A few broken floes drifting just off a ragged front, riding lower than the slab. They keep to the middle of the
     * edge, clear of the corners where the fronts of other slabs break out.
     */
    private void floes(Vector3[] margin, Vector3 out, Random random) {
        float scale = BoardGeometry.hexScale(), water = BoardGeometry.waterZ(tile);
        List<Vector3> centres = new ArrayList<>();
        List<Float> radii = new ArrayList<>();
        int count = 1 + random.nextInt(4);
        for (int f = 0; f < count; f++) {
            float along = (.2f + .6f * random.nextFloat()) * (margin.length - 1);
            float size = random.nextFloat();
            float radius = (1.2f + 4.5f * size * size) * scale;
            float gap = (1 + 7 * random.nextFloat()) * scale;
            int j = (int) along;
            Vector3 centre = new Vector3(margin[j]).lerp(margin[j + 1], along - j).mulAdd(out, gap + radius);
            boolean clear = true;
            for (int k = 0; clear && k < centres.size(); k++) {
                clear = Math.hypot(centres.get(k).x - centre.x, centres.get(k).y - centre.y) > radii.get(k) + radius + scale;
            }
            if (!clear) { continue; }
            centres.add(centre);
            radii.add(radius);
            piece(centre, radius, water + .5f * scale, water - scale, random);
        }
    }

    /** One broken piece of ice, an irregular polygon round centre with its own cut edge. */
    private void piece(Vector3 centre, float radius, float top, float bottom, Random random) {
        int sides = 5 + random.nextInt(3);
        double turn = random.nextDouble() * 2 * Math.PI;
        Vector3[] ring = new Vector3[sides];
        for (int s = 0; s < sides; s++) {
            double angle = turn + (s + (random.nextDouble() - .5) * .5) * 2 * Math.PI / sides;
            float r = radius * (.7f + .3f * random.nextFloat());
            ring[s] = new Vector3(centre.x + (float) Math.cos(angle) * r, centre.y + (float) Math.sin(angle) * r, top);
        }
        Vector3 middle = new Vector3(centre.x, centre.y, top);
        for (int s = 0; s < sides; s++) {
            iceFace(iceBroken, middle, ring[s], ring[(s + 1) % sides], Vector3.Z);
            // The ring runs counter-clockwise, so each side faces out to the right of its direction.
            iceSkirt(iceBroken, ring[s], ring[(s + 1) % sides], bottom, 1);
        }
    }

    /**
     * Where a ragged front reaches a corner, the break every slab at that corner shares: out round this hex's own
     * corner where the front turns it, or on into the open water where the front continues along a frozen neighbour.
     * A front that ends at the corner stays on it.
     */
    private Vector3 cornerBreak(BoardScene scene, int edge, int corner, boolean[] ragged, boolean[] continuous) {
        int beside = corner == edge ? (edge + 5) % 6 : (edge + 1) % 6;
        Vector3 point = corners[corner % 6];
        Vector3 direction;
        if (ragged[beside]) {
            direction = new Vector3(point).sub(center);
        } else if (continuous[beside]) {
            direction = BoardGeometry.center(neighbor(scene, edge).coords(), 0).sub(point);
        } else {
            return new Vector3();
        }
        direction.z = 0;
        // Corners come from the shared lattice, so both slabs derive the same break from identical coordinates.
        long bits = Float.floatToIntBits(point.x) * 0x9E3779B97F4A7C15L ^ Float.floatToIntBits(point.y) * 0xC2B2AE3D27D4EB4FL;
        float shared = ((bits ^ bits >>> 31) & 0xFFFFFF) / (float) 0x1000000;
        return direction.nor().scl(ICE_MARGIN * BoardGeometry.hexScale() * (.3f + .7f * shared));
    }

    private static float smooth(float x) {
        float t = Math.clamp(x, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** The ice's exposed edge below a to b, facing out of it. */
    private static void iceSkirt(List<Face> into, Vector3 a, Vector3 b, float bottom, float outward) {
        Vector3 facing = new Vector3(b.y - a.y, a.x - b.x, 0).scl(outward);
        Vector3 lowA = new Vector3(a.x, a.y, bottom), lowB = new Vector3(b.x, b.y, bottom);
        iceFace(into, a, b, lowB, facing);
        iceFace(into, a, lowB, lowA, facing);
    }

    private static void iceFace(List<Face> into, Vector3 a, Vector3 b, Vector3 c, Vector3 facing) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        if (normal.len2() <= 0.000001f) { return; }
        into.add(normal.dot(facing) >= 0 ? new Face(a, b, c, Finish.ICE) : new Face(a, c, b, Finish.ICE));
    }

    /** Connected liquid steps, including lava, use surface levels, never the depths of their beds. */
    static boolean waterSlope(BoardScene.Tile a, BoardScene.Tile b) {
        return a != null && b != null && !a.frozen() && !b.frozen()
              && a.liquid().present() && a.joins(b)
              && Math.abs(a.elevation() - b.elevation()) <= 2;
    }

    /** Both halves of an open mouth share its height and ease back to their own centre's water level. */
    private void gradeWaterline(BoardScene scene, float[] shore) {
        if (tile.frozen()) { return; }
        float base = BoardGeometry.waterZ(tile);
        float[] ends = new float[6];
        for (int k = 0; k < 6; k++) {
            int previous = (k + 5) % 6;
            if (shore[previous] == 0 && shore[k] == 0) {
                List<BoardScene.Tile> joined = cornerWater(scene, k);
                float sum = 0;
                for (BoardScene.Tile other : joined) { sum += BoardGeometry.waterZ(other); }
                ends[k] = sum / joined.size();
            } else {
                BoardScene.Tile other = shore[previous] == 0 ? neighbor(scene, previous)
                      : shore[k] == 0 ? neighbor(scene, k) : null;
                ends[k] = waterSlope(tile, other) ? (base + BoardGeometry.waterZ(other)) / 2 : base;
            }
        }
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile other = neighbor(scene, e);
            float middle = shore[e] == 0 && waterSlope(tile, other) ? (base + BoardGeometry.waterZ(other)) / 2 : base;
            Vector3 a = waterline[e * SHORE_SEGMENTS], b = waterline[(e + 1) * SHORE_SEGMENTS % waterline.length];
            for (int i = 0; i < SHORE_SEGMENTS; i++) {
                Vector3 point = waterline[e * SHORE_SEGMENTS + i];
                if (shore[e] == 0) {
                    point.z = mouthDepth(point, a, ends[e], b, ends[(e + 1) % 6], middle);
                } else {
                    // Keep the canonical mouth endpoints where neighbouring banks meet. Ease away from them over the
                    // same share of the bank as the surface eases inward from its rim, so the water lies about level
                    // across a descending channel; a mouth's third of an edge tilted it sideways by up to 60 degrees.
                    float t = i / (float) SHORE_SEGMENTS, reach = PLATEAU;
                    point.z = waterGrade(point.x, point.y) + (ends[e] - waterGrade(a.x, a.y)) * ease(t / reach)
                          + (ends[(e + 1) % 6] - waterGrade(b.x, b.y)) * ease((1 - t) / reach);
                }
                gradedWater |= Math.abs(point.z - base) > .0001f;
            }
        }
    }

    /** Open water connected by gentle steps at this corner, in a canonical order for water heights. */
    private List<BoardScene.Tile> cornerWater(BoardScene scene, int index) {
        return corner(scene, index, BoardSurface::waterSlope);
    }

    /**
     * This hex and those round the corner where edge {@code index} starts that {@code joins} links to it, directly or
     * through each other, in the same order from every hex of the corner.
     */
    private List<BoardScene.Tile> corner(BoardScene scene, int index,
          BiPredicate<BoardScene.Tile, BoardScene.Tile> joins) {
        List<BoardScene.Tile> joined = new ArrayList<>(List.of(tile));
        for (int pass = 0; pass < 2; pass++) {
            for (int e : new int[] { (index + 5) % 6, index }) {
                BoardScene.Tile other = neighbor(scene, e);
                if (other == null || joined.contains(other)) { continue; }
                if (joined.stream().anyMatch(t -> joins.test(t, other))) { joined.add(other); }
            }
        }
        joined.sort(java.util.Comparator.comparingInt((BoardScene.Tile t) -> t.coords().getX())
              .thenComparingInt(t -> t.coords().getY()));
        return joined;
    }

    /**
     * A gentle correction toward connected water levels. Retain most of the radial profile's flowing bulge;
     * fully blending toward a cross-channel level makes the descent look like a flat ramp. Beds and water share it.
     */
    private float waterGrade(float x, float y) {
        float base = BoardGeometry.waterZ(tile);
        float radius2 = square((1 + PLATEAU) * .5f * BoardGeometry.height());
        float own = Math.max(0, 1 - (square(x - center.x) + square(y - center.y)) / radius2);
        float total = own * own, change = 0;
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            if (!waterSlope(tile, other)) { continue; }
            float dx = x - BoardGeometry.centerX(other.coords()), dy = y - BoardGeometry.centerY(other.coords());
            float weight = Math.max(0, 1 - (dx * dx + dy * dy) / radius2);
            weight *= weight;
            total += weight;
            change += weight * (BoardGeometry.waterZ(other) - base);
        }
        return total > 0 ? base + .2f * change / total : base;
    }

    /** The shared mouth/crest profile is exact at the rim; its correction eases away inside the channel. */
    private float waterGrade(float x, float y, Vector3 rim, float inward) {
        return waterGrade(x, y) + (rim.z - waterGrade(rim.x, rim.y)) * ease(inward);
    }

    /** Rings between a descending transparent stream's rim and its level centre. */
    private static final int SLOPE_RINGS = 4;

    /** Sample a transparent stream's rounded descent, with a level centre. */
    private void slopingSurface(Vector3[] outer) {
        Vector3[] ring = outer;
        float base = BoardGeometry.waterZ(tile);
        for (int k = 1; k <= SLOPE_RINGS; k++) {
            float x = k / (float) SLOPE_RINGS, scale = 1 - PLATEAU * x;
            Vector3[] inner = new Vector3[outer.length];
            for (int i = 0; i < outer.length; i++) {
                float px = center.x + (outer[i].x - center.x) * scale;
                float py = center.y + (outer[i].y - center.y) * scale;
                inner[i] = new Vector3(px, py, waterGrade(px, py, outer[i], x));
            }
            for (int i = 0; i < outer.length; i++) {
                int j = (i + 1) % outer.length;
                waterFaces.add(new Face(ring[i], ring[j], inner[j], Finish.TOP));
                waterFaces.add(new Face(ring[i], inner[j], inner[i], Finish.TOP));
            }
            ring = inner;
        }
        Vector3 anchor = new Vector3(center.x, center.y, base);
        for (int i = 0; i < ring.length; i++) {
            waterFaces.add(new Face(anchor, ring[i], ring[(i + 1) % ring.length], Finish.TOP));
        }
    }

    float waterHeight(float x, float y) {
        float height = BoardGeometry.waterZ(tile);
        if (!gradedWater) { return height; }
        float drawn = sampleHeight(waterFaces, x, y, Float.NaN);
        if (Float.isFinite(drawn)) { return drawn; }
        // Bank vertices lie outside the water mesh. Extend the nearest shore's height for their wetness and bed
        // tint; reverting to the hex's level painted dry banks beside a descending stream as deep water.
        float nearest = Float.POSITIVE_INFINITY;
        for (int edge = 0; edge < 6; edge++) {
            List<Vector3> contact = waterContacts.get(edge);
            int count = contact == null ? SHORE_SEGMENTS : contact.size() - 1;
            for (int i = 0; i < count; i++) {
                Vector3 a = contact == null ? waterline[edge * SHORE_SEGMENTS + i] : contact.get(i);
                Vector3 b = contact == null ? waterline[(edge * SHORE_SEGMENTS + i + 1) % waterline.length] : contact.get(i + 1);
                float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
                float t = length == 0 ? 0 : Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / length, 0, 1);
                float distance = square(x - a.x - t * dx) + square(y - a.y - t * dy);
                if (distance < nearest) {
                    nearest = distance;
                    height = BoardRelief.lerp(a.z, b.z, t);
                }
            }
        }
        return height;
    }

    /**
     * Whitewater gathers after a stream starts descending and settles as it levels out. The upper hex owns the
     * entire slope's effect, sampled at the drawn water height, so neither hex spreads foam over its level approach.
     */
    float slopeAgitation(Vector3 point) {
        return slopeEffects(point, null);
    }

    /** The upper surface owns each descent's whitewater and added downhill velocity, in hex widths per second. */
    float slopeEffects(Vector3 point, Vector2 acceleration) {
        if (!gradedWater) { return 0; }
        float top = BoardGeometry.waterZ(tile), agitation = 0;
        if (point.z >= top) { return 0; }
        boolean inside = BoardGeometry.contains(tile.coords(), point.x, point.y);
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            if (!waterSlope(tile, other) || other.elevation() >= tile.elevation()) { continue; }
            float dx = BoardGeometry.centerX(other.coords()) - center.x;
            float dy = BoardGeometry.centerY(other.coords()) - center.y;
            float length2 = dx * dx + dy * dy;
            float along = ((point.x - center.x) * dx + (point.y - center.y) * dy) / length2;
            float drop = tile.elevation() - other.elevation();
            float descended = (top - point.z) / (drop * BoardGeometry.level());
            if (acceleration != null && along > 0) {
                // Gain speed from the actual lost surface height, never the riverbed depth. Carry that momentum
                // through the foot, then ease it away inside the receiving hex. The rounded corridor also keeps
                // unrelated nearby streams out of this descent and slows the current toward the banks.
                float across = ((point.x - center.x) * dy - (point.y - center.y) * dx) / length2;
                float distance = (float) Math.hypot(Math.max(0, along - 1), across);
                float wake = 1 - BoardRelief.smooth((distance - .15f) / .25f);
                float fallen = Math.clamp(descended, 0, 1) * drop;
                float gain = ((float) Math.sqrt(.01f + .08f * fallen) - .1f) * wake;
                float length = (float) Math.sqrt(length2);
                acceleration.add(dx / length * gain, dy / length * gain);
            }
            if (!inside && !BoardGeometry.contains(other.coords(), point.x, point.y)) { continue; }
            float churn = BoardRelief.smooth((descended - .15f) / .5f)
                  * (1 - BoardRelief.smooth((descended - .85f) / .15f)) * BoardRelief.smooth(along * 4);
            agitation = Math.max(agitation, (drop == 2 ? .85f : .3f) * churn);
        }
        return agitation;
    }

    /** The water's cut face along an edge that the board's edge cuts through: from its bed up to its surface. */
    private void cut(int edge) {
        float tolerance = .001f * BoardGeometry.hexScale();
        for (int i = 0; i < SHORE_SEGMENTS; i++) {
            int at = edge * SHORE_SEGMENTS + i, next = (at + 1) % bedOutline.length;
            Vector3 a = bedOutline[at], b = bedOutline[next];
            // A descending pool's border shares the drawn rim, including a neighbouring fall's pulled-back end.
            Vector3 topA = new Vector3(water.get(at)), topB = new Vector3(water.get(next));
            if (b.z < topB.z - tolerance) {
                cutFaces.add(new Face(new Vector3(a), new Vector3(b), topB, Finish.TOP, edge));
            }
            if (a.z < topA.z - tolerance) {
                cutFaces.add(new Face(new Vector3(a), topB, topA, Finish.TOP, edge));
            }
        }
    }

    /** The crest of the fall over {@code edge}, from where its mouth's water starts to where it stops. */
    private Crest crest(Vector3[] waterline, float[] lip, int edge) {
        int previous = (edge + 5) % 6, next = (edge + 1) % 6;
        Vector3 along = new Vector3(corners[next]).sub(corners[edge]).nor();
        Vector3 a = new Vector3(waterline[edge * SHORE_SEGMENTS]), b = new Vector3(waterline[next * SHORE_SEGMENTS]);
        Vector3 startTangent = new Vector3(along), endTangent = new Vector3(along);
        if (joinA[edge] != null) { turn(a, startTangent, corners[edge], joinA[edge], lip[previous] > 0); }
        if (joinB[edge] != null) { turn(b, endTangent, corners[next], joinB[edge], lip[next] > 0); }
        a.z = waterline[edge * SHORE_SEGMENTS].z;
        b.z = waterline[next * SHORE_SEGMENTS].z;
        // Only a fall standing free between two banks breaks unevenly; falls that run on round a corner stay smooth.
        float jut = joinA[edge] == null && joinB[edge] == null ? LIP_JUT * BoardGeometry.hexScale() : 0;
        return new Crest(a, b, startTangent, endTangent, new Vector3(corners[edge]), edgeOutward(edge), jut,
              BoardGeometry.waterZ(tile));
    }

    /**
     * Where a crest turns a corner into the next fall, given its normal there: at the corner itself on a prow and out
     * over the pool below in a valley, running square to the normal.
     */
    private static void turn(Vector3 point, Vector3 tangent, Vector3 corner, Vector3 normal, boolean prow) {
        point.set(corner).mulAdd(normal, prow ? 0 : VALLEY * BoardGeometry.hexScale());
        tangent.set(-normal.y, normal.x, 0);
    }

    /**
     * The bed as one sculpted basin. It descends from the waterline, or from an open mouth's profile, along rays toward
     * the hex centre, where a plateau keeps the unit anchor at the game depth. Mouth profiles and corners are pure
     * functions of the hexes sharing them, so neighbouring beds meet without a step. Under each bank the bed drops as
     * the land beside it and the water's depth suggest: steeply under higher ground and in deep water, gently off land
     * at the water's own level, where shallows reach out. Ordinary pools use one bank and a flat floor; shallow bars
     * and descending streams retain the rounded profile between rim and plateau.
     * {@code outer} is the waterline with each falling mouth moved out to its crest, which the ledge beneath the fall
     * reaches; rise, per edge, the levels the land beside a bank rises above this hex; plunge, the mouths a fall pours
     * in over, where the water scours a deeper pool.
     */
    private void basin(BoardScene scene, Vector3[] waterline, Vector3[] outer, Vector3[] liquid, float[] shore, float[] rise,
          boolean[] plunge) {
        if (canal) {
            canalBed(outer);
            return;
        }
        int count = waterline.length;
        float surface = BoardGeometry.waterZ(tile), full = depth(tile), ledge = LIP_DEPTH * BoardGeometry.hexScale();
        boolean[] spills = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            spills[edge] = crests[edge] != null;
        }
        // Depth at each edge's first point: none where a bank meets it, the ledge beside a fall, else the depth shared
        // by the corner's hexes.
        float[] ends = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6;
            // Past a separated side's free end all three hexes meet in open water, at the depth the corner's mouths have.
            boolean tip = (shore[edge] > 0) != (shore[previous] > 0) && stripTip(scene, edge, shore[previous] == 0);
            ends[edge] = (shore[edge] > 0 || shore[previous] > 0) && !relief.wetCliffCorner(edge) && !tip ? 0
                  : spills[edge] || spills[previous] ? ledge : cornerDepth(scene, edge);
        }
        float[] steep = new float[6], scour = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            scour[edge] = plunge[edge] ? 1 : 0;
            float land = shore[edge] == 0 ? 0 : rise[edge] > 0 ? .4f + .3f * rise[edge] : rise[edge] == 0 ? -.6f : 0;
            // Under a slope that runs on down to the bed the bed takes its full depth near the slope's foot.
            steep[edge] = relief.slope(edge) ? 2
                  : Math.clamp(land + .25f * (tile.waterDepth() - 1), -.7f, 1);
        }
        float[] rim = new float[count];
        for (int i = 0; i < count; i++) {
            int edge = i / SHORE_SEGMENTS;
            if (i % SHORE_SEGMENTS == 0) {
                rim[i] = ends[edge];
            } else if (shore[edge] == 0 || relief.wetCliff(edge)) {
                // Beds of one level meet at their mean depth; a fall pours over its ledge into the pool below, whose
                // bed keeps its own depth.
                BoardScene.Tile other = neighbor(scene, edge);
                float middle = spills[edge] ? ledge
                      : shore[edge] == 0 && other != null && (other.elevation() == tile.elevation() || waterSlope(tile, other))
                            ? (full + depth(other)) / 2 : full;
                int next = (edge + 1) % 6;
                rim[i] = mouthDepth(waterline[i], waterline[edge * SHORE_SEGMENTS], ends[edge],
                      waterline[next * SHORE_SEGMENTS], ends[next], middle);
            } else {
                // A bank keeps no depth, but on the open stretch of a separated side the bed rises from its free end's.
                rim[i] = Math.max(ends[edge], ends[(edge + 1) % 6]) * relief.stripDepth(waterline[i].x, waterline[i].y);
            }
        }
        Vector3[] ring = new Vector3[count];
        boolean shallow = tile.waterDepth() == 0 && tile.detailedGround() && !tile.frozen() && !tile.liquid().molten();
        float[] bank = new float[count];
        for (int i = 0; i < count; i++) {
            int edge = i / SHORE_SEGMENTS;
            float t = i % SHORE_SEGMENTS / (float) SHORE_SEGMENTS;
            bank[i] = shore[edge] == 0 || relief.wetCliff(edge) ? 0 : Math.min(shore[(edge + 5) % 6] > 0 ? 1 : BoardRelief.smooth(2 * t),
                  shore[(edge + 1) % 6] > 0 ? 1 : BoardRelief.smooth(2 * (1 - t)));
            float bed = shallow ? shallowBed(rim[i], outer[i].x, outer[i].y, bank[i]) : rim[i];
            ring[i] = new Vector3(outer[i].x, outer[i].y, outer[i].z - bed);
        }
        bedOutline = ring;
        Vector3 anchor = new Vector3(center.x, center.y, surface - full);
        if (tile.liquid().molten() && !tile.frozen()) {
            lavaBasin(ring, liquid, full);
            return;
        }
        boolean levelBed = !shallow && !gradedWater;
        for (int i = 0; levelBed && i < count; i++) {
            levelBed = shore[i / SHORE_SEGMENTS] == 0 && Math.abs(ring[i].z - anchor.z) < .0001f;
        }
        // Ordinary pools need a level floor and a bank joining it to the shared contour. Keep detail in that
        // contour, rather than repeating all its samples in concentric rings across an almost flat seabed.
        if (!shallow && !gradedWater && !tile.liquid().molten() && sparseBed(ring, anchor, levelBed)) { return; }
        if (levelBed) {
            for (int i = 0; i < count; i++) {
                triangle(anchor, ring[i], ring[(i + 1) % count], Finish.BED, i / SHORE_SEGMENTS);
            }
            return;
        }
        // The optical-depth field samples this bed across chunk boundaries, independently of their render LoDs.
        int rings = 2;
        // Preserve exposed bars and the varying water-height attribute beneath descending streams.
        boolean simplify = !shallow && !gradedWater && !tile.frozen() && !tile.liquid().molten() && lod != TerrainLod.FULL;
        Vector3[][] bedRings = new Vector3[rings + 1][];
        int[] bandEnds = new int[rings + 1];
        bedRings[0] = ring;
        bandEnds[0] = faces.size();
        for (int k = 1; k <= rings; k++) {
            float x = k / (float) rings, scale = 1 - PLATEAU * x;
            // Falling from the rim with some slope, steepest halfway, and easing into the plateau.
            float descent = .25f * x + .75f * x * x * (3 - 2 * x);
            Vector3[] inner = new Vector3[count];
            for (int i = 0; i < count; i++) {
                // Each ray drops as the banks beside it do.
                float s = alongBanks(steep, i);
                float d = s >= 0 ? 1 - (float) Math.pow(1 - descent, 1 + 2 * s)
                      : (float) Math.pow(descent, 1 - 1.5f * s);
                float px = center.x + (outer[i].x - center.x) * scale, py = center.y + (outer[i].y - center.y) * scale;
                // Bars and pools between the rim and the plateau, which stays at the game depth.
                float bed = (rim[i] + (full - rim[i]) * d)
                      * (1 + (.25f * meander(px, py, 5.1f) + .45f * alongBanks(scour, i)) * 4 * x * (1 - x));
                if (shallow) {
                    // Bars can merge into a bank instead of dipping at the old waterline. Open mouths and the
                    // centre plateau keep their shared bed depth; neither acquires a step or a raised unit anchor.
                    bed = shallowBed(bed, px, py, BoardRelief.lerp(4 * x * (1 - x), 1 - x, bank[i]));
                }
                float level = gradedWater ? waterGrade(px, py, outer[i], x) : surface;
                inner[i] = new Vector3(px, py, level - bed);
            }
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                quad(ring[i], ring[j], inner[j], inner[i], Finish.BED, i / SHORE_SEGMENTS);
            }
            ring = inner;
            bedRings[k] = ring;
            bandEnds[k] = faces.size();
            if (k == 1) { interiorFrom = faces.size(); }
        }
        for (int i = 0; i < count; i++) {
            triangle(anchor, ring[i], ring[(i + 1) % count], Finish.BED, i / SHORE_SEGMENTS);
        }
        interiorTo = faces.size();
        if (simplify) { simplifyBed(bedRings, bandEnds, anchor); }
    }

    /**
     * A canal's lined bed: from its waterline the lining falls to a level floor at the hex's depth, along the same bearings
     * from the centre as the waterline. Across a mouth the floor runs out to the edge between the linings' feet, which
     * the next canal's meet. Both are cast concrete, the hex's ground ({@link Finish#BANK}), not a bed's sediment.
     */
    private void canalBed(Vector3[] outer) {
        int count = outer.length;
        float surface = BoardGeometry.waterZ(tile), floor = surface - depth(tile);
        float run = CANAL_HALF * BoardGeometry.hexScale() - canalFloor();
        Vector3[] ring = new Vector3[count], foot = new Vector3[count];
        for (int i = 0; i < count; i++) {
            int edge = i / SHORE_SEGMENTS, segment = i % SHORE_SEGMENTS;
            boolean mouth = (openMouths & 1 << edge) != 0, mouthBefore = (openMouths & 1 << (edge + 5) % 6) != 0;
            Vector3 w = outer[i];
            if (mouth && segment > 0) {
                ring[i] = foot[i] = new Vector3(w.x, w.y, floor);
                continue;
            }
            ring[i] = new Vector3(w);
            if (segment == 0 && mouth != mouthBefore) {
                // A mouth's end: its lining runs down along the edge to the floor's end there.
                int at = mouth ? edge : (edge + 5) % 6;
                foot[i] = canalMouth(at, edge, canalFloor() / BoardGeometry.hexScale());
            } else {
                // Along the bearing from the centre, to where the channel is the lining's run narrower.
                float low = 0, high = 1;
                for (int k = 0; k < 24; k++) {
                    float t = (low + high) / 2;
                    if (canalField(center.x + (w.x - center.x) * t, center.y + (w.y - center.y) * t) > run) { low = t; } else { high = t; }
                }
                foot[i] = new Vector3(center.x + (w.x - center.x) * low, center.y + (w.y - center.y) * low, 0);
            }
            foot[i].z = floor;
        }
        bedOutline = ring;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            quad(ring[i], ring[j], foot[j], foot[i], Finish.BANK, i / SHORE_SEGMENTS);
        }
        Vector3 anchor = new Vector3(center.x, center.y, floor);
        for (int i = 0; i < count; i++) {
            triangle(anchor, foot[i], foot[(i + 1) % count], Finish.BANK, i / SHORE_SEGMENTS);
        }
    }

    /** A flat floor and, where needed, one sloping bank; curved shore and depth contacts remain exact. */
    private boolean sparseBed(Vector3[] boundary, Vector3 anchor, boolean level) {
        List<Face> sparse = basinFaces(boundary, anchor, level, boundarySamples(boundary, boundary), 6, 1);
        if (sparse == null) { return false; }
        if (!level) { interiorFrom = faces.size() + sparse.size() - 6; }
        faces.addAll(sparse);
        if (!level) { interiorTo = faces.size(); }
        return true;
    }

    /** Opaque lava and its shallow bed share every interior vertex and diagonal, so the bed cannot pierce the melt. */
    private void lavaBasin(Vector3[] boundary, Vector3[] liquid, float depth) {
        List<Integer> perimeter = boundarySamples(boundary, liquid);
        Vector3 anchor = new Vector3(center.x, center.y, BoardGeometry.waterZ(tile));
        int innerSamples = 12;
        float inward = 2 / 3f;
        List<Face> surface = basinFaces(liquid, anchor, !gradedWater, perimeter, innerSamples, inward);
        boolean plateau = gradedWater && surface != null;
        if (plateau) {
            // One shoulder carries the grade into the centre, instead of long fans ending at a flat plateau.
            for (int i = 0; i < innerSamples; i++) {
                Vector3 point = surface.get(surface.size() - innerSamples + i).b();
                point.z = waterGrade(point.x, point.y, liquid[i * liquid.length / innerSamples], inward);
            }
        }
        if (surface == null) {
            // A narrow concave mouth may reach inside the plateau. Its centre fan needs no inner ring.
            surface = new ArrayList<>();
            for (int i = 0; i < perimeter.size(); i++) {
                surface.add(new Face(anchor, liquid[perimeter.get(i)],
                      liquid[perimeter.get((i + 1) % perimeter.size())], Finish.TOP));
            }
        }
        Map<Vector3, Vector3> bed = new HashMap<>();
        for (int index : perimeter) {
            Vector3 p = liquid[index];
            bed.put(p, new Vector3(p.x, p.y, boundary[index].z));
        }
        // Only a falling lip pulls the drawn surface inward. Its exposed collar keeps the original crest contact.
        for (int i = 0; i < perimeter.size(); i++) {
            int at = perimeter.get(i), next = perimeter.get((i + 1) % perimeter.size());
            // At a lip's end the two contours meet along the same line; those collapsed pieces have no bed area.
            for (Face collar : List.of(
                  new Face(boundary[at], boundary[next], bed.get(liquid[next]), Finish.BED, at / SHORE_SEGMENTS),
                  new Face(boundary[at], bed.get(liquid[next]), bed.get(liquid[at]), Finish.BED, at / SHORE_SEGMENTS))) {
                if (upward(collar) > 0) { faces.add(collar); }
            }
        }
        if (plateau) { interiorFrom = faces.size() + surface.size() - innerSamples; }
        for (Face face : surface) {
            waterFaces.add(new Face(face.a(), face.b(), face.c(), Finish.TOP));
            triangle(bed.computeIfAbsent(face.a(), p -> new Vector3(p.x, p.y, p.z - depth)),
                  bed.computeIfAbsent(face.b(), p -> new Vector3(p.x, p.y, p.z - depth)),
                  bed.computeIfAbsent(face.c(), p -> new Vector3(p.x, p.y, p.z - depth)), Finish.BED, face.landEdge());
        }
        if (plateau) { interiorTo = faces.size(); }
    }

    /** Retain bends in either shared contour; omit only samples lying on the same straight 3D segment. */
    private List<Integer> boundarySamples(Vector3[] boundary, Vector3[] paired) {
        List<Integer> perimeter = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            int first = edge * SHORE_SEGMENTS;
            // The liquid rim is refined against the final cliff later; that pass needs its individual segments.
            boolean straight = (paired == boundary || !relief.wetCliff(edge)) && straightEdge(boundary, first)
                  && (paired == boundary || straightEdge(paired, first));
            for (int i = 0; i < SHORE_SEGMENTS; i += straight ? SHORE_SEGMENTS : 1) { perimeter.add(first + i); }
        }
        return perimeter;
    }

    private static boolean straightEdge(Vector3[] boundary, int first) {
        Vector3 a = boundary[first], b = boundary[(first + SHORE_SEGMENTS) % boundary.length];
        Vector3 direction = new Vector3(b).sub(a);
        float length2 = direction.len2();
        if (length2 == 0) { return false; }
        for (int i = 1; i < SHORE_SEGMENTS; i++) {
            Vector3 p = boundary[first + i];
            float t = new Vector3(p).sub(a).dot(direction) / length2;
            float tolerance = Math.max(.0001f * BoardGeometry.hexScale(),
                  2 * Math.max(Math.ulp(p.x), Math.max(Math.ulp(p.y), Math.ulp(p.z))));
            if (t < 0 || t > 1 || p.dst2(new Vector3(a).mulAdd(direction, t)) > tolerance * tolerance) { return false; }
        }
        return true;
    }

    /** A perimeter joined to one inner contour, or just a centre fan when the surface is already level. */
    private static List<Face> basinFaces(Vector3[] boundary, Vector3 anchor, boolean level, List<Integer> perimeter,
          int innerSamples, float inward) {
        List<Face> sparse = new ArrayList<>();
        if (level) {
            for (int i = 0; i < perimeter.size(); i++) {
                int at = perimeter.get(i), next = perimeter.get((i + 1) % perimeter.size());
                sparse.add(new Face(anchor, boundary[at], boundary[next], Finish.BED, at / SHORE_SEGMENTS));
            }
        } else {
            Vector3[] floor = new Vector3[innerSamples];
            float scale = 1 - PLATEAU * inward;
            int stride = boundary.length / innerSamples;
            for (int i = 0; i < innerSamples; i++) {
                Vector3 rim = boundary[i * stride];
                floor[i] = new Vector3(anchor.x + (rim.x - anchor.x) * scale,
                      anchor.y + (rim.y - anchor.y) * scale, anchor.z);
            }
            int outer = 0, inner = 0;
            while (outer < perimeter.size() || inner < floor.length) {
                int at = perimeter.get(outer % perimeter.size());
                int next = outer + 1 < perimeter.size() ? perimeter.get(outer + 1) : boundary.length;
                // A flowing shoulder joins the nearer inner point; a one-sided fan can form a steep angular lip.
                float through = (inner + (inward < 1 ? .5f : 1)) * stride;
                if (outer < perimeter.size() && (inner == floor.length || next <= through)) {
                    sparse.add(new Face(boundary[at], boundary[next % boundary.length], floor[inner % innerSamples],
                          Finish.BED, at / SHORE_SEGMENTS));
                    outer++;
                } else {
                    sparse.add(new Face(boundary[at], floor[(inner + 1) % innerSamples], floor[inner % innerSamples],
                          Finish.BED, inner * 6 / innerSamples));
                    inner++;
                }
            }
            for (int i = 0; i < innerSamples; i++) {
                sparse.add(new Face(anchor, floor[i], floor[(i + 1) % innerSamples], Finish.BED,
                      i * 6 / innerSamples));
            }
        }
        // A deeply concave mouth can reach inside the proposed floor. Keep its radial construction in that case.
        return sparse.stream().anyMatch(face -> upward(face) <= 0) ? null : sparse;
    }

    /** Keep only rings needed to describe the bed's profile, with every boundary sample and radial seam intact. */
    private void simplifyBed(Vector3[][] rings, int[] bandEnds, Vector3 anchor) {
        // Less than a fifth of a pixel at the default refinement thresholds; independent of board dimensions.
        float tolerance = BoardGeometry.width() / switch (lod) {
            case MEDIUM -> 512;
            case COARSE -> 128;
            default -> 32;
        };
        List<Face> simplified = new ArrayList<>();
        for (int outer = 0; outer < rings.length;) {
            int inner = rings.length;
            while (inner > outer + 1 && !bedBandFits(rings, outer, inner, anchor, tolerance)) { inner--; }
            if (inner == outer + 1 && inner < rings.length) {
                simplified.addAll(faces.subList(bandEnds[outer], bandEnds[inner]));
            } else {
                for (int i = 0; i < rings[outer].length; i++) {
                    int j = (i + 1) % rings[outer].length;
                    Vector3 a = rings[outer][i], b = rings[outer][j];
                    Vector3 c = inner == rings.length ? anchor : rings[inner][j];
                    simplified.add(new Face(a, b, c, Finish.BED, i / SHORE_SEGMENTS));
                    if (inner < rings.length) {
                        simplified.add(new Face(a, c, rings[inner][i], Finish.BED, i / SHORE_SEGMENTS));
                    }
                }
            }
            outer = inner;
        }
        if (simplified.size() < interiorTo - bandEnds[0]) { renderBed = simplified; }
    }

    /**
     * Each old radial strip lies inside the proposed quad (or centre fan). Bound both proposed triangle planes
     * over every old vertex, even outside the triangle: linear interpolation then also bounds the error where
     * their diagonals cross. This conservative check avoids accepting a saddle just because its corners match.
     */
    private static boolean bedBandFits(Vector3[][] rings, int outer, int inner, Vector3 anchor, float tolerance) {
        Vector3 first = new Vector3(), second = new Vector3();
        for (int i = 0; i < rings[outer].length; i++) {
            int j = (i + 1) % rings[outer].length;
            Vector3 a = rings[outer][i], b = rings[outer][j];
            Vector3 c = inner == rings.length ? anchor : rings[inner][j];
            first.set(b).sub(a).crs(second.set(c).sub(a));
            if (first.z <= 0) { return false; }
            first.scl(1 / first.z);
            if (inner < rings.length) {
                Vector3 d = rings[inner][i];
                second.set(c).sub(a).crs(d.x - a.x, d.y - a.y, d.z - a.z);
                if (second.z <= 0) { return false; }
                second.scl(1 / second.z);
            } else { second.set(first); }
            for (int k = outer; k <= Math.min(inner, rings.length - 1); k++) {
                for (int side = 0; side < 2; side++) {
                    Vector3 p = rings[k][side == 0 ? i : j];
                    float dx = p.x - a.x, dy = p.y - a.y, dz = p.z - a.z;
                    if (Math.abs(first.x * dx + first.y * dy + dz) > tolerance
                          || Math.abs(second.x * dx + second.y * dy + dz) > tolerance) { return false; }
                }
            }
        }
        return true;
    }

    /**
     * A deep, level pool's bed is wholly submerged inside the same contour as its water. Exposed shallow bars,
     * descending streams, pulled-back waterfall lips and refined cliff contacts still need coverage clipping.
     */
    boolean bedUnderLevelWater() {
        return tile.waterDepth() > 0 && !tile.frozen() && !tile.liquid().molten() && !gradedWater
              && waterfalls.isEmpty() && waterContacts.isEmpty();
    }

    /** The caller owns the supplied bed list; canonical faces remain available for normal and depth sampling. */
    List<Face> renderBed(List<Face> canonical) {
        if (renderBed != null) { return new ArrayList<>(renderBed); }
        if (tile.liquid().molten() && !tile.frozen()) {
            // Lava is opaque. Remove only triangles wholly covered by one drawn lava triangle, including shared
            // shore vertices at equal height; keep crest geometry outside it. Picking retains the sparse bed.
            List<Face> visible = new ArrayList<>(canonical);
            visible.removeIf(face -> {
                for (Face top : waterFaces) {
                    if (top.height(face.a().x, face.a().y, 0) >= face.a().z
                          && top.height(face.b().x, face.b().y, 0) >= face.b().z
                          && top.height(face.c().x, face.c().y, 0) >= face.c().z) { return true; }
                }
                return false;
            });
            return visible;
        }
        return canonical;
    }

    private static float shallowBed(float bed, float x, float y, float weight) {
        float bar = BoardRelief.smooth((meander(x, y, 9.7f) + .15f) / .5f);
        return Math.max(bed - 2.5f * BoardGeometry.hexScale() * bar * weight, -.6f * BoardGeometry.hexScale());
    }

    /** A value given per edge at waterline point {@code index}, blended over each corner so no crease shows there. */
    private static float alongBanks(float[] perEdge, int index) {
        int edge = index / SHORE_SEGMENTS;
        float f = index % SHORE_SEGMENTS / (float) SHORE_SEGMENTS, own = perEdge[edge];
        return f < .5f ? BoardRelief.lerp((perEdge[(edge + 5) % 6] + own) / 2, own, 2 * f)
              : BoardRelief.lerp(own, (own + perEdge[(edge + 1) % 6]) / 2, 2 * f - 1);
    }

    /** Smooth world noise, roughly in [-1, 1], for banks and beds: swells of about 12 m with 4 m detail. */
    private static float meander(float x, float y, float seed) {
        float m = BoardRelief.metres(1);
        return .7f * BoardRelief.gradient(x / (12 * m) + seed, y / (12 * m) - seed)
              + .3f * BoardRelief.gradient(x / (4 * m) - seed, y / (4 * m) + seed);
    }

    private BoardScene.Tile neighbor(BoardScene scene, int edge) {
        return scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
    }

    /** Water depth of a liquid tile, from its surface to its bed. */
    private static float depth(BoardScene.Tile tile) {
        return BoardGeometry.waterZ(tile) - BoardGeometry.groundZ(tile);
    }

    /**
     * Mean depth of the connected water around the corner where edge {@code index} starts. Ice does not change a bed:
     * frozen and open water at one level join alike, so every bed round the corner meets at the same depth. Molten
     * beds join only at their own level.
     */
    private float cornerDepth(BoardScene scene, int index) {
        boolean molten = tile.liquid().molten();
        List<BoardScene.Tile> joined = corner(scene, index, (a, b) -> a.joins(b)
              && (a.elevation() == b.elevation() || !molten && waterSlope(a, b)));
        float sum = 0;
        int low = tile.elevation(), high = low;
        for (BoardScene.Tile other : joined) {
            sum += depth(other);
            low = Math.min(low, other.elevation());
            high = Math.max(high, other.elevation());
        }
        if (high - low >= 3) { return Math.min(sum / joined.size(), LIP_DEPTH * BoardGeometry.hexScale()); }
        return sum / joined.size();
    }

    /**
     * An open mouth's bed at p: its middle depth, easing over a third of the mouth to the depth at either end. The
     * profile is symmetric in its ends, so both hexes of the mouth evaluate the same points to the same heights.
     */
    private static float mouthDepth(Vector3 p, Vector3 a, float atA, Vector3 b, float atB, float middle) {
        float reach = Math.max((float) Math.hypot(b.x - a.x, b.y - a.y) / 3, .001f);
        return middle + (atA - middle) * ease((float) Math.hypot(p.x - a.x, p.y - a.y) / reach)
              + (atB - middle) * ease((float) Math.hypot(p.x - b.x, p.y - b.y) / reach);
    }

    /** 1 at 0, easing smoothly down to 0 at 1 and beyond. */
    private static float ease(float x) {
        float t = Math.clamp(x, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * The crest's normal where falling edge {@code edge} meets edge {@code side}, when a fall of the same drop carries
     * on there; null where the end is free. On a prow, where this pool falls on over {@code side} into connected water
     * at the same level, it bisects the two edges. In a valley, where the neighbouring pool across {@code side}, at
     * this pool's level, falls into the same lower pool, it runs on along the edge the two pools share: both pools see
     * that edge alike, so their crests meet exactly on it and neither passes inside the other's hex.
     */
    private Vector3 join(BoardScene scene, float[] shore, float[] lip, int edge, int side) {
        BoardScene.Tile below = neighbor(scene, edge), beside = neighbor(scene, side);
        // Falls off the board's edge over two edges of this pool run on round the corner between them, as a prow.
        if (below == null || beside == null) {
            return below == null && beside == null && lip[side] > 0 ? edgeOutward(edge).add(edgeOutward(side)).nor()
                  : null;
        }
        if (lip[side] > 0) {
            return beside.elevation() == below.elevation() && beside.joins(below)
                  ? edgeOutward(edge).add(edgeOutward(side)).nor() : null;
        }
        if (shore[side] != 0 || beside.elevation() != tile.elevation() || beside.frozen() != tile.frozen()
              || !beside.joins(below)) { return null; }
        boolean before = side == (edge + 5) % 6;
        Vector3 corner = before ? corners[edge] : corners[side];
        return new Vector3(corner).sub(before ? corners[side] : corners[(side + 1) % 6]).nor();
    }

    /** Outward normal of one edge: away from this hex. */
    private Vector3 edgeOutward(int edge) {
        return edgeInward(edge).scl(-1);
    }

    /** The crest a fall of this pool pours over. */
    Crest crest(Side fall) {
        return crests[fall.edge()];
    }

    /** A waterfall can land beside a sloping stream; sample that same receiving surface for its sheet and spray. */
    float receivingHeight(Side fall, float x, float y) {
        BoardScene.Tile other = neighbor(scene, fall.edge());
        if (other == null) { return fall.lowA(); }
        if (other.frozen()) {
            return tile.frozen() || tile.liquid().molten() ? BoardGeometry.surfaceZ(other) : BoardGeometry.waterZ(other);
        }
        if (receivingWater[fall.edge()] == null) {
            receivingWater[fall.edge()] = new BoardSurface(scene, other, false, lod);
        }
        return receivingWater[fall.edge()].waterHeight(x, y);
    }

    /** Whether the end of a fall carries on into another fall's sheet, so that end is not a free side. */
    boolean fallJoins(Side fall, boolean atA) {
        return (atA ? joinA : joinB)[fall.edge()] != null;
    }

    /** Radius of the curve where a fall leaves the upper surface, bounded by half the drop and one hex width. */
    static float fallLip(float surface, float bottom) {
        return Math.min(Math.max(0, surface - bottom) * FALL_LIP_DROP, FALL_LIP_WIDTH * BoardGeometry.width());
    }

    /** Inward normal of one edge: the direction a falling mouth pulls its water back from the shared edge. */
    private Vector3 edgeInward(int edge) {
        return new Vector3(corners[(edge + 1) % 6]).sub(corners[edge]).crs(Vector3.Z).nor().scl(-1);
    }

    /**
     * Pull the water surface back from each crest along its normal, leaving the lip's room for the sheet to fill. A
     * corner where two falls meet recedes along the normal both crests share there, which is where both pulled-back
     * edges and both sheets meet, whether the other fall is this pool's or the neighbouring one's.
     */
    private Vector3[] pulledBack(Vector3[] crestLine, float[] lip) {
        Vector3[] result = crestLine.clone();
        for (int index = 0; index < crestLine.length; index++) {
            int edge = index / SHORE_SEGMENTS, previous = (edge + 5) % 6;
            // An anchor also ends the previous edge, whose crest ends there too.
            boolean anchor = index % SHORE_SEGMENTS == 0;
            Crest crest = crests[edge];
            int lipEdge = edge;
            float s = index % SHORE_SEGMENTS / (float) SHORE_SEGMENTS;
            if (crest == null && anchor && crests[previous] != null) {
                crest = crests[previous];
                lipEdge = previous;
                s = 1;
            }
            if (crest != null) {
                result[index] = new Vector3(crestLine[index])
                      .mulAdd(crest.normal(s), -lipWidth(lipEdge, s,
                            anchor ? Math.max(lip[edge], lip[previous]) : lip[edge]));
            }
        }
        return result;
    }

    /** At a free bank the lip narrows to its anchor, so pulling it back cannot cross the adjoining shoreline. */
    float lipWidth(int edge, float s, float width) {
        if (joinA[edge] == null && joinB[edge] == null) {
            width = Math.min(width, .15f * crests[edge].chord());
        }
        return width * (joinA[edge] == null ? BoardRelief.smooth(s / .2f) : 1)
              * (joinB[edge] == null ? BoardRelief.smooth((1 - s) / .2f) : 1);
    }

    private Vector3 shoreLip(Vector3 waterline, Vector3 boundary, int index) {
        // The bank's width is horizontal, just as BoardRelief.stub measures it. A descending stream can put its
        // waterline far below the corner; counting that height shortened the rim used to close its side walls.
        float distance = (float) Math.hypot(boundary.x - waterline.x, boundary.y - waterline.y);
        float fraction = Math.min(1, BoardRelief.SHORE_LIP * BoardGeometry.hexScale() / Math.max(distance, .0001f));
        Vector3 lip = new Vector3(waterline).lerp(boundary, fraction);
        boolean graded = !tile.liquid().molten();
        if (graded && index % SHORE_SEGMENTS == 0) {
            int edge = index / SHORE_SEGMENTS, before = (edge + 5) % 6;
            graded = (!mouth(edge) || relief.gradedBankMouth(edge))
                  && (!mouth(before) || relief.gradedBankMouth(before));
        }
        // The wall snapshot precedes sculpting: its mouth ends must follow the same grade as the rendered bank.
        lip.z = graded ? center.z - (center.z - waterline.z) * (1 - fraction) : center.z;
        return lip;
    }

    /**
     * The waterline: where this hex's shore (see {@link #wet}) turns from water to land, inside its outline as the
     * shore moves its corners. Open mouths run straight between their ends, which both hexes of a mouth place alike
     * (see {@link #mouthEnd}); along the banks the line lies where the shore turns on the way out from the hex centre
     * toward the outline, so it stays star-shaped round the centre and the bed's rings never fold.
     */
    private Vector3[] shoreline(BoardScene scene, float[] inset, float[] rise, boolean[] plunge, float z) {
        float scale = BoardGeometry.hexScale();
        shoreCorners = new Vector3[6];
        for (int k = 0; k < 6; k++) { shoreCorners[k] = moved(k); }
        // Keep a bank beside land at the water's level, a beach beside walls and lower land, and the slope's reach
        // plus a wet margin beside a slope up from the water, which runs on under it to the bed. Keep clear of the
        // corners the land rounds into the hex too.
        banks = new ArrayList<>(12);
        for (int k = 0; k < 6; k++) {
            Vector3 a = shoreCorners[k], b = shoreCorners[(k + 1) % 6];
            // A separated side keeps no bank of its own: its land is the strip in the shore field, which stops short of a free end.
            BoardScene.Tile across = neighbor(scene, k);
            if (inset[k] > 0 && (across == null || !across.liquid().connects(tile.liquid()))) {
                float width = relief.slope(k) || rise[k] != 0 ? inset[k] : SHORE_BANK * scale;
                Vector3 inward = new Vector3(b).sub(a).crs(Vector3.Z).nor().scl(-1);
                banks.add(new Bank(a.x, a.y, inward.x, inward.y, width));
            }
            float in = relief.cornerInset(k);
            if (in > 0) {
                Vector3 corner = shoreCorners[k];
                Vector3 inward = new Vector3(center.x - corner.x, center.y - corner.y, 0).nor();
                banks.add(new Bank(corner.x, corner.y, inward.x, inward.y, in + HUG * scale));
            }
        }
        Vector3[] anchors = new Vector3[6];
        for (int k = 0; k < 6; k++) {
            int before = (k + 5) % 6, next = (k + 1) % 6;
            boolean in = inset[before] == 0, out = inset[k] == 0;
            if (canal && in != out) {
                // A canal's mouth spans its channel's width about the edge's middle.
                anchors[k] = canalMouth(in ? before : k, k, CANAL_HALF);
            } else if (canal && !in && !out) {
                anchors[k] = along(shoreCorners[k], plunge);
            } else if (relief.wetCliffCorner(k) || !in && !out && (relief.wetCliff(before) || relief.wetCliff(k))) {
                // A beach ending against a cliff tapers to the shared rock seam, not to a freestanding sand shelf.
                anchors[k] = relief.seam(k, k, 0);
            } else if (BoardConcrete.concreteBank(scene, tile, before) || BoardConcrete.concreteBank(scene, tile, k)) {
                // A poured edge meets the water directly. No beach setback, rounded corner or natural shore field.
                anchors[k] = new Vector3(shoreCorners[k]);
            } else if (in && out || in != out && stripTip(scene, k, in)) {
                openEnds |= !in || !out;
                anchors[k] = new Vector3(shoreCorners[k]);
            } else if (in || out) {
                int other = in ? before : next, mouth = in ? before : k;
                float end = mouthEnd(k, other, plunge[mouth]);
                anchors[k] = new Vector3(shoreCorners[k]).lerp(shoreCorners[other], end);
                // Across the mouth, onto the seam the relief lays there, which bends where steps through the corner
                // move it; along it, where the mouth ends.
                Vector3 across = new Vector3(shoreCorners[other]).sub(shoreCorners[k]).crs(Vector3.Z).nor();
                anchors[k].mulAdd(across, new Vector3(relief.seam(mouth, k, end)).sub(anchors[k]).dot(across));
            } else {
                // Between two banks, where the shore turns on the way out to the corner; the banks' own limits stop it
                // short of the corner where the water would run on past them.
                anchors[k] = along(shoreCorners[k], plunge);
            }
            anchors[k].z = z;
        }
        Vector3[] result = new Vector3[6 * SHORE_SEGMENTS];
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6;
            Vector3[] bank = inset[edge] == 0 || !canal && (relief.wetCliff(edge) || BoardConcrete.concreteBank(scene, tile, edge))
                  ? null : bank(anchors[edge], anchors[next], plunge, edge);
            // Across a canal's mouth its floor runs out to the edge between its linings, which meet the next canal's.
            boolean lined = canal && bank == null && (anchors[edge].dst2(shoreCorners[edge]) > 1e-6f
                  || anchors[next].dst2(shoreCorners[next]) > 1e-6f);
            Vector3 cliffA = relief.wetCliff(edge) ? relief.seam(edge, edge, 0) : null;
            Vector3 cliffB = cliffA == null ? null : relief.seam(edge, next, 0);
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                Vector3 point = bank != null ? bank[segment] : !lined || segment == 0 ? mouthPoint(anchors[edge], anchors[next], segment)
                      : canalMouthPoint(edge, segment);
                if (cliffA != null) {
                    // Follow the rock's curve, retaining the common mouth ends where a cliff changes into a bank.
                    point.add(relief.seam(edge, edge, segment / (float) SHORE_SEGMENTS)
                          .sub(mouthPoint(cliffA, cliffB, segment)));
                }
                point.z = z;
                result[edge * SHORE_SEGMENTS + segment] = point;
            }
        }
        return result;
    }

    /**
     * A bank's waterline from {@code from} to the next bank's or mouth's end {@code to}, as {@link #SHORE_SEGMENTS}
     * points spaced evenly along it, the first at {@code from}: traced out along bearings turning evenly round the
     * centre, so it follows the shore however far past the corners its ends lie, then spaced by length, so it keeps its
     * points where the shore runs out nearly along those bearings, as beside a mouth that bends into the hex.
     */
    private Vector3[] bank(Vector3 from, Vector3 to, boolean[] plunge, int edge) {
        int fine = SHORE_SEGMENTS;
        float start = bearing(from), turn = bearing(to) - start;
        if (turn <= 0) { turn += 2 * (float) Math.PI; }
        Vector3[] trace = new Vector3[fine + 1];
        float[] length = new float[fine + 1];
        trace[0] = from;
        trace[fine] = to;
        Vector3 rawFrom = along(outlinePoint(start), plunge), rawTo = along(outlinePoint(start + turn), plunge);
        float fromOffset = radius(from) - radius(rawFrom), toOffset = radius(to) - radius(rawTo);
        Vector3 fromNormal = bankNormal(from, (edge + 5) % 6), toNormal = bankNormal(to, (edge + 1) % 6);
        boolean fromCliff = relief.wetCliff((edge + 5) % 6), toCliff = relief.wetCliff((edge + 1) % 6);
        for (int i = 1; i <= fine; i++) {
            if (i < fine) {
                float t = BoardRelief.smooth(i / (float) fine);
                Vector3 limit = outlinePoint(start + turn * t);
                trace[i] = along(limit, plunge);
                float rawRadius = radius(trace[i]);
                // Meet a mouth with the same tangent from both hexes. Correcting only its radius misses cases where
                // the raw trace follows the hex edge past the fixed opening, then doubles back into a pointed bank.
                float fromCorrection = fromNormal == null ? fromOffset
                      : bankJoin(from, fromNormal, limit, rawRadius, fromOffset);
                float toCorrection = toNormal == null ? toOffset
                      : bankJoin(to, toNormal, limit, rawRadius, toOffset);
                float outerRadius = radius(limit);
                if (fromCliff || toCliff) {
                    // A fixed wall endpoint must not push the natural shore onto the straight hex edge or beneath
                    // the neighbouring grass. Taper within the actual relief boundary, which also builds the top.
                    outerRadius = Math.min(outerRadius, relief.bankRadius(center, limit));
                    float room = Math.max(0, outerRadius - rawRadius);
                    if (fromCliff) { fromCorrection = Math.min(fromCorrection, room); }
                    if (toCliff) { toCorrection = Math.min(toCorrection, room); }
                }
                float correction = fromCorrection * ease(2 * t) + toCorrection * ease(2 * (1 - t));
                float core = Math.min(rawRadius, (tile.waterDepth() > 0 ? 12 : 4) * BoardGeometry.hexScale());
                float radius = Math.min(outerRadius, Math.max(core, rawRadius + correction));
                trace[i].sub(center).scl(radius / rawRadius).add(center);
            }
            length[i] = length[i - 1] + (float) Math.hypot(trace[i].x - trace[i - 1].x, trace[i].y - trace[i - 1].y);
        }
        Vector3[] result = new Vector3[SHORE_SEGMENTS];
        result[0] = new Vector3(from);
        for (int segment = 1, i = 0; segment < SHORE_SEGMENTS; segment++) {
            float wanted = length[fine] * segment / SHORE_SEGMENTS;
            while (i < fine - 1 && length[i + 1] < wanted) { i++; }
            float span = length[i + 1] - length[i], f = span > 0 ? (wanted - length[i]) / span : 0;
            result[segment] = new Vector3(trace[i]).lerp(trace[i + 1], f);
        }
        return result;
    }

    /** A margin-limited opening shares a bank tangent perpendicular to its seam. Natural shores keep their curve. */
    private Vector3 bankNormal(Vector3 point, int edge) {
        // A separated side's free end leaves the side on its own nose: no bank runs in from the mouth there.
        if (!mouth(edge) || shore(point.x, point.y) <= .25f * BoardGeometry.hexScale()
              || openEnds && relief.stripLand(point.x, point.y) == 0) { return null; }
        return new Vector3(shoreCorners[(edge + 1) % 6]).sub(shoreCorners[edge]);
    }

    /** A local tangent blend at a mouth, preserving the bed's non-crossing radial rings. */
    private float bankJoin(Vector3 end, Vector3 normal, Vector3 limit, float rawRadius, float offset) {
        float denominator = (limit.x - center.x) * normal.x + (limit.y - center.y) * normal.y;
        float numerator = (end.x - center.x) * normal.x + (end.y - center.y) * normal.y;
        if (Math.abs(denominator) < 1e-6f || numerator * denominator <= 0) { return offset; }
        float correction = radius(limit) * numerator / denominator - rawRadius;
        float bend = correction - offset;
        float width = SHORE_BANK * BoardGeometry.hexScale();
        // Keep ordinary mouth curves intact, but limit an infinite tangent that would pull the bank into the channel.
        // The excess bends smoothly toward one more bank width, without clipping the shared endpoint tangent.
        float excess = Math.abs(bend) - 2 * width;
        if (excess <= 0) { return correction; }
        return offset + Math.copySign(2 * width + width * (excess / (width + excess)), bend);
    }

    private float radius(Vector3 point) {
        return (float) Math.hypot(point.x - center.x, point.y - center.y);
    }

    /** The bearing of p from the hex centre, in radians. */
    private float bearing(Vector3 p) {
        return (float) Math.atan2(p.y - center.y, p.x - center.x);
    }

    /** Where the way out from the hex centre at {@code bearing} meets the outline as the shore moves its corners. */
    private Vector3 outlinePoint(float bearing) {
        float dx = (float) Math.cos(bearing), dy = (float) Math.sin(bearing), nearest = Float.POSITIVE_INFINITY;
        for (int k = 0; k < 6; k++) {
            Vector3 a = shoreCorners[k], b = shoreCorners[(k + 1) % 6];
            float ex = b.x - a.x, ey = b.y - a.y, denominator = dx * ey - dy * ex;
            if (Math.abs(denominator) < 1e-9f) { continue; }
            float qx = a.x - center.x, qy = a.y - center.y;
            float s = (qx * ey - qy * ex) / denominator, u = (qx * dy - qy * dx) / denominator;
            if (s > 0 && u >= -.00001f && u <= 1.00001f) { nearest = Math.min(nearest, s); }
        }
        return new Vector3(center.x + dx * nearest, center.y + dy * nearest, center.z);
    }

    /**
     * Point {@code segment} of an open mouth from {@code a} to {@code b}, laid out from the end both hexes of the mouth
     * order first, so they compute the bit-identical point.
     */
    /** A canal mouth's points past its first end: on the floor, from one lining's foot to the other's. */
    private Vector3 canalMouthPoint(int edge, int segment) {
        float floor = canalFloor() / BoardGeometry.hexScale();
        Vector3 a = canalMouth(edge, edge, floor), b = canalMouth(edge, (edge + 1) % 6, floor);
        boolean forward = a.x < b.x || a.x == b.x && a.y < b.y;
        float t = (segment - 1) / (float) (SHORE_SEGMENTS - 2);
        return forward ? new Vector3(a).lerp(b, t) : new Vector3(b).lerp(a, 1 - t);
    }

    private static Vector3 mouthPoint(Vector3 a, Vector3 b, int segment) {
        boolean forward = a.x < b.x || a.x == b.x && a.y < b.y;
        return forward ? new Vector3(a).lerp(b, segment / (float) SHORE_SEGMENTS)
              : new Vector3(b).lerp(a, (SHORE_SEGMENTS - segment) / (float) SHORE_SEGMENTS);
    }

    /**
     * Whether the bank beside a mouth at corner k is a separated side with open water round its end ({@code mouthBefore}:
     * the mouth is the edge before k). Its strip of land stops short of the corner, so all three hexes keep their mouths
     * running on to the corner itself.
     */
    private boolean stripTip(BoardScene scene, int k, boolean mouthBefore) {
        BoardScene.Tile bank = neighbor(scene, mouthBefore ? k : (k + 5) % 6);
        BoardScene.Tile mouth = neighbor(scene, mouthBefore ? (k + 5) % 6 : k);
        return bank != null && mouth != null && bank.liquid().connects(tile.liquid()) && bank.joins(mouth);
    }

    /**
     * Where an open mouth beside a bank ends, as the fraction of the mouth from corner k toward corner {@code other},
     * as the shore moves both: where the shore field turns to land on the way out from the mouth's middle toward k,
     * but no nearer k than {@link #mouthLimit} allows. Both hexes of the mouth compute it from the same corners and the
     * same field, so they end the mouth at the same point.
     */
    private float mouthEnd(int k, int other, boolean plunge) {
        Vector3 c = shoreCorners[k], o = shoreCorners[other];
        float least = mouthLimit(k, plunge) / c.dst(o);
        float mx = (c.x + o.x) / 2, my = (c.y + o.y) / 2;
        float seed = .5f;
        if (shore(mx, my) <= 0 && !tile.liquid().molten()) {
            // A narrow curved stream can cross the side away from its midpoint. Find its wet centre, using the
            // same sampling order from either hex, before tracing toward either bank.
            boolean forward = c.x < o.x || c.x == o.x && c.y < o.y;
            Vector3 a = forward ? c : o, b = forward ? o : c;
            float best = 0;
            for (int i = 1; i < 96; i++) {
                float s = i / 96f, x = a.x + (b.x - a.x) * s, y = a.y + (b.y - a.y) * s;
                float wet = shore(x, y);
                if (wet <= best) { continue; }
                best = wet;
                mx = x;
                my = y;
                seed = forward ? s : 1 - s;
            }
        }
        // Fixed banks can make the field dry even between connected water hexes. Keep their opening instead of
        // tracing from land, which would collapse both mouth ends onto its midpoint.
        if (shore(mx, my) <= 0) { return least; }
        float dry = firstDry(mx, my, c.x + (o.x - c.x) * least, c.y + (o.y - c.y) * least, null);
        return dry < 0 ? least : seed - dry * (seed - least);
    }

    /**
     * How near corner k, as the shore moves it, an open mouth beside a bank may end, in world units along the mouth:
     * a bank and a unit short of a corner the shore moves at the water's level, a wet margin beyond the steps through
     * a corner where the land rises; at a corner that stays put four units less land than a beach (below a fall,
     * nearly at the corner, so the whole sheet lands in water), and beyond whatever steps through it reach into the
     * mouth.
     */
    private float mouthLimit(int k, boolean plunge) {
        float scale = BoardGeometry.hexScale(), reach = relief.cornerReach(k);
        float opening = (BEACH - (plunge ? PLUNGE_OPENING : MOUTH_OPENING)) * scale * 1.1547005f;
        // Past a bank a deck has moved, a riverside's width across the corner, so the shore runs out to the mouth's end
        // without crossing the bank; elsewhere over lower land as at a corner that stays.
        if (relief.bayAt(k)) { return reach + BEACH * scale * 1.1547005f; }
        if (relief.overLand(k)) { return reach + opening; }
        if (shoreCorners[k].equals(corners[k])) { return Math.max(opening, reach + HUG * scale); }
        return reach > 0 ? reach + HUG * scale : (SHORE_BANK + 1) * scale;
    }

    /**
     * Where the water ends on the way from the hex centre out to {@code target} on the outline: the first point where
     * this hex's shore turns to land; the target itself if it never does.
     */
    private Vector3 along(Vector3 target, boolean[] plunge) {
        float dry = firstDry(center.x, center.y, target.x, target.y, plunge);
        return dry < 0 ? new Vector3(target) : new Vector3(center).lerp(target, dry);
    }

    /**
     * Where the way from (ax, ay) to (bx, by) first turns to land, as a fraction of it, or -1 if it never does: by the
     * shore field alone without {@code plunge}, else by this hex's shore ({@link #wet}). Even steps find the first dry
     * one, and halvings the turn within it.
     */
    private float firstDry(float ax, float ay, float bx, float by, boolean[] plunge) {
        float dx = bx - ax, dy = by - ay, lo = 0, hi = -1;
        for (int i = 1; i <= SHORE_STEPS && hi < 0; i++) {
            float s = i / (float) SHORE_STEPS;
            if (plunge == null ? shore(ax + dx * s, ay + dy * s) <= 0 : wet(ax + dx * s, ay + dy * s, plunge) <= 0) {
                lo = (i - 1) / (float) SHORE_STEPS;
                hi = s;
            }
        }
        if (hi < 0) { return -1; }
        for (int i = 0; i < SHORE_HALVINGS; i++) {
            float s = (lo + hi) / 2;
            if (plunge == null ? shore(ax + dx * s, ay + dy * s) > 0 : wet(ax + dx * s, ay + dy * s, plunge) > 0) {
                lo = s;
            } else {
                hi = s;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * This hex's shore at (x, y), positive in the water: the shore field, swollen round the foot of each fall that
     * pours into this hex, and inside the banks it keeps, the corners between them rounded over {@link #SHORE_ROUND}.
     */
    private float wet(float x, float y, boolean[] plunge) {
        if (canal) { return canalField(x, y); }
        float scale = BoardGeometry.hexScale(), value = shore(x, y);
        for (int edge = 0; edge < 6; edge++) {
            if (!plunge[edge]) { continue; }
            Vector3 middle = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], .5f);
            Vector3 inward = edgeInward(edge);
            float into = (x - middle.x) * inward.x + (y - middle.y) * inward.y;
            // Scour opens a rounded pool downstream of the inlet. At the shared edge it vanishes smoothly,
            // leaving the canonical mouth intact instead of abruptly narrowing the swollen bank there.
            middle.mulAdd(inward, .15f * BoardGeometry.width());
            float d = (float) Math.hypot(x - middle.x, y - middle.y) / (.4f * BoardGeometry.width());
            value += PLUNGE_POOL * scale * (1 - BoardRelief.smooth(d))
                  * BoardRelief.smooth(into / (.12f * BoardGeometry.width()));
        }
        for (Bank bank : banks) {
            value = BoardRelief.smoothMin(value, bank.clearance(x, y), SHORE_ROUND * scale);
        }
        float bank = bankClearance(x, y);
        return Float.isNaN(bank) ? value : BoardRelief.smoothMin(value, bank, SHORE_ROUND * scale);
    }

    /**
     * How far inside a canal's channel (x, y) lies, in world units, negative on its shoulders. Each point belongs to the
     * spoke from the hex's centre to a mouth's middle nearest it in bearing, and lies in that spoke's straight strip of the
     * channel's half-width, which stops that far behind the centre: strips meet in mitred corners, and the next canal meets
     * a mouth at the same width. With no mouth, a hexagonal basin set in from the hex's edges.
     */
    private float canalField(float x, float y) {
        float scale = BoardGeometry.hexScale(), dx = x - center.x, dy = y - center.y, best = Float.NEGATIVE_INFINITY;
        float along = 0, across = 0;
        for (int edge = 0; edge < 6; edge++) {
            Vector3 out = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], .5f).sub(center).nor();
            float a = dx * out.x + dy * out.y;
            if (openMouths == 0) {
                best = Math.max(best, a);
            } else if ((openMouths & 1 << edge) != 0) {
                float c = dx * out.y - dy * out.x, bearing = a / Math.max((float) Math.hypot(a, c), 1e-6f);
                if (bearing > best) {
                    best = bearing;
                    along = a;
                    across = Math.abs(c);
                }
            }
        }
        if (openMouths == 0) { return CANAL_BASIN * scale - best; }
        return Math.min(CANAL_HALF * scale - across, along + CANAL_HALF * scale);
    }

    /**
     * Where a canal deck's ramp comes down onto the next hex: the solid ramp's footprint here, from the edge to the deck's
     * level centre and the ramp's half-width either side (BoardBridgeFooting), with the ramp's height at the edge and at its
     * inner end. {@code a}, {@code b}, {@code c}, {@code d} run round it with its inside on their left.
     */
    private record RampCut(int edge, Vector3 a, Vector3 b, Vector3 c, Vector3 d, Vector3 gate, Vector3 inward, float length,
          float low, float high) {
        /** The ramp's plane at (x, y), sunk under its surface by {@link #RAMP_SINK}. */
        float floor(float x, float y) {
            float t = Math.clamp(((x - gate.x) * inward.x + (y - gate.y) * inward.y) / length, 0, 1);
            return low + (high - low) * t - RAMP_SINK * BoardGeometry.hexScale();
        }

        List<Vector3> outline() { return List.of(a, b, c, d); }
    }

    /** Tile pixels a ramp's cut lies under its surface, hidden by the ramp's own block, and inside its rails. */
    private static final float RAMP_SINK = .5f, CUT_INSET = .25f;

    private List<RampCut> rampCuts(BoardScene scene) {
        List<RampCut> result = new ArrayList<>();
        var bridge = BoardBridge.feature(tile);
        if (bridge == null || BoardBridge.family(tile) == BoardBridge.Family.NATURAL) { return result; }
        float scale = BoardGeometry.hexScale(), high = BoardBridge.deckZ(tile);
        for (int edge = 0; edge < 6; edge++) {
            int d = BoardGeometry.edgeDirection(edge);
            BoardScene.Tile next = scene.tile(tile.coords().translated(d));
            if (next == null || !BoardBridge.road(tile, next, d)) { continue; }
            float low = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, next, d));
            if (low >= high - .001f) { continue; }
            // A hair inside the rails' outer faces, which then stand in the concrete instead of sharing a plane with the cut.
            float width = (((tile.deckCorridor().sides() & 1 << d) != 0 ? BoardRoad.CORRIDOR_HALF : BoardRoad.Kind.PAVED.halfWidth)
                  + BoardBridge.SHOULDER - CUT_INSET) * scale;
            Vector3 gate = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], .5f);
            gate.z = 0;
            Vector3 inward = new Vector3(center.x - gate.x, center.y - gate.y, 0);
            float length = (1 - BoardRoadGrade.BRIDGE_START) * inward.len();
            inward.nor();
            Vector3 across = new Vector3(-inward.y, inward.x, 0);
            Vector3 a = new Vector3(gate).mulAdd(across, -width), b = new Vector3(gate).mulAdd(across, width);
            Vector3 c = new Vector3(b).mulAdd(inward, length), dd = new Vector3(a).mulAdd(inward, length);
            // Inside on the left of each side.
            boolean ccw = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) > 0;
            result.add(ccw ? new RampCut(edge, a, b, c, dd, gate, inward, length, low, high)
                  : new RampCut(edge, b, a, dd, c, gate, inward, length, low, high));
        }
        return result;
    }

    /**
     * Cuts each of a canal deck's ramps straight into the canal's concrete: its ground and water inside the ramp's
     * footprint give way to a floor under the ramp, and upright faces along the cut join that floor to the ground beside
     * it, down from a shoulder or up from the channel's lining, where the ramp's block stands in the water.
     */
    private List<Face> cutRamps() {
        List<Face> cutWalls = new ArrayList<>();
        for (RampCut cut : deckCuts) {
            List<Vector3> outline = cut.outline();
            List<Face> kept = carve(faces, outline);
            faces.clear();
            faces.addAll(kept);
            kept = subtract(waterFaces, outline, false);
            waterFaces.clear();
            waterFaces.addAll(kept);
            List<Vector3> floor = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Vector3 p = outline.get(i), q = outline.get((i + 1) % 4);
                floor.add(new Vector3(p.x, p.y, cut.floor(p.x, p.y)));
                // Across the edge the wall opens instead (openRamps).
                if (i == 0) { continue; }
                // Each edge of the ground lying on the cut's side, in the side's direction, down (or up) to its floor.
                float dx = q.x - p.x, dy = q.y - p.y, length2 = dx * dx + dy * dy;
                List<Vector3[]> rim = new ArrayList<>();
                for (Face face : faces) {
                    Vector3[] corners = { face.a(), face.b(), face.c() };
                    for (int k = 0; k < 3; k++) {
                        Vector3 u = corners[k], v = corners[(k + 1) % 3];
                        float tu = ((u.x - p.x) * dx + (u.y - p.y) * dy) / length2, tv = ((v.x - p.x) * dx + (v.y - p.y) * dy) / length2;
                        if (Math.abs(cross(p, q, u)) < .01f * p.dst(q) && Math.abs(cross(p, q, v)) < .01f * p.dst(q)
                              && Math.min(tu, tv) > -1e-4f && Math.max(tu, tv) < 1 + 1e-4f && Math.abs(tu - tv) * length2 > 1e-8f) {
                            rim.add(tu < tv ? new Vector3[] { u, v } : new Vector3[] { v, u });
                        }
                    }
                }
                int from = faces.size();
                for (Vector3[] segment : rim) { side(cut, segment[0], segment[1]); }
                cutWalls.addAll(faces.subList(from, faces.size()));
            }
            polygon(floor.toArray(Vector3[]::new), Finish.BANK, faces);
        }
        return cutWalls;
    }

    /**
     * An upright face of a cut between two of the ground's points on its side and the cut's floor below or above them,
     * facing whichever way it is seen: into the cut from a shoulder, out of it from the channel. Split where it crosses.
     */
    private void side(RampCut cut, Vector3 a, Vector3 b) {
        Vector3 fa = new Vector3(a.x, a.y, cut.floor(a.x, a.y)), fb = new Vector3(b.x, b.y, cut.floor(b.x, b.y));
        float da = a.z - fa.z, db = b.z - fb.z;
        if (da * db < 0) {
            float t = da / (da - db);
            Vector3 middle = new Vector3(a).lerp(b, t);
            middle.z = cut.floor(middle.x, middle.y);
            side(cut, a, middle);
            side(cut, middle, b);
            return;
        }
        if (da + db >= 0) {
            quad(a, b, fb, fa, Finish.BANK, -1);
        } else {
            quad(b, a, fa, fb, Finish.BANK, -1);
        }
    }

    /**
     * The ground with what lies inside a convex outline (its inside on the left of each side) taken out: every face split
     * along each side's whole line first, so faces on either side of a line meet at the same points, then what lies inside
     * dropped. Points a split puts inside a face take its shading.
     */
    private List<Face> carve(List<Face> source, List<Vector3> outline) {
        List<Face> pieces = new ArrayList<>(source);
        for (int i = 0; i < outline.size(); i++) {
            Vector3 p = outline.get(i), q = outline.get((i + 1) % outline.size());
            List<Face> next = new ArrayList<>(pieces.size() + 16);
            for (Face face : pieces) {
                List<Vector3> triangle = List.of(face.a(), face.b(), face.c());
                List<Vector3> left = BoardTacticalGeometry.clip(triangle, p, q), right = BoardTacticalGeometry.clip(triangle, q, p);
                if (left.size() < 3 || right.size() < 3 || area(left) < 1e-6f || area(right) < 1e-6f) {
                    next.add(face);
                    continue;
                }
                for (List<Vector3> part : List.of(left, right)) {
                    for (Vector3 point : part) { shadeCut(face, point); }
                    for (int k = 1; k + 1 < part.size(); k++) {
                        if (area(List.of(part.getFirst(), part.get(k), part.get(k + 1))) > 1e-6f) {
                            next.add(new Face(part.getFirst(), part.get(k), part.get(k + 1), face.finish(), face.landEdge(), face.scatter()));
                        }
                    }
                }
            }
            pieces = next;
        }
        pieces.removeIf(face -> {
            float x = (face.a().x + face.b().x + face.c().x) / 3, y = (face.a().y + face.b().y + face.c().y) / 3;
            for (int i = 0; i < outline.size(); i++) {
                if (cross(outline.get(i), outline.get((i + 1) % outline.size()), new Vector3(x, y, 0)) < 0) { return false; }
            }
            return true;
        });
        return pieces;
    }

    /** The faces with what lies inside a convex outline (its inside on the left of each side) taken out. */
    private List<Face> subtract(List<Face> source, List<Vector3> outline, boolean shaded) {
        List<Face> kept = new ArrayList<>(source.size());
        for (Face face : source) {
            List<Vector3> triangle = List.of(face.a(), face.b(), face.c());
            List<Vector3> inside = BoardTacticalGeometry.clip(triangle, outline);
            if (inside.size() < 3 || area(inside) < 1e-6f) {
                kept.add(face);
                continue;
            }
            // What lies outside, side by side: right of each side in turn, the rest on to the next.
            List<Vector3> rest = triangle;
            for (int i = 0; i < outline.size() && rest.size() >= 3; i++) {
                Vector3 p = outline.get(i), q = outline.get((i + 1) % outline.size());
                List<Vector3> outside = BoardTacticalGeometry.clip(rest, q, p);
                for (int k = 1; k + 1 < outside.size(); k++) {
                    Vector3[] points = { outside.getFirst(), outside.get(k), outside.get(k + 1) };
                    if (shaded) {
                        for (Vector3 point : points) { shadeCut(face, point); }
                    }
                    if (area(List.of(points)) > 1e-6f) {
                        kept.add(new Face(points[0], points[1], points[2], face.finish(), face.landEdge(), face.scatter()));
                    }
                }
                rest = BoardTacticalGeometry.clip(rest, p, q);
            }
        }
        return kept;
    }

    /** A canal deck's ramp opens its edge's wall down to the ramp's cut there. */
    private List<Face> openRamps(List<Face> walls) {
        List<RampCut> cuts = deckCuts;
        if (cuts.isEmpty()) { return walls; }
        List<Face> result = new ArrayList<>(walls.size());
        for (Face face : walls) {
            RampCut cut = null;
            for (RampCut candidate : cuts) {
                if (candidate.edge() == face.landEdge()) { cut = candidate; }
            }
            if (cut == null) {
                result.add(face);
                continue;
            }
            // The wall across the ramp's width, above its cut, gives way: keep what lies beside the ramp or below the cut.
            Vector3 across = new Vector3(cut.b()).sub(cut.a()).nor();
            float s0 = across.dot(cut.a()), s1 = across.dot(cut.b());
            List<Vector3> rest = List.of(face.a(), face.b(), face.c());
            List<List<Vector3>> pieces = new ArrayList<>();
            pieces.add(planeClip(rest, p -> s0 - across.dot(p)));
            rest = planeClip(rest, p -> across.dot(p) - s0);
            pieces.add(planeClip(rest, p -> across.dot(p) - s1));
            rest = planeClip(rest, p -> s1 - across.dot(p));
            RampCut ramp = cut;
            pieces.add(planeClip(rest, p -> ramp.floor(p.x, p.y) - p.z));
            for (List<Vector3> piece : pieces) {
                for (int k = 1; k + 1 < piece.size(); k++) {
                    result.add(new Face(piece.getFirst(), piece.get(k), piece.get(k + 1), face.finish(), face.landEdge()));
                }
            }
        }
        return result;
    }

    /**
     * The part of a polygon where {@code side} is at least zero, cut along where it is zero. A cut point takes the
     * shading of the wall it was cut from.
     */
    private List<Vector3> planeClip(List<Vector3> polygon, java.util.function.ToDoubleFunction<Vector3> side) {
        List<Vector3> result = new ArrayList<>(polygon.size() + 1);
        if (polygon.isEmpty()) { return result; }
        Vector3 previous = polygon.getLast();
        double before = side.applyAsDouble(previous);
        for (Vector3 point : polygon) {
            double after = side.applyAsDouble(point);
            if ((before >= 0) != (after >= 0)) {
                float t = (float) (before / (before - after));
                Vector3 cut = new Vector3(previous).lerp(point, t);
                relief.interpolateShade(previous, point, t, cut);
                result.add(cut);
            }
            if (after >= 0) { result.add(point); }
            previous = point;
            before = after;
        }
        return result;
    }

    /** A point a cut puts inside a face takes the face's shading there. */
    private void shadeCut(Face face, Vector3 p) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        if (p == a || p == b || p == c) { return; }
        float d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
        if (Math.abs(d) < 1e-9f) { return; }
        float wa = ((b.y - c.y) * (p.x - c.x) + (c.x - b.x) * (p.y - c.y)) / d;
        float wb = ((c.y - a.y) * (p.x - c.x) + (a.x - c.x) * (p.y - c.y)) / d;
        relief.interpolateShade(a, b, c, wa, wb, 1 - wa - wb, p);
    }

    private static float cross(Vector3 a, Vector3 b, Vector3 p) {
        return (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x);
    }

    private static float area(List<Vector3> polygon) {
        float sum = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Vector3 p = polygon.get(i), q = polygon.get((i + 1) % polygon.size());
            sum += p.x * q.y - q.x * p.y;
        }
        return Math.abs(sum) / 2;
    }

    /** Where a canal's mouth on {@code edge} ends towards {@code corner}, {@code half} tile pixels from its middle. */
    private Vector3 canalMouth(int edge, int corner, float half) {
        Vector3 middle = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], .5f);
        return middle.mulAdd(new Vector3(corners[corner]).sub(middle).nor(), half * BoardGeometry.hexScale());
    }

    /** A canal's floor half-width: its lining's batter, steepened where the floor would grow narrower than it keeps. */
    private float canalFloor() {
        return Math.max(CANAL_FLOOR * BoardGeometry.hexScale(), CANAL_HALF * BoardGeometry.hexScale() - depth(tile) * CANAL_BATTER);
    }

    /**
     * How far past a riverside's width inside the rims of this water hex's banks over lower land a point lies, wherever
     * roads have moved them; negative on the bank, {@code NaN} without such a bank.
     */
    private float bankClearance(float x, float y) {
        float scale = BoardGeometry.hexScale();
        return relief.insideRim(x, y) - BEACH * scale - BANK_WANDER * scale * BoardRelief.bankWander(x, y);
    }

    /** Whether a point of this water hex lies on its bank over lower land, where its water keeps away. */
    boolean overBank(float x, float y) {
        return !columns && bankClearance(x, y) < 0;
    }

    /** The board's shore field of this hex's liquid at (x, y): positive in it (see {@link BoardRelief#shore}). */
    private float shore(float x, float y) {
        return relief.shore(x, y, tile.liquid().molten());
    }

    /**
     * How far a bank on {@code edge} keeps the water in from the edge: beside a slope up from the water, which runs on
     * under it to the bed, a wet margin beyond how far the slope's foot reaches into the hex; elsewhere a beach.
     */
    private float least(int edge) {
        float scale = BoardGeometry.hexScale();
        return relief.slope(edge) ? relief.reach(edge) + HUG * scale : BEACH * scale;
    }

    /** Triangulates a planar outline, including concave shores and cliff rims that cannot use a centre fan. */
    static void polygon(Vector3[] contour, Finish finish, List<Face> destination) {
        float[] xy = new float[contour.length * 2];
        for (int i = 0; i < contour.length; i++) {
            xy[i * 2] = contour[i].x - contour[0].x;
            xy[i * 2 + 1] = contour[i].y - contour[0].y;
        }
        var indices = new EarClippingTriangulator().computeTriangles(xy);
        int start = destination.size();
        for (int i = 0; i < indices.size; i += 3) {
            destination.add(new Face(contour[indices.get(i)], contour[indices.get(i + 2)],
                  contour[indices.get(i + 1)], finish));
        }
        // Ear clipping can leave a collinear boundary sample in a zero-area ear. Dropping that ear loses
        // the shared rim segmentation. Flip its internal diagonal into the adjacent triangle instead;
        // this keeps every boundary vertex and the same triangle count without moving the outline.
        for (int pass = 0; pass < contour.length; pass++) {
            boolean changed = false;
            for (int i = start; i < destination.size(); i++) {
                Face face = destination.get(i);
                Vector3[] p = { face.a(), face.b(), face.c() };
                int longest = 0;
                for (int k = 1; k < 3; k++) {
                    if (p[k].dst2(p[(k + 1) % 3]) > p[longest].dst2(p[(longest + 1) % 3])) { longest = k; }
                }
                Vector3 a = p[longest], b = p[(longest + 1) % 3], c = p[(longest + 2) % 3];
                float tolerance = .001f * BoardGeometry.hexScale() * a.dst(b);
                if (Math.abs(upward(face)) > tolerance) { continue; }
                for (int j = start; j < destination.size(); j++) {
                    if (i == j) { continue; }
                    Face other = destination.get(j);
                    Vector3[] q = { other.a(), other.b(), other.c() };
                    for (int k = 0; k < 3; k++) {
                        if (q[k] != b || q[(k + 1) % 3] != a) { continue; }
                        Vector3 d = q[(k + 2) % 3];
                        Face first = new Face(d, b, c, finish), second = new Face(d, c, a, finish);
                        if (Math.min(upward(first), upward(second)) <= tolerance) { continue; }
                        destination.set(i, first);
                        destination.set(j, second);
                        changed = true;
                        break;
                    }
                    if (destination.get(i) != face) { break; }
                }
            }
            if (!changed) { break; }
        }
        // Coincident outline points, as where a concrete fit collapses an edge, leave a face with no plane: it covers
        // nothing, and picking would hit it at the ray's origin.
        destination.subList(start, destination.size())
              .removeIf(face -> same(face.a(), face.b()) || same(face.b(), face.c()) || same(face.c(), face.a()));
    }

    private static boolean same(Vector3 p, Vector3 q) { return p.x == q.x && p.y == q.y; }

    /** The carriageways that may reserve ground here: this hex's own, then its neighbours'. */
    private List<BoardRoadGrade> grades(boolean corners) {
        return grades(scene, tile, corners, !columns);
    }

    private static List<BoardRoadGrade> grades(BoardScene scene, BoardScene.Tile tile, boolean corners, boolean eased) {
        List<BoardRoadGrade> grades = new ArrayList<>();
        var own = BoardRoadGrade.of(scene, tile, eased);
        if (own != null) { grades.add(own); }
        for (int d = 0; d < 6; d++) {
            var next = scene.neighbor(tile, d);
            // Only arms along an edge that ends at one of this hex's corners can reach past it into this hex. Otherwise
            // any carriageway beside this hex counts, and a roadless apron where a step between them reaches into it, or
            // where this hex's road runs on into it: this hex's ground can reach under the apron's lane at their edge.
            if (next == null) { continue; }
            int arms = corners ? 1 << (d + 2) % 6 | 1 << (d + 4) % 6 : 63;
            if ((next.wayExits() & arms) == 0 && (corners
                  || next.floor() == tile.floor() && !BoardRoadGrade.apron(tile, next, d))) { continue; }
            var grade = BoardRoadGrade.of(scene, next, eased);
            if (grade != null) { grades.add(grade); }
        }
        // One order on every hex, so overlapping reserved ground takes the same grade on both sides of an edge.
        grades.sort(java.util.Comparator.comparingInt((BoardRoadGrade grade) -> grade.tile().coords().getX())
              .thenComparingInt(grade -> grade.tile().coords().getY()));
        return grades;
    }

    /**
     * The ground a hex owns: its lattice outline, fitted to concrete as its top is (BoardConcrete#corner), so the road a
     * hex meshes covers the top it replaces. A shore's move is not: the water's own shore takes the ground it frees.
     */
    private static Area cell(BoardScene scene, Coords coords, boolean fitted) {
        Path2D.Float outline = new Path2D.Float();
        for (int k = 0; k < 6; k++) {
            Vector3 corner = fitted ? BoardConcrete.of(scene).corner(coords, k) : BoardGeometry.corner(coords, 0, k);
            if (k == 0) { outline.moveTo(corner.x, corner.y); } else { outline.lineTo(corner.x, corner.y); }
        }
        outline.closePath();
        return new Area(outline);
    }

    /**
     * Carriageways reserve their ground: the top gives way to the road surface across the reserved part of this hex.
     * Natural ground first eases into the roads around ({@link BoardRoadBank}); poured ground keeps a vertical cut face,
     * as does whatever still stands off the road's edge. Level roads on level ground keep the top.
     */
    private void reserveRoads() {
        bank = new BoardRoadBank(scene, tile, relief);
        if (!bank.active()) {
            bank = null;
        } else if (tile.liquid().present()) {
            // A water hex's shore reaches into the dry hexes around, whose ground it shares: split on the same grid, it
            // eases alike, though the field leaves the water's own cell as it is. Its vertices move in place, as its
            // water shares them.
            List<Face> split = new ArrayList<>(bank.split(faces));
            faces.clear();
            faces.addAll(split);
            bank.move(faces);
        } else if (natural()) {
            // A copy: with no bank to shape, conform hands back the list itself.
            List<Face> eased = new ArrayList<>(bank.conform(faces, Float.NEGATIVE_INFINITY));
            faces.clear();
            faces.addAll(eased);
        }
        boolean wet = tile.liquid().present();
        if (wet) { bank = null; }
        if (wet && !BoardRoad.groundShown(tile)) {
            // Ice, lava or a deck keeps roads off this hex's ground, but its shore can reach into a road's reserved ground
            // beside it, and gives way there as any natural top does; its bed and water stay as they are.
            var grades = grades(false);
            if (!grades.isEmpty() && natural()) {
                var cut = new BoardRoadCut(new Area(), cell(scene, tile.coords(), true), center.z, grades, boardEdges(scene, tile));
                List<Face> shore = faces.stream().filter(face -> face.finish() == Finish.TOP).toList();
                List<Face> kept = cut.clip(shore, relief, cutFaces(retainingWalls), cutFaces(pouredWalls), roadAround(),
                      null, true, true);
                faces.removeIf(face -> face.finish() == Finish.TOP);
                faces.addAll(kept);
            }
            return;
        }
        var cut = roadCut(scene, tile);
        if (cut == null) { return; }
        BoardRoadCut.WallBuilder wall = cutFaces(retainingWalls);
        List<Face> road = new ArrayList<>();
        cut.mesh(road, wall);
        if (wet) {
            // Water is a layer over the same road ground, which only takes the bed's material under it.
            road.replaceAll(face -> new Face(face.a(), face.b(), face.c(), Finish.BED, face.landEdge()));
            renderBed = null;
        }
        reservedRoad = List.copyOf(road);
        reservedFaces.addAll(road);
        List<Face> kept = cut.clip(faces, relief, wall, cutFaces(pouredWalls), roadAround(), bank, wet || natural(), false);
        pouredTop = pouredWalls.size();
        faces.clear();
        faces.addAll(kept);
        faces.addAll(road);
        var normals = vertexNormals(road);
        for (Face face : road) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) { relief.roadShade(p, normals.get(p)); }
        }
    }

    /**
     * The cut reserving an Enhanced hex's road ground (#reserveRoads); null where none leaves the top's level, which level
     * roads on level ground keep. A bed is no level top, whatever its neighbours' levels.
     */
    private static BoardRoadCut roadCut(BoardScene scene, BoardScene.Tile tile) {
        if (!BoardRoad.groundShown(tile) || tile.ultraSublevel() || tile.backdrop().present()) { return null; }
        var grades = grades(scene, tile, false, true);
        if (grades.isEmpty()) { return null; }
        boolean level = !tile.liquid().present() && grades.stream().noneMatch(grade -> grade.graded()
              || grade.tile().floor() != tile.floor());
        for (int d = 0; level && d < 6; d++) {
            var next = scene.neighbor(tile, d);
            level = next == null || next.floor() == tile.floor() && !next.liquid().present();
        }
        if (level) { return null; }
        return new BoardRoadCut(new Area(), cell(scene, tile.coords(), true), BoardRoadGrade.ground(tile) * BoardGeometry.level(),
              grades, boardEdges(scene, tile));
    }

    /**
     * The road surfaces this hex's ground meets: its own and its neighbours', as they emit them, so the ground is cut
     * exactly where it meets them. Each is meshed once per scene snapshot (BoardRoadGrade#roads).
     */
    private List<Face> roadAround() {
        List<Face> around = new ArrayList<>(road(scene, tile));
        for (int d = 0; d < 6; d++) { around.addAll(road(scene, scene.neighbor(tile, d))); }
        return around;
    }

    /** The road surface a dry hex emits (#reserveRoads), for sampling only; empty where it emits none. */
    static List<Face> road(BoardScene scene, BoardScene.Tile tile) {
        if (tile == null) { return List.of(); }
        return BoardRoadGrade.roads(scene).computeIfAbsent(tile.coords(), coords -> {
            var cut = roadCut(scene, tile);
            if (cut == null) { return List.of(); }
            List<Face> road = new ArrayList<>();
            cut.mesh(road, (topA, lowA, topB, lowB, fx, fy) -> { });
            return List.copyOf(road);
        });
    }

    /**
     * Whether a face of this hex's top is road surface a neighbour's carriageway reserves here, which that road paints;
     * other ground here, such as the ground under a bridge it runs onto, lies apart from that road.
     */
    boolean carries(Face face, BoardScene.Tile source) {
        var grade = BoardRoadGrade.of(scene, source, true);
        if (grade == null || !reservedFaces.contains(face)) { return false; }
        return grade.contains((face.a().x + face.b().x + face.c().x) / 3, (face.a().y + face.b().y + face.c().y) / 3);
    }

    /** The edges of a hex that are the board's, as a bit mask by edge: the board's cut stands under them. */
    private static int boardEdges(BoardScene scene, BoardScene.Tile tile) {
        int edges = 0;
        for (int e = 0; e < 6; e++) {
            if (scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(e))) == null) { edges |= 1 << e; }
        }
        return edges;
    }

    /**
     * Whether tessellation keeps a relief vertex where its mesh puts it (GpuTessellation, a_tessFixed), so every mesh
     * that meets it there meets it flat: the board's cut, against its flat wall; rail grades and bridge passages, which
     * their rigid meshes sample; the shared edges of water banks, which are shaded apart; and a liquid hex's ground at
     * and below its water, whose bed fans and their clipped and material-split neighbours keep one support.
     */
    boolean fixedTessellation(Vector3 point) {
        float tolerance = contactTolerance();
        if (tile.liquid().present() && point.z <= waterHeight(point.x, point.y) + tolerance) { return true; }
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = moved(edge), b = moved((edge + 1) % 6);
            if (((boardCutEdges | waterSeamEdges) & 1 << edge) != 0
                  && BoardRelief.distance(point.x, point.y, a.x, a.y, b.x, b.y) <= tolerance) { return true; }
        }
        for (var passage : bridgePassages) {
            if (passage.obstructs(point, tolerance, 0)) { return true; }
        }
        return supportsRail(point, tolerance);
    }

    /** How near a point lies to a contact to share its decision on both sides. */
    private static float contactTolerance() { return .001f * BoardGeometry.hexScale(); }

    /** Rail meshes sample these exact planes; their ground and any crossing asphalt must keep that support. */
    private boolean supportsRail(Vector3 point, float tolerance) {
        for (var grade : railGrades) {
            float[] bounds = grade.bounds();
            if (point.x < bounds[0] - tolerance || point.y < bounds[1] - tolerance
                  || point.x > bounds[2] + tolerance || point.y > bounds[3] + tolerance) { continue; }
            if (grade.contains(point.x, point.y)) { return true; }
            // Include the outline: ground on either side of a shared contact must make the same decision.
            float[] outline = grade.outline();
            for (int i = 0; i < outline.length; i += 4) {
                if (BoardRelief.distance(point.x, point.y, outline[i], outline[i + 1], outline[i + 2], outline[i + 3])
                      <= tolerance) { return true; }
            }
        }
        return false;
    }

    /** Natural ground eases into the roads around; poured ground is cut clean. */
    private boolean natural() { return tile.surface() != BoardScene.Surface.CONCRETE; }

    /**
     * Cut faces into {@code into}. Poured ground has none along an edge a cast wall already closes (#sides): its own down
     * to a lower hex, or a higher poured hex's down to it. A cut face in that wall's plane would fight it.
     */
    private BoardRoadCut.WallBuilder cutFaces(List<Face> into) {
        return (topA, lowA, topB, lowB, fx, fy) -> {
            if (natural() || !onCastStep(topA, topB)) { relief.cutWall(topA, lowA, topB, lowB, fx, fy, into); }
        };
    }

    /** Whether a run lies along an edge a cast wall closes: down to a lower hex, or up to poured ground. */
    private boolean onCastStep(Vector3 a, Vector3 b) {
        float tolerance = .01f * BoardGeometry.hexScale();
        for (int edge = 0; edge < 6; edge++) {
            var next = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            if (next == null || next.backdrop().present() || next.elevation() == tile.elevation()
                  || next.elevation() > tile.elevation() && next.surface() != BoardScene.Surface.CONCRETE) { continue; }
            Vector3 p = moved(edge), q = moved((edge + 1) % 6);
            if (edgeDistance(a, p, q) < tolerance && edgeDistance(b, p, q) < tolerance) { return true; }
        }
        return false;
    }

    /** Slopes ease into the roads around, as the top does, and give way to the carriageways that reserve their ground. */
    private List<Face> reserveWalls(List<Face> walls, float floor) {
        // A water hex's walls stand past its cell, on ground the roads' banks ease. Built here, as a water hex restored
        // from finished geometry (WaterGeometry) has no field of its own.
        var field = !tile.liquid().present() ? bank : walls.isEmpty() ? null : new BoardRoadBank(scene, tile, relief);
        if (field != null && natural()) { walls = field.conform(walls, floor); }
        var grades = grades(false);
        if (grades.isEmpty()) { return walls; }
        var cut = new BoardRoadCut(new Area(), cell(scene, tile.coords(), true), center.z, grades, boardEdges(scene, tile));
        List<Face> curtains = new ArrayList<>();
        pouredWalls.subList(pouredTop, pouredWalls.size()).clear();
        List<Face> result = new ArrayList<>(cut.clip(walls, relief, cutFaces(curtains), cutFaces(pouredWalls),
              roadAround(), field, natural(), false));
        result.addAll(curtains);
        containmentWalls.clear();
        if (natural() && BoardRelief.tuning().containmentWalls()) {
            List<Face> ground = new ArrayList<>(groundFaces());
            ground.addAll(result);
            cut.containmentWalls(ground, reservedRoad, relief, containmentWalls);
        }
        return result;
    }

    /**
     * The top where carriageways reserve ground: this hex's own, and a neighbour's wide corridor past a corner of its
     * edge. Each reserved piece takes its road's grade and a cut face closes it against the level top; false where no
     * carriageway leaves the top's level.
     */
    private boolean reservedTop() {
        var grades = grades(true);
        if (grades.isEmpty()) { return false; }
        Area cell = cell(scene, tile.coords(), false);
        var cut = new BoardRoadCut(cell, cell, center.z, grades, boardEdges(scene, tile));
        if (!cut.reserves() || !cut.graded()) { return false; }
        cut.mesh(faces, (topA, lowA, topB, lowB, fx, fy) -> relief.cutWall(topA, lowA, topB, lowB, fx, fy, retainingWalls));
        roadCut = true;
        return true;
    }

    /** A level road with level, dry neighbours needs only the six original terrain faces. */
    static boolean flatRoadTop(BoardScene scene, BoardScene.Tile tile, int ramps) {
        if (ramps != 0) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            if (neighbor != null && (neighbor.elevation() != tile.elevation() || neighbor.liquid().present())) { return false; }
        }
        return true;
    }

    /** Area-weighted shared normals for an emitted surface, used by the mesh renderer and its road contacts. */
    static Map<Vector3, Vector3> vertexNormals(List<Face> faces) {
        Map<Vector3, Vector3> normals = new HashMap<>();
        for (Face face : faces) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(p, key -> new Vector3()).add(normal);
            }
        }
        normals.values().forEach(Vector3::nor);
        return normals;
    }

    private static float upward(Face face) { return upward(face.a(), face.b(), face.c()); }

    private static float upward(Vector3 a, Vector3 b, Vector3 c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private float edgeDistance(Vector3 p, int edge) {
        return edgeDistance(p, corners[edge], corners[(edge + 1) % 6]);
    }

    private static float edgeDistance(Vector3 p, Vector3 a, Vector3 b) {
        float ex = b.x - a.x, ey = b.y - a.y;
        return Math.abs((p.x - a.x) * ey - (p.y - a.y) * ex) / (float) Math.hypot(ex, ey);
    }

    /** Raised bridge fill can receive concrete; descending approaches retain their excavated natural banks. */
    boolean bridgeSupport(Face face) {
        return ramps != 0 && bridgeSupport(scene, tile, face);
    }

    /** Finished geometry can use the same material decision for ground cover without retaining its builder. */
    static boolean bridgeSupport(BoardScene scene, BoardScene.Tile tile, Face face) {
        if (tile.wayExits() == 0 || tile.liquid().present()
              || (face.finish() != Finish.TOP && face.finish() != Finish.WALL)) { return false; }
        float ground = tile.elevation() * BoardGeometry.level(), epsilon = .001f * BoardGeometry.hexScale();
        float high = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
        float low = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
        if (high <= ground + epsilon && low >= ground - epsilon) { return false; }
        int edge = face.landEdge();
        if (edge < 0) {
            var middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
            float nearest = Float.POSITIVE_INFINITY;
            for (int i = 0; i < 6; i++) {
                float distance = edgeDistance(middle, BoardGeometry.corner(tile.coords(), 0, i),
                      BoardGeometry.corner(tile.coords(), 0, i + 1));
                if (distance < nearest) { nearest = distance; edge = i; }
            }
        }
        int direction = BoardGeometry.edgeDirection(edge);
        var next = scene.tile(tile.coords().translated(direction));
        if (next == null || connectingBridge(tile, next, direction) == null) { return false; }
        float rise = roadEdgeElevation(tile, next, direction) - BoardRoadGrade.ground(tile);
        return rise > 0 && high > ground + epsilon;
    }

    private void fan(Vector3[] polygon, float z, Finish finish) {
        Vector3 middle = new Vector3(center.x, center.y, z);
        for (int i = 0; i < polygon.length; i++) {
            triangle(middle, polygon[i], polygon[(i + 1) % polygon.length], finish);
        }
    }

    private void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Finish finish) {
        quad(a, b, c, d, finish, -1);
    }

    private void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Finish finish, int landEdge) {
        triangle(a, b, c, finish, landEdge);
        triangle(a, c, d, finish, landEdge);
    }

    private void triangle(Vector3 a, Vector3 b, Vector3 c, Finish finish) {
        triangle(a, b, c, finish, -1);
    }

    private void triangle(Vector3 a, Vector3 b, Vector3 c, Finish finish, int landEdge) {
        if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 0.000001f) {
            faces.add(new Face(new Vector3(a), new Vector3(b), new Vector3(c), finish, landEdge));
        }
    }

    /** Banks and exposed bars continue the land across their own edge, even on a hex with several shore materials. */
    BoardScene.Surface family(Face face) {
        if (gaps.contains(face)) { return groundBelow(); }
        BoardScene.Tile land = tile.liquid().present() && face.landEdge() >= 0 ? neighbor(scene, face.landEdge()) : null;
        return land != null && !land.liquid().present() ? land.surface() : BoardScene.Surface.values()[relief.family()];
    }

    float height(float x, float y) {
        return height(faces, x, y);
    }

    /** The finished terrain before Rough cover, sharing the rendered vertices rather than a second height field. */
    List<Face> groundFaces() {
        return faces.subList(0, faces.size() - rough.size());
    }

    /** Coatings also reach the walkable upper rim, which is emitted with the wall mesh. */
    List<Face> coatingFaces() {
        List<Face> result = new ArrayList<>();
        for (var face : groundFaces()) {
            if (face.finish() == Finish.TOP || face.finish() == Finish.BED || face.finish() == Finish.SHORE) {
                result.add(face);
            }
        }
        if (!columns && !tile.liquid().present()) {
            var finished = wallFaces == null ? walls(scene, BoardGeometry.floor(scene)) : wallFaces;
            // A road spilling past the plateau keeps its rounded rim, without coating a lower slope or talus.
            float rim = BoardGeometry.groundZ(tile) - .1f * BoardGeometry.level();
            for (var face : BoardTacticalGeometry.lying(finished)) {
                if ((face.finish() == Finish.WALL || face.finish() == Finish.CAP)
                      && Math.min(face.a().z, Math.min(face.b().z, face.c().z)) >= rim) { result.add(face); }
            }
        }
        return result;
    }

    /** The ground or bed a structure can stand on: loose rocks are no foundation, and under ice the floor is the bed. */
    List<Face> foundation() {
        return faces.stream().filter(f -> f.finish() != Finish.OUTCROP && f.finish() != Finish.DRESSING
              && f.finish() != Finish.ICE).toList();
    }

    private float height(List<Face> geometry, float x, float y) {
        return sampleHeight(geometry, x, y, BoardGeometry.groundZ(tile));
    }

    /** Shared triangle sampler for ground support and embedded rock placement. */
    static float sampleHeight(List<Face> geometry, float x, float y, float fallback) {
        return sampleHeight(geometry, x, y, fallback, false);
    }

    /** Solid contact may include ice; placement's existing ground-only queries retain their original behavior. */
    static float sampleHeight(List<Face> geometry, float x, float y, float fallback, boolean includeIce) {
        float height = Float.NEGATIVE_INFINITY, nearby = Float.NEGATIVE_INFINITY;
        // One query shares one settings snapshot; avoid a thread-local lookup for every rejected triangle.
        float tolerance = .001f * BoardGeometry.hexScale();
        for (Face face : geometry) {
            if ((includeIce || face.finish() != Finish.ICE) && face.finish() != Finish.DRESSING) {
                float exact = face.height(x, y, 0);
                if (Float.isFinite(exact)) { height = Math.max(height, exact); }
                else { nearby = Math.max(nearby, face.height(x, y, tolerance)); }
            }
        }
        // Border tolerance recovers rounding gaps, but must not choose the higher of two neighbouring slopes
        // when one triangle actually contains the contact. Road overlays use that same triangle plane.
        return Float.isFinite(height) ? height : Float.isFinite(nearby) ? nearby : fallback;
    }

    /**
     * Pavement parted from the pavement beside it ({@link BoardScene.Tile#pavementApart}): the slab stops short of the side,
     * as the concrete fit moved it, and the ground it lies on shows there. Both hexes of a side do this, so the ground runs
     * between the two slabs. A side that collapsed where the fit's walls mitre round a corner has no gap of its own.
     */
    private void pavementGaps() {
        float scale = BoardGeometry.hexScale(), gap = PAVEMENT_GAP * scale;
        float[] inset = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile next = neighbor(scene, edge);
            if (next != null && tile.pavementApart(next, BoardGeometry.edgeDirection(edge))
                  && moved(edge).dst(moved((edge + 1) % 6)) >= .01f * scale) { inset[edge] = gap; }
        }
        for (int edge = 0; edge < 6; edge++) {
            if (inset[edge] == 0) { continue; }
            int end = (edge + 1) % 6;
            Vector3 a = moved(edge), b = moved(end), inward = inward(edge);
            List<Boundary> strip = List.of(p -> (p.x - a.x) * inward.x + (p.y - a.y) * inward.y - gap,
                  gapEnd(edge, edge, inset, b), gapEnd(edge, end, inset, a));
            List<Face> tops = new ArrayList<>(), others = new ArrayList<>(), ground = new ArrayList<>();
            for (Face face : faces) { (face.finish() == Finish.TOP && !gaps.contains(face) ? tops : others).add(face); }
            subtract(tops, strip, relief, ground::add);
            gaps.addAll(ground);
            faces.clear();
            faces.addAll(others);
            faces.addAll(tops);
            faces.addAll(ground);
        }
    }

    /**
     * The end of an edge's gap at its corner {@code k}, as the {@link Boundary} that holds {@code keep}: square where the
     * cut stops inside the pavement, the third hex there joining both the others; else along the {@link #slabCorner}.
     */
    private Boundary gapEnd(int edge, int k, float[] inset, Vector3 keep) {
        int other = k == edge ? (edge + 5) % 6 : k;
        BoardScene.Tile third = neighbor(scene, other), across = neighbor(scene, edge);
        boolean stops = inset[other] == 0 && third != null && third.surface() == BoardScene.Surface.CONCRETE
              && !third.liquid().present() && third.elevation() == tile.elevation()
              && moved(other).dst(moved((other + 1) % 6)) >= .01f * BoardGeometry.hexScale()
              && !across.pavementApart(third, across.coords().direction(third.coords()));
        return through(moved(k), stops ? moved(k).add(inward(edge)) : slabCorner(k, inset), keep);
    }

    /** The inward unit normal of an edge as the concrete fit moved it. */
    private Vector3 inward(int edge) {
        return moved((edge + 1) % 6).sub(moved(edge)).crs(Vector3.Z).nor().scl(-1);
    }

    /** The side of the line from {@code p} through {@code q} that holds {@code keep}, as a {@link Boundary}. */
    private static Boundary through(Vector3 p, Vector3 q, Vector3 keep) {
        float nx = q.y - p.y, ny = p.x - q.x;
        float sign = (keep.x - p.x) * nx + (keep.y - p.y) * ny > 0 ? -1 : 1;
        return point -> sign * ((point.x - p.x) * nx + (point.y - p.y) * ny);
    }

    /**
     * Where the slab's outline turns at corner k once each edge stands in by its {@code inset}: the two lines' meeting
     * point, so a gap's strips join in a mitre round the corner and end along an edge that keeps its place. A collapsed
     * edge is passed over.
     */
    private Vector3 slabCorner(int k, float[] inset) {
        int before = (k + 5) % 6, after = k;
        Vector3 corner = moved(k);
        float least = .01f * BoardGeometry.hexScale();
        for (int i = 0; i < 5 && moved(before).dst(moved((before + 1) % 6)) < least; i++) { before = (before + 5) % 6; }
        for (int i = 0; i < 5 && moved(after).dst(moved((after + 1) % 6)) < least; i++) { after = (after + 1) % 6; }
        Vector3 first = inward(before), second = inward(after);
        float c1 = first.x * corner.x + first.y * corner.y + inset[before], c2 = second.x * corner.x + second.y * corner.y + inset[after];
        float determinant = first.x * second.y - first.y * second.x;
        if (Math.abs(determinant) < .01f) {
            float by = Math.max(inset[before], inset[after]);
            return new Vector3(corner.x + first.x * by, corner.y + first.y * by, corner.z);
        }
        return new Vector3((c1 * second.y - first.y * c2) / determinant, (first.x * c2 - c1 * second.x) / determinant, corner.z);
    }

    /** The ground under pavement: the nearest that is no pavement or water, on a board paved all round its meadow. */
    private BoardScene.Surface groundBelow() {
        Coords at = tile.coords();
        for (int ring = 1; ring <= SHORE_RINGS; ring++) {
            for (int x = at.getX() - ring; x <= at.getX() + ring; x++) {
                for (int y = at.getY() - ring - 1; y <= at.getY() + ring + 1; y++) {
                    BoardScene.Tile other = at.distance(x, y) == ring ? scene.tile(new Coords(x, y)) : null;
                    if (other != null && !other.liquid().present() && other.detailedGround()
                          && other.surface() != BoardScene.Surface.CONCRETE) { return other.surface(); }
                }
            }
        }
        return BoardScene.Surface.GRASS;
    }

    /** Digs the hex's scenery into its top, which gives way where the scenery's models draw dug ground instead. */
    private void dig() {
        stands = BoardGroundCut.dig(scene, tile, faces, relief, this::diggable);
        dug = faces.stream().anyMatch(face -> face.finish() == Finish.DUG);
    }

    /** Whether scenery may dig {@code face} away: the hex's own top, but not a road over it. */
    private boolean diggable(Face face) {
        return face.finish() == Finish.TOP && !reservedFaces.contains(face);
    }

    /** One side of a convex region ({@link #subtract}): its signed distance at a point, positive outside. */
    interface Boundary { float distance(Vector3 point); }

    /**
     * Removes the part of {@code faces} inside the convex region within all of {@code region}'s boundaries and keeps the
     * rest, cut exactly along it. New vertices take their face's shading from {@code relief}.
     */
    static void subtract(List<Face> faces, List<Boundary> region, BoardRelief relief) {
        subtract(faces, region, relief, face -> { });
    }

    /** {@link #subtract(List, List, BoardRelief)}, handing the parts it removes to {@code removed}. */
    private static void subtract(List<Face> faces, List<Boundary> region, BoardRelief relief,
          java.util.function.Consumer<Face> removed) {
        List<Face> result = new ArrayList<>(faces.size());
        for (var face : faces) {
            List<Vector3> inside = List.of(face.a(), face.b(), face.c());
            // A separating boundary rejects almost every face without allocating clipped polygons.
            boolean outside = false;
            for (Boundary boundary : region) {
                if (inside.stream().allMatch(p -> boundary.distance(p) > 0)) { outside = true; break; }
            }
            if (outside) { result.add(face); continue; }
            for (Boundary boundary : region) {
                if (inside.size() < 3) { break; }
                List<Vector3> kept = new ArrayList<>(), cut = new ArrayList<>();
                for (int i = 0; i < inside.size(); i++) {
                    Vector3 a = inside.get(i), b = inside.get((i + 1) % inside.size());
                    float da = boundary.distance(a), db = boundary.distance(b);
                    (da <= 0 ? cut : kept).add(a);
                    if ((da < 0 && db > 0) || (da > 0 && db < 0)) {
                        float t = da / (da - db);
                        Vector3 p = new Vector3(a).lerp(b, t);
                        relief.interpolateShade(a, b, t, p);
                        kept.add(p);
                        cut.add(p);
                    } else if (da == 0) {
                        kept.add(a);
                    }
                }
                for (int i = 1; i + 1 < kept.size(); i++) {
                    Vector3 a = kept.getFirst(), b = kept.get(i), c = kept.get(i + 1);
                    if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-10f) {
                        result.add(new Face(a, b, c, face.finish(), face.landEdge(), face.scatter()));
                    }
                }
                inside = cut;
            }
            for (int i = 1; i + 1 < inside.size(); i++) {
                Vector3 a = inside.getFirst(), b = inside.get(i), c = inside.get(i + 1);
                if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-10f) {
                    removed.accept(new Face(a, b, c, face.finish(), face.landEdge(), face.scatter()));
                }
            }
        }
        faces.clear();
        faces.addAll(result);
    }

    private static float square(float value) { return value * value; }

    /** Derived once per immutable terrain snapshot; repeated pointer rays reuse the rendered topology. */
    List<Face> walls(BoardScene scene, float floor) {
        return walls(scene, floor, Map.of());
    }

    /** Vertical faces down each side to the lower neighbour: a Classic column's sides, or a Solaris elevator's shaft. */
    List<Face> straightWalls(List<Side> sides) {
        List<Face> result = new ArrayList<>();
        for (var side : sides) {
            var a = side.a(); var b = side.b();
            var lowA = new Vector3(a.x, a.y, side.lowA());
            var lowB = new Vector3(b.x, b.y, side.lowB());
            int drop = Math.round(Math.max(a.z - lowA.z, b.z - lowB.z) / BoardGeometry.level());
            Finish finish = BoardRim.high(tile, BoardGeometry.edgeDirection(side.edge()), drop)
                  ? Finish.OUTCROP : Finish.WALL;
            if (a.z > lowA.z) { result.add(new Face(a, lowA, lowB, finish, side.edge())); }
            if (b.z > lowB.z) { result.add(new Face(a, lowB, b, finish, side.edge())); }
        }
        return result;
    }

    /** Neighbours must have completed construction; only their immutable edge topography is borrowed. */
    List<Face> walls(BoardScene scene, float floor, Map<Coords, BoardSurface> neighbors) {
        if (wallFaces == null || wallFloor != floor) {
            if (columns || BoardFeatures.shaft(tile)) {
                wallFaces = straightWalls(sides(scene, floor, neighbors));
            } else { wallFaces = reserveWalls(relief.walls(sides(scene, floor, neighbors), neighbors), floor); }
            if (canal) { wallFaces = openRamps(wallFaces); }
            wallFaces.addAll(retainingWalls);
            for (int d = 0; d < 6; d++) {
                var lower = scene.tile(tile.coords().translated(d));
                if (lower == null || lower.elevation() >= tile.elevation()) { continue; }
                for (var tunnel : BoardTunnel.entrances(scene, lower)) { tunnel.cut(wallFaces, relief); }
            }
            wallFloor = floor;
        }
        return wallFaces;
    }

    private void cuts(Vector3 a, Vector3 b, TreeSet<Float> cuts) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float length2 = dx * dx + dy * dy;
        for (Face face : edgeTopography) {
            for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                if (Math.abs(dx * (point.y - a.y) - dy * (point.x - a.x)) < 0.02f) {
                    float t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / length2;
                    if (t > 0.001f && t < 0.999f) {
                        cuts.add(Math.round(t * 100000) / 100000f);
                    }
                }
            }
        }
    }

    /** See {@link #shoreShifts}. */
    private void settleRelief() {
        if (columns) { return; }
        // Cached water geometry also needs the current adjoining rail grades when its query context is rebuilt.
        railGrades = grades(false).stream().filter(grade -> grade.tile().railExits() != 0).toList();
        var passages = new ArrayList<BoardBridge.Approach>();
        for (int d = -1; d < 6; d++) {
            var source = d < 0 ? tile : scene.neighbor(tile, d);
            if (BoardBridge.feature(source) != null && BoardBridge.family(source) != BoardBridge.Family.NATURAL) {
                passages.addAll(BoardBridge.approaches(scene, source));
            }
        }
        bridgePassages = List.copyOf(passages);
        for (int k = 0; k < 6; k++) {
            shoreShifts[k] = tile.backdrop().present() ? new float[2] : relief.shoreShift(k);
            wetCliffs[k] = relief.wetCliff(k);
            var next = scene.neighbor(tile, BoardGeometry.edgeDirection(k));
            if (tile.liquid().present() || next != null && next.liquid().present()) { waterSeamEdges |= 1 << k; }
        }
    }

    /** Corner k as the shore moves it (see {@link BoardRelief#shoreShift}). */
    private Vector3 moved(int k) {
        if (columns) { return new Vector3(corners[k]); }
        return new Vector3(corners[k]).add(shoreShifts[k][0], shoreShifts[k][1], 0);
    }

    /** Road profiles and graded banks precede relief; flat concrete and other ground already include the fit. */
    private Vector3 topographyCorner(int k) {
        return moved(Math.floorMod(k, 6));
    }

    /** Whether connected liquid continues across this edge, which a bank never crosses and only a fall descends. */
    boolean mouth(int edge) {
        return (openMouths & 1 << edge) != 0;
    }

    /**
     * Only the higher column contributes a shared wall; road gates meet at the same height. Under a fall the wall
     * stands beneath its crest, from the ledge the water pours over down to the pool below.
     */
    List<Side> sides(BoardScene scene, float floor) {
        return sides(scene, floor, Map.of());
    }

    /** A cut has two heights at its shoulder. Select the interval's roof inside it, then use that roof's endpoint. */
    private float edgeHeight(Vector3 point, Vector3 interval) {
        if (interval == null) { return height(edgeTopography, point.x, point.y); }
        float x = interval.x + (center.x - interval.x) * .0001f;
        float y = interval.y + (center.y - interval.y) * .0001f;
        float highest = Float.NEGATIVE_INFINITY, result = Float.NEGATIVE_INFINITY;
        for (Face face : edgeTopography) {
            float sample = face.height(x, y, 0);
            if (sample > highest) {
                // Merging nearly coincident edge cuts can put the endpoint just beyond this triangle.
                // Continue the selected roof's plane instead of falling back to the higher uncut slab.
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                float endpoint = face.a().z - (normal.x * (point.x - face.a().x)
                      + normal.y * (point.y - face.a().y)) / normal.z;
                if (Float.isFinite(endpoint)) { highest = sample; result = endpoint; }
            }
        }
        return Float.isFinite(result) ? result : height(edgeTopography, point.x, point.y);
    }

    /** The build's completed surfaces save reconstructing neighbouring tops; the lookup is not retained. */
    List<Side> sides(BoardScene scene, float floor, Map<Coords, BoardSurface> neighbors) {
        if (tile.backdrop().present()) { return List.of(); }
        List<Side> result = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            // The edge as the shore moves its corners, where both hexes' tops meet.
            Vector3 a = moved(edge), b = moved((edge + 1) % 6);
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            // An aerospace picking plane has no solid ground to hide this column's exposed side.
            if (neighbor != null && neighbor.backdrop().present()) { neighbor = null; }
            if (mouth(edge) && neighbor != null && neighbor.elevation() == tile.elevation()) {
                continue; // Beds of one level share their mouth's profile and their banks' top: nothing stands between.
            }
            // Water at this dry hex's level owns the bank between them: its sculpted shore rises to this hex's top.
            if (!columns && neighbor != null && neighbor.liquid().present() && !tile.liquid().present()
                  && neighbor.elevation() == tile.elevation() && relief.sculpted()) { continue; }
            BoardSurface adjacent = neighbor == null ? null : neighbors.get(neighbor.coords());
            if (neighbor != null && (adjacent == null || columns && !adjacent.columns)) {
                adjacent = columns ? columns(scene, neighbor, lod) : new BoardSurface(scene, neighbor, false, lod);
            }
            // The basin owns the submerged cliff. The upper column stops at its rim instead of drawing
            // another wall over the same concrete quay or drowned cliff down to the seabed.
            boolean basin = !columns && adjacent != null && adjacent.wetCliffs[(edge + 3) % 6];
            // Compare both profiles at the same edge parameter, in each profile's own coordinate space.
            // Sampling a road at the shifted shore instead samples inside its ramp and leaves its wall short.
            Vector3 roofA = topographyCorner(edge), roofB = topographyCorner(edge + 1);
            Vector3 footA = adjacent == null ? null : adjacent.topographyCorner(edge + 4);
            Vector3 footB = adjacent == null ? null : adjacent.topographyCorner(edge + 3);
            TreeSet<Float> cuts = new TreeSet<>(List.of(0f, 1f));
            cuts(roofA, roofB, cuts);
            if (adjacent != null) {
                adjacent.cuts(footA, footB, cuts);
            }
            // The stretch of the edge a crest spans, where its own wall replaces the straight one.
            Crest crest = crests[edge];
            float spanA = crest == null ? 2 : joinA[edge] != null ? 0 : along(a, b, crest.a());
            float spanB = crest == null ? 2 : joinB[edge] != null ? 1 : along(a, b, crest.b());
            List<Float> points = new ArrayList<>(cuts);
            int previous = 0;
            for (int i = 1; i < points.size(); i++) {
                float from = points.get(previous), to = points.get(i);
                // Merge nearly coincident cuts into the next interval instead of leaving a slit in the wall.
                if (to - from < 0.0001f) { continue; }
                previous = i;
                if (from > spanA - 0.0001f && to < spanB + 0.0001f) { continue; }
                Vector3 start = new Vector3(a).lerp(b, from);
                Vector3 end = new Vector3(a).lerp(b, to);
                boolean graded = ramps != 0 || roadCut || adjacent != null && (adjacent.ramps != 0 || adjacent.roadCut);
                // Graded roads and gentle liquid mouths need their exact endpoints, including beside water;
                // inset samples shorten the wall at the bank's breaks and leave pinholes along the ramp.
                boolean inset = !graded && !waterSlope(tile, neighbor);
                float sampleA = inset ? from + (to - from) * .001f : from;
                float sampleB = inset ? to - (to - from) * .001f : to;
                // A road cut is discontinuous; both endpoints must come from this interval's own roof.
                Vector3 interval = graded ? new Vector3(roofA).lerp(roofB, (from + to) / 2) : null;
                start.z = edgeHeight(new Vector3(roofA).lerp(roofB, sampleA), interval);
                end.z = edgeHeight(new Vector3(roofA).lerp(roofB, sampleB), interval);
                Vector3 lowerInterval = graded && adjacent != null ? new Vector3(footA).lerp(footB, (from + to) / 2) : null;
                float lowA = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                      : adjacent.edgeHeight(new Vector3(footA).lerp(footB, sampleA), lowerInterval);
                float lowB = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                      : adjacent.edgeHeight(new Vector3(footA).lerp(footB, sampleB), lowerInterval);
                float dA = start.z - lowA, dB = end.z - lowB;
                if (dA < -0.001f && dB > 0.001f) {
                    float t = -dA / (dB - dA);
                    start.lerp(end, t);
                    lowA = start.z;
                } else if (dB < -0.001f && dA > 0.001f) {
                    float t = dA / (dA - dB);
                    end.set(new Vector3(start).lerp(end, t));
                    lowB = end.z;
                }
                if (Math.max(start.z - lowA, end.z - lowB) > 0.01f) {
                    result.add(new Side(start, end, Math.min(start.z, lowA), Math.min(end.z, lowB), edge));
                }
            }
            if (crest != null) {
                for (int i = 0; i < SHORE_SEGMENTS; i++) {
                    Vector3 start = new Vector3(bedOutline[edge * SHORE_SEGMENTS + i]);
                    Vector3 end = new Vector3(bedOutline[(edge * SHORE_SEGMENTS + i + 1) % bedOutline.length]);
                    float lowA = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                          : adjacent.height(adjacent.edgeTopography, start.x, start.y);
                    float lowB = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                          : adjacent.height(adjacent.edgeTopography, end.x, end.y);
                    if (Math.max(start.z - lowA, end.z - lowB) > 0.01f) {
                        result.add(new Side(start, end, Math.min(start.z, lowA), Math.min(end.z, lowB), edge));
                    }
                }
            }
        }
        return result;
    }

    /** Where p lies along the edge from a to b, 0 at a and 1 at b. */
    private static float along(Vector3 a, Vector3 b, Vector3 p) {
        float dx = b.x - a.x, dy = b.y - a.y;
        return ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy);
    }
}
