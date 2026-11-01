/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix4;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;

import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.A;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.EXTEND;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.H;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.LEVEL;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.MIDPOINTS;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.NOSE;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.ROOF_INSET;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.normal;

/**
 * A habitat building kit generated from its JSON spec ({@code models/buildings/<kit>/<kit>.json}: its named variants,
 * each a {@link Spec}, the first the default) in place of a GLB: the lunar habitat's corner and whole-hex pieces ({@link KitOutline}) in its Angled or Square footprint, every
 * module (base, the storeys the runtime picks from per hex and level, the roofs) at two levels of detail, as connected
 * kit nodes ({@link ConnectedKit}), so drawing, picking, interiors and cutaways read it as any kit. A facade along every
 * wall, dark piers at its corners and joints; on the ground a battered skirt and footings, on top a parapet over plating
 * and the roof's fittings. Piers, footings and fittings are modelled pieces from the kit's pieces GLB.
 * Square facades and plating keep the board's texture axes; an Angled piece lays its plating along its building, mitred
 * where arms meet ({@code -bend} roofs behind a 60-degree bend).
 */
final class HabitatKit {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, BoardKit<ConnectedKit.Kit>> KITS = new ConcurrentHashMap<>();
    /** Wall texture: two storeys a repeat up the wall; across, a third of a hex column (x facades), a quarter hex (y). */
    private static final double REPEAT_UP = 2 * LEVEL, REPEAT_X = A, REPEAT_Y = H / 2;
    /** Where a storey's foot lies in the wall texture: its upper storey's windows are lit, its lower storey's dimmed. */
    private static final double LIT = -.5, DIM = 0;
    private static final int LODS = 2;

    private HabitatKit() { }

    /**
     * A habitat kit's look, as data: its variant's {@code name}; board materials by role ({@code wall}, {@code roof}, {@code pier}, {@code concrete});
     * the GLB of its modelled pieces beside the spec ({@code pieces}); convex corners rounded to {@code cornerRadius} in
     * {@code cornerSegments} arcs; the parapet ({@code null}: none); the roof's rounded edge ({@code eave}: the parapet's
     * outer top edge, or without one a rim curving from the wall onto the roof; {@code null}: square); the ground storey's
     * battered skirt; the pieces at the walls' corners and joints: piers up every storey, their tops on the roof, footings
     * on the ground ({@code null}: none); the Angled lone hex's half length and a single link's band end; the storeys
     * above the ground ({@code windows} lit or dim, shifted along by a fraction of a repeat); the roofs' fittings per
     * footprint (hatch and vent pieces, laid out at x, y).
     */
    record Spec(String name, Map<String, String> materials, String pieces, double cornerRadius, int cornerSegments, Parapet parapet,
                Eave eave, Skirt skirt, Uprights piers, Uprights tops, Uprights footings, Lone lone, List<Storey> storeys,
                Fittings fittings) { }

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
    record Fittings(Fitting square, Fitting angled) { }

    /** A footprint's roof fittings: its hatch and vent pieces (each on the roof at its centre) and their layouts. */
    record Fitting(String hatch, String vent, List<Layout> layouts) { }
    record Layout(List<double[]> hatches, List<double[]> vents) { }

    /** A pier at a wall's start: where, facing which way, whether at a corner. */
    private record Pier(double x, double y, double[] n, boolean corner) { }

    /** How a piece lays its plating: the hex direction its halves run along, split on the diagonal to its corner. */
    private record Plating(int first, int second, Integer direction) { }

    /** One module's geometry for a piece, before the piece's cut, and the piers it stands. */
    private record Laid(GeneratedMesh geometry, List<Pier> piers) { }

    /**
     * The kit of {@code spec} (a JSON file) in its Square footprint or Angled, in the variant named {@code variant} (null,
     * or a name the spec does not list: its first); it and its pieces GLB read once, until assets reload.
     */
    static ConnectedKit.Kit kit(File spec, boolean square, String variant) {
        String key = spec.getAbsolutePath() + (square ? "#square" : "") + "@" + variant;
        return KITS.computeIfAbsent(key, ignored -> new BoardKit<>(() -> {
            Spec read;
            try {
                List<Spec> variants = List.of(JSON.readValue(spec, Spec[].class));
                if (variants.size() > 1 && variants.stream().map(Spec::name).filter(java.util.Objects::nonNull).distinct()
                      .count() < variants.size()) {
                    throw new IllegalArgumentException("its variants need distinct names");
                }
                // A building stores its variant's name in its appearance, as a valid appearance key.
                variants.stream().map(Spec::name).filter(java.util.Objects::nonNull)
                      .forEach(megamek.common.board.HexAppearance::ofKitVariant);
                read = variants.stream().filter(listed -> listed.name() != null && listed.name().equals(variant)).findFirst()
                      .orElse(variants.getFirst());
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("Cannot read habitat kit " + spec + ": " + failure.getMessage(), failure);
            }
            var modelled = RigidGlb.loadLods(new FileHandle(new File(spec.getParentFile(), read.pieces())),
                  new File(Configuration.dataDir(), "models").toPath());
            String name = spec.getName().replaceFirst("\\.json$", "");
            return ConnectedKit.load(List.copyOf(lods(read, modelled, square, name)));
        })).get();
    }

