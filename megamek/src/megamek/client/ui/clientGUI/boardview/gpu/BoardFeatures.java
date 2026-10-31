/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import megamek.common.Hex;
import megamek.common.WoodsClearingTracker;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;

/** Copies terrain appearance on the Swing thread; no game objects cross into the renderer. */
final class BoardFeatures {
    /** Width in tile pixels of a Rough boulder at feature scale one; placement and meshing share this size. */
    static final float ROUGH_BOULDER_WIDTH = 12;
    /** Half-diagonal in tile pixels of the 6.4-pixel square dragon-tooth footprint; clears roads and hex edges. */
    private static final float DRAGON_TOOTH_RADIUS = 4.6f;
    /** Ultra rough's tooth spacing in tile pixels, the closest that still reads as separate teeth. */
    private static final float CLOSE_TEETH_SPACING = 11;
    /** Tree species by where they grow; repeated names are the common ones. */
    private static final List<String> TEMPERATE = List.of("vegetation/tree", "vegetation/pine", "vegetation/tree-broad",
          "vegetation/birch", "vegetation/tree-slender", "vegetation/pine-tall", "vegetation/pine-broad",
          "vegetation/tree-forked", "vegetation/tree-layered", "vegetation/birch-tall", "vegetation/birch-spreading",
          "vegetation/birch-young", "vegetation/pine-slender", "vegetation/pine-layered");
    private static final List<String> HIGHLAND = List.of("vegetation/pine", "vegetation/pine-tall",
          "vegetation/pine-broad", "vegetation/tree-slender", "vegetation/pine-tall", "vegetation/birch",
          "vegetation/pine-slender", "vegetation/pine-layered", "vegetation/pine-slender", "vegetation/pine-layered",
          "vegetation/birch-tall", "vegetation/birch-young");
    private static final List<String> ROCKY = List.of("vegetation/pine", "vegetation/tree-dead", "vegetation/pine-tall",
          "vegetation/pine-broad", "vegetation/pine-slender", "vegetation/pine-layered");
    static final List<String> VOLCANO_TREES = List.of("vegetation/tree-volcano-crown", "vegetation/tree-volcano-forked",
          "vegetation/tree-volcano-spire");
    private static final List<String> WETLAND = List.of("vegetation/willow", "vegetation/tree-slender",
          "vegetation/tree-dead", "vegetation/willow", "vegetation/tree", "vegetation/willow-broad",
          "vegetation/willow-broad", "vegetation/birch-spreading", "vegetation/tree-forked");
    private static final List<String> BARREN = List.of("vegetation/tree-dead");
    private static final List<String> DESERT = List.of("vegetation/cactus", "vegetation/palm", "vegetation/tree-dead",
          "vegetation/cactus-flowers", "vegetation/palm-bent", "vegetation/cactus-old", "vegetation/cactus-young",
          "vegetation/cactus-organ");
    private static final List<String> PALMS = List.of("vegetation/palm", "vegetation/palm-bent");
    private static final List<String> TROPICAL = List.of("vegetation/palm", "vegetation/palm-bent",
          "vegetation/tree-broad", "vegetation/palm", "vegetation/tree-slender", "vegetation/palm-bent",
          "vegetation/palm", "vegetation/tree-forked", "vegetation/palm-bent", "vegetation/tree-layered");
    private static final List<String> ORCHARD = List.of("vegetation/orchard-round", "vegetation/orchard-spreading",
          "vegetation/orchard-upright", "vegetation/orchard-vase", "vegetation/orchard-leaning",
          "vegetation/orchard-young");
    static final List<String> MARS_CORALS = List.of("mars/finger-spires", "mars/fan-scalloped", "mars/tube-grove",
          "mars/antler-crown", "mars/plate-terraces", "mars/brain-lobes",
          "mars/finger-crown", "mars/fan-folded", "mars/tube-crown",
          "mars/organ-pipes", "mars/spiral-whorls", "mars/lattice-spires");
    /** Every species that snow turns into its {@code -snow} form: the lists {@link #species} maps on snow. */
    private static final Set<String> SNOW_FORMS = java.util.stream.Stream.of(TEMPERATE, HIGHLAND, ROCKY, WETLAND,
          BARREN, BoardTreeDistribution.PARK, ORCHARD).flatMap(List::stream).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private BoardFeatures() { }

    /** The tileset's orchard marker changes woods appearance, never creates cover by itself. */
    static boolean orchard(Hex hex) {
        return hex.terrainLevel(Terrains.WOODS) > 0 && !hex.containsTerrain(Terrains.JUNGLE)
              && hex.terrainLevel(Terrains.FLUFF) == 12;
    }

    /** Use the terrain material where every marking is either base ground or represented by a captured model. */
    static boolean detailedGround(Hex hex, Map<Integer, String> structureModels) {
        return detailedGround(hex, structureModels, Set.of());
    }

    static boolean detailedGround(Hex hex, Map<Integer, String> structureModels, Set<Integer> blankTerrains) {
        return detailedGround(hex, structureModels, blankTerrains, TilesetScenery.Scenery.EMPTY);
    }

    static boolean detailedGround(Hex hex, Map<Integer, String> structureModels, Set<Integer> blankTerrains,
          TilesetScenery.Scenery scenery) {
        for (int terrain : hex.getTerrainTypes()) {
            if (scenery.terrains().contains(terrain)) { continue; }
            if (blankTerrains.contains(terrain) && switch (terrain) {
                case Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF -> true;
                default -> false;
            }) { continue; }
            boolean base = switch (terrain) {
                // Old boards can retain "none" (type 0), whose tileset image is empty.
                case 0 -> true;
                case Terrains.WOODS, Terrains.JUNGLE, Terrains.FOLIAGE_ELEV, Terrains.SAND, Terrains.TUNDRA,
                      Terrains.PAVEMENT, Terrains.SNOW, Terrains.WATER, Terrains.HAZARDOUS_LIQUID, Terrains.RAPIDS, Terrains.ROUGH,
                      Terrains.CLIFF_TOP, Terrains.CLIFF_BOTTOM, Terrains.INCLINE_TOP, Terrains.INCLINE_BOTTOM,
                      Terrains.INCLINE_HIGH_TOP, Terrains.INCLINE_HIGH_BOTTOM, Terrains.METAL_CONTENT,
                      Terrains.DEPLOYMENT_ZONE, Terrains.IMPASSABLE, Terrains.ULTRA_SUBLEVEL, Terrains.FIRE, Terrains.SMOKE,
                      Terrains.SCREEN, Terrains.SPACE, Terrains.SKY,
                      Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV, Terrains.BRIDGE_REPAIRED, Terrains.BRIDGE_MUD,
                      Terrains.BRIDGE_ROAD, Terrains.BRIDGE_RAIL, Terrains.RAIL,
                      Terrains.FIELDS, Terrains.SWAMP, Terrains.MUD, Terrains.ICE, Terrains.BLACK_ICE, Terrains.BLDG_BASE_COLLAPSED,
                      Terrains.ARMS, Terrains.LEGS, Terrains.WATER_FLUFF -> true;
                case Terrains.MAGMA -> hex.terrainLevel(Terrains.MAGMA) == 1 || hex.terrainLevel(Terrains.MAGMA) == 2;
                case Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_CLASS,
                      Terrains.BLDG_ARMOR, Terrains.BLDG_BASEMENT_TYPE, Terrains.BLDG_FLUFF ->
                      structureModels.containsKey(Terrains.BUILDING);
                case Terrains.FUEL_TANK, Terrains.FUEL_TANK_CF, Terrains.FUEL_TANK_ELEV, Terrains.FUEL_TANK_MAGN ->
                      structureModels.containsKey(Terrains.FUEL_TANK);
                case Terrains.INDUSTRIAL -> structureModels.containsKey(Terrains.INDUSTRIAL);
                // A road through water lies on its bed, or out of sight under deeper water (BoardRoad#submerged).
                case Terrains.ROAD -> BoardRoad.capture(hex) != BoardRoad.Kind.NONE;
                // Fluff 1 selects the standard road bend, already represented by the captured road exits.
                case Terrains.ROAD_FLUFF -> hex.terrainLevel(Terrains.ROAD_FLUFF) == 1
                      && BoardRoad.capture(hex) != BoardRoad.Kind.NONE;
                case Terrains.FLUFF -> orchard(hex) || HexTile.roughVariant(hex) != 0;
                case Terrains.GROUND_FLUFF -> BoardSurfaceBlend.hasTransition(hex);
                default -> false;
            };
            if (!base) { return false; }
        }
        return true;
    }

