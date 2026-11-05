/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native rendering of full-resolution materials, relief toggling and all six cliff orientations. */
@Tag("on-demand")
class GpuCliffMaterialsSmokeTest {
    private static final Coords RAISED = new Coords(3, 3);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void lightsEveryCliffOrientationAndPreservesMaterialOwnership(boolean road) throws Exception {
        BoardScene review = reviewScene();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkMaterials();
                    checkRendering(review, road);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Cliff material rendering", failure.get()); }
    }

    private static void checkMaterials() {
        GpuAssets assets = new GpuAssets();
        try {
            for (String name : List.of("grassland-ground", "dirt-ground", "sand-verge", "rock-ground", "concrete-ground", "snow-ground",
                  "temperate-debris", "dirt-debris", "arid-debris", "temperate-cliff", "arid-cliff", "temperate-bank",
                  "concrete-bank")) {
                GpuAssets.Sculpt maps = assets.sculpt(name);
                assertNotNull(maps.normal(), name);
                assertSame(maps, assets.sculpt(name), "All hexes borrow one material set");
                for (Texture texture : List.of(maps.color(), maps.normal())) {
                    assertEquals(512, texture.getWidth());
                    assertEquals(512, texture.getHeight());
                    assertEquals(Texture.TextureWrap.Repeat, texture.getUWrap());
                    assertEquals(Texture.TextureFilter.MipMapLinearLinear, texture.getMinFilter());
                }
            }
            var missing = assertThrows(IllegalArgumentException.class, () -> assets.sculpt("missing-material"));
            assertTrue(missing.getMessage().contains("Missing required Enhanced terrain asset"), missing.getMessage());
            assertTrue(missing.getMessage().contains("missing-material.tres"), missing.getMessage());
        } finally {
            assets.dispose();
        }
    }

