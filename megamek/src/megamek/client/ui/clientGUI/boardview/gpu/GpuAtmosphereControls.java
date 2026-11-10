/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.common.board.Board;
import megamek.common.planetaryConditions.PlanetaryConditions;

/** Window-owned visual previews; authoritative scenario conditions are never modified here. */
final class GpuAtmosphereControls {
    private final Supplier<Board> board;
    private final Supplier<PlanetaryConditions> scenario;
    private final BooleanSupplier closed;
    private final double timeSample = ThreadLocalRandom.current().nextDouble();
    private double previewTimeSample = timeSample;
    private PlanetaryConditions previewConditions;
    /** EDT: whether the conditions card is open. */
    private boolean editing;

    GpuAtmosphereControls(Supplier<Board> board, Supplier<PlanetaryConditions> scenario, BooleanSupplier closed) {
        this.board = board;
        this.scenario = scenario;
        this.closed = closed;
    }

    /**
     * GPU thread: opens the planetary conditions card in {@code cards} with the previewed conditions. {@code completed}
     * gets, on the Swing thread, the board's settings for the conditions confirmed, or null when the card is cancelled,
     * the board changed meanwhile or no card can open.
     */
    void edit(Supplier<LobbyNativeDialogs> cards, Consumer<BoardAtmosphere.Settings> completed) {
        Application application = Gdx.app;
        SwingUtilities.invokeLater(() -> {
            if (closed.getAsBoolean() || editing) {
                completed.accept(null);
                return;
            }
            editing = true;
            Board initialBoard = board.get();
            var initial = new PlanetaryConditions(previewConditions == null ? scenario.get() : previewConditions);
            AtomicReference<BoardAtmosphere.Settings> result = new AtomicReference<>();
            Runnable finish = () -> SwingUtilities.invokeLater(() -> {
                editing = false;
                completed.accept(result.get());
            });
            application.postRunnable(() -> {
                LobbyNativeDialogs dialogs = cards.get();
                if (dialogs == null) {
                    finish.run();
                    return;
                }
                dialogs.conditions(new PlanetaryConditionsForm(initial), new LobbyConditionsView.Host() {
                    @Override
                    public void send(PlanetaryConditions selected) {
                        if (closed.getAsBoolean() || board.get() != initialBoard) {
                            return;
                        }
                        if (selected.getLight() != initial.getLight()) {
                            previewTimeSample = timeSample;
                        }
                        previewConditions = new PlanetaryConditions(selected);
                        result.set(BoardAtmosphere.fromScenario(previewConditions, initialBoard.isSpace(),
                              previewTimeSample));
                    }

                    @Override
                    public void edt(Runnable work, Runnable then) {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                work.run();
                            } finally {
                                application.postRunnable(then);
                            }
                        });
                    }
                }, finish);
            });
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
