/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The embedded inspector draws real terrain, switches boards and releases its offscreen GPU resources. */
@Tag("on-demand")
class LobbyBoardPreviewSmokeTest {
    @Test
    void rendersAndReplacesTheInspectedBoardInsideThePanel() {
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setInitialVisible(false);
        configuration.setWindowedMode(960, 720);
        UiTestStage.onGl(configuration, () -> {
            GpuGlsl.detect();
            UiTheme theme = new UiTheme(UiTestStage.FONTS);
            theme.worldFont("default-font", "Noto Sans/NotoSans-Regular.ttf", 15, 0);
            theme.worldFont(GpuBoardSkin.OUTLINE_FONT, "Noto Sans/NotoSans-Regular.ttf", 15,
                  15f * GpuBoardSkin.OUTLINE / GpuBoardSkin.FONT_RESOLUTION);
            Stage stage = new Stage(new ScreenViewport());
            stage.getViewport().update(960, 720, true);
            LobbyBoardPreview preview = new LobbyBoardPreview(new UiKit(theme.skin), () -> { });
            stage.addActor(preview);
            preview.setBounds(0, 0, 960, 720);
            try {
                Board board = Board.createEmptyBoard(6, 5);
                board.getHex(2, 2).setLevel(2);
                board.getHex(2, 2).addTerrain(new Terrain(Terrains.WOODS, 1));
                BoardSceneryLayouts.convert(board);
                var conditions = new PlanetaryConditions();
                conditions.setLight(Light.FULL_MOON);
                Inspection inspection = inspection(board, conditions);
                preview.loading("Inspection test");
                Image image = preview.findActor("map-inspection-image");
                assertSame(image, stage.getKeyboardFocus(), "opening the preview claims camera focus immediately");
                preview.show(inspection.source());
                GpuLoadingPanel loading = preview.findActor("board-loading-panel");
                assertTrue(loading.isVisible());
                assertNotNull(loading.findActor("board-loading-grid"));
                assertNotNull(loading.findActor("board-loading-shaders"));
                // A window/input reset during loading must not leave the newly presented preview unfocused.
                stage.setKeyboardFocus(null);
                stage.setScrollFocus(null);
                awaitImage(stage, preview);
                assertFalse(loading.isVisible());
                assertSame(image, stage.getKeyboardFocus());
                assertSame(image, stage.getScrollFocus());
                assertEquals(preview.getWidth(), image.getWidth(), .01f);
                assertEquals(preview.getHeight(), image.getHeight(), .01f);
                assertEquals(0, image.getY(), .01f, "title, controls and hints must overlay the full preview");
                GpuBoardTestUi.assertHorizontalBounds(preview, preview);
                UiTestStage.capture("map-embedded-inspection", 960, 720).dispose();
                BoardCamera camera = checkCameraInput(stage, preview, theme);
                checkHexInspection(stage, preview, camera, inspection);
                checkConditions(stage, preview, camera, inspection);
                checkDisplayPreferences(stage, preview, camera, inspection);
                camera.setIsometric(false);
                camera.zoom(.3f);
                camera.pan(90, 60);
                // Most catalogue boards have identical dimensions and the preview scene's board ID is always 0.
                board = Board.createEmptyBoard(6, 5);
                board.getHex(1, 1).setLevel(3);
                BoardSceneryLayouts.convert(board);
                Inspection replacementSource = inspection(board, new PlanetaryConditions());
                BoardScene replacement = replacementSource.source().takeFrame().scene();
                preview.loading("Another board");
                preview.show(replacementSource.source());
                SwingUtilities.invokeAndWait(() -> assertTrue(inspection.source().isClosed(),
                      "replacing a preview releases its listeners and LOS model"));
                assertTrue(loading.isVisible());
                assertNull(image.getDrawable(), "the previous map must disappear as soon as another is requested");
                var terrainField = LobbyBoardPreview.class.getDeclaredField("terrain");
                terrainField.setAccessible(true);
                GpuTerrain terrain = (GpuTerrain) terrainField.get(preview);
                assertFalse(terrain.ready(replacement), "same-sized maps are replacements, not edits shown over old terrain");
                stage.act(1 / 60f);
                assertTrue(loading.isVisible(), "the loading screen must get a frame before the new build starts");
                assertNull(image.getDrawable());
                TextField search = new TextField("", theme.skin, "input");
                search.setBounds(20, 70, 220, 35);
                stage.addActor(search);
                click(stage, search);
                awaitImage(stage, preview);
                assertFalse(loading.isVisible());
                assertSame(search, stage.getKeyboardFocus(), "finishing a load must not steal focus after an outside click");
                search.remove();
                assertEquals(45, camera.azimuth(), .001f, "a new preview resets the camera bearing");
                assertEquals(BoardCamera.ISOMETRIC_TILT, camera.tilt(), .001f, "a new preview starts in ISO");
                float fittedZoom = camera.camera.zoom;
                Vector3 fittedFocus = camera.focus.cpy();
                click(stage, preview.findActor("map-view-fit"));
                assertEquals(fittedZoom, camera.camera.zoom, .001f, "a new preview is already fitted to the panel");
                assertTrue(fittedFocus.epsilonEquals(camera.focus, .001f), "a new preview is already centered");
                assertEquals(3, terrain.presentation(replacement).tile(new Coords(1, 1)).elevation(),
                      "the first presented image belongs to the replacement map");
                assertEquals("Another board", preview.<Label>findActor("map-inspection-title").getText().toString());
                preview.setSize(720, 600);
                awaitImage(stage, preview);
                GpuBoardTestUi.assertHorizontalBounds(preview, preview);
                UiTestStage.capture("map-embedded-inspection-small", 960, 720).dispose();
                checkLineOfSight(stage, preview, camera, replacementSource);
                preview.dispose();
                assertNull(((Image) preview.findActor("map-inspection-image")).getDrawable());
                assertNull(stage.getKeyboardFocus());
                assertNull(stage.getScrollFocus());
            } finally {
                preview.dispose();
                stage.dispose();
                theme.dispose();
            }
        });
    }

