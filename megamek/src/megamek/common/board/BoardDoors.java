/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The large doors of one building ({@link HexAppearance#doors}) and their links, as the building rules have them
 * ({@code megamek.common.units.BuildingDoors} of the rules branch, and MekBay's designer): two doors touch when their
 * hexsides share exactly one corner, in one hex or two; touching doors of one height may be linked into one opening;
 * a link is a group number its doors share, and a group is a run of touching doors.
 */
public final class BoardDoors {
    private BoardDoors() { }

    /** A hexside of a building hex, clockwise from north. */
    public record Door(Coords hex, int side) {
        /** Its two corners, each named by the three hexes that meet there. */
        private List<Set<Coords>> corners() {
            Coords across = hex.translated(side);
            return List.of(Set.of(hex, across, hex.translated((side + 5) % 6)), Set.of(hex, across, hex.translated((side + 1) % 6)));
        }
    }

    /** Whether the two hexsides share exactly one corner: side by side in one hex, or meeting at a corner of two. */
    public static boolean touch(Door a, Door b) {
        int shared = 0;
        for (Set<Coords> corner : a.corners()) { if (b.corners().contains(corner)) { shared++; } }
        return shared == 1;
    }

    /** A building's doors by hexside: each one's height in levels and its link group (0 for none). Editable. */
    public static final class Plan {
        private final Map<Door, int[]> doors = new LinkedHashMap<>();

        /** The doors the hexes {@code cells} store, read through {@code appearance}. */
        public Plan(Collection<Coords> cells, Function<Coords, Map<String, HexAppearance>> appearance) {
            for (Coords cell : cells) {
                var stored = appearance.apply(cell);
                int[] heights = HexAppearance.doors(stored), groups = HexAppearance.doorLinks(stored);
                for (int side = 0; side < 6; side++) {
                    if (heights[side] > 0) { doors.put(new Door(cell, side), new int[] { heights[side], groups[side] }); }
                }
            }
            normalize();
        }

        public Set<Door> doors() { return doors.keySet(); }

        public int height(Door door) { return doors.containsKey(door) ? doors.get(door)[0] : 0; }

        public int group(Door door) { return doors.containsKey(door) ? doors.get(door)[1] : 0; }

        public boolean linked(Door a, Door b) { return group(a) > 0 && group(a) == group(b) && touch(a, b); }

        /** The pairs of doors that touch, each once: where a link can be, or is. */
        public List<Door[]> joints() {
            List<Door> all = List.copyOf(doors.keySet());
            List<Door[]> result = new ArrayList<>();
            for (int first = 0; first < all.size(); first++) {
                for (int second = first + 1; second < all.size(); second++) {
                    if (touch(all.get(first), all.get(second))) { result.add(new Door[] { all.get(first), all.get(second) }); }
                }
            }
            return result;
        }

        /** The doors of {@code door}'s opening, itself among them. */
        public List<Door> opening(Door door) {
            int group = group(door);
            if (group == 0) { return List.of(door); }
            return doors.keySet().stream().filter(other -> group(other) == group).toList();
        }

        /**
         * Gives {@code door} a height, and every door of its opening with it; 0 takes this door alone away. No door
         * rises above its own hex's {@code storeys}: the opening is as high as its lowest hex allows.
         */
        public void set(Door door, int height, ToIntFunction<Coords> storeys) {
            if (height <= 0) {
                doors.remove(door);
                normalize();
                return;
            }
            List<Door> opening = doors.containsKey(door) ? opening(door) : List.of(door);
            for (Door each : opening) { height = Math.min(height, Math.max(1, storeys.applyAsInt(each.hex()))); }
            doors.computeIfAbsent(door, added -> new int[2]);
            for (Door each : opening) { doors.get(each)[0] = height; }
        }

        /**
         * Links two touching doors into one opening, with every door either was linked to; all take the lowest of
         * their heights. Nothing for doors that do not touch.
         */
        public void link(Door a, Door b) {
            if (!doors.containsKey(a) || !doors.containsKey(b) || !touch(a, b)) { return; }
            Set<Door> joined = new HashSet<>(opening(a));
            joined.addAll(opening(b));
            int height = joined.stream().mapToInt(this::height).min().orElseThrow();
            int group = group(a) > 0 ? group(a) : group(b) > 0 ? group(b) : free();
            for (Door each : joined) { doors.put(each, new int[] { height, group }); }
        }

        /**
         * Parts two linked doors: the opening ends between them, each side of it an opening of its own (a door left
         * alone is linked to nothing). An opening that runs all the way round its building and meets itself opens
         * here and stays one.
         */
        public void unlink(Door a, Door b) {
            if (!linked(a, b)) { return; }
            // What a still reaches without stepping from a to b.
            Set<Door> side = new HashSet<>(List.of(a));
            ArrayDeque<Door> open = new ArrayDeque<>(side);
            while (!open.isEmpty()) {
                Door from = open.poll();
                for (Door to : opening(a)) {
                    boolean parted = from.equals(a) && to.equals(b) || from.equals(b) && to.equals(a);
                    if (!parted && touch(from, to) && side.add(to)) { open.add(to); }
                }
            }
            if (side.contains(b)) { return; }
            int group = free();
            for (Door each : side) { doors.get(each)[1] = group; }
            normalize();
        }

        /**
         * A group is a run of touching doors of one height: a door of another height or out of touch leaves it, a
         * second run of the same number takes a new one, and a door left alone has none.
         */
        private void normalize() {
            Set<Door> placed = new HashSet<>();
            Set<Integer> used = new HashSet<>();
            for (Door start : List.copyOf(doors.keySet())) {
                if (group(start) == 0 || !placed.add(start)) { continue; }
                List<Door> run = new ArrayList<>(List.of(start));
                for (int index = 0; index < run.size(); index++) {
                    for (Door other : doors.keySet()) {
                        if (group(other) == group(start) && height(other) == height(start) && touch(run.get(index), other)
                              && placed.add(other)) { run.add(other); }
                    }
                }
                int group = run.size() < 2 ? 0 : used.add(group(start)) ? group(start) : 0;
                if (run.size() >= 2 && group == 0) {
                    group = 1;
                    while (used.contains(group) || taken(group, run)) { group++; }
                    used.add(group);
                }
                for (Door each : run) { doors.get(each)[1] = group; }
            }
        }

        /** Whether a door outside {@code run} has {@code group}. */
        private boolean taken(int group, List<Door> run) {
            return doors.keySet().stream().anyMatch(door -> group(door) == group && !run.contains(door));
        }

        private int free() {
            int group = 1;
            while (taken(group, List.of())) { group++; }
            return group;
        }

        /** What {@code cell} stores for its doors now ({@link HexAppearance#ofDoors}); null for none. */
        public HexAppearance stored(Coords cell) {
            int[] heights = new int[6], groups = new int[6];
            for (int side = 0; side < 6; side++) {
                Door door = new Door(cell, side);
                heights[side] = height(door);
                groups[side] = group(door);
            }
            return HexAppearance.ofDoors(heights, groups);
        }
    }
}
