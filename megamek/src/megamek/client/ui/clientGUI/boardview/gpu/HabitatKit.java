/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.A;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.H;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.LEVEL;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.MIDPOINTS;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.NOSE;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.ROOF_INSET;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.normal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix4;

/**
 * A generated kit in the habitat style ({@link GeneratedKit}, style "habitat", a variant's {@link Spec}): the lunar
 * habitat's corner and whole-hex pieces ({@link KitOutline}) in its Angled or Square footprint, every module (base, the
 * storeys the runtime picks from per hex and level, the roofs) at each level of detail. A facade along every wall, dark
 * piers at its corners and joints; on the ground a battered skirt and footings, on top a parapet or a rounded edge over
 * plating and the roof's fittings. Piers, footings and fittings are modelled pieces from the kit's pieces GLB.
 * Square facades and plating keep the board's texture axes; an Angled piece lays its plating along its building, mitred
 * where arms meet ({@code -bend} roofs behind a 60-degree bend).
 */
final class HabitatKit {
    /** Wall texture: two storeys a repeat up the wall; across, a third of a hex column (x facades), a quarter hex (y). */
    private static final double REPEAT_UP = 2 * LEVEL, REPEAT_X = A, REPEAT_Y = H / 2;
    /** Where a storey's foot lies in the wall texture: its upper storey's windows are lit, its lower storey's dimmed. */
    private static final double LIT = -.5, DIM = 0;

    private HabitatKit() { }

    /**
     * A habitat kit's look, as data: its variant's {@code name} and {@code style} (habitat); board materials by role ({@code wall}, {@code roof}, {@code pier}, {@code concrete});
     * the GLB of its modelled pieces beside the spec ({@code pieces}); convex corners rounded to {@code cornerRadius} in
     * {@code cornerSegments} arcs; the parapet ({@code null}: none); the roof's rounded edge ({@code eave}: the parapet's
     * outer top edge, or without one a rim curving from the wall onto the roof; {@code null}: square); the ground storey's
     * battered skirt; the pieces at the walls' corners and joints: piers up every storey, their tops on the roof, footings
     * on the ground ({@code null}: none); the Angled lone hex's half length and a single link's band end; the storeys
     * above the ground ({@code windows} lit or dim, shifted along by a fraction of a repeat); the roofs' fittings per
     * footprint ({@link Fittings}); with {@code aviationLights}, aviation obstruction lights on the roofs' convex corners
     * ({@link GeneratedKit#lights}); its large doors' look ({@code largeDoor}, {@link KitDoors.Style}; a plain steel
     * door without); with {@code vault}, a roof that spans the hall ({@link BayKit.Vault}) in place of the flat roof
     * with its rim, pier tops and fittings.
     */
    record Spec(String name, String style, Map<String, String> materials, String pieces, double cornerRadius, int cornerSegments, Parapet parapet,
                Eave eave, Skirt skirt, Uprights piers, Uprights tops, Uprights footings, Lone lone, List<Storey> storeys,
                Fittings fittings, boolean aviationLights, KitDoors.Style largeDoor, BayKit.Vault vault) {
        /** The roof that spans the hall ({@code vault}; null: the flat roof), in the roof's material. */
        BayKit.Slab hall() {
            return vault == null ? null : new BayKit.Slab(materials.get("roof"), 18, 0, new double[] { 0 }, false, aviationLights, vault);
        }
    }

    record Parapet(double height, double width) { }

    /** A quarter round of {@code radius}, in {@code segments} facets per level of detail (the last for any further). */
    record Eave(double radius, int[] segments) {
        int segments(int lod) { return segments[Math.min(lod, segments.length - 1)]; }
    }
    record Skirt(double height, double out) { }

    /**
     * The pieces standing at a wall's joints and at its convex corners. Each stands at the wall line, its x along the
     * wall (left to right seen from outside) and its -y out of it: a pier on its storey's floor, stretched to the
     * storey; a pier top on the roof, stretched to the roof's rim and .6 over it; a footing on the ground, as modelled.
     */
    record Uprights(String joint, String corner) { }
    record Lone(double halfLength, double bandEnd) { }
    record Storey(String windows, double shift) { }

