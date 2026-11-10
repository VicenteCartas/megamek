/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.H;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.LEVEL;
import static megamek.client.ui.clientGUI.boardview.gpu.KitOutline.MIDPOINTS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix4;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;

/**
 * A generated kit's large doors, as a hangar has them ({@link HexAppearance#doors}, models/buildings/README.md): each
 * stands in the wall of its hex's footprint towards its hexside, as high as its levels less a lintel. Where a hex has
 * doors its kit pieces are merged into a mesh of its own, cut open over each door ({@link #model}), and the door is
 * generated there from the kit variant's {@link Style}: a frame (jambs where the door ends, a header across), a leaf of
 * horizontal panels set back in it, and a threshold. Linked doors ({@link HexAppearance#doorLinks}) side by side along
 * a straight wall, in one hex or two, are one wide door: jambs stand only at its ends, each hex's part a leaf of its
 * own. Doors that are not linked, and linked ones that meet at a bend of the wall, each keep their jambs.
 */
final class KitDoors {
    private static final float[] WHITE = { 1, 1, 1 }, SEAM = { .16f, .16f, .17f };
    /** How far out of the wall line a door's opening clears what stands there: canopies, planters, sidewalks. */
    private static final double CLEAR = 9;
    /** A wall's remainder beside a door's part of it, no longer than this, belongs to the door. */
    private static final double SHORT = 12.5;
    /** How near two doors' ends lie to be one door's: the board's hex is regular only to a 32nd. */
    private static final double TOUCH = .25;

    private KitDoors() { }

    /**
     * A door surface: a board material, {@code repeat} units a repeat along and up the door (12 without), a linear
     * {@code tint} (white without); with {@code paint} it takes the building's tint, with {@code glow} it lights up at
     * night in that linear colour.
     */
    record Part(String material, double repeat, float[] tint, boolean paint, float[] glow) {
        String id() { return material + "#door" + (paint ? "-paint" : "") + (glow != null ? "-lit" : ""); }

        float[] colour() { return tint == null ? WHITE : tint; }

        double units() { return repeat > 0 ? repeat : 12; }
    }

    /** A row of windows in each leaf: panes {@code width} by {@code height}, their sills at {@code sill}, one per {@code pitch}. */
    record Windows(Part glass, double width, double height, double sill, double pitch) { }

    /**
     * Modelled pieces of the kit's pieces GLB that stand in for the generated frame (x along the wall from the piece's
     * start, its face towards -y, z up): a {@code jamb} at a door's start, stretched to the opening's height and
     * mirrored at its end; a {@code header} laid across in stretches of {@code headerLength}, standing on the leaf's
     * top; a {@code crown} on the header over each leaf's middle. Each is optional.
     */
    record Pieces(String jamb, String header, double headerLength, String crown) { }

    /**
     * A kit variant's door ({@code door} in its spec; every value optional): the {@code leaf} and {@code frame}
     * surfaces; the frame's {@code jamb} width, {@code header} height, how far it stands {@code out} of the wall line
     * and its {@code depth} into the building (the wall's thickness, which the opening clears); the leaf's
     * {@code recess} behind the wall line; the {@code lintel} of wall left over the door under its top level's line;
     * the {@code margin} kept to a wall's corner; the leaf's {@code panel} height (grooves between; 0: plain) and the
     * width of its vertical {@code section}s (joints in the {@code seam} surface between; 0: none); a {@code stripe}
     * along its foot, {@code stripeHeight} high; its {@link Windows}; a {@code threshold} plate reaching {@code apron}
     * out of the wall line; and modelled {@link Pieces}.
     */
    record Style(Part leaf, Part frame, double jamb, double header, double out, double depth, double recess, double lintel,
                 double margin, double panel, double section, Part seam, Part stripe, double stripeHeight, Windows windows,
                 Part threshold, double apron, Pieces pieces) {
        /** A plain steel door, for a kit whose spec gives none. */
        static final Style DEFAULT = new Style(null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, null, null, 0, null).resolved();

        /** This style with the default for every value its spec left out. */
        Style resolved() {
            return new Style(leaf != null ? leaf : new Part("structures/common/corrugated-painted", 12, new float[] { .55f, .57f, .58f }, false, null),
                  frame != null ? frame : new Part("structures/common/painted-steel", 12, new float[] { .32f, .33f, .34f }, false, null),
                  jamb > 0 ? jamb : 2, header > 0 ? header : 2.4, out > 0 ? out : .8, depth > 0 ? depth : 3.4,
                  recess > 0 ? recess : .8, lintel > 0 ? lintel : 2, margin > 0 ? margin : 1.5, panel, section,
                  seam != null ? seam : new Part(frame != null ? frame.material() : "structures/common/painted-steel", 12, SEAM, false, null),
                  stripe, stripeHeight > 0 ? stripeHeight : 2, windows, threshold, apron > 0 ? apron : 5, pieces);
        }
    }