    private static void checkLineOfSight(Stage stage, LobbyBoardPreview preview, BoardCamera camera,
          Inspection inspection) throws Exception {
        click(stage, preview.findActor("map-view-top"));
        click(stage, preview.findActor("map-view-fit"));
        Image image = preview.findActor("map-inspection-image");
        Coords from = new Coords(0, 3), to = new Coords(5, 3);
        clickHex(stage, image, camera, from, Input.Buttons.RIGHT);
        awaitImage(stage, preview);
        click(stage, stage.getRoot().findActor("map-line-of-sight"));
        SwingUtilities.invokeAndWait(() -> { });
        awaitImage(stage, preview);
        assertTrue(inspection.source().takeFrame().panels().los().open());
        assertEquals(from, inspection.source().takeFrame().panels().los().start().coords(),
              "picking is local to the resized embedded viewport");
        clickHex(stage, image, camera, to, Input.Buttons.LEFT);
        SwingUtilities.invokeAndWait(() -> { });
        awaitImage(stage, preview);
        var result = inspection.source().takeFrame().panels().los();
        assertEquals(to, result.end().coords());
        assertEquals(from.distance(to), result.distance());
        assertNotNull(result.ruler(), "the shared model publishes the visible LOS ray");
        assertTrue(preview.findActor("los-panel").isVisible());
        UiTestStage.capture("map-embedded-inspection-los", 960, 720).dispose();
        click(stage, preview.findActor("los-close"));
        SwingUtilities.invokeAndWait(() -> { });
        awaitImage(stage, preview);
        assertFalse(inspection.source().takeFrame().panels().los().open());
    }

    private static void clickHex(Stage stage, Image image, BoardCamera camera, Coords coords, int button) {
        Vector3 point = camera.camera.project(BoardGeometry.center(coords, 0), 0, 0,
              camera.camera.viewportWidth, camera.camera.viewportHeight);
        Vector2 at = screen(stage, image, point.x * image.getWidth() / camera.camera.viewportWidth,
              point.y * image.getHeight() / camera.camera.viewportHeight);
        stage.touchDown(Math.round(at.x), Math.round(at.y), 0, button);
        stage.touchUp(Math.round(at.x), Math.round(at.y), 0, button);
    }

