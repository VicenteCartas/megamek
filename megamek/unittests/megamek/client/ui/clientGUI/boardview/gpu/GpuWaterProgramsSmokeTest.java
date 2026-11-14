/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Material masks alone cannot distinguish pools, curtains, particles and board-edge water. */
@Tag("on-demand")
class GpuWaterProgramsSmokeTest {
    @Test
    void modesRemainDistinctInTheCacheAndAfterSharedSourceReload() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkPrograms(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Water program selection and reload", failure.get()); }
    }

    private static void checkPrograms(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        var pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        var texture = new Texture(pixels);
        pixels.dispose();
        var material = new Material(TextureAttribute.createDiffuse(texture),
              new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA));
        long attributes = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates;
        var model = new ModelBuilder().createBox(1, 1, 1, material, attributes);
        try {
            var tile = BoardScenes.newTile(new Coords(0, 0), 0, 1, false, 0, BoardScene.Surface.ROCK, List.of(),
                  List.of());
            var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of());
            var surface = new BoardSurface(scene, scene.tiles().getFirst());
            var pool = new GpuWaterShader(scene, surface, true, false, null);
            var fall = new GpuWaterShader(scene, surface, true, true, null);
            var environment = new Environment();
            environment.set(ColorAttribute.createAmbientLight(.4f, .4f, .4f, 1));
            environment.add(new DirectionalLight().set(1, 1, 1, -.4f, -.3f, -1));
            var parts = new ArrayList<Renderable>();
            for (var water : List.of(pool, fall, fall.spray(), pool.cut(), pool.cut().beyond(), pool.depth())) {
                var part = new ModelInstance(model).getRenderable(new Renderable());
                part.material = new Material(material);
                part.material.set(water);
                part.environment = environment;
                parts.add(part);
            }
            var field = GpuTerrain.class.getDeclaredField("batch");
            field.setAccessible(true);
            var provider = ((ModelBatch) field.get(terrain)).getShaderProvider();
            checkCache(provider, parts);
            for (String source : List.of("water-lighting.glsl", "water-interactions.glsl")) {
                var result = manager.apply(Map.of(source, GpuShaderSource.read(source) + "\n// reload shared water\n"));
                assertTrue(result.success(), result.message());
                assertTrue(result.updated() >= parts.size(), "Reload each live water mode");
                checkCache(provider, parts);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { terrain.dispose(); model.dispose(); texture.dispose(); }
    }

    private static void checkCache(ShaderProvider provider, List<Renderable> parts) {
        List<Shader> shaders = parts.stream().map(provider::getShader).toList();
        for (int index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            var shader = shaders.get(index);
            for (int other = 0; other < parts.size(); other++) {
                assertEquals(index == other, shader.canRender(parts.get(other)), "Reject a different water mode");
            }
            var copy = new Renderable().set(part);
            copy.material = new Material(part.material);
            assertSame(shader, provider.getShader(copy), "A copied material must reuse the matching program");
            var mode = part.material.get(GpuWaterShader.class, GpuWaterShader.TYPE).mode;
            var program = ((BaseShader) GpuShaderProvider.unwrap(shader)).program;
            assertEquals(mode == GpuWaterShader.Mode.SURFACE || mode == GpuWaterShader.Mode.FALL,
                  program.getUniformLocation("u_waterOcean0") >= 0, "Only surfaces and crests need the wave simulation");
            assertEquals(mode == GpuWaterShader.Mode.SURFACE || mode == GpuWaterShader.Mode.DEPTH
                        || mode == GpuWaterShader.Mode.CUT || mode == GpuWaterShader.Mode.BEYOND,
                  program.getUniformLocation("u_waterShape") >= 0,
                  "The surface, its depth prepass and both sides of the board-edge section share one wave displacement");
            assertEquals(mode == GpuWaterShader.Mode.SURFACE || mode == GpuWaterShader.Mode.FALL,
                  program.getUniformLocation("u_waderCount") >= 0, "Only surfaces and crests need unit interactions");
            assertEquals(mode == GpuWaterShader.Mode.SPRAY, program.getUniformLocation("u_cameraUp") >= 0,
                  "Only spray needs particle billboarding");
        }
    }
}
