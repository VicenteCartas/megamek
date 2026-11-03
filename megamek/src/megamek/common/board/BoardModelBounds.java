/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import de.javagl.jgltf.model.v2.GltfModelCreatorV2;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/**
 * A placed object's model bounds, read without the views (the legacy conversion on the server): its GLB's finest level,
 * the group whose name ends {@code -lod0}, or the whole model without LOD groups, in board units with Z up as the 3D
 * board loads it; and the object's footprint on the board plane as placed. Invisible structural helpers are excluded.
 */
public final class BoardModelBounds {
    private static final MMLogger LOGGER = MMLogger.create(BoardModelBounds.class);
    private static final float[] NONE = new float[0];
    private static final Map<File, float[]> BOUNDS = new ConcurrentHashMap<>();

    private BoardModelBounds() { }

    /** Discard file-derived bounds before artwork is recaptured after an asset reload. */
    public static void reload() { BOUNDS.clear(); }

    /**
     * The GLB under {@code root} that holds {@code asset}, a model's path there without {@code .glb}: its own file, or
     * for a tree's winter form without one ({@code <species>-snow}), the bare species' file.
     */
    public static File file(File root, String asset) {
        if (asset.startsWith("buildings/")) { return TilesetScenery.customBuildingFile(asset); }
        File own = new File(root, asset + ".glb");
        File bare = asset.endsWith("-snow") ? new File(root, asset.substring(0, asset.length() - "-snow".length()) + ".glb") : null;
        return bare != null && !own.isFile() && bare.isFile() ? bare : own;
    }

    /** The model's bounds (min x, y, z, max x, y, z), read once per file; null when it is missing or unreadable. */
    public static float[] bounds(String asset) {
        File file = file(new File(Configuration.dataDir(), "models/board"), asset).getAbsoluteFile();
        float[] bounds = BOUNDS.computeIfAbsent(file, BoardModelBounds::read);
        return bounds == NONE ? null : bounds;
    }

    /**
     * The convex hull ({@link ConcreteFit#hull}) of the eight corners of an object's model bounds placed in hex {@code at}
     * as the 3D board places it: scaled and stretched (mirrored in x), turned, at its offset from the hex centre, in
     * board units at hex scale 1. Null when the model's bounds are unknown.
     */
    public static List<ConcreteFit.Point> footprint(Coords at, BoardDecoration object) {
        float[] bounds = bounds(object.asset());
        if (bounds == null) { return null; }
        double x = HexTile.centerX(at, HexTile.MODEL_WIDTH) + object.x() * HexTile.MODEL_WIDTH;
        double y = HexTile.centerY(at, HexTile.HEIGHT) + object.y() * HexTile.HEIGHT;
        var stretch = object.stretch();
        double scale = object.scale() * RailRoute.visualScale(object.asset());
        double sx = scale * stretch.x() * (object.mirror() ? -1 : 1), sy = scale * stretch.y();
        double sz = scale * stretch.z();
        List<ConcreteFit.Point> corners = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            double[] turned = object.rotateVector(sx * ((i & 1) == 0 ? bounds[0] : bounds[3]),
                  sy * ((i & 2) == 0 ? bounds[1] : bounds[4]), sz * ((i & 4) == 0 ? bounds[2] : bounds[5]));
            corners.add(new ConcreteFit.Point((float) (x + turned[0]), (float) (y + turned[1])));
        }
        return ConcreteFit.hull(corners);
    }

    private static float[] read(File file) {
        if (!file.isFile()) { return NONE; }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            var asset = new GltfAssetReader().readWithoutReferences(input);
            if (!(asset instanceof GltfAssetV2 binary)) { return NONE; }
            // Bounds need no textures: an external image is never read.
            binary.getImageReferences().forEach(image -> image.getTarget().accept(ByteBuffer.allocate(0)));
            var model = GltfModelCreatorV2.create(binary);
            float[] bounds = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                  Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            // Whole authored buildings can omit LOD groups; helpers never contribute to visual bounds.
            int scene = binary.getGltf().getScene() == null ? 0 : binary.getGltf().getScene();
            var roots = model.getSceneModels().get(scene).getNodeModels();
            boolean lods = roots.stream().anyMatch(root -> root.getName() != null && root.getName().endsWith("-lod0"));
            for (NodeModel root : roots) {
                if (!lods || root.getName() != null && root.getName().endsWith("-lod0")) { extend(root, bounds); }
            }
            return bounds[0] <= bounds[3] ? bounds : NONE;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Cannot read the bounds of {}: {}", file, failure.getMessage());
            return NONE;
        }
    }

    /** Every vertex of a node and its children under their global transforms, glTF's Y up turned to the board's Z up. */
    private static void extend(NodeModel node, float[] bounds) {
        if (BuildingVolumes.helper(node.getName())) { return; }
        float[] m = node.computeGlobalTransform(null);
        for (var mesh : node.getMeshModels()) {
            for (var primitive : mesh.getMeshPrimitiveModels()) {
                if (!(primitive.getAttributes().get("POSITION").getAccessorData() instanceof AccessorFloatData positions)) {
                    throw new IllegalArgumentException("Expected float positions");
                }
                for (int i = 0; i < positions.getNumElements(); i++) {
                    float px = positions.get(i, 0), py = positions.get(i, 1), pz = positions.get(i, 2);
                    float gx = m[0] * px + m[4] * py + m[8] * pz + m[12];
                    float gy = m[1] * px + m[5] * py + m[9] * pz + m[13];
                    float gz = m[2] * px + m[6] * py + m[10] * pz + m[14];
                    float[] point = { gx, -gz, gy };
                    for (int axis = 0; axis < 3; axis++) {
                        bounds[axis] = Math.min(bounds[axis], point[axis]);
                        bounds[axis + 3] = Math.max(bounds[axis + 3], point[axis]);
                    }
                }
            }
        }
        for (NodeModel child : node.getChildren()) { extend(child, bounds); }
    }
}
