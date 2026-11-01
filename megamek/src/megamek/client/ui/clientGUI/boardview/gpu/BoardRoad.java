/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

import com.badlogic.gdx.math.Vector2;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;

/** Cosmetic road footprints in tile pixels, shared by the road mesh and roadside vegetation/rock clearance. */
final class BoardRoad {
    enum Kind {
        NONE(0), PAVED, ALLEY, DIRT, GRAVEL;

        final float halfWidth;
        // Material choice must not pinch a continuous carriageway.
        Kind() { this(HexTile.ROAD_HALF_WIDTH); }
        Kind(float halfWidth) { this.halfWidth = halfWidth; }
    }

    private record Point(float x, float y, float width) { }
    private record End(Point point, float nx, float ny) {
        float distance(float x, float y) { return (x - point.x) * nx + (y - point.y) * ny; }

        Area band(float from, float to) {
            return band(from, to, 128);
        }

        Area band(float from, float to, float width) {
            Path2D.Float shape = new Path2D.Float();
            shape.moveTo(point.x + from * nx - width * ny, point.y + from * ny + width * nx);
            shape.lineTo(point.x + to * nx - width * ny, point.y + to * ny + width * nx);
            shape.lineTo(point.x + to * nx + width * ny, point.y + to * ny - width * nx);
            shape.lineTo(point.x + from * nx + width * ny, point.y + from * ny - width * nx);
            shape.closePath();
            return new Area(shape);
        }
    }

    /** Both sides of a connected material change meet at half coverage on their shared edge. */
    record Join(int direction, Kind kind, End edge) {
        Area area() { return area(128); }
        /** {@link #area}, ending {@code beyond} past the shared edge. */
        Area area(float beyond) { return edge.band(-JOIN_REACH, beyond); }
        float coverage(float x, float y) {
            return Math.clamp((edge.distance(x, y) + JOIN_REACH) / (2 * JOIN_REACH), 0, 1);
        }
        float lateral(float x, float y) {
            return -(x - edge.point.x) * edge.ny + (y - edge.point.y) * edge.nx;
        }
    }

    static final float SHOULDER = 1.5f;
    static final float MEDIAN_HALF = RailRoute.BALLAST_HALF_WIDTH;
    static final float CORRIDOR_HALF = HexTile.ROAD_HALF_WIDTH + MEDIAN_HALF + RailRoute.CORRIDOR_GAP / 2 + .05f;
    static final float MIDDLE_HALF = HexTile.ROAD_HALF_WIDTH + MEDIAN_HALF + .3f;
    private static final float BESIDE_ROAD = RailRoute.BESIDE_ROAD;
    static final float WHEEL_OFFSET = 3;
    /** Overlapping passes wear a broad swath; this is not the width of a single tyre. */
    static final float TRACK_HALF_WIDTH = 1.4f;
    static final float JOIN_REACH = 16;
    static final float ROUNDABOUT_RADIUS = 18;
    /** The largest angle between a bent road and the normal of the border it crosses. */
    private static final float MAX_BEND = (float) Math.toRadians(40);
    /** Where an approach outside a broad 120-degree bend starts its turn, holding the ramp corridor. */
    static final float CORRIDOR = .55f;
    /** Centre-line dash and gap lengths; a road fits whole dashes between the borders it crosses. */
    private static final float DASH = 7, GAP = 6;
    private final Coords coords;
    private final List<List<Point>> paths;
    /** Where the road crosses each border it leaves by: its dashes are anchored there, identically on both sides. */
    private final List<Point> borders;
    private final List<End> ends;
    private final List<Join> joins;
    /** The shared circulation geometry; null for a crossing or a through road. */
    private record Circle(float x, float y, float radius, int lanes, Coords owner) { }
    private final Circle circle;
    private final Map<Float, Area> outlines = new HashMap<>();
    /** The course's sections, derived once and shared by every worker that grades it; see {@link #sections()}. */
    private volatile float[] sections;
    private RailRoute.Corridor corridor = RailRoute.Corridor.NONE;
    private Area median;
    /** Connected bends requiring a solid divider on this road's immediate approaches. */
    private int solidApproaches;
    /** Roadless concrete entrances do not count as road junction arms, including sloped apron mouths. */
    private int apronExits;
    /** Fitted roadless aprons and one lane plus verge of their entrance, in this road's local coordinates. */
    private Area concreteEntrances = new Area();
    private HexAppearance.TrafficControl control = HexAppearance.TrafficControl.STOPS;
    /** Sides where this road meets one of the other family at its level, paved against loose (#meet). */
    private int meetings;
    /** Of those, the sides where a paved road ends square on its border: a construction edge (#meet). */
    private int squared;
    /** Sides where a dirt road runs on into another's hex, whose own coat draws the track there (#meet). */
    private int tracked;
    /** Sides where a road joins a bridge's deck: their paint lies on separate supports (#meet). */
    private int apart;
    /** The paved roads a loose one meets, and the gores it fills between arms into one of them, as corner lists. */
    private List<BoardRoad> paved = List.of();
    private List<float[]> mouths = List.of();

    /** What a road's course needs of one hex, whether from a scene tile or a captured hex. */
    record Node(int exits, int elevation, boolean plain, boolean circulating) {
        static Node of(BoardScene.Tile tile) {
            if (tile == null) { return null; }
            boolean plain = rendered(tile) && tile.corridor().sides() == 0
                  && tile.features().stream().noneMatch(feature -> feature.asset().equals("bridge"));
            return new Node(tile.roadExits() & 63, tile.elevation(), plain,
                  plain && tile.road() == Kind.PAVED && tile.railExits() == 0);
        }

        static Node of(Hex hex) {
            if (hex == null) { return null; }
            int exits = hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0;
            boolean plain = capture(hex) != Kind.NONE && !HexTile.liquid(hex) && !hex.containsTerrain(Terrains.BRIDGE)
                  && !hex.containsTerrain(Terrains.ULTRA_SUBLEVEL);
            return new Node(exits, hex.getLevel(), plain,
                  plain && capture(hex) == Kind.PAVED && RailRoute.groundSides(hex) == 0);
        }

        static Node of(Hex hex, Coords at, Function<Coords, Hex> board) {
            Node node = of(hex);
            if (node == null) { return null; }
            boolean clear = RailRoute.corridor(hex, at, board).sides() == 0;
            return new Node(node.exits(), node.elevation(), node.plain() && clear, node.circulating() && clear);
        }

        /** A plain two-way road, whose course may bend through its borders. */
        boolean simple() { return plain && Integer.bitCount(exits) == 2; }

        /** Opposite exits already describe a straight course through this hex. */
        boolean straight() { return simple() && (exits & (exits >> 3)) != 0; }

        /** Adjacent exits need the whole hex for their tight return turn. */
        boolean hairpin() { return simple() && (exits & ((exits << 1) | (exits >> 5))) != 0; }
    }

    private BoardRoad(Coords coords, List<List<Point>> paths, List<Point> borders, List<End> ends, List<Join> joins,
          Circle circle) {
        this.coords = coords;
        this.paths = paths;
        this.borders = borders;
        this.ends = ends;
        this.joins = joins;
        this.circle = circle;
    }

    static Kind capture(Hex hex) {
        // Which roads are drawn is common (HexTile.road), the legacy conversion's statement too.
        return HexTile.road(hex) ? kind(hex.terrainLevel(Terrains.ROAD)) : Kind.NONE;
    }

    /** The road its bridge deck carries ({@link Terrains#BRIDGE_ROAD}), NONE on a clean deck or without a bridge. */
    static Kind deck(Hex hex) {
        return hex.containsTerrain(Terrains.BRIDGE_ROAD) ? kind(hex.terrainLevel(Terrains.BRIDGE_ROAD)) : Kind.NONE;
    }

    /** Matches Hex.hasPavedRoad, retaining the alley's distinct appearance within paved roads. */
    private static Kind kind(int level) {
        return switch (level) {
            case 2 -> Kind.ALLEY;
            case Terrains.ROAD_LVL_DIRT -> Kind.DIRT;
            case Terrains.ROAD_LVL_GRAVEL -> Kind.GRAVEL;
            default -> Kind.PAVED;
        };
    }

    /** Unsupported road decoration and coexisting terrain retain their original artwork. */
    static boolean rendered(BoardScene.Tile tile) {
        return tile.road() != Kind.NONE && !tile.liquid().present();
    }

    /**
     * A road through shallow water, a ford or a flooded street, which Enhanced lays on the bed ({@link #bed}). Under a
     * level or more of water a road is out of sight, as in real water; a bridge carries its road over the water
     * instead, and molten liquid hides its bed.
     */
    static boolean submerged(BoardScene.Tile tile) {
        return tile.road() != Kind.NONE && tile.liquid().present() && !tile.liquid().molten() && tile.waterDepth() == 0
              && BoardBridge.feature(tile) == null;
    }

    /**
     * A carriageway the ground is shaped and cleared for ({@link BoardScene.Tile#wayExits}): a rendered road, or rails on
     * the tile's detailed, dry ground.
     */
    static boolean carriageway(BoardScene.Tile tile) {
        return rendered(tile) || tile.railExits() != 0 && !tile.liquid().present();
    }

    /**
     * The course of the tile's carriageway, which its shape and clearance follow: its road's ({@link #of}); where rails
     * lie on its ground too, the widest course a road with all those exits takes ({@link #clearance(Coords, int)}).
     */
    static BoardRoad way(BoardScene scene, BoardScene.Tile tile) {
        return tile.railExits() == 0 ? of(scene, tile) : clearance(tile.coords(), tile.wayExits(), tile.corridor()).solidHub();
    }

    /** Roads painting this dry, level ground, including their shoulders across neighbouring hex edges. */
    static List<BoardRoad> nearby(BoardScene scene, BoardScene.Tile tile) {
        return nearby(scene, tile, false, false);
    }

    /** The same neighbouring courses for placement clearance, including ground rails without a road. */
    static List<BoardRoad> nearbyWays(BoardScene scene, BoardScene.Tile tile) {
        return nearby(scene, tile, true, false);
    }

    /** {@link #nearbyWays} without rails alone: ground cover grows up to their ballast (BoardObstacles). */
    static List<BoardRoad> nearbyRoads(BoardScene scene, BoardScene.Tile tile) {
        return nearbyWays(scene, tile).stream().filter(way -> rendered(scene.tile(way.owner()))).toList();
    }

    /** Roads painting the bed of a {@link #submerged} road's hex: its own and those meeting it at its level. */
    static List<BoardRoad> bed(BoardScene scene, BoardScene.Tile tile) {
        return submerged(tile) ? nearby(scene, tile, false, true) : List.of();
    }

    private static List<BoardRoad> nearby(BoardScene scene, BoardScene.Tile tile, boolean clearance, boolean bed) {
        if (tile == null || tile.liquid().present() != bed) { return List.of(); }
        List<BoardRoad> result = new ArrayList<>();
        for (int direction = -1; direction < 6; direction++) {
            var source = direction < 0 ? tile : scene.neighbor(tile, direction);
            // A graded road's ramp and corridor reach a neighbour of another level too; drawn roads keep to their level.
            if (source == null || !clearance && source.elevation() != tile.elevation()
                  || !(clearance ? carriageway(source) : rendered(source) || bed && submerged(source))) { continue; }
            // Only an actual apron entrance ends at concrete. Through-roads and roundabouts keep their full width
            // where their sides cross a fitted concrete edge, including concrete carrying another road.
            if (!clearance && direction >= 0 && tile.surface() == BoardScene.Surface.CONCRETE
                  && tile.road() == Kind.NONE && (source.roadExits() & 1 << ((direction + 3) % 6)) != 0) { continue; }
            BoardRoad road = clearance ? way(scene, source) : of(scene, source);
            if (result.stream().noneMatch(existing -> existing.owner().equals(road.owner()))) { result.add(road); }
        }
        return List.copyOf(result);
    }

    /** Distance in tile pixels from a world-space point to the nearest course, respecting each course's origin. */
    static float distance(List<BoardRoad> roads, float x, float y) {
        float result = Float.POSITIVE_INFINITY, scale = BoardGeometry.hexScale();
        for (BoardRoad road : roads) {
            result = Math.min(result, road.distance((x - BoardGeometry.centerX(road.coords)) / scale,
                  (y - BoardGeometry.centerY(road.coords)) / scale));
        }
        return result;
    }

