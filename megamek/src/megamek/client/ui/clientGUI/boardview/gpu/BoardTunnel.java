/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;

/** Cosmetic portals at authored road exits blocked by a tall wall. Never adds a game connection. */
record BoardTunnel(Coords road, int direction, Vector3 origin, Vector3 along, BoardRoad.Kind kind, boolean bridge, float widthScale) {
    static final String ASSET = "scenery/road/road-tunnel";
    static final String BRIDGE_ASSET = "scenery/road/road-tunnel-bridge";
    // Asset contract: mm-data tools/board-models/road-tunnel.md. +Y points into the cliff, Z is up.
    /** The opening's half width; its elliptical arch rises ARCH_RISE above the piers' SPRING. */
    static final float HALF_WIDTH = 17.5f, SPRING = 6.9f, ARCH_RISE = 10.925f;
    /** Authored GLBs have a road's 30px carriageway; only their lateral dimension follows a wider carried route. */
    static final float ASSET_ROAD_HALF = 15;
    /** The portal's shell, its half width and roof; with the wings, the span the face it is set into keeps flat. */
    static final float SHELL_HALF = 22.25f, ROOF = 22.1f, SPAN = 28;
    static final float DEPTH = 36;
    /** How far the opening stands in front of the hex's edge, in tile pixels. */
    static final float SETBACK = 3;
    static final int ARCH_SEGMENTS = 16;
    /** The wing walls flare from their roots at the face to their ends WING_LENGTH ahead, falling from WING_HIGH to WING_LOW. */
    static final float WING_ROOT = 21.4f, WING_END = 27.4f, WING_LENGTH = 6, WING_HIGH = 9.2f, WING_LOW = 4;
    /** Over how far the ground eases back to its own shape above the wings, and over how far their ends sink into it. */
    static final float WING_EASE = 6, WING_BURY = 3;

    static List<BoardTunnel> entrances(BoardScene scene, BoardScene.Tile tile) {
        List<BoardTunnel> result = new ArrayList<>();
        if (BoardRoad.rendered(tile)) {
            entrances(scene, tile, tile.roadExits(), BoardRoadGrade.ground(tile), tile.road(), false, result);
        }
        // A deck's road continues into the wall on its own sides.
        var feature = BoardBridge.feature(tile);
        if (feature != null && tile.deckRoad() != BoardRoad.Kind.NONE) {
            entrances(scene, tile, tile.deckRoadExits(), tile.elevation() + feature.elevation(), tile.deckRoad(), true, result);
        }
        return List.copyOf(result);
    }

