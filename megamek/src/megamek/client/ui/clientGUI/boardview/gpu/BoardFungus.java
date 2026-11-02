/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Configuration;

/** Authored fungal cover and cosmetic colonies mounted on the shared, finished cliff mesh. */
final class BoardFungus {
    static final List<String> COVER = List.of("fungus/cup-chalice", "fungus/cup-trumpet", "fungus/cup-frilled",
          "fungus/cup-forked", "fungus/spore-round", "fungus/spore-pear", "fungus/spore-cluster");
    static final List<String> CUPS = COVER.subList(0, 4);
    static final List<String> SPORES = COVER.subList(4, COVER.size());
    static final List<String> SCATTER = List.of("fungus/scatter-cups", "fungus/scatter-buds",
          "fungus/scatter-spores", "fungus/scatter-mixed");
    static final List<String> CLIFF = List.of("fungus/cliff-shelf", "fungus/cliff-tiered",
          "fungus/cliff-cups", "fungus/cliff-mycelium");
    private static final BoardKit<Map<String, Mount>> MOUNTS = new BoardKit<>(BoardFungus::loadMounts);
    private static final BoardKit<Map<String, List<Vent>>> VENTS = new BoardKit<>(BoardFungus::loadVents);

    /** Model-space cup outlets from GLB empties; shared by every geometry LOD. */
    record Vent(Vector3 origin, float radius) { }

    static List<Vent> vents(String asset) { return CUPS.contains(asset) ? VENTS.get().get(asset) : List.of(); }

    private static Map<String, List<Vent>> loadVents() {
        var result = new LinkedHashMap<String, List<Vent>>();
        File root = new File(Configuration.dataDir(), "models/board");
        for (String asset : CUPS) {
            var vents = new ArrayList<Vent>();
            for (var frame : RigidGlb.sporeVents(new File(root, asset + ".glb"))) {
                var scale = frame.getScale(new Vector3());
                float radius = Math.min(scale.x, scale.y);
                if (!(radius > 0 && Float.isFinite(radius))) { throw new IllegalArgumentException("Invalid spore radius: " + asset); }
                vents.add(new Vent(frame.getTranslation(new Vector3()), radius));
            }
            if (vents.isEmpty()) { throw new IllegalArgumentException("Fungus has no authored spore outlets: " + asset); }
            result.put(asset, List.copyOf(vents));
        }
        return Map.copyOf(result);
    }

    /** The authored attachment side is local Y >= 0; outward growth faces -Y. No duplicated asset dimensions. */
    private record Mount(List<Vector3> roots, float reach) { }

    private static Map<String, Mount> loadMounts() {
        Map<String, Mount> mounts = new LinkedHashMap<>();
        for (String asset : CLIFF) {
            var roots = new LinkedHashSet<Vector3>();
            float reach = 0;
            for (var polygon : BoardShape.loadModel(asset).polygons()) {
                for (Vector3 point : polygon.points()) {
                    if (point.y >= 0) { roots.add(point); }
                    reach = Math.max(reach, -point.y);
                }
            }
            mounts.put(asset, new Mount(List.copyOf(roots), reach));
        }
        return Map.copyOf(mounts);
    }

    private BoardFungus() { }

    static boolean asset(String name) { return name.startsWith("fungus/"); }

    static void reload() { MOUNTS.reload(); VENTS.reload(); }

    record Placement(String asset, Matrix4 transform) { }

