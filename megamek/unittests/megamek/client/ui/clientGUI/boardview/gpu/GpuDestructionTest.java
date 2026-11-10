/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.badlogic.gdx.graphics.g3d.Renderable;
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
        var destruction = new GpuDestruction(new GpuLavaLighting(null), (name, level) -> null);
        var bounds = new BoundingBox(new Vector3(-30, -30, 0), new Vector3(30, 30, 90));
        destruction.take(HEX, new Array<Renderable>(), Set.of(), bounds, 0, 0, new Vector2(), 0);
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

    /**
     * Rubble where the old path broke a structure (a bridge's span) stays out of its chunk, risen by its slot, until its
     * pieces land, however long they take; then it rises, and its chunk draws it again once the slot has rested and gone.
     */
    @Test
    void rubbleWaitsForTheOldPathsPiecesToLand() {
        var destruction = new GpuDestruction(new GpuLavaLighting(null), (name, level) -> null);
        var bounds = new BoundingBox(new Vector3(-20, -20, 0), new Vector3(20, 20, 6));
        destruction.rubble(HEX, new Array<Renderable>(), bounds, 0, 0);
        destruction.committed(at -> false, (at, trees) -> true);
        run(destruction, 30);
        assertTrue(destruction.taken(HEX), "Its rubble waits under the ground while the pieces are in the air");
        destruction.landed(HEX);
        run(destruction, GpuDestruction.RISE_SECONDS);
        assertTrue(destruction.taken(HEX), "Risen, it lies under the pieces as they rest");
        run(destruction, GpuDestruction.REST_SECONDS + GpuDestruction.SINK_SECONDS);
        assertFalse(destruction.taken(HEX), "Its chunk draws it again");
        assertTrue(destruction.isEmpty());
    }

    /** Neighbouring falls raise one cloud of dust between them, and it hangs on after their piles have sunk. */
    @Test
    void neighbouringFallsShareOneCloudThatOutlivesTheirPiles() {
        var destruction = taken();
        var neighbour = new Coords(HEX.getX(), HEX.getY() + 1);
        destruction.take(neighbour, new Array<Renderable>(), Set.of(),
              new BoundingBox(new Vector3(-30, 40, 0), new Vector3(30, 100, 90)), 0, 0, new Vector2(), 0);
        destruction.start(HEX, 0);
        destruction.start(neighbour, .2f);
        destruction.committed(at -> false, (at, trees) -> true);
        var effects = new GpuExplosionEffects();
        run(destruction, .5f);
        destruction.dust(effects);
        assertEquals(1, effects.size(), "One cloud for both");
        run(destruction, 4.5f);
        assertTrue(destruction.isEmpty(), "Both piles have sunk");
        effects.begin();
        destruction.dust(effects);
        assertEquals(1, effects.size(), "Their dust still hangs there");
        run(destruction, GpuDestruction.CLOUD_SECONDS);
        effects.begin();
        destruction.dust(effects);
        assertEquals(0, effects.size(), "It has cleared");
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
