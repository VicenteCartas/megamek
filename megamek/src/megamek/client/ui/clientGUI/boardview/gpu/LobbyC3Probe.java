/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.Deque;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import jakarta.annotation.Nullable;
import megamek.client.lobby.C3NetworkBoard.Network;
import megamek.client.lobby.C3NetworkBoard.Picture;
import megamek.client.lobby.C3NetworkBoard.PlanCard;
import megamek.logging.MMLogger;

/**
 * The developer switch's run through the C3 network manager ({@code lobby+c3}): it opens the manager
 * {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, as the forces' menu would; {@code +c3plan} clicks Plan Force 2.5 s
 * before the shot, or {@code +c3config=N} the Configuration N template; {@code +c3build} then clicks Build a second
 * later. Each click goes through the stage, and the board is logged just before the shot.
 */
final class LobbyC3Probe {
    private static final MMLogger LOGGER = MMLogger.create(LobbyC3Probe.class);
    private static final String CONFIG_OPTION = "c3config=";

    private record Click(double at, String name) {}

    private final double shotAt;
    private final Deque<Click> clicks = new ArrayDeque<>();
    private boolean opened;
    private boolean logged;

    private LobbyC3Probe(double shotAt) {
        this.shotAt = shotAt;
    }

    /** The run {@code probe} asks for, or null without {@code lobby+c3}. */
    static @Nullable LobbyC3Probe of(GpuLobbyProbe probe) {
        if (!probe.options().contains("c3")) {
            return null;
        }
        LobbyC3Probe run = new LobbyC3Probe(probe.shotAt());
        String config = probe.options().stream().filter(option -> option.startsWith(CONFIG_OPTION))
              .map(option -> option.substring(CONFIG_OPTION.length())).findFirst().orElse(null);
        if (config != null) {
            run.clicks.add(new Click(probe.shotAt() - 2.5, "c3-config-" + config));
        } else if (probe.options().contains("c3plan") || probe.options().contains("c3build")) {
            run.clicks.add(new Click(probe.shotAt() - 2.5, "c3-plan-force"));
        }
        if (probe.options().contains("c3build")) {
            run.clicks.add(new Click(probe.shotAt() - 1.5, "c3-build"));
        }
        return run;
    }

    /** GPU thread, each frame: opens the manager, clicks what is due and logs the board before the shot. */
    void step(double elapsed, Stage stage, Runnable open, @Nullable LobbyC3View view) {
        if (!opened && elapsed > GpuLobbyScreen.PROBE_CLICK_AT) {
            opened = true;
            LOGGER.info("The lobby probe opens the C3 network manager");
            open.run();
        }
        Picture picture = (view == null) ? null : view.shown();
        if (picture == null) {
            return;
        }
        if (!clicks.isEmpty() && elapsed > clicks.peekFirst().at()) {
            Click click = clicks.removeFirst();
            Actor target = stage.getRoot().findActor(click.name());
            boolean clicked = target != null && target.isVisible() && GpuLobbyProbeInput.click(stage, target);
            LOGGER.info("The lobby probe's C3 click on {}: {}", click.name(), clicked ? "clicked" : "not shown");
        }
        if (!logged && elapsed > shotAt - .3) {
            logged = true;
            LOGGER.info("The lobby probe's C3 board: {} unassigned, {} plan card(s), {} network(s); status \"{}\"",
                  picture.rosterUnits().size(), picture.plans().size(), picture.networks().size(), picture.status());
            for (PlanCard card : picture.plans()) {
                LOGGER.info("The lobby probe's C3 plan: {}", card.header());
            }
            for (Network network : picture.networks()) {
                LOGGER.info("The lobby probe's C3 network: {} {} - members {}", network.title(), network.config(),
                      network.members());
            }
        }
    }
}
