/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class RailDamageTest {
    @Test void damageRetainsKindAndCutsThroughNativeBoardsClipboardAndGameSnapshots() throws Exception {
        for (var kind : RailRoute.Kind.values()) {
            for (int direction = 0; direction < 6; direction++) {
                for (boolean bed : List.of(false, true)) {
                    var board = Board.createEmptyBoard(5, 5);
                    var at = new Coords(2, 2); var lost = at.translated(direction);
                    int sides = 1 << direction | 1 << ((direction + 3) % 6);
                    RailRoute.lay(board.getHex(at), false, sides, 0, kind);
                    RailRoute.lay(board.getHex(lost), false, sides, 0, kind);
                    RailRoute.destroyGround(board.getHex(lost));
                    if (!bed) { board.getHex(lost).removeAllTerrains(); board.getHex(lost).setLevel(-3); }
                    var bytes = new ByteArrayOutputStream(); BoardFile.write(board, bytes);
                    var loaded = BoardFile.readNative(bytes.toString(StandardCharsets.UTF_8));
                    String xml = megamek.common.util.SerializationHelper.getSaveGameXStream().toXML(board.getHex(lost));
                    var saved = (Hex) megamek.common.util.SerializationHelper.getLoadSaveGameXStream().fromXML(xml);
                    for (Hex copy : List.of(board.getHex(lost).duplicate(), loaded.getHex(lost),
                          BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(lost))), saved)) {
                        board.setHex(lost, copy);
                        assertFalse(RailRoute.intact(copy));
                        assertEquals(bed, copy.containsTerrain(Terrains.RAIL));
                        assertEquals(kind == RailRoute.Kind.RAIL ? 1 : 2, copy.getRuin(Terrains.RAIL).getLevel());
                        assertEquals(1 << direction, RailRoute.cuts(board.getHex(at), board::getHex).ground());
                        assertEquals(sides, board.getHex(at).getTerrain(Terrains.RAIL).getExits());
                    }
                    RailRoute.lay(board.getHex(lost), false, sides, 0, kind);
                    assertEquals(RailRoute.Cuts.NONE, RailRoute.cuts(board.getHex(at), board::getHex));
                }
            }
        }
    }

    @Test void unrelatedTracksAndUnreachableDecksDoNotAcquireDamage() {
        var board = Board.createEmptyBoard(5, 5);
        var at = new Coords(2, 2); var lost = at.translated(0);
        RailRoute.lay(board.getHex(at), false, 9, 0, RailRoute.Kind.RAIL);
        RailRoute.lay(board.getHex(lost), false, 9, 0, RailRoute.Kind.MAGLEV);
        RailRoute.destroyGround(board.getHex(lost));
        assertEquals(RailRoute.Cuts.NONE, RailRoute.cuts(board.getHex(at), board::getHex));
        board.setHex(lost, new Hex(0, "bridge:1:9;bridge_cf:20;bridge_elev:4;bridge_rail:1:9", ""));
        board.getHex(lost).destroyStructure(1);
        assertEquals(RailRoute.Cuts.NONE, RailRoute.cuts(board.getHex(at), board::getHex));
        board.setHex(at, new Hex(0, "bridge:1:9;bridge_cf:20;bridge_elev:4;bridge_rail:1:9", ""));
        assertEquals(new RailRoute.Cuts(0, 1), RailRoute.cuts(board.getHex(at), board::getHex));
        RailRoute.lay(board.getHex(at), false, 8, 0);
        assertEquals(0, RailRoute.cuts(board.getHex(at), board::getHex).ground(), "An authored terminus never joined the lost track");
    }

    @Test void destroyedRoutesKeepTheirOriginalReachableLayerAfterCrateringAndPersistence() throws Exception {
        for (var kind : RailRoute.Kind.values()) {
            for (boolean survivorDeck : List.of(false, true)) {
                for (boolean destroyedDeck : List.of(false, true)) {
                    for (boolean cleared : List.of(false, true)) {
                        var board = Board.createEmptyBoard(5, 5);
                        var at = new Coords(2, 2); var lost = at.translated(0);
                        var survivor = survivorDeck ? new Hex(0, "bridge:1:9;bridge_cf:20;bridge_elev:4", "") : new Hex(4);
                        var destroyed = destroyedDeck ? new Hex(0, "bridge:1:9;bridge_cf:20;bridge_elev:4", "") : new Hex(4);
                        RailRoute.lay(survivor, survivorDeck, 9, 0, kind);
                        RailRoute.lay(destroyed, destroyedDeck, 9, 0, kind);
                        board.setHex(at, survivor); board.setHex(lost, destroyed);
                        if (cleared) {
                            RailRoute.destroyAll(destroyed, destroyed.getLevel());
                            destroyed.removeAllTerrains();
                        } else if (destroyedDeck) { destroyed.destroyStructure(1); }
                        else { RailRoute.destroyGround(destroyed); }
                        destroyed.setLevel(destroyed.getLevel() - 3);
                        assertEquals(4, destroyed.getRailRuinLevel(destroyedDeck));
                        var bytes = new ByteArrayOutputStream(); BoardFile.write(board, bytes);
                        var loaded = BoardFile.readNative(bytes.toString(StandardCharsets.UTF_8));
                        String xml = megamek.common.util.SerializationHelper.getSaveGameXStream().toXML(destroyed);
                        var saved = (Hex) megamek.common.util.SerializationHelper.getLoadSaveGameXStream().fromXML(xml);
                        for (Hex copy : List.of(destroyed.duplicate(), loaded.getHex(lost),
                              BoardFile.fromClipboard(BoardFile.clipboard(destroyed)), saved)) {
                            board.setHex(lost, copy);
                            assertEquals(4, copy.getRailRuinLevel(destroyedDeck));
                            assertEquals(survivorDeck ? new RailRoute.Cuts(0, 1) : new RailRoute.Cuts(1, 0),
                                  RailRoute.cuts(survivor, board::getHex));
                            if (cleared && destroyedDeck) {
                                assertNull(copy.getRuin(Terrains.BRIDGE), "A vaporized bridge must not draw surviving piers");
                                var restored = copy.duplicate();
                                restored.restoreRuins();
                                assertFalse(restored.containsTerrain(Terrains.BRIDGE_RAIL), "Route history alone cannot restore a bridge");
                            }
                        }
                        var shifted = destroyed.duplicate();
                        shifted.shiftLevel(2);
                        assertEquals(6, shifted.getRailRuinLevel(destroyedDeck));
                        shifted.removeRuins();
                        assertNull(shifted.getRailRuinLevel(destroyedDeck));
                    }
                }
            }
        }
    }

    @Test void aLaterCraterKeepsOlderBedAndBridgeDamageWhoseAbsoluteHeightWasNotSaved() {
        for (boolean deck : List.of(false, true)) {
            var board = Board.createEmptyBoard(5, 5);
            var at = new Coords(2, 2); var lost = at.translated(0);
            board.setHex(at, new Hex(0, "bridge:1:9;bridge_cf:20;bridge_elev:4;bridge_rail:1:9", ""));
            var old = deck ? new Hex(0) : new Hex(4, "rail:0:9", "");
            if (deck) {
                old.addRuin(new Terrain(Terrains.BRIDGE, 1, true, 9));
                old.addRuin(new Terrain(Terrains.BRIDGE_ELEV, 4));
                old.addRuin(new Terrain(Terrains.BRIDGE_RAIL, 1, true, 9));
            }
            assertNull(old.getRailRuinLevel(deck));
            board.setHex(lost, old);
            assertEquals(new RailRoute.Cuts(0, 1), RailRoute.cuts(board.getHex(at), board::getHex));
            RailRoute.destroyAll(old, old.getLevel());
            old.setLevel(old.getLevel() - 3);
            old.removeAllTerrains();
            assertEquals(4, old.getRailRuinLevel(deck));
            assertEquals(new RailRoute.Cuts(0, 1), RailRoute.cuts(board.getHex(at), board::getHex));
        }
    }
}
