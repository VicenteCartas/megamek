/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * A visual-only instance owned by one Hex. Heights are board levels; XY uses hex width/height units. The model's local
 * scale is the uniform {@code scale} times {@code stretch} per axis, applied in object space before the rotations.
 * {@code colours} replace the model's colour slots.
 */
public record BoardDecoration(String id, String kind, String asset, String name, double x, double y,
      double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
      double rotationX, double rotationY, String group, boolean bare, int connections, Stretch stretch, Colours colours,
      int tracks, boolean pillars, String uid, int levels) implements Serializable {
    /** An ordinary object; authored building modules may additionally specify their assembled level count. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare, int connections, Stretch stretch, Colours colours,
          int tracks, boolean pillars, String uid) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX, rotationY,
              group, bare, connections, stretch, colours, tracks, pillars, uid, 0);
    }

    public BoardDecoration withLevels(int levels) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex,
              rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }
    /** An object's asset identity normally comes from the installed catalog. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare, int connections, Stretch stretch, Colours colours,
          int tracks, boolean pillars) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX, rotationY,
              group, bare, connections, stretch, colours, tracks, pillars, null);
    }

    /** Retains an asset pack's identity even while its catalog entry is unavailable. */
    public BoardDecoration withUid(String uid) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex,
              rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }
    /**
     * An object without pillars. {@code pillars}: a maglev route marker's Pillars toggle, concrete piers under the joints
     * of its beam ({@link RailRoute#run}), as a bridge's {@link HexAppearance#PILLARS}; only a maglev has it.
     */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare, int connections, Stretch stretch, Colours colours,
          int tracks) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, group, bare, connections, stretch, colours, tracks, false);
    }
    /** An object without a stored track layout: a route marker lays the default pieces of its sides. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare, int connections, Stretch stretch, Colours colours) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, group, bare, connections, stretch, colours, 0);
    }
    /** An object in its model's own colours. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare, int connections, Stretch stretch) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, group, bare, connections, stretch, Colours.NONE);
    }
    /** An unstretched object. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare, int connections) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, group, bare, connections, Stretch.NONE);
    }
    /**
     * An object without route connections. {@code connections} is a route marker's sides as a mask, N=1, NE=2, SE=4,
     * S=8, SW=16, NW=32 (the order of {@link Coords#translated(int)}); {@code tracks} its track pieces where they differ
     * from the default of those sides ({@link RailRoute}), else 0.
     */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group, boolean bare) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, group, bare, 0);
    }
    /**
     * An object without a winter override: a tree with a snow form shows it on snow unless {@code bare} keeps it bare.
     */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY, String group) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, group, false);
    }
    /** An ungrouped object. Group membership is stored only on members; {@code null} means ungrouped. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex,
          double rotationX, double rotationY) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX,
              rotationY, null);
    }
    /** The original rotation is the turn about the board's vertical Z axis. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, 0, 0);
    }
    /** Historical instances clipped paint to their owner. New editor brushes explicitly allow overflow. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, "decal".equals(kind));
    }
    /** Positive object-space factors along the model's own X, Y and Z axes; a mirror stays a separate flag. */
    public record Stretch(double x, double y, double z) implements Serializable {
        public static final Stretch NONE = new Stretch(1, 1, 1);

        public Stretch {
            if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && x > 0 && y > 0 && z > 0)) {
                throw new IllegalArgumentException("Stretch factors must be finite and positive");
            }
        }
    }

    /**
     * Replacement colours for the model's colour slots, in slot order: an sRGB {@code #rrggbb} value, or null where the
     * slot keeps the model's own colour. A model marks up to {@link #SLOTS} slots and names their default colours (the
     * renderer keeps each slot vertex's shade relative to that default). Trailing defaults are dropped, so an object in
     * its model's own colours has none.
     */
    public record Colours(List<String> slots) implements Serializable {
        public static final int SLOTS = 4;
        public static final Colours NONE = new Colours(List.of());

        public Colours {
            List<String> values = new ArrayList<>(slots);
            if (values.size() > SLOTS) { throw new IllegalArgumentException("A model has at most " + SLOTS + " colour slots"); }
            for (int slot = 0; slot < values.size(); slot++) {
                String value = values.get(slot);
                if (value == null) { continue; }
                value = value.toLowerCase(Locale.ROOT);
                if (!value.matches("#[0-9a-f]{6}")) { throw new IllegalArgumentException("A colour is #rrggbb: " + value); }
                values.set(slot, value);
            }
            while (!values.isEmpty() && values.getLast() == null) { values.removeLast(); }
            slots = Collections.unmodifiableList(values);
        }

        public static Colours of(String... slots) { return new Colours(Arrays.asList(slots)); }

        /** The slot's replacement colour, or null for the model's own. */
        public String slot(int index) { return index < slots.size() ? slots.get(index) : null; }

        /** These colours with one slot replaced; null returns it to the model's own colour. */
        public Colours with(int index, String colour) {
            if (index < 0 || index >= SLOTS) { throw new IllegalArgumentException("No colour slot " + index); }
            List<String> values = new ArrayList<>(slots);
            while (values.size() <= index) { values.add(null); }
            values.set(index, colour);
            return new Colours(values);
        }
    }

    public record Receiver(String terrain, String surface) implements Serializable {
        public Receiver {
            if (!Set.of("ground/top", "bridge/deck", "building/roof", "industrial/top", "fuelTank/top", "ice/top")
                  .contains(terrain + "/" + surface)) {
                throw new IllegalArgumentException("Unsupported receiving surface: " + terrain + "/" + surface);
            }
        }
    }

    public record Placement(String mode, Double level, Receiver receiver, Double offset) implements Serializable {
        public Placement {
            if ("absolute".equals(mode)) {
                if (level == null || !Double.isFinite(level) || receiver != null || offset != null) {
                    throw new IllegalArgumentException("Absolute placement requires only a finite level");
                }
            } else if ("surface".equals(mode)) {
                if (level != null || receiver == null || offset == null || !Double.isFinite(offset)) {
                    throw new IllegalArgumentException("Surface placement requires a receiver and finite offset");
                }
            } else {
                throw new IllegalArgumentException("Unknown placement mode: " + mode);
            }
        }

        /**
         * The one support rule, in levels: an absolute placement stands at its level; a surface placement at the
         * {@code ground}, or at the top of its receiver ({@code top} of the receiver's terrain name), plus its offset.
         * The caller measures those tops: nominal levels from the hex's rules terrain, or the drawn support settled on
         * beneath the anchor. A {@code top} of NaN (no such support) gives NaN.
         */
        public double level(double ground, ToDoubleFunction<String> top) {
            if (mode.equals("absolute")) { return level; }
            return (receiver.terrain().equals("ground") ? ground : top.applyAsDouble(receiver.terrain())) + offset;
        }

        /**
         * The nominal level this placement puts an object's anchor at on {@code hex}, by {@link #level(double,
         * ToDoubleFunction)}: each support's top from the hex's rules terrain, or NaN where the hex (null: none) lacks
         * that support.
         */
        public double level(Hex hex) {
            return level(hex == null ? Double.NaN : hex.getLevel(), receiver -> {
                int type = switch (receiver) {
                    case "bridge" -> Terrains.BRIDGE_ELEV;
                    case "building" -> Terrains.BLDG_ELEV;
                    case "industrial" -> Terrains.INDUSTRIAL;
                    case "fuelTank" -> Terrains.FUEL_TANK_ELEV;
                    case "ice" -> Terrains.ICE;
                    default -> throw new IllegalArgumentException("Unknown receiver " + receiver);
                };
                if (hex == null || !hex.containsTerrain(type)) { return Double.NaN; }
                // Ice lies at the hex's own level, on its water or ground.
                return hex.getLevel() + (type == Terrains.ICE ? 0 : hex.terrainLevel(type));
            });
        }

        public static Placement ground() { return surface("ground", "top", 0); }
        public static Placement absolute(double level) { return new Placement("absolute", level, null, null); }
        public static Placement surface(String terrain, String surface, double offset) {
            return new Placement("surface", null, new Receiver(terrain, surface), offset);
        }
        /** On a support's one receiving surface: a bridge's deck, a building's roof, any other support's top. */
        public static Placement on(String terrain, double offset) {
            return surface(terrain, terrain.equals("bridge") ? "deck" : terrain.equals("building") ? "roof" : "top", offset);
        }
    }

    public BoardDecoration {
        if (uid != null && uid.isBlank()) { throw new IllegalArgumentException("An asset UID cannot be blank"); }
        if (id == null || id.isBlank() || !Set.of("prop", "decal").contains(kind)) {
            throw new IllegalArgumentException("An object requires an ID and supported kind");
        }
        if (asset == null || asset.isBlank() || asset.contains("..") || asset.startsWith("/") || asset.contains("\\") || asset.contains(":")) {
            throw new IllegalArgumentException("An object requires a logical asset key");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(rotation)
              || !Double.isFinite(rotationX) || !Double.isFinite(rotationY) || !Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("Object transforms must be finite; scale must be positive");
        }
        Objects.requireNonNull(placement, "placement");
        if (kind.equals("decal") && (!placement.mode().equals("surface") || placement.offset() != 0)) {
            throw new IllegalArgumentException("A decal requires a receiver and zero offset");
        }
        if (kind.equals("prop") && drawOrder != 0) {
            throw new IllegalArgumentException("Only decals have paint order");
        }
        if (group != null && group.isBlank()) {
            throw new IllegalArgumentException("A group name cannot be blank");
        }
        RailRoute.Kind route = kind.equals("prop") ? RailRoute.Kind.of(asset) : null;
        if (connections < 0 || connections > 63 || connections != 0 && route == null) {
            throw new IllegalArgumentException("Only a route marker has connections, a mask of its six sides");
        }
        if (tracks != 0 && (route == null || !RailRoute.valid(connections, tracks))) {
            throw new IllegalArgumentException("A route marker's tracks are gentle pieces between its sides, reaching each");
        }
        // A rail lies on its support, the ground or a bridge deck, and follows it; only maglev holds a level of its own.
        if (route == RailRoute.Kind.RAIL && !(placement.mode().equals("surface") && placement.offset() == 0
              && (placement.receiver().terrain().equals("ground") || placement.receiver().terrain().equals("bridge")))) {
            throw new IllegalArgumentException("A rail lies on the ground or a bridge deck");
        }
        if (pillars && route != RailRoute.Kind.MAGLEV) {
            throw new IllegalArgumentException("Only a maglev route stands on pillars");
        }
        // Java serialization of an object saved before stretch or colours existed supplies neither.
        if (stretch == null) { stretch = Stretch.NONE; }
        if (colours == null) { colours = Colours.NONE; }
        // A decal paints in one colour, slot 0 (lettering): it multiplies the decal's image.
        if (kind.equals("decal") && colours.slots().size() > 1) {
            throw new IllegalArgumentException("A decal has one paint colour; only a model has colour slots");
        }
    }

    /** How far apart (hex widths and heights) twins may stand ({@link #twin}): a 0.01 tolerance would join real pairs. */
    public static final double TWIN_DISTANCE = 1e-3;

    /**
     * Whether {@code other} is an exact twin of this object, drawn the same in the same place, so one of them is a hidden
     * copy: the same kind, art, name, colours, stretch, mirror, winter form, route sides and tracks, pillars, paint order,
     * clipping and placement, its turn and tilts within 0.001 degrees, its scale within 1e-6 and its position within
     * {@link #TWIN_DISTANCE}; id and group do not count. Both positions must be in the same frame (one hex's, or the
     * board's).
     */
    public boolean twin(BoardDecoration other) {
        return kind.equals(other.kind) && asset.equals(other.asset) && Objects.equals(name, other.name)
              && colours.equals(other.colours) && stretch.equals(other.stretch) && mirror == other.mirror && bare == other.bare
              && connections == other.connections && tracks == other.tracks && pillars == other.pillars
              && levels == other.levels && drawOrder == other.drawOrder && clipToHex == other.clipToHex && placement.equals(other.placement)
              && sameAngle(rotation, other.rotation) && sameAngle(rotationX, other.rotationX)
              && sameAngle(rotationY, other.rotationY) && Math.abs(scale - other.scale) < 1e-6
              && Math.abs(x - other.x) < TWIN_DISTANCE && Math.abs(y - other.y) < TWIN_DISTANCE;
    }

    private static boolean sameAngle(double a, double b) { return Math.abs(Math.IEEEremainder(a - b, 360)) < 1e-3; }

    /** A copy with a fresh ID that belongs to no group. */
    public BoardDecoration duplicate() {
        return new BoardDecoration(UUID.randomUUID().toString(), kind, asset, name, x, y, rotation, mirror,
              scale, placement, drawOrder, clipToHex, rotationX, rotationY, null, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }

    public BoardDecoration withGroup(String group) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }

    /** These sides; the stored track layout stays only while it is still a layout of them, else the default is laid. */
    public BoardDecoration withConnections(int connections) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours,
              RailRoute.valid(connections, tracks) ? tracks : 0, pillars, uid, levels);
    }

    /** This route marker with these track pieces ({@link RailRoute}); its default layout is stored as none. */
    public BoardDecoration withTracks(int tracks) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours,
              tracks == RailRoute.pieces(connections) ? 0 : tracks, pillars, uid, levels);
    }

    /** This maglev route marker with its Pillars toggle on or off. */
    public BoardDecoration withPillars(boolean pillars) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }

    public BoardDecoration withStretch(Stretch stretch) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }

    public BoardDecoration withAsset(String asset) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars,
              asset.equals(this.asset) ? uid : null, levels);
    }

    public BoardDecoration withColours(Colours colours) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder,
              clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }

    /** Fresh IDs; members that shared a group share one fresh group, ungrouped objects stay ungrouped. */
    public static List<BoardDecoration> copies(List<BoardDecoration> source) {
        Map<String, String> groups = new HashMap<>();
        return source.stream().map(object -> object.duplicate().withGroup(object.group() == null ? null
              : groups.computeIfAbsent(object.group(), ignored -> UUID.randomUUID().toString()))).toList();
    }

    public BoardDecoration transform(double x, double y, double rotation, boolean mirror, double scale,
          Placement placement) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex, rotationX, rotationY, group, bare, connections, stretch, colours, tracks, pillars, uid, levels);
    }

    /** Right-handed XYZ Euler angles in degrees: apply X, then Y, then Z (the board's up axis). */
    public double[] rotateVector(double px, double py, double pz) {
        double a = Math.toRadians(rotationX), b = Math.toRadians(rotationY), c = Math.toRadians(rotation);
        double yy = py * Math.cos(a) - pz * Math.sin(a), zz = py * Math.sin(a) + pz * Math.cos(a);
        double xx = px * Math.cos(b) + zz * Math.sin(b);
        return new double[] {xx * Math.cos(c) - yy * Math.sin(c), xx * Math.sin(c) + yy * Math.cos(c),
              -px * Math.sin(b) + zz * Math.cos(b)};
    }

    /**
     * Reflection in board space, not in anisotropic normalized XY; route sides reflect as terrain exits do, and the track
     * pieces between them with them. The stretch
     * stays on the same local axes: a reflection of the board is the mirror flag in object space, which commutes with it.
     */
    public BoardDecoration flip(boolean horizontal, boolean vertical) {
        double angle = rotation;
        boolean reflected = mirror;
        if (horizontal) { angle = -angle; reflected = !reflected; }
        if (vertical) { angle = 180 - angle; reflected = !reflected; }
        return new BoardDecoration(id, kind, asset, name, horizontal ? -x : x, vertical ? -y : y,
              angle, reflected, scale, placement, drawOrder, clipToHex, rotationX,
              horizontal != vertical ? -rotationY : rotationY, group, bare, flipSides(connections, horizontal, vertical),
              stretch, colours, RailRoute.flip(tracks, sides -> flipSides(sides, horizontal, vertical)), pillars, uid, levels);
    }

    static int flipSides(int sides, boolean horizontal, boolean vertical) {
        int result = 0;
        for (int side = 0; side < 6; side++) {
            if ((sides & 1 << side) == 0) { continue; }
            int flipped = horizontal ? (6 - side) % 6 : side;
            if (vertical) { flipped = (9 - flipped) % 6; }
            result |= 1 << flipped;
        }
        return result;
    }
}
