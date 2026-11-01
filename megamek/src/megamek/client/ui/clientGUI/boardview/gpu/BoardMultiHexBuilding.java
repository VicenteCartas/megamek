/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.Configuration;
import megamek.common.board.BoardGenerator;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.logging.MMLogger;

/**
 * Domed multi-hex buildings ({@code models/buildings/README.md}): one design generated over a whole building instead of
 * a model per hex. Every hex draws only its own share, so drawing, picking, clearance and a collapsed hex stay per hex,
 * and neighbouring shares meet exactly on their hexsides: the domes stand on a plinth over the building, fitted to its
 * heights ({@link #fits}), each hex taking the part over its own hex; a hex outside every podium is an annex: the
 * plinth, and a module at its own height standing out from the podium's ring towards it ({@link #module}). The lunar habitats are a connected kit instead
 * ({@code buildings/lunar_habitat}).
 * Coordinates are model units (a level {@link GpuBuilding#LEVEL_HEIGHT} high) about the hex centre on its own ground.
 */
final class BoardMultiHexBuilding {
    static final int LODS = 2;
    /** How far a facade stands back from a hexside the building does not connect across. */
    static final float SETBACK = 4;
    /** A dome's plinth above the highest ground under it, and how far its podium keeps inside the footprint: its fins
     * stay on the plinth's set-back deck. */
    static final float PLINTH = 6, DOME_MARGIN = SETBACK + 4;
    /** A dome's rise as a share of its foot's radius: a flattened cap, the same whatever hexes still stand. */
    static final float DOME_RISE = .62f;
    /**
     * The podium round a dome's foot, a ring building on a regular twelve-sided outline whose corners lie on the fit's
     * circle: its width as a share of that circle's radius, within bounds. Pylons stand at the six corners that face
     * hexsides, buttress fins at the six between, as far out as the podium keeps inside the footprint's set-back facades.
     */
    private static final float PODIUM_WIDTH = .2f, FIN = DOME_MARGIN - SETBACK;
    static final String FIN_MATERIAL = "structures/common/safety-yellow";
    /** The smallest dome worth drawing (a lone hex's fits); a footprint too narrow for any stands as annexes. */
    private static final float MIN_DOME = 16;
    /**
     * An annex's module ({@link #module}), a wing of the ring building ending in a gatehouse as the printed lunar map's
     * gate modules: the wing's half width, the gatehouse's length and how much wider it stands, the cut of its corners;
     * the root where the wing meets the ring, how much wider and how far out; its cap's bevel, in and up.
     */
    private static final float MODULE_HALF = 15, GATEHOUSE = 34, HEAD = 4, MODULE_CHAMFER = 4, ROOT = 10, ROOT_REACH = 10,
          BEVEL = 3, BEVEL_RISE = 1.6f;
    /** Geodesic strut length the frequency aims at, and the struts' cross-section. */
    private static final float STRUT_LENGTH = 16, STRUT_WIDTH = .75f, STRUT_OUT = .5f, STRUT_IN = .4f;
    private static final float LEVEL = GpuBuilding.LEVEL_HEIGHT;
    private static final float WIDTH = HexTile.MODEL_WIDTH, HEIGHT = HexTile.HEIGHT;
    /** Wall texture: one repeat per two storeys, as wide as high. */
    private static final float WALL_REPEAT = 2 * LEVEL;
    private static final float EPSILON = 1e-3f;

    // Board materials (textures/<name>.tres); their .tres files give every finish, transparency included.
    static final String WALL = "structures/lunar/colony-panel", ROOF = "structures/lunar/habitat-roof",
          PLINTH_MATERIAL = "structures/lunar/regolith-concrete", FRAME = "structures/lunar/dome-frame",
          GLASS = "structures/lunar/dome-glass", GARDEN = "structures/lunar/biodome-garden",
          PIER = "structures/lunar/habitat-pier", SKYLIGHT = "structures/lunar/skylight-glass";

    private static final MMLogger LOGGER = MMLogger.create(BoardMultiHexBuilding.class);

    private BoardMultiHexBuilding() { }

    enum Design {
        /** A geodesic glass dome on a plinth. */
        DOME,
        /** A geodesic glass dome over a garden. */
        BIODOME;

        final String asset = "buildings/multihex/" + name().toLowerCase(Locale.ROOT);
    }

    static boolean supports(String asset) {
        return BoardGenerator.of(asset) == BoardGenerator.MULTI_HEX_BUILDING;
    }

    static Design design(String asset) {
        for (Design design : Design.values()) {
            if (design.asset.equals(asset)) { return design; }
        }
        throw new IllegalArgumentException("Invalid multi-hex building asset: " + asset);
    }

    /**
     * A dome over this hex: its centre relative to the hex centre, its glass's foot radius, the heights of its plinth,
     * of the podium roof its glass rises from and of its apex over the hex's ground, its geodesic frequency (even, so
     * the base is a vertex ring), and its building's airlocks, five numbers each: the back's x and y relative to its
     * centre, the way the airlock faces and its floor above the foot, whose tubes ({@link #bellows}) join the podium, the
     * ring building
     * round its foot whose twelve-sided outline has its corners {@code podium} from the centre; {@code modules}, four
     * numbers each: an annex's module ({@link #module}), its direction from the centre, how far its outer end lies from
     * it and its rise above the foot.
     */
    record Dome(float x, float y, float radius, float foot, float ring, float apex, int frequency, float podium,
          List<Float> entrances, List<Float> modules) {
        Dome {
            entrances = List.copyOf(entrances);
            modules = List.copyOf(modules);
        }

        /** The podium's ring's height above the foot: where the glass meets its roof. */
        float height() { return ring - foot; }

        /** The cap's sphere radius and its centre's height: the glass between the ring and the apex. */
        float sphere() {
            float rise = apex - ring;
            return (radius * radius + rise * rise) / (2 * rise);
        }

        /** The glass's height at {@code from} the centre (inside the foot's radius). */
        float glass(float from) {
            float sphere = sphere();
            return apex - sphere + (float) Math.sqrt(Math.max(0, sphere * sphere - from * from));
        }

        /**
         * Whether something {@code margin} radians wide at {@code angle} round the centre would meet an entrance wing or an
         * annex's module: fins and pylons keep clear of them.
         */
        boolean joined(double angle, double margin) {
            for (int at = 0; at < entrances.size(); at += 5) {
                // Its tube enters the podium square to it, towards the airlock.
                double toward = Math.atan2(entrances.get(at + 1), entrances.get(at));
                if (Math.abs(Math.IEEEremainder(toward - angle, 2 * Math.PI)) < Math.toRadians(12) + margin) { return true; }
            }
            // Its root, the widest part at the ring.
            double half = Math.atan2(MODULE_HALF + ROOT + 2, podium * Math.cos(Math.PI / 12));
            for (int at = 0; at < modules.size(); at += 4) {
                double toward = Math.atan2(modules.get(at + 1), modules.get(at));
                if (Math.abs(Math.IEEEremainder(toward - angle, 2 * Math.PI)) < half + margin) { return true; }
            }
            return false;
        }
    }

    /**
     * Derived from the captured board; shared by drawing, picking and clearance. {@code top}: the shell's height,
     * {@code reach}: how high it connects towards each board direction (model units, 0 where a facade closes it);
     * {@code annex}: a domed building's hex outside every podium, whose share is the plinth and the modules over it
     * ({@link #module}): the domes are there for their modules alone.
     */
    record Layout(Design design, float top, List<Float> reach, List<Dome> domes, List<Gear> gear, boolean annex) {
        Layout {
            domes = List.copyOf(domes);
            reach = List.copyOf(reach);
            gear = List.copyOf(gear);
        }
    }

    /** A support piece on the share: its node in the equipment kit, its origin, its turn clockwise in degrees, its size. */
    record Gear(String piece, float x, float y, float z, float angle, float scale) {
        Gear(String piece, float x, float y, float z, float angle) { this(piece, x, y, z, angle, 1); }
    }

    /** The support pieces' kit (tools/buildings/lunar_equipment.py) and each piece's near bounds. */
    record Equipment(List<RigidGlb.Data> lods, Map<String, BoundingBox> bounds) { }

    /** A hex of a building: its board position, ground and top in levels; a ghost is a collapsed hex it still names. */
    record Member(Coords coords, int elevation, int top, boolean ghost) { }

    /**
     * A dome fitted to a footprint: the centre (board model coordinates), the foot radius, the ground it stands over
     * (levels), its rise above its plinth and its frequency.
     */
    record Fit(float x, float y, float radius, int base, float ring, float apex, int frequency, float podium) { }


    /** A lone hex of the design, {@code levels} high: palette thumbnails and previews. */
    static Layout layout(String asset, int levels) {
        Design design = design(asset);
        int height = Math.max(1, levels);
        Float[] closed = { 0f, 0f, 0f, 0f, 0f, 0f };
        Coords lone = new Coords(0, 0);
        var fits = fits(List.of(new Member(lone, 0, height, false)));
        var none = java.util.Collections.nCopies(fits.size(), List.<Float>of());
        return new Layout(design, PLINTH, List.of(closed), domes(fits, lone, 0, none, none), List.of(), false);
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        Design design = design(feature.asset());
        int levels = Math.max(1, Math.round(feature.height()));
        Float[] connected = connections(scene, tile, feature.asset(), levels);
        var group = group(scene, tile, feature.asset());
        var fits = fits(group);
        int base = group.stream().mapToInt(Member::elevation).max().orElse(tile.elevation());
        float shift = (base - tile.elevation()) * LEVEL, plinth = PLINTH + shift;
        var none = java.util.Collections.nCopies(fits.size(), List.<Float>of());
        if (annex(fits, tile.coords())) {
            // An annex: the plinth, joined to its neighbours', under the modules that cross it, its own among them; one
            // without a module of its own (no higher than the plinth, or none fits) a block at its own height.
            float top = levels * LEVEL, apron = Math.min(top, plinth);
            boolean block = annexModule(scene, tile, fits, base, feature.asset()) == null;
            for (int direction = 0; direction < 6; direction++) {
                connected[direction] = Math.min(connected[direction], apron);
                // A block opens only towards another block: the hexes under the domes and the wings draw no wall to meet.
                var neighbor = scene.neighbor(tile, direction);
                if (block && connected[direction] > 0 && !(annex(fits, neighbor.coords())
                      && annexModule(scene, neighbor, fits, base, feature.asset()) == null)) {
                    connected[direction] = 0f;
                }
            }
            var domes = block ? List.<Dome>of()
                  : domes(fits, tile.coords(), shift, none, modules(scene, group, fits, base, feature.asset()));
            return new Layout(design, top, List.of(connected), domes,
                  support(scene, tile, design, block ? 0 : apron, connected, List.of(), true, block), true);
        }
        plinth(connected, plinth);
        var domes = domes(fits, tile.coords(), shift, entrances(scene, group, fits, base, feature.asset()),
              modules(scene, group, fits, base, feature.asset()));
        return new Layout(design, plinth, List.of(connected), domes,
              support(scene, tile, design, plinth, connected, domes, false, false), false);
    }

    /**
     * How high {@code tile}, {@code levels} high, connects towards each direction (model units): to the lower of the two
     * tops; 0, a facade, towards a hex that does not connect back, holds no part of the same design or stands wholly
     * above or below it.
     */
    private static Float[] connections(BoardScene scene, BoardScene.Tile tile, String asset, int levels) {
        Float[] result = new Float[6];
        for (int direction = 0; direction < 6; direction++) {
            // A hexside without a structure beyond keeps its facade: a dangling exit (a collapsed neighbour) shows no void.
            var neighbor = scene.neighbor(tile, direction);
            boolean joined = (tile.cover().exits() & 1 << direction) != 0 && ConnectedKit.back(scene, tile, direction)
                  && neighbor.features().stream().anyMatch(feature -> feature.asset().equals(asset));
            // Up to the lower top where the two vertical spans overlap, the same seen from either side; else a facade.
            int top = joined ? Math.min(levels, neighbor.elevation() - tile.elevation() + Math.max(1, neighbor.cover().height()))
                  : 0;
            boolean overlap = joined && Math.max(tile.elevation(), neighbor.elevation())
                  < Math.min(tile.elevation() + levels, neighbor.elevation() + Math.max(1, neighbor.cover().height()));
            result[direction] = overlap ? top * LEVEL : 0f;
        }
        return result;
    }

    /** {@code connected} for a hex under the domes, in place: its plinth joins its neighbours', annexes' too, at its height. */
    private static void plinth(Float[] connected, float plinth) {
        for (int direction = 0; direction < 6; direction++) {
            if (connected[direction] > 0) { connected[direction] = plinth; }
        }
    }

    /** The domes of {@code fits} as the hex {@code at} sees them, its ground {@code shift} below the building's base. */
    private static List<Dome> domes(List<Fit> fits, Coords at, float shift, List<List<Float>> entrances,
          List<List<Float>> wings) {
        List<Dome> result = new ArrayList<>();
        for (int index = 0; index < fits.size(); index++) {
            var fit = fits.get(index);
            result.add(new Dome(fit.x() - HexTile.centerX(at, WIDTH), fit.y() - HexTile.centerY(at, HEIGHT), fit.radius(),
                  PLINTH + shift, fit.ring() + shift, fit.apex() + shift, fit.frequency(), fit.podium(),
                  entrances.get(index), wings.get(index)));
        }
        return result;
    }

    /** Whether a domed building's hex lies wholly outside every one of its domes' podiums: an annex. */
    static boolean annex(List<Fit> fits, Coords at) {
        float x = HexTile.centerX(at, WIDTH), y = HexTile.centerY(at, HEIGHT), corner = HexTile.cornerOffsetX(0, WIDTH);
        return fits.stream().allMatch(fit -> Math.hypot(x - fit.x(), y - fit.y()) > fit.podium() + corner);
    }

