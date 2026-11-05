/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * The fitted concrete outline: where pavement meets water, other ground or another level, its edges become straight
 * sides and square or mitred corners instead of hex steps. The 3D board's {@code BoardConcrete} (terrain, water,
 * artwork and picking in every view) and the legacy board conversion read this one fit. Plain Java over one {@link Cell}
 * per hex, in board units (a hex {@link HexTile#MODEL_WIDTH} by {@link HexTile#HEIGHT} at scale 1). Immutable once
 * built.
 */
public final class ConcreteFit {
    /**
     * What the fit reads of one hex. {@code fixed}: the hex keeps its outline (a structure, ground drawn without the
     * fitted relief, or a pin); {@code wayExits}: its road and ground-rail sides; {@code ramps}: the sides whose road
     * edge changes level (the 3D board's road ramps); {@code separated}: the sides parted from the pavement beside them
     * ({@link HexAppearance#separated}).
     */
    public record Cell(Coords coords, int level, boolean concrete, boolean liquid, boolean molten, boolean frozen,
          boolean ultraSublevel, boolean fixed, int wayExits, int ramps, int separated) { }

    /** A move on the board plane: how far a lattice corner moved, or how far an object must move ({@link #clearance}). */
    public record Shift(float x, float y) {
        public static final Shift ZERO = new Shift(0, 0);
    }

    /** A point on the board plane (x east, y north). */
    public record Point(float x, float y) {
        Point add(Point o) { return new Point(x + o.x, y + o.y); }

        Point sub(Point o) { return new Point(x - o.x, y - o.y); }

        Point scl(float s) { return new Point(x * s, y * s); }

        float dot(Point o) { return x * o.x + y * o.y; }

        float len2() { return x * x + y * y; }

        float dst2(Point o) {
            float a = o.x - x, b = o.y - y;
            return a * a + b * b;
        }

        float dst(Point o) { return (float) Math.sqrt(dst2(o)); }

        Point nor() {
            float len2 = len2();
            if (len2 == 0f || len2 == 1f) { return this; }
            return scl(1f / (float) Math.sqrt(len2));
        }

        Point lerp(Point target, float alpha) {
            return new Point(x + alpha * (target.x - x), y + alpha * (target.y - y));
        }
    }

    private final int width, height;
    private final List<Cell> cells;
    private final float scale, hexWidth, hexHeight;
    /** The farthest a fitted corner moves: one side of the hex, the lattice's longest (its slanted sides). */
    private final float maxMove;
    private final boolean everywhere;
    /** Null for material boundaries; otherwise the higher owner of a dry concrete level boundary. */
    private final Integer plateau;
    /** Prior accepted plateaus, read only while this temporary level fit is constructed. */
    private final ConcreteFit previous;
    /** While a separated slab's sides are fitted: every cell's slab by board index, and that slab; else null and 0. */
    private final int[] slabs;
    private final int slab;
    /** Hexes that keep their outline because a placed object pins them. */
    private final Set<Coords> pinned;
    /** Moved lattice corners by {@link #slot}; null where a corner keeps its place. */
    private final Shift[] shifts;
    /** The fitted outline (x0, y0 ... x5, y5) of every hex a moved corner touches, by board index; null elsewhere. */
    private final float[][] footprints;

    /**
     * Fits a board {@code width} by {@code height} hexes ({@code cells} in board order, x * height + y, read as given and
     * never changed) at hex {@code scale}. {@code everywhere}: pavement is fitted against every other ground too, not
     * only against water. {@code pinned} hexes keep their outline.
     */
    public ConcreteFit(int width, int height, List<Cell> cells, float scale, boolean everywhere, Set<Coords> pinned) {
        this(width, height, cells, scale, everywhere, pinned, null, null);
    }

    private ConcreteFit(int width, int height, List<Cell> cells, float scale, boolean everywhere,
          Set<Coords> pinned, Integer plateau, ConcreteFit previous) {
        this(width, height, cells, scale, everywhere, pinned, plateau, previous, null, 0);
    }

    private ConcreteFit(int width, int height, List<Cell> cells, float scale, boolean everywhere,
          Set<Coords> pinned, Integer plateau, ConcreteFit previous, int[] slabs, int slab) {
        this.slabs = slabs;
        this.slab = slab;
        this.width = width;
        this.height = height;
        this.cells = cells;
        this.scale = scale;
        hexWidth = HexTile.MODEL_WIDTH * scale;
        hexHeight = HexTile.HEIGHT * scale;
        maxMove = Math.max(hexWidth / 2, (float) Math.hypot(hexWidth / 4, hexHeight / 2));
        this.everywhere = everywhere;
        this.plateau = plateau;
        this.previous = previous;
        this.pinned = Set.copyOf(pinned);
        shifts = new Shift[(3 * width + 2) * (2 * height + 2)];
        footprints = new float[width * height][];
        fit();
        if (plateau == null && everywhere) {
            fitLevels();
            fitSeparated();
        }
    }

    /** Reuse the same contour fitter for each plateau, with one owner for every height boundary. */
    private void fitLevels() {
        Set<Coords> levelPins = new HashSet<>(pinned);
        Set<Integer> elevations = new TreeSet<>();
        for (Cell tile : cells) {
            if (footprint(tile.coords()) != null) { levelPins.add(tile.coords()); }
            if (tile.concrete() && !tile.liquid()) { elevations.add(tile.level()); }
        }
        if (elevations.size() < 2) { return; }
        // The lowest concrete level cannot own a boundary against another concrete level.
        elevations.remove(elevations.iterator().next());
        // A material or water outline has priority; three-level corners are pinned below, so the remaining
        // level runs cannot compete for a lattice corner.
        Set<Coords> fitted = new HashSet<>();
        for (int elevation : elevations) {
            adopt(new ConcreteFit(width, height, cells, scale, everywhere, levelPins, elevation, this), fitted);
        }
        footprints(fitted);
    }

    /** Takes the corners of a later pass that no earlier one moved, and those a separated side's end slid on. */
    private void adopt(ConcreteFit next, Set<Coords> fitted) {
        for (int i = 0; i < shifts.length; i++) {
            if ((shifts[i] == null || next.slab != 0) && next.shifts[i] != null) { shifts[i] = next.shifts[i]; }
        }
        for (Cell tile : cells) { if (next.footprint(tile.coords()) != null) { fitted.add(tile.coords()); } }
    }

    /**
     * A side separated between two slabs of pavement at one level is fitted as any concrete edge is, by the same contour
     * fitter, with one owner for each side: the later slab. Its ends start where the outline's fit put them. A side that
     * parts no two slabs keeps its hex edge, and a board with no separated side never comes here.
     */
    private void fitSeparated() {
        if (cells.stream().noneMatch(tile -> tile.separated() != 0)) { return; }
        int[] found = new int[cells.size()];
        int count = 0;
        for (int i = 0; i < cells.size(); i++) {
            if (found[i] != 0 || !paved(cells.get(i))) { continue; }
            found[i] = ++count;
            List<Cell> open = new ArrayList<>(List.of(cells.get(i)));
            while (!open.isEmpty()) {
                Cell tile = open.removeLast();
                for (int d = 0; d < 6; d++) {
                    Cell next = cell(tile.coords().translated(d));
                    if (next == null || !paved(next) || next.level() != tile.level()
                          || ((tile.separated() >> d | next.separated() >> (d + 3) % 6) & 1) != 0) { continue; }
                    int at = index(next.coords().getX(), next.coords().getY());
                    if (found[at] == 0) {
                        found[at] = count;
                        open.add(next);
                    }
                }
            }
        }
        Map<Integer, Integer> owners = new TreeMap<>();
        for (int i = 0; i < cells.size(); i++) {
            Cell tile = cells.get(i);
            for (int d = 0; d < 6 && found[i] != 0; d++) {
                Cell next = cell(tile.coords().translated(d));
                if (next != null && paved(next) && next.level() == tile.level()
                      && found[index(next.coords().getX(), next.coords().getY())] < found[i]) {
                    owners.put(found[i], tile.level());
                }
            }
        }
        Set<Coords> fitted = new HashSet<>();
        owners.forEach((owner, level) -> adopt(new ConcreteFit(width, height, cells, scale, everywhere, pinned, level, this, found, owner), fitted));
        // An end sliding along the outline also moves the ground hex beside it.
        for (Cell tile : cells) { if (footprint(tile.coords()) != null) { fitted.add(tile.coords()); } }
        footprints(fitted);
    }

    /**
     * The straight stretch of the outline a separated side ends on, as its two ends beside {@code corner}; null where the
     * side ends anywhere else. The ground beyond it is one hex, and the side's two slabs each have an edge along it.
     */
    private Point[] outlineAt(Corner corner) {
        List<Point> ends = new ArrayList<>(2);
        for (Cell tile : corner.tiles) {
            for (int k = 0; k < 6; k++) {
                if (original(tile.coords(), k).dst2(corner.original) > .001f) { continue; }
                for (int e : new int[] { k, (k + 5) % 6 }) {
                    Cell across = cell(tile.coords().translated(HexTile.edgeDirection(e)));
                    if (across != null && !across.concrete() && !across.liquid()) {
                        ends.add(previous.corner(tile.coords(), e == k ? (k + 1) % 6 : (k + 5) % 6));
                    }
                }
            }
        }
        if (ends.size() != 2) { return null; }
        Point a = ends.get(0), along = ends.get(1).sub(a).nor(), to = corner.point.sub(a);
        return Math.abs(along.x * to.y - along.y * to.x) < .01f * scale ? new Point[] { a, ends.get(1) } : null;
    }

    /** Where a separated side's fitted {@code line} meets the outline its end lies on, within it; else where it was. */
    private static Point slid(Corner corner, Line line, Point point) {
        if (corner.outline == null) { return point; }
        Point a = corner.outline[0], along = corner.outline[1].sub(a), unit = along.nor();
        Point joint = new Line(-unit.y, unit.x, -unit.y * a.x + unit.x * a.y).intersection(line);
        if (joint == null) { return point; }
        float t = joint.sub(a).dot(along) / along.len2();
        return t > .15f && t < .85f ? joint : point;
    }

    private static boolean paved(Cell tile) { return tile.concrete() && !tile.liquid(); }

    private int slab(Cell tile) { return slabs[index(tile.coords().getX(), tile.coords().getY())]; }

    /**
     * The cells of a board as the 3D board captures its hexes, from the hexes alone (what the legacy conversion can read
     * without the views): a structure (building, fuel tank, industrial, bridge) fixes its hex; ground drawn without the
     * fitted relief, bridges' road approaches and the Edges of a hex (a legacy board has none) are not seen.
     */
    public static List<Cell> cells(Board board) {
        int width = board.getWidth(), height = board.getHeight();
        int[] ways = new int[width * height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Hex hex = board.getHex(x, y);
                ways[x * height + y] = (hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0)
                      | RailRoute.groundSides(hex);
            }
        }
        List<Cell> result = new ArrayList<>(width * height);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(x, y);
                boolean liquid = HexTile.liquid(hex), ultra = hex.containsTerrain(Terrains.ULTRA_SUBLEVEL);
                int ramps = 0;
                for (int d = 0; d < 6 && !liquid && !ultra; d++) {
                    Coords next = at.translated(d);
                    if (!board.contains(next)) { continue; }
                    Hex other = board.getHex(next);
                    int levels = Math.abs(hex.getLevel() - other.getLevel());
                    if (levels == 0 || HexTile.liquid(other) || other.containsTerrain(Terrains.ULTRA_SUBLEVEL)) { continue; }
                    boolean exit = (ways[x * height + y] & 1 << d) != 0;
                    boolean continuation = (ways[next.getX() * height + next.getY()] & 1 << (d + 3) % 6) != 0;
                    if (HexTile.roadApproach(exit, continuation, levels)) { ramps |= 1 << d; }
                }
                boolean fixed = hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL, Terrains.BRIDGE);
                result.add(new Cell(at, hex.getLevel(), HexTile.concrete(hex), liquid, hex.terrainLevel(Terrains.MAGMA) == 2,
                      hex.containsTerrain(Terrains.ICE), ultra, fixed, ways[x * height + y], ramps,
                      HexAppearance.separated(hex.getAppearance(), "pavement")));
            }
        }
        return result;
    }

    /** The hexes that keep their outline because a placed object pins them. */
    public Set<Coords> pinned() { return pinned; }

    /** The shift of the lattice corner {@code ((long) column << 32) ^ (row & 0xffffffffL)}. */
    public Shift shift(long corner) {
        int slot = slot(corner);
        Shift shift = slot < 0 ? null : shifts[slot];
        return shift == null ? Shift.ZERO : shift;
    }

    /** The shift of a hex's corner k (0..5). */
    public Shift shift(Coords coords, int k) { return shift(key(original(coords, k))); }

    /** Corner k (0..5) of a hex as fitted. */
    public Point corner(Coords coords, int k) {
        Point point = original(coords, k);
        Shift delta = shift(key(point));
        return new Point(point.x + delta.x(), point.y + delta.y());
    }

    public List<Shift> corners(Coords coords) {
        List<Shift> result = new ArrayList<>(6);
        for (int k = 0; k < 6; k++) { result.add(shift(coords, k)); }
        return List.copyOf(result);
    }

    public boolean sameCorners(ConcreteFit other, Coords coords) {
        if (other == null) { return false; }
        for (int k = 0; k < 6; k++) {
            long key = key(original(coords, k));
            if (!shift(key).equals(other.shift(key))) { return false; }
        }
        return true;
    }

    /** The fitted outline (x0, y0 ... x5, y5) of a hex a moved corner touches; null where the hex keeps its outline. */
    public float[] footprint(Coords coords) {
        int index = index(coords.getX(), coords.getY());
        return index < 0 ? null : footprints[index];
    }

    private int index(int x, int y) {
        return x < 0 || y < 0 || x >= width || y >= height ? -1 : x * height + y;
    }

    private Cell cell(Coords coords) {
        int index = index(coords.getX(), coords.getY());
        return index < 0 ? null : cells.get(index);
    }

    private int slot(long corner) {
        int column = (int) (corner >> 32), row = -(int) corner;
        return column < 0 || row < 0 || column >= 3 * width + 2 || row >= 2 * height + 2 ? -1 : column * (2 * height + 2) + row;
    }

    private long key(Point p) {
        return ((long) Math.round(p.x / (hexWidth / 4)) << 32) ^ (Math.round(p.y / (hexHeight / 2)) & 0xffffffffL);
    }

    private Point original(Coords coords, int k) {
        return new Point(HexTile.cornerX(coords, k, hexWidth), HexTile.cornerY(coords, k, hexHeight));
    }

    private Point center(Coords coords) {
        return new Point(HexTile.centerX(coords, hexWidth), HexTile.centerY(coords, hexHeight));
    }

    /** Construction-only vertices. Each is the same lattice corner seen by its three adjoining hexes. */
    private static final class Corner {
        final Point original;
        Point point;
        final Set<Cell> tiles = new HashSet<>();
        Corner previous, next;
        Line rail;
        /** The straight stretch of the outline a separated side's end may slide along; null elsewhere. */
        Point[] outline;
        boolean pinned, dock, border;

        Corner(Point point) {
            original = point;
            this.point = point;
        }
    }

    private void fit() {
        Map<Long, Corner> corners = new TreeMap<>();
        for (Cell land : cells) {
            if (land.liquid() || !land.concrete() || plateau != null && land.level() != plateau
                  || slab != 0 && slab(land) != slab) { continue; }
            for (int e = 0; e < 6; e++) {
                Cell water = cell(land.coords().translated(HexTile.edgeDirection(e)));
                // A graded opening owns its road plane, not a vertical plateau wall to straighten.
                if (plateau != null && (land.ramps() & 1 << HexTile.edgeDirection(e)) != 0) { continue; }
                if (plateau != null && (water == null || !water.concrete() || water.liquid()
                      || (slab != 0 ? water.level() != land.level() : water.level() >= land.level()))) { continue; }
                if (!outside(water) || water.liquid() && water.level() > land.level()) { continue; }
                Corner a = coastCorner(corners, land.coords(), e);
                Corner b = coastCorner(corners, land.coords(), (e + 1) % 6);
                a.next = b;
                b.previous = a;
                a.rail = dockRail(land, a.point, b.point);
                a.tiles.add(land);
                a.tiles.add(water);
                b.tiles.add(land);
                b.tiles.add(water);
            }
        }
        for (Corner corner : corners.values()) {
            Integer dryLevel = null, outsideLevel = null;
            corner.border &= corner.tiles.size() == 2 && (corner.previous == null) != (corner.next == null);
            corner.pinned = !corner.border && (corner.tiles.size() != 3 || corner.previous == null || corner.next == null);
            for (Cell tile : corner.tiles) {
                corner.pinned |= fixedBoundary(corner, tile);
                if (outside(tile)) {
                    if (tile.liquid() || plateau != null) {
                        corner.pinned |= tile.frozen() || outsideLevel != null && outsideLevel != tile.level();
                        outsideLevel = tile.level();
                    }
                } else {
                    corner.pinned |= dryLevel != null && dryLevel != tile.level();
                    corner.pinned |= dockLinks(tile) == 0;
                    dryLevel = tile.level();
                }
            }
            if (slab != 0) {
                // Three slabs meeting stay put, and a side's end stays where the outline's fit put it.
                corner.pinned |= corner.tiles.stream().filter(this::outside).map(this::slab).distinct().count() > 1;
                Shift moved = previous.shift(key(corner.original));
                if (!moved.equals(Shift.ZERO)) {
                    corner.point = corner.original.add(new Point(moved.x(), moved.y()));
                    corner.pinned = true;
                }
                if (corner.previous == null || corner.next == null) { corner.outline = outlineAt(corner); }
            }
            if (!corner.pinned) {
                Line before = corner.previous == null ? null : corner.previous.rail, after = corner.rail;
                corner.dock = before != null || after != null;
                if (before != null && after != null && !before.same(after, scale)) {
                    Point joint = before.intersection(after);
                    if (joint != null) { corner.point = joint; corner.pinned = true; }
                } else if (corner.dock) {
                    corner.point = (before != null ? before : after).project(corner.point);
                }
            }
        }
        simplify(corners, null);
        Set<Corner> continued = continueSides(corners.values());
        if (!continued.isEmpty()) { simplify(corners, continued); }
        Set<Coords> fitted = new HashSet<>();
        corners.forEach((key, corner) -> {
            float dx = corner.point.x - corner.original.x, dy = corner.point.y - corner.original.y;
            // A corner on the board's edge keeps its place, or one of its hexes' edges would leave the board's hex
            // outline: the side fitted through it there turns back to it after the last corner before the edge.
            if (Math.hypot(dx, dy) > .001f && !corner.border) {
                shifts[slot(key)] = new Shift(dx, dy);
                corner.tiles.forEach(tile -> fitted.add(tile.coords()));
            }
        });
        // Surface blending asks for this polygon repeatedly per vertex: derive it once from the same corner shifts.
        footprints(fitted);
    }

    private void footprints(Set<Coords> fitted) {
        for (Coords coords : fitted) {
            float[] outline = new float[12];
            for (int k = 0; k < 6; k++) {
                Point point = corner(coords, k);
                outline[k * 2] = point.x;
                outline[k * 2 + 1] = point.y;
            }
            footprints[index(coords.getX(), coords.getY())] = outline;
        }
    }

    private void simplify(Map<Long, Corner> corners, Set<Corner> changed) {
        Set<Corner> visited = new HashSet<>();
        // Open chains first; otherwise starting midway would split a continuous quay at an arbitrary hex.
        for (Corner corner : corners.values()) {
            if (corner.previous == null) { simplifyChain(corner, visited, changed, corners); }
        }
        for (Corner corner : corners.values()) { simplifyChain(corner, visited, changed, corners); }
    }

    private Corner coastCorner(Map<Long, Corner> corners, Coords coords, int k) {
        Point point = original(coords, k);
        Corner corner = corners.computeIfAbsent(key(point), ignored -> new Corner(point));
        corner.border |= cell(coords.translated(HexTile.edgeDirection(k))) == null
              || cell(coords.translated(HexTile.edgeDirection((k + 5) % 6))) == null;
        return corner;
    }

    private static int nearest(List<Corner> chain, Point point, int first, int last) {
        int result = first;
        for (int i = first + 1; i <= last; i++) {
            if (point.dst2(chain.get(i).original) < point.dst2(chain.get(result).original)) { result = i; }
        }
        return result;
    }

    /** A broad patch can be one rectangle too. Try the three lattice axes and keep the least displaced safe fit. */
    private boolean fitRectangle(List<Corner> chain, Map<Long, Corner> corners) {
        if (chain.stream().anyMatch(corner -> corner.pinned && !corner.dock)) { return false; }
        float area = 0;
        Set<Cell> land = new HashSet<>();
        for (Corner corner : chain) {
            area += corner.point.x * corner.next.point.y - corner.point.y * corner.next.point.x;
            for (Cell tile : corner.tiles) { if (!outside(tile)) { land.add(tile); } }
        }
        // An inner hole has the reverse winding and must stay open.
        if (area <= 0 || land.size() < 6) { return false; }
        Point[] best = null;
        float bestError = Float.POSITIVE_INFINITY;
        for (float angle : new float[] { 0, (float) Math.atan2(hexHeight / 2, .75f * hexWidth),
              -(float) Math.atan2(hexHeight / 2, .75f * hexWidth) }) {
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle), vx = -uy, vy = ux;
            float u0 = Float.POSITIVE_INFINITY, u1 = Float.NEGATIVE_INFINITY;
            float v0 = Float.POSITIVE_INFINITY, v1 = Float.NEGATIVE_INFINITY;
            for (Cell tile : land) {
                float x = HexTile.centerX(tile.coords(), hexWidth), y = HexTile.centerY(tile.coords(), hexHeight);
                u0 = Math.min(u0, ux * x + uy * y);
                u1 = Math.max(u1, ux * x + uy * y);
                v0 = Math.min(v0, vx * x + vy * y);
                v1 = Math.max(v1, vx * x + vy * y);
            }
            if (Math.min(u1 - u0, v1 - v0) < 54 * scale) { continue; }
            float margin = 16 * scale;
            Line[] sides = { new Line(ux, uy, u0 - margin), new Line(vx, vy, v0 - margin),
                  new Line(-ux, -uy, -u1 - margin), new Line(-vx, -vy, -v1 - margin) };
            Line[] edges = new Line[chain.size()];
            int[] starts = new int[4];
            for (int k = 0; k < 4; k++) {
                Point corner = sides[(k + 3) % 4].intersection(sides[k]);
                starts[k] = nearest(chain, corner, 0, chain.size() - 1);
            }
            boolean ordered = true;
            for (int k = 0; k < 4 && ordered; k++) {
                int end = starts[(k + 1) % 4];
                if (starts[k] == end) { ordered = false; break; }
                for (int i = starts[k]; i != end; i = (i + 1) % chain.size()) {
                    if (edges[i] != null) { ordered = false; break; }
                    edges[i] = sides[k];
                }
            }
            if (!ordered) { continue; }
            Point[] points = new Point[chain.size()];
            int turns = 0;
            float error = 0;
            boolean fits = true;
            for (int i = 0; i < chain.size(); i++) {
                Line previous = edges[(i + chain.size() - 1) % chain.size()], next = edges[i];
                if (previous != next) { turns++; }
                Point p = previous == next ? next.project(chain.get(i).original) : previous.intersection(next);
                if (p == null || p.dst(chain.get(i).original) > maxMove) { fits = false; break; }
                points[i] = p;
                error += p.dst2(chain.get(i).original);
            }
            if (!fits || turns != 4) { continue; }
            for (int i = 0; i < chain.size() && fits; i++) {
                fits = clearEdge(chain.get(i), points[i], points[(i + 1) % chain.size()]);
            }
            if (fits && plateau != null) { fits = clearFootprints(chain, 0, chain.size() - 1, points, corners); }
            if (fits && error < bestError) { best = points; bestError = error; }
        }
        if (best == null) { return false; }
        for (int i = 0; i < chain.size(); i++) { chain.get(i).point = best[i]; }
        return true;
    }

    private void simplifyChain(Corner start, Set<Corner> visited, Set<Corner> changed, Map<Long, Corner> corners) {
        if (visited.contains(start)) { return; }
        List<Corner> chain = new ArrayList<>();
        for (Corner at = start; at != null && visited.add(at); at = at.next) {
            chain.add(at);
        }
        if (chain.size() < 3 || changed != null && Collections.disjoint(chain, changed)) { return; }
        if (chain.getLast().next == start && fitRectangle(chain, corners)) { return; }
        if (chain.getLast().next == start) {
            int anchor = 0;
            Point center = new Point(0, 0);
            for (Corner corner : chain) { center = center.add(corner.point); }
            center = center.scl(1f / chain.size());
            for (int i = 0; i < chain.size(); i++) {
                if (chain.get(i).pinned) { anchor = i; break; }
                if (chain.get(i).point.dst2(center) > chain.get(anchor).point.dst2(center)) { anchor = i; }
            }
            List<Corner> ring = new ArrayList<>(chain.size() + 1);
            for (int i = 0; i <= chain.size(); i++) { ring.add(chain.get((anchor + i) % chain.size())); }
            chain = ring;
        }
        int first = 0;
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i).pinned || i == chain.size() - 1) {
                fitLanding(chain, first, i, corners);
                first = i;
            }
        }
    }

    /** An inward unit normal and offset. Both sides of a dock come from one axis and one width. */
    private record Line(float nx, float ny, float offset) {
        float side(Point p) { return nx * p.x + ny * p.y - offset; }

        Point project(Point p) { return new Point(p.x - nx * side(p), p.y - ny * side(p)); }

        boolean same(Line other, float scale) {
            return Math.abs(nx - other.nx) + Math.abs(ny - other.ny) < .0001f
                  && Math.abs(offset - other.offset) < .001f * scale;
        }

        Point intersection(Line other) {
            float determinant = nx * other.ny - ny * other.nx;
            return Math.abs(determinant) < .001f ? null : new Point(
                  (offset * other.ny - ny * other.offset) / determinant,
                  (nx * other.offset - offset * other.nx) / determinant);
        }
    }

    private record Run(int first, int last, Line line, boolean rectangle) { }

    /** An established straight side can continue beyond its end across a paved junction. */
    private record Side(Line line, Point a, Point b) { }

    /** Prefer a longer established side across a short paved branch, then refit only the affected chains. */
    private Set<Corner> continueSides(Collection<Corner> corners) {
        Map<Coords, List<Side>> nearby = new HashMap<>();
        Map<Corner, Float> lengths = new HashMap<>();
        Set<Corner> visited = new HashSet<>();
        for (Corner corner : corners) {
            if (corner.next == null || visited.contains(corner)) { continue; }
            if (corner.point.dst2(corner.next.point) < .000001f * scale * scale) { visited.add(corner); continue; }
            Point along = corner.next.point.sub(corner.point).nor();
            Line line = new Line(-along.y, along.x, -along.y * corner.point.x + along.x * corner.point.y);
            Corner first = corner, last = corner.next;
            while (first.previous != null && first.previous != corner && !visited.contains(first.previous)
                  && Math.abs(line.side(first.previous.point)) < .003f * scale) { first = first.previous; }
            while (last.next != null && last != first && !visited.contains(last)
                  && Math.abs(line.side(last.next.point)) < .003f * scale) { last = last.next; }
            float length = first.point.dst(last.point);
            for (Corner at = first; at != last; at = at.next) {
                visited.add(at);
                lengths.put(at, length);
                // Reuse an exact fixed-width rail rather than recovering a slightly different line from float vertices.
                if (at.rail != null && line.same(at.rail, scale)) { line = at.rail; }
            }
            if (length < 1.5f * hexWidth) { continue; }
            Side side = new Side(line, first.point, last.point);
            for (Corner end : List.of(first, last)) {
                for (Cell tile : end.tiles) {
                    nearby.computeIfAbsent(tile.coords(), ignored -> new ArrayList<>()).add(side);
                }
            }
        }
        Set<Corner> changed = new HashSet<>();
        for (Corner corner : corners) {
            if (corner.next == null || corner.rail != null || corner.pinned || corner.next.pinned
                  || lengths.getOrDefault(corner, 0f) == 0) { continue; }
            Point middle = corner.original.lerp(corner.next.original, .5f);
            Cell owner = corner.tiles.stream().filter(tile -> tile.concrete() && !tile.liquid()
                  && corner.next.tiles.contains(tile)).findFirst().orElse(null);
            if (owner == null) { continue; }
            Set<Side> candidates = new LinkedHashSet<>();
            for (Coords at : owner.coords().allAtDistanceOrLess(3)) { candidates.addAll(nearby.getOrDefault(at, List.of())); }
            float error = 24 * scale;
            Point direction = corner.next.point.sub(corner.point).nor();
            for (Side side : candidates) {
                if (side.a.dst(side.b) <= lengths.get(corner) + .001f * scale) { continue; }
                float facing = -direction.y * side.line.nx + direction.x * side.line.ny;
                // A continuation repairs a turn at a branch; it must not shift an already parallel side.
                if (facing <= 0 || facing > .98f) { continue; }
                float distance = Math.abs(side.line.side(middle));
                if (distance >= error) { continue; }
                Point point = side.line.project(middle), along = side.b.sub(side.a);
                float t = point.sub(side.a).dot(along) / along.len2();
                if (t >= 0 && t <= 1) { continue; }
                Point end = t < 0 ? side.a : side.b;
                float gap = end.dst(point);
                if (gap < hexWidth / 2 || gap > 3 * hexWidth) { continue; }
                boolean paved = true;
                int steps = (int) Math.ceil(gap / (hexWidth / 4));
                for (int step = 1; paved && step < steps; step++) {
                    Point p = end.lerp(point, step / (float) steps);
                    int index = HexTile.at(p.x, p.y, hexWidth, hexHeight, width, height);
                    Cell tile = index < 0 ? null : cells.get(index);
                    paved &= tile != null && (corner.tiles.contains(tile) || corner.next.tiles.contains(tile)
                          || tile.concrete() && !tile.liquid() && tile.level() == owner.level());
                }
                if (paved && clearEdge(corner, side.line.project(corner.original), side.line.project(corner.next.original))) {
                    corner.rail = side.line;
                    error = distance;
                    changed.add(corner);
                }
            }
        }
        return changed;
    }

    private Line dockRail(Cell land, Point a, Point b) {
        int links = dockLinks(land);
        if (links <= 0) { return null; } // An isolated concrete tile always keeps its hex footprint.
        Point c = center(land.coords());
        float mx = (a.x + b.x) / 2 - c.x, my = (a.y + b.y) / 2 - c.y;
        int first = Integer.numberOfTrailingZeros(links), direction = first;
        float best = Float.NEGATIVE_INFINITY;
        boolean straight = links == ((1 << first) | (1 << (first + 3) % 6));
        // At a bend, each bank follows the nearest connected arm. Their fixed-width rails meet at a mitre.
        for (int d = 0; d < 6 && !straight; d++) {
            if ((links & 1 << d) == 0) { continue; }
            float[] candidate = axis(land.coords(), d);
            float along = mx * candidate[0] + my * candidate[1];
            if (along > best) { direction = d; best = along; }
        }
        float[] axis = axis(land.coords(), direction);
        float nx = -axis[1], ny = axis[0], half = dockHalf(land, axis);
        float across = mx * nx + my * ny;
        if (Integer.bitCount(links) == 1 && Math.abs(across) < half / 2) {
            // A square end cap, perpendicular to the same axis as the two parallel sides.
            float along = mx * axis[0] + my * axis[1];
            float sign = Math.signum(along);
            nx = -sign * axis[0];
            ny = -sign * axis[1];
            half = center(land.coords().translated(direction)).dst(c) / 2;
        } else {
            float sign = Math.signum(across);
            nx *= -sign;
            ny *= -sign;
        }
        return new Line(nx, ny, nx * c.x + ny * c.y - half);
    }

    /** Fit the quay between fixed rectangle sides, then use their intersections as the landing corners. */
    private void fitLanding(List<Corner> chain, int first, int last, Map<Long, Corner> corners) {
        if (last - first < 2) { return; }
        List<Run> runs = new ArrayList<>();
        for (int i = first; i < last;) {
            Line rail = chain.get(i).rail;
            int end = i + 1;
            while (end < last && (rail == null ? chain.get(end).rail == null
                  : chain.get(end).rail != null && rail.same(chain.get(end).rail, scale))) { end++; }
            if (rail == null) { fitQuay(chain, i, end, runs); }
            else { runs.add(new Run(i, end, rail, true)); }
            i = end;
        }
        // A short, almost-parallel apron belongs to the same rectangular side. Keeping it as a separate line
        // would create either a taper or a distant intersection at the dock root.
        for (int i = 0; i + 1 < runs.size();) {
            Run a = runs.get(i), b = runs.get(i + 1);
            Line rail = a.rectangle ? a.line : b.rectangle ? b.line : null;
            boolean fits = rail != null && a.rectangle != b.rectangle
                  && (a.line.nx * b.line.nx + a.line.ny * b.line.ny > .98f
                        || (a.rectangle ? b.last - b.first : a.last - a.first) <= 2);
            for (int j = a.first; fits && j <= b.last; j++) {
                fits = Math.abs(rail.side(chain.get(j).point)) <= 24 * scale;
            }
            if (fits) {
                runs.set(i, new Run(a.first, b.last, rail, true));
                runs.remove(i + 1);
            } else if (!a.rectangle && !b.rectangle) {
                List<Run> merged = new ArrayList<>();
                fitQuay(chain, a.first, b.last, merged);
                if (merged.size() == 1) {
                    runs.set(i, merged.getFirst());
                    runs.remove(i + 1);
                } else { i++; }
            } else { i++; }
        }
        // A short hex-shaped detour at a junction can hide the intersection of its two longer sides. Extend
        // those sides only if the resulting corner and every adjoining edge still clear their terrain centres.
        for (int i = 0; i + 2 < runs.size();) {
            Run a = runs.get(i), middle = runs.get(i + 1), b = runs.get(i + 2);
            Point joint = a.line.intersection(b.line);
            if (middle.rectangle || joint == null) { i++; continue; }
            int split = nearest(chain, joint, middle.first, middle.last);
            List<Run> candidate = new ArrayList<>(runs);
            candidate.set(i, new Run(a.first, split, a.line, a.rectangle));
            candidate.set(i + 1, new Run(split, b.last, b.line, b.rectangle));
            candidate.remove(i + 2);
            if (landingPoints(chain, first, last, candidate, corners) != null) { runs = candidate; }
            else { i++; }
        }
        Point[] points = landingPoints(chain, first, last, runs, corners);
        if (points == null) { return; }
        for (int i = first; i <= last; i++) { chain.get(i).point = points[i - first]; }
    }

    private Point[] landingPoints(List<Corner> chain, int first, int last, List<Run> runs,
          Map<Long, Corner> corners) {
        // The fitted intersection may belong to a neighbouring lattice corner. Assign it to the nearest one,
        // instead of pulling the arbitrary subdivision endpoint across a whole hex and rejecting both long sides.
        List<Run> joined = new ArrayList<>(runs);
        for (int i = 0; i + 1 < joined.size(); i++) {
            Run a = joined.get(i), b = joined.get(i + 1);
            Point joint = a.line.intersection(b.line);
            if (joint == null) { continue; }
            int split = nearest(chain, joint, a.first + 1, b.last - 1);
            joined.set(i, new Run(a.first, split, a.line, a.rectangle));
            joined.set(i + 1, new Run(split, b.last, b.line, b.rectangle));
        }
        Point[] points = outlinePoints(chain, first, last, joined, corners);
        return points == null && !joined.equals(runs) ? outlinePoints(chain, first, last, runs, corners) : points;
    }

    private Point[] outlinePoints(List<Corner> chain, int first, int last, List<Run> runs,
          Map<Long, Corner> corners) {
        Point[] points = new Point[last - first + 1];
        points[0] = chain.get(first).point;
        points[points.length - 1] = chain.get(last).point;
        // A run ends where the map ends: fit it on to the map's edge, whose corner itself keeps its place (#fit).
        if (!runs.isEmpty()) {
            if (chain.get(first).border && !chain.get(first).pinned) {
                points[0] = runs.getFirst().line.project(chain.get(first).point);
            }
            if (chain.get(last).border && !chain.get(last).pinned) {
                points[points.length - 1] = runs.getLast().line.project(chain.get(last).point);
            }
            points[0] = slid(chain.get(first), runs.getFirst().line, points[0]);
            points[points.length - 1] = slid(chain.get(last), runs.getLast().line, points[points.length - 1]);
        }
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            for (int j = run.first + 1; j < run.last; j++) {
                points[j - first] = run.line.project(chain.get(j).point);
            }
            if (i + 1 < runs.size()) {
                Line next = runs.get(i + 1).line;
                Point joint = run.line.same(next, scale) ? run.line.project(chain.get(run.last).point)
                      : run.line.intersection(next);
                if (joint == null) { return null; }
                points[run.last - first] = joint;
            }
        }
        if (chain.get(first) == chain.get(last) && !chain.get(first).pinned && !runs.isEmpty()) {
            Line before = runs.getLast().line, after = runs.getFirst().line;
            Point joint = before.same(after, scale) ? after.project(chain.get(first).point) : before.intersection(after);
            if (joint == null) { return null; }
            points[0] = joint;
            points[points.length - 1] = joint;
        }
        // A failed fit leaves the original rectangle intact. Never taper a pier to accommodate its landing.
        for (int i = first; i <= last; i++) {
            Point p = points[i - first];
            if (p == null || p.dst(chain.get(i).original) > maxMove + .001f * scale) { return null; }
            if (i < last && points[i + 1 - first] == null) { return null; }
            if (i < last && !clearEdge(chain.get(i), p, points[i + 1 - first])) { return null; }
        }
        return plateau == null || clearFootprints(chain, first, last, points, corners) ? points : null;
    }

    /** A fitted wall also moves the adjoining hexes; none of their edges may turn inside out. */
    private boolean clearFootprints(List<Corner> chain, int first, int last, Point[] candidate,
          Map<Long, Corner> corners) {
        Map<Long, Point> points = new HashMap<>();
        Set<Cell> touched = new HashSet<>();
        for (int i = first; i <= last; i++) {
            points.put(key(chain.get(i).original), candidate[i - first]);
            touched.addAll(chain.get(i).tiles);
        }
        for (Cell tile : touched) {
            Point center = center(tile.coords());
            for (int e = 0; e < 6; e++) {
                Point a = fittedPoint(tile.coords(), e, points, corners);
                Point b = fittedPoint(tile.coords(), (e + 1) % 6, points, corners);
                // Walls mitred round a lattice corner's neighbours meet on that corner, and the edge there collapses.
                if (a.dst2(b) <= .0001f * scale * scale) { continue; }
                float side = (b.x - a.x) * (center.y - a.y) - (b.y - a.y) * (center.x - a.x);
                if (side <= .001f * scale * scale) { return false; }
            }
        }
        return true;
    }

    /** Earlier plateau shifts have the same priority here as when their results are merged. */
    private Point fittedPoint(Coords coords, int k, Map<Long, Point> candidate, Map<Long, Corner> corners) {
        Point original = original(coords, k);
        long key = key(original);
        if (previous != null && !previous.shift(key).equals(Shift.ZERO)) { return previous.corner(coords, k); }
        Point point = candidate.get(key);
        if (point != null) { return point; }
        Corner corner = corners.get(key);
        return corner == null ? original : corner.point;
    }

    /** Straight quay runs use the board's three construction axes or their perpendiculars. */
    private void fitQuay(List<Corner> chain, int first, int last, List<Run> runs) {
        Point a = chain.get(first).point, b = chain.get(last).point;
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        if (length < .001f) {
            if (last - first < 2) { return; }
            int middle = (first + last) / 2;
            fitQuay(chain, first, middle, runs);
            fitQuay(chain, middle, last, runs);
            return;
        }
        float[] along = construction(dx, dy);
        float nx = -along[1], ny = along[0];
        float offset = 0;
        int begin = last - first > 1 ? first + 1 : first, end = last - first > 1 ? last - 1 : last;
        for (int i = begin; i <= end; i++) { offset += nx * chain.get(i).point.x + ny * chain.get(i).point.y; }
        offset /= end - begin + 1;
        float low = Float.NEGATIVE_INFINITY, high = Float.POSITIVE_INFINITY;
        for (int i = begin; i <= end; i++) {
            for (Cell tile : chain.get(i).tiles) {
                float at = nx * HexTile.centerX(tile.coords(), hexWidth) + ny * HexTile.centerY(tile.coords(), hexHeight);
                if (outside(tile)) { low = Math.max(low, at + margin(tile, 18)); }
                else { high = Math.min(high, at - margin(tile, 16)); }
            }
        }
        if (low <= high) { offset = Math.clamp(offset, low, high); }
        Line line = new Line(nx, ny, offset);
        if (last - first == 1) {
            // An unsimplifiable single edge retains its endpoints.
            line = new Line(-dy / length, dx / length, (-dy * a.x + dx * a.y) / length);
        }
        int split = (first + last) / 2;
        float error = 0;
        for (int i = first + 1; i < last; i++) {
            float distance = Math.abs(line.side(chain.get(i).point));
            // Corners mirrored across a regular hex lie equally far: the first is split at, never what rounding picks.
            if (distance > error + .01f * scale) { error = distance; split = i; }
        }
        if (last - first > 1 && (error > 24 * scale || low > high)) {
            fitQuay(chain, first, split, runs);
            fitQuay(chain, split, last, runs);
        } else { runs.add(new Run(first, last, line, false)); }
    }

    /**
     * The construction axis nearest a run's direction, as a unit vector: one of the lattice's six edge and six
     * centre-to-centre directions, so walls along it meet the lattice's corners where they should.
     */
    private float[] construction(float dx, float dy) {
        int k = Math.floorMod(Math.round((float) (Math.atan2(dy, dx) / (Math.PI / 6))), 12);
        float[][] axes = { { 1, 0 }, { .75f * hexWidth, hexHeight / 2 }, { hexWidth / 4, hexHeight / 2 }, { 0, 1 },
              { -hexWidth / 4, hexHeight / 2 }, { -.75f * hexWidth, hexHeight / 2 } };
        float[] axis = axes[k % 6];
        float sign = k < 6 ? 1 : -1, length = (float) Math.hypot(axis[0], axis[1]);
        return new float[] { sign * axis[0] / length, sign * axis[1] / length };
    }

    private boolean clearEdge(Corner corner, Point a, Point b) {
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        if (length < .001f) { return false; }
        for (Cell tile : corner.tiles) {
            if (!corner.next.tiles.contains(tile)) { continue; }
            float cx = HexTile.centerX(tile.coords(), hexWidth), cy = HexTile.centerY(tile.coords(), hexHeight);
            float side = (dx * (cy - a.y) - dy * (cx - a.x)) / length;
            if (outside(tile) ? side > -margin(tile, 18) + .001f * scale : side < margin(tile, 16) - .001f * scale) { return false; }
            if (fixedBoundary(corner, tile)) {
                for (int k = 0; k < 6; k++) {
                    Point p = original(tile.coords(), k);
                    float clearance = dx * (p.y - a.y) - dy * (p.x - a.x);
                    if (outside(tile) ? clearance > .001f : clearance < -.001f) { return false; }
                }
            }
        }
        return true;
    }

    private float margin(Cell tile, float minimum) {
        // Unlike a quay, an inland cut can carry the full road/rail corridor. Leave room for its
        // axis-aligned ballast bounds and shoulder; placed-object checks still reject an actual collision.
        float route = RailRoute.BESIDE_RAIL + (float) Math.sqrt(2) * RailRoute.BALLAST_HALF_WIDTH + 1.5f;
        return (plateau != null && tile.wayExits() != 0 ? Math.max(minimum, route) : minimum) * scale;
    }

    private boolean protectedLand(Cell tile) {
        return fixedFootprint(tile) || plateau == null && (tile.wayExits() != 0 || tile.ramps() != 0);
    }

    private boolean fixedFootprint(Cell tile) {
        return tile.fixed() || pinned.contains(tile.coords());
    }

    /**
     * Whether the fit moves a wall or bank of hex {@code at} into a placed object there: an edge toward another level or
     * a liquid ({@link #wall}) whose corners moved sweeps at least 4 px² (at scale 1) of the object's footprint inside
     * its own hex ({@link #cut}). An edge moved away from the object, a touch, or a moved edge between two dry hexes of
     * one level (it only changes the ground beneath the object) does not count.
     */
    public boolean reaches(List<Point> footprint, Coords at) {
        return reaches(footprint, at, 0);
    }

    /** As above, excluding actual route openings whose connected ramp has no wall across that route. */
    public boolean reaches(List<Point> footprint, Coords at, int openings) {
        Cell tile = cell(at);
        for (int k = 0; k < 6; k++) {
            if ((openings & 1 << HexTile.edgeDirection(k)) != 0
                  || shift(at, k).equals(Shift.ZERO) && shift(at, (k + 1) % 6).equals(Shift.ZERO)
                  || !wall(tile, cell(at.translated(HexTile.edgeDirection(k))))) {
                continue;
            }
            if (cut(footprint, at, k) >= 4 * scale * scale) { return true; }
        }
        return false;
    }

    /** Whether the edge between two hexes is a wall or a bank: the levels differ, or one side holds a liquid. */
    public static boolean wall(Cell cell, Cell across) {
        return across != null && (across.level() != cell.level() || across.liquid() != cell.liquid());
    }

    /**
     * The area of a convex counter-clockwise footprint ({@link #hull}) inside its hex {@code at} that the fit sweeps
     * edge k over, from its original to its fitted place.
     */
    public float cut(List<Point> footprint, Coords at, int k) {
        List<Point> own = new ArrayList<>(6);
        for (int j = 0; j < 6; j++) { own.add(original(at, j)); }
        List<Point> inside = clip(footprint, hull(own));
        float cut = 0;
        for (List<Point> piece : sweep(original(at, k), original(at, (k + 1) % 6), corner(at, k), corner(at, (k + 1) % 6))) {
            cut += area(clip(inside, piece));
        }
        return cut;
    }

    /**
     * The shortest move that puts a convex footprint in hex {@code at} on the land side of every fitted wall and bank of
     * its hex ({@link #wall}), half a pixel clear (at scale 1); null when none exists within one side of the hex.
     */
    public Shift clearance(List<Point> footprint, Coords at) {
        Point c = center(at);
        Cell tile = cell(at);
        // Rows (nx, ny, limit): the move t keeps the footprint inside where n.t <= limit, n the fitted edge's outward normal.
        List<float[]> rows = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            if (!wall(tile, cell(at.translated(HexTile.edgeDirection(k))))) { continue; }
            Point a = corner(at, k), b = corner(at, (k + 1) % 6);
            float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
            if (length < .001f) { continue; }
            float nx = dy / length, ny = -dx / length;
            if (nx * (a.x + b.x - 2 * c.x) + ny * (a.y + b.y - 2 * c.y) < 0) { nx = -nx; ny = -ny; }
            float reach = Float.NEGATIVE_INFINITY;
            for (Point p : footprint) { reach = Math.max(reach, nx * (p.x - c.x) + ny * (p.y - c.y)); }
            rows.add(new float[] { nx, ny, nx * (a.x - c.x) + ny * (a.y - c.y) - .5f * scale - reach });
        }
        if (rows.stream().allMatch(row -> row[2] >= 0)) { return Shift.ZERO; }
        // The shortest feasible move lies on one row's boundary or where two rows' boundaries cross.
        List<float[]> candidates = new ArrayList<>();
        for (float[] row : rows) { candidates.add(new float[] { row[0] * row[2], row[1] * row[2] }); }
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                float[] u = rows.get(i), v = rows.get(j);
                float determinant = u[0] * v[1] - u[1] * v[0];
                if (Math.abs(determinant) < 1e-5f) { continue; }
                candidates.add(new float[] { (u[2] * v[1] - u[1] * v[2]) / determinant, (u[0] * v[2] - u[2] * v[0]) / determinant });
            }
        }
        float[] best = null;
        for (float[] t : candidates) {
            boolean feasible = rows.stream().allMatch(row -> row[0] * t[0] + row[1] * t[1] <= row[2] + 1e-3f);
            if (feasible && (best == null || Math.hypot(t[0], t[1]) < Math.hypot(best[0], best[1]))) { best = t; }
        }
        return best == null || Math.hypot(best[0], best[1]) > maxMove ? null : new Shift(best[0], best[1]);
    }

    /** A convex polygon clipped by a convex polygon, both counter-clockwise (Sutherland-Hodgman). */
    private static List<Point> clip(List<Point> subject, List<Point> clipper) {
        List<Point> out = subject;
        for (int i = 0; i < clipper.size() && !out.isEmpty(); i++) {
            Point a = clipper.get(i), b = clipper.get((i + 1) % clipper.size());
            List<Point> in = out;
            out = new ArrayList<>();
            for (int j = 0; j < in.size(); j++) {
                Point p = in.get(j), q = in.get((j + 1) % in.size());
                float sp = cross(a, b, p), sq = cross(a, b, q);
                if (sp >= 0) { out.add(p); }
                if ((sp >= 0) != (sq >= 0)) {
                    float t = sp / (sp - sq);
                    out.add(new Point(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y)));
                }
            }
        }
        return out;
    }

    /**
     * The region an edge sweeps from (a, b) to its fitted (a2, b2), as convex pieces: two triangles where the fitted
     * edge crosses the original one (one corner moved in, the other out), else the quad's hull.
     */
    private static List<List<Point>> sweep(Point a, Point b, Point a2, Point b2) {
        float d1 = cross(a, b, a2), d2 = cross(a, b, b2);
        if (d1 * d2 < 0) {
            float t = d1 / (d1 - d2);
            Point x = new Point(a2.x + t * (b2.x - a2.x), a2.y + t * (b2.y - a2.y));
            return List.of(hull(List.of(a, x, a2)), hull(List.of(x, b, b2)));
        }
        return List.of(hull(List.of(a, b, b2, a2)));
    }

    private static float area(List<Point> polygon) {
        float sum = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Point p = polygon.get(i), q = polygon.get((i + 1) % polygon.size());
            sum += p.x * q.y - p.y * q.x;
        }
        return Math.abs(sum) / 2;
    }

    /** The convex hull of points on the board plane (monotone chain), counter-clockwise. */
    public static List<Point> hull(List<Point> points) {
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble((Point p) -> p.x).thenComparingDouble(p -> p.y));
        List<Point> result = new ArrayList<>();
        for (int pass = 0; pass < 2 && !sorted.isEmpty(); pass++) {
            int start = result.size();
            for (Point p : sorted) {
                while (result.size() >= start + 2 && cross(result.get(result.size() - 2), result.getLast(), p) <= 0) {
                    result.removeLast();
                }
                result.add(p);
            }
            result.removeLast();
            Collections.reverse(sorted);
        }
        return result;
    }

    private static float cross(Point o, Point a, Point b) {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    }

    private boolean fixedBoundary(Corner corner, Cell tile) {
        if (plateau != null) {
            // The ramp mesh also owns its level mouths; moving either endpoint would leave its
            // unchanged road corridor unsupported. Unconnected wall runs can still be fitted.
            if (tile.ramps() != 0) {
                for (int e = 0; e < 6; e++) {
                    if (((tile.wayExits() | tile.ramps()) & 1 << HexTile.edgeDirection(e)) != 0
                          && (corner.original.dst2(original(tile.coords(), e)) < .001f
                                || corner.original.dst2(original(tile.coords(), (e + 1) % 6)) < .001f)) { return true; }
                }
            }
            return fixedFootprint(tile) || tile.ultraSublevel();
        }
        // Moving a material boundary on one ground plane preserves the complete neighbouring foundation.
        // Structures on the concrete itself, height changes and road ramps keep their original footprint.
        return !tile.liquid() && protectedLand(tile)
              && (!outside(tile) || tile.ultraSublevel() || tile.ramps() != 0
                    || corner.tiles.stream().anyMatch(other -> other.liquid() || other.level() != tile.level()));
    }

    private boolean outside(Cell tile) {
        return tile != null && !tile.molten() && (tile.liquid() || everywhere && (!tile.concrete()
              || plateau != null && (slab != 0 ? tile.level() == plateau && slab(tile) < slab : tile.level() < plateau)));
    }

    private float dockHalf(Cell land, float[] axis) {
        float nx = -axis[1], ny = axis[0], half = Float.POSITIVE_INFINITY;
        float cx = HexTile.centerX(land.coords(), hexWidth), cy = HexTile.centerY(land.coords(), hexHeight);
        // The end edge supplies the dock's width; intermediate hex tips are clipped to its parallel sides.
        for (int k = 0; k < 6; k++) {
            Point corner = original(land.coords(), k);
            half = Math.min(half, Math.abs((corner.x - cx) * nx + (corner.y - cy) * ny));
        }
        return half;
    }

    /** Zero links: platform; one: end; two opposite: span; two others: angle. Other patterns are quay landings. */
    private int dockLinks(Cell land) {
        if (plateau != null) { return -1; }
        if (land.liquid() || !land.concrete() || protectedLand(land)) { return -1; }
        int links = 0;
        Integer waterLevel = null;
        for (int d = 0; d < 6; d++) {
            Cell other = cell(land.coords().translated(d));
            if (other == null) { return -1; }
            if (outside(other)) {
                if (other.liquid()) {
                    if (other.frozen() || other.level() > land.level()
                          || waterLevel != null && waterLevel != other.level()) { return -1; }
                    waterLevel = other.level();
                }
            } else {
                if (!other.concrete() || other.level() != land.level()) { return -1; }
                links |= 1 << d;
            }
        }
        return Integer.bitCount(links) <= 2 ? links : -1;
    }

    private float[] axis(Coords coords, int direction) {
        var next = coords.translated(direction);
        float dx = HexTile.centerX(next, hexWidth) - HexTile.centerX(coords, hexWidth);
        float dy = HexTile.centerY(next, hexHeight) - HexTile.centerY(coords, hexHeight);
        float length = (float) Math.hypot(dx, dy);
        return new float[] { dx / length, dy / length };
    }
}
