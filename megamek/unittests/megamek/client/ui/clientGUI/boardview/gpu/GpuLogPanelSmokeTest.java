/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Clipboard;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The combat log (G11) in the component harness: the weapon playback of shot 08 and the round report of shot 10
 * beside the mock, its filters, search, empty states, earlier rounds, keywords, keyword filter, artillery in flight and
 * copy on a scripted report set, its cards'
 * review, Locate and Replay through the playback history, and its layout at the three window sizes. The report
 * entries carry display values read off the mock's pictures, as the report log would capture them.
 */
@Tag("on-demand")
class GpuLogPanelSmokeTest {
    private static final GpuBattleStatus.Snapshot MOCK = GpuHudFixtures.status();
    private static final int WARHAMMER = 2;
    private static final int MARAUDER = 3;
    private static final int PANTHER = 4;
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    /** The log replaces the right column: below the minimap, 380 wide at 1920 x 1080, 70 above the bottom. */
    private static final int LEFT = 1520;
    private static final int TOP = 312;
    /** The roll line's key (K7). */
    private static final String ROLL_KEY = "GpuBoard.hud.log.roll";
    private static final Map<Integer, String> NAMES = Map.of(ATLAS, "Atlas", WARHAMMER, "Warhammer", MARAUDER,
          "Marauder", PANTHER, "Panther", TIMBER_WOLF, "Timber Wolf", KING_CRAB, "King Crab", BATTLEMASTER,
          "BattleMaster");

    /** A log under test with the HUD state, the playback history it reads and the source and menu it calls. */
    private static final class Panel {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuContextMenu menu = mock(GpuContextMenu.class);
        final GpuLogPanel log;
        final Table root;
        GpuBoardSource.UiPreferences preferences = GpuHudFixtures.preferences();

        Panel(GpuHudTestStage hud) {
            hud.window.clearChildren();
            log = new GpuLogPanel(hud.kit, source, state, menu);
            root = (Table) log.actor();
            hud.window.addActor(root);
        }

        /** One frame of the HUD: the status through the state, then the log, laid out in the right column. */
        void show(GpuHudTestStage hud, GpuBattleStatus.Snapshot status, GpuReportLog.Snapshot reports) {
            state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
            log.update(new GpuHud.Inputs(frame(status, reports), GpuHud.HudView.EMPTY, null, preferences,
                  GpuHud.Metrics.of(hud.width(), hud.height()), List.of()));
            place(hud);
            hud.draw();
        }

        /**
         * As the log's slot does: the column's width, below the minimap (header 36, canvas 150 or 120 at H <= 800,
         * padding and rails: 90 to 300 or 270) and 70 above the bottom, the content's height at most; y down.
         */
        void place(GpuHudTestStage hud) {
            GpuHud.Metrics metrics = GpuHud.Metrics.of(hud.width(), hud.height());
            float top = 90 + (metrics.lowHeight() ? 180 : 210) + 12;
            for (int pass = 0; pass < 2; pass++) {
                root.setWidth(metrics.log());
                root.validate();
                float height = Math.min(root.getPrefHeight(), hud.height() - 70 - top);
                root.setBounds(hud.width() - metrics.gap() - metrics.log(), hud.height() - top - height,
                      metrics.log(), height);
                root.validate();
            }
        }

        <T extends Actor> T find(String name) {
            return root.findActor(name);
        }

        /** The cards of the list, in order. */
        List<UiButton> cardActors() {
            List<UiButton> cards = new ArrayList<>();
            for (Actor child : ((Table) this.<ScrollPane>find("log-list").getActor()).getChildren()) {
                if (child instanceof UiButton card) {
                    cards.add(card);
                }
            }
            return cards;
        }

        /** The texts of each card in the list, in order. */
        List<List<String>> cards() {
            return cardActors().stream().map(GpuLogPanelSmokeTest::texts).toList();
        }

        /** Every text of the list: section headers, cards and the empty state. */
        List<String> list() {
            return texts(this.<ScrollPane>find("log-list").getActor());
        }

        UiButton card(int index) {
            return cardActors().get(index);
        }
    }

