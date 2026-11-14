/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardEditorTransformsTest {
    @Test void tiltedMirroredObjectsUseOneTransformForTheirOriginAndMesh() {
        String asset = "scenery/parks/picnic-table";
        var object = new BoardDecoration("table", "prop", asset, null, .12, -.25, 36, true, 1.2,
              BoardDecoration.Placement.ground(), 0, false, 25, -40);
        Hex hex = new Hex(); hex.setDecorations(List.of(object));
        Coords at = new Coords(3, 4);
        var features = BoardFeatures.capture(hex, at, Map.of()).stream().filter(f -> f.decoration() != null).toList();
        assertEquals(1, features.size());
        var feature = features.getFirst();
        assertEquals(object.x() * BoardGeometry.TILE_WIDTH, feature.x(), .0001);
        assertEquals(object.y() * 72, feature.y(), .0001);
        assertEquals(0, feature.elevation(), .0001);
        Vector3 modelPoint = new Vector3(2, 3, 5);
        Vector3 actual = modelPoint.cpy().mul(BoardFeatures.decorationTransform(at, feature, 0));
        modelPoint.scl(1.2f);
        double[] rotated = object.rotateVector(-modelPoint.x, modelPoint.y, modelPoint.z);
        assertEquals(BoardGeometry.centerX(at) + (feature.x() + rotated[0]) * BoardGeometry.hexScale(), actual.x, .001);
        assertEquals(BoardGeometry.centerY(at) + (feature.y() + rotated[1]) * BoardGeometry.hexScale(), actual.y, .001);
        assertEquals(rotated[2] * BoardGeometry.hexScale(), actual.z, .001);
        for (boolean x : new boolean[] {false, true}) {
            for (boolean y : new boolean[] {false, true}) {
                var reflected = object.flip(x, y);
                double[] before = object.rotateVector(-2, 3, 5);
                double[] after = reflected.rotateVector(reflected.mirror() ? -2 : 2, 3, 5);
                assertEquals(x ? -before[0] : before[0], after[0], .00001);
                assertEquals(y ? -before[1] : before[1], after[1], .00001);
                assertEquals(before[2], after[2], .00001);
            }
        }
    }

    @Test void stretchActsOnTheModelsOwnAxesBeforeItsTurnAndReflectsWithTheBoard() {
        var stretch = new BoardDecoration.Stretch(29 / 36., 1, 2);
        var object = new BoardDecoration("roof", "prop", "scenery/roofs/glass-roof-wide", null, .1, -.2, 60, false, 1.2,
              BoardDecoration.Placement.ground(), 0, false, 15, -20).withStretch(stretch);
        Coords at = new Coords(3, 4);
        Vector3 modelPoint = new Vector3(2, 3, 5);
        Vector3 offset = displacement(at, object, modelPoint);
        double[] expected = object.rotateVector(2 * 1.2 * stretch.x(), 3 * 1.2 * stretch.y(), 5 * 1.2 * stretch.z());
        assertEquals(expected[0] * BoardGeometry.hexScale(), offset.x, .001);
        assertEquals(expected[1] * BoardGeometry.hexScale(), offset.y, .001);
        assertEquals(expected[2] * BoardGeometry.hexScale(), offset.z, .001);
        for (boolean x : new boolean[] {false, true}) {
            for (boolean y : new boolean[] {false, true}) {
                Vector3 reflected = displacement(at, object.flip(x, y), modelPoint);
                assertEquals(x ? -offset.x : offset.x, reflected.x, .001, "A flipped stretched model is the board's reflection");
                assertEquals(y ? -offset.y : offset.y, reflected.y, .001);
                assertEquals(offset.z, reflected.z, .001);
            }
        }
        // Paint stretches its stamp the same way: the texture still spans the stretched rectangle.
        var paint = new BoardDecoration("paint", "decal", "paint", null, 0, 0, 35, false, 2,
              BoardDecoration.Placement.ground(), 0, false, 0, 0).withStretch(new BoardDecoration.Stretch(2, .5, 1));
        double[] sample = paint.rotateVector(BoardGeometry.TILE_WIDTH * 2 * 2 * .25, 72 * 2 * .5 * -.3, 0);
        assertEquals(.75, BoardDecals.paintUv(paint).u(sample[0], sample[1]), .00001);
        assertEquals(.8, BoardDecals.paintUv(paint).v(sample[0], sample[1]), .00001);
        // Layout rows hand the same stretch to the drawn terrain-owned rows and to imported objects.
        var row = new BoardSceneryLayouts.Component(object.asset(), false, 4, -6, 0, 90, 1, 0, stretch,
              BoardDecoration.Colours.NONE);
        var layout = new BoardSceneryLayouts.Layout(List.of(row));
        assertEquals(stretch, BoardFeatures.features(layout, at, new Hex()).getFirst().stretch());
        assertEquals(stretch, layout.decorations(at, new Hex(), () -> "id", null, true).getFirst().stretch());
    }

    /** Where {@code object}'s transform puts a model point, relative to its anchor. */
    private static Vector3 displacement(Coords at, BoardDecoration object, Vector3 modelPoint) {
        Hex hex = new Hex(); hex.setDecorations(List.of(object));
        var feature = BoardFeatures.capture(hex, at, Map.of()).stream().filter(f -> f.decoration() != null).findFirst().orElseThrow();
        return modelPoint.cpy().mul(BoardFeatures.decorationTransform(at, feature, 0))
              .sub(BoardGeometry.centerX(at) + feature.x() * BoardGeometry.hexScale(),
                    BoardGeometry.centerY(at) + feature.y() * BoardGeometry.hexScale(), 0);
    }

    @Test void tiltedPaintHasMatchingUvsAndNoFootprintWhenEdgeOn() {
        var object = new BoardDecoration("paint", "decal", "paint", null, 0, 0, 35, false, 2,
              BoardDecoration.Placement.ground(), 0, false, 60, 20);
        double[] sample = object.rotateVector(BoardGeometry.TILE_WIDTH * 2 * .25, 72 * 2 * -.3, 0);
        var uv = BoardDecals.paintUv(object);
        assertEquals(.75, uv.u(sample[0], sample[1]), .00001);
        assertEquals(.8, uv.v(sample[0], sample[1]), .00001);
        assertFalse(BoardDecals.footprint(new Coords(5, 5), object, 10, 10).isEmpty());
        var edge = new BoardDecoration("paint", "decal", "paint", null, 0, 0, 0, false, 2,
              BoardDecoration.Placement.ground(), 0, false, 90, 0);
        assertTrue(BoardDecals.footprint(new Coords(5, 5), edge, 10, 10).isEmpty());
    }

    @Test void authoredDeploymentRetainsLabelsAndSharedPerimeterWithoutTerrainTint() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        List<BoardTactical.Fill> fills = new ArrayList<>(); List<BoardTactical.Label> labels = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            Coords at = new Coords(x, 0);
            var marks = BoardEditorTerrain.capture(new Hex(x * 3, "deployment_zone:1:1", ""), at);
            fills.addAll(marks.fills()); labels.addAll(marks.labels());
            tiles.add(BoardScenes.newTile(at, x * 3, -1, false, 0, BoardScene.Surface.GRASS, List.of(),
                  List.of()));
        }
        var scene = new BoardScene(0, 2, 1, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), new BoardTactical(fills, labels));
        var outlines = BoardDeploymentGeometry.editorOutlines(scene);
        assertEquals(10, outlines.walls().size(), "The shared internal edge is omitted");
        assertEquals(2, outlines.flatWalls().size());
        assertTrue(outlines.fills().stream().noneMatch(BoardDeploymentGeometry::isZone), "No zone fills reach the tint pass");
        assertEquals(labels, outlines.labels());
        assertTrue(labels.stream().anyMatch(label -> label.text().value().equals("Zone 1")));
    }
}
