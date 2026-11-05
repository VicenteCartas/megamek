/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.event.BoardViewEvent;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuMovePlan.Band;
import megamek.client.ui.clientGUI.boardview.gpu.GpuMovePlan.Mode;
import megamek.client.ui.clientGUI.boardview.gpu.GpuMovePlan.Snapshot;
import megamek.client.ui.clientGUI.boardview.gpu.GpuMovePlan.Step;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.BasementType;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscType;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.moves.MoveStep;
import megamek.common.rules.RulesManager;
import megamek.common.rules.core.CoreRulesManager;
import megamek.common.rules.totalwarfare.TWRulesManager;
import megamek.common.units.BuildingTerrain;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.IAero;
import megamek.common.units.Tank;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The movement plan on a real MovementDisplay (GpuMovementFixture: a Sagittaire with walk 3, run 5 and jump 3 at
 * (11, 11) facing north): the prototype's interaction-test.mjs lines 33-52 in MegaMek terms, the cheaper re-route of
 * a turn, the explicit modes, a path changed elsewhere, the undo limit, a unit switch, a non-planner aerospace unit,
 * the display's own hex picks (an escape pod landing, a bridge build) and the hover route searched after the capture;
 * and the hold of all remaining units over scripted server turns (the fixture's Atlas is the second own unit).
 * Commands go through the source as the HUD sends them.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuMovePlanTest {
    /** The fixture's local player (GpuBoardFixture) and the opponent the hold tests add, as turn owners. */
    private static final int LOCAL = 0;
    private static final int OPPONENT = 1;
    private static final Coords NORTH_1 = new Coords(11, 10);
    private static final Coords NORTH_2 = new Coords(11, 9);
    private static final Coords NORTH_3 = new Coords(11, 8);
    /** Four hexes north: 4 MP, running. */
    private static final Coords NORTH_4 = new Coords(11, 7);
    /** Five hexes north, on a level 1 hill: 6 MP, beyond the run MP. */
    private static final Coords NORTH_5 = new Coords(11, 6);
    private static final Coords NORTH_EAST = new Coords(12, 11);
    /** North-east of the hex north of the unit. */
    private static final Coords AHEAD_RIGHT = new Coords(12, 10);
    /** Rough ground two hexes north-east: 5 MP, the run MP. */
    private static final Coords ROUGH = new Coords(12, 9);
    /** The escape pod vehicle's clear hex, facing north, and the clear hex right behind it, in its rear arc. */
    private static final Coords TANK = new Coords(13, 9);
    private static final Coords BEHIND_TANK = new Coords(13, 10);
    /** The bridge engineers' clear hex, with water to the south and the south-west, and the clear hex north of it. */
    private static final Coords ENGINEERS = new Coords(10, 10);
    private static final Coords NORTH_OF_ENGINEERS = new Coords(10, 9);
    /** The water south of the engineers: a hex a bridge can occupy. */
    private static final Coords BRIDGE_HEX = new Coords(10, 11);

    @ParameterizedTest
    @ValueSource(strings = { "CLMASC", "Supercharger", "both", "ISVTOLJetBooster" })
    void onlyBoostedStepsChangeBandInPlottedAndHoverRoutes(String booster) throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            boolean jet = booster.equals("ISVTOLJetBooster");
            Entity unit = new MekFileParser(new File("testresources/megamek/common/units/"
                  + (jet ? "Cobra Transport VTOL.blk" : "Charger C.mtf"))).getEntity();
            Coords start = new Coords(3, 16);
            onSwing(() -> {
                for (int y = 0; y <= start.getY(); y++) {
                    moving.board.game.getBoard().setHex(new Coords(start.getX(), y), new Hex());
                }
                if (jet) {
                    unit.addEquipment(EquipmentType.get(booster), Tank.LOC_BODY);
                    unit.setElevation(1);
                } else if (!booster.equals("both")) {
                    unit.getMisc().stream().filter(mount -> mount.getType().hasFlag(MiscType.F_MASC)
                          && !mount.getType().getInternalName().equals(booster))
                          .forEach(mount -> mount.setModeImmediately("Off"));
                }
                return null;
            });
            select(moving, unit, 7, start);
            GpuMovePlan plan = plan(moving);
            int walk = unit.getWalkMP();
            int run = unit.getRunMPWithoutMASC();
            int boosted = unit.getRunMP();
            assertTrue(boosted > run, "The fixture must require its booster beyond ordinary running");
            Coords destination = start.translated(0, boosted);
            hoverAfterCapture(moving.board.source, destination);
            List<Step> preview = shown(moving).hover();
            assertEquals(boosted, preview.size());
            plan.planTo(destination, 0, false);
            Snapshot plotted = shown(moving);
            assertEquals(preview, plotted.route(), "Hover and plotted routes share the boost transition");
            assertTrue(plotted.legal());
            for (int i = 0; i < boosted; i++) {
                assertEquals(i < walk ? Band.WALK : i < run ? Band.RUN : Band.BOOST,
                      plotted.route().get(i).band(), "Band at MP " + (i + 1));
            }
            onSwing(() -> {
                MovePath path = moving.display.getPlannedMovement();
                assertEquals(!booster.equals("Supercharger"), path.getLastStep().isUsingMASC());
                assertEquals(booster.equals("Supercharger") || booster.equals("both"),
                      path.getLastStep().isUsingSupercharger());
                path.addStep(MoveStepType.TURN_LEFT);
                moving.display.plotPath(path);
                return null;
            });
            assertEquals(Band.ILLEGAL, shown(moving).route().getLast().band(),
                  "An illegal step must keep its illegal band even after a boosted section");
        }
    }

    @Test
    void backwardsStepsUseTheirOwnColorBand() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            plan(moving);
            onSwing(() -> {
                Coords behind = GpuMovementFixture.START.translated(3);
                moving.board.game.getBoard().setHex(behind, new Hex());
                MovePath path = new MovePath(moving.board.game, moving.unit).addStep(MoveStepType.BACKWARDS);
                assertTrue(path.isMoveLegal());
                moving.display.plotPath(path);
                return null;
            });
            assertEquals(Band.BACK, shown(moving).route().getLast().band());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void backwardsWaypointCanTurnLeftAndContinueForward(boolean turnBeforeToggle) throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Coords behind = GpuMovementFixture.START.translated(3, 2);
            Coords destination = behind.translated(5);
            onSwing(() -> {
                moving.unit.setOriginalWalkMP(6);
                for (Coords coords : List.of(GpuMovementFixture.START.translated(3), behind, destination)) {
                    moving.board.game.getBoard().setHex(coords, new Hex());
                }
                return null;
            });
            GpuMovePlan plan = plan(moving);
            plan.setMode(Mode.BACK);
            plan.planTo(behind, 0, true);
            Snapshot backed = shown(moving);
            assertEquals(List.of(Band.BACK, Band.BACK), backed.route().stream().map(Step::band).toList());
            assertEquals(List.of(behind), pinHexes(backed));

            if (turnBeforeToggle) {
                plan.turn(-1);
            }
            Snapshot beforeToggle = shown(moving);
            plan.setMode(Mode.BACK);
            Snapshot forward = shown(moving);
            assertEquals(Mode.AUTO, forward.mode());
            assertEquals(beforeToggle.route(), forward.route(), "Changing direction keeps the planned steps");
            assertEquals(beforeToggle.pins(), forward.pins(), "Changing direction keeps the waypoints");
            if (!turnBeforeToggle) {
                plan.turn(-1);
            }
            Snapshot turned = shown(moving);
            assertEquals(5, turned.facing());
            assertEquals(5, turned.pins().getLast().facing(), "The waypoint retains its chosen facing");

            plan.planTo(destination, 0, false);
            Snapshot continued = shown(moving);
            assertEquals(turned.route(), continued.route().subList(0, turned.route().size()));
            assertEquals(List.of(MoveStepType.BACKWARDS, MoveStepType.BACKWARDS, MoveStepType.TURN_LEFT,
                  MoveStepType.FORWARDS), onSwing(() -> moving.display.getPlannedMovement().getStepVector().stream()
                  .map(MoveStep::getType).toList()));
            assertEquals(destination, continued.destination());
            assertEquals(4, continued.cost());
            assertEquals(EntityMovementType.MOVE_WALK, continued.type());
            assertTrue(continued.legal());
            assertEquals(turned.pins(), continued.pins());

            plan.undo();
            assertEquals(turned.route(), shown(moving).route());
            // Undo the turn and direction change in either order, including the waypoint's original facing.
            plan.undo();
            plan.undo();
            Snapshot undone = shown(moving);
            assertEquals(Mode.BACK, undone.mode());
            assertEquals(backed.route(), undone.route());
            assertEquals(backed.pins(), undone.pins());
            verify(moving.gui, never()).addToast(any(), anyString());
        }
    }

    @Test
    void directionChangesKeepPinnedAndDraftStepsAndCanBeUndoneOrCleared() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            onSwing(() -> {
                moving.board.game.getBoard().setHex(GpuMovementFixture.START, new Hex());
                return null;
            });
            GpuMovePlan plan = plan(moving);
            plan.planTo(NORTH_1, 0, true);
            plan.planTo(NORTH_2, 0, false);
            Snapshot before = shown(moving);
            plan.setMode(Mode.BACK);
            Snapshot backwards = shown(moving);
            assertEquals(Mode.BACK, backwards.mode());
            assertEquals(before.route(), backwards.route());
            assertEquals(before.pins(), backwards.pins());

            // The new backward leg replaces only the unpinned draft after the forward waypoint.
            plan.planTo(GpuMovementFixture.START, 0, true);
            Snapshot mixed = shown(moving);
            assertEquals(List.of(Band.WALK, Band.BACK), mixed.route().stream().map(Step::band).toList());
            assertEquals(List.of(NORTH_1, GpuMovementFixture.START), pinHexes(mixed));
            assertEquals(2, mixed.cost());
            plan.setMode(Mode.BACK);
            assertEquals(mixed.route(), shown(moving).route());
            plan.planTo(NORTH_1, 0, false);
            Snapshot continued = shown(moving);
            assertEquals(List.of(Band.WALK, Band.BACK, Band.WALK),
                  continued.route().stream().map(Step::band).toList());
            assertEquals(3, continued.cost());
            assertTrue(continued.legal());

            plan.clearRoute();
            Snapshot cleared = shown(moving);
            assertTrue(cleared.route().isEmpty() && cleared.pins().isEmpty());
            plan.undo();
            assertEquals(continued.route(), shown(moving).route());
            assertEquals(continued.pins(), shown(moving).pins());

            plan.setMode(Mode.JUMP);
            Snapshot jumping = shown(moving);
            assertEquals(Mode.JUMP, jumping.mode());
            assertTrue(jumping.route().isEmpty() && jumping.pins().isEmpty());
            plan.undo();
            assertEquals(Mode.AUTO, shown(moving).mode());
            assertEquals(continued.route(), shown(moving).route());
            assertEquals(continued.pins(), shown(moving).pins());
        }
    }

    /** interaction-test.mjs 33-52: automatic walk and run, undo, explicit walk and back, a pin, its continuation, X. */
    @Test
    void plotsPinsContinuesUndoesTurnsAndChangesModeAsThePrototype() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            // Line 33: a click plots automatic walking; the Walk button is outlined (the route's band), not pressed.
            plan.planTo(NORTH_2, 0, false);
            Snapshot walk = shown(moving);
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK"), route(walk.route()));
            assertTrue(walk.active() && walk.planner() && walk.auto() && !walk.explicit() && !walk.external());
            assertEquals(Mode.AUTO, walk.mode());
            assertEquals(EntityMovementType.MOVE_WALK, walk.type());
            assertEquals(Messages.getString("GpuBoard.hud.move.walk"), walk.typeLabel());
            assertEquals(List.of(2, 3, 0), List.of(walk.cost(), walk.budget(), walk.facing()));
            assertEquals(NORTH_2, walk.destination());
            assertEquals(26, walk.envelope().size(), "The unit's envelope (GpuMoveEnvelopeCharacterizationTest)");

            // Line 36: a longer route runs.
            plan.planTo(NORTH_4, 0, false);
            Snapshot run = shown(moving);
            assertEquals(Band.RUN, run.route().getLast().band());
            assertEquals(EntityMovementType.MOVE_RUN, run.type());
            assertEquals(Messages.getString("GpuBoard.hud.move.run"), run.typeLabel());
            assertEquals(List.of(4, 5), List.of(run.cost(), run.budget()));
            assertTrue(run.auto());

            // Line 38: Backspace restores the walking draft.
            plan.undo();
            assertEquals(route(walk.route()), route(shown(moving).route()));

            // Line 41: 1 constrains to walking: pressed, the route cleared, the run band hidden.
            plan.setMode(Mode.WALK);
            Snapshot walking = shown(moving);
            assertEquals(Mode.WALK, walking.mode());
            assertTrue(walking.explicit() && !walking.auto());
            assertEquals(List.of(), walking.route());
            assertEquals(Set.of(Band.WALK), Set.copyOf(walking.envelope().values()));

            // Line 43: 1 again is automatic walking and running.
            plan.setMode(Mode.WALK);
            Snapshot automatic = shown(moving);
            assertEquals(Mode.AUTO, automatic.mode());
            assertFalse(automatic.explicit());
            assertEquals(Set.of(Band.WALK, Band.RUN), Set.copyOf(automatic.envelope().values()));

            // Ctrl+click pins a waypoint.
            plan.planTo(NORTH_1, 0, true);
            Snapshot pinned = shown(moving);
            assertEquals(List.of(NORTH_1), pinHexes(pinned));
            assertEquals(List.of("11,10 f0 WALK"), route(pinned.route()));

            // Line 50: the next click continues from the waypoint with the MP so far (from the unit it costs 2 MP),
            // and the envelope is the waypoint's, with the MP left.
            plan.planTo(NORTH_EAST, 0, false);
            Snapshot continued = shown(moving);
            assertEquals(List.of("11,10 f0 WALK", "11,10 f1 WALK", "11,10 f2 WALK", "12,11 f2 RUN"),
                  route(continued.route()));
            assertEquals(List.of(NORTH_1), pinHexes(continued));
            assertEquals(4, continued.cost());
            assertEquals(17, continued.envelope().size());

            // Line 52: X turns the final facing.
            plan.turn(1);
            Snapshot turned = shown(moving);
            assertEquals((continued.facing() + 1) % 6, turned.facing());
            assertEquals(NORTH_EAST, turned.destination());
            assertEquals(5, turned.cost());
        }
    }

    /** G9: the facing is the cheaper legal one of a re-route ending in it and a turn at the destination. */
    @Test
    void turningReRoutesWhenThatCostsLessThanTurningAtTheDestination() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            // Rough ground at the run MP: turning either way there is beyond it, and so is every re-route.
            plan.planTo(ROUGH, 0, false);
            Snapshot rough = shown(moving);
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK", "11,9 f1 WALK", "12,9 f1 RUN"), route(rough.route()));
            plan.turn(1);
            plan.turn(-1);
            assertEquals(route(rough.route()), route(shown(moving).route()));
            verify(moving.gui, times(2)).addToast(ToastLevel.WARNING,
                  Messages.getString("GpuBoard.hud.move.noMpToFace"));
        }
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            // Light woods north of the unit: the hex ahead right costs 4 MP both through them (facing north-east)
            // and around them (facing north).
            onSwing(() -> {
                moving.board.game.getBoard().getHex(NORTH_1).addTerrain(new Terrain(Terrains.WOODS, 1));
                return null;
            });
            GpuMovePlan plan = plan(moving);
            plan.planTo(AHEAD_RIGHT, 0, false);
            Snapshot through = shown(moving);
            assertEquals(List.of("11,10 f0 WALK", "11,10 f1 WALK", "12,10 f1 RUN"), route(through.route()));
            assertEquals(4, through.cost());
            plan.turn(-1);
            Snapshot around = shown(moving);
            // Turning left at the destination would cost 5 MP.
            assertEquals(List.of("11,11 f1 WALK", "12,11 f1 WALK", "12,11 f0 WALK", "12,10 f0 RUN"),
                  route(around.route()));
            assertEquals(List.of(4, 0), List.of(around.cost(), around.facing()));
            verify(moving.gui, never()).addToast(any(), anyString());
            // Undo puts the route through the woods back.
            plan.undo();
            assertEquals(route(through.route()), route(shown(moving).route()));
        }
    }

    @Test
    void orientationKeepsTheDestinationAndPinsAndUndoesInOneStep() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            plan.planTo(NORTH_1, 0, true);
            plan.planTo(NORTH_2, 0, false);
            Snapshot before = shown(moving);

            plan.faceToward(NORTH_2.translated(2), 0);
            Snapshot oriented = shown(moving);
            assertEquals(2, oriented.facing());
            assertEquals(before.destination(), oriented.destination());
            assertEquals(before.pins(), oriented.pins());
            assertTrue(onSwing(() -> moving.display.getPlannedMovement().isMoveLegal()));

            // Same-hex and other-board clicks add no command to undo.
            plan.faceToward(NORTH_2, 0);
            plan.faceToward(NORTH_2.translated(3), 1);
            assertEquals(route(oriented.route()), route(shown(moving).route()));
            plan.undo();
            Snapshot undone = shown(moving);
            assertEquals(route(before.route()), route(undone.route()));
            assertEquals(before.pins(), undone.pins());
            verify(moving.gui, never()).addToast(any(), anyString());
        }
    }

    /** G6, G10: an explicit walk refuses a running route; Run shows its whole reach and cuts an illegal tail. */
    @Test
    void explicitModesFilterTheRouteByItsMovementType() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            plan.setMode(Mode.WALK);
            plan.planTo(NORTH_4, 0, false);
            assertEquals(List.of(), shown(moving).route());
            verify(moving.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.move.notReachable",
                  Messages.getString("GpuBoard.hud.move.walk").toUpperCase(Locale.ROOT)));
            // The refusal left no undo step: one undo takes back the mode change.
            plan.undo();
            Snapshot undone = shown(moving);
            assertEquals(Mode.AUTO, undone.mode());
            assertFalse(undone.canUndo());
            plan.setMode(Mode.WALK);
            plan.planTo(NORTH_2, 0, false);
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK"), route(shown(moving).route()));

            // Run: the walking and running reach as one run band; a walking route says it walks.
            plan.setMode(Mode.RUN);
            Snapshot running = shown(moving);
            assertEquals(List.of(), running.route(), "A mode change clears the route");
            assertEquals(Set.of(Band.RUN), Set.copyOf(running.envelope().values()));
            plan.planTo(NORTH_2, 0, false);
            assertEquals(Messages.getString("GpuBoard.hud.move.walkRunOnly"), shown(moving).typeLabel());

            // Beyond the run MP: the illegal tail is cut off, with the mode's toast.
            plan.planTo(NORTH_5, 0, false);
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK", "11,8 f0 WALK", "11,7 f0 RUN"),
                  route(shown(moving).route()));
            verify(moving.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.move.notReachable",
                  Messages.getString("GpuBoard.hud.move.run").toUpperCase(Locale.ROOT)));

            // Automatic movement names the run MP.
            plan.setMode(Mode.RUN);
            plan.planTo(NORTH_5, 0, false);
            settle();
            verify(moving.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.move.beyondRunningMp"));

            // Jump: one landing hex, no waypoints; Jump again is automatic movement.
            plan.setMode(Mode.JUMP);
            Snapshot jumping = shown(moving);
            assertEquals(Mode.JUMP, jumping.mode());
            assertEquals(Set.of(Band.JUMP), Set.copyOf(jumping.envelope().values()));
            plan.planTo(NORTH_2, 0, true);
            settle();
            verify(moving.gui).addToast(ToastLevel.WARNING, Messages.getString("GpuBoard.hud.move.jumpSingleHex"));
            plan.planTo(NORTH_2, 0, false);
            Snapshot jumped = shown(moving);
            assertEquals(Band.JUMP, jumped.route().getLast().band());
            assertFalse(jumped.canPin());
            plan.setMode(Mode.JUMP);
            assertEquals(Mode.AUTO, shown(moving).mode());
        }
    }

    /** A.7: a path changed elsewhere is kept as pinned, continued by a click and undone step by step. */
    @Test
    void aPathChangedElsewhereTurnsThePlanExternal() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            plan.planTo(NORTH_2, 0, false);
            assertFalse(shown(moving).external());
            // A Swing click extends MegaMek's path from its end.
            onSwing(() -> {
                for (int type : new int[] { BoardViewEvent.BOARD_HEX_DRAGGED, BoardViewEvent.BOARD_HEX_CLICKED }) {
                    moving.display.hexMoused(new BoardViewEvent(moving.board.view, NORTH_3, type, 0,
                          MouseEvent.BUTTON1));
                }
                return null;
            });
            Snapshot clicked = shown(moving);
            assertTrue(clicked.external() && clicked.canUndo());
            assertEquals(List.of(NORTH_3), pinHexes(clicked), "Everything so far is pinned");
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK", "11,8 f0 WALK"), route(clicked.route()));

            plan.planTo(NORTH_4, 0, false);
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK", "11,8 f0 WALK", "11,7 f0 RUN"),
                  route(shown(moving).route()));
            plan.undo();
            assertEquals(route(clicked.route()), route(shown(moving).route()), "The plan's own step is undone");
            // Then MegaMek's Backspace: one step at a time.
            plan.undo();
            Snapshot backspace = shown(moving);
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK"), route(backspace.route()));
            assertEquals(List.of(NORTH_2), pinHexes(backspace));

            // A gear chosen elsewhere (Swing's Jump command clears the path): the plan follows its mode.
            moving.command(MoveCommand.MOVE_JUMP);
            Snapshot jump = shown(moving);
            assertEquals(Mode.JUMP, jump.mode());
            assertTrue(jump.explicit());
            assertEquals(List.of(), jump.route());
            assertFalse(jump.external() || jump.canUndo());
            assertEquals(List.of(), jump.pins());
        }
    }

    /** G11: undo covers the last sixty plan commands. */
    @Test
    void undoKeepsTheLastSixtyPlanCommands() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            for (int command = 0; command <= GpuMovePlan.UNDO_LIMIT; command++) {
                plan.planTo(((command % 2) == 0) ? NORTH_1 : NORTH_2, 0, false);
            }
            assertEquals(NORTH_1, shown(moving).destination());
            for (int command = 0; command < GpuMovePlan.UNDO_LIMIT; command++) {
                plan.undo();
            }
            Snapshot oldest = shown(moving);
            assertEquals(List.of("11,10 f0 WALK"), route(oldest.route()), "The first plot; its own undo step is gone");
            assertFalse(oldest.canUndo());
            plan.undo();
            assertEquals(route(oldest.route()), route(shown(moving).route()));
        }
    }

    /** G19: switching units discards the plan, its mode and its undo steps. */
    @Test
    void switchingUnitsDiscardsThePlan() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            plan.setMode(Mode.WALK);
            plan.planTo(NORTH_1, 0, true);
            plan.planTo(NORTH_2, 0, false);
            assertEquals(List.of(NORTH_1), pinHexes(shown(moving)));
            for (Entity unit : List.of(moving.board.entity, moving.unit)) {
                onSwing(() -> {
                    moving.display.selectEntity(unit.getId());
                    return null;
                });
                Snapshot selected = shown(moving);
                assertEquals(unit.getId(), selected.entityId());
                assertEquals(Mode.AUTO, selected.mode());
                assertEquals(List.of(), selected.route());
                assertEquals(List.of(), selected.pins());
                assertFalse(selected.canUndo());
            }
        }
    }

    /** G20: a non-planner aerospace unit's route is MegaMek's path; the plan's commands leave it alone. */
    @Test
    void anAerospaceUnitsRouteIsMegaMeksPath() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            selectAirborneFighter(moving);
            onSwing(() -> {
                // MegaMek's board click (GpuBoardSource.click) plots it.
                for (int type : new int[] { BoardViewEvent.BOARD_HEX_DRAGGED, BoardViewEvent.BOARD_HEX_CLICKED }) {
                    moving.display.hexMoused(new BoardViewEvent(moving.board.view, new Coords(4, 9), type, 0,
                          MouseEvent.BUTTON1));
                }
                return null;
            });
            GpuMovePlan plan = plan(moving);
            Snapshot aero = shown(moving);
            assertTrue(aero.active());
            assertFalse(aero.planner() || aero.canPin() || aero.canUndo());
            assertEquals(Mode.OTHER, aero.mode());
            List<Coords> path = onSwing(() -> moving.display.getPlannedMovement().getStepVector().stream()
                  .map(MoveStep::getPosition).toList());
            assertEquals(List.of(new Coords(4, 11), new Coords(4, 10), new Coords(4, 9)), path);
            assertEquals(path, aero.route().stream().map(Step::coords).toList(), "The route is the path");
            assertEquals(List.of(), aero.pins());

            plan.planTo(new Coords(4, 8), 0, false);
            plan.setMode(Mode.WALK);
            assertEquals(path, shown(moving).route().stream().map(Step::coords).toList());
        }
    }

    /**
     * E2d: while the display picks the landing hex of a vehicle's escape pod, the unit is no planner and the plan's
     * commands are dropped; the board click the HUD then sends (MegaMek's board tool) launches the pod to that hex.
     */
    @Test
    void anEscapePodLandingHexIsPickedByTheBoardClickAndNotPlotted() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            // The launch ends the unit's turn, so the game gets turns (scripted, as for the hold tests).
            scriptTurns(moving, LOCAL, OPPONENT);
            Entity tank = selectEscapePodTank(moving);
            GpuMovePlan plan = plan(moving);
            assertTrue(shown(moving).planner(), "A ground vehicle is planned");
            moving.command(MoveCommand.MOVE_LAUNCH_ESCAPE_POD);
            Snapshot picking = shown(moving);
            assertTrue(picking.active());
            assertFalse(picking.planner() || picking.canPin() || picking.canUndo());
            assertEquals(Mode.OTHER, picking.mode());
            assertFalse(onSwing(moving::envelopeSprites).isEmpty(), "MegaMek's board highlights the pick's hexes");
            assertEquals(Map.of(), picking.envelope(), "and shows no movement envelope");

            // A command of an older frame is dropped: nothing is plotted and the pick still waits.
            plan.planTo(BEHIND_TANK, 0, false);
            assertEquals(List.of(), shown(moving).route());
            assertTrue(onSwing(moving.display::isSelectingHex));

            megamekClick(moving, BEHIND_TANK);
            verify(moving.client).moveEntity(eq(tank.getId()), argThat(path -> launchesPodTo(path, BEHIND_TANK)));
        }
    }

    /**
     * The user's crash report of 2026-10-03: with walk-on deployment a unit enters the board in its first movement
     * turn. Until MegaMek's click on its entry hex places it, the unit is no planner and the plan's commands are
     * dropped, so no path is searched from a unit without a position (MovePath.findPathTo threw). Once placed, it is
     * planned from the entry hex, and the placement stays at the start of the path as a jump's start does.
     */
    @Test
    void aWalkOnUnitEntersByTheBoardClickAndIsPlannedFromItsEntryHex() throws Exception {
        RulesManager rules = Game.rulesManager;
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Game.rulesManager = new TWRulesManager();
            Game.rulesManager.getRulesGame().setWalkOnDeployment(true);
            // The south edge of the 16 x 17 board, where the unit walks on.
            Coords entry = new Coords(11, 16);
            Coords inland = new Coords(11, 14);
            Entity walker = new MekFileParser(new File("testresources/megamek/common/units/Sagittaire SGT-14D.mtf"))
                  .getEntity();
            onSwing(() -> {
                walker.setId(8);
                walker.setOwner(moving.board.player);
                walker.setStartingPos(Board.START_S);
                walker.setDeployRound(0);
                moving.board.game.addEntity(walker, false);
                moving.display.selectEntity(walker.getId());
                return null;
            });
            GpuMovePlan plan = plan(moving);
            assertFalse(shown(moving).planner(), "Not on the board yet: MegaMek's board click places it");
            plan.planTo(inland, 0, false);
            assertEquals(List.of(), shown(moving).route(), "Nothing is searched from no position");

            megamekClick(moving, entry);
            assertEquals(List.of(true, entry), onSwing(() -> List.of(walker.isDeployed(), walker.getPosition())));
            assertTrue(shown(moving).planner());
            plan.planTo(inland, 0, false);
            Snapshot planned = shown(moving);
            assertEquals(inland, planned.route().getLast().coords());
            assertEquals(MoveStepType.DEPLOY, onSwing(() -> moving.display.getPlannedMovement().getStep(0).getType()),
                  "The route continues the placement");
            plan.clearRoute();
            assertEquals(List.of(MoveStepType.DEPLOY), onSwing(() -> moving.display.getPlannedMovement()
                  .getStepVector().stream().map(MoveStep::getType).toList()), "Clearing the route keeps the entry");
        } finally {
            Game.rulesManager = rules;
        }
    }

    /**
     * E2d: while the display picks the hexes of a bridge build, its own selection takes the board click; once MegaMek's
     * cancel ends the pick, the unit is planned again and a click plots as before.
    @Test
    void aBridgeBuildPickTakesTheBoardClickAndPlanningResumesAfterIt() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Entity engineers = selectBridgeEngineers(moving);
            GpuMovePlan plan = plan(moving);
            moving.command(MoveCommand.MOVE_BUILD_BRIDGE);
            assertFalse(shown(moving).planner());
            plan.planTo(BRIDGE_HEX, 0, false);
            assertEquals(List.of(), shown(moving).route(), "The plan plots nothing over the pick");

            megamekClick(moving, BRIDGE_HEX);
            verify(moving.gui).addToast(ToastLevel.INFO, Messages.getString(
                  "MovementDisplay.BuildBridge.toast.sectionSet", BRIDGE_HEX.getBoardNum()), engineers);
            assertFalse(shown(moving).planner(), "The bridge's far end is picked next");

            moving.command(MoveCommand.MOVE_CANCEL);
            assertTrue(shown(moving).planner());
            plan.planTo(NORTH_OF_ENGINEERS, 0, false);
            assertEquals(List.of("10,9 f0 WALK"), route(shown(moving).route()));
        }
    }

    /**
     * BCS: MegaMek's own highlight of an escape pod pick reaches the native frame. Its envelope sprites live in the
     * board state, which the source captures as tactical geometry, so no Legacy 2D board view is needed (the fixture
     * builds none). The frame draws the outline of the picked hexes and nothing else in that colour.
     */
    @Test
    void theEscapePodPickHighlightReachesTheNativeFrame() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            scriptTurns(moving, LOCAL, OPPONENT);
            selectEscapePodTank(moving);
            moving.command(MoveCommand.MOVE_LAUNCH_ESCAPE_POD);
            Set<Coords> pick = onSwing(() -> moving.envelopeSprites().stream()
                  .map(MovementEnvelopeSprite::getPosition).collect(Collectors.toSet()));
            assertFalse(pick.isEmpty(), "MegaMek's board state highlights the pick's hexes");
            onSwing(() -> {
                moving.board.source.refresh();
                return null;
            });
            int walk = GUIPreferences.getInstance().getMoveDefaultColor().getRGB() & 0xFFFFFF;
            Set<Coords> drawn = moving.board.source.takeFrame().scene().tactical().fills().stream()
                  .filter(fill -> ((fill.argb() & 0xFFFFFF) == walk) && (fill.planeAnchor() != null))
                  .map(fill -> hexAt(fill.planeAnchor())).collect(Collectors.toSet());
            Set<Coords> outline = pick.stream()
                  .filter(hex -> hex.allAdjacent().stream().anyMatch(neighbour -> !pick.contains(neighbour)))
                  .collect(Collectors.toSet());
            assertEquals(outline, drawn, "The native frame draws the pick's outline, and only it");
        }
    }

    /**
     * G3, rule 9: the hover route is searched in its own job after the capture, never while a dialog waits, and is
     * the route a click on the hex plots.
     */
    @Test
    void theHoverRouteIsSearchedAfterTheCaptureAndIsWhatAClickPlots() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = plan(moving);
            GpuBoardSource source = moving.board.source;
            // While a dialog waits, captures still publish, but nothing is searched.
            DialogRequest request = new DialogRequest(0, DialogKind.MESSAGE, "Confirm", "Continue?", false,
                  List.of("Yes", "No"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of());
            FutureTask<DialogAnswer> asked = new FutureTask<>(() -> source.ask(request));
            SwingUtilities.invokeLater(asked);
            DialogRequest shown = GpuDialogFixture.awaitDialog(source);
            assertEquals(List.of(), hoverAfterCapture(source, NORTH_2));
            settle();
            assertEquals(List.of(), hoverAfterCapture(source, NORTH_2), "No search inside the dialog's loop");
            source.answer(shown.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
            asked.get(20, SECONDS);

            // The capture asks for the search; the route appears once the job ran.
            assertEquals(List.of(), hoverAfterCapture(source, NORTH_2));
            settle();
            List<Step> hovered = shown(moving).hover();
            assertEquals(List.of("11,10 f0 WALK", "11,9 f0 WALK"), route(hovered));
            plan.planTo(NORTH_2, 0, false);
            Snapshot plotted = shown(moving);
            assertEquals(route(hovered), route(plotted.route()));
            assertEquals(List.of(), plotted.hover(), "No hover route while a route is plotted");

            // A hex out of reach has no hover route and asks for no search.
            plan.clearRoute();
            assertEquals(List.of(), hoverAfterCapture(source, NORTH_5));
            settle();
            assertEquals(List.of(), shown(moving).hover());
        }
    }

    /**
     * G15: Hold all holds the selected unit at once and the other own unit on the next own turn, with MegaMek's skip
     * (a move without steps); nothing on the opponent's turn, and nothing more in a turn the server has not ended yet.
     * The count shows on every turn of the phase, and the hold ends with the last own unit.
     */
    @Test
    void holdAllSkipsOnTheOwnTurnsOfThePhaseOnly() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Entity atlas = moving.board.entity;
            scriptTurns(moving, LOCAL, OPPONENT, LOCAL, OPPONENT);
            GpuMovePlan plan = plan(moving);
            plan.holdAll();
            Snapshot started = afterHold(moving);
            verify(moving.gui).addToast(ToastLevel.INFO, Messages.getString("GpuBoard.hud.move.holdStarted", 2));
            verify(moving.client).moveEntity(eq(moving.unit.getId()), argThat(GpuMovePlanTest::held));
            // The skip ended the local turn; both units count until the server answers.
            assertEquals(Snapshot.idle(2), started);
            // Until then the client still has the turn, and the player can select the Atlas, whose Skip MegaMek keeps
            // disabled until a new turn begins: the hold neither sends nor ends in this turn.
            moving.board.source.selectUnit(atlas.getId());
            Snapshot selected = afterHold(moving);
            assertEquals(List.of(atlas.getId(), 2), List.of(selected.entityId(), selected.holdingRemaining()));
            verify(moving.client, times(1)).moveEntity(anyInt(), any(MovePath.class));

            // The opponent's turn: nothing is sent; the dock still shows the hold.
            nextTurn(moving, moving.unit);
            assertEquals(Snapshot.idle(1), afterHold(moving));
            verify(moving.client, times(1)).moveEntity(anyInt(), any(MovePath.class));

            // The next own turn starts without a unit ("auto-select next unit" off): Next unit picks the Atlas.
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean autoSelect = preferences.getAutoSelectNextUnit();
            try {
                onSwing(() -> {
                    preferences.setAutoSelectNextUnit(false);
                    return null;
                });
                nextTurn(moving);
                afterHold(moving);
            } finally {
                onSwing(() -> {
                    preferences.setAutoSelectNextUnit(autoSelect);
                    return null;
                });
            }
            verify(moving.client).moveEntity(eq(atlas.getId()), argThat(GpuMovePlanTest::held));

            // The server's answer leaves no own unit to move: the hold is over.
            nextTurn(moving, atlas);
            assertEquals(Snapshot.EMPTY, afterHold(moving));
            verify(moving.client, times(2)).moveEntity(anyInt(), any(MovePath.class));
        }
    }

    /** G15: the hold ends with its movement phase; the next round's movement turns are the player's again. */
    @Test
    void holdEndsWithTheMovementPhase() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            holdTheFirstTurn(moving);
            assertEquals(1, afterHold(moving).holdingRemaining());
            // The server ends the phase (scripted: the Atlas has not moved).
            onSwing(() -> {
                moving.board.game.setPhase(GamePhase.FIRING);
                return null;
            });
            assertEquals(Snapshot.EMPTY, afterHold(moving));

            // The next round's movement phase begins with the local player's turn; MegaMek selects the Atlas.
            onSwing(() -> {
                Game game = moving.board.game;
                game.setRoundCount(game.getRoundCount() + 1);
                moving.unit.setDone(false);
                game.setPhase(GamePhase.MOVEMENT);
                game.setTurnVector(List.of(new GameTurn(LOCAL), new GameTurn(OPPONENT)));
                game.setTurnIndex(0, OPPONENT);
                return null;
            });
            Snapshot planning = afterHold(moving);
            assertTrue(planning.active());
            assertEquals(moving.board.entity.getId(), planning.entityId());
            assertEquals(0, planning.holdingRemaining());
            verify(moving.client, times(1)).moveEntity(anyInt(), any(MovePath.class));
        }
    }

    /**
     * G15: a unit that cannot hold ends the hold with a toast: one whose Skip MegaMek disables (here: a route plotted
     * as its turn began), and one whose skip MegaMek refuses (an airborne fighter must use its velocity).
     */
    @Test
    void aUnitThatCannotHoldEndsTheHold() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Entity atlas = moving.board.entity;
            holdTheFirstTurn(moving);
            // The own turn begins with the Atlas selected, and a step north is plotted at once (a Swing click).
            boolean skipEnabled = onSwing(() -> {
                Game game = moving.board.game;
                game.setTurnIndex(game.getTurnIndex() + 1, OPPONENT);
                moving.display.plotTo(new Coords(5, 4), 0);
                return moving.display.getCompletionButtons().getLast().isEnabled();
            });
            assertFalse(skipEnabled, "MegaMek disables Skip while the unit has a route");
            Snapshot stopped = afterHold(moving);
            verify(moving.gui).addToast(ToastLevel.WARNING,
                  Messages.getString("GpuBoard.hud.move.holdStopped", atlas.getShortName()));
            verify(moving.client, never()).moveEntity(eq(atlas.getId()), any(MovePath.class));
            assertEquals(List.of(true, 0), List.of(stopped.active(), stopped.holdingRemaining()));
            assertEquals(List.of("5,4 f0 WALK"), route(stopped.route()), "The player's route stays");
        }
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Entity fighter = selectAirborneFighter(moving);
            scriptTurns(moving, LOCAL, OPPONENT);
            GpuMovePlan plan = plan(moving);
            plan.holdAll();
            assertEquals(0, afterHold(moving).holdingRemaining());
            verify(moving.gui).addToast(ToastLevel.WARNING,
                  Messages.getString("GpuBoard.hud.move.holdStopped", fighter.getShortName()));
            verify(moving.client, never()).moveEntity(anyInt(), any(MovePath.class));
        }
    }

    /** G15: Stop on the opponent's turn ends the hold, and the next own turn is the player's. */
    @Test
    void stopEndsTheHold() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuMovePlan plan = holdTheFirstTurn(moving);
            assertEquals(1, afterHold(moving).holdingRemaining());
            plan.stopHolding();
            assertEquals(Snapshot.EMPTY, afterHold(moving));
            nextTurn(moving);
            Snapshot planning = afterHold(moving);
            assertTrue(planning.active());
            assertEquals(moving.board.entity.getId(), planning.entityId());
            assertEquals(0, planning.holdingRemaining());
            verify(moving.client, never()).moveEntity(eq(moving.board.entity.getId()), any(MovePath.class));
        }
    }

    /**
     * G15, B.1 rule 4: while the phase display ignores input (as under the bot's hex picker) or a native dialog
     * waits, the hold sends nothing and stays on; after the answer it holds the unit.
     */
    @Test
    void aPendingDialogPausesTheHold() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            int atlas = moving.board.entity.getId();
            holdTheFirstTurn(moving);
            // The own turn begins with the Atlas selected, and the display is set to ignore input at once.
            onSwing(() -> {
                Game game = moving.board.game;
                game.setTurnIndex(game.getTurnIndex() + 1, OPPONENT);
                moving.display.setIgnoringEvents(true);
                return null;
            });
            assertEquals(1, afterHold(moving).holdingRemaining());
            // A native dialog appears, and the display takes input again while it waits.
            GpuBoardSource source = moving.board.source;
            DialogRequest request = new DialogRequest(0, DialogKind.MESSAGE, "Confirm", "Continue?", false,
                  List.of("Yes", "No"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of());
            FutureTask<DialogAnswer> asked = new FutureTask<>(() -> source.ask(request));
            SwingUtilities.invokeLater(asked);
            DialogRequest shown = GpuDialogFixture.awaitDialog(source);
            onSwing(() -> {
                moving.display.setIgnoringEvents(false);
                return null;
            });
            assertEquals(1, afterHold(moving).holdingRemaining());
            afterHold(moving);
            verify(moving.client, never()).moveEntity(eq(atlas), any(MovePath.class));

            source.answer(shown.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
            asked.get(20, SECONDS);
            afterHold(moving);
            verify(moving.client).moveEntity(eq(atlas), argThat(GpuMovePlanTest::held));
        }
    }

    @Test
    void infantryCanPreviewAndMoveBetweenFloorsWithoutLeavingTheHex() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Entity infantry = buildingUnit(moving, "Foot Platoon (AFFS) (Laser 3067+).blk", 20);
            GpuMovePlan plan = plan(moving);
            List<Step> preview = floorHover(moving, GpuMovementFixture.START, 21);
            assertEquals(24, preview.getLast().level(), "Floor 21 above terrain level 3");
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(21));
            Snapshot up = shown(moving);
            assertEquals(preview, up.route());
            assertEquals(1, up.cost());
            assertEquals(20, infantry.getElevation(), "Planning must not move the authoritative unit");
            assertTrue(up.legal());

            plan.undo();
            assertTrue(shown(moving).route().isEmpty());
            assertEquals(22, floorHover(moving, GpuMovementFixture.START, 19).getLast().level(),
                  "Changing the hovered floor in the same hex must invalidate the preview");
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(19));
            assertEquals(22, shown(moving).route().getLast().level());
            assertEquals(1, shown(moving).cost());
        }
    }

    @Test
    void buildingWaypointsAndTurnsKeepTheirFloorAndAllowGroundAndRoofTargets() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            buildingUnit(moving, "Foot Platoon (AFFS) (Laser 3067+).blk", 2);
            GpuMovePlan plan = plan(moving);
            plan.planTo(GpuMovementFixture.START, 0, true, floorZ(1));
            assertEquals(1, shown(moving).pins().size());
            plan.planTo(GpuMovementFixture.START, 0, true, floorZ(0));
            Snapshot ground = shown(moving);
            assertEquals(2, ground.pins().size(), "Different floors in one hex are distinct waypoints");
            assertEquals(List.of(4f, 3f), ground.pins().stream().map(Step::level).toList(),
                  "Waypoint rendering retains each floor even when both pins share one hex");
            assertEquals(3, ground.route().getLast().level());
            assertEquals(2, ground.cost());
            plan.undo();
            assertEquals(4, shown(moving).route().getLast().level());
            assertEquals(1, shown(moving).pins().size());

            plan.planTo(NORTH_1, 0, false, floorZ(2));
            Snapshot adjacent = shown(moving);
            assertEquals(5, adjacent.route().getLast().level());
            plan.turn(1);
            assertEquals(5, shown(moving).route().getLast().level(), "A cheaper facing route must not change floor");

            onSwing(() -> {
                moving.display.currentEntity().setElevation(23);
                moving.display.selectEntity(moving.display.currentEntity().getId());
                return null;
            });
            plan = plan(moving);
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(24));
            assertEquals(27, shown(moving).route().getLast().level(), "The roof is a valid vertical destination");
        }
    }

    @Test
    void adjacentBuildingFloorsUseTheSameLegalRouteForHoverAndClick() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            buildingUnit(moving, "Foot Platoon (AFFS) (Laser 3067+).blk", 2);
            GpuMovePlan plan = plan(moving);
            List<Step> preview = floorHover(moving, NORTH_1, 1);
            assertEquals(4, preview.getLast().level());
            plan.planTo(NORTH_1, 0, false, floorZ(1));
            Snapshot plotted = shown(moving);
            assertEquals(preview, plotted.route());
            assertEquals(NORTH_1, plotted.destination());
            assertEquals(2, plotted.cost(), "One hex and one infantry floor change");
            assertTrue(plotted.legal());

            plan.planTo(NORTH_1, 0, false, floorZ(20));
            assertEquals(plotted.route(), shown(moving).route(), "An unaffordable floor must preserve the draft");
            verify(moving.gui).addToast(ToastLevel.WARNING,
                  Messages.getString("GpuBoard.hud.move.floorUnreachable", 20));
        }
    }

    @Test
    void protoMekFloorChangesUseTheEngineMovementCost() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            buildingUnit(moving, "Centaur.blk", 20);
            GpuMovePlan plan = plan(moving);
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(21));
            Snapshot up = shown(moving);
            assertTrue(up.legal());
            assertEquals(24, up.route().getLast().level());
            assertEquals(2, up.cost());
        }
    }

    @Test
    void mekCanStillJumpToARoofButCannotSelectAnInteriorLanding() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            buildingUnit(moving, null, 24);
            GpuMovePlan plan = plan(moving);
            plan.setMode(Mode.JUMP);
            shown(moving);
            List<Step> preview = floorHover(moving, NORTH_1, 24);
            assertEquals(27, preview.getLast().level());
            plan.planTo(NORTH_1, 0, false, floorZ(24));
            Snapshot roof = shown(moving);
            assertEquals(preview, roof.route());
            assertEquals(Band.JUMP, roof.route().getLast().band());
            assertTrue(roof.legal());

            plan.planTo(NORTH_1, 0, false, floorZ(23));
            assertEquals(roof.route(), shown(moving).route());
        }
    }

    @Test
    void floorSelectionPreservesBuildingEntryAndExit() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Entity infantry = buildingUnit(moving, "Foot Platoon (AFFS) (Laser 3067+).blk", 0);
            Coords outside = GpuMovementFixture.START.translated(3);
            onSwing(() -> {
                moving.board.game.getBoard().setHex(outside, new Hex(3));
                infantry.setPosition(outside);
                moving.display.selectEntity(infantry.getId());
                return null;
            });
            GpuMovePlan plan = plan(moving);
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(1));
            Snapshot entry = shown(moving);
            assertEquals(GpuMovementFixture.START, entry.destination());
            assertEquals(4, entry.route().getLast().level());
            assertEquals(2, entry.cost());
            assertTrue(entry.legal());

            onSwing(() -> {
                infantry.setPosition(GpuMovementFixture.START);
                infantry.setElevation(1);
                infantry.setFacing(3);
                moving.display.selectEntity(infantry.getId());
                return null;
            });
            plan = plan(moving);
            plan.planTo(outside, 0, false, floorZ(0));
            Snapshot exit = shown(moving);
            assertEquals(outside, exit.destination());
            assertEquals(3, exit.route().getLast().level());
            assertTrue(exit.legal());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void mekFloorAccessRespectsRulesetAndCannotSilentlyChangeFloor(boolean core) throws Exception {
        RulesManager rules = Game.rulesManager;
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Game.rulesManager = core ? new CoreRulesManager() : new TWRulesManager();
            buildingUnit(moving, null, 20);
            GpuMovePlan plan = plan(moving);
            assertTrue(floorHover(moving, GpuMovementFixture.START, 19).isEmpty());
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(19));
            assertTrue(shown(moving).route().isEmpty());
            verify(moving.gui).addToast(ToastLevel.WARNING,
                  Messages.getString("GpuBoard.hud.move.floorUnreachable", 19));

            plan.planTo(NORTH_1, 0, false, floorZ(20));
            Snapshot sameFloor = shown(moving);
            if (core) {
                assertTrue(sameFloor.route().isEmpty(), "CORE forbids entering another hex's interior floor too");
            } else {
                assertEquals(NORTH_1, sameFloor.destination());
                assertEquals(23, sameFloor.route().getLast().level());
            }
            plan.planTo(NORTH_1, 0, false, floorZ(2));
            assertEquals(sameFloor.route(), shown(moving).route());
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(0));
            assertEquals(sameFloor.route(), shown(moving).route());
        } finally {
            Game.rulesManager = rules;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void coreMekCanEnterAtGroundOrJumpOntoRoof(boolean jump) throws Exception {
        RulesManager rules = Game.rulesManager;
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Game.rulesManager = new CoreRulesManager();
            buildingUnit(moving, null, 0);
            Coords outside = GpuMovementFixture.START.translated(3);
            onSwing(() -> {
                moving.board.game.getBoard().setHex(outside, new Hex(3));
                moving.board.game.getBoard().setHex(GpuMovementFixture.START,
                      new Hex(3, "building:2;bldg_elev:2;bldg_cf:120;bldg_basement_type:1", ""));
                moving.unit.setPosition(outside);
                moving.display.selectEntity(moving.unit.getId());
                return null;
            });
            GpuMovePlan plan = plan(moving);
            plan.setMode(jump ? Mode.JUMP : Mode.WALK);
            assertTrue(floorHover(moving, GpuMovementFixture.START, 1).isEmpty());
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(1));
            assertTrue(shown(moving).route().isEmpty(), "An interior floor cannot become a plotted destination");
            int elevation = jump ? 2 : 0;
            plan.planTo(GpuMovementFixture.START, 0, false, floorZ(elevation));
            Snapshot entry = shown(moving);
            assertTrue(entry.legal());
            assertEquals(GpuMovementFixture.START, entry.destination());
            assertEquals(3 + elevation, entry.route().getLast().level());
        } finally {
            Game.rulesManager = rules;
        }
    }

    /** Three connected building hexes on elevated ground; real units and movement compiler, no legality mocks. */
    private static Entity buildingUnit(GpuMovementFixture moving, String filename, int elevation) throws Exception {
        Entity unit = filename == null ? moving.unit : new MekFileParser(
              new File("testresources/megamek/common/units/" + filename)).getEntity();
        onSwing(() -> {
            Board board = moving.board.game.getBoard();
            for (Coords coords : List.of(GpuMovementFixture.START, NORTH_1, NORTH_2)) {
                board.setHex(coords, new Hex(3, "building:2;bldg_elev:24;bldg_cf:120;bldg_basement_type:1", ""));
            }
            for (Coords coords : List.of(GpuMovementFixture.START, NORTH_1, NORTH_2)) {
                if (board.getBuildingAt(coords) == null) {
                    board.addBuildingToBoard(new BuildingTerrain(coords, board, Terrains.BUILDING, BasementType.NONE));
                }
            }
            // Keep enough MP for a multi-hex floor route; still enforce the compiler's real per-step costs and cap.
            if (unit instanceof ConvInfantry) { unit.setOriginalWalkMP(6); }
            unit.setElevation(elevation);
            if (unit != moving.unit) { moving.unit.setPosition(new Coords(14, 14)); }
            return null;
        });
        return select(moving, unit, unit == moving.unit ? unit.getId() : 7, GpuMovementFixture.START);
    }

    private static List<Coords> pinHexes(Snapshot plan) {
        return plan.pins().stream().map(Step::coords).toList();
    }

    private static float floorZ(int elevation) {
        return (3 + elevation) * BoardGeometry.level();
    }

    private static List<Step> floorHover(GpuMovementFixture moving, Coords coords, int elevation) throws Exception {
        onSwing(() -> {
            moving.board.source.setHover(coords, floorZ(elevation));
            moving.board.source.refresh();
            return null;
        });
        return shown(moving).hover();
    }

    /** An airborne Cheetah F-11 of the local player at (4, 12), velocity 3 at altitude 5, selected in the display. */
    private static Entity selectAirborneFighter(GpuMovementFixture moving) throws Exception {
        Entity fighter = new MekFileParser(new File("testresources/megamek/common/units/Cheetah F-11.blk")).getEntity();
        ((IAero) fighter).setCurrentVelocity(3);
        ((IAero) fighter).setNextVelocity(3);
        fighter.setAltitude(5);
        return select(moving, fighter, 5, new Coords(4, 12));
    }

    /** A Bulldog Medium Tank with a Combat Vehicle Escape Pod in the rear, selected in the display at TANK. */
    private static Entity selectEscapePodTank(GpuMovementFixture moving) throws Exception {
        Entity tank = new MekFileParser(new File("testresources/megamek/common/units/Bulldog Medium Tank.blk"))
              .getEntity();
        tank.addEquipment(EquipmentType.get("ISCombatVehicleEscapePod"), Tank.LOC_REAR);
        return select(moving, tank, 6, TANK);
    }

    /**
     * A foot platoon with the Bridge-Building Engineers specialization (and so its bridge kit), selected in the
     * display at ENGINEERS; asked for the bridge type, the player chooses a light bridge.
     */
    private static Entity selectBridgeEngineers(GpuMovementFixture moving) throws Exception {
        ConvInfantry engineers = (ConvInfantry) new MekFileParser(
              new File("testresources/megamek/common/units/Foot Platoon (AFFS) (Laser 3067+).blk")).getEntity();
        engineers.setSpecializations(ConvInfantry.BRIDGE_ENGINEERS);
        doReturn(Messages.getString("MovementDisplay.BuildBridgeDialog.light")).when(moving.gui)
              .input(anyString(), anyString(), anyInt(), any(), any());
        return select(moving, engineers, 7, ENGINEERS);
    }

    /** Puts a unit of the local player on the board facing north and selects it in the display. */
    private static Entity select(GpuMovementFixture moving, Entity unit, int id, Coords position) throws Exception {
        onSwing(() -> {
            unit.setId(id);
            unit.setOwner(moving.board.player);
            unit.setPosition(position);
            unit.setFacing(0);
            unit.setDeployed(true);
            moving.board.game.addEntity(unit, false);
            moving.display.selectEntity(id);
            return null;
        });
        return unit;
    }

    /** A left click as GpuHud sends it for movement it does not plan: MegaMek's board tool, press and click. */
    private static void megamekClick(GpuMovementFixture moving, Coords hex) throws Exception {
        moving.board.source.hover(hex, 0);
        moving.board.source.click(hex, false, 0);
        settle();
    }

    /** A move that launches the escape pod to the hex (the step's data 0 and 1 are its x and y). */
    private static boolean launchesPodTo(MovePath path, Coords hex) {
        return path.getStepVector().stream().anyMatch(step -> (step.getType() == MoveStepType.LAUNCH_ESCAPE_POD)
              && Integer.valueOf(hex.getX()).equals(step.getAdditionalData(0))
              && Integer.valueOf(hex.getY()).equals(step.getAdditionalData(1)));
    }

    /** EDT, in one event: sets the hovered hex, captures, and returns the published hover route. */
    private static List<Step> hoverAfterCapture(GpuBoardSource source, Coords hex) throws Exception {
        return onSwing(() -> {
            source.setHover(hex);
            source.refresh();
            return source.takeFrame().panels().move().hover();
        });
    }

    /**
     * The source's plan over the fixture's real display, which is the source's phase panel from now on, after a
     * capture: the HUD only sends commands for a plan it was shown.
     */
    private static GpuMovePlan plan(GpuMovementFixture moving) throws Exception {
        moving.board.panel = moving.display;
        shown(moving);
        return moving.board.source.moves();
    }

    /** The hex whose centre is the given annotation plane anchor, in tactical capture pixels (scale 1). */
    private static Coords hexAt(BoardTactical.Point centre) {
        int x = Math.round((centre.x() - 42) / 63f);
        return new Coords(x, Math.round((centre.y() - 36 - ((x & 1) == 1 ? 36 : 0)) / 72f));
    }

    /** The plan the source published after every posted command, search and republish ran. */
    private static Snapshot shown(GpuMovementFixture moving) throws Exception {
        settle();
        return onSwing(() -> {
            moving.board.source.refresh();
            return moving.board.source.takeFrame().panels().move();
        });
    }

    /** Lets posted commands run, then the search job they asked for and its republish. */
    private static void settle() throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            onSwing(() -> null);
        }
    }

    /**
     * Scripted movement turns instead of a server: an opponent joins, the client follows the game's current turn as
     * Client.isMyTurn, getMyTurn, getFirstEntityNum and getNextEntityNum do, and the turns go to these players in
     * order; the first begins now.
     */
    private static void scriptTurns(GpuMovementFixture moving, int... players) throws Exception {
        onSwing(() -> {
            Game game = moving.board.game;
            Player opponent = new Player(OPPONENT, "Opponent");
            opponent.setTeam(2);
            game.addPlayer(OPPONENT, opponent);
            game.setTurnVector(Arrays.stream(players).mapToObj(GameTurn::new).toList());
            Client client = moving.client;
            when(client.isMyTurn()).thenAnswer(invocation -> (game.getTurn() != null)
                  && game.getTurn().isValid(LOCAL, game));
            when(client.getMyTurn()).thenAnswer(invocation -> game.getTurn());
            when(client.getFirstEntityNum()).thenAnswer(invocation -> game.getFirstEntityNum(game.getTurn()));
            when(client.getNextEntityNum(anyInt()))
                  .thenAnswer(invocation -> game.getNextEntityNum(game.getTurn(), invocation.getArgument(0)));
            game.setTurnIndex(0, Player.PLAYER_NONE);
            return null;
        });
    }

    /** The server's answer to a move: the units it moved are done, and the next scripted turn begins. */
    private static void nextTurn(GpuMovementFixture moving, Entity... moved) throws Exception {
        onSwing(() -> {
            Game game = moving.board.game;
            for (Entity unit : moved) {
                unit.setDone(true);
            }
            game.setTurnIndex(game.getTurnIndex() + 1, game.getTurn().playerId());
            return null;
        });
    }

    /**
     * Turns own, opponent, own, opponent: Hold all on the first holds the Sagittaire (its skip is sent), and the
     * server answers with the opponent's turn.
     */
    private static GpuMovePlan holdTheFirstTurn(GpuMovementFixture moving) throws Exception {
        scriptTurns(moving, LOCAL, OPPONENT, LOCAL, OPPONENT);
        GpuMovePlan plan = plan(moving);
        plan.holdAll();
        settle();
        verify(moving.client).moveEntity(eq(moving.unit.getId()), argThat(GpuMovePlanTest::held));
        nextTurn(moving, moving.unit);
        return plan;
    }

    /** The plan once a capture saw the change and the hold's own event, which that capture asked for, ran. */
    private static Snapshot afterHold(GpuMovementFixture moving) throws Exception {
        shown(moving);
        return shown(moving);
    }

    /** A move without steps: MegaMek's skip, the unit holds its position. */
    private static boolean held(MovePath path) {
        return path.length() == 0;
    }

    /** Each step as "x,y f{facing} {band}". */
    private static List<String> route(List<Step> steps) {
        return steps.stream().map(step -> step.coords().getX() + "," + step.coords().getY() + " f" + step.facing()
              + " " + step.band()).toList();
    }
}
