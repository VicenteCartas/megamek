/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native upload/lifetime gate for the shared carrier; the mask renderer tests mask coverage and painter lift. */
@Tag("on-demand")
class GpuHexSurfaceSmokeTest {
    @Test
    void indexedCarriersKeepExactSurfacesAndOwnerCoordinatesWithoutOwningSources() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(800, 300);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { verify(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Hex surface carrier", failure.get()); }
    }

    private static void verify() throws Exception {
        String before = Mesh.getManagedStatus().replace(" 0", "");
        BoardScene scene = scene(10, 2);
        Map<Coords, BoardTacticalGeometry.Surface> surfaces = new HashMap<>();
        scene.tiles().forEach(tile -> surfaces.put(tile.coords(), surface(List.of(), List.of())));
        Vector3 a = new Vector3(10, -10, 2), b = new Vector3(60, -10, 2);
        Vector3 c = new Vector3(60, -60, 2), d = new Vector3(10, -60, 2);
        var first = face(a, b, c);
        var second = face(a, c, d);
        var slope = face(new Vector3(510, -40, 3), new Vector3(520, -40, 4), new Vector3(510, -50, 5));
        var distant = face(new Vector3(4000, 0, 9), new Vector3(4010, 0, 9), new Vector3(4000, -10, 9));
        var bed = face(new Vector3(10, -10, -40), new Vector3(60, -10, -40), new Vector3(60, -60, -40));
        var vertical = face(new Vector3(10, -10, 0), new Vector3(10, -10, 1000), new Vector3(60, -10, 0));
        surfaces.put(new Coords(0, 0), new BoardTacticalGeometry.Surface(List.of(first, second, distant), List.of(),
              List.of(first, second, bed), List.of(), List.of(vertical), List.of()));
        surfaces.put(new Coords(8, 0), surface(List.of(), List.of(slope)));
        var visibleBounds = GpuHexSurface.bounds(scene, new Coords(0, 0), surfaces::get);
        assertTrue(visibleBounds.contains(a));
        assertTrue(visibleBounds.contains(slope.a()));
        assertTrue(visibleBounds.max.z < 10);
        assertEquals(before, Mesh.getManagedStatus().replace(" 0", ""), "Visibility bounds allocate no meshes");
        GpuHexSurface carrier = new GpuHexSurface(scene, new Coords(0, 0), surfaces::get);
        try {
            assertTrue(carrier.current(surfaces::get));
            Set<Vector3> allowed = new HashSet<>(List.of(a, b, c, d, slope.a(), slope.b(), slope.c()));
            boolean neighbor = false;
            int ownerZeroVertices = 0;
            for (Mesh mesh : meshes(carrier)) {
                assertEquals(20, mesh.getVertexSize(), "Only XYZ and the overlay owner are stored");
                float[] vertices = new float[mesh.getNumVertices() * 5];
                short[] indices = new short[mesh.getNumIndices()];
                mesh.getVertices(vertices);
                mesh.getIndices(indices);
                for (int at = 0; at < vertices.length; at += 5) {
                    assertTrue(allowed.contains(new Vector3(vertices[at], vertices[at + 1], vertices[at + 2])),
                          "Every uploaded position is an unchanged eligible source vertex");
                    assertTrue(vertices[at + 3] >= 0 && vertices[at + 3] < 8);
                    assertTrue(vertices[at + 4] >= 0 && vertices[at + 4] < 2);
                    if (vertices[at + 3] == 0 && vertices[at + 4] == 0) { ownerZeroVertices++; }
                    if (vertices[at + 3] == 7 && vertices[at] == slope.a().x) { neighbor = true; }
                }
                for (int at = 0; at < indices.length; at += 3) {
                    int p = Short.toUnsignedInt(indices[at]) * 5;
                    for (int corner = 1; corner < 3; corner++) {
                        int q = Short.toUnsignedInt(indices[at + corner]) * 5;
                        assertEquals(vertices[p + 3], vertices[q + 3]);
                        assertEquals(vertices[p + 4], vertices[q + 4], "The whole triangle uses one owner's painter lift");
                    }
                }
            }
            assertEquals(4, ownerZeroVertices, "Adjacent triangles share equal positions within the same owner");
            assertTrue(neighbor, "An owner at the chunk edge includes a slope from the adjacent chunk");
            assertTrue(carrier.bounds().max.z < 10, "Beds, distant faces and vertical cliff geometry are absent");
            assertTrue(carrier.bytes() > 0);
            surfaces.put(new Coords(9, 0), surface(List.of(first), List.of()));
            assertTrue(carrier.current(surfaces::get), "A remote replacement does not invalidate this chunk");
            surfaces.put(new Coords(8, 0), surface(List.of(), List.of(slope)));
            assertFalse(carrier.current(surfaces::get), "Replacing a borrowed neighbor invalidates the carrier");
            render(carrier);
        } finally {
            carrier.dispose();
        }
        assertEquals(first, surfaces.get(new Coords(0, 0)).top().getFirst(), "Disposal leaves borrowed surfaces intact");
        assertEquals(before, Mesh.getManagedStatus().replace(" 0", ""));
        wholeHex();
        assertEquals(before, Mesh.getManagedStatus().replace(" 0", ""));
        pageBoundary();
        assertEquals(before, Mesh.getManagedStatus().replace(" 0", ""));
    }

    private static void wholeHex() throws Exception {
        BoardScene scene = scene(1, 1);
        Coords chunk = new Coords(0, 0);
        var ground = face(new Vector3(10, -10, 2), new Vector3(20, -10, 2), new Vector3(10, -20, 2));
        var water = face(new Vector3(40, -10, -1), new Vector3(50, -10, -1), new Vector3(40, -20, -1));
        var slope = face(new Vector3(60, -10, 45), new Vector3(70, -10, 50), new Vector3(60, -20, 45));
        var bed = face(new Vector3(40, -10, -40), new Vector3(50, -10, -40), new Vector3(40, -20, -40));
        var wall = face(new Vector3(95, -10, 0), new Vector3(95, -10, 1000), new Vector3(105, -10, 0));
        var surface = new BoardTacticalGeometry.Surface(List.of(ground, water), List.of(slope),
              List.of(ground, bed), List.of(water), List.of(slope, wall), List.of());
        List<BoardBridge.Facet> deck = new ArrayList<>();
        BoardBridge.triangle(deck, new Vector3(110, -10, 30), new Vector3(120, -10, 30), new Vector3(110, -20, 30),
              BoardBridge.Part.TOP);
        var bridge = BoardBridge.shape(BoardScene.Surface.CONCRETE, 1, deck);
        Set<Vector3> expected = Set.of(ground.a(), ground.b(), ground.c(), water.a(), water.b(), water.c(),
              slope.a(), slope.b(), slope.c(), bed.a(), bed.b(), bed.c(), wall.a(), wall.b(), wall.c(),
              deck.getFirst().a(), deck.getFirst().b(), deck.getFirst().c());
        GpuHexSurface carrier = new GpuHexSurface(scene, chunk, ignored -> surface, ignored -> bridge);
        GpuHexSurface regular = null;
        try {
            regular = new GpuHexSurface(scene, chunk, ignored -> surface);
            Set<Vector3> actual = new HashSet<>();
            int indices = 0;
            for (Mesh mesh : meshes(carrier)) {
                float[] vertices = new float[mesh.getNumVertices() * 5];
                mesh.getVertices(vertices);
                for (int at = 0; at < vertices.length; at += 5) {
                    actual.add(new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]));
                }
                indices += mesh.getNumIndices();
            }
            assertEquals(expected, actual,
                  "A whole-section tint keeps all terrain, including vertical/outlying cliffs, and the hex's bridge");
            assertEquals(18, indices, "Each source triangle is retained once");
            assertEquals(9, meshes(regular).getFirst().getNumIndices(), "Regular border carriers still include lying slopes");
            assertEquals(1000 + 2 * BoardGeometry.HEX_SCALE, carrier.bounds().max.z);
            assertEquals(120, carrier.bounds().max.x);
            var whole = GpuHexSurface.bounds(scene, chunk, ignored -> surface, ignored -> bridge);
            assertEquals(carrier.bounds().max.z, whole.max.z);
            assertEquals(carrier.bounds().max.x, whole.max.x);
            assertEquals(regular.bounds().max.z, GpuHexSurface.bounds(scene, chunk, ignored -> surface).max.z);
        } finally {
            if (regular != null) { regular.dispose(); }
            carrier.dispose();
        }
    }

    private static void pageBoundary() throws Exception {
        List<BoardSurface.Face> faces = new ArrayList<>();
        for (int i = 0; i < 20500; i++) {
            float x = 10 + (i % 100) * .1f, y = -10 - (i / 100) * .1f;
            faces.add(face(new Vector3(x, y, 0), new Vector3(x + .03f, y, 0), new Vector3(x, y - .03f, 0)));
            if (i == 15000) {
                var shared = faces.get(14000);
                faces.add(face(new Vector3(shared.a()), new Vector3(shared.b()), new Vector3(shared.c())));
            }
        }
        var surface = surface(faces, List.of());
        GpuHexSurface carrier = new GpuHexSurface(scene(1, 1), new Coords(0, 0), ignored -> surface);
        try {
            List<Mesh> meshes = meshes(carrier);
            assertEquals(2, meshes.size());
            assertEquals(60000, meshes.getFirst().getNumVertices());
            assertEquals(1500, meshes.getLast().getNumVertices());
            assertEquals(20500 * 3 * 22L + 3 * Short.BYTES, carrier.bytes());
            short[] sharedIndices = new short[3];
            meshes.getFirst().getIndices(45003, 3, sharedIndices, 0);
            assertArrayEquals(new short[] { (short) 42000, (short) 42001, (short) 42002 }, sharedIndices,
                  "Equal source positions reuse unsigned indices beyond 32767 without new vertices");
            long total = 0;
            for (Mesh mesh : meshes) {
                short[] indices = new short[mesh.getNumIndices()];
                mesh.getIndices(indices);
                for (short index : indices) { assertTrue(Short.toUnsignedInt(index) < mesh.getNumVertices()); }
                total += indices.length;
            }
            assertEquals(faces.size() * 3, total, "Page splitting neither loses nor repeats a triangle");
        } finally {
            carrier.dispose();
        }
    }

    private static void render(GpuHexSurface carrier) {
        ShaderProgram shader = GpuGlsl.compile("hex carrier probe", """
              in vec3 a_position;
              in vec2 a_texCoord0;
              uniform mat4 u_projection;
              out vec2 v_owner;
              void main() { v_owner = a_texCoord0; gl_Position = u_projection * vec4(a_position, 1.0); }
              """, """
              layout(location = 0) out vec4 fragColor;
              in vec2 v_owner;
              void main() { fragColor = vec4(1.0, v_owner.x / 10.0, v_owner.y / 2.0, 1.0); }
              """);
        assertTrue(shader.isCompiled(), shader.getLog());
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        try {
            OrthographicCamera camera = new OrthographicCamera(840, 315);
            camera.position.set(420, -108, 1000);
            camera.lookAt(420, -108, 0);
            camera.near = 1;
            camera.far = 2000;
            camera.update();
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            ScreenUtils.clear(0, 0, 0, 1);
            shader.bind();
            shader.setUniformMatrix("u_projection", camera.combined);
            profiler.enable();
            carrier.render(camera, shader);
            assertEquals(1, profiler.getDrawCalls());
            byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
            int colored = 0;
            for (int i = 0; i < pixels.length; i += 4) { if (pixels[i] != 0) { colored++; } }
            assertTrue(colored > 500, "The native draw must contain visible geometry");
            profiler.reset();
            camera.position.x += 100000;
            camera.update();
            carrier.render(camera, shader);
            assertEquals(0, profiler.getDrawCalls(), "Off-camera pages are culled");
            camera.position.x -= 100000;
            camera.update();
            submittedStates(carrier, camera);
        } finally {
            profiler.disable();
            shader.dispose();
        }
    }

    private static void submittedStates(GpuHexSurface carrier, Camera camera) {
        List<Object> drawn = new ArrayList<>();
        Material material = new Material();
        Shader shader = new Shader() {
            @Override public void init() { }
            @Override public void begin(Camera camera, RenderContext context) { }
            @Override public void end() { }
            @Override public void dispose() { }
            @Override public boolean canRender(Renderable renderable) { return true; }
            @Override public int compareTo(Shader other) { return 0; }
            @Override public void render(Renderable renderable) {
                assertArrayEquals(new Matrix4().val, renderable.worldTransform.val);
                assertTrue(renderable.meshPart.radius > 0);
                assertTrue(renderable.material == material);
                drawn.add(renderable.userData);
            }
        };
        Object first = new Object(), second = new Object();
        ModelBatch batch = new ModelBatch();
        try {
            batch.begin(camera);
            carrier.submit(batch, camera, shader, material, first);
            carrier.submit(batch, camera, shader, material, second);
            assertTrue(drawn.isEmpty(), "Both layer states remain queued until the shared batch flushes");
            batch.end();
            assertEquals(2, drawn.size());
            assertTrue(drawn.contains(first) && drawn.contains(second), "Pooled submissions retain each layer's own state");
        } finally {
            batch.dispose();
        }
    }

    private static List<Mesh> meshes(GpuHexSurface carrier) throws Exception {
        Field field = GpuHexSurface.class.getDeclaredField("pages");
        field.setAccessible(true);
        List<Mesh> result = new ArrayList<>();
        for (Object page : (List<?>) field.get(carrier)) {
            var method = page.getClass().getDeclaredMethod("mesh");
            method.setAccessible(true);
            result.add((Mesh) method.invoke(page));
        }
        return result;
    }

    private static BoardSurface.Face face(Vector3 a, Vector3 b, Vector3 c) {
        return new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP, -1);
    }

    private static BoardTacticalGeometry.Surface surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes) {
        return new BoardTacticalGeometry.Surface(top, slopes, top, List.of(), List.of(), List.of());
    }

    private static BoardScene scene(int width, int height) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0,
                      BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
