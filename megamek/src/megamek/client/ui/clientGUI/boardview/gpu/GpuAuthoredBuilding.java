/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.TilesetScenery;

/** One authored placement, assembled in full then cut into hex-owned meshes. Collapsed neighbours do not change the cut. */
final class GpuAuthoredBuilding {
    record Layout(Coords cell, Coords anchor, BoardDecoration object, double base, int ground, int height,
          BuildingInteriors interiors, List<Coords> members, int boardWidth, int boardHeight) { }
    record Slice(List<Model> levels, Model interior, List<Vector3> roof) {
        void dispose() { levels.stream().distinct().forEach(Model::dispose); if (interior != null) { interior.dispose(); } }
    }
    /** CPU geometry prepared with the terrain snapshot; only upload and small interior meshes touch GL. */
    record Prepared(Layout layout, List<ModelData> levels, List<List<Vector3>> floors, List<Vector3> columns,
          List<Vector3> roof) { }
    private record Assembly(String asset, List<BuildingVolumes.Module> modules) { }
    private record Placement(Coords anchor, BoardDecoration object, double base, int width, int height) { }
    private record Geometry(ModelData source, GeneratedMesh mesh) { }
    private record Whole(List<Geometry> levels, List<BuildingVolumes.Solid> solids, Map<Double, Area> roofs,
          Map<Double, Area> sections) { }
    /** Derived CPU geometry shared by workers of one immutable tile snapshot; never owns GPU resources. */
    private record Cached(WeakReference<List<BoardScene.Tile>> tiles, int width, int height,
          Map<String, List<Coords>> members, Map<Placement, Whole> placements, Map<Layout, Prepared> slices) { }
    // Keep the current and previous snapshots: route previews compare both. Do not retain every intermediate drag.
    private static final List<Cached> SCENES = new ArrayList<>();
    private static final Map<String, List<ModelData>> FILES = new ConcurrentHashMap<>();
    private static final Map<Assembly, List<ModelData>> ASSEMBLIES = new ConcurrentHashMap<>();

    static synchronized void reload() { FILES.clear(); ASSEMBLIES.clear(); SCENES.clear(); }

