/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.EXTEND;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.H;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.R;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.ROOF_INSET;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.normal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix4;

/**
 * A generated kit laid from bays ({@link GeneratedKit}, style "bays", a variant's {@link Spec}): the light building's and
 * the light fortress's. Each module lays a run of modelled pieces along every wall of a footprint piece ({@link Run}),
 * goes on past an end where the wall goes on (the next piece's cut or the wall's mitre trims it), and lays its floor or
 * roof slab and the ground storey's sidewalk on the board's axes, so they run on across corner pieces and hexes.
 */
final class BayKit {
    private BayKit() { }

    /**
     * A kit laid from bays, as data: its variant's {@code name} and {@code style} (bays); the GLB of its modelled pieces
     * beside the spec ({@code pieces}); the Angled lone hex's outline from its front ({@code lone}) and a single link's
     * band end; the roof slab and the storeys' floors ({@link Slab}); the ground storey's sidewalk ({@code null}: none);
     * and by role (base, floor and roof modules, in the order the runtime picks from) the module's runs, one per level of
     * detail (the last for any further).
     */
    record Spec(String name, String style, String pieces, List<double[]> lone, double bandEnd, Slab roof, Slab floors,
                Sidewalk sidewalk, Map<String, List<Run>> modules) { }

    /**
     * A flat surface over the piece: a board material, {@code repeat} units a repeat on the board's axes, at a height per
     * level of detail (the last for any further), kept {@code inset} off the walls.
     */
    record Slab(String material, double repeat, double inset, double[] heights) {
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
     * texture runs on across the pieces. All but bays and bay are optional.
     */
    record Run(List<String> bays, double bay, double margin, String door, Stretch ends, Stretch under,
               Map<String, Double> planar) { }

    /** A piece laid at {@code start} along the wall, stretched by {@code scale}. */
    private record Placed(double start, String piece, double scale) { }

