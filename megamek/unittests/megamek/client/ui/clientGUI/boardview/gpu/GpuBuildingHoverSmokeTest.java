/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native pointer routing and the visible column joining a building hit to its owning hex. */
@Tag("on-demand")
class GpuBuildingHoverSmokeTest {
    private static final Coords BUILDING = new Coords(3, 3);

    /** A flat 7 x 7 board with a five-level building in its middle. */
    private static Board board() {
        Board board = new Board(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) { board.setHex(new Coords(x, y), new Hex(0)); }
        }
        board.setHex(BUILDING, new Hex(0, "building:2;bldg_elev:5;bldg_cf:40", ""));
        return board;
    }

    @Test
    void hoverFollowsBuildingHitWhileKeepingItsHex() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var preferences = megamek.client.ui.clientGUI.GUIPreferences.getInstance();
        boolean types = preferences.getHexTypesEnabled();
        SwingUtilities.invokeAndWait(() -> preferences.setHexTypesEnabled(false));
        try (var fixture = GpuBoardFixture.create(board())) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;
                private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);

                @Override
                public void render() {
                    try {
                        assertTrue(System.nanoTime() < deadline, "Building hover fixture must finish");
                        super.render();
                        if (frames() == 0) { return; }
                        if (++tick == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.setPerspective(false);
                            boardCamera.camera.zoom = .4f;
                            boardCamera.center(BoardGeometry.center(BUILDING, 2));
                        } else {
                            // Pixel comparisons need complete programs for every cutaway state, not a partial
                            // frame while newly encountered material shaders link in the background.
                            GpuGlsl.presenting(false);
                            try { checkHover(this); }
                            finally { GpuGlsl.presenting(true); }
                            if (tick == 2) { boardCamera.setPerspective(true); }
                            else { Gdx.app.exit(); }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, config);
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setHexTypesEnabled(types));
        }
        if (failure.get() != null) { throw new AssertionError("Building hover column", failure.get()); }
    }

    /**
     * Occluded selection outlines: the acting unit behind the building keeps its ring, faintly where the
     * building hides it. The frame with the overlay's hidden rings differs from the same frame without them in a mint
     * ring only.
     */
    @Test
    void anActingUnitBehindTheBuildingKeepsAFaintRing() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                var panel = mock(MovementDisplay.class);
                when(panel.currentEntity()).thenReturn(fixture.entity);
                when(panel.getComponents()).thenReturn(new Component[0]);
                when(panel.getActionButtons()).thenReturn(List.of());
                when(panel.getCompletionButtons()).thenReturn(List.of());
                fixture.panel = panel;
                fixture.source.refresh();
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;
                private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);

                @Override
                public void render() {
                    try {
                        assertTrue(System.nanoTime() < deadline, "Hidden ring fixture must finish");
                        super.render();
                        if (frames() == 0) { return; }
                        if (++tick == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.center(BoardGeometry.center(BUILDING, 2));
                            boardCamera.zoom(.35f);
                        } else if (tick == 2) {
                            Coords behind = BUILDING.translated(behind(this));
                            SwingUtilities.invokeAndWait(() -> {
                                fixture.entity.setPosition(behind);
                                fixture.source.refresh();
                            });
                        } else if (tick == 30) {
                            checkHiddenRing(this, this::shot);
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }

                private Pixmap shot() {
                    super.render();
                    return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
                          Gdx.graphics.getBackBufferHeight());
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Hidden ring", failure.get()); }
    }

    /** The direction of the building's neighbour that lies straight behind it as the camera looks at it. */
    private static int behind(GpuBattleView view) {
        Vector3 eye = view.boardCamera.camera.position;
        Vector3 centre = BoardGeometry.center(BUILDING, 0);
        int best = 0;
        float bestCosine = -2;
        for (int direction = 0; direction < 6; direction++) {
            Vector3 next = BoardGeometry.center(BUILDING.translated(direction), 0).sub(centre);
            float cosine = (next.x * (centre.x - eye.x) + next.y * (centre.y - eye.y))
                  / (float) (Math.hypot(next.x, next.y) * Math.hypot(centre.x - eye.x, centre.y - eye.y));
            if (cosine > bestCosine) {
                bestCosine = cosine;
                best = direction;
            }
        }
        return best;
    }

    private static void checkHiddenRing(GpuBattleView view, Supplier<Pixmap> shot) throws Exception {
        var overlay = (GpuBoardOverlay) field(view, "overlay");
        var hidden = GpuBoardOverlay.class.getDeclaredField("hiddenUnitMarks");
        hidden.setAccessible(true);
        Object rings = hidden.get(overlay);
        assertFalse(((Map<?, ?>) rings).isEmpty(),
              "The acting unit's ring is drawn once more where the board hides it");
        Pixmap with = shot.get();
        hidden.set(overlay, Map.of());
        Pixmap without;
        try {
            without = shot.get();
        } finally {
            hidden.set(overlay, rings);
        }
        try {
            int changed = 0, mint = 0;
            for (int x = 0; x < with.getWidth(); x++) {
                for (int y = 0; y < with.getHeight(); y++) {
                    int a = with.getPixel(x, y), b = without.getPixel(x, y);
                    int red = (a >>> 24) - (b >>> 24), green = (a >>> 16 & 255) - (b >>> 16 & 255);
                    if (Math.abs(red) > 8 || Math.abs(green) > 8 || Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)) > 8) {
                        changed++;
                        if (green > red) { mint++; }
                    }
                }
            }
            assertTrue(changed > 50, "The hidden part of the ring shows: " + changed + " pixels");
            assertTrue(mint > .9f * changed, "It shows in the acting unit's mint: " + mint + " of " + changed);
        } finally {
            with.dispose();
            without.dispose();
        }
    }

    private static void checkHover(GpuBattleView view) throws Exception {
        var scene = (BoardScene) field(view, "scene");
        var terrain = (GpuTerrain) field(view, "terrain");
        var ui = (GpuBoardHud) field(view, "ui");
        var camera = view.boardCamera.camera;
        checkCutaway(terrain);
        checkRenderedStoreys(view, terrain, ui);
        checkModularStoreys(view, ui);
        assertTrue(scene.tile(BUILDING).text().stream().anyMatch(label -> label.text().equals("HEIGHT 5")),
              "Captured HEIGHT text includes its space");
        Vector3 center = camera.project(BoardGeometry.center(BUILDING, 3), 0, 0,
              camera.viewportWidth, camera.viewportHeight);
        int x = Math.round(center.x);
        float lowest = Float.POSITIVE_INFINITY, highest = Float.NEGATIVE_INFINITY;
        int wallY = -1;
        Input original = Gdx.input;
        Input pointer = mock(Input.class);
        Gdx.input = pointer;
        try {
            for (int y = Gdx.graphics.getHeight() - Math.round(center.y) + 100;
                  y > Gdx.graphics.getHeight() - Math.round(center.y) - 150; y -= 10) {
                if (ui.hit(x, y)) { continue; }
                var ray = camera.getPickRay(x, y, 0, 0, camera.viewportWidth, camera.viewportHeight);
                var hit = terrain.selectionHit(scene, ray);
                if (hit == null || !BUILDING.equals(hit.coords())) { continue; }
                float z = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance())).z;
                if (z < BoardGeometry.level()) { continue; }
                when(pointer.getX()).thenReturn(x);
                when(pointer.getY()).thenReturn(y);
                original.getInputProcessor().mouseMoved(x, y);
                assertEquals(BUILDING, field(view, "hovered"));
                assertEquals(z, (float) field(view, "hoverZ"), .001f,
                      "Moving within one hex must update the hover height from the same picking ray");
                lowest = Math.min(lowest, z);
                highest = Math.max(highest, z);
                float level = z / BoardGeometry.level();
                if (Math.abs(level - Math.round(level)) > .25f) { wallY = y; }
            }
            assertTrue(highest - lowest > BoardGeometry.level(), "The pointer sweep must cover wall hits at different heights");
            assertTrue(wallY >= 0, "The render check must use a wall hit well between floors, not an already-aligned roof");
            when(pointer.getY()).thenReturn(wallY);
            original.getInputProcessor().mouseMoved(x, wallY);
            // The floor's column, which the view draws over the battle HUD's overlay
            var draw = GpuBattleView.class.getDeclaredMethod("renderHoverRings");
            draw.setAccessible(true);
            HdpiUtils.glViewport(0, 0, (int) camera.viewportWidth, (int) camera.viewportHeight);
            ScreenUtils.clear(0, 0, 0, 1, true);
            draw.invoke(view);
            Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
                  Gdx.graphics.getBackBufferHeight());
            try {
                float base = BoardTacticalGeometry.floatingZ(scene, BUILDING);
                float level = (float) Math.floor((float) field(view, "hoverZ") / BoardGeometry.level() + .0001f);
                float top = level * BoardGeometry.level() + .5f * BoardGeometry.hexScale();
                Vector3 origin = BoardGeometry.center(BUILDING, 0);
                int brightEdges = 0, faintEdges = 0, verticals = 0;
                for (int edge = 0; edge < 6; edge++) {
                    Vector3 a = BoardGeometry.inset(BoardGeometry.corner(BUILDING, 0, edge), origin,
                          GpuBattleView.HOVER_HEX_INSET);
                    Vector3 b = BoardGeometry.inset(BoardGeometry.corner(BUILDING, 0, edge + 1), origin,
                          GpuBattleView.HOVER_HEX_INSET);
                    Vector3 midpoint = a.cpy().lerp(b, .5f);
                    if (brightness(pixels, view, midpoint.cpy().add(0, 0, top)) > 240) { brightEdges++; }
                    int baseBrightness = brightness(pixels, view, midpoint.cpy().add(0, 0, base));
                    if (baseBrightness > 40 && baseBrightness < 100) { faintEdges++; }
                    int verticalBrightness = brightness(pixels, view, a.cpy().add(0, 0, (base + top) / 2));
                    if (verticalBrightness > 40 && verticalBrightness < 100) { verticals++; }
                }
                assertEquals(6, brightEdges, "All upper hex edges snap to the supporting floor at full hover brightness");
                assertEquals(6, faintEdges, "All base edges are faint");
                assertEquals(6, verticals, "All six corners connect to the base with faint lines");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { pixels.dispose(); }
        } finally { Gdx.input = original; }
    }

    /** The brightest red within two pixels of a world point's projection. */
    private static int brightness(Pixmap pixels, GpuBattleView view, Vector3 world) {
        var camera = view.boardCamera.camera;
        Vector3 screen = camera.project(world, 0, 0, camera.viewportWidth, camera.viewportHeight);
        int result = 0;
        for (int x = Math.round(screen.x) - 2; x <= Math.round(screen.x) + 2; x++) {
            for (int y = Math.round(screen.y) - 2; y <= Math.round(screen.y) + 2; y++) {
                result = Math.max(result, pixels.getPixel(x, y) >>> 24);
            }
        }
        return result;
    }

    private static void checkCutaway(GpuTerrain terrain) throws Exception {
        var floors = GpuModularBuildingSmokeTest.floors(terrain, BUILDING);
        assertEquals(5, floors.size());
        for (int level : new int[] { 0, 2, 4, 5, 1 }) {
            terrain.animate(0, List.of(), level == 1 ? 1 : .5f, BUILDING, level * BoardGeometry.level());
            GpuModularBuildingSmokeTest.checkInteriorCache(terrain, level < 5 && level != 1);
            for (int floor = 0; floor < floors.size(); floor++) {
                assertFalse(
                      floors.get(floor).materials.first().has(BlendingAttribute.Type),
                      "Interior hover reveals its floor; roof hover leaves the whole building opaque");
            }
        }
        var unitModel = new ModelBuilder().createBox(3, 3, 3,
              new Material(), VertexAttributes.Usage.Position);
        try {
            var unit = new ModelInstance(unitModel,
                  BoardGeometry.center(BUILDING, 4).add(0, 0, 2));
            for (boolean occupied : new boolean[] { false, true, false, true }) {
                terrain.animate(0, occupied ? List.of(unit) : List.of(), .5f,
                      BUILDING, 2 * BoardGeometry.level());
                for (Object chunk : (List<?>) field(terrain, "chunks")) {
                    for (Object prop : (List<?>) field(chunk, "cutaways")) {
                        if (!BUILDING.equals(field(prop, "coords")) || !Float.isNaN((float) field(prop, "floorZ"))) { continue; }
                        assertEquals(!occupied, field(prop, "hoverOpaque") != null,
                              "Unit arrival/departure must restore the correct opacity outside the hover opening");
                        var shell = (ModelInstance) field(prop, "instance");
                        for (Material material : shell.materials) {
                            assertEquals(.5f, material.get(BlendingAttribute.class, BlendingAttribute.Type).opacity,
                                  "Hover and unit cutaways share the chosen wall opacity");
                            var clip = material.get(GpuBuildingCutaway.class, GpuBuildingCutaway.TYPE);
                            assertTrue(occupied ? clip == null : clip != null && clip.inside,
                                  "Hover fades only its storey; unit occupancy fades the whole shell");
                        }
                    }
                }
            }
        } finally { unitModel.dispose(); }
        terrain.animate(0, List.of(), .5f, null, Float.NaN);
        GpuModularBuildingSmokeTest.checkInteriorCache(terrain, false);
    }

    private static void checkModularStoreys(GpuBattleView view, GpuBoardHud ui) throws Exception {
        BoardScene scene = BoardSurfaceBlendTest.scene(coords -> {
            var tile = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            var hex = new Hex(0, "building:2;bldg_elev:5;bldg_cf:40", "");
            return new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), null, null,
                  coords.equals(BUILDING) ? BoardFeatures.capture(hex, coords,
                        Map.of(Terrains.BUILDING, GpuBuildingTest.ASSET)) : List.of(),
                  List.of(), BoardLiquid.NONE, null, true);
        });
        var terrain = new GpuTerrain();
        try {
            terrain.update(scene);
            checkCutaway(terrain);
            checkRenderedStoreys(view, terrain, ui);
        } finally { terrain.dispose(); }
    }

    private static void checkRenderedStoreys(GpuBattleView view, GpuTerrain terrain, GpuBoardHud ui) throws Exception {
        // Terrain writes scaled scene radiance; byte assertions consume the shared display conversion.
        try (var output = new GpuSceneTestOutput()) {
            checkRenderedStoreys(view, terrain, ui, output);
        }
    }

    private static void checkRenderedStoreys(GpuBattleView view, GpuTerrain terrain, GpuBoardHud ui,
          GpuSceneTestOutput output) throws Exception {
        var camera = view.boardCamera.camera;
        output.begin(0, 0, 0, 1);
        HdpiUtils.glViewport(0, 0, (int) camera.viewportWidth, (int) camera.viewportHeight);
        terrain.render(camera);
        output.display();
        var pages = (GpuPropBatch) field(terrain, "propBatch");
        long pageBuilds = pages.rebuilds();
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var buildingBounds = terrain.roofBounds(BUILDING);
        Pixmap baseline = Pixmap.createFromFrameBuffer(0, 0, width, height);
        PixmapIO.writePNG(new FileHandle("build/gpu-board-review/hover-baseline.png"), baseline, -1, true);
        var depth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        try {
            for (int level : new int[] { 2, 4, 0, 5, 1, 2 }) {
                terrain.animate(0, List.of(), level == 1 ? 1 : .5f, BUILDING, level * BoardGeometry.level());
                output.begin(0, 0, 0, 1);
                HdpiUtils.glViewport(0, 0, (int) camera.viewportWidth, (int) camera.viewportHeight);
                terrain.render(camera);
                output.display();
                assertEquals(pageBuilds, pages.rebuilds(),
                      "Opening/closing a building storey must not rebuild static prop pages");
                Pixmap hiddenWalls = Pixmap.createFromFrameBuffer(0, 0, width, height);
                // Continue the same scene/depth after its intermediate display readback; do not clear it.
                output.scene().begin();
                HdpiUtils.glViewport(0, 0, (int) camera.viewportWidth, (int) camera.viewportHeight);
                terrain.renderTransparent(camera);
                output.display();
                Pixmap opened = Pixmap.createFromFrameBuffer(0, 0, width, height);
                try {
                    int changedInside = 0, visibleWalls = 0, changedRoof = 0, visibleRoof = 0;
                    int unchangedOutside = 0, changedOutside = 0;
                    String outsideSample = "";
                    for (int x = 0; x < width; x += 4) {
                        for (int y = 0; y < 0 + camera.viewportHeight; y += 4) {
                            Vector3 point = new Vector3((x + .5f) / camera.viewportWidth * 2 - 1,
                                  (y + .5f - 0) / camera.viewportHeight * 2 - 1,
                                  depth.get(y * width + x) * 2 - 1).prj(camera.invProjectionView);
                            if (!buildingBounds.contains(point) || point.z <= buildingBounds.min.z + .01f) { continue; }
                            float z = point.z / BoardGeometry.level();
                            if (Math.abs(z - level) < .04f || level != 4 && Math.abs(z - level - 1) < .04f) { continue; }
                            int a = baseline.getPixel(x, y), b = opened.getPixel(x, y);
                            int difference = Math.abs((a >>> 24) - (b >>> 24))
                                  + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)) + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
                            if (level < 5 && level != 1 && z > level && (z < level + 1 || level == 4)) {
                                int hidden = hiddenWalls.getPixel(x, y);
                                int wallContribution = Math.abs((hidden >>> 24) - (b >>> 24))
                                      + Math.abs((hidden >>> 16 & 255) - (b >>> 16 & 255))
                                      + Math.abs((hidden >>> 8 & 255) - (b >>> 8 & 255));
                                if (level == 4 && z >= 5 - .002f) {
                                    if (difference > 10) { changedRoof++; }
                                    if (wallContribution > 10) { visibleRoof++; }
                                } else {
                                    if (difference > 10) { changedInside++; }
                                    if (wallContribution > 10) { visibleWalls++; }
                                }
                            } else {
                                if (difference > 10) {
                                    changedOutside++;
                                    outsideSample = " at pixel " + x + "," + y + " world=" + point + " delta=" + difference;
                                }
                                else { unchangedOutside++; }
                            }
                        }
                    }
                    if (level == 2) {
                        PixmapIO.writePNG(new FileHandle(
                              "build/gpu-board-review/hover-floor-" + (camera.projection.val[Matrix4.M33] == 0) + ".png"), opened, -1, true);
                    }
                    if (level == 4) {
                        PixmapIO.writePNG(new FileHandle(
                              "build/gpu-board-review/hover-top-floor-" + (camera.projection.val[Matrix4.M33] == 0) + ".png"), opened, -1, true);
                        assertTrue(changedRoof > 10, "Hovering the top storey must also fade the roof: " + changedRoof);
                        assertTrue(visibleRoof > 10, "The faded roof must remain visible: " + visibleRoof);
                    }
                    assertTrue(unchangedOutside > 100, "Storeys outside the cutaway must remain visible: " + unchangedOutside
                          + " unchanged, " + changedOutside + " changed, level=" + level + ", bounds=" + buildingBounds);
                    assertEquals(0, changedOutside, "Hover must not alter walls outside the highlighted storey: " + level + outsideSample);
                    if (level < 5 && level != 1) {
                        assertTrue(changedInside > 10, "The highlighted storey's walls must become translucent: " + level);
                        assertTrue(visibleWalls > 10, "Hovered walls must remain visible rather than disappearing: " + level);
                    }
                    if (level == 2 || level == 4) { checkDepth(view, terrain, ui); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } finally { opened.dispose(); hiddenWalls.dispose(); }
            }
        } finally {
            baseline.dispose();
            terrain.animate(0, List.of(), .5f, null, Float.NaN);
        }
    }

    private static void checkDepth(GpuBattleView view, GpuTerrain terrain, GpuBoardHud ui) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var buildingBounds = terrain.roofBounds(BUILDING);
        var colorDepth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, colorDepth);
        var config = new DepthShader.Config();
        config.depthBufferOnly = true;
        config.defaultCullFace = GL20.GL_BACK;
        var pass = new ModelBatch(GpuTreeInstances.depthProvider(config));
        try {
            Gdx.gl.glDepthMask(true);
            ScreenUtils.clear(0, 0, 0, 1, true);
            terrain.renderDepth(view.boardCamera.camera, List.of(), pass);
            var depth = BufferUtils.newFloatBuffer(width * height);
            Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
            int checked = 0;
            for (int x = 450; x < 750; x += 4) {
                for (int y = 350; y < 550; y += 4) {
                    var camera = view.boardCamera.camera;
                    Vector3 point = new Vector3((x + .5f) / camera.viewportWidth * 2 - 1,
                          (y + .5f - 0) / camera.viewportHeight * 2 - 1,
                          colorDepth.get(y * width + x) * 2 - 1).prj(camera.invProjectionView);
                    // Ground exposed through the opening uses surface displacement only in the colour pass.
                    if (!buildingBounds.contains(point)
                          || point.z < 2 * BoardGeometry.level() - .1f * BoardGeometry.hexScale()) { continue; }
                    checked++;
                    Vector3 depthPoint = new Vector3((x + .5f) / camera.viewportWidth * 2 - 1,
                          (y + .5f) / camera.viewportHeight * 2 - 1,
                          depth.get(y * width + x) * 2 - 1).prj(camera.invProjectionView);
                    assertEquals(colorDepth.get(y * width + x), depth.get(y * width + x), .00001f,
                          "Depth must preserve the storey opening at " + point + "; depth hit " + depthPoint);
                }
            }
            assertTrue(checked > 50, "Depth comparison must cover the building's visible surfaces");
        } finally { pass.dispose(); }
    }

}
