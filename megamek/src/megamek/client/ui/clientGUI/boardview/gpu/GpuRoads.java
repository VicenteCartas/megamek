/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;

/** Road material masks on terrain or bridge decks; the supporting surfaces own their physical shape. */
final class GpuRoads {
    static final VertexAttributes VERTICES = attributes(false);
    static final VertexAttributes TESSELLATED_VERTICES = attributes(true);

    private static VertexAttributes attributes(boolean tessellated) {
        var attributes = new ArrayList<>(List.of(VertexAttribute.Position(), VertexAttribute.Normal(),
              VertexAttribute.ColorPacked(), VertexAttribute.TexCoords(0),
              new VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_roadMaskUV"),
              new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_roadMaskRegion", 1)));
        if (tessellated) {
            attributes.add(new VertexAttribute(VertexAttributes.Usage.Generic, 4, GL20.GL_UNSIGNED_BYTE, true, "a_tessColor", 2));
            attributes.add(new VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_tessUV", 3));
            attributes.add(new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_tessLift", 4));
            // Whether the support stays where its mesh puts it (BoardSurface#fixedTessellation), as the ground's own.
            attributes.add(new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_tessFixed", 5));
        }
        return new VertexAttributes(attributes.toArray(VertexAttribute[]::new));
    }
    record MaskData(BoardScene.Pixels pixels, float x, float y, float width, float height) { }

    /** Renderer-owned exact sharing across chunks/workers; only live materials keep masks in memory. */
    static final class Masks {
        private final Map<MaskData, WeakReference<MaskData>> masks = new WeakHashMap<>();
        /**
         * Recently built masks by everything they depend on ({@link #mask}): a road's mask is wanted by every hex it
         * drapes over, and an edit rebuilds a whole chunk, most of whose roads it did not touch. Least recently used go
         * first, past {@link #RECENT_TEXELS} or {@link #RECENT_MASKS} (each key holds its scene's tiles).
         */
        private final LinkedHashMap<Object, MaskData> recent = new LinkedHashMap<>(64, .75f, true);
        private final Map<Object, CompletableFuture<MaskData>> pending = new HashMap<>();
        private long recentTexels;
        private static final long RECENT_TEXELS = 16L << 20;
        private static final int RECENT_MASKS = 1024;
        /** Counts {@link #forget}s: a mask built across one is never kept. */
        private int epoch;

        /**
         * The mask {@code key} names, built once even when neighbouring terrain workers request it together. Waiters
         * join outside the lock; the fork-join pool can keep working. The key holds all inputs, so a completed mask is
         * reusable even if its first requester became stale. Each requester still checks its own job before using it.
         */
        MaskData mask(Object key, Supplier<MaskData> build, Runnable check) {
            check.run();
            int started;
            CompletableFuture<MaskData> future;
            boolean owner;
            synchronized (this) {
                MaskData hit = recent.get(key);
                if (hit != null) { return hit; }
                started = epoch;
                future = pending.get(key);
                owner = future == null;
                if (owner) { future = new CompletableFuture<>(); pending.put(key, future); }
            }
            if (owner) {
                try {
                    MaskData built = share(build.get());
                    synchronized (this) {
                        if (epoch == started && recent.putIfAbsent(key, built) == null) { recentTexels += texels(built); }
                        for (var masks = recent.values().iterator(); (recentTexels > RECENT_TEXELS || recent.size() > RECENT_MASKS)
                              && masks.hasNext(); ) {
                            recentTexels -= texels(masks.next());
                            masks.remove();
                        }
                    }
                    future.complete(built);
                } catch (RuntimeException | Error failure) {
                    future.completeExceptionally(failure);
                    throw failure;
                } finally {
                    synchronized (this) { pending.remove(key, future); }
                }
            }
            MaskData built = future.join();
            check.run();
            return built;
        }

        /** Forgets the recent masks: something outside their keys that they depend on changed. */
        synchronized void forget() {
            recent.clear();
            pending.clear();
            recentTexels = 0;
            epoch++;
        }

        private static long texels(MaskData mask) { return (long) mask.pixels().width() * mask.pixels().height(); }

        synchronized MaskData share(MaskData mask) {
            WeakReference<MaskData> reference = masks.get(mask);
            MaskData shared = reference == null ? null : reference.get();
            if (shared != null) { return shared; }
            masks.put(mask, new WeakReference<>(mask));
            return mask;
        }
    }

    /** Borrowed chunk-atlas region, retained per tile range so an edit can relocate unchanged road vertices. */
    static final class Mask extends TextureAttribute {
        static final long TYPE = register("roadMask");
        static { TextureAttribute.Mask |= TYPE; }
        final MaskData data;

        Mask(TextureRegion region, MaskData data) {
            super(TYPE, region);
            this.data = data;
        }

        Mask withRegion(TextureRegion region) { return new Mask(region, data); }

        @Override
        public Mask copy() {
            var copy = new Mask(new TextureRegion(textureDescription.texture), data);
            copy.textureDescription.set(textureDescription);
            copy.offsetU = offsetU; copy.offsetV = offsetV; copy.scaleU = scaleU; copy.scaleV = scaleV;
            return copy;
        }
    }
    private static final class Surface extends FloatAttribute {
        static final long TYPE = register("roadSurface");
        final float lift;
        Surface(float transition, float lift) { super(TYPE, transition); this.lift = lift; }
        @Override public Surface copy() { return new Surface(value, lift); }
        @Override public int hashCode() { return 31 * super.hashCode() + Float.floatToIntBits(lift); }
        @Override public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            int transition = Float.compare(value, ((Surface) other).value);
            return transition != 0 ? transition : Float.compare(lift, ((Surface) other).lift);
        }
    }
    /** A road material's own draw: its surface map, and its place in {@link #MATERIALS}, which finds its finish. */
    static final class Maps extends TextureAttribute {
        static final long TYPE = register("roadMaterialMaps");
        static { TextureAttribute.Mask |= TYPE; }
        final int material;
        Maps(Texture texture, int material) {
            super(TYPE, texture);
            this.material = material;
        }
        @Override
        public Maps copy() { return new Maps(textureDescription.texture, material); }
        @Override public int hashCode() { return 31 * super.hashCode() + material; }
        @Override
        public int compareTo(Attribute other) {
            int order = super.compareTo(other);
            return order != 0 || type != other.type ? order : Integer.compare(material, ((Maps) other).material);
        }
    }
    static final class Soil extends FloatAttribute {
        static final long TYPE = register("roadSoil");
        Soil() { super(TYPE, 1); }
    }
    /** Road materials, in the order of their maps in {@link GpuAssets#roadArray}. */
    static final List<String> MATERIALS = List.of("asphalt", "dirt", "gravel", "colony-slabs");
    /** A paved road laid in a colony's precast slabs (#material), whose maps follow its course (BoardRoad#slab). */
    static final String SLABS = "roads/colony-slabs";
    /**
     * How far a colony slab road stands on the ground, in tile pixels: its deck is that much above it, on sides of its
     * own (#sides). Units on it stand on the ground, so it stays a slab's thickness.
     */
    static final float SLAB_RISE = 1.8f;
    /**
     * The road and sculpt map arrays that merged coats sample. Each coat's own maps, finish and wet response travel in
     * its vertices ({@link #coat}), so all coats of a chunk share one material and one draw.
     */
    static final class Coats extends Attribute {
        static final long TYPE = register("roadCoats");
        final GpuAssets.MaterialArrays roads, sculpt;

        Coats(GpuAssets.MaterialArrays roads, GpuAssets.MaterialArrays sculpt) {
            super(TYPE);
            this.roads = roads;
            this.sculpt = sculpt;
        }

        @Override
        public Coats copy() { return new Coats(roads, sculpt); }

        @Override
        public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            int result = Integer.compare(System.identityHashCode(roads), System.identityHashCode(((Coats) other).roads));
            return result != 0 ? result : Integer.compare(System.identityHashCode(sculpt), System.identityHashCode(((Coats) other).sculpt));
        }

        @Override
        public int hashCode() { return java.util.Objects.hash(super.hashCode(), roads, sculpt); }
    }
    static final long TYPE = Surface.TYPE;
    /** Pavement and bridge decks share this small clearance above their supporting elevation. */
    static final float SURFACE_LIFT = .04f;
    static final float WHEEL_TINT = .92f;
    /** The paved carriageway's edge fades out over this width inside its half width (car parks meet its middle). */
    static final float FEATHER = .45f;
    private static final float LOOSE_FEATHER = 3;

    /**
     * {@code deck}: on a built deck, whose loose track (#track) stays closed over the deck's own top; {@code carried}: a
     * loose road's grit on the paved road it meets; {@code spill}: a gravel road's spill onto it.
     */
    record Fade(float outer, float inner, float end, boolean wheels, BoardRoad.Join join, float setback, boolean landing,
          boolean deck, boolean carried, boolean spill) {
        Fade(float outer, float inner, float end) { this(outer, inner, end, false, null, 0, false, false, false, false); }
        Fade(float outer, float inner, float end, boolean wheels, BoardRoad.Join join) {
            this(outer, inner, end, wheels, join, 0, false, false, false, false);
        }

        /** A grounded apron's fade (#bare). */
        static Fade landing(float outer, float inner, float end, boolean wheels, BoardRoad.Join join, float setback) {
            return new Fade(outer, inner, end, wheels, join, setback, true, false, false, false);
        }

        /** A loose road's one coat, worn out over {@code end} at a road's end. */
        static Fade track(float end, boolean deck) { return new Fade(0, 0, end, true, null, 0, false, deck, false, false); }

        /** A gravel track spread on over the dirt road it joins, thinning out as the join's cover does. */
        static Fade spread(BoardRoad.Join join) { return new Fade(0, 0, 0, true, join, 0, false, false, false, false); }

        /** A loose road's grit carried on along the wheel paths of the paved road it meets. */
        static Fade carrying() { return new Fade(0, 0, 0, false, null, 0, false, false, true, false); }

        /** A gravel road's spill onto the paved road it meets. */
        static Fade spilled() { return new Fade(0, 0, 0, false, null, 0, false, false, false, true); }
    }
    /** {@code repeat} is a multiple of its material's own repeat (GpuAssets.roadCoverage, GpuAssets.sculptCoverage). */
    record Patch(Area shape, String texture, Color tint, float repeat, float lift, Fade fade) {
        boolean blended() { return fade != null; }
        float endFade() { return fade == null ? 0 : fade.end(); }
    }

    private GpuRoads() { }

    static String vertex(String source) {
        return source.replace("void main() {",
              GpuShaderSource.read("road-mask.vert") + "\nvoid main() {\nroadMaskCoordinates();\n");
    }

    static FloatAttribute attribute(Patch patch) { return new Surface(finish(patch), patch.lift()); }

    private static float finish(Patch patch) {
        boolean transition = patch.fade() != null && patch.fade().join() != null && !track(patch);
        // Positive: material joins (1, 2) or paint (3). Negative: loose (-1), a paved carriageway's wear (-2), landing
        // asphalt (-3) or aggregate/soil (-4), a loose road's track (-5; -6 on a built deck; its maps tell dirt from
        // gravel), a loose road's grit carried onto a paved one (-7), colony slabs (-8).
        float finish = slab(patch) ? -8
              : track(patch) ? patch.fade().deck() ? -6 : -5
              : carried(patch) || spill(patch) ? -7
              : patch.fade() != null && patch.fade().wheels() ? -2
              : patch.fade() != null && patch.fade().landing() ? asphalt(patch) ? -3 : -4
              : patch.texture().equals("roads/dirt") || patch.texture().equals("roads/gravel") ? -1
              : paint(patch) ? 3 : 0;
        return transition ? patch.texture().equals("roads/dirt") ? 2 : 1 : finish;
    }

    private static boolean asphalt(Patch patch) { return patch.texture().equals("roads/asphalt"); }

    /** A colony slab carriageway's coat (#patches): its mask carries where along and across the road it lies (#color). */
    private static boolean slab(Patch patch) { return patch.texture().equals(SLABS); }

    /**
     * A loose road's one coat (#patches): the shader builds its wheel tracks, crown and edges from the heading and the
     * place across the road its mask carries (#color).
     */
    private static boolean track(Patch patch) {
        return patch.fade() != null && patch.fade().wheels() && !asphalt(patch) && !slab(patch);
    }

    /** A loose road's grit on the paved road it meets (#patches). */
    private static boolean carried(Patch patch) { return patch.fade() != null && patch.fade().carried(); }

    /** A gravel road's grit and dust spilled onto the paved road it meets (#patches). */
    private static boolean spill(Patch patch) { return patch.fade() != null && patch.fade().spill(); }

    /** Carried grit and spills lie over the asphalt and its paint. */
    private static final float CARRIED_LIFT = .07f;

    /** Road markings: concrete-ground tinted with paint, at the paint's lift. */
    private static boolean paint(Patch patch) {
        return patch.texture().equals("concrete-ground") && patch.lift() == PAINT_LIFT;
    }

    /** Markings lie over the asphalt and its wear, under nothing. */
    private static final float PAINT_LIFT = .065f;

    /**
     * A merged coat's vertex colour: its maps (a {@link #MATERIALS} index, or their count plus a sculpt material's index), its
     * finish offset by 8, and its wet response in 255ths. Unmerged coats keep white.
     */
    static float coat(int maps, Patch patch, float response) {
        return Color.toFloatBits(maps, Math.round(finish(patch)) + 8, Math.round(response * 255), 255);
    }

    /** Region placement is vertex data; only the atlas page and actual surface state split draw calls. */
    static Material batchMaterial(Material material) {
        Mask mask = material.get(Mask.class, Mask.TYPE);
        if (mask == null) { return material; }
        Material shared = new Material(material);
        shared.set(new TextureAttribute(Mask.TYPE, mask.textureDescription));
        // A merged coat's finish is in its vertices, and its lift orders it within the chunk's one draw.
        if (shared.has(Coats.TYPE)) { shared.set(new Surface(0, 0)); }
        return shared;
    }

    /** A merged draw's continuation in another mesh starts at the given coat's lift, which orders it after the first. */
    static Material lifted(Material batch, Material coat) {
        Material continued = new Material(batch);
        continued.set(new Surface(0, layer(coat)));
        return continued;
    }

    static float layer(Material material) { return material.get(Surface.class, TYPE).lift; }

    /** Atlas replacements must update every reused vertex, even when different masks now share a draw. */
    static void relocate(float[] vertices, Mask mask, VertexAttributes attributes) {
        int stride = attributes.vertexSize / Float.BYTES;
        int region = VERTICES.get(VERTICES.size() - 1).offset / Float.BYTES;
        for (int i = region; i < vertices.length; i += stride) {
            vertices[i] = mask.offsetU; vertices[i + 1] = mask.offsetV;
            vertices[i + 2] = mask.scaleU; vertices[i + 3] = mask.scaleV;
        }
    }

    static String texture(BoardRoad.Kind kind) {
        return "roads/" + switch (kind) {
            case PAVED, ALLEY -> "asphalt";
            case GRAVEL -> "gravel";
            default -> "dirt";
        };
    }

    static List<Patch> patches(BoardScene.Tile tile, BoardRoad road) {
        return patches(tile.coords(), tile.surface(), tile.road(), tile.roadExits(), road, false, slabs(tile, false),
              HexAppearance.trafficControl(tile.appearance(), "road", tile.roadExits()), road.crossing(tile, false));
    }

    /**
     * The authored material of this road layer's own paint; verges and neighbouring transition coats keep theirs. A
     * colony's precast slabs are one such finish, on any map; legacy lunar and Martian paved roads are given it when
     * converted (BoardSceneryLayouts#colonyRoads).
     */
    static String material(BoardScene.Tile tile, boolean deck, String texture) {
        if (!texture.startsWith("roads/")) { return null; }
        String base = texture.substring("roads/".length());
        BoardRoad.Kind kind = deck ? tile.deckRoad() : tile.road();
        if (!texture.equals(texture(kind))) { return base; }
        var style = tile.appearance().get(deck ? "bridgeRoad" : "road");
        if (style == null) { return base; }
        var variant = BoardEditorBlueprint.get().variant(style.variant());
        String authored = style.material() != null ? style.material() : variant == null ? "" : variant.material();
        return authored.isBlank() ? base : authored;
    }

    /** The hex in tile pixels, grown by {@code margin}. */
    private static Area hex(float margin) {
        Path2D.Float outline = new Path2D.Float();
        float grow = 1 + margin / (BoardGeometry.TILE_HEIGHT / 2);
        for (int corner = 0; corner < 6; corner++) {
            float x = HexTile.cornerOffsetX(corner, BoardGeometry.TILE_WIDTH) * grow;
            float y = HexTile.cornerOffsetY(corner, BoardGeometry.TILE_HEIGHT) * grow;
            if (corner == 0) { outline.moveTo(x, y); } else { outline.lineTo(x, y); }
        }
        outline.closePath();
        return new Area(outline);
    }

    /** Whether this road layer's carriageway is laid in colony slabs (#material). */
    static boolean slabs(BoardScene.Tile tile, boolean deck) {
        return SLABS.equals("roads/" + material(tile, deck, texture(deck ? tile.deckRoad() : tile.road())));
    }

    /** The deck road's paint: its kind on its own sides ({@link BoardBridge.Deck#road}). */
    static List<Patch> deckPatches(BoardScene.Tile tile, BoardBridge.Deck deck, BoardRoad road) {
        // A rock arch carries ordinary ground paint, including loose shoulders and worn ends. Only a built deck
        // clips them to its carriageway; the arch's own top facets already keep the paint off its sides.
        return patches(tile.coords(), tile.surface(), deck.kind(), deck.roadExits(), road, !deck.natural(),
              slabs(tile, true), HexAppearance.trafficControl(tile.appearance(), "bridgeRoad", deck.roadExits()),
              road.crossing(tile, true));
    }

    /** Tile pixels a paved deck's asphalt stays solid past its footing on a bare bank, before fading over the apron. */
    static float landingSetback(BoardBridge.Deck deck) { return setback(paved(deck.kind())); }

    /** Tile pixels asphalt stays solid before a bare end, before fading over its apron; none for loose roads. */
    private static float setback(boolean paved) { return paved ? BoardRelief.metres(2.5f) / BoardGeometry.hexScale() : 0; }

    private static boolean paved(BoardRoad.Kind kind) { return kind == BoardRoad.Kind.PAVED || kind == BoardRoad.Kind.ALLEY; }

    /**
     * Tile pixels a dirt track's ruts run on past the border where it meets a paved road: over the asphalt's broken end
     * and the gravel under it (#painted, #bare), as long as a bridge's landing apron.
     */
    static float runOn() { return BoardBridgeFooting.apronLength(); }

    /** Tile pixels a gravel road's spill reaches onto the paved road it meets (#patches). */
    static float spill() { return BoardRelief.metres(1.2f) / BoardGeometry.hexScale(); }

    /** Tile pixels past that border a loose road's wheels carry faint grit on along the paved road's wheel paths. */
    static float carry() { return BoardRelief.metres(14) / BoardGeometry.hexScale(); }

    /** A bare bank gets a grounded material apron after the solid deck and its tapered rails have ended. */
    static List<Patch> deckPatches(BoardScene.Tile tile, BoardBridge.Deck deck, BoardRoad road, BoardBridgeFooting footing) {
        var patches = deckPatches(tile, deck, road);
        return footing.bareExits() == 0 ? patches : bare(patches, road, paved(deck.kind()));
    }

    /**
     * The road painting a hex's ground: one ending on a cliff's top (BoardRoadGrade#overDrop) runs on just past the rim,
     * so its paint wears out to gravel at the very edge, as if crumbling with the cliff's fall (#bare). A paved road ends
     * the same way on the border where it meets a dirt track (BoardRoad#meet), whose ruts run on over that end
     * (#runOn); against a gravel road it ends square, under the gravel's slight overlap (#overlap).
     */
    static BoardRoad painted(BoardScene scene, BoardScene.Tile tile, BoardRoad road) {
        int ends = 0;
        List<Float> past = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f));
        for (int d = 0; d < 6; d++) {
            if ((tile.roadExits() & 1 << d) != 0 && BoardRoadGrade.overDrop(tile, scene.neighbor(tile, d), d)) {
                ends |= 1 << d;
                past.set(d, BoardRelief.metres(CRUMBLE) / BoardGeometry.hexScale());
            }
        }
        if (paved(tile.road())) {
            for (var join : road.joins()) {
                if (road.meets(join) && join.kind() == BoardRoad.Kind.DIRT) { ends |= 1 << join.direction(); }
            }
        }
        return ends == 0 ? road : road.extended(past, ends);
    }

    /** How far, in metres, a road ending on a cliff's top runs on past its rim: its asphalt there is mostly gravel. */
    private static final float CRUMBLE = 3.5f;

    /**
     * The paint of a road, worn out towards the ends {@link #painted} gives it on cliff tops. A road built in concrete
     * (BoardRoadGrade#poured) stands on its cast base, whose top shows beside the paint.
     */
    static List<Patch> patches(BoardScene scene, BoardScene.Tile tile, BoardRoad painted, BoardRoad road) {
        var patches = new ArrayList<>(patches(tile, painted));
        var grade = BoardRoadGrade.of(scene, tile, true);
        if (grade != null && grade.poured()) {
            float scale = BoardGeometry.hexScale();
            Area base = grade.region().createTransformedArea(new AffineTransform(1 / scale, 0, 0, 1 / scale,
                  -BoardGeometry.centerX(tile.coords()) / scale, -BoardGeometry.centerY(tile.coords()) / scale));
            patches.add(new Patch(base, "concrete-ground", Color.WHITE, 1, CAST_LIFT, null));
        }
        return painted == road ? patches : bare(patches, painted, paved(tile.road()));
    }

    /** A cast base's lift: over the loose verge, under the carriageway's own paint. */
    private static final float CAST_LIFT = .03f;

    /** A road carried on through a ground portal to the end of its tunnel. */
    static BoardRoad through(BoardRoad road, BoardTunnel tunnel) {
        List<Float> lengths = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f));
        lengths.set(tunnel.direction(), BoardTunnel.DEPTH - BoardTunnel.SETBACK);
        return road.extended(lengths, 1 << tunnel.direction());
    }

    /**
     * The paint a road {@link #through} a ground portal carries on past its hex's edge: its verges and markings, on the
     * tunnel's floor, which carries the road's own surface.
     */
    static List<Patch> tunnelPatches(BoardScene.Tile tile, BoardRoad road, BoardTunnel tunnel) {
        float scale = BoardGeometry.hexScale(), cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        Path2D.Float outline = new Path2D.Float();
        for (Vector3 corner : tunnelFloor(tunnel)) {
            float x = (corner.x - cx) / scale, y = (corner.y - cy) / scale;
            if (outline.getCurrentPoint() == null) { outline.moveTo(x, y); } else { outline.lineTo(x, y); }
        }
        outline.closePath();
        Area floor = new Area(outline);
        String surface = slabs(tile, false) ? SLABS : texture(tile.road());
        List<Patch> result = new ArrayList<>();
        for (var patch : patches(tile, road)) {
            if (patch.texture().equals(surface)) { continue; }
            Area shape = new Area(patch.shape());
            shape.intersect(floor);
            if (!shape.isEmpty()) {
                result.add(new Patch(shape, patch.texture(), patch.tint(), patch.repeat(), patch.lift(), patch.fade()));
            }
        }
        return result;
    }

    /** The floor a tunnel's paint lies on, flat at the portal's base: the opening's width, from the hex's edge in. */
    static List<BoardTacticalGeometry.Triangle> tunnelFloor(BoardTunnel tunnel, Patch patch) {
        var corners = tunnelFloor(tunnel);
        float lift = patch.lift() * BoardGeometry.hexScale();
        for (var corner : corners) { corner.z += lift; }
        return List.of(new BoardTacticalGeometry.Triangle(corners.get(0), corners.get(1), corners.get(2), -1),
              new BoardTacticalGeometry.Triangle(corners.get(0), corners.get(2), corners.get(3), -1));
    }

    private static List<Vector3> tunnelFloor(BoardTunnel tunnel) {
        float scale = BoardGeometry.hexScale(), half = BoardTunnel.HALF_WIDTH * tunnel.widthScale() * scale;
        Vector3 along = tunnel.along(), across = new Vector3(along.y, -along.x, 0);
        List<Vector3> corners = new ArrayList<>();
        for (float[] c : new float[][] { { -1, BoardTunnel.SETBACK }, { 1, BoardTunnel.SETBACK },
              { 1, BoardTunnel.DEPTH }, { -1, BoardTunnel.DEPTH } }) {
            corners.add(new Vector3(tunnel.origin()).mulAdd(across, c[0] * half).mulAdd(along, c[1] * scale));
        }
        return corners;
    }

    /** A tunnel's paint mask, darkening into it as its floor does (BoardTunnel#light). */
    static MaskData tunnelMask(BoardScene.Tile tile, BoardRoad road, Patch patch, BoardTunnel tunnel) {
        float scale = BoardGeometry.hexScale(), cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        return mask(patch, (x, y, scratch) -> {
            Color tint = color(road, patch, x, y, scratch);
            // The shader reads a mask's tint as sRGB.
            float light = (float) Math.pow(BoardTunnel.light(tunnel.depth(cx + x * scale, cy + y * scale)), 1 / 2.2);
            return tint.set(tint.r * light, tint.g * light, tint.b * light, tint.a);
        });
    }

    /** Paint ending on bare ground: a grounded material apron, the road's own paint fading out over it. */
    private static List<Patch> bare(List<Patch> patches, BoardRoad road, boolean paved) {
        float apron = BoardBridgeFooting.apronLength();
        float setback = setback(paved);
        Area landing = road.endZone(apron);
        var result = new ArrayList<Patch>();
        if (paved) {
            float shoulder = BoardRoad.SHOULDER * 3;
            Area earth = road.footprint(shoulder + 1);
            earth.intersect(landing);
            result.add(new Patch(earth, "roads/dirt", Color.WHITE, 1, .02f, Fade.landing(shoulder + 1, -1, apron, false, null, 0)));
            Area gravel = road.footprint(shoulder);
            gravel.intersect(landing);
            result.add(new Patch(gravel, "roads/gravel", Color.WHITE, 1, .025f, Fade.landing(shoulder, -1, apron, false, null, 0)));
        }
        for (var patch : patches) {
            Area head = new Area(patch.shape());
            if (patch.texture().equals("concrete-ground")) {
                head.subtract(road.endZone(apron
                      + BoardBridgeFooting.terminalLength() + BoardRelief.metres(.3f) / BoardGeometry.hexScale()));
                result.add(new Patch(head, patch.texture(), patch.tint(), patch.repeat(), patch.lift(), patch.fade()));
                continue;
            }
            Area tail = new Area(patch.shape());
            tail.intersect(landing);
            head.subtract(tail);
            result.add(new Patch(head, patch.texture(), patch.tint(), patch.repeat(), patch.lift(), patch.fade()));
            if (tail.isEmpty()) { continue; }
            var fade = patch.fade();
            // The apron lies on the bank's ground: a dirt track opens there as on any ground.
            result.add(new Patch(tail, patch.texture(), patch.tint(), patch.repeat(), patch.lift(),
                  Fade.landing(0, paved ? -FEATHER : -LOOSE_FEATHER, apron - setback,
                        fade != null && fade.wheels() && !asphalt(patch), fade == null ? null : fade.join(), setback)));
        }
        return result;
    }

    /**
     * {@code deck}: clipped to a built deck's carriageway; {@code slabs}: a paved road laid in colony slabs (#material),
     * whose own maps carry its kerbs, joints and lamps, without verge or paint.
     */
    private static List<Patch> patches(Coords coords, BoardScene.Surface family, BoardRoad.Kind kind, int exits,
          BoardRoad road, boolean deck, boolean slabs, HexAppearance.TrafficControl control,
          BoardRoad.Crossing crossing) {
        boolean sand = family == BoardScene.Surface.SAND;
        boolean snow = family == BoardScene.Surface.SNOW;
        boolean paved = kind == BoardRoad.Kind.PAVED || kind == BoardRoad.Kind.ALLEY;
        // The route's compacted soil continues across biome borders; only its loose verge takes the local cover.
        String texture = slabs ? SLABS : texture(kind);
        Color tint = Color.WHITE;
        float repeat = 1;
        float endFade = deck || paved || Integer.bitCount(exits) > 1 ? 0 : kind == BoardRoad.Kind.DIRT ? 17 : 7;
        List<Patch> patches = new ArrayList<>();
        float feather = deck ? 0 : paved ? FEATHER : LOOSE_FEATHER;
        float outer = deck || paved ? 0 : BoardRoad.SHOULDER;
        // A loose road is one track in one coat: the shader builds its wheel tracks, crown, edges and worn end from what
        // its mask carries (#color).
        boolean track = !paved;
        // A slab road is one coat out to its edge, feathered there by its coverage: its maps carry the kerbs.
        Area core = road.footprint(track ? outer : slabs ? 0 : -feather);
        // A slab coat stays in its hex, a pixel over its borders: past them it would lay its own slabs over a bending
        // neighbour's.
        if (slabs) { core.intersect(hex(1)); }
        // A neighbouring loose road's own coat draws the track over its hex.
        if (track) { core.subtract(road.trackedHexes()); }
        // A gravel road's whole width, which its stone keeps where it spreads on over a dirt track (below).
        Area loose = new Area(core);
        // A dirt track shows nothing beyond its ruts' loose soil, but on a built deck, whose top its coat keeps closed.
        boolean dirt = kind == BoardRoad.Kind.DIRT && !deck;
        if (dirt) { core.intersect(road.rutReach()); }
        // Its ruts run on past the border where it meets a paved road, over the asphalt's broken end (#runOn).
        // Wide enough for ruts that cross the border obliquely: off them the run-on shows nothing (#coverage).
        if (dirt) { core.add(road.pastMeetings(runOn(), BoardRoad.RUT_OFFSET + BoardRoad.RUT_REACH + .6f * runOn())); }
        Area tail = new Area(core);
        tail.intersect(road.endZone(track ? 0 : endFade));
        core.subtract(tail);
        if (paved && !deck && !slabs) {
            Area verge = road.footprint(BoardRoad.SHOULDER);
            verge.subtract(road.footprint(-FEATHER));
            // Snow and sand verges fade into the same ground beside the road, so they keep its scale.
            patches.add(new Patch(verge, snow ? "snow-ground" : sand ? "sand-verge" : "roads/gravel", Color.WHITE,
                  1, .025f, new Fade(BoardRoad.SHOULDER, -.2f, endFade)));
        }
        // A paved core carries its traffic: the mask gives the shader the heading and lane its wear follows.
        // A slab road stands on the ground with sides of its own (#sides), its edge sharp; on a deck it lies flat.
        patches.add(new Patch(core, texture, tint, repeat, slabs && !deck ? SLAB_RISE : SURFACE_LIFT,
              track ? Fade.track(endFade, deck) : paved ? new Fade(0, 0, 0, true, null) : null));
        if (!tail.isEmpty()) {
            patches.add(new Patch(tail, texture, tint, repeat, SURFACE_LIFT, new Fade(0, 0, endFade)));
        }
        Area edge = new Area();
        if (!track && !slabs) {
            edge = road.footprint(outer);
            edge.subtract(road.footprint(-feather));
            patches.add(new Patch(edge, texture, tint, repeat, .045f, new Fade(outer, -feather, endFade)));
        }
        for (var join : road.joins()) {
            String next = slabs && paved(join.kind()) ? SLABS : texture(join.kind());
            if (texture.equals(next)) { continue; }
            // Where a loose road meets a paved one, the paved road ends square under the loose one's run-on (below).
            if (paved != paved(join.kind()) && road.meets(join)) { continue; }
            if (road.tracked(join)) {
                // Two loose roads on one ground each draw their own track up to their border; the gravel spreads on
                // over the dirt beyond it, thinning out, as graded stone is carried on.
                if (kind == BoardRoad.Kind.DIRT) {
                    Area spread = new Area(loose);
                    spread.intersect(join.area());
                    patches.add(new Patch(spread, next, tint, repeat, .075f, Fade.spread(join)));
                }
                continue;
            }
            boolean looseJoin = !paved && (join.kind() == BoardRoad.Kind.DIRT || join.kind() == BoardRoad.Kind.GRAVEL);
            float joinOuter = !deck && looseJoin ? BoardRoad.SHOULDER : 0;
            float joinFeather = deck ? 0 : looseJoin ? LOOSE_FEATHER : FEATHER;
            Area change = road.footprint(joinOuter);
            change.intersect(join.area());
            core.subtract(change);
            tail.subtract(change);
            edge.subtract(change);
            // Both tiles use the same layer order and world UVs: dirt over asphalt, gravel over either.
            // Replacing the base in this small region avoids two opposing blends with a seam at the hex edge.
            String base = texture.compareTo(next) < 0 ? texture : next;
            String cover = base.equals(texture) ? next : texture;
            Area baseCore = new Area(change);
            baseCore.intersect(road.footprint(-joinFeather));
            Area baseEdge = new Area(change);
            baseEdge.subtract(baseCore);
            if (!base.equals(texture(BoardRoad.Kind.DIRT))) {
                patches.add(new Patch(baseCore, base, tint, repeat, SURFACE_LIFT, null));
                patches.add(new Patch(baseEdge, base, tint, repeat, .045f, new Fade(joinOuter, -joinFeather, 0)));
            } else {
                // Dirt under gravel, joined across a bridge's deck, which keeps their paint apart: each side lays its
                // own, a dirt track.
                patches.add(new Patch(change, base, tint, repeat, SURFACE_LIFT, Fade.track(0, deck)));
            }
            patches.add(new Patch(baseCore, cover, tint, repeat, .075f, new Fade(0, 0, 0, false, join)));
            patches.add(new Patch(baseEdge, cover, tint, repeat, .075f, new Fade(joinOuter, -joinFeather, 0, false, join)));
        }
        if (kind == BoardRoad.Kind.PAVED && !slabs) {
            var approaches = road.stopApproaches(exits, crossing);
            if (control != HexAppearance.TrafficControl.STOPS) {
                Area box = keepClear(road, exits, approaches);
                box.subtract(crossing.clearance());
                if (!box.isEmpty()) {
                    patches.add(new Patch(box, "concrete-ground", new Color(1, .78f, .08f, 1), 1.5f, PAINT_LIFT, null));
                }
            }
            var divider = markings(road, coords, exits);
            divider.shape().add(road.approachLines(exits, crossing.approaches()));
            divider.shape().subtract(crossing.clearance());
            patches.add(divider);
            Area stops = road.stopLines(approaches);
            stops.add(road.flowMarkings());
            stops.subtract(crossing.clearance());
            if (!stops.isEmpty()) {
                patches.add(new Patch(stops, "concrete-ground", new Color(.96f, .96f, .92f, 1), 1.5f, PAINT_LIFT, null));
            }
            Area median = road.medianLines();
            median.subtract(crossing.clearance());
            if (!median.isEmpty()) {
                patches.add(new Patch(median, "concrete-ground", new Color(.96f, .96f, .92f, 1), 1.5f, PAINT_LIFT, null));
            }
        }
        // Past a dirt track's run-on the paved road is its own, but for the faint grit its wheels carry on (#coverage).
        Area meeting = road.pavedCarriageway(0);
        if (!meeting.isEmpty()) {
            // Along the paved arm only, not on over a junction it leads into.
            Area carried = road.pastMeetings(carry(), BoardRoad.Kind.PAVED.halfWidth + BoardRoad.SHOULDER);
            carried.intersect(meeting);
            // A gravel road's short, broken spill of grit and dust, most where wheels carry it (#coverage, #color).
            Area spill = road.footprint(outer);
            spill.intersect(meeting);
            spill.subtract(road.pavedCarriageway(-spill()));
            if (dirt) { meeting.subtract(road.pastMeetings(runOn(), 128)); }
            for (var patch : patches) { patch.shape().subtract(meeting); }
            if (track && !dirt) { patches.add(new Patch(spill, texture, tint, repeat, CARRIED_LIFT, Fade.spilled())); }
            patches.add(new Patch(carried, texture, tint, repeat, CARRIED_LIFT, Fade.carrying()));
        }
        Area concrete = road.concreteClearance();
        if (!concrete.isEmpty()) {
            for (var patch : patches) {
                if (patch.texture().equals("concrete-ground")) { patch.shape().subtract(concrete); }
            }
        }
        // Rails bound a deck: loose margins and wear must never float outside its carriageway.
        if (deck) {
            Area carriageway = road.footprint(0);
            for (var patch : patches) { patch.shape().intersect(carriageway); }
        }
        return patches;
    }

    /** The chosen box-junction convention: a hollow yellow lattice inside the four controlled stop planes. */
    static Area keepClear(BoardRoad road, int exits) {
        return keepClear(road, exits, road.stopApproaches(exits));
    }

    private static Area keepClear(BoardRoad road, int exits, List<BoardRoad.StopApproach> approaches) {
        if (Integer.bitCount(exits & 63) != 4) { return new Area(); }
        if (approaches.size() != 4) { return new Area(); }
        Area box = road.footprint(-1);
        for (var approach : approaches) {
            float x = approach.x() - approach.nx() * 3, y = approach.y() - approach.ny() * 3;
            Path2D.Float beyond = new Path2D.Float();
            beyond.moveTo(x - 128 * approach.ny(), y + 128 * approach.nx());
            beyond.lineTo(x + 128 * approach.ny(), y - 128 * approach.nx());
            beyond.lineTo(x + 128 * (approach.ny() + approach.nx()), y + 128 * (approach.ny() - approach.nx()));
            beyond.lineTo(x + 128 * (-approach.ny() + approach.nx()), y + 128 * (approach.nx() + approach.ny()));
            beyond.closePath();
            box.subtract(new Area(beyond));
        }
        if (box.isEmpty()) { return box; }
        Area paint = new Area(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(box));
        var first = approaches.getFirst();
        double angle = Math.atan2(first.ny(), first.nx()) + Math.PI / 4;
        var bounds = AffineTransform.getRotateInstance(-angle).createTransformedShape(box).getBounds2D();
        float margin = .65f / 2 + .001f;
        Path2D.Float grid = new Path2D.Float();
        // Keep the same lattice phase and endpoints, but omit strokes that cannot reach the box.
        for (int offset = -128; offset <= 128; offset += 8) {
            if (offset >= bounds.getMinX() - margin && offset <= bounds.getMaxX() + margin) {
                grid.moveTo(offset, -128); grid.lineTo(offset, 128);
            }
            if (offset >= bounds.getMinY() - margin && offset <= bounds.getMaxY() + margin) {
                grid.moveTo(-128, offset); grid.lineTo(128, offset);
            }
        }
        grid.transform(AffineTransform.getRotateInstance(angle));
        paint.add(new Area(new BasicStroke(.65f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER).createStrokedShape(grid)));
        paint.intersect(box);
        Area reserve = road.medianBand();
        // Leave a small paint-free margin so antialiasing cannot bleed yellow onto the ballast edge.
        reserve.add(new Area(new BasicStroke(.5f).createStrokedShape(reserve)));
        paint.subtract(reserve);
        return paint;
    }

    static Patch markings(BoardRoad road, Coords coords, int exits) {
        return new Patch(road.markings(coords, exits), "concrete-ground", new Color(.96f, .96f, .92f, 1), 1.5f, PAINT_LIFT,
              null);
    }

    /** Flat carriers clip deck materials at the hex edges without draping them onto the riverbed. */
    static List<BoardTacticalGeometry.Triangle> deck(BoardScene.Tile tile, BoardScene.Feature bridge, Patch patch) {
        return deck(tile, bridge, patch, null);
    }

    static List<BoardTacticalGeometry.Triangle> deck(BoardScene.Tile tile, BoardScene.Feature bridge, Patch patch,
          BoardBridge.Shape footing) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float elevation = tile.elevation() + bridge.elevation();
        // Keep the base coat above the authored deck while preserving the ordinary road layer order.
        float lift = (patch.lift() + .01f) * BoardGeometry.hexScale();
        Vector3 center = BoardGeometry.center(tile.coords(), elevation).add(0, 0, lift);
        for (int i = 0; i < 6; i++) {
            result.add(new BoardTacticalGeometry.Triangle(center,
                  BoardGeometry.corner(tile.coords(), elevation, i).add(0, 0, lift),
                  BoardGeometry.corner(tile.coords(), elevation, i + 1).add(0, 0, lift), -1));
        }
        if (footing != null) {
            result.addAll(deck(patch, footing, SURFACE_LIFT));
        }
        return result;
    }

    /**
     * Every coat on the same triangles as the structural surface, the TOP facets of {@code shape}, whose top already
     * stands {@code lift} (model px) above the deck's plane: a built deck's slab and sloped decks at the road lift, a rock
     * arch's top at none.
     */
    static List<BoardTacticalGeometry.Triangle> deck(Patch patch, BoardBridge.Shape shape, float lift) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float offset = (patch.lift() + .01f - lift) * BoardGeometry.hexScale();
        for (var face : shape.facets()) {
            if (face.part() != BoardBridge.Part.TOP) { continue; }
            result.add(new BoardTacticalGeometry.Triangle(new Vector3(face.a()).add(0, 0, offset),
                  new Vector3(face.b()).add(0, 0, offset), new Vector3(face.c()).add(0, 0, offset), -1));
        }
        return result;
    }

    static List<BoardTacticalGeometry.Triangle> deck(BoardScene.Tile tile, BoardScene.Feature bridge, Patch patch,
          BoardBridgeFooting footing, Map<Coords, BoardSurface> surfaces, BoardBridge.Shape slope) {
        var result = slope == null ? deck(tile, bridge, patch, footing.shape()) : deck(patch, slope, SURFACE_LIFT);
        for (int d = 0; d < 6; d++) {
            if ((footing.bareExits() & (1 << d)) == 0) { continue; }
            // Only the existing dry bank carries the apron. Never drape it onto the surface under the span.
            result.addAll(drape(tile, surfaces.get(tile.coords().translated(d)), patch));
        }
        return result;
    }

    /** Whole terrain triangles carry the splat. Road borders, paint and tyre wear do not split them. */
    static List<BoardTacticalGeometry.Triangle> drape(BoardScene.Tile tile, BoardSurface surface, Patch patch) {
        List<BoardTacticalGeometry.Triangle> result = new ArrayList<>();
        float scale = BoardGeometry.hexScale();
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        var bounds = patch.shape().getBounds2D();
        // A neighbour's ground road at another level paints only the road surface it reserves here; a bridge's deck
        // carries its apron onto the bank on purpose.
        boolean apart = tile.elevation() != surface.tile.elevation() && BoardBridge.feature(tile) == null;
        // A road through water lies on its bed (BoardRoad#bed); a bridge's apron keeps to the dry bank.
        boolean bed = surface.tile.liquid().present() && BoardBridge.feature(tile) == null;
        for (var face : surface.coatingFaces()) {
            boolean ground = bed || face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.SHORE;
            if (!ground || apart && !surface.carries(face, tile)) { continue; }
            float area = (face.b().x - face.a().x) * (face.c().y - face.a().y)
                  - (face.b().y - face.a().y) * (face.c().x - face.a().x);
            // Nearly vertical slivers can remain where terrain strips meet. They have no paintable footprint.
            if (area <= .0001f * scale * scale) { continue; }
            float minX = (Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - cx) / scale;
            float maxX = (Math.max(face.a().x, Math.max(face.b().x, face.c().x)) - cx) / scale;
            float minY = (Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - cy) / scale;
            float maxY = (Math.max(face.a().y, Math.max(face.b().y, face.c().y)) - cy) / scale;
            if (!bounds.intersects(minX, minY, maxX - minX, maxY - minY)) { continue; }
            result.add(lifted(face, patch.lift() * scale));
        }
        return result;
    }

    private static BoardTacticalGeometry.Triangle lifted(BoardSurface.Face face, float lift) {
        return new BoardTacticalGeometry.Triangle(new Vector3(face.a()).add(0, 0, lift),
              new Vector3(face.b()).add(0, 0, lift), new Vector3(face.c()).add(0, 0, lift), -1);
    }

    /** Shape/coverage data only: repeating albedo, normals and roughness keep their original full resolution. */
    static MaskData mask(BoardRoad road, Patch patch) {
        return mask(road, patch, 4);
    }

    static MaskData mask(BoardRoad road, Patch patch, int density) {
        return mask(patch, (x, y, scratch) -> color(road, patch, x, y, scratch), density);
    }

    /** Only the broad paved wear field can become coarse. Thin paint and markings retain their original coverage. */
    static int maskDensity(Patch patch, TerrainLod lod) {
        return (lod == TerrainLod.COARSE || lod == TerrainLod.DISTANT)
              && asphalt(patch) && patch.fade() != null && patch.fade().wheels() ? 1 : 4;
    }

    /**
     * The mask texels this carrier can display. A neighbour's shared mask can change outside these triangles without
     * changing this hex. Keep a texel around the edges for linear filtering; discard wholly invisible patches.
     */
    static MaskData visibleMask(Coords at, MaskData mask, List<BoardTacticalGeometry.Triangle> triangles) {
        var pixels = mask.pixels();
        float density = pixels.width() / mask.width();
        var path = new Path2D.Float();
        for (var triangle : triangles) {
            var points = List.of(triangle.a(), triangle.b(), triangle.c());
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i);
                float x = maskU(at, mask, p) * pixels.width(), y = maskV(at, mask, p) * pixels.height();
                if (i == 0) { path.moveTo(x, y); } else { path.lineTo(x, y); }
            }
            path.closePath();
        }
        var image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fill(path);
            graphics.setStroke(new BasicStroke(2));
            graphics.draw(path);
        } finally { graphics.dispose(); }
        int[] visible = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        // Decode a compacted mask once, not by a search per texel.
        var decoded = java.nio.IntBuffer.allocate(visible.length);
        pixels.writeRgba(decoded, 0, visible.length);
        int minX = pixels.width(), minY = pixels.height(), maxX = -1, maxY = -1;
        for (int y = 0; y < pixels.height(); y++) {
            for (int x = 0; x < pixels.width(); x++) {
                int i = y * pixels.width() + x, rgba = decoded.get(i);
                if (visible[i] == 0 || (rgba & 255) == 0) { visible[i] = 0; continue; }
                visible[i] = Integer.rotateRight(rgba, 8);
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX) { return null; }
        return new MaskData(new BoardScene.Pixels(image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1)).compact(),
              Math.round(mask.x() * density + minX) / density, Math.round(mask.y() * density + minY) / density,
              (maxX - minX + 1) / density, (maxY - minY + 1) / density);
    }

    @FunctionalInterface
    interface MaskColor {
        Color at(float x, float y, Color scratch);
    }

    /** Ground treatments share the same soft mask carrier and original surface material maps as road coats. */
    static MaskData mask(Patch patch, MaskColor color) {
        return mask(patch, color, 4);
    }

    private static MaskData mask(Patch patch, MaskColor color, int density) {
        // The supporting terrain already clips the paint. Its fitted edge can extend beyond the original hex box.
        var bounds = patch.shape().getBounds2D();
        int x = (int) Math.floor(bounds.getMinX()) - 1;
        int y = (int) Math.floor(bounds.getMinY()) - 1;
        int w = Math.max(1, (int) Math.ceil(bounds.getMaxX()) - x + 1);
        int h = Math.max(1, (int) Math.ceil(bounds.getMaxY()) - y + 1);
        var image = new BufferedImage(w * density, h * density, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(density, density);
        graphics.translate(-x, -y);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fill(patch.shape());
        graphics.dispose();
        // This newly allocated ARGB image owns a contiguous raster. Paint it in place; Pixels takes the final copy.
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        // A loose track's masks are data, which linear filtering blends with the texels around the covered ones: those
        // carry it too. The atlas has no mipmaps, so no texel farther out is ever read.
        int[] covered = track(patch) || spill(patch) || carried(patch) ? pixels.clone() : null;
        int width = image.getWidth(), height = image.getHeight();
        // Large shared masks can otherwise leave one worker rasterizing while its neighbours wait for the result.
        // Split independent rows within the existing terrain pool, carrying its settings and a private colour scratch.
        TerrainSettings settings = TerrainSettings.current();
        IntStream rows = IntStream.range(0, (height + 31) / 32);
        if (Thread.currentThread() instanceof TerrainSettings.Worker && (long) width * height >= 65536) {
            rows = rows.parallel();
        }
        rows.forEach(group -> {
            try (TerrainSettings.Scope ignored = TerrainSettings.use(settings)) {
                Color scratch = new Color();
                for (int py = group * 32; py < Math.min(height, (group + 1) * 32); py++) {
                    for (int px = 0; px < width; px++) {
                        int i = py * width + px;
                        float alpha = (pixels[i] >>> 24) / 255f;
                        boolean read = alpha > 0;
                        for (int ny = Math.max(0, py - 1); covered != null && !read && ny <= Math.min(height - 1, py + 1); ny++) {
                            for (int nx = Math.max(0, px - 1); !read && nx <= Math.min(width - 1, px + 1); nx++) {
                                read = covered[ny * width + nx] >>> 24 != 0;
                            }
                        }
                        Color tint = read ? color.at(x + (px + .5f) / density, y + (py + .5f) / density, scratch) : patch.tint();
                        pixels[i] = Math.round(alpha * tint.a * 255) << 24 | Math.round(tint.r * 255) << 16
                              | Math.round(tint.g * 255) << 8 | Math.round(tint.b * 255);
                    }
                }
            }
        });
        return new MaskData(new BoardScene.Pixels(image).compact(), x, y, w, h);
    }

    /**
     * {@code coverage} widens the patch's world-space texture repeat with its texture (GpuAssets.coverage); a
     * {@code tessellated} coat writes its support's control data (TESSELLATED_VERTICES), on the ground's own final
     * triangles where water clipping or material subdivision split its face ({@code supports}, GpuTerrain.coatSupports).
     */
    static void write(Supplier<MeshPartBuilder> meshes, List<BoardTacticalGeometry.Triangle> triangles,
          Patch patch, Mask mask, BoardSurface surface, Coords source, boolean flat, float coat, float coverage, boolean tessellated,
          Function<BoardSurface.Face, List<MeshPartBuilder.VertexInfo>> supports) {
        Map<Vector3, Vector3> normals = new HashMap<>();
        Map<BoardTacticalGeometry.Triangle, BoardSurface.Face> ground = new HashMap<>();
        float lift = patch.lift() * BoardGeometry.hexScale();
        for (var face : flat ? List.<BoardSurface.Face>of() : surface.coatingFaces()) {
            if (tessellated) {
                // A rim and its top can share a position with different normals. Preserve the source face and its
                // original corner identities, rather than choosing one normal for every occurrence of that position.
                ground.put(lifted(face, lift), face);
                continue;
            }
            if (face.finish() == BoardSurface.Finish.BED || face.finish() == BoardSurface.Finish.SHORE) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (var p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(normalKey(p, 0), unused -> new Vector3()).add(normal);
            }
        }
        float repeat = BoardRelief.detailMetres(patch.repeat()) * coverage;
        if (slab(patch) && patch.lift() >= SLAB_RISE) {
            sides(meshes, patch.shape(), source, triangles, mask, surface, coat, tessellated, lift, repeat);
        }
        for (var t : triangles) {
            Vector3 fallback = new Vector3(t.b()).sub(t.a()).crs(new Vector3(t.c()).sub(t.a())).nor();
            Vector3 a = flat ? fallback : new Vector3(normals.getOrDefault(normalKey(t.a(), lift), fallback)).nor();
            Vector3 b = flat ? fallback : new Vector3(normals.getOrDefault(normalKey(t.b(), lift), fallback)).nor();
            Vector3 c = flat ? fallback : new Vector3(normals.getOrDefault(normalKey(t.c(), lift), fallback)).nor();
            var support = ground.get(t);
            var pieces = support == null || supports == null ? null : supports.apply(support);
            if (pieces != null) {
                // The ground's midpoints may curve where this face's own edges would not; follow them exactly.
                for (int i = 0; i < pieces.size(); i += 3) {
                    var pa = pieces.get(i);
                    var pb = pieces.get(i + 1);
                    var pc = pieces.get(i + 2);
                    MeshPartBuilder mesh = meshes.get();
                    mesh.triangle(carrier(mesh, pa.position, fallback, repeat, mask, surface, coat, tessellated, lift, pa),
                          carrier(mesh, pb.position, fallback, repeat, mask, surface, coat, tessellated, lift, pb),
                          carrier(mesh, pc.position, fallback, repeat, mask, surface, coat, tessellated, lift, pc));
                }
                continue;
            }
            var ga = sculpted(support, 0, surface);
            var gb = sculpted(support, 1, surface);
            var gc = sculpted(support, 2, surface);
            MeshPartBuilder mesh = meshes.get();
            mesh.triangle(carrier(mesh, t.a(), a, repeat, mask, surface, coat, tessellated, lift, ga),
                  carrier(mesh, t.b(), b, repeat, mask, surface, coat, tessellated, lift, gb),
                  carrier(mesh, t.c(), c, repeat, mask, surface, coat, tessellated, lift, gc));
        }
    }

    /**
     * A raised slab road's sides: along its {@code shape}'s outline (tile pixels about the source hex centre), in short runs that
     * follow the coat's lifted terrain ({@code triangles}), from the deck down to below the ground, facing out, textured
     * as the coat's edge (its kerb's steel), its mask read just inside the edge. Where the coat runs on into a
     * neighbour's, a pixel past the hex, the side stands under that neighbour's deck. Clip original edges to this
     * receiving hex; shifting the whole outline here would duplicate distant end faces, while adding cut caps would
     * put a wall across the road at every hex boundary.
     */
    private static void sides(Supplier<MeshPartBuilder> meshes, Area shape, Coords source, List<BoardTacticalGeometry.Triangle> triangles,
          Mask mask, BoardSurface surface, float coat, boolean tessellated, float lift, float repeat) {
        if (triangles.isEmpty()) { return; }
        float scale = BoardGeometry.hexScale(), cx = BoardGeometry.centerX(source);
        float cy = BoardGeometry.centerY(source), depth = 1.6f * lift, inside = .4f;
        float[] point = new float[6], start = null, last = null;
        for (var outline = shape.getPathIterator(null, .25); !outline.isDone(); outline.next()) {
            int type = outline.currentSegment(point);
            float[] next = type == java.awt.geom.PathIterator.SEG_CLOSE ? start : new float[] { point[0], point[1] };
            if (type == java.awt.geom.PathIterator.SEG_MOVETO) {
                start = last = next;
                continue;
            }
            if (last == null || next == null) { continue; }
            float dx = next[0] - last[0], dy = next[1] - last[1], length = (float) Math.hypot(dx, dy);
            if (length > 1e-4f) {
                var interval = BoardGeometry.crossing(new Vector3(cx + last[0] * scale, cy + last[1] * scale, 0),
                      new Vector3(cx + next[0] * scale, cy + next[1] * scale, 0), surface.tile.coords());
                if (interval == null || interval[1] - interval[0] < 1e-6f) { last = next; continue; }
                float nx = dy / length, ny = -dx / length;
                // Out of the coat.
                if (shape.contains(last[0] + dx / 2 + nx * inside, last[1] + dy / 2 + ny * inside)) { nx = -nx; ny = -ny; }
                Vector3 normal = new Vector3(nx, ny, 0);
                int runs = Math.max(1, (int) Math.ceil(length * (interval[1] - interval[0]) / 3));
                for (int run = 0; run < runs; run++) {
                    float t0 = interval[0] + (interval[1] - interval[0]) * run / runs;
                    float t1 = interval[0] + (interval[1] - interval[0]) * (run + 1) / runs;
                    float ax = last[0] + dx * t0, ay = last[1] + dy * t0, bx = last[0] + dx * t1, by = last[1] + dy * t1;
                    Vector3 a = new Vector3(cx + ax * scale, cy + ay * scale, 0), b = new Vector3(cx + bx * scale, cy + by * scale, 0);
                    a.z = deck(triangles, a.x, a.y);
                    b.z = deck(triangles, b.x, b.y);
                    Vector3 inA = new Vector3(a.x - nx * inside * scale, a.y - ny * inside * scale, a.z);
                    Vector3 inB = new Vector3(b.x - nx * inside * scale, b.y - ny * inside * scale, b.z);
                    MeshPartBuilder mesh = meshes.get();
                    short topA = side(mesh, a, inA, normal, repeat, mask, surface, coat, tessellated);
                    short topB = side(mesh, b, inB, normal, repeat, mask, surface, coat, tessellated);
                    short footA = side(mesh, new Vector3(a.x, a.y, a.z - depth), inA, normal, repeat, mask, surface, coat, tessellated);
                    short footB = side(mesh, new Vector3(b.x, b.y, b.z - depth), inB, normal, repeat, mask, surface, coat, tessellated);
                    // Counter-clockwise seen from outside, whichever way the outline runs.
                    if (dx * ny - dy * nx < 0) {
                        mesh.triangle(footA, footB, topB);
                        mesh.triangle(footA, topB, topA);
                    } else {
                        mesh.triangle(footB, footA, topA);
                        mesh.triangle(footB, topA, topB);
                    }
                }
            }
            last = next;
        }
    }

    /** The lifted coat's height at (x, y): on the triangle under it, else on the plane of the nearest one. */
    private static float deck(List<BoardTacticalGeometry.Triangle> triangles, float x, float y) {
        BoardTacticalGeometry.Triangle nearest = null;
        float best = Float.POSITIVE_INFINITY;
        for (var t : triangles) {
            Vector3 a = t.a(), b = t.b(), c = t.c();
            float d = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
            if (Math.abs(d) < 1e-9f) { continue; }
            float u = ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / d;
            float v = ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / d, w = 1 - u - v;
            if (u >= -1e-4f && v >= -1e-4f && w >= -1e-4f) { return u * a.z + v * b.z + w * c.z; }
            float mx = (a.x + b.x + c.x) / 3 - x, my = (a.y + b.y + c.y) / 3 - y, away = mx * mx + my * my;
            if (away < best) { best = away; nearest = t; }
        }
        if (nearest == null) { return 0; }
        Vector3 a = nearest.a(), b = nearest.b(), c = nearest.c();
        Vector3 n = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        return Math.abs(n.z) < 1e-9f ? a.z : a.z - (n.x * (x - a.x) + n.y * (y - a.y)) / n.z;
    }

    /** A side's vertex at {@code p}, its mask read at {@code sample} (#carrier, without a support). */
    private static short side(MeshPartBuilder mesh, Vector3 p, Vector3 sample, Vector3 normal, float repeat, Mask mask,
          BoardSurface surface, float coat, boolean tessellated) {
        var tile = surface.tile;
        float[] vertex = { p.x, p.y, p.z, normal.x, normal.y, normal.z, coat, p.x / repeat, -p.y / repeat,
              maskU(tile.coords(), mask.data, sample), maskV(tile.coords(), mask.data, sample),
              mask.offsetU, mask.offsetV, mask.scaleU, mask.scaleV };
        if (tessellated) {
            // Flat (no support): it keeps its own positions.
            vertex = Arrays.copyOf(vertex, vertex.length + 5);
            vertex[vertex.length - 1] = 1;
        }
        return mesh.vertex(vertex);
    }

    /**
     * The ground vertex at a support face's corner, as its own mesh writes it (a water hex's ground carries its water's
     * palette); null without a support.
     */
    private static MeshPartBuilder.VertexInfo sculpted(BoardSurface.Face support, int corner, BoardSurface surface) {
        if (support == null) { return null; }
        Vector3 p = corner == 0 ? support.a() : corner == 1 ? support.b() : support.c();
        float shore = surface.tile.liquid().present() ? GpuWaterShader.palette(surface.tile.liquid()) : Float.NaN;
        return GpuTerrain.sculptVertex(p, surface.relief.shade(p), shore, surface);
    }

    private static Vector3 normalKey(Vector3 p, float lift) {
        return new Vector3(p.x, p.y, Math.round((p.z - lift) / (.001f * BoardGeometry.hexScale())));
    }

    /** {@code ground}: the ground vertex a tessellated coat stands on, null where it has none (it stays flat). */
    private static short carrier(MeshPartBuilder mesh, Vector3 p, Vector3 normal, float repeat, Mask mask,
          BoardSurface surface, float coat, boolean tessellated, float lift, MeshPartBuilder.VertexInfo ground) {
        // Tessellate the same control triangle as the ground, then lift the coating in the evaluation stage. Lifting
        // its control points first changes the screen-space split and can expose the ground between the two meshes.
        var tile = surface.tile;
        if (ground != null) { normal = ground.normal; }
        float[] vertex = { p.x, p.y, ground == null ? p.z : ground.position.z, normal.x, normal.y, normal.z,
              coat, p.x / repeat, -p.y / repeat,
              maskU(tile.coords(), mask.data, p), maskV(tile.coords(), mask.data, p),
              mask.offsetU, mask.offsetV, mask.scaleU, mask.scaleV };
        if (tessellated) {
            int shape = vertex.length;
            vertex = Arrays.copyOf(vertex, shape + 5);
            vertex[shape + 4] = 1;
            if (ground != null) {
                vertex[shape] = ground.color.toFloatBits();
                vertex[shape + 1] = ground.uv.x;
                vertex[shape + 2] = ground.uv.y;
                vertex[shape + 3] = lift;
                vertex[shape + 4] = surface.fixedTessellation(ground.position) ? 1 : 0;
            }
        }
        return mesh.vertex(vertex);
    }

    /** Where the point {@code p} of the hex at {@code at} lies across {@code mask}: 0 at its left edge, 1 at its right. */
    static float maskU(Coords at, MaskData mask, Vector3 p) {
        return ((p.x - BoardGeometry.centerX(at)) / BoardGeometry.hexScale() - mask.x()) / mask.width();
    }

    /** Where the point {@code p} of the hex at {@code at} lies down {@code mask}: 0 at its first row, 1 past its last. */
    static float maskV(Coords at, MaskData mask, Vector3 p) {
        return ((p.y - BoardGeometry.centerY(at)) / BoardGeometry.hexScale() - mask.y()) / mask.height();
    }

    static float coverage(BoardRoad road, Patch patch, float x, float y) {
        Fade fade = patch.fade();
        if (fade == null) { return 1; }
        // Faint, on the paved road's wheel paths, thinning out away from the loose road.
        if (carried(patch)) { return .55f * road.runOn(x, y, carry()) * road.pavedWheels(x, y); }
        if (spill(patch)) { return Math.clamp(1 - road.pavedDepth(x, y) / spill(), 0, 1); }
        // A gravel spread is whole at the border, as the gravel's own track is, and gone by the join's reach.
        if (track(patch) && fade.join() != null) { return Math.min(1, 2 * materialCoverage(patch, x, y)); }
        if (fade.landing()) {
            float t = Math.clamp((-road.endDistance(x, y) - fade.setback()) / fade.end(), 0, 1);
            float metre = BoardRelief.metres(1) / BoardGeometry.hexScale();
            float wx = (x + BoardGeometry.centerX(road.coords()) / BoardGeometry.hexScale()) / metre;
            float wy = (y + BoardGeometry.centerY(road.coords()) / BoardGeometry.hexScale()) / metre;
            float wear = .7f * BoardRelief.noise(wx * 1.3f, wy * 1.3f)
                  + .3f * BoardRelief.noise(wx * 3.7f + 17, wy * 3.7f - 9) - .5f;
            t = Math.clamp(t + wear * .55f * t * (1 - t), 0, 1);
            // The landing fans out, then returns to the bank. The shader resolves actual chips and aggregate
            // from the surface's grain, instead of stretching a few large noise blobs into fingers.
            float fan = .35f + .65f * (float) Math.sin(Math.PI * t);
            float outer = fade.outer() * fan;
            boolean asphalt = asphalt(patch) || slab(patch);
            float inner = fade.inner() - (asphalt ? (1 - t) * 4 : 0);
            float edge = Math.clamp((outer - road.distance(x, y)) / (outer - inner), 0, 1);
            // Aggregate remains substantial beyond the last asphalt fragments, then separates into grains.
            if (!asphalt) { t = Math.min(1, t / .6f); }
            return t * t * (3 - 2 * t) * edge;
        }
        float alpha = fade.outer() > fade.inner()
              ? Math.clamp((fade.outer() - road.distance(x, y)) / (fade.outer() - fade.inner()), 0, 1) : 1;
        if (track(patch) && patch.texture().equals(texture(BoardRoad.Kind.DIRT))) {
            // A dirt track runs on over a paved road's landing in its ruts only, whole over half its run-on, then
            // wearing out as at its own end: its loose soil and the grass between them stay off it.
            float on = road.runOn(x, y, runOn());
            if (on < 1) {
                float metre = BoardRelief.metres(1) / BoardGeometry.hexScale();
                float rut = road.ruts(x, y).across() * (BoardRoad.RUT_OFFSET + BoardRoad.RUT_REACH) - BoardRoad.RUT_OFFSET;
                float past = BoardRelief.smooth((1 - on) * runOn() / metre);
                float wheels = 1 - BoardRelief.smooth((Math.abs(rut) / metre - .4f) / .3f);
                alpha *= Math.min(1, 2 * on) * BoardRelief.lerp(1, wheels, past);
            }
        }
        return fade.end() > 0 ? alpha * road.endCoverage(x, y, fade.end()) : alpha;
    }

    static float materialCoverage(Patch patch, float x, float y) {
        BoardRoad.Join join = patch.fade().join();
        float cover = join.coverage(x, y);
        return patch.texture().equals(texture(join.kind())) ? cover : 1 - cover;
    }

    private static Color color(BoardRoad road, Patch patch, float x, float y, Color tint) {
        tint.set(patch.tint());
        if (patch.blended()) {
            tint.a = coverage(road, patch, x, y);
        }
        if (track(patch)) {
            // A dirt track's B is its ruts (BoardRoad#ruts); a gravel road's, where across the whole road the point lies.
            boolean dirt = patch.texture().equals(texture(BoardRoad.Kind.DIRT));
            var sample = dirt ? road.ruts(x, y) : road.track(x, y);
            // Road UVs point down in Y. RG carries the heading, not an albedo tint; on gravel its length falls to a half
            // across a junction's hub (BoardRoad#trackHub, #track).
            float length = dirt ? 1 : 1 - .5f * road.trackHub(x, y);
            tint.r = Math.round(128 + sample.heading().x * 127 * length) / 255f;
            tint.g = Math.round(128 - sample.heading().y * 127 * length) / 255f;
            tint.b = sample.across();
        } else if (patch.fade() != null && patch.fade().join() != null) {
            // Transition masks carry along/across coordinates in RG, separately from edge coverage in A.
            // Their shader supplies white material tint. Neither coordinate can taper the road's footprint.
            tint.r = materialCoverage(patch, x, y);
            tint.g = Math.clamp(.5f + patch.fade().join().lateral(x, y) / (2 * BoardRoad.JOIN_REACH), 0, 1);
            tint.b = 0;
        } else if (spill(patch)) {
            // G: across the nearest arm, as transitions carry it, whose wheels carry most grit on; B 1 tells it from
            // carried grit, which shares its finish (terrain-road.frag).
            BoardRoad.Join arm = null;
            for (var join : road.joins()) {
                if (road.meets(join) && (arm == null || Math.abs(join.lateral(x, y)) < Math.abs(arm.lateral(x, y)))) {
                    arm = join;
                }
            }
            tint.g = Math.clamp(.5f + arm.lateral(x, y) / (2 * BoardRoad.JOIN_REACH), 0, 1);
            tint.b = 1;
        } else if (carried(patch)) {
            tint.b = 0;
        } else if (slab(patch)) {
            // RG: the phase along the course (BoardRoad#slab) as a unit vector, which filters smoothly where it wraps;
            // B: where across it, 0 at one edge and 1 at the other.
            float[] slab = road.slab(x, y);
            double phase = slab == null ? 0 : 2 * Math.PI * slab[0];
            tint.r = Math.round(128 + (float) Math.cos(phase) * 127) / 255f;
            tint.g = Math.round(128 + (float) Math.sin(phase) * 127) / 255f;
            tint.b = slab == null ? .5f : .5f + .5f * slab[1];
        } else if (patch.fade() != null && patch.fade().wheels()) {
            var wear = road.pavedWear(x, y);
            var direction = wear.heading();
            // Paved wear's heading, as a track's; B is its lane position (BoardRoad#wearHub, #wearLane).
            float length = 1 - .5f * road.wearHub(x, y);
            tint.r = Math.round(128 + direction.x * 127 * length) / 255f;
            tint.g = Math.round(128 - direction.y * 127 * length) / 255f;
            tint.b = wear.across();
        }
        return tint;
    }
}
