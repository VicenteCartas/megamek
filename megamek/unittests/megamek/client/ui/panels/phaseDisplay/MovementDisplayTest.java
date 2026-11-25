/*
 * Copyright (C) 2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.client.ui.panels.phaseDisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.MiscType;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.BipedMek;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Mek;
import megamek.common.units.QuadVee;
import megamek.common.units.Targetable;
import megamek.common.units.VTOL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@DisplayName("MovementDisplay Unit Tests")
class MovementDisplayTest {

    private MovementDisplay movementDisplay;
    private ClientGUI mockClientGUI;
    private Client mockClient;
    private MockedStatic<MegaMekGUI> mockedMegaMekGUI;
    private Game game;

    @BeforeEach
    void beforeEach() {
        MegaMekController mockController = mock(MegaMekController.class);
        mockedMegaMekGUI = mockStatic(MegaMekGUI.class);
        mockedMegaMekGUI.when(MegaMekGUI::getKeyDispatcher).thenReturn(mockController);

        Board board = new Board(4, 4);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }

        mockClientGUI = mock(ClientGUI.class);
        mockClient = mock(Client.class);
        game = new Game(board);
        BoardClientState boardState = mock(BoardClientState.class);
        CommonMenuBar menuBar = mock(CommonMenuBar.class);
        mockClientGUI.controller = mockController;

        when(mockClientGUI.getClient()).thenReturn(mockClient);
        when(mockClient.getGame()).thenReturn(game);
        when(mockClientGUI.boardStates()).thenReturn(List.of());
        when(mockClientGUI.getMenuBar()).thenReturn(menuBar);
        when(mockClientGUI.getBoardState((Targetable) any())).thenReturn(boardState);
        when(mockClientGUI.getBoardState(anyInt())).thenReturn(boardState);

        movementDisplay = new MovementDisplay(mockClientGUI);
    }

    @AfterEach
    void tearDown() {
        if (mockedMegaMekGUI != null) {
            mockedMegaMekGUI.close();
        }
    }

    @Test
    @DisplayName("clear resets a walk-on movement path without losing deployment")
    void clearWithWalkPathKeepsDeploymentAnchored() throws Exception {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setPosition(new Coords(0, 0));
        mek.setBoardId(game.getBoard().getBoardId());
        game.addEntity(mek);
        movementDisplay.currentEntity = mek.getId();

        MovePath walkPath = new MovePath(game, mek);
        walkPath.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", walkPath);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);

        movementDisplay.clear();

        assertTrue(mek.isDeployed(), "Walk-on clear should keep the deployment anchor intact.");
        assertEquals(MovementDisplay.GEAR_LAND, readField(movementDisplay, "gear"),
                     "clear should reset gear to walk after a walk-on move path.");

        MovePath refreshed = (MovePath) readField(movementDisplay, "cmd");
        assertNotNull(refreshed, "clear should rebuild the move path.");
        assertTrue(refreshed.getStepVector().stream().anyMatch(step -> step.getType() == MoveStepType.DEPLOY),
                   "clear should preserve the deployment step on a walk path.");
    }

    @Test
    @DisplayName("clear resets jump movement paths to the default gear while keeping deployment anchor")
    void clearWithJumpPathResetsToLandGear() throws Exception {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setPosition(new Coords(0, 0));
        mek.setBoardId(game.getBoard().getBoardId());
        game.addEntity(mek);
        movementDisplay.currentEntity = mek.getId();

        MovePath jumpPath = new MovePath(game, mek);
        jumpPath.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", jumpPath);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_JUMP);

        movementDisplay.clear();

        assertTrue(mek.isDeployed(), "clear should keep the deployed position for a jump path.");
        assertEquals(MovementDisplay.GEAR_JUMP, readField(movementDisplay, "gear"),
                     "clear should reset to jumping after reinitializing a jump path.");

        MovePath refreshed = (MovePath) readField(movementDisplay, "cmd");
        assertNotNull(refreshed, "clear should rebuild the jump path.");
        assertTrue(refreshed.getStepVector().stream().anyMatch(step -> step.getType() == MoveStepType.DEPLOY),
                   "clear should retain the deployment step before a jump move is reinitialized.");
    }

    @Test
    @DisplayName("clear declares the jump before the deploy step so the deploy hex is a jump hex")
    void clearWithJumpGearDeclaresTheJumpBeforeTheDeployStep() throws Exception {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setPosition(new Coords(0, 0));
        mek.setBoardId(game.getBoard().getBoardId());
        game.addEntity(mek);
        movementDisplay.currentEntity = mek.getId();

        MovePath jumpPath = new MovePath(game, mek);
        jumpPath.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", jumpPath);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_JUMP);

        movementDisplay.clear();

        MovePath refreshed = (MovePath) readField(movementDisplay, "cmd");
        assertNotNull(refreshed, "clear should rebuild the jump path.");
        assertEquals(MoveStepType.START_JUMP, refreshed.getStep(0).getType(),
              "The jump must be declared before the deploy step, as it leads every other jump path.");
        assertEquals(MoveStepType.DEPLOY, refreshed.getStep(1).getType(),
              "The deploy step follows the jump declaration.");
        assertTrue(refreshed.getStep(1).isJumping(),
              "A deploy step compiled after the jump declaration is a jump hex, costing 1 MP rather than terrain.");
    }

    @Test
    @DisplayName("clear preserves mechanical booster subgear for a deployed jump path")
    void clearWithMechanicalBoosterJumpPreservesSubGear() throws Exception {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setPosition(new Coords(0, 0));
        mek.setBoardId(game.getBoard().getBoardId());
        game.addEntity(mek);
        movementDisplay.currentEntity = mek.getId();

        MovePath jumpPath = new MovePath(game, mek);
        jumpPath.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", jumpPath);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_JUMP);
        setField(movementDisplay, "jumpSubGear", MovementDisplay.GEAR_SUB_MEK_BOOSTERS);

        movementDisplay.clear();

        assertEquals(MovementDisplay.GEAR_SUB_MEK_BOOSTERS, readField(movementDisplay, "jumpSubGear"),
                     "clear should preserve the selected mechanical booster subgear.");
        MovePath refreshed = (MovePath) readField(movementDisplay, "cmd");
        assertNotNull(refreshed, "clear should rebuild the jump path.");
        assertEquals(MoveStepType.START_JUMP, refreshed.getStep(0).getType());
        assertEquals(MoveStepType.JUMP_MEK_MECHANICAL_BOOSTER, refreshed.getStep(1).getType());
        assertEquals(MoveStepType.DEPLOY, refreshed.getStep(2).getType());
    }

    @Test
    @DisplayName("movement path rebuild keeps a deploy step for walk-on deployments")
    void clearRebuildsWalkOnMovementPathWithDeployStep() throws Exception {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setPosition(new Coords(0, 0));
        mek.setBoardId(game.getBoard().getBoardId());
        game.addEntity(mek);
        movementDisplay.currentEntity = mek.getId();

        MovePath walkPath = new MovePath(game, mek);
        walkPath.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", walkPath);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);

        movementDisplay.clear();

        MovePath refreshed = (MovePath) readField(movementDisplay, "cmd");
        assertNotNull(refreshed, "clear should rebuild the walk-on movement path.");
        assertTrue(refreshed.getStepVector().stream().anyMatch(step -> step.getType() == MoveStepType.DEPLOY),
                   "clear should preserve the deploy step when rebuilding a walk-on movement path.");
        assertTrue(mek.isDeployed(), "clear should keep the deployment anchor active for walk-on movement.");
    }

    @Test
    @DisplayName("clear keeps aerospace deployment at altitude 4, and a following UP raises it to 5")
    void clearWithAerospaceDeploymentPathCanRiseFromAltitudeFour() throws Exception {
        Game spiedGame = spy(game);
        when(spiedGame.getEntitiesVector(any(Coords.class))).thenReturn(List.of());
        setGameField(movementDisplay, spiedGame);

        AeroSpaceFighter fighter = new AeroSpaceFighter();
        fighter.setGame(game);
        fighter.setPosition(new Coords(0, 0));
        fighter.setBoardId(game.getBoard().getBoardId());
        fighter.setAltitude(4);
        game.addEntity(fighter);
        movementDisplay.currentEntity = fighter.getId();

        MovePath deployedPath = new MovePath(game, fighter);
        deployedPath.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", deployedPath);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);

        Method clearMethod = MovementDisplay.class.getDeclaredMethod("clear", boolean.class);
        clearMethod.setAccessible(true);
        clearMethod.invoke(movementDisplay, true);

        MovePath refreshed = (MovePath) readField(movementDisplay, "cmd");
        assertNotNull(refreshed, "clear should rebuild the aerospace deployment path.");
        assertEquals(4, refreshed.getFinalAltitude(),
                     "clear(true) must preserve a deployed aerospace unit at altitude 4.");

        refreshed.addStep(MoveStepType.UP);

        assertEquals(5, refreshed.getFinalAltitude(),
                     "A deployed aerospace unit cleared with clear(true) should rise from altitude 4 to 5 with UP.");
    }

    @Test
    @DisplayName("deployment position keeps the current facing when all facings remain valid")
    void determineDeploymentPositionPreservesCurrentFacingWhenFacingsAreValid() {
        Board board = new Board(7, 7);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }

        BipedMek unit = new BipedMek();
        unit.setGame(game);
        unit.setStartingPos(Board.START_EDGE);
        unit.setFacing(3);
        Coords deploymentCoords = new Coords(0, 0);

        var result = new DeploymentHelper(mockClientGUI)
                .determineDeploymentPosition(unit, deploymentCoords, board, new HashSet<>(), null);

        assertNotNull(result, "A valid deployment should return a facing.");
        assertEquals(unit.getFacing(), result.facing(),
                     "Deployment helpers keep the unit's current facing when no facing restriction is applied.");
    }

    @Test
    @DisplayName("center deployment keeps the current facing when all facings remain valid")
    void determineDeploymentPositionKeepsCurrentFacingForCenterDeployment() {
        Board board = new Board(7, 7);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }

        BipedMek unit = new BipedMek();
        unit.setGame(game);
        unit.setStartingPos(Board.START_CENTER);
        unit.setFacing(5);
        Coords deploymentCoords = new Coords(0, 0);

        var result = new DeploymentHelper(mockClientGUI)
                .determineDeploymentPosition(unit, deploymentCoords, board, new HashSet<>(), null);

        assertNotNull(result, "A valid center deployment should return a facing.");
        assertEquals(unit.getFacing(), result.facing(),
                     "Center deployments do not force a facing override when all facings remain valid.");
    }

    @Test
    @DisplayName("a VTOL only placed on its entry hex rises and lowers its entry elevation for free")
    void entryHeightChangesTheDeployElevationForFree() throws Exception {
        VTOL vtol = new VTOL();
        vtol.setMovementMode(EntityMovementMode.VTOL);
        vtol.setGame(game);
        vtol.setPosition(new Coords(0, 0));
        vtol.setBoardId(game.getBoard().getBoardId());
        vtol.setDeployed(true);
        vtol.setElevation(0);
        game.addEntity(vtol);
        movementDisplay.currentEntity = vtol.getId();
        MovePath placed = new MovePath(game, vtol);
        placed.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", placed);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);
        setField(movementDisplay, "unloadableUnits", new java.util.ArrayList<>());
        setField(movementDisplay, "towedUnits", new java.util.ArrayList<>());
        int entryCost = placed.getMpUsed();

        assertTrue(changeEntryHeight(vtol, true), "A bare placement chooses its entry height.");
        assertTrue(changeEntryHeight(vtol, true), "A VTOL may enter at any elevation above the hex.");

        MovePath raised = (MovePath) readField(movementDisplay, "cmd");
        assertEquals(2, vtol.getElevation());
        assertEquals(2, raised.getFinalElevation(), "The deploy step enters at the chosen elevation.");
        assertEquals(entryCost, raised.getMpUsed(), "Choosing the entry elevation costs no MP (Core Rulebook p. 224).");
        assertEquals(1, raised.length(), "The path stays a bare placement.");

        assertTrue(changeEntryHeight(vtol, false));
        assertEquals(1, ((MovePath) readField(movementDisplay, "cmd")).getFinalElevation());

        MovePath moved = ((MovePath) readField(movementDisplay, "cmd")).clone();
        moved.addStep(MoveStepType.FORWARDS);
        setField(movementDisplay, "cmd", moved);
        assertFalse(changeEntryHeight(vtol, true), "Once the unit moves, Go up plots a step that costs MP.");
    }

    @Test
    @DisplayName("a walk-on submarine may choose to enter at the surface or submerged, but not on the seafloor")
    void submarineEntryHeightSkipsTheSeafloor() throws Exception {
        for (int x = 0; x < game.getBoard().getWidth(); x++) {
            for (int y = 0; y < game.getBoard().getHeight(); y++) {
                game.getBoard().getHex(x, y).addTerrain(new megamek.common.units.Terrain(
                      megamek.common.units.Terrains.WATER, 2));
            }
        }
        ConvInfantry scuba = new ConvInfantry();
        scuba.setMovementMode(EntityMovementMode.SUBMARINE);
        scuba.setOriginalJumpMP(3);
        scuba.setGame(game);
        scuba.setBoardId(game.getBoard().getBoardId());
        scuba.setDeployed(true);
        scuba.setElevation(-1);
        game.addEntity(scuba);
        scuba.setPosition(new Coords(0, 1));
        movementDisplay.currentEntity = scuba.getId();
        MovePath placed = new MovePath(game, scuba);
        placed.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", placed);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);
        setField(movementDisplay, "unloadableUnits", new java.util.ArrayList<>());
        setField(movementDisplay, "towedUnits", new java.util.ArrayList<>());

        assertTrue(changeEntryHeight(scuba, false));
        assertEquals(-1, scuba.getElevation(), "On the seafloor a submarine may only rise (TW p. 56): no entry there.");
        assertTrue(changeEntryHeight(scuba, true));
        assertEquals(0, scuba.getElevation(), "A submarine may enter surfaced.");
        assertTrue(((MovePath) readField(movementDisplay, "cmd")).isMoveLegal());
    }

    @Test
    @DisplayName("a walk-on unit chooses among the heights it could deploy at there, as deployment offers them")
    void entryHeightsAreTheDeploymentHeights() throws Exception {
        VTOL vtol = new VTOL();
        vtol.setMovementMode(EntityMovementMode.VTOL);
        vtol.setGame(game);
        vtol.setPosition(new Coords(0, 0));
        vtol.setBoardId(game.getBoard().getBoardId());
        vtol.setDeployed(true);
        vtol.setElevation(0);
        game.addEntity(vtol);
        placeForEntry(vtol);

        List<Integer> deployment = DeploymentHelper.placementHeights(vtol, game.getBoard(), vtol.getPosition(), game);
        assertTrue(deployment.size() > 1, "A VTOL has heights to choose from.");
        assertEquals(deployment, movementDisplay.entryHeights(), "Walk-on and deployment offer the same heights.");

        assertTrue(movementDisplay.setEntryHeight(3));
        assertEquals(3, vtol.getElevation());
        assertEquals(3, ((MovePath) readField(movementDisplay, "cmd")).getFinalElevation());
        assertFalse(movementDisplay.setEntryHeight(-1), "A height it may not deploy at is refused.");
        assertEquals(3, vtol.getElevation());
    }

    @Test
    @DisplayName("a Mek walking on onto ice may enter on the ice or on the bottom, as it may deploy")
    void mekOnIceChoosesItsEntryHeight() throws Exception {
        Hex ice = game.getBoard().getHex(0, 0);
        ice.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.WATER, 2));
        ice.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.ICE, 1));
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setPosition(new Coords(0, 0));
        mek.setBoardId(game.getBoard().getBoardId());
        mek.setDeployed(true);
        game.addEntity(mek);
        placeForEntry(mek);

        List<Integer> deployment = DeploymentHelper.placementHeights(mek, game.getBoard(), mek.getPosition(), game);
        assertEquals(deployment, movementDisplay.entryHeights());
        assertTrue(deployment.contains(0) && deployment.contains(-2), "On the ice or on the bottom: " + deployment);
    }

    @Test
    @DisplayName("a walk-on submarine's entry heights are its deployment heights but the seafloor")
    void submarineEntryHeightsLeaveOutTheSeafloor() throws Exception {
        for (int x = 0; x < game.getBoard().getWidth(); x++) {
            for (int y = 0; y < game.getBoard().getHeight(); y++) {
                game.getBoard().getHex(x, y).addTerrain(new megamek.common.units.Terrain(
                      megamek.common.units.Terrains.WATER, 2));
            }
        }
        ConvInfantry scuba = new ConvInfantry();
        scuba.setMovementMode(EntityMovementMode.SUBMARINE);
        scuba.setOriginalJumpMP(3);
        scuba.setGame(game);
        scuba.setBoardId(game.getBoard().getBoardId());
        scuba.setDeployed(true);
        scuba.setElevation(-1);
        game.addEntity(scuba);
        scuba.setPosition(new Coords(0, 1));
        placeForEntry(scuba);

        List<Integer> deployment = DeploymentHelper.placementHeights(scuba, game.getBoard(), scuba.getPosition(), game);
        assertTrue(deployment.contains(-2), "It may deploy on the seafloor: " + deployment);
        List<Integer> expected = deployment.stream().filter(height -> height != -2).toList();
        assertEquals(expected, movementDisplay.entryHeights(), "It may not enter there (TW p. 56).");
    }

    /** Makes {@code unit} the selected walk-on unit, only placed on its entry hex. */
    private void placeForEntry(Entity unit) throws Exception {
        movementDisplay.currentEntity = unit.getId();
        MovePath placed = new MovePath(game, unit);
        placed.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", placed);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);
        setField(movementDisplay, "unloadableUnits", new java.util.ArrayList<>());
        setField(movementDisplay, "towedUnits", new java.util.ArrayList<>());
    }

    @Test
    @DisplayName("the envelope of a UMU unit searches as far as its UMU MP, which may exceed its ground MP")
    void envelopeSearchesUmuMp() throws Exception {
        ConvInfantry scuba = new ConvInfantry();
        scuba.setMovementMode(EntityMovementMode.INF_UMU);
        scuba.setOriginalJumpMP(3);
        scuba.setGame(game);
        game.addEntity(scuba);
        movementDisplay.currentEntity = scuba.getId();

        Method maxMP = MovementDisplay.class.getDeclaredMethod("maxMP", Entity.class, int.class);
        maxMP.setAccessible(true);
        assertEquals(3, maxMP.invoke(movementDisplay, scuba, MovementDisplay.GEAR_LAND));
    }

    @Test
    @DisplayName("a walk-on submarine infantry's envelope holds every water hex its UMU MP reaches after entering")
    void walkOnSubmarineInfantryEnvelopeReachesWithUmuMp() throws Exception {
        for (int x = 0; x < game.getBoard().getWidth(); x++) {
            for (int y = 0; y < game.getBoard().getHeight(); y++) {
                game.getBoard().getHex(x, y).addTerrain(new megamek.common.units.Terrain(
                      megamek.common.units.Terrains.WATER, 2));
            }
        }
        ConvInfantry scuba = new ConvInfantry();
        scuba.setMovementMode(EntityMovementMode.SUBMARINE);
        scuba.setOriginalJumpMP(3);
        scuba.setGame(game);
        scuba.setBoardId(game.getBoard().getBoardId());
        scuba.setDeployed(true);
        scuba.setElevation(-1);
        game.addEntity(scuba);
        scuba.setPosition(new Coords(0, 1));
        movementDisplay.currentEntity = scuba.getId();
        MovePath placed = new MovePath(game, scuba);
        placed.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", placed);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);

        movementDisplay.computeMovementEnvelope(placed);

        Map<Coords, Integer> reach = movementDisplay.getLastEnvelope().mp();
        assertTrue(reach.containsKey(new Coords(1, 1)), "One hex in: " + reach);
        assertTrue(reach.containsKey(new Coords(2, 1)), "Two hexes in, 3 UMU MP with the entry: " + reach);
    }

    @Test
    @DisplayName("the envelope holds a hex whose only legal paths arrive without turning, as on a seafloor")
    void envelopeKeepsAHexReachedOnlyWithoutTurningOnTheSeafloor() throws Exception {
        for (int x = 0; x < game.getBoard().getWidth(); x++) {
            for (int y = 0; y < game.getBoard().getHeight(); y++) {
                game.getBoard().getHex(x, y).addTerrain(new megamek.common.units.Terrain(
                      megamek.common.units.Terrains.WATER, ((x == 2) && (y == 1)) ? 1 : 2));
            }
        }
        ConvInfantry scuba = new ConvInfantry();
        scuba.setMovementMode(EntityMovementMode.SUBMARINE);
        scuba.setOriginalJumpMP(3);
        scuba.setGame(game);
        scuba.setBoardId(game.getBoard().getBoardId());
        scuba.setDeployed(true);
        scuba.setElevation(-1);
        game.addEntity(scuba);
        scuba.setPosition(new Coords(0, 1));
        movementDisplay.currentEntity = scuba.getId();
        MovePath placed = new MovePath(game, scuba);
        placed.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", placed);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);

        movementDisplay.computeMovementEnvelope(placed);

        // At depth 1 the unit at -1 sits on the seafloor, where it may not turn: only arriving facing straight is legal
        assertEquals(3, movementDisplay.getLastEnvelope().mp().get(new Coords(2, 1)),
              "Seafloor hex two in: " + movementDisplay.getLastEnvelope().mp());
    }

    @Test
    @DisplayName("a walk-on QuadVee placed on its entry hex converts to vehicle mode and back, keeping its placement")
    void walkOnQuadVeeConvertsAfterPlacement() throws Exception {
        QuadVee quadVee = new QuadVee();
        quadVee.setGame(game);
        quadVee.setBoardId(game.getBoard().getBoardId());
        quadVee.setDeployed(true);
        game.addEntity(quadVee);
        quadVee.setPosition(new Coords(0, 1));
        movementDisplay.currentEntity = quadVee.getId();
        MovePath placed = new MovePath(game, quadVee);
        placed.addStep(MoveStepType.DEPLOY);
        setField(movementDisplay, "cmd", placed);
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);
        setField(movementDisplay, "unloadableUnits", new java.util.ArrayList<>());
        setField(movementDisplay, "towedUnits", new java.util.ArrayList<>());

        invoke("updateConvertModeButton");
        assertTrue(movementDisplay.isConvertEnabled(), "A unit only placed on its entry hex may still convert.");

        adjustConvertSteps(EntityMovementMode.TRACKED);
        MovePath converted = (MovePath) readField(movementDisplay, "cmd");
        assertEquals(List.of(MoveStepType.DEPLOY, MoveStepType.CONVERT_MODE),
              converted.getStepVector().stream().map(step -> step.getType()).toList());
        assertEquals(EntityMovementMode.TRACKED, converted.getFinalConversionMode());
        assertTrue(quadVee.isConvertingNow());
        invoke("updateConvertModeButton");
        assertTrue(movementDisplay.isConvertEnabled(), "A planned conversion may still change its mode.");

        adjustConvertSteps(EntityMovementMode.QUAD);
        MovePath back = (MovePath) readField(movementDisplay, "cmd");
        assertEquals(List.of(MoveStepType.DEPLOY), back.getStepVector().stream().map(step -> step.getType()).toList(),
              "Back in its starting mode, the unit keeps only its placement.");
        assertFalse(quadVee.isConvertingNow());

        back.addStep(MoveStepType.FORWARDS);
        invoke("updateConvertModeButton");
        assertFalse(movementDisplay.isConvertEnabled(), "A unit that has moved converts no more.");
    }

    @Test
    @DisplayName("a Mek with tracks takes its tracks and its legs again, and a restored plan converts it as before")
    void trackedMekConvertsBackToLegs() throws Exception {
        BipedMek mek = new BipedMek();
        mek.addEquipment(MiscType.createTracks(), Mek.LOC_RIGHT_LEG);
        mek.addEquipment(MiscType.createTracks(), Mek.LOC_LEFT_LEG);
        mek.setGame(game);
        mek.setBoardId(game.getBoard().getBoardId());
        game.addEntity(mek);
        mek.setPosition(new Coords(1, 1));
        movementDisplay.currentEntity = mek.getId();
        setField(movementDisplay, "cmd", new MovePath(game, mek));
        setField(movementDisplay, "gear", MovementDisplay.GEAR_LAND);
        setField(movementDisplay, "unloadableUnits", new java.util.ArrayList<>());
        setField(movementDisplay, "towedUnits", new java.util.ArrayList<>());
        assertTrue(mek.hasTracks());

        adjustConvertSteps(EntityMovementMode.TRACKED);
        MovePath tracked = ((MovePath) readField(movementDisplay, "cmd")).clone();
        assertEquals(EntityMovementMode.TRACKED, tracked.getFinalConversionMode());
        assertEquals(EntityMovementMode.TRACKED, mek.getMovementMode(), "The Mek plans on its tracks.");

        adjustConvertSteps(EntityMovementMode.BIPED);
        MovePath legs = (MovePath) readField(movementDisplay, "cmd");
        assertFalse(legs.contains(MoveStepType.CONVERT_MODE), "Back on its legs, the Mek doesn't convert.");
        assertEquals(EntityMovementMode.BIPED, mek.getMovementMode());
        assertFalse(mek.isConvertingNow());

        movementDisplay.plotPath(tracked);
        MovePath restored = (MovePath) readField(movementDisplay, "cmd");
        assertEquals(EntityMovementMode.TRACKED, restored.getFinalConversionMode(),
              "A restored conversion compiles from the starting mode.");
        assertEquals(EntityMovementMode.TRACKED, mek.getMovementMode());
        assertTrue(mek.isConvertingNow());

        movementDisplay.clear();
        assertEquals(EntityMovementMode.BIPED, mek.getMovementMode(), "Clear takes the conversion back.");
        assertFalse(mek.isConvertingNow());
    }

    private void adjustConvertSteps(EntityMovementMode mode) throws Exception {
        Method method = MovementDisplay.class.getDeclaredMethod("adjustConvertSteps", EntityMovementMode.class);
        method.setAccessible(true);
        method.invoke(movementDisplay, mode);
    }

    private void invoke(String name) throws Exception {
        Method method = MovementDisplay.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(movementDisplay);
    }

    private boolean changeEntryHeight(Entity entity, boolean up) throws Exception {
        Method method = MovementDisplay.class.getDeclaredMethod("changeEntryHeight", Entity.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(movementDisplay, entity, up);
    }

    private static void setField(Object target,
                                 String fieldName,
                                 Object value) throws Exception {
        Field field = MovementDisplay.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setGameField(MovementDisplay target,
                                    Game value) throws Exception {
        Field field = ActionPhaseDisplay.class.getDeclaredField("game");
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object readField(Object target,
                                    String fieldName) throws Exception {
        Field field = MovementDisplay.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
