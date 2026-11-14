/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pieces thrown past the board's edge fall away below it; boxes thrown as the caller says ({@link MeepleDebris#box})
 * land on the floors of the hexes under them ({@link MeepleDebris.Floors}) and lie down. Nothing is drawn.
 */
class MeepleDebrisTest {
    /** A level and a hex across its flats, in board model units, as a building stands at the board's scale. */
    private static final float STOREY = 18, HEX = 72, FRAME = 1 / 60f;
    private static final MeepleDebris.Ground FLAT = (x, y, from, own) -> 0;

    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

    /** A three-storey block, 40 by 30, in twelve pieces: two by two per storey. */
    private record Block(ModelInstance instance, Node pieces, List<List<Vector3>> supports, List<Vector3> rests) { }

    @Test
    void piecesThrownPastTheBoardsEdgeFallAwayBelowIt() {
        // The board ends just east of the block; a hit from the west throws its pieces that way.
        float edge = 24;
        var block = block();
        var debris = new MeepleDebris(block.instance(), block.pieces(), block.supports(), new Vector3(400, 0, 0), 5);
        run(debris, 6, (x, y, from, own) -> x > edge ? Float.NaN : 0);
        assertTrue(debris.settled(), "Every piece comes to rest or is gone");
        int gone = 0;
        var settled = centers(block);
        for (int index = 0; index < settled.size(); index++) {
            // A piece may hang over the edge by less than its half; farther out nothing holds it.
            if (settled.get(index).x < edge + 12) { continue; }
            gone++;
            assertTrue(settled.get(index).z < -1, "Past the edge a piece falls below the ground: " + settled.get(index));
            assertTrue(lowest(block, index) > settled.get(index).z - .1f, "and has shrunk away");
        }
        assertTrue(gone > 0, "The hit throws pieces past the edge");
    }

    @Test
    void boxesLandOnTheFloorsOfTheHexesUnderThem() {
        // Three by three hexes, the middle column a level up.
        int columns = 3, rows = 3;
        float[] heights = new float[columns * rows];
        for (int row = 0; row < rows; row++) { heights[rows + row] = STOREY; }
        var floors = new MeepleDebris.Floors(heights, columns, rows, HexTile.WIDTH, HexTile.HEIGHT);
        var debris = new MeepleDebris();
        var level = new Quaternion();
        var half = new Vector3(6, 4, 3);
        // Dropped over a low hex; tumbling over the high one; over the edge the two share, turned a sixth; and thrown
        // east, past the board's edge.
        int low = debris.box(new Vector3(42, -108, 30), half, level, new Vector3(), new Vector3(), 0);
        int high = debris.box(new Vector3(105, -144, 50), half, level, new Vector3(), new Vector3(1, 2, .5f), 0);
        int between = debris.box(new Vector3(73.5f, -126, 40), half, new Quaternion(Vector3.Z, 60), new Vector3(),
              new Vector3(0, .5f, 0), .1f);
        int thrown = debris.box(new Vector3(168, -108, 20), half, level, new Vector3(200, 0, 30), new Vector3(0, 3, 0), 0);
        debris.advance(.05f, floors, 1);
        float[] pose = new float[8];
        debris.pose(between, pose, 0);
        assertEquals(40, pose[2], 0, "A box stands until its time comes");
        boolean hard = false;
        for (float elapsed = 0; elapsed < 6; elapsed += FRAME) {
            debris.advance(FRAME, floors, 1);
            hard |= !debris.landings().isEmpty();
        }
        assertTrue(debris.settled(), "Every box comes to rest or is gone");
        assertTrue(hard, "A box landing hard raises dust");
        assertEquals(0, lowest(debris, low, half, level, floors), .5f, "A box over a low hex rests on its floor");
        assertEquals(0, lowest(debris, high, half, level, floors), .5f, "A box over a high hex rests on its floor");
        debris.pose(high, pose, 0);
        assertTrue(pose[2] > STOREY, "a level up: " + pose[2]);
        assertTrue(lowest(debris, between, half, new Quaternion(Vector3.Z, 60), floors) > -.5f, "No corner sinks into the hex under it");
        debris.pose(thrown, pose, 0);
        assertTrue(pose[0] > 3 * HexTile.WIDTH * .75f && pose[2] < -1, "Past the edge a box falls below the ground: " + pose[0]
              + ", " + pose[2]);
        assertEquals(1e-3f, pose[3], 1e-6f, "and has shrunk away");
    }

    /** A slab that comes to rest standing on its edge lies down, onto the mound of the pile it landed on. */
    @Test
    void aBoxAtRestLiesDownOnItsFlattestSide() {
        var floors = new MeepleDebris.Floors(new float[4], 2, 2, HexTile.WIDTH, HexTile.HEIGHT);
        var debris = new MeepleDebris();
        int slab = debris.box(new Vector3(HexTile.WIDTH / 2, -HexTile.HEIGHT / 2, 9.2f), new Vector3(1, 8, 6),
              new Quaternion(), new Vector3(), new Vector3(), 0);
        debris.handed(slab, new Quaternion(), 3);
        for (int frame = 0; frame < 180; frame++) { debris.advance(1 / 60f, floors, 1); }
        assertTrue(debris.settled(), "It comes to rest");
        float[] pose = new float[8];
        debris.pose(slab, pose, 0);
        assertEquals(1, Math.abs(new Vector3(1, 0, 0).mul(new Quaternion(pose[4], pose[5], pose[6], pose[7])).z), 1e-3f,
              "Its thinnest side lies up");
        assertEquals(4, pose[2], .01f, "Half its thickness above the mound");
    }