    static BoardScene.Surface surface(Hex hex) {
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        if (hex.containsTerrain(Terrains.MAGMA)) { return BoardScene.Surface.ROCK; }
        if (hex.containsTerrain(Terrains.SNOW) || theme.contains("snow")) {
            return BoardScene.Surface.SNOW;
        }
        if (HexTile.concrete(hex)) {
            return BoardScene.Surface.CONCRETE;
        }
        // The theme supplies geology. BoardSurfaceBlend captures the independent gameplay SAND cover.
        if (theme.contains("mars")) { return BoardScene.Surface.MARS; }
        if (desert(hex)) { return BoardScene.Surface.DESERT; }
        if (theme.contains("lunar")) { return BoardScene.Surface.LUNAR; }
        if (theme.contains("fungus")) { return BoardScene.Surface.FUNGUS; }
        if (theme.contains("volcan")) { return BoardScene.Surface.VOLCANO; }
        if (tropical(hex)) { return BoardScene.Surface.TROPICAL; }
        if (theme.contains("rock")) {
            return BoardScene.Surface.ROCK;
        }
        // Fields and reed marshes replace the flat cover in the biome shader. Their banks retain the theme's
        // grass/soil mantle; classifying the whole column as dirt leaves a bare cutout around every plantation.
        if (hex.containsTerrain(Terrains.MUD) || hex.terrainLevel(Terrains.SWAMP) > 1
              || theme.contains("dirt")) {
            return BoardScene.Surface.DIRT;
        }
        return BoardScene.Surface.GRASS;
    }

