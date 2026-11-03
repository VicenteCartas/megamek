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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(ints = { 1, 3, 5, 10, 50 })
    void standaloneDomeGlassStaysOnItsTower(int levels) {
        for (var design : BoardMultiHexBuilding.Design.values()) {
            var layout = BoardMultiHexBuilding.layout(design.asset, levels);
            assertDomeMeetsTower(layout.domes().getFirst(), List.of(layout), levels);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 5, 8, 12 })
    void raisingOneHexExtendsTheTowerUnderItsDome(int levels) {
        var heights = flower(3, 1);
        Coords peak = CENTRE.translated(1);
        heights.put(peak, levels);
        for (var design : BoardMultiHexBuilding.Design.values()) {
            var scene = scene(heights, design.asset, Map.of(), Set.of());
            var shares = heights.keySet().stream().map(at -> layout(scene, at)).toList();
            assertDomeMeetsTower(layout(scene, peak).domes().getFirst(), shares, levels);
        }
    }

    private static void assertDomeMeetsTower(BoardMultiHexBuilding.Dome dome,
          List<BoardMultiHexBuilding.Layout> shares, int levels) {
        List<float[]> glass = new ArrayList<>();
        for (var share : shares) {
            var data = BoardMultiHexBuilding.glass(share);
            if (data != null) { glass.addAll(vertices(data, BoardMultiHexBuilding.GLASS)); }
        }
        assertFalse(glass.isEmpty());
        assertEquals(levels * GpuBuilding.LEVEL_HEIGHT,
              glass.stream().mapToDouble(vertex -> vertex[2]).max().orElseThrow(), .02,
              "The dome's apex must follow the edited building height");
        assertEquals(dome.ring(), glass.stream().mapToDouble(vertex -> vertex[2]).min().orElseThrow(), .02,
              "The glass must reach the supporting ring instead of floating above it");
        for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
            List<float[]> walls = new ArrayList<>();
            for (var share : shares) {
                walls.addAll(vertices(BoardMultiHexBuilding.model(share, lod), BoardMultiHexBuilding.WALL));
            }
            assertEquals(dome.ring(), walls.stream().mapToDouble(vertex -> vertex[2]).max().orElseThrow(), .02,
                  "The tower walls must extend all the way to the glass at every LOD");
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
    void equalHeightClustersKeepBothDomesWhenJoinedByANarrowCorridor() {
        var heights = flower(1, 1);
        Coords small = CENTRE.translated(0).translated(0).translated(0);
        for (Coords at : List.of(small, small.translated(0), small.translated(1))) { heights.put(at, 1); }
        var separate = scene(heights, DOME, Map.of(), Set.of());
        var largeFit = BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(separate, separate.tile(CENTRE), DOME)).getFirst();
        var smallFit = BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(separate, separate.tile(small), DOME)).getFirst();
        heights.put(CENTRE.translated(0).translated(0), 1);
        var joined = scene(heights, DOME, Map.of(), Set.of());
        var fits = BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(joined, joined.tile(CENTRE), DOME));
        assertEquals(2, fits.size(), "A narrow connector must not absorb the smaller, equally high dome");
        assertTrue(fits.contains(largeFit)); assertTrue(fits.contains(smallFit));
        assertFalse(layout(joined, CENTRE.translated(0).translated(0)).tunnels().isEmpty());
    }

    @Test
    void threeHexCorridorsHaveOneStraightRoofAndNoIntermediateGatehouses() {
        for (int direction = 0; direction < 6; direction++) {
            var heights = flower(1, 1);
            Coords at = CENTRE.translated(direction);
            List<Coords> corridor = new ArrayList<>();
            for (int step = 0; step < 3; step++) { at = at.translated(direction); heights.put(at, 1); corridor.add(at); }
            var scene = scene(heights, DOME, Map.of(), Set.of());
            var middle = layout(scene, corridor.get(1));
            assertEquals(1, middle.tunnels().size());
            assertTrue(middle.domes().stream().allMatch(dome -> dome.modules().isEmpty()), "No repeated radial wings along the corridor");
            assertEquals(2, middle.tunnels().getFirst().openings().stream().filter(top -> top > 0).count());
            for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
                var roof = vertices(BoardMultiHexBuilding.model(middle, lod), BoardMultiHexBuilding.ROOF);
                double area = 0;
                for (int index = 0; index < roof.size(); index += 3) {
                    float[] a = roof.get(index), b = roof.get(index + 1), c = roof.get(index + 2);
                    if (a[5] < .99f || b[5] < .99f || c[5] < .99f) { continue; }
                    assertEquals(GpuBuilding.LEVEL_HEIGHT, a[2], .01f);
                    area += Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) / 2;
                }
                assertEquals(24 * HexTile.HEIGHT, area, 3, "One uninterrupted roof, without overlapping module caps");
            }
        }
    }

    @Test
    void aNarrowLevelRunIsACorridorButARaisedPeakCanSupportADome() {
        var heights = new HashMap<Coords, Integer>();
        for (int row = 2; row <= 6; row++) { heights.put(new Coords(4, row), 1); }
        var flat = scene(heights, DOME, Map.of(), Set.of());
        assertTrue(BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(flat, flat.tile(CENTRE), DOME)).isEmpty());
        assertFalse(layout(flat, CENTRE).tunnels().isEmpty());
        heights.put(CENTRE, 5);
        var raised = scene(heights, DOME, Map.of(), Set.of());
        var fits = BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(raised, raised.tile(CENTRE), DOME));
        assertEquals(1, fits.size());
        assertEquals(5 * GpuBuilding.LEVEL_HEIGHT, fits.getFirst().apex(), .01f);
    }

    @Test
    void corridorJunctionsCoverTheirArmsAndKeepTheInsideCornersClear() {
        var heights = new HashMap<Coords, Integer>(); heights.put(CENTRE, 1);
        for (int direction : new int[] { 0, 2, 4 }) {
            Coords at = CENTRE;
            for (int step = 0; step < 2; step++) { at = at.translated(direction); heights.put(at, 1); }
        }
        var scene = scene(heights, DOME, Map.of(), Set.of());
        var junction = layout(scene, CENTRE);
        assertTrue(junction.domes().isEmpty());
        var triangles = BoardMultiHexBuilding.triangles(junction);
        assertTrue(triangles.stream().allMatch(vertex -> Float.isFinite(vertex.x) && Float.isFinite(vertex.y) && Float.isFinite(vertex.z)));
        var roof = GpuBuildingInterior.area(GpuBuildingInterior.plane(triangles, GpuBuilding.LEVEL_HEIGHT, true));
        assertTrue(roof.contains(0, 0));
        for (int direction = 0; direction < 6; direction++) {
            double angle = Math.toRadians(90 - 60 * direction);
            assertEquals(direction % 2 == 0, roof.contains(35 * Math.cos(angle), 35 * Math.sin(angle)),
                  "Only an authored arm reaches hexside " + direction);
        }
    }

    @Test
    void corridorEntrancesStayConnectedAfterReplacingTheGatehouses() {
        for (boolean hasDome : List.of(false, true)) {
            var heights = hasDome ? flower(1, 1) : new HashMap<Coords, Integer>();
            Coords corridor = CENTRE.translated(0).translated(0), approach = corridor.translated(1);
            for (int row = 1; row <= 3; row++) { heights.put(new Coords(4, row), 1); }
            for (boolean road : List.of(false, true)) {
                var scene = scene(heights, DOME, road ? Map.of() : Map.of(approach, 1), Set.of(), Set.of(),
                      Map.of(), road ? Map.of(approach, 1 << 4) : Map.of());
                var corridorLayout = layout(scene, corridor);
                assertEquals(road ? 1 : 0, corridorLayout.corridorGates().size());
                float ux = (float) Math.cos(Math.PI / 6), uy = .5f;
                float end = road ? HexTile.HEIGHT / 2f - 2.6f - 9
                      : corridorLayout.gear().stream().filter(gear -> gear.piece().equals("airlock"))
                            .map(gear -> gear.x() * ux + gear.y() * uy).findFirst().orElseThrow();
                float middle = (15 / ux + end) / 2;
                for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
                    var model = BoardMultiHexBuilding.model(corridorLayout, lod);
                    var vertices = vertices(model, BoardMultiHexBuilding.FRAME);
                    vertices.addAll(vertices(model, BoardMultiHexBuilding.ROOF));
                    List<Vector3> aboveFoundation = new ArrayList<>();
                    for (int at = 0; at < vertices.size(); at += 3) {
                        if (vertices.get(at)[2] <= 9 || vertices.get(at + 1)[2] <= 9 || vertices.get(at + 2)[2] <= 9) { continue; }
                        for (int corner = 0; corner < 3; corner++) {
                            var v = vertices.get(at + corner); aboveFoundation.add(new Vector3(v[0], v[1], v[2]));
                        }
                    }
                    assertTrue(GpuBuildingInterior.area(aboveFoundation).contains(middle * ux, middle * uy),
                          "A roof must bridge the gap from the corridor wall to its " + (road ? "vehicle gate" : "airlock"));
                }
            }
        }
    }

    @Test
    void gatesStillAttachWhenSeveralCorridorArmsLeaveOnlyAShortCorner() throws Exception {
        var door = BoardMultiHexBuilding.class.getDeclaredMethod("door", BoardMultiHexBuilding.Layout.class,
              float.class, float.class, float.class, float.class, float.class);
        door.setAccessible(true);
        for (int gateSide = 0; gateSide < 6; gateSide++) {
            var heights = new HashMap<Coords, Integer>(); heights.put(CENTRE, 1);
            Set<Set<Coords>> apart = new java.util.HashSet<>();
            for (int direction = 0; direction < 6; direction++) {
                if (direction != gateSide) { heights.put(CENTRE.translated(direction), 1); }
                apart.add(Set.of(CENTRE.translated(direction), CENTRE.translated((direction + 1) % 6)));
            }
            var scene = scene(heights, DOME, Map.of(), Set.of(), apart, Map.of(),
                  Map.of(CENTRE.translated(gateSide), 1 << (gateSide + 3) % 6));
            var layout = layout(scene, CENTRE);
            var gate = layout.corridorGates().getFirst();
            float[] attachment = (float[]) door.invoke(null, layout, 0f, 0f, gate.ux(), gate.uy(), 12f);
            assertNotNull(attachment, "A short facade must not disconnect the road gate on side " + gateSide);
            var triangles = BoardMultiHexBuilding.triangles(layout);
            for (var vertex : triangles) { assertTrue(Float.isFinite(vertex.x) && Float.isFinite(vertex.y) && Float.isFinite(vertex.z)); }
        }
    }

    @Test
    void corridorDockingOnUnevenGroundKeepsItsFoundationBelowItsRoof() {
        var heights = flower(3, 3);
        Coords petal = CENTRE.translated(0), first = petal.translated(0), second = first.translated(0);
        heights.put(first, 1); heights.put(second, 1);
        var scene = scene(heights, DOME, Map.of(), Set.of(), Set.of(), Map.of(CENTRE, 2), Map.of());
        var dock = layout(scene, petal);
        assertFalse(dock.tunnels().isEmpty());
        for (var tunnel : dock.tunnels()) {
            assertEquals(GpuBuilding.LEVEL_HEIGHT, tunnel.top(), .001f, "The dock meets the lower corridor roof");
            assertTrue(tunnel.foot() < tunnel.top() - 1.6f, "Its foundation leaves room for a wall below the bevel");
        }
        assertTrue(BoardMultiHexBuilding.triangles(dock).stream().allMatch(v -> Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z)));
    }

    @Test
    void connectedClustersKeepTheirOwnGroundLevelsAndAnnexRoofs() {
        var heights = flower(1, 1);
        Map<Coords, Integer> elevations = new HashMap<>(); heights.keySet().forEach(at -> elevations.put(at, 2));
        Coords small = new Coords(4, 1), annex = new Coords(6, 0);
        for (Coords at : List.of(small, small.translated(0), small.translated(1), annex)) { heights.put(at, 2); }
        heights.put(new Coords(4, 2), 3);
        var scene = scene(heights, DOME, Map.of(), Set.of(), Set.of(), elevations, Map.of());
        assertEquals(2, BoardMultiHexBuilding.fits(BoardMultiHexBuilding.group(scene, scene.tile(small), DOME)).size());
        assertEquals(BoardMultiHexBuilding.PLINTH, layout(scene, small).top(), .001f,
              "Connecting a cluster uphill must not lift the lower dome's equipment and entrances");
        var terminal = layout(scene, annex);
        assertTrue(terminal.annex());
        assertTrue(terminal.tunnels().isEmpty());
        var dome = terminal.domes().stream().filter(candidate -> !candidate.modules().isEmpty()).findFirst().orElseThrow();
        assertEquals(2 * GpuBuilding.LEVEL_HEIGHT, dome.foot() + dome.modules().get(3), .001f,
              "The terminal wing keeps its own roof height, relative to the dome it actually joins");
    }

    @Test
    void annexBevelsLayPanelsAlongEachEdgeWithoutStretchingOrHexSeams() {
        for (int direction = 0; direction < 6; direction++) {
            var heights = flower(3, 2);
            Coords petal = CENTRE.translated(direction), annex = petal.translated(direction);
            heights.put(annex, 2);
            var scene = scene(heights, DOME, Map.of(), Set.of());
            for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
                var caps = vertices(BoardMultiHexBuilding.model(layout(scene, annex), lod), BoardMultiHexBuilding.PIER);
                int checked = 0;
                for (int i = 0; i < caps.size(); i += 3) {
                    if (!bevel(caps.get(i))) { continue; }
                    var axes = textureAxes(caps.get(i), caps.get(i + 1), caps.get(i + 2));
                    if (axes == null) { continue; }
                    assertEquals(0, axes[1].z, .002f, "The coping's metal grain follows the horizontal edge, including cut corners");
                    assertEquals(0, axes[0].cpy().nor().dot(axes[1].cpy().nor()), .002f,
                          "Unwrapping the bevel must not shear its panels");
                    assertEquals(axes[0].len(), axes[1].len(), .01f, "Equal texel density along and across the bevel");
                    checked++;
                }
                assertTrue(checked > 0, "The annex must include bevel faces");

                var neighbour = vertices(BoardMultiHexBuilding.model(layout(scene, petal), lod), BoardMultiHexBuilding.PIER);
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
                tube.addAll(vertices(data, BoardMultiHexBuilding.WALL));
                tube.addAll(vertices(data, BoardMultiHexBuilding.ROOF));
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
    void roofsStayClearForManualEquipmentWhileFacadesHaveWindows() {
        for (int direction = 0; direction < 6; direction++) {
            var heights = flower(3, 2);
            Coords annex = CENTRE.translated(direction).translated(direction);
            heights.put(annex, 2);
            var scene = scene(heights, DOME, Map.of(), Set.of());
            for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
                var data = BoardMultiHexBuilding.model(layout(scene, annex), lod);
                assertTrue(vertices(data, "structures/lunar/habitat-roof").isEmpty(), "No automatic rooftop hatches or machinery");
                assertTrue(vertices(data, "structures/lunar/skylight-glass").isEmpty(), "Skylights are placed manually");
                var windows = vertices(data, BoardMultiHexBuilding.WINDOW);
                assertFalse(windows.isEmpty(), "The habitat has pressure windows in its walls");
                for (var vertex : windows) {
                    assertEquals(0, vertex[5], .001f, "Glazing is on facades, not automatic roof fittings");
                    assertTrue(vertex[2] < 2 * GpuBuilding.LEVEL_HEIGHT);
                }
            }
        }
    }

    @Test
    void vehiclePassageCutsFacadeDetailsButPreservesTheWallAboveAndBesideIt() throws Exception {
        for (float angle : new float[] { -60, -45, 0, 45, 60 }) {
            float nx = (float) Math.cos(Math.toRadians(angle)), ny = (float) Math.sin(Math.toRadians(angle));
            var mesh = new GeneratedMesh();
            float[][] wall = new float[4][];
            for (int i = 0; i < 4; i++) {
                float along = i == 0 || i == 3 ? -20 : 20, z = i < 2 ? 0 : 35;
                wall[i] = new float[] { -ny * along, nx * along, z, nx, ny, 0, 1, 1, 1, 1, (along + 20) / 40, z / 35 };
            }
            mesh.quad(BoardMultiHexBuilding.WINDOW, wall[0], wall[1], wall[2], wall[3]);
            passage(mesh, new float[] { 0, 0, nx, ny, 6, 27 }, new float[] { 18, 0, 1, 0, 6, 20 }, 0);
            List<Vector3> facade = new ArrayList<>();
            for (float[] vertex : vertices(mesh.data("facade opening", false), BoardMultiHexBuilding.WINDOW)) {
                float along = -ny * vertex[0] + nx * vertex[1];
                facade.add(new Vector3(along, vertex[2], 0));
                assertEquals((along + 20) / 40, vertex[10], .0001, "Clipped wall UVs retain their phase");
                assertEquals(vertex[2] / 35, vertex[11], .0001);
            }
            var remaining = GpuBuildingInterior.area(facade);
            assertFalse(remaining.contains(0, 10), "No window or door panel behind the passage");
            assertTrue(remaining.contains(-17, 10) && remaining.contains(17, 10), "Keep adjacent facade bays");
            assertTrue(remaining.contains(0, 32), "Keep the upper storey's facade");
        }
    }

    private static GeneratedMesh passage(float[] from, float[] to, int lod) throws Exception {
        var mesh = new GeneratedMesh();
        passage(mesh, from, to, lod);
        return mesh;
    }

    private static void passage(GeneratedMesh mesh, float[] from, float[] to, int lod) throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("passage", GeneratedMesh.class, List.class,
              float[].class, float[].class, int.class);
        method.setAccessible(true);
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 1000), HexTile.cornerOffsetY(corner, 866) });
        }
        method.invoke(null, mesh, hex, from, to, lod);
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
        return scene(heights, asset, rails, fallen, apart, Map.of(), Map.of());
    }

    private static BoardScene scene(Map<Coords, Integer> heights, String asset, Map<Coords, Integer> rails,
          Set<Coords> fallen, Set<Set<Coords>> apart, Map<Coords, Integer> elevations, Map<Coords, Integer> roads) {
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
            return new BoardScene.Tile(coords, elevations.getOrDefault(coords, 0), -1, false, roads.getOrDefault(coords, 0), base.surface(), null, null, features, List.of(),
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
        Set<String> parts = new java.util.HashSet<>();
        for (var node : data.nodes) {
            if (node.parts == null) { continue; }
            for (var part : node.parts) { if (part.materialId.equals(material)) { parts.add(part.meshPartId); } }
        }
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                if (!parts.contains(part.id)) { continue; }
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
