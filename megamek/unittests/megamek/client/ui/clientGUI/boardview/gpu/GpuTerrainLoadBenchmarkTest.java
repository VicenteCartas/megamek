/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Times the CPU side of terrain chunk preparation on a region of a real board and prints a digest of everything it
 * produced, so a faster build can be checked against the geometry and materials it must not change. The board,
 * region, detail levels, worker count, repeats and graphics ({@code megamek.gpu.benchmarkGraphics}, ENHANCED or
 * CLASSIC) come from the {@code megamek.gpu.benchmark*} properties; run it with
 * {@code gradlew :megamek:gpuTerrainLoadBenchmark}.
 */
@Tag("on-demand")
class GpuTerrainLoadBenchmarkTest {
    private static final Comparator<Vector3> VECTORS = Comparator.comparingInt((Vector3 v) -> Float.floatToIntBits(v.x))
          .thenComparingInt(v -> Float.floatToIntBits(v.y)).thenComparingInt(v -> Float.floatToIntBits(v.z));

    @Test
    void preparesABoardRegionAndDigestsTheResult() throws Exception {
        File file = new File(System.getProperty("megamek.gpu.benchmarkBoard",
              "data/boards/unofficial/VictorMorson/128x192 Texlos - Livius - Valencia_City.board"));
        assumeTrue(file.isFile(), "Benchmark board " + file);
        int[] region = Arrays.stream(System.getProperty("megamek.gpu.benchmarkRegion", "64,16,32,32").split(","))
              .mapToInt(value -> Integer.parseInt(value.trim())).toArray();
        int workers = Integer.getInteger("megamek.gpu.benchmarkWorkers", GpuTerrain.DEFAULT_WORKERS);
        int repeats = Integer.getInteger("megamek.gpu.benchmarkRepeats", 2);
        BoardGraphics graphics = BoardGraphics.parse(System.getProperty("megamek.gpu.benchmarkGraphics"));
        // As a game loads a sheet: converted (its placed objects and car-park lane roads are part of the board).
        Board board = BoardFile.loadSheet(file, false, true);
        BoardScene scene = scene(board);
        TerrainSettings settings = TerrainSettings.capture();
        Map<Coords, BoardFlow.Current> currents = settings.call(() -> BoardFlow.calculate(scene));
        float floor = settings.call(() -> BoardGeometry.floor(scene));
        System.out.printf("PERF terrain-prepare board=%s region=%s workers=%d graphics=%s%n", file.getName(),
              Arrays.toString(region), workers, graphics);
        // A Classic build prepares the tileset columns in place of the 3D chunks, which have no level
        // of detail.
        for (int repeat = 0; graphics == BoardGraphics.CLASSIC && repeat < repeats; repeat++) {
            columns(scene, settings, currents, floor, region, workers);
        }
        for (String name : System.getProperty("megamek.gpu.benchmarkLods", "DISTANT,FULL").split(",")) {
            TerrainLod lod = TerrainLod.valueOf(name.trim());
            for (int repeat = 0; graphics != BoardGraphics.CLASSIC && repeat < repeats; repeat++) {
                prepare(scene, settings, currents, floor, lod, region, workers);
            }
        }
    }

