/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BiConsumer;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/**
 * Blades over finished terrain: a meadow's grass and earth tundra's sward, its tussocks, and a field's wheat and
 * maize. Nothing of them is planted or stored. Each kind is drawn as instances without instance data over square tiles
 * fixed in the world: an instance's index says which tile of its run and which of the tile's candidates it is, and the
 * vertex stage places it (terrain-grass.glsl) where the ground map ({@link GpuGroundMap}) says the drawn ground grows
 * its kind, at the map's height. Here, each frame, the runs of tiles the camera sees are listed and the map is kept
 * under them. GL ownership stays here.
 */
final class GpuGroundCover implements Disposable {
    static final class Wind extends FloatAttribute {
        static final long TYPE = register("boardVegetationWind");
        Wind() { super(TYPE, 1); }
        @Override
        public Wind copy() { return new Wind(); }
    }

    /** The ranks a hex's worth of candidates span: a blade stands while its rank is below its density times these. */
    static final int RANKS_PER_HEX = 4096;
    /**
     * Tiles are squares of this many metres. A tile holds this many grass or wheat candidates, 7 a square metre;
     * {@link #TUFTS} tufts of {@link #TUFT_BLADES} blades each; and maize in {@link #CROP_CELLS} cells a side, each too
     * small for more than two plants, which then stand side by side in one row.
     */
    private static final float TILE_METRES = 8;
    private static final int TILE_BLADES = 448, TUFTS = 2, TUFT_BLADES = 112, CROP_CELLS = 5;
    /** Projected pixels per hex over which grass blades grow in. */
    static final float START_PIXELS = 120, FULL_PIXELS = 500;
    /**
     * Projected pixels per hex over which a tussock's blades stand up, a little before the meadow's first blades, and
     * below which fewer of them stand, each widening by as much (terrain-grass.glsl).
     */
    static final float TUFT_START_PIXELS = 100, TUFT_STAND_PIXELS = 140, TUFT_KEEP_PIXELS = 400;
    /**
     * The blade templates, coarse and fine: each a list of ribbons, each its part (terrain-grass.glsl) and its vertex
     * pairs as fractions of its length, its tip closing it. Grass is one blade at two or four segments; wheat a stalk
     * with its ear between vertices at .80 and .96 (terrain-crops.glsl's WHEAT_EAR falls just inside them) and the awns
     * at the tip; maize its stalk and tassel (part 2) and six leaves alternating up it (parts 3 to 8), each a single
     * segment in the coarse template, so a plant keeps its leaves across the switch; a tundra tussock's blade (part 10)
     * arches out from its tuft's centre.
     */
    private static final float[][][] GRASS = { { { 0, 0, .5f } }, { { 0, 0, .25f, .5f, .75f } } };
    private static final float[][][] TUSSOCK = { { { 10, 0, .5f } }, { { 10, 0, .25f, .5f, .75f } } };
    private static final float[][][] WHEAT = { { { 1, 0, .8f, .96f } }, { { 1, 0, .45f, .8f, .83f, .96f } } };
    private static final float[][][] MAIZE = {
          { { 2, 0, .55f, .9f }, { 3, 0, .5f }, { 4, 0, .5f }, { 5, 0, .5f }, { 6, 0, .5f }, { 7, 0, .5f }, { 8, 0, .5f } },
          { { 2, 0, .3f, .6f, .9f }, { 3, 0, .3f, .6f, .85f }, { 4, 0, .3f, .6f, .85f }, { 5, 0, .3f, .6f, .85f },
                { 6, 0, .3f, .6f, .85f }, { 7, 0, .3f, .6f, .85f }, { 8, 0, .3f, .6f, .85f } } };
    /** The tallest standing blade, as a fraction of the hex width; shared with cosmetic shrub sizing. */
    static final float MAX_HEIGHT_FRACTION = .07f;

    /** What a kind's tiles draw: its templates, the hexes it can grow on, and the instances a tile needs at a size. */
    private enum Kind {
        GRASS(GpuGroundCover.GRASS), TUSSOCK(GpuGroundCover.TUSSOCK), WHEAT(GpuGroundCover.WHEAT), MAIZE(GpuGroundCover.MAIZE);

        final float[][][] templates;

        Kind(float[][][] templates) { this.templates = templates; }

