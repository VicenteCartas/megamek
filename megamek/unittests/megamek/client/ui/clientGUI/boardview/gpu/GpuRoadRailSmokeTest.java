/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Small, repeatable views of the shipped road/rail corridors, with the same converted data as a game. */
@Tag("on-demand")
class GpuRoadRailSmokeTest {
    record View(BoardScene scene, Coords at, int level) { }

    @Test
    void shippedCorridors() throws Exception {
        render(false);
    }

    @Test
    void shippedBesideCorridors() throws Exception {
        render(true);
    }

    /** Exact camera from the step-2 handoff's small Depot42 verge difference, with its ground masks checked. */
    @Test
    void depot42VergeAtTheHandoffCamera() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "depot42");
        Files.createDirectories(output.toPath());
        Board board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
              "unofficial/Flynn/34x34 Starleague Depot42 FortifiedIsland.board"), false, true);
        var scene = BoardScenes.scene(board);
        // The changed verge is hex 2616, whose old span vote and stored deck-road neighbor are both PAVED.
        var tile = scene.tile(new Coords(25, 15));
        var actual = BoardRoad.of(scene, tile);
        var before = BoardRoad.layout(tile.coords(), tile.roadExits(), BoardRoad.Kind.PAVED,
              direction -> BoardRoad.Kind.PAVED, BoardRoad.bends(tile.coords(), at -> BoardRoad.Node.of(scene.tile(at))));
        var actualPatches = GpuRoads.patches(tile, actual);
        var beforePatches = GpuRoads.patches(tile, before);
        assertEquals(beforePatches.size(), actualPatches.size());
        var masks = new ArrayList<String>();
        for (int i = 0; i < actualPatches.size(); i++) {
            var mask = GpuRoads.mask(actual, actualPatches.get(i));
            assertEquals(GpuRoads.mask(before, beforePatches.get(i)), mask,
                  "The step-2 bank-join change must not change 2616's ground mask " + i);
            masks.add(i + " " + actualPatches.get(i).texture() + " lift=" + actualPatches.get(i).lift()
                  + " bounds=" + mask.x() + "," + mask.y() + "," + mask.width() + "," + mask.height()
                  + " pixels=" + mask.pixels().hashCode());
        }
        Files.write(new File(output, "2616-masks.txt").toPath(), masks);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
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
                    camera.orbit(20 - camera.azimuth(), 45 - camera.tilt());
                    camera.center(BoardGeometry.center(new Coords(25, 18), 1));
                    camera.zoom(.3f / camera.camera.zoom);
                    camera.update();
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    Files.write(new File(output, "2616-installed-coats.txt").toPath(), installedCoats(terrain, tile.coords()));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "13-depot42-bank-2619.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Depot42 verge capture", failure.get()); }
    }

    /** Records whether overlapping coats were split across atlas pages, without changing the visible geometry. */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> installedCoats(GpuTerrain terrain, Coords at) throws Exception {
        var lines = new ArrayList<String>();
        for (Object chunk : (java.util.List<?>) GpuTestSupport.field(terrain, "chunks")) {
            Object tile = ((Map<?, ?>) GpuTestSupport.field(chunk, "tileMeshes")).get(at);
            if (tile == null) { continue; }
            var pages = new java.util.HashSet<Integer>();
            var draws = GpuTerrainDepth.snapshot((java.util.List<ModelInstance>) GpuTestSupport.field(chunk, "overlays"));
            for (Object range : (java.util.List<?>) GpuTestSupport.field(tile, "ranges")) {
                Material material = (Material) GpuTestSupport.field(range, "material");
                var mask = material.get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
                if (mask == null) { continue; }
                pages.add(mask.textureDescription.texture.getTextureObjectHandle());
                int start = (int) GpuTestSupport.field(range, "offset"), end = start + (int) GpuTestSupport.field(range, "count");
                boolean found = false;
                for (var draw : draws) {
                    if (draw.meshPart.mesh != GpuTestSupport.field(range, "mesh") || draw.meshPart.offset > start
                          || draw.meshPart.offset + draw.meshPart.size < end) { continue; }
                    assertEquals(GpuRoads.layer(material), GpuRoads.layer(draw.material),
                          "Coats on separate pages retain their ordering in the actual merged draws");
                    found = true;
                }
                assertTrue(found, "Every affected coat belongs to an installed draw");
                lines.add("lift=" + GpuRoads.layer(material) + " page="
                      + mask.textureDescription.texture.getTextureObjectHandle() + " range="
                      + GpuTestSupport.field(range, "offset") + "+" + GpuTestSupport.field(range, "count") + " mask=" + mask.data.pixels().hashCode());
            }
            assertTrue(pages.size() > 1, "The historical junction still exercises overlapping coats on separate pages");
        }
        return lines;
    }

    private static void render(boolean beside) throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "road-rail");
        Files.createDirectories(output.toPath());
        Map<String, View> views = new LinkedHashMap<>();
        views.put("trainjob-deck", load("unofficial/VictorMorson/48x48 The Trainjob.board", new Coords(37, 29), beside));
        views.put("depot-deck", load("unofficial/VictorMorson/48x48 The Depot.board", new Coords(11, 32), beside));
        views.put("ocoee-ground", load("unofficial/Lanceman/50x700 Ocoee.board", null, beside));
        render(output, views, beside ? "beside-" : "");
    }

    static void render(File output, Map<String, View> views, String prefix) throws Exception {
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    for (var entry : views.entrySet()) {
                        var view = entry.getValue();
                        var terrain = new GpuTerrain();
                        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                        try {
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            camera.setIsometric(true);
                            camera.camera.zoom = .24f;
                            camera.center(BoardGeometry.center(view.at(), view.level()));
                            for (int tilt : new int[] { 0, 50 }) {
                                camera.orbit(25 - camera.azimuth(), tilt - camera.tilt());
                                frame.prepare(terrain, camera, view.scene());
                                terrain.update(view.scene());
                                GpuReviewFrame.settle(terrain, null, view.scene(), camera);
                                frame.render(terrain, camera, view.scene());
                                GpuReviewFrame.save(new File(output, prefix + entry.getKey() + "-" + tilt + ".png"));
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            }
                        } finally { frame.dispose(); terrain.dispose(); }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road and rail corridor rendering", failure.get()); }
    }

    static View load(String path, Coords selected, boolean beside) throws Exception {
        Board board = BoardFile.loadSheet(new File(Configuration.boardsDir(), path), false, true);
        if (selected == null) {
            for (int x = 3; x < board.getWidth() - 3 && selected == null; x++) {
                for (int y = 3; y < board.getHeight() - 3; y++) {
                    var hex = board.getHex(x, y);
                    if (HexTile.road(hex) && Integer.bitCount(RailRoute.groundSides(hex)
                          & hex.getTerrain(Terrains.ROAD).getExits()) >= 2) { selected = new Coords(x, y); break; }
                }
            }
        }
        if (selected == null) { throw new AssertionError("No corridor on " + path); }
        // Preserve odd/even column parity; crop only after the normal legacy conversion has built its ways.
        int left = Math.max(0, selected.getX() - 5) & ~1, top = Math.max(0, selected.getY() - 5);
        Board crop = Board.createEmptyBoard(Math.min(12, board.getWidth() - left), Math.min(12, board.getHeight() - top));
        crop.setNativeFormat(true);
        for (int x = 0; x < crop.getWidth(); x++) {
            for (int y = 0; y < crop.getHeight(); y++) { crop.setHex(x, y, board.getHex(x + left, y + top).duplicate()); }
        }
        Coords at = new Coords(selected.getX() - left, selected.getY() - top);
        var hex = crop.getHex(at);
        if (beside) {
            var marker = RailRoute.markers(hex, RailRoute.Kind.RAIL).stream()
                  .filter(m -> RailRoute.onDeck(m) == hex.containsTerrain(Terrains.BRIDGE)).findFirst().orElseThrow();
            var run = RailRoute.corridorRun(crop::getHex, at, marker);
            var bits = RailRoute.besideBits(crop::getHex, run, new RailRoute.Member(at, marker));
            bits.forEach((member, value) -> RailRoute.beside(crop.getHex(member.at()), RailRoute.onDeck(member.marker()), value));
        }
        return new View(BoardScenes.scene(crop), at,
              hex.getLevel() + (hex.containsTerrain(Terrains.BRIDGE) ? hex.terrainLevel(Terrains.BRIDGE_ELEV) : 0));
    }
}
