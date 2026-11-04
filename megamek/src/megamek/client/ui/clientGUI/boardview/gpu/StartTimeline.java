/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * When each part of the start screen's intro plays, in seconds from the window's first frame: the greeting types
 * itself, hexes pop open across the screen to reveal the art, and the menu slides in. The greeting is skipped when the
 * screen reopens after a game, when motion is reduced, or when the player clicks or presses a key.
 */
final class StartTimeline {
    static final double TEXT_START = 0.35;
    static final double LINE_ONE_TYPING = 0.45;
    static final double LINE_TWO_DELAY = 0.55;
    static final double LINE_TWO_TYPING = 1.0;
    static final double REVEAL_START = 2.7;
    /** How long the hexes take to open, from the first to the last. */
    static final double REVEAL_DURATION = 1.5;
    static final double REVEAL_END = REVEAL_START + REVEAL_DURATION;
    static final double MENU_START = REVEAL_END - 0.25;
    static final double INTRO_END = MENU_START + 0.9;
    /** How long one hex takes to open fully. */
    static final double HEX_SPAN = 0.12;
    /** How long each menu item takes to slide in, and how far each starts after the one above it. */
    static final double ITEM_SLIDE = 0.4;
    static final double ITEM_STAGGER = 0.06;

    private StartTimeline() {
    }

    /** How far the reveal has come at {@code t}, from 0 to 1. */
    static double revealProgress(double t) {
        return clamp((t - REVEAL_START) / REVEAL_DURATION);
    }

    /** How many of the first greeting line's {@code length} characters are typed at {@code t}. */
    static int lineOneTyped(double t, int length) {
        return (int) (clamp((t - TEXT_START) / LINE_ONE_TYPING) * length);
    }

    /** How many of the second greeting line's {@code length} characters are typed at {@code t}. */
    static int lineTwoTyped(double t, int length) {
        return (int) (clamp((t - TEXT_START - LINE_TWO_DELAY) / LINE_TWO_TYPING) * length);
    }

    /** How far menu item {@code index} has slid in at {@code t}, eased, from 0 to 1. */
    static double itemProgress(double t, int index) {
        return ease((t - MENU_START - ITEM_STAGGER * index) / ITEM_SLIDE);
    }

    /** Eases out: fast at first, settling at 1. */
    static double ease(double x) {
        return 1 - Math.pow(1 - clamp(x), 3);
    }

    static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }
}
