/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardSurfaceBlendTest {
    private static final BoardScene.Pixels PIXELS = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));

    @Test
    void everyFamilyMeetsTheReceivingNaturalGroundAtItsCliffFootInEveryDirection() {
        for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
            if (family == BoardScene.Surface.CONCRETE.ordinal()) { continue; }
            int upper = family, lower = (family + 3) % BoardScene.Surface.values().length;
            if (lower == BoardScene.Surface.CONCRETE.ordinal()) { lower = BoardScene.Surface.ROCK.ordinal(); }
            int receiving = lower;
            for (int direction = 0; direction < 6; direction++) {
                var next = BoardScenes.CENTER.translated(direction);
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? upper : receiving, c.equals(BoardScenes.CENTER) ? 4 : 0));
                var high = scene.tile(BoardScenes.CENTER);
                var low = scene.tile(next);
                var p = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
                p.z = BoardGeometry.groundZ(low);
                var foot = BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z);
                assertEquals(foot, BoardSurfaceBlend.sample(scene, low, p.x, p.y, p.z), "Shared foot, family " + family);
                assertTrue(foot.weight(upper) > .05f && foot.weight(lower) > .05f, foot.toString());
                p.z += BoardRelief.metres(BoardSurfaceBlend.FOOT_METRES + .1f);
                var above = BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z);
                assertEquals(0, above.weight(lower), "Receiving terrain stays near the foot");
                assertEquals(1, above.weight(upper), .00001f);
            }
        }
    }

    @Test
    void lavaMeltsSnowAndBurnsTurfAtTheFootOfTheirCliffs() {
        for (var hill : new BoardScene.Surface[] { BoardScene.Surface.SNOW, BoardScene.Surface.GRASS }) {
            int upper = hill.ordinal(), melted = BoardSurfaceBlend.melted(upper);
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? upper : BoardSurfaceBlend.BANK, c.equals(BoardScenes.CENTER) ? 2 : 0));
            var high = scene.tile(BoardScenes.CENTER);
            var next = BoardScenes.CENTER.translated(0);
            var foot = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
            foot.z = BoardGeometry.groundZ(scene.tile(next));
            var cover = BoardSurfaceBlend.sampleCliff(scene, high, foot.x, foot.y, foot.z);
            assertEquals(0, cover.weight(upper), .00001f, hill + " at the lava's edge");
            assertTrue(cover.weight(melted) > .05f, cover.toString());
            // A hex's width away the heat is gone.
            var top = BoardGeometry.center(BoardScenes.CENTER, 0);
            top.z = BoardGeometry.groundZ(high);
            assertEquals(BoardSurfaceBlend.solid(upper), BoardSurfaceBlend.sample(scene, high, top.x, top.y, top.z));
        }
    }

    @Test
    void naturalCliffsDoNotSpillTheirCoverAcrossLowerPavement() {
        for (int rise : new int[] { 1, 2, 3, 4, 8 }) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND,
                  c.equals(BoardScenes.CENTER) ? 0 : rise, -1, 0));
            var pavement = scene.tile(BoardScenes.CENTER);
            assertFalse(BoardSurfaceBlend.boundary(scene, pavement));
            for (int edge = 0; edge < 6; edge++) {
                var point = BoardGeometry.corner(BoardScenes.CENTER, 0, edge);
                assertEquals(BoardSurfaceBlend.solid(BoardScene.Surface.CONCRETE),
                      BoardSurfaceBlend.sample(scene, pavement, point.x, point.y, point.z));
            }
        }
    }

    @Test
    void concreteKeepsItsMaterialAtEveryHeight() {
        for (int level = 0; level <= 4; level++) {
            int rise = level;
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.getY() < 4 ? BoardScene.Surface.CONCRETE.ordinal()
                  : BoardScene.Surface.DIRT.ordinal(), c.getY() < 4 ? rise : 0));
            var high = scene.tile(new Coords(4, 3));
            var low = scene.tile(new Coords(4, 4));
            assertFalse(BoardSurfaceBlend.boundary(scene, high));
            assertFalse(BoardSurfaceBlend.boundary(scene, low));
            var p = BoardGeometry.center(high.coords(), 0).lerp(BoardGeometry.center(low.coords(), 0), .5f);
            assertEquals(BoardSurfaceBlend.solid(high.surface()), BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z));
            assertEquals(BoardSurfaceBlend.solid(low.surface()), BoardSurfaceBlend.sample(scene, low, p.x, p.y, p.z));
        }
    }

    @Test
    void tallConcreteKeepsACleanMaterialEdgeAtItsFittedFootAtEveryDetail() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.getY() < 4 ? BoardScene.Surface.CONCRETE.ordinal()
              : BoardScene.Surface.DIRT.ordinal(), c.getY() < 4 ? 4 : 0));
        int checked = 0;
        for (var lod : TerrainLod.values()) {
            for (int x = 2; x <= 6; x++) {
                var low = scene.tile(new Coords(x, 4));
                var surface = new BoardSurface(scene, low, lod);
                for (int edge = 0; edge < 6; edge++) {
                    var high = scene.tile(low.coords().translated(BoardGeometry.edgeDirection(edge)));
                    if (high == null || high.elevation() != 4) { continue; }
                    for (float t : new float[] { .1f, .35f, .6f, .9f }) {
                        var p = surface.relief.seam(edge, edge, t);
                        var cover = BoardSurfaceBlend.sample(scene, low, p.x, p.y, p.z);
                        assertEquals(BoardSurfaceBlend.solid(BoardScene.Surface.DIRT), cover);
                        assertEquals(BoardSurfaceBlend.solid(BoardScene.Surface.CONCRETE),
                              BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z), "Concrete reaches the foot");
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked >= 60);
    }

    @Test
    void lunarAndRockKeepDistinctCoverageWithoutBecomingVolcanic() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardScene.Surface.LUNAR : BoardScene.Surface.ROCK, 0, -1, 0));
        var lunar = scene.tile(BoardScenes.CENTER);
        var rock = scene.tile(BoardScenes.CENTER.translated(2));
        assertTrue(BoardSurfaceBlend.boundary(scene, lunar));
        assertTrue(BoardSurfaceBlend.boundary(scene, rock));
        assertFalse(new GpuSurfaceBlend.Palette(BoardScene.Surface.LUNAR, BoardScene.Surface.ROCK,
              BoardScene.Surface.SAND).volcanic());
        var center = BoardGeometry.center(BoardScenes.CENTER, 0);
        assertEquals(1, BoardSurfaceBlend.sample(scene, lunar, center.x, center.y, center.z).lunar());
        var edge = center.lerp(BoardGeometry.center(rock.coords(), 0), .5f);
        var cover = BoardSurfaceBlend.sample(scene, lunar, edge.x, edge.y, edge.z);
        assertEquals(cover, BoardSurfaceBlend.sample(scene, rock, edge.x, edge.y, edge.z));
        assertTrue(cover.lunar() > .05f && cover.rock() > .05f, cover.toString());
        assertEquals(0, cover.crust());
        assertEquals(0, cover.bank());
    }

    @Test
    void volcanicBoundariesShareCoverageOnLevelGround() {
        for (int family : new int[] { BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK }) {
            for (var host : new BoardScene.Surface[] { BoardScene.Surface.ROCK, BoardScene.Surface.LUNAR }) {
                var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? family : host.ordinal(), 0));
                var next = BoardScenes.CENTER.translated(2);
                var p = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
                var cover = BoardSurfaceBlend.sample(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z);
                assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(next), p.x, p.y, p.z));
                assertTrue(cover.weight(host) > .05f && cover.weight(family) > .05f, cover.toString());
                assertTrue(BoardSurfaceBlend.boundary(scene, scene.tile(BoardScenes.CENTER)));
            }
        }
    }

    @Test
    void bothSidesAgreeInAllDirectionsAndTileCentresKeepTheirIdentity() {
        for (int direction = 0; direction < 6; direction++) {
            Coords next = BoardScenes.CENTER.translated(direction);
            BoardScene scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardScene.Surface.SNOW : BoardScene.Surface.GRASS, 0, -1, 0));
            Vector3 a = BoardGeometry.center(BoardScenes.CENTER, 0), b = BoardGeometry.center(next, 0);
            for (float t : new float[] { 0, .35f, .45f, .5f, .55f, .65f, 1 }) {
                Vector3 p = new Vector3(a).lerp(b, t);
                var cover = BoardSurfaceBlend.sample(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z);
                assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(next), p.x, p.y, p.z));
                assertEquals(1, cover.grass() + cover.snow(), .00001f);
                if (t == 0) { assertEquals(1, cover.snow(), .00001f); }
                if (t == 1) { assertEquals(1, cover.grass(), .00001f); }
                if (t == .5f) { assertTrue(cover.snow() > .1f && cover.grass() > .1f); }
            }
        }
    }

    @Test
    void threeCoversMeetAtOneCornerWithoutChoosingOneNeighbourForTheHex() {
        Coords a = BoardScenes.CENTER.translated(BoardGeometry.edgeDirection(0));
        Coords b = BoardScenes.CENTER.translated(BoardGeometry.edgeDirection(5));
        BoardScene scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(a) ? BoardScene.Surface.SNOW
              : c.equals(b) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
        Vector3 p = BoardGeometry.corner(BoardScenes.CENTER, 0, 0);
        var cover = BoardSurfaceBlend.sample(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z);
        assertTrue(cover.snow() > .1f && cover.sand() > .1f && cover.grass() > .1f, cover.toString());
        assertEquals(1, cover.grass() + cover.sand() + cover.snow(), .00001f);
        assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(a), p.x, p.y, p.z));
        assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(b), p.x, p.y, p.z));
    }

    @Test
    void uniformFamiliesHaveNoBoundaryBand() {
        for (var family : BoardScene.Surface.values()) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, family, 0, -1, 0));
            assertFalse(BoardSurfaceBlend.boundary(scene, scene.tile(BoardScenes.CENTER)));
            for (int edge = 0; edge < 6; edge++) {
                var p = BoardGeometry.corner(BoardScenes.CENTER, 0, edge);
                assertEquals(1, BoardSurfaceBlend.sample(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z).weight(family), .00001f);
            }
        }
    }

    @Test
    void waterRoadsAndLevelConcreteEdgesBlockNaturalCover() {
        Coords next = BoardScenes.CENTER.translated(2);
        for (int mode = 0; mode < 3; mode++) {
            final int obstacle = mode;
            var scene = BoardScenes.scene(c -> c.equals(next) ? BoardScenes.tile(c, obstacle == 2 ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SNOW,
                  0, obstacle == 0 ? 1 : -1, obstacle == 1 ? 9 : 0)
                  : BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
            Vector3 p = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(next, 0), .49f);
            assertEquals(1, BoardSurfaceBlend.sample(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z).grass(), .00001f);
            assertFalse(BoardSurfaceBlend.boundary(scene, scene.tile(BoardScenes.CENTER)));
        }
    }

    @Test
    void cliffContactsContinueDownAllSixCornersWithoutBorrowingTheValleyCover() {
        for (int corner = 0; corner < 6; corner++) {
            var neighbour = BoardScenes.CENTER.translated(BoardGeometry.edgeDirection(corner));
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardScene.Surface.GRASS
                  : c.equals(neighbour) ? BoardScene.Surface.SAND : BoardScene.Surface.SNOW,
                  c.equals(BoardScenes.CENTER) || c.equals(neighbour) ? 4 : 0, -1, 0));
            var p = BoardGeometry.corner(BoardScenes.CENTER, 0, corner);
            for (int level = 1; level <= 4; level++) {
                p.z = level * BoardGeometry.level();
                var cover = BoardSurfaceBlend.sampleCliff(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z);
                assertEquals(cover, BoardSurfaceBlend.sampleCliff(scene, scene.tile(neighbour), p.x, p.y, p.z));
                assertTrue(cover.grass() > .1f && cover.sand() > .1f, "Corner " + corner + ": " + cover);
                assertEquals(0, cover.snow(), "The valley floor cannot paint a cliff above it");
                assertEquals(1, cover.grass() + cover.sand(), .00001f);
            }
        }
    }

    @Test
    void valleyCoverCannotPaintTheCliffAboveItsFootBand() {
        var next = BoardScenes.CENTER.translated(2);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(BoardScenes.CENTER) ? BoardScene.Surface.SAND
              : BoardScene.Surface.GRASS, c.equals(BoardScenes.CENTER) ? 4 : 1, -1, 0));
        var p = BoardGeometry.center(BoardScenes.CENTER, 3).lerp(BoardGeometry.center(next, 3), .5f);
        assertEquals(BoardSurfaceBlend.solid(BoardScene.Surface.SAND),
              BoardSurfaceBlend.sampleCliff(scene, scene.tile(BoardScenes.CENTER), p.x, p.y, p.z));
    }

    @Test
    void neighbouringBanksAgreeFromTheirLandCornerDownToTheWaterline() {
        for (int depth : new int[] { 0, 1 }) {
            var scene = BoardScenes.shores(depth, false);
            var water = scene.tile(BoardScenes.WATER);
            var surface = new BoardSurface(scene, water);
            // The sand/rock and dirt/sand junctions from the mixed-bank regression fixture.
            for (int corner : new int[] { 2, 3 }) {
                var first = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(corner - 1)));
                var second = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(corner)));
                var outer = BoardGeometry.corner(water.coords(), 0, corner);
                var inner = surface.waterBoundary(corner).getFirst();
                for (float t : new float[] { 0, .25f, .5f, .75f, 1 }) {
                    var p = new Vector3(outer).lerp(inner, t);
                    var cover = BoardSurfaceBlend.sample(scene, first, p.x, p.y, p.z);
                    assertEquals(cover, BoardSurfaceBlend.sample(scene, second, p.x, p.y, p.z));
                    assertTrue(cover.weight(first.surface()) > .1f, "First bank at " + t + ": " + cover);
                    assertTrue(cover.weight(second.surface()) > .1f, "Second bank at " + t + ": " + cover);
                    assertEquals(0, cover.grass(), "The water's nominal grass family must not paint its banks");
                    assertEquals(0, cover.concrete(), "Hard edges stay hard");
                }
            }
            for (int edge : new int[] { 0, 4 }) {
                var protectedLand = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(edge)));
                var p = surface.waterBoundary(edge).getFirst();
                assertEquals(BoardSurfaceBlend.solid(protectedLand.surface()),
                      BoardSurfaceBlend.sample(scene, protectedLand, p.x, p.y, p.z));
            }
        }
    }

    @Test
    void adjacentWaterBedsStayContinuousAcrossAllSixEdges() {
        for (int depth : new int[] { 0, 1 }) for (int direction = 0; direction < 6; direction++) {
            int waterDepth = depth;
            var next = BoardScenes.CENTER.translated(direction);
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(BoardScenes.CENTER) ? BoardScene.Surface.DESERT : c.equals(next) ? BoardScene.Surface.MARS
                        : c.distance(BoardScenes.CENTER) <= c.distance(next) ? BoardScene.Surface.GRASS : BoardScene.Surface.TROPICAL,
                  0, c.equals(BoardScenes.CENTER) || c.equals(next) ? waterDepth : -1, 0));
            var first = scene.tile(BoardScenes.CENTER);
            var second = scene.tile(next);
            var a = BoardGeometry.center(BoardScenes.CENTER, 0);
            var b = BoardGeometry.center(next, 0);
            for (float along : new float[] { -.35f, 0, .35f }) {
                var midpoint = new Vector3(a).lerp(b, .5f);
                var across = new Vector3(b).sub(a).nor();
                midpoint.add(-across.y * along * BoardGeometry.width() / 2,
                      across.x * along * BoardGeometry.width() / 2, 0);
                midpoint.z = BoardGeometry.groundZ(first);
                float epsilon = BoardRelief.metres(.001f);
                var left = new Vector3(midpoint).mulAdd(across, -epsilon);
                var right = new Vector3(midpoint).mulAdd(across, epsilon);
                var before = BoardSurfaceBlend.sample(scene, first, left.x, left.y, left.z);
                var after = BoardSurfaceBlend.sample(scene, second, right.x, right.y, right.z);
                assertEquals(before, BoardSurfaceBlend.sample(scene, second, left.x, left.y, left.z));
                for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
                    assertEquals(before.weight(family), after.weight(family), .003f,
                          "Water boundary at depth " + depth + ", direction " + direction + ", position " + along);
                }
            }
        }
    }

    @Test
    void shallowBarsShareTheirCentralCoverAndKeepBankPalettesBounded() {
        var scene = BoardScenes.shores(0, false);
        var water = scene.tile(BoardScenes.WATER);
        var center = BoardGeometry.center(water.coords(), 0);
        var banks = scene.tiles().stream().filter(t -> BoardSurfaceBlend.natural(t)
              && t.coords().distance(water.coords()) == 1).toList();
        var centralCover = BoardSurfaceBlend.sample(scene, banks.getFirst(), center.x, center.y, center.z);
        assertEquals(1, Integer.bitCount(centralCover.mask()), "Exposed bars share one centre instead of radial material wedges");
        for (int bearing = 0; bearing < 72; bearing++) {
            double angle = bearing * Math.PI / 36;
            for (int step = 0; step <= 8; step++) {
                float radius = step * BoardGeometry.HEIGHT * .05f;
                float x = center.x + radius * (float) Math.cos(angle), y = center.y + radius * (float) Math.sin(angle);
                var cover = BoardSurfaceBlend.sample(scene, banks.getFirst(), x, y, center.z);
                assertTrue(Integer.bitCount(cover.mask()) <= 3, "Bounded palette at radius " + radius + ": " + cover);
                assertEquals(1, cover.grass() + cover.dirt() + cover.sand() + cover.rock() + cover.concrete() + cover.snow(), .00001f);
                for (var bank : banks) { assertEquals(cover, BoardSurfaceBlend.sample(scene, bank, x, y, center.z)); }
            }
        }
    }

    @Test
    void editingANeighbourChangesCoverageAndTheExistingSurfaceCacheKey() {
        Coords next = BoardScenes.CENTER.translated(2);
        var before = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var after = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(next) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
        var p = BoardGeometry.center(BoardScenes.CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
        assertEquals(0, BoardSurfaceBlend.sample(before, before.tile(BoardScenes.CENTER), p.x, p.y, p.z).sand());
        assertTrue(BoardSurfaceBlend.sample(after, after.tile(BoardScenes.CENTER), p.x, p.y, p.z).sand() > .1f);
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(BoardScenes.CENTER)), BoardSurface.geometryKey(after, after.tile(BoardScenes.CENTER)));
    }

    @Test
    void everyBankAndShallowBarUsesItsOwnNeighbourIncludingRoadAndConcreteGround() {
        for (int depth : new int[] { 0, 1 }) {
            for (boolean mouths : new boolean[] { false, true }) {
                BoardScene scene = BoardScenes.shores(depth, mouths);
                BoardSurface surface = new BoardSurface(scene, scene.tile(BoardScenes.WATER));
                int[] banks = new int[6];
                for (var face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.TOP && face.finish() != BoardSurface.Finish.BED) { continue; }
                    assertTrue(face.landEdge() >= 0, "Every bank retains its source edge");
                    var land = scene.tile(BoardScenes.WATER.translated(BoardGeometry.edgeDirection(face.landEdge())));
                    if (!land.liquid().present()) {
                        assertEquals(land.surface(), surface.family(face));
                        banks[face.landEdge()]++;
                    } else if (face.finish() == BoardSurface.Finish.TOP) {
                        fail("A mouth's dry stub must take the bank's material, not the water neighbour");
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(normal.z >= -.001f * normal.len(), () -> "Folded material partition: " + face);
                }
                for (int e = 0; e < 6; e++) {
                    var land = scene.tile(BoardScenes.WATER.translated(BoardGeometry.edgeDirection(e)));
                    if (!land.liquid().present()) { assertTrue(banks[e] > 0, "Missing shore for edge " + e); }
                }
            }
        }
    }
}
