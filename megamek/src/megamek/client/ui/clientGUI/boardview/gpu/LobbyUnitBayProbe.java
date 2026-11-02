/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.Deque;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import jakarta.annotation.Nullable;
import megamek.client.lobby.UnitConfigForm;
import megamek.logging.MMLogger;

/**
 * The developer switch's run through the unit bay ({@code lobby+configure}): it opens the bay
 * {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, for the player's first unit or with {@code +multi} for all of them, then
 * clicks the controls {@code +bayclick=name,name} names through the stage, one every {@value #CLICK_GAP} s, logging the
 * bay after each.
 */
final class LobbyUnitBayProbe {
    private static final MMLogger LOGGER = MMLogger.create(LobbyUnitBayProbe.class);
    /** The time between the probe's clicks in the bay, in seconds. */
    static final double CLICK_GAP = .6;

    private final boolean multi;
    private final Deque<String> clicks;
    private boolean opened;
    private double nextAt = -1;
    private boolean logged;

    private LobbyUnitBayProbe(boolean multi, Deque<String> clicks) {
        this.multi = multi;
        this.clicks = clicks;
    }

    /** The run {@code probe} asks for, or null without {@code lobby+configure}. */
    static @Nullable LobbyUnitBayProbe of(GpuLobbyProbe probe) {
        if (!probe.options().contains("configure")) {
            return null;
        }
        return new LobbyUnitBayProbe(probe.options().contains("multi"), new ArrayDeque<>(probe.bayClicks()));
    }

    /** Whether the run opens the bay for all the player's units. */
    boolean multi() {
        return multi;
    }

    /** GPU thread, each frame: opens the bay, clicks what is due and logs the bay as it goes. */
    void step(double elapsed, Stage stage, Runnable open, @Nullable LobbyUnitBayView bay) {
        if (!opened && elapsed > GpuLobbyScreen.PROBE_CLICK_AT) {
            opened = true;
            LOGGER.info("The lobby probe opens the unit bay for {}", multi ? "all the player's units" : "a unit");
            open.run();
        }
        if (bay == null) {
            return;
        }
        if (!logged) {
            logged = true;
            nextAt = elapsed + CLICK_GAP;
            log("opened", bay);
        }
        if (!clicks.isEmpty() && elapsed > nextAt) {
            nextAt = elapsed + CLICK_GAP;
            String name = clicks.removeFirst();
            Actor target = bay.control(name);
            boolean clicked = target != null && target.isVisible() && GpuLobbyProbeInput.click(stage, target);
            log(name + (clicked ? " clicked" : " not shown"), bay);
        }
    }

    private static void log(String what, LobbyUnitBayView bay) {
        UnitConfigForm form = bay.form();
        String gunnery = form.showsCrew() && !form.members().isEmpty()
              ? form.member(0).text(UnitConfigForm.Skill.GUNNERY) : "-";
        Actor ok = bay.control("bay-ok");
        boolean okEnabled = ok instanceof Button button && !button.isDisabled();
        LOGGER.info("The lobby probe's unit bay, {}: units {}, gunnery {}, zone {}, round {}, valid {}, OK {}", what,
              form.ids(), gunnery, form.zone(), form.round(), form.valid(), okEnabled ? "enabled" : "disabled");
    }
}
