/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import megamek.common.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real file edits and fallback order, independent of the launch directory or an OpenGL context. */
class GpuShaderReloadTest {
    @TempDir
    Path directory;

    @Test
    void reloadAllowsInactiveCachedUniformButRejectsActiveLayoutChanges() {
        var inputs = new GpuShaderManager.Inputs(
              Map.of("u_mode", new GpuShaderManager.Input(GL20.GL_FLOAT, 1)), Map.of());
        var next = mock(ShaderProgram.class);
        // ShaderProgram.fetchUniformLocation(name, false) caches -1 for removed/optimized-out inputs;
        // hasUniform tests that cache's key, not whether the linked program has an active uniform.
        when(next.hasUniform("u_mode")).thenReturn(true);
        when(next.getUniformLocation("u_mode")).thenReturn(-1);
        assertTrue(next.hasUniform("u_mode"));
        assertDoesNotThrow(() -> inputs.check(next));

        when(next.getUniformLocation("u_mode")).thenReturn(3);
        when(next.getUniformType("u_mode")).thenReturn(GL20.GL_FLOAT);
        when(next.getUniformSize("u_mode")).thenReturn(1);
        assertDoesNotThrow(() -> inputs.check(next));
        when(next.getUniformType("u_mode")).thenReturn(GL20.GL_INT);
        assertThrows(IllegalArgumentException.class, () -> inputs.check(next));
        when(next.getUniformType("u_mode")).thenReturn(GL20.GL_FLOAT);
        when(next.getUniformSize("u_mode")).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> inputs.check(next));
    }

    @ParameterizedTest
    @MethodSource("shaderFiles")
    void rereadsOverridesAndCheckoutIncludesWithBundledFallback(String root, String name) throws Exception {
        File data = Configuration.dataDir();
        var previousFiles = Gdx.files;
        Configuration.setDataDir(directory.resolve("data").toFile());
        Gdx.files = spy(new Lwjgl3Files());
        doAnswer(call -> new FileHandle(directory.resolve((String) call.getArgument(0)).toFile()))
              .when(Gdx.files).local(anyString());
        try {
            String resource = "megamek/client/ui/clientGUI/boardview/gpu/" + name;
            String bundled = Gdx.files.classpath(resource).readString("UTF-8");
            assertEquals(bundled, GpuShaderSource.readDisk(name));

            Path checkout = directory.resolve(root).resolve(resource);
            Files.createDirectories(checkout.getParent());
            Files.writeString(checkout, bundled + "\n// checkout edit\n");
            assertEquals(bundled, GpuShaderSource.readDisk(name), "A file is read once, until Reload assets or the editor");
            // Reload assets and the shader editor's saves and reloads forget the read files.
            GpuShaderSource.forget();
            assertTrue(compose(name).contains("// checkout edit"));

            Path override = directory.resolve("data/shaders").resolve(name);
            Files.createDirectories(override.getParent());
            Files.writeString(override, bundled + "\n// first override\n");
            GpuShaderSource.forget();
            assertTrue(compose(name).contains("// first override"));
            Files.writeString(override, bundled + "\n// second override\n");
            GpuShaderSource.forget();
            assertTrue(compose(name).contains("// second override"), "Re-read the same filename without restarting");

            Files.delete(override);
            GpuShaderSource.forget();
            assertTrue(compose(name).contains("// checkout edit"));
            Files.delete(checkout);
            GpuShaderSource.forget();
            assertEquals(bundled, GpuShaderSource.readDisk(name));

            var session = new GpuShaderManager();
            try {
                session.run(() -> {
                    var dependencies = session.capture(() -> compose(name)).files();
                    assertTrue(dependencies.contains(name), "Track reload dependencies");
                    if (bundled.contains("// COLOR_SPACE")) {
                        assertTrue(dependencies.contains("color-space.glsl"), "Track the shared color transfer include");
                        assertTrue(GpuShaderSource.read(name).contains("vec3 toLinear"), "Expand the transfer include for compilation");
                    }
                    assertTrue(session.apply(Map.of(name, bundled + "\n// live draft\n")).success());
                    assertTrue(compose(name).contains("// live draft"), "Composition must read the current editor draft");
                });
            } finally { session.close(); }
        } finally {
            Configuration.setDataDir(data);
            Gdx.files = previousFiles;
            GpuShaderSource.forget();
        }
    }

    @Test
    void sharedIncludesMatchTheirOrderedSourcesAndTrackNestedDrafts() {
        var previousFiles = Gdx.files;
        File data = Configuration.dataDir();
        Configuration.setDataDir(directory.toFile());
        Gdx.files = new Lwjgl3Files();
        var session = new GpuShaderManager();
        try {
            String color = GpuShaderSource.readDisk("color-space.glsl");
            String lighting = GpuShaderSource.readDisk("light-model.glsl").replace("// COLOR_SPACE", color);
            String particle = GpuShaderSource.readDisk("particles.frag")
                  .replace("// LIGHT_MODEL", lighting)
                  .replace("// PARTICLE_APPEARANCE", GpuShaderSource.readDisk("particles-smoke.glsl") + "\n"
                        + GpuShaderSource.readDisk("particles-fire.glsl") + "\n"
                        + GpuShaderSource.readDisk("particles-jet.glsl"));
            session.run(() -> {
                var assembled = session.capture(() -> GpuShaderSource.read("particles.frag"));
                assertEquals(particle, assembled.value(), "Shared injection must preserve the actual GLSL bytes");
                assertEquals(Set.of("particles.frag", "light-model.glsl", "color-space.glsl", "particles-smoke.glsl",
                      "particles-fire.glsl", "particles-jet.glsl"), assembled.files());
                assertTrue(session.apply(Map.of("color-space.glsl", color + "\n// nested draft\n")).success());
                assertTrue(GpuShaderSource.read("particles.frag").contains("// nested draft"));
                assertTrue(session.apply(Map.of("color-space.glsl", "// LIGHT_MODEL")).success());
                var cycle = assertThrows(IllegalStateException.class, () -> GpuShaderSource.read("particles.frag"));
                assertTrue(cycle.getMessage().contains("light-model.glsl -> color-space.glsl -> light-model.glsl"));
                assertTrue(session.apply(Map.of()).success());
                assertEquals(particle, GpuShaderSource.read("particles.frag"), "A failed expansion must not poison retry");
            });
        } finally { session.close(); Gdx.files = previousFiles; Configuration.setDataDir(data); }
    }

    @Test
    void materialCountChangesOnlyDependentProgramKeysIncludingLiveDrafts() {
        var previousFiles = Gdx.files;
        File data = Configuration.dataDir();
        Configuration.setDataDir(directory.toFile());
        Gdx.files = new Lwjgl3Files();
        var session = new GpuShaderManager();
        try {
            String vertex = unitVertex();
            String model = GpuCloudShadow.fragment(GpuUnitShader.fragment());
            assertEquals(materialKey(35, vertex, model), materialKey(36, vertex, model), "Imported models keep their key");
            for (String name : new String[] { "water-surface.frag", "terrain-foliage.frag", "terrain-normal.frag" }) {
                String fragment = GpuTerrain.litFragment(name);
                assertEquals(materialKey(35, vertex, fragment), materialKey(36, vertex, fragment), name);
            }
            String depth = GpuShaderSource.read("terrain-depth.frag");
            assertEquals(materialKey(35, vertex, depth), materialKey(36, vertex, depth),
                  "A final empty depth stage no longer depends on its replaced surface fragment");
            for (String name : new String[] { "terrain-sculpt.frag", "terrain-road.frag", "terrain-vegetation.frag" }) {
                String fragment = name.equals("terrain-vegetation.frag")
                      ? GpuCloudShadow.fragment(GpuShaderSource.terrainLighting(GpuShaderSource.read(name)))
                      : GpuTerrain.litFragment(name);
                assertTrue(fragment.contains("u_finishSurface[FINISH_COUNT]"), name);
                assertNotEquals(materialKey(35, vertex, fragment), materialKey(36, vertex, fragment),
                      "Keep conditional roughness dependencies conservatively: " + name);
            }
            String water = GpuShaderSource.readDisk("water-surface.frag");
            session.run(() -> {
                var original = session.capture(() -> GpuTerrain.litFragment("water-surface.frag"));
                String draft = water.replace("void main() {",
                      "uniform float reviewMaterials[FINISH_COUNT];\nvoid main() {");
                assertNotEquals(water, draft, "Exercise a real editor source change");
                assertTrue(session.apply(Map.of("water-surface.frag", draft)).success());
                var changed = session.capture(() -> GpuTerrain.litFragment("water-surface.frag"));
                assertEquals(original.files(), changed.files(), "Count scoping must not change dependency ownership");
                assertNotEquals(materialKey(35, vertex, changed.value()), materialKey(36, vertex, changed.value()),
                      "A live draft can introduce a metadata dependency into any surface");
                assertTrue(session.apply(Map.of()).success());
                assertEquals(original.value(), GpuTerrain.litFragment("water-surface.frag"), "Restore exact assembly");
            });
            assertNotEquals(materialKey(35, "float values[FINISH_COUNT];", depth),
                  materialKey(36, "float values[FINISH_COUNT];", depth), "Either stage may need the count");
        } finally { session.close(); Gdx.files = previousFiles; Configuration.setDataDir(data); }
    }

    private static String materialKey(int count, String vertex, String fragment) {
        String prefix = GpuShaderSource.finishPrefix(count, count - 3, vertex, fragment);
        return GpuProgramCache.key("fixed driver", GpuGlsl.source(prefix, vertex, 330), GpuGlsl.source(prefix, fragment, 330));
    }

    @Test
    void variantIncludesAreRequiredAndSunVisibilityRemainsExplicit() {
        var previousFiles = Gdx.files;
        File data = Configuration.dataDir();
        Configuration.setDataDir(directory.toFile());
        Gdx.files = new Lwjgl3Files();
        try {
            String rain = GpuShaderSource.read("weather-particles.frag", Map.of("WEATHER_CONDITION", "weather-rain.glsl"));
            assertTrue(rain.contains(GpuShaderSource.read("weather-rain.glsl")));
            assertTrue(!rain.contains("// WEATHER_CONDITION") && !rain.contains("// LIGHT_MODEL"));
            assertThrows(IllegalStateException.class, () -> GpuShaderSource.read("light-model.glsl",
                  Map.of("WEATHER_CONDITION", "weather-rain.glsl")));
            String vertex = GpuShaderSource.read("atmosphere.vert");
            assertTrue(vertex.contains("// SUN_VISIBILITY"), "The caller must retain its capability decision");
            assertEquals(vertex.replace("// SUN_VISIBILITY", GpuShaderSource.read("sun-visibility.glsl")),
                  GpuShaderSource.expand(vertex, Map.of("SUN_VISIBILITY", "sun-visibility.glsl")));
            assertThrows(IllegalStateException.class, () -> GpuShaderSource.replaceOnce("void main() {} void main() {}",
                  "void main()", "void replaced()", "test"));
            String prose = "// LIGHT_MODEL is discussed here\n// LIGHT_MODEL2\n";
            assertEquals(prose, GpuShaderSource.expand(prose, Map.of()), "Only complete marker lines are includes");
        } finally { Gdx.files = previousFiles; Configuration.setDataDir(data); }
    }

    private static Stream<Arguments> shaderFiles() {
        return Stream.of("resources", "megamek/resources").flatMap(root -> Stream.of("light-model.glsl",
              "color-space.glsl", "model.vert", "tree-instances.glsl", "water-spray.glsl", "cloud-surface.glsl",
              "model.frag", "model-surface.glsl", "model-colour-slots.glsl",
              "road-mask.vert", "terrain-blend.vert",
              "ground-surface.glsl", "water-uniforms.glsl", "water-lighting.glsl", "water-pool.glsl", "water-interactions.glsl",
              "terrain-road.frag", "water-fall.frag", "water-spray.frag", "water-cut.frag",
              "weather-particles.vert", "weather-particles.frag", "weather-rain.glsl", "weather-snow.glsl",
              "weather-hail.glsl", "weather-sand.glsl", "particles-smoke.glsl", "particles-fire.glsl", "particles-jet.glsl",
              "terrain-projection.glsl", "terrain-concrete.glsl", "surface-shading.glsl", "lava-lighting.glsl",
              "atmosphere-fov.glsl", "atmosphere-grade.glsl",
              "atmosphere-glare.glsl", "terrain-magma.glsl", "magma-solid.glsl", "magma-flow.glsl", "magma-lighting.glsl",
              "terrain-magma-solid.frag", "terrain-magma-flow.frag", "ocean-finish.glsl",
              "ocean-water-finish.frag", "ocean-lava-finish.frag")
              .map(name -> Arguments.of(root, name)));
    }

    private static String compose(String name) {
        return switch (name) {
            case "tree-instances.glsl" -> GpuTreeInstances.vertex(unitVertex());
            case "water-spray.glsl" -> GpuWaterfall.vertex(unitVertex());
            case "road-mask.vert" -> GpuRoads.vertex(unitVertex());
            case "terrain-blend.vert" -> GpuSurfaceBlend.vertex(unitVertex());
            case "cloud-surface.glsl", "ground-surface.glsl" -> GpuTerrain.litFragment("terrain-normal.frag");
            case "water-uniforms.glsl", "water-lighting.glsl", "water-pool.glsl", "water-interactions.glsl" ->
                  GpuTerrain.litFragment("water-surface.frag");
            case "terrain-road.frag", "water-fall.frag", "water-spray.frag", "water-cut.frag" -> GpuTerrain.litFragment(name);
            case "weather-particles.vert", "weather-particles.frag" -> GpuWeatherParticles.source(name, "weather-rain.glsl");
            case "weather-rain.glsl", "weather-snow.glsl", "weather-hail.glsl" ->
                  GpuWeatherParticles.source("weather-particles.vert", name) + GpuWeatherParticles.source("weather-particles.frag", name);
            case "weather-sand.glsl", "atmosphere-fov.glsl", "atmosphere-grade.glsl", "atmosphere-glare.glsl" ->
                  GpuAtmosphere.fragment("atmosphere-composite.frag");
            case "particles-smoke.glsl", "particles-fire.glsl", "particles-jet.glsl" -> GpuEffectBatch.fragment("particles.frag");
            case "terrain-projection.glsl", "terrain-concrete.glsl", "surface-shading.glsl" ->
                  GpuTerrain.litFragment("terrain-sculpt.frag");
            case "lava-lighting.glsl" -> GpuTerrain.litFragment("terrain-sculpt.frag")
                  + GpuUnitShader.fragment();
            case "terrain-magma.glsl", "magma-solid.glsl" -> GpuTerrain.litFragment("terrain-sculpt.frag")
                  + GpuTerrain.litFragment("terrain-magma-solid.frag");
            case "magma-flow.glsl", "magma-lighting.glsl" -> GpuTerrain.litFragment("terrain-magma-flow.frag");
            case "terrain-magma-solid.frag", "terrain-magma-flow.frag" -> GpuTerrain.litFragment(name);
            case "ocean-finish.glsl" -> GpuOcean.fragment("ocean-water-finish.frag") + GpuOcean.fragment("ocean-lava-finish.frag");
            case "ocean-water-finish.frag", "ocean-lava-finish.frag" -> GpuOcean.fragment(name);
            case "model.frag", "model-surface.glsl", "light-model.glsl" -> GpuUnitShader.fragment();
            default -> unitVertex();
        };
    }

    private static String unitVertex() {
        return GpuUnitShader.linearVertex(GpuShaderSource.read("model.vert"));
    }
}