    /**
     * The roofs' fittings by footprint: the layouts a roof comes in, each naming pieces of the pieces GLB and where each
     * stands on the roof about the hex's middle (x, y and a turn in degrees, none without).
     */
    record Fittings(List<Map<String, List<double[]>>> square, List<Map<String, List<double[]>>> angled) {
        List<Map<String, List<double[]>>> of(boolean isSquare) { return isSquare ? square : angled; }
    }

    /** A pier at a wall's start: where, facing which way, whether at a corner. */
    private record Pier(double x, double y, double[] n, boolean corner) { }

    /** How a piece lays its plating: the hex direction its halves run along, split on the diagonal to its corner. */
    private record Plating(int first, int second, Integer direction) { }

    /** One module's geometry for a piece, before the piece's cut, and the piers it stands. */
    private record Laid(GeneratedMesh geometry, List<Pier> piers) { }

    /** The kit's levels of detail ({@link GeneratedKit#lods}); {@code modelled}, the pieces GLB's levels. */
    static List<RigidGlb.Data> lods(Spec spec, List<ModelData> modelled, boolean square, String name) {
        return lods(spec, modelled, square, name, 1);
    }

    static List<RigidGlb.Data> lods(Spec spec, List<ModelData> modelled, boolean square, String name, float footprintScale) {
        if (spec.cornerRadius() > 0 && spec.cornerSegments() < 1 || spec.eave() != null
              && (spec.eave().segments() == null || spec.eave().segments().length == 0
                    || java.util.Arrays.stream(spec.eave().segments()).anyMatch(n -> n < 1))) {
            throw new IllegalArgumentException("Habitat kit " + name + " rounds a corner or its eave in no segments");
        }
        for (Uprights uprights : new Uprights[] { spec.piers(), spec.tops(), spec.footings() }) {
            if (uprights != null && (uprights.joint() == null || uprights.corner() == null)) {
                throw new IllegalArgumentException("Habitat kit " + name + " names a joint and a corner piece for each upright");
            }
        }
        List<KitOutline.Piece> pieces = KitOutline.footprint(square, spec.lone().bandEnd(), false, footprintScale).stream()
              .map(piece -> KitOutline.rounded(piece, spec.cornerRadius(), spec.cornerSegments())).toList();
        // A roof for each layout of its fittings; one bare roof without any.
        List<Map<String, List<double[]>>> roofs = spec.fittings() == null ? List.of(Map.of()) : spec.fittings().of(square);
        List<String> roles = new ArrayList<>();
        roles.add("base0");
        for (int k = 0; k < spec.storeys().size(); k++) { roles.add("floor" + k); }
        for (int k = 0; k < roofs.size(); k++) { roles.add("roof" + k); }
        var levels = GeneratedKit.lods(name, square, modelled, roles, pieces, Map.of(), (role, piece, lod) -> {
            boolean roof = role.startsWith("roof");
            // The deck must be inset along the same walls as its rim. A separate cap would sit under the open deck.
            if (roof && piece.tags().endsWith("-cap")) { return Map.of("", new GeneratedMesh()); }
            double z0 = role.equals("base0") ? 0 : roof ? 2 * LEVEL : LEVEL;
            Map<String, KitOutline.Piece> twins = new LinkedHashMap<>();
            twins.put("", piece);
            // A linkless Angled corner's roof also as it stands behind a 60-degree bend (ConnectedKit.Twin).
            if (roof && !square && piece.direction() != null && !piece.tags().contains("link")) { twins.put("-bend", piece); }
            if (roof) {
                int links = KitOutline.links(piece);
                for (int closed = links; closed > 0; closed = (closed - 1) & links) {
                    twins.put("-closed-" + closed, KitOutline.closed(piece, closed));
                }
            }
            Map<String, GeneratedMesh> result = new LinkedHashMap<>();
            // A vault's corner inside the building keeps its crown, as a bays kit's (ConnectedKit#CROWN).
            if (roof && spec.vault() != null && !square && piece.direction() != null && piece.walls().isEmpty()
                  && Integer.bitCount(KitOutline.links(piece)) == 2) {
                result.put("-crown", BayKit.crown(spec.hall(), piece, lod, z0));
            }
            for (var twin : twins.entrySet()) {
                var outline = twin.getValue();
                Plating plating = square ? new Plating(0, 0, null) : plating(outline, twin.getKey().equals("-bend"));
                Laid laid;
                if (roof) {
                    laid = roof(spec, modelled, outline, z0, lod, roofs.get(Integer.parseInt(role.substring(4))),
                          square, plating, footprintScale);
                } else {
                    Storey storey = role.equals("base0") ? new Storey("lit", 0)
                          : spec.storeys().get(Integer.parseInt(role.substring(5)));
                    laid = storey(spec, piece, z0, role.equals("base0"), lod, square, plating, storey);
                }
                GeneratedMesh mesh = piece.direction() != null
                      ? laid.geometry().clipped(KitOutline.sector(piece.direction())) : laid.geometry();
                for (Pier pier : laid.piers()) {
                    if (roof) {
                        upright(mesh, modelled, lod, spec.tops(), pier, z0, rim(spec) + .6);
                    } else {
                        upright(mesh, modelled, lod, spec.piers(), pier, z0, LEVEL);
                        if (role.equals("base0")) { upright(mesh, modelled, lod, spec.footings(), pier, z0, 0); }
                    }
                }
                result.put(twin.getKey(), mesh);
                // Its aviation lights beside it (GeneratedKit#lights), on the roof's plating or parapet foot.
                if (roof && spec.aviationLights()) {
                    double facet = spec.cornerRadius() * Math.PI / Math.max(1, spec.cornerSegments());
                    GeneratedKit.lights(result, twin.getKey(), outline, facet, lightDeck(spec), lod);
                }
            }
            return result;
        }, spec.aviationLights() ? lod -> lightDeck(spec) : null);
        for (var level : levels) {
            level.buildingPaint.add(spec.materials().get("wall"));
        }
        return levels;
    }

