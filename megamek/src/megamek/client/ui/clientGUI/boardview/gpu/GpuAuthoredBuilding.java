/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.io.File;
import java.util.ArrayList;
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
    private static final Map<String, List<ModelData>> FILES = new ConcurrentHashMap<>();
    private static final Map<Assembly, List<ModelData>> ASSEMBLIES = new ConcurrentHashMap<>();

    static void reload() { FILES.clear(); ASSEMBLIES.clear(); }

    static Map<Layout, Prepared> prepare(BoardScene scene, List<BoardScene.Tile> tiles) {
        Map<Layout, Prepared> result = new java.util.LinkedHashMap<>();
        Map<Placement, List<ModelData>> placements = new java.util.HashMap<>();
        for (var tile : tiles) {
            for (var feature : tile.features()) {
                if (!supports(feature)) { continue; }
                Layout layout = layout(scene, tile, feature);
                var key = new Placement(layout.anchor(), layout.object(), layout.base(), scene.width(), scene.height());
                var whole = placements.computeIfAbsent(key, ignored -> placement(layout,
                      FILES.computeIfAbsent(feature.asset(), GpuAuthoredBuilding::load)));
                result.put(layout, slice(layout, whole));
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
        var members = scene.tiles().stream().filter(t -> t.cover().reference() != null
              && t.cover().reference().id().equals(reference.id())).map(BoardScene.Tile::coords).toList();
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
    private static List<ModelData> placement(Layout layout, List<ModelData> source) {
        var definition = BuildingVolumes.of(layout.object().asset());
        var object = layout.object();
        float x = HexTile.centerX(layout.anchor(), HexTile.MODEL_WIDTH) + (float) object.x() * HexTile.MODEL_WIDTH;
        float y = HexTile.centerY(layout.anchor(), HexTile.HEIGHT) + (float) object.y() * HexTile.HEIGHT;
        var frame = new Matrix4().translate(x, y, (float) layout.base() * HexTile.LEVEL_HEIGHT)
              .rotate(Vector3.Z, (float) object.rotation()).rotate(Vector3.Y, (float) object.rotationY()).rotate(Vector3.X, (float) object.rotationX())
              .scale((float) (object.scale() * object.stretch().x() * (object.mirror() ? -1 : 1)),
                    (float) (object.scale() * object.stretch().y()), (float) (object.scale() * object.stretch().z()));
        var assembly = new Assembly(object.asset(), definition.modules(object.levels(), BuildingVolumes.seed(object)));
        var assembled = ASSEMBLIES.computeIfAbsent(assembly, ignored -> GpuBuilding.lods(source).stream()
              .<ModelData>map(lod -> assemble(lod, definition, object.levels(), BuildingVolumes.seed(object), new Matrix4())
                    .data(object.asset(), lod)).toList());
        var solids = BuildingVolumes.placed(layout.anchor(), object, layout.base());
        List<ModelData> levels = new ArrayList<>();
        for (ModelData lod : assembled) {
            GeneratedMesh whole = new GeneratedMesh();
            for (int i = 0; i < lod.nodes.size; i++) { whole.placed(lod, lod.nodes.get(i), frame); }
            levels.add(AuthoredBoardClip.clip(whole, lod, layout.boardWidth(), layout.boardHeight(), solids));
        }
        return List.copyOf(levels);
    }

    private static Prepared slice(Layout layout, List<ModelData> placed) {
        var object = layout.object();
        float cx = HexTile.centerX(layout.cell(), HexTile.MODEL_WIDTH), cy = HexTile.centerY(layout.cell(), HexTile.HEIGHT);
        List<GeneratedMesh.Keep> boundaries = new ArrayList<>();
        // The Voronoi cell of the original occupied hex centres equals their shared hex edges. At outside edges it
        // also owns the visual fringe, so decorative overhangs disappear with a member rather than becoming scraps.
        for (Coords other : layout.members()) {
            if (other.equals(layout.cell())) { continue; }
            double dx = HexTile.centerX(other, HexTile.MODEL_WIDTH) - cx, dy = HexTile.centerY(other, HexTile.HEIGHT) - cy;
            boundaries.add((px, py) -> (dx * dx + dy * dy) * .5 - px * dx - py * dy);
        }
        List<ModelData> levels = new ArrayList<>();
        for (ModelData lod : placed) {
            GeneratedMesh whole = new GeneratedMesh();
            for (int i = 0; i < lod.nodes.size; i++) { whole.placed(lod, lod.nodes.get(i), new Matrix4().translate(-cx, -cy, 0)); }
            levels.add(whole.clipped(boundaries).data("authored-" + object.id() + "-" + layout.cell(), lod));
        }
        if (layout.height() == 0) { return new Prepared(layout, List.copyOf(levels), List.of(), List.of(), List.of()); }
        var solids = BuildingVolumes.placed(layout.anchor(), object, layout.base());
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
                    Area floor = BuildingVolumes.section(solids, z + .02); floor.intersect(interiorCell);
                    floor.transform(AffineTransform.getTranslateInstance(-cx, -cy));
                    floors.add(GpuBuildingInterior.triangles(floor));
                }
                Area middle = BuildingVolumes.section(solids, z + HexTile.LEVEL_HEIGHT * .5); middle.intersect(interiorCell);
                var bounds = middle.getBounds2D();
                for (double px = Math.ceil(bounds.getMinX() / 22) * 22; px < bounds.getMaxX(); px += 22) {
                    for (double py = Math.ceil(bounds.getMinY() / 22) * 22; py < bounds.getMaxY(); py += 22) {
                        if (middle.contains(px - .8, py - .8, 1.6, 1.6)) { columns.add(new Vector3((float) px - cx, (float) py - cy, z + 9)); }
                    }
                }
            }
        }
        Area roof = new Area();
        BuildingVolumes.roofs(solids).forEach((z, area) -> {
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
            float z = (layout.ground() + level) * HexTile.LEVEL_HEIGHT + (level == 0 ? .04f : 0);
            GpuBuildingInterior.floor(floor, triangles, z);
        }
        return builder.end();
    }
}
