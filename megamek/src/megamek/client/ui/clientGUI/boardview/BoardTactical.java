/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.stream.Stream;

import megamek.common.board.Coords;

/** Immutable drawing commands in tactical units (board units at hex scale 1, y down); game state stays on Swing. */
public record BoardTactical(List<Fill> fills, List<Label> labels, List<Wall> walls, List<Fill> flatWalls, Ruler ruler) {
    /** Range contour labels are a presentation choice shared by capture and rendering. */
    public static final boolean SCROLLING_RANGE_LABELS = false;
    public static final BoardTactical EMPTY = new BoardTactical(List.of(), List.of());

    /** Measurement tools stay live, unit overlays hide, and map-state markings retain their last displayed state. */
    public enum Playback { LIVE, HIDE_DURING_MOVEMENT, HOLD_DURING_PLAYBACK }

    public BoardTactical(List<Fill> fills, List<Label> labels) {
        this(fills, labels, List.of(), List.of());
    }

    public BoardTactical(List<Fill> fills, List<Label> labels, List<Wall> walls, List<Fill> flatWalls) {
        this(fills, labels, walls, flatWalls, null);
    }

    public BoardTactical withRuler(Ruler ruler) {
        return new BoardTactical(fills, labels, walls, flatWalls, ruler);
    }

    /** EDT-owned measurement snapshot. Heights are absolute display levels; null transitions mean no effect. */
    public record Ruler(Coords start, Coords end, int startHeight, int endHeight, Coords blockedAt,
          Coords modifierAt, int startArgb, int endArgb) { }

    public BoardTactical {
        fills = List.copyOf(fills);
        labels = List.copyOf(labels);
        walls = List.copyOf(walls);
        flatWalls = List.copyOf(flatWalls);
    }

    public BoardTactical duringPlayback(BoardTactical settled, boolean hideMovement) {
        // Keep live measurements above retained map markings.
        return new BoardTactical(Stream.concat(
              settled.fills.stream().filter(fill -> retained(fill.playback(), hideMovement)),
              fills.stream().filter(fill -> fill.playback() == Playback.LIVE)).toList(),
              Stream.concat(settled.labels.stream().filter(label -> retained(label.playback(), hideMovement)),
                    labels.stream().filter(label -> label.playback() == Playback.LIVE)).toList(),
              Stream.concat(settled.walls.stream().filter(wall -> retained(wall.playback(), hideMovement)),
                    walls.stream().filter(wall -> wall.playback() == Playback.LIVE)).toList(),
              Stream.concat(settled.flatWalls.stream().filter(fill -> retained(fill.playback(), hideMovement)),
                    flatWalls.stream().filter(fill -> fill.playback() == Playback.LIVE)).toList(), ruler);
    }

    private static boolean retained(Playback playback, boolean hideMovement) {
        return playback != Playback.LIVE && (!hideMovement || playback == Playback.HOLD_DURING_PLAYBACK);
    }

    public record Point(float x, float y) { }

    /** A straight upright segment anchored to its hex's surface; height is in board elevation levels. */
    public record Wall(Coords coords, Point a, Point b, BoardRangeBorder border, float outlineDistance, Playback playback,
          List<Coords> aNeighbors, List<Coords> bNeighbors) {
        public Wall {
            aNeighbors = List.copyOf(aNeighbors);
            bNeighbors = List.copyOf(bNeighbors);
        }

        public Wall(Coords coords, Point a, Point b, BoardRangeBorder border, float outlineDistance, Playback playback) {
            this(coords, a, b, border, outlineDistance, playback, List.of(), List.of());
        }

        public Wall(Coords coords, Point a, Point b, float height, int argb, Outline outline, float outlineDistance,
              Playback playback) {
            this(coords, a, b, new BoardRangeBorder(height, argb, outline), outlineDistance, playback);
        }

        public float height() { return border.height(); }
        public int argb() { return border.argb(); }
        public Outline outline() { return border.outline(); }
    }

    /** BasicStroke is immutable; retain the existing painter's line width and dash pattern. */
    public record Outline(int argb, BasicStroke stroke) { }

    public record Contour(List<Point> points) {
        public Contour {
            points = List.copyOf(points);
        }
    }

    /** Anchor is the hex centre in tactical units; the fill's contours remain the generic fallback. */
    public record HexBorder(Point anchor, double padding, double width, float scale, boolean zone) {
        public HexBorder(Point anchor, double padding, double width, float scale) {
            this(anchor, padding, width, scale, false);
        }
    }

    /** A plane anchor places any shape on its owner hex's flat annotation plane; null follows the terrain. */
    public record Fill(List<Contour> contours, int winding, int argb, Playback playback, HexBorder border,
          Point planeAnchor) {
        public Fill(List<Contour> contours, int winding, int argb) {
            this(contours, winding, argb, Playback.LIVE);
        }

        public Fill(List<Contour> contours, int winding, int argb, Playback playback) {
            this(contours, winding, argb, playback, null);
        }

        public Fill(List<Contour> contours, int winding, int argb, Playback playback, HexBorder border) {
            this(contours, winding, argb, playback, border, null);
        }

        public Fill {
            contours = List.copyOf(contours);
        }

        public Path2D shape() {
            Path2D result = new Path2D.Float(winding);
            for (Contour contour : contours) {
                if (contour.points().isEmpty()) {
                    continue;
                }
                Point first = contour.points().getFirst();
                result.moveTo(first.x(), first.y());
                for (int i = 1; i < contour.points().size(); i++) {
                    Point point = contour.points().get(i);
                    result.lineTo(point.x(), point.y());
                }
                result.closePath();
            }
            return result;
        }
    }

    /** Position relative to a shared board anchor, so related text/shadows stay together as the camera turns. */
    public record Text(String value, Font font, float x, float y, int argb) { }
    public record Label(Point anchor, Text text, Playback playback) {
        public Label(Point anchor, Text text) {
            this(anchor, text, Playback.LIVE);
        }
    }
}
