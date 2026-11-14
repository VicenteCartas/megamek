/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import megamek.common.Hex;
import megamek.common.game.IGame;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.units.VTOL;

/**
 * The one route model of rails and maglev ({@link Kind}): route markers at hex centres. A marker holds the sides it
 * leaves by ({@link BoardDecoration#connections()}, N=1 ... NW=32) and, only where it differs from the default layout of
 * those sides ({@link #pieces(int)}), the track pieces it lays between them ({@link BoardDecoration#tracks()}). Rails as
 * terrain ({@link Terrains#RAIL} on the ground, {@link Terrains#BRIDGE_RAIL} on a bridge deck, exits = sides) yield
 * derived markers, never stored ({@link #markers}); the editor and legacy conversion share {@link #lay}.
 * Each hex has at most one ground rail and one deck rail. A side joins the neighbour's reachable layer of the same
 * kind with the opposite side, so parallel routes in adjacent hexes stay apart and the kinds never join.
 * <p>
 * Every piece is gentle: a straight between opposite sides or a 60-degree arc between sides two apart; no rail vehicle
 * takes the 120-degree turn between adjacent sides, so none is ever laid. A layout is a 9-bit piece mask: bit d (0-2) is
 * the straight from side d to side d + 3, bit 3 + k the arc from side k to side k + 2. A side with no gentle partner (two
 * adjacent sides, or the middle of three) is stranded ({@link #stranded}): it ends in a short stub.
 */
public final class RailRoute {
    /** The two route kinds: one model, one builder, two looks. */
    public enum Kind {
        /** Suspended on its fixed level. */
        MAGLEV("scenery/maglev/route"),
        /** On the ground, following it as roads do, or on a bridge deck. */
        RAIL("scenery/rail/route");

        public final String asset;

        Kind(String asset) { this.asset = asset; }

        /** The kind whose route marker this asset is, or null. */
        public static Kind of(String asset) {
            for (Kind kind : values()) {
                if (kind.asset.equals(asset)) { return kind; }
            }
            return null;
        }

        /** The folder of the kind's pieces (rolling stock, platforms), which ride its route ({@link #rides}). */
        String folder() { return asset.substring(0, asset.lastIndexOf('/') + 1); }
    }

    /** Visual size of conventional track and rolling stock; maglev retains its authored dimensions. */
    public static final float CONVENTIONAL_SCALE = 1.3f;
    /** The rail top above the track's carrier plane (the ground or a deck), in model px: rolling stock rides on it. */
    public static final float RAIL_TOP = 1.4f * CONVENTIONAL_SCALE;
    /** The rail kind's buffer stop, which closes every dead end and stranded stub. */
    public static final String BUFFER_STOP = "scenery/rail/buffer-stop";
    /** The maglev's station platform: it rides the route's level but stands beside the track, not on it. */
    public static final String PLATFORM = "scenery/maglev/platform";
    /** The maglev train's gangway: a vehicle that rides between two cars, not a car of its own length. */
    public static final String COUPLER = "scenery/maglev/coupler";
    /** The rail kind's fixed pieces, which stand on the route's support rather than ride the rails. */
    private static final List<String> FIXED = List.of(BUFFER_STOP, "scenery/rail/crossing-signal");
    /** How far a stranded side's stub reaches in from its border: beside two other sides, and beside one. */
    public static final float STUB_SHORT = 13, STUB_LONG = 16;
    /** A buffer stop stands this far before its track's end, so its struts stay on the track (model px). */
    public static final float STOP_DEPTH = 6.5f * CONVENTIONAL_SCALE;
    /** Half the length of the stub a marker without sides draws (model px). */
    public static final float LONE_STUB = 12;
    /** Track pieces per layout: three straights and six arcs. */
    public static final int PIECES = 9;
    /** A course's handles, as a share of each end's distance from the hex centre ({@link #course}). */
    public static final float HANDLE = .35f;

    private RailRoute() { }

    /** The marker's kind, or null when the object is no route marker. */
    public static Kind kind(BoardDecoration object) {
        return object == null || !object.kind().equals("prop") ? null : Kind.of(object.asset());
    }

    public static boolean isRoute(BoardDecoration object) { return kind(object) != null; }

    /** The derived markers' ids: the rail on a hex's ground and the rail on its bridge deck. */
    public static final String GROUND_RAIL = "rail", DECK_RAIL = "bridge-rail";

    /** A hex's markers of {@code kind}, derived only from its ground and deck rail terrain. */
    public static List<BoardDecoration> markers(Hex hex, Kind kind) {
        if (hex == null) { return List.of(); }
        return derived(hex, "").stream().filter(object -> kind(object) == kind).toList();
    }

    /**
     * The markers a hex's rail terrain yields: its intact ground rail ({@link Terrains#RAIL}, id {@code prefix} +
     * {@link #GROUND_RAIL}, on the ground) and its deck rail ({@link Terrains#BRIDGE_RAIL}, id {@code prefix} +
     * {@link #DECK_RAIL}, on the bridge deck). Each leaves by its terrain's exits and lays the junction layout the hex's
     * appearance stores for it ({@link HexAppearance#tracks}) where that is a layout of them, else the default. A
     * destroyed rail yields none.
     */
    public static List<BoardDecoration> derived(Hex hex, String prefix) {
        List<BoardDecoration> result = new ArrayList<>(2);
        derive(hex, Terrains.RAIL, prefix + GROUND_RAIL, "rail", BoardDecoration.Placement.ground(), result);
        derive(hex, Terrains.BRIDGE_RAIL, prefix + DECK_RAIL, "bridgeRail", BoardDecoration.Placement.on("bridge", 0), result);
        return result;
    }

    /**
     * The one writer of a hex's rail: an intact rail with these sides on its ground ({@link Terrains#RAIL}) or on its
     * bridge deck ({@code deck}, {@link Terrains#BRIDGE_RAIL}, whose sides the caller keeps within the bridge's exits),
     * with its junction layout {@code tracks} on the rail's appearance, where it is not the default (0).
     */
    public static void lay(Hex hex, boolean deck, int sides, int tracks) {
        lay(hex, deck, sides, tracks, Kind.RAIL);
    }