    /**
     * What a generated kit's doors need of it: its footprint ({@link KitOutline#footprint}), the rounding of its convex
     * corners, its variant's door style and its pieces GLB's levels.
     */
    record Frame(boolean square, double bandEnd, float scale, double cornerRadius, int cornerSegments, Style style,
          List<ModelData> modelled) { }

    /**
     * A door's part of a hex's wall, from p to q with the building on its left; its link {@code group} (0: none);
     * whether each end is a corner of the wall.
     */
    record Slice(double[] p, double[] q, int levels, int group, boolean cornerStart, boolean cornerEnd) {
        double length() { return Math.hypot(q[0] - p[0], q[1] - p[1]); }
    }

    /**
     * One door of a hex, in its kit's frame (x east, y north): along its wall from p to q, the building on its left,
     * {@code levels} high; whether the same door goes on {@code before} its start or {@code after} its end, in this hex
     * or the next; whether each end is a corner of the wall.
     */
    record Cut(float px, float py, float qx, float qy, int levels, boolean before, boolean after, boolean cornerStart,
          boolean cornerEnd) { }

    /**
     * The doors of {@code tile}'s building {@code feature}: none unless its appearance stores some and its kit is a
     * generated one. A door on a hexside the building continues across is not drawn; one higher than the hex is as high
     * as it.
     */
    static List<Cut> cuts(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        if (!any(HexAppearance.doors(tile.appearance()))) { return List.of(); }
        Map<Coords, List<Slice>> known = new HashMap<>();
        List<Cut> result = new ArrayList<>();
        for (Slice slice : slices(scene, tile, feature, known)) {
            result.add(new Cut((float) slice.p()[0], (float) slice.p()[1], (float) slice.q()[0], (float) slice.q()[1], slice.levels(),
                  goesOn(scene, tile, feature, slice, false, known), goesOn(scene, tile, feature, slice, true, known),
                  slice.cornerStart(), slice.cornerEnd()));
        }
        return List.copyOf(result);
    }

    private static boolean any(int[] heights) {
        for (int height : heights) { if (height > 0) { return true; } }
        return false;
    }

    /** Whether a door linked to {@code slice} goes straight on from its start or end: in its own hex, or the next. */
    private static boolean goesOn(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, Slice slice,
          boolean forward, Map<Coords, List<Slice>> known) {
        double[] at = forward ? slice.q() : slice.p(), u = direction(slice);
        if (meets(slices(scene, tile, feature, known), slice, at, u, forward, 0, 0)) { return true; }
        for (int side = 0; side < 6; side++) {
            double[] middle = MIDPOINTS[side];
            if ((at[0] * middle[0] + at[1] * middle[1]) / Math.hypot(middle[0], middle[1]) < H - TOUCH) { continue; }
            var neighbor = scene.neighbor(tile, side);
            var other = neighbor == null ? null : building(neighbor);
            // One door needs one wall: the same kit in the same footprint.
            if (other == null || !other.asset().equals(feature.asset()) || other.footprintScale() != feature.footprintScale()) { continue; }
            if (meets(slices(scene, neighbor, other, known), slice, at, u, forward, 2 * middle[0], 2 * middle[1])) { return true; }
        }
        return false;
    }

