/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;

/**
 * macOS's OpenGL gives each shader stage only 16 texture units, and a program that uses more fails to link there, so
 * the board would not draw at all. Windows and Linux drivers allow 32, so nothing else catches an overrun before a Mac
 * user does. Render the battle view over boards and conditions that reach every terrain, liquid and weather variant, and
 * an editor, whose palette reaches the rest, and count the samplers each compiled program actually uses.
 */
@Tag("on-demand")
class GpuSamplerBudgetSmokeTest {
    /** Apple's OpenGL 4.1 limit per stage; counting a whole program against it is conservative. */
    private static final int MAC_TEXTURE_UNITS = 16;
    /** What a fragment stage may use: that limit, less the headroom a new feature takes (docs/gpu-shaders.md). */
    private static final int FRAGMENT_BUDGET = 13;
    private static final Set<Integer> SAMPLERS = Set.of(0x8B5E, 0x8B5F, 0x8B60, 0x8B62, 0x8B63, 0x8DC1, 0x8DC2, 0x8DC4,
          0x8DC5, 0x8DCA, 0x8DCB, 0x8DCC, 0x8DCF, 0x8DD0, 0x8DD2, 0x8DD3, 0x8DD4, 0x8DD7, 0x8DD8, 0x9108, 0x9109,
          0x910A, 0x910B, 0x910C, 0x910D);

    private record Scene(String board, Weather weather, Light light, Fog fog) { }

    private static final List<Scene> SCENES = List.of(
          new Scene("Map Pack Volcanic/16x17 Dome Vent 2.board", Weather.CLEAR, Light.DAY, Fog.FOG_NONE),
          new Scene("Map Pack Volcanic/16x17 Dome Vent 2.board", Weather.HEAVY_RAIN, Light.MOONLESS, Fog.FOG_NONE),
          new Scene("Map Pack Savannahs/16x17 Mountain Lake (Savannah).board", Weather.MOD_RAIN, Light.DAY, Fog.FOG_LIGHT),
          new Scene("unofficial/Drewbacca/16x17 Fire And Ice 2.board", Weather.HEAVY_SNOW, Light.DAY, Fog.FOG_NONE),
          new Scene("unofficial/Vamp/Elevated Highway.board", Weather.CLEAR, Light.DUSK_DAWN, Fog.FOG_NONE),
          new Scene("unofficial/Drewbacca/16x17 Jungle River 1.board", Weather.CLEAR, Light.DAY, Fog.FOG_NONE),
          new Scene("Shrapnel 13/16x17 Badlands 2 (Hazardous Liquid).board", Weather.CLEAR, Light.DAY, Fog.FOG_NONE));