    static BoardRoad of(BoardScene scene, BoardScene.Tile tile) {
        Function<Coords, Node> nodes = at -> Node.of(scene.tile(at));
        var road = layout(tile.coords(), tile.roadExits(), tile.road(), direction -> {
            var neighbor = scene.tile(tile.coords().translated(direction));
            // A road meeting a deck joins the deck's road where it has that side; a clean side keeps the road's own kind.
            if (neighbor != null && BoardSurface.connectingBridge(tile, neighbor, direction) != null) {
                return BoardBridge.deckRoad(neighbor, (direction + 3) % 6);
            }
            return neighbor != null && (neighbor.roadExits() & (1 << ((direction + 3) % 6))) != 0
                  ? neighbor.road() : Kind.NONE;
        }, bends(tile.coords(), nodes), true,
              climbingTurn(tile.roadExits(), BoardRoadGrade.ramps(scene, tile))).circulate(nodes);
        if (tile.railExits() != 0) { road = road.solidHub(); }
        if (road.circle != null && road.circle.lanes == 2) {
            // Every member owns the complete shared geometry, including all three fitted external mouths.
            // Apply the extensions in the same canonical order so neighboring paint can safely deduplicate it.
            for (Coords member : triangles(tile.coords(), nodes).getFirst()) {
                road = road.meetConcrete(scene, scene.tile(member));
            }
        } else { road = road.meetConcrete(scene, tile); }
        return road.corridor(tile.corridor()).approachMarkings(scene, tile, false)
              .control(HexAppearance.trafficControl(tile.appearance(), "road", tile.roadExits())).meet(scene, tile);
    }

    private static boolean paved(Kind kind) { return kind == Kind.PAVED || kind == Kind.ALLEY; }

