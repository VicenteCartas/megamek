/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardBuildingImportTest {
    private static final Coords AT = new Coords(1, 1);

    private static Board convert(String terrain, String theme) {
        Board board = Board.createEmptyBoard(3, 3);
        board.setHex(AT, new Hex(0, terrain, theme));
        BoardSceneryLayouts.convert(board);
        return board;
    }

    private static Board roundTrip(Board board) throws Exception {
        var output = new ByteArrayOutputStream();
        BoardFile.write(board, output);
        return BoardFile.readNative(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void legacyRoofChoicesUseTheWeightAndClassKitsThroughNativeSaveAndReload() throws Exception {
        int[] cf = { 15, 40, 90, 120 };
        for (int weight = 1; weight <= 4; weight++) {
            for (int buildingClass = 0; buildingClass <= 3; buildingClass++) {
                for (int style = 0; style <= 55; style++) {
                    int legacyArt = style == 55 ? 100 : style;
                    String terrain = "building:" + weight + ":0;bldg_class:" + buildingClass
                          + ";bldg_cf:" + cf[weight - 1] + ";bldg_elev:2;bldg_basement_type:1"
                          + (style == 0 ? "" : ";bldg_fluff:" + legacyArt);
                    Board imported = convert(terrain, "");
                    Hex after = roundTrip(imported).getHex(AT);
                    String expected = TilesetScenery.genericBuilding(after);
                    assertEquals(expected, TilesetScenery.structureModels(imported.getHex(AT)).get(Terrains.BUILDING), terrain);
                    assertEquals(expected, TilesetScenery.structureModels(after).get(Terrains.BUILDING), terrain);
                    assertTrue(TilesetScenery.customBuildingFile(expected).isFile(), expected);
                    assertEquals(weight, after.terrainLevel(Terrains.BUILDING));
                    assertEquals(buildingClass, after.terrainLevel(Terrains.BLDG_CLASS));
                    assertEquals(cf[weight - 1], after.terrainLevel(Terrains.BLDG_CF));
                    assertEquals(2, after.terrainLevel(Terrains.BLDG_ELEV));
                    assertFalse(after.containsTerrain(Terrains.BLDG_FLUFF));
                    assertFalse(after.getAppearance().containsKey("building"));
                }
            }
        }
    }

    @Test
    void oldSingleBuildingTokensAndIsolatedRoofSelectorsLeaveNoLegacyDesign() throws Exception {
        for (String art : List.of("fluff:11:7", "fluff:1", "fluff:3")) {
            Hex hex = roundTrip(convert("building:1:0;bldg_cf:15;bldg_elev:2;" + art, "")).getHex(AT);
            assertFalse(hex.containsTerrain(Terrains.FLUFF), art);
            assertEquals(TilesetScenery.genericBuilding(hex), TilesetScenery.structureModels(hex).get(Terrains.BUILDING));
        }
        for (int roof = 64; roof <= 68; roof++) {
            Board imported = convert("building:1:" + roof + ";bldg_cf:15;bldg_elev:2", "");
            assertEquals(List.of(AT), imported.getBuildingAt(AT).getCoordsList());
            Hex hex = roundTrip(imported).getHex(AT);
            assertEquals(0, hex.getTerrain(Terrains.BUILDING).getExits(), "An old roof selector is not a connection");
        }
    }

    @Test
    void sealedThemesUseCurrentLooksWhileSpecialBuildingClassesKeepTheirKits() throws Exception {
        for (String theme : List.of("lunar", "mars", "volcano")) {
            Hex hex = roundTrip(convert("building:2:0;bldg_cf:40;bldg_elev:2", theme)).getHex(AT);
            assertEquals(theme.equals("lunar") ? "building/lunar-habitat" : "building/glass-dome",
                  hex.getAppearance().get("building").variant());
            if (!theme.equals("lunar")) { assertEquals(BuildingInteriors.NO, BuildingInteriors.resolve(hex)); }
            for (int buildingClass = 1; buildingClass <= 3; buildingClass++) {
                Hex special = roundTrip(convert("building:2:0;bldg_class:" + buildingClass + ";bldg_cf:40;bldg_elev:2", theme)).getHex(AT);
                assertEquals(TilesetScenery.genericBuilding(special), TilesetScenery.structureModels(special).get(Terrains.BUILDING));
            }
        }
    }

    @Test
    void legacyDropshipPiecesLeaveOnlyTheirUnderlyingTerrainInEveryTheme() throws Exception {
        for (String theme : List.of("", "lunar", "mars", "volcano")) {
            for (int piece = 1; piece <= 7; piece++) {
                Board board = roundTrip(convert("building:4:" + (68 + piece)
                      + ";bldg_cf:100;bldg_elev:5;bldg_armor:100;bldg_fluff:100;bldg_basement_type:2;pavement:1;rough:1", theme));
                Hex hex = board.getHex(AT);
                assertNoBuilding(hex);
                assertEquals(1, hex.terrainLevel(Terrains.PAVEMENT));
                assertEquals(1, hex.terrainLevel(Terrains.ROUGH));
                assertEquals(theme.isEmpty() ? null : theme, hex.getTheme());
            }
        }
    }

    @Test
    void repoManDropsAllTwentyEightLegacyShipPiecesOnImportAndNativeReload() throws Exception {
        Board board = BoardFile.read(Configuration.boardsDir().toPath().resolve("unofficial/Radar/56x30 Repo man.board"), true);
        Map<Coords, Hex> pieces = new LinkedHashMap<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Hex hex = board.getHex(x, y);
                var building = hex.getTerrain(Terrains.BUILDING);
                if (building != null && building.getLevel() == 4 && building.getExits() >= 69 && building.getExits() <= 75) {
                    pieces.put(new Coords(x, y), hex.duplicate());
                }
            }
        }
        assertEquals(28, pieces.size());
        BoardSceneryLayouts.convert(board);
        Board loaded = roundTrip(board);
        pieces.forEach((at, original) -> {
            for (Board converted : List.of(board, loaded)) {
                Hex hex = converted.getHex(at);
                assertNoBuilding(hex);
                assertEquals(original.getLevel(), hex.getLevel());
                assertEquals(original.getTheme(), hex.getTheme());
                assertEquals(original.terrainLevel(Terrains.PAVEMENT), hex.terrainLevel(Terrains.PAVEMENT));
                assertNull(converted.getBuildingAt(at));
            }
        });
    }

    private static void assertNoBuilding(Hex hex) {
        assertFalse(hex.containsTerrain(Terrains.BUILDING));
        assertFalse(hex.containsTerrain(Terrains.BLDG_CF));
        assertFalse(hex.containsTerrain(Terrains.BLDG_ELEV));
        assertFalse(hex.containsTerrain(Terrains.BLDG_ARMOR));
        assertFalse(hex.containsTerrain(Terrains.BLDG_FLUFF));
        assertFalse(hex.containsTerrain(Terrains.BLDG_BASEMENT_TYPE));
        assertFalse(hex.getAppearance().containsKey("building"));
        assertNull(hex.getBuildingReference());
        assertTrue(hex.getDecorations().isEmpty());
    }
}
