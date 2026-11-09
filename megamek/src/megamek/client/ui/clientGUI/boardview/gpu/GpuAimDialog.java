/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.AimChoice;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.AimTarget;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.WeaponRow;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.compute.Compute;
import megamek.common.units.Entity;

/**
 * The aimed shot dialog (H36, R4): a modal over the board for one weapon of the acting unit and the unit it aims at
 * ({@link GpuHudState#aiming}), which the aim toggles of the weapons panel and the target cards and the weapon menu
 * open. Its title names the unit ("Aimed shot to ..."), its sub-line the weapon ("with ..."), and it shows the unit's
 * paperdoll enlarged with each location's armor and, under it, the weapon's roll aimed there, as the panels colour a
 * roll, outlined in the wireframe view's green; a location the rules leave out keeps its number in the doll's empty
 * look, outlined faintly, and takes no click. Where the shot strikes a Mek's rear, the record sheet's rear armor doll
 * stands beside the front one and takes the torsos, whose front armor the shot misses. A unit without a paperdoll lists
 * its locations instead. Picking a location aims the weapon there and closes; "No aimed shot", which shows the roll
 * without an aim, removes its aim and closes; Dismiss, Esc and a click beside the dialog close it unchanged. Every
 * change goes through the fire orders, whose choice ({@link GpuFireOrders.Snapshot#aimChoice}) the dialog shows.
 */
final class GpuAimDialog implements GpuHud.Component {
    private static final float WIDTH = 380;
    /** Wider with the rear doll beside the front one: the two and their gap fill its 388-unit body. */
    private static final float REAR_WIDTH = 420;
    /** The doll's box: the card's doll (84-90 by 114-136 units) about two and a half times over. */
    private static final float DOLL_WIDTH = 300;
    private static final float DOLL_HEIGHT = 340;
    private static final float FRONT_BESIDE_REAR = 236;
    private static final float REAR_DOLL_WIDTH = 140;
    private static final float DOLL_GAP = 12;
    private static final float MIN_DOLL_HEIGHT = 160;
    /** The dialog's rows besides the doll and a margin, which a low window keeps by shrinking the doll. */
    private static final float CHROME = 208;
    /** The doll's largest number; the card's is 14. */
    private static final float DOLL_FONT = 20;
    /** The button row's buttons, as the native modal dialog's (.b: min-height 36). */
    private static final float BUTTON_HEIGHT = 36;
    private static final float BUTTON_WIDTH = 88;
    /** The location buttons of a unit without a paperdoll, in this many columns. */
    private static final int LIST_COLUMNS = 2;
    /** The outline of a location a pick may hit: the wireframe view's line green (wireframe.frag). */
    private static final Color PHOSPHOR = new Color(.3f, 1, .45f, 1);
    /** Every other line of the doll: its muted rail with a faint tint of that green. */
    private static final Color PHOSPHOR_FAINT = new Color(UiTheme.RAIL).lerp(PHOSPHOR, .2f);

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuPaperdolls paperdolls;
    private final UiModal layer;
    private final GpuPaperdoll doll;
    /** The rear armor doll, shown while the shot strikes a Mek's rear ({@link #rearShown}). */
    private final GpuPaperdoll rearDoll;
    /** The request the fire orders were last given, null for none. */
    private AimTarget requested;
    /** The choice shown, the window height it was laid out for, and its locations' codes in the rules' order. */
    private AimChoice shown;
    private float shownHeight;
    private List<String> codes = List.of();
    private boolean rearShown;