    private static boolean meets(List<Slice> others, Slice slice, double[] at, double[] u, boolean forward, double dx, double dy) {
        for (Slice other : others) {
            double[] end = forward ? other.p() : other.q(), v = direction(other);
            if (other != slice && slice.group() > 0 && other.group() == slice.group() && other.levels() == slice.levels()
                  && u[0] * v[0] + u[1] * v[1] > .999
                  && Math.hypot(end[0] + dx - at[0], end[1] + dy - at[1]) < TOUCH) { return true; }
        }
        return false;
    }

    private static double[] direction(Slice slice) {
        double length = slice.length();
        return new double[] { (slice.q()[0] - slice.p()[0]) / length, (slice.q()[1] - slice.p()[1]) / length };
    }

    /** A hex's kit building, or null: no placed object, wall or structure of another kind. */
    static BoardScene.Feature building(BoardScene.Tile tile) {
        for (var feature : tile.features()) {
            if (feature.kind() == BoardScene.FeatureKind.BUILDING && feature.decoration() == null) { return feature; }
        }
        return null;
    }

    private static List<Slice> slices(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature,
          Map<Coords, List<Slice>> known) {
        return known.computeIfAbsent(tile.coords(), ignored -> {
            int[] heights = HexAppearance.doors(tile.appearance());
            Frame frame = any(heights) ? frame(feature) : null;
            if (frame == null) { return List.of(); }
            int storeys = Math.max(1, Math.round(feature.height())), mask = 0;
            int[] reach = ConnectedKit.reach(scene, tile, storeys);
            for (int side = 0; side < 6; side++) { if (reach[side] > 0) { mask |= 1 << side; } }
            return slices(frame, mask, heights, HexAppearance.doorLinks(tile.appearance()), storeys);
        });
    }

    /** The doors' frame of a building feature's kit; null for one that is not generated, or cannot be read. */
    private static Frame frame(BoardScene.Feature feature) {
        String asset = feature.asset();
        if (!asset.startsWith("buildings/") || BoardWall.supports(asset)
              || !megamek.common.board.TilesetScenery.generatedKit(asset)) { return null; }
        try {
            return GpuBuilding.kit(asset, feature.footprintScale()).doors();
        } catch (RuntimeException unreadable) {
            // GpuAssets#building reports the kit; a hex without it has no doors.
            return null;
        }
    }

