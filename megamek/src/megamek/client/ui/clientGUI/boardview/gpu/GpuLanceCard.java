/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.enums.GamePhase;

/**
 * The lower-left card while an allied bot's lance is selected (CASPAR only; Princess takes orders unit by unit): the
 * lance's name under a LANCE scope chip, its bot and size, one row per unit (armor, heat and whether it has acted; a
 * click inspects that unit alone) and the Orders band the whole lance follows. It fills the unit card's root, so it
 * stands where the unit card does. It also builds the scope chip and the Orders band the unit card shares.
 */
final class GpuLanceCard {
    private static final Color ROW_HOVER = new Color(1, 1, 1, .06f);
    private static final Color BAND_FILL = new Color(1, 1, 1, .04f);
    private static final Color BAND_EDGE = new Color(1, 1, 1, .08f);
    private static final Color CHIP_EDGE = new Color(1, 1, 1, .28f);
    private static final float ROW_HEIGHT = 22;
    private static final float BAR_WIDTH = 64;
    /** The units table's width, so the lance form is as wide as the unit card it replaces and names fit. */
    private static final float TABLE_WIDTH = 276;
    private static final float TABLE_WIDTH_LOW = 256;

    /** One unit's row: what the table shows of it. */
    private record Row(int id, String name, double armor, int heat, int heatRgb, String state, Color stateColor) { }

    /** What the card shows; it is rebuilt when this changes. */
    private record Shown(GpuBots.Lance lance, String bot, List<Row> rows, boolean low) { }

    private final UiKit ui;
    private final GpuHudState state;
    private Shown shown;

    GpuLanceCard(UiKit ui, GpuHudState state) {
        this.ui = ui;
        this.state = state;
    }

    /** Forgets the shown lance, so the next {@link #update} rebuilds the card. */
    void forget() {
        shown = null;
    }

    /** Shows {@code lance} of {@code bot} in {@code root}, rebuilding it only when what it shows changed. */
    void update(Table root, GpuBots.BotRow bot, GpuBots.Lance lance, GamePhase phase, boolean low) {
        List<Row> rows = new ArrayList<>();
        for (int id : lance.units()) {
            GpuBattleStatus.UnitStatus unit = state.presented(id);
            if (unit != null) {
                rows.add(row(unit, phase));
            }
        }
        Shown next = new Shown(lance, bot.name(), rows, low);
        if (next.equals(shown)) {
            return;
        }
        shown = next;
        root.setBackground(ui.skin.getDrawable("panel"));
        root.clearChildren();
        root.pad(low ? 12 : 14, low ? 14 : 16, 14, low ? 14 : 16);
        root.add(header(next, low)).growX().row();
        root.add(table(next)).growX().minWidth(low ? TABLE_WIDTH_LOW : TABLE_WIDTH).padTop(low ? 8 : 10).row();
        root.add(ordersBand(ui, text("GpuBoard.hud.orders.title"), lance.orders(), "lance-card-orders")).growX()
              .padTop(low ? 10 : 12);
    }

    private Row row(GpuBattleStatus.UnitStatus unit, GamePhase phase) {
        String state;
        Color color;
        if (unit.destroyed()) {
            state = text("GpuBoard.hud.orders.state.destroyed");
            color = UiTheme.CORAL;
        } else if (unit.pending() && pendingKey(phase) != null) {
            state = text(pendingKey(phase));
            color = UiTheme.TEXT;
        } else if (unit.done()) {
            state = text("GpuBoard.hud.orders.state.done");
            color = UiTheme.MUTED;
        } else {
            state = "";
            color = UiTheme.MUTED;
        }
        return new Row(unit.id(), GpuUnitCard.unitName(unit), unit.armor(), unit.heat(), unit.heatRgb(), state, color);
    }

    /** The state of a unit still to act in {@code phase} ("To move"), or null in a phase in which none acts in turn. */
    private static String pendingKey(GamePhase phase) {
        if (phase.isDeployment()) {
            return "GpuBoard.hud.orders.state.deploy";
        } else if (phase.isMovement()) {
            return "GpuBoard.hud.orders.state.move";
        } else if (phase.isFiring() || phase.isTargeting() || phase.isOffboard()) {
            return "GpuBoard.hud.orders.state.fire";
        } else if (phase.isPhysical() || phase.isPremovement() || phase.isPreFiring()) {
            return "GpuBoard.hud.orders.state.act";
        }
        return null;
    }

    private Table header(Shown next, boolean low) {
        Table header = new Table();
        header.left();
        Label name = ui.label(UiTheme.upper(next.lance().name()), "hud-heading", low ? 18 : 20, UiTheme.TEXT);
        name.setEllipsis(true);
        name.setName("lance-card-name");
        header.add(name).minWidth(0).growX().left().height(low ? 22 : 24);
        header.add(scopeChip(ui, "account-tree", text("GpuBoard.hud.orders.scopeLance"), "lance-card-scope"))
              .right().padLeft(8);
        UiButton close = ui.closeButton(state::clearLance);
        close.setName("lance-card-close");
        header.add(close).size(28).padLeft(2).padRight(-6);
        header.row();
        Label subtitle = ui.label(text("GpuBoard.hud.orders.lanceSubtitle", next.bot(), next.rows().size()),
              "hud-body", low ? 11 : 12, UiTheme.MUTED);
        subtitle.setEllipsis(true);
        subtitle.setName("lance-card-subtitle");
        header.add(subtitle).colspan(3).left().minWidth(0).growX().height(low ? 16 : 17);
        return header;
    }