    /**
     * An annex's module ({@link #module}): on the line from the centre of the dome nearest it through its own, out of
     * the ring over the annex's centre, where units stand, its gatehouse end drawn in as far as it must to keep inside
     * the annex's facades and clear of the airlocks before it, with room for their tubes where it can. That dome's index
     * and the module as {@link Dome#modules}
     * gives it; null where the annex rises no higher than the plinth.
     */
    private static float[] annexModule(BoardScene scene, BoardScene.Tile annex, List<Fit> fits, int base, String asset) {
        int levels = Math.max(1, annex.cover().height());
        float rise = (annex.elevation() + levels - base) * LEVEL - PLINTH;
        if (rise <= EPSILON || fits.isEmpty()) { return null; }
        float ax = HexTile.centerX(annex.coords(), WIDTH), ay = HexTile.centerY(annex.coords(), HEIGHT);
        int nearest = 0;
        for (int index = 1; index < fits.size(); index++) {
            var fit = fits.get(index);
            if (Math.hypot(ax - fit.x(), ay - fit.y()) - fit.podium()
                  < Math.hypot(ax - fits.get(nearest).x(), ay - fits.get(nearest).y()) - fits.get(nearest).podium()) {
                nearest = index;
            }
        }
        var fit = fits.get(nearest);
        float dx = ax - fit.x(), dy = ay - fit.y(), apart = (float) Math.hypot(dx, dy), ux = dx / apart, uy = dy / apart;
        var kit = equipment();
        List<List<float[]>> airlocks = new ArrayList<>();
        for (Gear airlock : airlocks(kit, scene, annex, 0, connections(scene, annex, asset, levels), List.of(), true, false)) {
            airlocks.add(footprint(kit, airlock));
        }
        List<float[]> facades = outline(63, SETBACK);
        float inner = ringMiddle(fit.radius(), fit.podium());
        // Room for the airlocks' tubes to bend to the door (#bellows), else only clear of them.
        for (float room : new float[] { AIRLOCK_ROOM, 0 }) {
            for (float outer = apart + GATEHOUSE / 2; outer > apart + MODULE_CHAMFER; outer -= .5f) {
                var shape = moduleOutline(-dx, -dy, ux, uy, Math.max(inner, outer - GATEHOUSE), outer, MODULE_HALF + HEAD,
                      MODULE_CHAMFER);
                boolean clear = true;
                // Its outer end's corners (#moduleOutline).
                for (int corner = 1; corner <= 4 && clear; corner++) {
                    clear = inside(facades, shape.get(corner)[0], shape.get(corner)[1]);
                }
                for (var airlock : airlocks) {
                    var kept = grown(airlock, room);
                    for (float[] corner : kept) { clear &= !inside(shape, corner[0], corner[1]); }
                    for (float[] corner : shape) { clear &= !inside(kept, corner[0], corner[1]); }
                }
                if (clear) { return new float[] { nearest, ux, uy, outer, rise }; }
            }
        }
        return null;
    }

    /** Each dome's annexes' modules ({@link Dome#modules}), in a stable order. */
    private static List<List<Float>> modules(BoardScene scene, List<Member> group, List<Fit> fits, int base, String asset) {
        List<float[]> found = new ArrayList<>();
        for (Member member : group) {
            if (member.ghost() || !annex(fits, member.coords())) { continue; }
            var module = annexModule(scene, scene.tile(member.coords()), fits, base, asset);
            if (module != null) { found.add(module); }
        }
        found.sort(Comparator.<float[]>comparingDouble(module -> module[1]).thenComparingDouble(module -> module[2]));
        List<List<Float>> result = new ArrayList<>();
        fits.forEach(fit -> result.add(new ArrayList<>()));
        for (float[] module : found) {
            for (int at = 1; at < module.length; at++) { result.get((int) module[0]).add(module[at]); }
        }
        return result;
    }

    /** The middle of a dome's ring, where an annex's module ends inside it. */
    private static float ringMiddle(float radius, float podium) {
        return (radius + podium * (float) Math.cos(Math.PI / 12)) / 2;
    }

    /**
     * A module's outline about the dome's centre at (cx, cy), counter-clockwise from its inner end's right corner: from
     * {@code from} to {@code to} along (ux, uy), {@code half} either side, its corners cut by {@code chamfer}; its outer
     * end's corners are the second to fifth points.
     */
    private static List<float[]> moduleOutline(float cx, float cy, float ux, float uy, float from, float to, float half,
          float chamfer) {
        float px = -uy, py = ux;
        float[][] points = { { from + chamfer, -half }, { to - chamfer, -half }, { to, chamfer - half },
              { to, half - chamfer }, { to - chamfer, half }, { from + chamfer, half }, { from, half - chamfer },
              { from, chamfer - half } };
        List<float[]> result = new ArrayList<>(points.length);
        for (float[] point : points) {
            result.add(new float[] { cx + ux * point[0] + px * point[1], cy + uy * point[0] + py * point[1] });
        }
        return result;
    }

