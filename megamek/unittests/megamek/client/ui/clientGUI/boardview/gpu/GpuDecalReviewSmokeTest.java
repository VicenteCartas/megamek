/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The decal art in the native render: every emblem as one decal at three scales (top down, oblique and far away, where
 * the mipmaps must keep it from shimmering), every standard car-park decal two-sided beside a road and back to back with
 * a car in each slot, the converted emblem template and a converted car-park district of a real map, converted car-park
 * lane roads (every car-park code, roof car parks, a lane joining two roads, kept automatic neighbours), and paint on
 * grass, where no blade grows through it. Reports the draw calls the emblem decals add and the decal textures' memory.
 */
@Tag("on-demand")
class GpuDecalReviewSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "decals");
    private static final Path DECALS = Configuration.dataDir().toPath().resolve("models/board/decals");
    private static final double[] SCALES = { 1, .5, .26 };
    private static final String[] PAINT = { "#b8b8b8", "#a32020", "#2050a0", "#e0e0e0", "#202020", "#c8a040" };

    private record Shot(String name, Coords at, float zoom, boolean oblique) { }

    private record View(BoardScene scene, List<Shot> shots) { }

    private static List<String> decals(String group) throws Exception {
        try (Stream<Path> files = Files.list(DECALS.resolve(group))) {
            return files.map(file -> "decal/" + group + "/" + file.getFileName().toString().replace(".png", "")).sorted().toList();
        }
    }

    private static Board pavement(int width, int height) {
        Board board = Board.createEmptyBoard(width, height);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
        }
        return board;
    }

    /** Each emblem in a column, at full size, at half size turned -30 and at the small one-hex size turned +30. */
    private static Board emblems(List<String> emblems, boolean paint) {
        Board board = pavement(3 * emblems.size() + 1, 10);
        if (!paint) { return board; }
        for (int i = 0; i < emblems.size(); i++) {
            for (int row = 0; row < SCALES.length; row++) {
                Coords at = new Coords(1 + 3 * i, 1 + 3 * row);
                board.getHex(at).setDecorations(List.of(new BoardDecoration("e" + i + "-" + row, "decal", emblems.get(i), null,
                      0, 0, row == 0 ? 0 : row == 1 ? -30 : 30, false, SCALES[row], BoardDecoration.Placement.ground(), 0, false)));
            }
        }
        return board;
    }

    /**
     * Each standard car park two-sided, every slot seated through the import's rows: beside a N-S road (row 1, the rows'
     * road-side edge where the data puts cars_3's) and the same rows back to back without one (row 3).
     */
    private static Board carParks(List<String> parks) {
        Board board = pavement(2 * parks.size() + 1, 5);
        var lane = BoardSceneryLayouts.layout("scenery/fluff/cars_3").components().stream()
              .filter(BoardSceneryLayouts.Component::decal).findFirst().orElseThrow();
        double edge = Math.abs(lane.x()) - BoardDecalArt.footprint(lane.asset()).height() / 2;
        for (int i = 0; i < parks.size(); i++) {
            String park = parks.get(i);
            var size = BoardDecalArt.footprint(park);
            List<BoardSceneryLayouts.Component> rows = new ArrayList<>();
            for (int side = 0; side < 2; side++) {
                // East of the road opening west, and the same row turned 180 degrees on the west side.
                rows.add(new BoardSceneryLayouts.Component(park, false,
                      (float) ((side == 0 ? 1 : -1) * (edge + size.height() / 2)), 0, 0, side == 0 ? -90 : 90, 1));
            }
            for (int side = 0; side < 2; side++) {
                for (int slot = 0; slot < size.slots().size(); slot++) {
                    rows.add(new BoardSceneryLayouts.Component("scenery/vehicles/car", false,
                          0, 0, 0, 0, 1, 0, BoardDecoration.Stretch.NONE,
                          BoardDecoration.Colours.of(PAINT[(slot + side) % PAINT.length]), new BoardSceneryLayouts.Seat(side, slot)));
                }
            }
            for (boolean road : new boolean[] { true, false }) {
                Coords at = new Coords(1 + 2 * i, road ? 1 : 3);
                if (road) { board.setHex(at, new Hex(0, "pavement:1;road:1:09", "")); }
                int[] next = { 0 };
                board.getHex(at).setDecorations(new BoardSceneryLayouts.Layout(List.copyOf(rows)).decorations(at,
                      board.getHex(at), () -> at.getBoardNum() + "-" + next[0]++, "park" + at.getBoardNum(), road));
            }
        }
        return board;
    }

    /** Paint on grass, along row 2: cars_3b (column 1) and a one-hex Red Cross token (column 11), legacy art. */
    private static Board grass() {
        Board board = Board.createEmptyBoard(13, 5);
        for (int x = 0; x < 13; x++) {
            for (int y = 0; y < 5; y++) { board.setHex(new Coords(x, y), new Hex(0, "", "grass")); }
        }
        board.setHex(new Coords(1, 2), new Hex(0, "fluff:5:99", "grass"));
        board.setHex(new Coords(11, 2), new Hex(0, "fluff:62:4", "grass"));
        return board;
    }

    /**
     * The converted grass board plus hand-placed paint, unclipped like the editor's: a straight-8 car park (column 3), the
     * Red Cross and ComStar emblems at half size (5, 7) and the light rubble path (9).
     */
    private static void placePaint(Board board) {
        String[] decals = { "decal/car-park/straight-8", "decal/emblems/red-cross", "decal/emblems/comstar",
              "decal/damage/rubble-light-path" };
        double[] scales = { 1, .5, .5, 1 };
        for (int i = 0; i < decals.length; i++) {
            Coords at = new Coords(3 + 2 * i, 2);
            board.getHex(at).setDecorations(List.of(new BoardDecoration("paint" + i, "decal", decals[i], null, 0, 0,
                  i == 0 ? 30 : 0, false, scales[i], BoardDecoration.Placement.ground(), 0, false)));
        }
    }

    /** A shipped board as read, or a crop of it; {@code x0} keeps the column parity of a cropped region. */
    private static Board read(String file, int x0, int y0, int width, int height) throws Exception {
        Board source = BoardFile.read(Configuration.boardsDir().toPath().resolve(file));
        if (width == 0) { return source; }
        Board board = Board.createEmptyBoard(width, height);
        // A crop keeps the board's road option, which decides its automatic road exits.
        board.setRoadsAutoExit(source.getRoadsAutoExit());
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) { board.setHex(new Coords(x, y), source.getHex(x0 + x, y0 + y)); }
        }
        return board;
    }

    /** Car-park codes 00-07, the laneless 98/99 and cars_3 on bare ground, each on its own hex along row 1. */
    private static final String[] PARK_HEXES = { "pavement:1;fluff:5:00", "pavement:1;fluff:5:01", "pavement:1;fluff:5:02",
          "pavement:1;fluff:5:03", "pavement:1;fluff:5:04", "pavement:1;fluff:5:05", "pavement:1;fluff:5:06",
          "pavement:1;fluff:5:07", "pavement:1;fluff:5:98", "pavement:1;fluff:5:99", "fluff:5:02" };

    private static Board parkRoads() {
        Board board = Board.createEmptyBoard(2 * PARK_HEXES.length + 1, 3);
        for (int i = 0; i < PARK_HEXES.length; i++) { board.setHex(new Coords(1 + 2 * i, 1), new Hex(0, PARK_HEXES[i], "")); }
        return board;
    }

    /** The installed blades of every painted hex (placed decals and untranslated paint) stay off the paint. */
    private static void noBladeThroughPaint(String view, GpuTerrain terrain, BoardScene scene) {
        var index = BoardDecals.index(scene);
        int hexes = 0, roots = 0;
        for (var tile : scene.tiles()) {
            var paint = BoardDecals.Opacity.of(tile, index.getOrDefault(tile.coords(), List.of()));
            var plants = terrain.planted(tile.coords());
            if (paint == null || plants == null || plants.grass() == null) { continue; }
            hexes++;
            for (int i = 0; i < plants.grass().size; i += 4) {
                roots++;
                assertTrue(paint.at(plants.grass().get(i), plants.grass().get(i + 1)) < .5f, view + ": a blade through paint at "
                      + tile.coords());
            }
        }
        System.out.printf("DECALS %s: %d painted grass hexes, %d blades, none through paint%n", view, hexes, roots);
        assertTrue(hexes > 0, view + " plants grass on painted hexes");
    }

    /** The full mip chain of an RGBA8 texture, in bytes. */
    private static long mipBytes(int width, int height) {
        long bytes = 0;
        for (int w = width, h = height; ; w = Math.max(1, w / 2), h = Math.max(1, h / 2)) {
            bytes += 4L * w * h;
            if (w == 1 && h == 1) { return bytes; }
        }
    }

    @Test
    void decalsRenderSharpAndConvertedBoardsPaintTheirDecals() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        var emblems = decals("emblems");
        var parks = decals("car-park");
        assertEquals(13, emblems.size());
        assertEquals(8, parks.size());
        long bytes = 0;
        for (String group : List.of("emblems", "car-park", "damage")) {
            for (String decal : decals(group)) {
                var image = ImageIO.read(BoardDecalArt.image(decal));
                bytes += mipBytes(image.getWidth(), image.getHeight());
            }
        }
        System.out.printf("DECALS 22 textures, one per image, mipmapped RGBA8: %d bytes%n", bytes);

        Board templateImported = read("Templates/LandingPadsFactionSymbols.board", 0, 0, 0, 0);
        // Perdition Spaceport: a Federated Suns set (6557), its one-hex variant on a roof (6070) and a car-park district.
        String spaceport = "unofficial/Ashnods Maps/110x80 Perdition Spaceport.board";
        Board districtImported = read(spaceport, 58, 52, 34, 24);
        // Car-park lane roads: roof car parks (Lunar 0206/0207 without a road, 0307/0308 with their own), a lane joining
        // roads at both ends (OP2Mescape 2402, local 0602) and automatic neighbours kept (Desert Airfield 2443-2446,
        // local 0607-0610, cropped with its road option).
        String lunar = "buildingsnobasement/Lunar Sample 16x17 (No Basement).board";
        String escape = "unofficial/Jackanapes/58x54 OP2Mescape.board";
        String airfield = "unofficial/Strategoslevel3/64x68 Desert Airfiled No Water.board";
        Board roadsImported = parkRoads();
        Board roofsImported = read(lunar, 0, 0, 0, 0);
        Board joinsImported = read(escape, 18, 0, 12, 8);
        // The crop as read, whose automatic road exits the converted crop must keep.
        Board kept = read(airfield, 18, 36, 12, 14), keptImported = read(airfield, 18, 36, 12, 14);
        Board meadowImported = grass();
        for (Board board : List.of(templateImported, districtImported, roadsImported, roofsImported, joinsImported,
              keptImported, meadowImported)) {
            assertTrue(BoardSceneryLayouts.convert(board).isEmpty());
        }
        placePaint(meadowImported);
        assertEquals(List.of(false, false, true, true), List.of(new Coords(1, 5), new Coords(1, 6), new Coords(2, 6),
              new Coords(2, 7)).stream().map(at -> roofsImported.getHex(at).containsTerrain(Terrains.ROAD)).toList(),
              "Roof car parks get no road; an authored one stays");
        assertEquals(18, joinsImported.getHex(5, 1).getTerrain(Terrains.ROAD).getExits(), "OP2Mescape 2402: the NE-SW lane");
        for (int y = 6; y <= 9; y++) {
            assertEquals(9, keptImported.getHex(5, y).getTerrain(Terrains.ROAD).getExits(), "Desert Airfield column 24: N-S lanes");
            for (int x : new int[] { 4, 6 }) {
                var road = keptImported.getHex(x, y).getTerrain(Terrains.ROAD);
                assertEquals(kept.getHex(x, y).getTerrain(Terrains.ROAD).getExits(), road.getExits(), "Kept neighbour " + x + "," + y);
            }
        }
        var set = new Coords(12, 10);
        var single = new Coords(5, 10);
        var lot = new Coords(22, 12);
        List<Shot> templateShots = List.of(new Shot("overview", new Coords(9, 14), .9f, false),
              new Shot("sets", set, .25f, false), new Shot("singles", single, .25f, false),
              new Shot("sets-oblique", set, .3f, true));
        List<Shot> districtShots = List.of(new Shot("overview", new Coords(17, 12), .8f, false),
              new Shot("set", new Coords(6, 5), .25f, false), new Shot("roof", new Coords(1, 17), .2f, true),
              new Shot("cars", lot, .2f, false), new Shot("cars-oblique", lot, .2f, true));
        List<Shot> emblemShots = new ArrayList<>(List.of(new Shot("overview", new Coords(19, 4), 1.65f, false),
              new Shot("far", new Coords(19, 4), 2.2f, true)));
        for (int i = 0; i < emblems.size(); i += 4) {
            emblemShots.add(new Shot("close-" + i, new Coords(1 + 3 * i, 4), .3f, false));
            emblemShots.add(new Shot("oblique-" + i, new Coords(1 + 3 * i, 4), .3f, true));
        }
        List<Shot> parkShots = new ArrayList<>(List.of(new Shot("overview", new Coords(8, 2), .7f, false),
              new Shot("oblique", new Coords(8, 2), .35f, true)));
        for (int i = 0; i < parks.size(); i++) {
            // One- and two-sided of one decal; between the two hexes' centres.
            parkShots.add(new Shot(parks.get(i).substring(parks.get(i).lastIndexOf('/') + 1), new Coords(1 + 2 * i, 2), .26f, false));
        }
        var views = new java.util.LinkedHashMap<String, View>();
        views.put("emblems-bare", new View(BoardScenes.scene(emblems(emblems, false)),
              List.of(new Shot("overview", new Coords(19, 4), 1.65f, false))));
        views.put("emblems", new View(BoardScenes.scene(emblems(emblems, true)), emblemShots));
        views.put("car-parks", new View(BoardScenes.scene(carParks(parks)), parkShots));
        views.put("level-crossing", new View(BoardScenes.scene(
              emblems(List.of("decal/rail/level-crossing"), true)), List.of(
              new Shot("top", new Coords(1, 4), .45f, false),
              new Shot("oblique", new Coords(1, 4), .45f, true),
              new Shot("close", new Coords(1, 1), .06f, false),
              new Shot("close-oblique", new Coords(1, 1), .06f, true))));
        views.put("template-imported", new View(BoardScenes.scene(templateImported), templateShots));
        views.put("spaceport-imported", new View(BoardScenes.scene(districtImported), districtShots));
        List<Shot> roadShots = new ArrayList<>(List.of(new Shot("overview", new Coords(PARK_HEXES.length, 1), 1, false),
              new Shot("oblique", new Coords(5, 1), .35f, true)));
        for (int i = 0; i < PARK_HEXES.length; i++) {
            String code = PARK_HEXES[i].substring(PARK_HEXES[i].length() - 2);
            roadShots.add(new Shot(PARK_HEXES[i].startsWith("pavement") ? code : code + "-ground", new Coords(1 + 2 * i, 1), .26f, false));
        }
        views.put("car-park-roads-imported", new View(BoardScenes.scene(roadsImported), roadShots));
        List<Shot> roofShots = List.of(new Shot("roofs", new Coords(2, 6), .3f, false), new Shot("roofs-oblique", new Coords(2, 6), .3f, true));
        views.put("lunar-imported", new View(BoardScenes.scene(roofsImported), roofShots));
        List<Shot> joinShots = List.of(new Shot("2402", new Coords(5, 1), .26f, false), new Shot("2402-oblique", new Coords(5, 2), .3f, true));
        views.put("op2mescape-imported", new View(BoardScenes.scene(joinsImported), joinShots));
        List<Shot> keptShots = List.of(new Shot("2443-2446", new Coords(5, 7), .35f, false),
              new Shot("2443-2446-oblique", new Coords(5, 8), .35f, true));
        views.put("desert-airfield-imported", new View(BoardScenes.scene(keptImported), keptShots));
        // Grass blades show from 120 projected px per hex: the close-ups show none growing through the paint.
        List<Shot> meadowShots = new ArrayList<>(List.of(new Shot("overview", new Coords(6, 2), .9f, false),
              new Shot("oblique", new Coords(5, 2), .3f, true)));
        String[] painted = { "cars-3b", "straight-8", "red-cross", "comstar", "rubble-path", "red-cross-token" };
        for (int i = 0; i < painted.length; i++) { meadowShots.add(new Shot(painted[i], new Coords(1 + 2 * i, 2), .26f, false)); }
        views.put("grass-imported", new View(BoardScenes.scene(meadowImported), meadowShots));

        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var draws = new java.util.HashMap<String, Integer>();
                    for (var entry : views.entrySet()) {
                        var terrain = new GpuTerrain();
                        try {
                            var scene = entry.getValue().scene();
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            for (Shot shot : entry.getValue().shots()) {
                                camera.setIsometric(shot.oblique());
                                if (shot.oblique()) { camera.tilt(70 - camera.tilt()); }
                                // On the hex's ground, so a raised board (OP2Mescape at level 4) stays framed when oblique.
                                camera.center(BoardGeometry.center(shot.at(), scene.tile(shot.at()).elevation()));
                                camera.camera.zoom = shot.zoom();
                                camera.update();
                                GpuReviewFrame.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                if (shot.name().equals("overview")) {
                                    profiler.enable();
                                    profiler.reset();
                                    frame.render(terrain, camera, scene);
                                    draws.put(entry.getKey(), profiler.getDrawCalls());
                                    System.out.printf("DECALS %s overview: %d draw calls%n", entry.getKey(), profiler.getDrawCalls());
                                    profiler.disable();
                                }
                                GpuReviewFrame.save(new File(OUTPUT, entry.getKey() + "-" + shot.name() + ".png"));
                            }
                            if (entry.getKey().startsWith("grass")) { noBladeThroughPaint(entry.getKey(), terrain, scene); }
                        } finally { terrain.dispose(); }
                    }
                    System.out.printf("DECALS 39 emblem decals add %d draw calls%n", draws.get("emblems") - draws.get("emblems-bare"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Decal review", failure.get()); }
    }
}
