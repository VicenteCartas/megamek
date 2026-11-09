/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import megamek.common.board.Coords;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GameNuclearDetonationEvent;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.marshalling.PacketMarshaller;
import megamek.common.net.marshalling.PacketMarshallerFactory;
import megamek.common.net.packets.Packet;
import org.junit.jupiter.api.Test;

class NuclearDetonationTest {
    /**
     * A conventional explosion reaches exactly as far as the rules' damage table (its radius on the client is base damage
     * over degradation), and keeps its kind through its completion's collapses.
     */
    @Test
    void anExplosionReachesAsFarAsItsDamageTable() {
        for (int[] blast : new int[][] { { 80, 10 }, { 45, 4 }, { 300, 4 }, { 20, 4 }, { 40, 2 } }) {
            var explosion = NuclearDetonation.explosion(UUID.randomUUID(), 0, new Coords(5, 5), 0, blast[0], blast[1]);
            assertEquals(blast[1], explosion.baseDamage() / explosion.degradation(), "Reach of " + blast[0] + " over " + blast[1]);
            assertTrue(explosion.baseDamage() >= blast[0]);
            assertEquals(0, explosion.craterDepth());
            assertEquals(NuclearDetonation.Kind.EXPLOSION,
                  explosion.withCollapses(List.of(megamek.common.board.BoardLocation.of(new Coords(5, 6), 0))).kind());
        }
    }

    @Test
    void customStrikeSurvivesSerializationAndDispatchWithoutAnEntity() throws Exception {
        var original = new NuclearDetonation(UUID.randomUUID(), 7, new Coords(3, 8), -2, 1234, 31, 93, 2)
              .withCollapses(List.of(megamek.common.board.BoardLocation.of(new Coords(4, 8), 7)));
        var bytes = new ByteArrayOutputStream();
        var marshaller = PacketMarshallerFactory.getInstance()
              .getMarshaller(PacketMarshaller.NATIVE_SERIALIZATION_MARSHALING);
        marshaller.marshall(new Packet(PacketCommand.NUCLEAR_DETONATION, original, true), bytes);
        var packet = marshaller.unmarshall(new ByteArrayInputStream(bytes.toByteArray()));
        var received = (NuclearDetonation) packet.getObject(0);
        assertEquals(PacketCommand.NUCLEAR_DETONATION, packet.command());
        assertTrue(packet.getBooleanValue(1));
        assertEquals(original, received);
        var stats = received.stats();
        assertEquals(1234, stats.baseDamage);
        assertEquals(31, stats.degradation);
        assertEquals(93, stats.secondaryRadius);
        assertEquals(2, stats.craterDepth);
        stats.baseDamage = 0;
        assertEquals(1234, received.stats().baseDamage, "A consumer cannot change the confirmed payload");
        List<GameNuclearDetonationEvent> events = new ArrayList<>();
        var listener = new GameListenerAdapter() {
            @Override
            public void gameNuclearDetonation(GameNuclearDetonationEvent event) { events.add(event); }
        };
        new GameNuclearDetonationEvent(this, received, false).fireEvent(listener);
        new GameNuclearDetonationEvent(this, received, true).fireEvent(listener);
        assertEquals(2, events.size());
        assertEquals(received, events.getFirst().detonation());
        assertFalse(events.getFirst().completed());
        assertTrue(events.getLast().completed());
    }
}
