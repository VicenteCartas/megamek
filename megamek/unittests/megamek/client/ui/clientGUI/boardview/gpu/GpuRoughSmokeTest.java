/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.NumberUtils;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native material, shadow, picking and edit checks for the authored rough variants. */
@Tag("on-demand")
class GpuRoughSmokeTest {
    @Test
    void roughVariantsRenderAndUpdateInTheSharedBoard() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "rough-variants");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    BoardScene scene = scene(1);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    for (boolean oblique : new boolean[] { true, false }) {
                        camera.setIsometric(oblique);
                        camera.camera.zoom = .52f;
                        camera.center(BoardGeometry.center(new Coords(5, 3), 0));
                        // Loading must see the same shader flags as the frame that follows (GpuReviewFrame.prepare).
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene, camera.camera);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, oblique ? "overview-oblique.png" : "overview-top.png"));
                        checkPicking(terrain, scene, new Coords(5, 3), BoardSurface.Finish.ROUGH);
                        checkPicking(terrain, scene, new Coords(8, 3), BoardSurface.Finish.ROUGH);
                    }
                    camera.setIsometric(true);
                    for (int x : new int[] { 2, 5, 8 }) {
                        camera.camera.zoom = .17f;
                        camera.center(BoardGeometry.center(new Coords(x, 3), 0));
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, x == 2 ? "boulders.png" : x == 5 ? "dragon-teeth.png" : "felled-woods.png"));
                        if (x == 2) { checkBoulderMaterials(terrain, scene, new Coords(2, 2)); }
                    }
                    var before = terrain.tacticalSurface(new Coords(5, 3));
                    BoardScene edited = scene(2);
                    terrain.update(edited, camera.camera);
                    GpuReviewFrame.settle(terrain, null, edited, camera);
                    assertNotSame(before, terrain.tacticalSurface(new Coords(5, 3)), "Fluff edits replace support geometry");
                    checkPicking(terrain, edited, new Coords(5, 3), BoardSurface.Finish.ROUGH);
                    frame.render(terrain, camera, edited);
                    // Without gravity the boulders give way to bedrock outcrops, which keep their support and picking.
                    frame.configure(new BoardAtmosphere.Settings(10, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
                          0, 0, BoardAtmosphere.Effects.NONE, Atmosphere.VACUUM));
                    terrain.setGravity(0);
                    terrain.update(scene, camera.camera);
                    for (boolean low : new boolean[] { false, true }) {
                        camera.setIsometric(true);
                        if (low) { camera.orbit(-70, 20); }
                        camera.camera.zoom = .17f;
                        camera.center(BoardGeometry.center(new Coords(3, 3), 0));
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, low ? "zero-gravity-low.png" : "zero-gravity.png"));
                    }
                    checkPicking(terrain, scene, new Coords(2, 3), BoardSurface.Finish.OUTCROP);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    BoardGeometry.tune(original);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Rough variant rendering", failure.get()); }
    }

    private static void checkBoulderMaterials(GpuTerrain terrain, BoardScene scene, Coords coords) throws Exception {
        var surface = new BoardSurface(scene, scene.tile(coords));
        Set<Vector3> rock = new HashSet<>();
        for (var face : surface.rough) { rock.addAll(List.of(face.a(), face.b(), face.c())); }
        assertFalse(rock.isEmpty());
        Map<Mesh, float[]> vertices = new IdentityHashMap<>();
        int checked = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object value : (Array<?>) GpuTestSupport.field(chunk, "terrainRenderables")) {
                Renderable part = (Renderable) value;
                Mesh mesh = part.meshPart.mesh;
                int stride = mesh.getVertexSize() / Float.BYTES;
                int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
                int color = mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                float[] data = vertices.computeIfAbsent(mesh, key -> mesh.getVertices(new float[mesh.getNumVertices() * stride]));
                short[] indices = new short[part.meshPart.size];
                mesh.getIndices(part.meshPart.offset, part.meshPart.size, indices, 0);
                for (short index : indices) {
                    int at = Short.toUnsignedInt(index) * stride;
                    if (!rock.contains(new Vector3(data[at + position], data[at + position + 1], data[at + position + 2]))) { continue; }
                    assertTrue(part.material.has(Attribute.getAttributeType("boardSculpt")),
                          "Roadside boulders must use the same textured geology as neighboring rough");
                    assertTrue(part.material.has(Attribute.getAttributeType("boardSculptLayers")));
                    assertEquals(255, (NumberUtils.floatToIntColor(data[at + color]) >>> 16) & 255,
                          "The shared shader must receive the rock material tag");
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "Check actual uploaded boulder triangles");
    }

    private static void checkPicking(GpuTerrain terrain, BoardScene scene, Coords coords, BoardSurface.Finish finish) {
        var faces = terrain.tacticalSurface(coords).faces().stream().filter(face -> face.finish() == finish).toList();
        assertFalse(faces.isEmpty());
        var face = faces.stream().max(java.util.Comparator.comparingDouble(f -> f.a().z + f.b().z + f.c().z)).orElseThrow();
        Vector3 p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
        var hit = terrain.hit(scene, new Ray(new Vector3(p.x, p.y, 200), new Vector3(0, 0, -1)));
        assertNotNull(hit);
        assertEquals(coords, hit.coords());
        assertEquals(200 - BoardSurface.sampleHeight(faces, p.x, p.y, Float.NaN), Math.sqrt(hit.distance()), .002);
    }

    private static BoardScene scene(int teethVariant) {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 11; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                Hex hex = new Hex(y > 4 ? 1 : 0);
                if (x >= 2 && x <= 9 && x % 3 != 1 && y >= 2 && y <= 4) {
                    hex.addTerrain(new Terrain(Terrains.ROUGH, y == 4 ? 2 : 1));
                    if (x >= 5) { hex.addTerrain(new Terrain(Terrains.FLUFF, x >= 8 ? 2 : teethVariant)); }
                }
                if (y == 2) { hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 18)); }
                tiles.add(new BoardScene.Tile(coords, hex.getLevel(), -1, false, y == 2 ? 18 : 0,
                      BoardFeatures.surface(hex), null, null,
                      BoardFeatures.capture(hex, coords, Map.of()), List.of(), BoardLiquid.NONE, null,
                      BoardFeatures.detailedGround(hex, Map.of()), BoardRoad.capture(hex)));
            }
        }
        return new BoardScene(0, 11, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
