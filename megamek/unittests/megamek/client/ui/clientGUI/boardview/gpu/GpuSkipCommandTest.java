/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * The turn's skip while MegaMek's "nag for no action" preference is off, which hides the movement display's Skip
 * (GpuBoardActions.skipButton, the dock's Hold position and the hold of every remaining unit): Done then holds a unit
 * without a route and sends the same move without steps as Skip; with a route Done moves, so there is no skip; and the
 * hold still ends when a prompt before the move is declined. A real MovementDisplay with a mocked Client.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuSkipCommandTest {
    private static final Coords NORTH = new Coords(11, 10);

    @Test
    void withSkipHiddenDoneHoldsAUnitWithoutARouteAsSkipDoes() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForNoAction();
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            moving.board.panel = moving.display;
            nagForNoAction(true);
            ownTurns(moving, moving.unit);
            GpuBoardSource.Frame shown = frame(moving);
            assertEquals(GpuBoardActions.SKIP_ID, shown.panels().phase().skipId(), "MegaMek's default shows Skip");
            run(shown, GpuBoardActions.SKIP_ID);

            // The preference off: MegaMek hides Skip and labels Done "Skip" while the unit has no route.
            nagForNoAction(false);
            nextTurn(moving);
            shown = frame(moving);
            assertEquals(1, onSwing(() -> moving.display.getCompletionButtons().size()), "Skip is hidden");
            assertEquals(List.of(GpuBoardActions.DONE_ID, GpuBoardActions.DONE_ID),
                  List.of(shown.panels().phase().doneId(), shown.panels().phase().skipId()));
            assertEquals(Messages.getString("MovementDisplay.Skip"), command(shown, GpuBoardActions.DONE_ID).label());
            // With a route Done moves the unit: nothing holds it.
            moving.board.source.moves().planTo(NORTH, 0, false);
            assertEquals("", frame(moving).panels().phase().skipId());
            moving.board.source.moves().clearRoute();
            shown = frame(moving);
            assertEquals(GpuBoardActions.DONE_ID, shown.panels().phase().skipId());
            run(shown, shown.panels().phase().skipId());

            // Skip (MegaMek's performDoneNoAction) and Done without a route send the same move: no step, the unit
            // where and as it stands.
            ArgumentCaptor<MovePath> sent = ArgumentCaptor.forClass(MovePath.class);
            verify(moving.client, times(2)).moveEntity(eq(moving.unit.getId()), sent.capture());
            for (MovePath path : sent.getAllValues()) {
                assertEquals(List.of(0, GpuMovementFixture.START, 0),
                      List.of(path.length(), path.getFinalCoords(), path.getFinalFacing()));
            }
        } finally {
            nagForNoAction(nag);
        }
    }

    @Test
    void holdingEveryUnitPressesDoneWhileSkipIsHiddenAndEndsWhenAPromptIsDeclined() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForNoAction();
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            moving.board.panel = moving.display;
            nagForNoAction(false);
            ownTurns(moving, moving.unit);
            frame(moving);
            moving.board.source.moves().holdAll();
            frame(moving);
            ArgumentCaptor<MovePath> sent = ArgumentCaptor.forClass(MovePath.class);
            verify(moving.client).moveEntity(eq(moving.unit.getId()), sent.capture());
            assertEquals(0, sent.getValue().length(), "Done held the Sagittaire");
        } finally {
            nagForNoAction(nag);
        }

        // A Storm Raider with a jammed rotary autocannon: before it ends the turn MegaMek asks whether the unit is
        // really done; No keeps its turn, so the hold ends with its toast, whether Skip shows or not.
        for (boolean skipShown : List.of(false, true)) {
            try (GpuMovementFixture moving = GpuMovementFixture.create()) {
                Entity raider = jammedRaider(moving);
                moving.board.panel = moving.display;
                nagForNoAction(skipShown);
                ownTurns(moving, raider);
                GpuBoardSource source = moving.board.source;
                GpuDialogFixture.present(moving.gui, moving.board.view, source);
                try {
                    assertEquals(skipShown ? GpuBoardActions.SKIP_ID : GpuBoardActions.DONE_ID,
                          frame(moving).panels().phase().skipId());
                    source.moves().holdAll();
                    DialogRequest asked = GpuDialogFixture.awaitDialog(source);
                    assertEquals(Messages.getString("MovementDisplay.ConfirmUnJamRACDlg.title"), asked.title());
                    // No: the answer ends the prompt's loop, and the hold's event goes on to end the hold.
                    source.answer(asked.id(), new DialogAnswer(1, List.of(), null, false, List.of()));
                    assertEquals(0, frame(moving).panels().move().holdingRemaining(), "The hold ended");
                    verify(moving.gui).addToast(ToastLevel.WARNING,
                          Messages.getString("GpuBoard.hud.move.holdStopped", raider.getShortName()));
                    verify(moving.client, never()).moveEntity(anyInt(), any(MovePath.class));
                } finally {
                    GpuDialogFixture.dismiss();
                    nagForNoAction(nag);
                }
            }
        }
    }

    /** EDT: the preference, which the phase display follows at once (it hides or shows Skip). */
    private static void nagForNoAction(boolean nag) throws Exception {
        onSwing(() -> {
            GUIPreferences.getInstance().setNagForNoAction(nag);
            return null;
        });
    }

    /**
     * Scripted turns instead of a server: three turns of the local player, the first beginning now, which the client
     * follows; MegaMek's turn start selects {@code unit} (its first unit), and the test selects it too.
     */
    private static void ownTurns(GpuMovementFixture moving, Entity unit) throws Exception {
        onSwing(() -> {
            // Stubbed on the EDT, where the source's timer and jobs call the client.
            when(moving.client.getFirstEntityNum()).thenReturn(unit.getId());
            Game game = moving.board.game;
            int local = moving.board.player.getId();
            game.setTurnVector(List.of(new GameTurn(local), new GameTurn(local), new GameTurn(local)));
            game.setTurnIndex(0, Player.PLAYER_NONE);
            moving.display.selectEntity(unit.getId());
            return null;
        });
    }

    /** The server's next turn of the local player after a move, which begins a new turn in the display. */
    private static void nextTurn(GpuMovementFixture moving) throws Exception {
        onSwing(() -> {
            Game game = moving.board.game;
            game.setTurnIndex(game.getTurnIndex() + 1, moving.board.player.getId());
            return null;
        });
    }

    /** A Storm Raider STM-R4 of the local player at (8, 8) whose rotary autocannon jammed, selected in the display. */
    private static Entity jammedRaider(GpuMovementFixture moving) throws Exception {
        Entity raider = new MekFileParser(new File("testresources/megamek/common/units/Storm Raider STM-R4.mtf"))
              .getEntity();
        onSwing(() -> {
            raider.setId(6);
            raider.setOwner(moving.board.player);
            raider.setPosition(new Coords(8, 8));
            raider.setFacing(0);
            raider.setDeployed(true);
            raider.getTotalWeaponList().stream()
                  .filter(weapon -> weapon.getType().getAmmoType() == AmmoType.AmmoTypeEnum.AC_ROTARY)
                  .forEach(weapon -> weapon.setJammedImmediately(true));
            moving.board.game.addEntity(raider, false);
            moving.display.selectEntity(raider.getId());
            return null;
        });
        return raider;
    }

    /** The frame the source publishes once the posted commands, and the events they posted, have run. */
    private static GpuBoardSource.Frame frame(GpuMovementFixture moving) throws Exception {
        drainSwing();
        return onSwing(() -> {
            moving.board.source.refresh();
            return moving.board.source.takeFrame();
        });
    }

    private static BoardScene.Command command(GpuBoardSource.Frame frame, String id) {
        return frame.scene().commands().stream().filter(command -> command.id().equals(id)).findFirst()
              .orElseThrow();
    }

    /** Runs the frame's phase command, as the dock's button does, and lets it run on the EDT. */
    private static void run(GpuBoardSource.Frame frame, String id) throws Exception {
        command(frame, id).action().run();
        drainSwing();
    }

    /**
     * Lets every event posted so far run, and the events they post in turn: a command, the display's updates and the
     * source's capture jobs, as GpuFirePreviewTest drains them.
     */
    private static void drainSwing() throws Exception {
        for (int pass = 0; pass < 50; pass++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }
}
