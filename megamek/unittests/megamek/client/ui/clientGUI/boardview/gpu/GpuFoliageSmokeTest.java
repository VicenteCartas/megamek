/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Render every tree species, including the independently textured snow-covered geometry and the desert plants. */
@Tag("on-demand")
class GpuFoliageSmokeTest {
    @Test
    void rendersSummerAndWinterTreeMaterials() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuAssets assets = new GpuAssets();
                GpuSceneTestOutput outputFrame = new GpuSceneTestOutput();
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.orbit(-45, 0);
                    var shared = new java.util.HashMap<String, Texture>();
                    for (boolean snow : new boolean[] { false, true }) {
                        BoardScene scene = treeScene(snow);
                        for (BoardScene.Tile tile : scene.tiles()) {
                            if (tile.features().isEmpty()) { continue; }
                            String name = tile.features().getFirst().asset();
                            var model = assets.model(name);
                            assertEquals(snow, model.getMaterial("snow") != null
                                  || model.getMaterial("canopy-snow-cutout") != null, name);
                            for (var material : model.materials) {
                                var map = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                                assertNotNull(map, name + ": " + material.id);
                                // The impostor carries its own rendered atlas, not a shared detail map.
                                if (material.id.equals("impostor")) { continue; }
                                if (material.id.endsWith("-cutout")) {
                                    assertEquals(512, map.textureDescription.texture.getWidth());
                                    assertEquals(512, map.textureDescription.texture.getHeight());
                                    assertEquals(Texture.TextureWrap.ClampToEdge, map.textureDescription.texture.getVWrap());
                                    continue;
                                }
                                if (material.id.equals("cactus")) {
                                    assertEquals(512, map.textureDescription.texture.getWidth());
                                    var normal = material.get(TextureAttribute.class, TextureAttribute.Normal);
                                    assertNotNull(normal, "Cactus ribs use the authored normal map");
                                    assertSame(assets.materialNormal("vegetation/cactus-skin"),
                                          normal.textureDescription.texture);
                                    assertSame(assets.material("vegetation/cactus-skin"), map.textureDescription.texture);
                                    continue;
                                }
                                // Board maps stay within 512 px (only multi-hex terrain takes 1024).
                                assertTrue(map.textureDescription.texture.getWidth() <= 512
                                      && map.textureDescription.texture.getHeight() <= 512, material.id);
                                assertEquals(Texture.TextureWrap.Repeat, map.textureDescription.texture.getVWrap());
                                // Foliage shares its textures: every variant's material of one id uses the same one.
                                assertSame(shared.computeIfAbsent(material.id, id -> map.textureDescription.texture),
                                      map.textureDescription.texture, "Tree variants share the small texture maps");
                            }
                        }
                        terrain.update(scene);
                        camera.fit(scene);
                        terrain.animate(0, List.of());
                        terrain.renderShadows(List.of());
                        outputFrame.begin(0.035f, 0.055f, 0.075f, 1);
                        terrain.render(camera.camera);
                        outputFrame.display();
                        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots"),
                              "trees-" + (snow ? "snow" : "summer") + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                    outputFrame.pixels().end();
                    var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    try {
                        for (boolean natural : new boolean[] { false, true }) {
                            BoardScene forest = forest(natural);
                            terrain.update(forest);
                            for (boolean overhead : new boolean[] { false, true }) {
                                camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setIsometric(!overhead);
                                camera.fit(forest);
                                GpuReviewFrame.settle(terrain, null, forest, camera);
                                frame.render(terrain, camera, forest);
                                GpuReviewFrame.save(new File(System.getProperty("megamek.gpu.screenshots"),
                                      "forest-" + (natural ? "stands" : "mixed") + (overhead ? "-top" : "") + ".png"));
                            }
                            camera.setIsometric(true);
                            camera.center(BoardGeometry.center(new Coords(5, 4), 0).add(0, 0, 18));
                            camera.camera.zoom = .32f;
                            camera.update();
                            GpuReviewFrame.settle(terrain, null, forest, camera);
                            frame.render(terrain, camera, forest);
                            GpuReviewFrame.save(new File(System.getProperty("megamek.gpu.screenshots"),
                                  "forest-" + (natural ? "stands" : "mixed") + "-close.png"));
                        }
                    } finally { frame.dispose(); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    outputFrame.close();
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static BoardScene treeScene(boolean snow) throws Exception {
        List<String> names = new ArrayList<>(BoardTreeDistributionTest.BASIC_TREES);
        names.add("vegetation/tree-dead");
        if (!snow) {
            names.addAll(List.of("vegetation/palm", "vegetation/palm-bent", "vegetation/cactus",
                  "vegetation/cactus-flowers"));
        }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            List<BoardScene.Feature> tree = index < names.size()
                  ? List.of(new BoardScene.Feature(names.get(index) + (snow ? "-snow" : ""), 0, 0, 20, 1.1f, 2, 0,
                        BoardScene.FeatureKind.TREE))
                  : List.of();
            tiles.add(new BoardScene.Tile(new Coords(index / 5, index % 5), 0, -1, false, 0,
                  snow ? BoardScene.Surface.SNOW : BoardScene.Surface.GRASS, null, tree, List.of()));
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of(), new BoardScene.Light(-24, -30));
    }

    private static BoardScene forest(boolean natural) {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 10; y++) {
                var coords = new Coords(x, y);
                String cover = x == 0 || x == 11 || y == 0 || y == 9 || x == 6 ? ""
                      : "woods:" + (y < 5 ? 1 : 2) + ";foliage_elev:2";
                var hex = new Hex(0, cover + (x == 6 ? ";road:1:9" : ""), "", coords);
                tiles.add(new BoardScene.Tile(coords, 0, -1, false, x == 6 ? 9 : 0, BoardScene.Surface.GRASS, null,
                      null, BoardTreeDistributionTest.capture(hex, coords, natural),
                      List.of(), BoardLiquid.NONE, null, true, BoardRoad.capture(hex)));
            }
        }
        return new BoardScene(0, 12, 10, tiles, List.of(), List.of(), -1, "", List.of(), new BoardScene.Light(-24, -30));
    }
}
