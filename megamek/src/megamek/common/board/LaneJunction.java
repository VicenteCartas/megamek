/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How traffic crosses a junction that single-lane, one-way roads ({@link SingleLane}) join, by the rules of right-hand
 * traffic in continental Europe (the 1968 Vienna Conventions on road traffic and on road signs): the movements its arms
 * allow, which of them conflict, which road keeps priority, which arms wait, and the signs that tell drivers so. A pure
 * function of the junction's arms as side masks (bit {@code d} for side {@code d}, numbered clockwise from north), so the
 * 3D board, its features and the editor read every junction alike.
 * <p>
 * An arm is two-way (a two-lane road), an entrance (a single lane whose traffic comes in) or an exit (a single lane traffic
 * leaves by). On a single-lane hex itself (a hub) every arm is an entrance or an exit.
 * <ul>
 * <li>A movement runs from a two-way arm or an entrance into another two-way arm or an exit, never back the way it came,
 * and not where the junction closes the turn ({@code closed}, SingleLane#closedTurns).</li>
 * <li>Two movements from different arms conflict where they end in the same arm (they merge) or cross. Traffic keeps
 * right: a two-way arm's arriving lane lies just anticlockwise of its middle and its departing lane just clockwise of it,
 * a single lane at its middle, and two movements cross where their chords across the hex do.</li>
 * <li>The priority road is the best pair of arms a movement joins, never a 120 degree hairpin: two two-way arms first,
 * straight before a 60 degree bend. Where at most one two-way road meets it: straight before bent, then the pair more
 * traffic arrives along, then an entrance's road before an exit's. A tie leaves none, as do two-way roads meeting only in
 * a hairpin, unless a one-way road runs straight on through the junction past them. A bend that entrances join, whose
 * own traffic conflicts in ways only signs on the bend could settle, with nothing else to sign, is none either; where
 * only exits leave a two-way road, bent or not, its own course settles every conflict, and nothing needs controls.</li>
 * <li>Under priority signs, an arm off the priority road whose traffic conflicts with the priority road's waits: an
 * entrance or a hub's branch gives way, a two-way arm stops, as at any junction. Without a priority road, entrances give
 * way and two-way arms keep priority to the right; at a hub the branch with the other on its right gives way. Never
 * every arm.</li>
 * </ul>
 * The editor reports the layouts no road authority would build ({@link #issues}); none of them is refused.
 */
public final class LaneJunction {
    /** Whether anything at the junction needs controls. */
    public enum State {
        /** Nothing conflicts that the road's own course does not settle: no lines, signs or signals, whatever is chosen. */
        NONE,
        /** Movements conflict, but no road has priority and nothing waits: priority to the right, without signs. */
        PRIORITY_RIGHT,
        /** Some arms wait under priority signs, or every approach is signalled. */
        CONTROLLED
    }

    /** The sign that tells an approach which way it may go on. */
    public enum TurnSign {
        NONE,
        /** No left turn (C,11a): every left turn closed, none kept. */
        NO_LEFT,
        /** No right turn (C,11b). */
        NO_RIGHT,
        /** Direction to be followed (D,1), its arrows at the kept movements' true angles ({@link #directions}). */
        DIRECTIONS
    }

    /** How bad a layout is, as the editor reports it. */
    public enum Severity { ERROR, WARNING, NOTE }

    /** A layout no road authority would build, or one that strands traffic. */
    public enum Kind {
        /** Traffic arriving along an entrance (or a hub's branch) has nowhere to go. */
        STRANDED(Severity.ERROR),
        /** A two-way road's traffic arriving here can only turn back: only entrances go on. */
        DEAD_END_ROAD(Severity.WARNING),
        /** No movement here feeds a two-way road's lane leaving the junction. */
        SOURCELESS_ROAD(Severity.WARNING),
        /** No movement here can take an exit. */
        UNFED_EXIT(Severity.ERROR),
        /** A two-way road divides into two carriageways that run on the left. */
        REVERSED_SPLIT(Severity.ERROR),
        /** An entrance and an exit side by side run on the left of each other. */
        REVERSED_PAIR(Severity.WARNING),
        /** A two-lane hex whose arms are all single lanes: a single-lane hub drawn too wide. */
        TWO_LANE_HUB(Severity.NOTE);

        private final Severity severity;

        Kind(Severity severity) { this.severity = severity; }

        public Severity severity() { return severity; }
    }

    /** An issue at the junction: its kind, and the side (or pair of sides, {@code other} else -1) it concerns. */
    public record Issue(Kind kind, int side, int other) { }

    private static final Map<Long, LaneJunction> CACHE = new ConcurrentHashMap<>();

    private final int arms, two, in, out;
    private final boolean narrow;
    private final long closed, structural, moves;
    private final int arriving, conflicting, priority, waiting;
    private final State state;
    /** For each arm, the arms whose movements conflict with its own. */
    private final int[] conflicts = new int[6];

    private LaneJunction(int arms, int two, int in, int out, long closed, boolean narrow) {
        this.arms = arms;
        this.narrow = narrow;
        this.two = two;
        this.in = in;
        this.out = out;
        this.closed = closed;
        long all = 0;
        for (int from = 0; from < 6; from++) {
            if (((two | in) & 1 << from) == 0) { continue; }
            for (int to = 0; to < 6; to++) {
                if (to != from && ((two | out) & 1 << to) != 0) { all |= 1L << 6 * from + to; }
            }
        }
        structural = all;
        moves = all & ~closed;
        int starts = 0;
        for (int from = 0; from < 6; from++) {
            if ((moves >>> 6 * from & 63) != 0) { starts |= 1 << from; }
        }
        arriving = starts;
        for (int a = 0; a < 6; a++) {
            for (int b = 0; b < 6; b++) {
                if (!move(a, b)) { continue; }
                for (int c = a + 1; c < 6; c++) {
                    for (int d = 0; d < 6; d++) {
                        if (move(c, d) && conflict(a, b, c, d)) {
                            conflicts[a] |= 1 << c;
                            conflicts[c] |= 1 << a;
                        }
                    }
                }
            }
        }
        int involved = 0;
        for (int a = 0; a < 6; a++) {
            if (conflicts[a] != 0) { involved |= 1 << a; }
        }
        conflicting = involved;
        int road = rank();
        // Where only exits leave a two-way road, round a bend or a hairpin too, its own course settles every conflict:
        // traffic turning off it gives way to oncoming traffic.
        boolean exitsOnly = in == 0 && Integer.bitCount(two) == 2;
        if (!exitsOnly && road != 0 && gap(road) == 2 && unsettledBend(road) && waitsFor(road) == 0) { road = 0; }
        priority = road;
        int wait = road != 0 ? waitsFor(road) : two != 0 ? in & involved : hubYields();
        // Someone keeps priority: an approach without a sign.
        if (road == 0 && wait == involved) { wait = 0; }
        waiting = wait;
        state = involved == 0 || exitsOnly ? State.NONE : wait != 0 ? State.CONTROLLED
              : road != 0 && (involved & ~road) == 0 ? State.NONE : State.PRIORITY_RIGHT;
    }

    /**
     * The junction of the arms across {@code sides} (its road's, aprons left out), of which {@code single} are single lanes and
     * {@code inbound} the sides traffic enters by: a two-lane road's entrances, or every way into a {@code narrow}
     * (single-lane) hub. {@code closed} holds its closed turns as bit {@code 6 * a + b} (SingleLane#closedTurns).
     */
    public static LaneJunction of(int sides, int single, int inbound, long closed, boolean narrow) {
        int arms = sides & 63;
        int in = (narrow ? inbound : inbound & single) & arms;
        int out = (narrow ? arms : single & arms) & ~in;
        int two = arms & ~in & ~out;
        long turns = closed & (1L << 36) - 1;
        long key = turns | (long) two << 36 | (long) in << 42 | (long) out << 48 | (narrow ? 1L << 54 : 0);
        if (CACHE.size() > 4096) { CACHE.clear(); }
        return CACHE.computeIfAbsent(key, k -> new LaneJunction(arms, two, in, out, turns, narrow));
    }

    /**
     * The U-turns a two-lane junction closes by default, as bit {@code 6 * a + b} both ways round: where a two-way road
     * ({@code twoWay}) meets it, from each entrance into the exit on its left, round the nose between two carriageways:
     * the exit beside it, or the exit across the side between them where those three are its only {@code arms} and the
     * two-way road lies opposite that side (a Y).
     */
    public static long noseTurns(int arms, int twoWay, int entrances, int exits) {
        long result = 0;
        if (twoWay == 0) { return result; }
        for (int entrance = 0; entrance < 6; entrance++) {
            if ((entrances & 1 << entrance) == 0) { continue; }
            int beside = (entrance + 1) % 6, across = (entrance + 2) % 6;
            if ((exits & 1 << beside) != 0) { result |= 1L << 6 * entrance + beside | 1L << 6 * beside + entrance; }
            if (Integer.bitCount(arms & 63) == 3 && (twoWay & 1 << (entrance + 4) % 6) != 0 && (exits & 1 << across) != 0) {
                result |= 1L << 6 * entrance + across | 1L << 6 * across + entrance;
            }
        }
        return result;
    }

    /** Its arms: two-way, entrances and exits. */
    public int arms() { return arms; }

    /** Its two-way arms. */
    public int twoWay() { return two; }

    /** Its entrances (or a hub's ways in). */
    public int entrances() { return in; }

    /** Its exits (or a hub's ways out). */
    public int exits() { return out; }

    /** Whether no two-way road meets here: a hub of single lanes. */
    public boolean hub() { return two == 0; }

    /** Whether traffic may go from the arm across side {@code from} into the arm across side {@code to}. */
    public boolean move(int from, int to) { return from != to && (moves & 1L << 6 * from + to) != 0; }

    /** The movements, as bit {@code 6 * from + to}. */
    public long movements() { return moves; }

    /** The arms some movement starts from. */
    public int arriving() { return arriving; }

    /** The arms whose traffic conflicts with another arm's. */
    public int conflicting() { return conflicting; }

    /** The priority road's two arms; 0 for none. */
    public int priority() { return priority; }

    /** The arms that wait under priority signs. */
    public int waiting() { return waiting; }

    /** Whether the arm across {@code side} waits by giving way rather than stopping: an entrance, or a hub's branch. */
    public boolean yields(int side) { return (waiting & 1 << side) != 0 && (two & 1 << side) == 0; }

    public State state() { return state; }

    /** Whether anything here needs controls (#State). */
    public boolean controlled() { return state != State.NONE; }

    /** The arms traffic lights stand at, where the junction is signalled: every arriving one; none at a hub, or with nothing to control. */
    public int signalled() { return state == State.NONE || two == 0 ? 0 : arriving; }

    /**
     * The road a two-lane road's centre line runs on through the junction: the priority road where both its arms are
     * two-way, else, where nothing needs controls, the junction's only two two-way arms; 0 otherwise.
     */
    public int road() {
        if (priority != 0 && (priority & ~two) == 0) { return priority; }
        return state == State.NONE && Integer.bitCount(two) == 2 ? two : 0;
    }

    /** Whether some movement this junction allows crosses the centre line of the {@code road} between its two arms. */
    public boolean crossesRoad(int road) { return crosses(moves, road); }

    /** Whether some movement this junction closes would cross the centre line of the {@code road} between its two arms. */
    public boolean closedCrossing(int road) { return crosses(structural & ~moves, road); }

    /**
     * Its default controls where none are chosen: never signals at a hub; overhead lights where four or more two-lane
     * roads meet; traffic lights where five or more approaches arrive, or four with no road keeping priority; otherwise
     * priority signs.
     */
    public HexAppearance.TrafficControl defaultControl() {
        if (two == 0) { return HexAppearance.TrafficControl.STOPS; }
        if (Integer.bitCount(two) >= 4) { return HexAppearance.TrafficControl.GANTRY; }
        int count = Integer.bitCount(arriving);
        if (state != State.NONE && (count >= 5 || count >= 4 && priority == 0)) { return HexAppearance.TrafficControl.SIGNALS; }
        return HexAppearance.TrafficControl.STOPS;
    }

    /** The entrances needing a no-entry sign at their mouth: those some other arm's traffic could turn into. */
    public int noEntry() {
        int result = 0;
        for (int side = 0; side < 6; side++) {
            if ((in & 1 << side) != 0 && ((two | in) & ~(1 << side)) != 0) { result |= 1 << side; }
        }
        return result;
    }

    /** The exits needing a one-way sign: every exit two-way traffic turns into; none at a hub, where every road is one-way. */
    public int oneWay() { return two == 0 ? 0 : out; }

    /**
     * Where a two-way road divides into two carriageways, its entrance beside or symmetric with its exit and on the right
     * of it: the gore's nose between them, in half sides clockwise from north (twice a side, or a corner's {@code 2 * a +
     * 1}); -1 for no such split.
     */
    public int nose() {
        if (Integer.bitCount(two) != 1 || Integer.bitCount(arms) != 3) { return -1; }
        int w = Integer.numberOfTrailingZeros(two);
        for (int p = 0; p < 6; p++) {
            if ((in & 1 << p) != 0 && (out & 1 << (p + 1) % 6) != 0) { return 2 * p + 1; }
        }
        return (in & 1 << (w + 2) % 6) != 0 && (out & 1 << (w + 4) % 6) != 0 ? 2 * ((w + 3) % 6) : -1;
    }

    /**
     * The ways traffic from the arm across {@code from} may go on, as the arrows of a direction sign show them: bit 0 a
     * hard left (120 degrees), 1 a left (60), 2 straight on, 3 a right, 4 a hard right.
     */
    public int directions(int from) {
        int result = 0;
        for (int to = 0; to < 6; to++) {
            if (move(from, to)) { result |= 1 << Math.floorMod(to - from, 6) - 1; }
        }
        return result;
    }

    /**
     * The sign telling the approach across {@code from} where it may go on. A two-way approach that may not take an exit
     * is signed: no left (or right) turn where every left (right) turn is closed and none kept, else the ways it keeps, at
     * their true angles. An approach whose only ways on are sharp (120 degree) turns, while straight on or a gentle turn
     * leads into an entrance's no-entry sign, is shown its ways too. An entrance's U-turn round a nose is closed by the
     * kerbs alone.
     */
    public TurnSign turnSign(int from) {
        if (((two | in) & 1 << from) == 0) { return TurnSign.NONE; }
        int kept = directions(from), signed = 0;
        for (int to = 0; to < 6; to++) {
            if ((two & 1 << from) != 0 && (structural & 1L << 6 * from + to) != 0 && !move(from, to)) {
                signed |= 1 << Math.floorMod(to - from, 6) - 1;
            }
        }
        if (signed == 0) {
            boolean tempting = false;
            for (int to = 0; to < 6; to++) {
                int round = Math.floorMod(to - from, 6);
                tempting |= (in & 1 << to) != 0 && round >= 2 && round <= 4;
            }
            return kept != 0 && (kept & 0b01110) == 0 && tempting ? TurnSign.DIRECTIONS : TurnSign.NONE;
        }
        if (kept == 0) { return TurnSign.NONE; }
        if ((signed & ~0b00011) == 0 && (kept & 0b00011) == 0) { return TurnSign.NO_LEFT; }
        if ((signed & ~0b11000) == 0 && (kept & 0b11000) == 0) { return TurnSign.NO_RIGHT; }
        return TurnSign.DIRECTIONS;
    }

    /** The layouts here that strand traffic, leave a lane without a source, or run on the left. */
    public List<Issue> issues() {
        List<Issue> result = new ArrayList<>();
        for (int side = 0; side < 6; side++) {
            if (((two | in) & 1 << side) != 0 && (arriving & 1 << side) == 0) {
                result.add(new Issue((two & 1 << side) != 0 ? Kind.DEAD_END_ROAD : Kind.STRANDED, side, -1));
            }
        }
        for (int side = 0; side < 6; side++) {
            if (((two | out) & 1 << side) == 0) { continue; }
            boolean fed = false;
            for (int from = 0; from < 6; from++) { fed |= move(from, side); }
            if (!fed) { result.add(new Issue((two & 1 << side) != 0 ? Kind.SOURCELESS_ROAD : Kind.UNFED_EXIT, side, -1)); }
        }
        if (two == 0) {
            if (!narrow && Integer.bitCount(arms) >= 2) { result.add(new Issue(Kind.TWO_LANE_HUB, -1, -1)); }
            return result;
        }
        boolean split = Integer.bitCount(two) == 1 && Integer.bitCount(arms) == 3;
        for (int p = 0; p < 6; p++) {
            int q = (p + 1) % 6;
            // An exit with the entrance on its left. Where one of them lies opposite a two-way road, it may just carry that
            // road on as a one-way street, the other a side street off it: only a warning at a split, else nothing.
            if ((out & 1 << p) == 0 || (in & 1 << q) == 0) { continue; }
            boolean opposite = (two & (1 << (p + 3) % 6 | 1 << (q + 3) % 6)) != 0;
            if (opposite && !split) { continue; }
            result.add(new Issue(split && !opposite ? Kind.REVERSED_SPLIT : Kind.REVERSED_PAIR, p, q));
        }
        if (split) {
            int w = Integer.numberOfTrailingZeros(two);
            if ((out & 1 << (w + 2) % 6) != 0 && (in & 1 << (w + 4) % 6) != 0) {
                result.add(new Issue(Kind.REVERSED_SPLIT, (w + 2) % 6, (w + 4) % 6));
            }
        }
        return result;
    }

    /** The narrower of the two angles between sides, in sides: 1 adjacent (a 120 degree turn), 2 a 60 degree turn, 3 straight. */
    private static int gap(int a, int b) {
        int d = Math.floorMod(a - b, 6);
        return Math.min(d, 6 - d);
    }

    private static int gap(int pair) {
        int a = Integer.numberOfTrailingZeros(pair);
        return gap(a, Integer.numberOfTrailingZeros(pair & ~(1 << a)));
    }

    /** Where a movement starts on the rim, in twelfths of a side: a two-way arm's arriving lane anticlockwise of its middle. */
    private int start(int side) { return Math.floorMod(12 * side - ((two & 1 << side) != 0 ? 1 : 0), 72); }

    /** Where a movement ends on the rim: a two-way arm's departing lane clockwise of its middle. */
    private int end(int side) { return 12 * side + ((two & 1 << side) != 0 ? 1 : 0); }

    /** Whether rim point {@code x} lies strictly inside the clockwise arc from {@code s} to {@code t}. */
    private static boolean inside(int x, int s, int t) {
        int along = Math.floorMod(x - s, 72);
        return along > 0 && along < Math.floorMod(t - s, 72);
    }

    private static boolean chordsCross(int s1, int t1, int s2, int t2) { return inside(s2, s1, t1) != inside(t2, s1, t1); }

    private boolean conflict(int a, int b, int c, int d) {
        return a != c && (b == d || chordsCross(start(a), end(b), start(c), end(d)));
    }

    private boolean crosses(long movements, int road) {
        int p = Integer.numberOfTrailingZeros(road), q = Integer.numberOfTrailingZeros(road & ~(1 << p));
        for (int from = 0; from < 6; from++) {
            for (int to = 0; to < 6; to++) {
                if ((movements & 1L << 6 * from + to) != 0 && chordsCross(start(from), end(to), 12 * p, 12 * q)) { return true; }
            }
        }
        return false;
    }

    /** The priority road by its rank (see the class comment), or 0 where none is best. */
    private int rank() {
        int best = -1, result = 0;
        boolean tie = false, paired = false;
        for (int pass = 0; pass < 2 && !paired; pass++) {
            for (int p = 0; p < 6; p++) {
                for (int q = p + 1; q < 6; q++) {
                    int pair = 1 << p | 1 << q;
                    if ((arms & pair) != pair || gap(p, q) < 2 || !move(p, q) && !move(q, p)) { continue; }
                    boolean both = (two & pair) == pair;
                    if (pass == 0 != both) { continue; }
                    int key = 100 * gap(p, q);
                    if (pass == 1) {
                        int kind = (two & pair) != 0 && (in & pair) != 0 ? 2 : (in & pair) != 0 ? 1 : 0;
                        key += 10 * Integer.bitCount(arriving & pair) + kind;
                    }
                    paired = true;
                    if (key > best) { best = key; result = pair; tie = false; } else if (key == best) { tie = true; }
                }
            }
            // Two-way roads meeting only in a hairpin keep priority to the right between them, and no one-way road takes
            // priority over them but one running straight on through the junction, as a road through does over side roads.
            if (pass == 0 && !paired && Integer.bitCount(two) >= 2) {
                for (int p = 0; p < 6; p++) {
                    int q = (p + 3) % 6;
                    if ((in & 1 << p) != 0 && (out & 1 << q) != 0 && move(p, q)) { return 1 << p | 1 << q; }
                }
                return 0;
            }
        }
        return tie ? 0 : result;
    }

    /** Whether two movements off a bent priority road conflict where neither follows the road: only signs on it could say who goes. */
    private boolean unsettledBend(int road) {
        int p = Integer.numberOfTrailingZeros(road), q = Integer.numberOfTrailingZeros(road & ~(1 << p));
        for (int b = 0; b < 6; b++) {
            if (!move(p, b) || b == q) { continue; }
            for (int d = 0; d < 6; d++) {
                if (move(q, d) && d != p && conflict(p, b, q, d)) { return true; }
            }
        }
        return false;
    }

    /** The arriving arms off the priority {@code road} whose traffic conflicts with its arms'. */
    private int waitsFor(int road) {
        int result = 0;
        for (int side = 0; side < 6; side++) {
            if ((arriving & ~road & 1 << side) != 0 && (conflicts[side] & road) != 0) { result |= 1 << side; }
        }
        return result;
    }

    /** At a hub, of exactly two arms in conflict, the one with the other on its right (anticlockwise of it). */
    private int hubYields() {
        if (Integer.bitCount(conflicting) != 2) { return 0; }
        int a = Integer.numberOfTrailingZeros(conflicting), b = Integer.numberOfTrailingZeros(conflicting & ~(1 << a));
        int round = Math.floorMod(b - a, 6);
        return round >= 4 ? 1 << a : round >= 1 && round <= 2 ? 1 << b : 0;
    }
}
