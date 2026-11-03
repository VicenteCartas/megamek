/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardBuildingDesignTest {
    @Test void buildingFeaturesCarryTintAndOnlyIsolatedBuildingsRotate() {
        Coords at = new Coords(2, 2);
        Hex hex = new Hex(0, "building:1:0;bldg_elev:2;bldg_cf:15", "");
        var colours = BoardDecoration.Colours.of("#72949b");
        hex.setAppearance(Map.of("building", new HexAppearance(null, null, null, null, colours, 45)));
        var models = TilesetScenery.structureModels(hex);
        var feature = BoardFeatures.capture(hex, at, models).stream().filter(f -> f.kind() == BoardScene.FeatureKind.BUILDING).findFirst().orElseThrow();
        assertEquals(-45, feature.rotation()); assertEquals(colours, feature.colours());
        Hex incoming = new Hex(0, "building:1:8;bldg_elev:2;bldg_cf:15", "");
        feature = BoardFeatures.capture(hex, at, models, Set.of(), cell -> cell.equals(at.translated(0)) ? incoming : null)
              .stream().filter(f -> f.kind() == BoardScene.FeatureKind.BUILDING).findFirst().orElseThrow();
        assertEquals(0, feature.rotation()); assertEquals(colours, feature.colours());
        hex.addTerrain(new Terrain(Terrains.BUILDING, 1, true, 8));
        assertEquals(0, BoardFeatures.capture(hex, at, models).stream()
              .filter(f -> f.kind() == BoardScene.FeatureKind.BUILDING).findFirst().orElseThrow().rotation());
    }

    @Test void tintChangesOnlyThePlacementsMaterialAndKeepsItsOriginalShading() {
        Model shared = new Model();
        var original = new Color(.8f, .6f, .4f, .7f);
        shared.materials.add(new Material("wall", ColorAttribute.createDiffuse(original)));
        ModelInstance first = new ModelInstance(shared), second = new ModelInstance(shared);
        first.materials.add(shared.materials.first().copy()); second.materials.add(shared.materials.first().copy());
        GpuBuilding.tint(first, BoardDecoration.Colours.of("#72949b"));
        Color expected = original.cpy().mul(GpuColor.linear(Color.valueOf("72949b")));
        assertEquals(expected, first.materials.first().get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertEquals(original, second.materials.first().get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertEquals(original, shared.materials.first().get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        shared.dispose();
    }

    @Test void placeholderAndAuthoredKitsLoadThroughTheRealConnectedKitContract() {
        for (int weight = 1; weight <= 4; weight++) {
            for (int buildingClass = 0; buildingClass <= 3; buildingClass++) {
                Hex hex = new Hex(0, "building:" + weight + ":0;bldg_class:" + buildingClass + ";bldg_elev:2", "");
                String asset = TilesetScenery.genericBuilding(hex);
                for (var footprint : HexAppearance.Footprint.values()) {
                    var kit = GpuBuilding.kit(TilesetScenery.footprint(asset, footprint));
                    assertNotNull(kit, asset);
                    assertTrue(kit.roles().keySet().stream().anyMatch(role -> role.startsWith("base")));
                    assertTrue(kit.roles().keySet().stream().anyMatch(role -> role.startsWith("floor")));
                    assertTrue(kit.roles().keySet().stream().anyMatch(role -> role.startsWith("roof")));
                }
            }
        }
        String authored = "buildings/debug/debug-authored";
        assertTrue(TilesetScenery.singleHexBuilding(authored));
        var kit = GpuBuilding.kit(authored);
        assertTrue(kit.pieces().stream().allMatch(ConnectedKit.Piece::core));
        assertEquals(Set.of("base0", "floor0", "roof0"), kit.roles().keySet());
        assertFalse(TilesetScenery.singleHexBuilding("buildings/lunar_habitat/lunar_habitat"));
        assertFalse(TilesetScenery.singleHexBuilding("buildings/fuel_tanks/fuel_tank_light"));
    }
}
