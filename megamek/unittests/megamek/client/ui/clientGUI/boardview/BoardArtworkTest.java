/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardArtworkTest {
    private static final Map<Integer, String> STRUCTURES = Map.of(
          Terrains.BUILDING, "buildings/test/building",
          Terrains.FUEL_TANK, "buildings/fuel_tanks/fuel_tank_light",
          Terrains.INDUSTRIAL, "buildings/test/industrial");
    /**
     * Each retired structure sprite marks its own pixel, so capture can detect obsolete art leaking into the ground.
     */
    private static final Map<String, Integer> MARKS = Map.of("models/board/classic/buildings/test/building.png",
          10, "models/board/classic/buildings/test/tank.png", 20,
          "models/board/classic/buildings/test/industrial.png", 30);

    @TempDir
    Path directory;

    @BeforeEach
    void tileset() throws Exception {
        BoardEditorBlueprint.get();
        Path tileset = Files.createDirectories(directory.resolve("models/board/classic/tileset"));
        Files.writeString(tileset.resolve("classic.tileset"), """
              super * "building:1" "" "buildings/test/building.png"
              super * "fuel_tank:1" "" "buildings/test/tank.png"
              super * "heavy_industrial:1" "" "buildings/test/industrial.png"
              base * "" "" "terrain/ground.png"
              """);
        for (String name : List.of("models/board/classic/terrain/ground.png",
              "models/board/classic/buildings/test/building.png",
              "models/board/classic/buildings/test/tank.png",
              "models/board/classic/buildings/test/industrial.png", "images/hexes/transparent/HexMask.png")) {
            var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xffffffff);
            if (MARKS.containsKey(name)) { image.setRGB(MARKS.get(name), MARKS.get(name), 0xffff0000); }
            Path file = directory.resolve(name);
            Files.createDirectories(file.getParent());
            ImageIO.write(image, "png", file.toFile());
        }
    }

    @Test
    void aNativeBuildingDesignAndTankWeightSelectTheirKits() throws Exception {
        for (int type : List.of(Terrains.BUILDING, Terrains.FUEL_TANK)) {
            model("models/" + STRUCTURES.get(type) + ".glb");
        }
        assertEquals(Map.of(Terrains.BUILDING, STRUCTURES.get(Terrains.BUILDING),
              Terrains.FUEL_TANK, STRUCTURES.get(Terrains.FUEL_TANK)), capture(designs()).structureModels());
    }

    @Test
    void retiredBoardModelsCannotBeSelectedByTilesetArtwork() throws Exception {
        for (String asset : STRUCTURES.values()) { model("models/board/" + asset + ".glb"); }
        assertEquals(Map.of(), capture(Map.of()).structureModels());
    }

    @Test
    void nativeKitsAndGeneratedIndustrialTerrainCoexist() throws Exception {
        model("models/buildings/test/building.glb");
        model("models/" + STRUCTURES.get(Terrains.FUEL_TANK) + ".glb");
        Path tileset = directory.resolve("models/board/classic/tileset/classic.tileset");
        String industrial = "buildings/misc/heavy_industrial_a";
        Files.writeString(tileset, Files.readString(tileset).replace("buildings/test/industrial.png", industrial));
        assertEquals(Map.of(Terrains.BUILDING, STRUCTURES.get(Terrains.BUILDING),
              Terrains.FUEL_TANK, STRUCTURES.get(Terrains.FUEL_TANK), Terrains.INDUSTRIAL, industrial),
              capture(designs()).structureModels());
    }

    @Test
    void obsoleteG3djFilesDoNotAdvertiseModelsThatTheRendererCannotLoad() throws Exception {
        for (String asset : STRUCTURES.values()) { model("models/board/" + asset + ".g3dj"); }
        assertEquals(Map.of(), capture(Map.of()).structureModels());
    }

    @Test
    void classicGroundNeverFallsBackToRetiredStructureSprites() throws Exception {
        model("models/buildings/test/building.glb");
        model("models/" + STRUCTURES.get(Terrains.FUEL_TANK) + ".glb");
        BufferedImage art = capture(designs()).tileset();
        assertEquals(0, art.getRGB(10, 10) >>> 24, "The building's model replaces its art");
        assertEquals(0, art.getRGB(20, 20) >>> 24, "So does the tank's modular kit");
        assertEquals(0, art.getRGB(30, 30) >>> 24, "A missing native model cannot revive its old sprite");
    }

    private void model(String name) throws Exception {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        // Artwork discovery checks existence; decoding belongs to the renderer's asset tests.
        Files.createFile(file);
    }

    private static Map<String, HexAppearance> designs() {
        return Map.of("building", new HexAppearance(null, STRUCTURES.get(Terrains.BUILDING), null, null));
    }

    private BoardArtwork.HexImage capture(Map<String, HexAppearance> designs) throws Exception {
        var task = new FutureTask<BoardArtwork.HexImage>(() -> {
            File data = Configuration.dataDir();
            Configuration.setDataDir(directory.toFile());
            try (var artwork = new BoardArtwork()) {
                Board board = Board.createEmptyBoard(1, 1);
                var coords = new Coords(0, 0);
                board.setHex(coords, new Hex(0, "building:1;bldg_elev:2;bldg_cf:15;fuel_tank:1;"
                      + "fuel_tank_elev:1;fuel_tank_cf:15;fuel_tank_magn:10;heavy_industrial:1", ""));
                board.getHex(coords).setAppearance(designs);
                return artwork.capture(board, coords, board.getHex(coords));
            } finally {
                Configuration.setDataDir(data);
            }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