        /** Whether a hex can grow it, or lend its ground to a neighbour that does (a blend reaches across an edge). */
        boolean grows(BoardScene.Tile tile) {
            var biome = BoardBiome.kind(tile);
            return switch (this) {
                case GRASS -> tile.surface() == BoardScene.Surface.GRASS || biome == BoardScene.Biome.TUNDRA;
                case TUSSOCK -> biome == BoardScene.Biome.TUNDRA;
                case WHEAT, MAIZE -> biome == BoardScene.Biome.FIELD;
            };
        }

        /** The instances a tile draws where a hex is {@code pixels} wide: a prefix by rank. */
        int drawn(float pixels) {
            return switch (this) {
                case GRASS -> (int) Math.ceil(TILE_BLADES * BoardQuality.current.meadowShare * density(pixels));
                case TUSSOCK -> TUFTS * (int) Math.ceil(TUFT_BLADES * tuftDensity(pixels));
                case WHEAT -> (int) Math.ceil(TILE_BLADES * GpuBiomeVegetation.cropDensity(BoardFields.Crop.WHEAT, pixels));
                case MAIZE -> (int) Math.ceil(2 * CROP_CELLS * CROP_CELLS
                      * GpuBiomeVegetation.cropDensity(BoardFields.Crop.MAIZE, pixels));
            };
        }

        /** The grass and the tussocks the Grass blades option hides; a field's plants stand either way. */
        boolean grass() { return this == GRASS || this == TUSSOCK; }
    }

    /** Whether a hex can grow any blade: a meadow's or tundra's grass, or a field's plants. */
    static boolean grows(BoardScene.Tile tile) {
        return Kind.GRASS.grows(tile) || Kind.WHEAT.grows(tile);
    }

    /** One blade template's mesh: no instance data, a blade is its instance's index. */
    private static final class Template implements Disposable {
        final GpuInstancedMesh mesh;
        final ModelInstance instance;

        Template(float[][] ribbons) {
            int vertices = 0, triangles = 0;
            for (float[] ribbon : ribbons) {
                vertices += (ribbon.length - 1) * 2 + 1;
                triangles += (ribbon.length - 1) * 2 - 1;
            }
            mesh = new GpuInstancedMesh(vertices, triangles * 3,
                  new VertexAttributes(VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked()));
            float[] points = new float[vertices * 7];
            short[] indices = new short[triangles * 3];
            int vertex = 0, index = 0;
            for (float[] ribbon : ribbons) {
                int count = (ribbon.length - 1) * 2 + 1;
                for (int i = 0; i < count; i++) {
                    int at = (vertex + i) * 7;
                    points[at] = i == count - 1 ? 0 : i % 2 == 0 ? -1 : 1;
                    points[at + 1] = i == count - 1 ? 1 : ribbon[1 + i / 2];
                    points[at + 2] = ribbon[0];
                    points[at + 5] = 1;
                    points[at + 6] = Color.WHITE.toFloatBits();
                }
                for (int i = 0; i < count - 2; i++) {
                    indices[index++] = (short) (vertex + (i % 2 == 0 ? i : i + 1));
                    indices[index++] = (short) (vertex + (i % 2 == 0 ? i + 1 : i));
                    indices[index++] = (short) (vertex + i + 2);
                }
                vertex += count;
            }
            mesh.setVertices(points); mesh.setIndices(indices);
            ModelBuilder builder = new ModelBuilder();
            builder.begin();
            builder.part("living-cover", mesh, GL20.GL_TRIANGLES,
                  new Material(ColorAttribute.createDiffuse(Color.WHITE), IntAttribute.createCullFace(GL20.GL_NONE), new Wind()));
            builder.manage(mesh);
            instance = new ModelInstance(builder.end());
        }

        @Override
        public void dispose() { instance.model.dispose(); }
    }

    /** One kind's draw this frame: its coarse and fine templates, made when first drawn, and its runs of tiles. */
    private static final class Layer implements Disposable {
        final Kind kind;
        final Template[] templates = new Template[2];
        /** Per run its first tile's column, its row and the instances each tile draws (terrain-grass.glsl's u_coverRun). */
        final FloatArray runs = new FloatArray();
        final IntArray instances = new IntArray();
        /** The run being gathered along a tile row: its first column and the most any of its tiles draws. */
        int first, most;

        Layer(Kind kind) { this.kind = kind; }

        /** The next tile of a row draws {@code drawn}: a run ends where one needs a quarter more or fewer than it draws. */
        void tile(int column, int row, int drawn, boolean last) {
            if (last || drawn > most * 1.25f || drawn < most * .8f) {
                if (most > 0) {
                    runs.addAll(first, row, most);
                    instances.add((column - first) * most);
                }
                first = column;
                most = 0;
            }
            most = Math.max(most, drawn);
        }

