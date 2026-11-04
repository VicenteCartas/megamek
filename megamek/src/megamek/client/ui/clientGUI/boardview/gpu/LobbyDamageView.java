/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.Cell;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiHealthBar;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.compute.damage.PreExistingDamageLevel;

/**
 * The lobby's damage editor (L-polish, item 6): the unit's paperdoll on the left, coloured live by the edits with the
 * change from the unit as read, the Whole unit button under it; on the right the picked location's name, stepped through with the chevrons beside it, over its values: armor and
 * structure as health bars, hits as boxes and states as switches; under both, the pre-existing damage roller. A click
 * on the doll picks a location. Restore Unit
 * brings every value back to a whole unit; More… keeps the edits and opens the Swing editor for what this one lacks.
 */
final class LobbyDamageView {
    /** The doll's width, and the most height it may take; a tall doll is fitted by its height instead. */
    private static final float DOLL_WIDTH = 260;
    private static final float DOLL_MAX_HEIGHT = 330;
    /**
     * The values' pane: one height for every location, so picking another never resizes the dialog, and low enough
     * that the dialog fits a 900 pixel high window at the shipped interface scale.
     */
    private static final float PANE_HEIGHT = 340;
    private static final PreExistingDamageLevel[] LEVELS = PreExistingDamageLevel.values();

    private final UiKit kit;
    private final UnitDamageForm form;
    private final List<UnitDamageForm.Place> places;
    private final GpuPaperdoll doll;
    private final GpuPaperdolls.Doll geometry;
    private final Label heading;
    private final UiButton wholeUnit;
    private final UiKit.Segmented roller;
    private final UiButton reroll;
    private final Table values = new Table();
    private final ScrollPane valuesScroll;
    /** The picked location, or null for the general values. */
    private UnitDamageForm.Place chosen;
    private String hovered = "";
    /** The level last rolled, or null when nothing was rolled since the dialog opened or the unit was restored. */
    private PreExistingDamageLevel rolled;

