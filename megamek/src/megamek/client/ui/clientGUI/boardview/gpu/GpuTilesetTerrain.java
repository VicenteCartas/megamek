/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;

/**
 * The Classic graphics' terrain, as the libGDX board drew it: every hex a plain column at its
 * level, topped by its Classic tileset art with the cliff-top rim along each drop and walled in its material down to
 * its lower neighbours or the board's floor. Only connected road and rail inclines cut the flat top, using the shared
 * route profiles with vertical sides. Generated bridges retain their own shapes; natural bridges keep a normal hex cap
 * over their hollow passage. Liquid retains its hex outline above a recessed bed. Drawing, tactical overlays and picking
 * all consume this same column geometry, independently of the Enhanced terrain's sculpted banks and cliffs.
 */
final class GpuTilesetTerrain {
    /** Isolates graded column artwork from props and animated liquids with otherwise identical materials. */
    static final class Grading extends Attribute {
        static final long TYPE = register("boardColumnGrading");
        Grading() { super(TYPE); }
        @Override public Attribute copy() { return new Grading(); }
        @Override public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    }

    /** The old board's material walls: their art repeats every 96 units at hex scale 1. */
    private static final float WALL_REPEAT = 96;
    /** Liquid moves this far above the surface, in hex-scale units. */
    static final float LIQUID_LIFT = .05f;
    /**
     * As the libGDX board's falls: hanging this far clear of the wall, in hex-scale units, their art repeating every 48
     * units down and 24 along at hex scale 1, the lip and the foot turning in four steps each.
     */
    private static final float FALL_CLEARANCE = .06f, FALL_REPEAT = 48, FALL_SPAN = 24;
    private static final int FALL_SEGMENTS = 4;

    /** One animated material: a liquid's frames, on its surface moving with its current, or running down its falls. */
    private record Liquid(BoardLiquid liquid, BoardLiquid.Textures source, BoardFlow.Current current, boolean falling) { }

    private GpuTilesetTerrain() { }

    /** Apply the shared elevation cue to completed column reflectance, before the existing model lighting. */
    static String fragment(String source) {
        String helpers = "// model-lighting-functions";
        source = GpuShaderSource.replaceOnce(source, helpers, GpuShaderSource.read("terrain-ground-color.glsl")
              + "\nuniform float u_levelHeight;\n" + helpers, "column");
        String reflectance = "albedo = materialReflectance(albedo);";
        return GpuShaderSource.replaceOnce(source, reflectance,
              reflectance + "\n    albedo = levelGrade(albedo, v_cloudPosition.z / u_levelHeight);", "column");
    }

