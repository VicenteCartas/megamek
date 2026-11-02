/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real glyph pixels, sculpted/scattered geometry and scene depth in both cameras, including a docked viewport. */
@Tag("on-demand")
class GpuHexTextSmokeTest {
    private static final Coords LABELED = new Coords(3, 3);
    private static final int BOTTOM = 67;

    @Test
    void topViewRevealsEnabledLabelsWhileAngledGroundLabelsStillRespectSceneDepth() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkLabels();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Native terrain label readability", failure.get()); }
    }

    private static void checkLabels() {
        GpuTerrain terrain = new GpuTerrain();
        // The contrast/history fixtures below straddle .2, independently of the player's default threshold.
        GpuAtmosphere atmosphere = new GpuAtmosphere(.2f);
        GpuHexText labels = new GpuHexText();
        GpuBoardSkin skin = new GpuBoardSkin();
        SpriteBatch batch = new SpriteBatch();
        ShapeRenderer object = new ShapeRenderer();
        try {
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight() - BOTTOM);
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            terrain.setAtmosphere(atmosphere.lighting());
            BoardScene scene = scene(false, true);
            terrain.update(scene);
            var font = skin.skin.getFont("default-font");
            var outline = skin.skin.getFont(GpuBoardSkin.OUTLINE_FONT);
            for (boolean isometric : List.of(false, true)) {
                camera.setIsometric(isometric);
                camera.camera.zoom = .20f;
                camera.center(BoardGeometry.center(LABELED, 2));
                terrain.renderShadows(camera.camera, List.of());
                labels.update(scene, font, outline, terrain::roofBounds);
                frame(terrain, atmosphere, camera, scene, null);
                labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
                Pixmap actual = pixels();
                capture(isometric ? "iso" : "top");
                // Clear only the destination depth: the identical glyphs now have no possible occluder.
                ScreenUtils.clear(0, 0, 0, 1, true);
                labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
                Pixmap expected = pixels();
                try {
                    int total = pink(expected);
                    assertTrue(total > 250, "The fixture must contain substantial, fully opaque coordinate and LEVEL glyphs");
                    int missing = missing(expected, actual);
                    assertTrue(missing <= total * .01, "Own relief must not cut through lettering in "
                          + (isometric ? "isometric" : "top") + ": " + missing + "/" + total);
                    // Negative control: the previous flat depth test must fail on exactly the same rocks and glyphs.
                    labels.update(scene(false, false), font, outline, terrain::roofBounds);
                    frame(terrain, atmosphere, camera, scene, null);
                    labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
                    Pixmap flat = pixels();
                    try {
                        if (isometric) {
                            assertTrue(missing(expected, flat) > total * .10,
                                  "The regression fixture must reproduce clipping with the old flat label plane");
                        } else {
                            assertTrue(missing(expected, flat) <= total * .01,
                                  "Top view must reveal labels independently of the relief envelope");
                        }
                    } finally { flat.dispose(); }
                } finally { actual.dispose(); expected.dispose(); }
            }

            // A foreground plateau is a different hex even where its wall lies on the exact shared boundary.
            BoardScene cliff = scene(true, true);
            terrain.update(cliff);
            labels.update(cliff, font, outline, terrain::roofBounds);
            camera.setIsometric(true);
            camera.center(BoardGeometry.center(LABELED, 2));
            frame(terrain, atmosphere, camera, cliff, null);
            labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
            Pixmap hidden = pixels();
            try { assertEquals(0, pink(hidden), "Terrain labels must not be revealed through a foreground cliff"); }
            finally { hidden.dispose(); }
            capture("occluded-cliff");

            // Top view reveals both ground labels through a roof well above their relief envelope.
            terrain.update(scene);
            labels.update(scene, font, outline, terrain::roofBounds);
            camera.setIsometric(false);
            camera.center(BoardGeometry.center(LABELED, 2));
            frame(terrain, atmosphere, camera, scene, object);
            labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
            Pixmap covered = pixels();
            try { assertTrue(pink(covered) > 250, "Top-view coordinates and LEVEL must show through a taller object"); }
            finally { covered.dispose(); }
            checkUnitOcclusion(terrain, atmosphere, camera, scene, labels, batch);
            checkHeightOcclusion(terrain, atmosphere, labels, font, outline, batch, camera);
            checkTopGrid(camera, scene);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Overlay drawing must leave valid GL state");
            checkStaticMeshes(batch);
            checkManyAtlases(batch);
            checkContrast(terrain, atmosphere, batch, font, outline);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            object.dispose(); batch.dispose(); skin.dispose(); labels.dispose(); atmosphere.dispose(); terrain.dispose();
        }
    }

    /** Known HDR backgrounds exercise the real composite, grid, static glyphs and borrowed-scene bindings. */
    private static void checkContrast(GpuTerrain terrain, GpuAtmosphere atmosphere, SpriteBatch batch,
          BitmapFont font, BitmapFont outline) {
        GpuHexText labels = new GpuHexText();
        GpuHexGrid grid = new GpuHexGrid();
        BoardScene scene = contrastScene(0xff000000);
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight() - BOTTOM);
        try {
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                for (boolean isometric : List.of(false, true)) {
                    camera.setIsometric(isometric);
                    camera.camera.zoom = .20f;
                    camera.center(BoardGeometry.center(LABELED, 0));
                    for (boolean outlined : List.of(false, true)) {
                        labels.update(scene, font, outline, c -> null, false, false, outlined);
                        List<Object> uploaded = pages(labels);
                        for (int source = 0; source < 3; source++) {
                            float[] rgb = source == 0 ? new float[] { .003f, .003f, .003f }
                                  : source == 1 ? new float[] { .214f, .214f, .214f }
                                  : new float[] { 2, .7f, .02f };
                            contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                            Pixmap background = pixels();
                            labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
                            Pixmap reference = pixels();
                            float[] depth = source == 0 && !outlined ? contrastDepth() : null;
                            contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                            labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
                            Pixmap adaptive = pixels();
                            try {
                                if (depth != null) { assertArrayEquals(depth, contrastDepth(), "Adaptive ink cannot change depth"); }
                                double ink = contrastInk(background, reference, adaptive, false);
                                assertTrue(source == 0 ? ink > 245 : ink < 10,
                                      "Actual neutral ink must contrast dark, mid-gray and HDR colored surfaces: " + ink
                                            + ", perspective=" + perspective + ", isometric=" + isometric
                                            + ", outlined=" + outlined + ", source=" + source);
                                if (outlined) {
                                    double ring = contrastInk(background, reference, adaptive, true);
                                    assertTrue(source == 0 ? ring < 10 : ring > 245,
                                          "The enabled outline retains complementary polarity: " + ring);
                                }
                                contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
                                Pixmap repeated = pixels();
                                try { assertTrue(adaptive.getPixels().equals(repeated.getPixels()), "Paused source repeats exactly"); }
                                finally { repeated.dispose(); }
                                assertEquals(uploaded, pages(labels), "Lighting changes must not rebuild any glyph page");
                                if (!perspective && outlined) { capture("contrast-" + isometric + "-" + source); }
                            } finally { background.dispose(); reference.dispose(); adaptive.dispose(); }
                        }
                    }
                }
            }

            camera.setPerspective(false);
            camera.setIsometric(false);
            camera.camera.zoom = .20f;
            camera.center(BoardGeometry.center(LABELED, 0));
            labels.update(scene, font, outline, c -> null, false, false, false);
            List<Object> uploaded = pages(labels);
            boolean sawWhite = false, sawBlack = false;
            float[] twilight = { .06f, .06f, .06f };
            for (int ev = -2; ev <= 2; ev++) {
                contrastFrame(terrain, atmosphere, camera, scene, twilight, ev);
                Pixmap background = pixels();
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
                Pixmap reference = pixels();
                contrastFrame(terrain, atmosphere, camera, scene, twilight, ev);
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
                Pixmap adaptive = pixels();
                try {
                    double ink = contrastInk(background, reference, adaptive, false);
                    System.out.printf(java.util.Locale.ROOT, "HEX_INK ev=%d mean=%.9f%n", ev, ink);
                    assertTrue(ink < 10 || ink > 245, "A hex must choose binary ink, never intermediate gray");
                    // Compare the binary choice: antialiased white blends and the quantized mask vary with the background.
                    assertFalse(sawBlack && ink > 245, "Increasing shared exposure cannot switch black ink back to white");
                    if (ev == -2) { assertTrue(ink > 245); }
                    if (ev == 2) { assertTrue(ink < 10); }
                    sawWhite |= ink > 245;
                    sawBlack |= ink < 10;
                    assertEquals(uploaded, pages(labels), "Exposure only changes uniforms");
                } finally { background.dispose(); reference.dispose(); adaptive.dispose(); }
            }
            assertTrue(sawWhite && sawBlack, "The exposure sweep must exercise both sides of the fixed threshold");

            // Independent linear grays bracket display brightness .2 (approximately .194 and .206).
            // Normalize by the actual exposure so this tests the requested threshold rather than a particular sun.
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            for (float exposed : new float[] { .031f, .035f }) {
                atmosphere.resetContrast();
                float source = exposed / atmosphere.exposure();
                float[] rgb = { source, source, source };
                contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                Pixmap background = pixels();
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
                Pixmap reference = pixels();
                contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
                Pixmap adaptive = pixels();
                try {
                    double ink = contrastInk(background, reference, adaptive, false);
                    assertTrue(exposed == .031f ? ink > 245 : ink < 10,
                          "The fixed display .2 threshold must lie between the two independent gray controls: " + ink);
                    assertEquals(uploaded, pages(labels), "Threshold checks must not rebuild glyph pages");
                } finally { background.dispose(); reference.dispose(); adaptive.dispose(); }
            }

            // A single hex crossing a dark/bright split must keep one ink everywhere. Two owning hexes
            // wholly on opposite sides must choose independently, using the same retained source in one draw.
            float[] split = { .003f, .003f, .003f, .8f, .8f, .8f };
            for (boolean sameHex : List.of(true, false)) {
                BoardScene splitScene = sameHex ? scene : contrastScene(0xff000000, true);
                labels.update(splitScene, font, outline, c -> null, false, false, false);
                List<Object> splitPages = pages(labels);
                contrastFrame(terrain, atmosphere, camera, splitScene, split, 0);
                Pixmap background = pixels();
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
                Pixmap reference = pixels();
                contrastFrame(terrain, atmosphere, camera, splitScene, split, 0);
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
                Pixmap adaptive = pixels();
                try {
                    int[] count = new int[2];
                    long[] sum = new long[2];
                    for (int y = 0; y < reference.getHeight(); y++) {
                        for (int x = 0; x < reference.getWidth(); x++) {
                            assertEquals(reference.getPixel(x, y) & 255, adaptive.getPixel(x, y) & 255);
                            if ((reference.getPixel(x, y) >>> 8) != 0 || reference.getPixel(x, y) == background.getPixel(x, y)
                                  || Math.abs(x - reference.getWidth() / 2) < 5) { continue; }
                            int side = x < reference.getWidth() / 2 ? 0 : 1;
                            count[side]++;
                            sum[side] += adaptive.getPixel(x, y) >>> 24;
                        }
                    }
                    assertTrue(count[0] > 100 && count[1] > 100, "Both sides must contain actual opaque glyph pixels");
                    if (sameHex) {
                        assertTrue(reference.getPixels().equals(adaptive.getPixels()),
                              "One hex has one black ink, including letters over its dark half; no mottled glyphs");
                    } else {
                        assertTrue(sum[0] / (double) count[0] > 245 && sum[1] / (double) count[1] < 10,
                              "Different owning hexes choose white and black independently in the same draw");
                    }
                    assertEquals(splitPages, pages(labels), "Sampling never rebuilds glyph pages");
                    capture(sameHex ? "contrast-whole-hex" : "contrast-neighbour-hexes");
                } finally { background.dispose(); reference.dispose(); adaptive.dispose(); }
            }

            checkContrastHistory(terrain, atmosphere, batch, labels, camera, scene, font, outline);
            checkContrastDisabled(terrain, batch, camera, scene, font, outline);

            // Colored labels AND their rings must remain pixel-identical with the new scene binding enabled.
            BoardScene colored = contrastScene(0xffff00ff);
            labels.update(colored, font, outline, c -> null, false, false, true);
            for (float value : new float[] { .003f, .214f }) {
                float[] rgb = { value, value, value };
                contrastFrame(terrain, atmosphere, camera, colored, rgb, 0);
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
                Pixmap coloredReference = pixels();
                contrastFrame(terrain, atmosphere, camera, colored, rgb, 0);
                labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
                Pixmap coloredAdaptive = pixels();
                try {
                    assertTrue(pink(coloredReference) > 250);
                    assertTrue(coloredReference.getPixels().equals(coloredAdaptive.getPixels()), "Authored colored glyphs/rings stay exact");
                } finally { coloredReference.dispose(); coloredAdaptive.dispose(); }

                atmosphere.resetContrast(); // Independent uniform-source grid control, including edge slivers.
                contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                Pixmap gridBackground = pixels();
                grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE, false, atmosphere);
                Pixmap adaptiveGrid = pixels();
                try {
                    int lighter = 0, darker = 0, changed = 0;
                    String firstWrong = "none";
                    for (int y = 0; y < gridBackground.getHeight(); y++) {
                        for (int x = 0; x < gridBackground.getWidth(); x++) {
                            int before = gridBackground.getPixel(x, y), after = adaptiveGrid.getPixel(x, y);
                            int difference = (after >>> 24) - (before >>> 24);
                            if (difference > 2) { lighter++; }
                            if (difference < -2) { darker++; }
                            if (before != after) { changed++; }
                            if ((value < .01f ? difference < -2 : difference > 2) && firstWrong.equals("none")) {
                                firstWrong = x + "," + y + " rgba=" + Integer.toHexString(before)
                                      + "->" + Integer.toHexString(after);
                            }
                        }
                    }
                    System.out.printf(java.util.Locale.ROOT,
                          "HEX_GRID source=%.6f lighter=%d darker=%d changed=%d firstWrong=%s%n",
                          value, lighter, darker, changed, firstWrong);
                    capture("contrast-grid-" + value);
                    assertTrue(value < .01f ? lighter > 100 && darker == 0 : darker > 100 && lighter == 0,
                          "Only contrasting grid lines should change the completed background");
                    assertTrue(changed < gridBackground.getWidth() * gridBackground.getHeight() / 5,
                          "Grid-only drawing must not shade hex interiors");
                } finally { gridBackground.dispose(); adaptiveGrid.dispose(); }

                contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                grid.render(camera, scene, false, true, GpuBattleStatus.Arc.NONE);
                Pixmap tactical = pixels();
                contrastFrame(terrain, atmosphere, camera, scene, rgb, 0);
                grid.render(camera, scene, false, true, GpuBattleStatus.Arc.NONE, false, atmosphere);
                Pixmap tacticalBound = pixels();
                try { assertTrue(tactical.getPixels().equals(tacticalBound.getPixels()), "Tactical palette ignores adaptive ink"); }
                finally { tactical.dispose(); tacticalBound.dispose(); }
            }
            labels.update(scene, font, outline, c -> null, true, false, true);
            assertFalse(pages(labels).isEmpty(), "Tactical coordinate control must contain real neutral glyphs");
            contrastFrame(terrain, atmosphere, camera, scene, twilight, -2);
            labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
            Pixmap tacticalText = pixels();
            contrastFrame(terrain, atmosphere, camera, scene, twilight, -2);
            labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
            Pixmap tacticalTextBound = pixels();
            try {
                assertTrue(tacticalText.getPixels().equals(tacticalTextBound.getPixels()),
                      "Tactical neutral glyphs and rings ignore adaptive ink");
            } finally { tacticalText.dispose(); tacticalTextBound.dispose(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { grid.dispose(); labels.dispose(); }
    }

    /** Panning moves the seven taps across source pixels; history must suppress threshold-only toggles. */
    private static void checkContrastHistory(GpuTerrain terrain, GpuAtmosphere atmosphere, SpriteBatch batch,
          GpuHexText labels, BoardCamera camera, BoardScene scene, BitmapFont font, BitmapFont outline) {
        camera.setPerspective(false);
        camera.setIsometric(false);
        camera.camera.zoom = .20f;
        labels.update(scene, font, outline, c -> null, false, false, false);
        List<Object> uploaded = pages(labels);
        atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
        float gain = atmosphere.exposure();
        float[] split = { .030f / gain, .030f / gain, .030f / gain, .036f / gain, .036f / gain, .036f / gain };
        // Three corners remain on either side. Only the center tap moves: means .0325714/.0334286
        // straddle linear(.2)=.0331048, both inside the display .18/.22 hysteresis band.
        for (float scale : new float[] { 1, .5f }) {
            int flips = 0;
            boolean previous = false;
            for (int step = 0; step < 16; step++) {
                camera.center(BoardGeometry.center(LABELED, 0));
                camera.pan(step % 2 == 0 ? -.75f : .75f, 0);
                atmosphere.resetContrast();
                boolean white = inkFrame(terrain, atmosphere, batch, labels, camera, scene, split, scale) > 245;
                if (step > 0 && white != previous) { flips++; }
                previous = white;
            }
            assertEquals(15, flips, "Fresh decisions must reproduce the previous stateless subpixel-pan flicker");
            for (boolean white : List.of(true, false)) {
                atmosphere.resetContrast();
                camera.center(BoardGeometry.center(LABELED, 0));
                float value = (white ? .005f : .1f) / gain;
                double initial = inkFrame(terrain, atmosphere, batch, labels, camera, scene,
                      new float[] { value, value, value }, scale);
                assertTrue(white ? initial > 245 : initial < 10, "The initial strong control selects the expected ink");
                Object points = field(atmosphere, "contrastPoints");
                Object read = field(atmosphere, "contrastRead"), write = field(atmosphere, "contrastWrite");
                Set<Object> targets = Set.of(read, write);
                for (int step = 0; step < 16; step++) {
                    camera.center(BoardGeometry.center(LABELED, 0));
                    camera.pan(step % 2 == 0 ? -.75f : .75f, 0);
                    double ink = inkFrame(terrain, atmosphere, batch, labels, camera, scene, split, scale);
                    assertTrue(white ? ink > 245 : ink < 10, "Subpixel panning retains the entire hex's binary ink");
                    assertSame(points, field(atmosphere, "contrastPoints"), "Panning cannot rebuild owner positions");
                    assertEquals(targets, Set.of(field(atmosphere, "contrastRead"), field(atmosphere, "contrastWrite")),
                          "Panning reuses both small history targets");
                    assertEquals(uploaded, pages(labels), "Panning must not rebuild glyph pages");
                }
                float crossing = (white ? .1f : .005f) / gain;
                double changed = inkFrame(terrain, atmosphere, batch, labels, camera, scene,
                      new float[] { crossing, crossing, crossing }, scale);
                assertTrue(white ? changed < 10 : changed > 245, "A clear lighting change crosses the band immediately");
                System.out.printf(java.util.Locale.ROOT, "HEX_INK_PAN scale=%.2f initialWhite=%b statelessFlips=%d historyFlips=0%n",
                      scale, white, flips);
            }
        }

        // High contrast at one source texel defeats hysteresis with nearest sampling: six .024 taps
        // and one .024/.15 tap average .024/.042, across both .18/.22 display thresholds. At +/- .1
        // display pixels the bilinear means are near .033, inside the band at either scene resolution.
        float low = .024f / gain, high = .15f / gain;
        float[] stripe = { low, low, low, high, high, high };
        for (float scale : new float[] { 1, .5f }) {
            for (boolean white : List.of(true, false)) {
                camera.center(BoardGeometry.center(LABELED, 0));
                atmosphere.resetContrast();
                float seed = (white ? .005f : .1f) / gain;
                double initial = inkFrame(terrain, atmosphere, batch, labels, camera, scene,
                      new float[] { seed, seed, seed }, scale);
                assertTrue(white ? initial > 245 : initial < 10);
                for (int step = 0; step < 16; step++) {
                    camera.center(BoardGeometry.center(LABELED, 0));
                    camera.pan(step % 2 == 0 ? -.1f : .1f, 0);
                    double ink = inkFrame(terrain, atmosphere, batch, labels, camera, scene, stripe, scale, true);
                    assertTrue(white ? ink > 245 : ink < 10,
                          "Bilinear HDR taps suppress a high-contrast texel crossing, beyond hysteresis alone");
                }
            }
        }

        // A partial hex cannot replace a learned full-hex average with its changing visible subset.
        camera.center(BoardGeometry.center(LABELED, 0));
        atmosphere.resetContrast();
        float darkEdge = .005f / gain, brightEdge = .1f / gain;
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, scene,
              new float[] { darkEdge, darkEdge, darkEdge }, 1) > 245);
        camera.pan(-camera.camera.viewportWidth / 2 + 60, 0);
        var edge = camera.camera.project(BoardGeometry.center(LABELED, 0),
              0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
        assertTrue(edge.x > 0 && edge.x < BoardGeometry.width() * .325f / camera.camera.zoom,
              "The center remains visible while at least the left inset tap is outside");
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, scene,
              new float[] { brightEdge, brightEdge, brightEdge }, 1) > 245,
              "A clipped footprint retains the established ink instead of learning a new subset");
        camera.center(BoardGeometry.center(LABELED, 0));
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, scene,
              new float[] { brightEdge, brightEdge, brightEdge }, 1) < 10,
              "The complete footprint immediately responds to a genuine brightness change");

        checkContrastEdges(terrain, atmosphere, camera, scene, gain);

        camera.center(BoardGeometry.center(LABELED, 0));
        float bright = .1f / gain, near = .031f / gain;
        inkFrame(terrain, atmosphere, batch, labels, camera, scene, new float[] { bright, bright, bright }, 1);
        Object read = field(atmosphere, "contrastRead"), write = field(atmosphere, "contrastWrite");
        Object points = field(atmosphere, "contrastPoints");
        Set<Object> targets = Set.of(read, write);
        // Reallocating the full scene for render-resolution changes must not reset small per-board history.
        for (float scale : new float[] { .5f, 1 }) {
            assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, scene,
                  new float[] { near, near, near }, scale) < 10, "Scene resize preserves initialized black ink inside the band");
            assertEquals(targets, Set.of(field(atmosphere, "contrastRead"), field(atmosphere, "contrastWrite")));
            assertSame(points, field(atmosphere, "contrastPoints"));
        }
        BoardScene relabeled = contrastScene(0xff000000);
        labels.update(relabeled, font, outline, c -> null, false, false, false);
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, relabeled,
              new float[] { near, near, near }, 1) < 10, "A new tile snapshot with unchanged owners retains history");
        assertSame(points, field(atmosphere, "contrastPoints"));

        camera.pan(10000, 0);
        contrastFrame(terrain, atmosphere, camera, relabeled, new float[] { .001f, .001f, .001f }, 0);
        camera.center(BoardGeometry.center(LABELED, 0));
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, relabeled,
              new float[] { near, near, near }, 1) < 10, "Offscreen owners must not learn an unrelated viewport-edge value");

        BoardScene replacement = new BoardScene(scene.boardId() + 1, scene.width(), scene.height(), scene.tiles(),
              List.of(), List.of(), -1, "", List.of());
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, replacement,
              new float[] { near, near, near }, 1) > 245, "A different board starts with the .2 first-state threshold");
        inkFrame(terrain, atmosphere, batch, labels, camera, replacement, new float[] { bright, bright, bright }, 1);
        atmosphere.resetContrast();
        assertNull(field(atmosphere, "contrastRead"));
        assertNull(field(atmosphere, "contrastWrite"));
        assertNull(field(atmosphere, "contrastPoints"));
        assertTrue(inkFrame(terrain, atmosphere, batch, labels, camera, replacement,
              new float[] { near, near, near }, 1) > 245, "Explicit same-sized board-generation reset clears prior ink");
    }

    /** Visible slivers and wholly offscreen owners differ even when neither has an inset sample. */
    private static void checkContrastEdges(GpuTerrain terrain, GpuAtmosphere atmosphere,
          BoardCamera camera, BoardScene scene, float gain) {
        float[] dark = { .005f / gain, .005f / gain, .005f / gain };
        float[] bright = { .1f / gain, .1f / gain, .1f / gain };
        float width = BoardGeometry.width() / camera.camera.zoom;
        float height = BoardGeometry.height() / camera.camera.zoom;
        for (float scale : new float[] { 1, .5f }) {
            placeInkOwner(camera, -.6f * width, camera.camera.viewportHeight / 2);
            atmosphere.resetContrast();
            contrastFrame(terrain, atmosphere, camera, scene, dark, 0, scale);
            assertEquals(1, ownerInk(atmosphere, camera), "Wholly offscreen fallback stays unlearned");
            placeInkOwner(camera, -.4f * width, camera.camera.viewportHeight / 2);
            // The right corner is visible, but the furthest inset tap is still left of the viewport.
            for (boolean white : List.of(true, false)) {
                atmosphere.resetContrast();
                contrastFrame(terrain, atmosphere, camera, scene, white ? dark : bright, 0, scale);
                assertEquals(white ? 3 : 2, ownerInk(atmosphere, camera), "A first visible sliver learns its fallback");
                for (int step = 0; step < 16; step++) {
                    placeInkOwner(camera, -.4f * width + (step % 2 == 0 ? -.25f : .25f), camera.camera.viewportHeight / 2);
                    contrastFrame(terrain, atmosphere, camera, scene, white ? bright : dark, 0, scale);
                    assertEquals(white ? 3 : 2, ownerInk(atmosphere, camera), "Subpixel slivers retain their learned ink");
                }
            }
            // Both projected bounds enter the viewport, but the hex misses its bottom-left corner.
            placeInkOwner(camera, -.45f * width, -.45f * height);
            atmosphere.resetContrast();
            contrastFrame(terrain, atmosphere, camera, scene, dark, 0, scale);
            assertEquals(1, ownerInk(atmosphere, camera), "A diagonal offscreen hex must not learn a false AABB overlap");

            placeInkOwner(camera, -.4f * width, camera.camera.viewportHeight / 2);
            contrastFrame(terrain, atmosphere, camera, scene, bright, 0, scale);
            assertEquals(2, ownerInk(atmosphere, camera));
            Set<Object> targets = Set.of(field(atmosphere, "contrastRead"), field(atmosphere, "contrastWrite"));
            Object points = field(atmosphere, "contrastPoints");
            contrastFrame(terrain, atmosphere, camera, scene, bright, -2, scale);
            assertEquals(3, ownerInk(atmosphere, camera), "Exposure changes relearn a clipped owner immediately");
            contrastFrame(terrain, atmosphere, camera, scene, bright, 2, scale);
            assertEquals(2, ownerInk(atmosphere, camera));
            contrastFrame(terrain, atmosphere, camera, scene, dark, 0, scale);
            assertEquals(3, ownerInk(atmosphere, camera));
            atmosphere.setOptions(new GpuAtmosphere.Options(.5f, true));
            contrastFrame(terrain, atmosphere, camera, scene, bright, 0, scale);
            assertEquals(2, ownerInk(atmosphere, camera), "A changed lighting option invalidates clipped ink");
            atmosphere.setOptions(new GpuAtmosphere.Options(.5f, true));
            contrastFrame(terrain, atmosphere, camera, scene, dark, 0, scale);
            assertEquals(2, ownerInk(atmosphere, camera), "Equal-valued per-frame options preserve the learned choice");
            assertEquals(targets, Set.of(field(atmosphere, "contrastRead"), field(atmosphere, "contrastWrite")),
                  "Lighting changes clear history without reallocating either target");
            assertSame(points, field(atmosphere, "contrastPoints"), "Lighting changes do not reupload owner positions");
            atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        }
    }

    private static void placeInkOwner(BoardCamera camera, float x, float y) {
        camera.center(BoardGeometry.center(LABELED, 0));
        camera.pan(x - camera.camera.viewportWidth / 2, camera.camera.viewportHeight / 2 - y);
        var projected = camera.camera.project(BoardGeometry.center(LABELED, 0),
              0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
        assertEquals(x, projected.x, .02f, "The fixture owner reaches its intended horizontal clipping position");
        assertEquals(y, projected.y, .02f, "The fixture owner reaches its intended vertical clipping position");
    }

    /** R and G are checked separately: bit 0 is white ink, bit 1 says the visible owner learned it. */
    private static int ownerInk(GpuAtmosphere atmosphere, BoardCamera camera) {
        FrameBuffer target = (FrameBuffer) field(atmosphere, "contrastRead");
        target.begin();
        try {
            byte[] pixel = ScreenUtils.getFrameBufferPixels(LABELED.getX(), LABELED.getY(), 1, 1, false);
            int ink = pixel[0] & 255, learned = pixel[1] & 255;
            assertTrue((ink == 0 || ink == 255) && (learned == 0 || learned == 255));
            return (ink == 255 ? 1 : 0) | (learned == 255 ? 2 : 0);
        } finally {
            target.end();
            HdpiUtils.glViewport(0, BOTTOM, (int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight);
        }
    }

    /** Uses actual opaque glyph pixels as the oracle, rather than merely reading the private history texel. */
    private static double inkFrame(GpuTerrain terrain, GpuAtmosphere atmosphere, SpriteBatch batch, GpuHexText labels,
          BoardCamera camera, BoardScene scene, float[] rgb, float scale) {
        return inkFrame(terrain, atmosphere, batch, labels, camera, scene, rgb, scale, false);
    }

    private static double inkFrame(GpuTerrain terrain, GpuAtmosphere atmosphere, SpriteBatch batch, GpuHexText labels,
          BoardCamera camera, BoardScene scene, float[] rgb, float scale, boolean stripe) {
        contrastFrame(terrain, atmosphere, camera, scene, rgb, 0, scale, stripe);
        Pixmap background = pixels();
        labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM);
        Pixmap reference = pixels();
        contrastFrame(terrain, atmosphere, camera, scene, rgb, 0, scale, stripe);
        labels.render(batch, camera, atmosphere.depthTexture(), null, BOTTOM, atmosphere);
        Pixmap adaptive = pixels();
        try { return contrastInk(background, reference, adaptive, false); }
        finally { background.dispose(); reference.dispose(); adaptive.dispose(); }
    }

    private static void checkContrastDisabled(GpuTerrain terrain, SpriteBatch batch, BoardCamera camera,
          BoardScene scene, BitmapFont font, BitmapFont outline) {
        GpuAtmosphere disabled = new GpuAtmosphere(0);
        GpuHexText labels = new GpuHexText();
        GpuHexGrid grid = new GpuHexGrid();
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        try {
            labels.update(scene, font, outline, c -> null, false, false, false);
            float[] dark = { .001f, .001f, .001f };
            contrastFrame(terrain, disabled, camera, scene, dark, 0);
            profiler.enable();
            disabled.prepareContrast(camera.camera, scene, BOTTOM);
            assertEquals(0, profiler.getDrawCalls(), "Zero threshold skips the history update pass");
            assertEquals(0, profiler.getShaderSwitches(), "Zero threshold does not bind an adaptive shader");
            assertEquals(0, profiler.getTextureBindings(), "Zero threshold does not bind adaptive source/history textures");
            profiler.disable();
            for (String resource : List.of("contrastRead", "contrastWrite", "contrastPoints", "contrastShader")) {
                assertNull(field(disabled, resource), "Zero threshold allocates no adaptive resource: " + resource);
            }
            labels.render(batch, camera, disabled.depthTexture(), null, BOTTOM);
            grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE);
            Pixmap authored = pixels();
            contrastFrame(terrain, disabled, camera, scene, dark, 0);
            labels.render(batch, camera, disabled.depthTexture(), null, BOTTOM, disabled);
            grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE, false, disabled);
            Pixmap bypassed = pixels();
            try { assertTrue(authored.getPixels().equals(bypassed.getPixels()), "Zero keeps authored grid/text pixels exact"); }
            finally { authored.dispose(); bypassed.dispose(); }
        } finally { profiler.disable(); labels.dispose(); grid.dispose(); disabled.dispose(); }
    }

    private static BoardScene contrastScene(int argb) {
        return contrastScene(argb, false);
    }

    private static BoardScene contrastScene(int argb, boolean neighbours) {
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        List<BoardHexText> text = List.of(new BoardHexText("0404 LEVEL", 36, font, argb, true, 0));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS, null, List.of(),
                      (neighbours
                            ? y == LABELED.getY() && Math.abs(x - LABELED.getX()) == 1
                            : coords.equals(LABELED)) ? text : List.of()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void contrastFrame(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera,
          BoardScene scene, float[] rgb, int ev) {
        contrastFrame(terrain, atmosphere, camera, scene, rgb, ev, 1);
    }

    private static void contrastFrame(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera,
          BoardScene scene, float[] rgb, int ev, float scale) {
        contrastFrame(terrain, atmosphere, camera, scene, rgb, ev, scale, false);
    }

    private static void contrastFrame(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera,
          BoardScene scene, float[] rgb, int ev, float scale, boolean stripe) {
        // A diagnostic constant scene is cleared directly into the existing RGBA16F target. No second color path.
        atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, ev));
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(.4f, .2f, .6f, 1, true); // Distinct dock background, never an opaque black glyph.
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, scale, 0);
        ScreenUtils.clear(rgb[0] * GpuSceneRadiance.SCALE, rgb[1] * GpuSceneRadiance.SCALE,
              rgb[2] * GpuSceneRadiance.SCALE, 1, true);
        if (rgb.length == 6) {
            // The second RGB triplet fills the right half, or one HDR texel column for the filter probe.
            int width = atmosphere.depthTexture().getWidth(), height = atmosphere.depthTexture().getHeight();
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(width / 2, 0, stripe ? 1 : width - width / 2, height);
            try {
                ScreenUtils.clear(rgb[3] * GpuSceneRadiance.SCALE, rgb[4] * GpuSceneRadiance.SCALE,
                      rgb[5] * GpuSceneRadiance.SCALE, 1, true);
            } finally { Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST); }
        }
        atmosphere.end(camera.camera, terrain, scene, BOTTOM, null, false);
        atmosphere.prepareContrast(camera.camera, scene, BOTTOM);
    }

    private static float[] contrastDepth() {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var buffer = com.badlogic.gdx.utils.BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, buffer);
        float[] values = new float[buffer.capacity()];
        buffer.get(values);
        return values;
    }

    /** Quantized old black/white glyph pixels define the independent mask; their mean classifies ink polarity. */
    private static double contrastInk(Pixmap background, Pixmap reference, Pixmap actual, boolean ring) {
        int target = ring ? 0xffffff : 0;
        int count = 0;
        long sum = 0;
        for (int y = 0; y < reference.getHeight(); y++) {
            for (int x = 0; x < reference.getWidth(); x++) {
                int expected = reference.getPixel(x, y);
                assertEquals(expected & 255, actual.getPixel(x, y) & 255, "Adaptive ink preserves every alpha sample");
                if ((expected >>> 8) != target || expected == background.getPixel(x, y)) { continue; }
                int observed = actual.getPixel(x, y);
                assertTrue(Math.abs((observed >>> 24) - (observed >>> 16 & 255)) <= 1,
                      "Neutral ink has no color cast beyond display quantization");
                assertTrue(Math.abs((observed >>> 24) - (observed >>> 8 & 255)) <= 1,
                      "Neutral ink has no color cast beyond display quantization");
                count++;
                sum += observed >>> 24;
            }
        }
        assertTrue(count > 100, "The independent opaque glyph/ring mask must be substantial: " + count);
        return sum / (double) count;
    }

    private record LegacyChunk(BitmapFontCache glyphs, BoundingBox bounds) { }

    /** Compares the same font-cache draws before and after static upload, including alpha and atlas-page ordering. */
    private static void checkStaticMeshes(SpriteBatch batch) {
        GpuHexText labels = new GpuHexText();
        Texture first = texture(0xffffffff), second = texture(0x5fff8fc0), depth = texture(0xffffffff);
        BitmapFont font = font(first, second);
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        String meshesBefore = Mesh.getManagedStatus();
        try {
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(false);
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            BoardScene scene = textScene(24, "ABAB");
            assertEquals(1, font.getRegions().size);
            labels.update(scene, font, font, coords -> null);
            assertEquals(2, font.getRegions().size, "Later chunks introduce another incremental font atlas page");
            assertEquals(1, pages(labels).size(), "Alternating atlas textures must share the same ordered batch");
            List<LegacyChunk> legacy = legacy(scene, font, true);
            assertVertices(labels, legacy, font);
            // Client Settings > Hex label outline off: the text alone, one run of glyphs instead of outline then text.
            labels.update(scene, font, font, coords -> null, false, false, false);
            assertVertices(labels, legacy(scene, font, false), font);
            labels.update(scene, font, font, coords -> null);
            assertVertices(labels, legacy, font);
            ShaderProgram previous = batch.getShader();
            camera.fit(scene);
            parity(labels, legacy, batch, camera, depth);
            assertSame(previous, batch.getShader(), "The annotation batch keeps its shader after direct mesh rendering");
            assertArrayEquals(new Matrix4().val, batch.getTransformMatrix().val,
                  "Later annotations retain their identity transform");
            ScreenUtils.clear(.1f, .2f, .3f, 1, true);
            batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight()));
            batch.begin();
            batch.draw(first, 12, 12, 32, 32);
            batch.end();
            Pixmap annotation = pixels();
            try { assertEquals(0xffffffff, annotation.getPixel(28, 28), "The restored SpriteBatch still draws annotations"); }
            finally { annotation.dispose(); }
            camera.camera.zoom = .2f;
            camera.center(BoardGeometry.center(new Coords(15, 15), 0));
            long visible = legacy.stream().filter(chunk -> camera.camera.frustum.boundsInFrustum(chunk.bounds())).count();
            assertTrue(visible > 0 && visible < legacy.size(), "The close view must contain culled chunk ranges");
            parity(labels, legacy, batch, camera, depth);

            // More than 65,535 glyph instances still use only the shared quad's six unsigned-short indices.
            BoardScene large = textScene(72, "AAAAAAAA");
            labels.update(large, font, font, coords -> null);
            List<Object> pages = pages(labels);
            assertEquals(1, pages.size(), "Glyph count no longer fragments draws at the 16-bit vertex limit");
            assertVertices(labels, legacy(large, font, true), font);
            labels.update(large, font, font, coords -> { throw new AssertionError("Unchanged labels must keep their meshes"); });
            assertSame(pages.getFirst(), pages(labels).getFirst());
            camera.fit(large);
            profiler.enable();
            try {
                ScreenUtils.clear(.1f, .2f, .3f, 1, true);
                profiler.reset();
                labels.render(batch, camera, depth, 0);
                assertEquals(1, profiler.getDrawCalls(), "All visible glyphs share one instanced draw");
                camera.center(new com.badlogic.gdx.math.Vector3(-10000, 10000, 0));
                profiler.reset();
                labels.render(batch, camera, depth, 0);
                assertEquals(0, profiler.getDrawCalls(), "An off-board view must submit no glyph meshes");
            } finally { profiler.disable(); }
            // Individual scatter heights change glyph occlusion, but must not fragment the same elevation's font draws.
            BoardScene varied = textScene(24, "AAAA", true);
            labels.update(varied, font, font, coords -> null);
            camera.fit(varied);
            assertVertices(labels, legacy(varied, font, true), font);
            parity(labels, legacy(varied, font, true), batch, camera, depth);
            profiler.enable();
            try {
                profiler.reset();
                labels.render(batch, camera, depth, 0);
                assertEquals(1, profiler.getDrawCalls(), "Different scatter heights share one ordered outline/ink batch");
            } finally { profiler.disable(); }
            labels.update(textScene(1, ""), font, font, coords -> null);
            assertEquals(meshesBefore, Mesh.getManagedStatus(), "Replacing text releases all old static meshes");
            assertTrue(Gdx.gl.glIsTexture(first.getTextureObjectHandle()));
            assertTrue(Gdx.gl.glIsTexture(second.getTextureObjectHandle()), "Font atlas pages are borrowed, not disposed");
        } finally {
            if (profiler.isEnabled()) { profiler.disable(); }
            labels.dispose();
            font.dispose();
            first.dispose(); second.dispose(); depth.dispose();
        }
        assertEquals(meshesBefore, Mesh.getManagedStatus());
    }

    /** More than the bound sampler count must split safely without reordering overlapping transparent glyphs. */
    private static void checkManyAtlases(SpriteBatch batch) {
        var textures = new ArrayList<Texture>();
        var regions = new Array<TextureRegion>();
        var data = new BitmapFont.BitmapFontData();
        data.lineHeight = 16; data.capHeight = 14; data.down = -16; data.spaceXadvance = 7;
        for (int index = 0; index < 10; index++) {
            Texture texture = texture((20 + index * 21) << 24 | (230 - index * 20) << 16 | 0x8050);
            textures.add(texture);
            regions.add(new TextureRegion(texture));
            data.setGlyph('A' + index, glyph((char) ('A' + index), index));
        }
        BitmapFont font = new BitmapFont(data, regions, false);
        Texture depth = texture(0xffffffff);
        GpuHexText labels = new GpuHexText();
        try {
            BoardScene scene = textScene(16, "ABCDEFGHIJ");
            labels.update(scene, font, font, coords -> null);
            assertTrue(pages(labels).size() > 1, "This fixture must cross the sampler budget");
            assertVertices(labels, legacy(scene, font, true), font);
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(false);
            camera.fit(scene);
            parity(labels, legacy(scene, font, true), batch, camera, depth);
        } finally {
            labels.dispose(); font.dispose(); depth.dispose();
            textures.forEach(Texture::dispose);
        }
    }

    private static void parity(GpuHexText labels, List<LegacyChunk> legacy, SpriteBatch batch, BoardCamera camera,
          Texture depth) {
        ScreenUtils.clear(.1f, .2f, .3f, 1, true);
        labels.render(batch, camera, depth, 0);
        Pixmap actual = pixels();
        // Independent SpriteBatch reference: these authored glyphs have no occluder or adaptive contrast.
        ScreenUtils.clear(.1f, .2f, .3f, 1, true);
        ShaderProgram previous = batch.getShader();
        batch.setShader(null);
        batch.setProjectionMatrix(camera.camera.combined);
        batch.setTransformMatrix(new Matrix4().setToTranslation(0, 0, .6f * BoardGeometry.HEX_SCALE));
        batch.begin();
        try {
            for (LegacyChunk chunk : legacy) {
                if (camera.camera.frustum.boundsInFrustum(chunk.bounds())) { chunk.glyphs().draw(batch); }
            }
        } finally {
            batch.end();
            batch.setTransformMatrix(new Matrix4());
            batch.setShader(previous);
        }
        Pixmap expected = pixels();
        try {
            int background = actual.getPixel(0, 0);
            boolean ink = false;
            for (int y = 0; y < actual.getHeight() && !ink; y++) {
                for (int x = 0; x < actual.getWidth() && !ink; x++) { ink = actual.getPixel(x, y) != background; }
            }
            assertTrue(ink, "Pixel parity must include rendered glyphs");
            assertTrue(actual.getPixels().equals(expected.getPixels()),
                  "Static glyph meshes must reproduce every pixel of the existing BitmapFontCache draw path");
        } finally { actual.dispose(); expected.dispose(); }
    }

    private static void assertVertices(GpuHexText labels, List<LegacyChunk> chunks, BitmapFont font) {
        FloatArray expected = new FloatArray(), actual = new FloatArray();
        List<Texture> textures = new ArrayList<>();
        for (LegacyChunk chunk : chunks) {
            for (int region = 0; region < chunk.glyphs().getPageCount(); region++) {
                int count = chunk.glyphs().getVertexCount(region);
                if (count == 0) { continue; }
                expected.addAll(chunk.glyphs().getVertices(region), 0, count);
                for (int glyph = 0; glyph < count / 20; glyph++) { textures.add(font.getRegion(region).getTexture()); }
            }
        }
        int glyph = 0;
        for (Object page : pages(labels)) {
            Mesh mesh = field(page, "mesh");
            assertEquals(4, mesh.getNumVertices());
            assertEquals(6, mesh.getNumIndices());
            assertTrue(mesh.isInstanced());
            assertEquals(64, mesh.getInstancedAttributes().vertexSize, "One compact record replaces four glyph vertices");
            float[] instances = field(page, "instances");
            List<Texture> bound = field(page, "textures");
            for (int at = 0; at < instances.length; at += 16) {
                // Reconstruct the independent font-cache quad to check exact positions, UVs, colors and drawing order.
                for (int corner = 0; corner < 4; corner++) {
                    boolean right = corner >= 2, top = corner == 1 || corner == 2;
                    actual.add(instances[at + (right ? 2 : 0)], instances[at + (top ? 3 : 1)], instances[at + 4]);
                    actual.add(instances[at + (right ? 7 : 5)], instances[at + (top ? 8 : 6)]);
                }
                assertEquals(0, instances[at + 9], 0, "These authored glyphs and rings must retain their color");
                assertSame(textures.get(glyph++), bound.get((int) instances[at + 15]));
            }
            short[] indices = new short[mesh.getNumIndices()];
            mesh.getIndices(indices);
            assertArrayEquals(new short[] { 0, 1, 2, 2, 3, 0 }, indices, "SpriteBatch quad winding");
        }
        assertEquals(expected.size, actual.size);
        for (int i = 0; i < expected.size; i++) {
            assertEquals(Float.floatToRawIntBits(expected.items[i]), Float.floatToRawIntBits(actual.items[i]),
                  "Glyph position, packed alpha/color and UV bits must survive upload at float " + i);
        }
    }

    /**
     * Independent copy of the previous single-plane layout/draw input, without static mesh construction: every chunk's
     * outlines (drawn with {@code font} as the outline font too) when {@code outlined}, then every chunk's text.
     */
    private static List<LegacyChunk> legacy(BoardScene scene, BitmapFont font, boolean outlined) {
        Map<Coords, LegacyChunk> chunks = new HashMap<>(), outlines = new HashMap<>();
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        var color = new com.badlogic.gdx.graphics.Color(font.getColor());
        try {
            for (BoardScene.Tile tile : scene.tiles()) {
                for (BoardHexText text : tile.text()) {
                    Coords cell = new Coords(tile.coords().getX() / GpuTerrain.CHUNK_SIZE,
                          tile.coords().getY() / GpuTerrain.CHUNK_SIZE);
                    LegacyChunk chunk = chunks.computeIfAbsent(cell,
                          key -> new LegacyChunk(new BitmapFontCache(font, false), new BoundingBox().inf()));
                    LegacyChunk outline = outlines.computeIfAbsent(cell,
                          key -> new LegacyChunk(new BitmapFontCache(font, false), chunk.bounds()));
                    float x = BoardGeometry.centerX(tile.coords()), y = BoardGeometry.centerY(tile.coords());
                    chunk.bounds().ext(x - BoardGeometry.WIDTH, y - BoardGeometry.WIDTH, -1)
                          .ext(x + BoardGeometry.WIDTH, y + BoardGeometry.WIDTH, 1);
                    font.getData().setScale(text.font().getSize2D() / GpuBoardSkin.FONT_RESOLUTION);
                    int argb = text.argb();
                    font.setColor((argb >>> 16 & 255) / 255f, (argb >>> 8 & 255) / 255f,
                          (argb & 255) / 255f, (argb >>> 24 & 255) / 255f);
                    GlyphLayout layout = new GlyphLayout(font, text.text());
                    float baseline = y + BoardGeometry.HEIGHT / 2 - text.baseline() * BoardGeometry.HEX_SCALE;
                    float left = x - layout.width / 2, top = text.fromTop() ? baseline : baseline + layout.height;
                    // Every label's outline: the bordered text in the outline's colour, from its first glyph's border.
                    var ring = new com.badlogic.gdx.graphics.Color();
                    com.badlogic.gdx.graphics.Color.argb8888ToColor(ring, BoardHexText.outline(argb));
                    outline.glyphs().addText(new GlyphLayout(font, text.text(), ring, 0, Align.left, false),
                          left - GpuBoardSkin.OUTLINE * font.getData().scaleX, top);
                    chunk.glyphs().addText(layout, left, top);
                }
            }
        } finally { font.getData().setScale(scaleX, scaleY); font.setColor(color); }
        List<LegacyChunk> drawn = new ArrayList<>(outlined ? outlines.values() : List.of());
        drawn.addAll(chunks.values());
        return drawn;
    }

    private static BoardScene textScene(int size, String text) {
        return textScene(size, text, false);
    }

    private static BoardScene textScene(int size, String text, boolean variedHeadroom) {
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, GpuBoardSkin.FONT_RESOLUTION);
        List<BoardHexText> labels = text.isEmpty() ? List.of()
              : List.of(new BoardHexText(text, 22, font, 0xafff70ff, true, 0));
        List<BoardHexText> firstPage = text.isEmpty() ? List.of()
              : List.of(new BoardHexText(text.replace('B', 'A'), 22, font, 0xafff70ff, true, 0));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                List<BoardScene.Feature> features = variedHeadroom
                      ? List.of(new BoardScene.Feature("scatter-rock", 0, 0, 0, 1, (x * size + y) * .01f, 0,
                            BoardScene.FeatureKind.SCATTER)) : List.of();
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS, null,
                      features, x < size / 2 ? firstPage : labels));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BitmapFont font(Texture first, Texture second) {
        Array<TextureRegion> regions = new Array<>();
        regions.add(new TextureRegion(first));
        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData() {
            @Override
            public BitmapFont.Glyph getGlyph(char character) {
                if (character == 'B' && super.getGlyph(character) == null) {
                    regions.add(new TextureRegion(second));
                    BitmapFont.Glyph glyph = glyph('B', 1);
                    setGlyph(character, glyph);
                    setGlyphRegion(glyph, regions.peek());
                }
                return super.getGlyph(character);
            }
        };
        data.lineHeight = 16; data.capHeight = 14; data.down = -16; data.spaceXadvance = 7;
        data.setGlyph('A', glyph('A', 0));
        BitmapFont result = new BitmapFont(data, regions, false);
        assertFalse(result.ownsTexture());
        return result;
    }

    private static BitmapFont.Glyph glyph(char character, int page) {
        BitmapFont.Glyph glyph = new BitmapFont.Glyph();
        glyph.id = character; glyph.page = page; glyph.width = 11; glyph.height = 14;
        glyph.xadvance = 7; glyph.yoffset = -14;
        return glyph;
    }

    private static Texture texture(int rgba) {
        Pixmap pixels = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        try { pixels.setColor(rgba); pixels.fill(); return new Texture(pixels); }
        finally { pixels.dispose(); }
    }

    private static List<Object> pages(GpuHexText labels) {
        return List.copyOf(field(labels, "pages"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void checkUnitOcclusion(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera,
          BoardScene scene, GpuHexText labels, SpriteBatch textBatch) {
        Model model = new ModelBuilder().createBox(BoardGeometry.WIDTH, BoardGeometry.HEIGHT, 2 * BoardGeometry.HEX_SCALE,
              new Material(ColorAttribute.createDiffuse(.1f, .2f, .1f, 1)),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        ModelBatch units = new ModelBatch();
        GpuUnitVisibility visibility = new GpuUnitVisibility();
        try {
            ModelInstance unit = new ModelInstance(model);
            // Above the rocks but below the label's conservative relief-plus-scatter envelope: depth alone is ambiguous.
            unit.transform.setToTranslation(BoardGeometry.centerX(LABELED), BoardGeometry.centerY(LABELED),
                  2 * BoardGeometry.LEVEL + BoardRelief.headroom(scene.tile(LABELED)) + 2 * BoardGeometry.HEX_SCALE);
            atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
            terrain.render(camera.camera);
            units.begin(camera.camera);
            units.render(unit);
            units.end();
            atmosphere.end(camera.camera, terrain, scene, BOTTOM);
            visibility.render(camera.camera, List.of(unit), atmosphere.depthTexture(), BOTTOM, .5f, 1);
            assertNotNull(visibility.depthTexture(), "Labels borrow the existing unit capture without a second render pass");
            labels.render(textBatch, camera, atmosphere.depthTexture(), visibility.depthTexture(), BOTTOM);
            Pixmap protectedUnit = pixels();
            try { assertTrue(pink(protectedUnit) > 250, "Top-view labels must also ignore captured unit depth"); }
            finally { protectedUnit.dispose(); }
            labels.render(textBatch, camera, atmosphere.depthTexture(), BOTTOM);
            Pixmap unprotectedUnit = pixels();
            try { assertTrue(pink(unprotectedUnit) > 250, "Top-view labels also remain visible without a unit capture"); }
            finally { unprotectedUnit.dispose(); }
            visibility.render(camera.camera, List.of(), atmosphere.depthTexture(), BOTTOM, .5f, 1);
            assertNull(visibility.depthTexture(), "A previous unit frame must not hide labels after all units leave");
            visibility.render(camera.camera, List.of(unit), atmosphere.depthTexture(), BOTTOM, 0, 1);
            assertNull(visibility.depthTexture(), "Disabling outlines must not reuse an earlier camera's unit depth");
        } finally { visibility.dispose(); units.dispose(); model.dispose(); }
    }

    private static void frame(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera, BoardScene scene,
          ShapeRenderer object) {
        ScreenUtils.clear(0, 0, 0, 1, true);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
        terrain.render(camera.camera);
        if (object != null) {
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthMask(true);
            object.setProjectionMatrix(camera.camera.combined);
            object.setTransformMatrix(new Matrix4().setToTranslation(0, 0, 8 * BoardGeometry.LEVEL));
            object.begin(ShapeRenderer.ShapeType.Filled);
            object.setColor(.1f, .2f, .1f, 1);
            object.rect(BoardGeometry.centerX(LABELED) - BoardGeometry.WIDTH / 2,
                  BoardGeometry.centerY(LABELED) - BoardGeometry.HEIGHT / 2, BoardGeometry.WIDTH, BoardGeometry.HEIGHT);
            object.end();
        }
        atmosphere.end(camera.camera, terrain, scene, BOTTOM);
    }

    private static void checkHeightOcclusion(GpuTerrain terrain, GpuAtmosphere atmosphere, GpuHexText labels,
          BitmapFont font, BitmapFont outline, SpriteBatch batch, BoardCamera camera) {
        BoardScene scene = scene(false, true, 2);
        terrain.update(scene);
        labels.update(scene, font, outline, terrain::roofBounds);
        // Keep the rectangular object entirely inside the hex, including its tapered corners.
        Model model = new ModelBuilder().createBox(.75f * BoardGeometry.WIDTH, .5f * BoardGeometry.HEIGHT,
              8 * BoardGeometry.LEVEL, new Material(ColorAttribute.createDiffuse(.1f, .2f, .1f, 1)),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        ModelBatch objects = new ModelBatch();
        GpuUnitVisibility visibility = new GpuUnitVisibility();
        try {
            ModelInstance object = new ModelInstance(model);
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                for (boolean isometric : List.of(false, true)) {
                    camera.setIsometric(isometric);
                    camera.center(BoardGeometry.center(LABELED, 4));
                    frame(terrain, atmosphere, camera, scene, null);
                    // Black makes the reference mask depend on glyph coverage, not the terrain's blended color.
                    ScreenUtils.clear(0, 0, 0, 1, true);
                    labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
                    Pixmap expected = pixels();
                    try {
                        assertTrue(pink(expected) > 250, "The fixture must contain visible HEIGHT glyphs");
                        for (Coords coords : List.of(LABELED, new Coords(4, 4))) {
                            object.transform.setToTranslation(BoardGeometry.center(coords, 4));
                            ScreenUtils.clear(0, 0, 0, 1, true);
                            atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
                            terrain.render(camera.camera);
                            objects.begin(camera.camera);
                            objects.render(object);
                            objects.end();
                            atmosphere.end(camera.camera, terrain, scene, BOTTOM);
                            visibility.render(camera.camera, List.of(object), atmosphere.depthTexture(), BOTTOM, .5f, 1);
                            labels.render(batch, camera, atmosphere.depthTexture(), visibility.depthTexture(), BOTTOM);
                            Pixmap covered = pixels();
                            try {
                                if (coords.equals(LABELED)) {
                                    capture("height-own-" + perspective + "-" + isometric);
                                    assertEquals(0, missing(expected, covered),
                                          "HEIGHT must show through its own hex's object: perspective=" + perspective
                                                + ", isometric=" + isometric);
                                } else if (isometric) {
                                    assertTrue(missing(expected, covered) > pink(expected) * .25,
                                          "A foreground object in another hex must occlude the HEIGHT glyphs it overlaps");
                                } else {
                                    assertEquals(0, missing(expected, covered), "An adjacent object must not hide clear glyphs");
                                }
                            } finally { covered.dispose(); }
                        }
                        if (isometric) {
                            BoardScene cliff = scene(true, true, 2);
                            terrain.update(cliff);
                            frame(terrain, atmosphere, camera, cliff, null);
                            labels.render(batch, camera, atmosphere.depthTexture(), BOTTOM);
                            Pixmap covered = pixels();
                            try { assertEquals(0, pink(covered), "A foreground cliff must hide HEIGHT at its shared hex edge"); }
                            finally { covered.dispose(); terrain.update(scene); }
                        }
                    } finally { expected.dispose(); }
                }
            }
        } finally { camera.setPerspective(false); visibility.dispose(); objects.dispose(); model.dispose(); }

        Texture depth = texture(0x000000ff);
        try {
            for (boolean isometric : List.of(false, true)) {
                camera.setIsometric(isometric);
                camera.center(BoardGeometry.center(LABELED, 4));
                for (int height : List.of(0, 2)) {
                    labels.update(scene(false, true, height), font, outline, c -> null);
                    ScreenUtils.clear(0, 0, 0, 1, true);
                    labels.render(batch, camera, depth, depth, BOTTOM);
                    Pixmap expected = pixels();
                    // Cover every pixel with nearer opaque depth, independently of any particular model shape.
                    Gdx.gl.glClearDepthf(0);
                    ScreenUtils.clear(0, 0, 0, 1, true);
                    Gdx.gl.glClearDepthf(1);
                    labels.render(batch, camera, depth, depth, BOTTOM);
                    Pixmap covered = pixels();
                    try {
                        assertTrue(pink(expected) > 250);
                        // The depth texture maps onto the window (its size can differ: the render resolution). Straight
                        // down, the covering depth lies over each glyph's own hex, which HEIGHT pierces as its own
                        // content; angled rays reach it over other hexes.
                        if (!isometric) {
                            assertEquals(0, missing(expected, covered), "Every enabled overlay glyph must survive full occlusion");
                        } else {
                            assertEquals(0, pink(covered), "HEIGHT and angled ground labels must respect foreign scene depth");
                        }
                    } finally { expected.dispose(); covered.dispose(); }
                }
            }
            labels.update(textScene(1, ""), font, outline, c -> null);
            ScreenUtils.clear(0, 0, 0, 1, true);
            labels.render(batch, camera, depth, BOTTOM);
            Pixmap empty = pixels();
            try { assertEquals(0, pink(empty), "An empty captured label list must remain empty"); }
            finally { empty.dispose(); }
        } finally { depth.dispose(); }
    }

    private static void checkTopGrid(BoardCamera camera, BoardScene scene) {
        GpuHexGrid grid = new GpuHexGrid();
        var settings = BoardGeometry.tuning();
        try {
            camera.setIsometric(false);
            int level = scene.tile(LABELED).elevation();
            camera.center(BoardGeometry.center(LABELED, level));
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                ScreenUtils.clear(1, 1, 1, 1, true);
                grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE);
                Pixmap expected = pixels();
                Gdx.gl.glClearDepthf(0);
                ScreenUtils.clear(1, 1, 1, 1, true);
                Gdx.gl.glClearDepthf(1);
                grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE);
                Pixmap covered = pixels();
                try {
                    assertTrue(expected.getPixels().equals(covered.getPixels()), "The entire grid must ignore scene depth");
                    var edge = camera.camera.project(BoardGeometry.corner(LABELED, level, 0)
                          .lerp(BoardGeometry.corner(LABELED, level, 1), .5f), 0, BOTTOM,
                          camera.camera.viewportWidth, camera.camera.viewportHeight);
                    assertTrue((covered.getPixel((int) edge.x, (int) edge.y) >>> 24) < 240,
                          "The pixel comparison must contain a border at the actual hex elevation");
                } finally { expected.dispose(); covered.dispose(); }
            }
            for (boolean disabled : List.of(false, true)) {
                if (disabled) {
                    BoardGeometry.tune(new BoardGeometry.Tuning(settings.hexScale(), settings.unitScale(),
                          settings.unitHeightScale(), settings.levelHeight(), 1, settings.multiHexUnitScale(),
                          settings.transitions(), settings.padding()));
                    camera.setIsometric(false);
                } else { camera.setIsometric(true); }
                ScreenUtils.clear(1, 1, 1, 1, true);
                Pixmap before = pixels();
                grid.render(camera, scene, false, false, GpuBattleStatus.Arc.NONE);
                Pixmap after = pixels();
                try { assertTrue(before.getPixels().equals(after.getPixels()), "No overlay grid in angled view or when disabled"); }
                finally { before.dispose(); after.dispose(); }
            }
        } finally { camera.setPerspective(false); BoardGeometry.tune(settings); grid.dispose(); }
    }

    private static BoardScene scene(boolean cliff, boolean detailedLabels) {
        return scene(cliff, detailedLabels, 0);
    }

    private static BoardScene scene(boolean cliff, boolean detailedLabels, int height) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(86, 119, 54));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        BoardScene.Pixels ground = new BoardScene.Pixels(image);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        List<BoardHexText> text = height > 0
              ? List.of(new BoardHexText("HEIGHT " + height, 40, font, 0xffff00ff, false, height))
              : List.of(new BoardHexText("0404", 10, font, 0xffff00ff, true, 0),
                    new BoardHexText("LEVEL2", 60, font, 0xffff00ff, false, 0));
        List<BoardScene.Feature> rocks = List.of(
              new BoardScene.Feature("scatter-rock", 0, 21, 0, 6, .6f, 0, BoardScene.FeatureKind.SCATTER),
              new BoardScene.Feature("scatter-rock", 0, -18, 0, 6, .6f, 0, BoardScene.FeatureKind.SCATTER));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean labeled = coords.equals(LABELED);
                tiles.add(new BoardScene.Tile(coords, cliff && y >= 4 ? 8 : 2, -1, false, 0,
                      BoardScene.Surface.GRASS, null, null,
                      labeled && detailedLabels ? rocks : List.of(), labeled ? text : List.of(),
                      BoardLiquid.NONE, null, detailedLabels));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static Pixmap pixels() {
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int pink(Pixmap pixels) {
        int result = 0;
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) { if (pink(pixels.getPixel(x, y))) { result++; } }
        }
        return result;
    }

    private static boolean pink(int rgba) { return (rgba >>> 24) >= 249 && (rgba >>> 8 & 255) >= 249 && (rgba >>> 16 & 255) <= 6; }

    private static int missing(Pixmap expected, Pixmap actual) {
        int result = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (pink(expected.getPixel(x, y)) && !pink(actual.getPixel(x, y))) { result++; }
            }
        }
        return result;
    }

    private static void capture(String name) {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        GpuBoardTestUi.capture(new File(output, "terrain-labels-" + name + ".png"));
    }
}
