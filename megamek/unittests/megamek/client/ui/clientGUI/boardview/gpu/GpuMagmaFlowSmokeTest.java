/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Track actual colour/heat pixels through the shipping projection and advection, including rotated falls. */
@Tag("on-demand")
class GpuMagmaFlowSmokeTest {
    private static final int SIZE = 256;

    @Test
    void moltenFeaturesFollowLocalCurrentsAndAllFallsDescend() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(SIZE, SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkMotion(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Molten flow direction", failure.get()); }
    }

    private static void checkMotion() {
        var assets = new GpuAssets();
        var quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        var shader = GpuGlsl.compile("magma-flow-motion-test", """
              in vec2 a_position;
              out vec2 v_position;
              void main() { v_position = a_position; gl_Position = vec4(a_position, 0.0, 1.0); }
              """, """
              in vec2 v_position;
              out vec4 fragColor;
              uniform vec3 u_face, u_right, u_up;
              uniform vec2 u_current;
              const float u_normalMaps = 0.0;
              """ + GpuShaderSource.magma(true) + """
              void main() {
                  vec3 world = vec3(7.0, -5.0, 3.0) + (u_right * v_position.x + u_up * v_position.y) * 6.0;
                  vec4 waves = vec4(0.0);
                  if (u_magmaOceanScale > 0.0) {
                      waves = texture(u_magmaOcean, world.xy * u_magmaOceanScale);
                      world.xy -= waves.zw * .30;
                  }
                  Volcanic material = magmaSurface(world, u_face, u_face,
                        magmaDrift(u_face, u_current, 1.0, waves), 1.0, waves);
                  // Independent channels catch a moving heat layer detached from the visible cooling skin.
                  fragColor = vec4(material.albedo.r, material.heat.x, 0.0, 1.0);
              }
              """);
        try {
            var maps = assets.fullSizeMaps();
            assertNotNull(maps, "Use the shipped lava maps");
            maps.data().bind(0); maps.color().bind(2);
            shader.bind();
            shader.setUniformi("u_fullSizeData", 0);
            shader.setUniformi("u_fullSizeColor", 2);
            shader.setUniformf("u_magmaLayers", GpuAssets.LAVA_COLOR, GpuAssets.LAVA_DATA);
            shader.setUniformi("u_magmaOcean", 1);
            shader.setUniformf("u_magmaOceanScale", 0);
            shader.setUniformf("u_magmaCoverage", assets.coverage(GpuAssets.magmaName(true)));
            Gdx.gl.glViewport(0, 0, SIZE, SIZE);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            for (int direction = 0; direction < 6; direction++) {
                double angle = direction * Math.PI / 3;
                float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
                shader.setUniformf("u_face", Vector3.Z);
                shader.setUniformf("u_right", Vector3.X);
                shader.setUniformf("u_up", Vector3.Y);
                shader.setUniformf("u_current", .025f * x, .025f * y);
                assertMotion(shader, quad, x, y, "river direction " + direction);
                for (float steepness : new float[] { .65f, 1f }) {
                    // World Z is screen up on the fall; a back face uses the same physical downhill direction.
                    var face = new Vector3(x * steepness, y * steepness,
                          (float) Math.sqrt(1 - steepness * steepness));
                    var right = new Vector3(-y, x, 0);
                    var up = new Vector3(face).crs(right);
                    shader.setUniformf("u_right", right);
                    shader.setUniformf("u_up", up);
                    for (int side : new int[] { 1, -1 }) {
                        shader.setUniformf("u_face", new Vector3(face).scl(side));
                        assertMotion(shader, quad, 0, -1, "fall " + direction + "/" + steepness + "/" + side);
                    }
                }
            }
            shader.setUniformf("u_face", Vector3.Z);
            shader.setUniformf("u_right", Vector3.X);
            shader.setUniformf("u_up", Vector3.Y);
            shader.setUniformf("u_current", .025f, 0);
            byte[] start = render(shader, quad, .17f);
            for (float time : new float[] { 1.17f, 2.57f, 4.17f, 8.17f, 12.17f }) {
                assertTrue(difference(start, render(shader, quad, time)) > 3,
                      "The material must advance, not repeat a stationary short loop at " + time);
            }
            // A short pair alone missed the one-second rocking regression. Check transport throughout several
            // resets, and sample across phase boundaries for a flash, with the actual colour and heat textures.
            for (int step = 0; step < 12; step++) {
                assertMotion(shader, quad, 1, 0, "sustained river at " + step, .17f + step * 1.07f);
            }
            double largestJump = 0;
            for (int step = 0; step < 160; step++) {
                float time = step * .05f;
                largestJump = Math.max(largestJump,
                      difference(render(shader, quad, time), render(shader, quad, time + .001f)));
            }
            System.out.println("Largest 1 ms material change across 8 seconds: " + largestJump);
            assertTrue(largestJump < .8, "Phase resets must not flash the material: " + largestJump);
            shader.setUniformf("u_current", 0, 0);
            assertArrayEquals(render(shader, quad, .17f), render(shader, quad, 12.27f),
                  "A closed pool has convection from its wave field, not an invented global current");
            var ocean = new GpuOcean(true);
            try {
                byte[] previous = null;
                for (int step = 0; step < 9; step++) {
                    float time = .17f + step;
                    ocean.update(time, Vector3.Zero, BoardAtmosphere.STANDARD_GRAVITY);
                    assertNotNull(ocean.texture(), "Use the real FFT in zero-current pools");
                    maps.data().bind(0); maps.color().bind(2);
                    ocean.texture().bind(1);
                    shader.bind();
                    shader.setUniformf("u_magmaOceanScale", 1f / GpuOcean.LAVA_PATCH);
                    byte[] now = render(shader, quad, time);
                    if (previous != null) {
                        double change = difference(previous, now);
                        System.out.println("Zero-current pool at " + time + " mean material change: " + change);
                        assertTrue(change > 6, "Both the skin and melt must visibly circulate in a still pool");
                    }
                    assertArrayEquals(now, render(shader, quad, time), "Rendering another view cannot advance the pool");
                    previous = now;
                }
            } finally { ocean.dispose(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { shader.dispose(); quad.dispose(); assets.dispose(); }
    }

    private static void assertMotion(ShaderProgram shader, Mesh quad, float x, float y, String description) {
        assertMotion(shader, quad, x, y, description, .17f);
    }

    private static void assertMotion(ShaderProgram shader, Mesh quad, float x, float y, String description, float time) {
        byte[] before = render(shader, quad, time), after = render(shader, quad, time + .1f);
        for (int channel = 0; channel < 2; channel++) {
            int bestX = 0, bestY = 0;
            double bestError = Double.POSITIVE_INFINITY;
            // Find the displacement of texture features, independently of the shader's UV/rotation conventions.
            for (int dy = -16; dy <= 16; dy++) {
                for (int dx = -16; dx <= 16; dx++) {
                    double error = 0;
                    for (int row = 32; row < SIZE - 32; row += 3) {
                        for (int col = 32; col < SIZE - 32; col += 3) {
                            int first = before[(row * SIZE + col) * 4 + channel] & 255;
                            int second = after[((row + dy) * SIZE + col + dx) * 4 + channel] & 255;
                            error += (first - second) * (first - second);
                        }
                    }
                    if (error < bestError) { bestError = error; bestX = dx; bestY = dy; }
                }
            }
            double distance = Math.hypot(bestX, bestY);
            System.out.println(description + " channel " + channel + " motion: " + bestX + ", " + bestY);
            assertTrue(distance >= 2, description + " must visibly transport its texture");
            assertTrue((bestX * x + bestY * y) / distance > .85,
                  description + " must carry colour and heat downstream; got " + bestX + ", " + bestY);
        }
    }

    private static double difference(byte[] first, byte[] second) {
        long sum = 0;
        for (int i = 0; i < first.length; i += 4) {
            sum += Math.abs((first[i] & 255) - (second[i] & 255));
            sum += Math.abs((first[i + 1] & 255) - (second[i + 1] & 255));
        }
        return sum / (first.length / 2.0);
    }

    private static byte[] render(ShaderProgram shader, Mesh quad, float time) {
        shader.setUniformf("u_magmaTime", time);
        quad.render(shader, GL20.GL_TRIANGLE_STRIP);
        return ScreenUtils.getFrameBufferPixels(0, 0, SIZE, SIZE, false);
    }
}
