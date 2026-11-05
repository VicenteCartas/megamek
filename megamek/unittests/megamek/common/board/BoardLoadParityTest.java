/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.postprocess.TWBoardTransformer;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Same file, same board on every load path: the game's sheet load ({@link BoardFile#loadSheet}, as the game start, the
 * lobby preview and scenarios call it), the file preview ({@link GpuBoardWindow#loadPreview}) and the 3D editor
 * ({@link BoardEditorSession#open}) share the conversion. Finished game and preview maps close outer building exits;
 * source sheets and editor documents retain them. The game and preview keep legacy tokens for their 2D views; the
 * editor holds none of the tokens the conversion took.
 */
class BoardLoadParityTest {
    /** Every legacy case of the conversion: car parks, emblems, bridges, seaport art, maglev and terrain-owned art. */
    private static final File FIXTURE = new File("testresources/megamek/common/board/load-parity.board");
    private static final String BIG_BOARD = "unofficial/VictorMorson/48x48";

    private static String text(Board board) throws IOException {
        var out = new ByteArrayOutputStream();
        BoardFile.write(board, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static Board finishedCopy(Board sheet) {
        Board board = BoardUtilities.resize(sheet, 0, 0, 0, 0);
        board.closeOffBoardBuildingExits();
        return board;
    }

    /** Loads {@code file} the three ways, checks they agree and that the board is converted, and returns the game's. */
    private static Board loadEveryWay(File file) throws Exception {
        Board game = BoardFile.loadSheet(file, false, true);
        Board preview = GpuBoardWindow.loadPreview(file);
        var session = new BoardEditorSession();
        session.open(file.toPath());
        String expected = text(game);
        assertEquals(text(finishedCopy(game)), text(preview), "The file preview shows the finished game board: " + file);
        assertEquals(expected, text(session.board()), "The editor opens the game's board: " + file);

        Board loaded = BoardFile.read(file.toPath());
        assertTrue(game.isNativeFormat(), "The native flag means converted");
        for (int y = 0; y < game.getHeight(); y++) {
            for (int x = 0; x < game.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex before = loaded.getHex(at), after = game.getHex(at);
                // A rail or maglev sprite becomes rail terrain, which draws it in the 2D views as well, with no objects.
                boolean railed = RailRoute.intact(after) || RailRoute.deckRail(after);
                for (int type : new int[] { Terrains.FLUFF, Terrains.ROAD_FLUFF }) {
                    Terrain token = before.getTerrain(type);
                    if (token != null && after.getTerrain(type) == null && railed) { continue; }
                    assertTrue(token == null || token.toString().equals(String.valueOf(after.getTerrain(type)))
                                && token.toString().equals(String.valueOf(preview.getHex(at).getTerrain(type))),
                          at + " keeps its token " + token + " in the game and the preview, for their 2D views");
                }
                // The editor's document is what its saved .board2 holds: the game's hex without its taken tokens.
                Hex edited = session.board().getHex(at);
                assertSame(edited, BoardSceneryLayouts.withoutTakenTokens(edited), at + ": the editor holds no taken token");
                assertEquals(BoardFile.hexNode(BoardSceneryLayouts.withoutTakenTokens(after)), BoardFile.hexNode(edited),
                      at + " is the game's hex without its taken tokens");
                if (after.containsTerrain(Terrains.BRIDGE)) {
                    assertNotNull(HexAppearance.bridgeBuilt(after.getAppearance()), at + " has a bridge type");
                }
                // A .board2 was converted when it was saved: its tokens are never decoded again.
                for (var layer : TilesetScenery.resolve(before, loaded.isNativeFormat()).layers()) {
                    if (!layer.taken() || loaded.isNativeFormat()) { continue; }
                    var piece = BoardDecalArt.legacy(layer.key());
                    if (piece != null && piece.set()) {
                        // A set piece's art is the one emblem on its set's centre.
                        Coords centre = piece.centre(at);
                        assertTrue(!game.contains(centre) || game.getHex(centre).getDecorations().stream()
                              .anyMatch(object -> object.asset().equals(piece.decal())), at + " has its emblem on " + centre);
                    } else {
                        assertTrue(!after.getDecorations().isEmpty() || HexAppearance.pillars(after.getAppearance())
                                    || railed,
                              at + " has the objects of " + layer.key());
                    }
                }
            }
        }
        BoardSceneryLayouts.convert(game);
        assertEquals(expected, text(game), "Converting a converted board changes nothing");
        return game;
    }

    @Test
    void theLegacyFixtureLoadsAsOneBoardEverywhere() throws Exception {
        Board board = loadEveryWay(FIXTURE);
        assertEquals(9, board.getHex(2, 0).getTerrain(Terrains.ROAD).getExits(), "A ground car park gets its lane");
        assertFalse(board.getHex(4, 0).containsTerrain(Terrains.ROAD), "A roof car park gets no road");
        assertFalse(board.getHex(6, 0).containsTerrain(Terrains.ROAD), "A 2b car park has no lane");
        assertTrue(board.getHex(4, 0).getDecorations().stream().anyMatch(object -> object.asset().startsWith("decal/car-park/")));
        assertEquals(1, board.getHex(3, 6).getDecorations().size(), "The 7-hex set is one emblem on its centre");
        for (Coords piece : List.of(new Coords(3, 5), new Coords(4, 6), new Coords(4, 7), new Coords(3, 7),
              new Coords(2, 7), new Coords(2, 6))) {
            assertTrue(board.getHex(piece).getDecorations().isEmpty(), "Set piece " + piece + " places nothing itself");
        }
        assertEquals(HexAppearance.PILLARS, board.getHex(7, 6).getAppearance().get("bridge"));
        assertEquals(HexAppearance.NATURAL_BRIDGE, board.getHex(9, 6).getAppearance().get("bridge"));
        assertEquals(HexAppearance.BUILT_BRIDGE, board.getHex(11, 1).getAppearance().get("bridge"));
        for (Coords owned : List.of(new Coords(2, 2), new Coords(4, 2), new Coords(6, 2), new Coords(0, 4),
              new Coords(10, 4))) {
            assertTrue(board.getHex(owned).getDecorations().isEmpty(), owned + " is terrain-owned art");
        }
        // The heli pad art: its pad decal and landing light, one group.
        assertEquals(List.of("scenery/roofs/landing-light", "decal/landing-pads/heli-pad"),
              board.getHex(8, 2).getDecorations().stream().map(BoardDecoration::asset).toList());
        // The park by the pool: its tables stand clear of the fitted bank on every path; the pool's coping stays on it.
        ConcreteFit fit = new ConcreteFit(board.getWidth(), board.getHeight(), ConcreteFit.cells(board), 1, true, java.util.Set.of());
        Coords park = new Coords(2, 9), pool = new Coords(1, 9);
        assertFalse(board.getHex(park).getDecorations().isEmpty());
        for (var object : board.getHex(park).getDecorations()) {
            assertFalse(fit.reaches(BoardModelBounds.footprint(park, object), park), object.id() + " stands clear of the bank");
        }
        var coping = board.getHex(pool).getDecorations().getFirst();
        assertTrue(fit.reaches(BoardModelBounds.footprint(pool, coping), pool), "The coping stays on the bank");
    }

    @Test
    void aConvertedFileOnlyGetsItsUntypedBridgesTyped(@TempDir Path folder) throws Exception {
        Board board = Board.createEmptyBoard(3, 3);
        board.setHex(new Coords(1, 0), new Hex(0, "road:1:09", ""));
        board.setHex(new Coords(1, 1), new Hex(0, "water:1;bridge:1:09;bridge_cf:40;bridge_elev:1", ""));
        board.setHex(new Coords(2, 2), new Hex(0, "fluff:93:8", ""));
        board.setNativeFormat(true);
        Path file = folder.resolve("Untyped.board2");
        BoardFile.save(board, file);
        Board loaded = loadEveryWay(file.toFile());
        assertEquals(HexAppearance.BUILT_BRIDGE, loaded.getHex(1, 1).getAppearance().get("bridge"), "A road leads onto it");
        assertTrue(loaded.getHex(2, 2).getDecorations().isEmpty(), "A native board's tokens are never decoded");
    }

    @Test
    void theRoofAndGroundCarParksOf48x48LoadAsOneBoardEverywhere() throws Exception {
        File file = new File(Configuration.boardsDir(), BIG_BOARD + ".board");
        Board board = loadEveryWay(file);
        for (Coords carPark : List.of(new Coords(7, 0), new Coords(41, 10))) {
            assertTrue(board.getHex(carPark).getDecorations().stream().anyMatch(object -> object.asset().startsWith("decal/car-park/")),
                  carPark + " has its bays");
        }
    }

    /** A save made before game boards were converted holds the legacy board: loading it converts it as a sheet load does. */
    @Test
    void anOldSavesBoardIsConvertedWhenTheSaveLoads() throws Exception {
        Game game = new Game();
        game.setBoard(BoardFile.read(FIXTURE.toPath()));
        game.initializeAfterLoad();
        assertEquals(text(finishedCopy(BoardFile.loadSheet(FIXTURE, false, true))), text(game.getBoard()));
    }

    @Test
    void previewClosesEdgesButEditingAndSavingKeepTheSourceConnections(@TempDir Path folder) throws Exception {
        Board sheet = Board.createEmptyBoard(1, 1);
        sheet.setHex(0, 0, new Hex(0, "building:1:63;bldg_cf:15;bldg_elev:2", ""));
        sheet.setNativeFormat(true);
        Path file = folder.resolve("Edge.board2");
        BoardFile.save(sheet, file);
        String original = Files.readString(file);

        Board preview = GpuBoardWindow.loadPreview(file.toFile());
        assertEquals(0, preview.getHex(0, 0).getTerrain(Terrains.BUILDING).getExits());
        var editor = new BoardEditorSession();
        editor.open(preview, file);
        assertEquals(63, editor.board().getHex(0, 0).getTerrain(Terrains.BUILDING).getExits());
        Path saved = folder.resolve("Edited.board2");
        editor.save(saved);
        assertEquals(63, BoardFile.read(saved).getHex(0, 0).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(original, Files.readString(file));

        Game restored = new Game();
        restored.setBoard(BoardFile.read(file));
        var building = restored.getBoard().getBuildingAt(new Coords(0, 0));
        building.setCurrentCF(7, new Coords(0, 0));
        restored.initializeAfterLoad();
        assertEquals(0, restored.getBoard().getHex(0, 0).getTerrain(Terrains.BUILDING).getExits());
        assertSame(building, restored.getBoard().getBuildingAt(new Coords(0, 0)));
        assertEquals(7, building.getCurrentCF(new Coords(0, 0)), "Closing outer exits does not reset damage");
    }

    /** The game converts a sheet before turning it, so its car-park lanes turn with it, as the editor's flip does. */
    @Test
    void aRotatedSheetIsTheTurnedEditorImport() throws Exception {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(48, 48);
        settings.setMapSize(1, 1);
        settings.setBoardsSelectedVector(List.of(Board.BOARD_REQUEST_ROTATION + BIG_BOARD));
        Board game = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());

        var session = new BoardEditorSession();
        session.open(new File(Configuration.boardsDir(), BIG_BOARD + ".board").toPath());
        Board turned = session.board();
        BoardUtilities.flip(turned, true, true);
        assertEquals(text(finishedCopy(turned)), text(game));
    }
}
