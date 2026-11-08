/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.common.board.HexTile;

/**
 * Connected structure kits ({@code models/buildings/README.md}): one GLB whose module nodes may carry tags for the
 * hexsides the structure connects across. Every building kit is one: {@link #placements} chooses, turns and stacks the
 * pieces that hold for a hex's connections, the single source of drawing (merged into one model by {@link #model} for
 * walls, placed from the kit's shared meshes by {@link GpuBuilding}), picking and ground-cover clearance. A symmetric
 * kit authors only the north (and north-east) pieces; the others are those turned. A whole-hex piece, tagged in all six
 * directions, stands alone for exactly those connections, in place of its module's other pieces. A linkless corner
 * piece may have a {@code -bend} variant, which stands in for it behind a 60-degree bend (the hex connects across just
 * the two hexsides opposite it): a roof whose plating mitres along the bend's two arms. Kits with {@code -cap} pieces
 * retain their ground footprint above shorter neighbours, closing the exposed hexsides with those walls and roof rims.
 * A roof's {@code -closed-N} variant includes the exposed links in bitmask N in its deck and edge calculation.
 */
final class ConnectedKit {
    /** Tag directions in MegaMek's order, clockwise from north. */
    static final List<String> DIRECTIONS = List.of("n", "ne", "se", "s", "sw", "nw");
    private static final String DIRECTION = "(?:ne|nw|se|sw|n|s)";
    private static final Pattern NODE = Pattern.compile(
          ".*-(base|floor|roof)(0|[1-9][0-9]*)((?:-(?:link|end)-" + DIRECTION + ")*)(-bend|-cap|-closed-[1-9][0-9]*)?");
    private static final Pattern TAG = Pattern.compile("-(link|end)-(" + DIRECTION + ")");
    /** How far a link arm's far end may lie from its edge line (authored seams overlap the neighbour's slightly). */
    private static final float REACH_TOLERANCE = 1;
    private static final Map<File, BoardKit<Kit>> KITS = new ConcurrentHashMap<>();

    private ConnectedKit() { }

    /**
     * One mesh node: its module role ({@code base0}, {@code floor1}, ...), the directions that must (not) connect, and
     * whether it is a linkless corner's {@code -bend} variant or a {@code -cap} closing a link above a shorter neighbour.
     * {@code closed} names a roof twin's exposed links in the same direction mask as {@code links}.
     */
    record Piece(String node, String role, int links, int ends, boolean bend, boolean cap, int closed) {
        boolean core() { return links == 0 && ends == 0; }

        boolean whole() { return (links | ends) == 63; }

        boolean holds(int connected) { return (connected & links) == links && (connected & ends) == 0; }

        Piece turned(int steps) { return new Piece(node, role, turn(links, steps), turn(ends, steps), bend, cap, turn(closed, steps)); }

        /** The same tags, as an identity shared by every module role; a bend shares its plain piece's. */
        long tags() { return (long) links << 6 | ends; }

        /** The tags and piece kind: what a kit holds once per role. */
        long identity() { return tags() | (bend ? 1L << 12 : 0) | (cap ? 1L << 13 : 0) | (long) closed << 14; }
    }

    /**
     * A kit file's levels of detail and pieces, read and checked once and shared by terrain workers. {@code pieces}: the
     * near level's; {@code parts}: each level of detail's ({@link #parts}). {@code grounds}: per level of detail, each
     * role's lowest point over its core and all its tagged pieces, the height it stands on.
     */
    record Kit(List<RigidGlb.Data> lods, List<Piece> pieces, List<List<Piece>> parts, Map<String, String> roles,
          boolean symmetric, List<Map<String, Float>> grounds) { }