    // ---- Surfaces ------------------------------------------------------------------------------------------------------

    private static float[] vertex(double x, double y, double z, double nx, double ny, double nz, double shade, double u,
          double v) {
        return new float[] { (float) x, (float) y, (float) z, (float) nx, (float) ny, (float) nz, (float) shade,
              (float) shade, (float) shade, 1, (float) u, (float) v };
    }

    /**
     * A vertical face from p to q, facing n; its texture from u0 to u1, one repeat per two storeys up, from v0 at its
     * foot (NaN: by height).
     */
    private static void face(GeneratedMesh geometry, String material, double[] p, double[] q, double[] n, double z0,
          double z1, double u0, double u1, double shade, double v0) {
        double foot = Double.isNaN(v0) ? -z0 / REPEAT_UP : v0, top = foot - (z1 - z0) / REPEAT_UP;
        float[] a0 = vertex(p[0], p[1], z0, n[0], n[1], 0, shade, u0, foot), b0 = vertex(q[0], q[1], z0, n[0], n[1], 0, shade, u1, foot);
        float[] b1 = vertex(q[0], q[1], z1, n[0], n[1], 0, 1, u1, top), a1 = vertex(p[0], p[1], z1, n[0], n[1], 0, 1, u0, top);
        // a0, b0, b1 faces the right of p to q.
        if ((q[1] - p[1]) * n[0] - (q[0] - p[0]) * n[1] > 0) {
            geometry.polygon(material, List.of(a0, b0, b1, a1));
        } else {
            geometry.polygon(material, List.of(b0, a0, a1, b1));
        }
    }

    /**
     * A deck vertex, its texture a hexside's width across and half a hex along hex direction {@code axis} (0, the
     * board's north, keeps the board's axes): a run of hexes that way continues, its neighbours whole repeats along.
     */
    private static float[] deck(double x, double y, double z, double shade, int axis) {
        double ax = MIDPOINTS[axis][0] / H, ay = MIDPOINTS[axis][1] / H;
        return vertex(x, y, z, 0, 0, 1, shade, (x * ay - y * ax) / A, -(x * ax + y * ay) / (H / 2));
    }

