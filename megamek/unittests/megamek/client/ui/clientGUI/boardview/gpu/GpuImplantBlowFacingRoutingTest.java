/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.answeredInSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.choices;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.dispose;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.initials;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.started;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBombMineDialogRoutingTest.values;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.button;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.components;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.labels;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.next;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.pick;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.dialogs.phaseDisplay.BuildingFacingDialog;
import megamek.client.ui.dialogs.phaseDisplay.CalledBlowDialog;
import megamek.client.ui.dialogs.phaseDisplay.SuicideImplantsDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.tileset.TilesetManager;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Infantry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The suicide implants, called blow and building facing dialogs (swing-inventory table 3, R4) asked in the native
 * battle window: each returns what its Swing dialog returns for the same choices, cancelling included, and shows as
 * before while no native window draws dialogs. The test thread plays the GL thread; the Swing dialogs need a display.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuImplantBlowFacingRoutingTest {
    private static final String[] BLOWS = { "No called blow (full body) - To Hit: 7",
          "Aim high (Punch table, +4) - To Hit: 11", "Aim low (Kick table, +4) - To Hit: 11" };

    @Test
    void swingSuicideImplantsDetonateTheChosenTroopers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            GpuDialogFixture.closingWindow(gui, fixture.view, fixture.source);
            Entity platoon = platoon();
            Entity squad = unit("Elemental BA [Laser] (Sqd5).blk");
            try {
                List<String> shown = new ArrayList<>();
                assertEquals(List.of(true, 10), onSwing(() -> implants(new SuicideImplantsDialog(frame, platoon),
                      dialog -> {
                          JSlider troopers = components(dialog, JSlider.class).getFirst();
                          shown.add(troopers.getMinimum() + ".." + troopers.getMaximum() + " at "
                                + troopers.getValue());
                          shown.add(texts(dialog).toString());
                          troopers.setValue(10);
                          shown.add(texts(dialog).toString());
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of("1..28 at 28",
                      "[WARNING: This action cannot be undone!, Select number of troopers to detonate:, 1, 28,  = , "
                            + "28, Damage to all units in hex: 16]",
                      "[WARNING: This action cannot be undone!, Select number of troopers to detonate:, 1, 28,  = , "
                            + "10, Damage to all units in hex: 6]"), shown);
                assertEquals(List.of(false, 10), onSwing(() -> implants(new SuicideImplantsDialog(frame, platoon),
                      dialog -> {
                          components(dialog, JSlider.class).getFirst().setValue(10);
                          button(dialog, "Cancel").doClick(0);
                      })), "Cancel detonates nothing");
                assertEquals(List.of(false, 28), onSwing(() -> implants(new SuicideImplantsDialog(frame, platoon),
                      GpuBombMineDialogRoutingTest::close)), "The close box detonates nothing");

                List<String> battleArmor = new ArrayList<>();
                assertEquals(List.of(true, 3), onSwing(() -> implants(new SuicideImplantsDialog(frame, squad),
                      dialog -> {
                          components(dialog, JSlider.class).getFirst().setValue(3);
                          battleArmor.add(texts(dialog).toString());
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of("[WARNING: This action cannot be undone!, Select number of troopers to "
                      + "detonate:, 1, 5,  = , 3, Selected troopers will be destroyed.]"), battleArmor);

                List<String> mek = new ArrayList<>();
                assertEquals(List.of(true, 1), onSwing(() -> implants(new SuicideImplantsDialog(frame,
                      fixture.entity), dialog -> {
                          mek.add(texts(dialog).toString());
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of("[WARNING: This action cannot be undone!, <html><center>Detonating will "
                      + "destroy the cockpit and kill the pilot.</center></html>]"), mek);
                assertEquals(List.of(false, 1), onSwing(() -> implants(new SuicideImplantsDialog(frame,
                      fixture.entity), dialog -> button(dialog, "Cancel").doClick(0))));
                assertNull(fixture.source.dialog(), "No native dialog while the window is not presented");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    @Test
    void swingCalledBlowAndBuildingFacingReturnTheChosenRow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        ClientGUI gui = previewingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            GpuDialogFixture.closingWindow(gui, fixture.view, fixture.source);
            try {
                List<String> blows = new ArrayList<>();
                assertEquals(List.of(DialogResult.CONFIRMED, 2), onSwing(() -> blow(
                      new CalledBlowDialog(frame, "Hatchet", BLOWS), dialog -> {
                          blows.add(dialog.getTitle());
                          blows.add(texts(dialog).toString());
                          blows.add(selected(dialog).toString());
                          radios(dialog).get(2).doClick(0);
                          button(dialog, "Ok").doClick(0);
                      })));
                assertEquals(List.of("Called blow with Hatchet?", "[Aim the blow from the Hatchet:]", "[0]"), blows);
                assertEquals(List.of(DialogResult.CONFIRMED, 0), onSwing(() -> blow(
                      new CalledBlowDialog(frame, "Hatchet", BLOWS), dialog -> button(dialog, "Ok").doClick(0))),
                      "The full body table is preselected");
                assertEquals(DialogResult.CANCELLED, onSwing(() -> blow(new CalledBlowDialog(frame, "Hatchet", BLOWS),
                      dialog -> {
                          radios(dialog).get(1).doClick(0);
                          button(dialog, "Cancel").doClick(0);
                      })).getFirst(), "Cancel swings no blow");
                assertEquals(DialogResult.CANCELLED, onSwing(() -> blow(new CalledBlowDialog(frame, "Hatchet", BLOWS),
                      GpuBombMineDialogRoutingTest::close)).getFirst(), "The close box swings no blow");

                onSwing(() -> {
                    fixture.entity.setFacing(1);
                    return null;
                });
                List<String> picker = new ArrayList<>();
                assertEquals(List.of(DialogResult.CONFIRMED, 4), onSwing(() -> facing(
                      new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4)), dialog -> {
                          picker.add(dialog.getTitle());
                          picker.add(facings(dialog).stream().map(JRadioButton::isEnabled).toList().toString());
                          picker.add(selectedFacings(dialog).toString());
                          facings(dialog).get(4).doClick(0);
                          button(dialog, "Ok").doClick(0);
                      })));
                assertEquals(List.of("Choose facing to deploy:", "[false, true, true, false, true, false]", "[1]"),
                      picker, "Only the facings that fit can be picked; the building's own is selected");
                assertEquals(List.of(DialogResult.CONFIRMED, 1), onSwing(() -> facing(
                      new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4)),
                      dialog -> button(dialog, "Ok").doClick(0))), "OK keeps the building's facing");
                assertEquals(DialogResult.CANCELLED, onSwing(() -> facing(
                      new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4)), dialog -> {
                          facings(dialog).get(2).doClick(0);
                          button(dialog, "Cancel").doClick(0);
                      })).getFirst(), "Cancel turns nothing");
                assertNull(fixture.source.dialog(), "No native dialog while the window is not presented");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    @Test
    void nativeSuicideImplantsDetonateTheSameTroopers() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            Entity platoon = platoon();
            Entity squad = unit("Elemental BA [Laser] (Sqd5).blk");
            try {
                // The troopers are picked as the slider's values; each pick moves the slider and its damage line
                FutureTask<List<Object>> detonated = started(() -> implants(new SuicideImplantsDialog(frame,
                      platoon)));
                DialogRequest form = GpuDialogFixture.awaitDialog(fixture.source);
                assertEquals(DialogKind.FORM, form.kind());
                assertEquals("Detonate Suicide Implants", form.title());
                assertEquals("WARNING: This action cannot be undone!\nDamage to all units in hex: 16", form.message());
                assertEquals("Select number of troopers to detonate:", form.fields().getFirst().label());
                assertEquals(List.of(IntStream.rangeClosed(1, 28).mapToObj(String::valueOf).toList()),
                      choices(form), "1 to 28 troopers");
                assertEquals(List.of("28"), initials(form));
                assertTrue(form.fields().getFirst().live());
                assertEquals(List.of("Okay", "Cancel"), form.buttons());
                assertEquals(1, form.cancelButton(), "Esc cancels as the Cancel button does");
                fixture.source.answer(form.id(), values(DialogAnswer.CHANGED, "10"));
                DialogRequest moved = next(fixture.source, form, null);
                assertEquals("WARNING: This action cannot be undone!\nDamage to all units in hex: 6", moved.message());
                assertEquals(List.of("10"), initials(moved));
                fixture.source.answer(moved.id(), values(0, "10"));
                assertEquals(List.of(true, 10), detonated.get(20, SECONDS));
                assertEquals(List.of(false, 28), ask(fixture.source, () -> implants(new SuicideImplantsDialog(frame,
                      platoon)), values(1)).result(), "Cancel detonates nothing");

                Asked<List<Object>> armor = ask(fixture.source, () -> implants(new SuicideImplantsDialog(frame,
                      squad)), values(0, "3"));
                assertEquals("WARNING: This action cannot be undone!\nSelected troopers will be destroyed.",
                      armor.request().message());
                assertEquals(List.of(List.of("1", "2", "3", "4", "5")), choices(armor.request()));
                assertEquals(List.of(true, 3), armor.result());

                Asked<List<Object>> mek = ask(fixture.source, () -> implants(new SuicideImplantsDialog(frame,
                      fixture.entity)), values(0));
                assertEquals(DialogKind.MESSAGE, mek.request().kind());
                assertEquals("WARNING: This action cannot be undone!\nDetonating will destroy the cockpit and kill "
                      + "the pilot.", mek.request().message());
                assertEquals(List.of("Okay", "Cancel"), mek.request().buttons());
                assertEquals(0, mek.request().defaultButton(), "Enter detonates, as Okay has the focus in Swing");
                assertEquals(List.of(true, 1), mek.result());
                assertEquals(List.of(false, 1), ask(fixture.source, () -> implants(new SuicideImplantsDialog(frame,
                      fixture.entity)), values(1)).result(), "Cancel detonates nothing");
                assertEquals(List.of(false, 1), ask(fixture.source, () -> implants(new SuicideImplantsDialog(frame,
                      fixture.entity)), values(-1)).result(), "Esc detonates nothing");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    @Test
    void nativeCalledBlowAndBuildingFacingReturnTheSameRow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = previewingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            try {
                Asked<List<Object>> aimed = ask(fixture.source, () -> blow(new CalledBlowDialog(frame, "Hatchet",
                      BLOWS)), pick(0, 2));
                DialogRequest blows = aimed.request();
                assertEquals(DialogKind.CHOICE, blows.kind());
                assertEquals("Called blow with Hatchet?", blows.title());
                assertEquals("Aim the blow from the Hatchet:", blows.message());
                assertEquals(List.of(BLOWS), labels(blows));
                assertEquals(List.of(0), blows.initiallySelected());
                assertEquals(List.of("Ok", "Cancel"), blows.buttons());
                assertEquals(1, blows.cancelButton(), "Esc cancels as the Cancel button does");
                assertEquals(List.of(DialogResult.CONFIRMED, 2), aimed.result());
                assertEquals(List.of(DialogResult.CONFIRMED, 0), ask(fixture.source, () -> blow(
                      new CalledBlowDialog(frame, "Hatchet", BLOWS)), pick(0, 0)).result());
                assertEquals(DialogResult.CANCELLED, ask(fixture.source, () -> blow(
                      new CalledBlowDialog(frame, "Hatchet", BLOWS)), pick(1)).result().getFirst());

                onSwing(() -> {
                    fixture.entity.setFacing(1);
                    return null;
                });
                Asked<List<Object>> turned = ask(fixture.source, () -> facing(new BuildingFacingDialog(frame, gui,
                      fixture.entity, Set.of(1, 2, 4))), pick(0, 4));
                DialogRequest facings = turned.request();
                assertEquals(DialogKind.CHOICE, facings.kind());
                assertEquals("Choose facing to deploy:", facings.title());
                assertEquals("", facings.message());
                assertEquals(List.of("North", "North-East", "South-East", "South", "South-West", "North-West"),
                      labels(facings));
                assertEquals(List.of(false, true, true, false, true, false),
                      facings.rows().stream().map(DialogRow::enabled).toList(), "Only the facings that fit");
                assertEquals(List.of(1), facings.initiallySelected(), "The building's own facing");
                assertEquals(List.of("Ok", "Cancel"), facings.buttons());
                assertEquals(1, facings.cancelButton());
                assertEquals(List.of(DialogResult.CONFIRMED, 4), turned.result());
                assertEquals(List.of(DialogResult.CONFIRMED, 1), ask(fixture.source, () -> facing(
                      new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4))), pick(0, 1)).result(),
                      "OK keeps the building's facing");
                assertEquals(List.of(DialogResult.CONFIRMED, 1), ask(fixture.source, () -> facing(
                      new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4))), pick(0, 3)).result(),
                      "A facing that does not fit is never chosen");
                assertEquals(DialogResult.CANCELLED, ask(fixture.source, () -> facing(
                      new BuildingFacingDialog(frame, gui, fixture.entity, Set.of(1, 2, 4))), pick(1)).result()
                      .getFirst(), "Cancel turns nothing");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    /** A foot platoon of 28 troopers, all of them able to act, as after the server's first damage pass. */
    static Entity platoon() throws Exception {
        Infantry platoon = (Infantry) unit("Foot Platoon (AFFS) (Laser 3067+).blk");
        platoon.applyDamage();
        return platoon;
    }

    private static Entity unit(String file) throws Exception {
        return new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
    }

    /** A routing client that also draws the building's preview picture, as the facing dialog needs it. */
    static ClientGUI previewingClient() {
        ClientGUI gui = routingClient();
        BufferedImage picture = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        doAnswer(invocation -> {
            ((JLabel) invocation.getArgument(0)).setIcon(new ImageIcon(picture));
            return null;
        }).when(gui).loadPreviewImage(any(), any());
        TilesetManager tiles = mock(TilesetManager.class);
        when(tiles.baseFor(any())).thenReturn(picture);
        doReturn(tiles).when(gui).getTilesetManager();
        return gui;
    }

    /** EDT: what the firing display reads from the implants dialog: whether to detonate, and how many troopers. */
    static List<Object> implants(SuicideImplantsDialog dialog) {
        boolean detonate = dialog.showDialog();
        return List.of(detonate, dialog.getTrooperCount());
    }

    private static List<Object> implants(SuicideImplantsDialog dialog, Consumer<SuicideImplantsDialog> player)
          throws Exception {
        return answeredInSwing(dialog, player, () -> implants(dialog));
    }

    /** EDT: what the physical display reads from the called blow dialog: its result and the chosen row. */
    private static List<Object> blow(CalledBlowDialog dialog) {
        DialogResult result = dialog.showDialog();
        return List.of(result, dialog.getSelectedIndex());
    }

    private static List<Object> blow(CalledBlowDialog dialog, Consumer<CalledBlowDialog> player) throws Exception {
        return answeredInSwing(dialog, player, () -> blow(dialog));
    }

    /** EDT: what the deployment display reads from the facing dialog: its result and the chosen facing. */
    static List<Object> facing(BuildingFacingDialog dialog) {
        DialogResult result = dialog.showDialog();
        return List.of(result, dialog.getChosenFacing());
    }

    private static List<Object> facing(BuildingFacingDialog dialog, Consumer<BuildingFacingDialog> player)
          throws Exception {
        return answeredInSwing(dialog, player, () -> facing(dialog));
    }

    /** The texts of the dialog's labels, in layout order. */
    private static List<String> texts(Container dialog) {
        return components(dialog, JLabel.class).stream().map(JLabel::getText).filter(Objects::nonNull).toList();
    }

    private static List<JRadioButton> radios(Container dialog) {
        return components(dialog, JRadioButton.class);
    }

    /** The indexes of the selected radio buttons. */
    private static List<Integer> selected(Container dialog) {
        List<JRadioButton> radios = radios(dialog);
        return radios.stream().filter(JRadioButton::isSelected).map(radios::indexOf).toList();
    }
    /** The six facing radio buttons in facing order (north first), wherever the picker lays them out. */
    private static List<JRadioButton> facings(Container dialog) {
        return radios(dialog).stream()
              .sorted(Comparator.comparingInt(radio -> Integer.parseInt(radio.getActionCommand()))).toList();
    }

    /** The selected facings. */
    private static List<Integer> selectedFacings(Container dialog) {
        return facings(dialog).stream().filter(JRadioButton::isSelected)
              .map(radio -> Integer.parseInt(radio.getActionCommand())).toList();
    }
}
