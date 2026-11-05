/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native authored-mask ownership, nuclear coexistence, erase, and a repeatable four-material severity contact sheet. */
@Tag("on-demand")
class GpuAuthoredScorchSmokeTest {
    private static final List<String> THEMES = List.of("grass", "desert", "snow", "concrete");
    private static final List<String> SEVERITIES = List.of("", "singed", "charred", "ash", "glass");

    @Test
    void authoredScorchSurvivesGameLoadAndCombatToggleWithoutRepaintingNuclearDamage() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "authored-scorch");
        Files.createDirectories(output.toPath());
        Board board = Board.createEmptyBoard(10, 8);
        board.setNativeFormat(true);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Hex hex = new Hex(0, y / 2 == 3 ? "pavement:1" : "", THEMES.get(y / 2));
                if (x >= 2) {
                    hex.setAppearance(Map.of("scorch", new HexAppearance("scorch/" + SEVERITIES.get(x / 2), null, null, null)));
                }
                board.setHex(new Coords(x, y), hex);
            }
        }
        var file = output.toPath().resolve("severity-sweep.board2");
        BoardFile.save(board, file);
        Board loaded = BoardFile.read(file);
        BoardScene scene = BoardScenes.scene(loaded);
        Files.writeString(output.toPath().resolve("layout.txt"),
              "Rows, north to south: grass, desert, snow, concrete (two hexes each).\n"
                    + "Columns, west to east: clean, singed, charred, ash, glass (two hexes each).\n"
                    + "The saved board2 is the fixture rendered by this test.\n");
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { review(loaded, scene, output); reviewGrass(output); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Authored scorch", failure.get()); }
    }

    /** Authored and nuclear scorch clear grass and decorative shrubs, with the same rocks still drawn. */
    private static void reviewGrass(File output) throws Exception {
        var bank = new Coords(4, 4);
        var dirt = new Coords(5, 8);
        Board board = Board.createEmptyBoard(10, 10);
        board.setNativeFormat(true);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(new Coords(x, y), new Hex(y < 7 && x < 5 ? 2 : 0, "", y < 7 ? "grass" : "dirt"));
            }
        }
        var samples = List.of(
              new BoardScene.Feature("scatter-dry-grass", 0, 0, 0, 1, .15f, 0, BoardScene.FeatureKind.SCATTER),
              new BoardScene.Feature("scatter-plant", 16, 0, 0, 1, .15f, 0, BoardScene.FeatureKind.SCATTER),
              new BoardScene.Feature("scatter-rock", -16, 0, 0, 1, .15f, 0, BoardScene.FeatureKind.SCATTER));
        int[] cleanVertices = new int[samples.size()];
        var fieldSurfaces = new BoardSurface.Cache();
        long cleanFieldStones = 0;
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        var camera = new BoardCamera();
        camera.resize(1600, 1000);
        camera.setIsometric(true);
        int programs = 0;
        try {
            for (String phase : List.of("clean", "singed", "nuclear")) {
                boolean burned = !phase.equals("clean");
                for (int x = 0; x < board.getWidth(); x++) {
                    for (int y = 0; y < board.getHeight(); y++) {
                        board.getHex(x, y).setAppearance(phase.equals("singed")
                              ? Map.of("scorch", new HexAppearance("scorch/singed", null, null, null)) : Map.of());
                    }
                }
                BoardScene captured = BoardScenes.scene(board);
                BoardScene scene = captured.withTiles(captured.tiles().stream().map(tile ->
                      tile.withFeatures(tile.coords().equals(dirt) ? samples : List.of())).toList());
                if (phase.equals("nuclear")) {
                    terrain.update(scene, camera.camera);
                    GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                    assertEquals(samples, terrain.presentation(scene).tile(dirt).features(),
                          "Erasing authored scorch restores the existing decorative mesh before the nuclear hit");
                    assertTrue(fieldFaces(terrain.presentation(scene), fieldSurfaces, true) > 0,
                          "Erasing authored scorch restores field shrubs through the existing surface cache");
                    terrain.scorch(scene, new GpuGroundDamage.Scorch(BoardGeometry.center(dirt, 0),
                          BoardGeometry.width() * board.getWidth() * 2, 0));
                    var clock = GpuGroundDamage.class.getDeclaredField("scorchedAt");
                    clock.setAccessible(true);
                    clock.setLong(terrain.groundDamage(), System.nanoTime() - 3_600_000_000_000L);
                }
                var centre = BoardGeometry.center(bank, scene.tile(bank).elevation());
                var outward = BoardGeometry.center(bank.translated(2), scene.tile(bank).elevation()).sub(centre).nor();
                var target = centre.cpy().mulAdd(outward, BoardGeometry.width() * .38f);
                target.z -= BoardGeometry.level();
                camera.camera.zoom = .145f;
                camera.camera.near = 1;
                camera.camera.far = 3000;
                camera.camera.up.set(Vector3.Z);
                camera.camera.position.set(target).mulAdd(outward, 650).add(0, 0, 350);
                camera.camera.lookAt(target);
                camera.camera.update();
                frame.prepare(terrain, camera, scene);
                terrain.update(scene, camera.camera);
                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                BoardScene shown = terrain.presentation(scene);
                var surface = new BoardSurface(shown, shown.tile(dirt));
                assertEquals(samples, scene.tile(dirt).features(), "Scorch must not mutate the source scene");
                assertTrue(scene.tiles().stream().noneMatch(BoardScene.Tile::barren), "Source tiles retain their vegetation policy");
                long fieldPlants = fieldFaces(shown, fieldSurfaces, true);
                long fieldStones = fieldFaces(shown, fieldSurfaces, false);
                if (!burned) {
                    assertTrue(fieldPlants > 0, "Empty-feature dirt tiles must exercise real PLANT/LEAF field faces");
                    assertTrue(fieldStones > 0, "The same field also contains independent stone faces");
                    cleanFieldStones = fieldStones;
                } else {
                    assertTrue(shown.tiles().stream().allMatch(BoardScene.Tile::barren),
                          "Every affected tile is barren, including tiles without scatter features");
                    assertEquals(0, fieldPlants, phase + " removes actual generated field shrubs");
                    assertEquals(cleanFieldStones, fieldStones, phase + " preserves the same field stones");
                }
                for (int i = 0; i < samples.size(); i++) {
                    var mesh = new MeshBuilder();
                    mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
                          | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates, GL20.GL_TRIANGLES);
                    if (shown.tile(dirt).features().contains(samples.get(i))) {
                        GpuScatter.build(mesh, shown.tile(dirt), surface, samples.get(i));
                    }
                    if (!burned) {
                        cleanVertices[i] = mesh.getNumVertices();
                        assertTrue(cleanVertices[i] > 0, "The dirt fixture must draw " + samples.get(i).asset());
                    } else {
                        assertEquals(i < 2 ? 0 : cleanVertices[i], mesh.getNumVertices(),
                              phase + " removes dry grass and shrubs while preserving rock geometry");
                    }
                }
                var planted = terrain.planted(bank);
                assertTrue(planted != null && planted.rim() != null && !planted.rim().isEmpty(),
                      "The focused two-level bank must exercise actual rim blades");
                terrain.setGrass(true);
                frame.render(terrain, camera, scene);
                int[] withGrass = pixels();
                GpuReviewFrame.save(new File(output, "grass-bank-" + phase + ".png"));
                terrain.setGrass(false);
                frame.render(terrain, camera, scene);
                assertEquals(burned, java.util.Arrays.equals(withGrass, pixels()),
                      "Clean bank grass must draw; " + phase + " must look identical with that grass disabled");
                terrain.setGrass(true);
                camera.fit(scene, List.of(scene.tile(dirt)));
                frame.prepare(terrain, camera, scene);
                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, "dry-grass-" + phase + ".png"));
                if (!burned) { programs = GpuShaderLoadSmokeTest.programs(terrain); }
                else {
                    assertEquals(programs, GpuShaderLoadSmokeTest.programs(terrain), "Removing grass links no new programs");
                }
                terrain.update(scene, camera.camera);
                var stableTiles = terrain.presentation(scene).tiles();
                terrain.update(scene, camera.camera);
                assertSame(stableTiles, terrain.presentation(scene).tiles(), "Unchanged frames retain the derived tile list");
                assertFalse(terrain.busy(), "Unchanged scorch does not start another geometry rebuild");
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); frame.dispose(); }
    }

    /** Inspect actual full-detail relief, including plants that were never captured as SCATTER features. */
    private static long fieldFaces(BoardScene scene, BoardSurface.Cache surfaces, boolean plants) {
        return scene.tiles().stream().filter(tile -> tile.coords().getX() >= 6 && tile.coords().getY() >= 7)
              .mapToLong(tile -> {
                  var surface = surfaces.get(scene, tile);
                  return surface.faces.stream().filter(face -> {
                      var shade = surface.relief.shade(face.a());
                      return shade != null && (plants
                            ? shade.kind() == BoardRelief.Kind.PLANT || shade.kind() == BoardRelief.Kind.LEAF
                            : shade.kind() == BoardRelief.Kind.ROCK);
                  }).count();
              }).sum();
    }

    private static void review(Board board, BoardScene scene, File output) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
        var camera = new BoardCamera();
        camera.resize(1600, 1000);
        camera.fit(scene);
        try {
            frame.prepare(terrain, camera, scene);
            terrain.update(scene, camera.camera);
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            frame.render(terrain, camera, scene);
            var damage = terrain.groundDamage();
            assertTrue(damage.scorchShown(), "The loaded appearance reaches the renderer");
            assertFalse(damage.scorched(), "Painted glass does not create nuclear heat");
            assertEquals(0f, damage.scorchHeat());
            int[] authored = directory(damage);
            checkAuthoredCells(damage, scene, authored);
            checkLocalEdit(damage, board, scene, authored);
            int programs = GpuShaderLoadSmokeTest.programs(terrain);
            for (boolean iso : new boolean[] { false, true }) {
                camera.setIsometric(iso);
                camera.fit(scene);
                frame.prepare(terrain, camera, scene);
                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                terrain.setCombatScars(true);
                frame.render(terrain, camera, scene);
                int[] game = pixels();
                GpuReviewFrame.save(new File(output, iso ? "game-angled.png" : "game-top.png"));
                terrain.setCombatScars(false);
                frame.render(terrain, camera, scene);
                assertTrue(java.util.Arrays.equals(game, pixels()), "The editor's disabled combat scars retain authored scorch");
                GpuReviewFrame.save(new File(output, iso ? "combat-disabled-angled.png" : "combat-disabled-top.png"));
            }
            // The shipped Atlas uses the same placement and scale conversion as a board unit.
            var units = new GpuUnitModels();
            var unitBatch = new ModelBatch(GpuUnitShader.provider());
            try {
                var asset = units.modular("units/modular/bodies/atlas.json");
                assertTrue(asset != null, "The scale reference uses the shipped Atlas");
                var visual = new GpuUnitModel(asset.model(), null, true);
                var unit = new ModelInstance(asset.model());
                camera.setIsometric(true);
                camera.tilt(45 - camera.tilt());
                camera.fit(scene, scene.tiles().stream().filter(tile -> tile.coords().getY() < 2).toList());
                visual.place(unit, camera.camera, BoardGeometry.center(new Coords(4, 1), 0), 0, 2, false);
                frame.render(terrain, camera, scene, List.of(unit), unitBatch);
                GpuReviewFrame.save(new File(output, "game-angled-unit-scale.png"));
            } finally { unitBatch.dispose(); units.dispose(); }
            var centre = BoardGeometry.center(new Coords(4, 2), 0);
            damage.scorch(new GpuGroundDamage.Scorch(centre, BoardRelief.metres(80), BoardRelief.metres(15)));
            damage.upload();
            int[] scorched = directory(damage);
            boolean strongerNuclear = false, strongerAuthored = false;
            for (int i = 0; i < scorched.length; i++) {
                assertEquals(authored[i] & 255, scorched[i] & 255, "A nuke preserves the authored channel");
                strongerNuclear |= (scorched[i] >>> 8 & 255) > (scorched[i] & 255);
                strongerAuthored |= (scorched[i] & 255) > (scorched[i] >>> 8 & 255);
            }
            assertTrue(strongerNuclear && strongerAuthored, "The fixture covers both sides of the stronger-scorch rule");
            camera.setIsometric(false);
            camera.fit(scene);
            frame.render(terrain, camera, scene);
            GpuReviewFrame.save(new File(output, "nuclear-fresh.png"));
            float freshHeat = damage.scorchHeat();
            assertTrue(freshHeat > .9f, "The fresh frame retains the blast's heat");
            camera.setIsometric(true);
            camera.tilt(45 - camera.tilt());
            camera.fit(scene, scene.tiles().stream().filter(tile -> tile.coords().equals(new Coords(4, 2))).toList());
            frame.render(terrain, camera, scene);
            int[] freshClose = pixels();
            GpuReviewFrame.save(new File(output, "nuclear-fresh-close.png"));
            // Advance only the renderer's existing heat clock; no 30-second wait and no different shader path.
            var clock = GpuGroundDamage.class.getDeclaredField("scorchedAt");
            clock.setAccessible(true);
            clock.setLong(damage, System.nanoTime() - 30_000_000_000L);
            frame.render(terrain, camera, scene);
            int[] coolClose = pixels();
            GpuReviewFrame.save(new File(output, "nuclear-30-seconds-close.png"));
            float cooledHeat = damage.scorchHeat();
            assertTrue(cooledHeat > .25f && cooledHeat < .35f, "The same blast has cooled for 30 seconds");
            long changed = java.util.stream.IntStream.range(0, freshClose.length)
                  .filter(i -> freshClose[i] != coolClose[i]).count();
            assertTrue(changed > 0, "The visible nuclear fissures cool without changing the persistent burn");
            Files.writeString(output.toPath().resolve("heat.txt"),
                  "Fresh heat=" + freshHeat + "; after 30 seconds=" + cooledHeat + "; changed close-up pixels=" + changed + "\n");
            camera.setIsometric(false);
            camera.fit(scene);
            frame.render(terrain, camera, scene);
            GpuReviewFrame.save(new File(output, "nuclear-30-seconds.png"));
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.getHex(x, y).setAppearance(Map.of()); }
            }
            // Preserve board identity, as an editor appearance edit does, while publishing a fresh immutable tile list.
            damage.update(scene.withTiles(BoardScenes.scene(board).tiles()));
            damage.upload();
            int[] erased = directory(damage);
            for (int i = 0; i < erased.length; i++) {
                assertEquals(0, erased[i] & 255, "Erase clears authored scorch");
                assertEquals(scorched[i] & 0xFFFFFF00, erased[i] & 0xFFFFFF00, "Erase preserves nuclear and combat damage");
            }
            assertEquals(programs, GpuShaderLoadSmokeTest.programs(terrain), "Scorch and heat are data, not program variants");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); frame.dispose(); }
    }

    /** Read the upload's actual CPU directory pages, which carry authored A and nuclear B independently. */
    @SuppressWarnings("unchecked")
    private static int[] directory(GpuGroundDamage damage) throws Exception {
        var field = GpuGroundDamage.class.getDeclaredField("directory");
        field.setAccessible(true);
        List<Pixmap> pages = (List<Pixmap>) field.get(damage);
        int[] values = new int[integer(damage, "tileColumns") * integer(damage, "tileRows")];
        int size = GpuGroundDamage.TILE_SIZE;
        for (int i = 0; i < values.length; i++) {
            int address = i % (size * size);
            values[i] = pages.get(i / (size * size)).getPixel(address % size, address / size);
        }
        return values;
    }

    private static int integer(GpuGroundDamage damage, String name) throws Exception {
        var field = GpuGroundDamage.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(damage);
    }

    private static void checkAuthoredCells(GpuGroundDamage damage, BoardScene scene, int[] values) throws Exception {
        int columns = integer(damage, "tileColumns");
        float size = BoardRelief.metres(GpuGroundDamage.TILE_METRES);
        int cleanFeather = 0, scorchedFeather = 0;
        for (int i = 0; i < values.length; i++) {
            float x = (i % columns + .5f) * size, y = -(i / columns + .5f) * size;
            var owner = BoardGeometry.tile(scene, x, y);
            int value = values[i] & 255;
            if (owner == null) { assertEquals(0, value, "No authored cells outside the board"); }
            else {
                int severity = Math.round(HexAppearance.scorch(owner.appearance()) * 255);
                if (severity == 0 && value > 0) { cleanFeather++; }
                if (severity > 0 && value > 0 && value < severity) { scorchedFeather++; }
            }
            assertEquals(0, values[i] & 0xFFFFFF00, "Authoring cannot write combat or nuclear channels");
        }
        assertTrue(cleanFeather > 0 && scorchedFeather > 0, "The transition extends to both sides of a painted boundary");
        for (var tile : scene.tiles()) {
            int expected = Math.round(HexAppearance.scorch(tile.appearance()) * 255);
            assertEquals(expected, values[cell(damage, BoardGeometry.center(tile.coords(), 0))] & 255,
                  "Every hex retains its authored severity at its centre");
        }
        for (int x : new int[] { 2, 4, 6, 8 }) {
            var across = BoardGeometry.center(new Coords(x, 3), 0).lerp(BoardGeometry.center(new Coords(x + 1, 3), 0), .5f);
            int severity = Math.round(HexAppearance.scorch(scene.tile(new Coords(x, 3)).appearance()) * 255);
            assertEquals(severity, values[cell(damage, across)] & 255,
                  "Matching neighbours have no internal fade or dark seam");
        }
    }

    /** A local edit reaches an unchanged neighbour, and erasing it restores the previous field exactly. */
    private static void checkLocalEdit(GpuGroundDamage damage, Board board, BoardScene scene, int[] before) throws Exception {
        var at = new Coords(0, 3);
        var neighbour = new Coords(1, 3);
        board.getHex(at).setAppearance(Map.of("scorch", new HexAppearance("scorch/glass", null, null, null)));
        damage.update(scene.withTiles(BoardScenes.scene(board).tiles()));
        int[] edited = directory(damage);
        int changedNeighbour = 0;
        for (int i = 0; i < edited.length; i++) {
            float x = (i % damage.tileColumns() + .5f) * damage.tileWorldSize();
            float y = -(i / damage.tileColumns() + .5f) * damage.tileWorldSize();
            if (BoardGeometry.contains(neighbour, x, y) && (edited[i] & 255) > (before[i] & 255)) { changedNeighbour++; }
            var owner = BoardGeometry.tile(scene, x, y);
            if (owner != null && owner.coords().distance(at) > 1) {
                assertEquals(before[i], edited[i], "A local edit cannot change distant cells");
            }
            assertEquals(before[i] & 0xFFFFFF00, edited[i] & 0xFFFFFF00, "A local edit preserves combat/nuclear channels");
        }
        assertTrue(changedNeighbour > 0, "Editing one hex refreshes the feather inside its unchanged neighbour");
        board.getHex(at).setAppearance(Map.of());
        damage.update(scene.withTiles(BoardScenes.scene(board).tiles()));
        assertTrue(java.util.Arrays.equals(before, directory(damage)), "Erase restores the complete neighbouring field");
    }

    private static int cell(GpuGroundDamage damage, com.badlogic.gdx.math.Vector3 point) {
        return (int) Math.floor(-point.y / damage.tileWorldSize()) * damage.tileColumns()
              + (int) Math.floor(point.x / damage.tileWorldSize());
    }

    private static int[] pixels() {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int[] values = new int[image.getWidth() * image.getHeight()];
            image.getPixels().asIntBuffer().get(values);
            return values;
        } finally { image.dispose(); }
    }
}
