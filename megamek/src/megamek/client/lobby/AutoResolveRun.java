/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import jakarta.annotation.Nullable;
import megamek.common.Player;
import megamek.common.autoResolve.Resolver;
import megamek.common.autoResolve.acar.SimulationOptions;
import megamek.common.autoResolve.converter.SetupForces;
import megamek.common.autoResolve.event.AutoResolveConcludedEvent;
import megamek.common.board.Board;
import megamek.common.internationalization.I18n;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.logging.MMLogger;
import megamek.server.victory.VictoryResult;

/**
 * The lobby's auto-resolve without a toolkit: the odds, worked out from many quick simulated battles, the battle
 * resolved once with its report, and the words that tell the local side how it went. The simulations run on the
 * caller's thread; a view shows them.
 */
public final class AutoResolveRun {
    private static final MMLogger LOGGER = MMLogger.create(AutoResolveRun.class);
    /** How many flavor lines the progress has, after the first, which loads the scenario. */
    static final int FLAVOR_LINES = 99;

    /** How a battle ended for the local side. */
    public enum Outcome {
        VICTORY, DEFEAT, DRAW
    }

    /**
     * The odds from {@code runs} simulated battles that ended, of which the local side won {@code victories}, lost
     * {@code losses} and drew {@code draws}; {@code failed} more broke off without a result.
     */
    public record Score(int runs, int victories, int losses, int draws, int failed) {
        /** The chance of victory as a whole percentage of the battles that ended; 0 when none did. */
        public int percent() {
            return runs == 0 ? 0 : victories * 100 / runs;
        }
    }

    private AutoResolveRun() {
    }

    /** How {@code result} ended for team {@code team} and player {@code playerId}. */
    public static Outcome outcome(VictoryResult result, int team, int playerId) {
        if (result.isDraw()) {
            return Outcome.DRAW;
        }
        boolean won = (team != Player.TEAM_NONE && result.getWinningTeam() == team)
              || (result.getWinningPlayer() != Player.PLAYER_NONE && result.getWinningPlayer() == playerId);
        return won ? Outcome.VICTORY : Outcome.DEFEAT;
    }

    /** The outcome's headline: Victory!, Defeat! or Draw. */
    public static String headline(Outcome outcome) {
        return switch (outcome) {
            case VICTORY -> I18n.getText("AutoResolveDialog.victory");
            case DEFEAT -> I18n.getText("AutoResolveDialog.defeat");
            case DRAW -> I18n.getText("AutoResolveDialog.draw");
        };
    }

    /** Who won {@code result}: the winning team, else the winning player as {@code playerName} names them. */
    public static String winner(VictoryResult result, IntFunction<String> playerName) {
        if (result.isDraw()) {
            return I18n.getText("AutoResolveDialog.messageScenarioDraw");
        }
        if (result.getWinningTeam() != Player.TEAM_NONE) {
            return I18n.getFormattedText("AutoResolveDialog.messageScenarioTeam", result.getWinningTeam());
        }
        return I18n.getFormattedText("AutoResolveDialog.messageScenarioPlayer",
              playerName.apply(result.getWinningPlayer()));
    }

    /** The briefing's title. */
    public static String title() {
        return I18n.getText("AutoResolveDialog.title");
    }

    /** The battle report's title. */
    public static String reportTitle() {
        return I18n.getText("AutoResolveSimulationLogDialog.title");
    }

    /** The title of the message that says the battle could not be resolved. */
    public static String failureTitle() {
        return I18n.getText("AutoResolveDialog.messageScenarioError.title");
    }

    /** The message that says the battle could not be resolved. */
    public static String failureText() {
        return I18n.getText("AutoResolveDialog.messageScenarioError.text");
    }

    /** The first progress line, which loads the scenario. */
    public static String loading() {
        return I18n.getText("AutoResolveMethod.progress.0");
    }

    /** The progress's flavor lines in an order {@code random} shuffles. */
    public static List<String> flavor(Random random) {
        List<String> lines = new ArrayList<>(FLAVOR_LINES);
        for (int line = 1; line <= FLAVOR_LINES; line++) {
            lines.add(I18n.getText("AutoResolveMethod.progress." + line));
        }
        Collections.shuffle(lines, random);
        return lines;
    }

    /**
     * Simulates the battle {@code runs} times, {@code threads} at once, on blank boards as large as {@code board},
     * without reports, and scores them for team {@code team} and player {@code playerId}. {@code done} hears how many
     * have ended after each, from the simulating threads; once {@code cancelled} says so, no more start.
     */
    public static Score odds(int runs, int threads, int team, int playerId, SetupForces setup, Board board,
          PlanetaryConditions conditions, IntConsumer done, BooleanSupplier cancelled) {
        AtomicInteger ended = new AtomicInteger();
        List<Future<AutoResolveConcludedEvent>> futures = new ArrayList<>(runs);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), work -> {
            Thread thread = new Thread(work, "Auto-resolve odds");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int run = 0; run < runs; run++) {
                futures.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    try {
                        return Resolver.simulationRunWithoutLog(setup, SimulationOptions.empty(),
                              new Board(board.getWidth(), board.getHeight()), new PlanetaryConditions(conditions))
                              .resolveSimulation();
                    } finally {
                        done.accept(ended.incrementAndGet());
                    }
                }));
            }
            List<VictoryResult> results = new ArrayList<>(runs);
            for (Future<AutoResolveConcludedEvent> future : futures) {
                AutoResolveConcludedEvent event = null;
                try {
                    event = future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    LOGGER.error(failure, "A simulated battle failed");
                }
                results.add(event == null ? null : event.getVictoryResult());
            }
            return score(results, team, playerId);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Scores {@code results} for team {@code team} and player {@code playerId}; a null result failed. */
    static Score score(List<VictoryResult> results, int team, int playerId) {
        int victories = 0;
        int losses = 0;
        int draws = 0;
        int failed = 0;
        for (VictoryResult result : results) {
            if (result == null) {
                failed++;
                continue;
            }
            switch (outcome(result, team, playerId)) {
                case VICTORY -> victories++;
                case DEFEAT -> losses++;
                case DRAW -> draws++;
            }
        }
        return new Score(victories + losses + draws, victories, losses, draws, failed);
    }

    /** Resolves the battle once on {@code board}, writing its report; null when the simulation failed. */
    public static @Nullable AutoResolveConcludedEvent resolve(SetupForces setup, Board board,
          PlanetaryConditions conditions) {
        try {
            return Resolver.simulationRun(setup, SimulationOptions.empty(), board, new PlanetaryConditions(conditions))
                  .resolveSimulation();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The auto-resolved battle failed");
            return null;
        }
    }
}
