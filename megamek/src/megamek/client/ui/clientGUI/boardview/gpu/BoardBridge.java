/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;

/**
 * The shared deck shape and its presentation: its family (natural, truss or standard, {@link #family}) and the road it
 * carries, the stored {@code bridge_road} ({@link BoardScene.Tile#deckRoad}).
 */
final class BoardBridge {
    static final float NATURAL_HALF_WIDTH = 8;
    /** The built deck's shoulder scales with its carriageway; ground-road shoulders remain narrower. */
    static final float SHOULDER = HexTile.ROAD_HALF_WIDTH / 5;
    /** Half the passage both deck styles reserve, in model px: a natural mouth or a paved lane and its shoulder. */
    static final float PASSAGE_HALF_WIDTH = Math.max(NATURAL_HALF_WIDTH * 1.04f * BoardGeometry.TILE_WIDTH / 30,
          BoardRoad.Kind.PAVED.halfWidth + SHOULDER);
    /** Slab beneath the deck surface, model px (deck top Z = 0, slab bottom Z = -1.5). */
    static final float SLAB = 1.5f;

    private BoardBridge() { }

    /** The drawn top of a built deck at {@code elevation} levels: its generated slab, landing and pier. */
    static float deckZ(float elevation) {
        return elevation * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
    }

    /** The drawn top of the tile's built deck, at its hex level plus its bridge's height. */
    static float deckZ(BoardScene.Tile tile) { return deckZ(tile.elevation() + feature(tile).elevation()); }

