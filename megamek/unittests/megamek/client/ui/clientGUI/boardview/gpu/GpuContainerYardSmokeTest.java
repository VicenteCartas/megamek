/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Container yards as a look of the woods cover (b12), as the game and the editor draw them. A review board: row A the
 * Rows look at foliage, light, heavy and ultra cover, a cleared yard and a heavy gantry yard burnt down to light; row B
 * Scattered (foliage), the two quay crane looks and the gantry yard at light, heavy and ultra; row C Rows at the twelve
 * rotations; row D the quay crane look at the six hex directions on islands, a chained crane (the quay crane prop and two
 * snapped boom segments) on the quay beyond; row E the ten props. Captured in the Enhanced and Classic graphics and the
 * Tactical View, with a click under a crane's boom picking that hex; two shipped maps as a game loads them; the 2D
 * tileset of the review board's {@code .board2} and of legacy crane art; and the editor's pages. The concrete pins of
 * every shipped board with yard art are printed. Captures in {@code build/gpu-board-review/yards/}.
 */
@Tag("on-demand")
class GpuContainerYardSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "yards");
    private static final String ROWS = "woods/container-yard/rows", CRANE = "woods/container-yard/quay-crane";
    private static final String[] COVERS = { "woods:1;foliage_elev:1", "woods:1;foliage_elev:2", "woods:2;foliage_elev:2",
          "woods:3;foliage_elev:3" };
    private static final String[] PROPS = { "container", "container-stack-2", "container-stack-3", "quay-crane", "crane-boom",
          "yard-gantry", "yard-gantry-tall", "straddle-carrier", "reach-stacker", "yard-truck" };
    private static final Coords CHAINED = new Coords(3, 13), FIRST_CRANE = new Coords(1, 10);

    private static void yard(Board board, Coords at, int level, String terrain, String look, int rotation) {
        Hex hex = new Hex(level, "pavement:1;" + terrain, "");
        hex.setAppearance(Map.of("vegetation", new HexAppearance(look, null, null, null, BoardDecoration.Colours.NONE, rotation)));
        board.setHex(at, hex);
    }

    private static BoardDecoration prop(String id, String name, double x, double y, double rotation) {
        return new BoardDecoration(id, "prop", "scenery/seaport/yard/" + name, null, x, y, rotation, false, 1,
              BoardDecoration.Placement.ground(), 0);
    }

    static Board reviewBoard() {
        Board board = Board.createEmptyBoard(18, 19);
        for (int x = 0; x < 18; x++) {
            for (int y = 0; y < 19; y++) {
                board.setHex(new Coords(x, y), new Hex(y >= 8 && y <= 12 ? 0 : y == 13 ? 1 : 0,
                      y >= 8 && y <= 12 ? "water:1" : "pavement:1", ""));
            }
        }
        for (int i = 0; i < 4; i++) { yard(board, new Coords(1 + i, 1), 0, COVERS[i], ROWS, 0); }
        // Woods cleared to rough draw no yard; a heavy gantry yard burnt down a level (tryClearHex) draws the light one.
        yard(board, new Coords(7, 1), 0, "rough:1", ROWS, 0);
        yard(board, new Coords(9, 1), 0, COVERS[2], "woods/container-yard/yard-gantry", 0);
        board.getHex(9, 1).addTerrain(new Terrain(Terrains.WOODS, 1));
        yard(board, new Coords(1, 3), 0, COVERS[0], "woods/container-yard/scattered", 0);
        yard(board, new Coords(3, 3), 0, COVERS[1], CRANE, 0);
        yard(board, new Coords(5, 3), 0, COVERS[1], "woods/container-yard/quay-crane-mirrored", 0);
        for (int i = 0; i < 3; i++) { yard(board, new Coords(7 + i, 3), 0, COVERS[1 + i], "woods/container-yard/yard-gantry", 0); }
        for (int i = 0; i < 12; i++) { yard(board, new Coords(1 + i, 6), 0, COVERS[1], ROWS, 30 * i); }
        for (int i = 0; i < 6; i++) { yard(board, new Coords(1 + 3 * i, 10), 1, COVERS[1], CRANE, 60 * i); }
        // A super-long crane: the prop's boom end and two segments, end to end northwards over the water.
        board.getHex(CHAINED).setDecorations(List.of(prop("crane", "quay-crane", 0, 0, 0), prop("boom-1", "crane-boom", 0, 1, 0),
              prop("boom-2", "crane-boom", 0, 2, 0)));
        for (int i = 0; i < PROPS.length; i++) {
            board.getHex(1 + i, 16).setDecorations(List.of(prop("prop-" + i, PROPS[i], 0, 0, 30)));
        }
        board.setNativeFormat(true);
        return board;
    }

    /** Renders until the terrain has no build left, a few frames at least. */
    private static void settle(GpuBattleView view) throws Exception {
        var terrain = (GpuTerrain) GpuCamouflageReview.field(view, "terrain");
        long deadline = System.nanoTime() + 120_000_000_000L;
        for (int frame = 0; frame < 12 || terrain.busy(); frame++) {
            assertTrue(System.nanoTime() < deadline, "The terrain settles");
            view.render();
        }
    }

    /** Looks at {@code at} from {@code tilt} degrees off the vertical at {@code zoom} (world units per pixel). */
    private static void look(GpuBattleView view, Coords at, float level, float zoom, float azimuth, float tilt) throws Exception {
        var camera = view.boardCamera;
        camera.orbit(azimuth - camera.azimuth(), tilt - camera.tilt());
        camera.center(BoardGeometry.center(at, level));
        camera.zoom(zoom / camera.camera.zoom);
        settle(view);
    }

    private static void capture(String name) {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        GpuBoardTestUi.capture(new File(OUTPUT, name));
    }

    @Test
    void reviewBoardMapsAndTheTwoDBoardDrawTheLooks() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        Board board = reviewBoard();
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        Files.write(OUTPUT.toPath().resolve("review.board2"), text.toByteArray());
        Board saved = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
        pins();
        twoD(saved);
        var failure = new AtomicReference<Throwable>();
        // The runs set the HUD scale; the next test (and the user's preferences) get theirs back.
        float scale = GUIPreferences.getInstance().getBoardUiScale();
        try {
            views(saved, failure);
        } finally {
            SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setBoardUiScale(scale));
        }
    }

    /** The review board in every view, then two shipped maps as a game loads them. */
    private static void views(Board saved, AtomicReference<Throwable> failure) throws Exception {
        try (var fixture = GpuBoardFixture.create(saved)) {
            GpuTacticalViewSmokeTest.run(fixture, failure, 1600, 1000, view -> {
                var scene = (BoardScene) GpuCamouflageReview.field(view, "scene");
                var terrain = (GpuTerrain) GpuCamouflageReview.field(view, "terrain");
                assertTrue(scene.tile(FIRST_CRANE).features().stream().anyMatch(f -> f.asset().equals("scenery/seaport/yard/crane-boom")));
                view.boardCamera.setIsometric(false);
                view.boardCamera.fit(scene);
                settle(view);
                capture("review-enhanced-top.png");
                view.boardCamera.setIsometric(true);
                view.boardCamera.fit(scene);
                settle(view);
                capture("review-enhanced-oblique.png");
                // A click at the centre of the hex under a crane's boom picks that hex, not the crane's.
                Coords under = FIRST_CRANE.translated(1);
                Vector3 centre = BoardGeometry.center(under, 0);
                var down = new Ray(new Vector3(centre.x, centre.y, 1000), new Vector3(0, 0, -1));
                assertEquals(under, terrain.selectionHit(scene, down).coords());
                assertEquals(under, terrain.walkableHit(scene, down).coords());
                // Close-ups by focus and zoom (world units per pixel).
                Map<String, Coords> closeUps = Map.of("rows-a-b", new Coords(5, 2), "rotations", new Coords(6, 6),
                      "cranes", new Coords(9, 10), "chained", new Coords(3, 12), "props", new Coords(5, 16));
                Map<String, Float> zooms = Map.of("rows-a-b", .5f, "rotations", .6f, "cranes", .8f, "chained", .35f, "props", .5f);
                for (var shot : closeUps.entrySet()) {
                    look(view, shot.getValue(), 0, zooms.get(shot.getKey()), 0, 50);
                    capture("review-" + shot.getKey() + ".png");
                }
                look(view, new Coords(9, 10), 0, .8f, 135, 55);
                capture("review-cranes-from-south-east.png");
                look(view, new Coords(9, 10), 0, .8f, 0, 50);
                GpuTacticalViewSmokeTest.graphics(view, BoardGraphics.CLASSIC);
                view.boardCamera.fit(scene);
                settle(view);
                capture("review-classic-oblique.png");
                look(view, new Coords(9, 10), 0, .8f, 0, 50);
                capture("review-classic-cranes.png");
                GpuTacticalViewSmokeTest.graphics(view, BoardGraphics.ENHANCED);
                GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
                settle(view);
                assertTrue(view.boardCamera.tactical(), "T enters the Tactical View");
                view.boardCamera.fit(scene);
                settle(view);
                capture("review-tactical-top.png");
                assertEquals(under, terrain.selectionHit(scene, down).coords(), "The Tactical View picks the same hex");
            });
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        for (String map : List.of("unofficial/Morkath/100x78 Shipyard Entrance.board",
              "unofficial/Cakefish/General/48x51 Arid Portside Connection.board")) {
            Board sheet = BoardFile.loadSheet(new File(Configuration.boardsDir(), map), false, true);
            String name = map.contains("Shipyard") ? "shipyard-entrance" : "arid-portside";
            try (var fixture = GpuBoardFixture.create(sheet)) {
                GpuTacticalViewSmokeTest.run(fixture, failure, 1600, 1000, view -> {
                    var scene = (BoardScene) GpuCamouflageReview.field(view, "scene");
                    if (name.equals("shipyard-entrance")) {
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.fit(scene);
                        settle(view);
                        capture(name + "-wide.png");
                        // Its densest yard: the yard hex with the most yard hexes around it.
                        java.util.function.Predicate<BoardScene.Tile> yard = t -> t != null && t.features().stream()
                              .anyMatch(f -> f.asset().startsWith("scenery/seaport/yard/"));
                        Coords crane = scene.tiles().stream().filter(yard).max(java.util.Comparator.comparingLong(t -> java.util.stream.IntStream
                              .range(0, 6).filter(d -> yard.test(scene.tile(t.coords().translated(d)))).count())).orElseThrow().coords();
                        look(view, crane, scene.tile(crane).elevation(), .6f, 0, 55);
                        capture(name + "-close.png");
                    } else {
                        // The concrete examples' spot (b12 pin refinement, example 3): the quay at 3213, from the south-west.
                        Coords quay = new Coords(31, 12);
                        look(view, quay, scene.tile(quay).elevation(), .4f, -60, 58);
                        capture(name + "-3213.png");
                    }
                });
            }
            assertNull(failure.get(), () -> map + ": " + failure.get());
        }
    }

    /** Prints, per shipped board with yard art, the hexes whose concrete outline a yard or crane look pins. */
    private static void pins() throws Exception {
        int total = 0, boards = 0;
        List<File> files;
        try (var walk = Files.walk(Configuration.boardsDir().toPath())) {
            files = walk.filter(path -> path.toString().endsWith(".board")).map(java.nio.file.Path::toFile).sorted().toList();
        }
        for (File file : files) {
            String content = Files.readString(file.toPath(), StandardCharsets.ISO_8859_1);
            if (!content.contains("fluff:80:") && !content.contains("fluff:81:")) { continue; }
            Board sheet = BoardFile.loadSheet(file, false, true);
            BoardScene scene = BoardScenes.scene(sheet);
            var pinned = BoardConcrete.of(scene).fit().pinned();
            long yards = pinned.stream().filter(at -> BoardSceneryLayouts.skin(sheet.getHex(at)) != null).count();
            if (yards > 0) {
                boards++;
                total += yards;
                System.out.printf("PINS %3d yard hexes of %3d pinned  %s%n", yards, pinned.size(),
                      Configuration.boardsDir().toPath().relativize(file.toPath()));
            }
        }
        System.out.printf("PINS total %d yard hexes on %d boards%n", total, boards);
    }

    /**
     * The 2D tileset drawing the review board's {@code .board2} (the looks through the 2D hook) and a legacy crane
     * fixture as a game of the loaded {@code .board} keeps it (its tokens).
     */
    private static void twoD(Board saved) throws Exception {
        Board legacy = Board.createEmptyBoard(14, 8);
        for (int x = 0; x < 14; x++) {
            for (int y = 0; y < 8; y++) { legacy.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
        }
        // The six crane directions of the N-S rows and of the NE-SW rows (81:01 ... 81:24 by threes), with their tips.
        int[] headings = { 1, 2, 4, 5, 2, 3, 5, 0 };
        for (int i = 0; i < 8; i++) {
            Coords crane = new Coords(1 + i % 4 * 3, 1 + i / 4 * 4);
            legacy.setHex(crane, new Hex(0, "pavement:1;woods:1;foliage_elev:2;fluff:81:" + (1 + 3 * i), ""));
            legacy.setHex(crane.translated(headings[i]), new Hex(0, "pavement:1;fluff:82:" + (headings[i] + 1), ""));
        }
        BoardSceneryLayouts.convert(legacy);
        var tileset = new HexTileset(new Game());
        try {
            tileset.loadFromFile("saxarba.tileset");
            ImageIO.write(drawn(tileset, saved), "png", new File(OUTPUT, "review-2d-board2.png"));
            ImageIO.write(drawn(tileset, legacy), "png", new File(OUTPUT, "legacy-cranes-2d.png"));
            ImageIO.write(drawn(tileset, BoardFile.readNative(text(legacy))), "png", new File(OUTPUT, "legacy-cranes-2d-board2.png"));
        } finally {
            tileset.close();
        }
    }

    private static String text(Board board) throws Exception {
        var out = new ByteArrayOutputStream();
        BoardFile.write(board, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Every hex's base and superimposed images, as the 2D board stacks them (84 x 72 hexes, columns 63 apart). */
    private static BufferedImage drawn(HexTileset tileset, Board board) {
        var image = new BufferedImage(board.getWidth() * 63 + 21, board.getHeight() * 72 + 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(48, 48, 48));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Hex hex = board.getHex(x, y);
                int px = x * 63, py = y * 72 + (x % 2 == 1 ? 36 : 0);
                graphics.drawImage(GpuLegacyArtReportSmokeTest.loaded(tileset, tileset.getBase(hex)), px, py, null);
                for (Image layer : tileset.getSupers(hex)) {
                    graphics.drawImage(GpuLegacyArtReportSmokeTest.loaded(tileset, layer), px, py, null);
                }
            }
        }
        graphics.dispose();
        return image;
    }

    /**
     * The editor: the Assets start page (the Industry tile is the gantry yard look), Industry > Seaport, the Container
     * yard gallery, a crane look's brush with its Rotation row, its ghost while hovering and mid-stroke, and a painted
     * hex's Edit panel with the Rotation row.
     */
    @Test
    void editorShowsTheYardGalleryTheBrushGhostAndTheRotationRows() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        Coords first = new Coords(5, 5), second = first.translated(2);
        List<GpuEditorPaletteSmokeTest.Step> steps = new ArrayList<>(List.of(
              (view, source, root) -> {
                  if (root.findActor("editor-back") != null) { GpuBoardTestUi.click("editor-back"); return false; }
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-category-scroll"))) { return false; }
                  capture("editor-start.png");
                  GpuBoardTestUi.click("editor-category-industry");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-industry/seaport"));
                  GpuBoardTestUi.reveal("editor-section-industry/seaport");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  capture("editor-industry-seaport.png");
                  GpuBoardTestUi.click("editor-gallery-Container yard");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-library-" + CRANE), "The gallery shows the crane look");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  capture("editor-gallery.png");
                  GpuBoardTestUi.click("editor-library-" + CRANE);
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-brush-rotation:vegetation"), "The brush's Rotation row");
                  source.editorCommand(new Command(Action.BRUSH_VALUE, "rotation:vegetation", "60"), source.takeFrame().boardGeneration());
                  source.editorCommand(new Command(Action.BRUSH, "", "1"), source.takeFrame().boardGeneration());
                  view.boardCamera.center(BoardGeometry.center(first, 0));
                  view.boardCamera.zoom(.35f / view.boardCamera.camera.zoom);
                  return true;
              },
              (view, source, root) -> {
                  hover(view, first);
                  return true;
              },
              (view, source, root) -> {
                  hover(view, first);
                  capture("editor-brush-ghost.png");
                  press(view, first);
                  drag(view, second);
                  return true;
              },
              (view, source, root) -> {
                  // Mid-stroke: both stroke hexes draw the look once the terrain has rebuilt them.
                  var tiles = source.takeFrame().scene();
                  if (GpuBoardTestUi.busy(view) || Set.of(first, second).stream()
                        .anyMatch(at -> tiles.tile(at).features().stream().noneMatch(f -> f.asset().equals("scenery/seaport/yard/crane-boom")))) {
                      return false;
                  }
                  capture("editor-mid-stroke.png");
                  release(view, second);
                  return true;
              },
              (view, source, root) -> {
                  var hex = source.takeFrame().scene().tile(first);
                  assertTrue(hex.features().stream().anyMatch(f -> f.asset().equals("scenery/seaport/yard/crane-boom")), "Painted");
                  source.editorCommand(new Command(Action.TOOL, "", "SELECT"), source.takeFrame().boardGeneration());
                  source.editorCommand(new Command(Action.SELECT_AT, first.getX() + "," + first.getY(), ""),
                        source.takeFrame().boardGeneration());
                  source.editorCommand(new Command(Action.COMPONENT, "", "vegetation"), source.takeFrame().boardGeneration());
                  return true;
              },
              (view, source, root) -> {
                  assertTrue(GpuBoardTestUi.texts(root).stream().anyMatch(t -> t.contains("Rotation (clockwise)")), "The Edit row");
                  capture("editor-edit-rotation.png");
                  return true;
              }));
        GpuEditorPaletteSmokeTest.run(1366, 768, steps);
    }

    private static Vector3 screen(GpuBattleView view, Coords at) {
        Vector3 result = view.boardCamera.camera.project(BoardGeometry.center(at, 0));
        result.y = Gdx.graphics.getHeight() - result.y;
        return result;
    }

    private static void hover(GpuBattleView view, Coords at) {
        Vector3 point = screen(view, at);
        Gdx.input.getInputProcessor().mouseMoved(Math.round(point.x), Math.round(point.y));
    }

    private static void press(GpuBattleView view, Coords at) {
        Vector3 point = screen(view, at);
        GpuBoardTestUi.withModifiers(0, () -> Gdx.input.getInputProcessor().touchDown(Math.round(point.x), Math.round(point.y), 0,
              Input.Buttons.LEFT));
    }

    private static void drag(GpuBattleView view, Coords at) {
        Vector3 point = screen(view, at);
        Gdx.input.getInputProcessor().touchDragged(Math.round(point.x), Math.round(point.y), 0);
    }

    private static void release(GpuBattleView view, Coords at) {
        Vector3 point = screen(view, at);
        Gdx.input.getInputProcessor().touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }
}
