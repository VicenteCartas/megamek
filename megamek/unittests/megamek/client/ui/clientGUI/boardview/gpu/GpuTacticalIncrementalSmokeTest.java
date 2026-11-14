/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.geom.Path2D;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Retained pages keep the old bulk builder's transparent ranges while replacing only local geometry. */
@Tag("on-demand")
class GpuTacticalIncrementalSmokeTest {
    private record Outlined(BoardTactical.Wall wall, BoardTacticalGeometry.Triangle triangle) { }

    @Test
    void localChangesRetainUnchangedPagesAndMatchBulkGeometryAndPixels() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(700, 500);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { verifyUpdates(); verifyFloatingUpdates(true); verifyFloatingUpdates(false); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Incremental tactical geometry", failure.get()); }
    }

    private static void verifyFloatingUpdates(boolean border) throws Exception {
        var graphics = new BoardTacticalGraphics();
        BoardTactical commands;
        try {
            graphics.setColor(java.awt.Color.ORANGE);
            var local = BoardTacticalGraphics.onHexPlane(graphics, new Coords(3, 0));
            try {
                if (border) { local.fillHexBorder(1, 2); } else { local.fillOval(-27, -26, 54, 52); }
            } finally { local.dispose(); }
            commands = graphics.snapshot();
        } finally {
            graphics.dispose();
        }
        Coords owner = new Coords(3, 0);
        AtomicReference<BoardTacticalGeometry.Surface> support = new AtomicReference<>();
        int[] queries = { 0 };
        Function<Coords, BoardTacticalGeometry.Surface> ownerOnly = coords -> {
            assertEquals(owner, coords, "Floating borders must never query neighboring terrain");
            queries[0]++;
            return support.get();
        };
        String before = Mesh.getManagedStatus().replace(" 0", "");
        GpuTactical tactical = new GpuTactical(ownerOnly);
        try {
            BoardScene initial = floatingScene(commands, 0, -1, false, 0);
            support.set(floatingSurface(initial.tile(owner), 0));
            tactical.update(initial);
            assertEquals(0, queries[0], "Flat annotations do not depend on finished terrain");
            assertEquals(1, ranges(tactical).size());
            Renderable first = ranges(tactical).getFirst();
            float[] initialVertices = vertices(first);
            float clearance = BoardTacticalGeometry.HEX_PLANE_CLEARANCE;
            assertFloatingPlane(initialVertices, first, clearance);
            compareBulk(initial, ownerOnly, tactical);

            BoardScene water = floatingScene(commands, 0, 5, false, 7);
            support.set(floatingSurface(water.tile(owner), 0));
            tactical.update(water);
            Renderable waterPage = ranges(tactical).getFirst();
            assertNotSame(first.meshPart.mesh, waterPage.meshPart.mesh);
            assertFloatingPlane(initialVertices, waterPage, BoardGeometry.waterZ(water.tile(owner)) + clearance);

            BoardScene deeper = floatingScene(commands, 0, 9, false, 12);
            support.set(floatingSurface(deeper.tile(owner), 0));
            tactical.update(deeper);
            assertSame(waterPage.meshPart.mesh, ranges(tactical).getFirst().meshPart.mesh,
                  "A replacement top at the same height retains the page despite deeper water and higher neighbors");
            BoardScene neighbors = floatingScene(commands, 0, 9, false, 25);
            tactical.update(neighbors);
            assertSame(waterPage.meshPart.mesh, ranges(tactical).getFirst().meshPart.mesh,
                  "Changing only neighbors must retain the owner's horizontal plane");
            compareBulk(neighbors, ownerOnly, tactical);

            BoardScene frozen = floatingScene(commands, 0, 9, true, 25);
            support.set(floatingSurface(frozen.tile(owner), 0));
            tactical.update(frozen);
            assertFloatingPlane(initialVertices, ranges(tactical).getFirst(), clearance);

            BoardScene raised = floatingScene(commands, 2, 9, true, 25);
            support.set(floatingSurface(raised.tile(owner), 0));
            tactical.update(raised);
            Renderable moved = ranges(tactical).getFirst();
            assertFloatingPlane(initialVertices, moved, 2 * BoardGeometry.LEVEL + clearance);

            // Finished relief changes must not move flat annotations at the same logical elevation.
            BoardScene rough = floatingScene(commands, 2, -1, false, 25);
            support.set(floatingSurface(rough.tile(owner), 6.25f));
            tactical.update(rough);
            Renderable lifted = ranges(tactical).getFirst();
            assertSame(moved.meshPart.mesh, lifted.meshPart.mesh,
                  "Changing relief at the same elevation must retain the owner's horizontal plane");
            assertFloatingPlane(initialVertices, lifted, 2 * BoardGeometry.LEVEL + clearance);
            compareBulk(rough, ownerOnly, tactical);
            int calls = queries[0];
            tactical.update(rough);
            assertEquals(calls, queries[0], "Unchanged snapshots do not rescan the owner's finished triangles");
            assertSame(lifted.meshPart.mesh, ranges(tactical).getFirst().meshPart.mesh);
        } finally {
            tactical.dispose();
        }
        assertEquals(before, Mesh.getManagedStatus().replace(" 0", ""));
    }

