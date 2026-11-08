/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** CPU-only preflight for the native nuclear benchmark's actual source snapshot and weapon/ammunition binding. */
class GpuNuclearImpactSetupTest {
    static Stream<GpuNuclearImpactSmokeTest.Weapon> weapons() {
        return GpuNuclearImpactSmokeTest.weapons().filter(weapon -> !weapon.scheduled());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weapons")
    void observedLauncherHasCapturedArtworkAndTheActualNuclearMount(GpuNuclearImpactSmokeTest.Weapon weapon)
          throws Exception {
        EquipmentType.initializeTypes();
        Board board = new Board(8, 8, Stream.generate(Hex::new).limit(64).toArray(Hex[]::new));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            var launcher = onSwing(() -> GpuNuclearImpactSmokeTest.launcher(fixture, weapon));
            var entity = fixture.game.getEntity(launcher.unit().id());
            assertNotNull(entity);
            assertTrue(entity.isOffBoard());
            assertFalse(board.contains(entity.getPosition()));
            assertFalse(entity.isDestroyed());
            assertNotNull(launcher.unit().image());
            assertTrue(launcher.unit().image().width() > 1);
            assertTrue(launcher.unit().image().height() > 1);
            var mount = entity.getEquipment(launcher.equipment());
            assertEquals(EquipmentType.get(weapon.weapon()), mount.getType());
            assertNotNull(mount.getLinked(), "The actual gun retains its real nuclear ammunition mount");
            assertEquals(weapon.type(), launcher.profile().nuclearType());
            var frame = fixture.source.takeFrame();
            assertEquals(1, Stream.concat(frame.scene().units().stream(), fixture.source.reserveUnits().stream())
                  .filter(unit -> unit.id() == launcher.unit().id()).count(),
                  "Exactly one source snapshot warms the launcher before the GPU measurement");
        }
    }
}
