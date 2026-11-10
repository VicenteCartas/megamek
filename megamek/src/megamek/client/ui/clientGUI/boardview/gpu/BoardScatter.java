/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Cosmetic scatter placement and the independently editable LOD0 meshes in scatter/*.glb. */
final class BoardScatter {
    /** 0 disables scatter, 1 is the baseline, 3 triples each biome's placement chance. */
    static final float DENSITY_MULTIPLIER = 3.0f;
    static final int BUSHES = 8;
    /** Metres, covering the widest authored bush variant for placement and culling. */
    static final float BUSH_RADIUS = 1.8f;
    /** Tundra's grass tussock, drawn with the trees (foliage-tussock.glb) but never counted as their cover. */
    static final String TUSSOCK = "vegetation/foliage-tussock";
    private static final int TUSSOCKS = 10;
    /** Tile pixels between tussocks, and kept clear around the unit standing at the hex centre. */
    private static final float TUSSOCK_SPACING = 10, TUSSOCK_CLEAR = 9;

    private BoardScatter() { }

    // Scene capture needs only placement; defer geometry loading until a renderer requests a shape.
    private static final BoardKit<Map<String, BoardShape>> KIT = new BoardKit<>(BoardScatter::load);

    private static Map<String, BoardShape> load() {
        List<String> names = new ArrayList<>(List.of("grass"));
        for (int variant = 0; variant < BUSHES; variant++) { names.add("bush-" + variant); }
        for (boolean block : new boolean[] { true, false }) {
            for (int variant = 0; variant < (block ? BoardRocks.BLOCKS : BoardRocks.BOULDERS); variant++) {
                names.add("stone-" + BoardRocks.name(block, variant));
            }
        }
        Map<String, BoardShape> shapes = new HashMap<>();
        for (String name : names) {
            Map<String, BoardShape> mesh = BoardShape.loadKit("scatter/" + name);
            String node = name + "-lod0";
            if (mesh.size() != 1 || !mesh.containsKey(node)) {
                throw new IllegalArgumentException("Scatter " + name + " must contain only " + node);
            }
            shapes.put(name, mesh.get(node));
        }
        return Map.copyOf(shapes);
    }

    static BoardShape shape(String name) {
        return KIT.get().get(name);
    }

    /** Replace the complete immutable LOD0 kit. */
    static void reload() { KIT.reload(); }

    /** Cosmetic stones always use the dedicated eight-triangle open-base meshes. */
    static BoardShape rock(BoardScene.Surface surface, int variant, boolean slab) {
        return shape("stone-" + BoardRocks.name(slab || BoardRocks.blocks(surface), variant));
    }

    /** Captured scatter that is loose stone rather than grass, plants or fungi. */
    static boolean stone(String asset) {
        return asset.equals("scatter-rock") || asset.equals("scatter-slab");
    }

    static BoardShape bush(BoardScene.Surface surface, int variant) {
        return shape("bush-" + ((surface == BoardScene.Surface.GRASS || surface == BoardScene.Surface.TROPICAL ? BUSHES / 2 : 0)
              + Math.floorMod(variant, BUSHES / 2)));
    }

    /** The small scatter plant is its biome's first bush (the former plant meshes were byte-identical copies). */
    static BoardShape plant(BoardScene.Surface surface) {
        return bush(surface, 0);
    }

    /** Authored tissue identity shared by field shrubs and captured bushes; unknown roles remain opaque. */
    static boolean leaf(String material) {
        return switch (material) {
            case "dry-leaf", "dry-tip", "green-leaf", "green-tip" -> true;
            default -> false;
        };
    }

    /** Bushes stand just above tall grass, with a level cap to keep unusually shallow boards readable. */
    static float bushScale(BoardShape shape, float scale) {
        float variation = Math.clamp((scale - .7f) / .5f, 0, 1);
        float height = BoardGeometry.width() * GpuGroundCover.MAX_HEIGHT_FRACTION * (1.15f + .15f * variation);
        return Math.min(height, BoardGeometry.level() * .45f) / shape.height();
    }

