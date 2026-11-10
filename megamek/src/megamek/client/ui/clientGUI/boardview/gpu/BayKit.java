/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.EXTEND;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.H;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.R;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.ROOF_INSET;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.normal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.FloatArray;

/**
 * A generated kit laid from bays ({@link GeneratedKit}, style "bays", a variant's {@link Spec}): the light building's,
 * the light fortress's, the civic and Utopia looks'. Each module lays a run of modelled pieces along every wall of a
 * footprint piece ({@link Run}), goes on past an end where the wall goes on (the next piece's cut, the wall's mitre or a
 * rounded corner's bisector trims it), and lays its floor or roof slab and the ground storey's sidewalk on the board's
 * axes, so they run on across corner pieces and hexes. Convex corners may be rounded, a short piece laid along each facet
 * of the arc; roofs may carry fittings from the pieces GLB, laid out about the hex's middle as the habitat's are.
 */
final class BayKit {
    private BayKit() { }

    /**
     * A kit laid from bays, as data: its variant's {@code name} and {@code style} (bays); the GLB of its modelled pieces
     * beside the spec ({@code pieces}); the Angled lone hex's outline from its front ({@code lone}) and a single link's
     * band end; the roof slab and the storeys' floors ({@link Slab}); the ground storey's sidewalk ({@code null}: none);
     * and by role (base, floor and roof modules, in the order the runtime picks from) the module's runs, one per level of
     * detail (the last for any further). Facade thickness belongs to the authored panels, including their recesses.
     * Convex corners round to {@code cornerRadius} in {@code cornerSegments} facets (0: square). With {@code fittings}
     * every roof module comes once per layout of the footprint's fittings, and the kit is not turned (its pieces stand
     * in all six corners, as the fittings stand in the hex's middle). With {@code varyBays} a wall may hold a bay fewer
     * or more than the nearest count, by its place ({@link #scale}). {@code largeDoor}: its large doors' look
     * ({@link KitDoors.Style}; a plain steel door without).
     */
    record Spec(String name, String style, String pieces, List<double[]> lone, double bandEnd, Slab roof, Slab floors,
                Sidewalk sidewalk, Map<String, List<Run>> modules, double cornerRadius, int cornerSegments,
                HabitatKit.Fittings fittings, boolean varyBays, KitDoors.Style largeDoor) { }

    /**
     * A flat surface over the piece: a board material, {@code repeat} units a repeat on the board's axes, at a height per
     * level of detail (the last for any further), kept {@code inset} off the walls; with {@code underlay} (a glass roof),
     * a planted bed just under it, so the glass shows a greenhouse rather than the ground down a seam; with
     * {@code aviationLights} (a roof), aviation obstruction lights on its convex corners ({@link GeneratedKit#lights}).
     */
    record Slab(String material, double repeat, double inset, double[] heights, boolean underlay, boolean aviationLights, Vault vault) {
        double height(int lod) { return heights[Math.min(lod, heights.length - 1)]; }
    }

    /**
     * A roof that spans its hall, as a hangar's does, in place of the flat slab: a hexside wide, it rises {@code rise}
     * over the roof's height in the {@code shape} of a {@code gable}, a shallow {@code bow} (a circle's segment, a
     * bowstring roof), an {@code arch} (half an ellipse, a barrel vault) or {@code plates} (a bow of flat plates, their
     * edges hard), in {@code segments} facets a side per level of detail (the last for any further). It follows the
     * hall ({@link #barrels}): a Square hex's runs north to south
     * along its column, so a column's hexes share one roof and columns side by side meet in valleys; an Angled hex's
     * runs out along each of its links, the roofs of two links meeting in a valley (but level at the crown where the
     * hexes across both are one building with it: a wide hall's roof is one roof), and turns round its unlinked sides in
     * hipped facets, so a hall's roof turns where the hall does. Every wall under it is filled up to it in the
     * {@code gable} surface (a material may carry its pieces' finish, as {@code ...#paint}), a {@code verge} high band
     * of the {@code trim} surface along its top. Ribs of the trim surface cross it every {@code ribPitch} along it, from
     * half a pitch off the hex's middle (where a hall's wall bays meet), {@code ribWidth} wide and {@code ribHeight}
     * high (0: none). {@code tint}: the roof sheeting's linear tint. It rises half a level at
     * the most: a unit on the roof plays at the eaves' level and is drawn standing on the roof.
     */
    record Vault(String shape, double rise, int[] segments, KitDoors.Part gable, KitDoors.Part trim, double verge,
          double ribPitch, double ribWidth, double ribHeight, float[] tint) {
        int segments(int lod) { return Math.max(1, segments[Math.min(lod, segments.length - 1)]); }

        /** The roof's height over its eaves, {@code x} off the middle line of a column {@code half} wide a side. */
        double height(double x, double half) {
            double t = Math.abs(x) / half;
            if (t >= 1) { return 0; }
            return switch (shape) {
                case "gable" -> rise * (1 - t);
                case "arch" -> rise * Math.sqrt(1 - t * t);
                case "bow", "plates" -> {
                    // A circle through both eaves and the crown.
                    double radius = (half * half + rise * rise) / (2 * rise);
                    yield Math.sqrt(radius * radius - x * x) - (radius - rise);
                }
                default -> throw new IllegalArgumentException("a vault's shape is gable, bow, arch or plates, not " + shape);
            };
        }
    }

    /** A strip {@code width} out from the ground storey's walls at {@code kerb} height, a board material on its axes. */
    record Sidewalk(String material, double repeat, double width, double kerb) { }

    /** A piece laid in stretches of {@code length} along the wall, each stretched a little to fit. */
    record Stretch(String piece, double length) { }

    /**
     * How a module lays a wall. Whole {@code bay}s of the {@code bays} pieces (chosen by the wall's place where there are
     * several) are stretched to fit between a {@code margin} at each end, a ground storey's {@code entrance} piece in a hub
     * face's first bay and next to the middle of a whole hex's front; with no margin a bay more goes on past an end where
     * the wall goes on. Whole {@code ends} stretches fill the margins and the wall's going on; one {@code under} stretch
     * lies along the whole of it. {@code planar} materials are textured along the wall's line, units a repeat, so their
     * texture runs on across the pieces. A rounded corner's facets each take one {@code round} piece in place of bays; with
     * {@code shallow}, a corner that turns less than {@link #SHARP} takes that piece instead (a kit's turrets stand on
     * its square corners only). A wall too short for a bay within its margins is laid with its {@code ends} alone.
     * A module is one hex's storey ({@link KitOutline#joints}): with {@code joints}, a wall ends its module where a linked
     * hexside goes on past it or the outline turns into a joint (a cap's wall leaving that room to the walls beside it),
     * and goes on along a joint that continues it. A roof's {@code seam} lies along each joint, the roof slab kept its
     * inset off it; such a roof comes closed above lower neighbours (ConnectedKit's {@code -closed-N} twins), its caps
     * empty. All but bays and bay are optional.
     */
    record Run(List<String> bays, double bay, double margin, String entrance, Stretch ends, Stretch under,
               Map<String, Double> planar, Stretch round, Stretch shallow, Joints joints, Stretch seam) { }

    /** The turn, in radians, from which a rounded corner takes its run's {@code round} piece rather than its {@code shallow}. */
    private static final double SHARP = Math.toRadians(75);

    /** The pieces ending a module's wall at its {@code start} and {@code end}, each {@code length} long, unstretched. */
    record Joints(String start, String end, double length) { }

    /** How a wall's end meets its module's joints: the module ends there, or goes straight on along a joint. */
    private enum Meeting { NONE, ENDS, CONTINUES }

    /** A wall end's meeting and the joint it meets ({@code null}: none in the piece). */
    private record Meet(Meeting kind, KitOutline.Wall joint) {
        /**
         * Whether {@code wall} ends on its joint at a slant, as a closed link's wall along its hexside meets the next
         * joint: a joint piece, made for a square end, would leave its groove open into the module there.
         */
        boolean slants(KitOutline.Wall wall) { return kind == Meeting.ENDS && joint != null && !square(wall, joint); }
    }

    /** Whether a wall meets a joint square; the kit's float outline leaves walls a hair off. */
    private static boolean square(KitOutline.Wall wall, KitOutline.Wall joint) {
        double[] u = KitOutline.direction(wall), v = KitOutline.direction(joint);
        return Math.abs(u[0] * v[0] + u[1] * v[1]) < 1e-3;
    }

    /** A piece laid at {@code start} along the wall, stretched by {@code scale}. */
    private record Placed(double start, String piece, double scale) { }

    /** The kit's levels of detail ({@link GeneratedKit#lods}); {@code modelled}, the pieces GLB's levels. */
    static List<RigidGlb.Data> lods(Spec spec, List<ModelData> modelled, boolean square, String name) {
        return lods(spec, modelled, square, name, 1);
    }

