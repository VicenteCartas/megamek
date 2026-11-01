/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;

/**
 * The ground a carriageway reserves: level across its width, graded only along its course. A junction's core keeps its
 * hex's level and each connected arm ramps along its straight approach to the height both hexes share on their edge;
 * a two-way road keeps its middle level and climbs over the outer part of its course (see {@link #rampStart}).
 * Classic ramps are straight. Enhanced ramps ease in and out: level where they leave the flat ground, and meeting their
 * neighbour's ramp at its edge with one slope both hexes derive alike. Terrain gives way to this surface.
 */
final class BoardRoadGrade {
    /** Lateral reach of the strips that let a wide carriageway past its edge's corners, in tile pixels. */
    private static final float PAST_CORNER = 64;
    /** How far past its edge a bridge mouth reserves the hexes beside the deck, in tile pixels. */
    private static final float PAST_MOUTH = 8;
    /** Rows across each eased ramp, so the mesh follows its curve. */
    private static final int EASED_ROWS = 8;
    /**
     * Share of a hex's width each side takes for a ramp it shares with the next hex: at .3 each hex climbs over its
     * outer 30% and keeps the middle 40% level. Adjust here; every graded road uses it.
     */
    static final float SHARED_RAMP = .3f;
    /**
     * Headroom, in levels, a carriageway keeps clear above its surface: a tall truck with margin, at six metres a level.
     * Terrain above it may stand over the road's verge; only what lies below it gives way.
     */
    static final float CLEARANCE = .75f;
    /**
     * The deepest, in levels, ground may lie under a carriageway's edge and still be closed up to it (Enhanced); below
     * lies a drop the road does not reach out over.
     */
    static final float FILL = 1;
    /** Where a bridge approach leaves the level: the inset half of the hex, as the deck's footing ramp spans it. */
    static final float BRIDGE_START = .5f;

    private final BoardScene.Tile tile;
    private final float cx, cy, level;
    /** World height where each direction's arm meets its edge; the hex's level without a graded arm. */
    private final float[] edges = new float[6];
    private final int arms;
    /** A two-way course's stations at its ends and middle; null for arms ramping from a level core. */
    private final BoardRoad course;
    private final int first, last;
    private final float firstStation, lastStation;
    /** Where each direction's ramp starts, as a share of its centre-to-edge run (see {@link #rampStart}). */
    private final float[] starts = new float[6];
    private final Area region;
    /** The region's outline as segments, {ax, ay, bx, by} after each other, in world coordinates. */
    private final float[] outline;
    /** The region's bounds: {minX, minY, maxX, maxY}. */
    private final float[] bounds;
    /** Lowest and highest world height of the surface. */
    private final float low, high;
    /** Enhanced ramps ease in and out; Classic ramps are straight. */
    private final boolean eased;
    /** Outward rise per run, in world units, where each eased ramp meets its edge. */
    private final float[] slopes = new float[6];
    /** The directions whose ramp continues a bridge deck's plane. */
    private int planar;
    /** Whether the carriageway is built in concrete (see {@link #poured()}). */
    private final boolean poured;
    /** Whether its bed is a loose road's track only (see {@link #track()}). */
    private final boolean track;
    /** Whether a natural carriageway ramps down onto an apron (see {@link #intoApron()}). */
    private boolean intoApron;