    /** The highest TOP facet of an installed deck shape at (x, y), or negative infinity where it has none there. */
    static float top(Shape shape, float x, float y) {
        float height = Float.NEGATIVE_INFINITY;
        if (shape == null) { return height; }
        for (var facet : shape.facets()) {
            if (facet.part() == Part.TOP) {
                height = Math.max(height, new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP)
                      .height(x, y));
            }
        }
        return height;
    }

    /**
     * The drawn deck at (x, y): its installed shape's top ({@link #top}, a sloped or fitted deck), else the plane of the
     * tile's deck when no installed surface is available ({@link #deckZ}). What lies on a deck stands on it, a rail too.
     */
    static float drawnTop(Shape shape, BoardScene.Tile tile, float x, float y) {
        float height = top(shape, x, y);
        return Float.isFinite(height) || feature(tile) == null ? height : deckZ(tile);
    }

    /**
     * {@code kind} and {@code roadExits}: the road the deck carries on its own sides, NONE and 0 on a clean deck;
     * {@code exits}: the bridge's. {@code natural}: a rock arch ({@link Family#NATURAL}). {@code neighbors}: per side, the
     * road kind that meets the deck there (a joined deck's road leaving back, or a road approach's), else NONE.
     * {@code piers}: the hex's Pillars toggle ({@link HexAppearance#PILLARS}), see {@link BoardBridgeFooting#pier}.
     * {@code colours}: the look span's truss colours ({@link BridgeSpan#truss}), null for the standard design or a natural
     * span. {@code joints}: the exits whose neighbour is a joined deck of its family ({@link #connected}); a truss
     * continues there and ends with an end post at every other exit. {@code rail} retains independently routed deck
     * tracks too, so a shared corridor is not required to keep a road junction clear of a roundabout island.
     * Any change rebuilds the hex.
     */
    record Deck(BoardRoad.Kind kind, int roadExits, int exits, boolean natural, List<BoardRoad.Kind> neighbors,
          BoardScene.Surface surface, List<Float> rises, boolean piers, BoardDecoration.Colours colours, int joints,
          RailRoute.Corridor corridor, List<Float> cornerRises, boolean rail) {
        Deck(BoardRoad.Kind kind, int roadExits, int exits, boolean natural, List<BoardRoad.Kind> neighbors,
              BoardScene.Surface surface, List<Float> rises, boolean piers, BoardDecoration.Colours colours, int joints,
              RailRoute.Corridor corridor, List<Float> cornerRises) {
            this(kind, roadExits, exits, natural, neighbors, surface, rises, piers, colours, joints, corridor,
                  cornerRises, corridor.sides() != 0);
        }
        Deck(BoardRoad.Kind kind, int roadExits, int exits, boolean natural, List<BoardRoad.Kind> neighbors,
              BoardScene.Surface surface, List<Float> rises, boolean piers, BoardDecoration.Colours colours, int joints,
              RailRoute.Corridor corridor) {
            this(kind, roadExits, exits, natural, neighbors, surface, rises, piers, colours, joints, corridor, List.of());
        }
        Deck(BoardRoad.Kind kind, int roadExits, int exits, boolean natural, List<BoardRoad.Kind> neighbors,
              BoardScene.Surface surface, List<Float> rises, boolean piers, BoardDecoration.Colours colours, int joints) {
            this(kind, roadExits, exits, natural, neighbors, surface, rises, piers, colours, joints, RailRoute.Corridor.NONE);
        }

        /** The deck road's course over its own sides: what its paint covers. */
        BoardRoad road(Coords coords) {
            BoardRoad road = BoardRoad.layout(coords, roadExits, kind, neighbors::get);
            return (rail ? road.solidHub() : road).corridor(corridor);
        }

        /** One physical carriageway over every bridge exit, spanning both ways of a road/rail corridor. */
        BoardRoad slab(Coords coords) {
            BoardRoad road = BoardRoad.layout(coords, exits, kind == BoardRoad.Kind.NONE ? BoardRoad.Kind.ALLEY : kind, neighbors::get);
            return (rail ? road.solidHub() : road).bridgeCorridor(corridor);
        }

        /** Whether the deck road paints the whole top: it has every bridge exit. */
        boolean paved() { return kind != BoardRoad.Kind.NONE && (roadExits & exits) == exits && corridor.beside() == 0; }
        boolean sloped() { return rises.stream().anyMatch(rise -> rise != 0) || cornerRises.stream().anyMatch(rise -> rise != 0); }
        boolean truss() { return colours != null; }
    }

    /**
     * The look families of decks ({@link HexAppearance#bridgeBuilt}, {@link HexAppearance#truss}): a look span (its
     * material, its truss colours) joins only decks of one family, and the 3D draws a seam where families meet. Untyped
     * and implicit decks are standard.
     */
    enum Family { NATURAL, TRUSS, STANDARD }

    static Family family(BoardScene.Tile tile) {
        return Boolean.FALSE.equals(HexAppearance.bridgeBuilt(tile.appearance())) ? Family.NATURAL
              : HexAppearance.truss(tile.appearance()) ? Family.TRUSS : Family.STANDARD;
    }

    /** The road of {@code tile}'s deck where it has side {@code side}, else NONE. */
    static BoardRoad.Kind deckRoad(BoardScene.Tile tile, int side) {
        return (tile.deckRoadExits() & (1 << side)) != 0 ? tile.deckRoad() : BoardRoad.Kind.NONE;
    }

    private record Material(BoardScene.Surface surface, BoardDecoration.Colours truss) { }

    /**
     * PIER: the concrete piers under the deck's joints ({@link BoardBridgeFooting#pier}), never a walkable level. STEEL:
     * a truss's steel members, drawn in their facets' {@code colour} (the span's recoloured Body).
     */
    enum Part { TOP, RIM, SIDE, SOFFIT, STRUCTURE, PIER, STEEL }

    /** {@code colour}: a STEEL facet's own colour; null for every other part, which its material colours. */
    record Facet(Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Part part, Color colour) {
        Facet(Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Part part) { this(a, b, c, normal, part, null); }
    }

    /** Immutable CPU geometry, owned by the installed terrain chunk and shared by drawing and picking. */
    record Shape(BoardScene.Surface surface, float level, List<Facet> facets, BoundingBox bounds) {
        float hit(Ray ray) { return hit(ray, true); }

        /** The squared distance to the nearest facet the ray meets; without {@code piers}, rays pass the piers. */
        float hit(Ray ray, boolean piers) {
            if (!Intersector.intersectRayBoundsFast(ray, bounds)) { return Float.POSITIVE_INFINITY; }
            float distance = Float.POSITIVE_INFINITY;
            var hit = new Vector3();
            for (var face : facets) {
                if ((piers || face.part() != Part.PIER)
                      && Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)) {
                    if (hit.equals(ray.origin)) { return 0; }
                    // A thin facet's float determinant can place an accepted hit off its plane.
                    var normal = face.normal();
                    double denominator = (double) normal.x * ray.direction.x
                          + (double) normal.y * ray.direction.y + (double) normal.z * ray.direction.z;
                    if (denominator != 0) {
                        double parameter = (normal.x * ((double) face.a().x - ray.origin.x)
                              + normal.y * ((double) face.a().y - ray.origin.y)
                              + normal.z * ((double) face.a().z - ray.origin.z)) / denominator;
                        if (parameter < 0) { continue; }
                        hit.set(ray.direction).scl((float) parameter).add(ray.origin);
                    }
                    distance = Math.min(distance, ray.origin.dst2(hit));
                }
            }
            return distance;
        }
    }

    static Shape shape(BoardScene.Surface surface, float level, List<Facet> facets) {
        var bounds = new BoundingBox().inf();
        facets.forEach(face -> bounds.ext(face.a()).ext(face.b()).ext(face.c()));
        return new Shape(surface, level, List.copyOf(facets), bounds);
    }

    static void triangle(List<Facet> faces, Vector3 a, Vector3 b, Vector3 c, Part part) {
        triangle(faces, a, b, c, part, null);
    }

    static void triangle(List<Facet> faces, Vector3 a, Vector3 b, Vector3 c, Part part, Color colour) {
        if (stable(a, b, c)) {
            faces.add(new Facet(a, b, c, new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor(), part, colour));
        }
    }

    /** The triangle has a stable plane: a needle a rounding step wide has none; its height and picking would be noise. */
    static boolean stable(Vector3 a, Vector3 b, Vector3 c) {
        float longest = Math.max(a.dst2(b), Math.max(b.dst2(c), c.dst2(a)));
        return new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > Math.max(1e-8f, 1e-8f * longest * longest);
    }

    static BoardScene.Feature feature(BoardScene.Tile tile) {
        return tile == null ? null : tile.features().stream().filter(f -> f.asset().equals("bridge")).findFirst().orElse(null);
    }

    /** An implicit bridge carrying only a maglev is the guideway beam itself, without a road slab or landings. */
    static boolean guideway(BoardScene.Tile tile) {
        return tile != null && feature(tile) != null && HexAppearance.implicit(tile.appearance())
              && tile.deckRoad() == BoardRoad.Kind.NONE && tile.features().stream().anyMatch(f ->
                    RailRoute.kind(f.decoration()) == RailRoute.Kind.MAGLEV && RailRoute.onDeck(f.decoration()));
    }

    static BoardScene.Feature guidewayRoute(BoardScene.Tile tile) {
        return tile.features().stream().filter(f -> RailRoute.kind(f.decoration()) == RailRoute.Kind.MAGLEV
              && RailRoute.onDeck(f.decoration())).findFirst().orElse(null);
    }

    static Deck deck(BoardScene scene, BoardScene.Tile tile) {
        var bridge = feature(tile);
        if (bridge == null) { return null; }
        var family = family(tile);
        var material = material(scene, tile, family);
        var neighbors = new ArrayList<BoardRoad.Kind>();
        var rises = new ArrayList<Float>();
        int joints = 0;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            boolean joined = connected(tile, next, d);
            if (joined && family(next) == family) { joints |= 1 << d; }
            neighbors.add(joined ? deckRoad(next, (d + 3) % 6) : road(tile, next, d) ? next.road() : BoardRoad.Kind.NONE);
            rises.add(edgeElevation(tile, next, d) - tile.elevation() - bridge.elevation());
        }
        return new Deck(tile.deckRoad(), tile.deckRoadExits(), bridge.bridgeExits(), family == Family.NATURAL,
              List.copyOf(neighbors), material.surface(), List.copyOf(rises), HexAppearance.pillars(tile.appearance()),
              material.truss(), joints, tile.deckCorridor(), cornerRises(scene, tile),
              tile.features().stream().anyMatch(feature -> RailRoute.isRoute(feature.decoration())
                    && RailRoute.onDeck(feature.decoration())));
    }

    /** Every deck sharing a corner derives its height from the same connected decks there, never from its own mean. */
    private static List<Float> cornerRises(BoardScene scene, BoardScene.Tile tile) {
        return cornerRises(scene, tile, false);
    }

    /** A Classic natural cap meets the bank itself; its graded approach stays inside the full hex footprint. */
    static List<Float> naturalCornerRises(BoardScene scene, BoardScene.Tile tile) {
        return cornerRises(scene, tile, true);
    }

    private static List<Float> cornerRises(BoardScene scene, BoardScene.Tile tile, boolean bankHeight) {
        float level = tile.elevation() + feature(tile).elevation();
        var result = new ArrayList<Float>();
        for (int corner = 0; corner < 6; corner++) {
            int first = BoardGeometry.edgeDirection(corner), second = BoardGeometry.edgeDirection((corner + 5) % 6);
            var neighbors = new BoardScene.Tile[] { tile, scene.tile(tile.coords().translated(first)),
                  scene.tile(tile.coords().translated(second)) };
            boolean[] joined = { true, false, false };
            for (int pass = 0; pass < 2; pass++) {
                for (int a = 0; a < neighbors.length; a++) {
                    if (!joined[a]) { continue; }
                    for (int b = 0; b < neighbors.length; b++) {
                        if (joined[b] || neighbors[b] == null) { continue; }
                        int d = neighbors[a].coords().direction(neighbors[b].coords());
                        if (cornerJoined(neighbors[a], neighbors[b], d)
                              || cornerJoined(neighbors[b], neighbors[a], (d + 3) % 6)) { joined[b] = true; }
                    }
                }
            }
            // Two of this deck's own approaches meet here and no other deck: the corner lies on both their edges, at
            // their heights, so the turn between them has no crease down towards the ground's lower mean.
            if (!bankHeight && joined[1] && joined[2] && feature(neighbors[1]) == null && feature(neighbors[2]) == null) {
                result.add((edgeElevation(tile, neighbors[1], first) + edgeElevation(tile, neighbors[2], second)) / 2 - level);
                continue;
            }
            float height = 0;
            int count = 0;
            float banks = 0;
            int bankCount = 0;
            for (int i = 0; i < neighbors.length; i++) {
                if (joined[i]) {
                    var bridge = feature(neighbors[i]);
                    height += bridge == null ? neighbors[i].floor() : neighbors[i].elevation() + bridge.elevation();
                    count++;
                    if (bridge == null) {
                        banks += neighbors[i].floor();
                        bankCount++;
                    }
                }
            }
            result.add((bankHeight && bankCount != 0 ? banks / bankCount : height / count) - level);
        }
        return List.copyOf(result);
    }

    private static boolean cornerJoined(BoardScene.Tile a, BoardScene.Tile b, int direction) {
        return feature(a) != null && (connected(a, b, direction) || road(a, b, direction) || bank(a, b, direction));
    }

    /**
     * The look span's materials: the decks joined to the tile's of its family. A truss span's design and colours follow
     * {@link BridgeSpan#truss}; a rock arch takes the commonest surface of the dry banks it rests on (a road meeting it is
     * no bank).
     */
    private static Material material(BoardScene scene, BoardScene.Tile tile, Family family) {
        var pending = new ArrayDeque<BoardScene.Tile>();
        var visited = new HashSet<Coords>();
        var banks = new HashSet<Coords>();
        var surfaces = BoardScene.Surface.values();
        int[] votes = new int[surfaces.length];
        pending.add(tile);
        visited.add(tile.coords());
        while (!pending.isEmpty()) {
            var current = pending.remove();
            for (int d = 0; d < 6; d++) {
                var next = scene.tile(current.coords().translated(d));
                if (connected(current, next, d) && family(next) == family) {
                    if (visited.add(next.coords())) { pending.add(next); }
                } else if (family == Family.NATURAL && !(next != null && next.roadExits() != 0 && road(current, next, d))
                      && abutment(current, next, d) && !next.liquid().present()
                      && next.surface() != BoardScene.Surface.CONCRETE && banks.add(next.coords())) {
                    votes[next.surface().ordinal()]++;
                }
            }
        }
        if (family != Family.NATURAL) {
            return new Material(BoardScene.Surface.ROCK, BridgeSpan.truss(at -> scene.tile(at).appearance(), visited));
        }
        var surface = tile.surface() == BoardScene.Surface.CONCRETE ? BoardScene.Surface.ROCK : tile.surface();
        int count = 0;
        for (var candidate : surfaces) {
            if (votes[candidate.ordinal()] > count) {
                surface = candidate;
                count = votes[candidate.ordinal()];
            }
        }
        return new Material(surface, null);
    }

    /** A carriageway (a road or a ground rail) on {@code next} meets the deck: it gets a half grade and a solid ramp. */
    static boolean road(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return next != null && next.wayExits() != 0
              && BoardSurface.connectingBridge(next, tile, (direction + 3) % 6) != null;
    }

    static boolean bank(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return abutment(tile, next, direction)
              && next.floor() <= tile.elevation() + feature(tile).elevation() + 1;
    }

    /** Solid ground that can anchor a span, including a cliff rising above its deck; under water, its bed. */
    static boolean abutment(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        var bridge = feature(tile);
        return bridge != null && next != null && BoardRoad.groundShown(next) && feature(next) == null
              && (bridge.bridgeExits() & (1 << direction)) != 0
              && next.floor() >= tile.elevation() + bridge.elevation() - 1;
    }

    /** A bridge's passage at deck height, through a bank or along the deck; what lies well below it is no obstruction. */
    record Approach(float x, float y, float nx, float ny, float length, float width, float level,
          boolean supportedLanding) {
        float lateral(Vector3 point) { return (point.y - y) * nx - (point.x - x) * ny; }

        /** A built level landing extends to the bank; its road must not pull receding terrain out under the deck. */
        boolean retreats(float px, float py, float dx, float dy, float margin) {
            if (!supportedLanding || dx * nx + dy * ny >= 0) { return false; }
            float along = (px - x) * nx + (py - y) * ny;
            return along >= 0 && along <= length && Math.abs((py - y) * nx - (px - x) * ny) <= width + margin;
        }

        boolean obstructs(Vector3 point, float radius, float height) {
            float along = (point.x - x) * nx + (point.y - y) * ny;
            return point.z + height >= level - BoardRelief.metres(.5f) && along > -radius && along < length + radius
                  && Math.abs(lateral(point)) < width + radius;
        }

        Vector3 beside(Vector3 point, float radius, int side) {
            float shift = side * (width + radius + BoardRelief.metres(.15f)) - lateral(point);
            return new Vector3(point).add(-ny * shift, nx * shift, 0);
        }
    }

    /** The passages through this hex: towards each bridge it is a bank of, and along its own bridge's deck. */
    static List<Approach> approaches(BoardScene scene, BoardScene.Tile tile) {
        var result = new ArrayList<Approach>();
        var center = BoardGeometry.center(tile.coords(), tile.elevation());
        var own = feature(tile);
        for (int d = 0; d < 6; d++) {
            boolean span = own != null && (own.bridgeExits() & (1 << d)) != 0;
            var next = scene.tile(tile.coords().translated(d));
            if (!span && !bank(next, tile, (d + 3) % 6)) { continue; }
            var direction = BoardGeometry.center(tile.coords().translated(d), tile.elevation()).sub(center);
            float length = direction.len();
            direction.scl(1 / length);
            // Both styles reserve the usable passage. A distant road edit must not change the bank's ground mesh.
            float width = passage(tile, d) * BoardGeometry.hexScale();
            float level = (span ? Math.min(tile.elevation() + own.elevation(),
                  edgeElevation(tile, scene.tile(tile.coords().translated(d)), d)) : BoardRoadGrade.ground(tile))
                  * BoardGeometry.level();
            boolean supportedLanding = !span && family(next) != Family.NATURAL && !guideway(next)
                  && road(next, tile, (d + 3) % 6)
                  && next.elevation() + feature(next).elevation() == BoardRoadGrade.ground(tile);
            result.add(new Approach(center.x, center.y, direction.x, direction.y, length, width, level, supportedLanding));
        }
        return List.copyOf(result);
    }

    /** Clear the whole corridor at a shared exit, leaving every ordinary bank and side arm unchanged. */
    static float passage(BoardScene.Tile tile, int direction) {
        if (guideway(tile)) { return 2; }
        return ((tile.corridor().sides() | tile.deckCorridor().sides()) & 1 << direction) != 0
              ? BoardRoad.CORRIDOR_HALF + SHOULDER : PASSAGE_HALF_WIDTH;
    }

    /** The decks join ({@link BridgeSpan#joined}): one span, one type, a joint the piers may stand under. */
    static boolean connected(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        var bridge = feature(tile);
        var other = feature(next);
        return bridge != null && other != null && BridgeSpan.joined(bridge.bridgeExits(), tile.elevation() + bridge.elevation(),
              other.bridgeExits(), next.elevation() + other.elevation(), direction);
    }

    /** Connected decks and approaches share the grade; built bridges also ramp down onto bare banks. */
    static float edgeElevation(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        float level = tile.elevation() + feature(tile).elevation();
        if (connected(tile, next, direction)) { return (level + next.elevation() + feature(next).elevation()) / 2; }
        boolean ramp = road(tile, next, direction) || family(tile) != Family.NATURAL && !guideway(tile)
              && bank(tile, next, direction) && BoardRoadGrade.ground(next) < level;
        return ramp ? (level + BoardRoadGrade.ground(next)) / 2 : level;
    }
}
