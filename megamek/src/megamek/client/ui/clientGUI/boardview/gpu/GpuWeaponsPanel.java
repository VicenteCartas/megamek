/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.percent;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.shown;
import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Attack;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Target;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.WeaponRow;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFlow;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiList;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;

/**
 * Weapons panel in the right column during weapon declaration (C.1 G10; r1 3.12, r2 5, plan A.8 H8-H20, H39): the
 * target pills in letter order, the assign line, one row per weapon of the acting unit with its roll and slot button,
 * and the heat if the queued weapons fire. A weapon's name selects and arms it, a pill focuses its target (a unit, a
 * hex, a building or a minefield), and the slots assign, remove or retarget; every order goes through the fire orders.
 * The fixed footer's Alpha Strike assigns all available weapons to the focus. On another player's firing turn it
 * shows the own focus unit's draft read-only (H33).
 */
final class GpuWeaponsPanel implements GpuHud.Component {
    private static final String SEPARATOR = " · ";
    /** The body's line height (proto3.css: 13px/1.35), which the panel's text lines keep at their own sizes. */
    private static final float LINE = 1.35f;
    /** The line box of the ammunition select (.amsel) in a row, measured in shot 05. */
    private static final float SELECT_LINE = 15.6f;
    /** The assign line (.assign): 11.5-unit text. */
    private static final float ASSIGN_SIZE = 11.5f;
    /** A dead location's rows (.wrow.dead). */
    private static final float DEAD_ALPHA = .45f;
    /** The armed row's ring outside its border (.wrow.armed: box-shadow 0 0 0 3px, mint at 25 %). */
    private static final float GLOW = 3;
    /** The margin above each row (.wrow: margin 6 10 0), which a row scrolled into view shows too. */
    private static final float ROW_GAP = 6;
    // The focused pill's text (.pill.on), and a focused enemy without attacks (.pill.new.on): the focused fill in a
    // dashed white border, with its "+" on grey.
    private static final Color ON_INK = Color.valueOf("17201D");
    private static final Color ON_FILL = Color.valueOf("E2EDE6");
    private static final Color NEW_LETTER = Color.valueOf("555555");

    /**
     * What the panel shows of the orders, so that the badges, the hover roll and the arcs, which change often, rebuild
     * nothing. {@code names} are the presented names of the actor and of the units the attacks target.
     */
    private record View(boolean editable, int actorId, GpuFireOrders.Focus focus, int selected,
          List<WeaponRow> weapons, List<Target> targets, List<Attack> attacks, GpuFireOrders.Heat heat, int armed,
          Map<Integer, String> names) {
        boolean sortable() {
            return editable && weapons.size() > 1;
        }
    }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final Table root;
    private final Label count;
    private final HorizontalGroup pills = new HorizontalGroup();
    private final Container<HorizontalGroup> pillBox;
    private final Cell<Actor> pillCell;
    private final UiFlow assign;
    private final Container<UiFlow> assignBox;
    private final Cell<Actor> assignCell;
    private final UiList list;
    private final ScrollPane scroll;
    private final Table heatSection = new Table();
    private final Cell<Actor> heatCell;
    private final Label heatLine;
    private final GpuHudKit.HeatBar heatBar;
    private final Cell<GpuHudKit.HeatBar> barCell;
    private final Label heatStatus;
    private final Table footer = new Table();
    private final Cell<Actor> footerCell;
    private final UiButton alphaStrike;
    private final Drawable rowPlain;
    private final Drawable rowSelected;
    private final Drawable pillPlain;
    private final Drawable pillOn;
    private final Drawable pillNew;
    private final Drawable newLetter;
    private final Drawable glow;
    private final TextButton.TextButtonStyle slotStyle;
    /** The weapon rows by equipment number, for the scroll into view. */
    private final Map<Integer, Table> rows = new HashMap<>();
    private GpuFireOrders.Snapshot fire = GpuFireOrders.Snapshot.EMPTY;
    private View shown;
    private int actorId = Entity.NONE;
    private int selected = -1;
    /** The orders in which Esc deselected their weapon; until the next ones arrive, Esc goes on down the chain. */
    private GpuFireOrders.Snapshot deselected;

