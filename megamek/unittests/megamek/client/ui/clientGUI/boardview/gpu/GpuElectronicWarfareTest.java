/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare.ENEMY_ECCM;
import static megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare.ENEMY_ECM;
import static megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare.JAMMED;
import static megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare.OWN_ECCM;
import static megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare.OWN_ECM;
import static megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare.RING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.common.ECMInfo;
import megamek.common.board.Coords;
import megamek.common.compute.ComputeECM;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscType;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Test;

/** The board's E(C)CM snapshot against the rules, and the GL pass's open field, exclusive mask and pulse clock. */
class GpuElectronicWarfareTest {
    @Test
    void snapshotIsComputeEcmOnEveryHexOfTheScenario() throws Exception {
        try (GpuEcmScenario scenario = GpuEcmScenario.create()) {
            SwingUtilities.invokeAndWait(() -> {
                var game = scenario.fixture.game;
                BoardElectronicWarfare warfare = scenario.fixture.view.electronicWarfare();
                assertSame(warfare, scenario.fixture.source.takeFrame().scene().electronicWarfare());
                assertTrue(scenario.fixture.source.takeFrame().scene().tactical().fills().isEmpty(),
                      "E(C)CM leaves the draped washes and floating rings");
                List<ECMInfo> fields = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
                assertEquals(3, fields.size());
                int jammed = 0, covered = 0;
                for (int x = 0; x < game.getBoard().getWidth(); x++) {
                    for (int y = 0; y < game.getBoard().getHeight(); y++) {
                        Coords coords = new Coords(x, y);
                        int hex = warfare.hex(coords), kinds = 0;
                        Set<Integer> sources = new HashSet<>();
                        for (ECMInfo field : fields) {
                            if (coords.distance(field.getPos()) <= field.getRange()) {
                                boolean own = !field.isOpposed(scenario.fixture.player);
                                kinds |= own ? (field.isECCM() ? OWN_ECCM : OWN_ECM) : (field.isECCM() ? ENEMY_ECCM : ENEMY_ECM);
                                sources.add(field.getEntity().getId());
                            }
                        }
                        assertEquals(kinds, hex & 15, "side and kind at " + coords);
                        assertEquals(sources.size(), BoardElectronicWarfare.count(hex), "sources at " + coords);
                        boolean affected = ComputeECM.isAffectedByECM(scenario.archer, coords, coords);
                        assertEquals(affected, (hex & JAMMED) != 0, "jammed at " + coords);
                        boolean ring = kinds == 0 && List.of(0, 1, 2, 3, 4, 5).stream()
                              .anyMatch(direction -> (warfare.hex(coords.translated(direction)) & 15) != 0);
                        assertEquals(ring, (hex & RING) != 0, "ring at " + coords);
                        jammed += affected ? 1 : 0;
                        covered += kinds != 0 ? 1 : 0;
                    }
                }
                // The mock-ups' numbers: 267 of 544 hexes covered, 79 where an own unit is jammed.
                assertEquals(267, covered);
                assertEquals(79, jammed);
                assertEquals(List.of(scenario.atlas.getId(), scenario.crab.getId(), scenario.warwolf.getId()),
                      warfare.sources().stream().map(BoardElectronicWarfare.Source::entityId).sorted().toList());
            });
        }
    }

    @Test
    void thePulseLightsOnlyTheHexesNoOtherFieldCovers() throws Exception {
        try (GpuEcmScenario scenario = GpuEcmScenario.create()) {
            SwingUtilities.invokeAndWait(() -> {
                BoardElectronicWarfare warfare = scenario.fixture.view.electronicWarfare();
                var open = GpuElectronicWarfare.open(warfare, scenario.warwolf.getId(), null, Entity.NONE);
                Set<Coords> alone = new HashSet<>(open.source().hexes());
                warfare.sources().stream().filter(source -> source != open.source())
                      .forEach(source -> alone.removeAll(source.hexes()));
                // The mock-up: 61 of the Warwolf's 127 hexes; its overlaps with the own Atlas ECM and Crab ECCM stay dark.
                assertEquals(127, open.source().hexes().size());
                assertEquals(61, alone.size());
                int[] texels = GpuElectronicWarfare.texels(warfare, open.source());
                Set<Coords> lit = new HashSet<>();
                for (int x = 0; x < warfare.width(); x++) {
                    for (int y = 0; y < warfare.height(); y++) {
                        int texel = texels[x * warfare.height() + y];
                        if ((texel >>> 16 & 255) == 1 && (texel >>> 8 & 255) != 0) { lit.add(new Coords(x, y)); }
                        assertEquals(warfare.hex(new Coords(x, y)) & 63, texel >>> 24);
                    }
                }
                assertEquals(alone, lit);
            });
        }
    }

