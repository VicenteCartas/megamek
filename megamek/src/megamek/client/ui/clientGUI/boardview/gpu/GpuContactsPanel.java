/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.facing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.percent;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.shown;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.signed;
import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Contact;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.UnitRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.UnitRow.Line;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;

/**
 * The contacts in the right column (battle HUD layout, zone 4): the forces list's mirror for the enemy. It folds to a
 * rail at the window's right edge, each contact's short name, armor and structure next to its sprite, and unfolds
 * while the pointer rests on it into three-line rows (name, pilot and movement, armor and structure); the pin keeps
 * it open, and the utility bar's contacts toggle shows and hides it. A sensor contact shows as a "?" with its
 * distance only. During movement it carries the fire preview instead: the rail gives each enemy's best roll, and the
 * open panel where the previewed unit fires from, each enemy's best salvo both ways and the open row's weapons, with
 * the Outgoing and Incoming guide toggles. The highlighted row is the unit the card shows: a row's click inspects its
 * enemy. It presents the snapshots and opens unit menus; the client computes every value.
 */
final class GpuContactsPanel implements GpuHud.Component {
    private static final String UNIDENTIFIED = "GpuBoard.hud.common.unidentified";
    private static final String SENSOR_CONTACT = "GpuBoard.hud.common.sensorContact";
    private static final String SEPARATOR = " \u00B7 ";
    /** Row and header heights; the rail and the open list share them, so rows stay in place as it unfolds. */
    private static final float ROW_HEIGHT = 44;
    private static final float HEAD_HEIGHT = 34;
    /** The pointer rests this long on the rail before it unfolds, and the list folds this long after it left. */
    private static final long UNFOLD_DELAY = 250;
    private static final long FOLD_DELAY = 400;
    private static final Color MOVED = Color.valueOf("BFEEE2");
    private static final Color HOT = Color.valueOf("FF8A5B");
    private static final Color SEPARATOR_LINE = new Color(43 / 255f, 56 / 255f, 71 / 255f, .45f);
    private static final Color HOVER = new Color(1, 1, 1, .07f);
    private static final Color INSPECTED = new Color(UiTheme.CORAL.r, UiTheme.CORAL.g, UiTheme.CORAL.b, .1f);
    /** A twist turns the torso by whole hexsides (the mock's "Twist left 60 degrees"). */
    private static final int HEXSIDE_DEGREES = 60;
    // Preview row opacities: no shot (.prow.none), a sensor contact (.prow.contact), and every row while updating.
    private static final float NO_SHOT_ALPHA = .7f;
    private static final float CONTACT_ALPHA = .8f;
    private static final float UPDATING_ALPHA = .5f;
    /** The pressed Incoming toggle's text (.b.mini.in[aria-pressed]). */
    private static final Color INCOMING_INK = Color.valueOf("231312");

    /**
     * A preview row: the kit's unit row in a box that shows the open row's fill, and the tooltip of the row's fourth
     * line, the coral return-fire line (.em.inc).
     */
    private record PreviewRow(Container<UnitRow> box, UnitRow row, TextTooltip incomingTip) { }

    /**
     * Everything the list shows; the snapshots keep their identity while unchanged, so the comparison is cheap.
     * {@code card} is the unit the card shows ({@link GpuHudState#cardUnit}).
     */
    private record View(GpuFirePreview.Snapshot preview, List<UnitStatus> units, GpuBattleStatus.Snapshot status,
          int focus, int card, boolean folded) { }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final IntConsumer select;
    private final Table root;
    /** The open panel's header (title, count, the preview's toggles, pin) and the rail's (the count). */
    private final Table openHead = new Table();
    private final Table railHead = new Table();
    private final Cell<Table> headCell;
    private final Label count;
    private final Label railCount;
    private final Table toggles = new Table();
    private final Cell<Table> togglesCell;
    private final UiButton pin;
    private final UiButton outgoingToggle;
    private final UiButton incomingToggle;
    private final Container<Table> from;
    private final Cell<Actor> fromCell;
    private final Drawable fromCurrent;
    private final Drawable fromDestination;
    private final Label fromTitle;
    private final Label fromTargets;
    private final Label fromThreats;
    private final Label fromLine;
    private final Table list = new Table();
    private final Drawable openFill;
    private final Drawable detailEdges;
    /** The contacts' rows, kept across rebuilds so that their pointer state stays with them. */
    private final Map<Integer, ContactRow> contactRows = new HashMap<>();
    private final Map<Integer, PreviewRow> previewRows = new HashMap<>();
    private boolean outgoing = true;
    private boolean incoming = true;
    private boolean folded = true;
    /** The open list's state while not pinned: unfolded by the resting pointer, folded a moment after it left. */
    private boolean unfolded;
    private boolean hovered;
    private long enteredAt;
    private long leftAt;
    private View shown;

