/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TilesetSceneryFilesTest {
    private static final String ASSET = "buildings/test/hall";

    @Test
    void fuelTanksUseAvailableWeightKitsIncludingOutOfRangeImportedWeights() {
        for (int weight = 0; weight <= 5; weight++) {
            Hex hex = new Hex(0, "fuel_tank:" + weight, "");
            String expected = "buildings/fuel_tanks/fuel_tank_"
                  + List.of("light", "light", "medium", "heavy", "hard", "hard").get(weight);
            assertEquals(expected, TilesetScenery.structureModels(hex).get(Terrains.FUEL_TANK));
            assertTrue(TilesetScenery.customBuildingFile(expected).isFile());
        }
    }

    @Test
    void retiredTilesetModelsCannotOverrideTheModularWeightAndClass(@TempDir Path root) throws Exception {
        File previous = Configuration.dataDir();
        try {
            prepare(root);
            Files.writeString(root.resolve("models/board/classic/tileset/classic.tileset"),
                  "super * \"building:*\" \"\" \"" + ASSET + "\"\n"
                        + "super * \"fuel_tank:*\" \"\" \"" + ASSET + "\"\n");
            Path legacy = root.resolve("models/board/" + ASSET + ".glb");
            Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "retired model");
            Configuration.setDataDir(root.toFile()); TilesetScenery.reload();
            assertTrue(TilesetScenery.structureModels(new Hex(0, "building:1;fuel_tank:1", "")).isEmpty(),
                  "A missing native kit must never revive a retired tileset model");
            Path unselected = root.resolve("models/" + ASSET + ".glb");
            Files.createDirectories(unselected.getParent()); Files.writeString(unselected, "unselected native model");
            TilesetScenery.reload();
            assertTrue(TilesetScenery.structureModels(new Hex(0, "building:1;fuel_tank:1", "")).isEmpty(),
                  "Even an installed native kit needs a native design, not an old tileset selection");
            for (int weight = 1; weight <= 4; weight++) {
                for (int kind = 0; kind <= 3; kind++) {
                    Hex hex = new Hex(0, "building:" + weight + ";bldg_class:" + kind + ";bldg_elev:2", "");
                    String expected = TilesetScenery.genericBuilding(hex);
                    Path kit = root.resolve("models/" + expected + ".json");
                    Files.createDirectories(kit.getParent()); Files.writeString(kit, "[]");
                }
            }
            Configuration.setDataDir(root.toFile()); TilesetScenery.reload();
            for (int weight = 1; weight <= 4; weight++) {
                for (int kind = 0; kind <= 3; kind++) {
                    Hex hex = new Hex(0, "building:" + weight + ";bldg_class:" + kind + ";bldg_elev:2", "");
                    assertEquals(TilesetScenery.genericBuilding(hex), TilesetScenery.structureModels(hex).get(Terrains.BUILDING));
                }
            }
        } finally {
            Configuration.setDataDir(previous); TilesetScenery.reload();
        }
    }

    @Test
    void filePrecedenceMissingModelsAndVariantsFollowTheAssetReloadGeneration(@TempDir Path root) throws Exception {
        File previous = Configuration.dataDir();
        try {
            prepare(root);
            Configuration.setDataDir(root.toFile());
            TilesetScenery.reload();
            Path exact = root.resolve("models/buildings/test/hall.glb");
            Path generated = root.resolve("models/buildings/test/hall.json");
            Files.createDirectories(exact.getParent());
            Hex hex = new Hex(0, "building:1", "");
            hex.setAppearance(Map.of("building", new HexAppearance(null, ASSET, null, null)));
            assertEquals(exact.toFile(), TilesetScenery.customBuildingFile(ASSET));
            assertFalse(TilesetScenery.structureModels(hex).containsKey(Terrains.BUILDING));
            Files.writeString(generated, "{\"variants\":[{\"name\":\"first\"},{\"name\":\"second\"}]}");
            assertFalse(TilesetScenery.structureModels(hex).containsKey(Terrains.BUILDING),
                  "A capture uses one asset generation until the explicit reload");
            TilesetScenery.reload();
            assertEquals(generated.toFile(), TilesetScenery.customBuildingFile(ASSET));
            assertEquals(ASSET, TilesetScenery.structureModels(hex).get(Terrains.BUILDING));
            assertEquals(generated.toFile(), TilesetScenery.customBuildingFile(ASSET + "-square@second"));
            assertEquals(List.of("first", "second"), TilesetScenery.kitVariants(ASSET));
            // A hangar draws a variant's vaulted roof where it offers one, unless it chooses the flat one; no other building does.
            Files.writeString(generated, "{\"variants\":[{\"name\":\"first\"},{\"name\":\"second\",\"vaulted\":\"vault\"}]}");
            TilesetScenery.reload();
            assertEquals(List.of("second"), TilesetScenery.kitVaults(ASSET));
            Hex hangar = new Hex(0, "building:1;bldg_class:1", "");
            hangar.setAppearance(Map.of("building", new HexAppearance(null, ASSET, null, null),
                  "kitVariant", HexAppearance.ofKitVariant("second")));
            assertEquals(ASSET + "@second" + TilesetScenery.VAULTED, TilesetScenery.structureModels(hangar).get(Terrains.BUILDING));
            hangar.setAppearance(Map.of("building", new HexAppearance(null, ASSET, null, null),
                  "kitVariant", HexAppearance.ofKitVariant("second"),
                  "roof", megamek.common.board.HexAppearance.of(megamek.common.board.HexAppearance.Roof.FLAT)));
            assertEquals(ASSET + "@second", TilesetScenery.structureModels(hangar).get(Terrains.BUILDING));
            Hex offices = new Hex(0, "building:1", "");
            offices.setAppearance(Map.of("building", new HexAppearance(null, ASSET, null, null),
                  "kitVariant", HexAppearance.ofKitVariant("second"),
                  "roof", megamek.common.board.HexAppearance.of(megamek.common.board.HexAppearance.Roof.VAULTED)));
            assertEquals(ASSET + "@second", TilesetScenery.structureModels(offices).get(Terrains.BUILDING));
            hangar.setAppearance(Map.of("building", new HexAppearance(null, ASSET, null, null)));
            assertEquals(ASSET, TilesetScenery.structureModels(hangar).get(Terrains.BUILDING),
                  "Its first variant offers none");
            Files.writeString(exact, "exact");
            TilesetScenery.reload();
            assertEquals(exact.toFile(), TilesetScenery.customBuildingFile(ASSET));
            assertFalse(TilesetScenery.generatedKit(ASSET));
            Files.delete(exact); Files.delete(generated);
            TilesetScenery.reload();
            assertFalse(TilesetScenery.structureModels(hex).containsKey(Terrains.BUILDING));
            Path legacy = root.resolve("models/board/" + ASSET + ".glb");
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy, "legacy");
            TilesetScenery.reload();
            assertFalse(TilesetScenery.structureModels(hex).containsKey(Terrains.BUILDING),
                  "A native design cannot load a retired model from models/board/buildings either");
            assertFalse(TilesetScenery.generatedKit(ASSET));
        } finally {
            Configuration.setDataDir(previous);
            TilesetScenery.reload();
        }
    }

    @Test
    void changingTheDataDirectoryCannotReuseAnotherRootsFileDecisions(@TempDir Path directory) throws Exception {
        File previous = Configuration.dataDir();
        try {
            Path first = directory.resolve("first"), second = directory.resolve("second");
            prepare(first); prepare(second);
            Path generated = first.resolve("models/buildings/test/hall.json");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, "[]");
            Configuration.setDataDir(first.toFile());
            assertTrue(TilesetScenery.generatedKit(ASSET));
            Configuration.setDataDir(second.toFile());
            assertFalse(TilesetScenery.generatedKit(ASSET));
            assertEquals(second.resolve("models/buildings/test/hall.glb").toFile(),
                  TilesetScenery.customBuildingFile(ASSET));
            Configuration.setDataDir(first.toFile());
            assertTrue(TilesetScenery.generatedKit(ASSET));
        } finally {
            Configuration.setDataDir(previous);
            TilesetScenery.reload();
        }
    }

    private static void prepare(Path root) throws Exception {
        Path tileset = root.resolve("models/board/classic/tileset/classic.tileset");
        Files.createDirectories(tileset.getParent());
        Files.writeString(tileset, "super * \"building:1\" \"\" \"" + ASSET + "\"\n");
    }
}
