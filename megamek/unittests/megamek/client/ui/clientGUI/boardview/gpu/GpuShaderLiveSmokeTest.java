/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuShaderPreviewSmokeTest.hasGreenPixels;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real editor documents/timer, board pixels, linked programs, shared includes and failure recovery. */
@Tag("on-demand")
class GpuShaderLiveSmokeTest {
    @Test
    void saveSurvivesSupersededPreviewsAndCompileErrorsCancelIt(@TempDir Path directory) throws Exception {
        record Preview(Map<String, String> sources, Consumer<GpuShaderManager.Result> completed) { }
        SwingUtilities.invokeAndWait(() -> {
            Path path = directory.resolve("fixture.frag");
            var previews = new ArrayList<Preview>();
            var editor = new GpuShaderEditor(Map.of("fixture.frag", new GpuShaderSource.FileSource("saved", path, false)),
                  "test", (sources, completed) -> previews.add(new Preview(sources, completed)), callback -> { });
            try {
                editor.open("fixture.frag");
                var documents = (Map<?, ?>) field(editor, "documents");
                var text = (RSyntaxTextArea) field(documents.get("fixture.frag"), "text");
                text.setText("first draft");
                editor.apply(true);
                text.setText("newer draft");
                editor.apply(false);
                previews.get(0).completed().accept(new GpuShaderManager.Result(true, "Compiled", null, 1));
                assertFalse(Files.exists(path), "An obsolete compilation cannot save newer unvalidated text");
                assertEquals("newer draft", previews.get(1).sources().get("fixture.frag"));
                previews.get(1).completed().accept(new GpuShaderManager.Result(true, "Compiled", null, 1));
                assertEquals("newer draft", Files.readString(path));

                text.setText("broken draft");
                editor.apply(true);
                previews.get(2).completed().accept(new GpuShaderManager.Result(false, "Preview unchanged", null, 0));
                assertEquals("newer draft", Files.readString(path));
                text.setText("fixed draft");
                editor.apply(false);
                previews.get(3).completed().accept(new GpuShaderManager.Result(true, "Compiled", null, 1));
                assertEquals("newer draft", Files.readString(path), "Fixing a rejected save only previews until Save is pressed again");
            } catch (Exception error) { throw new AssertionError(error); }
            finally { editor.close(); }
        });
    }

