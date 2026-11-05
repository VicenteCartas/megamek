/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GL coverage, occlusion, wind, LOD, board materials and a repeatable GPU cost report. */
@Tag("on-demand")
class GpuTerrainEffectsSmokeTest {
    private static final int SIZE = 512;
    private static final Color BACKGROUND = new Color(.55f, .62f, .7f, 1);
    // Match contrast()'s actual 8-bit display swatch through the production linear scene/display boundary.
    private static final Color LINEAR_BACKGROUND = GpuColor.linear(new Color(Color.rgba8888(BACKGROUND)));
    private static final Vector3 LIGHT = new Vector3(-.3f, -.4f, -.85f).nor();
    private static BufferedImage displayedBackground;
    private final File output = new File(System.getProperty("megamek.gpu.screenshots"), "fire-smoke");

    @Test
    void screenTerrainDrawsNativeChaffFromBothViews() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        config.setWindowedMode(SIZE, SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var effects = new GpuTerrainEffects();
                var depth = new GpuEffectDepth();
                var target = new GpuSceneTestOutput(SIZE, SIZE);
                try {
                    Files.createDirectories(output.toPath());
                    var surfaces = new BoardSurface.Cache();
                    var centre = BoardGeometry.center(new Coords(0, 0), 0);
                    for (boolean side : new boolean[] { false, true }) {
                        var camera = side ? sideCamera(centre, .7f) : camera(centre, .3f, false);
                        var clear = draw(target, effects, depth, surfaces,
                              scene(1, 1, BoardFireSmoke.NONE, false), camera, null, null);
                        for (int level : new int[] { 0, 1 }) {
                            var hex = new megamek.common.Hex(0, "screen:" + level, "");
                            var image = draw(target, effects, depth, surfaces,
                                  scene(1, 1, BoardFireSmoke.capture(hex), false), camera, null, null);
                            assertEquals(1, effects.size());
                            assertTrue(different(clear, image) > 1000, "Screen terrain must draw a volume");
                            save(image, "screen-" + level + (side ? "-side" : "-top"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { target.close(); effects.dispose(); depth.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native screen terrain", failure.get()); }
    }

    @Test
    void persistentEffectsFollowTheBoardAndStayVisibleAtAllLods() throws Exception {
        boolean closure = Boolean.getBoolean("megamek.gpu.adaptationClosure");
        BoardScene dome = closure ? GpuMagmaSmokeTest.domeScene() : null;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(closure ? 1280 : SIZE, closure ? 900 : SIZE);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    Files.createDirectories(output.toPath());
                    if (closure) {
                        reviewAdaptation(dome);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        return; // Explicit four-still review, not a pass of the default effect suite.
                    }
                    verifyVolumes();
                    verifyRisingPlume();
                    verifyJoinedFields();
                    verifyUnitOutlines();
                    reviewSurfaces();
                    benchmark();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain fire/smoke", failure.get()); }
    }

    private void verifyJoinedFields() throws Exception {
        var effects = new GpuTerrainEffects();
        var depth = new GpuEffectDepth();
        var surfaces = new BoardSurface.Cache();
        var target = new GpuSceneTestOutput(SIZE, SIZE);
        ModelBatch batch = new ModelBatch(GpuUnitShader.provider());
        var smoke = new BoardFireSmoke(0, 2);
        try {
            // Both staggered column parities, every shared edge, every LOD.
            for (int parity = 0; parity < 2; parity++) {
                Coords first = new Coords(2 + parity, 2);
                for (int direction = 0; direction < 6; direction++) {
                    Coords second = first.translated(direction);
                    var scene = scene(6, 6, c -> c.equals(first) || c.equals(second) ? smoke : BoardFireSmoke.NONE, false);
                    Vector3 a = BoardGeometry.center(first, 0), b = BoardGeometry.center(second, 0);
                    Vector3 middle = a.cpy().lerp(b, .5f);
                    for (int lod = 0; lod < 3; lod++) {
                        var camera = camera(middle, new float[] { .55f, 1.5f, 4 }[lod], false);
                        var joined = draw(target, effects, depth, surfaces, scene, camera, null, batch);
                        assertEquals(2, effects.lodCount(lod));
                        for (int sample = 0; sample <= 8; sample++) {
                            assertTrue(contrast(joined, camera, a.cpy().lerp(b, sample / 8f)) > 30,
                                  "No gap between adjacent hexes: parity=" + parity + ", direction=" + direction + ", lod=" + lod);
                        }
                        if (parity == 0 && direction == 1) { save(joined, "joined-pair-lod" + lod); }
                    }
                }
            }
            Coords centre = new Coords(2, 2);
            var cornerSources = Map.of(centre, smoke, centre.translated(0), smoke, centre.translated(1), smoke);
            var cornerScene = scene(5, 5, c -> cornerSources.getOrDefault(c, BoardFireSmoke.NONE), false);
            Vector3 corner = new Vector3();
            for (Coords coords : cornerSources.keySet()) { corner.add(BoardGeometry.center(coords, 0)); }
            corner.scl(1f / 3);
            for (int lod = 0; lod < 3; lod++) {
                var camera = camera(corner, new float[] { .55f, 1.5f, 4 }[lod], false);
                var image = draw(target, effects, depth, surfaces, cornerScene, camera, null, batch);
                assertTrue(contrast(image, camera, corner) > 30, "Three-hex corner stays joined at lod" + lod);
            }
            var ring = scene(5, 5, c -> c.distance(centre) == 1 ? smoke : BoardFireSmoke.NONE, false);
            var camera = camera(BoardGeometry.center(centre, 0), .65f, false);
            // Expanded smoke may cross clear hexes aloft. A near-ground slice must still have no source there.
            camera.position.z = BoardGeometry.level();
            camera.update();
            var ringImage = draw(target, effects, depth, surfaces, ring, camera, null, batch);
            assertEquals(0, contrast(ringImage, camera, BoardGeometry.center(centre, 0)), "A clear hole has no ground source");
            save(ringImage, "joined-ring-clear-centre");
            var line = scene(5, 5, c -> c.getX() == 2 && c.getY() >= 1 && c.getY() <= 3 ? smoke : BoardFireSmoke.NONE, false);
            var connected = draw(target, effects, depth, surfaces, line, camera, null, batch);
            assertTrue(contrast(connected, camera, BoardGeometry.center(centre, 0)) > 30);
            var split = scene(5, 5, c -> c.getX() == 2 && (c.getY() == 1 || c.getY() == 3) ? smoke : BoardFireSmoke.NONE, false);
            var separated = draw(target, effects, depth, surfaces, split, camera, null, batch);
            assertEquals(2, effects.size());
            assertEquals(0, contrast(separated, camera, BoardGeometry.center(centre, 0)), "Removing the connecting hex splits the field");
            save(separated, "joined-field-after-removal");
            camera = camera(BoardGeometry.center(centre, 0), .65f, false);

            // Wind deforms the entire join, not separate independently animated puffs.
            for (int direction : new int[] { 0, 90, 180, 270 }) {
                effects.dispose();
                var weather = BoardFireSmokeTest.wind(.8f, direction);
                for (int frame = 0; frame < 20; frame++) { effects.update(cornerScene, surfaces, weather, .1f); }
                var image = draw(target, effects, depth, surfaces, cornerScene, camera, null, batch);
                assertTrue(contrast(image, camera, corner) > 30, "Wind must not open a gap at a shared corner");
                save(image, "joined-wind-" + direction);
            }
            effects.dispose();
            var mixed = scene(5, 5, c -> c.getX() == 2 && c.getY() >= 1 && c.getY() <= 3
                  ? new BoardFireSmoke(c.getY() == 1 ? 1 : 0, c.getY() == 3 ? 5 : 2) : BoardFireSmoke.NONE, false);
            var mixedImage = draw(target, effects, depth, surfaces, mixed, camera, null, batch);
            save(mixedImage, "joined-fire-smoke-types");
            var sameSmoke = scene(5, 5, c -> c.getX() == 2 && c.getY() >= 1 && c.getY() <= 3
                  ? new BoardFireSmoke(0, c.getY() == 3 ? 5 : 2) : BoardFireSmoke.NONE, false);
            var smokeOnly = draw(target, effects, depth, surfaces, sameSmoke, camera, null, batch);
            assertTrue(warmth(mixedImage) > warmth(smokeOnly) + 1000, "Fire retains its hot core beside smoke-only hexes");

            var field = scene(6, 10, smoke, false);
            Vector3 pivot = BoardGeometry.center(new Coords(3, 4), 0);
            camera = camera(pivot, 1, true);
            camera.position.set(pivot).add(0, -240, 180);
            camera.lookAt(pivot);
            camera.update();
            var mixedLods = draw(target, effects, depth, surfaces, field, camera, null, batch);
            assertTrue(effects.lodCount(0) > 0 && effects.lodCount(1) > 0, "A perspective field spans LOD boundaries");
            save(mixedLods, "joined-perspective-mixed-lods");
        } finally { effects.dispose(); depth.dispose(); target.close(); batch.dispose(); }
    }

    private void verifyVolumes() throws Exception {
        var effects = new GpuTerrainEffects();
        var depth = new GpuEffectDepth();
        var surfaces = new BoardSurface.Cache();
        var target = new GpuSceneTestOutput(SIZE, SIZE);
        ModelBatch batch = new ModelBatch(GpuUnitShader.provider());
        Model wall = new ModelBuilder().createBox(280, 280, 2,
              new Material(ColorAttribute.createDiffuse(.1f, .2f, .3f, 1)), VertexAttributes.Usage.Position);
        var center = BoardGeometry.center(new Coords(0, 0), 0);
        BoardProjectionCamera camera = camera(center, .3f, false);
        BoardScene fire = scene(1, 1, new BoardFireSmoke(1, 0), false);
        BoardScene smoke = scene(1, 1, new BoardFireSmoke(0, 2), false);
        BoardScene clear = scene(1, 1, BoardFireSmoke.NONE, false);
        try {
            BufferedImage empty = draw(target, effects, depth, surfaces, clear, camera, null, batch);
            BufferedImage burning = draw(target, effects, depth, surfaces, fire, camera, null, batch);
            assertTrue(different(empty, burning) > 1000, "Fire must draw");
            assertTrue(Arrays.equals(pixels(burning), pixels(draw(target, effects, depth, surfaces, fire, camera, null, batch))),
                  "A paused board must produce the same field");
            assertEquals(1, effects.lodCount(0));
            BufferedImage cloudy = draw(target, effects, depth, surfaces, smoke, camera, null, batch);
            assertTrue(brightness(cloudy) < brightness(empty), "Smoke absorbs the background");
            assertTrue(warmth(burning) > warmth(cloudy) + 1000, "Fire has emissive warm cores");
            save(burning, "fire-top-lod0");
            save(cloudy, "smoke-top-lod0");
            // All authored palettes at identical density, camera and paused time.
            for (int smokeType : new int[] { megamek.server.SmokeCloud.SMOKE_LIGHT,
                  megamek.server.SmokeCloud.SMOKE_GREEN, megamek.server.SmokeCloud.SMOKE_LI_LIGHT,
                  megamek.server.SmokeCloud.SMOKE_CHAFF_LIGHT }) {
                var coloured = scene(1, 1, new BoardFireSmoke(0, smokeType), false);
                var palette = draw(target, effects, depth, surfaces, coloured, camera, null, batch);
                assertTrue(different(empty, palette) > 1000, "Each smoke palette draws");
                assertArrayEquals(pixels(palette), pixels(draw(target, effects, depth, surfaces, coloured, camera, null, batch)),
                      "Paused palette output repeats exactly");
                save(palette, "smoke-palette-" + smokeType);
            }
            for (int lod = 0; lod < 3; lod++) {
                camera = camera(center, new float[] { .3f, 1.5f, 4 }[lod], false);
                var image = draw(target, effects, depth, surfaces, smoke, camera, null, batch);
                assertEquals(1, effects.lodCount(lod));
                var baseline = draw(target, effects, depth, surfaces, clear, camera, null, batch);
                assertTrue(different(image, baseline) > 8, "lod" + lod + " must retain smoke");
                save(image, "smoke-top-lod" + lod);
                var flameOnly = draw(target, effects, depth, surfaces, fire, camera, null, batch);
                for (int fireType : new int[] { 1, 2 }) {
                    var withSmoke = draw(target, effects, depth, surfaces,
                          scene(1, 1, new BoardFireSmoke(fireType, 2), false), camera, null, batch);
                    assertTrue(warmPixels(flameOnly) > 0 && warmPixels(withSmoke) > warmPixels(flameOnly) * .65,
                          "Smoke over a burning tile must leave its flames readable from above at lod" + lod);
                    save(withSmoke, (fireType == 1 ? "fire" : "inferno") + "-with-smoke-top-lod" + lod);
                }
            }
            camera = camera(center, .3f, false);
            var occluder = new ModelInstance(wall);
            occluder.transform.setToTranslation(center.x, center.y, BoardGeometry.level() * 10);
            var hidden = draw(target, effects, depth, surfaces, fire, camera, occluder, batch);
            var wallOnly = draw(target, effects, depth, surfaces, clear, camera, occluder, batch);
            assertEquals(0, different(hidden, wallOnly), "No low-resolution fire may leak through opaque geometry");
            occluder.transform.setToTranslation(center.x + 140, center.y, BoardGeometry.level() * 10);
            var partial = draw(target, effects, depth, surfaces, fire, camera, occluder, batch);
            var partialBase = draw(target, effects, depth, surfaces, clear, camera, occluder, batch);
            long uncovered = different(partial, partialBase);
            save(partial, "partial-occlusion");
            assertTrue(uncovered > 500 && uncovered < different(burning, empty) * .8,
                  "Partial depth clipping: uncovered=" + uncovered + ", full=" + different(burning, empty));
            for (int y = 0; y < SIZE; y++) {
                for (int x = SIZE / 2 + 2; x < SIZE; x++) {
                    assertEquals(partialBase.getRGB(x, y), partial.getRGB(x, y),
                          "Depth-aware upsampling must not bleed through the wall");
                }
            }
            occluder.transform.setToTranslation(center.x, center.y, BoardGeometry.level() * 3);
            var aboveRoof = draw(target, effects, depth, surfaces, smoke, camera, occluder, batch);
            var roofOnly = draw(target, effects, depth, surfaces, clear, camera, occluder, batch);
            assertTrue(different(aboveRoof, roofOnly) > 500, "Upper smoke remains visible above a lower opaque roof");
            BufferedImage[] wind = new BufferedImage[3];
            for (int i = 0; i < wind.length; i++) {
                effects.dispose();
                var weather = BoardFireSmokeTest.wind(i == 0 ? 0 : 1, i == 2 ? 270 : 90);
                for (int frame = 0; frame < 20; frame++) { effects.update(smoke, surfaces, weather, .1f); }
                wind[i] = draw(target, effects, depth, surfaces, smoke, camera, null, batch);
                save(wind[i], new String[] { "wind-calm", "wind-east", "wind-west" }[i]);
            }
            assertTrue(centroidX(wind[1], empty) > centroidX(wind[0], empty) + 10);
            assertTrue(centroidX(wind[2], empty) < centroidX(wind[0], empty) - 10);
            camera = camera(center, .3f, true);
            save(draw(target, effects, depth, surfaces, fire, camera, null, batch), "fire-perspective");
            camera.position.set(center.x, center.y, 12);
            camera.direction.set(0, 1, .1f).nor();
            camera.far = 18;
            camera.update();
            var inside = draw(target, effects, depth, surfaces, smoke, camera, null, batch);
            var noSmoke = draw(target, effects, depth, surfaces, clear, camera, null, batch);
            assertTrue(different(inside, noSmoke) > 1000, "Near/far planes inside the volume");
            camera = camera(center, .3f, false);
            camera.position.x += 10000;
            camera.update();
            draw(target, effects, depth, surfaces, smoke, camera, null, batch);
            assertEquals(0, effects.visibleCount());
            assertEquals(0, effects.drawCalls(), "Offscreen tiles cost no effect draw calls");
            draw(target, effects, depth, surfaces, clear, camera, null, batch);
            assertEquals(0, effects.size(), "Removing smoke leaves no retained particles");
        } finally {
            effects.dispose(); depth.dispose(); target.close(); wall.dispose(); batch.dispose();
        }
    }

    private void verifyRisingPlume() throws Exception {
        var effects = new GpuTerrainEffects();
        var depth = new GpuEffectDepth();
        var surfaces = new BoardSurface.Cache();
        var target = new GpuSceneTestOutput(SIZE, SIZE);
        Vector3 centre = BoardGeometry.center(new Coords(0, 0), 0);
        var smoke = scene(1, 1, new BoardFireSmoke(0, 2), false);
        var fire = scene(1, 1, new BoardFireSmoke(1, 0), false);
        try {
            for (int lod = 0; lod < 3; lod++) {
                var camera = sideCamera(centre, new float[] { .7f, 1.5f, 4 }[lod]);
                var image = draw(target, effects, depth, surfaces, smoke, camera, null, null);
                assertEquals(1, effects.lodCount(lod));
                double lower = bandContrast(image, camera, centre, 2, 0);
                double middle = bandContrast(image, camera, centre, 4, 0);
                double upper = bandContrast(image, camera, centre, 6.5f, 0);
                assertTrue(middle > 10, "Smoke rises well above the old 2.4-level ceiling at lod" + lod);
                assertTrue(upper < middle * .6 && middle < lower, "Smoke progressively dissipates at lod" + lod);
                assertTrue(plumeWidth(image, camera, centre, 4) > plumeWidth(image, camera, centre, 1) * 1.15,
                      "Rising smoke spreads horizontally instead of retaining a pillar silhouette at lod" + lod);
                assertEquals(0, bandContrast(image, camera, centre, 9, 0), "No hard top remaining above the plume");
                save(image, "rising-smoke-side-lod" + lod);
            }
            var camera = sideCamera(centre, .7f);
            save(draw(target, effects, depth, surfaces, fire, camera, null, null), "rising-fire-side");
            for (int direction : new int[] { 90, 270 }) {
                effects.dispose();
                var weather = BoardFireSmokeTest.wind(.8f, direction);
                for (int frame = 0; frame < 30; frame++) { effects.update(smoke, surfaces, weather, .1f); }
                var image = draw(target, effects, depth, surfaces, smoke, camera, null, null);
                float drift = direction == 90 ? .8f : -.8f;
                assertTrue(bandContrast(image, camera, centre, 4, drift) > 10, "Rising smoke follows the wind");
                assertTrue(bandContrast(image, camera, centre, 4, drift)
                      > bandContrast(image, camera, centre, 4, -drift) + 10, "The upper plume is downwind");
                save(image, "rising-smoke-wind-" + direction);
            }
        } finally { effects.dispose(); depth.dispose(); target.close(); }
    }

    private static BoardProjectionCamera sideCamera(Vector3 centre, float zoom) {
        var camera = camera(centre, zoom, false);
        camera.position.set(centre).add(0, -400, BoardGeometry.level() * 4);
        camera.up.set(Vector3.Z);
        camera.direction.set(Vector3.Y);
        camera.update();
        return camera;
    }

    private static double bandContrast(BufferedImage image, BoardProjectionCamera camera, Vector3 centre, float levels, float drift) {
        double result = 0;
        for (int sample = -4; sample <= 4; sample++) {
            result += contrast(image, camera, centre.cpy().add(drift * levels * BoardGeometry.level()
                  + sample * BoardGeometry.width() * .05f, 0, levels * BoardGeometry.level()));
        }
        return result / 9;
    }

    /** Contrast-weighted spread measures shape independently of the much lower opacity higher up. */
    private static double plumeWidth(BufferedImage image, BoardProjectionCamera camera, Vector3 centre, float levels) {
        Vector3 screen = camera.project(centre.cpy().add(0, 0, levels * BoardGeometry.level()), 0, 0,
              image.getWidth(), image.getHeight());
        int y = image.getHeight() - 1 - Math.round(screen.y), background = Color.rgb888(BACKGROUND);
        double mass = 0, sum = 0, square = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            double weight = 0;
            for (int shift : new int[] { 0, 8, 16 }) {
                weight += Math.abs(((rgb >> shift) & 255) - ((background >> shift) & 255));
            }
            mass += weight;
            sum += x * weight;
            square += x * x * weight;
        }
        return mass == 0 ? 0 : Math.sqrt(Math.max(0, square / mass - Math.pow(sum / mass, 2)));
    }

    private void verifyUnitOutlines() throws Exception {
        var terrain = new GpuTerrain();
        var atmosphere = new GpuAtmosphere();
        var visibility = new GpuUnitVisibility();
        var effects = new GpuTerrainEffects();
        var depth = new GpuEffectDepth();
        var surfaces = new BoardSurface.Cache();
        var models = new GpuUnitModels();
        var batch = new ModelBatch(GpuUnitShader.provider());
        try {
            var smoke = scene(1, 1, new BoardFireSmoke(0, 2), false);
            var centre = BoardGeometry.center(new Coords(0, 0), 0);
            var camera = sideCamera(centre, .35f);
            camera.viewportWidth = Gdx.graphics.getWidth();
            camera.viewportHeight = Gdx.graphics.getHeight();
            camera.update();
            var atlas = models.get(new BoardScene.UnitModel("units/modular/meks/atlas.json",
                  "units/modular/meks/fallback-biped-heavy.json", "Atlas AS7-D", 1, 0, BoardScene.LocationDamage.NONE,
                  UnitModelState.capture(new megamek.common.units.BipedMek())));
            var unit = new ModelInstance(atlas.instance.model);
            unit.userData = new Color(.1f, .6f, 1, GpuUnitVisibility.ownHex(smoke.tile(new Coords(0, 0))));
            atlas.place(unit, camera, centre.cpy().add(0, 25, 0), 0, 2, false);
            for (BoardFireSmoke source : List.of(new BoardFireSmoke(0, 2), new BoardFireSmoke(1, 0))) {
                var scene = scene(1, 1, source, false);
                var off = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, scene, camera, List.of(unit), 0);
                var on = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, scene, camera, List.of(unit), .75f);
                assertTrue(different(off, on) > 100, "A unit behind dense smoke/flame keeps its outline");
                save(on, source.fire() > 0 ? "unit-behind-fire" : "unit-behind-smoke");
            }
            atlas.place(unit, camera, centre.cpy().add(0, -100, 0), 0, 2, false);
            var absentOff = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, smoke, camera, List.of(), 0);
            var absentOn = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, smoke, camera, List.of(), .75f);
            assertEquals(0, different(absentOff, absentOn), "A unit absent from the visible snapshot must not be revealed");
            var clear = scene(1, 1, BoardFireSmoke.NONE, false);
            var clearOff = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, clear, camera, List.of(unit), 0);
            var clearOn = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, clear, camera, List.of(unit), .75f);
            assertEquals(0, different(clearOff, clearOn), "Removed smoke must not leave a stale outline mask");

            var burning = scene(3, 3, c -> c.getY() == 1 ? new BoardFireSmoke(1, 0) : BoardFireSmoke.NONE, false);
            centre = BoardGeometry.center(new Coords(1, 1), 0);
            camera = sideCamera(centre, .45f);
            camera.viewportWidth = Gdx.graphics.getWidth();
            camera.viewportHeight = Gdx.graphics.getHeight();
            camera.position.x += 40;
            camera.update();
            atlas.place(unit, camera, centre.cpy().add(0, 25, 0), 0, 2, false);
            var wind = BoardFireSmokeTest.wind(.7f, 90);
            for (int frame = 0; frame < 30; frame++) { effects.update(burning, surfaces, wind, .1f); }
            var writer = ImageIO.getImageWritersByFormatName("gif").next();
            try (var stream = ImageIO.createImageOutputStream(new File(output, "rising-fire-smoke.gif"))) {
                writer.setOutput(stream);
                writer.prepareWriteSequence(null);
                BufferedImage first = null, last = null;
                for (int frame = 0; frame < 80; frame++) {
                    effects.update(burning, surfaces, wind, .05f);
                    last = drawOutlined(terrain, atmosphere, visibility, effects, depth, surfaces, batch, burning, camera, List.of(unit), .75f);
                    if (first == null) { first = last; }
                    if (frame % 20 == 0) { save(last, "animation-frame-" + frame); }
                    var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(last), null);
                    var root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                    var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                    control.setAttribute("delayTime", "5");
                    control.setAttribute("disposalMethod", "none");
                    if (frame == 0) {
                        var extensions = new IIOMetadataNode("ApplicationExtensions");
                        var loop = new IIOMetadataNode("ApplicationExtension");
                        loop.setAttribute("applicationID", "NETSCAPE");
                        loop.setAttribute("authenticationCode", "2.0");
                        loop.setUserObject(new byte[] { 1, 0, 0 });
                        extensions.appendChild(loop);
                        root.appendChild(extensions);
                    }
                    metadata.setFromTree("javax_imageio_gif_image_1.0", root);
                    writer.writeToSequence(new IIOImage(last, null, metadata), null);
                }
                writer.endWriteSequence();
                assertTrue(different(first, last) > 1000, "The live plume must animate while time advances");
                save(last, "rising-fire-smoke-board");
            } finally { writer.dispose(); }
        } finally {
            terrain.dispose(); atmosphere.dispose(); visibility.dispose(); effects.dispose(); depth.dispose(); models.dispose(); batch.dispose();
        }
    }

    private static BufferedImage drawOutlined(GpuTerrain terrain, GpuAtmosphere atmosphere, GpuUnitVisibility visibility,
          GpuTerrainEffects effects, GpuEffectDepth depth, BoardSurface.Cache surfaces, ModelBatch batch, BoardScene scene,
          BoardProjectionCamera camera, List<ModelInstance> units, float intensity) {
        terrain.update(scene);
        atmosphere.updateLight(camera);
        terrain.setAtmosphere(atmosphere.lighting());
        effects.update(scene, surfaces, BoardAtmosphere.Effects.NONE, 0);
        ScreenUtils.clear(BACKGROUND.r, BACKGROUND.g, BACKGROUND.b, 1, true);
        atmosphere.begin((int) camera.viewportWidth, (int) camera.viewportHeight, 0);
        terrain.render(camera);
        batch.begin(camera);
        units.forEach(unit -> batch.render(unit, terrain.environment()));
        batch.end();
        terrain.renderTransparent(camera);
        depth.begin();
        effects.render(camera, depth, atmosphere.sceneParticleLight(), atmosphere.lighting().direction());
        atmosphere.end(camera, terrain, scene, 0);
        visibility.render(camera, units, atmosphere.depthTexture(), 0, intensity, 1, null, effects.opacityTexture());
        return capture(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static BufferedImage draw(GpuSceneTestOutput target, GpuTerrainEffects effects, GpuEffectDepth depth,
          BoardSurface.Cache surfaces, BoardScene scene, BoardProjectionCamera camera, ModelInstance wall, ModelBatch batch) {
        effects.update(scene, surfaces, BoardAtmosphere.Effects.NONE, 0);
        target.scene().begin();
        // The previous display pass disables depth writes; a depth clear obeys that mask too.
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(LINEAR_BACKGROUND.r * GpuSceneRadiance.SCALE,
              LINEAR_BACKGROUND.g * GpuSceneRadiance.SCALE, LINEAR_BACKGROUND.b * GpuSceneRadiance.SCALE, 1, true);
        if (displayedBackground == null || displayedBackground.getWidth() != target.scene().getWidth()
              || displayedBackground.getHeight() != target.scene().getHeight()) {
            // Contrast isolates the effect from the actual display transfer and rounding.
            target.display();
            displayedBackground = capture(target.scene().getWidth(), target.scene().getHeight());
            target.scene().begin();
            Gdx.gl.glDepthMask(true);
        }
        if (wall != null) { batch.begin(camera); batch.render(wall); batch.end(); }
        depth.begin();
        effects.render(camera, depth, Color.WHITE, LIGHT);
        target.display();
        var image = capture(target.scene().getWidth(), target.scene().getHeight());
        FrameBuffer.unbind();
        return image;
    }

    private static BufferedImage capture(int width, int height) {
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
        var image = new BufferedImage(pixels.getWidth(), pixels.getHeight(), BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    image.setRGB(x, pixels.getHeight() - 1 - y, pixels.getPixel(x, y) >>> 8);
                }
            }
        } finally { pixels.dispose(); }
        return image;
    }

    /** Appearance evidence only; the default fire/smoke numerical and performance checks stay separate. */
    private void reviewAdaptation(BoardScene dome) throws Exception {
        File directory = new File(output, "adaptation-closure");
        Files.createDirectory(directory.toPath());
        assertEquals(1280, Gdx.graphics.getBackBufferWidth());
        assertEquals(900, Gdx.graphics.getBackBufferHeight());
        assertEquals(null, System.getProperty("megamek.gpu.shaderAuditBaseline"), "Current source only");
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        assertTrue(renderer.toLowerCase(java.util.Locale.ROOT).contains(System.getProperty(
              "megamek.gpu.expectedRenderer", "NVIDIA").toLowerCase(java.util.Locale.ROOT)), renderer);
        var report = new StringBuilder("Appearance review only: four current-source full-composite stills.\n")
              .append("GPU: ").append(renderer).append("\nOptions: ").append(GpuAtmosphere.Options.DEFAULTS)
              .append("\nRaw values below are stored RGBA16F (RGB scale 1/32); depth is actual scene depth.\n")
              .append("Vacuum moonless night retains the published stellar source; it is not zero source.\n");
        try {
            for (int sceneIndex = 0; sceneIndex < 2; sceneIndex++) {
                boolean lava = sceneIndex == 0;
                BoardScene board = lava ? dome : scene(6, 3, new BoardFireSmoke(1, 1), true);
                var terrain = new GpuTerrain();
                var effects = new GpuTerrainEffects();
                var depth = new GpuEffectDepth();
                var atmosphere = new GpuAtmosphere();
                var weather = lava ? BoardAtmosphere.Effects.NONE : BoardFireSmokeTest.wind(.5f, 90);
                var surfaces = new BoardSurface.Cache();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.fit(board);
                    if (!lava) { camera.camera.zoom = .9f; camera.update(); }
                    float[] firstDepth = null;
                    for (float hour : new float[] { 13, 0 }) {
                        var settings = new BoardAtmosphere.Settings(hour, 0, 0, 2, 0, 0, weather,
                              lava ? Atmosphere.VACUUM : Atmosphere.STANDARD, 25, false);
                        atmosphere.configure(settings);
                        atmosphere.updateLight(camera.camera);
                        terrain.setAtmosphere(atmosphere.lighting());
                        atmosphere.configureClouds(terrain, board);
                        if (hour == 13) { terrain.update(board); }
                        GpuReviewFrame.settle(terrain, null, board, camera);
                        if (hour == 13) {
                            terrain.animate(.5f, List.of());
                            if (!lava) {
                                for (int tick = 0; tick < 25; tick++) { effects.update(board, surfaces, weather, .1f); }
                            }
                        }
                        float clock = (Float) closureField(terrain, "magmaClock");
                        assertEquals(.5f, clock, 0, "The shared terrain clock remains fixed across lighting changes");
                        var motion = (GpuTerrainEffects.Motion) closureField(effects, "motion");
                        Vector3 offset = motion.offset.cpy();
                        for (int warmup = 0; warmup < 3; warmup++) {
                            drawClosure(terrain, effects, depth, atmosphere, board, camera, !lava);
                        }
                        String name = (lava ? "dome-vacuum-" : "fire-smoke-standard-")
                              + (hour == 13 ? "day" : "moonless-night");
                        ClosureFrame first = closureFrame(atmosphere);
                        ImageIO.write(first.image(), "png", new File(directory, name + ".png"));
                        drawClosure(terrain, effects, depth, atmosphere, board, camera, !lava);
                        ClosureFrame repeat = closureFrame(atmosphere);
                        assertArrayEquals(pixels(first.image()), pixels(repeat.image()), name + " frozen display");
                        assertArrayEquals(first.raw(), repeat.raw(), 0, name + " frozen scene radiance");
                        assertArrayEquals(first.depth(), repeat.depth(), 0, name + " frozen scene depth");
                        if (firstDepth != null) { assertArrayEquals(firstDepth, first.depth(), 0, "Day/night geometry"); }
                        firstDepth = first.depth();
                        assertEquals(clock, (Float) closureField(terrain, "magmaClock"), 0, "Drawing keeps owning time");
                        assertEquals(offset, motion.offset, "Drawing keeps the effect advection field");
                        var light = atmosphere.lighting();
                        report.append(name).append("\n  settings=").append(settings)
                              .append("\n  camera=").append(Arrays.toString(camera.camera.combined.val))
                              .append("\n  clock=").append(clock).append(" effectOffset=").append(offset)
                              .append("\n  direct=").append(closureRgb(light.direct()))
                              .append(" ambient=").append(closureRgb(light.ambient()))
                              .append(" sky=").append(closureRgb(light.sky()))
                              .append(" horizon=").append(closureRgb(light.horizon()))
                              .append("\n  gain=").append(atmosphere.exposure())
                              .append(" direction=").append(light.direction())
                              .append(" particleLight=").append(closureRgb(atmosphere.sceneParticleLight()))
                              .append("\n  finite=true exactRepeat=true comparedWithDayDepth=").append(hour == 0).append('\n');
                        float maximum = 0;
                        int occupied = 0;
                        for (int pixel = 0; pixel < first.depth().length; pixel++) {
                            if (first.depth()[pixel] < 1) { occupied++; }
                            for (int channel = 0; channel < 3; channel++) {
                                maximum = Math.max(maximum, first.raw()[pixel * 4 + channel]);
                            }
                        }
                        report.append("  storedRgbMaximum=").append(maximum).append(" depthPixels=")
                              .append(occupied).append('\n');
                        assertTrue(occupied > 0, "Actual scene geometry must be captured");
                    }
                } finally { terrain.dispose(); effects.dispose(); depth.dispose(); atmosphere.dispose(); }
            }
            report.append("PASS: four captures, exact raw/display/depth repeats. Appearance review remains separate.\n");
        } finally { Files.writeString(new File(directory, "review.txt").toPath(), report); }
    }

    private static void drawClosure(GpuTerrain terrain, GpuTerrainEffects effects, GpuEffectDepth depth,
          GpuAtmosphere atmosphere, BoardScene board, BoardCamera camera, boolean fire) {
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.renderShadows(camera.camera, List.of());
        atmosphere.prepareClouds(terrain, board, 0);
        atmosphere.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        if (fire) {
            depth.begin();
            effects.render(camera.camera, depth, atmosphere.sceneParticleLight(), atmosphere.lighting().direction());
        }
        atmosphere.renderWeather(camera.camera, board);
        atmosphere.end(camera.camera, terrain, board, 0);
    }

    private record ClosureFrame(BufferedImage image, float[] raw, float[] depth) { }

    private static ClosureFrame closureFrame(GpuAtmosphere atmosphere) throws Exception {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        BufferedImage image = capture(width, height);
        var scene = (FrameBuffer) closureField(atmosphere, "sceneColor");
        assertEquals(width, scene.getWidth()); assertEquals(height, scene.getHeight());
        var viewport = BufferUtils.newIntBuffer(4);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        float[] raw = new float[width * height * 4], depth = new float[width * height];
        var values = BufferUtils.newFloatBuffer(raw.length);
        scene.begin();
        try {
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_FLOAT, values);
            values.get(raw); values.clear();
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, values);
            values.get(depth);
            for (float value : raw) { assertTrue(Float.isFinite(value), "Finite raw RGBA16F"); }
            for (float value : depth) {
                assertTrue(Float.isFinite(value) && value >= 0 && value <= 1, "Finite normalized scene depth");
            }
        } finally {
            scene.end();
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        }
        return new ClosureFrame(image, raw, depth);
    }

    private static Object closureField(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static String closureRgb(Color color) {
        return color.r + "," + color.g + "," + color.b;
    }


    private void reviewSurfaces() throws Exception {
        var terrain = new GpuTerrain();
        var effects = new GpuTerrainEffects();
        var depth = new GpuEffectDepth();
        var atmosphere = new GpuAtmosphere();
        var surfaces = new BoardSurface.Cache();
        try {
            var scene = scene(6, 3, new BoardFireSmoke(1, 1), true);
            terrain.update(scene);
            var camera = new BoardCamera();
            int width = Gdx.graphics.getWidth(), height = Gdx.graphics.getHeight();
            camera.resize(width, height);
            camera.fit(scene);
            camera.camera.zoom = .9f;
            camera.setIsometric(true);
            var weather = BoardFireSmokeTest.wind(.5f, 90);
            atmosphere.configure(new BoardAtmosphere.Settings(13, 0, 0, 2, 0, 0, weather));
            for (int frame = 0; frame < 26; frame++) {
                if (frame == 25) { camera.setIsometric(false); }
                effects.update(scene, surfaces, weather, .1f);
                atmosphere.updateLight(camera.camera);
                terrain.setAtmosphere(atmosphere.lighting());
                terrain.renderShadows(camera.camera, List.of());
                atmosphere.prepareClouds(terrain, scene, 0);
                atmosphere.begin(width, height, 0);
                terrain.render(camera.camera);
                terrain.renderTransparent(camera.camera);
                depth.begin();
                effects.render(camera.camera, depth, atmosphere.sceneParticleLight(), atmosphere.lighting().direction());
                atmosphere.end(camera.camera, terrain, scene, 0);
                if (frame == 24) { GpuReviewFrame.save(new File(output, "board-surfaces.png")); }
            }
            GpuReviewFrame.save(new File(output, "board-top.png"));
        } finally { terrain.dispose(); effects.dispose(); depth.dispose(); atmosphere.dispose(); }
    }

    private void benchmark() throws Exception {
        var effects = new GpuTerrainEffects();
        var depth = new GpuEffectDepth();
        var surfaces = new BoardSurface.Cache();
        var target = new GpuSceneTestOutput(1920, 1080);
        StringBuilder report = new StringBuilder("GPU: " + Gdx.gl.glGetString(GL20.GL_RENDERER)
              + "\n1920x1080; effect only, including depth copy, half-resolution volume pass and upsampling.\n"
              + "20 warmup + 80 measured frames. No terrain, units, UI or readback in measured interval.\n");
        try {
            for (int lod = 0; lod < 3; lod++) {
                int width = 12 << lod, height = 8 << lod;
                var scene = scene(width, height, new BoardFireSmoke(1, 2), false);
                var center = BoardGeometry.center(new Coords(width / 2, height / 2), 0);
                var camera = camera(center, new float[] { .7f, 1.5f, 4 }[lod], false);
                camera.viewportWidth = 1920; camera.viewportHeight = 1080; camera.update();
                effects.update(scene, surfaces, BoardFireSmokeTest.wind(.5f, 90), 0);
                try (var timing = new GpuStageTimings()) {
                    for (int frame = 0; frame < 100; frame++) {
                        target.scene().begin();
                        Gdx.gl.glDepthMask(true);
                        ScreenUtils.clear(.4f * GpuSceneRadiance.SCALE, .5f * GpuSceneRadiance.SCALE,
                              .6f * GpuSceneRadiance.SCALE, 1, true);
                        depth.begin();
                        if (frame >= 20) { timing.beginFrame(); timing.stage("fire-smoke"); }
                        effects.render(camera, depth, Color.WHITE, LIGHT);
                        if (frame >= 20) { timing.stage(null); }
                        Gdx.gl.glFinish();
                        FrameBuffer.unbind();
                    }
                    report.append("lod").append(lod).append(": ").append(effects.visibleCount()).append(" visible / ")
                          .append(effects.size()).append(" total; ").append(effects.drawCalls()).append(" draw calls\n");
                    assertEquals(effects.visibleCount(), effects.lodCount(lod));
                    assertTrue(effects.drawCalls() <= (effects.visibleCount() + 255) / 256 + 1);
                    timing.appendReport(report, "lod" + lod);
                }
                var field = draw(target, effects, depth, surfaces, scene, camera, null, null);
                save(field, "joined-field-lod" + lod);
                // Flames intentionally have gaps and lighter soot. Dense smoke exercises continuity between batches.
                var smokeField = draw(target, effects, depth, surfaces,
                      scene(width, height, new BoardFireSmoke(0, 2), false), camera, null, null);
                for (int x = 1; x < width - 1; x++) {
                    for (int y = 1; y < height - 1; y++) {
                        var point = BoardGeometry.center(new Coords(x, y), 0)
                              .lerp(BoardGeometry.center(new Coords(x, y + 1), 0), .5f);
                        assertTrue(contrast(smokeField, camera, point) > 30, "A connected field stays filled across batch boundaries");
                    }
                }
            }
            Files.writeString(new File(output, "timing.txt").toPath(), report);
        } finally { effects.dispose(); depth.dispose(); target.close(); }
    }

    private static BoardProjectionCamera camera(Vector3 center, float zoom, boolean perspective) {
        var camera = new BoardProjectionCamera();
        camera.viewportWidth = SIZE; camera.viewportHeight = SIZE; camera.zoom = zoom;
        camera.perspective = perspective;
        camera.position.set(center).add(perspective ? 115 : 0, perspective ? -150 : 0, perspective ? 100 : 300);
        camera.up.set(perspective ? Vector3.Z : Vector3.Y);
        camera.lookAt(center.x, center.y, 18);
        camera.near = .1f; camera.far = 10000;
        camera.update();
        return camera;
    }

    private static BoardScene scene(int width, int height, BoardFireSmoke effect, boolean materials) {
        return scene(width, height, ignored -> effect, materials);
    }

    private static BoardScene scene(int width, int height, Function<Coords, BoardFireSmoke> effects, boolean materials) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                var family = materials ? BoardScene.Surface.values()[x % 6] : BoardScene.Surface.GRASS;
                var coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, family, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE,
                      materials && y != 1 ? BoardFireSmoke.NONE : effects.apply(coords)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static double contrast(BufferedImage image, BoardProjectionCamera camera, Vector3 world) {
        Vector3 screen = camera.project(world.cpy(), 0, 0, image.getWidth(), image.getHeight());
        int x = Math.round(screen.x), y = image.getHeight() - 1 - Math.round(screen.y);
        double difference = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int actual = image.getRGB(x + dx, y + dy);
                int background = displayedBackground.getRGB(x + dx, y + dy);
                for (int shift : new int[] { 0, 8, 16 }) {
                    difference += Math.abs(((actual >> shift) & 255) - ((background >> shift) & 255));
                }
            }
        }
        return difference / 9;
    }

    private void save(BufferedImage image, String name) throws Exception {
        ImageIO.write(image, "png", new File(output, name + ".png"));
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static long different(BufferedImage a, BufferedImage b) {
        int[] first = pixels(a), second = pixels(b);
        long count = 0;
        for (int i = 0; i < first.length; i++) { if (first[i] != second[i]) { count++; } }
        return count;
    }

    private static long brightness(BufferedImage image) {
        long result = 0;
        for (int rgb : pixels(image)) { result += ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255); }
        return result;
    }

    private static long warmth(BufferedImage image) {
        long result = 0;
        for (int rgb : pixels(image)) { result += ((rgb >> 16) & 255) - (rgb & 255); }
        return result;
    }

    private static long warmPixels(BufferedImage image) {
        return Arrays.stream(pixels(image)).filter(rgb -> ((rgb >> 16) & 255) > (rgb & 255) + 50
              && ((rgb >> 16) & 255) > ((rgb >> 8) & 255) + 15).count();
    }

    private static double centroidX(BufferedImage image, BufferedImage background) {
        int[] actual = pixels(image), clear = pixels(background);
        long sum = 0, count = 0;
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != clear[i]) { sum += i % image.getWidth(); count++; }
        }
        return sum / (double) Math.max(1, count);
    }
}
