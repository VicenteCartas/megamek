/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import megamek.MMConstants;

/**
 * A developer switch for looking at the start screen without clicking through it, read from the environment:
 * <ul>
 *     <li>{@code MM_START_PAGE}: the page to open at once, without the greeting, such as {@code host}, with options
 *     after a plus: {@code host+online+reveal} opens the Host page's online options and shows its password;
 *     {@code resume} reads the newest save and {@code briefing} opens the scenario file {@code MM_START_SCENARIO}
 *     names, or the last one played, and {@code briefing+play} presses its Start {@code MM_START_SHOT_AT} seconds in,
 *     once the scenario has loaded; {@code lobby} hosts a game at once with the Host page's saved values (on the next
 *     free port when the saved one is taken), and the lobby's own probe ({@link GpuLobbyProbe}) takes over from there,
 *     taking the shot itself. Any page with {@code +welcome} shows the welcome over it, whatever the saved choice
 *     ({@code main+welcome}); without it a run with a page never shows the welcome.</li>
 *     <li>{@code MM_START_SHOT}: a PNG file the screen saves from its own frame buffer, {@code MM_START_SHOT_AT}
 *     seconds after its first frame (by default once the page has slid in, or the greeting has played).</li>
 *     <li>{@code MM_START_QUIT}: {@code true} to quit MegaMek once the shot is saved.</li>
 * </ul>
 * With none of them set, the start screen behaves as it always does.
 */
record StartScreenProbe(String page, Set<String> options, File shot, double shotAt, boolean quit) {
    /** The shot's time on a page: its items have slid in and its local address has been found. */
    static final double PAGE_SHOT_AT = 2.5;
    /** Not a start page: hosts at once and leaves the shot to the lobby. */
    static final String LOBBY = "LOBBY";

    /** The probe the environment asks for, or null when it asks for none. */
    static StartScreenProbe fromEnvironment() {
        return from(System.getenv());
    }

    static StartScreenProbe from(Map<String, String> environment) {
        String pageValue = value(environment, "MM_START_PAGE");
        String shotValue = value(environment, "MM_START_SHOT");
        if (pageValue == null && shotValue == null) {
            return null;
        }
        String page = null;
        Set<String> options = new TreeSet<>();
        if (pageValue != null) {
            String[] parts = pageValue.toLowerCase(Locale.ROOT).split("\\+");
            page = parts[0].trim().toUpperCase(Locale.ROOT);
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isBlank()) {
                    options.add(parts[i].trim());
                }
            }
        }
        double defaultAt = page != null ? PAGE_SHOT_AT : StartTimeline.INTRO_END + 1;
        double shotAt = defaultAt;
        String atValue = value(environment, "MM_START_SHOT_AT");
        if (atValue != null) {
            try {
                shotAt = Math.max(0, Double.parseDouble(atValue));
            } catch (NumberFormatException ignored) {
                shotAt = defaultAt;
            }
        }
        boolean quit = shotValue != null && Boolean.parseBoolean(value(environment, "MM_START_QUIT"));
        return new StartScreenProbe(page, Set.copyOf(options), shotValue == null ? null : new File(shotValue),
              shotAt, quit);
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    boolean has(String option) {
        return options.contains(option);
    }

    /** True when the probe asks for the lobby, whose shot the lobby takes rather than the start screen. */
    boolean lobby() {
        return LOBBY.equals(page);
    }

    /** {@code port} when nothing listens on it, otherwise the next free port, up to 20 further. */
    static int freePort(int port) {
        for (int candidate = port; candidate <= Math.min(port + 20, MMConstants.MAX_PORT); candidate++) {
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (IOException busy) {
                // Taken; try the next one
            }
        }
        return port;
    }
}
