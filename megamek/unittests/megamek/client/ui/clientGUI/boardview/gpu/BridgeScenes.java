/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Bridge spans, decks and route tiles the board tests share. */
final class BridgeScenes {
    /** How far the mesh may stray from the ease it follows in rows. */
    static final float EASED = .06f;
    static final Coords START = new Coords(4, 2);
    /** Lava Tubes 1's spans 1010 and 1011, the 2D editor's way to stand them on its banks: heights 4 and 4 or 3. */
    static final List<Coords> RAISED = List.of(new Coords(9, 9), new Coords(9, 10));

    private BridgeScenes() { }

    static BoardScene.Tile bridge(Coords at, int ground, int height, int exits) {
        var tile = BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, ground, BoardScene.Surface.ROCK);
        var hex = new Hex(ground);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, height));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        return new BoardScene.Tile(at, ground, -1, false, 0, tile.surface(), null, null,
              BoardFeatures.capture(hex, at, Map.of()), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE);
    }

    /**
     * The share of its drop an Enhanced solid ramp has made at {@code t} of the way from the deck's centre to the
     * bank's: one ease from one inset to the other, level at both.
     */
    static float eased(float t) {
        float s = Math.clamp(2 * t - .5f, 0, 1);
        return s * s * (3 - 2 * s);
    }

    static float height(BoardBridge.Shape shape, Vector3 point, boolean underside) {
        var ray = new Ray(new Vector3(point.x, point.y, underside ? -200 : 200),
              new Vector3(0, 0, underside ? 1 : -1));
        float distance = (float) Math.sqrt(shape.hit(ray));
        return ray.origin.z + ray.direction.z * distance;
    }

    static BoardScene lowerBank(Coords at, int direction, boolean paved) {
        var bankAt = at.translated(direction);
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        return BoardScenes.scene(c -> {
            if (c.equals(at)) {
                var tile = bridge(c, -1, 2, exits);
                return paved ? withDeckRoad(tile, BoardRoad.Kind.PAVED, exits) : tile;
            }
            return BoardScenes.tile(c, paved && c.equals(bankAt) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  paved && c.equals(bankAt) ? exits : 0, c.equals(bankAt) ? 0 : -1, BoardScene.Surface.GRASS);
        });
    }

    static int step(int direction) { return direction % 3 - 1; }

    static BoardScene routeScene(Coords center, int exits, boolean roads) {
        var route = new HashMap<Coords, BoardScene.Tile>();
        // Roads meet the span, whose decks carry a paved road over their exits (as a legacy board's conversion writes).
        java.util.function.UnaryOperator<BoardScene.Tile> paved = tile -> roads ? withDeckRoad(tile,
              BoardRoad.Kind.PAVED, BoardBridge.feature(tile).bridgeExits()) : tile;
        route.put(center, paved.apply(bridge(center, -1, 3, exits)));
        for (int d = 0; d < 6; d++) {
            if ((exits & (1 << d)) == 0) { continue; }
            var at = center.translated(d);
            int straight = (1 << d) | (1 << ((d + 3) % 6));
            // Vary the supporting ground too: connectivity uses absolute deck elevation.
            route.put(at, paved.apply(bridge(at, -2, 4 + step(d), straight)));
            if (roads) {
                at = at.translated(d);
                route.put(at, BoardScenes.tile(at, BoardRoad.Kind.PAVED, straight, 2 + step(d), BoardScene.Surface.ROCK));
            }
        }
        var tiles = new ArrayList<BoardScene.Tile>();
        int width = Math.max(9, center.getX() + 4), height = Math.max(9, center.getY() + 4);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var at = new Coords(x, y);
                tiles.add(route.getOrDefault(at, BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, -2, BoardScene.Surface.ROCK)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** {@code scene} with every bridge tile storing {@code type}, as a converted board's bridges do. */
    static BoardScene typed(BoardScene scene, HexAppearance type) {
        var tiles = scene.tiles().stream()
              .map(t -> BoardBridge.feature(t) == null ? t : appearance(t, Map.of("bridge", type))).toList();
        return new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene.Tile appearance(BoardScene.Tile t, Map<String, HexAppearance> appearance) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), appearance);
    }

    /** {@code tile} with its deck carrying a road of {@code kind} on {@code exits} (a stored {@code bridge_road}). */
    static BoardScene.Tile withDeckRoad(BoardScene.Tile t, BoardRoad.Kind kind, int exits) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(),
              t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(),
              t.cliffTopExits(), t.bare(), t.groundCover(), t.ultraSublevel(), t.tilesetDecals(),
              t.tilesetScenery(), t.appearance(), t.cover(), t.barren(), kind, exits, t.deckMud(), t.railBed());
    }

    /**
     * A straight span of {@code length} deck hexes over water between roads of the kinds {@code before} and
     * {@code after} (NONE: no road); each deck carries a road of kind {@code deck} over both its exits, or none.
     */
    static BoardScene straight(Coords start, int direction, int length, BoardRoad.Kind before, BoardRoad.Kind after,
          BoardRoad.Kind deck) {
        int exits = (1 << direction) | (1 << ((direction + 3) % 6));
        Map<Coords, BoardScene.Tile> route = new HashMap<>();
        for (int i = 0; i < length; i++) {
            Coords at = start.translated(direction, i);
            Hex bridge = new Hex(0);
            bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
            bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 0));
            bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
            route.put(at, withDeckRoad(new BoardScene.Tile(at, 0, 1, false, 0, BoardScene.Surface.GRASS, null, null,
                  BoardFeatures.capture(bridge, at, Map.of()), List.of(),
                  BoardLiquid.WATER, null, true, BoardRoad.Kind.NONE), deck, deck == BoardRoad.Kind.NONE ? 0 : exits));
        }
        Coords entrance = start.translated((direction + 3) % 6), exit = start.translated(direction, length);
        route.put(entrance, BoardScenes.tile(entrance, before, before == BoardRoad.Kind.NONE ? 0 : exits, 0, BoardScene.Surface.GRASS));
        route.put(exit, BoardScenes.tile(exit, after, after == BoardRoad.Kind.NONE ? 0 : exits, 0, BoardScene.Surface.GRASS));
        int width = Math.max(9, route.keySet().stream().mapToInt(Coords::getX).max().orElseThrow() + 2);
        int height = Math.max(9, route.keySet().stream().mapToInt(Coords::getY).max().orElseThrow() + 2);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var at = new Coords(x, y);
                tiles.add(route.getOrDefault(at, BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /**
     * A N-S route of {@code kind} from {@link #START}, a hex per entry of {@code levels} (a maglev's fixed level; a rail
     * lies on the ground), Pillars on the markers at the indices in {@code toggled}, water:1 beneath those in {@code wet}.
     */
    static BoardScene line(RailRoute.Kind kind, double[] levels, Set<Integer> toggled, Set<Integer> wet) {
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        for (int i = 0; i < levels.length; i++) {
            Coords at = START.translated(3, i);
            int sides = (i > 0 ? RailRoute.side(0) : 0) | (i < levels.length - 1 ? RailRoute.side(3) : 0);
            tiles.put(at, routeTile(at, wet.contains(i), kind, (int) levels[i], sides, toggled.contains(i), 0));
        }
        return BoardScenes.scene(at -> tiles.getOrDefault(at,
              BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
    }

    /** The tile's pier facets, built as the terrain's roads stage builds its bridge shape. */
    static List<BoardBridge.Facet> piers(BoardScene scene, Coords at) {
        var tile = scene.tile(at);
        var surfaces = new HashMap<Coords, BoardSurface>();
        var deck = BoardBridge.deck(scene, tile);
        BoardBridge.Shape shape;
        if (deck.natural()) {
            shape = BoardNaturalBridge.build(scene, tile, deck, TerrainLod.FULL, surfaces);
        } else {
            var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
            shape = deck.sloped() ? BoardBridgeSlope.build(tile, deck, footing) : footing.shape();
        }
        return BoardBridgeFooting.withPiers(scene, tile, deck, shape, TerrainLod.FULL, surfaces).facets().stream()
              .filter(facet -> facet.part() == BoardBridge.Part.PIER).toList();
    }

    /** Native terrain owns the route and, for a raised maglev, the implicit bridge and its Pillars choice. */
    static BoardScene.Tile routeTile(Coords at, boolean water, RailRoute.Kind kind, int level, int sides,
          boolean pillars, int groundRail) {
        boolean deck = kind == RailRoute.Kind.MAGLEV && level > 0;
        Hex hex = new Hex(0, (water ? "water:1;" : "") + (deck
              ? "bridge:1:" + sides + ";bridge_elev:" + level + ";bridge_cf:40;bridge_rail:2:" + sides
                    + (groundRail == 0 ? "" : ";rail:1:" + groundRail)
              : "rail:" + (kind == RailRoute.Kind.MAGLEV ? 2 : 1) + ":" + sides), "");
        if (deck) { hex.setAppearance(Map.of("bridge", pillars ? HexAppearance.IMPLICIT_PILLARS : HexAppearance.IMPLICIT_BRIDGE)); }
        var features = new java.util.ArrayList<BoardScene.Feature>();
        RailRoute.derived(hex, "terrain").forEach(marker -> features.add(feature(marker)));
        if (deck) { features.add(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, level, BoardScene.FeatureKind.PROP, sides)); }
        var t = new BoardScene.Tile(at, 0, water ? 1 : -1, false, 0, BoardScene.Surface.GRASS, null, null,
              features, List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE);
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), hex.getAppearance(),
              t.cover(), t.barren(), BoardRoad.Kind.NONE, 0, false, RailRoute.groundSides(hex));
    }

    /**
     * A N-S span of {@code length} hexes from {@link #START}, its deck {@code deck} levels above water:1 or dry ground,
     * between paved roads (a built deck); the hexes at the indices in {@code toggled} have the Pillars toggle on.
     */
    static BoardScene span(int length, boolean water, int deck, Set<Integer> toggled) {
        Map<Coords, BoardScene.Tile> route = new HashMap<>();
        for (int i = 0; i < length; i++) {
            Coords at = START.translated(3, i);
            var tile = deckTile(at, 9, water, deck);
            route.put(at, toggled.contains(i) ? pillared(tile) : tile);
        }
        Coords entrance = START.translated(0), exit = START.translated(3, length);
        route.put(entrance, BoardScenes.tile(entrance, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        route.put(exit, BoardScenes.tile(exit, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        return BoardScenes.scene(at -> route.getOrDefault(at,
              BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
    }

    static BoardScene.Feature feature(BoardDecoration marker) {
        return new BoardScene.Feature(marker.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, marker);
    }

    static BoardScene.Tile deckTile(Coords at, int exits, boolean water, int deck) {
        Hex bridge = new Hex(0);
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deck));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        return new BoardScene.Tile(at, 0, water ? 1 : -1, false, 0, BoardScene.Surface.GRASS, null, null,
              BoardFeatures.capture(bridge, at, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true,
              BoardRoad.Kind.NONE);
    }

    /** The tile with its bridge's Pillars toggle on. */
    static BoardScene.Tile pillared(BoardScene.Tile t) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(),
              Map.of("bridge", HexAppearance.PILLARS));
    }

    /** A natural span's deck, its type stored as a converted board stores it. */
    static BoardScene.Tile naturalSpan(BoardScene.Tile ground, int exits) {
        var hex = new Hex(ground.elevation());
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, exits));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 3));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 100));
        return appearance(new BoardScene.Tile(ground.coords(), ground.elevation(), ground.waterDepth(),
              ground.frozen(), ground.roadExits(), ground.surface(), null, null,
              BoardFeatures.capture(hex, ground.coords(), Map.of()), List.of(), ground.liquid(), null, true, ground.road()),
              Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
    }

    static BoardScene canyonScene(BoardScene.Surface family, boolean water, boolean underRoad) {
        return canyonScene(family, water, underRoad, BoardScenes.CENTER, 0);
    }

    static BoardScene canyonScene(BoardScene.Surface family, boolean water, boolean underRoad, Coords bridge, int direction) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var center = BoardGeometry.center(bridge, 0);
        var along = BoardGeometry.center(bridge.translated((direction + 1) % 6), 0).sub(center).nor();
        var across = new Vector3(-along.y, along.x, 0);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                var at = new Coords(x, y);
                var relative = BoardGeometry.center(at, 0).sub(center);
                boolean canyon = Math.abs(relative.dot(across)) < BoardGeometry.height() * .55f;
                boolean road = !water && underRoad && Math.abs(relative.dot(across)) < .01f;
                int level = canyon ? 0 : 3;
                var ground = BoardScenes.tile(at, road ? BoardRoad.Kind.GRAVEL : BoardRoad.Kind.NONE,
                      road ? (1 << ((direction + 1) % 6)) | (1 << ((direction + 4) % 6)) : 0, level, family);
                if (water && canyon) {
                    ground = new BoardScene.Tile(at, 0, 1, false, 0, family, null, null,
                          List.of(), List.of(), BoardLiquid.WATER, null, true, BoardRoad.Kind.NONE);
                }
                tiles.add(at.equals(bridge) ? naturalSpan(ground, (1 << direction) | (1 << ((direction + 3) % 6))) : ground);
            }
        }
        return new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene raisedLavaTubes(int north, int south) throws Exception {
        return BoardScenes.installedScene("Map Pack Volcanic/16x17 Lava Tubes 1.board", board -> {
            int[] heights = { north, south };
            for (int i = 0; i < RAISED.size(); i++) {
                var hex = board.getHex(RAISED.get(i));
                hex.removeTerrain(Terrains.BRIDGE_ELEV);
                hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, heights[i]));
                board.setHex(RAISED.get(i), hex);
            }
        });
    }
}