    /** An upward polygon, textured along hex direction {@code axis} ({@link #deck}). */
    private static void flat(GeneratedMesh geometry, String material, List<double[]> points, double z, double shade, int axis) {
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            double[] a = points.get(i), b = points.get((i + 1) % points.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        List<float[]> vertices = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double[] point = points.get(area > 0 ? i : points.size() - 1 - i);
            vertices.add(deck(point[0], point[1], z, shade, axis));
        }
        geometry.polygon(material, vertices);
    }

    /**
     * How an Angled piece lays its plating: a half runs along its own hexside's link, else the piece's other link. A
     * piece without links is a hub's side, along the run that passes it (parallel to the chord between its two
     * hexsides); behind a 60-degree bend ({@code bend}: the hex links just the two hexsides opposite it) each half runs
     * along the arm beside it. The lone hex runs north, a band along its link.
     */
    private static Plating plating(KitOutline.Piece piece, boolean bend) {
        String[] states = piece.tags().substring(1).split("-");
        List<Integer> links = new ArrayList<>();
        for (int i = 0; i < states.length; i += 2) {
            if (states[i].equals("link")) { links.add(List.of(KitOutline.DIRECTIONS).indexOf(states[i + 1])); }
        }
        Integer direction = piece.direction();
        if (direction == null) {
            int axis = links.isEmpty() ? 0 : links.getFirst() % 3;
            return new Plating(axis, axis, null);
        }
        int following = (direction + 1) % 6;
        if (bend) { return new Plating((direction + 4) % 3, (direction + 3) % 3, direction); }
        if (links.isEmpty()) {
            int chord = Math.floorMod(direction - 1, 3);
            return new Plating(chord, chord, direction);
        }
        return new Plating((links.contains(direction) ? direction : following) % 3,
              (links.contains(following) ? following : direction) % 3, direction);
    }

    /** {@link #flat}, laid out as {@code plating}: a corner piece's two halves each along its own direction. */
    private static void plate(GeneratedMesh geometry, String material, List<double[]> points, double z, Plating plating,
          double shade) {
        if (plating.first() == plating.second()) {
            flat(geometry, material, points, z, shade, plating.first());
            return;
        }
        double[] corner = KitOutline.hexCorner(plating.direction()), mid = MIDPOINTS[plating.direction()];
        double side = corner[0] * mid[1] - corner[1] * mid[0] > 0 ? 1 : -1;
        int[] axes = { plating.first(), plating.second() };
        double[] signs = { side, -side };
        for (int k = 0; k < 2; k++) {
            var half = new GeneratedMesh();
            flat(half, material, points, z, shade, axes[k]);
            double sign = signs[k];
            geometry.add(half.clipped(List.of((x, y) -> sign * (corner[0] * y - corner[1] * x))));
        }
    }

    /** A pier's piece of {@code uprights} ({@code null}: none) at its foot z0, stretched to {@code height} (0: as modelled). */
    private static void upright(GeneratedMesh geometry, List<ModelData> modelled, int lod, Uprights uprights, Pier pier,
          double z0, double height) {
        if (uprights == null) { return; }
        float nx = (float) pier.n()[0], ny = (float) pier.n()[1];
        // The piece's x along the wall (left to right seen from outside), its -y out of the wall.
        var frame = new Matrix4(new float[] { -ny, nx, 0, 0, -nx, -ny, 0, 0, 0, 0, 1, 0,
              (float) pier.x(), (float) pier.y(), (float) z0, 1 });
        GeneratedKit.piece(geometry, modelled, lod, pier.corner() ? uprights.corner() : uprights.joint(), frame, height);
    }

    // ---- Walls and piers -----------------------------------------------------------------------------------------------

    /** Where a point lies along a facade facing n, from the viewer's left, in wall repeats. */
    private static double facadeU(double[] point, double[] n) {
        return n[0] != 0 ? point[1] * n[0] / REPEAT_Y : -point[0] * n[1] / REPEAT_X;
    }