        ModelInstance instance(boolean fine) {
            int detail = fine ? 1 : 0;
            if (templates[detail] == null) { templates[detail] = new Template(kind.templates[detail]); }
            templates[detail].mesh.drawRuns("u_coverRun", runs, instances);
            return templates[detail].instance;
        }

        @Override
        public void dispose() {
            for (int detail = 0; detail < templates.length; detail++) {
                if (templates[detail] != null) { templates[detail].dispose(); }
                templates[detail] = null;
            }
        }
    }

    private final Layer[] layers = { new Layer(Kind.GRASS), new Layer(Kind.TUSSOCK), new Layer(Kind.WHEAT), new Layer(Kind.MAIZE) };
    /** The ground under the view; null once disposed, until the next frame that draws cover. */
    private GpuGroundMap map = new GpuGroundMap();
    private final List<ModelInstance> shown = new ArrayList<>();
    /**
     * Where each kind can grow, for the scene's tiles last asked of: a bit a tile and kind over the board's tiles from
     * {@code tilesLeft}, {@code tilesBottom}, {@code tilesAcross} a row; and the board's lowest and highest ground.
     */
    private List<BoardScene.Tile> tiles;
    private final BitSet growing = new BitSet();
    private int tilesLeft, tilesBottom, tilesAcross, tilesUp;
    private final boolean[] grown = new boolean[Kind.values().length];
    private float lowest, highest;
    // The seen ground's outline (prepare): eight points, then three for its arithmetic.
    private final Vector3[] outline = new Vector3[11];
    { for (int point = 0; point < outline.length; point++) { outline[point] = new Vector3(); } }
    private int revision = -1, boardId = -1;
    /** A board whose map has not been drawn yet. */
    private boolean fresh;

    static String vertex(String source) {
        String meadow = GpuShaderSource.read("surface-noise.glsl") + GpuShaderSource.read("terrain-meadow.glsl")
              + GpuShaderSource.read("terrain-tundra-sward.glsl");
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        String blade = "#define GRASS_START_PIXELS " + START_PIXELS + "\n#define GRASS_FULL_PIXELS " + FULL_PIXELS
              + "\n#define GRASS_TUFT_START " + TUFT_START_PIXELS + "\n#define GRASS_TUFT_STAND " + TUFT_STAND_PIXELS
              + "\n#define GRASS_TUFT_KEEP " + TUFT_KEEP_PIXELS + "\n#define GRASS_ROOTS_PER_HEX " + RANKS_PER_HEX
              + "\n#define GRASS_TILE_METRES " + TILE_METRES + "\n#define GRASS_TILE_BLADES " + TILE_BLADES + ".0"
              + "\n#define GRASS_TUFTS " + TUFTS + "\n#define GRASS_TUFT_BLADES " + TUFT_BLADES + ".0"
              + "\n#define CROP_CELLS " + CROP_CELLS + "\n#define CROP_HEADLAND " + BoardFields.HEADLAND
              + "\n#define CROP_ROW_METRES " + BoardBiome.ROW_METRES + "\n#define CROP_SPACING " + GpuBiomeVegetation.CROP_SPACING
              + "\n#define CROP_ROW_HALF_WIDTH " + GpuBiomeVegetation.ROW_HALF_WIDTH
              + "\n#define CROP_STALK_REACH " + GpuBiomeVegetation.STALK_REACH
              + "\n#define GRASS_MAX_HEIGHT " + MAX_HEIGHT_FRACTION + "\n"
              // The board's hash and its fields' parcels (terrain-hexes.glsl, terrain-crops.glsl), as the ground draws them.
              + GpuShaderSource.read("terrain-hexes.glsl") + GpuBiomeVegetation.cropSource()
              + GpuGroundDamage.shaderSource() + GpuShaderSource.read("terrain-grass.glsl");
        // A blade that does not grow leaves every vertex outside the clip volume.
        return GpuShaderSource.coverVertex(source.replace("void main() {",
              wind + meadow + blade + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "if (!grassBlade(a_position, coverPosition, coverNormal, coverColor)) {\n"
                    + "gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }\n"));
    }

    /** Prepare the blades' program with the terrain, before any view shows a blade. */
    static void prepareMaterials(BiConsumer<Material, Mesh> consumer) {
        Template sample = new Template(GRASS[0]);
        try {
            consumer.accept(sample.instance.materials.first(), sample.mesh);
        } finally { sample.dispose(); }
    }

