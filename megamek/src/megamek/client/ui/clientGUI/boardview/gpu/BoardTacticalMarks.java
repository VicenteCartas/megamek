/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.TacticalGlyphs;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.StringDrawer;
import megamek.client.ui.util.UIUtil;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.server.props.OrbitalBombardment;

/**
 * EDT: the game's tactical markings in tactical units, drawn on the 3D board's hex lattice. What is marked, where and
 * in which colour comes from the client state (its marks and game); this class only shapes it.
 */
final class BoardTacticalMarks {
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final float W = BoardGeometry.TILE_WIDTH, H = BoardGeometry.TILE_HEIGHT;
    /** The heat map's turn label baseline below the hex centre. */
    private static final float HEAT_LABEL_BASELINE = 9;
    /** A movement vector starts and ends this far from the hex centres, clear of the unit standing there. */
    private static final float VECTOR_CLEARANCE = 18;
    private static final float VECTOR_WIDTH = 4, VECTOR_HEAD_WIDTH = 8, VECTOR_HEAD_LENGTH = 30;
    /** Upright visual-range boundary, half a level, 0 transparent to 1 opaque. */
    private static final float VISUAL_RANGE_HEIGHT = .5f, VISUAL_RANGE_OPACITY = .75f;
    /** Include the map perimeter in sensor/visual ranges; false keeps only boundaries within the map. */
    private static final boolean RANGE_SHOWS_MAP_BORDER = false;
    /** The crane countdown's camera-facing label. */
    private static final Font COUNTDOWN_FONT = new Font(MMConstants.FONT_SANS_SERIF, Font.BOLD, 28);

    private record OverlayKey(Board board, int width, int height, Set<Coords> embedded, Color sheets) { }

    private OverlayKey overlayKey;
    private BoardTactical overlays = BoardTactical.EMPTY;
    private BoardTactical previous = BoardTactical.EMPTY;

    /** The markings now, without the path's own steps when the move plan draws its route; equal captures keep one. */
    BoardTactical capture(BoardClientState view, boolean planner) {
        return capture(view, planner, List.of());
    }

