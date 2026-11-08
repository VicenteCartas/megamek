/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How far one board edit really reaches into the 3D terrain. Each edit is applied incrementally, as the editor does,
 * then reverted the same way; the hexes whose finished meshes and props differ from full builds are compared with the
 * hexes the update plan rebuilt, by ring around the edit. Hexes that the board's floor or a river's changed flow
 * explain are covered by their own rules and kept out of the rings. Fails when an incremental update differs from a
 * full build.
 *
 * <p>Besides fixed edits of a small synthetic board, it samples every kind of edit at random hexes of the shipped boards
 * in {@code megamek.gpu.reach.boards} (comma separated, under data/boards), {@code megamek.gpu.reach.samples} hexes
 * per kind and board.
 */
@Tag("on-demand")
class GpuTerrainEditReachSmokeTest {
    private static final int SIZE = 20;
    private static final Coords DRY = new Coords(5, 5), STEP = new Coords(4, 14), SHORE = new Coords(12, 10),
          LAKE = new Coords(15, 10);
    private static final String BOARDS = System.getProperty("megamek.gpu.reach.boards",
          "Map Pack Savannahs/16x17 Wide River (Svannah).board");
    private static final int SAMPLES = Integer.getInteger("megamek.gpu.reach.samples", 1);
    /** {@code GpuTerrain.ChunkBuild}'s layers: solid, scatter, overlay (roads), liquid. */
    private static final int LAYERS = 4, SCATTER_LAYER = 1;
    /** A road mask's slot in its section's atlas: where the mask lies there, not what is drawn. */
    private static final String MASK_SLOT = GpuRoads.VERTICES.get(GpuRoads.VERTICES.size() - 1).alias;