    /** Validate every node name before any assembly; a kit without tags has one core piece per role. */
    static List<Piece> parts(ModelData data) {
        List<Piece> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Terrain workers read kit metadata concurrently; libGDX's cached Array iterator is shared.
        for (int index = 0; index < data.nodes.size; index++) {
            var node = data.nodes.get(index);
            require(!node.id.matches(".*\\.[0-9]{3}"), "Rename Blender's duplicate suffix: " + node.id);
            var match = NODE.matcher(node.id);
            require(match.matches(), "Expected -baseN, -floorN or -roofN, then -link-<dir> or -end-<dir> tags: " + node.id);
            require(node.parts != null && (node.parts.length > 0 || match.group(1).equals("roof") && "-cap".equals(match.group(4)))
                  && (node.children == null || node.children.length == 0),
                  "Building modules must be mesh nodes: " + node.id);
            int links = 0, ends = 0;
            var tags = TAG.matcher(match.group(3));
            while (tags.find()) {
                int bit = 1 << DIRECTIONS.indexOf(tags.group(2));
                require(((links | ends) & bit) == 0, "A direction appears twice: " + node.id);
                if (tags.group(1).equals("link")) { links |= bit; } else { ends |= bit; }
            }
            require(Integer.bitCount(links | ends) <= 2 || (links | ends) == 63,
                  "One or two tags, or all six for a whole hex: " + node.id);
            boolean bend = "-bend".equals(match.group(4)), cap = "-cap".equals(match.group(4));
            int closed = match.group(4) != null && match.group(4).startsWith("-closed-")
                  ? Integer.parseInt(match.group(4).substring(8)) : 0;
            require(!bend || match.group(1).equals("roof") && links == 0 && Integer.bitCount(ends) == 2,
                  "A bend piece is a linkless corner's roof: " + node.id);
            require(!cap || Integer.bitCount(links) == 1 && ends == 0, "A cap closes one link: " + node.id);
            require(closed == 0 || match.group(1).equals("roof") && (closed & ~links) == 0,
                  "A closed roof includes only its own exposed links: " + node.id);
            var piece = new Piece(node.id, match.group(1) + match.group(2), links, ends, bend, cap, closed);
            require(seen.add(piece.role() + "/" + piece.identity()), "Duplicate building module: " + node.id);
            result.add(piece);
        }
        for (String role : List.of("base", "floor", "roof")) {
            require(result.stream().anyMatch(piece -> piece.role().startsWith(role)), "Building requires a " + role + " variant");
        }
        return List.copyOf(result);
    }

    /** A wall, fuel tank or building kit file, read once; a reload publishes a fresh one. */
    static Kit kit(File file, Path textureRoot) {
        return KITS.computeIfAbsent(file.getAbsoluteFile(),
              key -> new BoardKit<>(() -> load(RigidGlb.loadLods(new FileHandle(key), textureRoot)))).get();
    }

    static void reload() {
        KITS.clear();
        GeneratedKit.reload();
    }

