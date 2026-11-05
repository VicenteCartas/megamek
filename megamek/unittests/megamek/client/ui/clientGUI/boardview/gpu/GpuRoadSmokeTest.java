/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;

@Tag("on-demand")
class GpuRoadSmokeTest {
    @Test
    void keepsMinesRoadsVisibleAcrossTheRealBoard() throws Exception {
        BoardScene scene = BoardScenes.minesScene();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "roads");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 1440);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(weather(0));
                try {
                    BoardScenes.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(false);
                    camera.camera.zoom = 1;
                    camera.center(BoardGeometry.center(new Coords(7, 8), 0));
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene, camera.camera);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    terrain.animate(.5f, List.of());
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mines-roads-top.png"));
                    checkMinesRoads(scene, camera);
                    for (float zoom : new float[] { 2.5f, 1, .55f, 1 }) {
                        camera.camera.zoom = zoom;
                        camera.center(BoardGeometry.center(new Coords(7, 8), 0));
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "mines-roads-zoom-" + zoom + ".png"));
                        // At distant zoom the asphalt/paint/shoulder can share one pixel; check when inspecting it again.
                        if (zoom <= 1) { checkMinesRoads(scene, camera); }
                    }
                    // A nearby ground edit replaces the chunk atlas while reusing its unchanged road meshes.
                    var tiles = new ArrayList<>(scene.tiles());
                    var before = scene.tile(new Coords(8, 11));
                    tiles.set(tiles.indexOf(before), new BoardScene.Tile(before.coords(), before.elevation(), -1, false, 0,
                          before.surface(), null, null, List.of(), List.of(),
                          BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE, BoardFireSmoke.NONE, BoardScene.Biome.MUD));
                    var edited = new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles,
                          List.of(), List.of(), -1, "", List.of());
                    terrain.update(edited, camera.camera);
                    GpuReviewFrame.settle(terrain, null, edited, camera);
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(output, "mines-roads-edited.png"));
                    checkMinesRoads(edited, camera);
                    terrain.update(scene, camera.camera);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    checkMinesRoads(scene, camera);
                    camera.camera.zoom = .2f;
                    camera.center(BoardGeometry.center(new Coords(7, 14), 0));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mines-road-fluff-sand-top.png"));
                    checkMinesRoads(scene, camera);
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(new Coords(7, 14), 0));
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mines-road-fluff-sand-oblique.png"));
                    // This interior plateau fills the upper four fifths of the close view. Test actual scene
                    // coverage there, independently of blue skylight on the concrete and terrain.
                    int height = Gdx.graphics.getBackBufferHeight();
                    checkCoverage(frame, terrain, camera, scene, height / 5, height * 9 / 10, false,
                          "No sky may show through the Mines plateau, cliff feet or roadside slopes");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Mines road coverage", failure.get()); }
    }

    private static void checkMinesRoads(BoardScene scene, BoardCamera camera) {
        byte[] pixels = screen();
        for (var tile : scene.tiles()) {
            if (!BoardRoad.rendered(tile)) { continue; }
            var surface = new BoardSurface(scene, tile);
            var road = BoardRoad.of(scene, tile);
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            for (int direction = 0; direction < 6; direction++) {
                if ((tile.roadExits() & (1 << direction)) == 0) { continue; }
                Vector3 along = BoardGeometry.center(tile.coords().translated(direction), 0)
                      .sub(BoardGeometry.center(tile.coords(), 0)).nor();
                Vector3 across = new Vector3(-along.y, along.x, 0);
                // A road may bend through its border like a real road: sample beside its actual centre line.
                float offset = 0, nearest = Float.POSITIVE_INFINITY;
                for (float side = -9; side <= 9; side += .25f) {
                    float distance = road.distance(along.x * 24 + across.x * side, along.y * 24 + across.y * side);
                    if (distance < nearest) { nearest = distance; offset = side; }
                }
                Vector3 at = new Vector3(center).mulAdd(along, 24 * BoardGeometry.hexScale())
                      .mulAdd(across, (offset + 3) * BoardGeometry.hexScale());
                at.z = surface.height(at.x, at.y);
                Vector3 point = camera.camera.project(at);
                if (point.x < 3 || point.y < 3 || point.x >= Gdx.graphics.getBackBufferWidth() - 3
                      || point.y >= Gdx.graphics.getBackBufferHeight() - 3) { continue; }
                Vector3 color = pixelColor(pixels, point);
                assertTrue(color.x - color.z < 35,
                      "Mines road " + tile.coords().getBoardNum() + " exit " + direction + " is hidden: " + color);
            }
        }
    }

    @Test
    void capturesConcreteRetainingWallsBesideRoadRamps() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "roads");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(weather(0));
                try {
                    BoardScenes.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.orbit(-15, -20);
                    camera.camera.zoom = .22f;
                    for (int rise : new int[] { 1, 2, 3 }) {
                        var scene = ramp(BoardRoad.Kind.PAVED, BoardScene.Surface.CONCRETE, rise);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        assertTrue(terrain.ready(scene), "Retaining-wall captures require the prepared material families");
                        int[] roles = GpuTerrainPreparationSmokeTest.checkBlendedRoles(terrain, "concrete ramp " + rise);
                        assertTrue(roles[4] > 0, "The road fixture must retain actual concrete cliff triangles");
                        terrain.animate(.5f, List.of());
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0)
                              .lerp(BoardGeometry.center(new Coords(4, 4), rise), .5f));
                        for (int view = 0; view < 2; view++) {
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "concrete-road-walls-" + rise + "-" + view + ".png"));
                            camera.orbit(180, 0);
                        }
                    }
                    for (int exits : new int[] { 31, 63 }) {
                        var scene = BoardRoadSlopeTest.concreteJunction(exits);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        assertTrue(terrain.ready(scene), "Retaining-wall captures require the prepared material families");
                        GpuTerrainPreparationSmokeTest.checkBlendedRoles(terrain, "concrete junction " + exits);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .21f;
                        camera.center(BoardGeometry.center(BoardScenes.CENTER, 2));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "concrete-roundabout-" + exits + ".png"));
                        checkCoverage(frame, terrain, camera, scene, 0, Gdx.graphics.getBackBufferHeight(), true,
                              "Open road gates cannot leave sky seams");
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Concrete retaining wall review", failure.get()); }
    }

    @Test
    void capturesRoadMaterialsJunctionsAndRampsAndChecksLiveEdits() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "roads");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(weather(0));
                try {
                    BoardScenes.tune(.94f, true);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    var scene = roads(false);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    assertTrue(terrain.ready(scene), "Road materials settle with the visible lighting flags");
                    terrain.animate(.5f, List.of());
                    for (boolean oblique : new boolean[] { false, true }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .43f;
                        camera.center(BoardGeometry.center(BoardScenes.CENTER, 0));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, oblique ? "roads-oblique.png" : "roads-top.png"));
                    }
                    camera.camera.zoom = .19f;
                    camera.center(BoardGeometry.center(new Coords(2, 4), 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "junction-close.png"));
                    camera.camera.zoom = .17f;
                    camera.center(BoardGeometry.center(new Coords(3, 4), 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "mixed-branch.png"));
                    var edited = roads(true);
                    terrain.update(edited);
                    terrain.animate(.5f, List.of());
                    frame.render(terrain, camera, edited);
                    byte[] changed = screen();
                    var fresh = new GpuTerrain();
                    try {
                        frame.prepare(fresh, camera, edited);
                        fresh.update(edited);
                        assertTrue(fresh.ready(edited), "The clean-rebuild control uses the same prepared lighting");
                        fresh.animate(.5f, List.of());
                        frame.render(fresh, camera, edited);
                        byte[] rebuilt = screen();
                        long difference = 0;
                        for (int i = 0; i < changed.length; i++) {
                            difference += Math.abs(Byte.toUnsignedInt(changed[i]) - Byte.toUnsignedInt(rebuilt[i]));
                        }
                        assertTrue(difference / (double) changed.length < .1, "Road edits must match a clean rebuild");
                    } finally { fresh.dispose(); }
                    frame.configure(weather(1));
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(output, "junction-wet.png"));
                    frame.configure(weather(0));
                    camera.setIsometric(false);
                    camera.camera.zoom = .115f;
                    camera.center(BoardGeometry.center(BoardScenes.CENTER, 0));
                    for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                        var ends = ends(kind);
                        terrain.update(ends);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, ends);
                        GpuReviewFrame.save(new File(output, "road-end-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                        byte[] mapped = screen();
                        terrain.setNormalMaps(false);
                        frame.render(terrain, camera, ends);
                        assertTrue(roadDifference(mapped, screen()) > .05, "The " + kind + " normal map must affect the road pixels");
                        terrain.setNormalMaps(true);
                        frame.configure(weather(1));
                        frame.render(terrain, camera, ends);
                        assertTrue(roadDifference(mapped, screen()) > .5, "Wet " + kind + " must respond to lighting");
                        GpuReviewFrame.save(new File(output, "road-end-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-wet.png"));
                        frame.configure(weather(0));
                    }
                    camera.setIsometric(true);
                    camera.center(BoardGeometry.center(BoardScenes.CENTER, 0)
                          .lerp(BoardGeometry.center(BoardScenes.CENTER.translated(3), 0), .5f));
                    for (var pair : List.of(List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.GRAVEL),
                          List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL), List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.PAVED))) {
                        var change = materialChange(pair.getFirst(), pair.getLast());
                        terrain.update(change);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, change);
                        GpuReviewFrame.save(new File(output, "road-change-" + pair.getFirst() + "-" + pair.getLast() + ".png"));
                        if (pair.getFirst() == BoardRoad.Kind.DIRT && pair.getLast() == BoardRoad.Kind.PAVED) {
                            camera.setIsometric(false);
                            frame.render(terrain, camera, change);
                            GpuReviewFrame.save(new File(output, "road-change-dirt-asphalt-top.png"));
                            checkDirtCarry(camera, change);
                            camera.setIsometric(true);
                        }
                    }
                    camera.setIsometric(false);
                    camera.camera.zoom = .115f;
                    camera.center(BoardGeometry.center(BoardScenes.CENTER, 0));
                    for (var kind : List.of(BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                        var unsealed = materialChange(kind, kind);
                        terrain.update(unsealed);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, unsealed);
                        GpuReviewFrame.save(new File(output, "unpaved-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                        frame.configure(weather(1));
                        camera.setIsometric(true);
                        frame.render(terrain, camera, unsealed);
                        GpuReviewFrame.save(new File(output, "unpaved-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-wet.png"));
                        frame.configure(weather(0));
                        camera.setIsometric(false);
                    }
                    var bridge = bridge();
                    terrain.update(bridge);
                    terrain.animate(.5f, List.of());
                    camera.setIsometric(true);
                    camera.camera.zoom = .24f;
                    camera.center(BoardGeometry.center(BoardScenes.CENTER, 0));
                    frame.render(terrain, camera, bridge);
                    GpuReviewFrame.save(new File(output, "bridge-approaches.png"));
                    camera.camera.zoom = .13f;
                    camera.orbit(-15, -20);
                    camera.center(BoardGeometry.center(new Coords(4, 3), 0)
                          .lerp(BoardGeometry.center(new Coords(4, 4), 1), .5f));
                    for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                        var ramp = ramp(kind);
                        terrain.update(ramp);
                        terrain.animate(.5f, List.of());
                        frame.render(terrain, camera, ramp);
                        GpuReviewFrame.save(new File(output, "road-ramp-" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                    }
                    for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.ROCK,
                          BoardScene.Surface.SNOW)) {
                        var embankment = ramp(BoardRoad.Kind.PAVED, family, family == BoardScene.Surface.ROCK ? 3 : 2);
                        terrain.update(embankment);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .17f;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 0)
                              .lerp(BoardGeometry.center(new Coords(4, 4), family == BoardScene.Surface.ROCK ? 3 : 2), .5f));
                        frame.render(terrain, camera, embankment);
                        GpuReviewFrame.save(new File(output, "road-earthworks-" + family.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                    }
                    for (int layout = 0; layout < 3; layout++) {
                        int[] rises = layout == 0 ? new int[] { 2, 2, 2, 2, 2, 2 }
                              : layout == 1 ? new int[] { -2, -2, -2, -2, -2, -2 } : new int[] { -2, 1, 2, -1, 0, 2 };
                        var junction = BoardRoadSlopeTest.junction(BoardScenes.CENTER, rises);
                        terrain.update(junction);
                        terrain.animate(.5f, List.of());
                        camera.camera.zoom = .2f;
                        camera.center(BoardGeometry.center(BoardScenes.CENTER, 2));
                        frame.render(terrain, camera, junction);
                        GpuReviewFrame.save(new File(output, "road-six-way-" + layout + ".png"));
                    }
                    for (int exits : new int[] { 31, 63 }) {
                        for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT, BoardRoad.Kind.GRAVEL)) {
                            var junction = BoardScenes.scene(c -> {
                                int mask = c.equals(BoardScenes.CENTER) ? exits : 0;
                                for (int d = 0; d < 6; d++) {
                                    if ((exits & (1 << d)) != 0 && c.equals(BoardScenes.CENTER.translated(d))) {
                                        mask = (1 << d) | (1 << ((d + 3) % 6));
                                    }
                                }
                                return BoardScenes.tile(c, mask == 0 ? BoardRoad.Kind.NONE : kind,
                                      mask, 0, BoardScene.Surface.GRASS);
                            });
                            terrain.update(junction);
                            terrain.animate(.5f, List.of());
                            camera.setIsometric(false);
                            camera.camera.zoom = .16f;
                            camera.center(BoardGeometry.center(BoardScenes.CENTER, 0));
                            frame.render(terrain, camera, junction);
                            GpuReviewFrame.save(new File(output, "roundabout-" + exits + "-" + kind + ".png"));
                            if (kind == BoardRoad.Kind.PAVED) { checkEveryApproach(camera, exits); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road surface review", failure.get()); }
    }

    private static BoardAtmosphere.Settings weather(float rain) {
        return new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0,
              new BoardAtmosphere.Effects(rain, 0, 0, 0, 0, 0, 0));
    }

    private static BoardScene ends(BoardRoad.Kind kind) {
        return BoardScenes.scene(c -> {
            boolean road = c.getX() == BoardScenes.CENTER.getX() && c.getY() >= BoardScenes.CENTER.getY();
            int exits = c.equals(BoardScenes.CENTER) ? 8 : 9;
            return BoardScenes.tile(c, road ? kind : BoardRoad.Kind.NONE, road ? exits : 0, 0, BoardScene.Surface.GRASS);
        });
    }

    private static BoardScene ramp(BoardRoad.Kind kind) {
        return ramp(kind, BoardScene.Surface.GRASS, 1);
    }

    private static BoardScene ramp(BoardRoad.Kind kind, BoardScene.Surface family, int level) {
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              c.getX() == 4 ? kind : BoardRoad.Kind.NONE, c.getX() == 4 ? 9 : 0,
              c.getY() >= 4 ? level : 0, family));
    }

    private static BoardScene materialChange(BoardRoad.Kind from, BoardRoad.Kind to) {
        return BoardScenes.scene(c -> {
            boolean road = c.getX() == BoardScenes.CENTER.getX();
            return BoardScenes.tile(c, road ? c.getY() <= BoardScenes.CENTER.getY() ? from : to : BoardRoad.Kind.NONE,
                  road ? 9 : 0, 0, BoardScene.Surface.GRASS);
        });
    }

    static BoardScene roads(boolean edited) {
        Map<Coords, Integer> exits = new HashMap<>();
        Map<Coords, BoardRoad.Kind> kinds = new HashMap<>();
        route(exits, kinds, new Coords(2, 1), BoardRoad.Kind.PAVED, 3, 3, 3, 3, 3, 3);
        route(exits, kinds, new Coords(6, 1), BoardRoad.Kind.DIRT, 3, 3, 3, 3, 3, 3);
        route(exits, kinds, new Coords(1, 6), BoardRoad.Kind.GRAVEL, 1, 1, 1, 1, 1, 1);
        route(exits, kinds, new Coords(2, 4), BoardRoad.Kind.ALLEY, 2, 2, 1, 1);
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              edited && c.equals(new Coords(2, 4)) ? BoardRoad.Kind.GRAVEL : kinds.getOrDefault(c, BoardRoad.Kind.NONE),
              exits.getOrDefault(c, 0), c.getY() < 3 ? 1 : 0,
              c.getY() < 3 ? BoardScene.Surface.SNOW : c.getY() > 5 ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS));
    }

    private static BoardScene bridge() {
        return BoardScenes.scene(c -> {
            if (c.getY() == 4) {
                Hex hex = new Hex(0);
                hex.addTerrain(new Terrain(Terrains.WATER, 1));
                if (c.getX() == 4) {
                    hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 9));
                    hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
                }
                var tile = BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, 1, 0);
                return new BoardScene.Tile(c, 0, 1, false, 0, tile.surface(), null, null,
                      BoardFeatures.capture(hex, c, Map.of()), List.of(), BoardLiquid.WATER, null, true);
            }
            return BoardScenes.tile(c, c.getX() == 4 ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
                  c.getX() == 4 ? 9 : 0, 0, BoardScene.Surface.GRASS);
        });
    }

    private static void route(Map<Coords, Integer> exits, Map<Coords, BoardRoad.Kind> kinds, Coords start,
          BoardRoad.Kind kind, int... directions) {
        Coords at = start;
        for (int direction : directions) {
            Coords next = at.translated(direction);
            exits.merge(at, 1 << direction, (a, b) -> a | b);
            exits.merge(next, 1 << ((direction + 3) % 6), (a, b) -> a | b);
            kinds.putIfAbsent(at, kind);
            kinds.putIfAbsent(next, kind);
            at = next;
        }
    }

    /** The production atmosphere composite copies the scene hardware depth to the backbuffer. */
    private static FloatBuffer sceneDepth() {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        FloatBuffer depth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        return depth;
    }

    private static int uncoveredPixels(int fromY, int toY, boolean connected) {
        FloatBuffer depth = sceneDepth();
        int width = Gdx.graphics.getBackBufferWidth(), holes = 0;
        int first = Math.max(fromY, 1), last = Math.min(toY, Gdx.graphics.getBackBufferHeight() - 1);
        for (int y = first; y < last; y++) {
            for (int x = 2; x < width - 2; x++) {
                int index = y * width + x;
                float value = depth.get(index);
                assertTrue(Float.isFinite(value) && value >= 0 && value <= 1, "Valid hardware depth");
                // Keep the original scan window and connected-hole rule: this fixture allows isolated pinholes.
                if (value == 1 && (!connected || depth.get(index - 1) == 1 || depth.get(index + 1) == 1
                      || depth.get(index - width) == 1 || depth.get(index + width) == 1)) { holes++; }
            }
        }
        return holes;
    }

    private static void checkCoverage(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          int fromY, int toY, boolean connected, String message) {
        assertEquals(0, uncoveredPixels(fromY, toY, connected), message);
        int x = Gdx.graphics.getBackBufferWidth() / 2, y = (fromY + toY) / 2;
        FloatBuffer depth = sceneDepth();
        int width = Gdx.graphics.getBackBufferWidth();
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                assertTrue(depth.get((y + dy) * width + x + dx) < 1, "Hole control starts on covered geometry");
            }
        }
        // Erase precisely four occupied hardware-depth samples, without changing their RGB. A color detector
        // cannot see this control; an occupancy detector must find the new connected 2x2 hole exactly.
        boolean scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        boolean depthMask = GL11C.glGetBoolean(GL20.GL_DEPTH_WRITEMASK);
        float clearDepth = GL11C.glGetFloat(GL20.GL_DEPTH_CLEAR_VALUE);
        var box = BufferUtils.newIntBuffer(4);
        Gdx.gl.glGetIntegerv(GL20.GL_SCISSOR_BOX, box);
        try {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glScissor(x, y, 2, 2);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearDepthf(1);
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            assertEquals(4, uncoveredPixels(fromY, toY, connected), "Detect the injected hardware-depth hole");
        } finally {
            Gdx.gl.glScissor(box.get(0), box.get(1), box.get(2), box.get(3));
            if (!scissor) { Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST); }
            Gdx.gl.glDepthMask(depthMask);
            Gdx.gl.glClearDepthf(clearDepth);
            frame.render(terrain, camera, scene);
        }
        assertEquals(0, uncoveredPixels(fromY, toY, connected), "A fresh production frame restores coverage");
    }

    private static byte[] screen() {
        var pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            byte[] result = new byte[pixels.getPixels().remaining()];
            pixels.getPixels().get(result);
            return result;
        } finally { pixels.dispose(); }
    }

    /**
     * The dirt track runs on past its border with the paved road, over the asphalt's broken end (GpuRoads#runOn): its two
     * ruts paler than the ground between them.
     */
    private static void checkDirtCarry(BoardCamera camera, BoardScene scene) {
        byte[] pixels = screen();
        var dirt = BoardRoad.of(scene, scene.tile(BoardScenes.CENTER));
        Vector3 center = BoardGeometry.center(BoardScenes.CENTER, 0);
        Vector3 along = BoardGeometry.center(BoardScenes.CENTER.translated(3), 0).sub(center).nor();
        float scale = BoardGeometry.hexScale(), traces = 0, between = 0;
        // Their border, half way between the hexes' centres.
        float edge = BoardGeometry.center(BoardScenes.CENTER.translated(3), 0).dst(center) / (2 * scale);
        for (float onto : new float[] { .2f, .35f, .5f }) {
            Vector3 at = new Vector3(center).mulAdd(along, (edge + onto * GpuRoads.runOn()) * scale).add(0, 0, .1f);
            between += brightness(pixels, camera.camera.project(new Vector3(at)));
            for (int side : new int[] { -1, 1 }) {
                traces += brightness(pixels, camera.camera.project(new Vector3(at)
                      .add(-along.y * side * BoardRoad.RUT_OFFSET * scale, along.x * side * BoardRoad.RUT_OFFSET * scale, 0)))
                      / 2;
            }
        }
        assertTrue((traces - between) / 3 > 4, "The dirt track's ruts should run on over the asphalt's end");
    }

    private static float brightness(byte[] pixels, Vector3 point) {
        Vector3 color = pixelColor(pixels, point);
        return (color.x + color.y + color.z) / 3;
    }

    private static Vector3 pixelColor(byte[] pixels, Vector3 point) {
        Vector3 total = new Vector3();
        int width = Gdx.graphics.getBackBufferWidth();
        for (int y = Math.round(point.y) - 2; y <= Math.round(point.y) + 2; y++) {
            for (int x = Math.round(point.x) - 2; x <= Math.round(point.x) + 2; x++) {
                int index = (y * width + x) * 4;
                total.add(Byte.toUnsignedInt(pixels[index]), Byte.toUnsignedInt(pixels[index + 1]),
                      Byte.toUnsignedInt(pixels[index + 2]));
            }
        }
        return total.scl(1 / 25f);
    }

    /** Repeated mask images may share an atlas slot, but every hex must still draw its own asphalt. */
    private static void checkEveryApproach(BoardCamera camera, int exits) {
        byte[] pixels = screen();
        Vector3 center = BoardGeometry.center(BoardScenes.CENTER, 0);
        float scale = BoardGeometry.hexScale();
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & (1 << direction)) == 0) { continue; }
            Vector3 along = BoardGeometry.center(BoardScenes.CENTER.translated(direction), 0).sub(center).nor();
            Vector3 across = new Vector3(-along.y, along.x, 0);
            for (float distance : new float[] { 32, 55 }) {
                Vector3 at = new Vector3(center).mulAdd(along, distance * scale);
                Vector3 asphalt = pixelColor(pixels, camera.camera.project(new Vector3(at).mulAdd(across, 4 * scale)));
                Vector3 grass = pixelColor(pixels, camera.camera.project(new Vector3(at).mulAdd(across, 15 * scale)));
                assertTrue(asphalt.y - asphalt.z < grass.y - grass.z - 6,
                      "Asphalt must remain visible on approach " + direction + " at " + distance
                      + ": asphalt=" + asphalt + ", grass=" + grass);
            }
        }
    }

    /** Central road pixels only: surrounding terrain also changes when normal mapping is toggled. */
    private static float roadDifference(byte[] a, byte[] b) {
        long sum = 0;
        int count = 0, width = Gdx.graphics.getBackBufferWidth();
        for (int y = 100; y < 450; y++) {
            for (int x = 625; x < 655; x++) {
                int index = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    sum += Math.abs(Byte.toUnsignedInt(a[index + channel]) - Byte.toUnsignedInt(b[index + channel]));
                    count++;
                }
            }
        }
        return sum / (float) count;
    }
}
