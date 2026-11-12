/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;

/**
 * The hovered hex's card, shown as a tooltip: once the pointer rests on a hex of the board for {@link #DELAY} seconds,
 * the hex and the lines its menu's header shows ({@link GpuContextMenu#hexLines}), in small text on a faint plate
 * beside the pointer; a long terrain line breaks between its terms, never inside one. It goes when the pointer leaves
 * that hex or the board, at a press, and while a menu or a dialog is open. It never takes input.
 *
 * <p>In the local deployment turn it is the placement's readout: it shows at once and follows the pointer, and adds
 * how far the nearest enemy stands and, in coral, a hazard placing a unit there brings (as the ghost's red tint
 * does).</p>
 *
 * <p>In deployment, a unit on the map that is not the player's shows what its queue card tells instead: its name, then
 * its tonnage, pilot, skills and MP, and whether it starts damaged.</p>
 */
final class GpuHexTip implements GpuHud.Component {
    /** Seconds the pointer rests on a hex before its card shows: Swing's default tooltip delay. */
    static final float DELAY = .75f;
    /** How far, in stage units, the pointer may drift while it rests. */
    private static final float DRIFT = 3;
    /** The card's corner right of and below the pointer, clear of the cursor. */
    private static final float OFFSET_X = 14, OFFSET_Y = 20;
    /** The card's widest line, as wide as the HUD's widget tooltips. */
    private static final float WRAP = 340;

    private final GpuHudState state;
    private final GpuContextMenu menu;
    private final Table root = new Table();
    private final Table card = new Table();
    private final Label head;
    private final Label body;
    private final Label alert;
    /** Measures lines in the card's text; never shown. */
    private final Label measure;
    private final Cell<Label> headCell;
    private final Cell<Label> bodyCell;
    private final Cell<Label> alertCell;
    /** The peeked unit's armor and structure, under its card's text. */
    private final Table gauges = new Table();
    private final GpuDeploymentQueue.ArmorBar armor;
    private final GpuDeploymentQueue.ArmorBar structure;
    private final Cell<Table> gaugesCell;
    private final Vector2 pointer = new Vector2();
    private final Vector2 rest = new Vector2();
    /** The hex the pointer rests on, or null. */
    private Coords restHex;
    private float rested;
    private String shown = "";