    @Test
    void logBesideTheMock() {
        GpuHudTestStage.run(hud -> {
            // Shot 08: the weapon playback of round 3, paused after the Atlas's fourth shot, the newest one current.
            Panel panel = new Panel(hud);
            Round round = shot08();
            play(panel.state.history, round);
            GpuBattleStatus.Snapshot status = status(GamePhase.FIRING_REPORT);
            panel.show(hud, status, round.log());
            assertEquals(List.of("ROUND 03 \u00B7 COMBAT LOG"), texts(panel.find("log-header")));
            assertEquals(List.of("2", "HITS", "2", "MISSES", "0", "HEAT ALERTS"), texts(panel.find("log-totals")));
            assertEquals(List.of("WEAPON ATTACKS", "4 events"), panel.list().subList(0, 2));
            List<List<String>> cards = panel.cards();
            assertEquals(List.of("01", "Atlas \u2192 King Crab", "AC/20", "RT", " \u00B7 ", "MISS", roll(8, 4)),
                  cards.get(0));
            assertEquals(List.of("02", "Atlas \u2192 King Crab", "LRM 20", "LT", " \u00B7 ", "HIT", " \u00B7 12 damage",
                  roll(4, 5)), cards.get(1));
            assertEquals(List.of("04", "Atlas \u2192 King Crab", "Medium Laser", "LA", " \u00B7 ", "HIT",
                  " \u00B7 5 damage", roll(8, 8), "5 \u2192 CT",
                  "Medium Laser at King Crab; needs 8, rolls 8 : hits, King Crab takes 5 damage to Center Torso.",
                  "LOCATE", "REPLAY"), cards.get(3), "The newest step is current: its impacts, report and actions");
            assertEquals(List.of("COPY"), texts(panel.find("log-footer")));
            shoot(hud, panel, "log-08-playback", "08-weapon-playback.jpg");

            // Shot 10: the round report, the LRM card reviewed.
            panel = new Panel(hud);
            round = shot10();
            play(panel.state.history, round);
            panel.state.history.reviewTo(panel.state.history.played().get(1));
            panel.show(hud, status(GamePhase.END_REPORT), round.log());
            assertEquals(List.of("ROUND 03 \u00B7 BATTLE REPORT"), texts(panel.find("log-header")));
            assertEquals(List.of("15", "HITS", "14", "MISSES", "2", "HEAT ALERTS"), texts(panel.find("log-totals")));
            Table alerts = (Table) panel.<Table>find("log-totals").getChildren().get(2);
            assertEquals(UiTheme.AMBER, alerts.getChildren().first().getColor(), "Heat alerts are amber above 0");
            assertEquals(List.of("WEAPON ATTACKS", "29 events"), panel.list().subList(0, 2));
            assertEquals(List.of("02", "Atlas \u2192 King Crab", "LRM 20", "LT", " \u00B7 ", "HIT", " \u00B7 12 damage",
                  roll(4, 5), "5 \u2192 RL", "5 \u2192 LA", "2 \u2192 RA", "12/20 missiles", "Standard ammo",
                  "LRM 20 at King Crab; needs 4, rolls 5 : 12 missiles hit.", "LOCATE", "REPLAY"),
                  panel.cards().get(1), "The current card: impacts, missiles and ammunition, the report, the actions");
            assertTrue(panel.card(1).isChecked(), "The reviewed card is current (.ev.cur)");
            assertEquals("Timber Wolf \u2192 Atlas", panel.cards().get(5).get(1));
            shoot(hud, panel, "log-10-report", "10-round-report.jpg");
        });
    }

    @Test
    void filtersSearchAndEmptyStatesFollowTheScriptedReports() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            Round round = scripted();
            play(panel.state.history, round);
            GpuBattleStatus.Snapshot status = status(GamePhase.END_REPORT, 4);
            panel.show(hud, status, round.log());
            assertEquals(List.of("WEAPON ATTACKS", "2 events", "PILOTING ROLLS", "2 events", "HEAT & STATUS",
                  "2 events"), sections(panel.list()), "Summary: the groups, without the moves and other lines");
            assertEquals(List.of("2", "HITS", "0", "MISSES", "1", "HEAT ALERT"), texts(panel.find("log-totals")));

            click(hud, panel.find("log-filter-1"));
            panel.show(hud, status, round.log());
            assertEquals(List.of("Atlas \u2192 Timber Wolf", "Timber Wolf \u2192 Atlas", "Atlas \u00B7 piloting roll",
                  "Marauder \u00B7 heat"), whos(panel), "My force: an own unit attacks, is attacked or rolls");
            assertEquals(List.of("1", "HIT", "0", "MISSES", "1", "HEAT ALERT"), texts(panel.find("log-totals")),
                  "My force counts the own attacks");
            click(hud, panel.find("log-filter-2"));
            panel.show(hud, status, round.log());
            assertEquals(List.of("Timber Wolf \u2192 Atlas", "Atlas \u00B7 piloting roll", "Marauder \u00B7 heat"),
                  whos(panel), "Critical: a destroyed location, a failed roll, a heat alert");
            click(hud, panel.find("log-filter-0"));

            click(hud, panel.find("log-search"));
            panel.show(hud, status, round.log());
            panel.<TextField>find("log-search-field").setText("laser");
            panel.show(hud, status, round.log());
            assertEquals(List.of("Atlas \u2192 Timber Wolf", "Timber Wolf \u2192 Atlas"), whos(panel),
                  "Search covers the card and MegaMek's own words");
            panel.<TextField>find("log-search-field").setText("zzz");
            panel.show(hud, status, round.log());
            assertEquals(List.of("No events match the filter."), panel.list());
            click(hud, panel.find("log-search"));
            panel.show(hud, status, round.log());

            click(hud, panel.find("log-tab-full"));
            panel.show(hud, status, round.log());
            assertEquals(List.of("Initiative Phase", "Atlas AS7-D \u00B7 movement", "Atlas \u2192 Timber Wolf",
                  "Timber Wolf \u2192 Atlas", "King Crab \u00B7 piloting roll", "Atlas \u00B7 piloting roll",
                  "Marauder \u00B7 heat", "Timber Wolf \u00B7 heat"), whos(panel),
                  "Full log: every entry in report order and the moves after the movement phase");
            assertEquals(List.of("Atlas AS7-D \u00B7 movement", "Walked \u00B7 2 MP \u00B7 2 hexes",
                  "Hex 1414 \u2192 1412 \u00B7 facing N"), panel.cards().get(1));
            click(hud, panel.find("log-tab-summary"));

            // Empty states: a round without events, and a summary of lines that belong to no group.
            panel.show(hud, status(GamePhase.INITIATIVE, 5), log(5, GamePhase.INITIATIVE, List.of(), List.of()));
            assertEquals(List.of("No events yet this round."), panel.list());
            panel.show(hud, status, log(4, GamePhase.INITIATIVE_REPORT, List.of(other(4)), List.of()));
            assertEquals(List.of("Nothing to report."), panel.list());

