/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GpuRoughCaptureTest {
    @ParameterizedTest
    @CsvSource({ "0,0", "9,0", "0,1", "9,1", "0,2", "9,2" })
    void roughAddsGeometryWithoutPaintingDuplicateRocksUnderIt(int exits, int fluff) throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords coords = new Coords(2, 2);
                Hex hex = new Hex(0);
                if (exits != 0) { hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, exits)); }
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
                var clear = fixture.source.takeFrame().scene().tile(coords);
                Hex rough = hex.duplicate();
                rough.addTerrain(new Terrain(Terrains.ROUGH, 1));
                // A typed rough token (a gamemaster's) draws in 3D through the design its helper gives it.
                if (fluff != 0) { rough.addTerrain(new Terrain(Terrains.FLUFF, fluff)); }
                BoardSceneryLayouts.designTypedTokens(hex, rough);
                fixture.game.getBoard().setHex(coords, rough);
                fixture.source.refresh();
                var rocky = fixture.source.takeFrame().scene().tile(coords);
                assertEquals(exits == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED, rocky.road(),
                      "Adding Rough preserves the road type");
                assertEquals(exits, rocky.roadExits(), "Adding Rough preserves the road exits");
                assertTrue(rocky.features().stream().anyMatch(f -> f.kind() == (fluff == 0
                      ? BoardScene.FeatureKind.BOULDER : BoardScene.FeatureKind.ROUGH)));
                assertFalse(clear.sameGeometry(rocky), "Adding Rough invalidates the shared terrain mesh");
                assertTrue(fixture.game.getBoard().getHex(coords).containsTerrain(Terrains.ROUGH),
                      "Filtering artwork must not alter game terrain");
                assertEquals(rough.terrainLevel(Terrains.FLUFF), fixture.game.getBoard().getHex(coords).terrainLevel(Terrains.FLUFF));
                if (fluff != 0) {
                    Hex changed = rough.duplicate();
                    changed.addTerrain(new Terrain(Terrains.FLUFF, 3 - fluff));
                    BoardSceneryLayouts.designTypedTokens(rough, changed);
                    fixture.game.getBoard().setHex(coords, changed);
                    fixture.source.refresh();
                    var edited = fixture.source.takeFrame().scene().tile(coords);
                    assertFalse(rocky.sameGeometry(edited), "Changing only fluff rebuilds visible geometry and support");
                }
            });
        }
    }
}