    private BoardRoadGrade(BoardScene scene, BoardScene.Tile tile, int arms, boolean carriageway, boolean eased) {
        this.tile = tile;
        this.arms = arms;
        this.eased = eased;
        cx = BoardGeometry.centerX(tile.coords());
        cy = BoardGeometry.centerY(tile.coords());
        level = tile.elevation() * BoardGeometry.level();
        for (int d = 0; d < 6; d++) {
            edges[d] = (arms & 1 << d) == 0 ? level
                  : edgeLevel(tile, scene.neighbor(tile, d), d) * BoardGeometry.level();
        }
        BoardRoad road = carriageway ? BoardRoad.way(scene, tile) : null;
        int exits = arms & 63;
        int a = Integer.numberOfTrailingZeros(exits), b = Integer.numberOfTrailingZeros(exits & ~(1 << a));
        float sa = Float.NaN, sb = Float.NaN;
        boolean through = road != null && Integer.bitCount(exits) == 2 && Math.floorMod(b - a, 6) != 1
              && Math.floorMod(a - b, 6) != 1;
        if (through) {
            float[] pa = RailRoute.border(a), pb = RailRoute.border(b);
            sa = road.throughStation(pa[0], pa[1]);
            sb = road.throughStation(pb[0], pb[1]);
            through = Float.isFinite(sa) && Float.isFinite(sb) && sa != sb;
        }
        course = through ? road : null;
        first = a;
        last = b;
        firstStation = sa;
        lastStation = sb;
        boolean concrete = tile.surface() == BoardScene.Surface.CONCRETE, crossing = false, natural = false;
        for (int d = 0; d < 6; d++) {
            starts[d] = (arms & 1 << d) == 0 ? 1 : rampStart(scene, tile, d);
            var next = scene.neighbor(tile, d);
            if ((arms & 1 << d) != 0 && next != null && BoardSurface.connectingBridge(tile, next, d) != null) {
                planar |= 1 << d;
            } else if (edges[d] != level) {
                // A natural road ramping down onto an apron stays natural, cut into its own ground: the apron casts its
                // own half of the ramp. One ramping up to an apron stands on a cast base.
                boolean across = (next.surface() == BoardScene.Surface.CONCRETE) != concrete
                      && (concrete || !apron(tile, next, d) || next.elevation() > tile.elevation());
                intoApron |= !concrete && !across && apron(tile, next, d);
                crossing |= across;
                natural |= !across && !concrete;
            }
        }
        poured = crossing && !natural;
        track = road != null && BoardRoad.trackBed(scene, tile);
        region = region(scene, tile, road, arms);
        outline = outline(region);
        var box = region.getBounds2D();
        bounds = new float[] { (float) box.getMinX(), (float) box.getMinY(), (float) box.getMaxX(), (float) box.getMaxY() };
        float bottom = level, top = level;
        for (float edge : edges) { bottom = Math.min(bottom, edge); top = Math.max(top, edge); }
        low = bottom;
        high = top;
        for (int d = 0; eased && d < 6; d++) {
            var next = scene.neighbor(tile, d);
            float rise = edges[d] - level;
            if (rise == 0 || next == null) { continue; }
            if (BoardSurface.connectingBridge(tile, next, d) != null) { continue; }
            // A ramp ending at a level crossing eases fully into it. Otherwise both hexes take the steepest slope of
            // one ease over both their ramps, from their estimates of each other's ramp length.
            int back = (d + 3) % 6;
            float across = (edgeLevel(next, tile, back) - next.elevation()) * BoardGeometry.level();
            if (across == 0) { continue; }
            float runs = (1 - starts[d]) * inradius(d) + (1 - rampStart(scene, next, back)) * inradius(back);
            slopes[d] = 1.5f * (rise - across) / (runs * BoardGeometry.hexScale());
        }
    }

    /**
     * Where the ramp towards this exit starts, as a share of its centre-to-edge run. A junction keeps its core and a
     * roundabout its ring. A road sharing its ramp with the next hex keeps its middle level ({@link #SHARED_RAMP});
     * one carrying the whole ramp beside a level junction starts at its centre, as a climbing return turn does. A bridge
     * approach keeps the deck's plane over the hex's inset half.
     */
    private static float rampStart(BoardScene scene, BoardScene.Tile tile, int direction) {
        var next = scene.neighbor(tile, direction);
        // A bridge's approach is one plane over the inset halves of both hexes, as its footing builds it.
        if (next != null && BoardSurface.connectingBridge(tile, next, direction) != null) { return BRIDGE_START; }
        int exits = arms(scene, tile);
        if (Integer.bitCount(exits) >= 3) { return BoardRoad.roundabout(exits) ? .75f : BoardRoad.CORRIDOR; }
        if (BoardRoad.climbingTurn(exits, ramps(scene, tile))) { return 0; }
        // Measured from the centre, as a share of the centre-to-edge run (half the hex's width).
        return next != null && partners(tile, next, direction) && junction(tile) == junction(next)
              ? 1 - 2 * SHARED_RAMP : 0;
    }

    /** Distance in tile pixels from the hex centre to the edge in this direction. */
    private static float inradius(int direction) {
        int edge = Math.floorMod(1 - direction, 6);
        float ax = HexTile.cornerOffsetX(edge, BoardGeometry.TILE_WIDTH);
        float ay = HexTile.cornerOffsetY(edge, BoardGeometry.TILE_HEIGHT);
        float bx = HexTile.cornerOffsetX((edge + 1) % 6, BoardGeometry.TILE_WIDTH);
        float by = HexTile.cornerOffsetY((edge + 1) % 6, BoardGeometry.TILE_HEIGHT);
        return Math.abs(ax * by - ay * bx) / (float) Math.hypot(bx - ax, by - ay);
    }

    /** The world length of this direction's ramp, from where it leaves the level to its edge. */
    private float run(int direction) {
        float half = course != null ? Math.abs(lastStation - firstStation) / 2 : inradius(direction);
        return (1 - starts[direction]) * half * BoardGeometry.hexScale();
    }