            // Earlier rounds (K13): the header picks round 3, a finished battle report without totals.
            panel = new Panel(hud);
            List<GpuReportLog.Entry> both = new ArrayList<>(shot08().log().entries());
            both.addAll(round.log().entries());
            GpuReportLog.Snapshot two = new GpuReportLog.Snapshot(4, GamePhase.FIRING_REPORT, both, Map.of(),
                  round.log().combat(), round.log().psr(), round.log().moves(), List.of());
            play(panel.state.history, new Round(two, round.events()));
            panel.show(hud, status, two);
            assertEquals(List.of("ROUND 04 \u00B7 COMBAT LOG", "Round 4"), texts(panel.find("log-header")));
            click(hud, panel.find("log-round"));
            ArgumentCaptor<IntConsumer> choose = ArgumentCaptor.forClass(IntConsumer.class);
            verify(panel.menu).list(any(), anyList(), anyInt(), choose.capture());
            choose.getValue().accept(1);
            panel.show(hud, status, two);
            assertEquals(List.of("ROUND 03 \u00B7 BATTLE REPORT", "Round 3"), texts(panel.find("log-header")));
            assertNull(panel.find("log-totals"), "No totals: the report log keeps no events of earlier rounds");
            assertEquals(4, panel.cards().size());

            // Report keywords (K13): N finds the next card with the keyword; the row shows with the search.
            panel = new Panel(hud);
            play(panel.state.history, round);
            panel.preferences = GpuHudFixtures.with(GpuHudFixtures.preferences(),
                  Map.of("reportKeywords", "Piloting\nlaser", "reportFilterKeywords", "Hit Damage"));
            panel.show(hud, status, round.log());
            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_SELECT_NEXT)));
            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_NEXT)));
            panel.show(hud, status, round.log());
            assertNotNull(panel.find("log-keyword"), "The keyword row shows with the search");
            assertEquals("laser", texts(panel.find("log-keyword")).getFirst());
            Label matched = (Label) ((Table) panel.card(0).getChildren().get(1)).getChildren().first();
            assertEquals(UiTheme.AMBER, matched.getStyle().fontColor, "The found card is marked");
            assertFalse(panel.log.key(Set.of(KeyCommandBind.TOGGLE_CHAT)), "Other keys are not the log's");
        });
    }

    /**
     * MegaMek's other report tools, which the old report reader had (plan F.2 item 11): the keyword filter (Shift+F)
     * and the next filter keyword keep the cards with their words, N and Shift+N step through the cards with the
     * keyword, the artillery in the air has its own section, and Copy puts the shown cards' texts on the clipboard.
     */
    @Test
    void keywordFilterFindStepsArtilleryInFlightAndCopy() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            Round round = scripted();
            RoundsInAirDialog.Row inbound = new RoundsInAirDialog.Row(1, "Team 2", "OpFor", "Thumper Artillery Vehicle",
                  "1 turn(s)", "0607", "Standard");
            GpuReportLog.Snapshot log = new GpuReportLog.Snapshot(4, GamePhase.END_REPORT, round.log().entries(),
                  Map.of(), round.log().combat(), round.log().psr(), round.log().moves(), List.of(inbound));
            play(panel.state.history, new Round(log, round.events()));
            GpuBattleStatus.Snapshot status = status(GamePhase.END_REPORT, 4);
            panel.preferences = GpuHudFixtures.with(GpuHudFixtures.preferences(),
                  Map.of("reportKeywords", "laser", "reportFilterKeywords", "Piloting\nheat"));
            panel.show(hud, status, log);
            List<String> list = panel.list();
            int artillery = list.indexOf(UiTheme.upper(Messages.getString("GpuBoard.hud.log.artilleryInFlight")));
            assertTrue(artillery >= 0, "The artillery in the air has its section: " + list);
            assertEquals(List.of("1", "1", "Thumper Artillery Vehicle", "0607 · Standard",
                  "1 turn(s) · OpFor"), list.subList(artillery + 1, list.size()),
                  "its count, then the round's card: its turns in the disc, the unit, the hex and warhead, the turn");

            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_FILTER)));
            panel.show(hud, status, log);
            assertEquals(List.of("King Crab · piloting roll", "Atlas · piloting roll"), whos(panel),
                  "Shift+F keeps the cards with the first filter keyword");
            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_FILTER_KEY_SELECT_NEXT)));
            panel.show(hud, status, log);
            assertEquals(List.of("Marauder · heat", "Timber Wolf · heat"), whos(panel),
                  "and the next filter keyword its own");
            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_FILTER)));
            panel.show(hud, status, log);
            assertEquals(7, panel.cards().size(), "Shift+F again shows every card");

            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_NEXT)));
            panel.show(hud, status, log);
            assertEquals(List.of(true, false), List.of(matched(panel, 0), matched(panel, 1)),
                  "N finds the first laser");
            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_NEXT)));
            panel.show(hud, status, log);
            assertEquals(List.of(false, true), List.of(matched(panel, 0), matched(panel, 1)), "then the next");
            assertTrue(panel.log.key(Set.of(KeyCommandBind.REPORT_KEY_PREV)));
            panel.show(hud, status, log);
            assertEquals(List.of(true, false), List.of(matched(panel, 0), matched(panel, 1)), "Shift+N goes back");

            // The copy goes to a clipboard of the test's own, not to the desktop's.
            List<String> copies = new ArrayList<>();
            Clipboard clipboard = mock(Clipboard.class);
            doAnswer(call -> copies.add(call.getArgument(0))).when(clipboard).setContents(anyString());
            Application application = Gdx.app;
            Gdx.app = mock(Application.class);
            when(Gdx.app.getClipboard()).thenReturn(clipboard);
            try {
                click(hud, panel.find("log-copy"));
            } finally {
                Gdx.app = application;
            }
            assertEquals(1, copies.size(), "Copy sets the clipboard once");
            String copied = copies.getFirst();
            assertTrue(copied.startsWith("Medium Laser at Timber Wolf; needs 7, rolls 9 : hits.\n\n"), copied);
            assertTrue(copied.contains("Marauder gains 19 heat"), copied);
            assertTrue(copied.endsWith("Thumper Artillery Vehicle · 0607 · Standard · 1 turn(s)"
                  + " · OpFor\n\n"), copied);
        });
    }

    /** Whether the card at {@code index} is marked as a keyword's find: its who line in amber. */
    private static boolean matched(Panel panel, int index) {
        Label who = (Label) ((Table) panel.card(index).getChildren().get(1)).getChildren().first();
        return who.getStyle().fontColor.equals(UiTheme.AMBER);
    }

    @Test
    void cardsReviewLocateAndReplayThroughTheHistoryAndWaitForThePlayback() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            Round round = scripted();
            GpuPlaybackHistory history = panel.state.history;
            play(history, round);
            GpuBattleStatus.Snapshot status = status(GamePhase.END_REPORT, 4);
            panel.show(hud, status, round.log());

            click(hud, panel.card(1));
            panel.show(hud, status, round.log());
            assertEquals(history.played().get(1), history.current(), "A card click reviews its step (K8)");
            assertTrue(panel.card(1).isChecked());
            click(hud, panel.find("log-locate"));
            verify(panel.source).locateUnit(ATLAS);
            assertEquals(Entity.NONE, panel.state.inspected, "An own unit is only centred");
            click(hud, panel.find("log-play"));
            GpuPlaybackHistory.Step replayed = history.played().get(1);
            assertFalse(history.replaying(), "A card replays only its event, not the sequence");
            assertEquals(replayed, history.current(), "Replay keeps the already selected card open");
            assertEquals(TIMBER_WOLF, panel.state.inspected, "Replay inspects the actor, not the target");
            history.advance(10, ignored -> true);
            history.advance(10, ignored -> true);
            panel.show(hud, status, round.log());
            assertEquals(replayed, history.current(), "The cursor stays after the shot and its dwell finish");
            assertEquals(TIMBER_WOLF, panel.state.inspected, "The replayed actor stays inspected");
            verify(panel.source, never()).selectUnit(anyInt());
            history.togglePaused();
            assertTrue(history.replaying(), "Explicit Play still starts sequence transport");
            click(hud, panel.card(0));
            panel.show(hud, status, round.log());
            assertFalse(history.replaying(), "A card click stops the replay");
            assertEquals(history.played().getFirst(), history.current());
            click(hud, panel.find("log-locate"));
            verify(panel.source).locateUnit(TIMBER_WOLF);
            assertEquals(TIMBER_WOLF, panel.state.inspected, "An enemy is also inspected");
            click(hud, panel.find("log-play"));
            history.advance(10, ignored -> true);
            panel.show(hud, status, round.log());
            assertEquals(ATLAS, panel.state.inspected, "Replay also keeps an own actor inspected");
            verify(panel.source, never()).selectUnit(anyInt());
            click(hud, panel.card(0));
            panel.show(hud, status, round.log());
            assertNull(history.current(), "Again, the card collapses");
            assertFalse(panel.card(0).isChecked());

            // Attacks the playback is presenting: the one that landed is listed, the next one not yet, and the totals
            // count the presented ones only.
            panel = new Panel(hud);
            history = panel.state.history;
            play(history, round);
            // Two units, so the playback does not fire them as one volley.
            BoardScene.Combat miss = attack(PANTHER, KING_CRAB, false);
            BoardScene.Combat hit = attack(WARHAMMER, KING_CRAB, true);
            List<GpuReportLog.Entry> entries = new ArrayList<>(round.log().entries());
            entries.add(attackEntry(4, miss, "Medium Laser", "LA", null, null, "Medium Laser at King Crab", 8, 5));
            entries.add(attackEntry(4, hit, "Medium Laser", "RA", null, null, "Medium Laser at King Crab", 8, 9));
            List<GpuReportLog.CombatEvent> combat = new ArrayList<>(round.log().combat());
            combat.add(event(4, miss, "Medium Laser", "LA", 0, List.of(), null, 0));
            combat.add(event(4, hit, "Medium Laser", "RA", 5, List.of(impact("CT", 5)), null, 0));
            GpuReportLog.Snapshot playing = new GpuReportLog.Snapshot(4, GamePhase.FIRING_REPORT, entries, Map.of(),
                  combat, round.log().psr(), round.log().moves(), List.of());
            history.accept(List.of(miss, hit), scene(), playing, unit -> false);
            history.advance(new UnitAttack(miss).contactSeconds / UnitMotion.Speed.NORMAL.rate, ignored -> true);
            panel.show(hud, status(GamePhase.FIRING_REPORT, 4), playing);
            assertEquals(List.of("WEAPON ATTACKS", "3 events", "PILOTING ROLLS", "2 events", "HEAT & STATUS",
                  "2 events"), sections(panel.list()), "The Warhammer's shot waits for the Panther's");
            assertEquals(List.of("2", "HITS", "1", "MISS", "1", "HEAT ALERT"), texts(panel.find("log-totals")));
        });
    }

    @Test
    void layoutStaysInTheRightColumnAtTheThreeWindowSizes() {
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            Round round = shot10();
            play(panel.state.history, round);
            panel.state.history.reviewTo(panel.state.history.played().get(1));
            for (int[] size : new int[][] { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } }) {
                hud.size(size[0], size[1]);
                GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                // With the search row open, the tallest header the log has.
                panel.show(hud, status(GamePhase.END_REPORT), round.log());
                click(hud, panel.find("log-search"));
                panel.show(hud, status(GamePhase.END_REPORT), round.log());
                // y up, as the stage: the utility row and the chat button around the column.
                Rectangle utilities = new Rectangle(size[0] - metrics.gap() - 420, size[1] - 18 - 56, 420, 56);
                Rectangle chat = new Rectangle(size[0] - metrics.gap() - 80, 18, 80, 36);
                hud.capture("log-layout-" + size[0] + "x" + size[1]).dispose();
                hud.assertLayout(panel.root, utilities, chat);
                assertEquals(metrics.log(), panel.root.getWidth(), .5f);
                assertTrue(panel.<ScrollPane>find("log-list").isScrollY(), "The list scrolls at " + size[0]);
                click(hud, panel.find("log-search"));
            }
        });
    }

    /**
     * User item 30 over the opening of a real game (GpuRoundNumberTest.play): the phase header and the log's title and
     * round select show one round, 01 in the first combat round whether or not the game starts with a deployment and
     * 02 in the next. No round shows before the first: not in the header or the initiative card during the
     * start-of-game deployment, whose reports open round 1's log, nor in the board fixture's game, which has had no
     * initiative (once "ROUND -01").
     */
    @Test
    @SuppressWarnings("unchecked")
    void headerLogTitleAndRoundSelectShowTheRoundThePlayerSees() throws Exception {
        List<GpuRoundNumberTest.Shown> deployed = GpuRoundNumberTest.play(true, new ArrayList<>());
        List<GpuRoundNumberTest.Shown> placed = GpuRoundNumberTest.play(false, new ArrayList<>());
        GpuBoardSource.Frame unstarted;
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            unstarted = fixture.source.takeFrame();
        }
        GpuHudTestStage.run(hud -> {
            Panel panel = new Panel(hud);
            GpuPhaseHeader header = new GpuPhaseHeader(hud.kit, null, panel.state);
            GpuInitiativeCard card = new GpuInitiativeCard(hud.kit, null, panel.state);
            Container<Actor> slot = new Container<>(header.actor()).top().left().fillX();
            hud.window.addActor(slot);
            try {
                showRound(hud, panel, slot, new GpuRoundNumberTest.Shown(GamePhase.MOVEMENT, unstarted.status(),
                      unstarted.reports()), header);
                assertEquals(List.of("MOVEMENT"), texts(header.actor()), "A game before its first initiative");

                showRound(hud, panel, slot, phase(deployed, GamePhase.INITIATIVE_REPORT, 0), header, card);
                assertEquals(List.of("DEPLOYMENT ORDER", "TURN ORDER"), texts(header.actor()),
                      "The deployment's initiative");
                assertEquals("DEPLOYMENT ORDER", texts(card.actor()).getFirst());
                showRound(hud, panel, slot, phase(deployed, GamePhase.DEPLOYMENT, 0), header);
                assertEquals(List.of("DEPLOYMENT"), texts(header.actor()));
                assertEquals(List.of("ROUND 01 \u00B7 COMBAT LOG"), texts(panel.find("log-header")));
                hud.capture("log-round-deployment").dispose();
                showRound(hud, panel, slot, phase(deployed, GamePhase.INITIATIVE_REPORT, 1), header, card);
                assertEquals(List.of("ROUND 01", "INITIATIVE", "TURN ORDER"), texts(header.actor()));
                assertEquals("ROUND 1 \u00B7 INITIATIVE", texts(card.actor()).getFirst());
                showRound(hud, panel, slot, phase(deployed, GamePhase.MOVEMENT, 0), header);
                assertEquals(List.of("ROUND 01", "MOVEMENT"), texts(header.actor()));
                assertEquals(List.of("ROUND 01 \u00B7 COMBAT LOG"), texts(panel.find("log-header")));

                showRound(hud, panel, slot, phase(placed, GamePhase.MOVEMENT, 0), header);
                assertEquals(List.of("ROUND 01", "MOVEMENT"), texts(header.actor()), "MegaMek's round 0");
                assertEquals(List.of("ROUND 01 \u00B7 COMBAT LOG"), texts(panel.find("log-header")));
                showRound(hud, panel, slot, phase(placed, GamePhase.MOVEMENT, 1), header);
                assertEquals(List.of("ROUND 02", "MOVEMENT"), texts(header.actor()), "MegaMek's round 1");
                assertEquals(List.of("ROUND 02 \u00B7 COMBAT LOG", "Round 2"), texts(panel.find("log-header")));
                hud.capture("log-round-second").dispose();
                click(hud, panel.find("log-round"));
                ArgumentCaptor<List<String>> rounds = ArgumentCaptor.forClass(List.class);
                ArgumentCaptor<IntConsumer> choose = ArgumentCaptor.forClass(IntConsumer.class);
                verify(panel.menu).list(any(), rounds.capture(), eq(0), choose.capture());
                assertEquals(List.of("Round 2", "Round 1"), rounds.getValue(), "MegaMek's rounds 1 and 0");
                choose.getValue().accept(1);
                showRound(hud, panel, slot, phase(placed, GamePhase.MOVEMENT, 1), header);
                assertEquals(List.of("ROUND 01 \u00B7 BATTLE REPORT", "Round 1"), texts(panel.find("log-header")));
                assertEquals(List.of("ROUND 02", "MOVEMENT"), texts(header.actor()));
            } finally {
                header.dispose();
                card.dispose();
            }
        });
    }

    /** Draws the log, captures {name}.png and writes it beside the mock's same area. */
    private static void shoot(GpuHudTestStage hud, Panel panel, String name, String mock) {
        hud.draw();
        hud.assertLayout(panel.root);
        Pixmap image = hud.capture(name);
        try {
            hud.compare(name, image, panel.root, mock, LEFT, TOP);
        } finally {
            image.dispose();
        }
    }

    /**
     * One frame of the log and {@code components} from what the HUD captured after a phase change, the phase header in
     * GpuHud's top-left {@code slot}.
     */
    private static void showRound(GpuHudTestStage hud, Panel panel, Container<Actor> slot,
          GpuRoundNumberTest.Shown shown, GpuHud.Component... components) {
        panel.show(hud, shown.status(), shown.log());
        GpuHud.Metrics metrics = GpuHud.Metrics.of(hud.width(), hud.height());
        GpuHud.Inputs inputs = new GpuHud.Inputs(frame(shown.status(), shown.log()), GpuHud.HudView.EMPTY, null,
              panel.preferences, metrics, List.of());
        for (GpuHud.Component component : components) {
            component.update(inputs);
        }
        float height = slot.getPrefHeight();
        slot.setBounds(metrics.gap(), hud.height() - metrics.gap() - height, metrics.left(), height);
        slot.validate();
        hud.draw();
    }

    /** What the HUD captured after the change to {@code phase} numbered {@code occurrence}, from 0. */
    private static GpuRoundNumberTest.Shown phase(List<GpuRoundNumberTest.Shown> shown, GamePhase phase,
          int occurrence) {
        return shown.stream().filter(each -> each.phase() == phase).skip(occurrence).findFirst().orElseThrow();
    }

    /** The roll line (K7), "Needed {tn}+ \u00B7 rolled {roll}". */
    private static String roll(int needed, int rolled) {
        return Messages.getString(ROLL_KEY, needed, rolled);
    }

    /** A press and release at the actor's centre through the stage, as the board view hands HUD input on. */
    private static void click(GpuHudTestStage hud, Actor actor) {
        assertNotNull(actor);
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    /** The visible, non-empty label texts, including buttons except the header's playback-speed controls. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        collect(actor, texts);
        return texts;
    }

    private static void collect(Actor actor, List<String> texts) {
        if (actor == null || !actor.isVisible()
              || (actor.getName() != null && actor.getName().startsWith("phase-speed-"))) {
            return;
        }
        if (actor instanceof Label label && label.getText().length() > 0) {
            texts.add(label.getText().toString());
        }
        if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, texts));
        }
    }

    /** The section headers of the list's texts: an upper-case title followed by its count. */
    private static List<String> sections(List<String> texts) {
        List<String> sections = new ArrayList<>();
        for (int index = 0; index + 1 < texts.size(); index++) {
            String title = texts.get(index);
            if (title.equals(UiTheme.upper(title)) && texts.get(index + 1).matches("\\d+ events?")) {
                sections.add(title);
                sections.add(texts.get(index + 1));
            }
        }
        return sections;
    }

    /** The who line of every card. */
    private static List<String> whos(Panel panel) {
        return panel.cards().stream().map(card -> card.get(card.getFirst().matches("\\d+") ? 1 : 0)).toList();
    }

    // ------------------------------------------------------------------ the scripted rounds

    /** A round's log and the events the client animated for it. */
    private record Round(GpuReportLog.Snapshot log, List<BoardScene.Animation> events) { }

    /** Plays the round's events through the history at once, after a first frame at the round's start. */
    private static void play(GpuPlaybackHistory history, Round round) {
        GpuReportLog.Snapshot start = new GpuReportLog.Snapshot(round.log().round(), round.log().phase(), List.of(),
              Map.of(), List.of(), List.of(), List.of(), List.of());
        history.accept(List.of(), scene(), start, unit -> false);
        history.advance(0, ignored -> true);
        history.accept(round.events(), scene(), round.log(), unit -> false);
        history.skip();
        history.advance(0, ignored -> true);
    }

    /** Shot 08: the Atlas's first four shots at the King Crab. */
    private static Round shot08() {
        List<GpuReportLog.Entry> entries = new ArrayList<>();
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>();
        List<BoardScene.Animation> events = new ArrayList<>();
        shot(3, ATLAS, KING_CRAB, "AC/20", "RT", 0, List.of(), null, null, 8, 4,
              "AC/20 at King Crab; needs 8, rolls 4 : misses.", entries, combat, events);
        shot(3, ATLAS, KING_CRAB, "LRM 20", "LT", 12, List.of(impact("RL", 5), impact("LA", 5), impact("RA", 2)),
              12, "Standard", 4, 5, "LRM 20 at King Crab; needs 4, rolls 5 : 12 missiles hit.", entries, combat,
              events);
        shot(3, ATLAS, KING_CRAB, "SRM 6", "LT", 0, List.of(), null, null, 8, 7,
              "SRM 6 at King Crab; needs 8, rolls 7 : misses.", entries, combat, events);
        shot(3, ATLAS, KING_CRAB, "Medium Laser", "LA", 5, List.of(impact("CT", 5)), null, null, 8, 8,
              "Medium Laser at King Crab; needs 8, rolls 8 : hits, King Crab takes 5 damage to Center Torso.",
              entries, combat, events);
        return new Round(log(3, GamePhase.FIRING_REPORT, entries, combat), events);
    }

    /** Shot 10: the round's 29 attacks, 15 of them hits, two piloting rolls and two heat alerts. */
    private static Round shot10() {
        Round first = shot08();
        List<GpuReportLog.Entry> entries = new ArrayList<>(first.log().entries());
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>(first.log().combat());
        List<BoardScene.Animation> events = new ArrayList<>(first.events());
        shot(3, ATLAS, KING_CRAB, "Medium Laser", "RA", 0, List.of(), null, null, 8, 4,
              "Medium Laser at King Crab; needs 8, rolls 4 : misses.", entries, combat, events);
        shot(3, TIMBER_WOLF, ATLAS, "ER Large Laser", "LA", 10, List.of(impact("LT", 10)), null, null, 6, 9,
              "ER Large Laser at Atlas; needs 6, rolls 9 : hits.", entries, combat, events);
        int[] attackers = { WARHAMMER, KING_CRAB, MARAUDER, BATTLEMASTER, PANTHER, TIMBER_WOLF };
        for (int index = 0; index < 23; index++) {
            int attacker = attackers[index % attackers.length];
            int target = attacker == KING_CRAB || attacker == BATTLEMASTER || attacker == TIMBER_WOLF ? ATLAS
                  : KING_CRAB;
            boolean hit = index < 12;
            shot(3, attacker, target, "Medium Laser", "RA", hit ? 5 : 0, hit ? List.of(impact("CT", 5)) : List.of(),
                  null, null, 8, hit ? 9 : 5, "Medium Laser at " + NAMES.get(target) + ".", entries, combat, events);
        }
        GpuReportLog.PsrItem kingCrab = new GpuReportLog.PsrItem(3L << 32 | 900, 3, GamePhase.FIRING, KING_CRAB, 5, 7,
              true, "took 20+ damage");
        GpuReportLog.PsrItem marauder = new GpuReportLog.PsrItem(3L << 32 | 901, 3, GamePhase.FIRING, MARAUDER, 6, 4,
              false, "was kicked");
        entries.add(psrEntry(3, kingCrab, false));
        entries.add(psrEntry(3, marauder, true));
        entries.add(heatEntry(3, MARAUDER, 15, 19, 4, true));
        entries.add(heatEntry(3, WARHAMMER, 14, 18, 4, true));
        return new Round(new GpuReportLog.Snapshot(3, GamePhase.END_REPORT, entries, Map.of(), combat,
              List.of(kingCrab, marauder), List.of(), List.of()), events);
    }

    /**
     * Round 4's scripted report set: an initiative line, the Atlas's move, an own and an enemy attack, an enemy and
     * an own piloting roll, an own heat alert and an enemy's warm heat line.
     */
    private static Round scripted() {
        List<GpuReportLog.Entry> entries = new ArrayList<>();
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>();
        List<BoardScene.Animation> events = new ArrayList<>();
        entries.add(new GpuReportLog.Entry(4, "Initiative Phase", GamePhase.INITIATIVE, "", "Team 1 rolls a 9.",
              List.of(), "", List.of(), GpuReportLog.Kind.INITIATIVE, Entity.NONE, Entity.NONE, null, List.of(), false,
              false, null, null, null, null, null, null, null, false, false));
        shot(4, ATLAS, TIMBER_WOLF, "Medium Laser", "LA", 5, List.of(impact("CT", 5)), null, null, 7, 9,
              "Medium Laser at Timber Wolf; needs 7, rolls 9 : hits.", entries, combat, events);
        shot(4, TIMBER_WOLF, ATLAS, "ER Large Laser", "RA", 10, List.of(impact("LA", 10)), null, null, 6, 8,
              "ER Large Laser at Atlas; needs 6, rolls 8 : hits. LEFT ARM DESTROYED.", entries, combat, events);
        GpuReportLog.Entry destroyed = entries.getLast();
        entries.set(entries.size() - 1, new GpuReportLog.Entry(4, destroyed.phase(), destroyed.gamePhase(), "",
              destroyed.text(), destroyed.units(), "", List.of(), destroyed.kind(), destroyed.attackerId(),
              destroyed.targetId(), destroyed.attack(), List.of(), true, false, 6, 8, null, null, null, null, null,
              true, false));
        GpuReportLog.PsrItem kingCrab = new GpuReportLog.PsrItem(4L << 32 | 50, 4, GamePhase.FIRING, KING_CRAB, 5, 9,
              true, "took 20+ damage");
        GpuReportLog.PsrItem atlas = new GpuReportLog.PsrItem(4L << 32 | 51, 4, GamePhase.FIRING, ATLAS, 6, 4, false,
              "leg destroyed");
        entries.add(psrEntry(4, kingCrab, false));
        entries.add(psrEntry(4, atlas, true));
        entries.add(heatEntry(4, MARAUDER, 15, 19, 4, true));
        entries.add(heatEntry(4, TIMBER_WOLF, 4, 8, 4, false));
        GpuReportLog.MoveEvent move = new GpuReportLog.MoveEvent(1, 4, ATLAS, "Walked", 2, 2, new Coords(13, 13),
              new Coords(13, 11), 0);
        return new Round(new GpuReportLog.Snapshot(4, GamePhase.END_REPORT, entries, Map.of(), combat,
              List.of(kingCrab, atlas), List.of(move), List.of()), events);
    }

    /** One shot: its event as the playback gets it, its combat event and its linked report entry. */
    private static void shot(int round, int attacker, int target, String weapon, String location, int damage,
          List<ResolvedAttack.Impact> impacts, Integer missileHits, String ammo, int needed, int rolled, String text,
          List<GpuReportLog.Entry> entries, List<GpuReportLog.CombatEvent> combat, List<BoardScene.Animation> events) {
        BoardScene.Combat shot = attack(attacker, target, damage > 0);
        events.add(shot);
        combat.add(event(round, shot, weapon, location, damage, impacts, missileHits, missileHits == null ? 0 : 20));
        entries.add(attackEntry(round, shot, weapon, location, ammo, null, text, needed, rolled));
    }

    private static BoardScene.Combat attack(int attacker, int target, boolean hit) {
        return UnitPlaybackFixture.attack(UnitPlaybackFixture.unit(attacker, attacker), UnitPlaybackFixture.unit(target, target),
              ResolvedAttack.Kind.SHOT, hit);
    }

    private static GpuReportLog.CombatEvent event(int round, BoardScene.Combat shot, String weapon, String location,
          int damage, List<ResolvedAttack.Impact> impacts, Integer missileHits, int missiles) {
        return new GpuReportLog.CombatEvent(shot.result().id(), round, GamePhase.FIRING, ResolvedAttack.Kind.SHOT,
              shot.attacker().id(), shot.target().id(), weapon, location, damage > 0, damage, impacts, missileHits,
              missiles);
    }

    private static GpuReportLog.Entry attackEntry(int round, BoardScene.Combat shot, String weapon, String location,
          String ammo, String notFired, String text, int needed, int rolled) {
        int attacker = shot.attacker().id();
        int target = shot.target().id();
        UUID linked = notFired == null ? shot.result().id() : null;
        return new GpuReportLog.Entry(round, "Ranged Attack Phase", GamePhase.FIRING, "", text,
              List.of(unit(attacker), unit(target)), "", List.of(), GpuReportLog.Kind.WEAPON, attacker, target, linked,
              List.of(), false, false, needed, rolled, notFired, ammo, null, null, null, false, false);
    }

    private static GpuReportLog.Entry psrEntry(int round, GpuReportLog.PsrItem roll, boolean critical) {
        return new GpuReportLog.Entry(round, "Ranged Attack Phase", GamePhase.FIRING, "",
              NAMES.get(roll.entityId()) + " must make a piloting skill roll (" + roll.reasons() + ").",
              List.of(unit(roll.entityId())), "", List.of(), GpuReportLog.Kind.PSR, Entity.NONE, Entity.NONE, null,
              List.of(roll.id()), critical, false, null, null, null, null, null, null, null, false, false);
    }

    private static GpuReportLog.Entry heatEntry(int round, int unit, int heat, int gained, int sunk, boolean alert) {
        return new GpuReportLog.Entry(round, "Heat Phase", GamePhase.END, "", NAMES.get(unit) + " gains " + gained
              + " heat, sinks " + sunk + " heat and is now at " + heat + " heat.", List.of(unit(unit)), "", List.of(),
              GpuReportLog.Kind.HEAT, Entity.NONE, Entity.NONE, null, List.of(), alert, alert, alert ? 8 : null,
              alert ? 5 : null, null, null, heat, gained, sunk, false, false);
    }

    /** A line of no kind the summary groups, as the initiative report gives one. */
    private static GpuReportLog.Entry other(int round) {
        return new GpuReportLog.Entry(round, "Initiative Phase", GamePhase.INITIATIVE, "", "Team 1 rolls a 9.",
              List.of(), "", List.of(), GpuReportLog.Kind.INITIATIVE, Entity.NONE, Entity.NONE, null, List.of(), false,
              false, null, null, null, null, null, null, null, false, false);
    }

    private static GpuReportLog.Unit unit(int id) {
        return new GpuReportLog.Unit(id, NAMES.get(id));
    }

    private static ResolvedAttack.Impact impact(String location, int weight) {
        return new ResolvedAttack.Impact(location, false, weight);
    }

    private static GpuReportLog.Snapshot log(int round, GamePhase phase, List<GpuReportLog.Entry> entries,
          List<GpuReportLog.CombatEvent> combat) {
        return new GpuReportLog.Snapshot(round, phase, entries, Map.of(), combat, List.of(), List.of(), List.of());
    }

    /** The board the events play on: one hex, as the playback only needs the scene's board. */
    private static BoardScene scene() {
        return UnitPlaybackFixture.scene(Stream.of(ATLAS, WARHAMMER, MARAUDER, PANTHER, TIMBER_WOLF, KING_CRAB,
              BATTLEMASTER).map(id -> UnitPlaybackFixture.unit(id, id)).toArray(BoardScene.Unit[]::new));
    }

    private static GpuBattleStatus.Snapshot status(GamePhase phase) {
        return status(phase, 3);
    }

    private static GpuBattleStatus.Snapshot status(GamePhase phase, int round) {
        return new GpuBattleStatus.Snapshot(round, phase, false, MOCK.localPlayerId(), Entity.NONE, MOCK.turns(), -1,
              MOCK.units(), List.of(), false);
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuReportLog.Snapshot reports) {
        GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
        return new GpuBoardSource.Frame(null, List.of(), null, List.of(), "", null, 0, "", null, reports,
              status, panels);
    }
}