    private static void checkDisplayPreferences(Stage stage, LobbyBoardPreview preview, BoardCamera camera,
          Inspection inspection) throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        float resolution = preferences.getBoardRenderResolution();
        String aa = preferences.getBoardAntiAliasing(), quality = preferences.getBoardQuality();
        boolean tessellation = preferences.getBoardTessellation(), normals = preferences.getBoardNormalMaps();
        boolean coords = preferences.getCoordsEnabled(), types = preferences.getHexTypesEnabled();
        try {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardQuality("HIGH");
                preferences.setBoardRenderResolution(.5f);
                preferences.setBoardAntiAliasing("SMAA");
                preferences.setBoardTessellation(true);
                preferences.setBoardNormalMaps(false);
                preferences.setCoordsEnabled(true);
                preferences.setHexTypesEnabled(true);
                inspection.source().refresh();
            });
            camera.setIsometric(false);
            camera.fit(inspection.source().takeFrame().scene());
            awaitImage(stage, preview);
            var atmosphere = (GpuAtmosphere) field(preview, "atmosphere");
            FrameBuffer scene = (FrameBuffer) field(atmosphere, "sceneColor");
            FrameBuffer target = (FrameBuffer) field(preview, "target");
            assertEquals(target.getWidth() / 2, scene.getWidth());
            assertEquals(target.getHeight() / 2, scene.getHeight());
            assertNotNull(field(atmosphere, "smaa"), "SMAA runs on the embedded composite");
            assertNotNull(field(atmosphere, "fsr"), "the embedded image uses the same upscaler as gameplay");
            var terrain = field(preview, "terrain");
            assertEquals(true, field(terrain, "installedTessellation"));
            assertEquals(false, field(terrain, "normalMaps"));
            var tile = inspection.source().takeFrame().scene().tile(new Coords(0, 0));
            assertTrue(tile.text().stream().anyMatch(text -> text.text().equals("0101")));
            var labels = field(preview, "hexText");
            assertFalse(((List<?>) field(labels, "pages")).isEmpty(), "enabled labels reach the GPU");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            UiTestStage.capture("map-embedded-inspection-display", 960, 720).dispose();
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardRenderResolution(1);
                preferences.setBoardAntiAliasing("OFF");
                preferences.setBoardTessellation(false);
                preferences.setCoordsEnabled(false);
                preferences.setHexTypesEnabled(false);
                inspection.source().refresh();
            });
            awaitImage(stage, preview);
            assertEquals(target.getWidth(), ((FrameBuffer) field(atmosphere, "sceneColor")).getWidth());
            assertEquals(false, field(terrain, "installedTessellation"));
            tile = inspection.source().takeFrame().scene().tile(new Coords(0, 0));
            assertTrue(tile.text().isEmpty(), "disabling hex text refreshes the open preview's capture");
            UiTestStage.capture("map-embedded-inspection-top-grid", 960, 720).dispose();
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardQuality(quality);
                preferences.setBoardRenderResolution(resolution);
                preferences.setBoardAntiAliasing(aa);
                preferences.setBoardTessellation(tessellation);
                preferences.setBoardNormalMaps(normals);
                preferences.setCoordsEnabled(coords);
                preferences.setHexTypesEnabled(types);
                inspection.source().refresh();
            });
            awaitImage(stage, preview);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void checkConditions(Stage stage, LobbyBoardPreview preview, BoardCamera camera,
          Inspection inspection) throws Exception {
        var field = LobbyBoardPreview.class.getDeclaredField("atmosphere");
        field.setAccessible(true);
        GpuAtmosphere atmosphere = (GpuAtmosphere) field.get(preview);
        assertEquals(0, atmosphere.lighting().daylight(), .001f, "the first frame uses the lobby's moonlit night");
        float azimuth = camera.azimuth(), tilt = camera.tilt(), zoom = camera.camera.zoom;
        Vector3 focus = camera.focus.cpy();
        Image image = preview.findActor("map-inspection-image");
        var drawable = image.getDrawable();
        var conditions = new PlanetaryConditions();
        conditions.setLight(Light.DAY);
        conditions.setWeather(Weather.HEAVY_RAIN);
        conditions.setFog(Fog.FOG_HEAVY);
        conditions.setWind(Wind.STRONG_GALE);
        conditions.setWindDirection(WindDirection.NORTH);
        SwingUtilities.invokeAndWait(() -> {
            inspection.game().setPlanetaryConditions(conditions);
            inspection.source().refresh();
        });
        var settings = inspection.source().takeFrame().scenarioAtmosphere();
        awaitImage(stage, preview);
        assertSame(atmosphere, field.get(preview), "a conditions update reuses the open renderer");
        assertTrue(atmosphere.lighting().daylight() > .9f);
        var settingsField = GpuAtmosphere.class.getDeclaredField("settings");
        settingsField.setAccessible(true);
        assertEquals(settings, settingsField.get(atmosphere), "fog, precipitation and wind reach the shared renderer");
        var weatherField = GpuAtmosphere.class.getDeclaredField("weatherDrawn");
        weatherField.setAccessible(true);
        assertTrue(weatherField.getBoolean(atmosphere), "the embedded view actually draws the weather layer");
        assertSame(drawable, image.getDrawable(), "conditions do not unload the board image");
        assertEquals(azimuth, camera.azimuth(), .001f);
        assertEquals(tilt, camera.tilt(), .001f);
        assertEquals(zoom, camera.camera.zoom, .001f);
        assertTrue(focus.epsilonEquals(camera.focus, .001f), "conditions preserve the user's camera position");
        UiTestStage.capture("map-embedded-inspection-weather", 960, 720).dispose();
        SwingUtilities.invokeAndWait(() -> {
            inspection.game().setPlanetaryConditions(new PlanetaryConditions());
            inspection.source().refresh();
        });
        awaitImage(stage, preview);
        assertFalse(weatherField.getBoolean(atmosphere), "returning to clear weather removes the precipitation");
    }

    /** Real stage events exercise focus, shared camera buttons, and left-drag inspection. */
    private static BoardCamera checkCameraInput(Stage stage, LobbyBoardPreview preview, UiTheme theme) throws Exception {
        var field = LobbyBoardPreview.class.getDeclaredField("camera");
        field.setAccessible(true);
        BoardCamera camera = (BoardCamera) field.get(preview);
        Image image = preview.findActor("map-inspection-image");
        float openedBearing = camera.azimuth();
        assertTrue(stage.keyDown(Input.Keys.E), "camera keys work before clicking inside a newly opened preview");
        preview.act(.05f);
        stage.keyUp(Input.Keys.E);
        assertNotEquals(openedBearing, camera.azimuth());
        click(stage, preview.findActor("map-view-top"));
        assertEquals(0, camera.tilt(), .001f);
        assertSame(image, stage.getKeyboardFocus());
        click(stage, preview.findActor("map-view-isometric"));
        assertEquals(BoardCamera.ISOMETRIC_TILT, camera.tilt(), .001f);
        float fitZoom = camera.camera.zoom;
        camera.zoom(.5f);
        click(stage, preview.findActor("map-view-fit"));
        assertEquals(fitZoom, camera.camera.zoom, .001f);

        for (int button : new int[] { Input.Buttons.LEFT, Input.Buttons.RIGHT, Input.Buttons.MIDDLE }) {
            for (boolean shift : new boolean[] { false, true }) {
                click(stage, preview.findActor("map-view-isometric"));
                click(stage, preview.findActor("map-view-fit"));
                float azimuth = camera.azimuth();
                Vector3 focus = camera.focus.cpy();
                GpuBoardTestUi.withModifiers(shift ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0,
                      () -> drag(stage, image, button));
                boolean orbit = (button == Input.Buttons.MIDDLE) != shift;
                if (button == Input.Buttons.LEFT) {
                    assertEquals(azimuth, camera.azimuth(), .001f, "left-drag inspects without orbiting");
                    assertTrue(focus.epsilonEquals(camera.focus, .001f), "left-drag inspects without panning");
                } else if (orbit) {
                    assertNotEquals(azimuth, camera.azimuth(), "drag must orbit: button=" + button + ", shift=" + shift);
                } else {
                    assertEquals(azimuth, camera.azimuth(), .001f);
                    assertFalse(focus.epsilonEquals(camera.focus, .001f), "drag must pan: button=" + button + ", shift=" + shift);
                }
            }
        }

        float bearing = camera.azimuth();
        assertTrue(stage.keyDown(Input.Keys.E));
        preview.act(.05f);
        stage.keyUp(Input.Keys.E);
        float normalTurn = camera.azimuth() - bearing;
        assertEquals(4.5f, normalTurn, .001f);
        GpuBoardTestUi.withModifiers(java.awt.event.InputEvent.SHIFT_DOWN_MASK, () -> {
            float before = camera.azimuth();
            assertTrue(stage.keyDown(Input.Keys.E));
            preview.act(.05f);
            stage.keyUp(Input.Keys.E);
            assertEquals(normalTurn * 4, camera.azimuth() - before, .001f);
        });
        Vector3 beforePan = camera.focus.cpy();
        assertTrue(stage.keyDown(Input.Keys.W));
        preview.act(.05f);
        stage.keyUp(Input.Keys.W);
        assertFalse(beforePan.epsilonEquals(camera.focus, .001f));

        TextField search = new TextField("", theme.skin, "input");
        search.setBounds(20, 70, 220, 35);
        stage.addActor(search);
        assertTrue(stage.keyDown(Input.Keys.Q));
        click(stage, search);
        assertSame(search, stage.getKeyboardFocus());
        bearing = camera.azimuth();
        Vector3 still = camera.focus.cpy();
        stage.keyDown(Input.Keys.W);
        stage.keyTyped('w');
        stage.keyDown(Input.Keys.E);
        stage.keyTyped('e');
        preview.act(.1f);
        assertEquals("we", search.getText());
        assertEquals(bearing, camera.azimuth(), .001f, "focus loss clears held rotation even without its key-up");
        assertTrue(still.epsilonEquals(camera.focus, .001f), "typing in another field must not move the camera");
        Vector2 overMap = screen(stage, image, image.getWidth() / 2, image.getHeight() / 2);
        stage.mouseMoved(Math.round(overMap.x), Math.round(overMap.y));
        assertSame(search, stage.getKeyboardFocus(), "hovering alone must not take a field's keyboard focus");
        // Resolve the wheel target before Stage's next hover update, as the lobby's input stage does.
        UiKit.focusScrollAt(stage, Math.round(overMap.x), Math.round(overMap.y));
        float beforeZoom = camera.camera.zoom;
        assertTrue(stage.scrolled(0, -1));
        assertNotEquals(beforeZoom, camera.camera.zoom, "the first wheel tick zooms without a preceding click");
        assertSame(image, stage.getKeyboardFocus(), "wheel zoom also enables camera keys");
        assertSame(image, stage.getScrollFocus());
        bearing = camera.azimuth();
        assertTrue(stage.keyDown(Input.Keys.E));
        preview.act(.05f);
        stage.keyUp(Input.Keys.E);
        assertNotEquals(bearing, camera.azimuth());
        click(stage, search);
        Vector2 overSearch = screen(stage, search, search.getWidth() / 2, search.getHeight() / 2);
        stage.mouseMoved(Math.round(overSearch.x), Math.round(overSearch.y));
        UiKit.focusScrollAt(stage, Math.round(overSearch.x), Math.round(overSearch.y));
        beforeZoom = camera.camera.zoom;
        stage.scrolled(0, -1);
        assertEquals(beforeZoom, camera.camera.zoom, "scrolling elsewhere must not zoom the preview");
        assertSame(search, stage.getKeyboardFocus());
        search.remove();
        click(stage, image);
        assertSame(image, stage.getKeyboardFocus());
        // Even a control which does not claim keyboard focus must release the camera on a click outside it.
        Actor outside = new Actor();
        outside.setBounds(20, 70, 220, 35);
        stage.addActor(outside);
        click(stage, outside);
        assertNull(stage.getKeyboardFocus());
        outside.remove();
        return camera;
    }

    private static void checkHexInspection(Stage stage, LobbyBoardPreview preview, BoardCamera camera,
          Inspection inspection) throws Exception {
        click(stage, preview.findActor("map-view-top"));
        click(stage, preview.findActor("map-view-fit"));
        Image image = preview.findActor("map-inspection-image");
        for (Coords coords : new Coords[] { new Coords(0, 3), new Coords(2, 2) }) {
            float height = inspection.game().getBoard().getHex(coords).getLevel() * BoardGeometry.level();
            Vector3 point = camera.camera.project(BoardGeometry.center(coords, height), 0, 0,
                  camera.camera.viewportWidth, camera.camera.viewportHeight);
            Vector2 at = screen(stage, image, point.x * image.getWidth() / camera.camera.viewportWidth,
                  point.y * image.getHeight() / camera.camera.viewportHeight);
            stage.mouseMoved(Math.round(at.x), Math.round(at.y));
            stage.touchDown(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.LEFT);
            stage.touchUp(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.LEFT);
            SwingUtilities.invokeAndWait(inspection.source()::refresh);
            awaitImage(stage, preview);
            assertEquals(GpuMapSource.hexCard(inspection.game().getBoard().getHex(coords)),
                  inspection.source().takeFrame().tooltip(), "hovering follows the hex without a camera gesture");
            assertNull(inspection.source().takeFrame().context(), "left-click must not pin the hex card");
            assertFalse(inspection.source().takeFrame().panels().los().open(), "plain left-click must not start LOS");
            Table card = preview.findActor("map-hex");
            assertNotNull(card);
            assertTrue(card.hasChildren());
            Vector2 corner = card.localToAscendantCoordinates(preview, new Vector2());
            assertTrue(corner.x > preview.getWidth() / 2, "hex information is in the bottom-right corner");
            assertTrue(corner.y < 30);
            Actor hint = preview.findActor("map-inspection-controls");
            Vector2 hintRight = hint.localToAscendantCoordinates(preview, new Vector2(hint.getWidth(), 0));
            assertTrue(hintRight.x <= corner.x, "camera hints must not run under the hex card");
        }
        UiTestStage.capture("map-embedded-hex-card", 960, 720).dispose();
        stage.mouseMoved(959, 719);
        stage.act(1 / 60f);
        SwingUtilities.invokeAndWait(inspection.source()::refresh);
        awaitImage(stage, preview);
        assertEquals("", inspection.source().takeFrame().tooltip(), "moving off the board clears its hex information");
        assertNull(preview.findActor("map-hex"));
    }

    private static Vector2 screen(Stage stage, Actor actor, float x, float y) {
        return stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(x, y)));
    }

    private static void click(Stage stage, Actor actor) {
        Vector2 at = screen(stage, actor, actor.getWidth() / 2, actor.getHeight() / 2);
        stage.touchDown(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.LEFT);
        stage.touchUp(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.LEFT);
    }

    private static void drag(Stage stage, Image image, int button) {
        Vector2 at = screen(stage, image, image.getWidth() / 2, image.getHeight() / 2);
        stage.touchDown(Math.round(at.x), Math.round(at.y), 0, button);
        stage.touchDragged(Math.round(at.x + 60), Math.round(at.y + 20), 0);
        stage.touchUp(Math.round(at.x + 60), Math.round(at.y + 20), 0, button);
    }

    private record Inspection(Game game, GpuMapSource source) { }

    private static Inspection inspection(Board board, PlanetaryConditions conditions) throws Exception {
        AtomicReference<Inspection> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.getHex(x, y).setCoords(new Coords(x, y)); }
            }
            Game game = new Game();
            game.setPhase(GamePhase.LOUNGE);
            game.setBoard(board);
            game.setPlanetaryConditions(conditions);
            created.set(new Inspection(game, new GpuMapSource(game, null, null)));
        });
        return created.get();
    }

    private static void awaitImage(Stage stage, LobbyBoardPreview preview) throws InterruptedException {
        Image image = preview.findActor("map-inspection-image");
        boolean captured = false;
        long deadline = System.nanoTime() + 120_000_000_000L;
        do {
            ScreenUtils.clear(.05f, .07f, .07f, 1, true);
            stage.act(1 / 60f);
            stage.draw();
            GpuLoadingPanel loading = preview.findActor("board-loading-panel");
            if (loading.isVisible() && !captured && !loading.<Label>findActor("board-loading-ready").getText().isEmpty()) {
                UiTestStage.capture("map-embedded-inspection-loading", 960, 720).dispose();
                captured = true;
            }
            if (image.getDrawable() != null && !loading.isVisible()) {
                Gdx.gl.glFinish();
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertNotNull(image.getDrawable(), "the real terrain renderer must finish the embedded preview");
    }
}
