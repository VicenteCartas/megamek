/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.bot.princess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.BotStatus;
import megamek.common.game.Game;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.marshalling.SanityInputFilter;
import megamek.common.net.packets.Packet;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the typed bot commands, {@link BehaviorChange} and {@link BotOrder}: what each does to a Princess, what
 * she reports afterwards, and that every one survives the network filter.
 */
class BotCommandMessagesTest {

    private static final Coords HEX = new Coords(4, 7);

    private Princess princess;
    private BehaviorSettings settings;
    private Game mockGame;

    @BeforeEach
    void setUp() {
        princess = spy(new Princess("TestPrincess", UUID.randomUUID().toString(), 1));
        mockGame = mock(Game.class);
        Board board = mock(Board.class);
        when(board.contains(any(Coords.class))).thenReturn(true);
        when(mockGame.getBoard()).thenReturn(board);
        doReturn(mockGame).when(princess).getGame();
        // there is no server connection to report through
        doNothing().when(princess).sendPrincessSettings();
        doNothing().when(princess).sendStatus();
        doNothing().when(princess).sendDishonoredData();
        settings = princess.getBehaviorSettings();
    }

    @Test
    void postureAndIndexChangeOnlyTheirValue() {
        settings.setBraveryIndex(3);
        settings.setCombatPosture(CombatPosture.AUTO);

        princess.applyBehaviorChanges(List.of(new BehaviorChange.Posture(CombatPosture.DEFEND),
              new BehaviorChange.Index(BehaviorChange.Trait.AGGRESSION, 9)));

        assertEquals(CombatPosture.DEFEND, settings.getCombatPosture());
        assertEquals(9, BehaviorChange.Trait.AGGRESSION.of(settings));
        assertEquals(3, BehaviorChange.Trait.BRAVERY.of(settings), "an index change must leave the others alone");
    }

    @Test
    void everyTraitReadsAndWritesItsOwnIndex() {
        int value = 1;
        for (BehaviorChange.Trait trait : BehaviorChange.Trait.values()) {
            new BehaviorChange.Index(trait, value).apply(princess);
            assertEquals(value, trait.of(settings), trait.name());
            value++;
        }
    }

    @Test
    void aStyleTakesThePersonalityButKeepsOrdersAndTargets() {
        settings.setCombatPosture(CombatPosture.DEFEND);
        settings.setPriorityUnit(12, 2);
        settings.addIgnoredUnitTarget(30);
        settings.setDestinationEdge(CardinalEdge.SOUTH);
        BehaviorSettings preset = new BehaviorSettings();
        preset.setHyperAggressionIndex(10);
        preset.setBraveryIndex(0);
        preset.setSelfPreservationIndex(1);
        preset.setCombatPosture(CombatPosture.ATTACK);

        new BehaviorChange.Style(preset).apply(princess);

        assertEquals(10, settings.getHyperAggressionIndex());
        assertEquals(0, settings.getBraveryIndex());
        assertEquals(1, settings.getSelfPreservationIndex());
        assertEquals(CombatPosture.DEFEND, settings.getCombatPosture());
        assertEquals(2, settings.getTargetPriority(12));
        assertTrue(settings.getIgnoredUnitTargets().contains(30));
        assertEquals(CardinalEdge.SOUTH, settings.getDestinationEdge());
    }

    @Test
    void aPriorityTargetIsSetChangedAndRemoved() {
        new BehaviorChange.PriorityTarget(12, 1).apply(princess);
        assertEquals(1, settings.getTargetPriority(12));

        new BehaviorChange.PriorityTarget(12, 4).apply(princess);
        assertEquals(4, settings.getTargetPriority(12));

        new BehaviorChange.PriorityTarget(12, BehaviorChange.PriorityTarget.REMOVE).apply(princess);
        assertFalse(settings.getPriorityUnitTargets().contains(12));
    }

    @Test
    void ignoredUnitsAreAddedRemovedAndCleared() {
        new BehaviorChange.Ignore(Set.of(1, 2, 3), true).apply(princess);
        new BehaviorChange.Ignore(Set.of(2), false).apply(princess);
        assertEquals(Set.of(1, 3), settings.getIgnoredUnitTargets());

        new BehaviorChange.ClearIgnored().apply(princess);
        assertTrue(settings.getIgnoredUnitTargets().isEmpty());
    }