    static Kit load(List<ModelData> lods) {
        List<RigidGlb.Data> data = lods.stream().map(RigidGlb.Data.class::cast).toList();
        List<List<Piece>> parts = data.stream().map(ConnectedKit::parts).toList();
        List<Piece> pieces = parts.getFirst();
        Set<String> signature = signature(pieces);
        for (int lod = 0; lod < data.size(); lod++) {
            require(signature(parts.get(lod)).equals(signature), "Kit pieces must match across LODs: " + data.get(lod).id);
        }
        Map<String, String> roles = new LinkedHashMap<>();
        pieces.forEach(piece -> roles.putIfAbsent(piece.role(), piece.node()));
        boolean cores = pieces.stream().anyMatch(Piece::core);
        // A module type's variants are interchangeable, so they carry the same tagged pieces. A type may carry none:
        // a fuel tank's ground pipes run in its base only.
        Map<String, Set<Long>> typeTags = new HashMap<>();
        for (String role : roles.keySet()) {
            String type = role.replaceFirst("[0-9]+$", "");
            Set<Long> tags = pieces.stream().filter(piece -> piece.role().equals(role) && !piece.core())
                  .map(Piece::identity).collect(Collectors.toSet());
            require(typeTags.computeIfAbsent(type, ignored -> tags).equals(tags),
                  "Every " + type + " variant needs the same tagged pieces: " + role);
            require(!cores || pieces.stream().anyMatch(piece -> piece.role().equals(role) && piece.core()),
                  "Every module needs its core piece when any has one: " + role);
        }
        // Symmetric: one-tag pieces face north, two-tag pieces the north/north-east corner; turned for the others.
        boolean symmetric = pieces.stream().allMatch(piece -> piece.core() || piece.whole()
              || Integer.bitCount(piece.links() | piece.ends()) == 1 && (piece.links() | piece.ends()) == 1
              || (piece.links() | piece.ends()) == 3);
        Set<Long> wholes = pieces.stream().filter(Piece::whole).map(Piece::tags).collect(Collectors.toSet());
        for (var piece : pieces) {
            require(!piece.whole() || roles.keySet().stream().allMatch(role -> pieces.stream()
                  .anyMatch(other -> other.role().equals(role) && other.tags() == piece.tags())),
                  "Every module needs the kit's whole-hex pieces: " + piece.node());
            require(!piece.bend() || pieces.stream().anyMatch(other -> other.role().equals(piece.role()) && !other.bend()
                  && other.tags() == piece.tags()), "A bend piece stands in for its plain corner piece: " + piece.node());
            require(piece.closed() == 0 || pieces.stream().anyMatch(other -> other.role().equals(piece.role())
                  && other.closed() == 0 && !other.cap() && other.tags() == piece.tags()),
                  "A closed roof stands in for its open roof piece: " + piece.node());
            require(!piece.cap() || roles.keySet().stream().filter(role -> !role.startsWith("base")).allMatch(role -> pieces.stream()
                  .anyMatch(other -> other.role().equals(role) && other.cap() && other.links() == piece.links())),
                  "Every floor and roof needs the kit's link caps: " + piece.node());
            for (int steps = 1; steps < 6 && symmetric && piece.whole(); steps++) {
                long turned = piece.turned(steps).tags();
                require(turned == piece.tags() || !wholes.contains(turned),
                      "A symmetric kit authors each whole hex once, turned for the others: " + piece.node());
            }
        }
        if (!symmetric) {
            for (var piece : pieces) {
                for (int steps = 1; steps < 6 && !piece.core(); steps++) {
                    int links = turn(piece.links(), steps), ends = turn(piece.ends(), steps), closed = turn(piece.closed(), steps);
                    require(pieces.stream().anyMatch(other -> other.role().equals(piece.role()) && other.links() == links
                          && other.ends() == ends && other.bend() == piece.bend() && other.cap() == piece.cap()
                          && other.closed() == closed),
                          "A kit tags north only, or every direction: " + piece.node());
                }
            }
        }
        for (var piece : pieces) {
            int both = piece.links() | piece.ends();
            if (Integer.bitCount(both) != 2) { continue; }
            for (int links = 0; links < 4; links++) {
                int state = (links & 1) * Integer.lowestOneBit(both) | (links >> 1) * Integer.highestOneBit(both);
                require(pieces.stream().anyMatch(other -> other.role().equals(piece.role()) && other.links() == state
                      && other.ends() == (both & ~state)), "A corner piece needs all four link/end states: " + piece.node());
            }
        }
        List<Map<String, Float>> grounds = new ArrayList<>();
        for (int level = 0; level < data.size(); level++) {
            var lod = data.get(level);
            Map<String, BoundingBox> bounds = new HashMap<>();
            for (var piece : parts.get(level)) {
                var node = node(lod, piece.node());
                if (node.parts.length == 0) { continue; }
                bounds.computeIfAbsent(piece.role(), ignored -> new BoundingBox().inf()).ext(bounds(lod, node, new Matrix4()));
                if (!piece.cap() && Integer.bitCount(piece.links()) == 1 && piece.ends() == 0) { reach(lod, node, piece); }
            }
            Map<String, Float> ground = new HashMap<>();
            bounds.forEach((role, box) -> {
                require(role.startsWith("roof") || Math.abs(box.getDepth() - GpuBuilding.LEVEL_HEIGHT) < .001f,
                      role + " must be exactly one level (18 units) high, its tagged pieces included: " + lod.id);
                ground.put(role, box.min.z);
            });
            grounds.add(Map.copyOf(ground));
        }
        return new Kit(data, pieces, parts, Map.copyOf(roles), symmetric, List.copyOf(grounds));
    }

    private static Set<String> signature(List<Piece> pieces) {
        Set<String> result = new HashSet<>();
        pieces.forEach(piece -> result.add(piece.role() + "/" + piece.identity()));
        return result;
    }

