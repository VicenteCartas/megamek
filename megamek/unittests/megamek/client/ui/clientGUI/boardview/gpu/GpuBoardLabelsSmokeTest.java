/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.atlasMove;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.fire;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.frame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.ground;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.move;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.pantherMove;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.preferences;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.step;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.unit;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuContactsPanelSmokeTest.shot02;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuContactsPanelSmokeTest.shot03;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuContactsPanelSmokeTest.shot04;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.CONTACT;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuNameplatesSmokeTest.heads;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuNameplatesSmokeTest.opaque;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Contact;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Side;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * G8b: the board labels over the board-space harness in 3D and in the Tactical View, in the states of the hud-v3 shots
 * 02-09 and 13, each beside its crop of the shot, with the G7 overlay, the G8a nameplates and the G5 panel drawn as
 * GpuHud stacks them; and their behaviour: the destination tip's flip and window clamp, the waypoint numbers after an
 * undo, the paired and flowing guides with their toggles, traces, leaders and TN badges, and the playback pop-ups.
 * The snapshots are display values read off the mock's pictures, as the EDT services would publish them; the target
 * cards' rectangles stand in for G9's placement.
 */
@Tag("on-demand")
class GpuBoardLabelsSmokeTest {
    private static final int WARHAMMER = 2;
    private static final int PANTHER = 4;
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    private static final int ENEMY_LOCUST = 10;
    private static final String DOT = String.valueOf((char) 0xB7);
    private static final String OUT_OF_ARC = Messages.getString("WeaponAttackAction.OutOfArc");
    /** The right column at 1920 x 1080, where the contacts panel stands (shot 02: below the minimap, 70 up). */
    private static final int COLUMN_LEFT = 1590;
    private static final int COLUMN_TOP = 312;
    private static final int COLUMN_BOTTOM = 1080 - 70;
    private static final int COLUMN_WIDTH = 310;

    /**
     * The labels under test, stacked as GpuHud's labels layer stacks them (the #fx strokes, the G8a nameplates, then
     * the labels), with the HUD state they share and the contacts panel (G5) whose guide toggles and open row they
     * read; the panel stands in the right column while it shows.
     */
    private static final class Labels {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuContactsPanel contacts;
        final GpuNameplates plates;
        final GpuBoardLabels labels;
        final Table root;
        final Table panel;

        Labels(GpuHudTestStage hud, BoardCamera camera) {
            GpuBoardSource source = mock(GpuBoardSource.class);
            // The HUD's selection rule inspects an enemy (GpuHud.select): a row's click hands it the row's unit.
            contacts = new GpuContactsPanel(hud.kit, source, state, mock(GpuContextMenu.class),
                  id -> state.inspected = id);
            plates = new GpuNameplates(hud.kit, source, state);
            labels = new GpuBoardLabels(hud.kit, source, state, camera, contacts, plates);
            root = (Table) labels.actor();
            panel = (Table) contacts.actor();
            for (Actor layer : List.of(labels.fx(), plates.actor(), root)) {
                hud.window.addActor(layer);
                layer.setBounds(0, 0, hud.width(), hud.height());
            }
            hud.window.addActor(panel);
            panel.setVisible(false);
        }

        /**
         * One HUD frame: the state's focus rule, then the panel (shown with a preview), the plates and the labels,
         * which keep clear of the shown panel and of {@code more}.
         */
        void update(GpuHudTestStage hud, GpuBoardSource.Frame frame, GpuHud.HudView view, Rectangle... more) {
            state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
            frame.status().units().stream().map(UnitStatus::icon).forEach(hud.sprites::add);
            List<Rectangle> bounds = new ArrayList<>(List.of(more));
            boolean preview = frame.panels().preview().active();
            panel.setVisible(preview);
            GpuHud.Inputs inputs = new GpuHud.Inputs(frame, view, null, GpuHudFixtures.preferences(),
                  GpuHud.Metrics.of(hud.width(), hud.height()), bounds);
            contacts.update(inputs);
            if (preview) {
                // The right column's slot: its width, the content's height up to 70 above the bottom.
                for (int pass = 0; pass < 2; pass++) {
                    panel.setWidth(COLUMN_WIDTH);
                    panel.validate();
                    float height = Math.min(panel.getPrefHeight(), COLUMN_BOTTOM - COLUMN_TOP);
                    panel.setBounds(COLUMN_LEFT, hud.height() - COLUMN_TOP - height, COLUMN_WIDTH, height);
                    panel.validate();
                }
                bounds.add(GpuHudTestStage.bounds(panel));
                inputs = new GpuHud.Inputs(frame, view, null, inputs.preferences(), inputs.metrics(), bounds);
            }
            plates.update(inputs);
            labels.update(inputs);
        }

        Table tip() {
            return root.findActor("route-tip");
        }

        Actor pin(int number) {
            return root.findActor("pin-" + number);
        }

        Label flatPin(int number) {
            return root.findActor("pin-flat-" + number);
        }

        /** The shown TN badges' texts by the unit under each, found 4 units over its nameplate. */
        Map<Integer, List<String>> badges(GpuHud.HudView view) {
            Map<Integer, List<String>> badges = new HashMap<>();
            for (Actor actor : all(root, "tn-badge")) {
                Rectangle bounds = GpuHudTestStage.bounds(actor);
                view.unitHeads().forEach((id, head) -> {
                    if (Math.abs(bounds.x + bounds.width / 2 - head.x) <= 1
                          && Math.abs(bounds.y - 4 - plates.top(id, head.y)) <= 1) {
                        badges.put(id, texts(actor));
                    }
                });
            }
            return badges;
        }

        List<Actor> pops() {
            return all(root, "pop-up");
        }

        /** Presses and releases at the named actor of the panel, as the board view hands a click to the HUD. */
        void click(GpuHudTestStage hud, String name) {
            Actor actor = panel.findActor(name);
            assertNotNull(actor, name);
            Vector2 point = hud.stage.stageToScreenCoordinates(
                  actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
            hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        }

        void dispose() {
            labels.fx().remove();
            plates.actor().remove();
            root.remove();
            panel.remove();
            labels.dispose();
        }
    }

    @Test
    void labelsOverTheBoardBesideTheMock() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                Coords atlasHex = unit(scene, ATLAS).location().coords();

                // Shot 02: the Atlas walks two hexes north; the preview from there with the Timber Wolf's row open.
                Labels labels = new Labels(hud, board.camera);
                GpuMovePlan.Snapshot walk = facts(atlasMove(scene), "walk", 2, 3, 1, 0, List.of());
                GpuBoardSource.Frame frame = frameOf(scene, moving, panels(walk, GpuFireOrders.Snapshot.EMPTY,
                      shot02()), GpuReportLog.Snapshot.EMPTY);
                frame(board, false, atlasHex, 118, 960, 675);
                GpuHud.HudView view = draw(hud, board, overlay, labels, frame);
                labels.click(hud, "contacts-preview-" + TIMBER_WOLF);
                view = draw(hud, board, overlay, labels, frame);
                assertEquals(TIMBER_WOLF, labels.state.inspected, "The open row is the inspected enemy");
                assertEquals(List.of("WALK " + DOT + " 2 / 3 MP " + DOT + " AUTO", "Heat +1 " + DOT + " TMM +0",
                      "Facing N " + DOT + " 0 pinned waypoints"), texts(labels.tip()));
                Vector2 destination = board.screen(ground(scene, walk.destination()));
                assertEquals(Math.round(destination.x + 56), GpuHudTestStage.bounds(labels.tip()).x, 1,
                      "The tip starts 56 right of the destination");
                Map<Integer, List<String>> preview = new HashMap<>();
                preview.put(TIMBER_WOLF, List.of("4+", "92%"));
                preview.put(KING_CRAB, List.of("6+", "72%"));
                preview.put(BATTLEMASTER, List.of("6+", "72%"));
                preview.keySet().retainAll(view.unitHeads().keySet());
                assertEquals(preview, labels.badges(view), "The preview's targets with a shot get a TN badge");
                capture(hud, "labels-02", "02-movement-fire-preview.jpg", new Crop("tip", labels.tip(), 993, 444),
                      new Crop("area", area(hud, 540, 250, 860, 640), 540, 250)).dispose();
                labels.dispose();

                // Shot 03: the Panther runs on past a waypoint; near the right column the tip flips to the left.
                labels = new Labels(hud, board.camera);
                GpuMovePlan.Snapshot run = pantherMove(scene);
                run = facts(run, "run", 5, 6, 2, 1, run.pins());
                GpuBattleStatus.Snapshot pantherTurn = turn(GamePhase.MOVEMENT, true, PANTHER, moving.units());
                frame = frameOf(scene, pantherTurn, panels(run, GpuFireOrders.Snapshot.EMPTY,
                      from(shot03(), run.destination())), GpuReportLog.Snapshot.EMPTY);
                // The player selected the Panther during the turn, so it is the focus (its plate reads "moving").
                labels.state.update(moving, GpuUnitRecord.Snapshot.EMPTY, false);
                frame(board, false, unit(scene, PANTHER).location().coords(), 118, 1147, 660);
                draw(hud, board, overlay, labels, frame);
                assertEquals(List.of("RUN " + DOT + " 5 / 6 MP " + DOT + " AUTO", "Heat +2 " + DOT + " TMM +1",
                      "Facing NE " + DOT + " 1 pinned waypoint"), texts(labels.tip()));
                destination = board.screen(ground(scene, run.destination()));
                Rectangle tip = GpuHudTestStage.bounds(labels.tip());
                assertEquals(Math.round(destination.x - 56), tip.x + tip.width, 1, "Flipped left of the destination");
                assertFalse(tip.overlaps(GpuHudTestStage.bounds(labels.panel)), "The flipped tip leaves the column");
                Vector2 pinHex = board.screen(ground(scene, run.pins().getFirst().coords()));
                assertCentred(labels.pin(1), pinHex.x, pinHex.y + 22, "Waypoint 1 sits 22 above its hex");
                assertNull(labels.pin(2));
                capture(hud, "labels-03", "03-waypoint-route.jpg", new Crop("tip", labels.tip(), 1140, 460),
                      new Crop("pin", labels.pin(1), 1217, 595), new Crop("area", area(hud, 860, 380, 660, 520), 860,
                            380)).dispose();
                labels.dispose();

