/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The Damage overlay's numbers: the expected damage of the unit the overlay shows on each tinted hex, centred on the
 * hex on a small plate, so the board says how much rather than only where.
 */
final class GpuDamageNumbers implements GpuHud.Component {
    /** A hex's expected damage and where its centre lies in stage units (y up). */
    record HexDamage(Vector2 at, int value) { }

    /** At most this many numbers show; the board view leaves out hexes too small on the screen for one. */
    private static final int MOST = 400;

    private final UiKit ui;
    private final Group root = new Group();
    private final List<Plate> plates = new ArrayList<>();
    private List<HexDamage> numbers = List.of();

    GpuDamageNumbers(GpuHudKit kit) {
        ui = kit.ui;
        root.setName("damage-numbers");
        root.setTransform(false);
        root.setTouchable(Touchable.disabled);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** This frame's numbers, from the board view. */
    void numbers(List<HexDamage> numbers) {
        this.numbers = numbers;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        boolean hidden = inputs.dialog() != null || inputs.view().playbackBusy();
        int shown = 0;
        for (HexDamage number : hidden ? List.<HexDamage>of() : numbers) {
            if (shown == MOST) {
                break;
            }
            if (shown == plates.size()) {
                plates.add(new Plate());
            }
            plates.get(shown++).show(number);
        }
        for (int index = shown; index < plates.size(); index++) {
            plates.get(index).table.setVisible(false);
        }
    }

    private final class Plate {
        private final Table table = new Table();
        private final Label label = ui.label("", "hud-medium", 12, UiTheme.TEXT);
        private int value = -1;

        Plate() {
            table.setBackground(ui.skin.getDrawable("tip"));
            table.pad(0, 4, 0, 4);
            table.add(label);
            root.addActor(table);
        }

        void show(HexDamage number) {
            if (number.value() != value) {
                value = number.value();
                label.setText(Integer.toString(value));
                table.pack();
            }
            table.setPosition(number.at().x - table.getWidth() / 2, number.at().y - table.getHeight() / 2);
            table.setVisible(true);
        }
    }
}
