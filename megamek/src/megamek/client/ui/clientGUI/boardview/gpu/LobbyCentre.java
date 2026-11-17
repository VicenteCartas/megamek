/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.Consumer;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The lobby's centre column: the forces, or a view that takes their place, such as the folded battlefield and later
 * the map setup and Requisition. A view is a token panel with its title and Back to forces in its header; that, or
 * Escape (which the screen passes on), shows the forces again.
 */
final class LobbyCentre extends Container<Actor> {
    private final UiKit kit;
    private final Actor forces;
    private final boolean motion;
    private Consumer<String> changed = key -> { };
    private String showing;

    LobbyCentre(UiKit kit, Actor forces, boolean motion) {
        this.kit = kit;
        this.forces = forces;
        this.motion = motion;
        fill();
        setActor(forces);
    }

    /** Runs {@code listener} with the view's key, or null for the forces, each time the centre changes. */
    void onChange(Consumer<String> listener) {
        changed = listener;
    }

    /** @return the key of the view in the forces' place, or null while the forces show */
    String showing() {
        return showing;
    }

    /** Shows {@code body} in the forces' place as the view {@code key}, in a panel titled {@code title} with Back. */
    void show(String key, String title, Actor body) {
        // Bordered, unlike the views' quiet tools, so the way out stands apart from them
        UiButton back = kit.button("button-secondary" + UiTheme.COMPACT, "chevron-left",
              Messages.getString("Lobby.backToForces"), null);
        kit.tip(back, true).getActor().setText(Messages.getString("Lobby.backToForcesTip"));
        UiKit.onChange(back, this::back);
        Table view = kit.panel(true);
        view.add(kit.header(title, null, true, back)).growX().row();
        view.add(body).grow();
        showing = key;
        present(view);
        changed.accept(key);
    }

    /** Shows the forces again; nothing happens while they show. */
    void back() {
        if (showing == null) {
            return;
        }
        showing = null;
        present(forces);
        changed.accept(null);
    }

    /**
     * Whether Escape that takes the keys from {@code field} goes on to the view's own steps and Back: a field of the
     * view left empty, such as the unit search Add unit focuses, has nothing to keep, so one Escape leaves the view; a
     * field with text, or outside the view, keeps that Escape for itself.
     */
    boolean passesEscape(TextField field) {
        return showing != null && field.getText().isEmpty() && field.isDescendantOf(this);
    }

    private void present(Actor actor) {
        setActor(actor);
        actor.clearActions();
        if (motion) {
            actor.getColor().a = 0;
            actor.addAction(Actions.fadeIn(DesignTokens.shipped().duration("medium") / 1000f, Interpolation.pow2Out));
        } else {
            actor.getColor().a = 1;
        }
    }
}
