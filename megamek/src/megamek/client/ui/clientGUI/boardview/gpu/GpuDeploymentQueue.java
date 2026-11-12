/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * The deployment phase's force along the bottom of the screen: a compact card per own unit, in two rows, filled
 * column by column. Units keep their place once placed (a mint check marks them), so the strip never shifts under the
 * player; "Hide deployed" leaves them out. A card marks a unit that starts the battle damaged. Clicking a card selects
 * its unit for placing; right-clicking opens its menu.
 *
 * <p>Formations group the cards ("Unassigned" collects the units outside them). Without formations a small force
 * shows as one group, and a large one in automatic groups by weight class, which say they are automatic. The cards
 * narrow as the force grows, and the strip scrolls sideways once it is longer than the band.</p>
 */
final class GpuDeploymentQueue implements GpuHud.Component {
    /** A force up to this size shows without automatic groups. */
    static final int FLAT_LIMIT = 12;
    private static final float GAP = 6;
    private static final float GROUP_GAP = 14;
    /** Cards stand in two rows, filled column by column, so a lance of four is two columns wide. */
    private static final int ROWS = 2;
    private static final float CARD_HEIGHT = 54;
    private static final float ROW_GAP = 4;

    /** The card widths: wide while the force is small, narrower for more units; the name ellipsizes to fit. */
    enum Size {
        LARGE(176), MEDIUM(140), SMALL(104);

        final float width;

        Size(float width) {
            this.width = width;
        }

        static Size of(int units) {
            return units <= 8 ? LARGE : units <= 16 ? MEDIUM : SMALL;
        }
    }

    /** A group of cards: a formation, the units outside every formation, or an automatic weight class. */
    record Group(String name, boolean automatic, List<UnitStatus> units) { }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final IntConsumer select;
    private final Table root;
    private final Label count;
    private final Table cards = new Table();
    private final ScrollPane scroll;
    private final Map<Integer, Tile> shownCards = new HashMap<>();
    /** The overlay chips by the overlay each shows; the chip of the shown overlay is checked. */
    private final Map<GpuHudState.DeployOverlay, UiButton> overlayChips = new EnumMap<>(GpuHudState.DeployOverlay.class);
    private final UiButton hideDeployed;
    /** Whether a click on the selected card can let go of it: only in the local turn with a unit selected. */
    private boolean locked = true;
    private List<List<Integer>> shownGroups = List.of();
    private Size shownSize;

    GpuDeploymentQueue(GpuHudKit kit, GpuHudState state, GpuContextMenu menu, IntConsumer select,
          Consumer<GpuHudState.DeployOverlay> overlay, Actor chatButton) {
        this.kit = kit;
        ui = kit.ui;
        this.state = state;
        this.menu = menu;
        this.select = select;
        root = ui.panel();
        root.setName("deploy-queue");
        root.pad(8, 12, 8, 12);
        Table head = new Table();
        head.add(ui.label(UiTheme.upper(text("GpuBoard.hud.deploy.force")), "hud-title", 13, UiTheme.TEXT))
              .padRight(10);
        count = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        count.setName("deploy-queue-count");
        count.setEllipsis(true);
        head.add(count).growX().minWidth(0).left();
        // The overlays that help to choose a hex, named where the player looks rather than behind a gesture.
        Label overlays = ui.label(UiTheme.upper(text("GpuBoard.hud.deploy.overlays")), "hud-caption", 10,
              UiTheme.MUTED);
        head.add(overlays).padLeft(8).padRight(4);
        for (GpuHudState.DeployOverlay mode : GpuHudState.DeployOverlay.values()) {
            if (mode == GpuHudState.DeployOverlay.OFF) {
                continue;
            }
            UiButton chip = ui.button("hud-mini", null, mode.label(), null);
            chip.setName("deploy-overlay-" + mode.name().toLowerCase(java.util.Locale.ROOT));
            UiKit.onChange(chip, () -> overlay.accept(mode));
            ui.tip(chip).getActor().setText(text("GpuBoard.hud.deploy.overlayTip." + mode.name()));
            overlayChips.put(mode, chip);
            head.add(chip).padLeft(4);
        }
        UiButton overview = ui.button("hud-mini", null, text("GpuBoard.hud.deploy.overview"), null);
        overview.setName("deploy-queue-overview");
        UiKit.onChange(overview, () -> state.overview = !state.overview);
        hideDeployed = ui.button("hud-mini", "check-circle", text("GpuBoard.hud.deploy.hideDeployed"), null);
        hideDeployed.setName("deploy-queue-hide-deployed");
        ui.tip(hideDeployed).getActor().setText(text("GpuBoard.hud.deploy.hideDeployedTip"));
        UiKit.onChange(hideDeployed, () -> state.hideDeployed = !state.hideDeployed);
        head.add(hideDeployed).padLeft(8);
        head.add(overview).padLeft(4);
        head.add(chatButton).padLeft(4);
        root.add(head).growX().height(24).row();
        cards.left().top();
        scroll = ui.scrollStrip(cards);
        scroll.setName("deploy-queue-cards");
        root.add(scroll).grow().minWidth(0).padTop(6);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        locked = !status.myTurn() || state.turnLocked(status);
        List<UnitStatus> own = new ArrayList<>();
        int placed = 0;
        int damaged = 0;
        for (UnitStatus unit : state.presentedUnits()) {
            if (unit.side() == Side.OWN) {
                own.add(unit);
                placed += placed(unit) ? 1 : 0;
                damaged += unit.damageLevel() > 0 ? 1 : 0;
            }
        }
        count.setText(damaged > 0 ? text("GpuBoard.hud.deploy.countDamaged", placed, own.size(), damaged)
              : text("GpuBoard.hud.deploy.count", placed, own.size()));
        for (Map.Entry<GpuHudState.DeployOverlay, UiButton> chip : overlayChips.entrySet()) {
            chip.getValue().setChecked(state.deployOverlay == chip.getKey());
        }
        Size size = Size.of(own.size());
        List<UnitStatus> listed = own;
        if (state.hideDeployed) {
            listed = new ArrayList<>();
            for (UnitStatus unit : own) {
                if (!placed(unit)) {
                    listed.add(unit);
                }
            }
        }
        List<Group> groups = groups(listed);
        List<List<Integer>> ids = new ArrayList<>();
        for (Group group : groups) {
            List<Integer> members = new ArrayList<>();
            for (UnitStatus unit : group.units()) {
                members.add(unit.id());
            }
            ids.add(members);
        }
        if (!ids.equals(shownGroups) || size != shownSize) {
            rebuild(groups, size);
            shownGroups = ids;
            shownSize = size;
        }
        hideDeployed.setChecked(state.hideDeployed);
        int card = state.cardUnit();
        for (UnitStatus unit : own) {
            Tile shown = shownCards.get(unit.id());
            if (shown != null) {
                shown.show(unit, unit.id() == card);
            }
        }
    }

