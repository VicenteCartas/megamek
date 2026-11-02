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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
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
                preview.atmosphere(BoardAtmosphere.fromScenario(conditions, false, .4));
                preview.loading("Inspection test");
                preview.show(scene(board));
                GpuLoadingPanel loading = preview.findActor("board-loading-panel");
                assertTrue(loading.isVisible());
                assertNotNull(loading.findActor("board-loading-grid"));
                assertNotNull(loading.findActor("board-loading-shaders"));
                awaitImage(stage, preview);
                assertFalse(loading.isVisible());
                Image image = preview.findActor("map-inspection-image");
                assertEquals(preview.getWidth(), image.getWidth(), .01f);
                assertEquals(preview.getHeight(), image.getHeight(), .01f);
                assertEquals(0, image.getY(), .01f, "title, controls and hints must overlay the full preview");
                GpuBoardTestUi.assertHorizontalBounds(preview, preview);
                UiTestStage.capture("map-embedded-inspection", 960, 720).dispose();
                BoardCamera camera = checkCameraInput(stage, preview, theme);
                checkConditions(stage, preview, camera, conditions);
                camera.setIsometric(false);
                camera.zoom(.3f);
                camera.pan(90, 60);
                // Most catalogue boards have identical dimensions and the preview scene's board ID is always 0.
                board = Board.createEmptyBoard(6, 5);
                board.getHex(1, 1).setLevel(3);
                BoardSceneryLayouts.convert(board);
                BoardScene replacement = scene(board);
                preview.loading("Another board");
                preview.show(replacement);
                assertTrue(loading.isVisible());
                assertNull(image.getDrawable(), "the previous map must disappear as soon as another is requested");
                var terrainField = LobbyBoardPreview.class.getDeclaredField("terrain");
                terrainField.setAccessible(true);
                GpuTerrain terrain = (GpuTerrain) terrainField.get(preview);
                assertFalse(terrain.ready(replacement), "same-sized maps are replacements, not edits shown over old terrain");
                stage.act(1 / 60f);
                assertTrue(loading.isVisible(), "the loading screen must get a frame before the new build starts");
                assertNull(image.getDrawable());
                awaitImage(stage, preview);
                assertFalse(loading.isVisible());
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

    private static void checkConditions(Stage stage, LobbyBoardPreview preview, BoardCamera camera,
          PlanetaryConditions conditions) throws Exception {
        var field = LobbyBoardPreview.class.getDeclaredField("atmosphere");
        field.setAccessible(true);
        GpuAtmosphere atmosphere = (GpuAtmosphere) field.get(preview);
        assertEquals(0, atmosphere.lighting().daylight(), .001f, "the first frame uses the lobby's moonlit night");
        float azimuth = camera.azimuth(), tilt = camera.tilt(), zoom = camera.camera.zoom;
        Vector3 focus = camera.focus.cpy();
        Image image = preview.findActor("map-inspection-image");
        var drawable = image.getDrawable();
        conditions.setLight(Light.DAY);
        conditions.setWeather(Weather.HEAVY_RAIN);
        conditions.setFog(Fog.FOG_HEAVY);
        conditions.setWind(Wind.STRONG_GALE);
        conditions.setWindDirection(WindDirection.NORTH);
        var settings = BoardAtmosphere.fromScenario(conditions, false, .4);
        preview.atmosphere(settings);
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
        preview.atmosphere(BoardAtmosphere.DEFAULTS);
        awaitImage(stage, preview);
        assertFalse(weatherField.getBoolean(atmosphere), "returning to clear weather removes the precipitation");
    }

    /** Real stage events exercise focus, shared camera buttons and all six mouse gestures. */
    private static BoardCamera checkCameraInput(Stage stage, LobbyBoardPreview preview, UiTheme theme) throws Exception {
        var field = LobbyBoardPreview.class.getDeclaredField("camera");
        field.setAccessible(true);
        BoardCamera camera = (BoardCamera) field.get(preview);
        Image image = preview.findActor("map-inspection-image");
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
                if (orbit) {
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

    private static BoardScene scene(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
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
            if (image.getDrawable() != null) {
                Gdx.gl.glFinish();
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertNotNull(image.getDrawable(), "the real terrain renderer must finish the embedded preview");
    }
}