    GpuHexTip(GpuHudKit kit, GpuHudState state, GpuContextMenu menu) {
        this.state = state;
        this.menu = menu;
        UiKit ui = kit.ui;
        root.setName("hex-tip");
        root.setTouchable(Touchable.disabled);
        card.setBackground(ui.skin.getDrawable("tip"));
        card.setVisible(false);
        head = ui.label("", "hud-small", 11.5f, UiTheme.TEXT);
        body = ui.label("", "hud-small", 11.5f, UiTheme.MUTED);
        measure = ui.label("", "hud-small", 11.5f, UiTheme.TEXT);
        headCell = card.add(head).left();
        card.row();
        bodyCell = card.add(body).left();
        card.row();
        alert = ui.label("", "hud-small", 11.5f, UiTheme.CORAL);
        alertCell = card.add(alert).left();
        card.row();
        armor = GpuDeploymentQueue.ArmorBar.armor(ui);
        structure = GpuDeploymentQueue.ArmorBar.structure(ui);
        gauges.add(ui.label(text("GpuBoard.hud.common.armor"), "hud-small", 10, UiTheme.MUTED)).left().padRight(6);
        gauges.add(armor).growX().height(4).row();
        gauges.add(ui.label(text("GpuBoard.hud.common.structure"), "hud-small", 10, UiTheme.MUTED)).left()
              .padRight(6);
        gauges.add(structure).growX().height(4);
        gaugesCell = card.add(gauges).growX().padTop(4);
        gaugesCell.setActor(null);
        root.addActor(card);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        Coords hex = inputs.view().hovered();
        Stage stage = root.getStage();
        if (hex == null || stage == null || Gdx.input.isTouched() || menu.isOpen() || inputs.dialog() != null
              || state.dialog != GpuHudState.Dialog.NONE) {
            restHex = null;
            hide();
            return;
        }
        stage.screenToStageCoordinates(pointer.set(Gdx.input.getX(), Gdx.input.getY()));
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        boolean placing = status.myTurn() && status.phase().isDeployment();
        GpuBattleStatus.UnitStatus peeked = status.phase().isDeployment() ? state.presented(inputs.view().hoveredUnit())
              : null;
        if (peeked != null && peeked.side() != GpuBattleStatus.Side.OWN && !peeked.sensorContact()) {
            // Deployment's peek at a unit on the map that is not the player's: what the queue's card tells of it.
            if (!hex.equals(restHex)) {
                hide();
                restHex = hex;
                rested = 0;
            }
            rested += Gdx.graphics.getDeltaTime();
            if (placing || rested >= DELAY) {
                rest.set(pointer);
                armor.set(peeked.armor());
                structure.set(peeked.structure());
                show(GpuDeploymentQueue.title(peeked), GpuDeploymentQueue.facts(peeked),
                      peeked.damageLevel() > 0 ? text("GpuBoard.hud.deploy.damaged") : null,
                      peeked.armor() >= 0 || peeked.structure() >= 0, stage);
            }
            return;
        }
        if (placing) {
            // The placement's readout: at once, beside the pointer wherever it goes.
            restHex = hex;
            rest.set(pointer);
            BoardScene.Tile tile = inputs.frame().scene() == null ? null : inputs.frame().scene().tile(hex);
            String lines = GpuContextMenu.hexLines(inputs.frame(), hex);
            int nearest = nearestEnemy(hex);
            String enemy = nearest < 0 ? "" : text("GpuBoard.hud.deploy.nearestEnemy", nearest);
            String body = lines == null ? enemy : enemy.isEmpty() ? lines : lines + "\n" + enemy;
            show(text("GpuBoard.hud.context.hex", hex.getBoardNum()), body, hazard(tile), false, stage);
            return;
        }
        if (!hex.equals(restHex) || !card.isVisible() && pointer.dst(rest) > DRIFT) {
            // A new rest: on another hex, or the pointer still moving before the card showed.
            if (!hex.equals(restHex)) {
                hide();
            }
            restHex = hex;
            rest.set(pointer);
            rested = 0;
            return;
        }
        rested += Gdx.graphics.getDeltaTime();
        String lines = rested < DELAY ? null : GpuContextMenu.hexLines(inputs.frame(), hex);
        if (lines != null) {
            show(text("GpuBoard.hud.context.hex", hex.getBoardNum()), lines, null, false, stage);
        }
    }

    /** Hexes from {@code hex} to the nearest enemy on the board, or -1 when none stands on it. */
    private int nearestEnemy(Coords hex) {
        int nearest = -1;
        for (GpuBattleStatus.UnitStatus unit : state.presentedUnits()) {
            if (unit.side() == GpuBattleStatus.Side.ENEMY && unit.position() != null && !unit.destroyed()) {
                int distance = hex.distance(unit.position());
                nearest = nearest < 0 ? distance : Math.min(nearest, distance);
            }
        }
        return nearest;
    }

    /**
     * What standing on the tile harms a unit with, in a word for the readout: fire, magma, a hazardous liquid, black
     * ice or ice over water that may break; null when nothing does (or no tile).
     */
    static String hazard(BoardScene.Tile tile) {
        if (tile == null) {
            return null;
        }
        BoardLiquid.Kind liquid = tile.liquid() == null ? BoardLiquid.Kind.NONE : tile.liquid().kind();
        String key = tile.fireSmoke() != null && tile.fireSmoke().fire() > 0 ? "fire"
              : liquid == BoardLiquid.Kind.MAGMA || liquid == BoardLiquid.Kind.MAGMA_CRUST ? "magma"
              : liquid == BoardLiquid.Kind.HAZARDOUS ? "liquid"
              : tile.frozen() && tile.waterDepth() > 0 ? "ice"
              : tile.blackIce() ? "blackIce" : null;
        return key == null ? null : text("GpuBoard.hud.deploy.hazard." + key);
    }