    private void rebuild(List<Group> groups, Size size) {
        cards.clearChildren();
        shownCards.clear();
        boolean captions = groups.size() > 1 || !groups.isEmpty() && !groups.getFirst().name().isEmpty();
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            Table grid = new Table();
            grid.top().left();
            float width = columns(grid, group.units(), size.width);
            Table column = new Table();
            column.top().left();
            if (captions) {
                String name = group.automatic() ? text("GpuBoard.hud.deploy.autoGroup", group.name()) : group.name();
                Label caption = ui.label(UiTheme.upper(name), "hud-caption", 10, UiTheme.MUTED);
                caption.setName("deploy-queue-group");
                caption.setEllipsis(true);
                column.add(caption).left().minWidth(0).width(Math.max(0, width - GAP)).height(12).padBottom(3).row();
            }
            column.add(grid).left();
            cards.add(column).top().padRight(i < groups.size() - 1 ? GROUP_GAP - GAP : 0);
        }
    }

    /** Adds the units as cards in columns of {@link #ROWS}; returns the width they take. */
    private float columns(Table grid, List<UnitStatus> units, float width) {
        for (int start = 0; start < units.size(); start += ROWS) {
            Table column = new Table();
            column.top();
            for (int row = start; row < Math.min(units.size(), start + ROWS); row++) {
                int id = units.get(row).id();
                Tile tile = new Card(id);
                shownCards.put(id, tile);
                column.add(tile).size(width, CARD_HEIGHT).padBottom(row < start + ROWS - 1 ? ROW_GAP : 0).row();
            }
            grid.add(column).top().padRight(GAP);
        }
        return (units.size() + ROWS - 1) / ROWS * (width + GAP);
    }

    /**
     * The groups of the force: its formations when it has any, the units outside them last as "Unassigned"; else
     * one unnamed group for a force up to {@link #FLAT_LIMIT}, and automatic weight classes, heaviest first, for a
     * larger one.
     */
    static List<Group> groups(List<UnitStatus> own) {
        boolean formations = false;
        for (UnitStatus unit : own) {
            formations |= !unit.formation().isEmpty();
        }
        Map<String, List<UnitStatus>> byName = new LinkedHashMap<>();
        if (formations) {
            List<UnitStatus> unassigned = new ArrayList<>();
            for (UnitStatus unit : own) {
                if (unit.formation().isEmpty()) {
                    unassigned.add(unit);
                } else {
                    byName.computeIfAbsent(unit.formation(), name -> new ArrayList<>()).add(unit);
                }
            }
            List<Group> groups = new ArrayList<>();
            byName.forEach((name, units) -> groups.add(new Group(name, false, units)));
            if (!unassigned.isEmpty()) {
                groups.add(new Group(text("GpuBoard.hud.deploy.unassigned"), false, unassigned));
            }
            return groups;
        }
        if (own.size() <= FLAT_LIMIT) {
            return own.isEmpty() ? List.of() : List.of(new Group("", false, own));
        }
        List<UnitStatus> heaviestFirst = new ArrayList<>(own);
        heaviestFirst.sort((a, b) -> Integer.compare(b.weightClassIndex(), a.weightClassIndex()));
        for (UnitStatus unit : heaviestFirst) {
            byName.computeIfAbsent(unit.weightClass(), name -> new ArrayList<>()).add(unit);
        }
        List<Group> groups = new ArrayList<>();
        byName.forEach((name, units) -> groups.add(new Group(name, true, units)));
        return groups;
    }

    /** The unit stands on the board and has no deployment turn left: deployed, not merely tried on a hex. */
    static boolean placed(UnitStatus unit) {
        return unit.position() != null && !unit.pending();
    }

    /** The unit deploys in a later round: it has neither a hex nor a turn in this one. */
    static boolean later(UnitStatus unit) {
        return unit.position() == null && !unit.pending();
    }

    /**
     * In the local turn, the unit deploys this round but this turn takes other units (a turn for another unit type,
     * or infantry and ProtoMeks moving later).
     */
    static boolean notThisTurn(UnitStatus unit, boolean locked) {
        return !locked && unit.position() == null && unit.pending() && !unit.canActNow();
    }

    /** A unit's chassis and model, as its card's tooltip and the map's peek head it. */
    static String title(UnitStatus unit) {
        return unit.chassis() + (unit.model().isEmpty() ? "" : " " + unit.model());
    }

    /** A unit's tonnage, pilot, skills and MP in one line. */
    static String facts(UnitStatus unit) {
        return text("GpuBoard.hud.deploy.cardFacts", text("GpuBoard.hud.deploy.tons", Math.round(unit.tons())),
              unit.pilot(), unit.gunnery() + "/" + unit.piloting(), moves(unit));
    }

    private static String text(String key, Object... arguments) {
        return Messages.getString(key, arguments);
    }

    /**
     * A unit in the queue: a click selects it, a right click opens its menu, and hovering tells all its facts. The
     * unit the unit card shows carries the selection's mint frame.
     */
    private abstract class Tile extends Table {
        final GpuHudKit.UnitSprite sprite;
        final ArmorBar armor = ArmorBar.armor(ui);
        final ArmorBar structure = ArmorBar.structure(ui);
        private final Drawable up = ui.skin.getDrawable("row");
        private final Drawable over = ui.skin.getDrawable("row-over");
        private final Drawable picked = ui.skin.getDrawable("field-focused");
        private final ClickListener pointer;
        private final Label tip;
        private boolean selected;

        Tile(int id, String name, float spriteWidth, float spriteHeight) {
            sprite = kit.sprite(spriteWidth, spriteHeight);
            setName(name + id);
            setBackground(up);
            setTouchable(Touchable.enabled);
            tip = ui.tip(this).getActor();
            pointer = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    // The selected card again lets go of the unit: nothing is being deployed until a card is chosen.
                    if (selected && !locked) {
                        state.clearSelection();
                    } else {
                        select.accept(id);
                    }
                }
            };
            addListener(pointer);
            addListener(GpuHudKit.UnitRow.menuOpener(menu, id, state::presented));
        }

        void show(UnitStatus unit, boolean selected) {
            this.selected = selected;
            sprite.set(unit.icon(), GpuHudKit.UnitRow.spriteColor(unit));
            armor.set(unit.armor());
            structure.set(unit.structure());
            boolean placed = placed(unit);
            String line = placed ? text("GpuBoard.hud.deploy.placed") : later(unit) ? text("GpuBoard.hud.deploy.later")
                  : notThisTurn(unit, locked) ? text("GpuBoard.hud.deploy.notNow") : "";
            String tipText = title(unit) + "\n" + facts(unit);
            if (unit.damageLevel() > 0) {
                tipText += "\n" + text("GpuBoard.hud.deploy.damaged");
            }
            if (!line.isEmpty()) {
                tipText += "\n" + line;
            }
            tip.setText(tipText);
        }

        @Override
        public void act(float delta) {
            setBackground(selected ? picked : pointer.isOver() ? over : up);
            super.act(delta);
        }
    }

    /**
     * A unit of the force, what tells its units apart when choosing who goes where: its sprite beside its chassis and
     * model, its pilot beneath, then its tonnage, pilot skills (gunnery/piloting) and walk, run and jump MP, over a bar
     * of the armor left, amber once damaged. A unit that starts damaged carries a coral mark; one placed carries a mint
     * check and one deploying in a later round an hourglass, and both dim, keeping their place in the strip.
     */
    private final class Card extends Tile {
        private final Label name = ui.label("", "hud-medium", 11, UiTheme.TEXT);
        private final Label pilot = ui.label("", "hud-small", 10, UiTheme.MUTED);
        private final Label tons = ui.label("", "hud-small", 10, UiTheme.TEXT);
        private final Label skill = ui.label("", "hud-small", 10, UiTheme.MUTED);
        private final Label move = ui.label("", "hud-small", 10, UiTheme.MUTED);
        private final Label damage = ui.label("▲", "hud-small", 10, UiTheme.CORAL);
        private final Image placedMark = ui.icon("check-circle", 12, UiTheme.MINT);
        private final Image laterMark = ui.icon("hourglass", 12, UiTheme.MUTED);
        private final Image notNowMark = ui.icon("lock", 12, UiTheme.MUTED);
        private final Table body = new Table();

        private Card(int id) {
            super(id, "deploy-card-", 32, 28);
            pad(3, 4, 3, 5);
            damage.setName("deploy-card-damage");
            placedMark.setName("deploy-card-placed");
            name.setEllipsis(true);
            pilot.setEllipsis(true);
            move.setEllipsis(true);
            Table text = new Table();
            text.left();
            Table title = new Table();
            title.add(name).growX().minWidth(0).left();
            title.add(damage).padLeft(3);
            title.add(new Stack(placedMark, laterMark, notNowMark)).size(12).padLeft(3);
            text.add(title).growX().minWidth(0).row();
            text.add(pilot).growX().minWidth(0).left().row();
            Table facts = new Table();
            facts.left();
            facts.add(tons).padRight(7);
            facts.add(skill).padRight(7);
            facts.add(move).growX().minWidth(0).left();
            text.add(facts).growX().minWidth(0);
            body.add(sprite).padRight(5);
            body.add(text).grow().minWidth(0);
            add(body).grow().minWidth(0).row();
            add(armor).growX().height(3).padTop(2).row();
            add(structure).growX().height(2).padTop(1);
        }

        @Override
        void show(UnitStatus unit, boolean selected) {
            super.show(unit, selected);
            name.setText(unit.chassis() + (unit.model().isEmpty() ? "" : " " + unit.model()));
            pilot.setText(unit.pilot());
            tons.setText(text("GpuBoard.hud.deploy.tons", Math.round(unit.tons())));
            skill.setText(unit.gunnery() + "/" + unit.piloting());
            move.setText(moves(unit));
            damage.setVisible(unit.damageLevel() > 0);
            boolean placed = placed(unit);
            boolean later = later(unit);
            boolean notNow = notThisTurn(unit, locked);
            placedMark.setVisible(placed);
            laterMark.setVisible(later);
            notNowMark.setVisible(notNow);
            body.getColor().a = placed || later ? .55f : notNow ? .4f : 1;
        }
    }
    /** Walk, run and jump MP as the unit card writes them: "4 / 6", "4 / 6 / 4" with jump jets. */
    static String moves(UnitStatus unit) {
        return unit.walk() + " / " + unit.run() + (unit.jump() > 0 ? " / " + unit.jump() : "");
    }

    /**
     * A unit's gauge, on the queue's cards and the map's peek: the share of armor or internal structure left over a
     * faint track, in its whole colour until damaged and its damaged one after; nothing while unknown.
     */
    static final class ArmorBar extends Widget {
        private final UiKit ui;
        private final Color whole;
        private final Color damaged;
        float fraction = -1;

        ArmorBar(UiKit ui, Color whole, Color damaged) {
            this.ui = ui;
            this.whole = whole;
            this.damaged = damaged;
        }

        static ArmorBar armor(UiKit ui) {
            return new ArmorBar(ui, UiTheme.MINT, UiTheme.AMBER);
        }

        static ArmorBar structure(UiKit ui) {
            return new ArmorBar(ui, UiTheme.TEXT, UiTheme.CORAL);
        }

        /** Shows {@code value}, a share of 0 to 1 or negative when unknown. */
        void set(double value) {
            fraction = value < 0 ? -1 : (float) value;
        }

        @Override
        public float getPrefHeight() {
            return 3;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (fraction < 0) {
                return;
            }
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            ui.fill(batch, UiTheme.TRACK, alpha, getX(), getY(), getWidth(), getHeight());
            ui.fill(batch, fraction < .995f ? damaged : whole, alpha, getX(), getY(),
                  getWidth() * Math.min(1, fraction), getHeight());
            batch.setPackedColor(previous);
        }
    }
}