    /** No new terrain or cover is inferred: only existing exposed walls receive these decorative props. */
    static List<Placement> cliffs(BoardScene scene, BoardScene.Tile tile, List<BoardSurface.Face> faces) {
        if (tile.ultraSublevel() || tile.surface() != BoardScene.Surface.FUNGUS
              || tile.liquid().present() || !tile.detailedGround()) {
            return List.of();
        }
        List<Placement> result = new ArrayList<>();
        float unit = BoardGeometry.hexScale();
        for (int edge = 0; edge < 6; edge++) {
            int direction = BoardGeometry.edgeDirection(edge);
            var lower = scene.tile(tile.coords().translated(direction));
            // The board's cutaway perimeter is not an ecological cliff. Keep pit, road and water mouths clear.
            if (lower == null || lower.ultraSublevel() || lower.groundLevel() >= tile.groundLevel() || lower.liquid().present()
                  || (tile.wayExits() & (1 << direction)) != 0
                  || (lower.wayExits() & (1 << ((direction + 3) % 6))) != 0) {
                continue;
            }
            int drop = tile.groundLevel() - lower.groundLevel();
            Random random = new Random(tile.coords().getX() * 73_856_093L ^ tile.coords().getY() * 19_349_663L
                  ^ edge * 83_492_791L ^ 0xf091L);
            // Use the exposed height, not the hex's absolute elevation. Small steps are almost always bare.
            float occupancy = drop == 1 ? .06f : drop == 2 ? .25f : .8f;
            if (random.nextFloat() >= occupancy) { continue; }
            List<BoardSurface.Face> wall = new ArrayList<>();
            List<Float> areas = new ArrayList<>();
            float area = 0;
            for (var face : faces) {
                if (face.landEdge() != edge || face.finish() != BoardSurface.Finish.WALL) { continue; }
                Vector3 normal = face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a()));
                float size = normal.len();
                if (size == 0 || Math.abs(normal.z / size) > .6f) { continue; }
                area += size;
                wall.add(face);
                areas.add(area);
            }
            if (wall.isEmpty()) { continue; }
            int count = drop >= 3 ? 2 : 1;
            List<Vector3> placed = new ArrayList<>();
            for (int attempt = 0; attempt < count * 12 && placed.size() < count; attempt++) {
                float target = random.nextFloat() * area;
                int index = 0;
                while (index < areas.size() - 1 && target > areas.get(index)) { index++; }
                var face = wall.get(index);
                float u = (float) Math.sqrt(random.nextFloat()), v = random.nextFloat();
                Vector3 point = face.a().cpy().scl(1 - u).mulAdd(face.b(), u * (1 - v)).mulAdd(face.c(), u * v);
                if (point.z < lower.groundLevel() * BoardGeometry.level() + 5 * unit
                      || point.z > tile.elevation() * BoardGeometry.level() - 4 * unit
                      || placed.stream().anyMatch(previous -> previous.dst2(point) < 144 * unit * unit)) { continue; }
                Vector3 normal = face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a()));
                normal.z = 0;
                normal.nor();
                Vector3 outward = new Vector3(BoardGeometry.centerX(lower.coords()) - BoardGeometry.centerX(tile.coords()),
                      BoardGeometry.centerY(lower.coords()) - BoardGeometry.centerY(tile.coords()), 0);
                if (normal.dot(outward) < 0) { normal.scl(-1); }
                float scale = (.7f + .35f * random.nextFloat()) * unit;
                // Keep the cluster upright, but fit its entire attachment side, not just the model origin.
                float turn = (float) Math.toDegrees(Math.atan2(normal.x, -normal.y));
                Matrix4 transform = new Matrix4().setToTranslation(point).rotate(Vector3.Z, turn).scale(scale, scale, scale);
                String asset = CLIFF.get(random.nextInt(CLIFF.size()));
                if (!embed(MOUNTS.get().get(asset), transform, normal, scale, faces)) { continue; }
                placed.add(point);
                result.add(new Placement(asset, transform));
            }
        }
        return List.copyOf(result);
    }

    /** Bury all rear contacts in the drawn wall. Retry a different site if a cluster spans a gap or deep recess. */
    private static boolean embed(Mount mount, Matrix4 transform, Vector3 outward, float scale,
          List<BoardSurface.Face> faces) {
        float limit = mount.reach() * scale * .45f;
        float sink = 0;
        Vector3 point = new Vector3(), hit = new Vector3();
        Ray ray = new Ray(new Vector3(), outward.cpy().scl(-1));
        for (Vector3 root : mount.roots()) {
            point.set(root).mul(transform);
            ray.origin.set(point).mulAdd(outward, limit);
            float nearest = Float.POSITIVE_INFINITY;
            for (var face : faces) {
                if (face.finish() != BoardSurface.Finish.WALL
                      || point.z < Math.min(face.a().z, Math.min(face.b().z, face.c().z))
                      || point.z > Math.max(face.a().z, Math.max(face.b().z, face.c().z))) { continue; }
                if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)) {
                    nearest = Math.min(nearest, ray.origin.dst(hit));
                }
            }
            sink = Math.max(sink, nearest - limit + .12f * scale);
            if (sink > limit) { return false; }
        }
        transform.trn(-outward.x * sink, -outward.y * sink, 0);
        return !mount.roots().isEmpty();
    }
}
