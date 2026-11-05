/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;

import megamek.client.event.BoardViewEvent;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.clientGUI.boardview.spriteHandler.MovementEnvelopeSpriteHandler;
import megamek.client.ui.clientGUI.boardview.spriteHandler.MovementEnvelopeSpriteHandler.Band;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay.Envelope;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.moves.MoveStep;
import megamek.common.units.Entity;
import megamek.common.units.IAero;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The MovementDisplay entry points the GPU movement plan uses, on a real display: plotting as a board click does,
 * turning, cutting the path back to a waypoint, the envelope continued from a waypoint with its paths per hex and
 * facing, plotting one of those paths, and the cancelled mount bay prompt.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuMovementDisplayAccessTest {
    /** One and two hexes north of the unit, open ground: 1 and 2 MP walking forwards. */
    private static final Coords NORTH_1 = new Coords(11, 10);
    private static final Coords NORTH_2 = new Coords(11, 9);
    /** Behind the unit, open ground. */
    private static final Coords SOUTH_1 = new Coords(11, 12);

    @Test
    void plotToPlotsWhatAClickOnTheHexPlotsAndIgnoresAHeldShift() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            MovementDisplay display = moving.display;
            List<String> clicked = onSwing(() -> {
                click(moving, NORTH_2);
                List<String> steps = steps(display.getPlannedMovement());
                display.selectEntity(moving.unit.getId());
                return steps;
            });
            List<String> plotted = onSwing(() -> {
                display.plotTo(NORTH_2, 0);
                return steps(display.getPlannedMovement());
            });
            assertEquals(List.of("FORWARDS 11,10 f0 1", "FORWARDS 11,9 f0 2"), plotted);
            assertEquals(clicked, plotted, "The same path as a left-button click on the hex");

            // A Shift press turns the path to face the hex; plotTo afterwards still moves to its hex.
            List<String> afterShift = onSwing(() -> {
                display.selectEntity(moving.unit.getId());
                display.hexMoused(new BoardViewEvent(moving.board.view, GpuMovementFixture.START.translated(1),
                      BoardViewEvent.BOARD_HEX_DRAGGED, InputEvent.SHIFT_DOWN_MASK, MouseEvent.BUTTON1));
                display.plotTo(NORTH_2, 0);
                return steps(display.getPlannedMovement());
            });
            // The press turned right; the path continues from that facing (a right turn is not undone by a left one).
            assertEquals(List.of("TURN_RIGHT 11,11 f1 1", "FORWARDS 12,11 f1 2", "TURN_LEFT 12,11 f0 3",
                  "FORWARDS 12,10 f0 4", "TURN_LEFT 12,10 f5 5", "FORWARDS 11,9 f5 6"), afterShift);
        }
    }

    @Test
    void turnLeftAndTurnRightTurnTheEndOfThePath() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            MovementDisplay display = moving.display;
            assertEquals(List.of("TURN_LEFT 11,11 f5 1"), onSwing(() -> {
                display.turnLeft();
                return steps(display.getPlannedMovement());
            }), "In place");
            assertEquals(List.of("TURN_LEFT 11,11 f5 1", "TURN_RIGHT 11,11 f0 2", "TURN_RIGHT 11,11 f1 3"),
                  onSwing(() -> {
                      display.turnRight();
                      display.turnRight();
                      return steps(display.getPlannedMovement());
                  }));
            assertEquals(List.of("FORWARDS 11,10 f0 1", "FORWARDS 11,9 f0 2", "TURN_RIGHT 11,9 f1 3"),
                  onSwing(() -> {
                      display.selectEntity(moving.unit.getId());
                      display.plotTo(NORTH_2, 0);
                      display.turnRight();
                      return steps(display.getPlannedMovement());
                  }), "At the end of a route");
        }
    }

    @Test
    void truncateToCutsThePathBackToAWaypointAndKeepsTheGear() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            MovementDisplay display = moving.display;
            assertEquals(List.of("FORWARDS 11,10 f0 1"), onSwing(() -> {
                display.plotTo(NORTH_1, 0);
                int pin = display.getPlannedMovement().length();
                display.plotTo(NORTH_2, 0);
                display.truncateTo(pin);
                return steps(display.getPlannedMovement());
            }));
            assertEquals(List.of("FORWARDS 11,10 f0 1"), onSwing(() -> {
                display.truncateTo(5);
                return steps(display.getPlannedMovement());
            }), "A longer prefix keeps the path");

            onSwing(() -> {
                display.selectEntity(moving.unit.getId());
                return null;
            });
            moving.command(MoveCommand.MOVE_BACK_UP);
            assertEquals(List.of("BACKWARDS 11,12 f0 1"), onSwing(() -> {
                display.plotTo(SOUTH_1, 0);
                display.truncateTo(0);
                assertEquals(List.of(), steps(display.getPlannedMovement()));
                assertEquals(MovementDisplay.GEAR_BACKUP, display.getGear(), "An emptied path keeps its gear");
                display.plotTo(SOUTH_1, 0);
                return steps(display.getPlannedMovement());
            }), "Still backing up after the cut");
        }
    }

    @Test
    void theEnvelopeFromAWaypointUsesTheMpLeftAndKeepsAPathPerHexAndFacing() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean shown = preferences.getMoveEnvelope();
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            MovementDisplay display = moving.display;
            // The envelope computed on selection; a start path of no steps gives the same one.
            Envelope selected = onSwing(display::getLastEnvelope);
            Envelope fromStart = onSwing(() -> {
                display.computeMovementEnvelope(display.getPlannedMovement().clone());
                return display.getLastEnvelope();
            });
            assertEquals(selected.mp(), fromStart.mp());
            assertEquals(Map.of(Band.WALK, 8L, Band.RUN, 18L), bandCounts(fromStart, moving.unit),
                  "The bands Swing draws for the unit (GpuMoveEnvelopeCharacterizationTest)");

            // From a waypoint two hexes north (2 MP), with the 3 MP left of the run MP.
            MovePath waypoint = onSwing(() -> {
                display.plotTo(NORTH_2, 0);
                return display.getPlannedMovement().clone();
            });
            Envelope fromWaypoint = onSwing(() -> {
                display.computeMovementEnvelope(waypoint);
                return display.getLastEnvelope();
            });
            assertEquals(moving.unit.getId(), fromWaypoint.entityId());
            assertEquals(MovementDisplay.GEAR_LAND, fromWaypoint.gear());
            assertEquals(2, fromWaypoint.mp().get(NORTH_2), "The waypoint itself costs its 2 MP");
            assertEquals(9, fromWaypoint.mp().size(), "The hexes 3 more MP reach from the waypoint");
            assertEquals(Map.of(Band.WALK, 2L, Band.RUN, 7L), bandCounts(fromWaypoint, moving.unit),
                  "Banded by the whole route's MP: walking up to 3, running up to 5");
            assertTrue(fromWaypoint.paths().stream().allMatch(path -> path.length() >= 2
                  && steps(path).subList(0, 2).equals(steps(waypoint))), "Every path continues the waypoint");
            List<String> nodes = fromWaypoint.paths().stream()
                  .map(path -> path.getFinalCoords() + " f" + path.getFinalFacing()).toList();
            assertEquals(nodes.size(), nodes.stream().distinct().count(), "One path per hex and facing");
            assertEquals(6, fromWaypoint.paths().stream().filter(path -> NORTH_2.equals(path.getFinalCoords()))
                  .count(), "Every facing at the waypoint");

            // Plotting the computed path to the waypoint facing north-east makes it the current path.
            MovePath northEast = fromWaypoint.paths().stream()
                  .filter(path -> NORTH_2.equals(path.getFinalCoords()) && (path.getFinalFacing() == 1))
                  .findFirst().orElseThrow();
            assertEquals(List.of("FORWARDS 11,10 f0 1", "FORWARDS 11,9 f0 2", "TURN_RIGHT 11,9 f1 3"), onSwing(() -> {
                display.plotPath(northEast);
                assertNotSame(northEast, display.getPlannedMovement(), "The path is copied");
                return steps(display.getPlannedMovement());
            }));

            // With envelopes switched off the board shows none, but a start path is still searched.
            Envelope hidden = onSwing(() -> {
                preferences.setMoveEnvelope(false);
                display.computeMovementEnvelope(waypoint);
                return display.getLastEnvelope();
            });
            assertEquals(fromWaypoint.mp(), hidden.mp());
            assertEquals(List.of(), onSwing(moving::envelopeSprites));
            assertSame(Envelope.NONE, onSwing(() -> {
                display.computeMovementEnvelope(moving.unit);
                return display.getLastEnvelope();
            }), "Without a start path nothing is searched while switched off");
        } finally {
            onSwing(() -> {
                preferences.setMoveEnvelope(shown);
                return null;
            });
        }
    }

    @Test
    void theJumpEnvelopeContinuesTheJumpStart() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            MovementDisplay display = moving.display;
            moving.command(MoveCommand.MOVE_JUMP);
            Envelope jumped = onSwing(display::getLastEnvelope);
            Envelope fromStart = onSwing(() -> {
                assertEquals(List.of(MoveStepType.START_JUMP), display.getPlannedMovement().getStepVector().stream()
                      .map(MoveStep::getType).toList());
                display.computeMovementEnvelope(display.getPlannedMovement().clone());
                return display.getLastEnvelope();
            });
            assertEquals(MovementDisplay.GEAR_JUMP, fromStart.gear());
            assertEquals(jumped.mp(), fromStart.mp());
            assertEquals(Map.of(Band.JUMP, 36L), bandCounts(fromStart, moving.unit), "Every hex up to 3 hexes away");
        }
    }

    @Test
    void cancellingTheMountBayPromptMountsNothing() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            MovementDisplay display = moving.display;
            Entity union = new MekFileParser(new File("testresources/megamek/common/units/Union (3055).blk"))
                  .getEntity();
            onSwing(() -> {
                union.setId(3);
                union.setOwner(moving.board.player);
                ((IAero) union).land();
                union.setPosition(SOUTH_1);
                union.setDeployed(true);
                moving.board.game.addEntity(union, false);
                return null;
            });
            present(moving.gui, moving.board.view, moving.board.source);
            try {
                Asked<List<String>> cancelled = ask(moving.board.source, () -> {
                    mount(display);
                    return steps(display.getPlannedMovement());
                }, new DialogAnswer(1, List.of(), null, false, List.of()));
                assertEquals(Messages.getString("MovementDisplay.MountUnitBayNumberDialog.title"),
                      cancelled.request().title());
                assertEquals(2, cancelled.request().rows().size(), "The Union's two Mek bays");
                assertEquals(List.of(), cancelled.result(), "Cancel mounts nothing");

                dismiss();
                List<String> swingCancelled = onSwing(() -> {
                    try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
                        mount(display);
                        return steps(display.getPlannedMovement());
                    }
                });
                assertEquals(List.of(), swingCancelled, "The Swing prompt's Cancel (null) mounts nothing too");
                verify(moving.client, never()).moveEntity(anyInt(), any());
                verify(moving.client, never()).sendUpdateEntity(any(Entity.class));
            } finally {
                dismiss();
            }
        }
    }

    /** EDT: a left-button press and release on the hex, as the board view reports a click. */
    private static void click(GpuMovementFixture moving, Coords hex) {
        for (int type : new int[] { BoardViewEvent.BOARD_HEX_DRAGGED, BoardViewEvent.BOARD_HEX_CLICKED }) {
            moving.display.hexMoused(new BoardViewEvent(moving.board.view, hex, type, 0, MouseEvent.BUTTON1));
        }
    }

    /** EDT: the Mount button. */
    private static void mount(MovementDisplay display) {
        display.actionPerformed(new ActionEvent(display, ActionEvent.ACTION_PERFORMED,
              MoveCommand.MOVE_MOUNT.getCmd()));
    }

    /** Each step as "TYPE x,y f{facing} {MP used so far}". */
    private static List<String> steps(MovePath path) {
        return path.getStepVector().stream().map(step -> step.getType() + " " + step.getPosition().getX() + ","
              + step.getPosition().getY() + " f" + step.getFacing() + " " + step.getMpUsed()).toList();
    }

    /** The hexes per band of an envelope, banded with the unit's walk, run and jump MP as Swing's envelope does. */
    private static Map<Band, Long> bandCounts(Envelope envelope, Entity unit) {
        return MovementEnvelopeSpriteHandler.bands(envelope.mp(), unit.getWalkMP(), unit.getRunMP(),
                    unit.getAnyTypeMaxJumpMP(), envelope.gear()).values().stream()
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