    @Test
    void everyBattleViewProgramFitsMacTextureUnits() throws Exception {
        var over = new TreeMap<String, Integer>();
        var used = new TreeMap<String, Integer>();
        BoardScene ice = GpuIceSmokeTest.scene(2), volcanicIce = volcanicIceScene();
        var failure = new AtomicReference<Throwable>();
        var separable = new AtomicBoolean();
        boolean savedOcclusion = GUIPreferences.getInstance().getBoardAmbientOcclusion();
        String savedQuality = GUIPreferences.getInstance().getBoardQuality();
        // High uses the most samplers: Medium only leaves reads out, and has no ambient occlusion.
        GUIPreferences.getInstance().setBoardQuality("HIGH");
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    System.out.println("Sampler audit GL_RENDERER=" + Gdx.gl.glGetString(GL20.GL_RENDERER)
                          + "; GL_VERSION=" + Gdx.gl.glGetString(GL20.GL_VERSION));
                    // A fragment stage is counted alone, linked as a separable program (fragmentSamplers).
                    var capabilities = GL.getCapabilities();
                    separable.set(capabilities.OpenGL41 || capabilities.GL_ARB_separate_shader_objects);
                    if (!separable.get()) { return; }
                    var limit = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
                    for (int name : new int[] {GL20.GL_MAX_TEXTURE_IMAGE_UNITS,
                          GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS}) {
                        limit.clear();
                        Gdx.gl.glGetIntegerv(name, limit);
                        System.out.println("Texture limit 0x" + Integer.toHexString(name) + "=" + limit.get(0));
                    }
                    // Ambient occlusion off, then on: its sampler must fit too (GpuAmbientOcclusion.prefix).
                    for (boolean occlusion : new boolean[] { false, true }) {
                        GUIPreferences.getInstance().setBoardAmbientOcclusion(occlusion);
                        for (Scene scene : SCENES) { review(scene, used, over); }
                        reviewTerrain(ice, "GpuIceSmokeTest.scene(2)", occlusion, used, over);
                        reviewTerrain(volcanicIce, "cooled crust / rock / frozen land", occlusion, used, over);
                    }
                    GUIPreferences.getInstance().setBoardAmbientOcclusion(true);
                    reviewEditor(used, over);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GUIPreferences.getInstance().setBoardAmbientOcclusion(savedOcclusion);
                    GUIPreferences.getInstance().setBoardQuality(savedQuality);
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Sampler budget review", failure.get()); }
        assumeTrue(separable.get(), "Counting a fragment stage alone needs OpenGL 4.1 or ARB_separate_shader_objects");
        // The headroom left for new maps: every variant with the units it uses, the fullest first.
        used.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
              .forEach(program -> System.out.println(program.getValue() + " " + program.getKey()));
        assertTrue(used.keySet().stream().anyMatch(program -> program.contains("volcanicFlag")),
              "Reach the volcanic terrain programs: " + used.keySet());
        assertTrue(used.keySet().stream().anyMatch(program -> program.contains("ambientOcclusionFlag")),
              "Reach the programs with ambient occlusion: " + used.keySet());
        for (String feature : List.of("iceFlag", "roadCoatFlag", "u_surfaceLayers", "u_fullSizeData", "u_terrainData", "u_roadData", "u_environmentLut")) {
            assertTrue(used.keySet().stream().anyMatch(program -> program.contains(feature)),
                  "Reach the material-array consumer: " + feature);
        }
        // Individual ice and volcanic shaders are insufficient: this actual material must link both together.
        var combined = used.entrySet().stream().filter(program -> program.getKey().contains("iceFlag")
              && program.getKey().contains("volcanicFlag") && program.getKey().contains("u_terrainData")).toList();
        assertTrue(!combined.isEmpty(), "Reach the combined sculpt ice/volcanic material: " + used.keySet());
        for (var program : combined) {
            for (String sampler : List.of("u_terrainLayers", "u_terrainData", "u_fullSizeColor", "u_fullSizeData",
                  "u_environmentLut")) {
                assertTrue(program.getKey().contains(sampler), "Combined program must sample " + sampler + ": " + program);
            }
        }
        assertTrue(over.isEmpty(), "Programs over " + FRAGMENT_BUDGET + " fragment samplers or macOS's " + MAC_TEXTURE_UNITS
              + " texture units: " + over);
    }

