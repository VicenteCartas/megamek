/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ALLY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.CONTACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.unitDisplay.HeatEffects;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The forces overview (G13) in the component harness: beside the hud-v3 shot 12 and in its slot at the three window
 * sizes; what its cards, grouping, sides and search do; double blind and allies; and, in the real HUD, its keys, the
 * Esc chain, the unit menu above it and the HUD's selection rule behind its cards.
 */
@Tag("on-demand")
class GpuForceOverviewSmokeTest {
    private static final GpuBattleStatus.Snapshot MOCK = GpuHudFixtures.status();
    /** The client's key bindings, as the HUD receives them. */
    private static final GpuBoardSource.UiPreferences PREFERENCES = GpuBoardSource.UiPreferences.capture();
    private static final int WARHAMMER = 2;
    private static final int MARAUDER = 3;
    private static final int PANTHER = 4;
    private static final int LOCUST = 5;
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int ENEMY_LOCUST = 10;
    /** The player id of an allied player, on the local player's team. */
    private static final int ALLIED_PLAYER = 7;

    /** An overview under test with the HUD state it reads, the units it selected and the source and menu it uses. */
    private static final class Overview {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuContextMenu menu = mock(GpuContextMenu.class);
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final List<Integer> selected = new ArrayList<>();
        final GpuForceOverview overview;
        final Table root;

        Overview(GpuHudTestStage hud) {
            overview = new GpuForceOverview(hud.kit, source, state, menu, selected::add);
            root = (Table) overview.actor();
            hud.window.addActor(root);
            state.overview = true;
        }

        /**
         * One frame as the HUD gives it: the status through the state's focus rule, then the overview in its slot
         * (the gaps at the sides, 90 below the window's top, 70 above its bottom), drawn so that clicks find its cards.
         */
        Overview show(GpuHudTestStage hud, GpuBoardSource.Frame frame) {
            state.update(frame.status(), GpuUnitRecord.Snapshot.EMPTY, false);
            frame.status().units().stream().map(UnitStatus::icon).filter(Objects::nonNull).forEach(hud.sprites::add);
            GpuHud.Metrics metrics = GpuHud.Metrics.of(hud.width(), hud.height());
            overview.update(new GpuHud.Inputs(frame, GpuHud.HudView.EMPTY, null, PREFERENCES, metrics, List.of()));
            root.setBounds(metrics.gap(), 70, hud.width() - 2 * metrics.gap(), hud.height() - 160);
            root.validate();
            hud.draw();
            return this;
        }

        <T extends Actor> T find(String name) {
            T actor = root.findActor(name);
            assertNotNull(actor, "no " + name);
            return actor;
        }

        Table card(int id) {
            return find("force-overview-card-" + id);
        }

        /** The listing as the player reads it: each side's heading, then each group's title and its cards' unit ids. */
        List<String> listing() {
            List<String> listing = new ArrayList<>();
            walk(find("force-overview-list"), actor -> {
                String name = actor.getName() == null ? "" : actor.getName();
                if (name.equals("force-overview-own")) {
                    listing.add("YOUR FORCE");
                } else if (name.equals("force-overview-contacts")) {
                    listing.add("CONTACTS");
                } else if (name.startsWith("force-overview-section-")) {
                    listing.add(name.substring("force-overview-section-".length()));
                } else if (name.startsWith("force-overview-card-")) {
                    listing.add(name.substring("force-overview-card-".length()));
                }
            });
            return listing;
        }

        /** Types into the search field and shows the frame. */
        void search(GpuHudTestStage hud, GpuBoardSource.Frame frame, String query) {
            this.<TextField>find("force-overview-search").setText(query);
            show(hud, frame);
        }
    }

