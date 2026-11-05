/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Test;

class BoardSurfaceCacheTest {
    private static final Coords CENTER = new Coords(3, 3);

    @Test
    void graphicsModeSwitchesTheCachedGroundUsedByUnitContacts() {
        var lower = CENTER;
        var upper = lower.translated(0);
        var bank = lower.translated(1);
        var scene = BoardScenes.scene(at -> BoardScenes.tile(at,
              at.equals(lower) || at.equals(upper) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE,
              at.equals(lower) ? 1 : at.equals(upper) ? 8 : 0,
              at.equals(lower) ? 0 : at.equals(upper) ? 3 : 1, BoardScene.Surface.GRASS));
        var tile = scene.tile(bank);
        var cache = new BoardSurface.Cache();
        var enhanced = cache.get(scene, tile);
        var point = enhanced.groundFaces().stream()
              .map(face -> new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3))
              .filter(p -> Math.abs(p.z - BoardGeometry.surfaceZ(tile)) > .5f
                    && BoardGeometry.contains(bank, p.x, p.y)).findFirst().orElseThrow();
        float sculpted = UnitLandingSupports.terrain(scene, point.x, point.y, cache);
        cache.columns(true);
        var classic = cache.get(scene, tile);
        assertNotSame(enhanced, classic, "A mode switch must not retain the previous height field");
        assertEquals(BoardGeometry.surfaceZ(tile), UnitLandingSupports.terrain(scene, point.x, point.y, cache), .001f,
              "Units stand on the full sharp Classic roof beside the road");
        cache.columns(true);
        assertSame(classic, cache.get(scene, tile), "An unchanged mode retains its cached geometry");
        cache.columns(false);
        assertNotSame(classic, cache.get(scene, tile));
        assertEquals(sculpted, UnitLandingSupports.terrain(scene, point.x, point.y, cache), .001f,
              "Switching back restores Enhanced ground contact");
    }

    @Test
    void artworkAndTacticalSnapshotsReuseTerrainAndItsCachedWalls() {
        BoardScene scene = scene(0, 7, 7, true);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface first = cache.get(scene, scene.tile(CENTER));
        var walls = first.walls(scene, BoardGeometry.floor(scene));
        BoardScene.Pixels paint = pixels(0xff123456);
        List<BoardScene.Tile> painted = scene.tiles().stream().map(t -> new BoardScene.Tile(t.coords(), t.elevation(),
              t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), paint, paint,
              t.features(), t.text(), t.liquid(), paint, t.detailedGround())).toList();
        BoardScene next = new BoardScene(0, 7, 7, painted, List.of(), List.of(), -1, "", List.of());

        assertNotSame(scene.tiles(), next.tiles());
        assertSame(first, cache.get(next, next.tile(CENTER)));
        assertSame(walls, cache.get(next, next.tile(CENTER)).walls(next, BoardGeometry.floor(next)));
        assertSame(first, cache.get(scene, scene.tile(CENTER)), "Returning to an earlier snapshot still reuses its shape");
    }

    @Test
    void neighborMaterialEditUpdatesTheSharedLandformEdge() {
        BoardScene scene = scene(0, 7, 7, true);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface first = cache.get(scene, scene.tile(CENTER));
        Vector3 edge = sharedEdge();
        assertEquals(0, first.height(edge.x, edge.y), .003f, "Matching neighbors share the flat playing level");
        BoardScene.Tile neighbor = scene.tile(CENTER.translated(1));
        BoardScene edited = replace(scene, shape(neighbor, 0, 0, BoardScene.Surface.SAND));

        BoardSurface changed = cache.get(edited, edited.tile(CENTER));
        assertNotSame(first, changed);
        // A material edit invalidates derived geometry while both flat tops keep their shared playing level.
        BoardSurface other = cache.get(edited, edited.tile(CENTER.translated(1)));
        assertEquals(changed.height(edge.x, edge.y), other.height(edge.x, edge.y), .003f,
              "A material boundary keeps one continuous surface");
    }

    @Test
    void changingAnAdjacentNaturalSpanToBuiltRestoresTheBanksCliffRim() {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.SAND, false, false);
        var span = BoardScenes.CENTER;
        var bank = span.translated(0);
        var cache = new BoardSurface.Cache();
        var supported = cache.get(scene, scene.tile(bank));
        var retyped = BoardScenes.replace(scene, BridgeScenes.appearance(scene.tile(span),
              Map.of("bridge", HexAppearance.BUILT_BRIDGE)));
        assertEquals(scene.tile(span).features(), retyped.tile(span).features(),
              "Changing construction family does not change the bridge terrain");
        assertSame(scene.tile(bank), retyped.tile(bank), "Only the neighboring span was edited");
        var cliff = cache.get(retyped, retyped.tile(bank));
        assertNotSame(supported, cliff, "Natural approach normals must not survive replacing the rock span with a slab");
        assertSame(cliff, cache.get(retyped, retyped.tile(bank)));
    }

    @Test
    void secondRingRoadApproachInvalidatesAnOtherwiseUnchangedNeighborJoin() {
        BoardScene scene = scene(0, 7, 7, true);
        Coords neighbor = CENTER.translated(1);
        Coords road = neighbor.translated(1);
        scene = replace(scene, shape(scene.tile(road), 1, 0, BoardScene.Surface.GRASS));
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface first = cache.get(scene, scene.tile(CENTER));
        Vector3 edge = sharedEdge();
        assertEquals(0, BoardSurface.ramps(scene, scene.tile(neighbor)));
        assertEquals(0, first.height(edge.x, edge.y), .003f);

        BoardScene edited = replace(scene, shape(scene.tile(road), 1, 1 << 4, BoardScene.Surface.GRASS));
        assertSame(scene.tile(CENTER), edited.tile(CENTER));
        assertSame(scene.tile(neighbor), edited.tile(neighbor));
        // A road ending at bare ground carries its whole ramp; the neighbour keeps its shape (BoardRoadGrade.edgeLevel).
        assertTrue(BoardSurface.ramps(edited, edited.tile(road)) != 0);
        assertEquals(0, BoardSurface.ramps(edited, edited.tile(neighbor)));
        BoardSurface changed = cache.get(edited, edited.tile(CENTER));
        assertNotSame(first, changed, "A road changing in the second ring rebuilds the hex (BoardSurface.geometryKey)");
        // Ground a road only approaches keeps its natural rim, so the join with it keeps its height.
        assertEquals(first.height(edge.x, edge.y), changed.height(edge.x, edge.y), .003f);
    }

    @Test
    void aSecondRingExitChangeRebuildsTheTerminalEvenWhenTheRampMaskStaysTheSame() {
        BoardScene scene = scene(0, 7, 7, true);
        Coords neighbor = CENTER.translated(0), distant = neighbor.translated(0);
        scene = replace(scene, shape(scene.tile(CENTER), 3, 1, BoardScene.Surface.GRASS));
        scene = replace(scene, shape(scene.tile(neighbor), 2, 9, BoardScene.Surface.GRASS));
        scene = replace(scene, shape(scene.tile(distant), 1, 8, BoardScene.Surface.GRASS));
        var cache = new BoardSurface.Cache();
        var first = cache.get(scene, scene.tile(CENTER));

        // The one-sided approach still climbs, but no longer belongs to a continuous reciprocal route.
        var edited = replace(scene, shape(scene.tile(distant), 1, 4, BoardScene.Surface.GRASS));
        assertSame(scene.tile(CENTER), edited.tile(CENTER));
        assertSame(scene.tile(neighbor), edited.tile(neighbor));
        assertEquals(BoardSurface.ramps(scene, scene.tile(neighbor)), BoardSurface.ramps(edited, edited.tile(neighbor)));
        // The terminal's grade reads only its own edge (BoardRoadGrade.edgeLevel); the cache still rebuilds it.
        var changed = cache.get(edited, edited.tile(CENTER));
        assertNotSame(first, changed);
        assertSame(changed, cache.get(edited, edited.tile(CENTER)));
    }

    @Test
    void ownElevationChangesSupportWhileDistantEditsKeepItCached() {
        BoardScene scene = scene(0, 15, 15, true);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface first = cache.get(scene, scene.tile(CENTER));
        Coords distant = new Coords(14, 14);
        assertTrue(CENTER.distance(distant) > BoardSurface.SHORE_RINGS);
        BoardScene remoteEdit = replace(scene, shape(scene.tile(distant), 4, 0, BoardScene.Surface.SAND));
        assertSame(first, cache.get(remoteEdit, remoteEdit.tile(CENTER)));

        BoardScene localEdit = replace(remoteEdit, shape(remoteEdit.tile(CENTER), 2, 0, BoardScene.Surface.GRASS));
        BoardSurface changed = cache.get(localEdit, localEdit.tile(CENTER));
        Vector3 center = BoardGeometry.center(CENTER, 2);
        assertNotSame(first, changed);
        assertEquals(center.z, changed.height(center.x, center.y), .003f);
    }

    @Test
    void boardIdentityAndDimensionsInvalidateDerivedSurfaces() {
        BoardScene scene = scene(0, 7, 7, false);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface first = cache.get(scene, scene.tile(CENTER));
        BoardScene other = new BoardScene(1, 7, 7, scene.tiles(), List.of(), List.of(), -1, "", List.of());
        BoardSurface switched = cache.get(other, other.tile(CENTER));
        assertNotSame(first, switched);
        BoardScene resized = scene(1, 8, 7, false);
        assertNotSame(switched, cache.get(resized, resized.tile(CENTER)));
    }

    @Test
    void boundedCacheRetainsRecentlyUsedTerrain() {
        BoardScene scene = scene(0, 17, 17, false);
        BoardSurface.Cache cache = new BoardSurface.Cache();
        BoardSurface first = cache.get(scene, scene.tiles().get(0));
        BoardSurface second = cache.get(scene, scene.tiles().get(1));
        for (int i = 2; i < 256; i++) { cache.get(scene, scene.tiles().get(i)); }
        assertSame(first, cache.get(scene, scene.tiles().get(0)));
        cache.get(scene, scene.tiles().get(256));
        assertSame(first, cache.get(scene, scene.tiles().get(0)));
        assertNotSame(second, cache.get(scene, scene.tiles().get(1)), "Only the least recently used entry is evicted");
    }

    private static Vector3 sharedEdge() {
        return BoardGeometry.corner(CENTER, 0, 0).lerp(BoardGeometry.corner(CENTER, 0, 1), .5f);
    }

    /** Road exits make a road only with a kind of road (BoardRoad.rendered): a paved one. */
    private static BoardScene.Tile shape(BoardScene.Tile t, int elevation, int roadExits, BoardScene.Surface surface) {
        return new BoardScene.Tile(t.coords(), elevation, t.waterDepth(), t.frozen(), roadExits, surface, t.decals(),
              t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), roadExits == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED);
    }

    private static BoardScene replace(BoardScene scene, BoardScene.Tile tile) {
        List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
        tiles.set(tile.coords().getX() * scene.height() + tile.coords().getY(), tile);
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene scene(int boardId, int width, int height, boolean detailed) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, detailed));
            }
        }
        return new BoardScene(boardId, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels pixels(int color) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        return new BoardScene.Pixels(image);
    }
}