    /** The kit's levels of detail ({@link GeneratedKit#lods}); {@code modelled}, the pieces GLB's levels. */
    static List<RigidGlb.Data> lods(Spec spec, List<ModelData> modelled, boolean square, String name) {
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
                  || run.ends() != null && !(run.ends().length() > 0) || run.under() != null && !(run.under().length() > 0)) {
                throw new IllegalArgumentException("Bay kit " + name + " lays a piece without its length");
            }
        });
        // Slabs and sidewalks under a material of their own: a piece of the same board material keeps its UVs.
        Map<String, Float> boardUVs = new HashMap<>();
        for (Slab slab : new Slab[] { spec.roof(), spec.floors() }) { boardUVs.put(board(slab.material()), (float) slab.repeat()); }
        if (spec.sidewalk() != null) { boardUVs.put(board(spec.sidewalk().material()), (float) spec.sidewalk().repeat()); }
        return GeneratedKit.lods(name, square, modelled, List.copyOf(spec.modules().keySet()),
              KitOutline.footprint(square, spec.lone(), spec.bandEnd(), true), boardUVs,
              (role, piece, lod) -> Map.of("", module(spec, modelled, role, piece, lod)));
    }

    /** One piece of a module: its walls' runs (and sidewalk), its floor or roof slab, cut to its corner. */
    private static GeneratedMesh module(Spec spec, List<ModelData> modelled, String role, KitOutline.Piece piece, int lod) {
        boolean ground = role.startsWith("base"), top = role.startsWith("roof");
        List<Run> runs = spec.modules().get(role);
        Run run = runs.get(Math.min(lod, runs.size() - 1));
        var result = new GeneratedMesh();
        for (var wall : piece.walls()) {
            double[] goesOn = facade(result, run, wall, modelled, lod, top ? ROOF_INSET : 0, ground);
            if (ground && spec.sidewalk() != null) { sidewalk(result, spec.sidewalk(), wall, goesOn[0], goesOn[1]); }
        }
        Slab slab = top ? spec.roof() : spec.floors();
        for (var polygon : piece.polygons()) {
            for (var part : slab.inset() > 0 ? KitOutline.inset(polygon, piece.walls(), slab.inset()) : List.of(polygon)) {
                flat(result, board(slab.material()), part, slab.height(lod), slab.repeat());
            }
        }
        return piece.direction() != null ? result.clipped(KitOutline.sector(piece.direction())) : result;
    }

    /**
     * Lays {@code run} along {@code wall}, {@code inset} inside its line: a cut or corner end goes on {@link
     * KitOutline#EXTEND} past (the piece's cut or the wall's mitre trims it), an open or joint end stops square. Returns
     * how far it goes on before and after the wall.
     */
    private static double[] facade(GeneratedMesh result, Run run, KitOutline.Wall wall, List<ModelData> modelled, int lod,
          double inset, boolean ground) {
        double length = wall.length();
        double[] p = wall.p(), u = KitOutline.direction(wall), n = normal(wall.p(), wall.q());
        double before = goesOn(wall.start()) ? EXTEND : 0, after = goesOn(wall.end()) ? EXTEND : 0;
        var laid = new GeneratedMesh();
        for (Placed placed : layout(run, wall, length, before, after, ground)) {
            float scale = (float) placed.scale();
            float x = (float) (p[0] + u[0] * placed.start() - n[0] * inset), y = (float) (p[1] + u[1] * placed.start() - n[1] * inset);
            // A piece's x along the wall, stretched; its -y out of the wall.
            var frame = new Matrix4(new float[] { (float) u[0] * scale, (float) u[1] * scale, 0, 0, (float) -n[0], (float) -n[1], 0, 0,
                  0, 0, 1, 0, x, y, 0, 1 });
            GeneratedKit.piece(laid, modelled, lod, placed.piece(), frame, 0);
        }
        if (run.planar() != null) { run.planar().forEach((material, repeat) -> planar(laid, material, wall, repeat)); }
        List<GeneratedMesh.Keep> keeps = new ArrayList<>(wall.keeps());
        keeps.add((x, y) -> (x - p[0]) * u[0] + (y - p[1]) * u[1] + before);
        keeps.add((x, y) -> length + after - (x - p[0]) * u[0] - (y - p[1]) * u[1]);
        result.add(laid.clipped(keeps));
        return new double[] { before, after };
    }

    /** A board material's part textured on the board's axes ({@link GeneratedMesh#BOARD_UV}). */
    private static String board(String material) { return material + GeneratedMesh.BOARD_UV; }

    private static boolean goesOn(KitOutline.End end) { return end == KitOutline.End.CUT || end == KitOutline.End.CORNER; }

    /** The pieces {@code run} lays along a wall {@code length} long, going on {@code before} and {@code after} it. */
    private static List<Placed> layout(Run run, KitOutline.Wall wall, double length, double before, double after,
          boolean ground) {
        List<Placed> result = new ArrayList<>();
        if (run.under() != null && length + before + after > 1e-6) {
            result.add(new Placed(-before, run.under().piece(), (length + before + after) / run.under().length()));
        }
        if (run.ends() != null) {
            stretches(result, run.ends(), -before, run.margin());
            stretches(result, run.ends(), length - run.margin(), length + after);
        }
        double usable = length - 2 * run.margin();
        // A wall no longer than its margins holds no bay.
        if (run.bays() == null || run.bays().isEmpty() || usable <= 1e-6) { return result; }
        int count = Math.max(1, (int) Math.rint(usable / run.bay()));
        double scale = usable / (count * run.bay());
        Set<Integer> doors = ground && run.door() != null ? doors(wall, count) : Set.of();
        // A wall's bays are chosen by where it stands, the same in every piece that holds it.
        double[] p = wall.p(), q = wall.q();
        long seed = Math.floorMod((long) Math.rint((p[0] + q[0]) / 2 * 7 + (p[1] + q[1]) / 2 * 13), 997);
        java.util.function.IntFunction<String> pick = index -> run.bays().get((int) Math.floorMod(seed + index * 5L,
              (long) run.bays().size()));
        for (int index = 0; index < count; index++) {
            result.add(new Placed(run.margin() + index * run.bay() * scale, doors.contains(index) ? run.door() : pick.apply(index),
                  scale));
        }
        if (run.margin() == 0) {
            if (before > 0) { result.add(new Placed(-run.bay() * scale, pick.apply(-1), scale)); }
            if (after > 0) { result.add(new Placed(length, pick.apply(count), scale)); }
        }
        return result;
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
        var part = laid.parts.get(material);
        if (part == null) { return; }
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
     * in one plane), going on with the wall's facade; kept in the hex and mitred.
     */
    private static void sidewalk(GeneratedMesh result, Sidewalk walk, KitOutline.Wall wall, double before, double after) {
        double[] n = normal(wall.p(), wall.q()), u = KitOutline.direction(wall);
        double[] a = { wall.p()[0] - u[0] * before, wall.p()[1] - u[1] * before };
        double[] b = { wall.q()[0] + u[0] * after, wall.q()[1] + u[1] * after };
        var strip = new GeneratedMesh();
        flat(strip, board(walk.material()), List.of(a, b, new double[] { b[0] + n[0] * walk.width(), b[1] + n[1] * walk.width() },
              new double[] { a[0] + n[0] * walk.width(), a[1] + n[1] * walk.width() }),
              walk.kerb() + (Math.abs(n[0]) > .5 ? .02 : 0), walk.repeat());
        List<GeneratedMesh.Keep> keeps = new ArrayList<>(wall.keeps());
        keeps.addFirst(KitOutline::insideHex);
        result.add(strip.clipped(keeps));
    }

    /**
     * An upward polygon on the board's texture axes: about {@code repeat} units a repeat, whole repeats per hex column
     * across and per half hex up, so it continues across every hexside.
     */
    private static void flat(GeneratedMesh result, String material, List<double[]> polygon, double z, double repeat) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            double[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        double across = Math.max(1, Math.rint(1.5 * R / repeat)), up = Math.max(1, Math.rint(H / repeat));
        List<float[]> vertices = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            double[] point = polygon.get(area > 0 ? i : polygon.size() - 1 - i);
            vertices.add(new float[] { (float) point[0], (float) point[1], (float) z, 0, 0, 1, 1, 1, 1, 1,
                  (float) (point[0] * across / (1.5 * R)), (float) (-point[1] * up / H) });
        }
        result.polygon(material, vertices);
    }
}
