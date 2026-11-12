/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.windows.User32;

/**
 * The developer tuning utility (C.1 G17) in the real HUD over a real {@link GpuBoardTuning} model: the utility ends the
 * utility row and opens the panel at its place, one dialog at a time; every control of the model's two pages has its
 * hud-v3 control on the panel, which edits the model, group by group, and shows the model's own changes; the Camera
 * page sets the fixed sun and the board camera's framing; the Display page's UI scale is saved; Defaults restores both
 * pages and the UI scale; "Planetary conditions…" opens the window's native editor.
 */
@Tag("on-demand")
class GpuTuningPanelSmokeTest {
    private static final int[][] SIZES = { { 1920, 1080 }, { 1280, 720 }, { 900, 600 } };
    private static final float NEAR = .0001f;

    /** The real HUD at the harness's size, with a real tuning model over a mocked source and the HUD's camera. */
    private static final class Hud implements AutoCloseable {
        final GpuHudTestStage harness;
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final BoardCamera camera = new BoardCamera();
        final SpriteBatch batch = new SpriteBatch();
        final GpuBoardSource.Frame frame = GpuHudFixtures.frame(
              GpuHudFixtures.status(1, GamePhase.MOVEMENT, false, Entity.NONE, 0),
              GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY,
                    GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
        final GpuBoardTuning tuning;
        final GpuHud hud;

        Hud(GpuHudTestStage harness) {
            this.harness = harness;
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            // A preset previews its own conditions, as GpuBoardSource.preview does with its time sample.
            when(source.preview(any(AtmospherePreset.class)))
                  .thenAnswer(call -> call.<AtmospherePreset>getArgument(0).settings(.5));
            tuning = new GpuBoardTuning(harness.theme.skin, source, camera);
            hud = new GpuHud(source, harness.theme.skin, batch, camera, tuning,
                  new GpuPlaybackHistory(new UnitPlayback()));
            resize();
        }

        /** One stage unit per back-buffer pixel at the harness's window size. */
        void resize() {
            float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
            hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density), 1 / density);
        }

