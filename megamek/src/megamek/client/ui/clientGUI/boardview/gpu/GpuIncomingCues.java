/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Board;
import megamek.common.board.Coords;

/**
 * Walk-on deployment: the units still waiting to walk on, marked at the board edge they enter from with how many and
 * from which round: the player's own (named in the tip), and outside double blind the other players' by side. The
 * mark sits on its edge while the camera shows it and at the window's edge toward it otherwise; a click centres the
 * board on the edge. Marks of one edge stack.
 */
final class GpuIncomingCues implements GpuHud.Component {
    /** Units of one side entering from one edge: its zone, a hex on that edge, their first and last rounds and lines. */
    record Edge(int zone, Coords hex, GpuBattleStatus.Side side, int round, int lastRound, int count,
          List<String> lines) { }

    /** An edge and where its hex lies in stage units (y up), off the stage when the camera does not show it. */
    record Cue(Edge edge, Vector2 at) { }

    private static final float EDGE_INSET = 18;
    private static final float STACK_GAP = 3;

    /**
     * The units waiting to walk on, by the edge they enter from: the own first, then the other players' by side
     * ({@code GpuBattleStatus.Snapshot#incoming}). A unit that may enter from any edge has none to mark.
     * {@code width} and {@code height} are the board's, in hexes.
     */
    static List<Edge> edges(GpuBattleStatus.Snapshot status, int width, int height) {
        Map<Integer, List<GpuBattleStatus.UnitStatus>> byZone = new LinkedHashMap<>();
        for (GpuBattleStatus.UnitStatus unit : status.units()) {
            if (unit.side() == GpuBattleStatus.Side.OWN && unit.walksOn() && unit.position() == null
                  && unit.walkOnZone() >= Board.START_NW && unit.walkOnZone() <= Board.START_W) {
                byZone.computeIfAbsent(unit.walkOnZone(), ignored -> new ArrayList<>()).add(unit);
            }
        }
        List<Edge> edges = new ArrayList<>(byZone.size() + status.incoming().size());
        for (Map.Entry<Integer, List<GpuBattleStatus.UnitStatus>> entry : byZone.entrySet()) {
            List<GpuBattleStatus.UnitStatus> units = new ArrayList<>(entry.getValue());
            units.sort((left, right) -> Integer.compare(left.walkOnRound(), right.walkOnRound()));
            List<String> lines = new ArrayList<>(units.size());
            for (GpuBattleStatus.UnitStatus unit : units) {
                lines.add(text("GpuBoard.hud.deploy.incomingUnit",
                      unit.label().isEmpty() ? unit.name() : unit.label(), unit.walkOnRound()));
            }
            edges.add(new Edge(entry.getKey(), edgeHex(entry.getKey(), width, height), GpuBattleStatus.Side.OWN,
                  units.get(0).walkOnRound(), units.get(units.size() - 1).walkOnRound(), units.size(), lines));
        }
        for (GpuBattleStatus.Incoming other : status.incoming()) {
            edges.add(new Edge(other.zone(), edgeHex(other.zone(), width, height), other.side(), other.round(),
                  other.lastRound(), other.count(), List.of()));
        }
        return edges;
    }

    /** The middle of the stretch of the board edge {@code zone} names (NW is 1, then clockwise to W, 8). */
    static Coords edgeHex(int zone, int width, int height) {
        int right = Math.max(0, width - 1);
        int bottom = Math.max(0, height - 1);
        return switch (zone) {
            case Board.START_NW -> new Coords(0, 0);
            case Board.START_N -> new Coords(right / 2, 0);
            case Board.START_NE -> new Coords(right, 0);
            case Board.START_E -> new Coords(right, bottom / 2);
            case Board.START_SE -> new Coords(right, bottom);
            case Board.START_S -> new Coords(right / 2, bottom);
            case Board.START_SW -> new Coords(0, bottom);
            default -> new Coords(0, bottom / 2);
        };
    }

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Group root = new Group();
    /** Marks on the board's edge: under the units' labels and the board's move and deploy controls. */
    private final Group board = new Group();
    private final List<Mark> marks = new ArrayList<>();
    private List<Cue> cues = List.of();

    GpuIncomingCues(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this.ui = kit.ui;
        this.source = source;
        this.state = state;
        root.setName("incoming-cues");
        root.setTransform(false);
        root.setTouchable(Touchable.childrenOnly);
        board.setName("incoming-cues-board");
        board.setTransform(false);
        board.setTouchable(Touchable.childrenOnly);
    }

