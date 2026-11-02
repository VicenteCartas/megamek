/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import jakarta.annotation.Nullable;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.RandomMapForm;
import megamek.client.ui.gdx.UiDialog;
import megamek.logging.MMLogger;

/**
 * The developer switch's run through the map setup ({@code lobby+mapsetup}) or the random map settings
 * ({@code lobby+randommap}): it opens them {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, as the battlefield column
 * would, then clicks the controls {@code +mapclick=name,name} names through the stage, one every
 * {@value #CLICK_GAP} s, in the map setup or the dialog over it, logging the setup after each and the server's map
 * settings once the clicks are done.
 */
final class LobbyMapSetupProbe {
    private static final MMLogger LOGGER = MMLogger.create(LobbyMapSetupProbe.class);
    /** The time between the probe's clicks, in seconds. */
    static final double CLICK_GAP = .8;

    private final boolean random;
    private final Deque<String> clicks;
    private boolean opened;
    private boolean shown;
    private boolean done;
    private double nextAt = -1;

    private LobbyMapSetupProbe(boolean random, Deque<String> clicks) {
        this.random = random;
        this.clicks = clicks;
    }

    /** The run {@code probe} asks for, or null without {@code lobby+mapsetup} or {@code lobby+randommap}. */
    static @Nullable LobbyMapSetupProbe of(GpuLobbyProbe probe) {
        boolean setup = probe.options().contains("mapsetup");
        boolean random = probe.options().contains("randommap");
        if (!setup && !random) {
            return null;
        }
        return new LobbyMapSetupProbe(random && !setup, new ArrayDeque<>(probe.mapClicks()));
    }

    /** Whether the run opens the random map settings rather than the map setup. */
    boolean random() {
        return random;
    }

    /**
     * GPU thread, each frame: opens the view, clicks what is due and logs as it goes.
     *
     * @param open   opens the map setup or the random map settings
     * @param dialog the lobby's top libGDX dialog, or null
     * @param server logs the server's map settings, with what happened
     */
    void step(double elapsed, Stage stage, Runnable open, @Nullable LobbyMapSetupView view,
          @Nullable LobbyRandomMapView randomView, @Nullable UiDialog dialog, Consumer<String> server) {
        if (!opened && elapsed > GpuLobbyScreen.PROBE_CLICK_AT) {
            opened = true;
            LOGGER.info("The lobby probe opens the {}", random ? "random map settings" : "map setup");
            open.run();
        }
        boolean ready = random ? randomView != null : view != null && view.shown() != null;
        if (!ready) {
            return;
        }
        if (!shown) {
            shown = true;
            nextAt = elapsed + CLICK_GAP;
            log("opened", view, randomView);
        }
        if (!clicks.isEmpty() && elapsed > nextAt) {
            nextAt = elapsed + CLICK_GAP;
            String name = clicks.removeFirst();
            Actor target = dialog == null ? null : dialog.card().findActor(name);
            if (target == null && view != null) {
                target = view.control(name);
            }
            boolean clicked = target != null && target.isVisible() && GpuLobbyProbeInput.click(stage, target);
            log(name + (clicked ? " clicked" : " not shown"), view, randomView);
        } else if (clicks.isEmpty() && !done && elapsed > nextAt) {
            done = true;
            server.accept("after the clicks");
        }
    }

    private void log(String what, @Nullable LobbyMapSetupView view, @Nullable LobbyRandomMapView randomView) {
        if (random && randomView != null) {
            RandomMapForm form = randomView.form();
            LOGGER.info("The lobby probe's random map settings, {}: advanced {}, board {}x{}, theme {}, problem {}",
                  what, form.advanced(), form.boardWidth(), form.boardHeight(), form.theme(), form.problem());
            return;
        }
        MapSetupForm.Picture picture = view == null ? null : view.shown();
        if (picture == null) {
            LOGGER.info("The lobby probe's map setup, {}: not shown", what);
            return;
        }
        LOGGER.info("The lobby probe's map setup, {}: medium {}, map {}x{} boards of {}x{}, slots {}, picked {}", what,
              picture.medium(), picture.mapWidth(), picture.mapHeight(), picture.boardWidth(), picture.boardHeight(),
              picture.slots().stream().map(MapSetupForm.Slot::board).toList(), view.pickedSlots());
    }
}
