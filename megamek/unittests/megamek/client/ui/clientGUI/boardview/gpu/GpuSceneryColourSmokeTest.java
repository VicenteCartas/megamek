/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The scenery families that share one mesh and differ by colour (parked cars, herd animals, pools and ponds), the snow
 * forms of placed trees and two recoloured cars on a building's roof, each hex in close-up, on one converted board. Since
 * b13 every view draws placed objects as the editor does (instanced, colours applied on the GPU per placement), so these
 * checks run in the game view; RigidGlbTest compares the GPU recolour rule with the CPU copies' per vertex.
 */
@Tag("on-demand")
class GpuSceneryColourSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "scenery-colours");
    /** The trees whose winter form is a material variant of the bare tree. */
    static final List<String> SNOW_SPECIES = List.of("vegetation/orchard-leaning", "vegetation/orchard-round", "vegetation/orchard-spreading",
          "vegetation/orchard-upright", "vegetation/orchard-vase", "vegetation/orchard-young", "vegetation/pine", "vegetation/pine-broad", "vegetation/pine-slender", "vegetation/pine-layered",
          "vegetation/tree-broad", "vegetation/birch", "vegetation/willow-broad", "vegetation/tree-layered", "vegetation/birch-spreading", "vegetation/birch-young", "vegetation/tree-slender", "vegetation/tree",
          "vegetation/birch-tall");

    /**
     * Paved hexes where the imported board gets a car in a colour far from the model's red: mirrored (magenta), and
     * upright (white). Both have red >= 0x80 and blue 0xFF, so their 0xRRGGBB float is an odd integer above 2^23, which
     * a rounding decode on the GPU would turn red and black.
     */
    private static final Coords MIRRORED = new Coords(12, 1), FAR = new Coords(12, 3);
    /**
     * A two-storey building whose roof holds two recoloured cars, one mirrored (drawn in a batch of its own); with a
     * unit inside, they fade with the building.
     */
    private static final Coords ROOF = new Coords(2, 10);
    /**
     * Placed plants whose models have simpler mesh levels (b10-f1): a mirrored coral and a tilted tree, which the plant
     * stand cannot hold (the rolling stock's levels: {@link #LEVEL_TRAIN}). The overview and a mid view draw a simpler
     * level; a close-up draws the near one.
     */
    private static final Coords LEVEL_CORAL = new Coords(11, 8), LEVEL_TREE = new Coords(12, 9);
    /**
     * A blue locomotive (Body slot), whose file has two levels like every rail and maglev vehicle (a detailed LOD0, the
     * earlier mesh as LOD1): a view wider than the overview draws the slotted mesh of its simpler level in its colours.
     */
    private static final Coords LEVEL_TRAIN = new Coords(11, 9);
    /** Pixels of a capture whose colour may differ by more than 2/255 between two draws of the same objects. */
    private static final int EDGE_PIXELS = 40;
    /** Close-up pixels of a far-coloured car in its colour class ({@link #colourClasses}): the car is about 3,000. */
    private static final int CAR_PIXELS = 1000;

    /** The legacy board: every car park, the suburbs and pools with cars or ponds, all herds, and trees on snow. */
    static Map<Coords, String> fixture() {
        Map<Coords, String> hexes = new LinkedHashMap<>();
        String[] cars = { "00", "01", "02", "03", "04", "05", "06", "07", "98", "99" };
        for (int i = 0; i < cars.length; i++) { hexes.put(new Coords(i + 1, 1), "pavement:1;fluff:5:" + cars[i]); }
        String[] pools = { "fluff:7:03", "fluff:7:04", "fluff:7:05", "fluff:6:18", "fluff:92:2", "fluff:92:4",
              "fluff:94:1", "fluff:94:2", "fluff:94:3", "fluff:94:4", "fluff:94:5" };
        for (int i = 0; i < pools.length; i++) { hexes.put(new Coords(i + 1, 3), pools[i]); }
        // The tileset picks a herd's image by the hex; two rows of each herd code show every herd.
        for (int i = 0; i < 24; i++) { hexes.put(new Coords(i % 12, 5 + i / 12), "fluff:13:0" + i % 5); }
        for (int i = 0; i < SNOW_SPECIES.size(); i++) { hexes.put(new Coords(i % 10 + 1, 8 + i / 10), "snow:1"); }
        hexes.put(MIRRORED, "pavement:1");
        hexes.put(FAR, "pavement:1");
        hexes.put(ROOF, "pavement:1;building:2;bldg_elev:2;bldg_cf:50");
        return hexes;
    }

    /** The legacy fixture converted, with snow trees placed, the two far-coloured cars and the roof cars. */
    static Board imported() throws Exception {
        var hexes = fixture();
        var imported = Board.createEmptyBoard(13, 11);
        for (var entry : hexes.entrySet()) { imported.setHex(entry.getKey(), new Hex(0, entry.getValue(), "")); }
        SwingUtilities.invokeAndWait(() -> {
            BoardSceneryLayouts.convert(imported);
            for (int i = 0; i < SNOW_SPECIES.size(); i++) {
                var coords = new Coords(i % 10 + 1, 8 + i / 10);
                var hex = imported.getHex(coords).duplicate();
                hex.setDecorations(List.of(new BoardDecoration("snow-" + i, "prop", SNOW_SPECIES.get(i), null, 0, 0,
                      30, false, 1, BoardDecoration.Placement.ground(), 0, false)));
                imported.setHex(coords, hex);
            }
            for (var coords : List.of(MIRRORED, FAR)) {
                var hex = imported.getHex(coords).duplicate();
                hex.setDecorations(List.of(new BoardDecoration("far-" + coords.getBoardNum(), "prop", "scenery/vehicles/car", null,
                      0, 0, 30, coords.equals(MIRRORED), 1, BoardDecoration.Placement.ground(), 0, false)
                      .withColours(BoardDecoration.Colours.of(coords.equals(MIRRORED) ? "#ff00ff" : "#ffffff"))));
                imported.setHex(coords, hex);
            }
            var roof = imported.getHex(ROOF).duplicate();
            roof.setDecorations(List.of(new BoardDecoration("roof-car", "prop", "scenery/vehicles/car", null, -.12, 0, 30, false,
                  1, BoardDecoration.Placement.on("building", 0), 0, false).withColours(BoardDecoration.Colours.of("#b8b014")),
                  new BoardDecoration("roof-car-mirrored", "prop", "scenery/vehicles/car", null, .12, 0, 30, true, 1,
                        BoardDecoration.Placement.on("building", 0), 0, false).withColours(BoardDecoration.Colours.of("#245c99"))));
            imported.setHex(ROOF, roof);
            var coral = imported.getHex(LEVEL_CORAL).duplicate();
            coral.setDecorations(List.of(new BoardDecoration("level-coral", "prop", "mars/brain-lobes", null, 0, 0, 20, true, 1,
                  BoardDecoration.Placement.ground(), 0, false)));
            imported.setHex(LEVEL_CORAL, coral);
            var tree = imported.getHex(LEVEL_TREE).duplicate();
            tree.setDecorations(List.of(new BoardDecoration("level-tree", "prop", "vegetation/tree-broad", null, 0, 0, 0, false, 1,
                  BoardDecoration.Placement.ground(), 0, false, 12, 0)));
            imported.setHex(LEVEL_TREE, tree);
            var train = imported.getHex(LEVEL_TRAIN).duplicate();
            train.setDecorations(List.of(new BoardDecoration("level-train", "prop", "scenery/rail/locomotive", null, 0, 0, 30,
                  false, 1, BoardDecoration.Placement.ground(), 0, false).withColours(BoardDecoration.Colours.of("#24407a"))));
            imported.setHex(LEVEL_TRAIN, train);
        });
        return imported;
    }

    /**
     * The fixture converted, saved as .board2 and loaded the game's way (BoardFile.loadSheet, the game's GpuBoardSource),
     * its tactical markings and hex labels (UI overlays) left out, draws its placed objects as the editor does (b13): every
     * part instanced (the mirrored cars in a batch of their own) or, for a pose the instance data cannot hold, one by one,
     * none packed with the scenery, each part carrying its object's colours; the far colours draw as themselves (magenta
     * and white, not the model's red, nor the red and black of a rounding decode). The overview and a mid view draw
     * simpler mesh levels, a close-up the near ones, and a level change and back draws as before; when the unit in the
     * roof's building leaves (its roof cars fade with it), the roof draws as before it came.
     */
    @Test
    void theGameViewDrawsTheConvertedBoardsObjectsInstancedInTheirColours() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        var hexes = fixture();
        var imported = imported();
        int recoloured = 0;
        for (var entry : hexes.entrySet()) {
            var objects = imported.getHex(entry.getKey()).getDecorations();
            System.out.printf("COLOUR fixture %s %s: %s%n", entry.getKey().getBoardNum(), entry.getValue(),
                  objects.stream().filter(o -> !o.asset().equals("vegetation/tree-broad")).map(o -> o.asset() + o.colours().slots()).toList());
            for (var object : objects) {
                // Every coat or paint is its family's one mesh in that coat's colours.
                assertFalse(object.asset().matches("scenery/(vehicles/car-.*|farm/(hen|rooster|bison)-.*|parks/pond-.*)"), object.asset());
                if (!object.colours().slots().isEmpty()) { recoloured++; }
            }
        }
        assertTrue(recoloured > 50, "Converted cars, animals and ponds keep their colours: " + recoloured);
        File file = new File(OUTPUT, "fixture.board2");
        BoardFile.save(imported, file.toPath());
        List<BoardScene.Tile> gameTiles;
        try (var fixture = GpuBoardFixture.create(BoardFile.loadSheet(file, false, true))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            gameTiles = fixture.source.takeFrame().scene().tiles().stream().map(tile -> tile.withText(List.of())).toList();
        }
        var scene = new BoardScene(0, imported.getWidth(), imported.getHeight(), gameTiles, List.of(), List.of(), -1, "",
              List.of());
        // The drawn level of the two objects with levels: overview, mid view, then close-up.
        List<Integer> levels = new java.util.ArrayList<>();
        int[] train = new int[1];
        int[] parts = new int[3];
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    // Grass blades are planted asynchronously; a capture may catch a blade another run has not yet.
                    terrain.setGrass(false);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    System.arraycopy(objectParts(terrain), 0, parts, 0, parts.length);
                    // The level objects close up before any level change, to compare after a change and back.
                    for (var coords : List.of(LEVEL_CORAL, LEVEL_TREE)) {
                        camera.center(BoardGeometry.center(coords, 0));
                        camera.camera.zoom = .1f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, "game-level-first-" + coords.getBoardNum() + ".png"));
                    }
                    camera.center(BoardGeometry.center(new Coords(6, 5), 0));
                    camera.camera.zoom = .9f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    profiler.enable();
                    profiler.reset();
                    frame.render(terrain, camera, scene);
                    System.out.printf("COLOUR game overview: %d draw calls, %d GL calls%n", profiler.getDrawCalls(),
                          profiler.getCalls());
                    profiler.disable();
                    GpuReviewFrame.save(new File(OUTPUT, "game-overview.png"));
                    levels.addAll(List.of(level(terrain, LEVEL_CORAL), level(terrain, LEVEL_TREE)));
                    // The overview leaves the placed locomotive's 69-unit side at 76 pixels, inside its near level's hysteresis.
                    camera.camera.zoom = 1.1f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    train[0] = level(terrain, LEVEL_TRAIN);
                    camera.center(BoardGeometry.center(LEVEL_CORAL, 0).lerp(BoardGeometry.center(LEVEL_TREE, 0), .5f));
                    camera.camera.zoom = .6f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(OUTPUT, "game-levels.png"));
                    levels.addAll(List.of(level(terrain, LEVEL_CORAL), level(terrain, LEVEL_TREE)));
                    for (var coords : List.of(LEVEL_CORAL, LEVEL_TREE)) {
                        camera.center(BoardGeometry.center(coords, 0));
                        camera.camera.zoom = .1f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, "game-level-" + coords.getBoardNum() + ".png"));
                        levels.add(level(terrain, coords));
                    }
                    for (var coords : hexes.keySet()) {
                        camera.center(BoardGeometry.center(coords, 0));
                        camera.camera.zoom = .1f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, "game-" + coords.getBoardNum() + ".png"));
                    }
                    // A unit in the building at the default building opacity: the roof cars fade with it; then it leaves,
                    // and the roof is drawn as before.
                    camera.center(BoardGeometry.center(ROOF, 2));
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(OUTPUT, "game-roof-" + ROOF.getBoardNum() + ".png"));
                    var occupant = GpuTestSupport.occupant(ROOF);
                    terrain.animate(0, List.of(occupant), GpuTerrain.DEFAULT_BUILDING_OPACITY);
                    occupant.model.dispose();
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(OUTPUT, "game-occupied-" + ROOF.getBoardNum() + ".png"));
                    terrain.animate(0, List.of(), GpuTerrain.DEFAULT_BUILDING_OPACITY);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(OUTPUT, "game-left-" + ROOF.getBoardNum() + ".png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scenery colours in the game view", failure.get()); }
        System.out.printf("COLOUR object parts: %d instanced (%d of them mirror images), %d one by one%n", parts[0], parts[2],
              parts[1]);
        assertTrue(parts[0] > 100, "Placed objects draw instanced in the game view");
        assertTrue(parts[2] >= 2, "The mirrored cars draw instanced: " + parts[2]);
        System.out.printf("COLOUR levels (coral, tree: overview, mid, close): %s%n", levels);
        System.out.printf("COLOUR train level in a wide view (-1: one level): %d%n", train[0]);
        assertEquals(1, train[0], "A wide view draws the locomotive's simpler level");
        assertTrue(levels.subList(0, 4).stream().allMatch(level -> level > 0), "Overview and mid view draw simpler levels");
        assertEquals(List.of(0, 0), levels.subList(4, 6), "A close-up draws the near meshes");
        for (var coords : List.of(LEVEL_CORAL, LEVEL_TREE)) {
            int[] back = parity(new File(OUTPUT, "game-level-first-" + coords.getBoardNum() + ".png"),
                  new File(OUTPUT, "game-level-" + coords.getBoardNum() + ".png"));
            assertEquals(0, back[0], "The game view draws " + coords.getBoardNum() + " as before after a level change and back");
        }
        int[] restored = parity(new File(OUTPUT, "game-roof-" + ROOF.getBoardNum() + ".png"),
              new File(OUTPUT, "game-left-" + ROOF.getBoardNum() + ".png"));
        assertEquals(0, restored[0], "The game view draws the roof as before once the unit has left");
        int[] magenta = colourClasses(new File(OUTPUT, "game-" + MIRRORED.getBoardNum() + ".png"));
        int[] white = colourClasses(new File(OUTPUT, "game-" + FAR.getBoardNum() + ".png"));
        System.out.printf("COLOUR far colours (magenta, red, white px): mirrored car %s, upright car %s%n",
              java.util.Arrays.toString(magenta), java.util.Arrays.toString(white));
        assertTrue(magenta[0] > CAR_PIXELS && magenta[1] < CAR_PIXELS / 20, "The mirrored car draws magenta");
        assertTrue(white[2] > CAR_PIXELS && white[1] < CAR_PIXELS / 20, "The upright car draws white");
    }

    /**
     * The terrain's placed object parts, read from its chunks: instanced, one by one, and instanced mirror images. Each
     * carries its object's colours (RigidGlb#packed), whichever way it draws.
     */
    private static int[] objectParts(GpuTerrain terrain) throws ReflectiveOperationException {
        int[] result = new int[3];
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (String[] kind : new String[][] { { "decorationRenderables", "decorationOwners" }, { "looseDecorations", "looseOwners" } }) {
                var parts = (Array<?>) GpuTestSupport.field(chunk, kind[0]);
                var owners = (Array<?>) GpuTestSupport.field(chunk, kind[1]);
                for (int i = 0; i < parts.size; i++) {
                    var part = (Renderable) parts.get(i);
                    var feature = (BoardScene.Feature) GpuTestSupport.field(owners.get(i), "decorationFeature");
                    assertTrue(java.util.Arrays.equals(RigidGlb.packed(feature.colours()), (float[]) part.userData),
                          feature.asset() + " carries its colours " + feature.colours().slots());
                    if (kind[0].equals("looseDecorations")) { result[1]++; } else {
                        result[0]++;
                        if (GpuTreeInstances.mirrored(part.worldTransform)) { result[2]++; }
                    }
                }
            }
        }
        return result;
    }

    /**
     * A combat scar on a recoloured car (magenta; the model's own paint is red) is drawn in the car's colours: the scar
     * patch copies the shared mesh's vertices and carries the placement's colours, which no merged scar page could.
     */
    @Test
    void aCombatScarOnARecolouredCarKeepsItsColours() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        Coords at = new Coords(2, 2);
        var board = Board.createEmptyBoard(5, 5);
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
        }
        board.getHex(at).setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 30, false,
              1, BoardDecoration.Placement.ground(), 0, false).withColours(BoardDecoration.Colours.of("#ff00ff"))));
        var scene = BoardScenes.scene(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.setGrass(false);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    camera.center(BoardGeometry.center(at, 0));
                    camera.camera.zoom = .1f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(new File(OUTPUT, "scar-clean.png"));
                    Vector3 aim = terrain.editorObjects(at).getFirst().bounds().getCenter(new Vector3());
                    Vector3 origin = aim.cpy().add(BoardRelief.metres(10), 0, BoardRelief.metres(40));
                    var mark = GroundDamagePlaybackTest.impact("ISAC20", null);
                    mark.attack().landscape = ray -> terrain.hit(scene, ray);
                    mark.attack().landscapeSegment = (ray, length) -> terrain.hit(scene, ray, length);
                    var contact = mark.attack().landscapeContact(t -> origin.cpy().lerp(aim, (float) t), 1);
                    assertTrue(contact != null, "The shot strikes the car");
                    terrain.impact(scene, new GpuGroundDamage.Impact(mark.attack(), "car", origin, contact.point(), mark.effect(),
                          mark.shot()));
                    for (int i = 0; i < 6; i++) { frame.render(terrain, camera, scene); }
                    assertTrue(terrain.surfaceScars().tileCount() > 0, "The shot scars the car");
                    GpuReviewFrame.save(new File(OUTPUT, "scar-hit.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("A scar on a recoloured car", failure.get()); }
        var clean = javax.imageio.ImageIO.read(new File(OUTPUT, "scar-clean.png"));
        var hit = javax.imageio.ImageIO.read(new File(OUTPUT, "scar-hit.png"));
        // Changed pixels; of those, red-hued (the model's own paint, scorched or not) and magenta-hued (the car's).
        int changed = 0, red = 0, magenta = 0;
        for (int y = 0; y < hit.getHeight(); y++) {
            for (int x = 0; x < hit.getWidth(); x++) {
                int p = clean.getRGB(x, y), q = hit.getRGB(x, y), largest = 0;
                for (int shift = 0; shift < 24; shift += 8) { largest = Math.max(largest, Math.abs((p >> shift & 255) - (q >> shift & 255))); }
                if (largest <= 8) { continue; }
                changed++;
                int r = q >> 16 & 255, g = q >> 8 & 255, b = q & 255;
                if (r > 40 && r > 2.5f * Math.max(g, b)) { red++; }
                if (Math.min(r, b) > 40 && Math.min(r, b) > 2 * g) { magenta++; }
            }
        }
        System.out.printf("COLOUR scar on the magenta car: %d px changed, %d red, %d magenta%n", changed, red, magenta);
        assertTrue(magenta > 100, "The scar marks the magenta car: " + magenta + " of " + changed + " changed px");
        assertTrue(red < magenta / 20, "The scarred car keeps its colour, not the model's red: " + red + " red px");
    }

    /** Pixels of a capture in each colour class: magenta, red (the car model's own paint) and near white. */
    private static int[] colourClasses(File file) throws java.io.IOException {
        var image = javax.imageio.ImageIO.read(file);
        int[] result = new int[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y), r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
                if (r > 150 && b > 150 && g < 100) { result[0]++; }
                if (r > 150 && g < 90 && b < 90) { result[1]++; }
                if (Math.min(r, Math.min(g, b)) > 200 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 25) {
                    result[2]++;
                }
            }
        }
        return result;
    }

    /**
     * The Tactical View hides the scatter alone (GpuTerrain#setScatterHidden): placed objects, plants included, draw and
     * pick as in the 3D view. Game picking (hover and selection) finds the same hexes in both views and looks through
     * objects as before.
     */
    @Test
    void theTacticalViewKeepsEveryPlacedObjectAndPicksTheSameHexes() throws Exception {
        var scene = BoardScenes.scene(imported());
        Coords snowTree = new Coords(1, 8);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.center(BoardGeometry.center(new Coords(6, 5), 0));
                    camera.camera.zoom = .9f;
                    camera.update();
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    for (boolean tactical : new boolean[] { false, true, false }) {
                        terrain.setScatterHidden(tactical);
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        String view = tactical ? "Tactical View" : "3D view";
                        GpuReviewFrame.save(new File(OUTPUT, "plants-" + (tactical ? "tactical" : "3d") + ".png"));
                        int plants = 0, objects = 0;
                        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                            for (String owners : List.of("decorationOwners", "looseOwners")) {
                                for (Object owner : (Array<?>) GpuTestSupport.field(chunk, owners)) {
                                    if (!GpuLegacyImportSmokeTest.drawn(terrain, owner)) { continue; }
                                    if (plant(owner)) { plants++; } else { objects++; }
                                }
                            }
                        }
                        System.out.printf("PLANTS %s: %d plant parts, %d other object parts%n", view, plants, objects);
                        assertTrue(plants > 0, view + ": placed plants' parts");
                        assertTrue(objects > 100, view + ": the other objects' parts");
                        for (var at : List.of(LEVEL_CORAL, LEVEL_TREE, snowTree)) {
                            assertTrue(hit(terrain, scene, at, true) != null, view + ": the plant of " + at.getBoardNum());
                            assertTrue(terrain.editorObjects(at).getFirst().shown(), view + " shows " + at.getBoardNum());
                        }
                        for (var at : List.of(MIRRORED, FAR)) {
                            // The editor's object pick and game picking find the car's hex in both views, and the hover
                            // outline looks through the car to the ground.
                            var car = hit(terrain, scene, at, true);
                            assertEquals(at, car == null ? null : car.coords(), view + ": the car of "
                                  + at.getBoardNum());
                            assertEquals(at, hit(terrain, scene, at, false).coords(), view + ": the hex of the car");
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Placed plants in the Tactical View", failure.get()); }
    }

    /** A placed plant: in the tree stand, or a foliage model the stand cannot hold. */
    private static boolean plant(Object prop) throws ReflectiveOperationException {
        return GpuTestSupport.field(prop, "treeAsset") != null || GpuTerrain.foliageModel(((ModelInstance) GpuTestSupport.field(prop, "instance")).model);
    }

    /**
     * A ray straight down through the middle of the first placed object of {@code at}: the editor's object pick
     * ({@code objects}), or the game's pick and the hover outline, which must agree on the hex.
     */
    private static HitAt hit(GpuTerrain terrain, BoardScene scene, Coords at, boolean objects) {
        var object = terrain.editorObjects(at).getFirst();
        Vector3 centre = object.bounds().getCenter(new Vector3());
        var ray = new Ray(new Vector3(centre.x, centre.y, object.bounds().max.z + 100), new Vector3(0, 0, -1));
        if (objects) {
            var hit = terrain.decorationHit(scene, ray);
            return hit == null ? null : new HitAt(hit.coords());
        }
        var picked = terrain.selectionHit(scene, ray);
        var walkable = terrain.walkableHit(scene, ray);
        assertEquals(picked.coords(), walkable.coords(), "Picking and the hover outline agree on " + at.getBoardNum());
        assertEquals("ground", walkable.receiver(), "The hover outline looks through the object of " + at.getBoardNum());
        return new HitAt(picked.coords());
    }

    private record HitAt(Coords coords) { }

    /**
     * Every colouring of a model is one mesh in every view (option A): the fixture draws as many times as with every
     * object in its model's own colours. A partial rebuild of the chunk keeps an untouched car's paint, and a drag's
     * preview pose keeps the dragged car's.
     */
    @Test
    void everyColouringOfAModelDrawsFromOneMeshAndKeepsItsColours() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        Coords cars = new Coords(1, 1), edit = new Coords(7, 7);
        var board = imported();
        var uniform = imported();
        for (var hex : java.util.stream.IntStream.range(0, uniform.getWidth() * uniform.getHeight())
              .mapToObj(i -> uniform.getHex(i % uniform.getWidth(), i / uniform.getWidth())).toList()) {
            hex.setDecorations(hex.getDecorations().stream().map(o -> o.withColours(BoardDecoration.Colours.NONE)).toList());
        }
        BoardScene coloured = BoardScenes.scene(board), plain = BoardScenes.scene(uniform);
        // Another hex of the cars' chunk changes (not its level, which moves the chunk's wall charts and rebuilds every
        // hex); then one recoloured car moves, keeping its colours.
        board.setHex(edit, new Hex(0, "rough:1", ""));
        BoardScene edited = BoardScenes.scene(board);
        var lot = board.getHex(cars).duplicate();
        var car = lot.getDecorations().stream().filter(o -> o.asset().equals("scenery/vehicles/car") && !o.colours().slots().isEmpty())
              .findFirst().orElseThrow();
        lot.setDecorations(lot.getDecorations().stream().map(o -> o != car ? o
              : o.transform(o.x() + .08, o.y(), o.rotation(), o.mirror(), o.scale(), o.placement())).toList());
        board.setHex(cars, lot);
        BoardScene moved = BoardScenes.scene(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    int[] draws = new int[2];
                    // A terrain's first update draws one call fewer than any later one, in any colours: both counts follow one.
                    for (int index = -1; index < 2; index++) {
                        var scene = index == 0 ? plain : coloured;
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        camera.center(BoardGeometry.center(new Coords(6, 5), 0));
                        camera.camera.zoom = .9f;
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        profiler.enable();
                        profiler.reset();
                        terrain.render(camera.camera);
                        if (index >= 0) { draws[index] = profiler.getDrawCalls(); }
                        profiler.disable();
                    }
                    System.out.printf("COLOUR opaque draws: own colours %d, every colour %d%n", draws[0], draws[1]);
                    assertEquals(draws[0], draws[1], "Every colouring of a model shares its draws");

                    camera.center(BoardGeometry.center(cars, 0));
                    camera.camera.zoom = .1f;
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, coloured, camera);
                    frame.render(terrain, camera, coloured);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-rebuild-before.png"));
                    Object bounds = carBounds(terrain, cars);
                    terrain.update(edited);
                    GpuReviewFrame.settle(terrain, null, edited, camera);
                    assertTrue(bounds == carBounds(terrain, cars), "The edit rebuilds the chunk and reuses the cars' hex");
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-rebuild-after.png"));

                    terrain.previewEditorObjects(moved);
                    frame.render(terrain, camera, moved);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-drag-preview.png"));
                    terrain.update(moved);
                    GpuReviewFrame.settle(terrain, null, moved, camera);
                    frame.render(terrain, camera, moved);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-drag-released.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Editor colour slots", failure.get()); }
        int[] rebuilt = differing(new File(OUTPUT, "editor-rebuild-before.png"), new File(OUTPUT, "editor-rebuild-after.png"));
        int[] dragged = differing(new File(OUTPUT, "editor-drag-preview.png"), new File(OUTPUT, "editor-drag-released.png"));
        int[] moving = differing(new File(OUTPUT, "editor-rebuild-after.png"), new File(OUTPUT, "editor-drag-preview.png"));
        System.out.printf("COLOUR rebuild %s, drag preview against release %s, preview against before %s%n",
              java.util.Arrays.toString(rebuilt), java.util.Arrays.toString(dragged), java.util.Arrays.toString(moving));
        assertTrue(rebuilt[0] <= EDGE_PIXELS, "An untouched car keeps its paint through a rebuild of its chunk: " + rebuilt[0]);
        assertTrue(moving[1] > 200, "The preview moves the car: " + moving[1]);
        assertTrue(dragged[0] <= EDGE_PIXELS, "The dragged car keeps its paint: " + dragged[0]);
    }

    /**
     * A mirrored orchard tree, whose fruit only its two nearer levels hold, zoomed from its near level (LOD0) out to
     * its simplest (LOD2) and in through LOD1 again: its close-up after the round trip equals the one before (the fruit
     * keeps the mirrored placement's culling). On the roof of a building with a unit inside the same
     * round trip keeps the fade on every material, the fruit's too, and the faded close-up returns as it was. The editor's side view
     * draws the near level at any board zoom: its silhouette zoomed out equals the close-up's.
     */
    @Test
    void aLevelChangeKeepsThePlacementsStateAndTheSideViewDrawsTheNearLevel() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        Coords ground = new Coords(1, 1), roof = new Coords(3, 2);
        var board = Board.createEmptyBoard(5, 4);
        board.setHex(roof, new Hex(0, "pavement:1;building:2;bldg_elev:2;bldg_cf:50", ""));
        SwingUtilities.invokeAndWait(() -> {
            BoardSceneryLayouts.convert(board);
            for (var coords : List.of(ground, roof)) {
                var hex = board.getHex(coords).duplicate();
                hex.setDecorations(List.of(new BoardDecoration("orchard-" + coords.getBoardNum(), "prop", "vegetation/orchard-round", null,
                      0, 0, 30, true, 1, coords.equals(roof) ? BoardDecoration.Placement.on("building", 0)
                      : BoardDecoration.Placement.ground(), 0, false)));
                board.setHex(coords, hex);
            }
        });
        var scene = BoardScenes.scene(board);
        List<Integer> levels = new java.util.ArrayList<>();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.setGrass(false);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    camera.center(BoardGeometry.center(ground, 0));
                    camera.update();
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    // The zoom at which the tree's larger side spans a number of pixels (pixels go as 1 / zoom).
                    var bounds = (com.badlogic.gdx.math.collision.BoundingBox) GpuTestSupport.field(levelled(terrain, ground), "bounds");
                    float unit = Math.max(bounds.getWidth(), bounds.getHeight()) * BoardCamera.pixelsPerUnit(camera.camera)
                          * camera.camera.zoom;
                    float near = unit / 300, simplest = unit / 12, middle = unit / 45;
                    var occupant = GpuTestSupport.occupant(roof);
                    for (var coords : List.of(ground, roof)) {
                        // A unit in the roof's building through the whole round trip.
                        List<ModelInstance> units = coords.equals(roof) ? List.of(occupant) : List.of();
                        camera.center(BoardGeometry.center(coords, units.isEmpty() ? 0 : 2));
                        zoom(terrain, frame, camera, scene, near, units);
                        settle(terrain, frame, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, "orchard-first-" + coords.getBoardNum() + ".png"));
                        if (units.isEmpty()) { section(terrain, ground, bounds, "near"); }
                        zoom(terrain, frame, camera, scene, simplest, units);
                        levels.add(level(terrain, coords));
                        if (units.isEmpty()) { section(terrain, ground, bounds, "far"); }
                        zoom(terrain, frame, camera, scene, middle, units);
                        levels.add(level(terrain, coords));
                        if (!units.isEmpty()) {
                            for (var material : ((com.badlogic.gdx.graphics.g3d.ModelInstance) GpuTestSupport.field(levelled(terrain, roof),
                                  "instance")).materials) {
                                assertTrue(material.has(com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute.Type),
                                      "The faded tree's " + material.id + " keeps its fade");
                            }
                        }
                        zoom(terrain, frame, camera, scene, near, units);
                        levels.add(level(terrain, coords));
                        settle(terrain, frame, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, "orchard-back-" + coords.getBoardNum() + ".png"));
                    }
                    occupant.model.dispose();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Orchard level round trip", failure.get()); }
        System.out.printf("COLOUR orchard levels (ground, then roof: simplest, middle, near): %s%n", levels);
        assertEquals(List.of(2, 1, 0, 2, 1, 0), levels, "The view draws LOD2, LOD1, then LOD0");
        for (var coords : List.of(ground, roof)) {
            int[] back = parity(new File(OUTPUT, "orchard-first-" + coords.getBoardNum() + ".png"),
                  new File(OUTPUT, "orchard-back-" + coords.getBoardNum() + ".png"));
            assertEquals(0, back[0], "The view draws the orchard tree of " + coords.getBoardNum() + " as before");
        }
        int sides = silhouettes(new File(OUTPUT, "editor-orchard-section-near.png"), new File(OUTPUT, "editor-orchard-section-far.png"));
        System.out.printf("COLOUR side view silhouettes, near against zoomed out: %d px differ%n", sides);
        assertEquals(0, sides, "The side view draws the tree's near level at any board zoom");
    }

    /**
     * Zooms the camera to {@code zoom}, settles and draws a frame with {@code units} on the board, told before the frame
     * as the board tells them every frame.
     */
    private static void zoom(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, float zoom,
          List<ModelInstance> units) throws Exception {
        camera.camera.zoom = zoom;
        camera.update();
        GpuReviewFrame.settle(terrain, null, scene, camera);
        terrain.animate(0, units, GpuTerrain.DEFAULT_BUILDING_OPACITY);
        frame.render(terrain, camera, scene);
    }

    /**
     * Settled frames, the shadow map redrawn: a level change alone keeps the previous level's shadow until the next refit
     * (updateDetail) and a zoom within the last fit's guard does not refit. After the faded roof tree's level change the
     * frames also took a few frames to settle (2 pixels by 2/255 on its trunk; the cause is not isolated), so the map is
     * redrawn three frames after the zoom.
     */
    private static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene) {
        for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
        terrain.refreshShadows();
        frame.render(terrain, camera, scene);
        frame.render(terrain, camera, scene);
    }

    /** The editor's side view of the hex's object (isolated, as the side view reveals one) on a magenta backdrop. */
    private static void section(GpuTerrain terrain, Coords coords, com.badlogic.gdx.math.collision.BoundingBox bounds,
          String name) throws ReflectiveOperationException {
        var camera = new com.badlogic.gdx.graphics.OrthographicCamera();
        float size = 1.4f * Math.max(bounds.getWidth(), bounds.getDepth());
        camera.viewportWidth = size * Gdx.graphics.getWidth() / Gdx.graphics.getHeight();
        camera.viewportHeight = size;
        var centre = bounds.getCenter(new com.badlogic.gdx.math.Vector3());
        camera.position.set(centre).add(0, -10000, 0);
        camera.up.set(com.badlogic.gdx.math.Vector3.Z);
        camera.lookAt(centre);
        camera.near = 1;
        camera.far = 20000;
        camera.update();
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        com.badlogic.gdx.utils.ScreenUtils.clear(1, 0, 1, 1, true);
        terrain.renderEditorSection(camera, coords, (String) GpuTestSupport.field(levelled(terrain, coords), "decorationId"));
        GpuReviewFrame.save(new File(OUTPUT, "editor-orchard-section-" + name + ".png"));
    }

    /** Pixels covered by the object in one capture and not the other (the magenta backdrop elsewhere). */
    private static int silhouettes(File first, File second) throws java.io.IOException {
        var a = javax.imageio.ImageIO.read(first);
        var b = javax.imageio.ImageIO.read(second);
        int result = 0, covered = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                boolean p = (a.getRGB(x, y) & 0xffffff) != 0xff00ff, q = (b.getRGB(x, y) & 0xffffff) != 0xff00ff;
                if (p != q) { result++; }
                if (p) { covered++; }
            }
        }
        assertTrue(covered > 1000, "The side view draws the tree: " + covered + " px");
        return result;
    }

    /** The hex's placed object with mesh levels (its prop); null for none. */
    private static Object levelled(GpuTerrain terrain, Coords coords) throws ReflectiveOperationException {
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                if (coords.equals(GpuTestSupport.field(prop, "coords")) && GpuTestSupport.field(prop, "decorationId") != null
                      && GpuTestSupport.field(prop, "levels") != null) { return prop; }
            }
        }
        return null;
    }

    /** The drawn level of the hex's placed object with mesh levels, checked against the model it draws; -1 for none. */
    private static int level(GpuTerrain terrain, Coords coords) throws ReflectiveOperationException {
        Object prop = levelled(terrain, coords);
        if (prop == null) { return -1; }
        var levels = (List<?>) GpuTestSupport.field(prop, "levels");
        int level = (Integer) GpuTestSupport.field(prop, "lod");
        var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) GpuTestSupport.field(prop, "instance");
        assertTrue(instance.model == levels.get(level), coords + " draws its level " + level);
        assertTrue(GpuTestSupport.field(prop, "pickingModel") == levels.getFirst(), coords + " picks its near level");
        return level;
    }

    /** The installed bounds of a placed car of the hex, which a reused hex keeps. */
    private static Object carBounds(GpuTerrain terrain, Coords coords) throws ReflectiveOperationException {
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) GpuTestSupport.field(prop, "instance");
                if (coords.equals(GpuTestSupport.field(prop, "coords")) && GpuTestSupport.field(prop, "decorationId") != null
                      && instance.nodes.first().id.equals("car")) { return GpuTestSupport.field(prop, "bounds"); }
            }
        }
        throw new AssertionError("No car on " + coords);
    }

    /** A replaced slot colour tints its vertices' own glow: by night a generator ring's core shows its Glow slot's colour. */
    @Test
    void aReplacedGlowSlotColoursTheGlow() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        File shot = new File(OUTPUT, "glow-slot.png");
        Coords own = new Coords(3, 3), red = new Coords(4, 3);
        var board = Board.createEmptyBoard(8, 8);
        for (var coords : List.of(own, red)) {
            var hex = board.getHex(coords).duplicate();
            hex.setDecorations(List.of(new BoardDecoration("ring-" + coords.getBoardNum(), "prop", "scenery/roofs/generator-ring", null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false)
                  .withColours(coords.equals(red) ? BoardDecoration.Colours.NONE.with(1, "#8a241c") : BoardDecoration.Colours.NONE)));
            board.setHex(coords, hex);
        }
        var capture = new java.util.concurrent.FutureTask<BoardScene>(() -> {
            var editor = new megamek.client.ui.boardeditor.BoardEditorSession();
            editor.open(board, null);
            try (var source = new GpuMapSource(editor.game(), null, editor)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(capture);
        BoardScene scene = capture.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(800, 500);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(22, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    camera.setIsometric(false);
                    camera.camera.zoom = .2f;
                    camera.center(BoardGeometry.center(own, 0));
                    frame.render(terrain, camera, scene);
                    GpuReviewFrame.save(shot);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Glow slot capture", failure.get()); }
        var image = javax.imageio.ImageIO.read(shot);
        int blue = 0, reddish = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, b = rgb & 255;
                if (b > r + 60) { blue++; }
                if (r > b + 60) { reddish++; }
            }
        }
        assertTrue(blue > 20, "The ring in its own colours glows blue: " + blue);
        assertTrue(reddish > 20, "The ring with a red Glow slot glows red: " + reddish);
    }

    /** Pixels that differ at all, pixels whose largest channel difference exceeds 1/255, and the largest one. */
    static int[] parity(File first, File second) throws java.io.IOException {
        var a = javax.imageio.ImageIO.read(first);
        var b = javax.imageio.ImageIO.read(second);
        int[] result = new int[3];
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y), largest = 0;
                for (int shift = 0; shift < 24; shift += 8) { largest = Math.max(largest, Math.abs((p >> shift & 255) - (q >> shift & 255))); }
                if (largest > 0) { result[0]++; }
                if (largest > 1) { result[1]++; }
                result[2] = Math.max(result[2], largest);
            }
        }
        return result;
    }

    /** Pixels whose largest channel difference exceeds 2/255, and 8/255. */
    static int[] differing(File first, File second) throws java.io.IOException {
        var a = javax.imageio.ImageIO.read(first);
        var b = javax.imageio.ImageIO.read(second);
        int[] result = new int[2];
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y), largest = 0;
                for (int shift = 0; shift < 24; shift += 8) { largest = Math.max(largest, Math.abs((p >> shift & 255) - (q >> shift & 255))); }
                if (largest > 2) { result[0]++; }
                if (largest > 8) { result[1]++; }
            }
        }
        return result;
    }
}
