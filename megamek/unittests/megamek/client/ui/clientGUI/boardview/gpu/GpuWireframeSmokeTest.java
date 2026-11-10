/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.renderReady;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The HUD's wireframe utility on the real battle view, and its return to the shaded board. */
@Tag("on-demand")
class GpuWireframeSmokeTest {
    @Test
    void cameraToggleDrawsThermalUnitsAndRestoresShadedUnits() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            var infantry = new ConvInfantry();
            infantry.setId(2);
            infantry.setChassis("Thermal infantry");
            infantry.setMovementMode(EntityMovementMode.INF_LEG);
            infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
            Entity armor = new MekFileParser(new File("testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk")).getEntity();
            armor.setId(3);
            SwingUtilities.invokeAndWait(() -> {
                for (var troopers : List.of(infantry, armor)) {
                    troopers.setOwner(fixture.player);
                    troopers.setPosition(new Coords(7, troopers.getId() + 3));
                    troopers.setDeployed(true);
                    fixture.game.addEntity(troopers, false);
                }
                fixture.source.refresh();
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        verify(view, fixture);
                        verifyGlare(view);
                        verifySprite();
                        verifyScatter();
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Wireframe view failed", failure.get()); }
        }
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        var tuning = GpuBoardTestUi.tuning(view);
        assertTrue(GpuWireframe.supported(), "Desktop OpenGL has polygon line mode");
        Mix shaded = render(view, "wireframe-off.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Shaded frames");
        assertTrue(shaded.other > .6, "The shaded board is neither dark nor line green: " + shaded);
        assertNull(field(((GpuWireframe) field(view, "wireframe")).units, "batch"),
              "The thermal view is optional: loading leaves its programs to its first use");

        toggle("utility-wireframe");
        assertTrue(tuning.wireframe());
        Mix wire = render(view, "wireframe-on.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Wireframe frames");
        assertTrue(wire.line > .03, "Green edges cover the board: " + wire);
        assertTrue(wire.dark > .25, "The hidden-line fill leaves a dark ground between edges: " + wire);
        assertTrue(wire.other < .15, "Only units and overlays keep other colors: " + wire);

        // The same posed model must change temperature without corrupting its team-color outline or paint.
        var unit = ((Map<String, ModelInstance>) field(view, "unitInstances")).get("1:-1");
        Vector3 body = unit.calculateBoundingBox(new BoundingBox()).mul(unit.transform).getCenter(new Vector3());
        view.boardCamera.center(body);
        float originalZoom = view.boardCamera.camera.zoom;
        view.boardCamera.zoom(.08f);
        render(view, "wireframe-unit.png");
        // The cosmetic scatter draws no lines.
        Set<Object> scatter = visibleScatter(view);
        assertFalse(scatter.isEmpty(), "The board around the unit has scatter in view");
        List<Object> drawn = new ArrayList<>();
        var recording = new ModelBatch() {
            @Override public void render(RenderableProvider provider) { drawn.add(provider); }
        };
        try {
            ((GpuTerrain) field(view, "terrain")).renderWireframe(view.boardCamera.camera, recording);
        } finally { recording.dispose(); }
        assertFalse(drawn.isEmpty(), "The wireframe draws the board");
        assertTrue(drawn.stream().noneMatch(scatter::contains), "The wireframe draws no scatter");
        Vector3 point = view.boardCamera.camera.project(new Vector3(body), 0, 0,
              view.boardCamera.camera.viewportWidth, view.boardCamera.camera.viewportHeight);
        float density = (float) Gdx.graphics.getBackBufferWidth() / Gdx.graphics.getWidth();
        int x = Math.round(point.x * density), y = Math.round(point.y * density);
        Mix atUnit = classify(x - 4, y - 4, x + 5, y + 5, 1);
        assertTrue(atUnit.other > .6, "The unit fills its silhouette: " + atUnit);
        Color cold = colorAt(x, y);
        assertTrue(cold.b > cold.r + .2f, "Zero tracked heat must look blue/violet: " + cold);
        Object outline = unit.userData;
        toggle("utility-wireframe");
        render(view, "wireframe-unit-shaded.png");
        Color shadedUnit = colorAt(x, y);
        toggle("utility-wireframe");
        SwingUtilities.invokeAndWait(() -> { fixture.entity.heat = 30; fixture.source.refresh(); });
        render(view, "wireframe-unit-hot.png");
        Color hot = colorAt(x, y);
        assertTrue(hot.r > cold.r + .3f && hot.g > cold.g + .2f,
              "Actual game heat must change the model toward yellow/white: " + hot);
        assertSame(outline, unit.userData, "Thermal rendering must restore the outline data");
        assertSame(unit, ((Map<String, ModelInstance>) field(view, "unitInstances")).get("1:-1"),
              "Changing heat reuses the posed model");
        toggle("utility-wireframe");
        render(view, "wireframe-unit-shaded-restored.png");
        Color restoredUnit = colorAt(x, y);
        assertEquals(shadedUnit.r, restoredUnit.r, .08f);
        assertEquals(shadedUnit.g, restoredUnit.g, .08f);
        assertEquals(shadedUnit.b, restoredUnit.b, .08f);
        toggle("utility-wireframe");

        for (int id : new int[] { 2, 3 }) {
            var instance = ((Map<String, ModelInstance>) field(view, "unitInstances")).get(id + ":-1");
            view.boardCamera.center(UnitBounds.world(instance).getCenter(new Vector3()));
            view.boardCamera.zoom((id == 2 ? .06f : .10f) / view.boardCamera.camera.zoom);
            render(view, id == 2 ? "wireframe-infantry.png" : "wireframe-battle-armor.png");
            assertEquals(-1, ((BoardScene) field(view, "scene")).units().stream().filter(value -> value.id() == id)
                  .findFirst().orElseThrow().heat());
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Thermal troopers");
        }
        view.boardCamera.zoom(originalZoom / view.boardCamera.camera.zoom);

        view.boardCamera.setIsometric(false);
        toggle("utility-tactical");
        render(view, "wireframe-tactical.png");
        assertFalse(tuning.wireframe(), "Entering the Tactical View leaves the wireframe view");
        assertTrue(view.boardCamera.tactical(), "The Tactical View shows the units on their bases");
        assertFalse(((GpuTerrain) field(view, "terrain")).columns(), "over the terrain the graphics chose");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Tactical View after wireframe");

        toggle("utility-wireframe");
        assertTrue(tuning.wireframe());
        assertFalse(view.boardCamera.tactical(), "Turning the wireframe on leaves the Tactical View");
        render(view, "wireframe-after-tactical.png");
        assertFalse(((GpuTerrain) field(view, "terrain")).columns());

        toggle("utility-wireframe");
        assertFalse(tuning.wireframe());
        assertFalse(view.boardCamera.tactical(), "Leaving the wireframe view does not re-enter the Tactical View");
        Mix restored = render(view, "wireframe-restored.png");
        assertTrue(restored.other > .6, "Leaving the wireframe restores shading: " + restored);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Restored frames");
    }

