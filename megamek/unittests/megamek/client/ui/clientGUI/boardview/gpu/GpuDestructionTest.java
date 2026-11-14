/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** A structure's fall as the terrain sees it: standing until it gives way, lying until the rubble shows, then gone. */
class GpuDestructionTest {
    private static final Coords HEX = new Coords(3, 4);
    private static final float FRAME = 1 / 30f;

    private static GpuDestruction taken() {
        var destruction = new GpuDestruction(new GpuLavaLighting(null), (name, level) -> null, null);
        var bounds = new BoundingBox(new Vector3(-30, -30, 0), new Vector3(30, 30, 90));
        destruction.take(HEX, new Array<Renderable>(), Set.of(), bounds, 0, 0, new Vector2(), 0, false);
        return destruction;
    }

    private static void run(GpuDestruction destruction, float seconds) {
        for (float elapsed = 0; elapsed < seconds; elapsed += FRAME) { destruction.advance(FRAME, 1, 0, 0); }
    }

    @Test
    void aStructureStandsUntilItGivesWayAndLiesUntilTheRubbleShows() {
        var destruction = taken();
        assertTrue(destruction.taken(HEX), "Its chunk no longer draws it: its pieces do");
        run(destruction, 1);
        assertFalse(destruction.started(HEX), "Taken, it stands until it gives way");
        destruction.start(HEX, .5f);
        run(destruction, .4f);
        assertFalse(destruction.started(HEX));
        run(destruction, .2f);
        assertTrue(destruction.started(HEX));
        // The terrain still shows the structure (a strike's building the rules bring down at the phase's end): its pile
        // lies there however long that takes.
        destruction.committed(at -> true, (at, trees) -> true);
        run(destruction, 30);
        assertTrue(destruction.taken(HEX), "It lies there while the terrain still shows it standing");
        destruction.committed(at -> false, (at, trees) -> true);
        run(destruction, GpuDestruction.REST_SECONDS);
        assertTrue(destruction.taken(HEX), "Its pile rests on the rubble before it sinks");
        run(destruction, GpuDestruction.SINK_SECONDS + .1f);
        assertFalse(destruction.taken(HEX), "Sunk, it is gone");
        assertTrue(destruction.isEmpty());
    }

    @Test
    void aStructureFallsBeforeItsPileRestsAndInstantEndsEveryFall() {
        var destruction = taken();
        destruction.start(HEX, 0);
        // Its rubble shows at once: the pile rests only once its pieces have come down.
        destruction.committed(at -> false, (at, trees) -> true);
        run(destruction, GpuDestruction.REST_SECONDS + GpuDestruction.SINK_SECONDS);
        assertTrue(destruction.taken(HEX), "A 90-unit structure still falls and settles before its pile's rest begins");
        destruction.clear();
        assertFalse(destruction.taken(HEX));
    }

    /** On a zero-g board the pieces drift on; their pile still rests and goes. */
    @Test
    void withoutGravityAFallStillEnds() {
        var destruction = taken();
        // The game's order: the clock and its gravity run every frame before anything gives way.
        destruction.advance(0, 0, 0, 0);
        destruction.start(HEX, 0);
        destruction.committed(at -> false, (at, trees) -> true);
        for (float elapsed = 0; elapsed < 20; elapsed += FRAME) { destruction.advance(FRAME, 0, 0, 0); }
        assertFalse(destruction.taken(HEX));
    }

    /** Neighbouring falls raise one cloud of dust between them, and it hangs on after their piles have sunk. */
    @Test
    void neighbouringFallsShareOneCloudThatOutlivesTheirPiles() {
        var destruction = taken();
        var neighbour = new Coords(HEX.getX(), HEX.getY() + 1);
        destruction.take(neighbour, new Array<Renderable>(), Set.of(),
              new BoundingBox(new Vector3(-30, 40, 0), new Vector3(30, 100, 90)), 0, 0, new Vector2(), 0, false);
        destruction.start(HEX, 0);
        destruction.start(neighbour, .2f);
        destruction.committed(at -> false, (at, trees) -> true);
        var effects = new GpuExplosionEffects();
        run(destruction, .5f);
        destruction.dust(effects);
        assertEquals(1, effects.size(), "One cloud for both");
        for (float elapsed = .5f; !destruction.isEmpty(); elapsed += FRAME) {
            assertTrue(elapsed < GpuDestruction.CLOUD_SECONDS, "Both piles sink before their dust has cleared");
            destruction.advance(FRAME, 1, 0, 0);
        }
        effects.begin();
        destruction.dust(effects);
        assertEquals(1, effects.size(), "Their dust still hangs there");
        run(destruction, GpuDestruction.CLOUD_SECONDS);
        effects.begin();
        destruction.dust(effects);
        assertEquals(0, effects.size(), "It has cleared");
    }