    /**
     * The door of each hexside with a height in {@code heights} that {@code mask} (the hex's connections) leaves a wall
     * on: the longest straight stretch of the footprint's walls within the hexside's sixth of the hex (the wall most
     * towards it, then the one clockwise, where two are as long); with a remainder of its wall no longer than
     * {@link #SHORT} beside it, that too. {@code groups}: each door's link group.
     */
    static List<Slice> slices(Frame frame, int mask, int[] heights, int[] groups, int storeys) {
        List<double[][]> runs = null;
        List<Slice> result = new ArrayList<>();
        for (int side = 0; side < 6; side++) {
            if (heights[side] <= 0 || (mask & 1 << side) != 0) { continue; }
            if (runs == null) { runs = runs(frame, mask); }
            double[] a = KitOutline.hexCorner(side + 5), b = KitOutline.hexCorner(side), towards = MIDPOINTS[side];
            double[][] best = null;
            double bestLength = 0, bestFacing = 0, bestTurn = 0, from = 0, to = 0;
            for (double[][] run : runs) {
                double[] p = run[0], q = run[1], d = { q[0] - p[0], q[1] - p[1] };
                // Within the sixth: clockwise of the corner before the hexside, counter-clockwise of the one after.
                double t0 = 0, t1 = 1;
                double[][] bounds = { { -a[0], -a[1] }, { b[0], b[1] } };
                for (double[] bound : bounds) {
                    double fp = KitOutline.cross(bound, p), fd = KitOutline.cross(bound, d);
                    if (Math.abs(fd) < 1e-12) {
                        if (fp < 0) { t1 = -1; }
                    } else if (fd > 0) {
                        t0 = Math.max(t0, -fp / fd);
                    } else {
                        t1 = Math.min(t1, -fp / fd);
                    }
                }
                double whole = Math.hypot(d[0], d[1]), length = (t1 - t0) * whole;
                if (length < 1e-6) { continue; }
                double[] n = KitOutline.normal(p, q), middle = KitOutline.lerp(p, q, (t0 + t1) / 2);
                double facing = n[0] * towards[0] + n[1] * towards[1], turn = KitOutline.cross(towards, middle);
                if (facing <= 0) { continue; }
                boolean better = best == null || length > bestLength + 1e-6 || Math.abs(length - bestLength) <= 1e-6
                      && (facing > bestFacing + 1e-6 || Math.abs(facing - bestFacing) <= 1e-6 && turn < bestTurn);
                if (better) {
                    best = run; bestLength = length; bestFacing = facing; bestTurn = turn;
                    from = t0 * whole <= SHORT ? 0 : t0; to = (1 - t1) * whole <= SHORT ? 1 : t1;
                }
            }
            if (best == null) { continue; }
            double[] p = KitOutline.lerp(best[0], best[1], from), q = KitOutline.lerp(best[0], best[1], to);
            // A wall that ends on the hex's outline goes on in the next hex: no corner.
            result.add(new Slice(p, q, Math.min(heights[side], storeys), groups[side], from == 0 && KitOutline.insideHex(p[0], p[1]) > TOUCH * H,
                  to == 1 && KitOutline.insideHex(q[0], q[1]) > TOUCH * H));
        }
        return result;
    }

    /**
     * The straight stretches of the footprint's ground walls for a hex's connections, each from p to q with the
     * building on its left: the walls of the pieces that stand for {@code mask} ({@link ConnectedKit#placements}: the
     * lone hex's, an Angled single link's band, else the six corner pieces'), joined where one goes straight on.
     */
    private static List<double[][]> runs(Frame frame, int mask) {
        Map<String, KitOutline.Piece> pieces = new HashMap<>();
        for (var piece : KitOutline.footprint(frame.square(), frame.bandEnd(), false, frame.scale())) { pieces.put(piece.tags(), piece); }
        List<KitOutline.Piece> standing = new ArrayList<>();
        if (mask == 0 || !frame.square() && Integer.bitCount(mask) == 1) {
            StringBuilder tags = new StringBuilder();
            for (int side = 0; side < 6; side++) { tags.append(tag(mask, side)); }
            standing.add(pieces.get(tags.toString()));
        } else {
            for (int side = 0; side < 6; side++) { standing.add(pieces.get(tag(mask, side) + tag(mask, (side + 1) % 6))); }
        }
        List<double[][]> runs = new ArrayList<>();
        for (var piece : standing) {
            for (var wall : KitOutline.rounded(piece, frame.cornerRadius(), Math.max(1, frame.cornerSegments())).walls()) {
                runs.add(new double[][] { wall.p(), wall.q() });
            }
        }
        boolean joined = true;
        while (joined) {
            joined = false;
            search:
            for (int i = 0; i < runs.size(); i++) {
                for (int j = 0; j < runs.size(); j++) {
                    double[][] first = runs.get(i), second = runs.get(j);
                    if (i == j || Math.hypot(first[1][0] - second[0][0], first[1][1] - second[0][1]) > 1e-4) { continue; }
                    double[] n = KitOutline.normal(first[0], first[1]), m = KitOutline.normal(second[0], second[1]);
                    if (n[0] * m[0] + n[1] * m[1] > 1 - 1e-9) {
                        runs.set(i, new double[][] { first[0], second[1] });
                        runs.remove(j);
                        joined = true;
                        break search;
                    }
                }
            }
        }
        return runs;
    }

