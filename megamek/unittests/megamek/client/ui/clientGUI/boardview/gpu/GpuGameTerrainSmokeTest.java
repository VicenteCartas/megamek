/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Game destruction updates object ownership and picking without rebuilding the installed ground. */
@Tag("on-demand")
class GpuGameTerrainSmokeTest {
    @Test
    void collapseRetainsGroundAndPlantingWhileCratersAndEditorEditsChangeOnlyTheirGround() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                try {
                    var at = new Coords(4, 4);
                    var feature = new BoardScene.Feature(GpuBuildingTest.ASSET, 0, 0, 0, 1, 2, 0,
                          BoardScene.FeatureKind.BUILDING);
                    var tiles = new java.util.ArrayList<BoardScene.Tile>();
                    for (int x = 0; x < 18; x++) {
                        for (int y = 0; y < 18; y++) { tiles.add(BoardScenes.tile(new Coords(x, y), BoardScene.Biome.NONE, 0)); }
                    }
                    var scene = new BoardScene(0, 18, 18, tiles, List.of(), List.of(), -1, "", List.of());
                    // Keep the later local crater above the existing visual floor, so outside-wall changes are separate.
                    scene = BoardScenes.replace(scene, BoardScenes.tile(new Coords(17, 17), BoardScene.Biome.NONE, -4));
                    var roadAt = new Coords(6, 7);
                    scene = BoardScenes.replace(scene, BoardScenes.tile(roadAt, BoardRoad.Kind.PAVED, 9, 0, BoardScene.Surface.GRASS));
                    scene = BoardScenes.replace(scene, scene.tile(at).withFeatures(List.of(feature)));
                    terrain.update(scene);
                    var support = terrain.tacticalSurface(at);
                    var plants = terrain.planted(at);
                    var ground = ground(terrain);
                    Vector3 center = BoardGeometry.center(at, 0);
                    Ray ray = new Ray(new Vector3(center.x, center.y, 1000), new Vector3(0, 0, -1));
                    var roof = terrain.hit(scene, ray);
                    assertNotNull(roof);
                    for (String kind : List.of("light", "medium", "heavy", "hardened", "wall",
                          "light-path", "medium-path", "heavy-path", "hardened-path", "wall-path")) {
                        scene = BoardScenes.replace(scene, scene.tile(at).withStanding(List.of(feature)));
                        terrain.update(scene);
                        var rubble = new BoardScene.Feature("scenery/rubble/" + kind, 0, 0, 0, 1, 1, 0,
                              BoardScene.FeatureKind.SCENERY, 0, true);
                        scene = BoardScenes.replace(scene, scene.tile(at).withStanding(List.of(rubble)));
                        terrain.update(scene);
                        assertSame(support, terrain.tacticalSurface(at), "Collapse retains ground: " + kind);
                        assertSame(plants, terrain.planted(at), "Exposed ground gets no new roots: " + kind);
                        assertEquals(ground, ground(terrain), "Ground GPU meshes stay installed: " + kind);
                        var after = terrain.hit(scene, ray);
                        assertNotNull(after);
                        assertNotEquals(roof, after, "The former rooftop must no longer be pickable: " + kind);
                        scene = BoardScenes.replace(scene, scene.tile(at).withStanding(List.of()));
                        terrain.update(scene);
                        assertSame(support, terrain.tacticalSurface(at), "Clearing rubble retains ground: " + kind);
                        assertSame(plants, terrain.planted(at), "Clearing rubble cannot plant: " + kind);
                        assertEquals(ground, ground(terrain), "Clearing rubble retains GPU meshes: " + kind);
                    }
                    // No stale native buffers after the retired chunk disposes its object caches.
                    var camera = new BoardCamera(); camera.resize(640, 480); camera.fit(scene);
                    for (int i = 0; i < 12; i++) { terrain.refine(camera.camera); terrain.render(camera.camera); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    // Mixed updates choose work locally: the crater cannot make a distant collapse rebuild ground.
                    scene = BoardScenes.replace(scene, scene.tile(at).withStanding(List.of(feature)));
                    terrain.update(scene);
                    Object retainedGround = GpuTestSupport.field(
                          ((List<?>) GpuTestSupport.field(terrain, "chunks")).getFirst(), "ground");
                    var mixedSupport = terrain.tacticalSurface(at);
                    scene = BoardScenes.replace(scene, scene.tile(at).withStanding(List.of()));
                    scene = BoardScenes.replace(scene, BoardScenes.tile(new Coords(15, 15), BoardScene.Biome.NONE, -2));
                    terrain.update(scene);
                    assertSame(mixedSupport, terrain.tacticalSurface(at), "Distant crater plus collapse keeps installed support");
                    BoardScene installed = GpuTestSupport.field(terrain, "coverScene");
                    assertSame(installed.ground(), terrain.presentation(scene).ground(),
                          "The public scene boundary keeps the installed ground owner");
                    assertSame(retainedGround, GpuTestSupport.field(
                          ((List<?>) GpuTestSupport.field(terrain, "chunks")).getFirst(), "ground"),
                          "Object replacement transfers the same ground owner");
                    var queries = new BoardSurface.Cache();
                    queries.installed(terrain::tacticalSurface);
                    assertEquals(BoardSurface.sampleHeight(mixedSupport.faces(), center.x, center.y, 0),
                          UnitLandingSupports.ground(scene, center.x, center.y, queries), .001f,
                          "Unit placement reads the same installed triangles as picking and rendering");
                    var unaffected = terrain.tacticalSurface(new Coords(12, 12));
                    var unaffectedRoad = terrain.tacticalSurface(roadAt);
                    scene = BoardScenes.replace(scene, BoardScenes.tile(at, BoardScene.Biome.NONE, -1));
                    var beforeImpact = terrain.tacticalSurface(at);
                    terrain.update(scene, null, false);
                    long until = System.nanoTime() + 20_000_000_000L;
                    while (terrain.buildProgress() != 100 && System.nanoTime() < until) {
                        terrain.refine(null);
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    }
                    assertEquals(100, terrain.buildProgress(), "Prepare and upload the crater before impact");
                    for (int i = 0; i < 12; i++) { terrain.refine(null); }
                    assertSame(beforeImpact, terrain.tacticalSurface(at), "Prepared terrain stays hidden until impact");
                    assertNotEquals(-1, terrain.presentation(scene).tile(at).elevation());
                    long preparedGeneration = GpuTestSupport.field(terrain, "meshGeneration");
                    terrain.update(scene);
                    assertEquals(preparedGeneration, (long) GpuTestSupport.field(terrain, "meshGeneration"),
                          "Impact publishes the prepared job instead of starting another build");
                    assertNotSame(support, terrain.tacticalSurface(at), "A crater really changes ground shape");
                    assertSame(unaffected, terrain.tacticalSurface(new Coords(12, 12)));
                    assertSame(unaffectedRoad, terrain.tacticalSurface(roadAt), "A crater keeps the unaffected road in its own chunk");
                    assertNull(terrain.planted(at), "A crater removes unsupported plants");
                    terrain.setEditable(true);
                    scene = BoardScenes.replace(scene, BoardScenes.tile(at, BoardScene.Biome.NONE, 0));
                    terrain.update(scene);
                    // A meadow's blades are not planted (GpuGroundMap): the edit shows in the hex's ground alone.
                    assertNotSame(beforeImpact, terrain.tacticalSurface(at), "Explicit map editing rebuilds the hex's ground");
                    terrain.setEditable(false);
                    terrain.update(BoardScenes.replace(scene, BoardScenes.tile(at, BoardScene.Biome.NONE, -2)), null, false);
                    terrain.refine(null);
                    terrain.boardChanged();
                    scene = BoardScenes.scene(p -> BoardScenes.tile(p, BoardScene.Biome.NONE, 0));
                    var shrub = new BoardScene.Feature("scatter-plant", 3, 4, 30, 1, 1, 0, BoardScene.FeatureKind.SCATTER);
                    scene = BoardScenes.replace(scene, scene.tile(at).withFeatures(List.of(shrub)));
                    terrain.update(scene);
                    var unburnedSupport = terrain.tacticalSurface(at);
                    var unburnedGround = ground(terrain);
                    scene = BoardScenes.replace(scene, scene.tile(at).withoutGroundCover(false));
                    terrain.update(scene);
                    assertSame(unburnedSupport, terrain.tacticalSurface(at), "Burning plants does not rebuild ground");
                    assertEquals(unburnedGround, ground(terrain));
                    assertNull(terrain.planted(at));
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        assertTrue(((List<?>) GpuTestSupport.field(chunk, "vegetation")).isEmpty(), "Burned shrubs must disappear");
                    }
                    var planting = (BoardPlanting) GpuTestSupport.field(terrain, "planting");
                    boolean checkedField = false;
                    for (var tile : scene.tiles()) {
                        if (!tile.features().isEmpty() || tile.barren()) { continue; }
                        var field = planting.support(tile.coords());
                        int stones = (int) field.faces.stream().filter(BoardSurface.Face::scatter)
                              .filter(f -> field.relief.shade(f.a()).kind() == BoardRelief.Kind.ROCK).count();
                        int fieldFaces = (int) field.faces.stream().filter(BoardSurface.Face::scatter).count();
                        if (stones == 0 || fieldFaces == stones) { continue; }
                        assertEquals(fieldFaces * 3, scatterIndices(terrain, tile.coords()));
                        scene = BoardScenes.replace(scene, tile.withoutGroundCover(false));
                        terrain.update(scene);
                        assertEquals(0, scatterIndices(terrain, tile.coords()),
                              "Scorching removes every decorative stone and shrub without rebuilding ground");
                        assertEquals(unburnedGround, ground(terrain));
                        checkedField = true;
                        break;
                    }
                    assertTrue(checkedField, "The fixture must exercise a field with both stones and shrubs");
                    // The real game action also adds a ground decal and keeps piles beside the cleared path.
                    // Ultra rubble and unknown grades use the game's heavy-rubble artwork fallback.
                    for (int grade = 1; grade <= 7; grade++) {
                        var rubbleAt = new Coords(0, 0);
                        var hex = new megamek.common.Hex(0, "rubble:" + grade, "", rubbleAt);
                        var board = new megamek.common.board.Board(1, 1, hex);
                        terrain.boardChanged();
                        scene = BoardScenes.scene(board);
                        terrain.update(scene);
                        var rubbleSupport = terrain.tacticalSurface(rubbleAt);
                        var rubblePlants = terrain.planted(rubbleAt);
                        var rubbleGround = ground(terrain);
                        megamek.common.units.BulldozerRules.clearRubble(hex);
                        // As a game's capture does: the hex's new tile keeps what shaped its ground.
                        var cleared = BoardScenes.scene(board);
                        scene = BoardScenes.replace(cleared, cleared.tile(rubbleAt).since(scene.tile(rubbleAt)));
                        terrain.update(scene);
                        assertSame(rubbleSupport, terrain.tacticalSurface(rubbleAt), "Bulldozer grade " + grade);
                        assertSame(rubblePlants, terrain.planted(rubbleAt), "No planting after bulldozing grade " + grade);
                        assertEquals(rubbleGround, ground(terrain), "Bulldozing keeps ground GPU meshes: " + grade);
                        Object chunk = ((List<?>) GpuTestSupport.field(terrain, "chunks")).getFirst();
                        assertFalse(((List<?>) GpuTestSupport.field(chunk, "authoredPaint")).isEmpty(),
                              "The cleared path's new paint must still draw: " + grade);
                        camera.fit(scene);
                        terrain.render(camera.camera);
                        terrain.renderTransparent(camera.camera);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Gameplay terrain updates", failure.get()); }
    }

    private static List<ModelInstance> ground(GpuTerrain terrain) throws Exception {
        var result = new java.util.ArrayList<ModelInstance>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            result.addAll((List<ModelInstance>) GpuTestSupport.field(GpuTestSupport.field(chunk, "ground"), "opaque"));
        }
        return result;
    }

    private static int scatterIndices(GpuTerrain terrain, Coords at) throws Exception {
        int count = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            var tile = ((java.util.Map<?, ?>) GpuTestSupport.field(chunk, "tileMeshes")).get(at);
            if (tile == null) { continue; }
            for (Object range : (List<?>) GpuTestSupport.field(tile, "ranges")) {
                if (GpuTestSupport.field(range, "layer") == GpuTerrain.Layer.SCATTER) { count += (int) GpuTestSupport.field(range, "count"); }
            }
        }
        return count;
    }
}