    /**
     * The handover does not show: a body thrown from a piece in the air stands where the closed form has the piece at
     * that moment, turned alike, and a step later has moved and turned as the closed form does.
     */
    @Test
    void aPieceIsHandedToItsBodyAsTheClosedFormHasIt() {
        var form = new FallForm();
        form.board(GpuDestruction.GRAVITY, 0, 0);
        // A fall under way since 0, pushed by a hit, around (0, 0) from 0 to 90 high; it never sinks.
        float[] slot = { 0, 0, .6f, .3f, 0, 0, 0, 90, 1e9f, 0, 0, 0 };
        // A mirrored instance at (12, -7, 30), 1.5 times as wide, turned; its piece's box off its origin, turned in it.
        float[] inputs = { 12, -7, 30, -1.5f, (float) Math.cos(.7), (float) Math.sin(.7), 1.2f, 0,
              3, 2, 20, 0, 6, 1, 9, .3f };
        assertTrue(form.start(slot, inputs, 0));
        float instant = form.begins + .8f * form.landing, step = 1 / 120f;
        var poses = new GpuPoses();
        var bodies = new MeepleDebris();
        float[] pose = new float[8];
        assertTrue(form.at(instant));
        form.row(poses, 0);
        int body = form.body(bodies, instant - form.begins);
        bodies.pose(body, pose, 0);
        form.row(poses, 1, pose);
        assertSamePose(poses.get(0), poses.get(1), .01f, "At the handover");
        bodies.advance(step, (x, y, from, own) -> -1000, form.gravity / GpuDestruction.GRAVITY);
        bodies.pose(body, pose, 0);
        form.row(poses, 1, pose);
        form.at(instant + step);
        form.row(poses, 0);
        // The stepper's fall over one step lies half a step of gravity below the parabola.
        assertSamePose(poses.get(0), poses.get(1), .01f + form.gravity * step * step, "A step later");
    }

    /** Two rows pose a piece alike: its shift within {@code units}, its turn within half a degree. */
    private static void assertSamePose(float[] expected, float[] row, float units, String when) {
        assertEquals(0, Vector3.dst(expected[0], expected[1], expected[2], row[0], row[1], row[2]), units, when);
        assertEquals(expected[3], row[3], 1e-4f, when);
        float turned = Math.abs(expected[4] * row[4] + expected[5] * row[5] + expected[6] * row[6] + expected[7] * row[7]);
        assertEquals(0, Math.toDegrees(2 * Math.acos(Math.min(1, turned))), .5, when);
    }

    /** Three whole parts of a structure on {@link #HEX}, one above the other, and the level board under them. */
    private static GpuDestruction shown(boolean admits) {
        var destruction = new GpuDestruction(new GpuLavaLighting(null), (name, level) -> null, null);
        destruction.floors(new MeepleDebris.Floors(new float[64], 8, 8, BoardGeometry.width(), BoardGeometry.height()));
        if (!admits) { destruction.stepBudget = -1; }
        var middle = BoardGeometry.center(HEX, 0);
        var mesh = mock(Mesh.class);
        when(mesh.getVertexAttributes()).thenReturn(new VertexAttributes(VertexAttribute.Position()));
        var pieces = new Array<Renderable>();
        for (int storey = 0; storey < 3; storey++) {
            var piece = new Renderable();
            piece.meshPart.mesh = mesh;
            piece.meshPart.halfExtents.set(6, 1, 4);
            piece.worldTransform.setToTranslation(middle.x + 4 * storey, middle.y - 3, 20 + 30 * storey);
            pieces.add(piece);
        }
        var bounds = new BoundingBox(new Vector3(middle.x - 30, middle.y - 30, 0), new Vector3(middle.x + 30, middle.y + 30, 90));
        destruction.take(HEX, pieces, Set.of(), bounds, middle.x, middle.y, new Vector2(), 0, true);
        return destruction;
    }