    /**
     * {@code menu} opens this component's unit menus and select lists (C13, SelectField). {@code select} selects or
     * inspects a unit ({@link GpuHud#select}).
     */
    GpuContactsPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu,
          IntConsumer select) {
        this.kit = kit;
        ui = kit.ui;
        this.state = state;
        this.menu = menu;
        this.select = select;
        openFill = ui.skin.getDrawable("preview-open");
        detailEdges = ui.skin.getDrawable("preview-detail");
        fromCurrent = ui.skin.getDrawable("from-current");
        fromDestination = ui.skin.getDrawable("from-destination");
        root = ui.panel();
        root.setName("contacts-panel");
        root.top();

        outgoingToggle = toggle("GpuBoard.hud.contacts.outgoing", "GpuBoard.hud.contacts.outgoingTip",
              "button-mini-out", UiTheme.FILL_INK, Color.WHITE);
        outgoingToggle.setName("contacts-outgoing");
        onChange(outgoingToggle, () -> {
            outgoing = !outgoing;
            outgoingToggle.pressed(outgoing);
        });
        incomingToggle = toggle("GpuBoard.hud.contacts.incoming", "GpuBoard.hud.contacts.incomingTip",
              "button-mini-in", INCOMING_INK, INCOMING_INK);
        incomingToggle.setName("contacts-incoming");
        onChange(incomingToggle, () -> {
            incoming = !incoming;
            incomingToggle.pressed(incoming);
        });
        toggles.add(outgoingToggle);
        toggles.add(incomingToggle).padLeft(6);
        pin = ui.button("hud-icon", "pin", null, null);
        pin.setName("contacts-pin");
        ui.tip(pin).getActor().setText(text("GpuBoard.hud.contacts.pinTip"));
        onChange(pin, () -> {
            state.contactsPinned = !state.contactsPinned;
            unfolded = state.contactsPinned || hovered;
        });

        openHead.setName("contacts-header");
        openHead.pad(0, 12, 0, 6);
        openHead.add(ui.label(UiTheme.upper(text("GpuBoard.hud.common.contacts")), "hud-title", 14, UiTheme.TEXT))
              .padRight(8);
        count = ui.label("", "hud-medium", 12, UiTheme.MUTED);
        count.setName(UiKit.HEADER_COUNT);
        openHead.add(count).growX().left();
        togglesCell = openHead.add((Table) null).padLeft(6);
        openHead.add(pin).size(28).padLeft(2);
        // The rail's header sits at the window's edge, over the rows' sprites.
        railHead.setName("contacts-rail-head");
        railHead.right().padRight(12);
        railCount = ui.label("", "hud-medium", 12, UiTheme.MUTED);
        Image disc = new Image(ui.skin.getDrawable("unit-disc-6"));
        disc.setColor(UiTheme.CORAL);
        railHead.add(disc).padRight(5);
        railHead.add(railCount);
        headCell = root.add(openHead).growX().height(HEAD_HEIGHT);
        root.row();
        root.add(ui.solid(UiTheme.LINE)).growX().height(1).row();

        // The "from" block (.from): a 3-unit bar and padding 8 10 inside a margin of 0 10 8.
        Table block = new Table();
        block.setName("contacts-from");
        block.pad(8, 13, 8, 10);
        fromTitle = ui.label("", "hud-caption", 11.5f, UiTheme.TEXT);
        // White in its style, because a Label multiplies its own colour, mint or muted (showFrom), by the style's.
        fromTargets = ui.label("", "hud-small", 11, Color.WHITE);
        fromThreats = ui.label("", "hud-small", 11, UiTheme.CORAL);
        fromLine = ui.label("", "hud-small", 11.5f, UiTheme.ACCENT);
        fromLine.setWrap(true);
        // In a narrow column the title wraps before the counts do, as the prototype's flex line shrinks it.
        fromTitle.setWrap(true);
        block.add(fromTitle).growX().minWidth(0).left();
        Table counts = new Table();
        counts.add(fromTargets);
        counts.add(fromThreats);
        block.add(counts).right().top().padLeft(8).row();
        block.add(fromLine).colspan(2).growX().left().padTop(2);
        from = new Container<>(block).fillX().pad(8, 10, 8, 10);

        list.top();
        ScrollPane scroll = ui.scrollList(list);
        scroll.setName("contacts-list");
        root.add(scroll).grow().minHeight(0).row();
        // Below the rows, so the rail's rows keep their place when hovering unfolds the panel.
        fromCell = root.add((Actor) null).growX();
        root.row();

        // The pointer resting on the panel unfolds the rail; it folds again a moment after the pointer left.
        root.addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                if (pointer == -1 && !hovered && (fromActor == null || !fromActor.isDescendantOf(root))) {
                    hovered = true;
                    enteredAt = TimeUtils.millis();
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(root))) {
                    hovered = false;
                    leftAt = TimeUtils.millis();
                }
            }
        });
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Whether the panel shows as its rail this frame, which the HUD gives {@link GpuForcesPanel#RAIL_WIDTH}. */
    boolean folded() {
        return folded;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        long now = TimeUtils.millis();
        if (state.contactsPinned) {
            unfolded = true;
        } else if (hovered) {
            unfolded |= now - enteredAt >= UNFOLD_DELAY;
        } else if (unfolded && !menu.isOpen() && now - leftAt >= FOLD_DELAY) {
            unfolded = false;
        }
        folded = !unfolded;
        View view = new View(inputs.frame().panels().preview(), state.presentedUnits(), inputs.frame().status(),
              state.focus(), state.cardUnit(), folded);
        if (!view.equals(shown)) {
            shown = view;
            rebuild();
        }
        pin.pressed(state.contactsPinned);
    }

    /** The Outgoing toggle (F3): the board shows the guides from the previewed unit to its targets while pressed. */
    boolean outgoingGuides() {
        return outgoing;
    }

    /** The Incoming toggle (F3): the board shows the guides from enemies that can fire back while pressed. */
    boolean incomingGuides() {
        return incoming;
    }

    /**
     * A mini toggle (.b.mini.out, .b.mini.in) that is filled in its colour while pressed, with the skin's {@code face};
     * both start pressed.
     */
    private UiButton toggle(String key, String tipKey, String face, Color ink, Color hoverInk) {
        UiButton button = ui.button("hud-mini", null, text(key), null);
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(button.getStyle());
        // The prototype keeps the pressed face under the pointer, where only the Outgoing text turns white (its hover
        // rule outranks the pressed text colour).
        style.checked = ui.skin.getDrawable(face);
        style.checkedOver = style.checked;
        style.checkedDown = style.checked;
        style.checkedFontColor = ink;
        style.checkedOverFontColor = hoverInk;
        style.checkedDownFontColor = hoverInk;
        button.setStyle(style);
        button.pressed(true);
        ui.tip(button).getActor().setText(text(tipKey));
        return button;
    }

    private void rebuild() {
        List<UnitStatus> enemies = shown.units().stream()
              .filter(unit -> unit.side() == GpuBattleStatus.Side.ENEMY).toList();
        boolean previewing = shown.preview().active();
        boolean rail = shown.folded();
        count.setText(String.valueOf(enemies.size()));
        railCount.setText(String.valueOf(enemies.size()));
        togglesCell.setActor(previewing ? toggles : null);
        Table head = rail ? railHead : openHead;
        if (headCell.getActor() != head) {
            headCell.setActor(head);
        }
        fromCell.setActor(previewing && !rail ? from : null);
        list.clearChildren();
        if (previewing && !rail) {
            showFrom(shown.preview());
            previewRows(shown.preview());
        } else if (previewing) {
            for (Contact contact : shown.preview().contacts()) {
                UnitStatus unit = unit(contact.id());
                if (unit != null) {
                    list.add(contactRow(unit).show(unit, contact)).growX().minWidth(0).height(ROW_HEIGHT).row();
                }
            }
        } else {
            enemies.forEach(unit -> list.add(contactRow(unit).show(unit, null)).growX().minWidth(0)
                  .height(ROW_HEIGHT).row());
        }
        Set<Integer> ids = new HashSet<>(enemies.stream().map(UnitStatus::id).toList());
        contactRows.keySet().retainAll(ids);
        previewRows.keySet().retainAll(ids);
    }

    /**
     * The "from" block (F4): destination or current hex, the counts, and the hex, facing and movement modifiers, or
     * why the unit is not previewed. A preview being recomputed says so and keeps its old rows, dimmed.
     */
    private void showFrom(GpuFirePreview.Snapshot preview) {
        from.getActor().setBackground(preview.fromDestination() ? fromDestination : fromCurrent);
        fromTitle.setText(UiTheme.upper(text(preview.fromDestination() ? "GpuBoard.hud.contacts.fromDestination"
              : "GpuBoard.hud.contacts.fromCurrentHex")));
        if (preview.complete()) {
            fromTargets.setText(text("GpuBoard.hud.contacts.targets", preview.targets()) + SEPARATOR);
            fromTargets.setColor(UiTheme.MINT);
            fromThreats.setText(text("GpuBoard.hud.contacts.threats", preview.threats()));
        } else {
            fromTargets.setText(text("GpuBoard.hud.contacts.updating"));
            fromTargets.setColor(UiTheme.MUTED);
            fromThreats.setText("");
        }
        String line;
        if (!preview.unavailable().isEmpty()) {
            line = text("GpuBoard.hud.contacts.notPreviewed", preview.unavailable());
        } else {
            String movement;
            if (preview.fromDestination()) {
                movement = text("GpuBoard.hud.contacts.fromMove", preview.moved(), signed(preview.attackerModifier()),
                      signed(preview.tmm()));
            } else {
                UnitStatus unit = unit(preview.unitId());
                movement = text(unit != null && unit.done() ? "GpuBoard.hud.contacts.alreadyMoved"
                      : "GpuBoard.hud.contacts.notMovedYet");
            }
            line = text("GpuBoard.hud.contacts.fromLine", preview.from() == null ? "" : preview.from().getBoardNum(),
                  facing(preview.facing()), movement);
        }
        fromLine.setText(preview.breachNotPredicted()
              ? line + SEPARATOR + text("GpuBoard.hud.contacts.breachNotPredicted") : line);
    }

    /** The preview rows in the order the preview ranked them, the open one followed by its detail (F5, F6). */
    private void previewRows(GpuFirePreview.Snapshot preview) {
        float fade = preview.complete() ? 1 : UPDATING_ALPHA;
        for (Contact contact : preview.contacts()) {
            UnitStatus unit = unit(contact.id());
            if (unit == null) {
                // Not presented yet (C.6); its row follows with the status.
                continue;
            }
            PreviewRow item = previewRows.computeIfAbsent(contact.id(), this::previewRow);
            // The open row is the enemy the card shows; the board badges and guides it as well (F9).
            boolean opened = contact.id() == shown.card();
            boolean detail = opened && !contact.sensor() && !contact.outgoing().lines().isEmpty();
            showPreviewRow(item, contact, unit, preview, opened, fade);
            list.add(item.box()).growX().minWidth(0).pad(0, 10, detail ? 2 : 6, 10).row();
            if (detail) {
                list.add(detail(contact, unit, preview)).growX().minWidth(0).pad(0, 10, 8, 10).row();
            }
        }
    }

    private PreviewRow previewRow(int id) {
        UnitRow row = kit.unitRow(34, 30);
        row.setName("contacts-preview-" + id);
        // A click inspects the enemy through the HUD's rule, which opens its row; on the open row it ends the
        // inspection, which closes the row (F6).
        onChange(row, () -> {
            if (state.inspected == id) {
                state.inspected = Entity.NONE;
            } else {
                select.accept(id);
            }
        });
        row.addListener(UnitRow.menuOpener(menu, id, this::unit));
        // The name (.prow .nm b) is 13 units, half a unit smaller than the forces row's, on the font's normal line
        // height; with the coral return-fire line (the fourth line) the row is 70 units tall, as in shot 02 (Table
        // rounds sizes up to whole units).
        row.nameSize(13, 15);
        row.extra.setColor(UiTheme.CORAL);
        return new PreviewRow(new Container<>(row).fillX(), row, ui.tip(row.extra));
    }

    private void showPreviewRow(PreviewRow item, Contact contact, UnitStatus unit, GpuFirePreview.Snapshot preview,
          boolean opened, float fade) {
        UnitRow row = item.row();
        Table tn = new Table();
        if (contact.sensor()) {
            row.set(null, UiTheme.BLIP, text(UNIDENTIFIED), text(SENSOR_CONTACT),
                  text("GpuBoard.hud.contacts.sensorReturn", contact.distance()), Tone.NORMAL);
            row.line.setColor(UiTheme.MUTED);
            showIncoming(item, null, preview);
            tn.add(ui.label("\u2014", "hud-medium", 11, UiTheme.MINT)).right();
            // A sensor contact's row has no action and no menu (.prow.contact).
            row.setDisabled(true);
            row.getColor().a = CONTACT_ALPHA * fade;
        } else {
            GpuFirePreview.Side out = contact.outgoing();
            boolean shot = out.available() > 0;
            row.set(unit.icon(), UiTheme.CORAL, unit.model(), unit.chassis(), shot
                  ? text("GpuBoard.hud.contacts.weapons", out.available(), out.total(), pose(out, preview.facing()))
                  : text("GpuBoard.hud.contacts.noShot", contact.distance()), Tone.OK);
            if (!shot) {
                row.line.setColor(UiTheme.MUTED);
            }
            GpuFirePreview.Side in = contact.incoming();
            showIncoming(item, in.available() > 0 ? text("GpuBoard.hud.contacts.returnFire", in.available(),
                  shown(in.best())) : null, preview);
            if (shot) {
                tn.add(ui.label(text("GpuBoard.hud.common.targetNumber", shown(out.best())), "hud-heading", 20,
                      Color.WHITE)).right().height(20).row();
                tn.add(ui.label(percent(out.odds()), "hud-medium", 11, UiTheme.MINT)).right().padTop(3);
            } else {
                tn.add(ui.label("\u2014", "hud-medium", 11, UiTheme.MINT)).right();
            }
            row.setDisabled(false);
            row.getColor().a = (shot ? 1 : NO_SHOT_ALPHA) * fade;
        }
        row.right.setActor(tn);
        row.inspected(opened);
        item.box().setBackground(opened ? openFill : null);
    }

    /** The coral line under the status (.em.inc), or none when {@code line} is null. */
    private void showIncoming(PreviewRow item, String line, GpuFirePreview.Snapshot preview) {
        item.row().extra(line);
        item.incomingTip().getActor().setText(text(preview.fromDestination()
              ? "GpuBoard.hud.contacts.returnFireDestinationTip" : "GpuBoard.hud.contacts.returnFirePositionTip"));
    }

    /**
     * The open row's detail (.pdetail, F6): the chosen salvo's weapons with their roll or why they cannot fire, then
     * the weapons of the enemy that can fire back, or that none can.
     */
    private Table detail(Contact contact, UnitStatus unit, GpuFirePreview.Snapshot preview) {
        Table detail = new Table();
        detail.setName("contacts-detail-" + contact.id());
        detail.setBackground(detailEdges);
        // .pdetail's padding 6 10 8 inside its 1-unit side and bottom edges.
        detail.pad(6, 11, 9, 11);
        detail.defaults().growX().minWidth(0);
        GpuFirePreview.Side out = contact.outgoing();
        detail.add(detailHead(text("GpuBoard.hud.contacts.detailHead", detailPose(out, preview.facing()),
              contact.distance()), UiTheme.MUTED)).row();
        out.lines().forEach(line -> detail.add(weaponLine(line, Color.WHITE)).row());
        GpuFirePreview.Side in = contact.incoming();
        if (in.available() > 0) {
            detail.add(detailHead(text("GpuBoard.hud.contacts.incomingHead", unit.chassis(), pose(in, unit.facing())),
                  UiTheme.CORAL)).padTop(6).row();
            in.lines().stream().filter(GpuContactsPanel::available)
                  .forEach(line -> detail.add(weaponLine(line, UiTheme.CORAL)).row());
        } else {
            Label none = ui.label(text(preview.fromDestination() ? "GpuBoard.hud.contacts.noReturnDestination"
                  : "GpuBoard.hud.contacts.noReturnHex"), "hud-body", 12, UiTheme.MUTED);
            none.setWrap(true);
            detail.add(none).pad(2, 0, 2, 0);
        }
        return detail;
    }

    /** A detail header (.pdetail .ph): its title, which wraps, and "Roll / hit" at the right. */
    private Table detailHead(String title, Color color) {
        Table head = new Table();
        head.pad(2, 0, 4, 0);
        Label left = ui.label(UiTheme.upper(title), "hud-caption", 10, color);
        left.setWrap(true);
        head.add(left).growX().minWidth(0).left();
        head.add(ui.label(UiTheme.upper(text("GpuBoard.hud.contacts.rollHit")), "hud-caption", 10, color))
              .right().top().padLeft(10);
        return head;
    }

    /**
     * One weapon (.pdetail .pl): name and muted location, and its roll in {@code value} colour with the odds. A roll
     * above 12 is dim, with its modifiers in a tooltip; a weapon that cannot fire is dim and shows why instead.
     */
    private Table weaponLine(GpuFirePreview.Line line, Color value) {
        boolean available = available(line);
        // Neither available nor a finalizer (impossible, automatic fail; also a weapon not previewed): a number
        // above 12, which no 2d6 roll makes. Its detail is then the modifier list, not a reason.
        boolean aboveTwelve = !available && line.value() != TargetRoll.IMPOSSIBLE
              && line.value() != TargetRoll.AUTOMATIC_FAIL;
        Table row = new Table();
        // Padding 2 0 around a 12-unit line: 20 units per weapon, as measured in shot 02 (CSS 20.2).
        row.pad(2, 0, 2, 0);
        row.defaults().height(16);
        Label weapon = ui.label(line.weapon(), "hud-body", 12, available ? UiTheme.TEXT : UiTheme.DISABLED);
        weapon.setEllipsis(true);
        row.add(weapon).minWidth(0);
        row.add(ui.label(line.location(), "hud-small", 10, UiTheme.MUTED)).padLeft(3);
        row.add().expandX();
        if (available || aboveTwelve) {
            row.add(ui.label(text("GpuBoard.hud.common.targetNumber", shown(line.value())), "hud-name", 12,
                  available ? value : UiTheme.DISABLED)).padLeft(10);
            row.add(ui.label(percent(line.odds()), "hud-body", 12, available ? UiTheme.TEXT
                  : UiTheme.DISABLED)).padLeft(3);
            if (aboveTwelve) {
                // The whole line shows the tooltip, not only its labels.
                row.setTouchable(Touchable.enabled);
                ui.tip(row).getActor().setText(line.detail());
            }
        } else {
            Label reason = ui.label(line.detail(), "hud-body", 12, UiTheme.DISABLED);
            reason.setEllipsis(true);
            row.add(reason).minWidth(0).padLeft(10);
        }
        return row;
    }

    /** The contact's row, kept across rebuilds. */
    private ContactRow contactRow(UnitStatus unit) {
        return contactRows.computeIfAbsent(unit.id(), ContactRow::new);
    }

    /** The distance from the acting or own focus unit to {@code unit}, or -1 when they are not on one board. */
    private int distance(UnitStatus unit) {
        UnitStatus from = unit(UnitRow.acting(shown.status(), shown.focus()));
        return from == null || from.position() == null || unit.position() == null || from.boardId() != unit.boardId()
              ? -1 : from.position().distance(unit.position());
    }

    /**
     * The second line's right part: what keeps the enemy from acting (shut down, prone), else its movement this round
     * with its target modifier, else that it acts now, else its movement points.
     */
    private Line motion(UnitStatus unit) {
        if (unit.destroyed()) {
            return new Line("", Tone.NORMAL);
        } else if (UnitRow.shutDown(unit)) {
            return new Line(text("GpuBoard.hud.state.shutDown"), Tone.BAD);
        } else if (UnitRow.prone(unit)) {
            return new Line(text("GpuBoard.hud.state.prone"), Tone.BAD);
        } else if (!unit.moved().isEmpty()) {
            String moved = text("GpuBoard.hud.forces.moved", unit.moved(), unit.hexesMoved());
            return new Line(unit.tmmShown() ? moved + SEPARATOR + String.format(Locale.ROOT, "%+d", unit.tmm())
                  : moved, Tone.OK);
        }
        GamePhase phase = shown.status().phase();
        Line status = UnitRow.contactLine(unit, shown.status());
        if (phase.isMovement() && status.tone() == Tone.OK) {
            return status;
        }
        return new Line(text("GpuBoard.hud.forces.mp", unit.walk(), unit.run(), unit.jump()), Tone.NORMAL);
    }

    /**
     * A contact's row. Open: the name with its duplicate marker, the model and at the right destroyed, acted or the
     * distance; the pilot with gunnery/piloting and the movement; the armor and structure bars and the heat; the
     * sprite at the window's edge. On the rail: the short name and its state, then mini armor and structure bars or,
     * during the fire preview, the best roll, next to the sprite where the open row has it. A sensor contact shows as
     * a "?" with its distance. A click inspects the enemy, a right click opens its menu.
     */
    private final class ContactRow extends Table {
        private final ClickListener pointer;
        private final Cell<Table> face;
        // Open form.
        private final Table open = new Table();
        private final GpuHudKit.UnitSprite sprite = kit.sprite(36, 28);
        private final Label name = ui.label("", "hud-name", 13.5f, UiTheme.TEXT);
        private final Label marker = ui.label("", "hud-name", 13.5f, UiTheme.CORAL);
        private final Label model = ui.label("", "hud-small", 11, UiTheme.MUTED);
        private final Container<Actor> tag = new Container<>();
        private final Label pilot = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        private final Label skills = ui.label("", "hud-medium", 11.5f, UiTheme.TEXT);
        private final Label detail = ui.label("", "hud-medium", 11.5f, UiTheme.TEXT);
        private final Table bars = new Table();
        private final GpuHudKit.MeterBar armor = kit.bar(66, 5);
        private final GpuHudKit.MeterBar structure = kit.bar(46, 5);
        private final Label structureCaption = ui.label("S", "hud-caption", 10, UiTheme.MUTED);
        private final Label heat = ui.label("", "hud-medium", 11.5f, UiTheme.AMBER);
        private final UiKit.Icon flame = ui.icon("flame", 12, UiTheme.AMBER);
        // Rail form.
        private final Table rail = new Table();
        private final GpuHudKit.UnitSprite railSprite = kit.sprite(36, 28);
        private final Label code = ui.label("", "hud-name", 10.5f, UiTheme.TEXT);
        private final Label codeMarker = ui.label("", "hud-name", 10.5f, UiTheme.CORAL);
        private final Container<Actor> dot = new Container<>();
        private final Table railBars = new Table();
        private final GpuHudKit.MeterBar railArmor = kit.bar(40, 3);
        private final GpuHudKit.MeterBar railStructure = kit.bar(40, 3);
        private final Cell<GpuHudKit.MeterBar> railStructureCell;
        private final Label roll = ui.label("", "hud-medium", 11, Color.WHITE);
        private final Cell<Actor> railLower;
        private boolean highlight;

        private ContactRow(int id) {
            setName("contacts-unit-" + id);
            setTouchable(Touchable.enabled);
            face = add(open).grow();

            name.setEllipsis(true);
            model.setEllipsis(true);
            pilot.setEllipsis(true);
            detail.setEllipsis(true);
            Table line1 = new Table();
            line1.add(name).minWidth(0);
            line1.add(marker).padLeft(4);
            line1.add(model).growX().minWidth(0).padLeft(6).left();
            line1.add(tag).padLeft(6);
            Table line2 = new Table();
            line2.add(pilot).minWidth(0).left();
            line2.add(skills).padLeft(5);
            line2.add().growX();
            line2.add(detail).minWidth(0).padLeft(6).right();
            bars.add(ui.label("A", "hud-caption", 10, UiTheme.MUTED)).padRight(4);
            bars.add(armor).padRight(8);
            bars.add(structureCaption).padRight(4);
            bars.add(structure);
            bars.add().growX();
            bars.add(flame).padRight(2);
            bars.add(heat);
            Table text = new Table();
            text.defaults().growX().minWidth(0);
            text.add(line1).height(15).row();
            text.add(line2).height(14).row();
            text.add(bars).height(11);
            open.pad(0, 10, 0, 8);
            open.add(text).growX().minWidth(0);
            open.add(sprite).width(42).padLeft(8);

            code.setEllipsis(true);
            Table codeLine = new Table();
            codeLine.add(code).minWidth(0);
            codeLine.add(codeMarker).padLeft(2);
            codeLine.add().growX();
            codeLine.add(dot).padLeft(3);
            railBars.add(railArmor).growX().height(3).padTop(4).row();
            railStructureCell = railBars.add(railStructure).growX().height(3).padTop(2);
            Table side = new Table();
            side.add(codeLine).growX().minWidth(0).height(15).row();
            railLower = side.add((Actor) railBars).growX().minWidth(0).left();
            rail.pad(0, 9, 0, 8);
            rail.add(side).growX().minWidth(0);
            rail.add(railSprite).width(42).padLeft(6);

            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    // The fire preview opens the inspected enemy's row; a click on the open row closes it (F6).
                    if (shown.preview().active() && state.inspected == id) {
                        state.inspected = Entity.NONE;
                    } else {
                        select.accept(id);
                    }
                }
            };
            addListener(pointer);
            addListener(UnitRow.menuOpener(menu, id, GpuContactsPanel.this::unit));
        }

        /** Shows {@code unit}, with its fire preview {@code contact} on the rail during the preview (else null). */
        ContactRow show(UnitStatus unit, Contact contact) {
            Table wanted = shown.folded() ? rail : open;
            if (face.getActor() != wanted) {
                face.setActor(wanted);
            }
            boolean sensor = unit.sensorContact();
            boolean acted = UnitRow.acted(unit, shown.status().phase());
            int hexes = distance(unit);
            String away = hexes < 0 ? "" : text("GpuBoard.hud.common.hexDistance", hexes);
            boolean hasStructure = unit.marks().structureArgb() != 0 || unit.marks().equals(GpuBattleStatus.Marks.NONE);
            float fade = 1;
            if (shown.folded()) {
                railSprite.set(sensor ? null : unit.icon(), sensor ? UiTheme.BLIP : UiTheme.CORAL);
                code.setText(sensor ? away : GpuForcesPanel.code(unit));
                code.setColor(sensor ? UiTheme.MUTED : UiTheme.TEXT);
                codeMarker.setText(sensor ? "" : GpuForcesPanel.marker(unit));
                dot.setActor(unit.destroyed() ? ui.icon("close", 11, UiTheme.CORAL)
                      : acted ? ui.icon("check", 11, UiTheme.MUTED) : null);
                if (sensor) {
                    railLower.setActor(null);
                } else if (contact != null) {
                    GpuFirePreview.Side out = contact.outgoing();
                    boolean shot = out.available() > 0;
                    roll.setText(shot ? text("GpuBoard.hud.common.targetNumber", shown(out.best())) + SEPARATOR
                          + percent(out.odds()) : "\u2014");
                    roll.setColor(shot ? Color.WHITE : UiTheme.MUTED);
                    railLower.setActor(roll);
                    fade = (shot ? 1 : NO_SHOT_ALPHA) * (shown.preview().complete() ? 1 : UPDATING_ALPHA);
                } else {
                    railArmor.set(unit.armor(), unit.marks().armorArgb());
                    railStructure.set(unit.structure(), unit.marks().structureArgb());
                    railStructure.setVisible(hasStructure);
                    railStructureCell.height(hasStructure ? 3 : 0).padTop(hasStructure ? 2 : 0);
                    railLower.setActor(railBars);
                }
            } else {
                sprite.set(sensor ? null : unit.icon(), sensor ? UiTheme.BLIP : UiTheme.CORAL);
                name.setText(UiTheme.upper(sensor ? text(SENSOR_CONTACT) : unit.chassis()));
                marker.setText(sensor ? "" : GpuForcesPanel.marker(unit));
                model.setText(sensor ? "" : unit.model());
                tag.setActor(unit.destroyed() ? ui.label(UiTheme.upper(text("GpuBoard.hud.state.destroyed")),
                      "hud-mini", 10, UiTheme.CORAL) : acted ? ui.icon("check", 14, UiTheme.MUTED)
                      : away.isEmpty() ? null : ui.label(away, "hud-medium", 11, UiTheme.MUTED));
                boolean known = !sensor && !unit.pilot().isBlank();
                pilot.setText(sensor ? text("GpuBoard.hud.status.sensorReturn") : known ? unit.pilot() : "");
                skills.setText(known ? unit.gunnery() + "/" + unit.piloting() : "");
                Line line = sensor ? new Line("", Tone.NORMAL) : motion(unit);
                detail.setText(line.text());
                detail.setColor(line.tone() == Tone.OK ? MOVED : line.tone() == Tone.NORMAL ? UiTheme.TEXT
                      : line.tone().color);
                bars.setVisible(!sensor);
                armor.set(unit.armor(), unit.marks().armorArgb());
                structure.set(unit.structure(), unit.marks().structureArgb());
                structure.setVisible(hasStructure);
                structureCaption.setVisible(hasStructure);
                boolean hot = !unit.heatEffects().isEmpty();
                flame.setVisible(unit.heat() > 0);
                flame.setColor(hot ? HOT : UiTheme.AMBER);
                heat.setText(unit.heat() > 0 ? String.valueOf(unit.heat()) : "");
                heat.setColor(hot ? HOT : UiTheme.AMBER);
            }
            highlight = unit.id() == shown.card();
            getColor().a = (unit.destroyed() ? .38f : acted ? .62f : 1) * fade;
            return this;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            if (highlight) {
                ui.fill(batch, INSPECTED, parentAlpha, getX(), getY(), getWidth(), getHeight());
                ui.fill(batch, UiTheme.CORAL, parentAlpha, getX() + getWidth() - 3, getY(), 3, getHeight());
            }
            if (pointer.isOver()) {
                ui.fill(batch, HOVER, parentAlpha, getX(), getY(), getWidth(), getHeight());
            }
            ui.fill(batch, SEPARATOR_LINE, parentAlpha, getX(), getY(), getWidth(), 1);
            batch.setPackedColor(previous);
            super.draw(batch, parentAlpha);
        }
    }

    private UnitStatus unit(int id) {
        return shown.units().stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /**
     * A salvo's pose in a row (F5): torso forward or twisted left or right, a turret by the direction it faces;
     * {@code facing} is the attacker's facing.
     */
    private static String pose(GpuFirePreview.Side side, int facing) {
        if (side.turret()) {
            return text("GpuBoard.hud.contacts.turret", facing(facing + side.twist()));
        }
        return text(side.twist() == 0 ? "GpuBoard.hud.contacts.torsoForward"
              : side.twist() < 0 ? "GpuBoard.hud.contacts.twistLeft" : "GpuBoard.hud.contacts.twistRight");
    }

    /** The pose in the detail header, a twist with its angle (the mock's "Twist left 60 degrees"). */
    private static String detailPose(GpuFirePreview.Side side, int facing) {
        String pose = pose(side, facing);
        return side.turret() || side.twist() == 0 ? pose
              : text("GpuBoard.hud.contacts.twistDegrees", pose, Math.abs(side.twist()) * HEXSIDE_DEGREES);
    }

    /** A line's roll can succeed; the preview gives odds only to those. */
    private static boolean available(GpuFirePreview.Line line) {
        return line.odds() > 0;
    }
}
