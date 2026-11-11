/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import megamek.common.board.Board;
import megamek.common.planetaryConditions.PlanetaryConditions;

/** Window-owned visual previews; authoritative scenario conditions are never modified here. */
final class GpuAtmosphereControls {
    /** Shows the conditions editor; the window's native card in the GPU board views. */
    interface Editor {
        /**
         * Swing thread: shows {@code initial} for editing. {@code confirmed} runs on the Swing thread with the
         * confirmed conditions; {@code closed} runs once the editor is gone, after a confirmation or a cancel, on any
         * thread.
         */
        void open(PlanetaryConditions initial, Consumer<PlanetaryConditions> confirmed, Runnable closed);
    }

    private final Supplier<Board> board;
    private final Supplier<PlanetaryConditions> scenario;
    private final BooleanSupplier closed;
    private final double timeSample = ThreadLocalRandom.current().nextDouble();
    private double previewTimeSample = timeSample;
    private PlanetaryConditions previewConditions;
    private boolean editing;

    GpuAtmosphereControls(Supplier<Board> board, Supplier<PlanetaryConditions> scenario, BooleanSupplier closed) {
        this.board = board;
        this.scenario = scenario;
        this.closed = closed;
    }

    /**
     * Edits the preview conditions in {@code editor}; {@code completed} gets their atmosphere on the Swing thread, or
     * null when the edit was cancelled, the window closed or the board changed meanwhile.
     */
    void edit(Editor editor, Consumer<BoardAtmosphere.Settings> completed) {
        SwingUtilities.invokeLater(() -> {
            if (closed.getAsBoolean() || editing) { completed.accept(null); return; }
            editing = true;
            Board initialBoard = board.get();
            var initial = new PlanetaryConditions(previewConditions == null ? scenario.get() : previewConditions);
            BoardAtmosphere.Settings[] result = new BoardAtmosphere.Settings[1];
            Runnable done = () -> onEdt(() -> {
                if (editing) {
                    editing = false;
                    completed.accept(result[0]);
                }
            });
            try {
                editor.open(new PlanetaryConditions(initial), selected -> {
                    if (closed.getAsBoolean() || board.get() != initialBoard || selected == null) { return; }
                    if (selected.getLight() != initial.getLight()) { previewTimeSample = timeSample; }
                    previewConditions = new PlanetaryConditions(selected);
                    result[0] = BoardAtmosphere.fromScenario(previewConditions, initialBoard.isSpace(),
                          previewTimeSample);
                }, done);
            } catch (RuntimeException failure) {
                done.run();
                throw failure;
            }
        });
    }
    BoardAtmosphere.Settings settings(PlanetaryConditions conditions, boolean space) {
        return BoardAtmosphere.fromScenario(conditions, space, timeSample);
    }

    BoardAtmosphere.Settings settings(AtmospherePreset preset) { return preset.settings(timeSample); }

    BoardAtmosphere.Settings preview(AtmospherePreset preset) {
        onEdt(() -> {
            if (!closed.getAsBoolean()) {
                previewConditions = preset.conditions();
                previewTimeSample = preset.timeSample(timeSample);
            }
        });
        return settings(preset);
    }

    void reset() {
        onEdt(() -> { previewConditions = null; previewTimeSample = timeSample; });
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); } else { SwingUtilities.invokeLater(action); }
    }
}