                // Shot 04: the opponent moves; the Warhammer, up next, is previewed where it stands.
                labels = new Labels(hud, board.camera);
                List<UnitStatus> moved = new ArrayList<>(moving.units());
                moved.set(0, GpuNameplatesSmokeTest.unit(moved.getFirst(), false, false, true, false, List.of()));
                frame = frameOf(scene, turn(GamePhase.MOVEMENT, false, TIMBER_WOLF, moved), panels(
                      GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, sensorArcher(shot04())),
                      GpuReportLog.Snapshot.EMPTY);
                frame(board, false, atlasHex, 118, 960, 675);
                view = draw(hud, board, overlay, labels, frame);
                assertFalse(labels.tip().isVisible(), "No tip outside the local movement turn");
                preview = new HashMap<>(Map.of(TIMBER_WOLF, List.of("6+", "72%"), KING_CRAB, List.of("6+", "72%")));
                preview.keySet().retainAll(view.unitHeads().keySet());
                assertEquals(preview, labels.badges(view));
                capture(hud, "labels-04", "04-opponent-turn.jpg", new Crop("area", area(hud, 1000, 0, 560, 700), 1000,
                      0)).dispose();
                labels.dispose();

                // Shots 05 and 13 (the same board under G12's menu): the Atlas's orders on the Timber Wolf (A) and
                // the BattleMaster (B), TN badges for the selected weapon on the other enemies, traces and leaders.
                GpuBattleStatus.Snapshot declaring = turn(GamePhase.FIRING, true, ATLAS, moving.units());
                GpuFireOrders.Snapshot orders = orders(fire(null), fire(null).targets(), List.of(
                      new GpuFireOrders.Badge(KING_CRAB, 9, 27.78, ""),
                      new GpuFireOrders.Badge(ENEMY_LOCUST, TargetRoll.IMPOSSIBLE, 0, OUT_OF_ARC),
                      new GpuFireOrders.Badge(BATTLEMASTER, 8, 41.67, "")));
                frame = frameOf(scene, declaring, panels(GpuMovePlan.Snapshot.EMPTY, orders,
                      GpuFirePreview.Snapshot.NONE), GpuReportLog.Snapshot.EMPTY);
                for (boolean tactical : new boolean[] { false, true }) {
                    labels = new Labels(hud, board.camera);
                    frame(board, tactical, atlasHex, tactical ? 60.8f : 118, tactical ? 868 : 925,
                          tactical ? 672 : 790);
                    view = draw(hud, board, overlay, labels, frame);
                    labels.labels.cards(cards(hud, view, Map.of(TIMBER_WOLF, 'A', BATTLEMASTER, 'B')));
                    view = draw(hud, board, overlay, labels, frame);
                    Map<Integer, List<String>> badges = new HashMap<>(Map.of(KING_CRAB, List.of("9+", "28%"),
                          ENEMY_LOCUST, List.of(OUT_OF_ARC)));
                    badges.keySet().retainAll(view.unitHeads().keySet());
                    assertEquals(badges, labels.badges(view), "No badge on a unit that has a target card");
                    if (tactical) {
                        capture(hud, "labels-07", "07-tactical-view.jpg",
                              new Crop("badge", badge(labels, view, KING_CRAB), 703, 226),
                              new Crop("off", badge(labels, view, ENEMY_LOCUST), 1249, 651),
                              new Crop("area", area(hud, 380, 120, 1000, 800), 380, 120)).dispose();
                    } else {
                        Pixmap image = capture(hud, "labels-05", "05-weapon-declaration.jpg",
                              new Crop("badge", badge(labels, view, KING_CRAB), 562, 66),
                              new Crop("area", area(hud, 330, 100, 1260, 800), 330, 100));
                        hud.compare("labels-13-area", image, area(hud, 330, 100, 1260, 800), "13-context-menu.jpg",
                              330, 100);
                        image.dispose();
                    }
                    labels.dispose();
                }

                // Shot 06: a third target (C, the King Crab) and three traces; the cards of B and C are collapsed.
                labels = new Labels(hud, board.camera);
                List<GpuFireOrders.Target> three = new ArrayList<>(fire(null).targets());
                three.add(new GpuFireOrders.Target(TargetKey.unit(KING_CRAB), 'C', "King Crab", false, 1, true,
                      null));
                frame = frameOf(scene, declaring, panels(GpuMovePlan.Snapshot.EMPTY,
                      orders(fire(null), three, List.of()), GpuFirePreview.Snapshot.NONE), GpuReportLog.Snapshot.EMPTY);
                frame(board, false, atlasHex, 118, 980, 850);
                view = draw(hud, board, overlay, labels, frame);
                labels.labels.cards(cards(hud, view, Map.of(TIMBER_WOLF, 'A', BATTLEMASTER, 'B', KING_CRAB, 'C')));
                draw(hud, board, overlay, labels, frame);
                capture(hud, "labels-06", "06-three-targets.jpg", new Crop("area", area(hud, 500, 60, 800, 800), 500,
                      60)).dispose();
                labels.dispose();

                // Shot 08: the weapon playback; a hit on the King Crab's centre torso pops up over it.
                labels = new Labels(hud, board.camera);
                frame = frameOf(scene, turn(GamePhase.FIRING_REPORT, false, Entity.NONE, moving.units()), panels(
                      GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE),
                      GpuReportLog.Snapshot.EMPTY);
                frame(board, false, unit(scene, KING_CRAB).location().coords(), 118, 870, 200);
                draw(hud, board, overlay, labels, frame);
                labels.labels.show(hit(KING_CRAB, "Medium Laser", 5, new ResolvedAttack.Impact("CT", false, 5)));
                draw(hud, board, overlay, labels, frame);
                Actor pop = labels.pops().getFirst();
                assertEquals(List.of("\u22125 CT", "MEDIUM LASER"), texts(pop));
                capture(hud, "labels-08", "08-weapon-playback.jpg", new Crop("pop", pop, 897, 140),
                      new Crop("area", area(hud, 650, 40, 600, 400), 650, 40)).dispose();
                labels.dispose();