    /**
     * Rise of this direction's ramp at the share t of its run from the centre: level before its start, then straight,
     * or eased (a monotone Hermite curve, level where it leaves the flat ground).
     */
    private float profile(int direction, float t) {
        float rise = edges[direction] - level, u = Math.clamp((t - starts[direction]) / (1 - starts[direction]), 0, 1);
        // A bridge approach continues the deck's own plane.
        if (!eased || (planar & 1 << direction) != 0) { return rise * u; }
        float end = Math.clamp(slopes[direction] * run(direction), Math.min(0, 3 * rise), Math.max(0, 3 * rise));
        return rise * u * u * (3 - 2 * u) + end * u * u * (u - 1);
    }

    /**
     * The grade of a hex's carriageway, or of the concrete apron a road ramps into; null where neither reserves ground.
     * {@code eased} selects Enhanced's ramps.
     */
    static BoardRoadGrade of(BoardScene scene, BoardScene.Tile tile, boolean eased) {
        if (tile == null) { return null; }
        var grades = Cache.of(scene).grades;
        long key = (long) tile.coords().getX() << 33 | (long) tile.coords().getY() << 1 | (eased ? 1 : 0);
        Optional<BoardRoadGrade> known = grades.get(key);
        if (known == null) {
            int arms = arms(scene, tile);
            known = arms == 0 && !BoardRoad.carriageway(tile) ? Optional.empty()
                  : Optional.of(new BoardRoadGrade(scene, tile, arms, BoardRoad.carriageway(tile), eased));
            grades.putIfAbsent(key, known);
        }
        return known.orElse(null);
    }

    /** Each dry hex's emitted road surface by its coordinates, per scene snapshot as the grades (BoardSurface#road). */
    static Map<Coords, List<BoardSurface.Face>> roads(BoardScene scene) {
        return Cache.of(scene).roads;
    }

