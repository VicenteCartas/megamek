/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Quality choice through the real view: Medium's programs link and draw a real board, it takes the costly options out,
 * High brings back the player's own choices, and neither touches the render resolution.
 */
@Tag("on-demand")
class GpuQualitySmokeTest {
    @Test
    void normalDrawsTheBoardAndHighBringsTheChoicesBack() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        String savedQuality = preferences.getBoardQuality();
        float savedResolution = preferences.getBoardRenderResolution();
        boolean savedGrass = preferences.getBoardGrass();
        // What a new player starts with, whatever this machine has saved.
        preferences.setBoardQuality(preferences.getDefaultString(GUIPreferences.BOARD_QUALITY));
        preferences.setBoardRenderResolution(preferences.getDefaultFloat(GUIPreferences.BOARD_RENDER_RESOLUTION));
        preferences.setBoardGrass(preferences.getDefaultBoolean(GUIPreferences.BOARD_GRASS));
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 800);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.center(BoardGeometry.center(new Coords(5, 5), 0));
                        view.boardCamera.zoom(.3f);
                        SelectBox<BoardQuality> quality = GpuBoardTestUi.tuning(view, "tuning-quality");
                        Slider resolution = GpuBoardTestUi.tuning(view, "tuning-render-resolution");
                        CheckBox occlusion = GpuBoardTestUi.tuning(view, "tuning-ambient-occlusion");
                        CheckBox grass = GpuBoardTestUi.tuning(view, "tuning-grass");
                        var tuning = GpuBoardTestUi.tuning(view);
                        assertEquals(BoardQuality.MEDIUM, quality.getSelected(), "A new player starts in Medium");
                        assertEquals(100, resolution.getValue(), "at the full render resolution");
                        assertEquals(1, BoardCamera.renderScale);
                        assertTrue(occlusion.isDisabled(), "with what it leaves out disabled");
                        assertFalse(grass.isDisabled());
                        GpuCamouflageReview.renderReady(view);
                        assertEquals(BoardQuality.MEDIUM, BoardQuality.current);

                        // Low keeps Medium's programs: its switch links nothing and needs no loading stage.
                        int links = GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading();
                        quality.setSelected(BoardQuality.LOW);
                        for (int frame = 0; frame < 30; frame++) {
                            GpuBoardTestUi.present(view);
                            view.render();
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Low draws the board");
                            assertFalse(GpuBoardTestUi.loading(view), "Low shares Medium's programs");
                        }
                        assertEquals(BoardQuality.LOW, BoardQuality.current);
                        assertEquals(links, GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading());
                        assertTrue(grass.isChecked() && grass.isDisabled(), "Low leaves grass out, keeping the choice");

                        quality.setSelected(BoardQuality.HIGH);
                        assertFalse(grass.isDisabled());
                        occlusion.setChecked(true);
                        resolution.setValue(70);
                        view.render();
                        assertTrue(tuning.displaySettings().ambientOcclusion(), "High takes the player's choice");

                        quality.setSelected(BoardQuality.MEDIUM);
                        for (int frame = 0; frame < 30; frame++) {
                            GpuBoardTestUi.present(view);
                            view.render();
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Medium's programs draw the board");
                        }
                        assertEquals(BoardQuality.MEDIUM, BoardQuality.current);
                        assertEquals(70, resolution.getValue(), "Quality leaves the render resolution alone");
                        assertEquals(.7f, BoardCamera.renderScale, .001f);
                        assertFalse(tuning.displaySettings().ambientOcclusion(), "Medium leaves it out");
                        assertTrue(occlusion.isChecked() && occlusion.isDisabled(), "keeping the choice, disabled");

                        quality.setSelected(BoardQuality.HIGH);
                        for (int frame = 0; frame < 30; frame++) {
                            GpuBoardTestUi.present(view);
                            view.render();
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        }
                        assertEquals(BoardQuality.HIGH, BoardQuality.current);
                        assertEquals(70, resolution.getValue());
                        assertFalse(occlusion.isDisabled());
                        assertTrue(tuning.displaySettings().ambientOcclusion(), "High brings the choice back");
                        occlusion.setChecked(false);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        view.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        } finally {
            // On the Swing thread, after the panel's own queued saves.
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardQuality(savedQuality);
                preferences.setBoardRenderResolution(savedResolution);
                preferences.setBoardGrass(savedGrass);
            });
            BoardCamera.renderScale = 1;
        }
        if (failure.get() != null) { throw new AssertionError("Quality on the real board", failure.get()); }
    }
}