    private static float density(float pixels) {
        float t = Math.clamp((pixels - START_PIXELS) / (FULL_PIXELS - START_PIXELS), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** The share of a tussock's blades standing at this projected size (terrain-grass.glsl). */
    private static float tuftDensity(float pixels) {
        return Math.clamp(pixels / TUFT_KEEP_PIXELS, 0, 1)
              * BoardRelief.smooth((pixels - TUFT_START_PIXELS) / (TUFT_STAND_PIXELS - TUFT_START_PIXELS));
    }

    /**
     * Whether, with {@code grass}, grass or tussocks or, with {@code crops}, a field's plants can stand at this
     * scale. Orthographic detail has one scale everywhere; perspective detail still depends on each tile's depth.
     */
    static boolean visibleAtScale(Camera camera, boolean grass, boolean crops) {
        float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, camera.position);
        boolean perspective = camera.projection.val[Matrix4.M33] == 0;
        // A tussock's blades stand before the meadow's.
        return grass && (perspective || tuftDensity(pixels) > 0)
              || crops && (perspective || GpuBiomeVegetation.cropDensity(BoardFields.Crop.MAIZE, pixels) > 0);
    }

    /** The ground map, whose textures and frame the blades' vertex stage reads (terrain-grass.glsl). */
    GpuGroundMap map() {
        if (map == null) { map = new GpuGroundMap(); }
        return map;
    }

    /** A chunk or its paint was installed within these bounds: the map draws that rectangle again. */
    void installed(BoundingBox bounds) {
        if (map != null) { map.changed(bounds); }
    }

    /** How often the ground map was drawn again, whole or in part: none while the view and the terrain stand still. */
    long redraws() { return map == null ? 0 : map.redraws(); }

    /** The ground map's renders and this frame's runs and instances by kind, for measurements. */
    String stats() {
        StringBuilder text = new StringBuilder(map().stats());
        for (Layer layer : layers) {
            int instances = 0;
            for (int run = 0; run < layer.instances.size; run++) { instances += layer.instances.get(run); }
            text.append(", ").append(layer.kind).append(' ').append(layer.instances.size).append(" runs ").append(instances);
        }
        return text.toString();
    }

    /**
     * Before a frame draws: each kind's runs of tiles under this view, and the ground map under them. The tiles cover
     * the ground the camera sees close enough for a blade to stand, where the board grows the kind; a run draws the
     * instances its nearest tile needs, and the vertex stage thins the farther ones. {@code grass}: the Grass blades
     * option, which hides the grass and the tussocks; a field's plants stand either way. Nothing is kept per tile or hex
     * but where each kind can grow at all.
     */
    void prepare(BoardScene scene, Camera camera, boolean grass, GpuGroundMap.Terrain terrain) {
        shown.clear();
        for (Layer layer : layers) {
            layer.runs.clear();
            layer.instances.clear();
        }
        if (scene == null) { return; }
        if (revision != BoardGeometry.revision() || boardId != scene.boardId() || tiles != scene.tiles()) {
            fresh |= boardId != scene.boardId();
            revision = BoardGeometry.revision();
            boardId = scene.boardId();
            survey(scene);
            if (map != null) { map.invalidate(); }
        }
        boolean any = false;
        for (Layer layer : layers) { any |= grown[layer.kind.ordinal()] && (grass || !layer.kind.grass()); }
        if (!any) { return; }
        boolean perspective = camera.projection.val[Matrix4.M33] == 0;
        // The smallest hex any of the board's kinds shows on: a field's plants stand much farther out than grass.
        float smallest = grown[Kind.MAIZE.ordinal()] ? GpuBiomeVegetation.CROP_FADE[0] * BoardQuality.current.detailScale
              : TUFT_START_PIXELS;
        // Beyond this depth a hex is too small for any of them: its size falls with its depth, as at the far plane.
        float scale = perspective ? BoardCamera.pixelsPerUnit(camera, outline[8].set(camera.position)
              .mulAdd(camera.direction, camera.far)) * camera.far * BoardGeometry.width() : 0;
        float reach = perspective ? scale / smallest : Float.POSITIVE_INFINITY;
        // The seen ground's outline: where the view's corner rays meet the lowest and the highest ground.
        for (int corner = 0; corner < 4; corner++) {
            Vector3 near = outline[8].set(corner % 2 * 2 - 1, corner / 2 * 2 - 1, -1).prj(camera.invProjectionView);
            Vector3 far = outline[9].set(corner % 2 * 2 - 1, corner / 2 * 2 - 1, 1).prj(camera.invProjectionView).sub(near);
            float depth = outline[10].set(near).sub(camera.position).dot(camera.direction), along = far.dot(camera.direction);
            for (int level = 0; level < 2; level++) {
                float t = Math.abs(far.z) < 1e-6f ? 1 : ((level == 0 ? lowest : highest) - near.z) / far.z;
                // No farther than a blade shows.
                if (perspective) { t = Math.min(t < 0 ? Float.POSITIVE_INFINITY : t, Math.max(0, (reach - depth) / along)); }
                outline[corner * 2 + level].set(near).mulAdd(far, t);
            }
        }
        float tile = BoardRelief.metres(TILE_METRES);
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        for (int point = 0; point < 8; point++) {
            minX = Math.min(minX, outline[point].x); maxX = Math.max(maxX, outline[point].x);
            minY = Math.min(minY, outline[point].y); maxY = Math.max(maxY, outline[point].y);
        }
        // The board's own ground alone: no blade stands beyond it.
        float left = tilesLeft * tile, right = (tilesLeft + tilesAcross) * tile;
        minX = Math.max(minX, left); maxX = Math.min(maxX, right);
        minY = Math.max(minY, tilesBottom * tile); maxY = Math.min(maxY, (tilesBottom + tilesUp) * tile);
        if (!(maxX > minX) || !(maxY > minY)) { return; }
        boolean every = grown[Kind.TUSSOCK.ordinal()] || grown[Kind.MAIZE.ordinal()];
        if (fresh) {
            // A board's first frame draws the map once, at whatever size: its programs meet the driver while the board loads.
            fresh = false;
            map().update(minX, minY, maxX, maxY, every, terrain);
        }
        // A hex's pixels at one size everywhere, or falling with depth: a tile's nearest point is its centre's depth
        // less its half extent along the view.
        float flat = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, outline[0]), pixels = 0;
        Vector3 eye = camera.position, view = camera.direction;
        float half = (Math.abs(view.x) + Math.abs(view.y)) * tile / 2 + Math.abs(view.z) * (highest - lowest) / 2;
        for (int row = (int) Math.floor(minY / tile); row <= Math.min((int) Math.floor(maxY / tile), tilesBottom + tilesUp - 1); row++) {
            // The outline's reach across this row of tiles: of every line between two of its points.
            float bottom = row * tile, top = bottom + tile, from = Float.POSITIVE_INFINITY, to = Float.NEGATIVE_INFINITY;
            for (int a = 0; a < 8; a++) {
                for (int b = a; b < 8; b++) {
                    Vector3 p = outline[a], q = outline[b];
                    if (Math.max(p.y, q.y) < bottom || Math.min(p.y, q.y) > top) { continue; }
                    float span = q.y - p.y;
                    float enter = span == 0 ? 0 : Math.clamp((bottom - p.y) / span, 0, 1);
                    float leave = span == 0 ? 1 : Math.clamp((top - p.y) / span, 0, 1);
                    float x0 = p.x + (q.x - p.x) * enter, x1 = p.x + (q.x - p.x) * leave;
                    from = Math.min(from, Math.min(x0, x1)); to = Math.max(to, Math.max(x0, x1));
                }
            }
            from = Math.max(from, left); to = Math.min(to, right);
            if (!(to > from)) { continue; }
            int first = (int) Math.floor(from / tile), end = Math.min((int) Math.floor(to / tile) + 1, tilesLeft + tilesAcross);
            for (Layer layer : layers) { layer.first = first; layer.most = 0; }
            for (int column = first; column <= end; column++) {
                float size = 0;
                int bit = -1;
                if (column < end) {
                    float depth = ((column + .5f) * tile - eye.x) * view.x + (bottom + tile / 2 - eye.y) * view.y
                          + ((lowest + highest) / 2 - eye.z) * view.z - half;
                    size = perspective ? scale / Math.max(depth, camera.near) : flat;
                    bit = ((row - tilesBottom) * tilesAcross + column - tilesLeft) * layers.length;
                }
                for (Layer layer : layers) {
                    int kind = layer.kind.ordinal();
                    boolean drawn = bit >= 0 && growing.get(bit + kind) && (grass || !layer.kind.grass());
                    int count = drawn ? layer.kind.drawn(size) : 0;
                    layer.tile(column, row, count, column == end);
                    if (count > 0) { pixels = Math.max(pixels, size); }
                }
            }
        }
        for (Layer layer : layers) {
            if (!layer.instances.isEmpty()) {
                ModelInstance instance = layer.instance(pixels >= 280);
                instance.transform.setToTranslation((minX + maxX) / 2, (minY + maxY) / 2, highest);
                shown.add(instance);
            }
        }
        if (shown.isEmpty()) { return; }
        map().update(minX, minY, maxX, maxY, every, terrain);
    }