    /**
     * Grades are immutable and each hex builds its own and its neighbours': share them per scene snapshot, as the
     * concrete fit is shared; so are the road surfaces they mesh, which neighbours sample. Two snapshots stay cached
     * while workers move from one to the next.
     */
    private record Cache(WeakReference<List<BoardScene.Tile>> tiles, float scale, float level,
          Map<Long, Optional<BoardRoadGrade>> grades, Map<Coords, List<BoardSurface.Face>> roads) {
        private static volatile Cache current, previous;

        boolean matches(BoardScene scene) {
            return tiles.get() == scene.tiles() && scale == BoardGeometry.hexScale() && level == BoardGeometry.level();
        }

        static Cache of(BoardScene scene) {
            Cache first = current, second = previous;
            if (first != null && first.matches(scene)) { return first; }
            if (second != null && second.matches(scene)) { return second; }
            synchronized (Cache.class) {
                if (current != null && current.matches(scene)) { return current; }
                previous = current;
                current = new Cache(new WeakReference<>(scene.tiles()), BoardGeometry.hexScale(), BoardGeometry.level(),
                      new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
                return current;
            }
        }
    }

    /** The directions a hex's carriageway or apron runs out by; 0 where it reserves no ground. */
    private static int arms(BoardScene scene, BoardScene.Tile tile) {
        if (tile == null || tile.liquid().present() || tile.ultraSublevel() || tile.backdrop().present()) { return 0; }
        if (BoardRoad.carriageway(tile)) { return tile.wayExits() & 63; }
        int arms = 0;
        for (int d = 0; d < 6; d++) {
            var next = scene.neighbor(tile, d);
            if (next != null && apron(next, tile, (d + 3) % 6)) { arms |= 1 << d; }
        }
        return arms;
    }

    /** The edges a hex's carriageway ramps to: it meets them away from the hex's level. */
    static int ramps(BoardScene scene, BoardScene.Tile tile) {
        int arms = arms(scene, tile), result = 0;
        for (int d = 0; d < 6; d++) {
            if ((arms & 1 << d) != 0 && edgeLevel(tile, scene.neighbor(tile, d), d) != tile.elevation()) {
                result |= 1 << d;
            }
        }
        return result;
    }

    /**
     * The level at which a carriageway meets the edge in this direction. A junction keeps its crossing level, so the
     * road beyond it carries the whole ramp; two roads alike share it, meeting halfway. A road ending at unpaved ground
     * carries the whole ramp to that ground's level, which keeps its own shape. A bridge deck keeps its own approach
     * height.
     */
    static float edgeLevel(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        if (next == null || tile.ultraSublevel() || next.ultraSublevel() || tile.liquid().present()) {
            return tile.elevation();
        }
        if (BoardSurface.connectingBridge(tile, next, direction) != null) {
            return BoardSurface.roadEdgeElevation(tile, next, direction);
        }
        if (!graded(tile, next, direction)) { return tile.elevation(); }
        if (!partners(tile, next, direction)) { return next.elevation(); }
        boolean junction = junction(tile), across = junction(next);
        return junction == across ? (tile.elevation() + next.elevation()) / 2f
              : junction ? tile.elevation() : next.elevation();
    }

    private static boolean junction(BoardScene.Tile tile) { return Integer.bitCount(tile.wayExits() & 63) >= 3; }

    /**
     * Graded to each other across this edge: two carriageways sharing it (see {@link #partners}), or a road exit onto
     * dry ground within two levels.
     */
    static boolean graded(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        if (next == null || next.liquid().present() || next.ultraSublevel() || next.backdrop().present()
              || tile.liquid().present()) { return false; }
        // A road onto this hex's bridge deck leaves the ground beneath the deck as it is.
        if (BoardSurface.connectingBridge(next, tile, (direction + 3) % 6) != null) { return false; }
        return partners(tile, next, direction) || (tile.wayExits() & 1 << direction) != 0
              && Math.abs(tile.elevation() - next.elevation()) <= 2;
    }

    /** Both hexes reserve ground at this edge: reciprocal carriageways, or a road ending at a roadless concrete apron. */
    private static boolean partners(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        int reverse = (direction + 3) % 6;
        return (tile.wayExits() & 1 << direction) != 0 && (next.wayExits() & 1 << reverse) != 0
              || apron(tile, next, direction) || apron(next, tile, reverse);
    }

    /** A road ending at a roadless concrete hex within two levels ramps into that apron. */
    static boolean apron(BoardScene.Tile road, BoardScene.Tile apron, int direction) {
        return (road.wayExits() & 1 << direction) != 0 && !road.liquid().present()
              && apron.surface() == BoardScene.Surface.CONCRETE && !BoardRoad.carriageway(apron)
              && !apron.liquid().present() && Math.abs(road.elevation() - apron.elevation()) <= 2
              && BoardSurface.connectingBridge(road, apron, direction) == null;
    }

    /**
     * Whether a road leaving a hex at d ends there on a cliff's top: towards roadless dry ground further down than a road
     * ramps (#graded), and no bridge's deck carries it on.
     */
    static boolean overDrop(BoardScene.Tile tile, BoardScene.Tile next, int d) {
        return next != null && !next.liquid().present() && !BoardRoad.carriageway(next)
              && next.groundLevel() < tile.groundLevel() - 2 && BoardSurface.connectingBridge(tile, next, d) == null;
    }

    BoardScene.Tile tile() { return tile; }

    /** The reserved ground, in world coordinates; callers receive their own copy. */
    Area region() { return new Area(region); }

    /** The top of the space the road keeps clear at a world point (see {@link #CLEARANCE}). */
    float clearanceTop(float x, float y) { return height(x, y) + CLEARANCE * BoardGeometry.level(); }

    /** Whether this grade reserves the world point. */
    boolean contains(float x, float y) {
        if (x < bounds[0] || y < bounds[1] || x > bounds[2] || y > bounds[3]) { return false; }
        // The region is the polygon its outline bounds: even-odd crossings of the outline, cheaper than the Area's own.
        boolean inside = false;
        for (int i = 0; i < outline.length; i += 4) {
            float ay = outline[i + 1], by = outline[i + 3];
            if (ay > y != by > y && x < outline[i] + (y - ay) * (outline[i + 2] - outline[i]) / (by - ay)) { inside = !inside; }
        }
        return inside;
    }

    /** Whether a hex's Enhanced carriageway reserves part of another hex's ground. */
    static boolean reaches(BoardScene scene, BoardScene.Tile source, BoardScene.Tile tile) {
        var grade = of(scene, source, true);
        if (grade == null) { return false; }
        Area cell = new Area(hexagon(1)).createTransformedArea(new AffineTransform(BoardGeometry.hexScale(), 0, 0,
              BoardGeometry.hexScale(), BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords())));
        cell.intersect(grade.region());
        return !cell.isEmpty();
    }

    /** The region's bounds, {minX, minY, maxX, maxY}; do not modify. */
    float[] bounds() { return bounds; }

    /** The region's outline as segments, {ax, ay, bx, by} after each other, in world coordinates; do not modify. */
    float[] outline() { return outline; }


    /** Lowest world height of the surface. */
    float low() { return low; }

    /** Highest world height of the surface. */
    float high() { return high; }

    /** Whether an arm ramps between a bridge deck and the ground: its footing carries it, not the ground. */
    boolean bridgeRamp() {
        for (int d = 0; d < 6; d++) {
            if ((planar & 1 << d) != 0 && edges[d] != level) { return true; }
        }
        return false;
    }

