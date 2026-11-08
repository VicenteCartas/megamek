/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;

class UnitAttachmentsTest {
    @Test
    void exteriorOccupancyUsesTheCarrierAndDoesNotRevealInternalOrUnidentifiedPassengers() {
        for (Entity carrier : List.of(mock(BipedMek.class), mock(Tank.class))) {
            var exterior = mock(BattleArmor.class);
            var internal = mock(BattleArmor.class);
            when(carrier.getId()).thenReturn(1);
            when(exterior.getId()).thenReturn(2);
            when(internal.getId()).thenReturn(3);
            when(carrier.getSwarmAttackerId()).thenReturn(Entity.NONE);
            when(carrier.getLoadedUnits()).thenReturn(List.of(exterior, internal));
            when(carrier.getExternalUnits()).thenReturn(List.of(exterior));
            var entities = List.of(carrier, exterior, internal);
            var captured = GpuBoardSource.exteriorAttachments(entities, unit -> true, unit -> unit == carrier);
            assertEquals(new BoardScene.Attachment(1, false), captured.get(2));
            assertFalse(captured.containsKey(3));
            assertTrue(GpuBoardSource.exteriorAttachments(entities, unit -> unit != exterior, unit -> unit == carrier).isEmpty());
            assertTrue(GpuBoardSource.exteriorAttachments(entities, unit -> unit != carrier, unit -> true).isEmpty());
            when(carrier.getExternalUnits()).thenReturn(List.of());
            when(carrier.getSwarmAttackerId()).thenReturn(2);
            when(exterior.getSwarmTargetId()).thenReturn(Entity.NONE);
            assertEquals(new BoardScene.Attachment(1, true),
                  GpuBoardSource.exteriorAttachments(entities, unit -> true, unit -> unit == carrier).get(2),
                  "Passenger updates cannot release a swarmer before its carrier's path arrives");
        }
    }

    @Test
    void friendlyPassengersAndHostileSwarmersKeepSeparateBindingsOnTheSameCarrier() {
        for (Entity carrier : List.of(mock(BipedMek.class), mock(Tank.class))) {
            var rider = mock(BattleArmor.class);
            var swarmer = mock(BattleArmor.class);
            when(carrier.getId()).thenReturn(1);
            when(rider.getId()).thenReturn(2);
            when(swarmer.getId()).thenReturn(3);
            when(carrier.getExternalUnits()).thenReturn(List.of(rider));
            when(carrier.getSwarmAttackerId()).thenReturn(3);
            var entities = List.of(carrier, rider, swarmer);
            var captured = GpuBoardSource.exteriorAttachments(entities, unit -> true, unit -> unit == carrier);
            assertEquals(new BoardScene.Attachment(1, false), captured.get(2));
            assertEquals(new BoardScene.Attachment(1, true), captured.get(3));
            var concealed = GpuBoardSource.exteriorAttachments(entities, unit -> unit != swarmer, unit -> unit == carrier);
            assertEquals(new BoardScene.Attachment(1, false), concealed.get(2));
            assertFalse(concealed.containsKey(3));
        }
    }