    GpuAimDialog(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuPaperdolls paperdolls) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.paperdolls = paperdolls;
        layer = new UiModal(ui, this::close);
        layer.setName("aim-backdrop");
        doll = doll("aim-doll");
        rearDoll = doll("aim-rear-doll");
    }

    /** A doll of the dialog, whose locations take the pointer: hovering outlines them, a click aims there. */
    private GpuPaperdoll doll(String name) {
        GpuPaperdoll view = new GpuPaperdoll(ui.skin);
        view.setName(name);
        view.setTouchable(Touchable.enabled);
        view.addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                hover(view, index(view.pick(x, y)));
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(view))) {
                    hover(null, -1);
                }
            }
        });
        view.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                int index = index(view.pick(x, y));
                if (strikes(view, index)) {
                    aim(index);
                }
            }
        });
        return view;
    }

    @Override
    public Actor actor() {
        return layer;
    }

    /**
     * Asks the fire orders for the open dialog's choice and shows it once it arrives; a weapon that left the orders or
     * may no longer aim at its row's unit closes the dialog.
     */
    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        if (state.aiming != null && !aimable(fire, state.aiming)) {
            state.aiming = null;
        }
        AimTarget aim = state.aiming;
        if (!Objects.equals(aim, requested)) {
            requested = aim;
            source.fire().showAim(aim);
        }
        AimChoice choice = fire.aimChoice();
        boolean ready = aim != null && choice != null && choice.aim().equals(aim);
        float height = inputs.metrics().height();
        if (ready && (!choice.equals(shown) || height != shownHeight)) {
            build(choice, height);
        } else if (!ready) {
            shown = null;
        }
        layer.open(ready);
    }

    /**
     * A weapon row's aim toggle (H36), {@code size} units square: the crosshair on the filled face while the weapon's
     * attack is aimed and on the outlined face while it is not, as a slot shows its assignment; a click opens the
     * dialog for the weapon of the declaring unit {@code actorId} and the unit its row shows. {@code open}: the weapon
     * may aim now (it has an attack there, or "+" would assign it there).
     */
    static UiButton toggle(UiKit ui, GpuHudState state, int actorId, WeaponRow weapon, boolean open, float size) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(ui.skin.get("hud",
              TextButton.TextButtonStyle.class));
        // An aimed face stays filled under the pointer, as an assigned slot's does.
        style.checkedOver = style.checked;
        style.checkedDown = style.checked;
        style.checkedOverFontColor = UiTheme.FILL_INK;
        style.checkedDownFontColor = UiTheme.FILL_INK;
        UiButton button = new UiButton(ui, "hud");
        button.setStyle(style);
        button.pad(0);
        float glyph = Math.round(size * .6f);
        UiKit.Icon icon = ui.icon("locate", glyph, Color.WHITE);
        button.icons.add(icon);
        button.add(icon).size(glyph);
        boolean aimed = !weapon.aimedAt().isEmpty();
        button.pressed(aimed);
        button.setDisabled(!open);
        button.setName("aim-" + weapon.eqNum());
        ui.tip(button).getActor().setText(aimed ? text("GpuBoard.hud.aim.aimedTip", weapon.aimedAt())
              : text("GpuBoard.hud.aim.tip"));
        onChange(button, () -> state.aiming = new AimTarget(actorId, weapon.eqNum(), weapon.target()));
        return button;
    }

    /** Whether the orders still let the weapon aim at the unit: its unit declares, and its row shows that unit. */
    private static boolean aimable(GpuFireOrders.Snapshot fire, AimTarget aim) {
        return fire.active() && fire.editable() && fire.actorId() == aim.actorId() && fire.weapons().stream()
              .anyMatch(weapon ->
              weapon.eqNum() == aim.eqNum() && weapon.aimable() && weapon.target().equals(aim.target()));
    }

    /**
     * The dialog for the choice: the header naming the unit with the weapon on its muted sub-line, the doll in a box
     * the window's height allows (or the location buttons) and the buttons.
     */
    private void build(AimChoice choice, float windowHeight) {
        shown = choice;
        shownHeight = windowHeight;
        codes = choice.record().locations().stream().map(GpuUnitRecord.Location::abbr).toList();
        layer.clearChildren();
        Table dialog = ui.panel();
        dialog.setName("aim-dialog");
        dialog.setTouchable(Touchable.enabled);
        Table header = ui.header(text("GpuBoard.hud.aim.title", choice.unit()), null);
        header.row();
        Label weapon = ui.label(text("GpuBoard.hud.aim.weapon", choice.weapon()), "hud-small", 11.5f, UiTheme.MUTED);
        weapon.setName("aim-weapon");
        weapon.setWrap(true);
        header.add(weapon).colspan(header.getColumns()).growX().minWidth(0).left();
        dialog.add(header).growX().row();
        dialog.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
        Table body = new Table();
        body.pad(10, 16, 10, 16);
        body.defaults().left();
        GpuPaperdolls.Doll front = GpuUnitCard.doll(paperdolls, choice.record(), GpuPaperdolls.ARMOR);
        GpuPaperdolls.Doll rear = front == null || !choice.rear() ? null
              : GpuUnitCard.doll(paperdolls, choice.record(), GpuPaperdolls.REAR);
        rearShown = rear != null && choice.record().locations().stream().anyMatch(location -> location.maxRear() > 0);
        if (front != null) {
            Table dolls = new Table();
            doll.maxFont(DOLL_FONT);
            doll.show(front, cells(choice, doll), Map.of(), notes(choice, doll));
            doll.lines(lit(doll), PHOSPHOR, PHOSPHOR_FAINT);
            float height = Math.max(MIN_DOLL_HEIGHT, Math.min(DOLL_HEIGHT, windowHeight - CHROME));
            dolls.add(doll).size(rearShown ? FRONT_BESIDE_REAR : DOLL_WIDTH, height);
            if (rearShown) {
                rearDoll.maxFont(DOLL_FONT);
                rearDoll.show(rear, cells(choice, rearDoll), Map.of(), notes(choice, rearDoll));
                rearDoll.lines(lit(rearDoll), PHOSPHOR, PHOSPHOR_FAINT);
                Table column = new Table();
                column.add(ui.label(UiTheme.upper(text("GpuBoard.hud.aim.rearArmor")), "hud-caption", 10.5f,
                      UiTheme.AMBER)).row();
                column.add(rearDoll).size(REAR_DOLL_WIDTH, REAR_DOLL_WIDTH * rear.bounds()[3] / rear.bounds()[2])
                      .padTop(4);
                dolls.add(column).padLeft(DOLL_GAP);
            }
            body.add(dolls).expandX().center().padTop(10).row();
        } else {
            body.add(list(choice)).growX().padTop(10).row();
        }
        hover(null, -1);
        dialog.add(body).growX().row();
        Table foot = ui.footer(dialog);
        foot.add().expandX();
        UiButton none = ui.button("hud", null, text("GpuBoard.hud.aim.noAim"), null);
        none.setName("aim-none");
        Label unaimed = ui.label(roll(choice.value()), "hud-heading", 14, colour(choice, choice.value()));
        unaimed.setName("aim-none-roll");
        none.add(unaimed).padLeft(8);
        onChange(none, () -> {
            source.fire().aim(choice.aim(), Entity.LOC_NONE);
            close();
        });
        UiButton dismiss = ui.button("hud", null, text("GpuBoard.hud.aim.dismiss"), null);
        dismiss.setName("aim-dismiss");
        onChange(dismiss, this::close);
        foot.add(none).minWidth(BUTTON_WIDTH).height(BUTTON_HEIGHT);
        foot.add(dismiss).minWidth(BUTTON_WIDTH).height(BUTTON_HEIGHT).padLeft(8);
        layer.add(dialog).width(rearShown ? REAR_WIDTH : WIDTH);
    }

    /**
     * A doll's cells: the front doll's are the card's armor cells, the rear doll's each rear-armored location's rear
     * armor in the card's look. A location the weapon may not aim at, or that the other doll takes (a torso while the
     * shot strikes the rear), keeps its number in the "no value" look.
     */
    private Map<String, GpuPaperdoll.Cell> cells(AimChoice choice, GpuPaperdoll view) {
        Map<String, GpuPaperdoll.Cell> front = GpuUnitCard.frontCells(choice.record(), GpuPaperdoll.View.CARD,
              GpuUnitCard.Deltas.NONE);
        Map<String, GpuPaperdoll.Cell> cells = new HashMap<>(view == doll ? front : Map.of());
        List<GpuUnitRecord.Location> locations = choice.record().locations();
        for (int index = 0; index < Math.min(locations.size(), choice.enabled().size()); index++) {
            GpuUnitRecord.Location location = locations.get(index);
            GpuPaperdoll.Cell cell = front.get(location.abbr());
            if (cell == null || view == rearDoll && location.maxRear() <= 0) {
                continue;
            }
            if (view == rearDoll) {
                cell = GpuPaperdoll.cell(location.rear(), location.maxRear(), location.destroyed(),
                      location.internal(), cell.crit(), GpuPaperdoll.View.CARD);
            }
            if (!hits(view, index)) {
                cell = new GpuPaperdoll.Cell(GpuPaperdoll.DamageTier.NONE, cell.text(), false, false, 0);
            }
            cells.put(location.abbr(), cell);
        }
        return cells;
    }

    /**
     * Under the number of each location the doll takes: the weapon's roll aimed there, or a dim dash where it may not
     * aim (as a roll column without a roll).
     */
    private Map<String, GpuPaperdoll.Note> notes(AimChoice choice, GpuPaperdoll view) {
        Map<String, GpuPaperdoll.Note> notes = new HashMap<>();
        List<GpuUnitRecord.Location> locations = choice.record().locations();
        for (int index = 0; index < Math.min(locations.size(), choice.enabled().size()); index++) {
            if (strikes(view, index)) {
                notes.put(locations.get(index).abbr(), choice.enabled().get(index)
                      ? new GpuPaperdoll.Note(roll(choice.rolls().get(index)), colour(choice,
                            choice.rolls().get(index)))
                      : new GpuPaperdoll.Note("\u2014", UiTheme.DISABLED));
            }
        }
        return notes;
    }

    /**
     * The locations of a unit without a paperdoll (battle armor's troopers): a button each with its armor and the
     * weapon's roll aimed there, pressed where the weapon aims, disabled where it may not aim; a location without armor
     * or structure is left out.
     */
    private Table list(AimChoice choice) {
        Table list = new Table();
        list.setName("aim-locations");
        List<GpuUnitRecord.Location> locations = choice.record().locations();
        int column = 0;
        for (int index = 0; index < Math.min(locations.size(), choice.enabled().size()); index++) {
            GpuUnitRecord.Location location = locations.get(index);
            if (location.maxArmor() <= 0 && location.maxInternal() <= 0) {
                continue;
            }
            boolean open = choice.enabled().get(index);
            UiButton button = ui.button("hud", null, open ? text("GpuBoard.hud.aim.listRow", location.name(),
                  location.armor(), roll(choice.rolls().get(index))) : location.name(), null);
            button.setName("aim-location-" + index);
            button.pressed(index == choice.location());
            button.setDisabled(!open);
            int picked = index;
            onChange(button, () -> aim(picked));
            list.add(button).growX().uniformX().height(BUTTON_HEIGHT).pad(0, column == 0 ? 0 : 4, 4, 0);
            if (++column == LIST_COLUMNS) {
                column = 0;
                list.row();
            }
        }
        return list;
    }

    /** The location of a doll code in the rules' order, -1 for none (outside the doll, or a shield). */
    private int index(String code) {
        return code == null ? -1 : codes.indexOf(code);
    }

    /**
     * Whether the doll takes the location: the rear doll a rear-armored one while the shot strikes the rear, the front
     * doll every other.
     */
    private boolean strikes(GpuPaperdoll view, int index) {
        List<GpuUnitRecord.Location> locations = shown == null ? List.of() : shown.record().locations();
        boolean rear = rearShown && index >= 0 && index < locations.size() && locations.get(index).maxRear() > 0;
        return index >= 0 && view == (rear ? rearDoll : doll);
    }

    /** Whether the doll takes the location and the weapon may aim there: its outline is lit, a click aims there. */
    private boolean hits(GpuPaperdoll view, int index) {
        return shown != null && index >= 0 && index < shown.enabled().size() && strikes(view, index)
              && shown.enabled().get(index);
    }

    /** The codes of the locations the doll outlines in the wireframe green ({@link #hits}). */
    private Set<String> lit(GpuPaperdoll view) {
        Set<String> lit = new HashSet<>();
        for (int index = 0; index < codes.size(); index++) {
            if (hits(view, index)) {
                lit.add(codes.get(index));
            }
        }
        return lit;
    }

    /**
     * The location under the pointer on a doll ({@code -1}: none): that doll outlines it where it takes the location
     * and the weapon may aim there, and the aimed location stays marked on the doll that takes it.
     */
    private void hover(GpuPaperdoll over, int index) {
        AimChoice choice = shown;
        if (choice == null) {
            return;
        }
        boolean open = hits(over, index);
        for (GpuPaperdoll view : List.of(doll, rearDoll)) {
            view.marks(view == over && open ? codes.get(index) : "",
                  strikes(view, choice.location()) ? code(choice.location()) : "", "");
        }
    }

    /** Aims the weapon at the location and closes, where it may aim there; anything else does nothing. */
    private void aim(int index) {
        AimChoice choice = shown;
        if (choice != null && index >= 0 && index < choice.enabled().size() && choice.enabled().get(index)) {
            source.fire().aim(choice.aim(), index);
            close();
        }
    }

    /** Closes the dialog: Dismiss, Esc, a click beside it, and after a choice. */
    private void close() {
        state.aiming = null;
        layer.open(false);
    }

    private String code(int index) {
        return index >= 0 && index < codes.size() ? codes.get(index) : "";
    }

    /** A roll's colour as the weapons panel's: mint while it can succeed, dim where its odds are none. */
    private static Color colour(AimChoice choice, int value) {
        return GpuSolutionCard.possible(value) && Compute.oddsAbove(value, choice.aptitude()) > 0 ? UiTheme.MINT
              : UiTheme.DISABLED;
    }

    /** A roll as the panels show it, "{n}+", or a dash where there is none to make. */
    private static String roll(int value) {
        return GpuSolutionCard.possible(value) ? text("GpuBoard.hud.common.targetNumber", GpuHudKit.shown(value))
              : "—";
    }
}
