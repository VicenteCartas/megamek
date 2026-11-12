/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;

/**
 * Another player's unit arriving during deployment: a pointer in its side's colour naming the unit, until the
 * arrival's notice ends. It bobs over the unit's hex while the camera shows it (the board's rings ripple there too,
 * GpuBoardOverlay#renderArrivals), and sits at the window's edge toward it otherwise. A click on it centres the board
 * on the unit; the camera never jumps there by itself.
 */
final class GpuArrivalCues implements GpuHud.Component {
    /** A unit another player placed: its side, board label, hex and board, and when it began to materialize. */
    record Arrival(int id, GpuBattleStatus.Side side, String label, Coords hex, int boardId, long start) { }

    /** An arrival and where its hex lies in stage units (y up), off the stage when the camera does not show it. */
    record Cue(Arrival arrival, Vector2 at) { }

    static final Color ALLY = Color.valueOf("8CB8FF");
    /** The pointers' inset from the window's edges, and a hex this far inside counts as on screen. */
    private static final float EDGE = 18;
    private static final float ARROW = 22;
    private static final float GAP = 4;
    private static final float FADE_IN_MILLIS = 200;
    /** How far above its hex's centre an on-screen pointer's tip stays, clear of the unit and its label. */
    private static final float ABOVE = 96;

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Group root = new Group();
    private final List<Pointer> pointers = new ArrayList<>();
    private List<Cue> cues = List.of();

    GpuArrivalCues(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this.ui = kit.ui;
        this.source = source;
        this.state = state;
        root.setName("arrival-cues");
        root.setTransform(false);
        root.setTouchable(Touchable.childrenOnly);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** This frame's arrivals, from the board view. */
    void cues(List<Cue> cues) {
        this.cues = cues;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        float width = inputs.metrics().width();
        float height = inputs.metrics().height();
        boolean hidden = inputs.dialog() != null || state.overview || width <= 4 * EDGE || height <= 4 * EDGE;
        long now = TimeUtils.millis();
        int shown = 0;
        for (Cue cue : hidden ? List.<Cue>of() : cues) {
            Vector2 at = cue.at();
            boolean onScreen = at.x >= EDGE && at.x <= width - EDGE && at.y >= EDGE && at.y <= height - EDGE;
            if (shown == pointers.size()) {
                pointers.add(new Pointer());
            }
            Pointer pointer = pointers.get(shown++);
            if (onScreen) {
                pointer.showOver(cue, width, height, now);
            } else {
                pointer.show(cue, width, height, now);
            }
        }
        for (int index = shown; index < pointers.size(); index++) {
            pointers.get(index).hide();
        }
    }

    /** One arrival's arrow at the edge and its name plate beside it, toward the board. */
    private final class Pointer {
        private final UiKit.Icon arrow = ui.icon("arrow-up", ARROW, Color.WHITE);
        private final Table plate = new Table();
        private final Label name = ui.label("", "hud-small", 11.5f, UiTheme.TEXT);
        private int unitId;

        Pointer() {
            plate.setBackground(ui.skin.getDrawable("tip"));
            plate.pad(3, 8, 3, 8);
            plate.add(name);
            plate.setTouchable(Touchable.enabled);
            arrow.setTouchable(Touchable.enabled);
            arrow.setSize(ARROW, ARROW);
            arrow.setOrigin(Align.center);
            ClickListener locate = new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    source.locateUnit(unitId);
                }
            };
            plate.addListener(locate);
            arrow.addListener(locate);
            root.addActor(arrow);
            root.addActor(plate);
            ui.tip(plate).getActor().setText(text("GpuBoard.hud.deploy.arrivalTip"));
        }

        void show(Cue cue, float width, float height, long now) {
            Arrival arrival = cue.arrival();
            name(arrival);
            // From the window's centre toward the hex, to where that line meets the inset edge.
            Vector2 toward = new Vector2(cue.at()).sub(width / 2, height / 2);
            if (toward.isZero(1e-3f)) {
                toward.set(0, 1);
            }
            float reach = Math.min(Math.abs((width / 2 - EDGE) / (toward.x == 0 ? 1e-6f : toward.x)),
                  Math.abs((height / 2 - EDGE) / (toward.y == 0 ? 1e-6f : toward.y)));
            Vector2 edge = new Vector2(toward).scl(reach).add(width / 2, height / 2);
            Vector2 direction = toward.nor();
            long age = now - arrival.start();
            // The arrow nudges toward the hex, to say "over there", without moving the plate.
            float nudge = 3 * MathUtils.sin(age / 1000f * 7);
            arrow.setPosition(edge.x - ARROW / 2 + direction.x * (nudge - ARROW / 2),
                  edge.y - ARROW / 2 + direction.y * (nudge - ARROW / 2));
            arrow.setRotation(direction.angleDeg() - 90);
            float extent = Math.abs(direction.x) * plate.getWidth() / 2 + Math.abs(direction.y) * plate.getHeight() / 2;
            Vector2 center = new Vector2(edge).mulAdd(direction, -(ARROW + GAP + extent));
            plate.setPosition(MathUtils.clamp(center.x - plate.getWidth() / 2, GAP, width - GAP - plate.getWidth()),
                  MathUtils.clamp(center.y - plate.getHeight() / 2, GAP, height - GAP - plate.getHeight()));
            reveal(age);
        }

        /** Over its hex on the screen: the arrow points down at the unit, its name plate above the arrow. */
        void showOver(Cue cue, float width, float height, long now) {
            Arrival arrival = cue.arrival();
            name(arrival);
            long age = now - arrival.start();
            float bob = 5 * MathUtils.sin(age / 1000f * 6);
            float tipY = Math.min(cue.at().y + ABOVE + bob, height - GAP - plate.getHeight() - GAP - ARROW);
            arrow.setRotation(180);
            arrow.setPosition(cue.at().x - ARROW / 2, tipY);
            plate.setPosition(MathUtils.clamp(cue.at().x - plate.getWidth() / 2, GAP, width - GAP - plate.getWidth()),
                  tipY + ARROW + GAP);
            reveal(age);
        }

        private void name(Arrival arrival) {
            unitId = arrival.id();
            Color color = arrival.side() == GpuBattleStatus.Side.ENEMY ? UiTheme.CORAL : ALLY;
            name.setText(text("GpuBoard.hud.deploy.arrival", arrival.label()));
            name.setColor(color);
            arrow.setColor(color);
            plate.pack();
        }

        private void reveal(long age) {
            float life = 1 - age / (float) GpuBattleView.ARRIVAL_NOTICE_MILLIS;
            float alpha = MathUtils.clamp(Math.min(age / FADE_IN_MILLIS, life * 4), 0, 1);
            arrow.getColor().a = alpha;
            plate.getColor().a = alpha;
            arrow.setVisible(true);
            plate.setVisible(true);
        }

        void hide() {
            arrow.setVisible(false);
            plate.setVisible(false);
        }
    }
}
