/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardBridgeMaterialsTest {
    @ParameterizedTest
    @EnumSource(value = BoardRoad.Kind.class, names = { "PAVED", "ALLEY", "DIRT", "GRAVEL" })
    void matchingApproachesContinueTheirMaterialAndMarkingsAcrossTheDeck(BoardRoad.Kind kind) {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                var scene = straight(at, d, 1, kind, kind, kind);
                var tile = scene.tile(at);
                var deck = BoardBridge.deck(scene, tile);
                assertEquals(kind, deck.kind());
                var road = deck.road(at);
                assertTrue(road.joins().isEmpty());
                var patches = GpuRoads.deckPatches(tile, deck, road);
                assertEquals(kind == BoardRoad.Kind.PAVED, patches.stream()
                      .anyMatch(p -> p.texture().equals("concrete-ground") && !p.shape().isEmpty()));
                assertTrue(patches.stream().filter(p -> !p.shape().isEmpty()).allMatch(p ->
                      p.texture().equals(GpuRoads.texture(kind)) || p.texture().equals("concrete-ground")));
                assertFalse(patches.stream().anyMatch(p -> p.fade() != null && p.fade().outer() > 0),
                      "Decks must not grow loose shoulders outside their rails");
            }
        }
    }

    @Test
    void mixedApproachesUseTheSameTransitionOnTheRoadAndDeck() {
        for (var pair : List.of(List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT),
              List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.DIRT),
              List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.GRAVEL, BoardRoad.Kind.DIRT))) {
            for (int x : new int[] { 3, 4 }) {
                var at = new Coords(x, 4);
                for (int d = 0; d < 6; d++) {
                    var scene = straight(at, d, 1, pair.getFirst(), pair.getLast(), pair.getFirst());
                    var tile = scene.tile(at);
                    var next = scene.tile(at.translated(d));
                    var deck = BoardBridge.deck(scene, tile);
                    assertEquals(pair.getFirst(), deck.kind());
                    var bridgeRoad = deck.road(at);
                    var groundRoad = BoardRoad.of(scene, next);
                    assertEquals(1, bridgeRoad.joins().size());
                    assertEquals(1, groundRoad.joins().size());
                    var a = GpuRoads.deckPatches(tile, deck, bridgeRoad).stream()
                          .filter(p -> !p.shape().isEmpty() && p.fade() != null && p.fade().join() != null)
                          .findFirst().orElseThrow();
                    var b = GpuRoads.patches(next, groundRoad).stream()
                          .filter(p -> !p.shape().isEmpty() && p.fade() != null && p.fade().join() != null)
                          .findFirst().orElseThrow();
                    assertEquals(a.texture(), b.texture(), "Both halves must use the same material over the same base");
                    if (pair.getFirst() == BoardRoad.Kind.GRAVEL) {
                        // The deck's paint lies on the bridge alone: the dirt road's coat cannot run on under it.
                        assertTrue(GpuRoads.deckPatches(tile, deck, bridgeRoad).stream().anyMatch(p -> !p.shape().isEmpty()
                              && p.texture().equals("roads/dirt") && GpuRoads.attribute(p).value == -6),
                              "The gravel deck lays its own dirt base under its cover");
                    }
                    var gate = BoardGeometry.center(next.coords(), 0).sub(BoardGeometry.center(at, 0)).scl(.5f);
                    var across = new Vector3(-gate.y, gate.x, 0).nor();
                    for (float offset : new float[] { -6, 0, 6 }) {
                        var p = new Vector3(gate).mulAdd(across, offset);
                        var q = new Vector3(gate).scl(-1).mulAdd(across, offset);
                        assertEquals(.5f, GpuRoads.materialCoverage(a, p.x, p.y), .001f);
                        assertEquals(GpuRoads.materialCoverage(a, p.x, p.y),
                              GpuRoads.materialCoverage(b, q.x, q.y), .001f);
                    }
                }
            }
        }
    }

    @Test
    void pavedApproachesKeepMarkingsWhenTheOtherApproachIsAnAlley() {
        var at = new Coords(4, 4);
        for (int d = 0; d < 6; d++) {
            var scene = straight(at, d, 1, BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.PAVED);
            var tile = scene.tile(at);
            var deck = BoardBridge.deck(scene, tile);
            assertEquals(BoardRoad.Kind.PAVED, deck.kind());
            assertTrue(GpuRoads.deckPatches(tile, deck, deck.road(at)).stream()
                  .anyMatch(p -> p.texture().equals("concrete-ground") && !p.shape().isEmpty()));
        }
    }

    @Test
    void dirtOnANaturalArchKeepsItsLooseMarginsAndWornTerminal() {
        var at = new Coords(4, 4);
        var scene = BoardBridgeTypeTest.typed(straight(at, 0, 1, BoardRoad.Kind.NONE,
              BoardRoad.Kind.NONE, BoardRoad.Kind.NONE), HexAppearance.NATURAL_BRIDGE);
        scene = BoardScenes.replace(scene, withDeckRoad(scene.tile(at), BoardRoad.Kind.DIRT, 1));
        var deck = BoardBridge.deck(scene, scene.tile(at));
        var patches = GpuRoads.deckPatches(scene.tile(at), deck, deck.road(at));
        // The ground's dirt track (-5, not a built deck's closed -6) frays into loose shoulders in the shader.
        assertTrue(patches.stream().anyMatch(patch -> !patch.shape().isEmpty() && GpuRoads.attribute(patch).value == -5),
              "A rock arch has ground-like loose shoulders");
        assertTrue(patches.stream().anyMatch(patch -> !patch.shape().isEmpty() && patch.fade() != null
              && patch.fade().end() > 0), "A dirt road ending on the arch must wear out rather than stop square");
    }

    @Test
    void groundAndDeckRoadPaintUseTheirOwnAppearanceWithoutRepaintingOtherCoats() {
        var at = new Coords(4, 4);
        var tile = BoardBridgeTypeTest.appearance(BoardScenes.tile(at, BoardRoad.Kind.PAVED, 9,
              0, BoardScene.Surface.GRASS), Map.of("road", new HexAppearance(null, null, "gravel", null),
                    "bridgeRoad", new HexAppearance(null, null, "dirt", null)));
        tile = withDeckRoad(tile, BoardRoad.Kind.PAVED, 9);
        assertEquals("gravel", GpuRoads.material(tile, false, "roads/asphalt"));
        assertEquals("dirt", GpuRoads.material(tile, true, "roads/asphalt"));
        assertEquals("gravel", GpuRoads.material(tile, true, "roads/gravel"), "Transition and shoulder coats stay distinct");
        assertNull(GpuRoads.material(tile, true, "concrete-ground"), "Markings retain their own material");
    }

    /** b13 ways: a deck's road is painted on its own sides only; the slab still spans every bridge exit. */
    @Test
    void aDeckRoadIsPaintedOnItsOwnSidesOverTheWholeSlab() {
        var at = new Coords(4, 4);
        var scene = straight(at, 0, 1, BoardRoad.Kind.PAVED, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE);
        scene = BoardScenes.replace(scene, withDeckRoad(scene.tile(at), BoardRoad.Kind.PAVED, 1));
        var deck = BoardBridge.deck(scene, scene.tile(at));
        assertFalse(deck.paved(), "Half a deck painted is half road");
        float[] north = megamek.common.board.RailRoute.border(0), south = megamek.common.board.RailRoute.border(3);
        assertTrue(deck.road(at).distance(north[0], north[1]) < 0, "The road reaches its own side");
        assertTrue(deck.road(at).distance(south[0], south[1]) > 0, "and ends at the centre");
        assertTrue(deck.slab(at).distance(south[0], south[1]) < 0, "The slab spans every exit");
        assertFalse(GpuRoads.deckPatches(scene.tile(at), deck, deck.road(at)).isEmpty());
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

    /** {@code tile} with its deck carrying a road of {@code kind} on {@code exits} (a stored {@code bridge_road}). */
    static BoardScene.Tile withDeckRoad(BoardScene.Tile t, BoardRoad.Kind kind, int exits) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(),
              t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(),
              t.cliffTopExits(), t.bare(), t.groundCover(), t.ultraSublevel(), t.tilesetDecals(),
              t.tilesetScenery(), t.appearance(), t.cover(), t.barren(), kind, exits, t.deckMud(), t.railBed());
    }
}
