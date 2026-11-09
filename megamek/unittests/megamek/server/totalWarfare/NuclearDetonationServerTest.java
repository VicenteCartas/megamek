/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.NuclearDetonation;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.server.Server;
import megamek.server.commands.NukeCommand;
import megamek.server.props.OrbitalBombardment;
import org.junit.jupiter.api.Test;

class NuclearDetonationServerTest {
    @Test
    void stockAndCustomStrikesHaveOrderedBoundariesEvenWithoutTerrainChanges() throws Exception {
        var manager = spy(new TWGameManager());
        manager.getGame().setBoard(new Board(4, 4, Stream.generate(Hex::new).limit(16).toArray(Hex[]::new)));
        manager.getGame().addPlayer(0, new Player(0, "Nuclear test"));
        manager.getGame().getOptions().getOption(OptionsConstants.ALLOWED_ALLOW_NUKES).setValue(true);
        manager.getGame().getOptions().getOption(OptionsConstants.ALLOWED_REALLY_ALLOW_NUKES).setValue(true);
        List<Packet> packets = new ArrayList<>();
        doAnswer(call -> { packets.add(call.getArgument(0)); return null; }).when(manager).send(any(Packet.class));
        doNothing().when(manager).drawNukeHitOnBoard(any(int[].class));
        // The rules call is independent of its presentation; unchanged terrain must still close its boundary.
        doNothing().when(manager).doNuclearExplosion(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        var server = mock(Server.class);
        var game = manager.getGame();
        when(server.getGame()).thenReturn(game);
        var command = new NukeCommand(server, manager);
        command.run(0, new String[] { "nuke", "1", "1", "3" });
        command.run(0, new String[] { "nuke", "4", "4", "1234", "31", "93", "2" });
        packets.clear();
        manager.resolveScheduledNukes();
        var boundaries = packets.stream().filter(packet -> packet.command() == PacketCommand.NUCLEAR_DETONATION).toList();
        assertEquals(4, boundaries.size());
        for (int offset : new int[] { 0, 2 }) {
            assertFalse(boundaries.get(offset).getBooleanValue(1));
            assertTrue(boundaries.get(offset + 1).getBooleanValue(1));
            assertEquals(boundaries.get(offset).getObject(0), boundaries.get(offset + 1).getObject(0));
        }
        assertEquals(10000, ((NuclearDetonation) boundaries.getFirst().getObject(0)).baseDamage());
        assertEquals(new Coords(0, 0), ((NuclearDetonation) boundaries.getFirst().getObject(0)).coords());
        assertEquals(new Coords(3, 3), ((NuclearDetonation) boundaries.get(2).getObject(0)).coords());
        assertEquals(1234, ((NuclearDetonation) boundaries.get(2).getObject(0)).baseDamage());
        assertEquals(31, ((NuclearDetonation) boundaries.get(2).getObject(0)).degradation());
        packets.clear();
        manager.resolveScheduledNukes();
        assertTrue(packets.isEmpty(), "Resolved strikes must not replay on the next phase");
    }

    @Test
    void completionIsSentAfterIntermediateUpdatesEvenWhenResolutionThrows() throws Exception {
        var manager = spy(new TWGameManager());
        manager.getGame().setBoard(new Board(4, 4, Stream.generate(Hex::new).limit(16).toArray(Hex[]::new)));
        manager.getGame().addPlayer(0, new Player(0, "Nuclear test"));
        List<Packet> packets = new ArrayList<>();
        doAnswer(call -> { packets.add(call.getArgument(0)); return null; }).when(manager).send(any(Packet.class));
        doNothing().when(manager).drawNukeHitOnBoard(any(int[].class));
        doAnswer(call -> {
            manager.send(new Packet(PacketCommand.ENTITY_UPDATE));
            throw new IllegalStateException("resolution interrupted");
        }).when(manager).doNuclearExplosion(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        manager.addScheduledNuke(new int[] { 2, 2, 2 });
        packets.clear();
        assertThrows(IllegalStateException.class, manager::resolveScheduledNukes);
        assertEquals(List.of(PacketCommand.NUCLEAR_DETONATION, PacketCommand.ENTITY_UPDATE, PacketCommand.NUCLEAR_DETONATION),
              packets.stream().map(Packet::command).toList());
        assertFalse(packets.getFirst().getBooleanValue(1));
        assertTrue(packets.getLast().getBooleanValue(1));
    }

    /** A Game Master's orbital bombardment plays as a strike: its blast between a start and a completion, no crater. */
    @Test
    void anOrbitalBombardmentIsBracketedLikeAStrike() throws Exception {
        var manager = spy(new TWGameManager());
        manager.getGame().setBoard(new Board(4, 4, Stream.generate(Hex::new).limit(16).toArray(Hex[]::new)));
        manager.getGame().addPlayer(0, new Player(0, "Orbital test"));
        List<Packet> packets = new ArrayList<>();
        doAnswer(call -> { packets.add(call.getArgument(0)); return null; }).when(manager).send(any(Packet.class));
        doNothing().when(manager).doOrbitalBombardment(any(), anyInt(), anyInt());
        manager.addScheduledOrbitalBombardment(new OrbitalBombardment.Builder().x(1).y(1).radius(10).damage(1_000_000).build());
        packets.clear();
        manager.resolveScheduledOrbitalBombardments();
        var boundaries = packets.stream().filter(packet -> packet.command() == PacketCommand.NUCLEAR_DETONATION).toList();
        assertEquals(2, boundaries.size());
        assertFalse(boundaries.getFirst().getBooleanValue(1));
        assertTrue(boundaries.getLast().getBooleanValue(1));
        var strike = (NuclearDetonation) boundaries.getFirst().getObject(0);
        assertEquals(megamek.common.NuclearDetonation.Kind.ORBITAL, strike.kind());
        assertEquals(new Coords(1, 1), strike.coords());
        assertEquals(0, strike.craterDepth(), "An orbital bombardment digs no crater");
        assertEquals(11, strike.baseDamage() / strike.degradation(), "Its blast reaches its radius and the hex beyond");
    }

    /**
     * A fusion engine's explosion at 0202 beside a light building at 0203 (CF 15; 25 damage a hex out): its blast between
     * an explosion's start and completion, which names the building it brought down. Without the building it brings
     * nothing down and sends no explosion.
     */
    @Test
    void anEngineExplosionIsSentOnlyWhenItBringsSomethingDown() throws Exception {
        for (boolean building : new boolean[] { true, false }) {
            Hex[] hexes = Stream.generate(Hex::new).limit(16).toArray(Hex[]::new);
            if (building) {
                hexes[2 * 4 + 1].addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BUILDING, 1));
                hexes[2 * 4 + 1].addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_CF, 15));
                hexes[2 * 4 + 1].addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_ELEV, 1));
            }
            var manager = new TWGameManager();
            var board = new Board();
            board.newData(4, 4, hexes, null);
            manager.getGame().setBoard(board);
            manager.getGame().addPlayer(0, new Player(0, "Explosion test"));
            List<Packet> packets = new ArrayList<>();
            Server server = mock(Server.class, call -> {
                if (call.getMethod().getName().equals("send")) { packets.add(call.getArgument(call.getArguments().length - 1)); }
                return null;
            });
            try (var servers = org.mockito.Mockito.mockStatic(Server.class)) {
                servers.when(Server::getServerInstance).thenReturn(server);
                manager.doFusionEngineExplosion(250, megamek.common.board.BoardLocation.of(new Coords(1, 1), 0),
                      new java.util.Vector<>(), null);
            }
            var boundaries = packets.stream().filter(packet -> packet.command() == PacketCommand.NUCLEAR_DETONATION).toList();
            if (!building) {
                assertEquals(List.of(), boundaries, "Nothing came down");
                continue;
            }
            assertEquals(2, boundaries.size());
            assertFalse(boundaries.getFirst().getBooleanValue(1));
            assertTrue(boundaries.getLast().getBooleanValue(1));
            var explosion = (NuclearDetonation) boundaries.getLast().getObject(0);
            assertEquals(NuclearDetonation.Kind.EXPLOSION, explosion.kind());
            assertEquals(new Coords(1, 1), explosion.coords());
            assertEquals(4, explosion.baseDamage() / explosion.degradation(), "It reaches as far as its damage table");
            assertEquals(List.of(megamek.common.board.BoardLocation.of(new Coords(1, 2), 0)), explosion.collapses());
        }
    }

    /** An explosion on a second board damages the woods of its own board, its hex as the hexes around it. */
    @Test
    void anExplosionClearsTheWoodsOfItsOwnBoard() {
        var manager = new TWGameManager();
        Board[] boards = new Board[2];
        for (int id = 0; id < 2; id++) {
            Hex[] hexes = Stream.generate(Hex::new).limit(16).toArray(Hex[]::new);
            for (Hex hex : hexes) { hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.WOODS, 1)); }
            boards[id] = new Board();
            boards[id].newData(4, 4, hexes, null);
            boards[id].setBoardId(id);
        }
        manager.getGame().setBoard(boards[0]);
        manager.getGame().setBoard(1, boards[1]);
        manager.getGame().addPlayer(0, new Player(0, "Explosion test"));
        Server server = mock(Server.class);
        try (var servers = org.mockito.Mockito.mockStatic(Server.class)) {
            servers.when(Server::getServerInstance).thenReturn(server);
            manager.doExplosion(100, 50, false, new Coords(1, 1), 1, false, new java.util.Vector<>(), null, -1, false);
        }
        for (Coords at : List.of(new Coords(1, 1), new Coords(1, 2))) {
            assertFalse(boards[1].getHex(at).containsTerrain(megamek.common.units.Terrains.WOODS), at + " on its board");
            assertTrue(boards[0].getHex(at).containsTerrain(megamek.common.units.Terrains.WOODS), at + " on the other");
        }
    }
}

