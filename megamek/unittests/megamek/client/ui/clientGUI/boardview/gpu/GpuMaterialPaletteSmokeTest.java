/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tuning's material palette as a native card: it draws every board material on disk a few a frame, shows them in a
 * gallery with its progress, and gives every texture back when it closes, even with a pass still drawing.
 */
@Tag("on-demand")
class GpuMaterialPaletteSmokeTest {
    @Test
    void drawsEveryMaterialAndReleasesItsTexturesOnClose() {
        GpuHudTestStage.run(hud -> {
            Deque<Runnable> frames = new ArrayDeque<>();
            UiDialog dialog = new UiDialog(hud.kit.ui, "Material palette", () -> { }).width(GpuMaterialPalette.WIDTH);
            GpuMaterialPalette palette = new GpuMaterialPalette(hud.kit.ui, dialog, GpuMaterialPalette.WIDTH - 96, null,
                  () -> { }, frames::add);
            dialog.show(hud.stage, false);
            int materials = GpuMaterialPalette.materials().size();
            assertTrue(materials > 0, "the staged mm-data has board materials");
            assertEquals(materials, palette.tileCount());
            Label status = hud.stage.getRoot().findActor("palette-status");
            assertNotNull(status);
            assertTrue(status.getText().toString().startsWith("Drawing " + materials), status.getText().toString());
            int frame = 0;
            while (palette.drawnCount() < materials && frame++ < materials) {
                step(hud, frames);
            }
            assertEquals(materials, palette.drawnCount(), "every material is drawn");
            assertEquals(materials + " of " + materials + " materials", status.getText().toString());
            UiTestStage.assertTexts(dialog.card());
            UiTestStage.capture("material-palette", 1920, 1080).dispose();

            // The other shapes follow in the background; a change of shape then shows them without drawing again.
            while (!frames.isEmpty() && frame++ < 4 * materials) {
                step(hud, frames);
            }
            assertEquals(materials, palette.completeCount(), "every material is drawn on every shape");
            UiSegmented shape = hud.stage.getRoot().findActor("palette-shape");
            shape.select(2);
            shape.fire(new ChangeListener.ChangeEvent());
            assertTrue(frames.isEmpty(), "a drawn shape needs no new pass");
            assertEquals(materials, palette.drawnCount(), "the sphere swatches show at once");
            assertEquals(materials + " of " + materials + " materials", status.getText().toString());

            // Closing mid-pass: the next frame ends the pass and gives back its renderer.
            assertTrue(UiDialog.press(hud.stage.getRoot().findActor("palette-reload")));
            step(hud, frames);
            assertFalse(frames.isEmpty(), "the redraw is under way");
            palette.dispose();
            dialog.close();
            step(hud, frames);
            assertTrue(frames.isEmpty(), "the closed card's pass ends");
            assertEquals(0, palette.tileCount());
        });
    }

    /** One frame: the work queued for its start, then the stage. */
    private static void step(GpuHudTestStage hud, Deque<Runnable> frames) {
        List<Runnable> due = new ArrayList<>(frames);
        frames.clear();
        due.forEach(Runnable::run);
        ScreenUtils.clear(.1f, .12f, .14f, 1, true);
        hud.stage.act(0);
        hud.stage.draw();
    }
}
