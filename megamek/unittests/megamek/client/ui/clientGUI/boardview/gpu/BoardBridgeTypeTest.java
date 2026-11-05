/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BridgeConstruction;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bridge is built or natural ({@link HexAppearance#bridgeBuilt}): one legacy decode ({@link BridgeSpan#decode}) types
 * the bridges the conversion writes and the 3D editor opens; the render draws the stored type, and an untyped span built.
 */
class BoardBridgeTypeTest {
    private static final String DECK = "bridge:1:09;bridge_cf:40;bridge_elev:";

    /** A N-S span of two decks at column {@code x} (rows 2-3) between banks at rows 1 and 4, with the case's extras. */
    private record Case(String name, int x, boolean built, Map<Coords, String> terrain) { }

    private static Case span(String name, int x, boolean built, int deck, String north, String south, String extra) {
        Map<Coords, String> terrain = new LinkedHashMap<>();
        terrain.put(new Coords(x, 1), north);
        terrain.put(new Coords(x, 2), extra + DECK + deck);
        terrain.put(new Coords(x, 3), DECK + deck);
        terrain.put(new Coords(x, 4), south);
        return new Case(name, x, built, terrain);
    }

    /** The decode's categories, one span each, three columns apart so no case sees another. */
    private static final List<Case> CASES = new ArrayList<>();
    static {
        CASES.add(span("natural between bare banks", 1, false, 1, "", "", ""));
        CASES.add(span("a road two levels below a deck end", 4, true, 3, "road:1:09", "", ""));
        CASES.add(span("a road crossing beneath the deck", 7, true, 3, "", "", "road:1:18;"));
        CASES.add(span("railway art at a deck end", 10, true, 1, "fluff:10:05", "", ""));
        CASES.add(span("pavement on the deck hex", 13, true, 1, "", "", "pavement:1;"));
        CASES.add(span("snow-covered pavement at a deck end", 16, true, 1, "pavement:1;snow:1", "", ""));
        var beside = span("a building beside the span", 19, true, 1, "", "", "");
        beside.terrain().put(new Coords(19, 3).translated(2), "building:1;bldg_cf:15;bldg_elev:1");
        CASES.add(beside);
    }

    private static Board legacy() {
        Board board = Board.createEmptyBoard(22, 7);
        for (var shown : CASES) {
            shown.terrain().forEach((at, text) -> board.setHex(at, new Hex(text.contains("bridge") ? 0 : 1, text, "")));
        }
        return board;
    }

    private static boolean decoded(Board board, Coords at) {
        return BridgeSpan.decode(board::getHex, c -> false, BridgeSpan.of(board::getHex, at));
    }

    @Test
    void oneDecodeTypesEachSpanInTheConversionAndTheRenderDrawsIt() throws Exception {
        Board legacy = legacy(), imported = legacy();
        for (var shown : CASES) {
            for (int y = 2; y <= 3; y++) {
                assertEquals(shown.built(), decoded(legacy, new Coords(shown.x(), y)), shown.name());
            }
        }
        // Pillar art on one hex makes its whole span built; a stored type beats every signal, built beating natural.
        assertTrue(BridgeSpan.decode(legacy::getHex, c -> c.equals(new Coords(1, 3)), BridgeSpan.of(legacy::getHex, new Coords(1, 2))));
        Board stored = legacy();
        for (int y = 2; y <= 3; y++) { type(stored, new Coords(4, y), HexAppearance.NATURAL_BRIDGE); }
        type(stored, new Coords(1, 3), HexAppearance.BUILT_BRIDGE);
        assertFalse(decoded(stored, new Coords(4, 3)), "A stored natural road bridge stays natural");
        assertTrue(decoded(stored, new Coords(1, 2)), "One stored built hex types its whole span");
        type(stored, new Coords(1, 2), HexAppearance.NATURAL_BRIDGE);
        assertTrue(decoded(stored, new Coords(1, 2)), "Built wins in a mixed span");

        BoardSceneryLayouts.convert(imported);
        var drawn = new BoardScene(0, imported.getWidth(), imported.getHeight(), BoardTiles.captureAll(imported), List.of(),
              List.of(), -1, "", List.of());
        for (var shown : CASES) {
            for (int y = 2; y <= 3; y++) {
                Coords at = new Coords(shown.x(), y);
                var expected = shown.built() ? HexAppearance.BUILT_BRIDGE : HexAppearance.NATURAL_BRIDGE;
                assertEquals(expected, imported.getHex(at).getAppearance().get("bridge"), "Conversion: " + shown.name());
                assertEquals(expected, drawn.tile(at).appearance().get("bridge"), "Drawn: " + shown.name());
                assertEquals(!shown.built(), BoardBridge.deck(drawn, drawn.tile(at)).natural(), "Deck: " + shown.name());
            }
        }
        assertNull(imported.getHex(new Coords(4, 1)).getAppearance().get("bridge"), "Only bridge hexes get a type");
    }

    private static void type(Board board, Coords at, HexAppearance type) {
        Hex hex = board.getHex(at).duplicate();
        hex.setAppearance(Map.of("bridge", type));
        board.setHex(at, hex);
    }

    @Test
    void theEditorTypesAnOpenedBoardsUntypedBridges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("Untyped.board2");
        Board board = legacy();
        // A .board2 holds a rail route marker instead of the rail art: the untyped span still sees its railway.
        board.getHex(new Coords(10, 1)).setDecorations(List.of(new BoardDecoration("rail", "prop", RailRoute.Kind.RAIL.asset,
              null, 0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false).withConnections(RailRoute.side(3))));
        board.setNativeFormat(true);
        BoardFile.save(board, file);
        var session = new BoardEditorSession(BoardEditorBlueprint.get());
        session.open(file);
        for (var shown : CASES) {
            for (int y = 2; y <= 3; y++) {
                assertEquals(shown.built(), HexAppearance.bridgeBuilt(session.board().getHex(shown.x(), y).getAppearance()),
                      shown.name());
            }
        }
        assertFalse(session.dirty(), "The stored types are the ones the board was drawn with");
    }

    @Test
    void engineersBuildABuiltBridge() {
        // A fresh section is built; one rebuilt where a built section with piers stood (its look kept) keeps them.
        for (var before : java.util.Arrays.asList(null, HexAppearance.NATURAL_BRIDGE, HexAppearance.PILLARS)) {
            Board board = Board.createEmptyBoard(3, 3);
            board.setHex(1, 0, new Hex(1));
            Hex water = new Hex(0, "water:1", "");
            if (before != null) { water.setAppearance(Map.of("bridge", before)); }
            board.setHex(1, 1, water);
            board.setHex(1, 2, new Hex(1));
            BridgeConstruction.placeBridge(board, new Coords(1, 1), 9, 1, 15);
            assertEquals(before == HexAppearance.PILLARS ? HexAppearance.PILLARS : HexAppearance.BUILT_BRIDGE,
                  board.getHex(1, 1).getAppearance().get("bridge"), "Before: " + before);
        }
    }

    @Test
    void aOneSidedExitJoinsDecksAtOneLevelAndTheConversionWritesTheMissingExit() {
        // In columns 1 and 3 a deck points south into a deck that points only south (BaelCity's one-hex pieces); a road
        // at the north end makes the north deck built. Column 3's south deck is a level higher.
        Board board = Board.createEmptyBoard(5, 6);
        for (int x : new int[] { 1, 3 }) {
            board.setHex(x, 1, new Hex(1, "road:1:09", ""));
            board.setHex(x, 2, new Hex(0, DECK + 1, ""));
            board.setHex(x, 3, new Hex(0, "bridge:1:08;bridge_cf:40;bridge_elev:" + (x == 1 ? 1 : 2), ""));
            board.setHex(x, 4, new Hex(1));
        }
        Coords north = new Coords(1, 2), south = new Coords(1, 3), high = new Coords(3, 3);
        assertEquals(Set.of(north, south), Set.copyOf(BridgeSpan.of(board::getHex, north)), "From the deck that points");
        assertEquals(Set.of(north, south), Set.copyOf(BridgeSpan.of(board::getHex, south)), "From the one that does not");
        assertEquals(List.of(high), BridgeSpan.of(board::getHex, high), "Decks at different levels are not joined");
        assertEquals(List.of(new Coords(3, 2)), BridgeSpan.of(board::getHex, new Coords(3, 2)));
        assertTrue(decoded(board, south), "The piece takes its span's built type");
        assertFalse(decoded(board, high), "The higher piece is decoded alone");

        BoardSceneryLayouts.convert(board);
        assertEquals(9, board.getHex(south).getTerrain(Terrains.BRIDGE).getExits(), "The missing exit is written");
        assertEquals(8, board.getHex(high).getTerrain(Terrains.BRIDGE).getExits(), "No exit is guessed");
        assertEquals(9, board.getHex(north).getTerrain(Terrains.BRIDGE).getExits());
        assertEquals(HexAppearance.BUILT_BRIDGE, board.getHex(south).getAppearance().get("bridge"));
        assertEquals(HexAppearance.NATURAL_BRIDGE, board.getHex(high).getAppearance().get("bridge"));
    }

    /** A bridge tile of a hand-made scene with {@code type} (null: none) and a deck {@code deck} levels up. */
    private static BoardScene.Tile deck(Coords at, int exits, boolean water, int deck, HexAppearance type) {
        Hex bridge = new Hex(0);
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deck));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        var t = new BoardScene.Tile(at, 0, water ? 1 : -1, false, 0, BoardScene.Surface.GRASS, null, null,
              BoardFeatures.capture(bridge, at, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true,
              BoardRoad.Kind.NONE);
        return type == null ? t : appearance(t, Map.of("bridge", type));
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

    private static final Coords AT = new Coords(4, 4);

    @Test
    void theRenderDrawsTheStoredTypeAndAPlainBuiltDeckWithoutMarkings() {
        // A stored natural span keeps its rock arch between paved roads.
        var paved = BoardBridgeMaterialsTest.straight(AT, 3, 1, BoardRoad.Kind.PAVED, BoardRoad.Kind.PAVED, BoardRoad.Kind.PAVED);
        assertEquals(BoardRoad.Kind.PAVED, BoardBridge.deck(paved, paved.tile(AT)).kind());
        var natural = BoardScenes.replace(paved, appearance(paved.tile(AT), Map.of("bridge", HexAppearance.NATURAL_BRIDGE)));
        assertTrue(BoardBridge.deck(natural, natural.tile(AT)).natural());
        // A built deck without a road is clean (bare concrete); an Alley road is plain asphalt, unmarked; markings come
        // with a paved road.
        var bare = BoardBridgeMaterialsTest.straight(AT, 3, 1, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE);
        assertFalse(BoardBridge.deck(bare, bare.tile(AT)).natural(), "An untyped span is built, as the editor's stroke types a new bridge");
        assertFalse(BoardBridge.deck(bare, bare.tile(AT)).paved());
        var alley = BoardBridgeMaterialsTest.straight(AT, 3, 1, BoardRoad.Kind.NONE, BoardRoad.Kind.NONE, BoardRoad.Kind.ALLEY);
        var deck = BoardBridge.deck(alley, alley.tile(AT));
        assertTrue(deck.paved());
        assertTrue(GpuRoads.deckPatches(alley.tile(AT), deck, deck.road(AT)).stream()
              .noneMatch(p -> p.texture().equals("concrete-ground") && !p.shape().isEmpty()), "No centre markings without a road");
        var marked = BoardBridge.deck(paved, paved.tile(AT));
        assertTrue(GpuRoads.deckPatches(paved.tile(AT), marked, marked.road(AT)).stream()
              .anyMatch(p -> p.texture().equals("concrete-ground") && !p.shape().isEmpty()));
    }

    /**
     * b13 ways: a look span (material, truss colours, truss joints) joins only decks of one family, natural, truss or
     * standard: a natural arch continued by an implicit deck stays a rock arch, the implicit deck stays standard.
     */
    @Test
    void lookSpansJoinOnlyDecksOfOneFamily() {
        // A N-S run of decks a level up over water: natural, implicit (standard), truss, truss.
        var types = List.of(HexAppearance.NATURAL_BRIDGE, HexAppearance.IMPLICIT_BRIDGE, HexAppearance.TRUSS, HexAppearance.TRUSS);
        var scene = BoardScenes.scene(at -> at.getX() == 4 && at.getY() >= 2 && at.getY() <= 5
              ? deck(at, 9, true, 1, types.get(at.getY() - 2)) : BoardScenes.tile(at, BoardScene.Surface.GRASS, 0, -1, 0));
        var natural = BoardBridge.deck(scene, scene.tile(new Coords(4, 2)));
        var implicit = BoardBridge.deck(scene, scene.tile(new Coords(4, 3)));
        var truss = BoardBridge.deck(scene, scene.tile(new Coords(4, 4)));
        assertTrue(BoardBridge.connected(scene.tile(new Coords(4, 2)), scene.tile(new Coords(4, 3)), 3), "One span for the rules");
        assertTrue(natural.natural() && !implicit.natural(), "The rock arch beside an implicit deck");
        assertFalse(implicit.truss(), "The truss colours stay on the truss decks");
        assertTrue(truss.truss());
        assertEquals(0, natural.joints() | implicit.joints(), "No truss joint across a family seam");
        assertEquals(1 << 3, truss.joints(), "The truss continues into the truss deck south only");
    }

    @Test
    void piersStandOnlyUnderBuiltDecksAndTheApronStaysOffPavement() {
        // The Pillars toggle is a built type: its span is a built deck with piers even without a road.
        var pillared = BoardBridgeFootingTest.span(3, false, 2, Set.of(0, 1, 2));
        for (Coords end : List.of(BoardBridgeFootingTest.START.translated(0), BoardBridgeFootingTest.START.translated(3, 3))) {
            pillared = BoardScenes.replace(pillared, BoardScenes.tile(end, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS));
        }
        var middle = BoardBridgeFootingTest.START.translated(3);
        assertFalse(BoardBridge.deck(pillared, pillared.tile(middle)).natural());
        assertFalse(BoardBridgeFootingTest.piers(pillared, middle).isEmpty());
        // Switched to natural, the same span is a rock arch without piers.
        var arch = pillared;
        for (int i = 0; i < 3; i++) {
            Coords at = BoardBridgeFootingTest.START.translated(3, i);
            arch = BoardScenes.replace(arch, appearance(arch.tile(at), Map.of("bridge", HexAppearance.NATURAL_BRIDGE)));
        }
        assertTrue(BoardBridge.deck(arch, arch.tile(middle)).natural());
        assertTrue(BoardBridgeFootingTest.piers(arch, middle).isEmpty());

        // A built deck between a pavement bank (north) and a grass bank (south): the loose apron only on the grass.
        var scene = BoardScenes.scene(at -> at.equals(AT) ? deck(at, 9, false, 0, HexAppearance.BUILT_BRIDGE)
              : BoardScenes.tile(at, at.equals(AT.translated(0)) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS,
                    0, -1, 0));
        var footing = BoardBridgeFooting.build(scene, scene.tile(AT), TerrainLod.FULL, new java.util.HashMap<>());
        assertEquals(1 << 3, footing.bareExits(), "No dirt apron over pavement");
    }

    private static BoardTacticalGeometry.Surface support(BoardScene scene, Coords at) {
        return BoardTacticalGeometry.Surface.of(new BoardSurface(scene, scene.tile(at)), scene, BoardGeometry.floor(scene));
    }

    @Test
    void noBladeGrowsThroughALowDeck() {
        float half = BoardBridge.PASSAGE_HALF_WIDTH * BoardGeometry.hexScale();
        for (int level : new int[] { 0, 2 }) {
            var scene = BoardScenes.scene(at -> at.equals(AT) ? deck(at, 9, false, level, HexAppearance.BUILT_BRIDGE)
                  : BoardScenes.tile(at, BoardScene.Surface.GRASS, 0, -1, 0));
            FloatArray roots = GpuGroundCover.plant(scene, scene.tile(AT), support(scene, AT), null);
            int under = 0;
            for (int i = 0; i < roots.size; i += 4) {
                if (Math.abs(roots.get(i) - BoardGeometry.centerX(AT)) < half) { under++; }
            }
            System.out.printf("COVER deck %d levels up: %d of %d blades under the deck%n", level, under, roots.size / 4);
            if (level == 0) { assertEquals(0, under, "No blade through a ground-level deck"); }
            else { assertTrue(under > 50, "Grass grows beneath a raised deck: " + under); }
        }
    }
}