    /** A link arm runs out to its edge: its far end lies on the edge line through the midpoint. */
    private static void reach(RigidGlb.Data data, ModelNode node, Piece piece) {
        int direction = Integer.numberOfTrailingZeros(piece.links());
        float angle = (float) Math.toRadians(90 - 60 * direction);
        var axis = new Matrix4().rotateRad(Vector3.Z, (float) Math.PI / 2 - angle);
        // Turned so that the arm points north, its far end is its highest y.
        float far = bounds(data, node, axis).max.y;
        require(Math.abs(far - HexTile.HEIGHT / 2f) <= REACH_TOLERANCE,
              piece.node() + " must end on its edge, " + HexTile.HEIGHT / 2 + " units from the centre (ends at " + far + ")");
    }

    private static BoundingBox bounds(RigidGlb.Data data, ModelNode node, Matrix4 view) {
        var result = new BoundingBox().inf();
        triangles(data, node, view).forEach(result::ext);
        require(result.isValid(), "Empty building module: " + node.id);
        return result;
    }

    /** A placed piece's triangles in assembly space (model units): the clearance geometry of every building kit. */
    static List<Vector3> triangles(Kit kit, int lod, Placement piece) {
        var data = kit.lods().get(lod);
        return triangles(data, node(data, piece.node()), piece.matrix());
    }

    static List<Vector3> triangles(RigidGlb.Data data, ModelNode node, Matrix4 view) {
        Matrix4 placement = new Matrix4(view).mul(new Matrix4(node.translation, node.rotation, node.scale));
        Set<String> parts = new HashSet<>();
        for (var part : node.parts) { parts.add(part.meshPartId); }
        List<Vector3> result = new ArrayList<>();
        for (int meshIndex = 0; meshIndex < data.meshes.size; meshIndex++) {
            var mesh = data.meshes.get(meshIndex);
        for (var part : mesh.parts) {
            if (!parts.contains(part.id)) { continue; }
            for (short index : part.indices) {
                int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                result.add(new Vector3(mesh.vertices[at], mesh.vertices[at + 1], mesh.vertices[at + 2]).mul(placement));
            }
        }
        }
        return result;
    }

    static ModelNode node(ModelData data, String id) {
        for (int index = 0; index < data.nodes.size; index++) {
            if (data.nodes.get(index).id.equals(id)) { return data.nodes.get(index); }
        }
        throw new IllegalArgumentException("Missing kit node " + id);
    }

    /** {@code mask} turned clockwise by {@code steps} sixths. */
    static int turn(int mask, int steps) {
        int s = Math.floorMod(steps, 6);
        return (mask << s | mask >>> (6 - s)) & 63;
    }

    /**
     * How many levels from its ground the structure of {@code tile}, {@code height} levels high, connects towards each
     * board direction: 0 without an exit there ({@link BoardScene.Cover#exits}), else its whole height, lowered by a
     * neighbour of the same structure terrain (building or fuel tank) that connects back ({@link #arm}).
     */
    static int[] reach(BoardScene scene, BoardScene.Tile tile, int height) {
        int[] result = new int[6];
        for (int direction = 0; direction < 6; direction++) {
            if ((tile.cover().exits() & 1 << direction) == 0) { continue; }
            var neighbor = scene.neighbor(tile, direction);
            result[direction] = back(scene, tile, direction)
                  ? arm(tile, height, neighbor, Math.max(1, neighbor.cover().height())) : height;
        }
        return result;
    }

    /**
     * Whether the neighbour towards {@code direction} is the same structure terrain (building or fuel tank) and connects
     * back across the shared hexside.
     */
    static boolean back(BoardScene scene, BoardScene.Tile tile, int direction) {
        var neighbor = scene.neighbor(tile, direction);
        var other = neighbor == null ? BoardScene.Cover.NONE : neighbor.cover();
        var cover = tile.cover();
        return other.structure() != 0
              && (other.structure() == BoardScene.Cover.FUEL_TANK) == (cover.structure() == BoardScene.Cover.FUEL_TANK)
              && (other.exits() & 1 << (direction + 3) % 6) != 0;
    }

