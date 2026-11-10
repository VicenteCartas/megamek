/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
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
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/**
 * Grass blades over finished terrain. Terrain workers plant each full-detail hex's roots with its chunk
 * ({@link #plant}); here every chunk keeps one persistent instance buffer of those roots and draws the prefix by
 * rank that each hex's projected size needs, so zooming and panning upload nothing. GL ownership stays here.
 */
final class GpuGroundCover implements Disposable {
    static final class Wind extends FloatAttribute {
        static final long TYPE = register("boardVegetationWind");
        Wind() { super(TYPE, 1); }
        @Override
        public Wind copy() { return new Wind(); }
    }

    static final int ROOTS_PER_HEX = 4096;
    private static final int STRIDE = 4;
    /** Projected pixels per hex over which grass blades grow in. */
    static final float START_PIXELS = 120, FULL_PIXELS = 500;
    /**
     * Projected pixels per hex over which a bank rim's blades stand up, a little before the meadow's first blades, and
     * below which fewer of them stand, each widening by as much, so that the rim keeps its fringe (terrain-grass.glsl).
     */
    static final float RIM_START_PIXELS = 100, RIM_STAND_PIXELS = 140, RIM_KEEP_PIXELS = 400;
    /** Rim blade candidates per metre of each contour, before filtering: half behind its lip, half down it. */
    private static final float RIM_PER_METRE = 48;
    /** Metres out from a rim root at which the ground under its blade is sampled ({@link #lean}). */
    private static final float[] RIM_REACH = { .5f, 1, 1.5f, 2 };
    /**
     * The blade templates, coarse and fine: each a list of ribbons, each its part (terrain-grass.glsl) and its vertex
     * pairs as fractions of its length, its tip closing it. Grass is one blade at two or four segments; wheat a stalk
     * with its ear between vertices at .80 and .96 (terrain-crops.glsl's WHEAT_EAR falls just inside them) and the awns
     * at the tip; maize its stalk and tassel (part 2) and six leaves alternating up it (parts 3 to 8), each a single
     * segment in the coarse template, so a plant keeps its leaves across the switch.
     */
    private static final float[][][] GRASS = { { { 0, 0, .5f } }, { { 0, 0, .25f, .5f, .75f } } };
    /** A bank rim's blade (part 9): grass that arches out over the lip and hangs, at three or six segments. */
    private static final float[][][] RIM = { { { 9, 0, .35f, .7f } }, { { 9, 0, .18f, .36f, .54f, .72f, .88f } } };
    /** A tundra tussock's blade (part 10): grass arching out from its tuft's centre, at two or four segments. */
    private static final float[][][] TUSSOCK = { { { 10, 0, .5f } }, { { 10, 0, .25f, .5f, .75f } } };
    /** The first candidates of a hex that may seed a tussock, the blades of one, and the metres between two. */
    private static final int TUSSOCK_SEEDS = 32, TUSSOCK_BLADES = 112;
    private static final float TUSSOCK_SPACING = 1.6f;
    private static final float[][][] WHEAT = { { { 1, 0, .8f, .96f } }, { { 1, 0, .45f, .8f, .83f, .96f } } };
    private static final float[][][] MAIZE = {
          { { 2, 0, .55f, .9f }, { 3, 0, .5f }, { 4, 0, .5f }, { 5, 0, .5f }, { 6, 0, .5f }, { 7, 0, .5f }, { 8, 0, .5f } },
          { { 2, 0, .3f, .6f, .9f }, { 3, 0, .3f, .6f, .85f }, { 4, 0, .3f, .6f, .85f }, { 5, 0, .3f, .6f, .85f },
                { 6, 0, .3f, .6f, .85f }, { 7, 0, .3f, .6f, .85f }, { 8, 0, .3f, .6f, .85f } } };
    /** The tallest standing blade, as a fraction of the hex width; shared with cosmetic shrub sizing. */
    static final float MAX_HEIGHT_FRACTION = .07f;
    /** Metres frozen land's jagged border reaches past its hex edge at most: the shard offset, lean and splinters. */
    private static final float ICE_BORDER_REACH = 5.2f;
    /** Land-side metres over which standing grass thins to the bare bank. */
    private static final float SHORE_FADE_METRES = 3;
    /** Metres beyond a bridge landing's solid asphalt over which standing grass thickens to the full meadow. */
    private static final float LANDING_FADE_METRES = 5;

    /** Whether a hex's tundra grows its grass sward and its tussocks: earth's carpets. */
    static boolean sward(BoardScene.Tile tile) {
        return BoardBiome.kind(tile) == BoardScene.Biome.TUNDRA && BoardSurfaceBlend.natural(tile)
              && BoardBiome.earthTundra(tile.surface());
    }

