/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;

/**
 * Concrete ramp blocks and bank landings, with authored entrance wedges seated on the road or actual bank, and the
 * concrete piers under the joints between a deck's hexes. {@code easedExits}: the solid ramps whose grade eases in and
 * out (Enhanced, {@link BoardBridgeSlope#ease}); Classic's stay straight.
 */
record BoardBridgeFooting(BoardBridge.Shape shape, List<Float> lengths, int bareExits, int solidExits, int easedExits,
      Map<Integer, BoardRoad> bankCurves) {
    static final float APRON_METRES = 7;
    /**
     * A built deck landing level on a natural bank that is no cliff ({@link #abutted}) stands on a concrete abutment set
     * into the bank, whatever the drop below it. False: an abutment only where the bank's corners fall away and no cliff
     * drops below the deck, the bank coming out under the deck's end elsewhere (BoardRelief#push).
     */
    static final boolean BRIDGE_CONCRETE_ABUTMENT_ON_SLOPES = false;
    /** The kit was authored for a 1.5px shoulder; only its outward reach follows the widened deck. */
    private static final float TERMINAL_WIDTH_SCALE = BoardBridge.SHOULDER / 1.5f;

    private record Block(BoardShape shape, Vector3 size) { }

    /** Publish the complete immutable kit together; terrain workers retain one snapshot while building. */
    private static final BoardKit<List<Block>> KIT = new BoardKit<>(BoardBridgeFooting::load);

    private static List<Block> load() {
        var shapes = BoardShape.loadKit("bridges/bridge-terminal");
        for (String name : shapes.keySet()) {
            if (!name.matches("bridge-terminal-lod[01]")) {
                throw new IllegalArgumentException("Unexpected bridge terminal mesh: " + name);
            }
        }
        var blocks = MeshLod.load("bridge-terminal", 2, name -> {
            var shape = shapes.get(name);
            if (shape == null) { return null; }
            var bounds = new BoundingBox().inf();
            shape.polygons().forEach(face -> { for (var p : face.points()) { bounds.ext(p); } });
            var size = bounds.getDimensions(new Vector3());
            if (!bounds.min.isZero(.001f) || size.x <= 0 || size.y <= 0) {
                throw new IllegalArgumentException("Bridge terminal must start at the inner rail/deck origin: " + name);
            }
            return new Block(shape, size);
        });
        if (!blocks.getFirst().size().epsilonEquals(blocks.getLast().size(), .001f)) {
            throw new IllegalArgumentException("Bridge terminal LODs must retain the same bank footprint and height");
        }
        return blocks;
    }

    /**
     * A pier kit at two LODs: a cap that follows the underside of what it carries, a shaft stretched in Z only (kit Z 0
     * to 1) and a footing buried in the floor. Kit frame: the origin on the joint, X along the shared edge (across the
     * deck or beam), Y toward the neighbour; Z per part, as each joint places it. Each part is kept as its half on the
     * near side of the joint (Y at most 0), cut once here and closed by its section on the joint ({@link #section}):
     * each hex of a joint builds its own half.
     * The soffit (the cap's bottom), the footing's lip (its top) and its half extents are the parts' own bounds.
     */
    private record Pier(List<List<Vector3[]>> cap, List<List<Vector3[]>> shaft, List<List<Vector3[]>> footing,
          float soffit, float lip, float footX, float footY) { }

    /** The bridge's pier kit ({@code bridges/bridge-pier.glb}) and the maglev's slim one ({@code bridges/maglev-pier.glb}). */
    private static final BoardKit<Pier> PIER = new BoardKit<>(() -> loadPier("bridge-pier")),
          MAGLEV_PIER = new BoardKit<>(() -> loadPier("maglev-pier"));
    /** A pier footing's corners in units of its half extents. */
    private static final float[][] CORNERS = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };

    private static Pier loadPier(String kit) {
        var shapes = BoardShape.loadKit("bridges/" + kit);
        for (String name : shapes.keySet()) {
            if (!name.matches(kit + "-(cap|shaft|footing)-lod[01]")) {
                throw new IllegalArgumentException("Unexpected pier mesh: " + name);
            }
        }
        Map<String, BoundingBox> bounds = new HashMap<>();
        var cap = halves(shapes, kit + "-cap", bounds);
        var shaft = halves(shapes, kit + "-shaft", bounds);
        var footing = halves(shapes, kit + "-footing", bounds);
        var length = bounds.get(kit + "-shaft");
        if (Math.abs(length.min.z) > .001f || Math.abs(length.max.z - 1) > .001f) {
            throw new IllegalArgumentException("A pier shaft must span Z 0 to 1: " + kit);
        }
        var foot = bounds.get(kit + "-footing");
        return new Pier(cap, shaft, footing, bounds.get(kit + "-cap").min.z, foot.max.z, foot.max.x, foot.max.y);
    }

    /** One part's near halves by LOD; records its LOD0 bounds. */
    private static List<List<Vector3[]>> halves(Map<String, BoardShape> shapes, String part, Map<String, BoundingBox> bounds) {
        return MeshLod.load(part, 2, name -> {
            var shape = shapes.get(name);
            if (shape == null) { return null; }
            var box = new BoundingBox().inf();
            shape.polygons().forEach(face -> { for (var p : face.points()) { box.ext(p); } });
            // Both hexes of a joint place this same half: the part must be centred on the joint.
            if (Math.abs(box.min.x + box.max.x) > .001f || Math.abs(box.min.y + box.max.y) > .001f) {
                throw new IllegalArgumentException("Pier parts must be centred on the joint: " + name);
            }
            bounds.putIfAbsent(part, box);
            var near = new BoardSurface.Face(new Vector3(-100, 0, 0), new Vector3(0, -100, 0), new Vector3(100, 0, 0),
                  BoardSurface.Finish.TOP, -1);
            var clipper = new BoardTacticalGeometry.Clipper();
            List<Vector3[]> result = new ArrayList<>();
            for (var face : shape.polygons()) {
                var p = face.points();
                clipper.prepare(new BoardTacticalGeometry.Triangle(p[0], p[1], p[2], -1));
                // A flat face of zero height keeps each vertex's own Z; the cut lands exactly on the joint.
                clipper.displace(near, t -> result.add(new Vector3[] { seam(t.a()), seam(t.b()), seam(t.c()) }));
            }
            section(result);
            return List.copyOf(result);
        });
    }

    /**
     * Closes a half with its section on the joint, facing the neighbour, so it reads as solid from every side, as in
     * the editor's side view, which draws one hex. The parts are convex: the cut's points ring its centroid. In a
     * whole pier the two sections lie back to back inside it.
     */
    private static void section(List<Vector3[]> half) {
        List<Vector3> ring = new ArrayList<>();
        for (var triangle : half) {
            for (var p : triangle) {
                if (p.y == 0 && ring.stream().noneMatch(q -> q.dst2(p) < 1e-8f)) { ring.add(p); }
            }
        }
        if (ring.size() < 3) { return; }
        var centre = new Vector3();
        ring.forEach(centre::add);
        centre.scl(1f / ring.size());
        ring.sort(java.util.Comparator.comparingDouble(p -> Math.atan2(p.z - centre.z, p.x - centre.x)));
        for (int i = 0; i < ring.size(); i++) {
            var a = ring.get((i + 1) % ring.size());
            var b = ring.get(i);
            // Clockwise in X-Z, which faces +Y; a part that is not convex would fold its section over.
            if ((a.z - centre.z) * (b.x - centre.x) - (a.x - centre.x) * (b.z - centre.z) <= 0) {
                throw new IllegalArgumentException("Pier parts must be convex");
            }
            half.add(new Vector3[] { new Vector3(centre), new Vector3(a), new Vector3(b) });
        }
    }

    private static Vector3 seam(Vector3 point) {
        if (Math.abs(point.y) < 1e-4f) { point.y = 0; }
        return point;
    }

    static void reload() { KIT.reload(); PIER.reload(); MAGLEV_PIER.reload(); }

    static float terminalLength() { return KIT.get().getFirst().size().y; }

    /** The authored entrance block's outward reach from the carriageway edge, in model pixels. */
    static float terminalWidth() { return KIT.get().getFirst().size().x * TERMINAL_WIDTH_SCALE; }

    /** How far a banked span's terminal block rises above the deck surface. */
    static float terminalHeight() { return KIT.get().getFirst().size().z; }

    /**
     * Whether a pier stands under the joint of the tile's deck toward {@code d}, the edge it shares with the next hex of
     * its deck: the decks are {@link BoardBridge#connected}, the Pillars toggle ({@link HexAppearance#PILLARS}) is on in
     * either hex, no drawn ground road crosses that edge, and the deck is at least one whole level above both floors. The
     * toggle is a built type ({@link HexAppearance#bridgeBuilt}): a rock arch has none. Never at a hex
     * centre, a bank, ramp or road landing, an open or off-board end: a one-hex bridge rests on its landings. Scene data
     * only, and the same from both hexes.
     */
    static boolean pier(BoardScene scene, BoardScene.Tile tile, int d) {
        var next = scene.tile(tile.coords().translated(d));
        if (!BoardBridge.connected(tile, next, d)
              || !HexAppearance.pillars(tile.appearance()) && !HexAppearance.pillars(next.appearance())) { return false; }
        float top = BoardBridge.edgeElevation(tile, next, d) * BoardGeometry.level();
        if (BoardBridge.guideway(tile) || BoardBridge.guideway(next)) {
            if (structure(tile) || structure(next)) { return false; }
            var route = BoardBridge.guidewayRoute(tile);
            var joint = route == null ? null : BoardRail.joint(scene, tile, route, d);
            if (joint == null) { return false; }
            top = joint.underside();
        }
        return room(tile, next, d, top);
    }

    /**
     * Whether a pier fits under the joint of {@code tile} toward {@code d} that carries a deck or beam at height
     * {@code top} on the joint: neither hex is a chasm (no floor to stand on), no road or rail crosses that edge beneath,
     * and {@code top} is at least one whole level above both floors. The one room rule of bridge and maglev piers.
     */
    private static boolean room(BoardScene.Tile tile, BoardScene.Tile next, int d, float top) {
        if (tile.ultraSublevel() || next.ultraSublevel()) { return false; }
        // A road or rail passing beneath keeps its carriageway.
        if (BoardRoad.carriageway(tile) && (tile.wayExits() & 1 << d) != 0
              || BoardRoad.carriageway(next) && (next.wayExits() & 1 << (d + 3) % 6) != 0) { return false; }
        float room = top - Math.max(BoardGeometry.groundZ(tile), BoardGeometry.groundZ(next));
        return room >= BoardGeometry.level() - .001f * BoardGeometry.hexScale();
    }

    /** Whether a structure stands in the tile: a building, an industrial plant or a fuel tank (its captured model). */
    private static boolean structure(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> feature.decoration() == null
              && (feature.kind() == BoardScene.FeatureKind.BUILDING || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL
                    || feature.asset().startsWith("buildings/")));
    }

    /**
     * {@code shape}, the tile's deck, with the halves of its joints' piers ({@link #pier}) that stand in this hex, cut at
     * the shared edge; the neighbour adds the other half from the same inputs, so the two meet exactly. {@code shape}
     * itself when the tile has no pier.
     */
    static BoardBridge.Shape withPiers(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck, BoardBridge.Shape shape,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        return withPiers(scene, tile, deck, shape, lod, surfaces, false, 0);
    }

    private static BoardBridge.Shape withPiers(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck,
          BoardBridge.Shape shape, TerrainLod lod, Map<Coords, BoardSurface> surfaces, boolean columns, int eased) {
        List<BoardBridge.Facet> faces = null;
        List<BoardSurface.Face> under = List.of();
        for (int d = 0; d < 6; d++) {
            if (!pier(scene, tile, d)) { continue; }
            if (faces == null) {
                faces = new ArrayList<>(shape.facets());
                // What the cap follows: the deck's grade above its deck plane.
                under = deck.sloped() ? BoardBridgeSlope.profile(tile, deck) : List.of();
            }
            var next = scene.tile(tile.coords().translated(d));
            boolean guideway = BoardBridge.guideway(tile) || BoardBridge.guideway(next);
            if (guideway) {
                var route = BoardBridge.guidewayRoute(tile);
                var joint = route == null ? null : BoardRail.joint(scene, tile, route, d);
                if (joint == null) { continue; }
                var frame = joint(tile, d);
                pier(faces, MAGLEV_PIER.get(), scene, tile, d, joint.underside(),
                      p -> joint.underside() + joint.grade() * new Vector3(p).sub(frame[0]).dot(frame[2]), lod, surfaces, columns);
                continue;
            }
            // The underside at the joint, the same from both hexes.
            float atJoint = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, next, d)) - BoardBridge.SLAB * BoardGeometry.hexScale();
            var grade = under;
            float rise = BoardBridgeSlope.easedRise(deck, eased);
            pier(faces, PIER.get(), scene, tile, d, atJoint, p -> underside(tile, grade, rise, p), lod, surfaces,
                  columns);
        }
        return faces == null ? shape : BoardBridge.shape(shape.surface(), shape.level(), faces);
    }

    /**
     * The outline of the footing of the pier under the tile's joint {@code d} ({@link #pier}), world XY: two triangles;
     * a maglev's ({@code maglev}) of the slim kit.
     */
    static List<Vector3> pierFootprint(BoardScene.Tile tile, int d, boolean maglev) {
        var kit = (maglev ? MAGLEV_PIER : PIER).get();
        var frame = joint(tile, d);
        var corners = new Vector3[CORNERS.length];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = local(frame, CORNERS[i][0] * kit.footX(), CORNERS[i][1] * kit.footY());
        }
        return List.of(corners[0], corners[1], corners[2], new Vector3(corners[0]), new Vector3(corners[2]), corners[3]);
    }

    /** The drawn underside a pier's cap meets above a point of its joint (world XY), as a world height. */
    @FunctionalInterface
    private interface Underside { float at(Vector3 point); }

    /**
     * Appends the half of the pier of {@code kit} under joint {@code d} that lies in this tile, in its kit's three
     * frames: the cap's Z 0 is the drawn underside of the deck or beam above each of its points ({@code underside}, which
     * is {@code atJoint} on the joint), so the cap follows its grade; the footing's Z 0 is the lowest drawn floor under
     * the footing (a 5 x 5 grid over it, the joint and its corners among them), in either hex (no loose rock; under ice
     * the bed), lowered where needed to keep a 2 px shaft; under a liquid it sinks out of sight, since an uneven bed
     * would break its lip into loose slivers; the shaft spans between them, overlapping each by half a px. The kit
     * scales with the hex, never with the level height. The one placement of bridge and maglev piers.
     */
    private static void pier(List<BoardBridge.Facet> faces, Pier kit, BoardScene scene, BoardScene.Tile tile, int d,
          float atJoint, Underside underside, TerrainLod lod, Map<Coords, BoardSurface> surfaces, boolean columns) {
        float s = BoardGeometry.hexScale();
        var next = scene.tile(tile.coords().translated(d));
        var frame = joint(tile, d);
        var near = ground(scene, tile, lod, surfaces, columns).foundation();
        var far = ground(scene, next, lod, surfaces, columns).foundation();
        float floor = atJoint - (kit.lip() - kit.soffit() + 2) * s;
        for (int i = 0; i < 25; i++) {
            var p = local(frame, (i % 5 / 2f - 1) * kit.footX(), (i / 5 / 2f - 1) * kit.footY());
            var owner = BoardGeometry.tile(scene, p.x, p.y);
            if (owner == null) { continue; }
            floor = Math.min(floor, BoardSurface.sampleHeight(owner.coords().equals(next.coords()) ? far : near, p.x, p.y,
                  BoardGeometry.groundZ(owner)));
        }
        // Its top half a px under the lowest floor: never coplanar with a flat bed.
        if (tile.liquid().present() || next.liquid().present()) { floor -= (kit.lip() + .5f) * s; }
        int level = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 0 : 1;
        float bottom = floor + (kit.lip() - .5f) * s, top = (kit.soffit() + .5f) * s;
        var parts = List.of(kit.cap().get(level), kit.shaft().get(level), kit.footing().get(level));
        for (int part = 0; part < parts.size(); part++) {
            for (var triangle : parts.get(part)) {
                var points = new Vector3[3];
                for (int i = 0; i < 3; i++) {
                    var k = triangle[i];
                    var p = local(frame, k.x, k.y);
                    if (part == 2) {
                        p.z = floor + k.z * s;
                    } else {
                        // On the joint the deck's or beam's grade is the same from both hexes.
                        float deckUnder = underside.at(p);
                        p.z = part == 0 ? deckUnder + k.z * s : MathUtils.lerp(bottom, deckUnder + top, k.z);
                    }
                    points[i] = p;
                }
                BoardBridge.triangle(faces, points[0], points[1], points[2], BoardBridge.Part.PIER);
            }
        }
    }

    /**
     * The drawn underside of the tile's own deck above {@code p}: its slab on its grade ({@code under}, relative to the
     * deck plane, eased by {@code rise}, see {@link BoardBridgeSlope#eased}).
     */
    private static float underside(BoardScene.Tile tile, List<BoardSurface.Face> under, float rise, Vector3 p) {
        return BoardBridge.deckZ(tile) + BoardBridgeSlope.eased(BoardSurface.sampleHeight(under, p.x, p.y, 0), rise)
              - BoardBridge.SLAB * BoardGeometry.hexScale();
    }

    /**
     * The tile's joint toward {@code d}: the shared edge's midpoint, the unit vector along the edge (kit X) and the one
     * toward the neighbour (kit Y). The edge itself, not the centres' line, orients the pier on the slightly non-regular
     * lattice; both hexes compute the same midpoint and opposite axes.
     */
    private static Vector3[] joint(BoardScene.Tile tile, int d) {
        int edge = Math.floorMod(1 - d, 6);
        var a = BoardGeometry.corner(tile.coords(), 0, edge);
        var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
        var across = new Vector3(a).sub(b).nor();
        return new Vector3[] { a.add(b).scl(.5f), across, new Vector3(-across.y, across.x, 0) };
    }

    /** A kit point (model px) of a joint, in world XY; its Z is the caller's. */
    private static Vector3 local(Vector3[] frame, float x, float y) {
        float s = BoardGeometry.hexScale();
        return new Vector3(frame[0]).mulAdd(frame[1], x * s).mulAdd(frame[2], y * s);
    }

    /** The footing as the deck road's paint sees it: a bare bank's apron only on the road's own sides. */
    BoardBridgeFooting paint(BoardBridge.Deck deck) {
        int bare = bareExits & deck.roadExits();
        return bare == bareExits ? this
              : new BoardBridgeFooting(shape, lengths, bare, solidExits, easedExits, bankCurves);
    }

    /** Paint extends onto the existing bank after the structural footing has ended. */
    BoardRoad road(BoardBridge.Deck deck, Coords coords) {
        var reaches = new ArrayList<>(lengths);
        for (int d = 0; d < 6; d++) {
            if ((bareExits & (1 << d)) != 0) { reaches.set(d, reaches.get(d) + apronLength()); }
            else if (bankCurves.containsKey(d)) { reaches.set(d, reaches.get(d) + BoardBridge.SHOULDER); }
        }
        return deck.road(coords).extended(reaches, bareExits).followBanks(bankCurves);
    }

    static float apronLength() { return BoardRelief.metres(APRON_METRES) / BoardGeometry.hexScale(); }

    /** The built deck's complete support, shared by its drawing and neighboring rail or object placement. */
    BoardBridge.Shape withDeck(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        return withDeck(scene, tile, deck, lod, surfaces, false);
    }

    BoardBridge.Shape withDeck(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces, boolean columns) {
        var support = BoardBridge.guideway(tile) ? BoardRail.guideway(scene, tile) : BoardBridgeSlope.build(tile, deck, this);
        return withPiers(scene, tile, deck, support, lod, surfaces, columns, easedExits);
    }

    static BoardBridgeFooting build(BoardScene scene, BoardScene.Tile tile, TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        return build(scene, tile, lod, surfaces, false);
    }

    /** Every borrowed bank uses the same terrain mode as the chunk, including out-of-chunk landings and piers. */
    private static BoardSurface ground(BoardScene scene, BoardScene.Tile tile, TerrainLod lod,
          Map<Coords, BoardSurface> surfaces, boolean columns) {
        return surfaces.computeIfAbsent(tile.coords(), c -> columns
              ? BoardSurface.columns(scene, tile, lod) : new BoardSurface(scene, tile, lod));
    }

    static BoardBridgeFooting build(BoardScene scene, BoardScene.Tile tile, TerrainLod lod,
          Map<Coords, BoardSurface> surfaces, boolean columns) {
        float level = tile.elevation() + BoardBridge.feature(tile).elevation();
        if (BoardBridge.guideway(tile)) {
            return new BoardBridgeFooting(BoardBridge.shape(BoardScene.Surface.CONCRETE, level, List.of()),
                  List.of(0f, 0f, 0f, 0f, 0f, 0f), 0, 0, 0, Map.of());
        }
        float scale = BoardGeometry.hexScale();
        var block = KIT.get().get(lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 0 : 1);
        var center = BoardGeometry.center(tile.coords(), 0);
        center.z = BoardBridge.deckZ(level);
        var faces = new ArrayList<BoardBridge.Facet>();
        var lengths = new ArrayList<Float>();
        Map<Integer, BoardRoad> bankCurves = new HashMap<>();
        int bareExits = 0, solidExits = 0;
        for (int d = 0; d < 6; d++) {
            float lane = lane(tile, d), width = lane + BoardBridge.SHOULDER * scale;
            var next = scene.tile(tile.coords().translated(d));
            if (!BoardBridge.bank(tile, next, d)) { lengths.add(0f); continue; }
            var bank = ground(scene, next, lod, surfaces, columns);
            var ground = bank.foundation();
            var along = BoardGeometry.center(next.coords(), level).sub(BoardGeometry.center(tile.coords(), level));
            float half = along.len() / 2;
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            var gate = new Vector3(center).mulAdd(along, half);
            gate.z = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, next, d));
            boolean bare = !BoardBridge.road(tile, next, d);
            int firstFace = faces.size();
            int exits = next.roadExits() & 63;
            BoardRoad curve = !bare && Integer.bitCount(exits) == 2 && (exits & (exits >> 3)) == 0
                  ? BoardRoad.of(scene, next) : null;
            if (curve != null) { bankCurves.put(d, curve); }
            // A bare bank gets a loose apron (GpuRoads.deckPatches), never over pavement.
            if (bare && next.surface() != BoardScene.Surface.CONCRETE) { bareExits |= 1 << d; }
            if (BoardRoadGrade.ground(next) < level) {
                solidExits |= 1 << d;
                lengths.add((half * .5f + block.size().y * scale) / scale);
                var below = ground(scene, tile, lod, surfaces, columns);
                ramp(faces, scene, tile, next, d, center, gate, along, across, half, block, ground, below.groundFaces(),
                      curve, !columns);
                continue;
            }
            float reach = BoardRelief.metres(1);
            boolean firm = false;
            // The widened terminal blocks also need firm bank underneath. Loose rocks are not foundations.
            for (; reach < half * .9f; reach += BoardRelief.metres(.25f)) {
                firm = true;
                for (int i = -4; i <= 4; i++) {
                    var p = new Vector3(gate).mulAdd(along, reach)
                          .mulAdd(across, (lane + block.size().x * TERMINAL_WIDTH_SCALE * scale) * i / 4);
                    firm &= (bank.columns() || bank.relief.clearance(p.x, p.y) >= BoardRelief.metres(.5f))
                          && Float.isFinite(BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN));
                }
                if (firm) { break; }
            }
            int edge = Math.floorMod(1 - d, 6);
            var corner = BoardGeometry.corner(tile.coords(), level, edge);
            var edgeVector = BoardGeometry.corner(tile.coords(), level, edge + 1).sub(corner);
            var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
            float[] offsets = { -width, -lane, lane, width };
            Vector3[] start = new Vector3[4], end = new Vector3[4];
            for (int i = 0; i < offsets.length; i++) {
                start[i] = new Vector3(gate).mulAdd(across, offsets[i]);
                // The board lattice is slightly non-regular: use the GLB's real clipping edge at the join.
                start[i].mulAdd(along, new Vector3(corner).sub(start[i]).dot(normal) / along.dot(normal));
            }
            boolean ramp = !bare && BoardRoadGrade.ground(next) != level;
            // A level road landing on a natural bank that falls away below its edge stands on an abutment (Enhanced). Set
            // into a bank that is no cliff (#abutted) wherever the ground falls under the deck's end; where that bank never
            // firms up under the terminal blocks, as on a neck between cliffs, the abutment carries them. Otherwise
            // (!BRIDGE_CONCRETE_ABUTMENT_ON_SLOPES) only where the bank's corners fall away: over a cliff's drop the cliff
            // itself comes out under the deck's end (BoardRelief#ends).
            float underside = gate.z - BoardBridge.SLAB * scale;
            boolean seated = !columns && abutted(tile, next, d) && falls(List.of(ground,
                  ground(scene, tile, lod, surfaces, columns).foundation()), underside, along, -BoardRelief.metres(.5f),
                  start[0], start[1], new Vector3(start[1]).lerp(start[2], .5f), start[2], start[3]);
            boolean abutment = seated || !BRIDGE_CONCRETE_ABUTMENT_ON_SLOPES && !bare && !ramp && !columns
                  && gate.z - BoardGeometry.groundZ(tile) <= 2.5f * BoardGeometry.level()
                  && next.surface() != BoardScene.Surface.CONCRETE
                  && falls(List.of(ground), underside, along, BoardRelief.metres(.5f), start[0], start[3]);
            if (seated && !firm) { reach = BoardRelief.metres(1); }
            float length = block.size().y * scale;
            // Each rail and its terminal block stop short of the bank road's carriageway beyond the deck's lane: a
            // junction's other arms and corners. A bent bank road has none, and its frame is not this straight one.
            float[] stops = { reach, reach };
            if (!bare && curve == null) {
                var carriageway = BoardRoad.way(scene, next).footprint(0);
                carriageway.transform(new AffineTransform(scale, 0, 0, scale, BoardGeometry.centerX(next.coords()),
                      BoardGeometry.centerY(next.coords())));
                for (int i = 0; i < 2; i++) {
                    float clear = clear(carriageway, gate, along, across, lane, lane + terminalWidth() * scale,
                          reach + length, 2 * i - 1);
                    stops[i] = MathUtils.clamp(clear - length, 0, reach);
                }
            }
            boolean concreteGrade = !bare && bank.ramps != 0 && next.surface() == BoardScene.Surface.CONCRETE;
            reach = Math.max(stops[0], stops[1]) + length;
            lengths.add(reach / scale);
            // A ramp's road climbs over the bank's inset half (BoardRoadGrade#BRIDGE_START), then runs level.
            float climb = ramp ? Math.min(1, (1 - BoardRoadGrade.BRIDGE_START) * half / reach) : 1;
            for (int i = 0; i < offsets.length; i++) {
                end[i] = new Vector3(gate).mulAdd(along, reach).mulAdd(across, offsets[i]);
                end[i].z = BoardSurface.sampleHeight(ground, end[i].x, end[i].y, BoardGeometry.groundZ(next))
                      + GpuRoads.SURFACE_LIFT * scale;
            }
            float bottom = Float.NaN;
            if (ramp || abutment) {
                // One set into the bank stands in the lowest ground round the deck's end: the bank's and that beside it.
                List<BoardScene.Tile> under = new ArrayList<>(List.of(tile));
                if (seated) {
                    under.add(next);
                    for (int turn : new int[] { 1, 5 }) {
                        var beside = scene.tile(tile.coords().translated((d + turn) % 6));
                        if (beside != null) { under.add(beside); }
                    }
                }
                bottom = (float) under.stream().flatMap(hex -> ground(scene, hex, lod, surfaces, columns).groundFaces().stream())
                      .mapToDouble(f -> Math.min(f.a().z, Math.min(f.b().z, f.c().z))).min().orElse(BoardGeometry.groundZ(tile))
                      - BoardRelief.metres(.05f);
            }
            if (abutment) {
                // From the slab down past the bank over the whole landing, its face a hair out under the deck: the bank
                // stands on the edge behind it and flush beside it (BoardRelief#room), and the road's own fill stays inside.
                var out = new Vector3(along).scl(-BoardRelief.metres(.1f));
                support(faces, List.of(new Vector3(start[0]).add(out), end[0], end[3], new Vector3(start[3]).add(out)),
                      bottom, -BoardBridge.SLAB * scale);
            }
            if (ramp) {
                // The support ends at the bridge's inset, leaving the level centre of the span open below.
                var inner = new Vector3(center).lerp(corner, .5f);
                var left = new Vector3(start[0]);
                var right = new Vector3(start[3]);
                left.mulAdd(along, new Vector3(inner).sub(left).dot(normal) / along.dot(normal));
                right.mulAdd(along, new Vector3(inner).sub(right).dot(normal) / along.dot(normal));
                left.z = right.z = center.z;
                support(faces, List.of(left, start[0], start[3], right), bottom, -BoardBridge.SLAB * scale);
            }
            int steps = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 4 : 2;
            var runs = new TreeSet<Float>();
            for (int step = 1; step <= steps; step++) { runs.add(step / (float) steps); }
            // Keep the full-height rail across the void, then replace its end with a separate concrete block.
            float[] terminals = { stops[0] / reach, stops[1] / reach };
            for (float terminal : terminals) {
                if (terminal > 0) { runs.add(terminal); }
            }
            runs.add(climb);
            // A wing beside a ramp up onto its bank that runs the whole landing takes over its side's rail and block. On a
            // bent bank road it is built straight and bent with the rest, so it reads the bank where the bend puts it.
            int direction = d;
            UnaryOperator<Vector3> place = p -> curve == null ? p : bent(curve, tile.coords(), direction, p);
            // The bend takes the wing past the gate's corners, over the hexes beside the bank and the deck.
            var retained = ground;
            if (ramp && curve != null) {
                retained = new ArrayList<>(ground);
                for (int turn : new int[] { 1, 5 }) {
                    var beside = scene.tile(tile.coords().translated((d + turn) % 6));
                    if (beside != null) { retained.addAll(ground(scene, beside, lod, surfaces, columns).foundation()); }
                }
            }
            boolean[] walled = ramp ? wings(faces, retained, gate, along, across, lane, width,
                  (1 - BoardRoadGrade.BRIDGE_START) * half, reach, bottom,
                  (lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 1 : 2) * scale, place) : new boolean[2];
            var previous = start;
            for (float t : runs) {
                var row = row(start, end, t, !bare, climb);
                // A ramp or graded concrete junction already owns this road surface. A second slab would
                // intersect that grade. Natural level landings still bridge the gap to their receding bank.
                // Past the rails the terminal blocks cover the shoulders. The slab keeps to the lane there: a block
                // stays rigid while the slab bends, so outside a turn the slab's shoulder would outrun it.
                if (!ramp && !concreteGrade) {
                    int low = t <= terminals[0] ? 0 : 1, high = t <= terminals[1] ? 3 : 2;
                    prism(faces, previous[low], row[low], row[high], previous[high],
                          -BoardBridge.SLAB * scale, 0, BoardBridge.Part.TOP);
                }
                float rail = 2.5f * scale;
                if (t <= terminals[0] && !walled[0]) {
                    prism(faces, previous[0], row[0], row[1], previous[1], 0, rail, BoardBridge.Part.STRUCTURE);
                }
                if (t <= terminals[1] && !walled[1]) {
                    prism(faces, previous[2], row[2], row[3], previous[3], 0, rail, BoardBridge.Part.STRUCTURE);
                }
                // The support continues on the bank under the rails and terminal blocks: where the bank falls away
                // beside the ramp, concrete closes the space beneath them. Its sides stand a hair inside the slab's,
                // behind the faces the road's reserved ground casts on that line (BoardRoadGrade#region).
                if (ramp) {
                    var inset = new Vector3(across).scl(BoardRelief.metres(.05f));
                    side(faces, new Vector3(previous[0]).add(inset), new Vector3(row[0]).add(inset), bottom);
                    side(faces, new Vector3(row[3]).sub(inset), new Vector3(previous[3]).sub(inset), bottom);
                }
                previous = row;
            }
            bend(faces, firstFace, tile.coords(), d, curve, gate, along);
            for (int side : new int[] { -1, 1 }) {
                if (walled[(side + 1) / 2]) { continue; }
                terminal(faces, block.shape(), start, end, stops[(side + 1) / 2], reach, side, ground, !bare, climb,
                      curve, tile.coords(), d);
            }
        }
        return new BoardBridgeFooting(BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces),
              List.copyOf(lengths), bareExits, solidExits, columns ? 0 : solidExits, Map.copyOf(bankCurves));
    }

    /**
     * Concrete wing walls beside a ramp up onto its bank ({@code ground}): where the bank beside the road stands above
     * it, one straight wall on the rail's line retains that side, from {@code bottom} up, from the abutment at the gate
     * (never into the bridge hex, over the deck's own parapet) to the end of the landing's {@code reach}, or on to where
     * the bank still stands above the road, at most {@code run} past the gate. It is that side's parapet, taking over the
     * rail and its block (by side, the result). Its top runs straight from the gate to its far end, at least a rail's
     * height over the road at both, capping all of the bank it retains. Stations every {@code step}; {@code along} points
     * into the bank, and {@code place} puts a point of that straight frame where the bank road's bend takes it.
     */
    private static boolean[] wings(List<BoardBridge.Facet> faces, List<BoardSurface.Face> ground, Vector3 gate,
          Vector3 along, Vector3 across, float lane, float width, float run, float reach, float bottom, float step,
          UnaryOperator<Vector3> place) {
        float rail = 2.5f * BoardGeometry.hexScale();
        // From just behind the rail's inner face to just past the slab's edge, where the bank's cut face stands.
        float inner = lane + BoardRelief.metres(.03f), outer = width + BoardRelief.metres(.1f);
        boolean[] walled = new boolean[2];
        for (int side : new int[] { -1, 1 }) {
            // The stations {u, road, top} where the bank stands above the road.
            List<float[]> retained = new ArrayList<>();
            for (float u = 0; u <= run + step / 2; u += step) {
                var station = station(ground, place, gate, along, across, side, inner, outer, Math.min(u, run));
                if (!Float.isNaN(station[2])) { retained.add(station); }
            }
            if (retained.isEmpty()) { continue; }
            walled[(side + 1) / 2] = true;
            // A rail high over the road at both ends, and at the far end over the bank along its outer half; then the
            // lowest top at the gate whose straight run to there clears every retained station.
            float end = Math.max(reach, retained.getLast()[0]);
            float far = station(ground, place, gate, along, across, side, inner, outer, end)[1] + rail;
            float near = station(ground, place, gate, along, across, side, inner, outer, 0)[1] + rail;
            for (float[] station : retained) {
                if (station[0] >= end / 2) { far = Math.max(far, station[2]); }
            }
            for (float[] station : retained) {
                float share = station[0] / end;
                if (share < 1) { near = Math.max(near, (station[2] - far * share) / (1 - share)); }
            }
            List<float[]> wall = new ArrayList<>();
            for (float u = 0; u < end - step / 2; u += step) {
                wall.add(new float[] { u, Float.NaN, near + (far - near) * u / end });
            }
            wall.add(new float[] { end, Float.NaN, far });
            wing(faces, wall, gate, along, across, side, inner, outer, bottom);
        }
        return walled;
    }

    /**
     * A wing's station {u, road, top} at {@code u} past the gate on {@code side}: the road beside it and its top, on the
     * bank's edge with a cap no deeper than the cut, so it meets the road where the cut ends; the top is NaN where the
     * bank does not stand above the road. A bank reaching in over the wall's width, as beside a bent road whose cut runs
     * nearer it, is retained to its top there too.
     */
    private static float[] station(List<BoardSurface.Face> ground, UnaryOperator<Vector3> place, Vector3 gate,
          Vector3 along, Vector3 across, int side, float inner, float outer, float u) {
        var p = new Vector3(gate).mulAdd(along, u);
        var road = place.apply(new Vector3(p).mulAdd(across, side * (inner - BoardGeometry.hexScale())));
        var beyond = place.apply(new Vector3(p).mulAdd(across, side * (outer + BoardRelief.metres(.05f))));
        // At the gate the deck carries the road at the gate's height.
        float base = BoardSurface.sampleHeight(ground, road.x, road.y, u <= 0 ? gate.z : Float.NaN);
        float cut = BoardSurface.sampleHeight(ground, beyond.x, beyond.y, Float.NaN) - base, rise = cut;
        for (float share : new float[] { 0, .5f, 1 }) {
            var q = place.apply(new Vector3(p).mulAdd(across, side * (inner + (outer - inner) * share)));
            rise = Math.max(rise, BoardSurface.sampleHeight(ground, q.x, q.y, Float.NEGATIVE_INFINITY) - base);
        }
        float top = base + rise + BoardRelief.metres(.15f) * Math.min(1, rise / (2.5f * BoardGeometry.hexScale()));
        return new float[] { u, base, cut > 0 ? top : Float.NaN };
    }

    /** One wing through stations {u, road, top} on {@code side}, between {@code inner} and {@code outer}, closed. */
    private static void wing(List<BoardBridge.Facet> faces, List<float[]> wall, Vector3 gate, Vector3 along,
          Vector3 across, int side, float inner, float outer, float bottom) {
        if (wall.size() < 2) { return; }
        List<Vector3[]> sections = new ArrayList<>();
        for (float[] station : wall) {
            var p = new Vector3(gate).mulAdd(along, station[0]);
            var a = new Vector3(p).mulAdd(across, side * inner);
            var b = new Vector3(p).mulAdd(across, side * outer);
            sections.add(new Vector3[] { new Vector3(a.x, a.y, bottom), new Vector3(a.x, a.y, station[2]),
                  new Vector3(b.x, b.y, bottom), new Vector3(b.x, b.y, station[2]) });
        }
        var road = new Vector3(across).scl(-side);
        var bank = new Vector3(across).scl(side);
        for (int i = 1; i < sections.size(); i++) {
            var a = sections.get(i - 1);
            var b = sections.get(i);
            facing(faces, a[0], b[0], b[1], a[1], road);
            facing(faces, a[2], b[2], b[3], a[3], bank);
            facing(faces, a[1], b[1], b[3], a[3], Vector3.Z);
        }
        var first = sections.getFirst();
        var last = sections.getLast();
        facing(faces, first[0], first[2], first[3], first[1], new Vector3(along).scl(-1));
        facing(faces, last[0], last[2], last[3], last[1], along);
    }

    /** A structural quad wound to face {@code out}. */
    private static void facing(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d, Vector3 out) {
        var normal = new Vector3(c).sub(a).crs(new Vector3(d).sub(b));
        if (normal.dot(out) >= 0) { quad(faces, a, b, c, d, BoardBridge.Part.STRUCTURE); }
        else { quad(faces, d, c, b, a, BoardBridge.Part.STRUCTURE); }
    }

    /** Half the carriageway of the tile's deck toward {@code d}, world units: a road and rail corridor's or a road's. */
    private static float lane(BoardScene.Tile tile, int d) {
        return ((tile.deckCorridor().sides() & 1 << d) != 0 ? BoardRoad.CORRIDOR_HALF : BoardRoad.Kind.PAVED.halfWidth)
              * BoardGeometry.hexScale();
    }

    /** Half the width of the abutment under the tile's deck end toward {@code d} ({@link #abutted}), world units. */
    static float abutmentHalfWidth(BoardScene.Tile tile, int d) {
        return lane(tile, d) + BoardBridge.SHOULDER * BoardGeometry.hexScale();
    }

    /**
     * Whether the tile's built deck lands toward {@code d} level on its natural bank {@code next}, by its road, where the
     * bank falls to the deck's hex as no cliff: at most two levels above its surface and no cliff-top side
     * ({@link BoardRim#high}), and at most three down to its ground or bed ({@link BoardRelief#levels}); a bank a level
     * above water two deep is no cliff, a sea cliff is. The deck's end stands on a concrete abutment wherever the ground
     * falls away below it, and the bank's step keeps its place round it rather than coming out under the deck's end
     * (BoardRelief#push). A cliff stays natural rock. Never without {@link #BRIDGE_CONCRETE_ABUTMENT_ON_SLOPES}. Scene
     * data only.
     */
    static boolean abutted(BoardScene.Tile tile, BoardScene.Tile next, int d) {
        return BRIDGE_CONCRETE_ABUTMENT_ON_SLOPES
              && BoardBridge.bank(tile, next, d) && BoardBridge.road(tile, next, d) && !BoardBridge.guideway(tile)
              && BoardBridge.family(tile) != BoardBridge.Family.NATURAL && next.surface() != BoardScene.Surface.CONCRETE
              && BoardRoadGrade.ground(next) == tile.elevation() + BoardBridge.feature(tile).elevation()
              && !BoardRim.high(next, (d + 3) % 6, next.groundLevel() - tile.groundLevel())
              && BoardRelief.levels(next, tile) <= 3;
    }

    /**
     * Whether the highest of the {@code grounds} lies below {@code underside}, or is missing, at any of these points of
     * the gate's row moved {@code reach} along {@code along} (into the bank; negative: out under the deck's end).
     */
    private static boolean falls(List<List<BoardSurface.Face>> grounds, float underside, Vector3 along, float reach,
          Vector3... points) {
        for (var point : points) {
            var p = new Vector3(point).mulAdd(along, reach);
            float height = Float.NEGATIVE_INFINITY;
            for (var ground : grounds) { height = Math.max(height, BoardSurface.sampleHeight(ground, p.x, p.y, height)); }
            if (height < underside) { return true; }
        }
        return false;
    }

    /**
     * How far past the gate the landing's rail and terminal block on {@code side} (-1 or 1), between {@code lane} and
     * {@code outer} across it, run clear of {@code carriageway}; {@code limit} where they do throughout.
     */
    private static float clear(Area carriageway, Vector3 gate, Vector3 along, Vector3 across, float lane, float outer,
          float limit, int side) {
        float step = BoardRelief.metres(.25f);
        for (float at = 0; at < limit; at += step) {
            for (float lateral : new float[] { lane + step, (lane + outer) / 2, outer }) {
                var p = new Vector3(gate).mulAdd(along, at).mulAdd(across, side * lateral);
                if (carriageway.contains(p.x, p.y)) { return at; }
            }
        }
        return limit;
    }

    /**
     * Subdivide before bending so the slab and parapets follow the same bank road. The bend steepens a ramp's inner
     * side, so the carriageway is cut across its width too: a strip spanning it would split into long slivers that
     * alternate between the inner and outer slope and shade as streaks.
     */
    private static void bend(List<BoardBridge.Facet> faces, int first, Coords owner, int direction, BoardRoad road,
          Vector3 gate, Vector3 along) {
        if (road == null) { return; }
        float step = 2 * BoardGeometry.hexScale();
        var across = new Vector3(-along.y, along.x, 0);
        var source = new ArrayList<>(faces.subList(first, faces.size()));
        faces.subList(first, faces.size()).clear();
        var placed = new HashMap<Vector3, Vector3>();
        for (var face : source) {
            for (var strip : slice(List.of(face.a(), face.b(), face.c()), gate, along, step)) {
                for (var polygon : face.part() == BoardBridge.Part.TOP ? slice(strip, gate, across, step) : List.of(strip)) {
                    List<Vector3> bent = new ArrayList<>();
                    for (var point : polygon) {
                        bent.add(placed.computeIfAbsent(point, p -> bent(road, owner, direction, p)));
                    }
                    for (int i = 1; i + 1 < bent.size(); i++) {
                        BoardBridge.triangle(faces, bent.getFirst(), bent.get(i), bent.get(i + 1), face.part(), face.colour());
                    }
                }
            }
        }
    }

    /** A point of the deck's straight frame at its {@code direction} gate, where the bank road's bend takes it. */
    private static Vector3 bent(BoardRoad road, Coords owner, int direction, Vector3 p) {
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(owner, 0);
        var xy = road.bendFrom(owner, (direction + 3) % 6, (p.x - center.x) / scale, (p.y - center.y) / scale);
        return new Vector3(center.x + xy.x * scale, center.y + xy.y * scale, p.z);
    }

    /** A polygon's pieces between lines across {@code axis} (unit, XY) every {@code step} from {@code origin}. */
    static List<List<Vector3>> slice(List<Vector3> polygon, Vector3 origin, Vector3 axis, float step) {
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var point : polygon) {
            float distance = (point.x - origin.x) * axis.x + (point.y - origin.y) * axis.y;
            low = Math.min(low, distance);
            high = Math.max(high, distance);
        }
        int first = (int) Math.floor(low / step), last = Math.max(first, (int) Math.ceil(high / step) - 1);
        if (first == last) { return List.of(polygon); }
        List<List<Vector3>> pieces = new ArrayList<>();
        for (int k = first; k <= last; k++) {
            var a = new Vector3(origin).mulAdd(axis, k * step);
            var b = new Vector3(origin).mulAdd(axis, (k + 1) * step);
            pieces.add(BoardTacticalGeometry.clip(BoardTacticalGeometry.clip(polygon, a, new Vector3(a).add(axis.y, -axis.x, 0)),
                  b, new Vector3(b).add(-axis.y, axis.x, 0)));
        }
        return pieces;
    }

    /**
     * The bank half meets a support following the actual deck outline, including a turn above the ramp; an
     * {@code eased} ramp eases off the deck and onto the bank.
     */
    private static void ramp(List<BoardBridge.Facet> faces, BoardScene scene, BoardScene.Tile tile, BoardScene.Tile bank, int direction,
          Vector3 center, Vector3 gate, Vector3 along, Vector3 across, float half, Block block,
          List<BoardSurface.Face> ground, List<BoardSurface.Face> below, BoardRoad curve, boolean eased) {
        int firstFace = faces.size();
        float scale = BoardGeometry.hexScale();
        int edge = Math.floorMod(1 - direction, 6);
        var corner = BoardGeometry.corner(tile.coords(), 0, edge);
        var edgeVector = BoardGeometry.corner(tile.coords(), 0, edge + 1).sub(corner);
        var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
        float lane = lane(tile, direction), width = lane + BoardBridge.SHOULDER * scale;
        float[] offsets = { -width, -lane, lane, width };
        Vector3[] top = new Vector3[4], base = new Vector3[4], end = new Vector3[4];
        var inner = new Vector3(center).lerp(corner, .5f);
        var foot = new Vector3(inner).mulAdd(along, half);
        float length = block.size().y * scale;
        for (int i = 0; i < offsets.length; i++) {
            top[i] = new Vector3(gate).mulAdd(across, offsets[i]);
            top[i].mulAdd(along, new Vector3(inner).sub(top[i]).dot(normal) / along.dot(normal));
            top[i].z = center.z;
            base[i] = new Vector3(gate).mulAdd(across, offsets[i]);
            base[i].mulAdd(along, new Vector3(foot).sub(base[i]).dot(normal) / along.dot(normal));
            base[i].z = BoardGeometry.groundZ(bank) + GpuRoads.SURFACE_LIFT * scale;
            end[i] = new Vector3(base[i]).mulAdd(along, length);
        }
        float bottom = Math.min(BoardGeometry.groundZ(tile), BoardGeometry.groundZ(bank));
        for (var surface : List.of(ground, below)) {
            for (var face : surface) { bottom = Math.min(bottom, Math.min(face.a().z, Math.min(face.b().z, face.c().z))); }
        }
        bottom -= BoardRelief.metres(.05f);
        deckRamp(faces, tile, BoardBridge.deck(scene, tile), direction, bottom, eased);
        // Only the bank half is straight. The deck half must follow its generated curve and grade.
        top = row(top, base, .5f, true, 1);
        // Rows from the edge to the foot; eased, the grade meets the deck half's slope at the edge and levels out.
        int count = eased ? BoardBridgeSlope.EASED_ROWS : 1;
        List<Vector3[]> rows = new ArrayList<>(List.<Vector3[]>of(top));
        for (int k = 1; k < count; k++) {
            float t = k / (float) count;
            var row = row(top, base, t, true, 1);
            for (int i = 0; i < row.length; i++) {
                row[i].z = base[i].z + (top[i].z - base[i].z) * BoardBridgeSlope.ease(1 - t);
            }
            rows.add(row);
        }
        rows.add(base);
        // The slab and retaining sides share the same corners, with no terrain faces forming the support.
        List<Vector3> sides = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            for (int i = 0; i < 3; i++) {
                quad(faces, rows.get(k)[i], rows.get(k + 1)[i], rows.get(k + 1)[i + 1], rows.get(k)[i + 1],
                      i == 1 ? BoardBridge.Part.TOP : BoardBridge.Part.RIM);
            }
        }
        rows.forEach(row -> sides.add(row[0]));
        rows.reversed().forEach(row -> sides.add(row[3]));
        support(faces, sides, bottom, 0);
        quad(faces, new Vector3(top[3].x, top[3].y, bottom), new Vector3(base[3].x, base[3].y, bottom),
              new Vector3(base[0].x, base[0].y, bottom), new Vector3(top[0].x, top[0].y, bottom), BoardBridge.Part.STRUCTURE);
        // Only the bank half needs new rails; the bridge half retains its authored rails on the same grade.
        for (int k = 0; k < count; k++) {
            var upper = rows.get(k);
            var lower = rows.get(k + 1);
            prism(faces, upper[0], lower[0], lower[1], upper[1], 0, 2.5f * scale, BoardBridge.Part.STRUCTURE);
            prism(faces, upper[2], lower[2], lower[3], upper[3], 0, 2.5f * scale, BoardBridge.Part.STRUCTURE);
        }
        // The connected bank already owns the level road beneath the terminal blocks. Extending
        // another asphalt slab across it lets the bank's paint and tyre wear show through the slab.
        if (curve == null) {
            prism(faces, base[0], end[0], end[3], base[3], -BoardBridge.SLAB * scale, 0, BoardBridge.Part.TOP);
        }
        bend(faces, firstFace, tile.coords(), direction, curve, gate, along);
        for (int side : new int[] { -1, 1 }) {
            terminal(faces, block.shape(), base, end, 0, length, side, ground, true, 1,
                  curve, tile.coords(), direction);
        }
    }

    /** Extrude the solid part of the curved slab down to its foundation, on the deck's shared grade, {@code eased}. */
    private static void deckRamp(List<BoardBridge.Facet> faces, BoardScene.Tile tile, BoardBridge.Deck deck,
          int direction, float bottom, boolean eased) {
        var profile = BoardBridgeSlope.profile(tile, deck).stream()
              .filter(face -> face.landEdge() >= 0 && BoardGeometry.edgeDirection(face.landEdge()) == direction)
              // A wide mouth can consume an entire corner triangle; it has no plane to grade a wall against.
              .filter(face -> Math.abs((face.b().x - face.a().x) * (face.c().y - face.a().y)
                    - (face.b().y - face.a().y) * (face.c().x - face.a().x)) > .00001f).toList();
        float rise = BoardBridgeSlope.easedRise(deck, eased ? 1 << direction : 0);
        var region = new Area();
        for (var face : profile) {
            var path = new Path2D.Float();
            path.moveTo(face.a().x, face.a().y);
            path.lineTo(face.b().x, face.b().y);
            path.lineTo(face.c().x, face.c().y);
            path.closePath();
            region.add(new Area(path));
        }
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(tile.coords(), 0);
        var transform = new AffineTransform(scale, 0, 0, scale, center.x, center.y);
        var outline = BoardBridgeMesh.outline(tile, deck);
        var slab = outline.slab().createTransformedArea(transform);
        slab.intersect(region);
        var lane = outline.carriageway().createTransformedArea(transform);
        lane.intersect(region);
        var shoulder = new Area(slab);
        shoulder.subtract(lane);
        var top = new ArrayList<BoardBridge.Facet>();
        BoardBridgeMesh.cap(top, lane, 0, true, BoardBridge.Part.TOP, null);
        BoardBridgeMesh.cap(top, shoulder, 0, true, BoardBridge.Part.RIM, null);
        var clipper = new BoardTacticalGeometry.Clipper();
        float deckHeight = BoardBridge.deckZ(tile);
        for (var face : top) {
            for (var patch : profile) {
                clipper.prepare(new BoardTacticalGeometry.Triangle(face.a(), face.b(), face.c(), -1));
                clipper.displace(patch, displaced -> BoardBridgeSlope.ease(displaced, patch, rise, triangle ->
                      BoardBridge.triangle(faces,
                            new Vector3(triangle.a()).add(0, 0, deckHeight),
                            new Vector3(triangle.b()).add(0, 0, deckHeight),
                            new Vector3(triangle.c()).add(0, 0, deckHeight), face.part())));
            }
        }
        // Split the boundary on each grade patch before closing it; no straight wall cuts across the bend.
        for (var loop : BoardBridgeMesh.contours(slab)) {
            for (int i = 0; i < loop.size(); i++) {
                var a = loop.get(i);
                var b = loop.get((i + 1) % loop.size());
                var along = new Vector3(b).sub(a);
                for (var patch : profile) {
                    var edge = List.of(a, b);
                    // The boolean outline rounds points on a grade boundary. Use the plane's existing
                    // tolerance before exact clipping, which can otherwise discard half a collinear wall.
                    if (!Float.isFinite(patch.height(a.x, a.y)) || !Float.isFinite(patch.height(b.x, b.y))) {
                        float up = (patch.b().x - patch.a().x) * (patch.c().y - patch.a().y)
                              - (patch.b().y - patch.a().y) * (patch.c().x - patch.a().x);
                        edge = BoardTacticalGeometry.clip(edge, up > 0 ? List.of(patch.a(), patch.b(), patch.c())
                              : List.of(patch.c(), patch.b(), patch.a()));
                    }
                    if (edge.size() < 2) { continue; }
                    // Polygon clipping may repeat the cut at both ends of this two-point contour.
                    // Recover the interval in the original direction to retain the outward wall winding.
                    float from = 1, to = 0;
                    for (var point : edge) {
                        float t = new Vector3(point).sub(a).dot(along) / along.len2();
                        from = Math.min(from, t);
                        to = Math.max(to, t);
                    }
                    var p = new Vector3(a).mulAdd(along, from);
                    var q = new Vector3(a).mulAdd(along, to);
                    if (p.dst2(q) < 1e-8f) { continue; }
                    p.z = patch.height(p.x, p.y);
                    q.z = patch.height(q.x, q.y);
                    var wall = BoardBridgeSlope.easeEdge(p, q, rise);
                    for (int k = 0; k + 1 < wall.size(); k++) {
                        var s = new Vector3(wall.get(k)).add(0, 0, deckHeight);
                        var t = new Vector3(wall.get(k + 1)).add(0, 0, deckHeight);
                        quad(faces, new Vector3(s.x, s.y, bottom), s, t, new Vector3(t.x, t.y, bottom),
                              BoardBridge.Part.STRUCTURE);
                    }
                }
            }
        }
        BoardBridgeMesh.cap(faces, slab, bottom, false, BoardBridge.Part.STRUCTURE, null);
    }

    private static Vector3[] row(Vector3[] start, Vector3[] end, float t, boolean road, float climb) {
        var row = new Vector3[start.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = point(start[i], end[i], t, road, climb);
        }
        return row;
    }

    /** A road climbs straight over the share {@code climb} of the run, then runs level; bare ground eases. */
    private static Vector3 point(Vector3 start, Vector3 end, float t, boolean road, float climb) {
        var point = new Vector3(start).lerp(end, t);
        point.z = start.z + (end.z - start.z) * (road ? Math.min(1, t / climb) : t * t * (3 - 2 * t));
        return point;
    }

    /** Place the authored block outside the lane, grade it with the slab, and seat only its bottom on the bank. */
    private static void terminal(List<BoardBridge.Facet> faces, BoardShape block, Vector3[] start, Vector3[] end,
          float supportedAt, float reach, int side, List<BoardSurface.Face> ground, boolean road, float climb,
          BoardRoad curve, Coords owner, int direction) {
        float scale = BoardGeometry.hexScale();
        int inner = side < 0 ? 1 : 2, outer = side < 0 ? 0 : 3;
        var outward = new Vector3(end[outer]).sub(end[inner]);
        outward.z = 0;
        float railWidth = outward.len();
        outward.nor();
        var pivot = point(start[inner], end[inner], supportedAt / reach, road, climb);
        var movedPivot = new Vector3(pivot);
        var forward = new Vector3(end[inner]).sub(start[inner]);
        forward.z = 0;
        forward.nor();
        if (curve != null) { outward.set(-forward.y * side, forward.x * side, 0); }
        float angle = 0;
        if (curve != null) {
            var center = BoardGeometry.center(owner, 0);
            var at = curve.bendFrom(owner, (direction + 3) % 6,
                  (pivot.x - center.x) / scale, (pivot.y - center.y) / scale);
            var ahead = curve.bendFrom(owner, (direction + 3) % 6,
                  (pivot.x - center.x) / scale + forward.x * .1f, (pivot.y - center.y) / scale + forward.y * .1f);
            movedPivot.set(center.x + at.x * scale, center.y + at.y * scale, pivot.z);
            angle = (float) (Math.atan2(ahead.y - at.y, ahead.x - at.x) - Math.atan2(forward.y, forward.x));
        }
        float cosine = (float) Math.cos(angle), sine = (float) Math.sin(angle);
        var placed = new IdentityHashMap<Vector3, Vector3>();
        for (var face : block.polygons()) {
            var points = new Vector3[3];
            for (int i = 0; i < 3; i++) {
                points[i] = placed.computeIfAbsent(face.points()[i], source -> {
                    float at = (supportedAt + source.y * scale) / reach;
                    var p = point(start[inner], end[inner], at, road, climb)
                          .mulAdd(outward, source.x * TERMINAL_WIDTH_SCALE * scale);
                    if (curve != null) {
                        p.x = pivot.x + forward.x * source.y * scale + outward.x * source.x * TERMINAL_WIDTH_SCALE * scale;
                        p.y = pivot.y + forward.y * source.y * scale + outward.y * source.x * TERMINAL_WIDTH_SCALE * scale;
                    }
                    float px = p.x - pivot.x, py = p.y - pivot.y;
                    p.x = movedPivot.x + cosine * px - sine * py;
                    p.y = movedPivot.y + sine * px + cosine * py;
                    // Continue the rail's lateral plane through the wider block, then add its authored profile.
                    // Grading only the bottom leaves a raised wedge where the cap top meets a falling rail.
                    var rail = point(start[outer], end[outer], at, road, climb);
                    p.z += (rail.z - p.z) * source.x * TERMINAL_WIDTH_SCALE * scale / railWidth;
                    if (Math.abs(source.z) < .001f) {
                        p.z = Math.min(p.z, BoardSurface.sampleHeight(ground, p.x, p.y, p.z)) - BoardRelief.metres(.025f);
                    } else { p.z += source.z * scale; }
                    return p;
                });
            }
            // Mirroring to the opposite rail must preserve outward-facing triangles.
            BoardBridge.triangle(faces, points[0], points[side < 0 ? 1 : 2], points[side < 0 ? 2 : 1], BoardBridge.Part.STRUCTURE);
        }
    }

    /**
     * Solid concrete beneath a sloping bridge end, around its outline {@code ring}, seated below its ground and capped
     * by the existing deck.
     */
    private static void support(List<BoardBridge.Facet> faces, List<Vector3> ring, float bottom, float underside) {
        var top = ring.stream().map(p -> new Vector3(p).add(0, 0, underside)).toList();
        for (int i = 0; i < top.size(); i++) { side(faces, top.get(i), top.get((i + 1) % top.size()), bottom); }
    }

    /** One face of a support's side from p to q (its outside on the right), down to {@code bottom}; see {@link #support}. */
    private static void side(List<BoardBridge.Facet> faces, Vector3 p, Vector3 q, float bottom) {
        quad(faces, p, new Vector3(p.x, p.y, Math.min(bottom, p.z)), new Vector3(q.x, q.y, Math.min(bottom, q.z)), q,
              BoardBridge.Part.STRUCTURE);
    }

    private static void prism(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          float low, float high, BoardBridge.Part top) {
        var lower = new Vector3[4];
        var upper = new Vector3[4];
        var corners = List.of(a, b, c, d);
        for (int i = 0; i < 4; i++) {
            lower[i] = new Vector3(corners.get(i)).add(0, 0, low);
            upper[i] = new Vector3(corners.get(i)).add(0, 0, high);
        }
        quad(faces, upper[0], upper[1], upper[2], upper[3], top);
        quad(faces, lower[3], lower[2], lower[1], lower[0], BoardBridge.Part.STRUCTURE);
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            quad(faces, upper[i], lower[i], lower[n], upper[n], BoardBridge.Part.STRUCTURE);
        }
    }

    private static void quad(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d, BoardBridge.Part part) {
        BoardBridge.triangle(faces, a, b, c, part);
        BoardBridge.triangle(faces, a, c, d, part);
    }
}