    /**
     * A connection's height in levels towards a neighbour that connects back, {@code neighborHeight} levels high: up to
     * the lower top where their vertical spans overlap, else the whole {@code height}.
     */
    static int arm(BoardScene.Tile tile, int height, BoardScene.Tile neighbor, int neighborHeight) {
        // No overlapping vertical span means a terrain step separates the structures; do not invent a bridge.
        return overlaps(tile, height, neighbor, neighborHeight)
              ? Math.min(height, neighbor.elevation() - tile.elevation() + neighborHeight) : height;
    }

    private static boolean overlaps(BoardScene.Tile tile, int height, BoardScene.Tile neighbor, int neighborHeight) {
        return neighbor.elevation() - tile.elevation() + neighborHeight > 0 && neighbor.elevation() < tile.elevation() + height;
    }

    /**
     * Whether the inside of the structure of {@code tile}, {@code height} levels high, shows from outside: it, or a
     * neighbour it connects with, connects across a hexside where nothing connects back over its span ({@link #reach}),
     * such as a missing or collapsed neighbour, and stands open there.
     */
    static boolean open(BoardScene scene, BoardScene.Tile tile, int height) {
        if (openEnd(scene, tile, height)) { return true; }
        for (int direction = 0; direction < 6; direction++) {
            if ((tile.cover().exits() & 1 << direction) == 0 || !back(scene, tile, direction)) { continue; }
            var neighbor = scene.neighbor(tile, direction);
            if (openEnd(scene, neighbor, Math.max(1, neighbor.cover().height()))) { return true; }
        }
        return false;
    }

