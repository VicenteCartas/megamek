/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Native visual comparison: identical rules and camera, three choices of presentation. */
@Tag("on-demand")
class GpuFieldOfViewSmokeTest {
    private record Appearance(float visible, float blocked, float visibleChroma, float blockedChroma) { }

    @Test
    void allStylesShareVisibilityAndMaskCacheButRenderDifferentObscuration() throws Exception {
        Map<GpuFieldOfView.Style, Appearance> results = new EnumMap<>(GpuFieldOfView.Style.class);
        BoardFieldOfView expected = null;
        try (var options = new GpuFieldOfViewTest.Options()) {
            for (GpuFieldOfView.Style style : GpuFieldOfView.Style.values()) {
                GpuFieldOfView.Style sensorStyle = GpuFieldOfView.Style.values()[(style.ordinal() + 1) % 3];
                AtomicReference<Throwable> failure = new AtomicReference<>();
                try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
                    SwingUtilities.invokeAndWait(() -> {
                        fixture.view.select(new Coords(1, 6));
                        fixture.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                        fixture.source.refresh();
                    });
                    BoardFieldOfView mask = fixture.source.takeFrame().scene().fieldOfView();
                    assertTrue(mask.active());
                    if (expected != null) {
                        assertEquals(expected, mask, "Changing style must not change any visibility result");
                    }
                    expected = mask;
                    new Lwjgl3Application(new GpuBattleView(fixture.source) {
                        private int tick;
                        private final long readyDeadline = System.nanoTime() + 60_000_000_000L;
                        private GpuTerrain renderedTerrain;
                        private GpuFieldOfView probe;
                        private Appearance noDarkening;

                        @Override
                        public void render() {
                            try {
                                super.render();
                                if (renderedTerrain == null) {
                                    var field = GpuBattleView.class.getDeclaredField("terrain");
                                    field.setAccessible(true);
                                    renderedTerrain = (GpuTerrain) field.get(this);
                                }
                                if (!renderedTerrain.ready(fixture.source.takeFrame().scene())) {
                                    assertTrue(System.nanoTime() < readyDeadline, "Wait for actual terrain before comparing FoV pixels");
                                    return;
                                }
                                tick++;
                                if (tick == 1) {
                                    boardCamera.setIsometric(false);
                                    // The samples are board pixels: the HUD's panels would cover some of them.
                                    ((GpuHud) field("ui")).stage.getRoot().setVisible(false);
                                    // Compare FoV at one hour, independently of each window's sampled scenario time.
                                    Slider time = GpuBoardTestUi.tuning(this, "Time of day");
                                    time.setValue(13);
                                    GpuBoardTestUi.pressTuning(modeButton("fov", style));
                                    GpuBoardTestUi.pressTuning(modeButton("sensor", sensorStyle));
                                    probe = new GpuFieldOfView();
                                    probe.configure(style, GpuFieldOfView.FOV_DARKNESS, sensorStyle, GpuFieldOfView.SENSOR_DARKNESS);
                                    probe.update(mask);
                                    assertEquals(1, probe.uploads());
                                } else if (tick == 12) {
                                    capture("top");
                                    results.put(style, appearance((BoardScene) field("scene")));
                                    darknessSlider("FoV").setValue(0);
                                    probe.configure(style, 0, sensorStyle, GpuFieldOfView.SENSOR_DARKNESS);
                                } else if (tick == 16) {
                                    noDarkening = appearance((BoardScene) field("scene"));
                                    capture("no-darkening");
                                    darknessSlider("FoV").setValue(100);
                                    probe.configure(style, 1, sensorStyle, GpuFieldOfView.SENSOR_DARKNESS);
                                } else if (tick == 20) {
                                    Appearance darkened = appearance((BoardScene) field("scene"));
                                    assertTrue(darkened.blocked() < noDarkening.blocked() * 0.9f,
                                          "The darkness slider must darken blocked content in " + style);
                                    assertEquals(noDarkening.visible(), darkened.visible(), 0.01f,
                                          "The darkness slider must leave visible content unchanged");
                                    darknessSlider("FoV").setValue(GpuFieldOfView.FOV_DARKNESS * 100);
                                    boardCamera.setIsometric(true);
                                    boardCamera.fit(fixture.source.takeFrame().scene());
                                } else if (tick == 24) {
                                    capture("isometric");
                                    darknessSlider("FoV").setValue(50);
                                    darknessSlider("Sensor").setValue(50);
                                    GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-defaults"));
                                } else if (tick == 28) {
                                    if (style == GpuFieldOfView.Style.GRAYSCALE) {
                                        capture("tuning");
                                    }
                                    assertEquals(mask, fixture.source.takeFrame().scene().fieldOfView(),
                                          "Presentation controls must not alter LOS/sensor data");
                                    probe.configure(GpuFieldOfView.FOV_STYLE, GpuFieldOfView.FOV_DARKNESS,
                                          GpuFieldOfView.SENSOR_STYLE, GpuFieldOfView.SENSOR_DARKNESS);
                                    probe.update(mask);
                                    assertEquals(1, probe.uploads(), "Camera and tuning changes must reuse an unchanged mask");
                                    probe.update(BoardFieldOfView.EMPTY);
                                    assertFalse(probe.active(), "Disabling FoV must immediately stop shading");
                                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                                    Gdx.app.exit();
                                }
                            } catch (Throwable error) {
                                failure.set(error);
                                Gdx.app.exit();
                            }
                        }

                        private void capture(String camera) {
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            GpuBoardTestUi.capture(new File(output, "fov-" + style.name().toLowerCase(java.util.Locale.ROOT)
                                  + "-" + camera + ".png"));
                        }

                        private Slider darknessSlider(String effect) {
                            return GpuBoardTestUi.tuning(this, effect + " darkness");
                        }

                        private TextButton modeButton(String effect, GpuFieldOfView.Style mode) {
                            return GpuBoardTestUi.tuning(this, effect + "-style-" + mode.name());
                        }

                        private Object field(String name) throws ReflectiveOperationException {
                            var field = GpuBattleView.class.getDeclaredField(name);
                            field.setAccessible(true);
                            return field.get(this);
                        }

                        private Appearance appearance(BoardScene scene) {
                            Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0,
                                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                            try {
                                float visible = 0, blocked = 0;
                                float visibleChroma = 0, blockedChroma = 0;
                                int visibleCount = 0, blockedCount = 0;
                                for (BoardScene.Tile tile : scene.tiles()) {
                                    // Unit HUD annotations cover nearby hexes and deliberately keep their colors.
                                    if (tile.water() || !tile.features().isEmpty()
                                          || tile.coords().distance(fixture.entity.getPosition()) <= 2) {
                                        continue;
                                    }
                                    Vector3 point = BoardGeometry.center(tile.coords(), tile.elevation())
                                          .add(BoardGeometry.WIDTH * 0.10f, BoardGeometry.HEIGHT * 0.08f, 0);
                                    boardCamera.camera.project(point, 0, 0, boardCamera.camera.viewportWidth,
                                          boardCamera.camera.viewportHeight);
                                    int x = Math.round(point.x * pixels.getWidth() / Gdx.graphics.getWidth());
                                    int y = Math.round(point.y * pixels.getHeight() / Gdx.graphics.getHeight());
                                    if (x < 0 || y < 0 || x >= pixels.getWidth() || y >= pixels.getHeight()) {
                                        continue;
                                    }
                                    int rgb = pixels.getPixel(x, y);
                                    int red = (rgb >>> 24) & 255, green = (rgb >>> 16) & 255, blue = (rgb >>> 8) & 255;
                                    float value = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f;
                                    float chroma = (Math.max(red, Math.max(green, blue))
                                          - Math.min(red, Math.min(green, blue))) / 255f;
                                    var visibility = GpuFieldOfViewTest.at(scene.fieldOfView(), tile.coords()).visibility();
                                    if (visibility == BoardFieldOfView.Visibility.VISIBLE) {
                                        visible += value;
                                        visibleChroma += chroma;
                                        visibleCount++;
                                    } else if (visibility == BoardFieldOfView.Visibility.BLOCKED) {
                                        blocked += value;
                                        blockedChroma += chroma;
                                        blockedCount++;
                                        if (style == GpuFieldOfView.Style.GRAYSCALE
                                              && !besideVisible(scene, tile.coords())) {
                                            assertTrue(chroma <= 1 / 255f,
                                                  "Blocked hex content must be grayscale at " + tile.coords()
                                                        + "; pixel " + x + "," + y + "; RGB " + red + "," + green + "," + blue);
                                        }
                                    }
                                }
                                assertTrue(visibleCount > 10 && blockedCount > 10,
                                      "The comparison must sample both clear and blocked terrain");
                                return new Appearance(visible / visibleCount, blocked / blockedCount,
                                      visibleChroma / visibleCount, blockedChroma / blockedCount);
                            } finally {
                                pixels.dispose();
                            }
                        }

                        @Override
                        public void dispose() {
                            if (probe != null) {
                                probe.dispose();
                            }
                            super.dispose();
                        }
                    }, GpuBoardWindow.configuration(false));
                    if (failure.get() != null) {
                        throw new AssertionError("Native FoV comparison failed for " + style, failure.get());
                    }
                }
            }
        }
        Appearance dimmed = results.get(GpuFieldOfView.Style.DIMMED), fog = results.get(GpuFieldOfView.Style.FOG_OF_WAR);
        Appearance grayscale = results.get(GpuFieldOfView.Style.GRAYSCALE);
        assertEquals(dimmed.visible(), fog.visible(), 0.04, "Visible terrain must remain consistent between styles");
        assertEquals(dimmed.visible(), grayscale.visible(), 0.04, "Grayscale must leave visible terrain unchanged");
        assertEquals(dimmed.visibleChroma(), grayscale.visibleChroma(), 0.01, "Visible terrain must retain its color");
        assertTrue(grayscale.visibleChroma() > 0.01f, "Visible terrain must remain colored in grayscale mode");
        assertTrue(dimmed.blockedChroma() > 0.01f, "Dimmed terrain must retain its color");
        assertEquals(dimmed.blocked(), grayscale.blocked(), 0.02, "Dimmed and grayscale must share darkening strength");
        assertTrue(fog.blocked() < dimmed.blocked() * 0.8f, "Fog style must obscure blocked terrain more strongly");
    }

    /** Height of the raised hex, in levels. */
    private static final int LEVELS = 2;

    @ParameterizedTest
    @EnumSource(value = GpuFieldOfView.Style.class, names = { "DIMMED", "GRAYSCALE" })
    void cliffSidesKeepTheirOwningHexVisibilityThroughoutAnOrbit(GpuFieldOfView.Style style) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkCliffs(style);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("FoV cliff orbit regression", failure.get());
        }
    }

    private void checkCliffs(GpuFieldOfView.Style style) throws Exception {
        BufferedImage ground = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        var graphics = ground.createGraphics();
        graphics.setColor(new java.awt.Color(130, 150, 110));
        graphics.fillRect(0, 0, 84, 72);
        graphics.dispose();
        BoardScene.Pixels pixels = new BoardScene.Pixels(ground);
        Coords raised = new Coords(3, 3);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(raised) ? LEVELS : 0, -1, false, 0,
                      BoardScene.Surface.CONCRETE, null, List.of(), List.of()));
            }
        }
        BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuFieldOfView field = new GpuFieldOfView();
        field.configure(style, GpuFieldOfView.FOV_DARKNESS, style, GpuFieldOfView.SENSOR_DARKNESS);
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        camera.center(BoardGeometry.center(raised, LEVELS / 2f));
        camera.zoom(0.32f);
        terrain.update(scene);
        atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.renderShadows(List.of());
        var sides = new BoardSurface(scene, scene.tile(raised)).sides(scene, BoardGeometry.floor(scene));
        int compared = 0, mismatched = 0;
        try {
            for (boolean blocked : List.of(false, true)) {
                for (int angle = 0; angle < 24; angle++) {
                    camera.orbit(angle % 2 == 0 ? 29.7f : 0.3f, 0);
                    List<Vector3> samples = new ArrayList<>();
                    Vector3 center = BoardGeometry.center(raised, 0);
                    for (BoardSurface.Side side : sides) {
                        Vector3 normal = new Vector3(side.a()).lerp(side.b(), 0.5f).sub(center);
                        normal.z = 0;
                        if (normal.nor().dot(camera.camera.direction) > -0.4f) {
                            continue;
                        }
                        for (int along = 2; along <= 8; along++) {
                            for (int height = 2; height <= 8; height++) {
                                Vector3 point = new Vector3(side.a()).lerp(side.b(), along / 10f);
                                point.z = BoardGeometry.LEVEL * LEVELS * height / 10f;
                                samples.add(camera.camera.project(point));
                            }
                        }
                    }
                    Pixmap reference = draw(scene, terrain, atmosphere, field, camera, mask(scene, raised, blocked, true));
                    Pixmap actual = draw(scene, terrain, atmosphere, field, camera, mask(scene, raised, blocked, false));
                    try {
                        for (Vector3 point : samples) {
                            int x = Math.round(point.x * actual.getWidth() / Gdx.graphics.getWidth());
                            int y = Math.round(point.y * actual.getHeight() / Gdx.graphics.getHeight());
                            if (x >= 0 && y >= 0 && x < actual.getWidth() && y < actual.getHeight()) {
                                compared++;
                                if (difference(reference.getPixel(x, y), actual.getPixel(x, y)) > 3) {
                                    mismatched++;
                                }
                                if (blocked && style == GpuFieldOfView.Style.GRAYSCALE) {
                                    int rgb = actual.getPixel(x, y);
                                    assertEquals(rgb >>> 24, rgb >>> 16 & 255, 1, "Blocked cliffs must be grayscale");
                                    assertEquals(rgb >>> 24, rgb >>> 8 & 255, 1, "Blocked cliffs must be grayscale");
                                }
                            }
                        }
                        if (angle == 0) {
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            GpuBoardTestUi.capture(new File(output, "fov-cliff-" + style.name().toLowerCase(java.util.Locale.ROOT)
                                  + "-" + (blocked ? "blocked" : "visible") + ".png"));
                        }
                    } finally {
                        reference.dispose();
                        actual.dispose();
                    }
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertTrue(compared > 2000, "Sample wall interiors across all six sides, both visibility states and a full orbit");
            assertEquals(0, mismatched, "Cliff pixels must match their owning hex at every angle; sampled " + compared);
        } finally {
            field.dispose();
            atmosphere.dispose();
            terrain.dispose();
        }
    }

    private static BoardFieldOfView mask(BoardScene scene, Coords raised, boolean blocked, boolean uniform) {
        List<BoardFieldOfView.Hex> hexes = scene.tiles().stream().map(tile -> {
            boolean dark = uniform || tile.coords().equals(raised) ? blocked : !blocked;
            return new BoardFieldOfView.Hex(dark ? BoardFieldOfView.Visibility.BLOCKED : BoardFieldOfView.Visibility.VISIBLE, 0);
        }).toList();
        return new BoardFieldOfView(scene.width(), scene.height(), hexes, 120, 0, true, false, false);
    }

    private static Pixmap draw(BoardScene scene, GpuTerrain terrain, GpuAtmosphere atmosphere, GpuFieldOfView field,
          BoardCamera camera, BoardFieldOfView mask) {
        field.update(mask);
        ScreenUtils.clear(0, 0, 0, 1, true);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
        terrain.render(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0, field);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int difference(int a, int b) {
        return Math.max(Math.abs((a >>> 24) - (b >>> 24)), Math.max(Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)),
              Math.abs((a >>> 8 & 255) - (b >>> 8 & 255))));
    }

    private record Samples(int visible, int sensor, int blocked) { }

    @Test
    void fovAndSensorEffectsChangeIndependentlyInBothCameraViews() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkEffects();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Independent FoV and sensor effects", failure.get());
        }
    }

    private void checkEffects() {
        BufferedImage ground = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        var graphics = ground.createGraphics();
        graphics.setColor(new java.awt.Color(160, 200, 110));
        graphics.fillRect(0, 0, 84, 72);
        graphics.dispose();
        BoardScene.Pixels pixels = new BoardScene.Pixels(ground);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        List<BoardFieldOfView.Hex> hexes = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0,
                      BoardScene.Surface.GRASS, null, List.of(), List.of()));
                var visibility = x < 3 ? BoardFieldOfView.Visibility.VISIBLE
                      : x < 6 ? BoardFieldOfView.Visibility.SENSOR : BoardFieldOfView.Visibility.BLOCKED;
                hexes.add(new BoardFieldOfView.Hex(visibility, 0, x >= 6));
            }
        }
        BoardScene scene = new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
        // The Legacy 2D grayscale preference must not override the explicit GPU styles.
        BoardFieldOfView mask = new BoardFieldOfView(9, 7, hexes, 255, 0, true, true, false);
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuFieldOfView field = new GpuFieldOfView();
        BoardCamera camera = new BoardCamera();
        try {
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            terrain.update(scene);
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            terrain.setAtmosphere(atmosphere.lighting());
            terrain.renderShadows(List.of());
            for (boolean isometric : List.of(false, true)) {
                camera.setIsometric(isometric);
                camera.fit(scene);
                field.configure(GpuFieldOfView.FOV_STYLE, 0, GpuFieldOfView.SENSOR_STYLE, 0);
                Samples clear = drawEffects(scene, terrain, atmosphere, field, camera, mask);

                field.configure(GpuFieldOfView.FOV_STYLE, GpuFieldOfView.FOV_DARKNESS, GpuFieldOfView.SENSOR_STYLE, 0);
                Samples fov = drawEffects(scene, terrain, atmosphere, field, camera, mask);
                assertEquals(clear.visible(), fov.visible());
                assertEquals(clear.blocked(), fov.blocked(), "FoV darkness must leave out-of-sensor hexes alone");
                assertTrue(brightness(fov.sensor()) < brightness(clear.sensor()) * 0.8f);

                field.configure(GpuFieldOfView.FOV_STYLE, 0, GpuFieldOfView.SENSOR_STYLE, GpuFieldOfView.SENSOR_DARKNESS);
                Samples sensor = drawEffects(scene, terrain, atmosphere, field, camera, mask);
                assertEquals(clear.visible(), sensor.visible());
                assertEquals(clear.sensor(), sensor.sensor(), "Sensor darkness must leave sensor-only hexes alone");
                assertTrue(brightness(sensor.blocked()) < brightness(clear.blocked()) * 0.8f);

                field.configure(GpuFieldOfView.FOV_STYLE, GpuFieldOfView.FOV_DARKNESS,
                      GpuFieldOfView.SENSOR_STYLE, GpuFieldOfView.SENSOR_DARKNESS);
                Samples both = drawEffects(scene, terrain, atmosphere, field, camera, mask);
                assertEquals(new Samples(clear.visible(), fov.sensor(), sensor.blocked()), both,
                      "Each hidden hex must use one effect, without stacking them");
                assertTrue(chroma(both.visible()) > 10 && chroma(both.sensor()) > 10,
                      "Visible and dimmed terrain must retain color");
                assertTrue(chroma(both.blocked()) < chroma(both.visible()) / 3,
                      "Fog of war must desaturate out-of-sensor terrain");

                BoardFieldOfView faint = new BoardFieldOfView(mask.width(), mask.height(), mask.hexes(),
                      64, mask.highlightAlpha(), mask.darken(), mask.grayscale(), mask.spotting());
                Samples faded = drawEffects(scene, terrain, atmosphere, field, camera, faint);
                assertEquals(both.visible(), faded.visible());
                assertTrue(brightness(faded.sensor()) > brightness(both.sensor()), "Preference opacity updates the FoV uniform");
                assertTrue(brightness(faded.blocked()) > brightness(both.blocked()), "Preference opacity updates the sensor uniform");
                assertEquals(1, field.uploads(), "Preference opacity changes must reuse the mask");

                field.configure(GpuFieldOfView.Style.GRAYSCALE, GpuFieldOfView.FOV_DARKNESS,
                      GpuFieldOfView.SENSOR_STYLE, GpuFieldOfView.SENSOR_DARKNESS);
                Samples grayFov = drawEffects(scene, terrain, atmosphere, field, camera, mask);
                assertEquals(both.visible(), grayFov.visible());
                assertEquals(both.blocked(), grayFov.blocked());
                assertTrue(chroma(grayFov.sensor()) <= 1, "FoV grayscale must only desaturate sensor-only hexes");

                field.configure(GpuFieldOfView.FOV_STYLE, GpuFieldOfView.FOV_DARKNESS,
                      GpuFieldOfView.Style.GRAYSCALE, GpuFieldOfView.SENSOR_DARKNESS);
                Samples graySensor = drawEffects(scene, terrain, atmosphere, field, camera, mask);
                assertEquals(both.visible(), graySensor.visible());
                assertEquals(both.sensor(), graySensor.sensor());
                assertTrue(chroma(graySensor.blocked()) <= 1, "Sensor grayscale must only desaturate blocked hexes");
            }
            assertEquals(1, field.uploads(), "Both effects and both cameras must reuse the visibility mask");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            field.dispose();
            atmosphere.dispose();
            terrain.dispose();
        }
    }

    private static Samples drawEffects(BoardScene scene, GpuTerrain terrain, GpuAtmosphere atmosphere, GpuFieldOfView field,
          BoardCamera camera, BoardFieldOfView mask) {
        field.update(mask);
        ScreenUtils.clear(0, 0, 0, 1, true);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
        terrain.render(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0, field);
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            return new Samples(sample(pixels, camera, 1), sample(pixels, camera, 4), sample(pixels, camera, 7));
        } finally {
            pixels.dispose();
        }
    }

    private static int sample(Pixmap pixels, BoardCamera camera, int column) {
        Vector3 point = BoardGeometry.center(new Coords(column, 3), 0)
              .add(BoardGeometry.WIDTH * 0.1f, BoardGeometry.HEIGHT * 0.08f, 0);
        camera.camera.project(point);
        int x = Math.round(point.x * pixels.getWidth() / Gdx.graphics.getWidth());
        int y = Math.round(point.y * pixels.getHeight() / Gdx.graphics.getHeight());
        assertTrue(x >= 0 && y >= 0 && x < pixels.getWidth() && y < pixels.getHeight());
        return pixels.getPixel(x, y);
    }

    /** Whether a neighbour of the hex is in clear view: a tree on it may stand in front of the hex, in its own colours. */
    private static boolean besideVisible(BoardScene scene, Coords at) {
        for (int direction = 0; direction < 6; direction++) {
            Coords next = at.translated(direction);
            if (scene.tile(next) != null && GpuFieldOfViewTest.at(scene.fieldOfView(), next).visibility()
                  == BoardFieldOfView.Visibility.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    private static float brightness(int rgb) {
        return 0.2126f * (rgb >>> 24) + 0.7152f * (rgb >>> 16 & 255) + 0.0722f * (rgb >>> 8 & 255);
    }

    private static int chroma(int rgb) {
        int red = rgb >>> 24, green = rgb >>> 16 & 255, blue = rgb >>> 8 & 255;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
    }

    @Test
    void shadingFollowsSculptedTerrainAndVisibilityChangesReuseItsMeshes() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(960, 640);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkSurface(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Terrain-following LOS", failure.get()); }
    }

    private static void checkSurface() throws Exception {
        Coords raised = new Coords(2, 2);
        BoardScene scene = BoardPlaneConnectionsTest.scene(raised, 4);
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuFieldOfView field = new GpuFieldOfView();
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        camera.center(BoardGeometry.center(raised, 2));
        camera.zoom(.4f);
        BoardFieldOfView hidden = new BoardFieldOfView(scene.width(), scene.height(), scene.tiles().stream().map(tile ->
              new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.BLOCKED, 0)).toList(), 255, 0, true, false, false);
        try {
            field.configure(GpuFieldOfView.Style.GRAYSCALE, .5f, GpuFieldOfView.Style.GRAYSCALE, .5f);
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            terrain.setAtmosphere(atmosphere.lighting());
            terrain.update(scene);
            field.update(hidden);
            Object generation = member(terrain, "meshGeneration");
            List<?> chunks = List.copyOf((List<?>) member(terrain, "chunks"));
            Object texture = member(field, "mask");
            assertFalse(chunks.isEmpty());
            int checked = 0;
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                for (int angle = 0; angle < 12; angle++) {
                    camera.orbit(30, 0);
                    BoardFieldOfView preferences = new BoardFieldOfView(hidden.width(), hidden.height(), hidden.hexes(),
                          angle % 2 == 0 ? 255 : 120, angle * 10, true, false, angle % 2 == 0);
                    update(scene, terrain, field, camera, preferences, generation, chunks);
                    ScreenUtils.clear(0, 0, 0, 1, true);
                    atmosphere.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0);
                    terrain.render(camera.camera);
                    atmosphere.end(camera.camera, terrain, scene, 0, field);
                    if (angle % 3 == 0) {
                        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                              "fov-surface-" + perspective + "-" + angle + ".png"));
                    }
                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                    Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
                    FloatBuffer depths = BufferUtils.newFloatBuffer(width * height);
                    Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depths);
                    try {
                        for (int x = 1; x < width; x += 4) {
                            for (int y = 1; y < height; y += 4) {
                                Vector3 surface = new Vector3((x + .5f) / width * 2 - 1, (y + .5f) / height * 2 - 1,
                                      depths.get(y * width + x) * 2 - 1).prj(camera.camera.invProjectionView);
                                var owner = BoardGeometry.tile(scene, surface.x, surface.y);
                                if (owner == null || owner.coords().distance(raised) > 1 || surface.z < 0) { continue; }
                                checked++;
                                int rgb = pixels.getPixel(x, y);
                                assertEquals(rgb >>> 24, rgb >>> 16 & 255, 1, "No gaps in shaded slopes at " + surface);
                                assertEquals(rgb >>> 24, rgb >>> 8 & 255, 1, "No gaps in shaded slopes at " + surface);
                            }
                        }
                    } finally { pixels.dispose(); }
                }
            }
            assertTrue(checked > 10_000, "Sample the raised hex and its sloped neighbors in both projections");
            assertEquals(1, field.uploads(), "Orbits and appearance changes reuse the visibility texture");
            assertSame(texture, member(field, "mask"));

            BoardFieldOfView mixed = new BoardFieldOfView(scene.width(), scene.height(), scene.tiles().stream().map(tile ->
                  tile.coords().equals(raised) ? BoardFieldOfView.Hex.VISIBLE
                        : new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.SENSOR, 0, true)).toList(),
                  255, 0, true, false, false);
            update(scene, terrain, field, camera, mixed, generation, chunks);
            assertEquals(2, field.uploads(), "Changed LOS and sensor classifications upload only the small mask");
            assertSame(texture, member(field, "mask"), "Same-size masks update the existing texture");
            update(scene, terrain, field, camera, BoardFieldOfView.EMPTY, generation, chunks);
            assertFalse(field.active());
            assertEquals(2, field.uploads(), "Disabling LOS requires no upload");
            update(scene, terrain, field, camera, hidden, generation, chunks);
            assertTrue(field.active());
            assertEquals(3, field.uploads());
            assertSame(texture, member(field, "mask"));
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            field.dispose();
            atmosphere.dispose();
            terrain.dispose();
        }
    }

    private static void update(BoardScene scene, GpuTerrain terrain, GpuFieldOfView field, BoardCamera camera,
          BoardFieldOfView mask, Object generation, List<?> chunks) throws Exception {
        BoardScene next = new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(),
              scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(), mask);
        terrain.update(next, camera.camera);
        field.update(next.fieldOfView());
        assertEquals(generation, member(terrain, "meshGeneration"), "LOS must not request a terrain rebuild");
        assertFalse(terrain.busy(), "LOS updates must not schedule terrain work");
        List<?> current = (List<?>) member(terrain, "chunks");
        assertEquals(chunks.size(), current.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertSame(chunks.get(i), current.get(i), "LOS must retain the installed terrain chunks");
        }
    }

    private static Object member(Object owner, String name) throws ReflectiveOperationException {
        var member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }
}
