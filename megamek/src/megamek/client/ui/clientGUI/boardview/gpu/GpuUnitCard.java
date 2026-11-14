/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.SheetTab;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.DamageTier;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay.DeployCommand;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * A glance card: the own unit at bottom left, or an inspected non-owned unit at bottom right. Its fixed rows show
 * the unit's paperdoll with a number per location (a Mek's rear armor in a strip under it), four vitals rows by family,
 * one line of chips for what the doll cannot show, and the Unit record and Locate buttons. While the sheet is open it
 * is the one-line mini card; a sensor contact gets the compact contact card. Each element opens the sheet's tab that
 * explains it; hovering the doll shows the hover card on the popover layer. It also holds the one mapping of a record
 * to doll cells and the chip rule that the sheet shares.
 */
final class GpuUnitCard implements GpuHud.Component {
    /** The doll slot (84 wide; a Mek's rear strip of 18, 16 low, 4 under the doll) and the gap to the vitals. */
    private static final float DOLL_SLOT = 84;
    private static final float WIDE_DOLL = 90;
    private static final float REAR_STRIP = 18;
    private static final float LOW_REAR_STRIP = 16;
    private static final float CHIP_GAP = 5;
    /** The rear strip's order, as the locations stand on the doll. */
    private static final List<String> REAR_ORDER = List.of("LT", "CT", "RT");
    /** The hover card's width (4.5) and its distance from the card. */
    private static final float HOVER_WIDTH = 250;
    private static final String SEPARATOR = " · ";
    /** How long a playback change "−n" stays (6.5). */
    private static final long DELTA_MILLIS = 2000;

    /** A chip (4.4): its text, its tone and the sheet tab of the section that explains it. */
    record Chip(String text, Tone tone, SheetTab home) { }

    /**
     * The playback's "−n" (R2-8, 6.5): what each location lost between the two last presented records of the unit, by
     * code, for its front armor, rear armor and structure; shown until the next loss or for two seconds.
     */
    record Deltas(Map<String, Integer> front, Map<String, Integer> rear, Map<String, Integer> structure, long until) {
        static final Deltas NONE = new Deltas(Map.of(), Map.of(), Map.of(), 0);

        Deltas {
            front = Map.copyOf(front);
            rear = Map.copyOf(rear);
            structure = Map.copyOf(structure);
        }

        /** The deltas after the presented record changed from {@code previous}; a change without a loss keeps these. */
        Deltas next(GpuUnitRecord.Snapshot previous, GpuUnitRecord.Snapshot record, long now) {
            if (previous == record || previous.unitId() != record.unitId() || previous.unitId() == Entity.NONE) {
                return this;
            }
            Map<String, Integer> lostFront = new HashMap<>();
            Map<String, Integer> lostRear = new HashMap<>();
            Map<String, Integer> lostStructure = new HashMap<>();
            for (GpuUnitRecord.Location location : record.locations()) {
                GpuUnitRecord.Location before = previous.locations().stream()
                      .filter(candidate -> candidate.abbr().equals(location.abbr())).findFirst().orElse(null);
                if (before != null) {
                    lost(lostFront, location.abbr(), before.armor() - location.armor());
                    lost(lostRear, location.abbr(), before.rear() - location.rear());
                    lost(lostStructure, location.abbr(), before.internal() - location.internal());
                }
            }
            boolean any = !lostFront.isEmpty() || !lostRear.isEmpty() || !lostStructure.isEmpty();
            return any ? new Deltas(lostFront, lostRear, lostStructure, now + DELTA_MILLIS) : this;
        }

        private static void lost(Map<String, Integer> lost, String code, int points) {
            if (points > 0) {
                lost.put(code, points);
            }
        }

        /** These deltas while they last, else none. */
        Deltas shown(long now) {
            return now < until ? this : NONE;
        }
    }

    /** The long range's index in a weapon's ranges (minimum, short, medium, long). */
    private static final int LONG_RANGE = 3;

    /** What the card shows; it is rebuilt when this changes. Snapshots keep their identity while unchanged. */
    private record Shown(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record, GpuFireOrders.Heat heat,
          boolean low, GamePhase phase, boolean mini, boolean sheetSelected, Deltas deltas,
          List<GpuBoardSource.Bind> binds, List<Setup> setup) { }

    /** A deployment command of the placed unit as the card shows it; the card runs it by id at the click. */
    private record Setup(String id, String label, boolean enabled) { }

    /** The setup choices MegaMek offers a unit before it deploys, in its order; the card shows the enabled ones. */
    private static final List<String> SETUP = List.of(DeployCommand.DEPLOY_LOAD.getCmd(),
          DeployCommand.DEPLOY_UNLOAD.getCmd(), DeployCommand.DEPLOY_HULL_DOWN.getCmd(),
          DeployCommand.DEPLOY_ASSAULT_DROP.getCmd(), DeployCommand.DEPLOY_DOCK.getCmd());
    private static final String WITHDRAW = DeployCommand.DEPLOY_REMOVE.getCmd();

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final GpuPaperdolls paperdolls;
    private final boolean other;
    private final Table root;
    private final Drawable friendPanel;
    private final Drawable foePanel;
    private final GpuPaperdoll doll;
    /** The window-sized layer of the hover card, which GpuHud places on its popover layer. */
    private final Group overlay = new Group();
    private final Table hoverCard = new Table();
    /** The shown unit, or {@code Entity.NONE}; the buttons act on it. */
    private int shownId = Entity.NONE;
    private Shown shown;
    private InputListener opener;
    private GpuUnitRecord.Snapshot previousRecord = GpuUnitRecord.Snapshot.EMPTY;
    private Deltas deltas = Deltas.NONE;
    /** The phase commands of the last frame; the deployment card's setup buttons run theirs. */
    private List<BoardScene.Command> commands = List.of();

