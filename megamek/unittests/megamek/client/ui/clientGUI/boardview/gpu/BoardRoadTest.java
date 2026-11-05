/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadTest {

    /** {@code t} with its road's finish authored as the colony's slabs. */
    private static BoardScene.Tile slabs(BoardScene.Tile t) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(), t.detailedGround(),
              t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(), t.groundCover(),
              t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(),
              Map.of("road", new megamek.common.board.HexAppearance(null, null, "colony-slabs", null)), t.cover(), t.barren(),
              t.deckRoad(), t.deckRoadExits(), t.deckMud(), t.railBed(), t.corridor(), t.deckCorridor(), t.backdrop());
    }

    @Test
    void fiveAndSixExitsCirculateAroundAnIslandAndKeepEveryApproachFullWidth() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (int absent = -1; absent < 6; absent++) {
                int exits = absent < 0 ? 63 : 63 & ~(1 << absent);
                var road = BoardRoad.clearance(at, exits);
                var shape = road.footprint(0);
                var verge = road.footprint(BoardRoad.SHOULDER);
                var paint = road.markings(at, exits);
                for (int angle = 0; angle < 360; angle += 3) {
                    float nx = (float) Math.cos(Math.toRadians(angle)), ny = (float) Math.sin(Math.toRadians(angle));
                    assertFalse(verge.contains(7.5f * nx, 7.5f * ny), "The central island stays clear of road and shoulders");
                    assertFalse(paint.contains(22 * nx, 22 * ny), "Paint ends before entering the circulating lane");
                    for (float radius : new float[] { 11, 18, 25 }) {
                        assertTrue(shape.contains(radius * nx, radius * ny),
                              "The whole ring keeps one 15-unit circulating lane, including room around the island");
                    }
                    for (float along : new float[] { -9, 9 }) {
                        for (float across : new float[] { -5, 5 }) {
                            assertTrue(shape.contains((18 + across) * nx - along * ny, (18 + across) * ny + along * nx),
                                  "An 18-by-10 vehicle envelope can turn through the entire ring without hitting the island or verge");
                        }
                    }
                }
                for (int d = 0; d < 6; d++) {
                    Vector3 gate = gate(at, d), across = new Vector3(-gate.y, gate.x, 0).nor();
                    for (float offset : new float[] { -14, 0, 14 }) {
                        var p = new Vector3(gate).mulAdd(across, offset);
                        if ((exits & (1 << d)) != 0) {
                            assertTrue(shape.contains(p.x, p.y), "An approach reaches its border at its full width");
                            assertEquals(Math.abs(offset) - BoardRoad.Kind.PAVED.halfWidth, road.distance(p.x, p.y), .001f,
                                  "Clearance and material feathering must use the whole carriageway at a join");
                        } else if (offset == 0) {
                            assertFalse(shape.contains(p.x, p.y), "An absent approach has no invented centre-line entrance");
                        }
                    }
                }
            }
        }
        assertTrue(BoardRoad.clearance(BoardScenes.CENTER, 27).footprint(0).contains(0, 0), "Four exits still use an ordinary junction");
    }

    @Test
    void everyExitPatternReachesOnlyItsAuthoritativeSides() {
        for (int mask = 0; mask < 64; mask++) {
            int exits = mask;
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED,
                  c.equals(BoardScenes.CENTER) ? exits : 0, 0, BoardScene.Surface.GRASS));
            var road = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
            var shape = road.footprint(0);
            for (int d = 0; d < 6; d++) {
                Vector3 gate = gate(BoardScenes.CENTER, d);
                assertEquals((mask & 1 << d) != 0, shape.contains(gate.x, gate.y), "mask " + mask + ", side " + d);
            }
        }
    }

    @Test
    void connectedMaterialsMeetAcrossEveryOrientationAndColumnParity() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                int direction = d, reverse = (d + 3) % 6;
                Coords next = at.translated(d);
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                      c.equals(at) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.PAVED,
                      c.equals(at) ? 1 << direction : c.equals(next) ? 1 << reverse : 0, 0, BoardScene.Surface.GRASS));
                var first = BoardRoad.of(scene, scene.tile(at));
                var second = BoardRoad.of(scene, scene.tile(next));
                Vector3 gate = gate(at, d), normal = new Vector3(-gate.y, gate.x, 0).nor();
                for (float offset = -10; offset <= 10; offset += .5f) {
                    Vector3 a = new Vector3(gate).mulAdd(normal, offset);
                    Vector3 b = new Vector3(gate).scl(-1).mulAdd(normal, offset);
                    assertEquals(first.distance(a.x, a.y), second.distance(b.x, b.y), .002f,
                          "Changing material must not open a crack at a shared edge");
                }
            }
        }
    }

    @Test
    void deadEndsContinuePastTheCentreWithFlatCutsAndLeaveTheNextHexUnconnected() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                for (int d = 0; d < 6; d++) {
                    int exits = 1 << d;
                    var scene = BoardScenes.scene(c -> BoardScenes.tile(c, kind, exits, 0, BoardScene.Surface.GRASS));
                    var road = BoardRoad.of(scene, scene.tile(at));
                    var shape = road.footprint(0);
                    Vector3 outward = gate(at, d).scl(-1), across = new Vector3(-outward.y, outward.x, 0).nor();
                    for (float lateral : new float[] { -.9f, 0, .9f }) {
                        Vector3 inside = new Vector3(outward).scl(.78f).mulAdd(across, lateral * kind.halfWidth);
                        Vector3 outside = new Vector3(outward).scl(.82f).mulAdd(across, lateral * kind.halfWidth);
                        assertTrue(shape.contains(inside.x, inside.y), "Full width should reach close to the far edge");
                        assertTrue(road.distance(inside.x, inside.y) < 0, "Clearance must include the extended end");
                        assertFalse(shape.contains(outside.x, outside.y), "No round cap beyond the terminal plane");
                        assertTrue(road.distance(outside.x, outside.y) > 0);
                    }
                    assertFalse(road.footprint(BoardRoad.SHOULDER).contains(outward.x, outward.y),
                          "A dead end must leave ground before the opposite hex");
                }
            }
        }
    }

    @Test
    void unsealedEndsExposeTheGround() {
        for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 1, 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(BoardScenes.CENTER);
            var road = BoardRoad.of(scene, tile);
            var patches = GpuRoads.patches(tile, road);
            var tail = patches.stream().filter(p -> p.endFade() > 0 && p.fade().outer() == p.fade().inner() && p.lift() < .05f)
                  .findFirst().orElseThrow();
            Vector3 outward = gate(BoardScenes.CENTER, 0).scl(-1);
            Vector3 nearEnd = new Vector3(outward).scl(.76f);
            assertEquals(1, GpuRoads.coverage(road, tail, 0, 0));
            assertTrue(GpuRoads.coverage(road, tail, nearEnd.x, nearEnd.y) < .5f,
                  "The unsealed surface should dissolve before its final extent");
            assertEquals(0, GpuRoads.coverage(road, tail, outward.x, outward.y));
            assertTrue(patches.stream().noneMatch(p -> !p.blended() && p.shape().contains(nearEnd.x, nearEnd.y)),
                  "An opaque road underneath the fade would hide the original ground");
        }
    }

    @Test
    void aLooseRoadIsOneTrackCoatWhoseMaskCarriesItsProfileEverywhere() {
        for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 9, 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(BoardScenes.CENTER);
            var road = BoardRoad.of(scene, tile);
            var patches = GpuRoads.patches(tile, road);
            assertEquals(1, patches.size(), "The shader builds the wheel tracks, crown and shoulder of one coat");
            var coat = patches.getFirst();
            assertEquals(-5, GpuRoads.attribute(coat).value);
            // A dirt track's coat ends where its ruts' loose soil can reach; its B measures its ruts (BoardRoad#ruts), which
            // for one course runs as a gravel road's does across its whole width.
            float edge = kind == BoardRoad.Kind.DIRT ? BoardRoad.RUT_OFFSET + BoardRoad.RUT_REACH
                  : kind.halfWidth + BoardRoad.SHOULDER;
            assertTrue(coat.shape().contains(edge - .2f, 0) && !coat.shape().contains(edge + .2f, 0));
            var mask = GpuRoads.mask(road, coat);
            int heading = BoardScenes.maskTexel(mask, 0, 0) & 0xFFFF0000;
            // Linear filtering reads a texel past the coat's edge too (the atlas has no mipmaps).
            for (float x = -edge - .2f; x <= edge + .2f; x += .1f) {
                int texel = BoardScenes.maskTexel(mask, x, 0);
                assertEquals(Math.min(1, Math.abs(x) / edge), (texel >>> 8 & 255) / 255f, .03f,
                      "B runs across the whole road and the texel past it: filtering never blends a tint into it");
                assertEquals(heading, texel & 0xFFFF0000, "The heading holds across the road and the texel past its edge");
            }
            assertEquals(1, BoardScenes.maskAlpha(mask, BoardRoad.WHEEL_OFFSET, 0), .01f, "The coat itself is solid: the shader opens it");
        }
    }

    @Test
    void neighbouringLooseTracksMeetAtTheirBorderWithOneProfile() {
        // A straight road into a straight neighbour, a broad bend and a tight one; dirt, gravel, and gravel into dirt.
        for (int turn = 0; turn < 9; turn++) {
            var kind = turn < 3 ? BoardRoad.Kind.DIRT : BoardRoad.Kind.GRAVEL;
            var onwardKind = turn < 6 ? kind : BoardRoad.Kind.DIRT;
            for (int d = 0; d < 6; d++) {
                int direction = d, reverse = (d + 3) % 6, onward = (d + turn % 3) % 6;
                Coords next = BoardScenes.CENTER.translated(d);
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(next) ? onwardKind : kind,
                      c.equals(BoardScenes.CENTER) ? 1 << direction | 1 << reverse : c.equals(next) ? 1 << onward | 1 << reverse : 0,
                      0, BoardScene.Surface.GRASS));
                var here = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
                var there = BoardRoad.of(scene, scene.tile(next));
                var mine = track(GpuRoads.patches(scene.tile(BoardScenes.CENTER), here));
                var theirs = track(GpuRoads.patches(scene.tile(next), there));
                // A dirt coat's mask carries its ruts, a gravel coat's where across the road a point lies; a dirt coat
                // reaches only as far as its ruts' loose soil.
                float reach = kind == BoardRoad.Kind.DIRT || onwardKind == BoardRoad.Kind.DIRT
                      ? BoardRoad.RUT_OFFSET + BoardRoad.RUT_REACH - 1 : 14;
                float dx = (BoardGeometry.centerX(BoardScenes.CENTER) - BoardGeometry.centerX(next)) / BoardGeometry.hexScale();
                float dy = (BoardGeometry.centerY(BoardScenes.CENTER) - BoardGeometry.centerY(next)) / BoardGeometry.hexScale();
                Vector3 gate = gate(BoardScenes.CENTER, d), along = new Vector3(gate).nor(), across = new Vector3(-along.y, along.x, 0);
                for (float offset = -16; offset <= 16; offset += 2) {
                    Vector3 inside = new Vector3(gate).mulAdd(along, -.25f).mulAdd(across, offset);
                    Vector3 beyond = new Vector3(gate).mulAdd(along, .25f).mulAdd(across, offset);
                    if (kind == onwardKind) {
                        assertEquals(measure(there, kind, beyond.x + dx, beyond.y + dy), measure(here, kind, inside.x, inside.y),
                              .02f, "Both coats measure one track where they meet: turn " + turn);
                    }
                    if (Math.abs(offset) < reach) {
                        assertTrue(mine.contains(inside.x, inside.y) && theirs.contains(beyond.x + dx, beyond.y + dy),
                              "Each coat reaches the border");
                    }
                    for (float past = .5f; past <= 18; past += 2.5f) {
                        Vector3 p = new Vector3(gate).mulAdd(along, past).mulAdd(across, offset);
                        Vector3 q = new Vector3(gate).mulAdd(along, -past).mulAdd(across, offset);
                        assertFalse(mine.contains(p.x, p.y) || theirs.contains(q.x + dx, q.y + dy),
                              "Neither coat draws over the other's hex, with its own measure: turn " + turn);
                    }
                }
            }
        }
    }

    private static float measure(BoardRoad road, BoardRoad.Kind kind, float x, float y) {
        return (kind == BoardRoad.Kind.DIRT ? road.ruts(x, y) : road.track(x, y)).across();
    }

    private static Area track(List<GpuRoads.Patch> patches) {
        return patches.stream().filter(p -> GpuRoads.attribute(p).value == -5).findFirst().orElseThrow().shape();
    }

    @Test
    void colonySlabRoadsMeetOnOnePhaseOnAnyGround() {
        // A straight road into a straight neighbour, a broad bend and a tight one; the finish authored on Earth's ground.
        for (int turn = 0; turn < 3; turn++) {
            for (int d = 0; d < 6; d++) {
                int direction = d, reverse = (d + 3) % 6, onward = (d + turn) % 6;
                Coords next = BoardScenes.CENTER.translated(d);
                var scene = BoardScenes.scene(c -> slabs(BoardScenes.tile(c, BoardRoad.Kind.PAVED,
                      c.equals(BoardScenes.CENTER) ? 1 << direction | 1 << reverse : c.equals(next) ? 1 << onward | 1 << reverse : 0,
                      0, BoardScene.Surface.GRASS)));
                var here = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
                var there = BoardRoad.of(scene, scene.tile(next));
                var patches = GpuRoads.patches(scene.tile(BoardScenes.CENTER), here);
                assertTrue(patches.stream().allMatch(p -> p.texture().equals(GpuRoads.SLABS)),
                      () -> "The slabs' own maps carry kerbs, joints and lamps: no verge or paint: "
                            + patches.stream().map(GpuRoads.Patch::texture).toList());
                assertTrue(patches.stream().allMatch(p -> GpuRoads.attribute(p).value == -8));
                // Its mask carries the phase along the road (RG, a unit vector), not a heading.
                int texel = BoardScenes.maskTexel(GpuRoads.mask(here, patches.getFirst()), 0, 0);
                double angle = Math.atan2(((texel >>> 16 & 255) - 128) / 127.0, ((texel >>> 24 & 255) - 128) / 127.0);
                assertEquals(0, Math.IEEEremainder(angle / (2 * Math.PI) - here.slab(0, 0)[0], 1), .02);
                float dx = (BoardGeometry.centerX(BoardScenes.CENTER) - BoardGeometry.centerX(next)) / BoardGeometry.hexScale();
                float dy = (BoardGeometry.centerY(BoardScenes.CENTER) - BoardGeometry.centerY(next)) / BoardGeometry.hexScale();
                Vector3 gate = gate(BoardScenes.CENTER, d), along = new Vector3(gate).nor(), across = new Vector3(-along.y, along.x, 0);
                for (float offset = -14; offset <= 14; offset += 2) {
                    Vector3 inside = new Vector3(gate).mulAdd(along, -.05f).mulAdd(across, offset);
                    Vector3 beyond = new Vector3(gate).mulAdd(along, .05f).mulAdd(across, offset);
                    float[] mine = here.slab(inside.x, inside.y), theirs = there.slab(beyond.x + dx, beyond.y + dy);
                    for (float phase : new float[] { mine[0], theirs[0] }) {
                        assertEquals(0, phase - Math.round(phase), .01f, "Whole periods end on the border: turn " + turn);
                    }
                    assertEquals(Math.abs(mine[1]), Math.abs(theirs[1]), .02f,
                          "Both hexes measure one place across the road: turn " + turn);
                }
            }
        }
        // The finish is the map's choice (a legacy lunar or Martian road is given it when converted): unauthored, a paved
        // road is asphalt on any ground.
        var mars = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.MARS));
        assertFalse(GpuRoads.slabs(mars.tile(BoardScenes.CENTER), false));
    }

    @Test
    void aLooseRoadRunsOnBrieflyOverThePavedRoadItMeetsAndLeavesTheGroundBetweenItsArms() {
        Coords north = BoardScenes.CENTER.translated(0), northEast = BoardScenes.CENTER.translated(1);
        for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
            // Hex 1303 of Nebraska Farmhouse: two arms into one paved road, which runs on past them.
            var scene = BoardScenes.scene(c -> c.equals(BoardScenes.CENTER) ? BoardScenes.tile(c, kind, 1 | 2 | 8, 0, BoardScene.Surface.GRASS)
                  : c.equals(north) ? BoardScenes.tile(c, BoardRoad.Kind.PAVED, 8 | 4 | 32, 0, BoardScene.Surface.GRASS)
                  : c.equals(northEast) ? BoardScenes.tile(c, BoardRoad.Kind.PAVED, 16 | 32 | 4, 0, BoardScene.Surface.GRASS)
                  : BoardScenes.tile(c, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS));
            var loose = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
            for (Coords at : List.of(north, northEast)) {
                var paved = BoardRoad.of(scene, scene.tile(at));
                var pavedPatches = GpuRoads.patches(scene.tile(at), paved);
                assertTrue(pavedPatches.stream().noneMatch(p -> p.fade() != null && p.fade().join() != null),
                      "The paved road stays whole, under nothing of the loose one's");
                // Its arm into the loose hex ends square on their border: a construction edge, not a rounded tongue.
                int back = (at.equals(north) ? 0 : 1) + 3;
                Vector3 border = gate(at, back % 6), across = new Vector3(-border.y, border.x, 0).nor();
                for (float offset : new float[] { -10, 0, 10 }) {
                    Vector3 before = new Vector3(border).scl(.97f).mulAdd(across, offset);
                    Vector3 past = new Vector3(border).scl(1.03f).mulAdd(across, offset);
                    assertTrue(pavedPatches.stream().anyMatch(p -> p.shape().contains(before.x, before.y)));
                    assertTrue(pavedPatches.stream().noneMatch(p -> p.shape().contains(past.x, past.y)));
                }
            }
            var patches = GpuRoads.patches(scene.tile(BoardScenes.CENTER), loose);
            Area way = loose.pavedCarriageway(0), deep = loose.pavedCarriageway(0), farther = loose.pavedCarriageway(0);
            deep.subtract(loose.pastMeetings(1.05f * GpuRoads.runOn(), 128));
            farther.subtract(loose.pastMeetings(1.05f * GpuRoads.carry(), 128));
            Area paint = new Area();
            for (var patch : patches) {
                Area shape = new Area(patch.shape());
                // Only its faint grit reaches farther, along the paved road's wheel paths (GpuRoads#carry).
                shape.intersect(patch.fade() != null && patch.fade().carried() ? farther : deep);
                // Rectangles sharing the border's edge leave rounding slivers: only covered ground counts.
                assertFalse(covers(shape), "Only its run-on and grit reach onto the paved road: " + patch.texture());
                paint.add(patch.shape());
            }
            // The gore between the arms, out to the corner the three hexes share, stays ground.
            Vector3 corner = new Vector3(gate(BoardScenes.CENTER, 0)).add(gate(BoardScenes.CENTER, 1)).scl(.5f);
            for (float t = 1.15f; t <= 1.3f; t += .05f) {
                Vector3 p = new Vector3(corner).scl(t);
                assertFalse(paint.contains(p.x, p.y) && !way.contains(p.x, p.y), "Loose paint in the gore at " + t);
            }
        }
    }

    @Test
    void isolatedRoadHasTwoProperEndsWithoutCreatingAnyExits() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.DIRT, 0, 0, BoardScene.Surface.GRASS));
        var road = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
        var shape = road.footprint(0);
        assertTrue(shape.contains(0, -20) && shape.contains(0, 20));
        assertFalse(shape.contains(0, -32) || shape.contains(0, 32));
        assertTrue(road.markings(BoardScenes.CENTER, 0).isEmpty());
        for (int d = 0; d < 6; d++) {
            Vector3 gate = gate(BoardScenes.CENTER, d);
            assertFalse(shape.contains(gate.x, gate.y));
        }
    }

    @Test
    void gravelSpreadsOnOverTheDirtRoadItJoinsAndEachDrawsItsOwnTrack() {
        for (int d = 0; d < 6; d++) {
            int direction = d, reverse = (d + 3) % 6;
            Coords next = BoardScenes.CENTER.translated(d);
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(BoardScenes.CENTER) ? BoardRoad.Kind.DIRT : BoardRoad.Kind.GRAVEL,
                  c.equals(BoardScenes.CENTER) ? 1 << direction : 1 << reverse, 0, BoardScene.Surface.GRASS));
            Vector3 gate = gate(BoardScenes.CENTER, d), along = new Vector3(gate).nor(), across = new Vector3(-along.y, along.x, 0);
            var dirt = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
            var gravel = BoardRoad.of(scene, scene.tile(next));
            var dirtPatches = GpuRoads.patches(scene.tile(BoardScenes.CENTER), dirt);
            var gravelPatches = GpuRoads.patches(scene.tile(next), gravel);
            assertEquals(1, gravelPatches.size(), "The gravel draws its own track only, up to the border");
            assertEquals(2, dirtPatches.size(), "The dirt draws its track, and the gravel spread over it");
            var spread = dirtPatches.stream().filter(p -> p.texture().equals("roads/gravel")).findFirst().orElseThrow();
            assertEquals(-5, GpuRoads.attribute(spread).value, "The gravel spreads on as a track, not a transition coat");
            float dx = (BoardGeometry.centerX(BoardScenes.CENTER) - BoardGeometry.centerX(next)) / BoardGeometry.hexScale();
            float dy = (BoardGeometry.centerY(BoardScenes.CENTER) - BoardGeometry.centerY(next)) / BoardGeometry.hexScale();
            for (float offset = -12; offset <= 12; offset += 4) {
                Vector3 p = new Vector3(gate).mulAdd(along, -.25f).mulAdd(across, offset);
                assertEquals(1, GpuRoads.coverage(dirt, spread, p.x, p.y), .04f, "As whole at the border as the gravel's own");
                assertTrue(gravelPatches.getFirst().shape().contains(p.x + dx + along.x * .5f, p.y + dy + along.y * .5f));
                Vector3 deep = new Vector3(gate).mulAdd(along, -BoardRoad.JOIN_REACH - 1).mulAdd(across, offset);
                assertFalse(spread.shape().contains(deep.x, deep.y), "The spread thins out within the join's reach");
                assertEquals(gravel.track(p.x + dx + along.x * .5f, p.y + dy + along.y * .5f).across(),
                      dirt.track(p.x, p.y).across(), .02f, "Both measure one track across the join");
            }
        }
    }

    @Test
    void wheelSpacingStaysConstantThroughMaterialChangesAndRoadEndFades() {
        for (int d = 0; d < 6; d++) {
            int direction = d;
            Coords neighbor = BoardScenes.CENTER.translated(d);
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(neighbor) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.DIRT,
                  c.equals(neighbor) ? 1 << ((direction + 3) % 6) : 1 << direction, 0, BoardScene.Surface.GRASS));
            var road = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
            Vector3 along = gate(BoardScenes.CENTER, d).nor(), across = new Vector3(-along.y, along.x, 0);
            for (float progress = -23; progress <= 34; progress += 3) {
                for (int lane : new int[] { -1, 1 }) {
                    for (int side : new int[] { -1, 1 }) {
                        Vector3 centre = new Vector3(along).scl(progress).mulAdd(across,
                              lane * BoardRoad.Kind.DIRT.halfWidth / 2 + side * BoardRoad.WHEEL_OFFSET);
                        var heading = road.wheelDirection(centre.x, centre.y);
                        assertEquals(1, heading.len(), .001f);
                        assertEquals(1, Math.abs(heading.x * along.x + heading.y * along.y), .001f,
                              "Compacted scuffs follow the road, including reversed paths");

                    }
                }
            }
        }
    }

    @Test
    void mixedMaterialsAgreeAtTheirSharedEdgeInEveryDirection() {
        for (var pair : List.of(List.of(BoardRoad.Kind.ALLEY, BoardRoad.Kind.GRAVEL),
              List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.PAVED))) {
            for (int d = 0; d < 6; d++) {
                int direction = d, reverse = (d + 3) % 6;
                Coords next = BoardScenes.CENTER.translated(d);
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                      c.equals(BoardScenes.CENTER) ? pair.getFirst() : pair.getLast(),
                      c.equals(BoardScenes.CENTER) ? 1 << direction : 1 << reverse, 0, BoardScene.Surface.GRASS));
                var first = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
                var second = BoardRoad.of(scene, scene.tile(next));
                Vector3 gate = gate(BoardScenes.CENTER, d), across = new Vector3(-gate.y, gate.x, 0).nor();
                var a = first.joins().getFirst();
                var b = second.joins().getFirst();
                for (float offset = -4; offset <= 4; offset++) {
                    Vector3 p = new Vector3(gate).mulAdd(across, offset);
                    Vector3 q = new Vector3(gate).scl(-1).mulAdd(across, offset);
                    assertEquals(.5f, a.coverage(p.x, p.y), .001f);
                    assertEquals(.5f, b.coverage(q.x, q.y), .001f);
                }
                Vector3 along = new Vector3(gate).nor();
                for (float progress = -20; progress < 35; progress += 2) {
                    Vector3 edgePoint = new Vector3(along).scl(progress).mulAdd(across, BoardRoad.Kind.PAVED.halfWidth);
                    assertEquals(0, first.distance(edgePoint.x, edgePoint.y), .001f,
                          "The material transition must not squeeze the carriageway");
                }
            }
        }
    }

    @Test
    void junctionPaintFollowsThePavedRouteAndDoesNotSpillOntoAnAlley() {
        Coords alley = BoardScenes.CENTER.translated(2);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(alley) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.PAVED,
              c.equals(BoardScenes.CENTER) ? 13 : c.equals(alley) ? 32 : 9, 0, BoardScene.Surface.GRASS));
        var road = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
        var paint = road.markings(BoardScenes.CENTER, 13);
        Vector3 branch = gate(BoardScenes.CENTER, 2).nor();
        for (float at = 14; at < 36; at += .5f) {
            assertFalse(paint.contains(branch.x * at, branch.y * at), "No stranded lane dashes on an unmarked branch");
        }
        var straightScene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        var straight = BoardRoad.of(straightScene, straightScene.tile(BoardScenes.CENTER)).markings(BoardScenes.CENTER, 9);
        // Priority affects stop bars, not the clear space needed for turns across the divider.
        for (float y = -8; y <= 8; y += .25f) {
            assertFalse(paint.contains(0, y), "The junction leaves the through divider clear for turning traffic");
        }
        for (float y = 31; y <= 34; y += .25f) {
            assertTrue(paint.contains(0, y) && paint.contains(0, -y), "Both priority approaches channel traffic with solid lines");
        }
        assertFalse(straight.contains(0, 35), "A separate straight road retains the gap at its border");
    }

    @Test
    void allRoadLayersLieOnTheExistingRampAndDoNotChangeSupport() {
        for (int d = 0; d < 6; d++) {
            int direction = d;
            Coords next = BoardScenes.CENTER.translated(d);
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED,
                  c.equals(BoardScenes.CENTER) ? 1 << direction : c.equals(next) ? 1 << ((direction + 3) % 6) : 0,
                  c.equals(BoardScenes.CENTER) ? 2 : 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(BoardScenes.CENTER);
            var surface = new BoardSurface(scene, tile);
            var road = BoardRoad.of(scene, tile);
            int triangles = 0;
            for (var patch : GpuRoads.patches(tile, road)) {
                for (var triangle : GpuRoads.drape(tile, surface, patch)) {
                    Vector3 p = new Vector3(triangle.a()).add(triangle.b()).add(triangle.c()).scl(1f / 3);
                    // A splat borrows whole faces; fragments outside its mask never appear.
                    if (!patch.shape().contains((p.x - BoardGeometry.centerX(BoardScenes.CENTER)) / BoardGeometry.hexScale(),
                          (p.y - BoardGeometry.centerY(BoardScenes.CENTER)) / BoardGeometry.hexScale())) { continue; }
                    // Rounding the centroid's XY can change its height on a steep, narrow triangle. Compare both
                    // planes at that same point instead of comparing the sampled ground to averaged vertex Z.
                    float painted = new BoardSurface.Face(triangle.a(), triangle.b(), triangle.c(), BoardSurface.Finish.TOP)
                          .height(p.x, p.y);
                    assertEquals(surface.height(p.x, p.y) + patch.lift() * BoardGeometry.hexScale(), painted, .002f,
                          "Carrier " + triangle + " direction " + direction + " patch " + patch.texture());
                    triangles++;
                }
            }
            assertTrue(triangles > 10);
            Vector3 shared = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
            assertEquals(BoardGeometry.level(), surface.height(shared.x, shared.y), .01f);
        }
    }

    @Test
    void flatRoadsKeepTheHexMeshAndUseMasksForEdgesAndMarkings() {
        for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 9, 0, BoardScene.Surface.GRASS));
            var tile = scene.tile(BoardScenes.CENTER);
            var surface = new BoardSurface(scene, tile);
            var road = BoardRoad.of(scene, tile);
            assertEquals(6, surface.faces.size(), "A flat road must not subdivide the terrain");
            float inside = 0, outside = 0;
            for (var patch : GpuRoads.patches(tile, road)) {
                assertTrue(GpuRoads.drape(tile, surface, patch).size() <= surface.faces.size(),
                      "A dash, shoulder or tyre mark must not split terrain triangles");
                var mask = GpuRoads.mask(road, patch);
                float half = kind == BoardRoad.Kind.DIRT ? BoardRoad.RUT_OFFSET + BoardRoad.RUT_REACH : kind.halfWidth;
                inside = Math.max(inside, BoardScenes.maskAlpha(mask, half - 1.5f, 8));
                outside = Math.max(outside, BoardScenes.maskAlpha(mask, half + 3.5f, 8));
            }
            assertTrue(inside > .4f, "The mask preserves the road's width for " + kind);
            assertEquals(0, outside, "Borrowing terrain faces must not paint the surrounding hex");
        }
    }

    @Test
    void repeatedRoadsShareMaterialsWithoutSharingWorldCoordinates() {
        Texture texture = mock(Texture.class);
        when(texture.getWidth()).thenReturn(512);
        when(texture.getHeight()).thenReturn(512);
        var region = new TextureRegion(texture, 16, 32, 128, 96);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        var road = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
        var patch = GpuRoads.patches(scene.tile(BoardScenes.CENTER), road).stream()
              .filter(p -> p.texture().equals("roads/asphalt") && p.lift() == GpuRoads.SURFACE_LIFT).findFirst().orElseThrow();
        var data = GpuRoads.mask(road, patch);
        var masks = new GpuRoads.Masks();
        assertSame(data, masks.share(data));
        assertSame(data, masks.share(GpuRoads.mask(road, patch)), "Identical roads in different chunks share mask storage");
        var first = new Material("surface", new GpuRoads.Mask(region, data));
        assertEquals(first, new Material("surface", new GpuRoads.Mask(region, data)), "Repeated roads share one material batch");
        assertEquals(first, new Material(first), "Chunk rebuilds preserve the atlas region and mask coordinates");
        assertEquals(first.hashCode(), new Material(first).hashCode());
        var moved = new Material("surface", new GpuRoads.Mask(new TextureRegion(texture, 160, 200, 128, 96), data));
        assertNotEquals(first, moved, "Edit metadata retains each mask's placement");
        assertEquals(GpuRoads.batchMaterial(first), GpuRoads.batchMaterial(moved), "Regions on one page share a draw");
        assertEquals(GpuRoads.batchMaterial(first).hashCode(), GpuRoads.batchMaterial(moved).hashCode());
        float[] previous = null;
        for (var coords : List.of(BoardScenes.CENTER, BoardScenes.CENTER.translated(0))) {
            var tile = scene.tile(coords);
            var surface = new BoardSurface(scene, tile);
            var mesh = new MeshBuilder();
            mesh.begin(GpuRoads.VERTICES, GL20.GL_TRIANGLES);
            var triangles = GpuRoads.drape(tile, surface, patch);
            GpuRoads.write(() -> mesh, triangles, patch, new GpuRoads.Mask(region, data), surface, tile.coords(), false,
                  Color.WHITE.toFloatBits(), 1, false, null);
            assertEquals(triangles.size() * 3, mesh.getNumIndices(), "Mask UVs do not require additional geometry");
            int stride = mesh.getAttributes().vertexSize / Float.BYTES;
            int maskOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.Generic).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices, 0);
            if (previous != null) {
                assertEquals(previous.length, vertices.length);
                assertNotEquals(previous[1], vertices[1], "The two carriers occupy different hexes");
                for (int i = 0; i < vertices.length; i += stride) {
                    assertEquals(previous[i + maskOffset], vertices[i + maskOffset], .00001f);
                    assertEquals(previous[i + maskOffset + 1], vertices[i + maskOffset + 1], .00001f,
                          "A shared atlas slot still maps the road onto each hex's own terrain");
                }
            }
            previous = vertices;
        }
    }

    @Test
    void allCoatsOnAMaskPageShareOneDrawAndCarryTheirOwnAppearanceInTheirVertices() {
        Texture page = mock(Texture.class);
        when(page.getWidth()).thenReturn(512);
        when(page.getHeight()).thenReturn(512);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
        var road = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
        var maps = new GpuAssets.MaterialArrays(mock(TextureArray.class), mock(TextureArray.class));
        var coats = new GpuRoads.Coats(maps,
              new GpuAssets.MaterialArrays(mock(TextureArray.class), mock(TextureArray.class)));
        var patches = GpuRoads.patches(scene.tile(BoardScenes.CENTER), road);
        List<Material> batches = new ArrayList<>();
        for (int i = 0; i < patches.size(); i++) {
            var mask = new GpuRoads.Mask(new TextureRegion(page, 64 * i, 0, 64, 64), GpuRoads.mask(road, patches.get(i)));
            batches.add(GpuRoads.batchMaterial(new Material("surface", coats, GpuRoads.attribute(patches.get(i)), mask)));
        }
        assertTrue(patches.stream().map(GpuRoads::attribute).distinct().count() > 2, "A paved road has several distinct coats");
        assertEquals(1, batches.stream().distinct().count(), "One draw holds every coat on a mask page");
        var deckTrack = new GpuRoads.Patch(new Area(), "roads/dirt", Color.WHITE, 1, GpuRoads.SURFACE_LIFT,
              GpuRoads.Fade.track(0, true));
        assertEquals(-6, GpuRoads.attribute(deckTrack).value);
        for (var patch : Stream.concat(patches.stream(), Stream.of(deckTrack)).toList()) {
            // The shader reads back exact bytes; the packed colour only loses alpha's lowest bit.
            var color = new Color();
            Color.abgr8888ToColor(color, GpuRoads.coat(4, patch, .15f));
            assertEquals(4, Math.round(color.r * 255));
            assertEquals(GpuRoads.attribute(patch).value + 8, Math.round(color.g * 255));
            assertEquals(38, Math.round(color.b * 255));
        }
    }

    @Test
    void roadKindsUseEngineGroundButUnknownArtworkAndSpecialCombinationsRemainVisible() {
        Hex hex = new Hex(0);
        for (int level = 1; level <= 4; level++) {
            hex.addTerrain(new Terrain(Terrains.ROAD, level, true, 9));
            assertNotEquals(BoardRoad.Kind.NONE, BoardRoad.capture(hex));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            hex.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 1));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "Standard bends use the native road and ground");
            for (int fluff : new int[] { 2, 3 }) {
                hex.addTerrain(new Terrain(Terrains.ROAD_FLUFF, fluff));
                assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Preserve special road artwork " + fluff);
            }
            hex.removeTerrain(Terrains.ROAD_FLUFF);
            hex.addTerrain(new Terrain(Terrains.RUBBLE, 1));
            assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Preserve the unsupported combination with rubble");
            hex.removeTerrain(Terrains.RUBBLE);
            hex.addTerrain(new Terrain(Terrains.WATER, 0));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "A road through water lies on its bed");
            hex.removeTerrain(Terrains.WATER);
            hex.addTerrain(new Terrain(Terrains.ICE, 1));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "The native ice material supports roads on dry ground");
            hex.removeTerrain(Terrains.ICE);
        }
        hex.addTerrain(new Terrain(Terrains.ROAD, 17, true, 9));
        assertEquals(BoardRoad.Kind.PAVED, BoardRoad.capture(hex));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        hex.removeTerrain(Terrains.ROAD);
        hex.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 1));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Standalone artwork is not a captured road");
    }

    @Test
    void changingRoadKindInvalidatesGeometryAndSurroundingGroundStillBlends() {
        var before = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              c.equals(BoardScenes.CENTER) ? 9 : 0, 0, c.getX() < 5 ? BoardScene.Surface.GRASS : BoardScene.Surface.SAND));
        var after = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardRoad.Kind.ALLEY : BoardRoad.Kind.NONE,
              c.equals(BoardScenes.CENTER) ? 9 : 0, 0, c.getX() < 5 ? BoardScene.Surface.GRASS : BoardScene.Surface.SAND));
        assertFalse(before.tile(BoardScenes.CENTER).sameGeometry(after.tile(BoardScenes.CENTER)));
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(BoardScenes.CENTER)), BoardSurface.geometryKey(after, after.tile(BoardScenes.CENTER)));
        Vector3 edge = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(BoardScenes.CENTER.translated(1), 0), .5f);
        var cover = BoardSurfaceBlend.sample(before, before.tile(BoardScenes.CENTER), edge.x, edge.y, edge.z);
        assertTrue(cover.grass() > .1f && cover.sand() > .1f, "Natural verges still blend across the road tile's border");
    }

    @Test
    void plainRoadsRunStraightThroughAZigzagOfHexesAndBothSidesAgreeOnEveryCrossing() {
        // A road running east on this lattice alternates north-east and south-east exits.
        Map<Coords, Integer> chain = Map.of(new Coords(2, 4), 18, new Coords(3, 3), 20, new Coords(4, 4), 34,
              new Coords(5, 3), 20, new Coords(6, 4), 34);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, chain.containsKey(c) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              chain.getOrDefault(c, 0), 0, BoardScene.Surface.GRASS));
        int crossings = 0;
        for (var at : chain.keySet()) {
            var bends = BoardRoad.bends(at, c -> BoardRoad.Node.of(scene.tile(c)));
            for (int d = 0; d < 6; d++) {
                var next = at.translated(d);
                if (!chain.containsKey(next) || (chain.get(at) & 1 << d) == 0) { continue; }
                var out = bends.apply(d);
                var in = BoardRoad.bends(next, c -> BoardRoad.Node.of(scene.tile(c))).apply((d + 3) % 6);
                assertTrue(out != null && in != null, "Plain roads at one level bend through their shared border");
                assertTrue(out.epsilonEquals(new Vector2(in).scl(-1), 1e-5f),
                      "Both hexes cross their border along one direction: " + out + " / " + in);
                int before = Integer.numberOfTrailingZeros(chain.get(at) & ~(1 << d));
                int after = Integer.numberOfTrailingZeros(chain.get(next) & ~(1 << (d + 3) % 6));
                if (chain.containsKey(at.translated(before)) && chain.containsKey(next.translated(after))) {
                    assertEquals(0, out.y, 1e-4f, "An eastward zigzag of hexes is one straight road");
                }
                crossings++;
            }
        }
        assertEquals(8, crossings);
    }

    @Test
    void oppositeExitsStayStraightBesideTurnsAndGradesInEveryOrientation() {
        for (int x : new int[] { 3, 4 }) {
            Coords at = new Coords(x, 4);
            for (int axis = 0; axis < 3; axis++) {
                int forward = axis, reverse = axis + 3;
                Coords before = at.translated(forward), after = at.translated(reverse);
                for (boolean graded : new boolean[] { false, true }) {
                    var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardRoad.Kind.PAVED,
                          c.equals(at) ? (1 << forward) | (1 << reverse)
                                : c.equals(before) ? (1 << reverse) | (1 << ((forward + 1) % 6))
                                : c.equals(after) ? (1 << forward) | (1 << ((reverse + 1) % 6)) : 0,
                          graded && c.equals(after) ? 2 : 3, BoardScene.Surface.GRASS));
                    var road = BoardRoad.of(scene, scene.tile(at));
                    Vector3 gate = gate(at, forward), across = new Vector3(-gate.y, gate.x, 0).nor();
                    for (float along = -1; along <= 1; along += .125f) {
                        for (float offset : new float[] { -8, -4, 0, 4, 8 }) {
                            Vector3 point = new Vector3(gate).scl(along).mulAdd(across, offset);
                            assertEquals(Math.abs(offset) - BoardRoad.Kind.PAVED.halfWidth,
                                  road.distance(point.x, point.y), .002f,
                                  "Opposite exits must keep their straight centre and width beside another hex's turn");
                            assertEquals(1, Math.abs(road.wheelDirection(point.x, point.y).dot(
                                  new Vector2(gate.x, gate.y).nor())), .0001f);
                        }
                    }
                    for (int d : new int[] { forward, reverse }) {
                        var neighbor = at.translated(d);
                        var out = BoardRoad.bends(at, c -> BoardRoad.Node.of(scene.tile(c))).apply(d);
                        var in = BoardRoad.bends(neighbor, c -> BoardRoad.Node.of(scene.tile(c))).apply((d + 3) % 6);
                        if (graded && d == reverse) {
                            assertTrue(out == null && in == null, "A grade retains both straight ramp corridors");
                        } else {
                            assertTrue(out != null && in != null, "A level turn may approach from the whole neighbouring hex");
                            assertTrue(out.epsilonEquals(new Vector2(in).scl(-1), .00001f),
                                  "Both road halves must retain one shared tangent");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theLastHexBeforeASquareCrossingTurnsIntoItsCorridorAlongOneWideArc() {
        // As before the Fire and Ice 2 bridge: a plain road turns in its last hex towards a crossing that stays square,
        // here a junction. Its turn must not be left until the mouth of that crossing's straight corridor.
        Map<Coords, Integer> chain = Map.of(new Coords(2, 4), 18, new Coords(3, 3), 20, new Coords(4, 4), 34,
              new Coords(5, 3), 21);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, chain.containsKey(c) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              chain.getOrDefault(c, 0), 0, BoardScene.Surface.GRASS));
        var at = new Coords(4, 4);
        var road = BoardRoad.of(scene, scene.tile(at));
        float west = gate(at, 5).x + 1, east = gate(at, 1).x - 1, apothem = gate(at, 0).len();
        int samples = (int) (east - west);
        float[] y = new float[samples + 1];
        for (int i = 0; i <= samples; i++) {
            // The centre line crosses each vertical line once; the edge distance is smallest on it.
            float x = west + i, low = -apothem, high = apothem;
            for (int step = 0; step < 60; step++) {
                float a = high - .618f * (high - low), b = low + .618f * (high - low);
                if (road.distance(x, a) < road.distance(x, b)) { high = b; } else { low = a; }
            }
            y[i] = (low + high) / 2;
        }
        float sharpest = Float.POSITIVE_INFINITY;
        int window = 8;
        for (int i = 0; i + window < samples; i++) {
            float turn = Math.abs((float) (Math.atan2(y[i + window + 1] - y[i + window], 1) - Math.atan2(y[i + 1] - y[i], 1)));
            float length = 0;
            for (int j = i; j < i + window; j++) { length += (float) Math.hypot(1, y[j + 1] - y[j]); }
            sharpest = Math.min(sharpest, length / Math.max(turn, 1e-6f));
        }
        assertTrue(sharpest > apothem / 2, "The road turns along a wide arc, not a kink: radius " + sharpest);
    }

    /** Whether an area covers any point of a half-pixel grid over its bounds: more than a rounding sliver. */
    private static boolean covers(Area area) {
        var bounds = area.getBounds2D();
        for (double x = Math.floor(bounds.getMinX()); x <= bounds.getMaxX(); x += .5) {
            for (double y = Math.floor(bounds.getMinY()); y <= bounds.getMaxY(); y += .5) {
                if (area.contains(x, y)) { return true; }
            }
        }
        return false;
    }

    private static Vector3 gate(Coords at, int d) {
        return BoardGeometry.center(at.translated(d), 0).sub(BoardGeometry.center(at, 0)).scl(.5f / BoardGeometry.hexScale());
    }
}