    private static boolean openEnd(BoardScene scene, BoardScene.Tile tile, int height) {
        for (int direction = 0; direction < 6; direction++) {
            if ((tile.cover().exits() & 1 << direction) == 0) { continue; }
            var neighbor = scene.neighbor(tile, direction);
            if (!back(scene, tile, direction) || !overlaps(tile, height, neighbor, Math.max(1, neighbor.cover().height()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The kit assembled for one hex: {@code reach[d]} is how many levels from the ground the hex connects towards board
     * direction {@code d} (0 where it does not), and the whole assembly turns clockwise by {@code steps} sixths. Each
     * piece stacks through the levels where its tags hold: a base on the ground or a floor where it starts higher, and
     * its own roof where it stops. Module variants are chosen as for a plain building ({@link GpuBuilding#select}).
     * UVs, images and material factors remain authored. No GL resource belongs here.
     */
    static RigidGlb.Data model(Kit kit, int lod, int height, int[] reach, int steps, long seed, String id) {
        var source = kit.lods().get(lod);
        var result = new RigidGlb.Data();
        result.id = id;
        materials(result, source);
        for (var placement : placements(kit, lod, height, reach, steps, seed)) {
            append(result, source, node(source, placement.node()), placement.matrix(), placement.prefix());
        }
        return result;
    }

    /** Adds {@code source}'s materials, with their images and finishes, to {@code result} for its placed nodes. */
    static void materials(RigidGlb.Data result, RigidGlb.Data source) {
        result.inheritMaterials(source);
        // libGDX Array reuses its iterator: immutable kit data is read concurrently by terrain workers.
        for (int index = 0; index < source.materials.size; index++) {
            var material = source.materials.get(index);
            var copy = new ModelMaterial();
            copy.id = material.id;
            copy.ambient = material.ambient; copy.diffuse = material.diffuse; copy.specular = material.specular;
            copy.emissive = material.emissive; copy.reflection = material.reflection;
            copy.shininess = material.shininess; copy.opacity = material.opacity; copy.textures = material.textures;
            result.materials.add(copy);
        }
    }

    /**
     * Shared assembly result for runtime geometry and authoring previews: a piece of module {@code role} standing on
     * {@code level}; matrix precedes the authored node frame.
     */
    record Placement(String node, String role, int level, Matrix4 matrix, String prefix) { }

    static List<Placement> placements(Kit kit, int lod, int height, int[] reach, int steps, long seed) {
        return placements(kit, lod, height, reach, steps, seed, false);
    }

    static List<Placement> placements(Kit kit, int lod, int height, int[] reach, int steps, long seed, boolean stepped) {
        return placements(kit, lod, GpuBuilding.select(kit.roles(), height, seed), reach, steps, stepped);
    }

    /** {@code selected}: the base, the floors and the roof chosen for the levels ({@link GpuBuilding#select}). */
    static List<Placement> placements(Kit kit, int lod, List<String> selected, int[] reach, int steps) {
        return placements(kit, lod, selected, reach, steps, false);
    }

    static List<Placement> placements(Kit kit, int lod, List<String> selected, int[] reach, int steps, boolean stepped) {
        var source = kit.lods().get(lod);
        var ground = kit.grounds().get(lod);
        int height = selected.size() - 1;
        Map<String, Map<Long, Piece>> byRole = new HashMap<>(), bends = new HashMap<>(), closedRoofs = new HashMap<>();
        for (var piece : kit.parts().get(lod)) {
            (piece.closed() != 0 ? closedRoofs : piece.bend() ? bends : byRole).computeIfAbsent(piece.role(), ignored -> new HashMap<>())
                  .put(piece.bend() ? piece.tags() : piece.identity(), piece);
        }
        boolean capped = !stepped && kit.pieces().stream().anyMatch(Piece::cap);
        int[] connected = new int[height];
        for (int level = 0; level < height; level++) {
            for (int direction = 0; direction < 6; direction++) {
                // Kit direction k lies along board direction k + steps.
                if (level < reach[(direction + steps) % 6]) { connected[level] |= 1 << direction; }
            }
        }
        List<Placement> result = new ArrayList<>();
        Set<Long> identities = new HashSet<>();
        byRole.values().forEach(pieces -> identities.addAll(pieces.keySet()));
        // The ground connections choose the footprint. With caps it continues to the roof, while each exposed link
        // gets a wall above its neighbour. Authored kits without caps retain their existing stepped assembly.
        long whole = -1;
        int wholeTurn = 0;
        for (long tags : identities.stream().sorted().toList()) {
            Piece any = byRole.values().stream().map(pieces -> pieces.get(tags)).filter(Objects::nonNull)
                  .findFirst().orElseThrow();
            for (int turn = 0; turn < (kit.symmetric() ? 6 : 1) && any.whole() && whole < 0; turn++) {
                if (any.turned(turn).holds(connected[0])) { whole = tags; wholeTurn = turn; }
            }
        }
        for (long tags : identities.stream().sorted().toList()) {
            Piece any = byRole.values().stream().map(pieces -> pieces.get(tags)).filter(Objects::nonNull)
                  .findFirst().orElseThrow();
            for (int turn = 0; turn < (kit.symmetric() && !any.core() ? 6 : 1); turn++) {
                Piece turned = any.turned(turn);
                boolean before = false;
                for (int level = 0; level <= height; level++) {
                    boolean wholeLevel = whole >= 0 && level < height && (capped || connected[level] == connected[0]);
                    boolean holds = level < height && (any.cap()
                          ? capped && (connected[0] & turned.links()) != 0 && (connected[level] & turned.links()) == 0
                          : any.whole() ? tags == whole && turn == wholeTurn && wholeLevel
                                : !wholeLevel && turned.holds(capped ? connected[0] : connected[level]));
                    // A run's modules, then its own roof where it stops: below the top, or at the top.
                    String role = holds ? selected.get(level) : before ? selected.getLast() : null;
                    Piece piece = role == null ? null : byRole.get(role).get(tags);
                    // Where it stops behind a 60-degree bend (connected across just the two hexsides opposite it).
                    Piece bend = holds || role == null ? null : bends.getOrDefault(role, Map.of()).get(tags);
                    if (bend != null && connected[capped ? 0 : level - 1] == turn(turned.ends(), 3)) { piece = bend; }
                    if (capped && piece != null && role.startsWith("roof") && !piece.cap()) {
                        int closed = piece.links() & ~turn(connected[level - 1], -turn);
                        piece = closedRoofs.getOrDefault(role, Map.of()).getOrDefault(piece.identity() | (long) closed << 14, piece);
                    }
                    if (piece != null && node(source, piece.node()).parts.length > 0) {
                        result.add(new Placement(piece.node(), role, level, placement(level * GpuBuilding.LEVEL_HEIGHT
                              - ground.get(role), turn + steps), piece.node() + "@" + turn + "/" + role + "-at-" + level + "/"));
                    }
                    before = holds;
                }
            }
        }
        return List.copyOf(result);
    }

    private static final float[] COS = { 1, .5f, -.5f, -1, -.5f, .5f };
    private static final float[] SIN = { 0, (float) -Math.sqrt(.75), (float) -Math.sqrt(.75), 0, (float) Math.sqrt(.75),
          (float) Math.sqrt(.75) };

    static Matrix4 placement(float lift, int steps) {
        // Clockwise sixths of a turn about the up axis: exact, so every turned copy meets its edge like the north one.
        int turn = Math.floorMod(steps, 6);
        Matrix4 placement = new Matrix4();
        placement.val[Matrix4.M00] = COS[turn]; placement.val[Matrix4.M01] = -SIN[turn];
        placement.val[Matrix4.M10] = SIN[turn]; placement.val[Matrix4.M11] = COS[turn];
        placement.val[Matrix4.M23] = lift;
        return placement;
    }

    /** Adds {@code node}'s parts, placed by {@code frame}, to {@code result} as a mesh of their own. */
    static void append(RigidGlb.Data result, RigidGlb.Data source, ModelNode node, Matrix4 frame, String prefix) {
        for (int index = 0; index < source.meshes.size; index++) {
            append(result, source.meshes.get(index), node, frame, prefix + index + "/");
        }
    }

    private static void append(RigidGlb.Data result, ModelMesh mesh, ModelNode node, Matrix4 frame, String prefix) {
        Matrix4 placement = new Matrix4(frame).mul(new Matrix4(node.translation, node.rotation, node.scale));
        Matrix3 normals = new Matrix3().set(placement).inv().transpose();
        var point = new Vector3();
        var included = new HashSet<String>();
        for (var part : node.parts) { included.add(part.meshPartId); }
        var copy = new ModelMesh();
        copy.id = prefix + mesh.id;
        copy.attributes = mesh.attributes;
        var vertices = new FloatArray();
        int[] remap = new int[mesh.vertices.length / RigidGlb.STRIDE];
        Arrays.fill(remap, -1);
        List<ModelMeshPart> parts = new ArrayList<>();
        for (var original : mesh.parts) {
            if (!included.contains(original.id)) { continue; }
            var indices = new ShortArray();
            for (short originalIndex : original.indices) {
                int old = Short.toUnsignedInt(originalIndex);
                if (remap[old] < 0) {
                    remap[old] = vertices.size / RigidGlb.STRIDE;
                    int at = vertices.size;
                    vertices.addAll(mesh.vertices, old * RigidGlb.STRIDE, RigidGlb.STRIDE);
                    point.set(vertices.get(at), vertices.get(at + 1), vertices.get(at + 2)).mul(placement);
                    vertices.set(at, point.x); vertices.set(at + 1, point.y); vertices.set(at + 2, point.z);
                    point.set(vertices.get(at + 3), vertices.get(at + 4), vertices.get(at + 5)).mul(normals).nor();
                    vertices.set(at + 3, point.x); vertices.set(at + 4, point.y); vertices.set(at + 5, point.z);
                }
                indices.add((short) remap[old]);
            }
            var part = new ModelMeshPart();
            part.id = prefix + original.id;
            part.primitiveType = original.primitiveType;
            part.indices = indices.toArray();
            parts.add(part);
        }
        if (parts.isEmpty()) { return; }
        copy.vertices = vertices.toArray();
        copy.parts = parts.toArray(ModelMeshPart[]::new);
        result.meshes.add(copy);
        var copiedNode = new ModelNode();
        copiedNode.id = prefix + node.id; copiedNode.meshId = copy.id;
        copiedNode.children = new ModelNode[0];
        List<ModelNodePart> nodeParts = new ArrayList<>();
        for (var original : node.parts) {
            if (parts.stream().noneMatch(part -> part.id.equals(prefix + original.meshPartId))) { continue; }
            var part = new ModelNodePart();
            part.meshPartId = prefix + original.meshPartId;
            part.materialId = original.materialId;
            nodeParts.add(part);
        }
        copiedNode.parts = nodeParts.toArray(ModelNodePart[]::new);
        result.nodes.add(copiedNode);
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