    private static synchronized Cached cached(BoardScene scene) {
        SCENES.removeIf(cache -> cache.tiles().get() == null);
        for (var cache : SCENES) {
            if (cache.tiles().get() == scene.tiles() && cache.width() == scene.width() && cache.height() == scene.height()) { return cache; }
        }
        Map<String, List<Coords>> members = new java.util.HashMap<>();
        for (var tile : scene.tiles()) {
            var reference = tile.cover().reference();
            if (reference != null) { members.computeIfAbsent(reference.id(), ignored -> new ArrayList<>()).add(tile.coords()); }
        }
        members.replaceAll((id, coords) -> List.copyOf(coords));
        var cache = new Cached(new WeakReference<>(scene.tiles()), scene.width(), scene.height(), Map.copyOf(members),
              new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        if (SCENES.size() == 2) { SCENES.removeFirst(); }
        SCENES.add(cache);
        return cache;
    }

    static Map<Layout, Prepared> prepare(BoardScene scene, List<BoardScene.Tile> tiles) {
        Map<Layout, Prepared> result = new java.util.LinkedHashMap<>();
        Cached cache = cached(scene);
        for (var tile : tiles) {
            for (var feature : tile.features()) {
                if (!supports(feature)) { continue; }
                Layout layout = layout(scene, tile, feature);
                var key = new Placement(layout.anchor(), layout.object(), layout.base(), scene.width(), scene.height());
                var whole = cache.placements().computeIfAbsent(key, ignored -> placement(layout,
                      FILES.computeIfAbsent(feature.asset(), GpuAuthoredBuilding::load)));
                result.put(layout, cache.slices().computeIfAbsent(layout, ignored -> slice(layout, whole)));
            }
        }
        return Map.copyOf(result);
    }

    private GpuAuthoredBuilding() { }

    static boolean supports(BoardScene.Feature feature) {
        return (feature.kind() == BoardScene.FeatureKind.BUILDING || feature.kind() == BoardScene.FeatureKind.PROP) && feature.decoration() != null
              && BuildingVolumes.of(feature.asset()).present();
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        if (feature.kind() == BoardScene.FeatureKind.PROP) {
            return new Layout(tile.coords(), tile.coords(), feature.decoration(), feature.elevation(), tile.elevation(),
                  0, BuildingInteriors.NO, List.of(tile.coords()), scene.width(), scene.height());
        }
        var reference = tile.cover().reference();
        var members = cached(scene).members().get(reference.id());
        return new Layout(tile.coords(), reference.anchor(), feature.decoration(), feature.elevation(), tile.elevation(),
              tile.cover().height(), tile.cover().interiors(), members, scene.width(), scene.height());
    }

    static List<ModelData> load(String asset) {
        return RigidGlb.loadLods(new FileHandle(TilesetScenery.customBuildingFile(asset)),
              new File(Configuration.dataDir(), "models").toPath());
    }

    /** Palette cards assemble the same modules as the placed building, before any hex cuts. */
    static ModelData preview(String asset) {
        var source = load(asset).getFirst();
        return assemble(source, BuildingVolumes.of(asset), 3, 0, new Matrix4()).data(asset, source);
    }

    private static GeneratedMesh assemble(ModelData source, BuildingVolumes.Definition definition, int levels, long seed, Matrix4 frame) {
        var result = new GeneratedMesh();
        for (var module : definition.modules(levels, seed)) {
            Matrix4 placed = new Matrix4(frame).translate(0, 0, (float) module.lift());
            for (int i = 0; i < source.nodes.size; i++) {
                var node = source.nodes.get(i);
                if (definition.modular()) { module(result, source, node, placed, module.role()); }
                else { result.placed(source, node, placed); }
            }
        }
        return result;
    }

    private static void module(GeneratedMesh target, ModelData source, ModelNode node, Matrix4 frame, String role) {
        if (BuildingVolumes.role(node.id).equals(role)) { target.placed(source, node, frame); return; }
        Matrix4 parent = new Matrix4(frame).mul(new Matrix4(node.translation == null ? Vector3.Zero : node.translation,
              node.rotation == null ? new Quaternion() : node.rotation, node.scale == null ? new Vector3(1, 1, 1) : node.scale));
        if (node.children != null) { for (var child : node.children) { module(target, source, child, parent, role); } }
    }

    static Slice build(Layout layout, List<ModelData> source, Function<ModelData, Model> upload) {
        return build(prepare(layout, source), upload);
    }

    static Slice build(Prepared prepared, Function<ModelData, Model> upload) {
        List<Model> levels = new ArrayList<>();
        Model interior = null;
        try {
            for (ModelData lod : prepared.levels()) { levels.add(upload.apply(lod)); }
            if (prepared.layout().interiors() != BuildingInteriors.NO) { interior = interior(prepared); }
            return new Slice(List.copyOf(levels), interior, prepared.roof());
        } catch (RuntimeException | Error failure) {
            levels.forEach(Model::dispose);
            if (interior != null) { interior.dispose(); }
            throw failure;
        }
    }

    static Prepared prepare(Layout layout, List<ModelData> source) {
        return slice(layout, placement(layout, source));
    }

    /** Cut and seal the whole placement first. Internal member cuts remain open for floors and destruction. */
    private static Whole placement(Layout layout, List<ModelData> source) {
        var definition = BuildingVolumes.of(layout.object().asset());
        var object = layout.object();
        var frame = frame(layout.anchor(), object, layout.base());
        var assembly = new Assembly(object.asset(), definition.modules(object.levels(), BuildingVolumes.seed(object)));
        var assembled = ASSEMBLIES.computeIfAbsent(assembly, ignored -> GpuBuilding.lods(source).stream()
              .<ModelData>map(lod -> assemble(lod, definition, object.levels(), BuildingVolumes.seed(object), new Matrix4())
                    .data(object.asset(), lod)).toList());
        var solids = BuildingVolumes.placed(layout.anchor(), object, layout.base());
        List<Geometry> levels = new ArrayList<>();
        for (ModelData lod : assembled) {
            GeneratedMesh whole = new GeneratedMesh();
            for (int i = 0; i < lod.nodes.size; i++) { whole.placed(lod, lod.nodes.get(i), frame); }
            var clipped = AuthoredBoardClip.clip(whole, lod, layout.boardWidth(), layout.boardHeight(), solids);
            var mesh = new GeneratedMesh();
            for (var node : clipped.nodes) { mesh.placed(clipped, node, new Matrix4()); }
            // Only its material metadata is needed after conversion; do not retain a second copy of every vertex.
            clipped.meshes.clear(); clipped.nodes.clear();
            levels.add(new Geometry(clipped, mesh));
        }
        return new Whole(List.copyOf(levels), solids, BuildingVolumes.roofs(solids), new ConcurrentHashMap<>());
    }

    /** The authored frame in model units, shared with the immediate editor transform preview. */
    static Matrix4 frame(Coords anchor, BoardDecoration object, double base) {
        float x = HexTile.centerX(anchor, HexTile.MODEL_WIDTH) + (float) object.x() * HexTile.MODEL_WIDTH;
        float y = HexTile.centerY(anchor, HexTile.HEIGHT) + (float) object.y() * HexTile.HEIGHT;
        return new Matrix4().translate(x, y, (float) base * HexTile.LEVEL_HEIGHT)
              .rotate(Vector3.Z, (float) object.rotation()).rotate(Vector3.Y, (float) object.rotationY()).rotate(Vector3.X, (float) object.rotationX())
              .scale((float) (object.scale() * object.stretch().x() * (object.mirror() ? -1 : 1)),
                    (float) (object.scale() * object.stretch().y()), (float) (object.scale() * object.stretch().z()));
    }

    private static Prepared slice(Layout layout, Whole placed) {
        var object = layout.object();
        float cx = HexTile.centerX(layout.cell(), HexTile.MODEL_WIDTH), cy = HexTile.centerY(layout.cell(), HexTile.HEIGHT);
        List<GeneratedMesh.Keep> boundaries = new ArrayList<>();
        // The Voronoi cell of the original occupied hex centres equals their shared hex edges. At outside edges it
        // also owns the visual fringe, so decorative overhangs disappear with a member rather than becoming scraps.
        for (Coords other : layout.members().stream().sorted(Comparator.comparingDouble(at -> {
            double x = HexTile.centerX(at, HexTile.MODEL_WIDTH) - cx, y = HexTile.centerY(at, HexTile.HEIGHT) - cy;
            return x * x + y * y;
        })).toList()) {
            if (other.equals(layout.cell())) { continue; }
            double dx = HexTile.centerX(other, HexTile.MODEL_WIDTH) - cx, dy = HexTile.centerY(other, HexTile.HEIGHT) - cy;
            double length = Math.hypot(dx, dy);
            boundaries.add((px, py) -> length * .5 - (px - cx) * (dx / length) - (py - cy) * (dy / length));
        }
        List<ModelData> levels = new ArrayList<>();
        for (Geometry lod : placed.levels()) {
            // Bound the otherwise unbounded Voronoi region by this placement's visual envelope, once per share.
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (var part : lod.mesh().parts.values()) {
                for (int i = 0; i < part.size; i += RigidGlb.STRIDE) {
                    minX = Math.min(minX, part.items[i]); maxX = Math.max(maxX, part.items[i]);
                    minY = Math.min(minY, part.items[i + 1]); maxY = Math.max(maxY, part.items[i + 1]);
                }
            }
            List<float[]> polygon = List.of(new float[] {minX - 1, minY - 1}, new float[] {maxX + 1, minY - 1},
                  new float[] {maxX + 1, maxY + 1}, new float[] {minX - 1, maxY + 1});
            for (var boundary : boundaries) { polygon = GeneratedMesh.clip(polygon, boundary); }
            minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE;
            for (var point : polygon) {
                minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
            }
            // Only planes touching the final convex region can cut it; distant members are redundant.
            List<float[]> region = polygon;
            var active = boundaries.stream().filter(plane -> region.stream().anyMatch(p -> Math.abs(plane.at(p[0], p[1])) < .02)).toList();
            var share = polygon.isEmpty() ? new GeneratedMesh() : lod.mesh().clipped(active, minX - .01f, minY - .01f, maxX + .01f, maxY + .01f);
            for (var part : share.parts.values()) {
                for (int i = 0; i < part.size; i += RigidGlb.STRIDE) { part.items[i] -= cx; part.items[i + 1] -= cy; }
            }
            levels.add(share.data("authored-" + object.id() + "-" + layout.cell(), lod.source()));
        }
        if (layout.height() == 0) { return new Prepared(layout, List.copyOf(levels), List.of(), List.of(), List.of()); }
        Area cell = BuildingVolumes.hex(layout.cell());
        Area interiorCell = new Area(cell);
        // Floor slab edges must sit behind the neutral map cap, not flicker through its coincident plane.
        for (int edge = 0; edge < 6; edge++) {
            Coords neighbour = layout.cell().translated(HexTile.edgeDirection(edge));
            if (neighbour.getX() >= 0 && neighbour.getY() >= 0 && neighbour.getX() < layout.boardWidth() && neighbour.getY() < layout.boardHeight()) { continue; }
            int next = (edge + 1) % 6;
            var boundary = new Line2D.Double(HexTile.cornerX(layout.cell(), edge, HexTile.MODEL_WIDTH), HexTile.cornerY(layout.cell(), edge, HexTile.HEIGHT),
                  HexTile.cornerX(layout.cell(), next, HexTile.MODEL_WIDTH), HexTile.cornerY(layout.cell(), next, HexTile.HEIGHT));
            interiorCell.subtract(new Area(new BasicStroke(.08f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER).createStrokedShape(boundary)));
        }
        List<List<Vector3>> floors = new ArrayList<>();
        List<Vector3> columns = new ArrayList<>();
        if (layout.interiors() != BuildingInteriors.NO) {
            for (int level = 0; level < layout.height(); level++) {
                float z = (layout.ground() + level) * HexTile.LEVEL_HEIGHT;
                if (level == 0 || layout.interiors() == BuildingInteriors.YES) {
                    Area floor = new Area(placed.sections().computeIfAbsent(z + .02, height -> BuildingVolumes.section(placed.solids(), height))); floor.intersect(interiorCell);
                    floor.transform(AffineTransform.getTranslateInstance(-cx, -cy));
                    floors.add(GpuBuildingInterior.triangles(floor));
                }
                Area middle = new Area(placed.sections().computeIfAbsent(z + HexTile.LEVEL_HEIGHT * .5, height -> BuildingVolumes.section(placed.solids(), height))); middle.intersect(interiorCell);
                var bounds = middle.getBounds2D();
                for (double px = Math.ceil(bounds.getMinX() / 22) * 22; px < bounds.getMaxX(); px += 22) {
                    for (double py = Math.ceil(bounds.getMinY() / 22) * 22; py < bounds.getMaxY(); py += 22) {
                        if (middle.contains(px - .8, py - .8, 1.6, 1.6)) { columns.add(new Vector3((float) px - cx, (float) py - cy, z + 9)); }
                    }
                }
            }
        }
        Area roof = new Area();
        placed.roofs().forEach((z, area) -> {
            if (Math.ceil(z / HexTile.LEVEL_HEIGHT - 1e-5) == layout.ground() + layout.height()) { roof.add(area); }
        });
        roof.intersect(cell);
        roof.transform(AffineTransform.getTranslateInstance(-cx, -cy));
        List<Vector3> standing = new ArrayList<>();
        float roofZ = (layout.ground() + layout.height()) * HexTile.LEVEL_HEIGHT;
        for (var triangle : BoardTacticalGeometry.flat(roof, 0)) {
            standing.add(new Vector3(triangle.a()).set(triangle.a().x, triangle.a().y, roofZ));
            standing.add(new Vector3(triangle.b()).set(triangle.b().x, triangle.b().y, roofZ));
            standing.add(new Vector3(triangle.c()).set(triangle.c().x, triangle.c().y, roofZ));
        }
        return new Prepared(layout, List.copyOf(levels), List.copyOf(floors), List.copyOf(columns), List.copyOf(standing));
    }

    private static Model interior(Prepared prepared) {
        Layout layout = prepared.layout();
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        ModelBuilder builder = new ModelBuilder(); builder.begin();
        builder.node().id = "struts";
        if (!prepared.columns().isEmpty()) {
            MeshPartBuilder struts = builder.part("struts", GL20.GL_TRIANGLES, attributes,
                  new Material("struts", ColorAttribute.createDiffuse(.29f, .31f, .33f, 1)));
            for (var column : prepared.columns()) {
                BoxShapeBuilder.build(struts, column.x, column.y, column.z, 1.6f, 1.6f, 18);
            }
        }
        builder.node().id = "floors";
        var material = new Material("floors", ColorAttribute.createDiffuse(.58f, .56f, .52f, 1));
        for (int level = 0; level < prepared.floors().size(); level++) {
            var triangles = prepared.floors().get(level);
            if (triangles.isEmpty()) { continue; }
            var floor = builder.part("floor-" + level, GL20.GL_TRIANGLES, attributes, material);
            GpuBuildingInterior.floor(floor, triangles, floorHeight(layout, level));
        }
        return builder.end();
    }

    static float floorHeight(Layout layout, int level) {
        return (layout.ground() + level) * HexTile.LEVEL_HEIGHT + (level == 0 ? .04f : 0);
    }
}