    LobbyDamageView(UiKit kit, UiDialog dialog, UnitDamageForm form, GpuPaperdolls paperdolls,
          LobbyPromptViews.Edt edt, Runnable applied, Runnable more, Runnable close) {
        this.kit = kit;
        this.form = form;
        places = form.places();
        String family = GpuPaperdolls.family(form.entity());
        geometry = family.isEmpty() ? null : paperdolls.doll(family, GpuPaperdolls.ARMOR, Set.of());
        doll = new GpuPaperdoll(kit.skin);
        doll.setName("damage-doll");
        doll.maxFont(16);
        doll.setTouchable(Touchable.enabled);
        doll.addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                String picked = doll.pick(x, y);
                hovered = picked == null ? "" : picked;
                marks();
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1) {
                    hovered = "";
                    marks();
                }
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT) {
                    return false;
                }
                pickCode(doll.pick(x, y));
                return true;
            }
        });

        Table body = dialog.body();
        body.top().left();
        Table left = new Table();
        left.top();
        if (geometry != null) {
            float[] bounds = geometry.bounds();
            float width = DOLL_WIDTH;
            float height = width * bounds[3] / bounds[2];
            if (height > DOLL_MAX_HEIGHT) {
                height = DOLL_MAX_HEIGHT;
                width = height * bounds[2] / bounds[3];
            }
            left.add(doll).size(width, height).top().row();
        }
        wholeUnit = kit.button("button-secondary" + UiTheme.COMPACT, "unit",
              Messages.getString("Lobby.damage.wholeUnit"), null);
        wholeUnit.setName("damage-whole");
        UiKit.onChange(wholeUnit, () -> choose(null));
        left.add(wholeUnit).growX().padTop(space("md")).row();

        String[] names = new String[LEVELS.length];
        for (int index = 0; index < LEVELS.length; index++) {
            names[index] = Messages.getString("Lobby.damage." + LEVELS[index].name());
        }
        roller = kit.segmented(UiTheme.TOKEN_SEGMENT + UiTheme.COMPACT, true, names);
        for (int index = 0; index < LEVELS.length; index++) {
            UiButton segment = roller.buttons.get(index);
            PreExistingDamageLevel level = LEVELS[index];
            segment.setName("damage-roll-" + level.name().toLowerCase());
            kit.tip(segment, true).getActor().setText(
                  Messages.getString("UnitEditorDialog.preExistingDamage." + level.name()));
            UiKit.onChange(segment, () -> roll(level));
        }
        reroll = kit.button("button-icon" + UiTheme.COMPACT, "die", null, null);
        reroll.setName("damage-reroll");
        kit.tip(reroll, true).getActor().setText(Messages.getString("Lobby.damage.reroll"));
        UiKit.onChange(reroll, () -> roll(rolled));
        body.add(left).top().width(DOLL_WIDTH).padRight(space("xl"));

        Table right = new Table();
        right.top().left();
        Table head = new Table();
        UiButton previous = kit.button("button-icon" + UiTheme.COMPACT, "chevron-left", null, null);
        previous.setName("damage-previous");
        UiKit.onChange(previous, () -> step(-1));
        UiButton next = kit.button("button-icon" + UiTheme.COMPACT, "chevron-right", null, null);
        next.setName("damage-next");
        UiKit.onChange(next, () -> step(1));
        heading = kit.typeLabel("", "title", "text.primary");
        heading.setName("damage-heading");
        heading.setAlignment(Align.center);
        heading.setEllipsis(true);
        head.add(previous).size(size("control.sm"));
        head.add(heading).growX().minWidth(0).padLeft(space("sm")).padRight(space("sm"));
        head.add(next).size(size("control.sm"));
        right.add(head).growX().row();
        values.top().left().pad(0, space("md"), space("md"), space("md"));
        valuesScroll = kit.scrollList(values, true);
        valuesScroll.setName("damage-values");
        right.add(valuesScroll).growX().height(PANE_HEIGHT).padTop(space("md"));
        body.add(right).top().growX().row();
        if (form.rollable()) {
            // the roller is the whole unit's, so it spans both columns under them
            Table rolling = new Table();
            rolling.add(kit.typeLabel(Messages.getString("UnitEditorDialog.preExistingDamage"), "body",
                  "text.secondary")).left().padRight(space("lg"));
            rolling.add(roller).growX();
            rolling.add(reroll).size(size("control.sm")).padLeft(space("xs"));
            body.add(rolling).colspan(2).growX().padTop(space("lg"));
        }

        dialog.tool(Messages.getString("UnitEditorDialog.preExistingDamage.reset"), () -> {
            form.repairAll();
            rolled = null;
            roller.select(-1);
            reroll.setDisabled(true);
            refresh();
        }).setName("damage-repair");
        dialog.tool(Messages.getString("Lobby.damage.more"), () -> {
            close.run();
            edt.edt(() -> {
                form.apply();
                more.run();
            }, () -> { });
        }).setName("damage-more");
        dialog.primary(Messages.getString("Ok.text"), () -> edt.edt(() -> {
            form.apply();
            applied.run();
        }, close)).setName("damage-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("damage-cancel");

        reroll.setDisabled(true);
        choose(places.stream().filter(place -> place.abbr().equals("CT")).findFirst()
              .orElse(places.isEmpty() ? null : places.get(0)));
    }

    /** The location or general values that show. */
    UnitDamageForm.Place chosen() {
        return chosen;
    }

    /** Picks the location with the paperdoll region {@code code}, or the general values for structural integrity. */
    void pickCode(String code) {
        if (code == null) {
            return;
        }
        UnitDamageForm.Place place = form.place(code);
        if (place != null) {
            choose(place);
        } else if (code.equals("SI")) {
            choose(null);
        }
    }

    /** Steps to the next location, or back with a negative {@code by}; the whole unit follows the last one. */
    private void step(int by) {
        int count = places.size() + 1;
        int at = chosen == null ? places.size() : places.indexOf(chosen);
        int next = Math.floorMod(at + by, count);
        choose(next < places.size() ? places.get(next) : null);
    }

    private void roll(PreExistingDamageLevel level) {
        if (level == null) {
            return;
        }
        form.roll(level);
        rolled = level;
        roller.select(List.of(LEVELS).indexOf(level));
        reroll.setDisabled(level == PreExistingDamageLevel.NONE);
        refresh();
    }

    private void choose(UnitDamageForm.Place place) {
        chosen = place;
        heading.setText(place == null ? Messages.getString("Lobby.damage.wholeUnit") : place.name());
        wholeUnit.pressed(place == null);
        fillValues();
        refreshDoll();
        valuesScroll.setScrollY(0);
    }

    private void refresh() {
        float scroll = valuesScroll.getScrollY();
        fillValues();
        refreshDoll();
        valuesScroll.layout();
        valuesScroll.setScrollY(scroll);
    }

    private void fillValues() {
        values.clearChildren();
        List<UnitDamageForm.Value> shown = chosen == null ? form.general() : chosen.values();
        float gap = space("sm");
        for (int index = 0; index < shown.size(); index++) {
            UnitDamageForm.Value value = shown.get(index);
            if (value.kind() == UnitDamageForm.Kind.BOXES && value.max() <= 0) {
                continue;
            }
            Label label = kit.typeLabel(value.label(), "body", "text.secondary");
            label.setEllipsis(true);
            String name = "damage-value-" + index;
            if (value.kind() == UnitDamageForm.Kind.STEPPER) {
                // a bar takes the row's width under its label, as a stat block in a game shows it
                values.add(label).left().growX().minWidth(0).colspan(2).padTop(index == 0 ? 0 : space("md")).row();
                values.add(bar(value, name)).growX().colspan(2).padTop(space("xxs")).row();
                continue;
            }
            values.add(label).left().growX().minWidth(0).padTop(gap);
            values.add(control(value, name)).right().padLeft(space("lg")).padTop(gap).row();
        }
    }

    private UiHealthBar bar(UnitDamageForm.Value value, String name) {
        UiHealthBar bar = kit.healthBar(value.value(), value.max(), value.original());
        bar.setName(name);
        bar.bar().setName(name + "-bar");
        bar.up().setName(name + "-up");
        bar.down().setName(name + "-down");
        bar.field().setName(name + "-field");
        UiKit.onChange(bar, () -> {
            value.value(bar.value());
            refreshDoll();
        });
        return bar;
    }

    private Actor control(UnitDamageForm.Value value, String name) {
        if (value.kind() == UnitDamageForm.Kind.TOGGLE) {
            UiChoice toggle = kit.toggleSwitch("", value.on());
            toggle.setName(name);
            UiKit.onChange(toggle, () -> {
                value.value(toggle.isChecked() ? 1 : 0);
                // a limb brought back fills its armor and structure, which the rebuilt rows show
                refresh();
            });
            return toggle;
        }
        Table boxes = new Table();
        List<UiChoice> checks = new ArrayList<>();
        for (int box = 0; box < value.max(); box++) {
            UiChoice check = kit.check("", box < value.value());
            check.setName(name + "-" + box);
            int at = box;
            UiKit.onChange(check, () -> {
                // a click on a hit box past the hits hits up to it; on the last hit, clears that one
                value.value(check.isChecked() ? at + 1 : at);
                for (int each = 0; each < checks.size(); each++) {
                    checks.get(each).setChecked(each < value.value());
                }
                refreshDoll();
            });
            checks.add(check);
            boxes.add(check).padLeft(box == 0 ? 0 : space("xxs"));
        }
        return boxes;
    }

    private void refreshDoll() {
        if (geometry == null) {
            return;
        }
        Map<String, Cell> armor = new HashMap<>();
        Map<String, Cell> structure = new HashMap<>();
        for (UnitDamageForm.Place place : places) {
            UnitDamageForm.Value internal = place.internal();
            UnitDamageForm.Value front = place.armor();
            boolean destroyed = internal != null && internal.value() <= 0;
            boolean crit = place.values().stream().anyMatch(value -> value.kind() == UnitDamageForm.Kind.BOXES
                  && value.value() > 0);
            int remaining = internal == null ? 1 : internal.value();
            if (front != null) {
                armor.put(place.abbr(), GpuPaperdoll.cell(front.value(), front.max(), destroyed, remaining, crit,
                      View.CARD).with(false, Math.max(0, front.original() - front.value())));
            } else if (internal != null) {
                armor.put(place.abbr(), GpuPaperdoll.cell(internal.value(), internal.max(), destroyed,
                      internal.value(), crit, View.CARD).with(false, Math.max(0, internal.original()
                      - internal.value())));
            }
            if (internal != null) {
                structure.put(place.abbr(), GpuPaperdoll.cell(internal.value(), internal.max(), destroyed,
                      internal.value(), false, View.STRUCTURE).with(false, Math.max(0, internal.original()
                      - internal.value())));
            }
        }
        UnitDamageForm.Value si = form.structuralIntegrity();
        if (si != null) {
            structure.put("SI", GpuPaperdoll.cell(si.value(), si.max(), false, si.value(), false, View.STRUCTURE)
                  .with(false, Math.max(0, si.original() - si.value())));
        }
        doll.show(geometry, armor, structure);
        marks();
    }

    private void marks() {
        doll.marks(hovered, chosen == null ? "" : chosen.abbr(), "");
    }

    private static float space(String token) {
        return DesignTokens.shipped().space().get(token);
    }

    private static float size(String token) {
        return DesignTokens.shipped().size().get(token);
    }
}
