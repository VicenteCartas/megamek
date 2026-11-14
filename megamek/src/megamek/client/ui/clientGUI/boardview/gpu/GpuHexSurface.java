/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectIntMap;
import megamek.common.board.Coords;

/**
 * Terrain carriers shared by every hex-mask style. Each triangle carries its overlay owner's hex coordinates,
 * even when its terrain belongs to a neighbor; the shader supplies that owner's mask and exact painter lift.
 * Finished surfaces are borrowed, while this carrier owns its indexed meshes and no retained vertex arrays.
 */
final class GpuHexSurface implements Disposable {
    private static final int PAGE_VERTICES = 60000;
    private record Page(Mesh mesh, BoundingBox bounds) { }
    private final List<Page> pages = new ArrayList<>();
    private final Map<Coords, BoardTacticalGeometry.Surface> dependencies = new HashMap<>();
    private final BoundingBox bounds = new BoundingBox().inf();

    /** Chunk coordinates, not tile coordinates: (1, 0) begins at column {@link GpuTerrain#CHUNK_SIZE}. */
    GpuHexSurface(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        this(scene, chunk, surfaces, null);
    }

    /**
     * {@code bridges} (each hex's installed bridge, or null for none) selects a whole-hex tint's carriers: every hex's
     * complete terrain and its bridge, once.
     */
    GpuHexSurface(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          Function<Coords, BoardBridge.Shape> bridges) {
        boolean wholeHex = bridges != null;
        MeshBuilder builder = null;
        ObjectIntMap<Vector3> indices = new ObjectIntMap<>();
        float[] vertex = new float[5];
        int startX = chunk.getX() * GpuTerrain.CHUNK_SIZE, startY = chunk.getY() * GpuTerrain.CHUNK_SIZE;
        try {
            for (int x = startX; x < Math.min(scene.width(), startX + GpuTerrain.CHUNK_SIZE); x++) {
                for (int y = startY; y < Math.min(scene.height(), startY + GpuTerrain.CHUNK_SIZE); y++) {
                    indices.clear();
                    vertex[3] = x;
                    vertex[4] = y;
                    float left = x * BoardGeometry.TILE_WIDTH * .75f;
                    float top = (y + (x & 1) * .5f) * BoardGeometry.TILE_HEIGHT;
                    float right = left + BoardGeometry.TILE_WIDTH, bottom = top + BoardGeometry.TILE_HEIGHT;
                    // The same conservative candidate window as drape(), for a complete unscaled hex.
                    // A whole-hex tint uses its terrain once, with no footprint clipping or neighbor duplication.
                    int firstX = wholeHex ? x : Math.max(0, (int) Math.floor(left / (BoardGeometry.TILE_WIDTH * .75f)) - 1);
                    int lastX = wholeHex ? x : Math.min(scene.width() - 1, (int) Math.floor(right / (BoardGeometry.TILE_WIDTH * .75f)));
                    int firstY = wholeHex ? y : Math.max(0, (int) Math.floor(top / BoardGeometry.TILE_HEIGHT) - 1);
                    int lastY = wholeHex ? y : Math.min(scene.height() - 1, (int) Math.floor(bottom / BoardGeometry.TILE_HEIGHT));
                    float minX = left * BoardGeometry.hexScale(), maxX = right * BoardGeometry.hexScale();
                    float minY = -bottom * BoardGeometry.hexScale(), maxY = -top * BoardGeometry.hexScale();
                    for (int cx = firstX; cx <= lastX; cx++) {
                        for (int cy = firstY; cy <= lastY; cy++) {
                            Coords source = new Coords(cx, cy);
                            var surface = dependencies.computeIfAbsent(source, surfaces);
                            for (var faces : wholeHex ? whole(surface, bridges.apply(source))
                                  : List.of(surface.top(), surface.slopes())) {
                                for (BoardSurface.Face face : faces) {
                                    if (!wholeHex && !intersects(face, minX, minY, maxX, maxY)) { continue; }
                                    if (builder == null || builder.getNumVertices() + 3 > PAGE_VERTICES) {
                                        if (builder != null) { finish(builder); }
                                        builder = new MeshBuilder();
                                        builder.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.TextureCoordinates,
                                              GL20.GL_TRIANGLES);
                                        indices.clear();
                                    }
                                    builder.triangle(vertex(builder, indices, face.a(), vertex),
                                          vertex(builder, indices, face.b(), vertex), vertex(builder, indices, face.c(), vertex));
                                }
                            }
                        }
                    }
                }
            }
            if (builder != null) { finish(builder); }
        } catch (RuntimeException | Error failure) {
            dispose();
            throw failure;
        }
    }

    /** A whole hex: its ground, water and walls, and the facets its bridge is drawn from. */
    private static List<List<BoardSurface.Face>> whole(BoardTacticalGeometry.Surface surface, BoardBridge.Shape bridge) {
        List<BoardSurface.Face> span = bridge == null ? List.of() : bridge.facets().stream().map(facet ->
              new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP)).toList();
        return List.of(surface.faces(), surface.water(), surface.walls(), span);
    }

    private static short vertex(MeshBuilder builder, ObjectIntMap<Vector3> indices, Vector3 point, float[] vertex) {
        int index = indices.get(point, -1);
        if (index < 0) {
            vertex[0] = point.x;
            vertex[1] = point.y;
            vertex[2] = point.z;
            index = Short.toUnsignedInt(builder.vertex(vertex)); // MeshBuilder copies the scratch array immediately.
            indices.put(point, index);
        }
        return (short) index;
    }

    private static boolean intersects(BoardSurface.Face face, float minX, float minY, float maxX, float maxY) {
        float area = (face.b().x - face.a().x) * (face.c().y - face.a().y)
              - (face.b().y - face.a().y) * (face.c().x - face.a().x);
        if (Math.abs(area) < .00001f) { return false; }
        return Math.max(face.a().x, Math.max(face.b().x, face.c().x)) >= minX
              && Math.min(face.a().x, Math.min(face.b().x, face.c().x)) <= maxX
              && Math.max(face.a().y, Math.max(face.b().y, face.c().y)) >= minY
              && Math.min(face.a().y, Math.min(face.b().y, face.c().y)) <= maxY;
    }

    /** No-upload visibility bound. Callers may cache this until the board, tuning or terrain changes. */
    static BoundingBox bounds(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        return bounds(scene, chunk, surfaces, null);
    }

    static BoundingBox bounds(BoardScene scene, Coords chunk, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          Function<Coords, BoardBridge.Shape> bridges) {
        int startX = chunk.getX() * GpuTerrain.CHUNK_SIZE, startY = chunk.getY() * GpuTerrain.CHUNK_SIZE;
        int endX = Math.min(scene.width(), startX + GpuTerrain.CHUNK_SIZE);
        int endY = Math.min(scene.height(), startY + GpuTerrain.CHUNK_SIZE);
        float minX = startX * BoardGeometry.TILE_WIDTH * .75f * BoardGeometry.hexScale();
        float maxX = ((endX - 1) * BoardGeometry.TILE_WIDTH * .75f + BoardGeometry.TILE_WIDTH) * BoardGeometry.hexScale();
        float minY = -(endY + .5f) * BoardGeometry.TILE_HEIGHT * BoardGeometry.hexScale();
        float maxY = -startY * BoardGeometry.TILE_HEIGHT * BoardGeometry.hexScale();
        BoundingBox result = new BoundingBox().inf();
        if (bridges != null) {
            for (int x = startX; x < endX; x++) {
                for (int y = startY; y < endY; y++) {
                    Coords coords = new Coords(x, y);
                    for (var faces : whole(surfaces.apply(coords), bridges.apply(coords))) {
                        for (var face : faces) { result.ext(face.a()).ext(face.b()).ext(face.c()); }
                    }
                }
            }
            if (result.isValid()) { liftedBounds(result); }
            return result;
        }
        for (int x = Math.max(0, startX - 1); x <= Math.min(scene.width() - 1, endX); x++) {
            for (int y = Math.max(0, startY - 1); y <= Math.min(scene.height() - 1, endY); y++) {
                var surface = surfaces.apply(new Coords(x, y));
                extend(result, surface.top(), minX, minY, maxX, maxY);
                extend(result, surface.slopes(), minX, minY, maxX, maxY);
            }
        }
        if (result.isValid()) {
            // Whole carrier triangles can reach farther, but the shader discards outside their owner's hex.
            result.min.x = Math.max(result.min.x, minX);
            result.min.y = Math.max(result.min.y, minY);
            result.max.x = Math.min(result.max.x, maxX);
            result.max.y = Math.min(result.max.y, maxY);
            liftedBounds(result);
        }
        return result;
    }

    private static void extend(BoundingBox bounds, List<BoardSurface.Face> faces,
          float minX, float minY, float maxX, float maxY) {
        for (BoardSurface.Face face : faces) {
            if (intersects(face, minX, minY, maxX, maxY)) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
    }

    private static void liftedBounds(BoundingBox bounds) {
        // Current painter lift is capped at 1.35 tactical units; keep culling conservative after vertex lift.
        bounds.max.z += 2 * BoardGeometry.hexScale();
        bounds.update();
    }

    private void finish(MeshBuilder builder) {
        Mesh mesh = builder.end();
        BoundingBox pageBounds = new BoundingBox();
        pages.add(new Page(mesh, pageBounds));
        mesh.calculateBoundingBox(pageBounds);
        liftedBounds(pageBounds);
        bounds.ext(pageBounds);
    }

    /** A hex's bridge is installed with its support (GpuTerrain's tile collection), so the surfaces answer for it too. */
    boolean current(Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        for (var entry : dependencies.entrySet()) {
            if (surfaces.apply(entry.getKey()) != entry.getValue()) { return false; }
        }
        return true;
    }

    /** Borrowed read-only bounds, including the shader's capped painter lift. */
    BoundingBox bounds() { return bounds; }

    /** Uploaded vertex and index payload; excludes the driver's storage and borrowed surface objects. */
    long bytes() {
        return pages.stream().mapToLong(page -> (long) page.mesh().getNumVertices() * page.mesh().getVertexSize()
              + (long) page.mesh().getNumIndices() * Short.BYTES).sum();
    }

    /** The caller owns shader uniforms and GL state; meshes bind only for this call. */
    void render(Camera camera, ShaderProgram shader) {
        for (Page page : pages) {
            if (camera.frustum.boundsInFrustum(page.bounds())) { page.mesh().render(shader, GL20.GL_TRIANGLES); }
        }
    }

    /** Pooled submissions share the batch's ordering with icons; the caller keeps each draw state alive until flush. */
    void submit(ModelBatch batch, Camera camera, Shader shader, Material material, Object state) {
        for (Page page : pages) {
            if (!camera.frustum.boundsInFrustum(page.bounds())) { continue; }
            batch.render((renderables, pool) -> {
                Renderable renderable = pool.obtain();
                renderable.worldTransform.idt();
                renderable.meshPart.set("hex-mask", page.mesh(), 0, page.mesh().getNumIndices(), GL20.GL_TRIANGLES);
                page.bounds().getCenter(renderable.meshPart.center);
                page.bounds().getDimensions(renderable.meshPart.halfExtents).scl(.5f);
                renderable.meshPart.radius = renderable.meshPart.halfExtents.len();
                renderable.material = material;
                renderable.shader = shader;
                renderable.environment = null;
                renderable.bones = null;
                renderable.userData = state;
                renderables.add(renderable);
            });
        }
    }

    @Override
    public void dispose() {
        pages.forEach(page -> page.mesh().dispose());
        pages.clear();
        dependencies.clear();
        bounds.inf();
    }

    /**
     * One hex pass's carriers by chunk: each is built when its chunk is first in view and kept while its terrain stays
     * current. Its owner chooses the chunks and draws them with its own shader and per-draw state.
     */
    static final class Cache implements Disposable {
        private final Map<Coords, GpuHexSurface> surfaces = new HashMap<>();
        private final Map<Coords, BoundingBox> bounds = new HashMap<>();
        private BoardScene scene;
        private Function<Coords, BoardTacticalGeometry.Surface> terrain;
        private Function<Coords, BoardBridge.Shape> bridges;

        /**
         * Drops the carriers whose terrain changed, and the bounds when the tiles or the surfaces changed.
         * {@code whole}: the bridges of whole-hex carriers (see the constructor), null for regular ones.
         */
        void update(BoardScene next, Function<Coords, BoardTacticalGeometry.Surface> finished,
              Function<Coords, BoardBridge.Shape> whole) {
            if (whole != bridges) { clear(); }
            if (scene != null && (scene.tiles() != next.tiles() || terrain != finished)) { bounds.clear(); }
            scene = next;
            terrain = finished;
            bridges = whole;
            surfaces.entrySet().removeIf(entry -> {
                if (entry.getValue().current(finished)) { return false; }
                entry.getValue().dispose();
                bounds.remove(entry.getKey());
                return true;
            });
        }

        /** Frees the carriers of every other chunk. */
        void retain(Set<Coords> chunks) {
            bounds.keySet().retainAll(chunks);
            surfaces.entrySet().removeIf(entry -> {
                if (chunks.contains(entry.getKey())) { return false; }
                entry.getValue().dispose();
                return true;
            });
        }

        /** Submits the chunks in view; {@code state} reaches the shader as each renderable's user data. */
        void submit(ModelBatch batch, Camera camera, Shader shader, Material material, Object state,
              Collection<Coords> chunks) {
            for (Coords coords : chunks) {
                BoundingBox box = bounds.computeIfAbsent(coords, key -> GpuHexSurface.bounds(scene, key, terrain, bridges));
                if (!camera.frustum.boundsInFrustum(box)) { continue; }
                surfaces.computeIfAbsent(coords, key -> new GpuHexSurface(scene, key, terrain, bridges))
                      .submit(batch, camera, shader, material, state);
            }
        }

        long bytes() { return surfaces.values().stream().mapToLong(GpuHexSurface::bytes).sum(); }

        void clear() {
            surfaces.values().forEach(GpuHexSurface::dispose);
            surfaces.clear();
            bounds.clear();
            scene = null;
            terrain = null;
        }

        @Override
        public void dispose() { clear(); }
    }
}
