/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pool;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.util.UIUtil;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/** GL-owned geometry and cached text. Camera movement never rebuilds the surface meshes or label artwork. */
final class GpuTactical implements Disposable {
    /** White dash travel in tactical units per second. 0f is static; negative values reverse direction. */
    static final float OUTLINE_SCROLL_SPEED = 4f;
    /** A small depth bias keeps range curtains/tints stable where they share a vertical terrain face. */
    static final float COPLANAR_DEPTH_FAR = 1f - .000001f;

    private record TextImage(BoardScene.Pixels pixels, float x, float y) { }
    private static final int PAGE_TRIANGLES = 10000;
    private record FillKey(BoardTactical.Fill fill, int layer) { }
    private record FillGeometry(Map<Coords, BoardTacticalGeometry.Surface> surfaces, float[] vertices, float planeZ) { }
    private record WallGeometry(Map<Coords, BoardTacticalGeometry.Surface> surfaces, int[] levels,
          float[] body, float[] uprightOutline, float[] flatOutline) { }
    /**
     * Presentations 0, 3 and 4 are always drawn; 1 and 2 are the upright and flat wall alternatives. 3 holds the map
     * restrictions and 4 the other fills on their owner's hex plane, both depth-tested nearer (see
     * {@link #PLANE_SEE_THROUGH_LEVELS}).
     */
    private record Group(int presentation, BasicStroke stroke) { }
    /**
     * How much nearer the markings on the hex planes are depth-tested, in levels: a step's slope lying over a hex's
     * edge rises up to two levels, so the terrain inside their hex never covers them (the user's report of
     * 2026-10-04), while a hill in front of the hex still does. Only their depth moves; they stay flat where they are.
     */
    static final float PLANE_SEE_THROUGH_LEVELS = 2;
    private static final Group PLANES = new Group(4, null);
    private record Span(float[] vertices, int start, int count) { }
    /** A page's vertices: plain fills and walls, and outlines, whose dash pattern takes a texture coordinate. */
    private static final VertexAttributes PLAIN_VERTICES = new VertexAttributes(VertexAttribute.Position(),
          VertexAttribute.ColorPacked());
    private static final VertexAttributes INKED_VERTICES = new VertexAttributes(VertexAttribute.Position(),
          VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0));
    private final ModelBatch batch = new ModelBatch(GpuShaderManager.provider("Tactical pages", GpuUnitShader::unlitProvider));
    private final ShapeRenderer rulerShapes;
    /** Luminance differs as well as hue; the blocked section also has a dash pattern. */
    static final int RULER_CLEAR = 0x64D8A2FF;
    static final int RULER_MODIFIED = 0xF4D35EFF;
    static final int RULER_BLOCKED = 0xD84959FF;
    private final GpuHexMasks hexMasks = new GpuHexMasks();
    private final GpuHexMasks deploymentTint = new GpuHexMasks();
    private final GpuElectronicWarfare electronicWarfare = new GpuElectronicWarfare();
    private final SpriteBatch textBatch;
    private final GpuTextures<List<BoardTactical.Text>> textures = new GpuTextures<>(true);
    private final Map<List<BoardTactical.Text>, TextImage> images = new HashMap<>();
    private final Map<BoardTactical.Point, List<BoardTactical.Text>> labels = new LinkedHashMap<>();
    private final Material material = new Material(ColorAttribute.createDiffuse(Color.WHITE),
          new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
          new DepthTestAttribute(GL20.GL_LEQUAL, false), IntAttribute.createCullFace(GL20.GL_NONE));
    private final Material wallMaterial = material.copy();
    private BoardScene previous;
    private List<BoardTactical.Fill> impassable = List.of();
    private Coords impassableHover;
    private boolean movementPlanning;
    private Map<FillKey, FillGeometry> fills = Map.of();
    private float[] planeConnections = new float[0];
    // The fills and tiles planeConnections joins: they follow those and the board's levels, never its terrain detail.
    private List<BoardTactical.Fill> planeFills;
    private List<BoardScene.Tile> planeTiles;
    private Map<BoardTactical.Wall, WallGeometry> walls = Map.of();
    private Map<Group, List<Page>> pages = Map.of();
    private Map<BasicStroke, Material> outlines = Map.of();
    private int tuning = -1;
    private long builds;
    private final float outlineSpeed;
    private double scrollDistance;
    private final Function<Coords, BoardTacticalGeometry.Surface> terrain;

    GpuTactical() {
        this(OUTLINE_SCROLL_SPEED);
    }

    GpuTactical(float outlineSpeed) {
        this(outlineSpeed, null, null, null);
    }

    GpuTactical(Function<Coords, BoardTacticalGeometry.Surface> terrain) {
        this(OUTLINE_SCROLL_SPEED, terrain, null, null);
    }

    /**
     * The labels and the ruler draw with the view's sprite and shape programs ({@code sprites}, {@code shapes}), which
     * their batch and renderer do not own.
     */
    GpuTactical(Function<Coords, BoardTacticalGeometry.Surface> terrain, ShaderProgram sprites, ShaderProgram shapes) {
        this(OUTLINE_SCROLL_SPEED, terrain, sprites, shapes);
    }

    // Without shared programs the text batch and the ruler compile and own libGDX's own.
    private GpuTactical(float outlineSpeed, Function<Coords, BoardTacticalGeometry.Surface> terrain,
          ShaderProgram sprites, ShaderProgram shapes) {
        this.outlineSpeed = outlineSpeed;
        this.terrain = terrain;
        textBatch = new SpriteBatch(1000, sprites);
        rulerShapes = new ShapeRenderer(5000, shapes);
        wallMaterial.set(new DepthTestAttribute(GL20.GL_LEQUAL, 0, COPLANAR_DEPTH_FAR, false));
    }

    void update(BoardScene scene) {
        update(scene, false);
    }

    void update(BoardScene scene, boolean detailChanged) {
        update(scene, detailChanged, null);
    }

    void update(BoardScene scene, boolean detailChanged, Coords hover) {
        update(scene, detailChanged, hover, !scene.plannedPath().isEmpty());
    }

    void update(BoardScene scene, boolean detailChanged, Coords hover, boolean planning) {
        update(scene, detailChanged, hover, planning, Entity.NONE);
    }

    /**
     * {@code planning} is true while a move is plotted: the battle HUD's route (GpuMovePlan), which the board view
     * passes; the scene's own planned path is always empty there. {@code hoveredUnit} is the unit under the pointer,
     * whose E(C)CM field opens.
     */
    void update(BoardScene scene, boolean detailChanged, Coords hover, boolean planning, int hoveredUnit) {
        boolean boardChanged = previous == null || previous.boardId() != scene.boardId()
              || previous.width() != scene.width() || previous.height() != scene.height();
        boolean terrainChanged = detailChanged || boardChanged || !sameTerrain(scene);
        electronicWarfare.update(scene, terrainChanged ? surfaces(scene) : null, hover, hoveredUnit);
        Coords highlighted = !planning && hover != null && scene.tile(hover) != null && scene.tile(hover).impassable()
              ? hover : null;
        boolean markingChanged = boardChanged || !sameImpassable(scene) || planning != movementPlanning
              || !Objects.equals(highlighted, impassableHover);
        if (markingChanged) {
            impassableHover = highlighted;
            movementPlanning = planning;
            impassable = BoardImpassable.fills(scene, highlighted, planning);
        }
        if (tuning != BoardGeometry.revision() || terrainChanged || markingChanged
              || !previous.tactical().fills().equals(scene.tactical().fills())
              || !previous.tactical().walls().equals(scene.tactical().walls())
              || !previous.tactical().flatWalls().equals(scene.tactical().flatWalls())) {
            rebuild(scene, boardChanged || tuning != BoardGeometry.revision() || terrain == null && terrainChanged,
                  terrainChanged);
            tuning = BoardGeometry.revision();
        }
        if (previous == null || !previous.tactical().labels().equals(scene.tactical().labels())) {
            labels.clear();
            scene.tactical().labels().forEach(label ->
                  labels.computeIfAbsent(label.anchor(), key -> new ArrayList<>()).add(label.text()));
            labels.replaceAll((anchor, texts) -> List.copyOf(texts));
            images.keySet().retainAll(labels.values());
            Map<List<BoardTactical.Text>, BoardScene.Pixels> pixels = new HashMap<>();
            labels.values().forEach(texts -> pixels.put(texts, images.computeIfAbsent(texts, GpuTactical::paintText).pixels()));
            textures.update(pixels);
        }
        previous = scene;
    }

    private boolean sameImpassable(BoardScene scene) {
        if (previous.tiles() == scene.tiles()) { return true; }
        for (int i = 0; i < scene.tiles().size(); i++) {
            if (previous.tiles().get(i).impassable() != scene.tiles().get(i).impassable()) { return false; }
        }
        return true;
    }

    private boolean sameTerrain(BoardScene scene) {
        if (previous.tiles() == scene.tiles()) {
            return true;
        }
        for (int i = 0; i < scene.tiles().size(); i++) {
            BoardScene.Tile a = previous.tiles().get(i), b = scene.tiles().get(i);
            if (!a.sameGeometry(b)) {
                return false;
            }
        }
        return true;
    }

    private Function<Coords, BoardTacticalGeometry.Surface> surfaces(BoardScene scene) {
        return terrain == null ? BoardTacticalGeometry.surfaces(scene) : terrain;
    }

    private void rebuild(BoardScene scene, boolean reset, boolean terrainChanged) {
        Function<Coords, BoardTacticalGeometry.Surface> surfaces = surfaces(scene);
        var clipper = new BoardTacticalGeometry.Clipper();
        Map<FillKey, FillGeometry> nextFills = new HashMap<>();
        Map<BoardTactical.Wall, WallGeometry> nextWalls = new HashMap<>();
        Map<Group, List<float[]>> groups = new LinkedHashMap<>();
        var deployment = BoardDeploymentGeometry.zoneFills(scene);
        var perimeter = BoardDeploymentGeometry.perimeter(scene, deployment);
        List<BoardTactical.Wall> commands = new ArrayList<>(scene.tactical().walls());
        commands.addAll(BoardDeploymentGeometry.walls(scene, perimeter));
        commands = BoardRangeBorder.join(commands);
        deploymentTint.updateDeployment(scene, deployment.values(), surfaces);
        // Unit bases draw in their own pass (GpuUnitBases), so the regular-border mask always applies.
        boolean masked = hexMasks.update(scene, surfaces, true);
        addFills(scene, masked ? List.of() : scene.tactical().fills(), new Group(0, null), groups, nextFills,
              surfaces, clipper, reset, terrainChanged);
        // The joins read only the fills, tiles and tuning: a detail change keeps them. Panning over a movement envelope
        // joined its planes again on every refined chunk, the sampled cost of its slowest frames.
        if (reset || scene.tiles() != planeTiles || !scene.tactical().fills().equals(planeFills)) {
            FloatArray connections = new FloatArray();
            BoardTacticalGeometry.connectPlanes(scene, scene.tactical().fills(), packed(connections, null));
            planeConnections = reuse(connections, planeConnections);
            planeFills = scene.tactical().fills();
            planeTiles = scene.tiles();
        }
        add(groups, PLANES, planeConnections);
        // Keep map restrictions in their own retained pages, outside the sprite-mask fast path.
        addFills(scene, impassable, new Group(3, null), groups, nextFills, surfaces, clipper, reset, terrainChanged);
        groups.put(new Group(1, null), new ArrayList<>());
        for (BoardTactical.Wall wall : commands) {
            WallGeometry geometry = reset ? null : walls.get(wall);
            if (geometry == null || terrainChanged && (!current(geometry.surfaces(), surfaces)
                  || !Arrays.equals(geometry.levels(), wallLevels(scene, wall)))) {
                geometry = wallGeometry(scene, wall, surfaces, clipper, geometry);
            }
            nextWalls.put(wall, geometry);
            add(groups, new Group(1, null), geometry.body());
            if (wall.outline() != null) {
                add(groups, new Group(1, wall.outline().stroke()), geometry.uprightOutline());
            }
        }
        addFills(scene, scene.tactical().flatWalls(), new Group(2, null), groups, nextFills, surfaces, clipper, reset, terrainChanged);
        addFills(scene, perimeter, new Group(2, null), groups, nextFills, surfaces, clipper, reset, terrainChanged);
        for (BoardTactical.Wall wall : commands) {
            if (wall.outline() != null) {
                add(groups, new Group(2, wall.outline().stroke()), nextWalls.get(wall).flatOutline());
            }
        }
        replacePages(groups);
        fills = nextFills;
        walls = nextWalls;
        builds++;
    }

    private void addFills(BoardScene scene, List<BoardTactical.Fill> commands, Group group,
          Map<Group, List<float[]>> groups, Map<FillKey, FillGeometry> retained,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces, BoardTacticalGeometry.Clipper clipper,
          boolean reset, boolean terrainChanged) {
        groups.computeIfAbsent(group, ignored -> new ArrayList<>());
        for (int layer = 0; layer < commands.size(); layer++) {
            BoardTactical.Fill command = commands.get(layer);
            if (BoardDeploymentGeometry.isZone(command)) { continue; }
            FillKey key = new FillKey(command, command.planeAnchor() != null ? 0 : Math.min(layer, 10000));
            FillGeometry geometry = retained.get(key);
            if (geometry == null) { geometry = reset ? null : fills.get(key); }
            float planeZ = geometry == null || terrainChanged
                  ? floatingHeight(scene, key.fill()) : geometry.planeZ();
            if (geometry == null || terrainChanged
                  && (geometry.planeZ() != planeZ || !current(geometry.surfaces(), surfaces))) {
                Map<Coords, BoardTacticalGeometry.Surface> used = new HashMap<>();
                FloatArray vertices = new FloatArray();
                BoardTacticalGeometry.drape(scene, key.fill(), key.layer(), packed(vertices, null),
                      tracking(surfaces, used), clipper);
                geometry = new FillGeometry(used.isEmpty() ? Map.of() : used,
                      reuse(vertices, geometry == null ? null : geometry.vertices()), planeZ);
            }
            retained.put(key, geometry);
            add(groups, group.presentation() == 0 && command.planeAnchor() != null ? PLANES : group,
                  geometry.vertices());
        }
    }

    private static float floatingHeight(BoardScene scene, BoardTactical.Fill fill) {
        Coords coords = BoardTacticalGeometry.anchorCoords(scene, fill.planeAnchor());
        return coords == null ? Float.NEGATIVE_INFINITY : BoardTacticalGeometry.floatingZ(scene, coords);
    }

    private static WallGeometry wallGeometry(BoardScene scene, BoardTactical.Wall wall,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces, BoardTacticalGeometry.Clipper clipper,
          WallGeometry previous) {
        Map<Coords, BoardTacticalGeometry.Surface> used = new HashMap<>();
        FloatArray body = new FloatArray(), upright = new FloatArray(), flat = new FloatArray();
        var tracked = tracking(surfaces, used);
        var uprightTriangles = packed(upright, wall);
        var flatTriangles = packed(flat, wall);
        BoardTacticalGeometry.wall(scene, wall, false, packed(body, null),
              (ignored, triangle) -> uprightTriangles.accept(triangle), tracked, clipper);
        BoardTacticalGeometry.wall(scene, wall, true, ignored -> { },
              (ignored, triangle) -> flatTriangles.accept(triangle), tracked, clipper);
        return new WallGeometry(used, wallLevels(scene, wall), reuse(body, previous == null ? null : previous.body()),
              reuse(upright, previous == null ? null : previous.uprightOutline()),
              reuse(flat, previous == null ? null : previous.flatOutline()));
    }

    private static int[] wallLevels(BoardScene scene, BoardTactical.Wall wall) {
        int[] levels = new int[7];
        for (int i = 0; i < levels.length; i++) {
            var tile = scene.tile(i == 0 ? wall.coords() : wall.coords().translated(i - 1));
            levels[i] = tile == null ? Integer.MIN_VALUE : tile.elevation();
        }
        return levels;
    }

    private static Function<Coords, BoardTacticalGeometry.Surface> tracking(
          Function<Coords, BoardTacticalGeometry.Surface> surfaces, Map<Coords, BoardTacticalGeometry.Surface> used) {
        return coords -> used.computeIfAbsent(coords, surfaces);
    }

    private static boolean current(Map<Coords, BoardTacticalGeometry.Surface> used,
          Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        for (var entry : used.entrySet()) {
            if (surfaces.apply(entry.getKey()) != entry.getValue()) { return false; }
        }
        return true;
    }

    private static float[] reuse(FloatArray vertices, float[] previous) {
        float[] result = vertices.toArray();
        return Arrays.equals(result, previous) ? previous : result;
    }

    private static void add(Map<Group, List<float[]>> groups, Group group, float[] vertices) {
        if (vertices.length > 0) { groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(vertices); }
    }

    /** Keep the old draw boundaries: changing transparent part centers would change painter ordering. */
    private void replacePages(Map<Group, List<float[]>> groups) {
        Map<Group, List<Page>> next = new LinkedHashMap<>();
        Map<BasicStroke, Material> inks = new HashMap<>();
        List<Page> created = new ArrayList<>();
        List<Texture> createdTextures = new ArrayList<>();
        try {
            for (var entry : groups.entrySet()) {
                Group group = entry.getKey();
                if (entry.getValue().isEmpty()) { continue; }
                Material ink = group.presentation() == 1 ? wallMaterial : material;
                if (group.stroke() != null) {
                    ink = inks.get(group.stroke());
                    if (ink == null) {
                        ink = outlines.get(group.stroke());
                        if (ink == null) {
                            Texture texture = outlineTexture(group.stroke());
                            createdTextures.add(texture);
                            ink = ink(texture, 1 / dashPeriod(group.stroke()));
                        }
                        inks.put(group.stroke(), ink);
                    }
                }
                List<Page> previousPages = pages.getOrDefault(group, List.of());
                List<Page> replacement = new ArrayList<>();
                int capacity = PAGE_TRIANGLES * 3 * (group.stroke() == null ? 4 : 6), count = 0;
                List<Span> spans = new ArrayList<>();
                for (float[] vertices : entry.getValue()) {
                    for (int start = 0; start < vertices.length;) {
                        int length = Math.min(capacity - count, vertices.length - start);
                        spans.add(new Span(vertices, start, length));
                        count += length;
                        start += length;
                        if (count == capacity) {
                            appendPage(replacement, previousPages, spans, group, ink, created);
                            spans = new ArrayList<>();
                            count = 0;
                        }
                    }
                }
                if (!spans.isEmpty()) { appendPage(replacement, previousPages, spans, group, ink, created); }
                next.put(group, replacement);
            }
        } catch (RuntimeException | Error failure) {
            created.forEach(Page::dispose);
            createdTextures.forEach(Texture::dispose);
            throw failure;
        }
        var retained = new HashSet<Page>();
        next.values().forEach(retained::addAll);
        pages.values().forEach(group -> group.stream().filter(page -> !retained.contains(page)).forEach(Page::dispose));
        outlines.forEach((stroke, ink) -> {
            if (!inks.containsKey(stroke)) {
                ink.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture.dispose();
            }
        });
        pages = next;
        outlines = inks;
    }

    private static void appendPage(List<Page> destination, List<Page> previous, List<Span> spans,
          Group group, Material material, List<Page> created) {
        int index = destination.size();
        Page page = index < previous.size() ? previous.get(index) : null;
        if (page == null || !page.spans.equals(spans)) {
            page = new Page(spans, group, material);
            created.add(page);
        }
        destination.add(page);
    }

    /** Pages own only their mesh. Command arrays and animated stroke materials are shared by the active geometry. */
    private static final class Page implements RenderableProvider, Disposable {
        final List<Span> spans;
        final Renderable renderable = new Renderable();

        Page(List<Span> spans, Group group, Material material) {
            this.spans = List.copyOf(spans);
            int count = spans.stream().mapToInt(Span::count).sum(), stride = group.stroke() == null ? 4 : 6;
            float[] vertices = new float[count];
            int offset = 0;
            for (Span span : spans) {
                System.arraycopy(span.vertices(), span.start(), vertices, offset, span.count());
                offset += span.count();
            }
            short[] indices = new short[count / stride];
            for (int i = 0; i < indices.length; i++) { indices[i] = (short) i; }
            Mesh mesh = new Mesh(true, indices.length, indices.length,
                  group.stroke() == null ? PLAIN_VERTICES : INKED_VERTICES);
            try {
                mesh.setVertices(vertices);
                mesh.setIndices(indices);
                renderable.meshPart.set("tactical", mesh, 0, indices.length, GL20.GL_TRIANGLES);
                renderable.meshPart.update();
                renderable.material = material;
            } catch (RuntimeException | Error failure) {
                mesh.dispose();
                throw failure;
            }
        }

        @Override
        public void getRenderables(Array<Renderable> renderables, Pool<Renderable> pool) {
            renderable.shader = null;
            renderable.environment = null;
            renderables.add(renderable);
        }

        @Override
        public void dispose() { renderable.meshPart.mesh.dispose(); }
    }

    private static Consumer<BoardTacticalGeometry.Triangle> packed(FloatArray vertices, BoardTactical.Wall wall) {
        Color color = new Color();
        return triangle -> {
            Color.argb8888ToColor(color, triangle.argb());
            float bits = color.toFloatBits();
            pack(vertices, triangle.a(), bits, wall);
            pack(vertices, triangle.b(), bits, wall);
            pack(vertices, triangle.c(), bits, wall);
        };
    }

    private static void pack(FloatArray vertices, Vector3 point, float color, BoardTactical.Wall wall) {
        vertices.add(point.x, point.y, point.z, color);
        if (wall != null) {
            float dx = wall.b().x() - wall.a().x(), dy = wall.b().y() - wall.a().y();
            float length = (float) Math.hypot(dx, dy);
            float along = ((point.x / BoardGeometry.hexScale() - wall.a().x()) * dx
                  + (-point.y / BoardGeometry.hexScale() - wall.a().y()) * dy) / length;
            vertices.add(wall.outline().stroke().getDashPhase() + wall.outlineDistance() + along, 0.5f);
        }
    }

    private static float dashPeriod(BasicStroke stroke) {
        float[] dashes = stroke.getDashArray();
        if (dashes == null) {
            return 1;
        }
        float length = 0;
        for (float dash : dashes) {
            length += dash;
        }
        return length * (dashes.length % 2 == 0 ? 1 : 2);
    }

    private static Texture outlineTexture(BasicStroke stroke) {
        float period = dashPeriod(stroke);
        float[] dashes = stroke.getDashArray();
        Pixmap pixels = new Pixmap(Math.max(1, (int) Math.ceil(period * 16)), 1, Pixmap.Format.RGBA8888);
        try {
            pixels.setBlending(Pixmap.Blending.None);
            for (int x = 0; x < pixels.getWidth(); x++) {
                float remaining = (x + 0.5f) * period / pixels.getWidth();
                int dash = 0;
                while (dashes != null && remaining >= dashes[dash % dashes.length]) {
                    remaining -= dashes[dash++ % dashes.length];
                }
                pixels.drawPixel(x, 0, dash % 2 == 0 ? 0xFFFFFFFF : 0xFFFFFF00);
            }
            Texture texture = new Texture(pixels, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    void render(Camera camera, float deltaSeconds) {
        render(camera, deltaSeconds, false);
    }

    /** The Tactical View lays range walls flat on the surface; the 3D view keeps them upright at every angle. */
    void render(Camera camera, float deltaSeconds, boolean tacticalView) {
        render(camera, deltaSeconds, tacticalView, BoardSource.UiPreferences.Overlays.DEFAULTS);
    }

    /** As {@link #render(Camera, float, boolean)}, the Tactical View's E(C)CM as the Overlays settings choose. */
    void render(Camera camera, float deltaSeconds, boolean tacticalView, BoardSource.UiPreferences.Overlays overlays) {
        render(camera, deltaSeconds, tacticalView, overlays, false);
    }

    /**
     * As {@link #render(Camera, float, boolean, BoardSource.UiPreferences.Overlays)}; {@code allFields} outlines every
     * E(C)CM field in the 3D view too (deployment's ECM overlay), which otherwise shows only their ticks.
     */
    void render(Camera camera, float deltaSeconds, boolean tacticalView, BoardSource.UiPreferences.Overlays overlays,
          boolean allFields) {
        scrollDistance += deltaSeconds * outlineSpeed;
        for (Material ink : outlines.values()) {
            TextureAttribute texture = ink.get(TextureAttribute.class, TextureAttribute.Diffuse);
            double offset = -scrollDistance * texture.scaleU;
            texture.offsetU = (float) (offset - Math.floor(offset));
        }
        if (pages.isEmpty() && !hexMasks.active() && !deploymentTint.active() && !electronicWarfare.active()) {
            return;
        }
        batch.begin(camera);
        deploymentTint.submit(batch, camera);
        electronicWarfare.submit(batch, camera, deltaSeconds, tacticalView,
              tacticalView ? overlays.ecmFields() || allFields : allFields, overlays.ecmHatch(), allFields);
        hexMasks.submit(batch, camera);
        renderPages(camera, presentation -> presentation == 0 || presentation == (tacticalView ? 2 : 1));
        batch.end();
        renderPlanes(camera, () -> {
            batch.begin(camera);
            renderPages(camera, presentation -> presentation == 3 || presentation == PLANES.presentation());
            batch.end();
        });
    }

    /** Draws annotations on the shared hex planes through local slopes without changing their position. */
    static void renderPlanes(Camera camera, Runnable draw) {
        float[] combined = camera.combined.val;
        float depth = combined[Matrix4.M23];
        combined[Matrix4.M23] += camera.projection.val[Matrix4.M22] * PLANE_SEE_THROUGH_LEVELS * BoardGeometry.level();
        try {
            draw.run();
        } finally {
            combined[Matrix4.M23] = depth;
        }
    }

    private void renderPages(Camera camera, IntPredicate presentations) {
        for (var entry : pages.entrySet()) {
            if (presentations.test(entry.getKey().presentation())) {
                for (Page page : entry.getValue()) {
                    var bounds = page.renderable.meshPart;
                    if (camera.frustum.boundsInFrustum(bounds.center.x, bounds.center.y, bounds.center.z,
                          bounds.halfExtents.x, bounds.halfExtents.y, bounds.halfExtents.z)) { batch.render(page); }
                }
            }
        }
    }

    void renderLabels(Camera camera) {
        renderRuler(camera);
        if (labels.isEmpty()) {
            return;
        }
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        textBatch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, camera.viewportWidth, camera.viewportHeight));
        textBatch.begin();
        for (var entry : labels.entrySet()) {
            var anchor = entry.getKey();
            int column = Math.round((anchor.x() - BoardGeometry.TILE_WIDTH / 2) / (BoardGeometry.TILE_WIDTH * 0.75f));
            float halfHeight = BoardGeometry.TILE_HEIGHT / 2;
            Coords coords = new Coords(column,
                  Math.round((anchor.y() - halfHeight - (column & 1) * halfHeight) / BoardGeometry.TILE_HEIGHT));
            var tile = previous.tile(coords);
            if (tile == null) {
                continue;
            }
            Vector3 position = new Vector3(anchor.x() * BoardGeometry.hexScale(), -anchor.y() * BoardGeometry.hexScale(),
                  BoardTacticalGeometry.floatingZ(previous, coords));
            if (!camera.frustum.sphereInFrustum(position, BoardGeometry.width())) {
                continue;
            }
            Vector3 right = new Vector3(camera.direction).crs(camera.up).nor().scl(BoardGeometry.hexScale());
            Vector3 screen = camera.project(new Vector3(position), 0, 0, camera.viewportWidth, camera.viewportHeight);
            float scale = camera.project(new Vector3(position).add(right), 0, 0,
                  camera.viewportWidth, camera.viewportHeight).dst(screen);
            TextImage image = images.get(entry.getValue());
            var region = textures.region(entry.getValue());
            textBatch.draw(region, screen.x + image.x() * scale,
                  screen.y - (image.y() + image.pixels().height() / 2f) * scale,
                  image.pixels().width() * scale / 2, image.pixels().height() * scale / 2);
        }
        textBatch.end();
    }

    /** A screen-width stroke of one straight world-space ray, visible through the hex that blocks it. */
    private void renderRuler(Camera camera) {
        BoardTactical.Ruler ruler = previous == null ? null : previous.tactical().ruler();
        if (ruler == null || previous.tile(ruler.start()) == null) { return; }
        Vector3 start = BoardGeometry.center(ruler.start(), ruler.startHeight());
        Vector3 end = ruler.end() == null ? null : BoardGeometry.center(ruler.end(), ruler.endHeight());
        // Avoid projecting a point behind the near plane while the camera is inside the board.
        if (camera.frustum.planes[0].distance(start) < 0
              || end != null && camera.frustum.planes[0].distance(end) < 0) { return; }
        Vector3 blocked = end == null ? null : new Vector3(start).lerp(end, rulerBlockedFrom(ruler));
        Vector3 modified = end == null ? null : new Vector3(start).lerp(end, rulerModifierFrom(ruler));
        camera.project(start, 0, 0, camera.viewportWidth, camera.viewportHeight);
        if (end != null) {
            camera.project(end, 0, 0, camera.viewportWidth, camera.viewportHeight);
            camera.project(blocked, 0, 0, camera.viewportWidth, camera.viewportHeight);
            camera.project(modified, 0, 0, camera.viewportWidth, camera.viewportHeight);
        }
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        rulerShapes.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, camera.viewportWidth, camera.viewportHeight));
        rulerShapes.begin(ShapeRenderer.ShapeType.Filled);
        if (end != null) {
            rulerStroke(start, modified, RULER_CLEAR, false);
            rulerStroke(modified, blocked, RULER_MODIFIED, false);
            rulerStroke(blocked, end, RULER_BLOCKED, true);
            rulerCrosshair(end, ruler.endArgb());
        }
        rulerCrosshair(start, ruler.startArgb());
        rulerShapes.end();
    }

    /** Enter the engine's first blocking hex; the remainder stays blocked even after the ray leaves it. */
    static float rulerBlockedFrom(BoardTactical.Ruler ruler) {
        return rulerEnters(ruler, ruler.blockedAt());
    }

    /** Yellow starts at the first modifier's entry edge; a blocker always takes precedence. */
    static float rulerModifierFrom(BoardTactical.Ruler ruler) {
        return Math.min(rulerBlockedFrom(ruler), rulerEnters(ruler, ruler.modifierAt()));
    }

    private static float rulerEnters(BoardTactical.Ruler ruler, Coords coords) {
        if (coords == null || ruler.end() == null) { return 1; }
        float[] interval = BoardGeometry.crossing(BoardGeometry.center(ruler.start(), ruler.startHeight()),
              BoardGeometry.center(ruler.end(), ruler.endHeight()), coords);
        return interval == null ? 0 : interval[0];
    }

    private void rulerStroke(Vector3 start, Vector3 end, int rgba, boolean dashed) {
        float length = (float) Math.hypot(end.x - start.x, end.y - start.y);
        if (length < .01f) { return; }
        float step = dashed ? 16 : length;
        for (float distance = 0; distance < length; distance += step) {
            float a = distance / length, b = Math.min(length, distance + (dashed ? 10 : length)) / length;
            float ax = start.x + (end.x - start.x) * a, ay = start.y + (end.y - start.y) * a;
            float bx = start.x + (end.x - start.x) * b, by = start.y + (end.y - start.y) * b;
            rulerShapes.setColor(new Color(rgba));
            rulerShapes.rectLine(ax, ay, bx, by, 6);
        }
    }

    private void rulerCrosshair(Vector3 point, int argb) {
        for (int pass = 0; pass < 2; pass++) {
            rulerShapes.setColor(pass == 0 ? Color.BLACK : new Color((argb << 8) | (argb >>> 24)));
            float width = pass == 0 ? 4 : 2;
            for (int i = 0; i < 32; i++) {
                double a = i * Math.PI / 16, b = (i + 1) * Math.PI / 16;
                rulerShapes.rectLine(point.x + (float) Math.cos(a) * 8, point.y + (float) Math.sin(a) * 8,
                      point.x + (float) Math.cos(b) * 8, point.y + (float) Math.sin(b) * 8, width);
            }
            rulerShapes.rectLine(point.x - 12, point.y, point.x + 12, point.y, width);
            rulerShapes.rectLine(point.x, point.y - 12, point.x, point.y + 12, width);
        }
    }

    private static TextImage paintText(List<BoardTactical.Text> texts) {
        var metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        List<Shape> glyphs = new ArrayList<>();
        Rectangle2D bounds = null;
        try {
            for (var text : texts) {
                Shape shape = text.font().createGlyphVector(metrics.getFontRenderContext(), text.value())
                      .getOutline(text.x(), text.y());
                glyphs.add(shape);
                bounds = bounds == null ? shape.getBounds2D() : bounds.createUnion(shape.getBounds2D());
            }
        } finally {
            metrics.dispose();
        }
        int x = (int) Math.floor(bounds.getX()) - 2, y = (int) Math.floor(bounds.getY()) - 2;
        BufferedImage image = new BufferedImage(Math.max(1, (int) Math.ceil(bounds.getMaxX() - x + 2) * 2),
              Math.max(1, (int) Math.ceil(bounds.getMaxY() - y + 2) * 2), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            UIUtil.setHighQualityRendering(graphics);
            graphics.scale(2, 2);
            graphics.translate(-x, -y);
            graphics.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < texts.size(); i++) {
                graphics.setColor(new java.awt.Color(0, 0, 0, texts.get(i).argb() >>> 24));
                graphics.draw(glyphs.get(i));
                graphics.setColor(new java.awt.Color(texts.get(i).argb(), true));
                graphics.fill(glyphs.get(i));
            }
        } finally {
            graphics.dispose();
        }
        return new TextImage(new BoardScene.Pixels(image), x, y);
    }

    long builds() {
        return builds;
    }

    boolean deploymentActive() {
        return deploymentTint.active();
    }

    GpuElectronicWarfare electronicWarfare() {
        return electronicWarfare;
    }

    /** An outline's material: the walls' depth policy with its dash pattern, {@code dashScale} repeats per unit. */
    private Material ink(Texture dashes, float dashScale) {
        Material ink = wallMaterial.copy();
        TextureAttribute diffuse = TextureAttribute.createDiffuse(dashes);
        diffuse.scaleU = dashScale;
        ink.set(diffuse);
        return ink;
    }

    /**
     * Links the hex masks' and the pages' programs behind the loading stage. ECM fields and the deployment tint, which
     * last while they show, link when they first appear (the board's own ECM in the warm-up's frames).
     */
    void prepare() {
        hexMasks.prepare();
        // The pages' unlit programs: fills and walls, and dashed outlines.
        GpuUnitShader.prepareUnlit(batch, material, PLAIN_VERTICES);
        GpuUnitShader.prepareUnlit(batch, ink(null, 1), INKED_VERTICES);
    }

    @Override
    public void dispose() {
        rulerShapes.dispose();
        hexMasks.dispose();
        deploymentTint.dispose();
        electronicWarfare.dispose();
        pages.values().forEach(group -> group.forEach(Page::dispose));
        pages = Map.of();
        outlines.values().forEach(ink ->
              ink.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture.dispose());
        outlines = Map.of();
        fills = Map.of();
        planeConnections = new float[0];
        planeFills = null;
        planeTiles = null;
        walls = Map.of();
        impassable = List.of();
        batch.dispose();
        textBatch.dispose();
        textures.dispose();
        images.clear();
        labels.clear();
    }
}
