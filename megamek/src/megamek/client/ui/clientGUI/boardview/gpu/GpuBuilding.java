/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

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
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
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
    /** Building-wide multiplicative tint, applied only to a placement's copied materials, never the shared kit. */
    static void tint(ModelInstance instance, BoardDecoration.Colours colours) {
        String value = colours.slot(0);
        if (value == null) { return; }
        var tint = GpuColor.linear(com.badlogic.gdx.graphics.Color.valueOf(value));
        for (var material : instance.materials) {
            if (AuthoredBoardClip.CAP.equals(material.id)) { continue; }
            var diffuse = material.get(ColorAttribute.class, ColorAttribute.Diffuse);
            if (diffuse == null) { material.set(ColorAttribute.createDiffuse(tint)); }
            else { diffuse.color.mul(tint); }
        }
    }

    static final float LEVEL_HEIGHT = BoardGeometry.MODEL_LEVEL_HEIGHT;
    private final ConnectedKit.Kit kit;
    private final List<Model> models = new ArrayList<>();
    private final Map<Recipe, Assembly> assemblies = new HashMap<>();
    // Per piece in its authored frame: near for picking and roof outlines, far for wall sections.
    private final Map<String, List<Vector3>> triangles = new HashMap<>();
    private final Map<String, List<Vector3>> farTriangles = new HashMap<>();
    private final Map<String, BoundingBox> bounds = new HashMap<>();
    private final Map<Interior, Model> interiors = new HashMap<>();

    private record Interior(List<List<Vector3>> footprints, int floors) { }
    record Recipe(List<String> modules, List<Integer> reach, BuildingInteriors interiors, boolean stepped) { }

    /** No expanded Model is cached: each level of detail places the shared kit's nodes ({@link #instance}). */
    record Assembly(Recipe recipe, List<ConnectedKit.Placement> near, Model interior, GpuBuilding kit) {
        int lodCount() { return kit.models.size(); }
        Model model(int lod) { return kit.models.get(lod); }
        ModelInstance instance(int lod) { return kit.instance(recipe, lod); }
        boolean hit(Ray ray, Vector3 result) { return kit.hit(near, ray, result); }
    }

    GpuBuilding(ConnectedKit.Kit kit, Function<ModelData, Model> upload) {
        this.kit = kit;
        var data = lods(kit.lods());
        try {
            for (int lod = 0; lod < data.size(); lod++) {
                int previous = data.indexOf(data.get(lod));
                models.add(previous < lod ? models.get(previous) : upload.apply(data.get(lod)));
            }
            // Every kit shares its picking geometry; only enterable buildings need wall/roof footprints.
            for (var piece : kit.pieces()) {
                Node node = models.getFirst().getNode(piece.node());
                triangles.put(piece.node(), GpuTerrain.triangles(List.of(node), part -> !part.material.id.endsWith(GeneratedMesh.BUILDING_FLOOR)));
                bounds.put(piece.node(), node.calculateBoundingBox(new BoundingBox()));
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
        var recipe = new Recipe(select(kit.roles(), levels, seed), Arrays.stream(reach).boxed().toList(), policy, stepped);
        return assemblies.computeIfAbsent(recipe, key -> {
            var near = placements(key, 0);
            if (policy == BuildingInteriors.NO) { return new Assembly(key, near, null, this); }
            var footprints = volumes(key);
            int floors = policy == BuildingInteriors.NO_FLOORS ? 1 : levels;
            Model interior = interiors.computeIfAbsent(new Interior(footprints, floors),
                  ignored -> GpuBuildingInterior.buildStoreys(footprints, LEVEL_HEIGHT, floors));
            return new Assembly(key, near, interior, this);
        });
    }

    private List<ConnectedKit.Placement> placements(Recipe recipe, int lod) {
        return ConnectedKit.placements(kit, lod, recipe.modules(),
              recipe.reach().stream().mapToInt(Integer::intValue).toArray(), 0, recipe.stepped());
    }

    /**
     * The occupied volume: the roof projection within each storey's generated floor cap. Authored kits without caps
     * use their closed mid-storey wall outlines from the simplest LOD. A storey whose walls close nothing (open towards
     * connected neighbours) leaves the roof projection as it is.
     */
    private List<List<Vector3>> volumes(Recipe recipe) {
        var pieces = placements(recipe, 0);
        List<Area> storeys = new ArrayList<>();
        for (int level = 1; level < recipe.modules().size(); level++) { storeys.add(new Area()); }
        // These caps already fit behind the facade, including open connection ends. Closed panel boxes describe
        // wall strips, not rooms, so only kits without floor caps need the wall-section fallback below.
        boolean floorCaps = false;
        for (var piece : pieces) {
            if (piece.role().startsWith("roof")) { continue; }
            var floor = GpuTerrain.triangles(List.of(models.getFirst().getNode(piece.node())),
                  part -> part.material.id.endsWith(GeneratedMesh.BUILDING_FLOOR));
            floorCaps |= !floor.isEmpty();
            storeys.get(piece.level()).add(GpuBuildingInterior.area(placed(piece, floor)));
        }
        if (!floorCaps) {
            int far = models.size() - 1;
            for (var piece : placements(recipe, far)) {
                if (piece.role().startsWith("roof")) { continue; }
                var shape = farTriangles.computeIfAbsent(piece.node(),
                      node -> GpuTerrain.triangles(List.of(models.get(far).getNode(node)),
                            part -> !part.material.id.equals(GeneratedMesh.BUILDING_BODY)));
                storeys.get(piece.level()).add(GpuBuildingInterior.walls(placed(piece, shape),
                      (piece.level() + .5f) * LEVEL_HEIGHT));
            }
        }
        List<List<Vector3>> result = new ArrayList<>();
        for (int level = 0; level < storeys.size(); level++) {
            Area roof = new Area();
            for (var piece : pieces) {
                if (piece.role().startsWith("roof") && piece.level() > level) {
                    roof.add(GpuBuildingInterior.area(placed(piece, triangles.get(piece.node()))));
                }
            }
            require(!roof.isEmpty(), kit.lods().getFirst().id + " requires a roof footprint above storey " + level);
            // Each floor follows its own shell. A lower terrace must not enlarge the floors above it.
            Area storey = storeys.get(level);
            if (!storey.isEmpty()) { roof.intersect(storey); }
            result.add(GpuBuildingInterior.triangles(roof));
        }
        return List.copyOf(result);
    }

    private static List<Vector3> placed(ConnectedKit.Placement piece, List<Vector3> triangles) {
        return triangles.stream().map(point -> new Vector3(point).mul(piece.matrix())).toList();
    }

    /** Create only the scene instance, at load/edit or a LOD transition; mesh buffers stay in the shared kit. */
    private ModelInstance instance(Recipe recipe, int lod) {
        Model source = models.get(lod);
        ModelInstance instance = new ModelInstance(source, new String[0]);
        // Placement materials are needed for cutaway opacity; their textures remain shared.
        for (var material : source.materials) { instance.materials.add(material.copy()); }
        for (var piece : placements(recipe, lod)) {
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
