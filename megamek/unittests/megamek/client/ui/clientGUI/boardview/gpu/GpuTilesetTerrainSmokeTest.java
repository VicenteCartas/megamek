/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.utils.NumberUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Classic graphics' columns, built as GpuTerrain chunks: a board edit rebuilds only the chunks of
 * its hex and of the neighbours that read it (the chunk pipeline's change scan, which the tileset art joins), and the
 * liquid stays inside the shore that picking uses.
 */
@Tag("on-demand")
class GpuTilesetTerrainSmokeTest {
    /** At least two chunks each way. */
    private static final int WIDTH = 40, HEIGHT = 20;

    @Test
    void anEditRebuildsOnlyTheChunksOfItsHexAndItsNeighbours() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                try {
                    verify(terrain);
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Classic chunks", failure.get()); }
    }

    private static void verify(GpuTerrain terrain) throws Exception {
        BoardScene.Pixels art = pixels(0xff60a040);
        int size = GpuTerrain.CHUNK_SIZE, rows = (HEIGHT + size - 1) / size;
        BoardScene scene = scene(Map.of(), Map.of(), art);
        terrain.setColumns(true);
        terrain.update(scene);
        assertTrue(terrain.columns(), "A Classic build installs the columns");
        List<?> chunks = (List<?>) field(terrain, "chunks");
        assertEquals((WIDTH + size - 1) / size * rows, chunks.size());

        // Inside the first chunk, the hex and all its neighbours stay there.
        Coords inside = new Coords(size / 2, size / 2), edge = new Coords(size - 1, size / 2);
        scene = scene(Map.of(inside, 2), Map.of(), art);
        assertEquals(Set.of(0), rebuilt(terrain, chunks, scene));
        for (var face : terrain.tacticalSurface(inside).top()) {
            assertEquals(2 * BoardGeometry.level(), face.a().z, .0001f, "The edited column stands at its new level");
        }
        // Raised on the chunk's eastern edge, it walls down to the neighbours across that edge too.
        scene = scene(Map.of(inside, 2, edge, 1), Map.of(), art);
        assertEquals(Set.of(0, rows), rebuilt(terrain, chunks, scene));
        // New tileset art at the same height leaves the neighbours' walls and rims as they were.
        scene = scene(Map.of(inside, 2, edge, 1), Map.of(edge, pixels(0xff806040)), art);
        assertEquals(Set.of(0), rebuilt(terrain, chunks, scene));

        // A shallower pool on the chunk's edge keeps its water level and the board's floor, but changes the walls
        // under the water of its neighbours across that edge.
        List<BoardScene.Tile> water = new ArrayList<>();
        for (var tile : scene.tiles()) { water.add(pool(tile.coords(), 4, art)); }
        scene = scene.withTiles(water);
        terrain.update(scene);
        water = new ArrayList<>(water);
        water.set(edge.getX() * HEIGHT + edge.getY(), pool(edge, 2, art));
        scene = scene.withTiles(water);
        assertEquals(Set.of(0, rows), rebuilt(terrain, chunks, scene));
        for (var face : terrain.tacticalSurface(edge).faces()) {
            assertEquals(-2 * BoardGeometry.level(), face.a().z, .001f, "The artwork follows the edited bed depth");
        }

        // A bank borrows land art across a chunk boundary, even when only the land's art changed.
        water.set(edge.getX() * HEIGHT + edge.getY(), land(edge, art));
        scene = scene.withTiles(water);
        terrain.update(scene);
        water = new ArrayList<>(water);
        water.set(edge.getX() * HEIGHT + edge.getY(), land(edge, pixels(0xff907050)));
        scene = scene.withTiles(water);
        assertEquals(Set.of(0, rows), rebuilt(terrain, chunks, scene));

        // Sand fades over the solid bank; liquid keeps its existing opacity right up to its fitted boundary.
        Coords center = new Coords(1, 1);
        for (var bank : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.CONCRETE)) {
            List<BoardScene.Tile> shore = new ArrayList<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                Coords at = new Coords(x, y);
                shore.add(at.equals(center) ? pool(at, 2, art)
                      : new BoardScene.Tile(at, 1, -1, false, 0, bank, null, null,
                            List.of(), List.of(), BoardLiquid.NONE, art));
            }
            scene = new BoardScene(0, 3, 3, shore, List.of(), List.of(), -1, "", List.of());
            terrain.update(scene);
            var support = terrain.tacticalSurface(center);
            int vertices = 0;
            for (Object instance : (List<?>) field(((List<?>) field(terrain, "chunks")).getFirst(), "water")) {
                for (Mesh mesh : ((ModelInstance) instance).model.meshes) {
                    int stride = mesh.getVertexSize() / Float.BYTES;
                    int colour = mesh.getVertexAttribute(Usage.ColorPacked).offset / Float.BYTES;
                    int position = mesh.getVertexAttribute(Usage.Position).offset / Float.BYTES;
                    float[] data = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(data);
                    for (int i = 0; i < mesh.getNumVertices(); i++) {
                        // A packed colour keeps seven bits of alpha: white packs to 254.
                        int alpha = NumberUtils.floatToIntColor(data[i * stride + colour]) >>> 24;
                        assertTrue(alpha >= 254, "Water must not fade beside " + bank + ": " + alpha);
                        float x = data[i * stride + position], y = data[i * stride + position + 1];
                        assertTrue(support.water().stream().anyMatch(face -> Float.isFinite(face.height(x, y))),
                              "Uploaded water stays inside the same shore boundary used by picking: " + bank);
                        vertices++;
                    }
                }
            }
            assertTrue(vertices > 0, "Inspect the actual uploaded liquid mesh");
        }

        // Frozen water shows its water's still first frame, opaque, under Enhanced's lake-ice sheet.
        List<BoardScene.Tile> frozen = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            Coords at = new Coords(x, y);
            frozen.add(at.equals(center) ? new BoardScene.Tile(at, 0, 2, true, 0, BoardScene.Surface.GRASS, null, null,
                  List.of(), List.of(), BoardLiquid.WATER, art) : land(at, art));
        }
        terrain.update(new BoardScene(0, 3, 3, frozen, List.of(), List.of(), -1, "", List.of()));
        Object chunk = ((List<?>) field(terrain, "chunks")).getFirst();
        assertTrue(materials(chunk, "opaque").stream().anyMatch(material -> material.id.startsWith("column-frozen:")),
              "The still frame lies in the opaque layer");
        assertTrue(materials(chunk, "water").stream().anyMatch(GpuIceShader::floating), "The ice sheet lies over it");
        assertTrue(materials(chunk, "water").stream().noneMatch(material -> material.id.startsWith("column-liquid:")),
              "Frozen water does not animate");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    private static List<Material> materials(Object chunk, String layer) throws Exception {
        List<Material> result = new ArrayList<>();
        for (Object instance : (List<?>) field(chunk, layer)) {
            for (Material material : ((ModelInstance) instance).materials) { result.add(material); }
        }
        return result;
    }

    @Test
    void capturesNaturalShoresAndARaisedPoolWithTheRealTileset() throws Exception {
        int width = 12, height = 10;
        Hex[] hexes = new Hex[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            boolean river = x == 3 || y >= 6 && x >= 2 && x <= 5;
            boolean pool = x == 8 && y == 4;
            Hex hex = new Hex(pool ? 6 : x >= 7 ? 1 : y < 5 ? 2 : 0);
            if (river || pool) { hex.addTerrain(new Terrain(Terrains.WATER, pool || x == 3 ? 1 : 2)); }
            if (x == 6 && y >= 6) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
            hexes[y * width + x] = hex;
        }
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(new Board(width, height, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1440, 1080);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    try {
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                              "tactical-shores");
                        Files.createDirectories(output.toPath());
                        BoardCamera camera = new BoardCamera();
                        camera.resize(1440, 1080);
                        Coords pool = new Coords(8, 4);
                        terrain.update(scene);
                        assertFalse(terrain.columns());
                        terrain.setColumns(true);
                        terrain.update(scene);
                        assertTrue(terrain.columns());
                        float floor = BoardGeometry.floor(scene);
                        assertEquals(GpuTilesetTerrain.column(scene, scene.tile(pool), floor).top(),
                              terrain.tacticalSurface(pool).top(), "A Classic hex's support is its column");
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .65f;
                            camera.center(BoardGeometry.center(new Coords(5, 5), 2));
                            frame.render(terrain, camera, scene);
                            terrain.animate(.5f, List.of());
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, oblique ? "oblique.png" : "top.png"));
                        }
                        camera.camera.zoom = .16f;
                        camera.center(BoardGeometry.center(new Coords(8, 4), 5));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "raised-pool.png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        frame.dispose();
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Classic shore review", failure.get()); }
    }

    private static BoardScene.Tile pool(Coords coords, int depth, BoardScene.Pixels art) {
        return new BoardScene.Tile(coords, 0, depth, false, 0, BoardScene.Surface.GRASS, null, null,
              List.of(), List.of(), BoardLiquid.WATER, art);
    }

    private static BoardScene.Tile land(Coords coords, BoardScene.Pixels art) {
        return new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS, null, null,
              List.of(), List.of(), BoardLiquid.NONE, art);
    }

    /** The indices of the chunks a blocking update of the terrain to {@code scene} replaced. */
    private static Set<Integer> rebuilt(GpuTerrain terrain, List<?> chunks, BoardScene scene) {
        List<Object> before = new ArrayList<>(chunks);
        terrain.update(scene);
        Set<Integer> result = new HashSet<>();
        for (int index = 0; index < chunks.size(); index++) {
            if (chunks.get(index) != before.get(index)) { result.add(index); }
        }
        return result;
    }

    /** A level board with the given hexes raised or given other tileset art. */
    private static BoardScene scene(Map<Coords, Integer> raised, Map<Coords, BoardScene.Pixels> painted,
          BoardScene.Pixels art) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, raised.getOrDefault(coords, 0), -1, false, 0,
                      BoardScene.Surface.GRASS, null, null, List.of(), List.of(), BoardLiquid.NONE,
                      painted.getOrDefault(coords, art)));
            }
        }
        return new BoardScene(0, WIDTH, HEIGHT, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels pixels(int argb) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, argb); }
        }
        return new BoardScene.Pixels(image);
    }
}
