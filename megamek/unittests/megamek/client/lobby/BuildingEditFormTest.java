/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.dialogs.GameMasterEditMemory;
import megamek.common.Hex;
import megamek.common.board.BuildingEditSpec;
import megamek.common.board.Coords;
import megamek.common.enums.BuildingType;
import megamek.common.units.IBuilding;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BuildingEditFormTest {

    private static final Coords HEX = new Coords(3, 5);
    private static final int BOARD = 0;

    private final List<BuildingEditSpec> sent = new ArrayList<>();
    private List<Integer> fluff = List.of();
    private final BuildingEditForm.Host host = new BuildingEditForm.Host() {
        @Override
        public void sendBuildingEdit(BuildingEditSpec spec) {
            sent.add(spec);
        }

        @Override
        public List<Integer> fluffLevels(Hex building) {
            return fluff;
        }
    };

    @AfterEach
    void forgetTheHex() {
        GameMasterEditMemory.forget(HEX);
    }

    @Test
    void anEmptyHexStartsOnAMediumBuildingAndSendsIt() {
        BuildingEditForm form = new BuildingEditForm(HEX, BOARD, null, new Hex(), host);

        assertEquals(BuildingType.MEDIUM, form.type());
        assertEquals(BuildingType.MEDIUM.getDefaultCF(), form.constructionFactor());
        assertFalse(form.mayRemove());
        assertFalse(form.mayRestore());
        form.height(3);
        form.armor(10);

        assertTrue(form.apply());

        assertEquals(1, sent.size());
        BuildingEditSpec spec = sent.get(0);
        assertEquals(HEX, spec.getCoords());
        assertEquals(BuildingType.MEDIUM, spec.getBuildingType());
        assertEquals(3, spec.getHeight());
        assertEquals(10, spec.getArmor());
        assertFalse(spec.isFuelTank());
        assertTrue(form.mayRestore(), "the hex as it was is remembered on the first change");
    }

    @Test
    void theConstructionFactorKeepsToTheTypesBand() {
        BuildingEditForm form = new BuildingEditForm(HEX, BOARD, null, null, host);
        form.type(BuildingType.HEAVY);

        assertEquals(BuildingType.HEAVY.getDefaultCF(), form.constructionFactor());
        form.constructionFactor(BuildingEditForm.MAX_CONSTRUCTION_FACTOR);
        assertEquals(BuildingType.HEAVY.getMaximumCF(), form.constructionFactor());
        form.height(99);
        assertEquals(BuildingEditForm.MAX_HEIGHT, form.height());
        form.magnitude(1);
        assertEquals(BuildingEditForm.SMALLEST_USEFUL_MAGNITUDE, form.magnitude());
    }

    @Test
    void aFuelTankIsSentWithItsMagnitude() {
        BuildingEditForm form = new BuildingEditForm(HEX, BOARD, null, null, host);
        form.fuelTank(true);
        form.magnitude(40);

        form.apply();

        assertTrue(sent.get(0).isFuelTank());
        assertEquals(40, sent.get(0).getMagnitude());
    }

    @Test
    void waterWithoutABuildingRefusesOneAndSendsNothing() {
        Hex water = new Hex();
        water.addTerrain(new Terrain(Terrains.WATER, 2));
        BuildingEditForm form = new BuildingEditForm(HEX, BOARD, null, water, host);

        assertTrue(form.inWater());
        assertFalse(form.apply());
        assertTrue(sent.isEmpty());
        assertNull(GameMasterEditMemory.describeBeforeFirstEdit(HEX));
    }

    @Test
    void anExistingBuildingIsReadAndMayBeRemovedOrRestored() {
        IBuilding building = mock(IBuilding.class);
        when(building.getBuildingType()).thenReturn(BuildingType.HEAVY);
        when(building.getCurrentCF(HEX)).thenReturn(60);
        when(building.getArmor(HEX)).thenReturn(5);
        when(building.getHeight(HEX)).thenReturn(2);
        when(building.getBldgClass()).thenReturn(IBuilding.HANGAR);
        BuildingEditForm form = new BuildingEditForm(HEX, BOARD, building, new Hex(), host);

        assertEquals(BuildingType.HEAVY, form.type());
        assertEquals(60, form.constructionFactor());
        assertEquals(5, form.armor());
        assertEquals(2, form.height());
        assertEquals(1, form.buildingClass());
        assertTrue(form.mayRemove());

        form.remove();
        assertTrue(sent.get(0).isRemovingBuilding());
        assertTrue(form.mayRestore());

        form.restore();
        assertTrue(sent.get(1).isRestoringOriginal());
        assertFalse(form.mayRestore(), "restoring forgets the remembered hex");
    }

    @Test
    void offersTheFluffImagesTheTilesetHas() {
        fluff = List.of(2, 5);
        BuildingEditForm form = new BuildingEditForm(HEX, BOARD, null, null, host);

        assertEquals(List.of(0, 2, 5), form.fluffLevels());
        form.fluffImage(5);
        assertEquals(5, form.fluffImage());
        form.fluffImage(7);
        assertEquals(0, form.fluffImage(), "an image the tileset lacks falls back to the ordinary one");
        assertTrue(form.buildingAsHex(2).containsTerrain(Terrains.BLDG_FLUFF));
    }
}
