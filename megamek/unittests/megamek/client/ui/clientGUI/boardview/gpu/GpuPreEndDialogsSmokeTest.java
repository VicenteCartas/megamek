/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.next;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.ticked;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPreEndDialogsTest.Declaring;
import megamek.client.ui.dialogs.phaseDisplay.AutomaticEjectionDialog;
import megamek.client.ui.dialogs.phaseDisplay.InfantryActionDeclarationDialog;
import megamek.client.ui.dialogs.phaseDisplay.NovaNetworkDialog;
import megamek.client.ui.dialogs.phaseDisplay.TriggerAPPodDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.common.equipment.EquipmentType;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The native forms of the pre-end declaration and pod dialogs (N7b) as the HUD's modal draws them at 1280 x 720: each
 * request comes from its real dialog through the bridge, lies inside the window with no child wider than the dialog
 * (the button rows included), and is written to a PNG.
 */
@Tag("on-demand")
class GpuPreEndDialogsSmokeTest {
    private static final long WAIT_SECONDS = 20;

    @Test
    void theNativeFormsFitTheWindow() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForAutoEject();
        try (Declaring declaring = Declaring.create()) {
            declaring.novaUnits();
            Entity archer = declaring.fixture.game.getEntity(42);
            AbstractBuildingEntity building = declaring.building(declaring.fixture.player);
            declaring.platoon(60, declaring.fixture.player, building.getPosition());
            declaring.platoon(70, declaring.enemy, building.getPosition());
            onSwing(() -> {
                archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_LEFT_LEG);
                archer.addEquipment(EquipmentType.get("ISAntiPersonnelPod"), Mek.LOC_RIGHT_LEG).setFired(true);
                ((Mek) declaring.fixture.entity).setAutoEject(true);
                preferences.setNagForAutoEject(true);
                return null;
            });
            declaring.present();
            GpuBoardSource source = declaring.fixture.source;
            GpuHudTestStage.run(harness -> {
                harness.size(1280, 720);
                GpuModalDialog modal = new GpuModalDialog(harness.kit, source,
                      new GpuHudState(new GpuPlaybackHistory(new UnitPlayback())));
                Actor root = modal.actor();
                root.setTouchable(Touchable.enabled);
                harness.window.addActor(root);
                root.setBounds(0, 0, harness.width(), harness.height());

                FutureTask<Boolean> nova = onEdt(() -> {
                    NovaNetworkDialog dialog = new NovaNetworkDialog(declaring.frame, declaring.gui);
                    dialog.setVisible(true);
                    return dialog.wasApplied();
                });
                DialogRequest overview = draw(harness, modal, source, null, "n7b-nova-overview");
                assertEquals(DialogKind.MESSAGE, overview.kind());
                source.answer(overview.id(), ticked(0));
                DialogRequest picker = draw(harness, modal, source, overview, "n7b-nova-picker");
                assertEquals(DialogKind.MULTI, picker.kind());
                source.answer(picker.id(), ticked(1));
                DialogRequest back = next(source, picker, null);
                source.answer(back.id(), ticked(-1));
                assertEquals(false, nova.get(WAIT_SECONDS, SECONDS));

                FutureTask<DialogResult> defence = onEdt(() -> new InfantryActionDeclarationDialog(declaring.frame,
                      declaring.fixture.game, declaring.fixture.player, building).showDialog());
                DialogRequest form = draw(harness, modal, source, back, "n7b-infantry-defence");
                assertEquals(DialogKind.FORM, form.kind());
                source.answer(form.id(), ticked(1));
                assertEquals(DialogResult.CANCELLED, defence.get(WAIT_SECONDS, SECONDS));

                FutureTask<Boolean> ejection = onEdt(() -> {
                    AutomaticEjectionDialog dialog = new AutomaticEjectionDialog(declaring.frame, declaring.gui,
                          List.of(declaring.fixture.entity, archer), "vacuum");
                    dialog.setVisible(true);
                    return dialog.isDeploymentCancelled();
                });
                DialogRequest eject = draw(harness, modal, source, form, "n7b-ejection");
                source.answer(eject.id(), new DialogAnswer(-1, List.of(), null, false, List.of()));
                assertEquals(true, ejection.get(WAIT_SECONDS, SECONDS));

                FutureTask<Boolean> pods = onEdt(() -> {
                    TriggerAPPodDialog dialog = new TriggerAPPodDialog(declaring.frame, archer);
                    dialog.setVisible(true);
                    return dialog.getActions().hasMoreElements();
                });
                DialogRequest podRequest = draw(harness, modal, source, eject, "n7b-ap-pods");
                source.answer(podRequest.id(), ticked(0));
                assertEquals(false, pods.get(WAIT_SECONDS, SECONDS));
            });
        } finally {
            preferences.setNagForAutoEject(nag);
        }
    }

    /**
     * GL thread: waits for the request after {@code answered} (the first for null), draws it twice (the second frame
     * has the layout), checks that the dialog fits the window, writes {name}.png and returns the request.
     */
    private static DialogRequest draw(GpuHudTestStage harness, GpuModalDialog modal, GpuBoardSource source,
          DialogRequest answered, String name) throws Exception {
        DialogRequest request = next(source, answered, null);
        for (int frame = 0; frame < 2; frame++) {
            modal.update(new GpuHud.Inputs(null, GpuHud.HudView.EMPTY, request, GpuHudFixtures.preferences(),
                  GpuHud.Metrics.of(harness.width(), harness.height()), List.of()));
            harness.draw();
        }
        Actor dialog = ((Group) modal.actor()).getChildren().first();
        harness.assertLayout(dialog);
        harness.capture(name).dispose();
        return request;
    }

    private static <T> FutureTask<T> onEdt(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task;
    }
}
