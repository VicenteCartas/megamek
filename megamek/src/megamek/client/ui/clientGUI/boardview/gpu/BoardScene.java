/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.swing.ImageIcon;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Terrains;

/** A presentation snapshot. Only the Swing thread reads the game; the GPU thread owns rendering. */
record BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
      List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
      List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical,
      List<RangeLabel> rangeLabels, BoardFieldOfView fieldOfView, BoardElectronicWarfare electronicWarfare,
      List<Designation> designations, List<StarShell> starShells) {

    /** A scene with no TAG designations in effect and no illumination rounds burning. */
    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical,
          List<RangeLabel> rangeLabels, BoardFieldOfView fieldOfView, BoardElectronicWarfare electronicWarfare) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, tactical, rangeLabels, fieldOfView, electronicWarfare, List.of(), List.of());
    }

    /**
     * An illumination round's flare (a star shell) hanging over the board.
     *
     * @param coords    the hex it hangs over
     * @param radius    the hexes around it that it lights
     * @param turnsLeft the turns it has left to burn, counting this one
     * @param lit       whether it is burning yet: under the Core Rules it lights at the End phase of its arrival turn
     */
    record StarShell(Coords coords, int radius, int turnsLeft, boolean lit) { }

    /** A scene without E(C)CM: map previews, the editor and tests. */
    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical,
          List<RangeLabel> rangeLabels, BoardFieldOfView fieldOfView) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, tactical, rangeLabels, fieldOfView, BoardElectronicWarfare.EMPTY);
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical,
          List<RangeLabel> rangeLabels) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, tactical, rangeLabels, BoardFieldOfView.EMPTY);
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers, BoardTactical tactical) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, tactical, List.of());
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders, List<BoardMarker> markers) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, markers, BoardTactical.EMPTY);
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light,
          List<FiringLine> firingLines, List<RangeBorder> rangeBorders) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, firingLines,
              rangeBorders, List.of());
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands, Light light) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, light, List.of(), List.of());
    }

    BoardScene(int boardId, int width, int height, List<Tile> tiles, List<Unit> units,
          List<Waypoint> plannedPath, int selectedId, String phase, List<Command> commands) {
        this(boardId, width, height, tiles, units, plannedPath, selectedId, phase, commands, null);
    }

    /**
     * The hex's tile, as {@link BoardTiles} captures every hex: {@code pixels} is its art, {@code previous} the tile it
     * replaces (its unchanged pixels are kept), and {@code board} supplies the neighbouring roads, whose course through
     * this hex its scenery keeps clear of; {@code buildings} the board's building at a hex, or null where the board
     * keeps none (the editor, a map preview). Nothing is decoded here: the type, Pillars and roads are the hex's own.
     */
    static Tile captureTile(megamek.common.Hex hex, BoardArtwork.HexImage pixels, Tile previous, PixelPool terrainImages,
          java.util.function.Function<Coords, megamek.common.Hex> board) {
        return captureTile(hex, pixels, previous, terrainImages, board, (Set<String>) null);
    }

    static Tile captureTile(megamek.common.Hex hex, BoardArtwork.HexImage pixels, Tile previous, PixelPool terrainImages,
          java.util.function.Function<Coords, megamek.common.Hex> board, Set<String> buildingReferences) {
        return captureTile(hex, pixels, previous, terrainImages, board, coords -> null, buildingReferences);
    }

    static Tile captureTile(megamek.common.Hex hex, BoardArtwork.HexImage pixels, Tile previous, PixelPool terrainImages,
          java.util.function.Function<Coords, megamek.common.Hex> board,
          java.util.function.Function<Coords, megamek.common.units.IBuilding> buildings, Set<String> buildingReferences) {
        return captureTile(hex, pixels, previous, terrainImages, board, buildings, buildingReferences, null);
    }

    /** {@code searchedWoods}: the hex's woods if the capture ran their search ahead (BoardTiles), else null. */
    static Tile captureTile(megamek.common.Hex hex, BoardArtwork.HexImage pixels, Tile previous, PixelPool terrainImages,
          java.util.function.Function<Coords, megamek.common.Hex> board,
          java.util.function.Function<Coords, megamek.common.units.IBuilding> buildings, Set<String> buildingReferences,
          List<Feature> searchedWoods) {
        // A fallen span's landings still carry the road and track that ran onto them, and the ways beside it still end
        // there, as they stood (bridgeAsItStood); only its deck, its paint and what stood on it are gone.
        var stood = bridgeAsItStood(hex);
        java.util.function.Function<Coords, megamek.common.Hex> ways = at -> bridgeAsItStood(board.apply(at));
        return new BoardScene.Tile(pixels.coords(), hex.getLevel(),
              hex.containsTerrain(Terrains.WATER) ? Math.max(0, hex.terrainLevel(Terrains.WATER)) : -1,
              hex.containsTerrain(Terrains.ICE),
              hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() & 63 : 0,
              BoardFeatures.surface(hex),
              terrainImages.captureOverlay(pixels.decals(), previous == null ? null : previous.decals()),
              terrainImages.capture(pixels.decalsWithoutLimbs(), previous == null ? null : previous.decalsWithoutLimbs()),
              BoardFeatures.capture(hex, pixels.coords(), pixels.structureModels(), pixels.blankTerrains(), ways, pixels.scenery(),
                    megamek.common.board.BoardTreeDistribution.NATURAL, buildingReferences, searchedWoods),
              pixels.text(), BoardLiquid.capture(hex),
              terrainImages.capture(pixels.tileset(), previous == null ? null : previous.tileset()),
              BoardFeatures.detailedGround(hex, pixels.structureModels(), pixels.blankTerrains(), pixels.scenery()),
              BoardRoad.capture(hex),
              BoardFireSmoke.capture(hex), BoardFeatures.biome(hex), hex.containsTerrain(Terrains.IMPASSABLE),
              hex.containsTerrain(Terrains.BLACK_ICE) && hex.getTerrain(Terrains.BLACK_ICE).isBlackIceDetected(),
              cliffTopExits(hex, pixels.coords(), board), false, BoardSurfaceBlend.capture(hex),
              hex.containsTerrain(Terrains.ULTRA_SUBLEVEL),
              terrainImages.captureOverlay(pixels.tilesetDecals(), previous == null ? null : previous.tilesetDecals()),
              terrainImages.captureOverlay(pixels.tilesetScenery(), previous == null ? null : previous.tilesetScenery()),
              hex.getAppearance(), Cover.capture(hex, buildings.apply(pixels.coords()), pixels.coords()), false,
              BoardRoad.deck(stood), stood.containsTerrain(Terrains.BRIDGE_ROAD) ? stood.getTerrain(Terrains.BRIDGE_ROAD).getExits() & 63 : 0,
              hex.deckMud(hex.terrainLevel(Terrains.BRIDGE_ELEV)) != null, RailRoute.groundSides(hex),
              RailRoute.corridor(hex, pixels.coords(), ways), RailRoute.corridor(stood, pixels.coords(), ways, true),
              BoardBackdrop.capture(hex), MinimapTerrain.capture(hex));
    }

    /**
     * The hex as its fallen bridge stood ({@link megamek.common.Hex#restoreRuins}), for the presentation of what its
     * span leaves: a copy; the hex itself where no bridge fell or one stands again, null for none.
     */
    static megamek.common.Hex bridgeAsItStood(megamek.common.Hex hex) {
        if (hex == null || hex.containsTerrain(Terrains.BRIDGE) || hex.getRuin(Terrains.BRIDGE) == null) { return hex; }
        var copy = hex.duplicate();
        copy.restoreRuins();
        return copy;
    }

    /**
     * The terrain the Tactical View's ground layer marks on a hex, captured with it: the woods or jungle density
     * ({@code woods} 0 for none, 1 light, 2 heavy, 3 ultra) and a structure: its {@code structure} kind (a building's
     * type 1-5, {@link #FUEL_TANK} or {@link #INDUSTRIAL}, 0 for none), the hexsides it continues across
     * ({@code exits}, MegaMek directions), its current construction factor (the hex tooltip's rule: the board's
     * building, else the hex's own CF; 0 for industry), a wall's weight, which decides its look ({@code wallWeight},
     * {@link megamek.common.board.TilesetScenery#buildingWeight}; 0 for no wall), and height in levels.
     */
    record Cover(int woods, boolean jungle, int structure, int exits, int cf, int wallWeight, int height,
          megamek.common.board.BuildingInteriors interiors, megamek.common.board.BuildingVolumes.Reference reference) {
        Cover {
            if (!GpuTerrain.VISIBLE_BUILDING_INTERIORS) { interiors = megamek.common.board.BuildingInteriors.NO; }
        }

        Cover(int woods, boolean jungle, int structure, int exits, int cf, int wallWeight, int height) {
            this(woods, jungle, structure, exits, cf, wallWeight, height, megamek.common.board.BuildingInteriors.YES, null);
        }
        static final int FUEL_TANK = 6;
        static final int INDUSTRIAL = 7;
        static final Cover NONE = new Cover(0, false, 0, 0, 0, 0, 0);

        static Cover capture(megamek.common.Hex hex, megamek.common.units.IBuilding building, Coords coords) {
            boolean jungle = hex.containsTerrain(Terrains.JUNGLE);
            int woods = Math.max(0, jungle ? hex.terrainLevel(Terrains.JUNGLE) : hex.terrainLevel(Terrains.WOODS));
            if (hex.containsTerrain(Terrains.BUILDING)) {
                return new Cover(woods, jungle, hex.terrainLevel(Terrains.BUILDING),
                      hex.getTerrain(Terrains.BUILDING).getExits() & 63,
                      building == null ? hex.terrainLevel(Terrains.BLDG_CF) : building.getCurrentCF(coords),
                      hex.terrainLevel(Terrains.BUILDING) == megamek.common.enums.BuildingType.WALL.getTypeValue()
                            ? megamek.common.board.TilesetScenery.buildingWeight(hex) : 0,
                      hex.terrainLevel(Terrains.BLDG_ELEV), megamek.common.board.BuildingInteriors.resolve(hex), hex.getBuildingReference());
            }
            if (hex.containsTerrain(Terrains.FUEL_TANK)) {
                return new Cover(woods, jungle, FUEL_TANK, hex.getTerrain(Terrains.FUEL_TANK).getExits() & 63,
                      building == null ? hex.terrainLevel(Terrains.FUEL_TANK_CF) : building.getCurrentCF(coords),
                      hex.terrainLevel(Terrains.FUEL_TANK_CF),
                      hex.terrainLevel(Terrains.FUEL_TANK_ELEV));
            }
            if (hex.containsTerrain(Terrains.INDUSTRIAL)) {
                return new Cover(woods, jungle, INDUSTRIAL, 0, 0, 0, hex.terrainLevel(Terrains.INDUSTRIAL));
            }
            return woods == 0 && hex.getBuildingReference() == null ? NONE
                  : new Cover(woods, jungle, 0, 0, 0, 0, 0, megamek.common.board.BuildingInteriors.YES, hex.getBuildingReference());
        }
    }

    /** Pit rims use the existing authored-cliff path. The source hex and its gameplay exits remain untouched. */
    private static int cliffTopExits(megamek.common.Hex hex, Coords coords,
          java.util.function.Function<Coords, megamek.common.Hex> board) {
        int exits = hex.containsTerrain(Terrains.CLIFF_TOP) && hex.getTerrain(Terrains.CLIFF_TOP).hasExitsSpecified()
              ? hex.getTerrain(Terrains.CLIFF_TOP).getExits() & 63 : 0;
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = board.apply(coords.translated(direction));
            if (neighbor != null && neighbor.containsTerrain(Terrains.ULTRA_SUBLEVEL)) { exits |= 1 << direction; }
        }
        return exits;
    }

    /** World-space shadow travel per elevation level; null means directional shadows are disabled. */
    public record Light(float x, float y) { }

    public BoardScene {
        if (width < 1 || height < 1 || tiles.size() != width * height) {
            throw new IllegalArgumentException("A board snapshot must contain every hex in column order");
        }
        tiles = List.copyOf(tiles);
        units = List.copyOf(units);
        plannedPath = List.copyOf(plannedPath);
        commands = List.copyOf(commands);
        firingLines = List.copyOf(firingLines);
        rangeBorders = List.copyOf(rangeBorders);
        rangeLabels = List.copyOf(rangeLabels);
        markers = List.copyOf(markers);
    }

    /**
     * Absolute endpoint levels and displayed attack modes, copied from the existing visible attack sprites, with the
     * attacker and the target the attack names.
     */
    record FiringLine(Waypoint source, Waypoint target, int rgb, boolean indirect, int attackerId,
          TargetKey targetKey) { }

    /**
     * A TAG that hit, still marking its target for semi-guided and homing rounds: a laser designator line from the
     * unit that fired it to the unit it marked.
     */
    record Designation(int designatorId, Waypoint source, int targetId, Waypoint target) { }

    /** The weapon handler already determines these edges, brackets and colours. No range rules live in the renderer. */
    record RangeBorder(Coords coords, int edges, int rgb, String label) { }

    /** Flat lettering at positions chosen by the weapon handler, independently oriented toward the camera. */
    record RangeLabel(Coords coords, int rgb, String label) { }

    public Tile tile(Coords coords) { return tile(coords.getX(), coords.getY()); }

    Tile tile(int x, int y) {
        return x < 0 || y < 0 || x >= width || y >= height ? null : tiles.get(x * height + y);
    }

    /** The tile beside another in a hex direction, without constructing its coordinates. */
    Tile neighbor(Tile tile, int direction) {
        int x = tile.coords().getX(), y = tile.coords().getY();
        return tile(Coords.xInDir(x, y, direction), Coords.yInDir(x, y, direction));
    }

    /** Render materials. A tile's surface supplies its geology; groundCover can put shared SAND over any theme. */
    enum Surface {
        GRASS("terrain/classic/rock"),
        DIRT("terrain/classic/dirt"),
        SAND("terrain/classic/sand"),
        ROCK("terrain/classic/rock"),
        CONCRETE("terrain/classic/concrete"),
        SNOW("terrain/classic/snow"),
        LUNAR("terrain/classic/lunar"),
        FUNGUS("terrain/classic/fungus"),
        DESERT("terrain/classic/sand"),
        MARS("terrain/classic/mars"),
        VOLCANO("terrain/classic/volcano"),
        TROPICAL("terrain/classic/rock");

        /** The Classic column wall artwork; Enhanced uses the physical family materials. */
        final String classicWall;

        Surface(String classicWall) {
            this.classicWall = classicWall;
        }
    }

    /**
     * {@code FUEL_TANK}: a fuel tank, a structure without the floors, interior and cutaways of a {@code BUILDING}.
     * {@code SHAFT}: a Solaris elevator's shaft, which no model draws: the terrain builds its hex's walls as steel.
     */
    enum FeatureKind { PROP, BUILDING, INDUSTRIAL, FUEL_TANK, TREE, LIMB, SCATTER, BOULDER, ROUGH, SCENERY, METAL, SHAFT }

    /** Captured visual ground treatment; movement and cover modifiers remain in the game terrain. */
    enum Biome { NONE, FIELD, MARSH, QUICKSAND, MUD, TUNDRA }

    /**
     * Authored model or scatter shape, placement in tile pixels, and height/root lift in elevation levels.
     * {@code stretch} multiplies {@code scale} per model axis before the rotation, and {@code colours} replace the
     * model's colour slots: a placed object's own, or a legacy layout row's. {@code parts} are the numbered parts of its
     * model it shows (bit N for part N, {@link RigidGlb#part}), as the fighting holes of a dug-in platoon's figures;
     * 0 shows them all. {@code ruined}: a bridge whose span fell (megamek.common.Hex#getRuins): its landings and piers
     * still stand and the roads still end at them, but it has no deck and is no structure.
     */
    record Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
          FeatureKind kind, int bridgeExits, boolean authoredPlacement, megamek.common.board.BoardDecoration decoration,
          megamek.common.board.BoardDecoration.Stretch stretch, megamek.common.board.BoardDecoration.Colours colours,
          int parts, boolean ruined) {
        // Generated kits fit their outline before laying full-size facade pieces and roof fittings.
        float footprintScale() { return megamek.common.board.TilesetScenery.generatedKit(asset) ? scale : 1; }
        float modelScale() { return megamek.common.board.TilesetScenery.generatedKit(asset) ? 1 : scale; }
        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind, int bridgeExits, boolean authoredPlacement, megamek.common.board.BoardDecoration decoration,
              megamek.common.board.BoardDecoration.Stretch stretch, megamek.common.board.BoardDecoration.Colours colours,
              int parts) {
            this(asset, x, y, rotation, scale, height, elevation, kind, bridgeExits, authoredPlacement, decoration, stretch,
                  colours, parts, false);
        }
        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind, int bridgeExits, boolean authoredPlacement, megamek.common.board.BoardDecoration decoration,
              megamek.common.board.BoardDecoration.Stretch stretch, megamek.common.board.BoardDecoration.Colours colours) {
            this(asset, x, y, rotation, scale, height, elevation, kind, bridgeExits, authoredPlacement, decoration, stretch,
                  colours, 0);
        }
        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind, int bridgeExits, boolean authoredPlacement, megamek.common.board.BoardDecoration decoration) {
            this(asset, x, y, rotation, scale, height, elevation, kind, bridgeExits, authoredPlacement, decoration,
                  decoration == null ? megamek.common.board.BoardDecoration.Stretch.NONE : decoration.stretch(),
                  decoration == null ? megamek.common.board.BoardDecoration.Colours.NONE : decoration.colours());
        }
        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind, int bridgeExits, boolean authoredPlacement) {
            this(asset, x, y, rotation, scale, height, elevation, kind, bridgeExits, authoredPlacement, null);
        }
        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind, int bridgeExits) {
            this(asset, x, y, rotation, scale, height, elevation, kind, bridgeExits, false);
        }

        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation,
              FeatureKind kind) {
            this(asset, x, y, rotation, scale, height, elevation, kind, 0);
        }

        Feature(String asset, float x, float y, float rotation, float scale, float height, float elevation) {
            this(asset, x, y, rotation, scale, height, elevation, FeatureKind.PROP);
        }
    }

    /**
     * Water depth -1 means dry. Decals are independent from solid feature geometry. A barren tile is the
     * airless ground of {@link #lunar}, or scorched ground: nothing grows on it, so its field cover keeps only stones. A bare tile is its
     * zero-gravity rock: nothing lies loose or grows on it, so neither scatter nor field cover dresses it. The tileset
     * art is the hex's Classic art, which only the Classic graphics draw. {@code deckRoad} and {@code deckRoadExits}:
     * the road its bridge deck carries ({@link Terrains#BRIDGE_ROAD}), NONE and 0 on a clean deck. {@code deckMud}: its
     * raised deck is muddy ({@link megamek.common.Hex#deckMud}); a natural deck shows it.
     * {@code railBed}: the sides of its ground rails, a destroyed rail's too ({@link megamek.common.board.RailRoute#groundSides}).
     * {@code minimap}: the terrain-only snapshot captured with this tile, shared with thumbnails. Cosmetic labels
     * and feature overlays retain it; a recaptured hex replaces it with the rest of its terrain.
     */
    record Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
          Pixels decals, Pixels decalsWithoutLimbs,
          List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
          boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
          boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
          boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
          java.util.Map<String, megamek.common.board.HexAppearance> appearance, Cover cover, boolean barren,
          BoardRoad.Kind deckRoad, int deckRoadExits, boolean deckMud, int railBed,
          RailRoute.Corridor corridor, RailRoute.Corridor deckCorridor, BoardBackdrop backdrop, MinimapTerrain minimap) {
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
              java.util.Map<String, megamek.common.board.HexAppearance> appearance, Cover cover, boolean barren,
              BoardRoad.Kind deckRoad, int deckRoadExits, boolean deckMud, int railBed,
              RailRoute.Corridor corridor, RailRoute.Corridor deckCorridor, BoardBackdrop backdrop) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features,
                  text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce, cliffTopExits,
                  bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance, cover, barren,
                  deckRoad, deckRoadExits, deckMud, railBed, corridor, deckCorridor, backdrop, null);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
              java.util.Map<String, megamek.common.board.HexAppearance> appearance, Cover cover, boolean barren,
              BoardRoad.Kind deckRoad, int deckRoadExits, boolean deckMud, int railBed,
              RailRoute.Corridor corridor, RailRoute.Corridor deckCorridor) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, barren, deckRoad, deckRoadExits, deckMud, railBed, corridor, deckCorridor, BoardBackdrop.NONE);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
              java.util.Map<String, megamek.common.board.HexAppearance> appearance, Cover cover, boolean barren,
              BoardRoad.Kind deckRoad, int deckRoadExits, boolean deckMud, int railBed) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, barren, deckRoad, deckRoadExits, deckMud, railBed, RailRoute.Corridor.NONE, RailRoute.Corridor.NONE);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
              java.util.Map<String, megamek.common.board.HexAppearance> appearance, Cover cover, boolean barren) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, barren, BoardRoad.Kind.NONE, 0, false, 0);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
              java.util.Map<String, megamek.common.board.HexAppearance> appearance, Cover cover) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, false);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery,
              java.util.Map<String, megamek.common.board.HexAppearance> appearance) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  Cover.NONE);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
          Pixels decals, Pixels decalsWithoutLimbs,
          List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
          boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
          boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
          boolean ultraSublevel, Pixels tilesetDecals, Pixels tilesetScenery) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce, cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, java.util.Map.of());
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover,
              boolean ultraSublevel) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, ultraSublevel, null, null);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare, BoardSurfaceBlend.Cover groundCover) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, groundCover, false);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits, boolean bare) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, bare, BoardSurfaceBlend.solid(surface == null ? Surface.GRASS : surface));
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce, int cliffTopExits) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce,
                  cliffTopExits, false);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable,
              boolean blackIce) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, blackIce, 0);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome, boolean impassable) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, impassable, false);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke, Biome biome) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, biome, false);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road, BoardFireSmoke fireSmoke) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, fireSmoke, Biome.NONE);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround, BoardRoad.Kind road) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, road, BoardFireSmoke.NONE);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset,
              boolean detailedGround) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, detailedGround, BoardRoad.Kind.NONE);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid, Pixels tileset) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, tileset, false);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text, BoardLiquid liquid) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, liquid, null);
        }
        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, Pixels decalsWithoutLimbs,
              List<Feature> features, List<BoardHexText> text) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, decalsWithoutLimbs, features, text, waterDepth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE);
        }

        Tile(Coords coords, int elevation, int waterDepth, boolean frozen, int roadExits, Surface surface,
              Pixels decals, List<Feature> features, List<BoardHexText> text) {
            this(coords, elevation, waterDepth, frozen, roadExits, surface, decals, null, features, text);
        }

        /** This tile with its labels replaced; every captured terrain field is kept. */
        Tile withText(List<BoardHexText> labels) {
            if (Objects.equals(labels, text)) { return this; }
            return new Tile(coords, elevation, waterDepth, frozen, roadExits, surface, decals,
                  decalsWithoutLimbs, features, labels, liquid, tileset, detailedGround, road, fireSmoke, biome,
                  impassable, blackIce, cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, barren, deckRoad, deckRoadExits, deckMud, railBed, corridor, deckCorridor, backdrop, minimap);
        }

        /** This tile with other features: the editor preview's tile of a route stroke's next step. */
        Tile withFeatures(List<Feature> shown) {
            return new Tile(coords, elevation, waterDepth, frozen, roadExits, surface, decals,
                  decalsWithoutLimbs, List.copyOf(shown), text, liquid, tileset, detailedGround, road, fireSmoke, biome,
                  impassable, blackIce, cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, barren, deckRoad, deckRoadExits, deckMud, railBed, corridor, deckCorridor, backdrop, minimap);
        }

        /** Burned presentation only: remove decorative vegetation and prevent field shrubs replacing it. */
        Tile withoutVegetation() {
            List<Feature> kept = features.stream().filter(feature -> !GpuScatter.vegetation(feature)).toList();
            if (barren && kept.size() == features.size()) { return this; }
            return new Tile(coords, elevation, waterDepth, frozen, roadExits, surface, decals,
                  decalsWithoutLimbs, kept, text, liquid, tileset, detailedGround, road, fireSmoke, biome,
                  impassable, blackIce, cliffTopExits, bare, groundCover, ultraSublevel, tilesetDecals, tilesetScenery, appearance,
                  cover, true, deckRoad, deckRoadExits, deckMud, railBed, corridor, deckCorridor, backdrop, minimap);
        }

        /**
         * Airless presentation only (vacuum, and so zero gravity), without editing the source hex: nothing grows, not
         * even field shrubs ({@code barren}), and the surface is lunar except for concrete. In vacuum the water and
         * hazardous liquids freeze, as ICE does, and magma stays. Without gravity ({@code weightless}) the liquids are
         * gone, their beds exposed, and nothing lies loose either: scatter and field cover go and rough boulders become
         * bedrock outcrops.
         */
        Tile lunar(boolean weightless) {
            int depth = weightless ? Math.max(0, waterDepth) : 0;
            int level = elevation - depth;
            Feature bridge = BoardBridge.feature(this);
            List<Feature> kept = new ArrayList<>();
            int boulders = 0;
            for (Feature feature : features) {
                // Keep the deck at its original absolute level when the ground beneath it drops.
                if (feature == bridge && depth > 0) {
                    feature = new Feature(feature.asset(), feature.x(), feature.y(), feature.rotation(), feature.scale(),
                          feature.height(), feature.elevation() + depth, feature.kind(), feature.bridgeExits(), false, null,
                          megamek.common.board.BoardDecoration.Stretch.NONE, megamek.common.board.BoardDecoration.Colours.NONE,
                          0, feature.ruined());
                }
                switch (feature.kind()) {
                    case TREE -> { if (feature.authoredPlacement()) { kept.add(feature); } }
                    // Gravity still holds loose stones; grass, plants and fungi do not grow.
                    case SCATTER -> { if (!weightless && BoardScatter.stone(feature.asset())) { kept.add(feature); } }
                    // Felled timber goes with the other vegetation; Dragon's Teeth are concrete.
                    case ROUGH -> { if (feature.asset().equals("rough/dragon-tooth")) { kept.add(feature); } }
                    // Nothing lies loose without gravity. Rough is bedrock breaking the surface: every third boulder
                    // becomes an outcrop three times its size, its height a bound. A repeated presentation keeps them.
                    case BOULDER -> {
                        if (!weightless || feature.asset().equals(BoardRocks.OUTCROP)) {
                            kept.add(feature);
                        } else if (boulders++ % 3 == 0) {
                            kept.add(new Feature(BoardRocks.OUTCROP, feature.x(), feature.y(), feature.rotation(),
                                  feature.scale() * 3, feature.height() * 1.5f, feature.elevation(), feature.kind()));
                        }
                    }
                    default -> kept.add(feature);
                }
            }
            String sourceLevel = BoardHexText.level(elevation);
            String depthPrefix = Messages.getString("BoardView1.DEPTH");
            String heightPrefix = Messages.getString("BoardView1.HEIGHT") + " ";
            int bridgeHeight = bridge == null ? 0 : Math.round(bridge.elevation()) + depth;
            String sourceBridge = bridge == null ? null : BoardHexText.bridge(Math.round(bridge.elevation()));
            // The vegetation, the snow, the black ice and the swamp are gone with the old surface, so are their labels
            // (FOLIAGE, the cover, FIELDS, TUNDRA, SNOW, BLACK ICE, SWAMP). Without gravity the liquids and their ice
            // go too (ICE, MAGMA, RAPIDS, HAZ. LIQUID and their levels); in vacuum they stay, the water frozen.
            Stream<String> keys = Stream.of("LowFoliage", "hexType.cover1", "hexType.cover2", "hexType.cover3",
                  "hexType.planted_fields", "hexType.tundra", "hexType.snow", "hexType.snow2", "hexType.black_ice",
                  "hexType.swamp", "hexType.swamp2");
            if (weightless) {
                keys = Stream.concat(keys, Stream.of("hexType.ice", "hexType.magma", "hexType.magma2",
                      "hexType.rapids", "hexType.rapids2", "hexType.hazardous_liquid"));
            }
            Set<String> gone = Set.copyOf(keys.map(key -> Messages.getString("BoardView1." + key)).toList());
            List<BoardHexText> labels = new ArrayList<>();
            boolean groundLabel = false;
            for (BoardHexText label : text) {
                if (gone.contains(label.text())) { continue; }
                if (!weightless) {
                    labels.add(label);
                } else if (label.text().equals(sourceLevel) || label.text().startsWith(depthPrefix)) {
                    if (!groundLabel && level != 0) {
                        labels.add(new BoardHexText(BoardHexText.level(level), label.baseline(), label.font(),
                              label.argb(), label.fromTop(), 0));
                    }
                    groundLabel = true;
                } else if (bridgeHeight > label.elevation() && label.text().startsWith(heightPrefix)) {
                    labels.add(new BoardHexText(heightPrefix + bridgeHeight, label.baseline(), label.font(), label.argb(),
                          label.fromTop(), bridgeHeight));
                } else if (label.text().equals(sourceBridge)) {
                    // The type block's bridge line, on the deck where it is the roof label, keeps the deck's level too.
                    labels.add(new BoardHexText(BoardHexText.bridge(bridgeHeight), label.baseline(), label.font(),
                          label.argb(), label.fromTop(), label.elevation() > 0 ? bridgeHeight : 0));
                } else {
                    labels.add(label);
                }
            }
            boolean iced = !weightless && (frozen || liquid.present() && !liquid.molten());
            Surface airlessSurface = surface == Surface.CONCRETE ? Surface.CONCRETE : Surface.LUNAR;
            return new Tile(coords, level, weightless ? -1 : waterDepth, iced, roadExits, airlessSurface,
                  null, null, kept, BoardHexText.settled(labels, text),
                  weightless ? BoardLiquid.NONE : liquid, null, true, road, fireSmoke, Biome.NONE, impassable, false,
                  cliffTopExits, weightless, BoardSurfaceBlend.solid(airlessSurface), ultraSublevel, null, null,
                  appearance, cover, true, deckRoad, deckRoadExits, false, railBed, corridor, deckCorridor, backdrop,
                  minimap.withSurface(level, weightless ? -1 : waterDepth, iced, airlessSurface,
                        weightless ? BoardLiquid.Kind.NONE : liquid.kind(), Biome.NONE));
        }

        Tile {
            features = List.copyOf(features);
            text = List.copyOf(text);
            appearance = java.util.Map.copyOf(appearance);
            cover = Objects.requireNonNullElse(cover, Cover.NONE);
            deckRoad = Objects.requireNonNullElse(deckRoad, BoardRoad.Kind.NONE);
            backdrop = Objects.requireNonNullElse(backdrop, BoardBackdrop.NONE);
            if (minimap == null) {
                minimap = MinimapTerrain.captured(elevation, waterDepth, frozen, surface, liquid, cover, biome, road,
                      features);
            }
        }

        boolean water() {
            return waterDepth >= 0;
        }

        /** Whether a captured structure, a wall included, stands here. */
        boolean building() {
            for (Feature feature : features) {
                if (feature.kind() == FeatureKind.BUILDING) { return true; }
            }
            return false;
        }

        /**
         * Whether a structure whose ground keeps the slab rather than natural cover stands here. A generated wall stands
         * on the natural ground, whose cover grows up to its footprint ({@link BoardObstacles}).
         */
        boolean slab() {
            for (Feature feature : features) {
                if (feature.kind() == FeatureKind.BUILDING && !BoardWall.supports(feature.asset())) { return true; }
            }
            return false;
        }

        /** Visual cap below an ultra-sublevel's mouth, including pits beside ground at the same authored level. */
        int groundLevel() { return elevation - (ultraSublevel ? 1 : 0); }

        /** The level of the ground under this hex's liquid (Hex#floor): roads and bridges meet it, water being a layer over it. */
        int floor() { return elevation - (liquid.present() ? Math.max(0, waterDepth) : 0); }

        /**
         * Whether a side the author separated parts this hex's water from {@code other}'s, the neighbour in
         * {@code direction}. It holds between water at one level; across a step the water still falls.
         */
        boolean separated(Tile other, int direction) {
            return elevation == other.elevation
                  && ((liquid.separated() >> direction | other.liquid.separated() >> (direction + 3) % 6) & 1) != 0;
        }

        /** Whether this hex's liquid runs on into {@code other}, the neighbour in {@code direction}. */
        boolean joins(Tile other, int direction) {
            return liquid.connects(other.liquid) && !separated(other, direction);
        }

        /** {@link #joins(Tile, int)} for a neighbour known by its coordinates. */
        boolean joins(Tile other) {
            return liquid.connects(other.liquid) && ((liquid.separated() | other.liquid.separated()) == 0
                  || !separated(other, coords.direction(other.coords)));
        }

        /** The sides the author parted from the pavement beside this hex's pavement. */
        int pavementSides() {
            return surface == Surface.CONCRETE ? megamek.common.board.HexAppearance.separated(appearance, "pavement") : 0;
        }

        /** Whether a side the author separated parts this hex's pavement from {@code other}'s, at one level. */
        boolean pavementApart(Tile other, int direction) {
            return surface == Surface.CONCRETE && other.surface == Surface.CONCRETE && elevation == other.elevation
                  && ((pavementSides() >> direction | other.pavementSides() >> (direction + 3) % 6) & 1) != 0;
        }

        /**
         * The sides of the tile's rails that lie on its ground: its captured {@link #railBed} (a destroyed rail's too,
         * which draws no track) and its ground route features (a hand-made tile's, the editor preview's stroke).
         */
        int railExits() {
            int result = railBed;
            for (Feature feature : features) {
                var object = feature.decoration();
                if (megamek.common.board.RailRoute.kind(object) == megamek.common.board.RailRoute.Kind.RAIL
                      && !megamek.common.board.RailRoute.onDeck(object)) { result |= object.connections(); }
            }
            return result;
        }

        /**
         * Every carriageway the ground is shaped and cleared for: the road's exits and the ground rails' sides. A rail
         * shapes its hex as a road with those exits does (ramps, cuts, deck approaches); only road paint reads
         * {@link #roadExits} alone.
         */
        int wayExits() { return roadExits | railExits(); }

        /** Inputs shared by terrain meshes, support surfaces and draped tactical geometry. */
        boolean sameGeometry(Tile other) {
            return this == other || coords.equals(other.coords) && elevation == other.elevation
                  && waterDepth == other.waterDepth && frozen == other.frozen && roadExits == other.roadExits
                  && cliffTopExits == other.cliffTopExits && bare == other.bare && barren == other.barren
                  && ultraSublevel == other.ultraSublevel && backdrop == other.backdrop && deckRoad == other.deckRoad
                  && deckRoadExits == other.deckRoadExits && deckMud == other.deckMud && railBed == other.railBed
                  && corridor.equals(other.corridor) && deckCorridor.equals(other.deckCorridor)
                  && surface == other.surface && detailedGround == other.detailedGround && road == other.road && biome == other.biome
                  && liquid.equals(other.liquid) && features.equals(other.features) && groundCover.equals(other.groundCover)
                  && cover.exits() == other.cover.exits()
                  && cover.interiors() == other.cover.interiors() && java.util.Objects.equals(cover.reference(), other.cover.reference())
                  && BoardWall.family(this) == BoardWall.family(other)
                  && appearance.equals(other.appearance);
        }
    }

    /**
     * Stand/flight elevation, occupied levels and heat come from the game. Heat -1 means this unit does not track
     * heat or is only a sensor contact; zero is a valid captured heat for an identified unit.
     */
    public record Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
          Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb, List<Coords> footprint,
          Attachment attachment, int heat) {
        public Unit {
            footprint = List.copyOf(footprint);
        }

        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb, List<Coords> footprint,
              Attachment attachment) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  footprint, attachment, -1);
        }

        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb, List<Coords> footprint) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb, footprint, null);
        }

        Unit withAttachment(Attachment value) {
            return new Unit(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  footprint, value, heat);
        }

        Unit at(Waypoint point) {
            return new Unit(id, part, name, point, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  point.footprint().isEmpty() ? List.of(point.coords()) : point.footprint(), attachment, heat);
        }

        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne, UnitModel model, int outlineRgb) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, model, outlineRgb,
                  List.of(location.coords()));
        }
        Unit(int id, int part, String name, Waypoint location, Pixels image, boolean sensorContact,
              Pixels annotations, int height, boolean airborne) {
            this(id, part, name, location, image, sensorContact, annotations, height, airborne, null, 0xFFC0C0C0);
        }
    }

    /** Exterior occupancy observed on Swing. Internal bay passengers never acquire an attachment. */
    record Attachment(int carrierId, boolean hostile) { }

    enum Release { BOARD, CLIMB_DOWN, THROWN, JUMP, WATER }

    /** Both endpoints are authorized snapshots, including the destination and surviving figures after release. */
    record AttachmentChange(int boardId, Unit before, Unit after, Unit carrier, Release release, Waypoint destination) implements Animation {
        AttachmentChange(int boardId, Unit before, Unit after, Unit carrier, Release release) {
            this(boardId, before, after, carrier, release, after == null ? carrier.location() : after.location());
        }
        @Override
        public int entityId() { return before.id(); }
    }

    /**
     * Derived on Swing after visibility filtering; the render thread never reads an Entity.
     *
     * @param asset    the descriptor chosen for the unit, relative to the model directory
     * @param fallback the generic descriptor used when {@code asset} cannot be loaded, or {@code null}
     * @param variant  the key of the unit's loadout or formation inside the descriptor
     * @param figures  the number of figures a formation shows
     * @param twist    hexsides the displayed facing (a Mek's torso) is turned clockwise from the unit's own facing
     *                 (its legs), from {@code -2} to {@code 3}; {@code 0} when the two agree
     * @param damage   the locations to show as lost or destroyed
     * @param state    immutable live components and posture, or {@code null} for legacy review fixtures
     */
    record UnitModel(String asset, String fallback, String variant, int figures, int twist, LocationDamage damage,
          UnitModelState state) {
        UnitModel(String asset, String fallback, String variant, int figures, int twist, LocationDamage damage) {
            this(asset, fallback, variant, figures, twist, damage, null);
        }

        UnitModel(String asset, String fallback, String variant, int figures) {
            this(asset, fallback, variant, figures, 0, LocationDamage.NONE);
        }
    }

    /**
     * The locations of a unit that a model shows as lost, each by the game's own abbreviation ({@code LA},
     * {@code RT}, ...), which is also the name of that location's part in the model.
     *
     * @param removed locations shown as gone altogether: a lost arm
     * @param wrecked locations shown still in place but burnt out: a destroyed leg, side torso or head, which the
     *                rest of the model stands on or hangs from
     */
    record LocationDamage(Set<String> removed, Set<String> wrecked, Map<String, UnitDamageDisplay.Stage> stages) {
        static final LocationDamage NONE = new LocationDamage(Set.of(), Set.of());

        LocationDamage(Set<String> removed, Set<String> wrecked) { this(removed, wrecked, Map.of()); }

        LocationDamage {
            removed = Set.copyOf(removed);
            wrecked = Set.copyOf(wrecked);
            stages = Map.copyOf(stages);
        }

        boolean isNone() {
            return removed.isEmpty() && wrecked.isEmpty() && stages.isEmpty();
        }
    }

    /** Observed aerospace state, kept separate from the absolute height used for drawing. */
    public enum AeroState { LANDED, ELEVATED, AIRBORNE }

    /** Elevation is an absolute render level, already including the hex level for airborne units. */
    public record Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
          AeroState aeroState, List<Coords> footprint, megamek.common.units.UnitLocation.Form form,
          megamek.common.units.FallSide fallSide, Boolean hullDown) {
        public Waypoint {
            footprint = List.copyOf(footprint);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState, List<Coords> footprint, megamek.common.units.UnitLocation.Form form,
              megamek.common.units.FallSide fallSide) {
            this(coords, elevation, facing, proneCause, aeroState, footprint, form, fallSide, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState, List<Coords> footprint, megamek.common.units.UnitLocation.Form form) {
            this(coords, elevation, facing, proneCause, aeroState, footprint, form, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState, List<Coords> footprint) {
            this(coords, elevation, facing, proneCause, aeroState, footprint, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause,
              AeroState aeroState) {
            this(coords, elevation, facing, proneCause, aeroState, List.of());
        }
        public Waypoint(Coords coords, float elevation, float facing) {
            this(coords, elevation, facing, null, null);
        }

        public Waypoint(Coords coords, float elevation, float facing, megamek.common.units.ProneCause proneCause) {
            this(coords, elevation, facing, proneCause, null);
        }

        Waypoint withProneCause(megamek.common.units.ProneCause cause) {
            return new Waypoint(coords, elevation, facing, cause, aeroState, footprint, form, fallSide, hullDown);
        }

        Waypoint withAeroState(AeroState state) {
            return new Waypoint(coords, elevation, facing, proneCause, state, footprint, form, fallSide, hullDown);
        }

        Waypoint withFootprint(List<Coords> occupied) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, occupied, form, fallSide, hullDown);
        }

        Waypoint withForm(megamek.common.units.UnitLocation.Form value) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, footprint, value, fallSide, hullDown);
        }

        Waypoint withFallSide(megamek.common.units.FallSide side) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, footprint, form, side, hullDown);
        }

        Waypoint withHullDown(Boolean value) {
            return new Waypoint(coords, elevation, facing, proneCause, aeroState, footprint, form, fallSide, value);
        }

        /** Captured fitting metadata does not create another movement step at a queued path boundary. */
        boolean samePose(Waypoint other) {
            return facing == other.facing && samePoseExceptFacing(other);
        }

        boolean samePoseExceptFacing(Waypoint other) {
            return coords.equals(other.coords) && elevation == other.elevation
                  && proneCause == other.proneCause && fallSide == other.fallSide && aeroState == other.aeroState
                  && java.util.Objects.equals(form, other.form) && java.util.Objects.equals(hullDown, other.hullDown);
        }
    }

    BoardScene withTiles(List<Tile> shown) {
        if (shown == tiles) { return this; }
        return new BoardScene(boardId, width, height, shown, units, plannedPath, selectedId, phase, commands, light,
              firingLines, rangeBorders, markers, tactical, rangeLabels, fieldOfView, electronicWarfare, designations,
              starShells);
    }

    BoardScene withUnits(List<Unit> shown) {
        return new BoardScene(boardId, width, height, tiles, shown, plannedPath, selectedId, phase, commands, light,
              firingLines, rangeBorders, markers, tactical, rangeLabels, fieldOfView, electronicWarfare, designations,
              starShells);
    }

    /** Keep the displayed selection without exposing the next turn's commands, route or firing guides. */
    BoardScene withPlaybackSelection(int id) {
        return new BoardScene(boardId, width, height, tiles, units, List.of(), id, phase,
              List.of(), light, List.of(), List.of(), markers, tactical, List.of(), fieldOfView,
              electronicWarfare, designations, starShells);
    }

    /** Board artwork, terrain and authorized contacts advance with the queue; interactive tools stay live. */
    BoardScene duringPlayback(BoardScene settled, boolean hideMovement) {
        if (settled == this && !hideMovement) {
            return this;
        }
        BoardScene world = settled == null ? this : settled;
        var heldMarkers = settled == null ? Stream.<BoardMarker>empty() : settled.markers.stream();
        var shownMarkers = Stream.concat(heldMarkers.filter(marker ->
                    (!hideMovement || marker.kind() != BoardMarker.Kind.COLLAPSE_WARNING)
                          && marker.kind() != BoardMarker.Kind.PLAYER_NOTE),
              markers.stream().filter(marker -> marker.kind() == BoardMarker.Kind.PLAYER_NOTE)).toList();
        return new BoardScene(boardId, width, height, world.tiles, world.units,
              hideMovement ? List.of() : world.plannedPath, selectedId, phase, commands, light,
              hideMovement ? List.of() : world.firingLines, hideMovement ? List.of() : world.rangeBorders, shownMarkers,
              tactical.duringPlayback(settled == null ? BoardTactical.EMPTY : settled.tactical, hideMovement),
              hideMovement ? List.of() : world.rangeLabels, hideMovement ? BoardFieldOfView.EMPTY : world.fieldOfView,
              world.electronicWarfare, world.designations, world.starShells);
    }

    /** Excludes HUD commands and other live controls, which do not need a playback checkpoint. */
    boolean samePlaybackState(BoardScene other) {
        return other != null && boardId == other.boardId && width == other.width && height == other.height
              && tiles.equals(other.tiles) && units.equals(other.units) && markers.equals(other.markers)
              && tactical.equals(other.tactical) && plannedPath.equals(other.plannedPath)
              && firingLines.equals(other.firingLines) && rangeBorders.equals(other.rangeBorders)
              && rangeLabels.equals(other.rangeLabels) && fieldOfView.equals(other.fieldOfView)
              && electronicWarfare.equals(other.electronicWarfare) && designations.equals(other.designations)
              && starShells.equals(other.starShells);
    }

    /**
     * The action marshals back to Swing and rechecks the original button before invoking it. {@code shortcut} is the
     * menu item's accelerator text ("" without one); {@code selected} is a toggle item's state, null for other items.
     * {@code unitAction} identifies a shared unit menu item without interpreting its localized label.
     */
    public record Command(String id, String label, String detail, boolean enabled, boolean commit, boolean boardTool,
          List<Command> children, Runnable action, String shortcut, Boolean selected, MapMenu.UnitAction unitAction) {
        public Command {
            children = List.copyOf(children);
        }

        public Command(String id, String label, String detail, boolean enabled, boolean commit, boolean boardTool,
              List<Command> children, Runnable action, String shortcut, Boolean selected) {
            this(id, label, detail, enabled, commit, boardTool, children, action, shortcut, selected, null);
        }

        public Command(String id, String label, String detail, boolean enabled, boolean commit, boolean boardTool,
              List<Command> children, Runnable action) {
            this(id, label, detail, enabled, commit, boardTool, children, action, "", null);
        }

        public Command(String label, boolean enabled, Runnable action) {
            this(label, label, "", enabled, false, false, List.of(), action);
        }

        public Command(String id, String label, String detail, boolean enabled, boolean commit,
              List<Command> children, Runnable action) {
            this(id, label, detail, enabled, commit, false, children, action);
        }
    }

    /** The requested hex, unit and selection state, retained to match the asynchronous menu capture. */
    public record Context(Coords coords, int unitId, List<Command> commands, boolean selectionCleared) {
        public Context {
            commands = List.copyOf(commands);
        }

        public Context(Coords coords, int unitId, List<Command> commands) {
            this(coords, unitId, commands, false);
        }

        public Context(Coords coords, List<Command> commands) {
            this(coords, Entity.NONE, commands);
        }
    }

    interface Animation {
        int entityId();
        int boardId();
    }

    /** Visibility loss invalidates historical presentation immediately, including while playback is paused. */
    record Concealed(int entityId, int boardId) implements Animation { }

    /**
     * An immutable, visibility-filtered checkpoint in packet order; it consumes no animation time. {@code broken}: the
     * trees the attack before it took down, which break away from its {@code hit} as the checkpoint shows (GpuTerrain
     * #breakTrees); none for any other.
     */
    record SceneUpdate(BoardScene scene, List<Detonation.Felled> broken, TileTransition.Hit hit) implements Animation {
        SceneUpdate {
            broken = List.copyOf(broken);
        }

        SceneUpdate(BoardScene scene) { this(scene, List.of(), null); }

        @Override
        public int entityId() { return -1; }

        @Override
        public int boardId() { return scene.boardId(); }
    }

    /**
     * Hexes the server changed that move from their before to their after tile on the shared timeline: raised Solaris
     * elevators rising or sinking ({@link GpuTerrain#present}), and structures coming down ({@link #collapses}). One per
     * checkpoint, queued just before it; the checkpoint applies as the change starts, and the change plays for
     * {@link #seconds} of animation time.
     */
    record TileTransition(int boardId, List<Change> changes, List<Collapse> collapses) implements Animation {
        static final double SECONDS = 1.5;
        /** How long a collapse alone holds the playback: its pieces fall on and settle after it (GpuCollapse). */
        static final double COLLAPSE_SECONDS = 1;

        /** An elevator's hex. */
        record Change(Coords coords, Tile before, Tile after) { }

        /**
         * A building, fuel tank or bridge span coming down at {@code coords}, as its tile stood {@code before}: pushed
         * by the hit that brought it down, if it follows that attack's animation, of {@code strength} 0 to 1 coming
         * {@code from} where it was fired (null, 0: it comes straight down under its own weight), with the units that
         * stood on it as {@code loads}.
         */
        record Collapse(Coords coords, Tile before, float strength, Waypoint from, List<Waypoint> loads) {
            Collapse {
                loads = List.copyOf(loads);
            }
        }

        /** The hit that brought a structure down, which its collapse falls away from when it follows that hit. */
        record Hit(float strength, Waypoint from) { }

        /**
         * The hit a detonation deals the structure at {@code at}: whole inside its crater, else as its damage there would
         * (an AC/20's 20 a hit at full strength); null where it pushes nothing.
         */
        static Hit blast(megamek.common.NuclearDetonation strike, Coords at) {
            int distance = strike.coords().distance(at);
            float damage = strike.degradation() < 1 ? 0 : strike.baseDamage() - distance * strike.degradation();
            float strength = distance < 2 * strike.craterDepth() ? 1 : Math.min(1, (damage - 10) / 10);
            return strength > 0 ? new Hit(strength, new Waypoint(strike.coords(), strike.elevation(), 0)) : null;
        }

        TileTransition {
            changes = List.copyOf(changes);
            collapses = List.copyOf(collapses);
        }

        TileTransition(int boardId, List<Change> changes) {
            this(boardId, changes, List.of());
        }

        @Override
        public int entityId() { return -1; }

        /** Playback seconds it holds the queue. */
        double seconds() { return changes.isEmpty() ? COLLAPSE_SECONDS : SECONDS; }

        /** The elevators the server moved between two scenes of one board; null when none moved. */
        static TileTransition of(BoardScene before, BoardScene after) {
            return of(before, after, null);
        }

        /**
         * The elevators the server moved and, unless {@code hits} is null, the structures it brought down between two
         * scenes of one board, each with its hex's hit in {@code hits}; null when nothing moved or fell.
         */
        static TileTransition of(BoardScene before, BoardScene after, java.util.function.Function<Coords, Hit> hits) {
            if (before.tiles() == after.tiles() || before.width() != after.width() || before.height() != after.height()) {
                return null;
            }
            List<Change> changes = new java.util.ArrayList<>();
            List<Collapse> collapses = new java.util.ArrayList<>();
            for (Tile tile : after.tiles()) {
                Tile old = before.tile(tile.coords());
                if (old != null && old.elevation() != tile.elevation() && BoardFeatures.shaft(old) && BoardFeatures.shaft(tile)) {
                    changes.add(new Change(tile.coords(), old, tile));
                } else if (hits != null && old != null && structure(old) && !structure(tile)) {
                    collapses.add(collapse(before, old, hits.apply(tile.coords())));
                }
            }
            return changes.isEmpty() && collapses.isEmpty() ? null : new TileTransition(after.boardId(), changes, collapses);
        }

        /** The structure on {@code old} coming down after {@code hit} (null: its own weight), with who stood on it. */
        static Collapse collapse(BoardScene before, Tile old, Hit hit) {
            // Who stood on it: on its roof, an upper floor or its deck, above the hex's ground.
            List<Waypoint> loads = before.units().stream().filter(unit -> !unit.sensorContact()
                  && unit.location().coords().equals(old.coords()) && unit.location().elevation() > old.elevation())
                  .map(Unit::location).toList();
            return new Collapse(old.coords(), old, hit == null ? 0 : hit.strength(), hit == null ? null : hit.from(), loads);
        }

        /** Whether the tile holds what can come down: a building, a fuel tank or a bridge span, not its ruin. */
        static boolean structure(Tile tile) {
            return tile.cover().structure() > 0 && tile.cover().structure() != Cover.INDUSTRIAL
                  || tile.features().stream().anyMatch(feature -> "bridge".equals(feature.asset()) && !feature.ruined());
        }

        /** Whether {@code coords} rises or sinks in this change. */
        boolean moves(Coords coords) {
            return changes.stream().anyMatch(change -> change.coords().equals(coords));
        }

        /** Whether a structure comes down at {@code coords} in this change. */
        boolean collapses(Coords coords) {
            return collapses.stream().anyMatch(collapse -> collapse.coords().equals(coords));
        }
    }

    /** A scheduled strike has a location and resolved blast parameters, but no attacking unit. */
    /**
     * A strike or an explosion, with everything it brings down ({@code collapses}): what its own checkpoint shows gone
     * and what the rules bring down at the phase's end; and the trees it fells ({@code felled}). Each falls as its front
     * reaches it. An explosion another set off (a chain of fuel tanks, nested inside it on the server) names that one as
     * its {@code cause}, null for any other.
     */
    record Detonation(megamek.common.NuclearDetonation result, List<TileTransition.Collapse> collapses, List<Felled> felled,
          megamek.common.NuclearDetonation cause) implements Animation {
        /** A hex's trees a strike fells: its scene's tree features that stand nowhere in the strike's checkpoint. */
        record Felled(Coords coords, List<Feature> trees) {
            Felled {
                trees = List.copyOf(trees);
            }
        }

        Detonation {
            collapses = List.copyOf(collapses);
            felled = List.copyOf(felled);
        }

        Detonation(megamek.common.NuclearDetonation result, List<TileTransition.Collapse> collapses) {
            this(result, collapses, List.of(), null);
        }

        Detonation(megamek.common.NuclearDetonation result) { this(result, List.of(), List.of(), null); }

        @Override
        public int entityId() { return -1; }

        @Override
        public int boardId() { return result.boardId(); }
    }

    /**
     * Target type is captured with the visible event, independently of its chosen model or sprite. An attacker off the
     * map (off-board artillery) has no place on the board to show, so the camera frames only its target.
     * {@code attackerClan} decides a mixed-tech weapon's look (laser colour).
     */
    record Combat(megamek.common.ResolvedAttack result, Unit attacker, Unit target, Waypoint destination,
          boolean conventionalInfantryTarget, boolean attackerOffBoard, boolean attackerClan) implements Animation {
        Combat(megamek.common.ResolvedAttack result, Unit attacker, Unit target, Waypoint destination) {
            this(result, attacker, target, destination, false, false);
        }

        Combat(megamek.common.ResolvedAttack result, Unit attacker, Unit target, Waypoint destination,
              boolean conventionalInfantryTarget, boolean attackerOffBoard) {
            this(result, attacker, target, destination, conventionalInfantryTarget, attackerOffBoard, false);
        }

        Combat(megamek.common.ResolvedAttack result, Unit attacker, Unit target, Waypoint destination,
              boolean conventionalInfantryTarget) {
            this(result, attacker, target, destination, conventionalInfantryTarget, false);
        }

        @Override
        public int entityId() { return attacker.id(); }

        @Override
        public int boardId() { return result.attacker().boardId(); }
    }

    record Conversion(int boardId, Unit before, Unit after) implements Animation {
        @Override
        public int entityId() { return after.id(); }
    }

    public record Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP, int movementMP,
          Unit unit, float gravity) implements Animation {
        public Movement {
            path = List.copyOf(path);
        }

        public Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP, int movementMP) {
            this(entityId, boardId, path, type, jumpMP, movementMP, null);
        }

        public Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP, int movementMP, Unit unit) {
            this(entityId, boardId, path, type, jumpMP, movementMP, unit, 1);
        }

        public Movement(int entityId, int boardId, List<Waypoint> path, EntityMovementType type, int jumpMP) {
            this(entityId, boardId, path, type, jumpMP, 0);
        }

        boolean forced() { return type == EntityMovementType.MOVE_NONE; }
    }

    /** Immutable pixel ownership avoids accessing AWT images or the tileset from the GL thread. */
    public static final class Pixels {
        private final int width;
        private final int height;
        private final int[] argb;
        private final int[] runEnds;
        private final int hash;

        public Pixels(BufferedImage image) {
            this(image.getWidth(), image.getHeight(), read(image, null));
        }

        static Pixels copy(Image source) {
            ImageIcon loaded = new ImageIcon(source);
            BufferedImage copy = new BufferedImage(Math.max(1, loaded.getIconWidth()), Math.max(1, loaded.getIconHeight()),
                  BufferedImage.TYPE_INT_ARGB);
            var graphics = copy.createGraphics();
            try {
                graphics.drawImage(loaded.getImage(), 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return new Pixels(copy);
        }

        private static int[] read(BufferedImage image, int[] target) {
            int width = image.getWidth();
            int height = image.getHeight();
            if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
                return image.getRGB(0, 0, width, height, target, 0, width);
            }
            int[] result = target == null ? new int[width * height] : target;
            Raster raster = image.getRaster();
            DataBufferInt data = (DataBufferInt) raster.getDataBuffer();
            SinglePixelPackedSampleModel sample = (SinglePixelPackedSampleModel) raster.getSampleModel();
            int offset = data.getOffset() + sample.getOffset(raster.getMinX() - raster.getSampleModelTranslateX(),
                  raster.getMinY() - raster.getSampleModelTranslateY());
            int[] source = data.getData();
            for (int row = 0; row < height; row++) {
                System.arraycopy(source, offset + row * sample.getScanlineStride(), result, row * width, width);
            }
            return result;
        }

        private Pixels(int width, int height, int[] argb) {
            this(width, height, argb, null, 31 * (31 * width + height) + Arrays.hashCode(argb));
        }

        private Pixels(int width, int height, int[] argb, int[] runEnds, int hash) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.runEnds = runEnds;
            this.hash = hash;
        }

        /** Lossless storage for sparse material masks, without reducing their resolution or color precision. */
        Pixels compact() {
            if (runEnds != null) { return this; }
            int runs = 1;
            for (int i = 1; i < argb.length; i++) { if (argb[i] != argb[i - 1]) { runs++; } }
            if (runs * 2 >= argb.length) { return this; }
            int[] colors = new int[runs], ends = new int[runs];
            int run = 0;
            for (int i = 1; i <= argb.length; i++) {
                if (i == argb.length || argb[i] != argb[i - 1]) {
                    colors[run] = argb[i - 1];
                    ends[run++] = i;
                }
            }
            return new Pixels(width, height, colors, ends, hash);
        }

        public static Pixels capture(BufferedImage image, Pixels previous) {
            if (image == null) {
                return null;
            }
            Pixels next = new Pixels(image);
            return next.equals(previous) ? previous : next;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof Pixels pixels) || width != pixels.width || height != pixels.height || hash != pixels.hash) {
                return false;
            }
            if ((runEnds == null) == (pixels.runEnds == null)) {
                return Arrays.equals(argb, pixels.argb) && Arrays.equals(runEnds, pixels.runEnds);
            }
            for (int i = 0; i < width * height; i++) { if (rgba(i) != pixels.rgba(i)) { return false; } }
            return true;
        }

        /** How many values this keeps in memory: a compacted image's runs, else its texels. */
        int stored() { return argb.length + (runEnds == null ? 0 : runEnds.length); }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int rgba(int index) {
            if (runEnds != null) {
                int run = Arrays.binarySearch(runEnds, index + 1);
                index = run < 0 ? -run - 1 : run;
            }
            return Integer.rotateLeft(argb[index], 8);
        }

        /** Sequential atlas upload decodes each compressed run once, without expanding a second image on the heap. */
        void writeRgba(IntBuffer target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, width * height);
            int end = offset + length;
            if (runEnds == null) {
                for (int i = offset; i < end; i++) { target.put(Integer.rotateLeft(argb[i], 8)); }
                return;
            }
            int run = Arrays.binarySearch(runEnds, offset + 1);
            if (run < 0) { run = -run - 1; }
            while (offset < end) {
                int stop = Math.min(end, runEnds[run]), color = Integer.rotateLeft(argb[run++], 8);
                while (offset < stop) { target.put(color); offset++; }
            }
        }
    }

    /** Exact sharing, owned by the Swing source. Only artwork still referenced by the current scene is retained. */
    static final class PixelPool {
        private final Map<Pixels, Pixels> images = new HashMap<>();
        private final Map<Integer, int[]> buffers = new HashMap<>();
        private final Map<Pixels, Boolean> visible = new HashMap<>();

        Pixels capture(BufferedImage image, Pixels previous) {
            if (image == null) {
                return null;
            }
            int[] buffer = buffers.computeIfAbsent(image.getWidth() * image.getHeight(), size -> new int[size]);
            Pixels.read(image, buffer);
            Pixels lookup = new Pixels(image.getWidth(), image.getHeight(), buffer);
            if (lookup.equals(previous)) {
                return previous;
            }
            Pixels shared = images.get(lookup);
            if (shared != null) {
                return shared;
            }
            Pixels owned = new Pixels(lookup.width, lookup.height, buffer.clone());
            images.put(owned, owned);
            return owned;
        }

        Pixels captureOverlay(BufferedImage image, Pixels previous) {
            Pixels pixels = capture(image, previous);
            return pixels != null && visible.computeIfAbsent(pixels,
                  value -> Arrays.stream(value.argb).anyMatch(pixel -> (pixel >>> 24) != 0)) ? pixels : null;
        }

        void retain(List<Tile> tiles) {
            Set<Pixels> used = new HashSet<>();
            for (Tile tile : tiles) {
                used.add(tile.decals());
                used.add(tile.decalsWithoutLimbs());
                used.add(tile.tileset());
                used.add(tile.tilesetDecals());
                used.add(tile.tilesetScenery());
            }
            images.keySet().retainAll(used);
            visible.keySet().retainAll(images.keySet());
        }

        void clear() {
            images.clear();
            buffers.clear();
            visible.clear();
        }
    }
}
