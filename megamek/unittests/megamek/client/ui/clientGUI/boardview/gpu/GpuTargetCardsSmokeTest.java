/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.frame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.preferences;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.unit;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuNameplatesSmokeTest.opaque;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * G9: the target cards over the board-space harness in the states of the hud-v3 shots 05, 06, 07 (Tactical View) and
 * 15 (1280 x 720), placed by GpuCardPlacement clear of the shots' HUD panels and drawn over G8b's labels, which take
 * their leaders from the placed rectangles; each card beside its crop of the shot. Then the controls with mocked
 * services, a focused grip's Alt+Up/Down, and the reordering over a real FiringDisplay, where the letters stay. The
 * mock orders are display values read off the shots, as E3b publishes them.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuTargetCardsSmokeTest {
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    private static final int ENEMY_LOCUST = 10;
    /** The Atlas's weapons in shot 05, by equipment number. */
    private static final int AC20 = 1;
    private static final int LRM = 2;
    private static final int SRM = 3;
    private static final int LASER_LA = 4;
    private static final int LASER_RA = 5;
    private static final int REAR = 7;
    private static final String REORDER_HINT = "Drag or Alt+↑/↓ to reorder";
    /** Shot 05's HUD panels at 1920 x 1080 (r1 section 2): x, top, width, height. */
    private static final int[][] PANELS_05 = { { 20, 20, 300, 84 }, { 20, 116, 300, 507 }, { 20, 808, 300, 252 },
          { 1535, 18, 365, 56 }, { 1590, 90, 310, 208 }, { 1590, 312, 310, 556 }, { 1590, 880, 310, 130 },
          { 670, 892, 580, 158 }, { 722, 1056, 476, 17 }, { 1740, 1026, 160, 36 } };
    /** Shot 15's at 1280 x 720, the solution card beside the right column and no hint line. */
    private static final int[][] PANELS_15 = { { 16, 16, 250, 80 }, { 16, 108, 250, 356 }, { 16, 478, 250, 226 },
          { 930, 18, 334, 56 }, { 994, 90, 270, 178 }, { 994, 282, 270, 368 }, { 712, 90, 270, 144 },
          { 390, 532, 500, 158 }, { 1112, 662, 152, 36 } };

    /**
     * The cards with the context menu, and the nameplates and board labels below them, stacked as GpuHud's labels
     * layer: the labels' #fx strokes, the plates, the labels, the cards; the menu's popover above.
     */
    private static final class Layer {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuContextMenu menu;
        final GpuNameplates plates;
        final GpuBoardLabels labels;
        final GpuTargetCards cards;

        Layer(GpuHudTestStage hud, GpuBoardSource source, BoardCamera camera) {
            menu = new GpuContextMenu(hud.kit, source, state, id -> { });
            plates = new GpuNameplates(hud.kit, source, state);
            labels = new GpuBoardLabels(hud.kit, source, state, camera, null, plates);
            cards = new GpuTargetCards(hud.kit, source, state, menu);
            for (Actor layer : List.of(labels.fx(), plates.actor(), labels.actor(), cards.actor(), menu.actor())) {
                hud.window.addActor(layer);
                layer.setBounds(0, 0, hud.width(), hud.height());
            }
        }

        /** One HUD frame: the state's rules, the menu, plates, labels and cards; the leaders from the placed cards. */
        Layer update(GpuHudTestStage hud, GpuBoardSource.Frame frame, GpuHud.HudView view, List<Rectangle> panels) {
            state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
            frame.status().units().stream().map(GpuBattleStatus.UnitStatus::icon).forEach(hud.sprites::add);
            GpuHud.Inputs inputs = new GpuHud.Inputs(frame, view, null, GpuHudFixtures.preferences(),
                  GpuHud.Metrics.of(hud.width(), hud.height()), panels);
            menu.update(inputs);
            plates.update(inputs);
            labels.update(inputs);
            cards.update(inputs);
            labels.cards(cards.placed());
            return this;
        }

        Table card(int id) {
            return ((Group) cards.actor()).findActor("target-card-" + id);
        }

        UiPopover popover() {
            return ((Group) menu.actor()).findActor("context-menu-popover");
        }

        void dispose() {
            for (Actor layer : List.of(labels.fx(), plates.actor(), labels.actor(), cards.actor(), menu.actor())) {
                layer.remove();
            }
            labels.dispose();
        }
    }

    @Test
    void cardsOverTheBoardBesideShots05To07And15() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            try {
                Coords atlasHex = unit(scene, ATLAS).location().coords();
                GpuBattleStatus.Snapshot declaring = GpuBoardOverlaySmokeTest.status(GamePhase.FIRING, ATLAS);

                // Shot 05: four attacks on the Timber Wolf (A, primary), the LRM on the BattleMaster (B).
                for (boolean tactical : new boolean[] { false, true }) {
                    Layer layer = new Layer(hud, mock(GpuBoardSource.class), board.camera);
                    frame(board, tactical, atlasHex, tactical ? 60.8f : 118, tactical ? 868 : 925,
                          tactical ? 672 : 790);
                    GpuHud.HudView view = draw(hud, board, overlay, layer, firing(scene, declaring, shot05()),
                          panels(hud, PANELS_05));
                    Table wolf = layer.card(TIMBER_WOLF);
                    Table master = layer.card(BATTLEMASTER);
                    // No fire-order numbers in the rows.
                    assertEquals(List.of("A", "TIMBER WOLF", "PRIMARY", "AC/20", "[RT] AC/20  (8)", "7+", "58%",
                          "SRM 6", "[LT] SRM 6  (9)", "7+", "58%", "MEDIUM LASER", "LA · Energy", "7+", "58%",
                          "MEDIUM LASER", "RA · Energy", "7+", "58%"), rows(wolf));
                    assertEquals(REORDER_HINT, line(wolf, "card-foot"));
                    assertEquals(List.of("B", "BATTLEMASTER", "SET PRIMARY", "LRM 20", "[LT] LRM 20  (11)", "8+",
                          "42%"), rows(master));
                    assertEquals(REORDER_HINT, line(master, "card-foot"));
                    assertEquals(300, wolf.getWidth(), .01f, "an expanded card is 300 wide at 1920");
                    assertPlaced(hud, layer, view, panels(hud, PANELS_05));
                    String shot = tactical ? "07-tactical-view.jpg" : "05-weapon-declaration.jpg";
                    String name = tactical ? "cards-07" : "cards-05";
                    capture(hud, name, shot, new Crop("wolf", wolf, tactical ? 764 : 1005, tactical ? 92 : 40),
                          new Crop("master", master, tactical ? 945 : 693, tactical ? 370 : 89),
                          new Crop("area", area(hud, tactical ? 560 : 560, tactical ? 60 : 0, 860, 600),
                                tactical ? 560 : 560, tactical ? 60 : 0)).dispose();
                    layer.dispose();
                }

                // Shot 06: three targets; only the focused Timber Wolf's card lists its attacks.
                Layer layer = new Layer(hud, mock(GpuBoardSource.class), board.camera);
                frame(board, false, atlasHex, 118, 980, 850);
                GpuHud.HudView view = draw(hud, board, overlay, layer, firing(scene, declaring, shot06()),
                      panels(hud, PANELS_05));
                Table wolf = layer.card(TIMBER_WOLF);
                assertEquals(List.of("A", "TIMBER WOLF", "PRIMARY", "SRM 6", "[LT] SRM 6  (9)", "7+", "58%",
                      "MEDIUM LASER", "LA · Energy", "7+", "58%", "MEDIUM LASER", "RA · Energy", "7+", "58%"),
                      rows(wolf));
                assertEquals(List.of("B", "BATTLEMASTER", "SET PRIMARY", "1 attack assigned", "Show attacks"),
                      rows(layer.card(BATTLEMASTER)));
                assertEquals(List.of("C", "KING CRAB", "SET PRIMARY", "1 attack assigned", "Show attacks"),
                      rows(layer.card(KING_CRAB)));
                assertEquals(260, layer.card(KING_CRAB).getWidth(), .01f, "a collapsed card is 260 wide");
                assertPlaced(hud, layer, view, panels(hud, PANELS_05));
                capture(hud, "cards-06", "06-three-targets.jpg", new Crop("wolf", wolf, 869, 76),
                      new Crop("crab", layer.card(KING_CRAB), 598, 90),
                      new Crop("master", layer.card(BATTLEMASTER), 712, 306),
                      new Crop("area", area(hud, 520, 40, 760, 560), 520, 40)).dispose();
                layer.dispose();

                // Shot 15: 1280 x 720, where an expanded card is 270 wide.
                hud.size(1280, 720);
                layer = new Layer(hud, mock(GpuBoardSource.class), board.camera);
                frame(board, false, atlasHex, 79, 615, 360 + 530);
                view = draw(hud, board, overlay, layer, firing(scene, declaring, shot05()),
                      panels(hud, PANELS_15));
                assertEquals(270, layer.card(TIMBER_WOLF).getWidth(), .01f, "270 wide at W <= 1350");
                assertPlaced(hud, layer, view, panels(hud, PANELS_15));
                capture(hud, "cards-15", "15-compact-1280x720.jpg",
                      new Crop("wolf", layer.card(TIMBER_WOLF), 343, 172),
                      new Crop("master", layer.card(BATTLEMASTER), 424, 8),
                      new Crop("area", area(hud, 300, 0, 720, 480), 300, 0)).dispose();
                layer.dispose();
            } finally {
                overlay.dispose();
                board.dispose();
            }
        });
    }

    /**
     * What the controls post (H18, H13, H28, H37, H30, H25, H29): the remove mark, "Set primary", a click on the card
     * (which assigns the armed weapon once), a row's right-click menu, the ammunition select by carrier and number,
     * "Show attacks", which keeps its card open, and a row dragged by its grip onto another's place, in one move once
     * it lands.
     */
    @Test
    void theControlsPostTheirCommands() {
        GpuHudTestStage.run(hud -> {
            GpuBoardSource source = mock(GpuBoardSource.class);
            GpuFireOrders fire = mock(GpuFireOrders.class);
            when(source.fire()).thenReturn(fire);
            Layer layer = new Layer(hud, source, null);
            GpuHud.HudView view = view(Map.of(ATLAS, new Rectangle(900, 200, 60, 110), TIMBER_WOLF,
                  new Rectangle(1100, 520, 60, 110), BATTLEMASTER, new Rectangle(650, 560, 60, 110), KING_CRAB,
                  new Rectangle(400, 300, 60, 110)));
            layer.update(hud, firing(shot05()), view, List.of());
            hud.draw();
            Table wolf = layer.card(TIMBER_WOLF);
            Table master = layer.card(BATTLEMASTER);

            UiButton remove = wolf.findActor("card-remove-" + LASER_LA);
            click(hud, remove);
            verify(fire).remove(LASER_LA);
            click(hud, master.findActor("card-primary"));
            verify(fire).setPrimary(TargetKey.unit(BATTLEMASTER));
            click(hud, wolf.findActor("card-clear-all"));
            verify(fire).removeTarget(TargetKey.unit(TIMBER_WOLF));
            verify(fire, never()).focusTarget(any(TargetKey.class));

            // A click on the card focuses its enemy and assigns the armed weapon there once (H28, H16).
            layer.state.armedWeapon = REAR;
            click(hud, master.findActor("card-name"));
            verify(fire).focusTarget(TargetKey.unit(BATTLEMASTER));
            verify(fire).assign(REAR, TargetKey.unit(BATTLEMASTER));
            assertEquals(-1, layer.state.armedWeapon);

            // A row's right click opens the queued attack's menu (H37); "Fire later" moves it.
            press(hud, wolf.findActor("card-row-2"), Input.Buttons.RIGHT);
            assertTrue(layer.popover().isVisible());
            List<String> menu = new ArrayList<>();
            collect(layer.popover(), menu);
            assertTrue(menu.containsAll(List.of("SRM 6", "Fire earlier", "Fire later", "Remove attack")),
                  "the queued attack's menu: " + menu);
            click(hud, find(layer.popover(), "Fire later"));
            verify(fire).move(SRM, 1);

            // H30: the select lists the AC/20's bins and loads the one picked, by carrier and number.
            click(hud, wolf.findActor("card-ammo-" + AC20));
            List<UiButton> bins = items(layer.popover());
            assertEquals(List.of("[RT] AC/20  (8)", "[RT] AC/20 Armor-Piercing  (4)"),
                  bins.stream().map(item -> item.getText().toString()).toList());
            click(hud, bins.get(1));
            verify(fire).setAmmo(AC20, new GpuUnitRecord.AmmoChoice(ATLAS, 12, "[RT] AC/20 Armor-Piercing  (4)"));

            // H29: the left arm's laser dragged onto the first row takes its place, in one move once it lands.
            drag(hud, wolf.findActor("card-row-3"), wolf.findActor("card-row-1"));
            verify(fire, never()).move(LASER_LA, -2);
            UiTestStage.settle(hud.stage);
            verify(fire).move(LASER_LA, -2);
            verify(fire, never()).focusTarget(TargetKey.unit(TIMBER_WOLF));

            // H25: with three targets the others collapse; "Show attacks" opens the King Crab's card and focuses it.
            clearInvocations(fire);
            layer.update(hud, firing(shot06()), view, List.of());
            hud.draw();
            Table crab = layer.card(KING_CRAB);
            assertNull(crab.findActor("card-row-5"), "collapsed");
            click(hud, crab.findActor("card-clear-all"));
            verify(fire).removeTarget(TargetKey.unit(KING_CRAB));
            click(hud, crab.findActor("card-show"));
            verify(fire).focusTarget(TargetKey.unit(KING_CRAB));
            verify(fire, never()).assign(anyInt(), any(TargetKey.class));
            layer.update(hud, firing(shot06()), view, List.of());
            hud.draw();
            assertNotNull(layer.card(KING_CRAB).findActor("card-row-5"), "opened while the Timber Wolf has the focus");
            assertNull(layer.card(BATTLEMASTER).findActor("card-row-4"));
            hud.capture("cards-controls").dispose();
            layer.dispose();
        });
    }

    /**
     * H29 over the board of shot 05: a row dragged and then cancelled (the Esc chain's first step) goes home without a
     * move. The AC/20's grip dragged a row and a half down lifts its row, which floats over the Timber Wolf's card
     * under the pointer with its shadow, while the SRM's row rises into its place and the slot opens below it (written
     * to cards-drag.png, beside the shot's card); the drop lands it there in one move.
     */
    @Test
    void aDraggedRowFloatsOverItsCardWhileTheOthersMakeRoom() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            try {
                GpuBoardSource source = mock(GpuBoardSource.class);
                GpuFireOrders fire = mock(GpuFireOrders.class);
                when(source.fire()).thenReturn(fire);
                Layer layer = new Layer(hud, source, board.camera);
                GpuBattleStatus.Snapshot declaring = GpuBoardOverlaySmokeTest.status(GamePhase.FIRING, ATLAS);
                frame(board, false, unit(scene, ATLAS).location().coords(), 118, 925, 790);
                GpuBoardSource.Frame orders = firing(scene, declaring, shot05());
                draw(hud, board, overlay, layer, orders, panels(hud, PANELS_05));
                Table wolf = layer.card(TIMBER_WOLF);
                Rectangle srm = GpuHudTestStage.bounds(wolf.findActor("card-row-2"));
                // The right arm laser's row held by its middle (a row drags from any part of it, as the .or does).
                Vector2 home = hold(hud, wolf.findActor("card-row-4"), 60);
                assertTrue(layer.cards.cancelDrag(), "Esc's first step ends the drag");
                assertFalse(layer.cards.cancelDrag());
                release(hud, home);
                UiTestStage.settle(hud.stage);
                verify(fire, never()).move(anyInt(), anyInt());

                // 70 units down from the grip's centre: past the SRM row's middle, short of the next one's.
                Vector2 drop = hold(hud, wolf.findActor("card-grip-" + AC20), -70);
                for (int frame = 0; frame < 24; frame++) {
                    hud.stage.act(1 / 60f);
                }
                draw(hud, board, overlay, layer, orders, panels(hud, PANELS_05));
                Rectangle risen = GpuHudTestStage.bounds(wolf.findActor("card-row-2"));
                assertEquals(srm.y + 48, risen.y, .5f, "the SRM's row rose into the AC/20's place");
                capture(hud, "cards-drag", "05-weapon-declaration.jpg", new Crop("wolf", wolf, 1005, 40)).dispose();
                release(hud, drop);
                UiTestStage.settle(hud.stage);
                verify(fire).move(AC20, 1);
                layer.dispose();
            } finally {
                overlay.dispose();
                board.dispose();
            }
        });
    }

    /**
     * Plan Q1, H29: a pressed grip takes the keyboard focus and shows its mint ring; Alt+Down and Alt+Up fly the row
     * through the card's list and post the fire orders' move once it lands, and are consumed, the key without Alt is
     * not; when the new order arrives, the grip of the same attack keeps the focus and the letters stay.
     */
    @Test
    void aFocusedGripReordersOnAltUpAndDownAndKeepsTheFocus() {
        GpuHudTestStage.run(hud -> {
            GpuBoardSource source = mock(GpuBoardSource.class);
            GpuFireOrders fire = mock(GpuFireOrders.class);
            when(source.fire()).thenReturn(fire);
            Layer layer = new Layer(hud, source, null);
            GpuHud.HudView view = view(Map.of(ATLAS, new Rectangle(900, 200, 60, 110), TIMBER_WOLF,
                  new Rectangle(1100, 520, 60, 110), BATTLEMASTER, new Rectangle(650, 560, 60, 110)));
            layer.update(hud, firing(shot05()), view, List.of());
            hud.draw();
            Actor grip = layer.card(TIMBER_WOLF).findActor("card-grip-" + SRM);
            assertEquals(Messages.getString("GpuBoard.hud.cards.gripTip"), tooltip(grip));
            click(hud, grip);
            assertSame(grip, hud.stage.getKeyboardFocus(), "a pressed grip holds the keyboard focus");
            hud.draw();
            Pixmap image = hud.capture("cards-grip-focus");
            try {
                Rectangle box = GpuHudTestStage.bounds(grip);
                assertTrue(mint(image, Math.round(box.x - 1), Math.round(box.y + box.height / 2)),
                      "the mint ring left of the grip");
                assertTrue(mint(image, Math.round(box.x + box.width / 2), Math.round(box.y + box.height + 1)),
                      "and above it");
            } finally {
                image.dispose();
            }

            assertTrue(key(hud, Input.Keys.DOWN, true), "Alt+Down is the grip's");
            verify(fire, never()).move(anyInt(), anyInt());
            UiTestStage.settle(hud.stage);
            verify(fire).move(SRM, 1);
            assertSame(grip, hud.stage.getKeyboardFocus(), "the grip keeps the focus while its row flies");

            // The orders come back with the SRM after the left arm's laser: its grip, in row 03 now, keeps the focus.
            layer.update(hud, firing(swapped(shot05(), 1, 2)), view, List.of());
            hud.draw();
            Table wolf = layer.card(TIMBER_WOLF);
            Actor moved = wolf.findActor("card-grip-" + SRM);
            assertSame(moved, hud.stage.getKeyboardFocus(), "the attack's grip keeps the focus");
            assertEquals("SRM 6", rows(wolf.findActor("card-row-3")).getFirst(), "the third attack in fire order");
            assertEquals(List.of("A", "TIMBER WOLF"), rows(wolf).subList(0, 2), "letters never follow the order");
            assertEquals("B", rows(layer.card(BATTLEMASTER)).getFirst());
            assertTrue(key(hud, Input.Keys.UP, true), "never forwarded as a called shot");
            UiTestStage.settle(hud.stage);
            verify(fire).move(SRM, -1);
            assertFalse(key(hud, Input.Keys.UP, false), "Up without Alt goes on to the HUD's keys");
            UiTestStage.settle(hud.stage);
            verify(fire, times(2)).move(anyInt(), anyInt());
            layer.dispose();
        });
    }

    /**
     * Over a real FiringDisplay (E3b): the right arm's laser moved first with Alt+Up on its grip, then the AC/20 dropped
     * back on the first row; the display's queue follows, the Archer stays A and the Crab B.
     */
    @Test
    void reorderingThroughTheFiringDisplayKeepsTheLetters() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            int archer = firing.ahead.getId();
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            GpuHudTestStage.run(hud -> {
                GpuBoardSource source = firing.board.source;
                Layer layer = new Layer(hud, source, null);
                GpuHud.HudView view = view(Map.of(firing.attacker.getId(), new Rectangle(900, 200, 60, 110), archer,
                      new Rectangle(1150, 560, 60, 110), crab.getId(), new Rectangle(650, 600, 60, 110)));
                source.fire().assign(cannon, TargetKey.unit(archer));
                source.fire().assign(right, TargetKey.unit(archer));
                source.fire().assign(left, TargetKey.unit(crab.getId()));
                show(hud, layer, GpuWeaponsPanelSmokeTest.settled(firing), view);
                assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@42", "Medium Laser LA@44"),
                      GpuFiringFixture.queue(firing));
                assertEquals(List.of("A", "ARCHER ARC-2R"), rows(layer.card(archer)).subList(0, 2));
                assertEquals(List.of("B", "CRAB CRB-20"), rows(layer.card(crab.getId())).subList(0, 2));

                Actor grip = layer.card(archer).findActor("card-grip-" + right);
                click(hud, grip);
                assertTrue(key(hud, Input.Keys.UP, true));
                UiTestStage.settle(hud.stage);
                show(hud, layer, GpuWeaponsPanelSmokeTest.settled(firing), view);
                assertEquals(List.of("Medium Laser RA@42", "AC/20 RT@42", "Medium Laser LA@44"),
                      GpuFiringFixture.queue(firing));
                Table card = layer.card(archer);
                assertEquals("MEDIUM LASER", rows(card.findActor("card-row-1")).getFirst());
                assertSame(card.findActor("card-grip-" + right), hud.stage.getKeyboardFocus());
                assertEquals(List.of("A", "ARCHER ARC-2R"), rows(card).subList(0, 2), "the Archer stays A");
                assertEquals(List.of("B", "CRAB CRB-20"), rows(layer.card(crab.getId())).subList(0, 2));

                drag(hud, card.findActor("card-row-2"), card.findActor("card-row-1"));
                UiTestStage.settle(hud.stage);
                show(hud, layer, GpuWeaponsPanelSmokeTest.settled(firing), view);
                assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@42", "Medium Laser LA@44"),
                      GpuFiringFixture.queue(firing));
                assertEquals(List.of("A", "ARCHER ARC-2R"), rows(layer.card(archer)).subList(0, 2));
                hud.capture("cards-firing-display").dispose();
                layer.dispose();
            });
        }
    }

    /**
     * A terrain target with an attack has its card against the anchor the view gives its hex, with its full name
     * wrapped in the header; a click on the card focuses the hex by its key.
     */
    @Test
    void aHexTargetsCardStandsAtItsHex() {
        GpuHudTestStage.run(hud -> {
            GpuBoardSource source = mock(GpuBoardSource.class);
            GpuFireOrders orders = mock(GpuFireOrders.class);
            when(source.fire()).thenReturn(orders);
            Layer layer = new Layer(hud, source, null);
            TargetKey building = new TargetKey(Targetable.TYPE_BUILDING, 20007);
            String targetName = "Hex 0803 of Medium Building Building #20007 (Collapse)";
            GpuFireOrders.Snapshot fire = orders(List.of(target(TIMBER_WOLF, 'A', "Timber Wolf", true, 0),
                        new GpuFireOrders.Target(building, 'B', targetName, false, 1, true, new Coords(7, 2))),
                  List.of(attack(AC20, TIMBER_WOLF, "AC/20", "RT", "Ballistic", "[RT] AC/20  (8)", 8, 7, 58.3),
                        new GpuFireOrders.Attack(LRM, building, "LRM 20", "LT", "Missile", "[LT] LRM 20  (11)", 11, 2,
                              100, "")));
            Map<Integer, Rectangle> units = Map.of(ATLAS, new Rectangle(900, 200, 60, 110), TIMBER_WOLF,
                  new Rectangle(1100, 520, 60, 110));
            Map<Integer, Vector2> heads = new HashMap<>();
            units.forEach((id, rect) -> heads.put(id, new Vector2(rect.x + rect.width / 2, rect.y + rect.height)));
            GpuHud.HudView view = new GpuHud.HudView(false, false, units, heads, Map.of(), null, Entity.NONE, 118,
                  Map.of(building, new Vector2(500, 500)));
            layer.update(hud, firing(fire), view, List.of());
            hud.draw();
            Rectangle card = layer.cards.placed().get(building);
            assertNotNull(card, "The hex's card is placed");
            assertEquals(500 - card.width / 2, card.x, 1, "centred over the hex's anchor");
            assertEquals(500 + 26, card.y, 1, "26 above it, as above a head");
            assertEquals(List.of("B", UiTheme.upper(targetName)), rows(layer.card(building.id())).subList(0, 2));
            Label name = layer.card(building.id()).findActor("card-name");
            assertTrue(name.getGlyphLayout().height > name.getStyle().font.getLineHeight() * name.getFontScaleY());
            assertTrue(name.getGlyphLayout().width <= name.getWidth(), "the full name fits inside the header");
            hud.capture("cards-hex").dispose();
            click(hud, name);
            verify(orders).focusTarget(building);
            layer.dispose();
        });
    }

    /**
     * The cards follow the view: none while no declaration is active; a target the view does not draw (behind the
     * camera) has no card and no rectangle; one whose head alone is known is placed against the 40 x 60 area below
     * the head; the rectangles handed to the labels are the cards as drawn, inside the window.
     */
    @Test
    void cardsFollowTheViewAndHandTheirRectanglesToTheLabels() {
        GpuHudTestStage.run(hud -> {
            Layer layer = new Layer(hud, mock(GpuBoardSource.class), null);
            Map<Integer, Rectangle> units = new HashMap<>(Map.of(ATLAS, new Rectangle(900, 200, 60, 110), TIMBER_WOLF,
                  new Rectangle(1100, 520, 60, 110)));
            Map<Integer, Vector2> heads = new HashMap<>();
            units.forEach((id, rect) -> heads.put(id, new Vector2(rect.x + rect.width / 2, rect.y + rect.height)));
            // The BattleMaster's head only: its card sits 26 above the area below the head.
            heads.put(BATTLEMASTER, new Vector2(500, 500));
            GpuHud.HudView view = new GpuHud.HudView(false, false, units, heads, Map.of(), null, Entity.NONE, 118);
            layer.update(hud, firing(shot05()), view, List.of());
            hud.draw();
            Map<TargetKey, Rectangle> placed = layer.cards.placed();
            assertEquals(List.of(TargetKey.unit(TIMBER_WOLF), TargetKey.unit(BATTLEMASTER)),
                  List.copyOf(placed.keySet()));
            Rectangle master = placed.get(TargetKey.unit(BATTLEMASTER));
            assertEquals(500 - master.width / 2, master.x, 1, "centred over the head");
            assertEquals(500 + 26, master.y, 1, "26 above the head");
            for (Map.Entry<TargetKey, Rectangle> entry : placed.entrySet()) {
                assertEquals(GpuHudTestStage.bounds(layer.card(entry.getKey().id())), entry.getValue());
            }

            // A HUD panel where that card would go: the card keeps 6 units clear of it (overlay.js refreshHudRects).
            Rectangle panel = new Rectangle(300, 520, 400, 150);
            layer.update(hud, firing(shot05()), view, List.of(panel));
            Rectangle moved = layer.cards.placed().get(TargetKey.unit(BATTLEMASTER));
            assertFalse(overlaps(moved, new Rectangle(panel.x - 6, panel.y - 6, panel.width + 12, panel.height + 12)),
                  moved + " keeps 6 clear of the panel " + panel);

            heads.remove(BATTLEMASTER);
            layer.update(hud, firing(shot05()), new GpuHud.HudView(false, false, units, heads, Map.of(), null,
                  Entity.NONE, 118), List.of());
            assertFalse(layer.card(BATTLEMASTER).isVisible(), "a target behind the camera has no card");
            assertEquals(List.of(TargetKey.unit(TIMBER_WOLF)), List.copyOf(layer.cards.placed().keySet()));

            layer.update(hud, firing(GpuFireOrders.Snapshot.EMPTY), view, List.of());
            assertTrue(layer.cards.placed().isEmpty(), "no card outside a declaration");
            assertFalse(((Group) layer.cards.actor()).hasChildren());
            layer.dispose();
        });
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Shot 05's orders: four attacks on A (the Timber Wolf, primary and focused) and the LRM on B (the BattleMaster),
     * in fire order; the AC/20 selected. The weapons are the Atlas's rows with their bins (G10's fixture values).
     */
    private static GpuFireOrders.Snapshot shot05() {
        List<GpuFireOrders.Attack> attacks = List.of(
              attack(AC20, TIMBER_WOLF, "AC/20", "RT", "Ballistic", "[RT] AC/20  (8)", 8, 7, 58.3),
              attack(SRM, TIMBER_WOLF, "SRM 6", "LT", "Missile", "[LT] SRM 6  (9)", 9, 7, 58.3),
              attack(LASER_LA, TIMBER_WOLF, "Medium Laser", "LA", "Energy", "", -1, 7, 58.3),
              attack(LASER_RA, TIMBER_WOLF, "Medium Laser", "RA", "Energy", "", -1, 7, 58.3),
              attack(LRM, BATTLEMASTER, "LRM 20", "LT", "Missile", "[LT] LRM 20  (11)", 11, 8, 41.7));
        return orders(List.of(target(TIMBER_WOLF, 'A', "Timber Wolf", true, 0),
              target(BATTLEMASTER, 'B', "BattleMaster", false, 1)), attacks);
    }

    /** Shot 06: the AC/20 moved to C, the King Crab, at 9+; in fire order the primary's attacks, then B's, then C's. */
    private static GpuFireOrders.Snapshot shot06() {
        List<GpuFireOrders.Attack> attacks = List.of(
              attack(SRM, TIMBER_WOLF, "SRM 6", "LT", "Missile", "[LT] SRM 6  (9)", 9, 7, 58.3),
              attack(LASER_LA, TIMBER_WOLF, "Medium Laser", "LA", "Energy", "", -1, 7, 58.3),
              attack(LASER_RA, TIMBER_WOLF, "Medium Laser", "RA", "Energy", "", -1, 7, 58.3),
              attack(LRM, BATTLEMASTER, "LRM 20", "LT", "Missile", "[LT] LRM 20  (11)", 11, 8, 41.7),
              attack(AC20, KING_CRAB, "AC/20", "RT", "Ballistic", "[RT] AC/20  (8)", 8, 9, 27.8));
        return orders(List.of(target(TIMBER_WOLF, 'A', "Timber Wolf", true, 0),
              target(BATTLEMASTER, 'B', "BattleMaster", false, 1), target(KING_CRAB, 'C', "King Crab", false, 1)),
              attacks);
    }

    /** A unit target in the front arc. */
    private static GpuFireOrders.Target target(int id, char letter, String name, boolean primary, int modifier) {
        return new GpuFireOrders.Target(TargetKey.unit(id), letter, name, primary, modifier, true, null);
    }

    /** The orders with two attacks swapped in fire order, as the fire orders' move publishes them. */
    private static GpuFireOrders.Snapshot swapped(GpuFireOrders.Snapshot fire, int first, int second) {
        List<GpuFireOrders.Attack> attacks = new ArrayList<>(fire.attacks());
        attacks.set(first, fire.attacks().get(second));
        attacks.set(second, fire.attacks().get(first));
        return orders(fire.targets(), attacks);
    }

    private static GpuFireOrders.Snapshot orders(List<GpuFireOrders.Target> targets,
          List<GpuFireOrders.Attack> attacks) {
        List<GpuFireOrders.Badge> badges = List.of(new GpuFireOrders.Badge(KING_CRAB, 9, 27.78, ""),
              new GpuFireOrders.Badge(ENEMY_LOCUST, TargetRoll.IMPOSSIBLE, 0,
                    Messages.getString("WeaponAttackAction.OutOfArc")));
        return new GpuFireOrders.Snapshot(true, true, ATLAS,
              new GpuFireOrders.Focus(TargetKey.unit(TIMBER_WOLF), "Timber Wolf", null), AC20, weapons(), targets,
              attacks, 0, true, true, "", null, null, null, badges, null, Map.of(), 5, 0, null);
    }

    /** The Atlas's weapons with ammunition (the AC/20's and the SRM's two bins, the LRM's one) and its lasers. */
    private static List<GpuFireOrders.WeaponRow> weapons() {
        return List.of(weapon(AC20, "AC/20", List.of(bin(11, "[RT] AC/20  (8)"),
                    bin(12, "[RT] AC/20 Armor-Piercing  (4)"))),
              weapon(LRM, "LRM 20", List.of(bin(13, "[LT] LRM 20  (11)"))),
              weapon(SRM, "SRM 6", List.of(bin(14, "[LT] SRM 6  (9)"), bin(15, "[LT] SRM 6 Inferno  (6)"))),
              weapon(LASER_LA, "Medium Laser", List.of()), weapon(LASER_RA, "Medium Laser", List.of()),
              weapon(REAR, "Medium Laser", List.of()));
    }

    private static GpuFireOrders.WeaponRow weapon(int eqNum, String name, List<GpuUnitRecord.AmmoChoice> ammo) {
        return new GpuFireOrders.WeaponRow(eqNum, name, "", "", "", 0, "", ammo, ammo.isEmpty() ? -1 : 0, 0,
              TargetKey.unit(TIMBER_WOLF), 7, 58.3, "", true, false);
    }

    private static GpuUnitRecord.AmmoChoice bin(int eqNum, String label) {
        return new GpuUnitRecord.AmmoChoice(ATLAS, eqNum, label);
    }

    private static GpuFireOrders.Attack attack(int eqNum, int target, String weapon, String location, String kind,
          String ammo, int shots, int value, double odds) {
        return new GpuFireOrders.Attack(eqNum, TargetKey.unit(target), weapon, location, kind, ammo, shots, value,
              odds, "");
    }

    private static GpuBoardSource.Frame firing(GpuFireOrders.Snapshot fire) {
        return GpuHudFixtures.frame(GpuBoardOverlaySmokeTest.status(GamePhase.FIRING, ATLAS), panels(fire));
    }

    private static GpuBoardSource.Frame firing(BoardScene scene, GpuBattleStatus.Snapshot status,
          GpuFireOrders.Snapshot fire) {
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, panels(fire));
    }

    private static GpuHudData panels(GpuFireOrders.Snapshot fire) {
        return GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, fire, GpuPhysicalOptions.Snapshot.EMPTY,
              GpuUnitRecord.Snapshot.EMPTY);
    }

    /** A shot's HUD panels, given from the window's top as its CSS, as stage bounds. */
    private static List<Rectangle> panels(GpuHudTestStage hud, int[][] panels) {
        List<Rectangle> bounds = new ArrayList<>();
        for (int[] panel : panels) {
            bounds.add(new Rectangle(panel[0], hud.height() - panel[1] - panel[3], panel[2], panel[3]));
        }
        return bounds;
    }

    /** Synthetic view facts: each unit's rectangle, its head at the top centre. */
    private static GpuHud.HudView view(Map<Integer, Rectangle> units) {
        Map<Integer, Vector2> heads = new HashMap<>();
        units.forEach((id, rect) -> heads.put(id, new Vector2(rect.x + rect.width / 2, rect.y + rect.height)));
        return new GpuHud.HudView(false, false, units, heads, Map.of(), null, Entity.NONE, 118);
    }

    // ------------------------------------------------------------------ drawing and checks

    /**
     * Draws the board with the G7 overlay as GpuBattleView orders it (in the Tactical View on the bases), then the
     * labels layer with the view's facts of that draw, and returns them.
     */
    private static GpuHud.HudView draw(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          Layer layer, GpuBoardSource.Frame frame, List<Rectangle> panels) {
        boolean tactical = board.tactical();
        layer.state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
        overlay.update(frame, new GpuHud.HudView(tactical, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0),
              preferences(), layer.state);
        GpuFireOrders.Snapshot fire = frame.panels().fire();
        board.basesStatus = frame.status();
        board.draw(camera -> {
            overlay.renderGhost(camera, board.instances::get);
            overlay.renderMarks(camera, board.poses::get);
            overlay.render(camera);
        });
        GpuHud.HudView view = GpuBoardLabelsSmokeTest.view(board, board.anchors, tactical);
        layer.update(hud, frame, view, panels);
        hud.drawStage();
        return view;
    }

    /** One frame of the real source's orders over a plain background. */
    private static void show(GpuHudTestStage hud, Layer layer, GpuBoardSource.Frame frame, GpuHud.HudView view) {
        layer.update(hud, frame, view, List.of());
        hud.draw();
    }

    /**
     * GpuCardPlacement's promises, as drawn: every shown card 8 inside the window, clear of the panels grown by 6 and
     * of the other cards, and its rectangle the one the labels got.
     */
    private static void assertPlaced(GpuHudTestStage hud, Layer layer, GpuHud.HudView view, List<Rectangle> panels) {
        List<Rectangle> drawn = new ArrayList<>();
        for (Map.Entry<TargetKey, Rectangle> entry : layer.cards.placed().entrySet()) {
            Rectangle card = entry.getValue();
            assertNotNull(view.head(entry.getKey()));
            assertEquals(GpuHudTestStage.bounds(layer.card(entry.getKey().id())), card);
            assertTrue(card.x >= 8 - .5f && card.y >= 8 - .5f && card.x + card.width <= hud.width() - 8 + .5f
                  && card.y + card.height <= hud.height() - 8 + .5f, "inside the window: " + card);
            for (Rectangle panel : panels) {
                Rectangle grown = new Rectangle(panel.x - 6, panel.y - 6, panel.width + 12, panel.height + 12);
                assertFalse(overlaps(card, grown), card + " keeps clear of " + panel);
            }
            for (Rectangle other : drawn) {
                assertFalse(overlaps(card, other), card + " keeps clear of the card at " + other);
            }
            drawn.add(card);
        }
        assertFalse(drawn.isEmpty(), "cards shown");
    }

    private static boolean overlaps(Rectangle one, Rectangle other) {
        return Math.min(one.x + one.width, other.x + other.width) - Math.max(one.x, other.x) > .5f
              && Math.min(one.y + one.height, other.y + other.height) - Math.max(one.y, other.y) > .5f;
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

    private static boolean mint(Pixmap image, int x, int y) {
        Color color = new Color(image.getPixel(x, y));
        return Math.abs(color.r - UiTheme.MINT.r) < .12f && Math.abs(color.g - UiTheme.MINT.g) < .12f
              && Math.abs(color.b - UiTheme.MINT.b) < .12f;
    }

    /** The shown texts of a card's header and rows (not its footer's words), in drawing order. */
    private static List<String> rows(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (!actor.isVisible() || "card-footer".equals(actor.getName())) {
            return texts;
        }
        if (actor instanceof Label label && label.getText().length() > 0) {
            texts.add(label.getText().toString());
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(rows(child)));
        }
        return texts;
    }

    /** A paragraph's words as one line. */
    private static String line(Table card, String name) {
        List<String> words = new ArrayList<>();
        collect(card.findActor(name), words);
        return String.join(" ", words);
    }

    private static void collect(Actor actor, List<String> texts) {
        if (!actor.isVisible()) {
            return;
        }
        if (actor instanceof Label label) {
            if (!label.getText().isEmpty()) {
                texts.add(label.getText().toString());
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, texts));
        }
    }

    /** The text of the actor's hud tooltip. */
    private static String tooltip(Actor actor) {
        for (EventListener listener : actor.getListeners()) {
            if (listener instanceof TextTooltip tip) {
                return tip.getActor().getText().toString();
            }
        }
        return null;
    }

    /** The items of the open popover's list. */
    private static List<UiButton> items(Actor popover) {
        List<UiButton> items = new ArrayList<>();
        UiMenuList list = findList(popover);
        assertNotNull(list, "no open list");
        list.getChildren().forEach(child -> {
            if (child instanceof UiButton item) {
                items.add(item);
            }
        });
        return items;
    }

    private static UiMenuList findList(Actor actor) {
        if (actor instanceof UiMenuList list) {
            return list;
        } else if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                UiMenuList found = findList(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The open menu's item with this text. */
    private static UiButton find(Actor actor, String text) {
        if (actor instanceof UiButton item && item.getText().toString().equals(text)) {
            return item;
        } else if (actor instanceof Group group && !(actor instanceof UiButton)) {
            for (Actor child : group.getChildren()) {
                UiButton found = find(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void click(GpuHudTestStage hud, Actor actor) {
        press(hud, actor, Input.Buttons.LEFT);
    }

    /** A press and release of the button at the actor's centre through the stage, as the board view hands it on. */
    private static void press(GpuHudTestStage hud, Actor actor, int button) {
        assertNotNull(actor);
        Vector2 point = screen(hud, actor);
        hud.stage.touchDown((int) point.x, (int) point.y, 0, button);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, button);
    }

    /**
     * Presses an actor at its centre and drags it {@code dy} units up (down when negative) in small steps, holding it
     * there; returns that stage point.
     */
    private static Vector2 hold(GpuHudTestStage hud, Actor actor, float dy) {
        Vector2 from = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        Vector2 to = new Vector2(from.x, from.y + dy);
        Vector2 press = hud.stage.stageToScreenCoordinates(from.cpy());
        hud.stage.touchDown(Math.round(press.x), Math.round(press.y), 0, Input.Buttons.LEFT);
        for (int step = 1; step <= 7; step++) {
            Vector2 point = hud.stage.stageToScreenCoordinates(from.cpy().lerp(to, step / 7f));
            hud.stage.touchDragged(Math.round(point.x), Math.round(point.y), 0);
        }
        return to;
    }

    /** Releases the held pointer at a stage point. */
    private static void release(GpuHudTestStage hud, Vector2 point) {
        Vector2 screen = hud.stage.stageToScreenCoordinates(point.cpy());
        hud.stage.touchUp(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
    }

    /** A key press through the stage with Alt held or not, as the window reports it. */
    private static boolean key(GpuHudTestStage hud, int key, boolean alt) {
        Input input = Gdx.input;
        Gdx.input = mock(Input.class);
        try {
            when(Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn(alt);
            return hud.stage.keyDown(key);
        } finally {
            Gdx.input = input;
        }
    }

    /** A drag from the actor's left (where the grip is) onto the middle of {@code target}, in small steps. */
    private static void drag(GpuHudTestStage hud, Actor actor, Actor target) {
        Vector2 from = hud.stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(18, actor.getHeight() / 2)));
        Vector2 to = screen(hud, target);
        hud.stage.touchDown((int) from.x, (int) from.y, 0, Input.Buttons.LEFT);
        for (int step = 1; step <= 10; step++) {
            hud.stage.touchDragged((int) (from.x + (to.x - from.x) * step / 10),
                  (int) (from.y + (to.y - from.y) * step / 10), 0);
        }
        hud.stage.touchUp((int) to.x, (int) to.y, 0, Input.Buttons.LEFT);
    }

    private static Vector2 screen(GpuHudTestStage hud, Actor actor) {
        return hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }
}
