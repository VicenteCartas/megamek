/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Configuration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shipped Lunar Base's different-height corridor joins keep closed walls and complete, non-overlapping caps. */
class LunarDomeTest {
    private static final Map<Coords, BoardMultiHexBuilding.Layout> LAYOUTS = new HashMap<>();

    @BeforeAll
    static void lunarBase() throws Exception {
        GdxNativesLoader.load();
        var task = new FutureTask<BoardScene>(() -> {
            var editor = new BoardEditorSession();
            editor.open(BoardFile.read(Configuration.boardsDir().toPath().resolve("Alien Worlds/32x17 Lunar Base.board2")), null);
            try (var source = new GpuMapSource(editor.game(), null, editor)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task);
        var scene = task.get();
        for (var tile : scene.tiles()) {
            var feature = tile.features().stream().filter(f -> BoardMultiHexBuilding.supports(f.asset())).findFirst().orElse(null);
            if (feature == null) { continue; }
            LAYOUTS.put(tile.coords(), BoardMultiHexBuilding.layout(scene, tile, feature));
        }
    }

    @Test
    void mutuallyConnectedJunctionHasNoTriangularCourtyard() {
        List<Coords> junction = List.of(new Coords(21, 11), new Coords(22, 11), new Coords(22, 12));
        Area covered = new Area();
        for (Coords at : junction) {
            for (var tunnel : LAYOUTS.get(at).tunnels()) {
                covered.add(area(tunnel.outline()).createTransformedArea(AffineTransform.getTranslateInstance(
                      HexTile.centerX(at, HexTile.MODEL_WIDTH), HexTile.centerY(at, HexTile.HEIGHT))));
            }
        }
        double x = junction.stream().mapToDouble(at -> HexTile.centerX(at, HexTile.MODEL_WIDTH)).average().orElseThrow();
        double y = junction.stream().mapToDouble(at -> HexTile.centerY(at, HexTile.HEIGHT)).average().orElseThrow();
        Area hole = new Area(new Rectangle2D.Double(x - 5, y - 5, 10, 10)); hole.subtract(covered);
        assertTrue(areaSize(hole) < .01, "2212, 2312 and 2313 must enclose their shared junction: " + areaSize(hole));
    }

    @Test
    void junctionAt2212RetainsExposedSupportsAcrossSharedCuts() {
        var tunnel = LAYOUTS.get(new Coords(21, 11)).tunnels().getFirst();
        var courtyard = tunnel.corners().stream().filter(corner ->
              Math.hypot(corner.at().x() - 41.5625, corner.at().y()) < 2).findFirst().orElseThrow();
        assertFalse(courtyard.external(), "A turn over the lower roof uses a white pillar");
        assertEquals(18, courtyard.bottom(), .001, "Its foot lamp must sit on the lower roof, not the hidden ground");
        var exterior = tunnel.corners().stream().filter(corner ->
              Math.hypot(corner.at().x() - 23.672792, corner.at().y() + 30.990892) < 2).findFirst().orElseThrow();
        assertTrue(exterior.external(), "The tall convex pillar meeting the straight exterior facade uses yellow lamps");
        assertEquals(0, exterior.bottom(), .001, "An exterior pillar reaches the building bottom, including its plinth");
        assertTrue(tunnel.corners().stream().noneMatch(corner -> Math.hypot(corner.at().x(), corner.at().y() - 36) < 2),
              "A straight shared cut must not become another corner pillar");
        double x = courtyard.at().x() + tunnel.origin().x(), y = courtyard.at().y() + tunnel.origin().y();
        long owners = LAYOUTS.values().stream().flatMap(layout -> layout.tunnels().stream())
              .filter(other -> Math.abs(other.top() - tunnel.top()) < .01)
              .flatMap(other -> other.corners().stream().filter(corner -> Math.hypot(
                    corner.at().x() + other.origin().x() - x, corner.at().y() + other.origin().y() - y) < .05)).count();
        assertEquals(1, owners, "The complete shared-cut pillar and its lamps must have exactly one owner");
    }

    @Test
    void tallStepPillarAt2413KeepsBothWallFacesAndReachesGround() {
        var corner = LAYOUTS.get(new Coords(23, 12)).tunnels().stream().flatMap(tunnel -> tunnel.corners().stream())
              .filter(pillar -> Math.hypot(pillar.at().x() + 38.670956, pillar.at().y() - 5.0091085) < 2)
              .findFirst().orElseThrow();
        assertTrue(corner.external());
        assertEquals(-Math.sqrt(3) / 2, corner.normal().x(), .002, "The housing retains the incoming wall face");
        assertEquals(.5, corner.normal().y(), .002);
        assertEquals(-.5, corner.side().x(), .002, "The other face follows the exterior wall rather than a 45-degree bisector");
        assertEquals(-Math.sqrt(3) / 2, corner.side().y(), .002);
        assertEquals(0, corner.bottom(), .001);
    }

    @Test
    void raisedCornerCutKeepsItsLowerFloorAndExposedGroundMarker() throws Exception {
        var layout = LAYOUTS.get(new Coords(23, 12));
        var tunnel = layout.tunnels().stream().filter(t -> t.corners().stream().anyMatch(c ->
              Math.hypot(c.at().x() + 38.670956, c.at().y() - 5.0091085) < 2)).findFirst().orElseThrow();
        var corner = tunnel.corners().stream().filter(c ->
              Math.hypot(c.at().x() + 38.670956, c.at().y() - 5.0091085) < 2).findFirst().orElseThrow();
        var cut = corner.chamfer();
        assertNotNull(cut, "The prominent2413 upper corner must be clipped even though the ground boundary is straight");
        assertEquals(18, cut.bottom(), .001);
        assertEquals(0, Math.min(cut.firstBottom(), cut.sideBottom()), .001);
        List<Vector3> lower = new ArrayList<>();
        for (var mesh : cap(tunnel).meshes) {
            for (var part : mesh.parts) {
                if (!part.id.equals(BoardMultiHexBuilding.ROOF)) { continue; }
                for (int i = 0; i < part.indices.length; i += 3) {
                    int[] indices = { Short.toUnsignedInt(part.indices[i]) * RigidGlb.STRIDE,
                          Short.toUnsignedInt(part.indices[i + 1]) * RigidGlb.STRIDE,
                          Short.toUnsignedInt(part.indices[i + 2]) * RigidGlb.STRIDE };
                    boolean onLowerRoof = true;
                    for (int at : indices) { onLowerRoof &= Math.abs(mesh.vertices[at + 2] - cut.bottom()) < .001; }
                    if (onLowerRoof) {
                        for (int at : indices) { lower.add(new Vector3(mesh.vertices[at], mesh.vertices[at + 1], mesh.vertices[at + 2])); }
                    }
                }
            }
        }
        Area restored = cut.removed(); restored.intersect(area(tunnel.outline()));
        assertTrue(areaSize(restored) > 1, "Exercise a real upper-tier cut");
        Area missing = new Area(restored); missing.subtract(GpuBuildingInterior.area(lower));
        missing.subtract(new Area(new BasicStroke(.03f).createStrokedShape(restored)));
        assertTrue(areaSize(missing) < .05, "The connected lower floor must remain beneath the clipped upper corner");
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("chamferPost", GeneratedMesh.class, List.class,
              BoardMultiHexBuilding.Corner.class, float.class);
        method.setAccessible(true);
        var post = new GeneratedMesh(); method.invoke(null, post, hex(), corner, tunnel.top() - 2.2f);
        var white = post.parts.get(BoardMultiHexBuilding.LIGHT);
        var groundNormal = cut.firstBottom() == 0 ? cut.normal() : cut.side();
        int markers = 0;
        for (int i = 0; i < white.size; i += RigidGlb.STRIDE) {
            var v = white.items;
            if (v[i + 2] > 2 || v[i + 3] * groundNormal.x() + v[i + 4] * groundNormal.y() < .99f) { continue; }
            assertTrue((v[i] - cut.at().x()) * groundNormal.x() + (v[i + 1] - cut.at().y()) * groundNormal.y() > .2,
                  "The ground marker must stand outside the restored original wall, not inside the upper diagonal cut");
            markers++;
        }
        assertTrue(markers >= 3);
    }

    @Test
    void flatSupportAt2313IsCentredBetweenTheTwoTallerSections() {
        var posts = LAYOUTS.get(new Coords(22, 12)).tunnels().stream().flatMap(tunnel -> tunnel.corners().stream())
              .filter(BoardMultiHexBuilding.Corner::flat).toList();
        var post = posts.stream().filter(pillar -> Math.hypot(pillar.at().x() + 7.5, pillar.at().y() + 12.99) < .1)
              .findFirst().orElseThrow();
        assertFalse(post.external(), "The centred flat support uses white lamps");
        assertEquals(post.normal(), post.side(), "A flat support has one exposed face");
        assertEquals(0, post.bottom());
        assertEquals(1, posts.size(), "The low frontage has one centred support, not a phase-dependent extra post near its end");
    }

    @Test
    void concaveGroundPillarAt2612ReachesGroundWithoutCrowdingTheSmallReturn() {
        var posts = LAYOUTS.get(new Coords(25, 11)).tunnels().stream().flatMap(tunnel -> tunnel.corners().stream()).toList();
        var inner = posts.stream().filter(pillar -> Math.hypot(pillar.at().x(), pillar.at().y() - 22.92) < .1)
              .findFirst().orElseThrow();
        assertFalse(inner.external(), "The substantial concave corner keeps white head lamps");
        assertEquals(0, inner.bottom(), .001, "A white column on the ground boundary also extends through the foundation");
        assertTrue(posts.stream().noneMatch(pillar -> Math.hypot(pillar.at().x() - 9.684, pillar.at().y() - 11.73) < .1),
              "The short 19-degree return beside the main corner must not add a crowded second column");
        assertTrue(posts.stream().anyMatch(pillar -> pillar.external()
              && Math.hypot(pillar.at().x() - 15, pillar.at().y() - 8.66) < .1), "Keep the substantial outer corner");
    }

    @Test
    void nearbyDisconnectedWingDoesNotRemoveAConcaveSupport() throws Exception {
        Area footprint = area(List.of(new BoardMultiHexBuilding.Point(-30, -20), new BoardMultiHexBuilding.Point(30, -20),
              new BoardMultiHexBuilding.Point(30, 0), new BoardMultiHexBuilding.Point(0, -5), new BoardMultiHexBuilding.Point(-30, 0)));
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("turns", Area.class);
        method.setAccessible(true);
        assertEquals(5, ((List<?>) method.invoke(null, footprint)).size(), "The gentle concave turn has no crowded neighbour");
        footprint.add(new Area(new Rectangle2D.Float(2, -2, 4, 4)));
        assertEquals(9, ((List<?>) method.invoke(null, footprint)).size(),
              "A corner across the gap adds four supports without removing the original wing's concave support");
    }

    @Test
    void corridorRoofPanelsKeepTheirScaleAndOrientationAcrossHexes() throws Exception {
        int checked = 0;
        for (Coords coords : List.of(new Coords(21, 11), new Coords(22, 11), new Coords(22, 12))) {
            for (var tunnel : LAYOUTS.get(coords).tunnels()) {
                for (var mesh : cap(tunnel).meshes) {
                    for (var part : mesh.parts) {
                        if (!part.id.equals(BoardMultiHexBuilding.ROOF)) { continue; }
                        for (short index : part.indices) {
                            int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                            var v = mesh.vertices;
                            assertEquals((v[at] + HexTile.centerX(coords, HexTile.MODEL_WIDTH)) / 9, v[at + 10], .0001,
                                  coords.getBoardNum() + " roof U must continue across the hex boundary");
                            assertEquals(-(v[at + 1] + HexTile.centerY(coords, HexTile.HEIGHT)) / 9, v[at + 11], .0001,
                                  coords.getBoardNum() + " roof V must use the same panel scale and orientation");
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 30);
    }

    @Test
    void domeRingPanelsFollowEachStraightBayWithoutStretchingOrRestartingAtHexCuts() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("podium", GeneratedMesh.class, List.class, BoardMultiHexBuilding.Dome.class);
        method.setAccessible(true);
        Map<String, float[]> seams = new HashMap<>();
        int checked = 0, shared = 0;
        for (var entry : LAYOUTS.entrySet()) {
            for (var dome : entry.getValue().domes()) {
                if (dome.radius() < 100) { continue; }
                var generated = new GeneratedMesh(); method.invoke(null, generated, hex(), dome);
                for (var mesh : generated.data("ring-roof", false).meshes) {
                    for (var part : mesh.parts) {
                        if (!part.id.equals(BoardMultiHexBuilding.ROOF)) { continue; }
                        for (int at = 0; at < part.indices.length; at += 3) {
                            int a = Short.toUnsignedInt(part.indices[at]) * RigidGlb.STRIDE;
                            int b = Short.toUnsignedInt(part.indices[at + 1]) * RigidGlb.STRIDE;
                            int c = Short.toUnsignedInt(part.indices[at + 2]) * RigidGlb.STRIDE;
                            float[] v = mesh.vertices;
                            if (v[a + 5] < .999f || v[b + 5] < .999f || v[c + 5] < .999f
                                  || Math.abs(v[a + 2] - dome.foot() - dome.height()) > .001f) { continue; }
                            var ab = new Vector3(v[b] - v[a], v[b + 1] - v[a + 1], v[b + 2] - v[a + 2]);
                            var ac = new Vector3(v[c] - v[a], v[c + 1] - v[a + 1], v[c + 2] - v[a + 2]);
                            if (ab.cpy().crs(ac).len2() < .001) { continue; }
                            float du1 = v[b + 10] - v[a + 10], dv1 = v[b + 11] - v[a + 11];
                            float du2 = v[c + 10] - v[a + 10], dv2 = v[c + 11] - v[a + 11];
                            float determinant = du1 * dv2 - du2 * dv1;
                            var u = ab.cpy().scl(dv2).mulAdd(ac, -dv1).scl(1 / determinant);
                            var w = ac.cpy().scl(du1).mulAdd(ab, -du2).scl(1 / determinant);
                            assertEquals(9, u.len(), .03, "Ring roof U must retain the panel scale");
                            assertEquals(9, w.len(), .03, "Ring roof V must retain the panel scale");
                            assertEquals(0, u.cpy().nor().dot(w.cpy().nor()), .002, "Ring panels must be square");
                            double angle = Math.atan2((v[a + 1] + v[b + 1] + v[c + 1]) / 3 - dome.y(),
                                  (v[a] + v[b] + v[c]) / 3 - dome.x());
                            int bay = (int) Math.floor((angle + 2 * Math.PI) % (2 * Math.PI) / (Math.PI / 6));
                            double direction = Math.PI / 6 * (bay + .5);
                            assertEquals(-Math.sin(direction), u.x / u.len(), .002);
                            assertEquals(Math.cos(direction), u.y / u.len(), .002);
                            for (int index : new int[] { a, b, c }) {
                                String key = bay + ":" + Math.round((v[index] + HexTile.centerX(entry.getKey(), HexTile.MODEL_WIDTH)) * 1000)
                                      + ":" + Math.round((v[index + 1] + HexTile.centerY(entry.getKey(), HexTile.HEIGHT)) * 1000);
                                float[] before = seams.putIfAbsent(key, new float[] { v[index + 10], v[index + 11], entry.getKey().getX(), entry.getKey().getY() });
                                if (before != null && (before[2] != entry.getKey().getX() || before[3] != entry.getKey().getY())) {
                                    assertEquals(before[0], v[index + 10], .0001); assertEquals(before[1], v[index + 11], .0001); shared++;
                                }
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 50); assertTrue(shared > 10, "Check the ring across shared hex cuts");
    }

    @Test
    void windowsOnHeightStepsStayInsideTheirHexAndAboveTheLowerRoof() throws Exception {
        int checked = 0;
        for (var tunnel : LAYOUTS.get(new Coords(21, 11)).tunnels()) {
            for (int edge = 0; edge < tunnel.outline().size(); edge++) {
                float lower = tunnel.openings().get(edge);
                if (lower <= 0 || lower >= tunnel.top() - 7) { continue; }
                var a = tunnel.outline().get(edge); var b = tunnel.outline().get((edge + 1) % tunnel.outline().size());
                float length = (float) Math.hypot(b.x() - a.x(), b.y() - a.y());
                float nx = (b.y() - a.y()) / length, ny = -(b.x() - a.x()) / length;
                for (var mesh : cap(tunnel).meshes) {
                    for (var part : mesh.parts) {
                        if (!part.id.equals(BoardMultiHexBuilding.WINDOW)) { continue; }
                        for (short index : part.indices) {
                            int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE; var v = mesh.vertices;
                            if (v[at + 3] * nx + v[at + 4] * ny < .999f) { continue; }
                            assertTrue((v[at] - a.x()) * nx + (v[at + 1] - a.y()) * ny < 0, "Step windows are recessed into the owning hex");
                            assertTrue(v[at + 2] > lower && v[at + 2] < tunnel.top());
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 12, "The exposed upper wall retains its pressure windows");
    }

    @Test
    void gateMouthIsRecessedInsideTheBuildingOnTheRoadCentreline() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("door", BoardMultiHexBuilding.Layout.class,
              float.class, float.class, float.class, float.class, float.class);
        method.setAccessible(true);
        int checked = 0;
        for (var entry : LAYOUTS.entrySet()) {
            var layout = entry.getValue();
            for (var gate : layout.corridorGates()) {
                float[] door = (float[]) method.invoke(null, layout, 0f, 0f, gate.ux(), gate.uy(), 12f);
                assertNotNull(door, entry.getKey().getBoardNum() + " road gate needs a usable corridor attachment");
                assertEquals(0, door[0] * gate.uy() - door[1] * gate.ux(), .001f, "Keep the road centreline at corners too");
                assertEquals(gate.ux(), door[2], .001f); assertEquals(gate.uy(), door[3], .001f);
                assertTrue(door[5] - door[4] <= 14, "The gate enters one storey, even beside a tall tower");
                Area inside = new Area();
                for (var tunnel : layout.tunnels()) { inside.add(area(tunnel.outline())); }
                for (float across = -11.6f; across <= 11.6f; across += .5f) {
                    assertTrue(inside.contains(door[0] - door[2] * 6 - door[3] * across,
                          door[1] - door[3] * 6 + door[2] * across), entry.getKey().getBoardNum()
                                + " whole rear collar must be embedded inside the building");
                }
                checked++;
            }
        }
        assertTrue(checked >= 3);
    }

    @Test
    void gateAt2413EntersStraightThroughTheCorner() throws Exception {
        var layout = LAYOUTS.get(new Coords(23, 12));
        var gate = layout.corridorGates().getFirst();
        var attachment = BoardMultiHexBuilding.class.getDeclaredMethod("door", BoardMultiHexBuilding.Layout.class,
              float.class, float.class, float.class, float.class, float.class);
        attachment.setAccessible(true);
        float[] from = (float[]) attachment.invoke(null, layout, 0f, 0f, gate.ux(), gate.uy(), 12f);
        float[] to = { gate.x() - gate.ux() * 9, gate.y() - gate.uy() * 9, gate.ux(), gate.uy(), gate.floor(), gate.floor() + 14 };
        assertEquals(0, from[0], .001f, "2413's passage must remain centred on its corner and road");
        assertEquals(0, from[2], .001f); assertEquals(-1, from[3], .001f);
        var profile = BoardMultiHexBuilding.class.getDeclaredMethod("passageProfile", float[].class, float[].class, float.class, float.class);
        profile.setAccessible(true);
        for (float t : new float[] { 0, .25f, .5f, .75f, 1 }) {
            float[][] points = (float[][]) profile.invoke(null, from, to, t, 0f);
            assertEquals(21, Math.abs(points[0][0] - points[points.length - 1][0]), .001f, "Full-width straight passage");
            for (int i = 0; i < points.length; i++) {
                float[] opposite = points[points.length - 1 - i];
                assertEquals(-points[i][0], opposite[0], .001f, "No bend or sideways shift into a neighbouring facade");
                assertEquals(points[i][1], opposite[1], .001f);
                assertEquals(points[i][2], opposite[2], .001f);
            }
        }
    }
    @Test
    void lowSideBranchesDoNotLowerTheTallerDockingCorridor() {
        // 2407 and 2413 have two-storey corridors into the dome, with one-storey spurs beside them.
        for (Coords at : List.of(new Coords(23, 7), new Coords(23, 11))) {
            var middle = LAYOUTS.get(at).tunnels().stream().filter(t -> area(t.outline()).contains(0, 0)).findFirst().orElseThrow();
            assertEquals(2 * GpuBuilding.LEVEL_HEIGHT, middle.top(), .001f, at.getBoardNum());
        }
    }

    @Test
    void corridorCutsOpenOnlyAsHighAsTheActualNeighbouringRoof() {
        int checked = 0;
        for (var entry : LAYOUTS.entrySet()) {
            Coords at = entry.getKey();
            for (var tunnel : entry.getValue().tunnels()) {
                for (int edge = 0; edge < tunnel.outline().size(); edge++) {
                    float opening = tunnel.openings().get(edge);
                    if (opening <= 0) { continue; }
                    var a = tunnel.outline().get(edge); var b = tunnel.outline().get((edge + 1) % tunnel.outline().size());
                    float length = (float) Math.hypot(b.x() - a.x(), b.y() - a.y());
                    double x = (a.x() + b.x()) / 2 + (b.y() - a.y()) / length * .02;
                    double y = (a.y() + b.y()) / 2 - (b.x() - a.x()) / length * .02;
                    double neighbouringRoof = 0;
                    for (int direction = 0; direction < 6; direction++) {
                        Coords next = at.translated(direction);
                        var neighbour = LAYOUTS.get(next);
                        if (neighbour == null) { continue; }
                        double nx = x + HexTile.centerX(at, HexTile.MODEL_WIDTH) - HexTile.centerX(next, HexTile.MODEL_WIDTH);
                        double ny = y + HexTile.centerY(at, HexTile.HEIGHT) - HexTile.centerY(next, HexTile.HEIGHT);
                        for (var beyond : neighbour.tunnels()) {
                            if (area(beyond.outline()).contains(nx, ny)) { neighbouringRoof = Math.max(neighbouringRoof, beyond.top()); }
                        }
                    }
                    assertTrue(opening <= neighbouringRoof + .01, at.getBoardNum() + " leaves an opening up to " + opening
                          + " above its neighbour's roof at " + neighbouringRoof);
                    checked++;
                }
            }
        }
        assertTrue(checked > 30, "Check all of the biodome's corridor joins");
    }

    @Test
    void corridorCapsCoverTheirFootprintsWithoutFoldedOrOverlappingBevels() throws Exception {
        for (var entry : LAYOUTS.entrySet()) {
            for (var tunnel : entry.getValue().tunnels()) {
                var data = cap(tunnel);
                List<Vector3> roof = new ArrayList<>(); double total = 0;
                for (var mesh : data.meshes) {
                    for (var part : mesh.parts) {
                        // Inset fittings and fastener heads deliberately overlay the structural skin.
                        if (!part.id.equals(BoardMultiHexBuilding.ROOF) && !part.id.equals(BoardMultiHexBuilding.PIER)) { continue; }
                        for (int at = 0; at < part.indices.length; at += 3) {
                            int ia = Short.toUnsignedInt(part.indices[at]) * RigidGlb.STRIDE;
                            int ib = Short.toUnsignedInt(part.indices[at + 1]) * RigidGlb.STRIDE;
                            int ic = Short.toUnsignedInt(part.indices[at + 2]) * RigidGlb.STRIDE;
                            float[] v = mesh.vertices;
                            if (v[ia + 5] <= 0 || v[ib + 5] <= 0 || v[ic + 5] <= 0) { continue; }
                            // The solid wall backing has a horizontal cap below the bevel; it is not part of the roof.
                            if (v[ia + 5] > .999f && v[ib + 5] > .999f && v[ic + 5] > .999f
                                  && v[ia + 2] < tunnel.top() - .001f) { continue; }
                            for (int start : new int[] { ia, ib, ic }) { roof.add(new Vector3(v[start], v[start + 1], v[start + 2])); }
                            var a = roof.get(roof.size() - 3); var b = roof.get(roof.size() - 2); var c = roof.getLast();
                            total += Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2;
                        }
                    }
                }
                Area footprint = area(tunnel.outline()), drawn = GpuBuildingInterior.area(roof);
                for (var cut : tunnel.chamfers()) { footprint.subtract(cut.removed()); }
                Area difference = new Area(drawn); difference.exclusiveOr(footprint);
                difference.subtract(new Area(new BasicStroke(.03f).createStrokedShape(footprint)));
                // Triangle welding rounds to .001 model units; tolerate only the resulting hairline differences.
                assertTrue(areaSize(difference) < .05, entry.getKey().getBoardNum() + " has a hole or a bevel outside its walls: " + areaSize(difference));
                assertEquals(areaSize(footprint), total, .3, entry.getKey().getBoardNum() + " has overlapping roof triangles");
            }
        }
    }

    private static RigidGlb.Data cap(BoardMultiHexBuilding.Tunnel tunnel) throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("corridorBlock", GeneratedMesh.class, BoardMultiHexBuilding.Tunnel.class);
        method.setAccessible(true);
        var mesh = new GeneratedMesh();
        method.invoke(null, mesh, tunnel);
        return mesh.data("corridor-cap", false);
    }

    private static List<float[]> hex() {
        List<float[]> hex = new ArrayList<>();
        for (int corner = 0; corner < 6; corner++) {
            hex.add(new float[] { HexTile.cornerOffsetX(corner, HexTile.MODEL_WIDTH), HexTile.cornerOffsetY(corner, HexTile.HEIGHT) });
        }
        return hex;
    }

    private static Area area(List<BoardMultiHexBuilding.Point> outline) {
        Path2D.Float path = new Path2D.Float(); path.moveTo(outline.getFirst().x(), outline.getFirst().y());
        for (var point : outline) { path.lineTo(point.x(), point.y()); }
        path.closePath(); return new Area(path);
    }

    private static double polygonArea(List<BoardMultiHexBuilding.Point> outline) {
        double sum = 0;
        for (int index = 0; index < outline.size(); index++) {
            var a = outline.get(index); var b = outline.get((index + 1) % outline.size());
            sum += a.x() * b.y() - a.y() * b.x();
        }
        return Math.abs(sum) / 2;
    }

    private static double areaSize(Area area) {
        double sum = 0, x = 0, y = 0, firstX = 0, firstY = 0;
        double[] point = new double[6];
        for (var path = area.getPathIterator(null); !path.isDone(); path.next()) {
            int kind = path.currentSegment(point);
            if (kind == PathIterator.SEG_MOVETO) { firstX = x = point[0]; firstY = y = point[1]; }
            else {
                double nx = kind == PathIterator.SEG_CLOSE ? firstX : point[0], ny = kind == PathIterator.SEG_CLOSE ? firstY : point[1];
                sum += x * ny - y * nx; x = nx; y = ny;
            }
        }
        return Math.abs(sum) / 2;
    }
}
