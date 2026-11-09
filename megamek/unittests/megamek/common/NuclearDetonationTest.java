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
