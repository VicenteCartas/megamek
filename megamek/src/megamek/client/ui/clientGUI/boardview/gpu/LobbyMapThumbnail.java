/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;

/**
 * The battlefield column's map: the lobby's board as the in-game minimap draws it, each hex in its terrain's colour
 * ({@link GpuMinimap#colour}), with each player's deployment zone tinted and outlined, the accent for the local
 * player's side and the danger colour for the others, the local player's own zone edged in the text colour. It is
 * baked off the GPU thread into an image the screen uploads once per map or zone change.
 */
final class LobbyMapThumbnail {
    /** The image's longer side in pixels: sharp at the column's widest on a screen scaled twice. */
    static final int LONG_SIDE = 720;
    /** A hex's terrain fills this much of it, so the gaps between hexes read as the grid, as on the minimap. */
    private static final double HEX_FILL = .9;
    /** How strongly a deployment zone tints the terrain under it. */
    private static final int ZONE_ALPHA = 140;
    /** A zone's outline, as a share of a hex's width: about two pixels where the column shows the map. */
    private static final double ZONE_EDGE = .22;
    /** The ratio of a flat-topped hex's height to its width, corner to corner. */
    private static final double HEX_HEIGHT = Math.sqrt(3) / 2;

    private LobbyMapThumbnail() { }

    /** How a player's deployment zone is drawn: the tint over its hexes and the line around it. */
    record ZoneStyle(Color fill, Color edge) { }

