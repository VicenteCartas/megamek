/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.labels;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.pick;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.button;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.components;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.next;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static megamek.common.equipment.enums.BombType.BombTypeEnum.CLUSTER;
import static megamek.common.equipment.enums.BombType.BombTypeEnum.HE;
import static megamek.common.equipment.enums.BombType.BombTypeEnum.LG;
import static megamek.common.equipment.enums.BombType.BombTypeEnum.RL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.Asked;
import megamek.client.ui.dialogs.phaseDisplay.BombPayloadDialog;
import megamek.client.ui.dialogs.phaseDisplay.MineDensityDialog;
import megamek.client.ui.dialogs.phaseDisplay.MineLayingDialog;
import megamek.client.ui.dialogs.phaseDisplay.SeaMineDepthDialog;
import megamek.common.equipment.BombLoadout;
import megamek.common.equipment.EquipmentType;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The bomb payload and mine dialogs (swing-inventory table 3, R1) asked in the native battle window: each returns what
 * its Swing dialog returns for the same choices, cancelling included, and shows as before while no native window draws
 * dialogs. The test thread plays the GL thread; the Swing dialogs need a display.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuBombMineDialogRoutingTest {

    @Test
    void swingBombPayloadDropsTheChosenBombsWithinItsLimit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            GpuDialogFixture.closingWindow(gui, fixture.view, fixture.source);
            try {
                // A fighter bombing the ground drops at most two bombs; rockets cannot be dropped on the ground
                List<List<String>> offered = new ArrayList<>();
                List<List<String>> narrowed = new ArrayList<>();
                assertEquals(Arrays.asList(true, Map.of(HE, 1, CLUSTER, 1)), onSwing(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, 2, 0),
                      dialog -> {
                          List<JComboBox<?>> counts = combos(dialog);
                          counts.forEach(count -> offered.add(items(count)));
                          counts.get(0).setSelectedItem("1");
                          counts.get(1).setSelectedItem("1");
                          counts.forEach(count -> narrowed.add(items(count)));
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of(List.of("0", "1", "2"), List.of("0", "1", "2"), List.of("0", "1")), offered,
                      "HE, cluster and laser-guided bombs, each up to the limit");
                assertEquals(List.of(List.of("0", "1"), List.of("0", "1"), List.of("0")), narrowed,
                      "The choices left never exceed the limit");

                // A squadron of two drops whole salvos; each entry names the bombs in parentheses
                List<List<String>> salvos = new ArrayList<>();
                assertEquals(Arrays.asList(true, Map.of(HE, 5)), onSwing(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, external", squadronLoadout(), false, false, -1, 2),
                      dialog -> {
                          salvos.add(items(combos(dialog).getFirst()));
                          combos(dialog).getFirst().setSelectedItem("3 (5)");
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of(List.of("0", "1 (2)", "2 (4)", "3 (5)")), salvos);
                // Its first entries ignore a limit, so a salvo beyond it falls back to none once picked
                List<String> reset = new ArrayList<>();
                assertEquals(Arrays.asList(false, null), onSwing(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", squadronLoadout(), false, false, 2, 2),
                      dialog -> {
                          JComboBox<?> salvo = combos(dialog).getFirst();
                          salvo.setSelectedItem("2 (4)");
                          reset.add(items(salvo) + " at " + salvo.getSelectedItem());
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of("[0, 1 (2)] at 0"), reset);

                assertEquals(Arrays.asList(false, null), onSwing(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, 2, 0),
                      dialog -> {
                          combos(dialog).getFirst().setSelectedItem("2");
                          button(dialog, "Cancel").doClick(0);
                      })), "Cancel drops nothing");
                assertEquals(Arrays.asList(false, null), onSwing(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, 2, 0),
                      dialog -> {
                          combos(dialog).getFirst().setSelectedItem("2");
                          close(dialog);
                      })), "The close box drops nothing");
                assertEquals(Arrays.asList(false, null), onSwing(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, 2, 0),
                      dialog -> button(dialog, "Okay").doClick(0))), "No bomb chosen is no answer");
                assertNull(fixture.source.dialog(), "No native dialog while the window is not presented");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    @Test
    void swingMineDialogsReturnTheChosenMineDepthAndDensity() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            GpuDialogFixture.closingWindow(gui, fixture.view, fixture.source);
            Entity tank = minelayer();
            try {
                List<List<String>> mines = new ArrayList<>();
                assertEquals(List.of(true, 8), onSwing(() -> mine(new MineLayingDialog(frame, tank),
                      dialog -> {
                          mines.add(items(combos(dialog).getFirst()));
                          combos(dialog).getFirst().setSelectedIndex(1);
                          button(dialog, "Okay").doClick(0);
                      })));
                assertEquals(List.of(List.of("Front Conventional Mine",
                      "Rear Conventional Mine")), mines);
                assertEquals(false, onSwing(() -> mine(new MineLayingDialog(frame, tank),
                      dialog -> button(dialog, "Cancel").doClick(0))).getFirst(), "Cancel lays no mine");
                assertEquals(List.of(true, 7), onSwing(() -> mine(new MineLayingDialog(frame, tank),
                      GpuBombMineDialogRoutingTest::close)), "Closing the window lays the mine shown");

                List<List<String>> depths = new ArrayList<>();
                assertEquals(2, onSwing(() -> depth(new SeaMineDepthDialog(frame, 3), dialog -> {
                    depths.add(items(combos(dialog).getFirst()));
                    combos(dialog).getFirst().setSelectedItem("2");
                    button(dialog, "Okay").doClick(0);
                })));
                assertEquals(List.of(List.of("0", "1", "2", "3")), depths);
                assertEquals(-1, onSwing(() -> depth(new SeaMineDepthDialog(frame, 3),
                      GpuBombMineDialogRoutingTest::close)), "Closing the window chooses no depth");

                List<List<String>> densities = new ArrayList<>();
                assertEquals(20, onSwing(() -> density(new MineDensityDialog(frame), dialog -> {
                    densities.add(items(combos(dialog).getFirst()));
                    combos(dialog).getFirst().setSelectedItem("20");
                    button(dialog, "Okay").doClick(0);
                })));
                assertEquals(List.of(List.of("30", "25", "20", "15", "10", "5")), densities);
                assertEquals(30, onSwing(() -> density(new MineDensityDialog(frame),
                      dialog -> button(dialog, "Okay").doClick(0))), "The densest is preselected");
                assertEquals(-1, onSwing(() -> density(new MineDensityDialog(frame),
                      GpuBombMineDialogRoutingTest::close)), "Closing the window chooses no density");
                assertNull(fixture.source.dialog(), "No native dialog while the window is not presented");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    @Test
    void nativeBombPayloadNarrowsAsTheSwingDialogAndDropsTheSameBombs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            try {
                // The Swing test's fighter: each pick comes back as the Swing dialog's narrowed entries
                FutureTask<List<Object>> fighter = started(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, 2, 0)));
                DialogRequest form = GpuDialogFixture.awaitDialog(fixture.source);
                assertEquals(DialogKind.FORM, form.kind());
                assertEquals("Bomb Payload, internal", form.title());
                assertEquals("Select the number and type of bombs you wish to drop.", form.message());
                assertEquals(List.of("HE Bomb", "Cluster Bomb", "Laser-guided Bomb"),
                      form.fields().stream().map(DialogField::label).toList());
                assertEquals(List.of(List.of("0", "1", "2"), List.of("0", "1", "2"), List.of("0", "1")),
                      choices(form));
                assertEquals(List.of("0", "0", "0"), initials(form));
                assertTrue(form.fields().stream().allMatch(DialogField::live), "Under a limit each pick narrows");
                assertEquals(List.of("Okay", "Cancel"), form.buttons());
                assertEquals(0, form.defaultButton());
                assertEquals(1, form.cancelButton(), "Esc cancels as the Cancel button does");
                fixture.source.answer(form.id(), values(DialogAnswer.CHANGED, "1", "0", "0"));
                DialogRequest narrowed = next(fixture.source, form, null);
                assertEquals(List.of(List.of("0", "1", "2"), List.of("0", "1"), List.of("0", "1")), choices(narrowed));
                assertEquals(List.of("1", "0", "0"), initials(narrowed));
                fixture.source.answer(narrowed.id(), values(DialogAnswer.CHANGED, "1", "1", "0"));
                DialogRequest full = next(fixture.source, narrowed, null);
                assertEquals(List.of(List.of("0", "1"), List.of("0", "1"), List.of("0")), choices(full),
                      "The choices left never exceed the limit");
                fixture.source.answer(full.id(), values(0, "1", "1", "0"));
                assertEquals(Arrays.asList(true, Map.of(HE, 1, CLUSTER, 1)), fighter.get(20, SECONDS));

                Asked<List<Object>> salvo = ask(fixture.source, () -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, external", squadronLoadout(), false, false, -1, 2)),
                      values(0, "3 (5)"));
                assertEquals("Select the number of salvos for each bomb type you wish to drop.\n The number in "
                      + "parentheses represents the number of bombs that will be dropped.", salvo.request().message());
                assertEquals(List.of(List.of("0", "1 (2)", "2 (4)", "3 (5)")), choices(salvo.request()));
                assertFalse(salvo.request().fields().getFirst().live(), "Without a limit nothing narrows");
                assertEquals(Arrays.asList(true, Map.of(HE, 5)), salvo.result());

                // A salvo beyond the limit falls back to none, as in the Swing dialog
                FutureTask<List<Object>> limited = started(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", squadronLoadout(), false, false, 2, 2)));
                DialogRequest salvos = GpuDialogFixture.awaitDialog(fixture.source);
                fixture.source.answer(salvos.id(), values(DialogAnswer.CHANGED, "2 (4)"));
                DialogRequest undone = next(fixture.source, salvos, null);
                assertEquals(List.of(List.of("0", "1 (2)")), choices(undone));
                assertEquals(List.of("0"), initials(undone));
                fixture.source.answer(undone.id(), values(0, "0"));
                assertEquals(Arrays.asList(false, null), limited.get(20, SECONDS), "No bomb chosen is no answer");

                FutureTask<List<Object>> cancelled = started(() -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, 2, 0)));
                DialogRequest picked = GpuDialogFixture.awaitDialog(fixture.source);
                fixture.source.answer(picked.id(), values(DialogAnswer.CHANGED, "2", "0", "0"));
                fixture.source.answer(next(fixture.source, picked, null).id(), values(1));
                assertEquals(Arrays.asList(false, null), cancelled.get(20, SECONDS), "Cancel drops nothing");
                assertEquals(Arrays.asList(false, null), ask(fixture.source, () -> payload(
                      new BombPayloadDialog(frame, "Bomb Payload, internal", loadout(), false, false, -1, 0)),
                      values(0, "1")).result(), "An answer that does not fit the form is refused, not dropped");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    @Test
    void nativeMineDialogsReturnTheSwingMineDepthAndDensity() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Builds Swing dialogs");
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            JFrame frame = clientFrame(gui);
            present(gui, fixture.view, fixture.source);
            Entity tank = minelayer();
            try {
                Asked<List<Object>> laid = ask(fixture.source, () -> mine(new MineLayingDialog(frame, tank)),
                      pick(0, 1));
                DialogRequest mines = laid.request();
                assertEquals(DialogKind.CHOICE, mines.kind());
                assertEquals("Mine Selection", mines.title());
                assertEquals("Select which mine should be laid:", mines.message());
                assertEquals(List.of("Front Conventional Mine", "Rear Conventional Mine"), labels(mines));
                assertEquals(List.of(0), mines.initiallySelected());
                assertEquals(List.of("Okay", "Cancel"), mines.buttons());
                assertEquals(1, mines.cancelButton(), "Esc cancels as the Cancel button does");
                assertEquals(List.of(true, 8), laid.result());
                assertEquals(List.of(false), ask(fixture.source, () -> mine(new MineLayingDialog(frame, tank)),
                      pick(1)).result(), "Cancel lays no mine");

                Asked<Integer> depth = ask(fixture.source, () -> depth(new SeaMineDepthDialog(frame, 3)), pick(0, 2));
                assertEquals(DialogKind.CHOICE, depth.request().kind());
                assertEquals("Mine Density", depth.request().title(), "The Swing dialog's own title");
                assertEquals("Depth", depth.request().message());
                assertEquals(List.of("0", "1", "2", "3"), labels(depth.request()));
                assertEquals(List.of(0), depth.request().initiallySelected());
                assertEquals(List.of("Okay"), depth.request().buttons());
                assertEquals(-1, depth.request().cancelButton(), "Esc closes it like the window's close box");
                assertEquals(2, depth.result());
                assertEquals(-1, ask(fixture.source, () -> depth(new SeaMineDepthDialog(frame, 3)), pick(-1))
                      .result(), "Esc chooses no depth");

                Asked<Integer> density = ask(fixture.source, () -> density(new MineDensityDialog(frame)),
                      pick(0, 2));
                assertEquals("Mine Density", density.request().title());
                assertEquals("Density", density.request().message());
                assertEquals(List.of("30", "25", "20", "15", "10", "5"), labels(density.request()));
                assertEquals(List.of(0), density.request().initiallySelected());
                assertEquals(List.of("Okay"), density.request().buttons());
                assertEquals(-1, density.request().cancelButton());
                assertEquals(20, density.result());
                assertEquals(30, ask(fixture.source, () -> density(new MineDensityDialog(frame)), pick(0, 0))
                      .result(), "The densest is preselected");
                assertEquals(-1, ask(fixture.source, () -> density(new MineDensityDialog(frame)), pick(-1))
                      .result(), "Esc chooses no density");
            } finally {
                dismiss();
                dispose(frame);
            }
        }
    }

    /** HE, cluster, laser-guided bombs and rockets (which only a dump releases) on one fighter. */
    static BombLoadout loadout() {
        BombLoadout bombs = new BombLoadout();
        bombs.put(HE, 4);
        bombs.put(CLUSTER, 2);
        bombs.put(LG, 1);
        bombs.put(RL, 3);
        return bombs;
    }

    private static BombLoadout squadronLoadout() {
        BombLoadout bombs = new BombLoadout();
        bombs.put(HE, 5);
        return bombs;
    }

    /** A Bulldog tank with two vehicular mine dispensers, front and rear. */
    static Entity minelayer() throws Exception {
        Entity tank = new MekFileParser(new File("testresources/megamek/common/units/Bulldog Medium Tank.blk"))
              .getEntity();
        tank.addEquipment(EquipmentType.get("ISVehicularMineDispenser"), Tank.LOC_FRONT);
        tank.addEquipment(EquipmentType.get("ISVehicularMineDispenser"), Tank.LOC_REAR);
        return tank;
    }

    /** EDT: what the firing display reads from a bomb dialog once shown: its answer and its choices. */
    static List<Object> payload(BombPayloadDialog dialog) {
        dialog.setVisible(true);
        return Arrays.asList(dialog.getAnswer(), dialog.getChoices());
    }

    private static List<Object> payload(BombPayloadDialog dialog, Consumer<BombPayloadDialog> player)
          throws Exception {
        return answeredInSwing(dialog, player, () -> payload(dialog));
    }

    /** EDT: what the movement display reads from a mine dialog once shown: its answer and the mine's number. */
    static List<Object> mine(MineLayingDialog dialog) {
        dialog.setVisible(true);
        return dialog.getAnswer() ? List.of(true, dialog.getMine()) : List.of(false);
    }

    private static List<Object> mine(MineLayingDialog dialog, Consumer<MineLayingDialog> player) throws Exception {
        return answeredInSwing(dialog, player, () -> mine(dialog));
    }

    private static int depth(SeaMineDepthDialog dialog) {
        dialog.setVisible(true);
        return dialog.getDepth();
    }

    private static int depth(SeaMineDepthDialog dialog, Consumer<SeaMineDepthDialog> player) throws Exception {
        return answeredInSwing(dialog, player, () -> depth(dialog));
    }

    private static int density(MineDensityDialog dialog) {
        dialog.setVisible(true);
        return dialog.getDensity();
    }

    private static int density(MineDensityDialog dialog, Consumer<MineDensityDialog> player) throws Exception {
        return answeredInSwing(dialog, player, () -> density(dialog));
    }

    /** A FORM answer: the button ({@link DialogAnswer#CHANGED} for a live field's change) and the values. */
    static DialogAnswer values(int button, String... values) {
        return new DialogAnswer(button, List.of(), null, false, List.of(values));
    }

    static List<List<String>> choices(DialogRequest form) {
        return form.fields().stream().map(DialogField::choices).toList();
    }

    static List<String> initials(DialogRequest form) {
        return form.fields().stream().map(DialogField::initial).toList();
    }

    /** Runs the chokepoint on the EDT without waiting for it, as a click that opens a dialog does. */
    static <T> FutureTask<T> started(Callable<T> chokepoint) {
        FutureTask<T> task = new FutureTask<>(chokepoint);
        SwingUtilities.invokeLater(task);
        return task;
    }

    /**
     * EDT: shows a modal Swing dialog through {@code show} while a timer lets the player answer it as soon as it shows;
     * returns what {@code show} returns.
     */
    static <D extends Dialog, R> R answeredInSwing(D dialog, Consumer<D> player, Callable<R> show) throws Exception {
        AtomicBoolean answered = new AtomicBoolean();
        Timer timer = new Timer(50, event -> {
            if (dialog.isShowing() && answered.compareAndSet(false, true)) {
                player.accept(dialog);
            }
        });
        timer.start();
        try {
            R result = show.call();
            assertTrue(answered.get(), "The Swing dialog showed");
            return result;
        } finally {
            timer.stop();
        }
    }

    /** The player closes the dialog with the window's close box. */
    static void close(Dialog dialog) {
        dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
    }

    static List<JComboBox<?>> combos(Container dialog) {
        return components(dialog, JComboBox.class).stream().<JComboBox<?>>map(combo -> combo).toList();
    }

    static List<String> items(JComboBox<?> combo) {
        return IntStream.range(0, combo.getItemCount()).mapToObj(index -> String.valueOf(combo.getItemAt(index)))
              .toList();
    }

    static void dispose(JFrame frame) throws Exception {
        onSwing(() -> {
            frame.dispose();
            return null;
        });
    }
}