    /**
     * Shared by captured scatter and the relief's field stones and shrubs: bare rock takes neither, and only exposed
     * depth-zero shallows can justify liquid-tile dressing; reject deeper beds before loading kits.
     */
    static boolean allowed(BoardScene.Tile tile) {
        return !tile.ultraSublevel() && !tile.backdrop().present() && !tile.bare() && !tile.liquid().volcanic()
              && !BoardFeatures.shaft(tile) && (!tile.liquid().present() || tile.waterDepth() == 0);
    }

    /** Scatter never enters a pit; dry-bank placements stay above water and shallow stones break its surface. */
    static boolean visible(BoardScene scene, BoardScene.Tile owner, Vector3 base, float height) {
        if (!allowed(owner)) { return false; }
        var receiving = BoardGeometry.tile(scene, base.x, base.y);
        if (receiving != null && (receiving.ultraSublevel() || receiving.backdrop().present())) { return false; }
        if (receiving == null || !receiving.liquid().present()) { return true; }
        float water = BoardGeometry.waterZ(receiving);
        if (!owner.liquid().present() && base.z >= water) { return true; }
        return allowed(receiving) && base.z + height > water + BoardRelief.metres(.1f);
    }

    /** Cosmetic clusters leave the unit centre clear; terrain updates never reshuffle neighboring details. */
    static void capture(Hex hex, Coords coords, List<BoardScene.Feature> result) {
        boolean tundra = BoardFeatures.biome(hex) == BoardScene.Biome.TUNDRA;
        if (hex.containsTerrain(Terrains.WATER) && hex.terrainLevel(Terrains.WATER) > 0
              || hex.containsAnyTerrainOf(Terrains.ICE, Terrains.ROAD, Terrains.PAVEMENT,
              Terrains.BRIDGE, Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL, Terrains.FIELDS,
              Terrains.WOODS, Terrains.JUNGLE, Terrains.SPACE, Terrains.SKY, Terrains.MAGMA, Terrains.FIRE,
              Terrains.GEYSER, Terrains.SWAMP, Terrains.MUD, Terrains.HAZARDOUS_LIQUID, Terrains.FORTIFIED, Terrains.ROUGH)) {
            return;
        }
        BoardScene.Surface surface = BoardFeatures.surface(hex);
        if (tundra) { tussocks(surface, coords, result); }
        float density = switch (surface) {
            case GRASS, TROPICAL -> .16f;
            case ROCK, LUNAR, VOLCANO -> .18f;
            case FUNGUS -> .09f;
            case DIRT -> .12f;
            case SAND, DESERT, MARS -> .10f;
            case SNOW -> .06f;
            case CONCRETE -> 0;
        };
        Random random = new Random(coords.getX() * 0x9E3779B97F4A7C15L
              ^ coords.getY() * 0xC2B2AE3D27D4EB4FL ^ 0x165667B19E3779F9L);
        if (random.nextFloat() >= density * DENSITY_MULTIPLIER) {
            return;
        }
        int count = 3 + random.nextInt(4);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        boolean plants = !hex.containsTerrain(Terrains.SAND)
              && !theme.contains("lunar") && !theme.contains("mars") && !theme.contains("volcan");
        for (int index = 0; index < count; index++) {
            int choice = random.nextInt(10);
            String asset = choice % 2 == 0 ? "scatter-rock" : "scatter-slab";
            if (plants && surface == BoardScene.Surface.TROPICAL && choice < 8) {
                asset = hex.containsTerrain(Terrains.TUNDRA) ? "scatter-dry-grass" : "scatter-plant";
            } else if (plants && surface == BoardScene.Surface.GRASS) {
                if (choice < 5 || hex.containsTerrain(Terrains.TUNDRA) && choice < 8) {
                    asset = hex.containsTerrain(Terrains.TUNDRA) ? "scatter-dry-grass" : "scatter-grass";
                } else if (choice < 8) {
                    asset = "scatter-plant";
                }
            } else if (plants && surface == BoardScene.Surface.DIRT && choice < 4) {
                asset = "scatter-dry-grass";
            } else if (plants && (surface == BoardScene.Surface.SAND || surface == BoardScene.Surface.DESERT) && choice == 0) {
                asset = "scatter-plant";
            }
            if (surface == BoardScene.Surface.FUNGUS) {
                asset = BoardFungus.SCATTER.get(random.nextInt(BoardFungus.SCATTER.size()));
            }
            double angle = random.nextDouble() * Math.PI * 2;
            float radius = 16 + 12 * (float) Math.sqrt(random.nextFloat());
            var feature = new BoardScene.Feature(asset, (float) Math.cos(angle) * radius,
                  (float) Math.sin(angle) * radius, random.nextFloat() * 360, .7f + random.nextFloat() * .5f,
                  .10f + random.nextFloat() * .13f, 0, BoardScene.FeatureKind.SCATTER);
            // Replace only tundra's plants with support-rooted dwarf shrubs. Consume the same random values so
            // its loose rocks and slabs retain their original positions, sizes and rotations.
            if (!tundra || stone(asset)) { result.add(feature); }
        }
    }

