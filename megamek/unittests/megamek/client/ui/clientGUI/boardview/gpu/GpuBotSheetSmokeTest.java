/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorChange.Trait;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.Tactic;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCollapsible;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.BotStatus;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bot sheet (bot sheet plan, step 5) in the component harness, placed at the window's right as GpuHud places it:
 * its whole-bot scope, the Behavior and Targets pages showing what the bot reported, the commands its controls send,
 * its pick of strategic hexes on the board, and the waiting state before the bot's first report.
 */
@Tag("on-demand")
class GpuBotSheetSmokeTest {
    private static final int BOT = 3;

    private static final class Sheet {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuBots bots = mock(GpuBots.class);
        final GpuBotOrders orders = mock(GpuBotOrders.class);
        final GpuPlayers players = mock(GpuPlayers.class);
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuBotSheet sheet;
        final Table root;

        Sheet(GpuHudTestStage hud) {
            when(source.bots()).thenReturn(bots);
            when(source.botOrders()).thenReturn(orders);
            when(source.players()).thenReturn(players);
            sheet = new GpuBotSheet(hud.kit, source, state);
            root = (Table) sheet.actor();
            hud.window.addActor(root);
        }

        void show(GpuHudTestStage hud, GpuBots.Snapshot snapshot) {
            show(hud, snapshot, GpuPlayers.Snapshot.EMPTY);
        }

        void show(GpuHudTestStage hud, GpuBots.Snapshot snapshot, GpuPlayers.Snapshot players) {
            sheet.update(inputs(hud, snapshot, players));
            float width = Math.min(DesignTokens.shipped().size().get("dialog.sm"), hud.width() - 32);
            root.setBounds(hud.width() - 16 - width, 16, width, hud.height() - 72);
            root.validate();
        }
    }