    /** The layer for marks on a visible edge; {@link #actor()} holds the marks pinned to the window's edge. */
    Actor boardActor() {
        return board;
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** This frame's edges, from the board view. */
    void cues(List<Cue> cues) {
        this.cues = cues;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        float width = inputs.metrics().width();
        float height = inputs.metrics().height();
        boolean hidden = inputs.dialog() != null || state.overview || width <= 4 * EDGE_INSET
              || height <= 4 * EDGE_INSET;
        int shown = 0;
        Map<Integer, Integer> stacked = new java.util.HashMap<>();
        for (Cue cue : hidden ? List.<Cue>of() : cues) {
            if (shown == marks.size()) {
                marks.add(new Mark());
            }
            int below = stacked.merge(cue.edge().zone(), 1, Integer::sum) - 1;
            marks.get(shown++).show(cue, width, height, inputs.frame().status().round(), below);
        }
        for (int index = shown; index < marks.size(); index++) {
            marks.get(index).plate.setVisible(false);
        }
    }

    /** One edge's plate for one side: the walk-on mark in the side's colour, how many units and from which round. */
    private final class Mark {
        private final Table plate = new Table();
        private final UiKit.Icon icon = ui.icon("walk-on", 14, GpuArrivalCues.ALLY);
        private final Label count = ui.label("", "hud-small", 11.5f, UiTheme.TEXT);
        private final TextTooltip tip;
        private String tipText = "";
        private Coords hex;

        Mark() {
            plate.setBackground(ui.skin.getDrawable("tip"));
            plate.pad(3, 6, 3, 8);
            plate.add(icon).padRight(4);
            plate.add(count);
            plate.setTouchable(Touchable.enabled);
            plate.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (hex != null) {
                        source.centerOnHex(hex);
                    }
                }
            });
            board.addActor(plate);
            tip = ui.tip(plate);
        }

        /** {@code below}: how many marks of the same edge come before this one, stacked away from the board. */
        void show(Cue cue, float width, float height, int round, int below) {
            Edge edge = cue.edge();
            hex = edge.hex();
            Color color = edge.side() == GpuBattleStatus.Side.ENEMY ? UiTheme.CORAL : GpuArrivalCues.ALLY;
            icon.setColor(color);
            count.setText(text(edge.round() == edge.lastRound() ? "GpuBoard.hud.deploy.incoming"
                  : "GpuBoard.hud.deploy.incomingFrom", edge.count(), Math.max(edge.round(), round)));
            String text = switch (edge.side()) {
                case OWN -> text("GpuBoard.hud.deploy.incomingTip") + "\n" + String.join("\n", edge.lines());
                case ALLY -> text("GpuBoard.hud.deploy.incomingAllyTip");
                case ENEMY -> text("GpuBoard.hud.deploy.incomingEnemyTip");
            };
            if (!text.equals(tipText)) {
                tipText = text;
                tip.getActor().setText(text);
            }
            plate.pack();
            // Toward the edge from the window's centre, held inside the window.
            Vector2 at = new Vector2(cue.at());
            boolean onScreen = at.x >= EDGE_INSET && at.x <= width - EDGE_INSET && at.y >= EDGE_INSET
                  && at.y <= height - EDGE_INSET;
            if (!onScreen) {
                Vector2 toward = new Vector2(at).sub(width / 2, height / 2);
                if (toward.isZero(1e-3f)) {
                    toward.set(0, 1);
                }
                float reach = Math.min(Math.abs((width / 2 - EDGE_INSET) / (toward.x == 0 ? 1e-6f : toward.x)),
                      Math.abs((height / 2 - EDGE_INSET) / (toward.y == 0 ? 1e-6f : toward.y)));
                at.set(toward).scl(reach).add(width / 2, height / 2);
            }
            // A later mark of the same edge sits beside the earlier ones, away from the window's middle vertically.
            float lift = below * (plate.getHeight() + STACK_GAP);
            float y = at.y - plate.getHeight() / 2 + (at.y >= height / 2 ? lift : -lift);
            if (y < EDGE_INSET || y > height - EDGE_INSET - plate.getHeight()) {
                y = at.y - plate.getHeight() / 2 + (at.y >= height / 2 ? -lift : lift);
            }
            plate.setPosition(MathUtils.clamp(at.x - plate.getWidth() / 2, EDGE_INSET, width - EDGE_INSET - plate.getWidth()),
                  MathUtils.clamp(y, EDGE_INSET, height - EDGE_INSET - plate.getHeight()));
            plate.getColor().a = onScreen ? 1 : 0.8f;
            // Pinned marks stay over the side panels; a mark on its edge lies on the board, under its controls.
            Group layer = onScreen ? board : root;
            if (plate.getParent() != layer) {
                layer.addActor(plate);
            }
            plate.setVisible(true);
        }
    }
}
