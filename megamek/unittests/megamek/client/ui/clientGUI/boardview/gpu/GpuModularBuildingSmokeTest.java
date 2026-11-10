/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GLB import, native lifetime, terrain placement, cutaway and LOD-independent picking. */
@Tag("on-demand")
class GpuModularBuildingSmokeTest {
    @Test
    void assembledBuildingsPreserveXyOriginsThroughEditsAndCameraChanges() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkKit();
                    checkLightBuilding();
                    GpuResourcesSmokeTest.checkInteriorCourtyard(1);
                    GpuResourcesSmokeTest.checkInteriorCourtyard(BoardGeometry.MODEL_LEVEL_HEIGHT);
                    checkTerrain();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Modular building review", failure.get()); }
    }

    private static void checkKit() {
        var textures = new HashMap<String, Texture>();
        var authoredBounds = new java.util.ArrayList<Map<String, BoundingBox>>();
        var lods = GpuBuildingTest.lods(GpuBuildingTest.ASSET);
        // Preserve both baked and node XY offsets, including the modules' asymmetric details.
        for (var data : lods.stream().distinct().toList()) {
            for (var mesh : data.meshes) {
                for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                    mesh.vertices[i] += 71;
                    mesh.vertices[i + 1] -= 23;
                    mesh.vertices[i + 2] += 43;
                }
            }
            for (var node : data.nodes) { node.translation.add(900, -400, 257); }
        }
        GpuBuilding kit = new GpuBuilding(ConnectedKit.load(lods), data -> {
            Model model = ModelTextures.create(data, textures,
                  (path, color) -> { throw new AssertionError("Expected embedded textures"); });
            Map<String, BoundingBox> bounds = new HashMap<>();
            // By node: a role can have several pieces (a whole-hex piece beside its core one).
            ConnectedKit.parts(data).forEach(piece ->
                  bounds.put(piece.node(), model.getNode(piece.node()).calculateBoundingBox(new BoundingBox())));
            authoredBounds.add(bounds);
            return model;
        });
        int[] alone = new int[6];
        try {
            var five = kit.assemble(5, alone, 7, true);
            assertSame(five, kit.assemble(5, alone, 7, true), "Repeated loads/edits reuse compact recipes");
            var cover = kit.assemble(5, alone, 7, false);
            assertNull(cover.interior(), "Industrial use of the same kit must not borrow a building's interior");
            assertEquals(five.recipe().modules(), cover.recipe().modules());
            assertSame(five.model(0), cover.model(0), "Different terrain semantics still share native geometry");
            assertSame(cover, kit.assemble(5, alone, 7, false));
            var another = kit.assemble(5, alone, 19, true);
            assertSame(five.interior(), another.interior(), "Variants share the same volume's interior");
            for (int count : new int[] { 1, 5, 10 }) {
                var assembly = kit.assemble(count, alone, 7, true);
                assertEquals(count + 1, assembly.recipe().modules().size(), "Only module choices are cached");
                for (int lod = 0; lod < assembly.lodCount(); lod++) {
                    ModelInstance instance = assembly.instance(lod);
                    assertSame(assembly.model(lod), instance.model, "No expanded model per combination");
                    // A tall enough hex carries its roof's aviation lights as nodes of their own, after its storeys and roof.
                    int lights = 0;
                    for (var node : instance.nodes) { if (node.id.contains("-aol")) { lights++; } }
                    assertTrue(lights == 0 || count >= ConnectedKit.LIT_LEVELS, "Aviation lights stand only on tall hexes");
                    assertEquals(count + 1 + lights, instance.nodes.size);
                    for (int level = 0; level <= count; level++) {
                        var node = instance.nodes.get(level);
                        var bounds = node.calculateBoundingBox(new BoundingBox());
                        var authored = authoredBounds.get(lod).get(node.id.substring(0, node.id.indexOf('@')));
                        assertEquals(level * 18, bounds.min.z, .001f, "Saved vertical offsets must be discarded");
                        assertEquals(authored.min.x, bounds.min.x, .001f);
                        assertEquals(authored.max.x, bounds.max.x, .001f);
                        assertEquals(authored.min.y, bounds.min.y, .001f);
                        assertEquals(authored.max.y, bounds.max.y, .001f);
                        if (level < count) { assertEquals(18, bounds.getDepth(), .001f); }
                    }
                    for (var node : instance.nodes) {
                        for (var part : node.parts) {
                            assertTrue(instance.model.meshes.contains(part.meshPart.mesh, true), "Native buffers are shared");
                        }
                    }
                }
                var floors = new ModelInstance(assembly.interior(), "floors").calculateBoundingBox(new BoundingBox());
                assertEquals(count == 1 ? .036f : (count - 1) * 18, floors.max.z, .001f);
                var struts = new ModelInstance(assembly.interior(), "struts").calculateBoundingBox(new BoundingBox());
                assertEquals(count * 18, struts.getDepth(), .001f);
                var triangles = GpuTerrain.triangles(assembly.interior());
                // Probe inside the translated front wall, leaving room for its authored thickness.
                assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(972, -451, 250), new Vector3(0, 0, -1)),
                      triangles, new Vector3()), "A lone hex fills its front at every floor");
                assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(959, -423, 250), new Vector3(0, 0, -1)),
                      triangles, new Vector3()));
                Vector3 hit = new Vector3();
                assertFalse(assembly.hit(new Ray(new Vector3(0, 0, 250), new Vector3(0, 0, -1)), hit),
                      "Picking must not recenter the building");
                assertTrue(assembly.hit(new Ray(new Vector3(971, -423, 250), new Vector3(0, 0, -1)), hit));
                assertEquals(count * 18 + 1.2f, hit.z, .002f, "LOD0 roof picking includes the roof slab's thickness");
            }
            var mesh = five.model(0).meshes.first();
            kit.retain(Set.of(five));
            assertEquals(1, kit.assemblyCount());
            assertEquals(1, kit.interiorCount());
            assertSame(mesh, kit.assemble(10, alone, 3, true).model(0).meshes.first(), "Every height shares module buffers");
            kit.retain(Set.of());
            assertEquals(0, kit.assemblyCount());
            assertEquals(0, kit.interiorCount());
            assertSame(mesh, kit.assemble(1, alone, 9, true).model(0).meshes.first(), "An edit reuses the loaded kit");
        } finally {
            kit.dispose();
            textures.values().forEach(Texture::dispose);
        }
    }

    private static void checkLightBuilding() {
        var assets = new GpuAssets();
        try {
            var building = assets.building("buildings/standard/building-light", 5, new int[6], 0, true);
            assertNotNull(building);
            var floors = new ModelInstance(building.interior(), "floors").calculateBoundingBox(new BoundingBox());
            // A lone hex stands on its whole-hex pieces: its floors fill it inside the walls.
            var whole = building.instance(0).calculateBoundingBox(new BoundingBox());
            assertTrue(floors.getWidth() < whole.getWidth() && floors.getHeight() < whole.getHeight(),
                  "Floors stop at the external walls, before the roof border");
            var struts = new ModelInstance(building.interior(), "struts").calculateBoundingBox(new BoundingBox());
            assertTrue(floors.min.x <= struts.min.x && floors.max.x >= struts.max.x);
            assertTrue(floors.min.y <= struts.min.y && floors.max.y >= struts.max.y);
        } finally { assets.dispose(); }
    }

    private static BoardScene scene(int middleHeight, String asset) {
        return BoardScenes.scene(coords -> {
            int levels = coords.equals(new Coords(2, 4)) ? 1 : coords.equals(new Coords(4, 4)) ? middleHeight
                  : coords.equals(new Coords(6, 4)) ? 10 : 0;
            var tile = BoardScenes.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            int type = coords.getX() == 2 ? Terrains.INDUSTRIAL
                  : coords.getX() == 4 ? Terrains.BUILDING : Terrains.FUEL_TANK;
            var hex = new Hex(0);
            hex.addTerrain(new Terrain(type, type == Terrains.INDUSTRIAL ? levels : 1));
            if (type != Terrains.INDUSTRIAL) {
                hex.addTerrain(new Terrain(type == Terrains.BUILDING ? Terrains.BLDG_ELEV : Terrains.FUEL_TANK_ELEV, levels));
            }
            return new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), null, null,
                  levels == 0 ? List.of() : BoardFeatures.capture(hex, coords, Map.of(type, asset)),
                  List.of(), BoardLiquid.NONE, null, true);
        });
    }

    private static void checkTerrain() throws Exception {
        String hangar = "buildings/standard/hangar-light";
        GpuAssets assets = new GpuAssets();
        try {
            assertNotNull(assets.building(hangar, 5, new int[6], 4, true), "Hangars use their current modular kit");
            assertNotNull(assets.model(GpuBuildingTest.ASSET), "The default building kit loads");
        } finally { assets.dispose(); }
        var terrain = new GpuTerrain();
        var settings = new BoardAtmosphere.Settings(13, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
              0, 0, new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0));
        var frame = new GpuReviewFrame(settings);
        var camera = new BoardCamera();
        camera.resize(1280, 960);
        BoardScene scene = scene(5, GpuBuildingTest.ASSET);
        var tuning = BoardGeometry.tuning();
        try {
            BoardScene hangars = scene(5, hangar);
            terrain.update(hangars);
            for (int x : new int[] { 2, 4, 6 }) {
                var coords = new Coords(x, 4);
                // A hangar's vault rises over its eaves, which stand at the gameplay height: half a level at the most.
                float eaves = hangars.tile(coords).features().getFirst().height() * BoardGeometry.level();
                float top = terrain.roofBounds(coords).max.z;
                assertTrue(top >= eaves - BoardGeometry.level() * .1f && top <= eaves + BoardGeometry.level() * .6f,
                      "The kit follows the gameplay height: " + top + " over eaves at " + eaves);
            }
            checkFloorCutaways(terrain, hangars, frame, camera);
            terrain.update(scene);
            checkFloorCutaways(terrain, scene, frame, camera);
            camera.setIsometric(true);
            camera.fit(scene);
            camera.zoom(.55f);
            camera.center(BoardGeometry.center(new Coords(4, 4), 2));
            terrain.animate(0, List.of());
            for (int i = 0; i < 4; i++) { frame.render(terrain, camera, scene); }
            long uploads = terrain.treeInstanceUploads();
            assertTrue(uploads > 0, "Building modules use the existing GPU instancing path");
            long bytes = terrain.treeGeometryBytes();
            frame.render(terrain, camera, scene);
            assertEquals(uploads, terrain.treeInstanceUploads(), "An unchanged frame uploads no module instances");
            assertEquals(bytes, terrain.treeGeometryBytes(), "An unchanged frame allocates no module buffers");
            File output = new File(System.getProperty("megamek.gpu.screenshots"), "modular-buildings");
            assertTrue(output.isDirectory() || output.mkdirs());
            GpuReviewFrame.save(new File(output, "one-five-ten-levels.png"));
            checkPicking(terrain, scene);
            camera.zoom(8);
            for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
            GpuReviewFrame.save(new File(output, "distant-shells.png"));
            checkPicking(terrain, scene);
            Vector3 occupant = BoardGeometry.center(new Coords(4, 4), 1);
            Model marker = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createBox(12, 12, 12,
                  new com.badlogic.gdx.graphics.g3d.Material(), com.badlogic.gdx.graphics.VertexAttributes.Usage.Position
                        | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
            try {
                terrain.animate(0, List.of(new ModelInstance(marker, occupant)), .15f);
                for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                GpuReviewFrame.save(new File(output, "distant-cutaway.png"));
                camera.zoom(.125f);
                for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                GpuReviewFrame.save(new File(output, "interior-cutaway.png"));
            } finally { marker.dispose(); }
            scene = scene(3, GpuBuildingTest.ASSET);
            terrain.update(scene);
            checkPicking(terrain, scene);
            BoardGeometry.tune(new BoardGeometry.Tuning(tuning.hexScale(), tuning.unitScale(), tuning.unitHeightScale(),
                  12, tuning.gridShade()));
            terrain.update(scene);
            // A retune rebuilds every chunk: until the view settles, a pick meets their shapes only.
            GpuCalibrationScenes.settle(terrain, frame, camera, scene);
            checkPicking(terrain, scene);
        } finally {
            BoardGeometry.tune(tuning);
            frame.dispose();
            terrain.dispose();
        }
    }

    static List<ModelInstance> floors(GpuTerrain terrain, Coords coords) throws Exception {
        List<ModelInstance> floors = new java.util.ArrayList<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "cutaways")) {
                if ((boolean) GpuTestSupport.field(prop, "floors") && coords.equals(GpuTestSupport.field(prop, "coords"))) {
                    floors.add((ModelInstance) GpuTestSupport.field(prop, "instance"));
                }
            }
        }
        return floors;
    }

    private static void checkFloorCutaways(GpuTerrain terrain, BoardScene scene, GpuReviewFrame frame,
          BoardCamera camera) throws Exception {
        Coords coords = new Coords(4, 4);
        assertTrue(floors(terrain, coords).isEmpty(), "A closed building's interior is built when a unit first stands in it");
        Model model = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder().createBox(8, 8, 10,
              new com.badlogic.gdx.graphics.g3d.Material(), com.badlogic.gdx.graphics.VertexAttributes.Usage.Position
                    | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
        camera.setIsometric(false);
        camera.camera.zoom = .25f;
        camera.center(BoardGeometry.center(coords, 2));
        // The view's programs built: until then a pick finds nothing (GpuTerrain#ready).
        GpuCalibrationScenes.settle(terrain, frame, camera, scene);
        try {
            checkInteriorCache(terrain, false);
            Ray roofRay = new Ray(BoardGeometry.center(coords, 0).add(0, 0, 400), new Vector3(0, 0, -1));
            var shellHit = terrain.selectionHit(scene, roofRay);
            // Units on two floors: the hex shows all of its floors, see-through like its shell.
            ModelInstance low = new ModelInstance(model, BoardGeometry.center(coords, 1).add(0, 0, 5.5f));
            ModelInstance high = new ModelInstance(model, BoardGeometry.center(coords, 4).add(0, 0, 5.5f));
            for (float wallAlpha : new float[] { 0, .5f }) {
                terrain.animate(0, List.of(low, high), wallAlpha);
                checkInteriorCache(terrain, true);
                List<ModelInstance> floors = floors(terrain, coords);
                assertEquals(1, floors.size(), "A hex's floors are one prop: it shows all of them or none");
                for (var material : floors.getFirst().materials) {
                    var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
                    assertNotNull(blend, "The floors turn see-through with their shell");
                    assertTrue(blend.opacity > wallAlpha && blend.opacity < 1, "The floors remain more visible than the walls");
                    assertFalse(material.get(DepthTestAttribute.class, DepthTestAttribute.Type).depthMask);
                }
                frame.render(terrain, camera, scene);
                assertEquals(shellHit, terrain.selectionHit(scene, roofRay), "Cutaways must keep the shell's picking geometry");
            }
            terrain.animate(0, List.of());
            checkInteriorCache(terrain, false);
            // At full building opacity nothing fades.
            terrain.animate(0, List.of(low), 1);
            checkInteriorCache(terrain, false);
            for (ModelInstance floor : floors(terrain, coords)) {
                for (var material : floor.materials) {
                    assertFalse(material.has(BlendingAttribute.Type), "Leaving restores the floors");
                }
            }
        } finally { model.dispose(); }
    }

    static void checkInteriorCache(GpuTerrain terrain, boolean visible) throws Exception {
        int interiors = 0, floors = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            var renderables = new com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g3d.Renderable>();
            ((com.badlogic.gdx.graphics.g3d.RenderableProvider) GpuTestSupport.field(chunk, "solidProps"))
                  .getRenderables(renderables, null);
            for (Object value : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "sharedProps")) {
                renderables.add((com.badlogic.gdx.graphics.g3d.Renderable) value);
            }
            for (var part : renderables) {
                if (part.material.id.equals("struts")) { interiors++; }
                if (part.material.id.equals("floors")) { floors++; }
            }
            for (String field : List.of("sharedShadows", "shadowPropRenderables")) {
                for (Object value : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, field)) {
                    var part = (com.badlogic.gdx.graphics.g3d.Renderable) value;
                    assertFalse(part.material.id.equals("struts") || part.material.id.equals("floors"),
                          "Interiors never cast exterior shadows: the opaque shell does");
                }
            }
        }
        assertEquals(visible, interiors > 0, "Cutaway struts are submitted only while an occupied shell is faded");
        assertEquals(0, floors, "A closed building's floors never draw opaque: they blend with its shell");
    }

    private static void checkPicking(GpuTerrain terrain, BoardScene scene) {
        for (int x : new int[] { 2, 4, 6 }) {
            Coords coords = new Coords(x, 4);
            float levels = scene.tile(coords).features().getFirst().height();
            var center = BoardGeometry.center(coords, 0);
            var ray = new Ray(new Vector3(center.x, center.y, 400), new Vector3(0, 0, -1));
            var hit = terrain.hit(scene, ray);
            assertNotNull(hit);
            assertEquals(coords, hit.coords());
            // The roof is picked where it is drawn: over the gameplay height, no higher than its parapets and fittings,
            // which stay within half a level of it.
            float roof = 400 - (float) Math.sqrt(hit.distance());
            var bounds = terrain.roofBounds(coords);
            assertTrue(roof >= levels * BoardGeometry.level() && roof <= bounds.max.z + .02f, "Roof picked at " + roof);
            assertTrue(bounds.max.z <= (levels + .5f) * BoardGeometry.level(), "Roof bounds " + bounds.max.z);
        }
    }
}
