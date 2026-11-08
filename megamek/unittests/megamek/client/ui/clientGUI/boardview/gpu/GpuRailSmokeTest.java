/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Batch 10's rail system on shipped boards, as a game loads them and as the editor opens them (one 3D board): the
 * most rail-heavy board (the Shipyard), ground rails on slopes and a deck over water (The Trainjob, Desert Mesa
 * Oasis), a level crossing (Wagon Wheel), and a maglev guideway over a ground railway. Captures go to
 * {@code build/gpu-board-review/b10-s2/}.
 */
@Tag("on-demand")
class GpuRailSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "b10-s2");

    /** A shot: where the camera looks (hex, level), its tilt from the vertical (0 looks straight down), its zoom. */
    private record Shot(Coords at, float level, float tilt, float zoom) { }

    /** Hex ids as the boards number them (xxyy, from 1). */
    private static Coords hex(int id) { return new Coords(id / 100 - 1, id % 100 - 1); }

    private static BoardScene game(File file) throws Exception {
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(file, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            return fixture.source.takeFrame().scene();
        }
    }

    private static BoardScene editor(File file) throws Exception {
        var session = new BoardEditorSession();
        var opened = new FutureTask<>(() -> {
            session.open(file.toPath());
            try (var source = new GpuMapSource(session.game(), null, session)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(opened);
        return opened.get();
    }

    /** Every tile's route markers and the rail-owned objects (buffer stops), by hex. */
    private static Map<Coords, List<BoardDecoration>> routes(BoardScene scene) {
        Map<Coords, List<BoardDecoration>> result = new LinkedHashMap<>();
        for (var tile : scene.tiles()) {
            var objects = tile.features().stream().map(BoardScene.Feature::decoration)
                  .filter(object -> RailRoute.isRoute(object) || object != null && object.asset().equals(RailRoute.BUFFER_STOP))
                  .toList();
            if (!objects.isEmpty()) { result.put(tile.coords(), objects); }
        }
        return result;
    }

    @Test
    void shippedRailBoardsDrawTheirConvertedRailsAlikeInGameAndEditor() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        Map<String, Map<String, Shot>> boards = new LinkedHashMap<>();
        boards.put("unofficial/Morkath/100x78 Shipyard.board", Map.of());
        boards.put("unofficial/VictorMorson/48x48 The Trainjob.board", Map.of(
              "trainjob-slope", new Shot(hex(1029), 1, 50, .2f),
              "trainjob-bridge", new Shot(hex(3830), 2, 45, .3f)));
        boards.put("unofficial/Cakefish/General/45x45 Desert Mesa Oasis.board", Map.of(
              "mesa-slope", new Shot(hex(422), 1, 50, .22f),
              "mesa-deck", new Shot(hex(3632), 1, 50, .22f)));
        boards.put("unofficial/Radar/45x60 Wagon Wheel.board", Map.of(
              "wagon-wheel-crossing", new Shot(hex(1629), 0, 55, .16f),
              "wagon-wheel-crossing-top", new Shot(hex(1629), 0, 0, .16f)));
        Map<String, BoardScene> scenes = new LinkedHashMap<>();
        Map<String, Map<String, Shot>> shots = new LinkedHashMap<>();
        for (var board : boards.entrySet()) {
            File file = new File(megamek.common.Configuration.boardsDir(), board.getKey());
            String name = file.getName().replace(".board", "").replace(' ', '-');
            BoardScene game = game(file), editor = editor(file);
            var gameRoutes = routes(game);
            assertFalse(gameRoutes.isEmpty(), name + " has rails");
            assertEquals(gameRoutes, routes(editor), name + ": the game and the editor build the same rails");
            // The generated track's size, on a flat carrier (slopes add height samples only where the ground bends).
            long triangles = 0;
            for (var tile : game.tiles()) {
                for (var feature : tile.features()) {
                    if (BoardRail.route(feature)) {
                        triangles += BoardRail.model(BoardRail.layout(game, tile, feature)).meshes.first().parts[0].indices.length / 3;
                    }
                }
            }
            System.out.printf("RAIL %s: %d hexes with route markers, %d buffer stops, %d track triangles%n", name,
                  gameRoutes.size(), gameRoutes.values().stream().flatMap(List::stream)
                        .filter(o -> o.asset().equals(RailRoute.BUFFER_STOP)).count(), triangles);
            Map<String, Shot> named = new LinkedHashMap<>(board.getValue());
            if (named.isEmpty()) {
                // The most rail-heavy board: the whole board, its busiest stretch of track, and a junction close up.
                named.put("overview", null);
                Coords busiest = gameRoutes.keySet().stream().max(java.util.Comparator.comparingLong(at -> gameRoutes.keySet()
                      .stream().filter(other -> other.distance(at) <= 4).count())).orElseThrow();
                named.put("yard", new Shot(busiest, game.tile(busiest).elevation(), 45, .7f));
                Coords junction = gameRoutes.entrySet().stream().filter(entry -> entry.getValue().stream()
                            .anyMatch(object -> RailRoute.isRoute(object) && Integer.bitCount(object.connections()) >= 4))
                      .map(Map.Entry::getKey).findFirst().orElseThrow();
                named.put("junction", new Shot(junction, game.tile(junction).elevation(), 45, .18f));
            }
            for (var shot : named.entrySet()) {
                scenes.put(name + "-" + shot.getKey() + "-game", game);
                scenes.put(name + "-" + shot.getKey() + "-editor", editor);
                shots.put(name + "-" + shot.getKey() + "-game", Map.of("", shot.getValue() == null ? FIT : shot.getValue()));
                shots.put(name + "-" + shot.getKey() + "-editor", Map.of("", shot.getValue() == null ? FIT : shot.getValue()));
            }
        }
        render(scenes, shots);
    }

    /** The whole board in view. */
    private static final Shot FIT = new Shot(new Coords(0, 0), 0, 60, 0);

    @Test
    void oneImplicitMaglevDeckCrossesAGroundRailway() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        Board board = Board.createEmptyBoard(9, 9);
        Coords centre = new Coords(4, 4);
        // The supported ground + one-deck model: maglev N-S at level 2 and ground rail NW-SE beneath it.
        line(board, centre, 0, RailRoute.Kind.MAGLEV, 2);
        line(board, centre, 2, RailRoute.Kind.RAIL, 0);
        board.setNativeFormat(true);
        var crossing = board.getHex(centre);
        assertEquals(1, RailRoute.markers(crossing, RailRoute.Kind.MAGLEV).size());
        assertTrue(RailRoute.onDeck(RailRoute.find(crossing, RailRoute.Kind.MAGLEV)));
        assertFalse(RailRoute.onDeck(RailRoute.find(crossing, RailRoute.Kind.RAIL)));
        assertTrue(crossing.getDecorations().isEmpty(), "Both ways are terrain data");
        BoardScene scene = BoardScenes.scene(board);
        Map<String, BoardScene> scenes = new LinkedHashMap<>();
        Map<String, Map<String, Shot>> shots = new LinkedHashMap<>();
        for (var shot : Map.of("maglev-over-ground-rail", new Shot(centre, 1, 60, .3f),
              "maglev-over-ground-rail-top", new Shot(centre, 1, 0, .35f)).entrySet()) {
            scenes.put(shot.getKey(), scene);
            shots.put(shot.getKey(), Map.of("", shot.getValue()));
        }
        render(scenes, shots);
    }

    /** A straight route of {@code kind} along {@code direction} through {@code centre}, three hexes each way. */
    private static void line(Board board, Coords centre, int direction, RailRoute.Kind kind, int level) {
        List<Coords> cells = new ArrayList<>();
        for (int step = -3; step <= 3; step++) {
            cells.add(step < 0 ? centre.translated((direction + 3) % 6, -step) : centre.translated(direction, step));
        }
        for (int i = 0; i < cells.size(); i++) {
            int sides = (i > 0 ? RailRoute.side((direction + 3) % 6) : 0) | (i < cells.size() - 1 ? RailRoute.side(direction) : 0);
            var hex = board.getHex(cells.get(i));
            RailRoute.lay(hex, level > 0, sides, 0, kind);
            if (level > 0) { BridgeSpan.carry(hex, level, "bridge:4;bridge_cf:650"); }
            board.setHex(cells.get(i), hex);
        }
    }

    /**
     * Batch 10's maglev Pillars (step 4): a maglev drawn in the editor at level 2 across a road and a river, its Pillars
     * toggled for the whole route there, saved, and opened by the game and by the editor, which build the same markers;
     * captured at both level heights in {@code build/gpu-board-review/b10-s4/}.
     */
    @Test
    void aPillaredMaglevCrossesARoadAndWaterAlikeInGameAndEditor() throws Exception {
        File output = new File(OUTPUT.getParentFile(), "b10-s4");
        assertTrue(output.isDirectory() || output.mkdirs());
        var path = new ArrayList<Coords>(List.of(new Coords(1, 4)));
        for (int step = 0; step < 10; step++) { path.add(path.getLast().translated(step % 2 == 0 ? 2 : 1)); }
        File file = new File(output, "pillared-maglev.board2");
        var drawn = new FutureTask<>(() -> {
            var session = new BoardEditorSession();
            Board board = Board.createEmptyBoard(13, 10);
            for (int x = 0; x < 13; x++) {
                for (int y = 0; y < 10; y++) {
                    // A paved road N-S at column 3; a river of water:1 over columns 7 and 8.
                    board.setHex(new Coords(x, y), new Hex(0, x == 3 ? "road:1:09" : x == 7 || x == 8 ? "water:1" : "", ""));
                }
            }
            session.game().setBoard(board);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.ASSET, "", RailRoute.Kind.MAGLEV.asset), null);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.BRUSH_VALUE, "wayHeight", "level"), null);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.BRUSH_VALUE, "wayOffset", "2"), null);
            for (int i = 0; i < path.size(); i++) { session.pointer(path.get(i), 0, 0, i > 0); }
            session.finishStroke();
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "", "SELECT"), null);
            session.pointer(path.getFirst(), 0, 0, false);
            session.finishStroke(); session.release();
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.COMPONENT, "", "bridge"), null);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.PILLARS, "", "true"), null);
            session.save(file.toPath());
            return session.board();
        });
        SwingUtilities.invokeAndWait(drawn);
        for (Coords at : path) {
            assertTrue(HexAppearance.pillars(drawn.get().getHex(at).getAppearance()), at + " has bridge pillars");
        }
        BoardScene game = game(file), editor = editor(file);
        assertEquals(routes(game), routes(editor), "the game and the editor build the same pillared route");
        Coords road = path.stream().filter(at -> at.getX() == 3).findFirst().orElseThrow();
        Coords river = path.stream().filter(at -> at.getX() == 7).findFirst().orElseThrow();
        Coords middle = path.get(path.size() / 2);
        for (int levelHeight : new int[] { BoardGeometry.DEFAULTS.levelHeight(), 30 }) {
            BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, .8f));
            try {
                Map<String, BoardScene> scenes = new LinkedHashMap<>();
                Map<String, Map<String, Shot>> shots = new LinkedHashMap<>();
                var named = new LinkedHashMap<String, Shot>();
                named.put("maglev-pillars-overview", new Shot(middle, 1, 60, .5f));
                named.put("maglev-pillars-road", new Shot(road, 1, 65, .22f));
                named.put("maglev-pillars-water", new Shot(river, 1, 65, .22f));
                if (levelHeight == BoardGeometry.DEFAULTS.levelHeight()) { named.put("maglev-pillars-top", new Shot(middle, 1, 0, .5f)); }
                for (var shot : named.entrySet()) {
                    for (var view : List.of("game", "editor")) {
                        String name = shot.getKey() + "-" + levelHeight + "-" + view;
                        scenes.put(name, view.equals("game") ? game : editor);
                        shots.put(name, Map.of("", shot.getValue()));
                    }
                }
                render(output, scenes, shots);
            } finally {
                BoardGeometry.tune(BoardGeometry.DEFAULTS);
            }
        }
    }

    /**
     * The batch-10 report answers (b10-f3), as a game loads a legacy board and as the editor opens it (the same markers):
     * a rail bridge carries its rail across its whole span (Desert Mesa Oasis, Smash n Grab's double track); the row of
     * pillar art along Karkand's maglev line is its Pillars, the line a level up; Koryo's maglev over industrial plants has
     * Pillars (a level up) but no pier at a plant's joint. Then a pillared
     * maglev skips a building and draws over an industrial plant, with no pier at their joints. Clicking a pier picks
     * its bridge hex. Captures go to {@code build/gpu-board-review/b10-f3/}.
     */
    @Test
    void theReportAnswersDrawAlikeInGameAndEditorAndAPierPicksItsBridge() throws Exception {
        File output = new File(OUTPUT.getParentFile(), "b10-f3");
        assertTrue(output.isDirectory() || output.mkdirs());
        Map<String, Map<String, Shot>> boards = new LinkedHashMap<>();
        boards.put("unofficial/Cakefish/General/45x45 Desert Mesa Oasis.board", Map.of(
              "mesa-span-1", new Shot(hex(1930), 1, 50, .25f), "mesa-span-2", new Shot(hex(3433), 1, 50, .25f)));
        boards.put("unofficial/Cakefish/General/50x50 Grass- Smash n Grab.board", Map.of(
              "smash-double-track", new Shot(hex(2715), 1, 50, .3f)));
        boards.put("unofficial/Derv_Maps/35x35 Karkand.board", Map.of(
              "karkand-maglev", new Shot(hex(1311), 1, 55, .3f), "karkand-station", new Shot(hex(1307), 1, 60, .18f)));
        boards.put("unofficial/Derv_Maps/35x35 Koryo.board", Map.of(
              "koryo-maglev-over-plants", new Shot(hex(1218), 5, 55, .3f)));
        Map<String, BoardScene> scenes = new LinkedHashMap<>();
        Map<String, Map<String, Shot>> shots = new LinkedHashMap<>();
        for (var board : boards.entrySet()) {
            File file = new File(megamek.common.Configuration.boardsDir(), board.getKey());
            BoardScene game = game(file), editor = editor(file);
            assertEquals(routes(game), routes(editor), file.getName() + ": the game and the editor build the same routes");
            for (var shot : board.getValue().entrySet()) {
                for (var view : List.of("game", "editor")) {
                    scenes.put(shot.getKey() + "-" + view, view.equals("game") ? game : editor);
                    shots.put(shot.getKey() + "-" + view, Map.of("", shot.getValue()));
                }
            }
        }
        render(output, scenes, shots);

        // A pillared maglev at level 4 drawn over a building and an industrial plant, saved and opened by the game.
        var path = new ArrayList<Coords>(List.of(new Coords(1, 4)));
        for (int step = 0; step < 10; step++) { path.add(path.getLast().translated(step % 2 == 0 ? 2 : 1)); }
        Coords building = path.get(3), plant = path.get(7);
        File file = new File(output, "maglev-over-structures.board2");
        var drawn = new FutureTask<>(() -> {
            var session = new BoardEditorSession();
            Board board = Board.createEmptyBoard(13, 10);
            board.setHex(building, new Hex(0, "building:2;bldg_elev:2;bldg_cf:40", ""));
            board.setHex(plant, new Hex(0, "heavy_industrial:2", ""));
            session.game().setBoard(board);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.ASSET, "", RailRoute.Kind.MAGLEV.asset), null);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.BRUSH_VALUE, "wayHeight", "level"), null);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.BRUSH_VALUE, "wayOffset", "4"), null);
            for (int i = 0; i < path.size(); i++) { session.pointer(path.get(i), 0, 0, i > 0); }
            session.finishStroke();
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "", "SELECT"), null);
            session.pointer(path.getFirst(), 0, 0, false);
            session.finishStroke(); session.release();
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.COMPONENT, "", "bridge"), null);
            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.PILLARS, "", "true"), null);
            session.save(file.toPath());
            return null;
        });
        SwingUtilities.invokeAndWait(drawn);
        drawn.get();
        BoardScene game = game(file);
        for (int i = 0; i + 1 < path.size(); i++) {
            Coords a = path.get(i), b = path.get(i + 1);
            var tile = game.tile(a);
            boolean pier = BoardBridgeFooting.pier(game, tile, a.direction(b));
            boolean structure = a.equals(building) || b.equals(building) || a.equals(plant) || b.equals(plant);
            assertEquals(!structure, pier, a + "-" + b + (structure ? ": no pier beside a structure" : ": a pier"));
        }
        // A level-2 ray across the joint of two plain hexes meets the pier's shaft below the beam: it picks its terrain.
        Coords near = path.get(9), far = path.get(10);
        var joint = BoardGeometry.center(near, 2).lerp(BoardGeometry.center(far, 2), .5f);
        var along = BoardGeometry.center(far, 0).sub(BoardGeometry.center(near, 0)).nor();
        var across = new Vector3(-along.y, along.x, 0);
        var ray = new Ray(new Vector3(joint).mulAdd(across, 3 * BoardGeometry.width()), new Vector3(across).scl(-1));
        Map<String, BoardScene> pierScene = new LinkedHashMap<>(Map.of("maglev-over-structures", game));
        render(output, pierScene, Map.of("maglev-over-structures", Map.of("", new Shot(path.get(5), 2, 60, .45f))),
              (name, terrain) -> {
                  var hit = terrain.selectionHit(game, ray);
                  assertNotNull(hit, "The ray across the joint picks bridge terrain");
                  assertTrue(List.of(near, far).contains(hit.coords()), "A click on a pier selects its bridge hex: " + hit.coords());
                  assertTrue(terrain.decorationHit(game, ray) == null, "Terrain-owned piers are not placed objects");
              });
    }

    private static void render(Map<String, BoardScene> scenes, Map<String, Map<String, Shot>> shots) {
        render(OUTPUT, scenes, shots);
    }

    private static void render(File output, Map<String, BoardScene> scenes, Map<String, Map<String, Shot>> shots) {
        render(output, scenes, shots, (name, terrain) -> { });
    }

    /** Renders each named scene and shot to {@code output}; {@code check} then sees each shot's installed terrain. */
    private static void render(File output, Map<String, BoardScene> scenes, Map<String, Map<String, Shot>> shots,
          java.util.function.BiConsumer<String, GpuTerrain> check) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                GpuTerrain terrain = null;
                BoardScene installed = null;
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (var entry : scenes.entrySet()) {
                        var scene = entry.getValue();
                        if (scene != installed) {
                            if (terrain != null) { terrain.dispose(); }
                            terrain = new GpuTerrain();
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            installed = scene;
                        }
                        var shot = shots.get(entry.getKey()).get("");
                        camera.setIsometric(true);
                        // North up from straight above; turned a little for an oblique view.
                        camera.orbit((shot.tilt() < 1 ? 0 : 20) - camera.azimuth(), shot.tilt() - camera.tilt());
                        if (shot == FIT) {
                            camera.fit(scene);
                        } else {
                            camera.center(BoardGeometry.center(shot.at(), shot.level()));
                            camera.zoom(shot.zoom() / camera.camera.zoom);
                        }
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, entry.getKey() + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        check.accept(entry.getKey(), terrain);
                    }
                } catch (Throwable error) { failure.set(error); }
                finally {
                    if (terrain != null) { terrain.dispose(); }
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Rail review", failure.get()); }
    }
}
