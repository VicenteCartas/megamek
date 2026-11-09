/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A structure's pieces collapsing ({@link MeepleDebris#collapse}): a building comes down under its own weight where it
 * stood; only a strong hit pushes it over, away from the hit; a deck gives way where it fails first. The pieces are
 * boxes on flat ground; nothing is drawn.
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
    void underItsOwnWeightABuildingComesDownWhereItStood() {
        var block = block();
        var debris = MeepleDebris.collapse(block.instance(), block.pieces(), block.supports(),
              new MeepleDebris.Collapse(new Vector3(), STOREY, false, null, 0, List.of()), 5);
        run(debris, .08f);
        var early = centers(block);
        assertEquals(block.rests().getLast().z, early.getLast().z, .5f, "The top storey still stands as the foot gives way");
        run(debris, 4);
        assertTrue(debris.settled(), "Every piece comes to rest");
        var settled = centers(block);
        var drift = new Vector3();
        float before = 0, after = 0;
        for (int index = 0; index < settled.size(); index++) {
            var moved = new Vector3(settled.get(index)).sub(block.rests().get(index));
            drift.add(moved.x, moved.y, 0);
            assertTrue(Math.hypot(moved.x, moved.y) < HEX * .6f, "No piece flies off: " + moved);
            before += block.rests().get(index).z;
            after += settled.get(index).z;
            assertTrue(lowest(block, index) > -1, "No piece sinks into the ground");
        }
        drift.scl(1f / settled.size());
        assertTrue(drift.len() < 6, "With nothing pushing it, the rubble spreads about where it stood: " + drift);
        assertTrue(after < before * .6f, "It came down: its pieces' middles lie " + after / before + " as high as they stood");
    }

    @Test
    void onlyAStrongHitPushesItOverAwayFromTheHit() {
        float weak = eastward(0), strong = eastward(1);
        // A quarter of the block's width: it falls away from the hit, without flying off.
        assertTrue(strong > 10, "An AC/20 from the west throws it east: " + strong);
        assertTrue(strong > Math.abs(weak) + 8, "A machine gun adds nothing: " + weak + " vs " + strong);
    }

    @Test
    void aDeckGivesWayFirstUnderItsLoad() {
        // Six slabs in a row along x, a deck two levels up; a unit stands on its east end.
        List<List<Vector3>> supports = new ArrayList<>();
        List<Vector3> rests = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            var min = new Vector3(-36 + index * 12, -10, 34);
            supports.add(box(min, new Vector3(min).add(12, 20, 2)));
            rests.add(new Vector3(min).add(6, 10, 1));
        }
        var block = instance(supports, rests);
        var load = new Vector3(30, 0, 36);
        var debris = MeepleDebris.collapse(block.instance(), block.pieces(), block.supports(),
              new MeepleDebris.Collapse(new Vector3(0, 0, 34), STOREY, true, null, 0, List.of(load)), 3);
        run(debris, .12f);
        var falling = centers(block);
        float east = block.rests().getLast().z - falling.getLast().z, west = block.rests().getFirst().z - falling.getFirst().z;
        assertTrue(east > west + 1, "The slab under the unit drops first: " + east + " vs " + west);
        run(debris, 4);
        for (int index = 0; index < 6; index++) {
            assertTrue(lowest(block, index) < 6, "Every slab ends on the ground: " + lowest(block, index));
        }
    }

    @Test
    void piecesThrownPastTheBoardsEdgeFallAwayBelowIt() {
        // The board ends just east of the block; an AC/20 from the west throws it that way.
        float edge = 24;
        var block = block();
        var debris = MeepleDebris.collapse(block.instance(), block.pieces(), block.supports(),
              new MeepleDebris.Collapse(new Vector3(), STOREY, false, new Vector3(-400, 0, 20), 1, List.of()), 5);
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

    /** How far, on average, a hit of {@code strength} from the west carries the block's pieces east. */
    private static float eastward(float strength) {
        var block = block();
        var debris = MeepleDebris.collapse(block.instance(), block.pieces(), block.supports(),
              new MeepleDebris.Collapse(new Vector3(), STOREY, false, new Vector3(-400, 0, 20), strength, List.of()), 5);
        run(debris, 4);
        float sum = 0;
        var settled = centers(block);
        for (int index = 0; index < settled.size(); index++) { sum += settled.get(index).x - block.rests().get(index).x; }
        return sum / settled.size();
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