    /**
     * Whether the carriageway is built in concrete: a ramp of it crosses the edge of concrete, from or onto natural
     * ground. Natural ground neither banks into its reserved ground nor stands over it, and its cut faces are concrete.
     * A carriageway that also ramps between natural hexes keeps their banks.
     */
    boolean poured() { return poured; }

    /**
     * Whether the ground reserved is a loose road's track only (BoardRoad#terrainBed): the slopes beside a dirt or
     * gravel road stand back from its track and cut a natural bank, which no containment wall retains.
     */
    boolean track() { return track; }

    /**
     * Whether this natural carriageway ramps down onto an apron, cut into its own ground: its bank cuts that ground over
     * the whole range it spans, which the steps beside it then clear as well (BoardRelief#push).
     */
    boolean intoApron() { return intoApron; }

    /** Whether any arm leaves the hex's level. */
    boolean graded() {
        for (int d = 0; d < 6; d++) {
            if (edges[d] != level) { return true; }
        }
        return false;
    }

    /** The carriageway's height at a world point, level across its width. */
    float height(float x, float y) {
        // Past a bridge's edge, beside its deck, the approach runs on in the deck's plane down (or up) to the deck.
        float past = 0;
        for (int d = 0; planar != 0 && d < 6; d++) {
            if ((planar & 1 << d) == 0 || edges[d] == level) { continue; }
            float share = BoardGeometry.share(tile.coords(), d, x, y);
            if (share > 1) { past += (edges[d] - level) * Math.min(share - 1, 1 - starts[d]) / (1 - starts[d]); }
        }
        if (course != null) { return past + courseHeight(x, y); }
        // Each arm ramps along its own run. Where two adjacent arms overlap, each weighs by its nearness to its own
        // edge, so every edge keeps exactly the height both hexes share there.
        float rise = 0, weights = 0;
        for (int d = 0; d < 6; d++) {
            if ((arms & 1 << d) == 0) { continue; }
            float share = BoardGeometry.share(tile.coords(), d, x, y);
            float t = Math.clamp((share - starts[d]) / (1 - starts[d]), 0, 1);
            if (t <= 0) { continue; }
            float gap = Math.max(1 - share, 1e-5f), weight = t / (gap * gap);
            rise += weight * profile(d, share);
            weights += weight;
        }
        return past + (weights == 0 ? level : level + rise / weights);
    }

    /**
     * Along a two-way course, through the hex's level at its middle station. Its cross-sections at both borders run
     * along the edge, as both crossings of a graded course are square, so both hexes share one height along it.
     */
    private float courseHeight(float x, float y) {
        float scale = BoardGeometry.hexScale(), middle = (firstStation + lastStation) / 2;
        float station = course.throughStation((x - cx) / scale, (y - cy) / scale);
        boolean firstHalf = (station - middle) * (firstStation - middle) > 0;
        int direction = firstHalf ? first : last;
        float end = firstHalf ? firstStation : lastStation;
        float t = Math.clamp((station - middle) / (end - middle), 0, 1);
        return level + profile(direction, t);
    }

    /** Lines the surface bends across, in world coordinates: the mesh keeps a vertex row on each. */
    List<float[]> creases() {
        List<float[]> result = new ArrayList<>();
        // Where a bridge's edge runs on past its corners, beside the deck, a course's grade bends into the deck's plane.
        for (int d = 0; course != null && planar != 0 && d < 6; d++) {
            if ((planar & 1 << d) == 0 || edges[d] == level) { continue; }
            float[] line = crossSection(d, 1), edge = crossSection(d, 1, 0);
            result.add(new float[] { line[0], line[1], edge[0], edge[1] });
            result.add(new float[] { edge[2], edge[3], line[2], line[3] });
        }
        int rows = eased ? EASED_ROWS : 1;
        if (course != null) {
            // The course's cross-sections at the ramp's rows and at its own vertices, which keep a curved grade
            // within its lanes.
            float middle = (firstStation + lastStation) / 2, scale = BoardGeometry.hexScale();
            List<Float> stations = new ArrayList<>(List.of(middle));
            for (int d : new int[] { first, last }) {
                if (edges[d] == level) { continue; }
                float end = d == first ? firstStation : lastStation;
                for (int k = 0; k < rows; k++) {
                    float t = starts[d] + (1 - starts[d]) * k / rows;
                    if (t > 0) { stations.add(middle + (end - middle) * t); }
                }
            }
            for (float[] section : course.crossSections(stations)) {
                float s = section[0];
                boolean firstHalf = (s - middle) * (firstStation - middle) > 0;
                if ((s - firstStation) * (s - lastStation) >= 0 || edges[firstHalf ? first : last] == level
                      && (s != middle || edges[firstHalf ? last : first] == level)) { continue; }
                float x = section[1], y = section[2], nx = section[3] * PAST_CORNER, ny = section[4] * PAST_CORNER;
                result.add(new float[] { cx + (x - nx) * scale, cy + (y - ny) * scale,
                      cx + (x + nx) * scale, cy + (y + ny) * scale });
            }
            return result;
        }
        for (int d = 0; d < 6; d++) {
            if (edges[d] == level) { continue; }
            for (int k = 0; k < rows; k++) {
                float run = starts[d] + (1 - starts[d]) * k / rows;
                if (run > 0) { result.add(crossSection(d, run)); }
            }
        }
        return result;
    }

