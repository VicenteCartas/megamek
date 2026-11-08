/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * A game shows the board exactly as the 3D preview and the editor do: one converted board, loaded the game's, the file
 * preview's and the editor's way, gives the same terrain tiles in all three sources, and only the game adds its tactical
 * markings. The game and the preview keep the legacy tokens for their 2D views, the editor holds none, and the 3D board
 * draws none of them: the conversion's objects stand for them.
 */
class GpuSourceParityTest {
    /** The step-2 legacy fixture: car parks, emblems, bridges, seaport art, maglev and terrain-owned art. */
    private static final File FIXTURE = new File("testresources/megamek/common/board/load-parity.board");
    private static final Coords CAR_PARK = new Coords(0, 0), GEYSER = new Coords(2, 2), HELIPAD = new Coords(8, 2),
          STYLED_ROAD = new Coords(5, 3), GROUND_TRANSITION = new Coords(1, 3), PAVED_RUBBLE = new Coords(3, 3);
    /** The art the conversion never takes, which the 3D board still draws as rows: geysers, rubble, fortified, paths. */
    private static final List<String> TERRAIN_OWNED = List.of("misc/geyser_water_off", "misc/geyser_water_on",
          "misc/geyser_magma", "misc/fortified", "misc/rubble_light", "misc/rubble_medium", "misc/rubble_heavy",
          "misc/rubble_hardened", "misc/rubble_wall", "rubble_light_path", "rubble_medium_path", "rubble_heavy_path",
          "rubble_hardened_path", "rubble_wall_path");

    @Test
    void gamePreviewAndEditorCaptureTheSameTiles() throws Exception {
        Board game = BoardFile.loadSheet(FIXTURE, false, true);
        Board preview = GpuBoardWindow.loadPreview(FIXTURE);
        var editor = new BoardEditorSession();
        editor.open(FIXTURE.toPath());
        for (Board board : List.of(game, preview, editor.board())) { addCases(board); }
        assertTrue(game.getHex(CAR_PARK).containsTerrain(Terrains.FLUFF), "The game keeps the token for its 2D board");
        assertFalse(editor.board().getHex(CAR_PARK).containsTerrain(Terrains.FLUFF), "The editor holds no taken token");

        List<BoardScene.Tile> gameTiles;
        try (var fixture = GpuBoardFixture.create(game)) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            gameTiles = fixture.source.takeFrame().scene().tiles();
        }
        var task = new FutureTask<>(() -> {
            var map = new Game();
            map.setBoard(preview);
            List<BoardScene.Tile> previewTiles, editorTiles;
            try (var source = new GpuMapSource(map, null, null)) { previewTiles = source.takeFrame().scene().tiles(); }
            try (var source = new GpuMapSource(editor.game(), null, editor)) { editorTiles = source.takeFrame().scene().tiles(); }
            return List.of(previewTiles, editorTiles);
        });
        SwingUtilities.invokeAndWait(task);
        List<BoardScene.Tile> previewTiles = task.get().get(0), editorTiles = task.get().get(1);

        assertEquals(gameTiles.size(), previewTiles.size());
        assertEquals(gameTiles.size(), editorTiles.size());
        for (int i = 0; i < gameTiles.size(); i++) {
            var expected = gameTiles.get(i).withText(List.of());
            String hex = "Hex " + expected.coords().getBoardNum();
            assertEquals(expected, previewTiles.get(i).withText(List.of()), hex + ": preview");
            assertEquals(expected, editorTiles.get(i).withText(List.of()), hex + ": editor");
        }

        Set<String> rows = TERRAIN_OWNED.stream().flatMap(key -> BoardSceneryLayouts.layout("scenery/saxarba/" + key)
              .components().stream()).map(BoardSceneryLayouts.Component::asset).collect(Collectors.toSet());
        for (var tile : gameTiles) {
            Hex hex = game.getHex(tile.coords());
            assertTrue(tile.roadExits() == 0 || hex.containsTerrain(Terrains.ROAD), tile.coords() + ": no road without ROAD");
            for (var feature : tile.features()) {
                assertTrue(feature.kind() != BoardScene.FeatureKind.SCENERY || rows.contains(feature.asset().replaceFirst("-b$", "")),
                      tile.coords() + ": only terrain-owned art draws as rows, not " + feature.asset());
            }
            if (hex.containsTerrain(Terrains.BRIDGE)) {
                assertNotNull(HexAppearance.bridgeBuilt(tile.appearance()), tile.coords() + ": every bridge is typed");
            }
        }
        assertTrue(tile(game, gameTiles, GEYSER).features().stream().anyMatch(feature -> feature.asset().contains("geyser")),
              "The terrain-owned geyser model reaches the game");
        var parked = tile(game, gameTiles, CAR_PARK).features().stream().filter(f -> f.decoration() != null)
              .map(f -> f.decoration().asset()).toList();
        assertTrue(parked.stream().anyMatch(asset -> asset.startsWith("decal/car-park/")), "The car park has its decal");
        assertTrue(parked.contains("scenery/vehicles/car"), "The car park has its cars");
        // Batch 10: the helipad art is its pad decal and landing light, in both views; no sprite is painted.
        assertTrue(tile(game, gameTiles, HELIPAD).features().stream().anyMatch(f -> f.decoration() != null
              && f.decoration().asset().equals("decal/landing-pads/heli-pad")), "The helipad is its decal");
        assertEquals(null, tile(game, gameTiles, HELIPAD).tilesetDecals(), "The Tactical View paints no helipad sprite");
        assertEquals("gravel", tile(game, gameTiles, STYLED_ROAD).appearance().get("road").material(),
              "The authored road material survives the game's tactical repaint");
    }

    /** Tiles are in column order. */
    private static BoardScene.Tile tile(Board board, List<BoardScene.Tile> tiles, Coords at) {
        return tiles.get(at.getX() * board.getHeight() + at.getY());
    }

    /**
     * The same on every board: one authored road material, and the two cases of the two-source test this one extends
     * that the fixture lacks, a ground transition and rubble on pavement.
     */
    private static void addCases(Board board) {
        Hex road = new Hex(0, "road:4:9", "", STYLED_ROAD);
        road.setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "gravel", null)));
        board.setHex(STYLED_ROAD, road);
        board.setHex(GROUND_TRANSITION, new Hex(0, "ground_fluff:2:1", "", GROUND_TRANSITION));
        board.setHex(PAVED_RUBBLE, new Hex(0, "rubble:3;pavement:1", "", PAVED_RUBBLE));
    }
}
