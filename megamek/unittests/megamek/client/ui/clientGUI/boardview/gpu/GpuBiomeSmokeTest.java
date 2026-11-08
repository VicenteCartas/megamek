/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual shader, instancing, resource ownership and edit/LOD checks; screenshots are native renderer output. */
@Tag("on-demand")
class GpuBiomeSmokeTest {
    @Test
    void connectedFieldsAndMarshRenderAcrossLodsAndSurviveTerrainEdits() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "fields-marsh");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var originalLod = TerrainLod.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                int maskHandle = 0, sedgeHandle = 0;
                try {
                    BoardScenes.tune(.94f, true);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    var plants = (GpuBiomeVegetation) GpuTestSupport.field(terrain, "biomeVegetation");
                    var cover = (GpuGroundCover) GpuTestSupport.field(terrain, "groundCover");
                    var mask = (GpuBiomeSurface) GpuTestSupport.field(terrain, "biomes");
                    StringBuilder metrics = new StringBuilder("Native 1280x960 fields/marsh checks\n");
                    metrics.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
                    for (var kind : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH, BoardScene.Biome.QUICKSAND, BoardScene.Biome.MUD)) {
                        var scene = scene(kind);
                        boolean vegetation = kind == BoardScene.Biome.FIELD || kind == BoardScene.Biome.MARSH;
                        // A field's plants are the grass engine's; this renderer draws the reeds.
                        boolean reeds = kind == BoardScene.Biome.MARSH;
                        camera.setIsometric(true);
                        long previousTriangles = Long.MAX_VALUE;
                        for (int lod = 0; lod < 3; lod++) {
                            // Measure pure tiers outside the overlapping transition bands.
                            camera.camera.zoom = BoardGeometry.width() / new float[] { 560, 160, 48 }[lod];
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            settle(terrain, plants, frame, camera, scene);
                            GpuReviewFrame.save(new File(output, kind + "-LOD" + lod + ".png"));
                            if (vegetation) {
                                boolean drawn = false;
                                if (reeds) {
                                    for (Object batch : batches(plants)) {
                                        drawn |= (int) GpuTestSupport.field(batch, "lod") == lod && !((List<?>) GpuTestSupport.field(batch, "current")).isEmpty();
                                    }
                                } else {
                                    for (Object chunk : ((Map<?, ?>) GpuTestSupport.field(cover, "chunks")).values()) {
                                        for (String layer : List.of("wheat", "maize")) {
                                            for (Object batch : (Object[]) GpuTestSupport.field(GpuTestSupport.field(chunk, layer), "batches")) {
                                                drawn |= !((List<?>) GpuTestSupport.field(batch, "current")).isEmpty();
                                            }
                                        }
                                    }
                                }
                                assertTrue(drawn, kind + " LOD" + lod + " must submit its tier");
                            }
                            long triangles = 0;
                            int draws = 0, roots = 0;
                            for (Object batch : batches(plants)) {
                                if (((List<?>) GpuTestSupport.field(batch, "current")).isEmpty()) { continue; }
                                var mesh = (GpuInstancedMesh) GpuTestSupport.field(batch, "mesh");
                                if (mesh == null) { continue; }
                                int instances = ((com.badlogic.gdx.utils.FloatArray) GpuTestSupport.field(batch, "data")).size / 4;
                                draws++; roots += instances;
                                triangles += (long) mesh.getNumIndices() / 3 * instances;
                            }
                            assertEquals(reeds ? 1 : 0, draws);
                            // Budget the rendered 3x3 fixture (including its fringe), not just one template's indices.
                            long budget = reeds ? new long[] { 10_476, 2_510, 314 }[lod] : 0;
                            assertTrue(triangles <= budget, kind + " LOD" + lod + " exceeds its plant triangle budget: " + triangles);
                            assertTrue(triangles < previousTriangles || !reeds,
                                  kind + " LOD" + lod + ": " + triangles + " triangles after " + previousTriangles);
                            previousTriangles = triangles;
                            long uploads = plants.uploads() + cover.uploads();
                            for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                            assertEquals(uploads, plants.uploads() + cover.uploads(),
                                  "Stationary views must not upload plant instances again");
                            metrics.append(kind).append(" LOD").append(lod).append(": ").append(draws).append(" draws, ")
                                  .append(roots).append(" instances, ").append(triangles).append(" triangles\n");
                            if (vegetation) {
                                grassMetrics(terrain, camera, metrics);
                                try (var timings = new GpuStageTimings()) {
                                    for (int i = 0; i < 60; i++) {
                                        timings.beginFrame(i >= 12);
                                        timings.stage("whole-frame");
                                        frame.render(terrain, camera, scene);
                                        timings.stage(null);
                                        // Pace native queries without attributing the swap/wait to renderer time.
                                        org.lwjgl.glfw.GLFW.glfwSwapBuffers(((com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics)
                                              Gdx.graphics).getWindow().getWindowHandle());
                                    }
                                    GpuReviewFrame.save(new File(output, kind + "-LOD" + lod + ".png"));
                                    timings.appendReport(metrics, kind + " LOD" + lod);
                                }
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Files.writeString(new File(output, "metrics.txt").toPath(), metrics.toString());
                        }
                        if (vegetation) {
                            // Distant canopies/reeds must remain visible from above and after rotation.
                            camera.setIsometric(false);
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            settle(terrain, plants, frame, camera, scene);
                            GpuReviewFrame.save(new File(output, kind + "-LOD2-top.png"));
                            camera.orbit(135, 68);
                            settle(terrain, plants, frame, camera, scene);
                            GpuReviewFrame.save(new File(output, kind + "-LOD2-orbit.png"));
                            camera.setIsometric(true);
                        }
                        camera.camera.zoom = .075f;
                        camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                        settle(terrain, plants, frame, camera, scene);
                        GpuReviewFrame.save(new File(output, kind + "-close.png"));
                        if (kind == BoardScene.Biome.FIELD) {
                            for (float[] view : new float[][] { { 0, 0 }, { 110, 72 }, { 200, 72 }, { 45, 55 } }) {
                                camera.setIsometric(false);
                                camera.orbit(view[0], view[1]);
                                camera.camera.zoom = BoardGeometry.width() / 2400;
                                camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                                settle(terrain, plants, frame, camera, scene);
                                GpuReviewFrame.save(new File(output, "FIELD-registered-" + (int) view[0] + ".png"));
                            }
                            camera.setIsometric(true);
                            camera.camera.zoom = .075f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            settle(terrain, plants, frame, camera, scene);
                        }
                        if (kind == BoardScene.Biome.MARSH) {
                            camera.tilt(68);
                            frame.configure(new BoardAtmosphere.Settings(16, 0, 0,
                                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "MARSH-oblique.png"));
                            camera.setIsometric(true);
                            frame.configure(new BoardAtmosphere.Settings(13, 0, 0,
                                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                            frame.render(terrain, camera, scene);
                        }
                        if (vegetation) {
                            var before = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
                            long uploads = plants.uploads();
                            try {
                                frame.configure(new BoardAtmosphere.Settings(13, 0, 0,
                                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
                                      new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 1, 35)));
                                terrain.animate(1.25f, List.of());
                                frame.render(terrain, camera, scene);
                                assertTrue(changed(before) > 100, "Wind must change plant silhouettes without rebuilding roots");
                                assertEquals(uploads, plants.uploads());
                                GpuReviewFrame.save(new File(output, kind + "-wind.png"));
                            } finally { before.dispose(); }
                            var daylight = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
                            try {
                                frame.configure(new BoardAtmosphere.Settings(19, 0, 0,
                                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
                                      new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 1, 35)));
                                frame.render(terrain, camera, scene);
                                assertTrue(changed(daylight) > 1000, "Plants and wet surfaces must respond to the scene's lighting");
                                assertEquals(uploads, plants.uploads());
                                GpuReviewFrame.save(new File(output, kind + "-evening.png"));
                            } finally { daylight.dispose(); }
                            frame.configure(new BoardAtmosphere.Settings(13, 0, 0,
                                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                        }
                    }
                    camera.camera.zoom = .16f;
                    camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                    var marsh = scene(BoardScene.Biome.MARSH);
                    for (var lod : TerrainLod.values()) {
                        TerrainLod.tune(switch (lod) {
                            case FULL -> new TerrainLod.Tuning(1, 1);
                            case MEDIUM -> new TerrainLod.Tuning(100_000, 1);
                            case COARSE -> new TerrainLod.Tuning(100_000, 1000);
                            case DISTANT -> new TerrainLod.Tuning(100_000, 100_000);
                        });
                        settle(terrain, plants, frame, camera, marsh);
                        assertEquals(lod, GpuTestSupport.field(((List<?>) GpuTestSupport.field(terrain, "chunks")).getFirst(), "lod"));
                        GpuReviewFrame.save(new File(output, "MARSH-terrain-" + lod + ".png"));
                        for (var kind : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH)) {
                            for (boolean upper : new boolean[] { false, true }) {
                                var slope = BoardScenes.scene(c -> BoardScenes.tile(c,
                                      (c.getY() < 4) == upper ? kind : BoardScene.Biome.NONE, c.getY() < 4 ? 1 : 0));
                                camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                                settle(terrain, plants, frame, camera, slope);
                                GpuReviewFrame.save(new File(output, kind + "-slope-" + (upper ? "upper-" : "lower-") + lod + ".png"));
                                if (lod == TerrainLod.FULL && !upper) { slopeMaterialResponds(terrain, mask, frame, camera, slope); }
                            }
                        }
                    }
                    var clear = scene(BoardScene.Biome.NONE);
                    settle(terrain, plants, frame, camera, clear);
                    assertEquals(0, mask.width(), "Removing the terrain must clear the material mask");
                    for (Object batch : batches(plants)) {
                        assertTrue(((List<?>) GpuTestSupport.field(batch, "current")).isEmpty(), "No stale crops/reeds after editing");
                    }
                    TerrainLod.tune(originalLod);
                    for (var kind : List.of(BoardScene.Biome.MARSH, BoardScene.Biome.QUICKSAND, BoardScene.Biome.MUD)) {
                        for (int depth = 0; depth <= 1; depth++) {
                            var bank = shoreline(kind, depth);
                            camera.camera.zoom = .14f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            settle(terrain, plants, frame, camera, bank);
                            String suffix = kind + (depth == 0 ? "-shallow" : "");
                            GpuReviewFrame.save(new File(output, "water-" + suffix + ".png"));
                            frame.render(terrain, camera, bank, false);
                            GpuReviewFrame.save(new File(output, "bed-" + suffix + ".png"));
                            if (kind == BoardScene.Biome.MARSH && depth == 0) {
                                for (int lod = 0; lod < 3; lod++) {
                                    camera.camera.zoom = BoardGeometry.width() / new float[] { 560, 160, 48 }[lod];
                                    camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                                    settle(terrain, plants, frame, camera, bank);
                                    GpuReviewFrame.save(new File(output, "shore-MARSH-LOD" + lod + ".png"));
                                    frame.render(terrain, camera, bank, false);
                                    GpuReviewFrame.save(new File(output, "shore-bed-MARSH-LOD" + lod + ".png"));
                                }
                            }
                        }
                    }
                    var contacts = BoardScenes.scene(c -> BoardScenes.tile(c,
                          c.getX() < 4 ? BoardScene.Biome.MARSH
                                : c.getY() < 4 ? BoardScene.Biome.FIELD : BoardScene.Biome.MUD, 0));
                    camera.camera.zoom = .12f;
                    camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                    settle(terrain, plants, frame, camera, contacts);
                    GpuReviewFrame.save(new File(output, "field-marsh-mud.png"));
                    var fire = fireAndIce();
                    var focus = fire.tiles().stream().filter(t -> t.biome() == BoardScene.Biome.MARSH)
                          .max(java.util.Comparator.comparingInt(t -> neighbors(fire, t.coords()))).orElseThrow();
                    camera.camera.zoom = .28f;
                    camera.center(BoardGeometry.center(focus.coords(), focus.elevation()));
                    settle(terrain, plants, frame, camera, fire);
                    assertTrue(fire.tiles().stream().filter(t -> t.biome() == BoardScene.Biome.MARSH).count() > 5);
                    GpuReviewFrame.save(new File(output, "Fire-And-Ice-2.png"));
                    camera.setIsometric(false);
                    settle(terrain, plants, frame, camera, fire);
                    GpuReviewFrame.save(new File(output, "Fire-And-Ice-2-top.png"));
                    metrics.append("Fire And Ice 2 swamp focus: ").append(focus.coords()).append('\n');
                    metrics.append("Maximum active texture samplers: ").append(samplerBudget(terrain)).append('\n');
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                    settle(terrain, plants, frame, camera, contacts);
                    // The reeds share one cutout across their batches.
                    var texture = (Texture) GpuTestSupport.field(plants, "sedge");
                    assertTrue(texture.getWidth() <= 512 && texture.getHeight() <= 512, "The shared cutout has a bounded upload size");
                    assertEquals(Texture.TextureFilter.MipMapLinearLinear, texture.getMinFilter(),
                          "The reeds' cutout must retain trilinear mipmaps");
                    for (Object batch : batches(plants)) {
                        var instance = (ModelInstance) GpuTestSupport.field(batch, "instance");
                        if (instance == null) { continue; }
                        var diffuse = instance.materials.first().get(TextureAttribute.class, TextureAttribute.Diffuse);
                        assertEquals(texture, diffuse.textureDescription.texture, "Every LOD must reuse the reeds' cutout");
                    }
                    metrics.append("sedge cutout upload: ").append(texture.getWidth()).append('x').append(texture.getHeight())
                          .append(" RGBA8 plus mipmaps\n");
                    Files.writeString(new File(output, "metrics.txt").toPath(), metrics.toString());
                    maskHandle = mask.lights().texture().getTextureObjectHandle();
                    sedgeHandle = texture.getTextureObjectHandle();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose();
                    if (maskHandle != 0 && org.lwjgl.opengl.GL11.glIsTexture(maskHandle)) {
                        failure.compareAndSet(null, new AssertionError("Biome texture leaked after disposal"));
                    }
                    if (sedgeHandle != 0 && org.lwjgl.opengl.GL11.glIsTexture(sedgeHandle)) {
                        failure.compareAndSet(null, new AssertionError("Sedge texture leaked after disposal"));
                    }
                    frame.dispose();
                    BoardGeometry.tune(original);
                    TerrainLod.tune(originalLod);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Fields and marsh native renderer", failure.get()); }
    }

    private static int neighbors(BoardScene scene, Coords coords) {
        int count = 0;
        for (int direction = 0; direction < 6; direction++) {
            var tile = scene.tile(coords.translated(direction));
            if (tile != null && tile.biome() == BoardScene.Biome.MARSH) { count++; }
        }
        return count;
    }

    private static void settle(GpuTerrain terrain, GpuBiomeVegetation plants, GpuReviewFrame frame,
          BoardCamera camera, BoardScene scene) throws Exception {
        terrain.update(scene, camera.camera);
        frame.prepare(terrain, camera, scene);
        GpuReviewFrame.settle(terrain, null, scene, camera);
        // Plants are installed with the terrain; the first frame after settling draws them all.
        frame.render(terrain, camera, scene);
    }

    private static BoardScene scene(BoardScene.Biome kind) {
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              c.getX() >= 3 && c.getX() <= 5 && c.getY() >= 3 && c.getY() <= 5 ? kind : BoardScene.Biome.NONE, 0));
    }

    private static BoardScene fireAndIce() {
        var board = BoardFile.loadSheet(new File("data/boards/unofficial/Drewbacca/16x17 Fire And Ice 2.board"), false, true);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var coords = new Coords(x, y);
                var hex = board.getHex(coords);
                tiles.add(new BoardScene.Tile(coords, hex.getLevel(), hex.containsTerrain(Terrains.WATER)
                      ? hex.terrainLevel(Terrains.WATER) : -1, hex.containsTerrain(Terrains.ICE),
                      hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() : 0,
                      BoardFeatures.surface(hex), null, null,
                      BoardFeatures.capture(hex, coords, Map.of()), List.of(), BoardLiquid.capture(hex), null,
                      BoardFeatures.detailedGround(hex, Map.of()), BoardRoad.capture(hex), BoardFireSmoke.NONE, BoardFeatures.biome(hex)));
            }
        }
        return new BoardScene(2, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Every batch of a plant renderer: per-chunk batches in board order, then any board-wide distant tiers. */
    static List<Object> batches(Object renderer) throws Exception {
        var chunks = new TreeMap<Coords, Object>(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY));
        chunks.putAll((Map<Coords, ?>) GpuTestSupport.field(renderer, "chunks"));
        var result = new ArrayList<Object>();
        for (Object chunk : chunks.values()) {
            if (renderer instanceof GpuBiomeVegetation) { result.addAll(List.of((Object[]) GpuTestSupport.field(chunk, "batches"))); }
            // The grass engine's chunks hold a layer per kind of blade: grass, then the crops' plants.
            else {
                for (String layer : List.of("grass", "wheat", "maize")) {
                    result.addAll(List.of((Object[]) GpuTestSupport.field(GpuTestSupport.field(chunk, layer), "batches")));
                }
            }
        }
        if (renderer instanceof GpuBiomeVegetation) { result.add(GpuTestSupport.field(renderer, "distantReeds")); }
        return result;
    }

    private static void grassMetrics(GpuTerrain terrain, BoardCamera camera, StringBuilder metrics) throws Exception {
        int draws = 0, roots = 0;
        long triangles = 0;
        for (Object batch : batches(GpuTestSupport.field(terrain, "groundCover"))) {
            // Terrain skips grass at distant scales without clearing the reusable instance buffers.
            if (!GpuGroundCover.visibleAtScale(camera.camera, true, false)) { break; }
            if (((List<?>) GpuTestSupport.field(batch, "current")).isEmpty()) { continue; }
            var mesh = (GpuInstancedMesh) GpuTestSupport.field(batch, "mesh");
            if (mesh == null) { continue; }
            int count = mesh.drawInstances;
            draws++; roots += count;
            triangles += (long) mesh.getNumIndices() / 3 * count;
        }
        metrics.append("  ordinary grass elsewhere in this view: ").append(draws).append(" draws, ")
              .append(roots).append(" blades, ").append(triangles).append(" triangles\n");
    }

    /** Removing only the biome mask must affect actual bank faces, not just their adjoining flat hexes. */
    private static void slopeMaterialResponds(GpuTerrain terrain, GpuBiomeSurface mask, GpuReviewFrame frame,
          BoardCamera camera, BoardScene scene) throws Exception {
        // Measure the ground material itself; taller cutouts, slabs and plants can otherwise hide the sampled bank pixels.
        var hidden = new ArrayList<IntAttribute>();
        var plants = batches(GpuTestSupport.field(terrain, "biomeVegetation"));
        plants.addAll(batches(GpuTestSupport.field(terrain, "groundCover")));
        for (Object batch : plants) {
            var instance = (ModelInstance) GpuTestSupport.field(batch, "instance");
            if (instance == null) { continue; }
            var cull = instance.materials.first().get(IntAttribute.class, IntAttribute.CullFace);
            hidden.add(cull);
            cull.value = GL20.GL_FRONT_AND_BACK;
        }
        frame.render(terrain, camera, scene);
        var before = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
        try {
            mask.lights().setHexes(new int[scene.width() * scene.height()], scene.width());
            frame.render(terrain, camera, scene);
            var after = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
            try {
                var changed = new java.util.HashSet<Integer>();
                float metre = BoardRelief.metres(1);
                for (var tile : scene.tiles()) {
                    var surface = terrain.tacticalSurface(tile.coords());
                    if (surface == null) { continue; }
                    for (var face : surface.faces()) {
                        var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                        if (Math.abs(normal.z) < .05f || Math.abs(normal.z) > .90f) { continue; }
                        var bottom = face.a().z < face.b().z ? face.a() : face.b();
                        if (face.c().z < bottom.z) { bottom = face.c(); }
                        var middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                        for (float along : new float[] { .2f, .5f, .8f }) {
                            var point = new Vector3(bottom).lerp(middle, along);
                            if (point.z < .1f * metre || point.z > 2 * metre) { continue; }
                            camera.camera.project(point);
                            int x = Math.round(point.x), y = Math.round(point.y);
                            if (x < 0 || y < 0 || x >= before.getWidth() || y >= before.getHeight()) { continue; }
                            int a = before.getPixel(x, y), b = after.getPixel(x, y);
                            int difference = Math.abs((a >>> 24) - (b >>> 24)) + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255))
                                  + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
                            if (difference > 15) { changed.add(y * before.getWidth() + x); }
                        }
                    }
                }
                assertTrue(changed.size() > 12, "Biome soil must blend onto bank faces: " + changed.size());
            } finally { after.dispose(); }
        } finally {
            before.dispose();
            mask.lights().setHexes((int[]) GpuTestSupport.field(mask, "pixels"), scene.width());
            for (var cull : hidden) { cull.value = GL20.GL_NONE; }
        }
    }

    private static int samplerBudget(GpuTerrain terrain) throws Exception {
        var batch = (ModelBatch) GpuTestSupport.field(terrain, "batch");
        var shaders = BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        int maximum = 0;
        for (Object shader : (Iterable<?>) shaders.get(batch.getShaderProvider())) {
            var program = ((DefaultShader) shader).program;
            int count = 0;
            var samplers = new ArrayList<String>();
            for (String uniform : program.getUniforms()) {
                int type = program.getUniformType(uniform);
                if (type == GL20.GL_SAMPLER_2D || type == GL20.GL_SAMPLER_CUBE
                      || type == org.lwjgl.opengl.GL30.GL_SAMPLER_2D_ARRAY) {
                    count += program.getUniformSize(uniform);
                    samplers.add(uniform);
                }
            }
            assertTrue(count <= 16, "The shared biome map must fit the existing 16-sampler budget: " + count + " " + samplers);
            maximum = Math.max(maximum, count);
        }
        return maximum;
    }

    private static BoardScene shoreline(BoardScene.Biome kind, int depth) {
        return BoardScenes.scene(c -> c.getX() < 4
              ? BoardScenes.tile(c, BoardScene.Surface.DIRT, 0, depth, 0) : BoardScenes.tile(c, kind, 0));
    }

    private static int changed(Pixmap before) {
        var after = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
        try {
            int count = 0;
            for (int x = 0; x < 1280; x += 3) {
                for (int y = 0; y < 960; y += 3) {
                    if (before.getPixel(x, y) != after.getPixel(x, y)) { count++; }
                }
            }
            return count;
        } finally { after.dispose(); }
    }
}
