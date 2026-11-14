/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ALLY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.unitDisplay.HeatEffects;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The forces navigator (G2) in the component harness: its list and grid beside the hud-v3 shots 01-04, 08-11, 14
 * and 15, its layout at the three window sizes, and what its rows, tabs, search, grid controls and Next pending do.
 */
@Tag("on-demand")
class GpuForcesPanelSmokeTest {
    private static final GpuBattleStatus.Snapshot MOCK = GpuHudFixtures.status();
    private static final List<String> LANCES = List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf",
          "Hotel", "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra",
          "Tango", "Uniform", "Victor", "Whiskey", "X-ray", "Yankee");
    /** The prototype's panel heights around the forces at 1920 x 1080: the phase header with its ribbon, the card. */
    private static final float HEADER = 100;
    private static final float CARD = 222;
    /** The player id of an allied player, on the local player's team. */
    private static final int ALLIED_PLAYER = 7;

    /** A panel under test with the HUD state it reads, the units it selected and the menu it opened. */
    private static final class Panel {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final List<Integer> selected = new ArrayList<>();
        final GpuContextMenu menu = mock(GpuContextMenu.class);
        final GpuForcesPanel forces;
        final Table root;

        Panel(GpuHudTestStage hud) {
            // The open list, which these checks read; railFoldsTheListUntilItIsPinned unpins it.
            state.forcesPinned = true;
            forces = new GpuForcesPanel(hud.kit, mock(GpuBoardSource.class), state, menu, selected::add);
            root = (Table) forces.actor();
            hud.window.addActor(root);
        }

        /**
         * One frame of the HUD: the board view feeds the playback history, which presents the report's attacks (none
         * of their events waits), then the status goes through the state's focus rule, then the panel.
         */
        void update(GpuHudTestStage hud, GpuBoardSource.Frame frame) {
            state.history.accept(frame.timeline(), frame.scene(), frame.reports(), unit -> false);
            state.history.advance(0, ignored -> true);
            state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
            frame.status().units().stream().map(UnitStatus::icon).forEach(hud.sprites::add);
            forces.update(new GpuHud.Inputs(frame, GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences(),
                  GpuHud.Metrics.of(hud.width(), hud.height()), List.of()));
        }

        /** Lays the panel out as its slot does: the given width, its content height up to {@code bottom}, y down. */
        void place(GpuHudTestStage hud, float x, float top, float width, float bottom) {
            root.setWidth(width);
            float height = Math.min(root.getPrefHeight(), bottom - top);
            root.setBounds(x, hud.height() - top - height, width, height);
            root.validate();
        }

        <T extends Actor> T find(String name) {
            return root.findActor(name);
        }

        String line(int id) {
            GpuHudKit.UnitRow row = find("forces-unit-" + id);
            assertNotNull(row, "row of unit " + id);
            return row.line.getText().toString();
        }

        String foot() {
            Table foot = find("forces-footer");
            return ((Label) foot.getChildren().first()).getText().toString();
        }
    }

    @Test
    void listAndGridBesideTheMock() {
        GpuHudTestStage.run(hud -> {
            Panel panel = fresh(hud);
            // Shot 01: the initiative phase, no unit acts; the header has no ribbon yet (bottom 104).
            panel.update(hud, frame(status(GamePhase.INITIATIVE, false, Entity.NONE, -1,
                  own(unit -> copy(unit, unit.id(), unit.formation(), false, false, false, "", 0)))));
            panel.place(hud, 20, 116, 300, 1080 - 20);
            assertEquals("Ready", panel.line(1));
            assertEquals("Ready \u00B7 heat 4", panel.line(2));
            assertEquals("5 / 5 operational", panel.foot());
            assertTrue(panel.<Button>find("forces-next").isDisabled(), "Nothing is pending");
            shoot(hud, panel, "forces-01-initiative", "01-initiative.jpg", 20, 116);

            // Shot 02: the local movement turn with the Atlas acting.
            BoardScene.Command moveNext = next(true, () -> { });
            panel = fresh(hud);
            panel.update(hud, frame(MOCK, moveNext));
            panel.place(hud, 20, 132, 300, 1080 - 20 - CARD - 12);
            assertEquals("Acting now", panel.line(1));
            assertEquals("Pending", panel.line(2));
            assertEquals(UiTheme.MINT, panel.<GpuHudKit.UnitRow>find("forces-unit-1").line.getColor());
            assertTrue(panel.<GpuHudKit.UnitRow>find("forces-unit-1").isChecked(), "The acting row is selected");
            assertEquals("5 pending / 5", panel.foot());
            assertFalse(panel.<Button>find("forces-next").isDisabled());
            shoot(hud, panel, "forces-02-movement", "02-movement-fire-preview.jpg", 20, 132);

            // Shot 03: the Panther acts.
            panel = fresh(hud);
            panel.update(hud, frame(status(GamePhase.MOVEMENT, true, 4, 0,
                  own(unit -> copy(unit, unit.id(), unit.formation(), unit.id() == 4, true, false, "", 0))), moveNext));
            panel.place(hud, 20, 132, 300, 1080 - 20 - CARD - 12);
            assertEquals("Acting now", panel.line(4));
            assertEquals("Pending", panel.line(1));
            shoot(hud, panel, "forces-03-waypoint", "03-waypoint-route.jpg", 20, 132);

            // Shot 04: the opponent's turn after the Atlas walked; the Warhammer is up next. MegaMek disables its
            // next-unit command outside the local turn, so Next pending is disabled here, unlike the mock.
            panel = fresh(hud);
            panel.update(hud, frame(status(GamePhase.MOVEMENT, false, Entity.NONE, 1, own(unit -> unit.id() == 1
                  ? copy(unit, 1, unit.formation(), false, false, true, "Walked", 2)
                  : copy(unit, unit.id(), unit.formation(), false, true, false, "", 0))), next(false, () -> { })));
            panel.place(hud, 20, 132, 300, 1080 - 20 - CARD - 12);
            assertEquals("Moved \u00B7 Walked \u00B7 2 MP \u00B7 N", panel.line(1));
            assertEquals("Up next", panel.line(2));
            assertEquals("4 pending / 5", panel.foot());
            shoot(hud, panel, "forces-04-opponent", "04-opponent-turn.jpg", 20, 132);

            // Shot 08: the weapon playback lists the round's attacks and hits, else the move (C6).
            panel = fresh(hud);
            panel.update(hud, frame(status(GamePhase.FIRING_REPORT, false, Entity.NONE, -1,
                  own(GpuForcesPanelSmokeTest::played)), GpuFireOrders.Snapshot.EMPTY,
                  reports(GamePhase.FIRING_REPORT, attacks(1, 4, 2)), List.of()));
            panel.place(hud, 20, 116, 300, 1080 - 20);
            assertEquals("4 attacks \u00B7 2 hit", panel.line(1));
            assertEquals("held position", panel.line(2));
            assertEquals("Ran \u00B7 5 MP \u00B7 N", panel.line(3));
            assertEquals("5 / 5 operational", panel.foot());
            shoot(hud, panel, "forces-08-playback", "08-weapon-playback.jpg", 20, 116);

            // Shot 09: the local physical turn; the Atlas and the Panther have physical turns left. A turn says nothing
            // of an adjacent enemy, so unlike the mock's lines these name none.
            panel = fresh(hud);
            panel.update(hud, frame(status(GamePhase.PHYSICAL, true, 1, 0, own(unit -> {
                UnitStatus round = played(unit);
                return copy(round, unit.id(), unit.formation(), unit.id() == 1, unit.id() == 1 || unit.id() == 4,
                      false, round.moved(), round.mpUsed());
            })), next(true, () -> { })));
            panel.place(hud, 20, 116, 300, 1080 - 20);
            assertEquals("Acting now", panel.line(1));
            assertEquals("No physical attack \u00B7 held position", panel.line(2));
            assertEquals("No physical attack \u00B7 Ran \u00B7 5 MP \u00B7 N", panel.line(3));
            assertEquals("Pending", panel.line(4));
            assertEquals(UiTheme.AMBER, panel.<GpuHudKit.UnitRow>find("forces-unit-4").line.getColor());
            assertEquals("2 pending / 5", panel.foot());
            shoot(hud, panel, "forces-09-physical", "09-physical-attacks.jpg", 20, 116);

            // Shot 10: the round report adds the heat; a Mek whose heat has effects is amber with the flame.
            panel = fresh(hud);
            panel.update(hud, frame(status(GamePhase.END_REPORT, false, Entity.NONE, -1, own(unit -> heat(
                  played(unit), switch (unit.id()) {
                      case 1, 4 -> 4;
                      case 2 -> 6;
                      case 3 -> 15;
                      default -> 0;
                  }))), GpuFireOrders.Snapshot.EMPTY, reports(GamePhase.END_REPORT, Stream.of(attacks(1, 5, 2),
                  attacks(2, 2, 1), attacks(3, 5, 1), attacks(4, 2, 2)).flatMap(List::stream).toList()), List.of()));
            panel.place(hud, 20, 116, 300, 1080 - 20);
            assertEquals("5 attacks \u00B7 2 hit \u00B7 heat 4", panel.line(1));
            assertEquals("5 attacks \u00B7 1 hit \u00B7 heat 15", panel.line(3));
            assertEquals("Walked \u00B7 8 MP \u00B7 N", panel.line(5));
            assertEquals(UiTheme.AMBER, panel.<GpuHudKit.UnitRow>find("forces-unit-3").line.getColor());
            assertNotNull(panel.<GpuHudKit.UnitRow>find("forces-unit-3").right.getActor(), "The flame");
            assertNull(panel.<GpuHudKit.UnitRow>find("forces-unit-1").right.getActor(), "Heat 4 has no effects");
            shoot(hud, panel, "forces-10-report", "10-round-report.jpg", 20, 116);

            // Shot 11: the grid of shot 02, 630 units wide at 1920.
            panel = fresh(hud);
            panel.state.forcesGrid = true;
            panel.update(hud, frame(MOCK, moveNext));
            panel.place(hud, 20, 132, GpuHud.Metrics.of(1920, 1080).grid(), 1080 - 20 - CARD - 12);
            assertNotNull(panel.find("forces-tile-5"));
            assertNull(panel.find("forces-unit-5"), "The grid shows tiles, not rows");
            shoot(hud, panel, "forces-11-grid", "11-force-grid.jpg", 20, 132);

            // Shot 14: 100 v 100 in the grid; the list scrolls below the tenth lance.
            panel.update(hud, frame(large(), moveNext));
            panel.place(hud, 20, 132, GpuHud.Metrics.of(1920, 1080).grid(), 1080 - 20 - CARD - 12);
            assertEquals("100 pending / 100", panel.foot());
            assertTrue(panel.<ScrollPane>find("forces-list").isScrollY(), "The list scrolls");
            shoot(hud, panel, "forces-14-large", "14-large-battle-100v100.jpg", 20, 132);

            // Shot 15 at 1280 x 720: the firing phase with drafts; the list scrolls after three rows.
            hud.size(1280, 720);
            panel = fresh(hud);
            panel.update(hud, frame(status(GamePhase.FIRING, true, 1, 0, own(unit -> switch (unit.id()) {
                case 1 -> copy(unit, 1, unit.formation(), true, true, false, "Walked", 2);
                case 3 -> copy(unit, 3, unit.formation(), false, true, false, "Ran", 5);
                default -> copy(unit, unit.id(), unit.formation(), false, true, false, "", 0);
            })), fire(1, 5), GpuReportLog.Snapshot.EMPTY, List.of(next(true, () -> { }))));
            GpuHud.Metrics compact = GpuHud.Metrics.of(1280, 720);
            panel.place(hud, compact.gap(), 108, compact.forces(), 478 - 12);
            assertEquals("Acting now \u00B7 5 drafted \u00B7 Walked \u00B7 2 MP \u00B7 N", panel.line(1));
            assertEquals("Pending \u00B7 held position", panel.line(2));
            assertEquals("Pending \u00B7 Ran \u00B7 5 MP \u00B7 N", panel.line(3));
            shoot(hud, panel, "forces-15-compact", "15-compact-1280x720.jpg", 16, 108);
        });
    }

    @Test
    void layoutStaysInItsSlotAtTheThreeWindowSizes() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                float card = metrics.lowHeight() ? 226 : CARD;
                // The slot between the phase header and the unit card, y down; the panel is placed inside it.
                float top = metrics.gap() + HEADER + 12;
                float bottom = size[1] - metrics.gap() - card - 12;
                // y up, as the stage: the utility row at the right.
                Rectangle utilities = new Rectangle(size[0] - metrics.gap() - 420, size[1] - 18 - 56, 420, 56);
                for (boolean grid : new boolean[] { false, true }) {
                    panel.state.forcesGrid = grid;
                    panel.update(hud, frame(large(), next(true, () -> { })));
                    panel.place(hud, metrics.gap(), top, grid ? metrics.grid() : metrics.forces(), bottom);
                    hud.draw();
                    hud.capture("forces-layout-" + (grid ? "grid-" : "list-") + size[0] + "x" + size[1]).dispose();
                    hud.assertLayout(panel.root, utilities);
                    // Header, selects, tabs and footer fit the slot, so nothing is drawn over the header or the card.
                    assertTrue(panel.root.getMinHeight() <= bottom - top, "The panel needs "
                          + panel.root.getMinHeight() + " of the " + (bottom - top) + " units at " + size[0]);
                    assertTrue(panel.<ScrollPane>find("forces-list").isScrollY(), "100 units scroll at " + size[0]);
                }
            }
        });
    }

    @Test
    void railFoldsTheListUntilItIsPinned() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            panel.state.forcesPinned = false;
            AtomicInteger nextRuns = new AtomicInteger();
            GpuBoardSource.Frame frame = frame(MOCK, next(true, nextRuns::incrementAndGet));
            rail(hud, panel, frame);

            // Folded: the rail lists every unit as a sprite under the pending count, and no rows.
            assertTrue(panel.forces.folded(), "An unpinned panel folds");
            assertTrue(GpuBoardTestUi.shown(panel.find("forces-rail")));
            assertNull(panel.find("forces-unit-1"), "The rail shows no rows");
            assertEquals(List.of(1, 2, 3, 4, 5), railItems(panel.root));
            assertEquals("5", panel.<Label>find("forces-rail-count").getText().toString());

            // Its items select and its Next pending runs the phase's command, as the list's do.
            click(hud, panel.find("forces-rail-2"), Input.Buttons.LEFT);
            assertEquals(List.of(2), panel.selected);
            click(hud, panel.find("forces-rail-next"), Input.Buttons.LEFT);
            assertEquals(1, nextRuns.get());

            // The pointer on the rail unfolds it; it folds again a moment after the pointer left.
            Vector2 over = centre(hud, panel.find("forces-rail"));
            hud.stage.mouseMoved((int) over.x, (int) over.y);
            hud.stage.act(0);
            rail(hud, panel, frame);
            assertFalse(panel.forces.folded(), "The pointer on the panel unfolds it");
            hud.stage.mouseMoved(1900, 540);
            hud.stage.act(0);
            try {
                Thread.sleep(GpuForcesPanel.COLLAPSE_MILLIS + 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rail(hud, panel, frame);
            assertTrue(panel.forces.folded(), "It folds after the pointer left");

            // The chevron pins the list open; the header's pin unpins it.
            click(hud, panel.find("forces-expand"), Input.Buttons.LEFT);
            assertTrue(panel.state.forcesPinned);
            show(hud, panel, frame);
            assertFalse(panel.forces.folded());
            assertNotNull(panel.find("forces-unit-1"), "The pinned panel lists its rows");
            click(hud, panel.find("forces-pin"), Input.Buttons.LEFT);
            assertFalse(panel.state.forcesPinned);
        });
    }

    /** One frame at the left column's place as the rail, {@link GpuForcesPanel#RAIL_WIDTH} wide. */
    private static void rail(GpuHudTestStage hud, Panel panel, GpuBoardSource.Frame frame) {
        panel.update(hud, frame);
        panel.place(hud, 20, 132, panel.forces.folded() ? GpuForcesPanel.RAIL_WIDTH : 300, 1080 - 20);
        hud.draw();
    }

    /** The unit ids of the rail's items, in their order on the screen. */
    private static List<Integer> railItems(Actor actor) {
        List<Integer> ids = new ArrayList<>();
        String name = actor.getName();
        if (name != null && name.matches("forces-rail-\\d+")) {
            ids.add(Integer.parseInt(name.substring("forces-rail-".length())));
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> ids.addAll(railItems(child)));
        }
        return ids;
    }

    @Test
    void rowsTabsAndNextPendingUseTheHudsCommands() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            AtomicInteger nextRuns = new AtomicInteger();
            GpuBoardSource.Frame frame = frame(MOCK, next(true, nextRuns::incrementAndGet));
            show(hud, panel, frame);

            // A left click hands the unit to the HUD's selection rule; a right click opens its menu (C7, C13).
            click(hud, panel.find("forces-unit-2"), Input.Buttons.LEFT);
            assertEquals(List.of(2), panel.selected);
            click(hud, panel.find("forces-unit-3"), Input.Buttons.RIGHT);
            assertEquals(List.of(2), panel.selected, "A right click selects nothing");
            verify(panel.menu).open(eq(unit(MOCK, 3).position()), eq(3), anyFloat(), anyFloat());

            // A status update between press and release changes the rows in place and keeps the press.
            Vector2 press = centre(hud, panel.find("forces-unit-4"));
            hud.stage.touchDown((int) press.x, (int) press.y, 0, Input.Buttons.LEFT);
            show(hud, panel, frame(status(GamePhase.MOVEMENT, true, 1, 0, own(unit -> unit.id() == 3
                  ? copy(unit, 3, unit.formation(), false, false, true, "Ran", 5) : unit)), next(true, () -> { })));
            assertEquals("Moved \u00B7 Ran \u00B7 5 MP \u00B7 N", panel.line(3));
            hud.stage.touchUp((int) press.x, (int) press.y, 0, Input.Buttons.LEFT);
            assertEquals(List.of(2, 4), panel.selected);
            show(hud, panel, frame);

            // Next pending runs the phase's own next-unit command, and only while it and a pending unit exist.
            click(hud, panel.find("forces-next"), Input.Buttons.LEFT);
            assertEquals(1, nextRuns.get());
            show(hud, panel, frame(MOCK, next(false, nextRuns::incrementAndGet)));
            assertTrue(panel.<Button>find("forces-next").isDisabled());
            click(hud, panel.find("forces-next"), Input.Buttons.LEFT);
            assertEquals(1, nextRuns.get(), "A disabled next-unit command is not run");
            // Before movement and firing it is the prephase display's next-unit command.
            AtomicInteger prephaseRuns = new AtomicInteger();
            show(hud, panel, frame(status(GamePhase.PREMOVEMENT, true, 1, 0, MOCK.units()),
                  next("prephaseNext", true, prephaseRuns::incrementAndGet)));
            click(hud, panel.find("forces-next"), Input.Buttons.LEFT);
            assertEquals(1, prephaseRuns.get());

            // The contacts: grouped by formation, the sensor contact under "Unidentified", the menu hint in the foot.
            click(hud, panel.find("forces-tab-contacts"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            UiButton contacts = panel.find("forces-tab-contacts");
            assertTrue(contacts.isChecked());
            assertEquals("Contacts 5", contacts.getText().toString());
            assertNull(panel.find("forces-unit-1"));
            assertEquals("Unidentified \u00B7 sensor return", panel.line(GpuHudFixtures.CONTACT));
            assertEquals("Pending", panel.line(6), "An enemy with a movement turn left");
            assertNotNull(panel.find("forces-group-Unidentified"));
            assertNotNull(panel.find("forces-group-Opposition lance 1"));
            assertEquals("1 unidentified", panel.foot());
            hud.capture("forces-contacts").dispose();
            click(hud, panel.find("forces-unit-6"), Input.Buttons.LEFT);
            assertEquals(List.of(2, 4, 6), panel.selected, "An enemy row goes to the same rule, which inspects it");

            // Under double blind the enemy formations stay unnamed (plan C4).
            GpuBoardSource.Frame doubleBlind = frame(new GpuBattleStatus.Snapshot(3, GamePhase.MOVEMENT, true, 1, 1,
                  MOCK.turns(), 0, MOCK.units(), List.of(), true));
            show(hud, panel, doubleBlind);
            assertNull(panel.find("forces-group-Opposition lance 1"));
            assertNotNull(panel.find("forces-unit-6"));
            // Outside the movement phase an identified enemy reads as a visual contact.
            show(hud, panel, frame(status(GamePhase.FIRING, true, 1, 0, MOCK.units())));
            assertEquals("Visual", panel.line(6));

            // Allies are friendly and listed last, one group per allied player whatever their formations (C2).
            click(hud, panel.find("forces-tab-friendly"), Input.Buttons.LEFT);
            List<UnitStatus> units = new ArrayList<>(MOCK.units());
            units.add(side(copy(unit(MOCK, 5), 11, "Hotel lance", false, true, false, "", 0), ALLY, ALLIED_PLAYER));
            units.add(side(copy(unit(MOCK, 4), 12, "India lance", false, true, false, "", 0), ALLY, ALLIED_PLAYER));
            GpuBoardSource.Frame allies = frame(status(GamePhase.MOVEMENT, true, 1, 0, units), players());
            show(hud, panel, allies);
            assertEquals("Friendly 7", panel.<UiButton>find("forces-tab-friendly").getText().toString());
            assertEquals(List.of("Alpha lance", "Bravo lance", "Allies \u00B7 Kurita"), groups(panel));
            assertEquals(List.of(1, 2, 3, 4, 5, 11, 12), listed(panel, "forces-unit-"));
            assertEquals("5 pending / 5", panel.foot(), "The foot counts the local player's own units");
            hud.capture("forces-allies").dispose();

            // The search matches the group a unit is listed in: allies by their player, and no enemy by the formation
            // that double blind hides (C3, C4).
            search(hud, panel, allies, "kurita");
            assertEquals(List.of(11, 12), listed(panel, "forces-unit-"));
            click(hud, panel.find("forces-tab-contacts"), Input.Buttons.LEFT);
            search(hud, panel, doubleBlind, "opposition");
            assertEquals(List.of(), listed(panel, "forces-unit-"));
        });
    }

    @Test
    void searchGridAndCollapseChangeWhatIsListed() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            // The Atlas has moved, the Warhammer is prone, the Marauder runs hot and the Panther is hull down, with the
            // board label's severities (UnitStatusWords.statusWords).
            GpuBoardSource.Frame frame = frame(status(GamePhase.MOVEMENT, true, 2, 0, own(unit -> switch (unit.id()) {
                case 1 -> copy(unit, 1, unit.formation(), false, false, true, "Walked", 2);
                case 2 -> word(copy(unit, 2, unit.formation(), true, true, false, "", 0), "PRONE",
                      UnitStatusWords.Severity.CAUTION);
                case 3 -> heat(copy(unit, 3, unit.formation(), false, true, false, "", 0), 15);
                case 4 -> word(copy(unit, 4, unit.formation(), false, true, false, "", 0), "HULLDOWN",
                      UnitStatusWords.Severity.PRECAUTION);
                default -> copy(unit, unit.id(), unit.formation(), false, true, false, "", 0);
            })));
            show(hud, panel, frame);

            // The search field opens with the focus and matches the weight class too (C3).
            click(hud, panel.find("forces-search"), Input.Buttons.LEFT);
            TextField field = panel.find("forces-search-field");
            assertNotNull(field.getStage());
            assertSame(field, hud.stage.getKeyboardFocus());
            field.setText("light");
            show(hud, panel, frame);
            assertEquals(List.of(4, 5), listed(panel, "forces-unit-"));
            hud.capture("forces-search").dispose();
            field.setText("war");
            show(hud, panel, frame);
            assertEquals(List.of(2), listed(panel, "forces-unit-"));
            click(hud, panel.find("forces-search"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertNull(field.getStage(), "Closing the search removes the field");
            assertEquals(List.of(1, 2, 3, 4, 5), listed(panel, "forces-unit-"), "and its query");

            // A collapsed formation keeps its header and hides its rows.
            click(hud, panel.find("forces-group-Alpha lance"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of(4, 5), listed(panel, "forces-unit-"));
            click(hud, panel.find("forces-group-Alpha lance"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of(1, 2, 3, 4, 5), listed(panel, "forces-unit-"));

            // The grid's grouping select steps through formation, readiness and weight class (C8).
            panel.state.forcesGrid = true;
            show(hud, panel, frame);
            assertEquals(List.of("Alpha lance", "Bravo lance"), groups(panel));
            click(hud, panel.find("forces-grouping"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of("Acted", "Needs orders"), groups(panel));
            click(hud, panel.find("forces-grouping"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of("Assault", "Heavy", "Light"), groups(panel), "Heaviest first");
            hud.capture("forces-grid-weight").dispose();
            // A group follows its heaviest unit, not the class code: a small support vehicle's code (12) lies above
            // ASSAULT's (4), but it weighs less than a Locust.
            List<UnitStatus> withVehicle = new ArrayList<>(frame.status().units());
            withVehicle.add(0, weighs(unit(MOCK, 5), 13, 4.5, "Small Support Vehicle",
                  EntityWeightClass.WEIGHT_SMALL_SUPPORT));
            show(hud, panel, frame(status(GamePhase.MOVEMENT, true, 2, 0, withVehicle)));
            assertEquals(List.of("Assault", "Heavy", "Light", "Small Support Vehicle"), groups(panel));
            assertEquals(List.of(1, 2, 3, 4, 5, 13), listed(panel, "forces-tile-"));
            show(hud, panel, frame);
            // The filter: needs orders, acted, then damaged or impaired (heat effects count).
            click(hud, panel.find("forces-filter"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of(2, 3, 4, 5), listed(panel, "forces-tile-"));
            click(hud, panel.find("forces-filter"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of(1), listed(panel, "forces-tile-"));
            click(hud, panel.find("forces-filter"), Input.Buttons.LEFT);
            show(hud, panel, frame);
            assertEquals(List.of(2, 3), listed(panel, "forces-tile-"), "Prone impairs, hull down does not");
            click(hud, panel.find("forces-tile-3"), Input.Buttons.LEFT);
            assertEquals(List.of(3), panel.selected, "A tile goes to the HUD's selection rule too");
            // Rows show heat effects as a flame, and the move once the unit acted.
            panel.state.forcesGrid = false;
            show(hud, panel, frame);
            assertNotNull(panel.<GpuHudKit.UnitRow>find("forces-unit-3").right.getActor(), "Heat 15 has effects");
            assertNull(panel.<GpuHudKit.UnitRow>find("forces-unit-2").right.getActor(), "Heat 4 has none");
            assertEquals("Moved \u00B7 Walked \u00B7 2 MP \u00B7 N", panel.line(1));
            assertEquals(.72f, panel.<Actor>find("forces-unit-1").getColor().a, 1e-4, "An acted row fades");
        });
    }

    /** A new panel in the emptied window: each shot starts from a fresh HUD state, as at a phase start. */
    private static Panel fresh(GpuHudTestStage hud) {
        hud.window.clearChildren();
        return new Panel(hud);
    }

    /** Draws the panel, captures {name}.png and writes it beside the mock's same area. */
    private static void shoot(GpuHudTestStage hud, Panel panel, String name, String mock, int x, int y) {
        hud.draw();
        // In the window, and no row, tile or label wider than the panel.
        hud.assertLayout(panel.root);
        Pixmap image = hud.capture(name);
        try {
            hud.compare(name, image, panel.root, mock, x, y);
        } finally {
            image.dispose();
        }
    }

    /** One frame at the left column's place, drawn so that clicks find laid-out actors. */
    private static void show(GpuHudTestStage hud, Panel panel, GpuBoardSource.Frame frame) {
        panel.update(hud, frame);
        panel.place(hud, 20, 132, panel.state.forcesGrid ? GpuHud.Metrics.of(1920, 1080).grid() : 300, 1080 - 20);
        hud.draw();
    }

    /** Types {@code query} into the search field, which a click opens first if needed, and shows {@code frame}. */
    private static void search(GpuHudTestStage hud, Panel panel, GpuBoardSource.Frame frame, String query) {
        if (panel.find("forces-search-field") == null) {
            click(hud, panel.find("forces-search"), Input.Buttons.LEFT);
        }
        panel.<TextField>find("forces-search-field").setText(query);
        show(hud, panel, frame);
    }

    /** A press and release at the actor's centre through the stage, as the board view hands HUD input on. */
    private static void click(GpuHudTestStage hud, Actor actor, int button) {
        Vector2 point = centre(hud, actor);
        hud.stage.touchDown((int) point.x, (int) point.y, 0, button);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, button);
    }

    /** The actor's centre in screen coordinates. */
    private static Vector2 centre(GpuHudTestStage hud, Actor actor) {
        assertNotNull(actor);
        return hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }

    /** The unit ids of the shown rows or tiles, in their order on the screen. */
    private static List<Integer> listed(Panel panel, String prefix) {
        List<Integer> ids = new ArrayList<>();
        collect(panel.root, prefix, ids);
        return ids;
    }

    private static void collect(Actor actor, String prefix, List<Integer> ids) {
        if (actor.getName() != null && actor.getName().startsWith(prefix)) {
            ids.add(Integer.parseInt(actor.getName().substring(prefix.length())));
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, prefix, ids));
        }
    }

    /** The group headers' names, in their order on the screen. */
    private static List<String> groups(Panel panel) {
        List<String> names = new ArrayList<>();
        collectGroups(panel.root, names);
        return names;
    }

    private static void collectGroups(Actor actor, List<String> names) {
        if (actor.getName() != null && actor.getName().startsWith("forces-group-")) {
            names.add(actor.getName().substring("forces-group-".length()));
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collectGroups(child, names));
        }
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, BoardScene.Command... commands) {
        return frame(status, GpuFireOrders.Snapshot.EMPTY, GpuReportLog.Snapshot.EMPTY, List.of(commands));
    }

    /** A frame whose scene carries the phase commands and whose panels carry the fire orders. */
    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire,
          GpuReportLog.Snapshot reports, List<BoardScene.Command> commands) {
        return frame(status, reports, commands, GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, fire,
              GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
    }

    /** A frame whose panels carry the game's players and nothing else. */
    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuPlayers.Snapshot players) {
        return frame(status, GpuReportLog.Snapshot.EMPTY, List.of(), new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY,
              GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY,
              GpuUnitRecord.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY,
              GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE, players));
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuReportLog.Snapshot reports,
          List<BoardScene.Command> commands, GpuHudData panels) {
        BoardScene scene = mock(BoardScene.class);
        when(scene.commands()).thenReturn(commands);
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null, reports,
              status, panels);
    }

    /** The movement display's next-unit button as the scene carries it. */
    private static BoardScene.Command next(boolean enabled, Runnable action) {
        return next("moveNext", enabled, action);
    }

    /** A phase display's next-unit button, by the command id its button gives the scene. */
    private static BoardScene.Command next(String id, boolean enabled, Runnable action) {
        return new BoardScene.Command(id, "Next Unit", "", enabled, false, List.of(), action);
    }

    /** The fire orders of an actor with {@code attacks} queued attacks. */
    private static GpuFireOrders.Snapshot fire(int actor, int attacks) {
        List<GpuFireOrders.Attack> queued = IntStream.range(0, attacks).mapToObj(index -> new GpuFireOrders.Attack(
              index, TargetKey.unit(6), "Medium Laser", "RA", "Energy", "", 0, 7, .58, "")).toList();
        return new GpuFireOrders.Snapshot(true, true, actor, new GpuFireOrders.Focus(TargetKey.unit(6), "", null), -1,
              List.of(), List.of(), queued, 0, false, false, "", null, null, null, List.of(), null, Map.of(), 5, 0,
              null);
    }

    private static GpuReportLog.CombatEvent attack(int attacker, boolean hit) {
        return new GpuReportLog.CombatEvent(UUID.randomUUID(), 3, GamePhase.FIRING, ResolvedAttack.Kind.SHOT, attacker,
              6, "Medium Laser", "RA", hit, hit ? 5 : 0, List.of(), null, 0);
    }

    private static GpuBattleStatus.Snapshot status(GamePhase phase, boolean myTurn, int actor, int turnIndex,
          List<UnitStatus> units) {
        return new GpuBattleStatus.Snapshot(MOCK.round(), phase, myTurn, MOCK.localPlayerId(), actor, MOCK.turns(),
              turnIndex, units, List.of(), false);
    }

    /** The mock's units with the own ones changed. */
    private static List<UnitStatus> own(UnaryOperator<UnitStatus> change) {
        return MOCK.units().stream().map(unit -> unit.side() == OWN ? change.apply(unit) : unit).toList();
    }

    private static UnitStatus unit(GpuBattleStatus.Snapshot status, int id) {
        return status.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    /**
     * 100 v 100 as in shot 14: the mock's five own units repeated in 25 lances of four, the Atlas of Alpha lance
     * acting; the five enemies repeated in lances of their own.
     */
    private static GpuBattleStatus.Snapshot large() {
        List<UnitStatus> own = MOCK.units().stream().filter(unit -> unit.side() == OWN).toList();
        List<UnitStatus> enemy = MOCK.units().stream().filter(unit -> unit.side() == ENEMY).toList();
        List<UnitStatus> units = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            units.add(copy(own.get(index % own.size()), 100 + index, LANCES.get(index / 4) + " lance", index == 0,
                  true, false, "", 0));
        }
        for (int index = 0; index < 100; index++) {
            UnitStatus unit = enemy.get(index % enemy.size());
            units.add(copy(unit, 300 + index, unit.formation().isEmpty() ? "" : "Opposition " + (index / 4 + 1),
                  false, false, false, "", 0));
        }
        return status(GamePhase.MOVEMENT, true, 100, 0, units);
    }

    /** A copy of a unit with another id, formation, turn state and move. */
    private static UnitStatus copy(UnitStatus unit, int id, String formation, boolean canActNow, boolean pending,
          boolean done, String moved, int mpUsed) {
        return copy(unit, id, unit.side(), unit.ownerId(), formation, unit.tons(), unit.weightClass(),
              unit.weightClassIndex(), canActNow, pending, done, moved, mpUsed, unit.heat(), unit.heatEffects(),
              unit.statusWords());
    }

    /** A copy of a unit on another side, owned by player {@code owner}. */
    private static UnitStatus side(UnitStatus unit, GpuBattleStatus.Side side, int owner) {
        return copy(unit, unit.id(), side, owner, unit.formation(), unit.tons(), unit.weightClass(),
              unit.weightClassIndex(), unit.canActNow(), unit.pending(), unit.done(), unit.moved(), unit.mpUsed(),
              unit.heat(), unit.heatEffects(), unit.statusWords());
    }

    /**
     * A copy of a Mek at another heat with its heat effects as the status publishes them: the heat table's text, and
     * nothing below 5, where MegaMek's table has its first effect.
     */
    private static UnitStatus heat(UnitStatus unit, int heat) {
        return copy(unit, unit.id(), unit.side(), unit.ownerId(), unit.formation(), unit.tons(), unit.weightClass(),
              unit.weightClassIndex(), unit.canActNow(), unit.pending(), unit.done(), unit.moved(), unit.mpUsed(), heat,
              heat < 5 ? "" : HeatEffects.getHeatEffects(heat, false, false), unit.statusWords());
    }

    /** A copy of a unit with another id, weight and weight class. */
    private static UnitStatus weighs(UnitStatus unit, int id, double tons, String weightClass, int weightClassIndex) {
        return copy(unit, id, unit.side(), unit.ownerId(), unit.formation(), tons, weightClass, weightClassIndex,
              unit.canActNow(), unit.pending(), unit.done(), unit.moved(), unit.mpUsed(), unit.heat(),
              unit.heatEffects(), unit.statusWords());
    }

    /** A copy of a unit whose board label shows one word ({@code UnitStatusWords.statusWords} key and severity). */
    private static UnitStatus word(UnitStatus unit, String key, UnitStatusWords.Severity severity) {
        return copy(unit, unit.id(), unit.side(), unit.ownerId(), unit.formation(), unit.tons(), unit.weightClass(),
              unit.weightClassIndex(), unit.canActNow(), unit.pending(), unit.done(), unit.moved(), unit.mpUsed(),
              unit.heat(), unit.heatEffects(), List.of(new UnitStatusWords.StatusWord(key, key, severity)));
    }

    /** The one copy the helpers above make: the unit with every field these tests vary replaced. */
    private static UnitStatus copy(UnitStatus unit, int id, GpuBattleStatus.Side side, int owner, String formation,
          double tons, String weightClass, int weightClassIndex, boolean canActNow, boolean pending, boolean done,
          String moved, int mpUsed, int heat, String heatEffects, List<UnitStatusWords.StatusWord> words) {
        return new UnitStatus(id, side, unit.sensorContact(), unit.name(), unit.chassis(), unit.model(), tons,
              weightClass, formation, unit.pilot(), unit.gunnery(), unit.piloting(), unit.armor(), unit.structure(),
              heat, unit.heatRgb(), unit.heatCapacity(), unit.walk(), unit.run(), unit.jump(), moved, mpUsed,
              unit.hexesMoved(), unit.facing(), unit.tmm(), canActNow, pending, done, unit.destroyed(),
              unit.damageLevel(), unit.destroyedLocations(), heatEffects, unit.position(), unit.boardId(), unit.icon(),
              words, weightClassIndex, unit.declaredAttacks(), owner, unit.statusTiles());
    }

    /** The round of shots 08-10: the Warhammer held its position, the others walked or ran, nobody acted yet. */
    private static UnitStatus played(UnitStatus unit) {
        return switch (unit.id()) {
            case 1 -> copy(unit, 1, unit.formation(), false, false, false, "Walked", 2);
            case 3 -> copy(unit, 3, unit.formation(), false, false, false, "Ran", 5);
            case 4 -> copy(unit, 4, unit.formation(), false, false, false, "Walked", 3);
            case 5 -> copy(unit, 5, unit.formation(), false, false, false, "Walked", 8);
            default -> copy(unit, unit.id(), unit.formation(), false, false, false, "", 0);
        };
    }

    /** {@code count} weapon attacks of the unit this round, the first {@code hits} of them hits. */
    private static List<GpuReportLog.CombatEvent> attacks(int attacker, int count, int hits) {
        return IntStream.range(0, count).mapToObj(index -> attack(attacker, index < hits)).toList();
    }

    private static GpuReportLog.Snapshot reports(GamePhase phase, List<GpuReportLog.CombatEvent> combat) {
        return new GpuReportLog.Snapshot(3, phase, List.of(), Map.of(), combat, List.of(), List.of(), List.of());
    }

    /** The game's players as the players service lists them: the local player, the enemy and an ally. */
    private static GpuPlayers.Snapshot players() {
        return new GpuPlayers.Snapshot(List.of(player(GpuHudFixtures.LOCAL_PLAYER, "Player", 1, true),
              player(GpuHudFixtures.ENEMY_PLAYER, "Princess", 2, false), player(ALLIED_PLAYER, "Kurita", 1, false)));
    }

    private static GpuPlayers.PlayerRow player(int id, String name, int team, boolean local) {
        return new GpuPlayers.PlayerRow(id, name, team, 0, local, false, false, false, false, false, false, false,
              false, List.of());
    }
}
