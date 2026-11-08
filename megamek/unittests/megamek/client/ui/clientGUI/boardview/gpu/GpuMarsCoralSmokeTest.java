/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

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
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native capture exercises terrain fitting, both projections, snowy cover and the shared vegetation passes. */
@Tag("on-demand")
class GpuMarsCoralSmokeTest {
    @Test
    void rendersCoralWoodsOnMarsAndSnowThroughTheExistingBoardScene() throws Exception {
        var board = Board.createEmptyBoard(10, 8);
        for (int x = 0; x < 10; x++) for (int y = 0; y < 8; y++) {
            String cover = y >= 4 ? "snow:1" : "";
            if (x >= 1 && x <= 8 && (y == 2 || y == 5)) {
                int density = x < 4 ? 1 : x < 7 ? 2 : 3;
                cover += ";" + (x == 8 ? "jungle" : "woods") + ":" + density
                      + ";foliage_elev:" + (x == 8 ? 1 : 2);
            }
            if (x == 5) { cover += ";road:1:9"; }
            board.setHex(new Coords(x, y), new Hex(x == 0 ? 2 : x == 1 ? 1 : 0, cover, "mars"));
        }
        AtomicReference<BoardScene> captured = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                captured.set(fixture.source.takeFrame().scene());
            });
        }
        BoardScene scene = captured.get();
        Set<String> plants = scene.tiles().stream().flatMap(t -> t.features().stream())
              .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).map(BoardScene.Feature::asset)
              .collect(Collectors.toSet());
        assertTrue(plants.size() >= 6 && BoardFeatures.MARS_CORALS.containsAll(plants),
              "Local stands mix eligible coral forms; they need not contain the entire catalog");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "mars-corals");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1100);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    BoardScenes.tune(.8f, BoardGeometry.DEFAULT_TRANSITIONS);
                    // Loading must see the same shader flags as the frame that follows.
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    var ray = new Ray(BoardGeometry.center(new Coords(3, 3), 0).add(0, 0, 1000),
                          new Vector3(0, 0, -1));
                    BoardGeometry.Hit picked = null;
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .58f;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0));
                        frame.render(terrain, camera, scene);
                        var hit = terrain.hit(scene, ray);
                        if (picked == null) { picked = hit; } else { assertEquals(picked, hit); }
                        assertTrue(hit != null);
                        GpuReviewFrame.save(new File(output, oblique ? "mars-oblique.png" : "mars-top.png"));
                    }
                    for (int y : new int[] { 2, 5 }) {
                        camera.camera.zoom = .23f;
                        camera.center(BoardGeometry.center(new Coords(3, y), 0));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, y == 2 ? "mars-close.png" : "mars-snow-close.png"));
                    }
                    camera.camera.zoom = .10f;
                    camera.center(BoardGeometry.center(new Coords(4, 2), 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mars-material-detail.png"));
                    camera.orbit(0, 19);
                    camera.center(BoardGeometry.center(new Coords(3, 2), 0).add(0, 0, 12));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mars-root-contact.png"));
                    assertRooted(terrain);
                    var stones = scene.tiles().stream().filter(t -> t.surface() == BoardScene.Surface.MARS
                          && t.coords().getX() > 1 && t.features().stream()
                          .anyMatch(f -> f.kind() == BoardScene.FeatureKind.SCATTER)).findFirst().orElseThrow();
                    camera.setIsometric(true);
                    camera.camera.zoom = .10f;
                    camera.center(BoardGeometry.center(stones.coords(), stones.elevation()));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mars-scatter-bedrock.png"));
                    // Far meshes and their shadows use the same scene and leave board picking unchanged.
                    camera.camera.zoom = 2.4f;
                    frame.render(terrain, camera, scene);
                    assertEquals(picked, terrain.hit(scene, ray));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Mars coral review", failure.get()); }
    }

    private static void assertRooted(GpuTerrain terrain) throws Exception {
        Map<String, List<Vector3>> roots = new HashMap<>();
        for (String asset : BoardFeatures.MARS_CORALS) {
            var shape = BoardShape.loadModel(asset);
            roots.put(asset, shape.polygons().stream().filter(p -> p.normal().z < -.25f)
                  .map(p -> p.points()[0].cpy().add(p.points()[1]).add(p.points()[2]).scl(1f / 3))
                  .filter(p -> p.z < shape.height() * .05f).toList());
            assertTrue(roots.get(asset).size() > 20, "The asset supplies a broad mineral root layer");
        }
        int colonies = 0;
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                String asset = (String) field(prop, "treeAsset");
                if (!roots.containsKey(asset)) { continue; }
                colonies++;
                var instance = (ModelInstance) field(prop, "instance");
                float height = ((BoundingBox) field(prop, "bounds")).getDepth();
                var coords = (Coords) field(prop, "coords");
                // A colony beside a road can straddle the hex edge; inspect the actual neighbouring triangles too.
                var support = new ArrayList<>(terrain.tacticalSurface(coords).faces());
                for (int direction = 0; direction < 6; direction++) {
                    var neighbor = terrain.tacticalSurface(coords.translated(direction));
                    if (neighbor != null) { support.addAll(neighbor.faces()); }
                }
                var points = roots.get(asset);
                for (int i = 0; i < points.size(); i += Math.max(1, points.size() / 32)) {
                    var point = points.get(i).cpy().mul(instance.transform);
                    float ground = BoardSurface.sampleHeight(support, point.x, point.y, Float.NEGATIVE_INFINITY);
                    assertTrue(point.z <= ground + BoardRelief.metres(.05f),
                          asset + " at " + coords + " has floating roots: gap=" + (point.z - ground));
                    assertTrue(point.z > ground - height * .18f,
                          asset + " at " + coords + " is buried too far into the terrace: gap=" + (point.z - ground));
                }
            }
        }
        assertTrue(colonies > 20, "Check multiple coral forms on dry ground, snow and beside steps");
    }
}
