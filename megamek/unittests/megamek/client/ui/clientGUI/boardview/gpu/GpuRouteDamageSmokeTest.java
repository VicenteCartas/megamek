/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuRouteDamageSmokeTest {
    @Test void damagedRoutesUpdateWhileUnrelatedRoadSupportStaysInstalled() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                try (var output = new GpuSceneTestOutput(640, 480)) {
                    var board = Board.createEmptyBoard(9, 9);
                    for (int y = 1; y <= 5; y++) { board.setHex(2, y, new Hex(0, "road:1:9;rail:1:9", "")); }
                    for (int y = 6; y <= 8; y++) { board.setHex(6, y, new Hex(0, "road:1:9", "")); }
                    var scene = BoardScenes.scene(board);
                    terrain.update(scene);
                    var roadAt = new Coords(6, 7);
                    var roadSupport = terrain.tacticalSurface(roadAt);
                    var plants = terrain.planted(roadAt);
                    var lost = new Coords(2, 3);
                    for (int depth = 0; depth <= 2; depth += 2) {
                        var hex = board.getHex(lost);
                        hex.destroyRoad(board::getHex); RailRoute.destroyGround(hex);
                        hex.removeAllTerrains(); hex.setLevel(-depth); board.setHex(lost, hex);
                        scene = BoardScenes.scene(board); terrain.update(scene);
                        assertSame(roadSupport, terrain.tacticalSurface(roadAt), "Unrelated road support within the changed chunk stays installed");
                        assertSame(plants, terrain.planted(roadAt));
                        var survivor = scene.tile(new Coords(2, 2));
                        assertEquals(8, survivor.roadCuts());
                        assertEquals(8, survivor.railCuts().ground());
                        var camera = new BoardCamera(); camera.resize(640, 480); camera.fit(scene);
                        for (int frame = 0; frame < 3; frame++) { output.draw(terrain, camera.camera, true); }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Damaged route rendering", failure.get()); }
    }
}
