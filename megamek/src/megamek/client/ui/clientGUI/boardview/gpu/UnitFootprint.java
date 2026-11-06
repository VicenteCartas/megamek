/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Terrains;

/** Actual game occupancy projected into the model's local axes. Shared by every camera and unit family. */
final class UnitFootprint {
    /** The alpha from which a pixel of a stitched image counts as the unit, and not its soft shadow. */
    private static final int OPAQUE = 128;
    /** How far a stitched unit's oval clears its art, in apothems: as far as a one-hex ring clears its icon. */
    private static final float ART_CLEARANCE = .1f;
    /** Each stitched image's {@link #artOval}, measured once. */
    private static final Map<BoardScene.Pixels, Oval> ART_OVALS = Collections.synchronizedMap(new WeakHashMap<>());

    record Layout(float width, float depth, float offsetX, float offsetY) { }

    /**
     * The oval a multi-hex unit's base and selection marks share, as the unit rests: its centre from the unit's position,
     * its semi-axes, and {@code angle}, the major axis in degrees counterclockwise of east.
     */
    record Oval(float x, float y, float major, float minor, float angle) {
        /** The point {@code radians} round this oval, grown by {@code grow} on both axes. */
        Vector2 point(float radians, float grow) {
            return turned((major + grow) * MathUtils.cos(radians), (minor + grow) * MathUtils.sin(radians), angle).add(x, y);
        }

        float halfWidth() {
            return Vector2.len(major * MathUtils.cosDeg(angle), minor * MathUtils.sinDeg(angle));
        }

        float halfHeight() {
            return Vector2.len(major * MathUtils.sinDeg(angle), minor * MathUtils.cosDeg(angle));
        }

        /** The rim straight along {@code bearing}, in degrees clockwise of north, from the centre. */
        Vector2 rim(float bearing) {
            Vector2 along = turned(0, 1, -bearing);
            Vector2 own = turned(along.x, along.y, -angle);
            return along.scl(1 / Vector2.len(own.x / major, own.y / minor)).add(x, y);
        }

        Oval scaled(float scale) {
            return new Oval(x * scale, y * scale, major * scale, minor * scale, angle);
        }
    }

    /** The same GL-frame pose used to place a model, never a replacement for game occupancy. */
    record Pose(BoardScene.Unit unit, Vector3 position, float facing) {
        Pose {
            position = position.cpy();
        }

        Vector3 outlinePoint(Coords occupied, int corner) {
            return outlinePoint(occupied, corner, BoardGeometry.MARKER_INSET);
        }

        Vector3 outlinePoint(Coords occupied, int corner, float inset) {
            Vector3 center = BoardGeometry.center(occupied, 0);
            return posed(BoardGeometry.inset(BoardGeometry.corner(occupied, 0, corner), center, inset)
                  .sub(BoardGeometry.center(unit.location().coords(), 0)));
        }

        /** The point {@code radians} round {@code oval}, {@code inset} hex radii inside it; 1 is its centre, as for a hex. */
        Vector3 ovalPoint(Oval oval, float radians, float inset) {
            Vector2 point = inset >= 1 ? new Vector2(oval.x(), oval.y())
                  : oval.point(radians, -inset * BoardGeometry.width() / 2);
            return posed(new Vector3(point.x, point.y, 0));
        }

        private Vector3 posed(Vector3 rested) {
            return rested.rotate(Vector3.Z, unit.location().facing() * 60 - facing).add(position).add(0, 0, .5f);
        }
    }

    private UnitFootprint() { }

    private static Vector2 turned(float x, float y, float degrees) {
        float cos = MathUtils.cosDeg(degrees);
        float sin = MathUtils.sinDeg(degrees);
        return new Vector2(x * cos - y * sin, x * sin + y * cos);
    }

    /**
     * The hexes of a unit's one base and selection marks: its footprint, or for a unit drawn in parts every part's hex on
     * its first part and none on the others.
     */
    static List<Coords> markedHexes(BoardScene scene, BoardScene.Unit unit) {
        if (unit.part() < 0) {
            return unit.footprint().isEmpty() ? List.of(unit.location().coords()) : unit.footprint();
        }
        List<BoardScene.Unit> parts = scene.units().stream().filter(other -> other.id() == unit.id()).toList();
        if (parts.isEmpty()) {
            return List.of(unit.location().coords());
        }
        return parts.getFirst().part() == unit.part() ? parts.stream().map(part -> part.location().coords()).toList() : List.of();
    }

