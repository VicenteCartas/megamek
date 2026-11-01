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
 * heights ({@link #fits}), each hex taking the part over its own hex; a hex outside every podium is an annex, blocks at
 * its own height ({@link #blocks}) joined to the podium by a wing. The lunar habitats are a connected kit instead
 * ({@code buildings/lunar_habitat}).
 * Coordinates are model units (a level {@link GpuBuilding#LEVEL_HEIGHT} high) about the hex centre on its own ground.
 */
final class BoardMultiHexBuilding {
    static final int LODS = 2;
    /** How far a facade stands back from a hexside the building does not connect across. */
    static final float SETBACK = 4;
    /** An annex's roof parapet: its rise and its thickness. */
    static final float PARAPET = 3.2f, PARAPET_WIDTH = 2.2f;
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
    private static final float PODIUM_WIDTH = .14f, FIN = DOME_MARGIN - SETBACK;
    static final String FIN_MATERIAL = "structures/common/safety-yellow";
    /** Where a connected neighbour closes towards the hex beyond a shared corner, this hex's wall runs from that corner
     * to the neighbour's set-back facade: the set-back met at the corner's 120 degrees. */
    private static final float NOTCH = SETBACK / (float) Math.sin(Math.PI / 3);
    /** A roof service bank's raised curb, the gap between its pieces, and its duct's section. */
    private static final float CURB = 1.2f, GAP = 2.5f, DUCT = 1.4f;
    /** The smallest dome worth drawing (a lone hex's fits); a footprint too narrow for any stands as annexes. */
    private static final float MIN_DOME = 16;
    /**
     * Half a hex's core's width ({@link #blocks}): half a hexside, the width of the hex from its north to south side;
     * an annex's wing ({@link #wing}) is as wide.
     */
    static final float CORE = HexTile.cornerOffsetX(0, HexTile.MODEL_WIDTH) / 2;
    /** Geodesic strut length the frequency aims at, and the struts' cross-section. */
    private static final float STRUT_LENGTH = 16, STRUT_WIDTH = .75f, STRUT_OUT = .5f, STRUT_IN = .4f;
    private static final float LEVEL = GpuBuilding.LEVEL_HEIGHT;
    private static final float WIDTH = HexTile.MODEL_WIDTH, HEIGHT = HexTile.HEIGHT;
    /** Wall texture: one repeat per two storeys, as wide as high. */
    private static final float WALL_REPEAT = 2 * LEVEL;
    private static final float EPSILON = 1e-3f;

    // Board materials (textures/<name>.tres); their .tres files give every finish, transparency included.
    static final String WALL = "structures/lunar/habitat-wall", ROOF = "structures/lunar/habitat-roof",
          PLINTH_MATERIAL = "structures/lunar/regolith-concrete", FRAME = "structures/lunar/dome-frame",
          GLASS = "structures/lunar/dome-glass", GARDEN = "structures/lunar/biodome-garden",
          PIER = "structures/lunar/habitat-pier";

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
     * the base is a vertex ring), and its building's airlocks' backs relative to its centre, x and y in turn, which
     * entrance wings join to its podium, the ring building round its foot whose twelve-sided outline has its corners
     * {@code podium} from the centre; {@code wings}, six numbers each: an annex's wing ({@link #wing}), its hexside's
     * middle relative to the centre, its direction across the hex beyond, its rise above the foot and its length.
     */
    record Dome(float x, float y, float radius, float foot, float ring, float apex, int frequency, float podium,
          List<Float> entrances, List<Float> wings) {
        Dome {
            entrances = List.copyOf(entrances);
            wings = List.copyOf(wings);
        }

        /** The podium's ring's width, and its height above the foot: where the glass meets its roof. */
        float width() { return Math.clamp(podium * PODIUM_WIDTH, 9, 24); }

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
         * annex's wing: fins and pylons keep clear of them.
         */
        boolean joined(double angle, double margin) {
            for (int at = 0; at < entrances.size(); at += 2) {
                double toward = Math.atan2(entrances.get(at + 1), entrances.get(at));
                if (Math.abs(Math.IEEEremainder(toward - angle, 2 * Math.PI)) < Math.toRadians(12) + margin) { return true; }
            }
            for (int at = 0; at < wings.size(); at += 6) {
                float x = wings.get(at) + wings.get(at + 2) * wings.get(at + 5);
                float y = wings.get(at + 1) + wings.get(at + 3) * wings.get(at + 5);
                double half = Math.atan2(CORE + 2, Math.hypot(x, y));
                if (Math.abs(Math.IEEEremainder(Math.atan2(y, x) - angle, 2 * Math.PI)) < half + margin) { return true; }
            }
            return false;
        }
    }

    /**
     * Derived from the captured board; shared by drawing, picking and clearance. {@code top}: the shell's height,
     * {@code reach}: how high it connects towards each board direction (model units, 0 where a facade closes it);
     * {@code notches}: per direction d, for the corner it shares with direction d + 1 and then the one with d - 1, the
     * height from which the neighbour towards d closes towards the hex beyond that corner (above the top: never);
     * {@code around}, an annex's ({@link #blocks}): the tops of the neighbours it connects to towards each direction
     * (0 for none), then how high each pair of neighbours d and d + 1 connect to each other, in this hex's frame, then
     * the height at and under which its cells are the domes' plinth, then the directions its wings leave by (bits);
     * {@code annex}: a domed building's hex outside every podium, a block at its own height joined to the plinth.
     */
    record Layout(Design design, float top, List<Float> reach, List<Float> notches, List<Float> around, List<Dome> domes,
          List<Gear> gear, List<Pad> pads, boolean annex) {
        Layout {
            domes = List.copyOf(domes);
            reach = List.copyOf(reach);
            notches = List.copyOf(notches);
            around = List.copyOf(around);
            gear = List.copyOf(gear);
            pads = List.copyOf(pads);
        }
    }

    /** A roof service bank's raised curb: its centre, its row's direction (unit), half its length and width, its foot. */
    record Pad(float x, float y, float ux, float uy, float along, float across, float z) { }

    private record Support(List<Gear> gear, List<Pad> pads) { }

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

    private static final List<Float> NEVER = java.util.Collections.nCopies(12, Float.MAX_VALUE);

    /** A lone hex of the design, {@code levels} high: palette thumbnails and previews. */
    static Layout layout(String asset, int levels) {
        Design design = design(asset);
        int height = Math.max(1, levels);
        Float[] closed = { 0f, 0f, 0f, 0f, 0f, 0f };
        Coords lone = new Coords(0, 0);
        var fits = fits(List.of(new Member(lone, 0, height, false)));
        var none = java.util.Collections.nCopies(fits.size(), List.<Float>of());
        return new Layout(design, PLINTH, List.of(closed), NEVER, List.of(), domes(fits, lone, 0, none, none), List.of(),
              List.of(), false);
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        Design design = design(feature.asset());
        int levels = Math.max(1, Math.round(feature.height()));
        Float[] connected = connections(scene, tile, feature.asset(), levels);
        var group = group(scene, tile, feature.asset());
        var fits = fits(group);
        int base = group.stream().mapToInt(Member::elevation).max().orElse(tile.elevation());
        float shift = (base - tile.elevation()) * LEVEL, plinth = PLINTH + shift;
        if (annex(fits, tile.coords())) {
            // An annex: blocks at its own height, joined to the neighbouring annexes; towards a hex under a dome over
            // its whole height where its wing runs on across that hex to the podium, else closed, as that hex is.
            boolean[] winged = new boolean[6];
            for (int direction = 0; direction < 6; direction++) {
                var neighbor = scene.neighbor(tile, direction);
                if (connected[direction] <= 0 || annex(fits, neighbor.coords())) { continue; }
                winged[direction] = wing(fits, base, tile, neighbor) != null;
                if (!winged[direction]) { connected[direction] = 0f; }
            }
            float top = levels * LEVEL;
            var support = support(scene, tile, design, top, connected, List.of(), true, plinth);
            return new Layout(design, top, List.of(connected), NEVER,
                  annexAround(scene, tile, feature.asset(), connected, fits, plinth, winged, top), List.of(), support.gear(),
                  support.pads(), true);
        }
        plinth(scene, tile, fits, base, plinth, connected);
        var domes = domes(fits, tile.coords(), shift, entrances(scene, group, fits, base, feature.asset()),
              wings(scene, group, fits, base, feature.asset()));
        var support = support(scene, tile, design, plinth, connected, domes, false, plinth);
        return new Layout(design, plinth, List.of(connected), notches(scene, tile, connected, fits, plinth),
              List.of(), domes, support.gear(), support.pads(), false);
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

    /** The neighbours' tops and their connections to each other, in this hex's frame ({@link Layout#around}). */
    private static List<Float> around(BoardScene scene, BoardScene.Tile tile, String asset, Float[] connected) {
        Float[] result = new Float[13];
        result[12] = 0f;
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            result[direction] = 0f;
            result[6 + direction] = 0f;
            if (neighbor == null || neighbor.features().stream().noneMatch(feature -> feature.asset().equals(asset))) {
                continue;
            }
            int levels = Math.max(1, neighbor.cover().height());
            float shift = (neighbor.elevation() - tile.elevation()) * LEVEL;
            if (connected[direction] > 0) { result[direction] = shift + levels * LEVEL; }
            // Towards the next neighbour round this hex, two sixths on from this one.
            float joined = connections(scene, neighbor, asset, levels)[(direction + 2) % 6];
            if (joined > 0) { result[6 + direction] = shift + joined; }
        }
        return List.of(result);
    }

    /**
     * {@link Layout#around} for an annex: a hex under the domes stands as high as the annex where its wing runs on
     * across it, and is closed off otherwise; then the plinth's height, at which its cells are plinth, and the directions
     * its wings leave by (bits).
     */
    private static List<Float> annexAround(BoardScene scene, BoardScene.Tile tile, String asset, Float[] connected,
          List<Fit> fits, float plinth, boolean[] winged, float top) {
        var result = new ArrayList<>(around(scene, tile, asset, connected));
        int wings = 0;
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            var next = scene.neighbor(tile, (direction + 1) % 6);
            boolean under = neighbor != null && !annex(fits, neighbor.coords());
            boolean nextUnder = next != null && !annex(fits, next.coords());
            if (under && result.get(direction) > 0) { result.set(direction, top); }
            if (winged[direction]) { wings |= 1 << direction; }
            // Two hexes under the domes join at the plinth; an annex and one of them do not join at all.
            if ((under || nextUnder) && result.get(6 + direction) > 0) {
                result.set(6 + direction, under && nextUnder ? Math.min(result.get(6 + direction), plinth) : 0f);
            }
        }
        result.set(12, plinth);
        result.add((float) wings);
        return result;
    }

    /**
     * {@code connected} for a hex under the domes, in place: its plinth joins its neighbours under the domes, and an
     * annex whose wing crosses it, at the plinth's height; it closes towards any other annex.
     */
    private static void plinth(BoardScene scene, BoardScene.Tile tile, List<Fit> fits, int base, float plinth,
          Float[] connected) {
        for (int direction = 0; direction < 6; direction++) {
            if (connected[direction] <= 0) { continue; }
            var neighbor = scene.neighbor(tile, direction);
            boolean annex = annex(fits, neighbor.coords());
            connected[direction] = !annex || wing(fits, base, neighbor, tile) != null ? plinth : 0f;
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
     * The wing joining an annex to the podium of the dome nearest the hexside it shares with a hex {@code under} the
     * domes to its north or south: straight from that hexside across that hex, as wide as the annex's core, until both
     * its far corners lie in the middle of the podium's ring. Null where the annex rises no higher than the plinth or the wing would not
     * reach the ring within that hex; else that dome's index and the wing as {@link Dome#wings} gives it.
     */
    private static float[] wing(List<Fit> fits, int base, BoardScene.Tile annex, BoardScene.Tile under) {
        float rise = (annex.elevation() + Math.max(1, annex.cover().height()) - base) * LEVEL - PLINTH;
        if (rise <= EPSILON || fits.isEmpty()) { return null; }
        float ax = HexTile.centerX(annex.coords(), WIDTH), ay = HexTile.centerY(annex.coords(), HEIGHT);
        float ux = HexTile.centerX(under.coords(), WIDTH), uy = HexTile.centerY(under.coords(), HEIGHT);
        float mx = (ax + ux) / 2, my = (ay + uy) / 2, apart = (float) Math.hypot(ux - ax, uy - ay);
        float ix = (ux - ax) / apart, iy = (uy - ay) / apart;
        if (Math.abs(ix) > EPSILON) { return null; }
        int nearest = 0;
        for (int index = 1; index < fits.size(); index++) {
            var fit = fits.get(index);
            if (Math.hypot(mx - fit.x(), my - fit.y()) - fit.podium()
                  < Math.hypot(mx - fits.get(nearest).x(), my - fits.get(nearest).y()) - fits.get(nearest).podium()) {
                nearest = index;
            }
        }
        var fit = fits.get(nearest);
        float middle = (fit.radius() + fit.podium() * (float) Math.cos(Math.PI / 12)) / 2;
        float enter = 0, leave = Float.POSITIVE_INFINITY;
        for (int side = -1; side <= 1; side += 2) {
            float px = mx - iy * CORE * side - fit.x(), py = my + ix * CORE * side - fit.y();
            float along = px * ix + py * iy, room = along * along - (px * px + py * py) + middle * middle;
            if (room < 0) { return null; }
            enter = Math.max(enter, -along - (float) Math.sqrt(room));
            leave = Math.min(leave, -along + (float) Math.sqrt(room));
        }
        if (enter <= 0 || enter > leave || enter > HEIGHT * .9f) { return null; }
        return new float[] { nearest, mx - fit.x(), my - fit.y(), ix, iy, rise, enter };
    }

    /** Each dome's annexes' wings ({@link Dome#wings}), in a stable order. */
    private static List<List<Float>> wings(BoardScene scene, List<Member> group, List<Fit> fits, int base, String asset) {
        List<float[]> found = new ArrayList<>();
        for (Member member : group) {
            if (member.ghost() || !annex(fits, member.coords())) { continue; }
            var tile = scene.tile(member.coords());
            var connected = connections(scene, tile, asset, Math.max(1, tile.cover().height()));
            for (int direction = 0; direction < 6; direction++) {
                var neighbor = scene.neighbor(tile, direction);
                if (connected[direction] <= 0 || annex(fits, neighbor.coords())) { continue; }
                var wing = wing(fits, base, tile, neighbor);
                if (wing != null) { found.add(wing); }
            }
        }
        found.sort(Comparator.<float[]>comparingDouble(wing -> wing[1]).thenComparingDouble(wing -> wing[2]));
        List<List<Float>> result = new ArrayList<>();
        fits.forEach(fit -> result.add(new ArrayList<>()));
        for (float[] wing : found) {
            for (int at = 1; at < wing.length; at++) { result.get((int) wing[0]).add(wing[at]); }
        }
        return result;
    }

    /**
     * The backs of a domed building's airlocks relative to its dome's centre, x and y in turn, in a stable order: the
     * airlocks the hexes under its domes place ({@link #airlocks}), which entrance wings join to the nearest podium.
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
            plinth(scene, tile, fits, base, PLINTH + shift, connected);
            for (Gear airlock : airlocks(kit, scene, tile, PLINTH + shift, connected,
                  domes(fits, member.coords(), shift, none, none), false)) {
                points.add(new float[] { HexTile.centerX(member.coords(), WIDTH) + airlock.x(),
                      HexTile.centerY(member.coords(), HEIGHT) + airlock.y() });
            }
        }
        points.sort(Comparator.<float[]>comparingDouble(point -> point[0]).thenComparingDouble(point -> point[1]));
        for (float[] point : points) {
            int nearest = 0;
            for (int index = 1; index < fits.size(); index++) {
                if (Math.hypot(point[0] - fits.get(index).x(), point[1] - fits.get(index).y())
                      < Math.hypot(point[0] - fits.get(nearest).x(), point[1] - fits.get(nearest).y())) { nearest = index; }
            }
            result.get(nearest).add(point[0] - fits.get(nearest).x());
            result.get(nearest).add(point[1] - fits.get(nearest).y());
        }
        return result;
    }

    /**
     * {@link Layout#notches}: where this hex connects towards d and towards the hex beyond a corner of that hexside while
     * the neighbour towards d does not, the neighbour's facade stands back from the corner this hex's share reaches;
     * this hex closes the gap between them with a wall along the hexside.
     */
    private static List<Float> notches(BoardScene scene, BoardScene.Tile tile, Float[] connected, List<Fit> fits,
          float plinth) {
        Float[] result = new Float[12];
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            for (int side = 0; side < 2; side++) {
                int beyond = (direction + (side == 0 ? 2 : 4)) % 6;
                float height = Float.MAX_VALUE;
                if (neighbor != null && connected[direction] > 0) {
                    float shift = (neighbor.elevation() - tile.elevation()) * LEVEL;
                    if (!ConnectedKit.back(scene, neighbor, beyond)) {
                        height = Math.max(0, shift);
                    } else if (!annex(fits, neighbor.coords())) {
                        // A hex under the domes fills that corner with its plinth alone: an annex's wing keeps clear.
                        height = Math.max(0, plinth);
                    }
                }
                result[direction * 2 + side] = height;
            }
        }
        return List.of(result);
    }

    // ---- Support pieces ------------------------------------------------------------------------------------------------

    private static final String EQUIPMENT = "buildings/multihex/lunar-equipment.glb";
    /** An annex roof's gear: skylights, as the printed lunar map's roofs show most, twice as often as the rest. */
    private static final List<String> ROOF_GEAR = List.of("skylight", "skylight", "plant", "tanks", "solar", "radiator",
          "antenna");
    private static final List<String> APRON_GEAR = List.of("tanks", "radiator", "plant");
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
     * What stands on the share, chosen per hex and stable for it: an airlock at each closed hexside a corridor (a ground
     * rail) arrives at, against an annex's core or on a dome's plinth; on an annex's roof ({@code blocks}), unless placed
     * objects stand there, banks of roof gear; on a dome's plinth outside the dome, plant and tanks; under a glass
     * dome, at its middle, a piece that clears the glass. A piece goes only where it fits, turned to a hexside.
     */
    private static Support support(BoardScene scene, BoardScene.Tile tile, Design design, float top, Float[] reach,
          List<Dome> domes, boolean blocks, float deck) {
        var kit = equipment();
        List<Gear> result = new ArrayList<>();
        List<Pad> pads = new ArrayList<>();
        if (kit.lods().isEmpty()) { return new Support(List.of(), List.of()); }
        var random = new SplittableRandom(tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L);
        float[] heights = new float[6];
        for (int direction = 0; direction < 6; direction++) { heights[direction] = reach[direction]; }
        List<float[]> base = outline(closed(heights, 0), SETBACK - .01f);
        result.addAll(airlocks(kit, scene, tile, top, reach, domes, blocks));
        if (blocks) {
            // No gear on an annex no higher than the plinth, nor where placed objects stand.
            boolean decorated = top <= deck + EPSILON
                  || tile.features().stream().anyMatch(feature -> feature.decoration() != null);
            float chance = random.nextFloat();
            int banks = decorated ? 0 : chance < .12f ? 0 : chance < .5f ? 1 : 2;
            // An annex's core ({@link #blocks}) inside its parapets.
            float a = CORE - PARAPET_WIDTH - 1, b = HEIGHT / 2 - PARAPET_WIDTH - 1;
            List<float[]> roof = List.of(new float[] { -a, -b }, new float[] { a, -b }, new float[] { a, b },
                  new float[] { -a, b });
            int turn = random.nextInt(6);
            for (int bank = 0; bank < banks; bank++) {
                String piece = ROOF_GEAR.get(random.nextInt(ROOF_GEAR.size()));
                int count = piece.equals("antenna") ? 1 : 2 + random.nextInt(2);
                float offset = banks == 1 ? 0 : bank == 0 ? -1 : 1;
                for (int tries = 0; tries < 6 && !bank(kit, piece, count, (turn + tries) % 6, offset, top, roof, result, pads); tries++) {
                    if (count > 1) { count--; }
                }
            }
        } else if (!domes.isEmpty()) {
            Dome dome = domes.stream().min(Comparator.comparingDouble(near -> Math.hypot(near.x(), near.y()) - near.podium()))
                  .orElseThrow();
            float distance = (float) Math.hypot(dome.x(), dome.y());
            if (domes.stream().allMatch(near -> Math.hypot(near.x(), near.y()) > near.podium()) && random.nextFloat() >= .2f) {
                place(result, kit, APRON_GEAR.get(random.nextInt(APRON_GEAR.size())), 0, 0, top, random.nextInt(6), base,
                      domes, false);
            } else if (design == Design.DOME && distance < dome.radius()) {
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
        return new Support(List.copyOf(result), List.copyOf(pads));
    }

    /**
     * An airlock at each closed hexside a corridor (a ground rail) arrives at: against an annex's core ({@code blocks}),
     * or on a dome's plinth where it fits clear of the podiums.
     */
    private static List<Gear> airlocks(Equipment kit, BoardScene scene, BoardScene.Tile tile, float top, Float[] reach,
          List<Dome> domes, boolean blocks) {
        var bounds = kit.bounds().get("airlock");
        if (bounds == null) { return List.of(); }
        float[] heights = new float[6];
        for (int direction = 0; direction < 6; direction++) { heights[direction] = reach[direction]; }
        List<float[]> base = outline(closed(heights, 0), SETBACK - .01f);
        List<Gear> result = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            if (heights[direction] > 0 || neighbor == null || (neighbor.railBed() & 1 << (direction + 3) % 6) == 0) {
                continue;
            }
            float[] side = side(direction);
            if (blocks) {
                // Against the core's facade: across the hexside to the north or south, else its long side.
                boolean across = direction % 3 == 0;
                float x = across ? 0 : direction < 3 ? CORE : -CORE, y = Math.signum(side[1]) * HEIGHT / (across ? 2 : 4);
                result.add(new Gear("airlock", x, y, 0, across ? 60 * direction : direction < 3 ? 90 : 270));
                continue;
            }
            float along = side[2] - SETBACK - bounds.max.y;
            var airlock = new Gear("airlock", side[0] * along, side[1] * along, top, 60 * direction);
            if (fits(kit, airlock, base, domes, false)) { result.add(airlock); }
        }
        return result;
    }

    /**
     * A service bank on an annex's roof: {@code count} copies of {@code piece} in a row along its shorter side, turned to a
     * hexside, on a raised curb with a duct along it; side by side with the hex's other bank ({@code offset} -1 or 1, 0
     * alone). False, placing nothing, where the curb would leave the roof.
     */
    private static boolean bank(Equipment kit, String piece, int count, int turn, float offset, float top,
          List<float[]> roof, List<Gear> gear, List<Pad> pads) {
        BoundingBox box = kit.bounds().get(piece);
        if (box == null || roof.isEmpty()) { return false; }
        boolean alongX = box.getWidth() <= box.getHeight();
        float pitch = (alongX ? box.getWidth() : box.getHeight()) + GAP;
        float along = (count * pitch - GAP) / 2 + 2, across = (alongX ? box.getHeight() : box.getWidth()) / 2 + 2 + DUCT;
        Matrix4 frame = ConnectedKit.placement(0, turn);
        Vector3 row = new Vector3(alongX ? 1 : 0, alongX ? 0 : 1, 0).rot(frame);
        Vector3 cross = new Vector3(-row.y, row.x, 0);
        Vector3 centre = new Vector3(cross).scl(offset * (across + 2));
        var curb = new Pad(centre.x, centre.y, row.x, row.y, along, across, top);
        for (float[] corner : corners(curb, 0)) {
            if (!inside(roof, corner[0], corner[1])) { return false; }
        }
        // Clear of the hex's other bank: nothing of this curb inside that one's, with a walkway between.
        for (Pad other : pads) {
            List<float[]> overlap = corners(curb, 0);
            List<float[]> around = corners(other, GAP);
            for (int i = 0; i < 4 && !overlap.isEmpty(); i++) {
                float[] a = around.get(i), b = around.get((i + 1) % 4);
                float length = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                float nx = (b[1] - a[1]) / length, ny = -(b[0] - a[0]) / length;
                overlap = clip(overlap, nx, ny, nx * a[0] + ny * a[1], false);
            }
            if (!overlap.isEmpty()) { return false; }
        }
        // Each piece centred on its slot, the duct along the curb's +cross side.
        Vector3 middle = new Vector3(box.getCenterX(), box.getCenterY(), 0).rot(frame);
        Vector3 shift = new Vector3(cross).scl(-DUCT / 2);
        for (int index = 0; index < count; index++) {
            var slot = new Vector3(centre).add(shift).mulAdd(row, (index - (count - 1) / 2f) * pitch).sub(middle);
            gear.add(new Gear(piece, slot.x, slot.y, top + CURB, 60 * turn));
        }
        pads.add(curb);
        return true;
    }

    /** A curb's outline, counter-clockwise, grown by {@code margin}. */
    private static List<float[]> corners(Pad pad, float margin) {
        float along = pad.along() + margin, across = pad.across() + margin, cx = -pad.uy(), cy = pad.ux();
        List<float[]> result = new ArrayList<>(4);
        for (float[] sign : new float[][] { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) {
            result.add(new float[] { pad.x() + pad.ux() * along * sign[0] + cx * across * sign[1],
                  pad.y() + pad.uy() * along * sign[0] + cy * across * sign[1] });
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
     * Whether the piece's footprint lies within {@code area} (convex, counter-clockwise) and clear of every dome's
     * podium and its pylons, or ({@code inside}) wholly under the one dome's glass with room to spare.
     */
    private static boolean fits(Equipment kit, Gear gear, List<float[]> area, List<Dome> domes, boolean inside) {
        BoundingBox box = kit.bounds().get(gear.piece());
        if (box == null || area.isEmpty()) { return false; }
        Matrix4 frame = frame(gear);
        for (float x : new float[] { box.min.x, box.max.x }) {
            for (float y : new float[] { box.min.y, box.max.y }) {
                var corner = new Vector3(x, y, 0).mul(frame);
                if (!inside(area, corner.x, corner.y)) { return false; }
                for (Dome dome : domes) {
                    float from = (float) Math.hypot(corner.x - dome.x(), corner.y - dome.y());
                    if (!inside && from < dome.podium() + 12) { return false; }
                    if (inside && (from > dome.radius() - 3 || dome.glass(from) < gear.z() + box.max.z + 4)) { return false; }
                }
            }
        }
        return true;
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
            float radius = podium * (float) Math.cos(Math.PI / 12) - Math.clamp(podium * PODIUM_WIDTH, 9, 24);
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
        if (layout.annex()) {
            blocks(mesh, layout, lod);
        } else {
            float[] notches = new float[12];
            for (int corner = 0; corner < 12; corner++) { notches[corner] = layout.notches().get(corner); }
            shell(mesh, layout.top(), reach, notches);
        }
        for (Pad pad : layout.pads()) { pad(mesh, pad); }
        for (Dome dome : layout.domes()) {
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

    // ---- An annex's blocks --------------------------------------------------------------------------------------------

    /**
     * An annex's share in rectangular blocks, as the printed lunar maps draw them. Each hex's core, the rectangle
     * between its north and south hexsides as wide as a hexside, stands at its own height; the cell beside a slanted
     * hexside, between the cores of two neighbours that connect, at their connection's height. A facade rises wherever a
     * cell stands above the cell beyond its edge, crowned by a parapet, with steel piers at its corners and joints; the
     * cells a slanted hexside splits are cut to the hex.
     */
    private static void blocks(Mesh mesh, Layout layout, int lod) {
        float a = CORE, half = HEIGHT / 2, top = layout.top();
        float[] reach = new float[6], top6 = new float[6], link = new float[6];
        for (int direction = 0; direction < 6; direction++) {
            reach[direction] = layout.reach().get(direction);
            top6[direction] = layout.around().get(direction);
            link[direction] = layout.around().get(6 + direction);
        }
        // Each cell: x from, to; y from, to; its height; the heights beyond its south, east, north and west edges.
        float[][] cells = {
              { -a, a, 0, half, top, top, reach[1], top6[0], reach[5] },
              { -a, a, -half, 0, top, top6[3], reach[2], top, reach[4] },
              { a, 2 * a, 0, half, reach[1], reach[2], top6[1], link[0], top },
              { a, 2 * a, -half, 0, reach[2], link[2], top6[2], reach[1], top },
              { -2 * a, -a, -half, 0, reach[4], link[3], top, reach[5], top6[4] },
              { -2 * a, -a, 0, half, reach[5], reach[4], top, link[5], top6[5] } };
        List<float[]> hex = hex();
        float deck = layout.around().get(12);
        for (int index = 0; index < cells.length; index++) {
            if (cells[index][4] > EPSILON) { cell(mesh, index < 2 ? null : hex, cells[index], deck, lod); }
        }
        // Under a wing to the north or south the plinth beyond is set back from its corners: the core closes to the
        // ground there, up to the plinth's deck.
        int wings = Math.round(layout.around().get(13));
        for (int direction : new int[] { 0, 3 }) {
            if ((wings & 1 << direction) == 0) { continue; }
            float y = direction == 0 ? half : -half, ny = direction == 0 ? 1 : -1;
            face(mesh, null, PLINTH_MATERIAL, new float[] { -a, y }, new float[] { a, y }, 0, ny, 0, deck,
                  across(new float[] { -a, y }, 0, ny), across(new float[] { a, y }, 0, ny));
        }
    }

    /**
     * One cell of {@link #blocks}, cut to the hex unless that is null: its roof inside its parapets, a facade with a
     * parapet over each edge it stands above the cell beyond (from that cell's height), a pier at the start of each
     * (on the corner's bisector where two meet), and on the ground a base course along it. A cell no higher than
     * {@code deck} is the domes' plinth: concrete, with neither parapets nor piers.
     */
    private static void cell(Mesh mesh, List<float[]> hex, float[] cell, float deck, int lod) {
        float x0 = cell[0], x1 = cell[1], y0 = cell[2], y1 = cell[3], z = cell[4];
        // Counter-clockwise from the south-west corner: the edges south, east, north and west.
        float[][] corners = { { x0, y0 }, { x1, y0 }, { x1, y1 }, { x0, y1 } };
        float[][] normals = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
        boolean[] wall = new boolean[4];
        for (int edge = 0; edge < 4; edge++) { wall[edge] = cell[5 + edge] < z - EPSILON; }
        if (z <= deck + EPSILON) {
            if (hex == null) { flat(mesh, PLINTH_MATERIAL, List.of(corners), z, 1); }
            else { clippedFlat(mesh, hex, PLINTH_MATERIAL, List.of(corners), z); }
            for (int edge = 0; edge < 4; edge++) {
                if (!wall[edge]) { continue; }
                float[] a = corners[edge], b = corners[(edge + 1) % 4];
                face(mesh, hex, PLINTH_MATERIAL, a, b, normals[edge][0], normals[edge][1], Math.max(0, cell[5 + edge]), z,
                      across(a, normals[edge][0], normals[edge][1]), across(b, normals[edge][0], normals[edge][1]));
            }
            return;
        }
        float south = wall[0] ? PARAPET_WIDTH : 0, east = wall[1] ? PARAPET_WIDTH : 0, north = wall[2] ? PARAPET_WIDTH : 0,
              west = wall[3] ? PARAPET_WIDTH : 0;
        List<float[]> roof = List.of(new float[] { x0 + west, y0 + south }, new float[] { x1 - east, y0 + south },
              new float[] { x1 - east, y1 - north }, new float[] { x0 + west, y1 - north });
        if (hex == null) { flat(mesh, ROOF, roof, z, 1); } else { clippedFlat(mesh, hex, ROOF, roof, z); }
        for (int edge = 0; edge < 4; edge++) {
            if (!wall[edge]) { continue; }
            float[] a = corners[edge], b = corners[(edge + 1) % 4], c = roof.get(edge), d = roof.get((edge + 1) % 4);
            float nx = normals[edge][0], ny = normals[edge][1], bottom = Math.max(0, cell[5 + edge]);
            // Along the facade from the viewer's left, continuous across hexes: a hex column's width per repeat across,
            // half a hex up.
            float u0 = across(a, nx, ny), u1 = across(b, nx, ny);
            face(mesh, hex, WALL, a, b, nx, ny, bottom, z + PARAPET, u0, u1);
            face(mesh, hex, WALL, c, d, -nx, -ny, z, z + PARAPET, u0, u1);
            if (hex == null) { flat(mesh, PLINTH_MATERIAL, List.of(a, b, d, c), z + PARAPET, 1); }
            else { clippedFlat(mesh, hex, PLINTH_MATERIAL, List.of(a, b, d, c), z + PARAPET); }
            // A parapet ending where the roof runs on closes its end.
            if (!wall[(edge + 1) % 4]) { face(mesh, hex, WALL, b, d, -ny, nx, z, z + PARAPET, 0, PARAPET_WIDTH / WALL_REPEAT); }
            if (!wall[(edge + 3) % 4]) { face(mesh, hex, WALL, c, a, ny, -nx, z, z + PARAPET, 0, PARAPET_WIDTH / WALL_REPEAT); }
            if (bottom < EPSILON) {
                float[] e = { b[0] + nx * COURSE_DEPTH, b[1] + ny * COURSE_DEPTH }, f = { a[0] + nx * COURSE_DEPTH, a[1] + ny * COURSE_DEPTH };
                prism(mesh, hex, PLINTH_MATERIAL, List.of(b, e, f, a), 0, COURSE, 3);
            }
            // The pier at its start: on the corner's bisector where the facade before it turns there.
            int previous = (edge + 3) % 4;
            boolean corner = wall[previous];
            if (!corner && (lod > 0 || cell[5 + previous] > z + EPSILON)) { continue; }
            float px = corner ? nx + normals[previous][0] : nx, py = corner ? ny + normals[previous][1] : ny;
            float length = (float) Math.hypot(px, py);
            px /= length;
            py /= length;
            if (hex == null || inside(hex, a[0] - px, a[1] - py)) {
                float from = corner ? Math.max(bottom, Math.max(0, cell[5 + previous])) : bottom;
                pier(mesh, a[0], a[1], px, py, corner ? CORNER_PIER : PIER_HALF, from, z + PARAPET + .6f, from < EPSILON);
            }
        }
    }

    /** Where {@code point} lies along a facade facing (nx, ny), from the viewer's left, in wall texture repeats. */
    private static float across(float[] point, float nx, float ny) {
        return nx != 0 ? point[1] * nx / (HEIGHT / 2) : -point[0] * ny / (WIDTH * .75f / 2);
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
    private static void shell(Mesh mesh, float top, float[] reach, float[] notches) {
        TreeSet<Float> breaks = new TreeSet<>(List.of(0f, top));
        for (float connected : reach) { if (connected > EPSILON && connected < top - EPSILON) { breaks.add(connected); } }
        List<Float> heights = List.copyOf(breaks);
        for (int band = 0; band < heights.size() - 1; band++) {
            float bottom = heights.get(band), ceiling = heights.get(band + 1);
            int closed = closed(reach, bottom);
            List<float[]> outline = outline(closed, SETBACK);
            walls(mesh, PLINTH_MATERIAL, outline, closed, SETBACK, bottom, ceiling);
            notchWalls(mesh, PLINTH_MATERIAL, reach, notches, bottom, ceiling);
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

    /**
     * The walls closing this share where it reaches a corner its connected neighbour's facade stands back from
     * ({@link Layout#notches}): along the hexside, from the corner to that facade.
     */
    private static void notchWalls(Mesh mesh, String material, float[] reach, float[] notches, float bottom, float ceiling) {
        for (int direction = 0; direction < 6; direction++) {
            if (reach[direction] <= bottom + EPSILON) { continue; }
            int edge = Math.floorMod(1 - direction, 6);
            float[] first = { HexTile.cornerOffsetX(edge, WIDTH), HexTile.cornerOffsetY(edge, HEIGHT) };
            float[] last = { HexTile.cornerOffsetX((edge + 1) % 6, WIDTH), HexTile.cornerOffsetY((edge + 1) % 6, HEIGHT) };
            for (int side = 0; side < 2; side++) {
                int beside = (direction + (side == 0 ? 1 : 5)) % 6;
                float from = Math.max(bottom, notches[direction * 2 + side]);
                if (reach[beside] <= bottom + EPSILON || from >= ceiling - EPSILON) { continue; }
                // The corner shared with direction + 1 is the hexside's first in counter-clockwise order.
                float[] corner = side == 0 ? first : last, other = side == 0 ? last : first;
                float length = (float) Math.hypot(other[0] - corner[0], other[1] - corner[1]);
                float[] end = { corner[0] + (other[0] - corner[0]) * NOTCH / length,
                      corner[1] + (other[1] - corner[1]) * NOTCH / length };
                facade(mesh, material, side == 0 ? corner : end, side == 0 ? end : corner, side(direction), from, ceiling);
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

    /** A pier's half width at a corner and along a facade, how far it stands out, its chamfer; its footing's. */
    private static final float CORNER_PIER = 2.6f, PIER_HALF = 1.8f, PIER_DEPTH = 2.1f, PIER_CHAMFER = .75f,
          FOOTING_GROW = .9f, FOOTING_DEPTH = 3.2f, FOOTING = 2.4f, COURSE = 1.6f, COURSE_DEPTH = .9f;
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

    /** A service bank's curb: a low concrete block with a white duct along its +cross side. */
    private static void pad(Mesh mesh, Pad pad) {
        float rx = pad.ux(), ry = pad.uy(), cx = -ry, cy = rx;
        block(mesh, PLINTH_MATERIAL, pad.x(), pad.y(), rx, ry, pad.along(), pad.across(), pad.z(), pad.z() + CURB);
        float ductX = pad.x() + cx * (pad.across() - DUCT / 2 - .4f), ductY = pad.y() + cy * (pad.across() - DUCT / 2 - .4f);
        block(mesh, FRAME, ductX, ductY, rx, ry, pad.along() - .6f, DUCT / 2, pad.z() + CURB, pad.z() + CURB + DUCT);
    }

    /** A block centred at (x, y), {@code along} and {@code across} half sizes along (rx, ry) and across it. */
    private static void block(Mesh mesh, String material, float x, float y, float rx, float ry, float along, float across,
          float bottom, float top) {
        List<float[]> corners = corners(new Pad(x, y, rx, ry, along, across, bottom), 0);
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
        for (int entrance = 0; entrance < dome.entrances().size(); entrance += 2) {
            vestibule(mesh, hex, dome, dome.entrances().get(entrance), dome.entrances().get(entrance + 1));
        }
        for (int wing = 0; wing < dome.wings().size(); wing += 6) { annexWing(mesh, hex, dome, wing); }
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
        // A service unit on the ring roof over each side: a housing, its long side along the ring.
        float middle = (inner + flat) / 2, along = Math.min(7, (flat - inner) * .45f);
        for (int side = 0; side < sides; side++) {
            double angle = Math.PI / sides * (2 * side + 1);
            float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
            float rise = side % 2 == 0 ? 1.8f : 1.1f;
            clippedBlock(mesh, hex, FRAME, x + ux * middle, y + uy * middle, -uy, ux, along, along * .55f, top, top + rise);
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

    /** An entrance wing: an enclosed walkway from an airlock's back (x, y about the dome's centre) to its podium. */
    private static void vestibule(Mesh mesh, List<float[]> hex, Dome dome, float x, float y) {
        float distance = (float) Math.hypot(x, y), end = dome.podium() * (float) Math.cos(Math.PI / 12) - 1;
        if (distance <= end + 1) { return; }
        float ux = -x / distance, uy = -y / distance, cx = -uy, cy = ux, half = 4.6f;
        float bottom = dome.foot(), top = dome.foot() + Math.min(9, dome.height());
        float[][] start = { { dome.x() + x + cx * half, dome.y() + y + cy * half },
              { dome.x() + x - cx * half, dome.y() + y - cy * half } };
        float reach = distance - end;
        float[][] stop = { { start[0][0] + ux * reach, start[0][1] + uy * reach },
              { start[1][0] + ux * reach, start[1][1] + uy * reach } };
        float length = reach / 12;
        // Its two sides and its roof, faced outward.
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? 1 : -1, nx = cx * sign, ny = cy * sign;
            float[] a0 = vertex(start[side][0], start[side][1], bottom, nx, ny, 0, .8f, 0, 0);
            float[] b0 = vertex(stop[side][0], stop[side][1], bottom, nx, ny, 0, .8f, length, 0);
            float[] b1 = vertex(stop[side][0], stop[side][1], top, nx, ny, 0, 1, length, .58f);
            float[] a1 = vertex(start[side][0], start[side][1], top, nx, ny, 0, 1, 0, .58f);
            if (side == 0) {
                mesh.clipped(FRAME, hex, a0, b1, b0);
                mesh.clipped(FRAME, hex, a0, a1, b1);
            } else {
                mesh.clipped(FRAME, hex, a0, b0, b1);
                mesh.clipped(FRAME, hex, a0, b1, a1);
            }
        }
        float[] r0 = vertex(start[0][0], start[0][1], top, 0, 0, 1, 1, 0, 0);
        float[] r1 = vertex(start[1][0], start[1][1], top, 0, 0, 1, 1, 0, .53f);
        float[] r2 = vertex(stop[1][0], stop[1][1], top, 0, 0, 1, 1, length, .53f);
        float[] r3 = vertex(stop[0][0], stop[0][1], top, 0, 0, 1, 1, length, 0);
        mesh.clipped(FRAME, hex, r0, r1, r2);
        mesh.clipped(FRAME, hex, r0, r2, r3);
    }

    /**
     * An annex's wing ({@link #wing}): a block from the annex's hexside to the middle of the podium's ring, faced and
     * parapeted as the annex, its roof continuing the annex's.
     */
    private static void annexWing(Mesh mesh, List<float[]> hex, Dome dome, int at) {
        var wing = dome.wings();
        float mx = dome.x() + wing.get(at), my = dome.y() + wing.get(at + 1), ix = wing.get(at + 2), iy = wing.get(at + 3);
        float bottom = dome.foot(), top = dome.foot() + wing.get(at + 4), length = wing.get(at + 5);
        float lx = -iy, ly = ix;
        // Counter-clockwise from the start's right: the hexside it starts at stays open to the annex.
        List<float[]> outer = List.of(new float[] { mx - lx * CORE, my - ly * CORE },
              new float[] { mx - lx * CORE + ix * length, my - ly * CORE + iy * length },
              new float[] { mx + lx * CORE + ix * length, my + ly * CORE + iy * length },
              new float[] { mx + lx * CORE, my + ly * CORE });
        float inset = CORE - PARAPET_WIDTH, reach = length - PARAPET_WIDTH;
        List<float[]> inner = List.of(new float[] { mx - lx * inset, my - ly * inset },
              new float[] { mx - lx * inset + ix * reach, my - ly * inset + iy * reach },
              new float[] { mx + lx * inset + ix * reach, my + ly * inset + iy * reach },
              new float[] { mx + lx * inset, my + ly * inset });
        float[][] normals = { { -lx, -ly }, { ix, iy }, { lx, ly } };
        for (int side = 0; side < 3; side++) {
            float[] a = outer.get(side), b = outer.get(side + 1), c = inner.get(side), d = inner.get(side + 1);
            // Textured as the annex's core facades, so the wing's sides continue them.
            float along = across(a, normals[side][0], normals[side][1]), next = across(b, normals[side][0], normals[side][1]);
            face(mesh, hex, WALL, a, b, normals[side][0], normals[side][1], bottom, top + PARAPET, along, next);
            face(mesh, hex, WALL, c, d, -normals[side][0], -normals[side][1], top, top + PARAPET, along, next);
            clippedFlat(mesh, hex, PLINTH_MATERIAL, List.of(a, b, d, c), top + PARAPET);
            // A pier at the middle of each side, on its footing on the plinth: the share it stands over draws it.
            float px = (a[0] + b[0]) / 2, py = (a[1] + b[1]) / 2;
            if (side != 1 && inside(hex, px, py)) {
                pier(mesh, px, py, normals[side][0], normals[side][1], PIER_HALF, bottom, top + PARAPET + .6f, true);
            }
        }
        clippedFlat(mesh, hex, ROOF, inner, top);
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
