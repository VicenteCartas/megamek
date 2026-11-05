/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardBackdropTest {
    private static final Coords CENTER = new Coords(2, 2);

    @Test
    void spaceAndSkyStayFlatSelectableAndBareAcrossThemesAndDetailLevels() {
        for (String terrain : List.of("space:1", "sky:1")) {
            for (String theme : List.of("", "lunar", "grass", "fungus")) {
                Hex hex = new Hex(-2, terrain, theme);
                BoardScene scene = scene(at -> hex);
                BoardScene.Tile tile = scene.tile(CENTER);
                assertEquals(terrain.startsWith("space") ? BoardBackdrop.SPACE : BoardBackdrop.SKY, tile.backdrop());
                assertTrue(tile.detailedGround());
                assertEquals(-2, hex.getLevel());
                assertEquals(-2, tile.elevation());
                assertTrue(tile.features().isEmpty());
                assertFalse(BoardSurfaceBlend.natural(tile));
                assertFalse(BoardScatter.allowed(tile));
                assertFalse(GpuGroundCover.grows(scene, tile));
                for (TerrainLod lod : TerrainLod.values()) {
                    var surface = new BoardSurface(scene, tile, lod);
                    assertFalse(surface.relief.sculpted());
                    assertEquals(6, surface.faces.size());
                    assertTrue(surface.walls(scene, BoardGeometry.floor(scene)).isEmpty());
                    for (var face : surface.faces) {
                        for (var point : List.of(face.a(), face.b(), face.c())) {
                            assertEquals(-2 * BoardGeometry.level(), point.z, .001);
                        }
                    }
                    var support = BoardTacticalGeometry.Surface.of(surface, scene, BoardGeometry.floor(scene));
                    assertNull(BoardPlants.plant(scene, tile, support, lod, List.of()));
                }
                Vector3 center = BoardGeometry.center(CENTER, 0);
                assertEquals(CENTER, BoardGeometry.pick(scene, new Ray(center.add(0, 0, 500), new Vector3(0, 0, -1))));
                assertEquals(tile.backdrop(), tile.lunar(true).backdrop());
                assertEquals(tile.backdrop(), tile.withText(List.of()).backdrop());
            }
        }
    }

    @Test
    void backdropEditsInvalidateGeometryEvenWhenMaterialAndElevationAreUnchanged() {
        BoardScene space = scene(at -> new Hex(0, "space:1", "lunar"));
        BoardScene sky = scene(at -> new Hex(0, "sky:1", "lunar"));
        BoardScene ground = scene(at -> new Hex(0, "", "lunar"));
        assertEquals(space.tile(CENTER).surface(), ground.tile(CENTER).surface());
        assertFalse(space.tile(CENTER).sameGeometry(sky.tile(CENTER)));
        assertFalse(space.tile(CENTER).sameGeometry(ground.tile(CENTER)));
        assertNotEquals(BoardSurface.geometryKey(space, space.tile(CENTER)),
              BoardSurface.geometryKey(ground, ground.tile(CENTER)));
        var cache = new BoardSurface.Cache();
        assertNotSame(cache.get(space, space.tile(CENTER)), cache.get(ground, ground.tile(CENTER)));
    }

    @Test
    void bridgesRemainAboveSpaceAndSolidSurfacesTakePrecedenceOverAnImportedBaseMarker() {
        BoardScene scene = scene(at -> new Hex(-2, at.equals(CENTER)
              ? "space:1;bridge:1:9;bridge_cf:200;bridge_elev:1" : "space:1", ""));
        var tile = scene.tile(CENTER);
        assertEquals(BoardBackdrop.SPACE, tile.backdrop());
        assertTrue(tile.features().stream().anyMatch(feature -> feature.asset().equals("bridge")));
        assertTrue(tile.detailedGround());
        assertEquals(BoardBackdrop.NONE, BoardBackdrop.capture(new Hex(0, "space:1;pavement:1", "lunar")));
        assertEquals(BoardBackdrop.NONE, BoardBackdrop.capture(new Hex(0, "sky:1;water:1", "")));
        assertEquals(BoardBackdrop.NONE, BoardBackdrop.capture(new Hex(0, "", "lunar")));
    }

    @Test
    void neighboringScatterCannotFloatOverAnAerospaceCell() {
        BoardScene scene = scene(at -> new Hex(0, at.equals(CENTER) ? "space:1" : "", ""));
        BoardScene.Tile ground = scene.tile(CENTER.translated(0));
        assertFalse(BoardScatter.visible(scene, ground, BoardGeometry.center(CENTER, 0), BoardRelief.metres(2)));
    }

    private static BoardScene scene(Function<Coords, Hex> source) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        var pool = new BoardScene.PixelPool();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(x, y);
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(source.apply(at), artwork, null, pool,
                      c -> c.getX() < 0 || c.getY() < 0 || c.getX() >= 5 || c.getY() >= 5 ? null : source.apply(c)));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void solidIslandsKeepAllTheirExposedSidesBesideAerospacePickingPlanes() {
        for (String backdrop : List.of("space:1", "sky:1")) {
            for (int emptyElevation : new int[] { -2, 1, 4 }) {
                var scene = scene(backdrop, emptyElevation, false);
                var tile = scene.tile(CENTER);
                float floor = BoardGeometry.floor(scene);
                for (TerrainLod lod : TerrainLod.values()) {
                    var surface = new BoardSurface(scene, tile, lod);
                    var sides = surface.sides(scene, floor, Map.of());
                    assertEquals(6, sides.stream().map(BoardSurface.Side::edge).distinct().count());
                    for (var side : sides) {
                        assertEquals(floor, side.lowA(), .001f);
                        assertEquals(floor, side.lowB(), .001f);
                        assertEquals(BoardGeometry.level(), side.a().z, .001f);
                        assertEquals(BoardGeometry.level(), side.b().z, .001f);
                    }
                    assertFalse(surface.walls(scene, floor).isEmpty());
                }
            }
        }
    }

    @Test
    void realSolidNeighborsStillHideSharedSidesAndBackdropEditsInvalidateTheShape() {
        var empty = scene("space:1", 1, false);
        var filled = scene("space:1", 1, true);
        var cache = new BoardSurface.Cache();
        var exposed = cache.get(empty, empty.tile(CENTER));
        var joined = cache.get(filled, filled.tile(CENTER));
        assertNotSame(exposed, joined);
        assertEquals(6, exposed.sides(empty, BoardGeometry.floor(empty), Map.of()).size());
        assertEquals(5, joined.sides(filled, BoardGeometry.floor(filled), Map.of()).size());
        var space = cache.get(empty, empty.tile(CENTER.translated(0)));
        assertTrue(space.walls(empty, BoardGeometry.floor(empty)).isEmpty());
    }

    private static BoardScene scene(String backdrop, int emptyElevation, boolean solidNorth) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var pool = new BoardScene.PixelPool();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                var at = new Coords(x, y);
                boolean solid = at.equals(CENTER) || solidNorth && at.equals(CENTER.translated(0));
                var hex = new Hex(solid ? 1 : emptyElevation, solid ? "pavement:1" : backdrop, "lunar");
                var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(hex, artwork, null, pool, ignored -> null));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
