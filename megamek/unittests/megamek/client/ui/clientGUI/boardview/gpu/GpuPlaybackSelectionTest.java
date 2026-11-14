/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.ENEMY;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.client.ui.clientGUI.boardview.BoardFocus;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Test;

/** Selection follows the real shared playback clock, rather than incoming turn packets or movement contact. */
class GpuPlaybackSelectionTest {
    private static final BoardScene.Unit FIRST = UnitPlaybackFixture.unit(1, 0);
    private static final BoardScene.Unit MOVED = UnitPlaybackFixture.unit(1, 2);
    private static final BoardScene.Unit SECOND = UnitPlaybackFixture.unit(2, 4);
    private static final BoardScene.Unit ENEMY_UNIT = UnitPlaybackFixture.unit(9, 6);

    @Test
    void movementCombatAndConversionHoldSelectionThroughTheirCompleteAnimationAndFinalHold() {
        BoardScene.Movement movement = movement();
        BoardScene.Combat shot = UnitPlaybackFixture.attack(FIRST, ENEMY_UNIT, ResolvedAttack.Kind.SHOT, true);
        BoardScene.Conversion conversion = new BoardScene.Conversion(0, FIRST, MOVED);
        for (BoardScene.Animation action : List.of(movement, shot, conversion)) {
            GamePhase phase = action instanceof BoardScene.Combat ? GamePhase.FIRING : GamePhase.MOVEMENT;
            var playback = new UnitPlayback();
            var state = new GpuHudState(new GpuPlaybackHistory(playback));
            var before = frame(status(phase, 1, 1), 1, record(1), preview(1), 0);
            var next = frame(status(phase, 2, 2), 2, record(2), preview(2), 0);
            state.update(before, false);
            playback.accept(List.of(action), next.scene(), ignored -> false);
            assertHeld(state, next, playback);
            playback.advance(0, UnitMotion.Speed.NORMAL);
            double duration = action instanceof BoardScene.Movement
                  ? playback.motions.get(1).remainingSeconds()
                  : action instanceof BoardScene.Combat ? new UnitAttack(shot).duration : UnitConversion.DURATION_SECONDS;
            playback.advance(duration / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            assertEquals(UnitPlayback.COMPLETION_HOLD_SECONDS, playback.holdSeconds(), 1e-6);
            assertHeld(state, next, playback);
            playback.advance(UnitPlayback.COMPLETION_HOLD_SECONDS / 2, UnitMotion.Speed.NORMAL);
            assertHeld(state, next, playback);
            playback.advance(UnitPlayback.COMPLETION_HOLD_SECONDS / 2 + .001, UnitMotion.Speed.NORMAL);
            assertFalse(playback.busy());
            assertEquals(Entity.NONE, state.update(next, false));
            assertEquals(2, state.focus());
            assertEquals(2, state.cardUnit());
            assertSame(next, state.presented(next));
            assertEquals(Entity.NONE, state.update(next, false), "Repeated idle captures cannot advance again");
        }
    }

    @Test
    void pausedAndQueuedActionsDelayTheNewestPhaseUntilInstantCompletesTheWholeQueue() {
        var playback = new UnitPlayback();
        var state = new GpuHudState(new GpuPlaybackHistory(playback));
        var before = frame(status(GamePhase.MOVEMENT, 1, 1), 1, record(1), preview(1), 0);
        var moved = frame(status(GamePhase.MOVEMENT, 2, 2), 2, record(2), preview(2), 0);
        var physical = frame(status(GamePhase.PHYSICAL, 2, 3), 2, record(2), preview(2), 0);
        state.update(before, false);
        playback.accept(List.of(movement(), new BoardScene.Conversion(0, SECOND, SECOND)), moved.scene(), ignored -> false);
        playback.advance(.1, UnitMotion.Speed.NORMAL);
        assertHeld(state, moved, playback);
        playback.togglePaused();
        playback.advance(100, UnitMotion.Speed.NORMAL);
        assertHeld(state, physical, playback);
        assertEquals(GamePhase.MOVEMENT, state.presented(physical).status().phase());
        playback.advance(0, UnitMotion.Speed.INSTANT);
        assertFalse(playback.busy());
        state.update(physical, false);
        assertEquals(2, state.focus());
        assertSame(physical, state.presented(physical));
    }

    @Test
    void queuedActionsDoNotAdvanceSelectionAtAnIntermediateCompletion() {
        var playback = new UnitPlayback();
        var state = new GpuHudState(new GpuPlaybackHistory(playback));
        var before = frame(status(GamePhase.MOVEMENT, 1, 1), 1, record(1), preview(1), 0);
        var next = frame(status(GamePhase.MOVEMENT, 2, 2), 2, record(2), preview(2), 0);
        state.update(before, false);
        var movement = movement();
        playback.accept(List.of(new BoardScene.Conversion(0, FIRST, FIRST), movement), next.scene(), ignored -> false);
        playback.advance(UnitConversion.DURATION_SECONDS / UnitMotion.Speed.NORMAL.rate
              + UnitPlayback.COMPLETION_HOLD_SECONDS + .01, UnitMotion.Speed.NORMAL);
        assertSame(movement, playback.movement());
        assertHeld(state, next, playback);
        playback.advance(0, UnitMotion.Speed.INSTANT);
        state.update(next, playback.busy());
        assertEquals(2, state.focus());
    }

    @Test
    void aDeferredSamePlayerTurnSelectsTheNextCandidateOnlyOnce() {
        var state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        state.update(frame(status(GamePhase.MOVEMENT, 1, 1), 1, record(1), preview(1), 0), false);
        var later = GpuHudFixtures.status(1, GamePhase.MOVEMENT, true, 3, 2,
              GpuHudFixtures.unit(1, OWN, false, false), GpuHudFixtures.unit(2, OWN, true, true),
              GpuHudFixtures.unit(3, OWN, true, true));
        var next = frame(later, 3, record(3), preview(3), 0);
        assertEquals(Entity.NONE, state.update(next, true));
        assertEquals(1, state.focus());
        assertEquals(2, state.update(next, false), "Release reconciles against the last applied turn, not the busy capture");
        assertEquals(2, state.focus());
        assertEquals(Entity.NONE, state.update(next, false), "The automatic selection command is emitted once");
    }

    @Test
    void heldContextKeepsTheOldPreviewButNeverOldOrNextUnitCommands() {
        var state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        var before = frame(status(GamePhase.MOVEMENT, 1, 1), 1, record(1), preview(1), 0);
        var next = frame(status(GamePhase.FIRING, 2, 2), 2, record(2), preview(2), 0);
        state.update(before, false);
        state.update(next, true);
        var shown = state.presented(next);
        assertFalse(next.scene().commands().isEmpty());
        assertFalse(next.scene().plannedPath().isEmpty());
        assertTrue(next.panels().physical().active());
        assertEquals(1, shown.scene().selectedId());
        assertSame(before.panels().preview(), shown.panels().preview());
        assertEquals(1, shown.panels().record().unitId());
        assertSame(GpuBoardActions.PhaseInfo.EMPTY, shown.panels().phase());
        assertSame(GpuMovePlan.Snapshot.EMPTY, shown.panels().move());
        assertSame(GpuFireOrders.Snapshot.EMPTY, shown.panels().fire());
        assertSame(GpuPhysicalOptions.Snapshot.EMPTY, shown.panels().physical());
        assertTrue(shown.scene().plannedPath().isEmpty());
        assertTrue(shown.scene().commands().isEmpty());
        assertTrue(shown.scene().firingLines().isEmpty());
        assertTrue(shown.scene().rangeBorders().isEmpty());
        assertTrue(shown.scene().rangeLabels().isEmpty());
        assertNull(shown.context());
        assertTrue(state.turnLocked(next.status()));
        assertSame(next.panels().chat(), shown.panels().chat());
        assertSame(next.panels().toasts(), shown.panels().toasts());

        state.inspected = 2;
        state.update(next, true);
        // Capture may reuse its immutable frame: an explicit inspection must still take its existing record.
        assertEquals(1, state.focus());
        assertEquals(2, state.cardUnit());
        assertEquals(2, state.presentedRecord().unitId());
    }

    @Test
    void aFrameWithoutABoardCanBeFollowedByBusyPlaybackAndBoardReplacementResetsTheHold() {
        var state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        var initial = frame(GpuBattleStatus.Snapshot.EMPTY, Entity.NONE, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, 0).withScene(null);
        state.update(initial, false);
        var playing = frame(status(GamePhase.MOVEMENT, 1, 1), 1, record(1), preview(1), 0);
        state.update(playing, true);
        assertEquals(playing.scene().units(), state.presented(playing).scene().units());
        var replacement = frame(status(GamePhase.FIRING, 2, 2), 2, record(2), preview(2), 1);
        state.update(replacement, true);
        assertEquals(2, state.focus());
        assertEquals(2, state.presented(replacement).scene().selectedId());
    }

    @Test
    void concealmentRevokesHeldInspectionAndPreviewWhileAnotherActionRemainsQueued() {
        var state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        var before = frame(status(GamePhase.MOVEMENT, 1, 1), 1, record(9), preview(1), 0);
        state.inspected = 9;
        state.update(before, false);
        var authorized = GpuHudFixtures.status(1, GamePhase.MOVEMENT, true, 2, 2,
              GpuHudFixtures.unit(1, OWN, false, false), GpuHudFixtures.unit(2, OWN, true, true));
        var hidden = frame(authorized, 2, record(2), preview(2), 0)
              .withScene(UnitPlaybackFixture.scene(MOVED, SECOND))
              .withTimeline(List.of(new BoardScene.Concealed(9, 0), movement()));
        state.update(hidden, true);
        var shown = state.presented(hidden);
        assertEquals(Entity.NONE, state.inspected);
        assertTrue(state.presentedUnits().stream().noneMatch(unit -> unit.id() == 9));
        assertTrue(shown.status().units().stream().noneMatch(unit -> unit.id() == 9));
        assertEquals(Entity.NONE, shown.panels().record().unitId());
        assertSame(GpuFirePreview.Snapshot.NONE, shown.panels().preview());
        state.update(hidden.withTimeline(List.of()), true);
        assertSame(GpuFirePreview.Snapshot.NONE, state.presented(hidden).panels().preview(),
              "An empty subsequent event packet cannot restore revoked preview identities");
        assertEquals(1, state.focus());
    }

    private static void assertHeld(GpuHudState state, GpuBoardSource.Frame next, UnitPlayback playback) {
        assertTrue(playback.busy());
        assertEquals(Entity.NONE, state.update(next, playback.busy()));
        assertEquals(1, state.focus());
        assertEquals(1, state.cardUnit());
        assertEquals(1, state.presented(next).scene().selectedId());
    }

    private static BoardScene.Movement movement() {
        return new BoardScene.Movement(1, 0, List.of(FIRST.location(), MOVED.location()),
              EntityMovementType.MOVE_WALK, 0, 2, MOVED);
    }

    private static GpuBattleStatus.Snapshot status(GamePhase phase, int actor, int turn) {
        return GpuHudFixtures.status(1, phase, true, actor, turn,
              GpuHudFixtures.unit(1, OWN, actor == 1, actor == 1),
              GpuHudFixtures.unit(2, OWN, true, true), GpuHudFixtures.unit(9, ENEMY, false, false));
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, int selected,
          GpuUnitRecord.Snapshot record, GpuFirePreview.Snapshot preview, long generation) {
        BoardScene source = UnitPlaybackFixture.scene(FIRST, SECOND, ENEMY_UNIT);
        BoardScene scene = new BoardScene(source.boardId(), source.width(), source.height(), source.tiles(), source.units(),
              List.of(FIRST.location(), MOVED.location()), selected, status.phase().name(),
              List.of(new BoardScene.Command("captured unit command", true, () -> { })));
        var panels = new GpuHudData(new GpuBoardActions.PhaseInfo("ready", false, "done", "skip", "clear", List.of(), List.of()),
              GpuHudFixtures.move(true, List.of()), GpuFireOrders.Snapshot.EMPTY,
              new GpuPhysicalOptions.Snapshot(true, selected, Entity.NONE, List.of(9), List.of()), record, preview,
              new GpuChat.Snapshot(List.of()), new GpuToasts.Snapshot(List.of()), RulerModel.Snapshot.NONE,
              GpuPlayers.Snapshot.EMPTY);
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", new BoardFocus(0, null), generation,
              "Unit " + selected, BoardAtmosphere.DEFAULTS, GpuReportLog.Snapshot.EMPTY, status, panels);
    }

    private static GpuUnitRecord.Snapshot record(int id) {
        return new GpuUnitRecord.Snapshot(id, true, false, "", "", List.of(), List.of(), List.of(), List.of(),
              List.of(), List.of(), 0, new WeaponDisplayData.HeatBuildup(0, 0, ""), List.of(), List.of(), List.of(),
              List.of(), List.of(), List.of(), List.of(), List.of(), "", "", List.of(), List.of(), "", List.of());
    }

    private static GpuFirePreview.Snapshot preview(int id) {
        return new GpuFirePreview.Snapshot(true, true, id, false, new Coords(0, 0), 0, 0, "", 0, 0, "", false,
              1, 1, List.of(new GpuFirePreview.Contact(9, false, 3, GpuFirePreview.Side.NONE, GpuFirePreview.Side.NONE)));
    }
}