    private static String tag(int mask, int side) {
        return ((mask & 1 << side) != 0 ? "-link-" : "-end-") + KitOutline.DIRECTIONS[side];
    }

    /** A door in its wall's frame: along the wall from its start, out of it, up. */
    private record Opening(double[] p, double[] u, double[] n, double length, double from, double to, double top) {
        float[] vertex(double t, double e, double z, double[] normal, float[] tint, double shade, double s, double v) {
            return new float[] { (float) (p[0] + u[0] * t + n[0] * e), (float) (p[1] + u[1] * t + n[1] * e), (float) z,
                  (float) (u[0] * normal[0] + n[0] * normal[1]), (float) (u[1] * normal[0] + n[1] * normal[1]), (float) normal[2],
                  (float) (tint[0] * shade), (float) (tint[1] * shade), (float) (tint[2] * shade), 1, (float) s, (float) v };
        }

        double along(float[] vertex) { return (vertex[0] - p[0]) * u[0] + (vertex[1] - p[1]) * u[1]; }

        double outward(float[] vertex) { return (vertex[0] - p[0]) * n[0] + (vertex[1] - p[1]) * n[1]; }
    }

    private static Opening opening(Style style, Cut cut) {
        double[] p = { cut.px(), cut.py() }, q = { cut.qx(), cut.qy() };
        double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
        double[] u = { (q[0] - p[0]) / length, (q[1] - p[1]) / length };
        return new Opening(p, u, KitOutline.normal(p, q), length, cut.before() || !cut.cornerStart() ? 0 : style.margin(),
              length - (cut.after() || !cut.cornerEnd() ? 0 : style.margin()), cut.levels() * LEVEL - style.lintel());
    }

    /**
     * A hex's kit pieces as {@code placements} stands them at level of detail {@code lod}, cut open over its doors,
     * with the doors: a model of its own in the kit's materials, a node per storey (a fall's pieces) and one of doors.
     */
    static RigidGlb.Data model(ConnectedKit.Kit kit, int lod, List<ConnectedKit.Placement> placements, List<Cut> cuts, String id) {
        Frame frame = kit.doors();
        var source = kit.lods().get(lod);
        Map<String, GeneratedMesh> nodes = new LinkedHashMap<>();
        for (var placement : placements) {
            nodes.computeIfAbsent(id + "-storey" + placement.level(), ignored -> new GeneratedMesh())
                  .placed(source, ConnectedKit.node(source, placement.node()), placement.matrix());
        }
        var doors = new GeneratedMesh();
        Map<String, Part> parts = new HashMap<>();
        for (Cut cut : cuts) {
            Opening opening = opening(frame.style(), cut);
            if (opening.to() - opening.from() < 2 * frame.style().jamb() + 1 || opening.top() < frame.style().header() + 1) { continue; }
            nodes.replaceAll((name, mesh) -> without(mesh, opening, frame.style().depth()));
            door(doors, frame, cut, opening, lod, parts);
        }
        // A floor cap describes the room's outline; its slab and picking belong to the interior (GpuBuilding#instance).
        nodes.values().forEach(mesh -> mesh.parts.keySet().removeIf(material -> material.endsWith(GeneratedMesh.BUILDING_FLOOR)));
        nodes.values().removeIf(mesh -> mesh.parts.isEmpty());
        if (!doors.parts.isEmpty()) { nodes.put(id + "-doors", doors); }
        var result = GeneratedMesh.kit(id, nodes, source.boardUVs);
        GeneratedKit.finishes(result, frame.modelled(), lod, true);
        parts.forEach((part, look) -> {
            if (!result.surfaces.containsKey(part)) { return; }
            if (look.paint()) { result.buildingPaint.add(part); }
            if (look.glow() != null) { RigidGlb.nightEmission(result, part, look.glow()); }
        });
        return result;
    }