    /**
     * Writes the hex's column {@code surface} into a chunk: its art tops, banks, shallows and walls into
     * {@code opaque}, which casts and receives shadows, and its open liquid into {@code liquid}, molten liquid into
     * {@code opaque} as the 3D relief does, each liquid material with its frames in {@code animations} by its id.
     * {@code art} gives a hex's column art (null without any). Grows {@code bounds} by everything written. CPU only:
     * the shapes are emitted on a terrain worker.
     */
    static void write(BoardScene.Tile tile, BoardTacticalGeometry.Surface surface,
          BoardFlow.Current current, Function<Coords, TextureRegion> art, GpuAssets assets,
          BiConsumer<Material, Consumer<MeshPartBuilder>> opaque, BiConsumer<Material, Consumer<MeshPartBuilder>> liquid,
          Map<String, GpuTerrain.LiquidSurface> animations, BoundingBox bounds) {
        Coords coords = tile.coords();
        // Road ramps use their actual normals; the remaining column top is horizontal.
        Map<Vector3, Vector3> normals = BoardSurface.vertexNormals(surface.top());
        String rock = tile.liquid().molten() ? "terrain/classic/rock" : null;
        // SPACE is transparent, as in Enhanced: the atmosphere's space sky shows through. Its column still picks.
        boolean space = tile.backdrop() == BoardBackdrop.SPACE;
        // Frozen water shows its water's first frame, still, under the ice sheet the terrain lays over it.
        TextureRegion still = tile.frozen() && tile.water()
              ? new TextureRegion(assets.liquid(tile.liquid().textures(tile.waterDepth()), 0)) : null;
        Material frozen = still == null ? null : frozenWater(tile, still.getTexture());
        for (BoardSurface.Face face : surface.faces()) {
            bounds.ext(face.a()).ext(face.b()).ext(face.c());
            if (space) { continue; }
            if (frozen != null) {
                opaque.accept(frozen, mesh -> artTriangle(mesh, coords, face, still, normals));
                continue;
            }
            if (face.finish() == BoardSurface.Finish.BANK || face.finish() == BoardSurface.Finish.BED) {
                opaque.accept(classic(assets, rock != null ? rock : "terrain/classic/water-bed"),
                      mesh -> bank(mesh, tile, face, false));
                continue;
            }
            Coords source = face.landEdge() < 0 ? coords : coords.translated(BoardGeometry.edgeDirection(face.landEdge()));
            TextureRegion region = art.apply(source);
            if (region == null) {
                // No Classic art (an airless presentation's hex): its surface's Classic material, as its walls.
                opaque.accept(classic(assets, tile.surface().classicWall), mesh -> bank(mesh, tile, face, false));
            } else {
                opaque.accept(material(region.getTexture()), mesh -> artTriangle(mesh, coords, face, region, normals));
            }
            if (face.finish() == BoardSurface.Finish.SHORE) {
                Material sand = classic(assets, rock != null ? rock : "terrain/classic/sand");
                sand.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
                      new DepthTestAttribute(GL20.GL_LEQUAL, false));
                opaque.accept(sand, mesh -> bank(mesh, tile, face, true));
            }
        }
        for (BoardSurface.Face face : surface.top()) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        for (BoardSurface.Face face : surface.walls()) {
            bounds.ext(face.a()).ext(face.b()).ext(face.c());
            opaque.accept(classic(assets, side(tile.surface(), face.finish())), mesh -> wall(mesh, face));
        }
        if (tile.liquid().present() && !tile.frozen()) {
            BoardLiquid.Textures source = tile.liquid().textures(tile.waterDepth());
            var layer = tile.liquid().molten() ? opaque : liquid;
            TextureRegion frame = new TextureRegion(assets.liquid(source, 0));
            Material pool = liquid(new Liquid(tile.liquid(), source, current, false), assets, animations);
            layer.accept(pool, mesh -> pool(mesh, tile, surface.water(), frame));
            if (!surface.waterfalls().isEmpty()) {
                Material fall = liquid(new Liquid(tile.liquid(), source, BoardFlow.Current.STILL, true), assets, animations);
                for (BoardSurface.Side side : surface.waterfalls()) { layer.accept(fall, mesh -> fall(mesh, tile, side)); }
            }
        }
    }

    /** A natural cap carries dry terrain, never the water or legacy bridge sprite beneath it. */
    static BoardScene.Pixels naturalBridgeArt(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck) {
        if (deck == null || !deck.natural()) { return null; }
        for (int d = 0; d < 6; d++) {
            var bank = scene.neighbor(tile, d);
            if (BoardBridge.abutment(tile, bank, d) && bank.surface() == deck.surface() && bank.tileset() != null) {
                return bank.tileset();
            }
        }
        return null;
    }

    /** The same installed shell supplies the Classic hex cap, its road receiver and picking triangles. */
    static void naturalBridgeTop(BoardScene.Tile tile, BoardBridge.Shape shape, TextureRegion art,
          BiConsumer<Material, Consumer<MeshPartBuilder>> opaque) {
        Material material = material(art == null ? null : art.getTexture());
        for (var facet : shape.facets()) {
            if (facet.part() != BoardBridge.Part.TOP) { continue; }
            var face = new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP);
            opaque.accept(material, mesh -> artTriangle(mesh, tile.coords(), face, art, Map.of()));
        }
    }

    /** Tactical columns retain the authored pit artwork at its game level, without the sculpted view's recess. */
    private static float surfaceZ(BoardScene.Tile tile) {
        return tile.ultraSublevel() ? tile.elevation() * BoardGeometry.level() : BoardGeometry.surfaceZ(tile);
    }

    private static float solidZ(BoardScene.Tile tile) {
        return tile.liquid().present() && !tile.frozen() ? BoardGeometry.groundZ(tile) : surfaceZ(tile);
    }

    /** Only rendered routes and their paint receivers need a prepared road surface. */
    static boolean infrastructure(BoardScene scene, BoardScene.Tile tile) {
        return tile.wayExits() != 0 || BoardBridge.feature(tile) != null
              || tile.features().stream().anyMatch(BoardRail::route)
              || !BoardRoad.nearby(scene, tile).isEmpty() || BoardSurface.ramps(scene, tile) != 0
              || !BoardBridge.approaches(scene, tile).isEmpty();
    }

    /** The nominal column and its actual route cuts are shared by artwork, tactical overlays and picking. */
    static BoardTacticalGeometry.Surface column(BoardScene scene, BoardScene.Tile tile, float floor,
          BoardSurface source, Map<Coords, BoardSurface> sources) {
        if (tile.liquid().present()) { return plainColumn(scene, tile, floor); }
        if (source == null || !source.columns()) { source = BoardSurface.columns(scene, tile, TerrainLod.FULL); }
        var top = List.copyOf(source.groundFaces());
        return new BoardTacticalGeometry.Surface(top, List.of(), top, List.of(),
              List.copyOf(source.walls(scene, floor, sources)), List.of());
    }

    static BoardTacticalGeometry.Surface column(BoardScene scene, BoardScene.Tile tile, float floor) {
        return column(scene, tile, floor, null, Map.of());
    }

    /** Liquid also retains its complete hex footprint; depth is represented by a plain recessed bed. */
    private static BoardTacticalGeometry.Surface plainColumn(BoardScene scene, BoardScene.Tile tile, float floor) {
        float top = surfaceZ(tile), solidTop = solidZ(tile);
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        center.z = top;
        Vector3[] corners = new Vector3[6];
        for (int edge = 0; edge < 6; edge++) {
            corners[edge] = BoardGeometry.corner(tile.coords(), 0, edge);
            corners[edge].z = top;
        }
        List<BoardSurface.Face> tops = new ArrayList<>(), walls = new ArrayList<>();
        List<BoardSurface.Side> falls = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6, direction = BoardGeometry.edgeDirection(edge);
            var neighbor = scene.neighbor(tile, direction);
            Vector3 a = corners[edge], b = corners[next];
            triangle(tops, center, a, b, BoardSurface.Finish.TOP);
            wall(walls, tile, direction, a, b, solidTop, neighbor == null ? floor : solidZ(neighbor));
            if (open(tile, neighbor) && neighbor != null && !tile.frozen() && neighbor.elevation() < tile.elevation()) {
                float across = surfaceZ(neighbor);
                falls.add(new BoardSurface.Side(new Vector3(a), new Vector3(b), across, across, edge));
            }
        }
        boolean openWater = tile.liquid().present() && !tile.frozen();
        List<BoardSurface.Face> water = openWater ? List.copyOf(tops) : List.of();
        List<BoardSurface.Face> solid = tops;
        if (openWater) {
            solid = new ArrayList<>();
            for (var face : tops) {
                solid.add(new BoardSurface.Face(new Vector3(face.a().x, face.a().y, solidTop),
                      new Vector3(face.b().x, face.b().y, solidTop), new Vector3(face.c().x, face.c().y, solidTop),
                      BoardSurface.Finish.BED));
            }
        }
        return new BoardTacticalGeometry.Surface(List.copyOf(tops), List.of(), List.copyOf(solid),
              water, List.copyOf(walls), List.copyOf(falls));
    }
    /** Whether a liquid runs on across an edge: into the open liquid it joins, or off the board. */
    private static boolean open(BoardScene.Tile tile, BoardScene.Tile neighbor) {
        return neighbor == null || !neighbor.frozen() && tile.joins(neighbor);
    }

    /**
     * One stretch of an edge, from the column's height there down to what lies across it: an earth wall up to an
     * incline, a rock outcrop beyond, split as the rim splits them.
     */
    private static void wall(List<BoardSurface.Face> walls, BoardScene.Tile tile, int direction, Vector3 from, Vector3 to,
          float top, float bottom) {
        Vector3 a = new Vector3(from.x, from.y, top), b = new Vector3(to.x, to.y, top);
        wall(walls, tile, direction, a, b, bottom);
    }

    private static void wall(List<BoardSurface.Face> walls, BoardScene.Tile tile, int direction, Vector3 a, Vector3 b,
          float bottom) {
        float top = Math.max(a.z, b.z);
        if (bottom >= top) { return; }
        if (a.z < bottom) { a = new Vector3(a).lerp(b, (bottom - a.z) / (b.z - a.z)); }
        else if (b.z < bottom) { b = new Vector3(b).lerp(a, (bottom - b.z) / (a.z - b.z)); }
        boolean rock = BoardRim.high(tile, direction, Math.round((top - bottom) / BoardGeometry.level()));
        quad(walls, a, new Vector3(a.x, a.y, bottom), new Vector3(b.x, b.y, bottom), b,
              rock ? BoardSurface.Finish.OUTCROP : BoardSurface.Finish.WALL);
    }

    /** Grassland shows its dirt on an incline's side and its rock on a cliff's; other ground keeps its own material. */
    private static String side(BoardScene.Surface surface, BoardSurface.Finish finish) {
        return surface == BoardScene.Surface.GRASS && finish != BoardSurface.Finish.OUTCROP
              ? BoardScene.Surface.DIRT.classicWall : surface.classicWall;
    }

    private static void quad(List<BoardSurface.Face> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          BoardSurface.Finish finish) {
        triangle(faces, a, b, c, finish);
        triangle(faces, a, c, d, finish);
    }

    private static void triangle(List<BoardSurface.Face> faces, Vector3 a, Vector3 b, Vector3 c, BoardSurface.Finish finish) {
        if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-6f) {
            faces.add(new BoardSurface.Face(a, b, c, finish));
        }
    }

    /**
     * As the libGDX board's: the liquid's animated frames, water translucent, magma opaque and glowing. Equal liquids
     * share one material, by its id, and its frames are registered in {@code animations} under that id. Like
     * Enhanced's, no liquid is graded by its level.
     */
    private static Material liquid(Liquid key, GpuAssets assets, Map<String, GpuTerrain.LiquidSurface> animations) {
        Texture frame = assets.liquid(key.source(), 0);
        Material material = material(frame);
        material.remove(Grading.TYPE);
        material.id = "column-liquid:" + key;
        material.set(new GpuLiquidShader.Frame(frame));
        if (!key.liquid().molten()) {
            material.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  key.falling() ? .8f : GpuWaterShader.SURFACE_OPACITY));
            material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
        }
        GpuTerrain.liquidColour(material, key.liquid(), frame);
        animations.putIfAbsent(material.id, new GpuTerrain.LiquidSurface(material, key.source(), key.falling(), key.current()));
        return material;
    }

    /** Draw precisely the water footprint used by picking; only its small depth clearance is visual. */
    private static void pool(MeshPartBuilder mesh, BoardScene.Tile tile, List<BoardSurface.Face> faces,
          TextureRegion frame) {
        float lift = LIQUID_LIFT * BoardGeometry.hexScale();
        for (var face : faces) {
            mesh.triangle(topVertex(new Vector3(face.a()).add(0, 0, lift), tile.coords(), frame).setCol(Color.WHITE),
                  topVertex(new Vector3(face.b()).add(0, 0, lift), tile.coords(), frame).setCol(Color.WHITE),
                  topVertex(new Vector3(face.c()).add(0, 0, lift), tile.coords(), frame).setCol(Color.WHITE));
        }
    }

    /** The old branch's sandy fade over neighboring land art, continuing into the submerged bank and lakebed. */
    private static void bank(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface.Face face, boolean fade) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        float scale = BoardGeometry.hexScale(), repeat = WALL_REPEAT * scale;
        MeshPartBuilder.VertexInfo[] vertices = new MeshPartBuilder.VertexInfo[3];
        Vector3[] points = { face.a(), face.b(), face.c() };
        for (int i = 0; i < points.length; i++) {
            Vector3 point = points[i];
            float wet = fade ? Math.clamp((tile.elevation() * BoardGeometry.level() - point.z) / scale, 0, 1) : 1;
            float shade = fade ? 1 - wet * .12f : 1;
            vertices[i] = new MeshPartBuilder.VertexInfo().setPos(new Vector3(point).add(0, 0, fade ? .035f * scale : 0))
                  .setNor(normal).setUV(point.x / repeat, point.y / repeat).setCol(shade, shade, shade, wet);
        }
        mesh.triangle(vertices[0], vertices[1], vertices[2]);
    }

    /**
     * As the libGDX board's fall, from the column's edge: the liquid curves over the lip, hangs just clear of the wall
     * and spreads into the liquid it lands in. Its art scrolls down with the clock.
     */
    private static void fall(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface.Side side) {
        float scale = BoardGeometry.hexScale(), lift = LIQUID_LIFT * scale, clearance = FALL_CLEARANCE * scale;
        float top = side.a().z + lift, low = side.lowA() + lift;
        float foot = Math.min(.5f * BoardSurface.fallLip(top, low), top - clearance - low);
        Vector3 outward = new Vector3(side.a()).add(side.b()).scl(.5f).sub(BoardGeometry.center(tile.coords(), 0));
        outward.z = 0;
        outward.nor();
        // Each step of the profile, from the edge: over the lip a quarter turn down, then a quarter turn out at the foot.
        List<Vector3> steps = new ArrayList<>(), normals = new ArrayList<>();
        for (int i = 0; i <= FALL_SEGMENTS; i++) {
            Vector3 normal = arc(outward, MathUtils.HALF_PI * i / FALL_SEGMENTS);
            steps.add(new Vector3(normal).scl(clearance).add(0, 0, top - clearance));
            normals.add(normal);
        }
        for (int i = FALL_SEGMENTS; i >= 0; i--) {
            Vector3 normal = arc(outward, MathUtils.HALF_PI * i / FALL_SEGMENTS);
            steps.add(new Vector3(outward).scl(clearance + foot).mulAdd(normal, -foot).add(0, 0, low + foot));
            normals.add(normal);
        }
        float along = side.a().dst(side.b()) / (FALL_SPAN * scale), repeat = FALL_REPEAT * scale;
        for (int i = 0; i + 1 < steps.size(); i++) {
            mesh.rect(fallVertex(side.a(), steps.get(i), normals.get(i), 0, repeat),
                  fallVertex(side.a(), steps.get(i + 1), normals.get(i + 1), 0, repeat),
                  fallVertex(side.b(), steps.get(i + 1), normals.get(i + 1), along, repeat),
                  fallVertex(side.b(), steps.get(i), normals.get(i), along, repeat));
        }
    }

    /** A fall's normal a quarter turn from up (zero) to outward. */
    private static Vector3 arc(Vector3 outward, float angle) {
        return new Vector3(outward).scl(MathUtils.sin(angle)).add(0, 0, MathUtils.cos(angle));
    }

    /** A step of the profile beside an end of the edge; the art's height follows the world's, so it scrolls evenly. */
    private static MeshPartBuilder.VertexInfo fallVertex(Vector3 end, Vector3 step, Vector3 normal, float u, float repeat) {
        Vector3 point = new Vector3(end.x + step.x, end.y + step.y, step.z);
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setUV(u, point.z / repeat).setCol(Color.WHITE);
    }

    /** Classic artwork retains the nominal hex coordinates, including its actual route ramps. */
    private static void artTriangle(MeshPartBuilder mesh, Coords coords, BoardSurface.Face face, TextureRegion region,
          Map<Vector3, Vector3> normals) {
        mesh.triangle(topVertex(face.a(), coords, region).setNor(normals.getOrDefault(face.a(), Vector3.Z)),
              topVertex(face.b(), coords, region).setNor(normals.getOrDefault(face.b(), Vector3.Z)),
              topVertex(face.c(), coords, region).setNor(normals.getOrDefault(face.c(), Vector3.Z)));
    }

    /** A classic board material with its finish (its .tres, GpuAssets.finish). */
    private static Material classic(GpuAssets assets, String name) {
        return assets.finish(name, material(assets.material(name)));
    }

    /** Frozen water's still frame: opaque, ungraded like every liquid, tinted or toned as its open water is. */
    private static Material frozenWater(BoardScene.Tile tile, Texture frame) {
        Material material = material(frame);
        material.remove(Grading.TYPE);
        material.id = "column-frozen:" + tile.liquid() + ":" + tile.waterDepth();
        GpuTerrain.liquidColour(material, tile.liquid(), frame);
        return material;
    }

    /** One fixed id: equal materials share one draw in their chunk layer. */
    private static Material material(Texture texture) {
        Material material = new Material("column", IntAttribute.createCullFace(GL20.GL_NONE), new Grading());
        material.set(texture == null ? ColorAttribute.createDiffuse(Color.GRAY) : TextureAttribute.createDiffuse(texture));
        return material;
    }

    /** Flat artwork defaults to an upward normal; actual route ramps replace it with their surface normal. */
    private static MeshPartBuilder.VertexInfo topVertex(Vector3 point, Coords coords, TextureRegion region) {
        return region == null ? new MeshPartBuilder.VertexInfo().setPos(point).setNor(Vector3.Z).setCol(Color.WHITE)
              : GpuTerrain.topVertex(point, coords, region, Color.WHITE);
    }

    /**
     * Faces outward by its winding: the corners run counter-clockwise, so a wall faces its lower neighbour and a bank
     * whichever of the road and the top beside it lies lower.
     */
    private static void wall(MeshPartBuilder mesh, BoardSurface.Face face) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
        normal.z = 0;
        normal.nor();
        float repeat = WALL_REPEAT * BoardGeometry.hexScale();
        mesh.triangle(wallVertex(face.a(), normal, repeat), wallVertex(face.b(), normal, repeat),
              wallVertex(face.c(), normal, repeat));
    }

    /** World-aligned along the wall and up it, so neighbouring walls continue their material's art. */
    private static MeshPartBuilder.VertexInfo wallVertex(Vector3 point, Vector3 normal, float repeat) {
        float along = point.x * -normal.y + point.y * normal.x;
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setUV(along / repeat, -point.z / repeat);
    }
}