    GpuUnitCard(GpuHudKit kit, GpuBoardSource source, GpuHudState state, GpuContextMenu menu,
          GpuPaperdolls paperdolls, boolean other) {
        this.kit = kit;
        ui = kit.ui;
        this.source = source;
        this.state = state;
        this.menu = menu;
        this.paperdolls = paperdolls;
        this.other = other;
        root = ui.panel();
        root.setName(name(""));
        friendPanel = ui.skin.getDrawable("panel");
        foePanel = ui.skin.getDrawable("panel-foe");
        doll = new GpuPaperdoll(ui.skin);
        doll.setName(name("-doll"));
        doll.setTouchable(Touchable.enabled);
        doll.addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                hover(doll.pick(x, y), event.getStageX(), event.getStageY());
                return false;
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                hover(null, 0, 0);
            }
        });
        doll.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                // The ARMOR tab opens on the clicked location: its inspector shows it.
                String picked = doll.pick(x, y);
                if (picked != null) {
                    state.selectedLocation = picked;
                }
                showSheet(SheetTab.ARMOR);
            }
        });
        overlay.setTouchable(Touchable.disabled);
        hoverCard.setBackground(ui.skin.getDrawable("panel-pop"));
        hoverCard.setName(name("-hover"));
        hoverCard.setVisible(false);
        overlay.addActor(hoverCard);
    }

    /** A unit as its row names it: the chassis, or the whole name when there is none. */
    static String unitName(GpuBattleStatus.UnitStatus unit) {
        return unit.chassis().isEmpty() ? unit.name() : unit.chassis();
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** The popover layer's part of the card: a window-sized group holding its hover card. */
    Actor overlay() {
        return overlay;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuBattleStatus.UnitStatus unit = state.presented(other ? state.otherCardUnit() : state.ownCardUnit());
        root.setVisible(unit != null);
        if (unit == null) {
            shownId = Entity.NONE;
            hover(null, 0, 0);
            return;
        }
        if (unit.id() != shownId) {
            shownId = unit.id();
            hover(null, 0, 0);
            if (opener != null) {
                root.removeListener(opener);
            }
            // The card's right click opens the unit menu, as the unit's row does (G12).
            opener = GpuHudKit.UnitRow.menuOpener(menu, unit.id(), state::presented);
            root.addListener(opener);
        }
        // The source records the card unit at its next capture; until then the previous unit's record is not shown.
        GpuUnitRecord.Snapshot unitRecord = state.presentedRecord(unit.id());
        if (unitRecord.unitId() != unit.id()) {
            unitRecord = GpuUnitRecord.Snapshot.EMPTY;
        }
        long now = TimeUtils.millis();
        deltas = deltas.next(previousRecord, unitRecord, now);
        previousRecord = unitRecord;
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        GpuFireOrders.Heat forecast = fire.active() && fire.actorId() == unit.id() ? fire.heat() : null;
        BoardScene scene = inputs.frame().scene();
        commands = scene == null ? List.of() : scene.commands();
        Shown next = new Shown(unit, unitRecord, forecast, inputs.metrics().lowHeight(),
              inputs.frame().status().phase(), !other && GpuRecordSheet.open(state),
              state.recordOpen && state.cardUnit() == unit.id(), deltas.shown(now),
              inputs.preferences().binds(), setup(inputs.frame().status(), unit));
        if (!next.equals(shown)) {
            shown = next;
            show(next, inputs.preferences());
        }
    }

    private void show(Shown next, GpuBoardSource.UiPreferences preferences) {
        GpuBattleStatus.UnitStatus unit = next.unit();
        boolean enemy = unit.side() == GpuBattleStatus.Side.ENEMY;
        root.setBackground(enemy && !unit.sensorContact() ? foePanel : friendPanel);
        root.clearChildren();
        if (unit.sensorContact()) {
            contact(unit, next, preferences);
        } else if (next.phase().isDeployment()) {
            // The deployment card keeps its form while the record is open, so neither it nor the queue jumps.
            deployment(unit, next);
        } else if (next.mini()) {
            mini(unit, next, preferences);
        } else {
            full(unit, next, preferences);
        }
    }

    // ------------------------------------------------------------------------------------------------ the card forms

    /**
     * The deployment card: what decides where a unit goes, beside the force's cards. The header and the doll as the
     * full card has them (a unit may start damaged), and four rows: the walk, run and jump MP, the usable weapons, the
     * longest reach of those and the armor left; the Unit record below them. No heat, movement or chips yet.
     */
    private void deployment(GpuBattleStatus.UnitStatus unit, Shown next) {
        root.pad(12, 14, 12, 14);
        root.add(header(unit, next, true)).growX().height(40).row();
        Table body = new Table();
        body.top().left();
        body.add(dollSlot(unit, next, true)).width(dollWidth(next.record())).growY().top();
        Table rows = new Table();
        rows.top().left();
        rows.defaults().growX().left().padTop(5);
        String jump = unit.jump() > 0 ? " / " + unit.jump() : "";
        rows.add(row(text("GpuBoard.hud.deploy.move"), bold(unit.walk() + " / " + unit.run() + jump, ""),
              SheetTab.STATUS)).padTop(2).row();
        GpuUnitRecord.Snapshot record = next.record();
        // The rows stand from the first frame, with dashes until the record arrives, so the card never reflows.
        boolean recorded = !record.weapons().isEmpty();
        rows.add(row(text("GpuBoard.hud.common.weapons"), recorded ? bold(Integer.toString(usableWeapons(record)),
              " " + text("GpuBoard.hud.unit.ofListed", record.weapons().size())) : value("—", UiTheme.MUTED),
              SheetTab.WEAPONS)).row();
        int reach = recorded ? reach(record) : 0;
        rows.add(row(text("GpuBoard.hud.deploy.range"), reach > 0 ? bold(Integer.toString(reach),
              " " + text("GpuBoard.hud.deploy.hexes")) : value("—", UiTheme.MUTED), SheetTab.WEAPONS)).row();
        int armor = unit.armor() >= 0 ? Math.round((float) unit.armor() * 100) : -1;
        rows.add(row(text("GpuBoard.hud.deploy.armor"), armor >= 0 ? value(text("GpuBoard.hud.common.percent", armor),
              armor < 100 ? UiTheme.AMBER : UiTheme.ACCENT) : value("—", UiTheme.MUTED), SheetTab.ARMOR)).row();
        body.add(rows).grow().minWidth(0).top().padLeft(12);
        root.add(body).growX().height(100).padTop(6).row();
        // One row whatever the unit offers, so the card keeps its height: the record, MegaMek's setup choices for
        // this unit, and Withdraw at the end.
        Table actions = new Table();
        actions.left();
        UiButton sheet = ui.button("hud-mini", null, text("GpuBoard.hud.common.unitRecord"), null);
        sheet.setName(name("-record"));
        sheet.pressed(next.sheetSelected());
        onChange(sheet, () -> {
            if (state.recordOpen && state.cardUnit() == shownId) { state.recordOpen = false; }
            else { showSheet(state.sheetTab); }
        });
        actions.add(sheet);
        Setup withdraw = null;
        for (Setup setup : next.setup()) {
            if (setup.id().equals(WITHDRAW)) {
                withdraw = setup;
                continue;
            }
            UiButton button = ui.button("hud-mini", null, setup.label(), null);
            button.setName(name("-setup-" + setup.id()));
            onChange(button, () -> runCommand(setup.id()));
            actions.add(button).padLeft(6);
        }
        actions.add().expandX();
        if (withdraw != null) {
            UiButton remove = ui.button("hud-delete-icon", "delete", null, null);
            remove.setName(name("-withdraw"));
            remove.setDisabled(!withdraw.enabled());
            ui.tip(remove).getActor().setText(text("GpuBoard.hud.deploy.withdrawTip"));
            onChange(remove, () -> runCommand(WITHDRAW));
            actions.add(remove).size(26);
        }
        root.add(actions).growX().height(26).padTop(8);
    }

    /**
     * The placed unit's setup commands, in its own deployment turn only: the enabled setup choices, then Withdraw
     * (enabled or not, so the row does not jump). Empty for any other unit, phase or turn.
     */
    private List<Setup> setup(GpuBattleStatus.Snapshot status, GpuBattleStatus.UnitStatus unit) {
        if (other || !status.phase().isDeployment() || !status.myTurn() || status.actorId() != unit.id()) {
            return List.of();
        }
        List<Setup> setup = new ArrayList<>();
        for (String id : SETUP) {
            BoardScene.Command command = command(id);
            if (command != null && command.enabled()) {
                setup.add(new Setup(id, command.label(), true));
            }
        }
        BoardScene.Command withdraw = command(WITHDRAW);
        if (withdraw != null) {
            setup.add(new Setup(WITHDRAW, withdraw.label(), withdraw.enabled()));
        }
        return setup;
    }

    private BoardScene.Command command(String id) {
        for (BoardScene.Command command : commands) {
            if (command.id().equals(id)) {
                return command;
            }
        }
        return null;
    }

    /** Runs the phase command with this id as the last frame showed it, while it is enabled. */
    private void runCommand(String id) {
        BoardScene.Command command = command(id);
        if (command != null && command.enabled()) {
            command.action().run();
        }
    }

    /** The longest long range of the usable weapons, in hexes; 0 without one. */
    private static int reach(GpuUnitRecord.Snapshot record) {
        int reach = 0;
        for (GpuUnitRecord.RecordWeapon weapon : record.weapons()) {
            if (!weapon.destroyed() && !weapon.crippled() && weapon.ranges().size() > LONG_RANGE) {
                reach = Math.max(reach, weapon.ranges().get(LONG_RANGE));
            }
        }
        return reach;
    }

    /**
     * The full card (3.2): header, body (doll slot and vitals), the chip line and the buttons in fixed rows, 290 units
     * tall (252 at 800 units of height or less), so a heavily damaged unit's card is as tall as an undamaged one's.
     */
    private void full(GpuBattleStatus.UnitStatus unit, Shown next, GpuBoardSource.UiPreferences preferences) {
        boolean low = next.low();
        // #unit padding inside the 2-unit rails and transparent side borders (12 14 12; low 10 12 12).
        root.pad(low ? 12 : 14, low ? 14 : 16, 14, low ? 14 : 16);
        root.add(header(unit, next, low)).growX().height(low ? 40 : 44).row();
        Table body = new Table();
        body.top().left();
        body.add(dollSlot(unit, next, low)).width(dollWidth(next.record())).growY().top();
        body.add(vitals(unit, next, low)).growX().minWidth(0).top().padLeft(low ? 10 : 16);
        float bodyHeight = low ? 114 : 136;
        root.add(body).growX().height(bodyHeight).padTop(low ? 6 : 8).row();
        Table chips = new Table();
        chips.setName(name("-chips"));
        chips.left();
        float room = (low ? 250 : 300) - 2 * (low ? 14 : 16);
        fillChips(ui, chips, chips(unit, next.record(), next.phase()), room, chip -> showSheet(chip.home()));
        root.add(chips).growX().height(22).padTop(low ? 6 : 8).row();
        root.add(buttons(unit, next, preferences, low)).growX().height(low ? 30 : 34).padTop(low ? 8 : 10);
    }

    /** The mini card (R3-1), 44 units: one line of name, damage level, the pressed Unit record and Locate. */
    private void mini(GpuBattleStatus.UnitStatus unit, Shown next, GpuBoardSource.UiPreferences preferences) {
        root.pad(8, 16, 8, 10);
        Table line = new Table();
        Label name = ui.label(UiTheme.upper(unitName(unit)), "hud-title", 16, unit.side() == GpuBattleStatus.Side.ENEMY
              ? UiTheme.CORAL : UiTheme.TEXT);
        name.setEllipsis(true);
        name.setName(name("-name"));
        line.add(name).minWidth(0).padRight(8);
        line.add(damageLevel(unit, next.record())).padRight(8);
        line.add().expandX();
        UiButton record = ui.button("hud-icon", "chevron-down", null, null);
        record.setName(name("-record"));
        record.pressed(true);
        ui.tip(record).getActor().setText(text("GpuBoard.hud.unit.recordTip",
              GpuHintLine.key(preferences, KeyCommandBind.UNIT_DISPLAY)));
        onChange(record, () -> state.recordOpen = !state.recordOpen);
        line.add(record).size(26).padRight(4);
        line.add(locate(unit, false, preferences)).size(26);
        root.add(line).growX().height(28);
    }

    /**
     * A sensor contact's card (7), 184 units: what the client knows, the blip, Unit record disabled, Locate and the
     * ✕.
     */
    private void contact(GpuBattleStatus.UnitStatus unit, Shown next, GpuBoardSource.UiPreferences preferences) {
        root.pad(14, 16, 14, 16);
        Table header = new Table();
        header.left();
        header.add(new Label(UiTheme.upper(text("GpuBoard.hud.common.sensorContact")), ui.skin, "hud-heading"))
              .left().expandX();
        header.add(closeButton()).size(28).right();
        header.row();
        String hex = unit.position() == null ? "" : unit.position().getBoardNum();
        Label subtitle = ui.label(text("GpuBoard.hud.common.identityUnknown", hex), "hud-body", 12, UiTheme.MUTED);
        header.add(subtitle).left().colspan(2);
        root.add(header).growX().height(44).row();
        Table blip = new Table();
        blip.setBackground(new UiTheme.EdgeBox(ui.skin.get("white", Texture.class), null,
              UiTheme.alpha(UiTheme.BLIP, .35f), 1, 1, 1, 1).dashed(3));
        blip.add(ui.label("?", "hud-heading", 28, UiTheme.BLIP));
        root.add(blip).size(WIDE_DOLL, 60).left().padTop(8).row();
        Table buttons = new Table();
        UiButton record = ui.button("hud-plain", "report", text("GpuBoard.hud.common.unitRecord"), null);
        record.setName(name("-record"));
        record.setDisabled(true);
        ui.tip(record).getActor().setText(text("GpuBoard.hud.unit.noRecord"));
        buttons.add(record).growX().uniformX().minHeight(34).padRight(8);
        buttons.add(locate(unit, true, preferences)).growX().uniformX().minHeight(34);
        root.add(buttons).growX().padTop(10);
    }

    /** The header (4.1): name, damage level and the ✕ over the sub-line. */
    private Table header(GpuBattleStatus.UnitStatus unit, Shown next, boolean low) {
        Table header = new Table();
        header.left();
        boolean enemy = unit.side() == GpuBattleStatus.Side.ENEMY;
        Label name = ui.label(UiTheme.upper(unitName(unit)), "hud-heading", low ? 18 : 20, enemy ? UiTheme.CORAL
              : UiTheme.TEXT);
        name.setEllipsis(true);
        name.setName(name("-name"));
        ui.tip(name).getActor().setText(unit.name());
        header.add(name).minWidth(0).growX().left().height(low ? 22 : 24);
        header.add(damageLevel(unit, next.record())).right().padLeft(8);
        header.add(closeButton()).size(28).padLeft(2).padRight(-6);
        header.row();
        Label subtitle = ui.label(subtitle(unit), "hud-body", low ? 11 : 12, UiTheme.MUTED);
        subtitle.setEllipsis(true);
        header.add(subtitle).colspan(3).left().minWidth(0).growX().height(low ? 16 : 17);
        return header;
    }

    /** The sub-line (4.1): model, tons and pilot, or an ally's player, a carrier, a removed unit. */
    private String subtitle(GpuBattleStatus.UnitStatus unit) {
        GpuUnitRecord.Snapshot record = shown == null ? GpuUnitRecord.Snapshot.EMPTY : shown.record();
        if (record.removed()) {
            return text("GpuBoard.hud.unit.removed");
        }
        String carrier = record.info().stream().filter(row -> row.section() == GpuUnitRecord.InfoSection.CARRIER)
              .map(GpuUnitRecord.InfoRow::text).findFirst().orElse("");
        if (!carrier.isEmpty()) {
            return text("GpuBoard.hud.unit.carriedBy", carrier);
        }
        // A unit without a model name starts with its weight.
        String subtitle = text("GpuBoard.hud.unit.subtitle", unit.model(), unit.tons(), unit.pilot(), unit.gunnery(),
              unit.piloting());
        return unit.model().isBlank() ? subtitle.substring(subtitle.indexOf(SEPARATOR.strip()) + 1).strip()
              : subtitle;
    }

    /**
     * The damage level (4.1) after the unit's damage tile, the mark its nameplate shows, so the board and the card
     * read alike; no tile while the unit has none (undamaged, or the client hides damage levels). A destroyed unit's
     * level is a coral tag. The totals are its tooltip.
     */
    private Label damageLevel(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record) {
        String text = record.damageLevel().isEmpty() ? "" : UiTheme.upper(record.damageLevel());
        Label level = ui.label(text, "hud-name", 10.5f, unit.destroyed() ? UiTheme.FILL_INK : UiTheme.ACCENT);
        level.setName(name("-damage"));
        Label.LabelStyle style = level.getStyle();
        if (unit.destroyed()) {
            // A destroyed unit's level is a coral tag (4.1).
            Drawable tag = ui.skin.newDrawable("white", UiTheme.CORAL);
            tag.setLeftWidth(5);
            tag.setRightWidth(5);
            tag.setTopHeight(1);
            tag.setBottomHeight(1);
            style.background = tag;
        } else if (unit.marks().damageArgb() != 0) {
            Color tile = new Color();
            Color.argb8888ToColor(tile, unit.marks().damageArgb());
            style.background = kit.damageTile(tile);
        }
        level.setStyle(style);
        int[] sums = totals(record);
        if (record.unitId() != Entity.NONE) {
            ui.tip(level).getActor().setText(sums[3] > 0 ? text("GpuBoard.hud.unit.totals", sums[0], sums[1],
                  sums[2], sums[3]) : text("GpuBoard.hud.unit.armorTotal", sums[0], sums[1]));
        }
        clicks(level, SheetTab.ARMOR);
        return level;
    }

    /** Closing the secondary inspector leaves the own selection and its commands intact. */
    private UiButton closeButton() {
        UiButton close = ui.closeButton(() -> {
            if (other) { state.inspected = Entity.NONE; }
            else { state.clearSelection(); }
        });
        close.setName(name("-close"));
        return close;
    }

    /**
     * Locate (4.1): the unit's camera frame; disabled with its reason off the board. The icon alone names itself and
     * its key in its tooltip.
     */
    private UiButton locate(GpuBattleStatus.UnitStatus unit, boolean labelled,
          GpuBoardSource.UiPreferences preferences) {
        UiButton locate = labelled ? ui.button("hud-plain", "locate", text("GpuBoard.hud.common.locate"), null)
              : ui.button("hud-icon", "locate", null, null);
        locate.setName(name(unit.sensorContact() ? "-contact-locate" : "-locate"));
        boolean placed = unit.position() != null;
        locate.setDisabled(!placed);
        if (!placed) {
            ui.tip(locate).getActor().setText(text("GpuBoard.hud.unit.notPlaced"));
        } else if (!labelled) {
            ui.tip(locate).getActor().setText(text("GpuBoard.hud.unit.locateTip",
                  GpuHintLine.key(preferences, KeyCommandBind.CENTER_ON_SELECTED)));
        }
        onChange(locate, () -> {
            if (!locate.isDisabled()) {
                source.locateUnit(shownId);
            }
        });
        return locate;
    }

    /** The button row (.brow): Unit record (a toggle, 1.4 of the width) and Locate. */
    private Table buttons(GpuBattleStatus.UnitStatus unit, Shown next, GpuBoardSource.UiPreferences preferences,
          boolean low) {
        Table buttons = new Table();
        UiButton record = ui.button("hud-plain", "report", text("GpuBoard.hud.common.unitRecord"), null);
        record.setName(name("-record"));
        record.pressed(next.sheetSelected());
        onChange(record, () -> {
            if (state.recordOpen && state.cardUnit() == shownId) { state.recordOpen = false; }
            else { showSheet(state.sheetTab); }
        });
        UiButton locate = locate(unit, true, preferences);
        float height = low ? 30 : 34;
        // .brow .b:first-child flex 1.4 against Locate's 1, 8 apart.
        float width = (low ? 250 : 300) - 2 * (low ? 14 : 16) - 8;
        buttons.add(record).width(width * 1.4f / 2.4f).height(height).padRight(8);
        buttons.add(locate).width(width / 2.4f).height(height);
        return buttons;
    }

    // ------------------------------------------------------------------------------------------------ the doll slot

    private static float dollWidth(GpuUnitRecord.Snapshot record) {
        return record.hitTracks().isEmpty() ? WIDE_DOLL : DOLL_SLOT;
    }

    /**
     * The doll slot (4.2): a Mek's front armor doll over its rear strip; any other unit with a drawing across the
     * slot, its structure boxes by fill only; tiles for a unit without one.
     */
    private Table dollSlot(GpuBattleStatus.UnitStatus unit, Shown next, boolean low) {
        Table slot = new Table();
        slot.top();
        GpuUnitRecord.Snapshot record = next.record();
        GpuPaperdolls.Doll front = doll(paperdolls, record, GpuPaperdolls.ARMOR);
        boolean mek = !record.hitTracks().isEmpty();
        if (front != null) {
            Map<String, GpuPaperdoll.Cell> structure = new HashMap<>();
            if (!mek) {
                // The card's structure boxes show their fill only; the sheet prints their numbers.
                structureCells(record, View.CARD, next.deltas()).forEach((code, cell) -> structure.put(code,
                      new GpuPaperdoll.Cell(cell.tier(), "", false, cell.breached(), 0)));
            }
            doll.show(front, frontCells(record, View.CARD, next.deltas()), structure);
            doll.maxFont(low ? 10 : 14);
            doll.dimmed(unit.destroyed());
            float strip = mek ? (low ? LOW_REAR_STRIP : REAR_STRIP) : 0;
            float height = (low ? 114 : 136) - (mek ? strip + 4 : 0);
            slot.add(doll).size(dollWidth(record), height).row();
            if (mek) {
                slot.add(new RearStrip(record, low)).size(dollWidth(record), strip).padTop(4);
            }
        } else {
            doll.show(null, Map.of(), Map.of());
            slot.add(tiles(record, low)).top().left().growX();
        }
        return slot;
    }

    /**
     * Location tiles (4.2) for a unit without a drawing: battle armor troopers, emplacements, buildings, squadrons; a
     * conventional platoon's "active / original" troopers. Each tile is "abbr value" in its tier, "✕" when destroyed.
     */
    private Table tiles(GpuUnitRecord.Snapshot record, boolean low) {
        Table tiles = new Table();
        tiles.top().left();
        GpuUnitRecord.Vital troopers = record.vitals().stream()
              .filter(vital -> vital.group() == GpuUnitRecord.VitalGroup.TROOPERS).findFirst().orElse(null);
        boolean platoon = record.vitals().stream().anyMatch(vital -> vital.group() == GpuUnitRecord.VitalGroup.KIT);
        if (platoon && troopers != null) {
            tiles.add(ui.label(troopers.value() + "/" + troopers.max(), "hud-heading", low ? 20 : 24,
                  GpuPaperdoll.damageFill(troopers.value(), troopers.max(), troopers.value() <= 0).number)).left()
                  .row();
            tiles.add(ui.label(UiTheme.upper(text("GpuBoard.hud.unit.troopers")), "hud-caption", 9.5f,
                  UiTheme.MUTED)).left();
            clicks(tiles, SheetTab.ARMOR);
            return tiles;
        }
        int column = 0;
        for (GpuUnitRecord.Location location : record.locations()) {
            if (location.maxArmor() <= 0 && location.maxInternal() <= 0) {
                continue;
            }
            int value = location.maxArmor() > 0 ? location.armor() : location.internal();
            int original = location.maxArmor() > 0 ? location.maxArmor() : location.maxInternal();
            GpuPaperdoll.Cell cell = GpuPaperdoll.cell(value, original, location.destroyed(), location.internal(),
                  false, View.CARD);
            Table tile = new Table();
            tile.left();
            tile.add(ui.label(tileCode(location), "hud-caption", 8.5f, UiTheme.MUTED)).padRight(3);
            tile.add(ui.label(cell.text(), "hud-name", 11, cell.tier().number));
            tiles.add(tile).left().padRight(6).padBottom(3);
            if (++column % 2 == 0) {
                tiles.row();
            }
        }
        clicks(tiles, SheetTab.ARMOR);
        return tiles;
    }

    /** A tile's code: a trooper's number for battle armor ("Trooper 2" is "2"), else the abbreviation. */
    private static String tileCode(GpuUnitRecord.Location location) {
        String abbr = location.abbr();
        int space = abbr.lastIndexOf(' ');
        return space > 0 && abbr.substring(space + 1).chars().allMatch(Character::isDigit) ? abbr.substring(space + 1)
              : abbr;
    }

    /** The doll's hover card (4.5): what the card doll does not draw, beside the card; nothing for none. */
    private void hover(String code, float stageX, float stageY) {
        GpuUnitRecord.Snapshot record = shown == null ? GpuUnitRecord.Snapshot.EMPTY : shown.record();
        String title = null;
        String detail = "";
        if (code != null && code.startsWith("DC")) {
            GpuUnitRecord.Shield shield = record.shields().stream()
                  .filter(candidate -> candidate.location().equals(code.substring(2))).findFirst().orElse(null);
            if (shield != null) {
                title = shield.name();
                detail = text("GpuBoard.hud.unit.shieldDetail", shield.location(), shield.baseCapacity(),
                      shield.baseAbsorption());
            }
        } else if (code != null) {
            GpuUnitRecord.Location location = record.locations().stream()
                  .filter(candidate -> candidate.abbr().equals(code)).findFirst().orElse(null);
            if (location != null) {
                title = location.name();
                detail = locationDetail(location);
            }
        }
        hoverCard.setVisible(title != null);
        if (title == null) {
            return;
        }
        hoverCard.clearChildren();
        hoverCard.pad(10, 14, 9, 14);
        hoverCard.add(ui.label(UiTheme.upper(title), "hud-name", 13, UiTheme.TEXT)).left().row();
        Label text = ui.label(detail, "hud-small", 11.5f, UiTheme.ACCENT);
        text.setWrap(true);
        hoverCard.add(text).width(HOVER_WIDTH - 28).left().padTop(3);
        hoverCard.pack();
        // Beside the card, at the pointer's height, inside the window.
        Vector2 corner = root.localToStageCoordinates(new Vector2(root.getWidth(), 0));
        Vector2 local = overlay.stageToLocalCoordinates(new Vector2(corner.x + 8, stageY));
        float y = Math.max(10, Math.min(overlay.getHeight() - hoverCard.getHeight() - 10,
              local.y - hoverCard.getHeight() / 2));
        hoverCard.setPosition(Math.min(local.x, overlay.getWidth() - hoverCard.getWidth() - 10), y);
    }

    /**
     * What a location's hover card and the ARMOR inspector print, as the dolls cannot (R3-11): its maxima, its
     * structure, its critical hits, where its damage goes (or went, once destroyed) and what is lost with it.
     */
    static String locationDetail(GpuUnitRecord.Location location) {
        List<String> parts = new ArrayList<>();
        if (location.maxArmor() > 0) {
            parts.add(text("GpuBoard.hud.unit.maxArmor", location.maxArmor()));
        }
        if (location.maxRear() > 0) {
            parts.add(text("GpuBoard.hud.unit.maxRear", location.maxRear()));
        }
        if (location.maxInternal() > 0) {
            parts.add(text("GpuBoard.hud.unit.structureOf", location.internal(), location.maxInternal()));
        }
        String crits = location.slots().stream().filter(slot -> !slot.empty() && (slot.hit() || slot.destroyed()))
              .filter(slot -> !location.destroyed()).map(GpuUnitRecord.Slot::text).distinct()
              .collect(Collectors.joining(", "));
        if (!crits.isEmpty()) {
            parts.add(text("GpuBoard.hud.unit.criticalHits", crits));
        }
        if (!location.transferTo().isEmpty()) {
            parts.add(text(location.destroyed() ? "GpuBoard.hud.unit.transferred" : "GpuBoard.hud.unit.transfer",
                  location.transferTo()));
        }
        if (!location.dependent().isEmpty()) {
            parts.add(text("GpuBoard.hud.unit.dependent", location.dependent()));
        }
        return String.join(SEPARATOR, parts);
    }

    // ------------------------------------------------------------------------------------------------ the vitals

    /**
     * The vitals column (4.3): four fixed rows by family; a row the family lacks stays empty. Row 1 is the heat block
     * of a unit that tracks heat, else a vehicle's motive damage; row 2 the MP and TMM, or a flying unit's velocity,
     * altitude and fuel; row 3 this turn's movement, or the thrust; row 4 the usable weapons of those listed.
     */
    private Table vitals(GpuBattleStatus.UnitStatus unit, Shown next, boolean low) {
        Table vitals = new Table();
        vitals.top().left();
        vitals.defaults().growX().left();
        GpuUnitRecord.Snapshot record = next.record();
        Map<String, GpuUnitRecord.Vital> flight = vitals(record, GpuUnitRecord.VitalGroup.FLIGHT);
        float gap = low ? 7 : 9;
        if (!unit.heatCapacity().isEmpty()) {
            vitals.add(heatBlock(unit, record, next.heat())).padTop(2).row();
        } else if (!vitals(record, GpuUnitRecord.VitalGroup.MOTIVE).isEmpty()) {
            String word = motiveWord(record);
            GpuUnitRecord.Vital rotor = vitals(record, GpuUnitRecord.VitalGroup.MOTIVE).get("ROTOR");
            if (rotor != null && rotor.value() > 0) {
                word = text("GpuBoard.hud.unit.rotorLost", rotor.value(), rotor.max());
            }
            vitals.add(row(text("GpuBoard.hud.unit.motive"), value(word, word.equals("—") ? UiTheme.MUTED
                  : UiTheme.AMBER), SheetTab.SYSTEMS)).padTop(2).row();
        } else {
            vitals.add().height(17).padTop(2).row();
        }
        String mp = mp(unit, !flight.isEmpty(), !record.hitTracks().isEmpty());
        GpuUnitRecord.Vital original = vitals(record, GpuUnitRecord.VitalGroup.MP).get("MP");
        List<String> mpTip = new ArrayList<>();
        if (original != null) {
            mpTip.add(text("GpuBoard.hud.unit.mpTip", original.text()));
        }
        mpTip.addAll(mpCauses(record));
        if (!flight.isEmpty()) {
            GpuUnitRecord.Vital fuel = flight.get("FUEL");
            vitals.add(row(text("GpuBoard.hud.unit.flight"), value(text("GpuBoard.hud.unit.flightValue",
                  number(flight.get("VELOCITY")), number(flight.get("ALTITUDE")), fuel == null ? "—"
                  : Integer.toString(fuel.value())), UiTheme.ACCENT), SheetTab.STATUS)).padTop(gap).row();
            vitals.add(tip(row(text("GpuBoard.hud.unit.thrust"), bold(mp, ""), SheetTab.STATUS),
                  String.join(SEPARATOR, mpTip)))
                  .padTop(gap).row();
        } else {
            Table mpValue = low ? bold(mp, "") : bold(mp, SEPARATOR + text("GpuBoard.hud.unit.tmm",
                  GpuHudKit.signed(unit.tmm())));
            vitals.add(tip(row(text("GpuBoard.hud.unit.mp"), mpValue, SheetTab.STATUS), String.join(SEPARATOR, mpTip)))
                  .padTop(gap).row();
            String moved = moved(unit, next.phase());
            if (low) {
                vitals.add(row("", value(unit.moved().isEmpty() ? moved : text("GpuBoard.hud.unit.movedTmm",
                      unit.moved(), unit.hexesMoved(), GpuHudKit.signed(unit.tmm())), UiTheme.ACCENT),
                      SheetTab.STATUS)).padTop(gap).row();
            } else {
                vitals.add(row(text("GpuBoard.hud.unit.moved"), value(moved, UiTheme.ACCENT), SheetTab.STATUS))
                      .padTop(gap).row();
            }
        }
        if (!record.weapons().isEmpty()) {
            Table count = bold(Integer.toString(usableWeapons(record)), " " + text("GpuBoard.hud.unit.ofListed",
                  record.weapons().size()));
            String unusable = record.weapons().stream().filter(weapon -> weapon.destroyed() || weapon.crippled())
                  .map(GpuUnitRecord.RecordWeapon::name).collect(Collectors.joining(", "));
            vitals.add(tip(row(text(low ? "GpuBoard.hud.unit.weaponsShort" : "GpuBoard.hud.common.weapons"), count,
                  SheetTab.WEAPONS), unusable.isEmpty() ? "" : text("GpuBoard.hud.unit.unusable", unusable)))
                  .padTop(gap).row();
        }
        return vitals;
    }

    /**
     * The heat block (4.3, R3-4): "HEAT" with "h → f" (f amber while a forecast exists), the bar with its ticks, and
     * one caption: the effects of f, else the next level that changes them; the capacity is its tooltip.
     */
    private Table heatBlock(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record, GpuFireOrders.Heat heat) {
        int forecast = heat == null ? -1 : Math.max(0, heat.end());
        Table block = new Table();
        block.defaults().left();
        Table value = new Table();
        value.add(ui.label(Integer.toString(unit.heat()), "hud-name", 14, Color.WHITE));
        if (forecast >= 0 && forecast != unit.heat()) {
            value.add(ui.label("→", "hud-name", 12, UiTheme.MUTED)).pad(0, 3, 0, 3);
            value.add(ui.label(Integer.toString(forecast), "hud-name", 14, UiTheme.AMBER));
        }
        Table top = new Table();
        top.add(ui.label(UiTheme.upper(text("GpuBoard.hud.common.heat")), "hud-caption", 10.5f, UiTheme.MUTED)).left()
              .expandX();
        top.add(value).right();
        block.add(top).growX().row();
        GpuHudKit.HeatBar bar = kit.heatBar(false);
        bar.set(unit.heat(), Math.max(unit.heat(), forecast), Math.max(1, record.heatScale()), record.heatTicks());
        bar.setVisible(record.heatScale() > 0);
        block.add(bar).growX().height(7).padTop(4).row();
        String caption = heatCaption(record, unit.heat(), forecast);
        Label line = ui.label(caption, "hud-body", 11, forecast >= 0 ? UiTheme.AMBER : UiTheme.MUTED);
        line.setEllipsis(true);
        block.add(line).growX().minWidth(0).padTop(4).height(15);
        String now = effectsAt(record, unit.heat());
        ui.tip(block).getActor().setText(text("GpuBoard.hud.unit.heatTip", unit.heat(), unit.heatCapacity(),
              now.isEmpty() ? "—" : now));
        clicks(block, SheetTab.STATUS);
        return block;
    }

    /** A vitals row (.vr): the upper-case muted label at the left and the value at the right; a click opens a tab. */
    private Table row(String label, Actor value, SheetTab home) {
        Table row = new Table();
        if (!label.isEmpty()) {
            row.add(ui.label(UiTheme.upper(label), "hud-caption", 10.5f, UiTheme.MUTED)).left().padRight(8);
        }
        row.add(value).expandX().right().minWidth(0);
        clicks(row, home);
        return row;
    }

    private Table tip(Table row, String tip) {
        if (!tip.isEmpty()) {
            ui.tip(row).getActor().setText(tip);
        }
        return row;
    }

    private Label value(String text, Color color) {
        Label label = ui.label(text, "hud-body", 11.5f, color);
        label.setEllipsis(true);
        label.setAlignment(Align.right);
        return label;
    }

    /** A bold value (.vr b: 700 14 condensed) and its muted rest, such as "4" and " of 7". */
    private Table bold(String value, String rest) {
        Table parts = new Table();
        parts.add(ui.label(value, "hud-name", 14, Color.WHITE));
        if (!rest.isEmpty()) {
            parts.add(ui.label(rest, "hud-body", 11.5f, UiTheme.ACCENT));
        }
        return parts;
    }

    /** Opens the sheet on {@code home} on a click, as every card element does (4.5). */
    private void clicks(Actor actor, SheetTab home) {
        actor.setTouchable(Touchable.enabled);
        actor.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                showSheet(home);
            }
        });
    }

    private String name(String suffix) {
        return (other ? "other-unit-card" : "unit-card") + suffix;
    }

    /** The shared sheet must follow the card that was clicked, even while both cards are visible. */
    private void showSheet(SheetTab tab) {
        state.inspected = shownId == state.focus() ? Entity.NONE : shownId;
        GpuRecordSheet.show(state, tab);
    }

    /**
     * "2/3/0", the MP as the unit's display gives them: a Mek's walk, run and jump; another unit's cruise and flank,
     * with its jump only when it has one; a flying unit's safe and max thrust.
     */
    private static String mp(GpuBattleStatus.UnitStatus unit, boolean thrust, boolean mek) {
        String mp = unit.walk() + "/" + unit.run();
        return thrust || !mek && unit.jump() <= 0 ? mp : mp + "/" + unit.jump();
    }

    /**
     * This turn's movement (4.3): "Walked · 2 hex · NE", "Held position" once the movement is over without one, and
     * "—" before the unit moves (the forces list's rule, {@code UnitRow.moved}).
     */
    static String moved(GpuBattleStatus.UnitStatus unit, GamePhase phase) {
        if (!unit.moved().isEmpty()) {
            return String.join(SEPARATOR, unit.moved(), text("GpuBoard.hud.common.hexDistance", unit.hexesMoved()),
                  GpuHudKit.facing(unit.facing()));
        }
        String held = GpuHudKit.UnitRow.moved(unit, phase);
        return held.isEmpty() ? "—" : held;
    }

    private static String number(GpuUnitRecord.Vital vital) {
        return vital == null ? "—" : Integer.toString(vital.value());
    }

    // ------------------------------------------------------------------------------------------------ heat texts

    /**
     * The heat caption (4.3): with a forecast the effects of the forecast heat, else "Next {tick} · {effects}" for
     * the next level above the heat that changes them; MegaMek's heat table texts ({@code HeatEffects}).
     */
    static String heatCaption(GpuUnitRecord.Snapshot record, int heat, int forecast) {
        if (forecast >= 0) {
            String effects = effectsAt(record, forecast);
            if (!effects.isEmpty()) {
                return effects;
            }
        }
        for (int index = 0; index < record.heatTicks().size(); index++) {
            if (record.heatTicks().get(index) > heat) {
                return text("GpuBoard.hud.unit.nextHeat", record.heatTicks().get(index),
                      effects(record.heatTickEffects().get(index)));
            }
        }
        return "";
    }

    /** The heat table's effects at a heat level, "" below the first level with effects. */
    static String effectsAt(GpuUnitRecord.Snapshot record, int heat) {
        String effects = "";
        for (int index = 0; index < record.heatTicks().size(); index++) {
            if (record.heatTicks().get(index) <= heat) {
                effects = effects(record.heatTickEffects().get(index));
            }
        }
        return effects;
    }

    private static String effects(String heatEffects) {
        return heatEffects.replace(";", " ·").strip();
    }

    // ------------------------------------------------------------------------------------------------ record facts

    /** The weapons that can fire: neither destroyed nor unusable (jammed, empty, spent, missing). */
    static int usableWeapons(GpuUnitRecord.Snapshot record) {
        return (int) record.weapons().stream().filter(weapon -> !weapon.destroyed() && !weapon.crippled()).count();
    }

    /** A location whose front or rear armor is gone while its structure stands (the approved "(structure)" cue). */
    static boolean exposed(GpuUnitRecord.Location location) {
        return !location.destroyed() && location.internal() > 0
              && (location.maxArmor() > 0 && location.armor() <= 0 || location.maxRear() > 0 && location.rear() <= 0);
    }

    /** {armor, original armor, structure, original structure} of the unit, rear armor included. */
    static int[] totals(GpuUnitRecord.Snapshot record) {
        int[] sums = new int[4];
        for (GpuUnitRecord.Location location : record.locations()) {
            sums[0] += location.armor() + location.rear();
            sums[1] += location.maxArmor() + location.maxRear();
            sums[2] += location.internal();
            sums[3] += location.maxInternal();
        }
        return sums;
    }

    /** A vehicle's motive damage as MegaMek's tier word (minor, moderate, heavy), "—" without any (R3-11). */
    static String motiveWord(GpuUnitRecord.Snapshot record) {
        Map<String, GpuUnitRecord.Vital> motive = vitals(record, GpuUnitRecord.VitalGroup.MOTIVE);
        for (String[] tier : new String[][] { { "HEAVY_MOTIVE", "HeavyMovementDamage" },
              { "MODERATE_MOTIVE", "ModerateMovementDamage" }, { "MINOR_MOTIVE", "MinorMovementDamage" } }) {
            GpuUnitRecord.Vital vital = motive.get(tier[0]);
            if (vital != null && vital.value() > 0) {
                return text("BoardView1.Tooltip.Abbreviation" + tier[1]);
            }
        }
        return "—";
    }

    private static Map<String, GpuUnitRecord.Vital> vitals(GpuUnitRecord.Snapshot record,
          GpuUnitRecord.VitalGroup group) {
        Map<String, GpuUnitRecord.Vital> vitals = new HashMap<>();
        record.vitals().stream().filter(vital -> vital.group() == group)
              .forEach(vital -> vitals.put(vital.code(), vital));
        return vitals;
    }

    // ------------------------------------------------------------------------------------------------ doll cells

    /** A view of the record's paperdoll family, with its mounted shields' variants on the front armor; null: none. */
    static GpuPaperdolls.Doll doll(GpuPaperdolls paperdolls, GpuUnitRecord.Snapshot record, String view) {
        if (record.paperdoll().isEmpty()) {
            return null;
        }
        Set<String> arms = view.equals(GpuPaperdolls.ARMOR) ? record.shields().stream()
              .map(GpuUnitRecord.Shield::location).collect(Collectors.toSet()) : Set.of();
        return paperdolls.doll(record.paperdoll(), view, arms);
    }

    /**
     * The front armor cells (6.3), the one mapping of the record that the card, the hover card and the sheet use:
     * each location's armor over its structure with its critical hit dot, breached outline and playback change, and a
     * mounted shield's capacity panel and absorption strip. A location without structure of its own (an aerospace
     * unit's, whose structure is the SI) shows its armor's 0 on the exposed fill.
     */
    static Map<String, GpuPaperdoll.Cell> frontCells(GpuUnitRecord.Snapshot record, View view, Deltas deltas) {
        Map<String, GpuPaperdoll.Cell> cells = new HashMap<>();
        for (GpuUnitRecord.Location location : record.locations()) {
            boolean crit = location.slots().stream().anyMatch(slot -> !slot.filler() && (slot.hit()
                  || slot.destroyed()));
            GpuPaperdoll.Cell cell = location.maxInternal() <= 0 && location.maxArmor() > 0 && location.armor() <= 0
                  && !location.destroyed() ? new GpuPaperdoll.Cell(DamageTier.EXPOSED, "0", crit, false, 0)
                  : GpuPaperdoll.cell(location.armor(), location.maxArmor(), location.destroyed(),
                        location.internal(), crit, view);
            cells.put(location.abbr(), cell.with(location.breached(), deltas.front().getOrDefault(location.abbr(),
                  0)));
        }
        for (GpuUnitRecord.Shield shield : record.shields()) {
            boolean armBad = record.locations().stream()
                  .anyMatch(location -> location.abbr().equals(shield.location()) && location.destroyed());
            cells.putAll(GpuPaperdoll.shieldCells(shield.location(), shield.active() && !armBad, shield.capacity(),
                  shield.baseCapacity(), shield.absorption(), shield.baseAbsorption()));
        }
        return cells;
    }

    /** The rear armor cells of the sheet's rear doll: no number where the structure doll prints it (R3-3). */
    static Map<String, GpuPaperdoll.Cell> rearCells(GpuUnitRecord.Snapshot record, Deltas deltas) {
        Map<String, GpuPaperdoll.Cell> cells = new HashMap<>();
        for (GpuUnitRecord.Location location : record.locations()) {
            if (location.maxRear() > 0) {
                cells.put(location.abbr(), GpuPaperdoll.cell(location.rear(), location.maxRear(), location.destroyed(),
                      location.internal(), false, View.ARMOR).with(location.breached(),
                      deltas.rear().getOrDefault(location.abbr(), 0)));
            }
        }
        return cells;
    }

    /**
     * The structure cells: each location's structure (the structure doll, or another family's structure boxes) and
     * the value boxes SI, K-F, SAIL and DC of aerospace and capital units from their vitals.
     */
    static Map<String, GpuPaperdoll.Cell> structureCells(GpuUnitRecord.Snapshot record, View view, Deltas deltas) {
        Map<String, GpuPaperdoll.Cell> cells = new HashMap<>();
        for (GpuUnitRecord.Location location : record.locations()) {
            cells.put(location.abbr(), GpuPaperdoll.cell(location.internal(), location.maxInternal(),
                  location.destroyed(), location.internal(), false, view).with(location.breached(),
                  deltas.structure().getOrDefault(location.abbr(), 0)));
        }
        for (GpuUnitRecord.Vital vital : record.vitals()) {
            boolean box = vital.group() == GpuUnitRecord.VitalGroup.AERO && vital.code().equals("SI")
                  || vital.group() == GpuUnitRecord.VitalGroup.SHIP && List.of("KF", "SAIL", "DC")
                  .contains(vital.code());
            if (box) {
                cells.put(vital.code(), GpuPaperdoll.cell(vital.value(), vital.max(), false, vital.value(), false,
                      view));
            }
        }
        return cells;
    }

    /**
     * Why the unit's MP differ from its original MP (graft 2), as the unit tooltip marks it: "Heat −1 MP",
     * "Gravity 1.5 g", "Damage" and the like, in its order.
     */
    static List<String> mpCauses(GpuUnitRecord.Snapshot record) {
        return record.vitals().stream()
              .filter(vital -> vital.group() == GpuUnitRecord.VitalGroup.MP && !vital.code().equals("MP"))
              .map(vital -> switch (vital.code()) {
                  case "HEAT" -> vital.value() > 0 ? text("GpuBoard.hud.unit.cause.HEAT", vital.value())
                        : text("GpuBoard.hud.common.heat");
                  case "GRAVITY" -> text("GpuBoard.hud.unit.cause.GRAVITY", vital.text());
                  default -> text("GpuBoard.hud.unit.cause." + vital.code());
              }).toList();
    }

    // ------------------------------------------------------------------------------------------------ chips

    /**
     * The chips (4.4) of what the doll and the vitals cannot show, from the captured records only, sorted by tone
     * (coral, amber, neutral) and then by priority: the crew's state, the unit's warnings, its system hits and
     * stabilizers, its jammed weapons and empty or dumping bins, a narc pod, then pending changes, the unit's other
     * status words and its transport.
     */
    static List<Chip> chips(GpuBattleStatus.UnitStatus unit, GpuUnitRecord.Snapshot record, GamePhase phase) {
        List<Chip> chips = new ArrayList<>();
        for (GpuUnitRecord.CrewSeat seat : record.crew()) {
            if (seat.missing()) {
                continue;
            }
            if (!seat.active() && !seat.status().isBlank()) {
                chips.add(new Chip(seat.role() + " " + seat.status(), Tone.BAD, SheetTab.CREW));
            } else if (seat.hits() > 0) {
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.crewHits", seat.role(), seat.hits()), Tone.WARN,
                      SheetTab.CREW));
            }
        }
        for (UnitStatusWords.StatusWord word : unit.statusWords()) {
            if (word.severity() == UnitStatusWords.Severity.WARNING || word.severity() == UnitStatusWords.Severity.CAUTION) {
                chips.add(new Chip(word.label(), Tone.BAD, SheetTab.STATUS));
            }
        }
        for (GpuUnitRecord.HitTrack track : record.hitTracks()) {
            // A destroyed gyro is the NO_GYRO status word's fact; hits beyond the capacity add nothing.
            boolean gyroGone = track.track() == GpuUnitRecord.Track.GYRO && track.hits() >= track.capacity();
            if (track.hits() > 0 && !gyroGone) {
                boolean grave = track.track() == GpuUnitRecord.Track.ENGINE
                      || track.track() == GpuUnitRecord.Track.GYRO;
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.hits", text("GpuBoard.hud.unit.track."
                      + track.track().name()), Math.min(track.hits(), track.capacity()), track.capacity()),
                      grave ? Tone.BAD : Tone.WARN, SheetTab.SYSTEMS));
            }
        }
        for (GpuUnitRecord.Vital vital : record.vitals()) {
            boolean system = vital.group() == GpuUnitRecord.VitalGroup.SYSTEM
                  || vital.group() == GpuUnitRecord.VitalGroup.AERO && !vital.code().equals("SI");
            if (system && vital.value() > 0) {
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.hit", text("GpuBoard.hud.unit.track." + vital.code()),
                      vital.value()), vital.code().equals("ENGINE") ? Tone.BAD : Tone.WARN, SheetTab.SYSTEMS));
            } else if (vital.group() == GpuUnitRecord.VitalGroup.STABILIZER && vital.value() > 0) {
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.stabilizer", vital.code()), Tone.WARN,
                      SheetTab.SYSTEMS));
            }
        }
        for (GpuUnitRecord.RecordWeapon weapon : record.weapons()) {
            if (weapon.jammed() && !weapon.destroyed()) {
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.jammed", weapon.name()), Tone.WARN, SheetTab.WEAPONS));
            }
        }
        for (GpuUnitRecord.AmmoBin bin : record.ammo()) {
            if (bin.dumping()) {
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.dumping", bin.name()), Tone.WARN, SheetTab.WEAPONS));
            } else if (!bin.jettison() && bin.shots() <= 0 && bin.fullShots() > 0) {
                chips.add(new Chip(text("GpuBoard.hud.unit.chip.empty", bin.name()), Tone.WARN, SheetTab.SYSTEMS));
            }
        }
        if (unit.statusTiles().stream().anyMatch(tile -> tile.key().equals("N"))) {
            chips.add(new Chip(text("GpuBoard.hud.unit.chip.narc"), Tone.WARN, SheetTab.STATUS));
        }
        int pending = GpuUnitSheetTabs.pending(record).size();
        if (pending > 0) {
            chips.add(new Chip(text("GpuBoard.hud.unit.chip.pending", pending), Tone.NORMAL, SheetTab.STATUS));
        }
        for (UnitStatusWords.StatusWord word : unit.statusWords()) {
            if (word.severity() == UnitStatusWords.Severity.PRECAUTION || word.severity() == UnitStatusWords.Severity.INFO) {
                chips.add(new Chip(word.label(), Tone.NORMAL, SheetTab.STATUS));
            }
        }
        long carried = record.info().stream().filter(row -> row.section() == GpuUnitRecord.InfoSection.TRANSPORT)
              .count();
        if (record.info().stream().anyMatch(row -> row.section() == GpuUnitRecord.InfoSection.CARRIER)) {
            chips.add(new Chip(text("GpuBoard.hud.unit.chip.transported"), Tone.NORMAL, SheetTab.EXTRAS));
        }
        if (carried > 0) {
            chips.add(new Chip(text("GpuBoard.hud.unit.chip.carrying", carried), Tone.NORMAL, SheetTab.EXTRAS));
        }
        // A stable sort keeps the priority order within a tone.
        chips.sort(Comparator.comparingInt(chip -> switch (chip.tone()) {
            case BAD -> 0;
            case WARN -> 1;
            default -> 2;
        }));
        return chips;
    }

    /**
     * Fills a chip line (4.4): the chips in order, 5 apart, as many as fit in {@code room}; the rest collapse into
     * "+n", which opens the first hidden chip's home. An empty line stays empty.
     */
    static void fillChips(UiKit ui, Table line, List<Chip> chips, float room, Consumer<Chip> open) {
        float used = 0;
        for (int index = 0; index < chips.size(); index++) {
            Chip chip = chips.get(index);
            Label label = ui.chip(chip.text(), chip.tone());
            int rest = chips.size() - index - 1;
            float more = rest > 0 ? ui.chip("+" + rest, Tone.NORMAL).getPrefWidth() + CHIP_GAP : 0;
            if (used + label.getPrefWidth() + more > room) {
                Label plus = ui.chip("+" + (chips.size() - index), Tone.NORMAL);
                click(plus, () -> open.accept(chip));
                line.add(plus).padLeft(used > 0 ? CHIP_GAP : 0);
                return;
            }
            click(label, () -> open.accept(chip));
            line.add(label).padLeft(used > 0 ? CHIP_GAP : 0);
            used += label.getPrefWidth() + (used > 0 ? CHIP_GAP : 0);
        }
    }

    private static void click(Actor actor, Runnable action) {
        actor.setTouchable(Touchable.enabled);
        actor.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                action.run();
            }
        });
    }

    /**
     * A Mek's rear armor strip under the card doll (4.2): one cell per location with rear armor, its abbreviation and
     * value in its tier's fill, outline and number colour.
     */
    private final class RearStrip extends Table {
        private final List<GpuPaperdoll.Cell> cells = new ArrayList<>();

        RearStrip(GpuUnitRecord.Snapshot record, boolean low) {
            setName(name("-rear"));
            // Left to right as on the doll: the left torso, the centre torso, the right torso.
            List<GpuUnitRecord.Location> rear = record.locations().stream().filter(location -> location.maxRear() > 0)
                  .sorted(Comparator.comparingInt(location -> REAR_ORDER.indexOf(location.abbr()))).toList();
            for (GpuUnitRecord.Location location : rear) {
                GpuPaperdoll.Cell cell = GpuPaperdoll.cell(location.rear(), location.maxRear(), location.destroyed(),
                      location.internal(), false, View.CARD);
                cells.add(cell);
                Table part = new Table();
                part.add(ui.label(location.abbr(), "hud-caption", 8, cell.tier() == DamageTier.EXPOSED
                      ? UiTheme.FILL_INK : UiTheme.MUTED)).padRight(3);
                part.add(ui.label(cell.text(), "hud-name", low ? 10 : 11, cell.tier().number));
                add(part).growX().uniformX().fillY().padLeft(getCells().isEmpty() ? 0 : 3);
            }
            clicks(this, SheetTab.ARMOR);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            validate();
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            com.badlogic.gdx.utils.Array<Cell> parts = getCells();
            for (int index = 0; index < parts.size && index < cells.size(); index++) {
                Actor part = parts.get(index).getActor();
                DamageTier tier = cells.get(index).tier();
                float x = getX() + part.getX();
                float y = getY() + part.getY();
                ui.fill(batch, tier.opaque, alpha, x, y, part.getWidth(), part.getHeight());
                Color edge = tier.outline;
                ui.fill(batch, edge, alpha, x, y, part.getWidth(), 1);
                ui.fill(batch, edge, alpha, x, y + part.getHeight() - 1, part.getWidth(), 1);
                ui.fill(batch, edge, alpha, x, y, 1, part.getHeight());
                ui.fill(batch, edge, alpha, x + part.getWidth() - 1, y, 1, part.getHeight());
            }
            batch.setPackedColor(previous);
            super.draw(batch, parentAlpha);
        }
    }
}
