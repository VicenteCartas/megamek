/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual instanced geometry in all three passes, plus orchard rows in the shared board cameras. */
@Tag("on-demand")
class GpuOrchardSmokeTest {
    @Test
    void orchardModelsShareLodsAcrossColourDepthAndShadows() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "orchards");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var assets = new GpuAssets();
                var terrain = new GpuTerrain();
                var depth = new ModelBatch(GpuTreeInstances.depthProvider(new DepthShader.Config()));
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    profiler.enable();
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    camera.setIsometric(true);
                    Coords coords = new Coords(0, 0);
                    var names = new ArrayList<String>();
                    for (boolean snow : new boolean[] { false, true }) {
                        for (String form : BoardOrchardTest.FORMS) {
                            String name = "vegetation/orchard-" + form + (snow ? "-snow" : "");
                            names.add(name);
                            var tree = new BoardScene.Feature(name, 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.TREE);
                            // Keep terrain/scatter detail fixed while measuring the submitted tree geometry.
                            var tile = new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS, null,
                                  List.of(tree), List.of());
                            BoardScene scene = scene(List.of(tile), 1, 1);
                            terrain.update(scene);
                            int[] triangles = new int[TreeLod.LEVELS];
                            var maps = new HashMap<String, Texture>();
                            for (int lod = 0; lod < TreeLod.LEVELS; lod++) {
                                var model = assets.lodModel(name, lod);
                                for (var part : model.meshParts) { triangles[lod] += part.size / 3; }
                                for (var material : model.materials) {
                                    var map = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                                    assertNotNull(map);
                                    // The impostor has its own rendered atlas.
                                    if (material.id.equals("impostor")) { continue; }
                                    maps.putIfAbsent(material.id, map.textureDescription.texture);
                                    assertSame(maps.get(material.id), map.textureDescription.texture,
                                          "Orchard levels share each material's maps: " + material.id);
                                }
                            }
                            var bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                            float diameter = bounds.getDimensions(new Vector3()).scl(1, 1, 36 / bounds.getDepth()).len();
                            camera.center(BoardGeometry.center(coords, 0).add(0, 0, 18));
                            Ray ray = new Ray(BoardGeometry.center(coords, 0).add(0, 0, 1000), new Vector3(0, 0, -1));
                            var hit = terrain.hit(scene, ray);
                            assertNotNull(hit);
                            int nearColour = 0, nearDepth = 0, nearShadow = 0;
                            for (int lod : new int[] { 0, 1, 2, 3, 0 }) {
                                camera.camera.zoom = diameter * Gdx.graphics.getBackBufferHeight()
                                      / Gdx.graphics.getHeight() / GpuTreeLodSmokeTest.PIXELS[lod];
                                camera.update();
                                terrain.animate(0, List.of(), 1);
                                // A level change alone keeps the shadow map until the next refit; redraw it to count
                                // the selected level's geometry.
                                terrain.refreshShadows();
                                int shadow = count(profiler, () -> terrain.renderShadows(camera.camera, List.of()));
                                int colour = colour(profiler, terrain, camera);
                                int depthCount = count(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth));
                                if (lod == 0) { nearColour = colour; nearDepth = depthCount; nearShadow = shadow; }
                                int saved = 3 * (triangles[0] - triangles[lod]);
                                assertEquals(saved, nearColour - colour, name);
                                assertEquals(saved, nearDepth - depthCount, name);
                                // The shadow map's level follows its own texel (GpuTerrain.renderDepth).
                                assertTrue(GpuTreeLodSmokeTest.drawnLevel(nearShadow - shadow, triangles) >= 0,
                                      "Shadow draws one level of the orchard: " + name);
                                assertEquals(hit, terrain.hit(scene, ray), "Picking is independent of orchard LOD");
                            }
                        }
                    }
                    profiler.disable();
                    GpuTreeLodSmokeTest.compareLevels(assets, names);
                    BoardScene orchard = orchard();
                    terrain.update(orchard);
                    camera.fit(orchard);
                    for (int view = 0; view < 3; view++) {
                        camera.setIsometric(view != 1);
                        camera.setPerspective(view == 2);
                        GpuReviewFrame.settle(terrain, null, orchard, camera);
                        frame.render(terrain, camera, orchard);
                        GpuReviewFrame.save(new File(output, "orchard-" + view + ".png"));
                    }
                    camera.setPerspective(false);
                    camera.setIsometric(true);
                    camera.camera.zoom = .13f;
                    camera.center(BoardGeometry.center(new Coords(2, 2), 0).add(0, 0, 18));
                    GpuReviewFrame.settle(terrain, null, orchard, camera);
                    frame.render(terrain, camera, orchard);
                    GpuReviewFrame.save(new File(output, "orchard-close.png"));
                    camera.camera.zoom = .085f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, orchard, camera);
                    frame.render(terrain, camera, orchard);
                    GpuReviewFrame.save(new File(output, "orchard-fruit-detail.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    profiler.disable();
                    frame.dispose();
                    depth.dispose();
                    terrain.dispose();
                    assets.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Orchard rendering", failure.get()); }
    }

    /** The colour pass's own geometry: the depth pre-pass, on by default, submits the trees a second time. */
    private static int colour(GLProfiler profiler, GpuTerrain terrain, BoardCamera camera) {
        Set<GpuTerrain.Prepass> prepass = GpuTerrain.depthPrepass;
        GpuTerrain.depthPrepass = EnumSet.noneOf(GpuTerrain.Prepass.class);
        try { return count(profiler, () -> terrain.render(camera.camera)); }
        finally { GpuTerrain.depthPrepass = prepass; }
    }

    private static int count(GLProfiler profiler, Runnable draw) {
        profiler.reset();
        draw.run();
        return (int) profiler.getVertexCount().total;
    }

    private static BoardScene.Tile tile(Coords coords, Hex hex, List<BoardScene.Feature> features) {
        int exits = hex.containsTerrain(Terrains.ROAD) ? hex.getTerrain(Terrains.ROAD).getExits() : 0;
        return new BoardScene.Tile(coords, hex.getLevel(), -1, false, exits, BoardFeatures.surface(hex), null, null,
              features, List.of(), BoardLiquid.NONE, null,
              BoardFeatures.detailedGround(hex, Map.of()), BoardRoad.capture(hex));
    }

    private static BoardScene orchard() {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                String cover = x > 0 && x < 6 && y > 0 && y < 4
                      ? "woods:1;fluff:12;foliage_elev:" + (y == 3 ? 1 : 2) : "";
                Hex hex = new Hex(0, cover + (y == 1 ? ";road:1:18" : ""), x > 4 ? "snow" : "", coords);
                tiles.add(tile(coords, hex, BoardFeatures.capture(hex, coords, Map.of())));
            }
        }
        return scene(tiles, 7, 5);
    }

    private static BoardScene scene(List<BoardScene.Tile> tiles, int width, int height) {
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of(),
              new BoardScene.Light(-24, -30));
    }
}