    /** {@code menu} opens this component's unit menus and select lists (C13, SelectField). */
    GpuWeaponsPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu) {
        this.kit = kit;
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.menu = menu;
        Texture white = ui.skin.get("white", Texture.class);
        rowPlain = ui.skin.getDrawable("row");
        rowSelected = ui.skin.getDrawable("row-weapon-selected");
        pillPlain = ui.skin.getDrawable("pill");
        pillOn = ui.skin.getDrawable("pill-on");
        UiTheme.EdgeBox dashed = UiTheme.pad(new UiTheme.EdgeBox(white, ON_FILL, Color.WHITE, 1, 1, 1, 1).dashed(3),
              3, 8);
        dashed.setLeftWidth(3);
        pillNew = dashed;
        newLetter = ui.skin.newDrawable("letter-primary", UiTheme.tint(NEW_LETTER, UiTheme.MAIN, 1));
        glow = new UiTheme.EdgeBox(white, null, UiTheme.alpha(UiTheme.MINT, .25f), GLOW, GLOW, GLOW, GLOW);
        // The slot (.slot: 700 13 condensed); an assigned one stays filled under the pointer, as .b.slot.on comes
        // after the hover rule.
        slotStyle = new TextButton.TextButtonStyle(ui.skin.get("hud", TextButton.TextButtonStyle.class));
        slotStyle.font = ui.skin.getFont("hud-name");
        slotStyle.checkedOver = slotStyle.checked;
        slotStyle.checkedDown = slotStyle.checked;
        slotStyle.checkedOverFontColor = UiTheme.FILL_INK;
        slotStyle.checkedDownFontColor = UiTheme.FILL_INK;

        root = ui.panel();
        root.setName("weapons-panel");
        count = ui.label("", "hud-medium", 12, UiTheme.MUTED);
        count.setName("weapons-count");
        Table header = ui.header(text("GpuBoard.hud.common.weapons"), null, count);
        header.setName("weapons-header");
        root.add(header).growX().row();
        // .pills: wrapping, 6 apart, padding 0 14 6
        pills.setName("weapons-pills");
        pills.wrap().rowLeft().space(6).wrapSpace(6);
        pillBox = new Container<>(pills).fillX().pad(0, 14, 6, 14);
        pillCell = root.add((Actor) null).growX();
        root.row();
        // .assign: padding 0 14 8 above a hairline
        assign = new UiFlow(ui, ASSIGN_SIZE, ASSIGN_SIZE * LINE);
        assign.setName("weapons-assign");
        assignBox = new Container<>(assign).fillX().pad(0, 14, 8, 14);
        assignCell = root.add((Actor) null).growX();
        root.row();
        root.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        list = new UiList(ui);
        scroll = ui.scrollList(list);
        scroll.setName("weapons-list");
        root.add(scroll).grow().minHeight(0).row();

        // .heatfc: a hairline, then padding 9 14 11
        Table heat = new Table();
        heat.pad(9, 14, 11, 14);
        heat.add(ui.label(UiTheme.upper(text("GpuBoard.hud.weapons.heatIfFired")), "hud-caption", 10.5f,
              UiTheme.MUTED)).left().expandX();
        // .heatfc .l span: 600 12 condensed; white in its style, as the line's color, ink or amber, multiplies it.
        heatLine = ui.label("", "hud-mini", 12, Color.WHITE);
        heatLine.setName("weapons-heat-line");
        heat.add(heatLine).right().row();
        heatBar = kit.heatBar(true);
        barCell = heat.add(heatBar).colspan(2).growX();
        heat.row();
        heatStatus = ui.label("", "hud-body", 11.5f, UiTheme.ACCENT);
        heatStatus.setName("weapons-heat-status");
        heatStatus.setWrap(true);
        heat.add(heatStatus).colspan(2).growX().minWidth(0).left();
        heatSection.setName("weapons-heat");
        heatSection.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        heatSection.add(heat).growX();
        heatCell = footer.add((Actor) null).growX();
        footer.row();
        alphaStrike = ui.button("hud", null, text("GpuBoard.hud.weapons.alphaStrike"), null);
        alphaStrike.setName("weapons-alpha-strike");
        ui.tip(alphaStrike).getActor().setText(text("GpuBoard.hud.weapons.alphaStrikeTip"));
        onChange(alphaStrike, () -> {
            state.armedWeapon = -1;
            source.fire().assignAll(fire.focus().key());
        });
        footer.setName("weapons-footer");
        ui.footer(footer).add(alphaStrike).growX();
        footerCell = root.add((Actor) null).growX();
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The fixed controls and at least one weapon row must fit below the minimap. */
    float minimumHeight() {
        // The dragged row is temporarily on the stage, but still reserves its place in the panel.
        Table first = shown != null && shown.editable() && !shown.weapons().isEmpty()
              ? rows.get(shown.weapons().getFirst().eqNum())
              : list.hasChildren() ? (Table) list.getChildren().first() : null;
        return root.getMinHeight() + (first == null ? 0 : first.getPrefHeight());
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuFireOrders.Snapshot next = inputs.frame().panels().fire();
        boolean visible = next.active() && !state.logOpen() && !state.overview;
        if (next.actorId() != actorId || next.editable() != fire.editable() || !visible) {
            // A drag belongs to one unit's editable declaration; abandoning it never posts a move.
            list.clearChildren();
            rows.clear();
            shown = null;
        }
        if (next.actorId() != actorId || !next.editable() || row(next.weapons(), state.armedWeapon) == null) {
            // A weapon stays armed only within its own unit's declaration (H16).
            state.armedWeapon = -1;
        }
        actorId = next.actorId();
        fire = next;
        if (!visible) {
            return;
        }
        if (!list.busy()) {
            View view = new View(next.editable(), next.actorId(), next.focus(), next.selectedWeapon(), next.weapons(),
                  next.targets(), next.attacks(), next.heat(), state.armedWeapon, names(next));
            // H39: a newly selected row scrolls into view; a selected row in view stays there when rebuilt.
            boolean scrollTo = next.selectedWeapon() != selected;
            if (!view.equals(shown)) {
                scrollTo |= selectedInView();
                shown = view;
                rebuild();
            }
            selected = next.selectedWeapon();
            if (scrollTo && selected >= 0) {
                // once the HUD placed this frame's panels, before drawing
                root.addAction(Actions.run(this::scrollToSelected));
            }
        }
        // Laid out at the column's width now, so its wrapped pills and lines give the HUD its height this frame.
        root.setWidth(inputs.metrics().right());
        root.validate();
    }

    /** A dragged row goes home before Esc closes any of the HUD's panels. */
    boolean cancelDrag() {
        return list.cancel();
    }

    /**
     * One Esc step (C.4): disarms the armed weapon, else deselects the selected one, which hides the solution card and
     * the arc; true when it did either. It never clears orders (H35).
     */
    boolean cancel() {
        if (state.armedWeapon >= 0) {
            state.armedWeapon = -1;
            return true;
        }
        if (fire.editable() && fire.selectedWeapon() >= 0 && fire != deselected) {
            deselected = fire;
            source.fire().selectWeapon(-1);
            return true;
        }
        return false;
    }

    /** Disarms and deselects the weapon, which hides its solution card and arc (the card's close button, H16). */
    static void deselect(GpuBoardSource source, GpuHudState state) {
        state.armedWeapon = -1;
        source.fire().selectWeapon(-1);
    }

    /**
     * A weapon row's sub-line (H11), "{loc} · {dmg} dmg · {heat} heat", which the weapon menu's subtitle repeats
     * (H36).
     */
    static String detail(WeaponRow weapon) {
        return text("GpuBoard.hud.weapons.detail", weapon.location(),
              text("GpuBoard.hud.weapons.damage", weapon.damage()), weapon.heat());
    }

    /** The presented names of the actor and of the units the attacks target. */
    private Map<Integer, String> names(GpuFireOrders.Snapshot orders) {
        Set<Integer> named = new HashSet<>(List.of(orders.actorId()));
        orders.attacks().forEach(attack -> named.add(attack.target().unitId()));
        Map<Integer, String> names = new HashMap<>();
        for (UnitStatus unit : state.presentedUnits()) {
            if (named.contains(unit.id())) {
                names.put(unit.id(), unit.name());
            }
        }
        return names;
    }

    private void rebuild() {
        View view = shown;
        rows.clear();
        list.clearChildren();
        pills.clearChildren();
        assign.clearChildren();
        if (view.editable()) {
            count.setText(text("GpuBoard.hud.weapons.queued", view.attacks().size(), view.weapons().size()));
            pillCell.setActor(pillBox);
            assignCell.setActor(assignBox);
            for (TargetKey key : fire.carded()) {
                pills.addActor(pill(key, target(view.targets(), key), view));
            }
            if (!pills.hasChildren()) {
                pills.addActor(ui.label(text("GpuBoard.hud.weapons.noTargets"), "hud-body", 13, UiTheme.MUTED));
            }
            showAssign(view);
            for (WeaponRow weapon : view.weapons()) {
                Table entry = entry(weaponRow(weapon, view));
                rows.put(weapon.eqNum(), entry);
                list.add(entry, view.sortable() ? entry : null);
            }
            list.reorderable((from, to) -> source.fire().moveWeapon(view.actorId(), view.weapons().get(from).eqNum(),
                  view.weapons().get(to).eqNum()));
        } else {
            // H33: another player's turn shows the focus unit's draft, which waits for its own turn
            count.setText(text("GpuBoard.hud.weapons.drafted", view.names().getOrDefault(view.actorId(), ""),
                  view.attacks().size()));
            pillCell.setActor(null);
            assignCell.setActor(null);
            for (Attack attack : view.attacks()) {
                list.add(entry(draftRow(attack, view)), null);
            }
        }
        showHeat(view.editable() ? view.heat() : null);
        footerCell.setActor(view.editable() ? footer : null);
        TargetKey focus = view.focus().key();
        alphaStrike.setDisabled(!view.editable() || focus.equals(TargetKey.NONE)
              || view.weapons().stream().noneMatch(weapon -> {
                  Attack attack = attack(view.attacks(), weapon.eqNum());
                  return attack != null ? !attack.target().equals(focus)
                        : weapon.usable() && weapon.target().equals(focus) && weapon.reason().isEmpty();
              }));
    }

    /** The row and its spacing travel together while dragging. */
    private static Table entry(Table row) {
        Table entry = new Table();
        entry.add(row).growX().minWidth(0).pad(ROW_GAP, 10, 0, 10);
        return entry;
    }

    /**
     * A target pill (.pill): its letter, white for the primary, the name and the primary's star; the focus's pill is
     * light (.on), and a focus without attacks gets a dashed one with "+" (.new). A click focuses its target and
     * assigns the armed weapon to it (H15, H16); a right click opens a unit's menu (C13).
     */
    private Table pill(TargetKey key, Target target, View view) {
        boolean focused = key.equals(view.focus().key());
        Table pill = new Table();
        pill.setName("weapons-pill-" + key.id());
        pill.setBackground(target == null ? pillNew : focused ? pillOn : pillPlain);
        // .pill: padding 3 8 3 3 inside its 1-unit border
        pill.pad(4, 4, 4, 9);
        // A target's letter in its colour; the focused enemy without attacks shows the new assignment's "+".
        pill.add(target == null ? kit.square("+", Color.WHITE, newLetter, false) : kit.letter(target.letter(), false));
        String name = name(view, key);
        Label label = ui.label(name, "hud-medium", 11.5f, focused ? ON_INK : UiTheme.TEXT);
        // Keep short pills at their natural width; let the group shrink long ones and wrap their names.
        float nameWidth = label.getPrefWidth();
        label.setWrap(true);
        pill.add(label).growX().minWidth(0).prefWidth(nameWidth).padLeft(6);
        if (target != null && target.primary()) {
            pill.add(ui.icon("star", 11, UiTheme.AMBER)).padLeft(6);
        }
        pill.setTouchable(Touchable.enabled);
        pill.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                GpuContextMenu.focusTarget(source, state, key);
            }
        });
        if (key.unitId() != Entity.NONE) {
            pill.addListener(GpuHudKit.UnitRow.menuOpener(menu, key.unitId(), state::presented));
        }
        if (target == null) {
            ui.tip(pill).getActor().setText(text("GpuBoard.hud.weapons.assignTo", name));
        }
        return pill;
    }

    /**
     * The assign line (H10): the target a "+" assigns to, or how to begin. None while a weapon is armed, which its row
     * already shows selected (the user's decision of 2026-10-03).
     */
    private void showAssign(View view) {
        if (row(view.weapons(), view.armed()) != null) {
            assignCell.setActor(null);
        } else if (!view.focus().key().equals(TargetKey.NONE)) {
            Target target = target(view.targets(), view.focus().key());
            assign.run(target == null ? text("GpuBoard.hud.weapons.assignLineFocus", view.focus().name())
                  : text("GpuBoard.hud.weapons.assignLine", target.letter(), target.name()), "hud-small",
                  UiTheme.MUTED);
        } else {
            assign.run(text("GpuBoard.hud.weapons.selectHint"), "hud-small", UiTheme.MUTED);
        }
    }

    /**
     * A weapon's row (H11): its name block selects and arms it (H16) and shows its tooltip, its slot assigns, removes
     * or retargets (H18), and a right click opens its menu (H36).
     */
    private Table weaponRow(WeaponRow weapon, View view) {
        int eqNum = weapon.eqNum();
        Actor ammo = weapon.ammo().isEmpty() ? null
              : ammo(ui, menu, source, weapon, fire.editable(), UiTheme.ACCENT);
        if (ammo != null) {
            ammo.setName("weapons-ammo-" + eqNum);
        }
        Table name = nameBlock(weapon.name(), detail(weapon), weapon.reason(), ammo);
        name.setName("weapons-name-" + eqNum);
        name.setTouchable(Touchable.enabled);
        name.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                // The ammunition select is a control of its own (the prototype skips SELECT elements).
                if (event.getTarget().firstAscendant(Button.class) == null) {
                    toggle(eqNum);
                }
            }
        });
        ui.tip(name).getActor().setText(text(view.sortable() ? "GpuBoard.hud.weapons.nameSortTip"
              : "GpuBoard.hud.weapons.nameTip"));
        Table row = row(name, roll(!weapon.target().equals(TargetKey.NONE), weapon.value(), weapon.odds()),
              slot(weapon, attack(view.attacks(), eqNum), view), view.selected() == eqNum, view.armed() == eqNum);
        row.setName("weapons-row-" + eqNum);
        if (weapon.locationDestroyed()) {
            row.getColor().a = DEAD_ALPHA;
        }
        row.addListener(new ClickListener(Input.Buttons.RIGHT) {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                menu.weapon(eqNum, fire.selectedWeapon() == eqNum, () -> toggle(eqNum), event.getStageX(),
                      event.getStageY());
            }
        });
        return row;
    }

    /**
     * A draft's attack, read-only (H33): the weapon, its location and the unit it targets (a hex, a building or a
     * minefield has no presented name), its bin and its own roll.
     */
    private Table draftRow(Attack attack, View view) {
        int unit = attack.target().unitId();
        Table name = nameBlock(attack.weapon(), unit != Entity.NONE ? text("GpuBoard.hud.weapons.draftLine",
              attack.location(), view.names().getOrDefault(unit, "")) : attack.location(),
              attack.value() == TargetRoll.IMPOSSIBLE ? attack.detail() : "",
              attack.ammo().isEmpty() ? null : ammoText(ui, attack.ammo(), UiTheme.ACCENT));
        return row(name, roll(true, attack.value(), attack.odds()), null, false, false);
    }

    /**
     * A row (.wrow: padding 7 8 7 10 inside a 1-unit border, so 8 11 8 9 to the content, which the selected row's
     * 2-unit white border keeps): the name block, the roll column and the slot; an armed row glows (r1 3.12).
     */
    private Table row(Table name, Actor roll, Actor slot, boolean selected, boolean armed) {
        Table row = new Table() {
            @Override
            protected void drawBackground(Batch batch, float parentAlpha, float x, float y) {
                if (armed) {
                    Color color = getColor();
                    batch.setColor(color.r, color.g, color.b, color.a * parentAlpha);
                    glow.draw(batch, x - GLOW, y - GLOW, getWidth() + 2 * GLOW, getHeight() + 2 * GLOW);
                }
                super.drawBackground(batch, parentAlpha, x, y);
            }
        };
        row.setBackground(selected ? rowSelected : rowPlain);
        row.pad(8, 11, 8, 9);
        if (shown.sortable()) {
            row.add(ui.icon("grip", 12, UiTheme.MUTED)).size(12, 16).padRight(6);
        }
        row.add(name).growX().minWidth(0);
        row.add(roll).minWidth(40).right().padLeft(10);
        if (slot != null) {
            row.add(slot).size(30).padLeft(10);
        }
        return row;
    }

    /**
     * The name block (.wn): the upper-case name, the muted detail with a coral reason that ends in an ellipsis when it
     * runs out of room, and the ammunition line, each on the CSS line box of its text (the body's 1.35 line height; a
     * select's box is 15.6 units, as measured in shot 05).
     */
    private Table nameBlock(String name, String detail, String reason, Actor ammo) {
        Table block = new Table();
        block.left().defaults().left();
        Label title = ui.label(UiTheme.upper(name), "hud-name", 13.5f, Color.WHITE);
        title.setEllipsis(true);
        block.add(title).growX().minWidth(0).height(13 * LINE).row();
        Table line = new Table();
        line.left();
        line.add(ui.label(reason.isEmpty() ? detail : detail + SEPARATOR, "hud-small", 11, UiTheme.MUTED));
        if (!reason.isEmpty()) {
            Label why = ui.label(reason, "hud-small", 11, UiTheme.CORAL);
            why.setEllipsis(true);
            line.add(why).growX().minWidth(0);
        }
        block.add(line).growX().minWidth(0).height(11 * LINE).padTop(1).row();
        if (ammo != null) {
            block.add(ammo).minWidth(0).height(ammo instanceof Button ? SELECT_LINE : 11 * LINE).padTop(2);
        }
        return block;
    }

    /**
     * The ammunition line (.am): the loaded bin's Unit Display entry in {@code color}, and a select of the bins the
     * weapon may load (.amsel) when there are several and the orders are {@code editable}; a pick loads its bin by
     * carrier and number (H30). The target cards' attack rows show the same line.
     */
    static Actor ammo(UiKit ui, GpuContextMenu menu, GpuBoardSource source, WeaponRow weapon, boolean editable,
          Color color) {
        List<String> labels = weapon.ammo().stream().map(GpuUnitRecord.AmmoChoice::label).toList();
        int loaded = weapon.loadedAmmo();
        String face = loaded >= 0 ? labels.get(loaded) : "—";
        if (labels.size() < 2 || !editable) {
            return ammoText(ui, face, color);
        }
        UiButton select = ui.select(face, true);
        onChange(select, () -> menu.list(select, labels, loaded,
              choice -> source.fire().setAmmo(weapon.eqNum(), weapon.ammo().get(choice))));
        return select;
    }

    /** An ammunition line as 11-unit text in {@code color}, ending in an ellipsis when it runs out of room. */
    static Label ammoText(UiKit ui, String text, Color color) {
        Label label = ui.label(text, "hud-small", 11, color);
        label.setEllipsis(true);
        return label;
    }

    /** The roll column (.tn): the target number in mint over its odds, or a dim dash without a roll on a target. */
    private Table roll(boolean target, int value, double odds) {
        Table tn = new Table();
        tn.right().defaults().right();
        if (target && GpuSolutionCard.possible(value)) {
            tn.add(ui.label(text("GpuBoard.hud.common.targetNumber", shown(value)), "hud-heading", 20,
                  odds > 0 ? UiTheme.MINT : UiTheme.DISABLED)).height(20).row();
            tn.add(ui.label(percent(odds), "hud-medium", 10.5f, UiTheme.MUTED)).padTop(3);
        } else {
            tn.add(ui.label("—", "hud-medium", 10.5f, UiTheme.DISABLED));
        }
        return tn;
    }

    /**
     * The slot (H18, .slot): the assigned target's letter, filled, which removes the attack, or retargets it to the
     * focus; "+", which assigns the weapon to the focus; or a dim dash with the reason as its tooltip.
     */
    private UiButton slot(WeaponRow weapon, Attack attack, View view) {
        int eqNum = weapon.eqNum();
        TargetKey focus = view.focus().key();
        UiButton slot;
        String tip;
        if (attack != null) {
            Target target = target(view.targets(), attack.target());
            slot = slotButton(target == null ? "—" : String.valueOf(target.letter()));
            if (target != null) {
                slot.setStyle(targetSlot(target.letter()));
            }
            slot.pressed(true);
            boolean remove = focus.equals(TargetKey.NONE) || focus.equals(attack.target());
            tip = remove ? text("GpuBoard.hud.context.removeThisAttack")
                  : text("GpuBoard.hud.weapons.retargetTo", view.focus().name());
            onChange(slot, () -> {
                if (remove) {
                    source.fire().remove(eqNum);
                } else {
                    state.armedWeapon = -1;
                    source.fire().retarget(eqNum, focus);
                }
            });
        } else if (!focus.equals(TargetKey.NONE) && weapon.target().equals(focus) && weapon.usable()
              && weapon.reason().isEmpty()) {
            slot = slotButton("+");
            tip = text("GpuBoard.hud.weapons.assignTo", view.focus().name());
            onChange(slot, () -> {
                state.armedWeapon = -1;
                source.fire().assign(eqNum, focus);
            });
        } else {
            slot = slotButton("—");
            slot.setDisabled(true);
            tip = weapon.reason().isEmpty() ? text("GpuBoard.hud.weapons.noTarget") : weapon.reason();
        }
        slot.setName("weapons-slot-" + eqNum);
        ui.tip(slot).getActor().setText(tip);
        return slot;
    }

    /** An assigned slot's style: filled in its target's colour, as the target's letter square (MekBay's palette). */
    private TextButton.TextButtonStyle targetSlot(char letter) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(slotStyle);
        style.checked = ui.skin.newDrawable(slotStyle.checked,
              UiTheme.tint(GpuHudKit.targetColour(letter), UiTheme.FILL, 1));
        style.checkedOver = style.checked;
        style.checkedDown = style.checked;
        return style;
    }

    private UiButton slotButton(String text) {
        UiButton slot = ui.button("hud", null, text, null);
        slot.setStyle(slotStyle);
        UiKit.size(slot.getLabel(), "hud-name", 13);
        slot.pad(0);
        return slot;
    }

    /**
     * The heat if fired (H20; null hides it): now, after the queued weapons and at the end of the turn, amber when
     * the end of the turn brings heat effects; a segment per point of the heat scale with its ticks; the effects.
     */
    private void showHeat(GpuFireOrders.Heat heat) {
        heatCell.setActor(heat == null ? null : heatSection);
        if (heat == null) {
            return;
        }
        heatLine.setText(text("GpuBoard.hud.weapons.heatLine", heat.current(), heat.after(), heat.sinks(),
              heat.end()));
        heatLine.setColor(heat.effectsAtEnd().isEmpty() ? UiTheme.TEXT : UiTheme.AMBER);
        boolean scale = heat.scale() > 0;
        heatBar.set(heat.current(), heat.after(), heat.scale(), heat.ticks());
        // .heatfc .segs: 7 tall, margin 7 0 6; a unit without a heat scale has no segments.
        barCell.setActor(scale ? heatBar : null).height(scale ? 7 : 0).pad(scale ? 7 : 4, 0, scale ? 6 : 0, 0);
        heatStatus.setText(heat.effectsAtEnd().isEmpty() ? text("GpuBoard.hud.weapons.noHeatEffects")
              : text("GpuBoard.hud.weapons.heatEffects", heat.effectsAtEnd()));
    }

    /** H16: a name selects and arms its weapon; the selected weapon's name deselects and disarms it. */
    private void toggle(int eqNum) {
        if (fire.selectedWeapon() == eqNum) {
            deselect(source, state);
        } else {
            state.armedWeapon = eqNum;
            source.fire().selectWeapon(eqNum);
        }
    }

    /**
     * H39: the selected weapon's row and the margin above it come into view, scrolling as little as it can. The
     * rectangle's y is its top edge, as ScrollPane.scrollTo measures it (libGDX 1.14.2).
     */
    private void scrollToSelected() {
        Table row = rows.get(selected);
        if (row != null) {
            root.validate();
            scroll.scrollTo(row.getX(), row.getTop(), row.getWidth(), row.getHeight());
        }
    }

    /** Whether the selected weapon's row lies wholly in the list's view, where its scroll ends. */
    private boolean selectedInView() {
        Table row = rows.get(selected);
        float top = list.getHeight() - scroll.getScrollY();
        return row != null && row.getY() >= top - scroll.getScrollHeight() - .5f && row.getTop() <= top + .5f;
    }

    /** A carded target's name as its pill shows it: its lettered target's, else the focus's. */
    private static String name(View view, TargetKey key) {
        Target target = target(view.targets(), key);
        return target != null ? target.name() : view.focus().name();
    }

    private static Target target(List<Target> targets, TargetKey key) {
        return targets.stream().filter(target -> target.key().equals(key)).findFirst().orElse(null);
    }

    private static Attack attack(List<Attack> attacks, int eqNum) {
        return attacks.stream().filter(attack -> attack.eqNum() == eqNum).findFirst().orElse(null);
    }

    private static WeaponRow row(List<WeaponRow> weapons, int eqNum) {
        return weapons.stream().filter(weapon -> weapon.eqNum() == eqNum).findFirst().orElse(null);
    }
}