    /**
     * Where a fall shows, its pieces have a pose row each and land as bodies as they touch down; every one lies still on
     * the ground before its pile sinks, and the rows are free again once it has gone.
     */
    @Test
    void thePiecesOfAFallThatShowsLandAsBodies() {
        var destruction = shown(true);
        assertEquals(3, destruction.contacts().rows());
        destruction.start(HEX, 0);
        run(destruction, .1f);
        assertEquals(0, destruction.contacts().admitted(), "In the air they fall in closed form");
        assertTrue(destruction.poses().get(0)[3] < 0, "Their rows name the slot until then");
        run(destruction, 2.4f);
        assertEquals(3, destruction.contacts().admitted());
        assertEquals(0, destruction.contacts().awake(), "None is left moving");
        for (int row = 0; row < 3; row++) {
            float[] pose = destruction.poses().get(row);
            // A part's box lies about its instance's origin here: the shift is its middle's.
            float height = 20 + 30 * row + pose[2];
            assertEquals(1, pose[3], "It is posed by its body");
            assertTrue(height > .9f && height < 12, "It lies flat on the ground or the pile's mound: " + height);
            assertEquals(1, Math.abs(new Vector3(0, 1, 0).mul(new Quaternion(pose[4], pose[5], pose[6], pose[7])).z), .01f,
                  "On its flattest side");
        }
        destruction.committed(at -> false, (at, trees) -> true);
        run(destruction, GpuDestruction.REST_SECONDS + GpuDestruction.SINK_SECONDS + .1f);
        assertFalse(destruction.taken(HEX));
        assertEquals(0, destruction.contacts().rows());
    }

    /**
     * Falls taken together share the pose rows: no more than the budget are held, whatever they ask for, and those of
     * a fall past it settle in closed form; once they have gone, the rows are free again.
     */
    @Test
    void fallsTakenTogetherShareTheRowBudget() {
        var destruction = new GpuDestruction(new GpuLavaLighting(null), (name, level) -> null, null);
        var mesh = mock(Mesh.class);
        when(mesh.getVertexAttributes()).thenReturn(new VertexAttributes(VertexAttribute.Position()));
        for (Coords at : new Coords[] { HEX, new Coords(5, 4) }) {
            var middle = BoardGeometry.center(at, 0);
            var pieces = new Array<Renderable>();
            for (int part = 0; part < GpuDestruction.ROW_BUDGET * 3 / 4; part++) {
                var piece = new Renderable();
                piece.meshPart.mesh = mesh;
                piece.meshPart.halfExtents.set(2, 2, 2);
                piece.worldTransform.setToTranslation(middle.x, middle.y, 2 + part % 40);
                pieces.add(piece);
            }
            var bounds = new BoundingBox(new Vector3(middle.x - 30, middle.y - 30, 0), new Vector3(middle.x + 30, middle.y + 30, 90));
            destruction.take(at, pieces, Set.of(), bounds, middle.x, middle.y, new Vector2(), 0, true);
        }
        assertEquals(GpuDestruction.ROW_BUDGET, destruction.contacts().rows(), "The two falls hold the budget between them");
        destruction.committed(at -> false, (at, trees) -> true);
        destruction.startAll();
        run(destruction, 4);
        assertTrue(destruction.isEmpty());
        assertEquals(0, destruction.contacts().rows(), "Their rows are free again");
    }

    /** Past the step budget no piece is handed over: the fall stays in closed form, its rows naming its slot. */
    @Test
    void pastTheStepBudgetAFallStaysInClosedForm() {
        var destruction = shown(false);
        destruction.start(HEX, 0);
        run(destruction, 2);
        assertEquals(0, destruction.contacts().admitted());
        for (int row = 0; row < 3; row++) { assertTrue(destruction.poses().get(row)[3] < 0); }
    }

    /** The terrain may show the rubble before the structure gives way (its transition still framing): it sinks after. */
    @Test
    void rubbleShownBeforeTheFallStillLetsItsPileSink() {
        var destruction = taken();
        destruction.committed(at -> false, (at, trees) -> true);
        run(destruction, 1);
        destruction.start(HEX, 0);
        run(destruction, 10);
        assertFalse(destruction.taken(HEX), "Fallen, rested and sunk");
    }
}
