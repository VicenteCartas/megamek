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

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.IGame;
import megamek.common.scenario.Scenario;
import megamek.common.scenario.ScenarioV1;

/**
 * The briefing's map: the scenario's board as the in-game minimap draws it, each hex in its terrain's colour
 * ({@link GpuMinimap#colour}), with each side's deployment zone tinted and outlined in that side's colour. It is baked
 * off the GPU thread into an image the briefing uploads into a frame of fixed size ({@link StartMapFrame}); a render
 * of the 3D board could replace the image later without changing that frame.
 */
final class StartMapThumbnail {
    /** The image's longer side in pixels: sharp at the column's widest on a screen scaled twice. */
    static final int LONG_SIDE = 720;
    /** A hex's terrain fills this much of it, so the gaps between hexes read as the grid, as on the minimap. */
    private static final double HEX_FILL = .9;
    /** How strongly a deployment zone tints the terrain under it. */
    private static final int ZONE_TINT = 150;
    /** A zone's outline, as a share of a hex's width: about two pixels where the column shows the map. */
    private static final double ZONE_EDGE = .22;
    /** How far a zone's outline is lightened, so dark side colours still read on the dark frame. */
    private static final float EDGE_LIGHTEN = .3f;
    /** The ratio of a flat-topped hex's height to its width, corner to corner. */
    private static final double HEX_HEIGHT = Math.sqrt(3) / 2;
    private static final String MAPS = "Maps";
    private static final String SEPARATOR = "  \u00b7  ";

    private StartMapThumbnail() {
    }

    /**
     * Blocks: {@code board} drawn {@code longSide} pixels along its longer side, transparent between the hexes, with
     * each of {@code players}' deployment zones over it. A zone that covers the whole board says nothing about where
     * that side starts, so it isn't drawn.
     */
    static BufferedImage bake(Board board, List<Player> players, int longSide) {
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
        LobbyMapThumbnail.paintTerrain(g, board, unit);
        g.setStroke(new BasicStroke((float) Math.max(1.5, unit * ZONE_EDGE), BasicStroke.CAP_ROUND,
              BasicStroke.JOIN_ROUND));
        for (Player player : players) {
            boolean[][] zone = zone(board, player);
            if (zone == null) {
                continue;
            }
            Color colour = player.getColour().getColour();
            g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), ZONE_TINT));
            for (int x = 0; x < columns; x++) {
                for (int y = 0; y < rows; y++) {
                    if (zone[x][y]) {
                        Coords coords = new Coords(x, y);
                        g.fill(hex(centreX(coords, unit), centreY(coords, unit), radius * HEX_FILL));
                    }
                }
            }
            g.setColor(lighter(colour));
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

    /** Where {@code player} may deploy on {@code board}, or null where that is nowhere or everywhere. */
    static boolean[][] zone(Board board, Player player) {
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

    private static Color lighter(Color colour) {
        return new Color(lighter(colour.getRed()), lighter(colour.getGreen()), lighter(colour.getBlue()));
    }

    private static int lighter(int channel) {
        return Math.round(channel + (255 - channel) * EDGE_LIGHTEN);
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

    /** The line under the map: its size in hexes, and whether it was rolled at random or is one of several maps. */
    static String caption(IGame game, Scenario scenario) {
        Board board = game.getBoard();
        String caption = Messages.getString("StartScreen.mapSize", board.getWidth(), board.getHeight());
        if (randomMap(scenario)) {
            caption += SEPARATOR + Messages.getString("StartScreen.mapRandom");
        }
        int boards = game.getBoards().size();
        if (boards > 1) {
            caption += SEPARATOR + Messages.getString("StartScreen.mapFirstOf", boards);
        }
        return caption;
    }

    /**
     * Whether any of the scenario's map sheets is chosen at random, so the map differs each time the briefing makes
     * its game: a sheet named RANDOM, or one the Maps line leaves out.
     */
    static boolean randomMap(Scenario scenario) {
        if (!(scenario instanceof ScenarioV1 v1)) {
            return false;
        }
        int sheets = Math.max(1, number(v1.getString("BoardWidth"))) * Math.max(1, number(v1.getString("BoardHeight")));
        String maps = v1.getString(MAPS);
        if (maps == null) {
            return true;
        }
        String[] names = maps.split(",", -1);
        if (names.length < sheets) {
            return true;
        }
        for (int i = 0; i < sheets; i++) {
            String name = names[i].trim();
            if (name.startsWith(Board.BOARD_REQUEST_ROTATION)) {
                name = name.substring(Board.BOARD_REQUEST_ROTATION.length());
            }
            if (Scenario.MAP_RANDOM.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int number(String value) {
        try {
            return value == null ? 1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