    static List<RigidGlb.Data> lods(Spec spec, List<ModelData> modelled, boolean square, String name, float footprintScale) {
        for (Slab slab : new Slab[] { spec.roof(), spec.floors() }) {
            if (slab == null || slab.material() == null || !(slab.repeat() > 0) || slab.heights() == null
                  || slab.heights().length == 0) {
                throw new IllegalArgumentException("Bay kit " + name + " needs its roof and floors, each with a repeat and heights");
            }
        }
        if (spec.sidewalk() != null && !(spec.sidewalk().repeat() > 0)) {
            throw new IllegalArgumentException("Bay kit " + name + " needs its sidewalk's repeat");
        }
        spec.modules().values().stream().flatMap(List::stream).forEach(run -> {
            if (run.bays() != null && !run.bays().isEmpty() && !(run.bay() > 0)
                  || run.ends() != null && !(run.ends().length() > 0) || run.under() != null && !(run.under().length() > 0)
                  || run.round() != null && !(run.round().length() > 0) || run.shallow() != null && !(run.shallow().length() > 0)
                  || run.joints() != null && !(run.joints().length() > 0)
                  || run.seam() != null && !(run.seam().length() > 0)) {
                throw new IllegalArgumentException("Bay kit " + name + " lays a piece without its length");
            }
        });
        if (spec.cornerRadius() > 0 && spec.cornerSegments() < 1) {
            throw new IllegalArgumentException("Bay kit " + name + " rounds its corners in no segments");
        }
        Vault vault = spec.roof().vault();
        if (vault != null && vault.rise() > KitOutline.LEVEL / 2) {
            // A unit on it plays at its eaves' level and is drawn on it (GpuTerrain#roofLift): higher would mislead.
            throw new IllegalArgumentException("Bay kit " + name + " has a vault higher than half a level");
        }
        if (vault != null && (!(vault.rise() > 0) || vault.segments() == null || vault.segments().length == 0 || vault.gable() == null
              || vault.gable().material() == null || (vault.verge() > 0 || vault.ribHeight() > 0) && (vault.trim() == null
              || vault.trim().material() == null) || vault.ribHeight() > 0 && !(vault.ribPitch() > 0 && vault.ribWidth() > 0))) {
            throw new IllegalArgumentException("Bay kit " + name + " needs its vault's rise, segments and gable, and a trim for a verge or ribs");
        }
        // Slabs and sidewalks under a material of their own: a piece of the same board material keeps its UVs.
        Map<String, Float> boardUVs = new HashMap<>();
        for (Slab slab : new Slab[] { spec.roof(), spec.floors() }) { boardUVs.put(board(slab.material()), (float) slab.repeat()); }
        boardUVs.put(board(spec.floors().material()) + GeneratedMesh.BUILDING_FLOOR, (float) spec.floors().repeat());
        boardUVs.put(board(deck(spec.roof())), (float) spec.roof().repeat());
        if (spec.sidewalk() != null) { boardUVs.put(board(spec.sidewalk().material()), (float) spec.sidewalk().repeat()); }
        // With fittings, each roof module comes once per layout: roof roles numbered on, each naming its module and layout.
        Map<String, String> modules = new LinkedHashMap<>();
        Map<String, Map<String, List<double[]>>> layouts = new HashMap<>();
        int roofs = 0;
        for (String role : spec.modules().keySet()) {
            if (!role.startsWith("roof") || spec.fittings() == null) {
                modules.put(role, role);
                continue;
            }
            for (var layout : spec.fittings().of(square)) {
                String numbered = "roof" + roofs++;
                modules.put(numbered, role);
                layouts.put(numbered, layout);
            }
        }
        List<KitOutline.Piece> pieces = KitOutline.footprint(square, spec.bandEnd(), turned(spec), footprintScale).stream()
              .map(piece -> KitOutline.rounded(piece, spec.cornerRadius(), Math.max(1, spec.cornerSegments()))).toList();
        double halfLength = spec.lone() == null ? H : spec.lone().stream().mapToDouble(p -> Math.abs(p[1])).max().orElse(H);
        return GeneratedKit.lods(name, square, modelled, List.copyOf(modules.keySet()), pieces, boardUVs,
              (role, piece, lod) -> twins(spec, modelled, modules.get(role), piece, lod, square, footprintScale,
                    layouts.get(role), halfLength), spec.roof().aviationLights() ? spec.roof()::height : null);
    }

    /**
     * A role's module for a piece; also closed beside each set of its links' shorter neighbours (the twins that stand in
     * for it there): a seamed roof's, an Angled storey's, whose own walls then meet in corners like the kit's others, and
     * its roof's where those corners round or carry aviation lights. Their caps stay empty, since those twins carry their
     * edges.
     */
    private static Map<String, GeneratedMesh> twins(Spec spec, List<ModelData> modelled, String role, KitOutline.Piece piece,
          int lod, boolean square, float footprintScale, Map<String, List<double[]>> layout, double halfLength) {
        boolean seamed = role.startsWith("roof") && spec.modules().get(role).stream().anyMatch(run -> run.seam() != null);
        boolean closing = seamed || role.startsWith("roof") && spec.roof().aviationLights()
              || !square && (!role.startsWith("roof") || spec.cornerRadius() > 0);
        if (closing && piece.tags().endsWith("-cap")) { return Map.of("", new GeneratedMesh()); }
        Map<String, GeneratedMesh> result = new LinkedHashMap<>();
        result.put("", module(spec, modelled, role, piece, 0, lod, square, footprintScale, layout, halfLength));
        // A roof's aviation lights, beside each of its twins (GeneratedKit#lights).
        boolean lit = role.startsWith("roof") && spec.roof().aviationLights();
        double facet = spec.cornerRadius() * Math.PI / Math.max(1, spec.cornerSegments()), deck = spec.roof().height(lod);
        if (lit) { GeneratedKit.lights(result, "", piece, facet, deck, lod); }
        int links = closing ? KitOutline.links(piece) : 0;
        boolean fills = !role.startsWith("roof") && !piece.tags().endsWith("-cap") && joined(spec, role, lod);
        if (fills) { putFill(result, "-fill", fill(spec, modelled, role, piece, 0, lod, square)); }
        boolean jointed = spec.modules().values().stream().flatMap(List::stream).anyMatch(run -> run.joints() != null);
        // A storey's sills, where the storey standing on it closes links it keeps (ConnectedKit places them).
        GeneratedMesh sill = role.startsWith("roof") || fills || square || piece.tags().endsWith("-cap") ? null : sill(spec, piece);
        if (sill != null && !sill.parts.isEmpty()) { result.put("-sill", sill); }
        // A roof's corner linked both ways and closed one way: its edge turns an inside corner there (ConnectedKit#VALLEY).
        boolean turns = role.startsWith("roof") && !square && !seamed && !jointed && spec.roof().vault() == null
              && piece.direction() != null && piece.walls().isEmpty() && Integer.bitCount(links) == 2;
        for (int closed = links; closed > 0; closed = (closed - 1) & links) {
            // Its new corners round like the kit's others.
            var closedPiece = KitOutline.rounded(KitOutline.closed(piece, closed), spec.cornerRadius(),
                  Math.max(1, spec.cornerSegments()));
            result.put("-closed-" + closed, module(spec, modelled, role, closedPiece, closed, lod,
                  square, footprintScale, layout, halfLength));
            if (lit) { GeneratedKit.lights(result, "-closed-" + closed, closedPiece, facet, deck, lod); }
            if (fills) { putFill(result, "-closed-" + closed + "-fill", fill(spec, modelled, role, closedPiece, closed, lod, square)); }
            // A closed twin's own: over the corners its remaining links would still turn.
            GeneratedMesh closedSill = sill == null ? null : sill(spec, KitOutline.closed(piece, closed));
            if (closedSill != null && !closedSill.parts.isEmpty()) { result.put("-closed-" + closed + "-sill", closedSill); }
            if (turns && Integer.bitCount(closed) == 1) {
                result.put("-closed-" + closed + "-valley", module(spec, modelled, role, KitOutline.inward(closedPiece, true), closed,
                      lod, square, footprintScale, layout, halfLength));
                result.put("-closed-" + closed + "-wall", module(spec, modelled, role, KitOutline.inward(closedPiece, false), closed,
                      lod, square, footprintScale, layout, halfLength));
            }
        }
        // At a notch its walls ending open turn the corner with the next hex's ({@link KitOutline#notched}): a twin for
        // each set of such corners, which ConnectedKit picks; not where modules part at their joints.
        int open = square || jointed || piece.tags().endsWith("-cap") ? 0 : KitOutline.openCorners(piece);
        for (int notch = open; notch > 0; notch = (notch - 1) & open) {
            var notched = KitOutline.notched(piece, notch);
            result.put("-notch-" + notch, module(spec, modelled, role, notched, 0, lod, square, footprintScale, layout,
                  halfLength));
            if (lit) { GeneratedKit.lights(result, "-notch-" + notch, notched, facet, deck, lod); }
        }
        // A vault's corner linked both ways, where the hexes round it are one building: no valley (ConnectedKit#CROWN).
        if (role.startsWith("roof") && spec.roof().vault() != null && !square && piece.direction() != null && piece.walls().isEmpty()
              && Integer.bitCount(KitOutline.links(piece)) == 2) {
            result.put("-crown", crown(spec.roof(), piece, lod, spec.roof().height(lod) - .02));
        }
        // A storey's other junctions at the corner, held by its corner piece linked both ways (ConnectedKit places them).
        List<Run> runs = spec.modules().get(role);
        Run run = runs.get(Math.min(lod, runs.size() - 1));
        if (!square && !jointed && !role.startsWith("roof") && piece.direction() != null && piece.walls().isEmpty()
              && run.bays() != null && !run.bays().isEmpty()) {
            for (var junction : KitOutline.Junction.values()) {
                if (junction.notch()) { continue; }
                var wedge = junction(run, piece.direction(), junction, modelled, lod);
                if (!wedge.parts.isEmpty()) { result.put("-junction-" + junction.ordinal(), wedge); }
            }
        }
        return result;
    }

    /**
     * Where a wall ends at a hex corner on another hex's wall that does not go straight on ({@link KitOutline.Junction}),
     * it is carried on past the corner by its run's first bay and cut on the mitre between the two: an inside corner.
     * The bay's end lies on the corner either way (mirrored on a wall ending there), so both walls show the same
     * section there.
     */
    private static GeneratedMesh junction(Run run, int corner, KitOutline.Junction junction, List<ModelData> modelled, int lod) {
        var wall = KitOutline.junctionWall(junction, corner);
        double[] at = KitOutline.hexCorner(corner), u = KitOutline.direction(wall);
        boolean ends = KitOutline.close(wall.q(), at);
        var laid = new GeneratedMesh();
        lay(laid, List.of(ends ? new Placed(wall.length() + run.bay(), run.bays().getFirst(), -1)
              : new Placed(-run.bay(), run.bays().getFirst(), 1)), wall, 0, modelled, lod);
        if (run.planar() != null) { run.planar().forEach((material, repeat) -> planar(laid, material, wall, repeat)); }
        // Past the corner, with a lap under the wall's own end bay, on its side of the mitre.
        double lap = KitOutline.SEAM_OVERLAP / 2, sign = ends ? 1 : -1;
        return laid.clipped(List.of((x, y) -> lap + sign * ((x - at[0]) * u[0] + (y - at[1]) * u[1]),
              KitOutline.mitre(at, normal(wall.p(), wall.q()), KitOutline.partnerNormal(junction, corner), ends ? wall.p() : wall.q())));
    }

