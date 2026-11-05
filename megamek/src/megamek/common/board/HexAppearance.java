/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * Immutable authored appearance for one terrain owner. Asset data stays in the shared content catalog. {@code colours}
 * replace a truss bridge's colour slot (its Body, as a placed object's colours do), or tint a building's surfaces.
 * {@code rotation} turns an isolated building or a vegetation design clockwise (the latter in 30-degree steps,
 * {@link BoardEditorBlueprint.Skin}); 0 is no rotation and is stored as none. Connected buildings ignore rotation.
 * {@code tracks} is a rail's junction layout
 * where it differs from the default of its sides ({@link RailRoute#pieces(int)}): owners {@code "rail"} (the ground
 * rail) and {@code "bridgeRail"} (the deck rail) only, made by {@link #tracks(int)}. {@code beside} chooses the beside-road
 * corridor and its physical side at each border ({@link #beside(Map, String)}); the middle corridor stores none.
 * {@code trafficControl} chooses cosmetic junction controls for {@code "road"} or {@code "bridgeRoad"}; an absent
 * choice uses stop signs at three-arm junctions and gantries at larger junctions.
 */
public record HexAppearance(String variant, String asset, String material, Double strength,
      BoardDecoration.Colours colours, Integer rotation, Integer tracks, Integer beside,
      TrafficControl trafficControl, String uid, Double padding) implements Serializable {
    /** An appearance with no extra inset from the hex border. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation, Integer tracks, Integer beside, TrafficControl trafficControl, String uid) {
        this(variant, asset, material, strength, colours, rotation, tracks, beside, trafficControl, uid, null);
    }
    /** An appearance whose asset identity is supplied by the installed catalog. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation, Integer tracks, Integer beside, TrafficControl trafficControl) {
        this(variant, asset, material, strength, colours, rotation, tracks, beside, trafficControl, null);
    }

    /** Retains an asset pack's identity even while the pack is absent from the installed catalog. */
    public HexAppearance withUid(String uid) {
        return new HexAppearance(variant, asset, material, strength, colours, rotation, tracks, beside, trafficControl, uid, padding);
    }
    public static final Set<String> OWNERS = Set.of("ground", "road", "building", "pavement", "fuelTank",
          "vegetation", "rough", "bridge", "bridgeRoad", "rail", "bridgeRail", "scorch", "footprint", "kitVariant", "buildingProfile");
    /** Per-hex upper storeys step back above shorter neighbours; absent means keep the ground footprint. */
    public static final HexAppearance STEPPED_BUILDING = new HexAppearance("building/stepped", null, null, null);

    public static boolean steppedBuilding(Map<String, HexAppearance> appearance) {
        return STEPPED_BUILDING.equals(appearance.get("buildingProfile"));
    }

    /**
     * A built bridge with concrete piers under the joints between its hexes, never at a hex centre (owner
     * {@code "bridge"}). Cosmetic, like the other bridge types: the 3D views draw them; rules and the 2D views ignore it.
     */
    public static final HexAppearance PILLARS = new HexAppearance("bridge/pillars", null, null, null);
    /** A built (artificial) bridge without piers: the bridge deck models. Every hex of a {@link BridgeSpan} has one type. */
    public static final HexAppearance BUILT_BRIDGE = new HexAppearance("bridge/built", null, null, null);
    /** A natural bridge: a rock arch. */
    public static final HexAppearance NATURAL_BRIDGE = new HexAppearance("bridge/natural", null, null, null);
    /**
     * A built bridge in the riveted steel truss design, without piers; {@link #TRUSS_PILLARS} with them. The design and
     * its colours are one choice per span ({@link BridgeSpan#truss}), cosmetic like the type.
     */
    public static final HexAppearance TRUSS = new HexAppearance("bridge/truss", null, null, null);
    public static final HexAppearance TRUSS_PILLARS = new HexAppearance("bridge/truss-pillars", null, null, null);
    /**
     * An implicit bridge: the deck a road or rail makes for itself where nothing else carries it, over water or in the
     * air, shaped exactly like its ways and gone with its last way ({@link BridgeSpan#carry}). It counts as built;
     * {@link #IMPLICIT_PILLARS} has piers. A stored choice, never inferred.
     */
    public static final HexAppearance IMPLICIT_BRIDGE = new HexAppearance("bridge/implicit", null, null, null);
    public static final HexAppearance IMPLICIT_PILLARS = new HexAppearance("bridge/implicit-pillars", null, null, null);

    /**
     * How a pavement hex's walls and banks follow the fitted concrete outline (owner {@code "pavement"}). Cosmetic, like
     * the bridge types: the 3D views draw them; rules and the 2D views ignore it. {@link #AUTO}, stored as none: fitted
     * straight or rounded, except where a placed object stands on a wall the fit moves; {@link #FITTED}: fitted even
     * through objects; {@link #HEX}: plain hex steps.
     */
    public enum Edges { AUTO, FITTED, HEX }

    /** Cosmetic junction controls: stop signs, roadside lights, or overhead lights on a metal gantry. */
    public enum TrafficControl { STOPS, SIGNALS, GANTRY }

    /** The brush's junction controls before a road topology is known; absent choices use overhead traffic lights. */
    public static TrafficControl trafficControl(Map<String, HexAppearance> appearance, String owner) {
        return trafficControl(appearance, owner, 0);
    }

    /** Priority three-arm junctions use stop signs; larger junctions use signals unless explicitly overridden. */
    public static TrafficControl trafficControl(Map<String, HexAppearance> appearance, String owner, int exits) {
        HexAppearance stored = appearance.get(owner);
        if (stored != null && stored.trafficControl() != null) { return stored.trafficControl(); }
        return Integer.bitCount(exits & 63) == 3 ? TrafficControl.STOPS : TrafficControl.GANTRY;
    }

    /** Changes only junction controls. Null retains the topology-dependent default; explicit choices are stored. */
    public static HexAppearance withTrafficControl(HexAppearance current, TrafficControl control) {
        if (current == null) {
            return control == null ? null : new HexAppearance(null, null, null, null,
                  BoardDecoration.Colours.NONE, null, null, null, control);
        }
        if (control == null && current.variant() == null && current.asset() == null && current.material() == null
              && current.rotation() == null && current.padding() == null && current.colours().slots().isEmpty()) {
            return null;
        }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              current.colours(), current.rotation(), current.tracks(), current.beside(), control, current.uid(), current.padding());
    }

    /** Changes only the design's rotation; zero removes it, including an otherwise empty appearance. */
    public static HexAppearance withRotation(HexAppearance current, int rotation) {
        if (current == null) {
            return rotation == 0 ? null : new HexAppearance(null, null, null, null, BoardDecoration.Colours.NONE, rotation);
        }
        if (rotation == 0 && current.variant() == null && current.asset() == null && current.material() == null
              && current.trafficControl() == null && current.padding() == null && current.colours().slots().isEmpty()) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              current.colours(), rotation, current.tracks(), current.beside(), current.trafficControl(), current.uid(), current.padding());
    }

    /** Changes only colour overrides, keeping the design, rotation and any unavailable asset's identity. */
    public static HexAppearance withColours(HexAppearance current, BoardDecoration.Colours colours) {
        if (current == null) {
            return colours.slots().isEmpty() ? null : new HexAppearance(null, null, null, null, colours);
        }
        if (colours.slots().isEmpty() && current.variant() == null && current.asset() == null && current.material() == null
              && current.rotation() == null && current.trafficControl() == null && current.padding() == null) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              colours, current.rotation(), current.tracks(), current.beside(), current.trafficControl(), current.uid(), current.padding());
    }

    /** Extra horizontal inset, as a percentage of the isolated footprint; connected hexes keep their joining reach. */
    public static double padding(Map<String, HexAppearance> appearance) {
        HexAppearance building = appearance.get("building");
        return building == null || building.padding() == null ? 0 : building.padding();
    }

    /** Changes only the isolated building's padding; zero is omitted. */
    public static HexAppearance withPadding(HexAppearance current, double padding) {
        Double value = padding <= 0 ? null : Math.min(80, padding);
        if (current == null) {
            return value == null ? null : new HexAppearance(null, null, null, null, null, null, null, null, null, null, value);
        }
        if (value == null && current.variant() == null && current.asset() == null && current.material() == null
              && current.rotation() == null && current.trafficControl() == null && current.colours().slots().isEmpty()) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(), current.colours(),
              current.rotation(), current.tracks(), current.beside(), current.trafficControl(), current.uid(), value);
    }

    /**
     * A connected building kit's footprint (owner {@code "footprint"}, models/buildings/README.md): {@link #ANGLED}, the
     * classic exits art's, its walls along the hex's directions, stored as none; {@link #SQUARE} right-angled blocks
     * ({@link TilesetScenery#footprint}). Cosmetic, like the pavement {@link Edges}:
     * the 3D views draw it; rules and the 2D views ignore it. A connected building has one.
     */
    public enum Footprint { ANGLED, SQUARE }

    public static final HexAppearance SQUARE = new HexAppearance("footprint/square", null, null, null);

    /** A hex's stored {@link Footprint}; {@link Footprint#ANGLED} when none is stored. */
    public static Footprint footprint(Map<String, HexAppearance> appearance) {
        HexAppearance stored = appearance.get("footprint");
        return stored != null && SQUARE.variant().equals(stored.variant()) ? Footprint.SQUARE : Footprint.ANGLED;
    }

    /** The appearance that stores {@code footprint}; null for {@link Footprint#ANGLED}, which is stored as none. */
    public static HexAppearance of(Footprint footprint) { return footprint == Footprint.SQUARE ? SQUARE : null; }

    /**
     * A generated building kit's variant (owner {@code "kitVariant"}): the name of one of the variants its JSON spec lists
     * ({@link TilesetScenery#kitVariants}); none, or a name the spec no longer lists, draws its first. Cosmetic, like the
     * {@link Footprint}. A connected building has one.
     */
    public static String kitVariant(Map<String, HexAppearance> appearance) {
        HexAppearance stored = appearance.get("kitVariant");
        return stored == null ? null : stored.variant();
    }

    /** The appearance that stores the kit variant {@code name}; null for none. */
    public static HexAppearance ofKitVariant(String name) {
        return name == null ? null : new HexAppearance(name, null, null, null);
    }

    public static final HexAppearance EDGES_FITTED = new HexAppearance("pavement/fitted", null, null, null);
    public static final HexAppearance EDGES_HEX = new HexAppearance("pavement/hex", null, null, null);

    /** A hex's stored pavement {@link Edges}; {@link Edges#AUTO} when none is stored. */
    public static Edges edges(Map<String, HexAppearance> appearance) {
        HexAppearance stored = appearance.get("pavement");
        String variant = stored == null ? null : stored.variant();
        return EDGES_FITTED.variant().equals(variant) ? Edges.FITTED : EDGES_HEX.variant().equals(variant) ? Edges.HEX : Edges.AUTO;
    }

    /**
     * How badly a hex's ground is scorched (owner {@code "scorch"}), each named level a share of the scorch a nuclear
     * blast leaves at Ground Zero (1). Cosmetic, like the pavement {@link Edges}: the 3D views draw it; rules and the 2D
     * views ignore it.
     */
    public enum Scorch {
        SINGED("scorch/singed", .15f), CHARRED("scorch/charred", .35f), ASH("scorch/ash", .62f), GLASS("scorch/glass", 1f);

        private final String variant;
        private final float severity;

        Scorch(String variant, float severity) { this.variant = variant; this.severity = severity; }

        public String variant() { return variant; }
    }

    /** A hex's stored scorch, 0 to 1 ({@link Scorch}); 0 when none or an unknown level is stored. */
    public static float scorch(Map<String, HexAppearance> appearance) {
        HexAppearance stored = appearance.get("scorch");
        if (stored == null) { return 0; }
        for (Scorch level : Scorch.values()) {
            if (level.variant.equals(stored.variant())) { return level.severity; }
        }
        return 0;
    }

    /** The pavement appearance that stores {@code edges}; null for {@link Edges#AUTO}, which is stored as none. */
    public static HexAppearance of(Edges edges) {
        return switch (edges) {
            case AUTO -> null;
            case FITTED -> EDGES_FITTED;
            case HEX -> EDGES_HEX;
        };
    }

    /** An appearance without colours. */
    public HexAppearance(String variant, String asset, String material, Double strength) {
        this(variant, asset, material, strength, BoardDecoration.Colours.NONE);
    }

    /** An appearance without rotation. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours) {
        this(variant, asset, material, strength, colours, null);
    }

    /** An appearance without a rail layout. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation) {
        this(variant, asset, material, strength, colours, rotation, null);
    }

    /** An appearance without a beside-road rail placement. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation, Integer tracks) {
        this(variant, asset, material, strength, colours, rotation, tracks, null);
    }

    /** An appearance with the default road junction controls. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation, Integer tracks, Integer beside) {
        this(variant, asset, material, strength, colours, rotation, tracks, beside, null);
    }

    /** The rail appearance that stores the junction layout {@code tracks}; null (none) for the default layout, 0. */
    public static HexAppearance tracks(int tracks) {
        return rail(tracks, 0);
    }

    /** Rail junction and corridor appearance. Middle corridors and default track layouts store nothing. */
    public static HexAppearance rail(int tracks, int beside) {
        return tracks == 0 && beside == 0 ? null : new HexAppearance("rail/layout", null, null, null,
              BoardDecoration.Colours.NONE, null, tracks == 0 ? null : tracks, beside == 0 ? null : beside);
    }

    /** 0 for a middle corridor, otherwise 64 plus its six border-side bits ({@link RailRoute.Corridor}). */
    public static int beside(Map<String, HexAppearance> appearance, String owner) {
        HexAppearance stored = appearance.get(owner);
        return stored == null || stored.beside() == null ? 0 : stored.beside();
    }

    /** The junction layout a hex's appearance stores for {@code owner} ({@code "rail"} or {@code "bridgeRail"}), else 0. */
    public static int tracks(Map<String, HexAppearance> appearance, String owner) {
        HexAppearance stored = appearance.get(owner);
        return stored == null || stored.tracks() == null ? 0 : stored.tracks();
    }

    /**
     * Whether a hex's appearance turns its bridge's piers ({@link #PILLARS}, {@link #TRUSS_PILLARS},
     * {@link #IMPLICIT_PILLARS}) on.
     */
    public static boolean pillars(Map<String, HexAppearance> appearance) {
        return is(appearance, PILLARS) || is(appearance, TRUSS_PILLARS) || is(appearance, IMPLICIT_PILLARS);
    }

    /** Whether a hex's appearance stores an implicit bridge ({@link #IMPLICIT_BRIDGE}, {@link #IMPLICIT_PILLARS}). */
    public static boolean implicit(Map<String, HexAppearance> appearance) {
        return is(appearance, IMPLICIT_BRIDGE) || is(appearance, IMPLICIT_PILLARS);
    }

    /** Whether a hex's appearance draws its built bridge as a truss ({@link #TRUSS}, {@link #TRUSS_PILLARS}). */
    public static boolean truss(Map<String, HexAppearance> appearance) {
        return is(appearance, TRUSS) || is(appearance, TRUSS_PILLARS);
    }

    /** A built bridge hex's appearance: its design, its piers, and a truss's colours (ignored for the standard design). */
    public static HexAppearance built(boolean truss, boolean pillars, BoardDecoration.Colours colours) {
        if (!truss) { return pillars ? PILLARS : BUILT_BRIDGE; }
        return new HexAppearance((pillars ? TRUSS_PILLARS : TRUSS).variant(), null, null, null, colours);
    }

    /** Variants compare by id: a truss's colours do not change its type. */
    private static boolean is(Map<String, HexAppearance> appearance, HexAppearance type) {
        HexAppearance stored = appearance.get("bridge");
        return stored != null && type.variant().equals(stored.variant());
    }

    /**
     * The hex's stored bridge type: TRUE for built ({@link #BUILT_BRIDGE}, {@link #PILLARS}, {@link #TRUSS},
     * {@link #TRUSS_PILLARS} or an implicit bridge), FALSE for {@link #NATURAL_BRIDGE}, null when none is stored (the 3D
     * board's legacy decode decides).
     */
    public static Boolean bridgeBuilt(Map<String, HexAppearance> appearance) {
        return pillars(appearance) || truss(appearance) || implicit(appearance) || is(appearance, BUILT_BRIDGE) ? Boolean.TRUE
              : is(appearance, NATURAL_BRIDGE) ? Boolean.FALSE : null;
    }

    public HexAppearance {
        if (uid != null && (uid.isBlank() || asset == null)) { throw new IllegalArgumentException("An asset UID needs an asset path"); }
        colours = colours == null ? BoardDecoration.Colours.NONE : colours;
        rotation = rotation != null && rotation == 0 ? null : rotation;
        padding = padding == null || padding <= 0 ? null : Math.min(80, padding);
        if (variant == null && asset == null && material == null && trafficControl == null && rotation == null && padding == null
              && colours.slots().isEmpty()) {
            throw new IllegalArgumentException("An appearance needs a variant, asset, material, rotation or traffic controls");
        }
        for (String key : new String[] { variant, asset, material }) {
            if (key != null && (key.isBlank() || key.contains("..") || key.startsWith("/") || key.contains("\\") || key.contains(":"))) {
                throw new IllegalArgumentException("Invalid appearance key: " + key);
            }
        }
        if (asset != null && variant != null) {
            throw new IllegalArgumentException("A design cannot contain both an asset and a variant");
        }
        if (strength != null && (!Double.isFinite(strength) || variant == null
              || strength <= 0 || strength >= 1 || Math.abs(strength * 6 - Math.rint(strength * 6)) > 0.000001)) {
            throw new IllegalArgumentException("Blend strength must be one to five sixths");
        }
        if (rotation != null && (rotation < 0 || rotation >= 360)) {
            throw new IllegalArgumentException("A design's rotation is from 0 to 359 degrees");
        }
        if (tracks != null && (tracks <= 0 || tracks >= 1 << RailRoute.PIECES)) {
            throw new IllegalArgumentException("A rail layout is a mask of track pieces; the default is stored as none");
        }
        if (beside != null && (beside < 64 || beside > 127)) {
            throw new IllegalArgumentException("Beside-road rails store 64 plus their border-side bits");
        }
    }

    public void validateOwner(String owner) {
        if (!OWNERS.contains(owner) || asset != null && !owner.equals("building")
              || material != null && !owner.equals("road") && !owner.equals("bridgeRoad") || strength != null && !owner.equals("ground")
              || !colours.slots().isEmpty() && !owner.equals("building")
                    && !(owner.equals("bridge") && ("bridge/truss".equals(variant) || "bridge/truss-pillars".equals(variant)))
              || rotation != null && !owner.equals("building")
                    && (!owner.equals("vegetation") || variant == null || rotation % 30 != 0)
              || trafficControl != null && !owner.equals("road") && !owner.equals("bridgeRoad")
              || (tracks != null || beside != null) && !owner.equals("rail") && !owner.equals("bridgeRail")) {
            throw new IllegalArgumentException("Appearance fields do not belong to " + owner);
        }
    }
}