    /** As {@link #capture(BoardClientState, boolean)}, with the allied bots' trail dots the client state lacks. */
    BoardTactical capture(BoardClientState view, boolean planner, List<? extends TacticalMark> botOrders) {
        Board board = view.getBoard();
        Game game = view.getGame();
        List<TacticalMark> marks = view.getMarks();
        BoardTacticalGraphics graphics = new BoardTacticalGraphics();
        try {
            graphics.setClip(extent(board));
            UIUtil.setHighQualityRendering(graphics);
            graphics.append(overlays(board));
            altitudeBands(graphics, view);
            heatMap(graphics, view);
            // Areas lie under the other markings, as on the 2D board.
            marks(graphics, view, marks.stream().filter(BoardTacticalMarks::isArea).toList());
            orbitalBombardment(graphics, view);
            BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HIDE_DURING_MOVEMENT,
                  local -> hexes(local, view.getHighlightedEntityHexes(), hex -> outline(hex, UIUtil.uiGreen(), 2)));
            demolitionHighlights(graphics, view);
            marks(graphics, view, view.getLosEnds());
            if (view.showsAllDeployment()) {
                BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HIDE_DURING_MOVEMENT,
                      local -> allDeployment(local, view));
            }
            BoardTacticalGraphics.drawDeployment(graphics, local -> deployment(local, view));
            if (GUIP.getShowUnitLinks()) { marks(graphics, view, view.getC3Links()); }
            if (board.isGround()) {
                marks(graphics, view, view.getVtolAttacks());
                marks(graphics, view, view.getFlyOvers());
            }
            BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HOLD_DURING_PLAYBACK,
                  local -> driftLines(local, view));
            if (view.showsMovementVectors()) { marks(graphics, view, view.getMovementVectors()); }
            if (game.getPhase().isFiring()) {
                BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HIDE_DURING_MOVEMENT,
                      local -> hexes(local, view.getStrafingCoords(), hex -> ring(hex, Color.YELLOW, 3)));
            }
            marks(graphics, view, marks.stream().filter(mark -> !isArea(mark)).toList());
            marks(graphics, view, botOrders);
            if (!planner) { marks(graphics, view, view.getPathSteps()); }
            marks(graphics, view, view.getFlightPath());
            BoardTactical captured = graphics.snapshot();
            // Compare on Swing after drawing everything, keeping unchanged snapshots cheap on the GL thread.
            if (!captured.equals(previous)) { previous = captured; }
            return previous;
        } finally {
            graphics.dispose();
        }
    }

    private static boolean isArea(TacticalMark mark) {
        return mark instanceof TacticalMark.Envelope || mark instanceof TacticalMark.ControlZone
              || mark instanceof TacticalMark.SensorRange || mark instanceof TacticalMark.FacingModifier
              || mark instanceof TacticalMark.HexText;
    }

    /** The board's bounds in tactical units; markings stop at the map edge. */
    private static Rectangle2D extent(Board board) {
        return new Rectangle2D.Float(0, 0, (board.getWidth() * .75f + .25f) * W, (board.getHeight() + .5f) * H);
    }

    /** {@code painter} on each hex's annotation plane, about its centre. */
    private static void hexes(BoardTacticalGraphics graphics, Iterable<Coords> hexes,
          Consumer<BoardTacticalGraphics> painter) {
        for (Coords coords : hexes) {
            BoardTacticalGraphics local = BoardTacticalGraphics.onHexPlane(graphics, coords);
            try {
                painter.accept(local);
            } finally {
                local.dispose();
            }
        }
    }

    private static void ring(BoardTacticalGraphics hex, Color color, double width) {
        ring(hex, color, 0, width);
    }

    private static void ring(BoardTacticalGraphics hex, Color color, double padding, double width) {
        hex.setColor(color);
        hex.fillHexBorder(padding, width);
    }

    private static void outline(BoardTacticalGraphics hex, Color color, float width) {
        outline(hex, color, new BasicStroke(width));
    }

    private static void outline(BoardTacticalGraphics hex, Color color, BasicStroke stroke) {
        hex.setColor(color);
        hex.setStroke(stroke);
        hex.draw(BoardTacticalGeometry.insetHexagon(0));
    }

    /** Black base, then {@code color} dashes on top so the gaps show black underneath: a hazard stripe. */
    private static void hazardOutline(BoardTacticalGraphics hex, Color color, float dash) {
        float width = TacticalStyle.HAZARD_OUTLINE_WIDTH;
        outline(hex, TacticalStyle.HAZARD_BACKING_COLOR, new BasicStroke(width, BasicStroke.CAP_BUTT,
              BasicStroke.JOIN_MITER));
        outline(hex, color, new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
              new float[] { dash, dash }, 0));
    }

    /** Embedded-board markers and map-sheet borders change with the board and settings only. */
    private BoardTactical overlays(Board board) {
        Color sheets = GUIP.getShowMapSheets() ? GUIP.getMapsheetColor() : null;
        var key = new OverlayKey(board, board.getWidth(), board.getHeight(), Set.copyOf(board.embeddedBoardCoords()),
              sheets);
        if (key.equals(overlayKey)) { return overlays; }
        BoardTacticalGraphics graphics = new BoardTacticalGraphics();
        try {
            graphics.setClip(extent(board));
            UIUtil.setHighQualityRendering(graphics);
            Shape marker = new Rectangle2D.Float(-W / 4 + 1, -H / 2 + 2, W / 2 - 2, H - 4);
            hexes(graphics, key.embedded().stream().filter(board::contains)
                  .sorted(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY)).toList(), hex -> {
                hex.setColor(new Color(0, 140, 0, 120));
                hex.fill(marker);
                hex.setColor(new Color(0, 0, 0, 150));
                hex.setStroke(new BasicStroke(3.5f));
                hex.draw(marker);
                hex.setColor(new Color(0, 140, 0));
                hex.setStroke(new BasicStroke(1.5f));
                hex.draw(marker);
            });
            if (sheets != null) {
                // Shared edges cross into both hexes; all coverage fills must be below the borders.
                for (int x = 0; x < board.getWidth(); x++) {
                    for (int y = 0; y < board.getHeight(); y++) {
                        Coords coords = new Coords(x, y);
                        int borders = BoardClientState.mapSheetBorders(coords);
                        if (borders == 0) { continue; }
                        Path2D path = new Path2D.Float();
                        for (int direction = 0; direction < 6; direction++) {
                            if ((borders & (1 << direction)) != 0) {
                                path.append(BoardTacticalGeometry.sideLine(direction, false, false, 0), false);
                            }
                        }
                        hexes(graphics, List.of(coords), hex -> {
                            hex.setColor(new Color(0, 0, 0, sheets.getAlpha() / 2));
                            hex.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            hex.draw(path);
                            hex.setColor(sheets);
                            hex.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            hex.draw(path);
                        });
                    }
                }
            }
            overlays = graphics.snapshot();
            overlayKey = key;
            return overlays;
        } finally {
            graphics.dispose();
        }
    }

    /** A high-altitude map's bands: the atmosphere's rows, the space-atmosphere interface and the ground row. */
    private static void altitudeBands(BoardTacticalGraphics graphics, BoardClientState view) {
        Board board = view.getBoard();
        if (!board.isHighAltitude()) { return; }
        Path2D.Float underInterface = new Path2D.Float();
        underInterface.moveTo(-W / 4, -H / 2);
        underInterface.lineTo(0, -H / 2);
        underInterface.lineTo(0, H / 2);
        underInterface.lineTo(-W / 4, H / 2);
        underInterface.lineTo(-W / 2, 0);
        underInterface.closePath();
        Path2D.Float groundStrip = new Path2D.Float();
        groundStrip.moveTo(-W / 4, -H / 2);
        groundStrip.lineTo(-W / 4, H / 2);
        groundStrip.lineTo(-W / 2, 0);
        groundStrip.closePath();
        BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HOLD_DURING_PLAYBACK, layer -> {
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    Coords coords = new Coords(x, y);
                    BoardClientState.AltitudeBand band = view.altitudeBand(coords);
                    if (band == null) { continue; }
                    hexes(layer, List.of(coords), hex -> {
                        if (band.stars() != null) {
                            hex.setColor(band.stars());
                            hex.fill(BoardTacticalGeometry.insetHexagon(0));
                            hex.setColor(band.atmosphere());
                            hex.fill(BoardTacticalGeometry.insetHexagon(0));
                        }
                        if (band.spaceInterface()) {
                            hex.setColor(BoardClientState.AltitudeBand.INTERFACE_TINT);
                            hex.fill(underInterface);
                            hex.setColor(BoardClientState.AltitudeBand.INTERFACE_LINE);
                            hex.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1,
                                  new float[] { 3, 5 }, 0));
                            hex.draw(new Line2D.Float(0, -H / 2, 0, H / 2));
                        }
                        if (band.ground()) {
                            hex.setColor(BoardClientState.AltitudeBand.GROUND);
                            hex.fill(groundStrip);
                            hex.setColor(BoardClientState.AltitudeBand.GROUND_EDGE);
                            hex.setStroke(new BasicStroke(1));
                            hex.draw(BoardTacticalGeometry.crossing(2, 4, 2).line());
                        }
                    });
                }
            }
        });
    }

    /** Predicted-position fills and one turn label per hex. */
    private static void heatMap(BoardTacticalGraphics graphics, BoardClientState view) {
        BoardTacticalGraphics.draw(graphics, BoardTactical.Playback.HOLD_DURING_PLAYBACK, layer -> {
            for (var entry : view.getBoard().getSpecialHexDisplayTable().entrySet()) {
                List<SpecialHexDisplay> heat = entry.getValue().stream().filter(view::isHeatMapMarker)
                      .filter(marker -> shown(marker, view)).toList();
                if (heat.isEmpty()) { continue; }
                hexes(layer, List.of(entry.getKey()), hex -> {
                    heat.stream().filter(view::isPredictedHeatMapMarker).forEach(marker -> {
                        hex.setColor(view.heatMapFill(marker));
                        hex.fill(BoardTacticalGeometry.insetHexagon(0));
                    });
                    String label = view.heatMapTurnLabel(heat);
                    if (label != null) {
                        hex.setColor(Color.WHITE);
                        hex.setFont(BoardClientState.heatMapLabelFont());
                        float width = hex.getFontMetrics(hex.getFont()).stringWidth(label);
                        hex.drawString(label, -width / 2, HEAT_LABEL_BASELINE);
                    }
                });
            }
        });
    }

    private static boolean shown(SpecialHexDisplay display, BoardClientState view) {
        Game game = view.getGame();
        return display.drawNow(game.getPhase(), game.getRoundCount(), view.getLocalPlayer(), GUIP);
    }

    private static void orbitalBombardment(BoardTacticalGraphics graphics, BoardClientState view) {
        Color color = new Color(BoardMarker.Kind.ORBITAL_INCOMING.rgb());
        for (Enumeration<OrbitalBombardment> attacks = view.getGame().getOrbitalBombardmentAttacks();
              attacks.hasMoreElements(); ) {
            OrbitalBombardment attack = attacks.nextElement();
            // Each blast hex shares the flat annotation plane; the centre symbol is a native marker.
            hexes(graphics, new Coords(attack.getX(), attack.getY()).allAtDistanceOrLess(attack.getRadius()).stream()
                  .filter(view.getBoard()::contains).toList(), hex -> ring(hex, color, 1));
        }
    }

    private static void demolitionHighlights(BoardTacticalGraphics graphics, BoardClientState view) {
        boolean hazard = GUIP.getDemolitionChargeHazardOutline();
        hexes(graphics, view.getDemolitionChargeHighlightHexes(), hex -> {
            if (hazard) {
                hazardOutline(hex, BoardClientState.DEMO_CHARGE_HAZARD_COLOR, 10);
            } else {
                outline(hex, UIUtil.uiGreen(), 2);
            }
        });
    }

    /** Every listed player's legal deployment hexes, one nested band per player. */
    private static void allDeployment(BoardTacticalGraphics graphics, BoardClientState view) {
        Board board = view.getBoard();
        List<Player> players = view.deploymentPlayers();
        int width = view.deploymentBorderWidth();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords coords = new Coords(x, y);
                List<Player> legal = players.stream().filter(player -> board.isLegalDeployment(coords, player))
                      .toList();
                if (legal.isEmpty()) { continue; }
                hexes(graphics, List.of(coords), hex -> {
                    for (int count = 0; count < legal.size(); count++) {
                        ring(hex, legal.get(count).getColour().getColour(), (width + 2) * count, width);
                    }
                });
            }
        }
    }

    /** The selected deployer's legal hexes, which BoardDeploymentGeometry turns into its zone. */
    private static void deployment(BoardTacticalGraphics graphics, BoardClientState view) {
        Board board = view.getBoard();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords coords = new Coords(x, y);
                List<Color> colors = view.deploymentColors(coords);
                if (!colors.isEmpty()) {
                    hexes(graphics, List.of(coords), hex -> colors.forEach(color -> ring(hex, color, 1)));
                }
            }
        }
    }

    /** A band between the two units' hex centres, draped over the terrain. */
    private static void c3(BoardTacticalGraphics graphics, TacticalMark.C3Link link) {
        Coords from = link.entity().getPosition(), to = link.master().getPosition();
        if (from == null || to == null) { return; }
        Shape band = new BasicStroke(2 * TacticalStyle.C3_LINE_WIDTH)
              .createStrokedShape(new Line2D.Float(point(from), point(to)));
        graphics.setColor(link.color());
        graphics.fill(band);
        graphics.setColor(Color.BLACK);
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(band);
    }

    /** The flown hexes' centres as one wide band, ending on the edge into the last hex. */
    private static void flyOver(BoardTacticalGraphics graphics, BoardClientState view, TacticalMark.FlyOver flyOver) {
        Entity entity = flyOver.entity();
        List<Coords> passed = entity.getPassedThrough();
        if (entity.getPosition() == null || passed.size() < 2) { return; }
        Path2D route = new Path2D.Float();
        route.moveTo(point(passed.getFirst()).getX(), point(passed.getFirst()).getY());
        for (Coords coords : passed.subList(1, passed.size() - 1)) {
            route.lineTo(point(coords).getX(), point(coords).getY());
        }
        Point2D last = point(passed.getLast()), before = point(passed.get(passed.size() - 2));
        route.lineTo((last.getX() + before.getX()) / 2, (last.getY() + before.getY()) / 2);
        Shape band = new BasicStroke(2 * TacticalStyle.FLY_OVER_LINE_WIDTH, BasicStroke.CAP_BUTT,
              BasicStroke.JOIN_BEVEL).createStrokedShape(route);
        graphics.setColor(flyOver.color());
        graphics.fill(band);
        graphics.setColor(entity.equals(view.getSelectedEntity()) ? Color.BLUE : Color.BLACK);
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(band);
    }

    /** From each artillery display's aimed hex to where its round came down: a dashed line with an open head. */
    private static void driftLines(BoardTacticalGraphics graphics, BoardClientState view) {
        if (!GUIP.getShowArtilleryDriftArrows()) { return; }
        float dash = Math.max(4, W / 14);
        graphics.setColor(BoardClientState.ARTILLERY_DRIFT_LINE_COLOR);
        graphics.setStroke(new BasicStroke(Math.max(1, W / 60), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1,
              new float[] { dash, dash }, 0));
        for (var entry : view.getBoard().getSpecialHexDisplayTable().entrySet()) {
            for (SpecialHexDisplay display : entry.getValue()) {
                Coords landing = display.getDriftHex();
                if (landing == null || !shown(display, view)) {
                    continue;
                }
                Point2D from = point(entry.getKey()), to = point(landing);
                graphics.draw(new Line2D.Float(from, to));
                double angle = Math.atan2(to.getY() - from.getY(), to.getX() - from.getX());
                double head = Math.max(6, W / 6), spread = Math.toRadians(28);
                for (double side : new double[] { -spread, spread }) {
                    graphics.draw(new Line2D.Double(to.getX(), to.getY(), to.getX() - head * Math.cos(angle + side),
                          to.getY() - head * Math.sin(angle + side)));
                }
            }
        }
    }

    /** An arrow from the unit's hex towards the hex its vectors carry it to, clear of both centres. */
    private static void movementVector(BoardTacticalGraphics graphics, TacticalMark.MovementVector vector) {
        if (vector.velocity() == 0) { return; }
        Point2D start = point(vector.start()), end = point(vector.end());
        double length = start.distance(end);
        if (length == 0) { return; }
        double cos = (end.getX() - start.getX()) / length, sin = (end.getY() - start.getY()) / length;
        double ax = start.getX() + VECTOR_CLEARANCE * cos, ay = start.getY() + VECTOR_CLEARANCE * sin;
        double tx = end.getX() - VECTOR_CLEARANCE * cos, ty = end.getY() - VECTOR_CLEARANCE * sin;
        double neckX = tx - VECTOR_HEAD_LENGTH * cos, neckY = ty - VECTOR_HEAD_LENGTH * sin;
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(ax + VECTOR_WIDTH * sin, ay - VECTOR_WIDTH * cos);
        arrow.lineTo(neckX + VECTOR_WIDTH * sin, neckY - VECTOR_WIDTH * cos);
        arrow.lineTo(neckX + VECTOR_HEAD_WIDTH * sin, neckY - VECTOR_HEAD_WIDTH * cos);
        arrow.lineTo(tx, ty);
        arrow.lineTo(neckX - VECTOR_HEAD_WIDTH * sin, neckY + VECTOR_HEAD_WIDTH * cos);
        arrow.lineTo(neckX - VECTOR_WIDTH * sin, neckY + VECTOR_WIDTH * cos);
        arrow.lineTo(ax - VECTOR_WIDTH * sin, ay + VECTOR_WIDTH * cos);
        arrow.closePath();
        graphics.setColor(vector.color());
        graphics.fill(arrow);
        graphics.setColor(Color.GRAY.darker());
        graphics.setStroke(new BasicStroke(1));
        graphics.draw(arrow);
    }

    /**
     * Each published mark in the client's order. Routes stay live and objective control zones are held through
     * playback; the rest hide while units move. Weapon ranges are GpuFireControl's; the markers are native markers.
     */
    private static void marks(BoardTacticalGraphics graphics, BoardClientState view,
          List<? extends TacticalMark> marks) {
        for (TacticalMark mark : marks) {
            BoardTactical.Playback playback =
                  mark instanceof TacticalMark.RouteDot || mark instanceof TacticalMark.LosEnd ? BoardTactical.Playback.LIVE
                  : mark instanceof TacticalMark.ControlZone ? BoardTactical.Playback.HOLD_DURING_PLAYBACK
                  : BoardTactical.Playback.HIDE_DURING_MOVEMENT;
            BoardTacticalGraphics.draw(graphics, playback, local -> mark(local, view, mark));
        }
    }

    private static void mark(BoardTacticalGraphics graphics, BoardClientState view, TacticalMark mark) {
        switch (mark) {
            case TacticalMark.SensorRange range -> sensorRange(graphics, view.getBoard(), range, RANGE_SHOWS_MAP_BORDER);
            case TacticalMark.ControlZone zone -> zone(graphics, zone.coords(), zone.borders(),
                  TacticalStyle.withAlpha(zone.color(), TacticalStyle.CONTROL_ZONE_OPACITY),
                  TacticalStyle.CONTROL_ZONE_BAND_WIDTH, null);
            case TacticalMark.Envelope envelope -> envelope(graphics, envelope);
            case TacticalMark.FacingModifier modifier -> modifier(graphics, modifier);
            case TacticalMark.RouteDot dot -> hexes(graphics, List.of(dot.coords()), hex -> {
                double diameter = dot.diameter();
                Shape shape = new Ellipse2D.Double(-diameter / 2, -diameter / 2, diameter, diameter);
                hex.setColor(dot.color());
                hex.fill(shape);
                hex.setColor(TacticalStyle.ROUTE_OUTLINE_COLOR);
                hex.setStroke(TacticalStyle.ROUTE_OUTLINE_STROKE);
                hex.draw(shape);
            });
            case TacticalMark.HexText text -> hexes(graphics, List.of(text.coords()), hex -> glyphs(hex,
                  box -> TacticalGlyphs.paintHexText(box, text.text(), text.color(), HexTileset.HEX_W,
                        HexTileset.HEX_H, 1)));
            case TacticalMark.CraneTarget target -> hexes(graphics, List.of(target.coords()), hex -> {
                hex.setColor(TacticalStyle.withAlpha(target.color(), TacticalStyle.CRANE_TARGET_FILL_OPACITY));
                hex.fill(BoardTacticalGeometry.insetHexagon(0));
                outline(hex, TacticalStyle.withAlpha(target.color(), TacticalStyle.CRANE_TARGET_OUTLINE_OPACITY),
                      TacticalStyle.CRANE_TARGET_OUTLINE_WIDTH);
            });
            case TacticalMark.Scan scan -> TacticalGlyphs.paintScan(graphics, point(scan.scanner()),
                  point(scan.target()), scan.color(), 1);
            case TacticalMark.BridgeBuild bridge when bridge.hazardOutline() -> hexes(graphics, List.of(bridge.coords()),
                  hex -> hazardOutline(hex, GUIP.getCautionColor(), TacticalStyle.BRIDGE_HAZARD_DASH));
            case TacticalMark.CraneOperation crane -> hexes(graphics, List.of(crane.coords()), hex -> {
                String countdown = Integer.toString(crane.turnsLeft());
                hex.setColor(Color.WHITE);
                hex.setFont(COUNTDOWN_FONT);
                var metrics = hex.getFontMetrics(COUNTDOWN_FONT);
                hex.drawString(countdown, -metrics.stringWidth(countdown) / 2f, metrics.getAscent() / 2f - 2);
            });
            case TacticalMark.FlyOver flyOver -> flyOver(graphics, view, flyOver);
            case TacticalMark.VtolAttack attack -> hexes(graphics, attack.targets(), hex -> ring(hex, attack.color(), 3));
            case TacticalMark.C3Link link -> c3(graphics, link);
            case TacticalMark.MovementVector vector -> movementVector(graphics, vector);
            case TacticalMark.Step step -> step(graphics, view, step);
            case TacticalMark.FlightPath indicator -> flightPath(graphics, view, indicator);
            case TacticalMark.LosEnd end -> hexes(graphics, List.of(end.coords()), hex -> outline(hex, Color.RED, 1));
            default -> { }
        }
    }

    /** Where the unit can move: a translucent band inside each outside side, with a dashed line along it. */
    static void envelope(BoardTacticalGraphics graphics, TacticalMark.Envelope envelope) {
        Color color = envelope.color();
        Color band = TacticalStyle.withAlpha(color, TacticalStyle.ENVELOPE_BAND_OPACITY);
        hexes(graphics, List.of(envelope.coords()), hex -> {
            hex.setStroke(TacticalStyle.ENVELOPE_LINE_STROKE);
            eachSide(envelope.borders(), TacticalStyle.ENVELOPE_BAND_WIDTH,
                  TacticalStyle.ENVELOPE_LINE_THICKNESS / 2, run -> {
                hex.setColor(band);
                hex.fill(run.band());
                hex.setColor(color);
                hex.draw(run.line());
            });
        });
    }

    /** The defensive modifier a final facing earns: a band inside that side with the modifier on it. */
    private static void modifier(BoardTacticalGraphics graphics, TacticalMark.FacingModifier modifier) {
        int facing = modifier.facing().getIntValue();
        hexes(graphics, List.of(modifier.coords()), hex -> {
            hex.setColor(modifier.color());
            hex.fill(BoardTacticalGeometry.sideBand(facing, true, true, TacticalStyle.MODIFIER_INSET,
                  TacticalStyle.MODIFIER_BAND_WIDTH));
            double distance = H / 2 - TacticalStyle.MODIFIER_BAND_WIDTH / 2, angle = Math.toRadians(facing * 60);
            hex.setFont(hex.getFont().deriveFont(TacticalStyle.MODIFIER_FONT_SIZE));
            new StringDrawer(modifier.modifier()).center().color(TacticalStyle.MODIFIER_FONT_COLOR)
                  .at((int) Math.round(distance * Math.sin(angle)), (int) Math.round(-distance * Math.cos(angle)))
                  .asText().draw(hex);
        });
    }

    /**
     * A sensor range's flat band, or a visual range's half-level curtain, along its outside sides; the map edge itself
     * is a range boundary only with {@code includeMapBorder}.
     */
    static void sensorRange(BoardTacticalGraphics graphics, Board board, TacticalMark.SensorRange range,
          boolean includeMapBorder) {
        Coords coords = range.coords();
        int borders = range.borders();
        for (int direction = 0; direction < 6 && !includeMapBorder; direction++) {
            if (!board.contains(coords.translated(direction))) { borders &= ~(1 << direction); }
        }
        int bracket = range.bracket();
        Color color = TacticalStyle.sensorColor(bracket);
        BoardRangeBorder wall = bracket == TacticalStyle.VISUAL || bracket == TacticalStyle.VISUAL_DARK
              ? new BoardRangeBorder(VISUAL_RANGE_HEIGHT, color.getRGB(), VISUAL_RANGE_OPACITY,
                    new BoardTactical.Outline(TacticalStyle.ZONE_LINE_COLOR.getRGB(), TacticalStyle.ZONE_LINE_STROKE))
              : null;
        zone(graphics, coords, borders, TacticalStyle.withAlpha(color, TacticalStyle.RANGE_BAND_OPACITY),
              TacticalStyle.RANGE_BAND_WIDTH, wall);
    }

    /**
     * A zone's boundary in the shared border layout ({@link TacticalStyle#borderLayout}): a run across the hex where
     * two to five adjacent sides are outside, else a band inside each outside side; each with a dashed line along it,
     * or that line as {@code wall}'s curtain.
     */
    static void zone(BoardTacticalGraphics graphics, Coords coords, int borders, Color fill, double width,
          BoardRangeBorder wall) {
        hexes(graphics, List.of(coords), hex -> {
            hex.setStroke(TacticalStyle.ZONE_LINE_STROKE);
            Consumer<BoardTacticalGeometry.Run> boundary = run -> {
                if (wall != null) {
                    hex.wall(run.line(), run.band(), coords, wall);
                } else {
                    hex.setColor(fill);
                    hex.fill(run.band());
                    hex.setColor(TacticalStyle.ZONE_LINE_COLOR);
                    hex.draw(run.line());
                }
            };
            int turn = TacticalStyle.borderLayoutDirection(borders);
            IntFunction<BoardTacticalGeometry.Run> lone = direction -> side(direction, false, false, width, 0);
            switch (TacticalStyle.borderLayout(borders)) {
                case 1 -> boundary.accept(BoardTacticalGeometry.crossing(2, turn, width));
                case 2 -> boundary.accept(BoardTacticalGeometry.crossing(3, turn, width));
                case 3 -> boundary.accept(BoardTacticalGeometry.crossing(4, turn, width));
                case 4 -> {
                    boundary.accept(BoardTacticalGeometry.crossing(2, turn, width));
                    boundary.accept(BoardTacticalGeometry.crossing(2, turn + 3, width));
                }
                case 5 -> {
                    boundary.accept(BoardTacticalGeometry.crossing(3, turn, width));
                    boundary.accept(lone.apply(turn + 4));
                }
                case 6 -> {
                    boundary.accept(BoardTacticalGeometry.crossing(2, turn, width));
                    boundary.accept(lone.apply(turn + 3));
                }
                case 7 -> {
                    boundary.accept(BoardTacticalGeometry.crossing(2, turn, width));
                    boundary.accept(lone.apply(turn + 4));
                }
                case 8 -> boundary.accept(BoardTacticalGeometry.crossing(5, turn, width));
                default -> eachSide(borders, width, TacticalStyle.ZONE_LINE_THICKNESS / 2, boundary);
            }
        });
    }

    /** Each outside side's band and line, mitred where the neighbouring side is outside too. */
    private static void eachSide(int borders, double width, double inset,
          Consumer<BoardTacticalGeometry.Run> boundary) {
        for (int direction = 0; direction < 6; direction++) {
            if ((borders & (1 << direction)) == 0) { continue; }
            boolean left = (borders & (1 << ((direction + 5) % 6))) != 0;
            boolean right = (borders & (1 << ((direction + 1) % 6))) != 0;
            boundary.accept(side(direction, left, right, width, inset));
        }
    }

    /** The band along one side, and its line {@code inset} in from the outline. */
    private static BoardTacticalGeometry.Run side(int direction, boolean left, boolean right, double width,
          double inset) {
        direction = Math.floorMod(direction, 6);
        Path2D.Double band = new Path2D.Double(BoardTacticalGeometry.sideBand(direction, left, right, 0, width));
        Path2D.Double line = new Path2D.Double(BoardTacticalGeometry.sideLine(direction, left, right, inset));
        return new BoardTacticalGeometry.Run(line, band);
    }

    /** A path step's arrows and labels, its design box on the hex, and a bombing or strafing step's border. */
    private static void step(BoardTacticalGraphics graphics, BoardClientState view, TacticalMark.Step step) {
        hexes(graphics, List.of(step.step().getPosition()), hex -> {
            glyphs(hex, box -> TacticalGlyphs.paintStep(box, view, step.step(), step.last()));
            if (TacticalGlyphs.hasBorder(step.step())) {
                ring(hex, TacticalGlyphs.stepColor(step.step(), step.last()), 3);
            }
        });
    }

    private static void flightPath(BoardTacticalGraphics graphics, BoardClientState view,
          TacticalMark.FlightPath indicator) {
        hexes(graphics, List.of(indicator.steps().get(indicator.index()).getPosition()), hex -> glyphs(hex, box -> {
            box.setFont(FontHandler.symbolFont());
            TacticalGlyphs.paintFlightPath(box, view, indicator, true);
        }));
    }

    /** A shared glyph painter's {@link HexTileset#HEX_W} x {@link HexTileset#HEX_H} design box, centred on the hex. */
    private static void glyphs(BoardTacticalGraphics hex, Consumer<BoardTacticalGraphics> painter) {
        BoardTacticalGraphics box = (BoardTacticalGraphics) hex.create();
        try {
            box.translate(-HexTileset.HEX_W / 2.0, -HexTileset.HEX_H / 2.0);
            painter.accept(box);
        } finally {
            box.dispose();
        }
    }

    private static Point2D point(Coords coords) {
        BoardTactical.Point centre = BoardTacticalGeometry.centre(coords);
        return new Point2D.Float(centre.x(), centre.y());
    }
}
