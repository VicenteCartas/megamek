/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;

/**
 * The lobby's three columns under its top bar ({@link LobbyLayout}): the players, the centre, and the battlefield or,
 * folded, its rail.
 */
final class LobbyColumns extends WidgetGroup {
    private final Actor players;
    private final Actor centre;
    private final Actor battlefield;
    private final Actor rail;
    private final float gutter;
    private boolean fold;
    private boolean expanded;

    LobbyColumns(Actor players, Actor centre, Actor battlefield, Actor rail, float gutter) {
        this.players = players;
        this.centre = centre;
        this.battlefield = battlefield;
        this.rail = rail;
        this.gutter = gutter;
        addActor(players);
        addActor(centre);
        addActor(battlefield);
        addActor(rail);
    }

    /** Folds the battlefield into its rail, or unfolds it. */
    void fold(boolean folded) {
        if (fold != folded) {
            fold = folded;
            invalidate();
        }
    }

    /** Lets a centre view use the battlefield column's space until it closes. */
    void expandCentre(boolean expand) {
        if (expanded != expand) {
            expanded = expand;
            invalidate();
        }
    }

    @Override
    public void layout() {
        LobbyLayout.Columns columns = LobbyLayout.columns(getWidth(), getHeight(), gutter, fold);
        place(players, columns.players());
        LobbyLayout.Box middle = columns.centre();
        place(centre, expanded ? new LobbyLayout.Box(middle.x(), middle.y(),
              Math.max(0, getWidth() - middle.x()), middle.height()) : middle);
        place(battlefield, expanded ? null : columns.battlefield());
        place(rail, expanded ? null : columns.rail());
    }

    private static void place(Actor actor, LobbyLayout.Box box) {
        actor.setVisible(box != null);
        if (box != null) {
            actor.setBounds(Math.round(box.x()), Math.round(box.y()), Math.round(box.width()),
                  Math.round(box.height()));
        }
    }
}
