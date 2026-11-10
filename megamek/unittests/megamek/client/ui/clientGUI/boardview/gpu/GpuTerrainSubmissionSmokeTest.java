/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reusing terrain material state must preserve mixed materials and transparency across camera views. */
@Tag("on-demand")
class GpuTerrainSubmissionSmokeTest {
    @Test
    void materialBatchingPreservesTerrainAndTransparentPixelsAcrossCameraViews() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                ModelBatch units = new ModelBatch(GpuUnitShader.provider());
                GpuSceneTestOutput target = new GpuSceneTestOutput(1280, 900);
                Model model = new ModelBuilder().createBox(14, 14, 10,
                      new Material(ColorAttribute.createDiffuse(Color.RED)),
                      VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                try {
                    BoardScene scene = scene();
                    terrain.update(scene);
                    terrain.setWind(BoardAtmosphere.Effects.NONE);
                    List<ModelInstance> occupants = new ArrayList<>();
                    for (Coords coords : List.of(new Coords(4, 4), new Coords(6, 6))) {
                        ModelInstance unit = new ModelInstance(model);
                        Vector3 center = BoardGeometry.center(coords, 0);
                        unit.transform.setToTranslation(center.x, center.y, BoardGeometry.groundZ(scene.tile(coords)) + 5.5f);
                        occupants.add(unit);
                    }
                    Field terrainBatch = GpuTerrain.class.getDeclaredField("batch");
                    terrainBatch.setAccessible(true);
                    ModelBatch batch = (ModelBatch) terrainBatch.get(terrain);
                    Field sorter = ModelBatch.class.getDeclaredField("sorter");
                    sorter.setAccessible(true);
                    RenderableSorter original = batch.getRenderableSorter();
                    RenderableSorter reference = new GpuOpaqueSorter(), grouped = original;
                    try {
                        for (float opacity : new float[] { 1, .4f }) {
                            // Set occupancy and water once; neither animation time nor opacity advances during comparisons.
                            terrain.animate(0, occupants, opacity);
                            for (boolean perspective : new boolean[] { false, true }) {
                                for (float tilt : new float[] { 0, 54.73561f, 80 }) {
                                    BoardCamera camera = new BoardCamera();
                                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                    camera.setPerspective(perspective);
                                    camera.setIsometric(false);
                                    camera.orbit(45, tilt);
                                    camera.fit(scene);
                                    String name = (perspective ? "perspective" : "orthographic") + "-" + tilt + "-" + opacity;
                                    // Warm both submission orders so shader creation, grass and tree caches are settled.
                                    for (RenderableSorter order : List.of(reference, grouped)) {
                                        sorter.set(batch, order);
                                        for (int warmup = 0; warmup < 30; warmup++) {
                                            draw(terrain, camera, units, occupants, target);
                                        }
                                    }
                                    sorter.set(batch, reference);
                                    byte[] before = pixels(terrain, camera, units, occupants, target);
                                    // Some procedural cliff fragments vary even with an unchanged reference draw.
                                    // Use only repeated reference draws, before and after batching, to identify them.
                                    boolean[] unstable = new boolean[before.length / 4];
                                    for (int repeat = 0; repeat < 32; repeat++) {
                                        referenceNoise(before, pixels(terrain, camera, units, occupants, target), unstable);
                                    }
                                    sorter.set(batch, grouped);
                                    byte[] after = pixels(terrain, camera, units, occupants, target);
                                    sorter.set(batch, reference);
                                    byte[] restored = pixels(terrain, camera, units, occupants, target);
                                    referenceNoise(before, restored, unstable);
                                    for (int repeat = 0; repeat < 32; repeat++) {
                                        referenceNoise(before, pixels(terrain, camera, units, occupants, target), unstable);
                                    }
                                    int unstableCount = 0;
                                    for (boolean value : unstable) { if (value) { unstableCount++; } }
                                    assertTrue(unstableCount <= 64, "Unstable reference pixels: " + name + " " + unstableCount);
                                    Difference groupedDifference = difference(before, after, unstable);
                                    System.out.printf("Terrain submission %s: grouped %s; unstable=%d; total pixels=%d%n",
                                          name, groupedDifference, unstableCount, before.length / 4);
                                    if (!groupedDifference.acceptable()) {
                                        save(before, name + "-reference.png");
                                        save(after, name + "-grouped.png");
                                        save(restored, name + "-reference-restored.png");
                                    }
                                    assertTrue(groupedDifference.acceptable(),
                                          "Shader grouping must preserve pixels: " + name + " " + groupedDifference);
                                }
                            }
                        }
                    } finally {
                        FrameBuffer.unbind();
                        sorter.set(batch, original);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    target.close();
                    units.dispose();
                    model.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Terrain submission parity", failure.get()); }
    }

    @Test
    void terrainProviderRoutesMaterialFactorsForPropsTacticalPagesAndAnimatedLiquids() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowSizeLimits(64, 64, -1, -1);
        configuration.setWindowedMode(64, 64);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkTerrainFactorDomains(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Terrain material factor domains", failure.get()); }
    }

    private static void checkTerrainFactorDomains() throws ReflectiveOperationException {
        var terrain = new GpuTerrain();
        var target = GpuHeatGlow.buffer(64, 64, true);
        var mesh = new Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.TexCoords(0));
        mesh.setVertices(new float[] { -1, -1, 0, 0, 0, 1, -1, 0, 1, 0, 1, 1, 0, 1, 1, -1, 1, 0, 0, 1 });
        mesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
        var pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixels.drawPixel(0, 0, 0x808080FF);
        var raw = new Texture(pixels);
        var color = GpuColorTexture.create(pixels, false);
        pixels.drawPixel(0, 0, 0xFFFFFFFF);
        var next = GpuColorTexture.create(pixels, false);
        pixels.dispose();
        var instances = new GpuTreeInstances((name, level) -> {
            throw new AssertionError("This probe instances its supplied prop mesh");
        });
        try {
            Field field = GpuTerrain.class.getDeclaredField("batch");
            field.setAccessible(true);
            ModelBatch batch = (ModelBatch) field.get(terrain);
            var camera = new OrthographicCamera(2, 2);
            camera.near = .1f;
            camera.far = 4;
            camera.position.set(0, 0, 2);
            camera.lookAt(0, 0, 0);
            camera.update();
            var environment = new Environment();
            float sample = 128f / 255, linearSample = GpuColor.linear(sample);
            // No normals: isolate the terrain provider's factor/texture contract from BRDF integration.
            for (boolean linear : new boolean[] { false, true }) {
                var part = new Renderable();
                part.meshPart.set("factor-domain", mesh, 0, 6, GL20.GL_TRIANGLES);
                part.material = new Material(ColorAttribute.createDiffuse(.5f, .5f, .5f, 1),
                      TextureAttribute.createDiffuse(color));
                if (linear) { part.material.set(new GpuLinearColors()); }
                part.environment = environment;
                float factor = linear ? .5f : GpuColor.linear(.5f);
                float expected = factor * linearSample * GpuSceneRadiance.SCALE;
                checkTerrainFactorDraw(batch, camera, target, part, factor, expected, "plain prop; linear=" + linear);

                instances.begin(GpuTreeInstances.Pass.COLOUR);
                instances.add(part);
                var gathered = new Array<Renderable>();
                instances.getRenderables(gathered, null);
                assertEquals(1, gathered.size);
                Renderable instanced = gathered.first();
                instanced.environment = environment;
                var shader = checkTerrainFactorDraw(batch, camera, target, instanced, factor, expected,
                      "instanced prop; linear=" + linear);
                assertTrue(shader.program.hasAttribute("a_instance0"), "Exercise the actual instanced prop program");

                part.material = part.material.copy();
                var frame = new GpuLiquidShader.Frame(next);
                frame.blend = .25f;
                part.material.set(frame);
                shader = checkTerrainFactorDraw(batch, camera, target, part, factor,
                      factor * (linearSample * .75f + .25f) * GpuSceneRadiance.SCALE,
                      "generic animated liquid; linear=" + linear);
                assertTrue(shader.program.hasUniform("u_liquidBlend"), "Exercise the actual generic liquid program");
            }
            var part = new Renderable();
            part.meshPart.set("tactical-factor-domain", mesh, 0, 6, GL20.GL_TRIANGLES);
            part.material = new Material(ColorAttribute.createDiffuse(.5f, .5f, .5f, 1));
            part.environment = null;
            // The texture has the same attribute mask in both domains. Revisit raw after sRGB to exercise reuse.
            for (Texture texture : new Texture[] { raw, color, raw }) {
                part.material.set(TextureAttribute.createDiffuse(texture));
                boolean linear = texture == color;
                float factor = linear ? GpuColor.linear(.5f) : .5f;
                float expected = linear ? GpuColor.display(factor * linearSample) : factor * sample;
                checkTerrainFactorDraw(batch, camera, target, part, factor, expected, "tactical page; sRGB=" + linear);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            instances.dispose();
            raw.dispose();
            color.dispose();
            next.dispose();
            mesh.dispose();
            target.dispose();
            terrain.dispose();
        }
    }

    private static DefaultShader checkTerrainFactorDraw(ModelBatch batch, OrthographicCamera camera,
          FrameBuffer target, Renderable part, float factor, float expected, String name) {
        part.shader = null;
        var shader = (DefaultShader) batch.getShaderProvider().getShader(part);
        target.begin();
        try {
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            batch.begin(camera);
            batch.render(part);
            batch.end();
            var uniform = BufferUtils.newFloatBuffer(4);
            Gdx.gl.glGetUniformfv(shader.program.getHandle(), shader.program.getUniformLocation("u_diffuseColor"), uniform);
            var output = BufferUtils.newFloatBuffer(4);
            Gdx.gl.glReadPixels(32, 32, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, output);
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(factor, uniform.get(channel), .000001f, "Actual terrain factor upload: " + name);
                assertEquals(expected, output.get(channel), .002f * Math.max(expected, .01f),
                      "Actual terrain fragment output: " + name);
            }
        } finally { target.end(); }
        return shader;
    }

    private static BoardScene scene() {
        BoardScene relief = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.GRASS);
        BufferedImage decal = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        var graphics = decal.createGraphics();
        graphics.setColor(new java.awt.Color(70, 120, 230, 140));
        graphics.fillRect(22, 15, 35, 35);
        graphics.dispose();
        BoardScene.Pixels marking = new BoardScene.Pixels(decal);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (BoardScene.Tile tile : relief.tiles()) {
            Coords coords = tile.coords();
            List<BoardScene.Feature> features = coords.equals(new Coords(4, 4))
                  ? List.of(new BoardScene.Feature("buildings/standard/building-hard",
                        0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING))
                  : coords.equals(new Coords(2, 4))
                        ? List.of(new BoardScene.Feature("vegetation/tree", 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.TREE))
                        : List.of();
            tiles.add(new BoardScene.Tile(coords, tile.elevation(), tile.waterDepth(), false, 0,
                  BoardScene.Surface.values()[coords.getX() % BoardScene.Surface.values().length],
                  tile.water() ? null : marking, null, features, List.of(), tile.liquid(), null, true));
        }
        return new BoardScene(0, relief.width(), relief.height(), tiles, List.of(), List.of(), -1, "", List.of(),
              new BoardScene.Light(-24, -30));
    }