    /** A line parallel to this direction's edge at the given share of its run, across any carriageway. */
    private float[] crossSection(int direction, float run) {
        return crossSection(direction, run, PAST_CORNER);
    }

    /** That line, reaching {@code past} tile pixels beyond the edge's corners. */
    private float[] crossSection(int direction, float run, float past) {
        int edge = Math.floorMod(1 - direction, 6);
        float scale = BoardGeometry.hexScale();
        float ax = HexTile.cornerOffsetX(edge, BoardGeometry.TILE_WIDTH) * run;
        float ay = HexTile.cornerOffsetY(edge, BoardGeometry.TILE_HEIGHT) * run;
        float bx = HexTile.cornerOffsetX((edge + 1) % 6, BoardGeometry.TILE_WIDTH) * run;
        float by = HexTile.cornerOffsetY((edge + 1) % 6, BoardGeometry.TILE_HEIGHT) * run;
        float dx = bx - ax, dy = by - ay, length = (float) Math.hypot(dx, dy), reach = past / length;
        return new float[] { cx + (ax - dx * reach) * scale, cy + (ay - dy * reach) * scale,
              cx + (bx + dx * reach) * scale, cy + (by + dy * reach) * scale };
    }

    /**
     * The carriageway's bed (BoardRoad#terrainBed) with its shoulder, within its own hex and past its edges' corners,
     * where a wide corridor reserves a sliver of the hex beyond; never across the edge it crosses. A bridge deck
     * carries the road on across its edge, so beside the deck the mouth's full width stays reserved a little further.
     * An apron reserves straight lanes.
     */
    private Area region(BoardScene scene, BoardScene.Tile tile, BoardRoad road, int arms) {
        Area local;
        if (road != null) {
            local = road.terrainBed(scene, tile).footprint(BoardRoad.SHOULDER);
            Area ext = new Area(hexagon(1));
            for (int d = 0; d < 6; d++) {
                if ((arms & 1 << d) != 0) { ext.add(new Area(strip(d))); }
            }
            // A course ending past its edge belongs to the hex across it, whatever strip it reaches into.
            for (int d = 0; d < 6; d++) {
                if ((arms & 1 << d) != 0) { ext.intersect(new Area(inside(d))); }
            }
            local.intersect(ext);
            int exits = arms & 63;
            if (!eased && BoardRoad.climbingTurn(exits, ramps(scene, tile))) {
                // A climbing return turn runs through the centre (BoardRoad#of); in a column the wedge between its two
                // ramps is cut with them, rather than left standing as a pillar between the cuts. Natural ground
                // between them banks down to both instead (BoardRoadBank).
                int a = Integer.numberOfTrailingZeros(exits), b = Integer.numberOfTrailingZeros(exits & ~(1 << a));
                int corner = Math.floorMod(1 - (Math.floorMod(b - a, 6) == 1 ? a : b), 6);
                float[] pa = RailRoute.border(a), pb = RailRoute.border(b);
                Path2D.Float wedge = new Path2D.Float();
                wedge.moveTo(0, 0);
                wedge.lineTo(pa[0], pa[1]);
                wedge.lineTo(HexTile.cornerOffsetX(corner, BoardGeometry.TILE_WIDTH),
                      HexTile.cornerOffsetY(corner, BoardGeometry.TILE_HEIGHT));
                wedge.lineTo(pb[0], pb[1]);
                wedge.closePath();
                local.add(new Area(wedge));
            }
            float[] seals = new float[6];
            for (int d = 0; d < 6; d++) {
                var next = scene.neighbor(tile, d);
                if ((arms & 1 << d) == 0 || next == null || BoardSurface.connectingBridge(tile, next, d) == null) {
                    continue;
                }
                // The parapets of a ramp up from a deck run straight over the whole ramp (BoardBridgeFooting), clear
                // of the ground; on a turning road they bend with it, and a solid ramp down onto lower ground has its
                // own concrete sides.
                if (edges[d] < level && !BoardRoad.turning(tile.roadExits())) {
                    Area ramp = new Area(lane(d, BoardSurface.roadMouth(tile, next, d), BRIDGE_START, 1));
                    ramp.intersect(new Area(hexagon(1)));
                    local.add(ramp);
                }
                // A ramp up onto this bank meets the deck below the bank's own wall on the edge: the mouth reserves a
                // pixel either side of the edge, as the shore fit can move the wall off the lattice edge, so all of it
                // gives way to the road.
                float seal = seals[d] = edges[d] < level ? 1 / 36f : 0;
                Area mouth = new Area(lane(d, BoardSurface.roadMouth(tile, next, d), 1 - seal, 1 + PAST_MOUTH / 36));
                float[] across = RailRoute.border(d);
                mouth.subtract(new Area(hexagon(1)).createTransformedArea(
                      AffineTransform.getTranslateInstance((2 + seal) * across[0], (2 + seal) * across[1])));
                local.add(mouth);
            }
            // Slivers and mouth strips reserve only neighbouring ground the road can claim. A column is cut only about
            // the road's level: a cliff above its headroom keeps its ground, and lower ground is never filled up to it.
            // Natural ground is cut only up to the headroom anyway, and a shallow drop is filled up to the road's edge.
            float top = level, bottom = level;
            for (float edge : edges) { top = Math.max(top, edge); bottom = Math.min(bottom, edge); }
            for (int d = 0; d < 6; d++) {
                var next = scene.neighbor(tile, d);
                float ground = next == null ? 0 : next.elevation() * BoardGeometry.level();
                if (next == null || (eased || ground <= top + CLEARANCE * BoardGeometry.level())
                      && ground >= bottom - (eased ? FILL : .25f) * BoardGeometry.level()) {
                    continue;
                }
                // Over water a mouth keeps its seal past the edge, where the shore can move the bank's wall; dry ground
                // under a deck keeps its shape.
                float seal = next.liquid().present() ? seals[d] : 0;
                float[] across = RailRoute.border(d);
                local.subtract(new Area(hexagon(1)).createTransformedArea(
                      AffineTransform.getTranslateInstance((2 + seal) * across[0], (2 + seal) * across[1])));
            }
        } else {
            // Each lane runs a pixel past its edge, which the hex then cuts: ending exactly on the edge, rounding would
            // leave a sliver of the apron's level along it.
            local = new Area();
            for (int d = 0; d < 6; d++) {
                if ((arms & 1 << d) != 0) {
                    local.add(new Area(lane(d, BoardSurface.roadMouth(tile, scene.neighbor(tile, d), d), 0, 1 + 1 / 36f)));
                }
            }
            local.intersect(new Area(hexagon(1)));
        }
        float scale = BoardGeometry.hexScale();
        local.transform(new AffineTransform(scale, 0, 0, scale, cx, cy));
        // Flatten once: every piece cut from this region then shares the same boundary vertices. A straight side keeps
        // only its ends, however the course that drew it was sampled.
        Path2D.Float polygon = new Path2D.Float();
        PathIterator path = local.getPathIterator(null, .02f * scale);
        List<float[]> loop = new ArrayList<>();
        float[] point = new float[6];
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) { loop.clear(); }
            if (type != PathIterator.SEG_CLOSE) { loop.add(new float[] { point[0], point[1] }); }
            if (type == PathIterator.SEG_CLOSE) { closeStraight(polygon, loop); }
            path.next();
        }
        return new Area(polygon);
    }

    /** A closed loop into the polygon without the points that lie on the straight line between their neighbours. */
    private static void closeStraight(Path2D.Float polygon, List<float[]> loop) {
        List<float[]> kept = new ArrayList<>(loop);
        for (boolean removed = true; removed && kept.size() > 3; ) {
            removed = false;
            for (int i = 0; i < kept.size() && kept.size() > 3; i++) {
                float[] a = kept.get(Math.floorMod(i - 1, kept.size())), b = kept.get(i), c = kept.get((i + 1) % kept.size());
                float ux = b[0] - a[0], uy = b[1] - a[1], vx = c[0] - b[0], vy = c[1] - b[1];
                float length = (float) (Math.hypot(ux, uy) * Math.hypot(vx, vy));
                if (length == 0 || Math.abs(ux * vy - uy * vx) < 1e-5f * length && ux * vx + uy * vy > 0) {
                    kept.remove(i--);
                    removed = true;
                }
            }
        }
        for (int i = 0; i < kept.size(); i++) {
            if (i == 0) { polygon.moveTo(kept.get(i)[0], kept.get(i)[1]); } else { polygon.lineTo(kept.get(i)[0], kept.get(i)[1]); }
        }
        polygon.closePath();
    }

    private static float[] outline(Area region) {
        List<Float> segments = new ArrayList<>();
        PathIterator path = region.getPathIterator(null);
        float[] point = new float[6];
        float x = 0, y = 0, startX = 0, startY = 0;
        while (!path.isDone()) {
            int type = path.currentSegment(point);
            if (type == PathIterator.SEG_MOVETO) {
                startX = x = point[0];
                startY = y = point[1];
            } else {
                float nextX = type == PathIterator.SEG_CLOSE ? startX : point[0];
                float nextY = type == PathIterator.SEG_CLOSE ? startY : point[1];
                if (nextX != x || nextY != y) { segments.addAll(List.of(x, y, nextX, nextY)); }
                x = nextX;
                y = nextY;
            }
            path.next();
        }
        float[] result = new float[segments.size()];
        for (int i = 0; i < result.length; i++) { result[i] = segments.get(i); }
        return result;
    }

    /** A straight lane along an exit's axis, between two shares of its border's distance, in tile pixels. */
    private static Path2D.Float lane(int direction, float half, float from, float to) {
        float[] border = RailRoute.border(direction);
        float length = (float) Math.hypot(border[0], border[1]);
        float nx = -border[1] / length * half, ny = border[0] / length * half;
        Path2D.Float lane = new Path2D.Float();
        lane.moveTo(border[0] * from + nx, border[1] * from + ny);
        lane.lineTo(border[0] * to + nx, border[1] * to + ny);
        lane.lineTo(border[0] * to - nx, border[1] * to - ny);
        lane.lineTo(border[0] * from - nx, border[1] * from - ny);
        lane.closePath();
        return lane;
    }

    /** The hex outline in tile pixels, scaled about its centre. */
    private static Path2D.Float hexagon(float scale) {
        Path2D.Float outline = new Path2D.Float();
        for (int k = 0; k < 6; k++) {
            float x = HexTile.cornerOffsetX(k, BoardGeometry.TILE_WIDTH) * scale;
            float y = HexTile.cornerOffsetY(k, BoardGeometry.TILE_HEIGHT) * scale;
            if (k == 0) { outline.moveTo(x, y); } else { outline.lineTo(x, y); }
        }
        outline.closePath();
        return outline;
    }

    /** Everything on this hex's side of an exit's edge line, within reach of the hex. */
    private static Path2D.Float inside(int direction) {
        int edge = Math.floorMod(1 - direction, 6);
        float ax = HexTile.cornerOffsetX(edge, BoardGeometry.TILE_WIDTH);
        float ay = HexTile.cornerOffsetY(edge, BoardGeometry.TILE_HEIGHT);
        float bx = HexTile.cornerOffsetX((edge + 1) % 6, BoardGeometry.TILE_WIDTH);
        float by = HexTile.cornerOffsetY((edge + 1) % 6, BoardGeometry.TILE_HEIGHT);
        float dx = bx - ax, dy = by - ay, length = (float) Math.hypot(dx, dy);
        float ux = dx / length * 4 * PAST_CORNER, uy = dy / length * 4 * PAST_CORNER;
        // The inward normal of a counter-clockwise outline is to the left of its edge.
        float nx = -uy, ny = ux;
        Path2D.Float half = new Path2D.Float();
        half.moveTo(ax - ux, ay - uy);
        half.lineTo(bx + ux, by + uy);
        half.lineTo(bx + ux + nx, by + uy + ny);
        half.lineTo(ax - ux + nx, ay - uy + ny);
        half.closePath();
        return half;
    }

    /** The outer half of an exit's run, open sideways past both corners of its edge. */
    private static Path2D.Float strip(int direction) {
        int edge = Math.floorMod(1 - direction, 6);
        float ax = HexTile.cornerOffsetX(edge, BoardGeometry.TILE_WIDTH);
        float ay = HexTile.cornerOffsetY(edge, BoardGeometry.TILE_HEIGHT);
        float bx = HexTile.cornerOffsetX((edge + 1) % 6, BoardGeometry.TILE_WIDTH);
        float by = HexTile.cornerOffsetY((edge + 1) % 6, BoardGeometry.TILE_HEIGHT);
        float dx = bx - ax, dy = by - ay, length = (float) Math.hypot(dx, dy);
        float ux = dx / length * PAST_CORNER, uy = dy / length * PAST_CORNER;
        Path2D.Float strip = new Path2D.Float();
        strip.moveTo(ax - ux, ay - uy);
        strip.lineTo(bx + ux, by + uy);
        strip.lineTo(bx / 2 + ux, by / 2 + uy);
        strip.lineTo(ax / 2 - ux, ay / 2 - uy);
        strip.closePath();
        return strip;
    }
}
