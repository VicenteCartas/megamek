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
import megamek.client.lobby.UnitDamageForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.Cell;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;

/**
 * The lobby's damage editor (L-polish, item 6): the unit's paperdoll on the left, coloured live by the edits with the
 * change from the unit as read, and the picked location's values on the right, or the unit's general values. A click
 * on the doll or the location list above the values picks what shows. Repair all brings the shown values back to a
 * whole unit; More… keeps the edits and opens the Swing editor for ammunition, modes and the damage roller.
 */
final class LobbyDamageView {
    /** The doll's width, and the most height it may take; a tall doll is fitted by its height instead. */
    private static final float DOLL_WIDTH = 260;
    private static final float DOLL_MAX_HEIGHT = 400;
    /**
     * The values' pane: one height for every location, so picking another never resizes the dialog, and low enough
     * that the dialog fits a 900 pixel high window at the shipped interface scale.
     */
    private static final float PANE_HEIGHT = 380;

    private final UiKit kit;
    private final UnitDamageForm form;
    private final List<UnitDamageForm.Place> places;
    private final GpuPaperdoll doll;
    private final GpuPaperdolls.Doll geometry;
    private final UiSelect where;
    private final Table values = new Table();
    private final ScrollPane valuesScroll;
    /** The picked location, or null for the general values. */
    private UnitDamageForm.Place chosen;
    private String hovered = "";

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

        List<String> names = new ArrayList<>();
        places.forEach(place -> names.add(place.name()));
        names.add(Messages.getString("UnitEditorDialog.general"));
        where = kit.tokenSelect(true, names.toArray(String[]::new));
        where.setName("damage-where");
        UiKit.onChange(where, () -> {
            int index = where.getSelectedIndex();
            choose(index >= 0 && index < places.size() ? places.get(index) : null);
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
            left.add(doll).size(width, height).top();
        }
        body.add(left).top().width(DOLL_WIDTH).padRight(space("xl"));

        Table right = new Table();
        right.top().left();
        right.add(where).left().row();
        values.top().left().pad(0, space("md"), space("md"), space("md"));
        valuesScroll = kit.scrollList(values, true);
        valuesScroll.setName("damage-values");
        right.add(valuesScroll).growX().height(PANE_HEIGHT).padTop(space("md"));
        body.add(right).top().growX();

        dialog.tool(Messages.getString("UnitEditorDialog.preExistingDamage.reset"), () -> {
            form.repairAll();
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

    private void choose(UnitDamageForm.Place place) {
        chosen = place;
        int index = place == null ? places.size() : places.indexOf(place);
        if (where.getSelectedIndex() != index) {
            where.select(index);
        }
        fillValues();
        refreshDoll();
        valuesScroll.setScrollY(0);
    }

    private void refresh() {
        fillValues();
        refreshDoll();
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
            values.add(label).left().growX().minWidth(0).padTop(gap);
            values.add(control(value, "damage-value-" + index)).right().padLeft(space("lg")).padTop(gap).row();
        }
    }

    private Actor control(UnitDamageForm.Value value, String name) {
        switch (value.kind()) {
            case STEPPER -> {
                UiStepper stepper = kit.stepper(value.value(), 0, value.max());
                stepper.setName(name);
                stepper.up().setName(name + "-up");
                stepper.down().setName(name + "-down");
                UiKit.onChange(stepper, () -> {
                    value.value(stepper.value());
                    refreshDoll();
                });
                Table line = new Table();
                line.add(stepper);
                line.add(kit.typeLabel("/ " + value.max(), "small", "text.secondary")).minWidth(space("xxxl") + space("md"))
                      .padLeft(space("xs"));
                return line;
            }
            case TOGGLE -> {
                UiChoice toggle = kit.toggleSwitch("", value.on());
                toggle.setName(name);
                UiKit.onChange(toggle, () -> {
                    value.value(toggle.isChecked() ? 1 : 0);
                    // a limb brought back fills its armor and structure, which the rebuilt rows show
                    refresh();
                });
                return toggle;
            }
            default -> {
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
        }
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
}
