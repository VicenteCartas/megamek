/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BuildingInteriors;

/**
 * One shared GLB kit ({@link ConnectedKit}) and compact assembly recipes: the selected modules and the hex's connection
 * heights. Only the kit's meshes and generated interiors own GPU resources.
 */
final class GpuBuilding implements Disposable {
    /** Paint only authored wall/panel surfaces; glass, equipment and generated interiors keep their own finish. */
    static void tint(ModelInstance instance, BoardDecoration.Colours colours) {
        String value = colours.slot(0);
        var tint = value == null ? com.badlogic.gdx.graphics.Color.WHITE
              : GpuColor.linear(com.badlogic.gdx.graphics.Color.valueOf(value));
        for (var material : instance.materials) {
            if (!material.has(GpuBuildingPaint.TYPE) || AuthoredBoardClip.CAP.equals(material.id)) { continue; }
            material.set(new GpuBuildingPaint(tint));
        }
    }

    static final float LEVEL_HEIGHT = BoardGeometry.MODEL_LEVEL_HEIGHT;
    private final ConnectedKit.Kit kit;
    private final List<Model> models = new ArrayList<>();
    private final Map<Recipe, Assembly> assemblies = new HashMap<>();
    // Whether the kit holds junctions where its walls meet the neighbours' at a corner (ConnectedKit#junctions).
    private boolean junctions;
    // Per piece in its authored frame: near for picking and roof outlines, far for wall sections.
    private final Map<String, List<Vector3>> triangles = new HashMap<>();
    private final Map<String, List<Vector3>> farTriangles = new HashMap<>();
    private final Map<String, BoundingBox> bounds = new HashMap<>();
    // Per piece and slab height in its authored frame: its wall faces beside a floor slab there (faces).
    private final Map<String, Faces> faces = new HashMap<>();
    // Per piece in its authored frame: its floor cap (cap).
    private final Map<String, List<Vector3>> caps = new HashMap<>();
    // Per placement of the pieces that shape a storey: its floor (storey).
    private final Map<String, GpuBuildingInterior.Storey> storeys = new HashMap<>();
    // Per roof piece's placement on the plan: the outline it covers (roof).
    private final Map<String, Area> roofs = new HashMap<>();
    private final Map<Interior, Model> interiors = new HashMap<>();
    // Per assembly: its near instance's box in its own frame (Assembly#bounds).
    private final Map<Assembly, BoundingBox> instanceBounds = new java.util.IdentityHashMap<>();

    private record Interior(List<List<Vector3>> footprints, int floors) { }
    /** {@code junctions}: {@link ConnectedKit#junctions} where the kit holds them, else empty. */
    record Recipe(List<String> modules, List<Integer> reach, BuildingInteriors interiors, boolean stepped,
          List<Integer> junctions) { }

    /** No expanded Model is cached: each level of detail places the shared kit's nodes ({@link #instance}). */
    record Assembly(Recipe recipe, List<ConnectedKit.Placement> near, Model interior, GpuBuilding kit) {
        int lodCount() { return kit.models.size(); }
        Model model(int lod) { return kit.models.get(lod); }
        ModelInstance instance(int lod) { return kit.instance(lod == 0 ? near : kit.placements(recipe, lod), lod); }
        boolean hit(Ray ray, Vector3 result) { return kit.hit(near, ray, result); }

        /** A copy of the near instance's box in its own frame, scanned from {@code instance} once per assembly. */
        BoundingBox bounds(ModelInstance instance) {
            return new BoundingBox(kit.instanceBounds.computeIfAbsent(this, ignored -> instance.calculateBoundingBox(new BoundingBox())));
        }
    }