    /**
     * Where each kind can grow on the scene's tiles: every tile a growing hex or its neighbour's edge reaches, so that
     * no instance is drawn over ground that grows none of its kind; and the board's lowest and highest ground.
     */
    private void survey(BoardScene scene) {
        tiles = scene.tiles();
        float tile = BoardRelief.metres(TILE_METRES), reach = BoardGeometry.width() * .5f + BoardRelief.metres(6);
        Vector3 last = BoardGeometry.center(new Coords(scene.width() - 1, scene.height() - 1), 0);
        float edge = BoardGeometry.width();
        tilesLeft = (int) Math.floor(-edge / tile);
        tilesBottom = (int) Math.floor((Math.min(0, last.y) - edge) / tile);
        tilesAcross = (int) Math.floor((last.x + edge) / tile) - tilesLeft + 1;
        tilesUp = (int) Math.floor((Math.max(0, last.y) + edge) / tile) - tilesBottom + 1;
        growing.clear();
        java.util.Arrays.fill(grown, false);
        lowest = Float.POSITIVE_INFINITY;
        highest = Float.NEGATIVE_INFINITY;
        Kind[] kinds = Kind.values();
        for (BoardScene.Tile hex : tiles) {
            lowest = Math.min(lowest, BoardGeometry.groundZ(hex));
            highest = Math.max(highest, BoardGeometry.groundZ(hex));
            int grows = 0;
            for (Kind kind : kinds) {
                if (kind.grows(hex)) {
                    grows |= 1 << kind.ordinal();
                    grown[kind.ordinal()] = true;
                }
            }
            if (grows == 0) { continue; }
            float x = BoardGeometry.centerX(hex.coords()), y = BoardGeometry.centerY(hex.coords());
            for (int row = (int) Math.floor((y - reach) / tile); row <= (int) Math.floor((y + reach) / tile); row++) {
                for (int column = (int) Math.floor((x - reach) / tile); column <= (int) Math.floor((x + reach) / tile); column++) {
                    if (row < tilesBottom || row >= tilesBottom + tilesUp || column < tilesLeft || column >= tilesLeft + tilesAcross) {
                        continue;
                    }
                    int bit = ((row - tilesBottom) * tilesAcross + column - tilesLeft) * kinds.length;
                    for (Kind kind : kinds) {
                        if ((grows & 1 << kind.ordinal()) != 0) { growing.set(bit + kind.ordinal()); }
                    }
                }
            }
        }
        if (!grown[Kind.WHEAT.ordinal()]) { return; }
        // A field's tile draws the crops of the parcels that reach it, or a plant's own offset beyond it.
        int wheat = Kind.WHEAT.ordinal(), maize = Kind.MAIZE.ordinal();
        grown[wheat] = grown[maize] = false;
        for (int index = 0; index < tilesAcross * tilesUp; index++) {
            int bit = index * kinds.length;
            if (!growing.get(bit + wheat)) { continue; }
            float left = (index % tilesAcross + tilesLeft) * TILE_METRES - .5f, right = left + TILE_METRES + 1;
            float low = (index / tilesAcross + tilesBottom) * TILE_METRES - .5f, high = low + TILE_METRES + 1;
            boolean[] crops = new boolean[2];
            for (long parcel : BoardFields.parcels(new float[] { left, right, right, left }, new float[] { low, low, high, high })) {
                crops[BoardFields.crop(BoardFields.code(parcel)).ordinal()] = true;
            }
            growing.set(bit + wheat, crops[BoardFields.Crop.WHEAT.ordinal()]);
            growing.set(bit + maize, crops[BoardFields.Crop.MAIZE.ordinal()]);
            grown[wheat] |= crops[BoardFields.Crop.WHEAT.ordinal()];
            grown[maize] |= crops[BoardFields.Crop.MAIZE.ordinal()];
        }
    }

    /** This frame's blades ({@link #prepare}): one draw a kind, for the cover's own batch. */
    List<ModelInstance> visible() { return shown; }

    @Override
    public void dispose() {
        for (Layer layer : layers) { layer.dispose(); }
        shown.clear();
        tiles = null;
        if (map != null) { map.dispose(); }
        map = null;
    }
}