    private static void draw(GpuTerrain terrain, BoardCamera camera, ModelBatch units, List<ModelInstance> occupants,
          GpuSceneTestOutput target) {
        terrain.renderShadows(camera.camera, occupants);
        target.begin(.2f, .26f, .31f, 1);
        terrain.render(camera.camera);
        units.begin(camera.camera);
        units.render(occupants, terrain.environment());
        units.end();
        terrain.renderTransparent(camera.camera);
        target.display();
    }

    private static byte[] pixels(GpuTerrain terrain, BoardCamera camera, ModelBatch units, List<ModelInstance> occupants,
          GpuSceneTestOutput target) {
        draw(terrain, camera, units, occupants, target);
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
        int varied = 0;
        for (int i = 4; i < pixels.length; i += 4) {
            if (pixels[i] != pixels[0] || pixels[i + 1] != pixels[1] || pixels[i + 2] != pixels[2]) { varied++; }
        }
        assertTrue(varied > 2000, "The comparison must contain visible rendered terrain");
        return pixels;
    }

    private static void save(byte[] pixels, String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Pixmap image = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
        try {
            image.getPixels().put(pixels);
            PixmapIO.writePNG(new FileHandle(new File(directory, "terrain-submission-" + name)), image, -1, true);
        } finally {
            image.dispose();
        }
    }