                // Shot 09: the physical phase has no board label; the labels layer draws nothing over the board.
                labels = new Labels(hud, board.camera);
                frame = frameOf(scene, turn(GamePhase.PHYSICAL, true, ATLAS, moving.units()), panels(
                      GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE),
                      GpuReportLog.Snapshot.EMPTY);
                frame(board, false, atlasHex, 118, 960, 505);
                draw(hud, board, overlay, labels, frame);
                opaque();
                Pixmap with = hud.capture("labels-09");
                labels.labels.fx().setVisible(false);
                labels.root.setVisible(false);
                draw(hud, board, overlay, labels, frame);
                opaque();
                Pixmap without = hud.capture("labels-09-without");
                try {
                    assertTrue(same(with, without), "No board label in the physical phase");
                    hud.compare("labels-09-area", with, area(hud, 700, 280, 520, 420), "09-physical-attacks.jpg",
                          700, 280);
                } finally {
                    with.dispose();
                    without.dispose();
                }
                labels.dispose();
            } finally {
                overlay.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void theTipFlipsBesideTheHudAndStaysInTheWindow() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Labels labels = new Labels(hud, board.camera);
            try {
                // An interior destination lets the camera reach each window edge without hitting its pan limit.
                GpuMovePlan.Snapshot walk = facts(route(scene, List.of(new Coords(14, 9), new Coords(14, 8)),
                      List.of()), "walk", 2, 3, 1, 0, List.of());
                Coords destination = walk.destination();
                assertFalse(scene.tile(destination).liquid().present(), "A dry destination: its surface is its level");
                GpuBoardSource.Frame frame = frameOf(scene, GpuHudFixtures.status(), panels(walk,
                      GpuFireOrders.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE), GpuReportLog.Snapshot.EMPTY);
                // The Atlas stands 100 units tall on the screen, so the tip's anchor is 90 above the destination.
                GpuHud.HudView view = new GpuHud.HudView(false, false, Map.of(ATLAS, new Rectangle(0, 0, 60, 100)),
                      Map.of(), Map.of(), null, Entity.NONE, 118);

                // Mid-window: right of the anchor and above it, as .tip3d's (+56, -34).
                Rectangle free = tip(hud, board, labels, frame, view, destination, 960, 540);
                assertEquals(960 + 56, free.x, 1);
                assertEquals(540 + 90 + 34, free.y + free.height, 1);
                // A panel that the tip would cover, with the HUD rectangles' 6-unit margin: it flips to the left.
                float right = free.x + free.width;
                Rectangle flipped = tip(hud, board, labels, frame, view, destination, 960, 540,
                      new Rectangle(right + 4, 200, 300, 600));
                assertEquals(960 - 56, flipped.x + flipped.width, 1, "Flipped within the margin");
                assertEquals(free.y, flipped.y, "A flip keeps the height");
                assertEquals(free, tip(hud, board, labels, frame, view, destination, 960, 540,
                      new Rectangle(right + 8, 200, 300, 600)), "A panel beyond the margin keeps it on the right");
                assertEquals(free, tip(hud, board, labels, frame, view, destination, 960, 540,
                      new Rectangle(free.x, free.y + free.height + 8, 300, 100)), "Nor does a panel above it");
                // At the window's edges it stays 8 inside: flipped at the left edge, unflipped at the right, and
                // below the top.
                Rectangle left = tip(hud, board, labels, frame, view, destination, 60, 540,
                      new Rectangle(100, 0, 600, 1080));
                assertEquals(8, left.x, 1, "The flipped tip at the left edge");
                Rectangle edge = tip(hud, board, labels, frame, view, destination, 1890, 540);
                assertEquals(1920 - 8, edge.x + edge.width, 1, "The tip at the right edge");
                Rectangle top = tip(hud, board, labels, frame, view, destination, 960, 20);
                assertEquals(1080 - 8, top.y + top.height, 1, "The tip under the top edge");
                assertEquals(960 + 56, top.x, 1);
            } finally {
                labels.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void waypointNumbersFollowThePinsAfterAnUndo() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Labels labels = new Labels(hud, board.camera);
            try {
                board.units = false;
                Coords atlas = unit(scene, ATLAS).location().coords();
                Coords first = atlas.translated(0);
                Coords second = first.translated(0);
                Coords third = second.translated(1);
                Coords elsewhere = second.translated(5);
                GpuHud.HudView view = new GpuHud.HudView(false, false, Map.of(ATLAS, new Rectangle(0, 0, 60, 100)),
                      Map.of(), Map.of(), null, Entity.NONE, 118);
                frame(board, false, atlas, 118, 960, 675);

                // Two pins: numbered in the plan's order, each 22 above its hex.
                show(labels, hud, board, view, route(scene, List.of(first, second, third), List.of(first, second)));
                assertPin(labels, board, scene, 1, first);
                assertPin(labels, board, scene, 2, second);
                assertEquals("Facing NE " + DOT + " 2 pinned waypoints", texts(labels.tip()).get(2));
                // Undo takes the last pin back: its number goes with it, the first keeps its own.
                show(labels, hud, board, view, route(scene, List.of(first, second, third), List.of(first)));
                assertPin(labels, board, scene, 1, first);
                assertFalse(labels.pin(2).isVisible(), "The undone pin's number is gone");
                assertEquals("Facing NE " + DOT + " 1 pinned waypoint", texts(labels.tip()).get(2));
                // A new pin elsewhere takes the free number at its own hex.
                show(labels, hud, board, view, route(scene, List.of(first, second, elsewhere), List.of(first,
                      elsewhere)));
                assertPin(labels, board, scene, 1, first);
                assertPin(labels, board, scene, 2, elsewhere);
                assertNull(labels.pin(3));
                opaque();
                hud.capture("labels-pins-3d").dispose();
                // A cleared route has no numbers, nor has a hover route or the opponent's turn.
                show(labels, hud, board, view, route(scene, List.of(), List.of()));
                assertFalse(labels.pin(1).isVisible());
                GpuMovePlan.Snapshot pinned = route(scene, List.of(first, second, elsewhere), List.of(first));
                show(labels, hud, board, view, GpuMovePlan.Snapshot.EMPTY);
                assertFalse(labels.pin(1).isVisible(), "No plan outside the local movement turn");
                show(labels, hud, board, view, move(ATLAS, List.of(), pinned.route(), pinned.pins(), Map.of(), true));
                assertFalse(labels.pin(1).isVisible(), "No numbers on a hover route");

                // Two pins in the same hex on different floors must have distinct projected anchors.
                float floor = scene.tile(atlas).elevation();
                List<GpuMovePlan.Step> floors = List.of(
                      new GpuMovePlan.Step(atlas, scene.boardId(), floor + 1, 0, GpuMovePlan.Band.WALK, false),
                      new GpuMovePlan.Step(atlas, scene.boardId(), floor + 2, 0, GpuMovePlan.Band.WALK, false));
                show(labels, hud, board, view, move(ATLAS, floors, List.of(), floors, Map.of(), true));
                for (int number : new int[] { 1, 2 }) {
                    Vector2 centre = board.screen(BoardGeometry.center(atlas, floor + number));
                    assertCentred(labels.pin(number), centre.x, centre.y + 22,
                          "Waypoint " + number + " follows its floor");
                }

                // The Tactical View: the badge above the hex, and the number on the hex's own disc as well.
                frame(board, true, atlas, 60.8f, 960, 540);
                GpuHud.HudView flat = new GpuHud.HudView(true, false, Map.of(ATLAS, new Rectangle(0, 0, 44, 44)),
                      Map.of(), Map.of(), null, Entity.NONE, 60.8f);
                show(labels, hud, board, flat, route(scene, List.of(first, second, elsewhere),
                      List.of(first, elsewhere)));
                for (int number : new int[] { 1, 2 }) {
                    Coords hex = number == 1 ? first : elsewhere;
                    Vector2 centre = board.screen(ground(scene, hex));
                    assertPin(labels, board, scene, number, hex);
                    Label onDisc = labels.flatPin(number);
                    assertTrue(onDisc.isVisible(), "The number on disc " + number);
                    assertEquals(String.valueOf(number), onDisc.getText().toString());
                    assertCentred(onDisc, centre.x, centre.y, "Number " + number + " on its disc");
                }
                opaque();
                hud.capture("labels-pins-tactical").dispose();
                frame(board, false, atlas, 118, 960, 675);
                show(labels, hud, board, view, route(scene, List.of(first, second, elsewhere),
                      List.of(first, elsewhere)));
                assertFalse(labels.flatPin(1).isVisible(), "No number on the disc in 3D");
            } finally {
                labels.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void guidesPairUpFlowFromTheShooterAndFollowTheToggles() {
        GpuHudTestStage.run(hud -> {
            Labels labels = new Labels(hud, null);
            try {
                // The Warhammer, where it stands, against the Timber Wolf straight above it, which fires back: the
                // guides connect their centres, stopping outside both rectangles; the incoming one runs back down.
                GpuHud.HudView view = view(false, Map.of(WARHAMMER, new Rectangle(400, 200, 40, 100), TIMBER_WOLF,
                      new Rectangle(400, 700, 40, 100)));
                GpuBoardSource.Frame frame = frameOf(null, turn(GamePhase.MOVEMENT, false, TIMBER_WOLF,
                      GpuHudFixtures.status().units()), panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, inPlace(new Contact(TIMBER_WOLF, false, 9, side(2, 6, 72.22),
                            side(4, 5, 83.33)))), GpuReportLog.Snapshot.EMPTY);
                labels.update(hud, frame, view);
                Pixmap still = draw(hud, "labels-guides");
                // Side by side, 3 apart: outgoing right of the line from the shooter to the target, incoming left
                // (probed above the Warhammer's own nameplate, which covers them at its head, 300).
                assertEquals(.5f, share(still, 423, 320, 688, GpuBoardLabelsSmokeTest::mint), .06f,
                      "Outgoing dashes 7 on, 7 off");
                assertEquals(.5f, share(still, 417, 320, 688, GpuBoardLabelsSmokeTest::coral), .06f);
                assertEquals(0, share(still, 423, 320, 688, GpuBoardLabelsSmokeTest::coral));
                assertEquals(0, share(still, 417, 320, 688, GpuBoardLabelsSmokeTest::mint));
                // The dashes leave the shooter: the first one, 0-7 units up, has moved to 7-14 half a period later.
                assertTrue(mint(still, 423, 334) && !mint(still, 423, 341), "An outgoing dash clear of the nameplate");
                assertTrue(coral(still, 417, 693) && !coral(still, 417, 686), "The first incoming dash");
                hud.stage.act(.55f);
                Pixmap later = draw(hud, "labels-guides-later");
                assertTrue(!mint(later, 423, 334) && mint(later, 423, 341), "Outgoing flows toward the target");
                assertTrue(!coral(later, 417, 693) && coral(later, 417, 686), "Incoming flows toward the shooter");
                float thin = coverage(still, 334, 412, 434);
                still.dispose();
                later.dispose();

                // The open row, the inspected enemy's: its guides are 2.6 wide, its badge has the white .on frame.
                labels.click(hud, "contacts-preview-" + TIMBER_WOLF);
                labels.update(hud, frame, view);
                hud.stage.act(.55f);
                Pixmap open = draw(hud, "labels-guides-open");
                assertTrue(coverage(open, 334, 412, 434) > 1.2f * thin, "The open row's guide is wider");
                Rectangle badge = GpuHudTestStage.bounds(all(labels.root, "tn-badge").getFirst());
                Color frameColor = new Color(open.getPixel(Math.round(badge.x + badge.width / 2),
                      Math.round(badge.y + badge.height - 1)));
                assertTrue(frameColor.r > .9f && frameColor.g > .9f && frameColor.b > .9f, "White .on frame");
                open.dispose();
                labels.click(hud, "contacts-preview-" + TIMBER_WOLF);

                // The Outgoing toggle off: the incoming guide alone, on the line itself; then only the outgoing one.
                labels.click(hud, "contacts-outgoing");
                labels.update(hud, frame, view);
                Pixmap incoming = draw(hud, "labels-guides-incoming-only");
                assertEquals(.5f, share(incoming, 420, 320, 688, GpuBoardLabelsSmokeTest::coral), .06f);
                assertEquals(0, share(incoming, 423, 320, 688, GpuBoardLabelsSmokeTest::mint));
                incoming.dispose();
                labels.click(hud, "contacts-outgoing");
                labels.click(hud, "contacts-incoming");
                labels.update(hud, frame, view);
                Pixmap outgoing = draw(hud, "labels-guides-outgoing-only");
                assertEquals(.5f, share(outgoing, 420, 320, 688, GpuBoardLabelsSmokeTest::mint), .06f);
                assertEquals(0, share(outgoing, 417, 320, 688, GpuBoardLabelsSmokeTest::coral));
                outgoing.dispose();
                labels.click(hud, "contacts-incoming");

                // A row without a shot either way gets neither a guide nor a badge.
                labels.update(hud, frameOf(null, frame.status(), panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, inPlace(new Contact(TIMBER_WOLF, false, 9, Side.NONE,
                            Side.NONE))), GpuReportLog.Snapshot.EMPTY), view);
                Pixmap none = draw(hud, "labels-guides-no-shot");
                assertEquals(0, share(none, 423, 320, 688, GpuBoardLabelsSmokeTest::mint)
                      + share(none, 417, 320, 688, GpuBoardLabelsSmokeTest::coral));
                assertTrue(all(labels.root, "tn-badge").isEmpty(), "No badge without a shot");
                none.dispose();
            } finally {
                labels.dispose();
            }
        });
    }

    /**
     * Every shot is badged, and the guides are the preview's best 50 of each direction,
     * so a crowded battle stays legible. 60 enemies the Warhammer shoots, which all shoot back.
     */
    @Test
    void everyShotIsBadgedAndTheBestFiftyOfEachDirectionGetGuides() {
        GpuHudTestStage.run(hud -> {
            Labels labels = new Labels(hud, null);
            try {
                Map<Integer, Rectangle> units = new HashMap<>(Map.of(WARHAMMER, new Rectangle(100, 100, 20, 40)));
                List<Contact> contacts = new ArrayList<>();
                for (int index = 0; index < 60; index++) {
                    units.put(1000 + index, new Rectangle(150 + 25 * (index % 30), 500 + 150 * (index / 30), 20, 40));
                    contacts.add(new Contact(1000 + index, false, 9, side(2, 6, 72.22), side(4, 5, 83.33)));
                }
                GpuFirePreview.Snapshot preview = new GpuFirePreview.Snapshot(true, true, WARHAMMER, false,
                      new Coords(18, 12), 0, 0, "None", 0, 0, "", false, 60, 60, contacts);
                labels.update(hud, frameOf(null, turn(GamePhase.MOVEMENT, false, TIMBER_WOLF,
                      GpuHudFixtures.status().units()), panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, preview), GpuReportLog.Snapshot.EMPTY), view(false, units));
                assertEquals(60, all(labels.root, "tn-badge").size(), "Every shot is badged");
                assertEquals(100, guides(labels), "50 outgoing and 50 incoming guides");
            } finally {
                labels.dispose();
            }
        });
    }

