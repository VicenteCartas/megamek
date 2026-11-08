/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.ConcreteFit;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;

/**
 * The scene's fitted concrete outline ({@link ConcreteFit}), shared with adjoining terrain, water, artwork and picking:
 * cached per scene, with the hexes whose placed objects pin them. No game state or GL objects.
 */
final class BoardConcrete {
    /** One immutable derived outline, shared by terrain and picking; no scene, artwork or GL resources are retained. */
    private record Cached(WeakReference<List<BoardScene.Tile>> tiles, int width, int height, float scale,
          BoardConcrete shape) {
        boolean matches(BoardScene scene) {
            return tiles.get() == scene.tiles() && width == scene.width() && height == scene.height()
                  && scale == BoardGeometry.hexScale();
        }
    }
    // Retain the displayed and pending outlines. A cache miss must not hold a monitor during board construction.
    private static volatile Cached cached, previous;
    private final ConcreteFit fit;
    private final List<Cut> cuts;

    /** A placed object inside a wall or bank of its hex, whose Edges are Fitted: the editor lists it as an issue. */
    record Cut(Coords at, String object, String asset) { }

    static BoardConcrete of(BoardScene scene) {
        Cached first = cached, second = previous;
        if (first != null && first.matches(scene)) { return first.shape(); }
        if (second != null && second.matches(scene)) { return second.shape(); }
        BoardConcrete shape = new BoardConcrete(scene);
        synchronized (BoardConcrete.class) {
            if (cached != null && cached.matches(scene)) { return cached.shape(); }
            previous = cached;
            cached = new Cached(new WeakReference<>(scene.tiles()), scene.width(), scene.height(), BoardGeometry.hexScale(), shape);
        }
        return shape;
    }

    /** A scene whose concrete inputs are unchanged keeps the previous fit; the cache then serves it for the new tiles. */
    static BoardConcrete adopt(BoardScene scene, BoardConcrete shape) {
        synchronized (BoardConcrete.class) {
            previous = cached;
            cached = new Cached(new WeakReference<>(scene.tiles()), scene.width(), scene.height(), BoardGeometry.hexScale(), shape);
        }
        return shape;
    }

    /** The fit itself, with the hexes the scene's placed objects pin. */
    ConcreteFit fit() { return fit; }

    ConcreteFit.Shift shift(long corner) { return fit.shift(corner); }

    /** The same fitted corner for terrain, water, artwork and picking in both GPU views. */
    Vector3 corner(Coords coords, int corner) {
        ConcreteFit.Shift delta = fit.shift(coords, Math.floorMod(corner, 6));
        return BoardGeometry.corner(coords, 0, corner).add(delta.x(), delta.y(), 0);
    }

