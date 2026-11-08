/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.BATTLEMASTER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.HEXES;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.KING_CRAB;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.LOCUST;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.MARAUDER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.PANTHER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.TIMBER_WOLF;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.WARHAMMER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuLiveBoardSpaceSmokeTest.Live;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.HexTarget;
import megamek.common.Player;
import megamek.common.actions.EntityAction;
import megamek.common.actions.KickAttackAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.AmmoMounted;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * The hud-v3 prototype's interaction test (interaction-test.mjs) in MegaMek terms (rebuild plan P2). GpuParityBattle's
 * game, the prototype's battle as real units, runs through real phase displays; a live battle view draws the real
 * source's captures, and its HUD runs the real source's services, selection, board tool and dialogs. The tests press
 * keys and buttons through the view's input as GLFW reports them. Each ported check names the prototype's line.
 * Not ported, as the plan says: the bot's declaration after the player (the server's timing), "Enter sends local
 * chat" (the network send) and "Shift+M returns to 3D" (an alias MegaMek does not have; T covers it).
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuHudInteractionSmokeTest {
    /** A hex's width on the screen in the prototype's shots, in HUD units, as GpuHudParitySmokeTest frames them. */
    private static final float HEX = 112;

    /**
     * Lines 14-27 with the own focus unit (C.5, user item 29(c)): the initiative report shows its card and Continue;
     * an own unit clicked there is the shown unit and an enemy is inspected; the DONE key sends MegaMek's Done; the
     * movement turn the server starts next selects the unit the player picked, over the phase display's own choice;
     * the camera keys pan with the force overview open, pause while its search field takes the typing and pan again
     * once Esc leaves the field; the next Esc closes the overview and Q turns the camera. In the opponent's movement
     * turn an own unit clicked is the shown unit too, and the next own turn begins with it selected. Hold all
     * remaining units then holds it now and the next unit on the next own turn.
     */
    @Test
    void theInitiativeReportContinuesIntoTheMovementTurn() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuBoardFixture board = moving.board;
            Player enemy = new Player(2, "Princess");
            ReportDisplay report = onSwing(() -> {
                when(moving.client.isMyTurn()).thenReturn(false);
                GpuHudParitySmokeTest.attach(board, moving.gui);
                GpuParityBattle.populate(board, enemy);
                GpuParityBattle.initiative(board.game, board.player, enemy);
                moving.gui.clearMovementEnvelope();
                ReportDisplay display = new ReportDisplay(moving.gui);
                board.panel = display;
                board.game.setPhase(GamePhase.INITIATIVE_REPORT);
                display.setDoneEnabled(true);
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                board.source.refresh();
                return display;
            });
            GpuDialogFixture.present(moving.gui, board.view, board.source);
            try {
                GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                    Play play = new Play(live, moving.client);
                    // 14: the initiative report shows its card, and the dock continues (B5, B8).
                    assertTrue(play.shown("initiative-card"), "The initiative card");
                    assertEquals(upper("GpuBoard.hud.dock.continue"), play.text("dock-main"));
                    // User item 29(c): outside the local turn an own unit clicked is the shown unit; an enemy is
                    // inspected and the own unit stays shown.
                    play.clickUnit(MARAUDER, Input.Buttons.LEFT, 0);
                    play.assertShown(MARAUDER, "initiative report");
                    play.clickUnit(TIMBER_WOLF, Input.Buttons.LEFT, 0);
                    assertEquals(List.of(MARAUDER, TIMBER_WOLF), List.of(live.hud.state.focus(),
                          live.hud.state.inspected), "An enemy is inspected beside the shown own unit");
                    live.capture("p2-initiative.png");
                    // 15: the DONE key is the dock's Continue: MegaMek's Done goes to the server.
                    play.press(KeyCommandBind.DONE);
                    verify(moving.client).sendDone(true);

                    // 16: the server's movement phase, the local player's turn first. The display selects its first
                    // unit; the HUD then selects the unit the player picked (C.5).
                    onSwing(() -> {
                        report.removeAllListeners();
                        board.game.setPhase(GamePhase.MOVEMENT);
                        board.panel = moving.display;
                        when(moving.client.getFirstEntityNum()).thenReturn(ATLAS);
                        when(moving.client.isMyTurn()).thenReturn(true);
                        GpuParityBattle.turns(board.game, board.player, enemy, 0);
                        GpuHudParitySmokeTest.beginTurn(moving.display, moving.client);
                        return null;
                    });
                    play.act();
                    play.act();
                    assertEquals(MARAUDER, onSwing(() -> moving.display.currentEntity().getId()),
                          "The movement turn begins with the picked unit");
                    assertEquals(MARAUDER, play.move().entityId(), "The plan is the Marauder's");

                    // 19-21: the camera keys pan with the force overview open.
                    play.press(KeyCommandBind.UNIT_OVERVIEW);
                    assertTrue(live.hud.state.overview, "The force overview opens");
                    Vector3 focus = live.camera().focus.cpy();
                    play.hold(Input.Keys.W, 1.1f);
                    assertFalse(focus.epsilonEquals(live.camera().focus, .01f), "W pans with the overview open");
                    // 22-23: its search field takes the keys while it has the focus.
                    play.click("force-overview-search");
                    play.type("was");
                    focus.set(live.camera().focus);
                    play.hold(Input.Keys.D, 1.1f);
                    assertTrue(focus.epsilonEquals(live.camera().focus, .01f), "D pans nothing while typing");
                    assertEquals("was", ((TextField) play.actor("force-overview-search")).getText());
                    // 24-25: Esc leaves the field and keeps the overview; the keys pan again.
                    play.press(KeyCommandBind.CANCEL);
                    assertFalse(live.hud.isTextEditing(), "Esc leaves the field");
                    assertTrue(live.hud.state.overview, "and keeps the overview open");
                    focus.set(live.camera().focus);
                    play.hold(Input.Keys.A, 1.6f);
                    assertFalse(focus.epsilonEquals(live.camera().focus, .01f), "A pans again");
                    // 26: the next Esc closes the overview.
                    play.press(KeyCommandBind.CANCEL);
                    assertFalse(live.hud.state.overview, "Esc closes the overview");
                    // 27: Q turns the camera.
                    float azimuth = live.camera().azimuth();
                    play.hold(Input.Keys.Q, 1.1f);
                    assertNotEquals(azimuth, live.camera().azimuth(), .5f, "Q turns the camera");

                    // The opponent's movement turn: an own unit clicked is the shown unit (user item 29(c)) ...
                    onSwing(() -> {
                        when(moving.client.isMyTurn()).thenReturn(false);
                        board.game.setTurnIndex(1, board.player.getId());
                        return null;
                    });
                    play.act();
                    assertTrue(play.shown("dock-waiting"), "The dock waits for the opponent");
                    play.clickUnit(PANTHER, Input.Buttons.LEFT, 0);
                    play.assertShown(PANTHER, "opponent's movement turn");
                    // ... and the next own turn begins with it selected: the new turn index and the display's own
                    // first unit arrive in one capture (W0.3b limit 5).
                    onSwing(() -> {
                        when(moving.client.isMyTurn()).thenReturn(true);
                        board.game.setTurnIndex(2, enemy.getId());
                        return null;
                    });
                    play.act();
                    play.act();
                    assertEquals(PANTHER, onSwing(() -> moving.display.currentEntity().getId()),
                          "The next own turn begins with the picked unit");

                    // Hold all remaining units (G15) against the server's turns: the Panther holds now (MegaMek's
                    // Hold position), the Locust on the next own turn by itself.
                    play.click("dock-more");
                    play.choose(Messages.getString("GpuBoard.hud.dock.holdAll"));
                    verify(moving.client).moveEntity(eq(PANTHER), any());
                    int holding = play.move().holdingRemaining();
                    assertTrue(holding > 0 && play.text("dock-span").equals(
                          Messages.getString("GpuBoard.hud.dock.holding", holding)), "The hold shows its count");
                    onSwing(() -> {
                        board.game.getEntity(PANTHER).setDone(true);
                        when(moving.client.isMyTurn()).thenReturn(false);
                        board.game.setTurnIndex(3, board.player.getId());
                        return null;
                    });
                    play.act();
                    onSwing(() -> {
                        when(moving.client.getFirstEntityNum()).thenReturn(LOCUST);
                        when(moving.client.isMyTurn()).thenReturn(true);
                        board.game.setTurnIndex(4, enemy.getId());
                        return null;
                    });
                    play.act();
                    play.act();
                    verify(moving.client).moveEntity(eq(LOCUST), any());
                });
            } finally {
                GpuDialogFixture.dismiss();
                onSwing(() -> {
                    report.removeAllListeners();
                    return null;
                });
            }
        }
    }

    /**
     * Lines 30-83: the Atlas's movement plan. A click plots an automatic walk, a longer route runs, Backspace undoes;
     * 1 constrains the route to walking and again returns to automatic; Ctrl+click pins a waypoint the next route
     * continues from; Shift+click and the turn button change the final facing; the fire preview evaluates from the
     * destination, and its Incoming
     * toggle hides the incoming guides only. A short right click opens the hex menu and keeps the plan; right drags
     * pan, middle and Shift+right drags orbit; a left drag on the minimap pans and keeps the draft; the chat's draft
     * survives new frames with its focus; the DONE key sends the planned route.
     */
    @Test
    void aMovementPlanIsPlottedAndConfirmed() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuBoardFixture board = moving.board;
            Player enemy = new Player(2, "Princess");
            onSwing(() -> {
                GpuHudParitySmokeTest.attach(board, moving.gui);
                GpuParityBattle.populate(board, enemy);
                GpuParityBattle.initiative(board.game, board.player, enemy);
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                board.panel = moving.display;
                GpuHudParitySmokeTest.beginTurn(moving.display, moving.client);
                board.source.refresh();
                return null;
            });
            GpuDialogFixture.present(moving.gui, board.view, board.source);
            try {
                GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                    Play play = new Play(live, moving.client);
                    Coords start = HEXES.get(ATLAS);
                    live.zoom(new Coords(13, 13), HEX);
                    // 33: a click plots an automatic walk: Walk outlined, not pressed.
                    Coords walk = new Coords(13, 13);
                    play.click(walk, Input.Buttons.LEFT, 0);
                    GpuMovePlan.Snapshot move = play.move();
                    assertEquals(walk, move.destination());
                    assertTrue(move.auto() && !move.explicit(), "An automatic plan");
                    assertEquals(GpuMovePlan.Band.WALK, move.route().getLast().band());
                    assertTrue(play.outlined("dock-mode-walk") && !play.pressed("dock-mode-walk"), "Walk is outlined");
                    // 36: a longer route runs, automatically.
                    play.click(new Coords(13, 11), Input.Buttons.LEFT, 0);
                    assertEquals(GpuMovePlan.Band.RUN, play.move().route().getLast().band());
                    assertTrue(play.outlined("dock-mode-run") && !play.outlined("dock-mode-walk"), "Run is outlined");
                    // 38: Backspace undoes back to the walk.
                    play.press(KeyCommandBind.UNDO_LAST_STEP);
                    assertEquals(walk, play.move().destination());
                    // 41: 1 constrains the route to walking: Walk pressed, no run band.
                    play.press(KeyCommandBind.MOVE_MODE_WALK);
                    move = play.move();
                    assertTrue(move.explicit() && move.mode() == GpuMovePlan.Mode.WALK, "Walk only");
                    assertTrue(play.pressed("dock-mode-walk"), "Walk is pressed");
                    assertTrue(move.envelope().containsValue(GpuMovePlan.Band.WALK)
                          && !move.envelope().containsValue(GpuMovePlan.Band.RUN), "The run band is hidden");
                    // 43: 1 again returns to automatic, both bands back.
                    play.press(KeyCommandBind.MOVE_MODE_WALK);
                    move = play.move();
                    assertEquals(GpuMovePlan.Mode.AUTO, move.mode());
                    assertTrue(move.envelope().containsValue(GpuMovePlan.Band.WALK)
                          && move.envelope().containsValue(GpuMovePlan.Band.RUN), "Walk and run bands");
                    // Ctrl+click pins a waypoint.
                    Coords pin = start.translated(0);
                    play.click(pin, Input.Buttons.LEFT, InputEvent.CTRL_DOWN_MASK);
                    assertEquals(List.of(pin), play.move().pins().stream().map(GpuMovePlan.Step::coords).toList(),
                          "One waypoint");
                    // 50: the route continues from the waypoint with the cumulative cost.
                    Coords destination = pin.translated(1);
                    play.click(destination, Input.Buttons.LEFT, 0);
                    move = play.move();
                    assertEquals(List.of(pin), move.pins().stream().map(GpuMovePlan.Step::coords).toList());
                    assertEquals(destination, move.destination());
                    assertEquals(pin, move.route().getFirst().coords(), "The route runs through the waypoint");
                    assertTrue(move.cost() >= 2, "The cost counts both legs: " + move.cost());
                    int facing = move.facing();
                    // Shift+click turns the endpoint while retaining its destination and waypoint.
                    play.click(destination.translated((facing + 1) % 6), Input.Buttons.LEFT, InputEvent.SHIFT_DOWN_MASK);
                    assertEquals((facing + 1) % 6, play.move().facing());
                    assertEquals(destination, play.move().destination());
                    assertEquals(List.of(pin), play.move().pins().stream().map(GpuMovePlan.Step::coords).toList());
                    play.press(KeyCommandBind.UNDO_LAST_STEP);
                    assertEquals(facing, play.move().facing());
                    // The turn button uses the same facing command; Shift+D is a boosted camera gesture.
                    play.click("dock-turn-right");
                    assertEquals((facing + 1) % 6, play.move().facing());
                    // 54: the fire preview evaluates from the destination, outgoing and incoming.
                    GpuHudParitySmokeTest.awaitPreview(live, live.real, ATLAS, play::asCaptured);
                    GpuFirePreview.Snapshot preview = live.frame.get().panels().preview();
                    assertTrue(preview.fromDestination() && destination.equals(preview.from()), "From the destination");
                    int outgoing = play.guides("GUIDE_OUT");
                    int incoming = play.guides("GUIDE_IN");
                    assertTrue(outgoing > 0 && incoming > 0, "Guides both ways: " + outgoing + " / " + incoming);
                    live.capture("p2-movement-preview.png");
                    // 56: the Incoming toggle hides the incoming guides only.
                    play.click("contacts-incoming");
                    assertEquals(List.of(outgoing, 0), List.of(play.guides("GUIDE_OUT"), play.guides("GUIDE_IN")));
                    play.click("contacts-incoming");

                    // 60: a short right click on a hex opens its menu and leaves the plan alone (M9).
                    play.click(new Coords(15, 12), Input.Buttons.RIGHT, 0);
                    assertTrue(play.shown("context-menu-popover"), "The hex menu");
                    assertEquals(destination, play.move().destination());
                    play.press(KeyCommandBind.CANCEL);
                    assertFalse(play.shown("context-menu-popover"), "Esc closes it");
                    // 63: a right drag pans and opens no menu.
                    Vector3 focus = live.camera().focus.cpy();
                    float azimuth = live.camera().azimuth();
                    play.drag(800, 400, -100, -50, Input.Buttons.RIGHT, 0);
                    assertFalse(focus.epsilonEquals(live.camera().focus, .01f), "A right drag pans");
                    assertEquals(azimuth, live.camera().azimuth(), .01f);
                    assertFalse(play.shown("context-menu-popover"), "and opens no menu");
                    // 65: a middle drag orbits.
                    play.drag(800, 400, 80, 20, Input.Buttons.MIDDLE, 0);
                    assertNotEquals(azimuth, live.camera().azimuth(), .5f, "A middle drag orbits");
                    // 67: so does a right drag with Shift.
                    azimuth = live.camera().azimuth();
                    play.drag(800, 400, -40, -20, Input.Buttons.RIGHT, InputEvent.SHIFT_DOWN_MASK);
                    assertNotEquals(azimuth, live.camera().azimuth(), .5f, "Shift turns a right drag into an orbit");
                    // 70: a left drag on the minimap pans the camera and keeps the draft.
                    List<GpuMovePlan.Step> route = play.move().route();
                    focus.set(live.camera().focus);
                    Actor canvas = play.actor("minimap-canvas");
                    Vector2 from = play.screen(canvas, 30, canvas.getHeight() - 30);
                    Vector2 to = play.screen(canvas, canvas.getWidth() - 30, canvas.getHeight() - 40);
                    play.drag(Math.round(from.x), Math.round(from.y), Math.round(to.x - from.x),
                          Math.round(to.y - from.y), Input.Buttons.LEFT, 0);
                    assertFalse(focus.epsilonEquals(live.camera().focus, .01f), "The minimap drag pans");
                    play.act();
                    assertEquals(route, play.move().route(), "and keeps the draft");
                    // A middle drag on the minimap orbits as on the board.
                    azimuth = live.camera().azimuth();
                    play.drag(Math.round(from.x), Math.round(from.y), 80, 20, Input.Buttons.MIDDLE, 0);
                    assertNotEquals(azimuth, live.camera().azimuth(), .5f, "A middle drag on the minimap orbits");
                    live.zoom(new Coords(13, 13), HEX);

                    // 73-74: the chat's draft survives new frames and keeps the focus.
                    play.click("chat-button");
                    play.type("hold the ridge");
                    // The prototype turns the plan behind the typing (app.act('face:1')); so does its service here,
                    // back to the left: the route has no MP left for another turn to the right.
                    live.real.moves().turn(-1);
                    play.act();
                    assertEquals(facing, play.move().facing(), "The plan turned behind the typing");
                    TextField chat = play.actor("chat-input");
                    assertEquals("hold the ridge", chat.getText());
                    assertSame(chat, live.hud.stage.getKeyboardFocus(), "The field keeps the focus");
                    play.press(KeyCommandBind.CANCEL);
                    play.press(KeyCommandBind.CANCEL);
                    assertFalse(live.hud.state.chatOpen, "Two Esc close the chat");

                    // 79-83: Backspace undoes the turn; the DONE key confirms the planned route.
                    play.press(KeyCommandBind.UNDO_LAST_STEP);
                    assertEquals((facing + 1) % 6, play.move().facing(), "Backspace undid that turn");
                    play.press(KeyCommandBind.DONE);
                    ArgumentCaptor<MovePath> sent = ArgumentCaptor.forClass(MovePath.class);
                    verify(moving.client).moveEntity(eq(ATLAS), sent.capture());
                    assertEquals(destination, sent.getValue().getFinalCoords(), "The route sent ends at the plan's");
                    assertEquals((facing + 1) % 6, sent.getValue().getFinalFacing(), "turned as planned");
                });
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    /**
     * Lines 87-134: the weapon declaration of the Atlas after the movement. Clicking a weapon arms it with its
     * solution card, clicking an enemy then assigns it (target A); a second target keeps the letters when it becomes
     * primary, and removing A makes it A; Alt+Down on a focused grip of a real target card reorders; the card's
     * ammunition select changes the weapon list's; a twist with attacks asks first and Keep attacks keeps them; Esc
     * disarms without clearing; switching to another own unit and back keeps the draft; a weapon row's right click
     * opens its menu, which the arrow keys walk; Resolve phase discloses its consequence, then declares the Atlas now
     * and the next own unit on the next own turn; the weapon playback opens the combat log.
     */
    @Test
    void aWeaponDeclarationIsDraftedAndResolved() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            GpuBoardFixture board = firing.board;
            GpuHudParitySmokeTest.firingTurn(firing);
            GpuDialogFixture.present(firing.gui, board.view, board.source);
            ReportDisplay[] report = new ReportDisplay[1];
            try {
                GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                    Play play = new Play(live, firing.client);
                    live.zoom(new Coords(13, 10), HEX);
                    // 87: after the movement comes the Atlas's declaration.
                    assertTrue(play.texts("phase-header").contains(upper("GpuBoard.hud.phase.firing")),
                          () -> "Declare attacks: " + play.texts("phase-header"));
                    GpuFireOrders.Snapshot fire = play.fire();
                    assertTrue(fire.editable() && fire.actorId() == ATLAS, "The Atlas declares");
                    assertEquals(ATLAS, live.hud.state.focus());
                    // 93: clicking a weapon arms it and opens its solution card.
                    int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
                    int srm = GpuFiringFixture.eqNum(firing, "SRM 6", Mek.LOC_LEFT_TORSO);
                    int lrm = GpuFiringFixture.eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO);
                    int leftLaser = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
                    int rightLaser = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
                    int armed = fire.selectedWeapon() == cannon ? srm : cannon;
                    play.click("weapons-name-" + armed);
                    assertEquals(armed, live.hud.state.armedWeapon, "The weapon is armed");
                    assertTrue(play.shown("solution-card"), "with its solution card");
                    // 96: then clicking the enemy assigns it: target A, and the weapon is disarmed.
                    play.clickUnit(TIMBER_WOLF, Input.Buttons.LEFT, 0);
                    assertEquals(List.of(armed + "@" + TIMBER_WOLF), orders(play.fire()));
                    assertEquals(List.of("A" + TIMBER_WOLF), letters(play.fire()));
                    assertEquals(-1, live.hud.state.armedWeapon);
                    // 102: a second target, B, made primary on its card: no letter changes.
                    play.command(orders -> orders.assign(lrm, TargetKey.unit(BATTLEMASTER)));
                    play.click(play.inside("target-card-" + BATTLEMASTER, "card-primary"));
                    assertEquals(List.of("A" + TIMBER_WOLF, "B" + BATTLEMASTER), letters(play.fire()));
                    assertTrue(play.target(BATTLEMASTER).primary(), "B is primary");
                    // 104: removing A (its pill's menu, C13) makes the remaining target A.
                    play.click(play.actor("weapons-pill-" + TIMBER_WOLF), Input.Buttons.RIGHT);
                    play.choose(Messages.getString("GpuBoard.hud.context.removeTarget"));
                    assertEquals(List.of("A" + BATTLEMASTER), letters(play.fire()));
                    assertEquals("A", play.texts("weapons-pill-" + BATTLEMASTER).getFirst(), "The pill reads A");
                    // As the prototype: every weapon that reaches it on the Timber Wolf again.
                    for (int weapon : List.of(cannon, srm, leftLaser, rightLaser)) {
                        play.command(orders -> orders.assign(weapon, TargetKey.unit(TIMBER_WOLF)));
                    }
                    live.zoom(new Coords(13, 10), HEX);
                    live.capture("p2-declaration.png");
                    // 111: Alt+Down on a focused grip of the target card reorders the attacks.
                    List<String> before = orders(play.fire());
                    int first = play.fire().attacks().stream()
                          .filter(attack -> attack.target().equals(TargetKey.unit(TIMBER_WOLF)))
                          .findFirst().orElseThrow().eqNum();
                    Actor grip = play.inside("target-card-" + TIMBER_WOLF, "card-grip-" + first);
                    play.click(grip);
                    assertSame(grip, live.hud.stage.getKeyboardFocus(), "The grip has the focus");
                    play.key(Input.Keys.DOWN, InputEvent.ALT_DOWN_MASK);
                    // The row flies one place down; once it lands the fire orders move the attack.
                    for (int frames = 0; frames < 6 && orders(play.fire()).equals(before); frames++) {
                        play.act();
                    }
                    List<String> after = orders(play.fire());
                    assertNotEquals(before, after, "Alt+Down reorders");
                    assertEquals(before.size(), after.size());
                    // 114: the card's ammunition select changes the weapon list's.
                    Actor select = play.inside("target-card-" + BATTLEMASTER, "card-ammo-" + lrm);
                    assertInstanceOf(UiButton.class, select, "The LRM's two bins make a select");
                    play.click(select);
                    play.click(play.menuItems().get(1));
                    assertEquals(1, play.weapon(lrm).loadedAmmo(), "The second bin is loaded");
                    assertEquals(play.texts("card-ammo-" + lrm), play.texts("weapons-ammo-" + lrm),
                          "The weapon list shows the card's bin");
                    // 117: a twist with attacks queued asks first.
                    List<String> drafted = orders(play.fire());
                    play.press(KeyCommandBind.TWIST_LEFT);
                    assertTrue(play.shown("dock-confirm"), "The confirm strip");
                    assertEquals(Messages.getString("GpuBoard.hud.dock.twistConfirm", drafted.size()),
                          play.text("dock-confirm-text"));
                    // 118: Keep attacks keeps the draft and the facing.
                    play.click("dock-confirm-no");
                    assertFalse(play.shown("dock-confirm"));
                    assertEquals(drafted, orders(play.fire()));
                    assertEquals(0, play.fire().twist());
                    // 121: Esc disarms the armed weapon and clears no attack.
                    int other = play.fire().selectedWeapon() == leftLaser ? rightLaser : leftLaser;
                    play.click("weapons-name-" + other);
                    assertEquals(other, live.hud.state.armedWeapon);
                    play.press(KeyCommandBind.CANCEL);
                    assertEquals(-1, live.hud.state.armedWeapon, "Esc disarms");
                    assertEquals(drafted, orders(play.fire()), "and keeps the attacks");
                    // 124: switching to another own unit and back keeps the draft (H33).
                    play.click("forces-unit-" + WARHAMMER);
                    assertEquals(WARHAMMER, play.fire().actorId());
                    play.click("forces-unit-" + ATLAS);
                    assertEquals(ATLAS, play.fire().actorId());
                    assertEquals(drafted, orders(play.fire()), "The draft is back");
                    // 127: a weapon row's right click opens its menu.
                    play.click(play.actor("weapons-row-" + leftLaser), Input.Buttons.RIGHT);
                    assertTrue(play.shown("context-menu-popover"), "The weapon's menu");
                    assertTrue(play.texts("context-menu-popover").contains(
                          Messages.getString("GpuBoard.hud.context.removeThisAttack")), "with its attack's item");
                    // 128: the arrow keys walk the menu.
                    play.key(Input.Keys.DOWN, 0);
                    play.key(Input.Keys.DOWN, 0);
                    assertTrue(play.menuItems().stream().anyMatch(UiButton::isChecked), "An item is highlighted");
                    play.press(KeyCommandBind.CANCEL);
                    assertFalse(play.shown("context-menu-popover"));

                    // 132: Resolve phase discloses the consequence first.
                    play.click("dock-resolve");
                    assertTrue(play.shown("dock-confirm"));
                    int others = play.fire().pendingUnits() - 1;
                    assertEquals(Messages.getString("GpuBoard.hud.dock.resolveConfirm", others),
                          play.text("dock-confirm-text"));
                    // 133-134: Resolve declares the Atlas's attacks now (MegaMek's Done) ...
                    play.click("dock-confirm-yes");
                    play.act();
                    List<EntityAction> declared = firing.sent(ATLAS);
                    assertEquals(drafted.size(), declared.stream().filter(WeaponAttackAction.class::isInstance)
                          .count(), () -> "The Atlas's attacks: " + declared);
                    int remaining = play.fire().autoDeclareRemaining();
                    assertTrue(remaining > 0 && play.text("dock-span").equals(
                          Messages.getString("GpuBoard.hud.dock.autoDeclaring", remaining)), "The resolve mode");
                    // ... and the next own unit on the next own turn, a hold without a draft (E3c).
                    onSwing(() -> {
                        firing.attacker.setDone(true);
                        when(firing.client.isMyTurn()).thenReturn(false);
                        board.game.setTurnIndex(1, board.player.getId());
                        return null;
                    });
                    play.act();
                    onSwing(() -> {
                        when(firing.client.getFirstEntityNum()).thenReturn(WARHAMMER);
                        when(firing.client.isMyTurn()).thenReturn(true);
                        board.game.setTurnIndex(2, firing.enemy.getId());
                        return null;
                    });
                    play.act();
                    play.act();
                    verify(firing.client).sendAttackData(eq(WARHAMMER), any());
                    // 134: the weapon playback begins: the combat log opens itself (J1).
                    report[0] = onSwing(() -> {
                        board.view.removeBoardViewListener(firing.display);
                        firing.display.removeAllListeners();
                        when(firing.client.isMyTurn()).thenReturn(false);
                        GpuParityBattle.turns(board.game, board.player, firing.enemy, 10);
                        ReportDisplay display = new ReportDisplay(firing.gui);
                        board.panel = display;
                        board.game.setPhase(GamePhase.FIRING_REPORT);
                        display.setDoneEnabled(true);
                        return display;
                    });
                    play.act();
                    assertTrue(live.hud.state.logOpen() && play.shown("log-panel"), "The combat log opens");
                });
            } finally {
                GpuDialogFixture.dismiss();
                onSwing(() -> {
                    if (report[0] != null) {
                        report[0].removeAllListeners();
                    }
                    return null;
                });
            }
        }
    }

    /**
     * Lines 135-169, the rest of the round: after the volley played, a review step onto a piloting roll pops it up;
     * replay, previous and next re-present and never change damage, heat or ammunition; the log's tabs, filters and
     * search; the physical phase's per-limb options (A.9 I4) declare the chosen kick; the end report shows the battle
     * report; T switches to the Tactical View, where clicking an own unit's icon shows it (user item 29(c)) and an
     * enemy's inspects it, and a right drag pans; T returns to 3D with the camera as it was (the prototype's Shift+M);
     * Ready for next round sends MegaMek's Done and the next round's initiative follows; zooming out grows the units.
     */
    @Test
    void theRoundIsReviewedAndTheNextOneBegins() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            GpuBoardFixture board = firing.board;
            Game game = board.game;
            Object[] displays = new Object[3];
            displays[0] = onSwing(() -> {
                when(firing.client.isMyTurn()).thenReturn(false);
                GpuHudParitySmokeTest.attach(board, firing.gui);
                GpuParityBattle.populate(board, firing.enemy);
                GpuParityBattle.afterMovement(game);
                board.view.removeBoardViewListener(firing.display);
                firing.display.removeAllListeners();
                GpuParityBattle.turns(game, board.player, firing.enemy, 10);
                ReportDisplay display = new ReportDisplay(firing.gui);
                board.panel = display;
                game.setPhase(GamePhase.FIRING_REPORT);
                display.setDoneEnabled(true);
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                board.source.refresh();
                return display;
            });
            GpuDialogFixture.present(firing.gui, board.view, board.source);
            try {
                GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                    Play play = new Play(live, firing.client);
                    live.zoom(new Coords(12, 10), HEX);
                    GpuPlaybackHistory history = live.hud.state.history;
                    // The weapon playback of shot 08, paused at the Atlas's fourth shot; Space resumes and the DONE
                    // key skips to the results (J5, J7). The King Crab's piloting roll follows the volley.
                    GpuHudParitySmokeTest.volley(live, GamePhase.FIRING_REPORT);
                    play.publishing = false;
                    GpuBoardSource.Frame volley = live.frame.get();
                    GpuReportLog.Snapshot log = volley.reports();
                    GpuReportLog.PsrItem roll = new GpuReportLog.PsrItem(1, log.round(), GamePhase.FIRING, KING_CRAB,
                          7, 9, true, "20+ damage");
                    live.frame.set(withReports(volley, new GpuReportLog.Snapshot(log.round(), log.phase(),
                          log.entries(), log.icons(), log.combat(), List.of(roll), log.moves(), log.inFlight(),
                          log.crits())));
                    play.press(KeyCommandBind.PLAYBACK_TOGGLE);
                    play.press(KeyCommandBind.DONE);
                    for (int frame = 0; frame < 300 && history.running(); frame++) {
                        live.draw(1, 1 / 30f);
                    }
                    assertFalse(history.running(), "The volley played");
                    live.draw(5, 1 / 30f);
                    assertEquals(30, history.played().size(), "29 attacks and the piloting roll are reviewable");
                    List<String> state = play.damage(game);
                    int events = live.frame.get().reports().combat().size();
                    // The piloting roll review step (J4, J10): back one step and forward onto the roll pops it up.
                    assertSame(roll, history.current().roll(), "The newest step is the roll");
                    play.press(KeyCommandBind.PLAYBACK_PREV);
                    assertNotNull(history.current().attack(), "Back on the last attack");
                    play.press(KeyCommandBind.PLAYBACK_NEXT);
                    assertSame(roll, history.current().roll(), "Forward on the roll");
                    assertTrue(play.popUps().contains(upper("GpuBoard.hud.labels.psrPassed")),
                          () -> "Its pop-up: " + play.popUps());
                    // 139-142: replay, previous and next re-present and never apply damage, heat or ammunition.
                    play.click("dock-replay");
                    assertTrue(history.replaying(), "Replay");
                    live.draw(75, 1 / 30f);
                    play.press(KeyCommandBind.PLAYBACK_TOGGLE);
                    assertFalse(history.replaying(), "Space stops the replay");
                    play.press(KeyCommandBind.PLAYBACK_PREV);
                    play.press(KeyCommandBind.PLAYBACK_PREV);
                    play.press(KeyCommandBind.PLAYBACK_NEXT);
                    live.draw(60, 1 / 30f);
                    assertEquals(state, play.damage(game), "Review changed no unit");
                    assertEquals(events, live.frame.get().reports().combat().size(), events + " events");
                    // 144: the log's tabs and filters.
                    play.click("log-tab-full");
                    play.click("log-filter-1");
                    assertFalse(play.logCards().isEmpty(), "My force's events in the full log");
                    // 146: its search filters the events.
                    play.click("log-search");
                    assertTrue(live.hud.isTextEditing(), "The search field has the focus");
                    play.type("LRM");
                    List<List<String>> found = play.logCards();
                    assertFalse(found.isEmpty(), "The LRM's event");
                    assertTrue(found.stream().allMatch(card -> String.join(" ", card).toLowerCase(Locale.ROOT)
                          .contains("lrm")), () -> "Only LRM events: " + found);
                    play.press(KeyCommandBind.CANCEL);
                    play.click("log-search");
                    play.click("log-tab-summary");
                    play.click("log-filter-0");

                    // 149-152: the physical phase: the Atlas next to the Timber Wolf, with one option per limb.
                    play.publishing = true;
                    displays[1] = onSwing(() -> {
                        ((ReportDisplay) displays[0]).removeAllListeners();
                        GpuParityBattle.moved(game, ATLAS, new Coords(13, 10), EntityMovementType.MOVE_WALK, 5);
                        GpuParityBattle.moved(game, TIMBER_WOLF, new Coords(13, 9), EntityMovementType.MOVE_WALK,
                              4);
                        game.setPhase(GamePhase.PHYSICAL);
                        when(firing.client.isMyTurn()).thenReturn(true);
                        GpuParityBattle.turns(game, board.player, firing.enemy, 0);
                        PhysicalDisplay display = new PhysicalDisplay(firing.gui);
                        board.panel = display;
                        GpuHudParitySmokeTest.beginTurn(display, firing.client);
                        display.target(game.getEntity(TIMBER_WOLF));
                        return display;
                    });
                    play.act();
                    play.act();
                    for (String limb : List.of("punchLeft", "punchRight", "kickLeft", "kickRight")) {
                        assertTrue(play.shown("dock-option-" + limb), limb + " is an option");
                    }
                    // Both punches together declare them both; a kick is chosen alone (I4).
                    play.click("dock-option-punchLeft");
                    play.click("dock-option-punchRight");
                    assertEquals(upper("GpuBoard.hud.dock.declarePunches"), play.text("dock-main"));
                    play.click("dock-option-kickLeft");
                    assertEquals(List.of(false, false, true), List.of(play.pressed("dock-option-punchLeft"),
                          play.pressed("dock-option-punchRight"), play.pressed("dock-option-kickLeft")));
                    live.capture("p2-physical.png");
                    play.press(KeyCommandBind.DONE);
                    List<EntityAction> kick = firing.sent(ATLAS);
                    assertEquals(1, kick.size());
                    KickAttackAction action = assertInstanceOf(KickAttackAction.class, kick.getFirst());
                    assertEquals(List.of(TIMBER_WOLF, KickAttackAction.LEFT), List.of(action.getTargetId(),
                          action.getLeg()), "The left leg kicks the Timber Wolf");

                    // 153-154: the end report shows the battle report.
                    displays[2] = onSwing(() -> {
                        ((PhysicalDisplay) displays[1]).removeAllListeners();
                        when(firing.client.isMyTurn()).thenReturn(false);
                        GpuParityBattle.turns(game, board.player, firing.enemy, 10);
                        ReportDisplay display = new ReportDisplay(firing.gui);
                        board.panel = display;
                        game.setPhase(GamePhase.END_REPORT);
                        display.setDoneEnabled(true);
                        return display;
                    });
                    play.act();
                    int round = live.frame.get().status().round();
                    assertTrue(live.hud.state.logOpen(), "The log opens for the end report");
                    assertTrue(play.texts("log-header").stream().anyMatch(text -> text.equalsIgnoreCase(
                          Messages.getString("GpuBoard.hud.log.battleReport", round))),
                          () -> "The battle report: " + play.texts("log-header"));
                    // 156-157: T switches to the Tactical View.
                    BoardCamera camera = live.camera();
                    live.zoom(new Coords(13, 11), HEX);
                    List<Object> pose = pose(camera);
                    play.press(KeyCommandBind.TOGGLE_ISO);
                    assertTrue(camera.tactical() && play.shown("tactical-chip"), "The Tactical View");
                    // 158-159: an own unit's icon clicked is the shown unit (user item 29(c)); an enemy's inspects.
                    play.clickUnit(WARHAMMER, Input.Buttons.LEFT, 0);
                    play.assertShown(WARHAMMER, "end report, Tactical View");
                    play.clickUnit(KING_CRAB, Input.Buttons.LEFT, 0);
                    assertEquals(KING_CRAB, live.hud.state.inspected, "An enemy icon inspects it");
                    live.capture("p2-tactical.png");
                    // 160-161: a right drag pans the Tactical View.
                    Vector3 focus = camera.focus.cpy();
                    play.drag(900, 500, -80, -50, Input.Buttons.RIGHT, 0);
                    assertFalse(focus.epsilonEquals(camera.focus, .01f), "A right drag pans the Tactical View");
                    // 162-163 (Shift+M is MegaMek's T): back in 3D with the camera as it was.
                    play.press(KeyCommandBind.TOGGLE_ISO);
                    assertFalse(camera.tactical());
                    assertEquals(pose, pose(camera), "The 3D camera is restored");
                    // 165-166: Ready for next round sends MegaMek's Done; the next round's initiative follows.
                    assertEquals(upper("GpuBoard.hud.dock.readyNextRound"), play.text("dock-main"));
                    play.press(KeyCommandBind.DONE);
                    verify(firing.client).sendDone(true);
                    onSwing(() -> {
                        game.setRoundCount(game.getRoundCount() + 1);
                        game.setPhase(GamePhase.INITIATIVE_REPORT);
                        return null;
                    });
                    play.act();
                    GpuBattleStatus.Snapshot next = live.frame.get().status();
                    assertEquals(List.of(GamePhase.INITIATIVE_REPORT, round + 1), List.of(next.phase(), next.round()));
                    assertTrue(play.shown("initiative-card"), "The next initiative");
                    // 168-169: zooming out grows the units.
                    Coords atlas = live.hud.state.presented(ATLAS).position();
                    live.zoom(atlas, 120);
                    float near = live.model(ATLAS).transform.getScaleX();
                    live.zoom(atlas, 30);
                    float far = live.model(ATLAS).transform.getScaleX();
                    assertTrue(far / near > 1.9f, "Zoomed out the units grow: " + near + " -> " + far);
                });
            } finally {
                GpuDialogFixture.dismiss();
                onSwing(() -> {
                    for (Object display : displays) {
                        if (display instanceof ReportDisplay report) {
                            report.removeAllListeners();
                        } else if (display instanceof PhysicalDisplay physical) {
                            physical.removeAllListeners();
                        }
                    }
                    return null;
                });
            }
        }
    }

    /**
     * Lead decision D1 with the real targeting display: in the own TARGETING turn a board click is MegaMek's own tool
     * (an artillery weapon targets the clicked hex), Backspace, Delete and Shift+A go to MegaMek's keys, and Esc is
     * withheld, as it would clear the declared attacks.
     */
    @Test
    void theTargetingPhaseKeepsMegaMeksTool() throws Exception {
        try (GpuTargetingFixture targeting = GpuTargetingFixture.create()) {
            GpuDialogFixture.present(targeting.gui, targeting.view, targeting.source);
            try {
                GpuLiveBoardSpaceSmokeTest.run(targeting.source, live -> {
                    Play play = new Play(live, targeting.client);
                    Coords hex = new Coords(5, 1);
                    live.zoom(hex, HEX);
                    play.click(hex, Input.Buttons.LEFT, 0);
                    Targetable target = (Targetable) field(targeting.display, "target");
                    assertInstanceOf(HexTarget.class, target, "The Long Tom targets the clicked hex");
                    assertEquals(hex, target.getPosition());
                    for (KeyCommandBind bind : List.of(KeyCommandBind.UNDO_LAST_STEP, KeyCommandBind.CLEAR_ORDERS,
                          KeyCommandBind.TWIST_LEFT)) {
                        play.press(bind);
                        verify(live.source).key(bind.key, true, bind.modifiers);
                    }
                    play.press(KeyCommandBind.CANCEL);
                    verify(live.source, never()).key(eq(KeyCommandBind.CANCEL.key), anyBoolean(), anyInt());
                });
            } finally {
                GpuDialogFixture.dismiss();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String upper(String key) {
        return UiTheme.upper(Messages.getString(key));
    }

    /** The weapon attacks in fire order: "{eqNum}@{target}". */
    private static List<String> orders(GpuFireOrders.Snapshot fire) {
        return fire.attacks().stream().map(attack -> attack.eqNum() + "@" + attack.target().id()).toList();
    }

    /** The targets in letter order: "{letter}{target}". */
    private static List<String> letters(GpuFireOrders.Snapshot fire) {
        return fire.targets().stream().map(target -> target.letter() + "" + target.key().id()).toList();
    }

    /** The 3D camera's pose: focus, zoom, azimuth and tilt. */
    private static List<Object> pose(BoardCamera camera) {
        return List.of(camera.focus.cpy(), camera.camera.zoom, camera.azimuth(), camera.tilt());
    }

    /** {@code frame} with the log {@code reports}. */
    private static GpuBoardSource.Frame withReports(GpuBoardSource.Frame frame, GpuReportLog.Snapshot reports) {
        return new GpuBoardSource.Frame(frame.scene(), frame.timeline(), frame.context(), frame.globalCommands(),
              frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
              frame.scenarioAtmosphere(), reports, frame.status(), frame.panels());
    }

    /**
     * The live view whose HUD runs the real source's services, selection, centring, board tool and dialogs, and the
     * player's gestures through the view's input; each settles the commands it posted and publishes the next capture
     * as the client sees the turn. GL thread only.
     */
    private static final class Play {
        final Live live;
        final Client client;
        /** Whether the gestures publish the real source's next capture; off while a scripted frame is shown. */
        boolean publishing = true;

        Play(Live live, Client client) throws Exception {
            this.live = live;
            this.client = client;
            GpuBoardSource real = live.real;
            GpuBoardSource hud = live.source;
            when(hud.moves()).thenReturn(real.moves());
            when(hud.fire()).thenReturn(real.fire());
            when(hud.physical()).thenReturn(real.physical());
            when(hud.record()).thenReturn(real.record());
            when(hud.toasts()).thenReturn(real.toasts());
            when(hud.los()).thenReturn(real.los());
            when(hud.chat()).thenReturn(real.chat());
            when(hud.players()).thenReturn(real.players());
            when(hud.dialog()).thenAnswer(invocation -> real.dialog());
            doAnswer(invocation -> {
                real.selectUnit(invocation.getArgument(0));
                return null;
            }).when(hud).selectUnit(anyInt());
            doAnswer(invocation -> {
                real.locateUnit(invocation.getArgument(0));
                return null;
            }).when(hud).locateUnit(anyInt());
            doAnswer(invocation -> {
                real.click(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(hud).click(any(), anyBoolean(), anyInt());
            doAnswer(invocation -> {
                real.hover(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(hud).hover(any(), anyInt());
            doAnswer(invocation -> {
                real.answer(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(hud).answer(anyLong(), any());
            GpuHudParitySmokeTest.start(live, this::asCaptured);
            System.out.println("GL renderer " + Gdx.gl.glGetString(GL20.GL_RENDERER));
        }

        /** The capture as the client has the turn: Live presents every capture as the local turn. */
        GpuBattleStatus.Snapshot asCaptured(GpuBattleStatus.Snapshot status) {
            return GpuHudParitySmokeTest.turn(status, client.isMyTurn(), status.actorId());
        }

        /** Runs what the HUD posted, then the jobs it caused, and shows the next capture. */
        void act() throws Exception {
            GpuLiveBoardSpaceSmokeTest.settle();
            if (publishing) {
                GpuHudParitySmokeTest.publish(live, this::asCaptured);
            }
            live.draw(3, .1f);
        }

        /** Presses a bind's key with its modifiers. */
        void press(KeyCommandBind bind) throws Exception {
            GpuBoardTestUi.press(bind);
            act();
        }

        /** Presses a key with the modifier keys of {@code modifiers} held. */
        void key(int key, int modifiers) throws Exception {
            GpuBoardTestUi.withModifiers(modifiers, () -> {
                Gdx.input.getInputProcessor().keyDown(key);
                Gdx.input.getInputProcessor().keyUp(key);
            });
            act();
        }

        /** Holds a key down for {@code seconds} of frames. */
        void hold(int key, float seconds) {
            InputProcessor input = Gdx.input.getInputProcessor();
            input.keyDown(key);
            live.draw(Math.round(seconds / .1f), .1f);
            input.keyUp(key);
            live.draw(1, 0);
        }

        /** Types {@code text} as the keyboard does: each letter's press, character and release. */
        void type(String text) {
            InputProcessor input = Gdx.input.getInputProcessor();
            for (char character : text.toCharArray()) {
                char lower = Character.toLowerCase(character);
                int key = lower == ' ' ? Input.Keys.SPACE : lower >= 'a' && lower <= 'z' ? Input.Keys.A + lower - 'a'
                      : Input.Keys.UNKNOWN;
                input.keyDown(key);
                input.keyTyped(character);
                input.keyUp(key);
            }
            live.draw(1, 0);
        }

        /** A short click on {@code hex}, pressed with {@code modifiers}. */
        void click(Coords hex, int button, int modifiers) throws Exception {
            Vector3 point = live.view.screenPosition(hex);
            int x = Math.round(point.x);
            int y = Math.round(point.y);
            assertFalse(live.hud.hit(x, y), hex + " lies under a HUD panel");
            GpuBoardTestUi.withModifiers(modifiers, () -> {
                Gdx.input.getInputProcessor().touchDown(x, y, 0, button);
                Gdx.input.getInputProcessor().touchUp(x, y, 0, button);
            });
            act();
        }

        /** A short click on unit {@code id}, centred in the window first. */
        void clickUnit(int id, int button, int modifiers) throws Exception {
            Coords hex = live.hud.state.presented(id).position();
            live.zoom(hex, HEX);
            click(hex, button, modifiers);
        }

        /** A drag from window point ({@code x}, {@code y}) by ({@code dx}, {@code dy}) in six moves. */
        void drag(int x, int y, int dx, int dy, int button, int modifiers) {
            GpuBoardTestUi.withModifiers(modifiers, () -> {
                InputProcessor input = Gdx.input.getInputProcessor();
                input.touchDown(x, y, 0, button);
                for (int step = 1; step <= 6; step++) {
                    input.touchDragged(x + dx * step / 6, y + dy * step / 6, 0);
                }
                input.touchUp(x + dx, y + dy, 0, button);
            });
            live.draw(3, .1f);
        }

        /** A left click on the HUD actor {@code name}. */
        void click(String name) throws Exception {
            click(actor(name));
        }

        void click(Actor actor) throws Exception {
            GpuHudParitySmokeTest.click(live, actor);
            act();
        }

        /** A click with {@code button} at the middle of {@code actor}. */
        void click(Actor actor, int button) throws Exception {
            Vector2 point = screen(actor, actor.getWidth() / 2, actor.getHeight() / 2);
            InputProcessor input = Gdx.input.getInputProcessor();
            input.touchDown(Math.round(point.x), Math.round(point.y), 0, button);
            input.touchUp(Math.round(point.x), Math.round(point.y), 0, button);
            act();
        }

        /** Chooses the item with this text in the open popover. */
        void choose(String text) throws Exception {
            UiButton item = GpuHelpMenuPlayersSmokeTest.find(actor("context-menu-popover"), text);
            assertNotNull(item, "No item \"" + text + "\"");
            click(item);
        }

        /** The items of the open popover's menu list. */
        List<UiButton> menuItems() {
            List<UiButton> items = new ArrayList<>();
            collect(actor("context-menu-popover"), items);
            assertFalse(items.isEmpty(), "No menu");
            return items;
        }

        private static void collect(Actor actor, List<UiButton> items) {
            if (actor instanceof UiMenuList list) {
                for (Actor child : list.getChildren()) {
                    if (child instanceof UiButton item) {
                        items.add(item);
                    }
                }
            } else if (actor instanceof Group group) {
                group.getChildren().forEach(child -> collect(child, items));
            }
        }

        /** The window point (y down) of a point of {@code actor}, in its own units (y up). */
        Vector2 screen(Actor actor, float x, float y) {
            return live.hud.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(x, y)));
        }

        @SuppressWarnings("unchecked")
        <T extends Actor> T actor(String name) {
            return (T) live.actor(name);
        }

        /** The actor {@code name} inside the actor {@code parent}. */
        Actor inside(String parent, String name) {
            Actor actor = ((Group) actor(parent)).findActor(name);
            assertNotNull(actor, "No " + name + " in " + parent);
            return actor;
        }

        boolean shown(String name) {
            return GpuBoardTestUi.shown(live.hud.stage.getRoot().findActor(name));
        }

        List<String> texts(String name) {
            return GpuBoardTestUi.texts(actor(name));
        }

        /** A button's caption or a label's text. */
        String text(String name) {
            Actor actor = actor(name);
            return actor instanceof TextButton button ? button.getText().toString() : texts(name).getFirst();
        }

        /** A dock mode button pressed for an explicit mode (G10). */
        boolean pressed(String name) {
            return this.<UiButton>actor(name).isChecked();
        }

        /** A dock mode button outlined for the automatic plan's band (G2). */
        boolean outlined(String name) {
            UiButton button = actor(name);
            return button.getStyle().up == button.getSkin().getDrawable("button-auto");
        }

        GpuMovePlan.Snapshot move() {
            return live.frame.get().panels().move();
        }

        GpuFireOrders.Snapshot fire() {
            return live.frame.get().panels().fire();
        }

        GpuFireOrders.Target target(int id) {
            return fire().targets().stream().filter(target -> target.key().equals(TargetKey.unit(id))).findFirst()
                  .orElseThrow();
        }

        GpuFireOrders.WeaponRow weapon(int eqNum) {
            return fire().weapons().stream().filter(row -> row.eqNum() == eqNum).findFirst().orElseThrow();
        }

        /** Runs a fire-orders command as the prototype's game module does, through the real service. */
        void command(Consumer<GpuFireOrders> command) throws Exception {
            command.accept(live.real.fire());
            act();
        }

        /** The guides of the colour {@code constant} of GpuBoardLabels (GUIDE_OUT, GUIDE_IN) drawn this frame. */
        int guides(String constant) throws Exception {
            Object labels = field(live.hud, "boardLabels");
            Color colour = (Color) field(labels, constant);
            int count = 0;
            for (Object stroke : (List<?>) field(field(labels, "strokes"), "list")) {
                if (colour.equals(field(stroke, "color"))) {
                    count++;
                }
            }
            return count;
        }

        /** The titles of the board's pop-ups shown now. */
        List<String> popUps() {
            List<String> titles = new ArrayList<>();
            popUps(live.hud.stage.getRoot(), titles);
            return titles;
        }

        private static void popUps(Actor actor, List<String> titles) {
            if ("pop-up".equals(actor.getName()) && GpuBoardTestUi.shown(actor)) {
                titles.addAll(GpuBoardTestUi.texts(actor));
            } else if (actor instanceof Group group) {
                group.getChildren().forEach(child -> popUps(child, titles));
            }
        }

        /** The texts of each log card shown now. */
        List<List<String>> logCards() {
            List<List<String>> cards = new ArrayList<>();
            for (Actor card : ((Group) actor("log-cards")).getChildren()) {
                if ("log-card".equals(card.getName()) && card.isVisible()) {
                    cards.add(GpuBoardTestUi.texts(card));
                }
            }
            return cards;
        }

        /**
         * What a review must never change: each presented unit's armor, structure and heat, and each of the client's
         * units' armor and structure by location, heat and ammunition.
         */
        List<String> damage(Game game) throws Exception {
            List<String> state = new ArrayList<>();
            for (GpuBattleStatus.UnitStatus unit : live.hud.state.presentedUnits()) {
                state.add(unit.id() + ": " + unit.armor() + " " + unit.structure() + " " + unit.heat());
            }
            state.addAll(onSwing(() -> game.getEntitiesVector().stream().map(entity -> {
                int[] armor = new int[entity.locations()];
                int[] structure = new int[entity.locations()];
                for (int location = 0; location < entity.locations(); location++) {
                    armor[location] = entity.getArmor(location);
                    structure[location] = entity.getInternal(location);
                }
                return entity.getId() + ": " + Arrays.toString(armor) + Arrays.toString(structure) + " "
                      + entity.heat + " " + entity.getAmmo().stream().map(AmmoMounted::getUsableShotsLeft).toList();
            }).toList()));
            return state;
        }

        /**
         * Unit {@code id} is the shown unit, as the user decided for clicks outside the local turn (item 29(c)): the
         * focus and the card, its forces row selected in mint, nothing inspected.
         */
        void assertShown(int id, String when) {
            assertEquals(List.of(id, Entity.NONE), List.of(live.hud.state.focus(), live.hud.state.inspected),
                  when + ": the shown unit, nothing inspected");
            UiButton row = actor("forces-unit-" + id);
            assertTrue(row.isChecked(), when + ": its forces row is selected");
            assertNotEquals(row.getSkin().getDrawable("row-foe"), row.getBackground(), when + ": never coral");
            assertEquals(UiTheme.upper(GpuUnitCard.unitName(live.hud.state.presented(id))), text("unit-card-name"),
                  when + ": the card shows it");
        }
    }
}