    /**
     * Earth tundra's grass tussocks, a couple of metres apart in loose clusters, clear of the unit at the centre, in
     * the openings of its sward ({@link BoardBiome#sward}), whose blades stand as tall. They stand as tall as the
     * meadow's grass, well below the low woods, so they never read as cover; fungal mats, mineral crust and pavement
     * lichen grow none.
     */
    private static void tussocks(BoardScene.Surface surface, Coords coords, List<BoardScene.Feature> result) {
        if (!BoardBiome.earthTundra(surface)) { return; }
        Random random = new Random(coords.getX() * 0x632BE59BD9B4E019L ^ coords.getY() * 0x85157AF5L ^ 0x7155L);
        float half = BoardGeometry.TILE_WIDTH / 2, scale = BoardGeometry.hexScale();
        float centreX = BoardGeometry.centerX(coords), centreY = BoardGeometry.centerY(coords);
        float cluster = BoardGeometry.TILE_WIDTH / 6, metre = BoardRelief.metres(1);
        // The tallest meadow blade's height, in levels.
        float levels = BoardGeometry.width() * GpuGroundCover.MAX_HEIGHT_FRACTION / BoardGeometry.level();
        List<float[]> placed = new ArrayList<>();
        for (int attempt = 0; attempt < TUSSOCKS * 8 && placed.size() < TUSSOCKS; attempt++) {
            float x = (random.nextFloat() * 2 - 1) * half, y = (random.nextFloat() * 2 - 1) * half;
            float chance = random.nextFloat(), size = random.nextFloat(), turn = random.nextFloat() * 360;
            if (Math.hypot(x, y) < TUSSOCK_CLEAR || !BoardGeometry.contains(coords, centreX + x * scale, centreY + y * scale)) {
                continue;
            }
            // Clusters in the shared world field, so they run on across hex edges.
            float worldX = centreX / scale + x, worldY = centreY / scale + y;
            if (chance > BoardRelief.smooth((BoardRelief.noise(worldX / cluster, worldY / cluster) - .45f) / .3f)) {
                continue;
            }
            boolean crowded = BoardBiome.sward((centreX + x * scale) / metre, (centreY + y * scale) / metre) > .5f;
            for (float[] other : placed) {
                crowded |= Math.hypot(x - other[0], y - other[1]) < TUSSOCK_SPACING;
            }
            if (crowded) { continue; }
            placed.add(new float[] { x, y });
            result.add(new BoardScene.Feature(TUSSOCK, x, y, turn, 1, levels * (.75f + .25f * size), 0,
                  BoardScene.FeatureKind.TREE));
        }
    }
}
