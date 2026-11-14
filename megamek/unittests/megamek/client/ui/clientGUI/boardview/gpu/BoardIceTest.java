/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardIceTest {
    @Test
    void hiddenBlackIceDoesNotReplaceLegacy2DGrassWithPavement() throws Exception {
        onEdt(() -> {
            try (var tiles = new HexTileset(new File("data/images/hexes"))) {
                tiles.loadFromFile("saxarba.tileset");
                var dry = new Hex(0, "road:1:9", "grass");
                var hidden = new Hex(0, "road:1:9;black_ice:1", "grass");
                assertEquals(tiles.getBase(dry), tiles.getBase(hidden));
                assertEquals(tiles.getSupers(dry), tiles.getSupers(hidden));
                assertTrue(hidden.containsTerrain(Terrains.BLACK_ICE), "Artwork matching cannot mutate the rules hex");
            }
            return null;
        });
    }

    @Test
    void discoveryChangesOnlyTheMaterialSnapshotAndPreviewNeverShowsAuthoringMarkers() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(3, 3);
            var coords = new Coords(1, 1);
            var hex = new Hex(0, "road:1:9;black_ice:1;bldg_base_collapsed:1", "grass");
            board.setHex(coords, hex);
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                var hidden = source.takeFrame().scene();
                assertFalse(hidden.tile(coords).blackIce());
                assertFalse(hidden.tile(coords).frozen());
                assertTrue(BoardRoad.rendered(hidden.tile(coords)));
                assertEquals(BoardTactical.EMPTY, hidden.tactical());
                hex.getTerrain(Terrains.BLACK_ICE).detectBlackIce();
                board.setHex(coords, hex);
                source.refresh();
                var discovered = source.takeFrame().scene().tile(coords);
                assertTrue(discovered.blackIce());
                assertTrue(discovered.sameGeometry(hidden.tile(coords)), "Discovery adds no geometry");
                assertFalse(discovered.frozen(), "Do not turn thin black ice into load-bearing lake ice");
            }
            return null;
        });
    }

    @Test
    void editorStacksBadgesAndRemovesThemAfterTerrainEdits() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(2, 2);
            var coords = new Coords(0, 0);
            board.setHex(coords, new Hex(0, "black_ice:1;bldg_base_collapsed:1", ""));
            game.setBoard(board);
            var editor = new megamek.client.ui.boardeditor.BoardEditorSession();
            editor.game().setBoard(board);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                var markers = source.takeFrame().scene().tactical();
                assertEquals(BoardTactical.EMPTY, markers, "Floating badges replace the old ground card");
                assertEquals(java.util.List.of(BoardEditorTerrain.Kind.COLLAPSED_BASEMENT, BoardEditorTerrain.Kind.BLACK_ICE),
                      source.editorBadges().get(coords).stream().map(BoardEditorTerrain.Badge::kind).toList());
                board.setHex(coords, new Hex(0));
                source.refresh();
                assertEquals(BoardTactical.EMPTY, source.takeFrame().scene().tactical());
                assertTrue(source.editorBadges().isEmpty(), "Removing the flags clears their entire stack");
            }
            return null;
        });
    }

    @Test
    void flatLakeInteriorsUseOnlyFourIceTrianglesAndLandIceRetainsMarkerSupport() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(3, 3);
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) { board.setHex(new Coords(x, y), new Hex(0, "water:2;ice:1", "")); }
            }
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                var scene = source.takeFrame().scene();
                var center = new Coords(1, 1);
                var surface = new BoardSurface(scene, scene.tile(center));
                assertEquals(4, surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.ICE).count());
                assertTrue(surface.iceBroken.isEmpty() && surface.iceLeads.isEmpty(),
                      "Frozen neighbours continue one slab without cut edges");
                board.setHex(center, new Hex(0, "ice:1", ""));
                source.refresh();
                scene = source.takeFrame().scene();
                surface = new BoardSurface(scene, scene.tile(center));
                assertFalse(BoardTacticalGeometry.Surface.of(surface, scene, -10).top().isEmpty());
            }
            return null;
        });
    }

    @Test
    void lakeSlabCarriesUnitsAtItsLevelAndBreaksOutOverOpenWaterOnlyForRendering() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(4, 3);
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 3; y++) {
                    board.setHex(new Coords(x, y), new Hex(0, x == 1 ? "water:1;ice:1" : x > 1 ? "water:1" : "", ""));
                }
            }
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                var scene = source.takeFrame().scene();
                var frozen = scene.tile(new Coords(1, 1));
                var surface = new BoardSurface(scene, frozen);
                surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.ICE)
                      .flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
                      .forEach(p -> assertEquals(BoardGeometry.surfaceZ(frozen), p.z, 1e-4f, "Units stand on the slab"));
                Vector3 centre = BoardGeometry.center(frozen.coords(), 0);
                Vector3 farthest = surface.iceBroken.stream().flatMap(face -> Stream.of(face.a(), face.b(), face.c()))
                      .max(Comparator.comparingDouble(p -> Math.hypot(p.x - centre.x, p.y - centre.y))).orElseThrow();
                assertTrue(Math.hypot(farthest.x - centre.x, farthest.y - centre.y) > BoardGeometry.width() / 2,
                      "The ice breaks out over open water, beyond even the hex's corners");
                var hit = BoardGeometry.hit(scene, new Ray(new Vector3(farthest.x, farthest.y, 200), new Vector3(0, 0, -1)));
                assertNotNull(hit);
                assertNotEquals(frozen.coords(), hit.coords(), "Picking keeps the hex outline under the render-only margin");
            }
            return null;
        });
    }

    @Test
    void frozenAndOpenBedsMeetAlongEveryMouth() throws Exception {
        onEdt(() -> {
            var game = new Game();
            var board = Board.createEmptyBoard(5, 5);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    // Deeper open water through a shallower frozen lake: beds of both kinds share every corner.
                    board.setHex(new Coords(x, y), new Hex(0, x == 2 ? "water:2" : "water:1;ice:1", ""));
                }
            }
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) {
                var scene = source.takeFrame().scene();
                Map<Coords, List<BoardSurface.Face>> beds = new HashMap<>();
                for (var tile : scene.tiles()) {
                    beds.put(tile.coords(), new BoardSurface(scene, tile).faces.stream()
                          .filter(face -> face.finish() == BoardSurface.Finish.BED).toList());
                }
                int compared = 0;
                for (var tile : scene.tiles()) {
                    Vector3 centre = BoardGeometry.center(tile.coords(), 0);
                    for (int edge = 0; edge < 6; edge++) {
                        Coords other = tile.coords().translated(BoardGeometry.edgeDirection(edge));
                        if (!beds.containsKey(other)) { continue; }
                        Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge);
                        Vector3 b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                        Vector3 across = BoardGeometry.center(other, 0).sub(centre).nor().scl(.001f);
                        for (int k = 1; k < 20; k++) {
                            Vector3 p = new Vector3(a).lerp(b, k / 20f);
                            float own = BoardSurface.sampleHeight(beds.get(tile.coords()), p.x - across.x,
                                  p.y - across.y, Float.NaN);
                            float theirs = BoardSurface.sampleHeight(beds.get(other), p.x + across.x,
                                  p.y + across.y, Float.NaN);
                            if (Float.isNaN(own) || Float.isNaN(theirs)) { continue; }
                            assertEquals(own, theirs, .01f, "The beds of " + tile.coords() + " and " + other
                                  + " must meet without a slit at " + p);
                            compared++;
                        }
                    }
                }
                assertTrue(compared > 500, "Mouths between the lake's hexes were sampled: " + compared);
            }
            return null;
        });
    }

    @Test
    void blankFlagsAreIdentifiedFromTheSelectedArtworkWithoutHidingUnmodeledGround() throws Exception {
        onEdt(() -> {
            var board = Board.createEmptyBoard(1, 1);
            var coords = new Coords(0, 0);
            try (var artwork = new BoardArtwork()) {
                board.setHex(coords, new Hex(0, "ground_fluff:4", ""));
                var blank = artwork.capture(board, coords, board.getHex(coords));
                board.setHex(coords, new Hex(0, "ground_fluff:2000", ""));
                artwork.invalidate(coords);
                var visible = artwork.capture(board, coords, board.getHex(coords));
                assertTrue(BoardEditorTerrain.badges(board.getHex(coords)).isEmpty());
            }
            var flags = new Hex(0, "black_ice:1;bldg_base_collapsed:1;metal_deposit:1;fluff:0;road_fluff:99", "");
            var badges = BoardEditorTerrain.badges(flags);
            // Blank legacy tokens never reach the editor (the conversion drops them), so they are never marked.
            assertEquals(List.of(BoardEditorTerrain.Kind.COLLAPSED_BASEMENT, BoardEditorTerrain.Kind.BLACK_ICE,
                  BoardEditorTerrain.Kind.METAL_DEPOSITS), badges.stream().map(BoardEditorTerrain.Badge::kind).toList());
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