    /**
     * The zone style of {@code player} as {@code local} sees it: the accent for their own side (themselves, or their
     * team when they have one), the danger colour for the others, and the local player's own zone edged in the text
     * colour.
     */
    static ZoneStyle style(Player player, @Nullable Player local) {
        boolean friend = local != null && (player.getId() == local.getId()
              || (local.getTeam() > Player.TEAM_NONE && player.getTeam() == local.getTeam()));
        Color colour = token(friend ? "accent.primary" : "status.danger");
        Color edge = local != null && player.getId() == local.getId() ? token("text.primary") : colour;
        return new ZoneStyle(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), ZONE_ALPHA), edge);
    }

    private static Color token(String role) {
        return new Color(DesignTokens.shipped().color(role, Mode.DARK), true);
    }

    /**
     * Blocks: {@code board} drawn {@code longSide} pixels along its longer side, transparent between the hexes, with
     * each of {@code players}' deployment zones over it as {@link #style} colours it for {@code local}; the local
     * player's zone is drawn last, on top. A zone that covers the whole board says nothing about where that player
     * starts, so it isn't drawn.
     */
    static BufferedImage bake(Board board, List<Player> players, @Nullable Player local, int longSide) {
        int columns = board.getWidth();
        int rows = board.getHeight();
        double boardWidth = columns * .75 + .25;
        double boardHeight = (rows + .5) * HEX_HEIGHT;
        double unit = longSide / Math.max(boardWidth, boardHeight);
        BufferedImage image = new BufferedImage(Math.max(1, (int) Math.round(boardWidth * unit)),
              Math.max(1, (int) Math.round(boardHeight * unit)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double radius = unit / 2;
        for (BoardScene.Tile tile : BoardTiles.captureAll(board)) {
            Coords coords = tile.coords();
            int rgba = GpuMinimap.colour(tile);
            g.setColor(new Color(rgba >>> 24, (rgba >> 16) & 0xFF, (rgba >> 8) & 0xFF));
            g.fill(hex(centreX(coords, unit), centreY(coords, unit), radius * HEX_FILL));
        }
        g.setStroke(new BasicStroke((float) Math.max(1.5, unit * ZONE_EDGE), BasicStroke.CAP_ROUND,
              BasicStroke.JOIN_ROUND));
        List<Player> ordered = players.stream()
              .sorted((a, b) -> Boolean.compare(isLocal(a, local), isLocal(b, local))).toList();
        for (Player player : ordered) {
            if (player.isObserver()) {
                continue;
            }
            boolean[][] zone = zone(board, player);
            if (zone == null) {
                continue;
            }
            ZoneStyle zoneStyle = style(player, local);
            g.setColor(zoneStyle.fill());
            for (int x = 0; x < columns; x++) {
                for (int y = 0; y < rows; y++) {
                    if (zone[x][y]) {
                        Coords coords = new Coords(x, y);
                        g.fill(hex(centreX(coords, unit), centreY(coords, unit), radius * HEX_FILL));
                    }
                }
            }
            g.setColor(zoneStyle.edge());
            for (int x = 0; x < columns; x++) {
                for (int y = 0; y < rows; y++) {
                    if (zone[x][y]) {
                        outline(g, zone, x, y, unit);
                    }
                }
            }
        }
        g.dispose();
        return image;
    }

    /**
     * One zone alone, {@code zone}'s hexes by column then row, tinted and outlined in {@code style} over a transparent
     * image the size {@link #bake} makes of a board of the same size, to lie over its picture.
     */
    static BufferedImage bakeZone(boolean[][] zone, ZoneStyle style, int longSide) {
        int columns = zone.length;
        int rows = columns == 0 ? 0 : zone[0].length;
        double boardWidth = columns * .75 + .25;
        double boardHeight = (rows + .5) * HEX_HEIGHT;
        double unit = longSide / Math.max(boardWidth, boardHeight);
        BufferedImage image = new BufferedImage(Math.max(1, (int) Math.round(boardWidth * unit)),
              Math.max(1, (int) Math.round(boardHeight * unit)), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double radius = unit / 2;
        g.setColor(style.fill());
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                if (zone[x][y]) {
                    Coords coords = new Coords(x, y);
                    g.fill(hex(centreX(coords, unit), centreY(coords, unit), radius * HEX_FILL));
                }
            }
        }
        g.setStroke(new BasicStroke((float) Math.max(1.5, unit * ZONE_EDGE), BasicStroke.CAP_ROUND,
              BasicStroke.JOIN_ROUND));
        g.setColor(style.edge());
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                if (zone[x][y]) {
                    outline(g, zone, x, y, unit);
                }
            }
        }
        g.dispose();
        return image;
    }

    /** The style of a zone being chosen: the accent tint, edged in the text colour. */
    static ZoneStyle chosenStyle() {
        Color colour = token("accent.primary");
        return new ZoneStyle(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), ZONE_ALPHA),
              token("text.primary"));
    }

    private static boolean isLocal(Player player, @Nullable Player local) {
        return local != null && player.getId() == local.getId();
    }

    /** Where {@code player} may deploy on {@code board}, or null where that is nowhere or everywhere. */
    static @Nullable boolean[][] zone(Board board, Player player) {
        int columns = board.getWidth();
        int rows = board.getHeight();
        boolean[][] zone = new boolean[columns][rows];
        int count = 0;
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                if (board.isLegalDeployment(new Coords(x, y), player)) {
                    zone[x][y] = true;
                    count++;
                }
            }
        }
        return count == 0 || count == columns * rows ? null : zone;
    }

    /** The edges of the hex at ({@code x}, {@code y}) that border hexes outside {@code zone} or the board's edge. */
    private static void outline(Graphics2D g, boolean[][] zone, int x, int y, double unit) {
        double centreX = centreX(new Coords(x, y), unit);
        double centreY = centreY(new Coords(x, y), unit);
        double radius = unit / 2;
        for (int direction = 0; direction < 6; direction++) {
            int nx = Coords.xInDir(x, y, direction);
            int ny = Coords.yInDir(x, y, direction);
            boolean inside = nx >= 0 && ny >= 0 && nx < zone.length && ny < zone[nx].length && zone[nx][ny];
            if (!inside) {
                // Direction 0 is north: its edge runs between the corners at 240 and 300 degrees, image y down
                double from = Math.toRadians(240 + 60 * direction);
                double to = Math.toRadians(300 + 60 * direction);
                g.draw(new Line2D.Double(centreX + radius * Math.cos(from), centreY + radius * Math.sin(from),
                      centreX + radius * Math.cos(to), centreY + radius * Math.sin(to)));
            }
        }
    }

    private static double centreX(Coords coords, double unit) {
        return (.5 + .75 * coords.getX()) * unit;
    }

    /** Odd columns sit half a hex lower, as in {@link Coords#yInDir}. */
    private static double centreY(Coords coords, double unit) {
        return ((coords.getY() + .5 + ((coords.getX() & 1) == 0 ? 0 : .5)) * HEX_HEIGHT) * unit;
    }

    private static Path2D hex(double centreX, double centreY, double radius) {
        Path2D path = new Path2D.Double();
        for (int corner = 0; corner < 6; corner++) {
            double angle = Math.PI / 3 * corner;
            double x = centreX + radius * Math.cos(angle);
            double y = centreY + radius * Math.sin(angle);
            if (corner == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        return path;
    }
}
