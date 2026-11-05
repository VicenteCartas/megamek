/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardLiquidTest {
    private static final Coords SIDE = new Coords(2, 2);

    /** Two hexes of {@code token} north and south of one side, on grass, which {@code sides} parts on the south hex. */
    private static BoardScene sides(String token, String owner, int sides) throws Exception {
        Board board = Board.createEmptyBoard(5, 5);
        Hex south = new Hex(0, token, "");
        if (sides != 0) { south.setAppearance(java.util.Map.of(owner, megamek.common.board.HexAppearance.withSeparated(null, sides))); }
        board.setHex(SIDE, south);
        board.setHex(SIDE.translated(0), new Hex(0, token, ""));
        return GpuLegacyImportSmokeTest.scene(board);
    }

    private static int edgeToward(int direction) {
        for (int edge = 0; edge < 6; edge++) {
            if (BoardGeometry.edgeDirection(edge) == direction) { return edge; }
        }
        throw new AssertionError(direction);
    }

    private static Vector3 sideMiddle() {
        int edge = edgeToward(0);
        return BoardGeometry.corner(SIDE, 0, edge).lerp(BoardGeometry.corner(SIDE, 0, (edge + 1) % 6), .5f);
    }

    private static float nearest(List<BoardSurface.Face> faces, Vector3 point) {
        float nearest = Float.POSITIVE_INFINITY;
        for (var face : faces) {
            for (Vector3 corner : List.of(face.a(), face.b(), face.c())) {
                nearest = Math.min(nearest, (float) Math.hypot(corner.x - point.x, corner.y - point.y));
            }
        }
        return nearest;
    }

    private static float topArea(BoardSurface surface) {
        float area = 0;
        for (var face : surface.faces) {
            if (face.finish() == BoardSurface.Finish.TOP) {
                area += new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).len() / 2;
            }
        }
        return area;
    }

    /** A separated side is no mouth: the water of both hexes stays off it, with land between them, and no legacy exit does this. */
    @Test void aSeparatedWaterSideIsABankWithLandAlongIt() throws Exception {
        float scale = BoardGeometry.hexScale();
        BoardScene joined = sides("water:1", "water", 0), apart = sides("water:1", "water", 1);
        assertTrue(new BoardSurface(joined, joined.tile(SIDE)).mouth(edgeToward(0)));
        assertTrue(nearest(new BoardSurface(joined, joined.tile(SIDE)).waterFaces, sideMiddle()) < 2 * scale, "Open water crosses the side");
        for (Coords hex : List.of(SIDE, SIDE.translated(0))) {
            var surface = new BoardSurface(apart, apart.tile(hex));
            assertFalse(surface.mouth(edgeToward(hex.equals(SIDE) ? 0 : 3)), hex.toString());
            assertTrue(nearest(surface.waterFaces, sideMiddle()) > 3 * scale, hex + " keeps its water off the side");
        }
        assertEquals(0, BoardLiquid.capture(new Hex(0, "water:1:0", "")).separated());
    }

    /** Where open water passes round a separated side's end, no land or wall reaches the corner: the land stops at a nose. */
    @Test void aSeparatedSidesFreeEndLeavesOpenWaterAtTheCorner() throws Exception {
        Board board = Board.createEmptyBoard(5, 5);
        Coords east = SIDE.translated(1), north = SIDE.translated(0);
        Hex south = new Hex(0, "water:1", "");
        south.setAppearance(java.util.Map.of("water", megamek.common.board.HexAppearance.withSeparated(null, 1 << 1)));
        board.setHex(SIDE, south);
        board.setHex(east, new Hex(0, "water:1", ""));
        board.setHex(north, new Hex(0, "water:1", ""));
        BoardScene scene = GpuLegacyImportSmokeTest.scene(board);
        var surface = new BoardSurface(scene, scene.tile(SIDE));
        int edge = edgeToward(1);
        Vector3 a = BoardGeometry.corner(SIDE, 0, edge), b = BoardGeometry.corner(SIDE, 0, (edge + 1) % 6);
        // The free corner is the one the north hex shares.
        Vector3 shared = java.util.stream.IntStream.range(0, 6).mapToObj(k -> BoardGeometry.corner(north, 0, k))
              .anyMatch(corner -> corner.dst(a) < .01f) ? a : b;
        Vector3 other = shared == a ? b : a;
        float water = BoardGeometry.waterZ(scene.tile(SIDE)), scale = BoardGeometry.hexScale();
        for (var face : surface.faces) {
            if (face.finish() == BoardSurface.Finish.BED) { continue; }
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (p.dst(shared.x, shared.y, p.z) < .1f * shared.dst(other)) {
                    assertTrue(p.z <= water + .01f * scale, "Nothing stands out of the water near the corner: " + p);
                }
            }
        }
        var relief = new BoardRelief(scene, scene.tile(SIDE), BoardSurface.ramps(scene, scene.tile(SIDE)), TerrainLod.FULL);
        Vector3 open = new Vector3(shared).lerp(other, .1f), land = new Vector3(shared).lerp(other, .5f);
        assertTrue(relief.shore(open.x, open.y, false) > 0, "Open water on the side near the corner");
        assertTrue(relief.shore(land.x, land.y, false) < 0, "Land along the rest of the side");
    }

    /** Pavement stops short of a separated side and the ground it lies on fills the rest of its hex, with no gap in the mesh. */
    @Test void separatedPavementShowsTheGroundAlongItsSide() throws Exception {
        BoardScene joined = sides("pavement:1", "pavement", 0), apart = sides("pavement:1", "pavement", 1);
        var open = new BoardSurface(joined, joined.tile(SIDE));
        var parted = new BoardSurface(apart, apart.tile(SIDE));
        assertTrue(open.faces.stream().allMatch(face -> open.family(face) == BoardScene.Surface.CONCRETE));
        var ground = parted.faces.stream().filter(face -> parted.family(face) != BoardScene.Surface.CONCRETE).toList();
        assertFalse(ground.isEmpty());
        assertTrue(ground.stream().allMatch(face -> parted.family(face) == BoardScene.Surface.GRASS));
        Vector3 middle = sideMiddle();
        assertTrue(ground.stream().anyMatch(face -> face.height(middle.x, middle.y) != Float.NEGATIVE_INFINITY), "Ground on the side itself");
        assertEquals(topArea(open), topArea(parted), .01f * topArea(open), "The strip and the slab fill the hex");
        assertFalse(BoardSurface.geometryKey(joined, joined.tile(SIDE)).equals(BoardSurface.geometryKey(apart, apart.tile(SIDE))),
              "An edit of the side rebuilds the hex");
    }

    @Test
    void terrainControlsTheLiquidAndHazardLevelsNeverBecomeWaterDepths() {
        Hex hex = new Hex(5);
        hex.setTheme("mars");
        hex.addTerrain(new Terrain(Terrains.WATER, 8));
        BoardLiquid.Textures mars = BoardLiquid.capture(hex).textures(8);
        assertEquals("terrain/water/anim_water_4.gif", mars.base(), "Themed water plays the plain water's animation");
        assertTrue(mars.tone() != null, "in its theme's tone");
        for (int level = 0; level <= 3; level++) {
            hex.addTerrain(new Terrain(Terrains.HAZARDOUS_LIQUID, level));
            BoardLiquid liquid = BoardLiquid.capture(hex);
            assertEquals(BoardLiquid.Kind.HAZARDOUS, liquid.kind());
            assertEquals("terrain/water/anim_water_4.gif", liquid.textures(8).base());
            assertNull(liquid.textures(8).tone(), "A hazardous pool takes its material's green tint instead");
        }
        hex.removeAllTerrains();
        hex.addTerrain(new Terrain(Terrains.MAGMA, 1));
        assertEquals(BoardLiquid.Kind.MAGMA_CRUST, BoardLiquid.capture(hex).kind());
        assertFalse(BoardLiquid.capture(hex).present(), "Magma crust stays solid");
        assertTrue(BoardLiquid.capture(hex).volcanic(), "Solid crust retains its hot material");
        hex.addTerrain(new Terrain(Terrains.MAGMA, 2));
        BoardLiquid lava = BoardLiquid.capture(hex);
        assertTrue(lava.molten());
        assertEquals("terrain/ground/base_magma_anim.gif", lava.textures(-1).base(), "Levels grade magma at run time");
        for (int type : new int[] { Terrains.MUD, Terrains.SWAMP }) {
            hex.removeAllTerrains();
            hex.addTerrain(new Terrain(type, 1));
            assertEquals(BoardLiquid.NONE, BoardLiquid.capture(hex), "Wet ground keeps its authored terrain surface");
        }
    }

    @Test
    void crustBreakageAndRemovalRefreshTheMaterialAndSupportWithoutChangingGameElevation() throws Exception {
        Hex hex = new Hex(2);
        hex.addTerrain(new Terrain(Terrains.MAGMA, 1));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(1, 1, new Hex[] { hex }))) {
            Coords coords = new Coords(0, 0);
            for (int level : new int[] { 1, 2, 1, 0 }) {
                SwingUtilities.invokeAndWait(() -> {
                    Hex changed = hex.duplicate();
                    changed.removeTerrain(Terrains.MAGMA);
                    if (level > 0) { changed.addTerrain(new Terrain(Terrains.MAGMA, level)); }
                    fixture.game.getBoard().setHex(coords, changed);
                    fixture.source.refresh();
                });
                BoardScene scene = fixture.source.takeFrame().scene();
                BoardScene.Tile tile = scene.tile(coords);
                assertEquals(2, tile.elevation());
                assertEquals(level == 2, tile.liquid().present());
                assertEquals(level > 0, tile.liquid().volcanic());
                assertTrue(tile.detailedGround());
                assertEquals(level == 2, !new BoardSurface(scene, tile).waterFaces.isEmpty());
                assertEquals(2 * BoardGeometry.level() - (level == 2 ? BoardGeometry.hexScale() : 0),
                      BoardGeometry.surfaceZ(tile), .001);
                Vector3 above = BoardGeometry.center(coords, 2).add(0, 0, 100);
                assertEquals(coords, BoardGeometry.pick(scene, new Ray(above, new Vector3(0, 0, -1))));
                if (level > 0) {
                    assertNull(tile.decals(), "Legacy magma art must not cover the new material");
                    assertFalse(BoardSurfaceBlend.natural(tile), "Ordinary soil/grass cannot blend across hot crust");
                }
            }
        }
    }

    @Test
    void isolatedPoolsKeepVisibleLiquidBetweenHigherBanks() {
        Coords pool = new Coords(3, 3);
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        for (BoardLiquid.Kind kind : List.of(BoardLiquid.Kind.WATER, BoardLiquid.Kind.HAZARDOUS, BoardLiquid.Kind.MAGMA)) {
            BoardScene scene = BoardSurfaceBlendTest.scene(c -> c.equals(pool)
                  ? new BoardScene.Tile(pool, 0, -1, false, 0, BoardScene.Surface.ROCK, null, null, List.of(),
                        List.of(), new BoardLiquid(kind, "", 0), null, true)
                  : BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 1, -1, 0));
            assertVisiblePool(scene, pool);
        }
    }

    @Test
    void fireAndIceLavaPoolKeepsItsSurfaceThroughTheRealBoardCapture() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/unofficial/Drewbacca/16x17 Fire And Ice 2.board"));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            BoardScene scene = fixture.source.takeFrame().scene();
            Coords pool = new Coords(1, 15);
            assertEquals(BoardLiquid.Kind.MAGMA, scene.tile(pool).liquid().kind());
            assertEquals(0, scene.tile(pool).elevation());
            assertVisiblePool(scene, pool);
        }
    }

    private static void assertVisiblePool(BoardScene scene, Coords pool) {
        Vector3 center = BoardGeometry.center(pool, 0);
        for (TerrainLod lod : TerrainLod.values()) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(pool), lod);
            for (int direction = 0; direction < 6; direction++) {
                // An isolated depth-0 pool reaches about a fifth of the way to its corners; its waterline moves a tenth
                // of a unit with the shore search's precision, so the centre is sampled inside that.
                Vector3 point = new Vector3(center).lerp(BoardGeometry.corner(pool, 0, direction), .15f);
                float liquid = BoardSurface.sampleHeight(surface.waterFaces, point.x, point.y, Float.NaN);
                assertTrue(Float.isFinite(liquid), pool + " pool must retain a visible center at " + lod);
                assertTrue(BoardSurface.sampleHeight(surface.faces, point.x, point.y, Float.NEGATIVE_INFINITY) < liquid,
                      pool + " pool must stay above its bed and clear of its banks at " + lod);
            }
        }
        assertEquals(pool, BoardGeometry.pick(scene, new Ray(new Vector3(center).add(0, 0, 500), new Vector3(0, 0, -1))));
    }

    @Test
    void hazardousPoolsShareWaterGeometryAndMoltenEdgesConnectOnlyToMoltenNeighbors() {
        BoardLiquid toxic = new BoardLiquid(BoardLiquid.Kind.HAZARDOUS, "", 0);
        BoardLiquid lava = new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0);
        BoardScene waterScene = scene(BoardLiquid.WATER, BoardLiquid.WATER, 2);
        BoardScene toxicScene = scene(toxic, BoardLiquid.WATER, 2);
        Coords high = new Coords(0, 0);
        BoardSurface water = new BoardSurface(waterScene, waterScene.tile(high));
        BoardSurface hazardous = new BoardSurface(toxicScene, toxicScene.tile(high));
        assertEquals(water.faces, hazardous.faces);
        assertEquals(water.waterFaces, hazardous.waterFaces);
        assertEquals(water.waterfalls, hazardous.waterfalls);

        BoardScene lavaScene = scene(lava, lava, -1);
        BoardSurface molten = new BoardSurface(lavaScene, lavaScene.tile(high));
        assertTrue(molten.waterfalls.isEmpty(), "A two-level lava drop shares the normal graded liquid surface");
        BoardScene mixedScene = scene(lava, BoardLiquid.WATER, -1);
        assertTrue(new BoardSurface(mixedScene, mixedScene.tile(high)).waterfalls.isEmpty(),
              "A rocky shoreline separates lava from water");
        Vector3 point = BoardGeometry.center(high, 2);
        assertEquals(high, BoardGeometry.pick(lavaScene, new Ray(point.cpy().add(0, 0, 100), new Vector3(0, 0, -1))));
        assertEquals(point.z - BoardGeometry.HEX_SCALE, BoardGeometry.surfaceZ(lavaScene.tile(high)), 0.001f);
    }

    @Test
    void captureRetainsLiquidAndFlowAcrossTacticalRefreshesAndTerrainEdits() throws Exception {
        Hex pool = new Hex(1);
        pool.addTerrain(new Terrain(Terrains.WATER, 2));
        pool.addTerrain(new Terrain(Terrains.HAZARDOUS_LIQUID, 3));
        pool.addTerrain(new Terrain(Terrains.RAPIDS, 2));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(1, 1, new Hex[] { pool }))) {
            Coords coords = new Coords(0, 0);
            BoardScene.Tile first = fixture.source.takeFrame().scene().tile(coords);
            assertEquals(2, first.waterDepth());
            assertEquals(BoardLiquid.Kind.HAZARDOUS, first.liquid().kind());
            assertEquals(2, first.liquid().rapids());
            assertNull(first.decals(), "Static 2D hazardous and rapids overlays must not cover the animated surface");
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.setVisibleArea(new Rectangle(0, 0, 1, 1));
                fixture.source.refresh();
            });
            assertEquals(first.liquid(), fixture.source.takeFrame().scene().tile(coords).liquid());
            SwingUtilities.invokeAndWait(() -> {
                Hex changed = pool.duplicate();
                changed.removeTerrain(Terrains.HAZARDOUS_LIQUID);
                changed.setTheme("volcano");
                fixture.game.getBoard().setHex(coords, changed);
                fixture.source.refresh();
            });
            var last = fixture.source.takeFrame().scene().tile(coords);
            assertEquals(BoardLiquid.Kind.WATER, last.liquid().kind());
            assertEquals("volcano", last.liquid().theme());
            assertEquals(2, last.waterDepth());
            assertFalse(last.liquid().textures(2).foam().isEmpty());
        }
    }

    @Test
    void volcanicSlopesShareGeometryAndPickingInEveryDirection() {
        BoardSculptTest.withTransitions(true, () -> {
            Coords high = new Coords(3, 3);
            for (int direction = 0; direction < 6; direction++) {
                for (int drop : new int[] { 1, 2, 3 }) {
                    Coords low = high.translated(direction);
                    BoardScene crust = slopeScene(BoardLiquid.Kind.MAGMA_CRUST, drop, low);
                    BoardScene rock = slopeScene(BoardLiquid.Kind.NONE, drop, low);
                    for (TerrainLod lod : new TerrainLod[] { TerrainLod.FULL, TerrainLod.MEDIUM, TerrainLod.COARSE }) {
                        for (Coords coords : List.of(high, low)) {
                            BoardSurface solid = new BoardSurface(crust, crust.tile(coords), lod);
                            BoardSurface ordinary = new BoardSurface(rock, rock.tile(coords), lod);
                            assertTrue(solid.relief.sculpted());
                            assertEquals(ordinary.foundation(), solid.foundation(),
                                  "Crust keeps the canonical terrain; ordinary rock alone carries loose rim stones");
                            assertEquals(ordinary.walls(rock, -50), solid.walls(crust, -50),
                                  "Crust keeps every slope and cliff, including both halves of the shared edge");
                        }
                    }
                    BoardScene lava = slopeScene(BoardLiquid.Kind.MAGMA, drop, low);
                    BoardSurface upper = new BoardSurface(lava, lava.tile(high));
                    BoardSurface lower = new BoardSurface(lava, lava.tile(low));
                    assertEquals(drop <= 2, upper.waterfalls.isEmpty(), "Lava slopes for 1/2 levels, falls from 3");
                    if (drop > 2) { continue; }
                    int edge = Math.floorMod(1 - direction, 6), opposite = (edge + 3) % 6;
                    List<Vector3> a = upper.waterBoundary(edge), b = lower.waterBoundary(opposite);
                    assertEquals(a.size(), b.size());
                    for (int i = 0; i < a.size(); i++) {
                        assertTrue(a.get(i).epsilonEquals(b.get(b.size() - 1 - i), .002f),
                              "Lava mouth stays watertight in direction " + direction + " at drop " + drop);
                    }
                    Vector3 from = BoardGeometry.center(high, 0), to = BoardGeometry.center(low, 0);
                    float previous = Float.POSITIVE_INFINITY;
                    for (int i = 0; i <= 20; i++) {
                        Vector3 p = new Vector3(from).lerp(to, i / 20f);
                        BoardSurface own = i < 10 ? upper : lower;
                        float height = own.waterHeight(p.x, p.y);
                        assertTrue(Float.isFinite(height) && height <= previous + .01f,
                              "Lava descends continuously instead of making a vertical step");
                        previous = height;
                    }
                    Vector3 above = new Vector3(from).add(0, 0, 500);
                    assertEquals(high, BoardGeometry.pick(lava, new Ray(above, new Vector3(0, 0, -1))));
                }
            }
        });
    }

    private static BoardScene slopeScene(BoardLiquid.Kind kind, int drop, Coords low) {
        BoardScene.Pixels pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        Coords high = new Coords(3, 3);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                BoardLiquid liquid = coords.equals(high) || coords.equals(low) ? new BoardLiquid(kind, "", 0) : BoardLiquid.NONE;
                tiles.add(new BoardScene.Tile(coords, coords.equals(high) ? drop : 0, -1, false, 0,
                      BoardScene.Surface.ROCK, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene scene(BoardLiquid high, BoardLiquid low, int depth) {
        BoardScene.Pixels pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        return new BoardScene(0, 1, 2, List.of(
              new BoardScene.Tile(new Coords(0, 0), 2, depth, false, 0, BoardScene.Surface.ROCK, null, null, List.of(),
                    List.of(), high),
              new BoardScene.Tile(new Coords(0, 1), 0, depth, false, 0, BoardScene.Surface.ROCK, null, null, List.of(),
                    List.of(), low)),
              List.of(), List.of(), -1, "", List.of());
    }
}