    @Test
    void hoverOpensBeforeTheHoveredHexAndTheSelection() throws Exception {
        try (GpuEcmScenario scenario = GpuEcmScenario.create()) {
            SwingUtilities.invokeAndWait(() -> {
                BoardElectronicWarfare warfare = scenario.fixture.view.electronicWarfare();
                int atlas = scenario.atlas.getId(), warwolf = scenario.warwolf.getId();
                var hovered = GpuElectronicWarfare.open(warfare, warwolf, scenario.crab.getPosition(), atlas);
                assertEquals(warwolf, hovered.source().entityId(), "inspecting an enemy while your ECM unit is selected");
                assertTrue(hovered.hovered());
                var onHex = GpuElectronicWarfare.open(warfare, Entity.NONE, scenario.crab.getPosition(), atlas);
                assertEquals(scenario.crab.getId(), onHex.source().entityId());
                assertTrue(onHex.hovered());
                var selected = GpuElectronicWarfare.open(warfare, scenario.archer.getId(), new Coords(0, 0), atlas);
                assertEquals(atlas, selected.source().entityId(), "a unit without E(C)CM opens nothing");
                assertFalse(selected.hovered());
                assertNull(GpuElectronicWarfare.open(warfare, Entity.NONE, null, scenario.archer.getId()));
            });
        }
    }

    @Test
    void oneUnitWithEcmAndEccmCountsOnceAndUnseenFieldsSetNothing() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            fixture.addEcm();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.entity.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_RIGHT_ARM);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_ECCM).setValue(true);
                fixture.entity.setGameOptions();
                var suites = fixture.entity.getMisc().stream().filter(m -> m.getType().hasFlag(MiscType.F_ECM)).toList();
                assertTrue(suites.getLast().setModeImmediately("ECCM") >= 0);
                fixture.view.updateEcmList();
                BoardElectronicWarfare both = fixture.view.electronicWarfare();
                assertEquals(1, both.sources().size());
                var source = both.sources().getFirst();
                assertTrue(source.ecm() && source.eccm() && source.own());
                int hex = both.hex(fixture.entity.getPosition());
                assertEquals(OWN_ECM | OWN_ECCM, hex & 15);
                assertEquals(1, BoardElectronicWarfare.count(hex));

                var enemy = new megamek.common.Player(1, "Unseen");
                enemy.setTeam(2);
                fixture.game.addPlayer(enemy.getId(), enemy);
                fixture.entity.setOwner(enemy);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.view.updateEcmList();
                assertTrue(fixture.view.electronicWarfare().hexes().stream().allMatch(value -> value == 0)
                      && fixture.view.electronicWarfare().sources().isEmpty(), "an unseen emitter shows nothing");
            });
        }
    }

    /**
     * The Overlays panel's Fields and Jamming rows are client settings: the board's published preferences carry both
     * to the pass, and the E(C)CM snapshot, which keeps only what the rules and ECM Transparency decide, stays as it
     * was.
     */
    @Test
    void theFieldsAndJammingSettingsAreRememberedAndReachThePublishedPreferences() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean hatch = preferences.getEcmTacticalHatch();
        boolean fields = preferences.getEcmTacticalFields();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            fixture.addEcm();
            BoardElectronicWarfare[] before = new BoardElectronicWarfare[1];
            SwingUtilities.invokeAndWait(() -> {
                var overlays = fixture.source.uiPreferences().overlays();
                assertEquals(hatch, overlays.ecmHatch());
                assertEquals(fields, overlays.ecmFields());
                before[0] = fixture.view.electronicWarfare();
                preferences.toggleEcmTacticalHatch();
                preferences.toggleEcmTacticalFields();
            });
            // The source follows the client settings through its preference listener on the Swing thread.
            SwingUtilities.invokeAndWait(() -> {
                var overlays = fixture.source.uiPreferences().overlays();
                assertEquals(!hatch, overlays.ecmHatch());
                assertEquals(!fields, overlays.ecmFields());
                assertSame(before[0], fixture.view.electronicWarfare(), "the rule snapshot is not recaptured");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (preferences.getEcmTacticalHatch() != hatch) { preferences.toggleEcmTacticalHatch(); }
                if (preferences.getEcmTacticalFields() != fields) { preferences.toggleEcmTacticalFields(); }
            });
        }
    }
}
