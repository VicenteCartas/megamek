/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
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
    /** A patch of field, away from all fixtures, in the arena's centred overhead capture. */
    private static float centreBrightness() {
        var image = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(580, 430, 40, 40);
        try {
            float total = 0;
            for (int y = 0; y < 40; y++) {
                for (int x = 0; x < 40; x++) {
                    int rgba = image.getPixel(x, y);
                    total += ((rgba >>> 24) + ((rgba >>> 16) & 255) + ((rgba >>> 8) & 255)) / (3f * 255);
                }
            }
            return total / 1600;
        } finally { image.dispose(); }
    }

    @Test void civicCitadelKeepsSteppedFloorsAndNightGlazingAcrossLods() throws Exception {
        reviewCivicTower("civic-citadel", 14, List.of(2, 2, 3, 3, 14), 125, .33f);
    }

    @Test void civicHabitatKeepsSteppedFloorsAndNightGlazingAcrossLods() throws Exception {
        reviewCivicTower("civic-habitat", 12, List.of(1, 1, 2, 2, 2, 3, 3, 3, 12, 12, 12), 120, .44f);
    }

    private static void reviewCivicTower(String asset, int height, List<Integer> roofs, float targetZ, float zoom) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        File output = new File("build/gpu-board-review/" + asset);
        Files.createDirectories(output.toPath());
        Coords at = new Coords(4, 4);
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(9, 9), null);
        place(editor, at, asset, 0);
        var scene = capture(editor.board());
        assertEquals(height, scene.tile(at).cover().height());
        assertEquals(roofs, scene.tiles().stream()
              .filter(t -> t.cover().reference() != null).map(t -> t.cover().height()).sorted().toList());
        var slices = GpuAuthoredBuilding.prepare(scene, scene.tiles());
        for (var prepared : slices.values()) {
            assertEquals(prepared.layout().height(), prepared.floors().size(), "Each occupied level gets a generated floor");
        }
        editor.command(new Command(Action.OBJECT_VALUE, "colour0", "#729bdc"), null, true);
        var tinted = scene.withTiles(BoardTiles.captureAll(editor.board()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1200, 1100); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain(); GpuAssets assets = new GpuAssets();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    int[] windows = new int[2], lamps = new int[2], dark = new int[2];
                    for (var prepared : slices.values()) {
                        var model = assets.authoredBuilding(prepared);
                        for (int lod = 0; lod < 2; lod++) {
                            for (Material material : model.levels().get(lod).materials) {
                                if (material.has(ColorAttribute.Emissive) || material.has(GpuModelLight.TYPE)) {
                                    assertFalse(material.has(GpuBuildingPaint.TYPE), "Windows and lamps must not take wall tint");
                                    var light = material.get(GpuModelLight.class, GpuModelLight.TYPE);
                                    if (material.id.contains("glass")) {
                                        assertNotNull(light, "Night-window control must survive hex slicing");
                                        if (material.id.contains("#emission-off")) {
                                            assertEquals(new GpuModelLight(0, 0, 1), light);
                                            dark[lod]++;
                                        } else {
                                            assertEquals(true, GpuTestSupport.<Object>field(light, "nightOnly"));
                                            windows[lod]++;
                                        }
                                    } else { lamps[lod]++; }
                                }
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        assertTrue(windows[lod] > 0); assertTrue(lamps[lod] > 0); assertTrue(dark[lod] > 0);
                    }
                    BoardCamera camera = new BoardCamera(); camera.resize(1200, 1100);
                    terrain.update(scene);
                    record View(String name, float yaw, float pitch, boolean top) { }
                    var views = List.of(new View("isometric", 45, BoardCamera.ISOMETRIC_TILT, false), new View("front", 0, 80, false),
                          new View("side", 90, 80, false), new View("back", 180, 80, false), new View("top", 0, 0, true));
                    int[] selectedLod = {0};
                    try (var lodChoice = org.mockito.Mockito.mockStatic(TreeLod.class, call -> {
                        if (call.getMethod().getName().equals("level") && call.getArguments().length == 3
                              && call.getArgument(0) == TreeLod.PROPS) { return selectedLod[0]; }
                        return call.callRealMethod();
                    })) {
                        for (int lod = 0; lod < 2; lod++) {
                            selectedLod[0] = lod;
                            for (View view : views) {
                                camera.setIsometric(!view.top());
                                if (!view.top()) { camera.orbit(view.yaw() - 45, view.pitch() - BoardCamera.ISOMETRIC_TILT); }
                                camera.camera.zoom = view.top() ? .30f : zoom;
                                camera.center(BoardGeometry.center(at, 0).add(0, 0, view.top() ? 0 : targetZ));
                                terrain.animate(1, List.of()); frame.render(terrain, camera, scene);
                                int inspected = 0;
                                for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                                    for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                        if (GpuTestSupport.field(prop, "decorationId") == null) { continue; }
                                        assertEquals(lod, GpuTestSupport.<Object>field(prop, "lod")); inspected++;
                                    }
                                }
                                assertTrue(inspected > 0);
                                GpuReviewFrame.save(new File(output, "lod" + lod + "-" + view.name() + ".png"));
                            }
                        }
                        camera.setIsometric(true); camera.camera.zoom = zoom;
                        camera.center(BoardGeometry.center(at, 0).add(0, 0, targetZ));
                        frame.configure(new BoardAtmosphere.Settings(23, 0, 0,
                              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                        for (int lod = 0; lod < 2; lod++) {
                            selectedLod[0] = lod; terrain.animate(1, List.of()); frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "lod" + lod + "-night.png"));
                        }
                        frame.configure(new BoardAtmosphere.Settings(10, 0, 0,
                              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                        terrain.update(tinted);
                        for (int lod = 0; lod < 2; lod++) {
                            selectedLod[0] = lod; terrain.animate(1, List.of()); frame.render(terrain, camera, tinted);
                            GpuReviewFrame.save(new File(output, "lod" + lod + "-tint.png"));
                        }
                        int painted = 0;
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                painted += assertPaint((ModelInstance) GpuTestSupport.field(prop, "instance"), GpuColor.linear(Color.valueOf("729bdc")));
                            }
                        }
                        assertTrue(painted > 0);
                        terrain.update(scene); selectedLod[0] = 0;
                        camera.orbit(0, -15); camera.camera.zoom = .22f;
                        camera.center(BoardGeometry.center(at, 0).add(0, 0, targetZ + 15));
                        var occupant = GpuTestSupport.occupant(at);
                        terrain.animate(1, List.of(occupant), .25f);
                        occupant.model.dispose();
                        assertFalse(faded(terrain).isEmpty(), "A unit inside opens the shaft to its generated interior");
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "interior.png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); assets.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError(asset + " review", failure.get()); }
    }

    @Test void civicAssetsKeepMaterialsAndLightsAcrossSlicedLods() throws Exception {
        File output = new File("build/gpu-board-review/civic-emission");
        Files.createDirectories(output.toPath());
        var names = List.of("civic-church", "classic-church", "civic-arena", "neo-futurist-arena");
        var locations = List.of(new Coords(3, 4), new Coords(9, 4), new Coords(3, 11), new Coords(10, 11));
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(16, 17), null);
        for (int i = 0; i < names.size(); i++) { place(editor, locations.get(i), names.get(i), 0); }
        var scene = capture(editor.board());
        for (Coords at : locations) {
            editor.command(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), null);
            editor.command(new Command(Action.SELECT_OBJECT, editor.board().getHex(at).getDecorations().getFirst().id()), null);
            editor.command(new Command(Action.OBJECT_VALUE, "colour0", "#729bdc"), null, true);
        }
        var tinted = scene.withTiles(BoardTiles.captureAll(editor.board()));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuAssets assets = new GpuAssets();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(23, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var illuminated = new java.util.HashSet<String>();
                    for (var entry : GpuAuthoredBuilding.prepare(scene, scene.tiles()).entrySet()) {
                        var model = assets.authoredBuilding(entry.getValue());
                        for (int lod = 0; lod < model.levels().size(); lod++) {
                            for (Material material : model.levels().get(lod).materials) {
                                var light = material.get(ColorAttribute.class, ColorAttribute.Emissive);
                                var surface = material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
                                // A night light's colour is its finish's glow (RigidGlb.nightEmission).
                                if ((light == null || light.color.r + light.color.g + light.color.b == 0)
                                      && (surface == null || surface.glow[0] + surface.glow[1] + surface.glow[2] == 0)) { continue; }
                                assertFalse(material.id.contains("#paint"), "Tintable walls must remain unlit: " + material.id);
                                assertTrue(material.id.contains("#emission") || material.id.contains("dome-window") || material.id.contains("glass"),
                                      "Only lamps, displays and window maps emit: " + material.id);
                                if (material.id.contains("glass") || material.id.contains("dome-window")) {
                                    var control = material.get(GpuModelLight.class, GpuModelLight.TYPE);
                                    assertNotNull(control, "Window day/night metadata survives slicing and LODs");
                                    assertEquals(true, GpuTestSupport.<Object>field(control, "nightOnly"));
                                }
                                illuminated.add(entry.getKey().object().asset() + ":" + lod);
                            }
                        }
                    }
                    for (String name : names) {
                        for (int lod = 0; lod < 2; lod++) {
                            assertTrue(illuminated.contains("buildings/authored/" + name + ":" + lod),
                                  name + " LOD" + lod + "; illuminated slices: " + illuminated);
                        }
                    }
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1200, 900);
                    camera.setIsometric(true);
                    terrain.update(scene);
                    var localLights = (GpuLavaLighting) GpuTestSupport.field(terrain, "lavaLighting");
                    @SuppressWarnings("unchecked")
                    var installedLights = (List<GpuLavaLighting.Source>) GpuTestSupport.field(localLights, "buildings");
                    assertEquals(16, installedLights.size(), "Eight fixtures per arena, once each across all slices and LODs");
                    for (int i = 0; i < names.size(); i++) {
                        camera.setIsometric(true);
                        camera.camera.zoom = i < 2 ? .21f : .30f;
                        var target = BoardGeometry.center(locations.get(i), 1);
                        if (i < 2) { target.add(0, 36, 18); }
                        camera.center(target);
                        terrain.animate(1, List.of());
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, names.get(i) + "-night.png"));
                        if (i >= 2) {
                            camera.setIsometric(false);
                            camera.center(BoardGeometry.center(locations.get(i), 0));
                            frame.render(terrain, camera, scene);
                            float lit = centreBrightness();
                            GpuReviewFrame.save(new File(output, names.get(i) + "-night-lit-top.png"));
                            localLights.setBuildings(List.of());
                            frame.render(terrain, camera, scene);
                            float unlit = centreBrightness();
                            GpuReviewFrame.save(new File(output, names.get(i) + "-night-without-floodlights.png"));
                            assertTrue(lit > unlit + .03f, names.get(i) + " field must receive light, not just fixture glow: " + lit + " vs " + unlit);
                            localLights.setBuildings(installedLights);
                        }
                    }
                    // Compare the shipped LODs at the same size: distance must not disguise missing faces or colour jumps.
                    File exterior = new File(output, "exteriors");
                    Files.createDirectories(exterior.toPath());
                    frame.configure(new BoardAtmosphere.Settings(10, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    for (int i = 0; i < names.size(); i++) {
                        camera.setIsometric(true); camera.camera.zoom = i < 2 ? .21f : .30f;
                        var target = BoardGeometry.center(locations.get(i), 1);
                        if (i < 2) { target.add(0, 36, 18); }
                        camera.center(target); frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, names.get(i) + "-day.png"));
                    }
                    record View(String name, float yaw, float pitch, boolean top) { }
                    var views = List.of(new View("front", -45, 25.26439f, false), new View("back", 135, 25.26439f, false),
                          new View("side", 45, 25.26439f, false), new View("top", 0, 0, true),
                          new View("entrance", -45, 20, false));
                    int[] selectedLod = {0};
                    try (var lodChoice = org.mockito.Mockito.mockStatic(TreeLod.class, call -> {
                        if (call.getMethod().getName().equals("level") && call.getArguments().length == 3
                              && call.getArgument(0) == TreeLod.PROPS) { return selectedLod[0]; }
                        return call.callRealMethod();
                    })) {
                        for (int lod = 0; lod < 2; lod++) {
                            final int requested = lod;
                            selectedLod[0] = requested;
                            for (int i = 0; i < names.size(); i++) {
                                for (View view : views) {
                                    camera.setIsometric(!view.top());
                                    if (!view.top()) { camera.orbit(view.yaw(), view.pitch()); }
                                    camera.camera.zoom = i < 2 ? .17f : .25f;
                                    var target = BoardGeometry.center(locations.get(i), 0);
                                    if (i < 2) { target.add(0, 32, 35); }
                                    else { target.add(0, 0, 20); }
                                    camera.center(target);
                                    terrain.animate(1, List.of());
                                    frame.render(terrain, camera, scene);
                                    int inspected = 0;
                                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                            if (!(GpuTestSupport.field(prop, "decorationFeature") instanceof BoardScene.Feature feature)
                                                  || !feature.asset().equals("buildings/authored/" + names.get(i))) { continue; }
                                            assertEquals(requested, GpuTestSupport.<Object>field(prop, "lod"), "Review must draw the requested LOD");
                                            inspected++;
                                        }
                                    }
                                    assertTrue(inspected > 0, names.get(i));
                                    GpuReviewFrame.save(new File(exterior, names.get(i) + "-lod" + lod + "-" + view.name() + ".png"));
                                }
                            }
                        }
                        terrain.update(tinted);
                        for (int lod = 0; lod < 2; lod++) {
                            selectedLod[0] = lod;
                            for (int i = 0; i < names.size(); i++) {
                                camera.setIsometric(true);
                                camera.camera.zoom = i < 2 ? .17f : .25f;
                                var target = BoardGeometry.center(locations.get(i), 0);
                                target.add(0, i < 2 ? 32 : 0, i < 2 ? 35 : 20);
                                camera.center(target);
                                terrain.animate(1, List.of());
                                frame.render(terrain, camera, tinted);
                                GpuReviewFrame.save(new File(exterior, names.get(i) + "-lod" + lod + "-tint.png"));
                            }
                        }
                        int painted = 0;
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                painted += assertPaint((ModelInstance) GpuTestSupport.field(prop, "instance"), GpuColor.linear(Color.valueOf("729bdc")));
                            }
                        }
                        assertTrue(painted > 0, "Civic assets expose marked wall/panel paint regions");
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); assets.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Civic asset review", failure.get()); }
    }

    @Test void heldAuthoredDragHidesInteriorsAndOnlyRebuildsOnRelease() throws Exception {
        Coords at = new Coords(5, 5);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(12, 12), null);
            editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
            editor.pointer(at, 0, 0, false); editor.finishStroke();
            editor.command(new Command(Action.TOOL, "SELECT"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1200, 900);
        config.setInitialVisible(false); config.useVsync(false); config.setForegroundFPS(60);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                List<BoardScene.Tile> installed;
                int phase, held;
                String id;
                @Override public void create() {
                    super.create();
                    boardCamera.center(BoardGeometry.center(at, 1)); boardCamera.camera.zoom = .3f;
                }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Authored drag must settle at phase " + phase);
                        var terrain = (GpuTerrain) GpuTestSupport.field(this, "terrain");
                        if (GpuBoardTestUi.loading(this)) { return; }
                        if (phase == 0) {
                            if (terrain.busy()) { return; }
                            installed = ((BoardScene) GpuTestSupport.field(terrain, "coverScene")).tiles();
                            id = installed.stream().flatMap(t -> t.features().stream()).filter(GpuAuthoredBuilding::supports)
                                  .findFirst().orElseThrow().decoration().id();
                            long generation = source.takeFrame().boardGeneration();
                            source.editorPointer(at, 0, 0, false, id, generation);
                            source.editorPointer(at, .25, .15, true, id, generation);
                            phase = 1;
                        } else if (phase == 1) {
                            if (!source.editorAuthoredGesture()) { return; }
                            assertSame(installed, ((BoardScene) GpuTestSupport.field(terrain, "coverScene")).tiles());
                            assertSame(installed, GpuTestSupport.field(GpuTestSupport.field(this, "skyVisibility"), "tiles"),
                                  "Sky occlusion must not search intermediate drag positions");
                            assertNull(GpuTestSupport.field(terrain, "requested"), "Holding a drag must not queue terrain work");
                            assertNull(GpuTestSupport.field(terrain, "rebuild"), "Even a paused drag must not start slicing");
                            int hidden = 0;
                            for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                                hidden += ((java.util.Set<?>) GpuTestSupport.field(chunk, "hiddenInteriors")).size();
                            }
                            assertTrue(hidden > 0, "The old grey interiors remain hidden throughout the gesture");
                            if (++held == 30) { source.editorPointer(at, .45, .3, true, id, source.takeFrame().boardGeneration()); }
                            if (held == 90) { source.endEditorStroke(); phase = 2; }
                        } else {
                            if (source.editorAuthoredGesture() || terrain.busy()
                                  || installed == ((BoardScene) GpuTestSupport.field(terrain, "coverScene")).tiles()) { return; }
                            for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                                assertTrue(((java.util.Set<?>) GpuTestSupport.field(chunk, "hiddenInteriors")).isEmpty());
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Held authored drag", failure.get()); }
    }

    @Test void debugDemoShowsTwoHeightsGeneratedFloorsAndIndependentHexCollapse() throws Exception {
        File output = new File("build/gpu-board-review/authored-buildings");
        Files.createDirectories(output.toPath());
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(7, 7), null);
        Coords low = new Coords(2, 3), high = low.translated(1);
        editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
        editor.pointer(low, 0, 0, false); editor.finishStroke();
        var file = output.toPath().resolve("Debug Authored Slicing.board2"); editor.save(file);
        Board board = BoardFile.read(file);
        BoardScene original = capture(board);
        assertEquals(2, original.tiles().stream().filter(t -> t.cover().reference() != null).count());
        assertEquals(2, original.tile(low).cover().height()); assertEquals(4, original.tile(high).cover().height());
        var slices = GpuAuthoredBuilding.prepare(original, original.tiles());
        assertEquals(2, slices.size());
        for (var prepared : slices.values()) { assertEquals(prepared.layout().height(), prepared.floors().size()); }
        board.collapseBuilding(low);
        BoardScene collapsed = capture(board);
        assertTrue(collapsed.tile(low).features().stream().noneMatch(GpuAuthoredBuilding::supports));
        assertEquals(4, collapsed.tile(high).cover().height());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1200, 900); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    BoardCamera camera = new BoardCamera(); camera.resize(1200, 900); camera.setIsometric(true);
                    camera.camera.zoom = .22f; camera.center(BoardGeometry.center(low, 1).add(30, 18, 8));
                    terrain.update(original); terrain.animate(1, List.of());
                    frame.render(terrain, camera, original); GpuReviewFrame.save(new File(output, "debug-authored.png"));
                    for (Coords at : List.of(low, high)) {
                        var occupant = GpuTestSupport.occupant(at);
                        terrain.animate(1, List.of(occupant), 0);
                        occupant.model.dispose();
                        assertFalse(faded(terrain).isEmpty(), "A unit inside reveals the runtime floors");
                        frame.render(terrain, camera, original);
                        GpuReviewFrame.save(new File(output, at.equals(low) ? "debug-low-interior.png" : "debug-high-interior.png"));
                    }
                    terrain.update(collapsed); terrain.animate(1, List.of());
                    boolean open = false;
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) { open |= (boolean) GpuTestSupport.field(prop, "openShell"); }
                    }
                    assertTrue(open, "The hex left standing beside a collapsed one shows its floors without a unit");
                    frame.render(terrain, camera, collapsed); GpuReviewFrame.save(new File(output, "debug-hex-collapsed.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Debug authored slicing demo", failure.get()); }
    }

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
                    terrain.update(original); GpuReviewFrame.settle(terrain, null, original, camera);
                    // A unit's visit builds its hex's floors, which stay once it has left.
                    var occupant = GpuTestSupport.occupant(at); occupant.transform.translate(0, 0, 3 * BoardGeometry.level());
                    terrain.animate(1, List.of(occupant), 0); terrain.animate(1, List.of(), 0);
                    occupant.model.dispose();
                    var models = new java.util.HashSet<com.badlogic.gdx.graphics.g3d.Model>();
                    var materials = new java.util.IdentityHashMap<Material, Material>();
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                            if (GpuTestSupport.field(prop, "decorationId") == null) { continue; }
                            var installed = (ModelInstance) GpuTestSupport.field(prop, "instance");
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
                        int shares = 0, painted = 0, fixed = 0, floors = 0;
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            var hidden = (java.util.Set<?>) GpuTestSupport.field(chunk, "hiddenInteriors");
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                if ((boolean) GpuTestSupport.field(prop, "floors")) {
                                    floors++;
                                    assertEquals(pose != original, hidden.contains(GpuTestSupport.<Object>field(prop, "coords")),
                                          "Moved interiors hide until fresh cuts install");
                                    assertNull(GpuTestSupport.field(prop, "previewInstance"), "Floors are not transformed on each drag event");
                                    var drawn = terrain.getClass().getDeclaredMethod("drawn", prop.getClass(), boolean.class);
                                    drawn.setAccessible(true);
                                    for (boolean shadows : List.of(false, true)) {
                                        assertEquals(pose == original, drawn.invoke(terrain, prop, shadows),
                                              "Stale floors must disappear from both colour and depth/shadow passes");
                                    }
                                }
                                if (GpuTestSupport.field(prop, "decorationId") == null) { continue; }
                                var installed = (com.badlogic.gdx.graphics.g3d.ModelInstance) GpuTestSupport.field(prop, "instance");
                                var preview = (com.badlogic.gdx.graphics.g3d.ModelInstance) GpuTestSupport.field(prop, "previewInstance");
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
                                // The side view requests near geometry even when the main view installed a farther LOD.
                                var level = prop.getClass().getDeclaredMethod("level", int.class, ModelInstance.class);
                                level.setAccessible(true);
                                assertPaint((ModelInstance) level.invoke(prop, 0, preview), tint);
                                shares++;
                            }
                        }
                        assertTrue(shares > 40);
                        assertTrue(floors > 0, "The visited hex has its floors to hide");
                        // Some slices contain only unpainted dishes or equipment; the whole building has both kinds.
                        assertTrue(painted > 0, "Authored material metadata must identify its paintable surfaces");
                        assertTrue(fixed > 0, "The building must retain fixed material finishes");
                        var shown = terrain.editorObjects(at).getFirst();
                        assertEquals(3, shown.anchorLevel(), .0001);
                        assertEquals(BoardGeometry.centerX(at) + next.decoration().x() * BoardGeometry.width(), shown.anchor().x, .001);
                    }
                    terrain.update(poses.getFirst());
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        assertTrue(((java.util.Set<?>) GpuTestSupport.field(chunk, "hiddenInteriors")).isEmpty(), "Committed interiors reappear");
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

    @Test void rendersClippedHpgAndArenaWithNeutralCapsAndRestoresTheirGeometryWhenTheBoardGrows() throws Exception {
        File output = new File("build/gpu-board-review/authored-buildings");
        Files.createDirectories(output.toPath());
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(12, 12), null);
        Coords hpg = new Coords(0, 6), arena = new Coords(11, 1);
        place(editor, hpg, "hpg-headquarters", 37);
        editor.command(new Command(Action.OBJECT_VALUE, "stretchX", "1.15"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "stretchY", ".9"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "colour0", "#dc9b72"), null);
        place(editor, arena, "civic-arena", -20);
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
                    camera.center(BoardGeometry.center(arena, 2));
                    frame.render(terrain, camera, scene); GpuReviewFrame.save(new File(output, "arena-edge.png"));
                    int neutral = 0;
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                            if (GpuTestSupport.field(prop, "decorationId") == null) { continue; }
                            var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) GpuTestSupport.field(prop, "instance");
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
                    var before = terrain.editorObjects(arena).getFirst().bounds().max.x;
                    terrain.update(grown); terrain.animate(1, List.of());
                    var after = terrain.editorObjects(arena).getFirst().bounds().max.x;
                    assertTrue(after > before + BoardGeometry.width() * .1f, "Resizing must not reuse the old clipped mesh");
                    frame.render(terrain, camera, grown); GpuReviewFrame.save(new File(output, "arena-edge-grown.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored board-edge review", failure.get()); }
    }

    @Test void rendersAuthoredExamplesAndKeepsTheRemainingSlicesAfterAnchorCollapse() throws Exception {
        File output = new File("build/gpu-board-review/authored-buildings");
        Files.createDirectories(output.toPath());
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(32, 24), null);
        Coords hpg = new Coords(13, 10), arena = new Coords(28, 10), church = new Coords(28, 17);
        place(editor, hpg, "hpg-headquarters", 0);
        place(editor, arena, "civic-arena", -12);
        place(editor, church, "civic-church", 0);
        editor.board().setDescription("Authored buildings: HPG headquarters, a two-hex frontier church and a compact oval civic arena. "
              + "Structural helpers define the floors; the antenna, church crown and arena scoreboard are decorative. The arena field is open.");
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
        for (String asset : List.of("hpg-headquarters", "civic-arena")) {
            smallEditor.command(new Command(Action.ASSET, "buildings/authored/" + asset), null);
            smallEditor.command(new Command(Action.BRUSH_VALUE, "object:scale", ".05"), null);
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
                    for (Coords focus : List.of(hpg, arena, church)) {
                        camera.setIsometric(true);
                        camera.camera.zoom = focus.equals(hpg) ? 1.15f : focus.equals(arena) ? .27f : .19f;
                        var target = BoardGeometry.center(focus, 1);
                        if (focus.equals(hpg)) {
                            camera.orbit(-90, 0);
                            target.add(-65, 0, 180);
                        }
                        if (focus.equals(church)) { target.add(0, 36, 18); }
                        camera.center(target);
                        terrain.animate(1, List.of());
                        frame.render(terrain, camera, original);
                        GpuReviewFrame.save(new File(output, focus.equals(hpg) ? "hpg.png" : focus.equals(arena) ? "arena.png" : "church.png"));
                    }
                    // Review the small receiver and relay at the distance where their near details matter.
                    camera.setIsometric(true);
                    camera.camera.zoom = .27f;
                    camera.center(BoardGeometry.center(hpg, 0).add(310, 110, 38));
                    frame.render(terrain, camera, original);
                    GpuReviewFrame.save(new File(output, "hpg-sensors.png"));
                    camera.camera.zoom = .5f;
                    camera.center(BoardGeometry.center(hpg, 1));
                    // A unit inside turns the building see-through around its runtime floors; a sealed one stays shut.
                    var occupant = GpuTestSupport.occupant(hpg);
                    terrain.animate(1, List.of(occupant), .25f);
                    assertFalse(faded(terrain).isEmpty());
                    frame.render(terrain, camera, original);
                    GpuReviewFrame.save(new File(output, "interior.png"));
                    terrain.update(hallScene);
                    terrain.animate(1, List.of(occupant), .25f);
                    assertFalse(faded(terrain).isEmpty());
                    frame.render(terrain, camera, hallScene);
                    GpuReviewFrame.save(new File(output, "no-floors.png"));
                    terrain.update(sealedScene);
                    terrain.animate(1, List.of(occupant), .25f);
                    occupant.model.dispose();
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
                    GpuReviewFrame.settle(terrain, null, smallScene, camera);
                    assertTrue(terrain.ready(smallScene), "Picking requires the scene's material configuration to be ready");
                    int selectable = 0;
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                            if (GpuTestSupport.field(prop, "decorationId") == null) { continue; }
                            selectable++;
                            assertTrue(((List<?>) GpuTestSupport.field(prop, "supportTriangles")).isEmpty());
                            assertNotEquals("building", GpuTestSupport.field(prop, "receiver"));
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
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) { result.addAll((java.util.Set<?>) GpuTestSupport.field(chunk, "faded")); }
        return result;
    }
}
