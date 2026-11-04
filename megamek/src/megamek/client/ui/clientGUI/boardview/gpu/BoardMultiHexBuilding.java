/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GeneratedMesh.clip;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
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

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.EarClippingTriangulator;
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
 * and neighbouring shells meet exactly on their hexsides. A corner's owning facade keeps its complete pillar relief
 * across the cut. The domes stand on a plinth over the building, fitted to its
 * heights ({@link #fits}), each hex taking the part over its own hex; a hex outside every podium is an annex: the
     * plinth and, at its own height, a corridor following connected sides or a single terminal module ({@link #module}). The lunar habitats are a connected kit instead
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
          BEVEL = 3.8f, BEVEL_RISE = 2.2f;
    /** Geodesic strut length the frequency aims at, and the struts' cross-section. */
    private static final float STRUT_LENGTH = 16, STRUT_WIDTH = .75f, STRUT_OUT = .5f, STRUT_IN = .4f;
    private static final float LEVEL = GpuBuilding.LEVEL_HEIGHT;
    private static final float WIDTH = HexTile.MODEL_WIDTH, HEIGHT = HexTile.HEIGHT;
    /** Two panel rows per texture repeat: one framed cladding bay per storey. */
    private static final float WALL_REPEAT = 2 * LEVEL;
    /** Two 4.5-unit roof plates per repeat. Fittings have bounded UVs of their own. */
    private static final float ROOF_REPEAT = 9;
    private static final float EPSILON = 1e-3f;

    // Board materials (textures/<name>.tres); their .tres files give every finish, transparency included.
    static final String WALL = "structures/lunar/dome-cladding", ROOF = "structures/lunar/dome-deck",
          PLINTH_MATERIAL = "structures/lunar/regolith-concrete", FRAME = "structures/lunar/dome-frame",
          GLASS = "structures/lunar/dome-glass", GARDEN = "structures/lunar/biodome-garden",
          PIER = "structures/common/matte-steel", HARDWARE = "structures/lunar/dome-trim", WINDOW = "structures/lunar/dome-window",
          LIGHT = "structures/lunar/dome-light", MARKER = "structures/lunar/dome-marker";

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
     * the base is a vertex ring), and its building's entrances, seven numbers each: x and y relative to its centre (a
     * gate's door, an airlock's back), the way it faces, its floor above the foot, 1 for a vehicle gate ({@link #gate})
     * and 0 for an airlock, whose tube ({@link #bellows}) joins the podium, and how far an annex's gate stands from the
     * annex's centre (0: a gate of the podium, {@link #passage}), the ring building
     * round its foot whose twelve-sided outline has its corners {@code podium} from the centre; {@code modules}, four
     * numbers each: an annex's module ({@link #module}), its direction from the centre, how far its outer end lies from
     * it and its rise above the foot.
     */
    record Dome(float x, float y, float radius, float foot, float ring, float apex, int frequency, float podium,
          List<Float> entrances, List<Float> modules, List<Point> ports, List<Float> pierFeet) {
        Dome {
            entrances = List.copyOf(entrances);
            modules = List.copyOf(modules);
            ports = List.copyOf(ports);
            pierFeet = List.copyOf(pierFeet);
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
            for (Point port : ports) {
                double toward = Math.atan2(port.y(), port.x());
                if (Math.abs(Math.IEEEremainder(toward - angle, 2 * Math.PI)) < Math.atan2(MODULE_HALF + 3, podium) + margin) { return true; }
            }
            for (int at = 0; at < entrances.size(); at += 7) {
                // Its gate or its airlock's tube enters the podium towards it.
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
    record Layout(Design design, float top, List<Float> reach, List<Dome> domes, List<Gear> gear, boolean annex,
          List<Tunnel> tunnels, List<Gate> corridorGates) {
        Layout {
            domes = List.copyOf(domes);
            reach = List.copyOf(reach);
            gear = List.copyOf(gear);
            tunnels = List.copyOf(tunnels);
            corridorGates = List.copyOf(corridorGates);
        }
    }

    record Point(float x, float y) { }

    record Gate(float x, float y, float ux, float uy, float floor) { }

    /** A facade support owned by one share; turns retain both adjoining wall normals, flat posts repeat one. */
    record Corner(Point at, Point normal, Point side, float bottom, boolean external, boolean flat) { }

    /** A winding-aware turn of a board-space outline, before assigning its exposed height and material. */
    private record Turn(Point at, Point normal, Point side, boolean convex) { }

    /** One corridor outline clipped to this hex; each edge opens up to its neighbour's roof, or zero for a wall. */
    record Tunnel(List<Point> outline, List<Float> openings, float foot, float top, Point origin, List<Corner> corners) {
        Tunnel { outline = List.copyOf(outline); openings = List.copyOf(openings); corners = List.copyOf(corners); }
    }

    private record Link(Point from, Point to, int firstDome, int secondDome, float top) { }

    /** Temporary board-space footprint used to union corridors and solid three-way junctions before clipping. */
    private record Span(Area footprint, float top) { }

    /** One connected corridor footprint and the roof actually drawn over it, before opening its shared cuts. */
    private record Corridor(List<Point> outline, float top) { }

    /** A support piece on the share: its node in the equipment kit, its origin, its turn clockwise in degrees, its size. */
    record Gear(String piece, float x, float y, float z, float angle, float scale) {
        Gear(String piece, float x, float y, float z, float angle) { this(piece, x, y, z, angle, 1); }
    }

    /** The support pieces' kit (tools/buildings/lunar_equipment.py) and each piece's near bounds. */
    record Equipment(List<RigidGlb.Data> lods, Map<String, BoundingBox> bounds) { }

    /** A hex of a building: its board position, ground and top in levels; a ghost is a collapsed hex it still names. */
    record Member(Coords coords, int elevation, int top, boolean ghost, int exits) {
        Member(Coords coords, int elevation, int top, boolean ghost) { this(coords, elevation, top, ghost, 63); }
    }

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
        return new Layout(design, PLINTH, List.of(closed), domes(fits, lone, 0, none, none, List.of(), List.of(), List.of()),
              List.of(), false, List.of(), List.of());
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        Design design = design(feature.asset());
        int levels = Math.max(1, Math.round(feature.height()));
        Float[] connected = connections(scene, tile, feature.asset(), levels);
        var group = group(scene, tile, feature.asset());
        var fits = fits(group);
        Set<Coords> routed = routed(scene, group, fits, feature.asset());
        List<Link> links = links(scene, group, fits, routed, feature.asset());
        int nearest = nearest(fits, tile.coords());
        int base = nearest < 0 || routed.contains(tile.coords()) ? tile.elevation() : Math.max(tile.elevation(), fits.get(nearest).base());
        float plinth = PLINTH + (base - tile.elevation()) * LEVEL;
        var spans = spans(links, group, fits, routed);
        var tunnels = tunnels(scene, tile, feature.asset(), spans, fits, group, connected,
              Math.min(plinth, levels * LEVEL - 3));
        var corridorGates = routed.contains(tile.coords()) ? gates(scene, tile, connected) : List.<Gate>of();
        if (annex(fits, tile.coords())) {
            // An annex: the plinth, joined to its neighbours', under the modules that cross it, its own among them; one
            // without a module of its own (no higher than the plinth, or none fits) a block at its own height.
            float top = levels * LEVEL, apron = Math.min(top, plinth);
            boolean block = tunnels.isEmpty() && annexModule(scene, tile, fits, feature.asset()) == null;
            for (int direction = 0; direction < 6; direction++) {
                connected[direction] = Math.min(connected[direction], apron);
                // A block opens only towards another block: the hexes under the domes and the wings draw no wall to meet.
                var neighbor = scene.neighbor(tile, direction);
                if (block && connected[direction] > 0 && !(annex(fits, neighbor.coords())
                      && annexModule(scene, neighbor, fits, feature.asset()) == null)) {
                    connected[direction] = 0f;
                }
            }
            var domes = block ? List.<Dome>of()
                  : domes(fits, tile.coords(), tile.elevation(), entrances(scene, group, fits, feature.asset(), routed),
                        modules(scene, group, fits, feature.asset(), routed), links, spans, group);
            return new Layout(design, top, List.of(connected), domes,
                  support(scene, tile, design, block ? 0 : apron, connected, List.of(), true, block), true, tunnels, corridorGates);
        }
        plinth(connected, plinth);
        var domes = domes(fits, tile.coords(), tile.elevation(), entrances(scene, group, fits, feature.asset(), routed),
              modules(scene, group, fits, feature.asset(), routed), links, spans, group);
        return new Layout(design, plinth, List.of(connected), domes,
              support(scene, tile, design, plinth, connected, domes, false, false), false, tunnels, corridorGates);
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

    /** The domes of {@code fits} relative to this hex's centre and ground elevation. */
    private static List<Dome> domes(List<Fit> fits, Coords at, int ground, List<List<Float>> entrances,
          List<List<Float>> wings, List<Link> links, List<Span> spans, List<Member> members) {
        List<Dome> result = new ArrayList<>();
        for (int index = 0; index < fits.size(); index++) {
            var fit = fits.get(index);
            float shift = (fit.base() - ground) * LEVEL;
            List<Point> ports = new ArrayList<>();
            for (Link link : links) {
                Point port = link.firstDome() == index ? link.from() : link.secondDome() == index ? link.to() : null;
                if (port != null) { ports.add(new Point(port.x() - fit.x(), port.y() - fit.y())); }
            }
            List<Float> pierFeet = new ArrayList<>();
            for (int pier = 0; pier < 12; pier++) {
                double angle = Math.PI / 6 * pier;
                float x = fit.x() + (fit.podium() + PIER_DEPTH + 1) * (float) Math.cos(angle);
                float y = fit.y() + (fit.podium() + PIER_DEPTH + 1) * (float) Math.sin(angle);
                float floor = PLINTH + shift;
                for (Span span : spans) {
                    if (!span.footprint().contains(x, y)) { continue; }
                    float roof = span.top() - ground * LEVEL;
                    for (Member member : members) {
                        Point centre = centre(member.coords());
                        if (!member.ghost() && inside(hex(), x - centre.x(), y - centre.y())) {
                            roof = Math.min(roof, (member.top() - ground) * LEVEL);
                        }
                    }
                    floor = Math.max(floor, Math.min(fit.ring() + shift, roof));
                }
                pierFeet.add(floor);
            }
            result.add(new Dome(fit.x() - HexTile.centerX(at, WIDTH), fit.y() - HexTile.centerY(at, HEIGHT), fit.radius(),
                  PLINTH + shift, fit.ring() + shift, fit.apex() + shift, fit.frequency(), fit.podium(),
                  entrances.get(index), wings.get(index), ports, pierFeet));
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
    private static float[] annexModule(BoardScene scene, BoardScene.Tile annex, List<Fit> fits, String asset) {
        int levels = Math.max(1, annex.cover().height());
        if (fits.isEmpty()) { return null; }
        float ax = HexTile.centerX(annex.coords(), WIDTH), ay = HexTile.centerY(annex.coords(), HEIGHT);
        int nearest = nearest(fits, annex.coords());
        var fit = fits.get(nearest);
        float rise = (annex.elevation() + levels - fit.base()) * LEVEL - PLINTH;
        if (rise <= EPSILON) { return null; }
        float dx = ax - fit.x(), dy = ay - fit.y(), apart = (float) Math.hypot(dx, dy), ux = dx / apart, uy = dy / apart;
        var kit = equipment();
        List<List<float[]>> airlocks = new ArrayList<>();
        var connected = connections(scene, annex, asset, levels);
        for (Gear airlock : airlocks(kit, scene, annex, 0, connected, List.of(), true, false)) {
            airlocks.add(footprint(kit, airlock));
        }
        for (Gate gate : gates(scene, annex, connected)) {
            airlocks.add(moduleOutline(gate.x(), gate.y(), gate.ux(), gate.uy(), -GATE_DEPTH, 0, GATE_HALF, 2));
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
    private static List<List<Float>> modules(BoardScene scene, List<Member> group, List<Fit> fits, String asset,
          Set<Coords> routed) {
        List<float[]> found = new ArrayList<>();
        for (Member member : group) {
            if (member.ghost() || routed.contains(member.coords()) || !annex(fits, member.coords())) { continue; }
            var module = annexModule(scene, scene.tile(member.coords()), fits, asset);
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

    /** Adjacent annexes form a corridor network; a single annex keeps its terminal gatehouse. */
    private static Set<Coords> routed(BoardScene scene, List<Member> group, List<Fit> fits, String asset) {
        Set<Coords> result = new java.util.HashSet<>();
        for (Member member : group) {
            if (member.ghost() || !annex(fits, member.coords())) { continue; }
            var tile = scene.tile(member.coords());
            var reach = connections(scene, tile, asset, Math.max(1, tile.cover().height()));
            int count = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (reach[direction] > 0) { count++; }
                var next = scene.neighbor(tile, direction);
                if (reach[direction] > 0 && annex(fits, next.coords())) {
                    result.add(member.coords()); result.add(next.coords());
                }
            }
            if (count > 1) { result.add(member.coords()); }
        }
        return result;
    }

    /** The closest dome whose podium reaches this hex, or -1 for a corridor hex outside all podiums. */
    private static int domeAt(List<Fit> fits, Coords at) {
        return annex(fits, at) ? -1 : nearest(fits, at);
    }

    private static int nearest(List<Fit> fits, Coords at) {
        Point centre = centre(at);
        int best = -1; double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < fits.size(); index++) {
            Fit fit = fits.get(index);
            double from = Math.hypot(centre.x() - fit.x(), centre.y() - fit.y()) - fit.podium();
            if (from < distance) { best = index; distance = from; }
        }
        return best;
    }

    private static Point centre(Coords at) { return new Point(HexTile.centerX(at, WIDTH), HexTile.centerY(at, HEIGHT)); }

    /** Routes follow the authored sides, with one span per edge and one terminal cap at a dead end. */
    private static List<Link> links(BoardScene scene, List<Member> group, List<Fit> fits, Set<Coords> routed, String asset) {
        List<Link> result = new ArrayList<>();
        for (Member member : group.stream().sorted(Comparator.comparingInt((Member m) -> m.coords().getX())
              .thenComparingInt(m -> m.coords().getY())).toList()) {
            if (member.ghost()) { continue; }
            var tile = scene.tile(member.coords());
            var reach = connections(scene, tile, asset, Math.max(1, tile.cover().height()));
            Point point = centre(member.coords());
            int own = domeAt(fits, member.coords()), count = 0, last = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (reach[direction] <= 0) { continue; }
                count++; last = direction;
                Coords next = member.coords().translated(direction);
                if (next.getX() < member.coords().getX() || next.getX() == member.coords().getX()
                      && next.getY() < member.coords().getY()) { continue; }
                int other = domeAt(fits, next);
                if (!routed.contains(member.coords()) && !routed.contains(next) && (own < 0 || other < 0 || own == other)) { continue; }
                Point end = centre(next);
                Point from = own < 0 ? point : dock(fits.get(own), point, other < 0 ? end : new Point(fits.get(other).x(), fits.get(other).y()));
                Point to = other < 0 ? end : dock(fits.get(other), end, own < 0 ? point : new Point(fits.get(own).x(), fits.get(own).y()));
                var neighbour = scene.tile(next);
                float top = Math.min(member.top(), neighbour.elevation() + Math.max(1, neighbour.cover().height())) * LEVEL;
                result.add(new Link(from, to, own, other, top));
            }
            if (routed.contains(member.coords())) {
                result.add(new Link(point, point, -1, -1, member.top() * LEVEL));
                if (count == 1) {
                    Point next = centre(member.coords().translated(last));
                    float dx = point.x() - next.x(), dy = point.y() - next.y(), length = (float) Math.hypot(dx, dy);
                    result.add(new Link(point, new Point(point.x() + dx / length * GATEHOUSE / 2,
                          point.y() + dy / length * GATEHOUSE / 2), -1, -1, member.top() * LEVEL));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Meet the ring along the incoming hex axis where possible, keeping an offset straight run straight. */
    private static Point dock(Fit fit, Point inside, Point outside) {
        float dx = inside.x() - outside.x(), dy = inside.y() - outside.y(), length = (float) Math.hypot(dx, dy);
        float radius = ringMiddle(fit.radius(), fit.podium());
        if (length > EPSILON) {
            dx /= length; dy /= length;
            float x = outside.x() - fit.x(), y = outside.y() - fit.y(), along = x * dx + y * dy;
            float discriminant = along * along - x * x - y * y + radius * radius;
            if (discriminant >= 0) {
                float travel = -along - (float) Math.sqrt(discriminant);
                if (travel >= 0) { return new Point(outside.x() + dx * travel, outside.y() + dy * travel); }
            }
        }
        dx = outside.x() - fit.x(); dy = outside.y() - fit.y(); length = (float) Math.hypot(dx, dy);
        return new Point(fit.x() + dx / length * radius, fit.y() + dy / length * radius);
    }

    /** Open shared cuts only up to the roof drawn on the other side, which may be below that hex's dome. */
    private static List<Tunnel> tunnels(BoardScene scene, BoardScene.Tile tile, String asset, List<Span> spans, List<Fit> fits,
          List<Member> group, Float[] reach, float foot) {
        Map<Coords, List<Corridor>> neighbours = new HashMap<>();
        List<Tunnel> result = new ArrayList<>();
        Point origin = centre(tile.coords());
        for (Corridor corridor : corridors(tile, spans, !annex(fits, tile.coords()))) {
            var openings = openings(scene, tile, corridor, spans, fits, reach, neighbours);
            var corners = corridorCorners(scene, tile, asset, corridor, openings, spans, fits, group, reach, neighbours, foot);
            result.add(new Tunnel(corridor.outline(), openings, Math.min(foot, corridor.top() - 3), corridor.top(), origin, corners));
        }
        return List.copyOf(result);
    }

    private static List<Float> openings(BoardScene scene, BoardScene.Tile tile, Corridor corridor, List<Span> spans,
          List<Fit> fits, Float[] reach, Map<Coords, List<Corridor>> neighbours) {
        List<Float> result = new ArrayList<>();
        Point origin = centre(tile.coords());
        for (int edge = 0; edge < corridor.outline().size(); edge++) {
            Point a = corridor.outline().get(edge), b = corridor.outline().get((edge + 1) % corridor.outline().size());
            float opening = 0;
            for (int direction = 0; direction < 6; direction++) {
                if (reach[direction] <= 0) { continue; }
                float[] border = side(direction);
                if (Math.abs(a.x() * border[0] + a.y() * border[1] - border[2]) >= .02f
                      || Math.abs(b.x() * border[0] + b.y() * border[1] - border[2]) >= .02f) { continue; }
                var neighbour = scene.neighbor(tile, direction);
                var beyond = neighbours.computeIfAbsent(neighbour.coords(), ignored ->
                      corridors(neighbour, spans, !annex(fits, neighbour.coords())));
                Point next = centre(neighbour.coords());
                double x = (a.x() + b.x()) / 2 + origin.x() - next.x() + border[0] * .02;
                double y = (a.y() + b.y()) / 2 + origin.y() - next.y() + border[1] * .02;
                for (Corridor other : beyond) {
                    if (area(other.outline().stream().map(p -> new float[] { p.x(), p.y() }).toList()).contains(x, y)) {
                        opening = Math.max(opening, Math.min(reach[direction], other.top() + (neighbour.elevation() - tile.elevation()) * LEVEL));
                    }
                }
            }
            result.add(opening);
        }
        return List.copyOf(result);
    }

    /** Recover the joined facade before detecting corners: a shared cut is not itself a structural turn. */
    private static List<Corner> corridorCorners(BoardScene scene, BoardScene.Tile tile, String asset, Corridor own,
          List<Float> openings, List<Span> spans, List<Fit> fits, List<Member> group, Float[] reach, Map<Coords, List<Corridor>> neighbours,
          float foot) {
        Point origin = centre(tile.coords());
        Area local = facadeArea(own, openings, own.top(), origin), joined = new Area(local);
        Map<Coords, Area> owners = new LinkedHashMap<>(); owners.put(tile.coords(), local);
        Area footprint = new Area();
        for (Span span : spans) { footprint.add(span.footprint()); }
        Map<Area, Float> lowerRoofs = new LinkedHashMap<>();
        for (Corridor other : neighbours.computeIfAbsent(tile.coords(), ignored ->
              corridors(tile, spans, !annex(fits, tile.coords())))) {
            if (other.top() < own.top() - EPSILON) { lowerRoofs.put(facadeArea(other, null, other.top(), origin), other.top()); }
        }
        for (int direction = 0; direction < 6; direction++) {
            if (reach[direction] <= 0) { continue; }
            var neighbour = scene.neighbor(tile, direction);
            var beyond = neighbours.computeIfAbsent(neighbour.coords(), ignored ->
                  corridors(neighbour, spans, !annex(fits, neighbour.coords())));
            float height = own.top() + (tile.elevation() - neighbour.elevation()) * LEVEL;
            Float[] otherReach = connections(scene, neighbour, asset, Math.max(1, neighbour.cover().height()));
            for (Corridor other : beyond) {
                Point otherOrigin = centre(neighbour.coords());
                if (other.top() < height - EPSILON) {
                    lowerRoofs.put(facadeArea(other, null, other.top(), otherOrigin),
                          other.top() + (neighbour.elevation() - tile.elevation()) * LEVEL);
                    continue;
                }
                if (reach[direction] < own.top() - EPSILON) { continue; }
                var otherOpenings = openings(scene, neighbour, other, spans, fits, otherReach, neighbours);
                Area surface = facadeArea(other, otherOpenings, height, otherOrigin);
                joined.add(surface);
                owners.computeIfAbsent(neighbour.coords(), ignored -> new Area()).add(surface);
            }
        }
        var outside = boundaries(footprint);
        var turns = turns(joined);
        List<Corner> result = new ArrayList<>();
        for (Turn turn : turns) {
            Point at = turn.at();
            Vector3 outward = new Vector3(turn.normal().x() + turn.side().x(), turn.normal().y() + turn.side().y(), 0).nor();
            Point normal = new Point(outward.x, outward.y);
            // Give a complete pillar to the facade just inside its corner. Exact shared cuts use board order,
            // rather than splitting the lens/body at a hex boundary or drawing two overlaid columns.
            if (!owns(owners, tile.coords(), at, normal)) { continue; }
            // A convex upper corner beside an exterior-to-ground wall is external even when a lower wing
            // continues along that straight facade. Turns wholly over a lower roof remain white supports.
            boolean ground = exteriorNormal(outside, at) != null;
            boolean external = turn.convex() && ground;
            float bottom = ground ? 0 : Math.min(foot, own.top() - 3);
            // Step facades sit 1.1 units inside their share. Probe beyond that inset and its corner miter so the
            // foot lamp rests on the visible lower roof rather than disappearing into the storey beneath it.
            if (!ground) {
                for (var lower : lowerRoofs.entrySet()) {
                    if (lower.getKey().contains(at.x() + normal.x() * 3, at.y() + normal.y() * 3)) {
                        bottom = Math.max(bottom, lower.getValue());
                    }
                }
            }
            result.add(new Corner(new Point(at.x() - origin.x(), at.y() - origin.y()),
                  turn.normal(), turn.side(), bottom, external, false));
        }
        result.addAll(flatPosts(scene, tile, asset, own.top(), local, spans, fits, group, neighbours, outside));
        return List.copyOf(result);
    }

    /** A short, gentle return beside a substantial corner keeps its wall seam rather than a second crowded column. */
    private static boolean minorReturn(Turn turn, List<Turn> adjacent) {
        float limit = (float) Math.cos(Math.PI / 6);
        if (turn.convex() || turn.normal().x() * turn.side().x() + turn.normal().y() * turn.side().y() <= limit) { return false; }
        return adjacent.stream().anyMatch(other ->
              other.normal().x() * other.side().x() + other.normal().y() * other.side().y() <= limit
                    && Math.hypot(other.at().x() - turn.at().x(), other.at().y() - turn.at().y()) < 8);
    }

    /** Centre a white support on each long exterior section between corners or changes of roof height. */
    private static List<Corner> flatPosts(BoardScene scene, BoardScene.Tile tile, String asset, float top, Area local,
          List<Span> spans, List<Fit> fits, List<Member> group, Map<Coords, List<Corridor>> neighbours,
          List<List<float[]>> outside) {
        Area joined = new Area();
        Map<Coords, Area> owners = new LinkedHashMap<>();
        for (Member member : group) {
            if (member.ghost()) { continue; }
            var otherTile = scene.tile(member.coords());
            float height = top + (tile.elevation() - otherTile.elevation()) * LEVEL;
            var reach = connections(scene, otherTile, asset, Math.max(1, otherTile.cover().height()));
            for (Corridor other : neighbours.computeIfAbsent(member.coords(), ignored ->
                  corridors(otherTile, spans, !annex(fits, member.coords())))) {
                if (Math.abs(other.top() - height) >= EPSILON) { continue; }
                var openings = openings(scene, otherTile, other, spans, fits, reach, neighbours);
                Area surface = facadeArea(other, openings, other.top(), centre(member.coords()));
                joined.add(surface);
                owners.computeIfAbsent(member.coords(), ignored -> new Area()).add(surface);
            }
        }
        Point origin = centre(tile.coords());
        List<Corner> result = new ArrayList<>();
        for (var outline : boundaries(joined)) {
            for (int i = 0; i < outline.size(); i++) {
                float[] a = outline.get(i), b = outline.get((i + 1) % outline.size());
                float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (length < 3 * LEVEL) { continue; }
                Point at = new Point((a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
                Point normal = new Point((b[1] - a[1]) / length, (a[0] - b[0]) / length);
                Point exterior = exteriorNormal(outside, at);
                if (exterior == null || exterior.x() * normal.x() + exterior.y() * normal.y() < .99
                      || !local.intersects(at.x() - normal.x() * .1 - .02, at.y() - normal.y() * .1 - .02, .04, .04)
                      || !owns(owners, tile.coords(), at, normal)) { continue; }
                result.add(new Corner(new Point(at.x() - origin.x(), at.y() - origin.y()), normal, normal, 0, false, true));
            }
        }
        return result;
    }

    private static boolean owns(Map<Coords, Area> owners, Coords tile, Point at, Point normal) {
        double x = at.x() - normal.x() * .1, y = at.y() - normal.y() * .1;
        Coords owner = owners.entrySet().stream().filter(entry -> entry.getValue().intersects(x - .02, y - .02, .04, .04))
              .map(Map.Entry::getKey).min(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY)).orElse(null);
        return tile.equals(owner);
    }

    private static List<Turn> turns(Area footprint) {
        List<Turn> result = new ArrayList<>();
        for (List<float[]> outline : boundaries(footprint)) {
            List<Turn> boundary = new ArrayList<>();
            for (int i = 0; i < outline.size(); i++) {
                float[] previous = outline.get((i + outline.size() - 1) % outline.size()), at = outline.get(i);
                float[] next = outline.get((i + 1) % outline.size());
                Vector3 incoming = new Vector3(at[0] - previous[0], at[1] - previous[1], 0).nor();
                Vector3 outgoing = new Vector3(next[0] - at[0], next[1] - at[1], 0).nor();
                float turn = incoming.x * outgoing.y - incoming.y * outgoing.x;
                if (Math.abs(turn) < .01f) { continue; }
                boundary.add(new Turn(new Point(at[0], at[1]), new Point(incoming.y, -incoming.x),
                      new Point(outgoing.y, -outgoing.x), turn > 0));
            }
            // Only adjoining turns can crowd a short return. Another wing across a narrow gap is unrelated.
            for (int i = 0; i < boundary.size(); i++) {
                Turn turn = boundary.get(i);
                if (!minorReturn(turn, List.of(boundary.get((i + boundary.size() - 1) % boundary.size()),
                      boundary.get((i + 1) % boundary.size())))) { result.add(turn); }
            }
        }
        return List.copyOf(result);
    }

    /** A height-step column faces the exterior wall below it, not sideways into the lower roof. */
    private static Point exteriorNormal(List<List<float[]>> boundaries, Point at) {
        Vector3 normal = new Vector3();
        for (var outline : boundaries) {
            for (int i = 0; i < outline.size(); i++) {
                float[] a = outline.get(i), b = outline.get((i + 1) % outline.size());
                if (Line2D.ptSegDist(a[0], a[1], b[0], b[1], at.x(), at.y()) >= 2.3) { continue; }
                normal.add(new Vector3(b[1] - a[1], a[0] - b[0], 0).nor());
            }
        }
        if (normal.len2() < EPSILON) { return null; }
        normal.nor();
        return new Point(normal.x, normal.y);
    }

    private static List<List<float[]>> boundaries(Area footprint) {
        var result = outlines(footprint);
        for (var outline : result) {
            float[] a = outline.getFirst(), b = outline.get(1);
            float dx = b[0] - a[0], dy = b[1] - a[1], length = (float) Math.hypot(dx, dy);
            // Keep solid on the left, including courtyard holes whose winding is opposite to the outer outline.
            if (!footprint.contains((a[0] + b[0]) / 2 - dy / length * .02,
                  (a[1] + b[1]) / 2 + dx / length * .02)) { java.util.Collections.reverse(outline); }
        }
        return result;
    }

    private static Area facadeArea(Corridor corridor, List<Float> openings, float top, Point origin) {
        var walls = corridor.outline().stream().map(p -> new float[] { p.x(), p.y() }).toList();
        var facade = facadeOutline(walls, openings, top);
        // The hex grid uses floats; weld the same world-space cut before Area union so a sub-texel rounding gap
        // cannot become a false, very thin courtyard with four extra corners.
        return area(facade.stream().map(p -> new float[] { Math.round((p[0] + origin.x()) * 100) / 100f,
              Math.round((p[1] + origin.y()) * 100) / 100f }).toList());
    }

    /** Connected triangles are solid junctions; unconnected bends and larger courtyards retain their open ground. */
    private static List<Span> spans(List<Link> links, List<Member> group, List<Fit> fits, Set<Coords> routed) {
        List<Span> result = new ArrayList<>();
        for (Link link : links) {
            float x = link.from().x(), y = link.from().y();
            float dx = link.to().x() - link.from().x(), dy = link.to().y() - link.from().y();
            float length = (float) Math.hypot(dx, dy);
            List<float[]> shape = new ArrayList<>();
            if (length < EPSILON) {
                // A hub tangent to the six corridor axes avoids tiny notches where an octagon met diagonal spans.
                Point anchor = centre(new Coords(0, 0));
                for (int corner = 0; corner < 6; corner++) {
                    Point a = centre(new Coords(0, 0).translated(corner)), b = centre(new Coords(0, 0).translated((corner + 1) % 6));
                    float[] p = meet(shifted(new float[] { 0, 0 }, new float[] { a.x() - anchor.x(), a.y() - anchor.y() }, MODULE_HALF),
                          shifted(new float[] { 0, 0 }, new float[] { b.x() - anchor.x(), b.y() - anchor.y() }, MODULE_HALF));
                    shape.add(new float[] { x + p[0], y + p[1] });
                }
            } else {
                float px = -dy / length * MODULE_HALF, py = dx / length * MODULE_HALF;
                shape.add(new float[] { x + px, y + py }); shape.add(new float[] { x - px, y - py });
                shape.add(new float[] { x + dx - px, y + dy - py }); shape.add(new float[] { x + dx + px, y + dy + py });
            }
            result.add(new Span(area(shape), link.top()));
        }
        Map<Coords, Member> members = new HashMap<>();
        for (Member member : group) { if (!member.ghost()) { members.put(member.coords(), member); } }
        Area podiums = new Area();
        for (Fit fit : fits) {
            List<float[]> outline = new ArrayList<>();
            for (int corner = 0; corner < 12; corner++) {
                double angle = Math.PI / 6 * corner;
                outline.add(new float[] { fit.x() + (fit.podium() - 1) * (float) Math.cos(angle),
                      fit.y() + (fit.podium() - 1) * (float) Math.sin(angle) });
            }
            podiums.add(area(outline));
        }
        for (Member a : members.values()) {
            // These two sectors visit every adjacent triangle once, from its westernmost/northernmost hex.
            for (int direction = 1; direction <= 2; direction++) {
                Member b = members.get(a.coords().translated(direction)), c = members.get(a.coords().translated(direction + 1));
                if (b == null || c == null || !joined(a, b) || !joined(a, c) || !joined(b, c)
                      || Math.min(a.top(), Math.min(b.top(), c.top())) <= Math.max(a.elevation(), Math.max(b.elevation(), c.elevation()))) { continue; }
                int top = 0;
                for (Member member : List.of(a, b, c)) {
                    if (routed.contains(member.coords())) { top = Math.max(top, member.top()); }
                }
                if (top == 0) { continue; }
                List<float[]> triangle = new ArrayList<>();
                for (Member member : List.of(a, b, c)) {
                    Point p = centre(member.coords()); triangle.add(new float[] { p.x(), p.y() });
                }
                Area junction = area(triangle);
                // Stop inside the solid ring wall; a roof must never bridge over the dome's glass or garden.
                junction.subtract(podiums);
                if (!junction.isEmpty()) { result.add(new Span(junction, top * LEVEL)); }
            }
        }
        return result;
    }

    /** Union spans before cutting shares. Each separate docking roof meets its tallest incoming corridor. */
    private static List<Corridor> corridors(BoardScene.Tile tile, List<Span> footprints, boolean docking) {
        Point origin = centre(tile.coords());
        Area area = new Area();
        Map<Area, Float> spans = new LinkedHashMap<>();
        for (Span footprint : footprints) {
            Area span = footprint.footprint().createTransformedArea(AffineTransform.getTranslateInstance(-origin.x(), -origin.y()));
            span.intersect(area(hex()));
            if (span.isEmpty()) { continue; }
            spans.put(span, footprint.top() - tile.elevation() * LEVEL);
            area.add(span);
        }
        List<Corridor> result = new ArrayList<>();
        for (List<float[]> points : outlines(area)) {
            List<float[]> polygon = counterClockwise(points);
            float top = Math.max(1, tile.cover().height()) * LEVEL;
            if (docking) {
                float incoming = 0;
                Area footprint = area(polygon);
                for (var span : spans.entrySet()) {
                    Area part = new Area(span.getKey()); part.intersect(footprint);
                    if (!part.isEmpty()) { incoming = Math.max(incoming, span.getValue()); }
                }
                top = Math.min(top, incoming);
            }
            result.add(new Corridor(polygon.stream().map(p -> new Point(p[0], p[1])).toList(), top));
        }
        return List.copyOf(result);
    }

    /** Polygon loops without union-produced collinear vertices; retain winding so holes can be distinguished. */
    private static List<List<float[]>> outlines(Area area) {
        List<List<float[]>> result = new ArrayList<>();
        List<float[]> polygon = new ArrayList<>();
        float[] point = new float[6];
        for (PathIterator path = area.getPathIterator(null); !path.isDone(); path.next()) {
            int segment = path.currentSegment(point);
            if (segment == PathIterator.SEG_MOVETO) { polygon = new ArrayList<>(); }
            if (segment == PathIterator.SEG_MOVETO || segment == PathIterator.SEG_LINETO) {
                polygon.add(new float[] { point[0], point[1] });
            } else if (segment == PathIterator.SEG_CLOSE) {
                // Area can leave collinear vertices at unions; they must not create parallel inset lines.
                for (int i = polygon.size() - 1; i >= 0 && polygon.size() > 2; i--) {
                    float[] a = polygon.get((i + polygon.size() - 1) % polygon.size()), b = polygon.get(i), c = polygon.get((i + 1) % polygon.size());
                    if (Math.abs((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])) < .002f) { polygon.remove(i); }
                }
                if (polygon.size() < 3) { continue; }
                result.add(polygon);
            }
        }
        return result;
    }

    private static Area area(List<float[]> polygon) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(polygon.getFirst()[0], polygon.getFirst()[1]);
        for (int index = 1; index < polygon.size(); index++) { path.lineTo(polygon.get(index)[0], polygon.get(index)[1]); }
        path.closePath(); return new Area(path);
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
     * The entrances of a domed building as {@link Dome#entrances} gives them, in a stable order: the vehicle gates where
     * roads arrive ({@link #gates}) and the airlocks the hexes under its domes place ({@link #airlocks}), by the
     * nearest dome.
     */
    private static List<List<Float>> entrances(BoardScene scene, List<Member> group, List<Fit> fits,
          String asset, Set<Coords> routed) {
        var kit = equipment();
        List<List<Float>> result = new ArrayList<>();
        fits.forEach(fit -> result.add(new ArrayList<>()));
        if (fits.isEmpty()) { return result; }
        var none = java.util.Collections.nCopies(fits.size(), List.<Float>of());
        List<float[]> points = new ArrayList<>();
        for (Member member : group) {
            if (member.ghost()) { continue; }
            var tile = scene.tile(member.coords());
            float shift = Math.max(0, fits.get(nearest(fits, member.coords())).base() - tile.elevation()) * LEVEL;
            float cx = HexTile.centerX(member.coords(), WIDTH), cy = HexTile.centerY(member.coords(), HEIGHT);
            boolean annex = annex(fits, member.coords());
            var connected = connections(scene, tile, asset, Math.max(1, tile.cover().height()));
            // Its vehicle gates, in to the podium, or on an annex to its gatehouse (an annex standing as a block keeps
            // airlocks at its facade, #airlocks).
            if (!routed.contains(member.coords()) && (!annex || annexModule(scene, tile, fits, asset) != null)) {
                for (Gate gate : gates(scene, tile, connected)) {
                    points.add(new float[] { cx + gate.x(), cy + gate.y(), gate.ux(), gate.uy(), gate.floor() + tile.elevation() * LEVEL, 1,
                          annex ? (float) Math.hypot(gate.x(), gate.y()) : 0 });
                }
            }
            if (annex) { continue; }
            plinth(connected, PLINTH + shift);
            for (Gear airlock : airlocks(kit, scene, tile, PLINTH + shift, connected,
                  domes(fits, member.coords(), tile.elevation(), none, none, List.of(), List.of(), List.of()), false, false)) {
                // Turned clockwise from +y (#frame).
                double facing = Math.toRadians(airlock.angle());
                points.add(new float[] { cx + airlock.x(), cy + airlock.y(), (float) Math.sin(facing),
                      (float) Math.cos(facing), airlock.z() + tile.elevation() * LEVEL, 0, 0 });
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
                  point[3], point[4] - fits.get(nearest).base() * LEVEL - PLINTH, point[5], point[6]));
        }
        return result;
    }

    // ---- Support pieces ------------------------------------------------------------------------------------------------

    private static final String EQUIPMENT = "buildings/multihex/lunar-equipment.glb";
    private static final List<String> INTERIOR_GEAR = List.of("plant", "tanks");
    private static volatile BoardKit<Equipment> equipment = new BoardKit<>(BoardMultiHexBuilding::loadEquipment);

    /** A reload publishes the kit afresh; in-flight terrain workers keep the copy they hold. */
    static void reload() {
        equipment = new BoardKit<>(BoardMultiHexBuilding::loadEquipment);
        GeneratedMesh.reload();
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
            int back = 1 << (direction + 3) % 6;
            // A road ends at a vehicle gate (#gates), but at a block's facade.
            if (heights[direction] > 0 || neighbor == null
                  || ((neighbor.railBed() | (block ? neighbor.roadExits() : 0)) & back) == 0
                  || !block && (neighbor.roadExits() & back) != 0) {
                continue;
            }
            float[] side = side(direction);
            // In front of a block's facade, else on the plinth inside its edge.
            float along = side[2] - SETBACK - (block ? 0 : bounds.max.y);
            float z = (neighbor.roadExits() & back) != 0 ? road(tile, neighbor) : top;
            var airlock = new Gear("airlock", side[0] * along, side[1] * along, z, 60 * direction);
            if (blocks || fits(kit, airlock, base, domes, false)) { result.add(airlock); }
        }
        return result;
    }

    /**
     * A vehicle gate at each closed hexside a road arrives at (a road meets a closed hexside square at its middle,
     * BoardRoad#layout): its door's x and y about the hex centre, just inside the hexside where the road ends, the way it
     * faces and its floor, at the road's own level.
     */
    private static List<Gate> gates(BoardScene scene, BoardScene.Tile tile, Float[] reach) {
        List<Gate> result = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            if (reach[direction] > 0 || neighbor == null || (neighbor.roadExits() & 1 << (direction + 3) % 6) == 0) {
                continue;
            }
            float[] side = side(direction);
            // Its door's frame and lintel stand out of its face, inside the hexside.
            float along = side[2] - 2.6f;
            result.add(new Gate(side[0] * along, side[1] * along, side[0], side[1], road(tile, neighbor)));
        }
        return result;
    }

    /** The level of the road arriving from {@code neighbor}: a colony slab road's deck (GpuRoads#SLAB_RISE), else its ground. */
    private static float road(BoardScene.Tile tile, BoardScene.Tile neighbor) {
        return Math.max(0, (GpuRoads.slabs(neighbor, false) ? GpuRoads.SLAB_RISE : 0)
              + (neighbor.elevation() - tile.elevation()) * LEVEL);
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
        return new Member(tile.coords(), tile.elevation(), tile.elevation() + Math.max(1, tile.cover().height()), ghost,
              ghost ? 63 : tile.cover().exits());
    }

    /**
     * Broad clusters are joined triangles of hexes; a one-hex-wide chain between them is a corridor, not part of
     * either dome's fit. A lone hex still gets a dome, as does a raised peak in a narrow run. Everything is derived
     * from the current connections and heights, including boards made before the editor had drawn connections.
     */
    static List<Fit> fits(Collection<Member> group) {
        Map<Coords, Member> members = new LinkedHashMap<>();
        group.stream().sorted(Comparator.comparingInt((Member member) -> member.coords().getX())
              .thenComparingInt(member -> member.coords().getY())).forEach(member -> members.put(member.coords(), member));
        if (members.size() <= 1) { return fitRegion(members.values()); }
        Map<Coords, Set<Coords>> wide = new HashMap<>();
        for (Member member : members.values()) {
            for (int direction = 0; direction < 6; direction++) {
                Member a = members.get(member.coords().translated(direction));
                Member b = members.get(member.coords().translated((direction + 1) % 6));
                if (!joined(member, a) || !joined(member, b) || !joined(a, b)) { continue; }
                for (Member vertex : List.of(member, a, b)) {
                    var neighbours = wide.computeIfAbsent(vertex.coords(), ignored -> new java.util.HashSet<>());
                    for (Member other : List.of(member, a, b)) {
                        if (vertex != other) { neighbours.add(other.coords()); }
                    }
                }
            }
        }
        Set<Coords> seen = new java.util.HashSet<>();
        List<Fit> result = new ArrayList<>();
        for (Member start : members.values()) {
            if (!wide.containsKey(start.coords()) || !seen.add(start.coords())) { continue; }
            List<Member> cluster = new ArrayList<>();
            var open = new ArrayDeque<>(List.of(start.coords()));
            while (!open.isEmpty()) {
                Coords at = open.remove(); cluster.add(members.get(at));
                for (Coords next : wide.get(at)) { if (seen.add(next)) { open.add(next); } }
            }
            if (cluster.stream().anyMatch(member -> !member.ghost())) { result.addAll(fitRegion(cluster)); }
        }
        // Heights can still make a tower in an otherwise narrow corridor; a level run never creates repeated domes.
        for (Member start : members.values()) {
            if (start.ghost() || !seen.add(start.coords())) { continue; }
            List<Member> peak = new ArrayList<>();
            var open = new ArrayDeque<>(List.of(start));
            boolean higher = false, lower = false;
            while (!open.isEmpty()) {
                Member at = open.remove(); peak.add(at);
                for (int direction = 0; direction < 6; direction++) {
                    Member next = members.get(at.coords().translated(direction));
                    if (!joined(at, next) || next.ghost()) { continue; }
                    higher |= next.top() > at.top() || next.top() == at.top() && wide.containsKey(next.coords());
                    lower |= next.top() < at.top();
                    if (next.top() == at.top() && !wide.containsKey(next.coords()) && seen.add(next.coords())) { open.add(next); }
                }
            }
            if (!higher && lower) { result.addAll(fitRegion(peak)); }
        }
        result.sort(Comparator.comparingDouble(Fit::x).thenComparingDouble(Fit::y));
        return List.copyOf(result);
    }

    private static boolean joined(Member first, Member second) {
        if (first == null || second == null || first.coords().distance(second.coords()) != 1) { return false; }
        int direction = first.coords().direction(second.coords());
        return (first.exits() & 1 << direction) != 0 && (second.exits() & 1 << (direction + 3) % 6) != 0;
    }

    /**
     * The domes a cluster's heights shape: one over each peak, standing hexes of one top that no standing neighbour
     * rises above. Each centres on the peak's hex centre or corner farthest from the footprint's outline (ties to the
     * one nearest the peak's middle; a collapsed hex still counts in the outline); its podium's corners lie that far in
     * less {@link #DOME_MARGIN}, shrunk in proportion where two domes' podiums would meet; its apex is the peak's top.
     * Where the glass meets the podium's roof follows the heights of the hexes it covers: the cap that best passes
     * through their tops, the podium no lower than {@link #RING_LOW} and the glass at least a fifth of its radius high.
     * The order of {@code group} does not matter, so every hex of a building fits the same domes. Heights are model
     * units over the highest ground under the building.
     */
    private static List<Fit> fitRegion(Collection<Member> group) {
        List<Member> members = group.stream().sorted(Comparator.comparingInt((Member member) -> member.coords().getX())
              .thenComparingInt(member -> member.coords().getY())).toList();
        Map<Coords, Member> byCoords = new HashMap<>();
        members.forEach(member -> byCoords.put(member.coords(), member));
        List<float[]> outline = new ArrayList<>();
        int base = Integer.MIN_VALUE;
        for (Member member : members) {
            for (int direction = 0; direction < 6; direction++) {
                if (joined(member, byCoords.get(member.coords().translated(direction)))) { continue; }
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
                    if (!joined(member, next) || next.ghost()) { continue; }
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

    /** The podium's lowest roof over its plinth; taller domes extend the podium to meet their glass. */
    static final float RING_LOW = PLINTH + 7;

    /**
     * Where the glass of a dome with this foot radius and apex meets its podium's roof: the cap whose heights best
     * match the samples' (distance from the centre, top) by least squares, a sample beyond the foot taking the podium's
     * roof; a dome over its peak alone rises {@link #DOME_RISE} of its radius.
     */
    private static float ring(float radius, float apex, List<float[]> samples) {
        // Keep the cap no taller than a hemisphere: any extra height belongs to its supporting tower.
        float low = Math.max(RING_LOW, apex - radius), high = apex - radius * .2f;
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

    private static GeneratedMesh mesh(Layout layout, int lod) {
        var mesh = new GeneratedMesh();
        float[] reach = new float[6];
        for (int direction = 0; direction < 6; direction++) { reach[direction] = layout.reach().get(direction); }
        if (layout.domes().isEmpty() && layout.tunnels().isEmpty()) {
            // A footprint too narrow for any dome, or an annex without a module of its own, stands at its own height.
            shell(mesh, layout.top(), reach);
        }
        for (Dome dome : layout.domes()) {
            if (layout.annex()) {
                for (int at = 0; at < dome.modules().size(); at += 4) { module(mesh, hex(), dome, at); }
                continue;
            }
            dome(mesh, dome, lod);
            if (layout.design() == Design.BIODOME) { garden(mesh, dome); }
        }
        for (Tunnel tunnel : layout.tunnels()) {
            var outline = tunnel.outline().stream().map(point -> new float[] { point.x(), point.y() }).toList();
            prism(mesh, hex(), PLINTH_MATERIAL, outline, 0, tunnel.foot(), outline.size());
            moduleBlock(mesh, hex(), outline, tunnel.foot(), tunnel.top(), tunnel.openings(), tunnel.origin());
            for (Corner corner : tunnel.corners()) {
                // One owning facade carries the complete corner housing, including its small relief over the cut.
                float[] at = { corner.at().x(), corner.at().y() };
                if (corner.flat()) {
                    facadePost(mesh, grown(hex(), 6), at, -corner.normal().y(), corner.normal().x(),
                          corner.bottom(), tunnel.top() - BEVEL_RISE, 1.3f, false);
                } else {
                    cornerPost(mesh, grown(hex(), 6), at, corner.normal(), corner.side(),
                          corner.bottom(), tunnel.top() - BEVEL_RISE, corner.external());
                }
            }
        }
        // Entrances cut their own opening in the finished facade before adding their passage and fitted flanges.
        if (!layout.domes().isEmpty() || layout.annex()) { skirt(mesh, layout, lod); }
        // The shared palette metal takes the architecture's charcoal paint from vertex colour, as the wall kits do.
        FloatArray steel = mesh.parts.get(PIER);
        if (steel != null) {
            for (int at = 0; at < steel.size; at += RigidGlb.STRIDE) {
                for (int channel = 6; channel < 9; channel++) { steel.items[at + channel] *= .34f; }
            }
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
    private static void skirt(GeneratedMesh mesh, Layout layout, int lod) {
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
            // Each vehicle gate, in from the road's end; each airlock's tube, out of the podium square to it and bending
            // round to the airlock's back.
            var entrances = dome.entrances();
            for (int at = 0; at < entrances.size(); at += 7) {
                float x = entrances.get(at), y = entrances.get(at + 1), from = (float) Math.hypot(x, y), start = flat - 1;
                if (entrances.get(at + 5) > 0) {
                    float ux = entrances.get(at + 2), uy = entrances.get(at + 3), floor = top + entrances.get(at + 4);
                    float bx = x - ux * GATE_DEPTH, by = y - uy * GATE_DEPTH, centre = entrances.get(at + 6);
                    gate(mesh, hex, dome.x() + x, dome.y() + y, ux, uy, floor);
                    // Joined to its annex's gatehouse door, else to the podium square to it.
                    float[] building = centre > 0
                          ? door(layout, dome.x() + x - ux * centre, dome.y() + y - uy * centre, ux, uy, GATE_HALF) : ring(dome, bx, by, top);
                    if (building != null) {
                        passage(mesh, hex, building,
                              new float[] { dome.x() + bx, dome.y() + by, ux, uy, floor, floor + GATE_HEIGHT }, lod);
                    }
                    continue;
                }
                if (from <= start) { continue; }
                bellows(mesh, hex, new float[] { dome.x() + x / from * start, dome.y() + y / from * start, x / from, y / from,
                      top }, new float[] { dome.x() + x, dome.y() + y, entrances.get(at + 2), entrances.get(at + 3),
                      top + entrances.get(at + 4) }, lod, TUBE, false);
            }
        }
        for (Gate gate : layout.corridorGates()) {
            gate(mesh, hex, gate.x(), gate.y(), gate.ux(), gate.uy(), gate.floor());
            float[] door = door(layout, 0, 0, gate.ux(), gate.uy(), GATE_HALF);
            if (door != null) {
                passage(mesh, hex, door, new float[] { gate.x() - gate.ux() * GATE_DEPTH, gate.y() - gate.uy() * GATE_DEPTH,
                      gate.ux(), gate.uy(), gate.floor(), gate.floor() + GATE_HEIGHT }, lod);
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
            float[] door = door(layout, 0, 0, airlock[2], airlock[3], TUBE + 1.1f);
            if (door != null && (gear.x() - door[0]) * door[2] + (gear.y() - door[1]) * door[3] > 3) {
                bellows(mesh, hex, door, airlock, lod, TUBE, false);
            } else if (door != null) {
                bellows(mesh, hex, new float[] { 0, 0, airlock[2], airlock[3], gear.z() }, airlock, lod, TUBE, false);
            }
        }
    }

    /**
     * A vehicle gate's half width, depth and height above its floor; its door's half width and height: one vehicle at a
     * time, on half a road's width (HexTile#ROAD_HALF_WIDTH).
     */
    private static final float GATE_HALF = 12, GATE_DEPTH = 9, GATE_HEIGHT = 14, GATE_DOOR = 8, GATE_DOOR_HEIGHT = 11;

    /**
     * A vehicle gate, cut to the hex: a panelled block behind its door at (x, y), facing (ux, uy) where the road ends,
     * joined to the building by its passage (#passage); in its face a pale blast door of three leaves in a dark frame, at
     * the road's level ({@code floor}), under a yellow lintel between hazard posts.
     */
    private static void gate(GeneratedMesh mesh, List<float[]> hex, float x, float y, float ux, float uy, float floor) {
        moduleBlock(mesh, hex, moduleOutline(x, y, ux, uy, -GATE_DEPTH, 0, GATE_HALF, 2), 0, floor + GATE_HEIGHT, null, null, false);
        float px = -uy, py = ux, top = floor + GATE_DOOR_HEIGHT;
        clippedBlock(mesh, hex, PIER, x + ux * .4f, y + uy * .4f, px, py, GATE_DOOR + 1.2f, .8f, floor, top + 1.2f);
        clippedBlock(mesh, hex, FRAME, x + ux * .9f, y + uy * .9f, px, py, GATE_DOOR, .5f, floor, top);
        for (int leaf = 1; leaf < 3; leaf++) {
            float z = floor + GATE_DOOR_HEIGHT * leaf / 3;
            clippedBlock(mesh, hex, PIER, x + ux * 1.45f, y + uy * 1.45f, px, py, GATE_DOOR - .3f, .1f, z - .2f, z + .2f);
        }
        clippedBlock(mesh, hex, FIN_MATERIAL, x + ux * .7f, y + uy * .7f, px, py, GATE_DOOR + 1.2f, 1, top + 1.2f, top + 2.4f);
        for (int side = -1; side <= 1; side += 2) {
            float across = side * (GATE_DOOR + 2);
            clippedBlock(mesh, hex, FIN_MATERIAL, x + ux * .7f + px * across, y + uy * .7f + py * across, px, py, .6f, .9f,
                  floor, top);
            // Layered jambs, recessed service strips and warning feet give the gate a fitted frame rather than
            // another plain building block. All fittings face the road; the passage remains on its centreline.
            float jamb = side * (GATE_DOOR + 3.1f);
            clippedBlock(mesh, hex, PIER, x + px * jamb, y + py * jamb, px, py, .85f, 1.2f, floor, top + 1.6f);
            clippedBlock(mesh, hex, FRAME, x + ux * 1.24f + px * jamb, y + uy * 1.24f + py * jamb,
                  px, py, .36f, .1f, floor + .7f, top - 1.3f);
            clippedBlock(mesh, hex, LIGHT, x + ux * 1.36f + px * jamb, y + uy * 1.36f + py * jamb,
                  px, py, .17f, .05f, top - 2.8f, top - 2.1f);
            float cx = x - ux * GATE_DEPTH / 2 + px * side * (GATE_HALF + .1f);
            float cy = y - uy * GATE_DEPTH / 2 + py * side * (GATE_HALF + .1f);
            clippedBlock(mesh, hex, PIER, cx, cy, ux, uy, GATE_DEPTH / 2 - 2, .15f,
                  floor + .4f, top + .3f);
            clippedBlock(mesh, hex, HARDWARE, cx + px * side * .17f, cy + py * side * .17f,
                  ux, uy, 1.35f, .07f, floor + 3.1f, top - 1.1f);
            for (int slat = 0; slat < 4; slat++) {
                float z = floor + .7f + slat * .45f;
                clippedBlock(mesh, hex, HARDWARE, cx + px * side * .18f, cy + py * side * .18f,
                      ux, uy, 1.8f, .05f, z, z + .1f);
            }
            for (int stripe = 0; stripe < 3; stripe++) {
                float base = floor + .2f + stripe * .65f;
                float[][] corners = new float[4][];
                for (int i = 0; i < 4; i++) {
                    float offset = i == 0 || i == 3 ? -.55f : .55f;
                    float z = base + (offset + .55f) * .6f + (i < 2 ? 0 : .22f);
                    corners[i] = vertex(x + ux * 1.62f + px * (across + offset),
                          y + uy * 1.62f + py * (across + offset), z, ux, uy, 0, 1, 0, 0);
                }
                outward(mesh, PIER, hex, corners[0], corners[1], corners[2]);
                outward(mesh, PIER, hex, corners[0], corners[2], corners[3]);
            }
        }
        // The broad housing cap has a narrow crown plate and separate edge rails.
        clippedBlock(mesh, hex, ROOF, x - ux * GATE_DEPTH / 2, y - uy * GATE_DEPTH / 2, px, py,
              GATE_DOOR + .8f, 1.8f, floor + GATE_HEIGHT - .1f, floor + GATE_HEIGHT + .22f);
        for (float along : new float[] { -GATE_DEPTH + 2.8f, -1.8f }) {
            clippedBlock(mesh, hex, PIER, x + ux * along, y + uy * along, px, py,
                  GATE_DOOR + 1, .24f, floor + GATE_HEIGHT, floor + GATE_HEIGHT + .35f);
        }
    }

    /** A gatehouse door, or the corridor wall reached from the annex's centre towards its entrance. */
    private static float[] door(Layout layout, float x, float y, float ux, float uy, float half) {
        for (Dome dome : layout.domes()) {
            float[] door = door(dome, x - dome.x(), y - dome.y());
            if (door != null) { return door; }
        }
        if (!inside(hex(), x, y)) { return null; }
        float[] result = null;
        Tunnel owner = null;
        float nearest = Float.POSITIVE_INFINITY;
        for (Tunnel tunnel : layout.tunnels()) {
            for (int edge = 0; edge < tunnel.outline().size(); edge++) {
                Point a = tunnel.outline().get(edge), b = tunnel.outline().get((edge + 1) % tunnel.outline().size());
                float dx = b.x() - a.x(), dy = b.y() - a.y(), cross = ux * dy - uy * dx;
                if (Math.abs(cross) < EPSILON || tunnel.openings().get(edge) >= tunnel.top() - EPSILON) { continue; }
                float distance = ((a.x() - x) * dy - (a.y() - y) * dx) / cross;
                float along = ((a.x() - x) * uy - (a.y() - y) * ux) / cross;
                if (distance < 0 || distance >= nearest || along < 0 || along > 1) { continue; }
                nearest = distance;
                owner = tunnel;
                float length = (float) Math.hypot(dx, dy);
                result = new float[] { x + ux * distance, y + uy * distance, dy / length, -dx / length,
                      tunnel.foot(), Math.min(tunnel.top() - BEVEL_RISE, tunnel.foot() + GATE_DOOR_HEIGHT) };
            }
        }
        if (owner == null) { return null; }
        // Stay on the gate's centreline, including a corner entry. Recess the whole mouth into the footprint;
        // the passage cuts both intersected walls to its own profile instead of bending towards one of them.
        Area footprint = area(owner.outline().stream().map(p -> new float[] { p.x(), p.y() }).toList());
        for (int step = 0; step <= Math.ceil(nearest); step++) {
            float distance = Math.max(0, nearest - step), px = x + ux * distance, py = y + uy * distance;
            float ax = px - uy * half, ay = py + ux * half, bx = px + uy * half, by = py - ux * half;
            Area collar = area(List.of(new float[] { ax, ay }, new float[] { bx, by },
                  new float[] { bx - ux, by - uy }, new float[] { ax - ux, ay - uy }));
            collar.subtract(footprint);
            if (collar.isEmpty()) {
                return new float[] { px, py, ux, uy, owner.foot(), result[5] };
            }
        }
        return null;
    }

    /**
     * The blast door of the gatehouse over the annex whose centre lies at (cx, cy) about the dome's centre, as
     * {@link #bellows} takes a tube's start, and the height a passage reaches there to cover it; null without one.
     */
    private static float[] door(Dome dome, float cx, float cy) {
        var modules = dome.modules();
        for (int at = 0; at < modules.size(); at += 4) {
            float ux = modules.get(at), uy = modules.get(at + 1), outer = modules.get(at + 2) + 1;
            // Its own: the module's line runs through the annex's centre, its gatehouse over it.
            float apart = cx * ux + cy * uy;
            if (Math.abs(cx * uy - cy * ux) < 1 && apart > 0 && outer - apart <= GATEHOUSE / 2 + 2) {
                // Over the lintel, but under the gatehouse's cap (#moduleBlock) however low it is.
                float rise = modules.get(at + 3), lintel = dome.foot() + Math.min(15, rise - 3) + 1.3f;
                float reach = Math.min(lintel + 2, dome.foot() + rise - BEVEL_RISE);
                return new float[] { dome.x() + ux * outer, dome.y() + uy * outer, ux, uy, dome.foot(), reach };
            }
        }
        return null;
    }

    /** Whether a gate of an annex on the module's line from the dome's centre along (ux, uy) docks its passage there. */
    private static boolean docked(Dome dome, float ux, float uy) {
        var entrances = dome.entrances();
        for (int at = 0; at < entrances.size(); at += 7) {
            float centre = entrances.get(at + 6);
            if (entrances.get(at + 5) <= 0 || centre <= 0) { continue; }
            // The annex's centre, about the dome's.
            float cx = entrances.get(at) - entrances.get(at + 2) * centre, cy = entrances.get(at + 1) - entrances.get(at + 3) * centre;
            if (Math.abs(cx * uy - cy * ux) < 1 && cx * ux + cy * uy > 0) { return true; }
        }
        return false;
    }

    /**
     * Where a passage to (bx, by) about the dome's centre leaves its podium, square to it, as {@link #bellows} takes it,
     * at the gate's height.
     */
    private static float[] ring(Dome dome, float bx, float by, float floor) {
        float from = (float) Math.hypot(bx, by), start = dome.podium() * (float) Math.cos(Math.PI / 12) - 1;
        if (from <= start + 1) { return null; }
        float ux = bx / from, uy = by / from;
        return new float[] { dome.x() + ux * start, dome.y() + uy * start, ux, uy, floor, floor + GATE_DOOR_HEIGHT };
    }

    /** The height above its floor a bending passage's arched roof springs from (#arch). */
    private static final float SPRING = 6;

    /**
     * A rigid pressure passage. Corresponding points of its two rounded rectangular mouths are lofted directly,
     * keeping short angled connections full-width instead of folding a wide tube around a tight centreline.
     */
    private static void passage(GeneratedMesh mesh, List<float[]> hex, float[] from, float[] to, int lod) {
        float length = (float) Math.hypot(to[0] - from[0], to[1] - from[1]);
        if (length < 1.5f) { return; }
        float[][] start = passageProfile(from, to, 0, 0), end = passageProfile(from, to, 1, 0);
        passageOpening(mesh, start, end, from, to);
        // Anchor the wrap at the roof's centre, not the ground: a ramp must not slide its panels round the arch.
        float across = 0;
        for (int i = 0; i < start.length / 2 - 1; i++) { across -= new Vector3(start[i]).dst(new Vector3(start[i + 1])); }
        across -= new Vector3(start[start.length / 2 - 1]).dst(new Vector3(start[start.length / 2])) / 2;
        for (int side = 0; side < start.length - 1; side++) {
            String material = side == 0 || side == start.length - 2 ? WALL : ROOF;
            passagePanel(mesh, hex, material, start[side], start[side + 1], end[side + 1], end[side], across);
            across += new Vector3(start[side]).dst(new Vector3(start[side + 1]));
        }
        // Heavy fitted end flanges and a few widely spaced structural hoops, never an accordion skin.
        int bays = Math.max(2, Math.round((length + GATE_HALF / 2 + 2) / 8));
        for (int rib = 0; rib <= bays; rib++) {
            float centre = (float) rib / bays;
            float half = (rib == 0 || rib == bays ? .75f : .38f) / (length + GATE_HALF / 2 + 2);
            float lo = Math.max(0, centre - half), hi = Math.min(1, centre + half);
            float[][] a = passageProfile(from, to, lo, 0), b = passageProfile(from, to, hi, 0);
            float[][] c = passageProfile(from, to, lo, .55f), d = passageProfile(from, to, hi, .55f);
            float run = 0;
            for (int side = 0; side < a.length - 1; side++) {
                passagePanel(mesh, hex, PIER, c[side], c[side + 1], d[side + 1], d[side], run);
                passagePanel(mesh, hex, PIER, a[side], a[side + 1], c[side + 1], c[side], run);
                passagePanel(mesh, hex, PIER, d[side], d[side + 1], b[side + 1], b[side], run);
                run += new Vector3(c[side]).dst(new Vector3(c[side + 1]));
            }
            if (lod == 0) {
                // Bolted clamp shoes on the roof and shoulders, aligned with each hoop's actual surface.
                for (int side : new int[] { 2, 5, 6, 7, 10 }) {
                    Vector3 acrossRib = new Vector3(c[side + 1]).sub(new Vector3(c[side])).nor();
                    Vector3 alongRib = new Vector3(d[side]).sub(new Vector3(c[side])).nor();
                    Vector3 normal = new Vector3(acrossRib).crs(alongRib).nor();
                    Vector3 centrePoint = new Vector3(c[side]).add(new Vector3(c[side + 1]))
                          .add(new Vector3(d[side])).add(new Vector3(d[side + 1])).scl(.25f).mulAdd(normal, .08f);
                    surfacePlate(mesh, hex, HARDWARE, centrePoint, acrossRib, alongRib, .5f, .32f);
                    fastener(mesh, hex, new Vector3(centrePoint).mulAdd(normal, .025f), normal, .14f);
                }
            }
        }
    }

    /** Broad flat roof, rounded shoulders and vertical walls, recessed into both buildings under their caps. */
    private static float[][] passageProfile(float[] from, float[] to, float t, float relief) {
        float recess = GATE_HALF / 2;
        float sx = from[0] - from[2] * recess, sy = from[1] - from[3] * recess;
        float ex = to[0] + to[2] * 2, ey = to[1] + to[3] * 2;
        float sign = from[2] * to[2] + from[3] * to[3] < 0 ? -1 : 1;
        // Do not normalise the interpolated axes: that would bow the inner wall into the usable passage.
        float ax = from[3] * (1 - t) + to[3] * sign * t, ay = -from[2] * (1 - t) - to[2] * sign * t;
        float x = sx + (ex - sx) * t, y = sy + (ey - sy) * t;
        float top = from[5] * (1 - t) + (to[5] - BEVEL_RISE) * t + relief;
        float half = GATE_HALF - 1.5f + relief, shoulder = Math.min(6, top * .4f);
        int steps = 5;
        List<float[]> result = new ArrayList<>(2 * steps + 4);
        result.add(new float[] { x - ax * half, y - ay * half, 0 });
        for (int corner = 0; corner <= steps; corner++) {
            double angle = Math.PI / 2 * corner / steps;
            float out = -half + shoulder * (1 - (float) Math.cos(angle));
            result.add(new float[] { x + ax * out, y + ay * out, top - shoulder + shoulder * (float) Math.sin(angle) });
        }
        for (int corner = 0; corner <= steps; corner++) {
            double angle = Math.PI / 2 * corner / steps;
            float out = half - shoulder + shoulder * (float) Math.sin(angle);
            result.add(new float[] { x + ax * out, y + ay * out, top - shoulder + shoulder * (float) Math.cos(angle) });
        }
        result.add(new float[] { x + ax * half, y + ay * half, 0 });
        return result.toArray(float[][]::new);
    }

    /** A rigid plate with physical texture distances and a normal derived from the plate, including sloping roofs. */
    private static void passagePanel(GeneratedMesh mesh, List<float[]> hex, String material, float[] a, float[] b,
          float[] c, float[] d, float across) {
        Vector3 origin = new Vector3(a), u = new Vector3(d).sub(origin).nor();
        Vector3 v = new Vector3(b).sub(origin), normal = new Vector3(v).crs(u).nor();
        if (normal.isZero(.0001f)) { return; }
        v.set(u).crs(normal).nor();
        float[][] vertices = new float[4][];
        float[][] points = { a, b, c, d };
        for (int i = 0; i < 4; i++) {
            float[] p = points[i]; Vector3 delta = new Vector3(p).sub(origin);
            float du = delta.dot(u) / (2 * ROOF_REPEAT), dv = (across + delta.dot(v)) / ROOF_REPEAT;
            if (material.equals(WALL)) { du = delta.dot(u) / LEVEL; dv = -p[2] / WALL_REPEAT; }
            vertices[i] = vertex(p[0], p[1], p[2], normal.x, normal.y, normal.z, contact(p[2]), du, dv);
        }
        outward(mesh, material, hex, vertices[0], vertices[1], vertices[2]);
        outward(mesh, material, hex, vertices[0], vertices[2], vertices[3]);
    }

    /** Cut the passage's volume out of the facade, including windows, floor bands and inset panel details. */
    private static void passageOpening(GeneratedMesh mesh, float[][] start, float[][] end, float[] from, float[] to) {
        List<float[]> planes = new ArrayList<>();
        planes.add(new float[] { -from[2], -from[3], 0, -from[2] * start[0][0] - from[3] * start[0][1] });
        planes.add(new float[] { to[2], to[3], 0, to[2] * end[0][0] + to[3] * end[0][1] });
        planes.add(new float[] { 0, 0, -1, .01f });
        var bounds = new BoundingBox();
        for (int i = 0; i < start.length; i++) { bounds.ext(new Vector3(start[i])); bounds.ext(new Vector3(end[i])); }
        for (int i = 0; i < start.length - 1; i++) {
            Vector3 a = new Vector3(start[i]), b = new Vector3(start[i + 1]), c = new Vector3(end[i + 1]), d = new Vector3(end[i]);
            Vector3 n = new Vector3(b).sub(a).crs(new Vector3(d).sub(a)).nor();
            planes.add(new float[] { n.x, n.y, n.z, n.dot(a) });
            n.set(c).sub(b).crs(new Vector3(d).sub(b)).nor();
            planes.add(new float[] { n.x, n.y, n.z, n.dot(b) });
        }
        for (var entry : mesh.parts.entrySet()) {
            FloatArray source = entry.getValue(), result = new FloatArray();
            for (int at = 0; at < source.size; at += 3 * RigidGlb.STRIDE) {
                List<float[]> polygon = new ArrayList<>(3);
                BoundingBox triangle = new BoundingBox();
                for (int corner = 0; corner < 3; corner++) {
                    float[] v = new float[RigidGlb.STRIDE];
                    System.arraycopy(source.items, at + corner * RigidGlb.STRIDE, v, 0, v.length);
                    polygon.add(v); triangle.ext(new Vector3(v[0], v[1], v[2]));
                }
                if (!bounds.intersects(triangle)) {
                    result.addAll(source.items, at, 3 * RigidGlb.STRIDE); continue;
                }
                // Each outside piece is disjoint; only what remains inside all planes is the removed opening.
                for (float[] plane : planes) {
                    List<float[]> outside = passageClip(polygon, plane, true);
                    for (int i = 1; i < outside.size() - 1; i++) {
                        result.addAll(outside.getFirst()); result.addAll(outside.get(i)); result.addAll(outside.get(i + 1));
                    }
                    polygon = passageClip(polygon, plane, false);
                    if (polygon.isEmpty()) { break; }
                }
            }
            entry.setValue(result);
        }
    }

    /** Clip a facade triangle against a passage plane, interpolating positions, normals, colour and UVs together. */
    private static List<float[]> passageClip(List<float[]> polygon, float[] plane, boolean outside) {
        List<float[]> result = new ArrayList<>();
        float sign = outside ? -1 : 1;
        for (int i = 0; i < polygon.size(); i++) {
            float[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            float da = sign * (plane[0] * a[0] + plane[1] * a[1] + plane[2] * a[2] - plane[3]);
            float db = sign * (plane[0] * b[0] + plane[1] * b[1] + plane[2] * b[2] - plane[3]);
            if (da <= 0) { result.add(a); }
            if (da < 0 != db < 0 && da != 0 && db != 0) {
                float share = da / (da - db); float[] v = new float[a.length];
                for (int k = 0; k < v.length; k++) { v[k] = a[k] + (b[k] - a[k]) * share; }
                Vector3 n = new Vector3(v[3], v[4], v[5]).nor(); v[3] = n.x; v[4] = n.y; v[5] = n.z;
                result.add(v);
            }
        }
        return result.size() < 3 ? List.of() : result;
    }

    /**
     * An airlock's tube's radius and its ribs' depth and pitch: a flexible duct a little narrower than an airlock; the
     * room a gatehouse keeps from an annex's airlocks and gates for their tubes and passages.
     */
    private static final float TUBE = 5, RIB = .5f, PITCH = 2, AIRLOCK_ROOM = 10;

    /**
     * A flexible pressurised tube, cut to the hex: a ribbed duct on a curve from {@code from} to {@code to} (each x, y,
     * the way the tube runs there, and its floor), {@code radius} round, heavy collars at its ends, saddles under it down
     * to the ground; or ({@code tunnel}) a ribbed tunnel {@code radius} either side, its walls on the ground and its
     * roof arched over them (#arch).
     */
    private static void bellows(GeneratedMesh mesh, List<float[]> hex, float[] from, float[] to, int lod, float radius,
          boolean tunnel) {
        float chord = (float) Math.hypot(to[0] - from[0], to[1] - from[1]), reach = chord * .4f;
        if (chord < 3) { return; }
        // A cubic curve leaving and arriving along the given ways, measured finely for even ribs.
        float[][] curve = { { from[0], from[1] }, { from[0] + from[2] * reach, from[1] + from[3] * reach },
              { to[0] - to[2] * reach, to[1] - to[3] * reach }, { to[0], to[1] } };
        int samples = 64;
        float[] lengths = new float[samples + 1];
        float[] last = point(curve, 0), previous = tangent(curve, 0);
        float bend = 0;
        for (int sample = 1; sample <= samples; sample++) {
            float[] next = point(curve, (float) sample / samples);
            float[] direction = tangent(curve, (float) sample / samples);
            float distance = (float) Math.hypot(next[0] - last[0], next[1] - last[1]);
            lengths[sample] = lengths[sample - 1] + distance;
            float turn = (float) Math.atan2(previous[0] * direction[1] - previous[1] * direction[0],
                  previous[0] * direction[0] + previous[1] * direction[1]);
            bend = Math.max(bend, Math.abs(turn) / Math.max(EPSILON, distance));
            last = next;
            previous = direction;
        }
        // A short, wide elbow cannot follow its centreline without folding its inner wall. Loft corresponding
        // points of the two openings instead: a gradual transition that keeps both collars and the passage open.
        boolean loft = bend * (radius + 1.1f) > .8f;
        float total = loft ? chord : lengths[samples];
        float endSign = from[2] * to[2] + from[3] * to[3] < 0 ? -1 : 1;
        int stations = Math.max(6, Math.round(total / (PITCH / 2)) + 1);
        // Round its ring, or its arch's feet and roof (#arch).
        int sides = tunnel ? (lod == 0 ? 12 : 6) + 2 : lod == 0 ? 14 : 8;
        float rib = tunnel ? .7f : RIB;
        float[][] centres = new float[stations][], along = new float[stations][], across = new float[stations][];
        float[][] ups = new float[stations][];
        float[] radii = new float[stations], rises = new float[stations];
        float climb = (to[4] - from[4]) / total;
        for (int station = 0; station < stations; station++) {
            float fraction = (float) station / (stations - 1), distance = total * fraction, t = fraction;
            if (!loft) {
                for (int sample = 1; sample <= samples; sample++) {
                    if (lengths[sample] >= distance) {
                        float share = (distance - lengths[sample - 1]) / Math.max(1e-6f, lengths[sample] - lengths[sample - 1]);
                        t = (sample - 1 + share) / samples;
                        break;
                    }
                }
            }
            float[] at = loft ? new float[] { from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t }
                  : point(curve, t);
            float[] slope = loft ? new float[] { from[2] * (1 - t) + to[2] * endSign * t,
                  from[3] * (1 - t) + to[3] * endSign * t } : tangent(curve, t);
            float flat = (float) Math.hypot(slope[0], slope[1]), tx = slope[0] / flat, ty = slope[1] / flat;
            float floor = from[4] + (to[4] - from[4]) * distance / total, length = (float) Math.sqrt(1 + climb * climb);
            centres[station] = new float[] { at[0], at[1], floor + (tunnel ? SPRING : radius + .4f) };
            // A tunnel's roof over its springing: up to the height its ends ask for, between them in turn.
            rises[station] = tunnel ? Math.max(2, from[5] + (to[5] - from[5]) * distance / total - floor - SPRING) : 0;
            along[station] = new float[] { tx / length, ty / length, climb / length };
            // Linear interpolation of the opening's axes keeps the loft's side paths straight. Do not normalise
            // it: doing so bows the inner wall back into a fold at a tight bend.
            across[station] = loft ? new float[] { slope[1], -slope[0], 0 } : new float[] { ty, -tx, 0 };
            // Up, square to the tube: across x along.
            float[] a = along[station];
            ups[station] = new float[] { -tx * a[2], -ty * a[2], ty * a[1] + tx * a[0] };
            // Collars at the ends, ribs between.
            radii[station] = station <= 1 || station >= stations - 2 ? radius + 1.1f : station % 2 == 0 ? radius
                  : radius + rib;
        }
        // Derive the texture distances and normals from the drawn surface, including its changing roof height,
        // collars and bends. Equal texture density in both directions keeps the corrugations from smearing panels.
        float[][][] profiles = new float[stations][sides + 1][];
        float[][] us = new float[stations][sides + 1], vs = new float[stations][sides + 1];
        for (int station = 0; station < stations; station++) {
            for (int side = 0; side <= sides; side++) {
                float[] p = tunnel ? arch(centres[station], across[station], radii[station],
                      rises[station] + radii[station] - radius, side, sides)
                      : ring(centres[station], across[station], ups[station], radii[station], side, sides);
                profiles[station][side] = p;
                if (station > 0) {
                    float[] q = profiles[station - 1][side];
                    us[station][side] = us[station - 1][side] + Vector3.dst(p[0], p[1], p[2], q[0], q[1], q[2]) / 12;
                }
                if (side > 0) {
                    float[] q = profiles[station][side - 1];
                    vs[station][side] = vs[station][side - 1] + Vector3.dst(p[0], p[1], p[2], q[0], q[1], q[2]) / 12;
                }
            }
        }
        for (int band = 0; band < stations - 1; band++) {
            String material = band == 0 || band == stations - 2 ? PIER : FRAME;
            for (int side = 0; side < sides; side++) {
                float[] a = bellowsVertex(profiles, us, vs, band, band, side, tunnel);
                float[] b = bellowsVertex(profiles, us, vs, band, band, side + 1, tunnel);
                float[] c = bellowsVertex(profiles, us, vs, band, band + 1, side + 1, tunnel);
                float[] d = bellowsVertex(profiles, us, vs, band, band + 1, side, tunnel);
                outward(mesh, material, hex, a, b, c);
                outward(mesh, material, hex, a, c, d);
            }
        }
        // Saddles every few metres, clear of the collars; a tunnel stands on its walls.
        for (int station = 3; station < stations - 3 && !tunnel; station += 3) {
            float[] centre = centres[station], x = across[station];
            float bottom = centre[2] - radius;
            if (bottom > .3f) {
                // Square to the tube on the ground: across turned back a quarter.
                clippedBlock(mesh, hex, PIER, centre[0], centre[1], -x[1], x[0], .5f, radius * .8f, 0, bottom + .4f);
            }
        }
    }

    /**
     * A tunnel's cross-section: left foot on the ground, the half ellipse over its springing, and right foot.
     */
    private static float[] arch(float[] centre, float[] across, float radius, float rise, int point, int points) {
        float out, z;
        if (point == 0 || point == points) {
            out = point == 0 ? -1 : 1;
            z = 0;
        } else {
            double angle = Math.PI * (points - 1 - point) / (points - 2);
            out = (float) Math.cos(angle);
            z = centre[2] + (float) Math.sin(angle) * rise;
        }
        return new float[] { centre[0] + across[0] * out * radius, centre[1] + across[1] * out * radius, z };
    }

    /** A tube's round cross-section in its local frame. */
    private static float[] ring(float[] centre, float[] across, float[] up, float radius, int side, int sides) {
        double angle = 2 * Math.PI * side / sides;
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new float[] { centre[0] + (across[0] * c + up[0] * s) * radius,
              centre[1] + (across[1] * c + up[1] * s) * radius, centre[2] + up[2] * s * radius };
    }

    /** Smooth around an arch, sharp at its corrugations: the normal uses the two actual neighbouring profiles. */
    private static float[] bellowsVertex(float[][][] profiles, float[][] us, float[][] vs, int band, int station,
          int point, boolean tunnel) {
        int sides = profiles[station].length - 1;
        int before = point == 0 ? (tunnel ? 0 : sides - 1) : point - 1;
        int after = point == sides ? (tunnel ? sides : 1) : point + 1;
        var across = new Vector3(profiles[station][after]).sub(new Vector3(profiles[station][before]));
        var along = new Vector3(profiles[band + 1][point]).sub(new Vector3(profiles[band][point]));
        var normal = across.crs(along).nor().scl(tunnel ? 1 : -1);
        float[] p = profiles[station][point];
        return vertex(p[0], p[1], p[2], normal.x, normal.y, normal.z, contact(p[2]), us[station][point], vs[station][point]);
    }

    /** A triangle of a tube's wall, wound to face along its vertices' normals. */
    private static void outward(GeneratedMesh mesh, String material, List<float[]> hex, float[] a, float[] b, float[] c) {
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
    private static void shell(GeneratedMesh mesh, float top, float[] reach) {
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
    private static void walls(GeneratedMesh mesh, String material, List<float[]> outline, int closed, float inset, float bottom,
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
    private static final float CORNER_PIER = 3.3f, PIER_DEPTH = 2.8f, PIER_CHAMFER = .85f, FOOTING_GROW = .9f,
          FOOTING_DEPTH = 3.2f, FOOTING = .5f;
    /** A pier facing (nx, ny) at (x, y) on a facade, its back buried in the wall even at a corner; on a footing. */
    private static void pier(GeneratedMesh mesh, List<float[]> hex, float x, float y, float nx, float ny, float half, float bottom, float top,
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
            face(mesh, hex, PIER, a, b, normal[0], normal[1], bottom, top, 0, u);
        }
        clippedFlat(mesh, hex, PIER, counterClockwise(ring), top);
        postCap(mesh, hex, ring, top - .6f, top + 1.3f, .55f);
        postCap(mesh, hex, ring, bottom, bottom + .7f, .55f);
        postDetails(mesh, hex, new float[] { x, y }, tx, ty, 0, bottom, top, PIER_DEPTH, half - PIER_CHAMFER, false);
        if (footing) {
            float grown = half + FOOTING_GROW;
            List<float[]> base = new ArrayList<>(4);
            for (float[] point : new float[][] { { grown, back }, { grown, FOOTING_DEPTH }, { -grown, FOOTING_DEPTH }, { -grown, back } }) {
                base.add(new float[] { x + tx * point[0] + nx * point[1], y + ty * point[0] + ny * point[1] });
            }
            prism(mesh, hex, PLINTH_MATERIAL, base, bottom, bottom + FOOTING, 3);
        }
    }

    /**
     * An upright prism over {@code outline} (convex) from {@code bottom} to {@code top}, its roof and the faces of its
     * first {@code faces} edges, cut to the hex unless that is null; facades' texture, one repeat per {@link #WALL_REPEAT}.
     */
    private static void prism(GeneratedMesh mesh, List<float[]> hex, String material, List<float[]> outline, float bottom,
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
    private static void facade(GeneratedMesh mesh, String material, float[] a, float[] b, float[] side, float bottom, float ceiling) {
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
    private static void block(GeneratedMesh mesh, String material, float x, float y, float rx, float ry, float along, float across,
          float bottom, float top) {
        List<float[]> corners = new ArrayList<>(4);
        List<float[]> roof = new ArrayList<>(4);
        for (float[] sign : new float[][] { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) {
            float px = x + rx * along * sign[0] - ry * across * sign[1];
            float py = y + ry * along * sign[0] + rx * across * sign[1];
            corners.add(new float[] { px, py });
            // The metal's grain runs in V, along the piece, at the same scale as its sides. These local UVs also
            // survive clippedBlock's hex cuts without restarting at each share's origin.
            roof.add(vertex(px, py, top, 0, 0, 1, 1, (sign[1] + 1) * across / 12, (sign[0] + 1) * along / 12));
        }
        mesh.polygon(material, roof);
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
    private static void flat(GeneratedMesh mesh, String material, List<float[]> polygon, float z, float shade) {
        if (polygon.isEmpty()) { return; }
        List<float[]> vertices = new ArrayList<>(polygon.size());
        for (float[] point : polygon) { vertices.add(deck(point[0], point[1], z, shade)); }
        for (int i = 1; i < vertices.size() - 1; i++) { mesh.triangle(material, vertices.get(0), vertices.get(i), vertices.get(i + 1)); }
    }

    /** {@link #flat}, cut to the hex: a part of a dome's building over whichever shares it stands. */
    private static void clippedFlat(GeneratedMesh mesh, List<float[]> hex, String material, List<float[]> polygon, float z) {
        clippedPolygon(mesh, hex, material, polygon.stream().map(point -> deck(point[0], point[1], z, 1)).toList());
    }

    /** Concave corridor bends need triangulation rather than a fan across their empty inside corner. */
    private static void clippedPolygon(GeneratedMesh mesh, List<float[]> hex, String material, List<float[]> vertices) {
        float[] xy = new float[vertices.size() * 2];
        for (int index = 0; index < vertices.size(); index++) {
            xy[index * 2] = vertices.get(index)[0]; xy[index * 2 + 1] = vertices.get(index)[1];
        }
        var indices = new EarClippingTriangulator().computeTriangles(xy);
        for (int index = 0; index < indices.size; index += 3) {
            mesh.clipped(material, hex, vertices.get(indices.get(index)), vertices.get(indices.get(index + 2)), vertices.get(indices.get(index + 1)));
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
    private static void dome(GeneratedMesh mesh, Dome dome, int lod) {
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
     * panels inside and out, roofed in regolith concrete laid round the ring; yellow buttress fins at
     * its six corners between the pylons and the middle of each side, where no entrance wing arrives.
     */
    private static void podium(GeneratedMesh mesh, List<float[]> hex, Dome dome) {
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
            // Each rib-bounded bay has a straight outer edge. Lay square panels along that edge, rather than
            // warping them by angle/radius. All four segments and all hex shares use the same bay's axes.
            double bay = Math.PI / sides * (2 * (segment / steps) + 1);
            float ux = -(float) Math.sin(bay), uy = (float) Math.cos(bay);
            float[][] corners = { deck(ax, ay, top, 1), deck(bx, by, top, 1),
                  deck(x + inner * cb, y + inner * sb, top, 1), deck(x + inner * ca, y + inner * sa, top, 1) };
            for (float[] v : corners) {
                float dx = v[0] - x, dy = v[1] - y;
                v[10] = (dx * ux + dy * uy + corner * (float) Math.sin(Math.PI / sides)) / ROOF_REPEAT;
                v[11] = (dx * uy - dy * ux - flat) / ROOF_REPEAT;
            }
            mesh.clipped(ROOF, hex, corners[0], corners[1], corners[2]);
            mesh.clipped(ROOF, hex, corners[0], corners[2], corners[3]);
            // Its inner wall faces the dome, under the glass.
            float[] i0 = vertex(x + inner * cb, y + inner * sb, bottom, -cb, -sb, 0, contact(0), u1, v0);
            float[] i1 = vertex(x + inner * ca, y + inner * sa, bottom, -ca, -sa, 0, contact(0), u0, v0);
            float[] i2 = vertex(x + inner * ca, y + inner * sa, top, -ca, -sa, 0, 1, u0, v1);
            float[] i3 = vertex(x + inner * cb, y + inner * sb, top, -cb, -sb, 0, 1, u1, v1);
            mesh.clipped(WALL, hex, i0, i1, i2);
            mesh.clipped(WALL, hex, i0, i2, i3);
        }
        // One white-lit pier at each ring bay, shared by clipping. Passage mouths cut it later where necessary.
        for (int pier = 0; pier < sides; pier++) {
            double angle = Math.toRadians(30 * pier);
            float px = x + corner * (float) Math.cos(angle), py = y + corner * (float) Math.sin(angle);
            float foot = dome.pierFeet().get(pier);
            if (foot < top - 1) {
                pier(mesh, hex, px, py, (float) Math.cos(angle), (float) Math.sin(angle), CORNER_PIER, foot, top,
                      foot <= bottom + EPSILON);
            }
        }
        for (int fin = 0; fin < 18; fin++) {
            // The six corners between the pylons, then the twelve sides' middles, set in to the side's line.
            double angle = Math.toRadians(fin < 6 ? 60 * fin : 15 + 30 * (fin - 6));
            if (!dome.joined(angle, 0)) { fin(mesh, hex, dome, (float) angle, fin < 6 ? corner : flat, bottom, top); }
        }
        // The ring roof in bays, one a side: steel ribs on the corners' radial lines and a steel rim along the outline.
        float rib = 1.25f, rim = 1.9f;
        for (int k = 0; k < sides; k++) {
            double angle = 2 * Math.PI * k / sides;
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle), reach = (corner - inner) / 2;
            clippedBlock(mesh, hex, PIER, x + ux * (inner + reach), y + uy * (inner + reach), ux, uy, reach, rib, top,
                  top + .95f);
            clippedBlock(mesh, hex, HARDWARE, x + ux * (inner + reach), y + uy * (inner + reach), ux, uy,
                  reach, .85f, top + .92f, top + 1.02f);
            for (float at : new float[] { inner + 2, corner - 2 }) {
                clippedBlock(mesh, hex, PIER, x + ux * at, y + uy * at, ux, uy, .5f, rib + .22f, top, top + 1.1f);
                fastener(mesh, hex, new Vector3(x + ux * at, y + uy * at, top + 1.12f), Vector3.Z, .18f);
            }
        }
        float half = corner * (float) Math.sin(Math.PI / sides);
        for (int side = 0; side < sides; side++) {
            double angle = Math.PI / sides * (2 * side + 1);
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
            float[] a = { x + flat * ux + half * uy, y + flat * uy - half * ux };
            float[] b = { x + flat * ux - half * uy, y + flat * uy + half * ux };
            float scale = 1 - 2 * rim / flat;
            float[] c = { x + (a[0] - x) * scale, y + (a[1] - y) * scale };
            float[] d = { x + (b[0] - x) * scale, y + (b[1] - y) * scale };
            coping(mesh, hex, a, b, c, d, top, 2 * half * side);
            facadeDetails(mesh, hex, a, b, bottom, top, 2 * half * side, true, true, false);
        }
    }

    /** {@link #block}, cut to the hex: a podium's parts belong to whichever shares they stand over. */
    private static void clippedBlock(GeneratedMesh mesh, List<float[]> hex, String material, float x, float y, float rx, float ry,
          float along, float across, float bottom, float top) {
        var part = new GeneratedMesh();
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
    private static void fin(GeneratedMesh mesh, List<float[]> hex, Dome dome, float angle, float corner, float bottom, float top) {
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
    private static void module(GeneratedMesh mesh, List<float[]> hex, Dome dome, int at) {
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
        // The blast door: a dark steel frame round a pale door under a yellow lintel; a gate's passage docked to it hides it.
        float door = Math.min(15, rise - 3), fx = cx + ux * outer, fy = cy + uy * outer;
        if (!docked(dome, ux, uy)) {
            clippedBlock(mesh, hex, PIER, fx + ux * .5f, fy + uy * .5f, px, py, 7.5f, 1, bottom, bottom + door);
            clippedBlock(mesh, hex, FRAME, fx + ux * 1.1f, fy + uy * 1.1f, px, py, 5.5f, .6f, bottom, bottom + door - 1.8f);
            clippedBlock(mesh, hex, FIN_MATERIAL, fx + ux * .6f, fy + uy * .6f, px, py, 7.5f, 1.2f, bottom + door,
                  bottom + door + 1.3f);
        }
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
    private static void moduleBlock(GeneratedMesh mesh, List<float[]> hex, List<float[]> walls, float bottom, float top) {
        moduleBlock(mesh, hex, walls, bottom, top, null, null);
    }

    /** Open corridor cuts have neither a wall nor a bevel across the shared roof. */
    private static void moduleBlock(GeneratedMesh mesh, List<float[]> hex, List<float[]> walls, float bottom, float top,
          List<Float> openings, Point origin) {
        moduleBlock(mesh, hex, walls, bottom, top, openings, origin, true);
    }

    private static void moduleBlock(GeneratedMesh mesh, List<float[]> hex, List<float[]> walls, float bottom, float top,
          List<Float> openings, Point origin, boolean detailed) {
        List<float[]> cap = walls;
        List<float[]> roof;
        List<float[]> facades = walls;
        if (openings == null) { roof = parapets(walls, BEVEL); }
        else {
            // A height-step facade lies on the hex cut. Recess its cladding so its relief stays in this share;
            // the full-depth floor bands close the ledges up to the adjoining roof and the coping.
            facades = facadeOutline(walls, openings, top);
            // An open hex cut is not a roof edge. Extend it before bevelling, then let the shared hex clip trim the
            // finished cap. Insetting an already clipped sliver can invert its corners and leave overlapping faces.
            cap = new ArrayList<>();
            for (int index = 0; index < walls.size(); index++) {
                int before = (index + walls.size() - 1) % walls.size(), after = (index + 1) % walls.size();
                cap.add(meet(shifted(walls.get(before), walls.get(index), openings.get(before) >= top - EPSILON ? -2 * BEVEL : 0),
                      shifted(walls.get(index), walls.get(after), openings.get(index) >= top - EPSILON ? -2 * BEVEL : 0)));
            }
            roof = new ArrayList<>();
            for (int index = 0; index < walls.size(); index++) {
                int before = (index + walls.size() - 1) % walls.size(), after = (index + 1) % walls.size();
                roof.add(meet(shifted(cap.get(before), cap.get(index), openings.get(before) >= top - EPSILON ? 0 : BEVEL),
                      shifted(cap.get(index), cap.get(after), openings.get(index) >= top - EPSILON ? 0 : BEVEL)));
            }
        }
        float along = 0;
        for (int edge = 0; edge < walls.size(); edge++) {
            int next = (edge + 1) % walls.size();
            float[] a = walls.get(edge), b = walls.get(next), c = roof.get(edge), d = roof.get(next);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float nx = (b[1] - a[1]) / length, ny = -(b[0] - a[0]) / length;
            if (openings != null && openings.get(edge) >= top - EPSILON) { along += length; continue; }
            // Only the fixed module outline alternates cut corners. A union's vertex order is not a facade design.
            float wallU = origin == null ? along : (a[0] + origin.x()) * -ny + (a[1] + origin.y()) * nx;
            float[] fa = facades.get(edge), fb = facades.get(next);
            float facadeU = wallU + (fa[0] - a[0]) * -ny + (fa[1] - a[1]) * nx;
            float facadeLength = (float) Math.hypot(fb[0] - fa[0], fb[1] - fa[1]);
            face(mesh, hex, openings == null && edge % 2 == 1 ? PIER : WALL, fa, fb, nx, ny,
                  openings == null ? bottom : Math.max(bottom, openings.get(edge)), top - BEVEL_RISE,
                  facadeU / WALL_REPEAT, (facadeU + facadeLength) / WALL_REPEAT);
            if (detailed) {
                boolean startCorner = openings == null || openings.get((edge + walls.size() - 1) % walls.size()) < top - EPSILON;
                boolean endCorner = openings == null || openings.get(next) < top - EPSILON;
                facadeDetails(mesh, hex, fa, fb, openings == null ? bottom : Math.max(bottom, openings.get(edge)),
                      top - BEVEL_RISE, facadeU, startCorner, endCorner, openings == null);
            }
            float phase = wallU + (cap.get(edge)[0] - a[0]) * -ny + (cap.get(edge)[1] - a[1]) * nx;
            coping(mesh, hex, cap.get(edge), cap.get(next), c, d, top, phase);
            along += length;
        }
        if (detailed && openings == null) { cornerPosts(mesh, hex, facades, null, bottom, top - BEVEL_RISE); }
        // Its roof laid along the block (#moduleOutline's first edge), from its first corner, alike in every share.
        float[] start = walls.getFirst(), next = walls.get(1);
        float length = (float) Math.hypot(next[0] - start[0], next[1] - start[1]);
        float ux = (next[0] - start[0]) / length, uy = (next[1] - start[1]) / length;
        List<float[]> laid = new ArrayList<>(roof.size());
        for (float[] corner : roof) {
            if (origin != null) {
                float[] v = deck(corner[0], corner[1], top, 1); roofUV(v, origin); laid.add(v); continue;
            }
            float dx = corner[0] - start[0], dy = corner[1] - start[1];
            laid.add(vertex(corner[0], corner[1], top, 0, 0, 1, 1, (dx * ux + dy * uy) / ROOF_REPEAT,
                  (dx * uy - dy * ux) / ROOF_REPEAT));
        }
        clippedPolygon(mesh, hex, ROOF, laid);
    }

    /** Boxed metal coping: a vertical fascia, narrow edge chamfers and a broad flat cap with fitted joints. */
    private static void coping(GeneratedMesh mesh, List<float[]> hex, float[] a, float[] b, float[] c, float[] d,
          float top, float phase) {
        float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        float tx = (b[0] - a[0]) / length, ty = (b[1] - a[1]) / length, nx = ty, ny = -tx;
        float width = (a[0] - c[0]) * nx + (a[1] - c[1]) * ny;
        float[] fraction = { 0, 0, .12f, .88f, 1 }, z = { top - BEVEL_RISE, top - .15f, top + .25f, top + .25f, top };
        float run = 0;
        for (int band = 0; band < fraction.length - 1; band++) {
            float next = run + (float) Math.hypot(width * (fraction[band + 1] - fraction[band]), z[band + 1] - z[band]);
            Vector3 normal = new Vector3(nx * (z[band + 1] - z[band]), ny * (z[band + 1] - z[band]),
                  width * (fraction[band + 1] - fraction[band])).nor();
            float[][] corners = new float[4][];
            for (int i = 0; i < 4; i++) {
                int level = i < 2 ? band : band + 1;
                boolean end = i == 1 || i == 2;
                float[] outer = end ? b : a, inner = end ? d : c;
                float x = outer[0] + (inner[0] - outer[0]) * fraction[level];
                float y = outer[1] + (inner[1] - outer[1]) * fraction[level];
                corners[i] = vertex(x, y, z[level], normal.x, normal.y, normal.z, 1,
                      -(i < 2 ? run : next) / 12, (phase + (x - a[0]) * tx + (y - a[1]) * ty) / 12);
            }
            outward(mesh, PIER, hex, corners[0], corners[1], corners[2]);
            outward(mesh, PIER, hex, corners[0], corners[2], corners[3]);
            if (band == 2) {
                // Fitted gunmetal top plates sit over the dark box beam; leave a narrow joint between plates.
                float first = (float) Math.floor(phase / 9) * 9 - phase;
                for (float at = first; at < length; at += 9) {
                    float lo = Math.max(0, at + .12f), hi = Math.min(length, at + 8.88f);
                    if (hi <= lo) { continue; }
                    float[][] plate = new float[4][];
                    for (int k = 0; k < 4; k++) {
                        float t = (k == 1 || k == 2 ? hi : lo) / length;
                        float[] start = corners[k < 2 ? 0 : 3], end = corners[k < 2 ? 1 : 2];
                        plate[k] = new float[RigidGlb.STRIDE];
                        for (int channel = 0; channel < RigidGlb.STRIDE; channel++) {
                            plate[k][channel] = start[channel] + (end[channel] - start[channel]) * t;
                        }
                        plate[k][2] += .025f;
                    }
                    outward(mesh, HARDWARE, hex, plate[0], plate[1], plate[2]);
                    outward(mesh, HARDWARE, hex, plate[0], plate[2], plate[3]);
                }
            }
            run = next;
        }
        for (float at = (float) Math.ceil((phase + width) / 9) * 9 - phase; at < length - width; at += 9) {
            Vector3 centre = new Vector3(a[0] + tx * at - nx * width / 2, a[1] + ty * at - ny * width / 2, top + .31f);
            surfacePlate(mesh, hex, HARDWARE, centre, new Vector3(tx, ty, 0), new Vector3(-nx, -ny, 0), .12f, width * .35f);
            for (float inset : new float[] { -.25f, .25f }) {
                fastener(mesh, hex, new Vector3(centre).add(tx * .28f + nx * width * inset,
                      ty * .28f + ny * width * inset, .015f), Vector3.Z, .13f);
            }
        }
    }

    private static void roofUV(float[] vertex, Point origin) {
        vertex[10] = (vertex[0] + origin.x()) / ROOF_REPEAT;
        vertex[11] = -(vertex[1] + origin.y()) / ROOF_REPEAT;
    }

    /** The drawn cladding outline: height-step walls leave room for their relief within their own share. */
    private static List<float[]> facadeOutline(List<float[]> walls, List<Float> openings, float top) {
        if (openings == null) { return walls; }
        List<float[]> result = new ArrayList<>();
        for (int index = 0; index < walls.size(); index++) {
            int before = (index + walls.size() - 1) % walls.size(), after = (index + 1) % walls.size();
            float previousInset = openings.get(before) > 0 && openings.get(before) < top - EPSILON ? 1.1f : 0;
            float nextInset = openings.get(index) > 0 && openings.get(index) < top - EPSILON ? 1.1f : 0;
            result.add(meet(shifted(walls.get(before), walls.get(index), previousInset),
                  shifted(walls.get(index), walls.get(after), nextInset)));
        }
        return result;
    }

    /** The same framed habitat facade on corridors and dome rings. Roof equipment is placed by the editor. */
    private static void facadeDetails(GeneratedMesh mesh, List<float[]> hex, float[] a, float[] b, float bottom, float top,
          float phase, boolean startCorner, boolean endCorner, boolean majorPosts) {
        float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (length < 1 || top - bottom < 2) { return; }
        float tx = (b[0] - a[0]) / length, ty = (b[1] - a[1]) / length;
        float first = startCorner ? 1.7f : 0, last = length - (endCorner ? 1.7f : 0), pitch = LEVEL / 2;
        for (float floor = (float) Math.floor(bottom / LEVEL) * LEVEL; floor < top; floor += LEVEL) {
            float low = Math.max(bottom, floor), high = Math.min(top, floor + LEVEL);
            if (high - low < 1) { continue; }
            facadeStrip(mesh, hex, PIER, a, tx, ty, 0, length, .55f, 1.1f, low, low + .8f);
            facadeStrip(mesh, hex, PIER, a, tx, ty, 0, length, .55f, 1.1f, high - .9f, high);
            facadeStrip(mesh, hex, HARDWARE, a, tx, ty, 0, length, 1.06f, .08f, low + .6f, low + .76f);
            if (high - low < 7 || length < 8) { continue; }
            float sill = high - 5.2f, lintel = high - 2;
            // Glass sits back from the deep sill and lintel, on a dark gasket. Its bounded UVs sample a complete
            // pressure window from the habitat material rather than tiling its entire wall over each pane.
            facadeStrip(mesh, hex, PIER, a, tx, ty, first, last, .2f, .45f, sill - .35f, lintel + .35f);
            facadeStrip(mesh, hex, PIER, a, tx, ty, first, last, .5f, .7f, sill - .6f, sill);
            facadeStrip(mesh, hex, PIER, a, tx, ty, first, last, .5f, .7f, lintel, lintel + .6f);
            facadeStrip(mesh, hex, HARDWARE, a, tx, ty, first, last, .88f, .08f, sill - .22f, sill - .08f);
            for (int bay = (int) Math.floor((phase + first) / pitch); bay * pitch < phase + last; bay++) {
                float from = bay * pitch - phase + .8f, to = (bay + 1) * pitch - phase - .8f;
                float lo = Math.max(first, from), hi = Math.min(last, to);
                if (hi - lo < .1f) { continue; }
                float u0 = (lo - from) / (to - from), u1 = (hi - from) / (to - from);
                float x0 = a[0] + tx * lo + ty * .44f, y0 = a[1] + ty * lo - tx * .44f;
                float x1 = a[0] + tx * hi + ty * .44f, y1 = a[1] + ty * hi - tx * .44f;
                float[] v0 = vertex(x0, y0, sill, ty, -tx, 0, 1, u0, 1), v1 = vertex(x1, y1, sill, ty, -tx, 0, 1, u1, 1);
                float[] v2 = vertex(x1, y1, lintel, ty, -tx, 0, 1, u1, 0), v3 = vertex(x0, y0, lintel, ty, -tx, 0, 1, u0, 0);
                mesh.clipped(WINDOW, hex, v0, v1, v2); mesh.clipped(WINDOW, hex, v0, v2, v3);
            }
        }
        // Positions come from the complete facade's phase, so a clipped hex share does not restart the bays.
        for (int bay = (int) Math.floor((phase - .5f) / LEVEL); bay * LEVEL < phase + length + .5f; bay++) {
            float at = bay * LEVEL - phase;
            if (startCorner && at < 1.5f || endCorner && at > length - 1.5f) { continue; }
            if (majorPosts && Math.floorMod(bay, 4) == 0 && (!startCorner || at > 4) && (!endCorner || at < length - 4)) {
                facadePost(mesh, hex, new float[] { a[0] + tx * at, a[1] + ty * at }, tx, ty, bottom, top, 1.3f, false);
                continue;
            }
            facadeStrip(mesh, hex, PIER, a, tx, ty, Math.max(0, at - .6f), Math.min(length, at + .6f),
                  .35f, 1.1f, bottom, top);
        }
    }

    /** A strip of facade relief, measured along its wall and out from its surface. */
    private static void facadeStrip(GeneratedMesh mesh, List<float[]> hex, String material, float[] a, float tx, float ty,
          float from, float to, float out, float depth, float bottom, float top) {
        if (to - from < EPSILON || top - bottom < EPSILON) { return; }
        float middle = (from + to) / 2;
        clippedBlock(mesh, hex, material, a[0] + tx * middle + ty * out, a[1] + ty * middle - tx * out,
              tx, ty, (to - from) / 2, depth / 2, bottom, top);
    }

    /** One substantial pillar faces the corner's open space, including concave corners and raised-height steps. */
    private static void cornerPosts(GeneratedMesh mesh, List<float[]> hex, List<float[]> outline, List<Float> openings,
          float bottom, float top) {
        for (int i = 0; i < outline.size(); i++) {
            int before = (i + outline.size() - 1) % outline.size();
            if (openings != null && (openings.get(before) >= top || openings.get(i) >= top)) { continue; }
            float[] a = outline.get(before), b = outline.get(i), c = outline.get((i + 1) % outline.size());
            Vector3 incoming = new Vector3(b[0] - a[0], b[1] - a[1], 0).nor();
            Vector3 outgoing = new Vector3(c[0] - b[0], c[1] - b[1], 0).nor();
            float turn = incoming.x * outgoing.y - incoming.y * outgoing.x;
            if (Math.abs(turn) < .01f) { continue; }
            float low = openings == null ? bottom : Math.max(bottom, Math.min(openings.get(before), openings.get(i)));
            cornerPost(mesh, hex, b, new Point(incoming.y, -incoming.x), new Point(outgoing.y, -outgoing.x),
                  low, top, turn > 0);
        }
    }

    /** A corner housing wraps both walls: its broad faces and their lamps follow the walls, not their bisector. */
    private static void cornerPost(GeneratedMesh mesh, List<float[]> hex, float[] at, Point first, Point second,
          float bottom, float top, boolean marker) {
        float turn = first.x() * second.y() - first.y() * second.x();
        if (Math.abs(turn) < .01f) { return; }
        // Keep a fixed physical face width: shallow changes of wall direction must not stretch a post into a fin.
        float span = 5.0f * Math.abs(turn), high = turn > 0 ? 1.6f : Math.max(1.6f, span - .65f);
        float low = high - span, back = Math.min(low, -.5f), bevel = Math.min(.2f, span * .12f);
        List<float[]> outline = new ArrayList<>();
        for (float[] p : new float[][] { { back + bevel, back }, { high - bevel, back }, { high, low + bevel },
              { high, high - bevel }, { high - bevel, high }, { low + bevel, high }, { back, high - bevel }, { back, back + bevel } }) {
            outline.add(cornerPoint(at, first, second, p[0], p[1]));
        }
        outline = counterClockwise(outline);
        prism(mesh, hex, PIER, outline, bottom, top, outline.size());
        var collar = grown(outline, .18f);
        postCap(mesh, hex, outline, bottom, bottom + .7f, .65f);
        postCap(mesh, hex, outline, top, top + BEVEL_RISE + .4f, .5f);
        for (float z = (float) Math.ceil((bottom + 1) / LEVEL) * LEVEL; z < top - 1; z += LEVEL) {
            prism(mesh, hex, PIER, collar, z - .3f, z + .3f, collar.size());
        }
        for (int face = 0; face < 2; face++) {
            Point normal = face == 0 ? first : second;
            float[] a = cornerPoint(at, first, second, face == 0 ? high : low + bevel, face == 0 ? low + bevel : high);
            float[] b = cornerPoint(at, first, second, face == 0 ? high : high - bevel, face == 0 ? high - bevel : high);
            float[] centre = { (a[0] + b[0]) / 2, (a[1] + b[1]) / 2 };
            float half = (float) Math.hypot(b[0] - a[0], b[1] - a[1]) / 2;
            postDetails(mesh, hex, centre, -normal.y(), normal.x(), 0, bottom, top, 0, half, marker);
        }
    }

    private static float[] cornerPoint(float[] at, Point first, Point second, float a, float b) {
        float cross = first.x() * second.y() - first.y() * second.x();
        return new float[] { at[0] + (a * second.y() - first.y() * b) / cross,
              at[1] + (first.x() * b - a * second.x()) / cross };
    }

    /** A projecting armored shoe/capital with a sloping shoulder and a fitted lighter top plate. */
    private static void postCap(GeneratedMesh mesh, List<float[]> hex, List<float[]> outline, float bottom, float top,
          float growth) {
        var outer = grown(counterClockwise(outline), growth);
        var inner = parapets(outer, .3f);
        float shoulder = Math.max(bottom, top - .4f);
        prism(mesh, hex, PIER, outer, bottom, shoulder, outer.size());
        for (int edge = 0; edge < outer.size(); edge++) {
            int next = (edge + 1) % outer.size();
            float[] a = outer.get(edge), b = outer.get(next), c = inner.get(edge), d = inner.get(next);
            float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
            float nx = (b[1] - a[1]) / length, ny = (a[0] - b[0]) / length;
            Vector3 normal = new Vector3(nx * (top - shoulder), ny * (top - shoulder), .3f).nor();
            float slope = (float) Math.hypot(.3f, top - shoulder);
            float[] v0 = vertex(a[0], a[1], shoulder, normal.x, normal.y, normal.z, 1, 0, 0);
            float[] v1 = vertex(b[0], b[1], shoulder, normal.x, normal.y, normal.z, 1, 0, length / 12);
            float[] v2 = vertex(d[0], d[1], top, normal.x, normal.y, normal.z, 1, -slope / 12,
                  ((d[1] - a[1]) * nx - (d[0] - a[0]) * ny) / 12);
            float[] v3 = vertex(c[0], c[1], top, normal.x, normal.y, normal.z, 1, -slope / 12,
                  ((c[1] - a[1]) * nx - (c[0] - a[0]) * ny) / 12);
            outward(mesh, HARDWARE, hex, v0, v1, v2); outward(mesh, HARDWARE, hex, v0, v2, v3);
        }
        clippedPolygon(mesh, hex, PIER, inner.stream()
              .map(p -> vertex(p[0], p[1], top, 0, 0, 1, 1, p[0] / 12, -p[1] / 12)).toList());
        var plate = parapets(inner, .28f);
        clippedPolygon(mesh, hex, HARDWARE, plate.stream()
              .map(p -> vertex(p[0], p[1], top + .035f, 0, 0, 1, 1, p[0] / 12, -p[1] / 12)).toList());
        var bolts = parapets(plate, .28f);
        for (int corner = 0; corner < bolts.size(); corner += 2) {
            float[] at = bolts.get(corner);
            fastener(mesh, hex, new Vector3(at[0], at[1], top + .075f), Vector3.Z, .16f);
        }
    }

    private static void facadePost(GeneratedMesh mesh, List<float[]> hex, float[] a, float tx, float ty,
          float bottom, float top, float front, boolean marker) {
        float[][] profile = { { -2.8f, -.5f }, { -2.8f, front - .5f }, { -2.2f, front },
              { 2.2f, front }, { 2.8f, front - .5f }, { 2.8f, -.5f } };
        List<float[]> outline = new ArrayList<>();
        for (float[] p : profile) {
            outline.add(new float[] { a[0] + tx * p[0] + ty * p[1], a[1] + ty * p[0] - tx * p[1] });
        }
        prism(mesh, hex, PIER, outline, bottom, top, outline.size());
        float collar = (front - .35f) / 2, depth = front + .65f;
        postCap(mesh, hex, outline, bottom, bottom + .7f, .5f);
        postCap(mesh, hex, outline, top, top + BEVEL_RISE + .4f, .5f);
        for (float z = (float) Math.ceil((bottom + 1) / LEVEL) * LEVEL; z < top - 1; z += LEVEL) {
            facadeStrip(mesh, hex, PIER, a, tx, ty, -2.95f, 2.95f, collar, depth, z - .3f, z + .3f);
        }
        postDetails(mesh, hex, a, tx, ty, 0, bottom, top, front, 2.2f, marker);
    }

    /** The same inset panels, luminous lenses and fastening plates on every exposed corner and ring pier. */
    private static void postDetails(GeneratedMesh mesh, List<float[]> hex, float[] a, float tx, float ty, float at,
          float bottom, float top, float front, float half, boolean marker) {
        for (float floor = (float) Math.floor(bottom / LEVEL) * LEVEL; floor < top; floor += LEVEL) {
            float low = Math.max(bottom, floor), high = Math.min(top, floor + LEVEL);
            if (high - low < 6) { continue; }
            facadeStrip(mesh, hex, HARDWARE, a, tx, ty, at - half * .50f, at + half * .50f,
                  front + .025f, .04f, low + .75f, high - .8f);
            for (float side : new float[] { -.58f, .58f }) {
                facadeStrip(mesh, hex, PIER, a, tx, ty, at + half * (side - .05f), at + half * (side + .05f),
                      front + .075f, .10f, low + .75f, high - .8f);
            }
            for (float z : new float[] { low + .4f, high - .5f }) {
                for (float offset : new float[] { -half * .7f, half * .7f }) {
                    fastener(mesh, hex, new Vector3(a[0] + tx * (at + offset) + ty * (front + .08f),
                          a[1] + ty * (at + offset) - tx * (front + .08f), z), new Vector3(ty, -tx, 0), .13f);
                }
            }
        }
        if (top - bottom < 5) { return; }
        // Each exposed face has one head lamp and one small foot lamp, never another lamp at every storey.
        // External corners use larger amber head lenses. Foot markers are always small and white.
        for (boolean head : new boolean[] { true, false }) {
            float width = head ? half * (marker ? .55f : .45f) : half * .12f;
            float low = head ? top - (marker ? 2.5f : 1.95f) : bottom + .9f;
            float high = head ? top - 1.2f : bottom + 1.4f;
            facadeStrip(mesh, hex, HARDWARE, a, tx, ty, at - width - .2f, at + width + .2f,
                  front + .05f, .08f, low - .2f, high + .2f);
            facadeStrip(mesh, hex, PIER, a, tx, ty, at - width - .07f, at + width + .07f,
                  front + .1f, .02f, low - .07f, high + .07f);
            facadeStrip(mesh, hex, head && marker ? MARKER : LIGHT, a, tx, ty, at - width, at + width,
                  front + .12f, .02f, low, high);
        }
    }

    private static void surfacePlate(GeneratedMesh mesh, List<float[]> hex, String material, Vector3 centre,
          Vector3 right, Vector3 up, float halfWidth, float halfHeight) {
        Vector3 normal = new Vector3(right).crs(up).nor();
        float[][] corners = new float[4][];
        for (int i = 0; i < 4; i++) {
            float u = i == 0 || i == 3 ? -1 : 1, v = i < 2 ? -1 : 1;
            Vector3 p = new Vector3(centre).mulAdd(right, u * halfWidth).mulAdd(up, v * halfHeight);
            corners[i] = vertex(p.x, p.y, p.z, normal.x, normal.y, normal.z, 1,
                  (u + 1) * halfWidth / 12, (v + 1) * halfHeight / 12);
        }
        outward(mesh, material, hex, corners[0], corners[1], corners[2]);
        outward(mesh, material, hex, corners[0], corners[2], corners[3]);
    }

    private static void fastener(GeneratedMesh mesh, List<float[]> hex, Vector3 centre, Vector3 normal, float radius) {
        Vector3 right = new Vector3(normal).crs(Math.abs(normal.z) < .9f ? Vector3.Z : Vector3.X).nor();
        Vector3 up = new Vector3(normal).crs(right).nor();
        float[] middle = vertex(centre.x, centre.y, centre.z, normal.x, normal.y, normal.z, 1, .1f, .1f);
        for (int i = 0; i < 6; i++) {
            float[][] edge = new float[2][];
            for (int j = 0; j < 2; j++) {
                double angle = (i + j) * Math.PI / 3;
                Vector3 p = new Vector3(centre).mulAdd(right, radius * (float) Math.cos(angle))
                      .mulAdd(up, radius * (float) Math.sin(angle));
                edge[j] = vertex(p.x, p.y, p.z, normal.x, normal.y, normal.z, 1, .1f, .1f);
            }
            outward(mesh, HARDWARE, hex, middle, edge[0], edge[1]);
        }
    }

    /**
     * A wall from a to b between two heights, facing (nx, ny), cut to the hex unless that is null; its texture from
     * {@code u0} to {@code u1} along it, one repeat per {@link #WALL_REPEAT} up it.
     */
    private static void face(GeneratedMesh mesh, List<float[]> hex, String material, float[] a, float[] b, float nx, float ny,
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
        if (material.equals(WALL)) {
            // Annex and podium facades use the same solid inward backing as the modular kits.
            double[] p = { corners[0][0], corners[0][1] }, q = { corners[1][0], corners[1][1] };
            var wall = new KitOutline.Wall(p, q, KitOutline.End.OPEN, KitOutline.End.OPEN, List.of(), false);
            var body = new GeneratedMesh();
            KitOutline.wallBody(body, wall, List.of(wall), bottom, top, 0, 2);
            List<GeneratedMesh.Keep> cuts = new ArrayList<>();
            if (hex != null) {
                for (int i = 0; i < hex.size(); i++) {
                    float[] first = hex.get(i), next = hex.get((i + 1) % hex.size());
                    cuts.add((x, y) -> (next[0] - first[0]) * (y - first[1]) - (next[1] - first[1]) * (x - first[0]));
                }
            }
            mesh.add(body.clipped(cuts));
        }
    }

    /** A strut standing proud of the glass: its outer face and, near, its two sides. */
    private static void strut(GeneratedMesh mesh, List<float[]> hex, Vector3 a, Vector3 b, Vector3 centre, int lod) {
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
    private static void quad(GeneratedMesh mesh, List<float[]> hex, Vector3 a, Vector3 b, Vector3 first, Vector3 second,
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
    private static void ring(GeneratedMesh mesh, List<float[]> hex, Dome dome, int lod) {
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
    private static void garden(GeneratedMesh mesh, Dome dome) {
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
}
