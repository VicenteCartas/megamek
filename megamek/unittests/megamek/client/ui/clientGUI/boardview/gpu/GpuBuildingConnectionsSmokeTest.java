/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Edge edits rebuild installed assemblies immediately; every generated kit keeps a compact footprint and solid walls. */
@Tag("on-demand")
class GpuBuildingConnectionsSmokeTest {
    private static final Coords AT = new Coords(2, 2);

    @Test void editsConnectionsAndRendersCompactBuildingsAndInteriorWalls() throws Exception {
        List<BoardScene> scenes = new ArrayList<>();
        List<BoardScene> tall = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            Board board = Board.createEmptyBoard(8, 11);
            for (int x : new int[] { 2, 5 }) {
                for (int y : new int[] { 2, 5, 8 }) {
                    Hex hex = new Hex(0, "building:1:0;bldg_elev:2;bldg_cf:15;bldg_basement_type:1", "grass");
                    var style = new java.util.LinkedHashMap<String, HexAppearance>();
                    if (y == 5) { style.put("building", new HexAppearance("building/lunar-habitat", null, null, null)); }
                    if (y == 8) { hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BLDG_CLASS, 2)); }
                    if (x == 2) { style.put("footprint", HexAppearance.SQUARE); }
                    hex.setAppearance(style); board.setHex(x, y, hex);
                }
            }
            var editor = new BoardEditorSession(); editor.open(board, null);
            // A legacy board's unconnected buildings load Square (BoardSceneryLayouts): the second column is set Angled.
            for (int y : new int[] { 2, 5, 8 }) {
                editor.command(new Command(Action.SELECT_AT, "5," + y, ""), null);
                editor.command(new Command(Action.FOOTPRINT, "angled"), null);
            }
            editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
            try (var source = new GpuMapSource(editor.game(), null, editor)) {
                scenes.add(source.takeFrame().scene());
                long generation = source.takeFrame().boardGeneration();
                source.editorCommand(new Command(Action.EDGE, "building", "1"), generation);
                scenes.add(source.takeFrame().scene());
                source.editorCommand(new Command(Action.EDGE, "building", "2"), generation);
                scenes.add(source.takeFrame().scene());
                source.editorCommand(new Command(Action.UNDO), generation);
                scenes.add(source.takeFrame().scene());
                source.editorCommand(new Command(Action.UNDO), generation);
                scenes.add(source.takeFrame().scene());
                source.editorCommand(new Command(Action.BUILDING_PADDING, "20"), generation);
                scenes.add(source.takeFrame().scene());
                source.editorCommand(new Command(Action.DESIGN_ROTATION, "building", "30"), generation);
                scenes.add(source.takeFrame().scene());
                source.editorCommand(new Command(Action.EDGE, "building", "1"), generation);
                source.editorCommand(new Command(Action.EDGE, "building", "2"), generation);
                source.editorCommand(new Command(Action.TERRAIN, "bldg_elev", "5"), generation);
                Hex neighbour = new Hex(0, "building:1:16;bldg_elev:2;bldg_cf:15;bldg_basement_type:1", "grass");
                neighbour.setAppearance(Map.of("footprint", HexAppearance.SQUARE));
                editor.board().setHex(AT.translated(1), neighbour);
                source.refresh();
                tall.add(source.takeFrame().scene());
            }
        });
        assertEquals(scenes.getFirst().tile(AT).features(), scenes.get(1).tile(AT).features(), "Only connections changed");
        assertFalse(scenes.getFirst().tile(AT).sameGeometry(scenes.get(1).tile(AT)));
        var failure = new AtomicReference<Throwable>();
        File output = new File("build/gpu-board-review/building-connections");
        assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardCamera camera = new BoardCamera(); camera.resize(1600, 1000);
                    int[] masks = { 0, 2, 6, 2, 0, 0, 0 };
                    for (int step = 0; step < scenes.size(); step++) {
                        BoardScene scene = scenes.get(step); terrain.update(scene);
                        var assembly = building(terrain, AT);
                        assertNotNull(assembly);
                        assertEquals(2, scene.tile(AT).cover().height());
                        assertEquals(masks[step], scene.tile(AT).cover().exits());
                        assertEquals(List.of(0, (masks[step] & 2) == 0 ? 0 : 2, (masks[step] & 4) == 0 ? 0 : 2, 0, 0, 0),
                              assembly.recipe().reach(), "Installed mesh must follow the edge edit immediately");
                        terrain.animate(1, List.of());
                        camera.setIsometric(false); camera.camera.zoom = .7f; camera.center(BoardGeometry.center(new Coords(4, 5), 0));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "connections-" + step + ".png"));
                        if (step == 0) {
                            for (int x : new int[] { 2, 5 }) {
                                for (int y : new int[] { 2, 5, 8 }) { compact(building(terrain, new Coords(x, y)), x == 2); }
                            }
                        }
                        if (step >= 5) {
                            var feature = scene.tile(AT).features().stream().filter(f -> f.kind() == BoardScene.FeatureKind.BUILDING).findFirst().orElseThrow();
                            assertEquals(.8f, feature.scale(), .0001f);
                            assertEquals(step == 5 ? 0 : -30, feature.rotation());
                        }
                        if (step == 2) {
                            camera.setIsometric(true); camera.camera.zoom = .17f; camera.center(BoardGeometry.center(AT, 1));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "interior-walls.png"));
                            insideWalls(assembly);
                        }
                    }
                    terrain.update(tall.getFirst());
                    camera.setIsometric(true); camera.camera.zoom = .2f; camera.center(BoardGeometry.center(AT, 2));
                    frame.render(terrain, camera, tall.getFirst());
                    GpuReviewFrame.save(new File(output, "tall-facade-and-floors.png"));
                    camera.orbit(30, 25);
                    frame.render(terrain, camera, tall.getFirst());
                    GpuReviewFrame.save(new File(output, "tall-floor-section.png"));
                    auditKits();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Building connection review", failure.get()); }
    }

    private static void compact(GpuBuilding.Assembly assembly, boolean square) {
        BoundingBox bounds = assembly.instance(0).calculateBoundingBox(new BoundingBox());
        float ratio = bounds.getHeight() / bounds.getWidth();
        assertTrue(square ? Math.abs(ratio - 1) < .15 : Math.abs(ratio - .866f) < .12, "Isolated footprint ratio: " + ratio);
        for (int lod = 0; lod < assembly.lodCount(); lod++) {
            for (var node : assembly.instance(lod).nodes) {
                for (Vector3 p : GpuTerrain.triangles(node)) {
                    // A little past the hex is fine (an Imperial eave's upturned tip reaches 1.4 out); a footprint is not.
                    // insideHex is a cross product with a hex side: over the side's length, world units.
                    assertTrue(KitOutline.insideHex(p.x, p.y) / KitOutline.HEX[0][0] >= -1.5, "Facade or curb outside hex at " + p);
                }
            }
        }
    }

    private static void auditKits() {
        GpuAssets assets = new GpuAssets();
        try {
            List<String> kits = new ArrayList<>();
            for (int weight = 1; weight <= 4; weight++) {
                for (int kind = 0; kind <= 3; kind++) {
                    kits.add(TilesetScenery.genericBuilding(new Hex(0, "building:" + weight + ";bldg_class:" + kind, "")));
                }
            }
            kits.add("buildings/lunar_habitat/lunar_habitat");
            kits.add("buildings/lunar_habitat/lunar_habitat@parapet");
            kits.addAll(List.of("buildings/civic/civic", "buildings/civic/civic@ribbons", "buildings/civic/civic@offices",
                  "buildings/utopia/utopia", "buildings/utopia/utopia@bands", "buildings/frontier/frontier",
                  "buildings/frontier/frontier@medical", "buildings/frontier/frontier@industrial", "buildings/frontier/frontier@greenhouse",
                  "buildings/imperial/imperial", "buildings/imperial/imperial@tower", "buildings/imperial/imperial@habitat",
                  "buildings/imperial/imperial@commercial", "buildings/imperial/imperial@industrial"));
            for (String asset : kits) {
                for (var footprint : HexAppearance.Footprint.values()) {
                    String design = TilesetScenery.footprint(asset, footprint);
                    try {
                        var isolated = assets.building(design, 2, new int[6], 0, false);
                        assertNotNull(isolated, design);
                        if (TilesetScenery.generatedKit(design)) {
                            compact(isolated, footprint == HexAppearance.Footprint.SQUARE);
                            // The generic hangars' roofs are vaults of sheeting (BayKit.Vault), no slab.
                            thickness(isolated, design.contains("/hangar_") ? .3f : .99f);
                        }
                        insideWalls(isolated);
                        if (!TilesetScenery.singleHexBuilding(design)) {
                            var connected = assets.building(design, 2, new int[] { 0, 2, 2, 0, 0, 0 }, 0, true);
                            assertNotNull(connected);
                            insideWalls(connected);
                            Vector3 floor = frontHit(GpuTerrain.triangles(connected.interior().getNode("floors")),
                                  new Ray(new Vector3(0, 1, 30), new Vector3(0, 0, -1)));
                            assertNotNull(floor, "A connected room needs a floor across its interior, not just its wall strips");
                            assertEquals(18, floor.z, .01f);
                        }
                    } catch (Throwable error) { throw new AssertionError(design, error); }
                }
            }
            for (String asset : List.of("buildings/standard/building-light", "buildings/lunar_habitat/lunar_habitat")) {
                var hall = assets.building(asset, 2, new int[6], 0, megamek.common.board.BuildingInteriors.NO_FLOORS);
                Vector3 hit = frontHit(GpuTerrain.triangles(hall.interior()), new Ray(new Vector3(0, 1, 30), new Vector3(0, 0, -1)));
                assertNotNull(hit);
                assertTrue(hit.z < 1, "No-floors buildings must not retain invisible upper-floor picking surfaces");
                assertEquals(1, hall.interior().getNode("floors").parts.size);
                var enclosed = assets.building(asset, 2, new int[6], 0, megamek.common.board.BuildingInteriors.YES);
                assertTrue(enclosed.interior().getNode("floors").parts.get(1).meshPart.mesh.getNumVertices() > 0);
                var slabs = GpuTerrain.triangles(enclosed.interior());
                Vector3 underside = frontHit(slabs, new Ray(new Vector3(0, 1, 9), Vector3.Z));
                assertNotNull(underside);
                assertEquals(16.8f, underside.z, .05f, "Floor thickness extends below the standing level");
            }
        } finally { assets.dispose(); }
    }

    private static void insideWalls(GpuBuilding.Assembly assembly) {
        // Shoot from inside towards the closed west wall, respecting the renderer's front-face culling.
        Ray ray = new Ray(new Vector3(0, 1, 9), new Vector3(-1, 0, 0));
        for (int lod = 0; lod < assembly.lodCount(); lod++) {
            List<Vector3> triangles = new ArrayList<>();
            for (var node : assembly.instance(lod).nodes) { triangles.addAll(GpuTerrain.triangles(node)); }
            boolean hit = false;
            for (int i = 0; i < triangles.size(); i += 3) {
                Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
                if (b.cpy().sub(a).crs(c.cpy().sub(a)).dot(ray.direction) < -.001f
                      && Intersector.intersectRayTriangle(ray, a, b, c, null)) { hit = true; break; }
            }
            assertTrue(hit, "Closed wall must be visible from inside at LOD " + lod);
        }
    }

    private static void thickness(GpuBuilding.Assembly assembly, float roofThickness) {
        for (int lod = 0; lod < assembly.lodCount(); lod++) {
            List<Vector3> triangles = new ArrayList<>();
            for (var node : assembly.instance(lod).nodes) { triangles.addAll(GpuTerrain.triangles(node)); }
            Vector3 inside = frontHit(triangles, new Ray(new Vector3(0, 1, 9), new Vector3(-1, 0, 0)));
            Vector3 outside = frontHit(triangles, new Ray(new Vector3(-100, 1, 9), new Vector3(1, 0, 0)));
            assertNotNull(inside); assertNotNull(outside);
            assertTrue(inside.x - outside.x >= .3f, "Walls need solid thickness at LOD " + lod + ": " + inside + " / " + outside);
            Vector3 ceiling = frontHit(triangles, new Ray(new Vector3(0, 1, 30), Vector3.Z));
            Vector3 roof = frontHit(triangles, new Ray(new Vector3(0, 1, 100), new Vector3(0, 0, -1)));
            assertNotNull(ceiling); assertNotNull(roof);
            assertTrue(roof.z - ceiling.z >= roofThickness, "Roof needs a solid slab at LOD " + lod);
        }
    }

    private static Vector3 frontHit(List<Vector3> triangles, Ray ray) {
        Vector3 result = null, point = new Vector3();
        for (int i = 0; i < triangles.size(); i += 3) {
            Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
            if (b.cpy().sub(a).crs(c.cpy().sub(a)).dot(ray.direction) < -.001f
                  && Intersector.intersectRayTriangle(ray, a, b, c, point)
                  && (result == null || ray.origin.dst2(point) < ray.origin.dst2(result))) { result = point.cpy(); }
        }
        return result;
    }

    private static GpuBuilding.Assembly building(GpuTerrain terrain, Coords at) throws Exception {
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                if (at.equals(GpuTestSupport.field(prop, "coords")) && GpuTestSupport.field(prop, "building") instanceof GpuBuilding.Assembly assembly) { return assembly; }
            }
        }
        return null;
    }
}
