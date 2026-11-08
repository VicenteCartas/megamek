/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
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
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.ConvInfantry;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native formation, feet and terrain-edit checks through the shared live board renderer. */
@Tag("on-demand")
class GpuInfantryRoughSmokeTest {
    private static final List<Coords> ROUGH = List.of(new Coords(2, 2), new Coords(6, 2), new Coords(5, 5));

    @Test
    void infantryFindFootingWhileVehiclesAndOrdinaryUnitsClipTheRoughCover() throws Exception {
        Hex[] hexes = new Hex[81];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                Hex hex = new Hex(x == 2 && y == 2 ? 2 : 0);
                hex.addTerrain(new Terrain(x < 4 ? Terrains.SAND : Terrains.SNOW, 1));
                hexes[y * 9 + x] = hex;
            }
        }
        Board board = new Board(9, 9, hexes);
        var original = BoardGeometry.tuning();
        var failure = new AtomicReference<Throwable>();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "infantry-rough");
        assertTrue(output.isDirectory() || output.mkdirs());
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                for (int id : new int[] { 2, 3 }) {
                    var infantry = new ConvInfantry();
                    infantry.setChassis(id == 2 ? "Rough Foot Platoon" : "Rough Tracked Platoon");
                    infantry.setModel("");
                    infantry.setId(id);
                    infantry.setOwner(fixture.player);
                    infantry.setPosition(ROUGH.get(id - 2));
                    infantry.setMovementMode(id == 2 ? EntityMovementMode.INF_LEG : EntityMovementMode.TRACKED);
                    infantry.setDeployed(true);
                    infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                    fixture.game.addEntity(infantry, false);
                }
                fixture.source.refresh();
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1440, 1080);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        BoardGeometry.tune(BoardGeometry.DEFAULTS);
                        view.create();
                        GpuBoardTestUi.present(view);
                        // Every unit needs its authored assembly here, the Atlas included.
                        GpuBoardTestUi.<SelectBox<UnitDisplayMode>>tuning(view, "tuning-unit-display").setSelected(UnitDisplayMode.MODELS);
                        view.render();
                        view.boardCamera.advance(BoardCamera.ENTRANCE_SECONDS);
                        view.render();
                        Map<String, Vector3> plain = positions(view);
                        edit(true);
                        view.render();
                        Map<String, Vector3> rough = positions(view);
                        int moved = 0;
                        for (var entry : plain.entrySet()) {
                            Vector3 next = rough.get(entry.getKey());
                            if (entry.getKey().contains("trooper-")) {
                                if (entry.getValue().dst2(next) > .01f) { moved++; }
                            } else {
                                assertEquals(entry.getValue(), next, "Rough must not relocate a vehicle or the ordinary unit");
                            }
                        }
                        assertTrue(moved > 0, "Some infantry must seek gaps between the boulders");
                        verifyFeet(view);
                        for (int id : new int[] { 2, 3, 1 }) {
                            Coords coords = id == 1 ? ROUGH.get(2) : ROUGH.get(id - 2);
                            view.boardCamera.setIsometric(true);
                            view.boardCamera.camera.zoom = .10f;
                            view.boardCamera.center(BoardGeometry.center(coords, id == 2 ? 2 : 0));
                            view.render();
                            verifyFeet(view);
                            assertEquals(rough, positions(view), "Watching and camera changes must not shuffle the formation");
                            GpuBoardTestUi.capture(new File(output, "unit-" + id + ".png"));
                        }
                        edit(false);
                        view.render();
                        assertEquals(plain, positions(view), "Removing Rough invalidates the fitted layout");
                        edit(true);
                        view.render();
                        assertEquals(rough, positions(view), "Rebuilding keeps the same terrain and formation layout");
                        verifyFeet(view);
                        crowdedRocks(view);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        view.dispose();
                        BoardGeometry.tune(original);
                        Gdx.app.exit();
                    }
                }

                private void edit(boolean rough) throws Exception {
                    SwingUtilities.invokeAndWait(() -> {
                        for (Coords coords : ROUGH) {
                            var hex = board.getHex(coords).duplicate();
                            if (rough) { hex.addTerrain(new Terrain(Terrains.ROUGH, 2)); }
                            else { hex.removeTerrain(Terrains.ROUGH); }
                            board.setHex(coords, hex);
                        }
                        fixture.source.refresh();
                    });
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Rough infantry placement", failure.get()); }
    }

    private static Map<String, Vector3> positions(GpuBattleView view) throws Exception {
        // A replaced board loads behind the loading screen before it can be inspected.
        GpuBoardTestUi.present(view);
        BoardScene scene = (BoardScene) GpuTestSupport.field(view, "scene");
        var instances = (Map<?, ?>) GpuTestSupport.field(view, "unitInstances");
        var models = (GpuUnitModels) GpuTestSupport.field(view, "unitModels");
        Map<String, Vector3> result = new LinkedHashMap<>();
        for (var unit : scene.units()) {
            ModelInstance instance = (ModelInstance) instances.get(unit.id() + ":-1");
            assertNotNull(instance);
            var model = models.get(unit.model(), unit.id());
            result.put(unit.id() + ":unit", instance.transform.getTranslation(new Vector3()));
            for (var rig : model.rigs()) {
                if (rig.container() != null) {
                    result.put(unit.id() + ":" + rig.container(), new Vector3(instance.getNode(rig.container()).translation));
                }
            }
        }
        return result;
    }

    private static void verifyFeet(GpuBattleView view) throws Exception {
        GpuBoardTestUi.present(view);
        BoardScene scene = (BoardScene) GpuTestSupport.field(view, "scene");
        var instances = (Map<?, ?>) GpuTestSupport.field(view, "unitInstances");
        var models = (GpuUnitModels) GpuTestSupport.field(view, "unitModels");
        var surfaces = (BoardSurface.Cache) GpuTestSupport.field(view, "groundSurfaces");
        for (var unit : scene.units()) {
            if (unit.id() == 1) { continue; }
            ModelInstance instance = (ModelInstance) instances.get(unit.id() + ":-1");
            verifyFeet(scene, unit, instance, models.get(unit.model(), unit.id()), surfaces);
        }
    }

    private static int verifyFeet(BoardScene scene, BoardScene.Unit unit, ModelInstance instance, GpuUnitModel model,
          BoardSurface.Cache surfaces) {
        int feet = 0, onRock = 0;
        for (var rig : model.rigs()) {
            if (!rig.trooper()) { continue; }
            float gap = Float.POSITIVE_INFINITY;
            for (var joint : rig.joints().entrySet()) {
                if (!joint.getKey().endsWith("Foot")) { continue; }
                var foot = UnitAnimator.find(instance.getNode(rig.container()).getChildren(), joint.getValue());
                var bounds = UnitBounds.subtree(foot);
                Vector3 point = new Vector3(bounds.getCenterX(), bounds.getCenterY(), bounds.min.z).mul(instance.transform);
                float ground = UnitLandingSupports.ground(scene, point.x, point.y, surfaces);
                gap = Math.min(gap, Math.abs(point.z - ground));
                if (ground > unit.location().elevation() * BoardGeometry.LEVEL + BoardGeometry.HEX_SCALE) { onRock++; }
                assertTrue(Float.isFinite(ground), "No footing beneath " + rig.container());
                assertTrue(point.z >= ground - .2f * BoardGeometry.HEX_SCALE,
                      "Foot clips Rough: " + rig.container() + " " + point + " ground=" + ground);
                assertTrue(UnitLandingSupports.terrain(scene, point.x, point.y, surfaces)
                      >= unit.location().elevation() * BoardGeometry.LEVEL - 2 * BoardGeometry.HEX_SCALE,
                      "Avoiding a boulder must not send infantry down the cliff");
                feet++;
            }
            assertTrue(gap < .2f * BoardGeometry.HEX_SCALE, "At least one foot must touch the ground or rock");
        }
        assertTrue(feet >= 4, "Real infantry feet must be checked");
        return onRock;
    }

    /** Fill a narrow plateau with two broad intersecting rocks so there genuinely is no remaining gap. */
    private static void crowdedRocks(GpuBattleView view) throws Exception {
        BoardScene original = (BoardScene) GpuTestSupport.field(view, "scene");
        var unit = original.units().stream().filter(value -> value.id() == 2).findFirst().orElseThrow();
        var tiles = original.tiles().stream().map(tile -> {
            if (!tile.coords().equals(unit.location().coords())) { return tile; }
            var rocks = List.of(new BoardScene.Feature("rough-boulder", 0, 0, 0, 4.8f, .5f, 0, BoardScene.FeatureKind.BOULDER),
                  new BoardScene.Feature("rough-boulder", 0, 0, 90, 4.8f, .5f, 0, BoardScene.FeatureKind.BOULDER));
            return new BoardScene.Tile(tile.coords(), tile.elevation(), -1, false, 0, tile.surface(), null, null, rocks,
                  List.of(), BoardLiquid.NONE, null, true);
        }).toList();
        BoardScene scene = new BoardScene(0, original.width(), original.height(), tiles, List.of(unit), List.of(), -1, "", List.of());
        var model = ((GpuUnitModels) GpuTestSupport.field(view, "unitModels")).get(unit.model(), unit.id());
        var instance = new ModelInstance(model.instance);
        var surfaces = new BoardSurface.Cache();
        var animator = new UnitAnimator(surfaces, () -> scene);
        animator.apply(model, instance, unit, UnitMotion.Sample.STILL, 0, 0, true, 0);
        model.place(instance, view.boardCamera.camera, BoardGeometry.center(unit.location().coords(), unit.location().elevation()), 0, unit);
        animator.groundSupports(scene, unit, UnitMotion.Sample.STILL);
        assertTrue(verifyFeet(scene, unit, instance, model, surfaces) > 0, "When no clear spot fits, stand on the rocks");
    }
}
