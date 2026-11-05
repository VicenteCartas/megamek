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
    @Test void mixedFacadeAtlasesProtectWindowsAndRoofs() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var root = java.nio.file.Path.of("data/models/board/textures/structures");
        var habitat = javax.imageio.ImageIO.read(root.resolve("lunar/habitat-wall_albedo.png").toFile());
        assertEquals(0, habitat.getRGB(40, 65) >>> 24, "Upper window stays unpainted");
        assertEquals(0, habitat.getRGB(40, 325) >>> 24, "Lower window stays unpainted");
        assertEquals(255, habitat.getRGB(40, 130) >>> 24, "Upper wall panel receives tint");
        assertEquals(255, habitat.getRGB(40, 390) >>> 24, "Lower wall panel receives tint");
        var light = javax.imageio.ImageIO.read(root.resolve("buildings/light-far_albedo.png").toFile());
        assertEquals(0, light.getRGB(83, 233) >>> 24, "Baked glass stays unpainted at far LOD");
        assertEquals(0, light.getRGB(118, 400) >>> 24, "Baked roof stays unpainted at far LOD");
        assertEquals(255, light.getRGB(250, 270) >>> 24, "Baked facade receives tint at far LOD");
        for (var lod : GpuBuilding.kit("buildings/lunar_habitat/lunar_habitat").lods()) {
            var data = (RigidGlb.Data) lod;
            assertTrue(data.buildingPaint.contains("structures/lunar/habitat-wall"));
            assertTrue(data.paintMasks.contains("structures/lunar/habitat-wall"));
        }
    }

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
        shared.materials.add(new Material("wall", ColorAttribute.createDiffuse(original), new GpuBuildingPaint(Color.WHITE)));
        ModelInstance first = new ModelInstance(shared), second = new ModelInstance(shared);
        first.materials.add(shared.materials.first().copy()); second.materials.add(shared.materials.first().copy());
        first.materials.add(new Material("glass", ColorAttribute.createDiffuse(original)));
        GpuBuilding.tint(first, BoardDecoration.Colours.of("#72949b"));
        Color expected = GpuColor.linear(Color.valueOf("72949b"));
        assertEquals(expected, first.materials.first().get(GpuBuildingPaint.class, GpuBuildingPaint.TYPE).colour);
        assertEquals(original, first.materials.first().get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertFalse(first.getMaterial("glass").has(GpuBuildingPaint.TYPE));
        assertEquals(original, first.getMaterial("glass").get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertEquals(original, second.materials.first().get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertEquals(original, shared.materials.first().get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertEquals(Color.WHITE, shared.materials.first().get(GpuBuildingPaint.class, GpuBuildingPaint.TYPE).colour);
        GpuBuilding.tint(first, BoardDecoration.Colours.NONE);
        assertEquals(Color.WHITE, first.materials.first().get(GpuBuildingPaint.class, GpuBuildingPaint.TYPE).colour);
        shared.dispose();
    }

    @Test void placeholderKitsUseConnectedPiecesAndTheAuthoredDemoUsesVolumes() {
        for (int weight = 1; weight <= 4; weight++) {
            for (int buildingClass = 0; buildingClass <= 3; buildingClass++) {
                Hex hex = new Hex(0, "building:" + weight + ":0;bldg_class:" + buildingClass + ";bldg_elev:2", "");
                String asset = TilesetScenery.genericBuilding(hex);
                for (var footprint : HexAppearance.Footprint.values()) {
                    var kit = GpuBuilding.kit(TilesetScenery.footprint(asset, footprint));
                    assertNotNull(kit, asset);
                    assertTrue(kit.roles().keySet().stream().anyMatch(role -> role.startsWith("base")));
                    assertTrue(kit.roles().keySet().stream().anyMatch(role -> role.startsWith("floor")));
                    for (var lod : kit.lods()) {
                        var data = (RigidGlb.Data) lod;
                        assertFalse(data.buildingPaint.isEmpty(), asset + " retains paint regions through kit generation");
                        assertTrue(data.buildingPaint.stream().noneMatch(id -> id.contains("glass") || id.contains("roof")),
                              asset + " excludes glazing and roof finishes");
                    }
                    assertTrue(kit.roles().keySet().stream().anyMatch(role -> role.startsWith("roof")));
                }
            }
        }
        String authored = "buildings/debug/debug-authored";
        assertFalse(TilesetScenery.singleHexBuilding(authored));
        assertTrue(megamek.common.board.BuildingVolumes.of(authored).present());
        var data = (RigidGlb.Data) GpuAuthoredBuilding.load(authored).getFirst();
        assertEquals(Set.of("structures/buildings/debug-panels#paint"), data.buildingPaint,
              "The demo paints its walls while preserving the neutral roofs, labels and storey bands");
        assertFalse(TilesetScenery.singleHexBuilding("buildings/lunar_habitat/lunar_habitat"));
        assertFalse(TilesetScenery.singleHexBuilding("buildings/fuel_tanks/fuel_tank_light"));
    }
}