    private static void checkRendering(BoardScene review, boolean road) throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        if (road) { output = new File(output, "road-walls"); }
        assertTrue(output.isDirectory() || output.mkdirs());
        GpuTerrain terrain = new GpuTerrain();
        try (var surface = new GpuSceneTestOutput()) {
            for (BoardScene.Surface family : BoardScene.Surface.values()) {
                BoardScene scene = scene(family, road);
                assertEquals(!road, new BoardSurface(scene, scene.tile(RAISED)).relief.sculpted(),
                      "Exercise both the sculpted cliff and the retained artwork/road wall path");
                terrain.update(scene);
                for (int edge = 0; edge < 6; edge++) {
                    Vector3 a = BoardGeometry.corner(RAISED, 6, edge);
                    Vector3 b = BoardGeometry.corner(RAISED, 6, edge + 1);
                    Vector3 along = b.cpy().sub(a).nor();
                    Vector3 outward = along.cpy().crs(Vector3.Z);
                    Vector3 target = a.cpy().lerp(b, .5f);
                    target.z = 3 * BoardGeometry.LEVEL;
                    OrthographicCamera camera = new OrthographicCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.zoom = .09f;
                    camera.near = 1;
                    camera.far = 3000;
                    camera.up.set(Vector3.Z);
                    camera.position.set(target).mulAdd(outward, 700).mulAdd(along, 160).add(0, 0, 100);
                    camera.lookAt(target);
                    camera.update();
                    Vector3 light = outward.cpy().mulAdd(along, .7f).add(0, 0, .65f).nor().scl(-1);
                    // This controlled normal-map probe needs its declared ambient fill in the PBR environment too.
                    // A black sky/horizon would leave self-shadowed folds lit only by weak ground bounce.
                    Color ambient = new Color(.23f, .25f, .28f, 1);
                    terrain.setAtmosphere(new BoardAtmosphere.Lighting(light, new Color(.85f, .82f, .76f, 1),
                          ambient, Color.BLACK, ambient, ambient, Color.WHITE, 1, 1, true));
                    terrain.renderShadows(camera, List.of());
                    terrain.setNormalMaps(false);
                    int[] flat = samples(surface, terrain, camera, a, b);
                    if (edge == 4) { GpuBoardTestUi.capture(new File(output, "cliff-" + family + "-flat.png")); }
                    terrain.setNormalMaps(true);
                    int[] relief = samples(surface, terrain, camera, a, b);
                    double difference = difference(flat, relief);
                    if (difference <= .5) {
                        GpuBoardTestUi.capture(new File(output, "failed-" + family + "-" + edge + "-relief.png"));
                        terrain.setNormalMaps(false);
                        samples(surface, terrain, camera, a, b);
                        GpuBoardTestUi.capture(new File(output, "failed-" + family + "-" + edge + "-flat.png"));
                    }
                    assertTrue(difference > .5, family + " edge " + edge + " must respond to mapped relief: " + difference
                          + "; mean display channels flat=" + meanChannel(flat) + ", mapped=" + meanChannel(relief));
                    if (edge == 4 && family == BoardScene.Surface.ROCK && !road) {
                        // Terrain Parallax moves the sculpted rock's maps, whether or not the normal maps shade them.
                        for (boolean normals : new boolean[] { true, false }) {
                            terrain.setNormalMaps(normals);
                            int[] moved = samples(surface, terrain, camera, a, b);
                            terrain.setTerrainParallax(false);
                            int[] still = samples(surface, terrain, camera, a, b);
                            terrain.setTerrainParallax(true);
                            double parallax = difference(moved, still);
                            assertTrue(parallax > .5, "Terrain Parallax with normal maps " + normals + ": " + parallax);
                        }
                        terrain.setNormalMaps(true);
                    }
                    if (edge == 4) {
                        GpuBoardTestUi.capture(new File(output, "cliff-" + family + "-relief.png"));
                        terrain.setWetness(1);
                        int[] wet = samples(surface, terrain, camera, a, b);
                        if (family != BoardScene.Surface.SNOW) {
                            assertTrue(difference(wet, relief) > 1, "Exposed cliffs respond to rain: " + family);
                        } else {
                            assertEquals(0, difference(wet, relief), "Snow never takes the liquid rain film");
                        }
                        GpuBoardTestUi.capture(new File(output, "cliff-" + family + "-wet.png"));
                        terrain.setWetness(0);
                    }
                }
            }
            captureOverview(surface, terrain, review, output);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
        }
    }

    /** Shipped ground art and connected multi-hex cliffs, alongside the isolated material probes. */
    private static BoardScene reviewScene() throws Exception {
        Hex[] hexes = new Hex[11 * 10];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 11; x++) {
                int level = x >= 2 && x <= 8 && y <= 6 ? y < 4 ? 5 : x < 7 ? 3 : 0 : 0;
                Hex hex = new Hex(level);
                hex.setTheme("desert");
                hexes[y * 11 + x] = hex;
            }
        }
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(11, 10, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    private static void captureOverview(GpuSceneTestOutput surface, GpuTerrain terrain, BoardScene scene, File output)
          throws Exception {
        terrain.update(scene);
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.tilt(10);
        camera.camera.zoom = .32f;
        camera.center(BoardGeometry.center(new Coords(5, 5), 2));
        Color ambient = new Color(.25f, .28f, .32f, 1);
        terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(-.9f, .5f, -.65f).nor(),
              new Color(.9f, .84f, .75f, 1), ambient, Color.BLACK, ambient, ambient, Color.WHITE, 1, 1, true));
        terrain.renderShadows(camera.camera, List.of());
        for (boolean relief : new boolean[] { false, true }) {
            terrain.setNormalMaps(relief);
            surface.begin(.16f, .20f, .24f, 1);
            terrain.render(camera.camera);
            terrain.renderTransparent(camera.camera);
            surface.display();
            GpuBoardTestUi.capture(new File(output, "cliff-board-" + (relief ? "relief" : "flat") + ".png"));
        }
    }

    private static int[] samples(GpuSceneTestOutput surface, GpuTerrain terrain, OrthographicCamera camera, Vector3 a,
          Vector3 b) {
        surface.begin(.06f, .07f, .09f, 1);
        terrain.render(camera);
        terrain.renderTransparent(camera);
        surface.display();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int[] samples = new int[121];
            for (int y = 0; y <= 10; y++) {
                for (int x = 0; x <= 10; x++) {
                    Vector3 point = a.cpy().lerp(b, .2f + x * .06f);
                    point.z = BoardGeometry.LEVEL * (2 + y * .2f);
                    Vector3 screen = camera.project(point);
                    int px = (int) (screen.x * image.getWidth() / Gdx.graphics.getWidth());
                    int py = (int) (screen.y * image.getHeight() / Gdx.graphics.getHeight());
                    samples[y * 11 + x] = image.getPixel(px, py);
                }
            }
            return samples;
        } finally {
            image.dispose();
        }
    }

    private static double difference(int[] a, int[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) {
            for (int shift : new int[] { 8, 16, 24 }) {
                total += Math.abs(((a[i] >>> shift) & 255) - ((b[i] >>> shift) & 255));
            }
        }
        return total / (a.length * 3.0);
    }

    private static double meanChannel(int[] pixels) {
        long total = 0;
        for (int pixel : pixels) { total += (pixel >>> 24) + ((pixel >>> 16) & 255) + ((pixel >>> 8) & 255); }
        return total / (pixels.length * 3.0);
    }

    private static BoardScene scene(BoardScene.Surface family, boolean road) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        int color = switch (family) {
            case GRASS -> 0xff7c9262;
            case TROPICAL -> 0xff555b36;
            case DIRT -> 0xff9a8065;
            case SAND, DESERT -> 0xffcdb18a;
            case MARS -> 0xff97684f;
            case VOLCANO -> 0xff777d86;
            case ROCK, LUNAR -> 0xff9a9992;
            case FUNGUS -> 0xff827589;
            case CONCRETE -> 0xff999996;
            case SNOW -> 0xffdedee0;
        };
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) { image.setRGB(x, y, color); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                Coords coords = new Coords(x, y);
                // End the artwork road on the plateau so a connected ramp cannot cover the wall being sampled.
                tiles.add(new BoardScene.Tile(coords, coords.equals(RAISED) ? 6 : 0, -1, false,
                      road && coords.equals(RAISED) ? 18 : 0,
                      family, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static final int PERIOD = 64;

    private static final List<String> MATERIALS = List.of("temperate-bank", "temperate-cliff", "arid-cliff",
          "mars-cliff", "volcano-cliff", "lunar-cliff", "fungus-cliff", "fungus-bank", "dirt-ground", "snow-ground",
          "volcano-ground", "mars-ground");

    @Test
    void tallNaturalFacesDoNotRepeatAtTheSourceMapPeriod() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(512, 768);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkTallFaces(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tall cliff material repetition", failure.get()); }
    }

    private static void checkTallFaces() throws Exception {
        var assets = new GpuAssets();
        var quad = new Mesh(true, 4, 0, new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"));
        quad.setVertices(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        String materials = GpuShaderSource.read("terrain-materials.glsl");
        int start = materials.indexOf("vec4 materialTexel(");
        String sampling = materials.substring(start, materials.indexOf("#ifdef volcanicFlag", start));
        // The translated and fungal reads' heights (the terrain's parallax) sample through terrain-projection.glsl's.
        String projection = GpuShaderSource.read("terrain-projection.glsl");
        sampling = projection.substring(projection.indexOf("float heightTexel("), projection.indexOf("// planar's height"))
              + sampling;
        var shader = GpuGlsl.compile("cliff-material-repeat-test", """
              in vec2 a_position;
              void main() { gl_Position = vec4(a_position, 0.0, 1.0); }
              """, """
              out vec4 fragColor;
              uniform sampler2DArray u_terrainLayers;
              uniform float u_layer;
              uniform int u_mode;
              """ + sampling + """
              void main() {
                  vec2 uv = gl_FragCoord.xy / 64.0;
                  mat2 gradient = mat2(dFdx(uv), dFdy(uv));
                  vec4 color = u_mode == 0 ? translatedTexel(u_layer, uv, gradient, .37)
                        : u_mode == 2 ? fungusTexel(u_layer, uv, gradient)
                        : cliffTexel(u_layer, uv, gradient, .37);
                  fragColor = vec4(color.rgb, 1.0);
              }
              """);
        try {
            var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "cliff-materials");
            Files.createDirectories(output.toPath());
            var array = assets.sculptArray(MATERIALS);
            StringBuilder report = new StringBuilder("Material: vertical correlation at one source period, fixed column -> height-aware\n");
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            array.color().bind(0);
            shader.bind();
            shader.setUniformi("u_terrainLayers", 0);
            for (int layer = 0; layer < MATERIALS.size(); layer++) {
                String name = MATERIALS.get(layer);
                shader.setUniformf("u_layer", 2f * layer);
                double[] correlation = new double[2];
                for (int pass = 0; pass < 2; pass++) {
                    shader.setUniformi("u_mode", pass == 0 ? 0 : name.startsWith("fungus") ? 2 : 1);
                    quad.render(shader, GL20.GL_TRIANGLE_STRIP);
                    Pixmap pixels = ScreenUtils.getFrameBufferPixmap(0, 0,
                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    try { correlation[pass] = correlation(pixels); }
                    finally { pixels.dispose(); }
                    GpuReviewFrame.save(new File(output, name + (pass == 0 ? "-fixed.png" : "-varied.png")));
                }
                report.append(name).append(": ").append(correlation[0]).append(" -> ").append(correlation[1]).append('\n');
                assertTrue(correlation[0] > .98, name + ": exercise the repeating source, not an empty frame");
                assertTrue(correlation[1] < .8, name + ": repeated cliff motif remains, correlation=" + correlation[1]);
            }
            Files.writeString(new File(output, "report.txt").toPath(), report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            shader.dispose();
            quad.dispose();
            assets.dispose();
        }
    }

    private static double correlation(Pixmap pixels) {
        double a = 0, b = 0, aa = 0, bb = 0, ab = 0;
        int count = 0;
        for (int y = 0; y < pixels.getHeight() - PERIOD; y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                double first = luminance(pixels.getPixel(x, y));
                double second = luminance(pixels.getPixel(x, y + PERIOD));
                a += first; b += second; aa += first * first; bb += second * second; ab += first * second; count++;
            }
        }
        return (ab - a * b / count) / Math.sqrt((aa - a * a / count) * (bb - b * b / count));
    }

    private static double luminance(int rgba) {
        return .2126 * (rgba >>> 24) + .7152 * (rgba >>> 16 & 255) + .0722 * (rgba >>> 8 & 255);
    }
}
