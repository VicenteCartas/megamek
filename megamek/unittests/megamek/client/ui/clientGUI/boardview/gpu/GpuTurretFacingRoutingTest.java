/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.dispose;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.labels;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.pick;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuImplantBlowFacingRoutingTest.previewingClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.dialogs.TurretFacingDialog;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The turret facing picker (swing-inventory table 2, D12) asked in the native battle window: a Mek's shoulder turret
 * turns, and a vehicle's main turret hands its facing to the attack display, exactly as the Swing dialog does for the
 * same choice, cancelling included (GpuSwingDialogGateTest covers the Swing dialog while no native window draws
 * dialogs). The test thread plays the GL thread; the Swing dialogs need a display.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuTurretFacingRoutingTest {

    @Test
    void nativeTurretFacingTurnsTheSameMountAndHandsOnTheSameFacing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        Turrets turrets = Turrets.create();
        try {
            turrets.presentWindow();
            int turret = turrets.atlas.getEquipmentNum(turrets.turret);
            Asked<Boolean> turned = ask(turrets.fixture.source, () -> turrets.atlasPicker(), pick(0, 5));
            DialogRequest request = turned.request();
            assertEquals(DialogKind.CHOICE, request.kind());
            assertEquals("Turret facing", request.title());
            assertEquals("", request.message());
            assertEquals(List.of("North", "North-East", "South-East", "South", "South-West", "North-West"),
                  labels(request));
            assertEquals(List.of(true, true, false, false, true, true),
                  request.rows().stream().map(DialogRow::enabled).toList(), "Only the facings the turret can take");
            assertEquals(List.of(1), request.initiallySelected(), "The turret's own facing");
            assertEquals(List.of("Okay", "Cancel"), request.buttons());
            assertEquals(1, request.cancelButton(), "Esc cancels, as the Cancel button does");
            assertFalse(turned.result(), "The picker is gone once answered");
            verify(turrets.client).sendMountFacingChange(1, turret, 4);
            assertEquals(4, turrets.turret.getFacing());

            assertFalse(ask(turrets.fixture.source, () -> turrets.atlasPicker(), pick(1, 3)).result());
            verify(turrets.client).sendMountFacingChange(anyInt(), anyInt(), anyInt());
            // A facing the turret cannot take is never chosen: OK keeps its present one, as the Swing OK does.
            ask(turrets.fixture.source, () -> turrets.atlasPicker(), pick(0, 2));
            verify(turrets.client).sendMountFacingChange(1, turret, 0);
            verify(turrets.client, times(2)).sendMountFacingChange(anyInt(), anyInt(), anyInt());

            List<Integer> handed = new ArrayList<>();
            Asked<Boolean> twisted = ask(turrets.fixture.source, () -> {
                TurretFacingDialog dialog = new TurretFacingDialog(turrets.frame, turrets.bulldog, turrets.gui,
                      handed::add);
                dialog.setVisible(true);
                return dialog.isDisplayable();
            }, pick(0, 2));
            assertEquals(List.of(true, true, true, true, true, true),
                  twisted.request().rows().stream().map(DialogRow::enabled).toList());
            assertEquals(List.of(0), twisted.request().initiallySelected());
            assertEquals(List.of(2), handed);
            ask(turrets.fixture.source, () -> {
                new TurretFacingDialog(turrets.frame, turrets.bulldog, turrets.gui, handed::add).setVisible(true);
                return true;
            }, pick(1, 4));
            assertEquals(List.of(2), handed, "Cancel hands nothing on");
            assertFalse(turrets.anyShowing(), "No Swing picker showed");
        } finally {
            turrets.close();
        }
    }

    /**
     * The board fixture's Atlas (1) facing north-east with a shoulder turret in its left torso, and a Bulldog (60) of
     * the same player facing north; a client of the fixture's game that draws a picker's unit picture, records what it
     * sends and owns its frame. Its native window is registered but not presented until {@link #presentWindow()}.
     */
    static final class Turrets implements AutoCloseable {
        final GpuBoardFixture fixture;
        final ClientGUI gui = previewingClient();
        final Client client = mock(Client.class);
        final JFrame frame;
        final Mek atlas;
        final Mounted<?> turret;
        final Tank bulldog;

        private Turrets(GpuBoardFixture fixture) throws Exception {
            this.fixture = fixture;
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(gui.getClient()).thenReturn(client);
            frame = clientFrame(gui);
            when(gui.getFrame()).thenReturn(frame);
            atlas = (Mek) fixture.entity;
            turret = onSwing(() -> {
                atlas.setFacing(1);
                return atlas.addEquipment(EquipmentType.get("ISShoulderTurret"), Mek.LOC_LEFT_TORSO);
            });
            Tank tank = (Tank) new MekFileParser(new File("testresources/megamek/common/units/Bulldog Medium Tank.blk"))
                  .getEntity();
            bulldog = onSwing(() -> {
                tank.setId(60);
                tank.setOwner(fixture.player);
                tank.setPosition(new Coords(8, 8));
                tank.setFacing(0);
                tank.setSecondaryFacing(0);
                tank.setDeployed(true);
                fixture.game.addEntity(tank, false);
                return tank;
            });
            GpuDialogFixture.set(present(gui, fixture.view, fixture.source), "presented", false);
        }

        static Turrets create() throws Exception {
            GpuBoardFixture fixture = GpuBoardFixture.create();
            try {
                return new Turrets(fixture);
            } catch (Exception | Error failure) {
                fixture.close();
                throw failure;
            }
        }

        /** Registers this client's native window as presented, so it draws the client's dialogs. */
        void presentWindow() throws Exception {
            present(gui, fixture.view, fixture.source);
        }

        /** EDT: shows the picker of the Atlas' turret, as the attack display's rotate button does; still open? */
        boolean atlasPicker() {
            TurretFacingDialog dialog = new TurretFacingDialog(frame, atlas, turret, gui);
            dialog.setVisible(true);
            return dialog.isDisplayable();
        }

        boolean anyShowing() throws Exception {
            return onSwing(() -> List.of(Window.getWindows()).stream()
                  .anyMatch(window -> window instanceof TurretFacingDialog && window.isShowing()));
        }

        @Override
        public void close() throws Exception {
            try {
                dismiss();
                dispose(frame);
            } finally {
                fixture.close();
            }
        }
    }
}
