/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.NumberUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL compilation and pixels, including live discovery and the normal-map/detail controls. */
@Tag("on-demand")
class GpuIceSmokeTest {
    private static final Coords ROAD = new Coords(1, 2), LAKE = new Coords(4, 2);

    @Test
    void iceUsesMappedMaterialsWithoutRevealingUndetectedBlackIce() throws Exception {
        BoardScene dry = scene(0), hidden = scene(1), detected = scene(2);
        BoardScene margins = Boolean.getBoolean("megamek.gpu.iceCompositionReview") ? scene(2, true) : null;
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(settings(13));
                var badges = new GpuEditorBadges();
                var annotations = new SpriteBatch();
                try (var pixels = new GpuSceneTestOutput()) {
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(1200, 900);
                    camera.fit(dry);
                    terrain.setAtmosphere(frame.lighting());
                    terrain.update(dry);
                    int[] road = sample(pixels, terrain, camera, ROAD);
                    GpuReviewFrame.save(new File(output, "ice-debug-dry.png"));
                    terrain.update(hidden);
                    assertArrayEquals(road, sample(pixels, terrain, camera, ROAD), "Undetected ice must be visually identical");
                    terrain.update(detected);
                    int[] glazed = sample(pixels, terrain, camera, ROAD);
                    GpuReviewFrame.save(new File(output, "ice-debug-detected.png"));
                    assertTrue(difference(road, glazed) > 1, "Discovery must visibly coat the road: " + difference(road, glazed));
                    terrain.setNormalMaps(false);
                    camera.setIsometric(true);
                    camera.camera.zoom = .16f;
                    camera.center(BoardGeometry.center(LAKE, 0));
                    int[] flat = sample(pixels, terrain, camera, LAKE);
                    terrain.setNormalMaps(true);
                    double normalChange = difference(flat, sample(pixels, terrain, camera, LAKE));
                    assertTrue(normalChange > .05, "Ice normal maps must reach the GL shader: " + normalChange);
                    frame.render(terrain, camera, detected);
                    GpuReviewFrame.save(new File(output, "ice-lake-detail.png"));
                    // Substitute two opaque backgrounds under the same sheet. A fake opaque ice texture
                    // would give identical pixels, whereas actual transmission must reveal the difference.
                    pixels.scene().begin();
                    Gdx.gl.glDepthMask(true);
                    // These are the original scene-radiance stimuli, not authored sRGB material pigments.
                    // Store them in the scene's units and let the shared display boundary encode the result.
                    ScreenUtils.clear(.05f * GpuSceneRadiance.SCALE, .12f * GpuSceneRadiance.SCALE,
                          .3f * GpuSceneRadiance.SCALE, 1, true);
                    var profiler = new GLProfiler(Gdx.graphics);
                    profiler.enable();
                    terrain.renderTransparent(camera.camera);
                    int iceDraws = profiler.getDrawCalls();
                    profiler.disable();
                    pixels.display();
                    // Snow on the slab is opaque; its bare ice is not. Sample a wide area of the hex.
                    int[] blue = read(camera, LAKE, 240);
                    pixels.scene().begin();
                    Gdx.gl.glDepthMask(true);
                    ScreenUtils.clear(.3f * GpuSceneRadiance.SCALE, .08f * GpuSceneRadiance.SCALE,
                          .03f * GpuSceneRadiance.SCALE, 1, true);
                    terrain.renderTransparent(camera.camera);
                    pixels.display();
                    double transmitted = difference(blue, read(camera, LAKE, 240));
                    System.out.println("Lake ice transmission difference: " + transmitted);
                    assertTrue(transmitted > .5, "Lake ice must transmit the scene behind it");
                    assertEquals(1, iceDraws, "The connected lake in one chunk shares one ice draw call");
                    System.out.println("Connected lake ice draw calls: " + iceDraws);
                    if (margins != null) {
                        terrain.update(margins);
                        GpuReviewFrame.settle(terrain, null, margins, camera);
                        frame.render(terrain, camera, margins);
                        verifyIceComposition(pixels, terrain, camera, frame, margins, 3, "lake-margins");
                        terrain.update(detected);
                        GpuReviewFrame.settle(terrain, null, detected, camera);
                    }
                    camera.center(BoardGeometry.center(ROAD, 0));
                    frame.render(terrain, camera, detected);
                    GpuReviewFrame.save(new File(output, "ice-road-detected.png"));
                    for (boolean perspective : new boolean[] {false, true}) {
                        camera.setPerspective(perspective);
                        camera.setIsometric(true);
                        camera.fit(detected);
                        frame.render(terrain, camera, detected);
                        GpuReviewFrame.save(new File(output, perspective ? "ice-perspective.png" : "ice-isometric.png"));
                    }
                    camera.setPerspective(false);
                    camera.setIsometric(false);
                    camera.fit(detected);
                    frame.configure(settings(0));
                    frame.render(terrain, camera, detected);
                    GpuReviewFrame.save(new File(output, "ice-night.png"));
                    frame.configure(settings(13));
                    terrain.setAtmosphere(frame.lighting());
                    int[] beforeRemoval = sample(pixels, terrain, camera, ROAD);
                    terrain.update(dry);
                    int[] restored = sample(pixels, terrain, camera, ROAD);
                    assertTrue(difference(beforeRemoval, restored) > 1, "Removing ice must remove the visible coat");
                    terrain.update(hidden);
                    assertArrayEquals(restored, sample(pixels, terrain, camera, ROAD), "Hidden ice retains the restored dry surface");
                    var stack = BoardEditorTerrain.badges(new Hex(0, "black_ice:1;bldg_base_collapsed:1", ""));
                    camera.setIsometric(true);
                    camera.camera.zoom = .10f;
                    camera.center(BoardGeometry.center(ROAD, 0));
                    frame.render(terrain, camera, hidden);
                    annotations.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
                          camera.camera.viewportWidth, camera.camera.viewportHeight));
                    annotations.begin();
                    badges.render(annotations, camera.camera, 1, hidden, Map.of(ROAD, stack), terrain::roofBounds, ROAD);
                    annotations.end();
                    GpuReviewFrame.save(new File(output, "ice-editor-marker.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    annotations.dispose();
                    badges.dispose();
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Ice material rendering", failure.get()); }
    }

    /** Actual provider and compositor: only this diagnostic's queued floating-ice draws are isolated. */
    static void verifyIceComposition(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera,
          GpuReviewFrame frame, BoardScene scene, int mode, String name) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField("batch");
        field.setAccessible(true);
        var batch = (ModelBatch) field.get(terrain);
        RenderableSorter original = batch.getRenderableSorter();
        var sorter = ModelBatch.class.getDeclaredField("sorter");
        sorter.setAccessible(true);
        int[] submitted = { 0 }, branches = new int[4];
        boolean[] inspectGeometry = { true };
        try {
            sorter.set(batch, (RenderableSorter) (view, parts) -> {
                for (int i = parts.size - 1; i >= 0; i--) {
                    var material = parts.get(i).material;
                    if (GpuIceShader.mode(material) != mode) { parts.removeIndex(i); continue; }
                    var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
                    assertTrue(blend != null && blend.blended && blend.sourceFunction == GL20.GL_ONE
                          && blend.destFunction == GL20.GL_ONE_MINUS_SRC_ALPHA, "Floating ice is premultiplied");
                    assertTrue(!material.get(DepthTestAttribute.class, DepthTestAttribute.Type).depthMask,
                          "Floating ice cannot overwrite its opaque support depth");
                    if (mode == 3 && inspectGeometry[0]) { countIceBranches(parts.get(i), branches); }
                    submitted[0]++;
                }
                original.sort(view, parts);
            });
            float[] source = iceOver(output, terrain, camera, new float[] {0, 0, 0, 0}, 1);
            inspectGeometry[0] = false;
            if (mode == 3) {
                System.out.printf("Ice installed inputs %s slab=%d lead=%d edge=%d margin=%d%n",
                      name, branches[0], branches[1], branches[2], branches[3]);
                for (int branch : branches) {
                    assertTrue(branch > 0, "Installed ice must include slab, lead, cut face and broken margin inputs");
                }
            }
            int covered = 0, partial = 0;
            for (int i = 0; i < source.length; i += 4) {
                float alpha = source[i + 3];
                assertTrue(Float.isFinite(alpha) && alpha >= 0 && alpha <= 1, "Finite bounded ice coverage");
                if (alpha > .01f) { covered++; }
                if (alpha > .01f && alpha < .995f) { partial++; }
                for (int c = 0; c < 3; c++) {
                    assertTrue(Float.isFinite(source[i + c]) && source[i + c] >= 0, "Finite canonical ice radiance");
                }
            }
            System.out.printf("Ice composition %s mode=%d submitted=%d covered=%d partial=%d%n",
                  name, mode, submitted[0], covered, partial);
            assertTrue(submitted[0] > 0 && covered > 100 && partial > 100, "The actual translucent ice must be visible");
            float[][] backgrounds = {{.05f, .12f, .3f, 1}, {.3f, .08f, .03f, 1}, {.2f, .4f, .1f, .25f}};
            for (int b = 0; b < backgrounds.length; b++) {
                float[] background = backgrounds[b];
                float[] result = iceOver(output, terrain, camera, background, 1);
                output.display();
                GpuReviewFrame.save(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                      "ice-composition-" + name + "-background-" + b + ".png"));
                float maxColorError = 0, maxAlphaError = 0;
                for (int i = 0; i < source.length; i += 4) {
                    float remaining = 1 - source[i + 3];
                    for (int c = 0; c < 3; c++) {
                        float expected = source[i + c] + background[c] * background[3] * remaining;
                        assertTrue(Float.isFinite(result[i + c]), "The composed RGBA16F result must stay finite");
                        maxColorError = Math.max(maxColorError,
                              Math.abs(result[i + c] - expected) / Math.max(1, Math.abs(expected)));
                    }
                    maxAlphaError = Math.max(maxAlphaError,
                          Math.abs(result[i + 3] - (source[i + 3] + background[3] * remaining)));
                }
                System.out.printf("Ice composition %s background=%d colorError=%g alphaError=%g%n",
                      name, b, maxColorError, maxAlphaError);
                // Allow several half-float rounding steps across overlapping physical ice faces.
                assertTrue(maxColorError <= .004f && maxAlphaError <= .002f,
                      "Premultiplied source-over must preserve the background and coverage exactly once");
            }
            float[] hidden = iceOver(output, terrain, camera, new float[] {0, 0, 0, 0}, 0);
            for (float value : hidden) { assertEquals(0, value, 0, "Opaque foreground depth must occlude the ice"); }

            // Exercise the real transparent-export composite, including its clear-sky removal.
            var atmosphere = frame.atmosphere();
            atmosphere.setTransparentBackground(true);
            try {
                atmosphere.beginPixels(output.scene().getWidth(), output.scene().getHeight(), 0);
                terrain.renderTransparent(camera.camera);
                float[] scenePixels = readIcePixels(output.scene().getWidth(), output.scene().getHeight());
                atmosphere.end(camera.camera, terrain, scene, 0, null, false, output.pixels());
                output.pixels().begin();
                var display = ScreenUtils.getFrameBufferPixels(false);
                int exported = 0;
                for (int i = 3; i < scenePixels.length; i += 4) {
                    assertEquals(scenePixels[i], (display[i] & 255) / 255f, 1.1f / 255,
                          "The real transparent-export composite preserves ice coverage");
                    if (scenePixels[i] > .01f && scenePixels[i] < .995f) { exported++; }
                }
                System.out.printf("Ice composition %s transparentExportPartial=%d%n", name, exported);
                assertTrue(exported > 100, "The export must contain the actual partial ice silhouette");
            } finally { atmosphere.setTransparentBackground(false); }
        } finally {
            Gdx.gl.glClearDepthf(1);
            sorter.set(batch, original);
        }
    }

    /** Inspect the actual submitted slab's indexed vertex data, including its early-out optical inputs. */
    private static void countIceBranches(Renderable part, int[] branches) {
        var mesh = part.meshPart.mesh;
        int stride = mesh.getVertexSize() / Float.BYTES;
        int normal = mesh.getVertexAttribute(VertexAttributes.Usage.Normal).offset / Float.BYTES;
        int color = mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
        float[] vertices = mesh.getVertices(new float[mesh.getNumVertices() * stride]);
        short[] indices = new short[mesh.getNumIndices()];
        if (indices.length > 0) { mesh.getIndices(indices); }
        for (int i = part.meshPart.offset; i < part.meshPart.offset + part.meshPart.size; i++) {
            int at = (indices.length == 0 ? i : Short.toUnsignedInt(indices[i])) * stride;
            int packed = NumberUtils.floatToIntColor(vertices[at + color]);
            if ((packed & 255) < 128) { branches[1]++; } // Open lead, selected before the cut-face branch.
            else if (vertices[at + normal + 2] < .5f) { branches[2]++; }
            else if ((packed >>> 24) < 128) { branches[3]++; }
            else { branches[0]++; }
        }
    }

    private static float[] iceOver(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera,
          float[] background, float depth) {
        output.scene().begin();
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearDepthf(depth);
        float a = background[3], scale = GpuSceneRadiance.SCALE;
        ScreenUtils.clear(background[0] * a * scale, background[1] * a * scale,
              background[2] * a * scale, a, true);
        terrain.renderTransparent(camera.camera);
        var depths = BufferUtils.newFloatBuffer(output.scene().getWidth() * output.scene().getHeight());
        Gdx.gl.glReadPixels(0, 0, output.scene().getWidth(), output.scene().getHeight(),
              GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depths);
        while (depths.hasRemaining()) { assertEquals(depth, depths.get(), 0, "Ice preserves opaque/picking depth"); }
        Gdx.gl.glClearDepthf(1);
        return output.linearPixels();
    }

    private static float[] readIcePixels(int width, int height) {
        var buffer = BufferUtils.newFloatBuffer(width * height * 4);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        float[] pixels = new float[buffer.capacity()];
        buffer.get(pixels);
        for (int i = 0; i < pixels.length; i++) { if (i % 4 != 3) { pixels[i] /= GpuSceneRadiance.SCALE; } }
        return pixels;
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    static BoardScene scene(int discovery) throws Exception { return scene(discovery, false); }

    private static BoardScene scene(int discovery, boolean openEdge) throws Exception {
        var task = new FutureTask<BoardScene>(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(7, 5);
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 5; y++) {
                    String contents = x == 1 ? "road:1:9" : "";
                    if (x == 1 && discovery > 0) { contents += ";black_ice:1"; }
                    if (x > 2 && x < 6 && y > 0 && y < 4) {
                        contents = "water:2" + (openEdge && x == 5 ? "" : ";ice:1");
                    }
                    if (x == 6 && y == 2) { contents = "ice:1"; }
                    var hex = new Hex(0, contents, "grass");
                    if (x == 1 && discovery > 1) { hex.getTerrain(Terrains.BLACK_ICE).detectBlackIce(); }
                    board.setHex(new Coords(x, y), hex);
                }
            }
            game.setBoard(board);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static int[] sample(GpuSceneTestOutput output, GpuTerrain terrain, BoardCamera camera, Coords coords) {
        output.draw(terrain, camera.camera, true);
        return read(camera, coords);
    }

    private static int[] read(BoardCamera camera, Coords coords) {
        return read(camera, coords, 48);
    }

    private static int[] read(BoardCamera camera, Coords coords, int size) {
        Vector3 screen = camera.camera.project(BoardGeometry.center(coords, 0));
        Pixmap image = ScreenUtils.getFrameBufferPixmap(Math.round(screen.x) - size / 2, Math.round(screen.y) - size / 2,
              size, size);
        try {
            int[] result = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) { result[y * size + x] = image.getPixel(x, y); }
            }
            return result;
        } finally { image.dispose(); }
    }

    private static double difference(int[] a, int[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] {24, 16, 8}) { sum += Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)); }
        }
        return sum / (a.length * 3);
    }
}
