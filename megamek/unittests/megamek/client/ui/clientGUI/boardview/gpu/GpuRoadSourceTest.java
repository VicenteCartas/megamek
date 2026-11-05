/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuRoadSourceTest {
    @Test
    void roadEndCoversTheTerrainRimBeyondItsHex() {
        var scene = GpuRailTessellationSmokeTest.scene(true);
        var source = scene.tile(3, 4);
        var road = BoardRoad.of(scene, source);
        var pavement = GpuRoads.patches(source, road).stream()
              .filter(p -> p.texture().equals("roads/asphalt") && p.lift() == GpuRoads.SURFACE_LIFT)
              .findFirst().orElseThrow();
        road.clipPaint(pavement.shape());
        for (var lod : TerrainLod.values()) {
            var supportingFaces = new ArrayList<BoardSurface.Face>();
            var triangles = new ArrayList<BoardTacticalGeometry.Triangle>();
            // Coarser rims can move the same support into a neighbouring hex's mesh.
            for (int d = -1; d < 6; d++) {
                var tile = d < 0 ? source : scene.neighbor(source, d);
                var surface = new BoardSurface(scene, tile, lod);
                supportingFaces.addAll(surface.groundFaces());
                supportingFaces.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
                triangles.addAll(GpuRoads.drape(source, surface, pavement));
            }
            for (float x : new float[] { 253, 255, 257 }) {
                // The reported notch lies on the rim of 0505, inside 0405's rounded road end.
                float y = -330;
                assertTrue(pavement.shape().contains(x - BoardGeometry.centerX(source.coords()),
                      y - BoardGeometry.centerY(source.coords())));
                float support = BoardSurface.sampleHeight(supportingFaces, x, y, Float.NaN);
                assertTrue(Float.isFinite(support), "The road end has existing terrain support at " + lod);
                var ray = new Ray(new Vector3(x, y, 200), new Vector3(0, 0, -1));
                var hit = new Vector3();
                double painted = triangles.stream().mapToDouble(t -> Intersector.intersectRayTriangle(ray,
                      t.a(), t.b(), t.c(), hit) ? hit.z : Double.NEGATIVE_INFINITY).max().orElseThrow();
                assertEquals(support + pavement.lift() * BoardGeometry.hexScale(), painted, .001,
                      "Pavement must cover the rim at " + x + "," + y + " / " + lod);
            }
        }
    }

    @Test
    void neighbouringSlabSidesKeepTheirSourcePositionAndStopAtTheReceivingHex() {
        var scene = BoardScenes.scene(at -> BoardScenes.tile(at, BoardRoad.Kind.PAVED, 9, 0,
              BoardScene.Surface.GRASS));
        var source = scene.tile(new Coords(3, 3));
        var road = BoardRoad.of(scene, source);
        var texture = mock(Texture.class);
        when(texture.getWidth()).thenReturn(16);
        when(texture.getHeight()).thenReturn(16);
        float scale = BoardGeometry.hexScale(), half = 15;
        var origin = BoardGeometry.center(source.coords(), 0);
        for (int direction = 0; direction < 6; direction++) {
            var receiving = scene.neighbor(source, direction);
            var centre = BoardGeometry.center(receiving.coords(), 0);
            var axis = new Vector3(centre).sub(origin);
            float end = axis.len() / scale / 2 + 1;
            axis.nor();
            var across = new Vector3(-axis.y, axis.x, 0);
            List<Vector3> outline = new ArrayList<>();
            var path = new Path2D.Float();
            for (float[] corner : new float[][] { { -10, -half }, { end, -half }, { end, half }, { -10, half } }) {
                var local = new Vector3(axis).scl(corner[0]).mulAdd(across, corner[1]);
                if (outline.isEmpty()) { path.moveTo(local.x, local.y); } else { path.lineTo(local.x, local.y); }
                outline.add(new Vector3(local).scl(scale).add(origin));
            }
            path.closePath();
            var patch = new GpuRoads.Patch(new Area(path), GpuRoads.SLABS, Color.WHITE, 1, GpuRoads.SLAB_RISE, null);
            var surface = new BoardSurface(scene, receiving);
            var triangles = GpuRoads.drape(source, surface, patch);
            assertFalse(triangles.isEmpty(), "The road extends one unit onto its neighbour");
            var data = GpuRoads.mask(road, patch);
            var shifted = new GpuRoads.MaskData(data.pixels(), data.x() + (origin.x - centre.x) / scale,
                  data.y() + (origin.y - centre.y) / scale, data.width(), data.height());
            var mask = new GpuRoads.Mask(new TextureRegion(texture), shifted);
            var hex = new Path2D.Float();
            for (int corner = 0; corner < 6; corner++) {
                var point = BoardGeometry.corner(receiving.coords(), 0, corner);
                if (corner == 0) { hex.moveTo(point.x, point.y); } else { hex.lineTo(point.x, point.y); }
            }
            hex.closePath();
            Area bounds = new Area(hex);
            bounds.add(new Area(new BasicStroke(.005f * scale).createStrokedShape(hex)));
            for (boolean tessellated : new boolean[] { false, true }) {
                var attributes = tessellated ? GpuRoads.TESSELLATED_VERTICES : GpuRoads.VERTICES;
                var mesh = new MeshBuilder();
                mesh.begin(attributes, GL20.GL_TRIANGLES);
                GpuRoads.write(() -> mesh, triangles, patch, mask, surface, source.coords(), false, 0, 1, tessellated, null);
                int stride = attributes.vertexSize / Float.BYTES;
                float[] vertices = new float[mesh.getNumVertices() * stride];
                short[] indices = new short[mesh.getNumIndices()];
                mesh.getVertices(vertices, 0); mesh.getIndices(indices, 0);
                double terminalArea = 0;
                int sides = 0;
                for (int index = 0; index < indices.length; index += 3) {
                    List<Vector3> points = new ArrayList<>();
                    boolean vertical = true;
                    for (int corner = 0; corner < 3; corner++) {
                        int v = Short.toUnsignedInt(indices[index + corner]) * stride;
                        vertical &= Math.abs(vertices[v + 5]) < .001f;
                        points.add(new Vector3(vertices[v], vertices[v + 1], vertices[v + 2]));
                    }
                    if (!vertical) { continue; }
                    sides++;
                    for (var point : points) {
                        assertTrue(bounds.contains(point.x, point.y), "A neighbour's patch emits sides only in its receiving hex");
                    }
                    boolean originalEdge = false;
                    for (int edge = 0; edge < outline.size(); edge++) {
                        var a = outline.get(edge); var b = outline.get((edge + 1) % outline.size());
                        originalEdge |= points.stream().allMatch(p -> Line2D.ptSegDist(a.x, a.y, b.x, b.y, p.x, p.y) < .005f * scale);
                    }
                    assertTrue(originalEdge, "Slab sides keep the source outline; the hex cut creates no extra end wall");
                    if (points.stream().allMatch(p -> Math.abs(new Vector3(p).sub(origin).dot(axis) / scale - end) < .005f)) {
                        terminalArea += new Vector3(points.get(1)).sub(points.get(0))
                              .crs(new Vector3(points.get(2)).sub(points.get(0))).len() / 2;
                    }
                }
                assertTrue(sides > 0, "The small overlap retains its actual slab side faces");
                assertEquals(2 * half * scale * 1.6f * GpuRoads.SLAB_RISE * scale, terminalArea, .02f,
                      "The real terminal cap is kept once at the source road's end");
            }
        }
    }

    @Test
    void tessellatedRoadsUseTheirSupportingControlTriangles() throws Exception {
        var scene = scene("unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        var texture = mock(Texture.class);
        when(texture.getWidth()).thenReturn(16);
        when(texture.getHeight()).thenReturn(16);
        int checked = 0;
        for (var at : List.of(new Coords(0, 6), new Coords(1, 6), new Coords(0, 7))) {
            var tile = scene.tile(at);
            var surface = new BoardSurface(scene, tile, TerrainLod.FULL);
            surface.walls(scene, BoardGeometry.floor(scene));
            var supportFaces = new ArrayList<>(surface.groundFaces());
            supportFaces.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
            var road = BoardRoad.of(scene, tile);
            for (var patch : GpuRoads.patches(tile, road)) {
                var triangles = GpuRoads.drape(tile, surface, patch);
                var sources = new HashMap<BoardTacticalGeometry.Triangle, BoardSurface.Face>();
                float lift = patch.lift() * BoardGeometry.hexScale();
                for (var face : supportFaces) {
                    sources.put(new BoardTacticalGeometry.Triangle(new Vector3(face.a()).add(0, 0, lift),
                          new Vector3(face.b()).add(0, 0, lift), new Vector3(face.c()).add(0, 0, lift), -1), face);
                }
                var mask = new GpuRoads.Mask(new TextureRegion(texture), GpuRoads.mask(road, patch));
                var mesh = new MeshBuilder();
                mesh.begin(GpuRoads.TESSELLATED_VERTICES, GL20.GL_TRIANGLES);
                GpuRoads.write(() -> mesh, triangles, patch, mask, surface, tile.coords(), false, 0, 1, true, null);
                int stride = GpuRoads.TESSELLATED_VERTICES.vertexSize / Float.BYTES;
                float[] vertices = new float[mesh.getNumVertices() * stride];
                mesh.getVertices(vertices, 0);
                for (int offset = 0; offset < vertices.length; offset += stride) {
                    float[] v = Arrays.copyOfRange(vertices, offset, offset + stride);
                    var face = sources.get(triangles.get(offset / stride / 3));
                    assertTrue(face != null, "The coat must use an original supporting triangle");
                    var point = List.of(face.a(), face.b(), face.c()).get(offset / stride % 3);
                    var support = GpuTerrain.sculptVertex(point, surface.relief.shade(point), Float.NaN, surface);
                    assertEquals(point, new Vector3(v[0], v[1], v[2]), "Original control position before lift");
                    String contact = "Support at " + support.position + " in " + at + " / " + patch.texture();
                    assertEquals(support.normal.x, v[3], contact);
                    assertEquals(support.normal.y, v[4], contact);
                    assertEquals(support.normal.z, v[5], contact);
                    // The shape payload follows the existing road mask and atlas coordinates: the ground's own inputs.
                    int shape = stride - 5;
                    assertEquals(support.color.toFloatBits(), v[shape]);
                    assertEquals(support.uv.x, v[shape + 1]);
                    assertEquals(support.uv.y, v[shape + 2]);
                    assertEquals(patch.lift() * BoardGeometry.hexScale(), v[shape + 3], .00001f,
                          "Clearance is applied after both patches evaluate the same surface");
                    assertEquals(surface.fixedTessellation(point) ? 1 : 0, v[shape + 4], contact + ": fixed as its ground");
                    checked++;
                }
                float[] original = vertices.clone();
                var relocated = new GpuRoads.Mask(new TextureRegion(texture, 4, 4, 8, 8), mask.data);
                GpuRoads.relocate(vertices, relocated, GpuRoads.TESSELLATED_VERTICES);
                int region = GpuRoads.VERTICES.vertexSize / Float.BYTES - 4;
                float[] placement = { relocated.offsetU, relocated.offsetV, relocated.scaleU, relocated.scaleV };
                for (int i = 0; i < vertices.length; i++) {
                    int field = i % stride - region;
                    assertEquals(field >= 0 && field < 4 ? placement[field] : original[i], vertices[i],
                          "An edited road atlas must relocate every coat without corrupting its shape inputs");
                }
            }
        }
        assertTrue(checked > 100, "Exercise the reported road bends and their edge triangles");
    }

    /** A drawn ground corner's tessellation inputs. */
    private record Corner(Vector3 normal, float color, float u, float v) { }

    @Test
    void tessellatedRoadsFollowTheGroundsSplitTriangles() throws Exception {
        var texture = mock(Texture.class);
        when(texture.getWidth()).thenReturn(16);
        when(texture.getHeight()).thenReturn(16);
        int pieces = 0;
        for (String board : List.of("unofficial/Drewbacca/16x17 Fire And Ice 2.board",
              "AGoAC Maps/16x17 Grassland 2 (Low Bridges).board")) {
            var scene = scene(board);
            Map<Coords, BoardSurface> surfaces = new HashMap<>();
            for (int x = 0; x < scene.width(); x++) {
                for (int y = 0; y < scene.height(); y++) {
                    surfaces.put(new Coords(x, y), new BoardSurface(scene, scene.tile(x, y), TerrainLod.FULL));
                }
            }
            for (var surface : List.copyOf(surfaces.values())) {
                var tile = surface.tile;
                if (!BoardRoad.rendered(tile) || !surface.relief.sculpted()) { continue; }
                var plan = GpuTerrain.prepareSculpt(scene, tile, surface, BoardGeometry.floor(scene), TerrainLod.FULL,
                      surfaces);
                // The ground as drawn: the blend plan's triangles (its cache tops too), and the other sculpted faces as
                // their mesh writes them.
                Map<List<Vector3>, List<Corner>> ground = new HashMap<>();
                for (var group : Stream.concat(plan.blended().values().stream(),
                      plan.blendedTops().values().stream()).toList()) {
                    for (var t : group) {
                        List<Corner> corners = new ArrayList<>();
                        for (var v : List.of(t.a().vertex(), t.b().vertex(), t.c().vertex())) {
                            corners.add(new Corner(v.normal, v.color.toFloatBits(), v.uv.x, v.uv.y));
                        }
                        ground.put(List.of(t.a().vertex().position, t.b().vertex().position, t.c().vertex().position),
                              corners);
                    }
                }
                var layout = MeshBuilder.createAttributes(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
                      | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates);
                var drawn = new MeshBuilder();
                drawn.begin(layout, GL20.GL_TRIANGLES);
                GpuTerrain.sculptedFaces(() -> drawn, surface, plan.formed(), tile.liquid().present()
                      ? GpuWaterShader.palette(tile.liquid()) : Float.NaN, scene, surfaces);
                int size = layout.vertexSize / Float.BYTES, normal = layout.getOffset(VertexAttributes.Usage.Normal);
                int color = layout.getOffset(VertexAttributes.Usage.ColorPacked);
                int uv = layout.getOffset(VertexAttributes.Usage.TextureCoordinates);
                float[] floats = new float[drawn.getNumVertices() * size];
                short[] indices = new short[drawn.getNumIndices()];
                drawn.getVertices(floats, 0);
                drawn.getIndices(indices, 0);
                for (int i = 0; i < indices.length; i += 3) {
                    List<Vector3> positions = new ArrayList<>();
                    List<Corner> corners = new ArrayList<>();
                    for (int k = 0; k < 3; k++) {
                        int v = Short.toUnsignedInt(indices[i + k]) * size;
                        positions.add(new Vector3(floats[v], floats[v + 1], floats[v + 2]));
                        corners.add(new Corner(new Vector3(floats[v + normal], floats[v + normal + 1],
                              floats[v + normal + 2]), floats[v + color], floats[v + uv], floats[v + uv + 1]));
                    }
                    ground.put(positions, corners);
                }
                // Faces the ground does not draw whole, and the pieces it draws instead.
                Set<List<Vector3>> split = new HashSet<>(), whole = new HashSet<>();
                Set<List<Vector3>> parts = new HashSet<>(ground.keySet());
                for (var face : plan.formed()) {
                    var corners = List.of(face.a(), face.b(), face.c());
                    if (ground.containsKey(corners)) { whole.add(corners); } else { split.add(corners); }
                }
                plan.supports().forEach((face, vertices) -> {
                    var corners = List.of(face.a(), face.b(), face.c());
                    if (vertices.size() > 3) { split.add(corners); } else { whole.add(corners); }
                });
                parts.removeAll(whole);
                var road = BoardRoad.of(scene, tile);
                for (var patch : GpuRoads.patches(tile, road)) {
                    var mask = new GpuRoads.Mask(new TextureRegion(texture), GpuRoads.mask(road, patch));
                    var mesh = new MeshBuilder();
                    mesh.begin(GpuRoads.TESSELLATED_VERTICES, GL20.GL_TRIANGLES);
                    GpuRoads.write(() -> mesh, GpuRoads.drape(tile, surface, patch), patch, mask, surface, tile.coords(), false, 0, 1,
                          true, GpuTerrain.coatSupports(plan, surface, scene, surfaces));
                    int stride = GpuRoads.TESSELLATED_VERTICES.vertexSize / Float.BYTES, shape = stride - 5;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(vertices, 0);
                    for (int offset = 0; offset < vertices.length; offset += 3 * stride) {
                        List<Vector3> positions = new ArrayList<>();
                        for (int k = 0; k < 3; k++) {
                            int v = offset + k * stride;
                            positions.add(new Vector3(vertices[v], vertices[v + 1], vertices[v + 2]));
                        }
                        String at = board + " " + tile.coords() + " / " + patch.texture() + " " + positions;
                        assertFalse(split.contains(positions), "A coat must not span a face its ground splits: " + at);
                        var corners = ground.get(positions);
                        if (corners == null) { continue; }
                        if (parts.contains(positions)) { pieces++; }
                        for (int k = 0; k < 3; k++) {
                            int v = offset + k * stride;
                            var corner = corners.get(k);
                            var drawnNormal = new Vector3(vertices[v + 3], vertices[v + 4], vertices[v + 5]);
                            assertEquals(corner.normal(), drawnNormal, at);
                            assertEquals(corner.color(), vertices[v + shape], at);
                            assertEquals(corner.u(), vertices[v + shape + 1], at);
                            assertEquals(corner.v(), vertices[v + shape + 2], at);
                            assertEquals(surface.fixedTessellation(positions.get(k)) ? 1 : 0, vertices[v + shape + 4], at);
                        }
                    }
                }
            }
        }
        assertTrue(pieces > 0, "Exercise coats over the ground's split triangles");
    }

    @Test
    void forestsEndRoadsReachBothEndsOfTheBridgeAtEveryDetail() throws Exception {
        var scene = scene("unofficial/Strategoslevel3/32x17 (CW) Forests End  - Road.board");
        var bridge = scene.tile(new Coords(8, 8));
        assertEquals(36, bridge.features().stream().filter(f -> f.asset().equals("bridge"))
              .findFirst().orElseThrow().bridgeExits());
        for (int direction : new int[] { 2, 5 }) {
            var tile = scene.tile(bridge.coords().translated(direction));
            assertTrue(BoardRoad.rendered(tile));
            assertEquals(36, tile.roadExits());
            var center = BoardGeometry.center(tile.coords(), tile.elevation());
            var gate = BoardGeometry.center(bridge.coords(), bridge.elevation()).lerp(center, .5f);
            var inward = new Vector3(center).sub(gate).nor();
            var across = new Vector3(-inward.y, inward.x, 0);
            int edge = Math.floorMod(1 - direction, 6);
            var step = BoardGeometry.corner(bridge.coords(), 0, edge + 1)
                  .sub(BoardGeometry.corner(bridge.coords(), 0, edge));
            step.scl(1 / step.dot(across));
            var road = BoardRoad.of(scene, tile);
            var pavement = GpuRoads.patches(tile, road).stream()
                  .filter(p -> p.texture().equals("roads/asphalt") && p.lift() == GpuRoads.SURFACE_LIFT)
                  .findFirst().orElseThrow();
            for (var lod : TerrainLod.values()) {
                var surfaces = new HashMap<Coords, BoardSurface>();
                surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod));
                var footing = BoardBridgeFooting.build(scene, bridge, lod, surfaces);
                var triangles = GpuRoads.drape(tile, surfaces.get(tile.coords()), pavement);
                // The bridge's bank landing spans any recessed shore before it meets the road carrier.
                triangles.addAll(GpuRoads.deck(pavement, footing.shape(), GpuRoads.SURFACE_LIFT));
                for (float lateral : new float[] { -7, -6, 0, 6, 7 }) {
                    for (float distance : new float[] { .05f, 1, 3, 6, 10 }) {
                        var point = new Vector3(gate).mulAdd(step, lateral).mulAdd(inward, distance);
                        assertTrue(pavement.shape().contains((point.x - center.x) / BoardGeometry.hexScale(),
                              (point.y - center.y) / BoardGeometry.hexScale()));
                        var ray = new Ray(new Vector3(point.x, point.y, 200), new Vector3(0, 0, -1));
                        var hit = new Vector3();
                        double height = triangles.stream().mapToDouble(t -> Intersector.intersectRayTriangle(ray,
                              t.a(), t.b(), t.c(), hit) ? hit.z : Double.NEGATIVE_INFINITY).max().orElseThrow();
                        assertTrue(Double.isFinite(height),
                              tile.coords().getBoardNum() + " " + lod + " road missing at " + point);
                        if (distance == .05f) {
                            // Deck coats sit .01 hex units above their structural landing to avoid z-fighting.
                            float deckCoat = (pavement.lift() + .01f) * BoardGeometry.hexScale();
                            assertEquals(deckCoat, height, .001f,
                                  tile.coords().getBoardNum() + " " + lod + " road must meet the level deck");
                        }
                    }
                }
            }
        }
    }

    @Test
    void lavaTubesBridgeFloorsKeepTheNativeVolcanicGround() throws Exception {
        var scene = scene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        var bridges = scene.tiles().stream().filter(t -> t.features().stream().anyMatch(f -> f.asset().equals("bridge")))
              .toList();
        assertEquals(4, bridges.size());
        for (var tile : bridges) {
            assertTrue(tile.detailedGround(), "Native ground beneath bridge " + tile.coords().getBoardNum());
            assertEquals(BoardScene.Surface.VOLCANO, tile.surface());
            assertFalse(tile.liquid().present());
            assertEquals(-2, tile.elevation());
        }
    }

    @Test
    void minesCapturesEveryAuthoredRoadExitWithDesertGroundUnderTheCurves() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 Mines 1.board"));
        BoardScene scene = minesScene();
        int roads = 0, decorated = 0;
        for (var tile : scene.tiles()) {
            Hex hex = board.getHex(tile.coords());
            if (!hex.containsTerrain(Terrains.ROAD)) { continue; }
            roads++;
            if (hex.containsTerrain(Terrains.ROAD_FLUFF)) { decorated++; }
            assertEquals(BoardRoad.Kind.PAVED, tile.road(), tile.coords().getBoardNum());
            assertEquals(hex.getTerrain(Terrains.ROAD).getExits(), tile.roadExits(), tile.coords().getBoardNum());
            assertTrue(BoardRoad.rendered(tile), "Native road including curved artwork: " + tile.coords().getBoardNum());
            assertEquals(BoardScene.Surface.DESERT, tile.surface(), "Desert ground: " + tile.coords().getBoardNum());
        }
        assertEquals(30, roads);
        assertEquals(9, decorated);
    }

    @Test
    void referenceRoadsClearTheirTreesWallOnlySlopesTheyCutAndNeverFoldTheirGround() throws Exception {
        var scene = scene("unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        for (var tile : scene.tiles()) {
            if (!BoardRoad.rendered(tile)) { continue; }
            // Capture places scenery clear of the same bent course the scene gives the road.
            var road = BoardRoad.of(scene, tile);
            for (var feature : tile.features()) {
                if (feature.kind() != BoardScene.FeatureKind.TREE) { continue; }
                assertTrue(road.distance(feature.x(), feature.y()) >= BoardRoad.SHOULDER + 2 - .01f,
                      "A tree stands off the road in " + tile.coords().getBoardNum());
            }
            var surface = new BoardSurface(scene, tile);
            for (var face : surface.groundFaces()) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                float up = (face.b().x - face.a().x) * (face.c().y - face.a().y) - (face.b().y - face.a().y) * (face.c().x - face.a().x);
                float rise = Math.max(face.a().z, Math.max(face.b().z, face.c().z))
                      - Math.min(face.a().z, Math.min(face.b().z, face.c().z));
                // A folded slope stands up as a fin. Small slivers can still fold where two rims meet at a corner;
                // they stay well below a visible fin (see docs/gpu-road-slopes-tunnels.md).
                assertTrue(up > -1e-4f || rise < BoardRelief.metres(.3f),
                      "Graded ground never folds into a fin in " + tile.coords().getBoardNum() + ": " + face);
            }
        }
    }

    @Test
    void rocksAndShrubsStandBesideRoadsAndBridgesNeverOnThem() throws Exception {
        // MesaCity's promontory 3725 once kept a rim boulder on its bridge deck, where neither side had room for it.
        var scene = scene("unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board");
        float scale = BoardGeometry.hexScale();
        int checked = 0;
        for (var tile : scene.tiles()) {
            var passages = BoardBridge.approaches(scene, tile);
            var road = BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
            if (road == null && passages.isEmpty()) { continue; }
            float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
            for (var face : new BoardSurface(scene, tile).faces) {
                if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    checked++;
                    assertTrue(road == null || road.distance((p.x - cx) / scale, (p.y - cy) / scale) >= BoardRoad.SHOULDER - .01f,
                          "Rocks and shrubs stand beside the road of " + tile.coords().getBoardNum() + ", never on it: " + p);
                    for (var passage : passages) {
                        assertFalse(passage.obstructs(p, 0, 0),
                              "Nothing stands on a bridge's deck or approach in " + tile.coords().getBoardNum() + ": " + p);
                    }
                }
            }
        }
        assertTrue(checked > 0, "The board's roads and bridge banks carry rocks and shrubs to check");
    }

    static BoardScene minesScene() throws Exception {
        return scene("Deserts/16x17 Mines 1.board");
    }

    /** The shipped board as a game shows it: converted where it loads, as every load site does. */
    static BoardScene scene(String path) throws Exception {
        return scene(path, board -> { });
    }

    /** The shipped board after {@code edit}, as an editor would change it, then converted and shown. */
    static BoardScene scene(String path, Consumer<Board> edit) throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/" + path));
        edit.accept(board);
        BoardSceneryLayouts.convert(board);
        var scene = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
        }
        return scene.get();
    }

    /** A destroyed rail ({@code rail:0}) draws no track and no buffer stop, but its bed stays shaped as the intact one's. */
    @Test
    void aDestroyedRailKeepsItsBedAndDrawsNoTrack() {
        Coords at = new Coords(2, 2);
        Board board = Board.createEmptyBoard(5, 5);
        for (int level : new int[] { 1, 0 }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.RAIL, level, true, 1));
            board.setHex(at, hex);
            var tile = new BoardScene(0, 5, 5, BoardTiles.captureAll(board), List.of(), List.of(), -1, "", List.of()).tile(at);
            assertEquals(1, tile.wayExits(), "rail:" + level + ": the bed is shaped as a road with its exits");
            long drawn = tile.features().stream().filter(f -> f.decoration() != null
                  && (RailRoute.isRoute(f.decoration()) || f.asset().equals(RailRoute.BUFFER_STOP))).count();
            assertEquals(level == 1 ? 2 : 0, drawn, "rail:" + level + ": the track and the buffer stop of its dead end");
        }
    }

    @Test
    void roadAppearanceSurvivesRecaptureAndChangesWithoutDependingOnTheLegacyImage() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords at = new Coords(3, 4);
                for (int level = 1; level <= 4; level++) {
                    Hex hex = new Hex(0);
                    hex.addTerrain(new Terrain(Terrains.ROAD, level, true, 9));
                    fixture.game.getBoard().setHex(at, hex);
                    fixture.source.refresh();
                    var tile = fixture.source.takeFrame().scene().tile(at);
                    assertEquals(BoardRoad.capture(hex), tile.road());
                    assertEquals(9, tile.roadExits());
                    assertTrue(BoardRoad.rendered(tile));
                    fixture.view.centerOnHex(at);
                    fixture.source.refresh();
                    assertEquals(tile.road(), fixture.source.takeFrame().scene().tile(at).road());
                }
                Hex custom = fixture.game.getBoard().getHex(at).duplicate();
                custom.setTheme("desert");
                custom.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 1));
                fixture.game.getBoard().setHex(at, custom);
                fixture.source.refresh();
                var curve = fixture.source.takeFrame().scene().tile(at);
                assertTrue(BoardRoad.rendered(curve));
                assertEquals(BoardScene.Surface.DESERT, curve.surface());
                custom = custom.duplicate();
                custom.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 3));
                fixture.game.getBoard().setHex(at, custom);
                fixture.source.refresh();
                var decorated = fixture.source.takeFrame().scene().tile(at);
                assertTrue(BoardRoad.rendered(decorated), "Cosmetic road artwork keeps the native carriageway");
                assertEquals(BoardRoad.capture(custom), decorated.road());
                assertEquals(custom.getTerrain(Terrains.ROAD).getExits(), decorated.roadExits());
                fixture.game.getBoard().setHex(at, new Hex(0));
                fixture.source.refresh();
                assertEquals(BoardRoad.Kind.NONE, fixture.source.takeFrame().scene().tile(at).road());
            });
        }
    }
}