        /** One frame: the snapshots, then a draw, which lays the HUD out. */
        void show() {
            hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudFixtures.preferences());
            ScreenUtils.clear(.1f, .13f, .13f, 1, true);
            hud.draw();
        }

        <T extends Actor> T find(String name) {
            T actor = hud.stage.getRoot().findActor(name);
            assertNotNull(actor, name);
            return actor;
        }

        /**
         * Writes {name}.png once the last click's visual press (Scene2D's 0.1 s) is over, with the panel's texts
         * checked for missing message keys.
         */
        void capture(String name) throws InterruptedException {
            Thread.sleep(150);
            show();
            UiTestStage.assertTexts(find("tuning-panel"));
            harness.capture(name).dispose();
        }

        /** The model's control of that name, on either of its pages. */
        <T extends Actor> T model(String name) {
            T actor = tuning.displayRows().findActor(name);
            if (actor == null) {
                actor = tuning.boardRows().findActor(name);
            }
            if (actor == null) {
                actor = tuning.atmosphereRows().findActor(name);
            }
            if (actor == null) {
                actor = tuning.terrainRows().findActor(name);
            }
            assertNotNull(actor, name);
            return actor;
        }

        /** A left click at the actor's centre through the HUD's stage, once its page shows it; then a frame. */
        void click(Actor actor) {
            // The Tuning utility hangs in the View popover: open it first, as a player would.
            Actor popover = hud.stage.getRoot().findActor("utility-view-menu");
            if (popover != null && actor.isDescendantOf(popover) && !popover.isVisible()) {
                click("utility-view");
            }
            reveal(actor);
            Vector2 point = hud.stage.stageToScreenCoordinates(
                  actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
            hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            show();
        }

        void click(String name) {
            click(find(name));
        }

        /** A press near the right end of the slider, as a drag ends there; then a frame. */
        void pressEnd(Slider slider) {
            reveal(slider);
            Vector2 point = hud.stage.stageToScreenCoordinates(
                  slider.localToStageCoordinates(new Vector2(slider.getWidth() - 1, slider.getHeight() / 2)));
            hud.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            show();
        }

        /** Sets the panel's slider as its drag does (Slider.calculatePositionAndValue), then a frame. */
        void drag(String name, float value) {
            Slider slider = find(name);
            assertFalse(slider.isDisabled(), name + " is enabled");
            slider.setValue(value);
            show();
        }

        /** Opens the panel's choice and clicks the item of that text in its list. */
        void choose(String name, Object item) {
            click(name);
            UiButton row = button(find("tuning-choices"), String.valueOf(item));
            assertNotNull(row, item + " in the list of " + name);
            click(row);
            assertFalse(find("tuning-choices").isVisible(), "a choice closes the list");
        }

        /** Whether the HUD takes a press at the stage point (y up), as GpuBattleView asks before the board. */
        boolean hits(float x, float y) {
            Vector2 screen = hud.stage.stageToScreenCoordinates(new Vector2(x, y));
            return hud.hit(Math.round(screen.x), Math.round(screen.y));
        }

        void key(KeyCommandBind bind) {
            hud.keyDown(Input.Keys.UNKNOWN, bind.keyDefault, bind.modifiersDefault);
            hud.keyUp(Input.Keys.UNKNOWN, bind.keyDefault);
            show();
        }

        /** Scrolls the page that holds the actor so that the actor shows. */
        private void reveal(Actor actor) {
            // Dynamic notes can change the wrapped page height; settle layout before deriving scroll coordinates.
            show();
            for (Group parent = actor.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof ScrollPane page) {
                    Vector2 corner = actor.localToAscendantCoordinates(page.getActor(), new Vector2());
                    // Keep click targets away from the scroll viewport's clipped edge on long pages.
                    page.scrollTo(corner.x, corner.y, actor.getWidth(), actor.getHeight(), false, true);
                    page.updateVisualScroll();
                    show();
                    return;
                }
            }
        }

        @Override
        public void close() {
            // Geometry and family sizes are global: Defaults puts them back for the next test.
            fire(tuning.defaults());
            hud.dispose();
            batch.dispose();
        }
    }

    @Test
    void clickedSlidersTakeArrowKeysUntilAnotherControlOrTheBoardIsClicked() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show();
                hud.click("tuning-button");
                hud.click("tuning-board-tab");
                Slider slider = hud.find("Hex scale");
                hud.click(slider);
                assertSame(slider, hud.hud.stage.getKeyboardFocus());
                float initial = slider.getValue();
                for (int[] key : new int[][] {
                      { Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 1 }, { Input.Keys.LEFT, KeyEvent.VK_LEFT, -1 },
                      { Input.Keys.UP, KeyEvent.VK_UP, 1 }, { Input.Keys.DOWN, KeyEvent.VK_DOWN, -1 } }) {
                    float previous = slider.getValue();
                    assertTrue(hud.hud.keyDown(key[0], key[1], 0), "The slider consumes the camera's arrow key");
                    assertTrue(hud.hud.keyUp(key[0], key[1]));
                    hud.show();
                    assertEquals(previous + key[2] * slider.getStepSize(), slider.getValue(), NEAR);
                    assertEquals(slider.getValue(), hud.<Slider>model("Hex scale").getValue(), NEAR);
                }
                assertEquals(initial, slider.getValue(), NEAR);

                Slider other = hud.find("Unit scale");
                hud.click(other);
                assertSame(other, hud.hud.stage.getKeyboardFocus());
                hud.click("tuning-fps");
                assertFalse(slider.hasKeyboardFocus());
                assertFalse(other.hasKeyboardFocus());
                hud.click(slider);
                hud.hud.stage.touchDown(20, 300, 0, Input.Buttons.LEFT);
                hud.hud.stage.touchUp(20, 300, 0, Input.Buttons.LEFT);
                assertNull(hud.hud.stage.getKeyboardFocus(), "A board click releases the slider");
                assertFalse(hud.hud.stage.keyDown(Input.Keys.RIGHT));
                assertEquals(initial, slider.getValue(), NEAR);

                hud.click(slider);
                hud.click("tuning-atmosphere-tab");
                assertNull(hud.hud.stage.getKeyboardFocus(), "The old tab must not retain its slider's focus");
                Slider clouds = hud.find("Cloud cover");
                hud.click(clouds);
                assertSame(clouds, hud.hud.stage.getKeyboardFocus());
                hud.click("tuning-close");
                assertNull(hud.hud.stage.getKeyboardFocus());
            }
        });
    }

    @Test
    void heldArrowsRespectBoundsAndReleaseFocusWhenDisabledHiddenOrRemoved() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show();
                hud.click("tuning-button");
                hud.click("tuning-board-tab");
                Slider slider = hud.find("Hex scale");
                Slider.SliderStyle normal = slider.getStyle();
                int listeners = hud.hud.stage.getRoot().getCaptureListeners().size;
                hud.click(slider);
                assertSame(normal.knobOver, slider.getStyle().knob, "Keyboard focus highlights the handle");
                Input original = Gdx.input;
                Input keys = mock(Input.class);
                when(keys.isKeyPressed(Input.Keys.RIGHT)).thenReturn(true);
                try {
                    Gdx.input = keys;
                    float initial = slider.getValue();
                    float step = slider.getStepSize();
                    assertTrue(hud.hud.keyDown(Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 0));
                    assertEquals(initial + step, slider.getValue(), NEAR);
                    hud.hud.stage.act(.15f);
                    assertEquals(initial + step, slider.getValue(), NEAR, "Held keys wait before repeating");
                    hud.hud.stage.act(.16f);
                    assertEquals(initial + 2 * step, slider.getValue(), NEAR);
                    hud.hud.stage.act(.05f);
                    assertEquals(initial + 3 * step, slider.getValue(), NEAR);
                    assertTrue(hud.hud.keyUp(Input.Keys.RIGHT, KeyEvent.VK_RIGHT));
                    hud.hud.stage.act(1);
                    assertEquals(initial + 3 * step, slider.getValue(), NEAR, "Release stops repetition");

                    slider.setValue(slider.getMaxValue());
                    assertTrue(hud.hud.keyDown(Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 0));
                    hud.hud.stage.act(.5f);
                    hud.hud.keyUp(Input.Keys.RIGHT, KeyEvent.VK_RIGHT);
                    assertEquals(slider.getMaxValue(), slider.getValue(), NEAR);
                    slider.setValue(slider.getMinValue());
                    assertTrue(hud.hud.keyDown(Input.Keys.LEFT, KeyEvent.VK_LEFT, 0));
                    hud.hud.keyUp(Input.Keys.LEFT, KeyEvent.VK_LEFT);
                    assertEquals(slider.getMinValue(), slider.getValue(), NEAR);

                    hud.hud.keyDown(Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 0);
                    float beforeBlur = slider.getValue();
                    hud.<Slider>model("Hex scale").setDisabled(true);
                    hud.show();
                    assertNull(hud.hud.stage.getKeyboardFocus());
                    hud.hud.stage.act(1);
                    assertEquals(beforeBlur, slider.getValue(), NEAR);
                    hud.click(slider);
                    assertNull(hud.hud.stage.getKeyboardFocus(), "Disabled sliders cannot take focus");
                    hud.hud.keyUp(Input.Keys.RIGHT, KeyEvent.VK_RIGHT);
                    assertSame(normal, slider.getStyle());
                    assertEquals(listeners, hud.hud.stage.getRoot().getCaptureListeners().size);

                    hud.<Slider>model("Hex scale").setDisabled(false);
                    hud.click(slider);
                    hud.hud.keyDown(Input.Keys.RIGHT, KeyEvent.VK_RIGHT, 0);
                    beforeBlur = slider.getValue();
                    hud.find("tuning-panel").setVisible(false);
                    hud.hud.stage.act(1);
                    assertNull(hud.hud.stage.getKeyboardFocus(), "Hidden panels release slider focus");
                    assertEquals(beforeBlur, slider.getValue(), NEAR);
                    hud.hud.keyUp(Input.Keys.RIGHT, KeyEvent.VK_RIGHT);
                    assertSame(normal, slider.getStyle());

                    hud.show();
                    hud.click(slider);
                    slider.remove();
                    assertNull(hud.hud.stage.getKeyboardFocus());
                    assertEquals(listeners, hud.hud.stage.getRoot().getCaptureListeners().size,
                          "Removing a slider removes its stage listener too");
                } finally { Gdx.input = original; }
            }
        });
    }

    @Test
    void assetReloadProgressStaysLiveWithoutNewBoardSnapshots() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                for (int[] size : SIZES) {
                    harness.size(size[0], size[1]);
                    hud.resize();
                    hud.show();
                    if (!hud.find("tuning-panel").isVisible()) { hud.click("tuning-button"); }
                    hud.click("tuning-reload-assets");
                    assertTrue(hud.tuning.takeAssetReloadRequest());
                    assertFalse(hud.tuning.takeAssetReloadRequest());
                    assertTrue(hud.<UiButton>find("tuning-reload-assets").isDisabled());
                    assertTrue(hud.find("tuning-reload-progress").isVisible());
                    assertEquals(-1, hud.tuning.assetReloadFraction());

                    Path file = Path.of("models/board/textures/terrain/surfaces/grassland-ground_albedo.png");
                    hud.tuning.assetSyncProgress(new GpuAssetSync.Progress("Checking assets", 50, 100, 3, file));
                    // During sync GpuBattleView only draws the HUD; there is no fresh ui.update(frame, ...).
                    hud.hud.draw();
                    assertEquals("50 / 100 files · 3 copied", hud.<Label>find("tuning-reload-count").getText().toString());
                    assertEquals(file.toString(), hud.<Label>find("tuning-reload-file").getText().toString());
                    assertEquals(.5f, hud.tuning.assetReloadFraction());
                    hud.capture("tuning-asset-progress-" + size[0] + "x" + size[1]);
                    GpuBoardTestUi.assertHorizontalBounds(hud.find("tuning-frame"), hud.find("tuning-frame"));

                    hud.tuning.assetReloadStage("Reloading artwork");
                    hud.hud.draw();
                    assertEquals(-1, hud.tuning.assetReloadFraction(), "An unknown stage must not claim 100%");
                    assertEquals("", hud.<Label>find("tuning-reload-file").getText().toString());
                    hud.tuning.assetReloadFinished(false);
                    hud.hud.draw();
                    assertNull(hud.hud.stage.getRoot().findActor("tuning-reload-progress"));
                    assertFalse(hud.<UiButton>find("tuning-reload-assets").isDisabled());

                    hud.click("tuning-reload-assets");
                    assertTrue(hud.tuning.takeAssetReloadRequest(), "Failure allows a retry");
                    hud.tuning.assetSyncProgress(new GpuAssetSync.Progress("Checking assets", 0, 0, 0, null));
                    hud.hud.draw();
                    assertEquals("0 / 0 files · 0 copied", hud.<Label>find("tuning-reload-count").getText().toString());
                    assertTrue(Float.isFinite(hud.tuning.assetReloadFraction()));
                    hud.tuning.assetReloadFinished(true);
                    hud.hud.draw();
                    assertNull(hud.hud.stage.getRoot().findActor("tuning-reload-progress"));
                    assertEquals("OK", hud.tuning.assetReloadStatus().getText().toString());
                }
            }
        });
    }

    @Test
    void theDisplayShowsLiveTerrainCacheStatusAndSwitchesItForAB() {
        GpuHudTestStage.run(harness -> {
            for (int[] size : SIZES) {
                harness.size(size[0], size[1]);
                try (Hud hud = new Hud(harness)) {
                    var status = new AtomicReference<>(new GpuMaterialCache.Status("Rebuilding stale pages", true,
                          260L << 20, 1L << 20, new GpuPageCache.Status(256, 150, 25, 1, 100, 75, 25, 12),
                          2, 120, 100, 0, 4, 2, 4, 1, "Driver memory budget (256 MiB)"));
                    hud.tuning.terrainCacheStatus(status::get);
                    hud.show();
                    hud.click("tuning-button");
                    Label state = hud.find("tuning-terrain-cache-state");
                    Label details = hud.find("tuning-terrain-cache-details");
                    assertFalse(hud.tuning.terrainCacheDebug(), "Each new view starts with Debug off");
                    Actor cache = hud.find("tuning-terrain-cache");
                    Actor debug = hud.find("tuning-terrain-cache-debug");
                    assertEquals(cache.localToStageCoordinates(new Vector2()).y,
                          debug.localToStageCoordinates(new Vector2()).y, 1, "Debug is inline with Enable terrain cache");
                    assertTrue(debug.getX() > cache.getX() + cache.getWidth(), "The two checkboxes do not overlap");
                    assertEquals(((Group) cache).getChildren().first().getWidth(),
                          ((Group) debug).getChildren().first().getWidth(), .01f,
                          "The reading column must not squeeze Debug's checkbox icon");
                    hud.click(debug);
                    assertTrue(hud.tuning.terrainCacheDebug());
                    assertTrue(hud.<Label>find("tuning-terrain-cache-legend").getText().toString().contains("red = direct"));
                    assertTrue(state.getText().toString().contains("75% (75 / 100 pages current)"));
                    assertTrue(details.getText().toString().contains("260.0 MiB"));
                    assertTrue(details.getText().toString().contains("stale: 25"));
                    assertTrue(details.getText().toString().contains("Capacity: Driver memory budget (256 MiB)"));
                    hud.reveal(details);
                    hud.capture("tuning-cache-building-" + size[0]);
                    GpuBoardTestUi.assertHorizontalBounds(hud.find("tuning-frame"), hud.find("tuning-frame"));

                    assertTrue(hud.tuning.terrainCache());
                    hud.click("tuning-terrain-cache");
                    assertFalse(hud.tuning.terrainCache(), "The visible checkbox drives the renderer's setting");
                    assertTrue(hud.tuning.terrainCacheDebug(), "Debug remains usable with the cache disabled");
                    hud.click("tuning-defaults");
                    assertFalse(hud.tuning.terrainCache(), "Defaults leaves Display settings alone");
                    status.set(new GpuMaterialCache.Status("Disabled - direct rendering", false,
                          260L << 20, 1L << 20, status.get().pages(), 2, 0, 0, 0, 0, 0, 0, 0, "Configured budget"));
                    hud.hud.draw(); // No board snapshot: live diagnostics still refresh.
                    assertEquals("Disabled - direct rendering", state.getText().toString());
                    assertTrue(details.getText().toString().contains("260.0 MiB"), "Disabled retains its allocation");
                    assertFalse(details.getText().toString().contains("View:"));
                    hud.click("tuning-terrain-cache");
                    assertTrue(hud.tuning.terrainCache());

                    status.set(new GpuMaterialCache.Status("Ready - partial coverage", true,
                          260L << 20, 1L << 20, new GpuPageCache.Status(256, 150, 0, 0, 100, 100, 0, 12),
                          2, 120, 100, 0, 4, 0, 4, 0, "Configured budget"));
                    hud.hud.draw();
                    assertTrue(state.getText().toString().contains("100%"));
                    assertTrue(details.getText().toString().contains("100 / 120 pages admitted"));
                    assertTrue(details.getText().toString().contains("tops 0 / 4 · walls 0 / 4"),
                          "Draw coverage remains distinct from progress through the admitted pages");
                    hud.reveal(details);
                    hud.capture("tuning-cache-ready-" + size[0]);

                    status.set(new GpuMaterialCache.Status("Not allocated", false, 0, 1L << 20,
                          new GpuPageCache.Status(0, 0, 0, 0, 0, 0, 0, 0), 0, 0, 0, 0, 0, 0, 0, 0, "Not allocated"));
                    hud.hud.draw();
                    assertEquals("Not allocated", state.getText().toString());
                    assertFalse(state.getText().toString().contains("%"), "No invented percentage without a view");
                    assertTrue(details.getText().toString().contains("0.0 MiB"));
                }
            }
        });
    }

    @Test
    void theUtilityHangsInTheViewPopoverAndOpensThePanelAtItsPlaceOneDialogAtATime() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                for (int[] size : SIZES) {
                    harness.size(size[0], size[1]);
                    hud.resize();
                    hud.show();
                    GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                    String at = size[0] + " x " + size[1];
                    UiButton utility = hud.find("tuning-button");
                    Rectangle menu = GpuHudTestStage.bounds(hud.find("utility-menu"));
                    Rectangle view = GpuHudTestStage.bounds(hud.find("utility-view"));
                    assertEquals(size[0] - metrics.gap(), menu.x + menu.width, .5f, "Menu is last at the right gap " + at);
                    assertNull(hud.hud.stage.getRoot().findActor("utility-help"), "Help is a page of the Menu " + at);
                    assertEquals("", hud.<UiButton>find("utility-menu").getText().toString());
                    Rectangle phase = GpuHudTestStage.bounds(hud.find("phase-header"));
                    assertFalse(phase.overlaps(GpuHudTestStage.bounds(hud.find("utility-bar"))),
                          "The camera controls must stay clear of the phase header " + at);
                    assertEquals(menu.y, view.y, .5f, "on the row's line " + at);
                    assertFalse(hud.find("utility-view-menu").isVisible(), "the popover is closed at first");
                    assertFalse(hud.find("tuning-panel").isVisible(), "closed at first");

                    hud.click(utility);
                    assertSame(GpuHudState.Dialog.TUNING, hud.hud.state.dialog);
                    assertTrue(utility.isChecked(), "the utility is pressed while the panel is open");
                    assertFalse(hud.find("utility-view-menu").isVisible(), "the panel replaces the popover");
                    // The panel keeps its last page when it closes, so it opens on Display only the first time.
                    if (size == SIZES[0]) { assertTrue(hud.find("tuning-display").isVisible(), "the Display page is the first"); }
                    hud.click("tuning-board-tab");
                    Rectangle panel = GpuHudTestStage.bounds(hud.find("tuning-frame"));
                    assertEquals(size[0] - metrics.gap(), panel.x + panel.width, .5f, "at the right gap " + at);
                    assertEquals(360, panel.width, .5f, at);
                    assertEquals(metrics.gap(), view.y - panel.y - panel.height, .5f,
                          "one gap below the utilities " + at);
                    assertEquals(70, panel.y, .5f, "the long Board page fills down to the bottom margin " + at);
                    assertTrue(hud.<ScrollPane>find("tuning-board").isScrollY(), "and scrolls " + at);
                    assertTrue(hud.hits(panel.x + 3, panel.y + panel.height - 3), "the panel takes its presses");
                    assertFalse(hud.hits(size[0] / 2f, size[1] / 2f), "presses beside it reach the board");
                    for (String page : List.of("tuning-display", "tuning-board", "tuning-atmosphere", "tuning-camera")) {
                        hud.click(page + "-tab");
                        GpuBoardTestUi.assertHorizontalBounds(hud.find("tuning-frame"), hud.find("tuning-frame"));
                    }
                    hud.click("tuning-board-tab");
                    hud.capture("tuning-hud-" + size[0] + "x" + size[1]);
                    String fps = hud.<Label>find("tuning-fps").getText().toString();
                    assertTrue(fps.matches("\\d+ fps"), "The frame rate beside the title: " + fps + " " + at);

                    hud.key(KeyCommandBind.KEY_BINDS);
                    assertSame(GpuHudState.Dialog.HELP, hud.hud.state.dialog, "Help replaces the panel " + at);
                    assertFalse(hud.find("tuning-panel").isVisible());
                    assertFalse(utility.isChecked());
                    // Help is modal: a press outside it, on the View utility too, only dismisses it (UiModal).
                    hud.click("utility-view");
                    assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog, "a press beside Help closes it " + at);
                    assertFalse(hud.find("utility-view-menu").isVisible(), "and opens nothing " + at);
                    hud.click(utility);
                    assertSame(GpuHudState.Dialog.TUNING, hud.hud.state.dialog, "then the utility opens it " + at);
                    hud.key(KeyCommandBind.CANCEL);
                    assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog, "Esc closes the panel " + at);
                    assertFalse(hud.find("tuning-panel").isVisible());
                    hud.click(utility);
                    hud.click("tuning-close");
                    assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog, "the close button closes it " + at);
                }
            }
        });
    }

    @Test
    void theUiScaleShowsTheSavedShareIsSavedOnceSetAndDefaultsLeavesIt() {
        GUIPreferences preferences = GUIPreferences.getInstance();
        float saved = preferences.getBoardUiScale();
        try {
            preferences.setBoardUiScale(.8f);
            GpuHudTestStage.run(harness -> {
                try (Hud hud = new Hud(harness)) {
                    hud.show();
                    hud.click("tuning-button");
                    Slider slider = hud.find("tuning-ui-scale");
                    assertEquals(50, slider.getMinValue(), NEAR);
                    assertEquals(100, slider.getMaxValue(), NEAR, "only a reduction of the window's own scale");
                    assertEquals(5, slider.getStepSize(), NEAR);
                    assertEquals("80%", reading(hud, "tuning-ui-scale"), "the saved share");
                    hud.drag("tuning-ui-scale", 65);
                    assertEquals("65%", reading(hud, "tuning-ui-scale"));
                    // The board view saves it each frame; the source publishes the saved share to the layout.
                    hud.tuning.saveUiScale();
                    SwingUtilities.invokeAndWait(() -> { });
                    assertEquals(.65f, preferences.getBoardUiScale(), NEAR, "saved");
                    hud.click("tuning-defaults");
                    assertEquals("65%", reading(hud, "tuning-ui-scale"), "Defaults leaves the Display settings");
                    hud.tuning.saveUiScale();
                    SwingUtilities.invokeAndWait(() -> { });
                    assertEquals(.65f, preferences.getBoardUiScale(), NEAR);
                }
            });
        } finally {
            preferences.setBoardUiScale(saved);
        }
    }

    @Test
    void theRenderResolutionShowsTheSavedShareIsSavedAndDefaultsLeavesIt() {
        GUIPreferences preferences = GUIPreferences.getInstance();
        float saved = preferences.getBoardRenderResolution();
        try {
            preferences.setBoardRenderResolution(.7f);
            GpuHudTestStage.run(harness -> {
                try (Hud hud = new Hud(harness)) {
                    hud.show();
                    hud.click("tuning-button");
                    assertEquals("70%", reading(hud, "tuning-render-resolution"), "the saved share");
                    assertEquals(.7f, BoardCamera.renderScale, NEAR);
                    hud.drag("tuning-render-resolution", 50);
                    SwingUtilities.invokeAndWait(() -> { });
                    assertEquals(.5f, preferences.getBoardRenderResolution(), NEAR, "saved");
                    hud.click("tuning-defaults");
                    SwingUtilities.invokeAndWait(() -> { });
                    assertEquals("50%", reading(hud, "tuning-render-resolution"), "Defaults leaves the Display settings");
                    assertEquals(.5f, preferences.getBoardRenderResolution(), NEAR);
                }
            });
        } finally {
            preferences.setBoardRenderResolution(saved);
            BoardCamera.renderScale = 1;
        }
    }

    /**
     * In the map preview or the board editor: Swing's GUI scale keys (Ctrl+NumPad +/-, and Ctrl+0) change neither
     * Swing's GUI scale nor the 3D UI scale. Then the path a user takes, from Windows mouse messages through the
     * window's own event loop: the Tuning utility, then a drag on the Display page's UI scale. While the button is held
     * the HUD keeps its layout; once it is released the HUD lays out at that share of the scale it had, also in a
     * window whose size limits that scale (the default 1280 x 800 on a high-DPI display).
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    @EnabledOnOs(OS.WINDOWS)
    void theGuiScaleKeysDoNothingAndAMouseDragOnTheUiScaleRescalesTheHudOnRelease(boolean editing) throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        float saved = preferences.getBoardUiScale();
        float guiScale = preferences.getGUIScale();
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            preferences.setBoardUiScale(1);
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(9, 9));
            return new GpuMapSource(game, null, editor, () -> { }, () -> { });
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                private GpuBattleView view;
                private long window;
                private int frame;
                private float full;
                private Slider slider;

                @Override
                public void create() {
                    view = new GpuBattleView(source);
                    view.create();
                    GpuBoardTestUi.present(view);
                    window = GLFWNativeWin32.glfwGetWin32Window(
                          ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                }

                @Override
                public void resize(int width, int height) {
                    view.resize(width, height);
                }

                /** The window's own loop: its events are polled, then each frame takes the next step. */
                @Override
                public void render() {
                    try {
                        view.render();
                        Stage stage = GpuBoardTestUi.stage();
                        int step = frame++;
                        switch (step) {
                            case 0 -> {
                                full = stage.getWidth();
                                GpuBoardTestUi.press(KeyCommandBind.INC_GUI_SCALE);
                                GpuBoardTestUi.press(KeyCommandBind.DEC_GUI_SCALE);
                                GpuBoardTestUi.press(KeyCommandBind.INC_GUI_SCALE);
                                GpuBoardTestUi.withModifiers(InputEvent.CTRL_DOWN_MASK, () -> {
                                    Gdx.input.getInputProcessor().keyDown(Input.Keys.NUM_0);
                                    Gdx.input.getInputProcessor().keyUp(Input.Keys.NUM_0);
                                });
                            }
                            case 2 -> {
                                // Any change they made would have reached the Swing thread by now.
                                SwingUtilities.invokeAndWait(() -> { });
                                assertEquals(guiScale, preferences.getGUIScale(), "Swing's GUI scale is untouched");
                                assertEquals(1, preferences.getBoardUiScale(), "so is the 3D UI scale");
                                assertEquals(full, stage.getWidth(), .5f, "and the HUD's layout");
                                Actor popover = stage.getRoot().findActor("utility-view-menu");
                                if (popover != null && !popover.isVisible()) {
                                    stage.getRoot().findActor("utility-view").fire(new ChangeListener.ChangeEvent());
                                }
                                Actor tune = stage.getRoot().findActor("tuning-button");
                                Vector2 at = windowPoint(stage, tune, tune.getWidth() / 2, tune.getHeight() / 2);
                                mouse(User32.WM_MOUSEMOVE, 0, at);
                                mouse(User32.WM_LBUTTONDOWN, User32.MK_LBUTTON, at);
                                mouse(User32.WM_LBUTTONUP, 0, at);
                            }
                            case 6 -> {
                                slider = stage.getRoot().findActor("tuning-ui-scale");
                                assertTrue(GpuBoardTestUi.shown(slider), "the Display page shows the UI scale");
                                float middle = slider.getHeight() / 2;
                                Vector2 start = windowPoint(stage, slider, slider.getWidth() - 1, middle);
                                mouse(User32.WM_MOUSEMOVE, 0, start);
                                mouse(User32.WM_LBUTTONDOWN, User32.MK_LBUTTON, start);
                                mouse(User32.WM_MOUSEMOVE, User32.MK_LBUTTON, windowPoint(stage, slider, 0, middle));
                            }
                            case 10 -> {
                                assertTrue(Gdx.input.isTouched(), "the button is held");
                                assertEquals(50, slider.getValue(), NEAR, "the drag reached the minimum");
                                assertEquals(full, stage.getWidth(), .5f, "the layout waits for the release");
                                mouse(User32.WM_LBUTTONUP, 0, windowPoint(stage, slider, 0, slider.getHeight() / 2));
                            }
                            default -> {
                                // Saved on release, the share reaches the layout through the Swing thread.
                                if (step > 10 && (stage.getWidth() > full + 1 || step > 120)) {
                                    assertFalse(Gdx.input.isTouched(), "the button is released");
                                    assertEquals(.5f, preferences.getBoardUiScale(), NEAR, "the share is saved");
                                    assertEquals(full / .5f, stage.getWidth(), 1, "the HUD lays out at half its scale");
                                    assertEquals(guiScale, preferences.getGUIScale(), "Swing's GUI scale is untouched");
                                    Gdx.app.exit();
                                }
                            }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    view.dispose();
                }

                /** Posts a mouse message at the window point, which the window's next event poll delivers. */
                private void mouse(int message, int buttons, Vector2 at) {
                    long position = ((long) Math.round(at.y) << 16) | (Math.round(at.x) & 0xFFFF);
                    assertTrue(User32.PostMessage(window, message, buttons, position), "the window takes the message");
                }
            }, GpuBoardWindow.configuration(false));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                source.close();
                preferences.setBoardUiScale(saved);
            });
        }
        if (failure.get() != null) {
            throw new AssertionError(editing ? "UI scale in the editor" : "UI scale in the map preview", failure.get());
        }
    }

    /** The window point (y down) of the point (x, y) of {@code actor}. */
    private static Vector2 windowPoint(Stage stage, Actor actor, float x, float y) {
        return stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(x, y)));
    }

    @Test
    void everyModelControlHasItsControlAndTheBoardPageEditsTheModel() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show();
                hud.click("tuning-button");
                // Every named control of both model pages, on the page's tab, and Defaults, by name and kind.
                List<Actor> display = controls(hud.tuning.displayRows());
                List<Actor> board = controls(hud.tuning.boardRows());
                List<Actor> atmosphere = controls(hud.tuning.atmosphereRows());
                hud.click("tuning-atmosphere-tab");
                assertMirrored(hud, atmosphere);
                hud.click("tuning-display-tab");
                assertMirrored(hud, display);
                hud.click("tuning-board-tab");
                assertMirrored(hud, board);
                assertMirrored(hud, List.of(hud.tuning.defaults()));
                List<Actor> every = new ArrayList<>(display);
                every.addAll(board);
                every.addAll(atmosphere);
                assertEquals(every.size(), every.stream().map(Actor::getName).distinct().count(),
                      "the model's control names are unique");
                assertTrue(every.size() > 60, "the model's " + every.size() + " controls");

                // Geometry: a press at the slider's end and drags.
                hud.pressEnd(hud.find("Hex scale"));
                assertEquals(3, BoardGeometry.tuning().hexScale(), NEAR, "Hex scale's maximum");
                assertEquals("3.00", reading(hud, "Hex scale"), "the reading is the model's");
                hud.drag("Unit scale", 1.5f);
                hud.drag("Unit height scale", 1.2f);
                hud.drag("Base level height", 24);
                hud.drag("Hex frame shade", .5f);
                hud.drag("Multi-hex unit scale", .75f);
                BoardGeometry.Tuning geometry = BoardGeometry.tuning();
                assertEquals(1.5f, geometry.unitScale(), NEAR);
                assertEquals(1.2f, geometry.unitHeightScale(), NEAR);
                assertEquals(24, geometry.levelHeight());
                assertEquals(.5f, geometry.gridShade(), NEAR);
                assertEquals(.75f, geometry.multiHexUnitScale(), NEAR);

                // VSync, on the Display page.
                hud.click("tuning-display-tab");
                boolean vsync = hud.<CheckBox>model("tuning-vsync").isChecked();
                hud.click("tuning-vsync");
                assertEquals(!vsync, hud.<CheckBox>model("tuning-vsync").isChecked(), "VSync switched");
                hud.click("tuning-board-tab");

                // Unit family sizes.
                for (UnitFamilyScale family : UnitFamilyScale.values()) {
                    hud.drag("tuning-size-" + family.name(), 1.5f);
                    assertEquals(1.5f, family.UNIT_SCALE, NEAR, family.label);
                }

                // Zoom-out unit scaling: the panel's controls set the growth the 3D view takes on
                // its next frame, from the constants.
                UiKit.Checkbox scaling = hud.find("tuning-zoom-scaling");
                assertEquals(UnitScreenScale.ENABLED, scaling.isTicked(), "the switch starts at its constant");
                assertEquals(String.valueOf(Math.round(UnitScreenScale.THRESHOLD)), reading(hud, "Threshold hex px"));
                if (!scaling.isTicked()) {
                    hud.click(scaling);
                }
                hud.drag("Threshold hex px", 120);
                hud.drag("Max zoom-out scale", 1.5f);
                assertEquals(1, UnitScreenScale.factor(120), NEAR, "from a hex 120 HUD pixels wide");
                assertEquals(1.2f, UnitScreenScale.factor(100), NEAR, "units grow as the hex shrinks");
                assertEquals(1.5f, UnitScreenScale.factor(60), NEAR, "up to the maximum");
                assertEquals("120", reading(hud, "Threshold hex px"), "the reading is the model's");
                hud.click(scaling);
                assertFalse(UnitScreenScale.enabled);
                assertEquals(1, UnitScreenScale.factor(60), NEAR, "switched off, units keep their size");
                assertTrue(hud.<Slider>find("Threshold hex px").isDisabled(), "its values rest while it is off");
                hud.click(scaling);
                assertEquals(1.5f, UnitScreenScale.factor(60), NEAR, "and come back on");
                hud.capture("tuning-zoom-scaling");

                // The route pulse (user item 55): the panel's switch and sliders set the values the overlay's pulse
                // takes on its next frame, from the constants.
                UiKit.Checkbox pulse = hud.find("tuning-route-pulse");
                assertEquals(GpuRoutePulse.ENABLED, pulse.isTicked(), "the pulse's switch starts at its constant");
                if (!pulse.isTicked()) {
                    hud.click(pulse);
                }
                hud.drag("Pulse speed", 2);
                hud.drag("Pulse intensity", .5f);
                assertEquals(2, GpuRoutePulse.speed, NEAR);
                assertEquals(.5f, GpuRoutePulse.intensity, NEAR);
                assertEquals("2.00", reading(hud, "Pulse speed"), "the reading is the model's");
                hud.click(pulse);
                assertFalse(GpuRoutePulse.enabled, "switched off");
                assertTrue(hud.<Slider>find("Pulse intensity").isDisabled(), "its values rest while it is off");
                hud.click(pulse);
                assertTrue(GpuRoutePulse.enabled, "and back on");
                hud.capture("tuning-route-pulse");

                // Unit visibility.
                hud.drag("Building opacity", 40);
                hud.drag("See-through", 25);
                assertEquals(.4f, hud.tuning.buildingOpacity(), NEAR);
                assertEquals(.25f, hud.tuning.seeThrough(), NEAR);

                // Outside field of view and sensor range: the modes are segments of one choice.
                for (GpuFieldOfView.Style style : GpuFieldOfView.Style.values()) {
                    hud.click("fov-style-" + style.name());
                    hud.click("sensor-style-" + style.name());
                    assertSame(style, hud.tuning.fovStyle());
                    assertSame(style, hud.tuning.sensorStyle());
                    for (GpuFieldOfView.Style other : GpuFieldOfView.Style.values()) {
                        assertEquals(other == style, hud.<UiButton>find("fov-style-" + other.name()).isChecked(),
                              "only the chosen mode is pressed");
                    }
                }
                hud.drag("FoV darkness", 30);
                hud.drag("Sensor darkness", 70);
                assertEquals(.3f, hud.tuning.fovDarkness(), NEAR);
                assertEquals(.7f, hud.tuning.sensorDarkness(), NEAR);

                // Unit damage: the preview's slider and location follow the override.
                assertTrue(hud.<Slider>find("Display damage").isDisabled(), "no damage preview without override");
                assertTrue(hud.<UiButton>find("tuning-damage-location").isDisabled());
                hud.click("tuning-override-damage");
                hud.drag("Display damage", .6f);
                assertEquals(.6f, hud.tuning.damageOverride(), NEAR);
                hud.choose("tuning-damage-location", UnitDamageDisplay.Location.LEFT_ARM);
                assertSame(UnitDamageDisplay.Location.LEFT_ARM, hud.tuning.damageLocation());
                assertEquals("Left arm", hud.<UiButton>find("tuning-damage-location").getText().toString());
                hud.click("tuning-override-damage");
                assertEquals(-1, hud.tuning.damageOverride(), "override off");
                hud.capture("tuning-board-edited");

                // Defaults restores the Board page and preserves the saved Display choices.
                boolean normalMaps = hud.tuning.normalMaps(), parallax = hud.tuning.terrainParallax();
                hud.click("tuning-defaults");
                assertEquals(1, BoardGeometry.tuning().hexScale(), NEAR);
                assertEquals(normalMaps, hud.tuning.normalMaps());
                assertEquals(parallax, hud.tuning.terrainParallax());
                for (UnitFamilyScale family : UnitFamilyScale.values()) {
                    assertEquals(family.defaultUnitScale, family.UNIT_SCALE, NEAR, family.label);
                }
                assertEquals(UnitScreenScale.ENABLED, UnitScreenScale.enabled, "the zoom-out scaling's constants");
                assertEquals(UnitScreenScale.THRESHOLD, UnitScreenScale.threshold, NEAR);
                assertEquals(UnitScreenScale.MAX, UnitScreenScale.max, NEAR);
                assertEquals(UnitScreenScale.ENABLED, hud.<UiKit.Checkbox>find("tuning-zoom-scaling").isTicked());
                assertEquals(GpuRoutePulse.ENABLED, GpuRoutePulse.enabled, "the route pulse's constants");
                assertEquals(GpuRoutePulse.SPEED, GpuRoutePulse.speed, NEAR);
                assertEquals(GpuRoutePulse.INTENSITY, GpuRoutePulse.intensity, NEAR);
                assertEquals("1.00", reading(hud, "Hex scale"), "the panel shows the restored value");
            }
        });
    }

    @Test
    void theAtmospherePageEditsTheModelAndShowsItsOwnChanges() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                GpuAtmosphere.Options initialOptions = hud.tuning.atmosphereOptions();
                hud.show();
                hud.click("tuning-button");
                hud.click("tuning-atmosphere-tab");
                hud.capture("tuning-atmosphere");

                // Presets: the model takes the preset's conditions, and the panel shows the model's new values.
                for (AtmospherePreset preset : AtmospherePreset.values()) {
                    hud.click("atmosphere-" + preset.name());
                    BoardAtmosphere.Settings settings = preset.settings(.5);
                    assertEquals(settings, hud.tuning.atmosphere(), preset.label);
                    assertEquals(settings.clouds(), hud.<Slider>find("Cloud cover").getValue(), .01f, preset.label);
                    assertEquals(settings.moonlight(), hud.<UiKit.Checkbox>find("tuning-moonlight").isTicked());
                }
                hud.click("atmosphere-CLEAR");

                // Lighting.
                hud.drag("Time of day", 12);
                hud.drag("Exposure (EV)", .5f);
                assertEquals(12, hud.tuning.atmosphere().hour(), NEAR);
                assertEquals(.5f, hud.tuning.atmosphere().exposure(), NEAR);
                assertEquals("12:00", reading(hud, "Time of day"));
                hud.drag("Shadows", 45);
                hud.drag("Ambient light", 32);
                hud.drag("Sky contribution", 60);
                assertEquals(.45f, hud.tuning.atmosphereOptions().shadowStrength(), NEAR);
                assertEquals(.32f, hud.tuning.atmosphereOptions().ambientShare(), NEAR);
                assertEquals(.60f, hud.tuning.atmosphereOptions().skyContribution(), NEAR);
                hud.drag("White balance", .5f);
                hud.drag("Tint", -.25f);
                hud.drag("Saturation", 1.5f);
                hud.drag("Contrast", 1.2f);
                var grade = hud.tuning.atmosphereOptions();
                assertEquals(.5f, grade.whiteBalance(), NEAR);
                assertEquals(-.25f, grade.tint(), NEAR);
                assertEquals(1.5f, grade.saturation(), NEAR);
                assertEquals(1.2f, grade.contrast(), NEAR);
                assertEquals("+0.50", reading(hud, "White balance"));
                assertEquals("-0.25", reading(hud, "Tint"));
                assertEquals("1.50", reading(hud, "Saturation"));
                assertEquals("1.20", reading(hud, "Contrast"));
                hud.capture("tuning-color-controls");
                boolean moonlight = hud.tuning.atmosphere().moonlight();
                hud.click("tuning-moonlight");
                assertEquals(!moonlight, hud.tuning.atmosphere().moonlight(), "Moonlight switched");
                hud.click("tuning-fixed-sun");
                assertTrue(hud.tuning.fixedSun(), "Fixed sun/moon on the Atmosphere page");
                hud.click("tuning-fixed-sun");
                assertFalse(hud.tuning.fixedSun());

                // Planet properties: pressure and taint through their lists; vacuum disables the weather sliders.
                hud.drag("Gravity (g)", 2);
                assertEquals(2, hud.tuning.atmosphere().gravity(), NEAR);
                assertEquals(2, hud.tuning.gravityOverride(), NEAR);
                hud.drag("Temperature (C)", 0);
                assertEquals(0, hud.tuning.atmosphere().temperature());
                hud.choose("tuning-atmosphere-pressure", Atmosphere.VACUUM);
                assertSame(Atmosphere.VACUUM, hud.tuning.atmosphere().pressure());
                assertTrue(hud.<Slider>find("Rain").isDisabled(), "no rain in vacuum");
                assertTrue(hud.<UiButton>find("weather-toggle-Rain").isDisabled());
                hud.choose("tuning-atmosphere-pressure", Atmosphere.STANDARD);
                assertFalse(hud.<Slider>find("Rain").isDisabled());
                // The taint's list, open over the page: the current choice checked.
                float readings = right(readingOf(hud, "Time of day"));
                hud.click("tuning-atmospheric-taint");
                hud.capture("tuning-choice-open");
                hud.click(button(hud.find("tuning-choices"), String.valueOf(AtmosphericTaint.TOXIC_POISON)));
                assertSame(AtmosphericTaint.TOXIC_POISON, hud.tuning.atmosphere().taint());
                assertEquals(String.valueOf(AtmosphericTaint.TOXIC_POISON),
                      hud.<UiButton>find("tuning-atmospheric-taint").getText().toString(), "the face shows it");
                // A long choice ends in an ellipsis on its face: the page keeps its columns and its width.
                assertEquals(readings, right(readingOf(hud, "Time of day")), .5f, "the readings stay in place");
                GpuBoardTestUi.assertHorizontalBounds(hud.find("tuning-frame"), hud.find("tuning-frame"));
                // An open list closes with the panel and leaves no keyboard focus to swallow the HUD's keys. Keys
                // pass no press outside the list, which would close it by itself: the first Esc ends the list's
                // focus (GpuHud's chain), the second closes the panel.
                hud.click("tuning-atmosphere-pressure");
                assertTrue(hud.find("tuning-choices").isVisible());
                hud.key(KeyCommandBind.CANCEL);
                hud.key(KeyCommandBind.CANCEL);
                assertSame(GpuHudState.Dialog.NONE, hud.hud.state.dialog);
                assertFalse(hud.find("tuning-choices").isVisible(), "the list closes with the panel");
                assertNull(hud.hud.stage.getKeyboardFocus());
                hud.click("tuning-button");
                assertFalse(hud.find("tuning-choices").isVisible(), "and stays closed when the panel reopens");
                hud.capture("tuning-atmosphere-planet");

                // Clouds and ground air.
                hud.drag("Cloud cover", .6f);
                hud.drag("Ground fog", .4f);
                hud.drag("Ground layer height", 3);
                hud.drag("Haze", .3f);
                BoardAtmosphere.Settings air = hud.tuning.atmosphere();
                assertEquals(.6f, air.clouds(), NEAR);
                assertEquals(.4f, air.fog(), NEAR);
                assertEquals(3, air.groundLayerHeight(), NEAR);
                assertEquals(.3f, air.haze(), NEAR);

                // Weather effects: the sliders and the weather switches.
                hud.drag("Temperature (C)", 15);
                String[] weather = { "Rain", "Snow", "Hail", "Blowing sand", "Lightning", "Wind strength" };
                for (int index = 0; index < weather.length; index++) {
                    hud.drag(weather[index], .1f * (index + 2));
                }
                hud.drag("Wind direction", 90);
                BoardAtmosphere.Effects effects = hud.tuning.atmosphere().effects();
                assertEquals(.2f, effects.rain(), NEAR);
                assertEquals(.3f, effects.snow(), NEAR);
                assertEquals(.4f, effects.hail(), NEAR);
                assertEquals(.5f, effects.sand(), NEAR);
                assertEquals(.6f, effects.lightning(), NEAR);
                assertEquals(.7f, effects.wind(), NEAR);
                assertEquals(90, effects.windDirection(), NEAR);
                UiButton rain = hud.find("weather-toggle-Rain");
                assertEquals("RAIN ON", rain.getText().toString());
                hud.click(rain);
                assertEquals(0, hud.tuning.atmosphere().effects().rain(), NEAR, "the switch turns rain off");
                assertEquals("RAIN OFF", rain.getText().toString());
                assertFalse(rain.isChecked());
                hud.click(rain);
                assertEquals(.5f, hud.tuning.atmosphere().effects().rain(), NEAR, "and on again at half strength");

                // Light and fog effects.
                String[] names = { "God rays", "Cloud shadow min", "Cloud shadow max", "Sun glare",
                      "Fog height variation", "Fog density variation", "Moon shadow contrast", "Taint strength",
                      "Fog calm drift" };
                float[] values = { .8f, .3f, .9f, .2f, .5f, .4f, .45f, 1.5f, .12f };
                if (!hud.tuning.atmosphere().moonlight()) {
                    hud.click("tuning-moonlight");
                }
                for (int index = 0; index < names.length; index++) {
                    hud.drag(names[index], values[index]);
                }
                GpuAtmosphere.Options options = hud.tuning.atmosphereOptions();
                float[] model = { options.rays(), options.minCloudShadow(), options.maxCloudShadow(),
                      options.sunGlare(), options.fogHeightVariation(), options.fogDensityVariation(),
                      options.moonShadowContrast(), options.taintStrength(), options.fogCalmDrift() };
                for (int index = 0; index < names.length; index++) {
                    assertEquals(values[index], model[index], NEAR, names[index]);
                }
                hud.capture("tuning-atmosphere-effects");

                // Planetary conditions: the model's button opens the window's editor, one at a time.
                UiButton conditions = hud.find("tuning-planetary-conditions");
                assertEquals("PLANETARY CONDITIONS…", conditions.getText().toString());
                GpuAtmosphereControls.Editor editor = (initial, confirmed, closed) -> { };
                hud.tuning.conditionsEditor(editor);
                hud.click(conditions);
                verify(hud.source).editPlanetaryConditions(same(editor), any());
                assertTrue(conditions.isDisabled(), "while its editor is open");

                // Defaults restores the page and the extra effect controls.
                hud.click("tuning-defaults");
                assertEquals(BoardAtmosphere.DEFAULTS, hud.tuning.atmosphere());
                assertEquals(initialOptions, hud.tuning.atmosphereOptions());
                assertEquals("+0.00", reading(hud, "White balance"));
                assertEquals("+0.00", reading(hud, "Tint"));
                assertEquals("1.00", reading(hud, "Saturation"));
                assertEquals("1.00", reading(hud, "Contrast"));
                assertEquals(BoardAtmosphere.DEFAULTS.clouds(), hud.<Slider>find("Cloud cover").getValue(), .01f);
            }
        });
    }

    @Test
    void theCameraPageSetsTheFixedSunAndTheCamerasFraming() {
        GpuHudTestStage.run(harness -> {
            try (Hud hud = new Hud(harness)) {
                hud.show();
                hud.click("tuning-button");
                hud.click("tuning-camera-tab");
                UiKit.Checkbox selection = hud.find("camera-animate-selection");
                UiKit.Checkbox movement = hud.find("camera-animate-movement");
                UiKit.Checkbox returning = hud.find("camera-return-playback");
                UiKit.Checkbox dramatic = hud.find("camera-dramatic-death");
                assertEquals(hud.camera.animateOnSelectionChange, selection.isTicked());
                assertEquals(hud.camera.animateOnMove, movement.isTicked());
                assertTrue(returning.isTicked());
                assertTrue(hud.camera.returnAfterPlayback());
                assertTrue(dramatic.isTicked());
                assertTrue(hud.tuning.dramaticDeath());
                hud.click(returning);
                hud.click(dramatic);
                assertFalse(hud.camera.returnAfterPlayback());
                assertFalse(hud.tuning.dramaticDeath());

                hud.click("camera-fixed-sun");
                assertTrue(hud.tuning.fixedSun(), "the fixed sun is the model's");
                hud.click("tuning-atmosphere-tab");
                assertTrue(hud.<UiKit.Checkbox>find("tuning-fixed-sun").isTicked(), "the Atmosphere page shows it");
                hud.click("tuning-camera-tab");
                boolean animateSelection = hud.camera.animateOnSelectionChange;
                hud.click(selection);
                assertEquals(!animateSelection, hud.camera.animateOnSelectionChange);
                boolean animateMovement = hud.camera.animateOnMove;
                hud.click(movement);
                assertEquals(!animateMovement, hud.camera.animateOnMove);
                hud.capture("tuning-camera");

                // Changes made elsewhere (the model, the camera) show on the next frame.
                hud.tuning.setFixedSun(false);
                hud.camera.animateOnMove = animateMovement;
                hud.camera.setReturnAfterPlayback(true);
                hud.show();
                assertFalse(hud.<UiKit.Checkbox>find("camera-fixed-sun").isTicked());
                assertEquals(animateMovement, movement.isTicked());
                assertTrue(returning.isTicked());
            }
        });
    }

    @Test
    void planetaryConditionsOpensTheWindowsEditor() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        AtomicBoolean onSwing = new AtomicBoolean();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuHudTestStage.run(harness -> {
                GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
                GpuBoardTuning tuning = new GpuBoardTuning(harness.theme.skin, fixture.source);
                tuning.conditionsEditor((initial, confirmed, closed) -> {
                    onSwing.set(SwingUtilities.isEventDispatchThread());
                    opened.countDown();
                });
                GpuTuningPanel panel = new GpuTuningPanel(harness.kit, fixture.source, state, new BoardCamera(), tuning);
                harness.window.addActor(panel.actor());
                panel.actor().setSize(harness.width(), harness.height());
                state.dialog = GpuHudState.Dialog.TUNING;
                GpuHud.Inputs inputs = new GpuHud.Inputs(null, GpuHud.HudView.EMPTY, null, null,
                      GpuHud.Metrics.of(harness.width(), harness.height()), List.of());
                panel.update(inputs);
                harness.draw();
                UiButton tab = harness.stage.getRoot().findActor("tuning-atmosphere-tab");
                clickOn(harness, tab);
                panel.update(inputs);
                harness.draw();
                UiButton conditions = harness.stage.getRoot().findActor("tuning-planetary-conditions");
                clickOn(harness, conditions);
                assertTrue(opened.await(30, TimeUnit.SECONDS), "the window's planetary conditions editor opens");
                assertTrue(onSwing.get(), "the editor opens on the Swing thread, where the preview lives");
                panel.update(inputs);
                harness.draw();
                assertTrue(conditions.isDisabled(), "one editor at a time");
            });
        }
    }

    /** Each model control has a shown control of its name and kind: a slider, a checkbox or a hud button. */
    private static void assertMirrored(Hud hud, List<Actor> controls) {
        for (Actor model : controls) {
            Class<?> kind = model instanceof Slider ? Slider.class
                  : model instanceof CheckBox ? UiKit.Checkbox.class : UiButton.class;
            assertInstanceOf(kind, hud.find(model.getName()), model.getName());
        }
    }

    /** The named controls of a model page: sliders, checkboxes, choices and buttons, nested ones included. */
    private static List<Actor> controls(Group page) {
        List<Actor> controls = new ArrayList<>();
        for (Actor actor : page.getChildren()) {
            if (actor instanceof Slider || actor instanceof SelectBox<?> || actor instanceof TextButton) {
                assertNotNull(actor.getName(), "every model control is named: " + actor);
                controls.add(actor);
            } else if (actor instanceof Group group) {
                controls.addAll(controls(group));
            }
        }
        return controls;
    }

    /** The reading the panel shows right of the slider of that name. */
    private static String reading(Hud hud, String name) {
        return readingOf(hud, name).getText().toString();
    }

    private static Label readingOf(Hud hud, String name) {
        Slider slider = hud.find(name);
        Group row = slider.getParent();
        return (Label) row.getChildren().get(row.getChildren().indexOf(slider, true) + 1);
    }

    /** The actor's right edge in stage units. */
    private static float right(Actor actor) {
        Rectangle area = GpuHudTestStage.bounds(actor);
        return area.x + area.width;
    }

    /** The visible button of that text under {@code group}, or null. */
    private static UiButton button(Group group, String text) {
        for (Actor actor : group.getChildren()) {
            if (actor.isVisible() && actor instanceof UiButton button && button.getText().toString().equals(text)) {
                return button;
            }
            if (actor.isVisible() && actor instanceof Group nested) {
                UiButton found = button(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A ChangeEvent on the model's button, as a click on it fires. */
    private static void fire(TextButton model) {
        ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
        model.fire(event);
        Pools.free(event);
    }

    /** A left click at the actor's centre through the harness's stage. */
    private static void clickOn(GpuHudTestStage harness, Actor actor) {
        assertNotNull(actor);
        Vector2 point = harness.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        harness.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        harness.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
}