    /**
     * One piece of a module: its walls' runs (and sidewalk), a roof's seams, its floor or roof slab and a roof's fittings
     * of {@code layout} ({@code null}: none), cut to its corner; {@code closed}, the links a roof closes.
     */
    private static GeneratedMesh module(Spec spec, List<ModelData> modelled, String role, KitOutline.Piece piece, int closed,
          int lod, boolean square, float footprintScale, Map<String, List<double[]>> layout, double halfLength) {
        boolean ground = role.startsWith("base"), top = role.startsWith("roof"), cap = piece.tags().endsWith("-cap");
        List<Run> runs = spec.modules().get(role);
        Run run = runs.get(Math.min(lod, runs.size() - 1));
        boolean seamed = top && run.seam() != null;
        List<KitOutline.Wall> joints = cap || run.joints() == null && !seamed ? List.of()
              : KitOutline.joints(square, KitOutline.links(piece) & ~closed);
        // How far a wall goes on along a joint that continues it: its joint's bevel, or the roof's off its seam.
        double chamfer = seamed ? spec.roof().inset() : run.joints() != null ? run.joints().length() : 0;
        var result = new GeneratedMesh();
        List<GeneratedMesh.Keep> cut = piece.direction() != null ? KitOutline.sector(piece.direction()) : List.of();
        // Each side laid whole, so its bays repeat along it unbroken.
        List<KitOutline.Wall> sides = KitOutline.sides(piece.walls());
        for (var wall : sides) {
            double outlineScale = piece.direction() == null && !piece.tags().contains("-link-") ? footprintScale : 1;
            List<GeneratedMesh.Keep> keeps = keeps(wall, sides);
            KitOutline.Wall[] partners = square || piece.direction() == null ? new KitOutline.Wall[2]
                  : new KitOutline.Wall[] { partner(spec, piece.direction(), wall, true, turned(spec)),
                        partner(spec, piece.direction(), wall, false, turned(spec)) };
            double[] goesOn = facade(result, run, wall, keeps, modelled, lod, top ? ROOF_INSET : 0, ground, outlineScale,
                  wall.turn() > 0 ? corner(run, wall) : null, joints, cap, chamfer, cut, partners, spec.varyBays());
            if (ground && spec.sidewalk() != null) { sidewalk(result, spec.sidewalk(), wall, keeps, goesOn[0], goesOn[1]); }
        }
        // Closed modules: a module parted from the next at its joints has its own side along each, behind the grooves,
        // and a seamed roof a floor under its grooves. Where the next module does not meet it exactly, these show, never
        // the inside.
        if (run.joints() != null && !cap && !square) {
            for (var joint : joints) { side(result, joint, piece.walls(), run.joints().length()); }
        }
        if (seamed) {
            for (var polygon : piece.polygons()) {
                for (var part : KitOutline.inset(polygon, piece.walls(), .5)) { underside(result, part, -chamfer - .01); }
            }
            for (var joint : joints) { pocket(result, joint, chamfer); }
        }
        List<KitOutline.Wall> edges = new ArrayList<>(piece.walls());
        if (seamed) {
            for (var joint : joints) { seam(result, run.seam(), joint, piece.walls(), joints, modelled, lod, chamfer); }
            edges.addAll(joints);
        }
        Slab slab = top ? spec.roof() : spec.floors();
        double inset = slab.inset();
        // Seams part a roof's modules: each module's slab textured along it, not on the board's axes.
        double[] along = seamed ? axis(square, piece) : null;
        for (var polygon : piece.polygons()) {
            for (var part : inset > 0 ? KitOutline.inset(polygon, edges, inset) : List.of(polygon)) {
                // The authored rim has a horizontal lip at this height; keep the deck just underneath it.
                double height = slab.height(lod) - (top ? .02 : 0);
                if (top && slab.vault() != null) {
                    vault(result, slab, part, height, barrels(square, piece), lod);
                    continue;
                }
                if (along != null) {
                    flat(result, top ? deck(slab) : slab.material(), part, height, slab.repeat(), along);
                } else {
                    flat(result, top ? board(deck(slab)) : board(slab.material()) + GeneratedMesh.BUILDING_FLOOR, part, height, slab.repeat(), null);
                }
                if (top) { result.slab(GeneratedMesh.BUILDING_BODY, part, height - 1.2, height, false); }
                // A glass roof's underlay: the biodome's planted bed, which the sun lights through the glass.
                if (top && slab.underlay()) { flat(result, board(BoardMultiHexBuilding.GARDEN), part, height - .3, 24, null); }
            }
        }
        if (top && slab.vault() != null) {
            for (var wall : piece.walls()) { gable(result, slab.vault(), wall, slab.height(lod) - .02, barrels(square, piece), lod); }
        }
        if (top && layout != null) {
            HabitatKit.fittings(result, modelled, lod, layout, piece, spec.roof().height(lod), halfLength, footprintScale);
        }
        return piece.direction() != null
              ? result.clipped(KitOutline.sector(piece.direction(), KitOutline.SEAM_OVERLAP)) : result;
    }

    /** How far past a wall's end its keep stands at the least (see {@link #facade}). */
    private static final double END_CLEAR = .001;

    /** The dark of a module's inside, as vertex colour on the building body. */
    private static final float DARK = .17f;

    /**
     * The piece a rounded corner's facet takes ({@code null}: bays, as any wall): the run's {@code round}, or its
     * {@code shallow} where the outline turns less than {@link #SHARP} at that corner.
     */
    private static Stretch corner(Run run, KitOutline.Wall facet) {
        return run.shallow() != null && run.round() != null && facet.turn() < SHARP ? run.shallow() : run.round();
    }

    /**
     * The wall's own mitres, and at each round end the bisector with the next wall of its arc: what goes on past the end
     * stops there, meeting the next facet's.
     */
    private static List<GeneratedMesh.Keep> keeps(KitOutline.Wall wall, List<KitOutline.Wall> walls) {
        List<GeneratedMesh.Keep> keeps = new ArrayList<>(wall.keeps());
        double[][] ends = KitOutline.offsets(wall, walls);
        double[] u = KitOutline.direction(wall);
        for (int k = 0; k < 2; k++) {
            if ((k == 0 ? wall.start() : wall.end()) != KitOutline.End.ROUND) { continue; }
            double[] at = ends[2 * k], half = ends[2 * k + 1];
            // The bisector runs through the end along the two walls' mean normal; keep the side the wall lies on.
            double side = Math.signum(KitOutline.cross(half, u)) * (k == 0 ? 1 : -1);
            keeps.add((x, y) -> side * KitOutline.cross(half, new double[] { x - at[0], y - at[1] }));
        }
        return keeps;
    }

