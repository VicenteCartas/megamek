/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.List;

import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Test;

class GpuScenePlaybackTest {
    @Test
    void eachArrivalReleasesItsOwnTerrainArtworkAndContactsAtEverySpeed() {
        for (var speed : List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL, UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE)) {
            var start = UnitPlaybackFixture.unit(1, 0);
            var middle = UnitPlaybackFixture.unit(1, 2);
            var end = UnitPlaybackFixture.unit(1, 4);
            var before = snapshot(0, start, contact(2, 1));
            var arrived = snapshot(1, middle, contact(2, 3), contact(3, 2));
            var latest = snapshot(2, end, contact(3, 4));
            var playback = new UnitPlayback();
            playback.accept(List.of(new BoardScene.SceneUpdate(before), move(start, middle),
                  new BoardScene.SceneUpdate(arrived), move(middle, end), new BoardScene.SceneUpdate(latest)), latest, ignored -> false);
            assertWorld(before, playback.present(latest));
            playback.advance(0, speed);
            playback.advance(playback.motions.get(1).remainingSeconds() / (2 * speed.rate), speed);
            assertWorld(before, playback.present(latest));
            assertTrue(playback.present(latest).rangeBorders().isEmpty());
            playback.advance(playback.motions.get(1).remainingSeconds() / speed.rate, speed);
            assertWorld(arrived, playback.present(latest));
            assertEquals(1, playback.holdSeconds(), 1e-6);
            playback.advance(UnitPlayback.COMPLETION_HOLD_SECONDS, speed);
            assertWorld(arrived, playback.present(latest));
            playback.advance(playback.motions.get(1).remainingSeconds() / speed.rate, speed);
            assertWorld(latest, playback.present(latest));
            assertEquals(latest.rangeBorders(), playback.present(latest).rangeBorders());
            assertEquals(1, playback.holdSeconds(), 1e-6, "Checkpoints do not add holds or delay the arrival state");
            assertEquals(0, before.tiles().getFirst().elevation(), "Retained snapshots are immutable");
        }
    }

    @Test
    void eachAttackAppliesDestructionAndVisibilityAtImpactBeforeRecoveryOrTheNextAttack() {
        for (var kind : List.of(ResolvedAttack.Kind.SHOT, ResolvedAttack.Kind.KICK)) {
            var attacker = UnitPlaybackFixture.unit(1, 0);
            // Two attacks by one unit from one pose play as a single volley; a second attacker keeps them separate.
            var second = UnitPlaybackFixture.unit(5, 0);
            var victim = UnitPlaybackFixture.unit(2, 4);
            var before = snapshot(0, attacker, second, victim, contact(3, 1));
            var firstImpact = snapshot(1, attacker, second, victim, contact(3, 3));
            var latest = snapshot(2, attacker, second, contact(4, 2));
            var playback = new UnitPlayback();
            playback.accept(List.of(new BoardScene.SceneUpdate(before), UnitPlaybackFixture.attack(attacker, victim, kind, true),
                  new BoardScene.SceneUpdate(firstImpact), UnitPlaybackFixture.attack(second, victim, kind, true),
                  new BoardScene.SceneUpdate(latest)), latest, ignored -> false);
            playback.advance(0, UnitMotion.Speed.NORMAL);
            assertWorld(before, playback.present(latest));
            assertTrue(playback.present(latest).units().contains(victim), "A removed victim remains visible until its received attack");
            var attack = playback.attack();
            playback.advance((attack.contactSeconds - .001) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            assertWorld(before, playback.present(latest));
            playback.advance(.002 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            assertWorld(firstImpact, playback.present(latest));
            assertTrue(playback.attack().seconds < playback.attack().duration);
            playback.advance((attack.duration - attack.seconds) / UnitMotion.Speed.NORMAL.rate + UnitPlayback.COMPLETION_HOLD_SECONDS,
                  UnitMotion.Speed.NORMAL);
            assertWorld(firstImpact, playback.present(latest));
            playback.advance((playback.attack().contactSeconds + .001) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            assertWorld(latest, playback.present(latest));
            assertFalse(playback.present(latest).units().contains(victim));
        }
    }

    @Test
    void lateCheckpointsWaitWhilePausedAndReleaseWithoutAnotherGameFrame() {
        var start = UnitPlaybackFixture.unit(1, 0);
        var end = UnitPlaybackFixture.unit(1, 4);
        var before = snapshot(0, start, UnitPlaybackFixture.unit(2, 1));
        var latest = snapshot(1, end, contact(2, 3));
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(before), move(start, end)), before, ignored -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        playback.togglePaused();
        playback.accept(List.of(new BoardScene.SceneUpdate(latest)), latest, ignored -> false);
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertWorld(before, playback.present(latest));
        assertFalse(playback.present(latest).units().get(1).sensorContact());
        playback.togglePaused();
        playback.advance(playback.motions.get(1).remainingSeconds() / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertWorld(latest, playback.present(latest));
        assertTrue(playback.present(latest).units().get(1).sensorContact());
    }

    @Test
    void instantLargeFramesAndBoardResetDoNotStrandCheckpoints() {
        var start = UnitPlaybackFixture.unit(1, 0);
        var end = UnitPlaybackFixture.unit(1, 4);
        var before = snapshot(0, start);
        var latest = snapshot(1, end);
        for (var speed : List.of(UnitMotion.Speed.NORMAL, UnitMotion.Speed.INSTANT)) {
            var playback = new UnitPlayback();
            playback.accept(List.of(new BoardScene.SceneUpdate(before), move(start, end), new BoardScene.SceneUpdate(latest)),
                  latest, ignored -> false);
            playback.advance(1000, speed);
            assertSame(latest, playback.present(latest));
            assertFalse(playback.busy());
            playback.accept(List.of(new BoardScene.SceneUpdate(before), move(start, end)), latest, ignored -> false);
            playback.clear();
            assertSame(latest, playback.present(latest));
            assertTrue(playback.motions.isEmpty());
        }
    }

    @Test
    void designationOnlyChangesRequirePlaybackCheckpoints() {
        var source = UnitPlaybackFixture.unit(1, 0);
        var target = UnitPlaybackFixture.unit(2, 4);
        var before = snapshot(0, source, target);
        var designation = new BoardScene.Designation(source.id(), source.location(), target.id(), target.location());
        var tagged = withDesignations(before, List.of(designation));
        assertFalse(before.samePlaybackState(tagged), "A new line needs its own world checkpoint");
        assertFalse(tagged.samePlaybackState(before), "Removing only the line must also release a checkpoint");
        assertTrue(tagged.samePlaybackState(withDesignations(before, List.of(designation))),
              "An equivalent designation snapshot does not create another checkpoint");
        var other = UnitPlaybackFixture.unit(3, 2);
        var retargeted = withDesignations(before, List.of(new BoardScene.Designation(
              source.id(), source.location(), other.id(), other.location())));
        assertFalse(tagged.samePlaybackState(retargeted), "The same number of lines can mark a different target");
        assertEquals(List.of(), tagged.duringPlayback(before, false).designations(),
              "The line follows the settled world until its checkpoint is applied");
        assertEquals(List.of(designation), before.duringPlayback(tagged, false).designations());
    }

    @Test
    void anElevatorMovesAsOneActionFromItsCheckpointOnceTheBoardShowsItsEnd() {
        var rider = UnitPlaybackFixture.unit(1, 0);
        var start = UnitPlaybackFixture.unit(2, 4);
        var end = UnitPlaybackFixture.unit(2, 2);
        var lowered = elevator(0, rider, start);
        var raised = elevator(2, rider, start);
        var latest = elevator(2, rider, end);
        var change = BoardScene.TileTransition.of(lowered, raised);
        assertEquals(List.of(new Coords(0, 0)), change.changes().stream().map(BoardScene.TileTransition.Change::coords).toList());
        assertNull(BoardScene.TileTransition.of(raised, latest), "Only an elevator that rises or sinks moves");
        var playback = new UnitPlayback();
        playback.togglePaused();
        playback.accept(List.of(new BoardScene.SceneUpdate(lowered), change, new BoardScene.SceneUpdate(raised), move(start, end),
              new BoardScene.SceneUpdate(latest)), latest, ignored -> false);
        playback.advance(1, UnitMotion.Speed.NORMAL);
        assertSame(lowered.tiles(), playback.present(latest).tiles(), "Paused before it moves, its checkpoint waits with it");
        playback.togglePaused();
        playback.advance(1, UnitMotion.Speed.NORMAL, ignored -> false);
        assertSame(change, playback.transition());
        assertEquals(0, playback.transitionProgress());
        assertSame(raised.tiles(), playback.present(latest).tiles(),
              "Its checkpoint applies as it starts, so that the board builds its end while it waits to move");
        playback.advance(BoardScene.TileTransition.SECONDS / 2 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertEquals(.5, playback.transitionProgress(), 1e-6);
        assertNull(playback.movement(), "What follows waits for it");
        playback.advance(BoardScene.TileTransition.SECONDS / 2 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertEquals(1, playback.transitionProgress());
        playback.advance(UnitPlayback.COMPLETION_HOLD_SECONDS, UnitMotion.Speed.NORMAL);
        assertNull(playback.transition());
        assertEquals(2, playback.movement().entityId());

        var stepped = new UnitPlayback();
        stepped.togglePaused();
        stepped.accept(List.of(new BoardScene.SceneUpdate(lowered), change, new BoardScene.SceneUpdate(raised), move(start, end)),
              raised, ignored -> false);
        stepped.stepOnce();
        stepped.advance(100, UnitMotion.Speed.NORMAL);
        assertEquals(1, stepped.transitionProgress(), "One step plays the whole change");
        assertNull(stepped.movement());
    }

    private static BoardScene elevator(int level, BoardScene.Unit... units) {
        var shaft = new BoardScene.Feature("shaft", 0, 0, 0, 1, 0, 0, BoardScene.FeatureKind.SHAFT);
        var tile = new BoardScene.Tile(new Coords(0, 0), level, -1, false, 0, null, null, List.of(shaft), List.of());
        return new BoardScene(0, 1, 1, List.of(tile), List.of(units), List.of(), -1, "END", List.of());
    }

    private static BoardScene withDesignations(BoardScene scene, List<BoardScene.Designation> designations) {
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(),
              scene.firingLines(), scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(),
              scene.fieldOfView(), scene.electronicWarfare(), designations, scene.starShells());
    }

    private static void assertWorld(BoardScene expected, BoardScene shown) {
        assertSame(expected.tiles(), shown.tiles(), "Terrain and every bitmap layer share the event's checkpoint");
        assertEquals(expected.markers(), shown.markers());
        assertEquals(expected.tactical(), shown.tactical());
        assertEquals(expected.units().stream().filter(BoardScene.Unit::sensorContact).toList(),
              shown.units().stream().filter(BoardScene.Unit::sensorContact).toList());
    }

    private static BoardScene.Unit contact(int id, int row) {
        return new BoardScene.Unit(id, -1, "?", new BoardScene.Waypoint(new Coords(0, row), 0, 0), null, true, null, 1, false);
    }

    private static BoardScene.Movement move(BoardScene.Unit start, BoardScene.Unit end) {
        return new BoardScene.Movement(start.id(), 0, List.of(start.location(), end.location()), EntityMovementType.MOVE_WALK, 0, 4, end);
    }

    private static BoardScene snapshot(int version, BoardScene.Unit... units) {
        var coords = new Coords(0, 0);
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF000000 | version);
        var pixels = new BoardScene.Pixels(image);
        var feature = new BoardScene.Feature("building-" + version, 0, 0, 0, 1, version, 0, BoardScene.FeatureKind.BUILDING);
        var tile = new BoardScene.Tile(coords, version, -1, false, 0, BoardScene.Surface.GRASS, pixels,
              List.of(feature), List.of());
        var marker = new BoardMarker(BoardMarker.Kind.OBJECTIVE, coords, 0, 0xFFFF00, "Objective " + version);
        var text = new BoardTactical.Text("Objective " + version, new Font(Font.SANS_SERIF, Font.PLAIN, 12), 0, 0, 0xFFFF0000);
        var tactical = new BoardTactical(List.of(), List.of(new BoardTactical.Label(new BoardTactical.Point(0, 0), text,
              BoardTactical.Playback.HOLD_DURING_PLAYBACK)));
        return new BoardScene(0, 1, 1, List.of(tile), List.of(units), List.of(), 1, "MOVEMENT", List.of(), null,
              List.of(), List.of(new BoardScene.RangeBorder(coords, 63, 0x44FF88, "S")), List.of(marker), tactical);
    }
}
