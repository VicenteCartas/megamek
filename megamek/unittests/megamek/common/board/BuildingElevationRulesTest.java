/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.BipedMek;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;
import megamek.common.units.QuadMek;
import megamek.common.units.Tank;
import megamek.common.units.TripodMek;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The shared deployment chooser and movement validation must agree on CORE's Mek floor restriction. */
class BuildingElevationRulesTest {
    private static final Coords POSITION = new Coords(1, 1);
    private final Game game = new Game();
    private final Board board = Board.createEmptyBoard(3, 3);
    private final Player player = new Player(0, "Player");
    private RulesManager previousRules;

    @BeforeAll
    static void equipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        previousRules = Game.rulesManager;
        board.setHex(POSITION, new Hex(3, "building:2;bldg_elev:4;bldg_cf:120;bldg_basement_type:1", ""));
        game.setBoard(0, board);
        game.addPlayer(0, player);
    }

    @AfterEach
    void restoreRules() {
        Game.rulesManager = previousRules;
    }

    static Stream<Arguments> unitsAndRules() {
        return Stream.of(OptionsConstants.RULES_CORE, OptionsConstants.RULES_TW).flatMap(rules ->
              Stream.of(new BipedMek(), new QuadMek(), new TripodMek(), new ConvInfantry(), new BattleArmor(),
                    new ProtoMek(), new Tank()).map(unit -> Arguments.of(rules, unit)));
    }

    @ParameterizedTest
    @MethodSource("unitsAndRules")
    void deploymentAndMovementRespectTheSelectedRules(String rules, Entity unit) {
        selectRules(rules);
        if (unit instanceof Tank) {
            unit.setMovementMode(EntityMovementMode.TRACKED);
        }
        add(unit);
        boolean interior = !(unit instanceof Tank)
              && (!(unit instanceof Mek) || rules.equals(OptionsConstants.RULES_TW));
        List<ElevationOption> options = elevations(unit);
        List<Integer> expected = unit instanceof Tank ? List.of(0)
              : interior ? List.of(0, 1, 2, 3, 4) : List.of(0, 4);
        assertEquals(expected, options.stream().map(ElevationOption::elevation).toList());
        assertEquals(DeploymentElevationType.ON_GROUND, options.getFirst().type());
        Hex hex = board.getHex(POSITION);
        assertTrue(unit.isElevationValid(0, hex), "Ground zero is relative to the elevated hex");
        for (int floor : List.of(1, 2, 3)) {
            assertEquals(interior, unit.isElevationValid(floor, hex), "Interior floor " + floor);
        }
        if (!(unit instanceof Tank)) {
            assertEquals(DeploymentElevationType.BUILDING_TOP, options.getLast().type());
            assertTrue(unit.isElevationValid(4, hex), "The roof remains a legal elevation");
        }
    }

    @Test
    void changingTheRulesOptionRefreshesFloorChoices() {
        BipedMek unit = new BipedMek();
        add(unit);
        selectRules(OptionsConstants.RULES_TW);
        assertEquals(5, elevations(unit).size());
        selectRules(OptionsConstants.RULES_CORE);
        assertEquals(List.of(0, 4), elevations(unit).stream().map(ElevationOption::elevation).toList());
        selectRules(OptionsConstants.RULES_TW);
        assertEquals(5, elevations(unit).size());
    }

    @Test
    void fighterModeDeploymentUsesAltitudesRatherThanBuildingFloors() {
        selectRules(OptionsConstants.RULES_CORE);
        LandAirMek unit = new LandAirMek(Mek.GYRO_STANDARD, Mek.COCKPIT_STANDARD, LandAirMek.LAM_STANDARD);
        unit.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
        add(unit);
        assertTrue(elevations(unit).contains(new ElevationOption(1, DeploymentElevationType.ALTITUDE)),
              "Altitude 1 above a building is not its first floor");
    }

    private void selectRules(String rules) {
        game.getOptions().getOption(OptionsConstants.RULES_SYSTEM).setValue(rules);
        game.setOptions(game.getOptions());
    }

    @Test
    void interiorOverridesFilterMovementAndDeploymentTogetherAndKeepTheRoof() {
        selectRules(OptionsConstants.RULES_TW);
        var unit = new ConvInfantry(); add(unit);
        Hex hex = board.getHex(POSITION);
        BuildingInteriors.set(hex, BuildingInteriors.NO_FLOORS);
        assertEquals(List.of(0, 4), elevations(unit).stream().map(ElevationOption::elevation).toList());
        org.junit.jupiter.api.Assertions.assertFalse(unit.isElevationValid(1, hex));
        assertTrue(unit.isElevationValid(0, hex)); assertTrue(unit.isElevationValid(4, hex));
        hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_BASEMENT_TYPE, 2));
        hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_BASE_COLLAPSED, 1));
        BuildingInteriors.set(hex, BuildingInteriors.NO);
        assertEquals(megamek.common.enums.BasementType.NONE.ordinal(), hex.terrainLevel(megamek.common.units.Terrains.BLDG_BASEMENT_TYPE));
        org.junit.jupiter.api.Assertions.assertFalse(hex.containsTerrain(megamek.common.units.Terrains.BLDG_BASE_COLLAPSED));
        assertEquals(List.of(4), elevations(unit).stream().map(ElevationOption::elevation).toList());
        org.junit.jupiter.api.Assertions.assertFalse(unit.isElevationValid(0, hex));
        assertTrue(unit.isElevationValid(4, hex));
        BuildingInteriors.set(hex, BuildingInteriors.YES);
        assertEquals(List.of(0, 1, 2, 3, 4), elevations(unit).stream().map(ElevationOption::elevation).toList());
    }

    @Test
    void domeAndHangarDefaultsCanBeOverridden() {
        Hex hex = board.getHex(POSITION);
        hex.setAppearance(java.util.Map.of("building", new HexAppearance("building/glass-dome", null, null, null)));
        assertEquals(BuildingInteriors.NO, BuildingInteriors.resolve(hex));
        BuildingInteriors.set(hex, BuildingInteriors.YES);
        assertEquals(BuildingInteriors.YES, BuildingInteriors.resolve(hex));
        BuildingInteriors.set(hex, BuildingInteriors.DEFAULT);
        hex.setAppearance(java.util.Map.of());
        hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_CLASS, megamek.common.units.IBuilding.HANGAR));
        assertEquals(BuildingInteriors.NO_FLOORS, BuildingInteriors.resolve(hex));
    }

    private void add(Entity unit) {
        unit.setId(1);
        unit.setOwner(player);
        game.addEntity(unit);
    }

    private List<ElevationOption> elevations(Entity unit) {
        return new AllowedDeploymentHelper(unit, POSITION, board, board.getHex(POSITION), game)
              .findAllowedElevations();
    }
}
