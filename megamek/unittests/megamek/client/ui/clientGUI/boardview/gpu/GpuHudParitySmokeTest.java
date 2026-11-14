/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.ARCHER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.ATLAS_MOVED;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.BATTLEMASTER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.ENEMY_LOCUST;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.KING_CRAB;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.LOCUST;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.MARAUDER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.PANTHER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.TIMBER_WOLF;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuParityBattle.WARHAMMER;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.gpu.GpuLiveBoardSpaceSmokeTest.Live;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.WeaponMounted;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Per-screenshot parity of the live battle view with the hud-v3 mock (rebuild plan P1). For each of the mock's 16
 * shots a real GpuBattleView draws GpuParityBattle's game in the shot's state, through the real phase displays the
 * tests can build (movement, firing, physical and report displays) and the HUD's own state for its panels, in a
 * window of the shot's size with one HUD unit per window pixel. Each shot checks that every panel of the mock is shown
 * at the mock's width (within 2 units), where the live layout places it: a 10-unit screen gap (GpuBoardHud#GAP) and
 * the utility bar over the right column, not the prototype's 20 and taller bar. It also checks that no label shows a
 * missing message key, and the texts the data allows; it writes parity-NN.png, parity-NN.txt
 * (each panel's bounds beside the mock's, and its texts) and, with the mock at hand, parity-NN-sbs.png, the capture
 * beside the mock's shot.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuHudParitySmokeTest {
    /** The tolerance of a panel's width against the mock's frame, in HUD units. */
    private static final float TOLERANCE = 2;
    /** A hex's width on the screen in the mock's 3D shots, in HUD units (G16: about 112). */
    private static final float MOCK_HEX = 112;
    /** The mock's camera tilt from straight down, in degrees, from its hexes' proportions (about .6 high per wide). */
    private static final float MOCK_TILT = 45;

    /** Which edge of a panel the prototype's layout anchors; free panels (cards, menus) are only reported. */
    private enum Edge { TOP, BOTTOM, FREE }

    /** One panel of a shot: its actor and the mock's frame of it, x and y down from the top left, in CSS pixels. */
    private record Region(String actor, int x, int y, int width, int height, Edge edge) { }

    /** One shot of the volley (shots 08 and 10) and its card's roll. */
    private record Attack(int attacker, int target, String weapon, String location, int damage, int needed,
          int rolled) { }

    private static Region top(String actor, int x, int y, int width, int height) {
        return new Region(actor, x, y, width, height, Edge.TOP);
    }

    private static Region bottom(String actor, int x, int y, int width, int height) {
        return new Region(actor, x, y, width, height, Edge.BOTTOM);
    }

    private static Region free(String actor, int x, int y, int width, int height) {
        return new Region(actor, x, y, width, height, Edge.FREE);
    }

    // The mock's frames, found by their corner brackets in the shots (SP/p1/corners.py).
    private static final Region HEADER = top("phase-header", 20, 20, 300, 84);
    private static final Region HEADER_RIBBON = top("phase-header", 20, 20, 300, 100);
    private static final Region MINIMAP = top("minimap", 1590, 90, 310, 210);
    // The forces list is narrower than the mock's column of 300 (the user's request of 2026-10-02).
    private static final int FORCES_WIDTH = Math.round(GpuHud.FORCES_WIDTH);
    private static final Region FORCES = top("forces-panel", 20, 116, FORCES_WIDTH, 508);
    private static final Region FORCES_RIBBON = top("forces-panel", 20, 132, FORCES_WIDTH, 508);
    private static final Region CARD = bottom("unit-card", 20, 837, 300, 223);
    private static final Region CARD_CHIP = bottom("unit-card", 20, 808, 300, 252);
    private static final Region PLAN_DOCK = bottom("command-dock", 670, 875, 580, 174);
    private static final Region FIRE_DOCK = bottom("command-dock", 670, 891, 580, 158);
    private static final Region PLAYBACK_DOCK = bottom("command-dock", 670, 885, 580, 164);
    private static final Region WEAPONS = top("weapons-panel", 1590, 312, 310, 556);
    private static final Region SOLUTION = bottom("solution-card", 1590, 880, 310, 130);

    @Test
    void initiative() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            Player enemy = new Player(2, "Princess");
            ReportDisplay report = onSwing(() -> {
                // A report phase has no turn of the local player.
                when(moving.client.isMyTurn()).thenReturn(false);
                attach(moving.board, moving.gui);
                GpuParityBattle.populate(moving.board, enemy);
                GpuParityBattle.initiative(moving.board.game, moving.board.player, enemy);
                // The fixture's movement turn has ended: its envelope is gone.
                moving.gui.clearMovementEnvelope();
                ReportDisplay display = new ReportDisplay(moving.gui);
                moving.board.panel = display;
                moving.board.game.setPhase(GamePhase.INITIATIVE_REPORT);
                display.setDoneEnabled(true);
                moving.board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                moving.board.source.refresh();
                return display;
            });
            try {
                GpuLiveBoardSpaceSmokeTest.run(moving.board.source, live -> {
                    start(live, status -> turn(status, false, Entity.NONE));
                    publish(live, status -> turn(status, false, Entity.NONE));
                    live.draw(10, .1f);
                    live.zoom(new Coords(13, 10), MOCK_HEX);
                    // No fire preview in a report phase, so no contacts panel.
                    shot(live, "01", "01-initiative.jpg", HEADER, MINIMAP, FORCES,
                          top("initiative-card", 680, 216, 560, 259),
                          bottom("command-dock", 670, 933, 580, 117));
                });
            } finally {
                onSwing(() -> {
                    report.removeAllListeners();
                    return null;
                });
            }
        }
    }

    @Test
    void movement() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuBoardFixture board = moving.board;
            Player enemy = new Player(2, "Princess");
            onSwing(() -> {
                attach(board, moving.gui);
                GpuParityBattle.populate(board, enemy);
                GpuParityBattle.initiative(board.game, board.player, enemy);
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                // The round's initiative report, which writes the chat's round line (shot 16), then the movement.
                board.game.setPhase(GamePhase.INITIATIVE_REPORT);
                board.source.refresh();
                board.game.setPhase(GamePhase.MOVEMENT);
                board.panel = moving.display;
                beginTurn(moving.display, moving.client);
                board.source.refresh();
                return null;
            });
            GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                start(live, UnaryOperator.identity());
                publish(live, UnaryOperator.identity());
                live.draw(10, .1f);
                live.zoom(ATLAS_MOVED, MOCK_HEX);

                // 11: no route yet, the forces as a grid.
                live.hud.state.forcesGrid = true;
                shot(live, "11", "11-force-grid.jpg", HEADER_RIBBON, MINIMAP, top("forces-panel", 20, 132, 630, 282),
                      top("contacts-panel", 1590, 312, 310, 506), CARD, PLAN_DOCK);
                live.hud.state.forcesGrid = false;

                // 16: the chat open with a draft.
                live.hud.state.chatOpen = true;
                live.draw(2, .1f);
                TextField field = live.hud.stage.getRoot().findActor("chat-input");
                field.setText("Moving Alpha to the ridge");
                field.setCursorPosition(field.getText().length());
                live.hud.stage.setKeyboardFocus(field);
                shot(live, "16", "16-chat.jpg", HEADER_RIBBON, MINIMAP, FORCES_RIBBON,
                      bottom("chat-panel", 1560, 694, 340, 320), CARD, PLAN_DOCK);
                live.hud.stage.setKeyboardFocus(null);
                live.hud.state.chatOpen = false;

                // 12: the force overview, grouped by status.
                live.hud.state.overview = true;
                live.draw(2, .1f);
                click(live, "force-overview-grouping-status");
                shot(live, "12", "12-force-overview.jpg", HEADER_RIBBON,
                      new Region("force-overview", 20, 90, 1920 - 2 * (int) GpuBoardHud.GAP, 920, Edge.TOP));
                live.hud.state.overview = false;

                // 02: a walk of two hexes north, the fire preview from its end with the Timber Wolf's row open.
                plan(live, board, ATLAS_MOVED, false);
                click(live, "contacts-preview-" + TIMBER_WOLF);
                shot(live, "02", "02-movement-fire-preview.jpg", HEADER_RIBBON, MINIMAP, FORCES_RIBBON,
                      top("contacts-panel", 1590, 312, 310, 698), CARD, PLAN_DOCK);
                click(live, "contacts-preview-" + TIMBER_WOLF);

                // 14: 100 v 100, a run of five hexes, the forces as a grid.
                plan(live, board, new Coords(13, 10), false);
                live.hud.state.forcesGrid = true;
                crowd(live);
                live.zoom(new Coords(13, 11), 85);
                shot(live, "14", "14-large-battle-100v100.jpg", HEADER_RIBBON, MINIMAP,
                      top("forces-panel", 20, 132, 630, 693), top("contacts-panel", 1590, 312, 310, 698), CARD,
                      bottom("command-dock", 670, 875, 580, 175));
                live.hud.state.forcesGrid = false;
                onSwing(() -> {
                    board.source.moves().clearRoute();
                    return null;
                });

                // 03: the Panther acts, a pinned waypoint and a route on to the north-east.
                onSwing(() -> {
                    moving.display.selectEntity(PANTHER);
                    // The plan takes the new unit with the source's next capture; until then it drops commands.
                    board.source.refresh();
                    return null;
                });
                plan(live, board, new Coords(14, 14), true);
                plan(live, board, new Coords(15, 12), false);
                live.zoom(new Coords(14, 13), MOCK_HEX);
                shot(live, "03", "03-waypoint-route.jpg", HEADER_RIBBON, MINIMAP, FORCES_RIBBON,
                      top("contacts-panel", 1590, 312, 310, 506), CARD, PLAN_DOCK);

                // 04: the Atlas walked and its turn ended; Princess moves, the Warhammer is up next (C.5).
                onSwing(() -> {
                    board.source.moves().clearRoute();
                    moving.display.selectEntity(ATLAS);
                    return null;
                });
                publish(live, UnaryOperator.identity());
                live.draw(5, .1f);
                onSwing(() -> {
                    GpuParityBattle.moved(board.game, ATLAS, ATLAS_MOVED, EntityMovementType.MOVE_WALK, 2);
                    board.game.getEntity(ATLAS).setDone(true);
                    board.game.setTurnIndex(1, Player.PLAYER_NONE);
                    when(moving.client.isMyTurn()).thenReturn(false);
                    // As MovementDisplay.endMyTurn: the route and the envelope go with the turn.
                    board.view.clearMovementData();
                    moving.gui.clearMovementEnvelope();
                    return null;
                });
                publish(live, status -> turn(status, false, Entity.NONE));
                live.draw(10, .1f);
                awaitPreview(live, board.source, WARHAMMER, status -> turn(status, false, Entity.NONE));
                live.zoom(new Coords(14, 13), MOCK_HEX);
                shot(live, "04", "04-opponent-turn.jpg", HEADER_RIBBON, MINIMAP, FORCES_RIBBON,
                      top("contacts-panel", 1590, 312, 310, 506), CARD, bottom("command-dock", 670, 933, 580, 116));
            });
        }
    }

    @Test
    void firing() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            declare(firing);
            GpuLiveBoardSpaceSmokeTest.run(firing.board.source, live -> {
                start(live, UnaryOperator.identity());
                publish(live, UnaryOperator.identity());
                live.draw(10, .1f);
                live.zoom(new Coords(13, 10), MOCK_HEX);

                // 05: four weapons on the Timber Wolf (A), the LRM on the BattleMaster (B), the AC/20 selected.
                shot(live, "05", "05-weapon-declaration.jpg", HEADER, MINIMAP, FORCES, WEAPONS, CARD_CHIP, SOLUTION,
                      FIRE_DOCK, free("target-card-" + TIMBER_WOLF, 1005, 40, 300, 266));

                // 13: the King Crab's menu.
                Vector3 crab = live.view.screenPosition(firing.board.game.getEntity(KING_CRAB).getPosition());
                live.hud.boardClick(firing.board.game.getEntity(KING_CRAB).getPosition(), KING_CRAB,
                      Input.Buttons.RIGHT, 0, Math.round(crab.x), Math.round(crab.y), Float.NaN);
                shot(live, "13", "13-context-menu.jpg", HEADER, MINIMAP, FORCES, WEAPONS, CARD_CHIP, SOLUTION,
                      FIRE_DOCK, free("context-menu-popover", 789, 193, 260, 241));
                live.hud.boardPress();

                // 07: the Tactical View.
                live.view.setTacticalView(true);
                live.zoom(new Coords(13, 10), 90);
                shot(live, "07", "07-tactical-view.jpg", HEADER, MINIMAP, FORCES, WEAPONS, CARD_CHIP, SOLUTION,
                      FIRE_DOCK, free("target-card-" + TIMBER_WOLF, 764, 92, 300, 266));
                live.view.setTacticalView(false);

                // 06: the AC/20 moves to the King Crab (C), a third target.
                int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(KING_CRAB)));
                GpuFiringFixture.command(firing, fire -> fire.selectWeapon(cannon));
                GpuFiringFixture.command(firing, fire -> fire.focusTarget(TargetKey.unit(TIMBER_WOLF)));
                publish(live, UnaryOperator.identity());
                live.zoom(new Coords(13, 10), MOCK_HEX);
                shot(live, "06", "06-three-targets.jpg", HEADER, MINIMAP, FORCES, WEAPONS, CARD_CHIP, SOLUTION,
                      FIRE_DOCK, free("target-card-" + TIMBER_WOLF, 869, 76, 299, 218));
                GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(TIMBER_WOLF)));
                GpuFiringFixture.command(firing, fire -> fire.selectWeapon(cannon));
                GpuFiringFixture.command(firing, fire -> fire.focusTarget(TargetKey.unit(TIMBER_WOLF)));
            });

            // 15: the declaration of 05 in a 1280 x 720 window.
            GpuLiveBoardSpaceSmokeTest.run(firing.board.source, 1280, 720, live -> {
                start(live, UnaryOperator.identity());
                publish(live, UnaryOperator.identity());
                live.draw(10, .1f);
                live.zoom(new Coords(13, 10), 75);
                shot(live, "15", "15-compact-1280x720.jpg", top("phase-header", 16, 16, 250, 81),
                      top("solution-card", 712, 90, 270, 146), top("minimap", 994, 90, 270, 180),
                      top("forces-panel", 16, 109, Math.min(FORCES_WIDTH, 250), 356),
                      top("weapons-panel", 994, 282, 270, 368),
                      bottom("unit-card", 16, 477, 250, 227), bottom("command-dock", 391, 531, 499, 158));
            });
        }
    }

    @Test
    void physical() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            GpuBoardFixture board = firing.board;
            PhysicalDisplay physical = onSwing(() -> {
                attach(board, firing.gui);
                GpuParityBattle.populate(board, firing.enemy);
                GpuParityBattle.afterMovement(board.game);
                // The Atlas closed in on the Timber Wolf; its arm lasers fired this turn; the King Crab fell.
                GpuParityBattle.moved(board.game, ATLAS, new Coords(13, 10), EntityMovementType.MOVE_WALK, 5);
                GpuParityBattle.moved(board.game, TIMBER_WOLF, new Coords(13, 9), EntityMovementType.MOVE_WALK, 4);
                Entity atlas = board.game.getEntity(ATLAS);
                for (WeaponMounted weapon : atlas.getWeaponList()) {
                    if (weapon.getLocation() == Mek.LOC_LEFT_ARM || weapon.getLocation() == Mek.LOC_RIGHT_ARM) {
                        weapon.setUsedThisRound(true);
                    }
                }
                board.game.getEntity(KING_CRAB).setProne(true);
                board.view.removeBoardViewListener(firing.display);
                firing.display.removeAllListeners();
                board.game.setPhase(GamePhase.PHYSICAL);
                GpuParityBattle.turns(board.game, board.player, firing.enemy, 0);
                PhysicalDisplay display = new PhysicalDisplay(firing.gui);
                board.panel = display;
                beginTurn(display, firing.client);
                display.target(board.game.getEntity(TIMBER_WOLF));
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                board.source.refresh();
                return display;
            });
            try {
                GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                    start(live, UnaryOperator.identity());
                    publish(live, UnaryOperator.identity());
                    live.draw(10, .1f);
                    live.zoom(new Coords(13, 10), 130);
                    Actor kick = live.hud.stage.getRoot().findActor("dock-option-kickLeft");
                    if (kick != null) {
                        click(live, kick);
                    }
                    // No fire preview in the physical phase, so no contacts panel.
                    shot(live, "09", "09-physical-attacks.jpg", HEADER, MINIMAP, FORCES,
                          bottom("unit-card", 20, 809, 300, 251),
                          bottom("command-dock", 670, 871, 580, 179));
                });
            } finally {
                onSwing(() -> {
                    physical.removeAllListeners();
                    return null;
                });
            }
        }
    }

    @Test
    void playback() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            GpuBoardFixture board = firing.board;
            ReportDisplay report = onSwing(() -> {
                when(firing.client.isMyTurn()).thenReturn(false);
                attach(board, firing.gui);
                GpuParityBattle.populate(board, firing.enemy);
                GpuParityBattle.afterMovement(board.game);
                board.view.removeBoardViewListener(firing.display);
                firing.display.removeAllListeners();
                // The firing phase's turns are over.
                GpuParityBattle.turns(board.game, board.player, firing.enemy, 10);
                ReportDisplay display = new ReportDisplay(firing.gui);
                board.panel = display;
                board.game.setPhase(GamePhase.FIRING_REPORT);
                display.setDoneEnabled(true);
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                board.source.refresh();
                return display;
            });
            try {
                GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                    start(live, status -> turn(status, false, Entity.NONE));
                    publish(live, status -> turn(status, false, Entity.NONE));
                    live.draw(10, .1f);
                    live.zoom(new Coords(12, 10), MOCK_HEX);
                    // 08: the Atlas's four shots at the King Crab played, paused at the fourth, a hit; 25 to come.
                    volley(live, GamePhase.FIRING_REPORT);
                    shot(live, "08", "08-weapon-playback.jpg", HEADER, MINIMAP, FORCES,
                          top("log-panel", 1520, 312, 380, 583), PLAYBACK_DOCK);
                    // The phase's other attacks at once (the dock's "Skip to results").
                    live.hud.state.history.skip();
                    live.draw(5, .1f);
                    // 10: the round's report.
                    onSwing(() -> {
                        board.game.setPhase(GamePhase.END_REPORT);
                        return null;
                    });
                    volley(live, GamePhase.END_REPORT);
                    shot(live, "10", "10-round-report.jpg", HEADER, MINIMAP, FORCES,
                          top("log-panel", 1520, 312, 380, 698), PLAYBACK_DOCK);
                });
            } finally {
                onSwing(() -> {
                    report.removeAllListeners();
                    return null;
                });
            }
        }
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * EDT: the firing fixture's game as in shot 05: the roster after its movement, the local player's firing turn
     * with the Atlas, its AC/20, SRM 6 and arm lasers on the Timber Wolf and its LRM 20 on the BattleMaster.
     */
    private static void declare(GpuFiringFixture firing) throws Exception {
        firingTurn(firing);
        int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
        for (int[] order : new int[][] { { cannon, TIMBER_WOLF },
              { GpuFiringFixture.eqNum(firing, "SRM 6", Mek.LOC_LEFT_TORSO), TIMBER_WOLF },
              { GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM), TIMBER_WOLF },
              { GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM), TIMBER_WOLF },
              { GpuFiringFixture.eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO), BATTLEMASTER } }) {
            GpuFiringFixture.command(firing, fire -> fire.assign(order[0], TargetKey.unit(order[1])));
        }
        GpuFiringFixture.command(firing, fire -> fire.selectWeapon(cannon));
        GpuFiringFixture.command(firing, fire -> fire.focusTarget(TargetKey.unit(TIMBER_WOLF)));
    }

    /** The firing fixture's game with the roster after its movement, on the local player's firing turn (Atlas). */
    static void firingTurn(GpuFiringFixture firing) throws Exception {
        GpuBoardFixture board = firing.board;
        onSwing(() -> {
            attach(board, firing.gui);
            GpuParityBattle.populate(board, firing.enemy);
            GpuParityBattle.afterMovement(board.game);
            beginTurn(firing.display, firing.client);
            board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
            board.source.refresh();
            return null;
        });
    }

    /** Plans the movement unit's route to {@code hex}, pinned as a waypoint or not; publishes it with its preview. */
    private static void plan(Live live, GpuBoardFixture board, Coords hex, boolean pin) throws Exception {
        board.source.moves().planTo(hex, 0, pin);
        GpuLiveBoardSpaceSmokeTest.settle();
        awaitPreview(live, board.source, Entity.NONE, UnaryOperator.identity());
    }

    /**
     * Publishes until the source's fire preview of {@code unit} (any unit for NONE) completed; with the HUD's
     * focus unit forwarded to the real source, that is the unit the HUD shows.
     */
    static void awaitPreview(Live live, GpuBoardSource source, int unit,
          UnaryOperator<GpuBattleStatus.Snapshot> status) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            publish(live, status);
            live.draw(1, .1f);
            GpuFirePreview.Snapshot preview = live.frame.get().panels().preview();
            if (preview.complete() && (unit == Entity.NONE || preview.unitId() == unit)) {
                live.draw(5, .1f);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The fire preview did not complete");
    }

    /**
     * 100 against 100 (shot 14): the published frame gains 95 more units of each side as copies of the roster's, in
     * lances of four on free hexes, the local side's in the south and Princess's in the north, and 200 turns.
     */
    private static void crowd(Live live) {
        GpuBoardSource.Frame base = live.frame.get();
        BoardScene scene = base.scene();
        GpuBattleStatus.Snapshot status = base.status();
        List<BoardScene.Unit> units = new ArrayList<>(scene.units());
        List<GpuBattleStatus.UnitStatus> listed = new ArrayList<>(status.units());
        List<Coords> taken = new ArrayList<>(scene.units().stream().map(unit -> unit.location().coords()).toList());
        for (int x = 0; x < 16; x++) {
            taken.add(new Coords(13, 10 + x % 6));
        }
        String[] lances = { "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo",
                            "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango",
                            "Uniform", "Victor", "Whiskey", "X-ray", "Yankee", "Zulu" };
        for (int index = 0; index < 190; index++) {
            boolean own = index % 2 == 0;
            int copy = index / 2;
            int template = own ? 1 + copy % 5 : 6 + copy % 5;
            Coords hex = free(scene, taken, own);
            taken.add(hex);
            int id = 100 + index;
            BoardScene.Unit unit = scene.units().stream().filter(each -> each.id() == template).findFirst()
                  .orElseThrow();
            units.add(new BoardScene.Unit(id, -1, unit.name(), new BoardScene.Waypoint(hex,
                  scene.tile(hex).elevation(), own ? 0 : 3), unit.image(), false, null, unit.height(), false,
                  unit.model(), unit.outlineRgb()));
            GpuBattleStatus.UnitStatus listing = GpuHudState.unit(status, template);
            String lance = own ? lances[Math.min(copy / 4, lances.length - 1)] + " lance"
                  : "Opposition lance " + (copy / 4 + 2);
            listed.add(GpuBoardOverlaySmokeTest.listing(listing, id, listing.side(), hex, lance));
        }
        List<GpuBattleStatus.Slot> turns = new ArrayList<>();
        for (int turn = 0; turn < 200; turn++) {
            turns.add(status.turns().get(turn % 2));
        }
        live.frame.set(Live.copy(base, scene.withUnits(units), new GpuBattleStatus.Snapshot(status.round(),
              status.phase(), status.myTurn(), status.localPlayerId(), status.actorId(), turns, 0, listed,
              status.initiative(), status.turnOrderHidden()), base.panels()));
        live.draw(10, .1f);
    }

    /** The next free hex of the side's half of the board, row by row from the side's edge. */
    private static Coords free(BoardScene scene, List<Coords> taken, boolean own) {
        for (int row = 0; row < scene.height(); row++) {
            int y = own ? scene.height() - 1 - row : row;
            for (int x = 0; x < scene.width(); x++) {
                Coords hex = new Coords(x, y);
                if (!taken.contains(hex)) {
                    return hex;
                }
            }
        }
        throw new AssertionError("No free hex left");
    }

    private static BoardScene.Unit unit(BoardScene scene, int id) {
        return scene.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    /**
     * The report phase's frame with the Atlas's four shots at the King Crab of shot 08 (AC/20 miss, LRM 20 hit for 12,
     * SRM 6 miss, medium laser hit for 5) and 25 attacks of both sides after them, every other one a hit: 29 attacks,
     * 15 hits and 14 misses as shots 08 and 10 count them. In the firing report the playback pauses as the fourth
     * shot lands: its card opens under the cursor and its pop-up holds. In the end report every attack has played and
     * a click on the LRM's card shows it again.
     */
    static void volley(Live live, GamePhase phase) throws Exception {
        publish(live, status -> turn(status, false, Entity.NONE));
        GpuBoardSource.Frame captured = live.frame.get();
        BoardScene scene = captured.scene();
        List<Attack> attacks = new ArrayList<>(List.of(new Attack(ATLAS, KING_CRAB, "AC/20", "RT", 0, 8, 4),
              new Attack(ATLAS, KING_CRAB, "LRM 20", "LT", 12, 4, 5),
              new Attack(ATLAS, KING_CRAB, "SRM 6", "LT", 0, 8, 7),
              new Attack(ATLAS, KING_CRAB, "Medium Laser", "LA", 5, 8, 8)));
        int atlasShots = attacks.size();
        int[][] pairs = { { WARHAMMER, TIMBER_WOLF }, { MARAUDER, KING_CRAB }, { PANTHER, BATTLEMASTER },
                          { LOCUST, ENEMY_LOCUST }, { TIMBER_WOLF, ATLAS }, { KING_CRAB, ATLAS },
                          { BATTLEMASTER, WARHAMMER }, { ARCHER, MARAUDER }, { ENEMY_LOCUST, LOCUST } };
        for (int i = 0; i < 25; i++) {
            attacks.add(new Attack(pairs[i % pairs.length][0], pairs[i % pairs.length][1], "Medium Laser", "RA",
                  i % 2 == 0 ? 5 : 0, 7, i % 2 == 0 ? 9 : 5));
        }
        Map<Integer, String> names = Map.of(ATLAS, "Atlas", WARHAMMER, "Warhammer", MARAUDER, "Marauder", PANTHER,
              "Panther", LOCUST, "Locust", TIMBER_WOLF, "Timber Wolf", KING_CRAB, "King Crab", BATTLEMASTER,
              "BattleMaster", ARCHER, "Archer", ENEMY_LOCUST, "Locust");
        int round = captured.status().round();
        List<BoardScene.Combat> shots = new ArrayList<>();
        List<GpuReportLog.CombatEvent> events = new ArrayList<>();
        List<GpuReportLog.Entry> entries = new ArrayList<>();
        for (Attack attack : attacks) {
            boolean hit = attack.damage() > 0;
            BoardScene.Combat shot = UnitPlaybackFixture.attack(unit(scene, attack.attacker()),
                  unit(scene, attack.target()), ResolvedAttack.Kind.SHOT, hit);
            UUID id = shot.result().id();
            shots.add(shot);
            events.add(new GpuReportLog.CombatEvent(id, round, GamePhase.FIRING, ResolvedAttack.Kind.SHOT,
                  attack.attacker(), attack.target(), attack.weapon(), attack.location(), hit, attack.damage(),
                  hit ? List.of(new ResolvedAttack.Impact("CT", false, attack.damage())) : List.of(), null, 0));
            entries.add(new GpuReportLog.Entry(round, "Weapon Attack Phase", GamePhase.FIRING, "", attack.weapon(),
                  List.of(new GpuReportLog.Unit(attack.attacker(), names.get(attack.attacker())),
                        new GpuReportLog.Unit(attack.target(), names.get(attack.target()))), "", List.of(),
                  GpuReportLog.Kind.WEAPON, attack.attacker(), attack.target(), id, List.of(), false, false,
                  attack.needed(), attack.rolled(), null, null, null, null, null, false, false));
        }
        GpuReportLog.Snapshot log = new GpuReportLog.Snapshot(round, phase, entries, Map.of(), events,
              List.of(), List.of(), List.of());
        GpuBattleStatus.Snapshot s = captured.status();
        GpuBattleStatus.Snapshot status = new GpuBattleStatus.Snapshot(round, phase, false, s.localPlayerId(),
              Entity.NONE, s.turns(), s.turnIndex(), s.units(), s.initiative(), s.turnOrderHidden());
        GpuHudData p = captured.panels();
        GpuHudData panels = new GpuHudData(p.phase(), GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY,
              GpuPhysicalOptions.Snapshot.EMPTY, p.record(), GpuFirePreview.Snapshot.NONE, p.chat(), p.toasts(),
              p.los(), p.players());
        GpuBoardSource.Frame frame = new GpuBoardSource.Frame(scene, List.of(), captured.context(),
              captured.globalCommands(), captured.tooltip(), captured.centerRequest(), captured.boardGeneration(),
              captured.actorName(), captured.scenarioAtmosphere(), log, status, panels);
        GpuPlaybackHistory history = live.hud.state.history;
        if (phase == GamePhase.FIRING_REPORT) {
            live.frame.set(frame.withTimeline(List.copyOf(shots)));
            live.draw(1, 1 / 30f);
            live.frame.set(frame);
            for (int step = 0; step < 900 && history.played().size() < atlasShots; step++) {
                live.draw(1, 1 / 30f);
            }
            assertEquals(atlasShots, history.played().size(), "The Atlas's shots played");
            // The dock's pause as the fourth lands.
            history.togglePaused();
            live.draw(40, 1 / 30f);
        } else {
            // The LRM's card, as a click on it (shot 10): its shot lands again and its pop-up goes.
            live.frame.set(frame);
            live.draw(5, .1f);
            history.reviewTo(history.played().get(1));
            live.draw(150, 1 / 30f);
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * The window's start: daylight at 13:00 for every shot, and the HUD's card and focus units forwarded to the real
     * source, as GpuHud publishes them to the source in the battle window (the live harness's source is a recording
     * one), so the card shows its unit's record and the fire preview follows the focus.
     */
    static void start(Live live, UnaryOperator<GpuBattleStatus.Snapshot> status) throws Exception {
        live.source.uiPreferences = GpuHudFixtures.with(live.source.uiPreferences,
              Map.of("binds", live.real.uiPreferences.binds()));
        doAnswer(invocation -> {
            live.real.setCardUnit(invocation.getArgument(0));
            return null;
        }).when(live.source).setCardUnit(anyInt());
        doAnswer(invocation -> {
            live.real.setFocusUnit(invocation.getArgument(0));
            return null;
        }).when(live.source).setFocusUnit(anyInt());
        Slider time = GpuBoardTestUi.tuning(live.view, "Time of day");
        time.setValue(13);
        // The mock's camera looks north, tilted about 45 degrees from straight down.
        live.camera().orbit(-live.camera().azimuth(), MOCK_TILT - live.camera().tilt());
        // The HUD publishes its card and focus units in its first frames; the capture after them has their data.
        publish(live, status);
        live.draw(10, .1f);
    }

    /**
     * EDT: the phase display's own start of the local turn, as its turn change event runs it (Next, Done and the
     * skip enabled), with the Atlas as the client's first unit. The fixtures select their unit directly instead.
     */
    static void beginTurn(Object display, Client client) throws ReflectiveOperationException {
        when(client.getFirstEntityNum()).thenReturn(ATLAS);
        Method begin = display.getClass().getDeclaredMethod("beginMyTurn");
        begin.setAccessible(true);
        begin.invoke(display);
    }

    /**
     * EDT: gives the fixture's board state the fixture's client, as the battle window's board state has it. The
     * fixtures build the board state before their client; without one the source knows no turn of the local player
     * (the fire preview would not follow the plan, the physical options stay off).
     * The client shows that board, so the source's captured commands run (a command
     * of a board the client does not show is stale).
     */
    static void attach(GpuBoardFixture board, ClientGUI gui) throws ReflectiveOperationException {
        Field client = BoardClientState.class.getDeclaredField("clientgui");
        client.setAccessible(true);
        client.set(board.view, gui);
        when(gui.getCurrentBoardState()).thenReturn(Optional.of(board.view));
    }

    /**
     * Publishes the real source's next capture with the status {@code change} makes of it. The fixtures' client has
     * no menu bar, so the capture has no global commands; the frame gets the View menu's minimap item, which the
     * battle window's menu bar has and the Map utility switches.
     */
    static void publish(Live live, UnaryOperator<GpuBattleStatus.Snapshot> change) throws Exception {
        live.publish();
        GpuBoardSource.Frame f = live.frame.get();
        List<BoardScene.Command> global = new ArrayList<>(f.globalCommands());
        global.add(new BoardScene.Command("View/" + ClientGUI.VIEW_MINI_MAP + ":Minimap", "Minimap", "", true, false,
              List.of(), () -> { }));
        live.frame.set(new GpuBoardSource.Frame(f.scene(), f.timeline(), f.context(), global, f.tooltip(),
              f.centerRequest(), f.boardGeneration(), f.actorName(), f.scenarioAtmosphere(), f.reports(),
              change.apply(f.status()), f.panels()));
    }

    /** The status with the local player's turn or not, and the acting unit. */
    static GpuBattleStatus.Snapshot turn(GpuBattleStatus.Snapshot s, boolean mine, int actor) {
        return new GpuBattleStatus.Snapshot(s.round(), s.phase(), mine, s.localPlayerId(), actor, s.turns(),
              s.turnIndex(), s.units(), s.initiative(), s.turnOrderHidden());
    }

    private static void click(Live live, String name) {
        Actor actor = live.hud.stage.getRoot().findActor(name);
        assertTrue(actor != null, "No actor " + name);
        click(live, actor);
    }

    /** A left click at the middle of {@code actor}, then a few frames. */
    static void click(Live live, Actor actor) {
        Vector2 point = live.hud.stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        live.hud.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        live.hud.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        live.draw(3, .1f);
    }

    /**
     * The shot: draws a few frames, writes parity-{id}.png (opaque, as the window shows it), parity-{id}-sbs.png beside
     * the mock and parity-{id}.txt, then checks that no label shows a missing key and that every region is shown at
     * the mock's width.
     */
    private static void shot(Live live, String id, String mock, Region... regions) throws IOException {
        live.draw(5, .1f);
        UiTestStage.assertTexts(live.hud.stage.getRoot());
        Pixmap window = live.window();
        try {
            ByteBuffer pixels = window.getPixels();
            for (int alpha = 3; alpha < pixels.limit(); alpha += 4) {
                pixels.put(alpha, (byte) 255);
            }
            File output = UiTestStage.OUTPUT;
            assertTrue(output.isDirectory() || output.mkdirs());
            PixmapIO.writePNG(new FileHandle(new File(output, "parity-" + id + ".png")), window, -1, true);
            sideBySide(window, new File(output, "parity-" + id + "-sbs.png"), mock);
        } finally {
            window.dispose();
        }
        float height = live.hud.stage.getHeight();
        StringBuilder report = new StringBuilder("Shot " + id + " (" + mock + "), stage " + live.hud.stage.getWidth()
              + " x " + height + "; x, y down, width, height\n");
        List<String> failures = new ArrayList<>();
        for (Region region : regions) {
            Actor actor = live.hud.stage.getRoot().findActor(region.actor());
            if (actor == null || !GpuBoardTestUi.shown(actor)) {
                report.append(String.format(Locale.ROOT, "%-24s mock %4d %4d %4d %4d | not shown%n", region.actor(),
                      region.x(), region.y(), region.width(), region.height()));
                failures.add(region.actor() + " is not shown");
                continue;
            }
            com.badlogic.gdx.math.Rectangle bounds = UiTestStage.bounds(actor);
            float y = height - bounds.y - bounds.height;
            report.append(String.format(Locale.ROOT,
                  "%-24s mock %4d %4d %4d %4d | ours %6.1f %6.1f %6.1f %6.1f | delta %+6.1f %+6.1f %+6.1f %+6.1f%n",
                  region.actor(), region.x(), region.y(), region.width(), region.height(), bounds.x, y, bounds.width,
                  bounds.height, bounds.x - region.x(), y - region.y(), bounds.width - region.width(),
                  bounds.height - region.height()));
            report.append("    ").append(GpuBoardTestUi.texts(actor)).append('\n');
            if (region.edge() == Edge.FREE) {
                continue;
            }
            check(failures, region, "width", bounds.width, region.width());
        }
        for (String name : List.of("utility-bar", "tuning-button", "chat-button", "hint-line", "tactical-chip",
              "target-cards", "toast-stack")) {
            Actor actor = live.hud.stage.getRoot().findActor(name);
            if (actor != null && GpuBoardTestUi.shown(actor)) {
                com.badlogic.gdx.math.Rectangle bounds = UiTestStage.bounds(actor);
                report.append(String.format(Locale.ROOT, "%-24s ours %6.1f %6.1f %6.1f %6.1f%n    %s%n", name,
                      bounds.x, height - bounds.y - bounds.height, bounds.width, bounds.height,
                      GpuBoardTestUi.texts(actor)));
            }
        }
        Files.writeString(new File(UiTestStage.OUTPUT, "parity-" + id + ".txt").toPath(), report.toString(),
              StandardCharsets.UTF_8);
        System.out.print(report);
        assertTrue(failures.isEmpty(), "Shot " + id + ": " + failures);
    }

    private static void check(List<String> failures, Region region, String what, float ours, float mock) {
        if (Math.abs(ours - mock) > TOLERANCE) {
            failures.add(String.format(Locale.ROOT, "%s %s %.1f, mock %.1f", region.actor(), what, ours, mock));
        }
    }

    /** Writes {@code file}: the window (rows bottom up) beside the mock's shot, when the mock is at hand. */
    private static void sideBySide(Pixmap window, File file, String mock) {
        File shot = new File(UiTestStage.MOCK, mock);
        if (!shot.isFile()) {
            System.out.println("No mock screenshot at " + shot.getAbsolutePath() + "; " + file.getName() + " skipped");
            return;
        }
        Pixmap reference = new Pixmap(new FileHandle(shot));
        int width = window.getWidth();
        int height = window.getHeight();
        Pixmap result = new Pixmap(width + reference.getWidth(), Math.max(height, reference.getHeight()),
              Pixmap.Format.RGBA8888);
        try {
            result.setBlending(Pixmap.Blending.None);
            result.setColor(0, 0, 0, 1);
            result.fill();
            for (int row = 0; row < height; row++) {
                result.drawPixmap(window, 0, height - 1 - row, width, 1, 0, row, width, 1);
            }
            result.drawPixmap(reference, width, 0);
            PixmapIO.writePNG(new FileHandle(file), result);
        } finally {
            result.dispose();
            reference.dispose();
        }
    }
}