    @Test
    void typingUpdatesTheBoardAndInvalidEditsKeepTheLastWorkingPrograms() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(Board.createEmptyBoard(6, 6))) {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                GpuShaderManager manager;
                GpuShaderEditor editor;
                GpuTerrain originalTerrain;
                ShaderProgram original, preview;
                Object units;
                String composite, light;
                int step;
                long revision;
                float sampleSeconds;
                BufferedImage originalRain;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Live shader editor step " + step + " timed out");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "GL error at step " + step);
                        if (step == 0 && frames() >= 5 && !((GpuTerrain) field(this, "terrain")).busy()) {
                            originalTerrain = (GpuTerrain) field(this, "terrain");
                            manager = (GpuShaderManager) field(this, "shaderEdits");
                            original = compositeProgram();
                            composite = GpuShaderSource.readDisk("atmosphere-composite.frag");
                            light = GpuShaderSource.readDisk("light-model.glsl");
                            // Edit shaders is the tuning model's button, in the HUD's tuning panel footer.
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-edit-shaders"));
                            step = 1;
                        } else if (step == 1) {
                            SwingUtilities.invokeAndWait(() -> {
                                try { editor = (GpuShaderEditor) field(manager, "editor"); }
                                catch (Exception error) { throw new AssertionError(error); }
                            });
                            if (editor == null) { return; }
                            revision = manager.revision();
                            String output = "fragColor = vec4(color, mix(1.0, coverage, u_transparentBackground));";
                            assertTrue(composite.contains(output), "The live edit must change the current output statement");
                            edit("atmosphere-composite.frag", composite.replace(output,
                                  "fragColor = vec4(0.85, 0.15, 0.65, 1.0);"));
                            step = 2;
                        } else if (step == 2 && manager.revision() > revision) {
                            assertSame(originalTerrain, field(this, "terrain"), "Typing must not rebuild terrain");
                            preview = compositeProgram();
                            assertNotSame(original, preview);
                            assertFalse(Gdx.gl.glIsProgram(original.getHandle()), "The replaced program is released");
                            assertPink();
                            checkRejectedChanges();
                            capture();
                            units = ((GpuShaderProvider) ((ModelBatch) field(this, "unitBatch")).getShaderProvider()).active();
                            // A shared include prepares material replacements before the later composite fails.
                            // Compilation drops comments (GpuGlsl.withoutComments), so the edit is a define.
                            edit("light-model.glsl", light + "\n#define LIVE_EDITOR_SHARED_INCLUDE\n");
                            edit("atmosphere-composite.frag", "#version 330 core\nthis is an unfinished edit");
                            step = 3;
                        } else if (step == 3 && editorStatus().startsWith("Preview unchanged")) {
                            assertSame(preview, compositeProgram());
                            assertTrue(Gdx.gl.glIsProgram(preview.getHandle()));
                            assertSame(units, ((GpuShaderProvider) ((ModelBatch) field(this, "unitBatch")).getShaderProvider()).active());
                            assertPink();
                            revision = manager.revision();
                            edit("atmosphere-composite.frag", composite);
                            step = 4;
                        } else if (step == 4 && manager.revision() > revision) {
                            assertSame(originalTerrain, field(this, "terrain"));
                            assertNotSame(preview, compositeProgram());
                            assertFalse(Gdx.gl.glIsProgram(preview.getHandle()));
                            var provider = (GpuShaderProvider) ((ModelBatch) field(this, "unitBatch")).getShaderProvider();
                            assertNotSame(units, provider.active());
                            assertTrue(provider.sources().stream().allMatch(source ->
                                  source.fragment().contains("LIVE_EDITOR_SHARED_INCLUDE")));
                            assertEquals(composite, GpuShaderSource.readDisk("atmosphere-composite.frag"));
                            checkInactiveEffect();
                            manager.run(() -> assertTrue(manager.apply(Map.of()).success()));
                            SwingUtilities.invokeAndWait(() -> editor.open("beams.frag"));
                            step = 5;
                        } else if (step == 5 && sample() != null && sample().preset() == GpuShaderPreview.Preset.LASER) {
                            assertTrue(sample().image() != null, sample().message());
                            sampleSeconds = sample().seconds();
                            step = 6;
                        } else if (step == 6 && sample() != null && sample().seconds() > sampleSeconds) {
                            SwingUtilities.invokeAndWait(() -> {
                                try {
                                    ((JCheckBox) field(editor.previewPanel(), "play")).doClick();
                                    ((JButton) field(editor.previewPanel(), "replay")).doClick();
                                }
                                catch (Exception error) { throw new AssertionError(error); }
                            });
                            revision = manager.revision();
                            edit("beams.frag", """
                                  #version 330 core
                                  layout(location = 0) out vec4 fragColor;
                                  void main() { fragColor = vec4(0.0, 1.0, 0.0, 1.0); }
                                  """);
                            step = 7;
                        } else if (step == 7 && manager.revision() > revision && greenSample()) {
                            assertSame(originalTerrain, field(this, "terrain"));
                            assertEquals(0, fixture.clicks.get(), "Editor never issues game commands");
                            SwingUtilities.invokeAndWait(() -> editor.open("explosion.frag"));
                            step = 8;
                        } else if (step == 8 && sample() != null && sample().preset() == GpuShaderPreview.Preset.EXPLOSION) {
                            assertTrue(sample().image() != null, sample().message());
                            SwingUtilities.invokeAndWait(() -> editor.open("weather-particles.frag"));
                            step = 9;
                        } else if (step == 9 && input("u_light", 3) != null && sample() != null
                              && sample().preset() == GpuShaderPreview.Preset.RAIN) {
                            assertTrue(input("u_wind", 3) != null && input("u_clock", 3) != null && input("u_projView", 3) != null,
                                  "Weather inputs come from program reflection");
                            originalRain = sample().image();
                            override("u_light", "1, 0, 0");
                            step = 10;
                        } else if (step == 10 && "1.0, 0.0, 0.0".equals(input("u_light", 3)) && redSample()) {
                            capture();
                            override("u_light", "");
                            step = 11;
                        } else if (step == 11 && "".equals(input("u_light", 4)) && sameSample(originalRain)) {
                            revision = manager.revision();
                            String weather = GpuShaderSource.readDisk("weather-particles.frag");
                            assertTrue(weather.contains("color * u_light"));
                            edit("weather-particles.frag", weather.replace("void main()", "uniform float u_previewGain = 1.0;\nvoid main()")
                                  .replace("color * u_light", "color * u_light * u_previewGain"));
                            step = 12;
                        } else if (step == 12 && manager.revision() > revision && input("u_previewGain", 3) != null) {
                            override("u_previewGain", "0");
                            step = 13;
                        } else if (step == 13 && "0.0".equals(input("u_previewGain", 3)) && !sameSample(originalRain)) {
                            override("u_previewGain", "");
                            step = 14;
                        } else if (step == 14 && "1.0".equals(input("u_previewGain", 3)) && sameSample(originalRain)) {
                            assertEquals(0, fixture.clicks.get());
                            capture();
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }

                ShaderProgram compositeProgram() throws Exception {
                    return (ShaderProgram) field(field(this, "atmosphere"), "compositeShader");
                }

                void edit(String name, String source) throws Exception {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            editor.open(name);
                            var documents = (Map<?, ?>) field(editor, "documents");
                            ((RSyntaxTextArea) field(documents.get(name), "text")).setText(source);
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                }

                String editorStatus() throws Exception {
                    var status = new AtomicReference<String>();
                    SwingUtilities.invokeAndWait(() -> {
                        try { status.set(((JLabel) field(editor, "status")).getText()); }
                        catch (Exception error) { throw new AssertionError(error); }
                    });
                    return status.get();
                }

                GpuShaderPreview.Frame sample() throws Exception {
                    var result = new AtomicReference<GpuShaderPreview.Frame>();
                    SwingUtilities.invokeAndWait(() -> {
                        try { result.set((GpuShaderPreview.Frame) field(editor.previewPanel(), "displayed")); }
                        catch (Exception error) { throw new AssertionError(error); }
                    });
                    return result.get();
                }

                boolean greenSample() throws Exception {
                    var frame = sample();
                    return frame != null && frame.image() != null && hasGreenPixels(frame.image());
                }

                boolean sameSample(BufferedImage expected) throws Exception {
                    var frame = sample();
                    return frame != null && frame.image() != null && Arrays.equals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0,
                          expected.getWidth()), frame.image().getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()));
                }

                boolean redSample() throws Exception {
                    var frame = sample();
                    if (frame == null || frame.image() == null) { return false; }
                    return Arrays.stream(frame.image().getRGB(0, 0, frame.image().getWidth(), frame.image().getHeight(), null, 0,
                          frame.image().getWidth())).filter(rgb -> (rgb >> 16 & 255) > (rgb >> 8 & 255) + 40
                                && (rgb >> 16 & 255) > (rgb & 255) + 40).limit(26).count() > 25;
                }

                String input(String name, int column) throws Exception {
                    var result = new AtomicReference<String>();
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            var table = (JTable) field(editor.inputsPanel(), "table");
                            for (int row = 0; row < table.getRowCount(); row++) {
                                if (table.getValueAt(row, 0).equals(name)) { result.set(table.getValueAt(row, column).toString()); }
                            }
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                    return result.get();
                }

                void override(String name, String value) throws Exception {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            var table = (JTable) field(editor.inputsPanel(), "table");
                            for (int row = 0; row < table.getRowCount(); row++) {
                                if (table.getValueAt(row, 0).equals(name)) {
                                    table.setValueAt(value, row, 4);
                                    table.scrollRectToVisible(table.getCellRect(row, 4, true));
                                    return;
                                }
                            }
                            throw new AssertionError("Missing dynamic input " + name);
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                }

                void assertPink() {
                    byte[] pixel = ScreenUtils.getFrameBufferPixels(Gdx.graphics.getBackBufferWidth() / 2,
                          Gdx.graphics.getBackBufferHeight() / 2, 1, 1, false);
                    assertEquals(217, Byte.toUnsignedInt(pixel[0]), 2);
                    assertEquals(38, Byte.toUnsignedInt(pixel[1]), 2);
                    assertEquals(166, Byte.toUnsignedInt(pixel[2]), 2);
                }

                void checkInactiveEffect() {
                    // The warm-up links every weapon effect (GpuBattleView.warmUp); this board has no ocean.
                    manager.run(() -> {
                        var result = manager.apply(Map.of("ocean-initial.frag", "invalid lazy effect"));
                        assertTrue(result.success(), result.message());
                        ShaderProgram[] effect = new ShaderProgram[1];
                        effect[0] = GpuShaderManager.program(() -> GpuOcean.program("ocean-initial.frag"),
                              next -> effect[0] = next);
                        assertTrue(effect[0].isCompiled(), "An inactive invalid draft falls back when first drawn");
                        assertFalse(effect[0].getFragmentShaderSource().contains("invalid lazy effect"));
                        GpuShaderManager.dispose(effect[0]);
                    });
                }

                void checkRejectedChanges() {
                    manager.run(() -> {
                        int programs = ShaderProgram.getNumManagedShaderPrograms();
                        String wrongType = GpuShaderSource.readDisk("atmosphere-grade.glsl").replace("u_saturation", "u_saturation.x")
                              .replace("uniform float u_saturation.x", "uniform vec2 u_saturation");
                        var type = manager.apply(Map.of("atmosphere-grade.glsl", wrongType));
                        assertFalse(type.success());
                        assertTrue(type.message().contains("uniform type"), type.message());
                        var link = manager.apply(Map.of("atmosphere.vert", """
                              #version 330 core
                              in vec3 a_position;
                              out vec3 v_uv;
                              void main() { v_uv = a_position; gl_Position = vec4(a_position, 1.0); }
                              """, "atmosphere-composite.frag", composite));
                        assertFalse(link.success());
                        assertTrue(link.message().contains("link"), link.message());
                        assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "Failed edits release candidates");
                    });
                }

                void capture() throws Exception {
                    Path folder = Path.of(System.getProperty("megamek.gpu.screenshots", "build/shader-editor"));
                    Files.createDirectories(folder);
                    var image = ScreenUtils.getFrameBufferPixmap(0, 0,
                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    try { PixmapIO.writePNG(new FileHandle(folder.resolve("shader-live-preview.png").toFile()), image); }
                    finally { image.dispose(); }
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            JFrame window = (JFrame) field(editor, "window");
                            var content = window.getContentPane();
                            BufferedImage snapshot = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                            var graphics = snapshot.createGraphics();
                            try { content.printAll(graphics); }
                            finally { graphics.dispose(); }
                            ImageIO.write(snapshot, "png", folder.resolve("shader-editor.png").toFile());
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }
}
