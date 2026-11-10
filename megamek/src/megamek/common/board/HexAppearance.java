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
 * {@code lanes} narrows a ground paved road ({@code "road"}) to a single, one-way lane ({@link #SINGLE_LANE}); absent is
 * the default two-lane carriageway. {@code inbound} is that single lane's direction of travel: the sides through which
 * traffic enters the hex (a mask of the six sides), the others being where it leaves; absent, a default is derived
 * ({@link SingleLane#inbound}). {@code exitFrom}, on a two-lane road that single-lane exits (off-ramps) leave, lists for
 * each exit the sides whose traffic may take it, as bit {@code 6 * exit + side}; an exit with none listed, or none
 * stored, may be taken from every side ({@link SingleLane#feeds}). All three are cosmetic, like the junction controls:
 * rules and the 2D views ignore them.
 */
public record HexAppearance(String variant, String asset, String material, Double strength,
      BoardDecoration.Colours colours, Integer rotation, Integer tracks, Integer beside,
      TrafficControl trafficControl, String uid, Double padding, Integer separated, Integer lanes, Integer inbound,
      Long exitFrom) implements Serializable {
    /** The one stored lane count: a single, one-way lane. Two lanes, the default, are stored as none. */
    public static final int SINGLE_LANE = 1;

    /** An appearance with no separated sides, of a two-lane road or none. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation, Integer tracks, Integer beside, TrafficControl trafficControl, String uid, Double padding) {
        this(variant, asset, material, strength, colours, rotation, tracks, beside, trafficControl, uid, padding, null);
    }
    /** An appearance with {@code separated} sides, of a two-lane road or none. */
    public HexAppearance(String variant, String asset, String material, Double strength, BoardDecoration.Colours colours,
          Integer rotation, Integer tracks, Integer beside, TrafficControl trafficControl, String uid, Double padding,
          Integer separated) {
        this(variant, asset, material, strength, colours, rotation, tracks, beside, trafficControl, uid, padding, separated,
              null, null, null);
    }
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
        return new HexAppearance(variant, asset, material, strength, colours, rotation, tracks, beside, trafficControl, uid, padding,
              separated, lanes, inbound, exitFrom);
    }
    public static final Set<String> OWNERS = Set.of("ground", "road", "building", "pavement", "fuelTank",
          "vegetation", "rough", "bridge", "bridgeRoad", "rail", "bridgeRail", "scorch", "footprint", "kitVariant", "buildingProfile",
          "water", "fortification", "doors", "roof");
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
        return trafficControl(appearance, owner, exits, 0);
    }

    /**
     * As {@link #trafficControl(Map, String, int)}, where single-lane ramps join the road across {@code ramps}: they are no
     * roads of their own, so stop signs serve the junction until four two-lane roads meet there.
     */
    public static TrafficControl trafficControl(Map<String, HexAppearance> appearance, String owner, int exits, int ramps) {
        HexAppearance stored = appearance.get(owner);
        if (stored != null && stored.trafficControl() != null) { return stored.trafficControl(); }
        if ((exits & ramps & 63) != 0) {
            return Integer.bitCount(exits & ~ramps & 63) >= 4 ? TrafficControl.GANTRY : TrafficControl.STOPS;
        }
        return Integer.bitCount(exits & 63) == 3 ? TrafficControl.STOPS : TrafficControl.GANTRY;
    }

    /** Changes only junction controls. Null retains the topology-dependent default; explicit choices are stored. */
    public static HexAppearance withTrafficControl(HexAppearance current, TrafficControl control) {
        if (current == null) {
            return control == null ? null : new HexAppearance(null, null, null, null,
                  BoardDecoration.Colours.NONE, null, null, null, control);
        }
        if (control == null && current.variant() == null && current.asset() == null && current.material() == null
              && current.rotation() == null && current.padding() == null && current.colours().slots().isEmpty()
              && current.lanes() == null && current.exitFrom() == null && current.separated() == null) {
            return null;
        }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              current.colours(), current.rotation(), current.tracks(), current.beside(), control, current.uid(), current.padding(),
              current.separated(), current.lanes(), current.inbound(), current.exitFrom());
    }

    /**
     * Whether a hex's appearance for {@code owner} narrows its road to a single, one-way lane ({@link #SINGLE_LANE}). Only
     * a paved road ({@link SingleLane#paved}) is drawn so; the 3D board reads it with the road's level.
     */
    public static boolean singleLane(Map<String, HexAppearance> appearance, String owner) {
        HexAppearance stored = appearance.get(owner);
        return stored != null && stored.lanes() != null && stored.lanes() == SINGLE_LANE;
    }

    /** The single lane's stored direction for {@code owner}: the sides traffic enters by; null when none is stored. */
    public static Integer inbound(Map<String, HexAppearance> appearance, String owner) {
        HexAppearance stored = appearance.get(owner);
        return stored == null || stored.lanes() == null ? null : stored.inbound();
    }

    /**
     * For each exit (off-ramp) leaving a two-lane road, the sides whose traffic may take it, as stored for {@code owner}:
     * bit {@code 6 * exit + side}; null when none are stored, so every side may take every exit.
     */
    public static Long exitFrom(Map<String, HexAppearance> appearance, String owner) {
        HexAppearance stored = appearance.get(owner);
        return stored == null || stored.lanes() != null ? null : stored.exitFrom();
    }

    /**
     * Changes only the road's lanes: {@code single} narrows it to one one-way lane entered through {@code inbound} (a side
     * mask, or null for the derived default), dropping any exit approaches, which only a two-lane road keeps; otherwise
     * the two-lane default, which stores no lanes or direction and keeps its exit approaches.
     */
    public static HexAppearance withLanes(HexAppearance current, boolean single, Integer inbound) {
        return withLanes(current, single, inbound, single || current == null ? null : current.exitFrom());
    }

    /** As {@link #withLanes(HexAppearance, boolean, Integer)}, with a two-lane road's exit approaches ({@link #exitFrom}). */
    public static HexAppearance withLanes(HexAppearance current, boolean single, Integer inbound, Long exitFrom) {
        Integer lanes = single ? SINGLE_LANE : null;
        Integer flow = single ? inbound : null;
        Long from = single || exitFrom == null || exitFrom == 0 ? null : exitFrom;
        if (current == null) {
            return lanes == null && from == null ? null : new HexAppearance(null, null, null, null, BoardDecoration.Colours.NONE,
                  null, null, null, null, null, null, null, lanes, flow, from);
        }
        if (lanes == null && from == null && current.variant() == null && current.asset() == null && current.material() == null
              && current.rotation() == null && current.padding() == null && current.colours().slots().isEmpty()
              && current.trafficControl() == null && current.separated() == null) {
            return null;
        }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              current.colours(), current.rotation(), current.tracks(), current.beside(), current.trafficControl(), current.uid(),
              current.padding(), current.separated(), lanes, flow, from);
    }

    /** A two-lane road's appearance with its exit approaches ({@link #exitFrom}) changed to {@code exitFrom}; 0 or null for none. */
    public static HexAppearance withExitFrom(HexAppearance current, Long exitFrom) {
        return withLanes(current, false, null, exitFrom);
    }

    /**
     * {@code look} with the lanes, direction and exit approaches of {@code from} (either may be null), as a road's other
     * edits keep them.
     */
    public static HexAppearance keepLanes(HexAppearance look, HexAppearance from) {
        Integer lanes = from == null ? null : from.lanes(), inbound = from == null ? null : from.inbound();
        Long exits = from == null ? null : from.exitFrom();
        if (look == null ? lanes == null && exits == null : java.util.Objects.equals(look.lanes(), lanes)
              && java.util.Objects.equals(look.inbound(), inbound) && java.util.Objects.equals(look.exitFrom(), exits)) {
            return look;
        }
        return withLanes(look, lanes != null, inbound, exits);
    }

    /** Changes only the design's rotation; zero removes it, including an otherwise empty appearance. */
    public static HexAppearance withRotation(HexAppearance current, int rotation) {
        if (current == null) {
            return rotation == 0 ? null : new HexAppearance(null, null, null, null, BoardDecoration.Colours.NONE, rotation);
        }
        if (rotation == 0 && current.variant() == null && current.asset() == null && current.material() == null
              && current.trafficControl() == null && current.padding() == null && current.colours().slots().isEmpty()
              && current.lanes() == null && current.exitFrom() == null && current.separated() == null) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              current.colours(), rotation, current.tracks(), current.beside(), current.trafficControl(), current.uid(), current.padding(),
              current.separated(), current.lanes(), current.inbound(), current.exitFrom());
    }

    /** Changes only colour overrides, keeping the design, rotation and any unavailable asset's identity. */
    public static HexAppearance withColours(HexAppearance current, BoardDecoration.Colours colours) {
        if (current == null) {
            return colours.slots().isEmpty() ? null : new HexAppearance(null, null, null, null, colours);
        }
        if (colours.slots().isEmpty() && current.variant() == null && current.asset() == null && current.material() == null
              && current.rotation() == null && current.trafficControl() == null && current.padding() == null
              && current.lanes() == null && current.exitFrom() == null && current.separated() == null) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(),
              colours, current.rotation(), current.tracks(), current.beside(), current.trafficControl(), current.uid(), current.padding(),
              current.separated(), current.lanes(), current.inbound(), current.exitFrom());
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
              && current.rotation() == null && current.trafficControl() == null && current.colours().slots().isEmpty()
              && current.lanes() == null && current.exitFrom() == null && current.separated() == null) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(), current.colours(),
              current.rotation(), current.tracks(), current.beside(), current.trafficControl(), current.uid(), value,
              current.separated(), current.lanes(), current.inbound(), current.exitFrom());
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
     * A fortified hex's strongpoint (owner {@code "fortification"}): {@link #DUG} into its ground, stored as none, or a
     * {@link #SANDBAGS} emplacement. Pavement and roads are never dug: there it is always sandbagged
     * ({@link HexAppearance#sandbagsOnly}). Cosmetic, like the {@link Footprint}: the 3D views draw it; rules and the 2D
     * views ignore it.
     */
    public enum Fortification { DUG, SANDBAGS }

    public static final HexAppearance SANDBAGGED = new HexAppearance("fortification/sandbags", null, null, null);

    /** Whether a hex whose terrain {@code has} (a terrain type) is never dug: its pavement or any road. */
    public static boolean sandbagsOnly(java.util.function.IntPredicate has) {
        return has.test(megamek.common.units.Terrains.PAVEMENT) || has.test(megamek.common.units.Terrains.ROAD);
    }

    /** How a fortified hex with {@code appearance}, whose terrain {@code has}, is drawn: sandbags where it is never dug. */
    public static Fortification fortification(Map<String, HexAppearance> appearance, java.util.function.IntPredicate has) {
        HexAppearance stored = appearance.get("fortification");
        return sandbagsOnly(has) || stored != null && SANDBAGGED.variant().equals(stored.variant())
              ? Fortification.SANDBAGS : Fortification.DUG;
    }

    /** The appearance that stores {@code fortification}; null for {@link Fortification#DUG}, which is stored as none. */
    public static HexAppearance of(Fortification fortification) {
        return fortification == Fortification.SANDBAGS ? SANDBAGGED : null;
    }

    /**
     * The sides of a hex's {@code owner}, {@code "water"} or {@code "pavement"}, that the author parted from the same
     * terrain beside them, as a mask of directions clockwise from north; none stored means every side joins. Cosmetic,
     * like the pavement {@link Edges}: the 3D views draw a strip of ground along them; rules and the 2D views ignore it.
     * A legacy file's exits on these terrains are not this: they choose 2D tiles.
     */
    public static int separated(Map<String, HexAppearance> appearance, String owner) {
        HexAppearance stored = appearance.get(owner);
        return stored == null || stored.separated() == null ? 0 : stored.separated();
    }

    /** {@code current} with {@code sides} separated (see {@link #separated}); null when nothing is left to store. */
    public static HexAppearance withSeparated(HexAppearance current, int sides) {
        Integer value = sides == 0 ? null : sides;
        if (current == null) {
            return value == null ? null : new HexAppearance(null, null, null, null, null, null, null, null, null, null, null, value);
        }
        if (value == null && current.variant() == null) { return null; }
        return new HexAppearance(current.variant(), current.asset(), current.material(), current.strength(), current.colours(),
              current.rotation(), current.tracks(), current.beside(), current.trafficControl(), current.uid(), current.padding(), value,
              current.lanes(), current.inbound(), current.exitFrom());
    }

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

    /**
     * A hangar's roof (owner {@code "roof"}), where its generated kit's variant offers one that spans its hall
     * ({@link TilesetScenery#kitVaults}): vaulted (as none) or flat ({@link TilesetScenery#vaulted}). A building of
     * another class keeps its variant's own roof whatever it stores. Cosmetic. A connected building has one.
     */
    public enum Roof { VAULTED, FLAT }

    public static Roof roof(Map<String, HexAppearance> appearance) {
        HexAppearance stored = appearance.get("roof");
        String variant = stored == null ? null : stored.variant();
        return "roof/vaulted".equals(variant) ? Roof.VAULTED : "roof/flat".equals(variant) ? Roof.FLAT : null;
    }

    /** The appearance that stores {@code roof}; null for none. */
    public static HexAppearance of(Roof roof) {
        return roof == null ? null : new HexAppearance(roof == Roof.VAULTED ? "roof/vaulted" : "roof/flat", null, null, null);
    }

    private static final String DOORS = "doors/";

    /**
     * A building hex's large doors (owner {@code "doors"}), as a hangar has them: each door's height in levels from the
     * ground, by the hexside it stands on, clockwise from north; 0 for none. A door stands in the kit's wall towards
     * its hexside. Doors that meet at a corner, in this hex or the next, are one opening when they are linked
     * ({@link #doorLinks}, {@link BoardDoors}). Cosmetic for now, like the {@link Footprint}: the 3D views draw them;
     * rules and the 2D views ignore them.
     */
    public static int[] doors(Map<String, HexAppearance> appearance) {
        return doorValues(appearance, 0);
    }

    /**
     * Each door's link group by its hexside, 0 for a door of its own: doors of one building with the same group are
     * one opening, as {@code BuildingDesign.Door#linkGroup} of the building rules has it.
     */
    public static int[] doorLinks(Map<String, HexAppearance> appearance) {
        return doorValues(appearance, 1);
    }

    /** The variant is {@code doors/} and the six heights, then, with any linked, {@code /} and the six groups. */
    private static int[] doorValues(Map<String, HexAppearance> appearance, int part) {
        HexAppearance stored = appearance.get("doors");
        int[] result = new int[6];
        if (stored == null || stored.variant() == null || !stored.variant().startsWith(DOORS)) { return result; }
        String[] parts = stored.variant().substring(DOORS.length()).split("/");
        if (part >= parts.length) { return result; }
        String[] values = parts[part].split("-");
        for (int side = 0; side < 6 && side < values.length; side++) {
            try {
                result[side] = Math.max(0, Integer.parseInt(values[side]));
            } catch (NumberFormatException ignored) {
                // A value that is no number is no door, or no link.
            }
        }
        return result;
    }

    /**
     * The appearance that stores the door {@code heights} of the six hexsides and their link {@code groups}
     * ({@link #doors}, {@link #doorLinks}); null for no door. A side without a door has no group.
     */
    public static HexAppearance ofDoors(int[] heights, int[] groups) {
        StringBuilder variant = new StringBuilder(DOORS), links = new StringBuilder("/");
        boolean any = false, linked = false;
        for (int side = 0; side < 6; side++) {
            int height = Math.max(0, heights[side]), group = height > 0 ? Math.max(0, groups[side]) : 0;
            any |= height > 0;
            linked |= group > 0;
            variant.append(side == 0 ? "" : "-").append(height);
            links.append(side == 0 ? "" : "-").append(group);
        }
        return any ? new HexAppearance(linked ? variant.append(links).toString() : variant.toString(), null, null, null) : null;
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
        separated = separated != null && separated == 0 ? null : separated;
        if (separated != null && (separated < 0 || separated > 63)) {
            throw new IllegalArgumentException("Separated sides are a mask of the six directions");
        }
        if (variant == null && asset == null && material == null && trafficControl == null && rotation == null && padding == null
              && separated == null && colours.slots().isEmpty() && lanes == null && exitFrom == null) {
            throw new IllegalArgumentException(
                  "An appearance needs a variant, asset, material, rotation, traffic controls, lanes or exit approaches");
        }
        if (lanes != null && lanes != SINGLE_LANE) {
            throw new IllegalArgumentException("A road stores a single lane only; two lanes, the default, are stored as none");
        }
        if (exitFrom != null && (lanes != null || exitFrom <= 0 || exitFrom >= 1L << 36)) {
            throw new IllegalArgumentException("A two-lane road's exit approaches are a non-empty mask of side pairs");
        }
        if (inbound != null && (lanes == null || inbound < 0 || inbound > 63)) {
            throw new IllegalArgumentException("A single lane's direction is a mask of the sides traffic enters by");
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
              || (lanes != null || exitFrom != null) && !owner.equals("road")
              || (tracks != null || beside != null) && !owner.equals("rail") && !owner.equals("bridgeRail")
              || separated != null && !owner.equals("water") && !owner.equals("pavement")) {
            throw new IllegalArgumentException("Appearance fields do not belong to " + owner);
        }
    }
}
