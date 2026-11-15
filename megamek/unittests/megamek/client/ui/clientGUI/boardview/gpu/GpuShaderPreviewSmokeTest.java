/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The real effect renderers in an offscreen stage, with draft replacement and shared-context isolation. */
@Tag("on-demand")
class GpuShaderPreviewSmokeTest {
    @Test
    void loopsEffectsAppliesDraftsAndLeavesTheCallerFramebufferIntact() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                var preview = new GpuShaderPreview();
                FrameBuffer caller = null;
                try {
                    GpuGlsl.detect();
                    caller = GpuAtmosphere.buffer(32, 32, true);
                    caller.bind();
                    Gdx.gl.glViewport(3, 4, 20, 21);
                    Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                    Gdx.gl.glScissor(0, 0, 32, 32);
                    Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                    Gdx.gl.glDepthRangef(.2f, .85f);
                    Gdx.gl.glColorMask(false, true, true, false);
                    int framebuffer = integer(GL20.GL_FRAMEBUFFER_BINDING);
                    manager.run(() -> checkSamples(manager, preview, framebuffer));
                    manager.run(preview::dispose);
                    assertEquals(0, ShaderProgram.getNumManagedShaderPrograms(), "Preview releases all its programs");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    manager.run(preview::dispose);
                    manager.close();
                    if (caller != null) { caller.dispose(); }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkSamples(GpuShaderManager manager, GpuShaderPreview preview, int framebuffer) {
        var presets = Arrays.stream(GpuShaderPreview.Preset.values())
              .filter(preset -> preset != GpuShaderPreview.Preset.AUTO && preset != GpuShaderPreview.Preset.NONE).toList();
        var sheet = new BufferedImage(GpuShaderPreview.WIDTH * 2, (GpuShaderPreview.HEIGHT + 24) * presets.size(), BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        long now = 1_000_000_000L;
        int restart = 0;
        try {
            for (var preset : presets) {
                var settings = settings(preset, true, ++restart);
                var first = preview.render(settings, BoardSwitches.BASE, now += 100_000_000, manager.revision());
                assertImage(first);
                assertEquals(preset, first.preset(), "Condition files select the matching automatic preview");
                var later = preview.render(settings, BoardSwitches.BASE, now += 100_000_000, manager.revision());
                assertImage(later);
                assertFalse(Arrays.equals(pixels(first.image()), pixels(later.image())), preset + " must animate");
                int y = (restart - 1) * (GpuShaderPreview.HEIGHT + 24);
                graphics.drawImage(first.image(), 0, y, null);
                graphics.drawImage(later.image(), GpuShaderPreview.WIDTH, y, null);
                graphics.setColor(java.awt.Color.WHITE);
                graphics.drawString(preset.toString(), 8, y + GpuShaderPreview.HEIGHT + 17);
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), preset.toString());
                assertEquals(framebuffer, integer(GL20.GL_FRAMEBUFFER_BINDING));
                var viewport = BufferUtils.newIntBuffer(4);
                Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
                assertEquals(3, viewport.get(0)); assertEquals(4, viewport.get(1));
                assertEquals(20, viewport.get(2)); assertEquals(21, viewport.get(3));
                assertTrue(Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST));
                assertFalse(Gdx.gl.glIsEnabled(GL20.GL_DEPTH_TEST));
                var range = BufferUtils.newFloatBuffer(2);
                Gdx.gl.glGetFloatv(GL20.GL_DEPTH_RANGE, range);
                assertEquals(.2f, range.get(0), .0001f); assertEquals(.85f, range.get(1), .0001f);
                var masks = BufferUtils.newByteBuffer(4);
                Gdx.gl.glGetBooleanv(GL20.GL_COLOR_WRITEMASK, masks);
                assertEquals(0, masks.get(0)); assertEquals(1, masks.get(1)); assertEquals(0, masks.get(3));
                var paused = preview.render(settings(preset, false, restart), BoardSwitches.BASE, now += 100_000_000, manager.revision());
                assertImage(paused);
                assertEquals(later.seconds(), paused.seconds());
                assertNull(preview.render(settings(preset, false, restart), BoardSwitches.BASE, now += 100_000_000, manager.revision()), "Pause skips readback");
            }
            var small = preview.render(settings(GpuShaderPreview.Preset.IMPACT, false, ++restart,
                  new GpuShaderPreview.Inputs(2, 1, 6, 6, true)), BoardSwitches.BASE, now += 100_000_000, manager.revision());
            var large = preview.render(settings(GpuShaderPreview.Preset.IMPACT, false, restart,
                  new GpuShaderPreview.Inputs(20, 1, 6, 6, true)), BoardSwitches.BASE, now += 100_000_000, manager.revision());
            assertImage(small); assertImage(large);
            assertEquals(small.seconds(), large.seconds(), "Changing rack size preserves the paused impact phase");
            assertFalse(Arrays.equals(pixels(small.image()), pixels(large.image())), "rackSize reaches the real impact geometry");
            var restored = preview.render(settings(GpuShaderPreview.Preset.IMPACT, false, restart,
                  new GpuShaderPreview.Inputs(2, 1, 6, 6, true)), BoardSwitches.BASE, now += 100_000_000, manager.revision());
            assertTrue(Arrays.equals(pixels(small.image()), pixels(restored.image())), "Input comparisons use a stable sample seed and clock");
            var laser = settings(GpuShaderPreview.Preset.LASER, false, ++restart);
            var original = preview.render(laser, BoardSwitches.BASE, now += 100_000_000, manager.revision());
            assertImage(original);
            var result = manager.apply(Map.of("beams.frag", """
                  #version 330 core
                  layout(location = 0) out vec4 fragColor;
                  void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
                  """));
            assertTrue(result.success(), result.message());
            assertTrue(result.updated() > 0, "The preview makes an otherwise inactive laser shader editable");
            var edited = preview.render(laser, BoardSwitches.BASE, now += 100_000_000, manager.revision());
            assertImage(edited);
            assertFalse(Arrays.equals(pixels(original.image()), pixels(edited.image())), "An edit must reach preview pixels while paused");
            assertTrue(hasGreenPixels(edited.image()), "The edited laser is visibly green");
            assertFalse(manager.apply(Map.of("beams.frag", "unfinished edit")).success());
            var retained = preview.render(settings(GpuShaderPreview.Preset.LASER, false, ++restart), BoardSwitches.BASE,
                  now += 100_000_000, manager.revision());
            assertImage(retained);
            assertTrue(Arrays.equals(pixels(edited.image()), pixels(retained.image())), "Invalid edits preserve the working sample");
            boolean disappeared = false, repeated = false;
            for (int frame = 0; frame < 40 && !repeated; frame++) {
                var playing = preview.render(settings(GpuShaderPreview.Preset.LASER, true, restart), BoardSwitches.BASE,
                      now += 100_000_000, manager.revision());
                assertImage(playing);
                if (!hasGreenPixels(playing.image())) { disappeared = true; }
                else if (disappeared) { repeated = true; }
            }
            assertTrue(repeated, "The laser fires again automatically after completing a shot");
            var hidden = new GpuShaderPreview.Settings("beams.frag", GpuShaderPreview.Preset.AUTO, false, true, 1, -75, 18, 1, restart,
                  GpuShaderPreview.Inputs.DEFAULT);
            assertNull(preview.render(hidden, BoardSwitches.BASE, now + 100_000_000, manager.revision()), "Hidden editors do no preview work");
            try {
                Path folder = Path.of(System.getProperty("megamek.gpu.screenshots", "build/shader-preview"));
                Files.createDirectories(folder);
                ImageIO.write(sheet, "png", folder.resolve("shader-preview-samples.png").toFile());
                ImageIO.write(edited.image(), "png", folder.resolve("shader-preview-edited.png").toFile());
            } catch (java.io.IOException error) { throw new AssertionError(error); }
        } finally { graphics.dispose(); }
    }

    private static GpuShaderPreview.Settings settings(GpuShaderPreview.Preset preset, boolean play, int restart) {
        return settings(preset, play, restart, GpuShaderPreview.Inputs.DEFAULT);
    }

    private static GpuShaderPreview.Settings settings(GpuShaderPreview.Preset preset, boolean play, int restart, GpuShaderPreview.Inputs inputs) {
        String file = switch (preset) {
            case SMOKE -> "particles-smoke.glsl";
            case JET -> "particles-jet.glsl";
            case FLAME -> "particles-fire.glsl";
            case RAIN -> "weather-rain.glsl";
            case SNOW -> "weather-snow.glsl";
            case HAIL -> "weather-hail.glsl";
            default -> "";
        };
        return new GpuShaderPreview.Settings(file, file.isEmpty() ? preset : GpuShaderPreview.Preset.AUTO,
              true, play, 1, -75, 18, 1, restart, inputs);
    }

    private static int[] pixels(BufferedImage image) { return ((DataBufferInt) image.getRaster().getDataBuffer()).getData(); }

    static boolean hasGreenPixels(BufferedImage image) {
        return Arrays.stream(pixels(image)).filter(rgb -> {
            int green = rgb >> 8 & 255;
            return green > (rgb >> 16 & 255) + 60 && green > (rgb & 255) + 60;
        }).limit(26).count() > 25;
    }

    private static void assertImage(GpuShaderPreview.Frame frame) {
        assertNotNull(frame);
        assertNotNull(frame.image(), frame.message());
        assertTrue(Arrays.stream(pixels(frame.image())).distinct().count() > 25, "The stage must contain rendered detail");
    }

    private static int integer(int parameter) {
        var value = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(parameter, value);
        return value.get(0);
    }
}
