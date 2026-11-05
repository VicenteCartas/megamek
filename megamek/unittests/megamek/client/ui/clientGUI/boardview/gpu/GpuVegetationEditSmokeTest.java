/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Predicate;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Check submitted instances on every frame: a retained CPU array alone does not prove that plants stay visible. */
@Tag("on-demand")
class GpuVegetationEditSmokeTest {
    private static final Coords EDIT = new Coords(4, 4);
    private record Roots(Object patch, float[] data) { }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void heightEditsKeepUnchangedGrassAndFieldsSubmitted(boolean editField) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var lod = TerrainLod.tuning();
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "vegetation-edits/" + (editField ? "field" : "grass"));
        Files.createDirectories(output.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1536, 1024);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardScenes.tune(.94f, true);
                    TerrainLod.tune(new TerrainLod.Tuning(1, 1));
                    var camera = new BoardCamera();
                    camera.resize(1536, 1024);
                    camera.setIsometric(editField);
                    camera.camera.zoom = BoardGeometry.width() / 200;
                    camera.center(BoardGeometry.center(EDIT, 0));
                    BoardScene scene = scene(0, editField);
                    terrain.update(scene);
                    // The grass engine draws the grass and the fields' plants.
                    var grass = (GpuGroundCover) field(terrain, "groundCover");
                    settle(terrain, frame, camera, scene);
                    GpuReviewFrame.save(new File(output, "before.png"));
                    // The direct cache checks use scene order rather than chunk order; normalize it before
                    // measuring uploads so a harmless reorder does not count as geometry invalidation.
                    grass.visible(scene, camera.camera, scene.tiles(), terrain::planted);
                    var grassBefore = submitted(grass, scene, terrain::planted, false);
                    var plantsBefore = submitted(grass, scene, terrain::planted, true);
                    assertTrue(grassBefore.keySet().stream().anyMatch(c -> c.distance(EDIT) > 2));
                    assertTrue(plantsBefore.keySet().stream().anyMatch(c -> c.distance(EDIT) > 2));

                    // A tactical-only capture and a chunk replanted on equal ground must be free: equal plants upload nothing.
                    var replanted = new HashMap<Coords, BoardPlants>();
                    for (var tile : scene.tiles()) {
                        if (terrain.planted(tile.coords()) != null) {
                            replanted.put(tile.coords(), BoardPlants.plant(scene, tile, terrain.tacticalSurface(tile.coords()),
                                  TerrainLod.FULL, java.util.List.of()));
                        }
                    }
                    long uploads = grass.uploads();
                    for (int capture = 0; capture < 5; capture++) {
                        scene = new BoardScene(scene.boardId(), scene.width(), scene.height(), new ArrayList<>(scene.tiles()),
                              List.of(), List.of(), -1, "", List.of());
                        assertFalse(grass.visible(scene, camera.camera, scene.tiles(), replanted::get).isEmpty());
                        retained(grassBefore, submitted(grass, scene, replanted::get, false), c -> true);
                        retained(plantsBefore, submitted(grass, scene, replanted::get, true), c -> true);
                    }
                    assertEquals(uploads, grass.uploads(), "Equivalent surfaces must not upload grass or fields again");

                    // A held altitude wheel can publish several snapshots before earlier meshes have finished.
                    // Check every rendered frame, including the actual terrain publication and subsequent root work.
                    int nextEdit = 0, frames = 0;
                    long nextInput = 0, deadline = System.nanoTime() + 60_000_000_000L;
                    // Negative levels also replace exterior walls when the map's visual floor moves.
                    int[] levels = { 1, 2, 3, 2, 1, 0, -1, -2, -1, 0, 1, 2 };
                    do {
                        long now = System.nanoTime();
                        if (nextEdit < levels.length && now >= nextInput) {
                            scene = scene(levels[nextEdit++], editField);
                            terrain.update(scene, camera.camera);
                            nextInput = now + 50_000_000;
                        }
                        terrain.refine(camera.camera);
                        frame.render(terrain, camera, scene);
                        var grassNow = submitted(grass, scene, terrain::planted, false);
                        var plantsNow = submitted(grass, scene, terrain::planted, true);
                        retained(grassBefore, grassNow, c -> c.distance(EDIT) > 2);
                        retained(plantsBefore, plantsNow, c -> c.distance(EDIT) > 2);
                        if (editField) { grounded(plantsNow.get(EDIT), terrain.tacticalSurface(EDIT)); }
                        assertTrue(System.nanoTime() < deadline, "Sustained editing must finish");
                        frames++;
                        LockSupport.parkNanos(5_000_000);
                    } while (nextEdit < levels.length || terrain.busy());
                    GpuReviewFrame.save(new File(output, "after.png"));

                    // Changed roots must still converge to a clean build on the final published ground.
                    var freshGrass = new GpuGroundCover();
                    try {
                        freshGrass.visible(scene, camera.camera, scene.tiles(), terrain::planted);
                        for (boolean fields : new boolean[] { false, true }) {
                            sameRoots(submitted(freshGrass, scene, terrain::planted, fields),
                                  submitted(grass, scene, terrain::planted, fields));
                        }
                    } finally { freshGrass.dispose(); }
                    Files.writeString(new File(output, "results.txt").toPath(),
                          "Unchanged roots stayed submitted across " + frames + " frames and " + levels.length
                                + " altitude inputs (50 ms target cadence). Final roots match a clean build.\n"
                                + "Unchanged grass patches: " + grassBefore.keySet().stream().filter(c -> c.distance(EDIT) > 2).count()
                                + "; unchanged field patches: " + plantsBefore.keySet().stream().filter(c -> c.distance(EDIT) > 2).count()
                                + ". Equivalent-surface captures: 5; additional uploads: 0.\n");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose(); frame.dispose(); BoardGeometry.tune(original); TerrainLod.tune(lod);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Vegetation edit continuity", failure.get()); }
    }

    private static BoardScene scene(int level, boolean editField) {
        return BoardScenes.scene(c -> BoardScenes.tile(c,
              c.getX() >= (editField ? 4 : 5) ? BoardScene.Biome.FIELD : BoardScene.Biome.NONE, c.equals(EDIT) ? level : 0));
    }

    private static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        do {
            terrain.refine(camera.camera);
            frame.render(terrain, camera, scene);
            assertTrue(System.nanoTime() < deadline, "Initial plants must finish");
        } while (terrain.busy());
        frame.render(terrain, camera, scene);
    }

    /**
     * The grass, or with {@code fields} the fields' plants, the grass engine was given that it actually submitted in its
     * drawn batches. A chunk prepared again replants identical roots, so plants compare by their roots rather than by
     * identity.
     */
    private static Map<Coords, Roots> submitted(GpuGroundCover cover, BoardScene scene, Function<Coords, BoardPlants> plants,
          boolean fields) throws Exception {
        var active = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object batch : GpuBiomeSmokeTest.batches(cover)) { active.addAll((List<?>) field(batch, "current")); }
        var result = new HashMap<Coords, Roots>();
        for (var tile : scene.tiles()) {
            var planted = plants.apply(tile.coords());
            if (planted == null) { continue; }
            var roots = new FloatArray();
            for (FloatArray part : fields ? new FloatArray[] { planted.wheat(), planted.maize() }
                  : new FloatArray[] { planted.grass() }) {
                if (part != null && active.contains(part)) { roots.addAll(part); }
            }
            if (roots.size > 0) { result.put(tile.coords(), new Roots(null, roots.toArray())); }
        }
        return result;
    }

    private static void retained(Map<Coords, Roots> before, Map<Coords, Roots> after, Predicate<Coords> unchanged) {
        before.forEach((coords, roots) -> {
            if (unchanged.test(coords)) {
                assertTrue(after.containsKey(coords), "Plants disappeared at " + coords);
                assertArrayEquals(roots.data(), after.get(coords).data(), "Unchanged roots moved at " + coords);
            }
        });
    }

    private static void grounded(Roots roots, BoardTacticalGeometry.Surface surface) {
        if (roots == null) { return; }
        float[] data = roots.data();
        for (int i = 0; i < data.length; i += 4) {
            float floor = BoardSurface.sampleHeight(surface.top(), data[i], data[i + 1], Float.NaN);
            assertEquals(floor, data[i + 2] + .018f * BoardRelief.metres(1), .0001f,
                  "An edited field must never show roots at its previous elevation");
        }
    }

    private static void sameRoots(Map<Coords, Roots> expected, Map<Coords, Roots> actual) {
        assertEquals(expected.keySet(), actual.keySet(), "Visible planted hexes must match a clean build");
        expected.forEach((coords, roots) -> assertArrayEquals(roots.data(), actual.get(coords).data(),
              "Stale or incomplete roots at " + coords));
    }

    private static Object field(Object owner, String name) throws Exception {
        var member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }
}