    GpuBuilding(ConnectedKit.Kit kit, Function<ModelData, Model> upload) {
        this.kit = kit;
        var data = lods(kit.lods());
        try {
            for (int lod = 0; lod < data.size(); lod++) {
                int previous = data.indexOf(data.get(lod));
                models.add(previous < lod ? models.get(previous) : upload.apply(data.get(lod)));
            }
            // Every kit shares its picking geometry, a corner's junctions too; only enterable buildings need wall/roof
            // footprints.
            for (Node node : models.getFirst().nodes) {
                triangles.put(node.id, GpuTerrain.triangles(List.of(node), part -> !part.material.id.endsWith(GeneratedMesh.BUILDING_FLOOR)));
                bounds.put(node.id, node.calculateBoundingBox(new BoundingBox()));
                junctions |= node.id.contains("-junction-");
            }
        } catch (RuntimeException | Error error) {
            dispose();
            throw error;
        }
    }

    /**
     * A custom kit file ({@link megamek.common.board.TilesetScenery#customBuildingFile}), read once for every user. Its
     * textures may be the board's shared materials, as the walls' are.
     */
    static ConnectedKit.Kit kit(File file) {
        return ConnectedKit.kit(file, new File(Configuration.dataDir(), "models").toPath());
    }

    /**
     * A building asset's kit: its GLB, or generated from its JSON spec in the asset's footprint and variant
     * ({@link GeneratedKit}, {@link megamek.common.board.TilesetScenery#kitVariant}).
     */
    static ConnectedKit.Kit kit(String asset) {
        return kit(asset, 1);
    }

    static ConnectedKit.Kit kit(String asset, float footprintScale) {
        File file = megamek.common.board.TilesetScenery.customBuildingFile(asset);
        if (!file.getName().endsWith(".json")) { return kit(file); }
        int at = asset.indexOf('@');
        String design = at < 0 ? asset : asset.substring(0, at);
        return GeneratedKit.kit(file, design.endsWith("-square"), at < 0 ? null : asset.substring(at + 1), footprintScale);
    }

    /** Trim trailing fallbacks, retaining authored level numbers and any gaps filled by the generic reader. */
    static <T extends ModelData> List<T> lods(List<T> loaded) {
        require(MeshLod.level(loaded.getFirst().id) == 0, "Building requires -lod0");
        int last = loaded.size() - 1;
        while (last > 0 && loaded.get(last) == loaded.get(last - 1)) { last--; }
        return List.copyOf(loaded.subList(0, last + 1));
    }

    /** A stable per-placement seed, independent of camera, height, LOD, frame order and JVM hash randomization. */
    static long seed(BoardScene.Tile tile, BoardScene.Feature feature) {
        return tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L ^ feature.asset().hashCode();
    }

    /** A stable per-placement seed, independent of camera, height, LOD, frame order and JVM hash randomization. */
    static List<String> select(Map<String, String> parts, int levels, long seed) {
        require(levels >= 1, "A building needs at least one level");
        return megamek.common.board.BuildingVolumes.select(parts.keySet(), levels, seed);
    }

    /**
     * {@code reach}: how many levels the hex connects towards each direction ({@link ConnectedKit#reach}). Industrial
     * terrain and fuel tanks borrow the same modules without allocating occupiable interiors.
     */
    Assembly assemble(int levels, int[] reach, long seed, boolean withInterior) {
        return assemble(levels, reach, seed, withInterior ? BuildingInteriors.YES : BuildingInteriors.NO);
    }

    Assembly assemble(int levels, int[] reach, long seed, BuildingInteriors policy) {
        return assemble(levels, reach, seed, policy, false);
    }

    Assembly assemble(int levels, int[] reach, long seed, BuildingInteriors policy, boolean stepped) {
        return assemble(levels, reach, seed, policy, stepped, null);
    }