    @Test
    void theFloorsAnswerForTheHexThatHoldsAPoint() {
        // Each hex's floor is its board index: the floor under a point names the hex found there.
        int columns = 5, rows = 4;
        float[] heights = new float[columns * rows];
        for (int index = 0; index < heights.length; index++) { heights[index] = index; }
        var floors = new MeepleDebris.Floors(heights, columns, rows, HexTile.WIDTH, HexTile.HEIGHT);
        int on = 0, off = 0;
        for (float x = -90; x < 430; x += 3.7f) {
            for (float y = 60; y > -380; y -= 2.9f) {
                int hex = HexTile.at(x, y, HexTile.WIDTH, HexTile.HEIGHT, columns, rows);
                if (hex < 0) {
                    off++;
                    assertTrue(floors.at(x, y) < -1e5f, "No floor past the board's edge at " + x + ", " + y);
                } else {
                    on++;
                    assertEquals(hex, floors.at(x, y), 0, "The hex at " + x + ", " + y);
                }
            }
        }
        assertTrue(on > 1000 && off > 1000, "Points on the board and past each of its edges: " + on + ", " + off);
        // The middle of hex 0203 (2, 2): a hex is 72 across its flats.
        float x = 2 * HexTile.WIDTH * .75f + HexTile.WIDTH / 2f, y = -(2 * HexTile.HEIGHT + HexTile.HEIGHT / 2f);
        assertEquals(2 * rows + 2, floors.under(x, y, 30), 0, "What lies within a hex has its one floor under it");
        assertTrue(Float.isNaN(floors.under(x, y, 40)), "What reaches past its hex's edge has none");
        assertTrue(Float.isNaN(floors.under(x, y + 30, 10)), "nor what lies near that edge");
    }

    /**
     * How far above the floor of the hex under it the box's lowest corner lies, as it is posed: its {@code half} size
     * along its axes, which stood turned by {@code axes}.
     */
    private static float lowest(MeepleDebris debris, int box, Vector3 half, Quaternion axes, MeepleDebris.Floors floors) {
        float[] pose = new float[8];
        debris.pose(box, pose, 0);
        var turn = new Quaternion(pose[4], pose[5], pose[6], pose[7]).mul(axes);
        float lowest = Float.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            var point = turn.transform(new Vector3((corner & 1) == 0 ? half.x : -half.x, (corner & 2) == 0 ? half.y : -half.y,
                  (corner & 4) == 0 ? half.z : -half.z)).add(pose[0], pose[1], pose[2]);
            lowest = Math.min(lowest, point.z - floors.at(point.x, point.y));
        }
        return lowest;
    }

    private static Block block() {
        List<List<Vector3>> supports = new ArrayList<>();
        List<Vector3> rests = new ArrayList<>();
        for (int storey = 0; storey < 3; storey++) {
            for (int x = 0; x < 2; x++) {
                for (int y = 0; y < 2; y++) {
                    var min = new Vector3(-20 + x * 20, -15 + y * 15, storey * STOREY);
                    supports.add(box(min, new Vector3(min).add(20, 15, STOREY)));
                    rests.add(new Vector3(min).add(10, 7.5f, STOREY / 2));
                }
            }
        }
        return instance(supports, rests);
    }

    private static Block instance(List<List<Vector3>> supports, List<Vector3> rests) {
        var model = new Model();
        var root = new Node();
        root.id = "rubble";
        model.nodes.add(root);
        for (int index = 0; index < supports.size(); index++) {
            var piece = new Node();
            piece.id = MeepleShatter.PIECE + index;
            root.addChild(piece);
        }
        var instance = new ModelInstance(model);
        return new Block(instance, instance.nodes.first(), supports, rests);
    }

    /** Points on the box's surface a quarter of its size apart, as MeepleShatter samples a piece's. */
    private static List<Vector3> box(Vector3 min, Vector3 max) {
        List<Vector3> points = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 4; z++) {
                    if (x % 4 != 0 && y % 4 != 0 && z % 4 != 0) { continue; }
                    points.add(new Vector3(min.x + (max.x - min.x) * x / 4, min.y + (max.y - min.y) * y / 4,
                          min.z + (max.z - min.z) * z / 4));
                }
            }
        }
        return points;
    }

    private static void run(MeepleDebris debris, float seconds) { run(debris, seconds, FLAT); }

    private static void run(MeepleDebris debris, float seconds, MeepleDebris.Ground ground) {
        for (float elapsed = 0; elapsed < seconds; elapsed += FRAME) { debris.advance(FRAME, ground, 1); }
        debris.pose();
    }

    private static List<Vector3> centers(Block block) {
        List<Vector3> result = new ArrayList<>();
        int index = 0;
        for (Node piece : block.pieces().getChildren()) {
            result.add(new Vector3(block.rests().get(index++)).mul(piece.globalTransform));
        }
        return result;
    }

    private static float lowest(Block block, int index) {
        float lowest = Float.POSITIVE_INFINITY;
        var transform = block.pieces().getChild(index).globalTransform;
        for (Vector3 point : block.supports().get(index)) { lowest = Math.min(lowest, new Vector3(point).mul(transform).z); }
        return lowest;
    }
}
