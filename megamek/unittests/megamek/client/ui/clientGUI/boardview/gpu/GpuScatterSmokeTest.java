/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Measures the submitted geometry and draws; captures the actual tileset in a native GL context. */
@Tag("on-demand")
class GpuScatterSmokeTest {
    @Test
    void batchesSparseDetailsAndCullsAllPassesWithoutChangingPicking() throws Exception {
        assumeTrue(BoardScatter.DENSITY_MULTIPLIER > 0, "Scatter is disabled");
        Hex[] hexes = new Hex[16 * 16];
        String[] themes = { "grass", "dirt", "desert", "rock", "lunar" };
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                Hex hex = new Hex(0);
                hex.setTheme(themes[x * themes.length / 16]);
                hexes[y * 16 + x] = hex;
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(16, 16, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene captured = fixture.source.takeFrame().scene();
            // Isolate the baked scatter layer from the separate living grass and sculpted-ground systems.
            var tiles = captured.tiles().stream().map(tile -> new BoardScene.Tile(tile.coords(), tile.elevation(),
                  tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(),
                  tile.decals(), tile.decalsWithoutLimbs(), tile.features(), tile.text(), tile.liquid(),
                  tile.tileset(), false)).toList();
            BoardScene scene = new BoardScene(0, 16, 16, tiles, List.of(), List.of(), -1, "", List.of(),
                  new BoardScene.Light(-24, -30));
            BoardScene bare = new BoardScene(0, 16, 16,
                  scene.tiles().stream().map(tile -> new BoardScene.Tile(tile.coords(), tile.elevation(),
                  tile.waterDepth(), tile.frozen(), tile.roadExits(), tile.surface(), tile.decals(),
                  tile.decalsWithoutLimbs(), List.of(),
                  tile.text())).toList(), List.of(), List.of(), -1, "", List.of(), scene.light());
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1440, 1000);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuTerrain plain = new GpuTerrain();
                    ModelBatch depth = new ModelBatch(GpuTreeInstances.depthProvider(new DepthShader.Config()));
                    GL20 rawGl20 = Gdx.gl20;
                    GLProfiler profiler = new GLProfiler(Gdx.graphics);
                    try {
                        terrain.update(scene);
                        plain.update(bare);
                        var materials = scatterMaterials(terrain);
                        assertTrue(!materials.isEmpty(), "The scatter layer must carry its atlas");
                        assertEquals(1, materials.values().stream().map(value -> value.textureDescription.texture).distinct().count(),
                              "Every rock/grass type and chunk borrows the same atlas");
                        Texture atlas = materials.values().iterator().next().textureDescription.texture;
                        assertEquals(Texture.TextureFilter.MipMapLinearLinear, atlas.getMinFilter());
                        var features = scene.tiles().stream().flatMap(tile -> tile.features().stream()).toList();
                        assertTrue(!features.isEmpty() && features.size() <= 1536,
                              "Clusters stay bounded regardless of the density multiplier");
                        int triangles = scene.tiles().stream().mapToInt(tile -> tile.features().stream()
                              .mapToInt(feature -> switch (feature.asset()) {
                                  case "scatter-grass", "scatter-dry-grass" -> 6;
                                  case "scatter-plant" -> BoardScatter.plant(tile.surface()).polygons().size();
                                  default -> {
                                      int count = GpuScatter.rock(tile, feature).polygons().stream()
                                            .mapToInt(polygon -> polygon.points().length - 2).sum();
                                      assertEquals(8, count, "Tiny scatter uses eight-triangle rocks with an open base");
                                      yield count;
                                  }
                              }).sum()).sum();
                        float diameter = (float) features.stream().mapToDouble(GpuScatter::diameter).max().orElseThrow();
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setIsometric(true);
                        camera.center(BoardGeometry.center(new Coords(7, 7), 0));
                        profiler.enable();
                        int scatterDraws = 0;
                        long bushFamilies = scene.tiles().stream().filter(tile -> tile.features().stream()
                              .anyMatch(feature -> feature.asset().equals("scatter-plant")))
                              .map(BoardScene.Tile::surface).distinct().count();
                        float[] pixels = { 6, 2.4f, 1.5f, 2.4f, 4 };
                        for (int step = 0; step < pixels.length; step++) {
                            camera.camera.zoom = diameter / pixels[step] * Gdx.graphics.getBackBufferHeight()
                                  / Gdx.graphics.getHeight();
                            camera.update();
                            // Change the sun so both scenes submit a fresh shadow map; camera-only changes can
                            // reuse the fitted texel grid, and LoD dirties only the scene containing scatter.
                            var lighting = BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12 + step * .2f,
                                  0, 0, 2, 0, 0));
                            terrain.setAtmosphere(lighting);
                            plain.setAtmosphere(lighting);
                            boolean visible = step < 2 || step == 4;
                            int vertices = visible ? triangles * 3 : 0;
                            if (step == 0) { settle(camera.camera, plain, terrain); }
                            int[] background = count(profiler, () -> plain.render(camera.camera));
                            int[] decorated = count(profiler, () -> terrain.render(camera.camera));
                            assertEquals(vertices, decorated[0] - background[0], "Color pass at LoD step " + step);
                            if (step == 0) {
                                scatterDraws = decorated[1] - background[1];
                                // Distinct tissue materials cannot merge with the atlas. Allow one part per
                                // bush family plus the original 16-bit geometry budget, never one per object.
                                assertTrue(scatterDraws > 0 && scatterDraws <= Math.ceil(vertices / 32768.0) + bushFamilies,
                                      "Scatter must stay in material batches rather than draw individual objects");
                            }
                            assertEquals(visible ? scatterDraws : 0, decorated[1] - background[1],
                                  "Scatter batch visibility at LoD step " + step);
                            assertEquals(vertices,
                                  count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth))[0]
                                        - count(profiler, () -> plain.renderDepth(camera.camera, List.of(), depth))[0],
                                  "Depth pass at LoD step " + step);
                            assertEquals(
                                  count(profiler, () -> plain.renderWireframe(camera.camera, depth))[0],
                                  count(profiler, () -> terrain.renderWireframe(camera.camera, depth))[0],
                                  "Wireframe omits scatter at LoD step " + step);
                            int shadowVertices = count(profiler, () -> terrain.renderShadows(camera.camera, List.of()))[0]
                                  - count(profiler, () -> plain.renderShadows(camera.camera, List.of()))[0];
                            assertEquals(vertices, shadowVertices, "Shadow pass at LoD step " + step);
                            assertEquals(0, count(profiler, () -> terrain.renderShadows(camera.camera, List.of()))[0],
                                  "An unchanged camera reuses the shadow map");
                            for (var tile : scene.tiles()) {
                                for (var feature : tile.features()) {
                                    Vector3 origin = BoardGeometry.center(tile.coords(), 0).add(
                                          feature.x() * BoardGeometry.HEX_SCALE, feature.y() * BoardGeometry.HEX_SCALE, 100);
                                    Ray ray = new Ray(origin, new Vector3(0, 0, -1));
                                    assertEquals(plain.hit(bare, ray), terrain.hit(scene, ray),
                                          "Decoration must not change a board-space hit");
                                }
                            }
                        }
                        BoardScene approach = approach(List.of(features.getFirst()));
                        terrain.update(approach);
                        plain.update(approach(List.of()));
                        camera.camera.zoom = .25f;
                        camera.center(BoardGeometry.center(new Coords(0, 0), 0));
                        settle(camera.camera, plain, terrain);
                        assertEquals(count(profiler, () -> plain.render(camera.camera))[0],
                              count(profiler, () -> terrain.render(camera.camera))[0],
                              "A sloped road approach onto a roadless concrete apron must also suppress scatter");
                        terrain.update(scene);
                        GpuStageTimings.stopCounting(profiler, rawGl20);
                        camera.fit(scene);
                        preview(terrain, camera, "scatter-overview");
                        for (BoardScene.Surface surface : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT,
                              BoardScene.Surface.DESERT, BoardScene.Surface.ROCK, BoardScene.Surface.LUNAR)) {
                            var tile = scene.tiles().stream().filter(value -> value.surface() == surface
                                  && !value.features().isEmpty()).findFirst().orElseThrow();
                            camera.camera.zoom = .15f;
                            camera.center(BoardGeometry.center(tile.coords(), tile.elevation()));
                            preview(terrain, camera, "scatter-" + surface.name().toLowerCase(java.util.Locale.ROOT));
                        }
                        System.out.println("Scatter: " + features.size() + " objects, " + triangles
                              + " triangles, " + scatterDraws + " additional color draws on 256 hexes; 0 at overview cull.");
                        if (Boolean.getBoolean("megamek.gpu.scatterBenchmark")) { benchmark(terrain, camera); }
                        // Inspect the texture next to the actual sculpted ground as well as the isolated fixture.
                        terrain.update(captured);
                        var close = captured.tiles().stream().filter(tile -> tile.surface() == BoardScene.Surface.GRASS
                              && tile.coords().getY() > 2 && tile.coords().getY() < 13
                              && tile.features().stream().anyMatch(feature -> feature.asset().equals("scatter-rock"))
                              && tile.features().stream().anyMatch(feature -> feature.asset().equals("scatter-plant")))
                              .findFirst().orElseThrow();
                        camera.camera.zoom = .06f;
                        camera.center(BoardGeometry.center(close.coords(), close.elevation()));
                        preview(terrain, camera, "scatter-textured-close");
                        // Inspect both plant paths in the same daylight/composite pipeline as the real board.
                        GpuReviewFrame frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                        try {
                            for (var family : List.of(BoardScene.Surface.DESERT, BoardScene.Surface.GRASS)) {
                                var shrubTile = captured.tiles().stream().filter(tile -> tile.surface() == family
                                      && tile.features().isEmpty()).filter(tile -> {
                                          var ground = new BoardSurface(captured, tile);
                                          return ground.faces.stream().anyMatch(face ->
                                                ground.relief.shade(face.a()).kind() == BoardRelief.Kind.PLANT);
                                      }).findFirst().orElseThrow();
                                var ground = new BoardSurface(captured, shrubTile);
                                var plantFace = ground.faces.stream().filter(face ->
                                      ground.relief.shade(face.a()).kind() == BoardRelief.Kind.PLANT).findFirst().orElseThrow();
                                String name = family.name().toLowerCase(java.util.Locale.ROOT);
                                camera.camera.zoom = .04f;
                                camera.center(plantFace.a());
                                frame.render(terrain, camera, captured);
                                String output = System.getProperty("megamek.gpu.screenshots");
                                if (output != null) { GpuReviewFrame.save(new File(output, "bush-field-" + name + ".png")); }
                                bushLowSun(terrain, camera, captured, "bush-field-" + name);
                                var plantTile = captured.tiles().stream().filter(tile -> tile.surface() == family
                                      && tile.features().stream().anyMatch(f -> f.asset().equals("scatter-plant")))
                                      .findFirst().orElseThrow();
                                var plant = plantTile.features().stream().filter(f -> f.asset().equals("scatter-plant"))
                                      .findFirst().orElseThrow();
                                camera.center(BoardGeometry.center(plantTile.coords(), plantTile.elevation()).add(
                                      plant.x() * BoardGeometry.hexScale(), plant.y() * BoardGeometry.hexScale(), 0));
                                frame.render(terrain, camera, captured);
                                if (output != null) { GpuReviewFrame.save(new File(output, "bush-scatter-" + name + ".png")); }
                                bushLowSun(terrain, camera, captured, "bush-scatter-" + name);
                                camera.camera.zoom = .12f;
                                camera.update();
                                frame.render(terrain, camera, captured);
                                if (output != null) { GpuReviewFrame.save(new File(output, "bush-board-" + name + ".png")); }
                            }
                        } finally {
                            frame.dispose();
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        GpuStageTimings.stopCounting(profiler, rawGl20);
                        depth.dispose();
                        plain.dispose();
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        }
        if (failure.get() != null) { throw new AssertionError("Scatter rendering", failure.get()); }
    }

    /**
     * Draws the views until their deferred work has settled: prop pages merge only after their sources held for
     * GpuMeshPage.SETTLE_FRAMES frames, then a little each frame, and the ground material cache fills its pages.
     */
    private static void settle(Camera camera, GpuTerrain... terrains) {
        for (int frame = 0; frame < 2 * GpuMeshPage.SETTLE_FRAMES; frame++) {
            for (GpuTerrain terrain : terrains) { terrain.render(camera); }
        }
    }

    private static int[] count(GLProfiler profiler, Runnable draw) {
        profiler.reset();
        ScreenUtils.clear(.035f, .055f, .075f, 1, true);
        draw.run();
        return new int[] { (int) profiler.getVertexCount().total, profiler.getDrawCalls() };
    }

    private static Map<Material, TextureAttribute> scatterMaterials(GpuTerrain terrain) throws Exception {
        Map<Material, TextureAttribute> result = new IdentityHashMap<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object part : (Array<?>) GpuTestSupport.field(chunk, "scatterRenderables")) {
                Material material = ((Renderable) part).material;
                // Bushes use the same tissue-aware sculpt material as field shrubs. Atlas controls apply
                // only to rocks and grass; removing the sculpt dummy diffuse slot would also remove its UVs.
                if (GpuTerrain.sculpted(material)) { continue; }
                TextureAttribute atlas = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                assertTrue(atlas != null, "Non-bush scatter must retain its shared textured path");
                result.put(material, atlas);
            }
        }
        return result;
    }

    /** Same meshes, density, camera and lighting in alternating rounds; GPU pass cost, not application FPS. */
    private static void benchmark(GpuTerrain terrain, BoardCamera camera) throws Exception {
        var materials = scatterMaterials(terrain);
        GpuPropBatch pages = (GpuPropBatch) GpuTestSupport.field(terrain, "propBatch");
        System.out.println("SCATTER GPU: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + "; "
              + Gdx.graphics.getBackBufferWidth() + "x" + Gdx.graphics.getBackBufferHeight());
        try (GpuStageTimings timings = new GpuStageTimings()) {
            for (String view : new String[] { "overview", "normal", "close" }) {
                camera.camera.zoom = switch (view) { case "overview" -> 2; case "normal" -> .5f; default -> .12f; };
                camera.center(BoardGeometry.center(new Coords(7, 7), 0));
                terrain.renderShadows(camera.camera, List.of());
                for (int round = 0; round < 4; round++) {
                    boolean textured = round % 2 == 1;
                    materials.forEach((material, atlas) -> {
                        if (textured) { material.set(atlas); }
                        else { material.remove(TextureAttribute.Diffuse); }
                    });
                    // Materials are immutable in production; changing them for this probe needs fresh prop pages.
                    pages.dispose();
                    for (int i = 0; i < 390; i++) {
                        ScreenUtils.clear(.035f, .055f, .075f, 1, true);
                        timings.beginFrame(i >= 90);
                        timings.stage("opaque");
                        terrain.render(camera.camera);
                        timings.stage(null);
                        Gdx.gl.glFinish();
                    }
                    StringBuilder report = new StringBuilder();
                    timings.appendReport(report, "SCATTER " + view + " round=" + round / 2 + " textured=" + textured);
                    System.out.print(report);
                }
            }
        } finally {
            materials.forEach(Material::set);
            pages.dispose();
        }
    }

    /**
     * A paved road ending a level above a roadless concrete hex ramps into it (BoardRoadGrade.apron); a road ending at
     * other unpaved ground keeps its whole ramp in its own hex.
     */
    private static BoardScene approach(List<BoardScene.Feature> details) {
        var apron = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.CONCRETE, null, details,
              List.of());
        var road = new BoardScene.Tile(new Coords(0, 1), 1, -1, false, 1, BoardScene.Surface.GRASS, null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, false, BoardRoad.Kind.PAVED);
        return new BoardScene(0, 1, 2, List.of(apron, road), List.of(), List.of(), -1, "", List.of());
    }

    private static void bushLowSun(GpuTerrain terrain, BoardCamera camera, BoardScene scene, String name) {
        String output = System.getProperty("megamek.gpu.screenshots");
        if (output == null) { return; }
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(6.5f, 0, 0, 2, 0, 0));
        try {
            for (int side = 0; side < 2; side++) {
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, name + "-low-sun-" + side + ".png"));
                camera.orbit(180, 0);
                camera.update();
            }
        } finally { frame.dispose(); }
    }

    private static void preview(GpuTerrain terrain, BoardCamera camera, String name) {
        ScreenUtils.clear(.035f, .055f, .075f, 1, true);
        terrain.renderShadows(camera.camera, List.of());
        terrain.render(camera.camera);
        String directory = System.getProperty("megamek.gpu.screenshots");
        if (directory != null) {
            GpuBoardTestUi.capture(new File(directory, name + ".png"));
        }
    }
}