    /** Signed distance to the fitted footprint; NaN means this hex still uses its original outline. */
    float distance(Coords coords, float x, float y) {
        float[] outline = fit.footprint(coords);
        if (outline == null) { return Float.NaN; }
        // The nearest edge is chosen on exact squared offsets; only that offset is measured, as every edge was.
        double nearest = Double.POSITIVE_INFINITY;
        float nearestX = 0, nearestY = 0, ax = 0, ay = 0;
        boolean inside = false;
        for (int k = 0; k <= 6; k++) {
            int index = (k % 6) * 2;
            float bx = outline[index], by = outline[index + 1];
            if (k > 0) {
                float dx = bx - ax, dy = by - ay;
                float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / Math.max(dx * dx + dy * dy, .00001f), 0, 1);
                float px = x - ax - t * dx, py = y - ay - t * dy;
                double squared = (double) px * px + (double) py * py;
                if (squared < nearest) {
                    nearest = squared;
                    nearestX = px;
                    nearestY = py;
                }
                if ((ay > y) != (by > y) && x < ax + (y - ay) * dx / dy) { inside = !inside; }
            }
            ax = bx;
            ay = by;
        }
        float distance = (float) Math.hypot(nearestX, nearestY);
        return inside ? -distance : distance;
    }

    List<ConcreteFit.Shift> corners(Coords coords) { return fit.corners(coords); }

    boolean sameCorners(BoardConcrete other, Coords coords) {
        return other != null && fit.sameCorners(other.fit, coords);
    }

    private BoardConcrete(BoardScene scene) {
        List<ConcreteFit.Cell> cells = new ArrayList<>(scene.tiles().size());
        for (BoardScene.Tile tile : scene.tiles()) { cells.add(cell(scene, tile)); }
        // A placed object keeps its hex's outline only where the fit moves a wall or bank into it. Pinning its hex can move
        // other edges, so the fit repeats until it reaches no further object; the set of pinned hexes only grows.
        Set<Coords> pins = new HashSet<>();
        ConcreteFit next = fit(scene, cells, pins);
        for (Set<Coords> reached = reached(scene, next, pins); !reached.isEmpty(); reached = reached(scene, next, pins)) {
            pins.addAll(reached);
            next = fit(scene, cells, pins);
        }
        fit = next;
        List<Cut> inside = new ArrayList<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (edges(tile) != HexAppearance.Edges.FITTED || fit.footprint(tile.coords()) == null) { continue; }
            for (BoardScene.Feature feature : tile.features()) {
                if (feature.decoration() == null || !fitFeature(feature)) { continue; }
                List<ConcreteFit.Point> footprint = footprint(scene, tile, feature);
                if (footprint != null && reaches(scene, fit, tile, feature, footprint)) {
                    inside.add(new Cut(tile.coords(), feature.decoration().id(), feature.decoration().asset()));
                }
            }
        }
        cuts = List.copyOf(inside);
    }

    /**
     * A pavement hex's {@link HexAppearance.Edges}, the one check of the 3D views: Hex keeps the hex's outline, Fitted is
     * never pinned by its objects ({@link #cuts} lists those it cuts). Auto on any other ground.
     */
    static HexAppearance.Edges edges(BoardScene.Tile tile) {
        return tile.surface() == BoardScene.Surface.CONCRETE ? HexAppearance.edges(tile.appearance()) : HexAppearance.Edges.AUTO;
    }

    /** The placed objects that stand inside a wall or bank of their Fitted hex, in board order. */
    List<Cut> cuts() { return cuts; }

    private static ConcreteFit fit(BoardScene scene, List<ConcreteFit.Cell> cells, Set<Coords> pins) {
        return new ConcreteFit(scene.width(), scene.height(), cells, BoardGeometry.hexScale(), true, pins);
    }

    /** What the fit reads of a tile: structures and ground drawn without the fitted relief keep their hex. */
    private static ConcreteFit.Cell cell(BoardScene scene, BoardScene.Tile tile) {
        boolean fixed = !tile.detailedGround() || edges(tile) == HexAppearance.Edges.HEX
              || tile.features().stream().anyMatch(feature -> fitFeature(feature) && feature.decoration() == null);
        return new ConcreteFit.Cell(tile.coords(), tile.elevation(), tile.surface() == BoardScene.Surface.CONCRETE,
              tile.liquid().present(), tile.liquid().molten(), tile.frozen(), tile.ultraSublevel(), fixed, tile.wayExits(),
              BoardSurface.ramps(scene, tile), tile.pavementSides());
    }

    /**
     * The features the fit reads, the one predicate {@code GpuTerrain#concreteInputsChanged} compares too: structures
     * (buildings, industrial, bridges, fuel tanks), which keep their hex's outline, and placed objects, which keep it
     * only where the fit moves a wall or bank into them ({@link #reached}).
     */
    static boolean fitFeature(BoardScene.Feature feature) {
        return feature.kind() == BoardScene.FeatureKind.BUILDING || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL
              || feature.kind() == BoardScene.FeatureKind.FUEL_TANK || feature.kind() == BoardScene.FeatureKind.PROP;
    }

    /**
     * The hexes, not yet pinned and not Fitted, holding a placed object that a wall or bank this fit moved reaches
     * ({@link ConcreteFit#reaches}). An object whose footprint is unknown keeps its hex, as a structure does.
     */
    private static Set<Coords> reached(BoardScene scene, ConcreteFit fit, Set<Coords> pins) {
        Set<Coords> result = new HashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            // Only a hex the fit moved has a footprint of its own.
            if (fit.footprint(tile.coords()) == null || pins.contains(tile.coords())
                  || edges(tile) == HexAppearance.Edges.FITTED) { continue; }
            for (BoardScene.Feature feature : tile.features()) {
                if (feature.decoration() == null || !fitFeature(feature)) { continue; }
                List<ConcreteFit.Point> footprint = footprint(scene, tile, feature);
                if (footprint == null || reaches(scene, fit, tile, feature, footprint)) { result.add(tile.coords()); break; }
            }
        }
        return result;
    }

    /** Ground rails cross their graded openings; their side walls still have to clear the track. */
    private static boolean reaches(BoardScene scene, ConcreteFit fit, BoardScene.Tile tile,
          BoardScene.Feature feature, List<ConcreteFit.Point> footprint) {
        int openings = BoardRail.route(feature) && RailRoute.kind(feature.decoration()) == RailRoute.Kind.RAIL
              && !RailRoute.onDeck(feature.decoration()) && BoardRail.level(tile, feature) == tile.elevation()
              ? BoardSurface.ramps(scene, tile) & BoardRail.drawnSides(tile, feature) : 0;
        return fit.reaches(footprint, tile.coords(), openings);
    }

    /** A placed object's footprint on the board plane: the hull of {@link BoardObstacles#outline}; null when unknown. */
    private static List<ConcreteFit.Point> footprint(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        List<Vector3> object = BoardObstacles.outline(scene, tile, feature);
        if (object == null) { return null; }
        List<ConcreteFit.Point> points = new ArrayList<>(object.size());
        for (Vector3 p : object) { points.add(new ConcreteFit.Point(p.x, p.y)); }
        return ConcreteFit.hull(points);
    }

    static boolean concreteBank(BoardScene scene, BoardScene.Tile water, int edge) {
        BoardScene.Tile land = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(edge)));
        return land != null && land.surface() == BoardScene.Surface.CONCRETE && !land.liquid().present()
              && land.elevation() >= water.elevation();
    }
}