    /** Lays either ordinary rail or maglev on its terrain layer, with the same junction appearance ownership. */
    public static void lay(Hex hex, boolean deck, int sides, int tracks, Kind kind) {
        hex.addTerrain(new Terrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL, kind == Kind.MAGLEV ? 2 : 1, true, sides));
        Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
        int beside = kind == Kind.MAGLEV ? 0 : HexAppearance.beside(appearance, deck ? "bridgeRail" : "rail");
        HexAppearance layout = HexAppearance.rail(tracks, beside == 0 ? 0 : 64 | (beside & sides & 63));
        if (layout == null) { appearance.remove(deck ? "bridgeRail" : "rail"); }
        else { appearance.put(deck ? "bridgeRail" : "rail", layout); }
        hex.setAppearance(appearance);
    }

    private static void derive(Hex hex, int type, String id, String owner, BoardDecoration.Placement placement,
          List<BoardDecoration> result) {
        Terrain rail = hex.getTerrain(type);
        if (rail == null || rail.getLevel() <= 0) { return; }
        int sides = rail.getExits() & 63, tracks = HexAppearance.tracks(hex.getAppearance(), owner);
        Kind kind = rail.getLevel() == 2 ? Kind.MAGLEV : Kind.RAIL;
        result.add(new BoardDecoration(id, "prop", kind.asset, null, 0, 0, 0, false, 1, placement, 0, false, 0, 0,
              null, false, sides, BoardDecoration.Stretch.NONE, BoardDecoration.Colours.NONE, valid(sides, tracks) ? tracks : 0));
    }

    /** Whether TacOps rails are in play (TO:AR p.49). With the option off, rails are drawn and play as nothing. */
    public static boolean rules(IGame game) {
        return game != null && game.getOptions().booleanOption(OptionsConstants.ADVANCED_TAC_OPS_RAILS);
    }

    /** Whether a rail hinders {@code entity} (TO:AR p.49): 'Meks and ground vehicles; infantry, ProtoMeks and aircraft pass. */
    public static boolean affects(Entity entity) {
        return entity instanceof Mek || entity instanceof Tank && !(entity instanceof VTOL);
    }

    /** Whether the hex holds the rules' rail: an intact {@link Terrains#RAIL} on its ground (a destroyed one is clear). */
    public static boolean intact(Hex hex) {
        return hex != null && hex.terrainLevel(Terrains.RAIL) > 0;
    }

    /** Destroy the ground track, retaining its kind and connections; its level-zero terrain is the surviving bed. */
    public static void destroyGround(Hex hex) {
        destroyGround(hex, hex.getLevel());
    }

    private static void destroyGround(Hex hex, int originalLevel) {
        Terrain rail = hex.getTerrain(Terrains.RAIL);
        if (rail == null || rail.getLevel() <= 0) { return; }
        hex.addRailRuin(new Terrain(rail), originalLevel);
        hex.addTerrain(new Terrain(Terrains.RAIL, 0, true, rail.getExits()));
    }

    /**
     * Before a blast clears every terrain, retain both routes without retaining a bridge body or piers. The supplied
     * ground level precedes the same blast's cratering/water removal; bridge height is still relative to that ground.
     */
    public static void destroyAll(Hex hex, int originalGroundLevel) {
        // Old boards did not store absolute heights. Capture their best available height before this blast changes it.
        for (int layer = 0; layer < 2; layer++) {
            boolean deck = layer == 1;
            Terrain old = hex.getRuin(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL);
            if (!deck && old == null && hex.terrainLevel(Terrains.RAIL) == 0) {
                old = new Terrain(Terrains.RAIL, 1, true, hex.getTerrain(Terrains.RAIL).getExits());
            }
            Terrain height = deck ? hex.getRuin(Terrains.BRIDGE_ELEV) : null;
            if (old != null && hex.getRailRuinLevel(deck) == null) {
                hex.addRailRuin(old, originalGroundLevel + (height == null ? 0 : height.getLevel()));
            }
        }
        destroyGround(hex, originalGroundLevel);
        Terrain rail = hex.getTerrain(Terrains.BRIDGE_RAIL);
        if (rail == null || rail.getLevel() <= 0) { return; }
        int deckHeight = hex.containsTerrain(Terrains.BRIDGE_ELEV) ? hex.terrainLevel(Terrains.BRIDGE_ELEV) : 0;
        hex.addRailRuin(new Terrain(rail), originalGroundLevel + deckHeight);
        hex.removeTerrain(Terrains.BRIDGE_RAIL);
    }

    /** Surviving track edges cut by destruction, captured with a hex for rendering. */
    public record Cuts(int ground, int deck) {
        public static final Cuts NONE = new Cuts(0, 0);
    }

    public static Cuts cuts(Hex hex, Function<Coords, Hex> board) {
        int ground = 0, deck = 0;
        for (var marker : derived(hex, "")) {
            int cuts = 0;
            for (int d = 0; d < 6; d++) {
                if ((drawnSides(marker, hex) & side(d)) == 0) { continue; }
                Hex next = board.apply(hex.getCoords().translated(d));
                if (next == null || joined(hex, marker, d, next) != null) { continue; }
                for (int layer = 0; layer < 2; layer++) {
                    boolean deckRuin = layer == 1;
                    int type = deckRuin ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
                    if (next.terrainLevel(type) > 0) { continue; } // Repaired/authored track supersedes its old ruin.
                    Terrain lost = next.getRuin(type);
                    // Older games retained only the conventional track's level-zero bed.
                    if (lost == null && !deckRuin && next.terrainLevel(type) == 0) { lost = next.getTerrain(type); }
                    if (lost == null || lost.getLevel() < 0 || deckRuin && lost.getLevel() == 0
                          || kind(marker) != (lost.getLevel() == 2 ? Kind.MAGLEV : Kind.RAIL)
                          || (lost.getExits() & side((d + 3) % 6)) == 0) { continue; }
                    Integer originalLevel = next.getRailRuinLevel(deckRuin);
                    Terrain oldDeck = next.getRuin(Terrains.BRIDGE_ELEV);
                    float lostLevel = originalLevel != null ? originalLevel
                          : next.getLevel() + (deckRuin && oldDeck != null ? oldDeck.getLevel() : 0);
                    if (Float.isFinite(joinScore(onDeck(marker), level(hex, marker), deckRuin, lostLevel))) {
                        cuts |= side(d);
                        break;
                    }
                }
            }
            if (onDeck(marker)) { deck |= cuts; } else { ground |= cuts; }
        }
        return ground == 0 && deck == 0 ? Cuts.NONE : new Cuts(ground, deck);
    }

    /** Whether a step from {@code from} into the adjacent {@code to}, leaving by {@code direction}, follows an intact rail. */
    public static boolean along(Hex from, Hex to, int direction) {
        return intact(from) && intact(to) && from.terrainLevel(Terrains.RAIL) == to.terrainLevel(Terrains.RAIL)
              && from.containsTerrainExit(Terrains.RAIL, direction)
              && to.containsTerrainExit(Terrains.RAIL, (direction + 3) % 6);
    }

    /** The hex's first marker of {@code kind}, or null. */
    public static BoardDecoration find(Hex hex, Kind kind) {
        var markers = markers(hex, kind);
        return markers.isEmpty() ? null : markers.getFirst();
    }

    /** The side bit of a {@link Coords#translated(int)} direction. */
    public static int side(int direction) { return 1 << direction; }

    /**
     * True when {@code route} leaves by {@code direction} and {@code neighbour}, a marker of the same kind that way,
     * leaves back.
     */
    public static boolean joined(BoardDecoration route, int direction, BoardDecoration neighbour) {
        return isRoute(route) && kind(route) == kind(neighbour) && (route.connections() & side(direction)) != 0
              && (neighbour.connections() & side((direction + 3) % 6)) != 0;
    }

    /**
     * The marker of {@code route}'s kind on {@code neighbour} (the hex in {@code direction}) that joins it, or null: the
     * one that leaves back by the opposite side, unique because a hex's markers own disjoint sides.
     */
    public static BoardDecoration joined(BoardDecoration route, int direction, Hex neighbour) {
        if (!isRoute(route)) { return null; }
        return markers(neighbour, kind(route)).stream().filter(other -> joined(route, direction, other))
              .findFirst().orElse(null);
    }

    /** A joined rail on the same reachable layer; a ground continuation takes precedence over a bridge above it. */
    public static BoardDecoration joined(Hex from, BoardDecoration route, int direction, Hex neighbour) {
        BoardDecoration best = null;
        float gap = Float.POSITIVE_INFINITY;
        for (var other : markers(neighbour, kind(route))) {
            if (!joined(route, direction, other)) { continue; }
            float score = joinScore(route, level(from, route), other, level(neighbour, other));
            if (score < gap) { best = other; gap = score; }
        }
        return best;
    }

    /** The shared selection rule for authoring and captured rendering; unreachable layers have infinite score. */
    public static float joinScore(BoardDecoration route, float level, BoardDecoration other, float otherLevel) {
        return joinScore(onDeck(route), level, onDeck(other), otherLevel);
    }

    private static float joinScore(boolean deck, float level, boolean otherDeck, float otherLevel) {
        if (!deck && !otherDeck) { return 0; }
        float delta = Math.abs(level - otherLevel);
        return delta > 1 ? Float.POSITIVE_INFINITY : delta + (deck == otherDeck ? 0 : 2);
    }

    /** The rules surface a route rides, including its bridge rather than the ground beneath that bridge. */
    public static float level(Hex hex, BoardDecoration marker) {
        return (float) marker.placement().level(hex.getLevel(), receiver -> hex.getLevel()
              + (receiver.equals("bridge") && hex.containsTerrain(Terrains.BRIDGE)
                    ? hex.terrainLevel(Terrains.BRIDGE_ELEV) : 0));
    }

    /** A rail marker on a bridge deck, which it rides; any other rail lies on the ground. */
    public static boolean onDeck(BoardDecoration marker) {
        var receiver = marker.placement().receiver();
        return receiver != null && receiver.terrain().equals("bridge");
    }

    /**
     * Whether the hex holds intact rail terrain on its ground, including a ground-level maglev. A suspended maglev
     * belongs to its deck and leaves the ground clear ({@link Hex#isClear}).
     */
    public static boolean groundRail(Hex hex) {
        return intact(hex);
    }

    /** Whether the hex's bridge deck carries a rail ({@link Hex#isClearDeck}). */
    public static boolean deckRail(Hex hex) {
        return hex != null && hex.terrainLevel(Terrains.BRIDGE_RAIL) > 0;
    }

    /**
     * Whether a rail with these sides may ride a bridge with these exits: one of its sides runs along the deck. A deck
     * carries track only along itself ({@link #drawnSides}), so the conversion and the editor put a rail on a deck only
     * then; any other rail on a bridge hex, one crossing under it, lies beneath.
     */
    public static boolean alongDeck(int sides, int bridgeExits) { return (sides & bridgeExits & 63) != 0; }

    /** The exits of the hex's bridge, 0 without one. */
    public static int bridgeExits(Hex hex) {
        var bridge = hex.getTerrain(Terrains.BRIDGE);
        return bridge == null ? 0 : bridge.getExits();
    }

    /**
     * The sides of the hex's rails that lie on the ground, a destroyed rail's too: the road engine shapes and clears the
     * ground for them as for a road with those exits.
     */
    public static int groundSides(Hex hex) {
        Terrain rail = hex == null ? null : hex.getTerrain(Terrains.RAIL);
        int result = rail == null ? 0 : rail.getExits() & 63;
        for (var marker : markers(hex, Kind.RAIL)) {
            if (!onDeck(marker)) { result |= marker.connections(); }
        }
        return result;
    }

    // ---------------------------------------------------------------- road and rail corridors

    /**
     * Shared corridor borders; beside marks option A and right stores the physical side of its track there.
     * roadEnd also retains a shared terminal's length when its adjoining junction cannot carry a corridor.
     */
    public record Corridor(int sides, int beside, int right, int tracks, boolean roadEnd) {
        public static final Corridor NONE = new Corridor(0, 0, 0);

        public Corridor(int sides, int beside, int right) { this(sides, beside, right, 0); }

        public Corridor(int sides, int beside, int right, int tracks) { this(sides, beside, right, tracks, sides != 0); }

        /** Right of looking out through d. Opposite hexes use opposite signs for the same physical point. */
        public float lateral(int d) {
            return d < 0 || (beside & side(d)) == 0 ? 0 : ((right & side(d)) == 0 ? -1 : 1) * (d < 3 ? 1 : -1);
        }

        public int middle() { return sides & ~beside; }
    }

    /** The ordinary track's ballast footprint; widening its adjacent road does not widen the railway. */
    public static final float BALLAST_HALF_WIDTH = 5.6f * CONVENTIONAL_SCALE;
    /** Clear space between the paved edge and the ballast foot, in model pixels. */
    public static final float CORRIDOR_GAP = 1.5f;
    /** Road and track offsets balance the two footprints around their shared corridor axis. */
    public static final float BESIDE_ROAD = -(BALLAST_HALF_WIDTH + CORRIDOR_GAP / 2);
    public static final float BESIDE_RAIL = HexTile.ROAD_HALF_WIDTH + CORRIDOR_GAP / 2;

    /** Rail sides that continue gently, with hairpin stubs excluded. */
    public static int corridorSides(BoardDecoration marker, int bridgeExits) {
        int sides = drawnSides(marker, bridgeExits);
        return sides & ~stranded(sides);
    }

    private static int local(Hex hex, boolean deck) {
        if (hex == null || hex.terrainLevel(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL) != 1) { return 0; }
        int roadType = deck ? Terrains.BRIDGE_ROAD : Terrains.ROAD;
        Terrain road = hex.getTerrain(roadType);
        if (road == null || (deck ? !hex.containsTerrain(Terrains.BRIDGE)
              : !HexTile.road(hex) || hex.containsTerrain(Terrains.WATER) || Integer.bitCount(road.getExits() & 63) >= 5)) { return 0; }
        Terrain rail = hex.getTerrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL);
        int sides = rail.getExits() & (deck ? bridgeExits(hex) : 63);
        return road.getExits() & sides & ~stranded(sides) & 63;
    }

    /** The ground corridor of this hex, shared with neighbours (or continuing off the board). */
    public static Corridor corridor(Hex hex, Coords at, Function<Coords, Hex> board) {
        return corridor(hex, at, board, false);
    }

    /** One layer's corridor. Deck roads are local data; no span-wide decision or cache is needed. */
    public static Corridor corridor(Hex hex, Coords at, Function<Coords, Hex> board, boolean deck) {
        int local = local(hex, deck), sides = 0, beside = 0, right = 0;
        if (local == 0) { return Corridor.NONE; }
        BoardDecoration route = markers(hex, Kind.RAIL).stream().filter(m -> onDeck(m) == deck).findFirst().orElseThrow();
        int stored = HexAppearance.beside(hex.getAppearance(), deck ? "bridgeRail" : "rail");
        for (int d = 0; d < 6; d++) {
            if ((local & side(d)) == 0) { continue; }
            Hex next = board.apply(at.translated(d));
            int opposite = (d + 3) % 6;
            BoardDecoration otherRoute = next == null ? null : joined(hex, route, d, next);
            boolean nextDeck = otherRoute != null && onDeck(otherRoute);
            if (next != null && (otherRoute == null || (local(next, nextDeck) & side(opposite)) == 0
                  || !route.equals(joined(next, otherRoute, opposite, hex)))) { continue; }
            sides |= side(d);
            int other = next == null ? stored : HexAppearance.beside(next.getAppearance(), nextDeck ? "bridgeRail" : "rail");
            boolean bit = (stored & side(d)) != 0;
            if (stored != 0 && other != 0 && (next == null || bit == ((other & side(opposite)) != 0))) {
                beside |= side(d);
                if (bit) { right |= side(d); }
            }
        }
        // A terminal still ends with its road when the adjoining junction cannot carry a corridor (a hairpin or
        // multi-arm hub). This changes only its closed end; the border retains the neighbour's ordinary width.
        boolean roadEnd = Integer.bitCount(route.connections()) == 1
              && hex.getTerrain(deck ? Terrains.BRIDGE_ROAD : Terrains.ROAD).getExits() == route.connections();
        return sides == 0 && !roadEnd ? Corridor.NONE : new Corridor(sides, beside, right, route.tracks(), sides != 0 || roadEnd);
    }

    /** The connected corridor containing a selected derived rail, on either ground or deck. */
    public static List<Member> corridorRun(Function<Coords, Hex> board, Coords at, BoardDecoration marker) {
        if (kind(marker) != Kind.RAIL || corridor(board.apply(at), at, board, onDeck(marker)).sides() == 0) { return List.of(); }
        List<Member> result = new ArrayList<>(List.of(new Member(at, marker)));
        var found = new java.util.HashSet<>(result);
        for (int index = 0; index < result.size(); index++) {
            Member member = result.get(index);
            Corridor c = corridor(board.apply(member.at()), member.at(), board, onDeck(member.marker()));
            for (int d = 0; d < 6; d++) {
                if ((c.sides() & side(d)) == 0) { continue; }
                Coords next = member.at().translated(d);
                var other = joined(board.apply(member.at()), member.marker(), d, board.apply(next));
                if (other == null || (corridor(board.apply(next), next, board, onDeck(other)).sides() & side((d + 3) % 6)) == 0) { continue; }
                var reached = new Member(next, other);
                if (found.add(reached)) { result.add(reached); }
            }
        }
        return List.copyOf(result);
    }

    /** The beside-side bits of a whole run, propagated through its track pieces and across shared borders. */
    public static Map<Member, Integer> besideBits(Function<Coords, Hex> board, List<Member> run, Member start) {
        Map<Member, Integer> known = new HashMap<>(), right = new HashMap<>();
        if (run.isEmpty()) { return Map.of(); }
        Member first = run.contains(start) ? start : run.getFirst();
        int sides = corridor(board.apply(first.at()), first.at(), board, onDeck(first.marker())).sides();
        int d = Integer.numberOfTrailingZeros(sides);
        int stored = HexAppearance.beside(board.apply(first.at()).getAppearance(), onDeck(first.marker()) ? "bridgeRail" : "rail");
        known.put(first, side(d));
        right.put(first, stored == 0 ? (d < 3 ? side(d) : 0) : stored & side(d));
        Map<Coords, List<Member>> byHex = new HashMap<>();
        for (Member member : run) { byHex.computeIfAbsent(member.at(), key -> new ArrayList<>()).add(member); }
        var pending = new java.util.ArrayDeque<Member>();
        pending.add(first);
        while (!pending.isEmpty()) {
            Member member = pending.removeFirst();
            int mask = known.getOrDefault(member, 0), bits = right.getOrDefault(member, 0);
            int tracks = pieces(member.marker(), drawnSides(member.marker(), board.apply(member.at())));
            int before;
            do {
                before = mask;
                for (int piece = 0; piece < PIECES; piece++) {
                    if ((tracks & 1 << piece) == 0) { continue; }
                    int pair = pieceSides(piece), a = Integer.numberOfTrailingZeros(pair), b = 31 - Integer.numberOfLeadingZeros(pair);
                    if ((mask & side(a)) == 0 && (mask & side(b)) != 0) { int swap = a; a = b; b = swap; }
                    if ((mask & side(a)) == 0 || (mask & side(b)) != 0) { continue; }
                    boolean bit = ((bits & side(a)) != 0) != (a < 3 == b < 3);
                    mask |= side(b);
                    if (bit) { bits |= side(b); }
                }
            } while (before != mask);
            known.put(member, mask); right.put(member, bits);
            int shared = corridor(board.apply(member.at()), member.at(), board, onDeck(member.marker())).sides();
            for (int exit = 0; exit < 6; exit++) {
                if ((mask & shared & side(exit)) == 0) { continue; }
                int opposite = (exit + 3) % 6;
                BoardDecoration joined = joined(board.apply(member.at()), member.marker(), exit,
                      board.apply(member.at().translated(exit)));
                for (Member next : byHex.getOrDefault(member.at().translated(exit), List.of())) {
                    if (!next.marker().equals(joined)) { continue; }
                    int nextShared = corridor(board.apply(next.at()), next.at(), board, onDeck(next.marker())).sides();
                    if ((nextShared & side(opposite)) == 0 || (known.getOrDefault(next, 0) & side(opposite)) != 0) { continue; }
                    known.merge(next, side(opposite), (a, b) -> a | b);
                    if ((bits & side(exit)) != 0) { right.merge(next, side(opposite), (a, b) -> a | b); }
                    pending.add(next);
                }
            }
            // Separate tracks at a crossing have an independent side, chosen in stable run/side order.
            if (pending.isEmpty()) {
                for (Member next : run) {
                    int missing = corridor(board.apply(next.at()), next.at(), board, onDeck(next.marker())).sides()
                          & ~known.getOrDefault(next, 0);
                    if (missing == 0) { continue; }
                    int exit = Integer.numberOfTrailingZeros(missing);
                    known.merge(next, side(exit), (a, b) -> a | b);
                    if (exit < 3) { right.merge(next, side(exit), (a, b) -> a | b); }
                    pending.add(next);
                    break;
                }
            }
        }
        Map<Member, Integer> result = new HashMap<>();
        for (Member member : run) { result.put(member, 64 | (right.getOrDefault(member, 0) & member.marker().connections())); }
        return Map.copyOf(result);
    }

    /** Write only the corridor choice, retaining this layer's authored junction. */
    public static void beside(Hex hex, boolean deck, int value) {
        String owner = deck ? "bridgeRail" : "rail";
        Map<String, HexAppearance> looks = new HashMap<>(hex.getAppearance());
        int sides = hex.getTerrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL).getExits() & 63;
        HexAppearance look = HexAppearance.rail(HexAppearance.tracks(looks, owner), value == 0 ? 0 : 64 | (value & sides));
        if (look == null) { looks.remove(owner); } else { looks.put(owner, look); }
        hex.setAppearance(looks);
    }

    /**
     * The sides a marker draws: all of them, but on a deck only those that are exits of the bridge
     * ({@code bridgeExits}), since a deck carries track only along itself.
     */
    public static int drawnSides(BoardDecoration marker, int bridgeExits) {
        return onDeck(marker) ? marker.connections() & bridgeExits : marker.connections();
    }

    /** {@link #drawnSides(BoardDecoration, int)} of a marker on {@code hex}, from its bridge's exits. */
    public static int drawnSides(BoardDecoration marker, Hex hex) { return drawnSides(marker, bridgeExits(hex)); }

    /**
     * The kind whose route an object of {@code asset} rides (its rolling stock, the maglev's platform), or null: maglev
     * pieces at the route's own placement, their ride height being in their meshes; rail vehicles on the rail top.
     */
    public static Kind rides(String asset) {
        for (Kind kind : Kind.values()) {
            if (asset.startsWith(kind.folder()) && !asset.equals(kind.asset) && !FIXED.contains(asset)) { return kind; }
        }
        return null;
    }

    /**
     * Where a piece riding {@code marker} stands: at the marker's own placement. A maglev piece's ride height is in its
     * mesh. A rail rider ({@link #railRider}) has its origin at rail-top height: the renderer stands it on the rail top
     * the track draws over that support, in model px as the track (on ballast, or set into a road), never as a stored
     * offset in levels.
     */
    public static BoardDecoration.Placement ride(BoardDecoration marker) { return marker.placement(); }

    /** Whether an object of {@code asset} stands on a rail's rail top: rail rolling stock and the buffer stop. */
    public static boolean railRider(String asset) { return rides(asset) == Kind.RAIL || asset.equals(BUFFER_STOP); }

    /** Conventional rolling stock and its buffer stop match the generated track's physical scale. */
    public static float visualScale(String asset) { return railRider(asset) ? CONVENTIONAL_SCALE : 1; }

    // ---------------------------------------------------------------- pieces

    /** The two sides a piece joins, as a side mask. */
    public static int pieceSides(int piece) {
        return piece < 3 ? side(piece) | side(piece + 3) : side(piece - 3) | side((piece - 1) % 6);
    }

    /** The piece between two sides, or -1 for sides that are no gentle pair (the same side, or adjacent sides). */
    public static int piece(int from, int to) {
        int apart = Math.floorMod(to - from, 6);
        if (apart == 3) { return Math.min(from, to); }
        if (apart == 2) { return 3 + from; }
        if (apart == 4) { return 3 + to; }
        return -1;
    }

    /** Whether the piece is a straight (else a 60-degree arc). */
    public static boolean straight(int piece) { return piece < 3; }

    private static int distance(int a, int b) {
        int apart = Math.floorMod(a - b, 6);
        return Math.min(apart, 6 - apart);
    }

    /** The sides of a mask of two or more that have no gentle partner: each ends in a stub. */
    public static int stranded(int sides) {
        sides &= 63;
        if (Integer.bitCount(sides) < 2) { return 0; }
        int result = 0;
        for (int d = 0; d < 6; d++) {
            int partners = side((d + 2) % 6) | side((d + 3) % 6) | side((d + 4) % 6);
            if ((sides & side(d)) != 0 && (sides & partners) == 0) { result |= side(d); }
        }
        return result;
    }

    /**
     * The default layout of {@code sides}, which reproduces every legacy rail sprite's gentle pieces: each opposite
     * pair a straight; every other side turns out onto each through line it can reach, else onto each side two apart; a
     * Y without a through line (N, SE, SW or NE, S, NW) has its stem on the N or S side, as the legacy art.
     */
    public static int pieces(int sides) {
        sides &= 63;
        if (sides == 21 || sides == 42) { return stem(sides, (sides & side(0)) != 0 ? 0 : 3); }
        int straights = 0, on = 0;
        for (int d = 0; d < 3; d++) {
            int pair = side(d) | side(d + 3);
            if ((sides & pair) == pair) { straights |= 1 << d; on |= pair; }
        }
        int result = straights;
        for (int d = 0; d < 6; d++) {
            if ((sides & side(d)) == 0 || (on & side(d)) != 0) { continue; }
            int reach = sides & (side((d + 2) % 6) | side((d + 4) % 6));
            int onto = (reach & on) != 0 ? reach & on : reach;
            for (int e = 0; e < 6; e++) {
                if ((onto & side(e)) != 0) { result |= 1 << piece(d, e); }
            }
        }
        return result;
    }

    /** A Y without a through line, from {@code stem} to the other two sides. */
    private static int stem(int sides, int stem) {
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if (d != stem && (sides & side(d)) != 0) { result |= 1 << piece(stem, d); }
        }
        return result;
    }

    /**
     * Whether {@code tracks} is a layout of {@code sides}: none (0, the default), or pieces that each join two of the
     * sides and together reach every side that has a gentle partner.
     */
    public static boolean valid(int sides, int tracks) {
        if (tracks == 0) { return true; }
        if (tracks < 0 || tracks >= 1 << PIECES) { return false; }
        int covered = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((tracks & 1 << piece) == 0) { continue; }
            if ((pieceSides(piece) & ~sides) != 0) { return false; }
            covered |= pieceSides(piece);
        }
        int reachable = Integer.bitCount(sides & 63) < 2 ? 0 : sides & 63 & ~stranded(sides);
        return (reachable & ~covered) == 0;
    }

    /** The pieces {@code marker} lays on its drawn {@code sides}: its stored layout where valid, else the default. */
    public static int pieces(BoardDecoration marker, int sides) {
        return marker.tracks() != 0 && valid(sides, marker.tracks()) ? marker.tracks() : pieces(sides);
    }

    /**
     * The layout to store ({@link BoardDecoration#tracks}) for {@code sides} that keeps the pieces of {@code kept} whose
     * sides it has (what a hex laid before, and what a stroke drew through it): none (0) when the default lays them all,
     * else those pieces plus each default piece that reaches a side they leave uncovered.
     */
    public static int layout(int sides, int kept) {
        sides &= 63;
        int fallback = pieces(sides), result = 0, covered = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((kept & 1 << piece) != 0 && (pieceSides(piece) & ~sides) == 0) { result |= 1 << piece; covered |= pieceSides(piece); }
        }
        if ((fallback & result) == result) { return 0; }
        for (int piece = 0; piece < PIECES; piece++) {
            if ((fallback & 1 << piece) != 0 && (pieceSides(piece) & ~covered) != 0) { result |= 1 << piece; }
        }
        return result;
    }

    /** The marker of {@code kind} on {@code hex} other than {@code except} that owns side {@code direction}, or null. */
    public static BoardDecoration owner(Hex hex, Kind kind, int direction, BoardDecoration except) {
        return markers(hex, kind).stream().filter(marker -> (except == null || !marker.id().equals(except.id()))
              && (marker.connections() & side(direction)) != 0).findFirst().orElse(null);
    }

    /** A marker of a connected run and the hex that holds it. */
    public record Member(Coords at, BoardDecoration marker) { }

    /**
     * The connected run of {@code marker} on {@code at}: itself first, then every marker of its kind joined to it,
     * directly or through others ({@link #joined(BoardDecoration, int, Hex)}). One hex may hold two members, where a
     * route passes over itself at another level. A maglev's Pillars toggle applies to its whole run, as a bridge's to its
     * whole span.
     */
    public static List<Member> run(java.util.function.Function<Coords, Hex> board, Coords at, BoardDecoration marker) {
        List<Member> result = new ArrayList<>(List.of(new Member(at, marker)));
        java.util.Set<Member> found = new java.util.HashSet<>(result);
        for (int index = 0; index < result.size(); index++) {
            Member member = result.get(index);
            for (int d = 0; d < 6; d++) {
                Coords next = member.at().translated(d);
                BoardDecoration other = (member.marker().connections() & side(d)) == 0 ? null
                      : joined(board.apply(member.at()), member.marker(), d, board.apply(next));
                if (other != null && found.add(new Member(next, other))) { result.add(new Member(next, other)); }
            }
        }
        return result;
    }

    /**
     * A sensible layout of a junction's sides ({@link #junctions}): its tracks and its descriptive name, a message key
     * suffix ({@code BoardEditor.junction.<name>}) that names the side {@code side} where it has one (-1: none).
     */
    public record Junction(int tracks, String name, int side) { }

    /** The tracks of {@link #junctions}, the default first. */
    public static List<Integer> variants(int sides) { return junctions(sides).stream().map(Junction::tracks).toList(); }

    /**
     * The sensible layouts of {@code sides}, the default first, each named: for a Y without a through line its three
     * stems ("Y from N") and the wye triangle ("Wye"); for a crossing (two or three straights) the plain crossing and
     * all slips ("Double slip" for two lines); for a crossing with a fifth side both turnouts, its turnout into one line
     * or the other ("Turnout to SE"), and all curves; for a straight with two more sides two apart both turnouts, the
     * curve crossing the straight, and all curves. Every other mask has one layout, the default.
     */
    public static List<Junction> junctions(int sides) {
        sides &= 63;
        List<Junction> result = new ArrayList<>();
        int count = Integer.bitCount(sides), straights = 0, on = 0, gentle = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((pieceSides(piece) & ~sides) != 0) { continue; }
            gentle |= 1 << piece;
            if (straight(piece)) { straights |= 1 << piece; on |= pieceSides(piece); }
        }
        int lines = Integer.bitCount(straights), extras = sides & ~on;
        int a = Integer.numberOfTrailingZeros(extras), b = 31 - Integer.numberOfLeadingZeros(extras);
        if (sides == 21 || sides == 42) {
            // The default's stem is on the N or S side, as the legacy art.
            add(result, new Junction(pieces(sides), "yFrom", (sides & side(0)) != 0 ? 0 : 3));
            for (int d = 0; d < 6; d++) {
                if ((sides & side(d)) != 0) { add(result, new Junction(stem(sides, d), "yFrom", d)); }
            }
            add(result, new Junction(gentle, "wye", -1));
        } else if (lines >= 2 && (count == 4 || count == 6)) {
            add(result, new Junction(pieces(sides), "crossing", -1));
            add(result, new Junction(gentle, lines == 2 ? "doubleSlip" : "allCurves", -1));
        } else if (lines == 2 && count == 5) {
            // The fifth side is the branch.
            add(result, new Junction(pieces(sides), "bothTurnouts", -1));
            for (int line = 0; line < 3; line++) {
                if ((straights & 1 << line) == 0) { continue; }
                int target = distance(line, a) == 2 ? line : line + 3;
                add(result, new Junction(straights | 1 << piece(a, target), "turnoutTo", target));
            }
            add(result, new Junction(gentle, "allCurves", -1));
        } else if (lines == 1 && count == 4 && distance(a, b) == 2) {
            // Both extra sides turn onto the line toward the same side of it.
            add(result, new Junction(pieces(sides), "turnoutsTo", (on & side((a + 2) % 6)) != 0 ? (a + 2) % 6 : (a + 4) % 6));
            add(result, new Junction(straights | 1 << piece(a, b), "crossingCurve", -1));
            add(result, new Junction(gentle, "allCurves", -1));
        } else {
            result.add(new Junction(pieces(sides), "default", -1));
        }
        return List.copyOf(result);
    }

    /** Adds {@code layout} unless its tracks are listed already. */
    private static void add(List<Junction> layouts, Junction layout) {
        if (layouts.stream().noneMatch(listed -> listed.tracks() == layout.tracks())) { layouts.add(layout); }
    }

    /** A rail appearance's junction layout ({@link HexAppearance#tracks}) reflected as a board flip reflects its exits. */
    public static HexAppearance flip(HexAppearance look, boolean horizontal, boolean vertical) {
        if (look.tracks() == null && look.beside() == null) { return look; }
        return HexAppearance.rail(look.tracks() == null ? 0
                    : flip(look.tracks(), sides -> BoardDecoration.flipSides(sides, horizontal, vertical)),
              look.beside() == null ? 0 : flipBeside(look.beside(), horizontal, vertical));
    }

    /** Reflect the physical rail side as a board flip reflects its exits. */
    public static int flipBeside(int value, boolean horizontal, boolean vertical) {
        if (value == 0) { return 0; }
        int result = 64;
        for (int d = 0; d < 6; d++) {
            boolean bit = (value & side(d)) != 0;
            if (horizontal && d % 3 == 0 || vertical && d % 3 != 0) { bit = !bit; }
            if (bit) { result |= BoardDecoration.flipSides(side(d), horizontal, vertical); }
        }
        return result;
    }

    /** {@code tracks} reflected as {@link BoardDecoration#flip} reflects the sides ({@code flipSides}). */
    static int flip(int tracks, java.util.function.IntUnaryOperator flipSides) {
        int result = 0;
        for (int piece = 0; piece < PIECES; piece++) {
            if ((tracks & 1 << piece) == 0) { continue; }
            int sides = flipSides.applyAsInt(pieceSides(piece));
            result |= 1 << piece(Integer.numberOfTrailingZeros(sides), 31 - Integer.numberOfLeadingZeros(sides));
        }
        return result;
    }

    // ---------------------------------------------------------------- geometry shared by the builder and its stops

    /** A buffer stop on a rail: where it stands in its hex (model px, +y north) and the turn that faces its buffers. */
    public record Stop(float x, float y, float rotation) { }

    /** The midpoint of the border in {@code direction}, from the hex centre, in model px (+y north). */
    public static float[] border(int direction) {
        Coords origin = new Coords(0, 0), next = origin.translated(direction);
        return new float[] { (HexTile.centerX(next, HexTile.MODEL_WIDTH) - HexTile.centerX(origin, HexTile.MODEL_WIDTH)) / 2,
              (HexTile.centerY(next, HexTile.HEIGHT) - HexTile.centerY(origin, HexTile.HEIGHT)) / 2 };
    }

    /** The closed end opposite a road's single exit, shared by the road and its corridor track. */
    public static float[] roadEnd(int direction) {
        float[] edge = border(direction);
        return new float[] { -edge[0] * HexTile.ROAD_END_REACH, -edge[1] * HexTile.ROAD_END_REACH };
    }

    /**
     * A lateral shift along the actual hex edge, {@code offset} model pixels right of the outward centre line.
     * The lattice is regular only to a 32nd: a plain perpendicular shift misses its diagonal borders by a hair, leaving
     * one rail endpoint outside its supporting ground and making its height sampler fall back to the plateau.
     */
    public static float[] borderOffset(int direction, float offset) {
        float[] at = border(direction);
        float length = (float) Math.hypot(at[0], at[1]);
        float x = at[1] / length * offset, y = -at[0] / length * offset;
        int edge = Math.floorMod(1 - direction, 6), next = (edge + 1) % 6;
        float nx = HexTile.cornerOffsetY(next, HexTile.HEIGHT) - HexTile.cornerOffsetY(edge, HexTile.HEIGHT);
        float ny = HexTile.cornerOffsetX(edge, HexTile.MODEL_WIDTH) - HexTile.cornerOffsetX(next, HexTile.MODEL_WIDTH);
        float along = (x * nx + y * ny) / (at[0] * nx + at[1] * ny);
        return new float[] { x - at[0] * along, y - at[1] * along };
    }

    /** How far the stub of a stranded side reaches in from its border. */
    public static float stub(int sides) { return Integer.bitCount(sides & 63) == 3 ? STUB_SHORT : STUB_LONG; }

    /**
     * The buffer stops of a rail with these drawn sides: one at each closed track end. Each faces the track it closes
     * and stands {@link #STOP_DEPTH} before its end.
     */
    public static List<Stop> stops(int sides) {
        return stops(sides, Corridor.NONE);
    }

    /** Stops follow the same lateral placement as the track and its riders. */
    public static List<Stop> stops(int sides, Corridor corridor) {
        List<Stop> result = new ArrayList<>(2);
        for (Line line : lines(sides, pieces(sides), corridor)) {
            float[] a = line.at(0), b = line.at(1);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float dx = (b[0] - a[0]) / length, dy = (b[1] - a[1]) / length;
            if (line.from() < 0) {
                result.add(new Stop(a[0] + dx * STOP_DEPTH, a[1] + dy * STOP_DEPTH,
                      (float) Math.toDegrees(Math.atan2(-dx, dy))));
            }
            if (line.to() < 0) {
                result.add(new Stop(b[0] - dx * STOP_DEPTH, b[1] - dy * STOP_DEPTH,
                      (float) Math.toDegrees(Math.atan2(dx, -dy))));
            }
        }
        return result;
    }

    /**
     * The point at {@code t} (0 to 1) of the course from end {@code a} to end {@code b} (model px from the hex centre): a
     * cubic whose handles ({@link #HANDLE}) leave each end along the line through the hex centre, so it continues the
     * neighbour's course squarely. Between opposite sides it is the straight; between sides two apart it stays within
     * half a pixel of the 60-degree arc.
     */
    public static float[] course(float ax, float ay, float bx, float by, float t) {
        float u = 1 - t, wa = u * u * u + 3 * u * u * t * HANDLE, wb = 3 * u * t * t * HANDLE + t * t * t;
        return new float[] { ax * wa + bx * wb, ay * wa + by * wb };
    }

    /**
     * One centre line of a track in plan, model px from the hex centre (+y north): the course ({@link #course}) from
     * {@code a} to {@code b}, each end on the border of a side ({@code from}, {@code to}) or, -1, inside the hex.
     */
    public record Line(int from, int to, float[] a, float[] b, float oa, float ob) {
        public Line(int from, int to, float[] a, float[] b) { this(from, to, a, b, 0, 0); }

        /** The course, with a smooth lateral shift whose end tangent stays perpendicular to the border. */
        public float[] at(float t) {
            float[] p = course(a[0], a[1], b[0], b[1], t);
            if (oa == 0 && ob == 0) { return p; }
            float u = 1 - t;
            float da = -3 * u * u + 3 * HANDLE * (u * u - 2 * u * t);
            float db = 3 * HANDLE * (2 * u * t - t * t) + 3 * t * t;
            float dx = a[0] * da + b[0] * db, dy = a[1] * da + b[1] * db;
            if (dx * dx + dy * dy < 1e-12f) { dx = b[0] - a[0]; dy = b[1] - a[1]; }
            float length = (float) Math.hypot(dx, dy), offset = oa + t * t * (3 - 2 * t) * (ob - oa);
            if (length > 1e-6f) { p[0] += dy / length * offset; p[1] -= dx / length * offset; }
            float blend = t * t * (3 - 2 * t);
            meetBorder(p, from, -oa, 1 - blend);
            meetBorder(p, to, ob, blend);
            return p;
        }

        private static void meetBorder(float[] point, int direction, float offset, float weight) {
            if (direction < 0 || offset == 0 || weight == 0) { return; }
            float[] edge = border(direction), shift = borderOffset(direction, offset);
            float length = (float) Math.hypot(edge[0], edge[1]);
            point[0] += weight * (shift[0] - edge[1] / length * offset);
            point[1] += weight * (shift[1] + edge[0] / length * offset);
        }
    }

    /**
     * The centre lines of a track with these drawn {@code sides} and {@code pieces}, the one composition the builder
     * lays and rolling stock rides: each piece's course between its sides, each stranded side's stub from its border, a
     * single side's run from the centre, or the short N-S stub of a marker without sides.
     */
    public static List<Line> lines(int sides, int pieces) {
        sides &= 63;
        if (sides == 0) { return List.of(new Line(-1, -1, new float[] { 0, -LONE_STUB }, new float[] { 0, LONE_STUB })); }
        if (Integer.bitCount(sides) == 1) {
            int d = Integer.numberOfTrailingZeros(sides);
            return List.of(new Line(-1, d, new float[] { 0, 0 }, border(d)));
        }
        List<Line> result = new ArrayList<>();
        for (int piece = 0; piece < PIECES; piece++) {
            if ((pieces & 1 << piece) == 0) { continue; }
            int pair = pieceSides(piece), a = Integer.numberOfTrailingZeros(pair), b = 31 - Integer.numberOfLeadingZeros(pair);
            result.add(new Line(a, b, border(a), border(b)));
        }
        int stranded = stranded(sides);
        for (int d = 0; d < 6; d++) {
            if ((stranded & side(d)) == 0) { continue; }
            float[] edge = border(d);
            float share = 1 - stub(sides) / (float) Math.hypot(edge[0], edge[1]);
            result.add(new Line(d, -1, edge, new float[] { edge[0] * share, edge[1] * share }));
        }
        return List.copyOf(result);
    }

    /** Corridor tracks share their road's terminal plane; option A shifts the track, option B retains its axis. */
    public static List<Line> lines(int sides, int pieces, Corridor corridor) {
        if (corridor.sides() == 0 && !corridor.roadEnd()) { return lines(sides, pieces); }
        return lines(sides, pieces).stream().map(line -> {
            float a = -corridor.lateral(line.from()) * BESIDE_RAIL;
            float b = corridor.lateral(line.to()) * BESIDE_RAIL;
            if (line.from() < 0) { a = b; }
            if (line.to() < 0) { b = a; }
            float[] start = line.from() < 0 && line.to() >= 0 && corridor.roadEnd()
                  ? roadEnd(line.to()) : line.a();
            return new Line(line.from(), line.to(), start, line.b(), a, b);
        }).toList();
    }

    // ---------------------------------------------------------------- what rides the track

    /** A point on a route's track, model px from its hex's centre (+y north), heading as an object's rotation there. */
    public record Spot(float x, float y, float rotation) { }

    /** Whether an object of {@code asset} is a vehicle that rides the track's centre line (a platform stands beside it). */
    public static boolean vehicle(String asset) { return rides(asset) != null && !asset.equals(PLATFORM); }

    /** One centre line of a marker's track, sampled: its points, and the side each end lies on (-1: inside the hex). */
    private record Track(List<float[]> points, int from, int to) { }

    /** Points per piece's course: a 3 px step at most along a 62 px arc. */
    private static final int SAMPLES = 24;

    /** The centre lines ({@link #lines(int, int)}) of a marker's track on its drawn {@code sides}, a piece's course sampled. */
    private static List<Track> tracks(BoardDecoration marker, int sides, Corridor corridor) {
        List<Track> result = new ArrayList<>();
        for (Line line : lines(sides, pieces(marker, sides & 63), corridor)) {
            int samples = line.from() >= 0 && line.to() >= 0 ? SAMPLES : 1;
            List<float[]> points = new ArrayList<>(samples + 1);
            for (int i = 0; i <= samples; i++) {
                points.add(line.at((float) i / samples));
            }
            result.add(new Track(points, line.from(), line.to()));
        }
        return result;
    }

    /** A place on a track: the track, the segment from point {@code index}, and how far along it (0 to 1). */
    private record Place(Track line, int index, float share) {
        float[] point() {
            float[] a = line.points.get(index), b = line.points.get(index + 1);
            return new float[] { a[0] + (b[0] - a[0]) * share, a[1] + (b[1] - a[1]) * share };
        }

        /** The segment's direction, along the line from its first end to its second. */
        float[] direction() {
            float[] a = line.points.get(index), b = line.points.get(index + 1);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            return new float[] { (b[0] - a[0]) / length, (b[1] - a[1]) / length };
        }
    }

    /** The place of the marker's track nearest (x, y). */
    private static Place nearest(List<Track> tracks, float x, float y) {
        Place best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Track line : tracks) {
            for (int i = 0; i < line.points.size() - 1; i++) {
                float[] a = line.points.get(i), b = line.points.get(i + 1);
                float dx = b[0] - a[0], dy = b[1] - a[1];
                float share = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy)));
                double gap = Math.hypot(a[0] + dx * share - x, a[1] + dy * share - y);
                if (gap < distance) { distance = gap; best = new Place(line, i, share); }
            }
        }
        return best;
    }

    /** An object's rotation that heads its +y along {@code direction}. */
    private static float heading(float[] direction) {
        return (float) Math.toDegrees(Math.atan2(-direction[0], direction[1]));
    }

    /** A rotation in [0, 360). */
    private static float normal(float rotation) { return (rotation % 360 + 360) % 360; }

    /** How far apart two rotations are, in degrees (0 to 180). */
    private static float turn(float a, float b) { return Math.abs(((a - b) % 360 + 540) % 360 - 180); }

    /**
     * The point of {@code marker}'s track on {@code hex} nearest (x, y) (model px from the hex centre), and of the
     * track's two headings there the one nearer {@code rotation}: where a vehicle placed there stands.
     */
    public static Spot snap(Hex hex, BoardDecoration marker, float x, float y, float rotation) {
        return snap(hex, marker, x, y, rotation, Corridor.NONE);
    }

    /** The editor's snap with the same corridor captured by the renderer. */
    public static Spot snap(Hex hex, BoardDecoration marker, float x, float y, float rotation, Corridor corridor) {
        Place place = nearest(tracks(marker, drawnSides(marker, hex), corridor), x, y);
        float[] point = place.point();
        float along = heading(place.direction());
        return new Spot(point[0], point[1], normal(turn(along, rotation) <= 90 ? along : along + 180));
    }

    /**
     * Where {@link #follow} arrives: the hex and its marker whose track the spot lies on, and the spot in that hex's
     * frame. A vehicle standing there rides that marker ({@link #ride}) and belongs to that hex. {@code beyond} is how
     * far (model px) the spot lies past the track's end, on along its last heading; 0 on the track.
     */
    public record OnTrack(Coords at, BoardDecoration marker, Spot spot, float beyond) { }

    /**
     * The spot {@code distance} px along the track from {@code start} (on {@code marker} at {@code at}), the way it heads
     * (negative: behind it). Across a joined side it runs on along the neighbour's track, on the line that leaves the
     * opposite side, a straight before a curve; where the track ends it runs on along its last heading (by
     * {@link OnTrack#beyond}), still on the last marker it reached.
     */
    public static OnTrack follow(java.util.function.Function<Coords, Hex> board, Coords at, BoardDecoration marker,
          Spot start, float distance) {
        Place place = nearest(tracks(marker, drawnSides(marker, board.apply(at)),
              corridor(board.apply(at), at, board, onDeck(marker))), start.x(), start.y());
        float[] point = place.point(), direction = place.direction();
        // Forward along the line where it heads with the start, and the other way for a negative distance.
        boolean forward = turn(heading(direction), start.rotation()) <= 90 == distance >= 0;
        if (!forward) { direction = new float[] { -direction[0], -direction[1] }; }
        float left = Math.abs(distance);
        Track line = place.line();
        int index = place.index();
        Coords here = at;
        BoardDecoration current = marker;
        float beyond = 0;
        while (true) {
            float[] next = line.points.get(forward ? index + 1 : index);
            float step = (float) Math.hypot(next[0] - point[0], next[1] - point[1]);
            if (step > 0) { direction = new float[] { (next[0] - point[0]) / step, (next[1] - point[1]) / step }; }
            if (left <= step) {
                point = new float[] { point[0] + direction[0] * left, point[1] + direction[1] * left };
                break;
            }
            left -= step;
            point = next;
            index += forward ? 1 : -1;
            if (index >= 0 && index < line.points.size() - 1) { continue; }
            // The line's end: across a joined side onto the neighbour's track, else on along the last heading.
            int side = forward ? line.to() : line.from();
            Coords neighbour = side < 0 ? null : here.translated(side);
            Hex hex = neighbour == null ? null : board.apply(neighbour);
            BoardDecoration joined = hex == null ? null : joined(board.apply(here), current, side, hex);
            Track onward = joined == null ? null : onward(tracks(joined, drawnSides(joined, hex),
                  corridor(hex, neighbour, board, onDeck(joined))), (side + 3) % 6);
            if (onward == null) {
                point = new float[] { point[0] + direction[0] * left, point[1] + direction[1] * left };
                beyond = left;
                break;
            }
            here = neighbour; current = joined; line = onward;
            forward = onward.from() == (side + 3) % 6;
            index = forward ? 0 : line.points.size() - 1;
            point = line.points.get(index);
        }
        float rotation = heading(direction);
        return new OnTrack(here, current, new Spot(point[0], point[1], normal(distance >= 0 ? rotation : rotation + 180)),
              beyond);
    }

    /**
     * The line that leaves {@code side}: the straight through it before a curve, a curve before a run that ends inside
     * the hex (a dead end's run to its buffer stop, a stranded side's stub), else none.
     */
    private static Track onward(List<Track> tracks, int side) {
        Track result = null;
        for (Track line : tracks) {
            if (line.from() != side && line.to() != side) { continue; }
            int other = line.from() == side ? line.to() : line.from();
            if (other == (side + 3) % 6) { return line; }
            if (result == null || other >= 0 && (result.from() < 0 || result.to() < 0)) { result = line; }
        }
        return result;
    }
}
