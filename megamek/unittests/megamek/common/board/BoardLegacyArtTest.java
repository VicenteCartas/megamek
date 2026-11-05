/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Batch 10: the conversion takes every legacy art token ({@link BoardSceneryLayouts#takenTokens}): its art becomes
 * objects, a hex design draws it, or it drew nothing and is dropped. A game of the loaded board keeps the token in
 * memory for its 2D board; the written {@code .board2} holds none. Rail art is the rail step's.
 */
class BoardLegacyArtTest {
    private static final Coords AT = new Coords(2, 2);
    private static final int[] ART = { Terrains.FLUFF, Terrains.GROUND_FLUFF, Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF,
          Terrains.BLDG_FLUFF };

    /** A 5x5 legacy board with {@code terrain} on its centre hex, converted as a game converts it. */
    private static Board converted(String terrain) throws Exception {
        Board board = Board.createEmptyBoard(5, 5);
        board.setHex(AT, new Hex(0, terrain, ""));
        BoardSceneryLayouts.convert(board);
        return board;
    }

    /** The board as its .board2 holds it, read back. */
    private static Board written(Board board) throws Exception {
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        return BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
    }

    /** The legacy art terrains a written hex still holds. */
    private static Set<Integer> art(Hex hex) {
        return java.util.Arrays.stream(ART).filter(hex::containsTerrain).boxed().collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "{0} becomes {1} turned {2}")
    @CsvSource({ "fluff:14:25, decal/runway/centreline, 60", "fluff:40:5, decal/landing-pads/vtol-asphalt, -60",
          "fluff:71:27, decal/lettering/letter-a, -30", "fluff:91:2, decal/sport/tennis-court-green, -60" })
    void aOneHexArtTokenBecomesItsDecal(String terrain, String decal, double rotation) throws Exception {
        Board board = converted(terrain);
        Hex game = board.getHex(AT);
        assertEquals(Map.of(Terrains.FLUFF, BoardSceneryLayouts.Taken.OBJECTS), BoardSceneryLayouts.takenTokens(game));
        assertTrue(game.containsTerrain(Terrains.FLUFF), "The game keeps the token for its 2D board");
        var object = game.getDecorations().getFirst();
        assertEquals(1, game.getDecorations().size());
        assertEquals(decal, object.asset());
        assertEquals(rotation, object.rotation(), 1e-9);
        assertTrue(object.clipToHex());
        assertEquals(BoardDecoration.Colours.NONE, object.colours(), "Paint in the decal's own colour");
        Hex saved = written(board).getHex(AT);
        assertTrue(art(saved).isEmpty(), "A .board2 holds no art token");
        assertEquals(game.getDecorations(), saved.getDecorations());
    }

    @Test
    void blackLettersKeepTheirPaintColour() throws Exception {
        var letter = converted("fluff:70:1").getHex(AT).getDecorations().getFirst();
        assertEquals("decal/lettering/letter-a", letter.asset());
        assertEquals(BoardDecoration.Colours.of("#000000"), letter.colours());
        assertEquals("#ffffff", BoardDecalArt.footprint(letter.asset()).paint(), "One white image, painted in any colour");
    }

    @Test
    void aHelipadIsItsPadAndLightsInOneGroupWhereTheDataPlacesThemWhileACarParkStillFolds() throws Exception {
        var objects = converted("fluff:2:1").getHex(AT).getDecorations();
        var pad = objects.stream().filter(object -> object.kind().equals("decal")).toList();
        assertEquals(1, pad.size());
        assertEquals("decal/landing-pads/heli-pad", pad.getFirst().asset());
        assertEquals(0, pad.getFirst().x(), 1e-9);
        assertEquals(-.5 / HexTile.HEIGHT, pad.getFirst().y(), 1e-6, "Where the row places it, not folded as a car park");
        assertEquals(0, pad.getFirst().rotation(), 1e-9);
        assertFalse(pad.getFirst().mirror());
        assertEquals(6, objects.stream().filter(object -> object.asset().equals("scenery/roofs/landing-light")).count());
        assertEquals(1, objects.stream().map(BoardDecoration::group).distinct().count(), "One group");
        // A car park without a lane (2b) still stands its rows back to back.
        var rows = converted("fluff:5:98").getHex(AT).getDecorations().stream()
              .filter(object -> object.asset().startsWith("decal/car-park/")).toList();
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(BoardDecoration::mirror), rows.toString());
    }

    @ParameterizedTest(name = "{0} gets {1} = {2}")
    @CsvSource({ "ground_fluff:1:5, ground, ground/blend/desert", "road:1:09;road_fluff:1, road, road/rounded",
          "road:1:18;road_fluff:2, road, road/horizontal", "woods:1;fluff:12, vegetation, woods/orchard",
          "rough:1;fluff:1, rough, rough/obstacles", "rough:1;fluff:2, rough, rough/felled-trees" })
    void aTokenAHexDesignDrawsGetsThatDesign(String terrain, String owner, String variant) throws Exception {
        Board board = converted(terrain);
        Hex game = board.getHex(AT);
        assertEquals(variant, game.getAppearance().get(owner).variant());
        int token = java.util.Arrays.stream(ART).filter(game::containsTerrain).findFirst().orElseThrow();
        assertEquals(Map.of(token, BoardSceneryLayouts.Taken.DESIGN), BoardSceneryLayouts.takenTokens(game));
        assertTrue(game.containsTerrain(token), "The game keeps the token for its 2D board");
        assertTrue(game.getDecorations().isEmpty());
        Hex saved = written(board).getHex(AT);
        assertTrue(art(saved).isEmpty(), "A .board2 holds no art token");
        assertEquals(game.getAppearance(), saved.getAppearance());
        // The design draws what the token drew: the same drawn scenery layers and structure model.
        Hex legacy = game.duplicate();
        legacy.setAppearance(Map.of());
        assertEquals(drawn(legacy), drawn(saved));
        assertEquals(TilesetScenery.structureModels(legacy), TilesetScenery.structureModels(saved));
        assertEquals(HexTile.roughVariant(legacy), HexTile.roughVariant(saved));
    }

    /** The hex's scenery layers the 3D board draws (not skipped): file and kind, in drawing order. */
    private static List<String> drawn(Hex hex) {
        return TilesetScenery.resolve(hex, true).layers().stream().filter(layer -> layer.kind() != TilesetScenery.Kind.SKIPPED)
              .map(layer -> layer.file() + " " + layer.kind()).toList();
    }

    @Test
    void aBlendKeepsItsStrengthAndAUserDesignWinsOverItsToken() throws Exception {
        assertEquals(5 / 6.0, converted("ground_fluff:1:5").getHex(AT).getAppearance().get("ground").strength(), 1e-9);
        // The .board2's 2D board draws the blend's token art (the hook), so BoardView puts it under the shadow map too.
        Hex blend = written(converted("ground_fluff:1:5")).getHex(AT);
        assertFalse(blend.containsTerrain(Terrains.GROUND_FLUFF));
        assertTrue(BoardEditorBlueprint.get().artwork(blend).containsTerrain(Terrains.GROUND_FLUFF));
        Board board = Board.createEmptyBoard(5, 5);
        Hex picked = new Hex(0, "road:1:09;road_fluff:1", "");
        picked.setAppearance(Map.of("road", new HexAppearance("road/horizontal", null, null, null)));
        board.setHex(AT, picked);
        BoardSceneryLayouts.convert(board);
        Hex game = board.getHex(AT);
        assertEquals("road/horizontal", game.getAppearance().get("road").variant(), "The user's pick stays");
        assertEquals(Map.of(Terrains.ROAD_FLUFF, BoardSceneryLayouts.Taken.DESIGN), BoardSceneryLayouts.takenTokens(game));
        assertTrue(art(written(board).getHex(AT)).isEmpty());
    }

    @ParameterizedTest(name = "{0} is dropped")
    @ValueSource(strings = { "building:2;bldg_elev:1;bldg_cf:30;bldg_fluff:0", "woods:1;fluff:51", "ground_fluff:4",
          "pavement:1;road_fluff:1", "water:1;water_fluff:1" })
    void aTokenThatDrawsNothingIsDropped(String terrain) throws Exception {
        Board board = converted(terrain);
        Hex game = board.getHex(AT);
        assertTrue(game.getDecorations().isEmpty());
        assertTrue(game.getAppearance().isEmpty(), game.getAppearance().toString());
        assertTrue(BoardSceneryLayouts.takenTokens(game).values().stream().allMatch(BoardSceneryLayouts.Taken.DROP::equals));
        assertFalse(BoardSceneryLayouts.takenTokens(game).isEmpty());
        assertTrue(art(written(board).getHex(AT)).isEmpty());
    }

    /**
     * Quay art gets its quay design (batch-10 answer 1), on water and on pavement alike (Valencia), which only the 2D
     * board of a .board2 draws: the 3D views derive the fitted quay wall.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "water:1;fluff:100:3;pavement:1", "pavement:1;fluff:100:3" })
    void quayArtGetsItsQuayDesign(String terrain) throws Exception {
        Board board = converted(terrain);
        Hex game = board.getHex(AT);
        assertEquals(Map.of(Terrains.FLUFF, BoardSceneryLayouts.Taken.DESIGN), BoardSceneryLayouts.takenTokens(game));
        assertEquals(Map.of("ground", new HexAppearance("ground/quay-3", null, null, null)), game.getAppearance());
        Hex saved = written(board).getHex(AT);
        assertTrue(art(saved).isEmpty());
        assertEquals(game.getAppearance(), saved.getAppearance());
        assertTrue(drawn(saved).isEmpty(), "The 3D views draw no quay sprite: the fitted quay wall is derived");
        assertEquals("fluff:100:3", String.valueOf(BoardEditorBlueprint.get().artwork(saved).getTerrain(Terrains.FLUFF)),
              "The 2D board draws the quay art");
    }

    @ParameterizedTest(name = "{0} is kept")
    @ValueSource(strings = { "fluff:2001", "ground_fluff:2000", "solaris_elevator:3" })
    void serverStateAndRuleTerrainAreKept(String terrain) throws Exception {
        Board board = converted(terrain);
        assertTrue(BoardSceneryLayouts.takenTokens(board.getHex(AT)).isEmpty());
        assertEquals(new Hex(0, terrain, "").getTerrainTypes().length, written(board).getHex(AT).getTerrainTypes().length);
    }

    /** The red cross's pieces by direction from the set's centre (-1 the centre). */
    private static final Map<Integer, String> RED_CROSS = Map.of(-1, "fluff:62:10", 0, "fluff:62:7", 1, "fluff:62:9",
          2, "fluff:62:12", 3, "fluff:62:13", 4, "fluff:62:11", 5, "fluff:62:8");

    /** A 5x5 board with the red cross's pieces of {@code directions} around (2, 2), converted. */
    private static Board set(int... directions) {
        Board board = Board.createEmptyBoard(5, 5);
        for (int direction : directions) {
            board.setHex(direction < 0 ? AT : AT.translated(direction), new Hex(0, RED_CROSS.get(direction), ""));
        }
        assertTrue(BoardSceneryLayouts.convert(board).isEmpty(), "No issue");
        return board;
    }

    @Test
    void aCompleteSetIsOneEmblemAndASetMissingAPieceOnTheBoardPaintsExactlyItsPieces() {
        var whole = set(-1, 0, 1, 2, 3, 4, 5).getHex(AT).getDecorations();
        assertEquals(1, whole.size());
        assertFalse(whole.getFirst().clipToHex(), "One emblem spanning the centre's neighbours");

        // A lone centre piece: only its own hex of the emblem.
        var lone = set(-1).getHex(AT).getDecorations();
        assertEquals(1, lone.size());
        assertTrue(lone.getFirst().clipToHex());
        assertEquals(0, lone.getFirst().x(), 1e-9);
        assertEquals(0, lone.getFirst().y(), 1e-9);

        // A ring without its centre: six pieces, each the emblem on the empty centre, clipped to its hex, one group.
        Board ring = set(0, 1, 2, 3, 4, 5);
        assertTrue(ring.getHex(AT).getDecorations().isEmpty(), "The missing centre paints nothing");
        String group = null;
        for (int direction = 0; direction < 6; direction++) {
            Coords piece = AT.translated(direction);
            var objects = ring.getHex(piece).getDecorations();
            assertEquals(1, objects.size(), piece.toString());
            var emblem = objects.getFirst();
            assertEquals("decal/emblems/red-cross", emblem.asset());
            assertTrue(emblem.clipToHex());
            assertEquals((HexTile.centerX(AT, 1) - HexTile.centerX(piece, 1)), emblem.x(), 1e-6, "On the set's centre");
            assertEquals((HexTile.centerY(AT, 1) - HexTile.centerY(piece, 1)), emblem.y(), 1e-6);
            if (group == null) { group = emblem.group(); }
            assertEquals(group, emblem.group());
        }
        assertTrue(group != null && !group.isBlank());
    }

    @Test
    void theGmBuildingEditKeepsLegacyArtOnlyForThe2DBoard() {
        Hex hex = new Hex(0, "building:2;bldg_elev:1;bldg_cf:30", "");
        BoardSceneryLayouts.setBuildingArt(hex, 29);
        assertEquals(29, hex.terrainLevel(Terrains.BLDG_FLUFF), "The 2D board's token");
        assertNull(hex.getAppearance().get("building"), "Modern buildings use the gameplay weight/class kit");
        Hex saved = BoardSceneryLayouts.withoutTakenTokens(hex);
        assertFalse(saved.containsTerrain(Terrains.BLDG_FLUFF));
        assertEquals(0, BoardSceneryLayouts.buildingArt(saved));
        BoardSceneryLayouts.setBuildingArt(hex, 0);
        assertFalse(hex.containsTerrain(Terrains.BLDG_FLUFF));
        assertNull(hex.getAppearance().get("building"), "The ordinary artwork");
        assertEquals(0, BoardSceneryLayouts.buildingArt(hex));
    }
}