    /**
     * A facade's texture across it: along the board's axes on a Square wall, so facades continue across hexes; from the
     * wall's start on an Angled one, a repeat per wall.
     */
    private static double[] wallU(KitOutline.Wall wall, boolean square) {
        double[] n = normal(wall.p(), wall.q());
        return square ? new double[] { facadeU(wall.p(), n), facadeU(wall.q(), n) } : new double[] { 0, wall.length() / NOSE };
    }

    /**
     * The pier at a wall's start: a corner pier facing out of a convex corner it turns in the piece, else a joint pier
     * facing out of the wall (a wall's start holds the one pier of every corner and joint).
     */
    private static Pier pierAt(KitOutline.Wall wall, List<KitOutline.Wall> walls) {
        double[] n = normal(wall.p(), wall.q());
        if (wall.start() == KitOutline.End.CORNER) {
            for (var before : walls) {
                if (Math.hypot(before.q()[0] - wall.p()[0], before.q()[1] - wall.p()[1]) >= 1e-6) { continue; }
                double[] m = normal(before.p(), before.q());
                double turn = (before.q()[0] - before.p()[0]) * (wall.q()[1] - wall.p()[1])
                      - (before.q()[1] - before.p()[1]) * (wall.q()[0] - wall.p()[0]);
                if (turn > 0) {
                    double px = n[0] + m[0], py = n[1] + m[1], length = Math.hypot(px, py);
                    return new Pier(wall.p()[0], wall.p()[1], new double[] { px / length, py / length }, true);
                }
                break;
            }
        }
        return new Pier(wall.p()[0], wall.p()[1], n, false);
    }

    /** Whether a wall's start holds a pier: every corner; at the near level, every joint and cut too. */
    private static boolean piered(Spec spec, KitOutline.Wall wall, int lod) {
        return spec.piers() != null
              && (wall.start() == KitOutline.End.CORNER || wall.start() != KitOutline.End.ROUND && lod == 0);
    }

    /** Where a roof's aviation lights stand: on its plating, or at its parapet's foot. */
    private static double lightDeck(Spec spec) { return 2 * LEVEL + (spec.parapet() != null ? 0 : eave(spec)); }

    /** The eave's radius, 0 without one. */
    private static double eave(Spec spec) { return spec.eave() == null ? 0 : spec.eave().radius(); }

    /** How high a roof's edge stands over the roof's foot: its parapet, or its rounded rim. */
    private static double rim(Spec spec) { return spec.parapet() != null ? spec.parapet().height() : eave(spec); }

    /** The ground storey's battered dark steel skirt along a wall: from its foot out up to the wall. */
    private static void skirt(Spec spec, GeneratedMesh geometry, KitOutline.Wall wall, List<KitOutline.Wall> walls, double z0) {
        double[] n = normal(wall.p(), wall.q());
        double[][] ends = KitOutline.offsets(wall, walls);
        double[] p = ends[0], a = ends[1], q = ends[2], b = ends[3];
        double height = spec.skirt().height(), out = spec.skirt().out();
        double[] foot0 = { p[0] + a[0] * out, p[1] + a[1] * out }, foot1 = { q[0] + b[0] * out, q[1] + b[1] * out };
        double slope = Math.hypot(out, height);
        double fx = n[0] * height / slope, fy = n[1] * height / slope, fz = out / slope;
        double along = Math.hypot(q[0] - p[0], q[1] - p[1]) / 12;
        String pier = material(spec, "pier");
        geometry.polygon(pier, List.of(vertex(foot0[0], foot0[1], z0, fx, fy, fz, .8, 0, 0),
              vertex(foot1[0], foot1[1], z0, fx, fy, fz, .8, along, 0),
              vertex(q[0], q[1], z0 + height, fx, fy, fz, 1, along, .25), vertex(p[0], p[1], z0 + height, fx, fy, fz, 1, 0, .25)));
    }

    // ---- Modules -------------------------------------------------------------------------------------------------------

