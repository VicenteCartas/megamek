/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * The terrain facts a minimap needs, captured solely from map content, without resolving visual assets. Thumbnails
 * capture these directly; the board scene retains the same immutable snapshot for its in-game minimap. Neither
 * image is authoritative terrain. Both use {@link #colour}, including its precedence, road/bridge tint and shading.
 */
record MinimapTerrain(int elevation, int waterDepth, boolean frozen, BoardScene.Surface surface,
      BoardLiquid.Kind liquid, boolean building, int woods, boolean jungle, BoardScene.Biome biome,
      boolean rough, boolean road, boolean bridge) {
    private static final Map<BoardScene.Surface, Color> SURFACES = new EnumMap<>(Map.ofEntries(
          Map.entry(BoardScene.Surface.GRASS, Color.valueOf("7E8D41")),
          Map.entry(BoardScene.Surface.TROPICAL, Color.valueOf("5E8A3A")),
          Map.entry(BoardScene.Surface.DIRT, Color.valueOf("8A7350")),
          Map.entry(BoardScene.Surface.SAND, Color.valueOf("C2AE7A")),
          Map.entry(BoardScene.Surface.DESERT, Color.valueOf("CDB27A")),
          Map.entry(BoardScene.Surface.ROCK, Color.valueOf("8A867C")),
          Map.entry(BoardScene.Surface.CONCRETE, Color.valueOf("9C9C98")),
          Map.entry(BoardScene.Surface.SNOW, Color.valueOf("E2E7EC")),
          Map.entry(BoardScene.Surface.LUNAR, Color.valueOf("8E8E8C")),
          Map.entry(BoardScene.Surface.FUNGUS, Color.valueOf("7E6A8E")),
          Map.entry(BoardScene.Surface.MARS, Color.valueOf("A0583A")),
          Map.entry(BoardScene.Surface.VOLCANO, Color.valueOf("4A4440"))));
    private static final Color[] WOODS = { Color.valueOf("4E7A34"), Color.valueOf("3C6428"), Color.valueOf("2C4E1E") };
    private static final Color JUNGLE = Color.valueOf("3E7A3A");
    private static final Color FIELD = Color.valueOf("A8A050");
    private static final Color MARSH = Color.valueOf("4E6B45");
    private static final Color MUD = Color.valueOf("6E5A3E");
    private static final Color TUNDRA = Color.valueOf("9AA08A");
    private static final Color ROUGH = Color.valueOf("8F8160");
    private static final Color BUILDING = Color.valueOf("B0ADA6");
    private static final Color WATER = Color.valueOf("4A8CA6");
    private static final Color DEEP_WATER = Color.valueOf("33688F");
    private static final Color ICE = Color.valueOf("C8DCEB");
    private static final Color MAGMA = Color.valueOf("E0581E");
    private static final Color MAGMA_CRUST = Color.valueOf("5A2A1C");
    private static final Color ROAD = Color.valueOf("5A5A5E");
    private static final Color BRIDGE = Color.valueOf("7A5A3C");
    private static final float ELEVATION_SHADE = .05f;

    /** Legacy art tokens and authored models do not affect terrain colours or need resolving here. */
    static MinimapTerrain capture(Hex hex) {
        boolean jungle = hex.containsTerrain(Terrains.JUNGLE);
        return new MinimapTerrain(hex.getLevel(),
              hex.containsTerrain(Terrains.WATER) ? Math.max(0, hex.terrainLevel(Terrains.WATER)) : -1,
              hex.containsTerrain(Terrains.ICE), BoardFeatures.surface(hex), BoardLiquid.capture(hex).kind(),
              !hex.containsTerrain(Terrains.ULTRA_SUBLEVEL) && hex.containsTerrain(Terrains.BUILDING),
              Math.max(0, hex.terrainLevel(jungle ? Terrains.JUNGLE : Terrains.WOODS)), jungle,
              BoardFeatures.biome(hex), BoardFeatures.roughTerrain(hex),
              BoardRoad.capture(hex) != BoardRoad.Kind.NONE, hex.containsTerrain(Terrains.BRIDGE));
    }

    /** A changed surface presentation retains terrain facts, including buildings whose visual model is missing. */
    MinimapTerrain withSurface(int level, int depth, boolean iced, BoardScene.Surface ground, BoardLiquid.Kind liquid,
          BoardScene.Biome biome) {
        return new MinimapTerrain(level, depth, iced, ground, liquid, building, woods, jungle, biome, rough, road, bridge);
    }

    /** Compatibility for hand-built tiles that have captured fields rather than a source hex. */
    static MinimapTerrain captured(int elevation, int waterDepth, boolean frozen, BoardScene.Surface surface,
          BoardLiquid liquid, BoardScene.Cover cover, BoardScene.Biome biome, BoardRoad.Kind road,
          List<BoardScene.Feature> features) {
        boolean building = false, rough = false, bridge = false;
        for (BoardScene.Feature feature : features) {
            building |= feature.kind() == BoardScene.FeatureKind.BUILDING;
            rough |= feature.kind() == BoardScene.FeatureKind.ROUGH || feature.kind() == BoardScene.FeatureKind.BOULDER;
            bridge |= feature.asset().equals("bridge");
        }
        BoardLiquid.Kind kind = liquid == null
              ? waterDepth >= 0 ? BoardLiquid.Kind.WATER : BoardLiquid.Kind.NONE : liquid.kind();
        return new MinimapTerrain(elevation, waterDepth, frozen, surface, kind, building,
              cover.woods(), cover.jungle(), biome, rough, road != BoardRoad.Kind.NONE, bridge);
    }

    /** One rgba8888 colour for both the in-game minimap and all board thumbnails. */
    int colour() {
        Color colour = new Color(ground());
        if (road) { colour.lerp(ROAD, .4f); }
        if (bridge) { colour.lerp(BRIDGE, .6f); }
        colour.mul(MathUtils.clamp(1 + ELEVATION_SHADE * elevation, .7f, 1.3f));
        colour.a = 1;
        return Color.rgba8888(colour);
    }

    private Color ground() {
        if (frozen) { return ICE; }
        if (liquid == BoardLiquid.Kind.MAGMA) { return MAGMA; }
        if (liquid == BoardLiquid.Kind.MAGMA_CRUST) { return MAGMA_CRUST; }
        if (liquid != BoardLiquid.Kind.NONE) {
            Color water = waterDepth > 1 ? DEEP_WATER : WATER;
            return liquid == BoardLiquid.Kind.HAZARDOUS ? water.cpy().mul(BoardLiquid.HAZARDOUS_TINT) : water;
        }
        if (building) { return BUILDING; }
        if (woods > 0) { return jungle ? JUNGLE : WOODS[Math.min(woods, WOODS.length) - 1]; }
        return switch (biome) {
            case FIELD -> FIELD;
            case MARSH -> MARSH;
            case QUICKSAND, MUD -> MUD;
            case TUNDRA -> TUNDRA;
            default -> rough ? ROUGH : SURFACES.get(surface);
        };
    }
}