    /** {@code corners}: {@link ConnectedKit#corners}, or null for none of its walls' junctions with the neighbours'. */
    Assembly assemble(int levels, int[] reach, long seed, BuildingInteriors policy, boolean stepped, int[] corners) {
        // Pieces stand by the ground's connections where the kit caps the links above ({@link ConnectedKit#placements}).
        boolean capped = !stepped && kit.pieces().stream().anyMatch(ConnectedKit.Piece::cap);
        List<Integer> junctions = corners == null || !this.junctions ? List.of()
              : Arrays.stream(ConnectedKit.junctions(levels, reach, corners, capped)).boxed().toList();
        var recipe = new Recipe(select(kit.roles(), levels, seed), Arrays.stream(reach).boxed().toList(), policy, stepped,
              junctions);
        return assemblies.computeIfAbsent(recipe, key -> {
            var near = placements(key, 0);
            if (policy == BuildingInteriors.NO) { return new Assembly(key, near, null, this); }
            var storeys = volumes(key, near);
            int floors = policy == BuildingInteriors.NO_FLOORS ? 1 : levels;
            var footprints = storeys.stream().map(GpuBuildingInterior.Storey::triangles).toList();
            Model interior = interiors.computeIfAbsent(new Interior(footprints, floors),
                  ignored -> GpuBuildingInterior.buildStoreys(storeys, LEVEL_HEIGHT, floors));
            return new Assembly(key, near, interior, this);
        });
    }