    /**
     * Times the region's Classic chunks as the terrain workers prepare them (GpuTerrain#prepareColumns), then digests
     * their columns hex by hex in the region's order, as the step-4 baseline digested GpuTilesetTerrain#column: an
     * equal checksum is the same column geometry.
     */
    private static void columns(BoardScene scene, TerrainSettings settings, Map<Coords, BoardFlow.Current> currents,
          float floor, int[] region, int workers) throws Exception {
        ForkJoinPool pool = TerrainSettings.workers(workers);
        try {
            List<CompletableFuture<GpuTerrain.Prepared>> results = new ArrayList<>();
            long start = System.nanoTime();
            for (int x = region[0]; x < Math.min(scene.width(), region[0] + region[2]); x += TerrainLod.CHUNK_SIZE) {
                for (int y = region[1]; y < Math.min(scene.height(), region[1] + region[3]); y += TerrainLod.CHUNK_SIZE) {
                    int chunkX = x, chunkY = y;
                    results.add(CompletableFuture.supplyAsync(() -> settings.call(() -> GpuTerrain.prepareColumns(scene,
                          chunkX, chunkY, floor, currents, Set.of(), Map.of(), Map.of(), () -> { },
                          new TerrainLoadProgress())), pool));
                }
            }
            Map<Coords, BoardTacticalGeometry.Surface> columns = new HashMap<>();
            for (var result : results) { columns.putAll(result.join().topography()); }
            double prepared = (System.nanoTime() - start) / 1e6;
            Sink out = new Sink();
            int hexes = 0;
            long faces = 0;
            for (int x = region[0]; x < Math.min(scene.width(), region[0] + region[2]); x++) {
                for (int y = region[1]; y < Math.min(scene.height(), region[1] + region[3]); y++) {
                    BoardTacticalGeometry.Surface column = columns.get(new Coords(x, y));
                    hexes++;
                    faces += column.top().size() + column.faces().size() + column.walls().size();
                    surface(out, column);
                }
            }
            System.out.printf("PERF terrain-columns hexes=%d chunks=%d workers=%d prepare=%.1f ms withDigest=%.1f ms faces=%d"
                        + " checksum=%08x%n", hexes, results.size(), workers, prepared, (System.nanoTime() - start) / 1e6,
                  faces, out.value());
        } finally {
            pool.shutdownNow();
        }
    }

