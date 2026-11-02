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
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.clientGUI.boardview.gpu.GpuFirePreview.Contact;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.UnitRow;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;

/**
 * Contacts and the movement fire preview in the right column (C.1 G5). Without an active preview it lists the enemy
 * units; with one it shows where the previewed unit fires from, each enemy's best salvo both ways and the open row's
 * weapons, with the Outgoing and Incoming guide toggles. The highlighted row, and the preview's open row, is the unit
 * the card shows: a row's click inspects its enemy. It presents the snapshots and
 * opens unit menus; the client computes every value.
 */
final class GpuContactsPanel implements GpuHud.Component {
    private static final String UNIDENTIFIED = "GpuBoard.hud.common.unidentified";
    private static final String SENSOR_CONTACT = "GpuBoard.hud.common.sensorContact";
    private static final String SEPARATOR = " \u00B7 ";
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
          int focus, int card) { }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final IntConsumer select;
    private final Table root;
    private final Cell<Actor> headerCell;
    private final Table previewHeader;
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
    /** The contact list's footer (its rule and line), which the fire preview does not have. */
    private final Table footer = new Table();
    private final Cell<Actor> footerCell;
    private final Label contactsFoot;
    private final Drawable openFill;
    private final Drawable detailEdges;
    /** The plain contact rows, kept across rebuilds so that their pointer state and tooltips stay with them. */
    private final Map<Integer, UnitRow> contactRows = new HashMap<>();
    private final Map<Integer, PreviewRow> previewRows = new HashMap<>();
    private boolean outgoing = true;
    private boolean incoming = true;
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
        previewHeader = ui.header(text("GpuBoard.hud.common.contacts"), null, outgoingToggle, incomingToggle);
        previewHeader.setName("contacts-header");
        headerCell = root.add((Actor) null).growX();
        root.row();

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
        from = new Container<>(block).fillX().pad(0, 10, 8, 10);
        fromCell = root.add((Actor) null).growX();
        root.row();

        list.top();
        ScrollPane scroll = ui.scrollList(list);
        scroll.setName("contacts-list");
        root.add(scroll).growX().row();

        Table foot = ui.footer(footer);
        foot.setName("contacts-footer");
        contactsFoot = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        contactsFoot.setEllipsis(true);
        foot.add(contactsFoot).growX().minWidth(0).left();
        footerCell = root.add((Actor) null).growX();
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        View view = new View(inputs.frame().panels().preview(), state.presentedUnits(), inputs.frame().status(),
              state.focus(), state.cardUnit());
        if (!view.equals(shown)) {
            shown = view;
            rebuild();
        }
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
        list.clearChildren();
        footerCell.setActor(previewing ? null : footer);
        if (previewing) {
            headerCell.setActor(previewHeader);
            fromCell.setActor(from);
            showFrom(shown.preview());
            previewRows(shown.preview());
        } else {
            Table header = ui.header(text("GpuBoard.hud.common.contacts"), String.valueOf(enemies.size()));
            header.setName("contacts-header");
            headerCell.setActor(header);
            fromCell.setActor(null);
            enemies.forEach(unit -> list.add(contactRow(unit)).growX().minWidth(0).pad(0, 10, 6, 10).row());
            contactsFoot.setText(text(enemies.stream().anyMatch(UnitStatus::sensorContact)
                  ? "GpuBoard.hud.contacts.includesUnidentified" : "GpuBoard.hud.contacts.allIdentified"));
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

    /**
     * A plain contact row (C12, F12): the enemy's sprite, model, name and status line, and at the right whether it
     * is destroyed, has acted or runs hot, or its distance from the acting unit; the forces navigator's rows show the
     * same.
     */
    private UnitRow contactRow(UnitStatus unit) {
        UnitRow row = contactRows.computeIfAbsent(unit.id(), id -> {
            UnitRow created = kit.unitRow(40, 34);
            created.setName("contacts-unit-" + id);
            // The HUD's one selection rule, which inspects an enemy (C7).
            onChange(created, () -> select.accept(id));
            created.addListener(UnitRow.menuOpener(menu, id, this::unit));
            return created;
        });
        GpuBattleStatus.Snapshot status = shown.status();
        return row.show(unit, UnitRow.contactLine(unit, status), status.phase(), unit.id() == shown.card(),
              unit(UnitRow.acting(status, shown.focus())));
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