    /**
     * Shows the hex's {@code title} and {@code lines} at the resting point; laid out again only when they change. The
     * head, the title and the terrain line, breaks only between its terms ({@link #balance}); MegaMek's lines below it
     * stay as they are.
     */
    private void show(String title, String lines, String warning, boolean gauged, Stage stage) {
        if (lines == null) {
            lines = "";
        }
        String text = title + "\n" + lines + "\n" + warning + "\n" + gauged;
        if (!text.equals(shown)) {
            shown = text;
            int newline = lines.indexOf('\n');
            List<String> terms = new ArrayList<>(List.of(title));
            if (!lines.isEmpty()) {
                terms.addAll(List.of((newline < 0 ? lines : lines.substring(0, newline))
                      .split(Pattern.quote(GpuContextMenu.SEPARATOR))));
            }
            List<String> headLines = balance(terms, this::width, WRAP);
            List<String> bodyLines = newline < 0 ? List.of() : lines.substring(newline + 1).lines().toList();
            head.setText(String.join("\n", headLines));
            body.setText(String.join("\n", bodyLines));
            bodyCell.setActor(bodyLines.isEmpty() ? null : body);
            // Only a term or a line wider than the card on its own still wraps, between its words.
            double widest = Stream.concat(headLines.stream(), bodyLines.stream()).mapToDouble(this::width).max()
                  .orElse(0);
            float width = (float) Math.min(WRAP, widest) + 1;
            fit(headCell, head, width);
            fit(bodyCell, body, width);
            alert.setText(warning == null ? "" : warning);
            alertCell.setActor(warning == null ? null : alert);
            gaugesCell.setActor(gauged ? gauges : null);
            card.pack();
        }
        float x = rest.x + OFFSET_X;
        float y = rest.y - OFFSET_Y - card.getHeight();
        if (x + card.getWidth() > stage.getWidth()) { x = rest.x - OFFSET_X - card.getWidth(); }
        if (y < 0) { y = rest.y + OFFSET_Y; }
        card.setPosition(Math.max(0, x), Math.min(y, stage.getHeight() - card.getHeight()));
        card.setVisible(true);
    }

    private void hide() {
        card.setVisible(false);
    }

    /** A line's width in the card's text, which the head and the body share but for its colour. */
    private double width(String line) {
        measure.setText(line);
        return measure.getPrefWidth();
    }

    /**
     * The terms joined into lines that break only between terms: the fewest lines no wider than {@code max} (a term
     * wider than that keeps a line of its own), and of those breaks the one whose widest line is narrowest, so the
     * lines come out even, as CSS "text-wrap: balance" evens a heading.
     */
    static List<String> balance(List<String> terms, ToDoubleFunction<String> width, double max) {
        double[] widths = terms.stream().mapToDouble(width).toArray();
        double gap = width.applyAsDouble(GpuContextMenu.SEPARATOR);
        int fewest = breaks(widths, gap, max).size();
        double low = Arrays.stream(widths).max().orElse(0);
        double high = Math.max(low, max);
        // The narrowest limit that keeps that many lines; 30 halvings settle it far below a unit.
        for (int step = 0; step < 30; step++) {
            double limit = (low + high) / 2;
            if (breaks(widths, gap, limit).size() <= fewest) { high = limit; } else { low = limit; }
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int end : breaks(widths, gap, high)) {
            lines.add(String.join(GpuContextMenu.SEPARATOR, terms.subList(start, end)));
            start = end;
        }
        return lines;
    }

    /** Where each line ends (exclusive) when the terms fill each line up to {@code limit} in turn. */
    private static List<Integer> breaks(double[] widths, double gap, double limit) {
        List<Integer> ends = new ArrayList<>();
        int start = 0;
        double line = 0;
        for (int index = 0; index < widths.length; index++) {
            double next = index == start ? widths[index] : line + gap + widths[index];
            if (index > start && next > limit) {
                ends.add(index);
                start = index;
                next = widths[index];
            }
            line = next;
        }
        ends.add(widths.length);
        return ends;
    }

    /** A label wrapped at {@code width}, so a long line breaks instead of widening the card. */
    private static void fit(Cell<Label> cell, Label label, float width) {
        label.setWrap(true);
        label.setWidth(width);
        cell.width(width);
    }
}