    /**
     * Where a loose road (dirt or gravel) meets a paved one at its level, the paved road keeps its carriageway whole up to
     * their border, where it ends square (a construction edge), and the loose one stops at its edge (GpuRoads#patches).
     * Two arms of a loose road through adjacent borders into one paved road meet it in one mouth: the gore between them
     * fills out to that road.
     */
    private BoardRoad meet(BoardScene scene, BoardScene.Tile tile) {
        boolean loose = !paved(tile.road());
        List<BoardRoad> roads = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            var next = scene.neighbor(tile, d);
            if ((tile.roadExits() & 1 << d) == 0 || next == null) { continue; }
            if (BoardSurface.connectingBridge(tile, next, d) != null
                  || BoardSurface.connectingBridge(next, tile, (d + 3) % 6) != null) {
                apart |= 1 << d;
                continue;
            }
            if (next.elevation() != tile.elevation() || !rendered(next) || (next.roadExits() & 1 << (d + 3) % 6) == 0) {
                continue;
            }
            if (tile.road() == Kind.DIRT && next.road() == Kind.DIRT) { tracked |= 1 << d; }
            if (paved(next.road()) == loose) {
                meetings |= 1 << d;
                if (loose) { roads.add(of(scene, next)); }
            }
        }
        List<float[]> gores = new ArrayList<>();
        for (Join a : joins) {
            for (Join b : joins) {
                int d = a.direction();
                if (!loose || b.direction() != (d + 1) % 6 || !meets(a) || !meets(b)
                      || (scene.neighbor(tile, d).roadExits() & 1 << (d + 2) % 6) == 0) { continue; }
                // The whole sector between the arms, from the hex's centre out past each arm's reach beyond its border
                // (#approach), onto the paved road's side.
                Point p = a.edge().point(), q = b.edge().point();
                float reach = .06f * (float) Math.hypot(p.x, p.y) + p.width + 2 * SHOULDER;
                gores.add(new float[] { 0, 0, p.x + a.edge().nx() * reach, p.y + a.edge().ny() * reach,
                      q.x + b.edge().nx() * reach, q.y + b.edge().ny() * reach });
            }
        }
        paved = List.copyOf(roads);
        mouths = List.copyOf(gores);
        squared = loose ? 0 : meetings;
        outlines.clear();
        return this;
    }

    /** Whether this road meets a road of the other family through the join's side (#meet). */
    boolean meets(Join join) { return (meetings & 1 << join.direction()) != 0; }

    /** Whether the join's other road lies on a bridge's deck, or this one does on its ground's side (#meet). */
    boolean apart(Join join) { return (apart & 1 << join.direction()) != 0; }

    /**
     * The hexes, in this road's tile pixels, of the dirt roads it runs on into (#meet). Each one's own coat draws the
     * track over its hex, measured from its own course; this one's paint stops at their border, so the two never
     * overlap with two measures of where across the road a point lies.
     */
    Area trackedHexes() {
        Area result = new Area();
        for (int d = 0; d < 6; d++) {
            if ((tracked & 1 << d) == 0) { continue; }
            Coords next = coords.translated(d);
            float dx = (BoardGeometry.centerX(next) - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale();
            float dy = (BoardGeometry.centerY(next) - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale();
            Path2D.Float hex = new Path2D.Float();
            for (int k = 0; k < 6; k++) {
                float x = dx + HexTile.cornerOffsetX(k, BoardGeometry.TILE_WIDTH);
                float y = dy + HexTile.cornerOffsetY(k, BoardGeometry.TILE_HEIGHT);
                if (k == 0) { hex.moveTo(x, y); } else { hex.lineTo(x, y); }
            }
            hex.closePath();
            result.add(new Area(hex));
        }
        return result;
    }

    /**
     * The carriageways of the paved roads a loose one meets (#meet), widened by {@code margin}, in its tile pixels. A
     * narrower one stops short of their square ends too, as a spill's reach does.
     */
    Area pavedCarriageway(float margin) {
        Area result = new Area();
        for (BoardRoad road : paved) {
            float dx = (BoardGeometry.centerX(road.coords) - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale();
            float dy = (BoardGeometry.centerY(road.coords) - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale();
            Area carriageway = road.footprint(margin);
            for (Join join : road.joins) {
                if (margin < 0 && (road.squared & 1 << join.direction()) != 0) {
                    carriageway.subtract(road.square(join, margin));
                }
            }
            result.add(carriageway.createTransformedArea(AffineTransform.getTranslateInstance(dx, dy)));
        }
        return result;
    }

    /** How far a point lies inside the carriageways of the paved roads a loose one meets; negative outside them. */
    float pavedDepth(float x, float y) {
        return -distance(paved, BoardGeometry.centerX(coords) + x * BoardGeometry.hexScale(),
              BoardGeometry.centerY(coords) + y * BoardGeometry.hexScale());
    }

    /**
     * What a square end removes of its arm's outline widened by {@code margin}: the arm past its border (from
     * {@code from} on), and nothing beside it, such as a shared roundabout's other members (#meet).
     */
    private Area square(Join join, float from) {
        return join.edge().band(from, 128, join.edge().point().width + SHOULDER + 3);
    }

    /**
     * How far a point lies past this road's square ends (negative short of them), within their arms' width; negative
     * infinity beside them.
     */
    private float squareEdge(float x, float y) {
        float result = Float.NEGATIVE_INFINITY;
        for (Join join : joins) {
            if ((squared & 1 << join.direction()) == 0) { continue; }
            var edge = join.edge();
            if (Math.abs(join.lateral(x, y)) < edge.point().width + SHOULDER + 3) {
                result = Math.max(result, edge.distance(x, y));
            }
        }
        return result;
    }

    /** Signed distance in tile pixels to the nearest mouth (#meet), negative inside one. */
    private float mouthEdge(float x, float y) {
        float result = Float.POSITIVE_INFINITY;
        for (float[] mouth : mouths) {
            float near = Float.POSITIVE_INFINITY, turn = 0;
            boolean inside = true;
            for (int i = 0; i < mouth.length; i += 2) {
                float ax = mouth[i], ay = mouth[i + 1];
                float bx = mouth[(i + 2) % mouth.length], by = mouth[(i + 3) % mouth.length];
                near = Math.min(near, BoardRelief.distance(x, y, ax, ay, bx, by));
                float side = Math.signum((bx - ax) * (y - ay) - (by - ay) * (x - ax));
                inside &= side == 0 || turn == 0 || side == turn;
                if (side != 0) { turn = side; }
            }
            result = Math.min(result, inside ? -near : near);
        }
        return result;
    }

    BoardRoad control(HexAppearance.TrafficControl value) { control = value; return this; }

    BoardRoad aprons(int exits) { apronExits = exits & 63; return this; }

    /** Snapshot the connected ground/deck course for paint; a bend is preceded by one road-width of solid divider. */
    BoardRoad approachMarkings(BoardScene scene, BoardScene.Tile tile, boolean deck) {
        solidApproaches = 0;
        int exits = deck ? tile.deckRoadExits() : tile.roadExits();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & 1 << direction) == 0) { continue; }
            var next = scene.neighbor(tile, direction);
            if (next == null) { continue; }
            boolean nextDeck = deck ? BoardBridge.connected(tile, next, direction)
                  : BoardSurface.connectingBridge(tile, next, direction) != null;
            if (!nextDeck && !(deck ? BoardBridge.road(tile, next, direction)
                  : BoardSurface.hasRoadApproach(tile, next, direction))) { continue; }
            int nextExits = nextDeck ? next.deckRoadExits() : next.roadExits();
            Kind nextKind = nextDeck ? next.deckRoad() : next.road();
            if (nextKind == Kind.PAVED && turning(nextExits) && (nextExits & 1 << ((direction + 3) % 6)) != 0) {
                solidApproaches |= 1 << direction;
            }
        }
        return this;
    }

    static boolean turning(int exits) {
        return Integer.bitCount(exits & 63) == 2 && (exits & (exits >> 3)) == 0;
    }

    /** Carry the whole approach to a fitted concrete edge; the supporting ground clips its diagonal join. */
    private BoardRoad meetConcrete(BoardScene scene, BoardScene.Tile tile) {
        List<Float> lengths = null;
        Area unmarked = new Area(concreteEntrances);
        for (int direction = 0; direction < 6; direction++) {
            if ((tile.roadExits() & 1 << direction) == 0) { continue; }
            var neighbor = scene.tile(tile.coords().translated(direction));
            if (neighbor == null || neighbor.surface() != BoardScene.Surface.CONCRETE || neighbor.liquid().present()
                  || BoardSurface.connectingBridge(tile, neighbor, direction) != null) {
                continue;
            }
            var concrete = BoardConcrete.of(scene);
            if (neighbor.road() == Kind.NONE) {
                apronExits |= 1 << direction;
                Path2D.Float apron = new Path2D.Float();
                for (int k = 0; k < 6; k++) {
                    var point = concrete.corner(neighbor.coords(), k);
                    float x = (point.x - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale();
                    float y = (point.y - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale();
                    if (k == 0) { apron.moveTo(x, y); }
                    else { apron.lineTo(x, y); }
                }
                apron.closePath();
                unmarked.add(new Area(apron));
                unmarked.add(new Area(new BasicStroke(2 * (Kind.PAVED.halfWidth + SHOULDER), BasicStroke.CAP_ROUND,
                      BasicStroke.JOIN_ROUND).createStrokedShape(apron)));
            }
            if (neighbor.elevation() != tile.elevation()) { continue; }
            var shifts = concrete.corners(tile.coords());
            Vector2 axis = border(direction);
            float distance = axis.len(), reach = .06f * distance;
            axis.nor();
            int edge = Math.floorMod(1 - direction, 6);
            for (int k : new int[] { edge, (edge + 1) % 6 }) {
                var shift = shifts.get(k);
                var corner = BoardGeometry.corner(tile.coords(), 0, k);
                float x = (corner.x + shift.x() - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale();
                float y = (corner.y + shift.y() - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale();
                reach = Math.max(reach, x * axis.x + y * axis.y - distance);
            }
            if (reach > .06f * distance) {
                if (lengths == null) { lengths = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f)); }
                lengths.set(direction, reach);
            }
        }
        var result = lengths == null ? this : extended(lengths, 0, tile.coords());
        result.concreteEntrances = unmarked;
        return result;
    }

    /** All paint shares the same fitted entrance clearance; callers may subtract this copy. */
    Area concreteClearance() { return new Area(concreteEntrances); }

    /** Widen or move only the shared corridor arms, blending along their existing road courses. */
    BoardRoad corridor(RailRoute.Corridor value) { return corridor(value, MIDDLE_HALF); }

    /** One centered carrier supports both lanes and the track, whichever rail placement the author chooses. */
    BoardRoad bridgeCorridor(RailRoute.Corridor value) {
        return corridor(new RailRoute.Corridor(value.sides(), 0, 0), CORRIDOR_HALF);
    }

    private record Anchor(float along, float width, float x, float y) { }

    private BoardRoad corridor(RailRoute.Corridor value, float middleWidth) {
        if (value.sides() == 0) { return this; }
        if (circle != null && circle.lanes == 1) {
            return solidHub().corridor(value, middleWidth);
        }
        if (value.beside() == 0 && borders.stream().allMatch(p -> {
            int d = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(p.x, p.y)) / 60), 6);
            return (value.sides() & 1 << d) != 0;
        })) {
            // The common through corridor needs only a wider existing envelope, with no extra course samples.
            BoardRoad result = new BoardRoad(coords,
                  paths.stream().map(path -> path.stream().map(p -> new Point(p.x, p.y, middleWidth)).toList()).toList(),
                  borders.stream().map(p -> new Point(p.x, p.y, middleWidth)).toList(), ends, joins, circle);
            result.corridor = value;
            result.solidApproaches = solidApproaches;
            result.apronExits = apronExits;
            result.concreteEntrances = concreteEntrances;
            result.control = control;
            return result;
        }
        float hubWidth = 0, hubX = 0, hubY = 0;
        int count = 0;
        for (int d = 0; d < 6; d++) {
            if ((value.sides() & 1 << d) == 0) { continue; }
            Vector2 edge = border(d).nor();
            float offset = BESIDE_ROAD * value.lateral(d);
            hubWidth += (value.beside() & 1 << d) == 0 ? middleWidth : Kind.PAVED.halfWidth;
            hubX += edge.y * offset; hubY -= edge.x * offset; count++;
        }
        hubWidth /= count; hubX /= count; hubY /= count;
        List<List<Point>> changed = new ArrayList<>();
        for (var path : paths) {
            List<Point> dense = new ArrayList<>(List.of(path.getFirst()));
            for (int i = 1; i < path.size(); i++) {
                Point a = path.get(i - 1), b = path.get(i);
                int steps = Math.max(1, (int) Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 2));
                for (int k = 1; k <= steps; k++) {
                    float t = (float) k / steps;
                    dense.add(new Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.width + t * (b.width - a.width)));
                }
            }
            float[] distances = new float[dense.size()];
            int hub = 0;
            for (int i = 1; i < dense.size(); i++) {
                Point a = dense.get(i - 1), p = dense.get(i);
                distances[i] = distances[i - 1] + (float) Math.hypot(p.x - a.x, p.y - a.y);
                if (p.x * p.x + p.y * p.y < dense.get(hub).x * dense.get(hub).x + dense.get(hub).y * dense.get(hub).y) { hub = i; }
            }
            var anchors = new java.util.TreeMap<Float, Anchor>();
            anchors.put(0f, new Anchor(0, dense.getFirst().width, 0, 0));
            float length = distances[distances.length - 1];
            anchors.put(length, new Anchor(length, dense.getLast().width, 0, 0));
            if (borders.size() == 1) {
                // A shared dead end retains its lanes and lateral offset past the centre to the closed end.
                anchors.put(0f, new Anchor(0, hubWidth, hubX, hubY));
                anchors.put(length, new Anchor(length, hubWidth, hubX, hubY));
            }
            anchors.put(distances[hub], new Anchor(distances[hub], hubWidth, hubX, hubY));
            for (int i = 0; i < dense.size(); i++) {
                Point p = dense.get(i);
                for (int d = 0; d < 6; d++) {
                    Vector2 edge = border(d);
                    float projection = (p.x * edge.x + p.y * edge.y) / edge.len2();
                    if (projection < .999f || Math.abs(p.x * edge.y - p.y * edge.x) / edge.len() > .05f) { continue; }
                    boolean shared = (value.sides() & 1 << d) != 0;
                    float width = shared && (value.beside() & 1 << d) == 0 ? middleWidth : p.width;
                    float offset = BESIDE_ROAD * value.lateral(d);
                    float[] shift = RailRoute.borderOffset(d, offset);
                    anchors.put(distances[i], new Anchor(distances[i], width, shift[0], shift[1]));
                }
            }
            List<Point> moved = new ArrayList<>();
            for (int i = 0; i < dense.size(); i++) {
                Point p = dense.get(i);
                Anchor a = anchors.floorEntry(distances[i]).getValue(), b = anchors.ceilingEntry(distances[i]).getValue();
                float t = a.along == b.along ? 0 : (distances[i] - a.along) / (b.along - a.along);
                t = t * t * (3 - 2 * t);
                moved.add(new Point(p.x + a.x + t * (b.x - a.x), p.y + a.y + t * (b.y - a.y),
                      a.width + t * (b.width - a.width)));
            }
            changed.add(List.copyOf(moved));
        }
        List<Point> movedBorders = new ArrayList<>();
        for (Point p : borders) {
            int d = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(p.x, p.y)) / 60), 6);
            float[] shift = RailRoute.borderOffset(d, BESIDE_ROAD * value.lateral(d));
            movedBorders.add(new Point(p.x + shift[0], p.y + shift[1],
                  (value.middle() & 1 << d) != 0 ? middleWidth : p.width));
        }
        BoardRoad result = new BoardRoad(coords, List.copyOf(changed), List.copyOf(movedBorders), ends, joins, circle);
        result.corridor = value;
        result.solidApproaches = solidApproaches;
        result.apronExits = apronExits;
        result.concreteEntrances = concreteEntrances;
        result.control = control;
        return result;
    }

    /** Rails retain their authored routes through a solid hub rather than distorting a circulating road lane. */
    BoardRoad solidHub() {
        if (circle == null || circle.lanes != 1) { return this; }
        int exits = 0;
        for (Point point : borders) {
            exits |= 1 << Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(point.x, point.y)) / 60), 6);
        }
        BoardRoad hub = layout(coords, exits, Kind.PAVED,
              direction -> joins.stream().filter(join -> join.direction() == direction).map(Join::kind)
                    .findFirst().orElse(Kind.PAVED), direction -> null, false, false);
        hub.solidApproaches = solidApproaches;
        hub.apronExits = apronExits;
        hub.concreteEntrances = concreteEntrances;
        hub.control = control;
        List<Float> reaches = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) { reaches.add(borderReach(direction)); }
        return hub.extended(reaches);
    }

    /** The central ballast reserve, opened where a non-corridor side road crosses the track. */
    Area medianBand() {
        if (median == null) {
            median = medianAxis(MEDIAN_HALF);
            median.subtract(sideArms());
        }
        return new Area(median);
    }

    private Area medianAxis(float halfWidth) {
        Area result = new Area();
        int tracks = corridor.tracks() == 0 ? RailRoute.pieces(corridor.middle()) : corridor.tracks();
        for (int piece = 0; piece < RailRoute.PIECES; piece++) {
            if ((RailRoute.pieceSides(piece) & corridor.middle()) != RailRoute.pieceSides(piece)) { tracks &= ~(1 << piece); }
        }
        for (var line : RailRoute.lines(corridor.middle(), tracks, new RailRoute.Corridor(corridor.middle(), 0, 0))) {
            if (corridor.middle() == 0) { break; }
            Path2D.Float path = new Path2D.Float();
            float[] first = line.at(0), after = line.at(.01f);
            if (line.from() >= 0) {
                float length = (float) Math.hypot(after[0] - first[0], after[1] - first[1]);
                float reach = borderReach(line.from());
                first[0] -= reach * (after[0] - first[0]) / length; first[1] -= reach * (after[1] - first[1]) / length;
            }
            path.moveTo(first[0], first[1]);
            for (int i = 1; i <= 24; i++) { float[] p = line.at(i / 24f); path.lineTo(p[0], p[1]); }
            if (line.to() >= 0) {
                float[] end = line.at(1), before = line.at(.99f);
                float length = (float) Math.hypot(end[0] - before[0], end[1] - before[1]);
                float reach = borderReach(line.to());
                path.lineTo(end[0] + reach * (end[0] - before[0]) / length, end[1] + reach * (end[1] - before[1]) / length);
            }
            result.add(new Area(new BasicStroke(halfWidth * 2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                  .createStrokedShape(path)));
        }
        return result;
    }

    /** The median continues over the same bank extension as its road, including the yellow edge lines. */
    private float borderReach(int direction) {
        Vector2 edge = border(direction);
        float length = edge.len(), nx = edge.x / length, ny = edge.y / length, reach = 2;
        for (var path : paths) {
            for (Point end : List.of(path.getFirst(), path.getLast())) {
                if (Math.abs(end.x * ny - end.y * nx) < .01f) {
                    reach = Math.max(reach, end.x * nx + end.y * ny - length);
                }
            }
        }
        return reach;
    }

    private Area sideArms() {
        Area result = new Area();
        for (Point p : borders) {
            int d = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(p.x, p.y)) / 60), 6);
            if ((corridor.sides() & 1 << d) != 0) { continue; }
            Path2D.Float path = new Path2D.Float();
            path.moveTo(0, 0); path.lineTo(p.x * 1.1f, p.y * 1.1f);
            result.add(new Area(new BasicStroke(p.width * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)));
        }
        return result;
    }

    /** A rail is set into the road only outside its central ballast reserve. */
    boolean street(float x, float y) {
        if (distance(x, y) >= 0) { return false; }
        if (median == null) { medianBand(); }
        return !median.contains(x, y);
    }

    /** Solid yellow median lines stop at side-road crossings. */
    Area medianLines() {
        Area lines = medianAxis(MEDIAN_HALF + .7f);
        lines.subtract(medianAxis(MEDIAN_HALF + .1f));
        lines.subtract(sideArms());
        lines.intersect(footprint(0));
        return lines;
    }

    /** Capture has no scene yet. The widest supported verge conservatively clears mixed-width joins. */
    static BoardRoad clearance(Coords coords, int exits) {
        return layout(coords, exits, Kind.PAVED, direction -> Kind.PAVED, direction -> null);
    }

    /** Reserve both the road and the beside track, but keep non-corridor side arms at their usual width. */
    static BoardRoad clearance(Coords coords, int exits, RailRoute.Corridor corridor) {
        return clearance(coords, exits).corridor(new RailRoute.Corridor(corridor.sides(), 0, 0), CORRIDOR_HALF);
    }

    /**
     * As {@link #clearance(Coords, int)}, following the course the scene will give the road through its borders; where
     * {@code rails} lie on the hex's ground too, the course of all its carriageways ({@link #way}).
     */
    static BoardRoad clearance(Coords coords, Hex hex, Function<Coords, Hex> board) {
        return clearance(coords, hex, board, megamek.common.board.RailRoute.groundSides(hex));
    }

    /** As {@link #clearance(Coords, Hex, Function)}, with the sides of the rails on the hex's ground given. */
    static BoardRoad clearance(Coords coords, Hex hex, Function<Coords, Hex> board, int rails) {
        int exits = hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0;
        if (rails != 0) { return clearance(coords, exits | rails, RailRoute.corridor(hex, coords, board)).solidHub(); }
        Function<Coords, Node> nodes = at -> Node.of(at.equals(coords) ? hex : board.apply(at), at, board);
        // The ramps BoardRoadGrade#ramps finds in the scene, up or down: an exit onto dry ground at another level that
        // carries the way on or lies within two levels. A bridge deck's approach height is not known here.
        int ramps = 0;
        for (int d = 0; d < 6; d++) {
            Hex next = board.apply(coords.translated(d));
            if (next == null || HexTile.liquid(next) || next.getLevel() == hex.getLevel()) { continue; }
            int back = 1 << (d + 3) % 6;
            boolean way = next.containsTerrain(Terrains.ROAD) && (next.getTerrain(Terrains.ROAD).getExits() & back) != 0
                  || (RailRoute.groundSides(next) & back) != 0;
            if (way || Math.abs(next.getLevel() - hex.getLevel()) <= 2) { ramps |= 1 << d; }
        }
        return layout(coords, exits, Kind.PAVED, direction -> Kind.PAVED, bends(coords, nodes), true,
              climbingTurn(exits, ramps)).circulate(nodes);
    }

    /** A return turn that climbs runs straight in to the hex centre and out again, keeping both ramps long. */
    static boolean climbingTurn(int exits, int ramps) {
        exits &= 63;
        return Integer.bitCount(exits) == 2 && (exits & ((exits << 1) | (exits >> 5))) != 0 && (ramps & exits) != 0;
    }

    /**
     * Where a road runs on at one level through plain two-way hexes, it crosses their shared border as a real road
     * does, instead of turning at every hex centre. Between two such crossings it follows the line from the border
     * before to the border after. Other crossings (junctions, ends, bridges, climbs) stay square to their border.
     * Outside a broad 120-degree bend they hold a straight corridor; next to one, the hex's whole course into it is
     * one circular arc, rather than a late sharp turn at its mouth. Both hexes derive the same crossing; the result is
     * its direction, pointing out of this hex, or null for a square crossing. Opposite-exit roads keep their straight
     * axis; their turning neighbours approach that same axis without pulling the straight road sideways. Adjacent
     * exits likewise keep square shared tangents, leaving the full hex to round their return turn.
     */
    static IntFunction<Vector2> bends(Coords coords, Function<Coords, Node> nodes) {
        return direction -> {
            // A straight road through a bridge's hex crosses as a plain straight road does: square to its border.
            if (!bent(coords, direction, nodes)) {
                return straightThrough(coords, direction, nodes) ? border(direction).nor() : null;
            }
            Coords after = coords.translated(direction);
            int mine = other(nodes.apply(coords), direction), theirs = other(nodes.apply(after), (direction + 3) % 6);
            Vector2 normal = border(direction);
            // A neighbouring turn must not pull a straight road sideways or squeeze a hairpin against its border.
            // Both sides use this tangent; non-null lets a level turning hex use its full width for a broad curve.
            Node here = nodes.apply(coords), next = nodes.apply(after);
            if (here.straight() || next.straight() || here.hairpin() || next.hairpin()) { return normal.nor(); }
            Vector2 offset = new Vector2(BoardGeometry.centerX(after) - BoardGeometry.centerX(coords),
                  BoardGeometry.centerY(after) - BoardGeometry.centerY(coords)).scl(1 / BoardGeometry.hexScale());
            Vector2 before = border(mine), beyond = border(theirs);
            boolean held = !bent(coords, mine, nodes), holds = !bent(after, theirs, nodes);
            Vector2 tangent = held || holds ? new Vector2() : new Vector2(beyond).add(offset).sub(before);
            if (held) { tangent.sub(arc(Vector2.Zero, before, normal)); }
            if (holds) { tangent.add(arc(offset, beyond, normal)); }
            normal.nor();
            if (tangent.len2() < 1e-8f) { return normal; }
            tangent.nor();
            float along = tangent.dot(normal);
            if (along >= (float) Math.cos(MAX_BEND)) { return tangent; }
            // A sharp turn keeps its crossing within MAX_BEND of square, turned towards the same side.
            Vector2 aside = new Vector2(tangent).mulAdd(normal, -along);
            if (aside.len2() < 1e-8f) { return normal; }
            aside.nor();
            return normal.scl((float) Math.cos(MAX_BEND)).mulAdd(aside, (float) Math.sin(MAX_BEND));
        };
    }

    /** Whether the road crosses this border between plain two-way hexes at one level, so it may bend there. */
    private static boolean bent(Coords coords, int direction, Function<Coords, Node> nodes) {
        Node self = nodes.apply(coords), next = nodes.apply(coords.translated(direction));
        return self != null && next != null && self.simple() && next.simple() && self.elevation() == next.elevation()
              && (self.exits() & 1 << direction) != 0 && (next.exits() & 1 << (direction + 3) % 6) != 0;
    }

    /** Whether a straight road crosses this border at one level into a straight road, plain or not. */
    private static boolean straightThrough(Coords coords, int direction, Function<Coords, Node> nodes) {
        Node self = nodes.apply(coords), next = nodes.apply(coords.translated(direction));
        int opposite = (direction + 3) % 6, through = 1 << direction | 1 << opposite;
        return self != null && next != null && self.elevation() == next.elevation() && self.exits() == through
              && next.exits() == through;
    }

    /** A two-way road's exit other than the given one. */
    private static int other(Node node, int direction) {
        return Integer.numberOfTrailingZeros(node.exits() & ~(1 << direction));
    }

    /**
     * The direction at a crossing of the circular arc that runs into a square approach's corridor along its axis:
     * the axis mirrored in the chord between them. It points into the corridor's hex, whose centre and border
     * midpoint are given.
     */
    private static Vector2 arc(Vector2 center, Vector2 border, Vector2 crossing) {
        Vector2 axis = new Vector2(border).nor();
        Vector2 chord = new Vector2(center).mulAdd(border, CORRIDOR).sub(crossing).nor();
        return chord.scl(2 * chord.dot(axis)).sub(axis);
    }

    /** The midpoint of the border in the given direction, in this hex's tile pixels. */
    private static Vector2 border(int direction) {
        float[] point = RailRoute.border(direction);
        return new Vector2(point[0], point[1]);
    }

    static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors) {
        return layout(coords, exits, kind, neighbors, direction -> null);
    }

    static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors, IntFunction<Vector2> bends) {
        return layout(coords, exits, kind, neighbors, bends, true, false);
    }

    /** {@code radial} runs every exit straight to the centre, as a junction's arms do. */
    private static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors,
          IntFunction<Vector2> bends, boolean circulation, boolean radial) {
        List<Point> ends = new ArrayList<>();
        List<Vector2> directions = new ArrayList<>();
        List<Integer> sides = new ArrayList<>();
        List<Join> joins = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & (1 << direction)) == 0) { continue; }
            Vector2 middle = border(direction);
            float x = middle.x, y = middle.y;
            Kind neighbor = neighbors.apply(direction);
            float width = neighbor == Kind.NONE ? kind.halfWidth : (kind.halfWidth + neighbor.halfWidth) / 2;
            ends.add(new Point(x, y, width));
            directions.add(radial ? null : bends.apply(direction));
            sides.add(direction);
            if (neighbor != Kind.NONE && neighbor != kind) {
                float length = (float) Math.hypot(x, y);
                joins.add(new Join(direction, neighbor, new End(new Point(x, y, width), x / length, y / length)));
            }
        }
        List<List<Point>> paths = new ArrayList<>();
        List<End> terminals = new ArrayList<>();
        Circle circle = circulation && roundabout(exits) ? new Circle(0, 0, ROUNDABOUT_RADIUS, 1, coords) : null;
        if (circle != null) {
            // One circulating lane, as wide as each incoming lane of the two-way approach.
            paths.add(ring(circle, kind.halfWidth / 2));
            for (Point end : ends) { entry(paths, end, circle, kind.halfWidth); }
        } else if (ends.size() == 2 && !radial
              && RailRoute.piece(sides.get(0), sides.get(1)) >= 3
              && square(ends.get(0), directions.get(0)) && square(ends.get(1), directions.get(1))) {
            // A 120-degree connection uses the same broad course as its rail. Holding straight corridors inside
            // this bend pinches the bridge around the track, even though both reach the same border midpoints.
            paths.add(course(ends.get(0), ends.get(1)));
        } else if (ends.size() == 2) {
            // A return turn needs the whole hex to keep its inner shoulder open at full road width. A climbing one holds
            // both ramp corridors instead; its legs converge too closely for an open inner shoulder, which closes into
            // the wedge between the ramps.
            boolean returnTurn = !radial && RailRoute.piece(sides.get(0), sides.get(1)) < 0;
            if (returnTurn) {
                for (int i = 0; i < directions.size(); i++) {
                    if (directions.get(i) == null) { directions.set(i, new Vector2(ends.get(i).x, ends.get(i).y).nor()); }
                }
            }
            // Other square approaches keep to their ramp corridor; bent ones turn from their border.
            List<Point> path = new ArrayList<>(), tail = new ArrayList<>();
            Point from = approach(path, ends.get(0), directions.get(0), kind);
            Point to = approach(tail, ends.get(1), directions.get(1), kind);
            Vector2 in = inward(ends.get(0), directions.get(0)), out = inward(ends.get(1), directions.get(1));
            if (returnTurn) {
                // A short square throat keeps the full-width join exact before the circular return begins.
                float lead = .04f * Math.min((float) Math.hypot(from.x, from.y), (float) Math.hypot(to.x, to.y));
                line(path, from, new Point(from.x + in.x * lead, from.y + in.y * lead, from.width));
                line(tail, to, new Point(to.x + out.x * lead, to.y + out.y * lead, to.width));
                from = path.getLast();
                to = tail.getLast();
            }
            float dx = to.x - from.x, dy = to.y - from.y, cross = in.x * out.y - in.y * out.x;
            float chord = (float) Math.hypot(dx, dy), reachIn = .39f * chord, reachOut = reachIn;
            if (Math.abs(dx * in.y - dy * in.x) < .0001f * chord
                  && Math.abs(dx * out.y - dy * out.x) < .0001f * chord
                  && dx * in.x + dy * in.y > 0 && dx * out.x + dy * out.y < 0) {
                line(path, from, to);
            } else {
                Point finish = to;
                if (Math.abs(cross) > 1e-4f) {
                    float s = (dx * out.y - dy * out.x) / cross, u = (dx * in.y - dy * in.x) / cross;
                    if (s > 0 && u > 0) {
                        // A circular fillet leaves room inside a tight turn. A quadratic pulled towards the
                        // tangent intersection can bend more sharply than the road's own half-width.
                        float arm = Math.min(s, u);
                        Point start = new Point(from.x + in.x * (s - arm), from.y + in.y * (s - arm), from.width);
                        if (s > u) { line(path, from, start); }
                        from = start;
                        to = new Point(to.x + out.x * (u - arm), to.y + out.y * (u - arm), to.width);
                        float halfCos = (float) Math.sqrt((1 - Math.clamp(in.dot(out), -1f, 1f)) / 2);
                        reachIn = reachOut = 4 * arm * halfCos / (3 * (1 + halfCos));
                    }
                }
                for (int i = 1; i <= 16; i++) {
                    float t = i / 16f, s = 1 - t;
                    float bx = from.x + in.x * reachIn, by = from.y + in.y * reachIn;
                    float cx = to.x + out.x * reachOut, cy = to.y + out.y * reachOut;
                    path.add(new Point(s * s * s * from.x + 3 * s * s * t * bx + 3 * s * t * t * cx + t * t * t * to.x,
                          s * s * s * from.y + 3 * s * s * t * by + 3 * s * t * t * cy + t * t * t * to.y,
                          from.width + t * (to.width - from.width)));
                }
                if (to.x != finish.x || to.y != finish.y) { line(path, to, finish); }
            }
            path.addAll(tail.reversed().subList(1, tail.size()));
            paths.add(path);
        } else if (ends.isEmpty()) {
            float radius = Math.abs(border(0).y);
            List<Point> path = new ArrayList<>();
            line(path, new Point(0, -HexTile.ROAD_END_REACH * radius, kind.halfWidth),
                  new Point(0, HexTile.ROAD_END_REACH * radius, kind.halfWidth));
            paths.add(path);
            terminals.add(new End(path.getFirst(), 0, -1));
            terminals.add(new End(path.getLast(), 0, 1));
        } else {
            for (Point end : ends) {
                List<Point> path = new ArrayList<>();
                if (ends.size() == 1) {
                    // A dead end occupies most of its tile, but never invents an exit into the opposite hex.
                    float[] stop = RailRoute.roadEnd(sides.getFirst());
                    Point tip = new Point(stop[0], stop[1], kind.halfWidth);
                    line(path, tip, new Point(0, 0, kind.halfWidth));
                    float length = (float) Math.hypot(end.x, end.y);
                    terminals.add(new End(tip, -end.x / length, -end.y / length));
                }
                List<Point> arm = new ArrayList<>();
                approach(arm, end, null, kind);
                line(path, new Point(0, 0, kind.halfWidth), arm.getLast());
                path.addAll(arm.reversed().subList(1, arm.size()));
                paths.add(path);
            }
        }
        if (circle == null && ends.size() >= 3) {
            // Keep the radial approaches for paint and wear, but reserve each legal broad turn at full width.
            // An exit-only bridge must also carry explicit rail layouts, not just the default junction pieces.
            for (int a = 0; a < ends.size(); a++) {
                for (int b = a + 1; b < ends.size(); b++) {
                    if (RailRoute.piece(sides.get(a), sides.get(b)) >= 3) {
                        paths.add(course(ends.get(a), ends.get(b)));
                    } else if (Math.floorMod(sides.get(a) - sides.get(b), 6) == 1
                          || Math.floorMod(sides.get(b) - sides.get(a), 6) == 1) {
                        // Round the tight corners of a crossing too, without forcing its hex exits onto square axes.
                        // These short turn envelopes share the same distance/clearance calculation as every road.
                        Point from = scale(ends.get(a), .64f, kind.halfWidth);
                        Point to = scale(ends.get(b), .64f, kind.halfWidth);
                        List<Point> turn = new ArrayList<>(List.of(from));
                        curve(turn, from, scale(from, 1f / 3, kind.halfWidth), scale(to, 1f / 3, kind.halfWidth), to);
                        paths.add(turn);
                    }
                }
            }
        }
        return new BoardRoad(coords, paths, List.copyOf(ends), terminals, joins, circle);
    }

    /**
     * A small triangular road loop is one two-lane roundabout shared by its three ground tiles. Each tile derives
     * the same circle and approaches; each outward approach retains its existing border crossing. Rails, grades, overlapping
     * loops and sideways entrances keep their authored junctions.
     */
    BoardRoad circulate(Function<Coords, Node> nodes) {
        if (circle != null) { return this; }
        var choices = triangles(coords, nodes);
        if (choices.size() != 1) { return this; }
        var members = choices.getFirst();
        for (Coords member : members) {
            if (triangles(member, nodes).size() != 1) { return this; }
        }
        float x = 0, y = 0;
        for (Coords member : members) {
            x += BoardGeometry.centerX(member);
            y += BoardGeometry.centerY(member);
        }
        Circle shared = new Circle((x / 3 - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale(),
              (y / 3 - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale(), 32, 2, members.getFirst());
        List<List<Point>> courses = new ArrayList<>();
        List<Point> entrances = new ArrayList<>();
        courses.add(ring(shared, Kind.PAVED.halfWidth));
        for (Coords member : members) {
            int internal = 0;
            for (Coords other : members) {
                if (!member.equals(other)) { internal |= 1 << member.direction(other); }
            }
            int external = Integer.numberOfTrailingZeros(nodes.apply(member).exits() & ~internal);
            Vector2 gate = border(external);
            float dx = (BoardGeometry.centerX(member) - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale();
            float dy = (BoardGeometry.centerY(member) - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale();
            entrances.add(new Point(dx + gate.x, dy + gate.y, Kind.PAVED.halfWidth));
            List<List<Point>> approach = new ArrayList<>();
            entry(approach, new Point(gate.x, gate.y, Kind.PAVED.halfWidth),
                  new Circle(shared.x - dx, shared.y - dy, shared.radius, shared.lanes, shared.owner), Kind.PAVED.halfWidth);
            for (var path : approach) {
                courses.add(path.stream().map(p -> new Point(p.x + dx, p.y + dy, p.width)).toList());
            }
        }
        return new BoardRoad(coords, courses, List.copyOf(entrances), List.of(), List.of(), shared);
    }

    /** Eligible three-cell cycles touching this tile, in canonical order so every member gets identical geometry. */
    private static List<List<Coords>> triangles(Coords at, Function<Coords, Node> nodes) {
        Node node = nodes.apply(at);
        if (node == null || !node.circulating() || Integer.bitCount(node.exits()) != 3) { return List.of(); }
        List<List<Coords>> result = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            int pair = (1 << direction) | (1 << ((direction + 1) % 6));
            if ((node.exits() & pair) != pair) { continue; }
            var members = new ArrayList<>(List.of(at, at.translated(direction), at.translated((direction + 1) % 6)));
            boolean valid = true;
            for (Coords member : members) {
                Node here = nodes.apply(member);
                if (here == null || !here.circulating() || here.elevation() != node.elevation()
                      || Integer.bitCount(here.exits()) != 3) { valid = false; break; }
                int inner = 0;
                for (Coords other : members) {
                    if (!other.equals(member)) { inner |= 1 << member.direction(other); }
                }
                if ((here.exits() & inner) != inner) { valid = false; break; }
                int outward = here.exits() & ~inner;
                int opposite = ((inner << 3) | (inner >> 3)) & 63;
                if ((outward & opposite) == 0) { valid = false; break; }
                int side = Integer.numberOfTrailingZeros(outward);
                Node next = nodes.apply(member.translated(side));
                if (next == null || !next.circulating() || next.elevation() != here.elevation()
                      || (next.exits() & 1 << ((side + 3) % 6)) == 0) { valid = false; break; }
            }
            if (valid) {
                members.sort(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY));
                result.add(List.copyOf(members));
            }
        }
        return List.copyOf(result);
    }

    private static List<Point> ring(Circle circle, float width) {
        List<Point> ring = new ArrayList<>();
        for (int i = 0; i <= 96; i++) {
            double angle = i * Math.PI / 48;
            ring.add(new Point(circle.x + circle.radius * (float) Math.cos(angle),
                  circle.y + circle.radius * (float) Math.sin(angle), width));
        }
        return ring;
    }

    /** Incoming right-hand traffic merges CCW; reversing the other connector leaves the ring in that same direction. */
    private static void entry(List<List<Point>> paths, Point end, Circle circle, float halfWidth) {
        float length = (float) Math.hypot(end.x, end.y), nx = end.x / length, ny = end.y / length;
        float width = halfWidth / 2, radius = circle.radius + (circle.lanes - 1) * width;
        float along = circle.x * nx + circle.y * ny, across = -circle.x * ny + circle.y * nx;
        List<Point> approachPath = new ArrayList<>();
        // Fill the space between the two turning lanes without cutting into the central island.
        float throatRadius = circle.radius + (2 - circle.lanes) * width;
        float throat = along + (float) Math.sqrt(Math.max(0, throatRadius * throatRadius - across * across));
        line(approachPath, local(nx, ny, throat, 0, halfWidth), scale(end, 1.06f, end.width));
        paths.add(approachPath);
        for (int side : new int[] { -1, 1 }) {
            float offset = side * width;
            List<Point> lane = new ArrayList<>();
            Point start = local(nx, ny, length * 1.06f, offset, end.width / 2);
            // An externally tangent circle rounds both carriageway edges into the ring. A free cubic can cross
            // the ring early, leaving a sharp cusp where its widened envelope meets the circulating carriageway.
            // Entries deflect traffic into the circulating lane; departures unwind on a gentler, wider curve.
            float turn = side < 0 ? 2 * halfWidth : halfWidth, fy = offset + side * turn;
            float fx = along + (float) Math.sqrt((radius + turn) * (radius + turn) - (fy - across) * (fy - across));
            float jx = along + (fx - along) * radius / (radius + turn);
            float jy = across + (fy - across) * radius / (radius + turn);
            Point approach = local(nx, ny, fx, offset, width);
            line(lane, start, approach);
            float angle = (float) Math.acos(Math.clamp(side * (fy - jy) / turn, -1f, 1f));
            float reach = 4 * turn / 3 * (float) Math.tan(angle / 4);
            float tx = -side * (jy - across) / radius, ty = side * (jx - along) / radius;
            curve(lane, approach, local(nx, ny, fx - reach, offset, width),
                  local(nx, ny, jx - tx * reach, jy - ty * reach, width), local(nx, ny, jx, jy, width));
            paths.add(lane);
        }
    }

    /** A full-width gentle turn, sharing the rail's course and extending squarely beyond both borders. */
    private static List<Point> course(Point from, Point to) {
        List<Point> path = new ArrayList<>();
        line(path, scale(from, 1.06f, from.width), from);
        for (int i = 1; i <= 24; i++) {
            float t = i / 24f;
            float[] point = RailRoute.course(from.x, from.y, to.x, to.y, t);
            path.add(new Point(point[0], point[1], from.width + t * (to.width - from.width)));
        }
        line(path, to, scale(to, 1.06f, to.width));
        return path;
    }

    /** Whether the shared crossing is square to its border, including a held ramp approach. */
    private static boolean square(Point end, Vector2 bend) {
        return bend == null || Math.abs(end.x * bend.y - end.y * bend.x) < .001f
              && end.x * bend.x + end.y * bend.y > 0;
    }

    /**
     * Adds the approach through one border, from just outside it inwards, including the border crossing itself, and
     * returns where the approach may start to turn: halfway in for a square approach, which holds the ramp corridor,
     * or at the border for a bent one.
     */
    private static Point approach(List<Point> path, Point end, Vector2 bend, Kind kind) {
        if (bend == null) {
            line(path, scale(end, 1.06f, end.width), end);
            line(path, end, scale(end, .9f, end.width));
            line(path, scale(end, .9f, end.width), scale(end, CORRIDOR, kind.halfWidth));
        } else {
            float reach = .06f * (float) Math.hypot(end.x, end.y);
            line(path, new Point(end.x + bend.x * reach, end.y + bend.y * reach, end.width), end);
        }
        return path.getLast();
    }

    /** The direction an approach heads into the hex at its turning point; see {@link #approach}. */
    private static Vector2 inward(Point end, Vector2 bend) {
        return bend == null ? new Vector2(-end.x, -end.y).nor() : new Vector2(bend).scl(-1);
    }

    private static Point scale(Point p, float scale, float width) { return new Point(p.x * scale, p.y * scale, width); }

    /** Continue bridge paint and wear in world space through bank extensions, preserving the central junction. */
    BoardRoad extended(List<Float> lengths) {
        return extended(lengths, 0);
    }

    BoardRoad extended(List<Float> lengths, int terminalExits) {
        return extended(lengths, terminalExits, coords);
    }

    /** Carry a bridge's extended paint along the bank's existing course instead of adding a straight spur. */
    BoardRoad followBanks(Map<Integer, BoardRoad> banks) {
        if (banks.isEmpty()) { return this; }
        List<List<Point>> changed = new ArrayList<>();
        for (var path : paths) {
            List<Point> points = new ArrayList<>();
            for (int i = 0; i < path.size(); i++) {
                Point a = path.get(Math.max(0, i - 1)), b = path.get(i);
                int count = i == 0 ? 1 : Math.max(1, (int) Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 2));
                for (int sample = 1; sample <= count; sample++) {
                    float t = sample / (float) count;
                    var point = new Vector2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
                    for (var bank : banks.entrySet()) {
                        point = bank.getValue().bendFrom(coords, (bank.getKey() + 3) % 6, point.x, point.y);
                    }
                    points.add(new Point(point.x, point.y, a.width + (b.width - a.width) * t));
                }
            }
            changed.add(List.copyOf(points));
        }
        var result = new BoardRoad(coords, changed, borders, ends, joins, circle);
        result.corridor = corridor;
        result.solidApproaches = solidApproaches;
        result.apronExits = apronExits;
        result.concreteEntrances = concreteEntrances;
        result.control = control;
        return result;
    }

    /** Bend a point past the bank's shared border, retaining its offset across the incoming carriageway. */
    Vector2 bendFrom(Coords origin, int direction, float x, float y) {
        float dx = HexTile.centerX(origin, BoardGeometry.TILE_WIDTH) - HexTile.centerX(coords, BoardGeometry.TILE_WIDTH);
        float dy = HexTile.centerY(origin, BoardGeometry.TILE_HEIGHT) - HexTile.centerY(coords, BoardGeometry.TILE_HEIGHT);
        var gate = border(direction);
        var axis = new Vector2(gate).nor();
        float along = (x + dx) * axis.x + (y + dy) * axis.y;
        if (along >= gate.len() || paths.size() != 1) { return new Vector2(x, y); }
        var frame = approach(paths.getFirst(), direction, along);
        var mouth = approach(paths.getFirst(), direction, gate.len());
        if (frame == null || mouth == null) { return new Vector2(x, y); }
        var path = paths.getFirst();
        Vector2 normal = new Vector2(frame.nx(), frame.ny());
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            float from = a.x * axis.x + a.y * axis.y, to = b.x * axis.x + b.y * axis.y;
            if (along < Math.min(from, to) || along > Math.max(from, to) || Math.abs(to - from) < .001f) { continue; }
            float sign = Math.signum(to - from), t = (along - from) / (to - from);
            normal = bankTangent(path, i - 1, sign, gate, axis).lerp(bankTangent(path, i, sign, gate, axis), t).nor();
            break;
        }
        float across = -(x + dx - mouth.x()) * axis.y + (y + dy - mouth.y()) * axis.x;
        return new Vector2(frame.x() - normal.y * across - dx, frame.y() + normal.x * across - dy);
    }

    private static Vector2 bankTangent(List<Point> path, int index, float sign, Vector2 gate, Vector2 axis) {
        Point point = path.get(index);
        if (Math.hypot(point.x - gate.x, point.y - gate.y) < .001f) { return new Vector2(axis); }
        Point before = path.get(Math.max(0, index - 1)), after = path.get(Math.min(path.size() - 1, index + 1));
        return new Vector2(point.x - before.x, point.y - before.y).nor()
              .add(new Vector2(after.x - point.x, after.y - point.y).nor()).nor().scl(sign);
    }

    /** Shared roundabouts can extend a member's approach while retaining their current local coordinate frame. */
    private BoardRoad extended(List<Float> lengths, int terminalExits, Coords origin) {
        float ox = (BoardGeometry.centerX(origin) - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale();
        float oy = (BoardGeometry.centerY(origin) - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale();
        List<List<Point>> extended = new ArrayList<>();
        for (var path : paths) {
            var points = new ArrayList<>(path);
            for (int d = 0; d < 6; d++) {
                if (lengths.get(d) <= 0) { continue; }
                Coords next = origin.translated(d);
                float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(origin)) / (2 * BoardGeometry.hexScale());
                float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(origin)) / (2 * BoardGeometry.hexScale());
                float length = (float) Math.hypot(x, y), reach = lengths.get(d) + .25f;
                float[] shift = RailRoute.borderOffset(d, BESIDE_ROAD * corridor.lateral(d));
                for (int end : new int[] { 0, points.size() - 1 }) {
                    var p = points.get(end);
                    if (Math.hypot(p.x - ox - shift[0] - x * 1.06f, p.y - oy - shift[1] - y * 1.06f) < .01f) {
                        points.set(end, new Point(ox + x * (1 + reach / length) + shift[0],
                              oy + y * (1 + reach / length) + shift[1], p.width));
                    }
                }
            }
            extended.add(List.copyOf(points));
        }
        var terminals = new ArrayList<>(ends);
        for (int d = 0; d < 6; d++) {
            if ((terminalExits & (1 << d)) == 0) { continue; }
            Coords next = coords.translated(d);
            float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(coords)) / (2 * BoardGeometry.hexScale());
            float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(coords)) / (2 * BoardGeometry.hexScale());
            float length = (float) Math.hypot(x, y), reach = 1 + lengths.get(d) / length;
            terminals.add(new End(new Point(x * reach, y * reach, Kind.PAVED.halfWidth), x / length, y / length));
        }
        BoardRoad result = new BoardRoad(coords, extended, borders, terminals, joins, circle);
        result.corridor = corridor;
        result.solidApproaches = solidApproaches;
        result.apronExits = apronExits;
        result.concreteEntrances = concreteEntrances;
        result.control = control;
        result.meetings = meetings;
        result.squared = squared;
        result.tracked = tracked;
        result.apart = apart;
        result.paved = paved;
        result.mouths = mouths;
        return result;
    }

    static boolean roundabout(int exits) { return Integer.bitCount(exits & 63) >= 5; }

    private static Point local(float nx, float ny, float along, float across, float width) {
        return new Point(nx * along - ny * across, ny * along + nx * across, width);
    }

    private static void curve(List<Point> path, Point a, Point b, Point c, Point d) {
        for (int i = 1; i <= 20; i++) {
            float t = i / 20f, s = 1 - t;
            path.add(new Point(s * s * s * a.x + 3 * s * s * t * b.x + 3 * s * t * t * c.x + t * t * t * d.x,
                  s * s * s * a.y + 3 * s * s * t * b.y + 3 * s * t * t * c.y + t * t * t * d.y, d.width));
        }
    }

    private static void line(List<Point> path, Point a, Point b) {
        if (path.isEmpty()) { path.add(a); }
        // A line needs only its endpoints. Retain each explicit border point, which anchors the centre-line dashes.
        path.add(b);
    }

    /** Union before tessellation: junctions have one surface, without overlapping translucent road arms. */
    Area footprint(float margin) {
        // Road patches reuse several margins; callers subtract their own masks from a fresh copy.
        return new Area(outlines.computeIfAbsent(margin, this::outline));
    }

    private Area outline(float margin) {
        Area area = new Area();
        for (var path : paths) {
            if (path.stream().allMatch(point -> point.width == path.getFirst().width)) {
                // A round stroke is the same constant-width envelope as the segment rectangles and vertex discs.
                Path2D.Float lane = new Path2D.Float();
                lane.moveTo(path.getFirst().x, path.getFirst().y);
                for (int i = 1; i < path.size(); i++) { lane.lineTo(path.get(i).x, path.get(i).y); }
                float width = 2 * Math.max(.1f, path.getFirst().width + margin);
                area.add(new Area(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                      .createStrokedShape(lane)));
                continue;
            }
            for (int i = 0; i < path.size(); i++) {
                Point a = path.get(i);
                float radius = Math.max(.1f, a.width + margin);
                area.add(new Area(new Ellipse2D.Float(a.x - radius, a.y - radius, 2 * radius, 2 * radius)));
                if (i == 0) { continue; }
                Point b = path.get(i - 1);
                float length = (float) Math.hypot(b.x - a.x, b.y - a.y);
                if (length < .001f) { continue; }
                float nx = (b.y - a.y) / length, ny = (a.x - b.x) / length;
                float previous = Math.max(.1f, b.width + margin);
                Path2D.Float quad = new Path2D.Float();
                quad.moveTo(a.x + nx * radius, a.y + ny * radius);
                quad.lineTo(b.x + nx * previous, b.y + ny * previous);
                quad.lineTo(b.x - nx * previous, b.y - ny * previous);
                quad.lineTo(a.x - nx * radius, a.y - ny * radius);
                quad.closePath();
                area.add(new Area(quad));
            }
        }
        for (float[] mouth : mouths) {
            Path2D.Float gore = new Path2D.Float();
            gore.moveTo(mouth[0], mouth[1]);
            for (int i = 2; i < mouth.length; i += 2) { gore.lineTo(mouth[i], mouth[i + 1]); }
            gore.closePath();
            area.add(new Area(gore));
        }
        clipEnds(area, margin);
        for (Join join : joins) {
            // Every outline stops on the border: a verge never lays a strip across the square end.
            if ((squared & 1 << join.direction()) != 0) { area.subtract(square(join, 0)); }
        }
        return area;
    }

    /** Paint can cross inactive hex edges, but each connected approach owns only its side of the mouth. */
    void clipPaint(Area area) { clipEnds(area, 0); }

    /** Clip only real termini. Curves and the shared hub of a junction keep their continuous joins. */
    private void clipEnds(Area area, float margin) {
        for (End end : ends) {
            var bounds = area.getBounds2D();
            // Bridge footings and their aprons can reach farther than a tile-sized clipping rectangle.
            float reach = (float) (Math.hypot(bounds.getWidth(), bounds.getHeight()) / 2
                  + Math.hypot(bounds.getCenterX() - end.point.x, bounds.getCenterY() - end.point.y) + 1);
            area.intersect(end.band(-reach, margin, reach));
        }
    }

    Area endZone(float length) {
        Area result = new Area();
        if (length > 0) {
            for (End end : ends) { result.add(end.band(-length, SHOULDER)); }
        }
        return result;
    }

    float endDistance(float x, float y) {
        float distance = Float.NEGATIVE_INFINITY;
        for (End end : ends) { distance = Math.max(distance, end.distance(x, y)); }
        return distance;
    }

    Coords coords() { return coords; }
    /** Canonical source for a shared footprint, preventing repeated blended paint on neighbouring support cells. */
    Coords owner() { return circle == null ? coords : circle.owner; }
    List<Join> joins() { return joins; }

    /** Symmetric feathering changes tyre wear intensity, never the axle spacing or tyre width. */
    float wheelCoverage(float x, float y) {
        float t = Math.clamp((TRACK_HALF_WIDTH - Math.abs(laneOffset(x, y) - WHEEL_OFFSET)) / TRACK_HALF_WIDTH, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** How far a point lies from the centre line of its lane, whichever side. */
    private float laneOffset(float x, float y) {
        PathSample nearest = pathSample(x, y, paintPaths(), ALL_ENDS);
        return Math.abs(nearest.distance() - laneCenter(nearest.width()));
    }

    /**
     * Where a point lies across its lane for paved wear (terrain-road.frag): 0 on the lane's centre line, 1 at its edge,
     * a two-way road's middle or kerb, so its wheel paths lie at WHEEL_OFFSET of that.
     */
    float wearLane(float x, float y) { return Math.min(1, laneOffset(x, y) / (Kind.PAVED.halfWidth / 2)); }

    /**
     * How far into a junction's hub a point lies for paved wear: 1 where the approaches' lanes cross and turning
     * traffic wears the whole surface, fading to 0 on the arms, whose lanes carry their own wear.
     */
    float wearHub(float x, float y) { return circle != null ? 0 : hub(x, y, 14, 10); }

    /**
     * A dirt track's hub, as {@link #wearHub}: wider, so its wheel tracks and crown give way to bare, evenly worn ground
     * well before the crossing. A roundabout's narrow lanes wear that way all over.
     */
    float trackHub(float x, float y) { return circle != null ? 1 : hub(x, y, 12, 16); }

    /** 1 within {@code radius} of a junction's centre, fading to 0 over {@code fade}; 0 off junctions. */
    private float hub(float x, float y, float radius, float fade) {
        if (borders.size() < 3) { return 0; }
        float t = Math.clamp(((float) Math.hypot(x, y) - radius) / fade, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * A dirt road's track at a point (terrain-road.frag), from the one nearest course its paint follows: the heading,
     * and where across the road the point lies, 0 on that course and 1 at a dirt road's full half width with its
     * shoulder, in the same metres on every course.
     */
    record Track(Vector2 heading, float across) { }

    Track track(float x, float y) {
        // Past a border the road runs on straight, as the neighbour's measures it up to there, never round an end.
        PathSample nearest = pathSample(x, y, paintPaths(), BORDER_ENDS);
        // A mouth onto a paved road is loose ground, past the wheel tracks' reach (#meet).
        float across = Math.min(nearest.distance(), MOUTH + Math.max(0, mouthEdge(x, y))) / TRACK_REACH;
        return new Track(new Vector2(nearest.dx(), nearest.dy()).nor(), Math.min(1, across));
    }

    /** {@link #track}'s whole width: a dirt road's half width with its shoulder. */
    private static final float TRACK_REACH = Kind.DIRT.halfWidth + SHOULDER;
    /** How far from a course a mouth's ground lies (#track): past the wheel tracks, short of the shoulder. */
    private static final float MOUTH = .35f * TRACK_REACH;

    /** Disturbed soil/fines thin out in patches; their final extent still stays inside the terminal plane. */
    float endCoverage(float x, float y, float length) {
        float t = Math.clamp(-endDistance(x, y) / length, 0, 1);
        float wx = x + BoardGeometry.centerX(coords) / BoardGeometry.hexScale();
        float wy = y + BoardGeometry.centerY(coords) / BoardGeometry.hexScale();
        float noise = BoardRelief.noise(wx / 2.1f, wy / 2.1f);
        t = Math.clamp(t + (noise - .5f) * 5 * t * (1 - t), 0, 1);
        return t * t * (3 - 2 * t);
    }

    float distance(float x, float y) {
        return Math.max(Math.max(Math.min(pathSample(x, y, paths).edge(), mouthEdge(x, y)), endDistance(x, y)),
              squareEdge(x, y));
    }

    /** The same nearest path supplies the wheel mask and the direction of shallow, elongated scuffs. */
    Vector2 wheelDirection(float x, float y) {
        PathSample sample = pathSample(x, y, paintPaths());
        return new Vector2(sample.dx(), sample.dy()).nor();
    }

    private record PathSample(float distance, float edge, float dx, float dy, float width) { }

    /** Single circulating lanes keep one axle; two-way roads place an axle on each side of their divider or median. */
    private float laneCenter(float width) {
        if (width <= Kind.PAVED.halfWidth / 2 + .001f) { return 0; }
        float share = corridor.middle() == 0 ? 0 : Math.clamp((width - Kind.PAVED.halfWidth) / (MIDDLE_HALF - Kind.PAVED.halfWidth), 0, 1);
        return width / 2 + MEDIAN_HALF / 2 * share;
    }

    /** Junction turns widen the carrier; their original radial approaches still own its markings and tyre wear. */
    private List<List<Point>> paintPaths() {
        return circle == null && borders.size() >= 3 ? paths.subList(0, borders.size()) : paths;
    }

    private PathSample pathSample(float x, float y, List<List<Point>> sampledPaths) {
        return pathSample(x, y, sampledPaths, 0);
    }

    /** Per path, which ends run on (#pathSample): its first end's flags, and its last end's shifted once; none on a ring. */
    private volatile byte[] pathEnds;
    private static final int ALL_ENDS = 1, BORDER_ENDS = 4;
    /** Path ends this far out lie past the hex's border (#approach). */
    private static final float BORDER = BoardGeometry.TILE_HEIGHT / 2 - .5f;

    private byte[] pathEnds() {
        byte[] result = pathEnds;
        if (result != null) { return result; }
        result = new byte[paths.size()];
        for (int p = 0; p < paths.size(); p++) {
            var path = paths.get(p);
            // A ring closes on itself (to rounding): it has no ends.
            if (Math.hypot(path.getFirst().x - path.getLast().x, path.getFirst().y - path.getLast().y) <= .01) { continue; }
            int flags = ALL_ENDS | ALL_ENDS << 1;
            if (Math.hypot(path.getFirst().x, path.getFirst().y) > BORDER) { flags |= BORDER_ENDS; }
            if (Math.hypot(path.getLast().x, path.getLast().y) > BORDER) { flags |= BORDER_ENDS << 1; }
            result[p] = (byte) flags;
        }
        pathEnds = result;
        return result;
    }

    /**
     * A path's {@code open} ends run straight on: every end ({@link #ALL_ENDS}) or those past the hex's border
     * ({@link #BORDER_ENDS}), so a point past one (an oblique border crossing leaves a wedge of carriageway beyond it)
     * still measures across the lane rather than round the end. With open ends, {@code sampledPaths} is {@link #paths}
     * or its prefix.
     */
    private PathSample pathSample(float x, float y, List<List<Point>> sampledPaths, int open) {
        float distance = Float.POSITIVE_INFINITY, edge = Float.POSITIVE_INFINITY, alongX = 0, alongY = 1, width = 0;
        byte[] ends = open == 0 ? null : pathEnds();
        for (int p = 0; p < sampledPaths.size(); p++) {
            var path = sampledPaths.get(p);
            boolean first = ends != null && (ends[p] & open) != 0, last = ends != null && (ends[p] & open << 1) != 0;
            for (int i = 1; i < path.size(); i++) {
                Point a = path.get(i - 1), b = path.get(i);
                float dx = b.x - a.x, dy = b.y - a.y;
                float along = ((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy);
                float t = Math.clamp(along, first && i == 1 ? Float.NEGATIVE_INFINITY : 0,
                      last && i == path.size() - 1 ? Float.POSITIVE_INFINITY : 1);
                float ex = x - a.x - t * dx, ey = y - a.y - t * dy;
                // Not Math.hypot, which runs in software: a road's paint samples this for every texel and segment.
                float near = (float) Math.sqrt((double) ex * ex + (double) ey * ey);
                t = Math.clamp(t, 0, 1);
                edge = Math.min(edge, near - a.width - t * (b.width - a.width));
                if (near < distance) { distance = near; alongX = dx; alongY = dy; width = a.width + t * (b.width - a.width); }
            }
        }
        return new PathSample(distance, edge, alongX, alongY, width);
    }

    /**
     * Distance along the existing two-exit course, used by its longitudinal terrain grade. Shared cross-sections
     * at path vertices keep this continuous across the lane; nearest-segment projection jumps inside a bend.
     */
    float throughStation(float x, float y) {
        if (borders.size() != 2 || paths.size() != 1) { return Float.NaN; }
        float station = 0, nearest = Float.POSITIVE_INFINITY, length = 0;
        boolean bounded = false;
        var path = paths.getFirst();
        float[] sections = sections();
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            float dx = b.x - a.x, dy = b.y - a.y, span = (float) Math.hypot(dx, dy);
            if (span == 0) { continue; }
            float from = (x - a.x) * sections[2 * i - 2] + (y - a.y) * sections[2 * i - 1];
            float to = (x - b.x) * sections[2 * i] + (y - b.y) * sections[2 * i + 1];
            boolean inside = from >= 0 && to <= 0;
            float t = inside ? from / (from - to)
                  : Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / (span * span), 0, 1);
            float ox = x - a.x - t * dx, oy = y - a.y - t * dy, distance = ox * ox + oy * oy;
            if (inside && !bounded || inside == bounded && distance < nearest) {
                bounded = inside; nearest = distance; station = length + t * span;
            }
            length += span;
        }
        return station;
    }

    /**
     * The unit normal {x, y} of {@link #throughStation}'s section at each vertex of the course: square to the bisector of
     * the segments meeting there. Where the course crosses a border, the section is the border itself, so both hexes
     * measure one station all along their shared edge: the lattice is regular only to a 32nd, and a square crossing is
     * not exactly square to it.
     */
    private float[] sections() {
        float[] result = sections;
        if (result != null) { return result; }
        var path = paths.getFirst();
        result = new float[2 * path.size()];
        for (int i = 0; i < path.size(); i++) {
            Point p = path.get(i);
            float sx = 0, sy = 0;
            if (i > 0) {
                Point before = path.get(i - 1);
                float run = (float) Math.hypot(p.x - before.x, p.y - before.y);
                if (run > 0) { sx += (p.x - before.x) / run; sy += (p.y - before.y) / run; }
            }
            if (i + 1 < path.size()) {
                Point after = path.get(i + 1);
                float run = (float) Math.hypot(after.x - p.x, after.y - p.y);
                if (run > 0) { sx += (after.x - p.x) / run; sy += (after.y - p.y) / run; }
            }
            float[] border = borderNormal(p, sx, sy);
            if (border != null) { sx = border[0]; sy = border[1]; }
            float length = (float) Math.hypot(sx, sy);
            if (length > 0) { result[2 * i] = sx / length; result[2 * i + 1] = sy / length; }
        }
        sections = result;
        return result;
    }

    /** The normal of the hex edge a course point lies on, turned along (dx, dy); null off the hex outline. */
    private static float[] borderNormal(Point p, float dx, float dy) {
        for (int k = 0; k < 6; k++) {
            float ax = HexTile.cornerOffsetX(k, BoardGeometry.TILE_WIDTH);
            float ay = HexTile.cornerOffsetY(k, BoardGeometry.TILE_HEIGHT);
            float ex = HexTile.cornerOffsetX((k + 1) % 6, BoardGeometry.TILE_WIDTH) - ax;
            float ey = HexTile.cornerOffsetY((k + 1) % 6, BoardGeometry.TILE_HEIGHT) - ay;
            float length = (float) Math.hypot(ex, ey), along = ((p.x - ax) * ex + (p.y - ay) * ey) / (length * length);
            if (along < 0 || along > 1 || Math.abs((p.x - ax) * ey - (p.y - ay) * ex) > .01f * length) { continue; }
            float nx = ey / length, ny = -ex / length, sign = nx * dx + ny * dy < 0 ? -1 : 1;
            return new float[] { nx * sign, ny * sign };
        }
        return null;
    }

    /**
     * Cross-sections of the two-exit course at the given stations and at each path vertex: station, point and unit
     * normal in tile pixels, as {@link #throughStation} measures them. Empty without such a course.
     */
    List<float[]> crossSections(List<Float> stations) {
        if (borders.size() != 2 || paths.size() != 1) { return List.of(); }
        List<float[]> result = new ArrayList<>();
        var path = paths.getFirst();
        float length = 0;
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            float span = (float) Math.hypot(b.x - a.x, b.y - a.y);
            if (span == 0) { continue; }
            float nx = -(b.y - a.y) / span, ny = (b.x - a.x) / span;
            for (float station : stations) {
                // A station at a vertex takes the vertex's own section below, not a second one beside it.
                if (station >= length + .05f && station < length + span - .05f) {
                    float t = (station - length) / span;
                    result.add(new float[] { station, a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), nx, ny });
                }
            }
            length += span;
            if (i + 1 < path.size()) {
                Point c = path.get(i + 1);
                float next = (float) Math.hypot(c.x - b.x, c.y - b.y);
                if (next == 0) { continue; }
                float mx = nx - (c.y - b.y) / next, my = ny + (c.x - b.x) / next, m = (float) Math.hypot(mx, my);
                if (m > 0) { result.add(new float[] { length, b.x, b.y, mx / m, my / m }); }
            }
        }
        return result;
    }

    /** The period nearest {@code period} that fits whole periods into {@code span}: road dashes, rail sleepers. */
    static float fit(float span, float period) { return span / Math.max(1, Math.round(span / period)); }

    /**
     * One junction approach in tile pixels. The axis at {@code x,y} points outwards along {@code nx,ny}; its incoming
     * right-hand lane lies from {@code inner} to {@code outer} along {@code -ny,nx}. Roadside controls use the same
     * position and face the outward normal, standing beyond {@code outer} on that side of the road.
     */
    record StopApproach(int direction, float x, float y, float nx, float ny, float inner, float outer) {
        private End edge() { return new End(new Point(x, y, outer), nx, ny); }
    }

    /** Track-width clearance and the road lanes that approach an actual same-level railway crossing. */
    record Crossing(Area clearance, List<StopApproach> approaches) { }

    Crossing crossing(BoardScene.Tile tile, boolean deck) {
        List<RailRoute.Line> lines = new ArrayList<>();
        int shared = 0;
        var bridge = BoardBridge.feature(tile);
        int bridgeExits = bridge == null ? 0 : bridge.bridgeExits();
        float level = tile.elevation() + (deck && bridge != null ? bridge.elevation() : 0);
        for (var feature : tile.features()) {
            var marker = feature.decoration();
            if (RailRoute.kind(marker) != RailRoute.Kind.RAIL || RailRoute.onDeck(marker) != deck
                  || Math.abs(BoardRail.level(tile, feature) - level) > .001f) { continue; }
            int sides = RailRoute.drawnSides(marker, bridgeExits);
            shared |= sides;
            lines.addAll(RailRoute.lines(sides, RailRoute.pieces(marker, sides), corridor));
        }
        int exits = deck ? tile.deckRoadExits() & bridgeExits : tile.roadExits();
        return crossing(exits, shared == exits ? List.of() : lines);
    }

    Crossing crossing(int roadExits, int railSides, int railPieces) {
        return crossing(roadExits, (roadExits & 63) == (railSides & 63) ? List.of()
              : RailRoute.lines(railSides, railPieces, corridor));
    }

    private Crossing crossing(int exits, List<RailRoute.Line> lines) {
        Area clearance = new Area();
        List<StopApproach> approaches = new ArrayList<>();
        if (circle != null || exits == 0 || lines.isEmpty()) { return new Crossing(clearance, List.of()); }
        List<List<Point>> rails = new ArrayList<>();
        for (var line : lines) {
            List<Point> points = new ArrayList<>();
            for (int i = 0; i <= 24; i++) {
                float[] p = line.at(i / 24f);
                points.add(new Point(p[0], p[1], RailRoute.BALLAST_HALF_WIDTH));
            }
            rails.add(points);
        }
        var roads = paintPaths();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & 1 << direction) == 0) { continue; }
            int d = direction;
            if (joins.stream().anyMatch(join -> join.direction() == d && join.kind() != Kind.PAVED)) { continue; }
            int index = borders.size() < 3 ? 0 : Integer.bitCount(exits & ((1 << direction) - 1));
            if (index >= roads.size()) { continue; }
            var road = roads.get(index);
            Area tracks = new Area();
            for (var rail : rails) {
                if (!crosses(road, rail)) { continue; }
                tracks.add(new Area(new BasicStroke(2 * (RailRoute.BALLAST_HALF_WIDTH + 1), BasicStroke.CAP_BUTT,
                      BasicStroke.JOIN_ROUND).createStrokedShape(linePath(rail))));
            }
            if (tracks.isEmpty()) { continue; }
            float width = road.stream().map(Point::width).max(Float::compare).orElse(Kind.PAVED.halfWidth);
            tracks.intersect(new Area(new BasicStroke(2 * width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                  .createStrokedShape(linePath(road))));
            clearance.add(tracks);
            var axis = border(direction).nor();
            float limit = road.stream().map(p -> p.x * axis.x + p.y * axis.y).max(Float::compare).orElse(0f) - .1f;
            for (float along = limit; along >= 0; along -= .5f) {
                var frame = approach(road, direction, along);
                if (frame == null) { continue; }
                boolean conflict = false;
                for (float across = frame.inner; across <= frame.outer; across += .5f) {
                    if (tracks.contains(frame.x - frame.ny * across, frame.y + frame.nx * across)) { conflict = true; break; }
                }
                if (!conflict) { continue; }
                // Leave the entire incoming lane before the rail band. A crossing at the very border has no
                // supported stop position inside this owner; its neighbouring approach must own the control.
                if (along + 1.75f <= limit) {
                    var stop = approach(road, direction, along + 1.75f);
                    if (stop != null) { approaches.add(stop); }
                }
                break;
            }
        }
        clearance.intersect(footprint(0));
        return new Crossing(clearance, List.copyOf(approaches));
    }

    /** Parallel embedded tracks are not level crossings; a transverse segment must actually cross the road course. */
    private static boolean crosses(List<Point> road, List<Point> rail) {
        for (int i = 1; i < road.size(); i++) {
            Point a = road.get(i - 1), b = road.get(i);
            float dx = b.x - a.x, dy = b.y - a.y;
            for (int j = 1; j < rail.size(); j++) {
                Point c = rail.get(j - 1), d = rail.get(j);
                float rx = d.x - c.x, ry = d.y - c.y, cross = dx * ry - dy * rx;
                if (Math.abs(cross) <= .25f * Math.sqrt((dx * dx + dy * dy) * (rx * rx + ry * ry))) { continue; }
                float t = ((c.x - a.x) * ry - (c.y - a.y) * rx) / cross;
                float u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / cross;
                if (t >= -.0001f && t <= 1.0001f && u >= -.0001f && u <= 1.0001f) { return true; }
            }
        }
        return false;
    }

    private static Path2D.Float linePath(List<Point> points) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(points.getFirst().x, points.getFirst().y);
        for (int i = 1; i < points.size(); i++) { path.lineTo(points.get(i).x, points.get(i).y); }
        return path;
    }

    /** Paved approaches that yield at a crossing; an opposite pair carries the through road of a T. */
    List<StopApproach> stopApproaches(int exits) {
        int stopped = exits & ~apronExits & 63;
        int count = Integer.bitCount(stopped);
        if (circle != null || count < 3) { return List.of(); }
        if (count == 3 && control == HexAppearance.TrafficControl.STOPS) {
            for (int direction = 0; direction < 3; direction++) {
                int through = (1 << direction) | (1 << (direction + 3));
                if ((stopped & through) == through) { stopped &= ~through; break; }
            }
        }
        int controlled = stopped;
        return conflictApproaches(exits).stream().filter(arm -> (controlled & 1 << arm.direction()) != 0)
              .filter(this::clearOfConcrete).toList();
    }

    private boolean clearOfConcrete(StopApproach approach) {
        Area bar = stopBar(approach);
        bar.intersect(concreteEntrances);
        return bar.isEmpty();
    }

    /** A lane waits before whichever obstruction comes first; an existing junction and railway never add two bars. */
    List<StopApproach> stopApproaches(int exits, Crossing crossing) {
        List<StopApproach> result = new ArrayList<>(stopApproaches(exits));
        for (var approach : crossing.approaches()) {
            int found = -1;
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).direction() == approach.direction()) { found = i; break; }
            }
            if (found < 0) { result.add(approach); }
            else {
                var current = result.get(found);
                var axis = border(approach.direction()).nor();
                if ((approach.x() - current.x()) * axis.x + (approach.y() - current.y()) * axis.y > 0) {
                    result.set(found, approach);
                }
            }
        }
        result.sort(Comparator.comparingInt(StopApproach::direction));
        return result.stream().filter(this::clearOfConcrete).toList();
    }

    /** Every approach leaves the crossing clear for turns; priority only decides which approaches also get stop bars. */
    private List<StopApproach> conflictApproaches(int exits) {
        if (circle != null || Integer.bitCount(exits & 63) < 3) { return List.of(); }
        List<StopApproach> result = new ArrayList<>();
        var arms = paintPaths();
        boolean boxed = Integer.bitCount(exits & 63) == 4 && control != HexAppearance.TrafficControl.STOPS;
        int remaining = exits & 63;
        for (var path : arms) {
            int side = Integer.lowestOneBit(remaining);
            remaining &= ~side;
            int direction = Integer.numberOfTrailingZeros(side);
            if (joins.stream().anyMatch(join -> join.direction() == direction && join.kind() != Kind.PAVED)) { continue; }
            var other = arms.stream().filter(arm -> arm != path).toList();
            float limit = border(direction).len() - 2;
            // A box encloses the full crossing, including its outgoing half. On a skew junction, checking only
            // the incoming lane cuts two box edges through the turning area. Bars and signals share this plane.
            float stop = 0;
            for (float along = limit; along >= 0; along -= .5f) {
                StopApproach frame = approach(path, direction, along);
                if (frame == null) { continue; }
                boolean conflict = false;
                float inner = boxed ? -frame.outer : frame.inner;
                int samples = Math.max(1, (int) Math.ceil(frame.outer - inner));
                for (int sample = 0; sample <= samples; sample++) {
                    float across = inner + (frame.outer - inner) * sample / samples;
                    if (pathSample(frame.x - frame.ny * across, frame.y + frame.nx * across, other).edge() <= 0) {
                        conflict = true;
                        break;
                    }
                }
                if (conflict) { stop = Math.min(limit, along + 1.75f); break; }
            }
            StopApproach frame = approach(path, direction, stop);
            if (frame != null) { result.add(frame); }
        }
        return List.copyOf(result);
    }

    /** An approach's actual fitted lane at this distance along the normal of its shared border. */
    private StopApproach approach(List<Point> path, int direction, float along) {
        Vector2 axis = border(direction).nor();
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            float from = a.x * axis.x + a.y * axis.y, to = b.x * axis.x + b.y * axis.y;
            if (along < Math.min(from, to) || along > Math.max(from, to) || Math.abs(to - from) < .001f) { continue; }
            float t = (along - from) / (to - from);
            float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
            float width = a.width + t * (b.width - a.width);
            float inner = (corridor.middle() & (1 << direction)) == 0 ? .7f : MEDIAN_HALF + .7f;
            float outward = Math.signum(to - from);
            return new StopApproach(direction, a.x + t * dx, a.y + t * dy, outward * dx / length, outward * dy / length,
                  inner, Math.max(inner, width - .7f));
        }
        return null;
    }

    /** White transverse bars cover only the incoming lanes, leaving outgoing lanes and railway medians clear. */
    Area stopLines(int exits) {
        return stopLines(stopApproaches(exits));
    }

    Area stopLines(List<StopApproach> approaches) {
        Area result = new Area();
        for (var approach : approaches) {
            result.add(stopBar(approach));
        }
        result.intersect(footprint(-.4f));
        result.subtract(medianAxis(MEDIAN_HALF + .1f));
        return result;
    }

    private static Area stopBar(StopApproach approach) {
        Path2D.Float bar = new Path2D.Float();
        bar.moveTo(approach.x - approach.ny * approach.inner, approach.y + approach.nx * approach.inner);
        bar.lineTo(approach.x - approach.ny * approach.outer, approach.y + approach.nx * approach.outer);
        return new Area(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER).createStrokedShape(bar));
    }

    /** A continuous divider channels the last road-width before a junction or railway stop boundary. */
    Area approachLines(int exits, List<StopApproach> approaches) {
        Area result = new Area();
        var courses = paintPaths();
        for (var approach : approaches) {
            int index = borders.size() < 3 ? 0 : Integer.bitCount(exits & ((1 << approach.direction()) - 1));
            if (index >= courses.size()) { continue; }
            var points = courses.get(index);
            Area solid = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                  .createStrokedShape(linePath(points)));
            solid.intersect(approach.edge().band(1.4f, 1.4f + 2 * Kind.PAVED.halfWidth));
            result.add(solid);
        }
        clipEnds(result, -3);
        result.subtract(medianAxis(MEDIAN_HALF + 1));
        return result;
    }

    /** Bends and their approaches have solid dividers; straight dashes and all paint stay clear of junction conflicts. */
    Area markings(Coords coords, int exits) {
        Area area = new Area();
        if (exits == 0) { return area; }
        if (circle != null) {
            // End the approach separator before the circulating lane. No straight-through dashes cross the island.
            int remaining = exits;
            for (int i = 1; i < paths.size(); i += 3) {
                int arm = Integer.lowestOneBit(remaining);
                remaining &= ~arm;
                if (joins.stream().anyMatch(join -> (arm & (1 << join.direction())) != 0 && join.kind() != Kind.PAVED)) {
                    continue;
                }
                Point start = paths.get(i).getFirst(), end = paths.get(i).getLast();
                float x = end.x, y = end.y;
                Path2D.Float separator = new Path2D.Float();
                separator.moveTo(x, y);
                separator.lineTo(start.x + (x - start.x) / 4, start.y + (y - start.y) / 4);
                area.add(new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                      .createStrokedShape(separator)));
            }
            float clear = circle.radius + paths.getFirst().getFirst().width + 1.75f;
            area.subtract(new Area(new Ellipse2D.Float(circle.x - clear, circle.y - clear, clear * 2, clear * 2)));
            return area;
        }
        boolean junction = Integer.bitCount(exits) > 2;
        boolean turning = turning(exits);
        int markedExits = exits;
        for (Join join : joins) {
            if (join.kind() != Kind.PAVED) { markedExits &= ~(1 << join.direction()); }
        }
        var conflicts = conflictApproaches(exits);
        int remaining = exits;
        for (var points : paintPaths()) {
            int arm = Integer.lowestOneBit(remaining);
            remaining &= ~arm;
            if (junction && (markedExits & arm) == 0) { continue; }
            Path2D.Float path = new Path2D.Float();
            path.moveTo(points.getFirst().x, points.getFirst().y);
            float along = 0;
            List<Float> anchors = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                if (i > 0) {
                    along += (float) Math.hypot(p.x - points.get(i - 1).x, p.y - points.get(i - 1).y);
                    path.lineTo(p.x, p.y);
                }
                if (borders.stream().anyMatch(border -> border.x == p.x && border.y == p.y)) { anchors.add(along); }
            }
            // Every border a road crosses sits in the middle of a gap, seen from both of its hexes, and a road
            // between two borders fits whole dashes, so the centre line continues through turns and bridges alike.
            // A junction's arm keeps that same period on its approach; the stop plane clears its conflict area.
            float span = anchors.size() > 1 ? anchors.getLast() - anchors.getFirst()
                  : anchors.isEmpty() ? 0 : 2 * anchors.getFirst();
            float period = span > 0 ? fit(span, DASH + GAP) : DASH + GAP;
            float dash = period * DASH / (DASH + GAP), anchor = anchors.isEmpty() ? 0 : anchors.getFirst();
            float phase = ((dash + (period - dash) / 2 - anchor) % period + period) % period;
            Area paint = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                  10, new float[] { dash, period - dash }, phase).createStrokedShape(path));
            if (turning || solidApproaches != 0) {
                Area solid = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
                      .createStrokedShape(path));
                if (!turning) {
                    Area approach = new Area();
                    int sides = exits;
                    for (Point border : borders) {
                        int side = Integer.lowestOneBit(sides);
                        sides &= ~side;
                        if ((solidApproaches & side) == 0 || junction && side != arm) { continue; }
                        var normal = border(Integer.numberOfTrailingZeros(side)).nor();
                        approach.add(new End(border, normal.x, normal.y).band(-2 * Kind.PAVED.halfWidth, 128));
                    }
                    solid.intersect(approach);
                }
                paint.add(solid);
            }
            if (junction) {
                int direction = Integer.numberOfTrailingZeros(arm);
                conflicts.stream().filter(stop -> stop.direction() == direction).findFirst()
                      .ifPresent(stop -> paint.intersect(stop.edge().band(1.4f, 128)));
                if (borders.size() >= 5) {
                    float clear = Kind.PAVED.halfWidth + 1.4f;
                    paint.subtract(new Area(new Ellipse2D.Float(-clear, -clear, clear * 2, clear * 2)));
                }
            }
            area.add(paint);
        }
        area.add(approachLines(exits, conflicts));
        for (Join join : joins) {
            if (junction || join.kind() == Kind.PAVED) { continue; }
            End edge = join.edge();
            area.subtract(edge.band(-8, 128, edge.point.width + 1));
        }
        clipEnds(area, -3);
        area.subtract(medianAxis(MEDIAN_HALF + 1));
        return area;
    }

    /** Restrained white circulation paint: an island edge, entry give-way markings, and a two-lane divider. */
    Area flowMarkings() {
        Area result = new Area();
        if (circle == null || corridor.sides() != 0) { return result; }
        if (circle.lanes == 2) {
            Path2D.Float divider = new Path2D.Float();
            var points = paths.getFirst();
            divider.moveTo(points.getFirst().x, points.getFirst().y);
            for (int i = 1; i < points.size(); i++) { divider.lineTo(points.get(i).x, points.get(i).y); }
            float period = fit((float) (2 * Math.PI * circle.radius), DASH + GAP);
            result.add(new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                  10, new float[] { period * DASH / (DASH + GAP), period * GAP / (DASH + GAP) }, 0)
                  .createStrokedShape(divider)));
        }
        float inner = circle.radius - paths.getFirst().getFirst().width + .7f;
        result.add(new Area(new BasicStroke(.65f).createStrokedShape(new Ellipse2D.Float(
              circle.x - inner, circle.y - inner, inner * 2, inner * 2))));
        // Each group contains the shared two-way throat, the outgoing connector, then the incoming connector.
        for (int i = 3; i < paths.size(); i += 3) {
            var lane = paths.get(i);
            StopApproach frame = yieldApproach(lane);
            if (frame == null) { continue; }
            // A straight transverse give-way line stops the whole incoming lane before the circulating road.
            Path2D.Float bar = new Path2D.Float();
            bar.moveTo(frame.x - frame.ny * frame.inner, frame.y + frame.nx * frame.inner);
            bar.lineTo(frame.x - frame.ny * frame.outer, frame.y + frame.nx * frame.outer);
            result.add(new Area(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                  10, new float[] { 1.6f, 1.2f }, 0).createStrokedShape(bar)));
            // The triangle sits upstream of the line, with its point facing approaching traffic.
            Path2D.Float triangle = new Path2D.Float();
            triangle.moveTo(frame.x + frame.nx * 8, frame.y + frame.ny * 8);
            triangle.lineTo(frame.x + frame.nx * 2 - frame.ny * 2.5f,
                  frame.y + frame.ny * 2 + frame.nx * 2.5f);
            triangle.lineTo(frame.x + frame.nx * 2 + frame.ny * 2.5f,
                  frame.y + frame.ny * 2 - frame.nx * 2.5f);
            triangle.closePath();
            result.add(new Area(new BasicStroke(.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                  .createStrokedShape(triangle)));
        }
        result.intersect(footprint(-.4f));
        return result;
    }

    /** Last full lane cross-section clear of the circulating road, including the give-way stroke's clearance. */
    private StopApproach yieldApproach(List<Point> lane) {
        float outside = circle.radius + paths.getFirst().getFirst().width + 1;
        StopApproach clear = null;
        for (int i = 1; i < lane.size(); i++) {
            Point a = lane.get(i - 1), b = lane.get(i);
            float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
            if (length < .001f) { continue; }
            int steps = Math.max(1, (int) Math.ceil(length / .25f));
            for (int step = 0; step <= steps; step++) {
                float t = (float) step / steps, x = a.x + t * dx, y = a.y + t * dy;
                float nx = -dx / length, ny = -dy / length, half = a.width + t * (b.width - a.width) - .7f;
                float across = Math.clamp(-(x - circle.x) * ny + (y - circle.y) * nx, -half, half);
                if (Math.hypot(x + across * ny - circle.x, y - across * nx - circle.y) < outside) { return clear; }
                clear = new StopApproach(-1, x, y, nx, ny, -half, half);
            }
        }
        return null;
    }

    /** Each lane's two worn wheel courses remain legible where unsealed soil matches the surrounding sand. */
    Area tracks(int exits) {
        Area result = new Area();
        for (var points : paintPaths()) {
            int[] lanes = points.stream().allMatch(p -> laneCenter(p.width) == 0) ? new int[] { 0 } : new int[] { -1, 1 };
            for (int lane : lanes) {
                for (int side : new int[] { -1, 1 }) {
                    Path2D.Float path = new Path2D.Float();
                    for (int i = 0; i < points.size(); i++) {
                        Point p = points.get(i), before = points.get(Math.max(0, i - 1));
                        Point after = points.get(Math.min(points.size() - 1, i + 1));
                        float dx = after.x - before.x, dy = after.y - before.y;
                        float length = (float) Math.hypot(dx, dy);
                        float offset = lane * laneCenter(p.width) + side * WHEEL_OFFSET;
                        float x = p.x - dy / length * offset, y = p.y + dx / length * offset;
                        if (i == 0) { path.moveTo(x, y); } else { path.lineTo(x, y); }
                    }
                    Area track = new Area(new BasicStroke(2 * TRACK_HALF_WIDTH, BasicStroke.CAP_BUTT,
                          BasicStroke.JOIN_ROUND).createStrokedShape(path));
                    clipEnds(track, side < 0 ? -2 : 0);
                    result.add(track);
                }
            }
        }
        if (Integer.bitCount(exits) > 2) { result.subtract(new Area(new Ellipse2D.Float(-10, -10, 20, 20))); }
        return result;
    }
}