    /** World units per pixel of a multi-hex unit's whole image, which covers its footprint. */
    static float imageScale(Layout layout, BoardScene.Pixels image) {
        return Math.min(layout.width() / image.width(), layout.depth() / image.height()) * BoardGeometry.multiHexUnitScale();
    }

    /**
     * The oval a multi-hex unit's base and selection marks share. A stitched image shows the unit's shape, so a Union's is
     * nearly round and an Aurora's long, whatever hexes they occupy; without one it goes around {@code hexes}.
     */
    static Oval oval(BoardScene.Unit unit, List<Coords> hexes) {
        BoardScene.Pixels image = unit.image();
        if (unit.part() >= 0 || image == null || image.width() <= HexTileset.HEX_W) {
            return oval(unit.location().coords(), hexes);
        }
        // Turned clockwise with the unit and clear of its art, as a one-hex ring clears its icon.
        float rest = unit.location().facing() * 60;
        Layout layout = layout(unit.location().coords(), unit.footprint(), rest);
        float scale = imageScale(layout, image);
        Oval art = ART_OVALS.computeIfAbsent(image, UnitFootprint::artOval);
        Vector2 center = turned(art.x() * scale, art.y() * scale, -rest);
        float margin = ART_CLEARANCE * BoardGeometry.height() / 2;
        return new Oval(layout.offsetX() + center.x, layout.offsetY() + center.y, art.major() * scale + margin,
              art.minor() * scale + margin, art.angle() - rest);
    }

    /**
     * The oval of least area around an image's opaque pixels, in pixels from the image's centre with y up, along the
     * image's axes: the art is drawn nose up, so its axes are the ship's.
     */
    private static Oval artOval(BoardScene.Pixels image) {
        int width = image.width();
        int height = image.height();
        int[] opaque = new int[width * height];
        int count = 0;
        int left = width;
        int right = 0;
        int top = height;
        int bottom = 0;
        for (int index = 0; index < width * height; index++) {
            if ((image.rgba(index) & 255) >= OPAQUE) {
                opaque[count++] = index;
                left = Math.min(left, index % width);
                right = Math.max(right, index % width);
                top = Math.min(top, index / width);
                bottom = Math.max(bottom, index / width);
            }
        }
        if (count == 0) {
            return new Oval(0, 0, width / 2f, height / 2f, 0);
        }
        float column = (left + right) / 2f;
        float row = (top + bottom) / 2f;
        float leastArea = Float.POSITIVE_INFINITY;
        float wide = 1;
        float tall = 0;
        // Every proportion of width to height that a ship's art could have, the height each needs to hold every pixel.
        for (float stretch = .25f; stretch <= 4; stretch *= 1.05f) {
            float reach = 0;
            for (int at = 0; at < count; at++) {
                float across = (opaque[at] % width - column) / stretch;
                float up = opaque[at] / width - row;
                reach = Math.max(reach, across * across + up * up);
            }
            if (reach * stretch < leastArea) {
                leastArea = reach * stretch;
                wide = stretch;
                tall = reach;
            }
        }
        tall = (float) Math.sqrt(tall);
        float x = column - (width - 1) / 2f;
        float y = (height - 1) / 2f - row;
        return wide >= 1 ? new Oval(x, y, tall * wide, tall, 0) : new Oval(x, y, tall, tall * wide, 90);
    }

    /**
     * The oval around {@code occupied}, every hex corner inside it. Its proportions and axes are those of the hexes' area,
     * so a round footprint gets a circle and a long one an oval along it.
     */
    static Oval oval(Coords origin, List<Coords> occupied) {
        Vector3 center = BoardGeometry.center(origin, 0);
        float x = 0;
        float y = 0;
        for (Coords coords : occupied) {
            Vector3 hex = BoardGeometry.center(coords, 0).sub(center);
            x += hex.x / occupied.size();
            y += hex.y / occupied.size();
        }
        // A regular hexagon's own second moment about its centre is 5/24 of its squared side.
        float side = BoardGeometry.width() / 2;
        float xx = side * side * 5 / 24;
        float yy = xx;
        float xy = 0;
        for (Coords coords : occupied) {
            Vector3 hex = BoardGeometry.center(coords, 0).sub(center).sub(x, y, 0);
            xx += hex.x * hex.x / occupied.size();
            yy += hex.y * hex.y / occupied.size();
            xy += hex.x * hex.y / occupied.size();
        }
        float angle = (float) Math.toDegrees(Math.atan2(2 * xy, xx - yy)) / 2;
        float spread = Vector2.len((xx - yy) / 2, xy);
        float ratio = (float) Math.sqrt(((xx + yy) / 2 + spread) / ((xx + yy) / 2 - spread));
        float minor = 0;
        Vector3 point = new Vector3();
        for (Coords coords : occupied) {
            for (int corner = 0; corner < 6; corner++) {
                BoardGeometry.corner(point, coords, 0, corner).sub(center).sub(x, y, 0);
                Vector2 own = turned(point.x, point.y, -angle);
                minor = Math.max(minor, Vector2.len(own.x / ratio, own.y));
            }
        }
        return new Oval(x, y, minor * ratio, minor, angle);
    }

