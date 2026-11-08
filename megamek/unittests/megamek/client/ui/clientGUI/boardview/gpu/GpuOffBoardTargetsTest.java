/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.awaitDialog;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Function;

import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.OverlayImage;
import megamek.common.OffBoardDirection;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.GameTurn;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Off-board artillery targets (OffBoardTargetOverlay, plan P7) in a scripted TARGETING turn: an Atlas whose Long Tom
 * faces west, with observed enemy units off the west edge and off the east edge. The Legacy 2D board's arrows and their
 * click are pinned first; over the GPU window the dock's commands offer the same edges and send exactly what the click
 * sends, and a choice between units beyond one edge is a native question that Esc cancels.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuOffBoardTargetsTest {
    /** The Legacy 2D board view of the tests, and the middle of its west arrow (OffBoardTargetOverlay's layout). */
    private static final Rectangle VIEW = new Rectangle(800, 600);
    private static final Point WEST_ARROW = new Point(35, 290);
    private static final int ARCHER = 42;
    private static final int CRAB = 44;
    private static final int QUICKDRAW = 45;

    /**
     * The Legacy 2D board (characterization of the overlay): one arrow, at the west edge, as the arm-mounted Long Tom
     * faces west but not east; a press on it queues the Long Tom's artillery attack on the unit beyond the edge, and
     * Done sends it with the Long Tom's ammunition.
     */
    @Test
    void theLegacy2DArrowQueuesTheLongTomsAttackOnTheUnitBeyondItsEdge() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            batteries(targeting);
            List<OverlayImage> arrows = onSwing(() -> arrows(targeting));
            assertEquals(List.of(new Point(5, 270)), arrows.stream().map(arrow -> new Point(arrow.x(), arrow.y()))
                  .toList(), "one arrow, at the west edge's middle");
            assertTrue(onSwing(() -> targeting.overlay.isHit(new Point(WEST_ARROW), VIEW.getSize())));
            assertEquals(List.of(expected(targeting, ARCHER)), sent(targeting));
        }
    }

    /**
     * Over the GPU window, which draws no overlays: the frame's phase commands lead with the edges whose arrows show,
     * and the west edge's command queues and sends exactly what the arrow's press sends. Once the Long Tom has fired,
     * the display selects the next weapon and the command goes, as the arrow does.
     */
    @Test
    void theDocksCommandOffersTheArrowsEdgeAndSendsWhatItsPressSends() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            batteries(targeting);
            List<BoardScene.Command> commands = phaseCommands(targeting);
            BoardScene.Command west = commands.getFirst();
            assertEquals(List.of("offboard.WEST"), offBoard(commands).stream().map(BoardScene.Command::id).toList(),
                  "the edges of the arrows, first");
            assertEquals("Off-board West", west.label());
            assertTrue(west.enabled());
            run(west);
            assertEquals(List.of(), offBoard(phaseCommands(targeting)), "the Long Tom has fired");
            assertEquals(List.of(expected(targeting, ARCHER)), sent(targeting));
        }
    }

    /**
     * Two units beyond the west edge: the command asks which one, as the Legacy 2D arrow does, and the question is
     * native over the GPU window. Esc (its cancel) queues no attack and keeps the command; choosing the second unit
     * sends the attack on it. A command whose arrow has gone (another weapon selected) asks nothing.
     */
    @Test
    void aChoiceBetweenUnitsBeyondTheEdgeIsNativeAndEscCancelsIt() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            onSwing(() -> {
                targeting.battery("Archer ARC-2R.mtf", ARCHER, OffBoardDirection.WEST);
                targeting.battery("Quickdraw QKD-8X.mtf", QUICKDRAW, OffBoardDirection.WEST);
                return null;
            });
            present(targeting.gui, targeting.view, targeting.source);
            try {
                BoardScene.Command west = offBoard(phaseCommands(targeting)).getFirst();
                select(targeting, laser(targeting));
                run(west);
                assertNull(targeting.source.dialog(), "no question once the arrow has gone");
                select(targeting, targeting.longTom);
                run(targeting, offBoard(phaseCommands(targeting)).getFirst(), shown -> {
                    assertEquals(2, shown.rows().size(), shown.toString());
                    assertTrue(shown.rows().get(1).label().contains("Quickdraw"), shown.rows().toString());
                    return DialogAnswer.cancelled(shown);
                });
                assertEquals(List.of(), onSwing(targeting.display::getAttackDescriptions), "Esc queues no attack");
                run(targeting, offBoard(phaseCommands(targeting)).getFirst(),
                      shown -> new DialogAnswer(0, List.of(1), null, false, List.of()));
                assertEquals(List.of(expected(targeting, QUICKDRAW)), sent(targeting));
            } finally {
                dismiss();
            }
        }
    }

    /**
     * A command is captured with a frame and runs later, from the render thread: it queues nothing once its arrow has
     * gone (another weapon selected) or the turn it was captured in has passed.
     */
    @Test
    void aCommandWhoseArrowOrTurnHasGoneQueuesNothing() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            batteries(targeting);
            BoardScene.Command west = offBoard(phaseCommands(targeting)).getFirst();
            select(targeting, laser(targeting));
            run(west);
            assertEquals(List.of(), onSwing(targeting.display::getAttackDescriptions), "no arrow for the laser");
            assertEquals(List.of(), offBoard(phaseCommands(targeting)));

            select(targeting, targeting.longTom);
            BoardScene.Command earlier = offBoard(phaseCommands(targeting)).getFirst();
            onSwing(() -> {
                // The local player's next turn of the phase: the arrow shows again, for another turn.
                GameTurn turn = new GameTurn(targeting.board.player.getId());
                targeting.board.game.setTurnVector(List.of(turn, new GameTurn(targeting.board.player.getId())));
                targeting.board.game.setTurnIndex(1, targeting.board.player.getId());
                return null;
            });
            assertEquals(List.of("offboard.WEST"), offBoard(phaseCommands(targeting)).stream()
                  .map(BoardScene.Command::id).toList());
            run(earlier);
            assertEquals(List.of(), onSwing(targeting.display::getAttackDescriptions), "a command of an earlier turn");
        }
    }

    /** EDT: an Archer off the west edge and a Crab off the east edge, both seen by the local team. */
    private static void batteries(GpuTargetingFixture targeting) throws Exception {
        onSwing(() -> {
            targeting.battery("Archer ARC-2R.mtf", ARCHER, OffBoardDirection.WEST);
            targeting.battery("Crab CRB-20.mtf", CRAB, OffBoardDirection.EAST);
            return null;
        });
    }

    /** EDT: the arrows the overlay draws over the Legacy 2D board view. */
    private static List<OverlayImage> arrows(GpuTargetingFixture targeting) {
        var graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            return targeting.overlay.captureLayers(graphics, VIEW);
        } finally {
            graphics.dispose();
        }
    }

    /** The Atlas's first Medium Laser. */
    private static WeaponMounted laser(GpuTargetingFixture targeting) {
        return targeting.attacker.getWeaponList().stream().filter(weapon -> weapon.getName().equals("Medium Laser"))
              .findFirst().orElseThrow();
    }

    /** EDT: selects the weapon in the Unit Display, as the player does there or with the weapon keys. */
    private static void select(GpuTargetingFixture targeting, WeaponMounted weapon) throws Exception {
        onSwing(() -> {
            targeting.unitDisplay.selectWeapon(weapon);
            return null;
        });
    }

    /** The phase commands of the frame the source publishes now, as the GPU window receives them. */
    private static List<BoardScene.Command> phaseCommands(GpuTargetingFixture targeting) throws Exception {
        return onSwing(() -> {
            targeting.source.refresh();
            return targeting.source.takeFrame().scene().commands();
        });
    }

    private static List<BoardScene.Command> offBoard(List<BoardScene.Command> commands) {
        return commands.stream().filter(command -> command.id().startsWith("offboard.")).toList();
    }

    /** Runs a command as the dock's button does, from the render thread, and waits until the EDT ran it. */
    private static void run(BoardScene.Command command) throws Exception {
        command.action().run();
        onSwing(() -> null);
    }

    /**
     * Runs a command that asks a native question, answers the question as the render thread would and waits until the
     * EDT finished the command.
     */
    private static void run(GpuTargetingFixture targeting, BoardScene.Command command,
          Function<DialogRequest, DialogAnswer> answer) throws Exception {
        command.action().run();
        DialogRequest shown = awaitDialog(targeting.source);
        targeting.source.answer(shown.id(), answer.apply(shown));
        // The answer ends the question's nested loop; the command then ends in the event that asked.
        onSwing(() -> null);
        onSwing(() -> null);
    }

    /** Presses the display's Done (its ready()) and returns what it sent for the Atlas, described. */
    private static List<String> sent(GpuTargetingFixture targeting) throws Exception {
        onSwing(() -> {
            targeting.display.ready();
            return null;
        });
        return targeting.sent(targeting.attacker.getId()).stream().map(GpuOffBoardTargetsTest::describe).toList();
    }

    /** The Long Tom's artillery attack on the unit, with its ammunition, as the server receives it. */
    private static String expected(GpuTargetingFixture targeting, int unitId) {
        return "ArtilleryAttackAction by " + targeting.attacker.getId() + " at " + Targetable.TYPE_ENTITY + ":"
              + unitId + " with " + targeting.attacker.getEquipmentNum(targeting.longTom) + " and "
              + targeting.attacker.getEquipmentNum(targeting.longTom.getLinked()) + " of "
              + targeting.attacker.getId() + ", 1 turn(s)";
    }

    private static String describe(EntityAction action) {
        ArtilleryAttackAction attack = (ArtilleryAttackAction) action;
        return attack.getClass().getSimpleName() + " by " + attack.getEntityId() + " at " + attack.getTargetType()
              + ":" + attack.getTargetId() + " with " + attack.getWeaponId() + " and " + attack.getAmmoId() + " of "
              + attack.getAmmoCarrier() + ", " + attack.getTurnsTilHit() + " turn(s)";
    }
}
