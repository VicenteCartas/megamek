/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardUltraSublevelTest {
    private static final Coords PIT = new Coords(3, 3);

    @Test
    void zeroAndOneFlagsMakeOpaqueBarePitsAcrossThemesWithoutChangingGameElevation() {
        for (String theme : List.of("grass", "dirt", "desert", "mars", "snow", "lunar", "fungus")) {
            for (int flag : new int[] { 0, 1 }) {
                Hex hex = new Hex(-2, "ultra_sublevel:" + flag, theme);
                BoardScene scene = scene(at -> at.equals(PIT) ? hex : new Hex(-1, "", theme));
                var tile = scene.tile(PIT);
                assertTrue(tile.ultraSublevel());
                assertTrue(tile.detailedGround());
                assertEquals(-2, tile.elevation());
                assertEquals(-2, hex.getLevel(), "The cosmetic recess never edits game elevations");
                assertTrue(tile.features().isEmpty());
                assertFalse(BoardScatter.allowed(tile));
                assertFalse(BoardSurfaceBlend.natural(tile));
                assertTrue(tile.lunar(true).ultraSublevel() && tile.lunar(false).ultraSublevel());
                assertTrue(tile.withText(List.of()).ultraSublevel());
                for (TerrainLod lod : TerrainLod.values()) {
                    var surface = new BoardSurface(scene, tile, lod);
                    assertEquals(6, surface.faces.size(), "A complete hex seals the pit at every detail level");
                    for (var face : surface.faces) {
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            assertEquals(-3 * BoardGeometry.level(), point.z, .001f);
                        }
                    }
                    var support = BoardTacticalGeometry.Surface.of(surface, scene, BoardGeometry.floor(scene));
                    assertNull(BoardPlants.plant(scene, tile, support, lod, java.util.List.of()));
                }
                Vector3 center = BoardGeometry.center(PIT, 0);
                assertEquals(PIT, BoardGeometry.pick(scene, new Ray(center.add(0, 0, 500), new Vector3(0, 0, -1))));
                var column = GpuTilesetTerrain.column(scene, tile, BoardGeometry.floor(scene));
                assertEquals(-2 * BoardGeometry.level(), column.highestTop(), .001f,
                      "Tactical columns retain the authored pit tile at its game level");
            }
        }
        var material = GpuTerrain.pitMaterial();
        assertEquals(Color.BLACK, material.get(ColorAttribute.class, ColorAttribute.Diffuse).color);
        assertFalse(material.has(BlendingAttribute.Type), "The black cap is opaque, not a window onto the sky");
    }

    @Test
    void pitRimsUseForcedCliffsWithoutExtendingATalusApronIntoTheOpening() {
        BoardScenes.withTransitions(true, () -> {
            for (int drop : new int[] { 0, 1, 2 }) {
                BoardScene pit = scene(at -> new Hex(at.equals(PIT) ? -drop : 0,
                      at.equals(PIT) ? "ultra_sublevel:0" : "", "fungus"));
                Vector3 center = BoardGeometry.center(PIT, 0);
                for (int direction = 0; direction < 6; direction++) {
                    Coords at = PIT.translated(direction);
                    var tile = pit.tile(at);
                    int towardPit = at.direction(PIT);
                    assertEquals(1 << towardPit, tile.cliffTopExits());
                    assertTrue(BoardRim.high(tile, towardPit, drop + 1));
                    for (TerrainLod lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                        var surface = new BoardSurface(pit, tile, lod);
                        int edge = Math.floorMod(1 - towardPit, 6);
                        var points = surface.walls(pit, BoardGeometry.floor(pit)).stream()
                              .filter(face -> face.landEdge() == edge)
                              .flatMap(face -> List.of(face.a(), face.b(), face.c()).stream()).toList();
                        assertFalse(points.isEmpty());
                        float bottom = pit.tile(PIT).groundLevel() * BoardGeometry.level();
                        assertEquals(bottom, points.stream().mapToDouble(p -> p.z).min().orElseThrow(), .001);
                        assertEquals(0, points.stream().mapToDouble(p -> p.z).max().orElseThrow(), .001);
                        assertTrue(points.stream().map(surface.relief::shade)
                              .anyMatch(shade -> shade != null && shade.kind() == BoardRelief.Kind.PIT_WALL && shade.level() > .99f),
                              "Low pit edges use the existing cliff-top rock classification");
                        for (Vector3 point : points) {
                            assertTrue(Math.hypot(point.x - center.x, point.y - center.y) > .38f * BoardGeometry.width(),
                                  "The cliff must not spread a textured apron across the pit");
                        }
                    }
                }
                for (int corner = 0; corner < 6; corner++) {
                    Vector3 nearRim = new Vector3(center).lerp(BoardGeometry.corner(PIT, 0, corner), .75f);
                    assertEquals(PIT, BoardGeometry.pick(pit, new Ray(nearRim.add(0, 0, 500), new Vector3(0, 0, -1))),
                          "The black opening remains exposed close to the rim");
                }
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void scatterFromNeighboringTilesCannotLandInAPit(int flag) {
        BoardScene scene = scene(at -> new Hex(at.equals(PIT) ? -2 : 0,
              at.equals(PIT) ? "ultra_sublevel:" + flag : "", "grass"));
        var owner = scene.tile(PIT.translated(0));
        assertTrue(BoardScatter.visible(scene, owner, BoardGeometry.center(owner.coords(), 0), 1));
        for (int level : new int[] { -3, 0, 3 }) {
            assertFalse(BoardScatter.visible(scene, owner, BoardGeometry.center(PIT, level), 1),
                  "A neighboring tile cannot place scatter anywhere above the pit");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void pitRimRocksStayOnSolidLandAtEveryDetail(int flag) {
        for (String theme : List.of("grass", "mars", "fungus")) {
            BoardScene scene = scene(at -> new Hex(at.equals(PIT) ? -2 : 0,
                  at.equals(PIT) ? "ultra_sublevel:" + flag : "", theme));
            for (TerrainLod lod : TerrainLod.values()) {
                int rocks = 0;
                for (int direction = 0; direction < 6; direction++) {
                    var tile = scene.tile(PIT.translated(direction));
                    int edge = Math.floorMod(1 - tile.coords().direction(PIT), 6);
                    var surface = new BoardSurface(scene, tile, lod);
                    var land = surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.TOP).toList();
                    var rimRocks = surface.faces.stream().filter(face -> face.landEdge() == edge
                          && face.finish() == BoardSurface.Finish.OUTCROP).toList();
                    rocks += rimRocks.size();
                    for (var face : rimRocks) {
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            assertTrue(Float.isFinite(BoardSurface.sampleHeight(land, point.x, point.y, Float.NaN)),
                                  theme + " " + lod + ": every rock vertex must have solid land beneath it");
                        }
                    }
                }
                if (lod.dressing) { assertTrue(rocks > 0, "Solid land around the pit must retain rim rocks"); }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1 })
    void pitFacingWallsDoNotGrowFungalScatter(int flag) {
        BoardScene scene = scene(at -> new Hex(at.equals(PIT) ? -2 : 0,
              at.equals(PIT) ? "ultra_sublevel:" + flag : "", "fungus"));
        for (int direction = 0; direction < 6; direction++) {
            var tile = scene.tile(PIT.translated(direction));
            var surface = new BoardSurface(scene, tile);
            List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
            faces.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
            assertTrue(BoardFungus.cliffs(scene, tile, faces).isEmpty(),
                  "Fungal scatter must not grow into a pit from its neighboring walls");
        }
    }

    @Test
    void addingAndRemovingAPitInvalidatesItsCapAndTheSurroundingCliffs() {
        BoardScene before = scene(at -> new Hex(0, "", "grass"));
        BoardScene after = scene(at -> new Hex(0, at.equals(PIT) ? "ultra_sublevel:0" : "", "grass"));
        var cache = new BoardSurface.Cache();
        for (Coords at : List.of(PIT, PIT.translated(0))) {
            assertFalse(before.tile(at).sameGeometry(after.tile(at)));
            assertNotEquals(BoardSurface.geometryKey(before, before.tile(at)), BoardSurface.geometryKey(after, after.tile(at)));
            var original = cache.get(before, before.tile(at));
            assertNotSame(original, cache.get(after, after.tile(at)));
            assertEquals(original.faces, cache.get(before, before.tile(at)).faces);
        }
        Coords next = PIT.translated(0);
        BoardScene joined = scene(at -> new Hex(0,
              at.equals(PIT) || at.equals(next) ? "ultra_sublevel:0" : "", "grass"));
        var cap = new BoardSurface(joined, joined.tile(PIT));
        int shared = Math.floorMod(1 - PIT.direction(next), 6);
        assertTrue(cap.sides(joined, BoardGeometry.floor(joined)).stream().noneMatch(side -> side.edge() == shared),
              "Adjacent pit floors join without an internal cliff");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void mixedHeightPitRimsStayClosedAtEveryDetail(boolean transitions) {
        BoardScenes.withTransitions(transitions, () -> {
            BoardScene scene = scene(at -> new Hex(at.equals(PIT) ? -2
                  : at.distance(PIT) == 1 ? -(PIT.direction(at) % 3) : 0,
                  at.equals(PIT) ? "ultra_sublevel:0" : "", "fungus"));
            float floor = BoardGeometry.floor(scene);
            for (TerrainLod lod : TerrainLod.values()) {
                List<BoardSurface.Face> faces = new ArrayList<>();
                for (var tile : scene.tiles()) {
                    var surface = new BoardSurface(scene, tile, lod);
                    faces.addAll(surface.faces);
                    var walls = surface.walls(scene, floor);
                    faces.addAll(walls);
                    for (var face : walls) {
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            var shade = surface.relief.shade(point);
                            if (shade.kind() == BoardRelief.Kind.PIT_WALL) {
                                assertEquals((point.z - BoardGeometry.groundZ(scene.tile(PIT))) / BoardRelief.metres(1),
                                      shade.rim(), .001f, "Every pit wall, including its corners, fades from the same floor");
                            }
                        }
                    }
                }
                BoardScenes.assertClosed(faces, floor, "Pit among different rim levels " + lod);
            }
        });
    }

    @Test
    void fungalCrevasseCapturesAllThreeZeroValuedPitsAndTheirRims() {
        Board board = new Board();
        board.load(new File("data/boards/Alien Worlds/32x17 Fungal Crevasse.board"));
        BoardScene scene = capture(board.getWidth(), board.getHeight(), board::getHex);
        var pits = scene.tiles().stream().filter(BoardScene.Tile::ultraSublevel).toList();
        assertEquals(3, pits.size());
        for (var pit : pits) {
            assertEquals(0, board.getHex(pit.coords()).terrainLevel(Terrains.ULTRA_SUBLEVEL));
            for (int direction = 0; direction < 6; direction++) {
                var rim = scene.tile(pit.coords().translated(direction));
                assertTrue((rim.cliffTopExits() & (1 << rim.coords().direction(pit.coords()))) != 0);
                assertFalse(board.getHex(rim.coords()).containsTerrain(Terrains.CLIFF_TOP), "No synthetic game terrain");
            }
        }
    }

    @Test
    void gameAndMapPreviewRefreshPitRimsAfterAnEdit() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                var preview = new GpuMapSource(fixture.game, null, null);
                try {
                    var board = fixture.game.getBoard();
                    for (int direction = -1; direction < 6; direction++) {
                        board.setHex(direction < 0 ? PIT : PIT.translated(direction), new Hex(0, "", "grass"));
                    }
                    for (boolean present : new boolean[] { true, false }) {
                        board.setHex(PIT, new Hex(0, present ? "ultra_sublevel:0" : "", "grass"));
                        fixture.source.refresh();
                        preview.refresh();
                        for (var scene : List.of(fixture.source.takeFrame().scene(), preview.takeFrame().scene())) {
                            assertEquals(present, scene.tile(PIT).ultraSublevel());
                            for (int direction = 0; direction < 6; direction++) {
                                var rim = scene.tile(PIT.translated(direction));
                                assertEquals(present, (rim.cliffTopExits() & (1 << rim.coords().direction(PIT))) != 0);
                            }
                        }
                        assertEquals(0, board.getHex(PIT).getLevel());
                    }
                } finally { preview.close(); }
            });
        }
    }

    private static BoardScene scene(Function<Coords, Hex> source) { return capture(7, 7, source); }

    private static BoardScene capture(int width, int height, Function<Coords, Hex> source) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        var pool = new BoardScene.PixelPool();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Coords at = new Coords(x, y);
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(source.apply(at), artwork, null, pool,
                      c -> c.getX() < 0 || c.getY() < 0 || c.getX() >= width || c.getY() >= height ? null : source.apply(c)));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
