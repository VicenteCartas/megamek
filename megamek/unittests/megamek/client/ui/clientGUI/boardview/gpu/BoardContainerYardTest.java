/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.ui.tileset.HexTileset;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Container yards are a look of the woods cover (b12): the conversion turns the legacy yard and crane art into the
 * look and its rotation, a crane's boom over the next hex included; every 3D view draws the look's rows for the hex's
 * cover level as objects the woods own; the 2D board of a {@code .board2} draws the legacy sprite of the rotation.
 */
class BoardContainerYardTest {
    private static final String ROWS = "woods/container-yard/rows", CRANE = "woods/container-yard/quay-crane";
    private static final String BOOM = "scenery/seaport/yard/crane-boom";
    /**
     * The legacy art's crane headings (0-5 = N, NE, SE, S, SW, NW), as GpuScenerySmokeTest states them: the four row
     * families of fluff:81 (N-S, NE-SW, NW-SE, E-W) each hold four groups of three cranes.
     */
    private static final int[][] HEADINGS = { { 1, 2, 4, 5 }, { 2, 3, 5, 0 }, { 0, 1, 3, 4 }, { 1, 2, 4, 5 } };

    private static Board pavement(int width, int height) {
        Board board = Board.createEmptyBoard(width, height);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
        }
        return board;
    }

    /** The drawn rows the woods own on {@code hex}, as every 3D view captures them. */
    private static List<BoardScene.Feature> skin(Hex hex, Coords at) {
        return BoardFeatures.decorations(hex, at).stream().filter(f -> BoardFeatures.terrainOwned(f.decoration())).toList();
    }

    private static Hex yard(String terrain, String look, Integer rotation) {
        Hex hex = new Hex(0, terrain, "");
        hex.setAppearance(Map.of("vegetation", new HexAppearance(look, null, null, null, BoardDecoration.Colours.NONE, rotation)));
        return hex;
    }

    @Test
    void eachConvertedCraneBoomsOverTheNeighbourOfItsLegacyHeading() {
        Board board = pavement(20, 26);
        for (int i = 0; i < 48; i++) {
            board.setHex(new Coords(2 + i % 6 * 3, 2 + i / 6 * 3), new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:81:" + (i + 1), ""));
        }
        BoardSceneryLayouts.convert(board);
        for (int i = 0; i < 48; i++) {
            Coords base = new Coords(2 + i % 6 * 3, 2 + i / 6 * 3), over = base.translated(HEADINGS[i / 12][i % 12 / 3]);
            Hex crane = board.getHex(base);
            assertTrue(crane.getDecorations().isEmpty(), "81:" + (i + 1));
            var rows = skin(crane, base);
            var boom = rows.stream().filter(f -> f.asset().equals(BOOM)).findFirst().orElseThrow();
            // The boom segment lies over the neighbour's centre (authored on the 2D art's hex: within a pixel).
            float dx = HexTile.centerX(over, HexTile.MODEL_WIDTH) - HexTile.centerX(base, HexTile.MODEL_WIDTH);
            float dy = HexTile.centerY(over, HexTile.HEIGHT) - HexTile.centerY(base, HexTile.HEIGHT);
            assertEquals(0, Math.hypot(boom.x() - dx, boom.y() - dy), 1, "81:" + (i + 1) + " booms over " + over);
            // The segment and the crane turn alike, along the boom: a turn's sign error would point them elsewhere.
            var mast = rows.stream().filter(f -> f.asset().equals("scenery/seaport/yard/quay-crane")).findFirst().orElseThrow();
            assertEquals(boom.decoration().rotation(), mast.decoration().rotation(), 1e-3);
            double bearing = Math.toDegrees(Math.atan2(boom.x(), boom.y()));
            assertEquals(0, Math.floorMod(Math.round(bearing + boom.decoration().rotation()), 360), "81:" + (i + 1));
        }
    }

    @Test
    void aSheetLoadedTurnedTurnsEachCraneLookWithItsBoomOverTheTurnedNeighbour(@TempDir Path directory) throws Exception {
        // The 48 cranes in a legacy file that a game loads turned (BoardFile.loadSheet: conversion, then the flip); the
        // first crane's tip, which its boom reaches, and a row yard in its own colours (80:05).
        Board board = pavement(20, 26);
        for (int i = 0; i < 48; i++) {
            board.setHex(new Coords(2 + i % 6 * 3, 2 + i / 6 * 3), new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:81:" + (i + 1), ""));
        }
        Coords tip = new Coords(2, 2).translated(HEADINGS[0][0]), rows = new Coords(1, 1);
        board.setHex(tip, new Hex(0, "pavement:1;fluff:82:" + (HEADINGS[0][0] + 1), ""));
        board.setHex(rows, new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:80:05", ""));
        File file = directory.resolve("cranes.board").toFile();
        try (var out = new FileOutputStream(file)) { board.saveLegacy(out, false); }
        Board turned = BoardFile.loadSheet(file, true, true);
        var blueprint = BoardEditorBlueprint.get();
        for (int i = 0; i < 48; i++) {
            Coords base = turned(new Coords(2 + i % 6 * 3, 2 + i / 6 * 3)), over = base.translated((HEADINGS[i / 12][i % 12 / 3] + 3) % 6);
            Hex crane = turned.getHex(base);
            var boom = skin(crane, base).stream().filter(f -> f.asset().equals(BOOM)).findFirst().orElseThrow();
            float dx = HexTile.centerX(over, HexTile.MODEL_WIDTH) - HexTile.centerX(base, HexTile.MODEL_WIDTH);
            float dy = HexTile.centerY(over, HexTile.HEIGHT) - HexTile.centerY(base, HexTile.HEIGHT);
            assertEquals(0, Math.hypot(boom.x() - dx, boom.y() - dy), 1, "81:" + (i + 1) + " booms over " + over);
            // The 2D board draws the legacy sprite of the turned look.
            assertEquals(crane.getAppearance().get("vegetation"), blueprint.reproducing(crane, blueprint.artwork(crane).getTerrain(Terrains.FLUFF)));
        }
        assertEquals(turned(tip), turned(new Coords(2, 2)).translated((HEADINGS[0][0] + 3) % 6));
        assertTrue(turned.getHex(turned(tip)).getDecorations().isEmpty(), "The reached tip stays drawn by the turned boom");
        // Rows read alike turned round: the 2D board keeps the hex's own token.
        assertEquals(5, blueprint.artwork(turned.getHex(turned(rows))).getTerrain(Terrains.FLUFF).getExits());
    }

    /** Where a 180-degree turn of the 20 x 26 crane sheet moves {@code at}. */
    private static Coords turned(Coords at) { return new Coords(19 - at.getX(), 25 - at.getY()); }

    @Test
    void aFlipAlongOneAxisShowsTheCraneOfTheOtherHandWithItsBoomMirrored() {
        String light = "pavement:1;woods:1;foliage_elev:2";
        for (String look : List.of(CRANE, CRANE + "-mirrored")) {
            for (int rotation = 0; rotation < 360; rotation += 30) {
                for (boolean[] axes : new boolean[][] { { true, false }, { false, true }, { true, true } }) {
                    Board board = pavement(4, 4);
                    Coords at = new Coords(1, 1), to = new Coords(axes[0] ? 2 : 1, axes[1] ? 2 : 1);
                    board.setHex(at, yard(light, look, rotation));
                    var before = skin(board.getHex(at), at).stream().filter(f -> f.asset().equals(BOOM)).findFirst().orElseThrow();
                    BoardUtilities.flip(board, axes[0], axes[1]);
                    String label = look + " @" + rotation + " flipped " + axes[0] + "/" + axes[1];
                    Hex flipped = board.getHex(to);
                    assertEquals(axes[0] != axes[1], !flipped.getAppearance().get("vegetation").variant().equals(look), label);
                    var after = skin(flipped, to).stream().filter(f -> f.asset().equals(BOOM)).findFirst().orElseThrow();
                    assertEquals(axes[0] ? -before.x() : before.x(), after.x(), 1e-3, label);
                    assertEquals(axes[1] ? -before.y() : before.y(), after.y(), 1e-3, label);
                }
            }
        }
    }

    @Test
    void theFourLegacyRowAxesAreFourTurnsOfOneLayout() {
        // fluff:80:01, 08, 16 and 23 are the legacy rows N-S, NE-SW, NW-SE and E-W: axes 0, 60, 120 and 90 degrees.
        Map<Integer, Integer> axes = Map.of(1, 0, 8, 60, 16, 120, 23, 90);
        Set<Long> seen = new HashSet<>();
        for (var axis : axes.entrySet()) {
            Board board = pavement(3, 3);
            Coords at = new Coords(1, 1);
            board.setHex(at, new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:80:" + axis.getKey(), ""));
            BoardSceneryLayouts.convert(board);
            var containers = skin(board.getHex(at), at).stream()
                  .filter(f -> f.asset().equals("scenery/seaport/yard/light-rows")).findFirst().orElseThrow();
            long turn = Math.floorMod(Math.round(-containers.decoration().rotation()), 180);
            assertEquals((long) axis.getValue(), turn, "80:" + axis.getKey());
            seen.add(turn);
        }
        assertEquals(4, seen.size());
    }

    @Test
    void aConvertedYardIsItsLookWithItsRotationAndRoundTripsWithoutItsToken() throws Exception {
        Board board = pavement(3, 3);
        Coords at = new Coords(1, 1);
        board.setHex(at, new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:80:13", ""));
        BoardSceneryLayouts.convert(board);
        Hex yard = board.getHex(at);
        assertTrue(yard.getDecorations().isEmpty(), "No object stands for the yard");
        assertEquals(new HexAppearance(ROWS, null, null, null, BoardDecoration.Colours.NONE, 60), yard.getAppearance().get("vegetation"));
        assertTrue(yard.containsTerrain(Terrains.FLUFF), "The game keeps the token for its 2D board");
        assertEquals(Map.of(Terrains.FLUFF, BoardSceneryLayouts.Taken.DESIGN), BoardSceneryLayouts.takenTokens(yard));
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        String saved = text.toString(StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"appearance\":{\"variant\":\"" + ROWS + "\",\"rotation\":60}"), saved);
        assertFalse(saved.contains("fluff"), "The .board2 holds the look, not the token");
        assertFalse(saved.contains(BoardTreeDistribution.NO_TREES));
        Board reread = BoardFile.readNative(saved);
        assertEquals(BoardFile.hexNode(BoardSceneryLayouts.withoutTakenTokens(yard)), BoardFile.hexNode(reread.getHex(at)));
        assertEquals(skin(BoardSceneryLayouts.withoutTakenTokens(yard), at), skin(reread.getHex(at), at));
    }

    @Test
    void aTipTheConvertedBoomReachesGoesAndOtherTipsBecomeBoomSegmentsAlongTheirLine() {
        // 81:13 booms SE (direction 2); tip n points along direction n - 1.
        Board board = pavement(9, 9);
        Coords crane = new Coords(2, 2), reached = crane.translated(2), beyond = reached.translated(2), orphan = new Coords(7, 1);
        board.setHex(crane, new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:81:13", ""));
        board.setHex(reached, new Hex(0, "pavement:1;fluff:82:3", ""));
        board.setHex(beyond, new Hex(0, "pavement:1;fluff:82:3", ""));
        board.setHex(orphan, new Hex(0, "pavement:1;fluff:82:1", ""));
        BoardSceneryLayouts.convert(board);
        assertTrue(board.getHex(reached).getDecorations().isEmpty(), "The crane's skin draws its boom");
        assertEquals(Map.of(Terrains.FLUFF, BoardSceneryLayouts.Taken.OBJECTS), BoardSceneryLayouts.takenTokens(board.getHex(reached)));
        assertFalse(BoardSceneryLayouts.withoutTakenTokens(board.getHex(reached)).containsTerrain(Terrains.FLUFF));
        for (var tip : Map.of(beyond, 120, orphan, 0).entrySet()) {
            var objects = board.getHex(tip.getKey()).getDecorations();
            assertEquals(List.of(BOOM), objects.stream().map(BoardDecoration::asset).toList(), tip.getKey().toString());
            assertEquals((long) tip.getValue(), Math.floorMod(Math.round(-objects.getFirst().rotation()), 180), "along the tip's line");
        }
        // The same tip typed beside a crane look on a converted board stays an object: a .board2 is taken as it is.
        Board typed = pavement(9, 9);
        typed.setNativeFormat(true);
        typed.setHex(crane, yard("pavement:1;woods:1;foliage_elev:2", CRANE, 60));
        typed.setHex(reached, new Hex(0, "pavement:1;fluff:82:3", ""));
        BoardSceneryLayouts.convert(typed, List.of(reached));
        assertEquals(List.of(BOOM), typed.getHex(reached).getDecorations().stream().map(BoardDecoration::asset).toList());
    }

    @Test
    void theCoverLevelPicksTheLayoutAndClearedWoodsDrawNothing() {
        Coords at = new Coords(1, 1);
        Map<String, String> covers = Map.of("woods:1;foliage_elev:1", "foliage-rows", "woods:1;foliage_elev:2", "light-rows",
              "woods:2;foliage_elev:2", "heavy-rows", "woods:3;foliage_elev:3", "ultra-rows", "jungle:2;foliage_elev:2", "heavy-rows");
        for (var cover : covers.entrySet()) {
            Hex hex = yard("pavement:1;" + cover.getKey(), ROWS, null);
            var assets = skin(hex, at).stream().map(BoardScene.Feature::asset).toList();
            assertEquals("scenery/seaport/yard/" + cover.getValue(), assets.getFirst(), cover.getKey());
            assertTrue(BoardFeatures.capture(hex, at, Map.of()).stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.TREE));
        }
        // A look without art at a level draws its Rows (data), the heavy crane yard so; a heavy yard burnt down a level
        // (TWGameManager.tryClearHex) draws the light layout, a cleared one (rough, no woods) nothing.
        assertEquals("scenery/seaport/yard/heavy-rows", skin(yard("woods:2;foliage_elev:2", CRANE, 60), at).getFirst().asset());
        Hex burnt = yard("woods:2;foliage_elev:2", CRANE, 60);
        burnt.addTerrain(new megamek.common.units.Terrain(Terrains.WOODS, 1));
        assertTrue(skin(burnt, at).stream().anyMatch(f -> f.asset().equals("scenery/seaport/yard/light-quay-crane")));
        Hex cleared = yard("rough:1", CRANE, 60);
        assertNull(BoardSceneryLayouts.skin(cleared));
        assertTrue(BoardFeatures.capture(cleared, at, Map.of()).stream()
              .noneMatch(f -> f.decoration() != null || f.kind() == BoardScene.FeatureKind.TREE));
        // The look stays on the hex, dormant: woods grown back draw it again.
        assertNotNull(BoardSceneryLayouts.skin(yard("woods:1;foliage_elev:2", CRANE, 60)));
    }

    @Test
    void theTwoDBoardDrawsTheLegacySpriteOfTheRotation() throws Exception {
        Game game = new Game();
        var tileset = new HexTileset(game);
        try {
            tileset.loadFromFile("saxarba.tileset");
            String light = "woods:1;foliage_elev:2", heavy = "woods:2;foliage_elev:2";
            var blueprint = BoardEditorBlueprint.get();
            // The rows' axes: 60 and 240 are one (the NE-SW legacy rows, 80:08), an odd step takes the nearer axis; a
            // crane keeps its exact heading; a look without legacy art draws the Rows' sprite.
            for (var expected : Map.of(yard(light, ROWS, 60), light + ";fluff:80:08", yard(light, ROWS, 240), light + ";fluff:80:08",
                  yard(light, ROWS, 270), light + ";fluff:80:23", yard(light, ROWS, 30), light + ";fluff:80:01",
                  yard(light, CRANE, 240), light + ";fluff:81:19", yard(light, CRANE, 60), light + ";fluff:81:13",
                  yard(light, "woods/container-yard/yard-gantry", null), light + ";fluff:80:01").entrySet()) {
                assertEquals(tileset.assignMatch(new Hex(0, expected.getValue(), ""))[1], tileset.assignMatch(expected.getKey())[1],
                      expected.getValue());
            }
            // Only light (and foliage) woods have yard sprites: heavy woods draw their own woods sprite.
            List<?> heavyYard = (List<?>) tileset.assignMatch(yard(heavy, ROWS, null))[1];
            assertTrue(heavyYard.containsAll((List<?>) tileset.assignMatch(new Hex(0, heavy, ""))[1]));
            assertFalse(heavyYard.containsAll((List<?>) tileset.assignMatch(new Hex(0, light + ";fluff:80:01", ""))[1]));
            // A game of a loaded .board keeps its own token (80:05: its own colours).
            Board board = pavement(3, 3);
            Coords at = new Coords(1, 1);
            board.setHex(at, new Hex(0, light + ";fluff:80:05", ""));
            BoardSceneryLayouts.convert(board);
            Hex legacy = board.getHex(at);
            assertEquals(5, blueprint.artwork(legacy).getTerrain(Terrains.FLUFF).getExits());
            assertEquals(tileset.assignMatch(new Hex(0, light + ";fluff:80:05", ""))[1], tileset.assignMatch(legacy)[1]);
        } finally {
            tileset.close();
        }
    }
}
