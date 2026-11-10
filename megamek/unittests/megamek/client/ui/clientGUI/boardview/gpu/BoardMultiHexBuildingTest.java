/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
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
                // The 30-unit corridor leaves a 22.4-unit panel between its two armored roof-edge beams.
                assertEquals(22.4 * HexTile.HEIGHT, area, 3, "One uninterrupted roof, without overlapping module caps");
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
                    vertices.addAll(vertices(model, BoardMultiHexBuilding.PIER));
                    vertices.addAll(vertices(model, BoardMultiHexBuilding.HARDWARE));
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
    void ringPierFixturesFollowExposedFacesAndKeepWhiteFeet() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("pier", GeneratedMesh.class, List.class,
              float.class, float.class, float.class, float.class, float.class, float.class, float.class, boolean.class);
        method.setAccessible(true);
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 1000), HexTile.cornerOffsetY(corner, 866) });
        }
        for (boolean exposed : new boolean[] { true, false }) {
            var mesh = new GeneratedMesh();
            method.invoke(null, mesh, hex, 0f, 0f, 1f, 0f, 3.3f, 6f, 54f, exposed);
            var data = mesh.data("ring-piers", false);
            var white = vertices(data, BoardMultiHexBuilding.LIGHT);
            var amber = vertices(data, BoardMultiHexBuilding.MARKER);
            assertFalse(white.isEmpty());
            assertEquals(exposed, !amber.isEmpty(), "Only ring corners exposed to the apron have amber head lamps");
            for (var normal : List.of(new Vector3(1, 0, 0), new Vector3(0, 1, 0), new Vector3(0, -1, 0))) {
                assertTrue(white.stream().anyMatch(v -> v[2] < 8 && v[3] * normal.x + v[4] * normal.y > .99f),
                      "The radial front and both exposed returns have small white foot lamps");
                assertTrue((exposed ? amber : white).stream().anyMatch(v -> v[2] > 50 && v[3] * normal.x + v[4] * normal.y > .99f),
                      "Each exposed face has a head fixture");
            }
            assertTrue(amber.stream().allMatch(v -> v[2] > 50), "Every bottom light remains white");
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
                boolean rib = Math.abs(a[2] - dome.ring() - .95f) < .002f;
                boolean rim = Math.abs(a[2] - dome.ring() - .25f) < .002f;
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
    void slopedPassageRoofKeepsLongitudinalPanelsWithoutShearing() throws Exception {
        for (float height : new float[] { 15, 30, 45 }) {
            // The farther mesh retains the same armor skins without overlaid fastening plates.
            var mesh = passage(new float[] { 0, 0, 1, 0, 6, height }, new float[] { 20, 0, 1, 0, 6, 20 }, 1);
            var roof = vertices(mesh.data("passage", false), BoardMultiHexBuilding.HARDWARE);
            assertFalse(roof.isEmpty());
            for (int i = 0; i < roof.size(); i += 3) {
                var axes = textureAxes(roof.get(i), roof.get(i + 1), roof.get(i + 2));
                if (axes == null) { continue; }
                assertEquals(axes[0].len(), 2 * axes[1].len(), .002f, "Passage plates run twice as far along the tunnel as across it");
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
                tube.addAll(vertices(data, BoardMultiHexBuilding.HARDWARE));
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
    void cornerPillarsFollowBothWallsWithHeadAndFootLightsOnEachFace() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("cornerPost", GeneratedMesh.class, List.class,
              float[].class, BoardMultiHexBuilding.Point.class, BoardMultiHexBuilding.Point.class,
              float.class, float.class, boolean.class);
        method.setAccessible(true);
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 100), HexTile.cornerOffsetY(corner, 86.6f) });
        }
        var mesh = new GeneratedMesh();
        var first = new BoardMultiHexBuilding.Point(0, -1);
        var side = new BoardMultiHexBuilding.Point(1, 0);
        method.invoke(null, mesh, hex, new float[] { 0, 0 }, first, side, 0f, 90f, true);
        var data = mesh.data("corner-pillar", false);
        assertEquals(0, vertices(data, BoardMultiHexBuilding.PIER).stream().mapToDouble(v -> v[2]).min().orElseThrow(), .001);
        var yellow = vertices(data, BoardMultiHexBuilding.MARKER);
        var white = vertices(data, BoardMultiHexBuilding.LIGHT);
        assertTrue(yellow.stream().allMatch(v -> v[2] > 86), "Only the head lamps may be yellow");
        assertTrue(white.stream().allMatch(v -> v[2] < 2), "Both foot markers must be white");
        var lamps = new ArrayList<>(yellow); lamps.addAll(white);
        for (var normal : List.of(first, side)) {
            var face = lamps.stream().filter(v -> v[3] * normal.x() + v[4] * normal.y() > .99).toList();
            assertTrue(face.stream().anyMatch(v -> v[2] > 86), "Each wall-aligned face has a head lamp");
            assertTrue(face.stream().anyMatch(v -> v[2] < 2), "Each wall-aligned face has a small ground-level marker");
            assertTrue(face.stream().allMatch(v -> v[2] < 2 || v[2] > 86), "No repeated storey lamps");
        }
        assertTrue(lamps.stream().allMatch(v -> Math.abs(v[3]) < .01 || Math.abs(v[4]) < .01),
              "The fixtures follow the walls rather than a diagonal corner bisector");
    }

    @Test
    void pillarCapFinishFollowsRotationAndTranslationWithoutStretching() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("postCap", GeneratedMesh.class, List.class,
              List.class, float.class, float.class, float.class);
        method.setAccessible(true);
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 1000), HexTile.cornerOffsetY(corner, 866) });
        }
        List<float[]> reference = new ArrayList<>();
        for (int degrees : new int[] { 0, 30, 90, 210 }) {
            float cos = (float) Math.cos(Math.toRadians(degrees)), sin = (float) Math.sin(Math.toRadians(degrees));
            float x = degrees == 0 ? 0 : 64, y = degrees == 0 ? 0 : -37;
            var outline = List.of(new float[] { -4, -3 }, new float[] { 4, -3 }, new float[] { 5, 2 }, new float[] { -3, 4 })
                  .stream().map(p -> new float[] { x + cos * p[0] - sin * p[1], y + sin * p[0] + cos * p[1] }).toList();
            var mesh = new GeneratedMesh();
            method.invoke(null, mesh, hex, outline, 0f, 2f, .5f);
            var plates = vertices(mesh.data("pillar-cap", false), BoardMultiHexBuilding.HARDWARE);
            int tops = 0, shoulders = 0;
            for (int i = 0; i < plates.size(); i += 3) {
                float[] a = plates.get(i), b = plates.get(i + 1), c = plates.get(i + 2);
                var ab = new Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
                var ac = new Vector3(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
                if (ab.crs(ac).len() < .2f) { continue; } // Ignore the small round fastener heads.
                var axes = textureAxes(a, b, c);
                assertNotNull(axes);
                assertEquals(12, axes[0].len(), .01, "The top and shoulders use the same physical texture scale");
                assertEquals(12, axes[1].len(), .01);
                assertEquals(0, axes[0].cpy().nor().dot(axes[1].cpy().nor()), .002,
                      "Angled plate edges must not shear the metal grain or its normal map");
                for (float[] v : List.of(a, b, c)) {
                    assertTrue(v[11] >= .1f && v[11] <= .9f, "Baked seam and bolt rows must not cross the fitted cap");
                    if (degrees == 0) { reference.add(v); continue; }
                    float originalX = (v[0] - x) * cos + (v[1] - y) * sin;
                    float originalY = -(v[0] - x) * sin + (v[1] - y) * cos;
                    assertTrue(reference.stream().anyMatch(r -> Math.abs(r[0] - originalX) < .001f
                                && Math.abs(r[1] - originalY) < .001f && Math.abs(r[2] - v[2]) < .001f
                                && Math.abs(r[10] - v[10]) < .001f && Math.abs(r[11] - v[11]) < .001f),
                          "Rotating and moving a cap must carry its texture orientation and phase with it");
                }
                if (a[5] > .99f) { tops++; } else { shoulders++; }
            }
            assertTrue(tops > 0 && shoulders > 0);
        }
    }

    @Test
    void copingPlateFinishFollowsItsMemberWithoutExtraTextureJoints() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("coping", GeneratedMesh.class, List.class,
              float[].class, float[].class, float[].class, float[].class, float.class, float.class);
        method.setAccessible(true);
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 1000), HexTile.cornerOffsetY(corner, 866) });
        }
        for (int degrees : new int[] { 0, 30, 90, 210 }) {
            float cos = (float) Math.cos(Math.toRadians(degrees)), sin = (float) Math.sin(Math.toRadians(degrees));
            var outline = List.of(new float[] { 0, 0 }, new float[] { 36, 0 }, new float[] { 3.8f, 3.8f }, new float[] { 32.2f, 3.8f })
                  .stream().map(p -> new float[] { 64 + cos * p[0] - sin * p[1], -37 + sin * p[0] + cos * p[1] }).toList();
            var mesh = new GeneratedMesh();
            method.invoke(null, mesh, hex, outline.get(0), outline.get(1), outline.get(2), outline.get(3), 20f, 3.5f);
            var plates = vertices(mesh.data("coping", false), BoardMultiHexBuilding.HARDWARE);
            int tops = 0, fronts = 0;
            for (int i = 0; i < plates.size(); i += 3) {
                float[] a = plates.get(i), b = plates.get(i + 1), c = plates.get(i + 2);
                var ab = new Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
                var ac = new Vector3(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
                if (ab.crs(ac).len() < .2f) { continue; }
                var axes = textureAxes(a, b, c);
                assertNotNull(axes);
                assertEquals(1, Math.abs(axes[0].cpy().nor().dot(new Vector3(cos, sin, 0))), .002f,
                      "Hardware finish must run along the wall, including mitered ends and joint straps");
                assertEquals(12, axes[0].len(), .02f);
                assertEquals(12, axes[1].len(), .02f);
                assertEquals(0, axes[0].cpy().nor().dot(axes[1].cpy().nor()), .002f);
                for (float[] v : List.of(a, b, c)) {
                    assertTrue(v[11] >= .1f && v[11] <= .9f, "Baked trim joints must not cross physical coping plates");
                }
                if (a[5] > .99f) { tops++; } else { fronts++; }
            }
            assertTrue(tops > 0 && fronts > 0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void topPlateBoltsRespectChamfersMiteredEndsAndWasherClearance() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("plateBolts", List.class, float.class);
        method.setAccessible(true);
        var cap = List.of(new float[] { -3, -.82f }, new float[] { 3, -.82f }, new float[] { 3, .22f },
              new float[] { 2.4f, .82f }, new float[] { -2.4f, .82f }, new float[] { -3, .22f });
        var rail = List.of(new float[] { 0, 0 }, new float[] { 9, 0 }, new float[] { 7.5f, 2.8f }, new float[] { 1.5f, 2.8f });
        for (var plate : List.of(cap, rail)) {
            var reference = (List<float[]>) method.invoke(null, plate, .18f);
            assertEquals(4, reference.size(), "Chamfer vertices must not turn four corner fasteners into an uneven three-bolt pattern");
            for (float[] bolt : reference) {
                for (int edge = 0; edge < plate.size(); edge++) {
                    float[] a = plate.get(edge), b = plate.get((edge + 1) % plate.size());
                    double distance = ((b[0] - a[0]) * (bolt[1] - a[1]) - (b[1] - a[1]) * (bolt[0] - a[0]))
                          / Math.hypot(b[0] - a[0], b[1] - a[1]);
                    assertTrue(distance >= .18f * 1.45f + .19f, "The whole washer needs a visible margin inside every plate edge");
                }
                assertTrue(reference.stream().anyMatch(other -> Math.abs(other[0] - (plate == cap ? -bolt[0] : 9 - bolt[0])) < .001f
                            && Math.abs(other[1] - bolt[1]) < .001f), "Symmetric plates need symmetric fastening patterns");
            }
            for (int shift = 0; shift < plate.size(); shift++) {
                List<float[]> reordered = new ArrayList<>(plate);
                Collections.rotate(reordered, shift);
                Collections.reverse(reordered);
                var bolts = (List<float[]>) method.invoke(null, reordered, .18f);
                assertEquals(reference.size(), bolts.size());
                for (float[] bolt : bolts) {
                    assertTrue(reference.stream().anyMatch(r -> Math.hypot(r[0] - bolt[0], r[1] - bolt[1]) < .001),
                          "Changing vertex order must not move the bolts");
                }
            }
        }
        var sliver = List.of(new float[] { 0, 0 }, new float[] { .7f, 0 }, new float[] { .7f, 3 }, new float[] { 0, 3 });
        assertTrue(((List<float[]>) method.invoke(null, sliver, .18f)).isEmpty(), "A partial plate too small for a washer gets no bolt");
    }

    @Test
    void railJointsCrossMitersSquarelyWithoutLosingTheOverhangingEnds() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("coping", GeneratedMesh.class, List.class,
              float[].class, float[].class, float[].class, float[].class, float.class, float.class);
        method.setAccessible(true);
        for (boolean inward : new boolean[] { false, true }) {
            for (int degrees : new int[] { 0, 30, 90, 210 }) {
                float cos = (float) Math.cos(Math.toRadians(degrees)), sin = (float) Math.sin(Math.toRadians(degrees));
                var outline = List.of(new float[] { 0, 0 }, new float[] { 36, 0 },
                            new float[] { inward ? -5 : 3.8f, 3.8f }, new float[] { inward ? 42 : 32.2f, 3.8f })
                      .stream().map(p -> new float[] { 64 + cos * p[0] - sin * p[1], -37 + sin * p[0] + cos * p[1] }).toList();
                var mesh = new GeneratedMesh();
                method.invoke(null, mesh, wideHex(), outline.get(0), outline.get(1), outline.get(2), outline.get(3), 20f, 3.5f);
                var top = vertices(mesh.data("rail-joints", false), BoardMultiHexBuilding.HARDWARE).stream()
                      .filter(v -> Math.abs(v[2] - 20.285f) < .001f && v[5] > .999f)
                      .map(v -> new Vector3((v[0] - 64) * cos + (v[1] + 37) * sin,
                            -(v[0] - 64) * sin + (v[1] + 37) * cos, 0)).toList();
                var plates = GpuBuildingInterior.area(top);
                for (float joint : new float[] { 5.5f, 14.5f, 23.5f }) {
                    for (float across : new float[] { .65f, 1.9f, 3.15f }) {
                        assertFalse(plates.contains(joint, across), "A joint crosses the full rail width at one along-rail distance");
                        assertTrue(plates.contains(joint - .4f, across) && plates.contains(joint + .4f, across),
                              "The fitted plates meet both sides of the narrow square joint");
                    }
                }
                if (inward) {
                    assertTrue(plates.contains(-3, 2.8) && plates.contains(40, 3),
                          "Inward miter overhangs keep their fitted plates beyond the outer rail's endpoints");
                    assertTrue(plates.contains(-3.5, 3.15),
                          "A periodic joint must not cut a small triangle out of the mitered end");
                }
            }
        }
    }

    @Test
    void railFastenersClearCapOverhangsWithoutErasingOtherHeights() throws Exception {
        var capType = Class.forName(BoardMultiHexBuilding.class.getName() + "$Cap");
        var constructor = capType.getDeclaredConstructor(List.class, float.class, float.class);
        constructor.setAccessible(true);
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("coping", GeneratedMesh.class, List.class,
              float[].class, float[].class, float[].class, float[].class, float.class, float.class, List.class);
        method.setAccessible(true);
        // The nearest bolt centres fall outside this narrow overhang, but their washers leave no useful gap.
        var outline = List.of(new float[] { 8.7f, 0 }, new float[] { 9.3f, 0 }, new float[] { 9.3f, 3.8f }, new float[] { 8.7f, 3.8f });
        List<List<float[]>> washers = new ArrayList<>();
        for (var caps : List.of(List.of(), List.of(constructor.newInstance(outline, 19f, 22f)),
              List.of(constructor.newInstance(outline, 30f, 32f)))) {
            var mesh = new GeneratedMesh();
            method.invoke(null, mesh, wideHex(), new float[] { 0, 0 }, new float[] { 36, 0 },
                  new float[] { 0, 3.8f }, new float[] { 36, 3.8f }, 20f, 0f, caps);
            washers.add(vertices(mesh.data("rail-clearance", false), BoardMultiHexBuilding.HARDWARE).stream()
                  .filter(v -> Math.abs(v[2] - 20.295f) < .001f && v[5] > .999f && v[6] < .2f).toList());
        }
        assertTrue(washers.get(1).size() < washers.getFirst().size(), "Remove the bolt pair crowded against the cap overhang");
        assertEquals(washers.getFirst().size(), washers.get(2).size(), "A cap on another roof level must not suppress these fasteners");
        assertTrue(washers.get(1).stream().allMatch(v -> v[0] < 8.5f || v[0] > 9.5f), "Keep whole washers clear of the cap edge");
        assertEquals(washers.getFirst().stream().filter(v -> v[0] > 12).count(),
              washers.get(1).stream().filter(v -> v[0] > 12).count(), "Retain the accepted pattern away from columns");
    }

    @Test
    void unchangedBuildingLayoutReusesItsGeometryCacheKey() {
        var heights = flower(3, 2);
        Coords annex = CENTRE.translated(0).translated(0);
        heights.put(annex, 2);
        var scene = scene(heights, DOME, Map.of(), Set.of());
        var first = layout(scene, annex);
        assertEquals("existing model", Map.of(first, "existing model").get(layout(scene, annex)),
              "Derived cap footprints must retain value equality when the same layout is regenerated");
    }

    @Test
    void tallPillarsHaveOnlyAHeadLightAndASmallerFootLight() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("facadePost", GeneratedMesh.class, List.class,
              float[].class, float.class, float.class, float.class, float.class, float.class, boolean.class);
        method.setAccessible(true);
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 100), HexTile.cornerOffsetY(corner, 86.6f) });
        }
        for (boolean external : new boolean[] { false, true }) {
            var mesh = new GeneratedMesh();
            method.invoke(null, mesh, hex, new float[] { 0, 0 }, 1f, 0f, 6f, 90f, 2.8f, external);
            var data = mesh.data("pillar", false);
            var white = vertices(data, BoardMultiHexBuilding.LIGHT);
            var yellow = vertices(data, BoardMultiHexBuilding.MARKER);
            assertTrue(white.stream().anyMatch(v -> v[2] < 12), "Every foot marker is white, including external pillars");
            assertTrue(yellow.stream().allMatch(v -> v[2] > 84), "Yellow is reserved for external head lamps");
            if (external) { assertTrue(white.stream().allMatch(v -> v[2] < 12)); }
            else { assertTrue(yellow.isEmpty()); }
            var lamps = new ArrayList<>(white); lamps.addAll(yellow);
            assertFalse(lamps.isEmpty());
            float footWidth = 0, headWidth = 0;
            for (float[] v : lamps) {
                assertTrue(v[2] < 12 || v[2] > 84, "Intermediate storeys must not add more lamps");
                if (v[2] < 12) { footWidth = Math.max(footWidth, Math.abs(v[0])); }
                else { headWidth = Math.max(headWidth, Math.abs(v[0])); }
            }
            assertTrue(footWidth > 0 && headWidth > 2 * footWidth, "The foot marker is much smaller than the head lamp");
        }
    }

    @Test
    void pillarLightsReachTheModelAsEmissionAtEveryLevelOfDetail() throws Exception {
        var heights = flower(3, 2);
        Coords annex = CENTRE.translated(0).translated(0);
        heights.put(annex, 2);
        var scene = scene(heights, DOME, Map.of(), Set.of());
        for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) {
            var data = BoardMultiHexBuilding.model(layout(scene, annex), lod);
            assertFalse(vertices(data, BoardMultiHexBuilding.LIGHT).isEmpty());
            var finish = ((RigidGlb.Data) data).surfaces.get(BoardMultiHexBuilding.LIGHT);
            assertNotNull(finish, "The lamp's authored emission must survive the generated model material conversion");
            assertTrue(finish.glow()[0] > 1 && finish.glow()[1] > 1 && finish.glow()[2] > 1,
                  "White pillar lenses emit HDR light independently of the sunlight, like the fortress wall lamps");
            var marker = ((RigidGlb.Data) data).surfaces.get(BoardMultiHexBuilding.MARKER);
            assertNotNull(marker, "External corners carry amber head lamps as well as white foot markers");
            var material = data.materials.select(m -> m.id.equals(BoardMultiHexBuilding.MARKER)).iterator().next();
            var map = material.textures.select(t -> t.usage == ModelTexture.USAGE_EMISSIVE).iterator().next();
            var image = data.images.get(map.fileName);
            assertNotNull(image, "The bounded amber lens must retain its emission map in the generated model");
            var bitmap = ImageIO.read(new java.io.File(image.file()));
            int core = bitmap.getRGB(bitmap.getWidth() / 2, bitmap.getHeight() / 2), rim = bitmap.getRGB(0, 0);
            int red = core >>> 16 & 255, green = core >>> 8 & 255, blue = core & 255;
            assertTrue(red > green * 1.2 && green > blue * 2,
                  "The luminous core remains warm gold rather than white");
            assertTrue((rim >>> 16 & 255) < red / 2, "A darker amber rim preserves the lens shape around the brighter core");
            assertTrue(marker.tint()[0] > 1, "The sampled emission uses HDR energy, independently of sunlight");
            assertTrue(vertices(data, BoardMultiHexBuilding.MARKER).stream().allMatch(v ->
                  v[10] >= 0 && v[10] <= 1 && v[11] >= 0 && v[11] <= 1), "Every head fixture samples its complete bounded lens");
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
                float along = i == 0 || i == 3 ? -36 : 36, z = i < 2 ? 0 : 35;
                wall[i] = new float[] { -ny * along, nx * along, z, nx, ny, 0, 1, 1, 1, 1, (along + 36) / 72, z / 35 };
            }
            mesh.quad(BoardMultiHexBuilding.WINDOW, wall[0], wall[1], wall[2], wall[3]);
            // The road stays straight even when it enters an angled facade; the whole mouth is recessed inside.
            passage(mesh, new float[] { -22, 0, 1, 0, 6, 27 }, new float[] { 18, 0, 1, 0, 6, 20 }, 0);
            List<Vector3> facade = new ArrayList<>();
            for (float[] vertex : vertices(mesh.data("facade opening", false), BoardMultiHexBuilding.WINDOW)) {
                float along = -ny * vertex[0] + nx * vertex[1];
                facade.add(new Vector3(along, vertex[2], 0));
                assertEquals((along + 36) / 72, vertex[10], .0001, "Clipped wall UVs retain their phase");
                assertEquals(vertex[2] / 35, vertex[11], .0001);
            }
            var remaining = GpuBuildingInterior.area(facade);
            assertFalse(remaining.contains(0, 10), "No window or door panel behind the passage");
            assertTrue(remaining.contains(-13 / nx, 10) && remaining.contains(13 / nx, 10), "Keep facade outside the angled opening");
            assertTrue(remaining.contains(0, 32), "Keep the upper storey's facade");
        }
    }

    @Test
    void gateRearBulkheadSealsAroundTheSmallerNeckWithoutClosingIt() throws Exception {
        var mesh = new GeneratedMesh();
        var housing = BoardMultiHexBuilding.class.getDeclaredMethod("gateHousing", GeneratedMesh.class, List.class,
              float.class, float.class, float.class, float.class, float.class);
        housing.setAccessible(true);
        housing.invoke(null, mesh, wideHex(), 0f, 0f, 1f, 0f, 6f);
        passage(mesh, new float[] { -30, 0, 1, 0, 6, 20 }, new float[] { -9, 0, 1, 0, 6, 20 }, 0);
        List<Vector3> rear = new ArrayList<>();
        var body = vertices(mesh.data("gate rear seal", false), BoardMultiHexBuilding.PIER);
        for (int at = 0; at < body.size(); at += 3) {
            boolean onRear = true;
            for (int corner = 0; corner < 3; corner++) {
                float[] vertex = body.get(at + corner);
                onRear &= Math.abs(vertex[0] + 9) < .001 && vertex[3] < -.99;
            }
            if (onRear) {
                for (int corner = 0; corner < 3; corner++) {
                    float[] vertex = body.get(at + corner);
                    rear.add(new Vector3(vertex[1], vertex[2], 0));
                }
            }
        }
        var seal = GpuBuildingInterior.area(rear);
        assertFalse(seal.contains(0, 10), "The vehicle neck remains open through the rear bulkhead");
        assertFalse(seal.contains(-9, 8) || seal.contains(9, 8), "Keep the full usable neck width clear");
        assertTrue(seal.contains(-11.5, 8) && seal.contains(11.5, 8), "Seal both side gaps between the larger hood and its neck");
        assertTrue(seal.contains(0, 19), "Seal the gap above the lower passage roof");
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
        method.invoke(null, mesh, wideHex(), from, to, lod);
    }

    private static List<float[]> wideHex() {
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, 1000), HexTile.cornerOffsetY(corner, 866) });
        }
        return hex;
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
        return BoardScenes.scene(coords -> {
            var base = BoardScenes.tile(coords, BoardScene.Surface.LUNAR, 0, -1, 0);
            int exits = 0;
            for (int direction = 0; direction < 6; direction++) {
                Coords next = coords.translated(direction);
                if (heights.containsKey(next) && !apart.contains(Set.of(coords, next))) { exits |= 1 << direction; }
            }
            boolean standing = heights.containsKey(coords) && !fallen.contains(coords);
            var cover = standing ? new BoardScene.Cover(0, false, 3, exits, 90, 0, heights.get(coords)) : BoardScene.Cover.NONE;
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
