/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.BoardBankClip.footprint;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardBankClip.outsideBanks;
import static megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.triangle;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Facet;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Part;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Shape;
import megamek.common.board.Coords;

/** A separate, hollow rock span. Its shell never replaces the ground, roads or water underneath. */
final class BoardNaturalBridge {
    private BoardNaturalBridge() { }

    /** How far, in metres, a corner joint's edge runs on into the ground's corner, past its rounded-off cliff. */
    private static final float CORNER_DEPTH = 9;
    /** The rubble a collapsed span leaves, as its terrain layout draws it ({@link #broken}). */
    private static final String RUBBLE = "scenery/rubble/";
    /** Radians over which the deck's centre blends the rim heights its spokes carry (see {@link #blend}). */
    private static final float BLEND = .6f;
    /**
     * Down an exposed face, each broken stratum's share of the depth and its outward relief in metres: a rounded lip
     * overhangs an undercut band, and a lower ledge stands above the soffit. A medium span keeps only the lip.
     */
    private static final float[][] STRATA = { { .2f, .9f }, { .45f, -.6f }, { .72f, .35f } };

    /**
     * An outline point. {@code exit}: the direction whose mouth it belongs to, else -1. {@code corner}: it seats a closed
     * corner ({@link #closes}). {@code shared}: it lies on an edge that a joined span builds too. {@code landing}: the
     * share of its spoke over which the deck reaches its height, less than 1 where it lies buried beyond its hex edge.
     */
    private record Rim(Vector3 point, int exit, boolean corner, boolean shared, float landing) {
        Rim(Vector3 point, int exit, boolean corner, boolean shared) { this(point, exit, corner, shared, 1); }

        boolean contact() { return exit >= 0 || corner; }
    }

    /** The outline from a point to the next: an exposed, eroded rim; an end in its ground; or an edge a joined span shares. */
    private enum Seam { OPEN, END, JOINT }

    static Shape build(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck, TerrainLod lod,
          Map<Coords, BoardSurface> surfaces) {
        return build(scene, tile, deck, lod, surfaces, false);
    }