    static float support(Board board, Coords origin, List<Coords> occupied, float relativeElevation) {
        Hex anchor = board.getHex(origin);
        float highest = (anchor == null ? 0 : anchor.getLevel()) + relativeElevation;
        int surface = -1;
        if (anchor != null && relativeElevation > 0) {
            for (int candidate : new int[] { Terrains.BRIDGE_ELEV, Terrains.BLDG_ELEV }) {
                if (anchor.terrainLevel(candidate) == relativeElevation) {
                    surface = candidate;
                    break;
                }
            }
        }
        for (Coords coords : occupied) {
            var hex = board.getHex(coords);
            if (hex != null) {
                highest = Math.max(highest, hex.getLevel() + (surface >= 0 && hex.containsTerrain(surface) ? hex.terrainLevel(surface) : 0));
            }
        }
        return highest;
    }

    static boolean terrainSupported(EntityMovementMode mode) {
        return mode != EntityMovementMode.NAVAL && mode != EntityMovementMode.HYDROFOIL && mode != EntityMovementMode.SUBMARINE;
    }

    /** Continuous visual clearance for a moving large hull. The game path and occupied coordinates are unchanged. */
    static void clearTerrain(BoardScene scene, BoardScene.Unit unit, Vector3 position, float facing) {
        clearTerrain(scene, unit, position, facing, unit.airborne());
    }

    static void clearTerrain(BoardScene scene, BoardScene.Unit unit, Vector3 position, float facing, boolean airborne) {
        if (airborne || unit.footprint().size() < 2) {
            return;
        }
        var mode = unit.model() == null || unit.model().state() == null ? null : unit.model().state().structure().movement();
        if (!terrainSupported(mode)) {
            return;
        }
        float support = -Float.MAX_VALUE;
        Vector3 origin = BoardGeometry.center(unit.location().coords(), 0);
        float turn = unit.location().facing() * 60 - facing;
        for (Coords occupied : unit.footprint()) {
            float[] vertices = new float[12];
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 6; corner++) {
                Vector3 point = BoardGeometry.corner(occupied, 0, corner).sub(origin).rotate(Vector3.Z, turn).add(position);
                vertices[corner * 2] = point.x;
                vertices[corner * 2 + 1] = point.y;
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
            }
            Polygon moved = new Polygon(vertices.clone());
            int left = Math.max(0, (int) (minX / (BoardGeometry.width() * .75f)) - 1);
            int right = Math.min(scene.width() - 1, (int) (maxX / (BoardGeometry.width() * .75f)) + 1);
            int top = Math.max(0, (int) (-maxY / BoardGeometry.height()) - 1);
            int bottom = Math.min(scene.height() - 1, (int) (-minY / BoardGeometry.height()) + 1);
            for (int x = left; x <= right; x++) {
                for (int y = top; y <= bottom; y++) {
                    var tile = scene.tile(new Coords(x, y));
                    for (int corner = 0; corner < 6; corner++) {
                        Vector3 point = BoardGeometry.corner(tile.coords(), 0, corner);
                        vertices[corner * 2] = point.x;
                        vertices[corner * 2 + 1] = point.y;
                    }
                    if (Intersector.overlapConvexPolygons(moved, new Polygon(vertices))) {
                        support = Math.max(support, tile.elevation());
                    }
                }
            }
        }
        if (support != -Float.MAX_VALUE) {
            position.z = Math.max(position.z, support * BoardGeometry.level());
        }
    }

    static Layout layout(Coords origin, List<Coords> occupied, float facing) {
        Vector3 center = BoardGeometry.center(origin, 0);
        Vector3 point = new Vector3();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Coords coords : occupied) {
            for (int corner = 0; corner < 6; corner++) {
                BoardGeometry.corner(point, coords, 0, corner).sub(center).rotate(Vector3.Z, facing);
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
            }
        }
        if (occupied.isEmpty()) {
            return new Layout(BoardGeometry.width(), BoardGeometry.height(), 0, 0);
        }
        point.set((minX + maxX) / 2, (minY + maxY) / 2, 0).rotate(Vector3.Z, -facing);
        return new Layout(maxX - minX, maxY - minY, point.x, point.y);
    }
}