    /**
     * Lays {@code run} along {@code wall}, {@code inset} inside its line: a cut, corner, round or notch end goes on
     * {@link KitOutline#EXTEND} past (the piece's cut or the wall's mitre trims it), an open or joint end stops square.
     * Where its module ends ({@link #meet}) it stops, its joint pieces there (a cap's room left empty; a storey's wall
     * ending on the joint at a slant goes on instead, cut on the joint's line) and a seamed roof's edge mitred to the
     * joint; where a joint continues it, it goes on {@code chamfer}, unmitred. At
     * the piece's {@code cut} or an open end (the next hex's wall goes on) its end bays go on a hair under the next
     * piece's, the bays beyond a cut stopping exactly on it; a notch's corner is closed by the run's first bay laid on
     * past it on both walls.
     * Returns how far it goes on before and after the wall.
     */
    private static double[] facade(GeneratedMesh result, Run run, KitOutline.Wall wall, List<GeneratedMesh.Keep> wallKeeps,
          List<ModelData> modelled, int lod, double inset, boolean ground, double outlineScale, Stretch corner,
          List<KitOutline.Wall> joints, boolean cap, double chamfer, List<GeneratedMesh.Keep> cut, KitOutline.Wall[] partners,
          boolean vary) {
        double length = wall.length();
        double[] p = wall.p(), u = KitOutline.direction(wall);
        List<GeneratedMesh.Keep> keeps = new ArrayList<>(wallKeeps);
        Meet[] meets = { meet(wall, true, joints), meet(wall, false, joints) };
        double[] reach = new double[2], pads = new double[2], laps = new double[2];
        for (int k = 0; k < 2; k++) {
            double[] point = k == 0 ? p : wall.q();
            KitOutline.End end = k == 0 ? wall.start() : wall.end();
            // A storey's wall ending on its joint at a slant goes on and is cut on the joint's line, where the next
            // module's wall or side meets it; no joint piece.
            boolean slants = run.joints() != null && meets[k].slants(wall);
            reach[k] = slants ? EXTEND : switch (meets[k].kind()) {
                case ENDS -> 0;
                case CONTINUES -> chamfer;
                case NONE -> goesOn(end) ? EXTEND : 0;
            };
            if (meets[k].kind() == Meeting.NONE && end == KitOutline.End.CUT && !cut.isEmpty()
                  || end == KitOutline.End.OPEN && joints.isEmpty() && run.joints() == null) {
                laps[k] = KitOutline.SEAM_OVERLAP / 2;
            }
            if (meets[k].kind() == Meeting.CONTINUES || slants) {
                keeps.removeIf(keep -> Math.abs(keep.at(point[0], point[1])) < 1e-6);
            }
            if (slants) {
                var joint = meets[k].joint();
                double[] from = joint.p(), v = KitOutline.direction(joint), far = k == 0 ? wall.q() : p;
                double side = Math.signum(KitOutline.cross(v, new double[] { far[0] - from[0], far[1] - from[1] }));
                keeps.add((x, y) -> side * KitOutline.cross(v, new double[] { x - from[0], y - from[1] }));
            } else if (meets[k].kind() == Meeting.ENDS && run.joints() != null) {
                pads[k] = run.joints().length();
            }
            if (meets[k].kind() == Meeting.ENDS && run.seam() != null && meets[k].joint() != null) {
                var joint = meets[k].joint();
                keeps.add(KitOutline.mitre(point, normal(p, wall.q()), normal(joint.p(), joint.q()), k == 0 ? wall.q() : p));
            }
        }
        double before = reach[0], after = reach[1];
        // The bays laid on past the wall's ends, for the next piece's cut or mitre to trim: at a cut, they stop exactly
        // on it, so no sliver of them shows past it where the next piece's own end bay stands.
        List<Placed> beyond = new ArrayList<>();
        List<Placed> placements = new ArrayList<>(corner != null
              ? List.of(new Placed(-before, corner.piece(), (length + before + after) / corner.length()))
              : layout(run, wall, length, before, after, ground, outlineScale, pads[0], pads[1], laps, beyond, partners, vary));
        if (!cap && pads[0] > 0) { placements.add(new Placed(0, run.joints().start(), 1)); }
        if (!cap && pads[1] > 0) { placements.add(new Placed(length - pads[1], run.joints().end(), 1)); }
        var laid = new GeneratedMesh();
        lay(laid, placements, wall, inset, modelled, lod);
        var past = new GeneratedMesh();
        lay(past, beyond, wall, inset, modelled, lod);
        laid.add(past.clipped(cut));
        if (run.planar() != null) { run.planar().forEach((material, repeat) -> planar(laid, material, wall, repeat)); }
        // An end bay's lap stays clear of the end's keep, and every end's keep a hair past it: a bay's end face never lies
        // on the clip, which cut holes in an awning's end cap at a joint.
        double keepBefore = Math.max(before, Math.max(2 * laps[0], END_CLEAR)),
              keepAfter = Math.max(after, Math.max(2 * laps[1], END_CLEAR));
        keeps.add((x, y) -> (x - p[0]) * u[0] + (y - p[1]) * u[1] + keepBefore);
        keeps.add((x, y) -> length + keepAfter - (x - p[0]) * u[0] - (y - p[1]) * u[1]);
        result.add(laid.clipped(keeps));
        return new double[] { before, after };
    }

    /** How far in from a hex corner a closed module's side runs: what shows of it from outside. Inside, the building runs on. */
    private static final double SIDE = 6;

    /**
     * A closed module's side along {@code joint}, {@link #SIDE} in from each of its corners: dark, facing the next module;
     * where a wall of the piece ends square at the corner, from {@code groove} in, the depth of that wall's joint piece,
     * so it stands behind the groove. From just over the floor (an interior floor keeps clear of wall faces at its height) up
     * to {@code groove} under the storey's top, the floor of a roof's seam groove.
     */
    private static void side(GeneratedMesh result, KitOutline.Wall joint, List<KitOutline.Wall> walls, double groove) {
        double[] p = joint.p(), q = joint.q();
        double length = joint.length();
        double from = grooved(p, joint, walls) ? groove : 0, to = grooved(q, joint, walls) ? length - groove : length;
        double top = KitOutline.LEVEL - groove;
        if (to - from <= 2 * SIDE) {
            sideFace(result, p, q, from, to, SIDE_FLOOR, top);
        } else {
            sideFace(result, p, q, from, from + SIDE, SIDE_FLOOR, top);
            sideFace(result, p, q, to - SIDE, to, SIDE_FLOOR, top);
        }
    }

    /** Whether a wall of the piece ends square on {@code joint} at {@code at}: its joint piece's groove lies there. */
    private static boolean grooved(double[] at, KitOutline.Wall joint, List<KitOutline.Wall> walls) {
        return walls.stream().anyMatch(wall -> (KitOutline.close(wall.p(), at) || KitOutline.close(wall.q(), at))
              && square(wall, joint));
    }

    /** The part of a side along p-q from {@code from} to {@code to} along it, {@code bottom} to {@code top}. */
    private static void sideFace(GeneratedMesh result, double[] p, double[] q, double from, double to, double bottom,
          double top) {
        if (to - from <= 1e-6) { return; }
        double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
        double[] a = KitOutline.lerp(p, q, from / length), b = KitOutline.lerp(p, q, to / length), n = normal(p, q);
        result.quad(GeneratedMesh.BUILDING_BODY, darkVertex(a, bottom, n), darkVertex(b, bottom, n), darkVertex(b, top, n),
              darkVertex(a, top, n));
    }

    /** Where a closed module's side starts, just over the storey's interior floor. */
    private static final double SIDE_FLOOR = .1;

    /**
     * The far side of a seamed roof's groove along {@code joint}: its floor and a back, {@code depth} into the next hex,
     * facing this module. Under a neighbour as tall it lies beneath that roof's own seam bevel; beside a taller one it
     * closes the groove against the neighbour's open storey. It stops {@code depth} short of the joint's ends, where
     * that roof's bevel stops at a wall's joint piece (whose back closes the groove) or turns along its next seam.
     */
    private static void pocket(GeneratedMesh result, KitOutline.Wall joint, double depth) {
        double length = joint.length();
        if (length <= 2 * depth) { return; }
        double[] p = KitOutline.lerp(joint.p(), joint.q(), depth / length),
              q = KitOutline.lerp(joint.p(), joint.q(), 1 - depth / length);
        double[] n = normal(p, q), back = { -n[0], -n[1] };
        double[] pp = { p[0] + n[0] * depth, p[1] + n[1] * depth }, qq = { q[0] + n[0] * depth, q[1] + n[1] * depth };
        underside(result, List.of(p, q, qq, pp), -depth);
        result.quad(GeneratedMesh.BUILDING_BODY, darkVertex(qq, -depth, back), darkVertex(pp, -depth, back), darkVertex(pp, 0, back),
              darkVertex(qq, 0, back));
    }

    private static boolean joined(Spec spec, String role, int lod) {
        List<Run> runs = spec.modules().get(role);
        return runs.get(Math.min(lod, runs.size() - 1)).joints() != null;
    }

    /**
     * A storey's sills: its top over each corner that it turns only where it closes links ({@link KitOutline#closed}),
     * as far in as the corner's rounding cuts. The storey standing on it may close those links above a lower neighbour
     * and round that corner, which would open this one's inside to the roofs beside it.
     */
    private static GeneratedMesh sill(Spec spec, KitOutline.Piece piece) {
        var result = new GeneratedMesh();
        int links = spec.cornerRadius() > 0 ? KitOutline.links(piece) : 0;
        List<double[]> covered = new ArrayList<>();
        for (int closed = links; closed > 0; closed = (closed - 1) & links) {
            List<KitOutline.Wall> walls = KitOutline.closed(piece, closed).walls();
            for (var first : walls) {
                double[] at = first.q();
                var second = first.end() != KitOutline.End.CORNER ? null
                      : walls.stream().filter(wall -> KitOutline.close(wall.p(), at)).findFirst().orElse(null);
                if (second == null || covered.stream().anyMatch(point -> KitOutline.close(point, at))
                      || piece.walls().stream().anyMatch(wall -> wall.end() == KitOutline.End.CORNER && KitOutline.close(wall.q(), at))) {
                    continue;
                }
                double[] u = KitOutline.direction(first), v = KitOutline.direction(second);
                double turn = Math.atan2(KitOutline.cross(u, v), u[0] * v[0] + u[1] * v[1]);
                if (turn <= 1e-6) { continue; }
                covered.add(at);
                double reach = Math.min(spec.cornerRadius() * Math.tan(turn / 2), Math.min(first.length(), second.length()));
                flat(result, board(spec.floors().material()), List.of(at, new double[] { at[0] - u[0] * reach, at[1] - u[1] * reach },
                      new double[] { at[0] + v[0] * reach, at[1] + v[1] * reach }), KitOutline.LEVEL - .02, spec.floors().repeat(), null);
            }
        }
        return result;
    }

    private static void putFill(Map<String, GeneratedMesh> result, String key, GeneratedMesh fill) {
        if (!fill.parts.isEmpty()) { result.put(key, fill); }
    }

    /**
     * Where another storey stands on this one (ConnectedKit places it), its joint pieces' top corners filled to the
     * crossing their bottoms have: {@code <joint piece>-fill} at each wall end where its module ends square at a joint.
     * At a joint's end without one (the module turns on along the next joint, as at a notch, or its wall slants there),
     * the next module's groove opens onto its storey line ({@link #closeEnd}).
     */
    private static GeneratedMesh fill(Spec spec, List<ModelData> modelled, String role, KitOutline.Piece piece, int closed,
          int lod, boolean square) {
        List<Run> runs = spec.modules().get(role);
        Run run = runs.get(Math.min(lod, runs.size() - 1));
        List<KitOutline.Wall> joints = KitOutline.joints(square, KitOutline.links(piece) & ~closed);
        var result = new GeneratedMesh();
        double groove = run.joints().length();
        for (var joint : square ? List.<KitOutline.Wall>of() : joints) {
            for (boolean start : new boolean[] { true, false }) {
                if (!grooved(start ? joint.p() : joint.q(), joint, piece.walls())) { closeEnd(result, joint, start, groove); }
            }
        }
        for (var wall : piece.walls()) {
            List<Placed> placed = new ArrayList<>();
            Meet start = meet(wall, true, joints), end = meet(wall, false, joints);
            if (start.kind() == Meeting.ENDS && !start.slants(wall)) { placed.add(new Placed(0, run.joints().start() + "-fill", 1)); }
            if (end.kind() == Meeting.ENDS && !end.slants(wall)) {
                placed.add(new Placed(wall.length() - run.joints().length(), run.joints().end() + "-fill", 1));
            }
            lay(result, placed, wall, 0, modelled, lod);
        }
        return piece.direction() != null ? result.clipped(KitOutline.sector(piece.direction(), KitOutline.SEAM_OVERLAP)) : result;
    }

