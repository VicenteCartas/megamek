/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Shipped rubble rests on the rendered plateau at every model detail, without changing its rule terrain. */
class BoardRubbleFitTest {
    static final Coords CENTER = new Coords(2, 2);

    @ParameterizedTest
    @ValueSource(strings = { "light", "medium", "heavy", "hardened", "wall" })
    void rubbleAndClearedPathsKeepTheirAuthoredSizeOnLevelGround(String variant) {
        BoardSculptTest.withTransitions(true, () -> {
            for (String suffix : List.of("", "-path")) {
                BoardScene scene = scene(0, 0, variant + suffix);
                var tile = scene.tile(CENTER);
                var rubble = tile.features().getFirst();
                assertSame(rubble, BoardGroundCut.fitted(scene, tile, rubble));
                assertFalse(BoardGroundCut.digs(rubble.asset()), "A fitted footprint does not dig ground");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "light", "medium", "heavy", "hardened", "wall" })
    void allRubbleDetailsHaveGroundBeneathThemOnAnIsolatedCliff(String variant) {
        BoardSculptTest.withTransitions(true, () -> {
            for (String suffix : List.of("", "-path")) {
                BoardScene scene = scene(3, 63, variant + suffix);
                assertSupported(scene, 0);
                assertSupported(scene, 37);
            }
        });
    }

    @Test
    void aSingleExposedEdgeFitsWhileLevelNeighboursContinueToSupportRubble() {
        BoardSculptTest.withTransitions(true, () -> {
            for (int direction = 0; direction < 6; direction++) {
                assertSupported(scene(3, 1 << direction, "hardened"), 0);
            }
        });
    }

    @Test
    void iceAndBridgeDecksKeepRubbleIndependentOfTheGroundBelow() {
        BoardSculptTest.withTransitions(true, () -> {
            BoardScene scene = scene(3, 63, "hardened");
            var tile = scene.tile(CENTER);
            var rubble = tile.features().getFirst();
            var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
            assertSame(rubble, BoardGroundCut.fitted(scene, tile.withFeatures(List.of(rubble, bridge)), rubble));
            var ice = capture(new Hex(3, "water:1;ice:1", ""), CENTER).withFeatures(List.of(rubble));
            assertSame(rubble, BoardGroundCut.fitted(scene, ice, rubble));
        });
    }

    private static void assertSupported(BoardScene scene, float turn) {
        var tile = scene.tile(CENTER);
        var source = tile.features().getFirst();
        var rubble = new BoardScene.Feature(source.asset(), 0, 0, turn, 1, 1, 0, BoardScene.FeatureKind.SCENERY, 0, true);
        var fitted = BoardGroundCut.fitted(scene, tile, rubble);
        assertTrue(fitted.stretch().x() < 1, "The fixture must exercise fitting: " + source.asset());
        assertEquals(fitted.stretch().x(), fitted.stretch().y(), "The fit is uniform in the ground plane");
        assertEquals(rubble.stretch().z(), fitted.stretch().z(), "The pile keeps its height");
        assertTrue(fitted.elevation() < rubble.elevation(), "The fitted base embeds slightly");
        List<BoardSurface.Face> tops = new ArrayList<>();
        for (var support : scene.tiles()) {
            if (support.elevation() == tile.elevation() && support.coords().distance(CENTER) <= 1) {
                new BoardSurface(scene, support).faces.stream().filter(face -> face.finish() == BoardSurface.Finish.TOP)
                      .forEach(tops::add);
            }
        }
        var frame = BoardFeatures.authoredTransform(BoardGeometry.centerX(CENTER), BoardGeometry.centerY(CENTER),
              BoardGeometry.groundZ(tile), fitted);
        File root = new File(Configuration.dataDir(), "models/board");
        var levels = RigidGlb.loadLods(RigidGlb.source(root, source.asset()), root.toPath());
        for (int lod = 0; lod < levels.size(); lod++) {
            for (var shape : BoardShape.shapes(levels.get(lod)).values()) {
                for (var polygon : shape.polygons()) {
                    for (var vertex : polygon.points()) {
                        Vector3 point = new Vector3(vertex).mul(frame);
                        float height = BoardSurface.sampleHeight(tops, point.x, point.y, Float.NaN);
                        assertTrue(Float.isFinite(height), source.asset() + " lod " + lod + " turn " + turn
                              + " hangs beyond the plateau at " + point);
                    }
                }
            }
        }
    }

    /** Rubble on a plateau, with the selected adjacent hexes three levels below it. */
    static BoardScene scene(int level, int lowerExits, String variant) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(x, y);
                boolean low = lowerExits == 63 && !at.equals(CENTER);
                for (int direction = 0; direction < 6; direction++) {
                    low |= (lowerExits & (1 << direction)) != 0 && at.equals(CENTER.translated(direction));
                }
                Hex hex = new Hex(low ? 0 : level, at.equals(CENTER) ? "cliff_top:1:" + lowerExits : "", "");
                var tile = capture(hex, at);
                if (at.equals(CENTER)) {
                    tile = tile.withFeatures(List.of(new BoardScene.Feature("scenery/rubble/" + variant,
                          0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.SCENERY, 0, true)));
                }
                tiles.add(tile);
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile capture(Hex hex, Coords at) {
        var artwork = new BoardArtwork.HexImage(at, null, null, List.of(), Map.of(), null);
        return BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(), neighbour -> null);
    }
}