    @Test
    void jumpArrivalFlowsDirectlyIntoReleaseAndPauseFreezesTheFall() {
        var carrier = UnitPlaybackFixture.unit(1, 0);
        var arrival = UnitPlaybackFixture.unit(1, 4);
        var riding = UnitPlaybackFixture.unit(2, 0).withAttachment(new BoardScene.Attachment(1, true));
        var landed = UnitPlaybackFixture.unit(2, 4);
        var before = UnitPlaybackFixture.scene(carrier, riding);
        var latest = UnitPlaybackFixture.scene(arrival, landed);
        var release = new BoardScene.AttachmentChange(0, riding, landed, arrival, BoardScene.Release.JUMP);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(before), new BoardScene.Movement(1, 0,
                    List.of(carrier.location(), arrival.location()), EntityMovementType.MOVE_JUMP, 4, 4, arrival),
              new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(arrival, riding)), release,
              new BoardScene.SceneUpdate(latest)), latest, ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        playback.advance(playback.motions.get(1).remainingSeconds() / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertNotNull(playback.attachment(), "No completion hold between landing and release");
        assertEquals(arrival.location(), find(playback.present(latest), 1).location());
        playback.advance(.4, UnitMotion.Speed.NORMAL);
        float progress = playback.attachment().progress();
        playback.togglePaused();
        playback.advance(20, UnitMotion.Speed.NORMAL);
        assertEquals(progress, playback.attachment().progress());
        playback.togglePaused();
        playback.advance(20, UnitMotion.Speed.NORMAL);
        assertEquals(landed, find(playback.present(latest), 2));
        assertFalse(playback.busy());
    }

    @Test
    void brushReleasesAtContactOnSuccessAndKeepsTheBindingOnFailure() {
        for (boolean hit : List.of(false, true)) {
            var carrier = UnitPlaybackFixture.unit(1, 0);
            var riding = UnitPlaybackFixture.unit(2, 0).withAttachment(new BoardScene.Attachment(1, true));
            var friendly = UnitPlaybackFixture.unit(3, 0).withAttachment(new BoardScene.Attachment(1, false));
            var after = riding.withAttachment(hit ? null : riding.attachment());
            var before = UnitPlaybackFixture.scene(carrier, riding, friendly);
            var latest = UnitPlaybackFixture.scene(carrier, after, friendly);
            var events = new java.util.ArrayList<BoardScene.Animation>();
            events.add(new BoardScene.SceneUpdate(before));
            events.add(UnitPlaybackFixture.attack(carrier, riding, ResolvedAttack.Kind.BRUSH_OFF, hit));
            if (hit) { events.add(new BoardScene.AttachmentChange(0, riding, after, carrier, BoardScene.Release.THROWN)); }
            events.add(new BoardScene.SceneUpdate(latest));
            var playback = new UnitPlayback();
            playback.accept(events, latest, ignored -> false);
            playback.advance(0, UnitMotion.Speed.NORMAL);
            playback.advance((playback.attack().contactSeconds - .01) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            assertNotNull(find(playback.present(latest), 2).attachment());
            assertNull(playback.attachment());
            playback.advance(.02 / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
            assertEquals(hit, playback.attachment() != null);
            if (!hit) { assertNotNull(find(playback.present(latest), 2).attachment()); }
            assertEquals(friendly, find(playback.present(latest), 3), "The brush-off only targets the hostile squad");
            playback.advance(20, UnitMotion.Speed.NORMAL);
            assertEquals(after, find(playback.present(latest), 2));
            assertEquals(friendly, find(playback.present(latest), 3));
        }
    }

    @Test
    void removedPassengersFinishFallingAndInstantOrConcealmentClearsTheTransition() {
        var carrier = UnitPlaybackFixture.unit(1, 0);
        var passenger = UnitPlaybackFixture.unit(2, 0).withAttachment(new BoardScene.Attachment(1, true));
        var latest = UnitPlaybackFixture.scene(carrier);
        List<BoardScene.Animation> events = List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(carrier, passenger)),
              new BoardScene.AttachmentChange(0, passenger, null, carrier, BoardScene.Release.WATER), new BoardScene.SceneUpdate(latest));
        for (boolean conceal : List.of(false, true)) {
            var playback = new UnitPlayback();
            playback.accept(events, latest, ignored -> false);
            playback.advance(.6, UnitMotion.Speed.NORMAL);
            assertNotNull(find(playback.present(latest), 2));
            if (conceal) { playback.accept(List.of(new BoardScene.Concealed(2, 0)), latest, ignored -> false); }
            else { playback.advance(0, UnitMotion.Speed.INSTANT); }
            assertNull(playback.attachment());
            assertTrue(playback.present(latest).units().stream().noneMatch(unit -> unit.id() == 2));
        }
    }

    @Test
    void consecutiveReleasesDoNotRestoreThePassengerThatAlreadyLanded() {
        var carrier = UnitPlaybackFixture.unit(1, 0);
        var first = UnitPlaybackFixture.unit(2, 0).withAttachment(new BoardScene.Attachment(1, false));
        var second = UnitPlaybackFixture.unit(3, 0).withAttachment(new BoardScene.Attachment(1, true));
        var firstEnd = UnitPlaybackFixture.unit(2, 1);
        var secondEnd = UnitPlaybackFixture.unit(3, 2);
        var latest = UnitPlaybackFixture.scene(carrier, firstEnd, secondEnd);
        var playback = new UnitPlayback();
        playback.accept(List.of(new BoardScene.SceneUpdate(UnitPlaybackFixture.scene(carrier, first, second)),
              new BoardScene.AttachmentChange(0, first, firstEnd, carrier, BoardScene.Release.CLIMB_DOWN),
              new BoardScene.AttachmentChange(0, second, secondEnd, carrier, BoardScene.Release.THROWN),
              new BoardScene.SceneUpdate(latest)), latest, ignored -> false);
        playback.advance(UnitAttachmentMotion.DURATION / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        assertEquals(3, playback.attachment().event.entityId());
        assertEquals(firstEnd, find(playback.present(latest), 2), "The first completed transition stays settled");
    }

    @Test
    void trajectoriesReachRaisedLoweredAndSameHexDestinationsContinuously() {
        var from = new Vector3(3, 4, 30);
        for (var to : List.of(new Vector3(50, -20, 40), new Vector3(3, 4, -20), new Vector3(-40, 30, 0))) {
            assertEquals(from, UnitAttachmentMotion.trajectory(from, to, Vector3.X, 0, 40, 20, new Vector3()));
            assertEquals(to, UnitAttachmentMotion.trajectory(from, to, Vector3.X, 1, 40, 20, new Vector3()));
            var almost = UnitAttachmentMotion.trajectory(from, to, Vector3.X, .9999f, 40, 20, new Vector3());
            assertTrue(almost.dst(to) < .001f, "No terminal snap");
            Vector3 previous = from;
            for (int frame = 1; frame <= 120; frame++) {
                var current = UnitAttachmentMotion.trajectory(from, to, Vector3.X, frame / 120f, 40, 20, new Vector3());
                assertTrue(current.dst(previous) < 4, "The entire fall is continuous");
                previous = current;
            }
        }
    }

    private static BoardScene.Unit find(BoardScene scene, int id) {
        return scene.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }
}
