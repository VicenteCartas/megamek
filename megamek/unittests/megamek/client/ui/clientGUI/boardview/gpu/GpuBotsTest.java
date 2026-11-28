/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorChange;
import megamek.client.bot.princess.BehaviorChange.Trait;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BotOrder;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.Tactic;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.lobby.BotSetupForm.Presets;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.Control;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.force.Force;
import megamek.common.game.BotStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.orders.ContactRule;
import megamek.common.orders.FormationOrder;
import megamek.common.orders.FormationPace;
import megamek.common.orders.FormationShape;
import megamek.common.orders.OrderPriority;
import megamek.common.orders.UnitOrderRequest;
import megamek.common.orders.UnitOrders;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The bot sheet's services: which bots the local player may command and what each shows (GpuBots), and the typed
 * messages its commands send, each marked as sending until the bot reports (GpuBotOrders).
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(180)
class GpuBotsTest {

    /** The services over the fixture's game, the messages sent, and a clock the test moves. */
    private record Sheet(GpuBots bots, GpuBotOrders orders, List<Object> sent, AtomicLong clock, Player princess,
          BehaviorSettings settings) { }

    @Test
    void alliedBotsShowFirstAndAnEnemyBotOnlyForAGameMaster() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            GpuBots.Snapshot shown = GpuTestSupport.onSwing(sheet.bots()::capture);
            assertEquals(List.of("Princess", "Bot Two"), names(shown), "allied bots only, the human players never");
            assertTrue(shown.bots().stream().allMatch(GpuBots.BotRow::allied));
            assertSame(shown, GpuTestSupport.onSwing(sheet.bots()::capture), "an unchanged capture keeps its instance");