    /**
     * A piece's storey from z0, one level high: an interior floor, facades from the storey's place in the wall texture
     * (lit or dimmed windows, shifted along), base course and footings on the ground.
     */
    private static Laid storey(Spec spec, KitOutline.Piece piece, double z0, boolean ground, int lod, boolean square,
          Plating plating, Storey storey) {
        var geometry = new GeneratedMesh();
        List<Pier> piers = new ArrayList<>();
        for (var polygon : piece.polygons()) {
            for (var part : KitOutline.inset(polygon, piece.walls(), 2)) {
                plate(geometry, material(spec, "concrete") + GeneratedMesh.BUILDING_FLOOR, part, z0 + .05, plating, .8);
            }
        }
        double v0 = storey.windows().equals("dim") ? DIM : LIT;
        for (var wall : piece.walls()) {
            double[] u = wallU(wall, square);
            face(geometry, material(spec, "wall"), wall.p(), wall.q(), normal(wall.p(), wall.q()), z0, z0 + LEVEL,
                  u[0] + storey.shift(), u[1] + storey.shift(), ground ? .72 : 1, v0);
            KitOutline.wallBody(geometry, wall, piece.walls(), z0, z0 + LEVEL, 0, 2);
            if (ground && spec.skirt() != null) {
                var band = new GeneratedMesh();
                skirt(spec, band, wall, piece.walls(), z0);
                geometry.add(mitred(band, wall));
            }
            if (piered(spec, wall, lod)) { piers.add(pierAt(wall, piece.walls())); }
        }
        return new Laid(geometry, piers);
    }

    /**
     * A piece's roof at z0: plating and parapet caps laid out as {@code plating}, a parapet along every wall (its outer
     * top edge rounded by the eave), or without one the eave's rim from the wall onto the plating; the piers' tops, and
     * the fittings of its layout about the hex's middle.
     */
    private static Laid roof(Spec spec, List<ModelData> modelled, KitOutline.Piece piece, double z0, int lod,
          Map<String, List<double[]>> layout, boolean square, Plating plating, float footprintScale) {
        var geometry = new GeneratedMesh();
        List<Pier> piers = new ArrayList<>();
        if (spec.vault() != null) {
            // A hall's roof spans it: no plating, rim, pier tops or fittings.
            BayKit.hall(geometry, spec.hall(), piece, square, z0, lod);
            return new Laid(geometry, piers);
        }
        String roof = material(spec, "roof"), wallMaterial = material(spec, "wall");
        double radius = eave(spec);
        int segments = spec.eave() == null ? 0 : spec.eave().segments(lod);
        // Without a parapet the plating stands on the rim's top, set in to meet it.
        double deck = z0 + (spec.parapet() != null ? 0 : radius);
        for (var polygon : piece.polygons()) {
            var laid = spec.parapet() != null || radius == 0 ? List.of(polygon)
                  : KitOutline.inset(polygon, piece.walls(), ROOF_INSET + radius);
            for (var part : laid) {
                plate(geometry, roof + GeneratedMesh.ROOF_DECK, part, deck, plating, 1);
                geometry.slab(GeneratedMesh.BUILDING_BODY, part, deck - 1.2, deck, false);
            }
        }
        for (var wall : piece.walls()) {
            double[] n = normal(wall.p(), wall.q());
            double[] u = wallU(wall, square);
            // Its outer face a hair inside the wall line (a storey going on above stands in front of it).
            double[][] ends = KitOutline.offsets(wall, piece.walls());
            double[] a = ends[1], b = ends[3];
            double[] p = { ends[0][0] - a[0] * ROOF_INSET, ends[0][1] - a[1] * ROOF_INSET };
            double[] q = { ends[2][0] - b[0] * ROOF_INSET, ends[2][1] - b[1] * ROOF_INSET };
            var band = new GeneratedMesh();
            if (spec.parapet() != null) {
                double height = spec.parapet().height(), width = spec.parapet().width();
                double[] c = { p[0] - a[0] * width, p[1] - a[1] * width }, d = { q[0] - b[0] * width, q[1] - b[1] * width };
                face(band, wallMaterial, p, q, n, z0, z0 + height - radius, u[0], u[1], 1, Double.NaN);
                face(band, wallMaterial, c, d, new double[] { -n[0], -n[1] }, z0, z0 + height, u[0], u[1], 1, Double.NaN);
                double[] in0 = { p[0] - a[0] * radius, p[1] - a[1] * radius }, in1 = { q[0] - b[0] * radius, q[1] - b[1] * radius };
                plate(band, roof, List.of(in0, in1, d, c), z0 + height, plating, 1);
                if (radius > 0) { round(band, wallMaterial, p, q, a, b, n, z0 + height - radius, radius, segments, u); }
            } else if (radius > 0) {
                round(band, wallMaterial, p, q, a, b, n, z0, radius, segments, u);
            }
            geometry.add(mitred(band, wall));
            // Closed link walls span the hexside before the sector cut; place each top only in the piece holding it.
            if (piered(spec, wall, lod) && (piece.direction() == null || KitOutline.inSector(piece.direction(), wall.p()))) {
                piers.add(pierAt(wall, piece.walls()));
            }
        }
        fittings(geometry, modelled, lod, layout, piece, deck, spec.lone().halfLength(), footprintScale);
        return new Laid(geometry, piers);
    }

