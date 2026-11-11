/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Legacy scenery converted to objects (the one conversion every load site runs, b9-design 1.1) stands on its support,
 * opens in the editor as groups whose members can be moved alone, and draws instanced in every view, as the editor
 * draws it (b13: one object renderer).
 */
@Tag("on-demand")
class GpuLegacyImportSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
    private record Sample(String terrain, int level) { }
    private static final Coords TABLES = new Coords(1, 1), ROOF = new Coords(3, 1), POSES = new Coords(0, 8),
          NARROW_ROOF = new Coords(10, 3);
    /** 48x48's car parks (mm-data boards/unofficial/VictorMorson): 0801 on the ground, 4211 on a six-level roof. */
    private static final Coords GROUND_PARK = new Coords(7, 0), ROOF_PARK = new Coords(41, 10);
    private static final String VALENCIA = "unofficial/VictorMorson/128x192 Texlos - Livius - Valencia_City.board";
    private static final Map<Coords, Sample> FIXTURE = new LinkedHashMap<>();
    static {
        // Picnic tables 03 on a hex one level above its neighbours, so the outer tables stand on the transition slope.
        FIXTURE.put(TABLES, new Sample("fluff:93:8", 1));
        FIXTURE.put(ROOF, new Sample("building:2;bldg_elev:2;bldg_cf:50;fluff:6:14", 0));
        // The roof pool's tree stands beyond this building's curved outline; it keeps the roof height.
        FIXTURE.put(new Coords(5, 1), new Sample("building:2;bldg_elev:1;bldg_cf:50;fluff:6:18", 0));
        FIXTURE.put(new Coords(7, 1), new Sample("heavy_industrial:2;fluff:6:14", 0));
        FIXTURE.put(new Coords(9, 1), new Sample("water:1;fluff:6:0", 0));
        // Ice and fuel tanks carry what stands on them.
        FIXTURE.put(new Coords(5, 3), new Sample("water:2;ice:1;fluff:8:00", 0));
        FIXTURE.put(new Coords(7, 3), new Sample("fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100;fluff:2:03", 0));
        FIXTURE.put(new Coords(9, 3), new Sample("road:2:09", 0));
        // Car parks convert their bays to standard decals (no prop) and seat the cars in them; a roof stack, square1 and
        // a herd decode to several objects.
        FIXTURE.put(new Coords(1, 5), new Sample("fluff:5:0", 0));
        FIXTURE.put(new Coords(3, 5), new Sample("fluff:5:2", 0));
        FIXTURE.put(new Coords(5, 5), new Sample("building:2;bldg_elev:1;bldg_cf:50;fluff:6:8", 0));
        FIXTURE.put(new Coords(7, 5), new Sample("fluff:4:0", 0));
        FIXTURE.put(new Coords(9, 5), new Sample("fluff:13:0", 0));
        // A seaport container yard and a quay crane are the woods' container yard look, whose rows the woods own (no
        // trees); a crane tip no crane's boom reaches is a boom segment object.
        FIXTURE.put(new Coords(1, 7), new Sample("woods:1;foliage_elev:2;fluff:80:01", 0));
        FIXTURE.put(new Coords(3, 7), new Sample("woods:1;foliage_elev:2;fluff:81:01", 0));
        FIXTURE.put(new Coords(5, 7), new Sample("fluff:82:1", 0));
        // Trees on snow take their winter form.
        FIXTURE.put(new Coords(9, 7), new Sample("snow:1;fluff:93:8", 0));
        // Merged duplicates: the narrow glass roof is the wide one stretched, an SE crane tip the boom segment turned.
        FIXTURE.put(NARROW_ROOF, new Sample("building:2;bldg_elev:1;bldg_cf:50;fluff:90:2", 0));
        FIXTURE.put(new Coords(10, 5), new Sample("fluff:82:3", 0));
    }

    /**
     * Bridges with legacy pillar art, which the conversion turns into their Pillars toggle: a one-hex deck (no joint, so no pier),
     * an Oanhu span of three over water between roads (paved GLB decks) and a raised span of two over dry ground.
     */
    private static final Map<Coords, String> BRIDGES = new LinkedHashMap<>();
    /** The Oanhu span's middle hex: a paved deck with only bridge neighbours, drawn by its GLB alone. */
    private static final Coords DECK = new Coords(12, 3);
    /** The Oanhu span's end hex, also drawn by its GLB alone at its centre: a beacon's art instead of pillar art. */
    private static final Coords BEACON = new Coords(12, 4);
    /** How many joints of each bridge hex carry a pier (half of it in this hex). */
    private static final Map<Coords, Integer> JOINTS = Map.of(new Coords(12, 2), 1, DECK, 2, new Coords(12, 4), 1,
          new Coords(12, 7), 1, new Coords(12, 8), 1);
    static {
        BRIDGES.put(new Coords(1, 3), "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:4:06");
        BRIDGES.put(new Coords(12, 1), "road:1:09");
        for (int y = 2; y <= 4; y++) {
            BRIDGES.put(new Coords(12, y), "water:1;bridge:1:09;bridge_cf:40;bridge_elev:0;fluff:" + (y == BEACON.getY() ? "2:03" : "4:06"));
        }
        BRIDGES.put(new Coords(12, 5), "road:1:09");
        BRIDGES.put(new Coords(12, 6), "road:1:09");
        for (int y = 7; y <= 8; y++) { BRIDGES.put(new Coords(12, y), "bridge:1:09;bridge_cf:40;bridge_elev:2;fluff:4:06"); }
        BRIDGES.put(new Coords(12, 9), "road:1:09");
    }

    static Board legacy() {
        Board board = Board.createEmptyBoard(13, 10);
        FIXTURE.forEach((at, sample) -> board.setHex(at, new Hex(sample.level(), sample.terrain(), "")));
        BRIDGES.forEach((at, terrain) -> board.setHex(at, new Hex(0, terrain, "")));
        return board;
    }

    /**
     * Pillar art on a bridge converts to the bridge's Pillars toggle: at level heights 18 and 30 one pier under each joint
     * of a toggled span and none under the one-hex deck; a car placed on the span's paved middle deck and the beacon art
     * on its end deck, both drawn by their GLB alone, stand on the deck, not on the lake bed.
     */
    @Test
    void convertedBridgeArtStandsOnItsDecksAndPiersStandUnderTheJointsAtEveryLevelHeight() throws Exception {
        Board converted = legacy();
        BoardSceneryLayouts.convert(converted);
        // A car on the span's paved middle deck, which only its GLB draws: it stands on the deck, not on the lake bed.
        converted.getHex(DECK).setDecorations(List.of(new BoardDecoration("deck-car", "prop", "scenery/vehicles/car", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.on("bridge", 0), 0)));
        BoardScene scene = BoardScenes.scene(converted);
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1400, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    for (int levelHeight : new int[] { BoardGeometry.DEFAULTS.levelHeight(), 30 }) {
                        BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, .8f));
                        Map<Coords, String> piers = new LinkedHashMap<>();
                        var props = props(frame, scene, "legacy-import-converted-" + levelHeight + ".png", piers);
                        // Art on a deck that only its GLB draws stands on the deck, not on the lake bed.
                        var end = props.get(BEACON);
                        var endDeck = end.stream().filter(p -> !p.structure()).findFirst().orElseThrow();
                        var beacon = end.stream().filter(Installed::structure).findFirst().orElseThrow();
                        assertTrue(beacon.bounds().min.z >= endDeck.bounds().min.z && beacon.bounds().min.z <= endDeck.bounds().max.z,
                              "The beacon stands on the paved deck " + endDeck.bounds() + " at level height " + levelHeight + ": "
                                    + beacon.bounds());
                        // One pier under each joint of a toggled span and nowhere else.
                        System.out.printf("PIERS at level height %d: %s%n", levelHeight, piers);
                        assertEquals(JOINTS.keySet(), piers.keySet(), "No pier under the one-hex bridge");
                        JOINTS.forEach((at, joints) -> assertTrue(piers.get(at).startsWith(joints + " joints"),
                              at + ": " + piers.get(at)));
                        var deck = props.get(DECK);
                        var glb = deck.stream().filter(p -> !p.structure()).findFirst().orElseThrow();
                        var car = deck.stream().filter(Installed::structure).findFirst().orElseThrow();
                        assertTrue(car.bounds().min.z >= glb.bounds().min.z && car.bounds().min.z <= glb.bounds().max.z,
                              "The car stands on the paved deck " + glb.bounds() + " at level height " + levelHeight + ": "
                                    + car.bounds());
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { BoardGeometry.tune(BoardGeometry.DEFAULTS); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Converted bridge art", failure.get()); }
    }

    /**
     * Converted objects draw in game views as in the editor (b13): decoded trees in the tree stand, every other object's
     * parts instanced, a mirrored table's too (in a batch of its own), none packed with the scenery, every object keeping
     * its id, and faded with an occupied building. 48x48 loaded as a game loads a sheet (the server's sheet loader, then
     * the game's {@link GpuBoardSource}) has its car parks 0801 and 4211 with their car-park decal rows and seated cars, at
     * 4211 on the six-level roof.
     * Capture {@code b9-48x48-game.png}: 0801 and 4211 side by side.
     */
    @Test
    void gameViewsDrawImportedObjectsInstanced() throws Exception {
        Board imported = legacy();
        BoardSceneryLayouts.convert(imported);
        // Poses the instance format cannot hold: a mirrored, a tilted and an unevenly stretched table.
        String table = "scenery/parks/picnic-table";
        imported.getHex(POSES).setDecorations(List.of(
              new BoardDecoration("mirrored", "prop", table, "", -.25, 0, 30, true, 1, BoardDecoration.Placement.ground(), 0, false),
              new BoardDecoration("tilted", "prop", table, "", 0, .25, 0, false, 1, BoardDecoration.Placement.ground(), 0, false, 20, 0),
              new BoardDecoration("stretched", "prop", table, "", .25, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false)
                    .withStretch(new BoardDecoration.Stretch(1.5, 1, 1))));
        BoardScene after = BoardScenes.scene(imported);
        // The converted board keeps the picnic token for 2D players; the 3D board never draws its art again.
        assertTrue(imported.getHex(TABLES).containsTerrain(Terrains.FLUFF));
        assertNull(after.tile(TABLES).tilesetScenery(), "No sprite for imported objects: the Tactical View draws them once, in 3D");
        assertTrue(after.tile(TABLES).features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY),
              "No legacy rows beside the objects");
        // 48x48 as a game loads it (BoardFile.loadSheet, as TWBoardTransformer loads each sheet on the server, then the
        // game's own source).
        File sheetFile = new File(megamek.common.Configuration.boardsDir(), "unofficial/VictorMorson/48x48.board");
        BoardScene gameSheet;
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(sheetFile, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            gameSheet = fixture.source.takeFrame().scene();
        }
        for (Coords park : List.of(GROUND_PARK, ROOF_PARK)) {
            var objects = gameSheet.tile(park).features().stream().map(BoardScene.Feature::decoration)
                  .filter(java.util.Objects::nonNull).toList();
            assertTrue(objects.stream().anyMatch(o -> o.asset().startsWith("decal/car-park/")), park.getBoardNum()
                  + ": its rows of bays, " + objects);
            assertTrue(objects.stream().anyMatch(o -> o.asset().startsWith("scenery/vehicles/car")), park.getBoardNum()
                  + ": its seated cars, " + objects);
        }
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var game = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    game.update(after);
                    int objects = after.tile(TABLES).features().stream().filter(f -> f.decoration() != null).toList().size();
                    assertEquals(17, objects);
                    var gameProps = installed(game).stream().filter(p -> p.coords().equals(TABLES)).toList();
                    assertEquals(17, gameProps.stream().filter(p -> p.id() != null).count(),
                          "Game views keep every object's id, as the editor does (picking data)");
                    assertEquals(2, gameProps.stream().filter(p -> p.id() != null && p.tree()).count(),
                          "Decoded trees join the instanced tree stand");
                    assertTrue(gameProps.stream().filter(p -> !p.tree()).allMatch(Installed::structure),
                          "The Tactical View keeps imported objects other than plants in 3D");
                    // Every placed object's parts draw instanced or one by one, never packed with the scenery; only the
                    // poses the instance data cannot hold draw one by one.
                    int instanced = 0, loose = 0, objectParts = 0, mirroredInstanced = 0, tableParts = 0, roofParts = 0;
                    for (Object chunk : (List<?>) GpuTestSupport.field(game, "chunks")) {
                        instanced += ((com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "decorationRenderables")).size;
                        loose += ((com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "looseDecorations")).size;
                        for (Object owner : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "decorationOwners")) {
                            if ("mirrored".equals(GpuTestSupport.field(owner, "decorationId"))) { mirroredInstanced++; }
                        }
                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                            var feature = (BoardScene.Feature) GpuTestSupport.field(prop, "decorationFeature");
                            if (feature == null || BoardRail.route(feature) || GpuTestSupport.field(prop, "treeAsset") != null) { continue; }
                            int parts = GpuTerrainDepth.snapshot(List.of((ModelInstance) GpuTestSupport.field(prop, "instance"))).size;
                            objectParts += parts;
                            if ("mirrored".equals(GpuTestSupport.field(prop, "decorationId"))) { tableParts = parts; }
                            if (NARROW_ROOF.equals(GpuTestSupport.field(prop, "coords"))) { roofParts = parts; }
                        }
                    }
                    System.out.printf("PERF legacy-import object parts: %d instanced, %d one by one, of %d%n", instanced,
                          loose, objectParts);
                    var poses = installed(game).stream().filter(p -> p.coords().equals(POSES)).toList();
                    assertEquals(Set.of("mirrored", "tilted", "stretched"),
                          poses.stream().map(Installed::id).collect(java.util.stream.Collectors.toSet()));
                    assertTrue(tableParts > 0 && roofParts > 0);
                    assertEquals(objectParts, instanced + loose, "Every placed object's parts draw as objects, none packed");
                    assertEquals(tableParts, mirroredInstanced, "The mirrored table draws instanced");
                    // The narrow roof's width stretch does not fit the instance data.
                    assertEquals(2 * tableParts + roofParts, loose,
                          "Only the tilted and stretched tables and the narrow glass roof draw one by one");
                    assertTrue(instanced >= 15, instanced + " instanced parts");

                    // A unit inside the roof's building fades the roof objects with the building.
                    var builder = new ModelBuilder();
                    var unitModel = builder.createBox(4, 4, 4, new Material(ColorAttribute.createDiffuse(Color.RED)),
                          VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                    var unit = new ModelInstance(unitModel);
                    unit.transform.setToTranslation(BoardGeometry.center(ROOF, .5f));
                    game.animate(0, List.of(unit), .3f);
                    boolean roofFaded = false;
                    for (Object chunk : (List<?>) GpuTestSupport.field(game, "chunks")) {
                        for (Object prop : (Set<?>) GpuTestSupport.field(chunk, "faded")) {
                            Installed placed = installed(prop);
                            roofFaded |= placed.coords().equals(ROOF) && placed.bounds().min.z > 2 * BoardGeometry.level() - 1;
                        }
                    }
                    unitModel.dispose();
                    assertTrue(roofFaded, "Roof objects fade with their occupied building");

                    // 48x48 in the game view: its roof cars on the roof.
                    var terrain = new GpuTerrain();
                    try {
                        var camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setIsometric(true);
                        frame.prepare(terrain, camera, gameSheet);
                        terrain.update(gameSheet);
                        sideBySide(frame, terrain, camera, gameSheet, new File(OUTPUT, "b9-48x48-game.png"),
                              List.of(GROUND_PARK, ROOF_PARK), new float[] { 0, 6 });
                        List<Float> roofCars = new ArrayList<>();
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                                if (ROOF_PARK.equals(GpuTestSupport.field(prop, "coords"))
                                      && ((ModelInstance) GpuTestSupport.field(prop, "instance")).nodes.first().id.startsWith("car")) {
                                    roofCars.add(((BoundingBox) GpuTestSupport.field(prop, "bounds")).min.z);
                                }
                            }
                        }
                        System.out.printf("48x48 game view: cars on 4211 at %s (six levels: %.2f)%n", roofCars,
                              6 * BoardGeometry.level());
                        assertFalse(roofCars.isEmpty(), "Cars on the roof car park 4211");
                        for (float z : roofCars) {
                            assertEquals(6 * BoardGeometry.level(), z, .5f * BoardGeometry.level(),
                                  "A car of 4211 stands on its six-level roof");
                        }
                    } finally { terrain.dispose(); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { game.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Imported objects in game views", failure.get()); }
    }

    /**
     * One isometric close-up per hex ({@code levels}: the height in levels to centre on), saved side by side as one PNG.
     */
    private static void sideBySide(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene, File file,
          List<Coords> hexes, float[] levels) throws Exception {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var sheet = new com.badlogic.gdx.graphics.Pixmap(width * hexes.size(), height, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        sheet.setBlending(com.badlogic.gdx.graphics.Pixmap.Blending.None);
        try {
            for (int i = 0; i < hexes.size(); i++) {
                camera.center(BoardGeometry.center(hexes.get(i), levels[i]));
                camera.camera.zoom = .25f;
                camera.update();
                GpuReviewFrame.settle(terrain, null, scene, camera);
                frame.render(terrain, camera, scene);
                var shot = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(0, 0, width, height);
                sheet.drawPixmap(shot, i * width, 0);
                shot.dispose();
            }
            var pixels = sheet.getPixels();
            for (int i = 3; i < pixels.limit(); i += 4) { pixels.put(i, (byte) 255); }
            com.badlogic.gdx.graphics.PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(file), sheet, -1, true);
        } finally { sheet.dispose(); }
    }

    @Test
    void picnicTablesOpenAsOneGroupAndATableMovesAlone() throws Exception {
        Path file = Files.createTempDirectory("legacy-import").resolve("Picnic.board");
        try (OutputStream output = Files.newOutputStream(file)) { legacy().save(output, false); }
        Game preview = new Game();
        // The map preview's own load, which converts the file.
        preview.setBoard(GpuBoardWindow.loadPreview(file.toFile()));
        var previewDraws = new java.util.concurrent.atomic.AtomicInteger();
        run(new FutureTask<>(() -> new GpuMapSource(preview, null, null)), (view, source) -> {
            closeUp(view);
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-preview.png"));
            previewDraws.set(opaqueDraws(view));
        });

        var session = new AtomicReference<BoardEditorSession>();
        run(new FutureTask<>(() -> {
            var editor = new BoardEditorSession(BoardEditorBlueprint.get());
            editor.open(file); session.set(editor);
            return new GpuMapSource(editor.game(), null, editor);
        }), (view, source) -> {
            assertTrue(source.editorState().message().startsWith("Imported Picnic.board: "), source.editorState().message());
            assertEquals("Picnic.board2 (imported, unsaved)", source.editorState().title());
            GpuTerrain terrain = terrain(view);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (terrain.editorObjects(TABLES).size() < 17 || terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, "The imported objects must load");
                view.render();
            }
            closeUp(view);
            settle(view, source);
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-editor.png"));
            // Placed objects draw instanced, in the map preview as in the editor: one draw per model part (mesh range
            // and material), not one per placed part.
            int editorDraws = opaqueDraws(view);
            Set<List<Object>> modelParts = new java.util.HashSet<>();
            int placedParts = 0, loose = 0;
            for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                for (Object value : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "decorationRenderables")) {
                    var part = (com.badlogic.gdx.graphics.g3d.Renderable) value;
                    modelParts.add(List.of(part.meshPart.mesh, part.meshPart.offset, part.meshPart.size, part.material));
                    placedParts++;
                }
                loose += ((com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "looseDecorations")).size;
            }
            System.out.printf("PERF legacy-import close-up opaque draws: preview %d, editor %d; editor object parts: %d placed, "
                  + "%d model parts, %d loose%n", previewDraws.get(), editorDraws, placedParts, modelParts.size(), loose);
            assertTrue(modelParts.size() < placedParts, "The fixture repeats objects");
            assertEquals(previewDraws.get(), editorDraws, "The editor draws the close-up as the map preview does");

            String table = "L1_1_0";
            clickAt(top(view, terrain, table)); settle(view, source);
            assertEquals(17, source.editorState().selection().size(), "Clicking a table selects its whole group");
            assertFalse(source.editorState().group().isEmpty());
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-group-selected.png"));
            clickAt(top(view, terrain, table)); settle(view, source);
            assertEquals(List.of(table), source.editorState().selection().stream().map(BoardEditorSession.Selection::object).toList(),
                  "A second click selects that table alone");

            BoardDecoration before = object(session.get(), table), sibling = object(session.get(), "L1_1_1");
            Map<Object, Object> batches = objectBatches(terrain);
            Vector2 from = top(view, terrain, table);
            var input = Gdx.input.getInputProcessor();
            GpuBoardTestUi.withModifiers(0, () -> input.touchDown(Math.round(from.x), Math.round(from.y), 0, Input.Buttons.LEFT));
            for (int step = 1; step <= 6; step++) {
                input.touchDragged(Math.round(from.x) + step * 8, Math.round(from.y) + step * 3, 0); settle(view, source);
            }
            input.touchUp(Math.round(from.x) + 48, Math.round(from.y) + 18, 0, Input.Buttons.LEFT); settle(view, source);
            BoardDecoration moved = object(session.get(), table);
            assertTrue(moved.x() > before.x() + .05, "The drilled-down table moved: " + before.x() + " -> " + moved.x());
            assertEquals(before.group(), moved.group(), "It stays in its group");
            assertEquals(sibling, object(session.get(), "L1_1_1"), "Its siblings stay where they were");
            while (terrain.busy()) { view.render(); }
            settle(view, source);
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-table-moved.png"));
            // The commit keeps the objects' instanced batches: an edit copies no object vertices again.
            Map<Object, Object> kept = objectBatches(terrain);
            assertFalse(batches.isEmpty());
            for (var entry : batches.entrySet()) {
                assertSame(entry.getValue(), kept.get(entry.getKey()), "Instanced batch kept for " + entry.getKey());
            }

            // A tree's inspector has the Snow toggle (on by default; a table has none). Off keeps that tree bare on snow.
            var stageRoot = GpuBoardTestUi.stage().getRoot();
            assertNull(stageRoot.findActor("editor-object-snow"), "Only trees have a Snow toggle");
            var tree = new AtomicReference<String>();
            SwingUtilities.invokeAndWait(() -> tree.set(session.get().board().getHex(TABLES).getDecorations().stream()
                  .filter(o -> BoardFeatures.hasSnowForm(o.asset())).findFirst().orElseThrow().id()));
            clickAt(top(view, terrain, tree.get())); settle(view, source);
            assertEquals(List.of(tree.get()), source.editorState().selection().stream().map(BoardEditorSession.Selection::object).toList());
            assertNotNull(stageRoot.findActor("editor-object-snow"));
            assertFalse(object(session.get(), tree.get()).bare());
            GpuBoardTestUi.click("editor-object-snow"); settle(view, source);
            assertTrue(object(session.get(), tree.get()).bare(), "The toggle keeps the tree bare");
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-tree-snow-toggle.png"));

            // The selected tree stands in the instanced tree stand; dragging it moves its stand entry, the old place
            // empties, and object picking still finds it at the new place.
            while (terrain.busy()) { view.render(); }
            settle(view, source);
            Vector3 planted = standPlace(terrain, tree.get());
            assertTrue(standHas(terrain, planted), "The editor tree stands in the tree stand");
            Vector2 grab = top(view, terrain, tree.get());
            GpuBoardTestUi.withModifiers(0, () -> input.touchDown(Math.round(grab.x), Math.round(grab.y), 0, Input.Buttons.LEFT));
            for (int step = 1; step <= 4; step++) {
                input.touchDragged(Math.round(grab.x) - step * 10, Math.round(grab.y) + step * 4, 0); settle(view, source);
            }
            Vector3 dragged = standPlace(terrain, tree.get());
            assertTrue(dragged.dst(planted) > 1, "The tree follows the drag: " + planted + " -> " + dragged);
            assertTrue(standHas(terrain, dragged) && !standHas(terrain, planted), "The stand draws the tree once, where it is");
            input.touchUp(Math.round(grab.x) - 40, Math.round(grab.y) + 16, 0, Input.Buttons.LEFT); settle(view, source);
            while (terrain.busy()) { view.render(); }
            settle(view, source);
            Vector3 dropped = standPlace(terrain, tree.get());
            assertTrue(standHas(terrain, dropped) && !standHas(terrain, planted));
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-tree-moved.png"));
            var scene = (BoardScene) GpuTestSupport.field(view, "scene");
            var down = new com.badlogic.gdx.math.collision.Ray(new Vector3(dropped).add(0, 0, 500), new Vector3(0, 0, -1));
            assertEquals(tree.get(), terrain.decorationHit(scene, down).id(), "Object picking finds the stand tree");
            assertTrue(objectParts(terrain) > 0, "The 3D view draws the other placed objects");

            // The bridge's Pillars toggle, from its Edit panel: the imported span has its piers; off removes them from the
            // whole span, Undo brings them back. A pier is the bridge in the side view too.
            long generation = source.takeFrame().boardGeneration();
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_AT,
                  DECK.getX() + "," + DECK.getY(), ""), generation);
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.COMPONENT, "bridge"), generation);
            settle(view, source);
            if (stageRoot.findActor("editor-hex-section") == null) { GpuBoardTestUi.click("editor-side-view-toggle"); }
            view.boardCamera.center(BoardGeometry.center(DECK, 0));
            for (int frame = 0; frame < 5 || terrain.busy(); frame++) { view.render(); }
            settle(view, source);
            assertEquals(JOINTS.keySet(), piers(terrain).keySet(), "Imported pillar art: piers under the spans' joints");
            var pillars = (megamek.client.ui.gdx.UiButton) stageRoot.findActor("editor-bridge-pillars");
            assertNotNull(pillars, "The Bridge panel has the Pillars toggle");
            assertTrue(pillars.isChecked());
            var onPier = new com.badlogic.gdx.math.collision.Ray(new Vector3(BoardGeometry.center(DECK, 0)
                  .lerp(BoardGeometry.center(DECK.translated(0), 0), .45f)).add(0, 0, 200), new Vector3(0, 0, -1));
            assertEquals("bridge", terrain.editorSectionHit(DECK, onPier).component(), "Pressing a pier opens the bridge");
            for (int frame = 0; frame < 30; frame++) { view.render(); }
            int[] sectionOn = pixels(stageRoot.findActor("editor-hex-section"));
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-piers-on.png"));
            GpuBoardTestUi.click("editor-bridge-pillars");
            settle(view, source);
            for (int frame = 0; frame < 5 || terrain.busy(); frame++) { view.render(); }
            settle(view, source);
            assertTrue(piers(terrain).keySet().stream().noneMatch(at -> at.getY() <= 5), "Off for the whole Oanhu span: " + piers(terrain));
            assertEquals(java.util.Set.of(new Coords(12, 7), new Coords(12, 8)), piers(terrain).keySet(), "Another span keeps its piers");
            for (int frame = 0; frame < 30; frame++) { view.render(); }
            // The side view shows the piers standing in the water too: through the liquid's tint, down to its bed.
            int[] sectionOff = pixels(stageRoot.findActor("editor-hex-section"));
            int changed = 0;
            for (int i = 0; i < Math.min(sectionOn.length, sectionOff.length); i++) {
                for (int shift = 8; shift < 32; shift += 8) {
                    if (Math.abs((sectionOn[i] >>> shift & 0xff) - (sectionOff[i] >>> shift & 0xff)) > 12) { changed++; break; }
                }
            }
            System.out.printf("SECTION pixels changed by the Pillars toggle: %d of %d%n", changed, sectionOn.length);
            assertTrue(changed > 200, "The side view shows the submerged piers: " + changed + " pixels change");
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-piers-off.png"));
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), source.takeFrame().boardGeneration());
            settle(view, source);
            for (int frame = 0; frame < 5 || terrain.busy(); frame++) { view.render(); }
            settle(view, source);
            assertEquals(JOINTS.keySet(), piers(terrain).keySet(), "One Undo restores the span's piers");
            // Undo clears the selection; reopen the bridge inspector before continuing with its controls.
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.COMPONENT, "bridge"),
                  source.takeFrame().boardGeneration());
            settle(view, source);
            // The runtime middle deck is what hovering, placing and the side view find there.
            var deckScene = (BoardScene) GpuTestSupport.field(view, "scene");
            var onDeck = new com.badlogic.gdx.math.collision.Ray(BoardGeometry.center(DECK, 0).add(0, 0, 200), new Vector3(0, 0, -1));
            var walk = terrain.walkableHit(deckScene, onDeck);
            float walkZ = onDeck.getEndPoint(new Vector3(), (float) Math.sqrt(walk.distance())).z;
            assertEquals(BoardBridge.deckZ(deckScene.tile(DECK)), walkZ, .5f * BoardGeometry.hexScale(), "The hover ring stands on the deck");
            assertEquals("bridge", terrain.placementHit(deckScene, onDeck).receiver(), "A click places on the deck, not the lake bed");
            assertEquals("bridge", terrain.editorSectionHit(DECK, onDeck).component(), "The deck opens the bridge");
            // Built or Natural for the whole span: a rock arch has no piers and no Pillars toggle; Undo restores both.
            assertNotNull(stageRoot.findActor("editor-bridge-built"));
            GpuBoardTestUi.click("editor-bridge-natural");
            settle(view, source);
            for (int frame = 0; frame < 5 || terrain.busy(); frame++) { view.render(); }
            settle(view, source);
            assertNull(stageRoot.findActor("editor-bridge-pillars"), "A natural bridge has no Pillars toggle");
            assertTrue(piers(terrain).keySet().stream().noneMatch(at -> at.getY() <= 5), "No piers under the rock arch: " + piers(terrain));
            for (int frame = 0; frame < 30; frame++) { view.render(); }
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-natural.png"));
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), source.takeFrame().boardGeneration());
            settle(view, source);
            for (int frame = 0; frame < 5 || terrain.busy(); frame++) { view.render(); }
            settle(view, source);
            assertEquals(JOINTS.keySet(), piers(terrain).keySet(), "One Undo restores the built span and its piers");
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.COMPONENT, "bridge"),
                  source.takeFrame().boardGeneration());
            settle(view, source);
            assertNotNull(stageRoot.findActor("editor-bridge-pillars"));
            // Built: Truss for the whole span, then a colour from its swatches, as a placed object's (b10-f1): one undo
            // step each; the piers stay.
            assertNull(stageRoot.findActor("editor-colour-0-0"), "A standard deck has no colour");
            GpuBoardTestUi.click("editor-bridge-truss");
            settle(view, source);
            assertTrue(megamek.common.board.HexAppearance.truss(session.get().board().getHex(DECK).getAppearance()));
            GpuBoardTestUi.click("editor-colour-0-6");
            settle(view, source);
            for (int frame = 0; frame < 5 || terrain.busy(); frame++) { view.render(); }
            settle(view, source);
            assertEquals("#24407a", session.get().board().getHex(DECK).getAppearance().get("bridge").colours().slot(0), "Blue");
            assertEquals(JOINTS.keySet(), piers(terrain).keySet(), "The truss keeps the span's piers");
            assertNotNull(stageRoot.findActor("editor-colour-0-0"));
            for (int frame = 0; frame < 30; frame++) { view.render(); }
            new File(OUTPUT, "b10-f1").mkdirs();
            GpuBoardTestUi.capture(new File(OUTPUT, "b10-f1/editor-truss-panel.png"));
            for (int undo = 0; undo < 2; undo++) {
                source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), source.takeFrame().boardGeneration());
                settle(view, source);
            }
            assertEquals(megamek.common.board.HexAppearance.PILLARS, session.get().board().getHex(DECK).getAppearance().get("bridge"),
                  "Two undo steps restore the standard deck");
            // The raised span's side view shows its pier half on the inspected hex's joint.
            Coords raised = new Coords(12, 8);
            source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_AT,
                  raised.getX() + "," + raised.getY(), ""), source.takeFrame().boardGeneration());
            settle(view, source);
            view.boardCamera.center(BoardGeometry.center(raised, 0));
            for (int frame = 0; frame < 30 || terrain.busy(); frame++) { view.render(); }
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-piers-side.png"));

            // The reduced palette: one entry per real object or stamp; seaport props and one animal card per species.
            var root = GpuBoardTestUi.stage().getRoot();
            GpuBoardTestUi.category("industry"); settle(view, source);
            assertNotNull(root.findActor("editor-section-industry/seaport"));
            GpuBoardTestUi.category("props"); settle(view, source);
            assertNotNull(root.findActor("editor-subcategory-animals"));
            assertNull(BoardEditorBlueprint.get().asset("scenery/saxarba/SMV_Fluff/FluffSystem-07-Garden-02-Table-1-03"),
                  "No baked picnic-table composite");
            var search = (com.badlogic.gdx.scenes.scene2d.ui.TextField) root.findActor("editor-search");
            search.setText("picnic");
            search.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
            settle(view, source);
            assertNotNull(root.findActor("editor-library-scenery/parks/picnic-table"), "One picnic table entry");
            search.setText("cluster");
            search.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
            settle(view, source);
            assertNotNull(root.findActor("editor-library-stamp/tree-cluster-1"), "Tree clusters are stamps");
            for (int frame = 0; frame < 60; frame++) { view.render(); }
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-palette.png"));

            // Transportation · Bridges: a "Bridge" (built), a "Natural bridge" and a "Truss bridge" card, each with its own
            // sample; no plain preset.
            GpuBoardTestUi.category("transportation"); settle(view, source);
            search.setText("bridge");
            settle(view, source);
            assertNull(root.findActor("editor-library-bridge/default"), "The single Bridge preset is gone");
            List<com.badlogic.gdx.scenes.scene2d.ui.Image> samples = new ArrayList<>();
            for (String key : List.of("bridge/built", "bridge/natural", "bridge/truss")) {
                com.badlogic.gdx.scenes.scene2d.ui.TextButton card = root.findActor("editor-library-" + key);
                assertNotNull(card, key + " card");
                assertEquals(switch (key) {
                    case "bridge/built" -> "Bridge";
                    case "bridge/natural" -> "Natural bridge";
                    default -> "Truss bridge";
                }, card.getText().toString(), key);
                samples.add(card.findActor("editor-card-preview"));
            }
            for (int frame = 0; frame < 600 && samples.stream().anyMatch(sample -> sample.getDrawable() == null); frame++) {
                view.render();
            }
            for (int frame = 0; frame < 10; frame++) { view.render(); }
            int[] built = pixels(samples.get(0)), natural = pixels(samples.get(1));
            int differ = 0;
            for (int i = 0; i < Math.min(built.length, natural.length); i++) { if (built[i] != natural[i]) { differ++; } }
            System.out.printf("CARDS built and natural bridge samples differ on %d of %d pixels%n", differ, built.length);
            assertTrue(differ > built.length / 10, "Two distinct samples: " + differ + " of " + built.length);
            int[] truss = pixels(samples.get(2));
            int steel = 0;
            for (int i = 0; i < Math.min(built.length, truss.length); i++) { if (built[i] != truss[i]) { steel++; } }
            System.out.printf("CARDS built and truss bridge samples differ on %d of %d pixels%n", steel, built.length);
            assertTrue(steel > built.length / 50, "The Truss card shows its truss: " + steel + " of " + built.length);
            GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-bridge-cards.png"));
        });
    }

    /**
     * Valencia City, the largest shipped legacy board: the read, the server's sheet load (read and
     * convert, {@code BoardFile.loadSheet}) and the editor's open; the Board packet and savegame sizes as read and
     * converted; and the game view (the game's {@link GpuBoardSource}) and the editor view of the same file, which draw
     * placed objects alike since b13: terrain build time, frame time and GL draws, overview and close. Run with
     * gpuBoardBenchmark.
     */
    @Test
    @Tag("gpu-benchmark")
    void measuresValenciaInTheGameAndTheEditor() throws Exception {
        Path file = megamek.common.Configuration.boardsDir().toPath().resolve(VALENCIA);
        long start = System.nanoTime();
        Board read = BoardFile.read(file);
        double readMs = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        Board converted = BoardFile.loadSheet(file.toFile(), false, true);
        System.out.printf("PERF valencia read %.0f ms, sheet load (read and convert) %.0f ms%n", readMs,
              (System.nanoTime() - start) / 1e6);
        // The close view centres on the hex with the most converted objects.
        Coords densest = new Coords(0, 0);
        for (int x = 0; x < converted.getWidth(); x++) {
            for (int y = 0; y < converted.getHeight(); y++) {
                if (converted.getHex(x, y).getDecorations().size() > converted.getHex(densest).getDecorations().size()) {
                    densest = new Coords(x, y);
                }
            }
        }
        Coords closeView = densest;
        System.out.printf("PERF valencia close view at %s%n", closeView);
        for (Board board : List.of(read, converted)) {
            var packet = new java.io.ByteArrayOutputStream();
            try (var output = new java.io.ObjectOutputStream(packet)) { output.writeObject(board); }
            String xml = megamek.common.util.SerializationHelper.getSaveGameXStream().toXML(board);
            int objects = 0;
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { objects += board.getHex(x, y).getDecorations().size(); }
            }
            System.out.printf("PERF valencia %s objects=%d packet=%d bytes savegame-xml=%d chars%n",
                  board == read ? "as read" : "converted", objects, packet.size(), xml.length());
        }
        for (boolean editing : new boolean[] { false, true }) {
            String label = editing ? "editor" : "game";
            Steps<BoardSource> steps = (view, source) -> {
                for (String shot : List.of("overview", "close")) {
                    if (shot.equals("close")) {
                        view.boardCamera.center(BoardGeometry.center(closeView, 0));
                        view.boardCamera.zoom(.12f / view.boardCamera.camera.zoom);
                    }
                    GpuTerrain terrain = terrain(view);
                    long built = System.nanoTime();
                    for (int frame = 0; frame < 20 || terrain.busy(); frame++) { view.render(); }
                    double build = (System.nanoTime() - built) / 1e6;
                    double[] samples = new double[60];
                    for (int frame = 0; frame < samples.length; frame++) {
                        long frameStart = System.nanoTime();
                        view.render();
                        Gdx.gl.glFinish();
                        samples[frame] = (System.nanoTime() - frameStart) / 1e6;
                    }
                    java.util.Arrays.sort(samples);
                    // One more frame with the GL profiler: draws and GL calls per render stage (deterministic).
                    var stages = ((ProfiledView) view).profile();
                    int draws = stages.values().stream().mapToInt(counts -> counts[0]).sum();
                    int calls = stages.values().stream().mapToInt(counts -> counts[1]).sum();
                    System.out.printf("PERF valencia %s %s: settle %.0f ms, frame median %.1f ms, p90 %.1f ms, "
                                + "draws %d (opaque %d, shadows %d), GL calls %d (opaque %d)%n", label, shot,
                          build, samples[samples.length / 2], samples[samples.length * 9 / 10], draws,
                          stages.getOrDefault("opaque terrain", new int[2])[0],
                          stages.getOrDefault("geometry shadows", new int[2])[0], calls,
                          stages.getOrDefault("opaque terrain", new int[2])[1]);
                    GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-valencia-" + label + "-" + shot + ".png"));
                }
            };
            if (!editing) {
                try (var fixture = GpuBoardFixture.create(converted)) {
                    run(new FutureTask<BoardSource>(() -> fixture.source), steps);
                }
            } else {
                run(new FutureTask<BoardSource>(() -> {
                    var editor = new BoardEditorSession();
                    long opening = System.nanoTime();
                    editor.open(file);
                    System.out.printf("PERF valencia editor open %.0f ms%n", (System.nanoTime() - opening) / 1e6);
                    return new GpuMapSource(editor.game(), null, editor);
                }), steps);
            }
        }
    }

    private static Map<Coords, List<Installed>> props(GpuReviewFrame frame, BoardScene scene, String screenshot,
          Map<Coords, String> piers) throws Exception {
        var terrain = new GpuTerrain();
        try {
            var camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(true);
            camera.fit(scene);
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            terrain.animate(0, List.of());
            frame.render(terrain, camera, scene);
            GpuReviewFrame.save(new File(OUTPUT, screenshot));
            Map<Coords, List<Installed>> result = new LinkedHashMap<>();
            for (Installed prop : installed(terrain)) {
                if (FIXTURE.containsKey(prop.coords()) || BRIDGES.containsKey(prop.coords())) {
                    result.computeIfAbsent(prop.coords(), at -> new ArrayList<>()).add(prop);
                }
            }
            piers.putAll(piers(terrain));
            return result;
        } finally { terrain.dispose(); }
    }

    record Installed(Coords coords, BoundingBox bounds, boolean tree, String id, boolean structure) { }

    /** The RGBA pixels of the frame just rendered inside the actor's bounds. */
    private static int[] pixels(com.badlogic.gdx.scenes.scene2d.Actor actor) {
        var stage = actor.getStage();
        Vector2 low = stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2()));
        Vector2 high = stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth(), actor.getHeight())));
        float sx = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        float sy = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
        int x = Math.round(Math.min(low.x, high.x) * sx), y = Math.round((Gdx.graphics.getHeight() - Math.max(low.y, high.y)) * sy);
        var image = com.badlogic.gdx.graphics.Pixmap.createFromFrameBuffer(x, y,
              Math.round(Math.abs(high.x - low.x) * sx), Math.round(Math.abs(high.y - low.y) * sy));
        try {
            int[] result = new int[image.getWidth() * image.getHeight()];
            for (int i = 0; i < result.length; i++) { result[i] = image.getPixel(i % image.getWidth(), i / image.getWidth()); }
            return result;
        } finally { image.dispose(); }
    }

    /** The installed piers of each hex that has any: how many of its edges carry one, its pier facets and their bounds. */
    static Map<Coords, String> piers(GpuTerrain terrain) throws Exception {
        Map<Coords, String> result = new LinkedHashMap<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (var entry : ((Map<?, ?>) GpuTestSupport.field(chunk, "tileMeshes")).entrySet()) {
                var shape = (BoardBridge.Shape) GpuTestSupport.field(entry.getValue(), "bridgeShape");
                var at = (Coords) entry.getKey();
                var facets = shape == null ? List.<BoardBridge.Facet>of()
                      : shape.facets().stream().filter(f -> f.part() == BoardBridge.Part.PIER).toList();
                if (facets.isEmpty()) { continue; }
                var bounds = new BoundingBox().inf();
                facets.forEach(f -> bounds.ext(f.a()).ext(f.b()).ext(f.c()));
                int joints = 0;
                for (int d = 0; d < 6; d++) {
                    var edge = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(at.translated(d), 0), .5f);
                    if (facets.stream().anyMatch(f -> Vector3.dst(f.a().x, f.a().y, 0, edge.x, edge.y, 0) < 12 * BoardGeometry.hexScale())) {
                        joints++;
                    }
                }
                result.put(at, String.format(java.util.Locale.ROOT, "%d joints, %d facets, %.3f %.3f %.3f to %.3f %.3f %.3f",
                      joints, facets.size(), bounds.min.x, bounds.min.y, bounds.min.z, bounds.max.x, bounds.max.y, bounds.max.z));
            }
        }
        return result;
    }

    /** Every installed prop of a terrain, read from its chunks. */
    static List<Installed> installed(GpuTerrain terrain) throws Exception {
        List<Installed> result = new ArrayList<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) { result.add(installed(prop)); }
        }
        return result;
    }

    private static Installed installed(Object prop) throws Exception {
        return new Installed((Coords) GpuTestSupport.field(prop, "coords"), new BoundingBox((BoundingBox) GpuTestSupport.field(prop, "bounds")),
              GpuTestSupport.field(prop, "treeAsset") != null, (String) GpuTestSupport.field(prop, "decorationId"), (Boolean) GpuTestSupport.field(prop, "structure"));
    }

    private static BoardDecoration object(BoardEditorSession session, String id) throws Exception {
        var found = new AtomicReference<BoardDecoration>();
        SwingUtilities.invokeAndWait(() -> found.set(session.board().getHex(TABLES).getDecorations().stream()
              .filter(o -> o.id().equals(id)).findFirst().orElse(null)));
        return found.get();
    }

    private interface Steps<S extends BoardSource> { void run(GpuBattleView view, S source) throws Exception; }

    /** A battle view that can count one frame's GL draws and calls per render stage. */
    private static final class ProfiledView extends GpuBattleView {
        private final Map<String, int[]> stages = new LinkedHashMap<>();
        private com.badlogic.gdx.graphics.profiling.GLProfiler profiler;
        private String stage;

        ProfiledView(BoardSource source) { super(source); }

        /** Renders one frame and returns {draws, GL calls} by stage name. */
        Map<String, int[]> profile() {
            GL20 raw = Gdx.gl20;
            profiler = new com.badlogic.gdx.graphics.profiling.GLProfiler(Gdx.graphics);
            profiler.enable();
            stages.clear();
            try { render(); }
            finally { GpuStageTimings.stopCounting(profiler, raw); profiler = null; }
            return new LinkedHashMap<>(stages);
        }

        @Override
        void renderStage(String next) {
            if (profiler != null) {
                if (stage != null) {
                    int[] counts = stages.computeIfAbsent(stage, key -> new int[2]);
                    counts[0] += profiler.getDrawCalls();
                    counts[1] += profiler.getCalls();
                }
                profiler.reset();
            }
            stage = next;
        }
    }

    /** Opens a profiled battle view on the source {@code setup} makes (on the EDT), runs the steps, then closes it. */
    private static <S extends BoardSource> void run(FutureTask<S> setup, Steps<S> steps) throws Exception {
        SwingUtilities.invokeAndWait(setup);
        S source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new ProfiledView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.fit(source.takeFrame().scene());
                        settle(view, source);
                        GpuTerrain terrain = terrain(view);
                        while (terrain.busy()) { view.render(); }
                        settle(view, source);
                        steps.run(view, source);
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(OUTPUT, "legacy-import-failure.png"));
                        failure.set(error);
                    } finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Converted board in a battle view", failure.get()); }
    }

    /** Frames the picnic hex, clear of the editor's panels. */
    private static void closeUp(GpuBattleView view) {
        view.boardCamera.center(BoardGeometry.center(TABLES, 1));
        view.boardCamera.zoom(.16f / view.boardCamera.camera.zoom);
        for (int frame = 0; frame < 5; frame++) { view.render(); }
    }

    private static GpuTerrain terrain(GpuBattleView view) throws Exception {
        return (GpuTerrain) GpuTestSupport.field(view, "terrain");
    }

    /** The top of an object's installed geometry, where a real click picks it. */
    private static Vector2 top(GpuBattleView view, GpuTerrain terrain, String id) {
        var object = terrain.editorObjects(TABLES).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
        var centre = object.bounds().getCenter(new Vector3());
        Vector3 point = view.boardCamera.camera.project(new Vector3(centre.x, centre.y, object.bounds().max.z - .05f));
        return new Vector2(point.x, Gdx.graphics.getHeight() - point.y);
    }

    /** The placed objects' parts the chunks draw through the object path, instanced or loose. */
    /** The placed object parts the terrain draws now: those whose object passes its draw rule ({@link #drawn}). */
    private static int objectParts(GpuTerrain terrain) throws Exception {
        int parts = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (String owners : List.of("decorationOwners", "looseOwners")) {
                for (Object owner : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, owners)) {
                    if (drawn(terrain, owner)) { parts++; }
                }
            }
        }
        return parts;
    }

    /**
     * Whether every colour pass draws this placed object now: GpuTerrain#drawn, the one rule the passes apply to the
     * object parts they gather (the zoom's size rule and the Tactical View's).
     */
    static boolean drawn(GpuTerrain terrain, Object prop) throws ReflectiveOperationException {
        var rule = GpuTerrain.class.getDeclaredMethod("drawn", prop.getClass(), boolean.class);
        rule.setAccessible(true);
        return (boolean) rule.invoke(terrain, prop, false);
    }

    private static int opaqueDraws(GpuBattleView view) {
        return ((ProfiledView) view).profile().getOrDefault("opaque terrain", new int[2])[0];
    }

    /** The instanced batches of the editor objects' model parts, by part. */
    private static Map<Object, Object> objectBatches(GpuTerrain terrain) throws Exception {
        Set<Object> meshes = new java.util.HashSet<>();
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object part : (com.badlogic.gdx.utils.Array<?>) GpuTestSupport.field(chunk, "decorationRenderables")) {
                meshes.add(((com.badlogic.gdx.graphics.g3d.Renderable) part).meshPart.mesh);
            }
        }
        Map<Object, Object> result = new java.util.HashMap<>();
        for (var entry : ((Map<?, ?>) GpuTestSupport.field(GpuTestSupport.field(terrain, "trees"), "sharedParts")).entrySet()) {
            if (meshes.contains(GpuTestSupport.field(entry.getKey(), "mesh"))) { result.put(entry.getKey(), entry.getValue()); }
        }
        return result;
    }

    /** Where an editable object's drawn (preview-aware) transform places it. */
    private static Vector3 standPlace(GpuTerrain terrain, String id) throws Exception {
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                if (!id.equals(GpuTestSupport.field(prop, "decorationId"))) { continue; }
                var instance = prop.getClass().getDeclaredMethod("instance");
                instance.setAccessible(true);
                return ((ModelInstance) instance.invoke(prop)).transform.getTranslation(new Vector3());
            }
        }
        throw new AssertionError("No installed object " + id);
    }

    /** Whether any chunk's tree stand holds a tree at this place. */
    private static boolean standHas(GpuTerrain terrain, Vector3 place) throws Exception {
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object value : ((Map<?, ?>) GpuTestSupport.field(GpuTestSupport.field(chunk, "stand"), "trees")).values()) {
                var trees = (com.badlogic.gdx.utils.FloatArray) value;
                for (int i = 0; i < trees.size; i += GpuTreeInstances.STRIDE) {
                    if (Math.abs(trees.get(i) - place.x) < .01f && Math.abs(trees.get(i + 1) - place.y) < .01f
                          && Math.abs(trees.get(i + 2) - place.z) < .01f) { return true; }
                }
            }
        }
        return false;
    }

    private static void clickAt(Vector2 point) {
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.withModifiers(0, () -> input.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
        input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    private static void settle(GpuBattleView view, BoardSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }
}
