/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.board.HexTile;
import megamek.common.enums.BuildingType;

/**
 * Authored fortress kits assembled identically for drawing, picking and dressing clearance. Ground cover grows up to a
 * military wall's body ({@link #footprint}) and everywhere under a civilian wall's net.
 */
final class BoardWall {
    static final int LODS = 2;
    private static final String PREFIX = "buildings/wall-";

    private BoardWall() { }

    enum Family {
        CIVILIAN("civilian"), LIGHT("light"), MEDIUM("medium"), HEAVY("heavy"), HARDENED("hardened"),
        CASTLE_BRIAN("castle_brian");

        final String folder;

        Family(String folder) { this.folder = folder; }
    }

    /** A wall's look is its weight's ({@link megamek.common.board.TilesetScenery#buildingWeight}): 0 civilian to 5 Castle Brian. */
    static Family family(int weight) {
        return Family.values()[Math.clamp(weight, 0, Family.values().length - 1)];
    }

    /** Only generated walls change geometry when an edit crosses a family boundary; model overrides stay authored. */
    static Family family(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> supports(feature.asset())) ? family(tile.cover().wallWeight()) : null;
    }

    static boolean supports(String asset) {
        return megamek.common.board.BoardGenerator.of(asset) == megamek.common.board.BoardGenerator.WALL;
    }

    /** An authored model changes a wall's appearance, not its solid building rules. */
    static boolean building(BoardScene.Cover cover, BoardScene.Feature feature) {
        return feature.kind() == BoardScene.FeatureKind.BUILDING
              && (supports(feature.asset()) || cover.structure() == BuildingType.WALL.getTypeValue());
    }

    /** A reload publishes new immutable source data; in-flight terrain workers keep their existing copies. */
    static void reload() { ConnectedKit.reload(); }

    private static ConnectedKit.Kit kit(Family family, String name) {
        File models = new File(Configuration.dataDir(), "models");
        // Textures may be the board's shared materials (the light walls' corrugated sheets).
        return ConnectedKit.kit(new File(models, "buildings/walls/" + family.folder + "/" + name + ".glb"), models.toPath());
    }

    /** Derived from the captured board; shared by CPU clearance, picking and the GPU model cache. */
    record Layout(String asset, Family family, int height, List<Integer> arms) {
        Layout { arms = List.copyOf(arms); }
    }

    static Layout layout(String asset, int height) {
        return layout(asset, height, Family.CIVILIAN);
    }

    static Layout layout(String asset, int height, Family family) {
        int levels = Math.max(1, height);
        return new Layout(asset, family, levels, java.util.Collections.nCopies(6, levels));
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        Layout own = layout(feature.asset(), Math.round(feature.height()), family(tile.cover().wallWeight()));
        List<Integer> arms = new ArrayList<>(own.arms());
        int exits = exits(feature.asset());
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & 1 << direction) == 0) { continue; }
            var neighbor = scene.neighbor(tile, direction);
            if (neighbor == null) { continue; }
            int opposite = (direction + 3) % 6;
            var connected = neighbor.features().stream().filter(part -> supports(part.asset())
                  && (exits(part.asset()) & 1 << opposite) != 0)
                  .findFirst().orElse(null);
            if (connected == null) { continue; }
            arms.set(direction, ConnectedKit.arm(tile, own.height(), neighbor, Math.max(1, Math.round(connected.height()))));
        }
        return new Layout(own.asset(), own.family(), own.height(), arms);
    }

    private static int exits(String asset) {
        if (!supports(asset)) { throw new IllegalArgumentException("Invalid wall asset: " + asset); }
        return Integer.parseInt(asset.substring(PREFIX.length()));
    }

    static ModelData model(String asset) { return model(asset, 1); }
    static ModelData model(String asset, int levels) { return model(asset, levels, 0); }

    static ModelData model(String asset, int levels, int lod) {
        return model(layout(asset, levels), lod);
    }

    /**
     * The topology picks the kit: a lone wall or one exit the terminal (its arm authored towards north, turned to the
     * exit; alone it keeps the half turn it always had), two opposite full-height exits the whole-hex straight
     * section, anything else the pier with one arm per exit, each as high as {@link Layout#arms} allows.
     */
    static ModelData model(Layout layout, int lod) {
        if (lod < 0 || lod >= RigidGlb.LEVELS) { throw new IllegalArgumentException("Invalid wall LOD: " + lod); }
        int exits = exits(layout.asset());
        int first = Integer.numberOfTrailingZeros(exits);
        int[] reach = new int[6];
        for (int direction = 0; direction < 6; direction++) {
            reach[direction] = (exits & 1 << direction) == 0 ? 0 : layout.arms().get(direction);
        }
        String piece = piece(layout);
        int steps = exits == 0 ? 3 : piece.equals("pier") ? 0 : first;
        return ConnectedKit.model(kit(layout.family(), piece), lod, layout.height(), reach, steps, 0,
              layout + "-lod" + lod);
    }

    private static String piece(Layout layout) {
        int exits = exits(layout.asset());
        int first = Integer.numberOfTrailingZeros(exits), opposite = (first + 3) % 6;
        return Integer.bitCount(exits) <= 1 ? "terminal"
              : exits == (1 << first | 1 << opposite) && layout.arms().get(first) == layout.height()
                    && layout.arms().get(opposite) == layout.height() ? "straight" : "pier";
    }

    /**
     * The ground a military wall's body stands on, as triangles at the top of each part (model units, ground at zero):
     * every military family's deck is a road wide and its junction 52 units across flats (walls/README.md), which a
     * straight section does not have. Their buttresses and armour stand on the ground cover they hide.
     */
    static List<Vector3> footprint(Layout layout) {
        List<Vector3> result = new ArrayList<>();
        if (!piece(layout).equals("straight")) {
            float top = layout.height() * GpuBuilding.LEVEL_HEIGHT, radius = 26 / (float) Math.cos(Math.PI / 6);
            for (int corner = 0; corner < 6; corner++) {
                double a = Math.PI / 3 * corner, b = Math.PI / 3 * (corner + 1);
                result.add(new Vector3(0, 0, top));
                result.add(new Vector3(radius * (float) Math.cos(a), radius * (float) Math.sin(a), top));
                result.add(new Vector3(radius * (float) Math.cos(b), radius * (float) Math.sin(b), top));
            }
        }
        int exits = exits(layout.asset());
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & 1 << direction) == 0) { continue; }
            // Each arm runs from the centre to its edge midpoint, as ConnectedKit#reach checks.
            double angle = Math.toRadians(90 - 60 * direction);
            var along = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0).scl(HexTile.HEIGHT / 2f);
            var across = new Vector3(-along.y, along.x, 0).nor().scl(HexTile.ROAD_HALF_WIDTH);
            float top = layout.arms().get(direction) * GpuBuilding.LEVEL_HEIGHT;
            Vector3 a = new Vector3(across).add(0, 0, top), b = new Vector3(across).scl(-1).add(0, 0, top);
            Vector3 c = new Vector3(b).add(along), d = new Vector3(a).add(along);
            result.addAll(List.of(a, b, c, new Vector3(a), new Vector3(c), d));
        }
        return result;
    }

    /** Exact near-level triangles, copied so obstacle/world transforms never mutate the source kit. */
    static List<Vector3> triangles(String asset) { return triangles(asset, 1); }

    static List<Vector3> triangles(String asset, int levels) {
        return triangles(layout(asset, levels));
    }

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
}
