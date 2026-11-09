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
     * in all six corners, as the fittings stand in the hex's middle).
     */
    record Spec(String name, String style, String pieces, List<double[]> lone, double bandEnd, Slab roof, Slab floors,
                Sidewalk sidewalk, Map<String, List<Run>> modules, double cornerRadius, int cornerSegments,
                HabitatKit.Fittings fittings) { }

    /**
     * A flat surface over the piece: a board material, {@code repeat} units a repeat on the board's axes, at a height per
     * level of detail (the last for any further), kept {@code inset} off the walls; with {@code underlay} (a glass roof),
     * a planted bed just under it, so the glass shows a greenhouse rather than the ground down a seam.
     */
    record Slab(String material, double repeat, double inset, double[] heights, boolean underlay) {
        double height(int lod) { return heights[Math.min(lod, heights.length - 1)]; }
    }

    /** A strip {@code width} out from the ground storey's walls at {@code kerb} height, a board material on its axes. */
    record Sidewalk(String material, double repeat, double width, double kerb) { }

    /** A piece laid in stretches of {@code length} along the wall, each stretched a little to fit. */
    record Stretch(String piece, double length) { }

    /**
     * How a module lays a wall. Whole {@code bay}s of the {@code bays} pieces (chosen by the wall's place where there are
     * several) are stretched to fit between a {@code margin} at each end, a ground storey's {@code door} piece in a hub
     * face's first bay and next to the middle of a whole hex's front; with no margin a bay more goes on past an end where
     * the wall goes on. Whole {@code ends} stretches fill the margins and the wall's going on; one {@code under} stretch
     * lies along the whole of it. {@code planar} materials are textured along the wall's line, units a repeat, so their
     * texture runs on across the pieces. A rounded corner's facets each take one {@code round} piece in place of bays.
     * A module is one hex's storey ({@link KitOutline#joints}): with {@code joints}, a wall ends its module where a linked
     * hexside goes on past it or the outline turns into a joint (a cap's wall leaving that room to the walls beside it),
     * and goes on along a joint that continues it. A roof's {@code seam} lies along each joint, the roof slab kept its
     * inset off it; such a roof comes closed above lower neighbours (ConnectedKit's {@code -closed-N} twins), its caps
     * empty. All but bays and bay are optional.
     */
    record Run(List<String> bays, double bay, double margin, String door, Stretch ends, Stretch under,
               Map<String, Double> planar, Stretch round, Joints joints, Stretch seam) { }

    /** The pieces ending a module's wall at its {@code start} and {@code end}, each {@code length} long, unstretched. */
    record Joints(String start, String end, double length) { }

    /** How a wall's end meets its module's joints: the module ends there, or goes straight on along a joint. */
    private enum Meeting { NONE, ENDS, CONTINUES }

    /** A wall end's meeting and the joint it meets ({@code null}: none in the piece). */
    private record Meet(Meeting kind, KitOutline.Wall joint) { }

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
                  || run.round() != null && !(run.round().length() > 0) || run.joints() != null && !(run.joints().length() > 0)
                  || run.seam() != null && !(run.seam().length() > 0)) {
                throw new IllegalArgumentException("Bay kit " + name + " lays a piece without its length");
            }
        });
        if (spec.cornerRadius() > 0 && spec.cornerSegments() < 1) {
            throw new IllegalArgumentException("Bay kit " + name + " rounds its corners in no segments");
        }
        // Slabs and sidewalks under a material of their own: a piece of the same board material keeps its UVs.
        Map<String, Float> boardUVs = new HashMap<>();
        for (Slab slab : new Slab[] { spec.roof(), spec.floors() }) { boardUVs.put(board(slab.material()), (float) slab.repeat()); }
        boardUVs.put(board(spec.floors().material()) + GeneratedMesh.BUILDING_FLOOR, (float) spec.floors().repeat());
        if (spec.sidewalk() != null) { boardUVs.put(board(spec.sidewalk().material()), (float) spec.sidewalk().repeat()); }
        HabitatKit.Fitting fitting = spec.fittings() == null ? null : square ? spec.fittings().square() : spec.fittings().angled();
        // With fittings, each roof module comes once per layout: roof roles numbered on, each naming its module and layout.
        Map<String, String> modules = new LinkedHashMap<>();
        Map<String, HabitatKit.Layout> layouts = new HashMap<>();
        int roofs = 0;
        for (String role : spec.modules().keySet()) {
            if (!role.startsWith("roof") || fitting == null) {
                modules.put(role, role);
                continue;
            }
            for (var layout : fitting.layouts()) {
                String numbered = "roof" + roofs++;
                modules.put(numbered, role);
                layouts.put(numbered, layout);
            }
        }
        List<KitOutline.Piece> pieces = KitOutline.footprint(square, spec.bandEnd(), fitting == null, footprintScale).stream()
              .map(piece -> KitOutline.rounded(piece, spec.cornerRadius(), Math.max(1, spec.cornerSegments()))).toList();
        double halfLength = spec.lone() == null ? H : spec.lone().stream().mapToDouble(p -> Math.abs(p[1])).max().orElse(H);
        return GeneratedKit.lods(name, square, modelled, List.copyOf(modules.keySet()), pieces, boardUVs,
              (role, piece, lod) -> twins(spec, modelled, modules.get(role), piece, lod, square, footprintScale, fitting,
                    layouts.get(role), halfLength));
    }

    /**
     * A role's module for a piece; also closed beside each set of its links' shorter neighbours (the twins that stand in
     * for it there): a seamed roof's, an Angled storey's, whose own walls then meet in corners like the kit's others, and
     * its roof's where those corners round. Their caps stay empty, since those twins carry their edges.
     */
    private static Map<String, GeneratedMesh> twins(Spec spec, List<ModelData> modelled, String role, KitOutline.Piece piece,
          int lod, boolean square, float footprintScale, HabitatKit.Fitting fitting, HabitatKit.Layout layout, double halfLength) {
        boolean seamed = role.startsWith("roof") && spec.modules().get(role).stream().anyMatch(run -> run.seam() != null);
        boolean closing = seamed || !square && (!role.startsWith("roof") || spec.cornerRadius() > 0);
        if (closing && piece.tags().endsWith("-cap")) { return Map.of("", new GeneratedMesh()); }
        Map<String, GeneratedMesh> result = new LinkedHashMap<>();
        result.put("", module(spec, modelled, role, piece, 0, lod, square, footprintScale, fitting, layout, halfLength));
        int links = closing ? KitOutline.links(piece) : 0;
        for (int closed = links; closed > 0; closed = (closed - 1) & links) {
            // Its new corners round like the kit's others.
            var closedPiece = KitOutline.rounded(KitOutline.closed(piece, closed), spec.cornerRadius(),
                  Math.max(1, spec.cornerSegments()));
            result.put("-closed-" + closed, module(spec, modelled, role, closedPiece, closed, lod,
                  square, footprintScale, fitting, layout, halfLength));
        }
        // At a notch its walls ending open turn the corner with the next hex's ({@link KitOutline#notched}): a twin for
        // each set of such corners, which ConnectedKit picks; not where modules part at their joints.
        boolean jointed = spec.modules().values().stream().flatMap(List::stream).anyMatch(run -> run.joints() != null);
        int open = square || jointed || piece.tags().endsWith("-cap") ? 0 : KitOutline.openCorners(piece);
        for (int notch = open; notch > 0; notch = (notch - 1) & open) {
            result.put("-notch-" + notch, module(spec, modelled, role, KitOutline.notched(piece, notch), 0, lod, square,
                  footprintScale, fitting, layout, halfLength));
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
          int lod, boolean square, float footprintScale, HabitatKit.Fitting fitting, HabitatKit.Layout layout, double halfLength) {
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
        for (var wall : piece.walls()) {
            double outlineScale = piece.direction() == null && !piece.tags().contains("-link-") ? footprintScale : 1;
            List<GeneratedMesh.Keep> keeps = keeps(wall, piece.walls());
            KitOutline.Wall[] partners = square || piece.direction() == null ? new KitOutline.Wall[2]
                  : new KitOutline.Wall[] { partner(spec, piece.direction(), wall, true, fitting == null),
                        partner(spec, piece.direction(), wall, false, fitting == null) };
            double[] goesOn = facade(result, run, wall, keeps, modelled, lod, top ? ROOF_INSET : 0, ground, outlineScale,
                  facet(spec, wall), joints, cap, chamfer, cut, piece.walls(), partners);
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
                if (along != null) {
                    flat(result, slab.material(), part, height, slab.repeat(), along);
                } else {
                    flat(result, board(slab.material()) + (top ? "" : GeneratedMesh.BUILDING_FLOOR), part, height, slab.repeat(), null);
                }
                if (top) { result.slab(GeneratedMesh.BUILDING_BODY, part, height - 1.2, height, false); }
                // A glass roof's underlay: the biodome's planted bed, which the sun lights through the glass.
                if (top && slab.underlay()) { flat(result, board(BoardMultiHexBuilding.GARDEN), part, height - .3, 24, null); }
            }
        }
        if (top && layout != null) {
            HabitatKit.fittings(result, modelled, lod, fitting, layout, piece, spec.roof().height(lod), halfLength, footprintScale);
        }
        return piece.direction() != null
              ? result.clipped(KitOutline.sector(piece.direction(), KitOutline.SEAM_OVERLAP)) : result;
    }

    /** How far past a wall's end its keep stands at the least (see {@link #facade}). */
    private static final double END_CLEAR = .001;

    /** The dark of a module's inside, as vertex colour on the building body. */
    private static final float DARK = .17f;

    /** Whether a wall is a facet of a rounded corner's arc: both ends round, and no longer than an arc's facet. */
    private static boolean facet(Spec spec, KitOutline.Wall wall) {
        return spec.cornerRadius() > 0 && wall.start() == KitOutline.End.ROUND && wall.end() == KitOutline.End.ROUND
              && wall.length() <= spec.cornerRadius() * Math.PI / 2 / Math.max(1, spec.cornerSegments()) + 1e-6;
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
     * Where its module ends ({@link #meet}) it stops, its joint pieces there (a cap's room left empty)
     * and a seamed roof's edge mitred to the joint; where a joint continues it, it goes on {@code chamfer}, unmitred. At
     * the piece's {@code cut} or an open end (the next hex's wall goes on) its end bays go on a hair under the next
     * piece's, the bays beyond a cut stopping exactly on it; a mitred corner is closed ({@link #closeMitre}), a notch's by
     * the run's first bay laid on past it on both walls.
     * Returns how far it goes on before and after the wall.
     */
    private static double[] facade(GeneratedMesh result, Run run, KitOutline.Wall wall, List<GeneratedMesh.Keep> wallKeeps,
          List<ModelData> modelled, int lod, double inset, boolean ground, double outlineScale, boolean facet,
          List<KitOutline.Wall> joints, boolean cap, double chamfer, List<GeneratedMesh.Keep> cut, List<KitOutline.Wall> walls,
          KitOutline.Wall[] partners) {
        double length = wall.length();
        double[] p = wall.p(), u = KitOutline.direction(wall);
        List<GeneratedMesh.Keep> keeps = new ArrayList<>(wallKeeps);
        Meet[] meets = { meet(wall, true, joints), meet(wall, false, joints) };
        double[] reach = new double[2], pads = new double[2], laps = new double[2];
        for (int k = 0; k < 2; k++) {
            double[] point = k == 0 ? p : wall.q();
            KitOutline.End end = k == 0 ? wall.start() : wall.end();
            reach[k] = switch (meets[k].kind()) {
                case ENDS -> 0;
                case CONTINUES -> chamfer;
                case NONE -> goesOn(end) ? EXTEND : 0;
            };
            if (meets[k].kind() == Meeting.NONE && end == KitOutline.End.CUT && !cut.isEmpty()
                  || end == KitOutline.End.OPEN && joints.isEmpty() && run.joints() == null) {
                laps[k] = KitOutline.SEAM_OVERLAP / 2;
            }
            if (meets[k].kind() == Meeting.CONTINUES) { keeps.removeIf(keep -> Math.abs(keep.at(point[0], point[1])) < 1e-6); }
            if (meets[k].kind() == Meeting.ENDS && run.joints() != null) { pads[k] = run.joints().length(); }
            if (meets[k].kind() == Meeting.ENDS && run.seam() != null && meets[k].joint() != null) {
                var joint = meets[k].joint();
                keeps.add(KitOutline.mitre(point, normal(p, wall.q()), normal(joint.p(), joint.q()), k == 0 ? wall.q() : p));
            }
        }
        double before = reach[0], after = reach[1];
        // The bays laid on past the wall's ends, for the next piece's cut or mitre to trim: at a cut, they stop exactly
        // on it, so no sliver of them shows past it where the next piece's own end bay stands.
        List<Placed> beyond = new ArrayList<>();
        List<Placed> placements = new ArrayList<>(facet && run.round() != null
              ? List.of(new Placed(-before, run.round().piece(), (length + before + after) / run.round().length()))
              : layout(run, wall, length, before, after, ground, outlineScale, pads[0], pads[1], laps, beyond, partners));
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
        for (int k = 0; k < 2; k++) {
            boolean start = k == 0;
            KitOutline.End end = start ? wall.start() : wall.end();
            double[] point = start ? p : wall.q();
            if (end == KitOutline.End.CORNER) {
                walls.stream().filter(other -> other != wall && KitOutline.close(start ? other.q() : other.p(), point)).findFirst()
                      .ifPresent(next -> closeMitre(result, laid, wall, start, normal(next.p(), next.q())));
            }
        }
        return new double[] { before, after };
    }

    /**
     * A mitred corner cuts the bays' recesses on a slant; where the next wall's bays leave the slant open, the inside
     * showed through. A dark face on the mitre, from the wall's line to its bays' deepest recess, closes this wall's cut.
     */
    private static void closeMitre(GeneratedMesh result, GeneratedMesh laid, KitOutline.Wall wall, boolean start, double[] m) {
        double[] c = start ? wall.p() : wall.q(), inside = start ? wall.q() : wall.p(), n = normal(wall.p(), wall.q());
        double[] b = { n[0] + m[0], n[1] + m[1] };
        double along = Math.hypot(b[0], b[1]);
        if (along < 1e-6) { return; }
        b[0] /= along; b[1] /= along;
        double deepest = 0, low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        for (FloatArray part : laid.parts.values()) {
            for (int at = 0; at < part.size; at += RigidGlb.STRIDE) {
                deepest = Math.max(deepest, -((part.items[at] - c[0]) * n[0] + (part.items[at + 1] - c[1]) * n[1]));
                low = Math.min(low, part.items[at + 2]);
                high = Math.max(high, part.items[at + 2]);
            }
        }
        if (deepest <= 0 || low >= high) { return; }
        // The mitre runs from the corner along the walls' mean normal; a point on it is `s` in from this wall's line.
        double reach = deepest / (b[0] * n[0] + b[1] * n[1]);
        double[] outer = c, inner = { c[0] - b[0] * reach, c[1] - b[1] * reach };
        // Facing out of this wall's side of the mitre, towards the next wall's.
        double[] face = { b[1], -b[0] };
        if (face[0] * (inside[0] - c[0]) + face[1] * (inside[1] - c[1]) > 0) { face[0] = -face[0]; face[1] = -face[1]; }
        double[] from = outer, to = inner;
        double[] right = normal(from, to);
        if (right[0] * face[0] + right[1] * face[1] < 0) { from = inner; to = outer; }
        result.quad(GeneratedMesh.BUILDING_BODY, darkVertex(from, low, face), darkVertex(to, low, face), darkVertex(to, high, face),
              darkVertex(from, high, face));
    }

    /** How far in from a hex corner a closed module's side runs: what shows of it from outside. Inside, the building runs on. */
    private static final double SIDE = 6;

    /**
     * A closed module's side along {@code joint}, {@link #SIDE} in from each of its corners: dark, facing the next module;
     * where a wall of the piece ends at the corner, from {@code groove} in, the depth of that wall's joint piece, so it
     * stands behind the groove. From just over the floor (an interior floor keeps clear of wall faces at its height) up
     * to {@code groove} under the storey's top, the floor of a roof's seam groove.
     */
    private static void side(GeneratedMesh result, KitOutline.Wall joint, List<KitOutline.Wall> walls, double groove) {
        double[] p = joint.p(), q = joint.q();
        double length = joint.length();
        double from = walls.stream().anyMatch(wall -> KitOutline.close(wall.p(), p) || KitOutline.close(wall.q(), p)) ? groove : 0;
        double to = walls.stream().anyMatch(wall -> KitOutline.close(wall.p(), q) || KitOutline.close(wall.q(), q)) ? length - groove : length;
        double top = KitOutline.LEVEL - groove;
        if (to - from <= 2 * SIDE) {
            sideFace(result, p, q, from, to, top);
        } else {
            sideFace(result, p, q, from, from + SIDE, top);
            sideFace(result, p, q, to - SIDE, to, top);
        }
    }

    /** The part of a side along p-q from {@code from} to {@code to} along it. */
    private static void sideFace(GeneratedMesh result, double[] p, double[] q, double from, double to, double top) {
        if (to - from <= 1e-6) { return; }
        double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
        double[] a = KitOutline.lerp(p, q, from / length), b = KitOutline.lerp(p, q, to / length), n = normal(p, q);
        result.quad(GeneratedMesh.BUILDING_BODY, darkVertex(a, SIDE_FLOOR, n), darkVertex(b, SIDE_FLOOR, n), darkVertex(b, top, n),
              darkVertex(a, top, n));
    }

    /** Where a closed module's side starts, just over the storey's interior floor. */
    private static final double SIDE_FLOOR = .1;

    /**
     * The far side of a seamed roof's groove along {@code joint}: its floor and a back, {@code depth} into the next hex,
     * facing this module. Under a neighbour as tall it lies beneath that roof's own seam bevel; beside a taller one it
     * closes the groove against the neighbour's open storey.
     */
    private static void pocket(GeneratedMesh result, KitOutline.Wall joint, double depth) {
        double[] p = joint.p(), q = joint.q(), n = normal(p, q), back = { -n[0], -n[1] };
        double[] pp = { p[0] + n[0] * depth, p[1] + n[1] * depth }, qq = { q[0] + n[0] * depth, q[1] + n[1] * depth };
        underside(result, List.of(p, q, qq, pp), -depth);
        result.quad(GeneratedMesh.BUILDING_BODY, darkVertex(qq, -depth, back), darkVertex(pp, -depth, back), darkVertex(pp, 0, back),
              darkVertex(qq, 0, back));
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
          KitOutline.Wall[] partners) {
        List<Placed> result = new ArrayList<>();
        if (run.under() != null && length + before + after > 1e-6) {
            result.add(new Placed(-before, run.under().piece(), (length + before + after) / run.under().length()));
        }
        if (run.ends() != null) {
            stretches(result, run.ends(), -before, run.margin());
            stretches(result, run.ends(), length - run.margin(), length + after);
        }
        double usable = length - 2 * run.margin() - startPad - endPad;
        // A wall no longer than its margins holds no bay.
        if (run.bays() == null || run.bays().isEmpty() || usable <= 1e-6) { return result; }
        double scale = scale(run, length, startPad, endPad, outlineScale);
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
                double other = scale(run, partners[0].length(), 0, 0, 1);
                int last = count(run, partners[0].length(), other);
                beyond.add(new Placed(startPad - run.bay() * other, bay(run, partners[0], last - 1, last, ground), other));
            } else if (before > 0) {
                beyond.add(new Placed(startPad - run.bay() * scale, wall.start() == KitOutline.End.NOTCH ? run.bays().getFirst()
                      : bay(run, wall, -1, count, ground), scale));
            }
            if (after > 0 && partners[1] != null) {
                double other = scale(run, partners[1].length(), 0, 0, 1);
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

    /** How much {@code run}'s bays stretch to fill a wall: padding changes the room, keeping the unpadded proportions. */
    private static double scale(Run run, double length, double startPad, double endPad, double outlineScale) {
        double nominal = (length - startPad - endPad) / outlineScale - 2 * run.margin();
        return nominal / (Math.max(1, (int) Math.rint(nominal / run.bay())) * run.bay());
    }

    /** How many of {@code run}'s bays, stretched by {@code scale}, fill {@code usable}. */
    private static int count(Run run, double usable, double scale) {
        return Math.max(1, (int) Math.ceil(usable / (run.bay() * scale) - 1e-6));
    }

    /** {@code run}'s bay {@code index} of {@code count} along {@code wall}: a door, or chosen by where the wall stands. */
    private static String bay(Run run, KitOutline.Wall wall, int index, int count, boolean ground) {
        if (ground && run.door() != null && doors(wall, count).contains(index)) { return run.door(); }
        // The same in every piece that holds the wall.
        double[] p = wall.p(), q = wall.q();
        long seed = Math.floorMod((long) Math.rint((p[0] + q[0]) / 2 * 7 + (p[1] + q[1]) / 2 * 13), 997);
        return run.bays().get((int) Math.floorMod(seed + index * 5L, (long) run.bays().size()));
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
        if (wall.front()) { return Set.of(wall.end() == KitOutline.End.JOINT ? count - 1 : 0); }
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