    /** Classic retains a normal hex cap with graded exits; both envelopes use the same hollow shell. */
    static Shape build(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck, TerrainLod lod,
          Map<Coords, BoardSurface> surfaces, boolean fullHex) {
        var bridge = BoardBridge.feature(tile);
        float level = tile.elevation() + bridge.elevation();
        var center = BoardGeometry.center(tile.coords(), level);
        int exits = bridge.bridgeExits();
        float space = clearance(tile, center.z);
        boolean[] levelBanks = new boolean[6];
        var bankTops = new Path2D.Float();
        float[] reaches = new float[6];
        var mouths = new ArrayList<List<Rim>>();
        boolean slab = !fullHex && overhang(scene, tile);
        for (int d = 0; d < 6 && !fullHex; d++) {
            var gate = BoardGeometry.center(tile.coords().translated(d), level).sub(center).scl(.5f);
            float length = gate.len();
            var along = new Vector3(gate).nor();
            var mouth = new ArrayList<Rim>();
            mouths.add(mouth);
            if ((exits & (1 << d)) == 0 || slab && free(scene, tile, d)) {
                // A free side narrows to the arch's waist. Between two supports dropping into a hollow beyond it,
                // it is the lip the banks continue around, so it keeps near the hex edge instead of pinching in. An
                // overhang's free end is a side of its slab too.
                float wander = noise((center.x + gate.x) * .45f, (center.y + gate.y) * .45f);
                float waist = slab ? slabEdge(scene, tile, d, wander)
                      : drops(scene, tile, d, (d + 5) % 6) && drops(scene, tile, d, (d + 1) % 6)
                      ? .86f + .08f * wander : .38f + .16f * wander;
                mouth.add(new Rim(new Vector3(center).mulAdd(along, length * waist), -1, false, false));
                continue;
            }
            var next = scene.tile(tile.coords().translated(d));
            boolean bank = BoardBridge.abutment(tile, next, d);
            boolean joined = spans(tile, next, d);
            // Only walkable landings need the extra flare; tall cliffs receive the full-width span itself.
            float width = mouthWidth(center.x + gate.x, center.y + gate.y, bank && BoardBridge.bank(tile, next, d));
            levelBanks[d] = bank && BoardRoadGrade.ground(next) == level;
            var across = new Vector3(-along.y, along.x, 0);
            var ground = bank ? surfaces.computeIfAbsent(next.coords(), c -> new BoardSurface(scene, next, lod)) : null;
            if (bank) {
                // Fit the entire rock body into the rendered wall, whose recess changes with height and width.
                float reach = bankReach(scene, ground, surfaces, center, along, width, space, d);
                gate.set(along).scl(Math.max(length + BoardRelief.metres(1), reach));
                reaches[d] = gate.len() - length;
            }
            var contact = bank ? ground.foundation() : List.<BoardSurface.Face>of();
            if (levelBanks[d]) {
                for (var face : contact) {
                    if (face.finish() == BoardSurface.Finish.TOP) {
                        bankTops.append(footprint(face.a(), face.b(), face.c()), false);
                    }
                }
            }
            // A straight two-corner mouth can miss a scalloped cliff between the corners. Fit the entire opening;
            // keep these contact samples at every LOD and never use a decorative boulder as the bank height.
            int sections = bank ? 4 : 1;
            for (int i = 0; i <= sections; i++) {
                var point = new Vector3(center).add(gate).mulAdd(across, width * (1 - 2f * i / sections));
                if (bank && BoardBridge.bank(tile, next, d)) {
                    float[] settled = ground.relief.settle(point.x, point.y, BoardRelief.metres(.7f));
                    // The deck ramps to the bank's top, unless the board marks that side a cliff top: there the rock
                    // enters the bank's wall at the deck's height.
                    point.set(settled[0], settled[1], cliff(next, d) ? center.z
                          : BoardSurface.sampleHeight(contact, settled[0], settled[1], BoardGeometry.groundZ(next))
                                - (levelBanks[d] ? 0 : BoardRelief.metres(.025f)));
                } else if (joined) {
                    point.z = BoardBridge.edgeElevation(tile, next, d) * BoardGeometry.level();
                }
                // A bank's cliff stands at or behind the hex edge: meet its height there, not only inside the bank.
                float landing = bank ? Math.min(1, length / Math.max(length, new Vector3(point).sub(center).dot(along))) : 1;
                mouth.add(new Rim(point, d, false, joined, landing));
            }
        }
        // Directions run clockwise, each followed by the corner it shares with the next. A corner may trim its mouths.
        var corners = new ArrayList<List<Rim>>();
        var tongues = new ArrayList<Tongue>();
        for (int d = 0; d < 6 && !fullHex; d++) {
            var points = new ArrayList<Rim>();
            corners.add(points);
            if (closes(scene, tile, d)) {
                var tongue = corner(scene, tile, d, center, mouths, Math.max(reaches[d], reaches[(d + 1) % 6]), points,
                      surfaces, lod);
                if (tongue != null) { tongues.add(tongue); }
            } else {
                shoulder(scene, tile, d, center, mouths, points);
                if (slab && points.isEmpty() && open(scene, tile, d) && open(scene, tile, (d + 1) % 6)) {
                    // Between two of an overhang's own edges its slab reaches out towards the hex corner.
                    var vertex = BoardGeometry.corner(tile.coords(), 0, Math.floorMod(1 - d, 6));
                    var point = new Vector3(center).lerp(vertex, .8f + .14f * noise(vertex.x * .3f, vertex.y * .3f));
                    point.z = center.z;
                    points.add(new Rim(point, -1, false, false));
                }
            }
        }
        var outline = new ArrayList<Rim>();
        if (fullHex) {
            var cornerRises = BoardBridge.naturalCornerRises(scene, tile);
            for (int k = 0; k < 6; k++) {
                var corner = BoardGeometry.corner(tile.coords(), level, k);
                corner.z += cornerRises.get(k) * BoardGeometry.level();
                outline.add(new Rim(corner, BoardGeometry.edgeDirection(k), false, false));
            }
        }
        for (int d = 0; d < 6 && !fullHex; d++) {
            outline.addAll(mouths.get(d));
            outline.addAll(corners.get(d));
        }
        // Wide neighboring mouths can cross their directional sectors. Order their points around the centre
        // before rounding so the radial shell never doubles back over an adjacent branch.
        outline.sort(Comparator.comparingDouble(edge -> Math.atan2(edge.point().y - center.y, edge.point().x - center.x)));
        // A ramp up to a bank is one plane rising towards it, across the whole rock: its free edges climb with the
        // deck beside the mouth, so no wedge of rock stands between the ramp and the side. It gives way to any lower
        // mouth or corner, whose rock keeps its height.
        float[] ramps = new float[6];
        for (int d = 0; d < 6 && !fullHex; d++) {
            var mouth = mouths.get(d);
            if (mouth.isEmpty() || mouth.getFirst().exit() != d) { continue; }
            float rise = 0;
            for (var rim : mouth) { rise += rim.point().z - center.z; }
            rise /= mouth.size();
            if (rise < BoardRelief.metres(.5f)) { continue; }
            ramps[d] = rise;
        }
        for (int d = 0; d < 6; d++) {
            for (var edge : outline) {
                if (ramps[d] == 0 || edge.contact()) { continue; }
                var point = edge.point();
                float lift = reach(tile, outline, center, point, d, ramps[d]);
                point.z = Math.max(point.z, center.z + (plane(tile, center, point, d, ramps[d]) - center.z) * lift);
            }
        }
        int edgeSteps = fullHex ? 1 : lod == TerrainLod.FULL ? 5 : lod == TerrainLod.MEDIUM ? 3 : lod == TerrainLod.COARSE ? 2 : 1;
        if (slab) { edgeSteps = Math.min(edgeSteps, lod == TerrainLod.FULL ? 3 : 2); }
        float soft = BoardRelief.jointScale(shell(deck.surface()).ordinal());
        var rim = new ArrayList<Rim>();
        var seams = new ArrayList<Seam>();
        for (int i = 0; i < outline.size(); i++) {
            var a = outline.get(i);
            var b = outline.get((i + 1) % outline.size());
            // A tongue continues the top beyond its corner's edge to the mouth: no wall there either.
            boolean tongued = tongues.stream().anyMatch(tongue -> tongue.vertex() == a && tongue.mouth() == b
                  || tongue.vertex() == b && tongue.mouth() == a);
            var seam = a.exit() >= 0 && a.exit() == b.exit() || a.corner() || b.corner()
                  ? a.shared() && b.shared() || tongued ? Seam.JOINT : Seam.END : Seam.OPEN;
            if (fullHex) {
                // A Classic cap's edge continues into a joined cap at its own height; any other edge is walled.
                var next = scene.tile(tile.coords().translated(a.exit()));
                seam = spans(tile, next, a.exit()) && natural(next) && deck(next) == level ? Seam.JOINT : Seam.END;
            }
            int steps = seam == Seam.OPEN ? edgeSteps : 1;
            rim.add(a);
            seams.add(seam);
            if (slab) {
                jointed(rim, seams, outline, i, steps, center, soft);
                continue;
            }
            for (int j = 1; j < steps; j++) {
                float t = j / (float) steps;
                var point = curve(outline, i, t, center);
                var outward = new Vector3(point).sub(center); outward.z = 0; outward.nor();
                // A fixed contact keeps its place; the irregular outline grows away from it.
                float free = (a.contact() ? BoardRelief.smooth(2 * t) : 1) * (b.contact() ? BoardRelief.smooth(2 - 2 * t) : 1);
                point.mulAdd(outward, BoardRelief.metres(2) * (noise(point.x * .6f, point.y * .6f) - .5f) * free);
                rim.add(new Rim(point, -1, false, false));
                seams.add(Seam.OPEN);
            }
        }
        // Only an anchor's rock, or a joined span's, reaches past an overhang's own hex edges; its authored free ends
        // reach them, as the deck does, and stop there.
        for (var edge : rim) {
            if (!slab || edge.contact()) { continue; }
            var point = edge.point();
            for (int d = 0; d < 6; d++) {
                var beyond = scene.tile(tile.coords().translated(d));
                if (anchors(scene, tile, d) || BoardBridge.connected(tile, beyond, d) || wall(tile, beyond)) {
                    continue;
                }
                float share = BoardGeometry.share(tile.coords(), d, point.x, point.y);
                float limit = (exits & (1 << d)) != 0 ? 1 : .97f;
                if (share > limit) {
                    float z = point.z;
                    point.sub(center).scl(limit / share).add(center);
                    point.z = z;
                }
            }
        }
        float[] exposure = exposure(rim);
        // How much of each ramp's plane each spoke takes (its own mouth and the free rim, never another mouth), and how
        // far its inner rings draw in with the ramp's.
        float[][] lifts = new float[6][rim.size()];
        float[] drawn = new float[rim.size()];
        for (int d = 0; d < 6; d++) {
            for (int i = 0; i < rim.size() && ramps[d] > 0; i++) {
                var edge = rim.get(i);
                float weight = reach(tile, outline, center, edge.point(), d, ramps[d]);
                drawn[i] = Math.max(drawn[i], weight);
                if (!edge.contact() || edge.exit() == d) { lifts[d][i] = weight; }
            }
        }
        int rings = lod == TerrainLod.FULL ? 5 : lod == TerrainLod.MEDIUM ? 4 : lod == TerrainLod.COARSE ? 3 : 1;
        if (fullHex) { rings = Math.max(2, rings); }
        var facets = new ArrayList<Facet>();
        Vector3[][] top = new Vector3[rings + 1][rim.size()], bottom = new Vector3[rings + 1][rim.size()];
        for (int r = 0; r <= rings; r++) {
            // Near spans retain an exposed caprock band; the distant span only needs the centre and silhouette.
            float t = r == 0 ? 0 : r == rings ? 1 : .8f * r / (rings - 1);
            if (fullHex && r > 0) { t = .5f + .5f * (r - 1) / (rings - 1); }
            float[][] blended = blend(rim, exposure, center, 1 - t);
            for (int i = 0; i < rim.size(); i++) {
                var edge = rim.get(i);
                float exposed = blended[2][i];
                // A ramp's spoke reaching into its bank keeps its inner rings at the radii of a spoke ending at the hex
                // edge, so the fan's triangles never stretch across very different radii into a twisted, steep facet.
                float spoke = Vector2.dst(center.x, center.y, edge.point().x, edge.point().y);
                float at = r == rings || fullHex ? t
                      : t * (1 + (Math.min(1, BoardGeometry.width() / 2 / spoke) - 1) * drawn[i]);
                var point = new Vector3(center).lerp(edge.point(), at);
                point.z = fullHex ? center.z + (edge.point().z - center.z) * Math.max(0, 2 * t - 1)
                      : center.z + (blended[0][i] - center.z) * Math.min(1, at / blended[1][i]);
                // Only the exposed rim erodes. Every contact keeps the plane its bank, joined span or end shares, and
                // the erosion fades in along the rim, so neighbouring spokes never step against each other.
                point.z -= exposed * Math.min(BoardRelief.metres(1.4f), space * .16f) * t * (1 - t)
                      * (.3f + .7f * noise(point.x * .35f, point.y * .35f));
                // An overhang's caprock keeps a firmer edge over open space; beside a bank it tucks under the lip.
                float round = slab
                      ? Math.min(BoardRelief.metres(.9f + .4f * anchoring(scene, tile, point)), space * .14f)
                      : Math.min(BoardRelief.metres(1.3f), space * .16f);
                point.z -= exposed * round * t * t * (.6f + .4f * noise(point.x, point.y));
                // A ramp is one plane rising towards its bank: its mouth's spokes and the free spokes beside them take
                // it all the way in, so neighbouring spokes of different lengths meet without a fold. The rim keeps
                // the mouth's contacts on the bank's own top.
                for (int d = 0; d < 6; d++) {
                    if (lifts[d][i] > 0 && (r < rings || !edge.contact())) {
                        float ramp = plane(tile, center, point, d, ramps[d]);
                        point.z = Math.max(point.z, center.z + (ramp - center.z) * lifts[d][i]);
                    }
                }
                top[r][i] = point;
                bottom[r][i] = new Vector3(point).add(0, 0, -thickness(scene, tile, space, point, slab));
                if (exposed > 0 && !slab) {
                    var inward = new Vector3(center).sub(point); inward.z = 0; inward.nor();
                    bottom[r][i].mulAdd(inward, BoardRelief.metres(.8f) * t * t * t * t * exposed);
                }
            }
        }
        Vector3[][] layers = slab
              ? beds(scene, tile, deck, lod, rim, exposure, center, top[rings], bottom, soft) : null;
        for (int i = 0; i < rim.size(); i++) {
            int n = (i + 1) % rim.size();
            var seam = seams.get(i);
            for (int r = 1; r <= rings; r++) {
                var cover = rings > 1 && r == rings && seam == Seam.OPEN ? Part.RIM : Part.TOP;
                triangle(facets, top[r - 1][i], top[r][i], top[r][n], cover);
                triangle(facets, bottom[r - 1][i], bottom[r][n], bottom[r][i], Part.SOFFIT);
                if (r > 1) {
                    triangle(facets, top[r - 1][i], top[r][n], top[r - 1][n], cover);
                    triangle(facets, bottom[r - 1][i], bottom[r - 1][n], bottom[r][n], Part.SOFFIT);
                }
            }
            // A joined span builds the other side of a shared edge: no internal wall.
            if (seam == Seam.JOINT) { continue; }
            if (slab) {
                bedFaces(facets, layers, i, n);
                continue;
            }
            // Broad broken strata define the close silhouette; the shared normal maps supply small fissures.
            int beds = fullHex ? 0 : lod == TerrainLod.FULL ? STRATA.length : lod == TerrainLod.MEDIUM ? 1 : 0;
            var a = top[rings][i];
            var b = top[rings][n];
            for (int bed = 0; bed <= beds; bed++) {
                float t = bed < beds ? STRATA[bed][0] : 1;
                var c = new Vector3(top[rings][i]).lerp(bottom[rings][i], t);
                var e = new Vector3(top[rings][n]).lerp(bottom[rings][n], t);
                if (bed < beds) {
                    for (int side = 0; side < 2; side++) {
                        float exposed = exposure[side == 0 ? i : n];
                        if (exposed == 0) { continue; }
                        var p = side == 0 ? c : e;
                        var outward = new Vector3(p).sub(center); outward.z = 0; outward.nor();
                        p.mulAdd(outward, BoardRelief.metres(STRATA[bed][1]) * (.4f + .6f * noise(p.x + bed, p.y)) * exposed);
                    }
                }
                triangle(facets, a, c, e, Part.SIDE);
                triangle(facets, a, e, b, Part.SIDE);
                a = c; b = e;
            }
        }
        for (var tongue : tongues) {
            int corner = rim.indexOf(tongue.vertex()), mouth = rim.indexOf(tongue.mouth());
            var deep = tongue.deep();
            var under = new Vector3(deep).add(0, 0, -thickness(scene, tile, space, deep, slab));
            var outward = new Vector3(deep).add(tongue.mouth().point()).scl(.5f).sub(tongue.vertex().point());
            outward.z = 0;
            facing(facets, top[rings][corner], new Vector3(deep), top[rings][mouth], Part.TOP, Vector3.Z);
            facing(facets, bottom[rings][corner], under, bottom[rings][mouth], Part.SOFFIT, new Vector3(0, 0, -1));
            facing(facets, new Vector3(deep), top[rings][mouth], bottom[rings][mouth], Part.SIDE, outward);
            facing(facets, new Vector3(deep), bottom[rings][mouth], under, Part.SIDE, outward);
        }
        return BoardBridge.shape(fullHex ? deck.surface() : shell(deck.surface()), level,
              fullHex ? facets : outsideBanks(scene, tile, facets, new Area(bankTops), level * BoardGeometry.level()));
    }

