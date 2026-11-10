/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Whatever draws a model file as a prop takes the file's simpler levels. Seen from so far that every prop is small on
 * screen, no prop may still draw the near mesh of a file that holds more levels ({@link GpuTerrain#propFiles}, read
 * from what the terrain draws; the level count from the file itself). A board of the kinds the terrain owns (mineral
 * deposits, felled timber, dragon's teeth, rubble, a strongpoint, a trench, a cargo crate, a geyser) and the 48 by 48
 * city, with its placed objects. Kit buildings are not model files' props: their levels are their kits'.
 */
@Tag("on-demand")
class GpuPropLevelsSmokeTest {
    /** A hex this many pixels wide puts a prop five hexes long under the props' last threshold. */
    private static final float FAR_HEX_PIXELS = 3;

    @Test
    void farPropsDrawTheirFilesSimplerLevels() throws Exception {
        BoardScene kinds = kinds();
        BoardScene city = GpuCalibrationScenes.scene(new File("data/boards/unofficial/VictorMorson/48x48.board"));
        List<String> failures = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    Map<File, Boolean> drawn = check(kinds, "kinds", failures);
                    for (String kind : List.of("minerals/deposit", "rough/felled-trunk", "rough/dragon-tooth", "scenery/rubble/",
                          "scenery/military/fortification", "scenery/military/trench", "scenery/military/cargo-crate",
                          "scenery/geysers/")) {
                        assertTrue(drawn.keySet().stream().anyMatch(file -> file.getPath().replace('\\', '/').contains(kind)),
                              () -> "The kinds board draws " + kind + ": " + drawn.keySet());
                    }
                    check(city, "48x48", failures);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Prop levels", failure.get()); }
        assertTrue(failures.isEmpty(), () -> "Props far away draw the near mesh of files that hold simpler levels: " + failures);
    }

    /** The board drawn from far away: each model file its props draw, and whether a prop still draws its near mesh. */
    private static Map<File, Boolean> check(BoardScene scene, String name, List<String> failures) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(GpuCalibrationScenes.NOON);
        var camera = new BoardCamera();
        try {
            GpuCalibrationScenes.prepare(terrain, frame, camera, scene);
            camera.camera.zoom *= BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera.camera) / FAR_HEX_PIXELS;
            camera.update();
            GpuCalibrationScenes.settle(terrain, frame, camera, scene);
            assertTrue(!terrain.pagesMerging(), name + ": the settled view has packed its props' levels");
            Map<File, Boolean> drawn = terrain.propFiles();
            drawn.forEach((file, near) -> {
                if (near && RigidGlb.levels(file) > 1) { failures.add(name + ": " + file.getName()); }
            });
            return drawn;
        } finally {
            frame.dispose();
            terrain.dispose();
        }
    }

    /** One hex of each kind of terrain-owned model, as a game shows them, with a dug-in platoon's trench and a crate. */
    private static BoardScene kinds() throws Exception {
        Hex[] hexes = new Hex[16];
        for (int index = 0; index < hexes.length; index++) { hexes[index] = new Hex(0); }
        hexes[0].addTerrain(new Terrain(Terrains.METAL_CONTENT, 6));
        // Felled timber, and dragon's teeth.
        hexes[1].addTerrain(new Terrain(Terrains.ROUGH, 1));
        hexes[1].addTerrain(new Terrain(Terrains.FLUFF, 2));
        hexes[2].addTerrain(new Terrain(Terrains.ROUGH, 1));
        hexes[2].addTerrain(new Terrain(Terrains.FLUFF, 1));
        hexes[4].addTerrain(new Terrain(Terrains.RUBBLE, 2));
        hexes[5].addTerrain(new Terrain(Terrains.FORTIFIED, 1));
        hexes[6].addTerrain(new Terrain(Terrains.GEYSER, Terrains.GEYSER_LVL_DORMANT));
        try (var fixture = GpuBoardFixture.create(new Board(4, 4, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            Coords trench = new Coords(1, 2), cargo = new Coords(2, 2);
            return scene.withTiles(scene.tiles().stream().map(tile -> tile.coords().equals(trench)
                  ? BoardFeatures.withTrench(tile, 3) : tile.coords().equals(cargo) ? BoardFeatures.withCargo(tile) : tile).toList());
        }
    }
}
