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
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Converted scenery (placed objects) and terrain-owned rows (geysers) touch the first solid surface under their anchor,
 * checked against the scene before they are added.
 */
@Tag("on-demand")
class GpuSceneryPlacementSmokeTest {
    private static final Coords CENTER = new Coords(2, 2);
    record Sample(String name, int elevation, String ground, String structure, String fluff, boolean lakebed) { }
    private static final List<Sample> SAMPLES = List.of(
          new Sample("submerged-geyser", -2, "water:1", "", "geyser:1", true),
          new Sample("deep-active-geyser", 0, "water:3", "", "geyser:2", true),
          new Sample("submerged-skylight", -1, "water:2", "", "fluff:6:0", true),
          new Sample("raised-ground", 3, "pavement:1", "", "fluff:6:0", false),
          new Sample("building-roof", 2, "pavement:1", "building:2;bldg_elev:3;bldg_cf:90", "fluff:6:0", false),
          new Sample("building-in-water", 1, "water:2", "building:2;bldg_elev:4;bldg_cf:90", "fluff:6:0", false),
          new Sample("tank-top", 0, "pavement:1", "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100", "fluff:6:0", false),
          new Sample("industrial-top", 1, "pavement:1", "heavy_industrial:2", "fluff:6:0", false),
          new Sample("bridge-deck", 0, "water:2", "bridge:1:9;bridge_elev:2;bridge_cf:90", "fluff:6:0", false),
          new Sample("solid-ice", -1, "water:2;ice:1", "", "fluff:6:0", false));

    @Test
    void sceneryRestsOnSolidGeometryAndEffectsFollowItsInstalledTransform() throws Exception {
        var controls = new ArrayList<BoardScene>();
        var decorated = new ArrayList<BoardScene>();
        for (Sample sample : SAMPLES) {
            var control = capture(sample, false);
            if (!sample.structure().isEmpty()) {
                assertTrue(control.tile(CENTER).features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.BUILDING
                      || f.kind() == BoardScene.FeatureKind.INDUSTRIAL || f.kind() == BoardScene.FeatureKind.FUEL_TANK
                      || f.kind() == BoardScene.FeatureKind.PROP),
                      sample.name() + " must include an actual solid model");
            }
            controls.add(control);
            decorated.add(capture(sample, true));
        }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "scenery-placement");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1400, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (int i = 0; i < SAMPLES.size(); i++) {
                        Sample sample = SAMPLES.get(i);
                        BoardScene control = controls.get(i), scene = decorated.get(i);
                        Vector3 center = BoardGeometry.center(CENTER, 0);
                        // A geyser's row, or the placed object the conversion made of the legacy art; its support is
                        // sampled under its own anchor.
                        var feature = scene.tile(CENTER).features().stream()
                              .filter(f -> f.kind() == BoardScene.FeatureKind.SCENERY || f.decoration() != null)
                              .findFirst().orElseThrow();
                        Vector3 anchor = new Vector3(center).add(feature.x() * BoardGeometry.hexScale(),
                              feature.y() * BoardGeometry.hexScale(), 0);
                        frame.prepare(terrain, camera, control);
                        terrain.update(control);
                        float support;
                        if (sample.lakebed()) {
                            support = new BoardSurface(control, control.tile(CENTER)).height(anchor.x, anchor.y);
                            assertTrue(support < BoardGeometry.waterZ(control.tile(CENTER)));
                        } else {
                            var ray = new Ray(new Vector3(anchor.x, anchor.y, 1000), new Vector3(0, 0, -1));
                            var hit = terrain.selectionHit(control, ray);
                            assertNotNull(hit, sample.name());
                            support = 1000 - (float) Math.sqrt(hit.distance());
                        }
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(.5f, List.of());
                        ModelInstance instance = instance(terrain, feature.asset());
                        BoundingBox placed = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
                        assertEquals(support, placed.min.z, .002f, sample.name() + " must touch the first solid surface");
                        if (sample.fluff().startsWith("geyser:")) {
                            var source = geyser(terrain);
                            // The steam starts at the model's own vent, wherever the installed transform puts it.
                            assertEquals(new Vector3(0, 0, GpuGeysers.vent(instance.model)).mul(instance.transform).z,
                                  source.origin().z, .002f, "Effects must stay attached to the submerged model");
                            assertTrue(source.origin().z < BoardGeometry.waterZ(scene.tile(CENTER)));
                        }
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .22f;
                            camera.center(new Vector3(center.x, center.y, support));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, sample.name() + (oblique ? "-oblique.png" : "-top.png")));
                            assertEquals(placed.min.z, instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform).min.z,
                                  .001f, "Changing the camera cannot move scenery");
                        }
                        if (sample.lakebed()) {
                            frame.render(terrain, camera, scene, false);
                            GpuReviewFrame.save(new File(output, sample.name() + "-bed.png"));
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose(); terrain.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scenery solid support", failure.get()); }
    }

    /**
     * The sample's 5x5 board, its centre with the sample's art when {@code scenery}, converted as every load site
     * converts a board and captured as every 3D view captures it: legacy art becomes placed objects, terrain-owned art
     * (a geyser) stays a scenery row.
     */
    static BoardScene capture(Sample sample, boolean scenery) throws Exception {
        var board = Board.createEmptyBoard(5, 5);
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) {
            board.setHex(new Coords(x, y), new Hex(sample.elevation(), sample.ground(), ""));
        }
        board.setHex(CENTER, new Hex(sample.elevation(), sample.ground()
              + (sample.structure().isEmpty() ? "" : ";" + sample.structure())
              + (scenery ? ";" + sample.fluff() : ""), ""));
        BoardSceneryLayouts.convert(board);
        return BoardScenes.scene(board);
    }

    private static ModelInstance instance(GpuTerrain terrain, String asset) throws Exception {
        String name = asset.substring(asset.lastIndexOf('/') + 1);
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                ModelInstance instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
                if (CENTER.equals(GpuTestSupport.field(prop, "coords")) && instance.nodes.first().id.equals(name)) { return instance; }
            }
        }
        throw new AssertionError("Missing installed scenery: " + asset);
    }

    private static GpuGeysers.Emitter geyser(GpuTerrain terrain) throws Exception {
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            var sources = (List<?>) GpuTestSupport.field(chunk, "geysers");
            if (!sources.isEmpty()) { return (GpuGeysers.Emitter) sources.getFirst(); }
        }
        throw new AssertionError("Missing installed geyser emitter");
    }
}
