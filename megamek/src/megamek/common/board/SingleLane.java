/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
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
 * that neighbour ({@link #entrance}), so both ends of a run always agree with its arrows.
 */
public final class SingleLane {
    /** The owner whose appearance stores the lanes: ground roads only, never a bridge deck's. */
    public static final String OWNER = "road";

    private SingleLane() { }

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
    public static int links(Hex hex, Coords at, Function<Coords, Hex> board) {
        if (hex == null || !hex.containsTerrain(Terrains.ROAD)) { return 0; }
        int result = 0;
        for (int d = 0; d < 6; d++) {
            if (!hex.containsTerrainExit(Terrains.ROAD, d)) { continue; }
            Hex next = board.apply(at.translated(d));
            if (next != null && next.containsTerrainExit(Terrains.ROAD, (d + 3) % 6)) { result |= 1 << d; }
        }
        return result;
    }

    /**
     * The sides by which traffic enters a single-lane hex, among its road exits: its stored direction where one is stored;
     * otherwise each side towards a single-lane neighbour with a stored direction follows that neighbour, and the rest
     * are settled so that a two-way course has one way in and one way out (its lowest side in, where nothing decides).
     * Zero for a hex that is not single-lane.
     */
    public static int inbound(Hex hex, Coords at, Function<Coords, Hex> board) {
        if (!single(hex)) { return 0; }
        return inbound(hex.getTerrain(Terrains.ROAD).getExits() & 63, HexAppearance.inbound(hex.getAppearance(), OWNER), d -> {
            Hex next = board.apply(at.translated(d));
            return single(next) && next.containsTerrainExit(Terrains.ROAD, (d + 3) % 6)
                  ? HexAppearance.inbound(next.getAppearance(), OWNER) : null;
        });
    }

    /**
     * {@link #inbound(Hex, Coords, Function)} from what it reads, shared by the editor and the 3D board's scene: a single
     * lane's road {@code exits}, its {@code stored} direction (null where none is stored), and per side the stored
     * direction of a connected single-lane neighbour ({@code neighbours}; null where there is none or it stores none).
     */
    public static int inbound(int exits, Integer stored, IntFunction<Integer> neighbours) {
        exits &= 63;
        if (stored != null) { return stored & exits; }
        int known = 0, in = 0;
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) == 0) { continue; }
            Integer theirs = neighbours.apply(d);
            if (theirs == null) { continue; }
            known |= 1 << d;
            // Traffic entering the neighbour through our shared border leaves this hex by it.
            if ((theirs & 1 << (d + 3) % 6) == 0) { in |= 1 << d; }
        }
        int open = exits & ~known;
        if (open == 0) { return in; }
        if (Integer.bitCount(exits) == 2) {
            // One way in and one way out: an open side takes whichever role its known partner leaves.
            if (known != 0) { return in != 0 ? in : open; }
            return Integer.lowestOneBit(open);
        }
        // A fork or merge: where nothing known lets traffic in, it comes in by the open sides; else they let it out.
        if (known == 0) { return Integer.lowestOneBit(open); }
        return in != 0 ? in : open;
    }

    /**
     * Whether traffic from the single-lane road across {@code side} enters this hex: an entrance (on-ramp), whose traffic
     * merges and gives way; false for an exit (off-ramp), which traffic leaves this hex by, or when no single-lane road
     * joins there. Read from the neighbour, which alone stores its direction.
     */
    public static boolean entrance(Hex hex, Coords at, int side, Function<Coords, Hex> board) {
        Coords next = at.translated(side);
        Hex neighbour = board.apply(next);
        if (hex == null || !hex.containsTerrainExit(Terrains.ROAD, side) || !single(neighbour)
              || !neighbour.containsTerrainExit(Terrains.ROAD, (side + 3) % 6)) { return false; }
        return (inbound(neighbour, next, board) & 1 << (side + 3) % 6) == 0;
    }

    /** The sides of this hex where a single-lane road joins it, connected both ways. */
    public static int joined(Hex hex, Coords at, Function<Coords, Hex> board) {
        int result = 0;
        int links = links(hex, at, board);
        for (int d = 0; d < 6; d++) {
            if ((links & 1 << d) != 0 && single(board.apply(at.translated(d)))) { result |= 1 << d; }
        }
        return result;
    }

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

    /** Whether a single-lane hex continues a run: at most two connected sides, so its traffic has one way through. */
    private static boolean course(Hex hex, Coords at, Function<Coords, Hex> board) {
        return single(hex) && Integer.bitCount(links(hex, at, board)) <= 2;
    }

    /**
     * The run of single-lane hexes through {@code start}, entered from its side {@code from} (-1 to take the whole run in
     * the board's own order): it continues through single-lane hexes of one course each and stops before a two-lane road,
     * a single-lane fork or merge, or a dead end. Null when {@code start} is not such a hex.
     */
    public static Run run(Function<Coords, Hex> board, Coords start, int from) {
        Hex hex = board.apply(start);
        if (!course(hex, start, board)) { return null; }
        if (from >= 0) { return (links(hex, start, board) & 1 << from) == 0 ? null : walk(board, start, from); }
        // Walk back to the run's own start, so that every hex of it finds the same run in the same order.
        int links = links(hex, start, board);
        Coords at = start;
        int toward = links == 0 ? -1 : Integer.numberOfTrailingZeros(links);
        Set<Coords> seen = new HashSet<>(List.of(start));
        while (toward >= 0) {
            Coords previous = at.translated(toward);
            Hex prior = board.apply(previous);
            if (!course(prior, previous, board)) { break; }
            if (!seen.add(previous)) {
                // A closed ring has no start: begin it at its least coordinates.
                return loop(board, seen.stream().min(LEAST).orElseThrow());
            }
            int others = links(prior, previous, board) & ~(1 << (toward + 3) % 6);
            at = previous;
            toward = others == 0 ? -1 : Integer.numberOfTrailingZeros(others);
        }
        // Whichever hex it was found from, a run starts at its end with the least coordinates.
        Run found = walk(board, at, toward);
        var last = found.steps().getLast();
        if (!found.loop() && LEAST.compare(last.at(), at) < 0) { return walk(board, last.at(), last.ahead()); }
        return found;
    }

    private static final java.util.Comparator<Coords> LEAST = java.util.Comparator.comparingInt(Coords::getX)
          .thenComparingInt(Coords::getY);

    /** The run from {@code start}, entered from its side {@code from} (-1 where the road starts there). */
    private static Run walk(Function<Coords, Hex> board, Coords start, int from) {
        List<Step> steps = new ArrayList<>();
        Set<Coords> seen = new HashSet<>();
        Coords at = start;
        int back = from;
        while (true) {
            Hex hex = board.apply(at);
            seen.add(at);
            int ahead = links(hex, at, board) & ~(back < 0 ? 0 : 1 << back);
            int side = ahead == 0 ? -1 : Integer.numberOfTrailingZeros(ahead);
            steps.add(new Step(at, back, side));
            if (side < 0) { break; }
            Coords next = at.translated(side);
            if (seen.contains(next)) {
                return new Run(List.copyOf(steps), next.equals(start));
            }
            if (!course(board.apply(next), next, board)) { break; }
            at = next;
            back = (side + 3) % 6;
        }
        return new Run(List.copyOf(steps), false);
    }

    /** A closed ring of single-lane hexes, from {@code start} round in its lowest side's direction. */
    private static Run loop(Function<Coords, Hex> board, Coords start) {
        int links = links(board.apply(start), start, board);
        int ahead = Integer.numberOfTrailingZeros(links);
        int back = Integer.numberOfTrailingZeros(links & ~(1 << ahead));
        return walk(board, start, back);
    }

    /**
     * Whether traffic on {@code run} runs from its start to its end, as its hexes' stored directions say: each stored
     * hex votes, those {@code weighty} carrying ten times the weight (the hexes it already had, against a stroke's new
     * ones); a tie runs forwards.
     */
    public static boolean forward(Function<Coords, Hex> board, Run run, Predicate<Coords> weighty) {
        int votes = 0;
        for (Step step : run.steps()) {
            Hex hex = board.apply(step.at());
            Integer stored = hex == null ? null : HexAppearance.inbound(hex.getAppearance(), OWNER);
            if (stored == null) { continue; }
            int exits = hex.getTerrain(Terrains.ROAD).getExits() & 63;
            int mask = stored & exits;
            int weight = weighty.test(step.at()) ? 10 : 1;
            if (mask == (step.inbound(true) & exits) && mask != (step.inbound(false) & exits)) { votes += weight; }
            else if (mask == (step.inbound(false) & exits) && mask != (step.inbound(true) & exits)) { votes -= weight; }
        }
        return votes >= 0;
    }

    /** The single-lane road appearance with its direction {@code inbound}, keeping the rest of {@code look}. */
    public static HexAppearance directed(HexAppearance look, int inbound) {
        return HexAppearance.withLanes(look, true, inbound & 63);
    }

    /** The hexes of the runs through {@code at}'s single-lane sides; {@code at}'s own run where it continues one. */
    public static List<Run> runsAround(Function<Coords, Hex> board, Coords at) {
        List<Run> result = new ArrayList<>();
        Hex hex = board.apply(at);
        Run own = run(board, at, -1);
        if (own != null) { result.add(own); }
        int links = links(hex, at, board);
        for (int d = 0; d < 6; d++) {
            Coords next = at.translated(d);
            if ((links & 1 << d) == 0 || own != null && own.contains(next)) { continue; }
            Run beside = run(board, next, -1);
            if (beside != null && result.stream().noneMatch(existing -> existing.contains(next))) { result.add(beside); }
        }
        return result;
    }

    /** A single lane's direction, a side mask, reflected as a board flip reflects its exits. */
    public static int flipSides(int sides, boolean horizontal, boolean vertical) {
        return BoardDecoration.flipSides(sides & 63, horizontal, vertical);
    }

    /** A road appearance with its single lane's direction reflected as a board flip reflects its exits. */
    public static HexAppearance flip(HexAppearance look, boolean horizontal, boolean vertical) {
        if (look == null || look.inbound() == null) { return look; }
        return HexAppearance.withLanes(look, true, flipSides(look.inbound(), horizontal, vertical));
    }

    /** The appearance map with its road's single-lane direction flipped ({@link #flip}). */
    public static Map<String, HexAppearance> flip(Map<String, HexAppearance> appearance, boolean horizontal, boolean vertical) {
        HexAppearance road = appearance.get(OWNER);
        if (road == null || road.inbound() == null) { return appearance; }
        var result = new java.util.LinkedHashMap<>(appearance);
        result.put(OWNER, flip(road, horizontal, vertical));
        return result;
    }
}