    private static void entrances(BoardScene scene, BoardScene.Tile tile, int exits, float elevation,
          BoardRoad.Kind kind, boolean bridge, List<BoardTunnel> result) {
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) == 0) { continue; }
            var next = scene.tile(tile.coords().translated(d));
            if (next == null || next.liquid().present()
                  || (bridge && BoardBridge.connected(tile, next, d))
                  || (next.elevation() - elevation) * BoardGeometry.level() < 20 * BoardGeometry.hexScale()) { continue; }
            // Ground roads keep their existing two-hex grading. A suspended deck instead meets the cliff at deck height.
            if (!bridge && (next.elevation() - elevation <= 2 || BoardSurface.hasRoadApproach(tile, next, d))) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), elevation);
            Vector3 neighbor = BoardGeometry.center(next.coords(), elevation);
            Vector3 along = new Vector3(neighbor).sub(center).nor();
            Vector3 origin = center.lerp(neighbor, .5f).mulAdd(along, -SETBACK * BoardGeometry.hexScale());
            origin.z += GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
            int rails = bridge ? tile.features().stream().map(BoardScene.Feature::decoration)
                  .filter(route -> RailRoute.kind(route) == RailRoute.Kind.RAIL && RailRoute.onDeck(route))
                  .mapToInt(route -> route.connections()).reduce(0, (a, b) -> a | b) : tile.railExits();
            float half = (rails & 1 << d) != 0 ? BoardRoad.CORRIDOR_HALF : kind.halfWidth;
            result.add(new BoardTunnel(tile.coords(), d, origin, along, kind, bridge, half / ASSET_ROAD_HALF));
        }
    }

    String asset() { return bridge ? BRIDGE_ASSET : ASSET; }

    /**
     * The light in a tunnel at a depth in from its opening, in tile pixels: full at the hex's edge, falling off to black at
     * its end. The GLBs' floor and lining colours follow it.
     */
    static float light(float depth) {
        return (float) Math.pow(Math.clamp(1 - (depth - SETBACK) / (DEPTH - SETBACK), 0, 1), 2.2);
    }

    /** How far a world point lies in from the opening, in tile pixels. */
    float depth(float x, float y) { return ((x - origin.x) * along.x + (y - origin.y) * along.y) / BoardGeometry.hexScale(); }

    Matrix4 transform() {
        float turn = (float) Math.toDegrees(Math.atan2(-along.x, along.y));
        float scale = BoardGeometry.hexScale();
        return new Matrix4().setToTranslation(origin).rotate(Vector3.Z, turn).scale(scale * widthScale, scale, scale);
    }

    private Vector3 local(Vector3 p) {
        float scale = BoardGeometry.hexScale(), x = (p.x - origin.x) / scale, y = (p.y - origin.y) / scale;
        return new Vector3((x * along.y - y * along.x) / widthScale, x * along.x + y * along.y, (p.z - origin.z) / scale);
    }

    /** How far (x, y) lies outside the wing walls' span, in tile pixels; negative within it. */
    float beside(float x, float y) {
        Vector3 p = local(new Vector3(x, y, origin.z));
        return Math.max((Math.abs(p.x) - (bridge ? SHELL_HALF : SPAN)) * widthScale, Math.max(-8 - p.y, p.y - DEPTH - 2));
    }

    /**
     * Moves a point of the ground ahead of a ground portal sideways out of its wing walls' span, up to their tops: the
     * slopes beside the approach stand behind the walls, which retain them, instead of burying them.
     */
    void clearWings(Vector3 point) {
        if (bridge) { return; }
        Vector3 p = local(point);
        if (p.y >= 0 || p.y <= -WING_LENGTH) { return; }
        float ahead = -p.y / WING_LENGTH;
        float outer = WING_ROOT + (WING_END - WING_ROOT) * ahead, top = WING_HIGH + (WING_LOW - WING_HIGH) * ahead;
        float share = (1 - BoardRelief.smooth((p.z - top) / WING_EASE))
              * BoardRelief.smooth((p.y + WING_LENGTH) / WING_BURY);
        float move = (outer - Math.abs(p.x)) * widthScale * BoardGeometry.hexScale() * share;
        if (move <= 0) { return; }
        float side = p.x < 0 ? -1 : 1;
        point.x += along.y * side * move;
        point.y -= along.x * side * move;
    }

    /** Keep whole decorative rocks clear of the shell, wing walls and the road approaching the mouth. */
    boolean obstructs(Vector3 base, float radius, float height) {
        Vector3 p = local(base);
        float scale = BoardGeometry.hexScale(), r = radius / scale;
        if (p.z >= ROOF + 1 || p.z + height / scale <= -1) { return false; }
        boolean shell = Math.abs(p.x) < (bridge ? SHELL_HALF : SPAN) + r / widthScale
              && p.y > (bridge ? -.1f : -7) - r && p.y < DEPTH + r;
        boolean approach = Math.abs(p.x) < HALF_WIDTH + 1 + r / widthScale && p.y > -36 - r && p.y < r;
        return shell || approach;
    }

    /** Remove only the portal's arch prism from the existing cliff mesh; its surrounding slope stays native. */
    void cut(List<BoardSurface.Face> faces, BoardRelief relief) {
        List<float[]> planes = new ArrayList<>();
        planes.add(new float[] { 1, 0, 0, HALF_WIDTH });
        planes.add(new float[] { -1, 0, 0, HALF_WIDTH });
        planes.add(new float[] { 0, 1, 0, DEPTH });
        planes.add(new float[] { 0, -1, 0, .1f });
        planes.add(new float[] { 0, 0, -1, .1f });
        for (int i = 0; i < ARCH_SEGMENTS; i++) {
            double a = i * Math.PI / ARCH_SEGMENTS, b = (i + 1) * Math.PI / ARCH_SEGMENTS;
            float ax = HALF_WIDTH * (float) Math.cos(a), az = SPRING + ARCH_RISE * (float) Math.sin(a);
            float bx = HALF_WIDTH * (float) Math.cos(b), bz = SPRING + ARCH_RISE * (float) Math.sin(b);
            float nx = bz - az, nz = ax - bx;
            planes.add(new float[] { nx, 0, nz, nx * ax + nz * az });
        }
        BoardSurface.subtract(faces, planes.stream()
              .<BoardSurface.Boundary>map(plane -> point -> distance(local(point), plane)).toList(), relief);
    }

    private static float distance(Vector3 p, float[] plane) {
        return p.x * plane[0] + p.y * plane[1] + p.z * plane[2] - plane[3];
    }
}
