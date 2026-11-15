/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/**
 * The ground map the meadow's blades place themselves on ({@link GpuGroundMap}), read back from the GPU over a board
 * with a paved road, buildings, a pond and level steps: it grows where the terrain draws meadow, nothing on a road,
 * under a building or in water, and its height is the installed ground's, across a chunk's border too.
 */
@Tag("on-demand")
class GpuGroundMapSmokeTest {
    private static final BoardAtmosphere.Settings NOON = new BoardAtmosphere.Settings(12, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));

    private GpuTerrain terrain;
    private GpuReviewFrame frame;
    private BoardScene scene;
    private final BoardCamera camera = new BoardCamera();

    @Test
    void theMapHoldsTheGroundTheTerrainDraws() throws Exception {
        scene = GpuCalibrationScenes.scene(new File("data/boards/unofficial/Lanceman/16x17 Nebraska Farmhouse.board"));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                frame = new GpuReviewFrame(NOON);
                terrain = new GpuTerrain();
                // The map is kept for grass: High's meadow share (Low draws none).
                terrain.setSwitches(BoardSwitches.of(BoardSwitches.Preset.HIGH));
                try {
                    GpuCalibrationScenes.prepare(terrain, frame, new BoardCamera(), scene);
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setPerspective(false);
                    camera.setIsometric(false);
                    camera.camera.zoom = BoardGeometry.width() / 180f;
                    check();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Ground map", failure.get()); }
    }

    private void check() throws Exception {
        float metre = BoardRelief.metres(1);
        // A meadow hex and its six neighbours, level: the map grows all over it and stands on its ground.
        Coords meadow = find(tile -> meadow(tile) && neighbours(tile, next -> meadow(next) && next.elevation() == tile.elevation()));
        assertNotNull(meadow, "The board has a level meadow");
        Vector3 centre = BoardGeometry.center(meadow, 0);
        for (int direction = 0; direction < 6; direction++) {
            Vector3 towards = BoardGeometry.center(meadow.translated(direction), 0);
            float x = centre.x + (towards.x - centre.x) * .4f, y = centre.y + (towards.y - centre.y) * .4f;
            float[] map = sample(meadow, x, y);
            assertTrue(map[0] > .9f, "A meadow grows all over: " + map[0] + " at " + meadow);
            assertEquals(ground(x, y), map[1], .05f * metre, "The map's height is the meadow's ground");
        }
        // Nothing grows on a paved road, where a building stands, or under water.
        Coords road = find(tile -> tile.road() == BoardRoad.Kind.PAVED && !tile.liquid().present());
        Coords building = find(BoardScene.Tile::slab);
        Coords water = find(tile -> tile.waterDepth() >= 1);
        for (Coords bare : new Coords[] { road, building, water }) {
            assertNotNull(bare, "The board has a paved road, a building and water");
            Vector3 at = BoardGeometry.center(bare, 0);
            assertEquals(0, sample(bare, at.x, at.y)[0], .02f, "Nothing grows at the centre of " + bare);
        }
        // A step of one level between two meadow hexes: the map follows the installed slope between them.
        Coords upper = find(tile -> meadow(tile) && !neighbours(tile, next -> !meadow(next) || next.elevation() != tile.elevation() - 1));
        if (upper != null) {
            BoardScene.Tile top = scene.tile(upper);
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile lower = scene.neighbor(top, direction);
                if (lower == null || !meadow(lower) || lower.elevation() != top.elevation() - 1) { continue; }
                Vector3 from = BoardGeometry.center(upper, 0), to = BoardGeometry.center(lower.coords(), 0);
                for (float along = .2f; along < .85f; along += .1f) {
                    float x = from.x + (to.x - from.x) * along, y = from.y + (to.y - from.y) * along;
                    assertEquals(ground(x, y), sample(upper, x, y)[1], .25f * metre, "The map's height follows the slope at " + along);
                }
                break;
            }
        }
        // Two meadow hexes on either side of a chunk's border: the same growth and one ground across it.
        Coords inner = find(tile -> tile.coords().getX() % TerrainLod.CHUNK_SIZE == TerrainLod.CHUNK_SIZE - 1 && meadow(tile)
              && beside(tile, 1) && beside(tile, 2));
        assertNotNull(inner, "The board has a level meadow on a chunk's border");
        Vector3 from = BoardGeometry.center(inner, 0);
        for (int direction = 1; direction <= 2; direction++) {
            Vector3 to = BoardGeometry.center(inner.translated(direction), 0);
            for (float along = .3f; along < .75f; along += .05f) {
                float x = from.x + (to.x - from.x) * along, y = from.y + (to.y - from.y) * along;
                float[] map = sample(inner, x, y);
                assertTrue(map[0] > .9f, "The meadow grows across a chunk's border: " + map[0] + " at " + along);
                assertEquals(ground(x, y), map[1], .05f * metre, "One ground across a chunk's border");
            }
        }
        // What changes the ground changes the map. A hex an area effect stripped grows none, at once: its tile says so.
        scene = BoardScenes.replace(scene, scene.tile(meadow).withoutGroundCover(false));
        assertEquals(0, sample(meadow, centre.x, centre.y)[0], .02f, "A scorched hex grows none");
        // An edited hex's chunk is installed later: the map redraws its rectangle and stands on the new ground.
        Coords raised = meadow.translated(3);
        terrain.setEditable(true);
        scene = BoardScenes.replace(scene, BoardScenes.tile(raised, BoardScene.Biome.NONE, scene.tile(raised).elevation() + 1));
        Vector3 top = BoardGeometry.center(raised, 0);
        float[] edited = sample(raised, top.x, top.y);
        assertEquals(ground(top.x, top.y), edited[1], .05f * metre, "The map follows an installed chunk's ground");
        assertTrue(edited[0] > .9f, "The raised meadow still grows: " + edited[0]);
        System.out.println("Ground map: " + terrain.groundMap().stats());
    }

    private static boolean meadow(BoardScene.Tile tile) {
        return tile != null && BoardSurfaceBlend.natural(tile) && tile.surface() == BoardScene.Surface.GRASS
              && BoardBiome.kind(tile) == BoardScene.Biome.NONE && tile.road() == BoardRoad.Kind.NONE;
    }

    /** Whether the tile's neighbour that way is a meadow on its level. */
    private boolean beside(BoardScene.Tile tile, int direction) {
        BoardScene.Tile next = scene.neighbor(tile, direction);
        return meadow(next) && next.elevation() == tile.elevation();
    }

    /** Whether all six neighbours of a tile exist and pass. */
    private boolean neighbours(BoardScene.Tile tile, Predicate<BoardScene.Tile> test) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile next = scene.neighbor(tile, direction);
            if (next == null || !test.test(next)) { return false; }
        }
        return true;
    }

    private Coords find(Predicate<BoardScene.Tile> test) {
        return scene.tiles().stream().filter(test).map(BoardScene.Tile::coords).findFirst().orElse(null);
    }

    /** The installed ground's height at a point, as units stand on it. */
    private float ground(float x, float y) {
        var queries = new BoardSurface.Cache();
        queries.installed(terrain::tacticalSurface);
        return UnitLandingSupports.ground(scene, x, y, queries);
    }

    /**
     * The map's growth and height at a world point, as a blade reads them (between its four texels), with the view
     * over {@code hex} drawn first.
     */
    private float[] sample(Coords hex, float x, float y) throws Exception {
        camera.center(BoardGeometry.center(hex, scene.tile(hex).elevation()));
        frame.prepare(terrain, camera, scene);
        terrain.update(scene, camera.camera);
        GpuReviewFrame.settle(terrain, null, scene, camera);
        frame.render(terrain, camera, scene);
        GpuGroundMap map = terrain.groundMap();
        float[] place = map.frame(), levels = map.levels();
        float u = (x - place[0]) / place[3] - .5f, v = (y - place[1]) / place[3] - .5f;
        int column = (int) Math.floor(u), row = (int) Math.floor(v);
        assertTrue(column >= 0 && row >= 0 && column + 1 < GpuGroundMap.SIZE && row + 1 < GpuGroundMap.SIZE,
              "The map holds the view's ground");
        ByteBuffer cover = BufferUtils.newByteBuffer(GpuGroundMap.SIZE * GpuGroundMap.SIZE * 4);
        FloatBuffer depth = BufferUtils.newFloatBuffer(GpuGroundMap.SIZE * GpuGroundMap.SIZE);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        map.cover().bind();
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, cover);
        map.height().bind();
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        float growth = 0, height = 0;
        for (int corner = 0; corner < 4; corner++) {
            int texel = (row + corner / 2) * GpuGroundMap.SIZE + column + corner % 2;
            float weight = (corner % 2 == 0 ? 1 - (u - column) : u - column) * (corner / 2 == 0 ? 1 - (v - row) : v - row);
            growth += weight * (cover.get(texel * 4) & 255) / 255f;
            height += weight * (levels[0] - depth.get(texel) * levels[1]);
        }
        return new float[] { growth, height };
    }
}
