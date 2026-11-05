/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Tank;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The game's half of the user's under-water report (2026-10-11): a submarine below the water's surface takes a click
 * through that surface, in the 3D view from above and at an angle and in the Tactical View. Units are picked by their
 * drawn model before the terrain (GpuBattleView#pickSelection), so the water surface never intercepts them.
 */
@Tag("on-demand")
class GpuUnderwaterUnitPickSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
    private static final Coords POSITION = new Coords(4, 4);
    private static final int SUBMARINE = 2;

    @Test
    void aSubmergedSubmarineTakesAClickThroughTheWaterSurface() throws Exception {
        Board board = new Board(9, 9);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                Hex hex = new Hex();
                hex.addTerrain(new Terrain(Terrains.WATER, 4));
                board.setHex(x, y, hex);
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(new Coords(1, 1));
                Tank submarine = new Tank();
                submarine.setId(SUBMARINE);
                submarine.setOwner(fixture.player);
                submarine.setChassis("Submerged vessel");
                submarine.setWeight(50);
                submarine.setMovementMode(EntityMovementMode.SUBMARINE);
                submarine.setElevation(-3);
                submarine.setPosition(POSITION);
                submarine.setDeployed(true);
                fixture.game.addEntity(submarine, false);
                fixture.source.refresh();
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1600, 1000);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        view = new GpuBattleView(fixture.source);
                        view.create();
                        GpuBoardTestUi.present(view);
                        BoardScene scene = (BoardScene) field(view, "scene");
                        view.boardCamera.center(BoardGeometry.center(POSITION, scene.tile(POSITION).elevation()));
                        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
                        view.boardCamera.setIsometric(false); frames(view);
                        assertPickedThroughWater(view);
                        view.boardCamera.setIsometric(true); frames(view);
                        assertPickedThroughWater(view);
                        GpuBoardTestUi.capture(new File(OUTPUT, "game-submarine-under-water.png"));
                        view.setTacticalView(true); frames(view);
                        // Batch 11 draws the Tactical View's units as their models on bases, not as icons.
                        assertEquals(SUBMARINE, entityAt(view, UnitBounds.world(model(view)).getCenter(new Vector3())),
                              "The Tactical View's unit takes the click");
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (view != null) { view.dispose(); }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    /** The ray at the submarine's centre meets the water's surface first, and the pick still takes the submarine. */
    private static void assertPickedThroughWater(GpuBattleView view) throws Exception {
        var scene = (BoardScene) field(view, "scene");
        var terrain = (GpuTerrain) field(view, "terrain");
        var instance = model(view);
        assertNotNull(instance, "The submarine is a 3D model");
        var bounds = UnitBounds.world(instance);
        float surface = BoardGeometry.waterZ(scene.tile(POSITION));
        assertTrue(bounds.max.z < surface, "The submarine lies below the surface: " + bounds + " under " + surface);
        Vector3 centre = bounds.getCenter(new Vector3());
        var camera = view.boardCamera.camera;
        Vector3 point = camera.project(new Vector3(centre), 0, 0, camera.viewportWidth, camera.viewportHeight);
        var ray = camera.getPickRay(Math.round(point.x), Gdx.graphics.getHeight() - Math.round(point.y), 0, 0,
              camera.viewportWidth, camera.viewportHeight);
        var ground = terrain.selectionHit(scene, ray);
        assertNotNull(ground);
        assertEquals(surface, ray.getEndPoint(new Vector3(), (float) Math.sqrt(ground.distance())).z, .05f * BoardGeometry.level(),
              "The terrain pick stops at the water's surface");
        assertTrue(Float.isFinite(new UnitPicking().distance(instance, ray)), "The ray reaches the submarine's model");
        assertEquals(SUBMARINE, entityAt(view, centre), "The click takes the submarine beneath the surface");
    }

    /** The submarine's drawn model. */
    @SuppressWarnings("unchecked")
    private static ModelInstance model(GpuBattleView view) throws Exception {
        return ((Map<String, ModelInstance>) field(view, "unitInstances")).get(SUBMARINE + ":-1");
    }

    /** The unit pickSelection takes at a world point's pixel. */
    private static int entityAt(GpuBattleView view, Vector3 world) throws Exception {
        var camera = view.boardCamera.camera;
        Vector3 point = camera.project(new Vector3(world), 0, 0, camera.viewportWidth, camera.viewportHeight);
        Object input = field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pickSelection", int.class, int.class);
        pick.setAccessible(true);
        Object picked = pick.invoke(input, Math.round(point.x), Gdx.graphics.getHeight() - Math.round(point.y));
        var entity = picked.getClass().getDeclaredMethod("entityId");
        entity.setAccessible(true);
        return (int) entity.invoke(picked);
    }

    private static void frames(GpuBattleView view) {
        for (int frame = 0; frame < 4; frame++) { view.render(); }
    }
}