    /** The live capture without artwork: structures keep a model name so their hexes get the same footprint. */
    static BoardScene scene(Board board) {
        var pool = new BoardScene.PixelPool();
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords coords = new Coords(x, y);
                Hex hex = board.getHex(coords);
                Map<Integer, String> models = new HashMap<>();
                for (int terrain : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
                    if (hex.containsTerrain(terrain)) { models.put(terrain, "buildings/standard/building-hard"); }
                }
                var artwork = new BoardArtwork.HexImage(coords, null, null, List.of(), models, null);
                tiles.add(BoardScene.captureTile(hex, artwork, null, pool, board::getHex));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void prepare(BoardScene scene, TerrainSettings settings, Map<Coords, BoardFlow.Current> currents,
          float floor, TerrainLod lod, int[] region, int workers) throws Exception {
        ForkJoinPool pool = TerrainSettings.workers(workers);
        try {
            GpuRoads.Masks masks = new GpuRoads.Masks();
            List<int[]> chunks = new ArrayList<>();
            for (int x = region[0]; x < Math.min(scene.width(), region[0] + region[2]); x += TerrainLod.CHUNK_SIZE) {
                for (int y = region[1]; y < Math.min(scene.height(), region[1] + region[3]); y += TerrainLod.CHUNK_SIZE) {
                    chunks.add(new int[] { x, y });
                }
            }
            // The renderer keeps at most four chunks in flight and consumes them in order; the same bound and a
            // checksum of each finished chunk keep this measurement comparable and its memory flat.
            List<CompletableFuture<GpuTerrain.Prepared>> results = new ArrayList<>();
            Sink out = new Sink();
            long triangles = 0;
            TreeMap<Long, Coords> largest = new TreeMap<>();
            AtomicLong lastPrepared = new AtomicLong();
            long start = System.nanoTime();
            for (int next = 0, done = 0; done < chunks.size(); ) {
                if (next < chunks.size() && next - done < 4) {
                    int[] chunk = chunks.get(next++);
                    var result = CompletableFuture.supplyAsync(() -> settings.call(() -> GpuTerrain.prepare(scene,
                          chunk[0], chunk[1], floor, lod, currents, settings, Set.of(), Map.of(), () -> { },
                          new TerrainLoadProgress(), masks)), pool);
                    result.thenRun(() -> lastPrepared.accumulateAndGet(System.nanoTime(), Math::max));
                    results.add(result);
                    continue;
                }
                GpuTerrain.Prepared prepared = results.set(done++, null).join();
                triangles += digest(out, prepared);
                for (var sculpt : prepared.sculpts().entrySet()) {
                    long count = sculpt.getValue().blended().values().stream().mapToLong(List::size).sum()
                          + sculpt.getValue().blendedTops().values().stream().mapToLong(List::size).sum();
                    largest.put(count * 100_000 + sculpt.getKey().getX() * 300 + sculpt.getKey().getY(), sculpt.getKey());
                    if (largest.size() > 5) { largest.pollFirstEntry(); }
                }
            }
            double millis = (System.nanoTime() - start) / 1e6, prepared = (lastPrepared.get() - start) / 1e6;
            StringBuilder busiest = new StringBuilder();
            largest.descendingMap().forEach((key, coords) -> busiest.append(' ').append(coords).append('=').append(key / 100_000));
            System.out.printf("PERF terrain-prepare lod=%s chunks=%d workers=%d prepare=%.1f ms perChunk=%.1f ms"
                        + " withDigest=%.1f ms blendedTriangles=%d checksum=%08x busiest:%s%n", lod, chunks.size(), workers,
                  prepared, prepared / chunks.size(), millis, triangles, out.value(), busiest);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Checksums values in bulk; a stream call per value would make the digest slower than the preparation. */
    private static final class Sink {
        private final CRC32C checksum = new CRC32C();
        private final ByteBuffer buffer = ByteBuffer.allocate(1 << 16);

        void writeFloat(float value) {
            if (buffer.remaining() < 4) { flush(); }
            buffer.putFloat(value);
        }

        void writeInt(int value) {
            if (buffer.remaining() < 4) { flush(); }
            buffer.putInt(value);
        }

        void writeBoolean(boolean value) { writeInt(value ? 1 : 0); }

        void writeUTF(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeInt(bytes.length);
            write(bytes);
        }

        void write(byte[] bytes) {
            flush();
            checksum.update(bytes);
        }

        private void flush() {
            buffer.flip();
            checksum.update(buffer);
            buffer.clear();
        }

        long value() {
            flush();
            return checksum.getValue();
        }
    }

    /** Everything a chunk hands to the mesh builders, in a fixed order; returns its blended triangle count. */
    private static long digest(Sink out, GpuTerrain.Prepared prepared) {
        long blended = 0;
        List<Coords> ordered = new ArrayList<>(prepared.surfaces().keySet());
        ordered.sort(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY));
        for (Coords coords : ordered) {
            out.writeInt(coords.getX());
            out.writeInt(coords.getY());
            BoardSurface surface = prepared.surfaces().get(coords);
            faces(out, surface.faces);
            faces(out, surface.waterFaces);
            var topography = prepared.topography().get(coords);
            if (topography != null) { surface(out, topography); }
            var sculpt = prepared.sculpts().get(coords);
            if (sculpt != null) {
                faces(out, sculpt.walls());
                faces(out, sculpt.ground());
                faces(out, sculpt.formed());
                faces(out, sculpt.bed());
                List<Vector3> keys = new ArrayList<>(sculpt.bedNormals().keySet());
                keys.sort(VECTORS);
                for (Vector3 key : keys) {
                    vector(out, key);
                    vector(out, sculpt.bedNormals().get(key));
                }
                for (var group : Stream.concat(sculpt.blended().entrySet().stream(),
                      sculpt.blendedTops().entrySet().stream()).toList()) {
                    var palette = group.getKey();
                    out.writeInt(palette.base());
                    out.writeInt(palette.first());
                    out.writeInt(palette.second());
                    out.writeInt(palette.third());
                    for (var triangle : group.getValue()) {
                        point(out, triangle.a());
                        point(out, triangle.b());
                        point(out, triangle.c());
                    }
                    blended += group.getValue().size();
                }
            }
            var walls = prepared.walls().get(coords);
            if (walls != null) {
                for (var wall : walls.entrySet()) {
                    side(out, wall.getKey());
                    faces(out, wall.getValue());
                }
            }
            var plants = prepared.plants().get(coords);
            if (plants != null) {
                floats(out, plants.wheat());
                floats(out, plants.maize());
                floats(out, plants.reeds());
                floats(out, plants.grass());
                floats(out, plants.rim());
            }
            var roads = prepared.roads().get(coords);
            if (roads != null) {
                for (var road : roads) {
                    var patch = road.patch();
                    out.writeUTF(patch.texture());
                    out.writeInt(patch.tint().toIntBits());
                    out.writeFloat(patch.repeat());
                    out.writeFloat(patch.lift());
                    var fade = patch.fade();
                    if (fade != null) {
                        out.writeFloat(fade.outer());
                        out.writeFloat(fade.inner());
                        out.writeFloat(fade.end());
                        out.writeBoolean(fade.wheels());
                        out.writeFloat(fade.setback());
                        out.writeBoolean(fade.landing());
                        if (fade.join() != null) {
                            out.writeInt(fade.join().direction());
                            out.writeInt(fade.join().kind().ordinal());
                            out.writeFloat(fade.join().lateral(0, 0));
                            out.writeFloat(fade.join().coverage(0, 0));
                        }
                    }
                    shape(out, patch.shape());
                    var mask = road.mask();
                    out.writeFloat(mask.x());
                    out.writeFloat(mask.y());
                    out.writeFloat(mask.width());
                    out.writeFloat(mask.height());
                    out.writeInt(mask.pixels().width());
                    out.writeInt(mask.pixels().height());
                    for (int i = 0; i < mask.pixels().width() * mask.pixels().height(); i++) {
                        out.writeInt(mask.pixels().rgba(i));
                    }
                    out.writeBoolean(road.flat());
                    for (var triangle : road.triangles()) {
                        vector(out, triangle.a());
                        vector(out, triangle.b());
                        vector(out, triangle.c());
                        out.writeInt(triangle.argb());
                    }
                }
            }
            var deck = prepared.bridges().get(coords);
            if (deck != null) { out.writeUTF(deck.toString()); }
            var bridge = prepared.bridgeShapes().get(coords);
            if (bridge != null) {
                out.writeInt(bridge.surface().ordinal());
                out.writeFloat(bridge.level());
                for (var facet : bridge.facets()) {
                    vector(out, facet.a());
                    vector(out, facet.b());
                    vector(out, facet.c());
                    vector(out, facet.normal());
                    out.writeUTF(String.valueOf(facet.part()));
                }
            }
        }
        field(out, prepared.water());
        field(out, prepared.lava());
        return blended;
    }

    private static void surface(Sink out, BoardTacticalGeometry.Surface surface) {
        faces(out, surface.top());
        faces(out, surface.slopes());
        faces(out, surface.faces());
        faces(out, surface.water());
        faces(out, surface.walls());
        for (var side : surface.waterfalls()) { side(out, side); }
        out.writeFloat(surface.highestTop());
    }

    private static void faces(Sink out, List<BoardSurface.Face> faces) {
        out.writeInt(faces.size());
        for (var face : faces) {
            vector(out, face.a());
            vector(out, face.b());
            vector(out, face.c());
            out.writeInt(face.finish().ordinal());
            out.writeInt(face.landEdge());
        }
    }

    private static void side(Sink out, BoardSurface.Side side) {
        vector(out, side.a());
        vector(out, side.b());
        out.writeFloat(side.lowA());
        out.writeFloat(side.lowB());
        out.writeInt(side.edge());
    }

    private static void vector(Sink out, Vector3 vector) {
        out.writeFloat(vector.x);
        out.writeFloat(vector.y);
        out.writeFloat(vector.z);
    }

    private static void point(Sink out, GpuSurfaceBlend.Point point) {
        var vertex = point.vertex();
        vector(out, vertex.position);
        vector(out, vertex.normal);
        out.writeFloat(vertex.color.r);
        out.writeFloat(vertex.color.g);
        out.writeFloat(vertex.color.b);
        out.writeFloat(vertex.color.a);
        out.writeFloat(vertex.uv.x);
        out.writeFloat(vertex.uv.y);
        for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
            out.writeFloat(point.cover().weight(family));
        }
        out.writeFloat(point.cover().interpolation());
    }

    private static void floats(Sink out, FloatArray values) {
        out.writeInt(values == null ? -1 : values.size);
        for (int i = 0; values != null && i < values.size; i++) { out.writeFloat(values.get(i)); }
    }

    private static void shape(Sink out, Area area) {
        float[] segment = new float[6];
        for (PathIterator path = area.getPathIterator(null); !path.isDone(); path.next()) {
            Arrays.fill(segment, 0);
            out.writeInt(path.currentSegment(segment));
            for (float value : segment) { out.writeFloat(value); }
        }
    }

    private static void field(Sink out, GpuWaterShader.Field.Prepared field) {
        out.writeInt(field == null ? -1 : field.width());
        if (field == null) { return; }
        out.writeInt(field.height());
        out.writeFloat(field.spacing());
        out.writeInt(field.firstX());
        out.writeInt(field.firstY());
        out.write(field.pixels());
    }
}
