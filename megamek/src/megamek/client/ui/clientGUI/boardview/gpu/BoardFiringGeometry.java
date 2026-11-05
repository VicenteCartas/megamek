/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Presentation geometry only: attack legality and the range edges arrive from the Swing board. */
final class BoardFiringGeometry {
    /** Contour height above the highest adjoining surface, in board elevation levels. */
    static final float RANGE_HEIGHT = 1;
    /** Clearance above the surface, in tactical units. */
    static final float RANGE_CLEARANCE = 0.6f;
    /** tan(30 degrees), for joins between inset edges of the hex contour. */
    private static final float MITER = 0.5774f;

    private BoardFiringGeometry() { }

    record RangeEdge(Vector3 bottomA, Vector3 bottomB, Vector3 topA, Vector3 topB) { }

    record RangeSide(BoardScene.RangeBorder border, int edge) { }

    private record RangeVertex(int x, int y, String label, int rgb) { }

    /** Follow each directed boundary once, so texture coordinates continue across hexes and close at the seam. */
    static List<List<RangeSide>> rangeContours(BoardScene scene) {
        Map<RangeVertex, RangeSide> remaining = new LinkedHashMap<>();
        for (BoardScene.RangeBorder border : scene.rangeBorders()) {
            if (scene.tile(border.coords()) == null) {
                continue;
            }
            for (int edge = 0; edge < 6; edge++) {
                if ((border.edges() & (1 << BoardGeometry.edgeDirection(edge))) != 0) {
                    remaining.put(rangeVertex(border, edge), new RangeSide(border, edge));
                }
            }
        }
        List<List<RangeSide>> contours = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<RangeSide> contour = new ArrayList<>();
            RangeSide side = remaining.values().iterator().next();
            remaining.remove(rangeVertex(side.border(), side.edge()));
            do {
                contour.add(side);
                side = remaining.remove(rangeVertex(side.border(), side.edge() + 1));
            } while (side != null);
            contours.add(contour);
        }
        return contours;
    }

    private static RangeVertex rangeVertex(BoardScene.RangeBorder border, int corner) {
        Vector3 point = BoardGeometry.corner(border.coords(), 0, corner);
        // Integer lattice coordinates avoid floating-point mismatches at shared hex corners.
        return new RangeVertex(Math.round(point.x / (BoardGeometry.width() / 4)),
              Math.round(point.y / (BoardGeometry.height() / 2)), border.label(), border.rgb());
    }

    /** Inset adjoining walls meet at convex and reflex corners without sharing the terrain's edge plane. */
    static RangeEdge rangeWall(BoardScene scene, RangeSide side) {
        RangeEdge wall = rangeEdge(scene, side.border().coords(), side.edge());
        Vector3 along = new Vector3(wall.bottomB().x - wall.bottomA().x,
              wall.bottomB().y - wall.bottomA().y, 0).nor();
        Vector3 inward = new Vector3(Vector3.Z).crs(along);
        float offset = BoardGeometry.MARKER_INSET * BoardGeometry.width() / 2;
        float miter = offset * MITER;
        int mask = side.border().edges();
        boolean convexStart = (mask & (1 << BoardGeometry.edgeDirection(side.edge() - 1))) != 0;
        boolean convexEnd = (mask & (1 << BoardGeometry.edgeDirection(side.edge() + 1))) != 0;
        Vector3 start = new Vector3(inward).scl(offset).mulAdd(along, convexStart ? miter : -miter);
        Vector3 end = new Vector3(inward).scl(offset).mulAdd(along, convexEnd ? -miter : miter);
        return new RangeEdge(wall.bottomA().add(start), wall.bottomB().add(end),
              wall.topA().add(start), wall.topB().add(end));
    }

    static List<Vector3> trajectory(BoardScene scene, BoardScene.FiringLine line) {
        Vector3 start = BoardGeometry.center(line.source().coords(), line.source().elevation());
        Vector3 end = BoardGeometry.center(line.target().coords(), line.target().elevation());
        if (!line.indirect() || start.dst2(end) < 0.01f) {
            return List.of(start, end);
        }
        float lift = Math.max(3 * BoardGeometry.level(), start.dst(end) * 0.2f);
        for (Coords coords : Coords.intervening(line.source().coords(), line.target().coords())) {
            BoardScene.Tile tile = scene.tile(coords);
            if (tile == null || coords.equals(line.source().coords()) || coords.equals(line.target().coords())) {
                continue;
            }
            float[] interval = BoardGeometry.crossing(start, end, coords);
            if (interval == null) {
                continue;
            }
            float roof = tile.elevation();
            for (BoardScene.Feature feature : tile.features()) {
                roof = Math.max(roof, tile.elevation() + feature.elevation() + feature.height());
            }
            float clearance = (roof + 1) * BoardGeometry.level();
            // A concave parabola's lowest clearance over a flat hex is at entry or exit, not its centre.
            for (float t : interval) {
                if (t > 0 && t < 1) {
                    lift = Math.max(lift, (clearance - start.z - (end.z - start.z) * t) / (4 * t * (1 - t)));
                }
            }
        }
        // Bound tessellation error below the one-level clearance even beside a very tall, nearby ridge.
        int segments = Math.max(32, (int) Math.ceil(Math.sqrt(lift / (0.05f * BoardGeometry.level()))));
        segments = Math.max(segments, (int) Math.ceil(start.dst(end) / (BoardGeometry.height() / 4)));
        List<Vector3> result = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            float t = i / (float) segments;
            result.add(new Vector3(start).lerp(end, t).add(0, 0, 4 * lift * t * (1 - t)));
        }
        return result;
    }

    static RangeEdge rangeEdge(BoardScene scene, Coords coords, int edge) {
        return new RangeEdge(ridgeCorner(scene, coords, edge, false), ridgeCorner(scene, coords, edge + 1, false),
              ridgeCorner(scene, coords, edge, true), ridgeCorner(scene, coords, edge + 1, true));
    }

    private static Vector3 ridgeCorner(BoardScene scene, Coords coords, int corner, boolean top) {
        // Tile elevation is the surface level, including over water; waterDepth only lowers the lakebed.
        float level = scene.tile(coords).elevation();
        // All three hexes touching a vertex use the same ridge height, so adjoining walls never jump apart.
        for (int edge : new int[] { corner - 1, corner }) {
            BoardScene.Tile neighbor = scene.tile(coords.translated(BoardGeometry.edgeDirection(edge)));
            if (neighbor != null) {
                level = top ? Math.max(level, neighbor.elevation()) : Math.min(level, neighbor.elevation());
            }
        }
        return BoardGeometry.corner(coords, level + (top ? RANGE_HEIGHT : 0), corner)
              .add(0, 0, RANGE_CLEARANCE * BoardGeometry.hexScale());
    }
}
