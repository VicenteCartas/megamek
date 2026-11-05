/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/** Z-up, tightly tiled hex columns. Rendering and picking use these same surfaces. */
final class BoardGeometry {
    /** One level in authored board models, in board model units. */
    static final int MODEL_LEVEL_HEIGHT = HexTile.LEVEL_HEIGHT;
    /** Independent default for a whole unit occupying more than one game hex. */
    static final float DEFAULT_MULTI_HEX_UNIT_SCALE = 1.0f;
    /** Whether steps between hexes take room on both sides of their edge; see {@link Tuning#transitions()}. */
    static final boolean DEFAULT_TRANSITIONS = true;
    /** Metres of gap between neighbouring hex tiles; 0 keeps them joined. See {@link Tuning#padding()}. */
    static final float DEFAULT_PADDING = 0;
    /** Beyond this the room of a step eats so far into its hexes that their tops fold (measured with FoldProbe). */
    static final float MAX_PADDING = 8;

    /**
     * Board presentation settings. {@code transitions}: each step between hexes of different levels takes room on
     * both sides of the shared edge, a slope up to two levels and a deep cliff from three (see {@link BoardRelief}).
     * {@code padding}: metres of gap the board opens between neighbouring hexes. The lattice spreads apart while every
     * hex keeps its own size; where levels match the ground runs on through the gap, and where they differ the gap holds
     * the step, a slope up to two levels and a cliff above its talus from three. Padding and transitions both lay steps
     * out in the room beside an edge, so padding turns transitions off.
     */
    record Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade,
          float multiHexUnitScale, boolean transitions, float padding) {
        Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade) {
            this(hexScale, unitScale, unitHeightScale, levelHeight, gridShade, DEFAULT_MULTI_HEX_UNIT_SCALE);
        }

        Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade,
              float multiHexUnitScale) {
            this(hexScale, unitScale, unitHeightScale, levelHeight, gridShade, multiHexUnitScale, DEFAULT_TRANSITIONS);
        }

        Tuning(float hexScale, float unitScale, float unitHeightScale, int levelHeight, float gridShade,
              float multiHexUnitScale, boolean transitions) {
            this(hexScale, unitScale, unitHeightScale, levelHeight, gridShade, multiHexUnitScale, transitions,
                  DEFAULT_PADDING);
        }

        Tuning {
            if (!Float.isFinite(hexScale) || hexScale <= 0 || !Float.isFinite(unitScale) || unitScale <= 0
                  || !Float.isFinite(unitHeightScale) || unitHeightScale <= 0 || levelHeight < 1
                  || !Float.isFinite(gridShade) || gridShade < 0 || gridShade > 1
                  || !Float.isFinite(multiHexUnitScale) || multiHexUnitScale <= 0
                  || !Float.isFinite(padding) || padding < 0 || padding > MAX_PADDING) {
                throw new IllegalArgumentException("Invalid board dimensions");
            }
            transitions &= padding == 0;
        }

        /**
         * Whether a step's face can lie back into the room beside its edge, so the ground at a point may be that face
         * rather than a hex's top: with hex transitions or padding.
         */
        boolean stepsBetweenTops() {
            return transitions || padding > 0;
        }
    }

    static final Tuning DEFAULTS = new Tuning(1, 1.0f, 1.0f, MODEL_LEVEL_HEIGHT, 0.8f);
    /**
     * Native tactical markers keep this fraction of the hex radius clear of the shared hex edges. Exactly on
     * an edge a marker is coplanar with the terrain there and flickers against it while the camera rotates.
     */
    static final float MARKER_INSET = 0.1f;
    static final float TILE_WIDTH = HexTile.MODEL_WIDTH;
    static final float TILE_HEIGHT = HexTile.HEIGHT;
    static float HEX_SCALE;
    static float WIDTH;
    static float HEIGHT;
    static float LEVEL;
    static float UNIT_SCALE;
    static float MULTI_HEX_UNIT_SCALE;
    static float UNIT_HEIGHT_SCALE;
    private static Tuning tuning;
    private static int revision;
    private static int terrainRevision;

    static {
        tune(DEFAULTS);
    }

    private BoardGeometry() { }

    static Tuning tuning() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? tuning : settings.geometry();
    }

    static float hexScale() { return tuning().hexScale(); }
    static float width() { return TILE_WIDTH * hexScale(); }
    static float height() { return TILE_HEIGHT * hexScale(); }
    static float level() {
        Tuning settings = tuning();
        return settings.levelHeight() * settings.hexScale();
    }
    static float unitScale() { return tuning().unitScale(); }
    static float multiHexUnitScale() { return tuning().multiHexUnitScale(); }
    static float unitHeightScale() { return tuning().unitHeightScale(); }

    static void tune(Tuning next) {
        if (next.equals(tuning)) {
            return;
        }
        tuning = next;
        HEX_SCALE = next.hexScale();
        WIDTH = TILE_WIDTH * HEX_SCALE;
        HEIGHT = TILE_HEIGHT * HEX_SCALE;
        LEVEL = next.levelHeight() * HEX_SCALE;
        UNIT_SCALE = next.unitScale();
        MULTI_HEX_UNIT_SCALE = next.multiHexUnitScale();
        UNIT_HEIGHT_SCALE = next.unitHeightScale();
        revision++;
    }

    static int revision() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? revision : settings.revision();
    }

    /** Terrain controls are applied on the GL thread between frames, like the board dimensions above. */
    static void terrainChanged() {
        terrainRevision++;
        revision++;
    }

    static int terrainRevision() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? terrainRevision : settings.terrainRevision();
    }

    static float centerX(Coords coords) {
        return HexTile.centerX(coords, width());
    }

    static float centerY(Coords coords) {
        return HexTile.centerY(coords, height());
    }

    static Vector3 center(Coords coords, float elevation) {
        return new Vector3(centerX(coords), centerY(coords), elevation * level());
    }

    static Vector3 corner(Coords coords, float elevation, int corner) {
        return corner(new Vector3(), coords, elevation, corner);
    }

    /** A corner in model-local units, without a world-position or presentation-scale round trip. */
    static Vector3 modelCorner(int corner) {
        int k = Math.floorMod(corner, 6);
        return new Vector3(HexTile.cornerOffsetX(k, TILE_WIDTH), HexTile.cornerOffsetY(k, TILE_HEIGHT), 0);
    }

    /**
     * Corners lie on a lattice of quarter widths and half heights. Computing them from lattice indices gives every
     * hex that shares a corner bit-identical coordinates, independently of the hex scale.
     */
    static Vector3 corner(Vector3 out, Coords coords, float elevation, int corner) {
        int k = Math.floorMod(corner, 6);
        return out.set(HexTile.cornerX(coords, k, width()), HexTile.cornerY(coords, k, height()), elevation * level());
    }

    /** The parameter interval of a world-space segment inside a hex; boundary segments belong to both neighbours. */
    static float[] crossing(Vector3 start, Vector3 end, Coords coords) {
        float enter = 0, exit = 1;
        for (int corner = 0; corner < 6; corner++) {
            Vector3 a = corner(coords, 0, corner);
            Vector3 b = corner(coords, 0, corner + 1);
            float ex = b.x - a.x, ey = b.y - a.y;
            float offset = ex * (start.y - a.y) - ey * (start.x - a.x);
            float slope = ex * (end.y - start.y) - ey * (end.x - start.x);
            if (Math.abs(slope) < 0.001f) {
                if (offset < -0.001f) { return null; }
            } else if (slope > 0) {
                enter = Math.max(enter, -offset / slope);
            } else {
                exit = Math.min(exit, -offset / slope);
            }
        }
        return enter <= exit ? new float[] { enter, exit } : null;
    }

    static int edgeDirection(int edge) {
        return HexTile.edgeDirection(edge);
    }

    /**
     * How far (x, y) lies from the hex's centre towards its edge in this direction, square to that edge: 0 on the line
     * through the centre parallel to it, 1 all along it, so both hexes sharing the edge agree there.
     */
    static float share(Coords coords, int direction, float x, float y) {
        int edge = Math.floorMod(1 - direction, 6);
        float ax = HexTile.cornerOffsetX(edge, TILE_WIDTH), ay = HexTile.cornerOffsetY(edge, TILE_HEIGHT);
        float bx = HexTile.cornerOffsetX((edge + 1) % 6, TILE_WIDTH);
        float by = HexTile.cornerOffsetY((edge + 1) % 6, TILE_HEIGHT);
        float scale = hexScale(), px = (x - centerX(coords)) / scale, py = (y - centerY(coords)) / scale;
        // Signed distance towards the centre is positive; both corners of a lattice edge lie at the same distance.
        float inradius = (ax * by - ay * bx) / (float) Math.hypot(bx - ax, by - ay);
        float inside = ((bx - ax) * (py - ay) - (by - ay) * (px - ax)) / (float) Math.hypot(bx - ax, by - ay);
        return 1 - inside / inradius;
    }

    /** Pulls a point toward a hex center inside the hex plane; the point keeps its elevation. */
    static Vector3 inset(Vector3 point, Vector3 center, float fraction) {
        return point.set(point.x + (center.x - point.x) * fraction,
              point.y + (center.y - point.y) * fraction, point.z);
    }

    /** Liquid beds include a two-world-unit visual recess even without positive game water depth. */
    static float groundZ(BoardScene.Tile tile) {
        return tile.groundLevel() * level() - (tile.liquid().present() ? Math.max(2 * hexScale(), tile.waterDepth() * level()) : 0);
    }

    static float waterZ(BoardScene.Tile tile) {
        return tile.elevation() * level() - hexScale();
    }

    static float surfaceZ(BoardScene.Tile tile) {
        if (tile.frozen()) {
            return tile.elevation() * level();
        }
        return tile.liquid().present() ? waterZ(tile) : groundZ(tile);
    }

    static float floor(BoardScene scene) {
        float lowest = Float.POSITIVE_INFINITY;
        float depth = level();
        for (BoardScene.Tile tile : scene.tiles()) {
            lowest = Math.min(lowest, groundZ(tile));
            // At small elevation-height settings, sculpted hollows can extend below a single game level.
            depth = Math.max(depth, BoardRelief.headroom(tile));
        }
        return lowest - depth;
    }

    /** Lowest hex elevation for fog, precipitation and volume bounds, never a riverbed or model height. */
    static float weatherBase(BoardScene scene) {
        return scene.tiles().stream().mapToInt(BoardScene.Tile::elevation).min().orElse(0) * level();
    }

    /** Sand ignores negative levels when at least 75% of border hexes have nonnegative elevations. */
    static float sandBase(BoardScene scene) {
        int lowest = Integer.MAX_VALUE, lowestNonnegative = Integer.MAX_VALUE;
        int border = 0, nonnegativeBorder = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            int elevation = tile.elevation();
            Coords at = tile.coords();
            // Each border hex counts once, including corners and one-row/one-column boards.
            if (at.getX() == 0 || at.getY() == 0 || at.getX() == scene.width() - 1 || at.getY() == scene.height() - 1) {
                border++;
                if (elevation >= 0) { nonnegativeBorder++; }
            }
            // An ultra-sublevel never supplies the sand layer's baseline.
            if (tile.ultraSublevel()) { continue; }
            lowest = Math.min(lowest, elevation);
            if (elevation >= 0) { lowestNonnegative = Math.min(lowestNonnegative, elevation); }
        }
        if (border > 0 && nonnegativeBorder * 4L >= border * 3L && lowestNonnegative != Integer.MAX_VALUE) {
            return lowestNonnegative * level();
        }
        return (lowest == Integer.MAX_VALUE ? 0 : lowest) * level();
    }

    static boolean contains(Coords coords, float x, float y) {
        return HexTile.contains(coords.getX(), coords.getY(), x, y, width(), height());
    }

    /** The hex whose footprint holds (x, y), or null off the board. */
    static BoardScene.Tile tile(BoardScene scene, float x, float y) {
        int index = HexTile.at(x, y, width(), height(), scene.width(), scene.height());
        return index < 0 ? null : scene.tiles().get(index);
    }

    record Hit(Coords coords, float distance, boolean hardSurface, String receiver, BoardSurface.Face face) {
        Hit(Coords coords, float distance, boolean hardSurface, String receiver) { this(coords, distance, hardSurface, receiver, null); }
        Hit(Coords coords, float distance, boolean hardSurface) { this(coords, distance, hardSurface, "ground"); }
        Hit(Coords coords, float distance) { this(coords, distance, false); }
    }

    static Coords pick(BoardScene scene, Ray ray) {
        Hit hit = hit(scene, ray);
        return hit == null ? null : hit.coords();
    }

    /** Intersect the same triangles used to draw carved roads, banks, beds and exposed sides. */
    static Hit hit(BoardScene scene, Ray ray) {
        return hit(scene, ray, scene.tiles(), floor(scene));
    }

    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor) {
        return hit(scene, ray, candidates, floor, (BoardSurface.Cache) null);
    }

    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor, BoardSurface.Cache cache) {
        return hit(scene, ray, candidates, floor, cache, null, false);
    }

    /** Borrow the installed terrain's finished geometry without retaining its builders or scene. */
    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        return hit(scene, ray, candidates, floor, null, surfaces, false);
    }

    /**
     * The finished {@code surfaces}, else {@code cache}'s. With {@code throughWater} a see-through liquid's surface
     * gives way to what lies beneath it, its bed (molten lava stays opaque).
     */
    static Hit hit(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor,
          BoardSurface.Cache cache, Function<Coords, BoardTacticalGeometry.Surface> surfaces, boolean throughWater) {
        Hit hit = nearest(scene, ray, candidates, floor, cache, surfaces, throughWater);
        if (hit == null) {
            // A ray exactly along a shared triangle edge can miss both triangles in float arithmetic. Symmetry lines
            // through hex centres make that reproducible for axis-aligned pointer rays, so retry once, nudged.
            Ray nudged = new Ray(new Vector3(ray.origin).add(.0013f * hexScale(), .0007f * hexScale(), 0), ray.direction);
            hit = nearest(scene, nudged, candidates, floor, cache, surfaces, throughWater);
        }
        return hit;
    }

    /**
     * The lower neighbour for a hit on the talus inside its footprint; otherwise the cliff's owner. With transitions or
     * padding a step's lower half lies in the lower hex's footprint, and a hit there belongs to it.
     */
    private static Coords foot(BoardScene scene, Coords owner, Vector3 hit) {
        if (contains(owner, hit.x, hit.y)) { return owner; }
        BoardScene.Tile high = scene.tile(owner);
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.tile(owner.translated(direction));
            if (neighbor == null || !contains(neighbor.coords(), hit.x, hit.y)) { continue; }
            float talus = level() * .3f;
            if (tuning().stepsBetweenTops() && high != null) {
                talus = Math.max(talus, (high.elevation() * level() - surfaceZ(neighbor)) * .5f);
            }
            if (hit.z < surfaceZ(neighbor) + talus) { return neighbor.coords(); }
        }
        return owner;
    }

    /**
     * The hex whose footprint holds a hit on a top, bank, bed or water: its owner, or the neighbour whose footprint
     * holds it where the owner's faces reach past its own, as a water hex's shore does over a land corner.
     */
    private static Coords footprint(BoardScene scene, Coords owner, Vector3 hit) {
        if (contains(owner, hit.x, hit.y)) { return owner; }
        for (int direction = 0; direction < 6; direction++) {
            Coords other = owner.translated(direction);
            if (scene.tile(other) != null && contains(other, hit.x, hit.y)) { return other; }
        }
        return owner;
    }

    private record PickCandidate(BoardScene.Tile tile, float entry, int order) { }

    private static Hit nearest(BoardScene scene, Ray ray, Iterable<BoardScene.Tile> candidates, float floor,
          BoardSurface.Cache cache, Function<Coords, BoardTacticalGeometry.Surface> surfaces, boolean throughWater) {
        Coords result = null;
        float nearest = Float.POSITIVE_INFINITY;
        boolean hardSurface = false;
        BoardSurface.Face struck = null;
        Vector3 hit = new Vector3();
        var ordered = new ArrayList<PickCandidate>();
        var bounds = new BoundingBox();
        // Sculpted cliffs, talus and rim lips can reach slightly beyond the logical footprint.
        float reach = 2 * BoardRelief.overhang();
        float halfWidth = (width() + reach) / 2, halfHeight = (height() + reach) / 2;
        int order = 0, nearestOrder = Integer.MAX_VALUE;
        boolean vertical = ray.direction.x == 0 && ray.direction.y == 0;
        for (BoardScene.Tile tile : candidates) {
            float x = centerX(tile.coords()), y = centerY(tile.coords());
            // Whether the ray passes over the footprint at all before its top, which takes the neighbours: a ray straight
            // down passes over a hex and its neighbours only.
            bounds.set(new Vector3(x - halfWidth, y - halfHeight, floor - .005f),
                  new Vector3(x + halfWidth, y + halfHeight, 1e9f));
            if (!Intersector.intersectRayBounds(ray, bounds, hit)) {
                order++;
                continue;
            }
            float high = tile.elevation() * level();
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
                high = Math.max(high, BoardRoadGrade.edgeLevel(tile, neighbor, direction) * level());
                // A neighbour's wide carriageway can reserve a sliver of this hex at its own grade.
                if (neighbor != null && BoardRoad.carriageway(neighbor)) {
                    high = Math.max(high, neighbor.elevation() * level());
                }
            }
            high += BoardRelief.decoration(tile);
            bounds.max.z = high + .005f;
            bounds.update();
            if (Intersector.intersectRayBounds(ray, bounds, hit)) {
                ordered.add(new PickCandidate(tile, ray.origin.dst2(hit), order));
            }
            order++;
        }
        ordered.sort(Comparator.comparingDouble(PickCandidate::entry));
        for (PickCandidate candidate : ordered) {
            // A low camera ray may cross the entire board. Do not reconstruct surfaces hidden behind the first hit.
            if (candidate.entry() > nearest) { break; }
            BoardScene.Tile tile = candidate.tile();
            BoardSurface surface = surfaces != null ? null
                  : cache == null ? new BoardSurface(scene, tile) : cache.get(scene, tile);
            BoardTacticalGeometry.Surface finished = surfaces == null ? null : surfaces.apply(tile.coords());
            for (BoardSurface.Face face : under(finished == null ? surface.faces : finished.faces(), ray, vertical)) {
                if (crosses(ray, vertical, face, hit)
                      && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                    nearest = ray.origin.dst2(hit);
                    nearestOrder = candidate.order();
                    // Rim rocks and a waterfall's bed lip can overhang a lower hex just like its cliff.
                    // A high bed hit belongs to that upper pool; only the low talus belongs to the receiving hex.
                    result = face.finish() == BoardSurface.Finish.OUTCROP || face.finish() == BoardSurface.Finish.BED
                          ? foot(scene, tile.coords(), hit) : footprint(scene, tile.coords(), hit);
                    hardSurface = hardSurface(tile, face);
                    struck = face;
                }
            }
            for (BoardSurface.Face face : throughWater && !tile.liquid().molten() ? List.<BoardSurface.Face>of()
                  : under(finished == null ? surface.waterFaces : finished.water(), ray, vertical)) {
                if (crosses(ray, vertical, face, hit)
                      && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                    nearest = ray.origin.dst2(hit);
                    nearestOrder = candidate.order();
                    result = footprint(scene, tile.coords(), hit);
                    hardSurface = false;
                    struck = null;
                }
            }
            for (BoardSurface.Face face : under(finished == null ? surface.walls(scene, floor) : finished.walls(), ray, vertical)) {
                if (crosses(ray, vertical, face, hit)
                      && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                    nearest = ray.origin.dst2(hit);
                    nearestOrder = candidate.order();
                    // A rock face belongs to the higher hex that owns it; the scree at its foot, which spreads
                    // past the logical edge, lies on the lower hex whose footprint contains it.
                    result = foot(scene, tile.coords(), hit);
                    hardSurface = hardSurface(tile, face);
                    struck = face;
                }
            }
            if (tile.frozen()) {
                // Installed pointer queries borrow the completed draw groups.
                for (var fall : finished == null ? surface.frozenFalls() : finished.frozenFalls()) {
                    for (BoardSurface.Face face : fall.faces()) {
                        if (crosses(ray, vertical, face, hit)
                              && nearer(ray.origin.dst2(hit), nearest, candidate.order(), nearestOrder)) {
                            nearest = ray.origin.dst2(hit);
                            nearestOrder = candidate.order();
                            result = tile.coords();
                            hardSurface = false;
                            struck = face;
                        }
                    }
                }
            }
            for (BoardSurface.Side side : tile.frozen() ? List.<BoardSurface.Side>of()
                  : finished == null ? surface.waterfalls : finished.waterfalls()) {
                Vector3 lowerA = new Vector3(side.a().x, side.a().y, side.lowA());
                Vector3 lowerB = new Vector3(side.b().x, side.b().y, side.lowB());
                float distance = Float.POSITIVE_INFINITY;
                if (Intersector.intersectRayTriangle(ray, side.a(), lowerA, lowerB, hit)) {
                    distance = ray.origin.dst2(hit);
                }
                if (Intersector.intersectRayTriangle(ray, side.a(), lowerB, side.b(), hit)) {
                    distance = Math.min(distance, ray.origin.dst2(hit));
                }
                if (Float.isFinite(distance) && nearer(distance, nearest, candidate.order(), nearestOrder)) {
                    nearest = distance;
                    nearestOrder = candidate.order();
                    result = tile.coords();
                    hardSurface = false;
                    struck = null;
                }
            }
        }
        return result == null ? null : new Hit(result, nearest, hardSurface, "ground", struck);
    }

    /** The faces a ray may cross: of a {@code vertical} one only those under it, where the list keeps their outlines. */
    private static List<BoardSurface.Face> under(List<BoardSurface.Face> faces, Ray ray, boolean vertical) {
        return vertical && faces instanceof BoardTacticalGeometry.Faces indexed ? indexed.under(ray.origin.x, ray.origin.y)
              : faces;
    }

    /** Whether the ray crosses the face: a {@code vertical} ray, straight up or down, only where the face lies under it. */
    private static boolean crosses(Ray ray, boolean vertical, BoardSurface.Face face, Vector3 hit) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float x = ray.origin.x, y = ray.origin.y;
        if (vertical && (x < Math.min(a.x, Math.min(b.x, c.x)) || x > Math.max(a.x, Math.max(b.x, c.x))
              || y < Math.min(a.y, Math.min(b.y, c.y)) || y > Math.max(a.y, Math.max(b.y, c.y)))) {
            return false;
        }
        return Intersector.intersectRayTriangle(ray, a, b, c, hit);
    }

    /** Preserve the original candidate order when shared-edge triangles have exactly the same hit distance. */
    private static boolean nearer(float distance, float nearest, int order, int nearestOrder) {
        return distance < nearest || distance == nearest && order < nearestOrder;
    }

    private static boolean hardSurface(BoardScene.Tile tile, BoardSurface.Face face) {
        if (face.finish() == BoardSurface.Finish.ICE || face.finish() == BoardSurface.Finish.DRESSING) { return false; }
        return face.finish() == BoardSurface.Finish.OUTCROP
              || tile.surface() == BoardScene.Surface.ROCK || tile.surface() == BoardScene.Surface.LUNAR
              || tile.surface() == BoardScene.Surface.CONCRETE
              || tile.surface() == BoardScene.Surface.GRASS && face.finish() == BoardSurface.Finish.WALL;
    }
}
