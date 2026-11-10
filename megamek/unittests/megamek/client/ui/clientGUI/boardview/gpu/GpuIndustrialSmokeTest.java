/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Procedural industrial geometry, shared picking, native ownership and non-occupiable terrain semantics. */
@Tag("on-demand")
class GpuIndustrialSmokeTest {
    private static final List<Coords> SITES = List.of(new Coords(2, 2), new Coords(3, 2),
          new Coords(4, 4), new Coords(6, 4));

    private static String asset(int index) { return "buildings/saxarba/misc/heavy_industrial_" + (char) ('a' + index); }

    @Test
    void industrialKitsKeepGroundAccessAndNeverBecomeBuildingCutaways() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkKits();
                    checkTerrain();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Industrial terrain review", failure.get()); }
    }

    private static void checkKits() {
        var assets = new GpuAssets();
        try {
            for (String name : List.of("paint", "steel")) {
                var texture = assets.material("structures/industrial/" + name);
                assertTrue(texture.getWidth() >= 1024, "Photographic materials retain source resolution");
                texture.bind();
                assertEquals(0, org.lwjgl.opengl.GL11.glGetTexParameteri(GL20.GL_TEXTURE_2D,
                      com.badlogic.gdx.graphics.GL30.GL_TEXTURE_BASE_LEVEL), "Industrial detail is not clamped to 128px");
            }
            var fan = assets.material("structures/industrial/fan");
            assertEquals(128, fan.getWidth());
            assertEquals(128, fan.getHeight());
            for (int family = 0; family < 4; family++) {
                for (int height : new int[] { 1, 7, 10 }) {
                    var scene = BoardIndustrialTest.scene(Map.of(BoardIndustrialTest.CENTER, family), height, 0);
                    var layout = BoardIndustrialTest.layout(scene, BoardIndustrialTest.CENTER);
                    var model = assets.industrial(layout);
                    assertSame(model, assets.industrial(layout), "Unchanged layouts reuse their GPU model");
                    assertEquals(height * 18, model.calculateBoundingBox(new BoundingBox()).max.z, .001f);
                    for (var material : model.materials) {
                        if (material.id.equals("fan")) {
                            assertSame(fan, material.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture,
                                  "All fans share one small asset-owned texture");
                            // Its finish (GpuModelMaterial) is one of the attributes the prop batch takes.
                            assertEquals(ColorAttribute.Diffuse | TextureAttribute.Diffuse | GpuModelMaterial.TYPE,
                                  material.getMask(), "Opaque fan panels remain eligible for the static prop batch");
                        }
                    }
                    int fanIndices = 0;
                    for (var part : model.meshParts) { if (part.id.equals("fan")) { fanIndices += part.size; } }
                    assertEquals(family == 0 ? 12 : 0, fanIndices, "Each of the two generators needs only one fan quad");
                    assets.retain(assets, java.util.Set.of(), java.util.Set.of(model), java.util.Set.of());
                }
            }
        } finally { assets.dispose(); }
    }

    private static BoardScene scene(int height) {
        return scene(height, true);
    }

    private static BoardScene scene(int height, boolean neighbor) {
        return BoardScenes.scene(coords -> {
            var tile = BoardScenes.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            int index = SITES.indexOf(coords);
            if (!neighbor && index == 1) { index = -1; }
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, height + Math.max(0, index)));
            return new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), null, null,
                  index < 0 ? List.of() : BoardFeatures.capture(hex, coords, Map.of(Terrains.INDUSTRIAL, asset(index))),
                  List.of(), BoardLiquid.NONE, null, true);
        });
    }

    private static void checkTerrain() throws Exception {
        var terrain = new GpuTerrain();
        var settings = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
              0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
        var frame = new GpuReviewFrame(settings);
        var camera = new BoardCamera();
        camera.resize(1280, 960);
        var marker = new ModelBuilder().createBox(8, 8, 12, new Material(),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "heavy-industrial");
        assertTrue(output.isDirectory() || output.mkdirs());
        try {
            for (int height : new int[] { 4, 7 }) {
                BoardScene scene = scene(height);
                terrain.update(scene);
                saveGeometry(scene, output);
                camera.setIsometric(true);
                camera.fit(scene);
                camera.zoom(.55f);
                camera.center(BoardGeometry.center(new Coords(4, 3), 2));
                for (boolean tactical : new boolean[] { false, true }) {
                    // The Classic graphics build the tileset columns and keep the industrial structures.
                    terrain.setColumns(tactical);
                    terrain.update(scene);
                    frame.prepare(terrain, camera, scene);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    assertTrue(terrain.ready(scene));
                    for (Coords coords : SITES) {
                        var center = BoardGeometry.center(coords, 0);
                        var equipment = BoardIndustrialTest.layout(scene, coords).equipment().getFirst();
                        var unit = new ModelInstance(marker, new Vector3(center).add(0, 0, 6));
                        terrain.animate(0, List.of(unit), .15f);
                        var ray = new Ray(new Vector3(center).add(equipment.x() * BoardGeometry.hexScale(),
                              equipment.y() * BoardGeometry.hexScale(), 400),
                              new Vector3(0, 0, -1));
                        var selection = terrain.selectionHit(scene, ray);
                        assertNotNull(selection);
                        assertEquals(coords, selection.coords());
                        assertEquals(400, Math.sqrt(selection.distance()), .15f, "Hover passes through machinery to ground");
                        var physical = terrain.hit(scene, ray);
                        assertNotNull(physical);
                        assertTrue(physical.distance() < selection.distance() - 1, "Effects still hit the physical equipment");
                    }
                    checkNoCutaways(terrain);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(output, "height-" + height + (tactical ? "-tactical" : "-3d") + ".png"));
                    if (!tactical && height == 4) {
                        float zoom = camera.camera.zoom;
                        camera.camera.zoom = .11f;
                        camera.center(BoardGeometry.center(SITES.get(1), 2));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "materials-close.png"));
                        for (int family : new int[] { 0, 1, 3 }) {
                            var layout = BoardIndustrialTest.layout(scene, SITES.get(family));
                            var machine = family == 0 ? layout.equipment().get(1) : family == 1
                                  ? layout.equipment().stream().filter(e -> e.cap() == BoardIndustrial.Cap.FLAT).findFirst().orElseThrow()
                                  : layout.equipment().getFirst();
                            camera.camera.zoom = .055f;
                            camera.center(BoardGeometry.center(SITES.get(family), 0).add(machine.x(), machine.y(), machine.height()));
                            for (int angle = 0; angle < 4; angle++) {
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "cap-" + family + "-orbit-" + angle + ".png"));
                                camera.orbit(90, 0);
                            }
                        }
                        camera.camera.zoom = zoom;
                        camera.center(BoardGeometry.center(new Coords(4, 3), 2));
                    }
                    camera.zoom(5);
                    frame.render(terrain, camera, scene);
                    checkNoCutaways(terrain);
                    camera.zoom(.2f);
                }
            }
            terrain.setColumns(false);
            BoardScene connected = scene(4);
            terrain.update(connected);
            // Until the 3D terrain is installed again a pick finds nothing.
            GpuReviewFrame.settle(terrain, null, connected, camera);
            var port = BoardIndustrialTest.layout(connected, SITES.getFirst()).ports().getFirst();
            var joint = BoardGeometry.center(SITES.getFirst(), 0).add(port.x() - port.dx() * .5f, port.y() - port.dy() * .5f, 0);
            var ray = new Ray(joint.add(0, 0, 400), new Vector3(0, 0, -1));
            float before = terrain.hit(connected, ray).distance();
            BoardScene isolated = scene(4, false);
            terrain.update(isolated);
            GpuReviewFrame.settle(terrain, null, isolated, camera);
            assertTrue(terrain.hit(isolated, ray).distance() > before + 1,
                  "Removing a neighbor rebuilds the surviving installation without its connecting pipe");
        } finally {
            marker.dispose();
            frame.dispose();
            terrain.dispose();
        }
    }

    private static void checkNoCutaways(GpuTerrain terrain) throws Exception {
        int count = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            assertTrue(((List<?>) GpuTestSupport.field(chunk, "struts")).isEmpty());
            assertTrue(((java.util.Set<?>) GpuTestSupport.field(chunk, "faded")).isEmpty());
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "cutaways")) {
                count++;
                assertTrue((boolean) GpuTestSupport.field(prop, "industrial"));
                var instance = (ModelInstance) GpuTestSupport.field(prop, "instance");
                for (var material : instance.materials) {
                    assertFalse(material.has(BlendingAttribute.Type));
                }
                assertNull(GpuTestSupport.field(prop, "building"));
            }
        }
        assertEquals(4, count, "Only the four industrial shells, without generated interior floors");
    }

    private static void saveGeometry(BoardScene scene, File output) throws java.io.IOException {
        var json = new StringBuilder("{\"models\":[");
        for (int i = 0; i < SITES.size(); i++) {
            if (i > 0) { json.append(','); }
            var data = BoardIndustrial.model(BoardIndustrialTest.layout(scene, SITES.get(i)));
            var position = BoardGeometry.center(SITES.get(i), 0);
            json.append("{\"name\":\"").append((char) ('a' + i)).append("\",\"vertices\":")
                  .append(java.util.Arrays.toString(data.meshes.first().vertices))
                  .append(",\"position\":").append(java.util.Arrays.toString(new float[] { position.x, position.y, position.z }))
                  .append(",\"paintTriangles\":").append(partTriangles(data, "paint"))
                  .append(",\"fanTriangles\":").append(partTriangles(data, "fan"))
                  .append('}');
        }
        java.nio.file.Files.writeString(new File(output, "geometry.json").toPath(), json.append("]}").toString());
    }

    private static String partTriangles(ModelData data, String id) {
        var part = java.util.Arrays.stream(data.meshes.first().parts).filter(value -> value.id.equals(id)).findFirst().orElse(null);
        return part == null ? "[]" : java.util.Arrays.toString(java.util.stream.IntStream.range(0, part.indices.length / 3)
              .map(index -> Short.toUnsignedInt(part.indices[index * 3]) / 3).toArray());
    }
}
