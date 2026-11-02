/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyGameMaster.Ballot;
import megamek.client.lobby.LobbyGameMaster.Choice;
import megamek.client.lobby.LobbyGameMaster.Seat;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Clicks the lobby's Game Master seat and its card through the stage, as a pointer would. */
@Tag("on-demand")
class LobbyGameMasterSeatSmokeTest {

    private static final LobbyGameMaster OPEN = new LobbyGameMaster(Seat.OPEN, "", "", List.of(), false);
    private static final LobbyGameMaster MINE = new LobbyGameMaster(Seat.MINE, "Local", "", List.of(), false);

    @Test
    void anEmptySeatAsksForTheRoleFromItsCard() {
        UiTestStage.run(ui -> {
            Moves moves = new Moves();
            LobbyGameMasterSeat seat = place(ui, moves, OPEN);

            click(ui, seat.button());
            assertTrue(seat.card().isVisible(), "a click opens the seat's card");
            Actor claim = seat.card().findActor("lobby-gm-claim");
            Vector2 at = claim.localToStageCoordinates(new Vector2(claim.getWidth() / 2, claim.getHeight() / 2));
            assertTrue(at.y > seat.card().getY(), "the card holds its whole content, its button too");
            click(ui, claim);
            assertEquals(List.of("claim"), moves.made);
            assertFalse(seat.card().isVisible(), "the card closes on its action");
        });
    }

    @Test
    void aVoteThatWaitsForThePlayerOpensItsCardAndTakesTheBallot() {
        UiTestStage.run(ui -> {
            Moves moves = new Moves();
            LobbyGameMasterSeat seat = place(ui, moves, OPEN);
            LobbyGameMaster vote = vote(Choice.PENDING);

            seat.show(vote);
            ui.draw();
            assertTrue(seat.card().isVisible(), "a vote waiting for the player opens the card on its own");
            assertEquals(3, pips(seat.button()), "one pip per voter");
            click(ui, seat.card().findActor("lobby-gm-allow"));
            assertEquals(List.of("vote yes"), moves.made);
            assertFalse(seat.card().isVisible());

            seat.show(vote(Choice.YES));
            ui.draw();
            assertFalse(seat.card().isVisible(), "the same vote does not open the card again");
            click(ui, seat.button());
            assertNull(seat.card().findActor("lobby-gm-allow"), "a cast ballot leaves nothing to vote");

            seat.show(vote(Choice.PENDING));
            ui.draw();
            assertTrue(seat.card().isVisible(), "an open card follows the vote");
            click(ui, seat.card().findActor("lobby-gm-deny"));
            assertEquals(List.of("vote yes", "vote no"), moves.made);
        });
    }

    @Test
    void theRequesterMayWithdraw() {
        UiTestStage.run(ui -> {
            Moves moves = new Moves();
            LobbyGameMaster own = new LobbyGameMaster(Seat.VOTE, "", "Local", List.of(
                  new Ballot("Local", Choice.YES, true), new Ballot("Other", Choice.PENDING, false)), false);
            LobbyGameMasterSeat seat = place(ui, moves, own);

            assertFalse(seat.card().isVisible(), "the requester's own vote does not ask for a ballot");
            click(ui, seat.button());
            click(ui, seat.card().findActor("lobby-gm-withdraw"));
            assertEquals(List.of("withdraw"), moves.made);
        });
    }

    @Test
    void theGameMasterStepsDownFromTheLitSeat() {
        UiTestStage.run(ui -> {
            Moves moves = new Moves();
            LobbyGameMasterSeat seat = place(ui, moves, MINE);

            assertTrue(seat.button().isChecked(), "the local Game Master's seat is lit");
            click(ui, seat.button());
            click(ui, seat.card().findActor("lobby-gm-step-down"));
            assertEquals(List.of("claim"), moves.made);
        });
    }

    @Test
    void aLockedOrTakenSeatOffersNothing() {
        UiTestStage.run(ui -> {
            Moves moves = new Moves();
            LobbyGameMasterSeat seat = place(ui, moves, LobbyGameMaster.OFF);

            assertTrue(seat.button().isDisabled());
            click(ui, seat.button());
            assertFalse(seat.card().isVisible(), "a locked seat opens no card");

            seat.show(new LobbyGameMaster(Seat.HELD, "Other", "", List.of(), false));
            ui.draw();
            click(ui, seat.button());
            assertTrue(seat.card().isVisible(), "a taken seat's card says who holds it");
            assertNull(seat.card().findActor("lobby-gm-claim"));
            assertNull(seat.card().findActor("lobby-gm-step-down"));
            assertTrue(moves.made.isEmpty());
        });
    }

    @Test
    void theSeatFlashesWhenItChangesHandsUnlessMotionIsReduced() {
        UiTestStage.run(ui -> {
            LobbyGameMasterSeat seat = place(ui, new Moves(), OPEN);
            seat.show(MINE);
            ui.draw();
            UiButton button = seat.button();
            List<Boolean> faces = new ArrayList<>();
            for (int frame = 0; frame < 60; frame++) {
                ui.stage.act(1 / 60f);
                faces.add(button.isChecked());
            }
            assertTrue(faces.contains(false) && faces.contains(true), "the seat flashes: " + faces);
            assertTrue(button.isChecked(), "and ends lit");

            LobbyGameMasterSeat still = new LobbyGameMasterSeat(ui.kit, new Moves(), false);
            still.show(OPEN);
            still.show(MINE);
            assertEquals(0, still.button().getActions().size, "reduce motion keeps it still");
        });
    }

    private static LobbyGameMaster vote(Choice local) {
        return new LobbyGameMaster(Seat.VOTE, "", "Other", List.of(new Ballot("Other", Choice.YES, false),
              new Ballot("Local", local, true), new Ballot("Third", Choice.PENDING, false)), true);
    }

    /** The pips on the seat's face: the icons of the table it holds after its label. */
    private static int pips(UiButton button) {
        for (Actor child : button.getChildren()) {
            if (child instanceof Table pips) {
                return pips.getChildren().size;
            }
        }
        return 0;
    }

    private static LobbyGameMasterSeat place(UiTestStage ui, Moves moves, LobbyGameMaster shown) {
        LobbyGameMasterSeat seat = new LobbyGameMasterSeat(ui.kit, moves, true);
        seat.attach(ui.stage.getRoot());
        Table holder = new Table();
        holder.add(seat);
        ui.window.addActor(holder);
        holder.setBounds(400, 600, 400, 60);
        seat.show(shown);
        holder.validate();
        ui.draw();
        return seat;
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor, "the control is there");
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }

    private static final class Moves implements LobbyGameMasterSeat.SeatActions {
        private final List<String> made = new ArrayList<>();

        @Override
        public void claim() {
            made.add("claim");
        }

        @Override
        public void vote(boolean allow) {
            made.add(allow ? "vote yes" : "vote no");
        }

        @Override
        public void withdraw() {
            made.add("withdraw");
        }
    }
}