    /**
     * Both directions use the same centre line and clearance, independent of facing and twist: in 3D the units'
     * rectangles, in the Tactical View the circles just outside the base rings (the squares around them).
     */
    @Test
    void guidesConnectCentresAndClearBothUnitsRegardlessOfFacing() {
        GpuHudTestStage.run(hud -> {
            Labels labels = new Labels(hud, null);
            try {
                for (boolean tactical : new boolean[] { false, true }) {
                    GpuHud.HudView view = tactical
                          ? view(true, Map.of(WARHAMMER, new Rectangle(370, 200, 100, 100),
                                TIMBER_WOLF, new Rectangle(370, 700, 100, 100)))
                          : view(false, Map.of(WARHAMMER, new Rectangle(400, 200, 40, 100),
                                TIMBER_WOLF, new Rectangle(400, 700, 40, 100)));
                    // The Tactical View's circle: radius .88 / .80 of the half square, the pair 3 off the centre line.
                    float near = tactical ? 250 + (float) Math.sqrt(55 * 55 - 9) : 303;
                    float far = tactical ? 750 - (float) Math.sqrt(55 * 55 - 9) : 697;
                    for (int facing = 0; facing < 6; facing++) {
                        Side salvo = new Side("", facing - 2, false, 2, 2, 6, 72.22, List.of());
                        GpuFirePreview.Snapshot preview = new GpuFirePreview.Snapshot(true, true, WARHAMMER, false,
                              new Coords(18, 12), 0, facing, "None", 0, 0, "", false, 1, 1,
                              List.of(new Contact(TIMBER_WOLF, false, 3, salvo, salvo)));
                        labels.update(hud, frameOf(null, GpuHudFixtures.status(), panels(GpuMovePlan.Snapshot.EMPTY,
                              GpuFireOrders.Snapshot.EMPTY, preview), GpuReportLog.Snapshot.EMPTY), view);
                        assertEquals(2, guides(labels));
                        assertTrue(new Vector2(423, near).epsilonEquals(endpoint(labels, 0, "from"), .01f));
                        assertTrue(new Vector2(423, far).epsilonEquals(endpoint(labels, 0, "to"), .01f));
                        assertTrue(new Vector2(417, far).epsilonEquals(endpoint(labels, 1, "from"), .01f));
                        assertTrue(new Vector2(417, near).epsilonEquals(endpoint(labels, 1, "to"), .01f));
                    }
                }
            } finally {
                labels.dispose();
            }
        });
    }

