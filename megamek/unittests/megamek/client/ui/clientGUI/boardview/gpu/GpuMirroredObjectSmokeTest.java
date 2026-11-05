/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Mirrored placed objects draw instanced (b13): the instance data carries the mirror (the model's y axis reversed) and
 * mirror images draw in batches of their own whose front face is flipped, so every pass culls the faces it culls for
 * the model unmirrored. A parking barrier (off its model's centre, so its mirror image stands on the other side), a
 * recoloured car and a coral (the instanced foliage shader) are each drawn mirrored, then mirrored with a tilt of 0.05
 * degrees, which the instance data cannot hold, so they draw one by one with both faces as before b13, then unmirrored.
 */
@Tag("on-demand")
class GpuMirroredObjectSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "mirrored-objects");
    private static final Coords BARRIER = new Coords(1, 1), CAR = new Coords(3, 1), CORAL = new Coords(5, 1);
    /** Two more recoloured cars: a model's mirror images share one batch whatever their colours. */
    private static final List<Coords> MORE_CARS = List.of(new Coords(3, 2), new Coords(4, 2));
    /**
     * The share of an object's pixels that may differ by more than 32/255 between its instanced mirror image and its
     * one-by-one draw: the tilt moves its top by under a millimetre and the one-by-one draw puts both faces in the shadow
     * map, which moves the coral's fine self-shadowing (measured: 1.3 % of its pixels over 32/255, 6.7 % over 8/255; the
     * barrier and the car under 0.5 %). Culling the wrong faces or dropping the mirror changes most of the object.
     */
    private static final double TIES = .03;

    private enum Variant { NONE, MIRRORED, TILTED, UPRIGHT }

    private static BoardScene scene(Variant variant) throws Exception {
        Board board = Board.createEmptyBoard(7, 4);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
        }
        if (variant != Variant.NONE) {
            place(board, BARRIER, "scenery/vehicles/parking-barrier", variant, BoardDecoration.Colours.NONE);
            place(board, CAR, "scenery/vehicles/car", variant, BoardDecoration.Colours.of("#ff00ff"));
            place(board, MORE_CARS.get(0), "scenery/vehicles/car", variant, BoardDecoration.Colours.of("#1ee0f0"));
            place(board, MORE_CARS.get(1), "scenery/vehicles/car", variant, BoardDecoration.Colours.of("#b8b014"));
            place(board, CORAL, "mars/brain-lobes", variant, BoardDecoration.Colours.NONE);
        }
        var captured = BoardScenes.scene(board);
        return new BoardScene(0, captured.width(), captured.height(), captured.tiles(), List.of(), List.of(), -1, "",
              List.of(), new BoardScene.Light(-24, -30));
    }

    private static void place(Board board, Coords at, String asset, Variant variant, BoardDecoration.Colours colours) {
        board.getHex(at).setDecorations(List.of(new BoardDecoration(at.getBoardNum(), "prop", asset, null, 0, 0, 30,
              variant != Variant.UPRIGHT, 1, BoardDecoration.Placement.ground(), 0, false, variant == Variant.TILTED ? .05 : 0, 0)
              .withColours(colours)));
    }

    @Test
    void mirroredObjectsDrawInstancedAsTheirOneByOneDraw() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        Map<Variant, BoardScene> scenes = new LinkedHashMap<>();
        for (Variant variant : Variant.values()) { scenes.put(variant, scene(variant)); }
        Map<Variant, int[]> parts = new LinkedHashMap<>();
        Map<Variant, Integer> draws = new LinkedHashMap<>();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    for (var entry : scenes.entrySet()) {
                        var scene = entry.getValue();
                        var terrain = new GpuTerrain();
                        try {
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            terrain.setGrass(false);
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            camera.center(BoardGeometry.center(new Coords(3, 2), 0));
                            camera.camera.zoom = .5f;
                            camera.update();
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            parts.put(entry.getKey(), parts(terrain));
                            profiler.enable();
                            profiler.reset();
                            ScreenUtils.clear(0, 0, 0, 1, true);
                            terrain.render(camera.camera);
                            draws.put(entry.getKey(), profiler.getDrawCalls());
                            profiler.disable();
                            for (var at : List.of(BARRIER, CAR, CORAL)) {
                                camera.center(BoardGeometry.center(at, 0));
                                camera.camera.zoom = .1f;
                                camera.update();
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(OUTPUT, name(entry.getKey(), at)));
                            }
                        } finally { terrain.dispose(); }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Mirrored objects", failure.get()); }
        parts.forEach((variant, counts) -> System.out.printf("MIRROR %s: %d instanced parts (%d mirror images), %d one by one, "
              + "%d opaque draws%n", variant, counts[0], counts[2], counts[1], draws.get(variant)));
        int carParts = parts.get(Variant.MIRRORED)[3];
        assertTrue(carParts > 0);
        assertEquals(0, parts.get(Variant.MIRRORED)[1], "Every mirrored part draws instanced");
        assertEquals(parts.get(Variant.MIRRORED)[0], parts.get(Variant.MIRRORED)[2], "as a mirror image");
        assertEquals(0, parts.get(Variant.TILTED)[0], "A tilted part cannot be instanced");
        // One draw per part of each model, whatever the number of its mirror images and their colours: the three cars
        // drawn one by one cost two more draws per car part, the barrier and the coral (one placement each) none.
        assertEquals(draws.get(Variant.MIRRORED) + 2 * carParts, draws.get(Variant.TILTED),
              "The three mirrored cars share one draw per model part");
        for (var at : List.of(BARRIER, CAR, CORAL)) {
            int area = differing(Variant.NONE, Variant.MIRRORED, at, 8);
            int ties = differing(Variant.TILTED, Variant.MIRRORED, at, 32);
            int mirror = differing(Variant.UPRIGHT, Variant.MIRRORED, at, 8);
            System.out.printf("MIRROR %s: %d object px; instanced against one by one %d px over 32/255, against unmirrored %d px%n",
                  at.getBoardNum(), area, ties, mirror);
            assertTrue(area > 1000, at.getBoardNum() + " shows its object");
            assertTrue(ties <= TIES * area, at.getBoardNum() + ": the instanced mirror image draws as its one-by-one draw, "
                  + ties + " of " + area + " px differ");
            if (!at.equals(CAR)) {
                // The car is almost symmetric across its length; the barrier and the coral are not.
                assertTrue(mirror > area / 4, at.getBoardNum() + ": the mirror image differs from the unmirrored object");
            }
        }
    }

    private static String name(Variant variant, Coords at) {
        return variant.name().toLowerCase(java.util.Locale.ROOT) + "-" + at.getBoardNum() + ".png";
    }

    /** Pixels of the close-ups of {@code at} whose largest channel differs by more than {@code threshold}/255. */
    private static int differing(Variant first, Variant second, Coords at, int threshold) throws java.io.IOException {
        var a = javax.imageio.ImageIO.read(new File(OUTPUT, name(first, at)));
        var b = javax.imageio.ImageIO.read(new File(OUTPUT, name(second, at)));
        int result = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y), largest = 0;
                for (int shift = 0; shift < 24; shift += 8) { largest = Math.max(largest, Math.abs((p >> shift & 255) - (q >> shift & 255))); }
                if (largest > threshold) { result++; }
            }
        }
        return result;
    }

    /** Placed object parts: instanced, one by one, instanced mirror images, and the instanced parts of the car at CAR. */
    private static int[] parts(GpuTerrain terrain) throws ReflectiveOperationException {
        int[] result = new int[4];
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            var instanced = (Array<?>) field(chunk, "decorationRenderables");
            var owners = (Array<?>) field(chunk, "decorationOwners");
            result[0] += instanced.size;
            result[1] += ((Array<?>) field(chunk, "looseDecorations")).size;
            for (int i = 0; i < instanced.size; i++) {
                if (GpuTreeInstances.mirrored(((com.badlogic.gdx.graphics.g3d.Renderable) instanced.get(i)).worldTransform)) {
                    result[2]++;
                }
                if (CAR.getBoardNum().equals(field(owners.get(i), "decorationId"))) { result[3]++; }
            }
        }
        return result;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