    private record Difference(int changedPixels, int maximumChannelDelta) {
        // Repeated frozen reference draws vary by 1 LSB at one cliff/shore pixel, even after 30 warmup frames.
        // Permit only that quantization noise in <=8 of 1,152,000 pixels; no shifted edges or structural tolerance.
        boolean acceptable() { return changedPixels <= 8 && maximumChannelDelta <= 1; }
    }

    private static void referenceNoise(byte[] before, byte[] sample, boolean[] unstable) {
        for (int p = 0; p < unstable.length; p++) {
            for (int channel = 0; channel < 3; channel++) {
                unstable[p] |= sample[p * 4 + channel] != before[p * 4 + channel];
            }
        }
    }

    private static Difference difference(byte[] expected, byte[] actual, boolean[] unstable) {
        assertEquals(expected.length, actual.length);
        int changedPixels = 0, maximum = 0;
        for (int pixel = 0; pixel < expected.length; pixel += 4) {
            if (unstable[pixel / 4]) { continue; }
            int delta = 0;
            for (int channel = 0; channel < 4; channel++) {
                delta = Math.max(delta, Math.abs(Byte.toUnsignedInt(expected[pixel + channel])
                      - Byte.toUnsignedInt(actual[pixel + channel])));
            }
            if (delta != 0) { changedPixels++; maximum = Math.max(maximum, delta); }
        }
        return new Difference(changedPixels, maximum);
    }
}
