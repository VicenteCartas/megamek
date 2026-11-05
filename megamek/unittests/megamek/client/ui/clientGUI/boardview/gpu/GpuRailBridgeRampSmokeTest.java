/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Installed rails and their editor previews follow a bridge-owned ramp across a terrain chunk boundary. */
@Tag("on-demand")
class GpuRailBridgeRampSmokeTest {
    private static final Coords BRIDGE = new Coords(2, TerrainLod.CHUNK_SIZE - 1);
    private static final Coords APPROACH = BRIDGE.translated(3);

    @Test
    void installedRailsAndPreviewStayOnTheRampAfterBridgeElevationChanges() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "rail-bridge-ramps");
        Files.createDirectories(output.toPath());
        assertFalse(TerrainLod.sameChunk(BRIDGE, APPROACH), "The ramp crosses the worker preparation boundary");
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
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
                    camera.setIsometric(true);
                    camera.camera.zoom = .16f;
                    camera.orbit(25 - camera.azimuth(), 55 - camera.tilt());
                    camera.center(BoardGeometry.center(BRIDGE, .5f).lerp(BoardGeometry.center(APPROACH, .5f), .5f));
                    var scene = scene();
                    for (int elevation : new int[] { 0, 1 }) {
                        if (elevation == 1) {
                            scene = BoardScenes.replace(scene, tile(BRIDGE, elevation));
                        }
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "rail-ramp-level-" + elevation + ".png"));
                        checkRoutes(terrain, scene, elevation);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Rail bridge approach rendering", failure.get()); }
    }

    private static void checkRoutes(GpuTerrain terrain, BoardScene scene, int elevation) throws Exception {
        var ground = triangles(installedRoute(terrain, APPROACH));
        var deck = triangles(installedRoute(terrain, BRIDGE));
        var seam = BoardGeometry.center(BRIDGE, 0).lerp(BoardGeometry.center(APPROACH, 0), .5f);
        var groundEnds = seamVertices(ground, seam);
        var deckEnds = seamVertices(deck, seam);
        assertFalse(groundEnds.isEmpty(), "Installed ground rail reaches the shared edge");
        sameVertices(groundEnds, deckEnds, "Ground and deck rails meet at the chunk boundary");
        float scale = BoardGeometry.hexScale();
        float lift = GpuRoads.SURFACE_LIFT * scale;
        float railTop = RailRoute.RAIL_TOP * scale;
        assertEquals(.5f * elevation * BoardGeometry.level() + lift + railTop,
              groundEnds.stream().mapToDouble(p -> p.z).max().orElseThrow(), .02,
              "The installed approach reaches the raised bridge, including after its elevation edit");
        // The editor preview of laying this track: its pieces (the deck's too) reach the installed rails' seam vertices.
        var bare = new ArrayList<>(scene.tiles());
        for (Coords at : List.of(APPROACH, BRIDGE)) {
            var tile = scene.tile(at);
            bare.set(at.getX() * scene.height() + at.getY(),
                  tile.withFeatures(tile.features().stream().filter(feature -> !BoardRail.route(feature)).toList()));
        }
        var preview = terrain.previewRoutes(terrain.compareRoutes(scene.withTiles(bare), scene, Set.of(APPROACH, BRIDGE)).join());
        try {
            for (Coords at : List.of(APPROACH, BRIDGE)) {
                assertNotNull(preview.ghosts.get(at), "The new track is previewed at " + at);
                var pieces = preview.ghosts.get(at).stream().flatMap(piece -> triangles(piece).stream()).toList();
                var ends = seamVertices(pieces, seam);
                for (var point : at.equals(APPROACH) ? groundEnds : deckEnds) {
                    assertTrue(ends.stream().anyMatch(p -> p.epsilonEquals(point, .001f)),
                          "Editor preview uses the installed ramp at " + at + ": " + point);
                }
            }
        } finally { preview.dispose(); }
        // Both rail heads remain above the bridge's solid, on its eased grade, on each side of the ownership boundary.
        if (elevation == 0) { return; }
        for (float along : new float[] { .35f, .45f, .55f, .65f }) {
            var point = BoardGeometry.center(BRIDGE, 0).lerp(BoardGeometry.center(APPROACH, 0), along);
            float expected = (1 - BoardBridgeSlopeTest.eased(along)) * elevation * BoardGeometry.level() + lift + railTop;
            for (int side : new int[] { -1, 1 }) {
                var ray = new Ray(new Vector3(point.x + side * BoardRail.GAUGE_HALF * scale, point.y, 200),
                      new Vector3(0, 0, -1));
                var hit = new Vector3();
                assertTrue(Intersector.intersectRayTriangles(ray, along < .5f ? deck : ground, hit),
                      "The installed rail must cover the ramp at " + along);
                // The rail's samples, a few pixels apart, chord the eased curve by up to a tenth.
                assertEquals(expected, hit.z, .1f, "The rail head stays above the ramp at " + along);
            }
        }
    }

    private static ModelInstance installedRoute(GpuTerrain terrain, Coords at) throws Exception {
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                var feature = (BoardScene.Feature) field(prop, "decorationFeature");
                if (at.equals(field(prop, "coords")) && feature != null && BoardRail.route(feature)) {
                    return (ModelInstance) field(prop, "instance");
                }
            }
        }
        throw new AssertionError("No installed rail at " + at);
    }

    private static List<Vector3> triangles(ModelInstance instance) {
        return GpuTerrain.triangles(instance.model).stream().map(p -> new Vector3(p).mul(instance.transform)).toList();
    }

    private static List<Vector3> seamVertices(List<Vector3> triangles, Vector3 seam) {
        var result = new ArrayList<Vector3>();
        for (var point : triangles) {
            if (Math.abs(point.y - seam.y) < .001f && result.stream().noneMatch(p -> p.epsilonEquals(point, .001f))) {
                result.add(point);
            }
        }
        return result;
    }

    private static void sameVertices(List<Vector3> expected, List<Vector3> actual, String message) {
        assertEquals(expected.size(), actual.size(), message);
        for (var point : expected) {
            assertTrue(actual.stream().anyMatch(p -> p.epsilonEquals(point, .001f)), message + ": " + point);
        }
    }

    private static BoardScene scene() {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 11; y++) { tiles.add(tile(new Coords(x, y), 0)); }
        }
        return new BoardScene(0, 5, 11, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile tile(Coords at, int elevation) {
        boolean water = at.getY() < APPROACH.getY();
        boolean track = at.getX() == BRIDGE.getX() && at.getY() >= 5;
        boolean bridge = track && water;
        var ground = BoardScenes.tile(at, BoardScene.Surface.GRASS, 0, water ? 1 : -1, 0);
        var hex = new Hex(0);
        if (bridge) {
            hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 9));
            hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, elevation));
            hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 40));
        }
        if (track) {
            var placement = bridge ? BoardDecoration.Placement.on("bridge", 0) : BoardDecoration.Placement.ground();
            hex.setDecorations(List.of(new BoardDecoration("rail-" + at, "prop", RailRoute.Kind.RAIL.asset, null,
                  0, 0, 0, false, 1, placement, 0).withConnections(9)));
        }
        var tile = new BoardScene.Tile(at, 0, ground.waterDepth(), false, 0, ground.surface(), null, null,
              BoardFeatures.capture(hex, at, Map.of()), List.of(), ground.liquid(), null,
              true, BoardRoad.Kind.NONE);
        return bridge ? BoardBridgeTypeTest.appearance(tile, Map.of("bridge", HexAppearance.TRUSS_PILLARS)) : tile;
    }
}
