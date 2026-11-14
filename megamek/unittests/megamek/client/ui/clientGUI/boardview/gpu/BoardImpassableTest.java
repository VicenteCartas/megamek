/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardImpassableTest {
    @Test
    void flagPresencePreservesTheUnderlyingTerrainIncludingZeroAndUnusualLevels() {
        Coords coords = new Coords(2, 2);
        for (int type : new int[] { 0, Terrains.PAVEMENT, Terrains.SAND, Terrains.SNOW, Terrains.WATER,
              Terrains.WOODS, Terrains.ROUGH, Terrains.SWAMP, Terrains.MUD, Terrains.RUBBLE, Terrains.FORTIFIED }) {
            Hex hex = new Hex(3);
            if (type != 0) { hex.addTerrain(new Terrain(type, 1)); }
            var plain = capture(hex, coords);
            for (int level : new int[] { 0, 1, 5, 54 }) {
                hex.addTerrain(new Terrain(Terrains.IMPASSABLE, level));
                var restricted = capture(hex, coords);
                assertTrue(restricted.impassable(), "Presence is authoritative, even for impassable:0");
                assertFalse(plain.impassable(), "The previously published snapshot remains immutable");
                assertTrue(plain.sameGeometry(restricted), "The restriction adds no physical obstacle or material: " + type);
                hex.removeTerrain(Terrains.IMPASSABLE);
                assertTrue(restricted.impassable());
                assertFalse(capture(hex, coords).impassable());
            }
        }
    }

    @Test
    void gameplayAndPreviewCapturePreserveTheFlagThroughRepaintingAndRemoval() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                var preview = new GpuMapSource(fixture.game, null, null);
                var preferences = GUIPreferences.getInstance();
                boolean showTerrain = preferences.getShowMapHexPopup();
                try {
                    preferences.setShowMapHexPopup(true);
                    Coords coords = new Coords(0, 16);
                    Hex hex = new Hex(2);
                    hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
                    fixture.game.getBoard().setHex(coords, hex);
                    fixture.source.refresh();
                    var plain = fixture.source.takeFrame().scene().tile(coords);
                    Hex blocked = hex.duplicate();
                    blocked.addTerrain(new Terrain(Terrains.IMPASSABLE, 0));
                    fixture.game.getBoard().setHex(coords, blocked);
                    fixture.source.setHover(coords);
                    preview.setHover(coords);
                    for (Rectangle area : List.of(new Rectangle(0, 15, 2, 2), new Rectangle(4, 4, 2, 2),
                          new Rectangle(0, 15, 2, 2))) {
                        fixture.source.setVisibleArea(area);
                        fixture.source.refresh();
                        preview.refresh();
                        var marked = fixture.source.takeFrame().scene().tile(coords);
                        assertTrue(marked.impassable());
                        assertTrue(plain.sameGeometry(marked));
                        assertTrue(preview.takeFrame().scene().tile(coords).impassable());
                        assertTrue(preview.takeFrame().tooltip().contains("Impassable"));
                        assertTrue(fixture.source.takeFrame().tooltip().contains("Impassable"));
                    }
                    fixture.game.getBoard().setHex(coords, hex);
                    fixture.source.refresh();
                    preview.refresh();
                    assertFalse(fixture.source.takeFrame().scene().tile(coords).impassable());
                    assertFalse(preview.takeFrame().scene().tile(coords).impassable());
                } finally {
                    preview.close();
                    preferences.setShowMapHexPopup(showTerrain);
                }
            });
        }
    }

    @Test
    void eachRestrictedHexHasItsOwnFloatingMarkingAndHoverOnlyChangesInk() {
        for (int x : new int[] { 2, 3 }) {
            Coords a = new Coords(x, 3);
            for (int direction = 0; direction < 6; direction++) {
                Coords b = a.translated(direction);
                var scene = scene(Set.of(a, b));
                var normal = BoardImpassable.fills(scene, null, false);
                var hover = BoardImpassable.fills(scene, a, false);
                var planning = BoardImpassable.fills(scene, null, true);
                assertEquals(Set.of(a, b), normal.stream()
                      .map(fill -> BoardTacticalGeometry.anchorCoords(scene, fill.planeAnchor()))
                      .collect(java.util.stream.Collectors.toSet()));
                assertEquals(normal.stream().map(BoardTactical.Fill::contours).toList(),
                      hover.stream().map(BoardTactical.Fill::contours).toList(), "Hover changes ink, never marker shape");
                assertEquals(normal.stream().map(BoardTactical.Fill::contours).toList(),
                      planning.stream().map(BoardTactical.Fill::contours).toList());
                for (int i = 0; i < normal.size(); i++) {
                    assertTrue((planning.get(i).argb() >>> 24) > (normal.get(i).argb() >>> 24));
                    if (BoardTacticalGeometry.anchorCoords(scene, normal.get(i).planeAnchor()).equals(b)) {
                        assertEquals(normal.get(i), hover.get(i), "Only the hovered hex is emphasized");
                    }
                }
                assertEquals(normal, BoardImpassable.fills(scene, new Coords(0, 0), false));
                assertTrue(normal.stream().allMatch(fill -> fill.planeAnchor() != null));
            }
        }
    }

    @Test
    void hatchingAndOutlinesStayInsideRestrictedHexesAndLeavePlayableHoles() {
        Coords hole = new Coords(3, 3);
        Set<Coords> marked = new HashSet<>();
        for (int direction = 0; direction < 6; direction++) { marked.add(hole.translated(direction)); }
        marked.add(new Coords(0, 0));
        var scene = scene(marked);
        var fills = BoardImpassable.fills(scene, null, false);
        Area legalArea = new Area();
        for (Coords coords : marked) {
            var centre = BoardTacticalGeometry.centre(coords);
            legalArea.add(new Area(AffineTransform.getTranslateInstance(centre.x(), centre.y())
                  .createTransformedShape(BoardTacticalGeometry.insetHexagon(0))));
        }
        for (var fill : fills) {
            Area spill = new Area(fill.shape());
            spill.subtract(legalArea);
            double spillArea = BoardTacticalGeometry.flat(spill, -1).stream().mapToDouble(triangle ->
                  Math.abs((triangle.b().x - triangle.a().x) * (triangle.c().y - triangle.a().y)
                        - (triangle.c().x - triangle.a().x) * (triangle.b().y - triangle.a().y)) / 2).sum();
            assertTrue(spillArea < .01, "Only float-rounding slivers may leave the region; pixel area: " + spillArea);
            assertFalse(fill.shape().contains(3 * .75 * BoardGeometry.TILE_WIDTH + BoardGeometry.TILE_WIDTH / 2, 3 * 72 + 72));
        }
        assertTrue(BoardImpassable.fills(scene(Set.of()), null, false).isEmpty());
    }

    @Test
    void bordersAndStripesUseTheSharedFloatingPlaneAndNeverClipAgainstSlopes() {
        Coords coords = new Coords(0, 0);
        var scene = scene(Set.of(coords));
        var face = new BoardSurface.Face(new Vector3(0, 0, 0), new Vector3(0, -72, 216),
              new Vector3(84, 0, 0), BoardSurface.Finish.TOP);
        var surface = new BoardTacticalGeometry.Surface(List.of(face), List.of(face), List.of(face), List.of(),
              List.of(face), List.of());
        List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
        var clipper = new BoardTacticalGeometry.Clipper();
        for (var fill : BoardImpassable.fills(scene, null, false)) {
            int[] lookups = { 0 };
            BoardTacticalGeometry.drape(scene, fill, 10000, triangles::add, owner -> {
                assertEquals(coords, owner, "Floating markers only query their owning hex");
                lookups[0]++;
                return surface;
            }, clipper);
            assertEquals(0, lookups[0], "The flat plane is independent of the finished terrain");
        }
        assertFalse(triangles.isEmpty());
        for (var triangle : triangles) {
            for (Vector3 vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                assertEquals(BoardTacticalGeometry.HEX_PLANE_CLEARANCE, vertex.z, .001);
            }
        }
    }

    static BoardScene scene(Set<Coords> marked) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                Hex hex = new Hex(0);
                if (marked.contains(coords)) { hex.addTerrain(new Terrain(Terrains.IMPASSABLE, 0)); }
                tiles.add(capture(hex, coords));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile capture(Hex hex, Coords coords) {
        var artwork = new BoardArtwork.HexImage(coords, List.of(), Map.of(), null);
        return BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(), neighbour -> null);
    }
}
