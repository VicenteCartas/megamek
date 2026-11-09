/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.TextureArrayData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import jakarta.annotation.Nullable;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.logging.MMLogger;

/** Cosmetic ground history on the render thread. Neither marks nor their texture change the board or game rules. */
final class GpuGroundDamage implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuGroundDamage.class);
    static final boolean COMBAT_SCARS = true;
    static final int TILE_SIZE = 256;
    static final int TILE_BORDER = 8;
    static final int TILE_INTERIOR = TILE_SIZE - 2 * TILE_BORDER;
    static final float TILE_METRES = 8;
    /** Half of the authored transition: eight metres on either side of a different-severity hex boundary. */
    static final float AUTHORED_EDGE_METRES = 8;
    static final int STAMPS_PER_FRAME = 8;
    /**
     * Texels the marks one frame paints may cover, at least one tile's worth: a few craters fit in a frame, while a
     * large mark (a fuel-air round's charring, any area effect's) resumes tile by tile over the next frames.
     */
    static final int STAMP_TEXELS_PER_FRAME = 120_000;
    /** A frame whose mark painting takes longer than this is logged. */
    private static final long SLOW_STAMPING_MILLIS = 20;
    private static final int MAX_PENDING_STAMPS = 256;
    private static final int MAX_EVENTS = UnitPlayback.MAX_PENDING_EVENTS * 2;
    /** An area effect marks every hex in its radius: one attack may leave as many marks as can wait. */
    private static final int MAX_MARKS_PER_EVENT = MAX_PENDING_STAMPS;
    private static final int MAX_BRUSH_SIZE = 256;

    enum Style {
        LASER("laser-gouge"), PPC("ppc-gouge"), BALLISTIC("ballistic-gouge"),
        LASER_BLAST("laser-blast"), PPC_BLAST("ppc-blast"), BALLISTIC_BLAST("ballistic-blast"),
        BLAST("explosive-blast"), BURN("fire-burn"), PLASMA("plasma-splash");

        private final String file;
        Style(String file) { this.file = file; }

        boolean cut() { return this == LASER || this == PPC || this == BALLISTIC; }
        boolean thermal() { return this == LASER || this == PPC || this == LASER_BLAST || this == PPC_BLAST || this == PLASMA; }
    }

    /**
     * A fuel-air round's charring of each 30 m hex in its radius: its soft edge reaches most of the hex. Kept to 9 m,
     * as one mark is painted in one frame and a 13 m mark took about 45 ms.
     */
    static final float FUEL_AIR_CHAR_METRES = 9;
    /** The ring damage at which fuel-air charring is darkest: a Sniper's or an Arrow IV's target hex. */
    static final float FUEL_AIR_DARKEST_DAMAGE = 20;

    /** The damage at which an artillery crater keeps the size every blast crater has: a Sniper's. */
    static final float ARTILLERY_CRATER_DAMAGE = 20;

    /** Miss endpoints and contact times are supplied by the existing attack effects, never rolled here. */
    record Impact(UnitAttack attack, String key, Vector3 origin, Vector3 point, String effect, ResolvedAttack.Shot shot) {
        Style style() {
            // Captured weapon flags take precedence over a mesh's generic energy/bullet emitter.
            if (shot != null) {
                // Fuel-air ordnance burns rather than digs: it chars the ground of every hex in its radius.
                if (shot.artillery()) { return fuelAir() ? Style.BURN : Style.BLAST; }
                if (shot.plasma()) { return Style.PLASMA; }
                if (shot.ppc()) { return Style.PPC; }
            }
            return switch (effect) {
                case "laser" -> Style.LASER;
                case "ppc" -> Style.PPC;
                case "bullet" -> Style.BALLISTIC;
                case "flame" -> Style.BURN;
                default -> Style.BLAST;
            };
        }

        boolean cut() { return style().cut(); }

        /** Within 45 degrees of the receiving triangle's normal, use a round impact of the same material. */
        Style style(BoardSurface.Face face) {
            var normal = face.b().cpy().sub(face.a()).crs(face.c().x - face.a().x,
                  face.c().y - face.a().y, face.c().z - face.a().z);
            return style(normal);
        }

        Vector3 incoming() {
            var contact = attack == null ? null : attack.landscapeContact(point);
            return contact == null ? point.cpy().sub(origin) : contact.incoming().cpy();
        }

        Style style(Vector3 normal) {
            var style = style();
            if (!style.cut()) { return style; }
            var incoming = incoming();
            float lengths = incoming.len2() * normal.len2(), dot = incoming.dot(normal);
            // Squared cosine: no normalization or trigonometry, and triangle winding does not matter.
            if (!(lengths > 0) || 2 * dot * dot < lengths) { return style; }
            return switch (style) {
                case LASER -> Style.LASER_BLAST;
                case PPC -> Style.PPC_BLAST;
                case BALLISTIC -> Style.BALLISTIC_BLAST;
                default -> style;
            };
        }

        boolean burn() { return style() == Style.BURN; }

        boolean thermal() { return style().thermal(); }

        float radius() {
            return radius(style());
        }

        float radius(Style style) {
            // Zero-damage weapons still leave a visible scar; the captured game value stays unchanged.
            float damage = shot == null ? cut() ? 5 : 1 : (float) Math.max(1, shot.damagePerHit());
            if ((style == Style.BURN) && fuelAir()) {
                return BoardRelief.metres(FUEL_AIR_CHAR_METRES);
            }
            if ((style == Style.BLAST) && (shot != null) && shot.artillery()) {
                // An artillery crater, and the scorch marks in its rings, grow in step with damage as its blast does.
                return BoardRelief.metres(blastCraterMetres(ARTILLERY_CRATER_DAMAGE) * damage / ARTILLERY_CRATER_DAMAGE);
            }
            if (style == Style.BLAST || style == Style.PLASMA) {
                return BoardRelief.metres(blastCraterMetres(damage));
            }
            // Gouges are 5:1. Their perpendicular blast diameter is twice the gouge width.
            float scale = switch (style) {
                case LASER_BLAST, PPC_BLAST, BALLISTIC_BLAST -> 2f / 5;
                default -> 1;
            };
            return BoardRelief.metres(1.5f * (float) Math.sqrt(damage) * scale);
        }

        /** A blast crater's radius in metres: one power curve through 2.7 m diameter at 1 damage and 12 m at 25. */
        static float blastCraterMetres(float damage) {
            return 1.35f * (float) Math.pow(damage, .4634086426);
        }

        float strength() {
            if (fuelAir()) {
                // A fuel-air round chars its target hex black and its outer rings lighter, as its damage falls.
                double damage = Math.max(0, shot.damagePerHit());
                return MathUtils.clamp(.4f + .6f * (float) damage / FUEL_AIR_DARKEST_DAMAGE, .4f, 1);
            }
            // A readable burn, lighter than an opaque crater; damage changes the footprint only.
            return burn() ? .65f : 1;
        }

        /** Whether the mark is a fuel-air round's charring of one hex in its radius. */
        boolean fuelAir() {
            return (shot != null) && shot.artillery() && shot.munitions().contains("M_FAE");
        }
    }

    private final Map<UUID, Set<String>> seen = new LinkedHashMap<>();
    /** Texture storage may be square; the trimmed artwork keeps its physical canvas proportions. */
    private record BrushArt(List<Pixmap> levels, List<int[]> texels, float aspect) { }
    private final Map<Style, BrushArt> brushes = new EnumMap<>(Style.class);
    private record Stamp(int level, Vector3 point, float radius, float angle, Style style, int seed, float strength) { }
    private final ArrayDeque<Stamp> pending = new ArrayDeque<>();
    /** The tile of the oldest waiting mark that painting resumes from: 0 until a mark spans frames. */
    private int resumeTile;
    private BoardScene groundScene;
    private final Map<Integer, Tile> tiles = new HashMap<>();
    private final List<Pixmap> directory = new ArrayList<>();
    private final List<Pixmap> mipScratch = new ArrayList<>();
    private final Set<Tile> dirty = new HashSet<>();
    private TextureArray texture;
    private int boardId, columns, rows;
    private float worldWidth, worldHeight;
    private int tileColumns, tileRows, capacity, maxLayers;
    private float tileWorldSize;
    private boolean directoryDirty;
    private int marks;
    private boolean scorched;
    /** Blast seconds since the last scorch ({@link #advance}). */
    private float scorchAge;
    private long scorchRevision;
    /** The hexes' authored scorch bytes as last written to the directory's alpha byte, and the tiles they came from. */
    private byte[] authored = new byte[0];
    private List<BoardScene.Tile> authoredTiles;
    private boolean authoredAny;
    /** The fused crust at Ground Zero cools over about this many seconds. */
    static final float HEAT_SECONDS = 25;
    private int nextSurfaceKey = -1;

    /** CPU tiles are allocated only around impacts; their GPU layers have the same fixed ground scale. */
    record Tile(int layer, Pixmap pixels, IntBuffer texels) {
        Tile(int layer, Pixmap pixels) { this(layer, pixels, GpuGroundDamage.texels(pixels)); }
        // The buffer is a mutable view, not part of a tile's identity in the dirty set.
        @Override public boolean equals(Object other) { return other instanceof Tile tile && layer == tile.layer && pixels == tile.pixels; }
        @Override public int hashCode() { return 31 * layer + System.identityHashCode(pixels); }
    }

    private static IntBuffer texels(Pixmap pixels) {
        return pixels.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
    }

    private static int[] array(Pixmap pixels) {
        int[] values = new int[pixels.getWidth() * pixels.getHeight()];
        texels(pixels).get(values);
        return values;
    }

    TextureArray texture() { return texture; }
    float tileWorldSize() { return tileWorldSize; }
    int tileColumns() { return tileColumns; }
    int tileRows() { return tileRows; }
    int tileCount() { return tiles.size(); }
    static String shaderSource() {
        return "#define GROUND_DAMAGE_TILE " + TILE_SIZE + ".0\n#define GROUND_DAMAGE_INNER " + TILE_INTERIOR
              + ".0\n#define GROUND_DAMAGE_BORDER " + TILE_BORDER + ".0\n"
              + GpuShaderSource.read("terrain-damage-mask.glsl");
    }

    /** Even a disabled array sampler needs a binding distinct from the shader's 2D samplers. */
    TextureArray binding() {
        if (texture == null) { texture = new TextureArray(new TileData(1)); capacity = 1; }
        return texture;
    }
    float width() { return worldWidth; }
    float height() { return worldHeight; }
    int marks() { return marks; }

    /** Whether a nuclear blast has scorched this board: Ground Zero's heat ({@link #scorchHeat}) is its own. */
    boolean scorched() { return scorched; }

    /** Whether any scorch shows: a nuclear blast's or a hex's authored one ({@link HexAppearance#scorch}). */
    boolean scorchShown() { return scorched || authoredAny; }

    long scorchRevision() { return scorchRevision; }

    /**
     * Baked decorative vegetation belongs to its hex. Clear it when authored scorch reaches that hex or a nuclear
     * directory texel's bilinear footprint touches it. This reads the existing mask only when the scene or scorch
     * changes; it neither advances the displayed authored mask nor changes gameplay vegetation.
     */
    boolean scorched(BoardScene scene, BoardScene.Tile tile) {
        if (HexAppearance.scorch(tile.appearance()) > 0) { return true; }
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(tile, direction);
            if (neighbor != null && HexAppearance.scorch(neighbor.appearance()) > 0) { return true; }
        }
        if (!scorched || directory.isEmpty() || boardId != scene.boardId()
              || columns != scene.width() || rows != scene.height()) { return false; }
        float cx = BoardGeometry.centerX(tile.coords()), cy = -BoardGeometry.centerY(tile.coords());
        float halfWidth = BoardGeometry.width() * .5f + tileWorldSize;
        float halfHeight = BoardGeometry.height() * .5f + tileWorldSize;
        int firstX = Math.max(0, (int) Math.floor((cx - halfWidth) / tileWorldSize));
        int lastX = Math.min(tileColumns - 1, (int) Math.floor((cx + halfWidth) / tileWorldSize));
        int firstY = Math.max(0, (int) Math.floor((cy - halfHeight) / tileWorldSize));
        int lastY = Math.min(tileRows - 1, (int) Math.floor((cy + halfHeight) / tileWorldSize));
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                int key = y * tileColumns + x, address = key % (TILE_SIZE * TILE_SIZE);
                int pixel = directory.get(key / (TILE_SIZE * TILE_SIZE)).getPixel(address % TILE_SIZE, address / TILE_SIZE);
                if ((pixel >>> 8 & 255) > 0 && BoardBiome.hexDistance(
                      Math.abs((x + .5f) * tileWorldSize - cx), Math.abs((y + .5f) * tileWorldSize - cy))
                      < tileWorldSize * 1.5f) { return true; }
            }
        }
        return false;
    }

    /** Ground Zero's glow after the last blast: 1 as it goes off, a third left after {@link #HEAT_SECONDS}. */
    float scorchHeat() {
        return scorched ? (float) Math.exp(-scorchAge / HEAT_SECONDS) : 0;
    }

    /** Ages Ground Zero's glow on the blasts' clock (GpuArtilleryBlast#advance), which pauses with the playback. */
    void advance(float seconds) { scorchAge += seconds; }

    /**
     * A nuclear blast's scorch (GpuArtilleryBlast): Ground Zero at {@code centre}, the blast area's radius and the
     * crater's, in world units.
     */
    record Scorch(Vector3 centre, float radius, float crater) { }

    /**
     * Scorches every 8 m tile within the blast area: each takes the blast's damage there as a share of Ground Zero's,
     * which falls off linearly to nothing at the edge as the rules' damage does, and the crater takes all of it. The
     * share lives in the directory texels' blue byte, which the terrain reads with the scars (terrain-damage-mask.glsl);
     * a later blast keeps the stronger scorch, and the authored scorch in the alpha byte stays. It is written once, so even the largest blast costs one pass over its
     * tiles and one directory upload, and nothing per frame.
     */
    void scorch(Scorch blast) {
        if (directory.isEmpty() || blast.radius() <= 0) { return; }
        float radius = blast.radius(), centreX = blast.centre().x, centreY = -blast.centre().y;
        int firstX = Math.max(0, (int) Math.floor((centreX - radius) / tileWorldSize));
        int lastX = Math.min(tileColumns - 1, (int) Math.floor((centreX + radius) / tileWorldSize));
        int firstY = Math.max(0, (int) Math.floor((centreY - radius) / tileWorldSize));
        int lastY = Math.min(tileRows - 1, (int) Math.floor((centreY + radius) / tileWorldSize));
        int pageSize = TILE_SIZE * TILE_SIZE;
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                float distance = Vector2.len((x + .5f) * tileWorldSize - centreX, (y + .5f) * tileWorldSize - centreY);
                if (distance >= radius) { continue; }
                int value = Math.round((distance <= blast.crater() ? 1 : 1 - distance / radius) * 255);
                int key = y * tileColumns + x;
                Pixmap page = directory.get(key / pageSize);
                int address = key % pageSize;
                int pixel = page.getPixel(address % TILE_SIZE, address / TILE_SIZE);
                if ((pixel >>> 8 & 255) >= value) { continue; }
                page.drawPixel(address % TILE_SIZE, address / TILE_SIZE, pixel & 0xFFFF00FF | value << 8);
            }
        }
        scorched = true;
        scorchAge = 0;
        scorchRevision++;
        directoryDirty = true;
    }

    /** New boards clear history; camera changes, chunk rebuilds and detail changes do not. */
    void update(BoardScene scene) {
        if (scene == null) { return; }
        float width = (scene.width() * .75f + .25f) * BoardGeometry.width();
        float height = (scene.height() + .5f) * BoardGeometry.height();
        if (boardId != scene.boardId() || columns != scene.width() || rows != scene.height()
              || width != worldWidth || height != worldHeight) {
            dispose();
            boardId = scene.boardId(); columns = scene.width(); rows = scene.height();
            worldWidth = width; worldHeight = height;
            if (COMBAT_SCARS) { allocate(); }
        }
        groundScene = scene;
        author(scene);
    }

    /**
     * The hexes' authored scorch ({@link HexAppearance#scorch}) in the directory texels' alpha byte. Neighbouring
     * severities blend across their shared edge; equal severities stay constant. Only changed hexes and their neighbours
     * are rewritten, and nothing when the scene's tiles are the same. Nuclear B and combat RG remain untouched.
     */
    private void author(BoardScene scene) {
        if (directory.isEmpty() || scene.tiles() == authoredTiles) { return; }
        authoredTiles = scene.tiles();
        if (authored.length != columns * rows) { authored = new byte[columns * rows]; }
        int pageSize = TILE_SIZE * TILE_SIZE;
        Set<Coords> changed = new HashSet<>();
        boolean any = false;
        for (BoardScene.Tile tile : scene.tiles()) {
            int value = Math.round(HexAppearance.scorch(tile.appearance()) * 255);
            any |= value > 0;
            int index = tile.coords().getY() * columns + tile.coords().getX();
            if ((authored[index] & 255) == value) { continue; }
            authored[index] = (byte) value;
            changed.add(tile.coords());
            for (int direction = 0; direction < 6; direction++) {
                var next = scene.neighbor(tile, direction);
                if (next != null) { changed.add(next.coords()); }
            }
        }
        // Publish every byte before sampling the field, so painting/erasing a group is independent of tile order.
        for (Coords coords : changed) {
            Vector3 centre = BoardGeometry.center(coords, 0);
            float halfWidth = BoardGeometry.width() * .5f, halfHeight = BoardGeometry.height() * .5f;
            int firstX = Math.max(0, (int) Math.floor((centre.x - halfWidth) / tileWorldSize));
            int lastX = Math.min(tileColumns - 1, (int) Math.floor((centre.x + halfWidth) / tileWorldSize));
            int firstY = Math.max(0, (int) Math.floor((-centre.y - halfHeight) / tileWorldSize));
            int lastY = Math.min(tileRows - 1, (int) Math.floor((-centre.y + halfHeight) / tileWorldSize));
            for (int y = firstY; y <= lastY; y++) {
                for (int x = firstX; x <= lastX; x++) {
                    float px = (x + .5f) * tileWorldSize, py = -(y + .5f) * tileWorldSize;
                    if (!BoardGeometry.contains(coords, px, py)) { continue; }
                    int value = authoredAt(px, py);
                    int key = y * tileColumns + x;
                    Pixmap page = directory.get(key / pageSize);
                    int address = key % pageSize;
                    int pixel = page.getPixel(address % TILE_SIZE, address / TILE_SIZE);
                    page.drawPixel(address % TILE_SIZE, address / TILE_SIZE, pixel & 0xFFFFFF00 | value);
                }
            }
            directoryDirty = true;
        }
        authoredAny = any;
    }

    /** The same bounded hex-distance stencil used by biome coverage, evaluated only when authored data changes. */
    private int authoredAt(float x, float y) {
        float width = BoardRelief.metres(AUTHORED_EDGE_METRES), sum = 0, severity = 0;
        int column = (int) Math.floor(x / (BoardGeometry.width() * .75f));
        for (int cx = column - 1; cx <= column + 1; cx++) {
            if (cx < 0 || cx >= columns) { continue; }
            int row = (int) Math.floor(-y / BoardGeometry.height() - (cx & 1) * .5f);
            for (int cy = row - 1; cy <= row + 1; cy++) {
                if (cy < 0 || cy >= rows) { continue; }
                var coords = new Coords(cx, cy);
                float distance = BoardBiome.hexDistance(Math.abs(x - BoardGeometry.centerX(coords)),
                      Math.abs(y - BoardGeometry.centerY(coords)));
                float weight = 1 - BoardRelief.smooth((distance + width) / (2 * width));
                sum += weight;
                severity += weight * (authored[cy * columns + cx] & 255);
            }
        }
        return sum > 0 ? Math.round(severity / sum) : 0;
    }

    void impact(BoardScene scene, Impact impact, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        impact(scene, impact, surfaces, ignored -> { });
    }

    void impact(BoardScene scene, Impact impact, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          java.util.function.Consumer<Impact> vertical) {
        if (!COMBAT_SCARS || scene == null || impact.attack().event.boardId() != scene.boardId()) { return; }
        update(scene);
        Set<String> keys = seen.computeIfAbsent(impact.attack().event.result().id(), ignored -> new HashSet<>());
        if (seen.size() > MAX_EVENTS) { seen.remove(seen.keySet().iterator().next()); }
        if (keys.size() >= MAX_MARKS_PER_EVENT || !keys.add(impact.key())) { return; }
        Vector3 point = impact.point();
        var contact = impact.attack().landscapeContact(point);
        // A deformed cliff can project into its neighbour (or beyond the board's flat outline).
        var tile = contact == null ? BoardGeometry.tile(scene, point.x, point.y) : scene.tile(contact.surface().coords());
        if (tile == null || impact.attack().landscape == null) {
            artilleryMarkDropped(impact, "it lies on no board tile, or the attack is not on the terrain yet");
            return;
        }
        // The attack already picked its collision. Compare its endpoint with the installed top triangles;
        // another board-wide ray would rebuild geometry unnecessarily and could stall at the first impact.
        float lift = BoardRelief.metres(.1f);
        var surface = surfaces.apply(tile.coords());
        if (surface == null) {
            artilleryMarkDropped(impact, "the tile has no installed surface");
            return;
        }
        var face = surface.top().stream().filter(candidate -> Math.abs(candidate.height(point.x, point.y) - point.z) < lift)
              .findFirst().orElse(null);
        Vector3 stampAt = point;
        // An artillery round's landing was found by a ray that stops at whatever stands on the hex, such as the
        // canopy of woods: its crater belongs on the ground beneath, not on the trees.
        if ((face == null) && isArtillery(impact)) {
            face = groundBeneath(surface.top(), point.x, point.y);
            if (face != null) {
                stampAt = new Vector3(point.x, point.y, face.height(point.x, point.y));
            }
        }
        if (face == null) {
            artilleryMarkDropped(impact, "its height matches no top face of " + tile.coords().getBoardNum());
            vertical.accept(impact);
            return;
        }
        if (tile.liquid().present() || tile.frozen()) {
            artilleryMarkDropped(impact, "the hex is water or ice");
            return;
        }
        var style = impact.style(face);
        if (brushes.get(style).levels().isEmpty()) { return; }
        int seed = impact.attack().event.result().id().hashCode() ^ impact.key().hashCode();
        var incoming = impact.incoming();
        float dx = incoming.x, dy = incoming.y;
        float length = (float) Math.hypot(dx, dy);
        float angle = length < .001f ? UnitAttack.noise(seed) * MathUtils.PI2 : MathUtils.atan2(dy, dx);
        if (pending.size() >= MAX_PENDING_STAMPS) {
            pending.removeFirst();
            resumeTile = 0;
        }
        pending.addLast(new Stamp(tile.elevation(), stampAt.cpy(), impact.radius(style), angle, style, seed,
              impact.strength()));
    }

    /**
     * The ground face directly beneath a point: the highest of the hex's top faces over it, or {@code null} when it lies
     * over none of them.
     */
    static @Nullable BoardSurface.Face groundBeneath(List<BoardSurface.Face> top, float pointX, float pointY) {
        BoardSurface.Face highest = null;
        float highestHeight = Float.NEGATIVE_INFINITY;
        for (BoardSurface.Face candidate : top) {
            float height = candidate.height(pointX, pointY);
            if (height > highestHeight) {
                highestHeight = height;
                highest = candidate;
            }
        }
        return highest;
    }

    private static boolean isArtillery(Impact impact) {
        return (impact.shot() != null) && impact.shot().artillery();
    }

    /**
     * Logs why an artillery round's ground mark is not painted: these are silent otherwise, and a crater missing after
     * a blast is the first thing a playtest asks about. One line per round, at most; other weapons' marks stay quiet.
     */
    private static void artilleryMarkDropped(Impact impact, String reason) {
        if (isArtillery(impact)) {
            LOGGER.info("[GroundDamage] no crater for the artillery round at {}: {}", impact.point(), reason);
        }
    }

    private void allocate() {
        // Decode at board setup, never in the middle of the first impact. No additional GPU samplers.
        for (var style : Style.values()) { brushes.put(style, loadBrush(style)); }
        tileWorldSize = BoardRelief.metres(TILE_METRES);
        tileColumns = (int) Math.ceil(worldWidth / tileWorldSize);
        tileRows = (int) Math.ceil(worldHeight / tileWorldSize);
        int entries = Math.multiplyExact(tileColumns, tileRows);
        for (int i = 0; i < Math.ceilDiv(entries, TILE_SIZE * TILE_SIZE); i++) { directory.add(blank()); }
        var limit = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS, limit);
        maxLayers = limit.get(0);
        directoryDirty = true;
    }

    private static Pixmap blank() {
        return blank(TILE_SIZE);
    }

    private static Pixmap blank(int size) {
        var result = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        result.setBlending(Pixmap.Blending.None);
        result.setColor(0);
        result.fill();
        return result;
    }

    /** Vertical receivers share the same image layers, filtering, upload budget and material channels. */
    Tile surfaceTile() {
        if (directory.size() + tiles.size() >= maxLayers) { return null; }
        var tile = new Tile(directory.size() + tiles.size(), blank());
        tiles.put(nextSurfaceKey--, tile);
        return tile;
    }

    void changed(Tile tile) {
        dirty.add(tile);
        marks++;
    }

    record Brush(Pixmap image, int[] texels, float aspect, float mirror, float strength) {
        int sample(float u, float v) {
            if (Math.abs(u) > 1 || Math.abs(v) > 1) { return 0; }
            return filtered(texels, image.getWidth(), image.getHeight(), (u * .5f + .5f) * image.getWidth() - .5f,
                  (.5f - v * mirror * .5f) * image.getHeight() - .5f);
        }
    }

    Brush brush(Style style, float radius, float strength, int seed) {
        var art = brushes.get(style);
        if (art == null || art.levels().isEmpty()) { return null; }
        var levels = art.levels();
        int lod = 0;
        float diameter = 2 * radius * TILE_INTERIOR / tileWorldSize;
        while (lod + 1 < levels.size() && levels.get(lod).getWidth() > diameter * 2) { lod++; }
        return new Brush(levels.get(lod), art.texels().get(lod), art.aspect(),
              (seed & 1) == 0 ? 1 : -1, strength);
    }

    static void blend(Tile tile, int x, int y, int sample, float strength) {
        float alpha = (sample >>> 24) / 255f * strength;
        int index = y * TILE_SIZE + x, old = tile.texels().get(index), combined = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int value = Math.round((sample >>> shift & 255) * strength + (old >>> shift & 255) * (1 - alpha));
            combined |= Math.min(255, value) << shift;
        }
        tile.texels().put(index, combined);
    }

    /**
     * Paints a mark's tiles from {@link #resumeTile} on until {@code budget} texels are spent, and returns the texels
     * spent. It leaves {@link #resumeTile} at the next tile, or at 0 once the mark is finished, so a frame costs at most
     * about one budget and one tile however large an area effect's marks grow.
     */
    private int stamp(Stamp mark, int budget) {
        float density = TILE_INTERIOR / tileWorldSize;
        float cos = MathUtils.cos(mark.angle()), sin = MathUtils.sin(mark.angle());
        float radius = mark.radius();
        var brush = brush(mark.style(), radius, mark.strength(), mark.seed());
        if (brush == null) {
            resumeTile = 0;
            return 0;
        }
        int spent = 0;
        // Brush dimensions describe the visible art, after empty authoring margins are removed at load.
        float halfHeight = radius * brush.aspect();
        float extentX = Math.abs(cos) * radius + Math.abs(sin) * halfHeight;
        float extentY = Math.abs(sin) * radius + Math.abs(cos) * halfHeight;
        var point = mark.point();
        // A brush magnified over the mask is sampled once per block of texels it spans: no detail is lost, and a
        // fuel-air char's work drops by the square of its magnification.
        int step = Math.max(1, (int) (2 * radius * density / brush.image().getWidth()));
        BoardScene.Tile receiver = null;
        int[] range = tileRange(point, extentX, extentY);
        int index = 0;
        for (int ty = range[2]; ty <= range[3]; ty++) {
            for (int tx = range[0]; tx <= range[1]; tx++, index++) {
                if (index < resumeTile) { continue; }
                if (spent > 0 && spent >= budget) {
                    resumeTile = index;
                    return spent;
                }
                int key = ty * tileColumns + tx;
                Tile tile = tiles.get(key);
                float ox = tx * tileWorldSize, oy = ty * tileWorldSize;
                int x0 = Math.max(0, (int) Math.floor((point.x - extentX - ox) * density) + TILE_BORDER);
                int x1 = Math.min(TILE_SIZE - 1, (int) Math.ceil((point.x + extentX - ox) * density) + TILE_BORDER);
                int y0 = Math.max(0, (int) Math.floor((-point.y - extentY - oy) * density) + TILE_BORDER);
                int y1 = Math.min(TILE_SIZE - 1, (int) Math.ceil((-point.y + extentY - oy) * density) + TILE_BORDER);
                spent += Math.max(0, x1 - x0 + 1) * Math.max(0, y1 - y0 + 1);
                // Blocks start on global texel positions so adjoining tiles have identical border samples.
                int originX = tx * TILE_INTERIOR - TILE_BORDER, originY = ty * TILE_INTERIOR - TILE_BORDER;
                for (int by = y0 - Math.floorMod(originY + y0, step); by <= y1; by += step) {
                    for (int bx = x0 - Math.floorMod(originX + x0, step); bx <= x1; bx += step) {
                        float wx = (originX + bx + step * .5f) / density;
                        float wy = -(originY + by + step * .5f) / density;
                        float px = wx - point.x, py = wy - point.y;
                        float u = (px * cos + py * sin) / radius, v = (-px * sin + py * cos) / halfHeight;
                        int sample = brush.sample(u, v);
                        float alpha = (sample >>> 24) / 255f * mark.strength();
                        if (alpha < 1 / 255f) { continue; }
                        if (receiver == null || !BoardGeometry.contains(receiver.coords(), wx, wy)) {
                            receiver = BoardGeometry.tile(groundScene, wx, wy);
                        }
                        if (receiver == null || receiver.elevation() != mark.level() || receiver.liquid().present() || receiver.frozen()) { continue; }
                        if (tile == null) {
                            if (directory.size() + tiles.size() >= maxLayers) {
                                LOGGER.warn("Ground scars exhausted the GPU's {} texture-array layers", maxLayers);
                                resumeTile = 0;
                                return spent;
                            }
                            tile = new Tile(directory.size() + tiles.size(), blank());
                            tiles.put(key, tile);
                            int address = key % (TILE_SIZE * TILE_SIZE);
                            int layer = tile.layer();
                            // The layer takes the red and green bytes; a nuclear scorch keeps its blue byte.
                            Pixmap page = directory.get(key / (TILE_SIZE * TILE_SIZE));
                            int kept = page.getPixel(address % TILE_SIZE, address / TILE_SIZE) & 0xFFFF;
                            page.drawPixel(address % TILE_SIZE, address / TILE_SIZE,
                                  (layer & 255) << 24 | (layer >>> 8) << 16 | kept);
                            directoryDirty = true;
                        }
                        // Premultiplied coverage, authored luminance, gloss and relief; newer art blends over older art.
                        for (int y = Math.max(by, y0); y < Math.min(by + step, y1 + 1); y++) {
                            for (int x = Math.max(bx, x0); x < Math.min(bx + step, x1 + 1); x++) {
                                blend(tile, x, y, sample, mark.strength());
                            }
                        }
                    }
                }
                if (tile != null) { dirty.add(tile); }
            }
        }
        resumeTile = 0;
        return spent;
    }

    /** The first and last tile columns and rows a mark of these extents about {@code point} reaches, borders included. */
    private int[] tileRange(Vector3 point, float extentX, float extentY) {
        float border = TILE_BORDER * tileWorldSize / TILE_INTERIOR;
        return new int[] {
              Math.max(0, (int) Math.floor((point.x - extentX - border) / tileWorldSize)),
              Math.min(tileColumns - 1, (int) Math.floor((point.x + extentX + border) / tileWorldSize)),
              Math.max(0, (int) Math.floor((-point.y - extentY - border) / tileWorldSize)),
              Math.min(tileRows - 1, (int) Math.floor((-point.y + extentY + border) / tileWorldSize)) };
    }

    /**
     * The tiles the waiting marks may still add, at most: so a volley grows the texture once rather than doubling it
     * frame after frame, each time uploading every tile again.
     */
    private int waitingTiles() {
        Set<Integer> keys = new HashSet<>();
        for (Stamp mark : pending) {
            var art = brushes.get(mark.style());
            float extent = mark.radius() * Math.max(1, art == null ? 1 : art.aspect());
            int[] range = tileRange(mark.point(), extent, extent);
            for (int ty = range[2]; ty <= range[3]; ty++) {
                for (int tx = range[0]; tx <= range[1]; tx++) {
                    int key = ty * tileColumns + tx;
                    if (!tiles.containsKey(key)) { keys.add(key); }
                }
            }
        }
        return keys.size();
    }

    /** Editable RGBA artwork: alpha is coverage; dark values are char/melt, light values disturbed debris. */
    private static BrushArt loadBrush(Style style) {
        String material = "terrain/damage/" + style.file;
        var levels = new ArrayList<Pixmap>();
        Pixmap source = null;
        try {
            source = GpuMaterialMaps.color(material);
            int left = source.getWidth(), top = source.getHeight(), right = -1, bottom = -1;
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // Almost-transparent export residue must not determine a weapon's visible size.
                    if ((source.getPixel(x, y) & 255) < 16) { continue; }
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
            if (right < left) { throw new IllegalArgumentException("Empty scar artwork"); }
            var mask = new Pixmap(right - left + 1, bottom - top + 1, Pixmap.Format.RGBA8888);
            mask.setBlending(Pixmap.Blending.None);
            levels.add(mask);
            // Gouges retain their authored 4:1 canvas even when exported to a square texture.
            // Empty margins are measured as fractions of that canvas, before selecting mip levels.
            float aspect = (float) mask.getHeight() / mask.getWidth();
            if (style.cut()) { aspect *= .25f * source.getWidth() / source.getHeight(); }
            float depth = switch (style) {
                case BURN -> 0;
                case PLASMA -> .18f;
                case LASER, LASER_BLAST -> .55f;
                case PPC, PPC_BLAST -> .65f;
                default -> 1;
            };
            for (int y = 0; y < mask.getHeight(); y++) {
                for (int x = 0; x < mask.getWidth(); x++) {
                    int color = source.getPixel(left + x, top + y);
                    float alpha = (color & 255) / 255f;
                    float shade = ((color >>> 24) * .2126f + (color >>> 16 & 255) * .7152f
                          + (color >>> 8 & 255) * .0722f) / 255;
                    float glass = style.thermal() ? alpha * (1 - .65f * shade) : 0;
                    float relief = alpha * (1 - shade) * depth;
                    // Premultiply coverage before filtering, avoiding dark fringes from transparent RGB.
                    mask.drawPixel(x, y, channel(alpha) << 24 | channel(alpha * shade) << 16 | channel(glass) << 8 | channel(relief));
                }
            }
            // Prefilter stamps as well as the final board mask: bilinear sampling a large source alone aliases.
            while (mask.getWidth() > 1 || mask.getHeight() > 1) {
                var next = new Pixmap(Math.max(1, mask.getWidth() / 2), Math.max(1, mask.getHeight() / 2), Pixmap.Format.RGBA8888);
                next.setBlending(Pixmap.Blending.None);
                levels.add(next);
                var sourceTexels = array(mask);
                for (int y = 0; y < next.getHeight(); y++) {
                    for (int x = 0; x < next.getWidth(); x++) {
                        next.drawPixel(x, y, filtered(sourceTexels, mask.getWidth(), mask.getHeight(), (x + .5f) * mask.getWidth() / next.getWidth() - .5f,
                              (y + .5f) * mask.getHeight() / next.getHeight() - .5f));
                    }
                }
                mask = next;
            }
            while (levels.getFirst().getWidth() > MAX_BRUSH_SIZE || levels.getFirst().getHeight() > MAX_BRUSH_SIZE) {
                levels.removeFirst().dispose();
            }
            if (!style.cut()) { aspect = (float) levels.getFirst().getHeight() / levels.getFirst().getWidth(); }
            return new BrushArt(List.copyOf(levels), levels.stream().map(GpuGroundDamage::array).toList(), aspect);
        } catch (RuntimeException error) {
            levels.forEach(Pixmap::dispose);
            LOGGER.warn("Could not load ground scar {}: {}", material, error.getMessage());
            return new BrushArt(List.of(), List.of(), 1);
        } finally {
            if (source != null) { source.dispose(); }
        }
    }

    private static int filtered(int[] image, int width, int height, float x, float y) {
        x = MathUtils.clamp(x, 0, width - 1);
        y = MathUtils.clamp(y, 0, height - 1);
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
        int a = image[y0 * width + x0], b = image[y0 * width + x1], c = image[y1 * width + x0], d = image[y1 * width + x1];
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            result |= Math.round(MathUtils.lerp(MathUtils.lerp(a >>> shift & 255, b >>> shift & 255, x - x0),
                  MathUtils.lerp(c >>> shift & 255, d >>> shift & 255, x - x0), y - y0)) << shift;
        }
        return result;
    }

    private static int channel(float value) { return Math.clamp(Math.round(value * 255), 0, 255); }

    /** Upload only changed tiles before the terrain pass; settled frames and camera changes do no raster work. */
    void upload() {
        // Large salvos/instant playback must not rasterize every scar in one render frame. Retain newest arrivals
        // on overload; only compact stamp data waits here, never the attack's unit/model snapshots.
        long start = System.nanoTime();
        int stamped = 0;
        int painted = 0;
        while (stamped < STAMPS_PER_FRAME && !pending.isEmpty() && painted < STAMP_TEXELS_PER_FRAME) {
            painted += stamp(pending.peekFirst(), STAMP_TEXELS_PER_FRAME - painted);
            if (resumeTile == 0) {
                pending.removeFirst();
                marks++;
                stamped++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (millis > SLOW_STAMPING_MILLIS) {
            LOGGER.debug("[GroundDamage] painted {} marks ({} texels) in {} ms, {} waiting", stamped, painted, millis,
                  pending.size());
        }
        if (directory.isEmpty() || !directoryDirty && dirty.isEmpty()) { return; }
        int required = directory.size() + tiles.size();
        if (texture == null || required > capacity) {
            // Geometric growth avoids reallocation on every impact. Empty board area consumes no image layers.
            required += waitingTiles();
            capacity = Math.min(maxLayers, Math.max(required, Math.max(directory.size(), capacity * 2)));
            var previous = texture;
            texture = new TextureArray(new TileData(capacity));
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            if (previous != null) { previous.dispose(); }
        } else {
            texture.bind();
            if (directoryDirty) {
                for (int i = 0; i < directory.size(); i++) { uploadTile(i, 0, directory.get(i)); }
            }
            for (var tile : dirty) { uploadImage(tile.layer(), tile.pixels()); }
        }
        directoryDirty = false;
        dirty.clear();
    }

    private static void uploadTile(int layer, int level, Pixmap pixels) {
        Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, 0, 0, layer, pixels.getWidth(), pixels.getHeight(), 1,
              GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels.getPixels());
    }

    /** Reuse one small scratch chain; a new hit must not rebuild every historical tile's mipmaps. */
    private void uploadImage(int layer, Pixmap pixels) {
        uploadTile(layer, 0, pixels);
        int level = 0;
        for (var next : mipScratch) {
            var source = pixels.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            var target = next.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            int width = pixels.getWidth();
            for (int y = 0; y < next.getHeight(); y++) {
                for (int x = 0; x < next.getWidth(); x++) {
                    int at = 2 * (y * width + x);
                    int a = source.get(at), b = source.get(at + 1), c = source.get(at + width), d = source.get(at + width + 1);
                    // Average four premultiplied texels, two independent byte channels at a time.
                    int ga = ((a & 0xff00ff) + (b & 0xff00ff) + (c & 0xff00ff) + (d & 0xff00ff) + 0x20002) >>> 2;
                    int rb = ((a >>> 8 & 0xff00ff) + (b >>> 8 & 0xff00ff) + (c >>> 8 & 0xff00ff) + (d >>> 8 & 0xff00ff) + 0x20002) >>> 2;
                    target.put((ga & 0xff00ff) | (rb & 0xff00ff) << 8);
                }
            }
            uploadTile(layer, ++level, next);
            pixels = next;
        }
    }

    private final class TileData implements TextureArrayData {
        private final int layers;
        private boolean prepared;
        TileData(int layers) { this.layers = layers; }
        @Override public boolean isPrepared() { return prepared; }
        @Override public void prepare() { prepared = true; }
        @Override public int getWidth() { return TILE_SIZE; }
        @Override public int getHeight() { return TILE_SIZE; }
        @Override public int getDepth() { return layers; }
        @Override public boolean isManaged() { return false; }
        @Override public int getInternalFormat() { return GL20.GL_RGBA; }
        @Override public int getGLType() { return GL20.GL_UNSIGNED_BYTE; }
        @Override public void consumeTextureArrayData() {
            if (mipScratch.isEmpty()) {
                for (int size = TILE_SIZE / 2; size > 0; size /= 2) { mipScratch.add(blank(size)); }
            }
            // TextureArray allocates level zero; allocate the remaining levels once per capacity change.
            for (int level = 1; level <= mipScratch.size(); level++) {
                int size = TILE_SIZE >> level;
                Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, GL20.GL_RGBA, size, size, layers, 0,
                      GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, null);
            }
            if (directory.isEmpty()) {
                var empty = blank();
                try { uploadImage(0, empty); } finally { empty.dispose(); }
            }
            for (int i = 0; i < directory.size(); i++) { uploadTile(i, 0, directory.get(i)); }
            tiles.values().forEach(tile -> uploadImage(tile.layer(), tile.pixels()));
            prepared = false;
        }
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); }
        tiles.values().forEach(tile -> tile.pixels().dispose());
        tiles.clear();
        directory.forEach(Pixmap::dispose);
        directory.clear();
        mipScratch.forEach(Pixmap::dispose);
        mipScratch.clear();
        dirty.clear();
        brushes.values().forEach(art -> art.levels().forEach(Pixmap::dispose));
        brushes.clear();
        texture = null; capacity = 0; seen.clear(); marks = 0; scorched = false;
        scorchRevision++;
        authored = new byte[0]; authoredTiles = null; authoredAny = false;
        nextSurfaceKey = -1;
        pending.clear(); resumeTile = 0; groundScene = null;
        worldWidth = 0; worldHeight = 0; columns = 0; rows = 0;
    }
}
