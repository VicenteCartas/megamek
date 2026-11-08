/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Read back the production GPU mask, then render the same blue/green/red contacts above and below water. */
@Tag("on-demand")
class GpuLiquidBlendSmokeTest {
    private static final Coords CENTER = new Coords(4, 4);
    private static final BoardLiquid[] LIQUIDS = { BoardLiquid.WATER, new BoardLiquid(BoardLiquid.Kind.WATER, "mars", 0),
          new BoardLiquid(BoardLiquid.Kind.WATER, "volcano", 0), new BoardLiquid(BoardLiquid.Kind.HAZARDOUS, "", 0) };

    @Test
    void everyPalettePairAndThreeWayContactBlendsWithoutMixingThroughBarriers() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "liquid-blends");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var tuning = BoardGeometry.tuning();
        var lodTuning = TerrainLod.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var mask = new GpuBiomeSurface(new GpuLavaLighting(null));
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try (var probe = new Probe(mask)) {
                    BoardScenes.tune(.94f, true);
                    for (int a = 0; a < 4; a++) {
                        for (int b = a + 1; b < 4; b++) {
                            int first = a, second = b;
                            var scene = BoardScenes.scene(c -> tile(c, c.equals(CENTER) ? first : second, 0, false));
                            mask.update(scene);
                            assertEquals(1, probe.sample(BoardGeometry.center(CENTER, 0))[first], .005);
                            for (int direction = 0; direction < 6; direction++) {
                                var neighbor = CENTER.translated(direction);
                                Vector3 p = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(neighbor, 0), .5f);
                                float[] middle = probe.sample(p);
                                assertTrue(middle[first] > .2 && middle[second] > .2, "Both contacting palettes contribute");
                                assertEquals(1, sum(middle), .01);
                                Vector3 side = BoardGeometry.center(neighbor, 0).sub(BoardGeometry.center(CENTER, 0)).nor().scl(.001f);
                                var left = probe.sample(new Vector3(p).sub(side));
                                var right = probe.sample(new Vector3(p).add(side));
                                for (int channel = 0; channel < 4; channel++) { assertEquals(left[channel], right[channel], .005); }
                                var below = probe.sample(new Vector3(p).add(0, 0, -BoardRelief.metres(6)));
                                for (int channel = 0; channel < 4; channel++) { assertEquals(middle[channel], below[channel], .005); }
                            }
                        }
                    }
                    Coords next = CENTER.translated(2);
                    for (int barrier = 0; barrier < 3; barrier++) {
                        int mode = barrier;
                        var scene = BoardScenes.scene(c -> c.equals(CENTER) ? tile(c, 0, 0, false)
                              : c.equals(next) ? tile(c, mode == 2 ? -1 : 3, mode == 0 ? 1 : 0, mode == 1)
                              : c.equals(new Coords(0, 0)) ? tile(c, 3, 0, false)
                              : BoardScenes.tile(c, BoardScene.Biome.NONE, 0));
                        mask.update(scene);
                        var p = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
                        // Ice and magma carry no palette, so this lone palette is not flagged and the stencil returns
                        // nothing: the water and bed shaders then take the water's own palette.
                        float[] mixture = probe.sample(p);
                        if (sum(mixture) < .5f) { mixture = new float[] { 1, 0, 0, 0 }; }
                        assertEquals(1, mixture[0], .005, "Different levels, ice and magma do not contaminate this water");
                    }
                    Coords red = CENTER.translated(BoardGeometry.edgeDirection(1));
                    Coords green = CENTER.translated(BoardGeometry.edgeDirection(2));
                    var cornerScene = BoardScenes.scene(c -> tile(c, c.equals(red) ? 2 : c.equals(green) ? 3 : 0, 0, false));
                    mask.update(cornerScene);
                    var corner = probe.sample(BoardGeometry.corner(CENTER, 0, 2));
                    assertTrue(corner[0] > .1 && corner[2] > .1 && corner[3] > .1, "Three palettes mix at the shared corner");

                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    camera.setIsometric(true);
                    camera.camera.zoom = .18f;
                    camera.center(BoardGeometry.center(CENTER, 0));
                    for (var lod : TerrainLod.values()) {
                        TerrainLod.tune(switch (lod) {
                            case FULL -> new TerrainLod.Tuning(1, 1);
                            case MEDIUM -> new TerrainLod.Tuning(100_000, 1);
                            case COARSE -> new TerrainLod.Tuning(100_000, 1000);
                            case DISTANT -> new TerrainLod.Tuning(100_000, 100_000);
                        });
                        var scene = BoardScenes.scene(c -> tile(c, c.getX() < 4 ? 0 : c.getY() < 4 ? 2 : 3, 0, false));
                        capture(terrain, frame, camera, scene);
                        GpuReviewFrame.save(new File(output, "blue-red-green-" + lod + ".png"));
                        frame.render(terrain, camera, scene, false);
                        GpuReviewFrame.save(new File(output, "bed-blue-red-green-" + lod + ".png"));
                    }
                    TerrainLod.tune(lodTuning);
                    for (int palette = 1; palette < 4; palette++) {
                        int second = palette;
                        var scene = BoardScenes.scene(c -> tile(c, c.getX() < 4 ? 0 : second, 0, false));
                        capture(terrain, frame, camera, scene);
                        GpuReviewFrame.save(new File(output, "blue-to-" + LIQUIDS[palette].kind() + "-" + palette + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose(); mask.lights().dispose(); frame.dispose();
                    BoardGeometry.tune(tuning);
                    TerrainLod.tune(lodTuning);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Liquid palette blending", failure.get()); }
    }

    private static void capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) throws Exception {
        terrain.update(scene, camera.camera);
        GpuReviewFrame.settle(terrain, null, scene, camera);
        terrain.animate(.15f, List.of());
        frame.render(terrain, camera, scene);
    }

    private static float sum(float[] values) { float sum = 0; for (float value : values) { sum += value; } return sum; }

    private static BoardScene.Tile tile(Coords coords, int palette, int elevation, boolean frozen) {
        var base = BoardScenes.tile(coords, BoardScene.Surface.DIRT, elevation, 1, 0);
        var hex = new Hex(elevation);
        if (palette < 0) { hex.addTerrain(new Terrain(Terrains.MAGMA, 2)); }
        else {
            hex.addTerrain(new Terrain(Terrains.WATER, 1));
            if (palette == 3) { hex.addTerrain(new Terrain(Terrains.HAZARDOUS_LIQUID, 1)); }
            hex.setTheme(LIQUIDS[palette].theme());
        }
        if (frozen) { hex.addTerrain(new Terrain(Terrains.ICE, 1)); }
        return new BoardScene.Tile(coords, elevation, 1, frozen, 0, base.surface(), null, null,
              List.of(), List.of(), BoardLiquid.capture(hex), null, BoardFeatures.detailedGround(hex, Map.of()));
    }

    /** Uses the actual production shader source and actual packed map, not a second CPU implementation. */
    private static final class Probe implements AutoCloseable {
        private final GpuBiomeSurface mask;
        private final FrameBuffer target = new FrameBuffer(Pixmap.Format.RGBA8888, 2, 2, false);
        private final Mesh mesh = new Mesh(true, 4, 0, VertexAttribute.Position());
        private final ShaderProgram shader;

        Probe(GpuBiomeSurface mask) {
            this.mask = mask;
            shader = GpuGlsl.compile("liquid coverage probe", "in vec3 a_position; void main(){gl_Position=vec4(a_position,1);}",
                  "#define biomeMaskFlag\nuniform float u_metre, u_levelHeight; uniform vec3 u_point;\n"
                        + "layout(location = 0) out vec4 result;\n"
                        + GpuShaderSource.read("terrain-hexes.glsl") + GpuShaderSource.read("terrain-biome-mask.glsl")
                        + "\nvoid main(){result=liquidCoverage(u_point,0.0);}");
            assertTrue(shader.isCompiled(), shader.getLog());
            mesh.setVertices(new float[] { -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0 });
        }

        float[] sample(Vector3 world) {
            target.begin();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            shader.bind();
            mask.lights().texture().bind(0);
            shader.setUniformi("u_lavaLights", 0);
            shader.setUniformi("u_hexTable", mask.lights().hexRow(), mask.lights().hexWidth());
            shader.setUniformf("u_biomeBoard", (float) mask.width(), (float) mask.height());
            // Biome height uniforms can be optimized out of this liquid-only diagnostic.
            if (shader.hasUniform("u_metre")) { shader.setUniformf("u_metre", BoardRelief.metres(1)); }
            if (shader.hasUniform("u_levelHeight")) { shader.setUniformf("u_levelHeight", BoardGeometry.level()); }
            shader.setUniformf("u_point", new Vector3(world).scl(1 / BoardRelief.metres(1)));
            mesh.render(shader, GL20.GL_TRIANGLE_FAN);
            var pixels = Pixmap.createFromFrameBuffer(0, 0, 1, 1);
            int rgba;
            try { rgba = pixels.getPixel(0, 0); } finally { pixels.dispose(); target.end(); }
            return new float[] { (rgba >>> 24) / 255f, ((rgba >>> 16) & 255) / 255f, ((rgba >>> 8) & 255) / 255f, (rgba & 255) / 255f };
        }

        @Override
        public void close() { mesh.dispose(); target.dispose(); shader.dispose(); }
    }
}
