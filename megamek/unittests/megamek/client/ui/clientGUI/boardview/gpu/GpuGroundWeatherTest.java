/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuGroundWeatherTest {
    @Test
    void fogAndSandKeepBaseFlowWhenCalmAndFollowStrongerWind() {
        var calm = new GpuAtmosphere.GroundMotion();
        calm.advance(BoardAtmosphere.Effects.NONE, 0.1f);
        assertTrue(calm.fog.len() > 0);
        var still = new GpuAtmosphere.GroundMotion();
        still.advance(BoardAtmosphere.Effects.NONE, 0.1f, 0);
        assertEquals(Vector2.Zero, still.fog, "Calm drift remains optional for visual comparisons");
        assertTrue(calm.sand.y > 0 && calm.grains > 0);
        for (int direction : new int[] { 0, 90, 180, 270 }) {
            float previous = 0;
            for (float wind : new float[] { 0.2f, 1 }) {
                var motion = new GpuAtmosphere.GroundMotion();
                motion.advance(wind(wind, direction), 0.1f);
                Vector2 travel = signed(motion.fog);
                float along = switch (direction) {
                    case 0 -> travel.y;
                    case 90 -> travel.x;
                    case 180 -> -travel.y;
                    default -> -travel.x;
                };
                assertTrue(along > previous, "Stronger wind must accelerate banks in the selected direction");
                assertEquals(along, travel.len(), 0.0001f, "Fog cannot drift across the selected wind");
                assertTrue(signed(motion.sand).dot(travel) > 0, "Sand must follow the same wind direction");
                previous = along;
            }
        }
    }

    @Test
    void travelIsContinuousAcrossFramesWindChangesAndPeriodicWraps() {
        var single = new GpuAtmosphere.GroundMotion();
        var split = new GpuAtmosphere.GroundMotion();
        single.advance(wind(0.6f, 60), 0.1f);
        for (int i = 0; i < 10; i++) { split.advance(wind(0.6f, 60), 0.01f); }
        assertTrue(single.fog.epsilonEquals(split.fog, 0.0001f));
        assertTrue(single.sand.epsilonEquals(split.sand, 0.0001f));
        assertEquals(single.grains, split.grains, 0.0001f);
        split.advance(wind(1, 240), 0);
        assertTrue(single.fog.epsilonEquals(split.fog, 0.0001f), "Changing wind cannot teleport existing banks");
        split.advance(wind(1, 240), Float.NaN);
        assertTrue(single.fog.epsilonEquals(split.fog, 0.0001f));
        var unwrapped = new GpuAtmosphere.GroundMotion();
        unwrapped.advance(wind(1, 0), 0.1f);
        float remaining = 256 - 255.999f;
        split.fog.set(0, 255.999f);
        split.advance(wind(1, 0), 0.1f);
        assertEquals(unwrapped.fog.y - remaining, split.fog.y, 0.00002f,
              "Wrapping preserves the full frame displacement at the repeating texture period");
    }

    @Test
    void calmFogDirectionVariesSmoothlyAndIsIndependentOfFrameRate() {
        var regular = new GpuAtmosphere.GroundMotion();
        var fast = new GpuAtmosphere.GroundMotion();
        Vector2 first = null;
        Vector2 last = null;
        for (int i = 0; i < 120; i++) {
            Vector2 before = regular.fog.cpy();
            regular.advance(BoardAtmosphere.Effects.NONE, 0.1f);
            for (int j = 0; j < 10; j++) { fast.advance(BoardAtmosphere.Effects.NONE, 0.01f); }
            last = regular.fog.cpy().sub(before);
            if (first == null) { first = last.cpy(); }
            assertEquals(GpuAtmosphere.FOG_CALM_DRIFT * 0.1f / 2, last.len(), 0.00001f,
                  "Calm drift keeps its configured speed in two-hex noise cells while changing direction");
        }
        assertTrue(regular.fog.epsilonEquals(fast.fog, 0.0001f));
        assertTrue(Math.abs(first.nor().crs(last.nor())) > 0.3f, "Calm banks follow a changing flow vector");
    }

    @Test
    void variationsCanBeDisabledAndRemainBounded() {
        var uniform = new GpuAtmosphere.Options(0, false, 0, 1, 0, -1, -1);
        assertEquals(0, uniform.fogHeightVariation());
        assertEquals(0, uniform.fogDensityVariation());
        var maximum = new GpuAtmosphere.Options(0, false, 0, 1, 0, 100, 100);
        assertEquals(4, maximum.fogHeightVariation());
        assertEquals(1, maximum.fogDensityVariation());
        assertThrows(IllegalArgumentException.class, () -> new GpuAtmosphere.Options(0, false, 0, 1, 0, Float.NaN, 1));
        assertThrows(IllegalArgumentException.class,
              () -> new GpuAtmosphere.Options(0, false, 0, 1, 0, 1, Float.POSITIVE_INFINITY));
        assertFalse(new BoardAtmosphere.Effects(0, 0, 0, 1, 0, 0, 0).hasParticles(),
              "Sand cannot allocate or draw the precipitation pool");
    }

    private static BoardAtmosphere.Effects wind(float strength, float direction) {
        return new BoardAtmosphere.Effects(0, 0, 0, 0, 0, strength, direction);
    }

    private static Vector2 signed(Vector2 wrapped) {
        return new Vector2(wrapped.x > 128 ? wrapped.x - 256 : wrapped.x, wrapped.y > 128 ? wrapped.y - 256 : wrapped.y);
    }

    @Test
    void weatherStartsAtTheLowestHexLevelRegardlessOfDepthOrBoardElevation() {
        for (int base : new int[] { -3, 0, 4 }) {
            for (int wetLevel : new int[] { base, base + 2 }) {
                var wet = new BoardScene.Tile(new Coords(0, 0), wetLevel, 12, false, 0, BoardScene.Surface.GRASS, null,
                      List.of(), List.of());
                var dry = new BoardScene.Tile(new Coords(1, 0), base, -1, false, 0, BoardScene.Surface.GRASS, null,
                      List.of(), List.of());
                BoardScene scene = new BoardScene(0, 2, 1, List.of(wet, dry), List.of(), List.of(), -1, "", List.of());
                assertEquals(base * BoardGeometry.LEVEL, BoardGeometry.weatherBase(scene));
                assertEquals(base * BoardGeometry.LEVEL, BoardGeometry.sandBase(scene));
                assertTrue(BoardGeometry.floor(scene) < BoardGeometry.weatherBase(scene),
                      "The carved board floor must not pull atmosphere below the hex surfaces");
            }
        }
    }

    @Test
    void sandUsesNonnegativeGroundAtExactlyThreeQuartersOfTheBorder() {
        var board = weatherScene(new int[][] {
              { -1, -1, -1, -1, 0 },
              { 0, -3, -3, -3, 0 },
              { 0, -3, -3, -3, 0 },
              { 0, -3, -3, -3, 0 },
              { 0, 0, 0, 0, 0 }
        });
        assertEquals(0, BoardGeometry.sandBase(board),
              "12 of 16 border hexes qualify, even though fewer than half of all hexes do");
        assertEquals(-3 * BoardGeometry.level(), BoardGeometry.weatherBase(board),
              "Fog and precipitation retain the original minimum");
        assertEquals(0, BoardGeometry.sandBase(BoardScenes.raisedScene(-3)), "An interior depression cannot lower sand on a level-0 rim");
    }

    @Test
    void sandKeepsNegativeGroundBelowTheBorderThresholdEvenWithAMostlyNonnegativeMap() {
        var board = weatherScene(new int[][] {
              { -2, -2, -2, -2, -2 },
              { 0, 0, 0, 0, 0 },
              { 0, 0, 0, 0, 0 },
              { 0, 0, 0, 0, 0 },
              { 0, 0, 0, 0, 0 }
        });
        assertEquals(-2 * BoardGeometry.level(), BoardGeometry.sandBase(board),
              "Only 11 of 16 border hexes qualify, despite 80% of the whole map being nonnegative");
    }

    @Test
    void sandFindsTheLowestNonnegativeLevelAnywhereOnTheMap() {
        for (int lowest : new int[] { 0, 2 }) {
            var board = weatherScene(new int[][] {
                  { 4, 4, 4, 4 },
                  { 4, -3, lowest, 4 },
                  { 4, 4, 4, 4 }
            });
            assertEquals(lowest * BoardGeometry.level(), BoardGeometry.sandBase(board),
                  "Select an actual eligible level, including zero, rather than clamping the old minimum");
        }
    }

    @Test
    void sandNeverUsesUltraSublevelsAsEitherMinimum() {
        for (int ground : new int[] { -2, 3 }) {
            for (int pit : new int[] { -20, 0 }) {
                var board = weatherScene(new int[][] {
                      { ground, ground, ground },
                      { ground, pit, ground },
                      { ground, ground, ground }
                }, new Coords(1, 1));
                assertEquals(ground * BoardGeometry.level(), BoardGeometry.sandBase(board));
            }
        }
        assertEquals(0, BoardGeometry.sandBase(weatherScene(new int[][] { { -20 } }, new Coords(0, 0))),
              "A board with only ultra-sublevels falls back to level zero");
    }

    @Test
    void sandCountsThinBoardBordersOnce() {
        assertEquals(0, BoardGeometry.sandBase(weatherScene(new int[][] { { -2, 0, 0, 0 } })));
        assertEquals(0, BoardGeometry.sandBase(weatherScene(new int[][] { { -2 }, { 0 }, { 0 }, { 0 } })));
        assertEquals(-2 * BoardGeometry.level(), BoardGeometry.sandBase(weatherScene(new int[][] { { -2, 0, 0 } })));
        assertEquals(-2 * BoardGeometry.level(), BoardGeometry.sandBase(weatherScene(new int[][] { { -2 } })));
    }

    private static BoardScene weatherScene(int[][] levels, Coords... pits) {
        int height = levels.length, width = levels[0].length;
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var at = new Coords(x, y);
                tiles.add(new BoardScene.Tile(at, levels[y][x], -1, false, 0, BoardScene.Surface.GRASS,
                      null, null, List.of(), List.of(), BoardLiquid.NONE, null, false, BoardRoad.Kind.NONE,
                      BoardFireSmoke.NONE, BoardScene.Biome.NONE, false, false, 0, false,
                      BoardSurfaceBlend.solid(BoardScene.Surface.GRASS), List.of(pits).contains(at)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
