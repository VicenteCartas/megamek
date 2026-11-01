/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import jakarta.annotation.Nullable;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.logging.MMLogger;

/**
 * The developer switch's run through the L17 tools and prompts: {@code lobby+victory}, {@code +randomarmy},
 * {@code +skills}, {@code +names}, {@code +readout}, {@code +princesshelp} or {@code +prompt=name} ({@code forcename},
 * {@code squadron}, {@code asset}, {@code assetdamage}, {@code train}, {@code confirm} or {@code error}) opens it
 * {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, as its control would; so do the L18 file routes: {@code +gameoptions}
 * and {@code +playersettings} open their dialogs (then {@code +click=options-save}, {@code options-load},
 * {@code settings-save-loadout} or {@code settings-load-loadout} asks the system file dialog), and {@code +savelist},
 * {@code +loadlist} and {@code +sprite} (the player's first unit) ask it at once. {@code +click=name,name} then clicks
 * its actors by name through the stage, one every {@value LobbyUnitBayProbe#CLICK_GAP} s and each once its actor shows
 * (up to {@value #WAIT} s after the opening), logging the tool after each.
 */
final class LobbyToolsProbe {
    private static final MMLogger LOGGER = MMLogger.create(LobbyToolsProbe.class);
    /** The tools the probe opens by name. */
    static final Set<String> TOOLS = Set.of("victory", "randomarmy", "skills", "names", "readout", "princesshelp",
          "gameoptions", "playersettings", "savelist", "loadlist", "sprite");
    private static final String PROMPT_OPTION = "prompt=";
    private static final String CLICK_OPTION = "click=";
    /** How long a click waits for its actor, as the random army waits for the unit tables, in seconds. */
    private static final double WAIT = 40;

    private final String tool;
    private final Deque<String> clicks;
    private boolean opened;
    private double nextAt = -1;
    private double openedAt;

    private LobbyToolsProbe(String tool, Deque<String> clicks) {
        this.tool = tool;
        this.clicks = clicks;
    }

    /** The run {@code probe} asks for, or null without one of the tools or a prompt. */
    static @Nullable LobbyToolsProbe of(GpuLobbyProbe probe) {
        String tool = null;
        Deque<String> clicks = new ArrayDeque<>();
        for (String option : probe.options()) {
            if (TOOLS.contains(option)) {
                tool = option;
            } else if (option.startsWith(PROMPT_OPTION)) {
                tool = option;
            } else if (option.startsWith(CLICK_OPTION)) {
                for (String name : option.substring(CLICK_OPTION.length()).split(",")) {
                    if (!name.isBlank()) {
                        clicks.add(name.trim());
                    }
                }
            }
        }
        return tool == null ? null : new LobbyToolsProbe(tool, clicks);
    }

    /** The tool, or {@code prompt=name}. */
    String tool() {
        return tool;
    }

    /** GPU thread, each frame: opens the tool, clicks what is due and logs the tool as it goes. */
    void step(double elapsed, Stage stage, Runnable open, @Nullable LobbyNativeDialogs dialogs) {
        if (!opened && elapsed > GpuLobbyScreen.PROBE_CLICK_AT) {
            opened = true;
            openedAt = elapsed;
            nextAt = elapsed + 2 * LobbyUnitBayProbe.CLICK_GAP;
            LOGGER.info("The lobby probe opens {}", tool);
            open.run();
            return;
        }
        if (!opened || clicks.isEmpty() || elapsed < nextAt) {
            return;
        }
        String name = clicks.peekFirst();
        Actor target = stage.getRoot().findActor(name);
        if ((target == null || !target.isVisible()) && elapsed < openedAt + WAIT) {
            return;
        }
        clicks.removeFirst();
        nextAt = elapsed + LobbyUnitBayProbe.CLICK_GAP;
        boolean clicked = target != null && target.isVisible() && GpuLobbyProbeInput.click(stage, target);
        log(name + (clicked ? " clicked" : " not shown"), dialogs);
    }

    private void log(String what, @Nullable LobbyNativeDialogs dialogs) {
        LOGGER.info("The lobby probe's {}, {}: {}", tool, what, state(dialogs));
    }

    private static String state(@Nullable LobbyNativeDialogs dialogs) {
        if (dialogs == null) {
            return "no dialogs";
        }
        if (dialogs.victoryView() != null) {
            VictoryConditionsForm form = dialogs.victoryView().form();
            return "victory, objectives " + form.activeObjectives() + ", changed " + form.changedOptions().size();
        }
        if (dialogs.randomArmyView() != null) {
            RandomArmyForm form = dialogs.randomArmyView().form();
            List<String> units = form.cards().stream()
                  .map(card -> card.unit().getName() + (card.kept() ? " (kept)" : "")).toList();
            return "army " + units + ", BV " + form.battleValue() + ", table " + form.table();
        }
        if (dialogs.readoutView() != null) {
            LobbyReadoutView view = dialogs.readoutView();
            return "readout, unit " + view.shown() + ", tab " + (view.tabs() == null ? "-"
                  : view.tabs().getSelectedIndex());
        }
        if (dialogs.crewView() != null) {
            return "crew tool open";
        }
        return dialogs.isOpen() ? "a dialog open" : "closed";
    }
}
