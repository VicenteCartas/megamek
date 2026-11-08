/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Compare cached hardware depth with the full shadow pass, including removed and occluded units. */
@Tag("on-demand")
class GpuShadowCacheSmokeTest {
    @Test
    void movingUnitsReuseStaticDepthAndInvalidationMatchesTheFullPass() throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/Grasslands BattleMats/32x17 Grasslands A BattleMat.board"), false, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            BoardScene scene = fixture.source.takeFrame().scene();
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try { check(fixture, scene); }
                    catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Static shadow reuse", failure.get()); }
    }

    private void check(GpuBoardFixture fixture, BoardScene scene) throws Exception {
        GpuTerrain terrain = new GpuTerrain();
        Model model = new ModelBuilder().createBox(20, 20, 20, new Material(ColorAttribute.createDiffuse(Color.WHITE)),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        try {
            BoardCamera camera = new BoardCamera();
            camera.resize(1280, 900);
            camera.fit(scene);
            terrain.update(scene);
            var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
            terrain.setAtmosphere(lighting);
            terrain.renderShadows(camera.camera, List.of());
            assertNull(GpuTestSupport.field(GpuTestSupport.field(terrain, "shadowMap"), "staticShadow"), "Map-only views keep one shadow framebuffer");
            camera.zoom(.6f);
            // The zoom changes tree levels, which the map picks up at its next redraw: start from that redraw.
            terrain.refreshShadows();
            terrain.renderShadows(camera.camera, List.of());
            byte[] stationary = depth(terrain);
            assertCastersIgnoreTheCameraZoom(terrain, camera);
            for (int pan = 0; pan < 5; pan++) {
                camera.pan(1, 0);
                profiler.reset();
                terrain.renderShadows(camera.camera, List.of());
                assertEquals(0, profiler.getDrawCalls(), "Small camera pans reuse the existing shadow map");
                assertArrayEquals(stationary, depth(terrain), "Camera reuse preserves the shadow texture and projection");
                fullPass(terrain, List.of());
                assertArrayEquals(stationary, depth(terrain), "A fresh pass at the reused projection remains exact");
            }
            ModelInstance unit = new ModelInstance(model);
            Coords at = scene.tiles().stream().filter(t -> t.elevation() == 0 && !t.liquid().present()).skip(30).findFirst().orElseThrow().coords();
            var center = BoardGeometry.center(at, 0);
            unit.transform.setToTranslation(center.x, center.y, 10);
            terrain.renderShadows(camera.camera, List.of(unit));
            for (int step = 0; step < 9; step++) {
                List<ModelInstance> units = (step == 4 || step == 8) ? List.of() : List.of(unit);
                unit.transform.setToTranslation(center.x + step * 3, center.y, step == 3 ? 0 : 10);
                if (step == 1 || step == 2) { camera.pan(1, -1); }
                if (step == 5) { camera.zoom(.7f); camera.pan(30, 15); }
                if (step == 6) { terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(9, 0, 0, 0, 0, 1))); }
                if (step == 7) {
                    javax.swing.SwingUtilities.invokeAndWait(() -> {
                        Hex raised = fixture.game.getBoard().getHex(at).duplicate();
                        raised.setLevel(raised.getLevel() + 1);
                        fixture.game.getBoard().setHex(at, raised);
                        fixture.source.refresh();
                    });
                    terrain.update(fixture.source.takeFrame().scene());
                }
                profiler.reset();
                terrain.renderShadows(camera.camera, units);
                int cached = profiler.getDrawCalls();
                byte[] actual = depth(terrain);
                profiler.reset();
                fullPass(terrain, units);
                int full = profiler.getDrawCalls();
                assertArrayEquals(depth(terrain), actual, "Hardware depth must match the full pass at step " + step);
                if (step < 3) {
                    assertTrue(cached < full / 2, "Unit motion must not redraw the static board: " + cached + " / " + full);
                }
                if (step == 4) {
                    assertEquals(0, cached, "Removing the last unit only blits depth, with no geometry draw or color draw-buffer call");
                }
                if (step >= 5) {
                    assertEquals(full, cached, "Camera, light and terrain changes must not pay an extra cache copy");
                }
                System.out.printf("SHADOW step=%d cached=%d full=%d%n", step, cached, full);
            }
            profiler.disable();
            double[][] times = new double[2][60];
            for (int frame = -10; frame < times[0].length; frame++) {
                unit.transform.setToTranslation(center.x + (frame & 3), center.y, 10);
                // Pair the two paths and alternate their order to reduce clock/power-state bias.
                for (int pass = 0; pass < 2; pass++) {
                    int variant = (frame + pass) & 1;
                    Gdx.gl.glFinish();
                    long started = System.nanoTime();
                    if (variant == 1) { terrain.renderShadows(camera.camera, List.of(unit)); }
                    else { fullPass(terrain, List.of(unit)); }
                    Gdx.gl.glFinish();
                    if (frame >= 0) { times[variant][frame] = (System.nanoTime() - started) / 1e6; }
                }
            }
            for (int variant = 0; variant < times.length; variant++) {
                java.util.Arrays.sort(times[variant]);
                System.out.printf("SHADOW isolated cached=%s median=%.3fms p95=%.3fms%n", variant == 1,
                      times[variant][30], times[variant][57]);
            }
            profiler.enable();
            assertRefitKeepsTheTexelGrid(terrain, camera, profiler);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            profiler.disable();
            model.dispose();
            terrain.dispose();
        }
    }

    /**
     * A pan past the map's room refits it at the same texel on the same world grid: the depth both maps hold is the
     * same, moved by whole texels, so casters' shadows keep their edges as the view moves. Interpolated depth and its
     * slope bias may differ in a few texels, where a depth step meets other 2 by 2 derivative quads.
     */
    private static void assertRefitKeepsTheTexelGrid(GpuTerrain terrain, BoardCamera camera, GLProfiler profiler) {
        // A view needing well under the whole board, which the board-wide map would otherwise cover.
        camera.zoom(.5f);
        terrain.renderShadows(camera.camera, List.of());
        var light = ((DirectionalShadowLight) terrain.environment().shadowMap).getCamera();
        int size = ((DirectionalShadowLight) terrain.environment().shadowMap).getFrameBuffer().getWidth();
        float width = light.viewportWidth, height = light.viewportHeight;
        Vector3 right = new Vector3(light.direction).crs(light.up).nor();
        float x = light.position.dot(right), y = light.position.dot(light.up);
        int[] before = texels(depth(terrain));
        boolean refit = false;
        for (int pan = 0; pan < 60 && !refit; pan++) {
            camera.pan(8, 3);
            profiler.reset();
            terrain.renderShadows(camera.camera, List.of());
            refit = profiler.getDrawCalls() > 0;
        }
        assertTrue(refit, "The pans leave the map's room");
        assertEquals(width, light.viewportWidth, 0, "The refit keeps the texel");
        assertEquals(height, light.viewportHeight, 0);
        float dx = (light.position.dot(right) - x) * size / width, dy = (light.position.dot(light.up) - y) * size / height;
        assertEquals(Math.round(dx), dx, .01f, "The map moves by whole texels");
        assertEquals(Math.round(dy), dy, .01f);
        int[] after = texels(depth(terrain));
        int shiftX = Math.round(dx), shiftY = Math.round(dy), compared = 0, differing = 0;
        // Leave out the borders, which the shadow pass's scissor keeps clear.
        for (int row = 1; row < size - 1; row++) {
            for (int column = 1; column < size - 1; column++) {
                int fromRow = row + shiftY, fromColumn = column + shiftX;
                if (fromRow < 1 || fromColumn < 1 || fromRow >= size - 1 || fromColumn >= size - 1) { continue; }
                compared++;
                long change = Math.abs(Integer.toUnsignedLong(after[row * size + column])
                      - Integer.toUnsignedLong(before[fromRow * size + fromColumn]));
                if (change > 0xffffffffL / 10000) { differing++; }
            }
        }
        System.out.printf("SHADOW refit shift=%d,%d compared=%d differing=%d%n", shiftX, shiftY, compared, differing);
        assertTrue(compared > size * size / 2, "The maps overlap");
        assertTrue(differing <= compared / 1000, "The overlap keeps its depth: " + differing + " of " + compared);
    }

    private static int[] texels(byte[] depth) {
        int[] result = new int[depth.length / Integer.BYTES];
        java.nio.ByteBuffer.wrap(depth).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer().get(result);
        return result;
    }

    private static void fullPass(GpuTerrain terrain, List<ModelInstance> units) throws ReflectiveOperationException {
        var shadow = (DirectionalShadowLight) terrain.environment().shadowMap;
        var draw = GpuTerrain.class.getDeclaredMethod("renderDepth", Camera.class, List.class, ModelBatch.class, boolean.class,
              boolean.class);
        draw.setAccessible(true);
        // As the full shadow pass draws: shadows on, scatter included.
        shadow.begin();
        try { draw.invoke(terrain, shadow.getCamera(), units, GpuTestSupport.field(terrain, "depthBatch"), true, true); }
        finally { shadow.end(); }
    }

    private static byte[] depth(GpuTerrain terrain) { return GpuFamilyAssemblyReview.shadowDepth(terrain); }

    /**
     * The map's trees take their detail from its texel, not from the camera: a zoom that changes the view's tree levels
     * changes none of the map's depth at the same projection.
     */
    private static void assertCastersIgnoreTheCameraZoom(GpuTerrain terrain, BoardCamera camera) throws Exception {
        var detail = GpuTerrain.class.getDeclaredMethod("updateDetail", Camera.class);
        detail.setAccessible(true);
        byte[] before = depth(terrain);
        int levels = treeLevels(terrain);
        float zoom = camera.camera.zoom;
        camera.camera.zoom = zoom / 8;
        camera.update();
        detail.invoke(terrain, camera.camera);
        assertTrue(treeLevels(terrain) < levels, "The zoom brings the view's trees nearer levels");
        fullPass(terrain, List.of());
        assertArrayEquals(before, depth(terrain), "The camera's tree levels do not reach the map");
        camera.camera.zoom = zoom;
        camera.update();
        detail.invoke(terrain, camera.camera);
    }

    /** The sum of the trees' levels the view chose (GpuTreeInstances.Stand#detail). */
    @SuppressWarnings("unchecked")
    private static int treeLevels(GpuTerrain terrain) throws ReflectiveOperationException {
        int sum = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            Object stand = GpuTestSupport.field(chunk, "stand");
            for (int[] starts : ((java.util.Map<String, int[]>) GpuTestSupport.field(stand, "starts")).values()) {
                for (int level = 0; level + 1 < starts.length; level++) { sum += level * (starts[level + 1] - starts[level]); }
            }
        }
        return sum;
    }
}