    private static void review(Scene scene, Map<String, Integer> used, Map<String, Integer> over) throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/" + scene.board()), false, true);
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                var conditions = fixture.game.getPlanetaryConditions();
                conditions.setWeather(scene.weather());
                conditions.setLight(scene.light());
                conditions.setFog(scene.fog());
                fixture.source.refresh();
            });
            var view = new GpuBattleView(fixture.source);
            try {
                view.create();
                // The board arrives asynchronously: wait for its terrain, then for the loading screen to go.
                GpuCamouflageReview.renderReady(view);
                GpuBoardTestUi.present(view);
                for (int frame = 0; frame < 20; frame++) { view.render(); }
                scan(scene, used, over);
            } finally {
                view.dispose();
            }
        } catch (Throwable error) {
            throw new AssertionError(scene.toString(), error);
        }
    }

    /** An editor's programs, each kind of its palette's among them, linked behind its loading stage. */
    private static void reviewEditor(Map<String, Integer> used, Map<String, Integer> over) throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.open(Board.createEmptyBoard(16, 17), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        try {
            var view = new GpuBattleView(source);
            try {
                view.create();
                // A cold cache takes minutes over the palette's programs; this checks what they read, not how fast.
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
                do {
                    assertTrue(System.nanoTime() < deadline, "The editor must finish loading");
                    view.render();
                } while (GpuBoardTestUi.loading(view) || GpuBoardTestUi.busy(view));
                scan(new Scene("the editor", Weather.CLEAR, Light.DAY, Fog.FOG_NONE), used, over);
            } finally { view.dispose(); }
        } finally { SwingUtilities.invokeAndWait(source::close); }
    }

    /** A natural rock strip blends toward crust on one edge and carries its frozen neighbour's ice fringe. */
    private static BoardScene volcanicIceScene() throws Exception {
        var task = new FutureTask<BoardScene>(() -> {
            var board = Board.createEmptyBoard(5, 3);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 3; y++) {
                    board.setHex(new Coords(x, y), new Hex(0, x == 0 ? "magma:1" : x > 1 ? "ice:1" : "", "rock"));
                }
            }
            var game = new Game();
            BoardSceneryLayouts.convert(board);
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    /** Render the actual captured material combinations, rather than manufacturing shader defines. */
    private static void reviewTerrain(BoardScene scene, String name, boolean occlusion, Map<String, Integer> used,
          Map<String, Integer> over) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, .5f, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try {
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.fit(scene);
            frame.setAmbientOcclusion(occlusion);
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            while (terrain.refine(camera.camera) | terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, () -> "Sampler specimen must settle: " + terrain.buildDetails());
                Thread.sleep(5);
            }
            frame.render(terrain, camera, scene);
            scan(new Scene(name, Weather.CLEAR, Light.DAY, Fog.FOG_NONE), used, over);
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static void scan(Scene scene, Map<String, Integer> used, Map<String, Integer> over) throws Exception {
        var registry = ShaderProgram.class.getDeclaredField("shaders");
        registry.setAccessible(true);
        var programs = new ArrayList<ShaderProgram>();
        for (Array<ShaderProgram> list : ((ObjectMap<?, Array<ShaderProgram>>) registry.get(null)).values()) {
            for (ShaderProgram program : list) { programs.add(program); }
        }
        assertTrue(programs.size() > 5, "The battle view compiled its programs");
        System.out.println(scene + ": " + programs.size() + " live programs");
        IntBuffer count = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
        IntBuffer size = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
        IntBuffer type = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
        for (ShaderProgram program : programs) {
            if (!program.isCompiled()) { continue; }
            Gdx.gl20.glGetProgramiv(program.getHandle(), GL20.GL_ACTIVE_UNIFORMS, count);
            int units = 0;
            var names = new TreeSet<String>();
            for (int i = 0; i < count.get(0); i++) {
                size.clear();
                type.clear();
                String name = Gdx.gl20.glGetActiveUniform(program.getHandle(), i, size, type);
                if (SAMPLERS.contains(type.get(0))) {
                    units += size.get(0);
                    names.add(name);
                }
            }
            String label = (program instanceof GpuShaderUniforms named ? named.name : "libGDX") + " " + flags(program)
                  + " " + names;
            // A sampler only the vertex stage reads takes no unit of the fragment stage.
            int fragment = fragmentSamplers(program);
            used.merge(label, fragment, Math::max);
            // Road ground reads two array pairs, its own 1024-texel and the sculpt 512-texel maps, and cached ground on a
            // biome board its pages' three arrays, which one array would limit to half the pages: with the crystal stage,
            // in an editor or on a board with crystals, at High quality with ambient occlusion, each is one over.
            boolean atCrystalLimit = label.contains("crystalTransmissionFlag") && label.contains("ambientOcclusionFlag")
                  && (label.contains("roadCoatFlag") || label.contains("materialCachedFlag") && label.contains("biomeMaskFlag"));
            int budget = FRAGMENT_BUDGET + (atCrystalLimit ? 1 : 0);
            if (fragment > budget || units > MAC_TEXTURE_UNITS) { over.merge(label + " in " + scene, fragment, Math::max); }
        }
    }

    /** The texture units a program's fragment stage uses, linked as a program of its own. */
    private static int fragmentSamplers(ShaderProgram program) {
        int shader = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER), alone = GL20C.glCreateProgram();
        try {
            GL20C.glShaderSource(shader, program.getFragmentShaderSource());
            GL20C.glCompileShader(shader);
            GL41C.glProgramParameteri(alone, GL41C.GL_PROGRAM_SEPARABLE, GL20C.GL_TRUE);
            GL20C.glAttachShader(alone, shader);
            GL20C.glLinkProgram(alone);
            assertTrue(GL20C.glGetProgrami(alone, GL20C.GL_LINK_STATUS) != 0, GL20C.glGetProgramInfoLog(alone));
            int units = 0;
            IntBuffer size = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
            IntBuffer type = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
            for (int i = 0; i < GL20C.glGetProgrami(alone, GL20C.GL_ACTIVE_UNIFORMS); i++) {
                GL20C.glGetActiveUniform(alone, i, size, type);
                if (SAMPLERS.contains(type.get(0))) { units += size.get(0); }
            }
            return units;
        } finally {
            GL20C.glDeleteProgram(alone);
            GL20C.glDeleteShader(shader);
        }
    }

    /** The variant's feature flags, which identify a program together with the samplers it reads. */
    private static String flags(ShaderProgram program) {
        var flags = new TreeSet<String>();
        for (String line : (program.getVertexShaderSource() + "\n" + program.getFragmentShaderSource()).split("\n")) {
            if (line.startsWith("#define ") && line.trim().endsWith("Flag")) { flags.add(line.substring(8).trim()); }
        }
        return flags.toString();
    }
}