    private List<ConnectedKit.Placement> placements(Recipe recipe, int lod) {
        return ConnectedKit.placements(kit, lod, recipe.modules(), recipe.reach().stream().mapToInt(Integer::intValue).toArray(),
              0, recipe.stepped(), recipe.junctions().isEmpty() ? null : recipe.junctions().stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * The occupied volume: the roof projection within each storey's generated floor cap. Authored kits without caps
     * use their closed mid-storey wall outlines from the simplest LOD. A storey whose walls close nothing (open towards
     * connected neighbours) leaves the roof projection as it is.
     */
    private List<GpuBuildingInterior.Storey> volumes(Recipe recipe, List<ConnectedKit.Placement> pieces) {
        // These caps fit behind the facade, including open connection ends. Closed panel boxes describe wall strips,
        // not rooms, so only kits without floor caps need the wall-section fallback.
        boolean floorCaps = pieces.stream().anyMatch(piece -> !piece.role().startsWith("roof") && !cap(piece).isEmpty());
        var far = floorCaps ? null : placements(recipe, models.size() - 1);
        List<GpuBuildingInterior.Storey> result = new ArrayList<>();
        for (int level = 0; level < recipe.modules().size() - 1; level++) { result.add(storey(pieces, far, level)); }
        return List.copyOf(result);
    }

    /**
     * Storey {@code level}'s floor outline ({@link #volumes}), its edge kept clear of the walls beside its slab
     * ({@link #clear}). Worked out once per placement of the pieces that shape it (its storey's, the roofs above and
     * those beside its slab), for every storey and assembly of the kit alike; {@code far}: the wall-section fallback's
     * pieces, or null with caps.
     */
    private GpuBuildingInterior.Storey storey(List<ConnectedKit.Placement> pieces, List<ConnectedKit.Placement> far, int level) {
        float top = level * LEVEL_HEIGHT;
        StringBuilder key = new StringBuilder(far == null ? "caps|" : "walls|");
        for (var piece : pieces) {
            boolean roof = piece.role().startsWith("roof");
            // A roof shapes the floor by its plan alone.
            if (roof ? piece.level() > level : piece.level() == level || level > 0 && faces(piece, top) != null) {
                key.append(placement(piece, roof ? null : top)).append('|');
            }
        }
        return storeys.computeIfAbsent(key.toString(), ignored -> {
            Area storey = new Area();
            for (var piece : far == null ? pieces : far) {
                if (piece.role().startsWith("roof") || piece.level() != level) { continue; }
                if (far == null) {
                    storey.add(GpuBuildingInterior.area(placed(piece, cap(piece))));
                    continue;
                }
                var shape = farTriangles.computeIfAbsent(piece.node(),
                      node -> GpuTerrain.triangles(List.of(models.getLast().getNode(node)),
                            part -> !part.material.id.equals(GeneratedMesh.BUILDING_BODY)));
                storey.add(GpuBuildingInterior.walls(placed(piece, shape), (level + .5f) * LEVEL_HEIGHT));
            }
            Area roof = new Area();
            for (var piece : pieces) {
                if (piece.role().startsWith("roof") && piece.level() > level) {
                    roof.add(roofs.computeIfAbsent(placement(piece, null),
                          plan -> GpuBuildingInterior.area(placed(piece, triangles.get(piece.node())))));
                }
            }
            require(!roof.isEmpty(), kit.lods().getFirst().id + " requires a roof footprint above storey " + level);
            // Each floor follows its own shell. A lower terrace must not enlarge the floors above it.
            if (!storey.isEmpty()) { roof.intersect(storey); }
            // The ground floor's slab lies under the terrain.
            return GpuBuildingInterior.Storey.of(GpuBuildingInterior.triangles(level == 0 ? roof : clear(roof, top, pieces)));
        });
    }

    /** A piece's floor cap in its authored frame, cached. */
    private List<Vector3> cap(ConnectedKit.Placement piece) {
        return caps.computeIfAbsent(piece.node(), node -> GpuTerrain.triangles(List.of(models.getFirst().getNode(node)),
              part -> part.material.id.endsWith(GeneratedMesh.BUILDING_FLOOR)));
    }

    /**
     * {@code floor}, topped at {@code top}, kept clear of the faces of the pieces beside its slab
     * ({@link GpuBuildingInterior#keepClear}): its edge never lies on a wall's outside, at an open end either.
     */
    private Area clear(Area floor, float top, List<ConnectedKit.Placement> pieces) {
        var bounds = floor.getBounds2D();
        List<float[]> placed = new ArrayList<>();
        for (var piece : pieces) {
            Faces faces = piece.role().startsWith("roof") ? null : faces(piece, top);
            if (faces == null || !transform(piece).createTransformedShape(faces.bounds()).getBounds2D().intersects(bounds)) { continue; }
            float[] m = piece.matrix().val;
            for (float[] face : faces.segments()) {
                placed.add(new float[] { m[Matrix4.M00] * face[0] + m[Matrix4.M01] * face[1] + m[Matrix4.M03],
                      m[Matrix4.M10] * face[0] + m[Matrix4.M11] * face[1] + m[Matrix4.M13],
                      m[Matrix4.M00] * face[2] + m[Matrix4.M01] * face[3] + m[Matrix4.M03],
                      m[Matrix4.M10] * face[2] + m[Matrix4.M11] * face[3] + m[Matrix4.M13] });
            }
        }
        return placed.isEmpty() ? floor : GpuBuildingInterior.keepClear(floor, placed);
    }

    /** A piece's wall faces in its authored frame, and their bounds widened by the clearance a floor keeps from them. */
    private record Faces(List<float[]> segments, java.awt.geom.Rectangle2D bounds) { }

    /** A piece's faces beside a slab topped at {@code top} ({@link GpuBuildingInterior#faces}), cached; null without any. */
    private Faces faces(ConnectedKit.Placement piece, float top) {
        float local = top - piece.matrix().val[Matrix4.M23];
        BoundingBox box = bounds.get(piece.node());
        if (box.min.z > local || box.max.z < local - GpuBuildingInterior.SLAB) { return null; }
        var faces = this.faces.computeIfAbsent(piece.node() + "@" + local, ignored -> {
            var segments = GpuBuildingInterior.faces(triangles.get(piece.node()), local);
            var path = new java.awt.geom.Path2D.Float();
            for (float[] face : segments) { path.moveTo(face[0], face[1]); path.lineTo(face[2], face[3]); }
            var bounds = path.getBounds2D();
            float reach = GpuBuildingInterior.CLEARANCE;
            bounds.setRect(bounds.getX() - reach, bounds.getY() - reach, bounds.getWidth() + 2 * reach, bounds.getHeight() + 2 * reach);
            return new Faces(segments, bounds);
        });
        return faces.segments().isEmpty() ? null : faces;
    }

    /**
     * A placement's identity for {@link #storeys}: its piece, how it stands on the plan and its height against the
     * slab topped at {@code top} (null: its plan alone), so every storey alike shares one entry.
     */
    private static String placement(ConnectedKit.Placement piece, Float top) {
        float[] m = piece.matrix().val;
        return piece.node() + ' ' + m[Matrix4.M00] + ' ' + m[Matrix4.M01] + ' ' + m[Matrix4.M03] + ' ' + m[Matrix4.M10]
              + ' ' + m[Matrix4.M11] + ' ' + m[Matrix4.M13] + (top == null ? "" : " " + (m[Matrix4.M23] - top));
    }

    /** The plan transform of a placement: its turn, squash and offset. */
    private static AffineTransform transform(ConnectedKit.Placement piece) {
        float[] m = piece.matrix().val;
        return new AffineTransform(m[Matrix4.M00], m[Matrix4.M10], m[Matrix4.M01], m[Matrix4.M11], m[Matrix4.M03], m[Matrix4.M13]);
    }

    private static List<Vector3> placed(ConnectedKit.Placement piece, List<Vector3> triangles) {
        return triangles.stream().map(point -> new Vector3(point).mul(piece.matrix())).toList();
    }

    /** Create only the scene instance, at load/edit or a LOD transition; mesh buffers stay in the shared kit. */
    private ModelInstance instance(List<ConnectedKit.Placement> pieces, int lod) {
        Model source = models.get(lod);
        ModelInstance instance = new ModelInstance(source, new String[0]);
        // Placement materials are needed for cutaway opacity; their textures remain shared.
        for (var material : source.materials) { instance.materials.add(material.copy()); }
        for (var piece : pieces) {
            Node node = source.getNode(piece.node()).copy();
            node.id = piece.prefix();
            // The placement turns and lifts the authored node frame.
            Matrix4 frame = new Matrix4(piece.matrix()).mul(new Matrix4(node.translation, node.rotation, node.scale));
            frame.getTranslation(node.translation);
            frame.getRotation(node.rotation, true);
            frame.getScale(node.scale);
            // Floor caps describe the room outline. Its visible slabs and picking belong to the interior.
            for (int i = node.parts.size - 1; i >= 0; i--) {
                if (node.parts.get(i).material.id.endsWith(GeneratedMesh.BUILDING_FLOOR)) { node.parts.removeIndex(i); }
            }
            for (var part : node.parts) { part.material = instance.getMaterial(part.material.id); }
            instance.nodes.add(node);
        }
        instance.calculateTransforms();
        return instance;
    }

    /** Keep only assemblies used by installed/cached chunks, at the scene commit boundary on the GL thread. */
    void retain(Set<Assembly> live) {
        assemblies.values().removeIf(assembly -> !live.contains(assembly));
        instanceBounds.keySet().removeIf(assembly -> !live.contains(assembly));
        Set<Model> needed = new java.util.HashSet<>();
        assemblies.values().forEach(assembly -> needed.add(assembly.interior()));
        interiors.values().removeIf(model -> {
            if (needed.contains(model)) { return false; }
            model.dispose();
            return true;
        });
    }

    /** Picking borrows one triangle set per piece, never expands every floor of every random combination. */
    private boolean hit(List<ConnectedKit.Placement> pieces, Ray ray, Vector3 result) {
        float nearest = Float.POSITIVE_INFINITY;
        Vector3 point = new Vector3();
        Ray local = new Ray();
        Matrix4 inverse = new Matrix4();
        for (var piece : pieces) {
            inverse.set(piece.matrix()).inv();
            local.origin.set(ray.origin).mul(inverse);
            local.direction.set(ray.direction).rot(inverse);
            var shape = triangles.get(piece.node());
            if (!shape.isEmpty() && Intersector.intersectRayBoundsFast(local, bounds.get(piece.node()))
                  && Intersector.intersectRayTriangles(local, shape, point)) {
                point.mul(piece.matrix());
                float distance = point.dst2(ray.origin);
                if (distance < nearest) { nearest = distance; result.set(point); }
            }
        }
        return Float.isFinite(nearest);
    }

    int assemblyCount() { return assemblies.size(); }
    int interiorCount() { return interiors.size(); }

    @Override
    public void dispose() {
        interiors.values().forEach(Model::dispose);
        interiors.clear();
        models.stream().distinct().forEach(Model::dispose);
        models.clear();
        assemblies.clear();
        triangles.clear();
        farTriangles.clear();
        bounds.clear();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
