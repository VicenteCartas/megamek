/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20C;

/** Material readiness is a draw boundary, including board replacement and changes to lighting flags. */
@Tag("on-demand")
class GpuTerrainPreparationSmokeTest {
    private String programCache;

    @BeforeEach
    void observeSourceCompilation() { programCache = System.setProperty(GpuProgramCache.PROPERTY, "false"); }

    @AfterEach
    void restoreProgramCache() {
        if (programCache == null) { System.clearProperty(GpuProgramCache.PROPERTY); }
        else { System.setProperty(GpuProgramCache.PROPERTY, programCache); }
    }
    /**
     * Inspect installed indexed mesh ranges, not sampled pixels or source-generation labels. Returned counts are
     * blended parts, blended ground triangles, blended cliff triangles, blended shore triangles, and concrete cliffs.
     * Called outside timing ranges; all meshes/materials remain borrowed from the terrain that owns them.
     */
    @SuppressWarnings("unchecked")
    static int[] checkBlendedRoles(GpuTerrain terrain, String label) throws ReflectiveOperationException {
        int[] counts = new int[5];
        Map<Mesh, float[]> vertices = new IdentityHashMap<>();
        Map<Mesh, short[]> indices = new IdentityHashMap<>();
        long sculptType = Attribute.getAttributeType("boardSculpt");
        var chunks = GpuTerrain.class.getDeclaredField("chunks");
        chunks.setAccessible(true);
        for (Object chunk : (List<?>) chunks.get(terrain)) {
            Object resources = GpuTestSupport.field(chunk, "ground");
            var opaque = resources.getClass().getDeclaredField("opaque");
            opaque.setAccessible(true);
            for (var part : GpuTerrainDepth.snapshot((List<ModelInstance>) opaque.get(resources))) {
                if (!part.material.has(sculptType)) { continue; }
                boolean blended = part.material.has(GpuSurfaceBlend.TYPE);
                if (blended) { counts[0]++; }
                var sculpt = part.material.get(FloatAttribute.class, sculptType);
                Mesh mesh = part.meshPart.mesh;
                var color = mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked);
                assertTrue(color != null, label + ": sculpt kind must have packed color storage");
                assertEquals(GL20.GL_TRIANGLES, part.meshPart.primitiveType, label);
                assertEquals(0, part.meshPart.size % 3, label);
                int stride = mesh.getVertexSize() / Float.BYTES, offset = color.offset / Float.BYTES;
                float[] data = vertices.computeIfAbsent(mesh, key ->
                      key.getVertices(new float[key.getNumVertices() * stride]));
                short[] elements = indices.computeIfAbsent(mesh, key -> {
                    short[] result = new short[key.getNumIndices()];
                    if (result.length > 0) { key.getIndices(result); }
                    return result;
                });
                for (int at = part.meshPart.offset; at < part.meshPart.offset + part.meshPart.size; at += 3) {
                    boolean ground = true, cliff = true, shore = true;
                    int[] kinds = new int[3];
                    for (int corner = 0; corner < 3; corner++) {
                        int index = elements.length == 0 ? at + corner : Short.toUnsignedInt(elements[at + corner]);
                        int packed = Float.floatToRawIntBits(data[index * stride + offset]);
                        int kind = packed >>> 16 & 255;
                        kinds[corner] = kind;
                        ground &= kind < 32;
                        cliff &= kind >= 96 && kind < 160;
                        shore &= kind < 32 && (packed >>> 24) < 64;
                    }
                    if (blended) {
                        // A ground-to-cliff triangle would interpolate through excluded plant kinds even though
                        // both endpoint roles are individually legal. Require one legal interval for the whole face.
                        assertTrue(ground || cliff, label + ": blended triangle crosses a legacy kind interval: "
                              + java.util.Arrays.toString(kinds));
                        if (ground) { counts[1]++; }
                        if (cliff) { counts[2]++; }
                        if (shore) { counts[3]++; }
                    }
                    if (cliff && sculpt.value == BoardScene.Surface.CONCRETE.ordinal()) { counts[4]++; }
                }
            }
        }
        System.out.println(label + " packed roles [blend parts, ground, cliff, shore, concrete cliff]="
              + java.util.Arrays.toString(counts));
        return counts;
    }

    @Test
    void preparesOnlyRequiredVariantsBeforeDrawingIncludingBoardAndLightingChanges() throws Exception {
        var captured = new AtomicReference<BoardScene>();
        var uniform = new AtomicReference<BoardScene>();
        var frozen = new AtomicReference<BoardScene>();
        SwingUtilities.invokeAndWait(() -> {
            Board board = Board.createEmptyBoard(5, 4);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 4; y++) {
                    board.setHex(new Coords(x, y), new Hex(x > 2 ? 1 : 0, x == 3 ? "ice:1" : "",
                          x == 4 ? "snow" : x < 2 ? "dirt" : "grass"));
                }
            }
            var game = new Game();
            game.setBoard(board);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { captured.set(source.takeFrame().scene()); }
            Board plain = Board.createEmptyBoard(5, 4);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 4; y++) { plain.setHex(new Coords(x, y), new Hex(0, "", "grass")); }
            }
            game.setBoard(plain);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { uniform.set(source.takeFrame().scene()); }
            // Actual uploaded lake and cascade materials share the same attribute mask but ICE_MODE differs.
            Board ice = Board.createEmptyBoard(5, 4);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 4; y++) {
                    ice.setHex(new Coords(x, y), new Hex(y < 2 ? 3 : 0,
                          x == 2 ? "water:2;ice:1" : "", "snow"));
                }
            }
            game.setBoard(ice);
            BoardSceneryLayouts.convert(game.getBoard());
            try (var source = new GpuMapSource(game, null, null)) { frozen.set(source.takeFrame().scene()); }
        });
        var report = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "terrain-preparation.log").toPath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, "");
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(800, 600);
        new Lwjgl3Application(new ApplicationAdapter() {
            private GpuTerrain terrain;
            private GpuReviewFrame frame;
            private BoardCamera camera;
            private GL20 original;
            private BoardScene scene = uniform.get();
            private int phase;
            private long deadline;
            private long started;
            private boolean drawing;
            private final Set<String> variants = new HashSet<>();
            private final Set<String> sources = new HashSet<>();
            private final Set<String> compiledSources = new HashSet<>();
            private boolean compiledGroundIce;
            private final Set<String> compiledIceModes = new HashSet<>();
            private final Set<String> compiledPalettes = new HashSet<>();

            @Override
            public void create() {
                try {
                    original = Gdx.gl20;
                    Gdx.gl20 = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                          (proxy, method, args) -> {
                              if (method.getName().equals("glShaderSource")) { checkTerrainSource((String) args[1], false); }
                              Object result;
                              try { result = method.invoke(original, args); }
                              catch (InvocationTargetException error) { throw error.getCause(); }
                              // Both compiler paths validate/adopt on this thread; shared-context source uploads
                              // intentionally bypass Gdx globals. Inspect the actual driver source at publication.
                              if (method.getName().equals("glGetShaderiv") && (int) args[1] == GL20.GL_COMPILE_STATUS) {
                                  checkTerrainSource(GL20C.glGetShaderSource((int) args[0]), true);
                              }
                              return result;
                          });
                    terrain = new GpuTerrain();
                    frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                    camera = new BoardCamera();
                    camera.resize(800, 600);
                    nextPhase();
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            private void checkTerrainSource(String text, boolean compiled) throws IOException {
                // Recognize actual ice fragment programs as well as sculpt assembly; unrelated vegetation
                // includes groundToneFor too. The macro plus fragment helper excludes ice vertex sources.
                boolean ice = text.contains("#define iceFlag\n") && text.contains("vec3 iceFinish(");
                // GpuGlsl removes comments before driver upload. Identify executable sculpt code, not its
                // assembly marker, so this observes both synchronous and shared-context compilation.
                if (!ice && !text.contains("vec4 debrisWeights()")) {
                    if (drawing && !compiled && text.contains("vec3 groundToneFor(")) {
                        Files.writeString(report, String.format(Locale.ROOT,
                              "phase %d non-sculpt shared-ground source %08x submitted during draw (%d chars)%n",
                              phase, text.hashCode(), text.length()), StandardOpenOption.APPEND);
                    }
                    return;
                }
                assertFalse(drawing, "A sculpt or ice variant reached visible draw in phase " + phase
                      + " (source " + Integer.toHexString(text.hashCode()) + ")");
                for (String flag : new String[] { "iceFlag", "volcanicFlag", "terrainBlendFlag", "cloudShadowFlag" }) {
                    if (text.contains("#define " + flag)) { variants.add(flag); }
                }
                if (compiled) {
                    String ordinaryFamily = null;
                    for (String line : text.split("\\R")) {
                        if (line.startsWith("#define VOLCANIC_ORDINARY_FAMILY ")) {
                            ordinaryFamily = line.substring("#define VOLCANIC_ORDINARY_FAMILY ".length());
                        }
                        if (line.startsWith("#define ICE_MODE ")) {
                            compiledIceModes.add(line.substring("#define ICE_MODE ".length()));
                        }
                    }
                    compiledGroundIce |= ice && text.contains("uniform float u_iceMode");
                    if (text.contains("#define volcanicFlag") && text.contains("#define terrainBlendFlag")) {
                        compiledPalettes.add(ordinaryFamily == null ? "generic" : ordinaryFamily);
                    }
                }
                boolean first = sources.add(text);
                if (compiled ? compiledSources.add(text) : first) {
                    Files.writeString(report, String.format(Locale.ROOT,
                          "phase %d terrain source %08x %s after %.3f ms; blend=%s ice=%s volcanic=%s cloud=%s shadow=%s%n",
                          phase, text.hashCode(), compiled ? "compiled" : "submitted", (System.nanoTime() - started) / 1_000_000.0,
                          text.contains("#define terrainBlendFlag"), text.contains("#define iceFlag"),
                          text.contains("#define volcanicFlag"), text.contains("#define cloudShadowFlag"),
                          text.contains("#define shadowMapFlag")), StandardOpenOption.APPEND);
                }
            }

            private void nextPhase() throws ReflectiveOperationException {
                started = System.nanoTime();
                deadline = started + 240_000_000_000L;
                camera.fit(scene);
                frame.configure(phase == 2 || phase == 6 ? new BoardAtmosphere.Settings(0, 0, 0, 2, 0, 0,
                      BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, false)
                      : new BoardAtmosphere.Settings(13, phase == 3 ? .7f : 0, 0, 2, 0, 0));
                if (phase == 4) {
                    // Use the same material construction and preparation entry points as uploaded geometry.
                    // Distinct ordinary families/overrides need programs; different slot orders must share them.
                    var blend = GpuTerrain.class.getDeclaredMethod("blendMaterial", GpuSurfaceBlend.Palette.class);
                    var prepare = GpuTerrain.class.getDeclaredMethod("prepareTerrainMaterial", Material.class,
                          VertexAttributes.class);
                    blend.setAccessible(true);
                    prepare.setAccessible(true);
                    int rock = BoardScene.Surface.ROCK.ordinal(), grass = BoardScene.Surface.GRASS.ordinal();
                    int sand = BoardScene.Surface.SAND.ordinal();
                    var palettes = new GpuSurfaceBlend.Palette[] {
                          new GpuSurfaceBlend.Palette(rock, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK,
                                BoardSurfaceBlend.CRUST),
                          new GpuSurfaceBlend.Palette(rock, BoardSurfaceBlend.BANK, rock, rock),
                          new GpuSurfaceBlend.Palette(sand, BoardSurfaceBlend.CRUST, sand, sand),
                          new GpuSurfaceBlend.Palette(grass, grass, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.CRUST),
                          new GpuSurfaceBlend.Palette(grass, grass, BoardSurfaceBlend.CRUST, BoardSurfaceBlend.CRUST),
                          new GpuSurfaceBlend.Palette(BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK,
                                BoardSurfaceBlend.CRUST, BoardSurfaceBlend.CRUST)
                    };
                    for (int i = 0; i < palettes.length; i++) {
                        var material = (Material) blend.invoke(terrain, palettes[i]);
                        if (i == 4) {
                            var cover = material.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                            cover.tiles[1][0] *= .63f;
                            cover.layers[1][0] = cover.layers[2][0];
                        }
                        prepare.invoke(terrain, material, GpuSurfaceBlend.VERTICES);
                    }
                }
            }

            @Override
            public void render() {
                if (failure.get() != null) { return; }
                try {
                    assertTrue(System.nanoTime() < deadline, "Terrain preparation timed out in phase " + phase);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene, camera.camera);
                    terrain.refine(camera.camera);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                    if (!terrain.ready(scene)) { return; }
                    Files.writeString(report, String.format(Locale.ROOT, "phase %d ready after %.3f ms%n",
                          phase, (System.nanoTime() - started) / 1_000_000.0), StandardOpenOption.APPEND);
                    if (phase == 0) {
                        // Floor and material-cache programs share sculpt helpers too. Their number is not a
                        // readiness contract; the first draw below must reuse every required program.
                        assertFalse(variants.contains("terrainBlendFlag"), "A uniform board must not prepare unused blends");
                    }
                    if (phase == 1) {
                        int[] roles = checkBlendedRoles(terrain, "preparation phase " + phase);
                        assertTrue(roles[0] > 0, "The mixed fixture must contain installed blended geometry");
                        // Frozen land and its dry neighbour use the same ground-ice program, selecting their mode
                        // through its uniform. That program must be ready before either material draws.
                        assertTrue(compiledGroundIce, "The shared ground-ice program must be compiled before drawing");
                    }
                    if (phase == 4) {
                        for (String palette : new String[] { "3", "2", "0", "-2", "generic" }) {
                            assertTrue(compiledPalettes.contains(palette),
                                  "Readiness must include each ordinary-family dispatch: " + compiledPalettes);
                        }
                        for (String family : new String[] { "3", "2", "0", "-2" }) {
                            String prefix = "#define VOLCANIC_ORDINARY_FAMILY " + family + "\n";
                            assertEquals(1, compiledSources.stream().filter(text -> text.contains(prefix)
                                        && !text.contains("#define SCULPT_FAMILY ")).count(),
                                  "Slot order must not multiply a family's prepared programs: " + family);
                        }
                    }
                    if (phase >= 5) {
                        assertTrue(compiledIceModes.containsAll(Set.of("3.0", "6.0")),
                              "Actual slab and fall programs must be published before drawing: " + compiledIceModes);
                    }
                    if (phase == 2 || phase == 6) {
                        assertFalse(frame.lighting().sunlight(), "The fixture reached its moonless night phase");
                        assertEquals(0f, frame.lighting().direct().r + frame.lighting().direct().g + frame.lighting().direct().b,
                              "Moonless lighting has no direct source; the same prepared program remains usable");
                    }
                    int preparedSources = compiledSources.size();
                    long drawStarted = System.nanoTime();
                    drawing = true;
                    try {
                        frame.render(terrain, camera, scene);
                        if (phase >= 5) { terrain.renderEditorSection(camera.camera, new Coords(2, 1), ""); }
                    }
                    finally { drawing = false; }
                    assertEquals(preparedSources, compiledSources.size(),
                          "The first same-scene frame must reuse the prepared terrain programs in phase " + phase);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Prepared draw in phase " + phase);
                    Files.writeString(report, String.format(Locale.ROOT,
                          "phase %d first draw wall %.3f ms; prepared sources %d; after draw %d%n",
                          phase, (System.nanoTime() - drawStarted) / 1_000_000.0, preparedSources, compiledSources.size()),
                          StandardOpenOption.APPEND);
                    if (++phase == 7) {
                        assertTrue(variants.containsAll(Set.of("iceFlag", "volcanicFlag", "terrainBlendFlag", "cloudShadowFlag")),
                              "The fixture must exercise every required variant: " + variants);
                        Gdx.app.exit();
                    } else {
                        if (phase == 1) { scene = captured.get(); terrain.boardChanged(); }
                        if (phase == 4) { scene = GpuMagmaSmokeTest.scene(false); terrain.boardChanged(); }
                        if (phase == 5) { scene = frozen.get(); terrain.boardChanged(); }
                        nextPhase();
                    }
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void dispose() {
                if (frame != null) { frame.dispose(); }
                if (terrain != null) { terrain.dispose(); }
                if (original != null) { Gdx.gl20 = original; }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain variant preparation", failure.get()); }
    }
}
