/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Converted compositions (legacy art the conversion turned into a group of placed objects) keep their support plane and
 * local transforms while sharing ordinary tree and prop instances.
 */
@Tag("on-demand")
class GpuSceneryTreeSmokeTest {
    @Test
    void compositionsKeepTheirTransformsOnGroundRoofsAndPoolIslands() throws Exception {
        var samples = List.of(
              new GpuSceneryPlacementSmokeTest.Sample("road-trees", 0, "road:2:9", "", "", false),
              new GpuSceneryPlacementSmokeTest.Sample("garden-trees", 0, "pavement:1", "", "fluff:8:6", false),
              new GpuSceneryPlacementSmokeTest.Sample("pool-island-trees", 0, "pavement:1", "", "fluff:92:3", false),
              new GpuSceneryPlacementSmokeTest.Sample("picnic-tables", 0, "pavement:1", "", "fluff:93:6", false),
              new GpuSceneryPlacementSmokeTest.Sample("roof-trees", 0, "pavement:1",
                    "building:2;bldg_elev:3;bldg_cf:90", "fluff:8:6", false));
        var scenes = new ArrayList<BoardScene>();
        for (var sample : samples) { scenes.add(GpuSceneryPlacementSmokeTest.capture(sample, true)); }
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "scenery-trees");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    var coords = new Coords(2, 2);
                    int trees = 0;
                    for (int index = 0; index < scenes.size(); index++) {
                        var scene = scenes.get(index);
                        // The converted composition's objects, as the board stores them.
                        var roots = scene.tile(coords).features().stream().filter(f -> f.decoration() != null).toList();
                        assertTrue(!roots.isEmpty(), samples.get(index).name());
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        var installed = new ArrayList<ModelInstance>();
                        // The tree-stand species of each installed object (null for other props).
                        var treeAssets = new ArrayList<Object>();
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                var instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
                                var treeAsset = GpuTestSupport.field(prop, "treeAsset");
                                if (coords.equals(GpuTestSupport.field(prop, "coords")) && roots.stream().anyMatch(f ->
                                      f.asset().equals(treeAsset) || instance.nodes.first().id.equals(
                                            f.asset().substring(f.asset().lastIndexOf('/') + 1)))) {
                                    installed.add(instance);
                                    treeAssets.add(treeAsset);
                                }
                            }
                        }
                        assertEquals(roots.size(), installed.size());
                        float support = Float.NaN;
                        for (int i = 0; i < roots.size(); i++) {
                            var root = roots.get(i);
                            var instance = installed.get(i);
                            var at = instance.transform.getTranslation(new Vector3());
                            assertEquals(BoardGeometry.centerX(coords) + root.x() * BoardGeometry.hexScale(), at.x, .001f);
                            assertEquals(BoardGeometry.centerY(coords) + root.y() * BoardGeometry.hexScale(), at.y, .001f);
                            // Every object of a converted composition stands on the hex's one support.
                            if (!Float.isFinite(support)) { support = at.z; }
                            assertEquals(support, at.z, .001f, "Components retain the same support plane");
                            var scale = instance.transform.getScale(new Vector3());
                            assertEquals(root.scale() * BoardGeometry.hexScale(), scale.z, .0001f);
                            assertEquals(scale.x, scale.z, .0001f, "Composed meshes retain their proportions");
                            var bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
                            if (root.asset().equals(treeAssets.get(i))) {
                                trees++;
                                var canopy = instance.getMaterial("canopy-cutout");
                                assertNotNull(canopy);
                                assertTrue(canopy.has(Attribute.getAttributeType("boardFoliage")));
                                assertTrue(canopy.has(TextureAttribute.Normal) && canopy.has(TextureAttribute.Ambient));
                            }
                            if (samples.get(index).name().equals("roof-trees")) {
                                assertTrue(bounds.min.z >= 3 * BoardGeometry.level() - .01f);
                            }
                        }
                        for (boolean overhead : new boolean[] { false, true }) {
                            camera.setIsometric(!overhead);
                            camera.center(BoardGeometry.center(coords, 0).add(0, 0,
                                  samples.get(index).name().equals("roof-trees") ? 3 * BoardGeometry.level() : 5));
                            camera.camera.zoom = .16f;
                            camera.update();
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, samples.get(index).name() + (overhead ? "-top.png" : ".png")));
                            var center = BoardGeometry.center(coords, 0);
                            assertNotNull(terrain.selectionHit(scene,
                                  new Ray(new Vector3(center.x, center.y, 1000), new Vector3(0, 0, -1))));
                        }
                    }
                    assertTrue(trees > 0, "Converted trees stand in the tree stand with their foliage materials");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Decorative tree integration", failure.get()); }
    }
}
