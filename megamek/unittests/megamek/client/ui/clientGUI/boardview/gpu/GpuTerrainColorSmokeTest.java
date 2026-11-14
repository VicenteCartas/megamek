/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Known pigments isolate level-zero terrain lighting from material artwork and elevation styling. */
@Tag("on-demand")
class GpuTerrainColorSmokeTest {
    private static final int WIDTH = 320, HEIGHT = 192;
    private static final double ROUGHNESS = .9;
    private static final String[] NAMES = { "neutral", "orange", "green", "purple" };
    private static final double[][] COLORS = { { .5, .5, .5 }, { .8, .4, .15 }, { .3, .6, .15 }, { .5, .25, .65 } };

    @Test
    void levelZeroPigmentsKeepLinearDiffuseAndReflectionThroughTheAtmosphereComposite() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(WIDTH, HEIGHT, -1, -1);
        config.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> check(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Level-zero terrain color", failure.get()); }
    }

    private static void check(GpuShaderManager manager) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        String original = GpuShaderSource.readDisk("terrain-sculpt.frag");
        String shading = GpuShaderSource.readDisk("surface-shading.glsl");
        String boundary = "albedo = materialReflectance(albedo);";
        String reflection = "albedo += sheen;";
        String output = "return albedo + s.emission;";
        assertEquals(1, original.split(java.util.regex.Pattern.quote(boundary), -1).length - 1);
        assertEquals(1, shading.split(java.util.regex.Pattern.quote(reflection), -1).length - 1);
        assertEquals(1, shading.split(java.util.regex.Pattern.quote(output), -1).length - 1);
        var pigment = new StringBuilder("\n");
        for (int i = 0; i < COLORS.length; i++) {
            pigment.append(String.format(Locale.ROOT, "%s (gl_FragCoord.x < %.1f) albedo = vec3(%.9f, %.9f, %.9f);\n",
                  i == 0 ? "if" : "else if", (i + 1) * width / 4.0,
                  linear(COLORS[i][0]), linear(COLORS[i][1]), linear(COLORS[i][2])));
        }
        // Only the finished material inputs are replaced. The real sculpt lighting, environment LUT, scene
        // storage, shadows and atmosphere compositor remain in use; no texture or grade can retint the swatch.
        pigment.append("normal = vec3(0,0,1); film = 0.0; cavity = 1.0; occlusion = 1.0;\n")
              .append("bounce = GROUND_ALBEDO; volcanic = 0.0; finish = Finish(.9, 0.0, 0.0, vec3(0)); caustic = 0.0;"
                    + " submerged = 0.0;\n");
        // shadeSurface (surface-shading.glsl) returns the diffuse-only and reflection-only rows; nothing emits here.
        String controls = String.format(Locale.ROOT, """
              if (gl_FragCoord.y < %.1f) return diffuseOnly;
              if (gl_FragCoord.y < %.1f) return albedo - diffuseOnly;
              %s
              """, height / 3.0, height * 2.0 / 3.0, output);
        var changed = manager.apply(Map.of("terrain-sculpt.frag", original.replace(boundary, boundary + pigment),
              "surface-shading.glsl", shading.replace(reflection, "vec3 diffuseOnly = albedo;\n" + reflection)
                    .replace(output, controls)));
        assertTrue(changed.success(), changed.message());
        var atmosphere = new GpuAtmosphere();
        var terrain = new GpuTerrain();
        var report = new StringBuilder("Level-zero sculpt: columns neutral/orange/green/purple; rows from bottom "
              + "diffuse-only/reflection-only/full. All variants share the production BRDF and display boundary.\n");
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "terrain-color");
        try {
            assertTrue(directory.isDirectory() || directory.mkdirs());
            BoardScene scene = scene();
            // The compositor's viewport is logical; scene storage and readback use physical framebuffer pixels.
            var camera = new OrthographicCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.zoom = 80f / camera.viewportWidth;
            Vector3 center = BoardGeometry.center(new Coords(2, 2), 0);
            camera.position.set(center.x, center.y, 200);
            camera.up.set(0, 1, 0);
            camera.lookAt(center);
            camera.near = 1; camera.far = 500; camera.update();
            terrain.setGrass(false);
            terrain.update(scene);
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false, 0, 0, 0));
            for (String name : List.of("unit-white", "noon", "default-13")) {
                var settings = new BoardAtmosphere.Settings(name.equals("default-13") ? 13 : 12,
                      0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                atmosphere.configure(settings);
                atmosphere.updateLight(camera);
                BoardAtmosphere.Lighting lighting = name.equals("unit-white") ? whiteLight() : atmosphere.lighting();
                terrain.setAtmosphere(lighting);
                atmosphere.configureClouds(terrain, scene);
                terrain.renderShadows(camera, List.of());
                atmosphere.beginPixels(width, height, 0);
                terrain.render(camera);
                float[][][] radiance = read(width, height, true);
                atmosphere.end(camera, terrain, scene, 0);
                float[][][] displayed = read(width, height, false);
                report.append(name).append(" groundLight=").append(rgb(lighting.groundLight())).append('\n');
                verify(name, radiance, displayed, atmosphere.lighting(), atmosphere.exposure(), width, height, report);
                GpuReviewFrame.save(new File(directory, name + ".png"));
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            System.out.print(report);
            try { Files.writeString(new File(directory, "swatches.txt").toPath(), report); }
            catch (java.io.IOException error) { throw new AssertionError("Write swatch diagnostics", error); }
            finally { terrain.dispose(); atmosphere.dispose(); }
        }
    }

    private static void verify(String name, float[][][] radiance, float[][][] displayed,
          BoardAtmosphere.Lighting grade, float exposure, int width, int height, StringBuilder report) {
        double specular = integratedSpecular(.04);
        // Multiple scattering returns the 1 - E of white light that single scattering loses, coloured by f0
        // (Fdez-Aguera 2019); the diffuse budget stays the single-scattering remainder.
        double white = integratedSpecular(1), reflected = specular * (1 + .04 * (1 - white) / white);
        for (int swatch = 0; swatch < COLORS.length; swatch++) {
            for (int row = 0; row < 3; row++) {
                report.append("  ").append(NAMES[swatch]).append(" row=").append(row)
                      .append(" raw=").append(Arrays.toString(radiance[row][swatch]))
                      .append(" display=").append(Arrays.toString(displayed[row][swatch])).append('\n');
            }
            for (int channel = 0; channel < 3; channel++) {
                double diffuse = radiance[0][swatch][channel], reflection = radiance[1][swatch][channel];
                double full = radiance[2][swatch][channel];
                assertTrue(reflection > 0 && reflection < .15, "A rough dielectric keeps bounded visible reflection");
                double bound = halfStep(diffuse) + halfStep(reflection) + halfStep(full);
                assertEquals(diffuse + reflection, full, bound,
                      name + "/" + NAMES[swatch] + ": diffuse and reflection must add in linear scene units");
                if (name.equals("unit-white")) {
                    double expectedDiffuse = linear(COLORS[swatch][channel]) * (1 - specular);
                    assertEquals(expectedDiffuse, diffuse, .001,
                          "White illumination must preserve each known pigment channel's diffuse reflectance");
                    assertEquals(reflected, reflection, .0002,
                          "Neutral dielectric reflection must match independent hemispherical GGX integration");
                    assertEquals(expectedDiffuse + reflected, full, .001,
                          "The complete colored material must retain the independently predicted neutral sheen");
                }
            }
            for (int row = 0; row < 3; row++) {
                double[] color = new double[3];
                float[] tint = { grade.tint().r, grade.tint().g, grade.tint().b };
                double luminance = 0;
                double[] weights = { .2126, .7152, .0722 };
                for (int channel = 0; channel < 3; channel++) {
                    // The composite exposes the scene for the view's adaptation, then grades it.
                    color[channel] = radiance[row][swatch][channel] * exposure * tint[channel];
                    luminance += color[channel] * weights[channel];
                }
                int x = sampleX(swatch, width), y = sampleY(row, height);
                for (int channel = 0; channel < 3; channel++) {
                    double graded = luminance + (color[channel] - luminance) * grade.saturation();
                    double shoulder = Math.min(graded, .6) + .4 * (1 - Math.exp(-Math.max(graded - .6, 0) / .4));
                    int expected = (int) Math.round(255 * display(shoulder));
                    assertEquals(expected, Math.round(displayed[row][swatch][channel] * 255), 1,
                          name + "/" + NAMES[swatch] + ": final pixels must encode the measured linear scene once");
                }
            }
        }
    }

    private static float[][][] read(int width, int height, boolean scene) {
        float[][][] result = new float[3][COLORS.length][3];
        var pixel = BufferUtils.newFloatBuffer(4);
        for (int row = 0; row < 3; row++) {
            for (int swatch = 0; swatch < COLORS.length; swatch++) {
                pixel.clear();
                Gdx.gl.glReadPixels(sampleX(swatch, width), sampleY(row, height), 1, 1,
                      GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
                assertEquals(1, pixel.get(3), .0001, "Swatches must cover the actual level-zero scene");
                for (int channel = 0; channel < 3; channel++) {
                    float value = pixel.get(channel) / (scene ? GpuSceneRadiance.SCALE : 1);
                    assertTrue(Float.isFinite(value) && value >= 0, "Every color channel must remain finite");
                    result[row][swatch][channel] = value;
                }
            }
        }
        return result;
    }

    /** Independent hemispherical GGX midpoint integral for f0, without the production LUT or its interpolation. */
    private static double integratedSpecular(double f0) {
        int samples = 65536;
        double sum = 0, alphaSquared = Math.pow(ROUGHNESS, 4);
        for (int i = 0; i < samples; i++) {
            // N=V, so rotational symmetry removes azimuth and leaves this independent midpoint quadrature.
            double u = (i + .5) / samples;
            double halfZ = Math.sqrt((1 - u) / (1 + (alphaSquared - 1) * u));
            double lightZ = 2 * halfZ * halfZ - 1;
            if (lightZ <= 0) { continue; }
            double fresnel = f0 + (1 - f0) * Math.pow(1 - halfZ, 5);
            double masking = 2 * lightZ / (lightZ + Math.sqrt(lightZ * lightZ * (1 - alphaSquared) + alphaSquared));
            sum += fresnel * masking;
        }
        return sum / samples;
    }

    private static double halfStep(double canonical) {
        return Math.scalb(1.0, Math.max(-24, Math.getExponent(canonical * GpuSceneRadiance.SCALE) - 10))
              / GpuSceneRadiance.SCALE;
    }

    private static int sampleX(int swatch, int width) { return (int) ((swatch + .5) * width / 4); }
    private static int sampleY(int row, int height) { return (int) ((row + .5) * height / 3); }
    private static double linear(double c) { return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4); }
    private static double display(double c) { return c <= .0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - .055; }
    private static String rgb(Color color) { return Arrays.toString(new float[] { color.r, color.g, color.b }); }

    private static BoardAtmosphere.Lighting whiteLight() {
        return new BoardAtmosphere.Lighting(new Vector3(0, 0, -1), new Color(0, 0, 0, 1), new Color(Color.WHITE),
              new Color(Color.WHITE), new Color(Color.WHITE), new Color(Color.WHITE), new Color(Color.WHITE),
              1, 1, true);
    }

    private static BoardScene scene() {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS,
                      List.of(), List.of(), BoardLiquid.NONE, null));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
