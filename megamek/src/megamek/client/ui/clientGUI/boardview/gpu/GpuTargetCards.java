/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.percent;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.shown;
import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Attack;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.Target;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFireOrders.WeaponRow;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFlow;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiList;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.units.Entity;

/**
 * Target cards over the board while the local unit declares its attacks (C.1 G9; r1 3.13, r2 5, plan A.8 H24-H29,
 * H31): one card per target in letter order, then one for the focus without attacks; a target that is no unit has its
 * card at its hex (GpuHud.HudView.head). A card shows the letter,
 * the name and the primary state, then the target's attacks in fire order (grip, weapon, ammunition, the aim toggle of
 * a weapon that may make an aimed shot (H36), roll, remove) over a footer; with more than two targets only the focused
 * card, or one opened by "Show attacks", lists them. GpuCardPlacement keeps the cards clear of the HUD panels and the
 * units; {@link #placed()} gives their rectangles to the board labels' leaders. The rows are a reorderable UiList: a
 * row with a grip drags to another place of the card, and its focused grip moves it on Alt+Up/Down; every move goes
 * through the fire orders.
 */
final class GpuTargetCards implements GpuHud.Component {
    /** The cards keep this far inside the window (layout.js: bounds 8, 8, W - 16, H - 16). */
    private static final float WINDOW_MARGIN = 8;
    /** Each HUD panel's rectangle grows by this much on every side (overlay.js refreshHudRects). */
    private static final float PANEL_MARGIN = 6;
    /** A collapsed card's width at every window size (.tcard.compact). */
    private static final float COMPACT_WIDTH = 260;
    /** Where the view knows a target's head only, its rectangle is 40 wide and reaches 60 below it (overlay.js). */
    private static final float HEAD_WIDTH = 40;
    private static final float HEAD_DEPTH = 60;
    /** The grip's and the remove mark's colour (.or .g, .or .x). */
    private static final Color MARK = Color.valueOf("8A9593");
    /**
     * A row's weapon name and the line under it, on whole units as the Table rounds them: the rows of shots 05-07 lie
     * 48 units apart with their rules.
     */
    private static final float NAME_LINE = 15;
    private static final float SUB_LINE = 18;
    /** The footer's 11-unit text on the body's 1.35 line height (.cf). */
    private static final float FOOT_SIZE = 11;
    private static final float FOOT_LINE = FOOT_SIZE * 1.35f;
    /** A row's aim toggle (H36), between the weapon and the roll. */
    private static final float AIM = 24;

    /**
     * What one card shows, so that a frame that changes none of it rebuilds nothing. {@code target} is null for the
     * focus without attacks; {@code attacks} counts the target's attacks, {@code rows} lists them while the card is
     * expanded; {@code selected}: a weapon is selected (the empty card's hint).
     */
    private record CardView(TargetKey key, Target target, String name, boolean expanded, List<Row> rows, int attacks,
          boolean selected, boolean editable, float width, int actorId) { }

    /** An attack row: the attack, its place in the fire order (1 first) and its weapon's row, null without one. */
    private record Row(Attack attack, int number, WeaponRow weapon) {
        /** Whether its weapon may aim at the card's unit (H36): the row then has the aim toggle. */
        boolean aimable() {
            return weapon != null && weapon.aimable();
        }
    }

    /** A card as built: what it shows, its table, the rows' grips by weapon, and the list of its rows (or null). */
    private record Card(CardView view, Table table, Map<Integer, Grip> grips, UiList list) {
        /** Whether a row of the card is dragged or still moves: the card waits for it before it is built anew. */
        boolean busy() {
            return list != null && list.busy();
        }
    }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final Table root = new Table();
    private final TextButton.TextButtonStyle setPrimaryStyle;
    private final TextButton.TextButtonStyle removeStyle;
    private final TextButton.TextButtonStyle showStyle;
    private final Drawable focusRing;
    /** The shown cards by target, in letter order with the focus card last. */
    private final Map<TargetKey, Card> cards = new LinkedHashMap<>();
    private Map<TargetKey, Rectangle> placed = Map.of();
    private int actorId = Entity.NONE;
    /** The card that "Show attacks" opened (S.ui.showCards); it stays expanded while its target keeps a card. */
    private TargetKey opened = TargetKey.NONE;

