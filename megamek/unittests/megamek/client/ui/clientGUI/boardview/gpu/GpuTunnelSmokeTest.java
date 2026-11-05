/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Authored tunnel models must seal cliff cutouts even when the approach is suspended over space. */
@Tag("on-demand")
class GpuTunnelSmokeTest {
    @Test
    void bridgeAndGroundPortalsAreInstalledInBothGraphicsModesAndKeptInTacticalView() throws Exception {
        var quatreBelle = BoardScenes.installedScene("unofficial/Derv_Maps/35x35 Quatre Belle.board");
        var mines = BoardScenes.minesScene();
        var reported = new Coords(3, 8);
        assertEquals(BoardBackdrop.SPACE, quatreBelle.tile(reported).backdrop());
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "tunnels");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var weather = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                var frame = new GpuReviewFrame(weather);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (var scene : List.of(quatreBelle, mines)) {
                        var portals = scene.tiles().stream().flatMap(t -> BoardTunnel.entrances(scene, t).stream()).toList();
                        assertFalse(portals.isEmpty());
                        var closeup = scene == quatreBelle ? portals.stream().filter(p -> p.road().equals(reported))
                              .findFirst().orElseThrow() : portals.getFirst();
                        assertEquals(scene == quatreBelle ? BoardTunnel.BRIDGE_ASSET : BoardTunnel.ASSET, closeup.asset());
                        for (boolean columns : new boolean[] { false, true }) {
                            terrain.setColumns(columns);
                            terrain.setFluffHidden(false);
                            terrain.update(scene);
                            aim(camera, closeup, 0);
                            frame.prepare(terrain, camera, scene);
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            assertEquals(columns, terrain.columns());
                            verifyPortals(terrain, portals);
                            String name = (scene == quatreBelle ? "quatre-belle-0409-" : "mines-")
                                  + (columns ? "classic" : "enhanced");
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, name + "-front.png"));
                            if (scene == quatreBelle) {
                                aim(camera, closeup, 25);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, name + "-oblique.png"));
                            }
                            terrain.setFluffHidden(true);
                            frame.render(terrain, camera, scene);
                            verifyPortals(terrain, portals);
                            assertFalse(terrain.busy(), "The Tactical View retains portals without rebuilding");
                            GpuReviewFrame.save(new File(output, name + "-tactical.png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Installed bridge and ground tunnel portals", failure.get()); }
    }

    /** Check the installed GL models, not only the geometry's decision to cut an opening. */
    private static void verifyPortals(GpuTerrain terrain, List<BoardTunnel> expected) throws Exception {
        var assets = (GpuAssets) field(terrain, "assets");
        List<Object> installed = new ArrayList<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                var instance = (ModelInstance) field(prop, "instance");
                if (instance.getMaterial("tunnel-floor") != null) { installed.add(prop); }
            }
        }
        assertEquals(expected.size(), installed.size(), "Every cliff cutout has exactly one authored entrance");
        for (var portal : expected) {
            Object match = null;
            for (Object prop : installed) {
                var instance = (ModelInstance) field(prop, "instance");
                if (portal.road().equals(field(prop, "coords"))
                      && instance.transform.getTranslation(new Vector3()).epsilonEquals(portal.origin(), .0001f)) {
                    assertTrue(match == null, "A graphics-mode switch must not duplicate a portal");
                    match = prop;
                }
            }
            assertNotNull(match, "Missing portal at " + portal.road().getBoardNum());
            var instance = (ModelInstance) field(match, "instance");
            assertSame(assets.model(portal.asset()), instance.model, "The maintained GLB seals this opening");
            assertArrayEquals(portal.transform().val, instance.transform.val, .0001f);
            assertNotNull(instance.getMaterial("tunnel-portal"));
            assertNotNull(instance.getMaterial("tunnel-lining"));
            assertTrue((boolean) field(match, "tactical"), "The entrance stays with its road in the Tactical View");
        }
    }

    private static void aim(BoardCamera camera, BoardTunnel portal, float oblique) {
        camera.setIsometric(true);
        camera.camera.zoom = .10f;
        float azimuth = (float) Math.toDegrees(Math.atan2(-portal.along().x, portal.along().y));
        camera.orbit(azimuth - camera.azimuth() + oblique, 60 - camera.tilt());
        camera.center(new Vector3(portal.origin()).add(0, 0, 7 * BoardGeometry.hexScale()));
    }
}
