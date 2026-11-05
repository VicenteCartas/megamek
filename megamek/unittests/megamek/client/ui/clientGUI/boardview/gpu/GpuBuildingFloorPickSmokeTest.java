/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native unit selection through buildings, without capturing clicks on another floor outside the unit. */
@Tag("on-demand")
class GpuBuildingFloorPickSmokeTest {
    private static final Coords BUILDING = new Coords(3, 3);

    @Test
    void buildingFloorHitsAreNotReplacedByTheUnitOnAnotherFloor() throws Exception {
        checkPicking(false);
    }

    @Test
    void unitOnFloorTenOfTwentyIsSelectableThroughTheSixSurroundingBuildingHexes() throws Exception {
        checkPicking(true);
    }

    private static void checkPicking(boolean surrounded) throws Exception {
        int floor = surrounded ? 10 : 3;
        Board board = Board.createEmptyBoard(7, 7);
        String building = "building:2;bldg_elev:" + (surrounded ? 20 : 5) + ";bldg_cf:120";
        board.setHex(BUILDING, new Hex(0, building, ""));
        if (surrounded) {
            for (int direction = 0; direction < 6; direction++) {
                board.setHex(BUILDING.translated(direction), new Hex(0, building, ""));
            }
            // Rebuild the rules' building after all seven connected hexes have their exits.
            Hex[] hexes = new Hex[49];
            for (int y = 0; y < 7; y++) {
                for (int x = 0; x < 7; x++) {
                    Hex hex = board.getHex(x, y);
                    var structure = hex.getTerrain(Terrains.BUILDING);
                    if (structure != null) {
                        for (int direction = 0; direction < 6; direction++) {
                            structure.setExit(direction, BUILDING.distance(new Coords(x, y).translated(direction)) <= 1);
                        }
                    }
                    hexes[y * 7 + x] = hex;
                }
            }
            board.newData(7, 7, hexes, null);
            assertEquals(7, board.getBuildingAt(BUILDING).getCoordsList().size());
        }
        var failure = new AtomicReference<Throwable>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            onSwing(() -> {
                fixture.entity.setPosition(BUILDING);
                fixture.entity.setElevation(floor);
                fixture.source.refresh();
                return null;
            });
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            config.setInitialVisible(false);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private final long deadline = System.nanoTime() + 120_000_000_000L;
                private int phase;
                private long after;

                @Override
                public void render() {
                    try {
                        assertTrue(System.nanoTime() < deadline, "Building floor pick timed out");
                        super.render();
                        var terrain = (GpuTerrain) GpuTestSupport.field(this, "terrain");
                        if (GpuBoardTestUi.loading(this) || terrain.busy() || frames() < after) { return; }
                        if (phase == 0) {
                            boardCamera.setIsometric(true);
                            boardCamera.setPerspective(false);
                            boardCamera.camera.zoom = .4f;
                            boardCamera.center(BoardGeometry.center(BUILDING, floor - 1));
                            phase++;
                            after = frames() + 3;
                        } else if (phase == 1) {
                            checkFloors(this, floor);
                            if (surrounded) { checkOccludedUnit(this, fixture.entity.getId()); }
                            boardCamera.setPerspective(true);
                            phase++;
                            after = frames() + 3;
                        } else if (phase == 2) {
                            checkFloors(this, floor);
                            if (surrounded) {
                                checkOccludedUnit(this, fixture.entity.getId());
                                boardCamera.setTactical(true, (BoardScene) GpuTestSupport.field(this, "scene"));
                                phase++;
                                after = frames() + 3;
                            } else { Gdx.app.exit(); }
                        } else {
                            checkTacticalUnit(this, fixture.entity.getId());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Building floor picking", failure.get()); }
    }

    private static void checkFloors(GpuBattleView view, int unitFloor) throws Exception {
        var scene = (BoardScene) GpuTestSupport.field(view, "scene");
        Object input = GpuTestSupport.field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pickSelection", int.class, int.class);
        pick.setAccessible(true);
        var camera = view.boardCamera.camera;
        Vector3 center = camera.project(BoardGeometry.center(BUILDING, unitFloor - 1), 0, 0,
              camera.viewportWidth, camera.viewportHeight);
        int centerX = Math.round(center.x);
        int centerY = Gdx.graphics.getHeight() - Math.round(center.y);
        Set<Integer> floors = new HashSet<>();
        for (int x = centerX - 100; x <= centerX + 100; x += 10) {
            for (int y = centerY - 220; y <= centerY + 220; y += 10) {
                Object picked = pick.invoke(input, x, y);
                Coords coords = (Coords) value(picked, "coords");
                if (coords == null || coords.distance(BUILDING) > 1 || !scene.tile(coords).building()) { continue; }
                float z = (float) value(picked, "pointedZ");
                if (!Float.isFinite(z)) { continue; } // A direct unit hit still selects that unit.
                int level = GpuLosResult.pointedLevel(z);
                if (level == unitFloor) { continue; }
                assertEquals(Entity.NONE, value(picked, "entityId"), "Surface at floor " + level);
                floors.add(level);
                ((InputProcessor) input).mouseMoved(x, y);
                assertEquals(Entity.NONE, GpuTestSupport.<Object>field(view, "hoveredUnit"));
            }
        }
        assertTrue(floors.size() >= 2, "The pointer sweep must cover multiple floors: " + floors);
    }

    @SuppressWarnings("unchecked")
    private static void checkOccludedUnit(GpuBattleView view, int unitId) throws Exception {
        var scene = (BoardScene) GpuTestSupport.field(view, "scene");
        var terrain = (GpuTerrain) GpuTestSupport.field(view, "terrain");
        var instances = (Map<String, ModelInstance>) GpuTestSupport.field(view, "unitInstances");
        var unit = scene.units().stream().filter(candidate -> candidate.id() == unitId).findFirst().orElseThrow();
        assertEquals(10, unit.location().elevation());
        ModelInstance instance = instances.get(unit.id() + ":" + unit.part());
        assertNotNull(instance);
        var camera = view.boardCamera.camera;
        Vector3 center = camera.project(UnitBounds.world(instance).getCenter(new Vector3()), 0, 0,
              camera.viewportWidth, camera.viewportHeight);
        int centerX = Math.round(center.x), centerY = Gdx.graphics.getHeight() - Math.round(center.y);
        var geometry = new UnitPicking();
        for (int y = centerY - 30; y <= centerY + 30; y += 2) {
            for (int x = centerX - 30; x <= centerX + 30; x += 2) {
                var ray = camera.getPickRay(x, y, 0, 0, camera.viewportWidth, camera.viewportHeight);
                float distance = geometry.distance(instance, ray);
                if (!Float.isFinite(distance)) { continue; }
                var ground = terrain.selectionHit(scene, ray);
                if (ground == null || ground.coords().equals(BUILDING) || ground.distance() >= distance) { continue; }
                assertTrue(scene.tile(ground.coords()).building(), "An outer building hex lies before the unit");
                assertEquals(1, BUILDING.distance(ground.coords()));
                assertUnitClick(view, x, y, unitId);
                // The first pixel outside the mesh lies on its coloured outline, not on any model triangle.
                int edge = x;
                do {
                    edge--;
                } while (Float.isFinite(geometry.distance(instance, camera.getPickRay(edge, y, 0, 0,
                      camera.viewportWidth, camera.viewportHeight))));
                assertUnitClick(view, edge, y, unitId);
                return;
            }
        }
        throw new AssertionError("The ray must reach the floor-10 unit through an outer building hex");
    }

    private static void checkTacticalUnit(GpuBattleView view, int unitId) throws Exception {
        var scene = (BoardScene) GpuTestSupport.field(view, "scene");
        assertTrue(view.boardCamera.tactical());
        // The Tactical View's base lies centred on the unit's meeple.
        ModelInstance base = ((GpuUnitBases) GpuTestSupport.field(view, "unitBases")).base(scene.units().getFirst());
        var camera = view.boardCamera.camera;
        Vector3 center = camera.project(base.transform.getTranslation(new Vector3()), 0, 0,
              camera.viewportWidth, camera.viewportHeight);
        assertUnitClick(view, Math.round(center.x), Gdx.graphics.getHeight() - Math.round(center.y), unitId);
    }

    private static void assertUnitClick(GpuBattleView view, int x, int y, int unitId) throws Exception {
        Object input = GpuTestSupport.field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pickSelection", int.class, int.class);
        pick.setAccessible(true);
        Object picked = pick.invoke(input, x, y);
        assertEquals(unitId, value(picked, "entityId"), "The unit takes a direct click through surrounding scenery");
        assertEquals(BUILDING, value(picked, "coords"));
        assertTrue(Float.isNaN((float) value(picked, "pointedZ")), "A unit click does not point at the outer wall's floor");
        ((InputProcessor) input).mouseMoved(x, y);
        assertEquals(unitId, GpuTestSupport.<Object>field(view, "hoveredUnit"));
        var hud = (GpuHud) GpuTestSupport.field(view, "ui");
        assertFalse(hud.hit(x, y), "The test clicks the board, outside HUD panels");
        hud.state.clearSelection();
        assertEquals(Entity.NONE, hud.state.cardUnit());
        GpuBoardTestUi.withModifiers(0, () -> {
            Gdx.input.getInputProcessor().touchDown(x, y, 0, Input.Buttons.LEFT);
            Gdx.input.getInputProcessor().touchUp(x, y, 0, Input.Buttons.LEFT);
        });
        assertEquals(unitId, hud.state.cardUnit(), "A real board click selects the unit");
    }

    private static Object value(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
