/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.lobby.NetworkInformationForm.Kind;
import megamek.client.lobby.NetworkInformationForm.Row;
import megamek.client.ui.Messages;
import org.junit.jupiter.api.Test;

class NetworkInformationFormTest {

    @Test
    void pendingKnowsTheHostAndLooksNothingUp() {
        AtomicInteger lookups = new AtomicInteger();
        NetworkInformationForm form = new NetworkInformationForm(() -> {
            lookups.incrementAndGet();
            return "192.168.1.5";
        }, () -> {
            lookups.incrementAndGet();
            return "203.0.113.7";
        }, "game.example.org");

        List<Row> rows = form.pending();

        assertEquals(0, lookups.get(), "the slow lookups wait for read()");
        assertEquals(List.of(Kind.LOCAL, Kind.PUBLIC, Kind.HOST), rows.stream().map(Row::kind).toList());
        assertTrue(rows.get(0).pending());
        assertFalse(rows.get(0).found());
        assertEquals(Messages.getString("NetworkInformation.native.looking"), rows.get(1).text());
        assertEquals("game.example.org", rows.get(2).text());
        assertTrue(rows.get(2).found());
    }

    @Test
    void readFindsTheAddresses() {
        NetworkInformationForm form = new NetworkInformationForm(() -> " 192.168.1.5 ", () -> "203.0.113.7",
              "localhost");

        List<Row> rows = form.read();

        assertEquals("192.168.1.5", rows.get(0).text(), "trimmed");
        assertEquals("203.0.113.7", rows.get(1).text());
        assertEquals("localhost", rows.get(2).text());
        assertTrue(rows.stream().allMatch(Row::found));
        assertTrue(rows.stream().noneMatch(Row::pending));
    }

    @Test
    void anAddressNotFoundSaysSoAndIsNotHidden() {
        NetworkInformationForm form = new NetworkInformationForm(() -> null, () -> "  ", null);

        List<Row> rows = form.read();

        assertTrue(rows.stream().noneMatch(Row::found));
        assertEquals(Kind.LOCAL.missing(), rows.get(0).text());
        assertEquals(Kind.PUBLIC.missing(), rows.get(1).text());
        assertEquals(Kind.HOST.missing(), rows.get(2).text());
    }
}
