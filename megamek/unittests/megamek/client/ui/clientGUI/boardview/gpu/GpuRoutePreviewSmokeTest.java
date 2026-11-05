/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Compare pending world triangles with the actual installed chunks, and exercise their replacement draws in GL. */
@Tag("on-demand")
class GpuRoutePreviewSmokeTest {
    @Test void joinedPreviewGeometryIsTheCommittedGeometry() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                var preview = new GpuEditorPreview(null);
                var settings = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                      0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
                var frame = new GpuReviewFrame(settings);
                try {
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "route-geometry");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    camera.setIsometric(true);
                    for (boolean classic : new boolean[] { false, true }) {
                        terrain.setColumns(classic);
                        for (String kind : List.of("wall", "road", "rail", "maglev", "bridge", "building", "habitat", "fortress")) {
                            var session = new BoardEditorSession();
                            session.game().setBoard(Board.createEmptyBoard(8, 8));
                            GpuRoutePreviewTest.choose(session, kind);
                            Coords start = new Coords(3, 2), join = start.translated(3, 2);
                            GpuRoutePreviewTest.drag(session, List.of(start, start.translated(3), join));
                            session.finishStroke();
                            var scene = scene(session.board());
                            terrain.update(scene);
                            camera.fit(scene);
                            camera.camera.zoom = .32f;
                            camera.center(BoardGeometry.center(join, 1));
                            frame.prepare(terrain, camera, scene);
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            var chunks = new ArrayList<>((List<?>) field(terrain, "chunks"));
                            GpuRoutePreviewTest.drag(session, List.of(start, start.translated(3), join));
                            try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
                                prepared(preview, scene, terrain, GpuRoutePreviewTest.data(session, capture, join));
                                var unchanged = (GpuTerrain.EditorRoutes) field(preview, "routes");
                                assertNotNull(unchanged);
                                assertTrue(unchanged.coords.isEmpty(), kind + ": repainting an identical piece replaces nothing");
                                assertTrue(unchanged.ghosts.isEmpty(), kind + ": repainting an identical piece has no ghosts");
                            }
                            session.cancelStroke();
                            preview.prepare(scene, terrain, GpuEditorPreview.Data.NONE);
                            {
                                Coords nearby = join.translated(2, 2);
                                try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
                                    var hover = GpuRoutePreviewTest.data(session, capture, nearby);
                                    prepared(preview, scene, terrain, hover);
                                    var hovered = (GpuTerrain.EditorRoutes) field(preview, "routes");
                                    assertTrue(hovered.ghosts.containsKey(nearby), kind + ": the new piece is previewed");
                                    assertTrue(hover.routes().containsKey(join), "The existing end supplies neighbourhood context");
                                    for (Coords committed : List.of(start, start.translated(3), join)) {
                                        assertFalse(hovered.coords.contains(committed), kind + ": hovering must not suppress " + committed);
                                        assertFalse(hovered.ghosts.containsKey(committed), kind + ": hovering must not ghost " + committed);
                                    }
                                    frame.render(terrain, camera, scene);
                                    preview.render(camera.camera, scene, terrain, hover);
                                    GpuReviewFrame.save(new File(output, kind + (classic ? "-classic" : "") + "-hover.png"));
                                }
                                preview.prepare(scene, terrain, GpuEditorPreview.Data.NONE);
                            }
                            var path = List.of(join, join.translated(2), join.translated(2, 2));
                            GpuRoutePreviewTest.drag(session, path);
                            GpuEditorPreview.Data data;
                            try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
                                data = GpuRoutePreviewTest.data(session, capture, path.getLast());
                            }
                            prepared(preview, scene, terrain, data);
                            var routes = (GpuTerrain.EditorRoutes) field(preview, "routes");
                            assertNotNull(routes);
                            assertTrue(routes.removed.isEmpty(), kind + ": extending a route takes no piece away");
                            var area = data.routes().keySet();
                            var committed = installed(terrain, area);
                            assertTrue(routes.ghosts.containsKey(join), "The old end becomes a joined ghost");
                            assertFalse(routes.coords.contains(start), kind + ": the unchanged far end remains committed");
                            if (kind.equals("wall")) {
                                assertFalse(routes.ghosts.containsKey(start.translated(3)), "An unchanged straight remains opaque");
                            }
                            Map<Coords, Map<String, Integer>> pending = new HashMap<>();
                            routes.ghosts.forEach((at, models) -> pending.put(at, geometry(models)));
                            assertFalse(pending.isEmpty());
                            assertEquals(chunks, field(terrain, "chunks"), "Hovering never rebuilds installed chunks");
                            frame.render(terrain, camera, scene);
                            preview.render(camera.camera, scene, terrain, data);
                            GpuReviewFrame.save(new File(output, kind + (classic ? "-classic" : "") + "-preview.png"));
                            if (kind.equals("wall")) { assertScreenPosition(preview, camera, scene, terrain, data, routes); }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            // Dropping the preview restores the original pieces without touching their meshes.
                            preview.prepare(scene, terrain, GpuEditorPreview.Data.NONE);
                            assertNull(field(terrain, "editorRoutes"));
                            assertEquals(chunks, field(terrain, "chunks"));
                            // The release: the ghosts stay until the terrain installs the edit, so the old pieces never show between.
                            prepared(preview, scene, terrain, data);
                            session.finishStroke();
                            scene = scene(session.board());
                            terrain.update(scene, camera.camera);
                            assertTrue(terrain.busy());
                            preview.prepare(scene, terrain, GpuEditorPreview.Data.NONE);
                            assertNotNull(field(terrain, "editorRoutes"), kind + ": the ghosts are held while the edit installs");
                            terrain.update(scene);
                            preview.prepare(scene, terrain, GpuEditorPreview.Data.NONE);
                            assertNull(field(terrain, "editorRoutes"), kind + ": the installed edit replaces the ghosts");
                            // Every tile of the neighbourhood is either replaced by its preview's pieces or keeps its own.
                            Map<Coords, Map<String, Integer>> installed = installed(terrain, area);
                            for (Coords at : area) {
                                boolean replaced = routes.coords.contains(at);
                                assertEquals(replaced ? pending.get(at) : committed.get(at), installed.get(at), kind
                                      + (replaced ? " world triangles at " : " unchanged pieces at ") + at + (classic ? " (Classic)" : ""));
                            }
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, kind + (classic ? "-classic" : "") + "-committed.png"));
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            // Erasing the far end shows what goes in red, with the rest of the route as it is left.
                            session.command(new BoardEditorSession.Command(BoardEditorSession.Action.TOOL, "", "ERASE"), null);
                            try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
                                var erase = GpuRoutePreviewTest.data(session, capture, start);
                                prepared(preview, scene, terrain, erase);
                                var erased = (GpuTerrain.EditorRoutes) field(preview, "routes");
                                assertTrue(erased.removed.contains(start) && erased.ghosts.containsKey(start), kind + ": what goes is shown");
                                frame.render(terrain, camera, scene);
                                preview.render(camera.camera, scene, terrain, erase);
                                GpuReviewFrame.save(new File(output, kind + (classic ? "-classic" : "") + "-erase.png"));
                            }
                            preview.prepare(scene, terrain, GpuEditorPreview.Data.NONE);
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally {
                    preview.dispose();
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Route preview geometry", failure.get()); }
    }

    private static BoardScene scene(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board), List.of(), List.of(),
              -1, "Editor", List.of(), new BoardScene.Light(1, -1));
    }

    /** World-space equality alone misses an inverted or displaced final ghost composite. */
    private static void assertScreenPosition(GpuEditorPreview preview, BoardCamera camera, BoardScene scene,
          GpuTerrain terrain, GpuEditorPreview.Data data, GpuTerrain.EditorRoutes routes) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        BoundingBox expected = new BoundingBox().inf();
        for (var models : routes.ghosts.values()) {
            for (var part : GpuTerrainDepth.snapshot(models)) {
                Mesh mesh = part.meshPart.mesh;
                int stride = mesh.getVertexSize() / Float.BYTES;
                int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
                float[] vertices = new float[mesh.getNumVertices() * stride];
                short[] indices = new short[part.meshPart.size];
                mesh.getVertices(vertices);
                mesh.getIndices(part.meshPart.offset, part.meshPart.size, indices, 0);
                for (short index : indices) {
                    int offset = Short.toUnsignedInt(index) * stride + position;
                    Vector3 point = new Vector3(vertices[offset], vertices[offset + 1], vertices[offset + 2]).mul(part.worldTransform);
                    expected.ext(camera.camera.project(point, 0, 0, width, height));
                }
            }
        }
        Gdx.gl.glClearColor(0, 0, 0, 0);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        preview.render(camera.camera, scene, terrain, data);
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
        BoundingBox actual = new BoundingBox().inf();
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if ((pixels.getPixel(x, y) >>> 8) != 0) { actual.ext(x, y, 0); }
                }
            }
        } finally { pixels.dispose(); }
        assertEquals(expected.min.x, actual.min.x, 2, "Ghost left edge follows the board camera");
        assertEquals(expected.max.x, actual.max.x, 2, "Ghost right edge follows the board camera");
        assertEquals(expected.min.y, actual.min.y, 2, "Ghost bottom edge follows the board camera");
        assertEquals(expected.max.y, actual.max.y, 2, "Ghost top edge follows the board camera");
    }

    /** {@code data} prepared with its route pieces installed: they arrive from a worker in a later frame. */
    private static void prepared(GpuEditorPreview preview, BoardScene scene, GpuTerrain terrain, GpuEditorPreview.Data data)
          throws Exception {
        preview.prepare(scene, terrain, data);
        while (field(preview, "comparing") != null) {
            Thread.sleep(1);
            preview.prepare(scene, terrain, data);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        return GpuMixedUnitBenchmarkSmokeTest.field(owner, name);
    }

    private static Map<Coords, Map<String, Integer>> installed(GpuTerrain terrain, java.util.Set<Coords> scope) throws Exception {
        Map<Coords, Map<String, Integer>> result = new HashMap<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (var entry : ((Map<?, ?>) field(chunk, "tileMeshes")).entrySet()) {
                Coords at = (Coords) entry.getKey();
                if (!scope.contains(at)) { continue; }
                Map<String, Integer> triangles = new HashMap<>();
                for (Object range : (List<?>) field(entry.getValue(), "ranges")) {
                    if (!(boolean) field(range, "route")) { continue; }
                    append(triangles, (Mesh) field(range, "mesh"), (int) field(range, "offset"),
                          (int) field(range, "count"), new Matrix4());
                }
                for (Object prop : (List<?>) field(entry.getValue(), "props")) {
                    if ((boolean) field(prop, "infrastructure")) {
                        geometry(List.of((ModelInstance) field(prop, "instance"))).forEach((key, count) -> triangles.merge(key, count, Integer::sum));
                    }
                }
                if (!triangles.isEmpty()) { result.put(at, triangles); }
            }
        }
        return result;
    }

    private static Map<String, Integer> geometry(List<ModelInstance> instances) {
        Map<String, Integer> result = new HashMap<>();
        for (var part : GpuTerrainDepth.snapshot(instances)) {
            append(result, part.meshPart.mesh, part.meshPart.offset, part.meshPart.size, part.worldTransform);
        }
        return result;
    }

    private static void append(Map<String, Integer> result, Mesh mesh, int offset, int count, Matrix4 transform) {
        float[] vertices = new float[mesh.getNumVertices() * mesh.getVertexSize() / Float.BYTES];
        short[] indices = new short[count];
        mesh.getVertices(vertices);
        mesh.getIndices(offset, count, indices, 0);
        int stride = mesh.getVertexSize() / Float.BYTES;
        int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
        for (int i = 0; i < count; i += 3) {
            List<String> corners = new ArrayList<>();
            for (int corner = 0; corner < 3; corner++) {
                int index = Short.toUnsignedInt(indices[i + corner]) * stride + position;
                var p = new Vector3(vertices[index], vertices[index + 1], vertices[index + 2]).mul(transform);
                corners.add(Math.round(p.x * 1000) + "," + Math.round(p.y * 1000) + "," + Math.round(p.z * 1000));
            }
            corners.sort(String::compareTo);
            result.merge(String.join("/", corners), 1, Integer::sum);
        }
    }
}