    /** The actual HUD toggle must suppress the composite's entire lens effect and restore the chosen strength. */
    private static void verifyGlare(GpuBattleView view) throws Exception {
        var tuning = GpuBoardTestUi.tuning(view);
        tuning.setFixedSun(false);
        GpuBoardTestUi.<Slider>tuning(view, "Sun glare").setValue(.65f);
        for (int hour : new int[] { 6, 18, 0 }) {
            GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(hour);
            Vector3 direction = BoardAtmosphere.lighting(tuning.atmosphere()).direction();
            view.boardCamera.setIsometric(false);
            view.boardCamera.orbit(MathUtils.atan2Deg(direction.x, -direction.y), 70);
            render(view, "wireframe-glare-shaded-" + hour + ".png");
            float shaded = glareStrength(view);
            if (hour != 0) { assertTrue(shaded > 0, "Facing dawn/dusk must enable the lens effect"); }

            toggle("utility-wireframe");
            render(view, "wireframe-glare-off-" + hour + ".png");
            assertEquals(0, glareStrength(view), "Wireframe must suppress glare, source bloom and lens reflections");
            assertEquals(.65f, tuning.atmosphereOptions().sunGlare(), .00001f,
                  "Wireframe must retain the user's glare setting");

            toggle("utility-wireframe");
            render(view, "wireframe-glare-restored-" + hour + ".png");
            assertEquals(shaded, glareStrength(view), .00001f, "Leaving wireframe must restore the lens effect");
        }
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Wireframe glare toggles");
    }

