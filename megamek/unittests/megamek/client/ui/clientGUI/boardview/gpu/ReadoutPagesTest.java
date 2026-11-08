/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.ratgenerator.RATGenerator;
import megamek.client.ratgenerator.UnitAvailability;
import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.client.ui.entityreadout.EntityReadout.Row.Kind;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** The readout's Alpha Strike and faction pages, as rows, without a GL context. */
class ReadoutPagesTest {

    @Test
    void theFactionPageTitlesEachEraAndListsTheFactionsThatFieldTheUnit() {
        Map<String, Map<String, String>> texts = Map.of(
              "Succession\nWars", Map.of("FS", "Common\n+1", "LA", "Rare"),
              "Clan Invasion", Map.of("FS", "Common"));
        UnitAvailability<String> availability = UnitAvailability.of(
              List.of("Succession\nWars", "Renaissance", "Clan Invasion"), List.of("LA", "FS", "XX"),
              (era, faction) -> texts.getOrDefault(era, Map.of()).get(faction));
        Map<String, String> names = Map.of("FS", "Federated Suns", "LA", "Lyran Alliance");
        List<Row> rows = ReadoutAvailability.rows(availability, era -> era,
              code -> names.getOrDefault(code, code));
        assertEquals(List.of(
              new Row(Kind.TITLE, List.of("Succession Wars")),
              new Row(Kind.TABLE_ROW, List.of("Federated Suns", "Common, +1")),
              new Row(Kind.TABLE_ROW, List.of("Lyran Alliance", "Rare")),
              new Row(Kind.TITLE, List.of("Clan Invasion")),
              new Row(Kind.TABLE_ROW, List.of("Federated Suns", "Common"))), rows,
              "an era nobody fields the unit in is left out, and the factions go by name");
    }

    @Test
    void theFactionPageWaitsForTheForceGeneratorRatherThanShowingNone() throws Exception {
        RATGenerator generator = mock(RATGenerator.class);
        AtomicBoolean loaded = new AtomicBoolean();
        when(generator.isInitialized()).thenAnswer(invocation -> loaded.get());
        List<ActionListener> listeners = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> listeners.add(invocation.getArgument(0))).when(generator).registerListener(any());
        doAnswer(invocation -> listeners.remove(invocation.getArgument(0))).when(generator).removeListener(any());
        List<Row> factions = List.of(new Row(Kind.TEXT, List.of("Davion")));
        AtomicInteger reads = new AtomicInteger();
        LobbyReadoutView.LaterRows later = ReadoutAvailability.later("Waiting Test\n" + System.nanoTime(),
              () -> generator, () -> {
                  reads.incrementAndGet();
                  return factions;
              });

        CountDownLatch asked = new CountDownLatch(1);
        later.read(asked::countDown);
        CountDownLatch again = new CountDownLatch(1);
        later.read(again::countDown);
        Thread.sleep(200);
        assertEquals(1, asked.getCount(), "nothing is shown while the Force Generator loads");
        assertNull(later.rows(), "the page keeps saying it is reading");
        assertEquals(0, reads.get());
        assertEquals(1, listeners.size(), "a second ask waits on the same listener");

        loaded.set(true);
        for (ActionListener listener : listeners) {
            listener.actionPerformed(null);
        }
        assertTrue(again.await(5, TimeUnit.SECONDS), "the page that asked last is told once it is ready");
        assertEquals(factions, later.rows());
        assertEquals(1, reads.get());
        assertTrue(listeners.isEmpty(), "the listener goes once the rows are read");
    }
    @Test
    void theAlphaStrikePageHasTheCardAndTheConversion() throws Exception {
        EquipmentType.initializeTypes();
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        ReadoutCalculationReport.AlphaStrike alphaStrike = ReadoutCalculationReport.alphaStrike(atlas, 320);
        assertNotNull(alphaStrike.card());
        assertEquals(320, alphaStrike.card().getWidth());
        assertFalse(alphaStrike.rows().isEmpty());
        assertTrue(alphaStrike.rows().stream().anyMatch(row -> row.kind() == Kind.TITLE), "the report has headers");
    }

    @Test
    void aBattlefieldSupportAssetHasNoAlphaStrikePage() {
        ReadoutCalculationReport.AlphaStrike alphaStrike = ReadoutCalculationReport.alphaStrike(
              new BattlefieldSupportAsset(), 320);
        assertNull(alphaStrike.card());
        assertTrue(alphaStrike.rows().isEmpty());
    }
}
