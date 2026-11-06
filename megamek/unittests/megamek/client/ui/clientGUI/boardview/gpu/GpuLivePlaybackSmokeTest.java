/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuLiveBoardSpaceSmokeTest.Live;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The playback history in the live battle view (rebuild plan I1c; C.6, A.10): a real GpuBattleView draws a real
 * source's captures of the firing fixture, with a scripted firing report over them. In it the Atlas's laser hits the
 * Archer, then the Quickdraw's volley hits the Atlas twice and misses once; the client has applied the Quickdraw's
 * damage when the report begins, as MegaMek's server has. The units the HUD shows change only once the volley has
 * played; the log's totals, the forces list's review line and the board's pop-ups follow each shot as it lands; a
 * review after a concealment re-presents nothing of the hidden unit; live events that arrive during a review play
 * after it, in their order; and a unit grown when zoomed out walks with strides as much longer.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuLivePlaybackSmokeTest {
    private static final int ATLAS = 1;
    private static final int ARCHER = 42;
    private static final int QUICKDRAW = 45;
    private static final Coords ATLAS_HEX = new Coords(5, 5);
    /** One frame of the tests' playback, at the dock's default speed. */
    private static final float FRAME = 1 / 30f;
    /** More frames than any of the scripted actions takes. */
    private static final int LIMIT = 900;

    /**
     * C.6 and the log, forces and labels during live playback: the Atlas's armor drop shows on its card, its forces
     * tile and its overview card only after the Quickdraw's shots played; each shot counts in the log's totals and
     * pops up at its target as it lands. The Atlas's forces line holds the phase from before the report, as the whole
     * HUD does, and counts the shots once they have played. The dock's speed and pause steer the board's playback, and
     * a paused playback holds its newest pop-up.
     */
    @Test
    void aVolleyShowsItsDamageOnlyOnceItHasPlayed() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            GpuLiveBoardSpaceSmokeTest.run(firing.board.source, live -> {
                Playback play = new Playback(live);
                Volley volley = play.report(firing);
                GpuUnitRecord.Location before = location(volley.before(), "CT");
                GpuUnitRecord.Location after = location(volley.after(), "CT");
                assertEquals(before.armor() - 10, after.armor(), "The client applied the volley");
                String lost = "\u2212" + 10;

                // Nothing has landed: the HUD still shows the units as they were.
                assertTrue(play.history.running());
                assertEquals(1, play.atlas().armor(), "The Atlas's armor as before the volley");
                List<String> doll = play.texts("unit-card-doll");
                assertTrue(doll.contains(String.valueOf(before.armor())) && !doll.contains(lost), () -> "" + doll);
                assertEquals(List.of("0", "0"), play.totals(), "The log counts no shot yet");
                String counted = Messages.getString("GpuBoard.hud.status.attacks", 1, 1);
                assertTrue(play.texts("forces-unit-" + ATLAS).stream().noneMatch(text -> text.contains(counted)),
                      "The Atlas's review line counts no shot yet");

                // The phase header's speeds are the board's: at 2x a shot's clock runs twice as fast.
                play.click(live.actor("phase-speed-double"));
                assertTrue(play.until(() -> play.playback.attack() != null && play.playback.attack().seconds > 0));
                UnitAttack shooting = play.playback.attack();
                float clock = shooting.seconds;
                play.frames(1);
                assertEquals(FRAME * UnitMotion.Speed.DOUBLE.rate, shooting.seconds - clock, 1e-4,
                      "The board plays at the header's speed");
                play.click(live.actor("phase-speed-normal"));

                // The Atlas's shot lands at the Archer.
                assertTrue(play.until(() -> play.played(volley.shots().getFirst())), "The Atlas's shot lands");
                assertEquals(List.of(hit(5, "CT")), play.popUps, "Its pop-up");
                play.assertAtTarget(ARCHER);
                assertEquals(List.of("1", "0"), play.totals());
                assertTrue(play.texts("forces-unit-" + ATLAS).stream().noneMatch(text -> text.contains(counted)),
                      "The forces line holds the phase from before the report until the playback ends");
                assertEquals(1, play.atlas().armor(), "The Quickdraw's volley still waits: no damage shows");

                // The dock pauses the board: nothing more lands, and the newest pop-up stays, held young.
                play.click(live.actor("dock-play"));
                assertTrue(play.playback.paused());
                Actor popUp = play.newest();
                play.frames(90);
                assertEquals(1, play.history.played().size(), "Paused, no further shot lands");
                assertNotNull(popUp.getStage(), "The pop-up outlasts its time while the playback is paused");
                assertTrue((float) field(popUp, "age") <= .35f, "and does not age");
                play.click(live.actor("dock-play"));
                assertFalse(play.playback.paused());

                // The Quickdraw's first hit lands at the Atlas; its other shots land on their own clocks.
                assertTrue(play.until(() -> play.played(volley.shots().get(1))));
                assertTrue(play.popUps.contains(hit(10, "CT")), () -> "Its pop-up: " + play.popUps);
                play.assertAtTarget(ATLAS);
                assertEquals(play.landed(), play.totals(), "The totals count the shots landed so far");
                live.capture("i1c-volley-live.png");
                assertTrue(play.history.running());
                assertEquals(1, play.atlas().armor(), "The volley is not over: the units are still held (C.6)");
                assertFalse(play.texts("unit-card-doll").contains(lost));

                // Once the volley has played, the HUD shows the client's units.
                assertTrue(play.until(() -> !play.history.running()), "The playback ends");
                assertEquals(List.of("3", "1"), play.totals());
                assertTrue(play.texts("forces-unit-" + ATLAS).stream().anyMatch(text -> text.contains(counted)),
                      () -> "The Atlas's review line counts its shot: " + play.texts("forces-unit-" + ATLAS));
                assertEquals(Set.of(hit(5, "CT"), hit(10, "CT"), hit(5, "LT"), UiTheme.upper(
                      Messages.getString("GpuBoard.hud.common.miss")) + " "
                      + Messages.getString("GpuBoard.hud.labels.missRoll", 7, 9)), Set.copyOf(play.popUps),
                      "Each shot popped up once, the miss with its roll");
                assertEquals(4, play.popUps.size());
                assertTrue(play.atlas().armor() < 1, "The Atlas's armor drop shows");
                List<String> damaged = play.texts("unit-card-doll");
                assertTrue(damaged.contains(String.valueOf(after.armor())) && damaged.contains(lost),
                      () -> "The card's doll shows the new armor and the loss: " + damaged);
                String percent = String.valueOf(Math.round(play.atlas().armor() * 100));
                assertTrue(play.overview(ATLAS).contains(percent), "The overview card's armor share");
                assertEquals((float) play.atlas().armor(), play.tileArmor(ATLAS), .0001f, "The forces tile's bar");
            });
        }
    }

    /**
     * Review from the log: a card click re-presents its shot, which pops up as it lands and applies nothing; after a
     * concealment of the Archer, its card re-presents nothing, so the board shows no unit with its identity, while the
     * Quickdraw's shots can still be reviewed; a board change clears the history.
     */
    @Test
    void aReviewAfterAConcealmentShowsNothingOfTheHiddenUnit() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            GpuLiveBoardSpaceSmokeTest.run(firing.board.source, live -> {
                Playback play = new Playback(live);
                Volley volley = play.report(firing);
                assertTrue(play.until(() -> !play.history.running()), "The volley plays");
                double armor = play.atlas().armor();
                play.click(play.card(2));
                assertTrue(play.playback.reviewing(), "The card reviews the Quickdraw's first shot");
                assertEquals(volley.shots().get(1).result().id(), play.history.current().attack().id());
                int popUps = play.popUps.size();
                assertTrue(play.until(() -> play.popUps.size() > popUps), "The reviewed shot pops up as it lands");
                assertEquals(hit(10, "CT"), play.popUps.getLast());
                play.assertAtTarget(ATLAS);
                live.capture("i1c-review.png");
                live.view.setTacticalView(true);
                live.draw(3, 0);
                live.capture("i1c-review-tactical.png");
                live.view.setTacticalView(false);
                assertTrue(play.until(() -> !play.playback.reviewing()));

                // The Archer is concealed: its event drops the shots that show it.
                play.conceal(ARCHER);
                play.click(play.card(1));
                assertEquals(volley.shots().getFirst().result().id(), play.history.current().attack().id(),
                      "The Atlas's card is the current one");
                assertFalse(play.playback.reviewing(), "Its shot showed the Archer, so nothing is re-presented");
                play.frames(10);
                assertNull(play.shown(ARCHER), "No unit with the Archer's identity on the board");
                assertFalse(play.models().containsKey(ARCHER + ":-1"), "Nor its model");
                Actor archerPop = play.newest();
                assertFalse((boolean) field(archerPop, "anchored"), "Its card's pop-up has no place on the board");

                play.click(play.card(3));
                assertTrue(play.playback.reviewing(), "The Quickdraw's other shots are still retained");
                play.frames(1);
                assertNotNull(play.shown(QUICKDRAW));
                assertNull(play.shown(ARCHER), "Nor does a review of another shot show the Archer");
                assertTrue(play.until(() -> !play.playback.reviewing()));
                assertEquals(armor, play.atlas().armor(), "Reviews never apply damage again");

                // A board change clears the history: no shot of the old board is re-presented.
                play.changeBoard();
                assertNull(play.history.current(), "No step is under the cursor on the new board");
                play.click(play.card(2));
                assertFalse(play.playback.reviewing(), "The new board's history retains no shot");
            });
        }
    }

    /**
     * Live events that arrive while a review runs wait for it (A.10): the board keeps the Quickdraw where it stood
     * and the HUD keeps the units, until the review ends; then the Quickdraw walks two hexes and fires again, in
     * that order, and the HUD shows the client's units once both played.
     */
    @Test
    void liveEventsThatArriveDuringAReviewPlayAfterItInOrder() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            GpuLiveBoardSpaceSmokeTest.run(firing.board.source, live -> {
                Playback play = new Playback(live);
                play.report(firing);
                assertTrue(play.until(() -> !play.history.running()), "The volley plays");
                double armor = play.atlas().armor();
                // A review of the Atlas's shot: the Quickdraw shows as the live board has it.
                play.click(play.card(1));
                assertTrue(play.playback.reviewing());

                // The Quickdraw walks north two hexes and fires again; the client applies both at once.
                Coords start = GpuFiringFixture.EAST;
                Coords end = new Coords(start.getX(), start.getY() - 2);
                BoardScene.Combat shot = play.walkAndFire(firing, end);
                play.frames(1);
                assertTrue(play.playback.reviewing(), "The review goes on");
                assertNull(play.playback.movement(), "The walk waits for the review");
                assertFalse(play.playback.waiting().contains(shot.result().id()), "So does the shot");
                assertEquals(start, play.shown(QUICKDRAW).location().coords(), "The board keeps the Quickdraw");
                assertTrue(play.position(QUICKDRAW).epsilonEquals(play.centre(start), .01f), "where it stood");
                assertEquals(start, play.unit(QUICKDRAW).position(), "The HUD keeps it there too");
                assertEquals(armor, play.atlas().armor(), "and the Atlas's armor");

                assertTrue(play.until(() -> !play.playback.reviewing()), "The review ends");
                List<String> order = new ArrayList<>();
                boolean between = false;
                for (int frame = 0; frame < LIMIT && play.history.running(); frame++) {
                    play.frames(1);
                    String now = play.playback.movement() != null ? "walk"
                          : play.playback.attack() != null && play.playback.attack().event == shot ? "shot" : null;
                    if (now != null && (order.isEmpty() || !order.getLast().equals(now))) {
                        order.add(now);
                    }
                    Vector2 at = play.position(QUICKDRAW);
                    between |= at.dst(play.centre(start)) > BoardGeometry.HEIGHT / 2
                          && at.dst(play.centre(end)) > BoardGeometry.HEIGHT / 2;
                }
                assertEquals(List.of("walk", "shot"), order, "The held events play after the review, in order");
                assertTrue(between, "The Quickdraw walks from where the board showed it");
                assertFalse(play.history.running());
                assertEquals(end, play.unit(QUICKDRAW).position(), "Now the HUD shows the client's units");
                assertTrue(play.atlas().armor() < armor);
            });
        }
    }

    /**
     * G16's growth and the gait (rebuild plan I1c): the Atlas walks the same four hexes at its size and grown twice
     * when zoomed out; grown, a gait cycle covers twice the ground, so its legs do not cycle twice as fast.
     */
    @Test
    void aGrownUnitWalksWithStridesAsMuchLonger() throws Exception {
        try (GpuFiringFixture firing = firing()) {
            float own = stride(firing, 120);
            float grown = stride(firing, 40);
            System.out.printf("The Atlas's stride over four hexes: %.2f world units at its size, %.2f grown twice%n",
                  own, grown);
            assertEquals(2, grown / own, .25f, "Grown twice, a gait cycle covers twice the ground");
        }
    }

    /**
     * The world distance one gait cycle of the Atlas covers while it walks four hexes north with hexes
     * {@code hexPixels} wide on the screen: the walk over the cycles of its left foot, each a step forward relative to
     * its hips.
     */
    private static float stride(GpuFiringFixture firing, float hexPixels) {
        float[] stride = new float[1];
        GpuLiveBoardSpaceSmokeTest.run(firing.board.source, live -> {
            Playback play = new Playback(live);
            // The gait is the 3D model's; Meks show as meeples by default.
            GpuBoardTestUi.<SelectBox<UnitDisplayMode>>tuning(live.view, "tuning-unit-display")
                  .setSelected(UnitDisplayMode.MODELS);
            live.zoom(new Coords(5, 3), hexPixels);
            float growth = UnitScreenScale.factor(live.hudView().hexPixels());
            assertEquals(hexPixels < UnitScreenScale.threshold ? 2 : 1, growth, .001f);
            GpuBoardSource.Frame base = live.frame.get();
            BoardScene scene = base.scene();
            BoardScene.Unit atlas = Playback.unit(scene, ATLAS);
            List<BoardScene.Waypoint> path = new ArrayList<>();
            for (int y = ATLAS_HEX.getY(); y >= ATLAS_HEX.getY() - 4; y--) {
                Coords coords = new Coords(ATLAS_HEX.getX(), y);
                path.add(new BoardScene.Waypoint(coords, scene.tile(coords).elevation(), 0));
            }
            BoardScene.Unit walked = new BoardScene.Unit(ATLAS, atlas.part(), atlas.name(), path.getLast(),
                  atlas.image(), false, atlas.annotations(), atlas.height(), false, atlas.model(), atlas.outlineRgb(),
                  List.of(path.getLast().coords()));
            BoardScene after = scene.withUnits(scene.units().stream().map(unit -> unit.id() == ATLAS ? walked : unit)
                  .toList());
            GpuBoardSource.Frame arrived = Playback.frame(base, after, base.status(), base.reports(), List.of());
            live.frame.set(arrived.withTimeline(List.of(new BoardScene.Movement(ATLAS, after.boardId(), path,
                  EntityMovementType.MOVE_WALK, 0, 4, walked))));
            play.frames(1);
            live.frame.set(arrived);
            List<Float> feet = new ArrayList<>();
            List<Float> travel = new ArrayList<>();
            while (play.playback.movement() != null) {
                assertEquals(growth, UnitScreenScale.factor(live.hudView().hexPixels()), .001f, "The zoom stays");
                ModelInstance model = live.model(ATLAS);
                Vector3 foot = model.getNode("LL-foot").globalTransform.getTranslation(new Vector3());
                Vector3 hips = model.getNode("pelvis").globalTransform.getTranslation(new Vector3());
                feet.add(foot.y - hips.y);
                travel.add(play.position(ATLAS).dst(play.centre(ATLAS_HEX)));
                play.frames(1);
                assertTrue(feet.size() < LIMIT, "The walk ends");
            }
            float middle = (float) feet.stream().mapToDouble(Float::doubleValue).average().orElseThrow();
            float forward = middle + (Collections.max(feet) - middle) / 2;
            int first = -1;
            int last = -1;
            int cycles = -1;
            boolean swung = false;
            for (int index = 1; index < feet.size(); index++) {
                // The foot passes under its hips once a cycle going back: it is planted and the body walks on. It has
                // swung well forward first: a foot settling by its hips as the walk starts or ends crosses them too.
                swung |= feet.get(index - 1) > forward;
                if (swung && feet.get(index - 1) > middle && feet.get(index) <= middle) {
                    first = first < 0 ? index : first;
                    last = index;
                    cycles++;
                    swung = false;
                }
            }
            assertTrue(cycles >= 2, "The walk has cycles to measure: " + cycles);
            stride[0] = (travel.get(last) - travel.get(first)) / cycles;
        });
        return stride[0];
    }

    private static GpuFiringFixture firing() throws Exception {
        GpuFiringFixture firing = GpuFiringFixture.firing();
        GpuFiringFixture.enemy(firing, "Quickdraw QKD-8X.mtf", QUICKDRAW, GpuFiringFixture.EAST);
        onSwing(() -> {
            // The card shows the Atlas's record, which the source captures for its card unit.
            firing.board.source.setCardUnit(ATLAS);
            firing.board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
            firing.board.source.refresh();
            return null;
        });
        return firing;
    }

    /** The Atlas's record before and after the report's damage, and the report's shots in report order. */
    private record Volley(GpuUnitRecord.Snapshot before, GpuUnitRecord.Snapshot after,
          List<BoardScene.Combat> shots) { }

    /** A hit's pop-up: its damage and location over the weapon. */
    private static String hit(int damage, String location) {
        return Messages.getString("GpuBoard.hud.labels.damage", damage, location) + " MEDIUM LASER";
    }

    private static GpuUnitRecord.Location location(GpuUnitRecord.Snapshot record, String abbr) {
        assertEquals(ATLAS, record.unitId(), "The source captures the Atlas's record");
        return record.locations().stream().filter(location -> location.abbr().equals(abbr)).findFirst().orElseThrow();
    }

    /**
     * The view under test with its playback history and playback, the frames the tests draw with the board's pop-ups
     * they saw appear, in order, and the HUD's facts the checks read.
     */
    private static final class Playback {
        final Live live;
        final GpuPlaybackHistory history;
        final UnitPlayback playback;
        /** The title and line of each pop-up as it appeared. */
        final List<String> popUps = new ArrayList<>();
        private final Set<Actor> seen = new HashSet<>();
        private final List<Actor> appeared = new ArrayList<>();

        Playback(Live live) throws Exception {
            this.live = live;
            history = live.hud.state.history;
            playback = (UnitPlayback) field(live.view, "playback");
            live.publish();
            live.draw(20, .1f);
        }

        /**
         * Publishes the firing report: the client applies the Quickdraw's damage to the Atlas, and the frame of its
         * report phase brings the scripted log and the four shots. The Atlas is selected for the card, as a click on
         * its row selects it outside the local turn.
         */
        Volley report(GpuFiringFixture firing) throws Exception {
            GpuUnitRecord.Snapshot before = live.frame.get().panels().record();
            GpuBoardSource real = firing.board.source;
            GpuBoardSource.Frame captured = onSwing(() -> {
                Entity atlas = firing.attacker;
                atlas.setArmor(atlas.getArmor(Mek.LOC_CENTER_TORSO) - 10, Mek.LOC_CENTER_TORSO);
                atlas.setArmor(atlas.getArmor(Mek.LOC_LEFT_TORSO) - 5, Mek.LOC_LEFT_TORSO);
                real.refresh();
                return real.takeFrame();
            });
            BoardScene scene = captured.scene();
            BoardScene.Unit atlas = unit(scene, ATLAS);
            BoardScene.Unit quickdraw = unit(scene, QUICKDRAW);
            List<BoardScene.Combat> shots = List.of(
                  UnitPlaybackFixture.attack(atlas, unit(scene, ARCHER), ResolvedAttack.Kind.SHOT, true),
                  UnitPlaybackFixture.attack(quickdraw, atlas, ResolvedAttack.Kind.SHOT, true),
                  UnitPlaybackFixture.attack(quickdraw, atlas, ResolvedAttack.Kind.SHOT, true),
                  UnitPlaybackFixture.attack(quickdraw, atlas, ResolvedAttack.Kind.SHOT, false));
            int round = Math.max(1, captured.status().round());
            List<GpuReportLog.CombatEvent> events = List.of(event(shots.get(0), round, 5, "CT"),
                  event(shots.get(1), round, 10, "CT"), event(shots.get(2), round, 5, "LT"),
                  event(shots.get(3), round, 0, ""));
            List<GpuReportLog.Entry> entries = events.stream().map(attack -> entry(attack, round)).toList();
            GpuReportLog.Snapshot log = new GpuReportLog.Snapshot(round, GamePhase.FIRING_REPORT, entries, Map.of(),
                  events, List.of(), List.of(), List.of());
            GpuBattleStatus.Snapshot s = captured.status();
            GpuBattleStatus.Snapshot status = new GpuBattleStatus.Snapshot(round, GamePhase.FIRING_REPORT, false,
                  s.localPlayerId(), Entity.NONE, s.turns(), s.turnIndex(), s.units(), s.initiative(),
                  s.turnOrderHidden());
            GpuBoardSource.Frame frame = frame(captured, scene, status, log, List.of());
            live.frame.set(frame.withTimeline(List.copyOf(shots)));
            frames(1);
            live.frame.set(frame);
            live.hud.select(ATLAS);
            live.draw(1, 0);
            return new Volley(before, captured.panels().record(), shots);
        }

        /** The Archer leaves the local player's sight: its concealment event, and it is gone from the captures. */
        void conceal(int id) {
            GpuBoardSource.Frame shown = live.frame.get();
            BoardScene scene = shown.scene();
            GpuBattleStatus.Snapshot s = shown.status();
            GpuBattleStatus.Snapshot status = new GpuBattleStatus.Snapshot(s.round(), s.phase(), s.myTurn(),
                  s.localPlayerId(), s.actorId(), s.turns(), s.turnIndex(),
                  s.units().stream().filter(unit -> unit.id() != id).toList(), s.initiative(), s.turnOrderHidden());
            GpuBoardSource.Frame hidden = frame(shown, scene.withUnits(scene.units().stream()
                  .filter(unit -> unit.id() != id).toList()), status, shown.reports(), List.of());
            live.frame.set(hidden.withTimeline(List.of(new BoardScene.Concealed(id, scene.boardId()))));
            frames(1);
            live.frame.set(hidden);
        }

        /** The view's board changes: the same board in a new generation, as the client shows another map. */
        void changeBoard() {
            GpuBoardSource.Frame shown = live.frame.get();
            GpuBoardSource.Frame next = new GpuBoardSource.Frame(shown.scene(), List.of(), shown.context(),
                  shown.globalCommands(), shown.tooltip(), shown.centerRequest(),
                  shown.boardGeneration() + 1, shown.actorName(), shown.scenarioAtmosphere(),
                  shown.reports(), shown.status(), shown.panels());
            live.frame.set(next);
            frames(2);
        }

        /**
         * The Quickdraw walks to {@code end} and fires at the Atlas again; the client applies both before the frame
         * that brings their events, with the shot's log entry. Returns the shot.
         */
        BoardScene.Combat walkAndFire(GpuFiringFixture firing, Coords end) throws Exception {
            GpuBoardSource.Frame shown = live.frame.get();
            GpuBoardSource real = firing.board.source;
            GpuBoardSource.Frame captured = onSwing(() -> {
                firing.board.game.getEntity(QUICKDRAW).setPosition(end);
                Entity atlas = firing.attacker;
                atlas.setArmor(atlas.getArmor(Mek.LOC_RIGHT_TORSO) - 5, Mek.LOC_RIGHT_TORSO);
                real.refresh();
                return real.takeFrame();
            });
            BoardScene scene = captured.scene();
            BoardScene.Unit walked = unit(scene, QUICKDRAW);
            BoardScene.Unit before = unit(shown.scene(), QUICKDRAW);
            List<BoardScene.Waypoint> path = new ArrayList<>();
            for (int y = before.location().coords().getY(); y >= end.getY(); y--) {
                Coords coords = new Coords(end.getX(), y);
                path.add(new BoardScene.Waypoint(coords, scene.tile(coords).elevation(), 0));
            }
            BoardScene.Combat shot = UnitPlaybackFixture.attack(walked, unit(scene, ATLAS), ResolvedAttack.Kind.SHOT,
                  true);
            GpuReportLog.Snapshot old = shown.reports();
            GpuReportLog.CombatEvent event = event(shot, old.round(), 5, "RT");
            List<GpuReportLog.Entry> entries = new ArrayList<>(old.entries());
            entries.add(entry(event, old.round()));
            List<GpuReportLog.CombatEvent> events = new ArrayList<>(old.combat());
            events.add(event);
            GpuReportLog.Snapshot log = new GpuReportLog.Snapshot(old.round(), old.phase(), entries, Map.of(), events,
                  List.of(), List.of(), List.of());
            GpuBattleStatus.Snapshot s = captured.status();
            GpuBattleStatus.Snapshot status = new GpuBattleStatus.Snapshot(old.round(), GamePhase.FIRING_REPORT, false,
                  s.localPlayerId(), Entity.NONE, s.turns(), s.turnIndex(), s.units(), s.initiative(),
                  s.turnOrderHidden());
            GpuBoardSource.Frame frame = frame(shown, scene, status, log, List.of());
            live.frame.set(frame.withTimeline(List.of(new BoardScene.Movement(QUICKDRAW, scene.boardId(), path,
                  EntityMovementType.MOVE_WALK, 0, 5, walked), shot)));
            live.draw(1, 0);
            live.frame.set(frame);
            return shot;
        }

        /** Draws {@code count} frames of the playback and notes the pop-ups that appear. */
        void frames(int count) {
            for (int index = 0; index < count; index++) {
                live.draw(1, FRAME);
                watch(live.hud.stage.getRoot());
            }
        }

        /** Draws frames until {@code done} holds; false when it does not within the limit. */
        boolean until(BooleanSupplier done) {
            for (int frame = 0; frame < LIMIT && !done.getAsBoolean(); frame++) {
                frames(1);
            }
            return done.getAsBoolean();
        }

        private void watch(Actor actor) {
            if ("pop-up".equals(actor.getName()) && seen.add(actor)) {
                appeared.add(actor);
                popUps.add(String.join(" ", GpuBoardTestUi.texts(actor)));
            }
            if (actor instanceof Group group) {
                group.getChildren().forEach(this::watch);
            }
        }

        /** The newest pop-up. */
        Actor newest() {
            return appeared.getLast();
        }

        /** The newest pop-up stands over unit {@code id}: 18 right of the middle of its screen rectangle (.pop3d). */
        void assertAtTarget(int id) throws Exception {
            com.badlogic.gdx.math.Rectangle rect = live.hudView().unitRects().get(id);
            assertNotNull(rect, id + " is drawn");
            assertEquals(rect.x + rect.width / 2 + 18, newest().getX(), 1.5f, "The pop-up stands at " + id);
        }

        /** The hits and misses among the shots that landed so far, as the log's totals count them. */
        List<String> landed() {
            long hits = history.playedAttacks().stream().filter(GpuReportLog.CombatEvent::hit).count();
            return List.of(String.valueOf(hits), String.valueOf(history.playedAttacks().size() - hits));
        }

        boolean played(BoardScene.Combat shot) {
            return history.playedAttacks().stream().anyMatch(attack -> attack.id().equals(shot.result().id()));
        }

        /** The presented status of unit {@code id}, which every component shows. */
        GpuBattleStatus.UnitStatus unit(int id) {
            return live.hud.state.presentedUnits().stream().filter(unit -> unit.id() == id).findFirst()
                  .orElseThrow();
        }

        GpuBattleStatus.UnitStatus atlas() {
            return unit(ATLAS);
        }

        /** The scene unit the board draws for {@code id}, or null for none or a sensor contact. */
        BoardScene.Unit shown(int id) throws Exception {
            return live.scene().units().stream().filter(unit -> unit.id() == id && !unit.sensorContact())
                  .findFirst().orElse(null);
        }

        /** Unit {@code id}'s animated board position, as the HudView carries it. */
        Vector2 position(int id) throws Exception {
            return live.hudView().unitPositions().get(id);
        }

        Vector2 centre(Coords coords) {
            Vector3 centre = BoardGeometry.center(coords, 0);
            return new Vector2(centre.x, centre.y);
        }

        @SuppressWarnings("unchecked")
        Map<String, ModelInstance> models() throws Exception {
            return (Map<String, ModelInstance>) field(live.view, "unitInstances");
        }

        List<String> texts(String name) {
            return GpuBoardTestUi.texts(live.actor(name));
        }

        /** The log's totals: hits and misses. */
        List<String> totals() {
            return List.of(texts("log-total-0").getFirst(), texts("log-total-1").getFirst());
        }

        /** The texts of unit {@code id}'s card in the force overview, opened for one frame. */
        List<String> overview(int id) {
            live.hud.state.overview = true;
            live.draw(1, 0);
            List<String> texts = texts("force-overview-card-" + id);
            live.hud.state.overview = false;
            live.draw(1, 0);
            return texts;
        }

        /** The armor share of unit {@code id}'s tile in the forces grid, shown for one frame. */
        float tileArmor(int id) throws Exception {
            live.hud.state.forcesGrid = true;
            live.draw(1, 0);
            float armor = (float) field(live.actor("forces-tile-" + id), "armor");
            live.hud.state.forcesGrid = false;
            live.draw(1, 0);
            return armor;
        }

        /** The log's card of step {@code number}. */
        Actor card(int number) {
            String disc = String.format("%02d", number);
            List<Actor> cards = new ArrayList<>();
            collect(live.actor("log-cards"), "log-card", cards);
            return cards.stream().filter(card -> GpuBoardTestUi.texts(card).getFirst().equals(disc)).findFirst()
                  .orElseThrow(() -> new AssertionError("No log card " + disc));
        }

        /** A left click at the middle of {@code actor}, then a frame. */
        void click(Actor actor) {
            Vector2 point = live.hud.stage.stageToScreenCoordinates(actor.localToStageCoordinates(
                  new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
            live.hud.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
            live.hud.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
            frames(1);
        }

        private static void collect(Actor actor, String name, List<Actor> found) {
            if (name.equals(actor.getName())) {
                found.add(actor);
            }
            if (actor instanceof Group group) {
                group.getChildren().forEach(child -> collect(child, name, found));
            }
        }

        static BoardScene.Unit unit(BoardScene scene, int id) {
            return scene.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
        }

        /** {@code base} with the scene, status, log and events given and the panels of a phase without orders. */
        static GpuBoardSource.Frame frame(GpuBoardSource.Frame base, BoardScene scene, GpuBattleStatus.Snapshot status,
              GpuReportLog.Snapshot reports, List<BoardScene.Animation> timeline) {
            GpuHudData p = base.panels();
            GpuHudData panels = status.phase().isReport() ? new GpuHudData(p.phase(), GpuMovePlan.Snapshot.EMPTY,
                  GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, p.record(),
                  GpuFirePreview.Snapshot.NONE, p.chat(), p.toasts(), p.los(), p.players()) : p;
            return new GpuBoardSource.Frame(scene, timeline, base.context(), base.globalCommands(),
                  base.tooltip(), base.centerRequest(), base.boardGeneration(), base.actorName(),
                  base.scenarioAtmosphere(), reports, status, panels);
        }

        /** The report log's record of a medium laser shot: its damage to one location, or a miss. */
        static GpuReportLog.CombatEvent event(BoardScene.Combat shot, int round, int damage, String location) {
            ResolvedAttack result = shot.result();
            return new GpuReportLog.CombatEvent(result.id(), round, GamePhase.FIRING, result.kind(),
                  shot.attacker().id(), shot.target().id(), "Medium Laser", "RA", result.hit(), damage,
                  result.hit() ? List.of(new ResolvedAttack.Impact(location, false, damage)) : List.of(), null, 0);
        }

        /** The report entry linked to an attack: a hit, or a miss rolled 7 against 9+. */
        static GpuReportLog.Entry entry(GpuReportLog.CombatEvent attack, int round) {
            List<GpuReportLog.Unit> units = List.of(new GpuReportLog.Unit(ATLAS, "Atlas"),
                  new GpuReportLog.Unit(ARCHER, "Archer"), new GpuReportLog.Unit(QUICKDRAW, "Quickdraw"));
            return new GpuReportLog.Entry(round, "Weapon Attack Phase", GamePhase.FIRING, "", "Medium Laser",
                  units, "", List.of(), GpuReportLog.Kind.WEAPON, attack.attackerId(), attack.targetId(), attack.id(),
                  List.of(), false, false, attack.hit() ? null : 9, attack.hit() ? null : 7, null, null, null, null,
                  null, false, false);
        }
    }
}
