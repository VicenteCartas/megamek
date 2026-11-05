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
import megamek.client.ui.gdx.UiLevelTiles;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.compute.damage.PreExistingDamageLevel;

/**
 * The lobby's Unit Status dialog (L-polish, item 6): the unit's paperdoll on the left, coloured live by the edits with
 * the change from the unit as read, or a list of its locations when it has no paperdoll, the Whole unit button under
 * it; a Mek shows its record sheet's three dolls instead, front armor beside rear armor over structure. On the right
 * the picked location's name, stepped through with the chevrons beside it, over its values: armor and structure as
 * health bars, hits as boxes and states as switches; under both, the pre-existing damage roller's level tiles. A click
 * on any doll picks a location. Restore Unit brings every value back to a whole unit. A unit with no locations to edit,
 * such as conventional infantry, shows its values alone. The whole unit's values end with its deployment: heat,
 * stance, hidden, scan target and deployment round, which its owner sets even where a Game Master keeps the damage,
 * showing it read-only.
 */
final class LobbyDamageView {
    /** The doll's width, and the most height it may take; a tall doll is fitted by its height instead. */
    private static final float DOLL_WIDTH = 260;
    private static final float DOLL_MAX_HEIGHT = 340;
    /** A Mek's front armor doll's height; its rear armor and structure dolls stack beside it in the same height. */
    private static final float SHEET_HEIGHT = 288;
    /**
     * The values' pane: one height for every location, so picking another never resizes the dialog, and low enough
     * that the dialog fits a 900 pixel high window at the shipped interface scale.
     */
    private static final float PANE_HEIGHT = 348;
    private static final PreExistingDamageLevel[] LEVELS = PreExistingDamageLevel.values();
    /** The roller's tiles' colours, from a whole unit to heavy damage. */
    private static final String[] LEVEL_ROLES = { "status.success", "accent.primary", "status.warning",
                                                  "status.danger" };

    private final UiKit kit;
    private final UnitDamageForm form;
    private final List<UnitDamageForm.Place> places;
    private final GpuPaperdoll doll;
    private final GpuPaperdolls.Doll geometry;
    /** A Mek's rear armor and structure dolls, as the record sheet shows them, or null for another unit. */
    private final GpuPaperdoll rearDoll;
    private final GpuPaperdolls.Doll rearGeometry;
    private final GpuPaperdoll insideDoll;
    private final GpuPaperdolls.Doll insideGeometry;
    private final List<GpuPaperdoll> dolls = new ArrayList<>();
    private final Label heading;
    private final UiButton wholeUnit;
    private final UiLevelTiles roller;
    private final UiButton reroll;
    private final Table values = new Table();
    private final List<UiButton> placeButtons = new ArrayList<>();
    private final ScrollPane valuesScroll;
    /** The picked location, or null for the general values. */
    private UnitDamageForm.Place chosen;
    private String hovered = "";
    /** The level last rolled, or null when nothing was rolled since the dialog opened or the unit was restored. */
    private PreExistingDamageLevel rolled;

