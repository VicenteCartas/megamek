/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
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
    void annexBevelsLayPanelsAlongEachEdgeWithoutStretchingOrHexSeams() {
        for (int direction = 0; direction < 6; direction++) {
            var heights = flower(3, 2);
            Coords petal = CENTRE.translated(direction), annex = petal.translated(direction);
            heights.put(annex, 2);
            var scene = scene(heights, DOME, Map.of(), Set.of());
            for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
                var caps = vertices(BoardMultiHexBuilding.model(layout(scene, annex), lod), BoardMultiHexBuilding.FRAME);
                int checked = 0;
                for (int i = 0; i < caps.size(); i += 3) {
                    if (!bevel(caps.get(i))) { continue; }
                    var axes = textureAxes(caps.get(i), caps.get(i + 1), caps.get(i + 2));
                    if (axes == null) { continue; }
                    assertEquals(0, axes[0].z, .002f, "Panel rows follow the horizontal edge, including cut corners");
                    assertEquals(0, axes[0].cpy().nor().dot(axes[1].cpy().nor()), .002f,
                          "Unwrapping the bevel must not shear its panels");
                    assertEquals(axes[0].len(), axes[1].len(), .01f, "Equal texel density along and across the bevel");
                    checked++;
                }
                assertTrue(checked > 0, "The annex must include bevel faces");

                var neighbour = vertices(BoardMultiHexBuilding.model(layout(scene, petal), lod), BoardMultiHexBuilding.FRAME);
                float dx = HexTile.centerX(annex, HexTile.MODEL_WIDTH) - HexTile.centerX(petal, HexTile.MODEL_WIDTH);
                float dy = HexTile.centerY(annex, HexTile.HEIGHT) - HexTile.centerY(petal, HexTile.HEIGHT);
                int shared = 0;
                for (float[] a : caps) {
                    if (!bevel(a)) { continue; }
                    for (float[] b : neighbour) {
                        if (Math.abs(a[0] + dx - b[0]) > .002f || Math.abs(a[1] + dy - b[1]) > .002f
                              || Math.abs(a[2] - b[2]) > .002f || Math.abs(a[3] - b[3]) > .002f
                              || Math.abs(a[4] - b[4]) > .002f || Math.abs(a[5] - b[5]) > .002f) { continue; }
                        assertEquals(a[10], b[10], .002f, "A bevel's U must continue across the hex cut");
                        assertEquals(a[11], b[11], .002f, "A bevel's V must continue across the hex cut");
                        shared++;
                    }
                }
                assertTrue(shared > 0, "Check a bevel crossing between the annex and its dome's hex");
            }
        }
    }

    @Test
    void podiumRibsAndRimsRunTheirMetalGrainAlongThePiece() {
        var heights = flower(3, 2);
        var scene = scene(heights, DOME, Map.of(), Set.of());
        int ribs = 0, rims = 0;
        for (Coords at : heights.keySet()) {
            var layout = layout(scene, at);
            var dome = layout.domes().getFirst();
            var metal = vertices(BoardMultiHexBuilding.model(layout, 0), BoardMultiHexBuilding.PIER);
            for (int i = 0; i < metal.size(); i += 3) {
                float[] a = metal.get(i), b = metal.get(i + 1), c = metal.get(i + 2);
                if (a[5] < .999f || b[5] < .999f || c[5] < .999f) { continue; }
                boolean rib = Math.abs(a[2] - dome.ring() - .55f) < .002f;
                boolean rim = Math.abs(a[2] - dome.ring() - .9f) < .002f;
                if (!rib && !rim) { continue; }
                var axes = textureAxes(a, b, c);
                if (axes == null) { continue; }
                double angle = Math.atan2((a[1] + b[1] + c[1]) / 3 - dome.y(),
                      (a[0] + b[0] + c[0]) / 3 - dome.x());
                double side = Math.PI / 6;
                angle = rib ? Math.round(angle / side) * side : (Math.floor(angle / side) + .5) * side + Math.PI / 2;
                var along = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
                // The steel texture's ribs run in V: rotation or clipping must not turn them across the strip.
                assertEquals(1, Math.abs(axes[1].cpy().nor().dot(along)), .002f);
                if (rib) { ribs++; } else { rims++; }
            }
        }
        assertTrue(ribs > 0 && rims > 0, "Check both radial ribs and the outer rim");
    }

    @Test
    void slopedPassageRoofKeepsSquarePanels() throws Exception {
        for (float height : new float[] { 15, 30, 45 }) {
            var mesh = passage(new float[] { 0, 0, 1, 0, 6, height }, new float[] { 20, 0, 1, 0, 6, 20 }, 0);
            var roof = vertices(mesh.data("passage", false), BoardMultiHexBuilding.ROOF);
            assertFalse(roof.isEmpty());
            for (int i = 0; i < roof.size(); i += 3) {
                var axes = textureAxes(roof.get(i), roof.get(i + 1), roof.get(i + 2));
                if (axes == null) { continue; }
                assertEquals(axes[0].len(), axes[1].len(), .002f, "The slope must not stretch square roof panels");
                assertEquals(0, axes[0].cpy().nor().dot(axes[1].cpy().nor()), .002f);
            }
        }
    }

    @Test
    void tightPassagesKeepUsableWidthThroughTheBend() throws Exception {
        for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
            for (float angle : new float[] { -60, -45, 45, 60 }) {
                float ux = (float) Math.cos(Math.toRadians(angle)), uy = (float) Math.sin(Math.toRadians(angle));
                var mesh = passage(new float[] { 0, 0, ux, uy, 6, 27 }, new float[] { 18, 0, 1, 0, 6, 20 }, lod);
                var data = mesh.data("bending passage", false);
                var tube = vertices(data, BoardMultiHexBuilding.FRAME);
                tube.addAll(vertices(data, BoardMultiHexBuilding.PIER));
                assertFalse(tube.isEmpty());
                for (float[] v : tube) {
                    for (float value : v) { assertTrue(Float.isFinite(value)); }
                    assertEquals(1, new Vector3(v[3], v[4], v[5]).len(), .002f, "Drawn bends need valid surface normals");
                }
                // Cross-sections beyond the entire angled opening (which extends as far as x = 9.1).
                // The old inner-wall clamp
                // collapsed these sections when the centreline radius became smaller than the tunnel's width.
                for (float x : new float[] { 10, 12, 14, 16 }) {
                    float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                    for (int i = 0; i < tube.size(); i += 3) {
                        for (int edge = 0; edge < 3; edge++) {
                            float[] a = tube.get(i + edge), b = tube.get(i + (edge + 1) % 3);
                            if (Math.abs(a[2]) > .001f || Math.abs(b[2]) > .001f || (a[0] < x) == (b[0] < x)) { continue; }
                            float y = a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]);
                            low = Math.min(low, y);
                            high = Math.max(high, y);
                        }
                    }
                    assertTrue(Float.isFinite(low) && Float.isFinite(high), "Both walls must cross the section");
                    assertTrue(high - low >= 16, "A vehicle passage must not pinch below its door width: " + (high - low));
                }
            }
        }
    }

    @Test
    void roofFittingsStayWholeAndClearOfTheBevel() {
        for (int direction = 0; direction < 6; direction++) {
            var heights = flower(3, 2);
            Coords annex = CENTRE.translated(direction).translated(direction);
            heights.put(annex, 2);
            var scene = scene(heights, DOME, Map.of(), Set.of());
            float area = 0, roofHeight = 2 * GpuBuilding.LEVEL_HEIGHT;
            for (Coords at : heights.keySet()) {
                var data = BoardMultiHexBuilding.model(layout(scene, at), 0);
                var details = vertices(data, "structures/lunar/habitat-roof");
                var roof = vertices(data, BoardMultiHexBuilding.ROOF);
                for (int i = 0; i < details.size(); i += 3) {
                    float[] a = details.get(i), b = details.get(i + 1), c = details.get(i + 2);
                    area += Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2;
                    // A centroid avoids float differences on a shared hex edge; the fittings' margin keeps their
                    // complete rectangle inside the flat roof, including the gatehouse's chamfered corners.
                    float x = (a[0] + b[0] + c[0]) / 3, y = (a[1] + b[1] + c[1]) / 3;
                    boolean supported = false;
                    for (int j = 0; j < roof.size(); j += 3) {
                        float[] p = roof.get(j), q = roof.get(j + 1), r = roof.get(j + 2);
                        if (p[5] < .999f || Math.abs(p[2] - roofHeight) > .001f) { continue; }
                        supported |= Intersector.isPointInTriangle(x, y, p[0], p[1], q[0], q[1], r[0], r[1]);
                    }
                    assertTrue(supported, "Every fitting sits above the flat roof, clear of its bevel");
                }
            }
            assertEquals(2 * 5.4f * 4.2f + 6 * 6, area, .02f,
                  "Both complete grilles and the complete hatch survive the cuts into hex shares");
        }
    }

    private static GeneratedMesh passage(float[] from, float[] to, int lod) throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("passage", GeneratedMesh.class, List.class,
              float[].class, float[].class, int.class);
        method.setAccessible(true);
        var mesh = new GeneratedMesh();
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 1000), HexTile.cornerOffsetY(corner, 866) });
        }
        method.invoke(null, mesh, hex, from, to, lod);
        return mesh;
    }

    private static boolean bevel(float[] vertex) { return vertex[5] > .8f && vertex[5] < .95f; }

    /** Physical directions of increasing U and V; ignore zero-area triangles left by clipping. */
    private static Vector3[] textureAxes(float[] a, float[] b, float[] c) {
        var ab = new Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
        var ac = new Vector3(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
        if (ab.cpy().crs(ac).len2() < 1e-8f) { return null; }
        float du1 = b[10] - a[10], dv1 = b[11] - a[11], du2 = c[10] - a[10], dv2 = c[11] - a[11];
        float determinant = du1 * dv2 - du2 * dv1;
        assertTrue(Math.abs(determinant) > 1e-8f, "A visible face needs non-degenerate texture coordinates");
        return new Vector3[] { ab.cpy().scl(dv2).mulAdd(ac, -dv1).scl(1 / determinant),
              ac.cpy().scl(du1).mulAdd(ab, -du2).scl(1 / determinant) };
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