    @Test
    void bothGuideDirectionsFollowTheDestinationFloorOrFlightHeight() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            BoardCamera camera = new BoardCamera();
            camera.resize(hud.width(), hud.height());
            camera.setIsometric(true);
            camera.fit(scene);
            Labels labels = new Labels(hud, camera);
            try {
                Coords destination = new Coords(14, 9);
                Rectangle current = new Rectangle(200, 100, 40, 100);
                Vector2 ground = GpuNameplates.project(camera.camera,
                      BoardGeometry.center(destination, scene.tile(destination).elevation()));
                assertNotNull(ground);
                GpuFirePreview.Snapshot preview = new GpuFirePreview.Snapshot(true, true, WARHAMMER, true,
                      destination, scene.boardId(), 0, "Jump", 3, 1, "", false, 1, 1,
                      List.of(new Contact(TIMBER_WOLF, false, 3, side(2, 6, 72.22), side(2, 6, 72.22))));
                // Different floors, a rooftop/bridge deck, and an airborne destination use the same absolute level.
                for (float level : new float[] { 2, 4, 8, 12 }) {
                    GpuMovePlan.Step end = new GpuMovePlan.Step(destination, scene.boardId(), level, 0,
                          GpuMovePlan.Band.JUMP, level == 12);
                    GpuMovePlan.Snapshot plan = move(WARHAMMER, List.of(end), List.of(), List.of(), Map.of(), true);
                    Vector2 foot = GpuNameplates.project(camera.camera, BoardGeometry.center(destination, level));
                    assertNotNull(foot);
                    Rectangle ghost = new Rectangle(foot.x - 20, foot.y, 40, 100);
                    Rectangle enemy = new Rectangle(ground.x + 400, ground.y + 300, 80, 60);
                    GpuHud.HudView view = view(false, Map.of(WARHAMMER, current, TIMBER_WOLF, enemy));
                    labels.update(hud, frameOf(scene, GpuHudFixtures.status(), panels(plan,
                          GpuFireOrders.Snapshot.EMPTY, preview), GpuReportLog.Snapshot.EMPTY), view);
                    assertEquals(2, guides(labels));
                    for (int index = 0; index < 2; index++) {
                        Vector2 from = endpoint(labels, index, "from");
                        Vector2 to = endpoint(labels, index, "to");
                        Vector2 along = new Vector2(to).sub(from).nor();
                        assertEquals(3, Math.abs(along.crs(ghost.getCenter(new Vector2()).sub(from))), .01f,
                              "The guide passes beside the elevated ghost centre at level " + level);
                        assertEquals(3, Math.abs(along.crs(enemy.getCenter(new Vector2()).sub(from))), .01f,
                              "The guide passes beside the displayed target centre");
                        assertFalse(Intersector.intersectSegmentRectangle(from, to, ghost));
                        assertFalse(Intersector.intersectSegmentRectangle(from, to, enemy));
                    }
                }
                // Selecting a movement mode can mark the preview as planned before it has any route or ghost.
                GpuHud.HudView standing = view(false, Map.of(WARHAMMER, current, TIMBER_WOLF,
                      new Rectangle(200, 600, 40, 100)));
                GpuMovePlan.Snapshot unplotted = move(WARHAMMER, List.of(), List.of(), List.of(), Map.of(), true);
                labels.update(hud, frameOf(scene, GpuHudFixtures.status(), panels(unplotted,
                      GpuFireOrders.Snapshot.EMPTY, preview), GpuReportLog.Snapshot.EMPTY), standing);
                assertEquals(new Vector2(223, 203), endpoint(labels, 0, "from"));
                assertEquals(new Vector2(217, 203), endpoint(labels, 1, "to"));
            } finally {
                labels.dispose();
            }
        });
    }

    /** The strokes the board labels draw in their #fx layer. */
    private static List<?> strokes(Labels labels) throws ReflectiveOperationException {
        Actor strokes = labels.labels.fx();
        var list = strokes.getClass().getDeclaredField("list");
        list.setAccessible(true);
        return (List<?>) list.get(strokes);
    }

    private static int guides(Labels labels) throws ReflectiveOperationException {
        return strokes(labels).size();
    }

    private static Vector2 endpoint(Labels labels, int index, String name) throws ReflectiveOperationException {
        Object stroke = strokes(labels).get(index);
        var endpoint = stroke.getClass().getDeclaredMethod(name);
        endpoint.setAccessible(true);
        return (Vector2) endpoint.invoke(stroke);
    }

    /**
     * The badge of the unit the card shows lies over every other badge, the hovered
     * unit's over the rest, and without either the badges keep the preview's order. Two badges overlap here; the King
     * Crab's comes first in that order, so the Timber Wolf's lies over it until the King Crab is selected.
     */
    @Test
    void theShownUnitsBadgeLiesOverTheOthersAndTheOrderReturnsWithoutIt() {
        GpuHudTestStage.run(hud -> {
            Labels labels = new Labels(hud, null);
            try {
                Map<Integer, Rectangle> units = Map.of(WARHAMMER, new Rectangle(300, 200, 40, 100), KING_CRAB,
                      new Rectangle(700, 400, 40, 100), TIMBER_WOLF, new Rectangle(732, 406, 40, 100));
                GpuFirePreview.Snapshot preview = new GpuFirePreview.Snapshot(true, true, WARHAMMER, false,
                      new Coords(18, 12), 0, 0, "None", 0, 0, "", false, 2, 0, List.of(
                      new Contact(KING_CRAB, false, 8, side(5, 8, 41.67), Side.NONE),
                      new Contact(TIMBER_WOLF, false, 9, side(2, 10, 16.67), Side.NONE)));
                GpuBoardSource.Frame frame = frameOf(null, turn(GamePhase.MOVEMENT, false, TIMBER_WOLF,
                      GpuHudFixtures.status().units()), panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, preview), GpuReportLog.Snapshot.EMPTY);
                labels.update(hud, frame, view(units, Entity.NONE));
                Actor crab = badge(labels, view(units, Entity.NONE), KING_CRAB);
                Actor wolf = badge(labels, view(units, Entity.NONE), TIMBER_WOLF);
                Rectangle overlap = new Rectangle();
                assertTrue(Intersector.intersectRectangles(GpuHudTestStage.bounds(crab),
                      GpuHudTestStage.bounds(wolf), overlap) && overlap.width > 10 && overlap.height > 10,
                      "The two badges overlap: " + overlap);
                assertTrue(crab.getZIndex() < wolf.getZIndex(), "The preview's order draws the Timber Wolf's last");
                Pixmap normal = draw(hud, "labels-badges-order-normal");
                assertTrue(onShare(normal, overlap) < .1f, "No .on badge over the overlap");
                normal.dispose();

                // The King Crab inspected (its row open): its .on badge lies over the Timber Wolf's.
                labels.state.inspected = KING_CRAB;
                labels.update(hud, frame, view(units, Entity.NONE));
                assertTrue(crab.getZIndex() > wolf.getZIndex(), "The shown unit's badge is drawn last");
                Pixmap shown = draw(hud, "labels-badges-order-shown");
                assertTrue(onShare(shown, overlap) > .5f, "The .on badge covers the overlap");
                shown.dispose();
                // The hovered Timber Wolf comes before the shown unit, which still wins.
                labels.update(hud, frame, view(units, TIMBER_WOLF));
                assertTrue(crab.getZIndex() > wolf.getZIndex(), "The shown unit wins over the hovered one");

                // Deselected, a hovered King Crab is drawn last; without the hover the preview's order returns.
                labels.state.inspected = Entity.NONE;
                labels.update(hud, frame, view(units, KING_CRAB));
                assertTrue(crab.getZIndex() > wolf.getZIndex(), "The hovered unit's badge is drawn last");
                labels.update(hud, frame, view(units, Entity.NONE));
                assertTrue(crab.getZIndex() < wolf.getZIndex(), "The order returns to the preview's");
                draw(hud, "labels-badges-order-returned").dispose();
            } finally {
                labels.dispose();
            }
        });
    }

    /** Synthetic facts with the {@code hovered} unit: each unit's head at its rectangle's top centre. */
    private static GpuHud.HudView view(Map<Integer, Rectangle> units, int hovered) {
        GpuHud.HudView view = view(false, units);
        return new GpuHud.HudView(false, false, view.unitRects(), view.unitHeads(), Map.of(), null, hovered, 118);
    }

    /** The share of the pixels in {@code area} that show the .on badge's dark red fill (rgba 60 30 28 .95). */
    private static float onShare(Pixmap image, Rectangle area) {
        int count = 0;
        int total = 0;
        for (int y = Math.round(area.y) + 2; y < area.y + area.height - 2; y++) {
            for (int x = Math.round(area.x) + 2; x < area.x + area.width - 2; x++) {
                Color color = pixel(image, x, y);
                total++;
                count += color.r > color.g + .05f && color.r < .4f ? 1 : 0;
            }
        }
        return count / (float) total;
    }

    @Test
    void tracesLeadersAndBadgesFollowTheOrders() {
        GpuHudTestStage.run(hud -> {
            Labels labels = new Labels(hud, null);
            try {
                // The Atlas's orders on the Timber Wolf (A, primary) and the BattleMaster (B), focus on A; badges for
                // the selected weapon on the King Crab, the Locust (no shot: MegaMek's reason) and both targets.
                Map<Integer, Rectangle> units = Map.of(ATLAS, new Rectangle(300, 100, 40, 100), TIMBER_WOLF,
                      new Rectangle(700, 600, 40, 100), BATTLEMASTER, new Rectangle(1100, 300, 40, 100), KING_CRAB,
                      new Rectangle(300, 800, 40, 100), ENEMY_LOCUST, new Rectangle(1500, 700, 40, 100));
                GpuFireOrders.Snapshot orders = orders(fire(null), fire(null).targets(), List.of(
                      new GpuFireOrders.Badge(TIMBER_WOLF, 7, 58.33, ""),
                      new GpuFireOrders.Badge(KING_CRAB, TargetRoll.AUTOMATIC_SUCCESS, 100, ""),
                      new GpuFireOrders.Badge(ENEMY_LOCUST, TargetRoll.IMPOSSIBLE, 0, OUT_OF_ARC),
                      new GpuFireOrders.Badge(BATTLEMASTER, 8, 41.67, "")));
                GpuBoardSource.Frame frame = frameOf(null, turn(GamePhase.FIRING, true, ATLAS,
                      GpuHudFixtures.status().units()), panels(GpuMovePlan.Snapshot.EMPTY, orders,
                      GpuFirePreview.Snapshot.NONE), GpuReportLog.Snapshot.EMPTY);
                GpuHud.HudView view = view(false, units);
                // The Timber Wolf's card covers its head; the BattleMaster's stands above and left of it.
                labels.labels.cards(Map.of(TargetKey.unit(TIMBER_WOLF), new Rectangle(650, 680, 200, 100),
                      TargetKey.unit(BATTLEMASTER), new Rectangle(900, 450, 200, 80)));
                labels.update(hud, frame, view);
                // Badges bottom-centred 4 over the heads; an automatic success reads 2+; none on a card's unit.
                assertEquals(Map.of(KING_CRAB, List.of("2+", "100%"), ENEMY_LOCUST, List.of(OUT_OF_ARC)),
                      labels.badges(view));
                Pixmap solid = draw(hud, "labels-traces-3d");
                // Traces follow the units' centres, the primary's brighter.
                float primary = brightest(solid, new Vector2(320, 150), new Vector2(720, 650));
                float secondary = brightest(solid, new Vector2(320, 150), new Vector2(1120, 350));
                assertTrue(secondary > 2.3f && primary > secondary + .2f, primary + " vs " + secondary);
                // A leader only where the card leaves the head free: from the card's nearest point (1100, 450) to
                // the BattleMaster's head (1120, 400), with the diamond there.
                assertTrue(diamond(solid, 1120, 400), "The leader's diamond at the BattleMaster's head");
                assertFalse(diamond(solid, 720, 700), "No leader to a head under its own card");
                assertTrue(brightest(solid, new Vector2(1100, 450), new Vector2(1120, 400)) > 2.4f, "The leader");
                solid.dispose();

                // The Tactical View: traces from icon centre to icon centre, the secondary one coral.
                GpuHud.HudView flat = view(true, units);
                labels.update(hud, frame, flat);
                Pixmap tactical = draw(hud, "labels-traces-tactical");
                assertTrue(along(tactical, new Vector2(320, 150), new Vector2(1120, 350),
                      GpuBoardLabelsSmokeTest::coral) > .3f, "The secondary trace is coral");
                assertTrue(along(tactical, new Vector2(320, 150), new Vector2(720, 650),
                      color -> color.r > .85f && color.g > .85f && color.b > .85f) > .3f, "The primary's is white");
                tactical.dispose();
                // Without orders nothing is drawn.
                labels.update(hud, frameOf(null, frame.status(), panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE), GpuReportLog.Snapshot.EMPTY), view);
                Pixmap empty = draw(hud, "labels-traces-none");
                assertFalse(diamond(empty, 1120, 400));
                assertTrue(brightest(empty, new Vector2(320, 150), new Vector2(720, 650)) < 1.5f, "No trace");
                assertTrue(labels.badges(view).isEmpty());
                empty.dispose();
            } finally {
                labels.dispose();
            }
        });
    }

    @Test
    void popUpsNameTheShotOrRollRiseFadeAndFreeze() {
        GpuHudTestStage.run(hud -> {
            Labels labels = new Labels(hud, null);
            try {
                Map<Integer, Rectangle> units = new HashMap<>(Map.of(KING_CRAB, new Rectangle(400, 300, 40, 100),
                      TIMBER_WOLF, new Rectangle(1000, 300, 40, 100)));
                // The report entries the events link to: rolled 4 against 8+, and a location destroyed.
                UUID miss = UUID.randomUUID();
                UUID blasted = UUID.randomUUID();
                GpuReportLog.Snapshot reports = new GpuReportLog.Snapshot(3, GamePhase.FIRING_REPORT, List.of(
                      entry(miss, KING_CRAB, 8, 4, null, false), entry(blasted, KING_CRAB, 6, 9, null, true)),
                      Map.of(), List.of(), List.of(), List.of(), List.of());
                GpuBoardSource.Frame frame = frameOf(null, turn(GamePhase.FIRING_REPORT, false, Entity.NONE,
                      GpuHudFixtures.status().units()), panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE), reports);
                labels.update(hud, frame, view(false, units));

                // What each pop-up says (J10, showShot).
                Map<String, List<String>> said = new HashMap<>();
                said.put("hit", pop(labels, () -> labels.labels.show(hit(KING_CRAB, "Medium Laser", 5,
                      new ResolvedAttack.Impact("CT", false, 5)))));
                said.put("rear", pop(labels, () -> labels.labels.show(hit(KING_CRAB, "AC/20", 20,
                      new ResolvedAttack.Impact("CT", true, 20)))));
                said.put("cluster", pop(labels, () -> labels.labels.show(new GpuReportLog.CombatEvent(
                      UUID.randomUUID(), 3, GamePhase.FIRING, ResolvedAttack.Kind.SHOT, ATLAS, KING_CRAB, "LRM 20",
                      "LT", true, 12, List.of(new ResolvedAttack.Impact("RL", false, 5),
                            new ResolvedAttack.Impact("LA", false, 5), new ResolvedAttack.Impact("RA", false, 2)), 12,
                      20))));
                said.put("destroyed", pop(labels, () -> labels.labels.show(event(blasted, KING_CRAB, "PPC", true, 10,
                      List.of(new ResolvedAttack.Impact("LA", false, 10))))));
                said.put("no damage", pop(labels, () -> labels.labels.show(event(UUID.randomUUID(), KING_CRAB, "TAG",
                      true, 0, List.of()))));
                said.put("miss", pop(labels, () -> labels.labels.show(event(miss, KING_CRAB, "AC/20", false, 0,
                      List.of()))));
                said.put("unlinked miss", pop(labels, () -> labels.labels.show(event(UUID.randomUUID(), KING_CRAB,
                      "AC/20", false, 0, List.of()))));
                said.put("passed", pop(labels, () -> labels.labels.show(new GpuReportLog.PsrItem(1, 3,
                      GamePhase.FIRING, KING_CRAB, 5, 7, true, "20+ damage"))));
                said.put("falls", pop(labels, () -> labels.labels.show(new GpuReportLog.PsrItem(2, 3,
                      GamePhase.FIRING, KING_CRAB, 5, 3, false, "20+ damage"))));
                said.put("not fired", pop(labels, () -> labels.labels.show(entry(null, KING_CRAB, null, null,
                      "Target not in arc.", false))));
                Map<String, List<String>> expected = new HashMap<>();
                expected.put("hit", List.of("\u22125 CT", "MEDIUM LASER"));
                expected.put("rear", List.of("\u221220 CTR", "AC/20"));
                expected.put("cluster", List.of("\u221212 RL LA RA", "LRM 20 " + DOT + " 12/20"));
                expected.put("destroyed", List.of("\u221210 LA", "LOCATION DESTROYED"));
                expected.put("no damage", List.of("HIT", "TAG"));
                expected.put("miss", List.of("MISS", "4 vs 8+"));
                expected.put("unlinked miss", List.of("MISS"));
                expected.put("passed", List.of("PSR PASSED"));
                expected.put("falls", List.of("FALLS"));
                expected.put("not fired", List.of("NOT FIRED"));
                assertEquals(expected, said);
                // An attack made, or one at a hex or a building (no unit to show it at), pops nothing up.
                labels.labels.show(entry(null, KING_CRAB, 8, 4, null, false));
                labels.labels.show(event(UUID.randomUUID(), Entity.NONE, "Arrow IV", true, 20, List.of()));
                assertTrue(labels.pops().isEmpty());

                // A pop-up starts 18 right of the unit's middle and 40 over it, rises 30 a second and is gone at 1.8 s.
                Vector2 middle = new Vector2(420, 355);
                labels.labels.show(hit(KING_CRAB, "Medium Laser", 5, new ResolvedAttack.Impact("CT", false, 5)));
                Actor pop = labels.pops().getFirst();
                assertEquals(middle.x + 18, GpuHudTestStage.bounds(pop).x, 1);
                assertEquals(middle.y + 40, top(pop), 1);
                hud.stage.act(.5f);
                assertEquals(middle.y + 40 + 15, top(pop), 1, "Risen 15 in half a second");
                // It follows its unit: the King Crab moves 100 to the right.
                units.put(KING_CRAB, new Rectangle(500, 300, 40, 100));
                labels.update(hud, frame, view(false, units));
                assertEquals(middle.x + 100 + 18, GpuHudTestStage.bounds(pop).x, 1);
                hud.stage.act(1.2f);
                assertEquals(List.of(pop), labels.pops(), "Still there at 1.7 s");
                hud.stage.act(.2f);
                assertTrue(labels.pops().isEmpty(), "Gone at 1.8 s");

                // Paused or reviewing (frozenFx): only the newest shows, held young, and it stays until play goes on.
                labels.labels.show(hit(KING_CRAB, "Medium Laser", 5, new ResolvedAttack.Impact("CT", false, 5)));
                hud.stage.act(.2f);
                labels.labels.show(hit(TIMBER_WOLF, "AC/20", 20, new ResolvedAttack.Impact("CT", false, 20)));
                Actor older = labels.pops().getFirst();
                Actor newest = labels.pops().getLast();
                hud.stage.act(.5f);
                labels.labels.freeze(true);
                hud.stage.act(.1f);
                float held = top(newest);
                assertEquals(355 + 40 + .35f * 30, held, 1, "Held at .35 s");
                Pixmap frozen = draw(hud, "labels-pops-frozen");
                assertTrue(gold(frozen, GpuHudTestStage.bounds(newest)), "The newest shows");
                assertFalse(gold(frozen, GpuHudTestStage.bounds(older)), "An older one hides");
                frozen.dispose();
                hud.stage.act(1.5f);
                assertEquals(List.of(newest), labels.pops(), "The older one ends, the newest stays");
                assertEquals(held, top(newest), 1, "and does not rise");
                labels.labels.freeze(false);
                hud.stage.act(.01f);
                assertTrue(labels.pops().isEmpty(), "Once play goes on, its time is up");

                // A volley's hits on one unit stack (the user's report of 2026-10-02): the newest keeps its place and
                // each older one rides 4 over the next newer one while they rise; another unit's pop-up stays apart.
                for (int shot = 0; shot < 3; shot++) {
                    labels.labels.show(hit(KING_CRAB, "Medium Laser", 5, new ResolvedAttack.Impact("CT", false, 5)));
                }
                labels.labels.show(hit(TIMBER_WOLF, "AC/20", 20, new ResolvedAttack.Impact("CT", false, 20)));
                List<Actor> volley = labels.pops();
                draw(hud, "labels-pops-stacked").dispose();
                for (float age : new float[] { 0, .5f }) {
                    assertEquals(355 + 40 + age * 30, top(volley.get(2)), 1, "The newest hit keeps its place");
                    for (int hit = 0; hit < 2; hit++) {
                        assertEquals(top(volley.get(hit + 1)) + 4, GpuHudTestStage.bounds(volley.get(hit)).y, 1,
                              "Each older hit rides 4 over the next newer one at " + age + " s");
                    }
                    assertEquals(355 + 40 + age * 30, top(volley.get(3)), 1, "Another unit's pop-up is not lifted");
                    hud.stage.act(.5f);
                }
            } finally {
                labels.dispose();
            }
        });
    }

    // Drawing and capturing.

    /**
     * Draws the board with the G7 overlay as GpuBattleView orders it (in the Tactical View on the bases), then the
     * HUD's labels layer with the view's facts of that draw, and returns them.
     */
    private static GpuHud.HudView draw(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          Labels labels, GpuBoardSource.Frame frame) {
        boolean tactical = board.tactical();
        labels.state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
        overlay.update(frame, new GpuHud.HudView(tactical, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0),
              preferences(), labels.state);
        GpuFireOrders.Snapshot fire = frame.panels().fire();
        board.basesStatus = frame.status();
        board.draw(camera -> {
            overlay.renderGhost(camera, board.instances::get);
            overlay.renderMarks(camera, board.poses::get);
            overlay.render(camera);
        });
        GpuHud.HudView view = view(board, board.anchors, tactical);
        labels.update(hud, frame, view);
        hud.drawStage();
        return view;
    }

    /** Draws the labels alone over the harness's plain background and captures them. */
    private static Pixmap draw(GpuHudTestStage hud, String name) {
        hud.draw();
        opaque();
        return hud.capture(name);
    }

    /**
     * The board view's facts of the last harness draw, as I1b will hand them over: each drawn unit's head (its label
     * anchor) and its screen rectangle, in 3D from its hex's ground up to the head and .55 of a hex wide (the
     * prototype's unitRect), in the Tactical View the square around its base's ring; the hex's width on the screen.
     */
    static GpuHud.HudView view(GpuBoardSpaceHarness board, Map<BoardScene.Unit, Vector3> anchors,
          boolean tactical) {
        Camera camera = board.camera.camera;
        float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        // Both views look north, so a world x span is a screen x span.
        float perUnit = (camera.project(new Vector3(1, 0, 0)).x - camera.project(new Vector3(0, 0, 0)).x) * density;
        float hex = BoardGeometry.WIDTH * perUnit;
        float icon = GpuUnitBases.RING * BoardGeometry.HEIGHT * perUnit;
        Map<Integer, Vector2> heads = heads(camera, anchors);
        Map<Integer, Rectangle> rects = new HashMap<>();
        anchors.keySet().forEach(unit -> {
            Vector2 head = heads.get(unit.id());
            Vector2 foot = GpuNameplates.project(camera, board.poses.get(unit).position());
            if (head != null && foot != null) {
                foot.scl(density);
                rects.put(unit.id(), tactical ? new Rectangle(foot.x - icon / 2, foot.y - icon / 2, icon, icon)
                      : new Rectangle(foot.x - .275f * hex, foot.y, .55f * hex, head.y - foot.y));
            }
        });
        return new GpuHud.HudView(tactical, false, rects, heads, Map.of(), null, Entity.NONE, hex);
    }

    /** Synthetic facts: each unit's rectangle, its head at the rectangle's top centre. */
    private static GpuHud.HudView view(boolean tactical, Map<Integer, Rectangle> units) {
        Map<Integer, Vector2> heads = new HashMap<>();
        units.forEach((id, rect) -> heads.put(id, new Vector2(rect.x + rect.width / 2, rect.y + rect.height)));
        return new GpuHud.HudView(tactical, false, units, heads, Map.of(), null, Entity.NONE, 118);
    }

    /** One frame of the movement plan over the harness board, which frames the board with its camera first. */
    private static void show(Labels labels, GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuHud.HudView view,
          GpuMovePlan.Snapshot plan) {
        labels.update(hud, frameOf(board.scene, GpuHudFixtures.status(), panels(plan, GpuFireOrders.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE), GpuReportLog.Snapshot.EMPTY), view);
        hud.draw();
    }

    /** The tip's bounds with the destination's hex centre at window pixel (x, y), keeping clear of {@code panels}. */
    private static Rectangle tip(GpuHudTestStage hud, GpuBoardSpaceHarness board, Labels labels,
          GpuBoardSource.Frame frame, GpuHud.HudView view, Coords destination, float x, float y,
          Rectangle... panels) {
        frame(board, false, destination, 118, x, y);
        Vector2 projected = board.screen(ground(board.scene, destination));
        assertEquals(x, projected.x, 1, "The camera puts the destination at the requested x");
        assertEquals(Gdx.graphics.getBackBufferHeight() - y, projected.y, 1,
              "The camera puts the destination at the requested y");
        labels.update(hud, frame, view, panels);
        assertTrue(labels.tip().isVisible());
        return GpuHudTestStage.bounds(labels.tip());
    }

    /** Writes {name}.png and each crop beside the same place of the mock shot; returns the capture. */
    private static Pixmap capture(GpuHudTestStage hud, String name, String shot, Crop... crops) {
        opaque();
        Pixmap captured = hud.capture(name);
        for (Crop crop : crops) {
            hud.compare(name + "-" + crop.part(), captured, crop.actor(), shot, crop.x(), crop.y());
        }
        return captured;
    }

    /** A part of a capture to compare, and the top-left corner of the same part in the mock shot. */
    private record Crop(String part, Actor actor, int x, int y) { }

    /** An area of the window, given from its top left as the prototype's CSS, for a side-by-side crop. */
    private static Actor area(GpuHudTestStage hud, int x, int y, int width, int height) {
        Actor area = new Actor();
        area.setBounds(x, hud.height() - y - height, width, height);
        return area;
    }

    private static Actor badge(Labels labels, GpuHud.HudView view, int unit) {
        Vector2 head = view.unitHeads().get(unit);
        return all(labels.root, "tn-badge").stream().filter(actor -> {
            Rectangle bounds = GpuHudTestStage.bounds(actor);
            return Math.abs(bounds.x + bounds.width / 2 - head.x) <= 1;
        }).findFirst().orElseThrow();
    }

    /**
     * Stand-ins for G9's card placement: each target's card above its head as shots 05 and 06 show them (A to its
     * upper right, the others above and to the left), kept 8 inside the window.
     */
    private static Map<TargetKey, Rectangle> cards(GpuHudTestStage hud, GpuHud.HudView view,
          Map<Integer, Character> letters) {
        Map<TargetKey, Rectangle> cards = new HashMap<>();
        letters.forEach((id, letter) -> {
            Vector2 head = view.unitHeads().get(id);
            if (head != null) {
                Rectangle card = switch (letter) {
                    case 'A' -> new Rectangle(head.x + 33, head.y + 20, 300, 265);
                    case 'B' -> new Rectangle(head.x - 267, head.y + 23, 297, 130);
                    default -> new Rectangle(head.x - 214, head.y + 22, 258, 91);
                };
                card.x = Math.max(8, Math.min(card.x, hud.width() - 8 - card.width));
                card.y = Math.max(8, Math.min(card.y, hud.height() - 8 - card.height));
                cards.put(TargetKey.unit(id), card);
            }
        });
        return cards;
    }

    // Assertions over actors and pixels.

    private static void assertPin(Labels labels, GpuBoardSpaceHarness board, BoardScene scene, int number,
          Coords hex) {
        Actor pin = labels.pin(number);
        assertNotNull(pin, "Waypoint " + number);
        assertTrue(pin.isVisible(), "Waypoint " + number + " shows");
        assertEquals(List.of(String.valueOf(number)), texts(pin));
        Vector2 centre = board.screen(ground(scene, hex));
        assertCentred(pin, centre.x, centre.y + 22, "Waypoint " + number + " 22 above " + hex);
    }

    private static void assertCentred(Actor actor, float x, float y, String what) {
        Rectangle bounds = GpuHudTestStage.bounds(actor);
        assertEquals(x, bounds.x + bounds.width / 2, 1, what);
        assertEquals(y, bounds.y + bounds.height / 2, 1, what);
    }

    /** The texts of the visible, non-empty labels under {@code actor}, in drawing order. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (actor.isVisible() && actor instanceof Label label && label.getText().length() > 0) {
            texts.add(label.getText().toString());
        }
        if (actor.isVisible() && actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(texts(child)));
        }
        return texts;
    }

    /** Every actor named {@code name} under {@code root}. */
    private static List<Actor> all(Group root, String name) {
        List<Actor> found = new ArrayList<>();
        for (Actor child : root.getChildren()) {
            if (name.equals(child.getName())) {
                found.add(child);
            }
            if (child instanceof Group group) {
                found.addAll(all(group, name));
            }
        }
        return found;
    }

    /** Shows one pop-up through {@code show}, returns its texts and removes it. */
    private static List<String> pop(Labels labels, Runnable show) {
        show.run();
        List<Actor> pops = labels.pops();
        assertEquals(1, pops.size());
        List<String> texts = texts(pops.getFirst());
        pops.getFirst().remove();
        return texts;
    }

    private static float top(Actor actor) {
        Rectangle bounds = GpuHudTestStage.bounds(actor);
        return bounds.y + bounds.height;
    }

    private static Color pixel(Pixmap image, float x, float y) {
        return new Color(image.getPixel(Math.round(x), Math.round(y)));
    }

    /** The outgoing guide's mint over the olive background. */
    private static boolean mint(Color color) {
        return color.g > .7f && color.r < .65f;
    }

    private static boolean mint(Pixmap image, int x, int y) {
        return mint(pixel(image, x, y));
    }

    /** The incoming guide's red and the secondary trace's coral. */
    private static boolean coral(Color color) {
        return color.r > .75f && color.g < .65f;
    }

    private static boolean coral(Pixmap image, int x, int y) {
        return coral(pixel(image, x, y));
    }

    /** The share of the pixels of column {@code x} from row {@code from} to {@code to} that pass {@code test}. */
    private static float share(Pixmap image, int x, int from, int to, Function<Color, Boolean> test) {
        int count = 0;
        for (int y = from; y <= to; y++) {
            count += test.apply(pixel(image, x, y)) ? 1 : 0;
        }
        return count / (float) (to - from + 1);
    }

    /** The green a mint line adds across row {@code y} from column {@code from} to {@code to}, over the olive. */
    private static float coverage(Pixmap image, int y, int from, int to) {
        float total = 0;
        for (int x = from; x <= to; x++) {
            total += Math.max(0, pixel(image, x, y).g - .5f);
        }
        return total;
    }

    /**
     * The largest r + g + b along the middle of a segment (its ends left out, where other strokes meet it), sampled
     * every half unit with a one-unit reach across it.
     */
    private static float brightest(Pixmap image, Vector2 from, Vector2 to) {
        float brightest = 0;
        Vector2 across = new Vector2(to).sub(from).nor().rotate90(1);
        for (float t = .15f; t <= .85f; t += .5f / from.dst(to)) {
            Vector2 point = new Vector2(from).lerp(to, t);
            for (int side = -1; side <= 1; side++) {
                Color color = pixel(image, point.x + across.x * side, point.y + across.y * side);
                brightest = Math.max(brightest, color.r + color.g + color.b);
            }
        }
        return brightest;
    }

    /**
     * The share of the unit steps along the middle of a segment where a pixel within one unit across the line passes
     * {@code test}.
     */
    private static float along(Pixmap image, Vector2 from, Vector2 to, Function<Color, Boolean> test) {
        int samples = 0;
        int hits = 0;
        Vector2 across = new Vector2(to).sub(from).nor().rotate90(1);
        for (float t = .1f; t <= .9f; t += 1 / from.dst(to)) {
            Vector2 point = new Vector2(from).lerp(to, t);
            samples++;
            for (int side = -1; side <= 1; side++) {
                if (test.apply(pixel(image, point.x + across.x * side, point.y + across.y * side))) {
                    hits++;
                    break;
                }
            }
        }
        return hits / (float) samples;
    }

    /** The leader's diamond (#ec6f64) at a head. */
    private static boolean diamond(Pixmap image, float x, float y) {
        Color color = pixel(image, x, y);
        return color.r > .85f && color.g > .35f && color.g < .5f && color.b > .3f && color.b < .48f;
    }

    /** Whether a hit pop-up's gold (#ffd27a) shows inside the bounds. */
    private static boolean gold(Pixmap image, Rectangle bounds) {
        for (int y = Math.round(bounds.y); y < bounds.y + bounds.height; y++) {
            for (int x = Math.round(bounds.x); x < bounds.x + bounds.width; x++) {
                Color color = pixel(image, x, y);
                if (color.r > .9f && color.g > .7f && color.b < .6f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean same(Pixmap one, Pixmap other) {
        for (int y = 0; y < one.getHeight(); y++) {
            for (int x = 0; x < one.getWidth(); x++) {
                if (one.getPixel(x, y) != other.getPixel(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Snapshots.

    private static GpuBoardSource.Frame frameOf(BoardScene scene, GpuBattleStatus.Snapshot status, GpuHudData panels,
          GpuReportLog.Snapshot reports) {
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null, reports,
              status, panels);
    }

    private static GpuHudData panels(GpuMovePlan.Snapshot move, GpuFireOrders.Snapshot fire,
          GpuFirePreview.Snapshot preview) {
        return new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, move, fire, GpuPhysicalOptions.Snapshot.EMPTY,
              GpuUnitRecord.Snapshot.EMPTY, preview, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
    }

    /** The mock's battle in another phase, turn and actor, with the given units. */
    private static GpuBattleStatus.Snapshot turn(GamePhase phase, boolean myTurn, int actor, List<UnitStatus> units) {
        GpuBattleStatus.Snapshot mock = GpuHudFixtures.status();
        return new GpuBattleStatus.Snapshot(mock.round(), phase, myTurn, mock.localPlayerId(), actor, mock.turns(),
              mock.turnIndex(), units, mock.initiative(), false);
    }

    /** The plan with the destination tip's facts of a shot: its movement word, MP, modifiers and pins. */
    private static GpuMovePlan.Snapshot facts(GpuMovePlan.Snapshot plan, String type, int cost, int budget, int heat,
          int tmm, List<GpuMovePlan.Step> pins) {
        return new GpuMovePlan.Snapshot(plan.active(), plan.planner(), plan.external(), plan.entityId(), plan.mode(),
              plan.explicit(), plan.gearLabel(), plan.route(), plan.hover(), pins, plan.destination(), plan.facing(),
              cost, budget, plan.type(), type, plan.auto(), heat, tmm, plan.legal(), plan.warnings(), plan.canUndo(),
              plan.canPin(), plan.facingMatters(), plan.envelope(), plan.mounts(), plan.holdingRemaining());
    }

    /** The Atlas's plotted route through {@code hexes} (facing north-east at the end) with {@code pins}. */
    private static GpuMovePlan.Snapshot route(BoardScene scene, List<Coords> hexes, List<Coords> pins) {
        List<GpuMovePlan.Step> steps = hexes.stream().map(hex -> step(scene, hex, 1, GpuMovePlan.Band.WALK)).toList();
        List<GpuMovePlan.Step> pinned = pins.stream().map(hex -> step(scene, hex, 1, GpuMovePlan.Band.WALK)).toList();
        return facts(move(ATLAS, steps, List.of(), pinned, Map.of(), true), "walk", hexes.size(), 3, 1, 0, pinned);
    }

    /** The fire orders with other targets and TN badges. */
    private static GpuFireOrders.Snapshot orders(GpuFireOrders.Snapshot fire, List<GpuFireOrders.Target> targets,
          List<GpuFireOrders.Badge> badges) {
        return new GpuFireOrders.Snapshot(fire.active(), fire.editable(), fire.actorId(), fire.focus(),
              fire.selectedWeapon(), fire.weapons(), targets, fire.attacks(), fire.twist(), fire.canTwistLeft(),
              fire.canTwistRight(), fire.torsoLabel(), fire.heat(), fire.solution(), fire.frontArc(), badges,
              fire.hoverBest(), fire.drafted(), fire.pendingUnits(), fire.autoDeclareRemaining(), fire.aimChoice());
    }

    /** The preview from another hex, the plan's destination (shot 03 names the hex beside the fixture's). */
    private static GpuFirePreview.Snapshot from(GpuFirePreview.Snapshot preview, Coords hex) {
        return new GpuFirePreview.Snapshot(preview.active(), preview.complete(), preview.unitId(),
              preview.fromDestination(), hex, preview.boardId(), preview.facing(), preview.moved(),
              preview.attackerModifier(), preview.tmm(), preview.unavailable(), preview.breachNotPredicted(),
              preview.targets(), preview.threats(), preview.contacts());
    }

    /** Shot 04's preview with the Archer still a sensor contact, as the fixture's battle status has it. */
    private static GpuFirePreview.Snapshot sensorArcher(GpuFirePreview.Snapshot preview) {
        List<Contact> contacts = preview.contacts().stream().map(contact -> contact.id() == CONTACT
              ? new Contact(CONTACT, true, contact.distance(), Side.NONE, Side.NONE) : contact).toList();
        return new GpuFirePreview.Snapshot(preview.active(), preview.complete(), preview.unitId(),
              preview.fromDestination(), preview.from(), preview.boardId(), preview.facing(), preview.moved(),
              preview.attackerModifier(), preview.tmm(), preview.unavailable(), preview.breachNotPredicted(),
              preview.targets(), preview.threats(), contacts);
    }

    /** The Warhammer previewed where it stands, against one enemy. */
    private static GpuFirePreview.Snapshot inPlace(Contact contact) {
        return new GpuFirePreview.Snapshot(true, true, WARHAMMER, false, new Coords(18, 12), 0, 0, "None", 0, 0, "",
              false, 1, 1, List.of(contact));
    }

    private static Side side(int available, int best, double odds) {
        return new Side("", 0, false, available, 7, best, odds, List.of());
    }

    private static GpuReportLog.CombatEvent hit(int target, String weapon, int damage, ResolvedAttack.Impact impact) {
        return event(UUID.randomUUID(), target, weapon, true, damage, List.of(impact));
    }

    private static GpuReportLog.CombatEvent event(UUID id, int target, String weapon, boolean hit, int damage,
          List<ResolvedAttack.Impact> impacts) {
        return new GpuReportLog.CombatEvent(id, 3, GamePhase.FIRING, ResolvedAttack.Kind.SHOT, ATLAS, target, weapon,
              "RT", hit, damage, impacts, null, 0);
    }

    /** An attack's report entry: linked to {@code attack} (null: none), its target number and roll, not fired. */
    private static GpuReportLog.Entry entry(UUID attack, int target, Integer targetNumber, Integer roll,
          String notFired, boolean locationDestroyed) {
        return new GpuReportLog.Entry(3, "Weapon attack", GamePhase.FIRING, "", "", List.of(), "", List.of(),
              GpuReportLog.Kind.WEAPON, ATLAS, target, attack, List.of(), false, false, targetNumber, roll, notFired,
              null, null, null, null, locationDestroyed, false);
    }
}