    @Test
    void aStrategicHexIsAddedAndRemoved() {
        new BehaviorChange.StrategicHex(HEX, true).apply(princess);
        assertTrue(princess.buildStatus().strategicHexes().contains(HEX));

        new BehaviorChange.StrategicHex(HEX, false).apply(princess);
        assertFalse(princess.buildStatus().strategicHexes().contains(HEX));
    }

    @Test
    void aRetreatSendsTheBotOffTheBoardAndNoneCallsItOff() {
        new BehaviorChange.Retreat(CardinalEdge.WEST).apply(princess);
        assertEquals(CardinalEdge.WEST, settings.getDestinationEdge());
        assertTrue(settings.shouldAutoFlee());
        assertTrue(princess.getFallBack());

        new BehaviorChange.Retreat(CardinalEdge.NONE).apply(princess);
        assertEquals(CardinalEdge.NONE, settings.getDestinationEdge());
        assertFalse(settings.shouldAutoFlee());
        assertFalse(princess.getFallBack());
    }

    @Test
    void behaviorChangesAreReportedOnceAfterAllAreApplied() {
        princess.receiveBehaviorChanges(List.of(new BehaviorChange.Index(BehaviorChange.Trait.BRAVERY, 8),
              new BehaviorChange.Posture(CombatPosture.ATTACK)));

        assertEquals(8, settings.getBraveryIndex());
        verify(princess).sendPrincessSettings();
        verify(princess).sendStatus();
    }

    @Test
    void holdAndTagOrdersShowInTheStatus() {
        princess.receiveBotOrder(new BotOrder.Hold(true));
        princess.receiveBotOrder(new BotOrder.TagTarget(40));
        princess.receiveBotOrder(new BotOrder.TagTarget(41));

        BotStatus status = princess.buildStatus();
        assertTrue(status.holdingPosition());
        assertEquals(Set.of(40, 41), status.tagTargets());
        verify(princess, never()).sendDishonoredData();

        princess.receiveBotOrder(new BotOrder.ClearTagTargets());
        princess.receiveBotOrder(new BotOrder.Hold(false));
        assertEquals(BotStatus.NONE, princess.buildStatus());
    }

    @Test
    void aBloodFeudDishonorsTheKnownPlayerAndReportsIt() {
        when(mockGame.getPlayer(2)).thenReturn(new Player(2, "Enemy"));

        princess.receiveBotOrder(new BotOrder.BloodFeud(2));
        princess.receiveBotOrder(new BotOrder.BloodFeud(9));

        assertTrue(princess.getHonorUtil().isEnemyDishonored(2));
        assertFalse(princess.getHonorUtil().isEnemyDishonored(9), "an unknown player must be skipped");
        verify(princess, times(2)).sendDishonoredData();
    }

    @Test
    void theStatusIsACopyNotTheLiveSets() {
        princess.addDesignatedTagTarget(5);
        BotStatus status = princess.buildStatus();

        princess.clearDesignatedTagTargets();

        assertEquals(Set.of(5), status.tagTargets());
    }

    @Test
    void everyCommandPassesTheNetworkFilter() throws Exception {
        BehaviorSettings preset = new BehaviorSettings();
        preset.setHyperAggressionIndex(7);
        ArrayList<BehaviorChange> changes = new ArrayList<>(List.of(new BehaviorChange.Posture(CombatPosture.ATTACK),
              new BehaviorChange.Index(BehaviorChange.Trait.FALL_SHAME, 2), new BehaviorChange.Style(preset),
              new BehaviorChange.Retreat(CardinalEdge.NORTH), new BehaviorChange.PriorityTarget(3, 1),
              new BehaviorChange.StrategicHex(HEX, true), new BehaviorChange.Ignore(Set.of(4, 5), true),
              new BehaviorChange.ClearIgnored()));

        Packet changePacket = throughTheNetworkFilter(new Packet(PacketCommand.BOT_BEHAVIOR_CHANGE, 1, changes));
        assertEquals(changes, changePacket.getObject(1));

        for (BotOrder order : List.of(new BotOrder.Hold(true), new BotOrder.TagTarget(6),
              new BotOrder.ClearTagTargets(), new BotOrder.BloodFeud(2),
              new BotOrder.SetWaypoints(7, List.of(HEX)), new BotOrder.AddWaypoints(7, List.of(HEX)),
              new BotOrder.RemoveLastWaypoint(7), new BotOrder.ClearWaypoints(7))) {
            assertEquals(order, throughTheNetworkFilter(new Packet(PacketCommand.BOT_ORDER, 1, order)).getObject(1));
        }

        BotStatus status = new BotStatus(true, Set.of(6), Set.of(HEX), Map.of(3, List.of(HEX, new Coords(9, 9))));
        assertEquals(status, throughTheNetworkFilter(new Packet(PacketCommand.PRINCESS_STATUS, 1, status))
              .getObject(1));
    }

