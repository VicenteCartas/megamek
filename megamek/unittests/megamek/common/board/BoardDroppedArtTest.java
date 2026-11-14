/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.client.ui.clientGUI.boardview.gpu.BoardSurfaceBlend;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Art without a 3D version ({@link BoardSceneryLayouts.Taken#UNTRANSLATED}): a legacy art token whose tileset art is a
 * flat image, with no layout, legacy decal or hex design, is dropped from what the 3D views and a {@code .board2} hold
 * and is reported ({@link BoardSceneryLayouts#droppedArt}); a ground transition is its ground blend. The shipped tileset
 * names no such image, so a temporary one gives a FLUFF value flat art.
 */
class BoardDroppedArtTest {
    private static final Coords FLAT = new Coords(1, 2), BLEND = new Coords(3, 2);

    @Test
    void artWithoutA3DVersionIsDroppedAndReportedAndAGroundTransitionIsNot(@TempDir Path root) throws Exception {
        // Layouts, decals and designs stay the shipped ones: each is read once, before the data directory changes.
        BoardSceneryLayouts.hasLayout("");
        BoardDecalArt.legacy("");
        BoardEditorBlueprint.get();
        File previous = Configuration.dataDir();
        try {
            Path tileset = root.resolve("models/board/classic/tileset/classic.tileset");
            Files.createDirectories(tileset.getParent());
            Files.writeString(tileset, "super * \"fluff:7\" \"\" \"old/flat_art.png\"\n"
                  + "super * \"ground_fluff:2:03\" \"\" \"terrain/transitions/toGrass_60.png\"\n"
                  + "super * \"fluff:*\" \"\" \"tileset/blank.png\"\n"
                  + "super * \"ground_fluff:*\" \"\" \"tileset/blank.png\"\n");
            Configuration.setDataDir(root.toFile());
            TilesetScenery.reload();
            Board board = Board.createEmptyBoard(5, 5);
            board.setHex(FLAT, new Hex(0, "fluff:7", ""));
            board.setHex(BLEND, new Hex(0, "ground_fluff:2:3", ""));
            BoardSceneryLayouts.convert(board);

            Hex flat = board.getHex(FLAT);
            assertEquals(Map.of(Terrains.FLUFF, BoardSceneryLayouts.Taken.UNTRANSLATED),
                  BoardSceneryLayouts.takenTokens(flat));
            assertTrue(flat.containsTerrain(Terrains.FLUFF),
                  "The game keeps the token: its 2D board draws it, each client reports it");
            assertTrue(flat.getDecorations().isEmpty() && flat.getAppearance().isEmpty(), "Nothing stands for it");
            assertFalse(BoardSceneryLayouts.withoutTakenTokens(flat).containsTerrain(Terrains.FLUFF),
                  "No 3D view holds it");
            assertEquals(List.of(new BoardSceneryLayouts.DroppedArt(FLAT, "fluff:7", "old/flat_art.png")),
                  BoardSceneryLayouts.droppedArt(board), "Only the flat art is reported");

            Hex blend = board.getHex(BLEND);
            assertEquals(Map.of(Terrains.GROUND_FLUFF, BoardSceneryLayouts.Taken.DESIGN),
                  BoardSceneryLayouts.takenTokens(blend));
            assertTrue(BoardSurfaceBlend.hasTransition(BoardSceneryLayouts.withoutTakenTokens(blend)),
                  "The native ground draws it");

            var text = new ByteArrayOutputStream();
            BoardFile.write(board, text);
            Board saved = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
            assertFalse(saved.getHex(FLAT).containsTerrain(Terrains.FLUFF), "A .board2 holds no art token");
            assertTrue(BoardSceneryLayouts.droppedArt(saved).isEmpty(), "Nothing is left to report");
            assertTrue(BoardSurfaceBlend.hasTransition(saved.getHex(BLEND)), "The blend is saved as its design");
        } finally {
            Configuration.setDataDir(previous);
            TilesetScenery.reload();
        }
    }
}