    /**
     * A stacked storey's joint end without a square wall: its side on up to the side above, and a floor at its top
     * {@code groove} square into the module. A shorter neighbour's roof pocket stops that short of the end, and a closed
     * wall over the neighbour's roof has its bottom groove there.
     */
    private static void closeEnd(GeneratedMesh result, KitOutline.Wall joint, boolean start, double groove) {
        double length = joint.length();
        double[] p = joint.p(), q = joint.q(), at = start ? p : q, u = KitOutline.direction(joint), n = normal(p, q);
        double along = start ? groove : -groove;
        sideFace(result, p, q, start ? 0 : length - groove, start ? groove : length, KitOutline.LEVEL - groove,
              KitOutline.LEVEL + SIDE_FLOOR);
        double[] a = { at[0] + u[0] * along, at[1] + u[1] * along };
        underside(result, List.of(at, a, new double[] { a[0] - n[0] * groove, a[1] - n[1] * groove },
              new double[] { at[0] - n[0] * groove, at[1] - n[1] * groove }), KitOutline.LEVEL);
    }

    /** A dark floor facing up at {@code z} over a convex {@code polygon}. */
    private static void underside(GeneratedMesh result, List<double[]> polygon, double z) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) { area += KitOutline.cross(polygon.get(i), polygon.get((i + 1) % polygon.size())); }
        List<float[]> vertices = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            double[] point = polygon.get(area > 0 ? i : polygon.size() - 1 - i);
            // Facing the sun, darker than the sides: a groove's shadowed bottom.
            vertices.add(new float[] { (float) point[0], (float) point[1], (float) z, 0, 0, 1, DARK / 3, DARK / 3, DARK / 3, 1, 0, 0 });
        }
        result.polygon(GeneratedMesh.BUILDING_BODY, vertices);
    }

    private static float[] darkVertex(double[] p, double z, double[] normal) {
        return new float[] { (float) p[0], (float) p[1], (float) z, (float) normal[0], (float) normal[1], 0, DARK, DARK, DARK, 1, 0, 0 };
    }

    /** Lays {@code placements} along {@code wall}, {@code inset} inside its line: a piece's x along it, its -y out. */
    private static void lay(GeneratedMesh laid, List<Placed> placements, KitOutline.Wall wall, double inset,
          List<ModelData> modelled, int lod) {
        double[] p = wall.p(), u = KitOutline.direction(wall), n = normal(wall.p(), wall.q());
        for (Placed placed : placements) {
            float scale = (float) placed.scale();
            float x = (float) (p[0] + u[0] * placed.start() - n[0] * inset), y = (float) (p[1] + u[1] * placed.start() - n[1] * inset);
            var frame = new Matrix4(new float[] { (float) u[0] * scale, (float) u[1] * scale, 0, 0, (float) -n[0], (float) -n[1], 0, 0,
                  0, 0, 1, 0, x, y, 0, 1 });
            GeneratedKit.piece(laid, modelled, lod, placed.piece(), frame, 0);
        }
    }

    /**
     * How a wall's start or end meets its module's joints: its module ends where the outline turns left into a joint
     * there, or at an open end (a linked hexside goes on past it, its joint perhaps in the neighbour's hex); it goes
     * straight on where a joint continues it.
     */
    private static Meet meet(KitOutline.Wall wall, boolean start, List<KitOutline.Wall> joints) {
        double[] point = start ? wall.p() : wall.q(), u = KitOutline.direction(wall);
        Meet result = new Meet((start ? wall.start() : wall.end()) == KitOutline.End.OPEN ? Meeting.ENDS : Meeting.NONE, null);
        for (var joint : joints) {
            if (!KitOutline.close(start ? joint.q() : joint.p(), point)) { continue; }
            double[] v = KitOutline.direction(joint);
            double turn = start ? KitOutline.cross(v, u) : KitOutline.cross(u, v);
            if (Math.abs(turn) < 1e-6 && u[0] * v[0] + u[1] * v[1] > 0) { return new Meet(Meeting.CONTINUES, joint); }
            if (turn > 1e-6) { result = new Meet(Meeting.ENDS, joint); }
        }
        return result;
    }

    /**
     * A roof's seam along a joint, laid on past each end and cut where its module's outline goes on: mitred to the next
     * joint or to a wall it turns from (and, turning left, kept off that wall's bevel: {@code chamfer} from both lines
     * together), or starting {@code chamfer} past a wall it continues, as that wall's roof goes on; kept in the hex.
     */
    private static void seam(GeneratedMesh result, Stretch seam, KitOutline.Wall joint, List<KitOutline.Wall> walls,
          List<KitOutline.Wall> joints, List<ModelData> modelled, int lod, double chamfer) {
        double length = joint.length();
        double[] p = joint.p(), q = joint.q(), u = KitOutline.direction(joint), n = normal(p, q);
        // A seam on a hexside has its vertices there: clip by each side, not by the hex's nearest one.
        List<GeneratedMesh.Keep> keeps = new ArrayList<>(KitOutline.hexSides());
        double[] reach = new double[2];
        for (int k = 0; k < 2; k++) {
            boolean start = k == 0;
            double[] point = start ? p : q;
            KitOutline.Wall next = neighbour(joint, start, walls, joints);
            if (next == null) { continue; }
            double[] v = KitOutline.direction(next), m = normal(next.p(), next.q()), at = next.p();
            double turn = start ? KitOutline.cross(v, u) : KitOutline.cross(u, v), sign = start ? 1 : -1;
            boolean wall = !joints.contains(next);
            if (wall && Math.abs(turn) < 1e-6) {
                keeps.add((x, y) -> sign * ((x - point[0]) * u[0] + (y - point[1]) * u[1]) - chamfer);
                continue;
            }
            reach[k] = EXTEND;
            keeps.add(KitOutline.mitre(point, n, m, start ? q : p));
            if (wall && turn > 1e-6) {
                keeps.add((x, y) -> -((x - p[0]) * n[0] + (y - p[1]) * n[1]) - ((x - at[0]) * m[0] + (y - at[1]) * m[1]) - chamfer);
            }
        }
        List<Placed> placements = new ArrayList<>();
        stretches(placements, seam, -reach[0], length + reach[1]);
        var laid = new GeneratedMesh();
        lay(laid, placements, joint, 0, modelled, lod);
        keeps.add((x, y) -> (x - p[0]) * u[0] + (y - p[1]) * u[1] + reach[0]);
        keeps.add((x, y) -> length + reach[1] - (x - p[0]) * u[0] - (y - p[1]) * u[1]);
        result.add(laid.clipped(keeps));
    }

    /**
     * The wall or joint of {@code edge}'s module before its start or after its end: of those meeting it there head to
     * tail (not turning back), the sharpest left turn.
     */
    private static KitOutline.Wall neighbour(KitOutline.Wall edge, boolean start, List<KitOutline.Wall> walls,
          List<KitOutline.Wall> joints) {
        double[] u = KitOutline.direction(edge), point = start ? edge.p() : edge.q();
        KitOutline.Wall best = null;
        double bestAngle = -Math.PI + 1e-6;
        for (var list : List.of(walls, joints)) {
            for (var other : list) {
                if (other == edge || !KitOutline.close(start ? other.q() : other.p(), point)) { continue; }
                double[] v = KitOutline.direction(other);
                double angle = start ? Math.atan2(KitOutline.cross(v, u), v[0] * u[0] + v[1] * u[1])
                      : Math.atan2(KitOutline.cross(u, v), u[0] * v[0] + u[1] * v[1]);
                if (Math.PI - Math.abs(angle) > 1e-6 && angle > bestAngle) {
                    best = other;
                    bestAngle = angle;
                }
            }
        }
        return best;
    }

    /** The roof's own surface, apart from its parapets and fittings ({@link GeneratedMesh#ROOF_DECK}). */
    private static String deck(Slab roof) { return roof.material() + GeneratedMesh.ROOF_DECK; }

    /** A board material's part textured on the board's axes ({@link GeneratedMesh#BOARD_UV}). */
    private static String board(String material) { return material + GeneratedMesh.BOARD_UV; }

    /** Whether a facade goes on past an end, to be trimmed there: by the piece's cut, a corner's or notch's mitre, an arc's bisector. */
    private static boolean goesOn(KitOutline.End end) {
        return end == KitOutline.End.CUT || end == KitOutline.End.CORNER || end == KitOutline.End.ROUND || end == KitOutline.End.NOTCH;
    }

    /**
     * The pieces {@code run} lays along a wall {@code length} long, going on {@code before} and {@code after} it, its bays
     * kept {@code startPad} and {@code endPad} off its ends for its joints and its end bays going on {@code laps} past
     * them; the bays beyond its ends go into {@code beyond}: those the walls going straight on there lay ({@code
     * partners}, before and after), else the next of its own.
     */
    private static List<Placed> layout(Run run, KitOutline.Wall wall, double length, double before, double after,
          boolean ground, double outlineScale, double startPad, double endPad, double[] laps, List<Placed> beyond,
          KitOutline.Wall[] partners, boolean vary) {
        List<Placed> result = new ArrayList<>();
        if (run.under() != null && length + before + after > 1e-6) {
            result.add(new Placed(-before, run.under().piece(), (length + before + after) / run.under().length()));
        }
        double usable = length - 2 * run.margin() - startPad - endPad;
        // Too short for a bay within its margins: its ends' stretch along the whole of it, no bay squashed between them.
        if (run.ends() != null && run.bays() != null && !run.bays().isEmpty() && usable < SQUASHED * run.bay()) {
            stretches(result, run.ends(), -before, length + after);
            return result;
        }
        if (run.ends() != null) {
            stretches(result, run.ends(), -before, run.margin());
            stretches(result, run.ends(), length - run.margin(), length + after);
        }
        // A wall no longer than its margins holds no bay.
        if (run.bays() == null || run.bays().isEmpty() || usable <= 1e-6) { return result; }
        double scale = scale(run, wall, length, startPad, endPad, outlineScale, vary);
        int count = count(run, usable, scale);
        for (int index = 0; index < count; index++) {
            double start = run.margin() + startPad + index * run.bay() * scale, width = run.bay() * scale;
            if (run.margin() == 0 && index == 0) { start -= laps[0]; width += laps[0]; }
            if (run.margin() == 0 && index == count - 1) { width += laps[1]; }
            result.add(new Placed(start, bay(run, wall, index, count, ground), width / run.bay()));
        }
        if (run.margin() == 0) {
            // Past a cut the next piece lays the wall's own bays on; beyond a slanted cut each piece shows a part of them.
            if (before > 0 && partners[0] != null) {
                double other = scale(run, partners[0], partners[0].length(), 0, 0, 1, vary);
                int last = count(run, partners[0].length(), other);
                beyond.add(new Placed(startPad - run.bay() * other, bay(run, partners[0], last - 1, last, ground), other));
            } else if (before > 0) {
                beyond.add(new Placed(startPad - run.bay() * scale, wall.start() == KitOutline.End.NOTCH ? run.bays().getFirst()
                      : bay(run, wall, -1, count, ground), scale));
            }
            if (after > 0 && partners[1] != null) {
                double other = scale(run, partners[1], partners[1].length(), 0, 0, 1, vary);
                beyond.add(new Placed(length - endPad, bay(run, partners[1], 0, count(run, partners[1].length(), other), ground),
                      other));
            } else if (after > 0 && wall.end() == KitOutline.End.NOTCH) {
                // Round a notch's corner both walls go on in their run's first bay, its end on the corner (a wall ending
                // there lays it mirrored): the two meet on the mitre in the same section.
                beyond.add(new Placed(length - endPad + run.bay() * scale, run.bays().getFirst(), -scale));
            } else if (after > 0) {
                beyond.add(new Placed(length - endPad, bay(run, wall, count, count, ground), scale));
            }
        }
        return result;
    }

    /** The least and most a bay stretches where a wall may hold one bay fewer or more ({@link #scale}). */
    private static final double SQUASHED = .7, STRETCHED = 1.5;

    /**
     * How much {@code run}'s bays stretch to fill {@code wall}: padding changes the room, keeping the unpadded
     * proportions. The nearest count of whole bays; where the kit {@code vary}s them and one bay fewer and one more both
     * stretch between {@link #SQUASHED} and {@link #STRETCHED}, the wall's place picks between them, as it picks its bays.
     */
    private static double scale(Run run, KitOutline.Wall wall, double length, double startPad, double endPad,
          double outlineScale, boolean vary) {
        double nominal = (length - startPad - endPad) / outlineScale - 2 * run.margin(), bays = nominal / run.bay();
        int count = Math.max(1, (int) Math.rint(bays)), fewer = (int) Math.floor(bays), more = fewer + 1;
        if (vary && fewer >= 1 && bays / fewer <= STRETCHED && bays / more >= SQUASHED) {
            count = seed(wall, 11, 17) % 2 == 0 ? fewer : more;
        }
        return nominal / (count * run.bay());
    }

    /** How many of {@code run}'s bays, stretched by {@code scale}, fill {@code usable}. */
    private static int count(Run run, double usable, double scale) {
        return Math.max(1, (int) Math.ceil(usable / (run.bay() * scale) - 1e-6));
    }

    /** {@code run}'s bay {@code index} of {@code count} along {@code wall}: a door, or chosen by where the wall stands. */
    private static String bay(Run run, KitOutline.Wall wall, int index, int count, boolean ground) {
        if (ground && run.entrance() != null && doors(wall, count).contains(index)) { return run.entrance(); }
        return run.bays().get((int) Math.floorMod(seed(wall, 7, 13) + index * 5L, (long) run.bays().size()));
    }

    /** A number fixed by where {@code wall} stands (its middle, weighted {@code x} and {@code y}): alike in every piece. */
    private static long seed(KitOutline.Wall wall, int x, int y) {
        double[] p = wall.p(), q = wall.q();
        return Math.floorMod((long) Math.rint((p[0] + q[0]) / 2 * x + (p[1] + q[1]) / 2 * y), 997);
    }

    /**
     * The wall going straight on past {@code wall}'s cut start or end, in the next Angled corner piece whose other side is
     * linked or not as that needs, as its piece lays it (turned pieces: in their own frame); null past any other end.
     */
    private static KitOutline.Wall partner(Spec spec, int direction, KitOutline.Wall wall, boolean start, boolean turned) {
        if ((start ? wall.start() : wall.end()) != KitOutline.End.CUT) { return null; }
        double[] at = start ? wall.p() : wall.q(), u = KitOutline.direction(wall);
        int next = (direction + (start ? 1 : 5)) % 6;
        for (int variant = 0; variant < 4; variant++) {
            List<KitOutline.Wall> walls = corner(spec, next, variant).walls();
            for (int index = 0; index < walls.size(); index++) {
                var other = walls.get(index);
                double[] v = KitOutline.direction(other);
                if (KitOutline.close(start ? other.q() : other.p(), at) && u[0] * v[0] + u[1] * v[1] > 1 - 1e-9) {
                    return turned ? corner(spec, 0, variant).walls().get(index) : other;
                }
            }
        }
        return null;
    }

    /** The Angled corner piece {@code direction}, linked across its first side ({@code variant} bit 0) and second (bit 1). */
    private static KitOutline.Piece corner(Spec spec, int direction, int variant) {
        return KitOutline.rounded(KitOutline.angled("", direction, (variant & 1) != 0, (variant & 2) != 0), spec.cornerRadius(),
              Math.max(1, spec.cornerSegments()));
    }

    /** Whole {@code stretch}es from {@code start} to {@code end}, each stretched a little to fit. */
    private static void stretches(List<Placed> result, Stretch stretch, double start, double end) {
        if (end - start <= 1e-6) { return; }
        int count = Math.max(1, (int) Math.ceil((end - start) / stretch.length() - 1e-6));
        double width = (end - start) / count;
        for (int index = 0; index < count; index++) {
            result.add(new Placed(start + index * width, stretch.piece(), width / stretch.length()));
        }
    }

    /** A ground storey's door bays: a whole hex's front, next to its middle; a hub face (cut at both ends), its first. */
    private static Set<Integer> doors(KitOutline.Wall wall, int count) {
        // A whole hex's front: the bay or two at its middle.
        if (wall.front()) { return count % 2 == 0 ? Set.of(count / 2 - 1, count / 2) : Set.of(count / 2); }
        return wall.start() == KitOutline.End.CUT && wall.end() == KitOutline.End.CUT ? Set.of(0) : Set.of();
    }

    /**
     * {@code material}'s triangles textured in the wall's frame (along it from the hex centre's foot on its line, out of
     * it, up), each projected along its main axis: faces out or in take (along, up), faces up or down (along, out), faces
     * along the wall (out, up).
     */
    private static void planar(GeneratedMesh laid, String material, KitOutline.Wall wall, double repeat) {
        laid.parts.forEach((key, part) -> {
            if (!key.endsWith(GeneratedMesh.BOARD_UV) && GeneratedMesh.boardName(key).equals(material)) { planar(part, wall, repeat); }
        });
    }

    private static void planar(FloatArray part, KitOutline.Wall wall, double repeat) {
        double[] p = wall.p(), u = KitOutline.direction(wall), n = normal(wall.p(), wall.q());
        double along = p[0] * u[0] + p[1] * u[1], fx = p[0] - u[0] * along, fy = p[1] - u[1] * along;
        float[] items = part.items;
        for (int start = 0; start < part.size; start += 3 * RigidGlb.STRIDE) {
            double nx = 0, ny = 0, nz = 0;
            for (int k = 0; k < 3; k++) {
                int at = start + k * RigidGlb.STRIDE;
                nx += items[at + 3]; ny += items[at + 4]; nz += items[at + 5];
            }
            double nd = Math.abs(nx * n[0] + ny * n[1]), nt = Math.abs(nx * u[0] + ny * u[1]);
            for (int k = 0; k < 3; k++) {
                int at = start + k * RigidGlb.STRIDE;
                double rx = items[at] - fx, ry = items[at + 1] - fy;
                double t = rx * u[0] + ry * u[1], d = rx * n[0] + ry * n[1], z = items[at + 2];
                double a = nd >= Math.max(nt, Math.abs(nz)) ? t : Math.abs(nz) >= nt ? t : d;
                double b = nd >= Math.max(nt, Math.abs(nz)) ? z : Math.abs(nz) >= nt ? d : z;
                items[at + 10] = (float) (a / repeat);
                items[at + 11] = (float) (-b / repeat);
            }
        }
    }

    /**
     * The sidewalk out from a ground storey's wall, at kerb height (east and west facing a hair higher so two never meet
     * in one plane), going on with the wall's facade; mitred.
     */
    private static void sidewalk(GeneratedMesh result, Sidewalk walk, KitOutline.Wall wall, List<GeneratedMesh.Keep> wallKeeps,
          double before, double after) {
        double[] n = normal(wall.p(), wall.q()), u = KitOutline.direction(wall);
        double[] a = { wall.p()[0] - u[0] * before, wall.p()[1] - u[1] * before };
        double[] b = { wall.q()[0] + u[0] * after, wall.q()[1] + u[1] * after };
        var strip = new GeneratedMesh();
        flat(strip, board(walk.material()), List.of(a, b, new double[] { b[0] + n[0] * walk.width(), b[1] + n[1] * walk.width() },
              new double[] { a[0] + n[0] * walk.width(), a[1] + n[1] * walk.width() }),
              walk.kerb() + (Math.abs(n[0]) > .5 ? .02 : 0), walk.repeat(), null);
        // Mitred, never cut to the hex: a walkway reaching a little past it is fine (user, 2026-11-08).
        result.add(strip.clipped(wallKeeps));
    }

    /**
     * Whether the kit's Angled corner pieces are built once and turned by the runtime: not where they hold something that
     * lies one way in the hex, roof fittings or a vault along its column.
     */
    private static boolean turned(Spec spec) { return spec.fittings() == null && spec.roof().vault() == null; }

    /**
     * How far a piece's column reaches each side of its middle line: half a hexside, or a lone hex's half width, out to
     * its side corners, so its vault spans the whole outline and its slanted walls rise to it as gables.
     */
    private static double halfSpan(KitOutline.Piece piece) {
        double half = 0;
        if (piece.direction() == null && !piece.tags().contains("-link-")) {
            for (var wall : piece.walls()) { half = Math.max(half, Math.max(Math.abs(wall.p()[0]), Math.abs(wall.q()[0]))); }
        }
        return half > 0 ? half : KitOutline.A;
    }

    /**
     * One run of a piece's vault: it runs along {@code axis} through the hex's middle, {@code half} wide a side, over
     * the piece's part {@code within}; {@code ribbed}: the hall's ribs cross it.
     */
    private record Barrel(double[] axis, double half, List<GeneratedMesh.Keep> within, boolean ribbed) {
        /** How far a point lies to the right of the axis. */
        double across(double x, double y) { return x * axis[1] - y * axis[0]; }

        double along(double x, double y) { return x * axis[0] + y * axis[1]; }
    }

    /**
     * How a piece's vault runs ({@link Vault}). Square, a lone hex and where nothing else is said: north to south. An
     * Angled hex linked across one side only: along that link. An Angled corner piece: along the link of its one linked
     * side; with both linked, along each on its side of the line between them, a valley; with neither, across the line
     * to the hex corner between its sides, a hip facet. On the line to a side's middle every piece reads that side
     * alone: the crown where it is linked, else the same fall to the eave, so the pieces of a hex and its neighbours
     * meet whatever the others' links.
     */
    private static List<Barrel> barrels(boolean square, KitOutline.Piece piece) {
        int links = KitOutline.links(piece);
        if (square || piece.direction() == null && links == 0) {
            return List.of(new Barrel(new double[] { 0, 1 }, halfSpan(piece), List.of(), true));
        }
        if (piece.direction() == null) {
            return List.of(new Barrel(towards(Integer.numberOfTrailingZeros(links)), KitOutline.A, List.of(), true));
        }
        int side = piece.direction(), next = (side + 1) % 6;
        double[] a = towards(side), b = towards(next), between = KitOutline.hexCorner(side);
        boolean first = (links & 1 << side) != 0, second = (links & 1 << next) != 0;
        if (first && second) {
            double sign = Math.signum(KitOutline.cross(between, a));
            return List.of(new Barrel(a, KitOutline.A, List.of((x, y) -> sign * (between[0] * y - between[1] * x)), true),
                  new Barrel(b, KitOutline.A, List.of((x, y) -> -sign * (between[0] * y - between[1] * x)), true));
        }
        if (first || second) { return List.of(new Barrel(first ? a : b, KitOutline.A, List.of(), true)); }
        double length = Math.hypot(between[0], between[1]);
        return List.of(new Barrel(new double[] { -between[1] / length, between[0] / length }, KitOutline.A, List.of(), false));
    }

    /** The unit vector from a hex's middle towards the middle of its side {@code direction}. */
    private static double[] towards(int direction) {
        double[] middle = KitOutline.MIDPOINTS[direction];
        double length = Math.hypot(middle[0], middle[1]);
        return new double[] { middle[0] / length, middle[1] / length };
    }

    /** The lines a vault's facets meet on, eave to eave, with its height over the eaves and its girth from the first at each. */
    private static double[][] facets(Vault vault, double half, int lod) {
        int n = vault.segments(lod);
        double[] cs = new double[2 * n + 1], zs = new double[2 * n + 1], girth = new double[2 * n + 1];
        for (int k = 0; k <= 2 * n; k++) {
            cs[k] = half * (k - n) / n;
            zs[k] = vault.height(cs[k], half);
            if (k > 0) { girth[k] = girth[k - 1] + Math.hypot(cs[k] - cs[k - 1], zs[k] - zs[k - 1]); }
        }
        return new double[][] { cs, zs, girth };
    }

    /**
     * A roof {@link Vault} over {@code polygon}, a part of a piece's floor, from {@code base} up, each of its
     * {@code barrels} over its own part: its facets between the eaves, level outside them, sheeting textured along it
     * and round its girth, a dark underside, and its ribs.
     */
    /**
     * {@code slab}'s vault over a whole {@code piece} from {@code base}, every wall of it filled up to the vault: a hall's
     * roof for a kit that lays its own walls (HabitatKit).
     */
    static void hall(GeneratedMesh result, Slab slab, KitOutline.Piece piece, boolean square, double base, int lod) {
        List<Barrel> barrels = barrels(square, piece);
        for (var polygon : piece.polygons()) { vault(result, slab, polygon, base, barrels, lod); }
        for (var wall : piece.walls()) { gable(result, slab.vault(), wall, base, barrels, lod); }
    }

    private static void vault(GeneratedMesh result, Slab slab, List<double[]> polygon, double base, List<Barrel> barrels, int lod) {
        Vault vault = slab.vault();
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) { area += KitOutline.cross(polygon.get(i), polygon.get((i + 1) % polygon.size())); }
        List<double[]> outline = area > 0 ? polygon : polygon.reversed();
        float[] tint = vault.tint() == null ? new float[] { 1, 1, 1 } : vault.tint();
        boolean smooth = vault.shape().equals("bow") || vault.shape().equals("arch");
        for (Barrel barrel : barrels) {
            double[][] lines = facets(vault, barrel.half(), lod);
            double[] cs = lines[0], zs = lines[1], girth = lines[2], axis = barrel.axis();
            int last = cs.length - 1;
            var own = barrel.within().isEmpty() ? outline : KitOutline.clipped(outline, barrel.within());
            for (int k = -1; k <= last && !own.isEmpty(); k++) {
                // Outside the eaves the roof lies level; between two lines it is one facet.
                double c0 = k < 0 ? -1e9 : cs[k], c1 = k == last ? 1e9 : cs[k + 1];
                var part = KitOutline.clipped(own, List.of((x, y) -> barrel.across(x, y) - c0, (x, y) -> c1 - barrel.across(x, y)));
                if (part.isEmpty()) { continue; }
                List<float[]> top = new ArrayList<>(), under = new ArrayList<>();
                for (double[] point : part) {
                    double c = barrel.across(point[0], point[1]), z = base, g, slope = 0;
                    if (k < 0) {
                        g = c - cs[0];
                    } else if (k == last) {
                        g = girth[last] + c - cs[last];
                    } else {
                        double f = (c - cs[k]) / (cs[k + 1] - cs[k]);
                        z += zs[k] + (zs[k + 1] - zs[k]) * f;
                        g = girth[k] + (girth[k + 1] - girth[k]) * f;
                        slope = smooth ? slope(cs, zs, k) * (1 - f) + slope(cs, zs, k + 1) * f : (zs[k + 1] - zs[k]) / (cs[k + 1] - cs[k]);
                    }
                    double length = Math.hypot(slope, 1);
                    // Sheeting runs down the slope: its ribs repeat along the roof.
                    top.add(new float[] { (float) point[0], (float) point[1], (float) z, (float) (-slope * axis[1] / length),
                          (float) (slope * axis[0] / length), (float) (1 / length), tint[0], tint[1], tint[2], 1,
                          (float) (barrel.along(point[0], point[1]) / slab.repeat()), (float) (g / slab.repeat()) });
                    under.addFirst(new float[] { (float) point[0], (float) point[1], (float) (z - .35), 0, 0, -1, DARK, DARK, DARK, 1, 0, 0 });
                }
                result.polygon(deck(slab), top);
                result.polygon(GeneratedMesh.BUILDING_BODY, under);
                if (barrel.ribbed() && k >= 0 && k < last && vault.ribHeight() > 0) { ribs(result, vault, barrel, part, base, cs, zs, k); }
            }
        }
    }

    /**
     * A vault's corner piece inside a building, level at its crown: where three hexes of a building meet, their roofs
     * are one roof, not three that fall into a valley between them.
     */
    static GeneratedMesh crown(Slab slab, KitOutline.Piece piece, int lod, double base) {
        Vault vault = slab.vault();
        float[] tint = vault.tint() == null ? new float[] { 1, 1, 1 } : vault.tint();
        double z = base + vault.rise();
        var result = new GeneratedMesh();
        for (var polygon : piece.polygons()) {
            double area = 0;
            for (int i = 0; i < polygon.size(); i++) { area += KitOutline.cross(polygon.get(i), polygon.get((i + 1) % polygon.size())); }
            List<float[]> top = new ArrayList<>(), under = new ArrayList<>();
            for (double[] point : area > 0 ? polygon : polygon.reversed()) {
                top.add(new float[] { (float) point[0], (float) point[1], (float) z, 0, 0, 1, tint[0], tint[1], tint[2], 1,
                      (float) (point[0] / slab.repeat()), (float) (-point[1] / slab.repeat()) });
                under.addFirst(new float[] { (float) point[0], (float) point[1], (float) (z - .35), 0, 0, -1, DARK, DARK, DARK, 1, 0, 0 });
            }
            result.polygon(deck(slab), top);
            result.polygon(GeneratedMesh.BUILDING_BODY, under);
        }
        return result.clipped(KitOutline.sector(piece.direction(), KitOutline.SEAM_OVERLAP));
    }

    /** A vault's slope across itself at line {@code k}: between the facets either side of it. */
    private static double slope(double[] cs, double[] zs, int k) {
        int a = Math.max(0, k - 1), b = Math.min(cs.length - 1, k + 1);
        return (zs[b] - zs[a]) / (cs[b] - cs[a]);
    }

    /** The ribs across one facet's part of a vault (between its lines k and k + 1): each a top and the sides that show. */
    private static void ribs(GeneratedMesh result, Vault vault, Barrel barrel, List<double[]> facet, double base, double[] cs, double[] zs,
          int k) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, pitch = vault.ribPitch(), wide = vault.ribWidth() / 2;
        for (double[] point : facet) {
            low = Math.min(low, barrel.along(point[0], point[1]));
            high = Math.max(high, barrel.along(point[0], point[1]));
        }
        var trim = vault.trim();
        double[] axis = barrel.axis(), right = { axis[1], -axis[0] };
        double slope = (zs[k + 1] - zs[k]) / (cs[k + 1] - cs[k]), length = Math.hypot(slope, 1);
        double[] up = { -slope * right[0] / length, -slope * right[1] / length, 1 / length };
        for (double middle = (Math.floor(low / pitch - .5) + .5) * pitch; middle - wide < high; middle += pitch) {
            double from = middle - wide, to = middle + wide;
            var part = KitOutline.clipped(facet, List.of((x, y) -> barrel.along(x, y) - from, (x, y) -> to - barrel.along(x, y)));
            if (part.isEmpty()) { continue; }
            double[][] top = new double[part.size()][];
            for (int i = 0; i < top.length; i++) {
                double[] point = part.get(i);
                top[i] = new double[] { point[0], point[1], base + vault.ribHeight() + zs[k] + slope * (barrel.across(point[0], point[1]) - cs[k]) };
            }
            face(result, trim, 1, up, top);
            for (int i = 0; i < top.length; i++) {
                double[] a = top[i], b = top[(i + 1) % top.length];
                double la = barrel.along(a[0], a[1]), lb = barrel.along(b[0], b[1]), ca = barrel.across(a[0], a[1]), cb = barrel.across(b[0], b[1]);
                // Its sides along the roof, and its end over an eave; where the facet's own edge cuts it, it goes on.
                double[] out = Math.abs(la - from) < 1e-6 && Math.abs(lb - from) < 1e-6 ? new double[] { -axis[0], -axis[1], 0 }
                      : Math.abs(la - to) < 1e-6 && Math.abs(lb - to) < 1e-6 ? new double[] { axis[0], axis[1], 0 }
                      : k == 0 && Math.abs(ca - cs[0]) < 1e-6 && Math.abs(cb - cs[0]) < 1e-6 ? new double[] { -right[0], -right[1], 0 }
                      : k == cs.length - 2 && Math.abs(ca - cs[k + 1]) < 1e-6 && Math.abs(cb - cs[k + 1]) < 1e-6 ? new double[] { right[0], right[1], 0 }
                      : null;
                if (out != null) {
                    face(result, trim, .8, out, new double[][] { { a[0], a[1], a[2] - vault.ribHeight() }, { b[0], b[1], b[2] - vault.ribHeight() }, b, a });
                }
            }
        }
    }

    /**
     * A wall filled from the roof's {@code base} up to the vault over it: a gable end, or a wall that crosses the roof
     * at a slant; nothing along an eave. A verge band of the trim surface follows its top.
     */
    private static void gable(GeneratedMesh result, Vault vault, KitOutline.Wall wall, double base, List<Barrel> barrels, int lod) {
        double[] p = wall.p(), q = wall.q(), n = normal(p, q);
        double length = wall.length();
        List<Double> stops = new ArrayList<>(List.of(0.0, 1.0));
        for (Barrel barrel : barrels) {
            double from = barrel.across(p[0], p[1]), to = barrel.across(q[0], q[1]);
            for (double c : facets(vault, barrel.half(), lod)[0]) {
                double t = (c - from) / (to - from);
                if (Math.abs(to - from) > 1e-9 && t > 1e-6 && t < 1 - 1e-6) { stops.add(t); }
            }
        }
        stops.sort(null);
        double[] out = { n[0], n[1], 0 };
        for (int i = 0; i < stops.size() - 1; i++) {
            double[] a = KitOutline.lerp(p, q, stops.get(i)), b = KitOutline.lerp(p, q, stops.get(i + 1));
            double za = 0, zb = 0;
            for (Barrel barrel : barrels) {
                za = Math.max(za, vault.height(barrel.across(a[0], a[1]), barrel.half()));
                zb = Math.max(zb, vault.height(barrel.across(b[0], b[1]), barrel.half()));
            }
            if (za < 1e-6 && zb < 1e-6) { continue; }
            double ua = stops.get(i) * length, ub = stops.get(i + 1) * length;
            double[] in = { -n[0] * ROOF_INSET, -n[1] * ROOF_INSET }, proud = { n[0] * .15, n[1] * .15 };
            face(result, vault.gable(), 1, out, new double[][] { { a[0] + in[0], a[1] + in[1], base }, { b[0] + in[0], b[1] + in[1], base },
                  { b[0] + in[0], b[1] + in[1], base + zb }, { a[0] + in[0], a[1] + in[1], base + za } },
                  new double[][] { { ua, base }, { ub, base }, { ub, base + zb }, { ua, base + za } });
            if (vault.verge() > 0) {
                double la = Math.max(0, za - vault.verge()), lb = Math.max(0, zb - vault.verge());
                face(result, vault.trim(), 1, out, new double[][] { { a[0] + proud[0], a[1] + proud[1], base + la },
                      { b[0] + proud[0], b[1] + proud[1], base + lb }, { b[0] + proud[0], b[1] + proud[1], base + zb },
                      { a[0] + proud[0], a[1] + proud[1], base + za } });
            }
        }
    }

    /** A face of {@code surface} through {@code corners}, wound to face {@code out}, textured by its two widest axes. */
    private static void face(GeneratedMesh result, KitDoors.Part surface, double shade, double[] out, double[][] corners) {
        double[][] uv = new double[corners.length][];
        for (int i = 0; i < corners.length; i++) {
            double[] c = corners[i];
            uv[i] = Math.abs(out[2]) > .7 ? new double[] { c[1], c[0] } : Math.abs(out[0]) > Math.abs(out[1]) ? new double[] { c[1], c[2] }
                  : new double[] { c[0], c[2] };
        }
        face(result, surface, shade, out, corners, uv);
    }

    /** As {@link #face(GeneratedMesh, KitDoors.Part, double, double[], double[][])}, textured at {@code uv} (units, the second up). */
    private static void face(GeneratedMesh result, KitDoors.Part surface, double shade, double[] out, double[][] corners, double[][] uv) {
        float[] tint = surface.colour();
        List<float[]> vertices = new ArrayList<>();
        for (int i = 0; i < corners.length; i++) {
            double[] c = corners[i];
            vertices.add(new float[] { (float) c[0], (float) c[1], (float) c[2], (float) out[0], (float) out[1], (float) out[2],
                  (float) (tint[0] * shade), (float) (tint[1] * shade), (float) (tint[2] * shade), 1, (float) (uv[i][0] / surface.units()),
                  (float) (-uv[i][1] / surface.units()) });
        }
        double[] a = corners[0], b = corners[1], c = corners[2];
        double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]), ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
              nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        result.polygon(surface.material(), nx * out[0] + ny * out[1] + nz * out[2] >= 0 ? vertices : vertices.reversed());
    }

    /**
     * An upward polygon: with {@code along} null, on the board's texture axes (a {@link GeneratedMesh#BOARD_UV} part)
     * about {@code repeat} units a repeat, whole repeats per hex column across and per half hex up, so it continues across
     * every hexside; else {@code repeat} units a repeat along {@code along} from the hex's centre.
     */
    private static void flat(GeneratedMesh result, String material, List<double[]> polygon, double z, double repeat,
          double[] along) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            double[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        double across = Math.max(1, Math.rint(1.5 * R / repeat)), up = Math.max(1, Math.rint(H / repeat));
        List<float[]> vertices = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            double[] point = polygon.get(area > 0 ? i : polygon.size() - 1 - i);
            double u = along == null ? point[0] * across / (1.5 * R) : (point[0] * along[0] + point[1] * along[1]) / repeat;
            double v = along == null ? -point[1] * up / H : -(point[1] * along[0] - point[0] * along[1]) / repeat;
            vertices.add(new float[] { (float) point[0], (float) point[1], (float) z, 0, 0, 1, 1, 1, 1, 1, (float) u, (float) v });
        }
        result.polygon(material, vertices);
    }

    /**
     * The way a piece's module runs, one sense for every piece of a hex: Square, along its column; Angled, along its
     * one link, else along its first wall (which runs along the module), else along its first link.
     */
    private static double[] axis(boolean square, KitOutline.Piece piece) {
        if (square) { return new double[] { 0, 1 }; }
        int links = KitOutline.links(piece);
        double[] along;
        if (Integer.bitCount(links) == 1 || piece.walls().isEmpty() && links != 0) {
            double[] link = KitOutline.MIDPOINTS[Integer.numberOfTrailingZeros(links)];
            double length = Math.hypot(link[0], link[1]);
            along = new double[] { link[0] / length, link[1] / length };
        } else if (!piece.walls().isEmpty()) {
            along = KitOutline.direction(piece.walls().getFirst());
        } else {
            return new double[] { 0, 1 };
        }
        return along[1] < -1e-9 || Math.abs(along[1]) <= 1e-9 && along[0] < 0 ? new double[] { -along[0], -along[1] } : along;
    }
}
