/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The production packed-map shortcut must equal the full stencil at edges, slopes and mixed biomes. */
@Tag("on-demand")
class GpuBiomeCoverageSmokeTest {
    private static final int SIZE = 128;
    private static final int UNIFORM_BIT = 0x00100000;

    @Test
    void uniformPlateausMatchTheFullStencilWithoutChangingOtherPackedChannels() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(320, 240);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkCoverage(); checkWetnessGradient(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Biome coverage equivalence", failure.get()); }
    }

    private static void checkCoverage() throws ReflectiveOperationException {
        var mask = new GpuBiomeSurface(new GpuLavaLighting(null));
        var target = GpuHeatGlow.buffer(SIZE, SIZE);
        var mesh = new Mesh(true, 4, 0, VertexAttribute.Position());
        mesh.setVertices(new float[] { -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0 });
        var shader = GpuGlsl.compile("biome shortcut equivalence", """
              in vec3 a_position;
              void main() { gl_Position = vec4(a_position, 1.0); }
              """, """
              layout(location = 0) out vec4 fragColor;
              uniform float u_metre, u_levelHeight, u_height, u_water;
              uniform vec4 u_bounds;
              uniform int u_result;
              """ + GpuShaderSource.read("terrain-hexes.glsl") + GpuShaderSource.read("terrain-biome-mask.glsl") + """
              void main() {
                  vec3 world = vec3(u_bounds.xy + gl_FragCoord.xy / 128.0 * u_bounds.zw, u_height);
                  vec4 cover, fringe, liquids;
                  float tundra;
                  terrainCoverage(world, 14.0, u_water, cover, fringe, liquids, tundra);
                  fragColor = u_result == 0 ? cover : u_result == 1 ? fringe
                        : u_result == 2 ? vec4(tundra) : liquids;
              }
              """);
        try {
            assertTrue(shader.isCompiled(), shader.getLog());
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            for (var biome : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH, BoardScene.Biome.QUICKSAND,
                  BoardScene.Biome.MUD, BoardScene.Biome.TUNDRA)) {
                for (int mode = 0; mode < 6; mode++) {
                    int fixture = mode;
                    var scene = BoardScenes.scene(c -> tile(c, biome, fixture));
                    mask.update(scene);
                    var member = GpuBiomeSurface.class.getDeclaredField("pixels");
                    member.setAccessible(true);
                    int[] packed = ((int[]) member.get(mask)).clone();
                    assertMetadata(scene, packed, fixture);
                    // Clearing only the optimization bit restores the full production stencil, retaining
                    // identical biome, palette, ice, elevation and reached-quarter inputs.
                    int[] stripped = packed.clone();
                    for (int i = 0; i < stripped.length; i++) { stripped[i] &= ~UNIFORM_BIT; }
                    var fullStencil = new GpuLavaLighting(null);
                    fullStencil.setHexes(stripped, scene.width());
                    try {
                        for (float height : new float[] { -.151f, -.15f, 0, .15f, .151f, 1.3f }) {
                            // Whole board includes both column parities, all stencil-cell corners and the edge.
                            // A close window crosses shortcut/fallback cells with active fine-detail derivatives.
                            for (boolean close : new boolean[] { false, true }) {
                                for (int result = 0; result < 4; result++) {
                                    if (result == 2 && biome != BoardScene.Biome.TUNDRA
                                          || result == 3 && (fixture != 4 || height != 0)) { continue; }
                                    float water = result == 3 ? 0 : -10000;
                                    target.begin();
                                    shader.bind();
                                    shader.setUniformi("u_lavaLights", 0);
                                    shader.setUniformi("u_hexTable", mask.lights().hexRow(), mask.lights().hexWidth());
                                    shader.setUniformf("u_biomeBoard", (float) scene.width(), (float) scene.height());
                                    shader.setUniformf("u_metre", BoardRelief.metres(1));
                                    shader.setUniformf("u_levelHeight", BoardGeometry.level());
                                    shader.setUniformf("u_height", height);
                                    shader.setUniformf("u_water", water);
                                    shader.setUniformi("u_result", result);
                                    shader.setUniformf("u_bounds", close ? 82 : -4, close ? -119 : -244,
                                          close ? 12 : 220, close ? 12 : 248);
                                    float[] expected = sample(shader, mesh, fullStencil.texture());
                                    float[] actual = sample(shader, mesh, mask.lights().texture());
                                    target.end();
                                    float maximum = 0;
                                    for (int i = 0; i < expected.length; i++) {
                                        maximum = Math.max(maximum, Math.abs(expected[i] - actual[i]));
                                    }
                                    assertTrue(Float.isFinite(maximum), "Coverage must remain finite");
                                    assertEquals(0, maximum, .0005, biome + " fixture " + fixture
                                          + ", height " + height + ", result " + result + ", close " + close);
                                }
                            }
                        }
                    } finally { fullStencil.dispose(); }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { shader.dispose(); mesh.dispose(); target.dispose(); mask.lights().dispose(); }
    }

    private static float[] sample(ShaderProgram shader, Mesh mesh, Texture map) {
        map.bind(0);
        mesh.render(shader, GL20.GL_TRIANGLE_FAN);
        var buffer = BufferUtils.newFloatBuffer(SIZE * SIZE * 4);
        Gdx.gl.glReadPixels(0, 0, SIZE, SIZE, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        float[] values = new float[buffer.capacity()];
        buffer.get(values);
        return values;
    }

    private static void checkWetnessGradient() {
        var target = GpuHeatGlow.buffer(SIZE, SIZE);
        var mesh = new Mesh(true, 4, 0, VertexAttribute.Position());
        mesh.setVertices(new float[] { -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0 });
        var shader = GpuGlsl.compile("analytic wetland gradients", """
              in vec3 a_position;
              void main() { gl_Position = vec4(a_position, 1.0); }
              """, """
              layout(location = 0) out vec4 fragColor;
              uniform float u_metre, u_levelHeight;
              """ + GpuShaderSource.read("terrain-hexes.glsl") + GpuShaderSource.read("terrain-biome-mask.glsl") + """
              void main() {
                  vec2 world = (gl_FragCoord.xy - 64.0) * .5;
                  fragColor = vec4(biomeWetnessGradient(world), 1.0);
              }
              """);
        try {
            assertTrue(shader.isCompiled(), shader.getLog());
            target.begin();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            shader.bind();
            mesh.render(shader, GL20.GL_TRIANGLE_FAN);
            var buffer = BufferUtils.newFloatBuffer(SIZE * SIZE * 4);
            Gdx.gl.glReadPixels(0, 0, SIZE, SIZE, GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
            target.end();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    float px = (x + .5f - 64) * .5f, py = (y + .5f - 64) * .5f;
                    float lowX = px - .002f, highX = px + .002f, lowY = py - .002f, highY = py + .002f;
                    // Independent oracle: finite differences of the existing CPU vegetation-placement field,
                    // including its warp, rather than another implementation of the analytic derivative.
                    float dx = (BoardBiome.wetness(highX, py) - BoardBiome.wetness(lowX, py)) / (highX - lowX);
                    float dy = (BoardBiome.wetness(px, highY) - BoardBiome.wetness(px, lowY)) / (highY - lowY);
                    int i = (y * SIZE + x) * 4;
                    assertEquals(BoardBiome.wetness(px, py), buffer.get(i), .0005f, "CPU/GPU wetness agreement");
                    assertEquals(dx, buffer.get(i + 1), .0015f, "Wetness X gradient at " + px + ", " + py);
                    assertEquals(dy, buffer.get(i + 2), .0015f, "Wetness Y gradient at " + px + ", " + py);
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { shader.dispose(); mesh.dispose(); target.dispose(); }
    }

    private static void assertMetadata(BoardScene scene, int[] packed, int fixture) {
        int count = 0;
        for (var tile : scene.tiles()) {
            int pixel = packed[tile.coords().getY() * scene.width() + tile.coords().getX()];
            boolean shortcut = (pixel & UNIFORM_BIT) != 0;
            assertEquals(tile.blackIce() ? 3 : tile.frozen() ? 1 : 0, pixel >>> 8 & 255,
                  "Uniform coverage must not overwrite ice metadata");
            assertEquals(tile.liquid().present() ? 1 : 0, pixel >>> 16 & 15,
                  "Uniform coverage must not overwrite the liquid palette");
            if (shortcut) {
                count++;
                for (var near : tile.coords().allAtDistanceOrLess(2)) {
                    var neighbor = scene.tile(near);
                    assertTrue(neighbor != null, "Board edges must retain the full stencil");
                    assertEquals(BoardBiome.kind(tile), BoardBiome.kind(neighbor));
                    assertEquals(tile.elevation(), neighbor.elevation());
                    assertTrue(!neighbor.liquid().present());
                }
            }
        }
        if (fixture == 0 || fixture == 5) { assertTrue(count > 0, "Uniform interiors must exercise the shortcut"); }
        else { assertTrue(count < 25, "A mixed neighbourhood must disable some otherwise uniform cells"); }
    }

    private static BoardScene.Tile tile(Coords coords, BoardScene.Biome biome, int fixture) {
        boolean changed = coords.equals(new Coords(4, 4));
        int elevation = fixture == 1 && changed ? 1 : 0;
        var kind = fixture == 2 && changed ? BoardScene.Biome.NONE : biome;
        boolean frozen = fixture == 3 && changed;
        var liquid = fixture == 4 && changed ? BoardLiquid.WATER : BoardLiquid.NONE;
        return new BoardScene.Tile(coords, elevation, liquid.present() ? 1 : -1, frozen, 0, BoardScene.Surface.GRASS,
              null, null, List.of(), List.of(), liquid, null, true,
              BoardRoad.Kind.NONE, BoardFireSmoke.NONE, kind, false, fixture == 5 && changed);
    }
}
