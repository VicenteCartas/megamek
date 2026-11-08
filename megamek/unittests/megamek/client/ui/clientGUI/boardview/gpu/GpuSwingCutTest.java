/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.clientGUI.boardview.sprite.FiringSolutionSprite;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.common.RangeType;
import megamek.common.ToHitData;
import megamek.common.board.Coords;
import megamek.common.equipment.Cargo;
import megamek.common.game.GameTurn;
import megamek.common.util.FiringSolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The Swing cut's EDT side (I3): the board state's tactical capture leaves out what the HUD draws itself (G4), the
 * minefield display's undeployed question and the map menu go through the native window, and native line of sight
 * uses the shared ruler model without constructing a Swing ruler.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuSwingCutTest {

    /**
     * G4: the native frame leaves MegaMek's firing solution out (the board labels' to-hit badges stand for it), and the
     * sprite stays shown for the Legacy 2D board.
     */
    @Test
    void theNativeFrameLeavesOutMegaMeksFiringSolutions() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            FiringSolutionSprite solution = onSwing(() -> {
                ToHitData toHit = new ToHitData(4, "test");
                toHit.setLocation(new Coords(8, 5));
                toHit.setRange(3);
                FiringSolutionSprite sprite = new FiringSolutionSprite(fixture.view, new FiringSolution(toHit, true));
                fixture.view.addSprites(List.of(sprite));
                return sprite;
            });
            List<BoardTactical> captures = onSwing(() -> {
                fixture.view.removeSprites(List.of(solution));
                BoardTactical without = new BoardTacticalMarks().capture(fixture.view, false);
                fixture.view.addSprites(List.of(solution));
                fixture.source.refresh();
                return List.of(without, fixture.source.takeFrame().scene().tactical());
            });
            assertEquals(captures.get(0), captures.get(1), "The native frame draws everything else, and no solution");
            assertFalse(onSwing(solution::isHidden), "The sprite stays shown for the Legacy 2D board");
        }
    }

    /**
     * G4: while the plan draws the route, the native frame shows MegaMek's movement envelope and the zones,
     * whose sprites extend the envelope's: everything the board state captures but the route's steps,
     * of which this plan has none yet. The weapon's bracket, a field of fire too,
     * is the fire control's wall.
     */
    @Test
    void theNativeFrameShowsMegaMeksEnvelopeAndFieldOfFireWhileThePlanDrawsTheRoute() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            assertFalse(onSwing(moving::envelopeSprites).isEmpty(),
                  "MegaMek's board state shows the selected unit's envelope");
            moving.board.panel = moving.display;
            onSwing(() -> {
                moving.board.view.addMarks(List.of(
                      new TacticalMark.WeaponRange(new Coords(4, 4), 63, RangeType.RANGE_SHORT),
                      new TacticalMark.ControlZone(new Coords(6, 4), 63, new Color(0x12, 0x34, 0x56))));
                return null;
            });
            Planned planning = planned(moving);
            assertTrue(planning.planner(), "The Sagittaire walks in a planner gear");
            assertEquals(planning.state(), planning.frame(), "The frame shows every marking of the board state");
            List<BoardScene.RangeBorder> brackets = onSwing(() -> {
                moving.board.source.refresh();
                return moving.board.source.takeFrame().scene().rangeBorders();
            });
            assertTrue(brackets.stream().anyMatch(border -> border.coords().equals(new Coords(4, 4))),
                  "The weapon's bracket reaches the fire control's walls");
        }
    }

    /**
     * G4: while the plan draws a unit's route, the native frame leaves out MegaMek's step arrows and costs too, which
     * would lie over the route (the user's report of 2026-10-03: the old route marks mixed with the new); without a
     * plan it shows them, and the sprites stay shown for the Legacy 2D board.
     */
    @Test
    void theNativeFrameLeavesOutMegaMeksStepArrowsWhileThePlanDrawsTheRoute() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            moving.board.panel = moving.display;
            List<TacticalMark.Step> steps = onSwing(() -> {
                moving.display.plotTo(new Coords(11, 9), 0);
                return moving.board.view.getPathSteps();
            });
            assertFalse(steps.isEmpty(), "MegaMek's board state draws the plotted path's steps");
            List<BoardTactical> captures = onSwing(() -> {
                moving.board.source.refresh();
                BoardTactical frame = moving.board.source.takeFrame().scene().tactical();
                BoardTactical withSteps = new BoardTacticalMarks().capture(moving.board.view, false);
                BoardTactical withoutSteps = new BoardTacticalMarks().capture(moving.board.view, true);
                return List.of(frame, withSteps, withoutSteps);
            });
            assertNotEquals(captures.get(2), captures.get(1), "Without a plan the markings draw the steps");
            assertEquals(captures.get(2), captures.get(0), "The native frame draws everything else, and no step");
            assertEquals(steps, onSwing(moving.board.view::getPathSteps), "The board state keeps the plotted steps");

            moving.board.panel = new JPanel();
            Planned unplanned = planned(moving);
            assertFalse(unplanned.planner());
            assertEquals(unplanned.state(), unplanned.frame(), "Without a plan the frame shows MegaMek's steps");
        }
    }

    /** A capture: the plan's planner flag, the frame's tactical geometry and all of the board's markings. */
    private record Planned(boolean planner, BoardTactical frame, BoardTactical state) { }

    /** EDT, in one event: captures the frame, then the markings of the same sprites, the path's steps included. */
    private static Planned planned(GpuMovementFixture moving) throws Exception {
        return onSwing(() -> {
            moving.board.source.refresh();
            GpuBoardSource.Frame frame = moving.board.source.takeFrame();
            return new Planned(frame.panels().move().planner(), frame.scene().tactical(),
                  new BoardTacticalMarks().capture(moving.board.view, false));
        });
    }

    /**
     * D12: the minefield display's question about undeployed objects is the client's confirm, so the presented
     * window asks it natively; No keeps the turn, Yes ends it.
     */
    @Test
    void theUndeployedObjectsQuestionIsAskedInTheNativeWindow() throws Exception {
        ClientGUI gui = routingClient();
        Client client = mock(Client.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(gui.getClient()).thenReturn(client);
            fixture.player.getGroundObjectsToPlace()
                  .add(GpuListPromptBridgeTest.groundObject(new Cargo(), "Supply crate", 2));
            DeployMinefieldDisplay display = GpuListPromptBridgeTest.placingCargo(gui);
            present(gui, fixture.view, fixture.source);
            try {
                Asked<Object> kept = ask(fixture.source, () -> {
                    display.ready();
                    return null;
                }, JOptionPane.NO_OPTION, false);
                DialogRequest request = kept.request();
                assertEquals(DialogKind.MESSAGE, request.kind());
                assertEquals(Messages.getString("DeployMinefieldDisplay.undeployedTitle"), request.title());
                assertEquals(Messages.getString("DeployMinefieldDisplay.undeployedCarryables", 1), request.message());
                verify(client, never()).sendDeployGroundObjects(any());

                ask(fixture.source, () -> {
                    display.ready();
                    return null;
                }, JOptionPane.YES_OPTION, false);
                verify(client).sendDeployGroundObjects(any());
            } finally {
                dismiss();
                onSwing(() -> {
                    display.removeAllListeners();
                    return null;
                });
            }
        }
    }

    /** Both board presentations receive the same unit actions, labels and availability from the shared map menu. */
    @Test
    void bothBoardsUseTheSameMapMenuForUnitSelectionInspectionAndRecords() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        Client client = mock(Client.class);
        MovementDisplay movement = mock(MovementDisplay.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(client.getMyTurn()).thenReturn(new GameTurn(fixture.player.getId()));
            when(gui.getClient()).thenReturn(client);
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardActions actions = new GpuBoardActions(view, () -> movement, () -> false, () -> { });
            Coords hex = fixture.entity.getPosition();
            List<BoardScene.Command> legacy = onSwing(() -> actions.contextCommands(hex));
            List<String> legacyLabels = labels(legacy);
            assertTrue(legacyLabels.containsAll(List.of("Select", "View")),
                  "The Legacy 2D board's map menu selects and views the unit on the hex");
            BoardScene.Command select = legacy.stream().filter(command -> command.label().equals("Select"))
                  .findFirst().orElseThrow();
            assertEquals(List.of(Messages.getString("ClientGUI.selectMenuItem") + fixture.entity.getDisplayName()),
                  labels(select.children()));
            List<MapMenu.UnitAction> expected = List.of(
                  new MapMenu.UnitAction(MapMenu.UnitActionType.SELECT, fixture.entity.getId()),
                  new MapMenu.UnitAction(MapMenu.UnitActionType.VIEW, fixture.entity.getId()),
                  new MapMenu.UnitAction(MapMenu.UnitActionType.READOUT, fixture.entity.getId()));
            assertEquals(expected, unitActions(legacy),
                  "The shared items identify their purpose without parsing translated labels");
            select.children().getFirst().action().run();
            onSwing(() -> null);
            verify(movement).selectEntity(fixture.entity.getId());
            present(gui, view, fixture.source);
            try {
                List<BoardScene.Command> nativeCommands = onSwing(() -> actions.contextCommands(hex));
                assertEquals(legacyLabels, labels(nativeCommands), "The native board uses every shared menu group");
                assertEquals(expected, unitActions(nativeCommands),
                      "Opening the native board neither suppresses nor duplicates shared unit actions");
                assertEquals(legacy.stream().flatMap(command -> command.children().stream())
                            .map(BoardScene.Command::label).toList(),
                      nativeCommands.stream().flatMap(command -> command.children().stream())
                            .map(BoardScene.Command::label).toList(),
                      "Both renderers receive the same unit labels");

                assertEquals(expected, unitActions(onSwing(() -> actions.contextCommands(null, fixture.entity.getId()))),
                      "A roster's explicit unit uses the same actions without a hovered hex");
                assertEquals(expected, unitActions(onSwing(() -> actions.contextCommands(hex, fixture.entity.getId()))),
                      "An explicit unit already on the hovered hex appears only once");

                when(movement.currentEntity()).thenReturn(fixture.entity);
                BoardScene.Command actorSelect = onSwing(() -> actions.contextCommands(hex)).stream()
                      .flatMap(command -> command.children().stream())
                      .filter(command -> expected.getFirst().equals(command.unitAction())).findFirst().orElseThrow();
                assertFalse(actorSelect.enabled(), "Selecting the acting unit again must not clear its orders");
                actorSelect.action().run();
                onSwing(() -> null);
                verify(movement).selectEntity(fixture.entity.getId());
                when(movement.currentEntity()).thenReturn(null);
            } finally {
                dismiss();
            }
            assertEquals(legacyLabels, labels(onSwing(() -> actions.contextCommands(hex))),
                  "Closing the native board leaves the common menu intact");
        }
    }

    private static List<String> labels(List<BoardScene.Command> commands) {
        return commands.stream().map(BoardScene.Command::label).toList();
    }

    private static List<MapMenu.UnitAction> unitActions(List<BoardScene.Command> commands) {
        return commands.stream().flatMap(command -> command.unitAction() == null
                    ? unitActions(command.children()).stream() : java.util.stream.Stream.of(command.unitAction()))
              .filter(action -> List.of(MapMenu.UnitActionType.SELECT, MapMenu.UnitActionType.VIEW,
                    MapMenu.UnitActionType.READOUT).contains(action.type()))
              .toList();
    }

    /** Native LOS owns the ray, its panel and pointed heights; no Swing ruler opens over the battle window. */
    @Test
    void nativeMeasurementsAndTheMenusLineOfSightShareTheRulerWithoutSwingWindows() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            doReturn(Optional.of(view)).when(gui).getCurrentBoardState();
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            Coords from = fixture.entity.getPosition();
            Coords to = new Coords(from.getX(), from.getY() - 3);
            Set<Window> windows = onSwing(() -> Set.of(Window.getWindows()));
            present(gui, view, source);
            try {
                source.measure(from, InputEvent.ALT_DOWN_MASK, Float.NaN);
                source.measure(to, 0, Float.NaN);
                onSwing(() -> {
                    var frame = source.takeFrame();
                    var ruler = frame.panels().los();
                    assertTrue(ruler.open());
                    assertEquals(List.of(from, to), List.of(ruler.start().coords(), ruler.end().coords()));
                    assertEquals(ruler.ruler(), frame.scene().tactical().ruler(), "The panel and ray share one model");
                    assertNull(view.getRuler(), "The Legacy 2D ruler stays untouched");
                    return null;
                });
                source.changeRuler(source.takeFrame().boardGeneration(), RulerModel::clear);
                onSwing(() -> {
                    assertFalse(source.takeFrame().panels().los().open(), "Close clears the native measurement");
                    assertNull(source.takeFrame().scene().tactical().ruler());
                    return null;
                });

                Coords other = new Coords(from.getX() + 1, from.getY() - 2);
                source.measure(to, InputEvent.ALT_DOWN_MASK, Float.NaN);
                onSwing(() -> {
                    assertEquals(InputEvent.ALT_DOWN_MASK, source.takeFrame().panels().los().pending());
                    return null;
                });
                source.los().lineOfSight(fixture.entity.getId(), other, Float.NaN);
                onSwing(() -> {
                    var ruler = source.takeFrame().panels().los();
                    assertEquals(List.of(from, other), List.of(ruler.start().coords(), ruler.end().coords()),
                          "The menu replaces the pending Alt measurement with its unit and target");
                    assertEquals(0, ruler.pending());
                    return null;
                });

                // A building's floor two levels above its hex, then the ground, at the pointer's world height.
                Coords up = new Coords(from.getX() + 1, from.getY() - 1);
                Coords down = new Coords(from.getX(), from.getY() - 2);
                float level = BoardGeometry.level();
                int upLevel = onSwing(() -> view.getBoard().getHex(up).getLevel());
                int downLevel = onSwing(() -> view.getBoard().getHex(down).getLevel());
                source.measure(up, InputEvent.ALT_DOWN_MASK, (upLevel + 2) * level + .1f);
                source.measure(down, 0, downLevel * level + .1f);
                onSwing(() -> {
                    var ruler = source.takeFrame().panels().los();
                    assertEquals(List.of(up, down, 2, 0), List.of(ruler.start().coords(), ruler.end().coords(),
                          ruler.start().height(), ruler.end().height()), "LOS uses the pointed floor heights");
                    assertEquals(windows, Set.of(Window.getWindows()), "Native LOS constructs no Swing dialogs");
                    return null;
                });
            } finally {
                dismiss();
                onSwing(() -> {
                    source.close();
                    return null;
                });
            }
        }
    }

}
