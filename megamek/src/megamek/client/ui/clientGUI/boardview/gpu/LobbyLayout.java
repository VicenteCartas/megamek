/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Where the lobby's parts go, as the Swing briefing room settled it (docs/design-system/gap-analysis-history.md,
 * step 6): the players at the left and the battlefield at the right keep their widths and the forces in the centre
 * take the rest; below {@link #FOLD_BELOW} the battlefield folds into a rail at the right edge. All in stage units,
 * which already hold the GUI scale, so it needs no OpenGL.
 */
final class LobbyLayout {
    /** The players column's width. */
    static final float PLAYERS_WIDTH = 260;
    /** The battlefield column's width. */
    static final float BATTLEFIELD_WIDTH = 320;
    /** The folded battlefield's rail width. */
    static final float RAIL_WIDTH = 48;
    /** The window width below which the battlefield folds into its rail. */
    static final float FOLD_BELOW = 1280;

    private LobbyLayout() { }

    /** A part's place, from the bottom left of its parent. */
    record Box(float x, float y, float width, float height) { }

    /**
     * The places of the players, the centre, and either the battlefield (unfolded) or its rail (folded); the other is
     * null.
     */
    record Columns(Box players, Box centre, Box battlefield, Box rail) { }

    /** @return whether a window {@code width} wide folds the battlefield into its rail */
    static boolean folds(float width) {
        return width < FOLD_BELOW;
    }

    /**
     * The columns in a {@code width} by {@code height} area, {@code gutter} apart. The players take at most a quarter
     * of the width and the battlefield at most a third; the centre takes the rest. Folded, the rail stands at the right
     * edge in the battlefield's place.
     */
    static Columns columns(float width, float height, float gutter, boolean fold) {
        float left = Math.min(PLAYERS_WIDTH, Math.max(0, width / 4));
        Box players = new Box(0, 0, left, height);
        float right = fold ? Math.min(RAIL_WIDTH, Math.max(0, width / 4)) : Math.min(BATTLEFIELD_WIDTH,
              Math.max(0, width / 3));
        Box centre = new Box(left + gutter, 0, Math.max(0, width - left - right - 2 * gutter), height);
        Box side = new Box(width - right, 0, right, height);
        return fold ? new Columns(players, centre, null, side) : new Columns(players, centre, side, null);
    }

    /**
     * The left edge of the top bar's chips, {@code chipsWidth} wide, between a heading {@code headingWidth} wide at the
     * bar's start and a trailing end {@code trailingWidth} wide at its end, in a bar {@code width} wide: centred on the
     * whole bar, so they stay put while the trailing end grows and shrinks, and pushed aside only where they would come
     * nearer than {@code gap} to either.
     */
    static float chipsX(float width, float headingWidth, float chipsWidth, float trailingWidth, float gap) {
        float from = headingWidth + gap;
        float to = width - trailingWidth - gap;
        float shown = Math.max(0, Math.min(chipsWidth, to - from));
        return Math.max(from, Math.min((width - shown) / 2, to - shown));
    }
}
