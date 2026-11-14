/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.server.SmokeCloud;
import org.junit.jupiter.api.Test;

class BoardFireSmokeTest {
    @Test
    void screenPresenceUsesTheChaffVolumeIncludingLegacyZeroWithoutChangingSmokeOrRules() {
        for (int level : new int[] { 0, 1, 3 }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.SCREEN, level));
            var screen = BoardFireSmoke.capture(hex);
            assertTrue(screen.present());
            assertTrue(screen.screen());
            assertEquals(3, screen.palette());
            assertEquals(new BoardFireSmoke(0, SmokeCloud.SMOKE_CHAFF_LIGHT).density(), screen.density());
            assertEquals(0, screen.flame());
            assertEquals(level, hex.terrainLevel(Terrains.SCREEN), "Capturing a screen never alters its rules");

            hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_HEAVY));
            var combined = BoardFireSmoke.capture(hex);
            assertTrue(combined.screen());
            assertEquals(SmokeCloud.SMOKE_HEAVY, combined.smoke());
            assertEquals(new BoardFireSmoke(0, SmokeCloud.SMOKE_HEAVY).density(), combined.density());
            hex.removeTerrain(Terrains.SMOKE);
            assertEquals(screen, BoardFireSmoke.capture(hex));
            hex.removeTerrain(Terrains.SCREEN);
            assertEquals(BoardFireSmoke.NONE, BoardFireSmoke.capture(hex));
        }
    }

    @Test
    void captureKeepsSpecialSmokeTypesAndAllInfernoTypesDistinctFromNormalFire() {
        assertFalse(BoardFireSmoke.capture(new Hex(0)).present());
        for (int fire = 1; fire <= 4; fire++) {
            for (int smoke = 1; smoke <= 6; smoke++) {
                Hex hex = new Hex(0);
                hex.addTerrain(new Terrain(Terrains.FIRE, fire));
                hex.addTerrain(new Terrain(Terrains.SMOKE, smoke));
                var effect = BoardFireSmoke.capture(hex);
                assertEquals(fire, effect.fire());
                assertEquals(smoke, effect.smoke());
                assertTrue(effect.present());
                assertEquals(fire == Terrains.FIRE_LVL_NORMAL ? 1 : 1.25f, effect.flame());
            }
        }
        assertTrue(new BoardFireSmoke(0, SmokeCloud.SMOKE_HEAVY).density()
              > new BoardFireSmoke(0, SmokeCloud.SMOKE_LIGHT).density());
        assertTrue(new BoardFireSmoke(0, SmokeCloud.SMOKE_LI_HEAVY).density()
              > new BoardFireSmoke(0, SmokeCloud.SMOKE_LI_LIGHT).density());
        assertEquals(1, new BoardFireSmoke(0, SmokeCloud.SMOKE_GREEN).palette());
        assertEquals(3, new BoardFireSmoke(0, SmokeCloud.SMOKE_CHAFF_LIGHT).palette());
    }

    @Test
    void nativeCapturePreservesGroundAndEffectsAcrossTacticalRepaintingAndRemoval() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords coords = new Coords(0, 16);
                for (int type : new int[] { Terrains.PAVEMENT, Terrains.SNOW, Terrains.SAND, Terrains.MUD, Terrains.WOODS }) {
                    Hex hex = new Hex(2);
                    hex.addTerrain(new Terrain(type, 1));
                    fixture.game.getBoard().setHex(coords, hex);
                    fixture.source.refresh();
                    var plain = fixture.source.takeFrame().scene().tile(coords);
                    hex = hex.duplicate();
                    hex.addTerrain(new Terrain(Terrains.FIRE, Terrains.FIRE_LVL_INFERNO));
                    hex.addTerrain(new Terrain(Terrains.SMOKE, SmokeCloud.SMOKE_HEAVY));
                    fixture.game.getBoard().setHex(coords, hex);
                    fixture.source.refresh();
                    var burning = fixture.source.takeFrame().scene().tile(coords);
                    assertEquals(plain.surface(), burning.surface());
                    assertEquals(new BoardFireSmoke(2, 2), burning.fireSmoke());
                    fixture.source.setVisibleArea(new Rectangle(0, 15, 2, 2));
                    fixture.source.refresh();
                    assertEquals(burning.fireSmoke(), fixture.source.takeFrame().scene().tile(coords).fireSmoke());
                    fixture.source.setVisibleArea(new Rectangle(4, 4, 2, 2));
                    fixture.source.refresh();
                    assertEquals(burning.fireSmoke(), fixture.source.takeFrame().scene().tile(coords).fireSmoke());
                    hex = hex.duplicate();
                    hex.removeTerrain(Terrains.FIRE);
                    hex.removeTerrain(Terrains.SMOKE);
                    fixture.game.getBoard().setHex(coords, hex);
                    fixture.source.refresh();
                    assertEquals(BoardFireSmoke.NONE, fixture.source.takeFrame().scene().tile(coords).fireSmoke());
                }
            });
        }
    }

    @Test
    void nativeScreenCaptureReplacesItsSpriteAndRetainsEnhancedGround() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords coords = new Coords(0, 16);
                Hex hex = new Hex(0);
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
                var plain = fixture.source.takeFrame().scene().tile(coords);
                for (int level : new int[] { 0, 1 }) {
                    hex.addTerrain(new Terrain(Terrains.SCREEN, level));
                    fixture.game.getBoard().setHex(coords, hex.duplicate());
                    fixture.source.refresh();
                    var screened = fixture.source.takeFrame().scene().tile(coords);
                    assertTrue(screened.fireSmoke().screen());
                    assertTrue(screened.fireSmoke().present());
                    assertEquals(level, fixture.game.getBoard().getHex(coords).terrainLevel(Terrains.SCREEN));
                }
            });
        }
    }

    @Test
    void windUsesBoardTravelBearingIntegratesChangesAndStopsWhenPaused() {
        var east = new GpuTerrainEffects.Motion();
        var west = new GpuTerrainEffects.Motion();
        for (int i = 0; i < 30; i++) {
            east.advance(wind(1, 90), .1f);
            west.advance(wind(1, 270), .1f);
        }
        assertTrue(east.wind.x > .99 && west.wind.x < -.99);
        assertEquals(256, east.offset.x + west.offset.x, .001f);
        assertEquals(east.offset.z, west.offset.z);
        Vector3 before = east.offset.cpy();
        east.advance(wind(1, 270), 0);
        assertEquals(before, east.offset, "Changing wind while paused cannot move the field");
        assertTrue(east.wind.x > .99);
        east.advance(wind(1, 270), .01f);
        assertTrue(east.wind.x > 0 && east.wind.x < 1, "Bending turns smoothly, without teleporting the plume");
        var calm = new GpuTerrainEffects.Motion();
        calm.advance(BoardAtmosphere.Effects.NONE, .1f);
        assertEquals(0, calm.offset.x);
        assertEquals(0, calm.offset.y);
        assertTrue(calm.offset.z > 0, "Buoyancy still animates in calm weather");
    }

    @Test
    void allThreeLodsUseHysteresis() {
        assertEquals(0, GpuTerrainEffects.lod(160, 2));
        assertEquals(0, GpuTerrainEffects.lod(76, 0));
        assertEquals(1, GpuTerrainEffects.lod(70, 0));
        assertEquals(1, GpuTerrainEffects.lod(85, 1));
        assertEquals(2, GpuTerrainEffects.lod(15, 1));
        assertEquals(2, GpuTerrainEffects.lod(25, 2));
        assertEquals(1, GpuTerrainEffects.lod(30, 2));
    }

    static BoardAtmosphere.Effects wind(float strength, float direction) {
        return new BoardAtmosphere.Effects(0, 0, 0, 0, 0, strength, direction);
    }
}
