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
import megamek.common.board.SingleLane;
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
        Area area() { return edge.band(-JOIN_REACH, 128); }
        float coverage(float x, float y) {
            return Math.clamp((edge.distance(x, y) + JOIN_REACH) / (2 * JOIN_REACH), 0, 1);
        }
        float lateral(float x, float y) {
            return -(x - edge.point.x) * edge.ny + (y - edge.point.y) * edge.nx;
        }
        /** How far a point lies past the join's border, along its arm: negative short of it. */
        float past(float x, float y) { return edge.distance(x, y); }
    }

    static final float SHOULDER = 1.5f;
    /** A single, one-way lane's half-width (SingleLane): one lane of a two-lane carriageway, as a roundabout's ring. */
    static final float SINGLE_HALF = HexTile.ROAD_HALF_WIDTH / 2;
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
    /** The courses' sections, derived once and shared by every worker that grades or paints them; see {@link #sections()}. */
    private volatile float[][] sections;
    private RailRoute.Corridor corridor = RailRoute.Corridor.NONE;
    private Area median;
    /** Connected bends requiring a solid divider on this road's immediate approaches. */
    private int solidApproaches;
    /** Roadless concrete entrances do not count as road junction arms, including sloped apron mouths. */
    private int apronExits;
    /** Fitted roadless aprons and one lane plus verge of their entrance, in this road's local coordinates. */
    private Area concreteEntrances = new Area();
    private HexAppearance.TrafficControl control = HexAppearance.TrafficControl.STOPS;
    /**
     * The arms at a single lane's width ({@link #SINGLE_HALF}): every arm of a single-lane road but one meeting a bridge
     * deck, which keeps the deck's two lanes, and a two-lane road's arms where a single-lane road joins it
     * ({@link #lanes}).
     */
    private int single;
    /** Whether this hex's own road is a single lane, its hub as narrow as its lane. */
    private boolean narrow;
    /**
     * The turns this two-lane road closes, into its exits from the approaches that may not take them, as bit
     * {@code 6 * a + b} for sides {@code a, b} both ways round (SingleLane#closedTurns): it reserves no turn between them.
     */
    private long closed;
    /** Of the single arms, those by which traffic enters this hex: a two-lane road's entrances (on-ramps). */
    private int inbound;
    /** Sides where this road meets one of the other family at its level, paved against loose (#meet). */
    private int meetings;
    /** Of those, the sides where a paved road ends square on its border: a construction edge (#meet). */
    private int squared;
    /** Sides where a loose road runs on into another's hex, whose own coat draws the track there (#meet). */
    private int tracked;
    /** The paved roads a loose one meets. */
    private List<BoardRoad> paved = List.of();
    /** A gravel road's courses (#courses), derived once, and which of their ends run on (#pathEnds). */
    private volatile List<List<Point>> courses;
    private volatile byte[] courseEnds;
    /** A dirt track's wheel courses (#wheels), derived once. */
    private volatile Wheels wheels;

    /** What a road's course needs of one hex, whether from a scene tile or a captured hex. */
    record Node(int exits, int elevation, boolean plain, boolean circulating) {
        static Node of(BoardScene.Tile tile) {
            if (tile == null) { return null; }
            boolean plain = rendered(tile) && tile.corridor().sides() == 0
                  && tile.features().stream().noneMatch(feature -> feature.asset().equals("bridge"));
            return new Node(tile.roadExits() & 63, tile.elevation(), plain,
                  plain && tile.road() == Kind.PAVED && tile.railExits() == 0 && !singleLane(tile));
        }

        static Node of(Hex hex) {
            if (hex == null) { return null; }
            int exits = hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0;
            boolean plain = capture(hex) != Kind.NONE && !HexTile.liquid(hex) && !hex.containsTerrain(Terrains.BRIDGE)
                  && !hex.containsTerrain(Terrains.ULTRA_SUBLEVEL);
            return new Node(exits, hex.getLevel(), plain,
                  plain && capture(hex) == Kind.PAVED && RailRoute.groundSides(hex) == 0 && !SingleLane.single(hex));
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
        int single = singleArms(scene, tile);
        long closed = single == 0 || singleLane(tile) ? 0 : SingleLane.closedTurns(lanes(scene), tile.coords());
        var road = layout(tile.coords(), tile.roadExits(), tile.road(), direction -> {
            var neighbor = scene.tile(tile.coords().translated(direction));
            // A road meeting a deck joins the deck's road where it has that side; a clean side keeps the road's own kind.
            if (neighbor != null && BoardSurface.connectingBridge(tile, neighbor, direction) != null) {
                return BoardBridge.deckRoad(neighbor, (direction + 3) % 6);
            }
            return neighbor != null && (neighbor.roadExits() & (1 << ((direction + 3) % 6))) != 0
                  ? neighbor.road() : Kind.NONE;
        }, bends(tile.coords(), nodes), true,
              climbingTurn(tile.roadExits(), BoardRoadGrade.ramps(scene, tile)), single, singleLane(tile), closed)
              .flow(inbound(scene, tile, single)).circulate(nodes);
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

    static boolean paved(Kind kind) { return kind == Kind.PAVED || kind == Kind.ALLEY; }

    /** Whether a tile's ground road is a single, one-way lane (SingleLane#single): a paved road narrowed to one. */
    static boolean singleLane(BoardScene.Tile tile) {
        return tile != null && tile.road() == Kind.PAVED && HexAppearance.singleLane(tile.appearance(), SingleLane.OWNER)
              && tile.railExits() == 0;
    }

    /**
     * The arms of a tile's ground road at a single lane's width ({@link #single}): all of a single-lane road's but those
     * meeting a bridge deck or a dirt or gravel road, which keep their two lanes' width, and a paved two-lane road's where
     * a single-lane road joins it; both hexes of a border agree. Captured hexes get the same from
     * BoardFeatures#singleArms.
     */
    static int singleArms(BoardScene scene, BoardScene.Tile tile) {
        if (!paved(tile.road())) { return 0; }
        boolean narrow = singleLane(tile);
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if ((tile.roadExits() & 1 << d) == 0) { continue; }
            var next = scene.tile(tile.coords().translated(d));
            if (next != null && BoardSurface.connectingBridge(tile, next, d) != null) { continue; }
            boolean joined = next != null && (next.roadExits() & 1 << (d + 3) % 6) != 0;
            if (narrow && joined && !paved(next.road()) && next.road() != Kind.NONE) { continue; }
            if (narrow || singleLane(next) && joined) { result |= 1 << d; }
        }
        return result;
    }

    /**
     * The sides by which traffic enters a tile's road along its single arms: a single-lane road's direction
     * (SingleLane#inbound), a two-lane road's entrances.
     */
    static int inbound(BoardScene scene, BoardScene.Tile tile, int single) {
        var lanes = lanes(scene);
        // A single lane's every arm runs its way, those at another road's width too.
        if (singleLane(tile)) { return SingleLane.inbound(lanes, tile.coords()); }
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if ((single & 1 << d) != 0 && SingleLane.entrance(lanes, tile.coords(), d)) { result |= 1 << d; }
        }
        return result;
    }

    /** The scene's tiles as the single-lane rules read them, as BoardFeatures reads the board's hexes (SingleLane#view). */
    static SingleLane.View lanes(BoardScene scene) {
        return new SingleLane.View() {
            @Override
            public boolean single(Coords at) { return singleLane(scene.tile(at)); }

            @Override
            public int exits(Coords at) {
                var tile = scene.tile(at);
                return tile == null || tile.road() == Kind.NONE ? 0 : tile.roadExits() & 63;
            }

            @Override
            public Integer stored(Coords at) {
                var tile = scene.tile(at);
                return tile == null ? null : HexAppearance.inbound(tile.appearance(), SingleLane.OWNER);
            }

            @Override
            public Long exitFrom(Coords at) {
                var tile = scene.tile(at);
                return tile == null ? null : HexAppearance.exitFrom(tile.appearance(), SingleLane.OWNER);
            }
        };
    }

    /**
     * This road with its direction of travel: the sides by which traffic enters it, all of a single lane's and, on a two-lane
     * road, those of its single arms (its entrances).
     */
    BoardRoad flow(int value) { inbound = (narrow ? value : value & single) & 63; return this; }

    /** The arms at a single lane's width. */
    int single() { return single; }

    /** The arms traffic enters by: a single lane's way in, of a two-lane road's {@link #single} arms its entrances. */
    int inbound() { return inbound; }

    /** Whether this hex's own road is a single lane. */
    boolean narrow() { return narrow; }

    /** Whether traffic may not turn from side {@code from} into side {@code to}, or back (SingleLane#closedTurns). */
    boolean closed(int from, int to) { return (closed & 1L << 6 * from + to) != 0; }

    /** The turns closed ({@link #closed(int, int)}), as bit {@code 6 * from + to}. */
    long closed() { return closed; }

    /**
     * Which way traffic from the approach across side {@code from} goes into the arm across side {@code to}: 1 for a left
     * turn, across the oncoming lanes of right-hand traffic (sides number clockwise, so the next one or two sides round
     * from the approach lie to its left), 0 for straight on, into the opposite side, and -1 for a right turn.
     */
    static int turn(int from, int to) {
        int round = Math.floorMod(to - from, 6);
        return round == 3 ? 0 : round < 3 ? 1 : -1;
    }

    /** Whether a turn from the approach across side {@code from} into the arm across side {@code to} is a left turn ({@link #turn}). */
    static boolean leftTurn(int from, int to) { return turn(from, to) > 0; }

    /** Copies the single-lane state onto a road derived from this one. */
    private BoardRoad lanes(BoardRoad from) {
        single = from.single;
        narrow = from.narrow;
        inbound = from.inbound;
        closed = from.closed;
        return this;
    }

    /**
     * Where a loose road (dirt or gravel) meets a paved one at its level, the paved road's carriageway ends square on
     * their border, broken up there over gravel as a bridge landing ends (GpuRoads#painted), and the loose one runs on a
     * little over it (GpuRoads#runOn), its wheels' lanes merging into the paved road's (#merged). Two arms of a loose road
     * through adjacent borders into one paved road each meet it on their own, with the ground between them left as it is.
     */
    private BoardRoad meet(BoardScene scene, BoardScene.Tile tile) {
        boolean loose = !paved(tile.road());
        List<BoardRoad> roads = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            var next = scene.neighbor(tile, d);
            if ((tile.roadExits() & 1 << d) == 0 || next == null) { continue; }
            if (BoardSurface.connectingBridge(tile, next, d) != null
                  || BoardSurface.connectingBridge(next, tile, (d + 3) % 6) != null) { continue; }
            if (next.elevation() != tile.elevation() || !rendered(next) || (next.roadExits() & 1 << (d + 3) % 6) == 0) {
                continue;
            }
            if (loose && !paved(next.road())) { tracked |= 1 << d; }
            if (paved(next.road()) == loose) {
                meetings |= 1 << d;
                if (loose) { roads.add(of(scene, next)); }
            }
        }
        paved = List.copyOf(roads);
        squared = loose ? 0 : meetings;
        outlines.clear();
        return this;
    }

    /** Whether this road meets a road of the other family through the join's side (#meet). */
    boolean meets(Join join) { return (meetings & 1 << join.direction()) != 0; }

    /**
     * How much of a loose road lies at a point on its run-on past a border where it meets a paved road (#meet): whole up
     * to the border, wearing out over {@code reach} past it along the arm, of those it lies past, whose course lies
     * nearest; 1 elsewhere.
     */
    float runOn(float x, float y, float reach) {
        Join arm = null;
        for (Join join : joins) {
            if (meets(join) && join.past(x, y) > 0
                  && (arm == null || Math.abs(join.lateral(x, y)) < Math.abs(arm.lateral(x, y)))) {
                arm = join;
            }
        }
        return arm == null ? 1 : Math.clamp(1 - arm.past(x, y) / reach, 0, 1);
    }

    /**
     * The ground past the borders where this road meets the other family: {@code reach} along each arm, {@code half}
     * either side of it.
     */
    Area pastMeetings(float reach, float half) {
        Area result = new Area();
        for (Join join : joins) {
            if (meets(join)) { result.add(join.edge().band(0, reach, half)); }
        }
        return result;
    }

    /** Whether a loose road runs on through the join's side into another loose road's hex at its level (#trackedHexes). */
    boolean tracked(Join join) { return (tracked & 1 << join.direction()) != 0; }

    /**
     * The hexes, in this road's tile pixels, of the loose roads (dirt or gravel) it runs on into (#meet). Each one's own
     * coat draws the track over its hex, measured from its own course; this one's paint stops at their border, so the
     * two never overlap with two measures of where across the road a point lies.
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
     * narrower one stops short of their square ends too.
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
     * How near a point lies to a wheel path of the paved roads a loose one meets (#meet), where their wear places them
     * (#wearLane, terrain-road.frag asphaltWear): 1 on one, fading out a third of a metre beside it.
     */
    float pavedWheels(float x, float y) {
        float result = 0;
        for (BoardRoad road : paved) {
            float dx = (BoardGeometry.centerX(road.coords) - BoardGeometry.centerX(coords)) / BoardGeometry.hexScale();
            float dy = (BoardGeometry.centerY(road.coords) - BoardGeometry.centerY(coords)) / BoardGeometry.hexScale();
            float off = Math.abs(road.wearLane(x - dx, y - dy) - WHEEL_OFFSET / (Kind.PAVED.halfWidth / 2));
            result = Math.max(result, 1 - BoardRelief.smooth((off - .07f) / .13f));
        }
        return result;
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
            return result.lanes(this);
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
        return result.lanes(this);
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
                    .findFirst().orElse(Kind.PAVED), direction -> null, false, false, single, narrow, closed);
        hub.inbound = inbound;
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
        // A single lane and a ramp's arm are cleared as wide as they are drawn (BoardFeatures#singleArms).
        int single = BoardFeatures.singleArms(hex, coords, board, exits);
        long closed = single == 0 || SingleLane.single(hex) ? 0 : SingleLane.closedTurns(hex, coords, board);
        return layout(coords, exits, Kind.PAVED, direction -> Kind.PAVED, bends(coords, nodes), true,
              climbingTurn(exits, ramps), single, SingleLane.single(hex), closed).circulate(nodes);
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
        return layout(coords, exits, kind, neighbors, bends, circulation, radial, 0, false, 0);
    }

    /** As {@link #layout(Coords, int, Kind, IntFunction)} with the {@link #single} arms of a road with lanes. */
    static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors, int single, boolean narrow) {
        return layout(coords, exits, kind, neighbors, single, narrow, 0);
    }

    /** As {@link #layout(Coords, int, Kind, IntFunction, int, boolean)}, reserving none of the {@code closed} turns. */
    static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors, int single, boolean narrow,
          long closed) {
        return layout(coords, exits, kind, neighbors, direction -> null, true, false, single, narrow, closed);
    }

    /**
     * {@code single} arms are a single lane wide ({@link #SINGLE_HALF}) at their border, where both hexes agree on it; a
     * {@code narrow} (single-lane) road's hub is too, while a two-lane road's widens to its two lanes on the way in.
     * Neither forms a roundabout. A junction reserves no turn between a pair of its sides that traffic may not take
     * ({@code closed}, SingleLane#closedTurns): an exit leaves it only towards the approaches that may take it.
     */
    private static BoardRoad layout(Coords coords, int exits, Kind kind, IntFunction<Kind> neighbors,
          IntFunction<Vector2> bends, boolean circulation, boolean radial, int single, boolean narrow, long closed) {
        single &= exits & 63;
        float hub = narrow ? SINGLE_HALF : kind.halfWidth;
        List<Point> ends = new ArrayList<>();
        List<Vector2> directions = new ArrayList<>();
        List<Integer> sides = new ArrayList<>();
        List<Join> joins = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & (1 << direction)) == 0) { continue; }
            Vector2 middle = border(direction);
            float x = middle.x, y = middle.y;
            Kind neighbor = neighbors.apply(direction);
            // A single lane's other arms (to a bridge deck or a loose road) meet their neighbour at its full width.
            float width = (single & 1 << direction) != 0 ? SINGLE_HALF
                  : neighbor == Kind.NONE ? hub : (kind.halfWidth + neighbor.halfWidth) / 2;
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
        Circle circle = circulation && single == 0 && !narrow && roundabout(exits)
              ? new Circle(0, 0, ROUNDABOUT_RADIUS, 1, coords) : null;
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
            // A single arm keeps its lane until the course turns; a two-lane hex narrows between its arms.
            Point from = approach(path, ends.get(0), directions.get(0), (single & 1 << sides.get(0)) != 0 ? SINGLE_HALF : hub);
            Point to = approach(tail, ends.get(1), directions.get(1), (single & 1 << sides.get(1)) != 0 ? SINGLE_HALF : hub);
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
            line(path, new Point(0, -HexTile.ROAD_END_REACH * radius, hub),
                  new Point(0, HexTile.ROAD_END_REACH * radius, hub));
            paths.add(path);
            terminals.add(new End(path.getFirst(), 0, -1));
            terminals.add(new End(path.getLast(), 0, 1));
        } else {
            for (int e = 0; e < ends.size(); e++) {
                Point end = ends.get(e);
                // A ramp's arm keeps its lane up to the two-lane hub, which it meets with rounded corners.
                float lane = (single & 1 << sides.get(e)) != 0 ? SINGLE_HALF : hub;
                List<Point> path = new ArrayList<>();
                if (ends.size() == 1) {
                    // A dead end occupies most of its tile, but never invents an exit into the opposite hex.
                    float[] stop = RailRoute.roadEnd(sides.getFirst());
                    Point tip = new Point(stop[0], stop[1], hub);
                    line(path, tip, new Point(0, 0, hub));
                    float length = (float) Math.hypot(end.x, end.y);
                    terminals.add(new End(tip, -end.x / length, -end.y / length));
                }
                List<Point> arm = new ArrayList<>();
                approach(arm, end, null, lane);
                line(path, new Point(0, 0, hub), arm.getLast());
                path.addAll(arm.reversed().subList(1, arm.size()));
                paths.add(path);
            }
        }
        if (circle == null && ends.size() >= 3) {
            // Keep the radial approaches for paint and wear, but reserve each legal broad turn at full width.
            // An exit-only bridge must also carry explicit rail layouts, not just the default junction pieces.
            for (int a = 0; a < ends.size(); a++) {
                for (int b = a + 1; b < ends.size(); b++) {
                    if ((closed & 1L << 6 * sides.get(a) + sides.get(b)) != 0) { continue; }
                    if (RailRoute.piece(sides.get(a), sides.get(b)) >= 3) {
                        paths.add(course(ends.get(a), ends.get(b)));
                    } else if (Math.floorMod(sides.get(a) - sides.get(b), 6) == 1
                          || Math.floorMod(sides.get(b) - sides.get(a), 6) == 1) {
                        // Round the tight corners of a crossing too, without forcing its hex exits onto square axes.
                        // These short turn envelopes share the same distance/clearance calculation as every road.
                        // A single arm's corner keeps to its lane, widening into the two-lane hub.
                        float wa = (single & 1 << sides.get(a)) != 0 ? SINGLE_HALF : hub;
                        float wb = (single & 1 << sides.get(b)) != 0 ? SINGLE_HALF : hub;
                        Point from = scale(ends.get(a), .64f, wa);
                        Point to = scale(ends.get(b), .64f, wb);
                        List<Point> turn = new ArrayList<>(List.of(from));
                        curve(turn, from, scale(from, 1f / 3, hub), scale(to, 1f / 3, hub), to);
                        if (wa != wb) {
                            // From the ramp's lane to the two-lane arm's width along the corner, not at its first step.
                            for (int i = 1; i < turn.size(); i++) {
                                Point p = turn.get(i);
                                turn.set(i, new Point(p.x, p.y, wa + (wb - wa) * i / (turn.size() - 1)));
                            }
                        }
                        paths.add(turn);
                    }
                }
            }
        }
        var result = new BoardRoad(coords, paths, List.copyOf(ends), terminals, joins, circle);
        result.single = single;
        result.narrow = narrow;
        result.closed = closed;
        return result;
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
    private static Point approach(List<Point> path, Point end, Vector2 bend, float hub) {
        if (bend == null) {
            line(path, scale(end, 1.06f, end.width), end);
            line(path, end, scale(end, .9f, end.width));
            line(path, scale(end, .9f, end.width), scale(end, CORRIDOR, hub));
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
        return result.lanes(this);
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
        result.paved = paved;
        return result.lanes(this);
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
        clipEnds(area, margin);
        for (Join join : joins) {
            // Every outline stops on the border: a verge never lays a strip across the square end.
            if ((squared & 1 << join.direction()) != 0) { area.subtract(square(join, 0)); }
        }
        return area;
    }

    /** Paint can cross inactive hex edges, but each connected approach owns only its side of the mouth. */
    void clipPaint(Area area) { clipEnds(area, 0); }

    /**
     * Clip only real termini, past each its arm's width (#endReach): curves, the shared hub of a junction and a junction's
     * other arms keep their continuous joins.
     */
    private void clipEnds(Area area, float margin) {
        for (End end : ends) {
            var bounds = area.getBounds2D();
            // Bridge footings and their aprons can reach farther than a tile-sized clipping rectangle.
            float reach = (float) (Math.hypot(bounds.getWidth(), bounds.getHeight()) / 2
                  + Math.hypot(bounds.getCenterX() - end.point.x, bounds.getCenterY() - end.point.y) + 1);
            Area past = end.band(margin, reach, endReach(end));
            past.intersect(arm(end));
            area.subtract(past);
        }
    }

    /** The ground within {@code length} before each terminus, across its arm (#endReach). */
    Area endZone(float length) {
        Area result = new Area();
        if (length > 0) {
            for (End end : ends) {
                Area zone = end.band(-length, SHOULDER, endReach(end));
                zone.intersect(arm(end));
                result.add(zone);
            }
        }
        return result;
    }

    /** How far either side of its course a terminus's arm reaches: its way and the widest landing apron (GpuRoads#bare). */
    private static float endReach(End end) { return end.point.width + 3 * SHOULDER + 3; }

    /**
     * The ground nearer a terminus's arm than any other arm of this road (#borders): the wedge between the bisectors with
     * its neighbours, so that a junction's other arms keep their own ground up to the end of this one.
     */
    private Area arm(End end) {
        double heading = Math.atan2(end.ny, end.nx), below = -Math.PI, above = Math.PI;
        for (Point border : borders) {
            double turn = Math.atan2(border.y, border.x) - heading;
            turn = Math.atan2(Math.sin(turn), Math.cos(turn));
            // The arm's own border, to the bend of its course.
            if (Math.abs(turn) < .2) { continue; }
            if (turn > 0) { above = Math.min(above, turn); } else { below = Math.max(below, turn); }
        }
        Path2D.Float wedge = new Path2D.Float();
        wedge.moveTo(0, 0);
        for (int i = 0; i <= 8; i++) {
            double angle = heading + (below + (above - below) * i / 8) / 2;
            wedge.lineTo(1024 * (float) Math.cos(angle), 1024 * (float) Math.sin(angle));
        }
        wedge.closePath();
        return new Area(wedge);
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

    /**
     * How far a paved road's traffic has merged into the single lane of a loose road it meets (#meet): 1 on their
     * border, where both its lanes' wheel paths run on in the loose road's, easing to 0 MERGE back along the arm.
     */
    private float merged(float x, float y) {
        float result = 0;
        for (Join join : joins) {
            if ((squared & 1 << join.direction()) == 0
                  || Math.abs(join.lateral(x, y)) > join.edge().point().width + SHOULDER + 3) { continue; }
            result = Math.max(result, BoardRelief.smooth(1 + join.past(x, y) / MERGE));
        }
        return result;
    }

    /**
     * Where a point lies across its lane for paved wear (terrain-road.frag): 0 on the lane's centre line, 1 at its edge,
     * a two-way road's middle or kerb, so its wheel paths lie at WHEEL_OFFSET of that.
     */
    float wearLane(float x, float y) {
        return wearLane(pathSample(x, y, paintPaths(), ALL_ENDS), merged(x, y));
    }

    private float wearLane(PathSample nearest, float merged) {
        // Where the lanes merge, the asphalt beside the one lane lies untrodden, short of the kerb's grit.
        float offset = Math.abs(nearest.distance() - laneCenter(nearest.width()) * (1 - merged));
        return Math.min(1 - .2f * merged, offset / (Kind.PAVED.halfWidth / 2));
    }

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
     * A gravel road's track at a point (terrain-road.frag), from its nearest course (#courses), so a junction's turns
     * grade its corners: the heading, and where across the road the point lies, 0 on that course and 1 at a loose road's
     * full half width with its shoulder, in the same metres on every course.
     */
    record Track(Vector2 heading, float across) { }

    Track track(float x, float y) {
        // Past a border the road runs on straight, as the neighbour's measures it up to there, never round an end.
        PathSample nearest = pathSample(x, y, courses(), courseEnds(), BORDER_ENDS, -1);
        return new Track(new Vector2(nearest.dx(), nearest.dy()).nor(), Math.min(1, nearest.distance() / TRACK_REACH));
    }

    /** {@link #track}'s whole width: a loose road's half width with its shoulder. */
    static final float TRACK_REACH = Kind.DIRT.halfWidth + SHOULDER;

    /** Tile pixels per metre. */
    private static final float METRE = BoardGeometry.TILE_WIDTH / 30;
    /** How far back from a loose road a paved arm's lanes start to close in on its one (#merged). */
    private static final float MERGE = 12 * METRE;
    /** A dirt track's wheel ruts lie this far either side of each of its courses (#ruts): a farm vehicle's half gauge. */
    static final float RUT_OFFSET = .9f * METRE;
    /** How far past a rut's centre line {@link #ruts} still measures: its loose soil lies within it. */
    static final float RUT_REACH = 2 * METRE;
    /** How far from a rut's centre line a dirt track keeps its ground bare of grass (#grass): the rut and its lip. */
    private static final float RUT_BARE = .65f * METRE;
    /** Over how much farther its grass thickens back to the meadow's (#grass): beside the ruts, and on their crown. */
    private static final float RUT_THIN = METRE, CROWN_THIN = .25f * METRE;
    /** A gravel road's graded edge either side of its course (terrain-road.frag GRAVEL_EDGE), where its grass begins. */
    private static final float GRAVEL_EDGE = 3.4f * METRE;

    /** A dirt track's track, its half width about each of its courses: its ruts with their loose soil. */
    private static final float TRACK_HALF = RUT_OFFSET + RUT_REACH;

    /**
     * The ground this carriageway ({@link #way}) is levelled and cleared for (BoardRoadGrade, BoardRelief): where its
     * bed is a loose road's track ({@link #trackBed}), its courses only as wide as that track ({@link #TRACK_HALF}, a
     * gravel road's {@link #GRAVEL_EDGE}), ends as they are, so the slopes beside a dirt or gravel road stand back from
     * its track, not from the paved width its paint's coordinates span; else this road.
     */
    BoardRoad terrainBed(BoardScene scene, BoardScene.Tile tile) {
        if (!trackBed(scene, tile)) { return this; }
        float half = tile.road() == Kind.GRAVEL ? GRAVEL_EDGE : TRACK_HALF;
        List<List<Point>> narrow = paths.stream()
              .map(path -> path.stream().map(point -> new Point(point.x, point.y, half)).toList()).toList();
        BoardRoad bed = new BoardRoad(coords, narrow, borders, ends, joins, circle);
        bed.squared = squared;
        return bed.lanes(this);
    }

    /**
     * Whether a hex's carriageway is levelled only as wide as its loose track ({@link #terrainBed}): a dirt or gravel
     * road's, but not beside rails, not where a bridge's approach meets its parapets or an apron's lane its concrete,
     * and not where a road of another kind is reached through a full-width transition coat (GpuRoads#patches): one
     * that does not meet it on the same dry ground (#meet). No containment wall retains a slope beside one.
     */
    static boolean trackBed(BoardScene scene, BoardScene.Tile tile) {
        if (tile.road() == Kind.NONE || paved(tile.road()) || tile.railExits() != 0 || tile.corridor().sides() != 0) {
            return false;
        }
        for (int d = 0; d < 6; d++) {
            var next = (tile.roadExits() & 1 << d) == 0 ? null : scene.neighbor(tile, d);
            if (next == null) { continue; }
            if (BoardSurface.connectingBridge(tile, next, d) != null
                  || BoardSurface.connectingBridge(next, tile, (d + 3) % 6) != null
                  || BoardRoadGrade.apron(tile, next, d)
                  || next.road() != tile.road() && (next.roadExits() & 1 << (d + 3) % 6) != 0
                        && (next.elevation() != tile.elevation() || !rendered(next))) {
                return false;
            }
        }
        return true;
    }

    /** Whether this road's arms meet in an open junction: not a roundabout, nor arms moved beside a rail's corridor. */
    private boolean openJunction() {
        return circle == null && borders.size() >= 3 && corridor == RailRoute.Corridor.NONE;
    }

    /**
     * A gravel road's courses (#track): its paths, but at an open junction traffic drives from every arm to every other,
     * each pair on its own course (#turn), so its turns grade the junction's corners.
     */
    private List<List<Point>> courses() {
        var result = courses;
        if (result != null) { return result; }
        if (!openJunction()) {
            result = paths;
        } else {
            result = new ArrayList<>();
            for (int a = 0; a < borders.size(); a++) {
                for (int b = a + 1; b < borders.size(); b++) { result.add(turn(borders.get(a), borders.get(b))); }
            }
            result = List.copyOf(result);
        }
        courses = result;
        return result;
    }

    /**
     * A course from one arm to another, from past one border to past the other: straight across, or along one arm and
     * round a farm vehicle's turn into the other, wider where it turns less.
     */
    private List<Point> turn(Point from, Point to) {
        Vector2 u = new Vector2(from.x, from.y).nor(), v = new Vector2(to.x, to.y).nor();
        float cos = u.dot(v);
        List<Point> course = new ArrayList<>();
        line(course, beyond(from), from);
        if (cos > -.9f) {
            // A circular fillet tangent to both arms, whose turn is the angle between them taken from a straight
            // crossing: a broad one round 9 m, a sharp one round 5 m.
            float radius = (cos < 0 ? 9 : 5) * METRE, between = (float) Math.acos(cos);
            float tangent = radius / (float) Math.tan(between / 2);
            float control = 4f / 3 * (float) Math.tan((Math.PI - between) / 4) * radius;
            Point start = new Point(u.x * tangent, u.y * tangent, from.width);
            Point end = new Point(v.x * tangent, v.y * tangent, to.width);
            line(course, from, start);
            curve(course, start, new Point(u.x * (tangent - control), u.y * (tangent - control), from.width),
                  new Point(v.x * (tangent - control), v.y * (tangent - control), to.width), end);
        }
        line(course, course.getLast(), to);
        line(course, to, beyond(to));
        return List.copyOf(course);
    }

    /** A dirt track's wheel courses (#wheels): the first {@code pairs} each a pair of ruts, the rest lone ruts. */
    private record Wheels(List<List<Point>> courses, int pairs, byte[] ends, float[] boxes) {
        Wheels(List<List<Point>> courses, int pairs, byte[] ends) { this(courses, pairs, ends, boxes(courses)); }

        /** Each course's bounding box: min x, min y, max x, max y. */
        private static float[] boxes(List<List<Point>> courses) {
            float[] result = new float[4 * courses.size()];
            for (int p = 0; p < courses.size(); p++) {
                float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
                for (Point point : courses.get(p)) {
                    minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
                    maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
                }
                result[4 * p] = minX; result[4 * p + 1] = minY; result[4 * p + 2] = maxX; result[4 * p + 3] = maxY;
            }
            return result;
        }

        /**
         * No point of course {@code p} lies nearer (x, y) than this: its bounding box, or a border end's straight run on
         * past it (#pathSample).
         */
        float nearest(int p, float x, float y) {
            float dx = Math.max(0, Math.max(boxes[4 * p] - x, x - boxes[4 * p + 2]));
            float dy = Math.max(0, Math.max(boxes[4 * p + 1] - y, y - boxes[4 * p + 3]));
            float result = (float) Math.sqrt(dx * dx + dy * dy);
            var course = courses.get(p);
            if ((ends[p] & BORDER_ENDS) != 0) { result = Math.min(result, ray(course.get(0), course.get(1), x, y)); }
            if ((ends[p] & BORDER_ENDS << 1) != 0) {
                result = Math.min(result, ray(course.getLast(), course.get(course.size() - 2), x, y));
            }
            return result;
        }

        /** How far (x, y) lies from the ray from {@code from} away from {@code toward}. */
        private static float ray(Point from, Point toward, float x, float y) {
            float ux = from.x - toward.x, uy = from.y - toward.y, length = ux * ux + uy * uy;
            float t = length == 0 ? 0 : Math.max(0, ((x - from.x) * ux + (y - from.y) * uy) / length);
            float ex = x - from.x - t * ux, ey = y - from.y - t * uy;
            return (float) Math.sqrt(ex * ex + ey * ey);
        }
    }

    /**
     * A dirt track's wheel courses (#ruts): its paths, each a pair of ruts. At an open junction its tracks run straight
     * on across it, or bend through it where all its other arms lie to one side; an arm that does neither runs into its
     * scuffed middle (#scuff). Between neighbouring arms the inner wheels round the corner in one rut. Other turns cross
     * the middle, as on a real farm track, rather than each wearing a track of its own.
     */
    private Wheels wheels() {
        var result = wheels;
        if (result != null) { return result; }
        if (!openJunction()) {
            result = new Wheels(paths, paths.size(), pathEnds());
        } else {
            List<Point> arms = new ArrayList<>(borders);
            arms.sort(Comparator.comparingDouble(p -> Math.atan2(p.y, p.x)));
            List<List<Point>> pairs = new ArrayList<>(), corners = new ArrayList<>();
            boolean[] carried = new boolean[arms.size()];
            for (int a = 0; a < arms.size(); a++) {
                for (int b = a + 1; b < arms.size(); b++) {
                    Point from = arms.get(a), to = arms.get(b);
                    if (new Vector2(from.x, from.y).nor().dot(new Vector2(to.x, to.y).nor()) < -.9f) {
                        pairs.add(turn(from, to));
                        carried[a] = carried[b] = true;
                    }
                }
            }
            for (int a = 0; a < arms.size(); a++) {
                int b = (a + 1) % arms.size();
                Point from = arms.get(a), to = arms.get(b);
                // Counterclockwise from this arm to the next: the corner between them, or the open side of a bend.
                double gap = Math.atan2(to.y, to.x) - Math.atan2(from.y, from.x);
                if (gap <= 0) { gap += 2 * Math.PI; }
                if (gap < Math.PI - .45) {
                    corners.add(corner(from, to));
                } else if (gap > Math.PI + .45) {
                    pairs.add(turn(from, to));
                    carried[a] = carried[b] = true;
                }
            }
            for (int a = 0; a < arms.size(); a++) {
                if (carried[a]) { continue; }
                List<Point> stub = new ArrayList<>();
                line(stub, beyond(arms.get(a)), arms.get(a));
                line(stub, arms.get(a), new Point(0, 0, arms.get(a).width));
                pairs.add(List.copyOf(stub));
            }
            List<List<Point>> courses = new ArrayList<>(pairs);
            courses.addAll(corners);
            result = new Wheels(List.copyOf(courses), pairs.size(), openEnds(courses));
        }
        wheels = result;
        return result;
    }

    /** How sharply the inner wheels round a dirt junction's corner (#corner): the radius of their rut. */
    private static final float CORNER = 3.5f * METRE;

    /**
     * The inner wheels' rut round the corner from one arm counterclockwise to the next: along each arm RUT_OFFSET inside
     * its course, from past its border, and round a circular fillet of CORNER between them.
     */
    private List<Point> corner(Point from, Point to) {
        Vector2 u = new Vector2(from.x, from.y).nor(), v = new Vector2(to.x, to.y).nor();
        float half = (float) Math.acos(Math.clamp(u.dot(v), -1, 1)) / 2;
        // Where the two arms' inner ruts meet, on the corner's bisector, and how far each lies from its arm.
        Vector2 meet = new Vector2(u).add(v).nor().scl(RUT_OFFSET / (float) Math.sin(half));
        Vector2 inA = new Vector2(meet).mulAdd(u, -meet.dot(u)), inB = new Vector2(meet).mulAdd(v, -meet.dot(v));
        float tangent = CORNER / (float) Math.tan(half);
        float control = 4f / 3 * (float) Math.tan((Math.PI - 2 * half) / 4) * CORNER;
        Point pastFrom = beyond(from), pastTo = beyond(to);
        List<Point> rut = new ArrayList<>();
        line(rut, new Point(pastFrom.x + inA.x, pastFrom.y + inA.y, from.width),
              new Point(from.x + inA.x, from.y + inA.y, from.width));
        Point start = new Point(meet.x + u.x * tangent, meet.y + u.y * tangent, from.width);
        Point end = new Point(meet.x + v.x * tangent, meet.y + v.y * tangent, to.width);
        line(rut, rut.getLast(), start);
        curve(rut, start, new Point(start.x - u.x * control, start.y - u.y * control, from.width),
              new Point(end.x - v.x * control, end.y - v.y * control, to.width), end);
        line(rut, end, new Point(to.x + inB.x, to.y + inB.y, to.width));
        line(rut, rut.getLast(), new Point(pastTo.x + inB.x, pastTo.y + inB.y, to.width));
        return List.copyOf(rut);
    }

    /** Where this road's paths end past the border at {@code border}: as far as an extension (#extended) carries them. */
    private Point beyond(Point border) {
        Point result = scale(border, 1.06f, border.width);
        float length = (float) Math.hypot(border.x, border.y), reach = 1.06f * length;
        for (var path : paths) {
            for (Point p : List.of(path.getFirst(), path.getLast())) {
                float along = (p.x * border.x + p.y * border.y) / length;
                if (along > reach && Math.abs(p.x * border.y - p.y * border.x) / length < 1) { result = p; reach = along; }
            }
        }
        return result;
    }

    private byte[] courseEnds() {
        byte[] result = courseEnds;
        if (result == null) { courseEnds = result = courses() == paths ? pathEnds() : openEnds(courses()); }
        return result;
    }

    /**
     * A dirt track's ruts at a point (terrain-road.frag): the heading of the rut that lies nearest (#wheels), and B, the
     * signed distance from that rut's centre line, negative between a course's two ruts: 0 at RUT_OFFSET inside it, 1 at
     * RUT_REACH outside. Every rut counts, so where they cross or part, so do their ruts' soil and grass. In a junction's
     * scuffed middle the distance shrinks, so its ruts run together into one worn patch (#scuff). Past a border a course
     * runs on straight, as the neighbour measures it up to there.
     */
    Track ruts(float x, float y) {
        var wheels = wheels();
        float nearest = Float.POSITIVE_INFINITY, inside = Float.POSITIVE_INFINITY, dx = 0, dy = 1;
        for (int p = 0; p < wheels.courses().size(); p++) {
            float offset = p < wheels.pairs() ? RUT_OFFSET : 0;
            // A course whose ruts all lie farther than the nearest so far, and outside it, changes nothing.
            if (wheels.nearest(p, x, y) - offset >= Math.max(0, nearest - .01f)) { continue; }
            PathSample sample = pathSample(x, y, wheels.courses(), wheels.ends(), BORDER_ENDS, p);
            float rut = sample.distance() - offset;
            inside = Math.min(inside, rut);
            // A corner's lone rut runs along a course's rut, maybe the other way: the course, listed first, keeps the
            // heading there, so that neighbouring texels do not filter it away.
            if (Math.abs(rut) < nearest - .01f) { nearest = Math.abs(rut); dx = sample.dx(); dy = sample.dy(); }
        }
        float rut = (inside < 0 ? -nearest : nearest) * (1 - scuff(x, y));
        return new Track(new Vector2(dx, dy).nor(), Math.clamp((rut + RUT_OFFSET) / (RUT_OFFSET + RUT_REACH), 0, 1));
    }

    /** A dirt junction's middle is scuffed most within SCUFF_CORE of its centre, fading out by SCUFF_EDGE. */
    private static final float SCUFF_CORE = 1.2f * METRE, SCUFF_EDGE = 3.9f * METRE;
    /** How far both wander, either way: no scuff lies farther out than SCUFF_EDGE + SCUFF_RAGGED. */
    private static final float SCUFF_RAGGED = .9f * METRE;
    /** How much of a rut's distance the most scuffed ground loses: enough to wear them together, not to erase them. */
    private static final float SCUFF_WEAR = .8f;

    /**
     * How far turning wheels have scuffed a dirt junction's middle at a point (#ruts): SCUFF_WEAR where they have worn
     * its ruts together, fading out over a ragged edge; 0 off open junctions.
     */
    private float scuff(float x, float y) {
        float centre = (float) Math.sqrt(x * x + y * y);
        if (centre > SCUFF_EDGE + SCUFF_RAGGED || !openJunction()) { return 0; }
        float wx = (x + BoardGeometry.centerX(coords) / BoardGeometry.hexScale()) / (1.5f * METRE);
        float wy = (y + BoardGeometry.centerY(coords) / BoardGeometry.hexScale()) / (1.5f * METRE);
        float edge = centre + (2 * BoardRelief.noise(wx, wy) - 1) * SCUFF_RAGGED;
        float t = Math.clamp((edge - SCUFF_CORE) / (SCUFF_EDGE - SCUFF_CORE), 0, 1);
        return SCUFF_WEAR * (1 - t * t * (3 - 2 * t));
    }

    /**
     * How much of the meadow's grass a loose road of {@code kind} leaves at a point, 0 to 1, thickening back over RUT_THIN
     * as its soil or stone thins out into the grass (terrain-road.frag): a gravel road's from its graded edge
     * (GRAVEL_EDGE, #track); a dirt track's from the lips of its ruts (#ruts), on their crown over CROWN_THIN, and where
     * gravel spreads on over it towards a gravel neighbour (GpuRoads#patches), from that stone's edge too, as much as the
     * spread covers. A neighbour whose own track draws its hex (#trackedHexes) decides there.
     */
    float grass(Kind kind, float x, float y) {
        float wx = BoardGeometry.centerX(coords) + x * BoardGeometry.hexScale();
        float wy = BoardGeometry.centerY(coords) + y * BoardGeometry.hexScale();
        for (int d = 0; d < 6; d++) {
            if ((tracked & 1 << d) != 0 && BoardGeometry.contains(coords.translated(d), wx, wy)) { return 1; }
        }
        if (distance(x, y) >= SHOULDER) { return 1; }
        float stone = BoardRelief.smooth((track(x, y).across() * TRACK_REACH - GRAVEL_EDGE) / RUT_THIN);
        if (kind == Kind.GRAVEL) { return stone; }
        float rut = ruts(x, y).across() * (RUT_OFFSET + RUT_REACH) - RUT_OFFSET;
        float grass = BoardRelief.smooth((Math.abs(rut) - RUT_BARE) / (rut < 0 ? CROWN_THIN : RUT_THIN));
        // The spread is whole at the border and gone by the join's reach (GpuRoads#coverage).
        float spread = 0;
        for (Join join : joins) {
            if (tracked(join) && join.kind() == Kind.GRAVEL) {
                spread = Math.max(spread, Math.min(1, 2 * join.coverage(x, y)));
            }
        }
        return BoardRelief.lerp(grass, Math.min(grass, stone), spread);
    }

    /** Where a dirt track's ruts and their loose soil can lie (#ruts): within their reach of them, and its scuffed middle. */
    Area rutReach() {
        Area result = new Area();
        if (openJunction()) {
            float reach = SCUFF_EDGE + SCUFF_RAGGED;
            result.add(new Area(new Ellipse2D.Float(-reach, -reach, 2 * reach, 2 * reach)));
        }
        var wheels = wheels();
        for (int p = 0; p < wheels.courses().size(); p++) {
            var course = wheels.courses().get(p);
            float width = 2 * (p < wheels.pairs() ? RUT_OFFSET + RUT_REACH : RUT_REACH);
            var stroke = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            Path2D.Float line = new Path2D.Float();
            line.moveTo(course.getFirst().x, course.getFirst().y);
            for (int i = 1; i < course.size(); i++) { line.lineTo(course.get(i).x, course.get(i).y); }
            result.add(new Area(stroke.createStrokedShape(line)));
        }
        return result;
    }

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
        return Math.max(Math.max(pathSample(x, y, paths).edge(), endDistance(x, y)), squareEdge(x, y));
    }

    /** The same nearest path supplies the wheel mask and the direction of shallow, elongated scuffs. */
    Vector2 wheelDirection(float x, float y) {
        PathSample sample = pathSample(x, y, paintPaths());
        return new Vector2(sample.dx(), sample.dy()).nor();
    }

    /** Wheel heading and lane position share their finite curve search, differing only at the open end segments. */
    Track pavedWear(float x, float y) {
        var paths = paintPaths();
        byte[] ends = pathEnds();
        PathSample closed = new PathSample(x, y, false);
        sampleInterior(closed, paths, ends, ALL_ENDS, -1, sampleBounds());
        PathSample open = new PathSample(closed);
        sampleEnds(open, paths, ends, ALL_ENDS, -1, closed);
        return new Track(new Vector2(closed.dx(), closed.dy()).nor(), wearLane(open, merged(x, y)));
    }

    /** One query's scratch/result; nearer branches may be visited first, but equal distances retain source order. */
    private static final class PathSample {
        private final float x, y, margin;
        private final boolean edgeNeeded;
        private float distance = Float.POSITIVE_INFINITY, edge = Float.POSITIVE_INFINITY, dx, dy = 1, width;
        private int nearestPath = Integer.MAX_VALUE, nearestSegment = Integer.MAX_VALUE;

        PathSample(float x, float y) { this(x, y, true); }

        PathSample(float x, float y, boolean edgeNeeded) {
            this.x = x; this.y = y;
            this.edgeNeeded = edgeNeeded;
            // Conservative slack for the rounding of bounds versus the original projection arithmetic.
            margin = .0001f * (1 + Math.abs(x) + Math.abs(y));
        }

        PathSample(PathSample source) {
            x = source.x; y = source.y; margin = source.margin;
            edgeNeeded = source.edgeNeeded;
            distance = source.distance; edge = source.edge; dx = source.dx; dy = source.dy; width = source.width;
            nearestPath = source.nearestPath; nearestSegment = source.nearestSegment;
        }

        float distance() { return distance; }
        float edge() { return edge; }
        float dx() { return dx; }
        float dy() { return dy; }
        float width() { return width; }

        void segment(Point a, Point b, int path, int segment, boolean before, boolean after) {
            segment(a, b, path, segment, before, after, null);
        }

        /** The bounded wheel heading and extended lane position share their projection and, usually, distance. */
        void segment(Point a, Point b, int path, int segment, boolean before, boolean after, PathSample closed) {
            float sx = b.x - a.x, sy = b.y - a.y;
            float along = ((x - a.x) * sx + (y - a.y) * sy) / (sx * sx + sy * sy);
            float t = before ? along : Math.max(0, along);
            if (!after) { t = Math.min(1, t); }
            float ex = x - a.x - t * sx, ey = y - a.y - t * sy;
            float near = (float) Math.sqrt((double) ex * ex + (double) ey * ey);
            float bounded = Math.min(1, Math.max(0, t));
            accept(a, b, sx, sy, bounded, near, path, segment);
            if (closed != null) {
                if (t != bounded) {
                    ex = x - a.x - bounded * sx; ey = y - a.y - bounded * sy;
                    near = (float) Math.sqrt((double) ex * ex + (double) ey * ey);
                }
                closed.accept(a, b, sx, sy, bounded, near, path, segment);
            }
        }

        private void accept(Point a, Point b, float sx, float sy, float t, float near, int path, int segment) {
            if (edgeNeeded) { edge = Math.min(edge, near - a.width - t * (b.width - a.width)); }
            if (near < distance || near < Float.POSITIVE_INFINITY && near == distance
                  && (path < nearestPath || path == nearestPath && segment < nearestSegment)) {
                distance = near; dx = sx; dy = sy; width = a.width + t * (b.width - a.width);
                nearestPath = path; nearestSegment = segment;
            }
        }
    }

    private record SampleBounds(int start, int end, float minX, float minY, float maxX, float maxY, float width,
          SampleBounds left, SampleBounds right) {
        float lower(float x, float y) {
            float dx = Math.max(0, Math.max(minX - x, x - maxX)), dy = Math.max(0, Math.max(minY - y, y - maxY));
            return dx * dx + dy * dy;
        }

        void sample(PathSample sample, List<Point> path, int p, boolean first, boolean last) {
            float lower = lower(sample.x, sample.y);
            float reach = (sample.edgeNeeded ? Math.max(sample.distance, sample.edge + width) : sample.distance) + sample.margin;
            if (lower > reach * reach) { return; }
            if (left != null) {
                SampleBounds a = left, b = right;
                if (a.lower(sample.x, sample.y) > b.lower(sample.x, sample.y)) { a = right; b = left; }
                a.sample(sample, path, p, first, last);
                b.sample(sample, path, p, first, last);
            } else {
                for (int i = start; i < end; i++) {
                    // Open end segments were sampled as rays before this finite tree; do not measure them twice.
                    if (first && i == 1 || last && i == path.size() - 1) { continue; }
                    sample.segment(path.get(i - 1), path.get(i), p, i, false, false);
                }
            }
        }
    }
    /** Immutable bounds over consecutive curve segments; all mask texels share these derived values. */
    private volatile SampleBounds[] sampleBounds;

    private SampleBounds[] sampleBounds() {
        SampleBounds[] result = sampleBounds;
        if (result == null) { sampleBounds = result = sampleBounds(paths); }
        return result;
    }

    private static SampleBounds[] sampleBounds(List<List<Point>> paths) {
        SampleBounds[] result = new SampleBounds[paths.size()];
        for (int p = 0; p < paths.size(); p++) {
            result[p] = sampleBounds(paths.get(p), 1, paths.get(p).size());
        }
        return result;
    }

    private static SampleBounds sampleBounds(List<Point> path, int start, int end) {
        if (end - start > 8) {
            int middle = (start + end) / 2;
            SampleBounds a = sampleBounds(path, start, middle), b = sampleBounds(path, middle, end);
            return new SampleBounds(start, end, Math.min(a.minX, b.minX), Math.min(a.minY, b.minY),
                  Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.width, b.width), a, b);
        }
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX, width = 0;
        for (int i = start - 1; i < end; i++) {
            Point point = path.get(i);
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
            width = Math.max(width, point.width);
            // Preserve the exhaustive sampler's NaN edge for a zero-length segment.
            if (i >= start && point.x == path.get(i - 1).x && point.y == path.get(i - 1).y) {
                width = Float.POSITIVE_INFINITY;
            }
        }
        return new SampleBounds(start, end, minX, minY, maxX, maxY, width, null, null);
    }

    /** Single circulating lanes keep one axle; two-way roads place an axle on each side of their divider or median. */
    private float laneCenter(float width) {
        if (width <= Kind.PAVED.halfWidth / 2 + .001f) { return 0; }
        // Where a single lane widens into two, its one axle parts into the two lanes' as smoothly.
        if (width < Kind.PAVED.halfWidth) { return width - Kind.PAVED.halfWidth / 2; }
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
        if (result == null) { pathEnds = result = openEnds(paths); }
        return result;
    }

    private static byte[] openEnds(List<List<Point>> paths) {
        byte[] result = new byte[paths.size()];
        for (int p = 0; p < paths.size(); p++) {
            var path = paths.get(p);
            // A ring closes on itself (to rounding): it has no ends.
            if (Math.hypot(path.getFirst().x - path.getLast().x, path.getFirst().y - path.getLast().y) <= .01) { continue; }
            int flags = ALL_ENDS | ALL_ENDS << 1;
            if (Math.hypot(path.getFirst().x, path.getFirst().y) > BORDER) { flags |= BORDER_ENDS; }
            if (Math.hypot(path.getLast().x, path.getLast().y) > BORDER) { flags |= BORDER_ENDS << 1; }
            result[p] = (byte) flags;
        }
        return result;
    }

    /**
     * A path's {@code open} ends run straight on: every end ({@link #ALL_ENDS}) or those past the hex's border
     * ({@link #BORDER_ENDS}), so a point past one (an oblique border crossing leaves a wedge of carriageway beyond it)
     * still measures across the lane rather than round the end. With open ends, {@code sampledPaths} is {@link #paths}
     * or its prefix.
     */
    private PathSample pathSample(float x, float y, List<List<Point>> sampledPaths, int open) {
        return pathSample(x, y, sampledPaths, open == 0 ? null : pathEnds(), open, -1, sampleBounds());
    }

    /** {@link #pathSample} with each path's {@code ends}; only path {@code only}, unless that is -1. */
    private static PathSample pathSample(float x, float y, List<List<Point>> sampledPaths, byte[] ends, int open,
          int only) {
        return pathSample(x, y, sampledPaths, ends, open, only, null);
    }

    private static PathSample pathSample(float x, float y, List<List<Point>> sampledPaths, byte[] ends, int open,
          int only, SampleBounds[] bounds) {
        PathSample sample = new PathSample(x, y);
        // Unbounded rays have no finite AABB. Their distance can prune the finite interior, without forcing every
        // ancestor of an open end to be traversed for every texel.
        sampleEnds(sample, sampledPaths, ends, open, only, null);
        sampleInterior(sample, sampledPaths, ends, open, only, bounds);
        return sample;
    }

    private static void sampleEnds(PathSample sample, List<List<Point>> sampledPaths, byte[] ends, int open,
          int only, PathSample closed) {
        if (ends == null) { return; }
        for (int p = Math.max(0, only); p < (only < 0 ? sampledPaths.size() : only + 1); p++) {
            var path = sampledPaths.get(p);
            int end = path.size() - 1;
            if (end < 1) { continue; }
            boolean first = (ends[p] & open) != 0, last = (ends[p] & open << 1) != 0;
            if (first || end == 1 && last) {
                sample.segment(path.get(0), path.get(1), p, 1, first, last && end == 1, closed);
            }
            if (last && end > 1) { sample.segment(path.get(end - 1), path.get(end), p, end, false, true, closed); }
        }
    }

    private static void sampleInterior(PathSample sample, List<List<Point>> sampledPaths, byte[] ends, int open,
          int only, SampleBounds[] bounds) {
        for (int p = Math.max(0, only); p < (only < 0 ? sampledPaths.size() : only + 1); p++) {
            var path = sampledPaths.get(p);
            boolean first = ends != null && (ends[p] & open) != 0, last = ends != null && (ends[p] & open << 1) != 0;
            if (bounds != null && bounds[p].left != null) {
                bounds[p].sample(sample, path, p, first, last);
            } else {
                for (int i = 1; i < path.size(); i++) {
                    if (first && i == 1 || last && i == path.size() - 1) { continue; }
                    sample.segment(path.get(i - 1), path.get(i), p, i, false, false);
                }
            }
        }
    }

    /**
     * Distance along the existing two-exit course, used by its longitudinal terrain grade. Shared cross-sections
     * at path vertices keep this continuous across the lane; nearest-segment projection jumps inside a bend.
     */
    float throughStation(float x, float y) {
        if (borders.size() != 2 || paths.size() != 1) { return Float.NaN; }
        return station(0, x, y);
    }

    /** Distance along path {@code index} from its first point, as {@link #throughStation} measures it. */
    private float station(int index, float x, float y) {
        float station = 0, nearest = Float.POSITIVE_INFINITY, length = 0;
        boolean bounded = false;
        var path = paths.get(index);
        float[] sections = sections()[index];
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
     * Per path, the unit normal {x, y} of {@link #station}'s section at each of its vertices: square to the bisector of
     * the segments meeting there. Where a course crosses a border, the section is the border itself, so both hexes
     * measure one station all along their shared edge: the lattice is regular only to a 32nd, and a square crossing is
     * not exactly square to it.
     */
    private float[][] sections() {
        float[][] result = sections;
        if (result != null) { return result; }
        result = new float[paths.size()][];
        for (int index = 0; index < paths.size(); index++) {
            var path = paths.get(index);
            float[] normals = new float[2 * path.size()];
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
                if (length > 0) { normals[2 * i] = sx / length; normals[2 * i + 1] = sy / length; }
            }
            result[index] = normals;
        }
        sections = result;
        return result;
    }

    /** A colony slab road's pattern period in tile pixels (terrain-road.frag): a plain slab and an X-braced one. */
    static final float SLAB_PERIOD = 36;

    /**
     * Where a point lies on a colony slab road (terrain-road.frag), from the nearest course its paint follows: along it,
     * in slab periods from the course's first end, whole periods fitting between its ends so that neighbouring hexes
     * meet on one phase at every border, a plain slab's middle; and across it, 0 on the course and -1 or 1 at its
     * edges, its left or right looking along it. Null without a course.
     */
    float[] slab(float x, float y) {
        var sampled = paintPaths();
        byte[] ends = pathEnds();
        int nearest = -1;
        float distance = Float.POSITIVE_INFINITY, width = 0, side = 1;
        for (int p = 0; p < sampled.size(); p++) {
            var path = sampled.get(p);
            // Past a border the road runs on straight, as in #track.
            boolean first = (ends[p] & BORDER_ENDS) != 0, last = (ends[p] & BORDER_ENDS << 1) != 0;
            for (int i = 1; i < path.size(); i++) {
                Point a = path.get(i - 1), b = path.get(i);
                float dx = b.x - a.x, dy = b.y - a.y, span = dx * dx + dy * dy;
                if (span == 0) { continue; }
                float t = Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / span, first && i == 1 ? Float.NEGATIVE_INFINITY : 0,
                      last && i == path.size() - 1 ? Float.POSITIVE_INFINITY : 1);
                float ex = x - a.x - t * dx, ey = y - a.y - t * dy;
                float near = (float) Math.sqrt((double) ex * ex + (double) ey * ey);
                if (near < distance) {
                    side = dx * ey - dy * ex < 0 ? -1 : 1;
                    t = Math.clamp(t, 0, 1);
                    distance = near; nearest = p; width = a.width + t * (b.width - a.width);
                }
            }
        }
        if (nearest < 0) { return null; }
        // Periods count from where the course crosses its first border to where it crosses its last: an end past the
        // border runs on from its border's middle, the path's next point (#approach).
        var path = sampled.get(nearest);
        float[] stations = new float[path.size()];
        for (int i = 1; i < path.size(); i++) {
            stations[i] = stations[i - 1]
                  + (float) Math.hypot(path.get(i).x - path.get(i - 1).x, path.get(i).y - path.get(i - 1).y);
        }
        int last = path.size() - 1;
        boolean first = (ends[nearest] & BORDER_ENDS) != 0, end = (ends[nearest] & BORDER_ENDS << 1) != 0;
        float from = first ? stations[1] : 0, to = end ? stations[last - 1] : stations[last];
        if (to <= from) { from = 0; to = stations[last]; }
        // The coat runs on past its borders, under or over its neighbour's: there its station runs on from the
        // border's section too, so both coats lay the same slabs.
        float station = station(nearest, x, y);
        float[] sections = sections()[nearest];
        if (first) {
            Point border = path.get(1);
            float past = (x - border.x) * sections[2] + (y - border.y) * sections[3];
            if (past < 0) { station = stations[1] + past; }
        }
        if (end) {
            Point border = path.get(last - 1);
            float past = (x - border.x) * sections[2 * last - 2] + (y - border.y) * sections[2 * last - 1];
            if (past > 0) { station = stations[last - 1] + past; }
        }
        return new float[] { (station - from) / fit(to - from, SLAB_PERIOD), side * Math.min(1, distance / width) };
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
    record StopApproach(int direction, float x, float y, float nx, float ny, float inner, float outer, boolean yields) {
        StopApproach(int direction, float x, float y, float nx, float ny, float inner, float outer) {
            this(direction, x, y, nx, ny, inner, outer, false);
        }

        private End edge() { return new End(new Point(x, y, outer), nx, ny); }

        /**
         * This approach giving way rather than stopping: an entrance (on-ramp) merging into a two-lane road under stop
         * controls, behind a broken give-way line and a give-way sign.
         */
        StopApproach yielding() { return new StopApproach(direction, x, y, nx, ny, inner, outer, true); }
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
            // Nothing approaches the junction along an exit (off-ramp) to wait at its rails.
            if (!narrow && (single & ~inbound & 1 << direction) != 0) { continue; }
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

    /**
     * Paved approaches that yield at a crossing; an opposite pair carries the through road of a T. Where single-lane roads
     * join a two-lane one, an exit (off-ramp) only carries traffic away and waits for nothing; under stop controls an
     * entrance (on-ramp) gives way ({@link StopApproach#yields}) to the two-lane road, which keeps its priority through
     * the junction when it has at most two arms there. A single-lane fork or merge has no controls.
     */
    List<StopApproach> stopApproaches(int exits) {
        int arms = exits & ~apronExits & 63;
        if (circle != null || narrow || Integer.bitCount(arms) < 3) { return List.of(); }
        int stopped = arms & ~(single & ~inbound);
        boolean stops = control == HexAppearance.TrafficControl.STOPS;
        if (stops) {
            int wide = arms & ~single;
            if ((arms & single) != 0 && Integer.bitCount(wide) <= 2) { stopped &= ~wide; }
            else if (Integer.bitCount(wide) == 3) {
                for (int direction = 0; direction < 3; direction++) {
                    int through = (1 << direction) | (1 << (direction + 3));
                    if ((wide & through) == through) { stopped &= ~through; break; }
                }
            }
        }
        int controlled = stopped;
        return conflictApproaches(exits).stream().filter(arm -> (controlled & 1 << arm.direction()) != 0)
              .map(arm -> stops && (single & 1 << arm.direction()) != 0 ? arm.yielding() : arm)
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
                    if (pathSample(frame.x - frame.ny * across, frame.y + frame.nx * across,
                          other, null, 0, -1).edge() <= 0) {
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

    /**
     * Where a single arm of a two-lane road (a ramp) meets the rest of it: its stop plane at a junction, else partway out
     * along it; null for any other arm, and on a single-lane road.
     */
    StopApproach rampFrame(int exits, int direction) {
        if (narrow || (single & exits & 1 << direction) == 0) { return null; }
        for (var frame : conflictApproaches(exits)) {
            if (frame.direction() == direction) { return frame; }
        }
        int index = borders.size() < 3 ? 0 : Integer.bitCount(exits & ((1 << direction) - 1));
        var courses = paintPaths();
        if (index >= courses.size()) { return null; }
        // Along the course from the ramp's own border, so a bending course finds the ramp's leg, not its other one.
        var course = courses.get(index);
        Vector2 gate = border(direction);
        Point first = course.getFirst(), last = course.getLast();
        if (Math.hypot(last.x - gate.x, last.y - gate.y) < Math.hypot(first.x - gate.x, first.y - gate.y)) {
            course = course.reversed();
        }
        float goal = .38f * gate.len(), walked = 0;
        for (int i = 1; i < course.size(); i++) {
            Point a = course.get(i - 1), b = course.get(i);
            float step = (float) Math.hypot(b.x - a.x, b.y - a.y);
            // Past the border the course runs on into the neighbour: measure from the border itself.
            if (step < 1e-4f || a.x * gate.x + a.y * gate.y > gate.len2()) { continue; }
            if (walked + step < goal) { walked += step; continue; }
            float t = (goal - walked) / step;
            float width = a.width + t * (b.width - a.width), half = Math.max(.7f, width - .7f);
            // The frame's normal points out along the arm, back the way the course came in.
            return new StopApproach(direction, a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), (a.x - b.x) / step,
                  (a.y - b.y) / step, -half, half);
        }
        return null;
    }

    /** The plane where an arm of a junction meets the rest of it ({@link #conflictApproaches}); null for no such arm. */
    StopApproach armFrame(int exits, int direction) {
        for (var frame : conflictApproaches(exits)) {
            if (frame.direction() == direction) { return frame; }
        }
        return null;
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
            // A one-way single lane's whole width carries its traffic.
            if ((single & 1 << direction) != 0) { inner = -Math.max(.7f, width - .7f); }
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
            result.add(approach.yields() ? giveWay(approach) : stopBar(approach));
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

    /**
     * Give-way markings across an approach: a broken transverse line over its incoming lane, and upstream of it an
     * outlined triangle with its point facing approaching traffic. A roundabout's entries and an entrance ramp's mouth
     * share them.
     */
    private static Area giveWay(StopApproach frame) {
        Area result = new Area();
        Path2D.Float bar = new Path2D.Float();
        bar.moveTo(frame.x - frame.ny * frame.inner, frame.y + frame.nx * frame.inner);
        bar.lineTo(frame.x - frame.ny * frame.outer, frame.y + frame.nx * frame.outer);
        result.add(new Area(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
              10, new float[] { 1.6f, 1.2f }, 0).createStrokedShape(bar)));
        float middle = (frame.inner + frame.outer) / 2;
        float cx = frame.x - frame.ny * middle, cy = frame.y + frame.nx * middle;
        Path2D.Float triangle = new Path2D.Float();
        triangle.moveTo(cx + frame.nx * 8, cy + frame.ny * 8);
        triangle.lineTo(cx + frame.nx * 2 - frame.ny * 2.5f, cy + frame.ny * 2 + frame.nx * 2.5f);
        triangle.lineTo(cx + frame.nx * 2 + frame.ny * 2.5f, cy + frame.ny * 2 - frame.nx * 2.5f);
        triangle.closePath();
        result.add(new Area(new BasicStroke(.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
              .createStrokedShape(triangle)));
        return result;
    }

    /** Direction arrows' size in tile pixels (about 4.3 by 1.2 metres): length, head length, head and shaft half-widths. */
    private static final float ARROW = 12, ARROW_HEAD = 4.5f, ARROW_HALF = 1.6f, ARROW_SHAFT = .45f;

    /**
     * A single-lane road's direction arrows, pointing the way its traffic runs: one midway along its course through the
     * hex, or one along each arm of a single-lane fork or merge. A two-lane road and a single lane whose way is unknown
     * (no exits) have none.
     */
    Area arrows(int exits) {
        Area result = new Area();
        exits &= 63;
        if (!narrow || circle != null || exits == 0) { return result; }
        var courses = paintPaths();
        int count = Integer.bitCount(exits);
        if (count <= 2) {
            if (courses.isEmpty()) { return result; }
            var course = courses.getFirst();
            int first = Integer.numberOfTrailingZeros(exits);
            // A two-way course runs from its lower side's border to the other; a dead end from its tip out to its side.
            boolean along = count == 2 ? (inbound & 1 << first) != 0 : (inbound & exits) == 0;
            result.add(arrow(course, count == 2 ? .5f : .75f, along));
        } else {
            int remaining = exits;
            for (int i = 0; i < Math.min(count, courses.size()); i++) {
                int arm = Integer.lowestOneBit(remaining);
                remaining &= ~arm;
                // A junction's arm runs from its hub out to its border.
                result.add(arrow(courses.get(i), .62f, (inbound & arm) == 0));
            }
        }
        result.intersect(footprint(-.4f));
        return result;
    }

    /**
     * Where a two-lane road restricts who may take an exit (SingleLane#feeds), arrows in each of its approaches' lanes
     * before the junction, one branch for each way its traffic may go on ({@link #turn}): the two-lane road's other arms
     * and the exits it may take, never an entrance. None where every approach may take its exits, as at any junction.
     */
    Area approachArrows(int exits) {
        Area result = new Area();
        int arms = exits & 63, outgoing = narrow ? 0 : single & ~inbound & arms;
        if (circle != null || outgoing == 0 || closed == 0) { return result; }
        int onward = arms & ~single | outgoing;
        for (int from = 0; from < 6; from++) {
            if ((arms & ~single & 1 << from) == 0) { continue; }
            boolean left = false, ahead = false, right = false;
            for (int to = 0; to < 6; to++) {
                if (to == from || (onward & 1 << to) == 0 || closed(from, to)) { continue; }
                switch (turn(from, to)) {
                    case 1 -> left = true;
                    case 0 -> ahead = true;
                    default -> right = true;
                }
            }
            var frame = armFrame(exits, from);
            if (frame == null) { continue; }
            // In the middle of the incoming lane, a little before the junction, pointing the way its traffic runs.
            float lane = (frame.inner() + frame.outer()) / 2, back = ARROW / 2 + 2;
            float x = frame.x() + frame.nx() * back - frame.ny() * lane, y = frame.y() + frame.ny() * back + frame.nx() * lane;
            result.add(laneArrow(x, y, -frame.nx(), -frame.ny(), ahead, left, right));
        }
        result.intersect(footprint(-.4f));
        return result;
    }

    /**
     * A lane arrow centred at x, y along tx, ty: pointing on ahead, or its shaft ending at its middle where it may not go
     * straight on, with a branch from there to the left, the right, or both.
     */
    private static Area laneArrow(float x, float y, float tx, float ty, boolean ahead, boolean left, boolean right) {
        Area result;
        if (ahead) {
            result = arrow(List.of(new Point(x - tx * ARROW, y - ty * ARROW, 0), new Point(x + tx * ARROW, y + ty * ARROW, 0)),
                  .5f, true);
        } else {
            Path2D.Float shaft = new Path2D.Float();
            float lx = -ty, ly = tx, back = -ARROW / 2, end = ARROW_SHAFT;
            shaft.moveTo(x + tx * back + lx * ARROW_SHAFT, y + ty * back + ly * ARROW_SHAFT);
            shaft.lineTo(x + tx * end + lx * ARROW_SHAFT, y + ty * end + ly * ARROW_SHAFT);
            shaft.lineTo(x + tx * end - lx * ARROW_SHAFT, y + ty * end - ly * ARROW_SHAFT);
            shaft.lineTo(x + tx * back - lx * ARROW_SHAFT, y + ty * back - ly * ARROW_SHAFT);
            shaft.closePath();
            result = new Area(shaft);
        }
        for (int side : new int[] { 1, -1 }) {
            if (side == 1 ? !left : !right) { continue; }
            // Leaving the shaft at its middle at 45 degrees, to the left (anticlockwise) or the right, its head as the main one.
            float bx = (tx - side * ty) * .7071f, by = (ty + side * tx) * .7071f;
            float length = ARROW * .55f, head = ARROW_HEAD * .8f, half = ARROW_HALF * .9f;
            float ex = x + bx * length, ey = y + by * length, lx = -by, ly = bx;
            float nx = ex - bx * head, ny = ey - by * head;
            Path2D.Float branch = new Path2D.Float();
            branch.moveTo(x + lx * ARROW_SHAFT, y + ly * ARROW_SHAFT);
            branch.lineTo(nx + lx * ARROW_SHAFT, ny + ly * ARROW_SHAFT);
            branch.lineTo(nx + lx * half, ny + ly * half);
            branch.lineTo(ex, ey);
            branch.lineTo(nx - lx * half, ny - ly * half);
            branch.lineTo(nx - lx * ARROW_SHAFT, ny - ly * ARROW_SHAFT);
            branch.lineTo(x - lx * ARROW_SHAFT, y - ly * ARROW_SHAFT);
            branch.closePath();
            result.add(new Area(branch));
        }
        return result;
    }

    /** An arrow centred {@code share} of the way along {@code course}, pointing along it or back against it. */
    private static Area arrow(List<Point> course, float share, boolean along) {
        float length = 0;
        for (int i = 1; i < course.size(); i++) {
            length += (float) Math.hypot(course.get(i).x - course.get(i - 1).x, course.get(i).y - course.get(i - 1).y);
        }
        if (length < ARROW) { return new Area(); }
        float goal = length * share, walked = 0;
        float x = course.getFirst().x, y = course.getFirst().y, tx = 0, ty = 0;
        for (int i = 1; i < course.size(); i++) {
            Point a = course.get(i - 1), b = course.get(i);
            float step = (float) Math.hypot(b.x - a.x, b.y - a.y);
            if (step < 1e-4f) { continue; }
            tx = (b.x - a.x) / step;
            ty = (b.y - a.y) / step;
            if (walked + step >= goal) {
                float t = (goal - walked) / step;
                x = a.x + t * (b.x - a.x);
                y = a.y + t * (b.y - a.y);
                break;
            }
            walked += step;
        }
        if (!along) { tx = -tx; ty = -ty; }
        // Across the arrow, its left as it points.
        float lx = -ty, ly = tx, back = -ARROW / 2, neck = ARROW / 2 - ARROW_HEAD, tip = ARROW / 2;
        Path2D.Float shape = new Path2D.Float();
        shape.moveTo(x + tx * back + lx * ARROW_SHAFT, y + ty * back + ly * ARROW_SHAFT);
        shape.lineTo(x + tx * neck + lx * ARROW_SHAFT, y + ty * neck + ly * ARROW_SHAFT);
        shape.lineTo(x + tx * neck + lx * ARROW_HALF, y + ty * neck + ly * ARROW_HALF);
        shape.lineTo(x + tx * tip, y + ty * tip);
        shape.lineTo(x + tx * neck - lx * ARROW_HALF, y + ty * neck - ly * ARROW_HALF);
        shape.lineTo(x + tx * neck - lx * ARROW_SHAFT, y + ty * neck - ly * ARROW_SHAFT);
        shape.lineTo(x + tx * back - lx * ARROW_SHAFT, y + ty * back - ly * ARROW_SHAFT);
        shape.closePath();
        return new Area(shape);
    }

    /** A continuous divider channels the last road-width before a junction or railway stop boundary. */
    Area approachLines(int exits, List<StopApproach> approaches) {
        Area result = new Area();
        var courses = paintPaths();
        for (var approach : approaches) {
            // A single lane has no divider to channel.
            if ((single & 1 << approach.direction()) != 0) { continue; }
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
        // A single lane has no centre line: its arrows show its way (#arrows).
        if (exits == 0 || narrow) { return area; }
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
        // A two-lane road keeping its priority past the ramps that join it runs its centre line on through the junction,
        // as through any hex of its own: nothing on it stops or turns across a give-way.
        int through = junction ? priorityRoad(exits) : 0;
        if (through != 0) {
            area.add(priorityLine(coords, through));
            int road = through;
            conflicts = conflicts.stream().filter(stop -> (road & 1 << stop.direction()) == 0).toList();
        }
        int remaining = exits;
        for (var points : paintPaths()) {
            int arm = Integer.lowestOneBit(remaining);
            remaining &= ~arm;
            if (junction && ((markedExits & arm) == 0 || ((single | through) & arm) != 0)) { continue; }
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
            } else if (single != 0) {
                // A two-lane road narrowing to a single lane: its divider ends midway, where its lanes begin to merge.
                int sides = exits;
                for (Point border : borders) {
                    int side = Integer.lowestOneBit(sides);
                    sides &= ~side;
                    if ((single & side) == 0) { continue; }
                    var normal = border(Integer.numberOfTrailingZeros(side)).nor();
                    var end = new End(border, normal.x, normal.y);
                    // Within the single arm's own wedge, so a tight return turn keeps its two-lane half's divider.
                    Area cut = end.band(-(float) Math.hypot(border.x, border.y), 128);
                    cut.intersect(arm(end));
                    paint.subtract(cut);
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

    /**
     * The two arms of the two-lane road that keeps its priority through this junction (#stopApproaches): under stop
     * controls, where only single-lane ramps join it and it has just these two two-lane arms; 0 otherwise, as at a
     * junction of two-lane roads or under traffic lights, whose dividers stop short of the junction.
     */
    int priorityRoad(int exits) {
        int arms = exits & ~apronExits & 63, wide = arms & ~single;
        if (circle != null || narrow || control != HexAppearance.TrafficControl.STOPS || (arms & single) == 0
              || Integer.bitCount(wide) != 2 || corridor.sides() != 0) { return 0; }
        return wide;
    }

    /** The centre line of the priority road's two arms ({@link #priorityRoad}): that of a road with just those two exits. */
    private Area priorityLine(Coords coords, int through) {
        BoardRoad road = layout(coords, through, Kind.PAVED,
              direction -> joins.stream().filter(join -> join.direction() == direction).map(Join::kind)
                    .findFirst().orElse(Kind.PAVED), direction -> null, false, false);
        road.solidApproaches = solidApproaches & through;
        road.apronExits = apronExits;
        road.concreteEntrances = concreteEntrances;
        // Where traffic may not cross the oncoming lanes into an exit, a solid line keeps it from turning across them.
        boolean noCrossing = false;
        for (int exit = 0; exit < 6; exit++) {
            if ((single & ~inbound & 1 << exit) == 0) { continue; }
            for (int from = 0; from < 6; from++) {
                noCrossing |= (through & 1 << from) != 0 && closed(from, exit) && leftTurn(from, exit);
            }
        }
        if (!noCrossing) { return road.markings(coords, through); }
        Area solid = new Area();
        for (var points : road.paintPaths()) {
            solid.add(new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(linePath(points))));
        }
        // It stops short of a dirt or gravel road it runs on into, as the dashed line does (#markings).
        for (Join join : road.joins) {
            if (join.kind() == Kind.PAVED) { continue; }
            End edge = join.edge();
            solid.subtract(edge.band(-8, 128, edge.point.width + 1));
        }
        road.clipEnds(solid, -3);
        return solid;
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
            // A straight transverse give-way line stops the whole incoming lane before the circulating road; the
            // triangle sits upstream of the line, with its point facing approaching traffic.
            result.add(giveWay(frame));
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
}
