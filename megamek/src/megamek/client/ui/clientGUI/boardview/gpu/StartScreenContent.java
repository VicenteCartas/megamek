/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import jakarta.annotation.Nullable;

/**
 * What a program's start screen shows in the native window ({@link GpuStartWindow}): its art, greeting, tips, version
 * and menu. The screen itself (the greeting typed over the dark field, the art revealed by hexes, the embers and the
 * menu sliding in at the right) is the same for every program of the suite.
 *
 * <p>The screen reads everything but {@link #settings} and {@link #quit} on its GPU thread, as it shows; those two
 * run on the Swing thread.</p>
 */
public abstract class StartScreenContent {
    /** The window's title. */
    public abstract String title();

    /** The art behind the screen, scaled to cover it; null for none. */
    public abstract @Nullable File art();

    /** The logo at the top of the menu column, or null for none, as when the art has its own. */
    public abstract @Nullable BufferedImage logo();

    /** The greeting's first, small line, such as the program and its version. */
    public abstract String greetingOverline();

    /** The greeting's large second line. */
    public abstract String greeting();

    /** The tips the screen cycles through; a tip's last line starting with "-" is its author. Empty for none. */
    public abstract List<String> tips();

    /** The version line in the top left corner. */
    public abstract String version();

    /** The main menu, read each time it shows; {@link GpuStartWindow#menuChanged} shows it again. */
    public abstract List<StartMenuItem> menu();

    /** The Help page's entries; empty leaves Help out of the bottom row. */
    public List<StartMenuItem> help() {
        return List.of();
    }

    /** Swing thread: the bottom row's Settings. */
    public abstract void settings();

    /** Swing thread: the bottom row's Quit, and the window's close button. */
    public abstract void quit();

    /** Whether the tip sits under the version at the top left rather than at the bottom left, clear of the art's. */
    public boolean tipAtTop() {
        return false;
    }

    /** Whether the screen has MegaMek's own pages, such as Host and Connect, rather than {@link #menu}. */
    boolean gamePages() {
        return false;
    }

    /**
     * How long ago {@code epochMillis} was, as the start screen words it for a save: "just now", minutes, hours,
     * yesterday, days, and the date beyond a week.
     */
    public static String ago(long epochMillis) {
        return StartSaves.ago(epochMillis, System.currentTimeMillis());
    }
}
