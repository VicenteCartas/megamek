/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The same cast panel must retain its material dimensions and relief when rotated on the board. */
@Tag("on-demand")
class GpuConcreteProjectionSmokeTest {
    @Test
    void rotatedWallsAndSlopesKeepTheirSlabsAndNormalRelief() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(512, 256);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkProjection(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Concrete texture orientation", failure.get()); }
    }

    private static void checkProjection() throws Exception {
        var assets = new GpuAssets();
        var quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        var pixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixels.setColor(.5f, .5f, .5f, 1);
        pixels.fill();
        var noise = new Texture(pixels);
        pixels.dispose();
        // Optional historical source proves that the regression check detects the old axis projection.
        String source = System.getProperty("megamek.gpu.concreteProjectionSource", "");
        // The flat noise of a texture this harness binds, in the shipped concrete's one read of the surface noise.
        String concrete = source.isBlank() ? GpuShaderSource.read("terrain-concrete.glsl")
              .replace("texture(u_surfaceNoise, rainNoiseAt(vec2(u / 3.1 + window.x * 7.0, z / 45.0))).r", ".5") : Files.readString(Path.of(source))
              .replace("mapTexel(u_sculptLayers.w + 1.0,", "normalTexel(u_sculptLayers.w + 1.0,");
        String roughness = source.isBlank() ? "#define FINISH_COUNT 1\n#define ROAD_FINISH 1\n"
              + GpuShaderSource.read("material-finish.glsl") + GpuShaderSource.read("finish-arrays.glsl") : "";
        String call = source.isBlank()
              ? "concreteSlab(world, dFdx(world), dFdy(world), face, vec3(u_direction, 0.0), local.y, 12.0 - local.y, "
                    + "albedo, normal, occlusion, cavity, finish); roughness = finish.roughness;"
              : "concreteSlab(world, face, vec3(u_direction, 0.0), local.y, 12.0 - local.y, "
                    + "albedo, normal, occlusion, cavity);";
        var shader = GpuGlsl.compile("concrete-projection-test", """
              in vec2 a_position;
              void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
              """, """
              out vec4 fragColor;
              uniform sampler2DArray u_terrainLayers, u_terrainData;
              uniform sampler2D u_rainNoise;
              uniform vec2 u_direction;
              uniform float u_slope;
              uniform int u_mode;
              const float u_metre = 1.0, u_levelHeight = 6.0, u_normalMaps = 1.0;
              const vec4 u_sculptTiles = vec4(8.0), u_sculptLayers = vec4(0.0);
              const mat2 TURN = mat2(1.0);
              """ + GpuShaderSource.read("terrain-projection.glsl") + roughness + concrete + """
              void main() {
                  // View each face head-on in its own coordinates, isolating projection from perspective and light.
                  vec2 local = gl_FragCoord.xy * .04 + vec2(.137, .219);
                  vec3 across = vec3(-u_direction.y, u_direction.x, 0.0);
                  vec3 face = normalize(vec3(u_direction, u_slope));
                  vec3 down = normalize(vec3(u_direction * u_slope, -1.0));
                  vec3 world = across * local.x + vec3(u_direction * (12.0 - local.y) * u_slope, local.y);
                  pixelMetres = .04;
                  vec3 albedo = vec3(0.0), normal = face;
                  float occlusion = 1.0, cavity = 1.0, roughness = .9;
                  FINISH_DECLARATION
                  CONCRETE_CALL
                  vec3 detail = vec3(dot(normal, across), dot(normal, down), dot(normal, face));
                  fragColor = vec4(u_mode == 2 ? vec3(roughness) : u_mode == 0 ? albedo : detail * .5 + .5, 1.0);
              }
              """.replace("CONCRETE_CALL", call)
              .replace("FINISH_DECLARATION", source.isBlank() ? "Finish finish = Finish(.9, 0.0, 0.0, vec3(0.0));" : ""));
        try {
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            var maps = assets.sculptArray(List.of("concrete-bank"));
            maps.color().bind(0);
            maps.data().bind(3);
            noise.bind(1);
            if (source.isBlank()) {
                assets.finishArrays(List.of("terrain/surfaces/concrete-bank"), 1).maps().bind(2);
            }
            shader.bind();
            shader.setUniformi("u_terrainLayers", 0);
            if (shader.hasUniform("u_terrainData")) { shader.setUniformi("u_terrainData", 3); }
            if (shader.hasUniform("u_rainNoise")) { shader.setUniformi("u_rainNoise", 1); }
            if (source.isBlank()) {
                shader.setUniformi("u_surfaceLayers", 2);
                shader.setUniformf("u_finishSurface[0]", .9f, 0, 0, -1);
                // The probe outputs roughness alone; the compiler may drop the emission it never shows.
                if (shader.hasUniform("u_finishEmission[0]")) { shader.setUniformf("u_finishEmission[0]", 0, 0, 0, -1); }
                if (shader.hasUniform("u_finishGlow[0]")) { shader.setUniformf("u_finishGlow[0]", 0, 0, 0); }
            }
            for (float slope : new float[] { 0, .5f, 1 }) {
                shader.setUniformf("u_slope", slope);
                for (int mode = 0; mode < 2; mode++) {
                    shader.setUniformi("u_mode", mode);
                    shader.setUniformf("u_direction", 1, 0);
                    quad.render(shader, GL20.GL_TRIANGLE_STRIP);
                    byte[] reference = ScreenUtils.getFrameBufferPixels(false);
                    for (int angle : new int[] { 30, 45, 60, 90, 135, 180, 240, 300 }) {
                        double radians = Math.toRadians(angle);
                        shader.setUniformf("u_direction", (float) Math.cos(radians), (float) Math.sin(radians));
                        quad.render(shader, GL20.GL_TRIANGLE_STRIP);
                        double difference = meanDifference(reference, ScreenUtils.getFrameBufferPixels(false));
                        String description = "slope=" + slope + ", mode=" + mode + ", angle=" + angle;
                        assertTrue(difference < .05, description + ": mean byte difference " + difference);
                    }
                }
            }
            if (source.isBlank()) {
                // Exercise the real slab implementation with uniform authored roughness, fully rough included.
                shader.setUniformi("u_mode", 2);
                for (float scalar : new float[] { .15f, .85f, 1 }) {
                    shader.setUniformf("u_finishSurface[0]", scalar, 0, 0, -1);
                    quad.render(shader, GL20.GL_TRIANGLE_STRIP);
                    byte[] response = ScreenUtils.getFrameBufferPixels(false);
                    for (int channel = 0; channel < 3; channel++) {
                        assertEquals(scalar * 255, response[channel] & 255, 1, "Cast slab must render its authored roughness");
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            shader.dispose();
            noise.dispose();
            quad.dispose();
            assets.dispose();
        }
    }

    private static double meanDifference(byte[] a, byte[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i += 4) {
            for (int c = 0; c < 3; c++) { sum += Math.abs((a[i + c] & 255) - (b[i + c] & 255)); }
        }
        return sum / (a.length * .75);
    }
}