    private Table table(Shown next) {
        Table table = new Table();
        table.setName("lance-card-units");
        table.top().left();
        table.add(caption("GpuBoard.hud.orders.column.unit", Align.left)).growX().left();
        table.add(caption("GpuBoard.hud.orders.column.armor", Align.left)).width(BAR_WIDTH).padLeft(8);
        table.add(caption("GpuBoard.hud.orders.column.heat", Align.right)).width(30).padLeft(8);
        table.add(caption("GpuBoard.hud.orders.column.state", Align.right)).width(66).padLeft(8);
        table.row();
        Texture white = ui.skin.get("white", Texture.class);
        for (Row row : next.rows()) {
            table.add(new UnitRow(row, white)).colspan(4).growX().height(ROW_HEIGHT).row();
        }
        return table;
    }

    private Label caption(String key, int align) {
        Label caption = ui.label(UiTheme.upper(text(key)), "hud-caption", 10, UiTheme.MUTED);
        caption.setAlignment(align);
        return caption;
    }

    /** A unit's row: its name, armor bar, heat and state; a click inspects the unit, which ends the lance selection. */
    private final class UnitRow extends Table {
        private final Texture white;
        private boolean hovered;

        UnitRow(Row row, Texture white) {
            this.white = white;
            setName("lance-card-unit-" + row.id());
            setTouchable(Touchable.enabled);
            Label name = ui.label(row.name(), "hud-name", 12, row.stateColor() == UiTheme.CORAL ? UiTheme.MUTED
                  : UiTheme.TEXT);
            name.setEllipsis(true);
            add(name).minWidth(0).growX().left().padLeft(4);
            add(new ArmorBar(row.armor(), white)).width(BAR_WIDTH).height(4).padLeft(8);
            Color heatColor = UiTheme.MUTED;
            if (row.heat() > 0) {
                heatColor = new Color();
                Color.argb8888ToColor(heatColor, row.heatRgb() == 0 ? Color.argb8888(UiTheme.AMBER) : row.heatRgb());
                heatColor.a = 1;
            }
            Label heat = ui.label(Integer.toString(row.heat()), "hud-name", 12, heatColor);
            heat.setAlignment(Align.right);
            add(heat).width(30).padLeft(8);
            Label state = ui.label(row.state(), "hud-body", 11.5f, row.stateColor());
            state.setAlignment(Align.right);
            state.setEllipsis(true);
            add(state).width(66).padLeft(8).padRight(4);
            addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    GpuLanceCard.this.state.inspected = row.id();
                }

                @Override
                public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                    super.enter(event, x, y, pointer, fromActor);
                    hovered = true;
                }

                @Override
                public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                    super.exit(event, x, y, pointer, toActor);
                    hovered = isOver();
                }
            });
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (hovered) {
                batch.setColor(ROW_HOVER.r, ROW_HOVER.g, ROW_HOVER.b, ROW_HOVER.a * parentAlpha);
                batch.draw(white, getX(), getY(), getWidth(), getHeight());
            }
            batch.setColor(BAND_EDGE.r, BAND_EDGE.g, BAND_EDGE.b, BAND_EDGE.a * parentAlpha);
            batch.draw(white, getX(), getY() + getHeight() - 1, getWidth(), 1);
            batch.setColor(Color.WHITE);
            super.draw(batch, parentAlpha);
        }
    }

    /** The armor left as a bar: mint, amber under half, coral under a quarter; a dim track for an unknown value. */
    private static final class ArmorBar extends Actor {
        private final double armor;
        private final Texture white;

        ArmorBar(double armor, Texture white) {
            this.armor = armor;
            this.white = white;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            batch.setColor(1, 1, 1, .12f * parentAlpha);
            batch.draw(white, getX(), getY(), getWidth(), getHeight());
            if (armor >= 0) {
                Color fill = armor < .25 ? UiTheme.CORAL : armor < .5 ? UiTheme.AMBER : UiTheme.MINT;
                batch.setColor(fill.r, fill.g, fill.b, fill.a * parentAlpha);
                batch.draw(white, getX(), getY(), getWidth() * (float) Math.min(1, armor), getHeight());
            }
            batch.setColor(Color.WHITE);
        }
    }

    /**
     * What a card selects, before its close button: an outlined chip of an icon and an upper-case word (UNIT, LANCE),
     * so the player reads whether the orders shown are one unit's or a whole lance's.
     */
    static Table scopeChip(UiKit ui, String icon, String word, String name) {
        Table chip = new Table();
        chip.setName(name);
        chip.setBackground(new UiTheme.EdgeBox(ui.skin.get("white", Texture.class), null, CHIP_EDGE, 1, 1, 1, 1));
        chip.pad(2, 6, 2, 7);
        chip.add(ui.icon(icon, 12, UiTheme.TEXT)).padRight(4);
        chip.add(ui.label(UiTheme.upper(word), "hud-name", 10.5f, UiTheme.TEXT));
        return chip;
    }

    /** The Orders band at a card's foot: a caption ("ORDERS", "ORDERS · FOLLOWS FIRE LANCE") over the order line. */
    static Table ordersBand(UiKit ui, String caption, String line, String name) {
        Table band = new Table();
        band.setName(name);
        band.setBackground(new UiTheme.EdgeBox(ui.skin.get("white", Texture.class), BAND_FILL, BAND_EDGE, 1, 0, 0, 0));
        band.pad(7, 10, 8, 10);
        band.left();
        Label title = ui.label(UiTheme.upper(caption), "hud-caption", 10, UiTheme.MUTED);
        title.setEllipsis(true);
        title.setName(name + "-caption");
        band.add(title).minWidth(0).growX().left().row();
        Label text = ui.label(line, "hud-body", 12.5f, UiTheme.TEXT);
        text.setWrap(true);
        text.setName(name + "-line");
        band.add(text).minWidth(0).growX().left().padTop(2);
        return band;
    }
}
