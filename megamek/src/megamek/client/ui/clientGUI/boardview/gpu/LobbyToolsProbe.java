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
 * {@code +skills}, {@code +names}, {@code +readout} (or {@code +readout=tro}, {@code bv} or {@code cost} for that
 * page), {@code +princesshelp}, {@code +maphelp}, {@code +resolve} (auto-resolve with its odds, or
 * {@code +resolve=quick} without, once the player's units arrive) or {@code +prompt=name} ({@code forcename},
 * {@code squadron}, {@code asset}, {@code assetdamage}, {@code train}, {@code confirm} or {@code error}) opens it
 * {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, as its control would; so do the L18 file routes: {@code +gameoptions}
 * and {@code +playersettings} open their dialogs (then {@code +click=options-save}, {@code options-load},
 * {@code settings-save-loadout} or {@code settings-load-loadout} asks the system file dialog), and {@code +savelist},
 * {@code +loadlist} and {@code +sprite} (the player's first unit) ask it at once. {@code +click=name,name} then clicks
 * its actors by name through the stage, one every {@value LobbyUnitBayProbe#CLICK_GAP} s and each once its actor shows
 * (up to {@value #WAIT} s after the opening), logging the tool after each; a step {@code type:text} types into the
 * focused field.
 */
final class LobbyToolsProbe {
    private static final MMLogger LOGGER = MMLogger.create(LobbyToolsProbe.class);
    /** The tools the probe opens by name. */
    static final Set<String> TOOLS = Set.of("victory", "randomarmy", "skills", "names", "readout", "princesshelp", "maphelp",
          "gameoptions", "playersettings", "savelist", "loadlist", "sprite", "resolve", "resolve=quick");
    private static final String PROMPT_OPTION = "prompt=";
    /** {@code readout=tro}, {@code readout=bv} or {@code readout=cost}: the readout, opened on that page. */
    private static final String READOUT_OPTION = "readout=";
    private static final String CLICK_OPTION = "click=";
    /** A click step {@code type:text} types its text into the field with the keyboard focus instead. */
    private static final String TYPE_STEP = "type:";
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
            } else if (option.startsWith(PROMPT_OPTION) || option.startsWith(READOUT_OPTION)) {
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
        if (name.startsWith(TYPE_STEP)) {
            clicks.removeFirst();
            nextAt = elapsed + LobbyUnitBayProbe.CLICK_GAP;
            for (char character : name.substring(TYPE_STEP.length()).toCharArray()) {
                stage.keyTyped(character);
            }
            log("typed " + name.substring(TYPE_STEP.length()), dialogs);
            return;
        }
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
            LobbyRandomArmyView view = dialogs.randomArmyView();
            LobbyForceGeneratorPane force = view.forcePane();
            if (view.mode() == LobbyRandomArmyView.Mode.FORCE && force != null) {
                return "army force " + force.form().organization() + ", units " + force.form().allUnits().size()
                      + ", included " + force.form().included().size() + (force.busy() ? " (busy)" : "");
            }
            RandomArmyForm form = view.form();
            List<String> units = form.cards().stream()
                  .map(card -> card.unit().getName() + (card.kept() ? " (kept)" : "")).toList();
            String source = view.mode() == LobbyRandomArmyView.Mode.TABLES
                  ? "table " + form.table() + ", browsing " + view.browser().path()
                  : "generator " + view.mode() + (view.factionsReady() ? "" : " (loading)")
                        + (form.context() != null ? ", for " + form.context().faction() : "");
            return "army " + units + ", BV " + form.battleValue() + ", " + source
                  + (view.filtering() ? ", filtering" : "");
        }
        if (dialogs.readoutView() != null) {
            LobbyReadoutView view = dialogs.readoutView();
            return "readout, unit " + view.shown() + ", tab " + (view.tabs() == null ? "-"
                  : view.tabs().getSelectedIndex());
        }
        if (dialogs.autoResolveView() != null) {
            return "auto-resolve " + dialogs.autoResolveView().phase();
        }
        if (dialogs.crewView() != null) {
            return "crew tool open";
        }
        return dialogs.isOpen() ? "a dialog open" : "closed";
    }
}