    private static void assertFloatingPlane(float[] original, Renderable current, float height) {
        float[] vertices = vertices(current);
        assertEquals(original.length, vertices.length);
        for (int i = 0; i < vertices.length; i += 4) {
            assertEquals(original[i], vertices[i]);
            assertEquals(original[i + 1], vertices[i + 1]);
            assertEquals(height, vertices[i + 2], .00001f, "Every vertex shares the owner's elevation plane");
            assertEquals(original[i + 3], vertices[i + 3]);
        }
    }

    private static BoardTacticalGeometry.Surface floatingSurface(BoardScene.Tile tile, float relief) {
        List<BoardSurface.Face> top = new ArrayList<>();
        for (var face : surface(tile).top()) {
            List<Vector3> points = List.of(new Vector3(face.a()), new Vector3(face.b()), new Vector3(face.c()));
            for (Vector3 point : points) {
                point.z = BoardGeometry.surfaceZ(tile)
                      + relief * (point.x - BoardGeometry.centerX(tile.coords()) + BoardGeometry.WIDTH / 2) / BoardGeometry.WIDTH;
            }
            top.add(new BoardSurface.Face(points.get(0), points.get(1), points.get(2), BoardSurface.Finish.TOP));
        }
        return new BoardTacticalGeometry.Surface(top, List.of(), top, List.of(), List.of(), List.of());
    }

