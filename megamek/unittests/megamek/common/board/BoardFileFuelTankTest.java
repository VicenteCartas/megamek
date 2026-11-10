/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardFileFuelTankTest {
    @ParameterizedTest
    @ValueSource(strings = { "building/tank-farm", "fuel-tank/light", "asset" })
    void oldTankDesignsKeepTheirStoredBuildingTerrainAndVisuals(String design) throws Exception {
        Board board = Board.createEmptyBoard(1, 3);
        board.setNativeFormat(true);
        Coords first = new Coords(0, 0), second = new Coords(0, 1), house = new Coords(0, 2);
        Hex light = new Hex(2, "building:1:8;bldg_cf:15;bldg_elev:3;bldg_basement_type:1;bldg_armor:10", "grass");
        Hex medium = new Hex(2, "building:2:1;bldg_cf:40;bldg_elev:2", "grass");
        HexAppearance appearance = design.equals("asset")
              ? new HexAppearance(null, "buildings/fuel_tanks/fuel_tank_light", null, null)
              : new HexAppearance(design, null, null, null);
        light.setAppearance(Map.of("building", appearance));
        medium.setAppearance(Map.of("building", new HexAppearance("building/tank-farm", null, null, null)));
        var roofObject = new BoardDecoration("on-tank", "prop", "scenery/parks/picnic-table", null,
              .1, -.2, 90, false, .5, BoardDecoration.Placement.surface("building", "roof", .25), 0);
        light.setDecorations(List.of(roofObject));
        board.setHex(first, light); board.setHex(second, medium);
        board.setHex(house, new Hex(0, "building:2:0;bldg_cf:40;bldg_elev:2", ""));
        assertFalse(board.isValid(), "The old catalog produced the reported building-type warning");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BoardFile.write(board, output);
        String text = output.toString(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> BoardFile.readNative(text), "Strict loading still reports the stored rules error");
        Board loaded = BoardFile.readNative(text, true);
        assertFalse(loaded.isValid(), "The editor loads the error without correcting it");
        for (Coords at : List.of(first, second, house)) {
            assertEquals(BoardFile.hexNode(board.getHex(at)), BoardFile.hexNode(loaded.getHex(at)),
                  "Loading preserves terrain, appearance and object placements");
        }
        assertEquals(Map.of(Terrains.BUILDING, "buildings/fuel_tanks/fuel_tank_light"),
              TilesetScenery.structureModels(loaded.getHex(first)), "The stored tank artwork still resolves on building terrain");
        output.reset();
        BoardFile.write(loaded, output);
        assertEquals(text, output.toString(StandardCharsets.UTF_8), "Saving does not introduce a correction either");
    }
}
