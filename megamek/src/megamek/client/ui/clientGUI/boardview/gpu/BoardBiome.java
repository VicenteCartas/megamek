/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.board.Coords;

/** Bounded visual fields in world metres, shared by vegetation placement and the ground shader. */
final class BoardBiome {
    static final float EDGE_METRES = 2.2f;
    static final float WET_EDGE_METRES = 14;
    static final float ROW_METRES = 1.15f;

    private BoardBiome() { }

    static BoardScene.Biome kind(BoardScene.Tile tile) {
        return tile != null && tile.detailedGround() && !tile.frozen() && !tile.liquid().present()
              && !tile.slab()
              ? tile.biome() : BoardScene.Biome.NONE;
    }

    /** The exposed bank belongs to the wetland visually even when its mesh belongs to a neighbouring water hex. */
    static BoardScene.Biome plantKind(BoardScene scene, BoardScene.Tile tile) {
        var own = kind(tile);
        if (own != BoardScene.Biome.NONE) { return own; }
        if (tile == null || !tile.detailedGround() || tile.frozen() || tile.liquid().volcanic()
              || tile.surface() == BoardScene.Surface.CONCRETE
              || tile.slab()) {
            return BoardScene.Biome.NONE;
        }
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.tile(tile.coords().translated(direction));
            if (kind(next) == BoardScene.Biome.MARSH && next.elevation() == tile.elevation()) {
                return BoardScene.Biome.MARSH;
            }
        }
        return BoardScene.Biome.NONE;
    }

    /** Same integer hash/value noise as biomeNoise in terrain-biome.glsl; no tile-local seed or phase. */
    static float wetness(float x, float y) {
        float px = x + 1.3f * (BoardRelief.noise(x / 4 + 31, y / 4) - .5f);
        float py = y + 1.3f * (BoardRelief.noise(x / 4, y / 4 - 23) - .5f);
        return .55f * BoardRelief.noise(px / 5.7f, py / 5.7f)
              + .30f * BoardRelief.noise(px / 2 + 19, py / 2 - 7) + .15f * BoardRelief.noise(px / .65f, py / .65f);
    }

    /** Whether tundra over this ground is earth's: its carpets, its sward and its tussocks. Fungal mats and mineral crust grow none. */
    static boolean earthTundra(BoardScene.Surface surface) {
        return switch (surface) {
            case FUNGUS, MARS, LUNAR, VOLCANO, CONCRETE -> false;
            default -> true;
        };
    }

    /**
     * Earth tundra's grass sward at a point in metres, 0 to 1: it covers most of the carpet and leaves openings of
     * lichen, moss and gravel some metres across. Same field as tundraSward in terrain-tundra.glsl, where the ground
     * takes the sward's colour under the roots this plants.
     */
    static float sward(float x, float y) {
        float px = x + 6 * (BoardRelief.noise(x / 19 + 7, y / 19 - 3) - .5f);
        float py = y + 6 * (BoardRelief.noise(x / 19 - 13, y / 19 + 5) - .5f);
        float field = .65f * BoardRelief.noise(px / 13 + 3, py / 13 + 11)
              + .35f * BoardRelief.noise(px / 4.1f - 17, py / 4.1f + 23);
        return BoardRelief.smooth((field - .18f) / .32f);
    }

    /** How fully ground at height {@code z} belongs to a biome floor at {@code floor}; banks and steps fade it out. */
    static float sameLevel(float z, float floor) {
        return 1 - BoardRelief.smooth((Math.abs(z - floor) / BoardRelief.metres(1) - .15f) / 1.1f);
    }

    /**
     * The coverage stencil's distance from a hex outline (biomeHexDistance in terrain-hexes.glsl), for absolute offsets
     * {@code px, py} from the hex centre; negative inside.
     */
    static float hexDistance(float px, float py) {
        float a = BoardGeometry.height() / 2, b = BoardGeometry.width() / 4;
        return Math.max(py - a, (a * px + b * py - BoardGeometry.width() * BoardGeometry.height() / 4)
              / (float) Math.sqrt(a * a + b * b));
    }

    /** All candidates, including ordinary land, compete. Adjacent matching tiles have no internal fade. */
    static float coverage(BoardScene scene, BoardScene.Biome kind, float x, float y, float z) {
        int column = (int) Math.floor(x / (BoardGeometry.width() * .75f));
        return new Coverage(scene, new Coords(column, (int) Math.floor(-y / BoardGeometry.height() - (column & 1) * .5f)))
              .of(kind, x, y, z);
    }

    /**
     * {@link #coverage} for many points around one hex: a point reads the nine hexes around it, and a planter asks
     * thousands of points in its hex, so each hex of the neighbourhood is looked up once. Points farther out are
     * answered alike, without being kept.
     */
    static final class Coverage {
        /** A hex's centre, its biome ({@link #kind}) and, with one, its ground. */
        private record Cell(float x, float y, BoardScene.Biome biome, float floor) { }

        private static final int REACH = 3, SPAN = 2 * REACH + 1;
        private final BoardScene scene;
        private final Coords around;
        private final Cell[] cells = new Cell[SPAN * SPAN];

        Coverage(BoardScene scene, Coords around) {
            this.scene = scene;
            this.around = around;
        }

        private Cell cell(int column, int row) {
            int i = column - around.getX() + REACH, j = row - around.getY() + REACH;
            boolean near = i >= 0 && j >= 0 && i < SPAN && j < SPAN;
            Cell cell = near ? cells[i * SPAN + j] : null;
            if (cell == null) {
                var coords = new Coords(column, row);
                var tile = scene.tile(coords);
                var biome = kind(tile);
                cell = new Cell(BoardGeometry.centerX(coords), BoardGeometry.centerY(coords), biome,
                      biome == BoardScene.Biome.NONE ? 0 : BoardGeometry.groundZ(tile));
                if (near) { cells[i * SPAN + j] = cell; }
            }
            return cell;
        }

        float of(BoardScene.Biome kind, float x, float y, float z) {
            boolean marsh = kind == BoardScene.Biome.MARSH;
            boolean tundra = kind == BoardScene.Biome.TUNDRA;
            float width = BoardRelief.metres(marsh || tundra ? WET_EDGE_METRES : EDGE_METRES);
            float fieldWidth = BoardRelief.metres(EDGE_METRES);
            float metre = BoardRelief.metres(1);
            float edgeNoise = tundra ? BoardRelief.noise(x / metre / 3.2f + 41, y / metre / 3.2f + 41) : 0;
            float down = 1.4f + (Math.max(3.6f, BoardGeometry.level() / metre * 1.4f) - 1.4f)
                  * BoardRelief.smooth((edgeNoise - .15f) / .7f);
            int col = (int) Math.floor(x / (BoardGeometry.width() * .75f));
            float sum = 0, covered = 0, wet = 0, field = 0, fieldSum = 0;
            float tundraTiles = 0, tundraAbove = 0, tundraBelow = 0;
            for (int dx = -1; dx <= 1; dx++) {
                int cx = col + dx;
                int row = (int) Math.floor(-y / BoardGeometry.height() - (cx & 1) * .5f);
                for (int dy = -1; dy <= 1; dy++) {
                    Cell cell = cell(cx, row + dy);
                    float distance = hexDistance(Math.abs(x - cell.x()), Math.abs(y - cell.y()));
                    float w = 1 - BoardRelief.smooth((distance + width) / (2 * width));
                    float fw = marsh ? 1 - BoardRelief.smooth((distance + fieldWidth) / (2 * fieldWidth)) : 0;
                    sum += w;
                    fieldSum += fw;
                    var biome = cell.biome();
                    if (biome != BoardScene.Biome.NONE) {
                        float floor = cell.floor();
                        float sameLevel = sameLevel(z, floor);
                        if (biome == kind) {
                            if (tundra) {
                                float reach = z < floor ? down : 2.8f + edgeNoise * .8f;
                                covered += w * (1 - BoardRelief.smooth((Math.abs(z - floor) / metre - .15f) / (reach - .15f)));
                                tundraTiles += w;
                                if (floor >= z + .15f * metre) { tundraAbove += w; }
                                if (z >= floor + .15f * metre) { tundraBelow += w; }
                            } else { covered += w * sameLevel; }
                        }
                        if (biome == BoardScene.Biome.FIELD) { field += fw * sameLevel; }
                        else if (biome != BoardScene.Biome.TUNDRA) { wet += w * sameLevel; }
                    }
                }
            }
            float result = sum > 0 ? covered / sum : 0;
            if (tundra && sum > 0) {
                float connected = BoardRelief.smooth(Math.min(tundraAbove, tundraBelow) / sum / .18f);
                result = Math.max(result, tundraTiles / sum * connected);
            }
            // Match the ground shader's wet-bank reach and its cultivated-row priority in the same bounded stencil.
            if (marsh && wet > 0) {
                result *= Math.min(3, sum / wet) * (1 - field / Math.max(fieldSum, .0001f));
            }
            return result;
        }
    }
}
