/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/** Real GL ownership across asynchronous detail changes, cache reuse, and an edit superseding an in-flight job. */
@Tag("on-demand")
class GpuTerrainLodSmokeTest {
    @Test
    void closingDuringPreparationStopsTheWorkerAndAllowsReopening() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                try {
                    BoardScene scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.GRASS);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(640, 480);
                    camera.fit(scene);
                    terrain.update(scene, camera.camera);
                    terrain.refine(camera.camera);
                    terrain.dispose();
                    assertTrue(((java.util.concurrent.ExecutorService) field(terrain, "detailWorker")).isTerminated());
                    terrain = new GpuTerrain();
                    terrain.update(scene, camera.camera);
                    settle(terrain, null, scene, camera);
                    assertTrue(terrain.ready(scene));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain close/reopen lifecycle", failure.get()); }
    }

    @Test
    void zoomRestoresDetailAndEditsDiscardObsoletePreparation() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuTactical tactical = new GpuTactical(terrain::tacticalSurface);
                var previousLod = TerrainLod.tuning();
                var previousSettings = BoardGeometry.tuning();
                try {
                    verifiesPreparedWaterTextureUpload();
                    BoardScene scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.SAND);
                    Coords at = new Coords(4, 4);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1280, 900);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    view(camera, 30);
                    terrain.update(scene, camera.camera);
                    assertTrue(terrain.busy(), "Opening must enqueue work instead of constructing the board in this call");
                    settle(terrain, tactical, scene, camera);
                    var distant = terrain.tacticalSurface(at);
                    assertEquals(TerrainLod.DISTANT, firstLod(terrain));

                    // Only the previous tier of a chunk is cached: test reuse between the tiers a zoom passes
                    // instead of assuming all old tiers survive.
                    view(camera, 2.5f);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.MEDIUM, firstLod(terrain));
                    var medium = terrain.tacticalSurface(at);

                    view(camera, .6f);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.FULL, firstLod(terrain));
                    assertNotSame(distant, terrain.tacticalSurface(at));
                    capture(terrain, camera, "terrain-lod-close.png");
                    var close = terrain.tacticalSurface(at);
                    assertTrue(!terrain.refine(camera.camera));
                    assertSame(close, terrain.tacticalSurface(at), "A settled camera must not rebuild meshes");

                    view(camera, 2.5f);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.MEDIUM, firstLod(terrain));
                    assertSame(medium, terrain.tacticalSurface(at), "Returning to the previous tier reuses its installed geometry");

                    // Pinning full detail at a fixed camera uses the same incremental/cached path as zooming.
                    int revision = BoardGeometry.terrainRevision();
                    TerrainLod.tune(new TerrainLod.Tuning(1, 1));
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.FULL, firstLod(terrain));
                    assertSame(close, terrain.tacticalSurface(at));
                    TerrainLod.tune(TerrainLod.DEFAULTS);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.MEDIUM, firstLod(terrain));
                    assertSame(medium, terrain.tacticalSurface(at));
                    assertEquals(revision, BoardGeometry.terrainRevision());

                    // The original DISTANT tier was evicted by the intermediate refinement. Rebuilding it
                    // must reproduce the same geometry, without pretending the old object is still cached.
                    view(camera, 30);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.DISTANT, firstLod(terrain));
                    assertNotSame(distant, terrain.tacticalSurface(at));
                    assertEquals(distant, terrain.tacticalSurface(at), "Rebuilt distant geometry remains deterministic");

                    // Request the uncached COARSE tier, then edit before that CPU preparation is installed.
                    view(camera, 8);
                    terrain.refine(camera.camera);
                    assertTrue(field(terrain, "detailJob") != null);
                    List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
                    BoardScene.Tile before = scene.tile(at);
                    tiles.set(tiles.indexOf(before), new BoardScene.Tile(at, before.elevation() + 2, -1, false, 0,
                          before.surface(), null, null, List.of(), List.of(),
                          BoardLiquid.NONE, null, true));
                    BoardScene edited = new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
                    var displayed = terrain.tacticalSurface(at);
                    terrain.update(edited, camera.camera);
                    assertSame(displayed, terrain.tacticalSurface(at), "The displayed revision survives while its replacement is prepared");
                    // A second request supersedes both an in-flight LoD job and the first edit, without a queue of old edits.
                    terrain.refine(camera.camera);
                    terrain.update(scene, camera.camera);
                    terrain.update(edited, camera.camera);
                    settle(terrain, tactical, edited, camera);
                    assertNotSame(displayed, terrain.tacticalSurface(at));
                    Vector3 center = BoardGeometry.center(at, before.elevation() + 2);
                    double height = terrain.tacticalSurface(at).top().stream()
                          .mapToDouble(face -> face.height(center.x, center.y)).max().orElseThrow();
                    assertTrue(Math.abs(height - center.z) < BoardRelief.metres(.3f));

                    // Unit scale alone does not rebuild a board without fallen limb props.
                    var unscaledSurface = terrain.tacticalSurface(at);
                    BoardGeometry.tune(new BoardGeometry.Tuning(1, 1.2f, 1, 18, .8f));
                    terrain.update(edited, camera.camera);
                    assertTrue(!terrain.busy());
                    assertSame(unscaledSurface, terrain.tacticalSurface(at));
                    assertEquals(BoardGeometry.tuning(), terrain.settings().geometry());
                    var oldSettings = terrain.settings();
                    var oldSurface = terrain.tacticalSurface(at);
                    BoardGeometry.tune(new BoardGeometry.Tuning(1.3f, 1.2f, 1, 24, .8f));
                    terrain.update(edited, camera.camera);
                    terrain.refine(camera.camera);
                    assertSame(oldSettings, terrain.settings(), "Pending tuning must not change the displayed coordinate system");
                    assertSame(oldSurface, terrain.tacticalSurface(at));
                    BoardGeometry.tune(new BoardGeometry.Tuning(1.5f, 1.2f, 1, 27, .8f));
                    terrain.update(edited, camera.camera);
                    settle(terrain, tactical, edited, camera);
                    assertEquals(BoardGeometry.tuning(), terrain.settings().geometry());
                    assertNotSame(oldSurface, terrain.tacticalSurface(at));
                    Vector3 changedCenter = BoardGeometry.center(at, before.elevation() + 2);
                    var hit = terrain.hit(edited, new com.badlogic.gdx.math.collision.Ray(
                          new Vector3(changedCenter).add(0, 0, 1000), new Vector3(0, 0, -1)));
                    assertEquals(at, hit.coords(), "Picking switches to the same published scale and height as terrain");

                    // A fallen limb does use unit scale. Pending changes retain the displayed instance until
                    // installation, and coalesce to the latest scale rather than losing its terrain update.
                    List<BoardScene.Tile> limbTiles = new ArrayList<>(edited.tiles());
                    BoardScene.Tile limbTile = edited.tile(at);
                    limbTiles.set(limbTiles.indexOf(limbTile), limbTile.withFeatures(List.of(new BoardScene.Feature(
                          "salvage/arm", 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.LIMB))));
                    BoardScene withLimb = edited.withTiles(limbTiles);
                    terrain.update(withLimb, camera.camera);
                    settle(terrain, tactical, withLimb, camera);
                    ModelInstance oldLimb = propAt(terrain, at);
                    float oldLimbScale = oldLimb.transform.getScale(new Vector3()).x;
                    BoardGeometry.tune(new BoardGeometry.Tuning(1.5f, 1.35f, 1, 27, .8f));
                    terrain.update(withLimb, camera.camera);
                    assertTrue(terrain.busy(), "Unit scaling must rebuild fallen limb props");
                    assertSame(oldLimb, propAt(terrain, at), "A queued setting must not mutate the displayed limb");
                    BoardGeometry.tune(new BoardGeometry.Tuning(1.5f, 1.5f, 1, 27, .8f));
                    terrain.update(withLimb, camera.camera);
                    settle(terrain, tactical, withLimb, camera);
                    ModelInstance scaledLimb = propAt(terrain, at);
                    assertNotSame(oldLimb, scaledLimb);
                    assertEquals(1.5f / 1.2f, scaledLimb.transform.getScale(new Vector3()).x / oldLimbScale, .0001f);
                    assertEquals(BoardGeometry.tuning(), terrain.settings().geometry());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    TerrainLod.tune(previousLod);
                    BoardGeometry.tune(previousSettings);
                    tactical.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain detail lifecycle", failure.get()); }
    }

    @Test
    void zoomingBackReinstallsEveryCachedChunk() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(1, 1, -1, -1);
        config.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                var previousLod = TerrainLod.tuning();
                try {
                    // Fifteen chunks of flat grass, all in view at medium detail.
                    List<BoardScene.Tile> tiles = new ArrayList<>();
                    for (int x = 0; x < 40; x++) {
                        for (int y = 0; y < 24; y++) {
                            tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS,
                                  null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
                        }
                    }
                    BoardScene scene = new BoardScene(0, 40, 24, tiles, List.of(), List.of(), -1, "", List.of());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1280, 900);
                    camera.camera.zoom = BoardGeometry.width() / 33;
                    camera.center(BoardGeometry.center(new Coords(20, 12), 0));
                    terrain.update(scene, camera.camera);
                    settle(terrain, null, scene, camera);
                    List<?> medium = new ArrayList<>((List<?>) field(terrain, "chunks"));
                    for (Object chunk : medium) { assertEquals(TerrainLod.MEDIUM, field(chunk, "lod")); }
                    // Full detail everywhere and back. The cache keeps the eight levels replaced last; the first chunks
                    // to coarsen are not among them, and their builds must not evict the others first.
                    TerrainLod.tune(new TerrainLod.Tuning(1, 1));
                    settle(terrain, null, scene, camera);
                    TerrainLod.tune(TerrainLod.DEFAULTS);
                    settle(terrain, null, scene, camera);
                    List<?> back = (List<?>) field(terrain, "chunks");
                    int reused = 0;
                    for (int i = 0; i < back.size(); i++) {
                        assertEquals(TerrainLod.MEDIUM, field(back.get(i), "lod"));
                        if (back.get(i) == medium.get(i)) { reused++; }
                    }
                    assertEquals(8, reused, "Every chunk the replaced-chunk cache held installs again");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    TerrainLod.tune(previousLod);
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Cached detail on a zoom back", failure.get()); }
    }

    private static void view(BoardCamera camera, float zoom) {
        camera.camera.zoom = zoom;
        camera.center(BoardGeometry.center(new Coords(4, 4), 1));
    }

    private static void verifiesPreparedWaterTextureUpload() {
        byte[] rgba = { -1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, 17, 29, 41, -1 };
        var field = new GpuWaterShader.Field.Prepared(2, 2, 1, 0, 0, rgba, null).upload();
        try {
            field.texture.bind();
            var pixels = BufferUtils.newByteBuffer(rgba.length);
            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
            byte[] actual = new byte[rgba.length];
            pixels.get(actual);
            assertArrayEquals(rgba, actual, "Uploading prepared field pixels must start at the buffer's first byte");
        } finally { field.dispose(); }
    }

    static void settle(GpuTerrain terrain, GpuTactical tactical, BoardScene scene, BoardCamera camera) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        double maximum = 0;
        while (true) {
            long start = System.nanoTime();
            boolean changed = terrain.refine(camera.camera);
            double elapsed = (System.nanoTime() - start) / 1e6;
            maximum = Math.max(maximum, elapsed);
            if (changed) { System.out.printf("LOD install %.3f ms%n", elapsed); }
            if (tactical != null && terrain.ready(scene)) { tactical.update(terrain.presentation(scene), changed); }
            if (!changed && !terrain.busy() && terrain.ready(scene)) {
                System.out.printf("LOD maximum render-thread handoff %.3f ms%n", maximum);
                return;
            }
            assertTrue(System.nanoTime() < deadline, () -> "Visible terrain detail must settle: " + terrain.buildDetails());
            Thread.sleep(5);
        }
    }

    private static TerrainLod firstLod(GpuTerrain terrain) throws Exception {
        return (TerrainLod) field(((List<?>) field(terrain, "chunks")).getFirst(), "lod");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static ModelInstance propAt(GpuTerrain terrain, Coords at) throws Exception {
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                if (at.equals(field(prop, "coords"))) { return (ModelInstance) field(prop, "instance"); }
            }
        }
        throw new AssertionError("Missing terrain prop at " + at);
    }

    private static void capture(GpuTerrain terrain, BoardCamera camera, String name) throws Exception {
        terrain.renderShadows(camera.camera, List.of());
        try (var outputFrame = new GpuSceneTestOutput()) {
            outputFrame.begin(.2f, .26f, .31f, 1);
            terrain.render(camera.camera);
            terrain.renderTransparent(camera.camera);
            outputFrame.display();
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            GpuBoardTestUi.capture(new File(output, name));
        }
    }
}