    @Test
    void theSheetShowsTheBotsBehaviorAndSendsEachChange() {
        GpuHudTestStage.run(hud -> {
            hud.size(1360, 900);
            Sheet sheet = new Sheet(hud);
            GpuBots.Snapshot snapshot = snapshot(row(behavior(), BotStatus.NONE, false));
            sheet.state.toggleBot(BOT);
            sheet.show(hud, snapshot);
            verify(sheet.bots).setSheetOpen(true);

            assertEquals("Princess", text(sheet.root, "bot-sheet-title"));
            assertEquals(Messages.getString("GpuBoard.hud.bots.scope", 4), text(sheet.root, "bot-sheet-scope"));
            UiTabs tabs = sheet.root.findActor("bot-sheet-tabs");
            assertEquals(2, tabs.count(), "Behavior and Targets only");
            assertFalse(visible(sheet.root, "bot-sheet-waiting"));
            assertFalse(visible(sheet.root, "bot-sheet-sending"));
            UiFilterChip stay = sheet.root.findActor("bot-tactic-stay_at_range");
            assertTrue(stay.isChecked(), "the reported indices match Stay at Range");
            assertFalse(((UiFilterChip) sheet.root.findActor("bot-tactic-no_prisoners")).isChecked());
            assertNotNull(sheet.root.findActor("bot-hold"), "the bot fights on, so the sheet offers Hold");
            hud.draw();
            hud.assertLayout(sheet.root);
            UiTestStage.assertTexts(sheet.root);
            hud.capture("bot-sheet-behavior").dispose();
            ScrollPane scroll = sheet.root.findActor("bot-sheet-scroll");
            ((UiCollapsible) sheet.root.findActor("bot-customize")).open(true);
            sheet.root.validate();
            scroll.layout();
            scroll.setScrollPercentY(1);
            scroll.updateVisualScroll();
            hud.draw();
            hud.capture("bot-sheet-behavior-lower").dispose();
            scroll.setScrollPercentY(0);
            scroll.updateVisualScroll();

            click(sheet.root.findActor("bot-hold"));
            verify(sheet.orders).hold(BOT, true);
            click(sheet.root.findActor("bot-tactic-alpha_strike"));
            verify(sheet.orders).tactic(BOT, Tactic.ALPHA_STRIKE);
            UiSegmented retreat = sheet.root.findActor("bot-retreat");
            retreat.select(1);
            click(retreat);
            verify(sheet.orders).retreat(BOT, CardinalEdge.NEAREST);

            // A held bot offers Resume; a command on its way shows in the footer.
            BotStatus held = new BotStatus(true, Set.of(), Set.of(new Coords(4, 7)));
            sheet.show(hud, snapshot(row(behavior(), held, true)));
            assertNotNull(sheet.root.findActor("bot-resume"));
            assertTrue(visible(sheet.root, "bot-sheet-sending"));

            tabs.select(1);
            tabs.fire(Pools.obtain(ChangeEvent.class));
            sheet.show(hud, snapshot(row(behavior(), held, false)));
            assertNotNull(sheet.root.findActor("bot-priority-21"));
            assertNotNull(sheet.root.findActor("bot-hex-remove-4-7"));
            assertNotNull(sheet.root.findActor("bot-ignored-22"));
            hud.draw();
            hud.assertLayout(sheet.root);
            UiTestStage.assertTexts(sheet.root);
            hud.capture("bot-sheet-targets").dispose();

            click(sheet.root.findActor("bot-priority-remove-21"));
            verify(sheet.orders).priority(BOT, 21, 0);
            click(sheet.root.findActor("bot-hex-remove-4-7"));
            verify(sheet.orders).hex(BOT, new Coords(4, 7), false);
            click(sheet.root.findActor("bot-ignored-22"));
            verify(sheet.orders).ignore(BOT, Set.of(22), false);
            click(sheet.root.findActor("bot-ignore-clear"));
            verify(sheet.orders).clearIgnored(BOT);

            // Pick on board keeps the sheet open: its pick area follows the board's pick, with Done and Cancel.
            GpuBots.Snapshot targets = snapshot(row(behavior(), held, false));
            click(sheet.root.findActor("bot-hex-pick"));
            verify(sheet.orders).pickHexes(BOT, Messages.getString("GpuBoard.hud.bots.hexes.order", "Princess"));
            assertEquals(Dialog.BOTS, sheet.state.dialog, "the sheet stays open while the board picks");
            GpuPlayers.Pick pick = new GpuPlayers.Pick("Click hexes on the board", "");
            sheet.show(hud, targets, new GpuPlayers.Snapshot(List.of(), null, pick));
            assertTrue(visible(sheet.root, "bot-hex-picking"));
            assertNull(sheet.root.findActor("bot-hex-pick"), "the pick area shows the running pick");
            assertEquals(Messages.getString("GpuBoard.hud.bots.hexes.picking"), text(sheet.root, "bot-hex-pick-status"));
            pick = new GpuPlayers.Pick("Click hexes on the board", "1 hex(es) selected: 1709");
            sheet.show(hud, targets, new GpuPlayers.Snapshot(List.of(), null, pick));
            assertEquals("1 hex(es) selected: 1709", text(sheet.root, "bot-hex-pick-status"));
            hud.draw();
            hud.assertLayout(sheet.root);
            UiTestStage.assertTexts(sheet.root);
            hud.capture("bot-sheet-picking").dispose();
            click(sheet.root.findActor("bot-hex-pick-done"));
            verify(sheet.players).endPick(true);
            click(sheet.root.findActor("bot-hex-pick-cancel"));
            verify(sheet.players).endPick(false);
            sheet.show(hud, targets);
            assertEquals(Dialog.BOTS, sheet.state.dialog);
            assertEquals(1, tabs.getSelectedIndex(), "on the page it showed");
            assertNotNull(sheet.root.findActor("bot-hex-pick"), "the pick ended: Pick on board is back");
            assertNull(sheet.root.findActor("bot-hex-picking"));
            // The bot gone, the sheet closes and stops capturing.
            sheet.show(hud, GpuBots.Snapshot.EMPTY);
            assertEquals(Dialog.NONE, sheet.state.dialog);
            sheet.show(hud, GpuBots.Snapshot.EMPTY);
            verify(sheet.bots).setSheetOpen(false);
        });
    }

    @Test
    void beforeTheBotsFirstReportTheControlsWait() {
        GpuHudTestStage.run(hud -> {
            hud.size(1360, 900);
            Sheet sheet = new Sheet(hud);
            sheet.state.toggleBot(BOT);
            sheet.show(hud, snapshot(row(null, BotStatus.NONE, false)));
            assertTrue(visible(sheet.root, "bot-sheet-waiting"));
            assertTrue(((UiButton) sheet.root.findActor("bot-hold")).isDisabled());
            assertTrue(((UiFilterChip) sheet.root.findActor("bot-tactic-alpha_strike")).isDisabled());
            click(sheet.root.findActor("bot-hold"));
            verify(sheet.orders, never()).hold(anyInt(), anyBoolean());
            hud.draw();
            hud.assertLayout(sheet.root);
            hud.capture("bot-sheet-waiting").dispose();
        });
    }

