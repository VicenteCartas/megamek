/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Elevation styling keeps level zero's pigment exact and bounded texture contrast at every other level. */
@Tag("on-demand")
class GpuTerrainGradeSmokeTest {
    private static final List<String> MATERIALS = List.of("snow", "dirt", "grass", "fungus", "mixed");
    private static final int[] LEVELS = { -1, 0, 2, 3, 8 };

    @Test
    void elevationSettingPreservesPigmentAndBoundedVariation() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> {
                        try (var gain = new GainProbe()) {
                            checkAuthoredGain(gain);
                            checkGradeDomain(gain);
                            review(manager);
                        } catch (Throwable error) { failure.set(error); }
                    });
                } finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain elevation styling", failure.get()); }
    }

    private static void checkAuthoredGain(GainProbe probe) {
        // Independent reference: HEAD multiplied authored RGB by gain, then used a gamma-2.2 lighting boundary.
        // The equivalent linear coefficient is gain^2.2. This tests the actual shared GLSL, not a Java polynomial.
        for (int step = 0; step <= 128; step++) {
            float gain = .6f + .8f * step / 128;
            float[] gains = { gain, 2 - gain, 1 };
            float[] actual = probe.read(gains);
            for (int channel = 0; channel < 3; channel++) {
                double expected = Math.pow(gains[channel], 2.2);
                assertEquals(expected, actual[channel], expected * .0027,
                      "Authored gain retains its linear response; bound includes cubic and half-float rounding");
                assertTrue(actual[channel] > 0 && Float.isFinite(actual[channel]));
            }
            assertEquals(1, actual[2], "A neutral pigment control remains exact identity");
        }
    }

    private static void checkGradeDomain(GainProbe probe) {
        float[][] pigments = { { 0, 0, 0 }, { 1, 1, 1 }, { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 },
              { .125f, .25f, .5f }, { .5f, .125f, .25f }, { .875f, .9375f, .96875f } };
        for (float[] pigment : pigments) {
            for (float fungus : new float[] { 0, .5f, 1 }) {
                for (float level : new float[] { -1000, -1, 0, 3, 1000 }) {
                    var styled = probe.grade(pigment, level, fungus);
                    for (int channel = 0; channel < 3; channel++) {
                        assertTrue(Float.isFinite(styled[channel]) && styled[channel] >= 0 && styled[channel] <= 1,
                              "Completed pigment stays in gamut, including saturated and black/white endpoints");
                        if (level == 0) { assertEquals(pigment[channel], styled[channel], "Level zero is identity"); }
                    }
                }
            }
        }
        // A bright ramp must remain ordered and detailed. The independent lower slope follows from the maximum
        // .6-stop ordinary cue; each RGBA16F endpoint can round by one adjacent half step.
        for (float level : new float[] { 2, 3, 8, 1000 }) {
            float previous = -1;
            for (int step = 0; step <= 16; step++) {
                float peak = .9f + step * .098f / 16;
                float[] styled = probe.grade(new float[] { peak * .85f, peak * .95f, peak }, level, 0);
                assertTrue(styled[2] > previous, "Bright texture samples must not collapse into a clipped plateau");
                previous = styled[2];
            }
        }
        var chromatic = new float[] { .125f, .25f, .5f };
        for (float level : new float[] { -0.000001f, 0.000001f }) {
            var styled = probe.grade(chromatic, level, 1);
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(chromatic[channel], styled[channel], halfStep(chromatic[channel]) * 1.01,
                      "The two elevation branches meet continuously at zero");
            }
        }
        var ordinary = probe.grade(new float[] { .25f, .25f, .25f }, 3, 0);
        var fungal = probe.grade(new float[] { .25f, .25f, .25f }, 3, 1);
        assertTrue(fungal[0] > ordinary[0] + .005, "Fungus retains its intentionally stronger elevation cue");
        for (float fungus : new float[] { 0, 1 }) {
            var sunk = probe.grade(new float[] { .5f, .5f, .5f }, -1, fungus);
            assertTrue(sunk[1] < .5f * .75f, "Level -1 reads a clear step darker than level 0 (fungus " + fungus + ")");
        }
    }

    private static void review(GpuShaderManager manager) throws Exception {
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer");
        if (expectedRenderer != null) { assertEquals(expectedRenderer, renderer); }
        System.out.println("Elevation pigment renderer=" + renderer);
        var outputPath = Path.of(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "elevation-pigment");
        Files.createDirectories(outputPath);
        var rows = new StringBuilder("material,level,covered,mixed,cool,changed,pre_luma,post_luma,"
              + "pre_peak_variance,post_peak_variance,bright_pixels,pre_bright_variance,post_bright_variance,"
              + "max_difference\n");
        var sources = new LinkedHashMap<String, String>();
        sources.put("terrain-sculpt.frag", sculptProbe(GpuShaderSource.readDisk("terrain-sculpt.frag")));
        var installed = manager.apply(sources);
        assertTrue(installed.success(), installed.message());
        var terrain = new GpuTerrain();
        try (var output = new GpuSceneTestOutput()) {
            var camera = new OrthographicCamera();
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            camera.near = 1;
            camera.far = 10000;
            camera.viewportWidth = output.scene().getWidth() * BoardRelief.metres(.1f);
            camera.viewportHeight = output.scene().getHeight() * BoardRelief.metres(.1f);
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            terrain.setGrass(false); // These 25 shelves measure ground.
            for (String material : MATERIALS) {
                for (int level : LEVELS) {
                    terrain.update(scene(material, level));
                    camera.position.set(BoardGeometry.center(BoardSurfaceBlendTest.CENTER, level)).add(0, 0, 5000);
                    camera.update();
                    output.draw(terrain, camera, false);
                    float[][] channels = gradeFrames(terrain, output, camera);
                    check(material, level, .25f, channels, rows);
                    Files.writeString(outputPath.resolve("pigment.csv"), rows);
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
            var restored = manager.apply(Map.of());
            assertTrue(restored.success(), restored.message());
        }
    }

    /** The completed pigment before grading, the graded pigment and the coverage tags of one terrain build. */
    private static float[][] gradeFrames(GpuTerrain terrain, GpuSceneTestOutput output,
          OrthographicCamera camera) throws Exception {
        float[] completed = read(terrain, output, camera, 0);
        float[] styled = read(terrain, output, camera, 1);
        float[] coverage = read(terrain, output, camera, 2);
        assertArrayEquals(styled, read(terrain, output, camera, 1),
              "Grading repeats exactly without rebuilding geometry");
        return new float[][] { completed, styled, coverage };
    }

    private static float[] read(GpuTerrain terrain, GpuSceneTestOutput output, OrthographicCamera camera,
          int mode) throws Exception {
        setProbe(terrain, mode);
        output.draw(terrain, camera, false);
        return output.linearPixels();
    }

    private static void check(String material, int level, float tag, float[][] values, StringBuilder rows) {
        int covered = 0, mixed = 0, cool = 0, changed = 0, bright = 0;
        double preLuma = 0, postLuma = 0, preSum = 0, postSum = 0, preSquare = 0, postSquare = 0;
        double brightPreSum = 0, brightPostSum = 0, brightPreSquare = 0, brightPostSquare = 0, maximumDifference = 0;
        String label = material + " level=" + level;
        for (int pixel = 0; pixel < values[0].length; pixel += 4) {
            for (float[] frame : values) {
                for (int channel = 0; channel < 4; channel++) {
                    if (!Float.isFinite(frame[pixel + channel])) {
                        throw new AssertionError(label + " nonfinite pixel=" + pixel / 4 + " channel=" + channel);
                    }
                }
                assertEquals(values[0][pixel + 3], frame[pixel + 3], 0, "Probe modes retain actual coverage");
            }
            if (values[0][pixel + 3] != tag) { continue; }
            covered++;
            float mixture = values[2][pixel + 1];
            if (mixture > .1f && mixture < .9f) { mixed++; }
            if (values[0][pixel + 2] > values[0][pixel] + .002f) { cool++; }
            double peakBefore = 0, peakAfter = 0;
            boolean altered = false;
            for (int channel = 0; channel < 3; channel++) {
                float pre = values[0][pixel + channel], post = values[1][pixel + channel];
                assertTrue(pre >= 0 && pre <= 1 && post >= 0 && post <= 1, "Bounded actual pigment");
                float difference = Math.abs(pre - post);
                if (level == 0) { assertEquals(pre, post, "Enabled styling is exact identity at level zero"); }
                if (difference > halfStep(pre) * 2) { altered = true; }
                maximumDifference = Math.max(maximumDifference, difference);
                peakBefore = Math.max(peakBefore, pre);
                peakAfter = Math.max(peakAfter, post);
            }
            if (altered) { changed++; }
            preLuma += luma(values[0], pixel);
            postLuma += luma(values[1], pixel);
            preSum += peakBefore; postSum += peakAfter;
            preSquare += peakBefore * peakBefore; postSquare += peakAfter * peakAfter;
            // A material's own completed input selects the highlight set. Selection never depends on styled output.
            if (peakBefore > .9 && peakBefore < .998) {
                bright++;
                brightPreSum += peakBefore; brightPostSum += peakAfter;
                brightPreSquare += peakBefore * peakBefore; brightPostSquare += peakAfter * peakAfter;
            }
            if (level > 0 && !material.equals("fungus") && !material.equals("mixed")) {
                assertTrue(peakAfter + .001 >= peakBefore, "The ordinary uphill cue must not darken highlights");
                // Collinearity tests hue preservation without reproducing the exposure curve in Java.
                for (int channel = 0; channel < 2; channel++) {
                    double left = values[1][pixel + channel] * (double) values[0][pixel + 2];
                    double right = values[0][pixel + channel] * (double) values[1][pixel + 2];
                    double error = productError(values[1][pixel + channel], values[0][pixel + 2])
                          + productError(values[0][pixel + channel], values[1][pixel + 2]);
                    assertEquals(left, right, error * 1.01,
                          "Ordinary uphill styling preserves RGB ratios, including blue snow");
                }
            }
        }
        assertTrue(covered > 1000,
              label + " must sample the intended real material: covered=" + covered + ", mixed=" + mixed);
        double preVariance = variance(preSum, preSquare, covered), postVariance = variance(postSum, postSquare, covered);
        double preBrightVariance = variance(brightPreSum, brightPreSquare, bright);
        double postBrightVariance = variance(brightPostSum, brightPostSquare, bright);
        String row = material + "," + level + "," + covered + "," + mixed + "," + cool + "," + changed + ","
              + preLuma / covered + "," + postLuma / covered + "," + preVariance + "," + postVariance + ","
              + bright + "," + preBrightVariance + "," + postBrightVariance + "," + maximumDifference;
        rows.append(row).append('\n');
        System.out.println("Elevation pigment " + row);
        assertTrue(preVariance > 1e-7, label + " includes sampled texture variation");
        if (material.equals("mixed")) {
            assertTrue(mixed > 100, label + " contains an actual authored material mixture");
        }
        if (material.equals("snow")) { assertTrue(cool > 100, label + " includes authored cool snow"); }
        if (level != 0) {
            assertTrue(changed > 100, label + " must exercise the enabled elevation cue");
            assertTrue(level > 0 ? postLuma > preLuma : postLuma < preLuma,
                  label + " preserves the intended uphill lift or downhill darkening");
        }
        if (material.equals("snow") && level > 0) {
            assertTrue(bright > 100 && preBrightVariance > 1e-7, "Actual bright snow must make clipping detectable");
            // For any set of samples, a lower pairwise slope m implies sigma(out)>=m*sigma(in).
            // Quantizing both sets to adjacent RGBA16F values adds at most two half steps to this sigma bound.
            assertTrue(Math.sqrt(postBrightVariance) + .001 >= Math.pow(2, -.6) * Math.sqrt(preBrightVariance),
                  label + " preserves highlight detail instead of flattening it at a hard ceiling: " + row);
        }
    }

    private static double luma(float[] values, int pixel) {
        return .2126 * values[pixel] + .7152 * values[pixel + 1] + .0722 * values[pixel + 2];
    }

    private static double variance(double sum, double square, int count) {
        return count == 0 ? 0 : Math.max(0, square / count - Math.pow(sum / count, 2));
    }

    private static float halfStep(float value) {
        return Math.max(0x1p-19f, Math.scalb(1f, Math.getExponent(value) - 10));
    }

    private static double productError(float a, float b) {
        // Each positive readback may choose either adjacent half. Bound the product without assuming nearest rounding.
        double da = halfStep(a), db = halfStep(b);
        return da * b + db * a + da * db;
    }

    private static String sculptProbe(String source) {
        source = replace(source, "void main() {", "uniform int u_elevationReview;\nvoid main() {\n"
              + "vec3 reviewBefore = vec3(0.0), reviewAfter = vec3(0.0);\n");
        source = replace(source, "    albedo = materialReflectance(albedo);",
              "    albedo = materialReflectance(albedo);\n    reviewBefore = albedo;");
        source = replace(source, "#ifdef lightingFlag\n    vec3 view = -viewDirection();",
              "    reviewAfter = albedo;\n#ifdef lightingFlag\n    vec3 view = -viewDirection();");
        return replace(source, "fragColor = vec4(result, 1.0);", """
              vec3 review = u_elevationReview == 0 ? reviewBefore : reviewAfter;
              if (u_elevationReview == 2) {
              #ifdef terrainBlendFlag
                  float snow = dot(materialCoverage, 1.0 - step(vec4(.5), abs(u_coverFamilies - 5.0)));
                  float fungus = dot(materialCoverage, 1.0 - step(vec4(.5), abs(u_coverFamilies - FUNGUS_FAMILY)));
              #else
                  float snow = family(5.0) ? 1.0 : 0.0;
                  float fungus = family(FUNGUS_FAMILY) ? 1.0 : 0.0;
              #endif
                  review = vec3(snow, fungus, (level + 16.0) / 32.0);
              }
              fragColor = vec4(review, ground ? .25 : 1.0);
              """);
    }

    private static String replace(String source, String anchor, String replacement) {
        source = source.replace("\r\n", "\n");
        assertEquals(1, source.split(java.util.regex.Pattern.quote(anchor), -1).length - 1, "Unique probe boundary");
        return source.replace(anchor, replacement);
    }

    private static void setProbe(GpuTerrain terrain, int mode) throws Exception {
        var batch = GpuTerrain.class.getDeclaredField("batch");
        batch.setAccessible(true);
        var provider = ((ModelBatch) batch.get(terrain)).getShaderProvider();
        if (provider instanceof GpuShaderProvider editable) { provider = editable.active(); }
        var shaders = BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        int bound = 0;
        for (Object shader : (Iterable<?>) shaders.get(provider)) {
            var program = ((BaseShader) shader).program;
            int location = program.getUniformLocation("u_elevationReview");
            if (location < 0) { continue; }
            program.bind(); program.setUniformi(location, mode); bound++;
        }
        assertTrue(bound > 0, "Actual terrain programs receive the test-only uniform");
    }

    private static BoardScene scene(String material, int level) {
        return BoardSurfaceBlendTest.scene(coords -> {
            var family = switch (material) {
                case "snow" -> BoardScene.Surface.SNOW;
                case "grass" -> BoardScene.Surface.GRASS;
                case "fungus" -> BoardScene.Surface.FUNGUS;
                case "mixed" -> coords.getX() < 4 ? BoardScene.Surface.FUNGUS : BoardScene.Surface.DIRT;
                default -> BoardScene.Surface.DIRT;
            };
            return BoardSurfaceBlendTest.tile(coords, family, level, -1, 0);
        });
    }

    private static final class GainProbe implements AutoCloseable {
        private final FrameBuffer target = GpuHeatGlow.buffer(1, 1);
        private final Mesh quad = GpuAtmosphere.screenQuad();
        private final ShaderProgram shader;

        GainProbe() {
            String fragment = "#version 330 core\n#define DESERT_FAMILY 8.0\n#define MARS_FAMILY 9.0\n"
                  + GpuShaderSource.read("light-model.glsl") + GpuShaderSource.read("environment-lighting.glsl")
                  + GpuShaderSource.read("terrain-ground-color.glsl") + """
                  uniform vec3 u_pigment;
                  uniform float u_level, u_fungus;
                  uniform int u_reviewGrade;
                  layout(location=0) out vec4 fragColor;
                  void main() {
                      fragColor=vec4(u_reviewGrade == 0 ? authoredPigmentGain(u_pigment)
                            : levelGrade(u_pigment, u_level, u_fungus, 1.0),1);
                  }
                  """;
            shader = GpuGlsl.compile("Authored pigment and elevation probe", GpuShaderSource.read("atmosphere.vert"), fragment);
        }

        float[] read(float[] pigment) { return read(pigment, 0, 0, 0); }

        float[] grade(float[] pigment, float level, float fungus) {
            return read(pigment, 1, level, fungus);
        }

        private float[] read(float[] pigment, int mode, float level, float fungus) {
            target.begin();
            try {
                GpuAtmosphere.screenState();
                shader.bind();
                shader.setUniformf("u_pigment", pigment[0], pigment[1], pigment[2]);
                shader.setUniformi("u_reviewGrade", mode);
                shader.setUniformf("u_level", level);
                shader.setUniformf("u_fungus", fungus);
                quad.render(shader, GL20.GL_TRIANGLES);
                var pixels = BufferUtils.newFloatBuffer(4);
                Gdx.gl.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
                float[] result = new float[4]; pixels.get(result); return result;
            } finally { target.end(); }
        }

        @Override public void close() { shader.dispose(); quad.dispose(); target.dispose(); }
    }
}