    /** A convex, counter-clockwise outline moved in by {@code by} on every edge (out where negative). */
    private static List<float[]> parapets(List<float[]> outline, float by) {
        int count = outline.size();
        List<float[]> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] before = outline.get((i + count - 1) % count), at = outline.get(i), after = outline.get((i + 1) % count);
            result.add(meet(shifted(before, at, by), shifted(at, after, by)));
        }
        return result;
    }

    /** The line through a to b moved left by {@code by}: a point on it and its direction. */
    private static float[] shifted(float[] a, float[] b, float by) {
        float dx = b[0] - a[0], dy = b[1] - a[1], length = (float) Math.hypot(dx, dy);
        return new float[] { a[0] - dy / length * by, a[1] + dx / length * by, dx, dy };
    }

    /** Where two lines (#shifted) meet. */
    private static float[] meet(float[] first, float[] second) {
        float cross = first[2] * second[3] - first[3] * second[2];
        float t = ((second[0] - first[0]) * second[3] - (second[1] - first[1]) * second[2]) / cross;
        return new float[] { first[0] + first[2] * t, first[1] + first[3] * t };
    }

    /**
     * The airlocks of a domed building as {@link Dome#entrances} gives them, in a stable order: those the hexes under
     * its domes place ({@link #airlocks}), whose tubes join the nearest podium.
     */
    private static List<List<Float>> entrances(BoardScene scene, List<Member> group, List<Fit> fits, int base,
          String asset) {
        var kit = equipment();
        List<List<Float>> result = new ArrayList<>();
        fits.forEach(fit -> result.add(new ArrayList<>()));
        if (fits.isEmpty()) { return result; }
        var none = java.util.Collections.nCopies(fits.size(), List.<Float>of());
        List<float[]> points = new ArrayList<>();
        for (Member member : group) {
            if (member.ghost() || annex(fits, member.coords())) { continue; }
            var tile = scene.tile(member.coords());
            float shift = (base - tile.elevation()) * LEVEL;
            var connected = connections(scene, tile, asset, Math.max(1, tile.cover().height()));
            plinth(connected, PLINTH + shift);
            for (Gear airlock : airlocks(kit, scene, tile, PLINTH + shift, connected,
                  domes(fits, member.coords(), shift, none, none), false, false)) {
                // Turned clockwise from +y (#frame).
                double facing = Math.toRadians(airlock.angle());
                points.add(new float[] { HexTile.centerX(member.coords(), WIDTH) + airlock.x(),
                      HexTile.centerY(member.coords(), HEIGHT) + airlock.y(), (float) Math.sin(facing),
                      (float) Math.cos(facing), airlock.z() - PLINTH - shift });
            }
        }
        points.sort(Comparator.<float[]>comparingDouble(point -> point[0]).thenComparingDouble(point -> point[1]));
        for (float[] point : points) {
            int nearest = 0;
            for (int index = 1; index < fits.size(); index++) {
                if (Math.hypot(point[0] - fits.get(index).x(), point[1] - fits.get(index).y())
                      < Math.hypot(point[0] - fits.get(nearest).x(), point[1] - fits.get(nearest).y())) { nearest = index; }
            }
            result.get(nearest).addAll(List.of(point[0] - fits.get(nearest).x(), point[1] - fits.get(nearest).y(), point[2],
                  point[3], point[4]));
        }
        return result;
    }

    // ---- Support pieces ------------------------------------------------------------------------------------------------

    private static final String EQUIPMENT = "buildings/multihex/lunar-equipment.glb";
    private static final List<String> INTERIOR_GEAR = List.of("plant", "tanks");
    private static volatile BoardKit<Equipment> equipment = new BoardKit<>(BoardMultiHexBuilding::loadEquipment);
    /** Whether each board material's .tres is transparent ({@link GodotMaterial#blended}), read once per asset load. */
    private static final Map<String, Boolean> BLENDED = new java.util.concurrent.ConcurrentHashMap<>();

    /** A reload publishes the kit afresh; in-flight terrain workers keep the copy they hold. */
    static void reload() {
        equipment = new BoardKit<>(BoardMultiHexBuilding::loadEquipment);
        BLENDED.clear();
    }

    static Equipment equipment() { return equipment.get(); }

    private static Equipment loadEquipment() {
        File models = new File(Configuration.dataDir(), "models");
        List<RigidGlb.Data> lods;
        try {
            lods = GpuBuilding.lods(RigidGlb.loadLods(new FileHandle(new File(models, EQUIPMENT)), models.toPath()))
                  .stream().map(RigidGlb.Data.class::cast).toList();
        } catch (RuntimeException failure) {
            // An interrupted read (a board closed meanwhile) is not kept: the next board reads the kit afresh.
            if (Thread.currentThread().isInterrupted()) { throw failure; }
            LOGGER.warn("Multi-hex buildings draw without their support pieces: cannot read {}: {}", EQUIPMENT,
                  failure.getMessage());
            return new Equipment(List.of(), Map.of());
        }
        Map<String, BoundingBox> bounds = new HashMap<>();
        var near = lods.getFirst();
        for (int index = 0; index < near.nodes.size; index++) {
            var node = near.nodes.get(index);
            var box = new BoundingBox().inf();
            ConnectedKit.triangles(near, node, new Matrix4()).forEach(box::ext);
            bounds.put(node.id.replaceFirst("-lod0$", ""), box);
        }
        return new Equipment(lods, Map.copyOf(bounds));
    }

    /**
     * What stands on the share, chosen per hex and stable for it: an airlock at each closed hexside a road or a corridor
     * (a ground rail) arrives at, at {@code top} (an annex's, {@code blocks}: on its plinth, or in front of the block it
     * stands as, {@code block}); under a glass dome, at its middle, a piece that clears the glass; pylons round the
     * podiums. A piece goes only where it fits, turned to a hexside.
     */
    private static List<Gear> support(BoardScene scene, BoardScene.Tile tile, Design design, float top, Float[] reach,
          List<Dome> domes, boolean blocks, boolean block) {
        var kit = equipment();
        List<Gear> result = new ArrayList<>();
        if (kit.lods().isEmpty()) { return List.of(); }
        var random = new SplittableRandom(tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L);
        float[] heights = new float[6];
        for (int direction = 0; direction < 6; direction++) { heights[direction] = reach[direction]; }
        List<float[]> base = outline(closed(heights, 0), SETBACK - .01f);
        result.addAll(airlocks(kit, scene, tile, top, reach, domes, blocks, block));
        if (!blocks && !domes.isEmpty()) {
            Dome dome = domes.stream().min(Comparator.comparingDouble(near -> Math.hypot(near.x(), near.y()) - near.podium()))
                  .orElseThrow();
            float distance = (float) Math.hypot(dome.x(), dome.y());
            if (design == Design.DOME && distance < dome.radius()) {
                // Under the glass, towards the dome's middle where it is highest, within this hex.
                float toward = Math.min(distance, 18);
                float x = distance < 1 ? 0 : dome.x() / distance * toward, y = distance < 1 ? 0 : dome.y() / distance * toward;
                place(result, kit, INTERIOR_GEAR.get(random.nextInt(INTERIOR_GEAR.size())), x, y, top, random.nextInt(6),
                      base, List.of(dome), true);
            }
        }
        List<float[]> hex = hex();
        for (Dome dome : domes) {
            for (int direction = 0; direction < 6; direction++) {
                double angle = Math.toRadians(90 - 60 * direction);
                float x = dome.x() + dome.podium() * (float) Math.cos(angle), y = dome.y() + dome.podium() * (float) Math.sin(angle);
                // A big dome's pylons are heavier: their mass grows with the podium's.
                float scale = Math.clamp(dome.podium() / 90, 1, 1.7f);
                if (inside(hex, x, y) && !dome.joined(angle, Math.atan2(8 * scale, dome.podium()))) {
                    result.add(new Gear("pylon", x, y, dome.foot(), 60 * direction, scale));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * An airlock at each closed hexside a road or a corridor (a ground rail) arrives at, facing it on its axis (a road
     * meets a closed hexside square at its middle, BoardRoad#layout), on the plinth at {@code top} (a colony slab
     * road's deck where one arrives, GpuRoads#SLAB_RISE): an annex's
     * ({@code blocks}) always, its module keeping clear of it (#annexModule) or in front of the {@code block} it stands
     * as, else where it fits clear of the podiums.
     */
    private static List<Gear> airlocks(Equipment kit, BoardScene scene, BoardScene.Tile tile, float top, Float[] reach,
          List<Dome> domes, boolean blocks, boolean block) {
        var bounds = kit.bounds().get("airlock");
        if (bounds == null) { return List.of(); }
        float[] heights = new float[6];
        for (int direction = 0; direction < 6; direction++) { heights[direction] = reach[direction]; }
        List<float[]> base = outline(closed(heights, 0), SETBACK - .01f);
        List<Gear> result = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            if (heights[direction] > 0 || neighbor == null
                  || ((neighbor.railBed() | neighbor.roadExits()) & 1 << (direction + 3) % 6) == 0) {
                continue;
            }
            float[] side = side(direction);
            // In front of a block's facade, else on the plinth inside its edge.
            float along = side[2] - SETBACK - (block ? 0 : bounds.max.y);
            // At a colony slab road's deck where one arrives, so it runs on level to the door.
            float z = GpuRoads.slabs(neighbor, false) && (neighbor.roadExits() & 1 << (direction + 3) % 6) != 0
                  ? Math.max(0, GpuRoads.SLAB_RISE + (neighbor.elevation() - tile.elevation()) * LEVEL) : top;
            var airlock = new Gear("airlock", side[0] * along, side[1] * along, z, 60 * direction);
            if (blocks || fits(kit, airlock, base, domes, false)) { result.add(airlock); }
        }
        return result;
    }

    private static boolean inside(List<float[]> area, float x, float y) {
        for (int i = 0; i < area.size(); i++) {
            float[] a = area.get(i), b = area.get((i + 1) % area.size());
            if ((b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) < 0) { return false; }
        }
        return true;
    }

    /** The plants of a biodome's garden: grown trees, as woods have them, in turn by spot. */
    private static final List<String> GARDEN_TREES = List.of("vegetation/palm", "vegetation/tree-broad",
          "vegetation/orchard-round", "vegetation/palm-bent", "vegetation/willow-broad", "vegetation/orchard-spreading");
    /** A garden tree's height in levels from the garden's floor, and the room it keeps from the glass above it. */
    private static final float TREE_HEIGHT = 1.7f, TREE_CLEARANCE = 6;

    /**
     * {@code features} with a biodome's garden trees on {@code tile}: on a ring of spots round the hex centre and the
     * centre itself, those under the dome where a tree clears the glass; chosen and turned per spot, stable for it.
     * They are drawn like any woods' trees; the game has no woods there.
     */
    static List<BoardScene.Feature> withTrees(BoardScene scene, BoardScene.Tile tile, List<BoardScene.Feature> features) {
        var building = features.stream().filter(feature -> supports(feature.asset())
              && design(feature.asset()) == Design.BIODOME).findFirst().orElse(null);
        if (building == null) { return features; }
        List<Dome> domes = layout(scene, tile, building).domes();
        if (domes.isEmpty()) { return features; }
        List<BoardScene.Feature> result = new ArrayList<>(features);
        var random = new SplittableRandom(tile.coords().getX() * 19_349_663L ^ tile.coords().getY() * 83_492_791L);
        for (int spot = 0; spot < 7; spot++) {
            double angle = Math.PI / 3 * spot + random.nextDouble() * .6;
            float reach = spot == 6 ? 0 : 19 + random.nextFloat() * 6;
            float x = reach * (float) Math.cos(angle), y = reach * (float) Math.sin(angle);
            float scale = .85f + random.nextFloat() * .3f;
            String species = GARDEN_TREES.get(random.nextInt(GARDEN_TREES.size()));
            float height = TREE_HEIGHT * scale * LEVEL;
            var dome = domes.stream().filter(near -> {
                float from = (float) Math.hypot(x - near.x(), y - near.y());
                return from <= near.radius() - 8 && near.glass(from) >= floor(near) + height + TREE_CLEARANCE;
            }).findFirst().orElse(null);
            if (dome == null) { continue; }
            result.add(new BoardScene.Feature(species, x, y, random.nextFloat() * 360, scale, TREE_HEIGHT * scale,
                  floor(dome) / LEVEL, BoardScene.FeatureKind.TREE));
        }
        return result;
    }

    /** Places {@code piece} at (x, y), trying each turn from {@code first}, where it fits ({@link #fits}). */
    private static void place(List<Gear> result, Equipment kit, String piece, float x, float y, float z, int first,
          List<float[]> area, List<Dome> domes, boolean inside) {
        for (int turn = first; turn < first + 6; turn++) {
            var gear = new Gear(piece, x, y, z, 60 * (turn % 6));
            if (fits(kit, gear, area, domes, inside)) {
                result.add(gear);
                return;
            }
        }
    }

    /**
     * Whether the piece's footprint lies within {@code area} (convex, counter-clockwise) and outside every dome's
     * podium, against it at most, or ({@code inside}) wholly under the one dome's glass with room to spare.
     */
    private static boolean fits(Equipment kit, Gear gear, List<float[]> area, List<Dome> domes, boolean inside) {
        BoundingBox box = kit.bounds().get(gear.piece());
        if (box == null || area.isEmpty()) { return false; }
        for (float[] corner : footprint(kit, gear)) {
            if (!inside(area, corner[0], corner[1])) { return false; }
            for (Dome dome : domes) {
                float from = (float) Math.hypot(corner[0] - dome.x(), corner[1] - dome.y());
                if (!inside && from < dome.podium() * (float) Math.cos(Math.PI / 12) - 2) { return false; }
                if (inside && (from > dome.radius() - 3 || dome.glass(from) < gear.z() + box.max.z + 4)) { return false; }
            }
        }
        return true;
    }

    /** The piece's footprint on the share, counter-clockwise; empty for a piece the kit lacks. */
    private static List<float[]> footprint(Equipment kit, Gear gear) {
        BoundingBox box = kit.bounds().get(gear.piece());
        if (box == null) { return List.of(); }
        Matrix4 frame = frame(gear);
        List<float[]> result = new ArrayList<>(4);
        for (float[] corner : new float[][] { { box.min.x, box.min.y }, { box.max.x, box.min.y }, { box.max.x, box.max.y },
              { box.min.x, box.max.y } }) {
            var point = new Vector3(corner[0], corner[1], 0).mul(frame);
            result.add(new float[] { point.x, point.y });
        }
        return result;
    }

    /** The piece's placement: turned to its hexside, at its origin. */
    private static Matrix4 frame(Gear gear) {
        // Clockwise about the up axis, as ConnectedKit#placement turns sixths.
        double turn = Math.toRadians(-gear.angle());
        Matrix4 frame = new Matrix4();
        frame.val[Matrix4.M00] = (float) Math.cos(turn);
        frame.val[Matrix4.M01] = (float) -Math.sin(turn);
        frame.val[Matrix4.M10] = (float) Math.sin(turn);
        frame.val[Matrix4.M11] = (float) Math.cos(turn);
        frame.val[Matrix4.M23] = gear.z();
        frame.scale(gear.scale(), gear.scale(), gear.scale());
        frame.val[Matrix4.M03] = gear.x();
        frame.val[Matrix4.M13] = gear.y();
        return frame;
    }

    /**
     * The building {@code tile} belongs to: the hexes of the same design joined by exits that connect back, plus the
     * hexes they name that hold no structure any more (collapsed), which still shape the fit.
     */
    static List<Member> group(BoardScene scene, BoardScene.Tile tile, String asset) {
        Map<Coords, Member> members = new LinkedHashMap<>();
        var open = new ArrayDeque<BoardScene.Tile>();
        open.add(tile);
        members.put(tile.coords(), member(tile, false));
        while (!open.isEmpty()) {
            var at = open.poll();
            for (int direction = 0; direction < 6; direction++) {
                if ((at.cover().exits() & 1 << direction) == 0) { continue; }
                var next = scene.neighbor(at, direction);
                if (next == null || members.containsKey(next.coords())) { continue; }
                if (next.cover().structure() == 0) {
                    members.put(next.coords(), member(next, true));
                } else if (ConnectedKit.back(scene, at, direction)
                      && next.features().stream().anyMatch(feature -> feature.asset().equals(asset))) {
                    members.put(next.coords(), member(next, false));
                    open.add(next);
                }
            }
        }
        return List.copyOf(members.values());
    }

    /** The hexes of the dome {@code tile} is part of, which one fit shapes together; none for any other hex. */
    static List<Coords> domed(BoardScene scene, BoardScene.Tile tile) {
        for (var feature : tile.features()) {
            if (supports(feature.asset())) {
                return group(scene, tile, feature.asset()).stream().map(Member::coords).toList();
            }
        }
        return List.of();
    }

    private static Member member(BoardScene.Tile tile, boolean ghost) {
        return new Member(tile.coords(), tile.elevation(), tile.elevation() + Math.max(1, tile.cover().height()), ghost);
    }

    /**
     * The domes a building's heights shape: one over each peak, standing hexes of one top that no standing neighbour
     * rises above. Each centres on the peak's hex centre or corner farthest from the footprint's outline (ties to the
     * one nearest the peak's middle; a collapsed hex still counts in the outline); its podium's corners lie that far in
     * less {@link #DOME_MARGIN}, shrunk in proportion where two domes' podiums would meet; its apex is the peak's top.
     * Where the glass meets the podium's roof follows the heights of the hexes it covers: the cap that best passes
     * through their tops, the podium no lower than {@link #RING_LOW} and the glass at least a fifth of its radius high.
     * The order of {@code group} does not matter, so every hex of a building fits the same domes. Heights are model
     * units over the highest ground under the building.
     */
    static List<Fit> fits(Collection<Member> group) {
        List<Member> members = group.stream().sorted(Comparator.comparingInt((Member member) -> member.coords().getX())
              .thenComparingInt(member -> member.coords().getY())).toList();
        Map<Coords, Member> byCoords = new HashMap<>();
        members.forEach(member -> byCoords.put(member.coords(), member));
        List<float[]> outline = new ArrayList<>();
        int base = Integer.MIN_VALUE;
        for (Member member : members) {
            for (int direction = 0; direction < 6; direction++) {
                if (byCoords.containsKey(member.coords().translated(direction))) { continue; }
                int edge = Math.floorMod(1 - direction, 6);
                Coords coords = member.coords();
                outline.add(new float[] { HexTile.cornerX(coords, edge, WIDTH), HexTile.cornerY(coords, edge, HEIGHT),
                      HexTile.cornerX(coords, (edge + 1) % 6, WIDTH), HexTile.cornerY(coords, (edge + 1) % 6, HEIGHT) });
            }
            base = Math.max(base, member.elevation());
        }
        List<Member> standing = members.stream().filter(member -> !member.ghost()).toList();
        if (standing.isEmpty()) { throw new IllegalArgumentException("A building needs a standing hex"); }
        // Peaks: plateaus of one top, none of whose standing neighbours stands higher.
        Set<Coords> seen = new java.util.HashSet<>();
        List<float[]> centres = new ArrayList<>();
        for (Member first : standing) {
            if (!seen.add(first.coords())) { continue; }
            List<Member> plateau = new ArrayList<>();
            var open = new ArrayDeque<>(List.of(first));
            boolean peak = true;
            while (!open.isEmpty()) {
                var member = open.poll();
                plateau.add(member);
                for (int direction = 0; direction < 6; direction++) {
                    var next = byCoords.get(member.coords().translated(direction));
                    if (next == null || next.ghost()) { continue; }
                    if (next.top() > member.top()) { peak = false; }
                    else if (next.top() == member.top() && seen.add(next.coords())) { open.add(next); }
                }
            }
            if (peak) { centres.add(centre(plateau, outline)); }
        }
        // Podiums that would meet shrink in proportion, leaving a walkway between.
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < centres.size(); i++) {
                for (int j = i + 1; j < centres.size(); j++) {
                    float[] a = centres.get(i), b = centres.get(j);
                    float apart = (float) Math.hypot(a[0] - b[0], a[1] - b[1]) - 4;
                    if (a[2] + b[2] > apart) {
                        float share = Math.max(0, apart) / (a[2] + b[2]);
                        a[2] *= share;
                        b[2] *= share;
                    }
                }
            }
        }
        List<Fit> result = new ArrayList<>();
        for (float[] centre : centres) {
            float podium = centre[2];
            float radius = podium * (float) Math.cos(Math.PI / 12) - Math.clamp(podium * PODIUM_WIDTH, 9, 30);
            if (radius < MIN_DOME) { continue; }
            float apex = (centre[3] - base) * LEVEL;
            // The tops of the standing hexes this dome is nearest, within its podium.
            List<float[]> samples = new ArrayList<>();
            for (Member member : standing) {
                float x = HexTile.centerX(member.coords(), WIDTH), y = HexTile.centerY(member.coords(), HEIGHT);
                float from = (float) Math.hypot(x - centre[0], y - centre[1]);
                boolean nearest = centres.stream().noneMatch(other -> other != centre
                      && Math.hypot(x - other[0], y - other[1]) < from);
                if (nearest && from <= podium) { samples.add(new float[] { from, (member.top() - base) * LEVEL }); }
            }
            float ring = ring(radius, apex, samples);
            result.add(new Fit(centre[0], centre[1], radius, base, ring, apex, frequency(radius, apex - ring), podium));
        }
        return List.copyOf(result);
    }

    /** The podium's lowest roof over its plinth, and its highest: two storeys. */
    static final float RING_LOW = PLINTH + 7, RING_HIGH = PLINTH + 2 * LEVEL;

    /**
     * Where the glass of a dome with this foot radius and apex meets its podium's roof: the cap whose heights best
     * match the samples' (distance from the centre, top) by least squares, a sample beyond the foot taking the podium's
     * roof; a dome over its peak alone rises {@link #DOME_RISE} of its radius.
     */
    private static float ring(float radius, float apex, List<float[]> samples) {
        float low = Math.min(RING_HIGH, Math.max(RING_LOW, apex - radius)), high = Math.min(RING_HIGH, apex - radius * .2f);
        if (high <= low) { return Math.max(RING_LOW, Math.min(apex - 1, low)); }
        if (samples.stream().allMatch(sample -> sample[1] >= apex)) {
            return Math.clamp(apex - radius * DOME_RISE, low, high);
        }
        float best = low, error = Float.POSITIVE_INFINITY;
        for (int step = 0; step <= 48; step++) {
            float ring = low + (high - low) * step / 48, rise = apex - ring;
            float sphere = (radius * radius + rise * rise) / (2 * rise), sum = 0;
            for (float[] sample : samples) {
                float z = sample[0] >= radius ? ring
                      : apex - sphere + (float) Math.sqrt(Math.max(0, sphere * sphere - sample[0] * sample[0]));
                sum += (z - sample[1]) * (z - sample[1]);
            }
            if (sum < error - EPSILON) {
                error = sum;
                best = ring;
            }
        }
        return best;
    }

    /**
     * A peak's dome centre: its hex centre or corner farthest from the footprint's outline, ties to the one nearest its
     * middle; with that clearance less {@link #DOME_MARGIN} and the peak's top.
     */
    private static float[] centre(List<Member> plateau, List<float[]> outline) {
        Set<List<Float>> candidates = new TreeSet<>(Comparator.<List<Float>, Float>comparing(point -> point.get(0))
              .thenComparing(point -> point.get(1)));
        float middleX = 0, middleY = 0;
        for (Member member : plateau) {
            Coords coords = member.coords();
            middleX += HexTile.centerX(coords, WIDTH) / plateau.size();
            middleY += HexTile.centerY(coords, HEIGHT) / plateau.size();
            candidates.add(List.of(HexTile.centerX(coords, WIDTH), HexTile.centerY(coords, HEIGHT)));
            for (int corner = 0; corner < 6; corner++) {
                candidates.add(List.of(HexTile.cornerX(coords, corner, WIDTH), HexTile.cornerY(coords, corner, HEIGHT)));
            }
        }
        float bestX = 0, bestY = 0, best = -1, bestMiddle = Float.POSITIVE_INFINITY;
        for (var candidate : candidates) {
            float x = candidate.get(0), y = candidate.get(1);
            float clearance = Float.POSITIVE_INFINITY;
            for (float[] edge : outline) { clearance = Math.min(clearance, distance(x, y, edge)); }
            float middle = (x - middleX) * (x - middleX) + (y - middleY) * (y - middleY);
            if (clearance > best + EPSILON || clearance > best - EPSILON && middle < bestMiddle - EPSILON) {
                best = clearance;
                bestMiddle = middle;
                bestX = x;
                bestY = y;
            }
        }
        return new float[] { bestX, bestY, best - DOME_MARGIN, plateau.getFirst().top() };
    }

    private static float distance(float x, float y, float[] edge) {
        float dx = edge[2] - edge[0], dy = edge[3] - edge[1];
        float t = Math.clamp(((x - edge[0]) * dx + (y - edge[1]) * dy) / (dx * dx + dy * dy), 0, 1);
        return (float) Math.hypot(x - edge[0] - t * dx, y - edge[1] - t * dy);
    }

    /** An even frequency whose struts are about {@link #STRUT_LENGTH} long on this cap. */
    private static int frequency(float radius, float rise) {
        if (radius <= 0) { return 4; }
        float sphere = (radius * radius + rise * rise) / (2 * rise);
        float cap = (float) Math.asin(Math.min(1, radius / sphere));
        // An icosahedron's edge spans 1.107 radians; the cap squeezes a hemisphere's polar angle into its own.
        int frequency = Math.round(1.1071f * sphere * cap / ((float) Math.PI / 2) / STRUT_LENGTH / 2) * 2;
        return Math.clamp(frequency, 4, 16);
    }

    /** The hex's share at a level of detail, without its transparent materials ({@link #glass}), with its gear. */
    static RigidGlb.Data model(Layout layout, int lod) {
        if (lod < 0 || lod >= RigidGlb.LEVELS) { throw new IllegalArgumentException("Invalid multi-hex building LOD: " + lod); }
        var data = mesh(layout, Math.min(lod, LODS - 1)).data(layout + "-lod" + lod, false);
        var kit = equipment();
        if (!layout.gear().isEmpty() && !kit.lods().isEmpty()) {
            int level = Math.min(lod, kit.lods().size() - 1);
            var source = kit.lods().get(level);
            ConnectedKit.materials(data, source);
            for (int index = 0; index < layout.gear().size(); index++) {
                var gear = layout.gear().get(index);
                ConnectedKit.append(data, source, ConnectedKit.node(source, gear.piece() + "-lod" + level), frame(gear),
                      gear.piece() + "@" + index + "/");
            }
        }
        return data;
    }

    /**
     * The share's transparent materials ({@link GodotMaterial#blended}), drawn after everything opaque without casting
     * shadows; null without any. One level: glass panes keep their shape at every distance.
     */
    static RigidGlb.Data glass(Layout layout) {
        var mesh = mesh(layout, 0);
        return mesh.has(true) ? mesh.data(layout + "-glass", true) : null;
    }

    private static Mesh mesh(Layout layout, int lod) {
        var mesh = new Mesh();
        float[] reach = new float[6];
        for (int direction = 0; direction < 6; direction++) { reach[direction] = layout.reach().get(direction); }
        if (layout.domes().isEmpty()) {
            // A footprint too narrow for any dome, or an annex without a module of its own, stands at its own height.
            shell(mesh, layout.top(), reach);
        }
        if (!layout.domes().isEmpty() || layout.annex()) { skirt(mesh, layout, lod); }
        for (Dome dome : layout.domes()) {
            if (layout.annex()) {
                for (int at = 0; at < dome.modules().size(); at += 4) { module(mesh, hex(), dome, at); }
                continue;
            }
            dome(mesh, dome, lod);
            if (layout.design() == Design.BIODOME) { garden(mesh, dome); }
        }
        return mesh;
    }

    /** Exact near-level triangles: picking and clearance. */
    static List<Vector3> triangles(Layout layout) {
        List<Vector3> result = new ArrayList<>();
        for (var mesh : model(layout, 0).meshes) {
            for (var part : mesh.parts) {
                for (short index : part.indices) {
                    int offset = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                    result.add(new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1], mesh.vertices[offset + 2]));
                }
            }
        }
        return result;
    }

    // ---- The plinth ----------------------------------------------------------------------------------------------------

    /** How far the plinth's foundation reaches past a podium's outline: under its pylons and fins. */
    private static final float SKIRT = 10;

    /**
     * The domes' plinth under what stands on it alone, cut to the hex, so the regolith shows between, as round a real
     * colony's buildings: a foundation round each podium, pads under its annexes' modules and under each airlock; and
     * the flexible tubes joining the airlocks to the podium or to their annex's gatehouse.
     */
    private static void skirt(Mesh mesh, Layout layout, int lod) {
        List<float[]> hex = hex();
        var kit = equipment();
        for (Dome dome : layout.domes()) {
            float top = dome.foot(), flat = dome.podium() * (float) Math.cos(Math.PI / 12);
            List<float[]> foundation = new ArrayList<>(12);
            float corner = (flat + SKIRT) / (float) Math.cos(Math.PI / 12);
            for (int k = 0; k < 12; k++) {
                double angle = Math.PI / 6 * k;
                foundation.add(new float[] { dome.x() + corner * (float) Math.cos(angle), dome.y() + corner * (float) Math.sin(angle) });
            }
            prism(mesh, hex, PLINTH_MATERIAL, foundation, 0, top, foundation.size());
            var modules = dome.modules();
            for (int at = 0; at < modules.size(); at += 4) {
                float ux = modules.get(at), uy = modules.get(at + 1), outer = modules.get(at + 2);
                float inner = ringMiddle(dome.radius(), dome.podium());
                List<float[]> pad = moduleOutline(dome.x(), dome.y(), ux, uy, inner, outer + 2, MODULE_HALF + HEAD + 2,
                      MODULE_CHAMFER);
                prism(mesh, hex, PLINTH_MATERIAL, pad, 0, top, pad.size());
                // Under its wider root too.
                List<float[]> root = moduleOutline(dome.x(), dome.y(), ux, uy, inner,
                      Math.min(outer, flat + ROOT_REACH) + 2, MODULE_HALF + ROOT + 2, MODULE_CHAMFER);
                prism(mesh, hex, PLINTH_MATERIAL, root, 0, top, root.size());
            }
            // Each airlock's tube, out of the podium square to it and bending round to the airlock's back.
            var entrances = dome.entrances();
            for (int at = 0; at < entrances.size(); at += 5) {
                float x = entrances.get(at), y = entrances.get(at + 1), from = (float) Math.hypot(x, y), start = flat - 1;
                if (from <= start) { continue; }
                bellows(mesh, hex, new float[] { dome.x() + x / from * start, dome.y() + y / from * start, x / from, y / from,
                      top }, new float[] { dome.x() + x, dome.y() + y, entrances.get(at + 2), entrances.get(at + 3),
                      top + entrances.get(at + 4) }, lod);
            }
        }
        // Under each raised airlock; on an annex its tube bends from its gatehouse's door to it, where it stands ahead of
        // the door, else runs in from it to the gatehouse over the annex's centre.
        for (Gear gear : layout.gear()) {
            if (!gear.piece().equals("airlock")) { continue; }
            List<float[]> pad = grown(footprint(kit, gear), 2);
            if (gear.z() > EPSILON && !pad.isEmpty()) { prism(mesh, hex, PLINTH_MATERIAL, pad, 0, gear.z(), pad.size()); }
            float from = (float) Math.hypot(gear.x(), gear.y());
            if (!layout.annex() || from <= EPSILON) { continue; }
            double facing = Math.toRadians(gear.angle());
            float[] airlock = { gear.x(), gear.y(), (float) Math.sin(facing), (float) Math.cos(facing), gear.z() };
            float[] door = door(layout);
            if (door != null && (gear.x() - door[0]) * door[2] + (gear.y() - door[1]) * door[3] > 3) {
                bellows(mesh, hex, door, airlock, lod);
            } else if (door != null) {
                bellows(mesh, hex, new float[] { 0, 0, airlock[2], airlock[3], gear.z() }, airlock, lod);
            }
        }
    }

    /**
     * The blast door of the annex's own gatehouse ({@link #module}), its tube's start in front of it as {@link #bellows}
     * takes it; null where the annex has no module.
     */
    private static float[] door(Layout layout) {
        for (Dome dome : layout.domes()) {
            var modules = dome.modules();
            for (int at = 0; at < modules.size(); at += 4) {
                float ux = modules.get(at), uy = modules.get(at + 1), outer = modules.get(at + 2) + 1;
                // Its own: the module's line runs through the annex's centre, its gatehouse over it.
                float apart = -(dome.x() * ux + dome.y() * uy);
                if (Math.abs(dome.x() * uy - dome.y() * ux) < 1 && apart > 0 && outer - apart <= GATEHOUSE / 2 + 2) {
                    return new float[] { dome.x() + ux * outer, dome.y() + uy * outer, ux, uy, dome.foot() };
                }
            }
        }
        return null;
    }

    /**
     * A connecting tube's radius and its ribs' depth and pitch: a flexible duct a little narrower than an airlock; the
     * room a gatehouse keeps from an annex's airlocks for it.
     */
    private static final float TUBE = 5, RIB = .5f, PITCH = 2, AIRLOCK_ROOM = 9;

    /**
     * A flexible pressurised tube, cut to the hex: a ribbed duct on a curve from {@code from} to {@code to} (each x, y,
     * the way the tube runs there, and its floor), heavy collars at its ends, saddles under it down to the ground.
     */
    private static void bellows(Mesh mesh, List<float[]> hex, float[] from, float[] to, int lod) {
        float chord = (float) Math.hypot(to[0] - from[0], to[1] - from[1]), reach = chord * .4f;
        if (chord < 3) { return; }
        // A cubic curve leaving and arriving along the given ways, measured finely for even ribs.
        float[][] curve = { { from[0], from[1] }, { from[0] + from[2] * reach, from[1] + from[3] * reach },
              { to[0] - to[2] * reach, to[1] - to[3] * reach }, { to[0], to[1] } };
        int samples = 64;
        float[] lengths = new float[samples + 1];
        float[] last = point(curve, 0);
        for (int sample = 1; sample <= samples; sample++) {
            float[] next = point(curve, (float) sample / samples);
            lengths[sample] = lengths[sample - 1] + (float) Math.hypot(next[0] - last[0], next[1] - last[1]);
            last = next;
        }
        float total = lengths[samples];
        int stations = Math.max(6, Math.round(total / (PITCH / 2)) + 1), sides = lod == 0 ? 14 : 8;
        float[][] centres = new float[stations][], along = new float[stations][], across = new float[stations][];
        float[][] ups = new float[stations][];
        float[] radii = new float[stations], distances = new float[stations];
        float climb = (to[4] - from[4]) / total;
        for (int station = 0; station < stations; station++) {
            float distance = total * station / (stations - 1), t = 1;
            for (int sample = 1; sample <= samples; sample++) {
                if (lengths[sample] >= distance) {
                    float share = (distance - lengths[sample - 1]) / Math.max(1e-6f, lengths[sample] - lengths[sample - 1]);
                    t = (sample - 1 + share) / samples;
                    break;
                }
            }
            float[] at = point(curve, t), slope = tangent(curve, t);
            float flat = (float) Math.hypot(slope[0], slope[1]), tx = slope[0] / flat, ty = slope[1] / flat;
            float floor = from[4] + (to[4] - from[4]) * distance / total, length = (float) Math.sqrt(1 + climb * climb);
            centres[station] = new float[] { at[0], at[1], floor + TUBE + .4f };
            along[station] = new float[] { tx / length, ty / length, climb / length };
            across[station] = new float[] { ty, -tx, 0 };
            // Up, square to the tube: across x along.
            float[] a = along[station];
            ups[station] = new float[] { -tx * a[2], -ty * a[2], ty * a[1] + tx * a[0] };
            distances[station] = distance;
            // Collars at the ends, ribs between.
            radii[station] = station <= 1 || station >= stations - 2 ? TUBE + 1.1f : station % 2 == 0 ? TUBE : TUBE + RIB;
        }
        for (int band = 0; band < stations - 1; band++) {
            String material = band == 0 || band == stations - 2 ? PIER : FRAME;
            float flare = (radii[band + 1] - radii[band]) / Math.max(1e-4f, distances[band + 1] - distances[band]);
            for (int side = 0; side < sides; side++) {
                float[] a = ring(centres, along, across, ups, radii, distances, band, side, sides, flare);
                float[] b = ring(centres, along, across, ups, radii, distances, band, side + 1, sides, flare);
                float[] c = ring(centres, along, across, ups, radii, distances, band + 1, side + 1, sides, flare);
                float[] d = ring(centres, along, across, ups, radii, distances, band + 1, side, sides, flare);
                outward(mesh, material, hex, a, b, c);
                outward(mesh, material, hex, a, c, d);
            }
        }
        // Saddles every few metres, clear of the collars.
        for (int station = 3; station < stations - 3; station += 3) {
            float[] centre = centres[station], x = across[station];
            float bottom = centre[2] - TUBE;
            if (bottom > .3f) {
                // Square to the tube on the ground: across turned back a quarter.
                clippedBlock(mesh, hex, PIER, centre[0], centre[1], -x[1], x[0], .5f, TUBE * .8f, 0, bottom + .4f);
            }
        }
    }

    /** A vertex of the tube's ring at {@code station}, {@code side} of {@code sides} round it, its normal leaning with the rib. */
    private static float[] ring(float[][] centres, float[][] along, float[][] across, float[][] ups, float[] radii,
          float[] distances, int station, int side, int sides, float flare) {
        double angle = 2 * Math.PI * side / sides;
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle), r = radii[station];
        float[] centre = centres[station], a = along[station], x = across[station], u = ups[station];
        float rx = x[0] * c + u[0] * s, ry = x[1] * c + u[1] * s, rz = x[2] * c + u[2] * s;
        float z = centre[2] + rz * r;
        return vertex(centre[0] + rx * r, centre[1] + ry * r, z, rx - a[0] * flare, ry - a[1] * flare, rz - a[2] * flare,
              contact(z), distances[station] / 8, (float) side / sides * 2);
    }

    /** A triangle of a tube's wall, wound to face along its vertices' normals. */
    private static void outward(Mesh mesh, String material, List<float[]> hex, float[] a, float[] b, float[] c) {
        float ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2], fx = c[0] - a[0], fy = c[1] - a[1], fz = c[2] - a[2];
        float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        if (nx * (a[3] + b[3] + c[3]) + ny * (a[4] + b[4] + c[4]) + nz * (a[5] + b[5] + c[5]) < 0) {
            mesh.clipped(material, hex, a, c, b);
        } else {
            mesh.clipped(material, hex, a, b, c);
        }
    }

    /** The cubic curve through {@code curve}'s four points at {@code t}. */
    private static float[] point(float[][] curve, float t) {
        float s = 1 - t, a = s * s * s, b = 3 * s * s * t, c = 3 * s * t * t, d = t * t * t;
        return new float[] { a * curve[0][0] + b * curve[1][0] + c * curve[2][0] + d * curve[3][0],
              a * curve[0][1] + b * curve[1][1] + c * curve[2][1] + d * curve[3][1] };
    }

    /** The cubic curve's direction at {@code t}. */
    private static float[] tangent(float[][] curve, float t) {
        float s = 1 - t, a = 3 * s * s, b = 6 * s * t, c = 3 * t * t;
        float x = a * (curve[1][0] - curve[0][0]) + b * (curve[2][0] - curve[1][0]) + c * (curve[3][0] - curve[2][0]);
        float y = a * (curve[1][1] - curve[0][1]) + b * (curve[2][1] - curve[1][1]) + c * (curve[3][1] - curve[2][1]);
        // A curve folded on itself at an end: its chord's way.
        return Math.hypot(x, y) > 1e-5 ? new float[] { x, y }
              : new float[] { curve[3][0] - curve[0][0], curve[3][1] - curve[0][1] };
    }

    /** A convex, counter-clockwise outline grown by {@code by} on every edge (corners stay sharp). */
    private static List<float[]> grown(List<float[]> outline, float by) {
        return outline.isEmpty() ? outline : parapets(outline, -by);
    }

    // ---- The shell -----------------------------------------------------------------------------------------------------

    /** The hex outline about its centre, counter-clockwise from the east corner (BoardGeometry#modelCorner). */
    private static List<float[]> hex() {
        List<float[]> result = new ArrayList<>(6);
        for (int corner = 0; corner < 6; corner++) {
            result.add(new float[] { HexTile.cornerOffsetX(corner, WIDTH), HexTile.cornerOffsetY(corner, HEIGHT) });
        }
        return result;
    }

    /** The outward normal and offset of the hexside towards {@code direction}: n.p = offset along it. */
    private static float[] side(int direction) {
        int edge = Math.floorMod(1 - direction, 6);
        float ax = HexTile.cornerOffsetX(edge, WIDTH), ay = HexTile.cornerOffsetY(edge, HEIGHT);
        float bx = HexTile.cornerOffsetX((edge + 1) % 6, WIDTH), by = HexTile.cornerOffsetY((edge + 1) % 6, HEIGHT);
        float length = (float) Math.hypot(bx - ax, by - ay);
        float nx = (by - ay) / length, ny = -(bx - ax) / length;
        return new float[] { nx, ny, nx * ax + ny * ay, ax, ay };
    }

    /** {@code polygon} (convex, counter-clockwise) where n.p <= offset, or >= offset with {@code beyond}. */
    private static List<float[]> clip(List<float[]> polygon, float nx, float ny, float offset, boolean beyond) {
        List<float[]> result = new ArrayList<>(polygon.size() + 1);
        float sign = beyond ? -1 : 1;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            float da = sign * (nx * a[0] + ny * a[1] - offset), db = sign * (nx * b[0] + ny * b[1] - offset);
            if (da <= 0) { result.add(a); }
            if (da < 0 != db < 0 && da != 0 && db != 0) {
                float t = da / (da - db);
                float[] cut = new float[a.length];
                for (int k = 0; k < a.length; k++) { cut[k] = a[k] + (b[k] - a[k]) * t; }
                result.add(cut);
            }
        }
        return result.size() < 3 ? List.of() : result;
    }

    /** The hex with the hexsides of {@code closed} (direction bits) moved in by {@code inset}. */
    private static List<float[]> outline(int closed, float inset) {
        List<float[]> result = hex();
        for (int direction = 0; direction < 6; direction++) {
            if ((closed & 1 << direction) == 0) { continue; }
            float[] side = side(direction);
            result = clip(result, side[0], side[1], side[2] - inset, false);
        }
        return result;
    }

    /**
     * The domes' plinth in bands between the heights where a connection ends: in each, the facades of the hexsides closed
     * there stand on the outline's set-back edges; where the next band closes more hexsides a ledge covers the difference;
     * the top band carries the deck.
     */
    private static void shell(Mesh mesh, float top, float[] reach) {
        TreeSet<Float> breaks = new TreeSet<>(List.of(0f, top));
        for (float connected : reach) { if (connected > EPSILON && connected < top - EPSILON) { breaks.add(connected); } }
        List<Float> heights = List.copyOf(breaks);
        for (int band = 0; band < heights.size() - 1; band++) {
            float bottom = heights.get(band), ceiling = heights.get(band + 1);
            int closed = closed(reach, bottom);
            List<float[]> outline = outline(closed, SETBACK);
            walls(mesh, PLINTH_MATERIAL, outline, closed, SETBACK, bottom, ceiling);
            if (band == heights.size() - 2) {
                flat(mesh, PLINTH_MATERIAL, outline, ceiling, 1);
                continue;
            }
            int next = closed(reach, ceiling);
            List<float[]> remaining = outline;
            for (int direction = 0; direction < 6; direction++) {
                if ((next & ~closed & 1 << direction) == 0) { continue; }
                float[] side = side(direction);
                flat(mesh, PLINTH_MATERIAL, clip(remaining, side[0], side[1], side[2] - SETBACK, true), ceiling, .9f);
                remaining = clip(remaining, side[0], side[1], side[2] - SETBACK, false);
            }
        }
    }

    private static int closed(float[] reach, float height) {
        int result = 0;
        for (int direction = 0; direction < 6; direction++) {
            if (reach[direction] <= height + EPSILON) { result |= 1 << direction; }
        }
        return result;
    }

    /** The facades on the edges of {@code outline} that lie on a closed hexside's line moved in by {@code inset}. */
    private static void walls(Mesh mesh, String material, List<float[]> outline, int closed, float inset, float bottom,
          float ceiling) {
        for (int i = 0; i < outline.size(); i++) {
            float[] a = outline.get(i), b = outline.get((i + 1) % outline.size());
            float[] side = facadeSide(a, b, closed, inset);
            if (side != null) { facade(mesh, material, a, b, side, bottom, ceiling); }
        }
    }

    /** The closed hexside ({@link #side}) whose line moved in by {@code inset} the edge a to b lies on; null for none. */
    private static float[] facadeSide(float[] a, float[] b, int closed, float inset) {
        for (int direction = 0; direction < 6; direction++) {
            if ((closed & 1 << direction) == 0) { continue; }
            float[] side = side(direction);
            float offset = side[2] - inset;
            if (Math.abs(side[0] * a[0] + side[1] * a[1] - offset) <= EPSILON
                  && Math.abs(side[0] * b[0] + side[1] * b[1] - offset) <= EPSILON) { return side; }
        }
        return null;
    }

    /** A corner pier's half width, how far a pier stands out, its chamfer; its footing's. */
    private static final float CORNER_PIER = 2.6f, PIER_DEPTH = 2.1f, PIER_CHAMFER = .75f, FOOTING_GROW = .9f,
          FOOTING_DEPTH = 3.2f, FOOTING = 2.4f;
    /** A pier facing (nx, ny) at (x, y) on a facade, its back buried in the wall even at a corner; on a footing. */
    private static void pier(Mesh mesh, float x, float y, float nx, float ny, float half, float bottom, float top,
          boolean footing) {
        float tx = -ny, ty = nx, back = -(.6f * half + .3f);
        float[][] profile = { { half, back }, { half, PIER_DEPTH - PIER_CHAMFER }, { half - PIER_CHAMFER, PIER_DEPTH },
              { -half + PIER_CHAMFER, PIER_DEPTH }, { -half, PIER_DEPTH - PIER_CHAMFER }, { -half, back } };
        List<float[]> ring = new ArrayList<>(profile.length);
        for (float[] point : profile) { ring.add(new float[] { x + tx * point[0] + nx * point[1], y + ty * point[0] + ny * point[1] }); }
        // The front face takes the plate's whole width, the chamfers and sides its edge strip.
        for (int i = 0; i < ring.size() - 1; i++) {
            float[] a = ring.get(i), b = ring.get(i + 1);
            float[] normal = outward(a, b, x, y);
            float u = i == 2 ? 1 : .12f;
            face(mesh, null, PIER, a, b, normal[0], normal[1], bottom, top, 0, u);
        }
        flat(mesh, PIER, counterClockwise(ring), top, 1);
        if (footing) {
            float grown = half + FOOTING_GROW;
            List<float[]> base = new ArrayList<>(4);
            for (float[] point : new float[][] { { grown, back }, { grown, FOOTING_DEPTH }, { -grown, FOOTING_DEPTH }, { -grown, back } }) {
                base.add(new float[] { x + tx * point[0] + nx * point[1], y + ty * point[0] + ny * point[1] });
            }
            prism(mesh, null, PLINTH_MATERIAL, base, bottom, bottom + FOOTING, 3);
        }
    }

    /**
     * An upright prism over {@code outline} (convex) from {@code bottom} to {@code top}, its roof and the faces of its
     * first {@code faces} edges, cut to the hex unless that is null; facades' texture, one repeat per {@link #WALL_REPEAT}.
     */
    private static void prism(Mesh mesh, List<float[]> hex, String material, List<float[]> outline, float bottom,
          float top, int faces) {
        float cx = 0, cy = 0;
        for (float[] point : outline) { cx += point[0] / outline.size(); cy += point[1] / outline.size(); }
        float along = 0;
        for (int i = 0; i < faces; i++) {
            float[] a = outline.get(i), b = outline.get((i + 1) % outline.size());
            float[] normal = outward(a, b, cx, cy);
            float next = along + (float) Math.hypot(b[0] - a[0], b[1] - a[1]) / WALL_REPEAT;
            face(mesh, hex, material, a, b, normal[0], normal[1], bottom, top, along, next);
            along = next;
        }
        if (hex == null) { flat(mesh, material, counterClockwise(outline), top, 1); }
        else { clippedFlat(mesh, hex, material, counterClockwise(outline), top); }
    }

    /** The horizontal unit normal of the edge a to b that points away from (x, y). */
    private static float[] outward(float[] a, float[] b, float x, float y) {
        float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        float nx = (b[1] - a[1]) / length, ny = -(b[0] - a[0]) / length;
        return nx * ((a[0] + b[0]) / 2 - x) + ny * ((a[1] + b[1]) / 2 - y) < 0 ? new float[] { -nx, -ny } : new float[] { nx, ny };
    }

    /** {@code polygon} counter-clockwise. */
    private static List<float[]> counterClockwise(List<float[]> polygon) {
        float area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area >= 0 ? polygon : polygon.reversed();
    }

    /** One facade from a to b (counter-clockwise round the share), along hexside {@code side} and facing out from it. */
    private static void facade(Mesh mesh, String material, float[] a, float[] b, float[] side, float bottom, float ceiling) {
        // Texture along the hexside from its first corner, so every band of the facade lines up.
        float tx = -side[1], ty = side[0];
        float ua = ((a[0] - side[3]) * tx + (a[1] - side[4]) * ty) / WALL_REPEAT;
        float ub = ((b[0] - side[3]) * tx + (b[1] - side[4]) * ty) / WALL_REPEAT;
        float nx = side[0], ny = side[1];
        // V runs down the image as height rises: each repeat stands upright, its floor band low.
        float[] a0 = vertex(a[0], a[1], bottom, nx, ny, 0, contact(bottom), ua, -bottom / WALL_REPEAT);
        float[] b0 = vertex(b[0], b[1], bottom, nx, ny, 0, contact(bottom), ub, -bottom / WALL_REPEAT);
        float[] b1 = vertex(b[0], b[1], ceiling, nx, ny, 0, contact(ceiling), ub, -ceiling / WALL_REPEAT);
        float[] a1 = vertex(a[0], a[1], ceiling, nx, ny, 0, contact(ceiling), ua, -ceiling / WALL_REPEAT);
        mesh.quad(material, a0, b0, b1, a1);
    }

    /** A block centred at (x, y), {@code along} and {@code across} half sizes along (rx, ry) and across it. */
    private static void block(Mesh mesh, String material, float x, float y, float rx, float ry, float along, float across,
          float bottom, float top) {
        List<float[]> corners = new ArrayList<>(4);
        for (float[] sign : new float[][] { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) {
            corners.add(new float[] { x + rx * along * sign[0] - ry * across * sign[1],
                  y + ry * along * sign[0] + rx * across * sign[1] });
        }
        flat(mesh, material, corners, top, 1);
        for (int i = 0; i < 4; i++) {
            float[] a = corners.get(i), b = corners.get((i + 1) % 4);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float nx = (b[1] - a[1]) / length, ny = -(b[0] - a[0]) / length;
            float[] a0 = vertex(a[0], a[1], bottom, nx, ny, 0, .8f, 0, 0), b0 = vertex(b[0], b[1], bottom, nx, ny, 0, .8f, length / 12, 0);
            float[] b1 = vertex(b[0], b[1], top, nx, ny, 0, 1, length / 12, (top - bottom) / 12);
            float[] a1 = vertex(a[0], a[1], top, nx, ny, 0, 1, 0, (top - bottom) / 12);
            mesh.quad(material, a0, b0, b1, a1);
        }
    }

    /** The darkening where a facade meets the ground: dust and contact shadow over its first metres. */
    private static float contact(float z) { return .72f + .28f * Math.min(1, Math.max(0, z / 8)); }

    /**
     * A horizontal polygon at {@code z}, textured along the board's axes, one repeat a column's width across and half a
     * hex up: every hex centre lies on a whole repeat, so roofs and decks continue across hexes without a per-hex offset.
     */
    private static void flat(Mesh mesh, String material, List<float[]> polygon, float z, float shade) {
        if (polygon.isEmpty()) { return; }
        List<float[]> vertices = new ArrayList<>(polygon.size());
        for (float[] point : polygon) { vertices.add(deck(point[0], point[1], z, shade)); }
        for (int i = 1; i < vertices.size() - 1; i++) { mesh.triangle(material, vertices.get(0), vertices.get(i), vertices.get(i + 1)); }
    }

    /** {@link #flat}, cut to the hex: a part of a dome's building over whichever shares it stands. */
    private static void clippedFlat(Mesh mesh, List<float[]> hex, String material, List<float[]> polygon, float z) {
        float[] first = deck(polygon.getFirst()[0], polygon.getFirst()[1], z, 1);
        for (int i = 1; i < polygon.size() - 1; i++) {
            mesh.clipped(material, hex, first, deck(polygon.get(i)[0], polygon.get(i)[1], z, 1),
                  deck(polygon.get(i + 1)[0], polygon.get(i + 1)[1], z, 1));
        }
    }

    /** A point of a horizontal surface, textured along the board's axes ({@link #flat}). */
    private static float[] deck(float x, float y, float z, float shade) {
        return vertex(x, y, z, 0, 0, 1, shade, x / (WIDTH * .75f), -y / (HEIGHT / 2));
    }

    // ---- The dome ------------------------------------------------------------------------------------------------------

    /**
     * A geodesic cap: a vertex-up icosphere's upper half (an even frequency puts its equator on a vertex ring), its
     * polar angle squeezed onto the cap, glass panels between struts, and a ring beam at its foot; only what lies over
     * this hex is kept.
     */
    private static void dome(Mesh mesh, Dome dome, int lod) {
        float radius = dome.radius(), sphere = dome.sphere(), centreZ = dome.apex() - sphere;
        float cap = (float) Math.asin(Math.min(1, radius / sphere));
        var geodesic = Geodesic.hemisphere(dome.frequency());
        List<Vector3> points = new ArrayList<>(geodesic.points().size());
        for (Vector3 unit : geodesic.points()) {
            float polar = (float) Math.acos(Math.clamp(unit.z, 0, 1)) * cap / ((float) Math.PI / 2);
            float azimuth = (float) Math.atan2(unit.y, unit.x);
            points.add(new Vector3(dome.x() + sphere * (float) (Math.sin(polar) * Math.cos(azimuth)),
                  dome.y() + sphere * (float) (Math.sin(polar) * Math.sin(azimuth)),
                  centreZ + sphere * (float) Math.cos(polar)));
        }
        Vector3 centre = new Vector3(dome.x(), dome.y(), centreZ);
        List<float[]> hex = hex();
        for (int[] face : geodesic.faces()) {
            Vector3 a = points.get(face[0]), b = points.get(face[1]), c = points.get(face[2]);
            // The sphere's own normals: a reflection runs across the panels as over one curved shell.
            mesh.clipped(GLASS, hex, panel(a, centre, dome), panel(b, centre, dome), panel(c, centre, dome));
        }
        for (int at = 0; at < dome.modules().size(); at += 4) { module(mesh, hex, dome, at); }
        podium(mesh, hex, dome);
        for (int[] edge : geodesic.edges()) { strut(mesh, hex, points.get(edge[0]), points.get(edge[1]), centre, lod); }
        ring(mesh, hex, dome, lod);
    }

    private static float[] panel(Vector3 point, Vector3 centre, Dome dome) {
        Vector3 normal = new Vector3(point).sub(centre).nor();
        return vertex(point.x, point.y, point.z, normal.x, normal.y, normal.z, 1, (point.x - dome.x()) / 24,
              (point.y - dome.y()) / 24);
    }

    /**
     * The podium: a ring building between the dome's ring beam and a twelve-sided outline, faced with the annexes'
     * panels inside and out, roofed in regolith concrete with a service unit over each side; yellow buttress fins at
     * its six corners between the pylons and the middle of each side, where no entrance wing arrives.
     */
    private static void podium(Mesh mesh, List<float[]> hex, Dome dome) {
        int sides = 12, steps = 4, segments = sides * steps;
        float inner = dome.radius() + 1.4f, corner = dome.podium(), bottom = dome.foot(), top = dome.foot() + dome.height();
        float flat = corner * (float) Math.cos(Math.PI / sides);
        float x = dome.x(), y = dome.y();
        float perimeter = 0;
        for (int segment = 0; segment < segments; segment++) {
            double a = 2 * Math.PI * segment / segments, b = 2 * Math.PI * (segment + 1) / segments;
            // The outline's point at each angle: on its side's straight line, the corners at multiples of 30 degrees.
            float oa = flat / (float) Math.cos(a - Math.PI / sides * (2 * Math.floor(a / (2 * Math.PI / sides)) + 1));
            float ob = flat / (float) Math.cos(b - Math.PI / sides * (2 * Math.floor((b - 1e-6) / (2 * Math.PI / sides)) + 1));
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b);
            float ax = x + oa * ca, ay = y + oa * sa, bx = x + ob * cb, by = y + ob * sb;
            float length = (float) Math.hypot(bx - ax, by - ay);
            float nx = (by - ay) / length, ny = -(bx - ax) / length;
            float u0 = perimeter / WALL_REPEAT, u1 = (perimeter + length) / WALL_REPEAT;
            perimeter += length;
            float v0 = -bottom / WALL_REPEAT, v1 = -top / WALL_REPEAT;
            float[] w0 = vertex(ax, ay, bottom, nx, ny, 0, contact(0), u0, v0), w1 = vertex(bx, by, bottom, nx, ny, 0, contact(0), u1, v0);
            float[] w2 = vertex(bx, by, top, nx, ny, 0, 1, u1, v1), w3 = vertex(ax, ay, top, nx, ny, 0, 1, u0, v1);
            mesh.clipped(WALL, hex, w0, w1, w2);
            mesh.clipped(WALL, hex, w0, w2, w3);
            // Its roof, from the outline in to the ring beam, in the lattice's coordinates as the plinth's deck.
            float[][] roof = { { ax, ay }, { bx, by }, { x + inner * cb, y + inner * sb }, { x + inner * ca, y + inner * sa } };
            float[][] corners = new float[4][];
            for (int i = 0; i < 4; i++) { corners[i] = deck(roof[i][0], roof[i][1], top, 1); }
            mesh.clipped(PLINTH_MATERIAL, hex, corners[0], corners[1], corners[2]);
            mesh.clipped(PLINTH_MATERIAL, hex, corners[0], corners[2], corners[3]);
            // Its inner wall faces the dome, under the glass.
            float[] i0 = vertex(x + inner * cb, y + inner * sb, bottom, -cb, -sb, 0, contact(0), u1, v0);
            float[] i1 = vertex(x + inner * ca, y + inner * sa, bottom, -ca, -sa, 0, contact(0), u0, v0);
            float[] i2 = vertex(x + inner * ca, y + inner * sa, top, -ca, -sa, 0, 1, u0, v1);
            float[] i3 = vertex(x + inner * cb, y + inner * sb, top, -cb, -sb, 0, 1, u1, v1);
            mesh.clipped(WALL, hex, i0, i1, i2);
            mesh.clipped(WALL, hex, i0, i2, i3);
        }
        // Steel piers on footings at the six corners between the pylons, where no wing arrives: the share each stands
        // over draws it.
        for (int pier = 0; pier < 6; pier++) {
            double angle = Math.toRadians(60 * pier);
            float px = x + corner * (float) Math.cos(angle), py = y + corner * (float) Math.sin(angle);
            if (inside(hex, px, py) && !dome.joined(angle, 0)) {
                pier(mesh, px, py, (float) Math.cos(angle), (float) Math.sin(angle), CORNER_PIER, bottom, top + .6f, true);
            }
        }
        for (int fin = 0; fin < 18; fin++) {
            // The six corners between the pylons, then the twelve sides' middles, set in to the side's line.
            double angle = Math.toRadians(fin < 6 ? 60 * fin : 15 + 30 * (fin - 6));
            if (!dome.joined(angle, 0)) { fin(mesh, hex, dome, (float) angle, fin < 6 ? corner : flat, bottom, top); }
        }
        // The ring roof in bays, one a side, as the colony's ring buildings are: steel ribs on the corners' radial lines
        // and a steel rim along the outline, and in each bay its plant, two or three dark units and housings of
        // different sizes along the ring, chosen per bay and stable for it.
        float depth = flat - inner, middle = (inner + flat) / 2, rib = .45f, rim = .7f;
        for (int k = 0; k < sides; k++) {
            double angle = 2 * Math.PI * k / sides;
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle), reach = (corner - inner) / 2;
            clippedBlock(mesh, hex, PIER, x + ux * (inner + reach), y + uy * (inner + reach), ux, uy, reach, rib, top,
                  top + .55f);
        }
        float half = corner * (float) Math.sin(Math.PI / sides);
        for (int side = 0; side < sides; side++) {
            double angle = Math.PI / sides * (2 * side + 1);
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
            clippedBlock(mesh, hex, PIER, x + ux * (flat - rim), y + uy * (flat - rim), -uy, ux, half - rim, rim, top,
                  top + .9f);
            // Seeded alike in every share the bay crosses (dome.x/y are each share's own).
            var random = new SplittableRandom(Float.floatToIntBits(dome.podium()) * 31L + Float.floatToIntBits(dome.radius()) * 17L + side);
            int units = 2 + random.nextInt(2);
            float room = (half - 2) * 2 * (inner + depth * .5f) / flat, span = room / units;
            for (int unit = 0; unit < units; unit++) {
                float along = Math.min(span * .5f - 1, Math.min(7, depth * .4f)) * (.55f + .4f * random.nextFloat());
                float across = Math.min(depth * .5f - 1.2f, along * (.5f + .5f * random.nextFloat()));
                float at = -room / 2 + span * (unit + .5f), out = (random.nextFloat() - .5f) * (depth * .5f - 1.2f - across);
                float cx = x + ux * (middle + out) - uy * at, cy = y + uy * (middle + out) + ux * at;
                boolean housing = random.nextFloat() < .35f;
                float rise = housing ? 1.6f + random.nextFloat() : .8f + 1.4f * random.nextFloat();
                if (along > .8f && across > .8f) {
                    clippedBlock(mesh, hex, housing ? FRAME : PIER, cx, cy, -uy, ux, along, across, top, top + rise);
                }
            }
        }
    }

    /** {@link #block}, cut to the hex: a podium's parts belong to whichever shares they stand over. */
    private static void clippedBlock(Mesh mesh, List<float[]> hex, String material, float x, float y, float rx, float ry,
          float along, float across, float bottom, float top) {
        var part = new Mesh();
        block(part, material, x, y, rx, ry, along, across, bottom, top);
        FloatArray triangles = part.parts.get(material);
        int stride = RigidGlb.STRIDE;
        for (int at = 0; at < triangles.size; at += 3 * stride) {
            float[][] corner = new float[3][];
            for (int k = 0; k < 3; k++) {
                corner[k] = new float[stride];
                System.arraycopy(triangles.items, at + k * stride, corner[k], 0, stride);
            }
            mesh.clipped(material, hex, corner[0], corner[1], corner[2]);
        }
    }

    /** A buttress fin standing out from the podium's corner at {@code angle}: a yellow wedge, tall at the wall. */
    private static void fin(Mesh mesh, List<float[]> hex, Dome dome, float angle, float corner, float bottom, float top) {
        float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle), cx = -uy, cy = ux, half = .8f;
        float wall = corner - 1, foot = corner + FIN, height = (top - bottom) * .7f;
        float[][] at = new float[2][];
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? 1 : -1, nx = cx * sign, ny = cy * sign;
            float ox = dome.x() + cx * half * sign, oy = dome.y() + cy * half * sign;
            float[] low = vertex(ox + ux * wall, oy + uy * wall, bottom, nx, ny, 0, .8f, 0, 0);
            float[] tip = vertex(ox + ux * foot, oy + uy * foot, bottom, nx, ny, 0, .8f, .5f, 0);
            float[] high = vertex(ox + ux * wall, oy + uy * wall, bottom + height, nx, ny, 0, 1, 0, .5f);
            if (side == 0) { mesh.clipped(FIN_MATERIAL, hex, low, high, tip); } else { mesh.clipped(FIN_MATERIAL, hex, low, tip, high); }
            at[side] = new float[] { ox, oy };
        }
        // The sloping back between the two faces.
        float sx = (FIN) , sz = height, slope = (float) Math.hypot(sx, sz), nz = sx / slope, nr = sz / slope;
        float[] a = vertex(at[0][0] + ux * wall, at[0][1] + uy * wall, bottom + height, ux * nr, uy * nr, nz, 1, 0, 0);
        float[] b = vertex(at[1][0] + ux * wall, at[1][1] + uy * wall, bottom + height, ux * nr, uy * nr, nz, 1, .1f, 0);
        float[] c = vertex(at[1][0] + ux * foot, at[1][1] + uy * foot, bottom, ux * nr, uy * nr, nz, 1, .1f, .5f);
        float[] d = vertex(at[0][0] + ux * foot, at[0][1] + uy * foot, bottom, ux * nr, uy * nr, nz, 1, 0, .5f);
        mesh.clipped(FIN_MATERIAL, hex, a, b, c);
        mesh.clipped(FIN_MATERIAL, hex, a, c, d);
    }

    /**
     * An annex's module ({@link Dome#modules}), cut to the hex: a wing of the ring building on the line from the dome's
     * centre through the annex's, from the middle of the ring out to a wider gatehouse over the annex's centre, its root
     * wider where it meets the ring; on the gatehouse one machinery cluster and its conduit, now and then a lit roof
     * light, a blast door under a yellow lintel in its outer face and yellow braces at its outer corners.
     */
    private static void module(Mesh mesh, List<float[]> hex, Dome dome, int at) {
        var module = dome.modules();
        float ux = module.get(at), uy = module.get(at + 1), outer = module.get(at + 2), rise = module.get(at + 3);
        float bottom = dome.foot(), top = bottom + rise, cx = dome.x(), cy = dome.y(), px = -uy, py = ux;
        float inner = ringMiddle(dome.radius(), dome.podium()), flat = dome.podium() * (float) Math.cos(Math.PI / 12);
        float head = Math.max(inner, outer - GATEHOUSE);
        moduleBlock(mesh, hex, moduleOutline(cx, cy, ux, uy, inner, Math.min(outer, flat + ROOT_REACH),
              MODULE_HALF + ROOT, MODULE_CHAMFER), bottom, Math.min(top, bottom + dome.height()));
        if (head > inner + EPSILON) {
            // The neck a step lower than the gatehouse.
            moduleBlock(mesh, hex, moduleOutline(cx, cy, ux, uy, inner, head + MODULE_CHAMFER, MODULE_HALF, MODULE_CHAMFER),
                  bottom, top - Math.min(6, rise * .25f));
        }
        moduleBlock(mesh, hex, moduleOutline(cx, cy, ux, uy, head, outer, MODULE_HALF + HEAD, MODULE_CHAMFER), bottom, top);
        float side = MODULE_HALF + HEAD - BEVEL - 1;
        // Chosen by the module, alike in every share it crosses.
        var random = new SplittableRandom(Float.floatToIntBits(ux) * 31L + Float.floatToIntBits(uy) * 17L
              + Float.floatToIntBits(outer));
        // The machinery cluster on the gatehouse: a pale housing between two dark units, a conduit to the door.
        float spot = head + (outer - head) * .35f, flank = random.nextFloat() < .5f ? 1 : -1;
        clippedBlock(mesh, hex, FRAME, cx + ux * spot, cy + uy * spot, ux, uy, 4.5f, 5, top, top + 3 + random.nextFloat());
        for (int unit = -1; unit <= 1; unit += 2) {
            float across = unit * 9.5f;
            clippedBlock(mesh, hex, PIER, cx + ux * spot + px * across, cy + uy * spot + py * across, ux, uy, 3, 2.4f, top,
                  top + 1.4f + .8f * random.nextFloat());
        }
        float conduit = (outer - BEVEL - 2 + spot + 5) / 2, length = (outer - BEVEL - 2 - spot - 5) / 2;
        if (length > 1) {
            clippedBlock(mesh, hex, PIER, cx + ux * conduit + px * flank * (side - 1), cy + uy * conduit + py * flank * (side - 1),
                  ux, uy, length, .7f, top, top + .9f);
        }
        if (random.nextFloat() < .5f) {
            float light = head + (outer - head) * .75f;
            clippedBlock(mesh, hex, SKYLIGHT, cx + ux * light - px * flank * 3, cy + uy * light - py * flank * 3, ux, uy,
                  3.5f, 4, top, top + .7f);
        }
        // The blast door: a dark steel frame round a pale door under a yellow lintel.
        float door = Math.min(15, rise - 3), fx = cx + ux * outer, fy = cy + uy * outer;
        clippedBlock(mesh, hex, PIER, fx + ux * .5f, fy + uy * .5f, px, py, 7.5f, 1, bottom, bottom + door);
        clippedBlock(mesh, hex, FRAME, fx + ux * 1.1f, fy + uy * 1.1f, px, py, 5.5f, .6f, bottom, bottom + door - 1.8f);
        clippedBlock(mesh, hex, FIN_MATERIAL, fx + ux * .6f, fy + uy * .6f, px, py, 7.5f, 1.2f, bottom + door,
              bottom + door + 1.3f);
        // Yellow braces at the foot of its outer corners.
        for (int corner = -1; corner <= 1; corner += 2) {
            float along = outer - MODULE_CHAMFER / 2, across = corner * (MODULE_HALF + HEAD - MODULE_CHAMFER / 2);
            float nx = (ux + px * corner) * .7071f, ny = (uy + py * corner) * .7071f;
            float bx = cx + ux * along + px * across + nx * 1.2f, by = cy + uy * along + py * across + ny * 1.2f;
            clippedBlock(mesh, hex, FIN_MATERIAL, bx, by, nx, ny, 1.4f, 1.1f, bottom, bottom + Math.min(10, rise * .4f));
        }
    }

    /**
     * A module's block over {@code walls} (#moduleOutline), cut to the hex: panelled faces, its cut corners in dark
     * steel, under a bevelled steel cap, its roof at {@code top}.
     */
    private static void moduleBlock(Mesh mesh, List<float[]> hex, List<float[]> walls, float bottom, float top) {
        List<float[]> roof = parapets(walls, BEVEL);
        float along = 0, slope = (float) Math.hypot(BEVEL, BEVEL_RISE);
        for (int edge = 0; edge < walls.size(); edge++) {
            int next = (edge + 1) % walls.size();
            float[] a = walls.get(edge), b = walls.get(next), c = roof.get(edge), d = roof.get(next);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float nx = (b[1] - a[1]) / length, ny = -(b[0] - a[0]) / length;
            // The odd edges are the cut corners (#moduleOutline).
            face(mesh, hex, edge % 2 == 1 ? PIER : WALL, a, b, nx, ny, bottom, top - BEVEL_RISE, along / WALL_REPEAT,
                  (along + length) / WALL_REPEAT);
            along += length;
            float sx = nx * BEVEL_RISE / slope, sy = ny * BEVEL_RISE / slope, sz = BEVEL / slope;
            float[] va = vertex(a[0], a[1], top - BEVEL_RISE, sx, sy, sz, 1, a[0] / 24, a[1] / 24);
            float[] vb = vertex(b[0], b[1], top - BEVEL_RISE, sx, sy, sz, 1, b[0] / 24, b[1] / 24);
            float[] vd = vertex(d[0], d[1], top, sx, sy, sz, 1, d[0] / 24, d[1] / 24);
            float[] vc = vertex(c[0], c[1], top, sx, sy, sz, 1, c[0] / 24, c[1] / 24);
            mesh.clipped(FRAME, hex, va, vb, vd);
            mesh.clipped(FRAME, hex, va, vd, vc);
        }
        clippedFlat(mesh, hex, ROOF, roof, top);
    }

    /**
     * A wall from a to b between two heights, facing (nx, ny), cut to the hex unless that is null; its texture from
     * {@code u0} to {@code u1} along it, one repeat per {@link #WALL_REPEAT} up it.
     */
    private static void face(Mesh mesh, List<float[]> hex, String material, float[] a, float[] b, float nx, float ny,
          float bottom, float top, float u0, float u1) {
        float[] a0 = vertex(a[0], a[1], bottom, nx, ny, 0, contact(0), u0, -bottom / WALL_REPEAT);
        float[] b0 = vertex(b[0], b[1], bottom, nx, ny, 0, contact(0), u1, -bottom / WALL_REPEAT);
        float[] b1 = vertex(b[0], b[1], top, nx, ny, 0, 1, u1, -top / WALL_REPEAT);
        float[] a1 = vertex(a[0], a[1], top, nx, ny, 0, 1, u0, -top / WALL_REPEAT);
        // Wound to face (nx, ny): a0, b0, b1 faces the right of a to b.
        float[][] corners = (b[1] - a[1]) * nx - (b[0] - a[0]) * ny > 0
              ? new float[][] { a0, b0, b1, a1 } : new float[][] { b0, a0, a1, b1 };
        if (hex == null) {
            mesh.quad(material, corners[0], corners[1], corners[2], corners[3]);
        } else {
            mesh.clipped(material, hex, corners[0], corners[1], corners[2]);
            mesh.clipped(material, hex, corners[0], corners[2], corners[3]);
        }
    }

    /** A strut standing proud of the glass: its outer face and, near, its two sides. */
    private static void strut(Mesh mesh, List<float[]> hex, Vector3 a, Vector3 b, Vector3 centre, int lod) {
        Vector3 along = new Vector3(b).sub(a);
        float length = along.len();
        along.scl(1 / length);
        Vector3 out = new Vector3(a).add(b).scl(.5f).sub(centre).nor();
        Vector3 side = new Vector3(along).crs(out).nor().scl(STRUT_WIDTH / 2);
        Vector3 rise = new Vector3(out).scl(STRUT_OUT), sink = new Vector3(out).scl(-STRUT_IN);
        quad(mesh, hex, a, b, new Vector3(side).add(rise), new Vector3(side).scl(-1).add(rise), out, length);
        if (lod > 0) { return; }
        Vector3 right = new Vector3(side).nor(), left = new Vector3(right).scl(-1);
        quad(mesh, hex, a, b, new Vector3(side).add(sink), new Vector3(side).add(rise), right, length);
        quad(mesh, hex, a, b, new Vector3(side).scl(-1).add(rise), new Vector3(side).scl(-1).add(sink), left, length);
    }

    /** A strut face from a to b between two offsets, wound to face {@code normal}. */
    private static void quad(Mesh mesh, List<float[]> hex, Vector3 a, Vector3 b, Vector3 first, Vector3 second,
          Vector3 normal, float length) {
        Vector3 a0 = new Vector3(a).add(first), b0 = new Vector3(b).add(first);
        Vector3 b1 = new Vector3(b).add(second), a1 = new Vector3(a).add(second);
        float[][] corners = {
              vertex(a0.x, a0.y, a0.z, normal.x, normal.y, normal.z, 1, 0, 0),
              vertex(b0.x, b0.y, b0.z, normal.x, normal.y, normal.z, 1, length / 12, 0),
              vertex(b1.x, b1.y, b1.z, normal.x, normal.y, normal.z, 1, length / 12, .1f),
              vertex(a1.x, a1.y, a1.z, normal.x, normal.y, normal.z, 1, 0, .1f) };
        Vector3 facing = new Vector3(b0).sub(a0).crs(new Vector3(b1).sub(a0));
        if (facing.dot(normal) < 0) {
            mesh.clipped(FRAME, hex, corners[0], corners[3], corners[2]);
            mesh.clipped(FRAME, hex, corners[0], corners[2], corners[1]);
        } else {
            mesh.clipped(FRAME, hex, corners[0], corners[1], corners[2]);
            mesh.clipped(FRAME, hex, corners[0], corners[2], corners[3]);
        }
    }

    /** The ring beam the dome's struts stand on, a low box round its foot. */
    private static void ring(Mesh mesh, List<float[]> hex, Dome dome, int lod) {
        int segments = Math.max(24, dome.frequency() * 10);
        float inner = dome.radius() - 1.4f, outer = dome.radius() + 1.4f, bottom = dome.ring(), top = dome.ring() + 2.4f;
        for (int segment = 0; segment < segments; segment++) {
            double a = 2 * Math.PI * segment / segments, b = 2 * Math.PI * (segment + 1) / segments;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), cb = (float) Math.cos(b), sb = (float) Math.sin(b);
            float u0 = (float) (a * dome.radius() / 12), u1 = (float) (b * dome.radius() / 12);
            float x = dome.x(), y = dome.y();
            float[] oa0 = vertex(x + outer * ca, y + outer * sa, bottom, ca, sa, 0, .8f, u0, 0);
            float[] ob0 = vertex(x + outer * cb, y + outer * sb, bottom, cb, sb, 0, .8f, u1, 0);
            float[] ob1 = vertex(x + outer * cb, y + outer * sb, top, cb, sb, 0, 1, u1, .2f);
            float[] oa1 = vertex(x + outer * ca, y + outer * sa, top, ca, sa, 0, 1, u0, .2f);
            mesh.clipped(FRAME, hex, oa0, ob0, ob1);
            mesh.clipped(FRAME, hex, oa0, ob1, oa1);
            float[] ta = vertex(x + outer * ca, y + outer * sa, top, 0, 0, 1, 1, u0, 0);
            float[] tb = vertex(x + outer * cb, y + outer * sb, top, 0, 0, 1, 1, u1, 0);
            float[] tc = vertex(x + inner * cb, y + inner * sb, top, 0, 0, 1, 1, u1, .2f);
            float[] td = vertex(x + inner * ca, y + inner * sa, top, 0, 0, 1, 1, u0, .2f);
            mesh.clipped(FRAME, hex, ta, tb, tc);
            mesh.clipped(FRAME, hex, ta, tc, td);
            // A concrete collar round the ring beam, lower than it: the foot's stepped base.
            float collar = dome.radius() + 4.6f, step = dome.foot() + 1.6f;
            mesh.clipped(PLINTH_MATERIAL, hex, vertex(x + collar * ca, y + collar * sa, bottom, ca, sa, 0, .75f, u0, 0),
                  vertex(x + collar * cb, y + collar * sb, bottom, cb, sb, 0, .75f, u1, 0),
                  vertex(x + collar * cb, y + collar * sb, step, cb, sb, 0, .95f, u1, .13f));
            mesh.clipped(PLINTH_MATERIAL, hex, vertex(x + collar * ca, y + collar * sa, bottom, ca, sa, 0, .75f, u0, 0),
                  vertex(x + collar * cb, y + collar * sb, step, cb, sb, 0, .95f, u1, .13f),
                  vertex(x + collar * ca, y + collar * sa, step, ca, sa, 0, .95f, u0, .13f));
            mesh.clipped(PLINTH_MATERIAL, hex, vertex(x + collar * ca, y + collar * sa, step, 0, 0, 1, 1, u0, 0),
                  vertex(x + collar * cb, y + collar * sb, step, 0, 0, 1, 1, u1, 0),
                  vertex(x + outer * cb, y + outer * sb, step, 0, 0, 1, 1, u1, .27f));
            mesh.clipped(PLINTH_MATERIAL, hex, vertex(x + collar * ca, y + collar * sa, step, 0, 0, 1, 1, u0, 0),
                  vertex(x + outer * cb, y + outer * sb, step, 0, 0, 1, 1, u1, .27f),
                  vertex(x + outer * ca, y + outer * sa, step, 0, 0, 1, 1, u0, .27f));
            if (lod > 0) { continue; }
            float[] ia0 = vertex(x + inner * ca, y + inner * sa, bottom, -ca, -sa, 0, .8f, u0, 0);
            float[] ib0 = vertex(x + inner * cb, y + inner * sb, bottom, -cb, -sb, 0, .8f, u1, 0);
            float[] ib1 = vertex(x + inner * cb, y + inner * sb, top, -cb, -sb, 0, 1, u1, .2f);
            float[] ia1 = vertex(x + inner * ca, y + inner * sa, top, -ca, -sa, 0, 1, u0, .2f);
            mesh.clipped(FRAME, hex, ib0, ia0, ia1);
            mesh.clipped(FRAME, hex, ib0, ia1, ib1);
        }
    }

    /** A biodome's garden floor: just above the plinth's deck. */
    private static float floor(Dome dome) { return dome.foot() + .4f; }

    /** A biodome's planted floor inside its ring. */
    private static void garden(Mesh mesh, Dome dome) {
        int segments = Math.max(24, dome.frequency() * 10);
        float radius = dome.radius() - 1.4f, z = floor(dome);
        float[] middle = vertex(dome.x(), dome.y(), z, 0, 0, 1, 1, 0, 0);
        List<float[]> hex = hex();
        for (int segment = 0; segment < segments; segment++) {
            double a = 2 * Math.PI * segment / segments, b = 2 * Math.PI * (segment + 1) / segments;
            float ax = radius * (float) Math.cos(a), ay = radius * (float) Math.sin(a);
            float bx = radius * (float) Math.cos(b), by = radius * (float) Math.sin(b);
            mesh.clipped(GARDEN, hex, middle, vertex(dome.x() + ax, dome.y() + ay, z, 0, 0, 1, 1, ax / 24, ay / 24),
                  vertex(dome.x() + bx, dome.y() + by, z, 0, 0, 1, 1, bx / 24, by / 24));
        }
    }

    /** A vertex in RigidGlb's layout: position, normal, colour (grey shade, opaque), UV. */
    private static float[] vertex(float x, float y, float z, float nx, float ny, float nz, float shade, float u, float v) {
        return new float[] { x, y, z, nx, ny, nz, shade, shade, shade, 1, u, v };
    }

    /** The upper half of a vertex-up icosphere on the unit sphere: points, outward faces and their unique edges. */
    record Geodesic(List<Vector3> points, List<int[]> faces, List<int[]> edges) {
        private static final Map<Integer, Geodesic> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        static Geodesic hemisphere(int frequency) {
            if (frequency < 2 || frequency % 2 != 0) { throw new IllegalArgumentException("Frequency must be even: " + frequency); }
            return CACHE.computeIfAbsent(frequency, Geodesic::build);
        }

        private static Geodesic build(int frequency) {
            List<Vector3> corners = new ArrayList<>();
            corners.add(new Vector3(0, 0, 1));
            double ringZ = 1 / Math.sqrt(5), ringRadius = 2 / Math.sqrt(5);
            for (int i = 0; i < 5; i++) {
                double upper = Math.toRadians(72 * i), lower = Math.toRadians(72 * i + 36);
                corners.add(new Vector3((float) (ringRadius * Math.cos(upper)), (float) (ringRadius * Math.sin(upper)), (float) ringZ));
                corners.add(new Vector3((float) (ringRadius * Math.cos(lower)), (float) (ringRadius * Math.sin(lower)), (float) -ringZ));
            }
            List<int[]> base = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int upper = 1 + 2 * i, lower = 2 + 2 * i, nextUpper = 1 + 2 * ((i + 1) % 5), nextLower = 2 + 2 * ((i + 1) % 5);
                base.add(new int[] { 0, upper, nextUpper });
                base.add(new int[] { upper, lower, nextUpper });
                base.add(new int[] { nextUpper, lower, nextLower });
            }
            List<Vector3> points = new ArrayList<>();
            Map<List<Integer>, Integer> index = new HashMap<>();
            List<int[]> faces = new ArrayList<>();
            for (int[] triangle : base) {
                Vector3 a = corners.get(triangle[0]), b = corners.get(triangle[1]), c = corners.get(triangle[2]);
                int[][] grid = new int[frequency + 1][];
                for (int i = 0; i <= frequency; i++) {
                    grid[i] = new int[frequency + 1 - i];
                    for (int j = 0; j <= frequency - i; j++) {
                        Vector3 point = new Vector3(a).scl(1 - (float) (i + j) / frequency)
                              .mulAdd(b, (float) i / frequency).mulAdd(c, (float) j / frequency).nor();
                        if (Math.abs(point.z) < 1e-5f) { point.z = 0; point.nor(); }
                        var key = List.of(Math.round(point.x * 1e5f), Math.round(point.y * 1e5f), Math.round(point.z * 1e5f));
                        grid[i][j] = index.computeIfAbsent(key, ignored -> {
                            points.add(point);
                            return points.size() - 1;
                        });
                    }
                }
                for (int i = 0; i < frequency; i++) {
                    for (int j = 0; j < frequency - i; j++) {
                        add(faces, points, grid[i][j], grid[i + 1][j], grid[i][j + 1]);
                        if (j < frequency - i - 1) { add(faces, points, grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]); }
                    }
                }
            }
            Set<List<Integer>> seen = new java.util.HashSet<>();
            List<int[]> edges = new ArrayList<>();
            for (int[] face : faces) {
                for (int k = 0; k < 3; k++) {
                    int p = face[k], q = face[(k + 1) % 3];
                    if (seen.add(List.of(Math.min(p, q), Math.max(p, q)))) { edges.add(new int[] { p, q }); }
                }
            }
            return new Geodesic(List.copyOf(points), List.copyOf(faces), List.copyOf(edges));
        }

        /** Keeps a face of the upper half, wound to face outward. */
        private static void add(List<int[]> faces, List<Vector3> points, int a, int b, int c) {
            Vector3 pa = points.get(a), pb = points.get(b), pc = points.get(c);
            if (pa.z + pb.z + pc.z < -1e-4f) { return; }
            Vector3 normal = new Vector3(pb).sub(pa).crs(new Vector3(pc).sub(pa));
            faces.add(normal.dot(new Vector3(pa).add(pb).add(pc)) < 0 ? new int[] { a, c, b } : new int[] { a, b, c });
        }
    }

    // ---- Assembly ------------------------------------------------------------------------------------------------------

    /** Triangles by board material, in RigidGlb's vertex layout, unindexed. */
    private static final class Mesh {
        private final Map<String, FloatArray> parts = new LinkedHashMap<>();

        void triangle(String material, float[] a, float[] b, float[] c) {
            var part = parts.computeIfAbsent(material, ignored -> new FloatArray());
            part.addAll(a);
            part.addAll(b);
            part.addAll(c);
        }

        void quad(String material, float[] a, float[] b, float[] c, float[] d) {
            triangle(material, a, b, c);
            triangle(material, a, c, d);
        }

        /** The part of a triangle over the hex (its outline about the centre), attributes interpolated. */
        void clipped(String material, List<float[]> hex, float[] a, float[] b, float[] c) {
            List<float[]> polygon = List.of(a, b, c);
            for (int corner = 0; corner < 6 && !polygon.isEmpty(); corner++) {
                float[] p = hex.get(corner), q = hex.get((corner + 1) % 6);
                float length = (float) Math.hypot(q[0] - p[0], q[1] - p[1]);
                float nx = (q[1] - p[1]) / length, ny = -(q[0] - p[0]) / length;
                polygon = clip(polygon, nx, ny, nx * p[0] + ny * p[1], false);
            }
            for (int i = 1; i < polygon.size() - 1; i++) {
                triangle(material, normalised(polygon.get(0)), normalised(polygon.get(i)), normalised(polygon.get(i + 1)));
            }
        }

        private static float[] normalised(float[] vertex) {
            float length = (float) Math.sqrt(vertex[3] * vertex[3] + vertex[4] * vertex[4] + vertex[5] * vertex[5]);
            if (length > 0 && Math.abs(length - 1) > 1e-4f) {
                vertex = vertex.clone();
                vertex[3] /= length; vertex[4] /= length; vertex[5] /= length;
            }
            return vertex;
        }

        /** Whether any part is (or, with {@code blended} false, is not) a transparent board material. */
        boolean has(boolean blended) {
            return parts.keySet().stream().anyMatch(material -> blended(material) == blended);
        }

        private static boolean blended(String material) {
            return BLENDED.computeIfAbsent(material, name -> GpuMaterialMaps.board(name).blended());
        }

        /** The parts whose board material is transparent, or those that are not. */
        RigidGlb.Data data(String id, boolean blended) {
            var data = new RigidGlb.Data();
            data.id = id;
            var vertices = new FloatArray();
            var mesh = new ModelMesh();
            mesh.id = "multihex";
            mesh.attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(),
                  VertexAttribute.ColorUnpacked(), VertexAttribute.TexCoords(0) };
            var node = new ModelNode();
            node.id = "multihex";
            node.meshId = mesh.id;
            node.children = new ModelNode[0];
            List<ModelMeshPart> meshParts = new ArrayList<>();
            List<ModelNodePart> nodeParts = new ArrayList<>();
            parts.forEach((material, part) -> {
                if (blended(material) != blended) { return; }
                int first = vertices.size / RigidGlb.STRIDE, count = part.size / RigidGlb.STRIDE;
                if (first + count > 65_536) { throw new IllegalStateException("Multi-hex building share too large: " + id); }
                vertices.addAll(part);
                var meshPart = new ModelMeshPart();
                meshPart.id = material;
                meshPart.primitiveType = GL20.GL_TRIANGLES;
                meshPart.indices = new short[count];
                for (int i = 0; i < count; i++) { meshPart.indices[i] = (short) (first + i); }
                meshParts.add(meshPart);
                RigidGlb.boardMaterial(data, material, material);
                var nodePart = new ModelNodePart();
                nodePart.meshPartId = material;
                nodePart.materialId = material;
                nodeParts.add(nodePart);
            });
            mesh.vertices = vertices.toArray();
            mesh.parts = meshParts.toArray(ModelMeshPart[]::new);
            node.parts = nodeParts.toArray(ModelNodePart[]::new);
            data.meshes.add(mesh);
            data.nodes.add(node);
            return data;
        }
    }
}