    @Test
    void besideShot12AndInItsSlotAtTheThreeWindowSizes() {
        GpuHudTestStage.run(hud -> {
            Overview overview = new Overview(hud);
            GpuBoardSource.Frame frame = frame(MOCK, GpuPlayers.Snapshot.EMPTY);
            overview.show(hud, frame);
            // Shot 12 groups by status.
            click(hud.stage, overview.find("force-overview-grouping-status"), Input.Buttons.LEFT);
            overview.show(hud, frame);
            assertEquals(List.of("FORCE OVERVIEW", "10 units"),
                  texts(overview.find("force-overview-header")));
            assertEquals(List.of("GROUP", "Formation", "Weight", "Status", "SHOW", "Both", "Mine", "Contacts"),
                  texts(overview.find("force-overview-bar")));
            assertEquals(List.of("YOUR FORCE", "Needs orders", "1", "2", "3", "4", "5", "CONTACTS", "Operational", "6",
                  "7", "8", "10", "Unidentified", "9"), overview.listing());
            assertEquals(List.of("YOUR FORCE", "5 SHOWN"), texts(overview.find("force-overview-own")));
            assertEquals(List.of("NEEDS ORDERS", "5"), texts(overview.find("force-overview-section-Needs orders")));
            assertEquals(List.of("ATLAS", "AS7-D · 100 t · hex 1514", "ARMOR", "91", "STRUCT.", "100", "HEAT", "0",
                  "NEEDS ORDERS", "W 3 · R 5", "3/4"), texts(overview.card(ATLAS)));
            assertEquals(List.of("PANTHER", "PNT-9R · 35 t · hex 1714", "ARMOR", "96", "STRUCT.", "100", "HEAT", "3",
                  "NEEDS ORDERS", "W 4 · R 6 · J 4", "4/5"), texts(overview.card(PANTHER)));
            // An enemy shows what the client discloses (plan D3), where the prototype hid its heat and internals.
            assertEquals(List.of("TIMBER WOLF", "Prime · 75 t · hex 1505", "ARMOR", "92", "STRUCT.", "100", "HEAT", "4",
                  "OPERATIONAL", "W 5 · R 8", "3/4"), texts(overview.card(TIMBER_WOLF)));
            assertEquals(List.of("?", "SENSOR CONTACT", "Identity unknown · hex 1004", "Sensor return only"),
                  texts(overview.card(CONTACT)));
            assertEquals(UiTheme.AMBER, label(overview.card(ATLAS), "NEEDS ORDERS").getColor());
            assertEquals(UiTheme.ACCENT, label(overview.card(TIMBER_WOLF), "OPERATIONAL").getColor());
            // Shot 12's footer.
            assertEquals(List.of("Select a card to locate it · right-click for unit actions"),
                  texts(overview.find("force-overview-footer")));
            Pixmap image = hud.capture("force-overview-12");
            try {
                hud.compare("force-overview-12", image, overview.root, "12-force-overview.jpg", 20, 90);
                hud.compare("force-overview-12-header", image, overview.find("force-overview-header"),
                      "12-force-overview.jpg", 22, 92);
                hud.compare("force-overview-12-bar", image, overview.find("force-overview-bar"),
                      "12-force-overview.jpg", 22, 141);
                hud.compare("force-overview-12-atlas", image, overview.card(ATLAS), "12-force-overview.jpg", 38, 252);
                hud.compare("force-overview-12-timber-wolf", image, overview.card(TIMBER_WOLF),
                      "12-force-overview.jpg", 38, 429);
                hud.compare("force-overview-12-contact", image, overview.card(CONTACT), "12-force-overview.jpg", 38,
                      571);
            } finally {
                image.dispose();
            }

            // E rule 6: in its slot at the three window sizes, inside the window and nothing wider than the panel.
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                overview.show(hud, frame);
                hud.assertLayout(overview.root);
                hud.capture("force-overview-" + size[0] + "x" + size[1]).dispose();
            }
        });
    }

    @Test
    void cardsLocateAndSelectTheirUnitsOpenItsMenuAndKeepAPressAcrossAStatusUpdate() {
        GpuHudTestStage.run(hud -> {
            Overview overview = new Overview(hud);
            overview.show(hud, frame(MOCK, GpuPlayers.Snapshot.EMPTY));
            for (int id : new int[] { ATLAS, TIMBER_WOLF, CONTACT }) {
                click(hud.stage, overview.card(id), Input.Buttons.LEFT);
                verify(overview.source).locateUnit(id);
            }
            assertEquals(List.of(ATLAS, TIMBER_WOLF, CONTACT), overview.selected,
                  "a card hands its unit to the HUD's selection rule, which selects or inspects it");

            click(hud.stage, overview.card(KING_CRAB), Input.Buttons.RIGHT);
            verify(overview.menu).open(eq(new Coords(12, 3)), eq(KING_CRAB), anyFloat(), anyFloat());
            verify(overview.source, never()).locateUnit(KING_CRAB);
            assertEquals(3, overview.selected.size(), "the unit menu opens without a selection");

            // A status update during a press keeps the card, whose contents change, so the press still clicks.
            Vector2 point = centre(hud.stage, overview.card(WARHAMMER));
            hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            overview.show(hud, frame(status(MOCK.units().stream()
                  .map(unit -> unit.id() == MARAUDER ? heat(unit, 7) : unit).toList(), false),
                  GpuPlayers.Snapshot.EMPTY));
            assertEquals("7", texts(overview.card(MARAUDER)).get(7), "the Marauder's heat");
            hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            assertEquals(List.of(ATLAS, TIMBER_WOLF, CONTACT, WARHAMMER), overview.selected);
        });
    }

    @Test
    void groupsSortTheUnitsByFormationWeightOrStatus() {
        GpuHudTestStage.run(hud -> {
            Overview overview = new Overview(hud);
            overview.show(hud, frame(MOCK, GpuPlayers.Snapshot.EMPTY));
            assertEquals(List.of("YOUR FORCE", "Alpha lance", "1", "2", "3", "Bravo lance", "4", "5", "CONTACTS",
                  "Opposition lance 1", "6", "7", "8", "10", "Unidentified", "9"), overview.listing(),
                  "by formation, in the order the units come");
            List<UnitStatus> contactFirst = new ArrayList<>(MOCK.units());
            contactFirst.add(0, contactFirst.remove(contactFirst.indexOf(unit(CONTACT))));
            overview.show(hud, frame(status(contactFirst, false), GpuPlayers.Snapshot.EMPTY));
            assertEquals(List.of("CONTACTS", "Opposition lance 1", "6", "7", "8", "10", "Unidentified", "9"),
                  overview.listing().subList(8, 16), "the unidentified last, as ui.js sorts them");

            click(hud.stage, overview.find("force-overview-grouping-weight"), Input.Buttons.LEFT);
            overview.show(hud, frame(MOCK, GpuPlayers.Snapshot.EMPTY));
            assertEquals(List.of("YOUR FORCE", "Assault", "1", "Heavy", "2", "3", "Light", "4", "5", "CONTACTS",
                  "Assault", "7", "8", "Heavy", "6", "Light", "10", "Unidentified", "9"), overview.listing(),
                  "by weight class, heaviest first");

            click(hud.stage, overview.find("force-overview-grouping-status"), Input.Buttons.LEFT);
            overview.show(hud, frame(statuses(), GpuPlayers.Snapshot.EMPTY));
            assertEquals(List.of("YOUR FORCE", "Needs orders", "1", "Running hot", "3", "Damaged", "4", "Prone", "5",
                  "Acted", "2", "CONTACTS", "Damaged", "10", "Shut down", "6", "Operational", "8", "Unidentified", "9",
                  "Destroyed", "7"), overview.listing(), "by status, in the order of the taxonomy (plan N6)");
            assertEquals(UiTheme.MINT, label(overview.card(WARHAMMER), "ACTED").getColor());
            assertEquals(UiTheme.AMBER, label(overview.card(MARAUDER), "RUNNING HOT").getColor());
            assertEquals(UiTheme.CORAL, label(overview.card(LOCUST), "PRONE").getColor());
            assertEquals(UiTheme.CORAL, label(overview.card(KING_CRAB), "DESTROYED").getColor());
            assertEquals(.45f, overview.card(KING_CRAB).getColor().a, 1e-6, "a destroyed unit's card fades");
            hud.capture("force-overview-statuses").dispose();
        });
    }

    @Test
    void theSidesAndTheSearchFilterTheCards() {
        GpuHudTestStage.run(hud -> {
            Overview overview = new Overview(hud);
            GpuBoardSource.Frame frame = frame(MOCK, GpuPlayers.Snapshot.EMPTY);
            overview.show(hud, frame);
            click(hud.stage, overview.find("force-overview-show-mine"), Input.Buttons.LEFT);
            overview.show(hud, frame);
            assertEquals(List.of("YOUR FORCE", "Alpha lance", "1", "2", "3", "Bravo lance", "4", "5"),
                  overview.listing());
            click(hud.stage, overview.find("force-overview-show-contacts"), Input.Buttons.LEFT);
            overview.show(hud, frame);
            assertEquals(List.of("CONTACTS", "Opposition lance 1", "6", "7", "8", "10", "Unidentified", "9"),
                  overview.listing());
            click(hud.stage, overview.find("force-overview-show-both"), Input.Buttons.LEFT);

            // The search matches the name, model, formation and weight class, and a contact by its labels.
            overview.search(hud, frame, "Light");
            assertEquals(List.of("YOUR FORCE", "Bravo lance", "4", "5", "CONTACTS", "Opposition lance 1", "10"),
                  overview.listing());
            assertEquals(List.of("YOUR FORCE", "2 SHOWN"), texts(overview.find("force-overview-own")));
            assertEquals(List.of("CONTACTS", "1 SHOWN"), texts(overview.find("force-overview-contacts")));
            overview.search(hud, frame, "sensor");
            assertEquals(List.of("CONTACTS", "Unidentified", "9"), overview.listing());
            overview.search(hud, frame, "zzz");
            assertEquals(List.of(), overview.listing());
            assertEquals(List.of(Messages.getString("GpuBoard.hud.forces.noMatch", "zzz")),
                  texts(overview.find("force-overview-list")));
            overview.search(hud, frame, "");
            assertEquals(16, overview.listing().size());
        });
    }

    @Test
    void doubleBlindHidesEnemyFormationsAndAlliesGroupUnderTheirPlayer() {
        GpuHudTestStage.run(hud -> {
            Overview overview = new Overview(hud);
            List<UnitStatus> units = new ArrayList<>(MOCK.units());
            units.add(5, copy(unit(ATLAS), 11, ALLY, ALLIED_PLAYER, "Kurita lance", 0, false, false, false,
                  Entity.DMG_NONE, List.of()));
            units.add(6, copy(unit(PANTHER), 12, ALLY, ALLIED_PLAYER, "Kurita lance", 0, false, false, false,
                  Entity.DMG_NONE, List.of()));
            GpuBoardSource.Frame frame = frame(status(units, true), players());
            overview.show(hud, frame);
            assertEquals(List.of("YOUR FORCE", "Alpha lance", "1", "2", "3", "Bravo lance", "4", "5",
                  "Allies · Kurita", "11", "12", "CONTACTS", "Unidentified", "6", "7", "8", "9", "10"),
                  overview.listing(), "double blind names no enemy formation; allies go by their player");
            overview.search(hud, frame, "opposition");
            assertEquals(List.of(), overview.listing(), "nor does the search find one");
            overview.search(hud, frame, "kurita");
            assertEquals(List.of("YOUR FORCE", "Allies · Kurita", "11", "12"), overview.listing());
            click(hud.stage, overview.find("force-overview-show-mine"), Input.Buttons.LEFT);
            overview.search(hud, frame, "");
            assertEquals(List.of("YOUR FORCE", "Alpha lance", "1", "2", "3", "Bravo lance", "4", "5",
                  "Allies · Kurita", "11", "12"), overview.listing(), "Mine includes the allies");

            // Without double blind the enemy formation shows again.
            overview.show(hud, frame(status(units, false), players()));
            click(hud.stage, overview.find("force-overview-show-contacts"), Input.Buttons.LEFT);
            overview.show(hud, frame(status(units, false), players()));
            assertEquals(List.of("CONTACTS", "Opposition lance 1", "6", "7", "8", "10", "Unidentified", "9"),
                  overview.listing());
        });
    }

    @Test
    void aLargeBattleKeepsTheLayoutAndScrolls() {
        GpuHudTestStage.run(hud -> {
            // 100 v 100 as in shot 14: the mock's units repeated in lances of four.
            List<UnitStatus> own = MOCK.units().stream().filter(unit -> unit.side() == GpuBattleStatus.Side.OWN)
                  .toList();
            List<UnitStatus> enemies = MOCK.units().stream()
                  .filter(unit -> unit.side() == GpuBattleStatus.Side.ENEMY && !unit.sensorContact()).toList();
            List<UnitStatus> units = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                UnitStatus unit = own.get(index % own.size());
                units.add(copy(unit, 100 + index, unit.side(), unit.ownerId(), "Lance " + (index / 4 + 1), unit.heat(),
                      true, false, false, Entity.DMG_NONE, List.of()));
                UnitStatus enemy = enemies.get(index % enemies.size());
                units.add(copy(enemy, 300 + index, enemy.side(), enemy.ownerId(), "Opposition " + (index / 4 + 1),
                      enemy.heat(), false, false, false, Entity.DMG_NONE, List.of()));
            }
            Overview overview = new Overview(hud);
            GpuBoardSource.Frame frame = frame(status(units, false), GpuPlayers.Snapshot.EMPTY);
            long start = System.nanoTime();
            overview.show(hud, frame);
            long opened = System.nanoTime();
            // A status change that keeps every group: the cards change in place.
            overview.show(hud, frame(status(units.stream().map(unit -> unit.id() == 100 ? heat(unit, 9) : unit)
                  .toList(), false), GpuPlayers.Snapshot.EMPTY));
            long updated = System.nanoTime();
            click(hud.stage, overview.find("force-overview-grouping-weight"), Input.Buttons.LEFT);
            long regrouped = System.nanoTime();
            overview.show(hud, frame);
            long laidOut = System.nanoTime();
            System.out.printf("100 v 100: open %.1f ms, update in place %.1f ms, regroup %.1f ms (frames drawn)%n",
                  (opened - start) / 1e6, (updated - opened) / 1e6, (laidOut - regrouped) / 1e6);
            assertEquals(200, overview.listing().stream().filter(item -> item.matches("\\d+")).count());
            assertEquals(List.of("YOUR FORCE", "Assault"), overview.listing().subList(0, 2));
            ScrollPane list = overview.find("force-overview-list");
            assertTrue(list.getMaxY() > 0, "the list scrolls");
            hud.capture("force-overview-100v100").dispose();
            list.setScrollPercentY(1);
            list.updateVisualScroll();
            hud.draw();
            hud.capture("force-overview-100v100-end").dispose();
        });
    }

    @Test
    void theHudsKeysOpenAndCloseItEscEndsTheSearchFirstAndItsCardsUseTheHudsMenuAndSelection() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuBoardSource source = mock(GpuBoardSource.class);
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(), mock(GpuBoardTuning.class),
                  new GpuPlaybackHistory(new UnitPlayback()));
            try {
                // One stage unit per back-buffer pixel, as the harness has it.
                float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                hud.resize(Math.round(1920 / density), Math.round(1080 / density), 1 / density);
                GpuBoardSource.Frame frame = frame(MOCK, GpuPlayers.Snapshot.EMPTY);
                hud.update(frame, GpuHud.HudView.EMPTY, null, PREFERENCES);
                Group root = hud.stage.getRoot();
                Table overview = root.findActor("force-overview");
                assertFalse(shown(overview));

                assertTrue(press(hud, KeyCommandBind.UNIT_OVERVIEW, Input.Keys.U));
                show(hud, frame, overview);
                assertTrue(shown(overview), "the overview's key opens it");
                assertEquals(new Rectangle(GpuBoardHud.GAP, 70, 1920 - 2 * GpuBoardHud.GAP, 920),
                      GpuHudTestStage.bounds(overview),
                      "left and right gap, top 90, bottom 70");
                // What it covers is hidden (the prototype blurs it, shot 12).
                for (String covered : List.of("unit-card", "command-dock", "hint-line", "forces-panel", "minimap",
                      "contacts-panel")) {
                    assertFalse(shown(root.findActor(covered)), covered + " is hidden while it is open");
                }
                assertTrue(press(hud, KeyCommandBind.FORCE_DISPLAY, Input.Keys.F));
                show(hud, frame, overview);
                assertFalse(shown(overview), "the force display's key closes it");
                assertTrue(shown(root.findActor("contacts-panel")), "the panels under it are shown again");
                assertTrue(press(hud, KeyCommandBind.FORCE_DISPLAY, Input.Keys.F));
                show(hud, frame, overview);
                assertTrue(shown(overview));

                // While the search field has the keyboard focus it takes the keys; the first Esc ends the focus.
                click(hud.stage, overview.findActor("force-overview-search"), Input.Buttons.LEFT);
                assertTrue(hud.isTextEditing());
                assertTrue(press(hud, KeyCommandBind.UNIT_OVERVIEW, Input.Keys.U));
                assertTrue(hud.state.overview, "the key went to the search field");
                assertTrue(press(hud, KeyCommandBind.CANCEL, Input.Keys.ESCAPE));
                assertFalse(hud.isTextEditing());
                assertTrue(hud.state.overview);
                assertTrue(press(hud, KeyCommandBind.CANCEL, Input.Keys.ESCAPE));
                assertFalse(hud.state.overview, "the next Esc closes the overview");
                assertTrue(press(hud, KeyCommandBind.UNIT_OVERVIEW, Input.Keys.U));
                show(hud, frame, overview);

                // A card's right click opens the HUD's unit menu above the overview; the first Esc closes the menu.
                click(hud.stage, overview.findActor("force-overview-card-" + KING_CRAB), Input.Buttons.RIGHT);
                Actor popover = root.findActor("context-menu-popover");
                assertTrue(popover.isVisible());
                assertEquals("KING CRAB KGC-000", texts(popover).getFirst());
                assertTrue(layer(root, popover) > layer(root, overview), "the menu's layer lies above the overview's");
                assertTrue(press(hud, KeyCommandBind.CANCEL, Input.Keys.ESCAPE));
                assertFalse(popover.isVisible());
                assertTrue(hud.state.overview);

                // A card's click goes through the HUD's selection rule: a unit that cannot act now is inspected, the
                // acting unit stays selected and ends the inspection.
                click(hud.stage, overview.findActor("force-overview-card-" + WARHAMMER), Input.Buttons.LEFT);
                verify(source).locateUnit(WARHAMMER);
                assertEquals(WARHAMMER, hud.state.inspected);
                click(hud.stage, overview.findActor("force-overview-card-" + ATLAS), Input.Buttons.LEFT);
                verify(source).locateUnit(ATLAS);
                assertEquals(Entity.NONE, hud.state.inspected);
                verify(source, never()).selectUnit(anyInt());
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    // ---------------------------------------------------------------- the HUD

    /** One HUD frame, then the overview's slot laid out, so that clicks find its widgets without a draw. */
    private static void show(GpuHud hud, GpuBoardSource.Frame frame, Table overview) {
        hud.update(frame, GpuHud.HudView.EMPTY, null, PREFERENCES);
        ((Container<?>) overview.getParent()).validate();
    }

    /** A key press of the bind's current key, as the board view hands it to the HUD. */
    private static boolean press(GpuHud hud, KeyCommandBind command, int key) {
        GpuBoardSource.Bind bind = PREFERENCES.binds().stream().filter(entry -> entry.command() == command)
              .findFirst().orElseThrow();
        return hud.keyDown(key, bind.keyCode(), bind.modifiers());
    }

    private static String key(KeyCommandBind command) {
        return GpuHintLine.key(PREFERENCES, command);
    }

    /** The actor and every parent are visible. */
    private static boolean shown(Actor actor) {
        for (Actor node = actor; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** The index among the stage root's layers of the layer that holds the actor. */
    private static int layer(Group root, Actor actor) {
        Actor node = actor;
        while (node.getParent() != root) {
            node = node.getParent();
        }
        return root.getChildren().indexOf(node, true);
    }

    // ---------------------------------------------------------------- reading and pressing

    /** A press and release at the actor's centre through the stage, as the board view hands HUD input on. */
    private static void click(Stage stage, Actor actor, int button) {
        Vector2 point = centre(stage, actor);
        stage.touchDown((int) point.x, (int) point.y, 0, button);
        stage.touchUp((int) point.x, (int) point.y, 0, button);
    }

    /** The actor's centre in screen coordinates. */
    private static Vector2 centre(Stage stage, Actor actor) {
        assertNotNull(actor);
        return stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }

    /** The texts of the actor's shown labels, as the player reads them: a table's cells in layout order. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        walk(actor, child -> {
            if (child instanceof Label label && label.isVisible() && !label.getText().isEmpty()) {
                texts.add(label.getText().toString());
            }
        });
        return texts;
    }

    /** The shown label with this text, or a failure. */
    private static Label label(Actor actor, String text) {
        List<Label> found = new ArrayList<>();
        walk(actor, child -> {
            if (child instanceof Label label && label.isVisible() && label.getText().toString().equals(text)) {
                found.add(label);
            }
        });
        assertEquals(1, found.size(), "labels \"" + text + "\" in " + texts(actor));
        return found.getFirst();
    }

    /** Visits the actor and its descendants in reading order: a table by its cells, another group by its children. */
    private static void walk(Actor actor, Consumer<Actor> visit) {
        if (!actor.isVisible()) {
            return;
        }
        visit.accept(actor);
        if (actor instanceof Table table) {
            for (Cell<?> cell : table.getCells()) {
                if (cell.getActor() != null) {
                    walk(cell.getActor(), visit);
                }
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> walk(child, visit));
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuPlayers.Snapshot players) {
        return new GpuBoardSource.Frame(mock(BoardScene.class), List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY,
              GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY,
              GpuUnitRecord.Snapshot.EMPTY, GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY,
              GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE, players));
    }

    /** The mock's movement turn of the Atlas with these units; {@code doubleBlind} is the option's status flag. */
    private static GpuBattleStatus.Snapshot status(List<UnitStatus> units, boolean doubleBlind) {
        return new GpuBattleStatus.Snapshot(MOCK.round(), MOCK.phase(), MOCK.myTurn(), MOCK.localPlayerId(),
              MOCK.actorId(), MOCK.turns(), MOCK.turnIndex(), units, MOCK.initiative(), doubleBlind);
    }

    private static UnitStatus unit(int id) {
        return MOCK.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    /**
     * Every status of the taxonomy in the mock's movement turn: the Atlas needs orders, the Warhammer has moved, the
     * Marauder runs hot outside its turn, the Panther is damaged, the Locust prone; the enemy Timber Wolf is shut down,
     * the King Crab destroyed, the BattleMaster operational and the enemy Locust lightly damaged.
     */
    private static GpuBattleStatus.Snapshot statuses() {
        List<UnitStatus> units = MOCK.units().stream().map(unit -> switch (unit.id()) {
            case WARHAMMER -> copy(unit, false, true, false, Entity.DMG_NONE, unit.heat(), List.of());
            case MARAUDER -> copy(unit, false, false, false, Entity.DMG_NONE, 15, List.of());
            case PANTHER -> copy(unit, false, false, false, Entity.DMG_MODERATE, unit.heat(), List.of());
            case LOCUST -> copy(unit, true, false, false, Entity.DMG_NONE, unit.heat(), List.of(word("PRONE")));
            case TIMBER_WOLF -> copy(unit, true, false, false, Entity.DMG_NONE, unit.heat(),
                  List.of(word("SHUTDOWN")));
            case KING_CRAB -> copy(unit, false, false, true, Entity.DMG_CRIPPLED, unit.heat(), List.of());
            case ENEMY_LOCUST -> copy(unit, true, false, false, Entity.DMG_LIGHT, unit.heat(), List.of());
            default -> unit;
        }).toList();
        return status(units, false);
    }

    /** A board label word ({@code UnitStatusWords.statusWords} key). */
    private static UnitStatusWords.StatusWord word(String key) {
        return new UnitStatusWords.StatusWord(key, key, UnitStatusWords.Severity.WARNING);
    }

    private static UnitStatus heat(UnitStatus unit, int heat) {
        return copy(unit, unit.pending(), unit.done(), unit.destroyed(), unit.damageLevel(), heat, unit.statusWords());
    }

    private static UnitStatus copy(UnitStatus unit, boolean pending, boolean done, boolean destroyed, int damageLevel,
          int heat, List<UnitStatusWords.StatusWord> words) {
        return copy(unit, unit.id(), unit.side(), unit.ownerId(), unit.formation(), heat, pending, done, destroyed,
              damageLevel, words);
    }

    /**
     * A copy of the unit with the fields these tests vary, and with its heat effects as the status publishes them:
     * the heat table's text, and nothing below 5, where the table has its first effect.
     */
    private static UnitStatus copy(UnitStatus unit, int id, GpuBattleStatus.Side side, int owner, String formation,
          int heat, boolean pending, boolean done, boolean destroyed, int damageLevel,
          List<UnitStatusWords.StatusWord> words) {
        return new UnitStatus(id, side, unit.sensorContact(), unit.name(), unit.chassis(), unit.model(), unit.tons(),
              unit.weightClass(), formation, unit.pilot(), unit.gunnery(), unit.piloting(), unit.armor(),
              unit.structure(), heat, unit.heatRgb(), unit.heatCapacity(), unit.walk(), unit.run(), unit.jump(),
              unit.moved(), unit.mpUsed(), unit.hexesMoved(), unit.facing(), unit.tmm(), unit.canActNow(), pending,
              done, destroyed, damageLevel, unit.destroyedLocations(),
              heat < 5 ? "" : HeatEffects.getHeatEffects(heat, false, false), unit.position(), unit.boardId(),
              unit.icon(), words, unit.weightClassIndex(), unit.declaredAttacks(), owner, unit.statusTiles());
    }

    /** The game's players as the players service lists them: the local player, the enemy and an ally. */
    private static GpuPlayers.Snapshot players() {
        return new GpuPlayers.Snapshot(List.of(player(GpuHudFixtures.LOCAL_PLAYER, "Player", 1, true),
              player(GpuHudFixtures.ENEMY_PLAYER, "Princess", 2, false), player(ALLIED_PLAYER, "Kurita", 1, false)));
    }

    private static GpuPlayers.PlayerRow player(int id, String name, int team, boolean local) {
        return new GpuPlayers.PlayerRow(id, name, team, 0, local, false, false, false, false, false, false, false,
              false);
    }
}
