/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The shipped examples exercise real slicing, material upload, interiors and anchor-hex collapse. */
@Tag("on-demand")
class GpuAuthoredBuildingSmokeTest {
    @Test void previewsAuthoredTransformsAndPaintBeforeRebuildingAnyChunks() throws Exception {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(24, 20), null);
        for (int x = 0; x < 24; x++) { for (int y = 0; y < 20; y++) { editor.board().getHex(x, y).setLevel(3); } }
        Coords at = new Coords(11, 9);
        place(editor, at, "hpg-headquarters", 0);
        BoardScene original = capture(editor.board());
        List<BoardScene> poses = new java.util.ArrayList<>();
        for (var edit : List.of(new Command(Action.OBJECT_VALUE, "x", ".25"), new Command(Action.OBJECT_VALUE, "rotation", "37"),
              new Command(Action.OBJECT_VALUE, "scale", ".8"), new Command(Action.OBJECT_VALUE, "stretchY", "1.2"),
              new Command(Action.OBJECT_VALUE, "colour0", "#dc9b72"), new Command(Action.OBJECT_VALUE, "colour0", "#729bdc"),
              new Command(Action.OBJECT_VALUE, "colour0", ""))) {
            editor.command(edit, null, true);
            poses.add(original.withTiles(BoardTiles.captureAll(editor.board())));
        }
        poses.add(original);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1200, 900); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                try {
                    BoardCamera camera = new BoardCamera(); camera.resize(1200, 900); camera.center(BoardGeometry.center(at, 3));
                    terrain.update(original); GpuTerrainLodSmokeTest.settle(terrain, null, original, camera);
                    terrain.animate(1, List.of(), .25f, at, 3 * BoardGeometry.level());
                    var models = new java.util.HashSet<com.badlogic.gdx.graphics.g3d.Model>();
                    var materials = new java.util.IdentityHashMap<Material, Material>();
                    for (Object chunk : (List<?>) field(terrain, "chunks")) {
                        for (Object prop : (List<?>) field(chunk, "props")) {
                            if (field(prop, "decorationId") == null) { continue; }
                            var installed = (ModelInstance) field(prop, "instance");
                            models.add(installed.model);
                            for (var material : installed.materials) { materials.put(material, material.copy()); }
                        }
                    }
                    var start = original.tile(at).features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElseThrow();
                    for (BoardScene pose : poses) {
                        var next = pose.tile(at).features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElseThrow();
                        String colour = next.colours().slot(0);
                        Color tint = colour == null ? Color.WHITE : GpuColor.linear(Color.valueOf(colour));
                        long clock = System.nanoTime(); terrain.previewEditorObjects(pose);
                        System.out.printf("PERF authored immediate-transform=%.2fms%n", (System.nanoTime() - clock) / 1_000_000.0);
                        float unit = BoardGeometry.hexScale();
                        var delta = new com.badlogic.gdx.math.Matrix4().scale(unit, unit, unit).mul(GpuAuthoredBuilding.frame(at, next.decoration(), 3))
                              .mul(new com.badlogic.gdx.math.Matrix4().scale(unit, unit, unit).mul(GpuAuthoredBuilding.frame(at, start.decoration(), 3)).inv());
                        int shares = 0, painted = 0, fixed = 0;
                        for (Object chunk : (List<?>) field(terrain, "chunks")) {
                            for (Object prop : (List<?>) field(chunk, "props")) {
                                if (field(prop, "decorationId") == null) { continue; }
                                var installed = (com.badlogic.gdx.graphics.g3d.ModelInstance) field(prop, "instance");
                                var preview = (com.badlogic.gdx.graphics.g3d.ModelInstance) field(prop, "previewInstance");
                                assertNotNull(preview); assertSame(installed.model, preview.model); assertTrue(models.contains(preview.model));
                                assertArrayEquals(new com.badlogic.gdx.math.Matrix4(delta).mul(installed.transform).val, preview.transform.val, .001f);
                                painted += assertPaint(preview, tint);
                                assertPaint(installed, Color.WHITE);
                                for (var material : installed.materials) {
                                    assertEquals(materials.get(material), material, "Preview must not change installed material state");
                                }
                                for (var material : preview.materials) {
                                    Material expected = materials.get(installed.getMaterial(material.id)).copy();
                                    Material actual = material.copy();
                                    expected.remove(GpuBuildingPaint.TYPE); actual.remove(GpuBuildingPaint.TYPE);
                                    assertEquals(expected, actual, "Paint leaves each surface's base finish unchanged");
                                    if (!material.has(GpuBuildingPaint.TYPE)) { fixed++; }
                                }
                                if (field(prop, "hoverOpaque") instanceof ModelInstance hovered) { assertPaint(hovered, tint); }
                                // The side view requests near geometry even when the main view installed a farther LOD.
                                var level = prop.getClass().getDeclaredMethod("level", int.class, ModelInstance.class);
                                level.setAccessible(true);
                                assertPaint((ModelInstance) level.invoke(prop, 0, preview), tint);
                                shares++;
                            }
                        }
                        assertTrue(shares > 40);
                        // Some slices contain only unpainted dishes or equipment; the whole building has both kinds.
                        assertTrue(painted > 0, "Authored material metadata must identify its paintable surfaces");
                        assertTrue(fixed > 0, "The building must retain fixed material finishes");
                        var shown = terrain.editorObjects(at).getFirst();
                        assertEquals(3, shown.anchorLevel(), .0001);
                        assertEquals(BoardGeometry.centerX(at) + next.decoration().x() * BoardGeometry.width(), shown.anchor().x, .001);
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored immediate preview", failure.get()); }
    }

    private static int assertPaint(ModelInstance instance, Color expected) {
        int painted = 0;
        for (var material : instance.materials) {
            var paint = material.get(GpuBuildingPaint.class, GpuBuildingPaint.TYPE);
            if (paint == null) { continue; }
            assertEquals(expected.r, paint.colour.r, 1e-6);
            assertEquals(expected.g, paint.colour.g, 1e-6);
            assertEquals(expected.b, paint.colour.b, 1e-6);
            painted++;
        }
        return painted;
    }

    @Test void rendersClippedHpgAndStationWithNeutralCapsAndRestoresTheirGeometryWhenTheBoardGrows() throws Exception {
        File output = new File("build/gpu-board-review/authored-buildings");
        Files.createDirectories(output.toPath());
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(12, 12), null);
        Coords hpg = new Coords(0, 6), station = new Coords(11, 1);
        place(editor, hpg, "hpg-headquarters", 37);
        editor.command(new Command(Action.OBJECT_VALUE, "stretchX", "1.15"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "stretchY", ".9"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "colour0", "#dc9b72"), null);
        place(editor, station, "xenobiology-station", -20);
        editor.command(new Command(Action.OBJECT_VALUE, "levels", "5"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "colour0", "#dc9b72"), null);
        var scene = capture(editor.board());
        var grown = capture(megamek.common.util.BoardUtilities.resize(editor.board(), 0, 4, 0, 0));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    int capped = 0;
                    for (var prepared : GpuAuthoredBuilding.prepare(scene, scene.tiles()).values()) {
                        var at = prepared.layout().cell();
                        float cx = megamek.common.board.HexTile.centerX(at, megamek.common.board.HexTile.MODEL_WIDTH);
                        float cy = megamek.common.board.HexTile.centerY(at, megamek.common.board.HexTile.HEIGHT);
                        for (var lod : prepared.levels()) {
                            var mesh = AuthoredBoardClipTest.mesh(lod);
                            if (mesh.parts.containsKey(AuthoredBoardClip.CAP)) { capped++; }
                            for (var part : mesh.parts.values()) {
                                for (int i = 0; i < part.size; i += RigidGlb.STRIDE) { part.items[i] += cx; part.items[i + 1] += cy; }
                            }
                            AuthoredBoardClipTest.assertInside(mesh, scene.width(), scene.height());
                        }
                    }
                    assertTrue(capped > 0);
                    terrain.update(scene); terrain.animate(1, List.of());
                    BoardCamera camera = new BoardCamera(); camera.resize(1600, 1000);
                    camera.setIsometric(true); camera.orbit(-90, 0); camera.camera.zoom = .72f;
                    camera.center(BoardGeometry.center(hpg, 0).add(170, -20, 120));
                    frame.render(terrain, camera, scene); GpuReviewFrame.save(new File(output, "hpg-edge.png"));
                    camera.setIsometric(true); camera.orbit(90, 0); camera.camera.zoom = .25f;
                    camera.center(BoardGeometry.center(station, 2));
                    frame.render(terrain, camera, scene); GpuReviewFrame.save(new File(output, "station-edge.png"));
                    int neutral = 0;
                    for (Object chunk : (List<?>) field(terrain, "chunks")) {
                        for (Object prop : (List<?>) field(chunk, "props")) {
                            if (field(prop, "decorationId") == null) { continue; }
                            var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) field(prop, "instance");
                            var cap = instance.getMaterial(AuthoredBoardClip.CAP, false);
                            if (cap != null) {
                                var colour = cap.get(com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.class,
                                      com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute.Diffuse).color;
                                assertEquals(.08f, colour.r, .00001f); assertEquals(colour.r, colour.g); assertEquals(colour.g, colour.b);
                                neutral++;
                            }
                        }
                    }
                    assertTrue(neutral > 0, "Placement tint must leave the map caps dark grey");
                    var before = terrain.editorObjects(station).getFirst().bounds().max.x;
                    terrain.update(grown); terrain.animate(1, List.of());
                    var after = terrain.editorObjects(station).getFirst().bounds().max.x;
                    assertTrue(after > before + BoardGeometry.width() * .1f, "Resizing must not reuse the old clipped mesh");
                    frame.render(terrain, camera, grown); GpuReviewFrame.save(new File(output, "station-edge-grown.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored board-edge review", failure.get()); }
    }

    @Test void rendersBothExamplesAndKeepsTheRemainingSlicesAfterAnchorCollapse() throws Exception {
        File output = new File("build/gpu-board-review/authored-buildings");
        Files.createDirectories(output.toPath());
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(32, 24), null);
        Coords hpg = new Coords(13, 10), lab = new Coords(28, 10);
        place(editor, hpg, "hpg-headquarters", 0);
        place(editor, lab, "xenobiology-station", -12);
        editor.save(output.toPath().resolve("Authored Buildings.board2"));
        Board board = BoardFile.read(output.toPath().resolve("Authored Buildings.board2"));
        var original = capture(board);
        for (var policy : List.of(BuildingInteriors.NO_FLOORS, BuildingInteriors.NO)) {
            for (var tile : original.tiles()) {
                if (tile.cover().reference() != null) { BuildingInteriors.set(board.getHex(tile.coords()), policy); }
            }
            editor.open(board, null);
            editor.save(output.toPath().resolve(policy.id() + ".board2"));
        }
        BoardScene hallScene = capture(BoardFile.read(output.toPath().resolve("noFloors.board2")));
        BoardScene sealedScene = capture(BoardFile.read(output.toPath().resolve("no.board2")));
        board = BoardFile.read(output.toPath().resolve("Authored Buildings.board2"));
        var reference = board.getHex(hpg).getBuildingReference();
        assertNotNull(reference);
        var surviving = original.tiles().stream().filter(t -> t.cover().reference() != null
              && t.cover().reference().id().equals(reference.id()) && !t.coords().equals(hpg)).findFirst().orElseThrow();
        var feature = surviving.features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElseThrow();
        var stable = GpuAuthoredBuilding.layout(original, surviving, feature);
        board.collapseBuilding(hpg);
        assertFalse(board.getHex(hpg).containsTerrain(Terrains.BUILDING));
        var collapsed = capture(board);
        var remaining = collapsed.tile(surviving.coords());
        assertEquals(stable, GpuAuthoredBuilding.layout(collapsed, remaining,
              remaining.features().stream().filter(GpuAuthoredBuilding::supports).findFirst().orElseThrow()));
        assertTrue(collapsed.tile(hpg).features().stream().noneMatch(GpuAuthoredBuilding::supports));
        assertEquals(1, board.getHex(hpg).getDecorations().size());

        var smallEditor = new BoardEditorSession(); smallEditor.open(Board.createEmptyBoard(6, 4), null);
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 4; y++) { smallEditor.board().getHex(x, y).setLevel(3); }
        }
        for (String asset : List.of("hpg-headquarters", "xenobiology-station")) {
            smallEditor.command(new Command(Action.ASSET, "buildings/authored/" + asset), null);
            smallEditor.command(new Command(Action.BRUSH_VALUE, "object:scale", ".05"), null);
            if (asset.equals("xenobiology-station")) { smallEditor.command(new Command(Action.BRUSH_VALUE, "object:levels", "5"), null); }
            smallEditor.pointer(new Coords(asset.equals("hpg-headquarters") ? 2 : 4, 2), 0, 0, false);
            smallEditor.finishStroke();
        }
        var smallScene = capture(smallEditor.board());
        assertTrue(smallScene.tiles().stream().allMatch(t -> t.cover().reference() == null && t.cover().height() == 0));
        assertEquals(2, smallScene.tiles().stream().flatMap(t -> t.features().stream()).filter(GpuAuthoredBuilding::supports).count());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuAssets assets = new GpuAssets();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var slice = assets.authoredBuilding(GpuAuthoredBuilding.prepare(stable, GpuAuthoredBuilding.load(stable.object().asset())));
                    assertFalse(slice.roof().isEmpty());
                    assertNotNull(slice.interior());
                    var bounds = slice.levels().getFirst().calculateBoundingBox(new BoundingBox());
                    assertTrue(bounds.isValid());
                    var hall = assets.authoredBuilding(GpuAuthoredBuilding.prepare(new GpuAuthoredBuilding.Layout(stable.cell(), stable.anchor(), stable.object(),
                          stable.base(), stable.ground(), stable.height(), BuildingInteriors.NO_FLOORS, stable.members(), stable.boardWidth(), stable.boardHeight()), GpuAuthoredBuilding.load(stable.object().asset())));
                    assertEquals(1, hall.interior().getNode("floors").parts.size);
                    var sealed = assets.authoredBuilding(GpuAuthoredBuilding.prepare(new GpuAuthoredBuilding.Layout(stable.cell(), stable.anchor(), stable.object(),
                          stable.base(), stable.ground(), stable.height(), BuildingInteriors.NO, stable.members(), stable.boardWidth(), stable.boardHeight()), GpuAuthoredBuilding.load(stable.object().asset())));
                    assertNull(sealed.interior());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1600, 1000);
                    camera.setIsometric(true);
                    camera.camera.zoom = 1.15f;
                    terrain.update(original);
                    for (Coords focus : List.of(hpg, lab)) {
                        camera.setIsometric(true);
                        camera.camera.zoom = focus.equals(hpg) ? 1.15f : .32f;
                        var target = BoardGeometry.center(focus, 1);
                        if (focus.equals(hpg)) {
                            camera.orbit(-90, 0);
                            target.add(-65, 0, 180);
                        }
                        camera.center(target);
                        terrain.animate(1, List.of());
                        frame.render(terrain, camera, original);
                        GpuReviewFrame.save(new File(output, focus.equals(hpg) ? "hpg.png" : "station.png"));
                    }
                    // Review the small receiver and relay at the distance where their near details matter.
                    camera.setIsometric(true);
                    camera.camera.zoom = .27f;
                    camera.center(BoardGeometry.center(hpg, 0).add(310, 110, 38));
                    frame.render(terrain, camera, original);
                    GpuReviewFrame.save(new File(output, "hpg-sensors.png"));
                    camera.camera.zoom = .5f;
                    camera.center(BoardGeometry.center(hpg, 1));
                    terrain.animate(1, List.of(), .25f, hpg, (original.tile(hpg).cover().height() - 1) * BoardGeometry.level());
                    assertFalse(faded(terrain).isEmpty());
                    frame.render(terrain, camera, original);
                    GpuReviewFrame.save(new File(output, "interior.png"));
                    terrain.update(hallScene);
                    terrain.animate(1, List.of(), .25f, hpg, BoardGeometry.level());
                    assertFalse(faded(terrain).isEmpty());
                    for (Object prop : faded(terrain)) {
                        var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) field(prop, "instance");
                        var cut = instance.materials.first().get(GpuBuildingCutaway.class, GpuBuildingCutaway.TYPE);
                        assertNotNull(cut);
                        assertEquals(0, cut.floor);
                        assertEquals(Float.MAX_VALUE, cut.ceiling);
                    }
                    frame.render(terrain, camera, hallScene);
                    GpuReviewFrame.save(new File(output, "no-floors.png"));
                    terrain.update(sealedScene);
                    terrain.animate(1, List.of(), .25f, hpg, 0);
                    assertTrue(faded(terrain).isEmpty());
                    frame.render(terrain, camera, sealedScene);
                    GpuReviewFrame.save(new File(output, "no-interiors.png"));
                    terrain.update(collapsed);
                    terrain.animate(1, List.of());
                    frame.render(terrain, camera, collapsed);
                    GpuReviewFrame.save(new File(output, "collapsed.png"));
                    for (var entry : GpuAuthoredBuilding.prepare(smallScene, smallScene.tiles()).entrySet()) {
                        var prepared = entry.getValue();
                        assertTrue(prepared.floors().isEmpty()); assertTrue(prepared.roof().isEmpty()); assertTrue(prepared.columns().isEmpty());
                        var prop = assets.authoredBuilding(prepared);
                        assertNull(prop.interior());
                        assertTrue(prop.levels().getFirst().calculateBoundingBox(new BoundingBox()).isValid());
                    }
                    terrain.update(smallScene);
                    terrain.animate(1, List.of());
                    camera.camera.zoom = .18f;
                    camera.center(BoardGeometry.center(new Coords(3, 2), 3));
                    frame.render(terrain, camera, smallScene);
                    GpuReviewFrame.save(new File(output, "small-props.png"));
                    // The displayed frame may enable material flags; settle them as the interactive loading loop does.
                    GpuTerrainLodSmokeTest.settle(terrain, null, smallScene, camera);
                    assertTrue(terrain.ready(smallScene), "Picking requires the scene's material configuration to be ready");
                    int selectable = 0;
                    for (Object chunk : (List<?>) field(terrain, "chunks")) {
                        for (Object prop : (List<?>) field(chunk, "props")) {
                            if (field(prop, "decorationId") == null) { continue; }
                            selectable++;
                            assertTrue(((List<?>) field(prop, "supportTriangles")).isEmpty());
                            assertNotEquals("building", field(prop, "receiver"));
                        }
                    }
                    assertEquals(2, selectable);
                    for (Coords owner : List.of(new Coords(2, 2), new Coords(4, 2))) {
                        var shown = terrain.editorObjects(owner).getFirst();
                        assertTrue(shown.shown());
                        boolean picked = false;
                        for (int x = 1; x < 10 && !picked; x++) {
                            for (int y = 1; y < 10 && !picked; y++) {
                                var ray = new com.badlogic.gdx.math.collision.Ray(new com.badlogic.gdx.math.Vector3(
                                      shown.bounds().min.x + shown.bounds().getWidth() * x / 10,
                                      shown.bounds().min.y + shown.bounds().getHeight() * y / 10, shown.bounds().max.z + 20),
                                      new com.badlogic.gdx.math.Vector3(0, 0, -1));
                                var hit = terrain.decorationHit(smallScene, ray);
                                picked = hit != null && hit.id().equals(shown.id()) && hit.coords().equals(owner);
                                if (picked) { assertEquals(shown.anchor().z, hit.anchorZ(), .0001f, "Authored drag plane follows the baked base height"); }
                            }
                        }
                        assertTrue(picked, "Small authored prop remains selectable at " + owner + " in " + shown.bounds());
                    }
                    terrain.setFluffHidden(true);
                    for (Coords owner : List.of(new Coords(2, 2), new Coords(4, 2))) {
                        assertFalse(terrain.editorObjects(owner).getFirst().shown());
                    }
                    terrain.setFluffHidden(false);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); assets.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored building review", failure.get()); }
    }

    private static void place(BoardEditorSession editor, Coords at, String asset, int rotation) {
        editor.command(new Command(Action.ASSET, "buildings/authored/" + asset), null);
        editor.pointer(at, 0, 0, false); editor.finishStroke();
        editor.command(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, editor.board().getHex(at).getDecorations().getFirst().id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "rotation", Integer.toString(rotation)), null);
    }

    private static BoardScene capture(Board board) throws Exception {
        AtomicReference<BoardScene> scene = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> { fixture.source.refresh(); scene.set(fixture.source.takeFrame().scene()); });
        }
        return scene.get();
    }

    private static List<Object> faded(GpuTerrain terrain) throws Exception {
        List<Object> result = new java.util.ArrayList<>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) { result.addAll((java.util.Set<?>) field(chunk, "faded")); }
        return result;
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