    /**
     * In the whole HUD the key opens the sheet for the single bot at the window's right, under the utility bar and
     * inside the window and clear of the dock, without a modal backdrop: the board stays playable while it is open.
     */
    @Test
    void theHudOpensTheSheetFromItsKeyBesideTheBoard() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuBoardSource source = mock(GpuBoardSource.class);
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            when(source.players()).thenReturn(mock(GpuPlayers.class));
            when(source.los()).thenReturn(mock(GpuLosResult.class));
            when(source.bots()).thenReturn(mock(GpuBots.class));
            when(source.botOrders()).thenReturn(mock(GpuBotOrders.class));
            GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(),
                  mock(GpuBoardTuning.class), new GpuPlaybackHistory(new UnitPlayback()));
            try {
                GpuBoardSource.Frame frame = GpuHudFixtures.frame(GpuHudFixtures.status(1, GamePhase.MOVEMENT,
                      false, Entity.NONE, 0), GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY)
                      .withBots(snapshot(row(behavior(), BotStatus.NONE, false))));
                GpuBoardSource.UiPreferences preferences = GpuBoardSource.UiPreferences.capture();
                for (int[] size : List.of(new int[] { 1360, 900 }, new int[] { 1920, 1080 })) {
                    harness.size(size[0], size[1]);
                    float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                    hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density),
                          1 / density);
                    hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                    if (hud.state.dialog != Dialog.BOTS) {
                        assertTrue(hud.keyDown(Input.Keys.G, KeyEvent.VK_G,
                              InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
                        hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                    }
                    assertEquals(Dialog.BOTS, hud.state.dialog, "a single bot: the key opens its sheet");
                    hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                    ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                    hud.draw();
                    Actor sheet = hud.stage.getRoot().findActor("bot-sheet");
                    assertTrue(sheet.isVisible() && sheet.getParent().isVisible());
                    assertFalse(hud.isModal(), "the sheet leaves the board playable");
                    Rectangle area = GpuHudTestStage.bounds(sheet);
                    Rectangle bar = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("utility-plate"));
                    assertTrue(area.y + area.height <= bar.y + .5f, "under the utility bar");
                    assertTrue(area.x >= 0 && area.y >= 0 && area.x + area.width <= harness.width() + .5f,
                          "inside the window: " + area);
                    Rectangle dock = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("command-dock"));
                    assertTrue(dock.x + dock.width <= area.x, "the dock stays clear of the sheet: " + dock);
                    assertTrue(((UiButton) hud.stage.getRoot().findActor("utility-bots")).isChecked());
                    UiTestStage.assertTexts(sheet);
                    harness.capture("hud-bot-sheet-" + size[0]).dispose();
                }
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    // ---------------------------------------------------------------- the lower-left card

    private static final int CASPAR_BOT = 9;
    private static final int FIRE_LANCE = 5;
    private static final String FIRE_ORDERS = "Route to 1206 \u00b7 2 waypoints \u00b7 Wedge \u00b7 Escort";

    /**
     * Step 2 of the bot orders: a selected allied CASPAR lance fills the lower-left card (LANCE scope, its units with
     * armor, heat and state, the Orders band); a click on a unit row inspects that unit alone, whose card then names
     * its bot and lance, wears the UNIT scope and shows its own Orders band; the ✕ and Esc end the lance selection.
     */
    @Test
    void theLowerLeftCardShowsAnAlliedLanceThenOneOfItsUnits() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuBoardSource source = mock(GpuBoardSource.class);
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            when(source.players()).thenReturn(mock(GpuPlayers.class));
            when(source.los()).thenReturn(mock(GpuLosResult.class));
            when(source.bots()).thenReturn(mock(GpuBots.class));
            when(source.botOrders()).thenReturn(mock(GpuBotOrders.class));
            GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(),
                  mock(GpuBoardTuning.class), new GpuPlaybackHistory(new UnitPlayback()));
            try {
                GpuBoardSource.Frame frame = GpuHudFixtures.frame(alliedStatus(),
                      GpuHudFixtures.panels(GpuUnitRecord.Snapshot.EMPTY).withBots(alliedBots()));
                GpuBoardSource.UiPreferences preferences = GpuBoardSource.UiPreferences.capture();
                for (int[] size : List.of(new int[] { 1360, 900 }, new int[] { 1920, 1080 })) {
                    harness.size(size[0], size[1]);
                    float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                    hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density),
                          1 / density);
                    hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                    hud.state.selectLance(CASPAR_BOT, FIRE_LANCE);
                    draw(hud, frame, preferences);
                    Table card = hud.stage.getRoot().findActor("unit-card");
                    assertTrue(card.isVisible() && card.getParent().isVisible(), "the lance takes the left card");
                    assertEquals("FIRE LANCE", text(card, "lance-card-name"));
                    assertEquals("Kurita Regulars \u00b7 allied bot \u00b7 3 units", text(card, "lance-card-subtitle"));
                    assertNotNull(card.findActor("lance-card-scope"));
                    assertEquals(FIRE_ORDERS, text(card, "lance-card-orders-line"));
                    for (int id : List.of(21, 22, 23)) {
                        assertNotNull(card.findActor("lance-card-unit-" + id), "a row per unit: " + id);
                    }
                    assertTrue(GpuHudTestStage.bounds(card).width > 0);
                    UiTestStage.assertTexts(card);
                    harness.capture("hud-lance-card-" + size[0]).dispose();

                    // A unit row inspects that unit: the lance selection ends and the unit card names its scope.
                    press(hud, card.findActor("lance-card-unit-22"));
                    draw(hud, frame, preferences);
                    assertNull(hud.state.selectedLance(), "inspecting a unit ends the lance selection");
                    assertEquals(22, hud.state.inspected);
                    assertEquals(22, hud.state.ownCardUnit(), "an allied bot unit stays on the left card");
                    assertNull(card.findActor("lance-card-name"));
                    assertNotNull(card.findActor("unit-card-scope"));
                    assertEquals("Kurita Regulars \u00b7 Fire lance \u00b7 Lt. M. Okafor 4/5",
                          text(card, "unit-card-subtitle"));
                    assertEquals("ORDERS \u00b7 FOLLOWS FIRE LANCE", text(card, "unit-card-orders-caption"));
                    assertEquals(FIRE_ORDERS, text(card, "unit-card-orders-line"));
                    Actor other = hud.stage.getRoot().findActor("other-unit-card");
                    assertTrue(other == null || !other.isVisible(), "the right card stays free");
                    harness.capture("hud-allied-unit-card-" + size[0]).dispose();

                    // The lance card's ✕ ends the selection; the own card comes back.
                    hud.state.inspected = Entity.NONE;
                    hud.state.selectLance(CASPAR_BOT, FIRE_LANCE);
                    draw(hud, frame, preferences);
                    click(card.findActor("lance-card-close"));
                    draw(hud, frame, preferences);
                    assertNull(hud.state.selectedLance());
                    assertNull(card.findActor("lance-card-name"));
                    assertNull(card.findActor("unit-card-scope"), "an own unit's card has no bot scope");
                }
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    private static void draw(GpuHud hud, GpuBoardSource.Frame frame, GpuBoardSource.UiPreferences preferences) {
        hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
        hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
        ScreenUtils.clear(.42f, .5f, .3f, 1, true);
        hud.draw();
    }

    private static void press(GpuHud hud, Actor actor) {
        assertNotNull(actor);
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    /** The own roster's five units, and three units of the allied CASPAR bot: one hot and worn, one that moved. */
    private static GpuBattleStatus.Snapshot alliedStatus() {
        GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
        List<GpuBattleStatus.UnitStatus> units = new ArrayList<>(fixture.units().stream()
              .filter(unit -> unit.side() == GpuBattleStatus.Side.OWN).toList());
        units.add(ally(fixture.units().get(0), 21, 0, .91, true, false));
        units.add(ally(fixture.units().get(1), 22, 11, .45, true, false));
        units.add(ally(fixture.units().get(3), 23, 3, .96, false, true));
        return new GpuBattleStatus.Snapshot(fixture.round(), fixture.phase(), fixture.myTurn(),
              fixture.localPlayerId(), fixture.actorId(), fixture.turns(), fixture.turnIndex(), units, List.of(),
              false);
    }

    private static GpuBattleStatus.UnitStatus ally(GpuBattleStatus.UnitStatus unit, int id, int heat, double armor,
          boolean pending, boolean done) {
        return new GpuBattleStatus.UnitStatus(id, GpuBattleStatus.Side.ALLY, false, unit.name(), unit.chassis(),
              unit.model(), unit.tons(), unit.weightClass(), "Fire lance", unit.pilot(), unit.gunnery(),
              unit.piloting(), armor, unit.structure(), heat, unit.heatRgb(), unit.heatCapacity(), unit.walk(),
              unit.run(), unit.jump(), done ? "Walked" : "", done ? 4 : 0, done ? 4 : 0, unit.facing(), unit.tmm(),
              false, pending, done, false, unit.damageLevel(), unit.destroyedLocations(), "",
              new Coords(unit.position().getX() - 4, unit.position().getY() + 2), 0, unit.icon(), List.of(),
              unit.weightClassIndex(), 0, CASPAR_BOT, List.of());
    }

    /** The allied CASPAR bot "Kurita Regulars" with its Fire lance of 21 (the leader), 22 and 23 on a route. */
    private static GpuBots.Snapshot alliedBots() {
        GpuBots.Lance fire = new GpuBots.Lance(FIRE_LANCE, "Fire lance", List.of(21, 22, 23), 21, FIRE_ORDERS);
        Map<Integer, GpuBots.Order> orders = Map.of(21, new GpuBots.Order(FIRE_LANCE, FIRE_ORDERS, false, true),
              22, new GpuBots.Order(FIRE_LANCE, FIRE_ORDERS, true, false),
              23, new GpuBots.Order(FIRE_LANCE, FIRE_ORDERS, true, false));
        GpuBots.BotRow bot = new GpuBots.BotRow(CASPAR_BOT, "Kurita Regulars", AIType.CASPAR, 0xFF82E2CE, true, 3,
              false, behavior(), BotStatus.NONE, List.of(), List.of(), Set.of(), false, List.of(fire), orders);
        return new GpuBots.Snapshot(List.of(bot), List.of(), Map.of());
    }

    // ---------------------------------------------------------------- fixtures

    private static GpuBots.Behavior behavior() {
        Map<Trait, Integer> traits = new EnumMap<>(Trait.class);
        traits.put(Trait.BRAVERY, 8);
        traits.put(Trait.AGGRESSION, 3);
        traits.put(Trait.SELF_PRESERVATION, 7);
        traits.put(Trait.FALL_SHAME, 5);
        traits.put(Trait.MUTUAL_SUPPORT, 5);
        return new GpuBots.Behavior(null, CombatPosture.AUTO, traits, CardinalEdge.NONE, Map.of(21, 1, 23, 3),
              Set.of(22));
    }

    private static GpuBots.BotRow row(GpuBots.Behavior behavior, BotStatus status, boolean sending) {
        List<GpuBots.Enemy> enemies = List.of(new GpuBots.Enemy(21, "Atlas AS7-D", 7, false),
              new GpuBots.Enemy(22, "Locust LCT-1V", 7, false), new GpuBots.Enemy(23, "Marauder MAD-3R", 8, false),
              new GpuBots.Enemy(24, "Turret", 8, true));
        List<GpuBots.EnemyPlayer> players = List.of(new GpuBots.EnemyPlayer(7, "Opponent", 0xFFEC9189, false),
              new GpuBots.EnemyPlayer(8, "Raider", 0xFFE3B46A, true));
        return new GpuBots.BotRow(BOT, "Princess", AIType.PRINCESS, 0xFF82E2CE, true, 4, false, behavior, status,
              enemies, players, sending ? Set.of(GpuBots.Control.HOLD) : Set.of(), false);
    }

    private static GpuBots.Snapshot snapshot(GpuBots.BotRow row) {
        Map<Personality, BehaviorSettings> presets = new EnumMap<>(Personality.class);
        for (Personality personality : List.of(Personality.BALANCED, Personality.CAUTIOUS, Personality.SNIPER,
              Personality.BERSERKER)) {
            presets.put(personality, new BehaviorSettings());
        }
        return new GpuBots.Snapshot(List.of(row), List.copyOf(presets.keySet()), presets);
    }

    private static GpuHud.Inputs inputs(GpuHudTestStage hud, GpuBots.Snapshot bots) {
        return inputs(hud, bots, GpuPlayers.Snapshot.EMPTY);
    }

    private static GpuHud.Inputs inputs(GpuHudTestStage hud, GpuBots.Snapshot bots, GpuPlayers.Snapshot players) {
        GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, players).withBots(bots);
        GpuBoardSource.Frame frame = new GpuBoardSource.Frame(board(), List.of(), null, List.of(), "",
              null, 0, "", null, GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, panels);
        GpuHud.HudView view = new GpuHud.HudView(false, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);
        return new GpuHud.Inputs(frame, view, null, GpuBoardSource.UiPreferences.capture(),
              GpuHud.Metrics.of(hud.width(), hud.height()), List.of());
    }

    private static String text(Table root, String name) {
        Label label = root.findActor(name);
        return label.getText().toString();
    }

    private static boolean visible(Table root, String name) {
        Actor actor = root.findActor(name);
        return actor != null && actor.isVisible();
    }

    private static void click(Actor actor) {
        assertNotNull(actor);
        ChangeEvent event = Pools.obtain(ChangeEvent.class);
        actor.fire(event);
        Pools.free(event);
    }


    /** A flat 16 x 17 board. */
    private static BoardScene board() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 17; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 16, 17, tiles, List.of(), List.of(), -1, "MOVEMENT", List.of());
    }
}
