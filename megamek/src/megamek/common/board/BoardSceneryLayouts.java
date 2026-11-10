/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Plain authored compositions of shared meshes ({@code data/models/board/scenery/layouts.json}); terrain rules and
 * artwork selection remain with their owners. The rows are the legacy conversion ({@link #convert}) and the editor's
 * stamps; the 3D board draws rows only for terrain-owned art (geysers, rubble, fortified, cleared-rubble paths and
 * gravel, elevator plates), which the conversion never takes, and for a vegetation skin ({@link #skin}: a container
 * yard). Image-free and thread-safe: the rows are read once and only read after.
 */
public final class BoardSceneryLayouts {
    /** The pillar mesh of the legacy pillar art (pillars1-6); on a bridge hex that art is the bridge's Pillars toggle. */
    public static final String PILLAR = "scenery/parks/pillar";
    /** A hex in the rows' model px. */
    private static final float TILE_WIDTH = HexTile.MODEL_WIDTH, TILE_HEIGHT = HexTile.HEIGHT;
    private static final String ROAD_TREES = "scenery/saxarba/roads/road_trees";
    private static final Map<String, Layout> LAYOUTS = read();

    private BoardSceneryLayouts() { }

    /**
     * A parked car's place: slot {@code slot} ({@link BoardDecalArt.Footprint#slots}) of the layout's {@code plate}-th
     * decal row.
     */
    public record Seat(int plate, int slot) { }

    /** A car park's decal row as placed (model px), the frame of its seats; {@code mirror} flips its art along the row. */
    private record Plate(Component row, float x, float y, float rotation, boolean mirror) {
        /**
         * Beside a road, the row as the data places it: its open side on the road's edge. Without one, the row folded
         * back to back on the line through the hex centre along it: turned to open away from that line, its closed side
         * on it, and mirrored so its slots keep their order along the lane (each car keeps its side and its order).
         */
        static Plate of(Component row, boolean road) {
            if (road) { return new Plate(row, row.x(), row.y(), row.rotation(), false); }
            // The row's back (its +y) points away from its lane; move it so its back edge lies on the centre line.
            double turn = Math.toRadians(row.rotation()), backX = -Math.sin(turn), backY = Math.cos(turn);
            double half = BoardDecalArt.footprint(row.asset()).height() * row.scale() / 2;
            double shift = half - (row.x() * backX + row.y() * backY);
            return new Plate(row, (float) (row.x() + shift * backX), (float) (row.y() + shift * backY),
                  row.rotation() + 180, true);
        }
    }

    /**
     * A row (model px and degrees); {@code tree} marks the shared park tree slot, {@code connections} are a route
     * marker's sides (see {@link BoardDecoration#connections()}), {@code stretch} its optional per-axis factors on top of
     * {@code scale} (see {@link BoardDecoration#stretch()}), {@code colours} its mesh's replacement slot colours (see
     * {@link BoardDecoration#colours()}), such as a car's paint, and {@code seat} (or null) where the conversion parks it
     * instead of its legacy spot.
     */
    public record Component(String asset, boolean tree, float x, float y, float z,
          float rotation, float scale, int connections, BoardDecoration.Stretch stretch, BoardDecoration.Colours colours,
          Seat seat) {
        public Component(String asset, boolean tree, float x, float y, float z, float rotation, float scale) {
            this(asset, tree, x, y, z, rotation, scale, 0, BoardDecoration.Stretch.NONE, BoardDecoration.Colours.NONE);
        }

        public Component(String asset, boolean tree, float x, float y, float z, float rotation, float scale,
              int connections, BoardDecoration.Stretch stretch, BoardDecoration.Colours colours) {
            this(asset, tree, x, y, z, rotation, scale, connections, stretch, colours, null);
        }

        /** Paint on the receiver, such as a car park's row of bays, rather than a mesh. */
        public boolean decal() { return asset.startsWith("decal/"); }

        /** A route marker ({@link RailRoute}): its own object, never a group member. */
        public boolean route() { return RailRoute.Kind.of(asset) != null; }
    }

    public record Layout(List<Component> components) {
        /** The rows that are pieces of the artwork; two or more of them form a group. */
        public long pieces() { return components.stream().filter(component -> !component.route()).count(); }

        /** Pillar art (pillars1-6): every row is the pillar mesh. */
        public boolean pillars() { return components.stream().allMatch(component -> component.asset().equals(PILLAR)); }

        /** Paint only: every row is a decal, such as an elevator's level plate. */
        public boolean paint() { return components.stream().allMatch(Component::decal); }

        /**
         * The same rows as native objects on {@code hex}: XY in hex width/height units, ids in row order from
         * {@code ids}. Trees store their bare species; the renderer shows the winter form on snow. A route marker keeps
         * its sides and stays out of {@code group}: a maglev marker holds its support's level ({@link #routePlacement}), a
         * rail lies on the ground (the conversion lays it as rail terrain, on a deck by {@link #railOnDeck}). A route's
         * pieces ride it
         * ({@link RailRoute#ride}: at the route's placement; a maglev train's ride height is in its meshes, the renderer
         * stands a rail vehicle on the drawn rail top): the layout's own marker of their kind, else the hex's marker of
         * that kind (a stamped train).
         * Other rows, such as parked cars, stand on the hex's support, trees beneath a bridge's deck
         * ({@link BoardSceneryLayouts#receiver}); an artwork with its own route leaves them out
         * where they would find no support: water or an industrial plant, which its fixed-level maglev crosses. A car park's
         * decal rows (the decals with car slots) stand beside its lane's road ({@code road}, as the data places them) or,
         * without one, back to back ({@link Plate#of}); a seated car parks in its slot of its decal row. Other decal rows
         * lie as the data places them. Parking paint can extend across the hex edge with its full-size bays and cars.
         */
        public List<BoardDecoration> decorations(Coords coords, Hex hex, Supplier<String> ids, String group, boolean road) {
            List<BoardDecoration> result = new ArrayList<>();
            boolean ownRoute = components.stream().anyMatch(Component::route);
            // A conventional train composition grows with its cars, retaining its coupler gaps. Mixed scenery and
            // route layouts keep their authored locations; their offsets describe the surrounding hex, not a train.
            boolean conventionalTrain = !components.isEmpty()
                  && components.stream().allMatch(row -> RailRoute.rides(row.asset()) == RailRoute.Kind.RAIL);
            // Each kind's marker placement: the layout's own marker, else the one already on the hex.
            Map<RailRoute.Kind, BoardDecoration.Placement> routes = new HashMap<>();
            for (RailRoute.Kind kind : RailRoute.Kind.values()) {
                var marker = RailRoute.find(hex, kind);
                if (marker != null) { routes.put(kind, marker.placement()); }
            }
            for (Component row : components) {
                var kind = RailRoute.Kind.of(row.asset());
                if (kind != null) {
                    routes.put(kind, kind == RailRoute.Kind.RAIL ? BoardDecoration.Placement.ground() : routePlacement(hex));
                }
            }
            // Only a car park's rows fold; other paint (a helipad, a level crossing) lies where the data places it.
            List<Plate> plates = components.stream().filter(Component::decal)
                  .map(row -> Plate.of(row, road || BoardDecalArt.footprint(row.asset()).slots().isEmpty())).toList();
            int decals = 0;
            for (Component row : chosen(coords, BoardTreeDistribution.PARK, BoardTreeDistribution.NATURAL)) {
                Plate own = row.decal() ? plates.get(decals++) : null;
                var marker = RailRoute.Kind.of(row.asset());
                var riding = marker != null ? null : RailRoute.rides(row.asset());
                boolean rides = marker != null || riding != null && routes.containsKey(riding);
                var placement = marker != null ? routes.get(marker)
                      : rides ? routes.get(riding) : receiver(hex, !row.tree());
                String support = placement.mode().equals("absolute") ? "" : placement.receiver().terrain();
                if (ownRoute && !rides && (support.equals("industrial") || support.equals("ground") && hex.containsTerrain(Terrains.WATER))) {
                    continue;
                }
                float x = own == null ? row.x() : own.x(), y = own == null ? row.y() : own.y();
                if (conventionalTrain) { x *= RailRoute.CONVENTIONAL_SCALE; y *= RailRoute.CONVENTIONAL_SCALE; }
                float rotation = row.route() ? 0 : own == null ? row.rotation() : own.rotation();
                if (row.seat() != null) {
                    // The slot as the plate paints it: a mirrored plate flips its art along the row.
                    Plate plate = plates.get(row.seat().plate());
                    var slot = BoardDecalArt.footprint(plate.row().asset()).slots().get(row.seat().slot());
                    double mirror = plate.mirror() ? -1 : 1, scale = plate.row().scale();
                    double turn = Math.toRadians(plate.rotation()), sx = mirror * slot.x() * scale, sy = slot.y() * scale;
                    x = (float) (plate.x() + sx * Math.cos(turn) - sy * Math.sin(turn));
                    y = (float) (plate.y() + sx * Math.sin(turn) + sy * Math.cos(turn));
                    rotation = (float) (plate.rotation() + mirror * slot.heading());
                }
                result.add(new BoardDecoration(ids.get(), row.decal() ? "decal" : "prop", row.asset(), null,
                      x / TILE_WIDTH, y / TILE_HEIGHT, rotation,
                      own != null && own.mirror(), row.scale(), placement, 0,
                      row.decal() && BoardDecalArt.footprint(row.asset()).slots().isEmpty(), 0, 0,
                      row.route() ? null : group, false, row.connections(), row.stretch(), row.colours()));
            }
            return result;
        }

        /**
         * The rows turned {@code degrees} clockwise about the hex centre, as a skin's rotation turns its look
         * ({@link HexAppearance#rotation}): each row's place, and its heading, which turns counter-clockwise.
         */
        public Layout turned(int degrees) {
            if (degrees == 0) { return this; }
            double turn = Math.toRadians(degrees), cos = Math.cos(turn), sin = Math.sin(turn);
            return new Layout(components.stream().map(row -> new Component(row.asset(), row.tree(),
                  (float) (row.x() * cos + row.y() * sin), (float) (row.y() * cos - row.x() * sin), row.z(),
                  row.rotation() - degrees, row.scale(), row.connections(), row.stretch(), row.colours(), row.seat())).toList());
        }

        /**
         * The rows with each broad park tree's species and heading drawn, in row order, from the hex's seed. The
         * conversion draws them, so a converted tree is the species the legacy render showed.
         */
        public List<Component> chosen(Coords coords, List<String> species, boolean natural) {
            var random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L);
            List<Component> result = new ArrayList<>(components.size());
            for (Component row : components) {
                if (!natural || !row.tree()) { result.add(row); continue; }
                String asset = BoardTreeDistribution.species(species, coords, row.x(), row.y(), random.nextLong());
                result.add(new Component(asset, row.tree(), row.x(), row.y(), row.z(), random.nextFloat() * 360, row.scale(),
                      row.connections(), row.stretch(), row.colours(), row.seat()));
            }
            return result;
        }
    }

    public static Layout layout(String asset) { return LAYOUTS.get(asset); }

    /** Roadside tree rows clear every ground route arm, including a rail that crosses or shares their road. */
    private static Layout layout(String asset, Hex hex) {
        if (asset.startsWith(ROAD_TREES)) {
            int rails = RailRoute.groundSides(hex);
            int sides = Integer.parseInt(asset.substring(ROAD_TREES.length())) | rails;
            asset = ROAD_TREES + String.format(Locale.ROOT, "%02d", sides & 63);
        }
        return LAYOUTS.get(asset);
    }

    /** Plain-road rows stay near their verge. Only an actual railway needs room for its additional footprint. */
    private static List<BoardDecoration> clearTreesOfRail(List<BoardDecoration> objects, Hex hex, Coords at,
          Function<Coords, Hex> board) {
        int sides = RailRoute.groundSides(hex);
        if (sides == 0) { return objects; }
        var marker = RailRoute.markers(hex, RailRoute.Kind.RAIL).stream().filter(m -> !RailRoute.onDeck(m)).findFirst();
        if (marker.isEmpty()) { return objects; }
        var corridor = RailRoute.corridor(hex, at, board);
        var tracks = RailRoute.lines(sides, RailRoute.pieces(marker.get(), sides), corridor);
        List<float[]> bands = new ArrayList<>();
        for (var track : tracks) {
            treeBand(bands, track, RailRoute.BALLAST_HALF_WIDTH);
            int ends = (track.from() < 0 ? 0 : 1 << track.from()) | (track.to() < 0 ? 0 : 1 << track.to());
            if ((ends & corridor.sides()) == 0) { continue; }
            // Keep the centered/beside union clear. A standalone stamp has no neighbors to resolve a border's side.
            treeBand(bands, new RailRoute.Line(track.from(), track.to(), track.a(), track.b()),
                  RailRoute.BESIDE_RAIL + RailRoute.BALLAST_HALF_WIDTH);
        }
        if (Integer.bitCount(sides) >= 3) {
            // The road fills a shared junction's centre even where the selected rail curves leave an inner gap.
            for (int d = 0; d < 6; d++) {
                if ((corridor.sides() & 1 << d) == 0) { continue; }
                for (var arm : RailRoute.lines(1 << d, 0)) {
                    treeBand(bands, arm, RailRoute.BESIDE_RAIL + RailRoute.BALLAST_HALF_WIDTH);
                }
            }
        }
        List<BoardDecoration> moved = new ArrayList<>();
        for (var object : objects) {
            var hull = BoardModelBounds.footprint(at, object);
            if (hull == null) { moved.add(object); continue; }
            float x = (float) object.x() * TILE_WIDTH, y = (float) object.y() * TILE_HEIGHT;
            float cx = HexTile.centerX(at, TILE_WIDTH) + x, cy = HexTile.centerY(at, TILE_HEIGHT) + y;
            var footprint = new float[hull.size() * 8 + 1][2];
            for (int edge = 0; edge < hull.size(); edge++) {
                var a = hull.get(edge); var b = hull.get((edge + 1) % hull.size());
                for (int i = 0; i < 8; i++) {
                    footprint[edge * 8 + i] = new float[] { a.x() + (b.x() - a.x()) * i / 8 - cx,
                          a.y() + (b.y() - a.y()) * i / 8 - cy };
                }
            }
            for (int step = 0; step < 100; step++) {
                float distance = treeDistance(bands, footprint, x, y);
                if (distance >= 1.5f) { break; }
                float dx = treeDistance(bands, footprint, x + .1f, y) - treeDistance(bands, footprint, x - .1f, y);
                float dy = treeDistance(bands, footprint, x, y + .1f) - treeDistance(bands, footprint, x, y - .1f);
                float length = (float) Math.hypot(dx, dy);
                if (length < .0001f) { dx = x; dy = y; length = (float) Math.hypot(x, y); }
                if (length < .0001f) { break; }
                float move = Math.min(2, 1.51f - distance);
                x += dx / length * move; y += dy / length * move;
            }
            moved.add(object.transform(x / TILE_WIDTH, y / TILE_HEIGHT, object.rotation(), object.mirror(), object.scale(),
                  object.placement()));
        }
        return List.copyOf(moved);
    }

    private static void treeBand(List<float[]> bands, RailRoute.Line track, float width) {
        float[] a = track.at(0);
        for (int i = 1; i <= 24; i++) {
            float[] b = track.at(i / 24f);
            bands.add(new float[] { a[0], a[1], b[0], b[1], width });
            a = b;
        }
    }

    private static float treeDistance(List<float[]> bands, float[][] footprint, float x, float y) {
        float distance = Float.POSITIVE_INFINITY;
        for (var p : footprint) {
            for (var band : bands) {
                float dx = band[2] - band[0], dy = band[3] - band[1];
                float t = Math.clamp(((x + p[0] - band[0]) * dx + (y + p[1] - band[1]) * dy)
                      / (dx * dx + dy * dy), 0, 1);
                distance = Math.min(distance, (float) Math.hypot(x + p[0] - band[0] - t * dx,
                      y + p[1] - band[1] - t * dy) - band[4]);
            }
        }
        return distance;
    }

    /**
     * The rows the hex's vegetation design draws as a skin ({@link BoardEditorBlueprint.Skin}, a container yard): its
     * layout for the hex's cover level, turned by the design's rotation; null without a skin design, or without woods
     * or jungle (cleared or burnt down). The one rule of the 3D views, which draw the rows as objects the terrain owns,
     * and of the conversion's crane tips ({@link #reachedTips}).
     */
    public static Layout skin(Hex hex) { return skin(hex, hex.getAppearance().get("vegetation")); }

    /** {@link #skin(Hex)} for the vegetation design {@code design} (or null). */
    private static Layout skin(Hex hex, HexAppearance design) {
        var variant = design == null || design.variant() == null ? null : BoardEditorBlueprint.get().variant(design.variant());
        String cover = variant == null || variant.skin() == null ? null : BoardEditorBlueprint.Skin.cover(hex);
        return cover == null ? null
              : LAYOUTS.get(variant.skin().layouts().get(cover)).turned(design.rotation() == null ? 0 : design.rotation());
    }

    /** A composed artwork selection needs no baked per-layout mesh. */
    public static boolean hasLayout(String asset) { return LAYOUTS.containsKey(asset); }

    /**
     * Pillar art on a bridge hex is the bridge's Pillars toggle ({@link HexAppearance#PILLARS}), not objects: the one
     * decode of the conversion. On any other hex it stays plain pillars.
     */
    public static boolean bridgePillars(Hex hex, String key) {
        return hex.containsTerrain(Terrains.BRIDGE) && LAYOUTS.get(key).pillars();
    }

    /** A present piece of a 7-hex set that misses one on the board: the set's emblem on {@code centre}, in {@code group}. */
    private record Piece(String emblem, Coords centre, String group) { }

    /** Where {@code centre} lies from {@code coords}, in hex widths. */
    private static float offsetX(Coords coords, Coords centre) {
        return (HexTile.centerX(centre, TILE_WIDTH) - HexTile.centerX(coords, TILE_WIDTH)) / TILE_WIDTH;
    }

    /** Where {@code centre} lies from {@code coords}, in hex heights. */
    private static float offsetY(Coords coords, Coords centre) {
        return (HexTile.centerY(centre, TILE_HEIGHT) - HexTile.centerY(coords, TILE_HEIGHT)) / TILE_HEIGHT;
    }

    /**
     * The legacy decode's type ({@link BridgeSpan#decode}) for every bridge hex of {@code board} that stores none, decoded
     * once per span, with pillar art read from {@code scenery} (the hexes resolved so far). The conversion calls it before
     * it changes the board.
     */
    private static Map<Coords, HexAppearance> bridgeTypes(Board board, Map<Coords, TilesetScenery.Scenery> scenery) {
        Map<Coords, HexAppearance> types = new HashMap<>();
        Function<Coords, Hex> hexes = board::getHex;
        Predicate<Coords> pillarArt = at -> scenery.getOrDefault(at, TilesetScenery.Scenery.EMPTY).models()
              .stream().anyMatch(key -> bridgePillars(board.getHex(at), key));
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (!hex.containsTerrain(Terrains.BRIDGE) || types.containsKey(at)
                      || HexAppearance.bridgeBuilt(hex.getAppearance()) != null) { continue; }
                var span = BridgeSpan.of(hexes, at);
                var type = BridgeSpan.decode(hexes, pillarArt, span) ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE;
                span.forEach(member -> types.put(member, type));
            }
        }
        return types;
    }

    /**
     * Gives each of {@code hexes} the designs that draw its legacy art tokens ({@link #designed}). The tokens stay, for
     * the 2D board.
     */
    private static void design(Board board, List<Coords> hexes) {
        Map<BoardLocation, Hex> designed = new HashMap<>();
        for (Coords coords : hexes) {
            Hex hex = board.getHex(coords);
            if (tokens(hex).isEmpty()) { continue; }
            var appearance = designed(hex.getAppearance(), designs(hex, layers(hex)));
            if (appearance.equals(hex.getAppearance())) { continue; }
            Hex next = replacement(hex);
            next.setAppearance(appearance);
            designed.put(BoardLocation.of(coords, board.getBoardId()), next);
        }
        if (!designed.isEmpty()) { board.setHexes(designed); }
    }

    /**
     * {@code appearance} with each design of {@code designs} ({@link #designs}) whose owner has none: an owner's design,
     * such as a user's pick, wins over its token. A road design keeps the road's finish and junction controls.
     */
    private static Map<String, HexAppearance> designed(Map<String, HexAppearance> appearance,
          Map<Integer, Map<String, HexAppearance>> designs) {
        Map<String, HexAppearance> result = new HashMap<>(appearance);
        for (var owners : designs.values()) {
            owners.forEach((owner, design) -> {
                HexAppearance current = result.get(owner);
                if (current == null) { result.put(owner, design); }
                else if (current.variant() == null && current.asset() == null) {
                    result.put(owner, HexAppearance.keepLanes(HexAppearance.withTrafficControl(
                          new HexAppearance(design.variant(), null, current.material(), design.strength()), current.trafficControl()),
                          current));
                }
            });
        }
        return result;
    }

    /** Writes each of {@code types} on its hex, except where the hex has a type by now (pillar art made it PILLARS). */
    private static void typeBridges(Board board, Map<Coords, HexAppearance> types) {
        Map<BoardLocation, Hex> typed = new HashMap<>();
        types.forEach((at, type) -> {
            Hex hex = board.getHex(at);
            if (HexAppearance.bridgeBuilt(hex.getAppearance()) != null) { return; }
            Hex next = replacement(hex);
            Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
            appearance.put("bridge", type);
            next.setAppearance(appearance);
            typed.put(BoardLocation.of(at, board.getBoardId()), next);
        });
        if (!typed.isEmpty()) { board.setHexes(typed); }
    }

    /** The hex's appearance with its bridge's Pillars toggle on where pillar art turns it on: the conversion's. */
    private static Map<String, HexAppearance> appearance(Hex hex, TilesetScenery.Scenery scenery) {
        if (scenery.models().stream().noneMatch(key -> bridgePillars(hex, key))) { return hex.getAppearance(); }
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        appearance.put("bridge", HexAppearance.PILLARS);
        return appearance;
    }

    /**
     * The one legacy conversion, wherever a board can reach a 3D view. A board that is not native (not converted yet) has
     * its bridge exits written out ({@link #bridgeExits}) and every decodable scenery token decoded
     * ({@link #convert(Board, Collection)} over all its hexes); every untyped bridge of any board gets its type; then the
     * board is native. So converting a converted board only types bridges that lack a type. Returns the issues of the
     * decode.
     */
    public static List<HexIssue> convert(Board board) {
        List<Coords> hexes = new ArrayList<>();
        if (!board.isNativeFormat()) {
            bridgeExits(board);
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) { hexes.add(new Coords(x, y)); }
            }
        }
        return convert(board, hexes);
    }

    /**
     * Writes every bridge's exits as specified: the exits the load derived (an automatic bridge joins every adjacent
     * bridge), with the missing side of each one-sided exit added ({@link BridgeSpan#oneSided}: a deck's exit points into
     * a neighbouring deck at the same level that does not point back). So the game rules of every player, the 2D board's
     * included, see the bridge the span unit joins, and no deck drawn later joins a legacy one by itself. Decks at
     * different levels keep their exits.
     */
    private static void bridgeExits(Board board) {
        Map<BoardLocation, Hex> fixed = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (!hex.containsTerrain(Terrains.BRIDGE)) { continue; }
                Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
                int exits = bridge.getExits();
                for (int d = 0; d < 6; d++) {
                    if (BridgeSpan.oneSided(hex, board.getHex(at.translated(d)), d)) { exits |= 1 << d; }
                }
                if (exits == bridge.getExits() && bridge.hasExitsSpecified()) { continue; }
                Hex next = replacement(hex);
                next.addTerrain(new Terrain(Terrains.BRIDGE, bridge.getLevel(), true, exits));
                fixed.put(BoardLocation.of(at, board.getBoardId()), next);
            }
        }
        if (!fixed.isEmpty()) { board.setHexes(fixed); }
    }

    /**
     * Legacy fix-up: a legacy map's one-sided links between a road and a deck within a level of it ({@link #roadLink}),
     * its decoded bridges' included, become two-sided. A road whose exit leads into a deck that does not lead back
     * gives the deck that exit; a deck leading onto a road whose written exits miss it gives the road the exit back (an
     * automatic road joins such a deck by itself, {@code Hex#setExits}). The game rules then see the link the map drew.
     */
    private static void roadLinks(Board board) {
        Map<BoardLocation, Hex> fixed = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (!hex.containsTerrain(Terrains.BRIDGE)) { continue; }
                Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
                int exits = bridge.getExits();
                for (int d = 0; d < 6; d++) {
                    Hex next = board.getHex(at.translated(d));
                    if (roadLink(hex, next, d) && next.containsTerrainExit(Terrains.ROAD, (d + 3) % 6)) {
                        exits |= 1 << d;
                    }
                }
                if (exits != bridge.getExits()) {
                    Hex next = fixed.computeIfAbsent(BoardLocation.of(at, board.getBoardId()), key -> replacement(hex));
                    next.addTerrain(new Terrain(Terrains.BRIDGE, bridge.getLevel(), true, exits));
                }
                for (int d = 0; d < 6; d++) {
                    Coords side = at.translated(d);
                    Hex road = board.getHex(side);
                    int back = (d + 3) % 6;
                    if ((exits & 1 << d) == 0 || !roadLink(hex, road, d)
                          || road.containsTerrainExit(Terrains.ROAD, back)
                          || !road.getTerrain(Terrains.ROAD).hasExitsSpecified()) { continue; }
                    Hex linked = fixed.computeIfAbsent(BoardLocation.of(side, board.getBoardId()),
                          key -> replacement(road));
                    Terrain way = linked.getTerrain(Terrains.ROAD);
                    linked.addTerrain(new Terrain(Terrains.ROAD, way.getLevel(), true, way.getExits() | 1 << back));
                }
            }
        }
        if (!fixed.isEmpty()) { board.setHexes(fixed); }
    }

    /** The road finish of a colony's precast slabs (the 3D board's roads/colony-slabs), a choice on any map. */
    public static final String COLONY_SLABS = "colony-slabs";

    /**
     * A paved road's finish on {@code hex}: on lunar or Martian ground a colony's slabs where it would be asphalt or
     * have none, else {@code material} as it is. Legacy conversion (#colonyRoads) and the editor's new roads share it.
     */
    public static String roadFinish(Hex hex, String material) {
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        boolean colony = theme.contains("lunar") || theme.contains("mars");
        return colony && (material == null || material.isBlank() || material.equals("asphalt")) ? COLONY_SLABS : material;
    }

    /**
     * Colony roads: a paved road, or a bridge deck's, on lunar or Martian ground, asphalt or without a finish of its own,
     * is laid in a colony's precast slabs ({@link #COLONY_SLABS}), written as its finish so the editor shows and changes
     * it.
     */
    private static void colonyRoads(Board board) {
        Map<BoardLocation, Hex> fixed = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                var styles = new LinkedHashMap<>(hex.getAppearance());
                for (var layer : Map.of(Terrains.ROAD, "road", Terrains.BRIDGE_ROAD, "bridgeRoad").entrySet()) {
                    int level = hex.terrainLevel(layer.getKey());
                    if (!hex.containsTerrain(layer.getKey()) || level == Terrains.ROAD_LVL_DIRT
                          || level == Terrains.ROAD_LVL_GRAVEL) {
                        continue;
                    }
                    var old = styles.get(layer.getValue());
                    String material = old == null || old.material() == null ? "" : old.material();
                    if (roadFinish(hex, material).equals(material)) { continue; }
                    styles.put(layer.getValue(), old == null ? new HexAppearance(null, null, COLONY_SLABS, null)
                          : HexAppearance.keepLanes(new HexAppearance(old.variant(), old.asset(), COLONY_SLABS, old.strength(),
                                old.colours(), old.rotation(), old.tracks(), old.beside(), old.trafficControl()), old));
                }
                if (styles.equals(hex.getAppearance())) { continue; }
                Hex result = replacement(hex);
                result.setAppearance(styles);
                fixed.put(BoardLocation.of(at, board.getBoardId()), result);
            }
        }
        if (!fixed.isEmpty()) { board.setHexes(fixed); }
    }

    /**
     * Whether the road of {@code next}, across {@code direction} from the bridge hex {@code bridge}, meets its deck: a
     * road on road ground ({@link #roadGround}) and on no deck of its own, within a level of the deck, which the
     * bridge hex does not continue on its own dry ground (the road then runs beside or beneath the deck), as the
     * automatic road exits join a deck ({@code Hex#setExits}).
     */
    private static boolean roadLink(Hex bridge, Hex next, int direction) {
        return next != null && next.containsTerrain(Terrains.ROAD) && !next.containsTerrain(Terrains.BRIDGE)
              && roadGround(next) && Math.abs(BridgeSpan.deck(bridge) - next.getLevel()) <= 1
              && (HexTile.liquid(bridge) || !bridge.containsTerrainExit(Terrains.ROAD, direction));
    }

    /** Ground a road leads along to a deck for the game's exits: dry, or a ford's depth-0 water. */
    private static boolean roadGround(Hex hex) {
        return wayGround(hex) && (!HexTile.liquid(hex) || hex.terrainLevel(Terrains.WATER) == 0);
    }

    /** Ground the 3D board lays a way on: dry, or under water at any depth, on its bed. */
    private static boolean wayGround(Hex hex) {
        return !HexTile.liquid(hex) || hex.terrainLevel(Terrains.MAGMA) != 2
              && !hex.containsTerrain(Terrains.HAZARDOUS_LIQUID);
    }

    /**
     * Decodes the legacy scenery tokens of {@code hexes}, whether or not the board is native (the editor converts the hexes
     * whose token an edit changed), then types every untyped bridge and marks the board native. Each decodable token
     * ({@link #imported}) becomes the objects its artwork shows, one group per artwork that has several, with ids
     * {@code L<x>_<y>_<n>}; the token stays on the hex for the 2D board, which keeps drawing its sprite. Rule terrain is
     * kept, except that a car park on plain ground gets its lane as a road ({@code laneRoad}), which runs between its rows
     * of bays and may write a neighbour's road exits; where no road is drawn the rows stand back to back
     * ({@link #roadside}). Artwork resolves with the 3D board's tileset ({@link TilesetScenery#resolve}). A legacy decal
     * ({@link BoardDecalArt#legacy}) becomes its decal: a one-hex variant at its scale and turn, in its paint colour, and
     * the pieces of a 7-hex emblem set ONE emblem on the set's centre hex, spanning its neighbours; a set missing a piece
     * on the board paints exactly its pieces (each the emblem clipped to its hex, one group), and one cut by the board
     * edge gets the full emblem and an issue. Legacy rail art becomes rail terrain with the art's sides
     * ({@link RailRoute#lay}), on the ground or its bridge's deck ({@link #railOnDeck}), and its token goes: the terrain
     * draws the rail sprite in 2D; art without sides joins the rails beside it ({@link #railJoins}) or lays nothing. On a
     * legacy (not native) board only, converted maglevs a level up or beside a row of pillar art stand on Pillars
     * ({@link #maglevPillars}) and a rail bridge carries its rail across its span ({@link #carryRails}), a crane tip that a
     * converted crane's boom reaches places nothing ({@link #reachedTips}), a building without connections is Square
     * ({@link #squareIsolatedBuildings}), minor objects step clear of the fitted concrete walls
     * ({@link #clearOfConcreteWalls}), and, once the bridges are typed, every deck gets the road the 3D
     * board drew on it ({@link #deckRoads}). Road junctions inherit the default overhead traffic lights. A token that a hex
     * design draws gets that design ({@link #design}): seaport container yards and cranes on woods become the woods'
     * container yard look at their rotation (a skin, {@link #skin}). A hex that already holds a twin of every object its art
     * decodes to ({@link BoardDecoration#twin}: a token typed again in the editor) gets none of them. A legacy board's
     * terrain aliases become their values first ({@link #aliases}). Every
     * bridge hex gets its type, decoded on the board as given ({@link #bridgeTypes}). Returns those issues. Thread-safe:
     * it changes only {@code board}.
     */
    public static List<HexIssue> convert(Board board, Collection<Coords> hexes) {
        boolean nativeBoard = board.isNativeFormat();
        List<Coords> scanned = hexes.stream().filter(board::contains).distinct()
              .sorted(Comparator.comparingInt(Coords::getY).thenComparingInt(Coords::getX)).toList();
        if (!nativeBoard) { aliases(board, scanned); }
        // Resolve every hex first: a set piece names its centre, which may come later in the scan or have no piece.
        Map<Coords, TilesetScenery.Scenery> scenery = new LinkedHashMap<>();
        for (Coords coords : scanned) {
            Hex hex = board.getHex(coords);
            // Only these tokens can select importable artwork; most hexes need no tileset resolution.
            if (hex.containsAnyTerrainOf(Terrains.FLUFF, Terrains.ROAD_FLUFF) || hex.terrainLevel(Terrains.ROAD) == 2) {
                scenery.put(coords, TilesetScenery.resolve(hex, nativeBoard).scenery());
            }
        }
        // Every bridge's type, decoded on the board as loaded: the lane roads below must not retype a span. Only fluff
        // can be pillar art, and it counts only on an untyped span (a stored type decides the span).
        Map<Coords, TilesetScenery.Scenery> pillarArt = new HashMap<>(scenery);
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                if (hex.containsAllTerrainsOf(Terrains.BRIDGE, Terrains.FLUFF) && !pillarArt.containsKey(at)
                      && HexAppearance.bridgeBuilt(hex.getAppearance()) == null) {
                    pillarArt.put(at, TilesetScenery.resolve(hex, nativeBoard).scenery());
                }
            }
        }
        Map<Coords, HexAppearance> bridges = bridgeTypes(board, pillarArt);
        // The car parks that get their lane as a road (laneRoad), all known before the conversion changes a hex.
        Map<Coords, Integer> lanes = new HashMap<>();
        scenery.forEach((coords, artwork) -> {
            Hex hex = board.getHex(coords);
            if (artwork.cosmeticRoadExits() != 0 && !hex.containsTerrain(Terrains.ROAD) && plainGround(hex)
                  && !HexTile.liquid(hex)) {
                lanes.put(coords, artwork.cosmeticRoadExits());
            }
        });
        // The crane tips the converted cranes' booms reach (legacy boards only), known before a hex changes.
        Set<Coords> reachedTips = nativeBoard ? Set.of() : reachedTips(board, scanned);
        // Emblem sets: owner hex -> emblem -> the piece directions found (-1 the centre piece). The owner is the set's
        // centre; a centre beyond the board edge (a cut set) is owned by its first piece's hex, at the centre's offset.
        Map<Coords, Map<String, Set<Integer>>> sets = new HashMap<>();
        Map<Coords, Coords> centres = new HashMap<>();
        scenery.forEach((coords, artwork) -> {
            for (var decal : artwork.decals()) {
                var legacy = BoardDecalArt.legacy(decal.key());
                if (!legacy.set() || !imported(decal.terrains(), board.getHex(coords))) { continue; }
                Coords centre = legacy.centre(coords);
                Coords owner = board.contains(centre) ? centre : centres.computeIfAbsent(centre, key -> coords);
                sets.computeIfAbsent(owner, key -> new TreeMap<>()).computeIfAbsent(legacy.decal(), key -> new TreeSet<>())
                      .add(legacy.direction());
            }
        });
        Map<Coords, Coords> centreOf = new HashMap<>();
        centres.forEach((centre, owner) -> centreOf.put(owner, centre));
        // A set missing a piece on the board paints exactly its pieces: each piece hex the set's emblem on the set's
        // centre, clipped to its own hex, one group per set. A piece beyond the board edge counts as there (a cut map or a
        // sheet seam, whose halves joinSheets merges), so a set cut by the edge keeps its one full emblem.
        Map<Coords, List<Piece>> pieces = new HashMap<>();
        sets.forEach((owner, emblems) -> emblems.entrySet().removeIf(set -> {
            Coords centre = centreOf.getOrDefault(owner, owner);
            List<Coords> present = new ArrayList<>();
            boolean missing = false;
            for (int direction = -1; direction < 6; direction++) {
                Coords member = direction < 0 ? centre : centre.translated(direction);
                if (set.getValue().contains(direction)) { present.add(member); }
                else { missing |= board.contains(member); }
            }
            if (missing) {
                String emblem = set.getKey(), group = "L" + owner.getX() + "_" + owner.getY() + "_set_"
                      + emblem.substring(emblem.lastIndexOf('/') + 1);
                present.forEach(at -> pieces.computeIfAbsent(at, key -> new ArrayList<>()).add(new Piece(emblem, centre, group)));
            }
            return missing;
        }));
        List<HexIssue> issues = new ArrayList<>();
        // The maglev markers this conversion places, and the rows of pillar art along legacy maglev art (maglevPillars).
        Map<Coords, List<String>> maglevs = new HashMap<>();
        Set<Coords> pillarRows = nativeBoard ? Set.of() : pillarRows(board, scenery);
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords coords = new Coords(x, y);
                var artwork = scenery.getOrDefault(coords, TilesetScenery.Scenery.EMPTY);
                if (artwork == TilesetScenery.Scenery.EMPTY && !sets.containsKey(coords)) { continue; }
                Hex hex = board.getHex(coords);
                List<BoardDecoration> added = new ArrayList<>();
                // Whether a decodable token was taken, even when its artwork leaves no object (pillar art on a bridge).
                boolean decoded = false;
                String prefix = "L" + x + "_" + y + "_";
                // Ids and groups fresh in the hex: art typed again beside a moved piece of its first conversion takes the
                // next free ones.
                Set<String> used = new HashSet<>();
                hex.getDecorations().forEach(object -> { used.add(object.id()); used.add(object.group()); });
                int[] next = { 0 };
                Supplier<String> ids = () -> {
                    String id;
                    do { id = prefix + next[0]++; } while (used.contains(id));
                    return id;
                };
                int keys = 0;
                boolean pillared = false;
                // A car park's rows stand beside the road along its lane (its own, or the lane the conversion gives it),
                // else back to back: no lane (2b/3b), off the ground, on a liquid, or a road level that is not drawn.
                boolean road = lanes.containsKey(coords) || artwork.cosmeticRoadExits() != 0 && roadside(hex);
                for (var model : artwork.sources()) {
                    if (!imported(model.terrains(), hex)) { continue; }
                    decoded |= model.terrains().contains(Terrains.FLUFF)
                          || model.terrains().contains(Terrains.ROAD_FLUFF) && hex.terrainLevel(Terrains.ROAD_FLUFF) == 3;
                    // A crane tip a converted crane's boom reaches places nothing: the crane's skin draws its boom.
                    if (reachedTips.contains(coords) && model.terrains().contains(Terrains.FLUFF)) { continue; }
                    // Pillar art on a bridge turns on the bridge's Pillars toggle (below) and places no objects.
                    if (bridgePillars(hex, model.key())) { pillared = true; continue; }
                    // A row of pillar art along a legacy maglev is that maglev's Pillars (maglevPillars), no objects either.
                    if (pillarRows.contains(coords) && LAYOUTS.get(model.key()).pillars()) { continue; }
                    // Every model key has rows: TilesetScenery counts only layouts.json keys as models.
                    Layout rows = layout(model.key(), hex);
                    while (used.contains(prefix + "g" + keys)) { keys++; }
                    String group = rows.pieces() > 1 ? prefix + "g" + keys : null;
                    keys++;
                    var objects = rows.decorations(coords, hex, ids, group, road);
                    added.addAll(model.key().startsWith(ROAD_TREES) ? clearTreesOfRail(objects, hex, coords, board::getHex) : objects);
                }
                // Rail art is laid as rail terrain on the ground or the deck (railOnDeck); art without sides (rail00)
                // joins the rails beside it, and lays nothing beside none.
                List<BoardDecoration> rails = new ArrayList<>(1);
                for (int i = added.size() - 1; i >= 0; i--) {
                    var rail = added.get(i);
                    if (RailRoute.kind(rail) != RailRoute.Kind.RAIL) { continue; }
                    added.remove(i);
                    int sides = rail.connections() != 0 ? rail.connections() : railJoins(board, coords, scenery);
                    if (sides != 0) { rails.add(rail.withConnections(sides)); }
                }
                for (var decal : artwork.decals()) {
                    if (!imported(decal.terrains(), hex)) { continue; }
                    // Every legacy decal token is decoded; a set piece's emblem is placed on its centre below.
                    decoded = true;
                    var legacy = BoardDecalArt.legacy(decal.key());
                    if (legacy.set()) { continue; }
                    var paint = new BoardDecoration(ids.get(), "decal", legacy.decal(), null, 0, 0, legacy.rotation(),
                          false, legacy.scale(), receiver(hex, true), 0, true);
                    added.add(legacy.colour() == null ? paint : paint.withColours(BoardDecoration.Colours.of(legacy.colour())));
                }
                int found = issues.size();
                for (var set : sets.getOrDefault(coords, Map.of()).entrySet()) {
                    Coords centre = centreOf.getOrDefault(coords, coords);
                    String id = ids.get();
                    // One emblem over the centre and its six neighbours, unclipped, on the owner's support.
                    added.add(new BoardDecoration(id, "decal", set.getKey(), null, offsetX(coords, centre),
                          offsetY(coords, centre), 0, false, 1, receiver(hex, true), 0, false));
                    if (set.getValue().size() < 7) {
                        issues.add(new HexIssue(coords, id, "Emblem set with " + set.getValue().size()
                              + " of 7 pieces: the full emblem is placed"));
                    }
                }
                for (Piece piece : pieces.getOrDefault(coords, List.of())) {
                    added.add(new BoardDecoration(ids.get(), "decal", piece.emblem(), null,
                          offsetX(coords, piece.centre()), offsetY(coords, piece.centre()), 0, false, 1, receiver(hex, true),
                          0, true, 0, 0, piece.group()));
                }
                // All or nothing: art whose every piece the hex holds already (a token typed again) adds no hidden copies,
                // nor the issues of its emblems again.
                if (added.stream().allMatch(object -> hex.getDecorations().stream().anyMatch(object::twin))) {
                    added.clear();
                    issues.subList(found, issues.size()).clear();
                }
                if (added.isEmpty() && !decoded) { continue; }
                added.stream().filter(object -> RailRoute.kind(object) == RailRoute.Kind.MAGLEV)
                      .forEach(marker -> maglevs.computeIfAbsent(coords, key -> new ArrayList<>()).add(marker.id()));
                // The decoded tokens stay: the 2D board keeps drawing their sprites; the 3D board draws the objects.
                Hex result = replacement(hex);
                List<BoardDecoration> objects = new ArrayList<>(hex.getDecorations());
                objects.addAll(added);
                result.setDecorations(objects);
                if (pillared) { result.setAppearance(appearance(result, artwork)); }
                for (var rail : rails) {
                    boolean deck = railOnDeck(board, coords, rail.connections(), scenery);
                    int sides = rail.connections();
                    if (!nativeBoard && !deck && HexTile.road(result) && !result.containsTerrain(Terrains.WATER)) {
                        int stubs = RailRoute.stranded(sides);
                        if ((stubs & ~result.getTerrain(Terrains.ROAD).getExits()) == 0) { sides &= ~stubs; }
                    }
                    if (sides != 0) {
                        sides = deck ? sides & RailRoute.bridgeExits(result) & 63 : sides;
                        RailRoute.lay(result, deck, sides, RailRoute.valid(sides, rail.tracks()) ? rail.tracks() : 0);
                    }
                }
                // The rail terrain draws the rail sprite in 2D, so its art's token goes.
                if (!rails.isEmpty() && result.terrainLevel(Terrains.FLUFF) == RAIL_ART) { result.removeTerrain(Terrains.FLUFF); }
                laneRoad(board, coords, result, lanes);
                board.setHex(coords, result);
            }
        }
        // Fix-ups of legacy art: a legacy board's conversion only, never a native board's typed tokens (the editor's).
        if (!nativeBoard) {
            maglevPillars(board, maglevs, pillarRows);
            carryRails(board, scanned);
            clearOfConcreteWalls(board, scanned);
            roadLinks(board);
            legacyBuildingSelectors(board, scanned);
            squareIsolatedBuildings(board, scanned);
            themedBuildings(board, scanned);
        }
        design(board, scanned);
        typeBridges(board, bridges);
        if (!nativeBoard) {
            deckRoads(board);
            colonyRoads(board);
        }
        maglevTerrain(board, maglevs, issues, !nativeBoard);
        board.setNativeFormat(true);
        return List.copyOf(issues);
    }

    /** Legacy roof selectors are isolated buildings; retired DropShip pieces leave only their underlying terrain. */
    private static void legacyBuildingSelectors(Board board, List<Coords> hexes) {
        Map<BoardLocation, Hex> changed = new LinkedHashMap<>();
        for (Coords at : hexes) {
            Hex hex = board.getHex(at);
            Terrain building = hex.getTerrain(Terrains.BUILDING);
            if (building == null || building.getExits() < 64 || building.getExits() > 75) { continue; }
            Hex next = replacement(hex);
            if (building.getExits() <= 68) {
                next.addTerrain(new Terrain(Terrains.BUILDING, building.getLevel(), true, 0));
            } else if (building.getLevel() == 4) {
                for (int type : hex.getTerrainTypes()) {
                    if (type == Terrains.BUILDING || Terrains.getName(type).startsWith("bldg_")) { next.removeTerrain(type); }
                }
            } else { continue; }
            changed.put(BoardLocation.of(at, board.getBoardId()), next);
        }
        if (!changed.isEmpty()) {
            board.setHexes(changed);
            board.initializeAll(null);
        }
    }

    /** A legacy board's building hex without connections, its own or a neighbour's, gets the Square footprint. */
    private static void squareIsolatedBuildings(Board board, List<Coords> hexes) {
        Map<BoardLocation, Hex> changed = new HashMap<>();
        for (Coords at : hexes) {
            Hex hex = board.getHex(at);
            if (!hex.containsTerrain(Terrains.BUILDING) || hex.getAppearance().containsKey("footprint")
                  || TilesetScenery.buildingConnected(hex, at, board::getHex)) { continue; }
            Hex next = replacement(hex);
            Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
            appearance.put("footprint", HexAppearance.SQUARE);
            next.setAppearance(appearance);
            changed.put(BoardLocation.of(at, board.getBoardId()), next);
        }
        if (!changed.isEmpty()) { board.setHexes(changed); }
    }

    /** The lunar theme's building designs, generated over whole buildings (the 3D board's BoardMultiHexBuilding). */
    static final String LUNAR_HABITAT = "building/lunar-habitat", GLASS_DOME = "building/glass-dome",
          BIODOME = "building/biodome";
    private static final Set<String> GLAZING = Set.of("scenery/roofs/skylight", "scenery/roofs/glass-roof-wide",
          "scenery/roofs/glass-dome");

    /**
     * Lunar bases (the lunar theme's sealed buildings): every standard building of a legacy board without a design of its own
     * becomes a lunar habitat, except a round one glazed over most of its hexes (skylights or glass roofs), which is a
     * glass dome: a full ring of hexes round a centre holds most of it. Two full rings make it a biodome. A dome's
     * glazing objects go, since the dome is its glass; the tokens stay for the 2D board. The old sealed buildings of
     * Mars and volcano boards use glass domes. Fortresses, hangars and gun emplacements keep their class kits.
     */
    private static void themedBuildings(Board board, List<Coords> hexes) {
        Set<Coords> done = new HashSet<>();
        Map<BoardLocation, Hex> designed = new HashMap<>();
        for (Coords start : hexes) {
            if (done.contains(start) || !themedBuilding(board.getHex(start))) { continue; }
            String theme = board.getHex(start).getTheme();
            Set<Coords> building = new LinkedHashSet<>(List.of(start));
            List<Coords> open = new ArrayList<>(building);
            while (!open.isEmpty()) {
                Coords at = open.removeLast();
                int exits = board.getHex(at).getTerrain(Terrains.BUILDING).getExits();
                for (int direction = 0; direction < 6; direction++) {
                    Coords next = at.translated(direction);
                    if ((exits & 1 << direction) == 0 || building.contains(next) || !board.contains(next)
                          || !themedBuilding(board.getHex(next)) || !theme.equals(board.getHex(next).getTheme())
                          || (board.getHex(next).getTerrain(Terrains.BUILDING).getExits() & 1 << (direction + 3) % 6) == 0) {
                        continue;
                    }
                    building.add(next);
                    open.add(next);
                }
            }
            done.addAll(building);
            int rings = 0;
            for (Coords centre : building) {
                int full = 0;
                while (within(building, centre, full + 1) == 1 + 3 * (full + 1) * (full + 2)) { full++; }
                rings = Math.max(rings, full);
            }
            int disc = 1 + 3 * rings * (rings + 1);
            long glazed = building.stream().filter(member -> glazed(board.getHex(member))).count();
            boolean lunar = "lunar".equals(theme);
            boolean dome = !lunar || rings >= 1 && disc * 5 >= building.size() * 3 && glazed * 2 >= building.size();
            String variant = !dome ? LUNAR_HABITAT : lunar && rings >= 2 ? BIODOME : GLASS_DOME;
            for (Coords member : building) {
                Hex next = replacement(board.getHex(member));
                Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
                appearance.put("building", new HexAppearance(variant, null, null, null));
                next.setAppearance(appearance);
                if (dome) {
                    next.setDecorations(next.getDecorations().stream().filter(object -> !GLAZING.contains(object.asset()))
                          .toList());
                }
                designed.put(BoardLocation.of(member, board.getBoardId()), next);
            }
        }
        if (!designed.isEmpty()) { board.setHexes(designed); }
    }

    private static int within(Collection<Coords> hexes, Coords centre, int distance) {
        int result = 0;
        for (Coords hex : hexes) { if (hex.distance(centre) <= distance) { result++; } }
        return result;
    }

    /** A standard sealed building, excluding explicit art. */
    private static boolean themedBuilding(Hex hex) {
        return ("lunar".equals(hex.getTheme()) || "mars".equals(hex.getTheme()) || "volcano".equals(hex.getTheme()))
              && hex.containsTerrain(Terrains.BUILDING) && hex.terrainLevel(Terrains.BLDG_CLASS) <= 0
              && hex.terrainLevel(Terrains.BUILDING) != megamek.common.enums.BuildingType.WALL.getTypeValue()
              && hex.getTerrain(Terrains.BUILDING).getExits() < 64
              && !hex.containsTerrain(Terrains.BLDG_FLUFF)
              && !hex.getAppearance().containsKey("building");
    }

    /** Skylights (fluff 6, variants 0 to 5) or glass roofs (fluff 90) over the hex. */
    private static boolean glazed(Hex hex) {
        var fluff = hex.getTerrain(Terrains.FLUFF);
        return fluff != null && (fluff.getLevel() == 6 && fluff.getExits() <= 5 || fluff.getLevel() == 90);
    }

    /** The 3D board's preference among the roads a deck took from its approaches: dirt, gravel, alley, paved (ROAD levels). */
    private static final List<Integer> DECK_ROAD_RANK = List.of(Terrains.ROAD_LVL_DIRT, Terrains.ROAD_LVL_GRAVEL, 2, 1);

    /**
     * Legacy fix-up: each deck carries the road the 3D board drew on it before decks stored their roads, over all its
     * bridge exits ({@link Terrains#BRIDGE_ROAD}). A built span takes the best road (paved, alley, gravel, dirt) that meets
     * one of its ends at deck height ({@link #meets}) or that the map lays on the span's own hexes where no ground
     * carries it: over a liquid, or where the deck lies on the ground ({@link #onDeck}); a road level that is not drawn
     * counts as paved. One that no road meets an Alley road, unless rails meet it or ride it: a rail bridge's deck
     * stays bare. A natural span carries none.
     */
    private static void deckRoads(Board board) {
        Function<Coords, Hex> hexes = at -> board.contains(at) ? board.getHex(at) : null;
        Set<Coords> seen = new HashSet<>();
        Map<BoardLocation, Hex> paved = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                if (!board.getHex(at).containsTerrain(Terrains.BRIDGE) || seen.contains(at)) { continue; }
                List<Coords> span = BridgeSpan.of(hexes, at);
                seen.addAll(span);
                if (Boolean.FALSE.equals(BridgeSpan.type(hexes, span))) { continue; }
                int best = 0;
                boolean rail = false;
                for (Coords member : span) {
                    Hex hex = hexes.apply(member);
                    rail |= RailRoute.deckRail(hex);
                    rail |= hex.getDecorations().stream().anyMatch(object -> RailRoute.kind(object) == RailRoute.Kind.MAGLEV
                          && object.placement().level(hex) == hex.getLevel() + hex.terrainLevel(Terrains.BRIDGE_ELEV));
                    if (onDeck(hex)) { best = better(best, hex); }
                    for (int d = 0; d < 6; d++) {
                        Hex next = hexes.apply(member.translated(d));
                        if (next == null || decksJoin(hex, next, d) || !meets(next, hex, (d + 3) % 6)) { continue; }
                        if (!next.containsTerrain(Terrains.ROAD) || (next.getTerrain(Terrains.ROAD).getExits() & 63) == 0) {
                            rail = true;
                            continue;
                        }
                        best = better(best, next);
                    }
                }
                int level = best != 0 ? best : rail ? 0 : 2;
                if (level == 0) { continue; }
                for (Coords member : span) {
                    Hex hex = replacement(board.getHex(member));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, level, true,
                          hex.getTerrain(Terrains.BRIDGE).getExits() & 63));
                    paved.put(BoardLocation.of(member, board.getBoardId()), hex);
                }
            }
        }
        if (!paved.isEmpty()) { board.setHexes(paved); }
    }

    /**
     * Whether a bridge hex's own ROAD is its deck's: a legacy map lays the road a span carries on its hexes, and where
     * no ground can carry it, over a liquid or with the deck on the ground, it is the deck's. Under a raised deck on
     * dry ground it is another road, passing under the bridge.
     */
    private static boolean onDeck(Hex bridge) {
        return bridge.containsTerrain(Terrains.ROAD) && (bridge.getTerrain(Terrains.ROAD).getExits() & 63) != 0
              && (HexTile.liquid(bridge) || BridgeSpan.deck(bridge) == bridge.getLevel());
    }

    /** {@code best}, or the ROAD level of {@code road} where the deck prefers it ({@link #DECK_ROAD_RANK}). */
    private static int better(int best, Hex road) {
        int level = HexTile.road(road) ? road.terrainLevel(Terrains.ROAD) : 1;
        if (!DECK_ROAD_RANK.contains(level)) { level = 1; }
        return DECK_ROAD_RANK.indexOf(level) > DECK_ROAD_RANK.indexOf(best) ? level : best;
    }

    /** Whether the decks of {@code hex} and {@code next} join across {@code direction}: one look span of the 3D board. */
    private static boolean decksJoin(Hex hex, Hex next, int direction) {
        return next.containsTerrain(Terrains.BRIDGE) && BridgeSpan.joined(hex.getTerrain(Terrains.BRIDGE).getExits(),
              BridgeSpan.deck(hex), next.getTerrain(Terrains.BRIDGE).getExits(), BridgeSpan.deck(next), direction);
    }

    /**
     * Whether a road or ground rail of {@code way} leaves by {@code direction} onto the deck of the bridge hex
     * {@code bridge}, as the 3D board joins a way to a deck: on its ground ({@link #wayGround}), the deck exiting back
     * within a level of that ground, and the bridge hex not continuing the way on its own ground at the same level.
     */
    private static boolean meets(Hex way, Hex bridge, int direction) {
        if (!wayGround(way) || (ways(way) & (1 << direction)) == 0) { return false; }
        int back = (direction + 3) % 6;
        boolean continued = !HexTile.liquid(bridge) && (ways(bridge) & (1 << back)) != 0;
        int deck = BridgeSpan.deck(bridge);
        return !(continued && way.floor() == bridge.getLevel()) && Math.abs(deck - way.floor()) <= 1
              && (!continued || deck == way.floor()) && (RailRoute.bridgeExits(bridge) & (1 << back)) != 0;
    }

    /** The sides of a hex's road and of its rails on the ground ({@link RailRoute#groundSides}). */
    private static int ways(Hex hex) {
        return (hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0)
              | RailRoute.groundSides(hex);
    }

    /**
     * The crane tips ({@code fluff:82}, the legacy art of a crane's boom end) that a converted crane's boom reaches: a
     * hex whose FLUFF converts to a skin ({@link BoardEditorBlueprint#reproducing}) reaches the neighbour whose centre a
     * row of that skin ({@link #skin}) lies nearer than its own hex's centre, a quay crane's boom. The skin draws the
     * whole crane, so such a tip's token is taken and places nothing; any other tip stays an object. Of {@code hexes}.
     */
    private static Set<Coords> reachedTips(Board board, List<Coords> hexes) {
        Set<Coords> result = new HashSet<>();
        for (Coords at : hexes) {
            Hex hex = board.getHex(at);
            Terrain fluff = hex.getTerrain(Terrains.FLUFF);
            Layout skin = fluff == null ? null : skin(hex, BoardEditorBlueprint.get().reproducing(hex, fluff));
            if (skin == null) { continue; }
            for (Component row : skin.components()) {
                Coords nearest = null;
                double distance = Math.hypot(row.x(), row.y());
                for (int direction = 0; direction < 6; direction++) {
                    Coords next = at.translated(direction);
                    double apart = Math.hypot(row.x() - offsetX(at, next) * TILE_WIDTH, row.y() - offsetY(at, next) * TILE_HEIGHT);
                    if (apart < distance) { nearest = next; distance = apart; }
                }
                if (nearest != null && board.contains(nearest) && board.getHex(nearest).terrainLevel(Terrains.FLUFF) == CRANE_TIP) {
                    result.add(nearest);
                }
            }
        }
        return result;
    }

    /**
     * A legacy board's terrain aliases, which the rules read only as present: pavement 4 (the old Runway, its corner
     * designs with their typed exits included) is pavement 1, every pavement level from 5 up is the plaza, 3, and mud 2 is
     * mud 1. Pavement's exits are derived again; mud keeps its own. A wall, which a legacy board gives no weight, gets
     * the one its construction factor belongs to ({@link TilesetScenery#buildingWeight}).
     */
    private static void aliases(Board board, List<Coords> hexes) {
        Map<BoardLocation, Hex> fixed = new HashMap<>();
        for (Coords at : hexes) {
            Hex hex = board.getHex(at);
            int pavement = hex.terrainLevel(Terrains.PAVEMENT);
            Terrain mud = hex.getTerrain(Terrains.MUD);
            boolean muddy = mud != null && mud.getLevel() == 2;
            boolean wall = hex.terrainLevel(Terrains.BUILDING) == megamek.common.enums.BuildingType.WALL.getTypeValue()
                  && !hex.containsTerrain(Terrains.BLDG_WEIGHT);
            if (pavement < 4 && !muddy && !wall) { continue; }
            Hex next = replacement(hex);
            if (wall) { next.addTerrain(new Terrain(Terrains.BLDG_WEIGHT, TilesetScenery.buildingWeight(hex))); }
            if (pavement >= 4) { next.addTerrain(new Terrain(Terrains.PAVEMENT, pavement == 4 ? 1 : 3)); }
            if (muddy) { next.addTerrain(new Terrain(Terrains.MUD, 1, mud.hasExitsSpecified(), mud.getExits())); }
            fixed.put(BoardLocation.of(at, board.getBoardId()), next);
        }
        if (!fixed.isEmpty()) { board.setHexes(fixed); }
    }

    /**
     * The hexes whose pillar art is a row of posts along a legacy maglev line (Karkand 1208-1218): pillar art, not on a
     * bridge, beside legacy maglev art whose line runs past it (none of its sides points at the pillar hex, so not at the
     * line's end), with a neighbour of the same kind, so a row of two or more; a lone pillar hex, or pillar art where a
     * line ends (Ishiyama's pillar field), stays plain pillars. By the scan's {@code scenery}.
     */
    private static Set<Coords> pillarRows(Board board, Map<Coords, TilesetScenery.Scenery> scenery) {
        // The sides of a hex's legacy maglev art, 0 without one.
        java.util.function.ToIntFunction<Coords> maglev = at -> scenery.getOrDefault(at, TilesetScenery.Scenery.EMPTY)
              .models().stream().flatMap(key -> LAYOUTS.get(key).components().stream())
              .filter(row -> RailRoute.Kind.of(row.asset()) == RailRoute.Kind.MAGLEV)
              .mapToInt(Component::connections).reduce(0, (a, b) -> a | b);
        Set<Coords> beside = new HashSet<>();
        scenery.forEach((at, artwork) -> {
            if (artwork.models().stream().noneMatch(key -> LAYOUTS.get(key).pillars() && !bridgePillars(board.getHex(at), key))) {
                return;
            }
            for (int direction = 0; direction < 6; direction++) {
                int sides = maglev.applyAsInt(at.translated(direction));
                if (sides != 0 && (sides & RailRoute.side((direction + 3) % 6)) == 0) { beside.add(at); return; }
            }
        });
        Set<Coords> rows = new HashSet<>();
        for (Coords at : beside) {
            for (int direction = 0; direction < 6; direction++) {
                if (beside.contains(at.translated(direction))) { rows.add(at); break; }
            }
        }
        return rows;
    }

    /**
     * Converted maglevs on Pillars (batch-10 answer 7), set as the editor's toggle sets them, on a whole connected run
     * ({@link RailRoute#run}): a run with a converted marker a level or more above its hex's ground, and a run beside
     * a row of legacy pillar art ({@link #pillarRows}, posts along the line as on Karkand). The pillar art is read as the
     * posts that carry the line, so such a run's converted markers that follow the ground stand a level above it, with
     * the maglev pieces that ride them; without that the piers would have no room. {@code maglevs} are the converted
     * markers' ids by hex.
     */
    private static void maglevPillars(Board board, Map<Coords, List<String>> maglevs, Set<Coords> pillarRows) {
        Map<Coords, Hex> changed = new HashMap<>();
        Function<Coords, Hex> hexes = at -> changed.containsKey(at) ? changed.get(at) : board.contains(at) ? board.getHex(at) : null;
        Predicate<RailRoute.Member> converted = member -> maglevs.getOrDefault(member.at(), List.of()).contains(member.marker().id());
        Set<String> done = new HashSet<>();
        maglevs.forEach((at, ids) -> ids.forEach(id -> {
            if (done.contains(at + id)) { return; }
            var marker = hexes.apply(at).getDecorations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
            var run = legacyMaglevRun(hexes, at, marker);
            run.forEach(member -> done.add(member.at() + member.marker().id()));
            boolean posts = run.stream().anyMatch(member -> {
                for (int d = 0; d < 6; d++) {
                    if (pillarRows.contains(member.at().translated(d))) { return true; }
                }
                return false;
            });
            boolean elevated = run.stream().anyMatch(member -> converted.test(member)
                  && member.marker().placement().level(hexes.apply(member.at())) - hexes.apply(member.at()).getLevel() >= 1);
            if (!posts && !elevated) { return; }
            var raised = BoardDecoration.Placement.on("ground", 1);
            for (var member : run) {
                Hex hex = hexes.apply(member.at()).duplicate();
                var placement = member.marker().placement();
                boolean lift = posts && converted.test(member) && placement.equals(BoardDecoration.Placement.ground());
                hex.setDecorations(hex.getDecorations().stream().map(object -> {
                    if (object.id().equals(member.marker().id())) {
                        var pillared = object.withPillars(true);
                        return lift ? pillared.transform(object.x(), object.y(), object.rotation(), object.mirror(),
                              object.scale(), raised) : pillared;
                    }
                    // The pieces that ride the lifted marker, at its placement, rise with it.
                    return lift && RailRoute.rides(object.asset()) == RailRoute.Kind.MAGLEV && object.placement().equals(placement)
                          ? object.transform(object.x(), object.y(), object.rotation(), object.mirror(), object.scale(), raised)
                          : object;
                }).toList());
                changed.put(member.at(), hex);
            }
        }));
        Map<BoardLocation, Hex> result = new HashMap<>();
        changed.forEach((at, hex) -> result.put(BoardLocation.of(at, board.getBoardId()), hex));
        if (!result.isEmpty()) { board.setHexes(result); }
    }

    /** Only the converter reads temporary route objects; runtime routes come exclusively from terrain. */
    private static List<RailRoute.Member> legacyMaglevRun(Function<Coords, Hex> hexes, Coords start,
          BoardDecoration marker) {
        List<RailRoute.Member> run = new ArrayList<>(List.of(new RailRoute.Member(start, marker)));
        Set<RailRoute.Member> found = new HashSet<>(run);
        for (int i = 0; i < run.size(); i++) {
            var member = run.get(i);
            for (int d = 0; d < 6; d++) {
                Coords at = member.at().translated(d);
                Hex hex = hexes.apply(at);
                if (hex == null || (member.marker().connections() & RailRoute.side(d)) == 0) { continue; }
                for (var other : hex.getDecorations()) {
                    if (RailRoute.joined(member.marker(), d, other)) {
                        var next = new RailRoute.Member(at, other);
                        if (found.add(next)) { run.add(next); }
                    }
                }
            }
        }
        return run;
    }

    /** Converts only the maglev art placed by this import; a native board is otherwise read exactly as authored. */
    private static void maglevTerrain(Board board, Map<Coords, List<String>> maglevs, List<HexIssue> issues,
          boolean legacy) {
        Map<BoardLocation, Hex> changed = new HashMap<>();
        maglevs.forEach((at, ids) -> {
            Hex hex = replacement(board.getHex(at));
            boolean drawnBridge = hex.containsTerrain(Terrains.BRIDGE) && !HexAppearance.implicit(hex.getAppearance());
            List<BoardDecoration> objects = new ArrayList<>(hex.getDecorations());
            for (String id : ids) {
                BoardDecoration marker = objects.stream().filter(object -> object.id().equals(id)).findFirst().orElseThrow();
                double level = marker.placement().level(hex);
                if (level < hex.getLevel()) {
                    issues.add(new HexIssue(at, id, "Maglev below the ground was moved onto the ground"));
                    level = hex.getLevel();
                }
                int elevation = (int) Math.round(level - hex.getLevel());
                boolean deck = elevation > 0 || hex.terrainLevel(Terrains.WATER) > 0
                      || hex.containsTerrain(Terrains.BRIDGE)
                            && elevation == hex.terrainLevel(Terrains.BRIDGE_ELEV);
                boolean existing = hex.containsTerrain(Terrains.BRIDGE);
                int layer = deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
                String refusal = deck && hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK)
                      ? "Maglev over a building or fuel tank was omitted: one structure per hex"
                      : deck && existing && elevation != hex.terrainLevel(Terrains.BRIDGE_ELEV)
                            ? "Maglev was omitted: one deck per hex"
                            : hex.containsTerrain(layer) && hex.terrainLevel(layer) != 2
                                  ? "Maglev was omitted: one rail kind per layer" : null;
                objects.remove(marker);
                if (refusal != null) {
                    issues.add(new HexIssue(at, id, refusal));
                    objects.removeIf(object -> RailRoute.rides(object.asset()) == RailRoute.Kind.MAGLEV
                          && object.placement().equals(marker.placement()));
                    continue;
                }
                int sides = marker.connections() & 63;
                if (deck && drawnBridge) {
                    Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
                    if (legacy && (sides & ~bridge.getExits()) != 0) {
                        // Legacy artwork can cross a bridge sideways. Preserve both routes on the existing deck;
                        // its road keeps its original exits, while the structure also carries the maglev arms.
                        hex.addTerrain(new Terrain(Terrains.BRIDGE, bridge.getLevel(), true, bridge.getExits() | sides));
                        issues.add(new HexIssue(at, id, "Bridge exits were widened to carry the legacy maglev crossing"));
                    } else {
                        sides &= RailRoute.bridgeExits(hex);
                    }
                }
                int tracks = marker.tracks();
                if (hex.containsTerrain(layer)) {
                    int oldSides = hex.getTerrain(layer).getExits();
                    int oldTracks = HexAppearance.tracks(hex.getAppearance(), deck ? "bridgeRail" : "rail");
                    sides |= hex.getTerrain(layer).getExits();
                    tracks = RailRoute.layout(sides, RailRoute.pieces(marker, marker.connections())
                          | (oldTracks != 0 && RailRoute.valid(oldSides, oldTracks) ? oldTracks : RailRoute.pieces(oldSides)));
                }
                RailRoute.lay(hex, deck, sides, RailRoute.valid(sides, tracks) ? tracks : 0, RailRoute.Kind.MAGLEV);
                if (deck) {
                    BridgeSpan.carry(hex, elevation, "bridge:4;bridge_cf:650");
                    if (!existing && marker.pillars()) {
                        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
                        appearance.put("bridge", HexAppearance.IMPLICIT_PILLARS);
                        hex.setAppearance(appearance);
                    }
                }
                var placement = deck ? BoardDecoration.Placement.on("bridge", 0) : BoardDecoration.Placement.ground();
                objects.replaceAll(object -> RailRoute.rides(object.asset()) == RailRoute.Kind.MAGLEV
                      && object.placement().equals(marker.placement())
                            ? object.transform(object.x(), object.y(), object.rotation(), object.mirror(), object.scale(), placement)
                            : object);
            }
            hex.setDecorations(objects);
            // Rule terrain draws the track; its trains/platforms remain the separately authored objects.
            if (hex.terrainLevel(Terrains.FLUFF) == 9 && hex.getTerrain(Terrains.FLUFF).getExits() < 3) {
                hex.removeTerrain(Terrains.FLUFF);
            }
            changed.put(BoardLocation.of(at, board.getBoardId()), hex);
        });
        if (!changed.isEmpty()) { board.setHexes(changed); }
    }

    /**
     * Moves the minor objects of {@code hexes} that the fitted concrete ({@link ConcreteFit}, as the 3D board fits this
     * board without pins) would cut, by at least 4 px² inside their hex ({@link ConcreteFit#reaches}), the shortest way
     * clear of the walls and banks of their hex ({@link ConcreteFit#clearance}), so the 3D board keeps the fitted outline
     * there. The members of a group in the hex move as one body (the hull of their footprints), by one offset, so the
     * art's layout stays. Objects that follow the hex sides ({@link #followsHexSides}) stay and pin their hex in the 3D
     * board, as does an object without a model (missing or unreadable on the converting machine), one that would move
     * more than 42 px, and every member of a group with such a member. A legacy board's conversion only: a .board2 is
     * drawn as authored.
     */
    private static void clearOfConcreteWalls(Board board, List<Coords> hexes) {
        ConcreteFit fit = new ConcreteFit(board.getWidth(), board.getHeight(), ConcreteFit.cells(board), 1, true, Set.of());
        for (Coords at : hexes) {
            if (fit.footprint(at) == null) { continue; }
            Hex hex = board.getHex(at);
            List<BoardDecoration> objects = new ArrayList<>(hex.getDecorations());
            // The bodies that move: each group's members, and each object without a group on its own.
            Map<Object, List<Integer>> bodies = new LinkedHashMap<>();
            for (int i = 0; i < objects.size(); i++) {
                String group = objects.get(i).group();
                bodies.computeIfAbsent(group == null ? i : group, key -> new ArrayList<>()).add(i);
            }
            boolean moved = false;
            for (List<Integer> body : bodies.values()) {
                List<ConcreteFit.Point> points = new ArrayList<>();
                boolean reached = false;
                for (int i : body) {
                    BoardDecoration object = objects.get(i);
                    List<ConcreteFit.Point> footprint = followsHexSides(object) ? null : BoardModelBounds.footprint(at, object);
                    if (footprint == null) {
                        points = null;
                        break;
                    }
                    points.addAll(footprint);
                    reached |= fit.reaches(footprint, at);
                }
                if (points == null || !reached) { continue; }
                ConcreteFit.Shift move = fit.clearance(ConcreteFit.hull(points), at);
                if (move == null || move.equals(ConcreteFit.Shift.ZERO)) { continue; }
                for (int i : body) {
                    BoardDecoration object = objects.get(i);
                    objects.set(i, object.transform(object.x() + move.x() / TILE_WIDTH, object.y() + move.y() / TILE_HEIGHT,
                          object.rotation(), object.mirror(), object.scale(), object.placement()));
                }
                moved = true;
            }
            if (moved) {
                Hex result = replacement(hex);
                result.setDecorations(objects);
                board.setHex(at, result);
            }
        }
    }

    /**
     * Objects whose shape follows the hex sides: route markers (rails, maglevs) and the pieces that ride them
     * ({@link RailRoute#rides}), decals, roof parts and seaport pieces. The legacy conversion never moves them off their
     * place ({@link #clearOfConcreteWalls}).
     */
    private static boolean followsHexSides(BoardDecoration object) {
        return RailRoute.Kind.of(object.asset()) != null || RailRoute.rides(object.asset()) != null
              || object.kind().equals("decal") || object.asset().startsWith("scenery/roofs/")
              || object.asset().startsWith("scenery/seaport/");
    }

    /**
     * A rail bridge carries its rail across the whole span (batch-10 answer 3): where the rail enters a bridge span
     * ({@link BridgeSpan#of}) along its deck at both of its two ends, every span hex rides that rail on its deck along all
     * its bridge exits, also where the legacy art left the middle hexes without rail (45x45 Desert Mesa Oasis). An end
     * enters along the deck where its hex's rail leads out that way on the deck, or, without a rail there, the rail of
     * the hex beyond leads onto it within a level of the deck. A span with a rail beneath its deck keeps its rails as
     * they are. Only spans with a hex in {@code hexes}, the hexes this conversion decodes.
     */
    private static void carryRails(Board board, List<Coords> hexes) {
        Set<Coords> seen = new HashSet<>();
        Map<BoardLocation, Hex> carried = new HashMap<>();
        for (Coords start : hexes) {
            if (!board.getHex(start).containsTerrain(Terrains.BRIDGE) || seen.contains(start)) { continue; }
            List<Coords> span = BridgeSpan.of(board::getHex, start);
            seen.addAll(span);
            int ends = 0, entering = 0;
            boolean beneath = false;
            for (Coords member : span) {
                Hex hex = board.getHex(member);
                var rail = RailRoute.find(hex, RailRoute.Kind.RAIL);
                beneath |= rail != null && !RailRoute.onDeck(rail);
                int exits = RailRoute.bridgeExits(hex);
                for (int d = 0; d < 6; d++) {
                    Coords next = member.translated(d);
                    if ((exits & RailRoute.side(d)) == 0 || span.contains(next)) { continue; }
                    ends++;
                    var bank = board.contains(next) ? RailRoute.find(board.getHex(next), RailRoute.Kind.RAIL) : null;
                    boolean enters = rail != null ? (rail.connections() & RailRoute.side(d)) != 0
                          : bank != null && (bank.connections() & RailRoute.side((d + 3) % 6)) != 0
                                && Math.abs(board.getHex(next).getLevel() - BridgeSpan.deck(hex)) <= 1;
                    if (enters) { entering++; }
                }
            }
            if (ends != 2 || entering != 2 || beneath) { continue; }
            for (Coords member : span) {
                Hex hex = board.getHex(member).duplicate();
                int exits = RailRoute.bridgeExits(hex) & 63;
                var rail = RailRoute.find(hex, RailRoute.Kind.RAIL);
                if (rail != null && (rail.connections() & exits) == exits) { continue; }
                RailRoute.lay(hex, true, exits, rail == null ? 0 : rail.tracks());
                carried.put(BoardLocation.of(member, board.getBoardId()), hex);
            }
        }
        if (!carried.isEmpty()) { board.setHexes(carried); }
    }

    /**
     * The one pass after sheets become one board: {@code BoardUtilities.combine} (every game board assembly) and an old
     * save's boards after their conversion ({@code Game.initializeAfterLoad}). Sheets are converted one by one, before
     * they are flipped and combined, so a bridge or an emblem that crosses a seam was decoded in two halves:
     * <ul>
     *     <li>a bridge span ({@link BridgeSpan#of}) with a built hex makes its other hexes built, since the decode is an
     *     OR over a span's hexes ({@link BridgeSpan#decode}); a hex with piers keeps them, one without gets none;</li>
     *     <li>set emblems that are twins at their world centre on one support ({@link BoardDecoration#twin}: each half
     *     of a set cut by a seam placed the full emblem on the set's centre) become one, the first in scan order.</li>
     * </ul>
     * Halves on a flipped sheet do not coincide and stay as they are.
     */
    public static void joinSheets(Board board) {
        Function<Coords, Hex> hexes = board::getHex;
        Set<Coords> spans = new HashSet<>();
        Map<BoardLocation, Hex> built = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                if (!board.getHex(at).containsTerrain(Terrains.BRIDGE) || spans.contains(at)) { continue; }
                List<Coords> span = BridgeSpan.of(hexes, at);
                spans.addAll(span);
                if (!Boolean.TRUE.equals(BridgeSpan.type(hexes, span))) { continue; }
                for (Coords member : span) {
                    Hex hex = board.getHex(member);
                    if (Boolean.TRUE.equals(HexAppearance.bridgeBuilt(hex.getAppearance()))) { continue; }
                    Hex next = replacement(hex);
                    Map<String, HexAppearance> appearance = new HashMap<>(next.getAppearance());
                    appearance.put("bridge", HexAppearance.BUILT_BRIDGE);
                    next.setAppearance(appearance);
                    built.put(BoardLocation.of(member, board.getBoardId()), next);
                }
            }
        }
        if (!built.isEmpty()) { board.setHexes(built); }
        // Each emblem kept so far, at its world centre in hex widths and heights, on one support: the halves of a set take
        // their owner hex's support, and two owner hexes may differ (a roof against the ground).
        List<BoardDecoration> emblems = new ArrayList<>();
        Map<BoardLocation, Hex> merged = new HashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                List<BoardDecoration> kept = hex.getDecorations().stream().filter(object -> {
                    if (!object.kind().equals("decal") || object.clipToHex() || !BoardDecalArt.setEmblem(object.asset())) {
                        return true;
                    }
                    var world = object.transform(HexTile.centerX(at, 1) + object.x(), HexTile.centerY(at, 1) + object.y(),
                          object.rotation(), object.mirror(), object.scale(), BoardDecoration.Placement.ground());
                    boolean twin = emblems.stream().anyMatch(world::twin);
                    if (!twin) { emblems.add(world); }
                    return !twin;
                }).toList();
                if (kept.size() < hex.getDecorations().size()) {
                    Hex next = replacement(hex);
                    next.setDecorations(kept);
                    merged.put(BoardLocation.of(at, board.getBoardId()), next);
                }
            }
        }
        if (!merged.isEmpty()) { board.setHexes(merged); }
    }

    /**
     * Which layers become objects ({@link Taken#OBJECTS}): decodable FLUFF (not server rubble), roadside-tree
     * ROAD_FLUFF 3, and the legacy road-with-trees art of ROAD level 2. Everything else is terrain-owned or kept.
     */
    public static boolean imported(Set<Integer> terrains, Hex hex) {
        if (terrains.contains(Terrains.FLUFF)) {
            int level = hex.terrainLevel(Terrains.FLUFF);
            return level < SERVER;
        }
        if (terrains.contains(Terrains.ROAD_FLUFF)) { return hex.terrainLevel(Terrains.ROAD_FLUFF) == 3; }
        return terrains.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) == 2;
    }

    /** What the conversion does with a legacy art token it takes ({@link #takenTokens}). */
    public enum Taken {
        /**
         * Its artwork became objects ({@link #imported}): rows, a legacy decal, pillar art's toggle; or nothing, for a
         * crane tip a converted crane's boom reaches ({@link #reachedTips}).
         */
        OBJECTS,
        /** A hex design draws it ({@link #designs}). */
        DESIGN,
        /** It draws nothing: the tileset's transparent wildcard, or building art no design stands for. */
        DROP
    }

    /** Terrains that are legacy art and nothing else: the tokens the conversion takes ({@link #takenTokens}). */
    private static final int[] ART_TERRAINS = { Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF,
          Terrains.WATER_FLUFF, Terrains.BLDG_FLUFF };
    /** FLUFF and GROUND_FLUFF from here up are game state written in play (cleared rubble), never legacy art. */
    private static final int SERVER = 2000;
    /** The FLUFF level of the legacy crane tips, the end of a crane's boom over the next hex ({@link #reachedTips}). */
    private static final int CRANE_TIP = 82;
    /** The FLUFF level of legacy rail art ({@code fluff:10:NN}), which the conversion lays as rail terrain. */
    private static final int RAIL_ART = 10;

    /** The hex's legacy art tokens, in {@link #ART_TERRAINS} order: every art terrain but a server value. */
    private static List<Integer> tokens(Hex hex) {
        List<Integer> result = new ArrayList<>(2);
        for (int type : ART_TERRAINS) {
            if (hex.containsTerrain(type) && (type == Terrains.ROAD_FLUFF || type == Terrains.WATER_FLUFF
                  || type == Terrains.BLDG_FLUFF || hex.terrainLevel(type) < SERVER)) { result.add(type); }
        }
        return result;
    }

    /** The hex's scenery layers, as a converted board resolves them, when it holds a scenery art token; else none. */
    private static List<TilesetScenery.Layer> layers(Hex hex) {
        if (!hex.containsAnyTerrainOf(Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF)) {
            return List.of();
        }
        // A converted board is native: road level 2 is the Alley finish, and it is never a token.
        return TilesetScenery.resolve(hex, true).layers();
    }

    /**
     * The one statement of what the conversion takes: each legacy art token of {@code hex} (FLUFF and GROUND_FLUFF
     * below the server values, ROAD_FLUFF, WATER_FLUFF, BLDG_FLUFF) that it takes, by terrain type, with what stands for
     * it, decided in this order:
     * <ol>
     *     <li>{@link Taken#OBJECTS}: a layer that matched it is taken ({@link TilesetScenery.Layer#taken}, the predicate
     *     of {@link #convert} and of the 3D board, which skips that layer): FLUFF, and ROAD_FLUFF level 3;</li>
     *     <li>{@link Taken#DESIGN}: a hex design draws exactly its art ({@link #designs}); {@link #convert} writes it
     *     unless the owner has a design by then, which wins, the token still being taken;</li>
     *     <li>{@link Taken#DROP}: it draws nothing, every layer that matched it being the transparent wildcard
     *     ({@link TilesetScenery.Layer#blank}); or it is building art that no design stands for.</li>
     * </ol>
     * Any other token is kept: on the shipped boards none.
     */
    public static Map<Integer, Taken> takenTokens(Hex hex) {
        if (tokens(hex).isEmpty()) { return Map.of(); }
        List<TilesetScenery.Layer> layers = layers(hex);
        return taken(hex, layers, designs(hex, layers));
    }

    /** {@link #takenTokens}, from the hex's {@link #layers} and {@link #designs}. */
    private static Map<Integer, Taken> taken(Hex hex, List<TilesetScenery.Layer> layers,
          Map<Integer, Map<String, HexAppearance>> designs) {
        Map<Integer, Taken> result = new TreeMap<>();
        for (var layer : layers) {
            if (layer.kind() == TilesetScenery.Kind.SKIPPED && layer.terrains().contains(Terrains.BUILDING)
                  && layer.terrains().contains(Terrains.FLUFF)) {
                result.putIfAbsent(Terrains.FLUFF, Taken.DROP);
            }
            if (!layer.taken()) { continue; }
            if (layer.terrains().contains(Terrains.FLUFF)) { result.put(Terrains.FLUFF, Taken.OBJECTS); }
            if (layer.terrains().contains(Terrains.ROAD_FLUFF) && hex.terrainLevel(Terrains.ROAD_FLUFF) == 3) {
                result.put(Terrains.ROAD_FLUFF, Taken.OBJECTS);
            }
        }
        designs.keySet().forEach(token -> result.putIfAbsent(token, Taken.DESIGN));
        for (int token : tokens(hex)) {
            List<TilesetScenery.Layer> drawn = layers.stream().filter(layer -> layer.terrains().contains(token)).toList();
            if (token == Terrains.BLDG_FLUFF || !drawn.isEmpty() && drawn.stream().allMatch(TilesetScenery.Layer::blank)) {
                result.putIfAbsent(token, Taken.DROP);
            }
        }
        return result;
    }

    /**
     * The hex designs that draw what the hex's legacy art tokens draw, by token and design owner: the blueprint variant
     * whose selector is the token ({@link BoardEditorBlueprint#reproducing}: transitions, kerbs and road pieces,
     * orchards, container yards with their rotation and rough). Quay art maps to its quay design (a ground design), which
     * only the 2D board of a {@code .board2} draws: the 3D views derive the fitted quay wall. A token absent from the
     * result has no design. {@code layers} are the hex's ({@link #layers}).
     */
    private static Map<Integer, Map<String, HexAppearance>> designs(Hex hex, List<TilesetScenery.Layer> layers) {
        var blueprint = BoardEditorBlueprint.get();
        Map<Integer, Map<String, HexAppearance>> result = new LinkedHashMap<>();
        for (int token : tokens(hex)) {
            Map<String, HexAppearance> owners = new LinkedHashMap<>();
            boolean quay = false;
            for (var layer : layers) {
                if (token != Terrains.FLUFF || !layer.terrains().contains(Terrains.FLUFF)) { continue; }
                quay |= layer.kind() == TilesetScenery.Kind.QUAY;
            }
            HexAppearance design = blueprint.reproducing(hex, hex.getTerrain(token));
            if (design != null) { owners.putIfAbsent(blueprint.variant(design.variant()).owner(), design); }
            if (quay || !owners.isEmpty()) { result.put(token, owners); }
        }
        return result;
    }

    /**
     * {@code hex} as a {@code .board2} holds it: without the legacy art tokens the conversion took
     * ({@link #takenTokens}), whose objects and designs stand for them; it adds nothing. Returns {@code hex} itself when
     * it holds no taken token, else a changed copy; {@code hex} is never changed. Games and previews of a loaded
     * {@code .board} keep the tokens in memory, so their 2D views draw the old sprites; the {@code .board2} writer
     * ({@link BoardFile#write}) and the 3D editor's document drop them, so a {@code .board2} never holds one; and every 3D
     * view draws each hex this way (the GPU board's one capture), so the game and the previews draw exactly what the
     * editor and the saved {@code .board2} hold. A token a gamemaster types in a game gets its design on the edit
     * ({@link #designTypedTokens}).
     */
    public static Hex withoutTakenTokens(Hex hex) {
        if (tokens(hex).isEmpty()) { return hex; }
        List<TilesetScenery.Layer> layers = layers(hex);
        var taken = taken(hex, layers, designs(hex, layers));
        if (taken.isEmpty()) { return hex; }
        Hex result = hex.duplicate();
        taken.keySet().forEach(result::removeTerrain);
        return result;
    }

    /**
     * The gamemaster edit path's translation of legacy art tokens typed in a game (kept for backward compatibility):
     * {@code hex}, which the edit changed from {@code before}, loses the design each token stood for that the edit
     * removed or changed (unless another design was chosen since), and gets the design each of its tokens stands for
     * where its owner has none, as the conversion gives it ({@link #designs}, {@link #designed}). The tokens stay, for the
     * game's 2D board; the 3D board draws the designs. Building art has its own helper ({@link #setBuildingArt}).
     */
    public static void designTypedTokens(Hex before, Hex hex) {
        if (tokens(before).isEmpty() && tokens(hex).isEmpty()) { return; }
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        designs(before, layers(before)).forEach((token, owners) -> {
            if (String.valueOf(before.getTerrain(token)).equals(String.valueOf(hex.getTerrain(token)))) { return; }
            owners.forEach((owner, design) -> {
                HexAppearance current = appearance.get(owner);
                // A road design keeps the road's finish and controls (designed): the design is its variant.
                if (current == null || !java.util.Objects.equals(current.variant(), design.variant())) { return; }
                HexAppearance surface = current.material() == null ? null : new HexAppearance(null, null, current.material(), null);
                HexAppearance controlled = HexAppearance.withTrafficControl(surface, current.trafficControl());
                HexAppearance retained = owner.equals(SingleLane.OWNER) && SingleLane.paved(hex)
                      ? HexAppearance.keepLanes(controlled, current) : controlled;
                if (retained == null) { appearance.remove(owner); } else { appearance.put(owner, retained); }
            });
        });
        hex.setAppearance(designed(appearance, designs(hex, layers(hex))));
    }

    /**
     * The building's legacy art level (its {@code bldg_fluff} value, 0 for its ordinary artwork): the token a game of a
     * loaded {@code .board} keeps for its 2D sprites. Modern buildings use their weight/class kit.
     */
    public static int buildingArt(Hex hex) {
        return hex.containsTerrain(Terrains.BLDG_FLUFF) ? Math.max(0, hex.terrainLevel(Terrains.BLDG_FLUFF)) : 0;
    }

    /**
     * Sets the building's legacy art level ({@link #buildingArt}) on {@code hex}, which holds its building: the token,
     * which a game's 2D board draws. It creates no modern design; level 0 removes the token.
     */
    public static void setBuildingArt(Hex hex, int level) {
        hex.removeTerrain(Terrains.BLDG_FLUFF);
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        appearance.remove("building");
        if (level > 0) {
            Terrain token = new Terrain(Terrains.BLDG_FLUFF, level);
            hex.addTerrain(token);
        }
        hex.setAppearance(appearance);
    }

    /**
     * A copy of a board's {@code hex} for the conversion to change and install in its place: {@link Hex#duplicate} with
     * the hex's fire turn, which a burning hex of an old save converted at load ({@code Game.initializeAfterLoad}) keeps.
     */
    private static Hex replacement(Hex hex) {
        Hex copy = hex.duplicate();
        for (int turn = 0; turn < hex.getFireTurn(); turn++) { copy.incrementFireTurn(); }
        return copy;
    }

    /**
     * A stamp's members on {@code hex} at {@code at}: fresh ids and one fresh group. {@code {exits}} in the layout key
     * is the hex's road exits as two digits.
     */
    public static List<BoardDecoration> stamp(String layout, Hex hex, Coords at) {
        String key = layout;
        if (key.contains("{exits}")) {
            if (!hex.containsTerrain(Terrains.ROAD)) { throw new IllegalArgumentException("Paint a road first"); }
            key = key.replace("{exits}", String.format(Locale.ROOT, "%02d", hex.getTerrain(Terrains.ROAD).getExits() & 63));
        }
        Layout rows = layout(key, hex);
        if (rows == null) { throw new IllegalArgumentException("Unknown stamp layout " + key); }
        String group = rows.pieces() > 1 ? UUID.randomUUID().toString() : null;
        var objects = rows.decorations(at, hex, () -> UUID.randomUUID().toString(), group, roadside(hex));
        return key.startsWith(ROAD_TREES) ? clearTreesOfRail(objects, hex, at, neighbor -> null) : objects;
    }

    /**
     * The surface a converted or stamped object stands on: the hex's roof, deck, industrial top, fuel tank or ice, else
     * its ground. Without {@code deck} it stands beneath a bridge, on what lies under the deck (trees: the legacy
     * road-with-trees art of a road passing under the bridge).
     */
    public static BoardDecoration.Placement receiver(Hex hex, boolean deck) {
        String support = hex.containsTerrain(Terrains.BUILDING) ? "building"
              : hex.containsTerrain(Terrains.BRIDGE) && deck ? "bridge"
              : hex.containsTerrain(Terrains.INDUSTRIAL) ? "industrial"
              : hex.containsTerrain(Terrains.FUEL_TANK) ? "fuelTank"
              : hex.containsTerrain(Terrains.ICE) ? "ice" : "ground";
        return BoardDecoration.Placement.on(support, 0);
    }

    /** The hex's own dry ground carries its objects: no roof, deck, industrial top, fuel tank, ice or water. */
    public static boolean plainGround(Hex hex) {
        return !hex.containsTerrain(Terrains.WATER) && receiver(hex, true).receiver().terrain().equals("ground");
    }

    /**
     * The renderer draws a road on the hex's own dry ground (its road kind, no liquid): a car park's decal rows stand
     * beside it rather than back to back.
     */
    public static boolean roadside(Hex hex) {
        return HexTile.road(hex) && plainGround(hex) && !HexTile.liquid(hex);
    }

    /**
     * The conversion's one rule-terrain change: a car park on plain ground without a road gets the lane the legacy render
     * paved through its sprite ({@link TilesetScenery.Scenery#cosmeticRoadExits}) as a paved road with exactly those
     * exits, so it runs edge to edge between its two rows of bays and joins only the roads that point along it. A car park
     * on a roof, deck, industrial top, fuel tank, ice or water keeps no road (its sprite was decoration there), nor does
     * one on another liquid, where no road is drawn; an authored road is kept. {@code lanes} holds every car park that
     * gets its lane. An automatic neighbour road on the lane stays automatic and gains its exit toward the car park. One
     * off the lane is written out with the exits it loaded with plus one toward each lane that points at it, so it grows
     * no arm toward a car park and joins every lane along it, whichever car park the scan meets first. Call before the
     * car park's own {@code board.setHex}.
     */
    private static void laneRoad(Board board, Coords coords, Hex result, Map<Coords, Integer> lanes) {
        int lane = lanes.getOrDefault(coords, 0);
        if (lane == 0) { return; }
        result.addTerrain(new Terrain(Terrains.ROAD, 1, true, lane));
        for (int direction = 0; direction < 6; direction++) {
            Coords next = coords.translated(direction);
            Terrain road = board.contains(next) ? board.getHex(next).getTerrain(Terrains.ROAD) : null;
            if (road == null || road.hasExitsSpecified() || (lane & 1 << direction) != 0
                  || (road.getExits() & 1 << (direction + 3) % 6) != 0) { continue; }
            int exits = road.getExits();
            for (int toward = 0; toward < 6; toward++) {
                if ((lanes.getOrDefault(next.translated(toward), 0) & 1 << (toward + 3) % 6) != 0) { exits |= 1 << toward; }
            }
            Hex kept = replacement(board.getHex(next));
            kept.addTerrain(new Terrain(Terrains.ROAD, road.getLevel(), true, exits));
            board.setHex(next, kept);
        }
    }

    /**
     * A route marker follows plain ground; on water, ice, a deck, roof, industrial top or fuel tank it holds that
     * support's level, since a route either follows the ground or keeps a fixed level. A support without its height
     * (a loadable but invalid hex) leaves the marker on the ground.
     */
    public static BoardDecoration.Placement routePlacement(Hex hex) {
        var support = receiver(hex, true);
        if (plainGround(hex)) { return support; }
        double level = support.level(hex);
        return Double.isFinite(level) ? BoardDecoration.Placement.absolute(level) : BoardDecoration.Placement.ground();
    }

    /**
     * Whether a converted rail with {@code sides} on {@code at} lies on its bridge's deck: when one of its sides runs
     * along the bridge ({@link RailRoute#alongDeck}) and the hex is water, or the rail leaves the bridge's span
     * ({@link BridgeSpan#of}) along the deck (a rail side of a span hex that is a bridge exit) off the board or onto a hex
     * whose ground is within one level of that deck, as a road meets a deck; otherwise it lies on the ground, beneath any
     * bridge (a rail crossing under it on water lies on the bed, as its neighbours). Never on a roof, industrial top or
     * fuel tank: there it runs on the ground inside them. Each span hex's rail sides are its sides as converted
     * ({@link #convertedSides}): rail00 art anywhere on the span counts the sides it joins.
     */
    private static boolean railOnDeck(Board board, Coords at, int sides, Map<Coords, TilesetScenery.Scenery> scenery) {
        Hex hex = board.getHex(at);
        if (!RailRoute.alongDeck(sides, RailRoute.bridgeExits(hex))) { return false; }
        if (hex.containsTerrain(Terrains.WATER)) { return true; }
        List<Coords> span = BridgeSpan.of(board::getHex, at);
        for (Coords member : span) {
            Hex bridge = board.getHex(member);
            int along = convertedSides(board, member, scenery) & bridge.getTerrain(Terrains.BRIDGE).getExits();
            for (int direction = 0; direction < 6; direction++) {
                Coords next = member.translated(direction);
                if ((along & RailRoute.side(direction)) == 0 || span.contains(next)) { continue; }
                if (!board.contains(next) || Math.abs(board.getHex(next).getLevel() - BridgeSpan.deck(bridge)) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The sides of {@code at}'s rail: its legacy art's, where the scan resolved it, else its rail markers'. */
    private static int railSides(Board board, Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        var artwork = scenery.get(at);
        int sides = 0;
        if (artwork == null) {
            for (var marker : RailRoute.markers(board.getHex(at), RailRoute.Kind.RAIL)) { sides |= marker.connections(); }
            return sides;
        }
        for (String key : artwork.models()) {
            for (Component row : LAYOUTS.get(key).components()) {
                if (RailRoute.Kind.of(row.asset()) == RailRoute.Kind.RAIL) { sides |= row.connections(); }
            }
        }
        return sides;
    }

    /**
     * The sides of {@code at}'s rail as the conversion gives them: its art's ({@link #railSides}), and where that art has
     * no sides (rail00) the sides it joins ({@link #railJoins}).
     */
    private static int convertedSides(Board board, Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        int sides = railSides(board, at, scenery);
        return sideless(at, scenery) ? sides | railJoins(board, at, scenery) : sides;
    }

    /** Whether {@code at}'s legacy art in {@code scenery} holds rail art without sides (rail00). */
    private static boolean sideless(Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        return scenery.getOrDefault(at, TilesetScenery.Scenery.EMPTY).models().stream()
              .anyMatch(key -> LAYOUTS.get(key).components().stream().anyMatch(row ->
                    RailRoute.Kind.of(row.asset()) == RailRoute.Kind.RAIL && row.connections() == 0));
    }

    /**
     * The sides legacy rail art without sides (rail00, drawn as a closed star) takes: toward each neighbour whose rail
     * leaves back ({@link #railSides}) or that is such art too.
     */
    private static int railJoins(Board board, Coords at, Map<Coords, TilesetScenery.Scenery> scenery) {
        int sides = 0;
        for (int direction = 0; direction < 6; direction++) {
            Coords next = at.translated(direction);
            if (!board.contains(next)) { continue; }
            if (sideless(next, scenery)
                  || (railSides(board, next, scenery) & RailRoute.side((direction + 3) % 6)) != 0) {
                sides |= RailRoute.side(direction);
            }
        }
        return sides;
    }

    private static Map<String, Layout> read() {
        var file = new File(Configuration.dataDir(), "models/board/scenery/layouts.json");
        // Legacy scenery keys decode through these rows; there are no baked composite meshes to fall back to.
        if (!file.isFile()) { throw new IllegalStateException("Cannot load the 3D board's scenery layouts"); }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read the 3D board's scenery layouts " + file, failure);
        }
        var result = new HashMap<String, Layout>();
        root.fields().forEachRemaining(entry -> {
            var components = new ArrayList<Component>();
            for (JsonNode component : entry.getValue()) {
                JsonNode position = component.get("position");
                String asset = component.get("asset").asText();
                // The shared park tree is the only tree slot; every other row is scenery. A stored "kind" is ignored.
                boolean tree = asset.equals("vegetation/tree-broad");
                JsonNode stretch = component.get("stretch");
                JsonNode seat = component.get("seat");
                var colours = new ArrayList<String>();
                if (component.has("colours")) {
                    for (JsonNode colour : component.get("colours")) { colours.add(colour.isNull() ? null : colour.asText()); }
                }
                components.add(new Component(asset, tree,
                      (float) position.get(0).asDouble(), (float) position.get(1).asDouble(), (float) position.get(2).asDouble(),
                      (float) component.get("rotation").asDouble(), (float) component.get("scale").asDouble(),
                      component.path("connections").asInt(0),
                      stretch == null ? BoardDecoration.Stretch.NONE
                            : new BoardDecoration.Stretch(stretch.get(0).asDouble(), stretch.get(1).asDouble(), stretch.get(2).asDouble()),
                      new BoardDecoration.Colours(colours), seat == null ? null : new Seat(seat.get(0).asInt(), seat.get(1).asInt())));
            }
            result.put(entry.getKey(), new Layout(List.copyOf(components)));
        });
        return Map.copyOf(result);
    }
}
