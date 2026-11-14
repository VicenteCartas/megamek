/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class NuclearTerrainTest {
    @Test void millionStrengthOrbitalStrikeBatchesTerrainAndSendsMarkersWithoutTerrainUpdates() throws Exception {
        List<Packet> packets = new ArrayList<>();
        var manager = new TWGameManager() {
            @Override public void send(Packet packet) { packets.add(packet); }
            @Override public void send(int connection, Packet packet) { packets.add(packet); }
        };
        Board board = Board.createEmptyBoard(27, 27);
        for (int x = 0; x < 27; x++) {
            for (int y = 0; y < 27; y++) { board.setHex(x, y, new Hex(0, "woods:1;road:1", "")); }
        }
        manager.getGame().setBoard(board);
        manager.getGame().addPlayer(0, new Player(0, "test"));
        manager.addScheduledOrbitalBombardment(new megamek.server.props.OrbitalBombardment.Builder()
              .x(13).y(13).damage(1_000_000).radius(10).build());
        assertEquals(1, packets.stream().filter(p -> p.command() == PacketCommand.SENDING_SPECIAL_HEX_DISPLAY).count());
        assertTrue(packets.stream().noneMatch(p -> p.command() == PacketCommand.CHANGE_HEX
              || p.command() == PacketCommand.CHANGE_HEXES), "Incoming markers do not change terrain");
        packets.clear();
        manager.resolveScheduledOrbitalBombardments();
        var updates = packets.stream().filter(p -> p.command() == PacketCommand.CHANGE_HEXES).toList();
        assertEquals(1, updates.size());
        assertEquals(331, updates.getFirst().getBoardLocationHexMap(0).size());
        assertTrue(packets.stream().noneMatch(p -> p.command() == PacketCommand.CHANGE_HEX));
        assertEquals(1, packets.stream().filter(p -> p.command() == PacketCommand.SENDING_SPECIAL_HEX_DISPLAY).count());
        var completion = packets.stream().filter(p -> p.command() == PacketCommand.NUCLEAR_DETONATION).toList().getLast();
        assertTrue(completion.getBooleanValue(1));
        assertTrue(packets.indexOf(updates.getFirst()) < packets.indexOf(completion),
              "The animation's completion captures the complete resolved terrain");
        assertFalse(board.getHex(13, 13).containsTerrain(Terrains.WOODS));
        assertEquals(0, board.getHex(13, 13).getLevel(), "Orbital bombardment does not dig a nuclear crater");
    }

    @Test void conventionalDamageRetainsTheBedAndOriginalRailKind() {
        var manager = new TWGameManager() {
            @Override public void send(Packet packet) { }
        };
        manager.getGame().getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_RAILS).setValue(true);
        var board = Board.createEmptyBoard(3, 3);
        manager.getGame().setBoard(board);
        var at = new Coords(1, 1);
        for (int kind : List.of(1, 2)) {
            board.setHex(at, new Hex(2, "rail:" + kind + ":9", ""));
            manager.damageTerrain(at, 0, 20, -1);
            var hex = board.getHex(at);
            assertEquals(0, hex.terrainLevel(Terrains.RAIL));
            assertEquals(kind, hex.getRuin(Terrains.RAIL).getLevel());
            assertEquals(9, RailRoute.groundSides(hex));
            assertEquals(2, hex.getLevel());
        }
    }

    @Test void radiusTenStrikeBatchesTerrainAndRemovesRoadsWithAndWithoutCratering() throws Exception {
        for (int depth : List.of(0, 3)) {
            List<Packet> packets = new ArrayList<>();
            var manager = new TWGameManager() {
                @Override public void send(Packet packet) { packets.add(packet); }
            };
            Board board = Board.createEmptyBoard(27, 27);
            for (int y = 0; y < 27; y++) {
                board.setHex(13, y, new Hex(0, "road:1;rail:1:9", ""));
                board.setHex(14, y, new Hex(0, "rail:2:9", ""));
            }
            manager.getGame().setBoard(board);
            manager.getGame().addPlayer(0, new Player(0, "test"));
            var at = new Coords(13, 13);
            manager.doNuclearExplosion(at, 10000, 1000, 10, depth, new Vector<>());
            var updates = packets.stream().filter(p -> p.command() == PacketCommand.CHANGE_HEXES).toList();
            assertEquals(1, updates.size());
            assertTrue(packets.stream().noneMatch(p -> p.command() == PacketCommand.CHANGE_HEX));
            assertEquals(331, updates.getFirst().getBoardLocationHexMap(0).size());
            assertEquals(-depth, board.getHex(at).getLevel());
            for (int y = 3; y <= 23; y++) {
                Hex hex = board.getHex(13, y);
                assertFalse(hex.containsTerrain(Terrains.ROAD));
                assertNotNull(hex.getRuin(Terrains.ROAD));
                assertFalse(hex.containsTerrain(Terrains.RAIL));
                assertEquals(1, hex.getRuin(Terrains.RAIL).getLevel());
                int distance = at.distance(13, y);
                int drop = depth == 0 || distance >= 6 ? 0 : distance <= 2 ? 3 : distance <= 4 ? 2 : 1;
                assertEquals(-drop, hex.getLevel());
            }
            Hex outside = board.getHex(13, 2);
            assertEquals(1, outside.getTerrain(Terrains.ROAD).getExits(), "Rules no longer connect to destroyed road");
            assertEquals(8, outside.roadCuts(board::getHex), "Presentation retains the former southbound approach");
            assertEquals(8, RailRoute.cuts(outside, board::getHex).ground());
            for (int y = 0; y < 27; y++) {
                Hex hex = board.getHex(14, y);
                if (at.distance(14, y) > 10) { continue; }
                assertFalse(RailRoute.intact(hex));
                assertEquals(2, hex.getRuin(Terrains.RAIL).getLevel());
            }
            packets.clear();
            manager.drawNukeHitOnBoard(new int[] { 13, 13 });
            assertTrue(packets.isEmpty(), "Scorch markers do not invalidate terrain or roads");
        }
    }

    @Test void radiusTenStrikePreservesBridgeRailAndMaglevCutsWithAndWithoutCratering() throws Exception {
        for (int depth : List.of(0, 3)) {
            for (int kind : List.of(1, 2)) {
                var manager = new TWGameManager() {
                    @Override public void send(Packet packet) { }
                };
                try {
                    Board original = Board.createEmptyBoard(27, 27);
                    for (int y = 0; y < 27; y++) {
                        original.setHex(13, y, new Hex(0,
                              "bridge:1:9;bridge_cf:20;bridge_elev:4;bridge_rail:" + kind + ":9", ""));
                    }
                    // Native loading also initializes the bridge's actual building records, used by blast damage.
                    var bytes = new ByteArrayOutputStream(); BoardFile.write(original, bytes);
                    Board board = BoardFile.readNative(bytes.toString(StandardCharsets.UTF_8));
                    manager.getGame().setBoard(board);
                    manager.getGame().addPlayer(0, new Player(0, "test"));
                    assertNotNull(board.getBuildingAt(new Coords(13, 3)));
                    manager.doNuclearExplosion(new Coords(13, 13), 10000, 1000, 10, depth, new Vector<>());
                    for (int y = 3; y <= 23; y++) {
                        Hex destroyed = board.getHex(13, y);
                        assertFalse(destroyed.containsTerrain(Terrains.BRIDGE_RAIL));
                        assertEquals(kind, destroyed.getRuin(Terrains.BRIDGE_RAIL).getLevel());
                        assertEquals(4, destroyed.getRailRuinLevel(true), "The original deck height survives the crater");
                        assertNull(destroyed.getRuin(Terrains.BRIDGE), "Route history cannot leave piers inside the blast");
                    }
                    Hex outside = board.getHex(13, 2);
                    assertEquals(kind, outside.terrainLevel(Terrains.BRIDGE_RAIL));
                    assertEquals(new RailRoute.Cuts(0, 8), RailRoute.cuts(outside, board::getHex));
                    assertEquals(-depth, board.getHex(13, 13).getLevel());
                } finally { manager.shutdown(); }
            }
        }
    }
}