    /**
     * A roof's fittings of {@code layout} about the hex's middle, on the deck at {@code deck}: they move in with a compact
     * isolated roof (its outline's half length against the lone hex's {@code halfLength}), keeping their own size.
     */
    static void fittings(GeneratedMesh geometry, List<ModelData> modelled, int lod, Map<String, List<double[]>> layout,
          KitOutline.Piece piece, double deck, double halfLength, float footprintScale) {
        boolean isolated = piece.direction() == null && !piece.tags().contains("-link-");
        double across = isolated ? footprintScale : 1;
        double along = isolated
              ? piece.polygons().stream().flatMap(List::stream).mapToDouble(p -> Math.abs(p[1])).max().orElse(0) / halfLength : 1;
        layout.forEach((name, places) -> places.forEach(at -> GeneratedKit.piece(geometry, modelled, lod, name,
              on(at, deck, across, along).rotate(0, 0, 1, at.length > 2 ? (float) at[2] : 0), 0)));
    }

    /**
     * A rounded edge along a wall: a quarter round of {@code radius} from the band p-q (its ends' outward directions a
     * and b) at {@code foot}, up and in to its top, in {@code segments} facets, the facade's texture running on over it.
     */
    private static void round(GeneratedMesh geometry, String material, double[] p, double[] q, double[] a, double[] b,
          double[] n, double foot, double radius, int segments, double[] u) {
        float[] previousP = null, previousQ = null;
        boolean right = (q[1] - p[1]) * n[0] - (q[0] - p[0]) * n[1] > 0;
        for (int k = 0; k <= segments; k++) {
            double angle = Math.PI / 2 * k / segments, in = radius * (1 - Math.cos(angle));
            double z = foot + radius * Math.sin(angle), v = -foot / REPEAT_UP - radius * angle / REPEAT_UP;
            double nx = n[0] * Math.cos(angle), ny = n[1] * Math.cos(angle), nz = Math.sin(angle);
            float[] atP = vertex(p[0] - a[0] * in, p[1] - a[1] * in, z, nx, ny, nz, 1, u[0], v);
            float[] atQ = vertex(q[0] - b[0] * in, q[1] - b[1] * in, z, nx, ny, nz, 1, u[1], v);
            if (previousP != null) {
                geometry.polygon(material, right ? List.of(previousP, previousQ, atQ, atP)
                      : List.of(previousQ, previousP, atP, atQ));
            }
            previousP = atP;
            previousQ = atQ;
        }
    }

    /** A band along {@code wall} (skirt, parapet, eave), mitred where the wall turns to another wall of its piece. */
    private static GeneratedMesh mitred(GeneratedMesh band, KitOutline.Wall wall) {
        return wall.keeps().isEmpty() ? band : band.clipped(wall.keeps());
    }

    /** A fitting's place on the roof at (x, y). */
    private static Matrix4 on(double[] at, double deck, double across, double along) {
        return new Matrix4().setToTranslation((float) (at[0] * across), (float) (at[1] * along), (float) deck);
    }

    private static String material(Spec spec, String role) {
        String board = spec.materials().get(role);
        if (board == null) { throw new IllegalArgumentException("Habitat kit has no " + role + " material"); }
        return board;
    }
}