    static BoardScene.Biome biome(Hex hex) {
        if (BoardBackdrop.capture(hex).present()) { return BoardScene.Biome.NONE; }
        if (hex.containsAnyTerrainOf(Terrains.ICE, Terrains.WATER, Terrains.HAZARDOUS_LIQUID, Terrains.MAGMA)) {
            return BoardScene.Biome.NONE;
        }
        boolean tundra = hex.containsTerrain(Terrains.TUNDRA) && !hex.containsAnyTerrainOf(Terrains.SNOW, Terrains.SAND);
        // Lichens colonize pavement, but wetland soil and planted fields do not replace a constructed slab.
        if (hex.containsTerrain(Terrains.PAVEMENT)) { return tundra ? BoardScene.Biome.TUNDRA : BoardScene.Biome.NONE; }
        if (hex.containsTerrain(Terrains.SWAMP)) {
            return hex.terrainLevel(Terrains.SWAMP) == 1 ? BoardScene.Biome.MARSH : BoardScene.Biome.QUICKSAND;
        }
        if (hex.containsTerrain(Terrains.FIELDS)) { return BoardScene.Biome.FIELD; }
        if (hex.containsTerrain(Terrains.MUD)) { return BoardScene.Biome.MUD; }
        // Snow terrain and loose sand cover the low tundra crust; a Snow theme can still show exposed tundra.
        return tundra ? BoardScene.Biome.TUNDRA : BoardScene.Biome.NONE;
    }

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels) {
        return capture(hex, coords, structureModels, Set.of());
    }

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels,
          Set<Integer> blankTerrains) {
        return capture(hex, coords, structureModels, blankTerrains, neighbor -> null);
    }

    /** The board supplies the neighbouring roads, whose course through this hex scenery keeps clear of. */
    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels,
          Set<Integer> blankTerrains, Function<Coords, Hex> board) {
        return capture(hex, coords, structureModels, blankTerrains, board, TilesetScenery.Scenery.EMPTY);
    }

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels,
          Set<Integer> blankTerrains, Function<Coords, Hex> board, TilesetScenery.Scenery scenery) {
        return capture(hex, coords, structureModels, blankTerrains, board, scenery, BoardTreeDistribution.NATURAL);
    }

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, Map<Integer, String> structureModels,
          Set<Integer> blankTerrains, Function<Coords, Hex> board, TilesetScenery.Scenery scenery,
          boolean natural) {
        if (hex.containsTerrain(Terrains.ULTRA_SUBLEVEL)) {
            // A chasm keeps its placed objects and a bridge across it, whose deck stands on the hex's own level.
            List<BoardScene.Feature> chasm = new ArrayList<>(decorations(hex, coords, board));
            chasm.addAll(roadControls(hex, coords, board, false));
            addBridge(hex, chasm);
            return List.copyOf(chasm);
        }
        List<BoardScene.Feature> result = new ArrayList<>();
        // Every scenery model is a layouts.json key (TilesetScenery counts nothing else as a model). Only terrain-owned
        // art is left (geysers, rubble, fortified, cleared-rubble paths, elevator plates): BoardTiles draws a hex without
        // the tokens the conversion took, whose objects stand for them.
        for (String asset : scenery.models()) { result.addAll(features(BoardSceneryLayouts.layout(asset), coords, hex)); }
        int variant = Math.floorMod(coords.getX() * 31 + coords.getY() * 17, 4);
        // Terrain levels are the game's collectable limb counts, not damage inferred from nearby units.
        Random limbs = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L ^ 0x41524D534C454753L);
        List<BoardScene.Feature> placedLimbs = new ArrayList<>();
        int arms = Math.max(0, hex.terrainLevel(Terrains.ARMS)), legs = Math.max(0, hex.terrainLevel(Terrains.LEGS));
        // Reserve interleaved positions even for absent limbs: collecting an arm cannot move a remaining leg.
        for (int slot = 0; slot < 2 * Math.max(arms, legs); slot++) {
            float x = 0, y = 0;
            for (int attempt = 0; attempt < 24; attempt++) {
                double angle = limbs.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(limbs.nextDouble()) * 24;
                x = (float) (Math.cos(angle) * radius); y = (float) (Math.sin(angle) * radius);
                float px = x, py = y;
                if (placedLimbs.stream().noneMatch(p -> Math.hypot(p.x() - px, p.y() - py) < 12)) { break; }
            }
            // Independent heading breaks the radial pattern; the seed keeps rebuilds and reloads stable.
            var limb = new BoardScene.Feature(slot % 2 == 0 ? "salvage/arm" : "salvage/leg", x, y,
                  limbs.nextFloat() * 360, 1, 1, 0, BoardScene.FeatureKind.LIMB);
            placedLimbs.add(limb);
            if (slot / 2 < (slot % 2 == 0 ? arms : legs)) { result.add(limb); }
        }
        for (var structure : structureModels.entrySet()) {
            int heightTerrain = switch (structure.getKey()) {
                case Terrains.BUILDING -> Terrains.BLDG_ELEV;
                case Terrains.FUEL_TANK -> Terrains.FUEL_TANK_ELEV;
                default -> Terrains.INDUSTRIAL;
            };
            result.add(new BoardScene.Feature(structure.getValue(), 0, 0, 0, 1,
                  Math.max(1, hex.terrainLevel(heightTerrain)), 0, switch (structure.getKey()) {
                      case Terrains.BUILDING -> BoardScene.FeatureKind.BUILDING;
                      case Terrains.FUEL_TANK -> BoardScene.FeatureKind.FUEL_TANK;
                      default -> BoardScene.FeatureKind.INDUSTRIAL;
                  }));
        }
        addBridge(hex, result);
        result.addAll(BoardMetalDeposits.capture(hex, coords));
        if (hex.containsTerrain(Terrains.SOLARIS_ELEVATOR)) {
            // The lift's walls are no terrain feature of its own: they stand only where the hex rises above a neighbour.
            result.add(new BoardScene.Feature(ELEVATOR_SHAFT, 0, 0, 0, 1, 0, 0, BoardScene.FeatureKind.SHAFT));
        }
        if (BoardBackdrop.capture(hex).present()) {
            result.addAll(decorations(hex, coords, board));
            result.addAll(roadControls(hex, coords, board, true));
            return List.copyOf(result);
        }
        if (hex.containsTerrain(Terrains.FIELDS) && !detailedGround(hex, structureModels, blankTerrains)) {
            add(result, "vegetation/field", 1, 0, 0);
        }
        Hex vegetation = hex.getAppearance().containsKey("vegetation")
              ? megamek.common.board.BoardEditorBlueprint.get().artwork(hex, "vegetation") : hex;
        boolean jungle = vegetation.containsTerrain(Terrains.JUNGLE);
        // Trees keep off the road and the rails on the ground.
        List<BoardRoad> roads = nearbyWays(vegetation, coords, board);
        float roadX = BoardGeometry.centerX(coords), roadY = BoardGeometry.centerY(coords), roadScale = BoardGeometry.hexScale();
        if ((jungle || vegetation.containsTerrain(Terrains.WOODS)) && !BoardTreeDistribution.noTrees(hex)) {
            // Snow/pavement change the ground, not the planet's vegetation (including low cover and jungle).
            boolean mars = vegetation.getTheme() != null && vegetation.getTheme().toLowerCase(Locale.ROOT).contains("mars");
            boolean volcano = vegetation.getTheme() != null && vegetation.getTheme().toLowerCase(Locale.ROOT).contains("volcan");
            boolean orchard = !mars && !volcano && orchard(vegetation);
            boolean fungus = surface(vegetation) == BoardScene.Surface.FUNGUS;
            int density = vegetation.terrainLevel(jungle ? Terrains.JUNGLE : Terrains.WOODS);
            int count = fungus || mars ? (density >= 3 ? 6 : density == 2 ? 4 : 2)
                  : density >= 3 ? 16 : density == 2 ? 9 : orchard ? 6 : 3;
            // Foliage reaches its rules height. Level-one cover uses proportioned shrubs; taller woods keep
            // broad tree crowns so the canopy reads as an obstacle. Light woods have the broadest tree crowns.
            float height = Math.max(1, vegetation.terrainLevel(Terrains.FOLIAGE_ELEV));
            float crown = orchard ? (density >= 3 ? .62f : .82f)
                  : density >= 3 ? 1.35f : density == 2 ? 1.45f : 1.7f;
            List<String> species = mars ? MARS_CORALS
                  : volcano ? (height == 1 ? shrubVariants("vegetation/foliage-volcano") : VOLCANO_TREES)
                  : fungus ? BoardFungus.COVER : orchard ? orchardSpecies(vegetation)
                  : height == 1 ? shrubVariants(shrub(vegetation, jungle)) : species(vegetation, jungle);
            // Cosmetic understory shares the canopy's placement, road clearance, grounding and tree LODs.
            boolean understory = surface(vegetation) == BoardScene.Surface.TROPICAL && height > 1 && !orchard && !volcano;
            if (understory) { count *= 2; }
            Random treeRandom = natural ? new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L) : null;
            for (int index = 0; index < count; index++) {
                boolean low = understory && index % 2 == 1;
                double angle = index * (density >= 2 ? 2.399963 : 2 * Math.PI / count) + variant;
                // Space light foliage around the centre; dense foliage fills an equal-area spiral.
                float radius = density >= 2 ? 28 * (float) Math.sqrt(index / (count - 1f))
                      : 20 + index * 2;
                // Keep the density and bounded footprint, but break identical per-vegetation spirals.
                if (natural) {
                    angle += treeRandom.nextFloat() * .7f - .35f;
                    radius = Math.min(28, radius) * (.86f + treeRandom.nextFloat() * .14f);
                }
                if (fungus || mars) { radius *= .8f; }
                float x = (float) Math.cos(angle) * radius, y = (float) Math.sin(angle) * radius;
                if (orchard) {
                    // Light orchard rows align across the staggered lattice: three to a column's three-quarter hex.
                    int columns = density >= 3 ? 4 : 3;
                    int rows = count / columns;
                    float spacing = density >= 3 ? 15 : BoardGeometry.TILE_WIDTH / 4;
                    x = (index % columns - (columns - 1) * .5f) * spacing;
                    y = (index / columns - (rows - 1) * .5f) * (density == 1 ? 36 : spacing);
                }
                // Relocate trunks to a clear verge, including a neighbouring road's overhanging footprint.
                for (int attempt = 0; BoardRoad.distance(roads, roadX + x * roadScale, roadY + y * roadScale)
                      < BoardRoad.SHOULDER + 2 && attempt < 24; attempt++) {
                    angle += 2.399963;
                    x = (float) Math.cos(angle) * 29;
                    y = (float) Math.sin(angle) * 29;
                }
                if (BoardRoad.distance(roads, roadX + x * roadScale, roadY + y * roadScale) < BoardRoad.SHOULDER + 2) {
                    continue;
                }
                // Every biome uses the same distribution. Eligibility and size still describe its vegetation:
                // fungal and Mars woods may grow one shorter specimen in light woods and two in heavier woods, the
                // rest (mature fungal cups) reaching cover height, so a hex never reads lower than its woods;
                // tropical understory uses its low plant model.
                boolean shorter = (fungus || mars) && index >= 1 && index <= (density == 1 ? 1 : 2);
                List<String> choices = low ? shrubVariants("vegetation/foliage-jungle")
                      : fungus && !shorter ? BoardFungus.CUPS : species;
                int speciesIndex = understory ? index / 2 : index;
                String tree = natural ? BoardTreeDistribution.species(choices, coords, x, y, treeRandom.nextLong())
                      : choices.get(Math.floorMod(coords.getX() * 31 + coords.getY() * 17 + speciesIndex, choices.size()));
                float foliageHeight = low ? .4f + (index % 3) * .06f : height * (1f + (index % 3) * .05f);
                if (fungus || mars) {
                    foliageHeight = !shorter ? height : height * (tree.startsWith("fungus/spore-")
                          ? .46f + (index % 3) * .08f : .7f + (index % 3) * .1f);
                }
                result.add(new BoardScene.Feature(tree, x,
                      y, natural ? treeRandom.nextFloat() * 360
                            : index * 137.5f + (mars ? Math.floorMod(coords.getX() * 97 + coords.getY() * 53, 360) : 0),
                      (low ? .9f : crown) * (0.9f + (index % 3) * 0.1f),
                      foliageHeight, 0, BoardScene.FeatureKind.TREE));
            }
        }
        rough(hex, coords, result, board);
        BoardScatter.capture(hex, coords, result);
        result.addAll(decorations(hex, coords, board));
        result.addAll(roadControls(hex, coords, board, true));
        return List.copyOf(result);
    }

    /** Captured counterparts of BoardRoad.nearbyWays; no game connection is added for visual overhang. */
    private static List<BoardRoad> nearbyWays(Hex hex, Coords coords, Function<Coords, Hex> board) {
        List<BoardRoad> roads = new ArrayList<>();
        for (int direction = -1; direction < 6; direction++) {
            Coords at = direction < 0 ? coords : coords.translated(direction);
            Hex source = direction < 0 ? hex : board.apply(at);
            // A graded road's ramp and corridor reach a neighbour of another level too.
            if (source == null || HexTile.liquid(source)) { continue; }
            int rails = RailRoute.groundSides(source);
            if (BoardRoad.capture(source) == BoardRoad.Kind.NONE && rails == 0) { continue; }
            BoardRoad road = BoardRoad.clearance(at, source, board, rails);
            if (roads.stream().noneMatch(existing -> existing.owner().equals(road.owner()))) { roads.add(road); }
        }
        return roads;
    }

    /** Road-owned GLBs share the stop bars' approach frame and the normal ground/deck support path. */
    static List<BoardScene.Feature> roadControls(Hex hex, Coords coords, Function<Coords, Hex> board,
          boolean groundVisible) {
        List<BoardScene.Feature> result = new ArrayList<>();
        for (boolean deck : new boolean[] { false, true }) {
            if (deck ? !hex.containsTerrain(Terrains.BRIDGE)
                  : !groundVisible || HexTile.liquid(hex) || hex.containsTerrain(Terrains.ULTRA_SUBLEVEL)) { continue; }
            BoardRoad.Kind kind = deck ? BoardRoad.deck(hex) : BoardRoad.capture(hex);
            if (kind != BoardRoad.Kind.PAVED) { continue; }
            int exits = hex.getTerrain(deck ? Terrains.BRIDGE_ROAD : Terrains.ROAD).getExits() & 63;
            if (deck) { exits &= hex.getTerrain(Terrains.BRIDGE).getExits(); }
            String owner = deck ? "bridgeRoad" : "road";
            var control = HexAppearance.trafficControl(hex.getAppearance(), owner, exits);
            var road = BoardRoad.layout(coords, exits, kind,
                  direction -> roadNeighbor(hex, board.apply(coords.translated(direction)), direction, deck)).control(control);
            if (!deck) { road = road.circulate(at -> BoardRoad.Node.of(at.equals(coords) ? hex : board.apply(at), at, board)); }
            if (deck ? RailRoute.deckRail(hex) : RailRoute.groundSides(hex) != 0) { road = road.solidHub(); }
            var corridor = RailRoute.corridor(hex, coords, board, deck);
            road = road.corridor(corridor);
            if (!deck) {
                int aprons = 0;
                for (int direction = 0; direction < 6; direction++) {
                    Hex next = board.apply(coords.translated(direction));
                    if ((exits & 1 << direction) != 0 && next != null && HexTile.concrete(next)
                          && !HexTile.liquid(next) && BoardRoad.capture(next) == BoardRoad.Kind.NONE
                          && !bridgeApproach(hex, next, direction)) {
                        aprons |= 1 << direction;
                    }
                }
                road.aprons(aprons);
            }
            double level = deck ? BridgeSpan.deck(hex) : hex.getLevel();
            // Native rail terrain derives at most one marker on each layer.
            var marker = RailRoute.markers(hex, RailRoute.Kind.RAIL).stream()
                  .filter(route -> RailRoute.onDeck(route) == deck
                        && Math.abs(route.placement().level(hex) - level) < .001).findFirst().orElse(null);
            int sides = marker == null ? 0 : RailRoute.drawnSides(marker, hex);
            var crossing = marker == null ? new BoardRoad.Crossing(new Area(), List.of())
                  : road.crossing(exits, sides, RailRoute.pieces(marker, sides));
            var junction = road.stopApproaches(exits);
            var approaches = road.stopApproaches(exits, crossing);
            crossingControls(hex, coords, board, deck, road, corridor, marker, crossing, approaches, junction, result);
            if (junction.isEmpty()) { continue; }
            List<BoardRoad> clearance = deck ? List.of(road) : nearbyWays(hex, coords, board);
            for (var approach : approaches) {
                if (junction.stream().noneMatch(arm -> arm.direction() == approach.direction())) { continue; }
                // Other turning envelopes can widen this approach beyond its own lane. Follow the whole asphalt
                // edge along the stop plane, keeping the narrow base on its shoulder. Front is model +Y.
                float roadside = approach.outer() + .7f;
                float offset = roadside;
                while (road.distance(approach.x() - approach.ny() * offset,
                      approach.y() + approach.nx() * offset) < .5f) { offset += .25f; }
                String asset = control == HexAppearance.TrafficControl.STOPS ? "scenery/road/stop-sign" : "scenery/road/traffic-signal";
                BoardDecoration.Stretch stretch = BoardDecoration.Stretch.NONE;
                float setback = 0;
                if (control == HexAppearance.TrafficControl.GANTRY) {
                    var gantry = gantry(road, clearance, approach, offset, hex, coords, board, deck);
                    // Walking sideways can cross another branch and skip a small island beside this lane.
                    // Keep existing supported masts; retry that island from the original lane edge.
                    if (gantry == null && offset > roadside) {
                        gantry = gantry(road, clearance, approach, roadside, hex, coords, board, deck);
                    }
                    if (gantry != null) {
                        offset = gantry.offset();
                        setback = gantry.setback();
                        stretch = new BoardDecoration.Stretch(gantry.stretch(), 1, 1);
                        asset = "scenery/road/traffic-signal-gantry";
                    }
                }
                float x = approach.x() + approach.nx() * setback - approach.ny() * offset;
                float y = approach.y() + approach.ny() * setback + approach.nx() * offset;
                // A small island may begin just upstream of the bar. Keep every valid existing pole, otherwise
                // search at most 2px upstream and 1.5px outward from its lane edge using the same road union.
                if (controlClearance(road, clearance, x, y) < .5f
                      || !controlSupported(hex, coords, board, deck, x, y)) {
                    boolean found = false;
                    for (float back = 0; back <= 2 && !found; back += .25f) {
                        for (int step = 0; step <= 12; step++) {
                            float across = roadside + step * .125f;
                            float px = approach.x() + approach.nx() * back - approach.ny() * across;
                            float py = approach.y() + approach.ny() * back + approach.nx() * across;
                            float clear = controlClearance(road, clearance, px, py);
                            if (clear < .5f || (deck && clear > BoardBridge.SHOULDER - .5f)
                                  || !controlSupported(hex, coords, board, deck, px, py)) { continue; }
                            x = px; y = py; found = true;
                            break;
                        }
                    }
                    if (!found) { continue; }
                    asset = control == HexAppearance.TrafficControl.STOPS
                          ? "scenery/road/stop-sign" : "scenery/road/traffic-signal";
                    stretch = BoardDecoration.Stretch.NONE;
                }
                double rotation = Math.toDegrees(Math.atan2(-approach.nx(), approach.ny()));
                String id = TERRAIN_OWNED + coords.getX() + "_" + coords.getY() + ":" + owner + ":stop:" + approach.direction();
                result.add(feature(hex, new BoardDecoration(id, "prop", asset, null,
                      x / BoardGeometry.TILE_WIDTH, y / BoardGeometry.TILE_HEIGHT, rotation, false, 1,
                      BoardDecoration.Placement.on(deck ? "bridge" : "ground", 0), 0, false, 0, 0, null, false, 0, stretch)));
            }
        }
        return List.copyOf(result);
    }

    /** Apply the completed scene's fitted entrance clearance to captured controls, shared by every graphics mode. */
    static List<BoardScene.Feature> fittedControls(BoardScene scene, BoardScene.Tile tile) {
        if (tile.features().stream().noneMatch(feature -> groundControl(feature) >= 0)) { return tile.features(); }
        var road = BoardRoad.of(scene, tile);
        var approaches = road.stopApproaches(tile.roadExits(), road.crossing(tile, false));
        return tile.features().stream().filter(feature -> {
            int direction = groundControl(feature);
            return direction < 0 || approaches.stream().anyMatch(approach -> approach.direction() == direction);
        }).toList();
    }

    private static int groundControl(BoardScene.Feature feature) {
        var object = feature.decoration();
        if (object == null || !terrainOwned(object)) { return -1; }
        String id = object.id();
        if (!id.contains(":road:stop:") && !id.contains(":road:rail-crossing:")) { return -1; }
        return id.charAt(id.length() - 1) - '0';
    }

    /** The same rail-clearance stop frames as the paint, with existing authored crossing poles left in place. */
    private static void crossingControls(Hex hex, Coords coords, Function<Coords, Hex> board, boolean deck,
          BoardRoad road, RailRoute.Corridor corridor, BoardDecoration marker, BoardRoad.Crossing crossing,
          List<BoardRoad.StopApproach> approaches, List<BoardRoad.StopApproach> junction, List<BoardScene.Feature> result) {
        if (marker == null || crossing.approaches().isEmpty()) { return; }
        String asset = "scenery/rail/crossing-signal";
        var placement = BoardDecoration.Placement.on(deck ? "bridge" : "ground", 0);
        for (var approach : approaches) {
            if (crossing.approaches().stream().noneMatch(arm -> arm.direction() == approach.direction())) { continue; }
            float setback = junction.stream().anyMatch(arm -> arm.direction() == approach.direction()) ? 3 : 0;
            // A square footing fits on the verge. On skew crossings, walk towards waiting traffic until the
            // pole also clears the actual track; the road's lane and the rail use the same corridor here.
            for (; setback <= 8; setback += .5f) {
                float offset = approach.outer() + 1.4f;
                float x = approach.x() + approach.nx() * setback - approach.ny() * offset;
                float y = approach.y() + approach.ny() * setback + approach.nx() * offset;
                while (road.distance(x, y) < .7f) {
                    offset += .1f;
                    x = approach.x() + approach.nx() * setback - approach.ny() * offset;
                    y = approach.y() + approach.ny() * setback + approach.nx() * offset;
                }
                if (deck && road.distance(x, y) > BoardBridge.SHOULDER - .7f + .0001f) { continue; }
                var track = RailRoute.snap(hex, marker, x, y, 0, corridor);
                if (Math.hypot(track.x() - x, track.y() - y) < RailRoute.BALLAST_HALF_WIDTH + .7f
                      || !controlSupported(hex, coords, board, deck, x, y)) { continue; }
                float px = x, py = y;
                boolean authored = hex.getDecorations().stream().anyMatch(object -> object.asset().equals(asset)
                      && object.placement().equals(placement) && Math.hypot(object.x() * BoardGeometry.TILE_WIDTH - px,
                            object.y() * BoardGeometry.TILE_HEIGHT - py) < 6);
                if (!authored) {
                    String id = TERRAIN_OWNED + coords.getX() + "_" + coords.getY()
                          + (deck ? ":bridgeRoad" : ":road") + ":rail-crossing:" + approach.direction();
                    double rotation = Math.toDegrees(Math.atan2(-approach.nx(), approach.ny()));
                    result.add(feature(hex, new BoardDecoration(id, "prop", asset, null,
                          x / BoardGeometry.TILE_WIDTH, y / BoardGeometry.TILE_HEIGHT, rotation, false, 1,
                          placement, 0, false)));
                }
                break;
            }
        }
    }

    private record Gantry(float offset, float stretch, float setback) { }

    /** The same-layer road and rail courses share clearance even when their paint crosses a nominal hex edge. */
    private static float controlClearance(BoardRoad road, List<BoardRoad> nearby, float x, float y) {
        float distance = road.distance(x, y);
        for (var other : nearby) {
            // Keep this approach's exact road/rail layout, without rounding its shoulder through world coordinates.
            if (other.coords().equals(road.coords())) { continue; }
            float dx = HexTile.centerX(road.coords(), BoardGeometry.TILE_WIDTH)
                  - HexTile.centerX(other.coords(), BoardGeometry.TILE_WIDTH);
            float dy = HexTile.centerY(road.coords(), BoardGeometry.TILE_HEIGHT)
                  - HexTile.centerY(other.coords(), BoardGeometry.TILE_HEIGHT);
            distance = Math.min(distance, other.distance(x + dx, y + dy));
        }
        return distance;
    }

    /** Native dimensions first; modest X fitting changes reach without changing vertical vehicle clearance. */
    private static Gantry gantry(BoardRoad road, List<BoardRoad> nearby, BoardRoad.StopApproach approach, float roadside,
          Hex hex, Coords coords, Function<Coords, Hex> board, boolean deck) {
        // The authored GLB: heads 2m and 3.2m from the mast, .5m wide; square footing .42m, at 2.8 model px/m.
        float near = 5.6f, far = 8.96f, lamp = .7f, foot = .588f;
        for (boolean nativeSize : new boolean[] { true, false }) {
            // An acute crossing can pinch the verge at the stop bar. Move the broad mast up to .7m towards
            // waiting traffic before changing its proportions; its lamps still cover the same incoming lane.
            for (float setback = 0; setback <= 2; setback += .25f) {
                for (int step = 0; step <= 12; step++) {
                    float offset = roadside + step * .125f;
                    float minimum = Math.max(.85f, (offset - approach.outer()) / (near - lamp));
                    float maximum = Math.min(1.25f, (offset - approach.inner()) / (far + lamp));
                    if (minimum > maximum) { continue; }
                    float stretch = nativeSize ? 1 : Math.clamp(1, minimum, maximum);
                    if (stretch < minimum || stretch > maximum) { continue; }
                    boolean supported = true;
                    for (int side : new int[] { -1, 1 }) {
                        for (int along : new int[] { -1, 1 }) {
                            float across = offset + side * foot * stretch;
                            float x = approach.x() - approach.ny() * across + approach.nx() * (setback + along * foot);
                            float y = approach.y() + approach.nx() * across + approach.ny() * (setback + along * foot);
                            float clearance = controlClearance(road, nearby, x, y);
                            supported &= clearance >= .02f && (!deck || clearance <= BoardBridge.SHOULDER - .02f)
                                  && controlSupported(hex, coords, board, deck, x, y);
                        }
                    }
                    if (supported) { return new Gantry(offset, stretch, setback); }
                }
            }
        }
        // A crowded/offset rail apron may not support the broad footing or both heads over its incoming lane.
        return null;
    }

    /** A shoulder can overhang the grid; ground furniture still needs dry ground at the road's height. */
    private static boolean controlSupported(Hex hex, Coords coords, Function<Coords, Hex> board,
          boolean deck, float x, float y) {
        if (deck) { return true; }
        float worldX = HexTile.centerX(coords, BoardGeometry.TILE_WIDTH) + x;
        float worldY = HexTile.centerY(coords, BoardGeometry.TILE_HEIGHT) + y;
        for (int direction = -1; direction < 6; direction++) {
            Coords at = direction < 0 ? coords : coords.translated(direction);
            if (!HexTile.contains(at.getX(), at.getY(), worldX, worldY, BoardGeometry.TILE_WIDTH,
                  BoardGeometry.TILE_HEIGHT)) { continue; }
            Hex support = direction < 0 ? hex : board.apply(at);
            if (support != null && support.getLevel() == hex.getLevel() && !HexTile.liquid(support)
                  && !support.containsTerrain(Terrains.ULTRA_SUBLEVEL)) { return true; }
        }
        return false;
    }

    /** Capture the same joined road layer that BoardRoad.of and BoardBridge.deck use for the rendered paint. */
    private static BoardRoad.Kind roadNeighbor(Hex hex, Hex next, int direction, boolean deck) {
        if (next == null) { return BoardRoad.Kind.NONE; }
        int reverse = (direction + 3) % 6;
        if (deck) {
            if (next.containsTerrain(Terrains.BRIDGE) && BridgeSpan.joined(hex.getTerrain(Terrains.BRIDGE).getExits(),
                  BridgeSpan.deck(hex), next.getTerrain(Terrains.BRIDGE).getExits(), BridgeSpan.deck(next), direction)) {
                return next.containsTerrainExit(Terrains.BRIDGE_ROAD, reverse) ? BoardRoad.deck(next) : BoardRoad.Kind.NONE;
            }
            return bridgeApproach(next, hex, reverse) ? BoardRoad.capture(next) : BoardRoad.Kind.NONE;
        }
        if (bridgeApproach(hex, next, direction)) {
            return next.containsTerrainExit(Terrains.BRIDGE_ROAD, reverse) ? BoardRoad.deck(next) : BoardRoad.Kind.NONE;
        }
        return next.containsTerrainExit(Terrains.ROAD, reverse) ? BoardRoad.capture(next) : BoardRoad.Kind.NONE;
    }

    /** The road and deck must expose matching sides before their shared layer-choice predicate applies. */
    private static boolean bridgeApproach(Hex road, Hex bridge, int direction) {
        int reverse = (direction + 3) % 6;
        if (HexTile.liquid(road) || !bridge.containsTerrainExit(Terrains.BRIDGE, reverse)
              || (groundWays(road) & (1 << direction)) == 0) { return false; }
        boolean continuation = !HexTile.liquid(bridge) && (groundWays(bridge) & (1 << reverse)) != 0;
        return HexTile.bridgeApproach(road.getLevel(), bridge.getLevel(), BridgeSpan.deck(bridge), continuation);
    }

    private static int groundWays(Hex hex) {
        return (hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() : 0) | RailRoute.groundSides(hex);
    }

    /**
     * A terrain-owned layout's rows on {@code hex} (a geyser, rubble, fortified, a cleared-rubble path, an elevator's
     * level plate, cleared-rubble gravel) as features at their authored place: a mesh row a scenery feature, never a
     * park tree; a decal row a decal of the hex's support, as a placed decal is
     * ({@link BoardSceneryLayouts.Layout#decorations}), which the terrain owns: the hex never stores it, so it follows
     * its terrain (an elevator's plate shows its level) and is never picked as an object ({@link #terrainOwned}).
     */
    static List<BoardScene.Feature> features(BoardSceneryLayouts.Layout layout, Coords coords, Hex hex) {
        List<BoardScene.Feature> result = new ArrayList<>(layout.components().stream().filter(row -> !row.decal())
              .map(row -> new BoardScene.Feature(geyserLook(onGround(row.asset(), hex), coords), row.x(), row.y(),
                    row.rotation(), row.scale(), 1, row.z() / BoardGeometry.MODEL_LEVEL_HEIGHT,
                    BoardScene.FeatureKind.SCENERY, 0, true, null, row.stretch(), row.colours())).toList());
        if (layout.components().stream().anyMatch(BoardSceneryLayouts.Component::decal)) {
            // Each row's id names its hex and asset: unique on the board, as a stored object's id is.
            String prefix = TERRAIN_OWNED + coords.getX() + "_" + coords.getY() + ":";
            int[] next = { 0 };
            layout.decorations(coords, hex, () -> prefix + layout.components().get(next[0]).asset() + "#" + next[0]++,
                        null, false).stream()
                  .filter(object -> object.kind().equals("decal")).map(object -> feature(hex, object)).forEach(result::add);
        }
        return List.copyOf(result);
    }

    /** The fighting holes of a dug-in platoon ({@link #withTrench}). */
    static final String TRENCH = "scenery/military/trench";
    /**
     * A fortified hex's strongpoint, as its layout places it, dug into its ground; and, where that ground is paved, which
     * cannot be dug, its sandbag emplacement ({@link #onGround}).
     */
    static final String FORTIFICATION = "scenery/military/fortification", SANDBAGS = "scenery/military/sandbags";

    /** A geyser's look on its hex: each geyser asset has a second look, {@code <asset>-b}, one or the other per hex. */
    static String geyserLook(String asset, Coords coords) {
        if (!asset.startsWith(GpuGeysers.GEYSERS)) { return asset; }
        Random random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L ^ 0x67657973L);
        return random.nextBoolean() ? asset + "-b" : asset;
    }

    /** {@code asset} as built on {@code hex}'s ground: a strongpoint on pavement is sandbagged, not dug. */
    private static String onGround(String asset, Hex hex) {
        return asset.equals(FORTIFICATION) && hex.containsTerrain(Terrains.PAVEMENT) ? SANDBAGS : asset;
    }
    /** A Solaris elevator's shaft ({@link BoardScene.FeatureKind#SHAFT}). */
    static final String ELEVATOR_SHAFT = "elevator-shaft";

    /** Whether {@code tile} is a Solaris elevator's: a flat lift in a shaft whose straight walls are steel. */
    static boolean shaft(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.SHAFT);
    }

    /**
     * {@code tile} with the fighting holes of the platoons dug in there (TO:AR p.106): scenery like a terrain-owned row's,
     * which no hex stores, turned by its hex so neighbouring holes differ, showing the holes of formation slots
     * {@code holes} (bit N for slot N, {@link InfantryVisual#slots}). A fortified hex's strongpoint takes the platoons in
     * instead: they dig none there.
     */
    static BoardScene.Tile withTrench(BoardScene.Tile tile, int holes) {
        if (tile.features().stream().anyMatch(feature -> feature.authoredPlacement() && feature.decoration() == null
              && BoardGroundCut.digs(feature.asset()))) { return tile; }
        List<BoardScene.Feature> features = new ArrayList<>(tile.features());
        features.add(new BoardScene.Feature(TRENCH, 0, 0, 60 * Math.floorMod(tile.coords().hashCode(), 6), 1, 1, 0,
              BoardScene.FeatureKind.SCENERY, 0, true, null, megamek.common.board.BoardDecoration.Stretch.NONE,
              megamek.common.board.BoardDecoration.Colours.NONE, holes));
        return tile.withFeatures(features);
    }

    /** The timber a saw clearing leaves ({@link #withSawClearing}), modelled at real size. */
    static final String SAWN_STUMP = "rough/sawn-stump", SAWN_LOG = "rough/sawn-log";
    /** Half the sawn log's length (4.6 m) and its diameter, in tile px. */
    private static final float SAWN_LOG_REACH = 6.4f, SAWN_LOG_DIAMETER = 1.45f;

    /**
     * {@code tile} while saws clear its woods (TW p.112): of its trees, a share for each turn of work done, felled from
     * one side of the hex across (its hex's choice), each leaving a sawn stump where it stood. Their logs, cut to one
     * length, lie in a low pile along that side, as near it as the trees and stumps leave room: half of them side by
     * side on the ground, the rest centred in the grooves between them (loose logs roll off anything higher). The game
     * lowers the woods when the work is done.
     */
    static BoardScene.Tile withSawClearing(BoardScene.Tile tile, int turnsRemaining) {
        List<BoardScene.Feature> trees = tile.features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).toList();
        if (trees.isEmpty()) { return tile; }
        int required = WoodsClearingTracker.TURNS_REQUIRED_SINGLE;
        int done = Math.clamp(required + 1 - turnsRemaining, 1, required);
        int felled = Math.max(1, Math.round(trees.size() * done / (required + 1f)));
        double heading = Math.toRadians(60 * Math.floorMod(tile.coords().hashCode(), 6) + 30);
        float dx = (float) Math.cos(heading), dy = (float) Math.sin(heading);
        List<BoardScene.Feature> cut = trees.stream()
              .sorted(Comparator.comparingDouble(tree -> tree.x() * dx + tree.y() * dy)).limit(felled).toList();
        Random random = new Random(tile.coords().hashCode() * 31L + 0x5a3);
        List<BoardScene.Feature> features = new ArrayList<>(tile.features());
        features.removeAll(cut);
        for (BoardScene.Feature tree : cut) {
            // The board's trees are drawn larger than life; their timber matches their trunks.
            features.add(new BoardScene.Feature(SAWN_STUMP, tree.x(), tree.y(), random.nextFloat() * 360,
                  2 * tree.scale(), 0, 0, BoardScene.FeatureKind.ROUGH));
        }
        float size = (float) cut.stream().mapToDouble(tree -> 2 * tree.scale()).average().orElseThrow();
        float diameter = SAWN_LOG_DIAMETER * size, reach = SAWN_LOG_REACH * size;
        int bottom = (cut.size() + 2) / 2, upper = cut.size() - bottom;
        float depth = bottom * diameter;
        // The pile's far side: within its hex side and, at the logs' ends, the two sides beside it.
        float edge = Math.min(BoardGeometry.TILE_HEIGHT / 2f - size, BoardGeometry.TILE_HEIGHT - 1.8f * reach);
        float far = edge;
        while (far > depth && blocked(trees, dx, dy, far - depth, far, reach, 2 * size)) { far--; }
        if (far <= depth) { far = edge; }
        for (int i = 0; i < cut.size(); i++) {
            // From the pile's near side: the ground row's centres, then the grooves between them.
            boolean top = i >= bottom;
            float across = top ? (bottom - 1 - upper) / 2 + i - bottom + 1 : i + .5f;
            float back = far - depth + across * diameter;
            float side = (random.nextFloat() - .5f) * size;
            features.add(new BoardScene.Feature(SAWN_LOG, -dx * back - dy * side, -dy * back + dx * side,
                  (float) Math.toDegrees(heading) + 90 + (random.nextFloat() - .5f) * 2, size, 0,
                  top ? diameter * .866f / BoardGeometry.MODEL_LEVEL_HEIGHT : 0, BoardScene.FeatureKind.ROUGH));
        }
        return tile.withFeatures(features);
    }

    /**
     * True when a trunk, with its roots within {@code trunk}, stands between {@code near} and {@code far} behind the hex
     * centre, across a log's reach.
     */
    private static boolean blocked(List<BoardScene.Feature> trees, float dx, float dy, float near, float far, float reach,
          float trunk) {
        return trees.stream().anyMatch(tree -> {
            float back = -(tree.x() * dx + tree.y() * dy), side = tree.y() * dx - tree.x() * dy;
            return back > near - trunk && back < far + trunk && Math.abs(side) < reach + trunk;
        });
    }

    /** A crate of cargo set down on the ground ({@link #withCargo}). */
    static final String CARGO = "scenery/military/cargo-crate";

    /** {@code tile} with cargo set down on it: a crate beside the middle, where a unit stands, turned by its hex. */
    static BoardScene.Tile withCargo(BoardScene.Tile tile) {
        int side = Math.floorMod(tile.coords().hashCode(), 6);
        double bearing = Math.toRadians(60 * side + 30);
        List<BoardScene.Feature> features = new ArrayList<>(tile.features());
        // Larger than life, as the units are; far enough out that its inner end stays clear of the unit.
        features.add(new BoardScene.Feature(CARGO, (float) Math.cos(bearing) * 19, (float) Math.sin(bearing) * 19,
              60 * side + 15, 1.6f, 1, 0, BoardScene.FeatureKind.SCENERY, 0, true));
        return tile.withFeatures(features);
    }

    /** What {@code feature} has stencilled on it ({@link RigidGlb#label}): a cargo crate's number, two digits its hex picks. */
    static String label(BoardScene.Tile tile, BoardScene.Feature feature) {
        if (!feature.asset().equals(CARGO)) { return ""; }
        int mixed = (tile.coords().getX() * 73_856_093) ^ (tile.coords().getY() * 19_349_663);
        return Integer.toString(10 + Math.floorMod(mixed, 90));
    }

    /**
     * The id prefix of the decals terrain-owned rows paint ({@link #features}), of the buffer stops a rail owns and of a
     * vegetation skin's rows ({@link #decorations}): no stored object has it.
     */
    private static final String TERRAIN_OWNED = "terrain:";

    /**
     * An object its terrain, its rail or its woods' skin owns ({@link #features}, {@link #decorations}), which no hex
     * stores: never selected.
     */
    static boolean terrainOwned(megamek.common.board.BoardDecoration object) { return object.id().startsWith(TERRAIN_OWNED); }

    /** A row of a woods cover's skin (a container yard, {@link #decorations}): the cover's look, kept in the Tactical View. */
    static boolean coverSkin(megamek.common.board.BoardDecoration object) {
        return terrainOwned(object) && object.id().contains(SKIN_ROW);
    }

    /** The id infix of a skin's rows ({@link #decorations}). */
    private static final String SKIN_ROW = ":skin#";

    /** Whether a placed tree of this species turns to its {@code -snow} form on snow. */
    static boolean hasSnowForm(String asset) { return SNOW_FORMS.contains(asset); }

    /** The one snow rule: a tree on a snow surface takes its winter form unless it is kept {@code bare}. */
    static String snowForm(Hex hex, String asset, boolean bare) {
        return !bare && hasSnowForm(asset) && surface(hex) == BoardScene.Surface.SNOW ? asset + "-snow" : asset;
    }

    /** A placed object's drawn asset, by {@link #snowForm}. */
    static String drawnAsset(Hex hex, megamek.common.board.BoardDecoration object) {
        return snowForm(hex, object.asset(), object.bare());
    }

    /**
     * The receiving surface a drawn structure offers placed objects (see {@link
     * megamek.common.board.BoardDecoration.Receiver}): a building's roof, an industrial top or a fuel tank's top, or
     * null. A bridge offers its deck through its own shape.
     */
    static String receiver(BoardScene.Feature feature) {
        return feature.decoration() != null ? null
              : feature.kind() == BoardScene.FeatureKind.BUILDING ? "building"
              : feature.kind() == BoardScene.FeatureKind.INDUSTRIAL ? "industrial"
              : feature.kind() == BoardScene.FeatureKind.FUEL_TANK ? "fuelTank" : null;
    }

    /**
     * The frame of authored scenery (a feature with {@link BoardScene.Feature#authoredPlacement}) anchored at (x, y) on
     * the supporting plane {@code base}: the drawn model's and its ground cut's ({@link BoardGroundCut}).
     */
    static com.badlogic.gdx.math.Matrix4 authoredTransform(float x, float y, float base, BoardScene.Feature feature) {
        float scale = feature.scale() * BoardGeometry.hexScale();
        return new com.badlogic.gdx.math.Matrix4().setToTranslation(x, y,
                    base + feature.elevation() * BoardGeometry.MODEL_LEVEL_HEIGHT * BoardGeometry.hexScale())
              .rotate(com.badlogic.gdx.math.Vector3.Z, feature.rotation()).scale(scale * (float) feature.stretch().x(),
                    scale * (float) feature.stretch().y(), scale * (float) feature.stretch().z());
    }

    /** The same authored frame drives the drawn model, picking, shadows and ground-cover clearance. */
    static com.badlogic.gdx.math.Matrix4 decorationTransform(Coords coords, BoardScene.Feature feature, float z) {
        var object = feature.decoration();
        float scale = feature.scale() * RailRoute.visualScale(feature.asset()) * BoardGeometry.hexScale();
        return new com.badlogic.gdx.math.Matrix4().setToTranslation(
                    BoardGeometry.centerX(coords) + feature.x() * BoardGeometry.hexScale(),
                    BoardGeometry.centerY(coords) + feature.y() * BoardGeometry.hexScale(), z)
              .rotate(com.badlogic.gdx.math.Vector3.Z, (float) object.rotation())
              .rotate(com.badlogic.gdx.math.Vector3.Y, (float) object.rotationY())
              .rotate(com.badlogic.gdx.math.Vector3.X, (float) object.rotationX())
              .scale(scale * (float) feature.stretch().x() * (object.mirror() ? -1 : 1), scale * (float) feature.stretch().y(),
                    scale * (float) feature.stretch().z());
    }

    /** The hex's bridge, at its height above the hex's level (BoardBridge.deckZ). */
    private static void addBridge(Hex hex, List<BoardScene.Feature> result) {
        if (hex.containsTerrain(Terrains.BRIDGE)) {
            int exits = hex.getTerrain(Terrains.BRIDGE).getExits() & 63;
            result.add(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1,
                  hex.terrainLevel(Terrains.BRIDGE_ELEV), BoardScene.FeatureKind.PROP, exits));
        }
    }

    /**
     * The rails the hex's rail terrain yields ({@link RailRoute#derived}) and its stored objects, as features, each rail's
     * buffer stops after it ({@link RailRoute#stops} of its drawn sides), then the rows of its vegetation skin
     * ({@link BoardSceneryLayouts#skin}, a container yard): the terrain, the rail or the woods own them, so the hex never
     * stores them, they come and go with the terrain, the rail's sides or the woods' cover and are never picked as
     * objects ({@link #terrainOwned}). The stops ride the rail ({@link RailRoute#ride}). A destroyed rail yields no track
     * and no stops.
     */
    static List<BoardScene.Feature> decorations(Hex hex, Coords coords) {
        return decorations(hex, coords, RailRoute.Corridor.NONE, RailRoute.Corridor.NONE);
    }

    private static List<BoardScene.Feature> decorations(Hex hex, Coords coords, Function<Coords, Hex> board) {
        return decorations(hex, coords, RailRoute.corridor(hex, coords, board), RailRoute.corridor(hex, coords, board, true));
    }

    private static List<BoardScene.Feature> decorations(Hex hex, Coords coords,
          RailRoute.Corridor ground, RailRoute.Corridor deck) {
        List<BoardScene.Feature> result = new ArrayList<>();
        int bridgeExits = hex.containsTerrain(Terrains.BRIDGE) ? hex.getTerrain(Terrains.BRIDGE).getExits() : 0;
        String owned = TERRAIN_OWNED + coords.getX() + "_" + coords.getY() + ":";
        List<megamek.common.board.BoardDecoration> objects = new ArrayList<>(RailRoute.derived(hex, owned));
        objects.addAll(hex.getDecorations());
        for (var object : objects) {
            if (liveCrossing(hex, coords, objects, object, ground, deck)) { continue; }
            result.add(feature(hex, object));
            if (RailRoute.kind(object) != RailRoute.Kind.RAIL) { continue; }
            int index = 0;
            String stops = terrainOwned(object) ? object.id() : owned + object.id();
            var corridor = RailRoute.onDeck(object) ? deck : ground;
            for (var stop : RailRoute.stops(RailRoute.drawnSides(object, bridgeExits), corridor)) {
                result.add(feature(hex, new megamek.common.board.BoardDecoration(stops + "#" + index++, "prop",
                      RailRoute.BUFFER_STOP, null, object.x() + stop.x() / BoardGeometry.TILE_WIDTH,
                      object.y() + stop.y() / BoardGeometry.TILE_HEIGHT,
                      stop.rotation(), false, 1, RailRoute.ride(object), 0, false)));
            }
        }
        var skin = BoardSceneryLayouts.skin(hex);
        if (skin != null) {
            int[] next = { 0 };
            String prefix = TERRAIN_OWNED + coords.getX() + "_" + coords.getY() + SKIN_ROW;
            skin.decorations(coords, hex, () -> prefix + next[0]++, null, false).forEach(object -> result.add(feature(hex, object)));
        }
        return List.copyOf(result);
    }

    /** Old saved crossings yield to the live road and embedded rails only on their own receiving surface. */
    private static boolean liveCrossing(Hex hex, Coords coords, List<BoardDecoration> objects, BoardDecoration object,
          RailRoute.Corridor ground, RailRoute.Corridor deck) {
        if (!object.asset().equals("decal/rail/level-crossing") || object.placement().receiver() == null) { return false; }
        boolean onDeck = RailRoute.onDeck(object);
        int terrain = onDeck ? Terrains.BRIDGE_ROAD : Terrains.ROAD;
        if (!hex.containsTerrain(terrain)) { return false; }
        var kind = onDeck ? BoardRoad.deck(hex) : BoardRoad.capture(hex);
        var corridor = onDeck ? deck : ground;
        var road = BoardRoad.layout(coords, hex.getTerrain(terrain).getExits(), kind, d -> kind).corridor(corridor);
        float x = (float) object.x() * BoardGeometry.TILE_WIDTH, y = (float) object.y() * BoardGeometry.TILE_HEIGHT;
        if (!road.street(x, y)) { return false; }
        for (var marker : objects) {
            if (RailRoute.kind(marker) != RailRoute.Kind.RAIL || !marker.placement().equals(object.placement())) { continue; }
            var point = RailRoute.snap(hex, marker, x, y, 0, corridor);
            if (Math.hypot(point.x() - x, point.y() - y) <= RailRoute.BALLAST_HALF_WIDTH) { return true; }
        }
        return false;
    }

    /** An object of {@code hex} as its feature, at its place. */
    private static BoardScene.Feature feature(Hex hex, megamek.common.board.BoardDecoration object) {
        return new BoardScene.Feature(drawnAsset(hex, object),
              (float) (object.x() * BoardGeometry.TILE_WIDTH), (float) (object.y() * BoardGeometry.TILE_HEIGHT),
              (float) object.rotation(), (float) object.scale(), 1, 0, BoardScene.FeatureKind.PROP, 0, false, object);
    }

    /** Rough is actual terrain cover, independent of cosmetic scatter density, with larger cover for ultra rough. */
    private static void rough(Hex hex, Coords coords, List<BoardScene.Feature> result, Function<Coords, Hex> board) {
        if (!hex.containsTerrain(Terrains.ROUGH)
              || hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL,
                    Terrains.SPACE, Terrains.SKY, Terrains.MAGMA)) { return; }
        boolean teeth = HexTile.roughVariant(hex) == 1;
        boolean felled = HexTile.roughVariant(hex) == 2;
        boolean ultra = hex.terrainLevel(Terrains.ROUGH) == 2;
        int count = teeth ? (ultra ? 24 : 10) : felled ? (ultra ? 12 : 9) : ultra ? 18 : 9;
        List<BoardRoad> roads = nearbyWays(hex, coords, board);
        int exits = hex.containsTerrain(Terrains.BRIDGE) ? hex.getTerrain(Terrains.BRIDGE).getExits() : 0;
        BoardRoad bridge = hex.containsTerrain(Terrains.BRIDGE) ? BoardRoad.clearance(coords, exits) : null;
        Predicate<BoardScene.Feature> clear = feature -> {
            float x = feature.x(), y = feature.y(), radius = roughRadius(feature);
            // Bridge decks follow the complete road footprint, including dead ends and solid roundabout islands, and
            // reach their own shoulder beyond it.
            return BoardRoad.distance(roads, BoardGeometry.centerX(coords) + x * BoardGeometry.hexScale(),
                  BoardGeometry.centerY(coords) + y * BoardGeometry.hexScale()) >= BoardRoad.SHOULDER + radius
                  && (bridge == null || bridge.distance(x, y) >= BoardBridge.SHOULDER + radius
                        && !(BoardRoad.roundabout(exits) && Math.hypot(x, y) < BoardRoad.ROUNDABOUT_RADIUS));
        };
        // A route clears the cover off its line and keeps the count beside it: teeth close their spacing.
        if (teeth) {
            // Standard teeth spread across the hex; ultra rough fills it at the closest spacing.
            result.addAll(dragonTeeth(count, ultra ? CLOSE_TEETH_SPACING : 20, clear));
            return;
        }
        // Other pieces stay where they lie, and those on the route are pushed off it onto its verge.
        var pieces = felled ? roughSpiral(coords, true, ultra, count) : roughRocks(coords, count);
        List<BoardScene.Feature> placed = new ArrayList<>(pieces.stream().filter(clear).toList());
        for (var piece : pieces) {
            if (clear.test(piece)) { continue; }
            var pushed = pushedAside(piece, clear, placed);
            if (pushed != null) { placed.add(pushed); }
        }
        result.addAll(placed);
    }

    /** The footprint radius of a rough piece, in tile pixels: rock meshes fit a square, so a corner reaches further. */
    private static float roughRadius(BoardScene.Feature feature) {
        return feature.scale() * switch (feature.asset()) {
            case "rough/charred-stump" -> 3.1f;
            case "rough/dragon-tooth" -> DRAGON_TOOTH_RADIUS;
            case "rough-boulder", BoardRocks.OUTCROP -> ROUGH_BOULDER_WIDTH * .65f;
            default -> ROUGH_BOULDER_WIDTH / 2;
        };
    }

    /**
     * {@code piece} moved the shortest way onto clear ground wholly inside its hex, as if shoved off a road: pieces
     * pushed together may overlap a little, never sit inside each other. Where the verge is crowded (a road ending in
     * the hex), a smaller piece still fits, as if broken up on the way. Null without such ground.
     */
    private static BoardScene.Feature pushedAside(BoardScene.Feature piece, Predicate<BoardScene.Feature> clear,
          List<BoardScene.Feature> placed) {
        for (float size : new float[] { 1, .8f, .65f, .5f, .35f }) {
            float radius = roughRadius(piece) * size;
            for (int distance = 1; distance <= BoardGeometry.TILE_WIDTH; distance++) {
                for (int step = 0; step < 24; step++) {
                    double angle = step * Math.PI / 12;
                    float x = piece.x() + distance * (float) Math.cos(angle);
                    float y = piece.y() + distance * (float) Math.sin(angle);
                    if (hexMargin(x, y) < radius) { continue; }
                    var moved = new BoardScene.Feature(piece.asset(), x, y, piece.rotation(), piece.scale() * size,
                          piece.height() * size, piece.elevation(), piece.kind());
                    if (clear.test(moved) && placed.stream().allMatch(other -> Math.hypot(x - other.x(), y - other.y())
                          >= .6f * (radius + roughRadius(other)))) { return moved; }
                }
            }
        }
        return null;
    }

    /**
     * Identical concrete teeth in staggered rows, nearest the centre first, wherever a whole tooth lies inside the
     * hex on clear ground. Until that ground holds the count, the rows shift sideways to straddle a route and the
     * spacing closes, down to ultra rough's.
     */
    private static List<BoardScene.Feature> dragonTeeth(int count, float spacing, Predicate<BoardScene.Feature> clear) {
        List<BoardScene.Feature> teeth = List.of();
        for (float pitch = spacing; teeth.size() < count && pitch >= CLOSE_TEETH_SPACING; pitch -= .5f) {
            for (float shift = 0; teeth.size() < count && shift < 1; shift += .25f) {
                List<BoardScene.Feature> slots = new ArrayList<>();
                int reach = (int) (BoardGeometry.TILE_WIDTH / 2 / pitch) + 1;
                for (int row = -reach; row <= reach; row++) {
                    for (int column = -reach; column <= reach; column++) {
                        // Alternate rows sit halfway between each other's teeth; the centre row straddles the centre.
                        float x = (column + .5f + (row & 1) * .5f - shift) * pitch, y = row * pitch;
                        var tooth = new BoardScene.Feature("rough/dragon-tooth", x, y, 0, 1, .28f, 0,
                              BoardScene.FeatureKind.ROUGH);
                        if (hexMargin(x, y) >= DRAGON_TOOTH_RADIUS && clear.test(tooth)) { slots.add(tooth); }
                    }
                }
                slots.sort(Comparator.comparingDouble(tooth -> Math.hypot(tooth.x(), tooth.y())));
                if (slots.size() > teeth.size()) { teeth = slots.subList(0, Math.min(count, slots.size())); }
            }
        }
        return teeth;
    }

    /** Distance in tile pixels from an offset about the hex centre to the nearest hex edge. */
    private static float hexMargin(float x, float y) {
        float w = BoardGeometry.TILE_WIDTH / 2, h = BoardGeometry.TILE_HEIGHT / 2;
        float slope = (h * (w - Math.abs(x)) - w / 2 * Math.abs(y)) / (float) Math.hypot(h, w / 2);
        return Math.min(h - Math.abs(y), slope);
    }

    /** Fractured groups: a rooted mass and two smaller pieces, using the existing bounded cover budget. */
    private static List<BoardScene.Feature> roughRocks(Coords coords, int count) {
        Random random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L ^ 0xb01deL);
        // Board-space noise gives nearby hexes a common fracture direction, independent of camera, scale and LOD.
        float strike = 360 * BoardRelief.noise(coords.getX() * .15f,
              -(coords.getY() + (coords.getX() & 1) * .5f) * BoardGeometry.TILE_HEIGHT / (5 * BoardGeometry.TILE_WIDTH));
        // The count turns the groups, so standard and ultra rough differ.
        double phase = random.nextFloat() * 2 * Math.PI + count * 2.399963, bearing = phase;
        float turn = strike;
        List<BoardScene.Feature> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int part = i % 3;
            if (part == 0) {
                bearing = phase + (i / 3) * 2.399963 + random.nextFloat() * .45;
                turn = strike + (random.nextFloat() - .5f) * 26;
            }
            float size = part == 0 ? .96f + random.nextFloat() * .22f
                  : part == 1 ? .64f + random.nextFloat() * .16f : .38f + random.nextFloat() * .18f;
            float height = part == 0 ? .42f + random.nextFloat() * .12f
                  : part == 1 ? .25f + random.nextFloat() * .11f : .14f + random.nextFloat() * .08f;
            // Keep the old equal-area reach: grouping changes bearings, not the required cover at slopes/feet.
            // The parent stays far enough inside to support the larger zero-gravity outcrop too.
            float radius = 29 * (float) Math.sqrt(i / (count - 1f));
            if (part == 0) { radius = Math.min(23, radius); }
            double angle = bearing + (part == 0 ? 0 : part == 1 ? .4 : -.4) + random.nextFloat() * .15;
            result.add(new BoardScene.Feature("rough-boulder", (float) Math.cos(angle) * radius,
                  (float) Math.sin(angle) * radius,
                  turn + (random.nextFloat() - .5f) * (part == 0 ? 12 : 50), size, height, 0,
                  BoardScene.FeatureKind.BOULDER));
        }
        return result;
    }

    /** Boulders or fallen timber on an equal-area spiral of {@code count} pieces. */
    private static List<BoardScene.Feature> roughSpiral(Coords coords, boolean felled, boolean ultra, int count) {
        // Every third piece is a large stump; in ultra rough, every second.
        int stumps = ultra ? 2 : 3;
        Random random = new Random(coords.getX() * 73_856_093L ^ coords.getY() * 19_349_663L ^ 0xb01deL);
        List<BoardScene.Feature> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = i * 2.399963 + random.nextFloat() * .45 + random.nextFloat();
            // Equal-area cover includes the centre and the slopes; units do not reserve an empty ring in Rough.
            float radius = 29 * (float) Math.sqrt(i / (count - 1f));
            float x = (float) Math.cos(angle) * radius, y = (float) Math.sin(angle) * radius;
            float size = .55f + random.nextFloat() * .55f;
            float height = .22f + random.nextFloat() * .32f;
            String asset = "rough-boulder";
            if (felled) {
                asset = "rough/felled-trunk";
                // Large standing stumps, every third one toppled, among the smaller broken branches.
                if (i % stumps == 0) {
                    boolean fallen = i / stumps % 3 == 1;
                    asset = fallen ? "rough/fallen-stump" : "rough/charred-stump";
                    size = 3 + random.nextFloat() * .4f;
                    height = (1.8f + random.nextFloat() * .3f) * (fallen ? .5f : 1);
                    // Lay long pieces around the centre, leaving room for the standing trunk there. A standing
                    // trunk keeps its whole footprint, three pixels per unit of size, inside the hex.
                    float reach = fallen ? 20 : Math.min(radius, BoardGeometry.TILE_HEIGHT / 2 - 3 * size);
                    x = (float) Math.cos(angle) * reach;
                    y = (float) Math.sin(angle) * reach;
                } else {
                    height = .10f + random.nextFloat() * .07f;
                }
            }
            float rotation = random.nextFloat() * 360;
            if (asset.equals("rough/fallen-stump")) { rotation = (float) Math.toDegrees(angle) + 90; }
            result.add(new BoardScene.Feature(asset, x, y, rotation, size, height, 0,
                  felled ? BoardScene.FeatureKind.ROUGH : BoardScene.FeatureKind.BOULDER));
        }
        return result;
    }

    /**
     * The trees that grow on this hex's ground: palms in jungle; cacti, palms and dead trees in the desert; conifers
     * on rock and on highland meadows two levels up or more; willows on wet dirt; dead trees on the Moon;
     * park trees on pavement. Snowfields grow the highland's conifers and birches, and snow keeps every species in its
     * winter form.
     */
    private static List<String> species(Hex hex, boolean jungle) {
        BoardScene.Surface surface = surface(hex);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        boolean snow = surface == BoardScene.Surface.SNOW;
        if (!snow && tropical(hex)) { return TROPICAL; }
        if (!snow && jungle) { return PALMS; }
        if (!snow && desert(hex)) { return DESERT; }
        List<String> trees = theme.contains("lunar") ? BARREN : switch (surface) {
            case ROCK, LUNAR, VOLCANO -> ROCKY;
            case DIRT -> WETLAND;
            case CONCRETE -> BoardTreeDistribution.PARK;
            case SNOW -> HIGHLAND;
            default -> hex.getLevel() >= 2 ? HIGHLAND : TEMPERATE;
        };
        return snow ? trees.stream().map(tree -> tree + "-snow").toList() : trees;
    }

    private static boolean desert(Hex hex) {
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        return theme.contains("desert") || theme.contains("sand");
    }

    private static boolean tropical(Hex hex) {
        return hex.getTheme() != null && hex.getTheme().toLowerCase(Locale.ROOT).contains("tropical");
    }

    private static List<String> orchardSpecies(Hex hex) {
        return surface(hex) == BoardScene.Surface.SNOW
              ? ORCHARD.stream().map(tree -> tree + "-snow").toList() : ORCHARD;
    }

    /** A shrub family grows three shapes, so level-one cover never repeats one bush across a hex. */
    private static List<String> shrubVariants(String shrub) {
        return List.of(shrub, shrub + "-2", shrub + "-3");
    }

    /** Level-one woods and jungle are understory, with their own low-growing geometry. */
    private static String shrub(Hex hex, boolean jungle) {
        BoardScene.Surface surface = surface(hex);
        String theme = hex.getTheme() == null ? "" : hex.getTheme().toLowerCase(Locale.ROOT);
        String family;
        if (surface == BoardScene.Surface.SNOW) {
            family = "snow";
        } else if (jungle || tropical(hex)) {
            family = "jungle";
        } else if (desert(hex)) {
            family = "desert";
        } else if (theme.contains("lunar")) {
            family = "barren";
        } else if (hex.containsAnyTerrainOf(Terrains.SWAMP, Terrains.MUD, Terrains.WATER)) {
            family = "wetland";
        } else {
            family = switch (surface) {
                case ROCK, LUNAR, VOLCANO -> "rocky";
                case DIRT -> "wetland";
                case CONCRETE -> "temperate";
                default -> hex.getLevel() >= 2 || hex.containsTerrain(Terrains.TUNDRA) ? "highland" : "temperate";
            };
        }
        return "vegetation/foliage-" + family;
    }

    private static void add(List<BoardScene.Feature> result, String asset, float height, float elevation, float rotation) {
        result.add(new BoardScene.Feature(asset, 0, 0, rotation, 1, height, elevation));
    }
}