    static void reload() { KITS.clear(); }

    /**
     * The kit's levels of detail: every piece of every module, named as a kit GLB's nodes; {@code modelled}, the pieces
     * GLB's levels.
     */
    static List<RigidGlb.Data> lods(Spec spec, List<ModelData> modelled, boolean square, String name) {
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
        String suffix = square ? "-square" : "";
        List<double[]> lone = List.of(new double[] { -A, -spec.lone().halfLength() }, new double[] { A, -spec.lone().halfLength() },
              new double[] { A, spec.lone().halfLength() }, new double[] { -A, spec.lone().halfLength() });
        List<KitOutline.Piece> pieces = KitOutline.footprint(square, lone, spec.lone().bandEnd()).stream()
              .map(piece -> KitOutline.rounded(piece, spec.cornerRadius(), spec.cornerSegments())).toList();
        Fitting fitting = square ? spec.fittings().square() : spec.fittings().angled();
        List<Layout> roofs = fitting.layouts();
        List<RigidGlb.Data> result = new ArrayList<>();
        for (int lod = 0; lod < LODS; lod++) {
            Map<String, GeneratedMesh> nodes = new LinkedHashMap<>();
            List<String> roles = new ArrayList<>();
            roles.add("base0");
            for (int k = 0; k < spec.storeys().size(); k++) { roles.add("floor" + k); }
            for (int k = 0; k < roofs.size(); k++) { roles.add("roof" + k); }
            for (String role : roles) {
                boolean roof = role.startsWith("roof");
                double z0 = role.equals("base0") ? 0 : roof ? 2 * LEVEL : LEVEL;
                for (var piece : pieces) {
                    List<String> twins = new ArrayList<>(List.of(""));
                    // A linkless Angled corner's roof also as it stands behind a 60-degree bend (ConnectedKit.Twin).
                    if (roof && !square && piece.direction() != null && !piece.tags().contains("link")) { twins.add("-bend"); }
                    for (String twin : twins) {
                        Plating plating = square ? new Plating(0, 0, null) : plating(piece, twin.equals("-bend"));
                        Laid laid;
                        if (roof) {
                            laid = roof(spec, modelled, piece, z0, lod, fitting, roofs.get(Integer.parseInt(role.substring(4))),
                                  square, plating);
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
                        String node = name + suffix + "-lod" + lod + "-" + role + piece.tags() + twin;
                        if (mesh.parts.isEmpty()) { throw new IllegalStateException("Empty piece " + node); }
                        nodes.put(node, mesh);
                    }
                }
            }
            result.add(GeneratedMesh.kit(name + suffix + "-lod" + lod, nodes));
        }
        return result;
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

    /** A modelled piece of the kit's pieces GLB at {@code lod}, {@link GeneratedMesh#piece}. */
    private static void piece(GeneratedMesh geometry, List<ModelData> modelled, int lod, String name, Matrix4 frame,
          double height) {
        // A level the GLB lacks repeats the one before it, under that level's names (MeshLod#load).
        var level = modelled.get(Math.min(lod, modelled.size() - 1));
        geometry.piece(level, name + "-lod" + MeshLod.level(level.id), frame, (float) height);
    }

    /** A pier's piece of {@code uprights} ({@code null}: none) at its foot z0, stretched to {@code height} (0: as modelled). */
    private static void upright(GeneratedMesh geometry, List<ModelData> modelled, int lod, Uprights uprights, Pier pier,
          double z0, double height) {
        if (uprights == null) { return; }
        float nx = (float) pier.n()[0], ny = (float) pier.n()[1];
        // The piece's x along the wall (left to right seen from outside), its -y out of the wall.
        var frame = new Matrix4(new float[] { -ny, nx, 0, 0, -nx, -ny, 0, 0, 0, 0, 1, 0,
              (float) pier.x(), (float) pier.y(), (float) z0, 1 });
        piece(geometry, modelled, lod, pier.corner() ? uprights.corner() : uprights.joint(), frame, height);
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

    /** The eave's radius, 0 without one. */
    private static double eave(Spec spec) { return spec.eave() == null ? 0 : spec.eave().radius(); }

    /** How high a roof's edge stands over the roof's foot: its parapet, or its rounded rim. */
    private static double rim(Spec spec) { return spec.parapet() != null ? spec.parapet().height() : eave(spec); }

    /**
     * A wall's ends for a band along it (skirt, parapet): each end point and its outward direction there, joined with
     * the next round wall's (scaled so the bands meet), moved on past a cut or corner end, which the piece's cut or the
     * wall's mitre trims.
     */
    private static double[][] offsets(KitOutline.Wall wall, List<KitOutline.Wall> walls) {
        double[] n = normal(wall.p(), wall.q()), u = KitOutline.direction(wall);
        double[][] result = new double[4][];
        for (int k = 0; k < 2; k++) {
            double[] point = k == 0 ? wall.p() : wall.q();
            KitOutline.End kind = k == 0 ? wall.start() : wall.end();
            double sign = k == 0 ? -1 : 1;
            KitOutline.Wall other = null;
            if (kind == KitOutline.End.ROUND) {
                for (var candidate : walls) {
                    double[] meets = k == 0 ? candidate.q() : candidate.p();
                    if (candidate != wall && Math.hypot(meets[0] - point[0], meets[1] - point[1]) < 1e-6) {
                        other = candidate;
                        break;
                    }
                }
            }
            if (other != null) {
                double[] m = normal(other.p(), other.q());
                double[] half = { n[0] + m[0], n[1] + m[1] };
                double scale = 2 / (half[0] * half[0] + half[1] * half[1]);
                result[2 * k] = point;
                result[2 * k + 1] = new double[] { half[0] * scale, half[1] * scale };
            } else if (kind == KitOutline.End.CUT || kind == KitOutline.End.CORNER) {
                result[2 * k] = new double[] { point[0] + u[0] * sign * EXTEND, point[1] + u[1] * sign * EXTEND };
                result[2 * k + 1] = n;
            } else {
                result[2 * k] = point;
                result[2 * k + 1] = n;
            }
        }
        return result;
    }

    /** The ground storey's battered dark steel skirt along a wall: from its foot out up to the wall. */
    private static void skirt(Spec spec, GeneratedMesh geometry, KitOutline.Wall wall, List<KitOutline.Wall> walls, double z0) {
        double[] n = normal(wall.p(), wall.q());
        double[][] ends = offsets(wall, walls);
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
        for (var polygon : piece.polygons()) { plate(geometry, material(spec, "concrete"), polygon, z0 + .05, plating, .8); }
        double v0 = storey.windows().equals("dim") ? DIM : LIT;
        for (var wall : piece.walls()) {
            double[] u = wallU(wall, square);
            face(geometry, material(spec, "wall"), wall.p(), wall.q(), normal(wall.p(), wall.q()), z0, z0 + LEVEL,
                  u[0] + storey.shift(), u[1] + storey.shift(), ground ? .72 : 1, v0);
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
    private static Laid roof(Spec spec, List<ModelData> modelled, KitOutline.Piece piece, double z0, int lod, Fitting fitting,
          Layout layout, boolean square, Plating plating) {
        var geometry = new GeneratedMesh();
        List<Pier> piers = new ArrayList<>();
        String roof = material(spec, "roof"), wallMaterial = material(spec, "wall");
        double radius = eave(spec);
        int segments = spec.eave() == null ? 0 : spec.eave().segments(lod);
        // Without a parapet the plating stands on the rim's top, set in to meet it.
        double deck = spec.parapet() != null ? z0 : z0 + radius;
        for (var polygon : piece.polygons()) {
            var laid = spec.parapet() != null || radius == 0 ? List.of(polygon)
                  : KitOutline.inset(polygon, piece.walls(), ROOF_INSET + radius);
            for (var part : laid) { plate(geometry, roof, part, deck, plating, 1); }
        }
        for (var wall : piece.walls()) {
            double[] n = normal(wall.p(), wall.q());
            double[] u = wallU(wall, square);
            // Its outer face a hair inside the wall line (a storey going on above stands in front of it).
            double[][] ends = offsets(wall, piece.walls());
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
            if (piered(spec, wall, lod)) { piers.add(pierAt(wall, piece.walls())); }
        }
        for (double[] at : layout.hatches()) { piece(geometry, modelled, lod, fitting.hatch(), on(at, deck), 0); }
        for (double[] at : layout.vents()) { piece(geometry, modelled, lod, fitting.vent(), on(at, deck), 0); }
        return new Laid(geometry, piers);
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
    private static Matrix4 on(double[] at, double deck) {
        return new Matrix4().setToTranslation((float) at[0], (float) at[1], (float) deck);
    }

    private static String material(Spec spec, String role) {
        String board = spec.materials().get(role);
        if (board == null) { throw new IllegalArgumentException("Habitat kit has no " + role + " material"); }
        return board;
    }
}