            SwingUtilities.invokeAndWait(() -> fixture.player.setGameMaster(true));
            GpuBots.Snapshot master = GpuTestSupport.onSwing(sheet.bots()::capture);
            assertEquals(List.of("Princess", "Bot Two", "Enemy bot"), names(master),
                  "a game master commands every bot, allied ones first");
            assertFalse(master.bot(5).allied());
        }
    }

    @Test
    void noCommandableBotMeansAnEmptySnapshot() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getPlayer(3).setTeam(2);
                fixture.game.getPlayer(6).setTeam(2);
            });
            GpuBots.Snapshot shown = GpuTestSupport.onSwing(sheet.bots()::capture);
            assertTrue(shown.bots().isEmpty(), "the bot button hides without a bot to command");
            assertTrue(shown.personalities().isEmpty());
        }
    }

    @Test
    void theRowShowsTheReportedBehaviorAndTheEnemiesWhileTheSheetIsOpen() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            GpuBots.BotRow closed = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertEquals(0, closed.units());
            assertTrue(closed.enemies().isEmpty() && closed.enemyPlayers().isEmpty(),
                  "nothing is listed while the sheet is closed");
            GpuBots.Behavior behavior = closed.behavior();
            assertEquals(Personality.BALANCED, behavior.personality(), "the settings match the Balanced preset");
            assertEquals(CombatPosture.AUTO, behavior.posture());
            assertEquals(5, behavior.traits().get(Trait.AGGRESSION));
            assertEquals(CardinalEdge.NONE, behavior.retreat());
            assertEquals(Map.of(9, 2), behavior.priorities());
            assertNull(GpuTestSupport.onSwing(sheet.bots()::capture).bot(6).behavior(),
                  "a bot that has not reported has no behavior yet");

            sheet.bots().setSheetOpen(true);
            SwingUtilities.invokeAndWait(() -> fixture.game.setDishonoredPlayers(3, List.of(4)));
            GpuBots.BotRow open = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertEquals(1, open.units());
            assertFalse(open.tag(), "an Atlas carries no TAG");
            assertEquals(List.of(new GpuBots.Enemy(9, unitName(fixture, 9), 4, false)), open.enemies());
            assertEquals(List.of("Opponent true", "Enemy bot false"), open.enemyPlayers().stream()
                  .map(player -> player.name() + " " + player.dishonored()).toList());
        }
    }

    @Test
    void aCommandShowsWhatWasAskedUntilTheBotReports() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            GpuTestSupport.onSwing(sheet.bots()::capture);
            sheet.orders().posture(3, CombatPosture.DEFEND);
            sheet.orders().hold(3, true);
            assertEquals(List.of("3 " + List.of(new BehaviorChange.Posture(CombatPosture.DEFEND)),
                  "3 " + new BotOrder.Hold(true)), sheet.sent(), "typed messages, never chat");
            GpuBots.BotRow sending = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertEquals(Set.of(Control.POSTURE, Control.HOLD), sending.sending());
            assertEquals(CombatPosture.DEFEND, sending.behavior().posture(), "the asked posture shows meanwhile");
            assertTrue(sending.status().holdingPosition());

            // The bot reports: its settings, then its status, a new instance.
            SwingUtilities.invokeAndWait(() -> {
                sheet.settings().setCombatPosture(CombatPosture.DEFEND);
                fixture.game.setBotStatus(3, new BotStatus(true, Set.of(), Set.of()));
            });
            GpuBots.BotRow reported = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertFalse(reported.isSending());
            assertFalse(reported.noReply());
            assertEquals(CombatPosture.DEFEND, reported.behavior().posture());
            assertTrue(reported.status().holdingPosition());
        }
    }

    @Test
    void aBotThatDoesNotReportShowsItsReportedValuesAgainMarkedNoReply() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            sheet.orders().trait(3, Trait.BRAVERY, 9);
            GpuBots.BotRow sending = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertEquals(9, sending.behavior().traits().get(Trait.BRAVERY));
            assertNull(sending.behavior().personality(), "a changed index makes a custom mix");

            sheet.clock().addAndGet(GpuBots.NO_REPLY_NANOS + 1);
            GpuBots.BotRow silent = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertFalse(silent.isSending());
            assertTrue(silent.noReply());
            assertEquals(5, silent.behavior().traits().get(Trait.BRAVERY), "back to what the bot reported");

            SwingUtilities.invokeAndWait(() -> fixture.game.setBotStatus(3, new BotStatus(false, Set.of(), Set.of())));
            assertFalse(GpuTestSupport.onSwing(sheet.bots()::capture).bot(3).noReply(), "a later report clears it");
        }
    }

    @Test
    void aPersonalitySendsItsPresetsValues() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            sheet.orders().personality(3, Personality.BERSERKER);
            assertEquals(1, sheet.sent().size());
            GpuBots.BotRow sending = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertEquals(Personality.BERSERKER, sending.behavior().personality());
            assertEquals(10, sending.behavior().traits().get(Trait.AGGRESSION));
            assertEquals(Set.of(Control.PERSONALITY), sending.sending());
        }
    }

    @Test
    void noPrisonersForgivesNoOne() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            sheet.orders().tactic(3, Tactic.NO_PRISONERS);
            assertEquals(List.of("3 " + List.of(new BehaviorChange.ClearIgnored()), "3 " + new BotOrder.BloodFeud(4),
                  "3 " + new BotOrder.BloodFeud(5)), sheet.sent(), "a blood feud against each enemy player");

            sheet.sent().clear();
            sheet.orders().tactic(3, Tactic.ALPHA_STRIKE);
            assertEquals(List.of("3 " + Tactic.ALPHA_STRIKE.indices()), sheet.sent(), "only the tactic's indices");
            GpuBots.BotRow sending = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            Tactic.ALPHA_STRIKE.indices().forEach(index -> assertEquals(index.value(),
                  sending.behavior().traits().get(index.trait())));
        }
    }

    @Test
    void alliedBotsListTheirLancesAndEachUnitsOrders() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            GpuBots.BotRow princess = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertFalse(princess.lancesSelectable(), "Princess takes orders for single units only");
            assertEquals(List.of(new GpuBots.Lance(Force.NO_FORCE, "Unassigned", List.of(8), -1,
                  "No waypoints")), princess.lances());
            assertEquals(new GpuBots.Order(Force.NO_FORCE, "No waypoints", false, false, 0, false, false),
                  princess.orders().get(8));

            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setBotStatus(3, new BotStatus(false, Set.of(), Set.of(),
                      Map.of(8, List.of(new Coords(11, 5)))));
                Player owner = fixture.game.getPlayer(3);
                int alpha = fixture.game.getForces().addTopLevelForce(Force.createToplevelForce("Alpha", owner),
                      owner);
                fixture.game.getForces().addEntity(fixture.game.getEntity(8), alpha);
            });
            GpuBots.Snapshot shown = GpuTestSupport.onSwing(sheet.bots()::capture);
            GpuBots.Lance alpha = shown.bot(3).lances().get(0);
            assertEquals("Alpha", alpha.name());
            assertEquals("Waypoints \u00b7 1206", shown.bot(3).orders().get(8).text(), "the bot's report names them");
            assertSame(shown.bot(3), shown.alliedOwnerOf(8));
            assertNull(shown.alliedOwnerOf(9), "an enemy unit has no allied owner");
        }
    }

    @Test
    void aCasparLanceIsSelectableAndFollowsItsFormationLeader() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setBotTypes(Map.of("Princess", AIType.CASPAR));
                try {
                    unit(fixture, 10, sheet.princess(), new Coords(7, 6));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                Player owner = sheet.princess();
                int alpha = fixture.game.getForces().addTopLevelForce(Force.createToplevelForce("Alpha", owner),
                      owner);
                for (int id : List.of(8, 10)) {
                    Entity member = fixture.game.getEntity(id);
                    fixture.game.getForces().addEntity(member, alpha);
                    member.setUnitOrders(UnitOrders.NONE.withRoute(List.of(new Coords(11, 5)))
                          .withFormation(new FormationOrder(FormationShape.WEDGE, 8, 2, id == 8 ? 0 : 1,
                                FormationPace.WALK, ContactRule.HOLD)));
                }
            });
            GpuBots.BotRow caspar = GpuTestSupport.onSwing(sheet.bots()::capture).bot(3);
            assertTrue(caspar.lancesSelectable());
            GpuBots.Lance alpha = caspar.lances().get(0);
            assertEquals(List.of(8, 10), alpha.units());
            assertEquals(8, alpha.leaderId());
            assertEquals("Route to 1206 \u00b7 1 waypoint \u00b7 Wedge", alpha.orders(), "the leader's orders");
            assertTrue(caspar.orders().get(8).leader());
            assertFalse(caspar.orders().get(8).follows());
            assertTrue(caspar.orders().get(10).follows(), "the wingman follows the leader");
            assertSame(alpha, caspar.lanceOf(10));
        }
    }

    @Test
    void aCasparUnitSentAloneLeavesItsFormationAndALanceKeepsIt() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            casparLance(fixture, sheet);
            List<Coords> hex = List.of(new Coords(11, 5));
            SwingUtilities.invokeAndWait(() -> sheet.orders().routeOrders(3, List.of(10), hex, false));
            assertEquals(List.of("unit 10 FORMATION_OFF", "unit 10 ROUTE " + hex), sheet.sent(),
                  "the wingman goes where it is sent, not back to its leader");

            sheet.sent().clear();
            SwingUtilities.invokeAndWait(() -> sheet.orders().routeOrders(3, List.of(8, 10), hex, false));
            assertEquals(List.of("unit 8 ROUTE " + hex, "unit 10 ROUTE " + hex), sheet.sent(),
                  "a lance moves in its formation");

            sheet.sent().clear();
            SwingUtilities.invokeAndWait(() -> sheet.orders().routeOrders(3, List.of(10), hex, true));
            assertEquals(List.of("unit 10 ADD " + hex), sheet.sent(), "adding a waypoint keeps the formation");
        }
    }

    @Test
    void aPrincessUnitTakesWaypoints() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            List<Coords> hexes = List.of(new Coords(11, 5), new Coords(12, 5));
            SwingUtilities.invokeAndWait(() -> sheet.orders().routeOrders(3, List.of(8), hexes, false));
            SwingUtilities.invokeAndWait(() -> sheet.orders().routeOrders(3, List.of(8), hexes, true));
            sheet.orders().removeLastWaypoint(3, List.of(8));
            sheet.orders().clearOrders(3, List.of(8));
            assertEquals(List.of("3 " + new BotOrder.SetWaypoints(8, hexes), "3 " + new BotOrder.AddWaypoints(8, hexes),
                  "3 " + new BotOrder.RemoveLastWaypoint(8), "3 " + new BotOrder.ClearWaypoints(8)), sheet.sent());
        }
    }

    @Test
    void casparOrdersGoAsUnitOrders() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            casparLance(fixture, sheet);
            sheet.orders().holdUnits(3, List.of(8, 10));
            sheet.orders().resume(3, List.of(8));
            sheet.orders().clearOrders(3, List.of(10));
            sheet.orders().removeLastWaypoint(3, List.of(10));
            sheet.orders().exitBy(3, List.of(8), OffBoardDirection.EAST);
            sheet.orders().routePriority(3, List.of(8), OrderPriority.IMPERATIVE);
            assertEquals(List.of("unit 8 HOLD_POSITION", "unit 10 HOLD_POSITION", "unit 8 RESUME", "unit 10 CLEAR",
                  "unit 10 REMOVE_LAST", "unit 8 EXIT_BY_EDGE EAST", "unit 8 PRIORITY IMPERATIVE"), sheet.sent());
        }
    }

    @Test
    void aCasparLanceTakesTheLobbysFormationAndAUnitRejoinsAfterTheLast() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            casparLance(fixture, sheet);
            sheet.orders().formation(3, List.of(8, 10), FormationShape.COLUMN);
            sheet.orders().formation(3, List.of(8, 10), null);
            sheet.orders().formation(3, List.of(8), FormationShape.COLUMN);
            assertEquals(List.of("unit 8 FORMATION COLUMN leader 8 slot 0", "unit 10 FORMATION COLUMN leader 8 slot 1",
                  "unit 8 FORMATION_OFF", "unit 10 FORMATION_OFF"), sheet.sent(), "one unit makes no formation");

            sheet.sent().clear();
            SwingUtilities.invokeAndWait(() -> fixture.game.getEntity(10).setUnitOrders(UnitOrders.NONE));
            sheet.orders().rejoin(3, 10, 8);
            assertEquals(List.of("unit 10 FORMATION WEDGE leader 8 slot 1", "unit 10 ROUTE [" + new Coords(11, 5)
                  + "] " + OrderPriority.NORMAL), sheet.sent(), "its place and the leader's route");
        }
    }

    @Test
    void retreatTakesTheBotsEdgeOrTheNearestOne() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            casparLance(fixture, sheet);
            SwingUtilities.invokeAndWait(() -> fixture.game.getEntity(8).setPosition(new Coords(0, 5)));
            sheet.orders().retreatUnits(3, List.of(8, 10));
            sheet.settings().setRetreatEdge(CardinalEdge.SOUTH);
            sheet.orders().retreatUnits(3, List.of(8));
            assertEquals(List.of("unit 8 EXIT_BY_EDGE WEST", "unit 10 EXIT_BY_EDGE WEST", "unit 8 EXIT_BY_EDGE SOUTH"),
                  sheet.sent(), "the lead unit stands on the west edge");
        }
    }
    @Test
    void ignoreTurretsListsNoTurretOnABoardWithout() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Sheet sheet = GpuTestSupport.onSwing(() -> sheet(fixture));
            sheet.orders().ignoreTurrets(3);
            assertTrue(sheet.sent().isEmpty(), "nothing to ignore, nothing sent");
            sheet.orders().priority(3, 9, 0);
            assertTrue(GpuTestSupport.onSwing(sheet.bots()::capture).bot(3).behavior().priorities().isEmpty(),
                  "priority 0 takes the unit off the list");
        }
    }

    /**
     * EDT: the fixture's local player (team 1) with the allied bots Princess (an Atlas, reported settings matching
     * Balanced, a priority target) and Bot Two (no report), the enemy player Opponent with an Atlas, and an enemy bot;
     * the services over a source whose commands run on the EDT, with presets for Balanced and Berserker.
     */
    private static Sheet sheet(GpuBoardFixture fixture) throws Exception {
        Player princess = player(fixture, 3, "Princess", 1, true);
        Player opponent = player(fixture, 4, "Opponent", 2, false);
        player(fixture, 5, "Enemy bot", 2, true);
        player(fixture, 6, "Bot Two", 1, true);
        unit(fixture, 8, princess, new Coords(6, 6));
        unit(fixture, 9, opponent, new Coords(9, 9));
        BehaviorSettings balanced = preset(Personality.BALANCED, 5);
        BehaviorSettings berserker = preset(Personality.BERSERKER, 10);
        BehaviorSettings settings = balanced.getCopy();
        settings.setPriorityUnit(9, 2);
        Map<String, BehaviorSettings> reported = new HashMap<>();
        reported.put("Princess", settings);
        fixture.game.setBotSettings(reported);
        fixture.game.setBotStatus(3, new BotStatus(false, Set.of(), Set.of()));

        GpuBoardSource source = mock(GpuBoardSource.class);
        when(source.currentView()).thenReturn(fixture.view);
        when(source.acceptsInput()).thenReturn(true);
        doAnswer(call -> {
            SwingUtilities.invokeAndWait(call.getArgument(0));
            return null;
        }).when(source).command(any(Runnable.class));
        AtomicLong clock = new AtomicLong();
        GpuBots bots = new GpuBots(source, () -> presets(balanced, berserker), clock::get);
        List<Object> sent = new CopyOnWriteArrayList<>();
        GpuBotOrders orders = new GpuBotOrders(source, bots, new GpuBotOrders.Sender() {
            @Override
            public void behavior(int botId, List<BehaviorChange> changes) {
                sent.add(botId + " " + changes);
            }

            @Override
            public void order(int botId, BotOrder order) {
                sent.add(botId + " " + order);
            }

            @Override
            public void units(List<UnitOrderRequest> orders) {
                orders.forEach(request -> sent.add("unit " + request.unitId() + " " + request.action()
                      + (request.hexes().isEmpty() ? "" : " " + request.hexes())
                      + (request.edge() == OffBoardDirection.NONE ? "" : " " + request.edge())
                      + (request.priority() == null ? "" : " " + request.priority())
                      + (request.formation() == null ? "" : " " + request.formation().getShape() + " leader "
                      + request.formation().getLeaderId() + " slot " + request.formation().getSlot())));
            }
        });
        return new Sheet(bots, orders, sent, clock, princess, settings);
    }

    /** EDT work: makes Princess a CASPAR bot whose lance Alpha is unit 8 leading unit 10 in a wedge to 1206. */
    private static void casparLance(GpuBoardFixture fixture, Sheet sheet) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.setBotTypes(Map.of("Princess", AIType.CASPAR));
            try {
                unit(fixture, 10, sheet.princess(), new Coords(7, 6));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            for (int id : List.of(8, 10)) {
                fixture.game.getEntity(id).setUnitOrders(UnitOrders.NONE.withRoute(List.of(new Coords(11, 5)))
                      .withFormation(new FormationOrder(FormationShape.WEDGE, 8, 2, id == 8 ? 0 : 1,
                            FormationPace.WALK, ContactRule.HOLD)));
            }
        });
    }

    private static BehaviorSettings preset(Personality personality, int aggression) throws Exception {
        BehaviorSettings preset = new BehaviorSettings();
        preset.setDescription(personality.preset());
        preset.setHyperAggressionIndex(aggression);
        return preset;
    }

    private static Presets presets(BehaviorSettings... stored) {
        Map<String, BehaviorSettings> byName = new HashMap<>();
        for (BehaviorSettings each : stored) {
            byName.put(each.getDescription(), each);
        }
        return new Presets() {
            @Override
            public List<String> names() {
                return new ArrayList<>(byName.keySet());
            }

            @Override
            public BehaviorSettings get(String name) {
                return byName.get(name);
            }

            @Override
            public void save(BehaviorSettings preset) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void remove(String name) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static List<String> names(GpuBots.Snapshot snapshot) {
        return snapshot.bots().stream().map(GpuBots.BotRow::name).toList();
    }

    private static String unitName(GpuBoardFixture fixture, int id) throws Exception {
        Entity entity = GpuTestSupport.onSwing(() -> fixture.game.getEntity(id));
        assertNotNull(entity);
        return entity.getShortName();
    }

    private static Player player(GpuBoardFixture fixture, int id, String name, int team, boolean bot) {
        Player player = new Player(id, name);
        player.setTeam(team);
        player.setBot(bot);
        fixture.game.addPlayer(id, player);
        return player;
    }

    private static void unit(GpuBoardFixture fixture, int id, Player owner, Coords position) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf"))
              .getEntity();
        entity.setId(id);
        entity.setOwner(owner);
        entity.setPosition(position);
        entity.setDeployed(true);
        fixture.game.addEntity(entity, false);
    }
}