    private record Edit(String name, Coords at, Consumer<Hex> change) { }
    private record Kind(String name, Predicate<Hex> applies, Consumer<Hex> change) { }
    private record Plan(Set<Coords> tiles, Set<Coords> sections) { }
    private record Result(String kind, boolean nearWater, int changedRing, int rebuiltRing, int changed, int rebuilt) { }
    /**
     * Per layer, so a report names the layer that differs. The scatter layer is left out of the comparison: its pebbles
     * can settle differently between builds of the same board.
     */
    private record Signature(long[] geometry, long[] uv, long props) {
        boolean sameTerrain(Signature other) {
            if (other == null || props != other.props) { return false; }
            for (int layer = 0; layer < LAYERS; layer++) {
                if (layer != SCATTER_LAYER && (geometry[layer] != other.geometry[layer] || uv[layer] != other.uv[layer])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final List<Kind> KINDS = List.of(
          new Kind("height +1", hex -> !water(hex), hex -> hex.setLevel(hex.getLevel() + 1)),
          new Kind("height -1", hex -> !water(hex), hex -> hex.setLevel(hex.getLevel() - 1)),
          new Kind("height +3", hex -> !water(hex), hex -> hex.setLevel(hex.getLevel() + 3)),
          new Kind("sand", hex -> !water(hex) && !hex.containsTerrain(Terrains.SAND),
                hex -> hex.addTerrain(new Terrain(Terrains.SAND, 1))),
          new Kind("pavement", hex -> !water(hex) && !hex.containsTerrain(Terrains.PAVEMENT),
                hex -> hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1))),
          new Kind("heavy woods", hex -> !water(hex) && !hex.containsAnyTerrainOf(Terrains.WOODS, Terrains.BUILDING),
                hex -> hex.addTerrain(new Terrain(Terrains.WOODS, 2))),
          new Kind("building", hex -> !water(hex) && !hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.WOODS),
                hex -> {
                    hex.addTerrain(new Terrain(Terrains.BUILDING, 2));
                    hex.addTerrain(new Terrain(Terrains.BLDG_CF, 40));
                    hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 2));
                }),
          new Kind("rough", hex -> !water(hex) && !hex.containsTerrain(Terrains.ROUGH),
                hex -> hex.addTerrain(new Terrain(Terrains.ROUGH, 1))),
          new Kind("to water", hex -> !water(hex) && !hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.BRIDGE),
                hex -> hex.addTerrain(new Terrain(Terrains.WATER, 1))),
          new Kind("to land", GpuTerrainEditReachSmokeTest::water, hex -> hex.removeTerrain(Terrains.WATER)),
          new Kind("deeper", GpuTerrainEditReachSmokeTest::water,
                hex -> hex.addTerrain(new Terrain(Terrains.WATER, hex.terrainLevel(Terrains.WATER) + 1))),
          new Kind("ice", hex -> water(hex) && !hex.containsTerrain(Terrains.ICE),
                hex -> hex.addTerrain(new Terrain(Terrains.ICE, 1))),
          new Kind("thaw", hex -> hex.containsTerrain(Terrains.ICE), hex -> hex.removeTerrain(Terrains.ICE)),
          new Kind("woods removed", hex -> hex.containsTerrain(Terrains.WOODS), hex -> {
              hex.removeTerrain(Terrains.WOODS);
              hex.removeTerrain(Terrains.FOLIAGE_ELEV);
          }),
          new Kind("road removed", hex -> hex.containsTerrain(Terrains.ROAD), hex -> hex.removeTerrain(Terrains.ROAD)),
          new Kind("building removed", hex -> hex.containsTerrain(Terrains.BUILDING), hex -> {
              for (int type : new int[] { Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV, Terrains.BLDG_CLASS,
                    Terrains.BLDG_ARMOR, Terrains.BLDG_FLUFF }) {
                  hex.removeTerrain(type);
              }
          }),
          new Kind("bridge removed", hex -> hex.containsTerrain(Terrains.BRIDGE), hex -> {
              for (int type : new int[] { Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV }) {
                  hex.removeTerrain(type);
              }
          }));

    @Test
    void measuresHowFarEditsReach() throws Exception {
        List<Edit> fixed = List.of(
              new Edit("height +1", DRY, hex -> hex.setLevel(1)),
              new Edit("height +3", DRY, hex -> hex.setLevel(3)),
              new Edit("sand", DRY, hex -> hex.addTerrain(new Terrain(Terrains.SAND, 1))),
              new Edit("light woods", DRY, hex -> hex.addTerrain(new Terrain(Terrains.WOODS, 1))),
              new Edit("height +1 beside a hill", STEP, hex -> hex.setLevel(1)),
              new Edit("height +1", SHORE, hex -> hex.setLevel(1)),
              new Edit("to water", SHORE, hex -> hex.addTerrain(new Terrain(Terrains.WATER, 1))),
              new Edit("deeper", LAKE, hex -> hex.addTerrain(new Terrain(Terrains.WATER, 2))));
        var failure = new AtomicReference<Throwable>();
        var report = new StringBuilder();
        List<Result> results = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try {
                    report.append("GL_RENDERER ").append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append('\n');
                    measureBoard("synthetic", synthetic(), fixed, results, problems, report);
                    for (String path : BOARDS.split(",")) {
                        Board board = BoardFile.loadSheet(new File("data/boards", path.trim()), false, true);
                        measureBoard(new File(path.trim()).getName(), board, sample(board, path.trim()), results, problems,
                              report);
                    }
                    summarize(results, report);
                    assertEquals(List.of(), problems, "Every changed hex rebuilt, and incremental updates as full builds");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        System.out.println(report);
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "edit-reach.txt");
        Files.createDirectories(output.getParentFile().toPath());
        Files.writeString(output.toPath(), report);
        if (failure.get() != null) { throw new AssertionError("Edit reach", failure.get()); }
    }

    /** Each edit applied incrementally to the board, compared, then reverted incrementally and compared again. */
    private static void measureBoard(String name, Board board, List<Edit> edits, List<Result> results,
          List<String> problems, StringBuilder report) throws Exception {
        BoardScene before = capture(copy(board, null));
        GpuTerrain incremental = new GpuTerrain();
        try {
            incremental.update(before);
            Map<Coords, Signature> original = signatures(incremental);
            // Hexes whose geometry differs between two full builds of the same board are noise, not reach.
            Set<Coords> unstable;
            GpuTerrain again = new GpuTerrain();
            try {
                again.update(before);
                unstable = differing(original, signatures(again));
            } finally { again.dispose(); }
            report.append("\n").append(name).append(", differing between full builds of the same board: ")
                  .append(unstable).append('\n');
            for (Edit edit : edits) {
                BoardScene after = capture(copy(board, edit));
                Plan plan = updateIncrementally(incremental, after);
                Map<Coords, Signature> updated = signatures(incremental);
                Map<Coords, Signature> rebuilt;
                GpuTerrain full = new GpuTerrain();
                try {
                    full.update(after);
                    rebuilt = signatures(full);
                } finally { full.dispose(); }
                updateIncrementally(incremental, before);
                Map<Coords, Signature> reverted = signatures(incremental);
                Set<Coords> changed = differing(original, rebuilt);
                Set<Coords> stale = differing(updated, rebuilt);
                stale.addAll(differing(reverted, original));
                changed.removeAll(unstable);
                stale.removeAll(unstable);
                Set<Coords> explained = explained(before, after);
                Set<Coords> local = new HashSet<>(changed), planned = new HashSet<>(plan.tiles());
                local.removeAll(explained);
                planned.removeAll(explained);
                Set<Coords> missed = new HashSet<>(changed);
                missed.removeAll(plan.tiles());
                boolean nearWater = nearWater(before, edit.at()) || nearWater(after, edit.at());
                Result result = new Result(edit.name(), nearWater, ring(edit.at(), local), ring(edit.at(), planned),
                      local.size(), planned.size());
                results.add(result);
                report.append(String.format("  %-24s %-15s %-5s changed ring %2d (%3d hexes), rebuilt ring %2d (%3d hexes, "
                            + "%d sections)%s%s%n", edit.name(), edit.at().toFriendlyString(), nearWater ? "water" : "",
                      result.changedRing(), result.changed(), result.rebuiltRing(), result.rebuilt(),
                      plan.sections().size(), missed.isEmpty() ? "" : " MISSED " + describe(missed, original, rebuilt),
                      stale.isEmpty() ? "" : " STALE " + describe(stale, updated, rebuilt, reverted, original)));
                if (!missed.isEmpty() || !stale.isEmpty()) {
                    problems.add(name + " " + edit.name() + " at " + edit.at() + ": missed " + missed + ", stale " + stale);
                    // Start the next edit from a correct terrain, so one miss cannot be reported again and again.
                    incremental.dispose();
                    incremental = new GpuTerrain();
                    incremental.update(before);
                    original = signatures(incremental);
                }
            }
        } finally {
            incremental.dispose();
        }
    }

    /** By edit kind and nearness to water: the furthest ring that changed against the furthest the editor rebuilt. */
    private static void summarize(List<Result> results, StringBuilder report) {
        Map<String, List<Result>> groups = new TreeMap<>();
        for (Result result : results) {
            groups.computeIfAbsent(result.kind() + (result.nearWater() ? " (near water)" : ""), key -> new ArrayList<>())
                  .add(result);
        }
        report.append(String.format("%n%-36s %7s %13s %13s %15s%n", "kind", "samples", "changed ring", "rebuilt ring",
              "hexes chg/rebuilt"));
        groups.forEach((kind, list) -> report.append(String.format("%-36s %7d %13d %13d %8d/%d%n", kind, list.size(),
              list.stream().mapToInt(Result::changedRing).max().orElse(-1),
              list.stream().mapToInt(Result::rebuiltRing).max().orElse(-1),
              list.stream().mapToInt(Result::changed).sum(), list.stream().mapToInt(Result::rebuilt).sum())));
    }

    /**
     * Hexes other rules already rebuild: the board's edge when the floor moved, and the water blending a changed
     * current, with its neighbours.
     */
    private static Set<Coords> explained(BoardScene before, BoardScene after) {
        Set<Coords> result = new HashSet<>();
        if (BoardGeometry.floor(before) != BoardGeometry.floor(after)) {
            for (BoardScene.Tile tile : after.tiles()) {
                Coords at = tile.coords();
                if (at.getX() == 0 || at.getY() == 0 || at.getX() == after.width() - 1 || at.getY() == after.height() - 1) {
                    result.add(at);
                }
            }
        }
        Map<Coords, BoardFlow.Current> was = BoardFlow.calculate(before), is = BoardFlow.calculate(after);
        Set<Coords> currents = new HashSet<>(was.keySet());
        currents.addAll(is.keySet());
        for (Coords coords : currents) {
            if (Objects.equals(was.get(coords), is.get(coords))) { continue; }
            result.add(coords);
            for (int direction = 0; direction < 6; direction++) { result.add(coords.translated(direction)); }
        }
        return result;
    }

    /** Which parts of each stale hex differ, after the edit against its full build and after the revert. */
    private static String describe(Set<Coords> stale, Map<Coords, Signature> updated, Map<Coords, Signature> rebuilt,
          Map<Coords, Signature> reverted, Map<Coords, Signature> original) {
        StringBuilder result = new StringBuilder();
        for (Coords coords : stale) {
            result.append(coords.toFriendlyString()).append('(').append(parts(updated.get(coords), rebuilt.get(coords)))
                  .append('/').append(parts(reverted.get(coords), original.get(coords))).append(") ");
        }
        return result.toString();
    }

    /** Which parts of each missed hex the edit changed. */
    private static String describe(Set<Coords> missed, Map<Coords, Signature> before, Map<Coords, Signature> after) {
        StringBuilder result = new StringBuilder();
        for (Coords coords : missed) {
            result.append(coords.toFriendlyString()).append('(').append(parts(before.get(coords), after.get(coords)))
                  .append(") ");
        }
        return result.toString();
    }

    /** The differing parts: t (triangles) and u (texture coordinates) with their layer, p (props). */
    private static String parts(Signature a, Signature b) {
        if (a == null || b == null) { return "missing"; }
        StringBuilder result = new StringBuilder();
        for (int layer = 0; layer < LAYERS; layer++) {
            if (layer == SCATTER_LAYER) { continue; }
            if (a.geometry()[layer] != b.geometry()[layer]) { result.append('t').append(layer); }
            if (a.uv()[layer] != b.uv()[layer]) { result.append('u').append(layer); }
        }
        return result + (a.props() != b.props() ? "p" : "");
    }

    private static Set<Coords> differing(Map<Coords, Signature> a, Map<Coords, Signature> b) {
        Set<Coords> result = new HashSet<>();
        for (Coords coords : b.keySet()) {
            if (!b.get(coords).sameTerrain(a.get(coords))) { result.add(coords); }
        }
        return result;
    }

    private static int ring(Coords at, Set<Coords> hexes) {
        return hexes.stream().mapToInt(at::distance).max().orElse(-1);
    }

    private static boolean nearWater(BoardScene scene, Coords at) {
        return scene.tiles().stream().anyMatch(tile -> tile.liquid().present()
              && at.distance(tile.coords()) <= BoardSurface.SHORE_RINGS);
    }

    private static boolean water(Hex hex) {
        return hex.containsTerrain(Terrains.WATER);
    }

    /** {@link #SAMPLES} random hexes of the board for each kind of edit they allow; the same hexes every run. */
    private static List<Edit> sample(Board board, String path) {
        Random random = new Random(path.hashCode());
        List<Edit> edits = new ArrayList<>();
        for (Kind kind : KINDS) {
            List<Coords> candidates = new ArrayList<>();
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    if (kind.applies().test(board.getHex(x, y))) { candidates.add(new Coords(x, y)); }
                }
            }
            Collections.shuffle(candidates, random);
            for (Coords at : candidates.subList(0, Math.min(SAMPLES, candidates.size()))) {
                edits.add(new Edit(kind.name(), at, kind.change()));
            }
        }
        return edits;
    }

    /** The editor's own path: the update plan's changed hexes and their sections, plus moved atlas slots. */
    @SuppressWarnings("unchecked")
    private static Plan updateIncrementally(GpuTerrain terrain, BoardScene after) throws Exception {
        terrain.update(after, null);
        Plan plan = null;
        while (terrain.busy()) {
            terrain.refine(null);
            Object rebuild = field(terrain, "rebuild");
            if (plan == null && rebuild != null && field(rebuild, "plan") != null) {
                Object update = field(rebuild, "plan");
                Set<Coords> tiles = new HashSet<>((Set<Coords>) field(update, "changedTiles"));
                tiles.addAll((Set<Coords>) field(terrain, "atlasTileChanges"));
                Set<Coords> sections = new HashSet<>((Set<Coords>) field(update, "changed"));
                sections.addAll((Set<Coords>) field(terrain, "atlasChanges"));
                plan = new Plan(tiles, sections);
            }
            if (terrain.busy()) { LockSupport.parkNanos(1_000_000); }
        }
        assertNotNull(plan, "The update plan must be observed before it is committed");
        return plan;
    }

    /**
     * Each hex's finished triangles, all vertex attributes rounded to a thousandth, and its props' transforms, in an
     * order-independent hash per layer. Texture coordinates hash apart: an atlas slot can move without any shape
     * changing. A road mask's atlas slot is left out: each build packs its section's masks afresh.
     */
    @SuppressWarnings("unchecked")
    private static Map<Coords, Signature> signatures(GpuTerrain terrain) throws Exception {
        Map<Coords, Signature> result = new HashMap<>();
        Map<Mesh, float[]> vertices = new IdentityHashMap<>();
        Map<Mesh, short[]> indices = new IdentityHashMap<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (var entry : ((Map<Coords, ?>) field(chunk, "tileMeshes")).entrySet()) {
                List<List<long[]>> parts = new ArrayList<>();
                for (int layer = 0; layer < LAYERS; layer++) { parts.add(new ArrayList<>()); }
                for (Object range : (List<?>) field(entry.getValue(), "ranges")) {
                    Mesh mesh = (Mesh) field(range, "mesh");
                    float[] data = vertices.computeIfAbsent(mesh, key -> {
                        float[] all = new float[key.getNumVertices() * key.getVertexSize() / 4];
                        key.getVertices(all);
                        return all;
                    });
                    short[] order = indices.computeIfAbsent(mesh, key -> {
                        short[] all = new short[key.getNumIndices()];
                        key.getIndices(all);
                        return all;
                    });
                    VertexAttributes attributes = mesh.getVertexAttributes();
                    int stride = mesh.getVertexSize() / 4, offset = (int) field(range, "offset");
                    int layer = (int) field(range, "layer");
                    for (int at = offset; at < offset + (int) field(range, "count"); at += 3) {
                        long geometry = 0, uv = 0;
                        for (int corner = 0; corner < 3; corner++) {
                            int vertex = (order[at + corner] & 0xffff) * stride;
                            for (VertexAttribute attribute : attributes) {
                                if (attribute.alias.equals(MASK_SLOT)) { continue; }
                                for (int component = 0; component < attribute.numComponents; component++) {
                                    float value = data[vertex + attribute.offset / 4 + component];
                                    long rounded = attribute.usage == VertexAttributes.Usage.ColorPacked
                                          ? Float.floatToIntBits(value) : Math.round(value * 1000d);
                                    if (attribute.usage == VertexAttributes.Usage.TextureCoordinates) {
                                        uv = uv * 31 + rounded;
                                    } else {
                                        geometry = geometry * 31 + rounded;
                                    }
                                }
                            }
                        }
                        parts.get(layer).add(new long[] { geometry, uv });
                    }
                }
                List<Long> props = new ArrayList<>();
                for (Object prop : (List<?>) field(entry.getValue(), "props")) {
                    long transform = 7;
                    for (float value : ((ModelInstance) field(prop, "instance")).transform.val) {
                        transform = transform * 31 + Math.round(value * 1000d);
                    }
                    props.add(transform);
                }
                props.sort(null);
                long[] geometry = new long[LAYERS], uv = new long[LAYERS];
                for (int layer = 0; layer < LAYERS; layer++) {
                    List<long[]> triangles = parts.get(layer);
                    geometry[layer] = Arrays.hashCode(triangles.stream().mapToLong(p -> p[0]).sorted().toArray());
                    uv[layer] = Arrays.hashCode(triangles.stream().mapToLong(p -> p[1]).sorted().toArray());
                }
                result.put(entry.getKey(), new Signature(geometry, uv, props.hashCode()));
            }
        }
        return result;
    }

    /** Level grass with a level-two hill along the west edge and a depth-one lake in the east. */
    private static Board synthetic() {
        Hex[] hexes = new Hex[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                Hex hex = new Hex(x <= 3 && y >= 11 && y <= 17 ? 2 : 0);
                hex.setTheme("grass");
                if (x >= 13 && x <= 17 && y >= 7 && y <= 13) { hex.addTerrain(new Terrain(Terrains.WATER, 1)); }
                hexes[y * SIZE + x] = hex;
            }
        }
        return new Board(SIZE, SIZE, hexes);
    }

    /** A copy of the board with the edit applied as the board editor applies it, updating the neighbours' exits. */
    private static Board copy(Board board, Edit edit) {
        Hex[] hexes = new Hex[board.getWidth() * board.getHeight()];
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) { hexes[y * board.getWidth() + x] = board.getHex(x, y).duplicate(); }
        }
        Board result = new Board(board.getWidth(), board.getHeight(), hexes);
        if (edit != null) {
            Hex hex = result.getHex(edit.at()).duplicate();
            edit.change().accept(hex);
            result.setHex(edit.at().getX(), edit.at().getY(), hex);
        }
        return result;
    }

    private static BoardScene capture(Board board) throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            AtomicReference<BoardScene> scene = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
            return scene.get();
        }
    }
}