    LobbyDamageView(UiKit kit, UiDialog dialog, UnitDamageForm form, GpuPaperdolls paperdolls,
          LobbyPromptViews.Edt edt, Runnable applied, Runnable close) {
        this.kit = kit;
        this.form = form;
        places = form.places();
        String family = GpuPaperdolls.family(form.entity());
        geometry = family.isEmpty() ? null : paperdolls.doll(family, GpuPaperdolls.ARMOR, Set.of());
        boolean sheet = sheet(paperdolls, form);
        rearGeometry = sheet ? paperdolls.doll(family, GpuPaperdolls.REAR, Set.of()) : null;
        insideGeometry = sheet ? paperdolls.doll(family, GpuPaperdolls.STRUCTURE, Set.of()) : null;
        doll = doll("damage-doll");
        rearDoll = sheet ? doll("damage-doll-rear") : null;
        insideDoll = sheet ? doll("damage-doll-structure") : null;

        Table body = dialog.body();
        body.top().left();
        Table left = new Table();
        left.top();
        // with only the general values there is nothing to step through, so the unit's values take the dialog
        boolean located = !places.isEmpty();
        if (sheet) {
            left.add(sheetDolls()).top().row();
        } else if (geometry != null) {
            float[] bounds = geometry.bounds();
            float width = DOLL_WIDTH;
            float height = width * bounds[3] / bounds[2];
            if (height > DOLL_MAX_HEIGHT) {
                height = DOLL_MAX_HEIGHT;
                width = height * bounds[2] / bounds[3];
            }
            left.add(doll).size(width, height).top().row();
        } else if (located) {
            // no paperdoll to click, so the locations are a list to pick from
            Table list = new Table();
            list.top();
            for (int index = 0; index < places.size(); index++) {
                UnitDamageForm.Place place = places.get(index);
                UiButton button = kit.button("button-toggle" + UiTheme.COMPACT, null, place.name(), null);
                button.setName("damage-place-" + index);
                UiKit.onChange(button, () -> choose(place));
                placeButtons.add(button);
                list.add(button).growX().padTop(index == 0 ? 0 : space("xxs")).row();
            }
            left.add(kit.scrollList(list, true)).growX().height(DOLL_MAX_HEIGHT).top().row();
        }
        wholeUnit = kit.button("button-toggle" + UiTheme.COMPACT, "unit",
              Messages.getString("Lobby.damage.wholeUnit"), null);
        wholeUnit.setName("damage-whole");
        UiKit.onChange(wholeUnit, () -> choose(null));
        left.add(wholeUnit).growX().padTop(space("md")).row();

        String[] names = new String[LEVELS.length];
        for (int index = 0; index < LEVELS.length; index++) {
            names[index] = Messages.getString("Lobby.damage." + LEVELS[index].name());
        }
        roller = kit.levelTiles(names, LEVEL_ROLES);
        roller.setName("damage-roller");
        for (int index = 0; index < LEVELS.length; index++) {
            UiLevelTiles.Tile tile = roller.tiles.get(index);
            PreExistingDamageLevel level = LEVELS[index];
            tile.setName("damage-roll-" + level.name().toLowerCase());
            kit.tip(tile, true).getActor().setText(
                  Messages.getString("UnitEditorDialog.preExistingDamage." + level.name()));
            UiKit.onChange(tile, () -> roll(level));
        }
        reroll = kit.button("button-icon" + UiTheme.COMPACT, "die", null, null);
        reroll.setName("damage-reroll");
        kit.tip(reroll, true).getActor().setText(Messages.getString("Lobby.damage.reroll"));
        UiKit.onChange(reroll, () -> roll(rolled));
        if (located) {
            body.add(left).top().width(sheet ? left.getPrefWidth() : DOLL_WIDTH).padRight(space("xl"));
        }

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
        if (located) {
            head.add(previous).size(size("control.sm"));
        }
        head.add(heading).growX().minWidth(0).padLeft(space("sm")).padRight(space("sm"));
        if (located) {
            head.add(next).size(size("control.sm"));
        }
        right.add(head).growX().row();
        values.top().left().pad(0, space("md"), space("md"), space("md"));
        valuesScroll = kit.scrollList(values, true);
        valuesScroll.setName("damage-values");
        right.add(valuesScroll).growX().height(PANE_HEIGHT).padTop(space("md"));
        body.add(right).top().growX().row();
        boolean damage = form.damageEditable();
        if (!damage) {
            // the damage is the Game Master's, so it only shows, and the roller's row says why
            Label note = kit.typeLabel(Messages.getString("Lobby.status.readOnly"), "small", "text.muted");
            note.setName("status-read-only");
            note.setWrap(true);
            body.add(note).colspan(located ? 2 : 1).growX().padTop(space("lg"));
        } else if (form.rollable()) {
            // the roller is the whole unit's, so it spans both columns under them
            Table rolling = new Table();
            rolling.add(kit.typeLabel(Messages.getString("UnitEditorDialog.preExistingDamage"), "body",
                  "text.secondary")).left().padRight(space("lg"));
            rolling.add(roller).growX();
            rolling.add(reroll).size(size("control.sm")).padLeft(space("xs"));
            body.add(rolling).colspan(located ? 2 : 1).growX().padTop(space("lg"));
        }

        UiButton repair = dialog.tool(Messages.getString("UnitEditorDialog.preExistingDamage.reset"), () -> {
            form.repairAll();
            rolled = null;
            roller.select(-1);
            reroll.setDisabled(true);
            refresh();
        });
        repair.setName("damage-repair");
        repair.setDisabled(!damage);
        dialog.primary(Messages.getString("Ok.text"), () -> edt.edt(() -> {
            form.apply();
            applied.run();
        }, close)).setName("damage-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("damage-cancel");

        reroll.setDisabled(true);
        // a player who may not edit the damage comes for the deployment, which the whole unit's values hold
        choose(!damage ? null : places.stream().filter(place -> place.abbr().equals("CT")).findFirst()
              .orElse(places.isEmpty() ? null : places.get(0)));
    }

    /** The location or general values that show. */
    UnitDamageForm.Place chosen() {
        return chosen;
    }

    /**
     * Whether the unit's dolls are a record sheet's: a Mek's front armor, rear armor and structure dolls side by side,
     * where another unit has one doll.
     */
    static boolean sheet(GpuPaperdolls paperdolls, UnitDamageForm form) {
        String family = GpuPaperdolls.family(form.entity());
        return !family.isEmpty() && !form.places().isEmpty()
              && paperdolls.doll(family, GpuPaperdolls.REAR, Set.of()) != null
              && paperdolls.doll(family, GpuPaperdolls.STRUCTURE, Set.of()) != null;
    }

    /** A doll whose hovered location the others outline too, and whose click picks a location. */
    private GpuPaperdoll doll(String name) {
        GpuPaperdoll made = new GpuPaperdoll(kit.skin);
        made.setName(name);
        made.maxFont(16);
        made.vivid(true);
        made.setTouchable(Touchable.enabled);
        made.addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                String picked = made.pick(x, y);
                hovered = picked == null ? "" : picked;
                marks();
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(made))) {
                    hovered = "";
                    marks();
                }
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT) {
                    return false;
                }
                pickCode(made.pick(x, y));
                return true;
            }
        });
        dolls.add(made);
        return made;
    }

    /**
     * The record sheet's dolls: the front armor at the left, the rear armor and structure stacked at its right in the
     * same height, each under its caption.
     */
    private Table sheetDolls() {
        float captionGap = space("xs");
        Label frontCaption = kit.caption(Messages.getString("GpuBoard.hud.unit.frontArmor"), true);
        float caption = frontCaption.getPrefHeight() + captionGap;
        float[] front = geometry.bounds();
        float[] rear = rearGeometry.bounds();
        float[] inside = insideGeometry.bounds();
        float frontWidth = SHEET_HEIGHT * front[2] / front[3];
        float stackGap = space("lg");
        // the stack's two captions and its gap less the front's caption, shared by the two dolls in proportion
        float narrow = (SHEET_HEIGHT - caption - stackGap) / (rear[3] / rear[2] + inside[3] / inside[2]);
        Table frontColumn = new Table();
        frontColumn.top().left();
        frontColumn.add(frontCaption).left().padBottom(captionGap).row();
        frontColumn.add(doll).size(frontWidth, SHEET_HEIGHT).row();
        Table stack = new Table();
        stack.top().left();
        stack.add(kit.caption(Messages.getString("GpuBoard.hud.unit.rearArmor"), true)).left().padBottom(captionGap)
              .row();
        stack.add(rearDoll).size(narrow, narrow * rear[3] / rear[2]).row();
        stack.add(kit.caption(Messages.getString("GpuBoard.hud.common.structure"), true)).left().padTop(stackGap)
              .padBottom(captionGap).row();
        stack.add(insideDoll).size(narrow, narrow * inside[3] / inside[2]).row();
        Table sheet = new Table();
        sheet.top().left();
        sheet.add(frontColumn).top().padRight(space("lg"));
        sheet.add(stack).top();
        return sheet;
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
        for (int index = 0; index < placeButtons.size(); index++) {
            placeButtons.get(index).pressed(places.get(index) == place);
        }
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
        boolean damage = form.damageEditable();
        int rows = 0;
        for (int index = 0; index < shown.size(); index++) {
            if (row(shown.get(index), "damage-value-" + index, rows == 0, damage)) {
                rows++;
            }
        }
        List<UnitDamageForm.Value> deployment = form.deployment();
        if (chosen == null && !deployment.isEmpty()) {
            // how the unit starts the game, its owner's to set even where its damage is the Game Master's
            Label caption = kit.typeLabel(Messages.getString("Lobby.status.deployment"), "body.strong",
                  "text.primary");
            caption.setName("status-deployment");
            values.add(caption).left().padTop(rows == 0 ? 0 : space("xl")).row();
            for (int index = 0; index < deployment.size(); index++) {
                row(deployment.get(index), "status-deploy-" + index, false, true);
            }
        }
    }

    /**
     * Adds a value's row, with its controls locked unless {@code editable}; false when the value has nothing to show,
     * such as a weapon with no hit boxes.
     */
    private boolean row(UnitDamageForm.Value value, String name, boolean first, boolean editable) {
        if (value.kind() == UnitDamageForm.Kind.BOXES && value.max() <= 0) {
            return false;
        }
        float gap = space("sm");
        Label label = kit.typeLabel(value.label(), "body", "text.secondary");
        label.setEllipsis(true);
        if (value.kind() == UnitDamageForm.Kind.STEPPER) {
            // a bar takes the row's width under its label, as a stat block in a game shows it
            float top = first ? 0 : space("md");
            // an ammunition bin's hit boxes sit on its name's row, its shots in the bar under it
            values.add(line(label, value.hits() == null ? null : boxes(value.hits(), name + "-hits", true, editable)))
                  .growX().padTop(top).row();
            UiHealthBar bar = bar(value, name);
            bar.disabled(value.locked() || !editable);
            values.add(bar).growX().padTop(space("xxs")).row();
            return true;
        }
        values.add(line(label, control(value, name, editable))).growX().padTop(gap).row();
        List<UnitDamageForm.Value> flags = value.flags();
        for (int flag = 0; flag < flags.size(); flag++) {
            // a weapon's state sits under its hit boxes, indented as part of it
            UnitDamageForm.Value state = flags.get(flag);
            Label caption = kit.typeLabel(state.label(), "small", "text.secondary");
            caption.setEllipsis(true);
            Actor toggle = control(state, name + "-flag-" + flag, editable);
            if (state.tip() != null) {
                kit.tip(toggle, true).getActor().setText(state.tip());
            }
            values.add(line(caption, toggle)).growX().padLeft(space("lg")).padTop(space("xxs")).row();
        }
        return true;
    }

    /**
     * A row of its own: the label taking what is left, the control at its right end. Each row lays out alone, so every
     * control ends at the pane's edge whatever the rows round it hold.
     */
    private Table line(Label label, Actor control) {
        Table line = new Table();
        line.add(label).left().growX().minWidth(0);
        if (control != null) {
            line.add(control).right().padLeft(space("lg"));
        }
        return line;
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

    private Actor control(UnitDamageForm.Value value, String name, boolean editable) {
        if (value.kind() == UnitDamageForm.Kind.CHOICE) {
            // a short ordered run, such as the rounds a unit may deploy in: stepped through, as a game's menu does
            UiStepper selector = kit.selector(value.choices(), value.value());
            selector.setName(name);
            selector.down().setDisabled(!editable || selector.value() == 0);
            selector.up().setDisabled(!editable || selector.value() == value.choices().size() - 1);
            UiKit.onChange(selector, () -> value.value(selector.value()));
            return selector;
        }
        if (value.kind() == UnitDamageForm.Kind.TOGGLE) {
            UiChoice toggle = kit.toggleSwitch("", value.on());
            toggle.setName(name);
            toggle.setDisabled(!editable);
            UiKit.onChange(toggle, () -> {
                value.value(toggle.isChecked() ? 1 : 0);
                // a limb brought back fills its armor and structure, which the rebuilt rows show
                refresh();
            });
            return toggle;
        }
        return boxes(value, name, false, editable);
    }

    /** Hit boxes; {@code rebuild} when other rows follow them, such as a bin's shots that a hit empties. */
    private Table boxes(UnitDamageForm.Value value, String name, boolean rebuild, boolean editable) {
        Table boxes = new Table();
        List<UiChoice> checks = new ArrayList<>();
        for (int box = 0; box < value.max(); box++) {
            UiChoice check = kit.check("", box < value.value());
            check.setName(name + "-" + box);
            check.setDisabled(!editable);
            int at = box;
            UiKit.onChange(check, () -> {
                // a click on a hit box past the hits hits up to it; on the last hit, clears that one
                value.value(check.isChecked() ? at + 1 : at);
                if (rebuild) {
                    refresh();
                    return;
                }
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
        Map<String, Cell> rear = new HashMap<>();
        Map<String, Cell> structure = new HashMap<>();
        // a record sheet's front doll shows the armor alone, as the structure has its own doll; a lone doll says a
        // location stripped of armor shows its structure
        View armorView = insideDoll == null ? View.CARD : View.ARMOR;
        for (UnitDamageForm.Place place : places) {
            UnitDamageForm.Value internal = place.internal();
            UnitDamageForm.Value front = place.armor();
            UnitDamageForm.Value back = place.rear();
            boolean destroyed = internal != null && internal.value() <= 0;
            boolean crit = place.values().stream().anyMatch(value -> value.kind() == UnitDamageForm.Kind.BOXES
                  && value.value() > 0 || value.locked());
            int remaining = internal == null ? 1 : internal.value();
            if (front != null) {
                armor.put(place.abbr(), GpuPaperdoll.cell(front.value(), front.max(), destroyed, remaining, crit,
                      armorView).with(false, Math.max(0, front.original() - front.value())));
            } else if (internal != null) {
                armor.put(place.abbr(), GpuPaperdoll.cell(internal.value(), internal.max(), destroyed,
                      internal.value(), crit, View.CARD).with(false, Math.max(0, internal.original()
                      - internal.value())));
            }
            if (back != null) {
                rear.put(place.abbr(), GpuPaperdoll.cell(back.value(), back.max(), destroyed, remaining, false,
                      View.ARMOR).with(false, Math.max(0, back.original() - back.value())));
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
        if (insideDoll == null) {
            doll.show(geometry, armor, structure);
        } else {
            doll.show(geometry, armor, Map.of());
            rearDoll.show(rearGeometry, rear, Map.of());
            insideDoll.show(insideGeometry, Map.of(), structure);
        }
        marks();
    }

    private void marks() {
        String picked = chosen == null ? "" : chosen.abbr();
        for (GpuPaperdoll each : dolls) {
            each.marks(hovered, picked, "");
        }
    }

    private static float space(String token) {
        return DesignTokens.shipped().space().get(token);
    }

    private static float size(String token) {
        return DesignTokens.shipped().size().get(token);
    }
}