    /** {@code mesh} less what lies in a door's opening: its span along the wall, through the wall's depth, up to its top. */
    private static GeneratedMesh without(GeneratedMesh mesh, Opening opening, double depth) {
        List<ToDoubleFunction<float[]>> inside = List.of(v -> opening.along(v) - opening.from(), v -> opening.to() - opening.along(v),
              v -> opening.outward(v) + depth, v -> CLEAR - opening.outward(v), v -> opening.top() - v[2]);
        var result = new GeneratedMesh();
        mesh.parts.forEach((material, part) -> {
            int stride = RigidGlb.STRIDE;
            var kept = new com.badlogic.gdx.utils.FloatArray(part.size);
            for (int start = 0; start < part.size; start += 3 * stride) {
                List<float[]> polygon = new ArrayList<>(3);
                // Most triangles lie wholly beyond one side of the opening.
                double[] most = new double[inside.size()];
                java.util.Arrays.fill(most, Double.NEGATIVE_INFINITY);
                for (int k = 0; k < 3; k++) {
                    float[] vertex = new float[stride];
                    System.arraycopy(part.items, start + k * stride, vertex, 0, stride);
                    polygon.add(vertex);
                    for (int side = 0; side < most.length; side++) { most[side] = Math.max(most[side], inside.get(side).applyAsDouble(vertex)); }
                }
                if (java.util.Arrays.stream(most).anyMatch(reach -> reach <= 0)) {
                    kept.addAll(part.items, start, 3 * stride);
                    continue;
                }
                // What lies outside each side in turn stays; what is left inside them all goes.
                var cut = new GeneratedMesh();
                for (var side : inside) {
                    cut.polygon(material, GeneratedMesh.clipBy(polygon, vertex -> -side.applyAsDouble(vertex)));
                    polygon = GeneratedMesh.clipBy(polygon, side);
                    if (polygon.isEmpty()) { break; }
                }
                if (cut.parts.containsKey(material)) { kept.addAll(cut.parts.get(material)); }
            }
            if (kept.size > 0) { result.parts.put(material, kept); }
        });
        return result;
    }

    private static final double[] OUT = { 0, 1, 0 }, IN = { 0, -1, 0 }, UP = { 0, 0, 1 }, DOWN = { 0, 0, -1 }, BACK = { -1, 0, 0 },
          ON = { 1, 0, 0 };

