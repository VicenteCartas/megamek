/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Wall geometry uses the ordinary structure picking, height fitting and shadow draw in both graphics modes. */
@Tag("on-demand")
class GpuWallSmokeTest {
    @Test
    void solidWallsRenderAndPickAtTheirRulesHeightWithoutBuildingInteriors() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = null;
                GpuReviewFrame frame = null;
                try {
                    var center = BoardWallTest.CENTER;
                    var walls = Map.of(center, 39, center.translated(0), 8, center.translated(1), 16,
                          center.translated(2), 32, center.translated(5), 4, new Coords(2, 6), 0);
                    var scene = BoardWallTest.scene(walls, 3, 1);
                    terrain = new GpuTerrain();
                    var settings = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                          0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                    frame = new GpuReviewFrame(settings);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    camera.zoom(.65f);
                    camera.center(BoardGeometry.center(center, 2));
                    File output = new File(System.getProperty("megamek.gpu.screenshots"), "walls");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    for (boolean classic : new boolean[] { false, true }) {
                        terrain.setColumns(classic);
                        terrain.update(scene);
                        frame.prepare(terrain, camera, scene);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        assertTrue(terrain.ready(scene));
                        terrain.animate(0, List.of(), .2f);
                        for (Coords coords : walls.keySet()) {
                            Vector3 start = BoardGeometry.center(coords, 4).add(.3f, .2f, 40);
                            var ray = new Ray(start, new Vector3(0, 0, -1));
                            var hit = terrain.hit(scene, ray);
                            assertNotNull(hit);
                            assertEquals(coords, hit.coords());
                            double distance = Math.sqrt(hit.distance());
                            assertTrue(distance >= 40 - 8 * BoardGeometry.hexScale() && distance <= 40.2,
                                  "The authored roof caps the wall's complete rules levels");
                            assertNotNull(terrain.selectionHit(scene, ray), "The rendered wall remains selectable");
                        }
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            assertTrue(((List<?>) GpuTestSupport.field(chunk, "struts")).isEmpty(),
                                  "A solid wall has no fabricated building floors or interior struts");
                        }
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, classic ? "classic.png" : "enhanced.png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                    // A closed fortress exposes repeated straight bays and every corner, not just one junction.
                    var ring = center.allAtDistance(2);
                    Map<Coords, Integer> enclosure = new LinkedHashMap<>();
                    for (Coords coords : ring) {
                        int exits = 0;
                        for (int direction = 0; direction < 6; direction++) {
                            if (ring.contains(coords.translated(direction))) { exits |= 1 << direction; }
                        }
                        enclosure.put(coords, exits);
                    }
                    scene = fortress(enclosure);
                    terrain.setColumns(false);
                    terrain.update(scene);
                    frame.setAmbientOcclusion(true);
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(center, 1.5f));
                    camera.camera.zoom = .46f;
                    camera.update();
                    renderReady(frame, terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fortress-oblique.png"));
                    camera.orbit(120, 0);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fortress-reverse.png"));
                    camera.setIsometric(false);
                    camera.center(BoardGeometry.center(center, 0));
                    camera.camera.zoom = .46f;
                    camera.update();
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fortress-top.png"));
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(center.translated(3, 2), 1.5f));
                    camera.camera.zoom = .18f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "fortress-detail.png"));
                    assertTrue(wallLodCount(terrain, 0) > 0, "Close walls draw the detailed mesh");
                    camera.camera.zoom = 7;
                    camera.center(BoardGeometry.center(center, 0));
                    camera.update();
                    renderReady(frame, terrain, camera, scene);
                    assertTrue(wallLodCount(terrain, 2) > 0, "Distant wall slots reuse the actual simpler LOD1 mesh");
                    scene = BoardWallTest.mixedScene();
                    terrain.update(scene);
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(center, 2));
                    camera.camera.zoom = .45f;
                    camera.update();
                    renderReady(frame, terrain, camera, scene);
                    for (var tile : scene.tiles()) {
                        for (var feature : tile.features()) {
                            if (!BoardWall.supports(feature.asset())) { continue; }
                            var start = BoardGeometry.center(tile.coords(), tile.elevation() + feature.height())
                                  .add(.3f, .2f, 40);
                            var hit = terrain.hit(scene, new Ray(start, new Vector3(0, 0, -1)));
                            assertNotNull(hit);
                            assertEquals(tile.coords(), hit.coords());
                            assertTrue(Math.sqrt(hit.distance()) >= 40 - 8 * BoardGeometry.hexScale()
                                  && Math.sqrt(hit.distance()) <= 40.2,
                                  "Each tower keeps its own rules height beside lower wall bays");
                        }
                    }
                    GpuReviewFrame.save(new File(output, "mixed-height-oblique.png"));
                    camera.orbit(120, 0);
                    renderReady(frame, terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mixed-height-reverse.png"));
                    camera.setIsometric(false);
                    camera.center(BoardGeometry.center(center, 0));
                    renderReady(frame, terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mixed-height-top.png"));
                    reviewFamilies(frame, terrain, camera, output);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (frame != null) { frame.dispose(); }
                    if (terrain != null) { terrain.dispose(); }
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Wall terrain review", failure.get()); }
    }

    private static void reviewFamilies(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, File output)
          throws Exception {
        int[] cfs = { 10, 15, 40, 90, 150, 151 };
        Hex[] hexes = new Hex[13 * 7];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 13; x++) {
                boolean wall = x % 2 == 1 && y >= 2 && y <= 4;
                int exits = y == 2 ? 8 : y == 4 ? 1 : 9;
                hexes[y * 13 + x] = new Hex(0, wall ? "building:5:" + exits
                      + ";bldg_elev:2;bldg_cf:" + cfs[x / 2] + ";bldg_basement_type:1" : "", "");
            }
        }
        Board board = new Board(13, 7, hexes);
        BoardSceneryLayouts.convert(board);
        var scene = captured(board);
        terrain.update(scene);
        camera.setIsometric(true);
        camera.fit(scene);
        camera.center(BoardGeometry.center(new Coords(6, 3), 1));
        camera.camera.zoom = .55f;
        camera.update();
        renderReady(frame, terrain, camera, scene);
        GpuReviewFrame.save(new File(output, "cf-families-oblique.png"));
        camera.setIsometric(false);
        camera.center(BoardGeometry.center(new Coords(6, 3), 0));
        camera.update();
        renderReady(frame, terrain, camera, scene);
        GpuReviewFrame.save(new File(output, "cf-families-top.png"));

        var assets = (GpuAssets) GpuTestSupport.field(terrain, "assets");
        var civilian = assets.wallLevels(BoardWall.layout("buildings/wall-9", 2, BoardWall.Family.CIVILIAN));
        var castle = assets.wallLevels(BoardWall.layout("buildings/wall-9", 2, BoardWall.Family.CASTLE_BRIAN));
        assertNotSame(civilian, castle, "The cache distinguishes identical layouts from different families");
        assertSame(castle, assets.wallLevels(BoardWall.layout("buildings/wall-9", 2, BoardWall.Family.CASTLE_BRIAN)));
        Coords edited = new Coords(11, 3);
        var changedHex = board.getHex(edited).duplicate();
        changedHex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_WEIGHT, 0));
        changedHex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_CF, 10));
        board.setHex(edited, changedHex);
        var changed = captured(board);
        assertFalse(scene.tile(edited).sameGeometry(changed.tile(edited)));
        terrain.update(changed);
        renderReady(frame, terrain, camera, changed);
        boolean replaced = false;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                if (edited.equals(GpuTestSupport.field(prop, "coords"))) {
                    assertSame(civilian.getFirst(), GpuTestSupport.field(prop, "pickingModel"),
                          "A weight edit replaces the installed draw and picking model");
                    replaced = true;
                }
            }
        }
        assertTrue(replaced);
        GpuReviewFrame.save(new File(output, "cf-family-changed.png"));
    }

    private static BoardScene captured(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
    }

    /** A changed AO configuration can invalidate shaders during the first render; capture only a settled frame. */
    private static void renderReady(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene)
          throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            frame.prepare(terrain, camera, scene);
            GpuReviewFrame.settle(terrain, null, scene, camera);
            frame.render(terrain, camera, scene);
            if (terrain.ready(scene) && !terrain.busy()) { return; }
        }
        fail("Wall review did not finish rebuilding its display shaders");
    }

    private static int wallLodCount(GpuTerrain terrain, int expected) throws Exception {
        int count = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                var levels = (List<?>) GpuTestSupport.field(prop, "levels");
                if (levels == null || levels.size() < 3) { continue; }
                assertEquals(4, levels.size());
                assertNotSame(levels.get(0), levels.get(1));
                assertSame(levels.get(1), levels.get(2));
                assertSame(levels.get(1), levels.get(3));
                assertSame(levels.getFirst(), GpuTestSupport.field(prop, "pickingModel"));
                var near = (Model) levels.get(0);
                var far = (Model) levels.get(2);
                assertTrue(indices(far) < indices(near));
                if ((int) GpuTestSupport.field(prop, "lod") == expected) { count++; }
            }
        }
        return count;
    }

    private static int indices(Model model) {
        int count = 0;
        for (var mesh : model.meshes) { count += mesh.getNumIndices(); }
        return count;
    }

    /** A real captured board retains the current paved road alongside the wall as a visible thickness reference. */
    private static BoardScene fortress(Map<Coords, Integer> walls) {
        Hex[] hexes = new Hex[81];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                Integer exits = walls.get(new Coords(x, y));
                String terrain = exits == null ? ""
                      : "building:5:" + exits + ";bldg_elev:3;bldg_cf:120;bldg_basement_type:1";
                if (x == 1 && y >= 1 && y <= 7) { terrain = "road:1:" + (y == 1 ? 8 : y == 7 ? 1 : 9); }
                hexes[y * 9 + x] = new Hex(0, terrain, "");
            }
        }
        Board board = new Board(9, 9, hexes);
        BoardSceneryLayouts.convert(board);
        return new BoardScene(0, 9, 9, BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of());
    }
}
