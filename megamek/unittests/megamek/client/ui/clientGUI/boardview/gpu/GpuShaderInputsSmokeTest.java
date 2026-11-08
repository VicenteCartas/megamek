/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reflection, changing renderer values, array uploads, reloads and editor document lifecycle. */
@Tag("on-demand")
class GpuShaderInputsSmokeTest {
    @Test
    void overridesRestoreSuppliedValuesAndEditorReloadsClosedFiles(@TempDir Path directory) {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    GpuGlsl.detect();
                    manager.run(() -> checkUniforms(manager));
                    SwingUtilities.invokeAndWait(() -> {
                        try { checkDocuments(directory); }
                        catch (Exception error) { throw new AssertionError(error); }
                    });
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkUniforms(GpuShaderManager manager) {
        String vertex = GpuShaderSource.readDisk("effects.vert");
        var extra = new AtomicReference<>("");
        ShaderProgram[] current = new ShaderProgram[1];
        current[0] = GpuShaderManager.program(() -> GpuGlsl.compile("Input fixture", GpuShaderSource.read("effects.vert"), """
              #version 330 core
              layout(location = 0) out vec4 fragColor;
              uniform float u_scalar;
              uniform vec2 u_vector;
              uniform vec3 u_array[2];
              uniform mat4 u_matrix;
              uniform bool u_enabled;
              uniform int u_integer;
              uniform uint u_unsigned;
              uniform uvec2 u_pair;
              uniform float u_unbound = 2.5;
              %s
              void main() {
                  vec3 sum = u_array[0] + u_array[1] + vec3(u_scalar + u_unbound + u_vector.x + u_vector.y
                        + u_matrix[0][0] + float(u_integer) + float(u_unsigned + u_pair.x + u_pair.y) + (u_enabled ? 1.0 : 0.0)%s);
                  fragColor = vec4(sum, 1.0);
              }
              """.formatted(extra.get().isEmpty() ? "" : "uniform float u_added;", extra.get())), next -> current[0] = next);
        try {
            var shader = (GpuShaderUniforms) current[0];
            shader.bind();
            shader.setUniformf("u_scalar", 1);
            shader.setUniformMatrix("u_matrix", new Matrix4().scl(2));
            assertTrue(shader.rows().stream().anyMatch(row -> row.name().equals("u_array[1]")), "Array elements are discovered");
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_scalar", "5"));
            shader.bind();
            shader.setUniformf("u_scalar", 3);
            assertEquals("3.0", row(shader, "u_scalar").supplied());
            assertEquals("5.0", row(shader, "u_scalar").effective());
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_scalar", "8"));
            shader.bind();
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_scalar", ""));
            shader.bind();
            assertEquals("3.0", row(shader, "u_scalar").effective(), "Changing an override must not replace the retained supplied value");

            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_array[1]", "8, 9, 10"));
            shader.bind();
            shader.setUniform3fv("u_array[0]", new float[] { 1, 2, 3, 4, 5, 6 }, 0, 6);
            assertEquals("4.0, 5.0, 6.0", row(shader, "u_array[1]").supplied());
            assertEquals("8.0, 9.0, 10.0", row(shader, "u_array[1]").effective());
            assertThrows(IllegalArgumentException.class, () -> manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_vector", "1")));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_vector", "2, 4"));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_enabled", "true"));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_integer", "-7"));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_unsigned", "4294967295"));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_pair", "3, 4"));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_unbound", "9"));
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_matrix", "1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1"));
            shader.bind();
            shader.setUniformf(shader.getUniformLocation("u_vector"), 11, 12);
            shader.setUniformi("u_integer", 2);
            shader.setUniformMatrix("u_matrix", new Matrix4().scl(3));
            assertEquals("11.0, 12.0", row(shader, "u_vector").supplied());
            assertEquals("2.0, 4.0", row(shader, "u_vector").effective());
            assertEquals("-7", row(shader, "u_integer").effective());
            assertEquals("4294967295", row(shader, "u_unsigned").effective());
            assertEquals("3, 4", row(shader, "u_pair").effective());
            assertEquals("true", row(shader, "u_enabled").effective());
            manager.editInput(new GpuShaderInputs.Edit("Input fixture", null, ""));
            shader.bind();
            assertEquals("2.5", row(shader, "u_unbound").effective(), "An unbound uniform restores its GLSL initializer");
            assertTrue(row(shader, "u_matrix").effective().startsWith("3.0,"));
            assertEquals("4.0, 5.0, 6.0", row(shader, "u_array[1]").effective());

            manager.editInput(new GpuShaderInputs.Edit("Input fixture", "u_scalar", "6"));
            extra.set(" + u_added");
            assertTrue(manager.apply(Map.of("effects.vert", vertex + "\n// input inspection reload\n")).success());
            shader = (GpuShaderUniforms) current[0];
            shader.bind();
            assertEquals("6.0", row(shader, "u_scalar").effective(), "Overrides survive program replacement");
            assertTrue(shader.rows().stream().anyMatch(row -> row.name().equals("u_added")), "New uniforms appear without editing Java");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { GpuShaderManager.dispose(current[0]); }
    }

    private static GpuShaderInputs.Row row(GpuShaderUniforms shader, String name) {
        return shader.rows().stream().filter(row -> row.name().equals(name)).findFirst().orElseThrow();
    }

    private static void checkDocuments(Path directory) throws Exception {
        var sources = new LinkedHashMap<String, GpuShaderSource.FileSource>();
        for (String name : new String[] { "first.frag", "closed.frag", "unopened.frag" }) {
            Path path = directory.resolve(name);
            Files.writeString(path, "original " + name);
            sources.put(name, new GpuShaderSource.FileSource(Files.readString(path), path, true));
        }
        var submitted = new AtomicReference<Map<String, String>>();
        var editor = new GpuShaderEditor(sources, "input test", (drafts, callback) -> {
            submitted.set(drafts); callback.accept(new GpuShaderManager.Result(true, "Applied", null, 1));
        }, callback -> { });
        try {
            checkInputTable(editor.inputsPanel());
            editor.reloadAllFromDisk();
            assertEquals("original unopened.frag", submitted.get().get("unopened.frag"), "Reload also works before opening any tab");
            editor.open("first.frag");
            var tabs = (JTabbedPane) field(editor, "tabs");
            var documents = (Map<?, ?>) field(editor, "documents");
            var first = (RSyntaxTextArea) field(documents.get("first.frag"), "text");
            first.setText("unsaved draft");
            var header = (JPanel) tabs.getTabComponentAt(0);
            ((JButton) Arrays.stream(header.getComponents()).filter(JButton.class::isInstance).findFirst().orElseThrow()).doClick();
            assertEquals(0, tabs.getTabCount());
            editor.open("first.frag");
            assertEquals("unsaved draft", first.getText());
            editor.open("closed.frag");
            var closed = (RSyntaxTextArea) field(documents.get("closed.frag"), "text");
            closed.getActionMap().get("shader-close-tab").actionPerformed(null);
            assertEquals(1, tabs.getTabCount());
            for (var source : sources.entrySet()) { Files.writeString(source.getValue().destination(), "changed " + source.getKey()); }
            editor.reloadAllFromDisk();
            assertEquals("changed first.frag", first.getText());
            assertEquals("changed closed.frag", closed.getText());
            assertEquals("changed unopened.frag", submitted.get().get("unopened.frag"));
            assertEquals(1, tabs.getTabCount(), "Reload does not reopen closed tabs");
            editor.open("closed.frag");
            assertEquals("changed closed.frag", closed.getText());
            var lastSubmitted = submitted.get();
            Files.writeString(directory.resolve("first.frag"), "must not replace the open document after another read fails");
            Files.delete(directory.resolve("unopened.frag"));
            editor.reloadAllFromDisk();
            assertEquals("changed first.frag", first.getText(), "A failed read retains all documents");
            assertEquals(lastSubmitted, submitted.get(), "A failed read does not compile a partial reload");
        } finally { editor.close(); }
    }

    private static void checkInputTable(GpuShaderInputPanel panel) throws Exception {
        var table = (JTable) field(panel, "table");
        var timer = (Timer) field(panel, "refresh");
        Runnable refresh = () -> Arrays.stream(timer.getActionListeners()).forEach(listener -> listener.actionPerformed(null));
        panel.select("weather-particles.frag");
        var original = new GpuShaderInputs.Row("u_clock", GpuShaderValue.Type.FLOAT, "1.0", "1.0", "", null, null, "");
        panel.receive(new GpuShaderInputs.Snapshot(panel.request(), List.of("Weather"), "Weather", List.of(original)));
        refresh.run();
        table.changeSelection(0, 4, false, false);
        var updated = new GpuShaderInputs.Row("u_clock", GpuShaderValue.Type.FLOAT, "2.0", "2.0", "", null, null, "");
        var snapshot = new GpuShaderInputs.Snapshot(panel.request(), List.of("Weather"), "Weather", List.of(updated));
        panel.receive(snapshot);
        refresh.run();
        assertEquals("2.0", table.getValueAt(0, 3));
        assertEquals(0, table.getSelectedRow(), "Live value refresh keeps keyboard navigation on the selected input");
        assertEquals(4, table.getSelectedColumn());
        panel.select("beams.frag");
        panel.receive(snapshot);
        refresh.run();
        assertEquals(0, table.getRowCount(), "A delayed snapshot from the previous file cannot replace the current inputs");
    }
}