    /** One door: its frame where it ends, its header, its leaf with what the style puts on it, its threshold. */
    private static void door(GeneratedMesh mesh, Frame kit, Cut cut, Opening o, int lod, Map<String, Part> parts) {
        Style style = kit.style();
        Pieces pieces = style.pieces() == null ? new Pieces(null, null, 0, null) : style.pieces();
        double head = o.top() - style.header(), out = style.out(), depth = style.depth();
        double left = cut.before() ? o.from() : o.from() + style.jamb(), right = cut.after() ? o.to() : o.to() - style.jamb();
        Part frame = style.frame();
        parts.put(frame.id(), frame);
        for (int end = 0; end < 2; end++) {
            if (end == 0 ? cut.before() : cut.after()) { continue; }
            double t0 = end == 0 ? o.from() : right, t1 = end == 0 ? left : o.to();
            if (pieces.jamb() != null) {
                piece(mesh, kit, lod, pieces.jamb(), o, end == 0 ? o.from() : o.to(), 0, end == 0 ? 1 : -1, head);
            } else {
                box(mesh, frame, o, t0, t1, -depth, out, 0, head, "fbsn", 0);
            }
        }
        if (pieces.header() != null && pieces.headerLength() > 0) {
            int count = Math.max(1, (int) Math.rint((o.to() - o.from()) / pieces.headerLength()));
            double width = (o.to() - o.from()) / count;
            for (int k = 0; k < count; k++) { piece(mesh, kit, lod, pieces.header(), o, o.from() + k * width, head, width / pieces.headerLength(), 0); }
        } else {
            box(mesh, frame, o, o.from(), o.to(), -depth, out, head, o.top(), "fbud" + (cut.before() ? "" : "s") + (cut.after() ? "" : "n"), 0);
        }
        if (pieces.crown() != null) { piece(mesh, kit, lod, pieces.crown(), o, (left + right) / 2, o.top(), 1, 0); }

        Part leaf = style.leaf();
        parts.put(leaf.id(), leaf);
        double face = -style.recess();
        int rows = lod == 0 && style.panel() > 0 ? Math.max(1, (int) Math.rint(head / style.panel())) : 1;
        double row = head / rows, groove = rows > 1 ? Math.min(.25, row * .12) : 0;
        for (int k = 0; k < rows; k++) {
            double z0 = k * row, z1 = (k + 1) * row - (k < rows - 1 ? groove : 0);
            quad(mesh, leaf, o, OUT, 1, new double[][] { { left, face, z0 }, { right, face, z0 }, { right, face, z1 }, { left, face, z1 } }, 0);
            if (k < rows - 1) {
                // The groove under the next panel: a narrow face leaning back, in shade.
                quad(mesh, leaf, o, new double[] { 0, .7, .7 }, .45, new double[][] { { left, face, z1 }, { right, face, z1 },
                      { right, face - groove, z1 + groove }, { left, face - groove, z1 + groove } }, 0);
            }
        }
        // Its inside, seen in a cutaway.
        quad(mesh, leaf, o, IN, .5, new double[][] { { right, face - .4, 0 }, { left, face - .4, 0 }, { left, face - .4, head }, { right, face - .4, head } }, 0);
        double proud = face + .05;
        if (style.section() > 0 && lod == 0) {
            Part seam = style.seam();
            parts.put(seam.id(), seam);
            int sections = Math.max(1, (int) Math.rint((right - left) / style.section()));
            // A joint between its sections, and half of one where the next hex's leaf goes on.
            for (int k = cut.before() ? 0 : 1; k <= (cut.after() ? sections : sections - 1); k++) {
                double t = left + (right - left) * k / sections, a = Math.max(left, t - .2), b = Math.min(right, t + .2);
                quad(mesh, seam, o, OUT, 1, new double[][] { { a, proud, 0 }, { b, proud, 0 }, { b, proud, head }, { a, proud, head } }, 0);
            }
        }
        if (style.stripe() != null && style.stripeHeight() < head) {
            parts.put(style.stripe().id(), style.stripe());
            quad(mesh, style.stripe(), o, OUT, 1, new double[][] { { left, proud, .3 }, { right, proud, .3 },
                  { right, proud, .3 + style.stripeHeight() }, { left, proud, .3 + style.stripeHeight() } }, .3);
        }
        Windows windows = style.windows();
        if (windows != null && windows.glass() != null && lod < 2 && windows.pitch() > 0 && windows.sill() + windows.height() < head - .5) {
            parts.put(windows.glass().id(), windows.glass());
            int count = (int) Math.floor((right - left) / windows.pitch());
            for (int k = 0; k < count; k++) {
                double middle = (left + right) / 2 + (k - (count - 1) / 2.0) * windows.pitch();
                double a = middle - windows.width() / 2, b = middle + windows.width() / 2, z0 = windows.sill(), z1 = z0 + windows.height();
                quad(mesh, windows.glass(), o, OUT, 1, new double[][] { { a, proud, z0 }, { b, proud, z0 }, { b, proud, z1 }, { a, proud, z1 } }, z0);
            }
        }
        if (style.threshold() != null) {
            parts.put(style.threshold().id(), style.threshold());
            box(mesh, style.threshold(), o, left, right, -depth, style.apron(), 0, .12, "fu" + (cut.before() ? "" : "s") + (cut.after() ? "" : "n"), 0);
        }
    }