    private static float glareStrength(GpuBattleView view) throws Exception {
        var shader = (ShaderProgram) field(field(view, "atmosphere"), "compositeShader");
        var value = BufferUtils.newFloatBuffer(4);
        Gdx.gl.glGetUniformfv(shader.getHandle(), shader.getUniformLocation("u_sunGlare"), value);
        return value.get(2);
    }

    /** Baked field cover and instanced fungi must look exactly like the same wireframe board without scatter. */
    private static void verifyScatter() throws Exception {
        // These offline terrains link behind no view frame: the shown view's per-frame link budget never resets.
        GpuGlsl.presenting(false);
        var terrain = new GpuTerrain();
        var plain = new GpuTerrain();
        var wireframe = new GpuWireframe();
        var camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.camera.zoom = .3f;
        camera.center(BoardGeometry.center(new Coords(1, 1), 0));
        try (var output = new GpuSceneTestOutput()) {
            for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.FUNGUS)) {
                terrain.update(scatterScene(family, true));
                // The bare board: without the fungi's features, and without the grass a meadow grows by itself.
                plain.setScatterHidden(true);
                plain.update(scatterScene(family, false));
                Pixmap shaded = scatterFrame(terrain, camera, null, output);
                Pixmap bare = scatterFrame(plain, camera, null, output);
                Pixmap expected = scatterFrame(plain, camera, wireframe, output);
                Pixmap actual = scatterFrame(terrain, camera, wireframe, output);
                Pixmap restored = scatterFrame(terrain, camera, null, output);
                try {
                    assertFalse(shaded.getPixels().equals(bare.getPixels()), family + " must have visible scatter in normal rendering");
                    assertEquals(expected.getPixels(), actual.getPixels(), family + " scatter must affect neither wireframe fill nor lines");
                    assertEquals(shaded.getPixels(), restored.getPixels(), family + " scatter returns after wireframe");
                } finally {
                    shaded.dispose(); bare.dispose(); expected.dispose(); actual.dispose(); restored.dispose();
                }
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Scatter wireframe and shaded rendering");
        } finally {
            wireframe.dispose(); plain.dispose(); terrain.dispose();
        }
    }

    private static BoardScene scatterScene(BoardScene.Surface family, boolean scatter) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) { image.setRGB(x, y, 0x607055); }
        var ground = new BoardScene.Pixels(image);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                var features = family == BoardScene.Surface.FUNGUS && scatter
                      ? List.of(new BoardScene.Feature(BoardFungus.SCATTER.get((x + y) % BoardFungus.SCATTER.size()),
                            20, 8, 0, 1, .2f, 0, BoardScene.FeatureKind.SCATTER)) : List.<BoardScene.Feature>of();
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, family,
                      null, null, features, List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 4, 4, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static Pixmap scatterFrame(GpuTerrain terrain, BoardCamera camera, GpuWireframe wireframe, GpuSceneTestOutput output) {
        terrain.settings().run(() -> {
            if (wireframe == null) { terrain.renderShadows(camera.camera, List.of()); }
            output.scene().begin();
            Gdx.gl.glDepthMask(true);
            ScreenUtils.clear(0, 0, 0, 1, true);
            if (wireframe == null) { terrain.render(camera.camera); }
            else { wireframe.fill(camera.camera, terrain); }
            output.display();
            if (wireframe != null) { wireframe.lines(camera.camera, terrain); }
        });
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    /** The scatter that the board's visible chunks show at the camera's detail. */
    private static Set<Object> visibleScatter(GpuBattleView view) throws Exception {
        Set<Object> scatter = new HashSet<>();
        for (Object chunk : (List<?>) field(field(view, "terrain"), "chunks")) {
            if ((boolean) field(chunk, "scatterVisible")
                  && view.boardCamera.camera.frustum.boundsInFrustum((BoundingBox) field(chunk, "bounds"))) {
                scatter.addAll((List<?>) field(chunk, "scatter"));
            }
        }
        return scatter;
    }

    /** Click a HUD utility: the wireframe view's or the Tactical View's switch. */
    private static void toggle(String utility) {
        GpuBoardTestUi.click(utility);
    }

    private record Mix(double line, double dark, double other) { }

    /** Flat fallback artwork must retain its alpha cutout and respond to heat despite having no vertical extent. */
    private static void verifySprite() {
        var pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setColor(Color.WHITE);
        pixels.fillRectangle(0, 0, 1, 2);
        var texture = new Texture(pixels);
        pixels.dispose();
        var model = GpuUnitModel.sprite(new BoardScene.Pixels(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)),
              new TextureRegion(texture));
        var thermal = new GpuThermalUnits();
        try (var output = new GpuSceneTestOutput(64, 64)) {
            var camera = new OrthographicCamera(2, 2);
            camera.position.set(0, 0, 2);
            camera.lookAt(0, 0, 0);
            camera.near = .1f;
            camera.far = 10;
            camera.update();
            var point = new BoardScene.Waypoint(new Coords(0, 0), 0, 0);
            var unit = new BoardScene.Unit(1, -1, "Sprite", point, null, false, null, 1, false, null, 0,
                  List.of(point.coords()), null, 30);
            output.scene().begin();
            Gdx.gl.glDepthMask(true);
            ScreenUtils.clear(0, 0, 0, 0, true);
            thermal.add(model.instance, unit, UnitBounds.world(model.instance));
            thermal.render(camera);
            output.display();
            Pixmap rendered = Pixmap.createFromFrameBuffer(0, 0, 64, 64);
            try {
                Color body = new Color(rendered.getPixel(16, 32));
                assertTrue(body.r > .7f && body.g > .3f, "A hot flat sprite uses warm colors: " + body);
                assertEquals(0, rendered.getPixel(48, 32), "Transparent sprite pixels must leave the target untouched");
            } finally { rendered.dispose(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Thermal sprite fallback");
        } finally {
            thermal.dispose();
            model.dispose();
            texture.dispose();
        }
    }

    private static Color colorAt(int x, int y) {
        Pixmap image = Pixmap.createFromFrameBuffer(x - 4, y - 4, 9, 9);
        try {
            float r = 0, g = 0, b = 0;
            for (int row = 0; row < 9; row++) {
                for (int column = 0; column < 9; column++) {
                    int pixel = image.getPixel(column, row);
                    r += pixel >>> 24;
                    g += (pixel >>> 16) & 255;
                    b += (pixel >>> 8) & 255;
                }
            }
            return new Color(r / (81 * 255), g / (81 * 255), b / (81 * 255), 1);
        } finally { image.dispose(); }
    }

    /** Settle the board, then classify the board area outside the HUD bands, as {@link GpuBoardTestUi#capture} samples it. */
    private static Mix render(GpuBattleView view, String name) throws Exception {
        renderReady(view);
        GpuBoardTestUi.present(view);
        view.render();
        File directory = new File(System.getProperty("megamek.gpu.screenshots"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
        // The board between the HUD's panels: right of Forces, above the action bar, below the toolbar.
        return classify(340, 160, Gdx.graphics.getBackBufferWidth() - 20, Gdx.graphics.getBackBufferHeight() - 70, 2);
    }

    /** Line green, near-black ground, or anything else, within back-buffer pixels whose rows count from the bottom. */
    private static Mix classify(int left, int bottom, int right, int top, int step) {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int line = 0;
            int dark = 0;
            int total = 0;
            for (int y = bottom; y < top; y += step) {
                for (int x = left; x < right; x += step) {
                    int pixel = image.getPixel(x, y);
                    int red = pixel >>> 24;
                    int green = (pixel >>> 16) & 255;
                    int blue = (pixel >>> 8) & 255;
                    if (green > 150 && green > red + 60 && green > blue + 40) {
                        line++;
                    } else if (Math.max(red, Math.max(green, blue)) < 30) {
                        dark++;
                    }
                    total++;
                }
            }
            return new Mix((double) line / total, (double) dark / total, (double) (total - line - dark) / total);
        } finally {
            image.dispose();
        }
    }
}