    @Test
    void everyUnitsWaypointsShowInTheStatusInOrder() {
        UnitBehavior tracker = trackerReachingEveryHex();
        Coords second = new Coords(9, 2);
        tracker.setEntityWaypoints(unit(7), List.of(HEX, second), princess);
        tracker.clearWaypoints(unit(8), princess);

        BotStatus status = princess.buildStatus();

        assertEquals(List.of(HEX, second), status.waypointsOf(7));
        assertEquals(Set.of(7), status.waypoints().keySet(), "a unit with no waypoints must be left out");
        assertEquals(List.of(), status.waypointsOf(8));
    }

    @Test
    void aWaypointChangeTheBotMadeItselfIsReported() {
        UnitBehavior tracker = trackerReachingEveryHex();

        princess.sendStatusIfChanged();
        verify(princess, never()).sendStatus();

        tracker.setEntityWaypoints(unit(7), List.of(HEX), princess);
        princess.sendStatusIfChanged();
        verify(princess).sendStatus();
    }

    @Test
    void waypointOrdersSetAddRemoveAndClearAUnitsWaypoints() {
        trackerReachingEveryHex();
        Entity own = unit(7);
        doReturn(List.of(own)).when(princess).getEntitiesOwned();
        Coords second = new Coords(9, 2);
        Coords third = new Coords(11, 3);

        princess.receiveBotOrder(new BotOrder.SetWaypoints(7, List.of(HEX, second)));
        princess.receiveBotOrder(new BotOrder.AddWaypoints(7, List.of(third)));
        assertEquals(List.of(HEX, second, third), princess.buildStatus().waypointsOf(7));

        princess.receiveBotOrder(new BotOrder.RemoveLastWaypoint(7));
        assertEquals(List.of(HEX, second), princess.buildStatus().waypointsOf(7));

        princess.receiveBotOrder(new BotOrder.ClearWaypoints(7));
        assertEquals(List.of(), princess.buildStatus().waypointsOf(7));
        verify(princess, times(4)).sendStatus();
    }

    @Test
    void waypointOrdersSkipOtherUnitsAndHexesOffTheBoard() {
        trackerReachingEveryHex();
        doReturn(List.of(unit(7))).when(princess).getEntitiesOwned();
        Coords offBoard = new Coords(99, 99);
        when(mockGame.getBoard().contains(offBoard)).thenReturn(false);

        princess.receiveBotOrder(new BotOrder.SetWaypoints(8, List.of(HEX)));
        princess.receiveBotOrder(new BotOrder.SetWaypoints(7, List.of(offBoard, HEX)));

        assertEquals(Set.of(7), princess.buildStatus().waypoints().keySet());
        assertEquals(List.of(HEX), princess.buildStatus().waypointsOf(7));
    }

    private UnitBehavior trackerReachingEveryHex() {
        UnitBehavior tracker = spy(new UnitBehavior());
        doReturn(false).when(tracker).isDestinationUnreachable(any(), any(), any());
        doReturn(tracker).when(princess).getUnitBehaviorTracker();
        return tracker;
    }

    private static Entity unit(int id) {
        Entity unit = mock(Entity.class);
        when(unit.getId()).thenReturn(id);
        when(unit.getDisplayName()).thenReturn("Unit " + id);
        return unit;
    }

    private static Packet throughTheNetworkFilter(Packet packet) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(packet);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            input.setObjectInputFilter(new SanityInputFilter());
            return (Packet) input.readObject();
        }
    }
}