    /**
     * A box in a door's frame, textured along the wall and up: its {@code faces} of f (out), b (in), s (start), n (end),
     * u (up) and d (down).
     */
    private static void box(GeneratedMesh mesh, Part part, Opening o, double t0, double t1, double e0, double e1, double z0, double z1,
          String faces, double base) {
        if (faces.indexOf('f') >= 0) { quad(mesh, part, o, OUT, 1, new double[][] { { t0, e1, z0 }, { t1, e1, z0 }, { t1, e1, z1 }, { t0, e1, z1 } }, base); }
        if (faces.indexOf('b') >= 0) { quad(mesh, part, o, IN, .6, new double[][] { { t1, e0, z0 }, { t0, e0, z0 }, { t0, e0, z1 }, { t1, e0, z1 } }, base); }
        if (faces.indexOf('s') >= 0) { quad(mesh, part, o, BACK, .8, new double[][] { { t0, e0, z0 }, { t0, e1, z0 }, { t0, e1, z1 }, { t0, e0, z1 } }, base); }
        if (faces.indexOf('n') >= 0) { quad(mesh, part, o, ON, .8, new double[][] { { t1, e1, z0 }, { t1, e0, z0 }, { t1, e0, z1 }, { t1, e1, z1 } }, base); }
        if (faces.indexOf('u') >= 0) { quad(mesh, part, o, UP, 1, new double[][] { { t0, e1, z1 }, { t1, e1, z1 }, { t1, e0, z1 }, { t0, e0, z1 } }, base); }
        if (faces.indexOf('d') >= 0) { quad(mesh, part, o, DOWN, .55, new double[][] { { t0, e0, z0 }, { t1, e0, z0 }, { t1, e1, z0 }, { t0, e1, z0 } }, base); }
    }

    /**
     * A face of {@code part} through {@code corners} (along, out, up), facing {@code normal} in that frame, shaded by
     * {@code shade}; textured along the wall (out of it on a face seen end on) and up from {@code base} (out of the wall
     * on a level face).
     */
    private static void quad(GeneratedMesh mesh, Part part, Opening o, double[] normal, double shade, double[][] corners, double base) {
        double repeat = part.units();
        float[][] vertices = new float[4][];
        for (int k = 0; k < 4; k++) {
            double t = corners[k][0], e = corners[k][1], z = corners[k][2];
            double s = Math.abs(normal[0]) > .5 ? e : t, v = Math.abs(normal[2]) > .9 ? e : z - base;
            vertices[k] = o.vertex(t, e, z, normal, part.colour(), shade, s / repeat, -v / repeat);
        }
        // Wound to face its normal: the frame along, out, up is left handed.
        float[] a = vertices[0], b = vertices[1], c = vertices[2];
        double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]), ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
              nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (nx * a[3] + ny * a[4] + nz * a[5] >= 0) {
            mesh.quad(part.id(), vertices[0], vertices[1], vertices[2], vertices[3]);
        } else {
            mesh.quad(part.id(), vertices[3], vertices[2], vertices[1], vertices[0]);
        }
    }

    /**
     * A modelled piece of the kit at {@code t} along the wall and {@code z} up, stretched {@code scale} along (negative:
     * mirrored, a door's end jamb) and to {@code height} (0: as modelled).
     */
    private static void piece(GeneratedMesh mesh, Frame kit, int lod, String name, Opening o, double t, double z, double scale, double height) {
        float x = (float) (o.p()[0] + o.u()[0] * t), y = (float) (o.p()[1] + o.u()[1] * t);
        var frame = new Matrix4(new float[] { (float) (o.u()[0] * scale), (float) (o.u()[1] * scale), 0, 0, (float) -o.n()[0], (float) -o.n()[1], 0, 0,
              0, 0, 1, 0, x, y, (float) z, 1 });
        GeneratedKit.piece(mesh, kit.modelled(), lod, name, frame, height);
    }
}
