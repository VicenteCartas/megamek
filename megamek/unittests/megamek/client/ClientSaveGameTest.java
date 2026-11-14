/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSaveGameTest {
    @Test
    void failedServerSaveReleasesTheWaitingClientWithoutOverwritingItsFile(@TempDir Path directory) throws Exception {
        Path previous = directory.resolve("previous.sav.gz");
        Files.writeString(previous, "existing local save");
        Client client = new Client("Save test", "localhost", 0);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<File> result = new AtomicReference<>(previous.toFile());
        client.setAwaitingSave(true);
        client.setSaveCompletionCallback(file -> { calls.incrementAndGet(); result.set(file); });
        assertTrue(client.handleGameSpecificPacket(new Packet(PacketCommand.SEND_SAVEGAME,
              previous.getFileName().toString(), null, directory.toString())));
        assertFalse(client.isAwaitingSave());
        assertEquals(1, calls.get());
        assertNull(result.get());
        assertEquals("existing local save", Files.readString(previous));
    }
}