    /**
     * The rock of an Enhanced span over banks of this family: a meadow does not root on a thin arch, so over grass the
     * shell is bare rock, onto which the banks' cover fades across each passage ({@link BoardSurfaceBlend}).
     */
    static BoardScene.Surface shell(BoardScene.Surface banks) {
        return banks == BoardScene.Surface.GRASS ? BoardScene.Surface.ROCK : banks;
    }

    /** A facet wound to face {@code toward}. */
    private static void facing(List<Facet> facets, Vector3 a, Vector3 b, Vector3 c, Part part, Vector3 toward) {
        var normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        if (normal.dot(toward) < 0) { triangle(facets, a, c, b, part); } else { triangle(facets, a, b, c, part); }
    }

    /**
     * The rock's depth below a top point: a thin crown, thickening where banks anchor it and kept clear of the last whole
     * level below; joined spans weigh their own anchors and room across their shared edge, so both agree there. An
     * overhang's {@code slab} is thicker.
     */
    private static float thickness(BoardScene scene, BoardScene.Tile tile, float space, Vector3 point, boolean slab) {
        float anchored = anchoring(scene, tile, point), room = space;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (!spans(tile, next, d)) { continue; }
            float u = Math.clamp(BoardGeometry.share(tile.coords(), d, point.x, point.y), 0, 1);
            float join = Math.clamp(2 * u - 1, 0, 1);
            anchored = Math.max(anchored, anchoring(scene, next, point) * join);
            float nextLevel = (next.elevation() + BoardBridge.feature(next).elevation()) * BoardGeometry.level();
            room = Math.min(room, space + (clearance(next, nextLevel) - space) * join);
        }
        // An overhang is a thick slab of beds, most of the room it may take above the level kept clear below.
        float crown = slab
              ? Math.min(BoardRelief.metres(3.8f) * (.88f + .24f * noise(point.x * .35f, point.y * .35f)), room * .62f)
              : Math.min(BoardRelief.metres(2.7f) * (.82f + .3f * noise(point.x * .35f, point.y * .35f)), room * .43f);
        float spring = slab ? Math.min(BoardRelief.metres(6), room * .86f)
              : Math.min(BoardRelief.metres(5.3f), room * .79f);
        return (crown + (spring - crown) * anchored) * (.93f + .1f * noise(point.x, point.y));
    }

    /**
     * The rim's heights, landings ({@link Rim#landing}) and {@code exposure} as each spoke towards it carries them
     * inwards, over a spread around the centre that grows with {@code spread} (none at the rim). Heights and landings
     * blend, so a deck climbing to a bank or falling to a joint curves smoothly between its spokes rather than folding
     * along them. Erosion only fades towards a contact's spoke, however close the two run, and never reaches it.
     */
    private static float[][] blend(List<Rim> rim, float[] exposure, Vector3 center, float spread) {
        int count = rim.size();
        float[][] result = new float[3][count];
        float sigma = BLEND * spread;
        if (sigma <= 0) {
            for (int i = 0; i < count; i++) {
                result[0][i] = rim.get(i).point().z;
                result[1][i] = rim.get(i).landing();
                result[2][i] = exposure[i];
            }
            return result;
        }
        float[] angles = new float[count];
        for (int i = 0; i < count; i++) {
            var p = rim.get(i).point();
            angles[i] = (float) Math.atan2(p.y - center.y, p.x - center.x);
        }
        float[] arcs = new float[count];
        for (int i = 0; i < count; i++) {
            arcs[i] = Math.abs(turn(angles[(i + 1) % count] - angles[Math.floorMod(i - 1, count)])) * .5f;
        }
        for (int i = 0; i < count; i++) {
            float heights = 0, landings = 0, weights = 0, exposed = exposure[i];
            for (int j = 0; j < count; j++) {
                float offset = turn(angles[j] - angles[i]) / sigma;
                float weight = arcs[j] * (float) Math.exp(-.5f * offset * offset);
                heights += weight * rim.get(j).point().z;
                landings += weight * rim.get(j).landing();
                weights += weight;
                exposed = Math.min(exposed, exposure[j] + Math.abs(offset));
            }
            result[0][i] = weights > 0 ? heights / weights : rim.get(i).point().z;
            result[1][i] = weights > 0 ? landings / weights : rim.get(i).landing();
            result[2][i] = exposed;
        }
        return result;
    }

    /** An angle difference brought into (-pi, pi]. */
    private static float turn(float angle) {
        float result = (float) Math.IEEEremainder(angle, 2 * Math.PI);
        return result <= -Math.PI ? result + (float) (2 * Math.PI) : result;
    }

    /** How freely each rim point erodes: none at a contact, fading in over the first metres of exposed rim. */
    private static float[] exposure(List<Rim> rim) {
        int count = rim.size();
        float[] distance = new float[count];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < 2 * count; k++) {
                int i = pass == 0 ? k % count : Math.floorMod(-k, count);
                int previous = pass == 0 ? Math.floorMod(i - 1, count) : (i + 1) % count;
                if (rim.get(i).contact()) { distance[i] = 0; continue; }
                var p = rim.get(i).point();
                var q = rim.get(previous).point();
                distance[i] = Math.min(distance[i], distance[previous] + Vector2.dst(p.x, p.y, q.x, q.y));
            }
        }
        float[] result = new float[count];
        for (int i = 0; i < count; i++) { result[i] = BoardRelief.smooth(distance[i] / BoardRelief.metres(2.5f)); }
        return result;
    }

    private static boolean natural(BoardScene.Tile tile) { return BoardBridge.family(tile) == BoardBridge.Family.NATURAL; }

    /** A joined natural span: the two rock shells continue into each other. */
    static boolean joined(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return spans(tile, next, direction) && natural(next);
    }

    /**
     * The decks join and the other one stands: a span beside one that fell ({@link BoardScene.Feature#ruined}) has its
     * broken end there, walled, as it keeps its form ({@link #overhang}).
     */
    private static boolean spans(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return BoardBridge.connected(tile, next, direction) && BoardBridge.standing(next) != null;
    }

    /** Both exits around the corner between directions d and d + 1 rest on ground or on a joined natural span. */
    private static boolean rests(BoardScene scene, BoardScene.Tile tile, int d) {
        return supported(scene, tile, d) && supported(scene, tile, (d + 1) % 6);
    }

    /** The exit in {@code direction} rests on ground or on a joined natural span. */
    private static boolean supported(BoardScene scene, BoardScene.Tile tile, int direction) {
        var next = scene.tile(tile.coords().translated(direction));
        return joined(tile, next, direction) || BoardBridge.abutment(tile, next, direction);
    }

    /**
     * The span is an overhang: a lone span with a free end, neither joined to a span nor left beside one that collapsed
     * ({@link #broken}). Its rock is a thick slab of the cliffs' beds, rooted wide in its banks; every other span keeps
     * its arch.
     */
    static boolean overhang(BoardScene scene, BoardScene.Tile tile) {
        int exits = BoardBridge.feature(tile).bridgeExits();
        boolean loose = false;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (BoardBridge.connected(tile, next, d) || (exits & (1 << d)) != 0 && broken(next)) { return false; }
            loose |= free(scene, tile, d);
        }
        return loose;
    }

    /**
     * The exit in {@code direction} is a free end: neither ground nor a span lies beyond it, and no cliff rises beside
     * it on either side, so the rock runs out over open space. An exit into a pit between banks is none; one off the
     * board's edge runs out over the void beyond.
     */
    static boolean free(BoardScene scene, BoardScene.Tile tile, int direction) {
        var next = scene.tile(tile.coords().translated(direction));
        if ((BoardBridge.feature(tile).bridgeExits() & (1 << direction)) == 0
              || BoardBridge.abutment(tile, next, direction) || BoardBridge.connected(tile, next, direction)) {
            return false;
        }
        for (int side : new int[] { 1, 5 }) {
            var beside = scene.tile(tile.coords().translated((direction + side) % 6));
            if (beside != null && BoardRoad.groundShown(beside) && BoardBridge.feature(beside) == null
                  && beside.floor() >= deck(tile) - 1) { return false; }
        }
        return true;
    }

    /**
     * Where a span was and collapsed: no deck, but the rubble the game leaves there (Board.collapseBuilding), as its
     * terrain layout draws it. A span left standing beside it keeps its form: it never becomes an overhang.
     */
    private static boolean broken(BoardScene.Tile tile) {
        return tile != null && BoardBridge.feature(tile) == null
              && tile.features().stream().anyMatch(feature -> feature.asset().startsWith(RUBBLE));
    }

    /** The side in {@code direction} is one of an overhang's own edges: no exit, or a free end. */
    private static boolean open(BoardScene scene, BoardScene.Tile tile, int direction) {
        return (BoardBridge.feature(tile).bridgeExits() & (1 << direction)) == 0 || free(scene, tile, direction);
    }

    /** The exit in {@code direction} rests on a bank or cliff. */
    private static boolean anchors(BoardScene scene, BoardScene.Tile tile, int direction) {
        return BoardBridge.abutment(tile, scene.tile(tile.coords().translated(direction)), direction);
    }

    /** The bank in {@code direction} marks its side towards the span as a cliff top: a wall there, not a ramp. */
    private static boolean cliff(BoardScene.Tile bank, int direction) {
        return (bank.cliffTopExits() & (1 << ((direction + 3) % 6))) != 0;
    }

    /**
     * How much of the plane of ramp {@code d}, rising {@code rise} to its bank, the rock towards {@code point} takes:
     * all of it away from any other fixed edge below the plane (a mouth or corner the plane would bury), fading out
     * within 60 degrees of one, so the rock there keeps that edge's height.
     */
    private static float reach(BoardScene.Tile tile, List<Rim> outline, Vector3 center, Vector3 point, int d,
          float rise) {
        double angle = Math.atan2(point.y - center.y, point.x - center.x), gap = Math.PI;
        for (var fixed : outline) {
            var p = fixed.point();
            if (fixed.contact() && fixed.exit() != d
                  && p.z < plane(tile, center, p, d, rise) - BoardRelief.metres(.5f)) {
                double delta = Math.abs(angle - Math.atan2(p.y - center.y, p.x - center.x));
                gap = Math.min(gap, Math.min(delta, 2 * Math.PI - delta));
            }
        }
        return BoardRelief.smooth((float) (gap / (Math.PI / 3)));
    }

    /** Ramp {@code d}'s plane over {@code point}: the deck at the centre, {@code rise} above it at the hex edge. */
    private static float plane(BoardScene.Tile tile, Vector3 center, Vector3 point, int d, float rise) {
        return center.z + rise * Math.clamp(BoardGeometry.share(tile.coords(), d, point.x, point.y), 0, 1);
    }

    /** Solid ground beyond, rising above the deck: a cliff whose wall an overhang may enter below its top. */
    private static boolean wall(BoardScene.Tile tile, BoardScene.Tile beyond) {
        return beyond != null && BoardRoad.groundShown(beyond) && BoardBridge.feature(beyond) == null
              && beyond.floor() > deck(tile);
    }

    /**
     * How far an overhang's own edge in direction {@code d} reaches, as a share of the inradius: into a higher cliff's
     * wall; to the hex edge at an authored free end, where the deck leads on; wide beside a bank at or above its deck
     * that it roots in (unless facing higher ground) and over a hollow the banks continue around; else a little short
     * of it.
     */
    private static float slabEdge(BoardScene scene, BoardScene.Tile tile, int d, float wander) {
        var beyond = scene.tile(tile.coords().translated(d));
        if (wall(tile, beyond)) { return 1.06f + .04f * wander; }
        if ((BoardBridge.feature(tile).bridgeExits() & (1 << d)) != 0) { return 1; }
        boolean rooted = false;
        for (int side : new int[] { 5, 1 }) {
            int e = (d + side) % 6;
            rooted |= anchors(scene, tile, e) && scene.tile(tile.coords().translated(e)).floor() >= deck(tile);
        }
        return rooted && (beyond == null || beyond.floor() <= deck(tile))
              || drops(scene, tile, d, (d + 5) % 6) && drops(scene, tile, d, (d + 1) % 6) ? .8f + .18f * wander
              : .62f + .18f * wander;
    }

    /**
     * An overhang's outline from {@code outline}'s point {@code i} to the next, in {@code steps} blocks broken along
     * the rock's two vertical joint sets: now and then the edge follows one set, then the other, the corner cut back by
     * weathering; most blocks stand nearly in line, scaled by the family's joint size ({@code soft}).
     */
    private static void jointed(List<Rim> rim, List<Seam> seams, List<Rim> outline, int i, int steps, Vector3 center,
          float soft) {
        var a = outline.get(i);
        var b = outline.get((i + 1) % outline.size());
        float angle = (float) Math.PI / 2 * noise(center.x * .05f + 11, center.y * .05f);
        float jx = (float) Math.cos(angle), jy = (float) Math.sin(angle);
        var from = a.point();
        for (int j = 1; j <= steps && steps > 1; j++) {
            float t = j / (float) steps;
            // A fixed contact keeps its place; the blocks grow away from it.
            float free = (a.contact() ? BoardRelief.smooth(2 * t) : 1)
                  * (b.contact() ? BoardRelief.smooth(2 - 2 * t) : 1);
            var to = b.point();
            if (j < steps) {
                to = curve(outline, i, t, center);
                var outward = new Vector3(to).sub(center); outward.z = 0; outward.nor();
                float wander = noise(to.x * .31f + 5, to.y * .31f) - .5f;
                to.mulAdd(outward, BoardRelief.metres(1.6f) * wander * free * soft);
            }
            float x = to.x - from.x, y = to.y - from.y;
            float along = x * jx + y * jy, across = y * jx - x * jy;
            boolean touches = a.contact() && j == 1 || b.contact() && j == steps;
            if (!touches && noise(to.x * .37f - 7, to.y * .37f) > .7f
                  && Math.min(Math.abs(along), Math.abs(across)) > BoardRelief.metres(.8f)) {
                boolean first = noise(from.x * 1.3f, from.y * 1.3f) > .5f;
                var corner = new Vector3(from).lerp(to, .5f);
                corner.x += ((first ? from.x + jx * along : from.x - jy * across) - corner.x) * .45f;
                corner.y += ((first ? from.y + jy * along : from.y + jx * across) - corner.y) * .45f;
                rim.add(new Rim(corner, -1, false, false));
                seams.add(Seam.OPEN);
            }
            if (j < steps) {
                rim.add(new Rim(to, -1, false, false));
                seams.add(Seam.OPEN);
            }
            from = to;
        }
    }

    /**
     * An overhang's beds at each outline point, from the caprock's edge ({@code edge}) down: the caprock and the bed
     * under it stand flush in its front face, a bed line between them, and the beds below step back towards the
     * anchors, so the underside climbs in treads. Their planes follow the cliffs' own bedding. The last bed's edge
     * becomes the underside's ({@code bottom}).
     */
    private static Vector3[][] beds(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck, TerrainLod lod,
          List<Rim> rim, float[] exposure, Vector3 center, Vector3[] edges, Vector3[][] bottom, float soft) {
        int family = shell(deck.surface()).ordinal(), rings = bottom.length - 1;
        float[] shares = lod == TerrainLod.FULL ? new float[] { .3f, .58f, .8f, 1 }
              : lod == TerrainLod.MEDIUM ? new float[] { .5f, 1 } : new float[] { 1 };
        int layers = shares.length, flush = Math.max(1, Math.min(2, layers - 1));
        Vector3[][] beds = new Vector3[layers + 1][rim.size()];
        for (int i = 0; i < rim.size(); i++) {
            var edge = edges[i];
            float depth = edge.z - bottom[rings][i].z;
            var inward = new Vector3(center).sub(edge); inward.z = 0;
            float room = inward.len() * .55f;
            inward.nor();
            float free = exposure[i] * (1.15f - anchoring(scene, tile, edge));
            beds[0][i] = edge;
            for (int k = 1; k <= layers; k++) {
                var p = new Vector3(edge);
                float share = shares[k - 1];
                p.z = edge.z - depth * share;
                if (k < layers) {
                    p.z = Math.clamp(BoardRelief.bedPlane(p, family, 2), edge.z - depth * (share + .08f),
                          edge.z - depth * (share - .08f));
                }
                // Most beds stand nearly flush with the one above, a few a little proud or set back.
                float jut = noise(edge.x * .9f + 9 * k, edge.y * .9f) - .5f;
                float recess = k == 1 ? 0 : BoardRelief.metres(.25f + 4.8f * jut * jut * jut) * free * soft;
                if (k > flush) {
                    recess += BoardRelief.metres(2.5f) * (k - flush) * free
                          * (.7f + .6f * noise(edge.x * .5f + 3 * k, edge.y * .5f));
                }
                beds[k][i] = p.mulAdd(inward, Math.min(recess, room));
            }
            bottom[rings][i] = beds[layers][i];
        }
        // The underside runs in from the last bed's edge.
        for (int r = 1; r < rings; r++) {
            float t = .8f * r / (rings - 1);
            for (int i = 0; i < rim.size(); i++) {
                float z = bottom[r][i].z;
                bottom[r][i] = new Vector3(center).lerp(beds[layers][i], t);
                bottom[r][i].z = z;
            }
        }
        return beds;
    }

    /**
     * An overhang's side from outline point i to n: each bed's face, and the tread under each bed the next leaves,
     * wound as the walls and the underside around them.
     */
    private static void bedFaces(List<Facet> facets, Vector3[][] beds, int i, int n) {
        for (int k = 1; k < beds.length; k++) {
            // From the plane above it down to its own: under the caprock, that plane is where the bed above ends.
            var a = k == 1 ? beds[0][i] : new Vector3(beds[k][i].x, beds[k][i].y, beds[k - 1][i].z);
            var b = k == 1 ? beds[0][n] : new Vector3(beds[k][n].x, beds[k][n].y, beds[k - 1][n].z);
            triangle(facets, a, beds[k][i], beds[k][n], Part.SIDE);
            triangle(facets, a, beds[k][n], b, Part.SIDE);
            if (k > 1) {
                triangle(facets, a, beds[k - 1][n], beds[k - 1][i], Part.SOFFIT);
                triangle(facets, a, b, beds[k - 1][n], Part.SOFFIT);
            }
        }
    }

    /** Beyond the free side {@code open} the ground lies below the support in {@code beside}: its cliff turns there. */
    private static boolean drops(BoardScene scene, BoardScene.Tile tile, int open, int beside) {
        var low = scene.tile(tile.coords().translated(open));
        var next = scene.tile(tile.coords().translated(beside));
        return low != null && supported(scene, tile, beside)
              && low.floor() < (joined(tile, next, beside) ? deck(next) : next.floor());
    }

    /**
     * The rock fills the corner between directions d and d + 1 when it rests on both sides and so does every span joined
     * there, so the spans sharing a corner always agree. An open corner keeps its curved, eroded rim.
     */
    static boolean closes(BoardScene scene, BoardScene.Tile tile, int d) {
        if (!rests(scene, tile, d)) { return false; }
        var first = scene.tile(tile.coords().translated(d));
        var second = scene.tile(tile.coords().translated((d + 1) % 6));
        return (!joined(tile, first, d) || rests(scene, first, (d + 2) % 6))
              && (!joined(tile, second, (d + 1) % 6) || rests(scene, second, (d + 4) % 6));
    }

    static float deck(BoardScene.Tile tile) { return tile.elevation() + BoardBridge.feature(tile).elevation(); }

    /**
     * The part of a closed corner beyond the hex corner {@code vertex}: the rock runs on along the joint's edge to
     * {@code deep}, inside the ground's rounded-off corner, and back to its mouth point {@code mouth}. It is built apart
     * from the centre's fan, which it would fold, and meets the joined span's own along that edge.
     */
    private record Tongue(Rim vertex, Vector3 deep, Rim mouth) { }

    /**
     * Seat a closed corner. Spans meeting there take one height and one point, built from the shared lattice corner, so
     * their tops meet exactly; beside ground the joint's edge runs on into that ground's rounded-off corner (the result).
     */
    private static Tongue corner(BoardScene scene, BoardScene.Tile tile, int d, Vector3 center, List<List<Rim>> mouths,
          float reach, List<Rim> points, Map<Coords, BoardSurface> surfaces, TerrainLod lod) {
        int e = (d + 1) % 6;
        var first = scene.tile(tile.coords().translated(d));
        var second = scene.tile(tile.coords().translated(e));
        boolean joinsFirst = joined(tile, first, d), joinsSecond = joined(tile, second, e);
        var vertex = BoardGeometry.corner(tile.coords(), 0, Math.floorMod(1 - d, 6));
        // A joint's mouth is wider than its hex edge: where the corner closes, the shared corner takes its place.
        if (joinsFirst) { overshoot(mouths.get(d), false, vertex, BoardGeometry.corner(tile.coords(), 0, Math.floorMod(2 - d, 6))); }
        if (joinsSecond) { overshoot(mouths.get(e), true, vertex, BoardGeometry.corner(tile.coords(), 0, Math.floorMod(-d, 6))); }
        if (joinsFirst && joinsSecond) {
            vertex.z = (deck(tile) + deck(first) + deck(second)) / 3 * BoardGeometry.level();
            points.add(new Rim(vertex, -1, true, true));
            return null;
        }
        if (!joinsFirst && !joinsSecond) {
            // Only this span reaches a corner between two grounds. Its wide mouths may cross the corner's ray
            // from the centre: each ends there, and the corner is seated on that ray as deep as the mouths reach,
            // into the grounds' rounded-off corner but never past it, where their shared edge ends.
            var ray = new Vector3(vertex).sub(center);
            ray.z = 0;
            float radius = ray.len();
            ray.nor();
            float depth = Math.max(trim(mouths.get(d), false, ray, center), trim(mouths.get(e), true, ray, center));
            float seat = radius + Math.min(Math.max(reach, depth - radius), BoardRelief.metres(CORNER_DEPTH));
            vertex.set(center.x, center.y, 0).mulAdd(ray, seat);
            float atFirst = support(tile, first, d), atSecond = support(tile, second, e);
            vertex.z = (atFirst + atSecond) / 2;
            if (atFirst != atSecond) {
                // Between grounds of different heights the slope joining them runs through the corner: the rock meets
                // it there, rather than standing out of it at their mean height.
                float slope = Float.NEGATIVE_INFINITY;
                for (var ground : List.of(first, second)) {
                    var surface = surfaces.computeIfAbsent(ground.coords(), c -> new BoardSurface(scene, ground, lod));
                    List<BoardSurface.Face> faces;
                    synchronized (surface) { faces = surface.walls(scene, BoardGeometry.floor(scene), surfaces); }
                    slope = Math.max(slope, BoardSurface.sampleHeight(surface.foundation(), vertex.x, vertex.y,
                          Float.NEGATIVE_INFINITY));
                    slope = Math.max(slope, BoardSurface.sampleHeight(faces, vertex.x, vertex.y, Float.NEGATIVE_INFINITY));
                }
                if (Float.isFinite(slope)) {
                    vertex.z = Math.clamp(slope, Math.min(atFirst, atSecond), Math.max(atFirst, atSecond));
                }
            }
            points.add(new Rim(vertex, -1, true, false, radius / seat));
            return null;
        }
        var span = joinsFirst ? first : second;
        var ground = joinsFirst ? second : first;
        var far = BoardGeometry.corner(tile.coords(), 0, joinsFirst ? Math.floorMod(2 - d, 6) : Math.floorMod(-d, 6));
        var line = new Vector3(vertex).sub(far).nor();
        var deep = new Vector3(line).scl(BoardRelief.metres(CORNER_DEPTH)).add(vertex);
        int across = joinsFirst ? (d + 2) % 6 : (d + 5) % 6;
        int towards = joinsFirst ? e : d;
        // A bank both spans rest on: the shared edge climbs or falls from the joint's mouth to its top. A bank
        // marking a cliff top towards either span is a wall both enter at the joint's height.
        if (BoardBridge.bank(tile, ground, towards) && BoardBridge.bank(span, ground, across)
              && !cliff(ground, towards) && !cliff(ground, across)) {
            float bed = BoardRoadGrade.ground(ground);
            boolean level = deck(tile) == bed && deck(span) == bed;
            vertex.z = bed * BoardGeometry.level() - (level ? 0 : BoardRelief.metres(.025f));
        } else {
            vertex.z = BoardBridge.edgeElevation(tile, span, joinsFirst ? d : e) * BoardGeometry.level();
        }
        deep.z = vertex.z;
        var shared = new Rim(vertex, -1, true, true);
        // The joint's line on into the ground divides the two spans there. Mouth points from the one nearest the corner:
        var mouth = mouths.get(joinsFirst ? e : d);
        var near = joinsFirst ? mouth : mouth.reversed();
        float sign = Math.signum(side(line, vertex, center));
        int beyond = 0;
        while (beyond < near.size() && sign * side(line, vertex, near.get(beyond).point()) <= 0) { beyond++; }
        points.add(shared);
        if (beyond == 0) {
            // The mouth stays on this side: a tongue fills the ground's rounded-off corner out to the line.
            return new Tongue(shared, deep, near.getFirst());
        }
        if (beyond == near.size()) { return null; }
        // A mouth reaching deep into the bank crosses the line: end it there, so the spans never overlap.
        var before = near.get(beyond - 1);
        var after = near.get(beyond);
        float from = side(line, vertex, before.point()), to = side(line, vertex, after.point());
        float t = from / (from - to);
        var cut = new Rim(new Vector3(before.point()).lerp(after.point(), t), after.exit(), false, true,
              before.landing() + (after.landing() - before.landing()) * t);
        mouth.removeAll(new ArrayList<>(near.subList(0, beyond)));
        if (joinsFirst) { mouth.addFirst(cut); } else { mouth.addLast(cut); }
        return null;
    }

    /**
     * Where a bank's cliff turns the corner between directions d and d + 1 onto a side without an exit, the rim follows
     * it to just inside that corner instead of cutting across; not where the bank's wide mouth already reaches past it.
     */
    private static void shoulder(BoardScene scene, BoardScene.Tile tile, int d, Vector3 center, List<List<Rim>> mouths,
          List<Rim> points) {
        int e = (d + 1) % 6, exits = BoardBridge.feature(tile).bridgeExits();
        int bank = (exits & (1 << e)) == 0 ? d : (exits & (1 << d)) == 0 ? e : -1;
        var ground = bank < 0 ? null : scene.tile(tile.coords().translated(bank));
        if (bank < 0 || !BoardBridge.abutment(tile, ground, bank) || !drops(scene, tile, bank == d ? e : d, bank)) { return; }
        var vertex = BoardGeometry.corner(tile.coords(), 0, Math.floorMod(1 - d, 6));
        var ray = new Vector3(vertex).sub(center);
        ray.z = 0;
        float sign = Math.signum(side(ray, center, BoardGeometry.center(ground.coords(), 0)));
        if (mouths.get(bank).stream().anyMatch(rim -> sign * side(ray, center, rim.point()) <= 0)) { return; }
        float inset = BoardRelief.metres(.7f + .6f * noise(vertex.x, vertex.y));
        var point = new Vector3(center).mulAdd(new Vector3(ray).nor(), ray.len() - inset);
        point.z = support(tile, ground, bank);
        points.add(new Rim(point, -1, false, false));
    }

    /**
     * Drop a joint mouth's corner ({@code first}: its first point, else its last) at or beyond the hex corner
     * {@code vertex} of the edge it shares, which runs from {@code far}; every span there drops it alike.
     */
    private static void overshoot(List<Rim> mouth, boolean first, Vector3 vertex, Vector3 far) {
        if (mouth.isEmpty()) { return; }
        var point = (first ? mouth.getFirst() : mouth.getLast()).point();
        float half = Vector2.dst(vertex.x, vertex.y, far.x, far.y) / 2;
        float mx = (vertex.x + far.x) / 2, my = (vertex.y + far.y) / 2;
        if (Vector2.dst(point.x, point.y, mx, my) >= half - BoardRelief.metres(.01f)) {
            if (first) { mouth.removeFirst(); } else { mouth.removeLast(); }
        }
    }

    /**
     * End a bank mouth ({@code first}: from its first point, else from its last) where it crosses the ray from the
     * centre along {@code ray} into the neighbouring direction's sector; the distance its crossing reaches, else 0.
     */
    private static float trim(List<Rim> mouth, boolean first, Vector3 ray, Vector3 center) {
        if (mouth.size() < 2) { return 0; }
        var near = first ? mouth : mouth.reversed();
        float sign = Math.signum(side(ray, center, near.getLast().point()));
        int beyond = 0;
        while (beyond < near.size() - 1 && sign * side(ray, center, near.get(beyond).point()) <= 0) { beyond++; }
        if (beyond == 0) { return 0; }
        var before = near.get(beyond - 1).point();
        var after = near.get(beyond).point();
        float from = side(ray, center, before), to = side(ray, center, after);
        var crossing = new Vector3(before).lerp(after, from / (from - to));
        mouth.removeAll(new ArrayList<>(near.subList(0, beyond)));
        return Vector2.dst(crossing.x, crossing.y, center.x, center.y);
    }

    /** Which side of the line through {@code origin} along {@code line} the point lies, in the plane. */
    static float side(Vector3 line, Vector3 origin, Vector3 point) {
        return line.x * (point.y - origin.y) - line.y * (point.x - origin.x);
    }

    /**
     * The height at which the span rests on ground: a bank's top, else its own deck in a taller cliff or a bank whose
     * side towards it the board marks a cliff top.
     */
    private static float support(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        if (!BoardBridge.bank(tile, next, direction) || cliff(next, direction)) {
            return deck(tile) * BoardGeometry.level();
        }
        float bed = BoardRoadGrade.ground(next);
        return bed * BoardGeometry.level() - (bed == deck(tile) ? 0 : BoardRelief.metres(.025f));
    }

    private static float mouthWidth(float x, float y, boolean bank) {
        return BoardRelief.metres(BoardBridge.NATURAL_HALF_WIDTH) * (.96f + .08f * noise(x, y)) * (bank ? 1.3f : 1);
    }

    /**
     * Where a level natural span supports a bank's rim, which is no longer an exposed rounded cliff there: across the
     * passage ({@code passage}) and, on a side whose corner the span closes ({@link #closes}), on along the whole edge.
     * {@code before} and {@code after}: the supported half-widths on the passage's clockwise and counter-clockwise sides.
     */
    record BankJoin(BoardBridge.Approach passage, float before, float after) {
        /** 1 where the span supports the rim at p, fading to 0 beside the passage. */
        float weight(Vector3 p) {
            float lateral = passage.lateral(p);
            return 1 - BoardRelief.smooth((Math.abs(lateral) / (lateral < 0 ? before : after) - .8f) / .2f);
        }
    }

    /** The span in {@code direction} of {@code bank} that supports its rim, or null. */
    static BankJoin bankApproach(BoardScene scene, BoardScene.Tile bank, int direction) {
        var span = scene.tile(bank.coords().translated(direction));
        // A fallen rock arch supports nothing.
        if (BoardBridge.standing(span) == null || !BoardBridge.bank(span, bank, (direction + 3) % 6)
              || BoardBridge.family(span) != BoardBridge.Family.NATURAL
              || span.elevation() + BoardBridge.feature(span).elevation() != BoardRoadGrade.ground(bank)) { return null; }
        var center = BoardGeometry.center(bank.coords(), 0);
        var along = BoardGeometry.center(span.coords(), 0).sub(center);
        float width = mouthWidth(center.x + along.x * .5f, center.y + along.y * .5f, true);
        along.nor();
        var passage = new BoardBridge.Approach(center.x, center.y, along.x, along.y, BoardGeometry.height(), width,
              BoardRoadGrade.ground(bank) * BoardGeometry.level(), false);
        // The bank's edge runs from the corner clockwise of the span (the span's corner between its directions two and
        // three on from this one) to the counter-clockwise corner (three and four on).
        float whole = BoardGeometry.width();
        return new BankJoin(passage, closes(scene, span, (direction + 2) % 6) ? whole : width,
              closes(scene, span, (direction + 3) % 6) ? whole : width);
    }

    /** Round the exposed outline through its control points; mouths keep their exact shared contacts. */
    private static Vector3 curve(List<Rim> outline, int index, float t, Vector3 center) {
        var a = outline.get(index).point();
        if (t == 0) { return new Vector3(a); }
        var b = outline.get((index + 1) % outline.size()).point();
        var before = outline.get(Math.floorMod(index - 1, outline.size())).point();
        var after = outline.get((index + 2) % outline.size()).point();
        float limit = a.dst(b) * .35f;
        var first = new Vector3(b).sub(before).scl(1f / 6).limit(limit).add(a);
        var second = new Vector3(a).sub(after).scl(1f / 6).limit(limit).add(b);
        float u = 1 - t;
        var point = new Vector3(a).scl(u * u * u).mulAdd(first, 3 * u * u * t)
              .mulAdd(second, 3 * u * t * t).mulAdd(b, t * t * t);
        double angle = Math.atan2(a.y - center.y, a.x - center.x);
        double sweep = Math.atan2(b.y - center.y, b.x - center.x) - angle;
        if (sweep < 0) { sweep += Math.PI * 2; }
        double radius = Math.hypot(point.x - center.x, point.y - center.y);
        return point.set(center.x + (float) (radius * Math.cos(angle + sweep * t)),
              center.y + (float) (radius * Math.sin(angle + sweep * t)), point.z);
    }

    private static float anchoring(BoardScene scene, BoardScene.Tile tile, Vector3 point) {
        float anchored = 0;
        for (int d = 0; d < 6; d++) {
            if (!BoardBridge.abutment(tile, scene.tile(tile.coords().translated(d)), d)) { continue; }
            float u = Math.clamp(BoardGeometry.share(tile.coords(), d, point.x, point.y), 0, 1);
            anchored = Math.max(anchored, u * u);
        }
        return anchored;
    }

    /** A conservative overlap of the whole mouth and underside with the finished cliff triangles. */
    private static float bankReach(BoardScene scene, BoardSurface ground, Map<Coords, BoardSurface> surfaces,
          Vector3 center, Vector3 along, float width, float space, int direction) {
        List<BoardSurface.Face> walls;
        // Within the chunk walls are already complete; an out-of-chunk bank can be borrowed by several spans.
        synchronized (ground) { walls = ground.walls(scene, BoardGeometry.floor(scene), surfaces); }
        float reach = 0;
        var opening = List.of(new Vector3(-width, center.z - space, 0), new Vector3(width, center.z - space, 0),
              new Vector3(width, center.z, 0), new Vector3(-width, center.z, 0));
        for (var face : walls) {
            // Around a scalloped corner the contact can belong to the bank's adjoining edge as well.
            int facing = Math.floorMod(BoardGeometry.edgeDirection(face.landEdge()) - direction - 3, 6);
            if (face.landEdge() < 0 || (facing != 0 && facing != 1 && facing != 5)) { continue; }
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            if (normal.dot(along) >= 0) { continue; }
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
            var projected = new ArrayList<Vector3>(3);
            for (var p : List.of(face.a(), face.b(), face.c())) {
                float x = p.x - center.x, y = p.y - center.y;
                float lateral = y * along.x - x * along.y;
                low = Math.min(low, lateral); high = Math.max(high, lateral);
                bottom = Math.min(bottom, p.z); top = Math.max(top, p.z);
                projected.add(new Vector3(lateral, p.z, x * along.x + y * along.y));
            }
            if (low <= width && high >= -width && bottom <= center.z && top >= center.z - space) {
                // A coarse wall triangle may reach far around the bank outside the opening. Only its clipped
                // contact depth belongs here; its remote corner can push the mouth through the far cliff wall.
                for (var point : BoardTacticalGeometry.clip(projected, opening)) { reach = Math.max(reach, point.z); }
            }
        }
        return reach + BoardRelief.metres(1.25f);
    }

    /** Preserve the last whole level below the deck; never root a support in an occupied lower hex. */
    private static float clearance(BoardScene.Tile tile, float deck) {
        float below = BoardGeometry.groundZ(tile);
        for (var feature : tile.features()) {
            if (feature.asset().equals("bridge")) { continue; }
            float top = (tile.elevation() + feature.elevation() + feature.height()) * BoardGeometry.level();
            if (top < deck) { below = Math.max(below, top); }
        }
        return Math.max(BoardRelief.metres(.05f), Math.min(BoardGeometry.level(), deck - below));
    }

    private static float noise(float x, float y) {
        float metre = BoardRelief.metres(1);
        return BoardRelief.noise(x / (metre * 2.3f), y / (metre * 2.3f));
    }

}