    /** {@code menu} opens a row's queued-attack menu and the ammunition list (G12). */
    GpuTargetCards(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu) {
        this.kit = kit;
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.menu = menu;
        root.setName("target-cards");
        Texture white = ui.skin.get("white", Texture.class);
        // ".pr.set" and ".or .x": bare marks that light up under the pointer, white and coral.
        setPrimaryStyle = bare("hud-title", UiTheme.ACCENT, Color.WHITE);
        removeStyle = bare("hud-body", MARK, UiTheme.CORAL);
        // ".cmp .mini": a small link with a quiet border, white under the pointer, in normal case.
        showStyle = bare("hud-small", UiTheme.ACCENT, Color.WHITE);
        showStyle.up = padded("pill");
        showStyle.over = padded("button-over");
        showStyle.down = showStyle.over;
        focusRing = new UiTheme.EdgeBox(white, null, UiTheme.MINT, 2, 2, 2, 2);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /**
     * The cards' rectangles by target as this frame placed and drew them, in stage units with y up; a card whose
     * target is not drawn (behind the camera) is left out. GpuBoardLabels.cards draws their leaders from them.
     */
    Map<TargetKey, Rectangle> placed() {
        return placed;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        if (fire.actorId() != actorId) {
            actorId = fire.actorId();
            opened = TargetKey.NONE;
        }
        Set<TargetKey> carded = fire.active() ? fire.carded() : Set.of();
        if (!carded.contains(opened)) {
            opened = TargetKey.NONE;
        }
        Map<TargetKey, CardView> views = new LinkedHashMap<>();
        for (TargetKey key : carded) {
            views.put(key, view(fire, key, inputs.metrics()));
        }
        cards.keySet().removeIf(key -> {
            boolean gone = !views.containsKey(key);
            if (gone) {
                cards.get(key).table().remove();
            }
            return gone;
        });
        int order = 0;
        for (CardView view : views.values()) {
            Card card = cards.get(view.key());
            // A card whose row is dragged or still moves keeps it: the new orders show once the rows rest.
            if (card == null || !card.view().equals(view) && !card.busy()) {
                card = rebuild(card, view);
                cards.put(view.key(), card);
            }
            card.table().setZIndex(order++);
        }
        place(fire, inputs);
    }

    /** The Esc chain's first step: a row dragged on a card goes home. True when one was. */
    boolean cancelDrag() {
        return cards.values().stream().anyMatch(card -> card.list() != null && card.list().cancel());
    }

    /** One card's view: its target (null for the focus without attacks), its rows while expanded and its width. */
    private CardView view(GpuFireOrders.Snapshot fire, TargetKey key, GpuHud.Metrics metrics) {
        Target target = fire.targets().stream().filter(candidate -> candidate.key().equals(key)).findFirst()
              .orElse(null);
        boolean expanded = fire.targets().size() <= 2 || key.equals(fire.focus().key()) || key.equals(opened);
        List<Row> rows = new ArrayList<>();
        List<Attack> attacks = fire.attacks();
        for (int index = 0; index < attacks.size(); index++) {
            Attack attack = attacks.get(index);
            if (attack.target().equals(key)) {
                WeaponRow weapon = fire.weapons().stream().filter(row -> row.eqNum() == attack.eqNum()).findFirst()
                      .orElse(null);
                rows.add(new Row(attack, index + 1, attack.eqNum() < 0 ? null : weapon));
            }
        }
        String name = target != null ? target.name() : fire.focus().name();
        return new CardView(key, target, name, expanded, expanded ? rows : List.of(), rows.size(),
              fire.selectedWeapon() >= 0, fire.editable(), expanded ? metrics.card() : COMPACT_WIDTH, fire.actorId());
    }

    /**
     * Builds the card anew in place of {@code old}; a grip that held the keyboard focus hands it to the new grip of
     * its weapon, so Alt+Up/Down goes on reordering the same attack (the prototype refocuses its grip).
     */
    private Card rebuild(Card old, CardView view) {
        Stage stage = root.getStage();
        int focusedGrip = Integer.MIN_VALUE;
        if (old != null && stage != null) {
            for (Map.Entry<Integer, Grip> grip : old.grips().entrySet()) {
                if (stage.getKeyboardFocus() == grip.getValue()) {
                    focusedGrip = grip.getKey();
                }
            }
        }
        Card card = build(view);
        if (old != null) {
            old.table().remove();
        }
        root.addActor(card.table());
        Grip grip = card.grips().get(focusedGrip);
        if (grip != null) {
            stage.setKeyboardFocus(grip);
        }
        return card;
    }

    /**
     * Places the cards of the targets the view draws (GpuCardPlacement): each target's screen rectangle, or the
     * rectangle the prototype assumes below its head; clear of the shown HUD panels, grown by 6, with the actor and
     * the targets as soft blocks, 8 inside the window. Positions round as the prototype's transforms do.
     */
    private void place(GpuFireOrders.Snapshot fire, GpuHud.Inputs inputs) {
        GpuHud.HudView view = inputs.view();
        List<GpuCardPlacement.Card> shown = new ArrayList<>();
        // The placement knows each shown card by its index here.
        List<TargetKey> keys = new ArrayList<>();
        List<Rectangle> units = new ArrayList<>();
        Rectangle actor = view.unitRects().get(fire.actorId());
        if (actor != null) {
            units.add(actor);
        }
        for (Card card : cards.values()) {
            TargetKey key = card.view().key();
            Vector2 head = view.head(key);
            Rectangle unit = view.unitRects().get(key.unitId());
            Table table = card.table();
            table.setVisible(head != null);
            if (head == null) {
                // A hidden card's grip lets go of the keyboard, as a hidden element loses the focus.
                Stage stage = root.getStage();
                if (stage != null && stage.getKeyboardFocus() != null
                      && stage.getKeyboardFocus().isDescendantOf(table)) {
                    stage.setKeyboardFocus(null);
                }
                continue;
            }
            Rectangle target = unit != null ? unit
                  : new Rectangle(head.x - HEAD_WIDTH / 2, head.y - HEAD_DEPTH, HEAD_WIDTH, HEAD_DEPTH);
            units.add(target);
            table.setWidth(card.view().width());
            table.validate();
            table.setHeight(table.getPrefHeight());
            table.validate();
            shown.add(new GpuCardPlacement.Card(keys.size(), table.getWidth(), table.getHeight(), target));
            keys.add(key);
        }
        List<Rectangle> panels = new ArrayList<>();
        for (Rectangle panel : inputs.panelBounds()) {
            panels.add(new Rectangle(panel.x - PANEL_MARGIN, panel.y - PANEL_MARGIN, panel.width + 2 * PANEL_MARGIN,
                  panel.height + 2 * PANEL_MARGIN));
        }
        GpuHud.Metrics metrics = inputs.metrics();
        Rectangle bounds = new Rectangle(WINDOW_MARGIN, WINDOW_MARGIN, metrics.width() - 2 * WINDOW_MARGIN,
              metrics.height() - 2 * WINDOW_MARGIN);
        Map<TargetKey, Rectangle> next = new LinkedHashMap<>();
        GpuCardPlacement.place(shown, bounds, panels, units).forEach((index, rectangle) -> {
            Table table = cards.get(keys.get(index)).table();
            // The left and top edges on whole units, as the prototype's translate(round(x), round(y)).
            table.setPosition(Math.round(rectangle.x), Math.round(rectangle.y + rectangle.height) - rectangle.height);
            next.put(keys.get(index), new Rectangle(table.getX(), table.getY(), table.getWidth(), table.getHeight()));
        });
        placed = next;
    }

    // ------------------------------------------------------------------ building

    /**
     * A card (.tcard.panel), its frame neutral for every target (the letter carries the target's colour):
     * the header and its rule, then the attack rows and the footer, the empty card's hint, or the collapsed line.
     * A click on it focuses its target and assigns the armed weapon there, as a click on the unit does.
     */
    private Card build(CardView view) {
        TargetKey key = view.key();
        Table table = ui.panel();
        table.setName("target-card-" + key.id());
        table.setTouchable(Touchable.enabled);
        table.add(header(view)).growX().row();
        table.add(rule()).growX().height(1).row();
        Map<Integer, Grip> grips = new HashMap<>();
        UiList list = null;
        if (!view.expanded()) {
            table.add(collapsed(view)).growX().row();
        } else if (!view.rows().isEmpty()) {
            list = new UiList(ui);
            list.setName("card-rows");
            List<Row> rows = view.rows();
            for (int index = 0; index < rows.size(); index++) {
                // The row with its rule (.or's border-bottom), which moves with it. As the prototype's draggable .or,
                // a row with controls drags from anywhere; its grip marks it.
                Table entry = new Table();
                entry.add(row(rows.get(index), view, list, index, grips)).growX().row();
                entry.add(rule()).growX().height(1);
                list.add(entry, grips.containsKey(rows.get(index).attack().eqNum()) ? entry : null);
            }
            // H29: a dropped row and Alt+Up/Down move the attack among its target's, in one requeue.
            list.reorderable((from, to) -> source.fire().move(rows.get(from).attack().eqNum(), to - from));
            table.add(list).growX().row();
        }
        table.add(footer(view)).growX();
        table.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                // The card's own controls do their own thing (the prototype's innermost data-act).
                if (event.getTarget().firstAscendant(Button.class) == null) {
                    GpuContextMenu.focusTarget(source, state, key);
                }
            }
        });
        return new Card(view, table, Map.copyOf(grips), list);
    }

    /**
     * The header (.th): the target's letter in its colour, the upper-case name, and "Primary", "Set primary" or "No
     * attacks" at the right. The focus without attacks has no letter yet, and no "+" square that would look like a
     * button.
     */
    private Table header(CardView view) {
        Target target = view.target();
        Table header = new Table();
        header.pad(9, 12, 8, 12);
        if (target != null) {
            header.add(kit.letter(target.letter(), true)).padRight(8);
        }
        Label name = ui.label(UiTheme.upper(view.name()), "hud-title", 14, UiTheme.TEXT);
        name.setName("card-name");
        name.setWrap(true);
        header.add(name).growX().minWidth(0).left();
        Actor primary;
        if (target == null) {
            primary = ui.label(UiTheme.upper(text("GpuBoard.hud.cards.noAttacks")), "hud-title", 11, UiTheme.MUTED);
        } else if (target.primary()) {
            Table tag = new Table();
            tag.add(ui.icon("star", 13, UiTheme.MINT));
            tag.add(ui.label(UiTheme.upper(text("GpuBoard.hud.cards.primary")), "hud-title", 11, UiTheme.MINT))
                  .padLeft(5);
            primary = tag;
        } else {
            UiButton set = mark(setPrimaryStyle, "star-outline", 13);
            set.setText(UiTheme.upper(text("GpuBoard.hud.cards.setPrimary")));
            UiKit.size(set.getLabel(), "hud-title", 11);
            set.add(set.getLabel()).padLeft(5);
            set.setDisabled(!view.editable());
            onChange(set, () -> source.fire().setPrimary(view.key()));
            primary = set;
        }
        primary.setName("card-primary");
        header.add(primary).padLeft(8);
        return header;
    }

    /**
     * An attack row (.or), the {@code index}th of the card's {@code list}: the grip (no fire-order number, which only
     * took room), the weapon over its ammunition (a select when it can switch bins) or its location and kind, the aim
     * toggle (H36), the roll and the remove mark. A right click opens the queued-attack menu (H28, H37); a drag takes
     * the row to another place of the card (H29). A handheld weapon's attack has no number on the actor, so it has no
     * controls.
     */
    private Table row(Row row, CardView view, UiList list, int index, Map<Integer, Grip> grips) {
        Attack attack = row.attack();
        int eqNum = attack.eqNum();
        boolean controls = eqNum >= 0 && view.editable();
        Table line = new Table();
        line.setName("card-row-" + row.number());
        // The whole row takes the right click, as the prototype's .or does.
        line.setTouchable(Touchable.enabled);
        line.pad(7, 12, 7, 12);
        if (controls) {
            Grip grip = new Grip(list, index);
            grip.setName("card-grip-" + eqNum);
            grips.put(eqNum, grip);
            line.add(grip).size(12, 16);
        } else {
            line.add().size(12, 16);
        }
        Table weapon = new Table();
        weapon.left().defaults().left();
        Label name = ui.label(UiTheme.upper(attack.weapon()), "hud-heading", 13, UiTheme.TEXT);
        name.setEllipsis(true);
        weapon.add(name).growX().minWidth(0).height(NAME_LINE).row();
        Actor sub = sub(row, controls);
        weapon.add(sub).minWidth(0).height(SUB_LINE);
        line.add(weapon).growX().minWidth(0).padLeft(9);
        if (view.editable() && view.rows().stream().anyMatch(Row::aimable)) {
            // The card's rows keep one column for the toggles, so their rolls line up.
            line.add(controls && row.aimable() ? GpuAimDialog.toggle(ui, state, view.actorId(), row.weapon(), true,
                  AIM) : null).size(AIM).padLeft(9);
        }
        Table roll = new Table();
        if (GpuSolutionCard.possible(attack.value())) {
            roll.add(ui.label(text("GpuBoard.hud.common.targetNumber", shown(attack.value())), "hud-heading", 16,
                  UiTheme.TEXT)).bottom();
            roll.add(ui.label(percent(attack.odds()), "hud-medium", 11, UiTheme.MUTED)).bottom().padLeft(5)
                  .padBottom(1);
        } else {
            roll.add(ui.label("\u2014", "hud-medium", 11, UiTheme.MUTED));
        }
        line.add(roll).padLeft(9);
        if (controls) {
            UiButton remove = mark(removeStyle, "close", 15);
            remove.setName("card-remove-" + eqNum);
            onChange(remove, () -> source.fire().remove(eqNum));
            line.add(remove).size(15).padLeft(9);
            line.addListener(new ClickListener(Input.Buttons.RIGHT) {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    menu.attack(eqNum, event.getStageX(), event.getStageY());
                }
            });
        } else {
            line.add().size(15).padLeft(9);
        }
        return line;
    }

    /**
     * Under the weapon's name: its ammunition as the weapons panel shows it (a select while the bins can change), the
     * attack's bin for a weapon without a row, else "{location} · Energy|Missile|Ballistic" or the location alone.
     */
    private Actor sub(Row row, boolean controls) {
        Attack attack = row.attack();
        Actor sub;
        if (row.weapon() != null && !row.weapon().ammo().isEmpty()) {
            sub = GpuWeaponsPanel.ammo(ui, menu, source, row.weapon(), controls, UiTheme.MUTED);
        } else if (!attack.ammo().isEmpty()) {
            sub = GpuWeaponsPanel.ammoText(ui, attack.ammo(), UiTheme.MUTED);
        } else {
            String key = switch (attack.kind()) {
                case "Energy" -> "GpuBoard.hud.cards.energy";
                case "Missile" -> "GpuBoard.hud.cards.missile";
                case "Ballistic" -> "GpuBoard.hud.cards.ballistic";
                default -> null;
            };
            sub = GpuWeaponsPanel.ammoText(ui, key == null ? attack.location() : text(key, attack.location()),
                  UiTheme.MUTED);
        }
        sub.setName("card-ammo-" + attack.eqNum());
        return sub;
    }

    /**
     * The collapsed line (.cmp): "{n} attack(s) assigned" and "Show attacks", which keeps the card open and focuses
     * its target.
     */
    private Table collapsed(CardView view) {
        Table line = new Table();
        line.pad(7, 12, 8, 12);
        line.add(ui.label(text("GpuBoard.hud.cards.attacksAssigned", view.attacks()), "hud-small", 11.5f,
              UiTheme.ACCENT)).left().expandX();
        UiButton show = new UiButton(ui, "hud-mini");
        show.setStyle(showStyle);
        show.setText(text("GpuBoard.hud.cards.showAttacks"));
        UiKit.size(show.getLabel(), "hud-small", 11);
        show.add(show.getLabel()).height(FOOT_LINE);
        show.setName("card-show");
        onChange(show, () -> {
            opened = opened.equals(view.key()) ? TargetKey.NONE : view.key();
            source.fire().focusTarget(view.key());
        });
        line.add(show).right();
        return line;
    }

    /** The footer: a wrapped hint and, when attacks are assigned, a button to remove them from this target. */
    private Table footer(CardView view) {
        Table footer = new Table();
        footer.setName("card-footer");
        footer.pad(6, 12, 7, 12);
        if (view.attacks() == 0 || view.expanded()) {
            String hint = view.attacks() > 0 ? "GpuBoard.hud.cards.reorderHint"
                  : view.selected() ? "GpuBoard.hud.cards.emptyHintArmed" : "GpuBoard.hud.cards.emptyHint";
            UiFlow flow = new UiFlow(ui, FOOT_SIZE, FOOT_LINE);
            flow.run(text(hint), "hud-body", UiTheme.MUTED);
            flow.setName("card-foot");
            footer.add(flow).growX().minWidth(0);
        } else {
            footer.add().expandX();
        }
        if (view.attacks() > 0) {
            UiButton clear = ui.button("hud-mini", null, text("GpuBoard.hud.cards.clearAll"), null);
            clear.setName("card-clear-all");
            clear.setDisabled(!view.editable());
            ui.tip(clear).getActor().setText(text("GpuBoard.hud.cards.clearAllTip"));
            onChange(clear, () -> source.fire().removeTarget(view.key()));
            footer.add(clear).padLeft(8);
        }
        return footer;
    }

    private Image rule() {
        return new Image(ui.skin.getDrawable("rule"));
    }

    /** A bare mark (.pr.set, .or .x): an icon, and text when the caller adds it, in the style's colours. */
    private UiButton mark(TextButton.TextButtonStyle style, String icon, float size) {
        UiButton mark = new UiButton(ui, "hud-mini");
        mark.setStyle(style);
        mark.pad(0);
        UiKit.Icon image = ui.icon(icon, size, Color.WHITE);
        mark.icons.add(image);
        mark.add(image).size(size);
        return mark;
    }

    /** A button style without a background whose text and icons turn from {@code rest} to {@code over}. */
    private TextButton.TextButtonStyle bare(String font, Color rest, Color over) {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = ui.skin.getFont(font);
        style.fontColor = rest;
        style.overFontColor = over;
        style.downFontColor = over;
        style.disabledFontColor = UiTheme.DISABLED;
        return style;
    }

    /**
     * A copy of a rounded box from the skin around the link's text (.cmp .mini: its 1-unit border and padding 3 8),
     * without a minimum height.
     */
    private Drawable padded(String name) {
        NinePatchDrawable box = new NinePatchDrawable((NinePatchDrawable) ui.skin.getDrawable(name));
        box.setTopHeight(4);
        box.setBottomHeight(4);
        box.setLeftWidth(9);
        box.setRightWidth(9);
        box.setMinHeight(0);
        return box;
    }

    // ------------------------------------------------------------------ reordering

    /**
     * A row's grip (.or .g): six dots that mark the row as draggable in its card's list, take the keyboard focus when
     * pressed and show the mint ring while they hold it; Alt+Up/Down then moves the row one place earlier or later
     * through the list, which flies it as a drop does (plan Q1, H29). The key is consumed even where the row cannot
     * move, so it never reaches MegaMek's called-shot keys.
     */
    private final class Grip extends Container<UiKit.Icon> {
        Grip(UiList list, int index) {
            super(ui.icon("grip", 12, MARK));
            setTouchable(Touchable.enabled);
            ui.tip(this).getActor().setText(text("GpuBoard.hud.cards.gripTip"));
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    getStage().setKeyboardFocus(Grip.this);
                    return false;
                }

                @Override
                public boolean keyDown(InputEvent event, int keycode) {
                    if (UIUtils.alt() && (keycode == Input.Keys.UP || keycode == Input.Keys.DOWN)) {
                        list.move(index, keycode == Input.Keys.UP ? -1 : 1);
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            super.draw(batch, parentAlpha);
            if (getStage() != null && getStage().getKeyboardFocus() == this) {
                // The focus outline (:focus-visible): 2 units of mint around the grip's box.
                batch.setColor(1, 1, 1, getColor().a * parentAlpha);
                focusRing.draw(batch, getX() - 2, getY() - 2, getWidth() + 4, getHeight() + 4);
            }
        }
    }
}
