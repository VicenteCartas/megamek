/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardMultiHexBuildingTest {
    private static final Coords CENTRE = new Coords(4, 4);
    private static final String DOME = BoardMultiHexBuilding.Design.DOME.asset;

    @BeforeAll
    static void natives() {
        // A board material's emission map is read to tell a uniform glow (GpuMaterialMaps.emission).
        GdxNativesLoader.load();
    }

    @Test
    void neighbouringDomeSharesMeetOnTheirCommonHexside() {
        Map<Coords, Integer> heights = flower(3, 2);
        BoardScene scene = scene(heights, DOME, Map.of(), Set.of());
        Map<Coords, List<Vector3>> glass = new HashMap<>(), frame = new HashMap<>();
        for (Coords at : heights.keySet()) {
            var layout = layout(scene, at);
            assertEquals(1, layout.domes().size(), "Every hex of a seven-hex dome lies under it: " + at);
            glass.put(at, world(at, BoardMultiHexBuilding.glass(layout)));
            frame.put(at, world(at, BoardMultiHexBuilding.model(layout, 0)));
        }
        for (int direction = 0; direction < 6; direction++) {
            Coords ring = CENTRE.translated(direction);
            for (var shares : List.of(glass, frame)) {
                assertMeet(shares.get(CENTRE), shares.get(ring), CENTRE, direction);
                assertMeet(shares.get(ring), shares.get(ring.translated((direction + 2) % 6)), ring, (direction + 2) % 6);
            }
        }
    }

    @Test
    void everyHexOfABuildingFitsTheSameDome() {
        Map<Coords, Integer> heights = flower(3, 2);
        List<BoardMultiHexBuilding.Member> members = new ArrayList<>();
        heights.forEach((at, height) -> members.add(new BoardMultiHexBuilding.Member(at, 0, height, false)));
        var fits = BoardMultiHexBuilding.fits(members);
        assertEquals(1, fits.size(), "One peak, one dome");
        var fit = fits.getFirst();
        Collections.reverse(members);
        assertEquals(fits, BoardMultiHexBuilding.fits(members), "The fit must not depend on which hex asks");
        assertEquals(HexTile.centerX(CENTRE, HexTile.MODEL_WIDTH), fit.x(), 1e-3f);
        assertEquals(HexTile.centerY(CENTRE, HexTile.HEIGHT), fit.y(), 1e-3f);
        // A flower's outline comes nearest its centre at the six notches between its petals, two corners out: the
        // podium's corners lie there, the dome inside its ring.
        assertEquals(HexTile.MODEL_WIDTH - BoardMultiHexBuilding.DOME_MARGIN, fit.podium(), .01f);
        assertTrue(fit.radius() < fit.podium() * Math.cos(Math.PI / 12) - 8);
        assertEquals(0, fit.frequency() % 2, "An even frequency puts the dome's foot on a vertex ring");
    }

    @Test
    void aCollapsedHexLeavesTheRestOfTheDomeAsItWas() {
        Map<Coords, Integer> heights = flower(3, 2);
        BoardScene whole = scene(heights, DOME, Map.of(), Set.of());
        Coords fallen = CENTRE.translated(2);
        // A ring hex: the peak still stands. (A fallen peak reshapes its dome: the standing heights decide.)
        for (Coords collapsed : List.of(fallen)) {
            BoardScene broken = scene(heights, DOME, Map.of(), Set.of(collapsed));
            for (Coords at : heights.keySet()) {
                if (at.equals(collapsed)) { continue; }
                assertEquals(layout(whole, at).domes(), layout(broken, at).domes(),
                      "Its standing neighbours still name the fallen hex, so the dome keeps its fit: " + at);
            }
        }
    }

    @Test
    void anAnnexIsAWingOfItsDomesRingBuilding() {
        // A flower with a two-level hex beyond its northern petal: outside the podium, an annex at its own height.
        Map<Coords, Integer> heights = flower(3, 2);
        Coords petal = CENTRE.translated(0), annex = petal.translated(0);
        heights.put(annex, 2);
        BoardScene scene = scene(heights, DOME, Map.of(), Set.of());
        var block = layout(scene, annex);
        assertTrue(block.annex());
        var under = layout(scene, petal);
        assertEquals(under.top(), block.reach().get(3), 1e-3f, "Towards the petal the annex's plinth joins the petal's");
        var modules = under.domes().getFirst().modules();
        assertEquals(4, modules.size(), "One module, the annex's");
        float top = 2 * GpuBuilding.LEVEL_HEIGHT;
        assertEquals(top, under.top() + modules.get(3), 1e-3f, "The module stands at the annex's own height");
        // Its roof at that height covers the annex's centre, where units stand (the annex's own frame).
        var roof = vertices(BoardMultiHexBuilding.model(block, 0), BoardMultiHexBuilding.ROOF).stream()
              .filter(vertex -> Math.abs(vertex[2] - top) < .01f).toList();
        assertTrue(roof.stream().anyMatch(vertex -> vertex[0] < 0) && roof.stream().anyMatch(vertex -> vertex[0] > 0)
              && roof.stream().anyMatch(vertex -> vertex[1] < 0) && roof.stream().anyMatch(vertex -> vertex[1] > 0));
        // It is a wing of the ring building: over the petal, the petal's share draws it on the annex's axis from inside
        // the podium out towards the annex.
        var parts = world(petal, BoardMultiHexBuilding.model(under, 0));
        float x = HexTile.centerX(annex, HexTile.MODEL_WIDTH), centreY = HexTile.centerY(CENTRE, HexTile.HEIGHT);
        float podium = under.domes().getFirst().podium();
        assertTrue(parts.stream().anyMatch(vertex -> Math.abs(vertex.x - x) < 8 && vertex.z > under.top() + 1
              && Math.abs(vertex.y - centreY) < podium - 5), "It starts inside the podium");
        assertTrue(parts.stream().anyMatch(vertex -> Math.abs(vertex.x - x) < 8 && vertex.z > under.top() + 1
              && Math.abs(vertex.y - centreY) > podium + 5), "It runs on out towards the annex");
    }

    @Test
    void theGlassDrawsApartFromTheRestOfTheShare() {
        var layout = layout(scene(flower(3, 2), DOME, Map.of(), Set.of()), CENTRE);
        assertTrue(GpuMaterialMaps.board(BoardMultiHexBuilding.GLASS).blended(), "The dome glass's .tres is transparent");
        assertTrue(vertices(BoardMultiHexBuilding.model(layout, 0), BoardMultiHexBuilding.GLASS).isEmpty());
        var glass = BoardMultiHexBuilding.glass(layout);
        assertFalse(vertices(glass, BoardMultiHexBuilding.GLASS).isEmpty());
        assertEquals(4, glass.blends.get(BoardMultiHexBuilding.GLASS), "It draws blended with premultiplied alpha, as its .tres says");
    }

    /** The centre hex {@code middle} levels high and its six neighbours {@code ring} high. */
    private static Map<Coords, Integer> flower(int middle, int ring) {
        Map<Coords, Integer> result = new HashMap<>(Map.of(CENTRE, middle));
        for (int direction = 0; direction < 6; direction++) { result.put(CENTRE.translated(direction), ring); }
        return result;
    }

    private static BoardMultiHexBuilding.Layout layout(BoardScene scene, Coords at) {
        var tile = scene.tile(at);
        return BoardMultiHexBuilding.layout(scene, tile, tile.features().getFirst());
    }

    /**
     * A building of {@code asset} over {@code heights}, every hex connecting to its neighbours in it; {@code fallen}
     * hexes have collapsed (no structure) while the others still name them; {@code rails}: ground rail sides by hex.
     */
    private static BoardScene scene(Map<Coords, Integer> heights, String asset, Map<Coords, Integer> rails,
          Set<Coords> fallen) {
        return scene(heights, asset, rails, fallen, Set.of());
    }

    /** {@code apart}: pairs of neighbouring hexes of the building that do not connect to each other. */
    private static BoardScene scene(Map<Coords, Integer> heights, String asset, Map<Coords, Integer> rails,
          Set<Coords> fallen, Set<Set<Coords>> apart) {
        return BoardSurfaceBlendTest.scene(coords -> {
            var base = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.LUNAR, 0, -1, 0);
            int exits = 0;
            for (int direction = 0; direction < 6; direction++) {
                Coords next = coords.translated(direction);
                if (heights.containsKey(next) && !apart.contains(Set.of(coords, next))) { exits |= 1 << direction; }
            }
            boolean standing = heights.containsKey(coords) && !fallen.contains(coords);
            var cover = standing ? new BoardScene.Cover(0, false, 3, exits, 90, 90, heights.get(coords)) : BoardScene.Cover.NONE;
            var features = standing ? List.of(new BoardScene.Feature(asset, 0, 0, 0, 1, heights.get(coords), 0,
                  BoardScene.FeatureKind.BUILDING)) : List.<BoardScene.Feature>of();
            int rail = 0;
            for (var entry : rails.entrySet()) {
                // A corridor hex's rail runs towards the building beside it.
                if (entry.getKey().equals(coords)) {
                    for (int direction = 0; direction < 6; direction++) {
                        if (heights.containsKey(coords.translated(direction))) { rail |= 1 << direction; }
                    }
                }
            }
            return new BoardScene.Tile(coords, 0, -1, false, 0, base.surface(), null, null, features, List.of(),
                  BoardLiquid.NONE, null, true, base.road(), base.fireSmoke(), base.biome(), false, false, 0, false,
                  base.groundCover(), false, null, null, Map.of(), cover, false, BoardRoad.Kind.NONE, 0, false, rail);
        });
    }

    /** The positions of {@code data}'s vertices on the board (model units), from its hex's local frame. */
    private static List<Vector3> world(Coords at, RigidGlb.Data data) {
        float x = HexTile.centerX(at, HexTile.MODEL_WIDTH), y = HexTile.centerY(at, HexTile.HEIGHT);
        List<Vector3> result = new ArrayList<>();
        for (var mesh : data.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                result.add(new Vector3(mesh.vertices[i] + x, mesh.vertices[i + 1] + y, mesh.vertices[i + 2]));
            }
        }
        return result;
    }

    /** The vertices (all their attributes) of {@code data}'s parts in board material {@code material}. */
    private static List<float[]> vertices(RigidGlb.Data data, String material) {
        List<float[]> result = new ArrayList<>();
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                if (!part.id.equals(material)) { continue; }
                for (short index : part.indices) {
                    int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                    float[] vertex = new float[RigidGlb.STRIDE];
                    System.arraycopy(mesh.vertices, at, vertex, 0, RigidGlb.STRIDE);
                    result.add(vertex);
                }
            }
        }
        return result;
    }

    /** Every vertex of {@code a} on its hexside towards {@code direction} has a twin of {@code b}'s there. */
    private static void assertMeet(List<Vector3> a, List<Vector3> b, Coords at, int direction) {
        float cx = HexTile.centerX(at, HexTile.MODEL_WIDTH), cy = HexTile.centerY(at, HexTile.HEIGHT);
        double angle = Math.toRadians(90 - 60 * direction);
        float nx = (float) Math.cos(angle), ny = (float) Math.sin(angle);
        int shared = 0;
        for (Vector3 vertex : a) {
            float across = (vertex.x - cx) * nx + (vertex.y - cy) * ny;
            if (Math.abs(across - HexTile.HEIGHT / 2f) > .01f) { continue; }
            shared++;
            assertTrue(b.stream().anyMatch(other -> other.dst(vertex) < .02f),
                  "A share's edge vertex has no twin across hexside " + direction + " of " + at + ": " + vertex);
        }
        assertTrue(shared > 0, "Shares meet across hexside " + direction + " of " + at);
    }
}
