/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shipped shrub textures, uniform placement and shared colour/depth/shadow LOD selection in native OpenGL. */
@Tag("on-demand")
class GpuShrubSmokeTest {
    private static final List<String> FAMILIES = List.of("temperate", "highland", "rocky", "wetland",
          "desert", "jungle", "barren", "snow");

    @Test
    void preservesShrubProportionsAndUsesAllThreeLodsInEveryPass() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuAssets assets = new GpuAssets();
                GpuTerrain terrain = new GpuTerrain();
                ModelBatch depth = new ModelBatch(GpuTreeInstances.depthProvider(new DepthShader.Config()));
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                try (var outputFrame = new GpuSceneTestOutput()) {
                    profiler.enable();
                    BoardScene.Pixels ground = new BoardScene.Pixels(ImageIO.read(
                          GpuAssets.tilesetFile("terrain/ground/base_default.png")));
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    for (String family : FAMILIES) {
                        String name = "vegetation/foliage-" + family;
                        terrain.update(scene(List.of(tile(family, new Coords(0, 0), ground)), 1, 1));
                        // The placed shrub's projected diameter selects its level: the board measures its placed bounds.
                        float diameter = 0;
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                var instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
                                var scale = instance.transform.getScale(new Vector3());
                                assertEquals(scale.z, scale.x, .0001f, "Shrubs must never be squashed: " + family);
                                assertEquals(scale.z, scale.y, .0001f);
                                diameter = ((BoundingBox) GpuTestSupport.field(prop, "bounds")).getDimensions(new Vector3()).len();
                            }
                        }
                        var atlases = new HashMap<String, Texture>();
                        int[] triangles = new int[TreeLod.LEVELS];
                        for (int lod = 0; lod < TreeLod.LEVELS; lod++) {
                            var model = assets.lodModel(name, lod);
                            for (var part : model.meshParts) { triangles[lod] += part.size / 3; }
                            for (var material : model.materials) {
                                var map = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                                assertNotNull(map);
                                // The impostor carries its own rendered atlas.
                                if (material.id.equals("impostor")) { continue; }
                                Texture atlas = atlases.computeIfAbsent(material.id, ignored -> map.textureDescription.texture);
                                assertSame(atlas, map.textureDescription.texture, "Every LOD shares its material maps");
                                assertNotNull(material.get(TextureAttribute.class, TextureAttribute.Normal));
                                assertNotNull(material.get(TextureAttribute.class, TextureAttribute.Ambient));
                            }
                        }
                        assertTrue(triangles[0] <= 480 && triangles[1] <= 240 && triangles[2] <= 96 && triangles[3] <= 12);
                        camera.center(BoardGeometry.center(new Coords(0, 0), 0).add(0, 0, 9));
                        int nearColour = 0, nearDepth = 0;
                        // The map's near level: so close that its texel resolves the shrub fully.
                        camera.camera.zoom = diameter / 400;
                        camera.update();
                        terrain.refreshShadows();
                        int nearShadow = count(profiler, () -> terrain.renderShadows(camera.camera, List.of()));
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
                            if (lod == 0) { nearColour = colour; nearDepth = depthCount; }
                            assertEquals(3 * (triangles[0] - triangles[lod]), nearColour - colour, family);
                            assertEquals(3 * (triangles[0] - triangles[lod]), nearDepth - depthCount, family);
                            // The map draws one whole level of its own, chosen by its texel (GpuTerrain.renderDepth).
                            assertTrue(GpuTreeLodSmokeTest.drawnLevel(nearShadow - shadow, triangles) >= 0,
                                  "Shadows draw one shrub level: " + family);
                        }
                    }
                    profiler.disable();
                    checkCoverage(assets);
                    List<BoardScene.Tile> tiles = new ArrayList<>();
                    for (int index = 0; index < FAMILIES.size(); index++) {
                        tiles.add(tile(FAMILIES.get(index), new Coords(index % 4, index / 4), ground));
                    }
                    BoardScene scene = scene(tiles, 4, 2);
                    terrain.update(scene);
                    camera.fit(scene);
                    for (int view = 0; view < 3; view++) {
                        camera.setIsometric(view != 1);
                        camera.setPerspective(view == 2);
                        terrain.renderShadows(camera.camera, List.of());
                        outputFrame.begin(.035f, .055f, .075f, 1);
                        terrain.render(camera.camera);
                        outputFrame.display();
                        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots"),
                              "shrubs-" + view + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    profiler.disable();
                    depth.dispose();
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
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

    private static void checkCoverage(GpuAssets assets) {
        var batch = new ModelBatch();
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(1, 1, 1, 1));
        try {
            for (String family : FAMILIES) {
                String name = "vegetation/foliage-" + family;
                var bounds = assets.model(name).calculateBoundingBox(new BoundingBox());
                for (float tilt : new float[] { 0, 55, 80 }) {
                    for (int bearing = 0; bearing < 360; bearing += 90) {
                        var camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.orbit(bearing, tilt);
                        camera.center(new Vector3(0, 0, 10.8f));
                        camera.camera.zoom = bounds.getDimensions(new Vector3()).len() * 1.2f / 160;
                        camera.update();
                        int near = GpuTreeLodSmokeTest.coverage(GpuTreeLodSmokeTest.render(batch, environment,
                              assets.lodModel(name, 0), camera));
                        assertTrue(near > 100, name);
                        for (int lod = 1; lod <= 2; lod++) {
                            int far = GpuTreeLodSmokeTest.coverage(GpuTreeLodSmokeTest.render(batch, environment,
                                  assets.lodModel(name, lod), camera));
                            double retained = (double) far / near;
                            assertTrue(retained >= .67 && retained <= 1.5,
                                  name + " LOD" + lod + " coverage=" + retained + " tilt=" + tilt + " bearing=" + bearing);
                        }
                    }
                }
            }
        } finally { batch.dispose(); }
    }

    private static BoardScene.Tile tile(String family, Coords coords, BoardScene.Pixels ground) {
        return new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS, null,
              List.of(new BoardScene.Feature("vegetation/foliage-" + family, 0, 0, 20, 1.7f, 1.1f, 0,
                    BoardScene.FeatureKind.TREE)), List.of());
    }

    private static BoardScene scene(List<BoardScene.Tile> tiles, int width, int height) {
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of(), new BoardScene.Light(-24, -30));
    }
}
