/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * Single-lane, one-way paved roads ({@link HexAppearance#lanes}): which hexes carry one, the way their traffic runs, and
 * the runs of them the editor turns as one. A single-lane road is cosmetic, like the junction controls: the 3D board
 * draws it half as wide as a two-lane road, with direction arrows, and where one joins a two-lane road it is that road's
 * entrance (an on-ramp: its traffic merges into the two-lane road and gives way) or exit (an off-ramp: two-lane traffic
 * leaves by it). Movement rules and the 2D views ignore it.
 * <p>
 * Its direction is stored on the single-lane hexes alone ({@link HexAppearance#inbound}): the sides through which traffic
 * enters each. A two-lane hex stores nothing; whether a single-lane road joining it is an entrance or an exit follows from
 * that neighbour ({@link #entrance}), so both ends of a run always agree with its arrows. Where no direction is stored,
 * every hex of a run derives the same one ({@link #inbound}). The rules read a board through a {@link View}, so the
 * editor's hexes and the 3D board's scene apply them alike.
 */
public final class SingleLane {
    /** The owner whose appearance stores the lanes: ground roads only, never a bridge deck's. */
    public static final String OWNER = "road";

    private SingleLane() { }

    /** What the single-lane rules read of a board, hex by hex; off the board, a hex has no road. */
    public interface View {
        /** Whether the hex's ground road is a single lane ({@link SingleLane#single(Hex)}). */
        boolean single(Coords at);

        /** The sides its ground road leaves by; 0 without one. */
        int exits(Coords at);

        /** Its single lane's stored direction ({@link HexAppearance#inbound}); null where none is stored. */
        Integer stored(Coords at);

        /** Its two-lane road's stored exit approaches ({@link HexAppearance#exitFrom}); null where none are stored. */
        Long exitFrom(Coords at);
    }

    /** A board's hexes as the single-lane rules read them. */
    public static View view(Function<Coords, Hex> board) {
        return new View() {
            @Override
            public boolean single(Coords at) { return SingleLane.single(board.apply(at)); }

            @Override
            public int exits(Coords at) {
                Hex hex = board.apply(at);
                return hex != null && hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0;
            }

            @Override
            public Integer stored(Coords at) {
                Hex hex = board.apply(at);
                return hex == null ? null : HexAppearance.inbound(hex.getAppearance(), OWNER);
            }

            @Override
            public Long exitFrom(Coords at) {
                Hex hex = board.apply(at);
                return hex == null ? null : HexAppearance.exitFrom(hex.getAppearance(), OWNER);
            }
        };
    }

    /** The board with {@code hex} at {@code at}, which it may not hold yet. */
    private static View view(Hex hex, Coords at, Function<Coords, Hex> board) {
        return view(where -> where.equals(at) ? hex : board.apply(where));
    }

    /** Whether a hex's ground road is the marked, paved kind (not an alley, dirt or gravel) a single lane may narrow. */
    public static boolean paved(Hex hex) {
        if (hex == null || !hex.containsTerrain(Terrains.ROAD)) { return false; }
        int level = hex.terrainLevel(Terrains.ROAD);
        return level != 2 && level != Terrains.ROAD_LVL_DIRT && level != Terrains.ROAD_LVL_GRAVEL;
    }

    /**
     * Whether a hex's ground road is drawn as a single, one-way lane: a paved road that stores one, without rails on its
     * ground, whose road and rail corridors keep their two lanes.
     */
    public static boolean single(Hex hex) {
        return paved(hex) && HexAppearance.singleLane(hex.getAppearance(), OWNER) && RailRoute.groundSides(hex) == 0;
    }

    /** The sides by which a hex's ground road connects to a neighbour's: both have the road and the reciprocal exit. */
    public static int links(View board, Coords at) {
        int exits = board.exits(at), result = 0;
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) != 0 && (board.exits(at.translated(d)) & 1 << (d + 3) % 6) != 0) { result |= 1 << d; }
        }
        return result;
    }

    public static int links(Hex hex, Coords at, Function<Coords, Hex> board) { return links(view(hex, at, board), at); }

    /** Whether a single-lane hex continues a run: at most two sides, so its traffic has one way through. */
    private static boolean course(View board, Coords at) {
        return board.single(at) && Integer.bitCount(board.exits(at)) <= 2;
    }

    /**
     * The sides by which traffic enters a single-lane hex, among its road exits; zero for a hex that is not single-lane.
     * Its stored direction where one is stored. Otherwise a hex of a run runs as the run does ({@link #forward}): as its
     * stored hexes say, or else from its start to its end, so every hex of it derives the same way. A fork or merge's
     * single-lane sides run as the roads beyond them do, two forks that store nothing by one fixed rule both read alike
     * across their border (traffic crosses it into the hex whose side it is numbered 3 to 5 on); its other sides let
     * traffic in when none of those does, and out otherwise.
     */
    public static int inbound(View board, Coords at) {
        if (!board.single(at)) { return 0; }
        int exits = board.exits(at);
        Integer stored = board.stored(at);
        if (stored != null) { return stored & exits; }
        if (course(board, at)) {
            Run run = run(board, at, -1);
            boolean forward = forward(board, run, where -> true);
            for (Step step : run.steps()) {
                if (step.at().equals(at)) { return step.inbound(forward) & exits; }
            }
            return 0;
        }
        int known = 0, in = 0;
        for (int d = 0; d < 6; d++) {
            Coords next = at.translated(d);
            int back = (d + 3) % 6;
            if ((exits & 1 << d) == 0 || !board.single(next) || (board.exits(next) & 1 << back) == 0) { continue; }
            known |= 1 << d;
            boolean arrives = board.stored(next) != null || course(board, next)
                  ? (inbound(board, next) & 1 << back) == 0 : d >= 3;
            if (arrives) { in |= 1 << d; }
        }
        int open = exits & ~known;
        if (known == 0) { return Integer.lowestOneBit(open); }
        return in != 0 ? in : open;
    }

    public static int inbound(Hex hex, Coords at, Function<Coords, Hex> board) { return inbound(view(hex, at, board), at); }

    /**
     * Whether traffic from the single-lane road across {@code side} enters this hex: an entrance (on-ramp), whose traffic
     * merges and gives way; false for an exit (off-ramp), which traffic leaves this hex by, or when no single-lane road
     * joins there. Read from the neighbour, which alone stores its direction.
     */
    public static boolean entrance(View board, Coords at, int side) {
        Coords next = at.translated(side);
        int back = (side + 3) % 6;
        if ((board.exits(at) & 1 << side) == 0 || !board.single(next) || (board.exits(next) & 1 << back) == 0) { return false; }
        return (inbound(board, next) & 1 << back) == 0;
    }

    public static boolean entrance(Hex hex, Coords at, int side, Function<Coords, Hex> board) {
        return entrance(view(hex, at, board), at, side);
    }

    /**
     * The sides of a two-lane hex whose traffic may take the exit (off-ramp) leaving it across {@code side}: of its
     * approaches (its road's sides no single lane joins), those it stores for that exit ({@link HexAppearance#exitFrom}),
     * or all of them where it stores none of them; 0 when no exit leaves there. The choice is the junction's own, so it
     * never reaches another junction the same single lane joins.
     */
    public static int feeds(View board, Coords at, int side) {
        Coords next = at.translated(side);
        int back = (side + 3) % 6;
        if (board.single(at) || (board.exits(at) & 1 << side) == 0 || !board.single(next)
              || (board.exits(next) & 1 << back) == 0 || (inbound(board, next) & 1 << back) == 0) { return 0; }
        int approaches = board.exits(at) & ~joined(board, at);
        Long stored = board.exitFrom(at);
        int chosen = stored == null ? 0 : (int) (stored >>> 6 * side) & approaches;
        return chosen == 0 ? approaches : chosen;
    }

    public static int feeds(Hex hex, Coords at, int side, Function<Coords, Hex> board) {
        return feeds(view(hex, at, board), at, side);
    }

    /**
     * The turns a two-lane hex closes, as bit {@code 6 * a + b} for the pair of sides {@code a, b}, both ways round: for
     * each exit (off-ramp) leaving it, the turns into it from its approaches that may not take it ({@link #feeds}); and,
     * where a two-way road meets it, the U-turn from an entrance into the exit on its left, round the nose between two
     * carriageways: beside it, or across the side between them where it and a lone two-way road opposite form a Y.
     */
    public static long closedTurns(View board, Coords at) {
        long result = 0;
        int exits = board.exits(at), joined = joined(board, at), approaches = exits & ~joined;
        for (int exit = 0; exit < 6; exit++) {
            int fed = feeds(board, at, exit);
            if (fed == 0) { continue; }
            for (int from = 0; from < 6; from++) {
                if ((approaches & ~fed & 1 << from) != 0) { result |= 1L << 6 * exit + from | 1L << 6 * from + exit; }
            }
        }
        int entrances = 0;
        for (int side = 0; side < 6; side++) {
            if ((joined & 1 << side) != 0 && entrance(board, at, side)) { entrances |= 1 << side; }
        }
        return result | LaneJunction.noseTurns(exits, approaches, entrances, joined & ~entrances);
    }

    public static long closedTurns(Hex hex, Coords at, Function<Coords, Hex> board) {
        return closedTurns(view(hex, at, board), at);
    }

    /** Whether a hex's ground road is marked as a paved road is (not a dirt or gravel one), alleys included. */
    private static boolean marked(Hex hex) {
        if (hex == null || !HexTile.road(hex)) { return false; }
        int level = hex.terrainLevel(Terrains.ROAD);
        return level != Terrains.ROAD_LVL_DIRT && level != Terrains.ROAD_LVL_GRAVEL;
    }

    /**
     * The sides of a hex's ground road at a single lane's width, as the 3D board draws them: all of a single-lane road's
     * but those meeting a bridge deck or a dirt or gravel road, which keep their two lanes' width, and a paved two-lane
     * road's where a single-lane road joins it, not across a neighbour's bridge deck. Of a two-lane road, its ramps.
     */
    public static int arms(Hex hex, Coords at, Function<Coords, Hex> board, int exits) {
        if (!marked(hex)) { return 0; }
        boolean narrow = single(hex);
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) == 0) { continue; }
            Hex next = board.apply(at.translated(d));
            if (next != null && bridgeApproach(hex, next, d)) { continue; }
            boolean joined = next != null && next.containsTerrainExit(Terrains.ROAD, (d + 3) % 6);
            if (narrow && joined && !marked(next)) { continue; }
            if (narrow || single(next) && joined) { result |= 1 << d; }
        }
        return result;
    }

    /**
     * The sides of a hex's ground road that run into roadless concrete (pavement it paves up to, an apron): no roads of a
     * junction there, neither for its markings nor for its controls.
     */
    public static int aprons(Hex hex, Coords at, Function<Coords, Hex> board, int exits) {
        int result = 0;
        for (int d = 0; d < 6; d++) {
            Hex next = board.apply(at.translated(d));
            if ((exits & 1 << d) != 0 && next != null && HexTile.concrete(next) && !HexTile.liquid(next) && !HexTile.road(next)
                  && !bridgeApproach(hex, next, d)) {
                result |= 1 << d;
            }
        }
        return result;
    }

    /**
     * Whether a road across {@code direction} approaches the neighbouring {@code bridge}'s deck rather than its ground:
     * both expose the side, and the deck is the layer the road meets (HexTile#bridgeApproach).
     */
    public static boolean bridgeApproach(Hex road, Hex bridge, int direction) {
        int reverse = (direction + 3) % 6;
        if (HexTile.liquid(road) || !bridge.containsTerrainExit(Terrains.BRIDGE, reverse)
              || (groundWays(road) & (1 << direction)) == 0) { return false; }
        boolean continuation = !HexTile.liquid(bridge) && (groundWays(bridge) & (1 << reverse)) != 0;
        return HexTile.bridgeApproach(road.getLevel(), bridge.getLevel(), BridgeSpan.deck(bridge), continuation);
    }

    private static int groundWays(Hex hex) {
        return (hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() : 0) | RailRoute.groundSides(hex);
    }

    /**
     * A ground road's stored junction controls, else its default ({@link HexAppearance#trafficControl}): where single-lane
     * {@code ramps} join it, neither they nor its {@code aprons} count as roads of the junction, and the junction's own
     * default applies ({@link LaneJunction#defaultControl}) where it is known.
     */
    public static HexAppearance.TrafficControl control(Map<String, HexAppearance> appearance, int exits, int ramps, int aprons,
          LaneJunction junction) {
        HexAppearance stored = appearance.get(OWNER);
        if ((exits & ramps & 63) == 0 || junction == null || stored != null && stored.trafficControl() != null) {
            return control(appearance, exits, ramps, aprons);
        }
        return junction.defaultControl();
    }

    public static HexAppearance.TrafficControl control(Map<String, HexAppearance> appearance, int exits, int ramps, int aprons) {
        return HexAppearance.trafficControl(appearance, OWNER, (exits & ramps & 63) != 0 ? exits & ~aprons : exits, ramps);
    }

    /**
     * The junction a hex's ground road forms with the single-lane roads joining it ({@link LaneJunction}), its aprons left
     * out; null where none joins it and it is no single lane itself.
     */
    public static LaneJunction junction(Hex hex, Coords at, Function<Coords, Hex> board) {
        if (hex == null || !hex.containsTerrain(Terrains.ROAD)) { return null; }
        int exits = hex.getTerrain(Terrains.ROAD).getExits() & 63;
        int single = arms(hex, at, board, exits);
        boolean narrow = single(hex);
        if (single == 0 && !narrow) { return null; }
        int inbound;
        if (narrow) { inbound = inbound(hex, at, board); } else {
            inbound = 0;
            for (int d = 0; d < 6; d++) {
                if ((single & 1 << d) != 0 && entrance(hex, at, d, board)) { inbound |= 1 << d; }
            }
        }
        long closed = narrow ? 0 : closedTurns(hex, at, board);
        return LaneJunction.of(exits & ~aprons(hex, at, board, exits), single, inbound, closed, narrow);
    }

    /** The sides of this hex where a single-lane road joins it, connected both ways. */
    public static int joined(View board, Coords at) {
        int links = links(board, at), result = 0;
        for (int d = 0; d < 6; d++) {
            if ((links & 1 << d) != 0 && board.single(at.translated(d))) { result |= 1 << d; }
        }
        return result;
    }

    public static int joined(Hex hex, Coords at, Function<Coords, Hex> board) { return joined(view(hex, at, board), at); }

    /**
     * One hex of a {@link Run}: its side towards the previous hex, or the road beyond the run's start ({@code back}), and
     * its side towards the next hex or beyond the run's end ({@code ahead}); -1 where the road ends.
     */
    public record Step(Coords at, int back, int ahead) {
        /** The inbound sides traffic running from the start of the run to its end uses, or the other way. */
        public int inbound(boolean forward) {
            int side = forward ? back : ahead;
            return side < 0 ? 0 : 1 << side;
        }
    }

    /** A course of single-lane hexes with one way through each, which one direction of travel runs along together. */
    public record Run(List<Step> steps, boolean loop) {
        public boolean contains(Coords at) { return steps.stream().anyMatch(step -> step.at().equals(at)); }
    }

    private static final Comparator<Coords> LEAST = Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY);

    /**
     * The run of single-lane hexes through {@code start}, entered from its side {@code from} (-1 to take the whole run in
     * its own order, from its end with the least coordinates): it continues through single-lane hexes of one course each,
     * joined both ways, and stops before a two-lane road, a single-lane fork or merge, a bridge deck, the board's edge or
     * a dead end, whose side it then leaves by. Null when {@code start} is not such a hex.
     */
    public static Run run(View board, Coords start, int from) {
        if (!course(board, start)) { return null; }
        int exits = board.exits(start);
        if (from >= 0) { return (exits & 1 << from) == 0 ? null : walk(board, start, from); }
        Coords at = start;
        int toward = exits == 0 ? -1 : Integer.numberOfTrailingZeros(exits);
        Set<Coords> seen = new HashSet<>(List.of(start));
        while (toward >= 0) {
            Coords previous = at.translated(toward);
            if (!course(board, previous) || (board.exits(previous) & 1 << (toward + 3) % 6) == 0) { break; }
            if (!seen.add(previous)) { return loop(board, seen.stream().min(LEAST).orElseThrow()); }
            int others = board.exits(previous) & ~(1 << (toward + 3) % 6);
            at = previous;
            toward = others == 0 ? -1 : Integer.numberOfTrailingZeros(others);
        }
        Run found = walk(board, at, toward);
        var last = found.steps().getLast();
        if (!found.loop() && LEAST.compare(last.at(), at) < 0) { return walk(board, last.at(), last.ahead()); }
        return found;
    }

    public static Run run(Function<Coords, Hex> board, Coords start, int from) { return run(view(board), start, from); }

    /** The run from {@code start}, entered from its side {@code from} (-1 where the road starts there). */
    private static Run walk(View board, Coords start, int from) {
        List<Step> steps = new ArrayList<>();
        Set<Coords> seen = new HashSet<>();
        Coords at = start;
        int back = from;
        while (true) {
            seen.add(at);
            int ahead = board.exits(at) & ~(back < 0 ? 0 : 1 << back);
            int side = ahead == 0 ? -1 : Integer.numberOfTrailingZeros(ahead);
            steps.add(new Step(at, back, side));
            if (side < 0) { break; }
            Coords next = at.translated(side);
            if (seen.contains(next)) { return new Run(List.copyOf(steps), next.equals(start)); }
            if (!course(board, next) || (board.exits(next) & 1 << (side + 3) % 6) == 0) { break; }
            at = next;
            back = (side + 3) % 6;
        }
        return new Run(List.copyOf(steps), false);
    }

    /** A closed ring of single-lane hexes, from {@code start} round in its lowest side's direction. */
    private static Run loop(View board, Coords start) {
        int exits = board.exits(start);
        int ahead = Integer.numberOfTrailingZeros(exits);
        int back = Integer.numberOfTrailingZeros(exits & ~(1 << ahead));
        return walk(board, start, back);
    }

    /**
     * Whether traffic on {@code run} runs from its start to its end, as its hexes' stored directions say: each stored
     * hex votes, those {@code weighty} carrying ten times the weight (the hexes it already had, against a stroke's new
     * ones); a tie runs forwards.
     */
    public static boolean forward(View board, Run run, Predicate<Coords> weighty) {
        int votes = 0;
        for (Step step : run.steps()) {
            Integer stored = board.stored(step.at());
            if (stored == null) { continue; }
            int exits = board.exits(step.at());
            int mask = stored & exits;
            int weight = weighty.test(step.at()) ? 10 : 1;
            if (mask == (step.inbound(true) & exits) && mask != (step.inbound(false) & exits)) { votes += weight; }
            else if (mask == (step.inbound(false) & exits) && mask != (step.inbound(true) & exits)) { votes -= weight; }
        }
        return votes >= 0;
    }

    public static boolean forward(Function<Coords, Hex> board, Run run, Predicate<Coords> weighty) {
        return forward(view(board), run, weighty);
    }

    /** The single-lane road appearance with its direction {@code inbound}, keeping the rest of {@code look}. */
    public static HexAppearance directed(HexAppearance look, int inbound) {
        return HexAppearance.withLanes(look, true, inbound & 63);
    }

    /** The runs through {@code at} and its sides: its own where it continues one, and its neighbours'. */
    public static List<Run> runsAround(Function<Coords, Hex> board, Coords at) {
        View lanes = view(board);
        List<Run> result = new ArrayList<>();
        Run own = run(lanes, at, -1);
        if (own != null) { result.add(own); }
        int exits = lanes.exits(at);
        for (int d = 0; d < 6; d++) {
            Coords next = at.translated(d);
            if ((exits & 1 << d) == 0 || own != null && own.contains(next)) { continue; }
            Run beside = run(lanes, next, -1);
            if (beside != null && result.stream().noneMatch(existing -> existing.contains(next))) { result.add(beside); }
        }
        return result;
    }

    /** A single lane's direction, a side mask, reflected as a board flip reflects its exits. */
    public static int flipSides(int sides, boolean horizontal, boolean vertical) {
        return BoardDecoration.flipSides(sides & 63, horizontal, vertical);
    }

    /**
     * A road appearance with its single lane's direction, or its two-lane road's exit approaches, reflected as a board
     * flip reflects its exits.
     */
    public static HexAppearance flip(HexAppearance look, boolean horizontal, boolean vertical) {
        if (look == null || look.inbound() == null && look.exitFrom() == null) { return look; }
        if (look.lanes() != null) { return HexAppearance.withLanes(look, true, flipSides(look.inbound(), horizontal, vertical)); }
        long flipped = 0;
        for (int exit = 0; exit < 6; exit++) {
            int sides = (int) (look.exitFrom() >>> 6 * exit & 63);
            if (sides == 0) { continue; }
            int to = Integer.numberOfTrailingZeros(flipSides(1 << exit, horizontal, vertical));
            flipped |= (long) flipSides(sides, horizontal, vertical) << 6 * to;
        }
        return HexAppearance.withExitFrom(look, flipped);
    }

    /** The appearance map with its road's single-lane direction flipped ({@link #flip}). */
    public static Map<String, HexAppearance> flip(Map<String, HexAppearance> appearance, boolean horizontal, boolean vertical) {
        HexAppearance road = appearance.get(OWNER);
        if (flip(road, horizontal, vertical) == road) { return appearance; }
        var result = new LinkedHashMap<>(appearance);
        result.put(OWNER, flip(road, horizontal, vertical));
        return result;
    }
}