    /**
     * Whether a hex grows grass: natural grass ground or earth tundra, or its neighbour within a level of such a hex,
     * which their cover reaches into.
     */
    static boolean grows(BoardScene scene, BoardScene.Tile tile) {
        if (sward(tile)) { return true; }
        boolean shallow = shallowShore(tile);
        if ((!BoardSurfaceBlend.natural(tile) && !shallow) || BoardBiome.kind(tile) != BoardScene.Biome.NONE) { return false; }
        if (shallow && BoardBiome.plantKind(scene, tile) != BoardScene.Biome.NONE) { return false; }
        if (!shallow && tile.surface() == BoardScene.Surface.GRASS) { return true; }
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.tile(tile.coords().translated(direction));
            if (BoardSurfaceBlend.natural(neighbor) && (neighbor.surface() == BoardScene.Surface.GRASS || sward(neighbor))
                  && Math.abs(neighbor.elevation() - tile.elevation()) <= 1) { return true; }
        }
        return false;
    }

    /**
     * A built deck's road runs on past the deck's end onto a bare bank as paint ({@link GpuRoads#deckPatches}): solid
     * asphalt as far as these courses reach, which no blade grows through, then an apron fading into the meadow.
     */
    private static List<BoardRoad> deckLandings(BoardScene scene, BoardScene.Tile tile) {
        List<BoardRoad> result = new ArrayList<>(0);
        for (int direction = 0; direction < 6; direction++) {
            var span = scene.neighbor(tile, direction);
            int exit = (direction + 3) % 6;
            if (BoardBridge.feature(span) == null || !BoardBridge.bank(span, tile, exit) || BoardBridge.road(span, tile, exit)) {
                continue;
            }
            var deck = BoardBridge.deck(scene, span);
            if (deck.natural() || (deck.roadExits() & 1 << exit) == 0) { continue; }
            var footing = BoardBridgeFooting.build(scene, span, TerrainLod.FULL, new HashMap<>()).paint(deck);
            if ((footing.bareExits() & 1 << exit) == 0) { continue; }
            var reaches = new ArrayList<>(footing.lengths());
            reaches.set(exit, reaches.get(exit) + GpuRoads.landingSetback(deck));
            result.add(deck.road(span.coords()).extended(reaches, footing.bareExits()));
        }
        return result;
    }

    /**
     * How much grass a road leaves a root (world x, y), 0 to 1: none on a paved road's way, shoulder and {@code margin}
     * tile pixels; a loose road's thins in towards its stone or its ruts (BoardRoad#grass).
     */
    private static float roadside(BoardScene scene, BoardRoad road, float x, float y, float margin) {
        float rx = (x - BoardGeometry.centerX(road.coords())) / BoardGeometry.hexScale();
        float ry = (y - BoardGeometry.centerY(road.coords())) / BoardGeometry.hexScale();
        var tile = scene.tile(road.coords());
        if (tile != null && (tile.road() == BoardRoad.Kind.DIRT || tile.road() == BoardRoad.Kind.GRAVEL)) {
            return road.grass(tile.road(), rx, ry);
        }
        return road.distance(rx, ry) < BoardRoad.SHOULDER + margin ? 0 : 1;
    }

    /** Only exposed ordinary depth-zero banks can extend the adjoining meadow into a water hex. */
    static boolean shallowShore(BoardScene.Tile tile) {
        return tile != null && tile.waterDepth() == 0 && tile.liquid().present() && !tile.liquid().volcanic()
              && !tile.frozen() && !tile.ultraSublevel() && !tile.backdrop().present() && tile.detailedGround()
              && !tile.slab() && (tile.roadExits() == 0 || BoardRoad.rendered(tile));
    }

    /**
     * The share of tundra at a point that is earth's and grows grass: its carpets where no pavement lies, clear of the
     * Snow theme's remnants (terrain-tundra.glsl draws its ground by the same shares). The sward grows on it between
     * its own openings ({@link BoardBiome#sward}), the tussocks anywhere.
     */
    private static float earthShare(BoardSurfaceBlend.Cover cover, float xMetres, float yMetres) {
        float earth = cover.grass() + cover.dirt() + cover.sand() + cover.rock() + cover.snow() + cover.desert()
              + cover.tropical() + cover.concrete();
        float total = earth + cover.lunar() + cover.fungus() + cover.mars() + cover.volcano();
        if (earth <= 0) { return 0; }
        float remnant = cover.snow() <= 0 ? 0 : cover.snow() / total
              * BoardRelief.smooth((BoardRelief.noise(xMetres / 8 + 29, yMetres / 8 + 29) - .55f) / .17f);
        return earth / total * (1 - cover.concrete() / total) * (1 - remnant);
    }

    /** The hex's grass alone: its tussocks ({@link #tufts}) grow only for a caller that collects them. */
    static FloatArray plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface,
          BoardDecals.Opacity paint) {
        return plant(scene, tile, surface, paint, null);
    }

    /**
     * Deterministic roots over the hex, filtered by the existing surface and road rules, as (x, y, z, rank) in rank
     * order. Earth tundra's sward grows from the meadow's candidates, where the tundra reaches. A rank's fraction packs
     * twelve bits (terrain-grass.glsl): the half that marks a sward's root, and the root's ground's slope
     * ({@link #slopeCode}). Tundra's tussocks grow into {@code tussocks}, when given ({@link #tufts}).
     * Candidates are drawn in the hex's plane and dropped onto the finished ground, so a root keeps its place
     * and rank at every terrain detail level and only its height follows the installed ground: a medium-detail
     * chunk's grass carries over to its full-detail replacement without a blade moving. Density selects a prefix by
     * rank, so zooming never moves an existing blade either. No blade grows through {@code paint} (null when nothing
     * paints the hex): adding or removing a decal keeps every other root's place and rank.
     */
    static FloatArray plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface,
          BoardDecals.Opacity paint, FloatArray tussocks) {
        var vegetation = surface.vegetation();
        List<BoardSurface.Face> ground;
        Map<Vector3, Vector3> normals;
        Map<Vector3, Float> waterHeights = vegetation == null ? Map.of() : vegetation.waterHeights();
        if (vegetation != null) {
            ground = vegetation.faces();
            normals = vegetation.normals();
        } else {
            ground = new ArrayList<>(surface.top().stream()
                  .filter(face -> face.finish() == BoardSurface.Finish.TOP).toList());
            if (BoardGeometry.tuning().stepsBetweenTops()) { ground.addAll(surface.slopes()); }
            ground.removeIf(face -> BoardSurface.bridgeSupport(scene, tile, face));
            normals = BoardSurface.vertexNormals(ground);
        }
        var roots = new FloatArray();
        if (ground.isEmpty()) { return roots; }
        var support = new GpuBiomeVegetation.Support(ground);
        var water = vegetation == null || vegetation.water().isEmpty() ? null
              : new GpuBiomeVegetation.Support(vegetation.water());
        Coords coords = tile.coords();
        var coverage = new BoardBiome.Coverage(scene, coords);
        // Level tundra all around is covered whole on its level: no query per candidate.
        boolean tundraAround = BoardVegetation.uniform(scene, tile, BoardScene.Biome.TUNDRA);
        float levelZ = BoardGeometry.groundZ(tile);
        // Where a tussock may stand, as (x, y), and the ground under each.
        var seeds = new FloatArray();
        List<BoardSurface.Face> seedGround = new ArrayList<>();
        float centerX = BoardGeometry.centerX(coords), centerY = BoardGeometry.centerY(coords);
        float halfWidth = BoardGeometry.width() / 2, halfHeight = BoardGeometry.height() / 2;
        var random = new Random(coords.getX() * 0x9E3779B97F4A7C15L ^ coords.getY() * 0xC2B2AE3D27D4EB4FL);
        boolean boundary = shallowShore(tile) || BoardSurfaceBlend.boundary(scene, tile);
        boolean tundraEdge = BoardBiome.kind(tile) == BoardScene.Biome.TUNDRA;
        boolean marshEdge = false;
        for (int direction = 0; direction < 6; direction++) {
            tundraEdge |= BoardBiome.kind(scene.neighbor(tile, direction)) == BoardScene.Biome.TUNDRA;
            marshEdge |= BoardBiome.kind(scene.neighbor(tile, direction)) == BoardScene.Biome.MARSH;
        }
        var roads = BoardRoad.nearbyRoads(scene, tile);
        var landings = deckLandings(scene, tile);
        float landingFade = BoardRelief.metres(LANDING_FADE_METRES) / BoardGeometry.hexScale();
        float sink = BoardGeometry.width() * .001f, metre = BoardRelief.metres(1);
        // Frozen land's jagged border reaches across a shared edge (terrain-ice.glsl); no blade grows through it.
        List<Vector3[]> iceEdges = new ArrayList<>();
        List<Vector3[]> shoreEdges = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            var neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            if (neighbor != null && neighbor.frozen() && !neighbor.liquid().present()
                  && neighbor.elevation() == tile.elevation()) {
                iceEdges.add(new Vector3[] { BoardGeometry.corner(tile.coords(), 0, edge),
                      BoardGeometry.corner(tile.coords(), 0, edge + 1) });
            }
            if (neighbor != null && neighbor.liquid().present() && !neighbor.liquid().volcanic() && !neighbor.frozen()
                  && neighbor.waterDepth() > 0 && neighbor.elevation() == tile.elevation()) {
                shoreEdges.add(new Vector3[] { BoardGeometry.corner(tile.coords(), 0, edge),
                      BoardGeometry.corner(tile.coords(), 0, edge + 1) });
            }
        }
        float iceReach = BoardRelief.metres(ICE_BORDER_REACH);
        float shoreReach = BoardRelief.metres(SHORE_FADE_METRES);
        var obstacles = new BoardObstacles(scene, tile, ground, MAX_HEIGHT_FRACTION * BoardGeometry.width(),
              2 * BoardGeometry.hexScale());
        List<BoardScene.Feature> minerals = vegetation == null ? List.of() : vegetation.minerals();
        var cover = new BoardSurfaceBlend.Sampler(scene, tile);
        var root = new Vector3();
        var normal = new Vector3();
        for (int rank = 0; rank < ROOTS_PER_HEX; rank++) {
            // The same random sequence at every detail level: every candidate draws its place and its growth chance
            // before any ground-dependent rejection.
            float x, y;
            do {
                x = centerX + (random.nextFloat() * 2 - 1) * halfWidth;
                y = centerY + (random.nextFloat() * 2 - 1) * halfHeight;
            } while (!BoardGeometry.contains(coords, x, y));
            float chance = random.nextFloat();
            var face = support.face(x, y);
            if (face == null) { continue; }
            float z = face.height(x, y);
            // A descending stream and its bed can have different diagonals. Its actual surface, not interpolated
            // bank shading metadata, decides whether a root is submerged.
            if (water != null && z - sink <= water.height(x, y)) { continue; }
            // Test the actual authored triangles: geyser basins/rims and other
            // scenery leave the surrounding grass and empty gaps untouched.
            if (!obstacles.isEmpty() && obstacles.obstructs(root.set(x, y, z), 2 * BoardGeometry.hexScale(),
                  MAX_HEIGHT_FRACTION * BoardGeometry.width())) { continue; }
            float verge = 1;
            for (int r = 0; r < roads.size() && verge > 0; r++) { verge = Math.min(verge, roadside(scene, roads.get(r), x, y, 1)); }
            if (verge <= 0) { continue; }
            boolean iced = false;
            for (Vector3[] edge : iceEdges) {
                float ex = edge[1].x - edge[0].x, ey = edge[1].y - edge[0].y;
                float t = Math.clamp(((x - edge[0].x) * ex + (y - edge[0].y) * ey) / (ex * ex + ey * ey), 0, 1);
                iced |= Math.hypot(x - edge[0].x - t * ex, y - edge[0].y - t * ey) < iceReach;
            }
            if (iced) { continue; }
            var mixture = boundary ? cover.sample(x, y, z) : BoardSurfaceBlend.solid(tile.surface());
            float grass = boundary ? BoardSurfaceBlend.grass(tile, mixture, x, y)
                  : tile.surface() == BoardScene.Surface.GRASS ? 1 : 0;
            float earth = 0;
            if (tundraEdge) {
                float tundra = tundraAround && Math.abs(z - levelZ) <= .15f * metre ? 1
                      : coverage.of(BoardScene.Biome.TUNDRA, x, y, z);
                grass *= 1 - tundra;
                earth = tundra * earthShare(mixture, x / metre, y / metre);
            }
            // Marsh, paint and mineral soil leave either kind as little.
            float clear = 1;
            if (marshEdge && waterHeights.containsKey(face.a())) {
                clear *= 1 - coverage.of(BoardScene.Biome.MARSH, x, y, z);
            }
            if (paint != null && grass + earth > 0) { clear *= 1 - paint.at(x, y); }
            if (!minerals.isEmpty()) {
                clear *= 1 - GpuMineralGround.coverage(minerals, (x - centerX) / BoardGeometry.hexScale(),
                      (y - centerY) / BoardGeometry.hexScale());
            }
            grass *= clear;
            earth *= clear;
            // Follow the same smoothly varying normals as the turf material, including the next hex's slope toe.
            // A whole-triangle cutoff removes green ground and leaves straight, full-density blade edges.
            float slope = grass + earth > 0 ? groundGrowth(face, normals, waterHeights, x, y, z, normal) : 0;
            if (tussocks != null && rank < TUSSOCK_SEEDS && earth * slope > .5f) {
                seeds.addAll(x, y);
                seedGround.add(face);
            }
            float sward = earth > 0 ? earth * BoardBiome.sward(x / metre, y / metre) : 0;
            float growth = grass * BoardRelief.smooth((grass - .55f) / .35f) * slope;
            // The sward thins out into its openings. It takes the candidates the meadow leaves: the far end of chance.
            float swardGrowth = sward * BoardRelief.smooth(sward / .6f) * slope;
            boolean meadow = growth > 0 && chance <= growth;
            if (!meadow && chance <= 1 - swardGrowth) { continue; }
            float fade = 1;
            if (!shoreEdges.isEmpty()) {
                float distance = shoreReach;
                for (Vector3[] edge : shoreEdges) {
                    distance = Math.min(distance, BoardRelief.distance(x, y, edge[0].x, edge[0].y, edge[1].x, edge[1].y));
                }
                // Thin the accepted roots, not the cover input: its grass threshold would turn this fade into a cut.
                fade *= BoardRelief.smooth(distance / shoreReach);
            }
            // Beside a dirt track the meadow thins out towards its ruts.
            fade *= verge;
            if (!landings.isEmpty()) {
                // None on a landing's solid asphalt; the meadow thickens again across its fading apron.
                fade *= BoardRelief.smooth((BoardRoad.distance(landings, x, y) - BoardRoad.SHOULDER - 1) / landingFade);
            }
            if (meadow ? chance <= growth * fade : chance > 1 - swardGrowth * fade) {
                roots.addAll(x, y, z - sink, rank + ((meadow ? 0 : 2048) | slopeCode(normal)) / 4096f);
            }
        }
        roots.shrink();
        if (tussocks != null) { tufts(coords, seeds, seedGround, sink, tussocks); }
        return roots;
    }

    /**
     * Earth tundra's tussocks: tufts of its sward's grass, standing taller, in loose clusters that run on across hex
     * edges, {@link #TUSSOCK_SPACING} metres apart or more. Each grows {@link #TUSSOCK_BLADES} blades out of a disc some
     * .9 to 1.8 metres across, most near its centre, as (x, y, z, rank): a blade of every tuft in turn, their ranks
     * spread over {@link #ROOTS_PER_HEX}, so that a prefix by rank thins every tuft alike. A rank's fraction packs
     * twelve bits (terrain-grass.glsl): the tuft's size in eight steps, the blade's distance from the centre in eight,
     * which leans it out, and its heading out from the centre in 64.
     */
    private static void tufts(Coords coords, FloatArray seeds, List<BoardSurface.Face> ground, float sink,
          FloatArray blades) {
        float metre = BoardRelief.metres(1);
        var random = new Random(coords.getX() * 0x632BE59BD9B4E019L ^ coords.getY() * 0x85157AF5L ^ 0x7155L);
        // Each tuft's x, y, size and seed.
        var tufts = new FloatArray();
        for (int seed = 0; seed < seeds.size / 2; seed++) {
            float x = seeds.items[seed * 2], y = seeds.items[seed * 2 + 1], size = random.nextFloat();
            boolean crowded = BoardRelief.noise(x / metre / 5 + 31, y / metre / 5 - 17) < .45f;
            for (int other = 0; other < tufts.size && !crowded; other += 4) {
                crowded = Math.hypot(x - tufts.items[other], y - tufts.items[other + 1]) < TUSSOCK_SPACING * metre;
            }
            if (!crowded) { tufts.addAll(x, y, size, seed); }
        }
        int total = tufts.size / 4 * TUSSOCK_BLADES;
        for (int blade = 0; blade < total; blade++) {
            int tuft = blade % (tufts.size / 4) * 4;
            float size = tufts.items[tuft + 2], distance = random.nextFloat(), heading = random.nextFloat();
            float reach = distance * (.45f + .45f * size) * metre;
            float x = tufts.items[tuft] + reach * (float) Math.cos(heading * 2 * Math.PI);
            float y = tufts.items[tuft + 1] + reach * (float) Math.sin(heading * 2 * Math.PI);
            int code = Math.round(size * 7) << 9 | Math.round(distance * 7) << 6 | Math.min((int) (heading * 64), 63);
            blades.addAll(x, y, ground.get((int) tufts.items[tuft + 3]).height(x, y) - sink,
                  (int) ((long) blade * ROOTS_PER_HEX / total) + code / 4096f);
        }
        blades.shrink();
    }

    /**
     * A root's ground's slope, by which its blades are lit and which a sward's lie along (terrain-grass.glsl), in the
     * low seven of its rank's twelve fraction bits: the rise per metre in eight steps of .2 and the uphill heading in
     * sixteen. A rank below {@link #ROOTS_PER_HEX} keeps the twelve exactly, like a rim root's.
     */
    private static int slopeCode(Vector3 normal) {
        int rise = Math.min(7, Math.round((float) Math.sqrt(normal.x * normal.x + normal.y * normal.y)
              / Math.max(normal.z, .01f) / .2f));
        // Level ground, most of a board, has no heading.
        if (rise == 0) { return 0; }
        int heading = Math.floorMod(Math.round((float) (Math.atan2(-normal.y, -normal.x) / (2 * Math.PI) * 16)), 16);
        return rise << 4 | heading;
    }

    /** Interpolate the rendered normal and water reference on the actual support; allocate nothing per root. */
    private static float groundGrowth(BoardSurface.Face face, Map<Vector3, Vector3> normals,
          Map<Vector3, Float> waterHeights, float x, float y, float z, Vector3 normal) {
        Vector3 a = face.a(), b = face.b(), c = face.c();
        float area = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
        float u = Math.max(0, ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / area);
        float v = Math.max(0, ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / area);
        float w = Math.max(0, 1 - u - v);
        float up = normal.set(normals.get(a)).scl(u).mulAdd(normals.get(b), v).mulAdd(normals.get(c), w).nor().z;
        float growth = BoardRelief.smooth((up - .6f) / .35f);
        // A wall is never wet ground (GpuTerrain#sculptVertex): water standing above land leaves its outer slope dry.
        boolean wall = face.finish() == BoardSurface.Finish.WALL || face.finish() == BoardSurface.Finish.CAP;
        if (!wall && waterHeights.containsKey(a)) {
            float water = (waterHeights.get(a) * u + waterHeights.get(b) * v + waterHeights.get(c) * w) / (u + v + w);
            // Keep a bare wet toe, then restore the meadow over the exposed shallow bank.
            float above = (z - water) / BoardGeometry.hexScale();
            growth *= BoardRelief.smooth((above - .18f) / .52f);
        }
        return growth;
    }

    /**
     * Blades along a grassy bank's rims, where the hex drops two levels to dry ground: roots on its upper lip and its
     * middle shoulder's, from a couple of metres behind the lip, thinning into the meadow there, down the rounded lip.
     * Each leans out as far as the ground falls away under its reach ({@link #lean}): standing behind the lip, arching
     * over it, hanging down the face, never into the ground or into a higher neighbour at a junction. Like the meadow's,
     * every candidate draws its place and chance before any rejection, its rank spread over {@link #ROOTS_PER_HEX} so
     * that density selects a prefix; the rank's fraction packs the root's contour, lean and outward heading in twelve
     * bits (terrain-grass.glsl). Null when the hex has no such rim.
     */
    static FloatArray plantRim(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface surface,
          BoardDecals.Opacity paint) {
        if (!BoardGeometry.tuning().stepsBetweenTops() || !BoardSurfaceBlend.natural(tile)
              || tile.surface() != BoardScene.Surface.GRASS || BoardBiome.kind(tile) != BoardScene.Biome.NONE) { return null; }
        var center = BoardGeometry.center(tile.coords(), tile.elevation());
        // Each rim's edge corners and its outward direction.
        List<Vector3[]> rims = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            int direction = BoardGeometry.edgeDirection(edge);
            var lower = scene.tile(tile.coords().translated(direction));
            if (lower == null || lower.liquid().present() || tile.elevation() - lower.elevation() != 2
                  || (tile.cliffTopExits() & (1 << direction)) != 0) { continue; }
            var a = BoardGeometry.corner(tile.coords(), 0, edge);
            var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
            var along = b.cpy().sub(a).nor();
            var outward = new Vector3(along.y, -along.x, 0);
            if (outward.dot(a.cpy().sub(center)) < 0) { outward.scl(-1); }
            rims.add(new Vector3[] { a, b, outward });
        }
        if (rims.isEmpty()) { return null; }
        List<BoardSurface.Face> faces = new ArrayList<>(surface.top());
        faces.addAll(surface.walls());
        faces.removeIf(face -> BoardSurface.bridgeSupport(scene, tile, face));
        var support = new GpuBiomeVegetation.Support(faces);
        var obstacles = new BoardObstacles(scene, tile, faces, MAX_HEIGHT_FRACTION * BoardGeometry.width(),
              2 * BoardRelief.metres(1));
        // Rim blades grow up to a ground rail's ballast (BoardObstacles); only a road keeps its verge bare.
        var road = BoardRoad.rendered(tile) ? BoardRoad.way(scene, tile) : null;
        boolean boundary = BoardSurfaceBlend.boundary(scene, tile);
        var sampler = new BoardSurfaceBlend.Sampler(scene, tile);
        float m = BoardRelief.metres(1), level = BoardGeometry.level(), sink = BoardGeometry.width() * .001f;
        Vector3[] first = rims.getFirst();
        int candidates = Math.min(ROOTS_PER_HEX, Math.round(rims.size() * 2 * first[0].dst(first[1]) / m * RIM_PER_METRE));
        var random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL
              ^ 0x6A09E667F3BCC909L);
        var roots = new FloatArray();
        for (int candidate = 0; candidate < candidates; candidate++) {
            Vector3[] rim = rims.get(random.nextInt(rims.size()));
            // The lip, or the middle shoulder's a level lower.
            int shoulder = random.nextInt(2);
            var point = rim[0].cpy().lerp(rim[1], random.nextFloat());
            // Below 0 behind the lip, up to two metres on the top and one on the narrower shoulder; above 0 from just
            // under its top down the rounded lip, most where it rounds over, whose turf the blades cover.
            float place = random.nextFloat() * 2 - 1, chance = random.nextFloat(), down = Math.max(place, 0);
            float z = center.z - (shoulder + .02f + .26f * down * down
                  + .03f * BoardRelief.gradient(point.x / (4 * m), point.y / (4 * m))) * level;
            var root = contour(support, point, rim[2], z);
            if (root == null) { continue; }
            // Lean down the local slope, through rounded corners, rather than square to the hex edge.
            float dx = support.height(root.x - m, root.y) - support.height(root.x + m, root.y);
            float dy = support.height(root.x, root.y - m) - support.height(root.x, root.y + m);
            var facing = new Vector3(dx, dy, 0);
            if (!Float.isFinite(dx + dy) || facing.len2() < .001f || facing.dot(rim[2]) <= 0) { facing.set(rim[2]); }
            facing.nor();
            if (place < 0) {
                root.mulAdd(facing, place * (2 - shoulder) * m);
                root.z = support.top(root.x, root.y);
                if (Float.isNaN(root.z)) { continue; }
            }
            float lean = lean(support, root, facing, m);
            if (Float.isNaN(lean) || obstacles.obstructs(root, 2 * m, MAX_HEIGHT_FRACTION * BoardGeometry.width())) {
                continue;
            }
            float verge = road == null ? 1 : roadside(scene, road, root.x, root.y, 2);
            if (verge <= 0) { continue; }
            var cover = boundary ? sampler.sampleCliff(root.x, root.y, root.z)
                  : BoardSurfaceBlend.solid(BoardScene.Surface.GRASS);
            float grass = cover.grass();
            if (paint != null && grass > 0) { grass *= 1 - paint.at(root.x, root.y); }
            // Behind the lip the rim's blades thin into the meadow's.
            if (chance > grass * BoardRelief.smooth((grass - .55f) / .35f) * (1 + Math.min(place, 0)) * verge) { continue; }
            float heading = (float) (Math.atan2(facing.y, facing.x) / (2 * Math.PI));
            heading -= (float) Math.floor(heading);
            // The contour, eight lean steps and 256 headings: twelve bits, which a rank below 4096 keeps exactly.
            int code = shoulder << 11 | Math.round(lean * 7) << 8 | Math.min((int) (heading * 256), 255);
            roots.addAll(root.x, root.y, root.z - sink,
                  (int) ((long) candidate * ROOTS_PER_HEX / candidates) + code / 4096f);
        }
        if (roots.isEmpty()) { return null; }
        roots.shrink();
        return roots;
    }

    /** Intersect a horizontal cross-section with the actual finished triangles, in the outward direction. */
    private static Vector3 contour(GpuBiomeVegetation.Support support, Vector3 point, Vector3 outward, float z) {
        float reach = BoardGeometry.width() * .45f;
        float previous = Float.NaN, from = -reach;
        for (int step = 0; step <= 48; step++) {
            float at = -reach + 2 * reach * step / 48;
            var sample = point.cpy().mulAdd(outward, at);
            float height = support.top(sample.x, sample.y);
            if (previous >= z && height < z) {
                float to = at;
                for (int iteration = 0; iteration < 12; iteration++) {
                    float middle = (from + to) * .5f;
                    sample.set(point).mulAdd(outward, middle);
                    float middleHeight = support.top(sample.x, sample.y);
                    if (Float.isNaN(middleHeight)) { return null; }
                    if (middleHeight >= z) { from = middle; } else { to = middle; }
                }
                sample.set(point).mulAdd(outward, (from + to) * .5f);
                sample.z = z;
                return sample;
            }
            previous = height;
            from = at;
        }
        return null;
    }

    /**
     * How far a rim blade from {@code root} may lean out along {@code facing}, from 0, standing, to 1, hanging: the
     * least fall of the ground under its free end, a metre to two out, as a share of .45 of the distance. NaN where the
     * ground rises ahead, as at a junction with a higher neighbour, or the root's own support ends within a metre;
     * farther out, beyond its hex's triangles, the ground is taken to fall away.
     */
    private static float lean(GpuBiomeVegetation.Support support, Vector3 root, Vector3 facing, float m) {
        float lean = 1;
        for (float metres : RIM_REACH) {
            float x = root.x + facing.x * metres * m, y = root.y + facing.y * metres * m;
            float ground = support.top(x, y);
            if (Float.isNaN(ground)) {
                if (metres <= 1) { return Float.NaN; }
                continue;
            }
            float fall = root.z - ground;
            if (fall < -.1f * m) { return Float.NaN; }
            if (metres >= 1) { lean = Math.min(lean, fall / (.45f * metres * m)); }
        }
        return Math.max(lean, 0);
    }

    /** One blade template's instanced draw for a chunk. Its buffer persists while the chunk's roots are unchanged. */
    private static final class Batch implements Disposable {
        final float[][] ribbons;
        // Roots are replaced, never edited in place: identity, then contents, identify unchanged hexes.
        final List<FloatArray> current = new ArrayList<>(), previous = new ArrayList<>();
        final FloatArray data = new FloatArray();
        // Where each hex's roots start in the uploaded data, when every hex draws its own range.
        final IntArray starts = new IntArray(), ranges = new IntArray();
        GpuInstancedMesh mesh;
        ModelInstance instance;
        int capacity;
        long uploads;

        Batch(float[][] ribbons) { this.ribbons = ribbons; }

        void begin() { current.clear(); }

        void add(FloatArray roots) { current.add(roots); }

        /** Upload changed roots, then draw the prefix by rank that each hex's target needs, one range per hex. */
        ModelInstance upload(IntArray targets) {
            if (current.isEmpty()) { previous.clear(); return null; }
            // A chunk prepared again on equal ground plants equal roots: those need no upload either. Remember the
            // new arrays either way, so an equal replant is compared once rather than every frame.
            boolean changed = current.size() != previous.size();
            for (int i = 0; !changed && i < current.size(); i++) {
                changed = current.get(i) != previous.get(i) && !current.get(i).equals(previous.get(i));
            }
            previous.clear(); previous.addAll(current);
            if (changed) {
                concatenate();
                if (data.size == 0) { return null; }
                if (mesh == null) { build(); }
                int count = data.size / STRIDE;
                if (count > capacity) {
                    if (capacity > 0) { mesh.disableInstancedRendering(); }
                    capacity = Math.max(count, Math.max(256, capacity * 2));
                    mesh.enableInstancedRendering(false, capacity, instanceAttribute());
                }
                mesh.setInstanceData(data.items, 0, data.size);
                uploads++;
            }
            if (data.size == 0) { return null; }
            int drawn = 0;
            ranges.clear();
            for (int i = 0; i < current.size(); i++) {
                int count = prefix(starts.get(i), starts.get(i + 1), targets.get(i));
                ranges.add(starts.get(i) / STRIDE, count);
                drawn += count;
            }
            mesh.drawRanges(ranges);
            return drawn == 0 ? null : instance;
        }

        /** Instances within the rank-ordered data range [from, to), in floats, whose rank is below the target. */
        private int prefix(int from, int to, int target) {
            int low = from / STRIDE, high = to / STRIDE;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (data.items[middle * STRIDE + 3] < target) { low = middle + 1; } else { high = middle; }
            }
            return low - from / STRIDE;
        }

        /** Hex after hex, each in rank order: every hex's prefix is one contiguous range. */
        private void concatenate() {
            data.clear();
            starts.clear();
            for (FloatArray roots : current) {
                starts.add(data.size);
                data.addAll(roots);
            }
            starts.add(data.size);
        }

        private void build() {
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
        public void dispose() {
            if (instance != null) { instance.model.dispose(); }
            mesh = null; instance = null; capacity = 0;
            begin(); previous.clear(); data.clear(); starts.clear();
        }
    }

    private static VertexAttribute instanceAttribute() {
        return new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_coverRoot");
    }

    /** Prepare the actual blade layout without planting a full-detail chunk for a hidden close-up. */
    static void prepareMaterials(BiConsumer<Material, Mesh> consumer) {
        Batch sample = new Batch(GRASS[0]);
        try {
            sample.build();
            sample.mesh.enableInstancedRendering(true, 1, instanceAttribute());
            consumer.accept(sample.instance.materials.first(), sample.mesh);
        } finally { sample.dispose(); }
    }

    /**
     * One kind of blade in a chunk: its coarse and fine templates, one in use at a time, the projected pixels per hex
     * below which none stands, its planted hexes and, for each, the roots its projected size needs this frame (none out
     * of view).
     */
    private static final class Layer implements Disposable {
        final Batch[] batches;
        final float start;
        final List<FloatArray> roots = new ArrayList<>();
        final IntArray targets = new IntArray();

        Layer(float[][][] templates, float start) {
            batches = new Batch[] { new Batch(templates[0]), new Batch(templates[1]) };
            this.start = start;
        }

        void begin() { roots.clear(); targets.clear(); }

        void add(FloatArray planted, int target) {
            if (planted == null) { return; }
            roots.add(planted);
            targets.add(target);
        }

        @Override
        public void dispose() { for (Batch batch : batches) { batch.dispose(); } }
    }

    /** One terrain chunk's grass, its banks' rims, its tundra's tussocks and its fields' wheat and maize. */
    private static final class Chunk implements Disposable {
        final Layer grass = new Layer(GRASS, START_PIXELS);
        final Layer rim = new Layer(RIM, RIM_START_PIXELS);
        final Layer tussock = new Layer(TUSSOCK, RIM_START_PIXELS);
        final Layer wheat = new Layer(WHEAT, GpuBiomeVegetation.CROP_FADE[0]);
        final Layer maize = new Layer(MAIZE, GpuBiomeVegetation.CROP_FADE[0]);
        final Layer[] layers = { grass, rim, tussock, wheat, maize };
        float nearPixels;
        long frame;
        // Blades place themselves from their roots; the transform only orders chunks nearest first for early depth.
        final Vector3 centre;

        Chunk(Coords chunk) {
            centre = BoardGeometry.center(new Coords(chunk.getX() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2,
                  chunk.getY() * TerrainLod.CHUNK_SIZE + TerrainLod.CHUNK_SIZE / 2), 0);
        }

        void begin(long next) {
            frame = next;
            for (Layer layer : layers) { layer.begin(); }
            nearPixels = 0;
        }

        @Override
        public void dispose() { for (Layer layer : layers) { layer.dispose(); } }
    }

    private final Map<Coords, Chunk> chunks = new HashMap<>();
    // Whether the meadows' grass and their banks' rims draw (the Grass blades option); the fields' plants always do.
    private boolean grass = true;
    private long frame, uploads;
    private int revision = -1, boardId = -1;
    /** Render-owned submission snapshot; installed roots still invalidate a stationary view. */
    private record View(float[] projectionView, float viewportPixels, BoardQuality quality, List<BoardScene.Tile> candidates,
          Map<Coords, BoardPlants> sources, List<ModelInstance> instances) { }
    private View view;
    /** How long blades take to grow on a hex whose roots arrived while it was already on screen without any. */
    private static final long GROWTH_NANOS = 500_000_000L;
    // Roots that arrived late are grown in from their arrival time rather than appearing at once. Roots a chunk
    // brought while off screen are not late: the hex shows them complete when it comes into view.
    private final Map<Coords, Long> arrivals = new HashMap<>();

    static String vertex(String source) {
        String meadow = GpuShaderSource.read("surface-noise.glsl") + GpuShaderSource.read("terrain-meadow.glsl")
              + GpuShaderSource.read("terrain-tundra-sward.glsl");
        String wind = GpuShaderSource.read("terrain-vegetation-wind.glsl");
        String blade = "#define GRASS_START_PIXELS " + START_PIXELS + "\n#define GRASS_FULL_PIXELS " + FULL_PIXELS
              + "\n#define GRASS_RIM_START " + RIM_START_PIXELS + "\n#define GRASS_RIM_STAND " + RIM_STAND_PIXELS
              + "\n#define GRASS_RIM_KEEP " + RIM_KEEP_PIXELS
              + "\n#define GRASS_ROOTS_PER_HEX " + ROOTS_PER_HEX
              + "\n#define GRASS_MAX_HEIGHT " + MAX_HEIGHT_FRACTION + "\n" + GpuBiomeVegetation.cropSource()
              + GpuGroundDamage.shaderSource() + GpuShaderSource.read("terrain-grass.glsl");
        // A root beyond its density leaves every vertex of its blade outside the clip volume.
        return GpuShaderSource.coverVertex(source.replace("void main() {",
              wind + meadow + blade + "\nvoid main() {\nvec3 coverPosition, coverNormal; vec4 coverColor;\n"
                    + "if (!grassBlade(a_position, coverPosition, coverNormal, coverColor)) {\n"
                    + "gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }\n"));
    }

    private static float density(float pixels) {
        float t = Math.clamp((pixels - START_PIXELS) / (FULL_PIXELS - START_PIXELS), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** The share of a bank rim's blades standing at this projected size (terrain-grass.glsl). */
    private static float rimDensity(float pixels) {
        return Math.clamp(pixels / RIM_KEEP_PIXELS, 0, 1)
              * BoardRelief.smooth((pixels - RIM_START_PIXELS) / (RIM_STAND_PIXELS - RIM_START_PIXELS));
    }

    /**
     * Whether, with {@code grass}, grass or its banks' rims or, with {@code crops}, a field's plants can stand at this
     * scale. Orthographic detail has one scale everywhere; perspective detail still depends on each tile's depth.
     */
    static boolean visibleAtScale(Camera camera, boolean grass, boolean crops) {
        float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, camera.position);
        boolean perspective = camera.projection.val[Matrix4.M33] == 0;
        // A rim's blades stand before the meadow's.
        return grass && (perspective || rimDensity(pixels) > 0)
              || crops && (perspective || GpuBiomeVegetation.cropDensity(BoardFields.Crop.MAIZE, pixels) > 0);
    }

    /** The Grass blades option: the meadows' grass and their banks' rims; a field's plants stand either way. */
    void showGrass(boolean shown) {
        if (grass != shown) { grass = shown; view = null; }
    }

    /** The candidate tiles' installed grass, one bound buffer per chunk drawing the prefix each hex's size needs. */
    List<ModelInstance> visible(BoardScene scene, Camera camera, List<BoardScene.Tile> candidates,
          Function<Coords, BoardPlants> plants) {
        if (revision != BoardGeometry.revision() || boardId != scene.boardId()) {
            dispose();
            revision = BoardGeometry.revision();
            boardId = scene.boardId();
        }
        float viewportPixels = Gdx.graphics == null ? camera.viewportHeight : camera.viewportHeight
              * Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale / Math.max(1f, Gdx.graphics.getHeight());
        long now = System.nanoTime();
        // A hex that has just grown in needs its full target once more; the last submission had it growing.
        boolean grown = arrivals.values().removeIf(arrived -> now - arrived >= GROWTH_NANOS);
        BoardQuality quality = BoardQuality.current;
        if (view != null && !grown && arrivals.isEmpty() && view.viewportPixels() == viewportPixels && view.quality() == quality
              && Arrays.equals(view.projectionView(), camera.combined.val) && view.candidates().equals(candidates)) {
            boolean current = true;
            for (var entry : view.sources().entrySet()) {
                if (plants.apply(entry.getKey()) != entry.getValue()) { current = false; break; }
            }
            if (current) { return view.instances(); }
        }
        frame++;
        Map<Coords, BoardPlants> sources = new HashMap<>();
        Vector3 nearest = new Vector3();
        for (BoardScene.Tile tile : candidates) {
            // A hex grows grass, while shown, or a field its crops' plants.
            boolean meadow = grass && grows(scene, tile);
            if (!meadow && BoardBiome.kind(tile) != BoardScene.Biome.FIELD) { continue; }
            Chunk chunk = chunks.computeIfAbsent(new Coords(tile.coords().getX() / TerrainLod.CHUNK_SIZE,
                  tile.coords().getY() / TerrainLod.CHUNK_SIZE), Chunk::new);
            if (chunk.frame != frame) { chunk.begin(frame); }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float radius = BoardGeometry.width() * .75f;
            boolean inView = camera.frustum.sphereInFrustum(center, radius);
            // Every candidate stays in its chunk's buffer, in view or not, so panning uploads nothing. Hexes without
            // installed roots are remembered too: their chunk's installation must refresh a stationary view.
            BoardPlants planted = plants.apply(tile.coords());
            boolean roots = blades(planted, meadow);
            if (inView) {
                if (roots && view != null && view.sources().containsKey(tile.coords())
                      && !blades(view.sources().get(tile.coords()), meadow)) {
                    arrivals.put(tile.coords(), now);
                }
                sources.put(tile.coords(), planted);
            }
            if (!roots) { continue; }
            // Enough roots for the hex's nearest edge; the shader evaluates density again at each actual root.
            nearest.set(center).mulAdd(camera.direction, -radius);
            float pixels = inView ? BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, nearest) : 0;
            Long arrived = arrivals.get(tile.coords());
            float growth = arrived == null ? 1 : BoardRelief.smooth((now - arrived) / (float) GROWTH_NANOS);
            if (meadow) {
                chunk.grass.add(planted.grass(), (int) Math.ceil(ROOTS_PER_HEX * quality.meadowShare * density(pixels) * growth));
                chunk.rim.add(planted.rim(), (int) Math.ceil(ROOTS_PER_HEX * rimDensity(pixels) * growth));
                // A tussock's blades stand up and thin out as a rim's do.
                chunk.tussock.add(planted.tussocks(), (int) Math.ceil(ROOTS_PER_HEX * rimDensity(pixels) * growth));
            } else {
                chunk.wheat.add(planted.wheat(), (int) Math.ceil(ROOTS_PER_HEX
                      * GpuBiomeVegetation.cropDensity(BoardFields.Crop.WHEAT, pixels) * growth));
                chunk.maize.add(planted.maize(), (int) Math.ceil(ROOTS_PER_HEX
                      * GpuBiomeVegetation.cropDensity(BoardFields.Crop.MAIZE, pixels) * growth));
            }
            chunk.nearPixels = Math.max(chunk.nearPixels, pixels);
        }
        List<ModelInstance> result = new ArrayList<>();
        for (var iterator = chunks.values().iterator(); iterator.hasNext(); ) {
            Chunk chunk = iterator.next();
            if (chunk.frame != frame) {
                chunk.dispose();
                iterator.remove();
                continue;
            }
            int detail = chunk.nearPixels >= 280 ? 1 : 0;
            for (Layer layer : chunk.layers) {
                for (int i = 0; i < layer.batches.length; i++) {
                    Batch batch = layer.batches[i];
                    // The idle template keeps its buffer for a return to its scale.
                    batch.begin();
                    if (i != detail || chunk.nearPixels <= layer.start) { continue; }
                    for (FloatArray roots : layer.roots) { batch.add(roots); }
                    long before = batch.uploads;
                    ModelInstance instance = batch.upload(layer.targets);
                    uploads += batch.uploads - before;
                    if (instance != null) {
                        instance.transform.setToTranslation(chunk.centre);
                        result.add(instance);
                    }
                }
            }
        }
        view = new View(camera.combined.val.clone(), viewportPixels, quality, List.copyOf(candidates), sources, result);
        return result;
    }

    /** Whether the hex has blades of its kind: grass, or its field's wheat or maize. */
    private static boolean blades(BoardPlants plants, boolean meadow) {
        return plants != null && (meadow ? plants.grass() != null : plants.wheat() != null || plants.maize() != null);
    }

    long uploads() { return uploads; }

    @Override
    public void dispose() {
        for (Chunk chunk : chunks.values()) { chunk.dispose(); }
        chunks.clear();
        view = null;
    }
}
