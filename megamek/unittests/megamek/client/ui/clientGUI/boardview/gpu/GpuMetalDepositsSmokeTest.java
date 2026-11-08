/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual enhanced model/material rendering, with both views sharing the installed mineral geometry. */
@Tag("on-demand")
class GpuMetalDepositsSmokeTest {
    @Test
    void mineralOutcropsReplaceTheirLegacyPaintAndStayVisibleFromBothViews() throws Exception {
        var sample = new GpuSceneryPlacementSmokeTest.Sample("metal-deposit", 0, "", "", "metal_deposit:8", false);
        var plain = GpuSceneryPlacementSmokeTest.capture(sample, false);
        var scene = GpuSceneryPlacementSmokeTest.capture(sample, true);
        var rough = GpuSceneryPlacementSmokeTest.capture(
              new GpuSceneryPlacementSmokeTest.Sample("rough-comparison", 0, "", "", "rough:1", false), true);
        var centre = new Coords(2, 2);
        assertEquals(plain.tile(centre).decals(), scene.tile(centre).decals(), "Enhanced never projects the old ore sprite");
        assertTrue(scene.tile(centre).detailedGround());
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "metal-deposits");
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
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.center(BoardGeometry.center(centre, 0));
                        camera.camera.zoom = .1f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        int minerals = 0;
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                ModelInstance instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
                                if (!centre.equals(GpuTestSupport.field(prop, "coords"))) { continue; }
                                assertFalse(instance.materials.isEmpty());
                                assertEquals(3, instance.materials.size);
                                assertNotSame(instance.model.materials.first(), instance.materials.first(),
                                      "Each placement owns material copies; cached model resources stay shared");
                                for (String name : new String[] { "mineral-quartz", "mineral-ore", "mineral-matrix" }) {
                                    var material = instance.getMaterial(name);
                                    assertTrue(material.has(TextureAttribute.Diffuse | TextureAttribute.Normal));
                                    if (!name.equals("mineral-matrix")) {
                                        assertNotNull(material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE).map);
                                    }
                                }
                                minerals++;
                            }
                        }
                        assertEquals(scene.tile(centre).features().stream()
                              .filter(feature -> feature.kind() == BoardScene.FeatureKind.METAL).count(), minerals);
                        assertTrue(minerals > 0);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, oblique ? "metal-oblique.png" : "metal-top.png"));
                        camera.camera.zoom = .04f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, oblique ? "metal-close-oblique.png" : "metal-close-top.png"));
                    }
                    // The comparison uses identical lighting and framing, making confusion with rough terrain reviewable.
                    frame.prepare(terrain, camera, rough);
                    terrain.update(rough);
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.center(BoardGeometry.center(centre, 0));
                        camera.camera.zoom = .1f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, rough, camera);
                        frame.render(terrain, camera, rough);
                        GpuReviewFrame.save(new File(output, oblique ? "rough-oblique.png" : "rough-top.png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native metal deposits", failure.get()); }
    }
}
