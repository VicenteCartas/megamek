/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the actual GLBs, terrain support and road masks in both projections and after an appearance edit. */
@Tag("on-demand")
class GpuRoadControlSmokeTest {
    private static final Coords AT = new Coords(3, 3);

    private static BoardScene scene(HexAppearance.TrafficControl control, boolean deck) throws Exception {
        Board board = Board.createEmptyBoard(7, 7);
        int exits = 27;
        for (int d : new int[] { 0, 1, 3, 4 }) {
            int sides = 1 << d | 1 << ((d + 3) % 6);
            for (int i = 1; i <= 2; i++) {
                board.setHex(AT.translated(d, i), new Hex(0, terrain(sides, deck), ""));
            }
        }
        Hex junction = new Hex(0, terrain(exits, deck), "");
        var appearance = HexAppearance.withTrafficControl(null, control);
        if (appearance != null) { junction.setAppearance(Map.of(deck ? "bridgeRoad" : "road", appearance)); }
        board.setHex(AT, junction);
        var captured = new AtomicReference<BoardScene>();
        SwingUtilities.invokeAndWait(() -> captured.set(new BoardScene(0, 7, 7,
              BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of())));
        return captured.get();
    }

    private static String terrain(int sides, boolean deck) {
        return deck ? "water:1;bridge:1:" + sides + ";bridge_cf:40;bridge_elev:2;bridge_road:1:" + sides
              : "pavement:1;road:1:" + sides;
    }

    @Test
    void stopSignsAndSignalsRenderOnTheirSharedRoadApproaches() throws Exception {
        BoardScene stops = scene(HexAppearance.TrafficControl.STOPS, false);
        BoardScene signals = scene(HexAppearance.TrafficControl.SIGNALS, false);
        BoardScene bridge = scene(HexAppearance.TrafficControl.SIGNALS, true);
        BoardScene gantry = scene(HexAppearance.TrafficControl.GANTRY, false);
        BoardScene gantryBridge = scene(HexAppearance.TrafficControl.GANTRY, true);
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "road-controls");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1100, 850);
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
                    var scenes = List.of(stops, signals, stops, bridge, gantry, gantryBridge);
                    var names = List.of("stops", "signals", "stops-restored", "bridge-signals", "gantry", "bridge-gantry");
                    for (int i = 0; i < scenes.size(); i++) {
                        var scene = scenes.get(i);
                        int level = i == 3 || i == 5 ? 2 : 0;
                        String asset = "scenery/road/" + (i >= 4 ? "traffic-signal-gantry"
                              : i == 0 || i == 2 ? "stop-sign" : "traffic-signal");
                        for (boolean angled : new boolean[] { false, true }) {
                            camera.setIsometric(angled);
                            // Native gantry reach needs the wider view to fall below the ordinary prop LOD threshold.
                            camera.camera.zoom = i >= 4 ? .20f : .16f;
                            if (angled) { camera.orbit(25 - camera.azimuth(), 55 - camera.tilt()); }
                            camera.center(BoardGeometry.center(AT, level));
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            GpuReviewFrame.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            var installed = installedControls(terrain, asset);
                            assertEquals(4, installed.size(), "Every junction approach loads a GLB after an edit");
                            for (var prop : installed) {
                                assertEquals(null, GpuTestSupport.<Object>field(prop, "decorationId"), "Terrain controls are not independently editable objects");
                                if (level == 2) {
                                    var bounds = (BoundingBox) GpuTestSupport.field(prop, "bounds");
                                    assertTrue(bounds.min.z >= 1.9f * BoardGeometry.level(), "Deck signals stand on the deck");
                                }
                            }
                            GpuReviewFrame.save(new File(output, names.get(i) + (angled ? "-oblique.png" : "-top.png")));
                            if ((i == 1 || i == 4) && angled) {
                                assertSignalLod(terrain, asset, 1);
                                captureBlink(terrain, frame, camera, scene, output, names.get(i));
                                // The ordinary prop thresholds select the detailed mesh only at close inspection.
                                var signal = scene.tile(AT).features().stream()
                                      .filter(f -> f.asset().equals(asset)
                                            && f.decoration().id().endsWith(":3")).findFirst().orElseThrow();
                                camera.camera.zoom = i == 4 ? .025f : .006f;
                                double rotation = Math.toRadians(signal.rotation());
                                float across = i == 4 ? 4 : 0;
                                camera.center(new com.badlogic.gdx.math.Vector3(
                                      BoardGeometry.centerX(AT) + (signal.x() + across * (float) Math.cos(rotation)) * BoardGeometry.hexScale(),
                                      BoardGeometry.centerY(AT) + (signal.y() + across * (float) Math.sin(rotation)) * BoardGeometry.hexScale(),
                                      (i == 4 ? 9 : 7.6f) * BoardGeometry.hexScale()));
                                GpuReviewFrame.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                assertSignalLod(terrain, asset, 0);
                                captureBlink(terrain, frame, camera, scene, output, names.get(i) + "-lod0");
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road control rendering", failure.get()); }
    }

    private static List<Object> installedControls(GpuTerrain terrain, String asset) throws Exception {
        List<Object> result = new ArrayList<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                var feature = (BoardScene.Feature) GpuTestSupport.field(prop, "decorationFeature");
                if (AT.equals(GpuTestSupport.field(prop, "coords")) && feature != null && feature.asset().equals(asset)) { result.add(prop); }
            }
        }
        return result;
    }

    private static void assertSignalLod(GpuTerrain terrain, String asset, int level) throws Exception {
        var controls = installedControls(terrain, asset);
        assertEquals(4, controls.size());
        for (Object prop : controls) {
            var instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
            var levels = (List<?>) GpuTestSupport.field(prop, "levels");
            assertNotNull(levels, "Actual GLB levels must load, not the missing-model fallback");
            assertEquals(2, levels.size());
            assertEquals(level, GpuTestSupport.<Object>field(prop, "lod"), asset + ": the ordinary prop thresholds select the captured LOD");
            assertSame(levels.get(level), instance.model);
            assertNotNull(instance.getMaterial("Amber"));
            assertTrue(instance.getMaterial("Amber").has(GpuModelLight.TYPE));
        }
    }

    private static void captureBlink(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, File output, String name) throws Exception {
        byte[] on = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(false);
        GpuReviewFrame.save(new File(output, name + "-amber-on.png"));
        terrain.animate(.5f, List.of(), 1);
        frame.render(terrain, camera, scene);
        byte[] off = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixels(false);
        assertTrue(!java.util.Arrays.equals(on, off), name + ": amber visibly switches off without rebuilding terrain");
        GpuReviewFrame.save(new File(output, name + "-amber-off.png"));
        terrain.animate(.5f, List.of(), 1);
        frame.render(terrain, camera, scene);
    }
}
