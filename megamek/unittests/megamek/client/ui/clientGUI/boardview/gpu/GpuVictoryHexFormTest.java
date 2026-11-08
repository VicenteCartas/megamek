/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.clientFrame;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.components;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.next;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.routingClient;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogFixture.set;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.gdx.UiKit.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import java.awt.Container;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogField;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.FieldKind;
import megamek.client.ui.panels.phaseDisplay.VictoryHexPropertiesPane;
import megamek.client.ui.panels.phaseDisplay.VictoryHexPropertiesPane.Result;
import megamek.common.Player;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.equipment.ObjectiveScoringScheme;
import megamek.common.equipment.ObjectiveScoringScheme.HoldCounting;
import megamek.common.equipment.ObjectiveScoringScheme.SchemePreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The control point editor of the Victory Setup phase: what its Okay applies to the point, in the Swing pane and in
 * the native form over the GPU battle window. The test thread answers the native form as the render thread would.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuVictoryHexFormTest {
    /** Okay, Remove Flag and Cancel, as the pane numbers its options. */
    private static final int OKAY = 0;
    private static final int REMOVE = 1;

    /**
     * Characterization of the Swing pane: the values set in its controls are what Okay writes to the point, the
     * hidden rows included, and the starting holder counts by team for a teamed player.
     */
    @Test
    void theSwingPaneAppliesItsControlsToThePoint() throws Exception {
        ObjectiveMarker marker = new ObjectiveMarker();
        assertEquals(Result.SAVED, onSwing(() -> swingEdit(null, marker, OKAY, GpuVictoryHexFormTest::defendScript)));
        assertEquals(SchemePreset.DEFEND, marker.getScoringScheme().getPreset());
        assertEquals(7, marker.getScoringScheme().getThreshold());
        assertEquals(HoldCounting.CUMULATIVE, marker.getScoringScheme().getHoldCounting());
        assertEquals(3, marker.getScoringScheme().getRatePerTurn());
        assertTrue(marker.getScoringScheme().retainsControlWhenEmpty());
        assertEquals(2, marker.getControllingTeam(), "Princess is on team 2, so the point is held by her team");
        assertEquals(ObjectiveMarker.NO_CONTROLLER, marker.getControllingPlayerId());
        assertEquals(2, marker.getControlRadius());
        assertEquals(5, marker.getVictoryPointValue());

        // Without retention the starting holder does not count, whatever the greyed list shows.
        ObjectiveMarker unretained = new ObjectiveMarker();
        onSwing(() -> swingEdit(null, unretained, OKAY, pane -> pane.startingControl().setSelectedIndex(1)));
        assertEquals(ObjectiveMarker.NO_CONTROLLER, unretained.getControllingTeam());
        assertEquals(ObjectiveMarker.NO_CONTROLLER, unretained.getControllingPlayerId());
        assertEquals(1, unretained.getVictoryPointValue());

        ObjectiveMarker removed = new ObjectiveMarker();
        assertEquals(Result.REMOVED, onSwing(() -> swingEdit(null, removed, REMOVE,
              pane -> pane.victoryPoints().setValue(9))));
        assertEquals(Result.CANCELLED, onSwing(() -> swingEdit(null, removed, JOptionPane.CLOSED_OPTION,
              pane -> pane.victoryPoints().setValue(9))));
        assertEquals(1, removed.getVictoryPointValue(), "Remove Flag and the close box apply nothing");
    }

    /**
     * Over the battle window the pane is a native form. The player chooses Hold, then cumulative counting, then
     * Defend, types the numbers, ticks the retention, picks Princess and presses Okay: each change asks again with
     * the rows, labels and description it makes, and the point gets what the Swing pane gives it for those answers.
     */
    @Test
    void theNativeFormFollowsItsChangesAndAppliesWhatTheSwingPaneApplies() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            present(gui, fixture.view, fixture.source);
            JFrame frame = clientFrame(gui);
            try {
                ObjectiveMarker marker = point();
                FutureTask<Result> edit = edit(frame, marker);
                DialogRequest standard = next(fixture.source, null, edit);
                assertEquals(DialogKind.FORM, standard.kind());
                assertEquals(text("VictoryHex.title", "Objective 0505"), standard.title());
                assertEquals(List.of(text("Okay"), text("VictoryHex.remove"), text("Cancel")), standard.buttons());
                assertEquals(0, standard.defaultButton(), "Okay is the pane's initial option");
                assertEquals(-1, standard.cancelButton(), "Esc closes it like the pane's close box");
                assertEquals(List.of(text("VictoryHex.scheme"), text("VictoryHex.retainControl"),
                      text("VictoryHex.radius"), text("VictoryHex.victoryPoints")), labels(standard),
                      "Standard has no rows of its own, and no holder without retention");
                DialogField scheme = standard.fields().getFirst();
                assertEquals(FieldKind.CHOICE, scheme.kind());
                assertEquals(Arrays.stream(SchemePreset.values()).map(preset -> label("scheme", preset)).toList(),
                      scheme.choices());
                assertEquals(label("scheme", SchemePreset.STANDARD), scheme.initial());
                assertEquals(List.of(true, true, false, false),
                      standard.fields().stream().map(DialogField::live).toList(),
                      "the scheme and the retention change the rows; the numbers do not");
                assertEquals(List.of(0, ObjectiveMarker.MAX_CONTROL_RADIUS), bounds(standard.fields().get(2)));
                assertEquals(List.of(1, 99), bounds(standard.fields().get(3)));
                assertEquals(text("VictoryHex.describe.standard"), standard.message());

                DialogRequest hold = change(fixture.source, standard, edit, label("scheme", SchemePreset.HOLD),
                      "false", "0", "1");
                assertEquals(List.of(text("VictoryHex.scheme"), text("VictoryHex.turnsToSecure"),
                      text("VictoryHex.counting"), text("VictoryHex.retainControl"), text("VictoryHex.radius"),
                      text("VictoryHex.victoryPoints")), labels(hold));
                assertEquals(text("VictoryHex.describe.hold.consecutive", 10), hold.message());
                assertEquals(Arrays.stream(HoldCounting.values()).map(counting -> label("counting", counting))
                      .toList(), hold.fields().get(2).choices());

                DialogRequest cumulative = change(fixture.source, hold, edit, label("scheme", SchemePreset.HOLD),
                      "10", label("counting", HoldCounting.CUMULATIVE), "false", "0", "1");
                assertEquals(text("VictoryHex.describe.hold.cumulative", 10), cumulative.message());

                DialogRequest defend = change(fixture.source, cumulative, edit,
                      label("scheme", SchemePreset.DEFEND), "10", label("counting", HoldCounting.CUMULATIVE),
                      "false", "0", "1");
                assertEquals(List.of(text("VictoryHex.scheme"), text("VictoryHex.startingGrip"),
                      text("VictoryHex.gripDrainPerTurn"), text("VictoryHex.retainControl"),
                      text("VictoryHex.radius"), text("VictoryHex.victoryPoints")), labels(defend));

                // The numbers were typed before the retention was ticked; the next form keeps them.
                DialogRequest retained = change(fixture.source, defend, edit, label("scheme", SchemePreset.DEFEND),
                      "7", "3", "true", "2", "5");
                assertEquals(List.of(text("VictoryHex.scheme"), text("VictoryHex.startingGrip"),
                      text("VictoryHex.gripDrainPerTurn"), text("VictoryHex.retainControl"),
                      text("VictoryHex.startingControl"), text("VictoryHex.radius"),
                      text("VictoryHex.victoryPoints")), labels(retained));
                assertEquals(List.of(label("scheme", SchemePreset.DEFEND), "7", "3", "true",
                      text("VictoryHex.startingControl.nobody"), "2", "5"),
                      retained.fields().stream().map(DialogField::initial).toList());
                assertEquals(List.of(text("VictoryHex.startingControl.nobody"), "GPU review",
                      text("VictoryHex.startingControl.teamed", "Princess", 2)),
                      retained.fields().get(4).choices());
                assertEquals(text("VictoryHex.describe.defend", 7, 3) + " "
                      + text("VictoryHex.describe.retained"), retained.message());

                fixture.source.answer(retained.id(), new DialogAnswer(OKAY, List.of(), null, false,
                      List.of(label("scheme", SchemePreset.DEFEND), "7", "3", "true",
                            text("VictoryHex.startingControl.teamed", "Princess", 2), "2", "5")));
                assertEquals(Result.SAVED, edit.get(20, SECONDS));

                ObjectiveMarker swing = point();
                assertEquals(Result.SAVED, onSwing(() -> swingEdit(null, swing, OKAY,
                      GpuVictoryHexFormTest::defendScript)));
                assertEquals(Properties.of(swing), Properties.of(marker));
            } finally {
                dismiss();
            }
        }
    }

    @Test
    void removeAndEscapeApplyNothingAndTheSwingPaneStaysWithoutTheNativeWindow() throws Exception {
        ClientGUI gui = routingClient();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardWindow window = present(gui, fixture.view, fixture.source);
            JFrame frame = clientFrame(gui);
            try {
                ObjectiveMarker marker = point();
                Properties before = Properties.of(marker);
                FutureTask<Result> removed = edit(frame, marker);
                DialogRequest form = next(fixture.source, null, removed);
                fixture.source.answer(form.id(), new DialogAnswer(REMOVE, List.of(), null, false, List.of()));
                assertEquals(Result.REMOVED, removed.get(20, SECONDS));
                FutureTask<Result> closed = edit(frame, marker);
                form = next(fixture.source, form, closed);
                fixture.source.answer(form.id(), DialogAnswer.cancelled(form));
                assertEquals(Result.CANCELLED, closed.get(20, SECONDS));
                assertEquals(before, Properties.of(marker));

                set(window, "presented", false);
                set(window, "closing", true);
                assertEquals(Result.SAVED, onSwing(() -> swingEdit(frame, marker, OKAY,
                      pane -> pane.victoryPoints().setValue(4))));
                assertNull(fixture.source.dialog(), "no native form without a presented window");
                assertEquals(4, marker.getVictoryPointValue());
            } finally {
                dismiss();
            }
        }
    }

    /** A new point as the Victory Setup display places it. */
    private static ObjectiveMarker point() {
        ObjectiveMarker marker = new ObjectiveMarker();
        marker.setName("Objective 0505");
        return marker;
    }

    /** The Swing pane's answers of the native flow: Defend 7/3, cumulative counting, Princess retained, 2 and 5. */
    private static void defendScript(SwingPane pane) {
        pane.scheme().setSelectedItem(SchemePreset.DEFEND);
        pane.threshold().setValue(7);
        // The Hold counting row is hidden for Defend, but its value is applied all the same.
        pane.counting().setSelectedItem(HoldCounting.CUMULATIVE);
        pane.rate().setValue(3);
        pane.retain().setSelected(true);
        pane.startingControl().setSelectedIndex(2);
        pane.radius().setValue(2);
        pane.victoryPoints().setValue(5);
    }

    /** What the pane writes to a point. */
    private record Properties(SchemePreset preset, int threshold, HoldCounting counting, int rate, boolean retains,
          int team, int player, int radius, int victoryPoints) {
        static Properties of(ObjectiveMarker marker) {
            ObjectiveScoringScheme scheme = marker.getScoringScheme();
            return new Properties(scheme.getPreset(), scheme.getThreshold(), scheme.getHoldCounting(),
                  scheme.getRatePerTurn(), scheme.retainsControlWhenEmpty(), marker.getControllingTeam(),
                  marker.getControllingPlayerId(), marker.getControlRadius(), marker.getVictoryPointValue());
        }
    }

    /** The players the starting holder is chosen from: an unteamed one and a teamed one. */
    static List<Player> players() {
        Player lone = new Player(0, "GPU review");
        lone.setTeam(Player.TEAM_NONE);
        Player princess = new Player(3, "Princess");
        princess.setTeam(2);
        return List.of(lone, princess);
    }

    /** Opens the editor on the EDT, as the Victory Setup display does on a hex click. */
    private static FutureTask<Result> edit(JFrame frame, ObjectiveMarker marker) {
        FutureTask<Result> edit = new FutureTask<>(() -> VictoryHexPropertiesPane.edit(frame, marker, players(),
              false));
        SwingUtilities.invokeLater(edit);
        return edit;
    }

    /** Answers a form as a changed live field does, with the form's values; returns the form asked next. */
    private static DialogRequest change(GpuBoardSource source, DialogRequest form, FutureTask<Result> edit,
          String... values) throws Exception {
        source.answer(form.id(), new DialogAnswer(DialogAnswer.CHANGED, List.of(), null, false, List.of(values)));
        return next(source, form, edit);
    }

    private static List<String> labels(DialogRequest form) {
        return form.fields().stream().map(DialogField::label).toList();
    }

    private static List<Integer> bounds(DialogField field) {
        return List.of(field.min(), field.max());
    }

    /** An enum value's label in the pane's lists ("VictoryHex.scheme.hold" and the like). */
    private static String label(String list, Enum<?> value) {
        return text("VictoryHex." + list + "." + value.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The Swing pane's controls in its rows' order: the scheme and counting lists first and the starting holder's
     * list (nobody, then the players) last.
     */
    record SwingPane(JComboBox<?> scheme, JSpinner threshold, JComboBox<?> counting, JSpinner rate,
          JCheckBox retain, JComboBox<?> startingControl, JSpinner radius, JSpinner victoryPoints) {
        static SwingPane of(Container editor) {
            var combos = components(editor, JComboBox.class);
            List<JSpinner> spinners = components(editor, JSpinner.class);
            List<JCheckBox> boxes = components(editor, JCheckBox.class);
            return new SwingPane(combos.get(0), spinners.get(0), combos.get(1), spinners.get(1), boxes.getFirst(),
                  combos.getLast(), spinners.get(2), spinners.get(3));
        }
    }

    /**
     * EDT: the Swing pane over {@code frame}, answered by a stubbed JOptionPane that first lets {@code script} set its
     * controls and then presses option {@code option}.
     */
    static Result swingEdit(JFrame frame, ObjectiveMarker marker, int option, Consumer<SwingPane> script) {
        try (MockedStatic<JOptionPane> swing = mockStatic(JOptionPane.class)) {
            swing.when(() -> JOptionPane.showOptionDialog(any(), any(), any(), anyInt(), anyInt(), any(), any(),
                  any())).thenAnswer(call -> {
                      script.accept(SwingPane.of(call.getArgument(1)));
                      return option;
                  });
            return VictoryHexPropertiesPane.edit(frame, marker, players(), false);
        }
    }
}
