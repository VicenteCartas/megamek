/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.tileset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.common.board.CubeCoords;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;
import megamek.common.equipment.GunEmplacement;
import megamek.common.equipment.WeaponMounted;
import megamek.common.units.BipedMek;
import megamek.common.units.BuildingEntity;
import megamek.common.units.Dropship;
import megamek.common.weapons.lasers.innerSphere.medium.ISLaserMedium;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MekTilesetModelsTest {
    @TempDir
    Path directory;

    @Test
    void spriteOverridesInheritChassisModelsAndMissingChassisUseTheDefault() throws Exception {
        Files.writeString(directory.resolve("mekset.txt"), """
              exact "default_medium" "medium.png" "units/fallback/biped.json"
              chassis "Test" "test.png" "units/test/model.json"
              exact "Test A" "variant.png"
              exact "Test B" "variant-b.png" "units/custom/model.json"
              """);
        MekTileset tileset = new MekTileset(directory.toFile());
        tileset.loadFromFile("mekset.txt");
        BipedMek mek = new BipedMek();
        mek.setWeight(50);
        mek.setChassis("Test");
        mek.setModel("A");
        assertEquals("variant.png", tileset.entryFor(mek, -1).getImageFile());
        assertEquals("units/test/model.json", tileset.modelFor(mek, -1));
        mek.setModel("B");
        assertEquals("units/custom/model.json", tileset.modelFor(mek, -1));
        mek.setChassis("Unknown");
        assertEquals("units/fallback/biped.json", tileset.modelFor(mek, -1));
        assertNull(new MekTileset(directory.toFile()).modelFor(mek, -1));
    }

    @Test
    void aDropShipFindsItsOwnStitchedImageByChassisAndByVariant() throws Exception {
        Files.writeString(directory.resolve("mekset.txt"), """
              chassis "Long" "long-single.png"
              chassis "Long_whole" "long-whole.png"
              exact "Long B_whole" "variant-whole.png"
              """);
        MekTileset tileset = new MekTileset(directory.toFile());
        tileset.loadFromFile("mekset.txt");
        Dropship ship = new Dropship();
        ship.setChassis("Long");
        ship.setModel("A");
        assertEquals("long-whole.png", tileset.entryFor(ship, MekTileset.WHOLE).getImageFile());
        assertEquals("long-single.png", tileset.entryFor(ship, -1).getImageFile());
        ship.setModel("B");
        assertEquals("variant-whole.png", tileset.entryFor(ship, MekTileset.WHOLE).getImageFile());
    }

    @Test
    void armedStructuresAssembleTheBuildingTurretWithTheirGenericModelAsFallback() throws Exception {
        Files.writeString(directory.resolve("mekset.txt"), """
              exact "default_gun_emplacement" "emplacement.png" "units/emplacement.json"
              exact "default_turret" "emplacement.png" "buildings/turret.json"
              exact "default_hhw" "hhw.png" "units/hhw.json"
              """);
        MekTileset tileset = new MekTileset(directory.toFile());
        tileset.loadFromFile("mekset.txt");
        GunEmplacement emplacement = new GunEmplacement();
        assertEquals("buildings/turret.json", tileset.modelFor(emplacement, -1));
        assertEquals("units/emplacement.json", tileset.genericModelFor(emplacement, -1));
        // An armed advanced building assembles its roof turret and wall casemates; an unarmed one keeps its own model.
        BuildingEntity building = new BuildingEntity(BuildingType.MEDIUM, 1);
        building.getInternalBuilding().setBuildingHeight(1);
        building.getInternalBuilding().addHex(CubeCoords.ZERO, 40, 0, BasementType.NONE, false);
        building.refreshLocations();
        building.refreshAdditionalLocations();
        assertEquals("units/hhw.json", tileset.modelFor(building, -1));
        WeaponMounted wall = new WeaponMounted(building, new ISLaserMedium());
        building.addEquipment(wall, 0, false);
        wall.setFacing(0);
        assertEquals("buildings/turret.json", tileset.modelFor(building, -1));
        assertEquals("units/hhw.json", tileset.genericModelFor(building, -1));
    }

    @Test
    void includesAndLegacyThreeFieldEntriesRemainValid() throws Exception {
        Files.writeString(directory.resolve("included.txt"), "chassis \"Test\" \"test.png\" \"units/test/model.json\"\n");
        Files.writeString(directory.resolve("mekset.txt"), "include \"included.txt\"\nexact \"Test A\" \"a.png\"\n");
        MekTileset tileset = new MekTileset(directory.toFile());
        tileset.loadFromFile("mekset.txt");
        BipedMek mek = new BipedMek();
        mek.setChassis("Test");
        mek.setModel("A");
        assertEquals("a.png", tileset.entryFor(mek, -1).getImageFile());
        assertEquals("units/test/model.json", tileset.modelFor(mek, -1));
        assertTrue(MekSetTest.isValidContentLine(List.of("exact", "Legacy", "sprite.png")));
        assertTrue(MekSetTest.isValidContentLine(List.of("exact", "Modern", "sprite.png", "units/model.json")));
    }
}