    private static BoardScene floatingScene(BoardTactical commands, int level, int depth, boolean frozen, int neighbor) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                boolean owner = x == 3 && y == 0;
                tiles.add(BoardScenes.newTile(new Coords(x, y), owner ? level : neighbor, owner ? depth : -1,
                      owner && frozen, 0, BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 4, 2, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), commands);
    }

    private static void verifyUpdates() throws Exception {
        String beforeMeshes = Mesh.getManagedStatus().replace(" 0", "");
        Map<Coords, BoardTacticalGeometry.Surface> surfaces = new HashMap<>();
        int[] queries = { 0 };
        Function<Coords, BoardTacticalGeometry.Surface> provider = coords -> {
            queries[0]++;
            return surfaces.get(coords);
        };
        GpuTactical tactical = new GpuTactical(provider);
        try {
            String emptyMeshes = Mesh.getManagedStatus();
            BoardScene initial = scene(commands(), 0);
            initial.tiles().forEach(tile -> surfaces.put(tile.coords(), surface(tile)));
            tactical.update(initial);
            Map<BasicStroke, Material> inks = field(tactical, "outlines");
            List<Texture> dashTextures = inks.values().stream().map(ink ->
                  ink.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture).toList();
            List<Renderable> first = ranges(tactical);
            assertTrue(first.size() > 5, "The fixture crosses the original 10,000-triangle page boundary");
            compareBulk(initial, provider, tactical);
            int calls = queries[0];
            long builds = tactical.builds();
            tactical.update(scene(initial.tactical(), 0));
            assertEquals(builds, tactical.builds());
            assertEquals(calls, queries[0], "An unchanged update must not query or re-clip terrain");

            List<BoardTactical.Fill> fills = new ArrayList<>(initial.tactical().fills());
            BoardTactical.Fill last = fills.getLast();
            fills.set(fills.size() - 1, new BoardTactical.Fill(last.contours(), last.winding(), 0xA030D070));
            BoardScene recolored = scene(new BoardTactical(fills, List.of(), initial.tactical().walls(),
                  initial.tactical().flatWalls()), 0);
            tactical.update(recolored);
            assertTrue(queries[0] - calls < 20, "Recoloring one command must only clip that command");
            List<Renderable> second = ranges(tactical);
            assertSame(first.getFirst().meshPart.mesh, second.getFirst().meshPart.mesh);
            assertTrue(changedMeshes(first, second) == 1, "One recolored fill replaces only its containing page");
            compareBulk(recolored, provider, tactical);

            BoardScene raised = scene(recolored.tactical(), 2);
            Coords edited = new Coords(3, 0);
            surfaces.put(edited, surface(raised.tile(edited)));
            tactical.update(raised);
            assertSame(second.getFirst().meshPart.mesh, ranges(tactical).getFirst().meshPart.mesh,
                  "A remote terrain edit retains the first fill page");
            assertTrue(changedMeshes(second, ranges(tactical)) > 0);
            compareBulk(raised, provider, tactical);

            // Playback and painter edits can insert, remove or reorder commands; their absolute lifts must follow.
            fills = new ArrayList<>(raised.tactical().fills());
            fills.addFirst(fills.removeLast());
            fills.remove(20);
            BoardScene reordered = scene(new BoardTactical(fills, List.of(), raised.tactical().walls(),
                  raised.tactical().flatWalls()), 2);
            tactical.update(reordered);
            compareBulk(reordered, provider, tactical);

            // Animate once before copying reference materials, then freeze the shared timeline during comparisons.
            Camera camera = camera(false, false);
            tactical.render(camera, 1.25f);
            compareBulk(reordered, provider, tactical);
            tactical.update(scene(BoardTactical.EMPTY, 2));
            assertTrue(ranges(tactical).isEmpty());
            assertEquals(emptyMeshes, Mesh.getManagedStatus(), "Removing overlays releases every retained page");
            for (Texture texture : dashTextures) {
                assertEquals(0, texture.getTextureObjectHandle(), "Removing outlines releases their dash textures");
            }
        } finally {
            tactical.dispose();
        }
        assertEquals(beforeMeshes, Mesh.getManagedStatus().replace(" 0", ""));
    }

    private static int changedMeshes(List<Renderable> previous, List<Renderable> next) {
        int changed = Math.abs(previous.size() - next.size());
        for (int i = 0; i < Math.min(previous.size(), next.size()); i++) {
            if (previous.get(i).meshPart.mesh != next.get(i).meshPart.mesh) { changed++; }
        }
        return changed;
    }

    private static void compareBulk(BoardScene scene, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          GpuTactical tactical) throws Exception {
        ModelInstance reference = bulk(scene, surfaces, tactical);
        ModelBatch batch = new ModelBatch();
        try {
            List<Renderable> expected = collect(reference), actual = ranges(tactical);
            assertEquals(expected.size(), actual.size(), "Keep every original draw range");
            for (int i = 0; i < expected.size(); i++) {
                var a = expected.get(i).meshPart;
                var b = actual.get(i).meshPart;
                assertEquals(a.center, b.center, "Blended sorting center at range " + i);
                assertEquals(a.halfExtents, b.halfExtents);
                assertEquals(a.radius, b.radius);
                assertArrayEquals(vertices(expected.get(i)), vertices(actual.get(i)), "Ordered vertices at range " + i);
            }
            for (boolean perspective : new boolean[] { false, true }) {
                for (boolean flat : new boolean[] { false, true }) {
                    Camera camera = camera(perspective, flat);
                    reference.getNode("flat-walls").parts.forEach(part -> part.enabled = flat);
                    reference.getNode("upright-walls").parts.forEach(part -> part.enabled = !flat);
                    Runnable original = () -> { batch.begin(camera); batch.render(reference); batch.end(); };
                    frame(original);
                    frame(() -> tactical.render(camera, 0, flat));
                    assertArrayEquals(frame(original), frame(() -> tactical.render(camera, 0, flat)),
                          "Exact bulk pixels: perspective=" + perspective + ", flat=" + flat);
                }
            }
            compareNarrow(reference, batch, tactical, expected.size() > 1);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            batch.dispose();
            reference.model.dispose();
        }
    }

    private static void compareNarrow(ModelInstance reference, ModelBatch batch, GpuTactical tactical,
          boolean requireReduction) {
        Camera camera = new OrthographicCamera(35, 25);
        Vector3 target = BoardGeometry.center(new Coords(3, 0), 0);
        camera.position.set(target).add(0, 0, 200);
        camera.lookAt(target);
        camera.near = 1; camera.far = 1000;
        camera.update();
        reference.getNode("flat-walls").parts.forEach(part -> part.enabled = true);
        reference.getNode("upright-walls").parts.forEach(part -> part.enabled = false);
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        try {
            profiler.reset();
            byte[] original = frame(() -> { batch.begin(camera); batch.render(reference); batch.end(); });
            int previousDraws = profiler.getDrawCalls();
            profiler.reset();
            byte[] culled = frame(() -> tactical.render(camera, 0, true));
            assertArrayEquals(original, culled, "Offscreen page rejection preserves the narrow camera image");
            assertTrue(profiler.getDrawCalls() <= previousDraws, "Culling must not add draw calls");
            if (requireReduction) {
                assertTrue(profiler.getDrawCalls() < previousDraws, "Offscreen pages must reduce actual GL draws");
            }
        } finally {
            profiler.disable();
        }
    }

    private static Camera camera(boolean perspective, boolean flat) {
        Camera camera = perspective ? new PerspectiveCamera(45, 700, 500) : new OrthographicCamera(330, 236);
        camera.position.set(110, flat ? -35 : -240, flat ? 380 : 300);
        camera.up.set(0, 1, 0);
        camera.lookAt(110, -35, 0);
        camera.near = 1; camera.far = 2000;
        camera.update();
        return camera;
    }

    private static byte[] frame(Runnable draw) {
        Gdx.gl.glViewport(0, 0, 700, 500);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearColor(.06f, .08f, .1f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        draw.run();
        return ScreenUtils.getFrameBufferPixels(0, 0, 700, 500, false);
    }

    private static List<Renderable> ranges(GpuTactical tactical) throws Exception {
        Map<?, ?> pages = field(tactical, "pages");
        List<Renderable> result = new ArrayList<>();
        for (Object group : pages.values()) {
            for (Object page : (List<?>) group) { result.addAll(collect((RenderableProvider) page)); }
        }
        return result;
    }

    private static List<Renderable> collect(RenderableProvider source) {
        Array<Renderable> result = new Array<>();
        source.getRenderables(result, new Pool<>() {
            @Override protected Renderable newObject() { return new Renderable(); }
        });
        List<Renderable> list = new ArrayList<>();
        result.forEach(list::add);
        return list;
    }

    private static float[] vertices(Renderable renderable) {
        var part = renderable.meshPart;
        Mesh mesh = part.mesh;
        int stride = mesh.getVertexSize() / 4;
        float[] vertices = new float[mesh.getNumVertices() * stride];
        short[] indices = new short[part.size];
        mesh.getVertices(vertices);
        mesh.getIndices(part.offset, part.size, indices, 0);
        float[] result = new float[part.size * stride];
        for (int i = 0; i < indices.length; i++) {
            System.arraycopy(vertices, (indices[i] & 0xFFFF) * stride, result, i * stride, stride);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    /** The pre-cache ModelBuilder layout is deliberately kept as an independent rendering reference. */
    private static ModelInstance bulk(BoardScene scene, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          GpuTactical tactical) throws Exception {
        Material body = field(tactical, "material");
        Map<BasicStroke, Material> outlines = field(tactical, "outlines");
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        builder.node().id = "surface";
        BoardTacticalGeometry.drape(scene, triangles(builder, body, null), surfaces);
        for (boolean flat : new boolean[] { false, true }) {
            builder.node().id = flat ? "flat-walls" : "upright-walls";
            Map<BasicStroke, List<Outlined>> ink = new LinkedHashMap<>();
            BoardTacticalGeometry.walls(scene, flat, triangles(builder, body, null), (wall, triangle) ->
                  ink.computeIfAbsent(wall.outline().stroke(), ignored -> new ArrayList<>()).add(new Outlined(wall, triangle)), surfaces);
            for (var entry : ink.entrySet()) {
                MeshPartBuilder mesh = null;
                for (int i = 0; i < entry.getValue().size(); i++) {
                    if (i % 10000 == 0) {
                        mesh = builder.part("outline-" + i, GL20.GL_TRIANGLES,
                              VertexAttributes.Usage.Position | VertexAttributes.Usage.ColorPacked
                                    | VertexAttributes.Usage.TextureCoordinates, outlines.get(entry.getKey()));
                    }
                    var value = entry.getValue().get(i);
                    emit(mesh, value.triangle(), value.wall());
                }
            }
        }
        return new ModelInstance(builder.end());
    }

    private static Consumer<BoardTacticalGeometry.Triangle> triangles(ModelBuilder builder, Material material,
          BoardTactical.Wall wall) {
        MeshPartBuilder[] mesh = { null };
        int[] count = { 0 };
        return triangle -> {
            if (count[0]++ % 10000 == 0) {
                mesh[0] = builder.part("body-" + count[0], GL20.GL_TRIANGLES,
                      VertexAttributes.Usage.Position | VertexAttributes.Usage.ColorPacked, material);
            }
            emit(mesh[0], triangle, wall);
        };
    }

    private static void emit(MeshPartBuilder mesh, BoardTacticalGeometry.Triangle triangle, BoardTactical.Wall wall) {
        Color color = new Color();
        Color.argb8888ToColor(color, triangle.argb());
        mesh.triangle(vertex(triangle.a(), color, wall), vertex(triangle.b(), color, wall), vertex(triangle.c(), color, wall));
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Color color, BoardTactical.Wall wall) {
        var vertex = new MeshPartBuilder.VertexInfo().setPos(point).setCol(color);
        if (wall != null) {
            float dx = wall.b().x() - wall.a().x(), dy = wall.b().y() - wall.a().y();
            float along = ((point.x / BoardGeometry.HEX_SCALE - wall.a().x()) * dx
                  + (-point.y / BoardGeometry.HEX_SCALE - wall.a().y()) * dy) / (float) Math.hypot(dx, dy);
            vertex.setUV(wall.outline().stroke().getDashPhase() + wall.outlineDistance() + along, .5f);
        }
        return vertex;
    }

    private static BoardTactical commands() {
        List<BoardTactical.Fill> fills = new ArrayList<>();
        for (int i = 0; i < 3000; i++) { fills.add(fill(i < 2500 ? 0 : 3, 0, 0x7030A0D0)); }
        List<BoardTactical.Wall> walls = new ArrayList<>();
        List<BoardTactical.Fill> bands = new ArrayList<>();
        for (int x : new int[] { 0, 3, 1 }) {
            float cx = x * BoardGeometry.TILE_WIDTH * .75f + BoardGeometry.TILE_WIDTH / 2;
            float y = BoardGeometry.TILE_HEIGHT * (1 + (x & 1) * .5f);
            var stroke = new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                  x == 3 ? new float[] { 3, 1, 2 } : new float[] { 2, 2 }, .75f);
            walls.add(new BoardTactical.Wall(new Coords(x, 1), new BoardTactical.Point(cx - 15, y + 5),
                  new BoardTactical.Point(cx + 15, y + 5), .5f, 0x8060C060,
                  new BoardTactical.Outline(0xC0FFFFFF, stroke), x * 7, BoardTactical.Playback.HIDE_DURING_MOVEMENT));
            bands.add(fill(x, 1, 0x8060C060));
        }
        fills.add(fill(2, 0, 0)); // Transparent commands still occupy their original painter layer.
        return new BoardTactical(fills, List.of(), walls, bands);
    }

    private static BoardTactical.Fill fill(int x, int y, int argb) {
        float cx = x * BoardGeometry.TILE_WIDTH * .75f + BoardGeometry.TILE_WIDTH / 2;
        float cy = (y + .5f + (x & 1) * .5f) * BoardGeometry.TILE_HEIGHT;
        return new BoardTactical.Fill(List.of(new BoardTactical.Contour(List.of(
              new BoardTactical.Point(cx - 8, cy - 8), new BoardTactical.Point(cx + 8, cy - 8),
              new BoardTactical.Point(cx + 8, cy + 8), new BoardTactical.Point(cx - 8, cy + 8)))), Path2D.WIND_NON_ZERO, argb);
    }

    private static BoardScene scene(BoardTactical tactical, int raised) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), x == 3 && y == 0 ? raised : 0,
                      x == 2 ? 2 : -1, y == 1 && x == 2, 0, BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 4, 2, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), tactical);
    }

    private static BoardTacticalGeometry.Surface surface(BoardScene.Tile tile) {
        List<BoardSurface.Face> faces = new ArrayList<>();
        Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
        for (int edge = 0; edge < 6; edge++) {
            faces.add(new BoardSurface.Face(new Vector3(center), BoardGeometry.corner(tile.coords(), tile.elevation(), edge),
                  BoardGeometry.corner(tile.coords(), tile.elevation(), edge + 1), BoardSurface.Finish.TOP));
        }
        return new BoardTacticalGeometry.Surface(List.copyOf(faces), List.of(), List.copyOf(faces), List.of(), List.of(), List.of());
    }
}
