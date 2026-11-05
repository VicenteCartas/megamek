/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.ConvInfantry;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live-view placement on the river-cut plateaus of Mountain Lake, including mechanized transports. */
@Tag("on-demand")
class GpuInfantryPlateauSmokeTest {
    @Test
    void formationsStaySupportedAfterCaptureTerrainEditsAndInstantArrival() throws Exception {
        var board = BoardFile.loadSheet(new File("data/boards/Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"), false, true);
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var originalRelief = BoardRelief.tuning();
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "infantry-plateau");
        assertTrue(output.isDirectory() || output.mkdirs());
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                for (int id : new int[] { 2, 3 }) {
                    var infantry = new ConvInfantry();
                    infantry.setChassis(id == 2 ? "Jump Platoon" : "Mechanized Tracked Platoon");
                    infantry.setModel("");
                    infantry.setId(id);
                    infantry.setOwner(fixture.player);
                    infantry.setPosition(new Coords(8, id == 2 ? 12 : 13));
                    infantry.setMovementMode(id == 2 ? EntityMovementMode.INF_JUMP : EntityMovementMode.TRACKED);
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
                        view.create();
                        BoardGeometry.tune(BoardGeometry.DEFAULTS);
                        GpuBoardTestUi.present(view);
                        view.render();
                        view.boardCamera.advance(BoardCamera.ENTRANCE_SECONDS);
                        for (boolean cliffs : new boolean[] { false, true }) {
                            BoardScenes.tune(cliffs);
                            view.boardCamera.setIsometric(true);
                            view.boardCamera.camera.zoom = .16f;
                            view.boardCamera.center(BoardGeometry.center(new Coords(8, 14), 0));
                            view.render();
                            GpuBoardTestUi.present(view);
                            GpuBoardTestUi.capture(new File(output, cliffs ? "cliff-into-water.png" : "cliff-with-beach.png"));
                            if (cliffs) {
                                view.boardCamera.orbit(30, 15);
                                view.render();
                                GpuBoardTestUi.present(view);
                                GpuBoardTestUi.capture(new File(output, "cliff-foot-oblique.png"));
                                for (var bank : List.of(new Coords(7, 14), new Coords(9, 13))) {
                                    view.boardCamera.setIsometric(true);
                                    view.boardCamera.orbit(0, -15);
                                    view.boardCamera.camera.zoom = .10f;
                                    view.boardCamera.center(BoardGeometry.center(bank, 0));
                                    view.render();
                                    GpuBoardTestUi.present(view);
                                    GpuBoardTestUi.capture(new File(output, "cliff-bank-" + bank.getX() + ".png"));
                                }
                            }
                        }
                        view.render();
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.camera.zoom = .17f;
                        view.boardCamera.center(BoardGeometry.center(new Coords(8, 13), 2));
                        view.render();
                        GpuBoardTestUi.present(view);
                        GpuBoardTestUi.capture(new File(output, "river-plateau-oblique.png"));
                        verify(view);
                        view.boardCamera.setIsometric(false);
                        view.boardCamera.camera.zoom = .19f;
                        view.boardCamera.center(BoardGeometry.center(new Coords(8, 13), 2));
                        view.render();
                        verify(view);
                        GpuBoardTestUi.present(view);
                        GpuBoardTestUi.capture(new File(output, "river-plateau-top.png"));

                        // Reuse the animators while the neighbouring bed changes the rendered river/cliff shape.
                        SwingUtilities.invokeAndWait(() -> {
                            var coords = new Coords(9, 12);
                            var hex = board.getHex(coords).duplicate();
                            hex.addTerrain(new Terrain(Terrains.WATER, 2));
                            board.setHex(coords, hex);
                            fixture.source.refresh();
                        });
                        view.render();
                        verify(view);

                        // The completion callback can create an animator before the first visible arrival frame.
                        var scene = (BoardScene) field(view, "scene");
                        var unit = scene.units().stream().filter(value -> value.id() == 3).findFirst().orElseThrow();
                        ((Map<?, ?>) field(view, "animators")).remove("3:-1");
                        var playback = (UnitPlayback) field(view, "playback");
                        var start = new BoardScene.Waypoint(new Coords(7, 13), 2, 0);
                        playback.accept(List.of(new BoardScene.Movement(3, 0, List.of(start, unit.location()),
                              EntityMovementType.MOVE_WALK, 0, 1, unit)), scene, ignored -> true);
                        playback.advance(0, UnitMotion.Speed.INSTANT);
                        verify(view);
                        view.render();
                        verify(view);
                        SwingUtilities.invokeAndWait(() -> {
                            var coords = new Coords(9, 12);
                            var hex = board.getHex(coords).duplicate();
                            hex.addTerrain(new Terrain(Terrains.WATER, 0));
                            board.setHex(coords, hex);
                            fixture.source.refresh();
                        });
                        view.render();
                        verify(view);
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        view.dispose();
                        BoardGeometry.tune(original);
                        BoardRelief.tune(originalRelief);
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Infantry plateau placement", failure.get()); }
    }

    private static void verify(GpuBattleView view) throws Exception {
        // A replaced board loads behind the loading screen before it can be inspected.
        GpuBoardTestUi.present(view);
        var scene = (BoardScene) field(view, "scene");
        var instances = (Map<?, ?>) field(view, "unitInstances");
        var models = (GpuUnitModels) field(view, "unitModels");
        var surfaces = (BoardSurface.Cache) field(view, "groundSurfaces");
        for (var unit : scene.units()) {
            if (unit.id() != 2 && unit.id() != 3) { continue; }
            var instance = (ModelInstance) instances.get(unit.id() + ":-1");
            assertNotNull(instance);
            var model = models.get(unit.model(), unit.id());
            var tile = scene.tile(unit.location().coords());
            for (var rig : model.rigs()) {
                var member = instance.getNode(rig.container());
                var rest = model.instance.getNode(rig.container()).copy();
                rest.translation.setZero();
                rest.rotation.idt();
                rest.calculateTransforms(true);
                var outline = InfantryFootprint.outline(UnitBounds.subtree(rest),
                      -member.rotation.getAngleAround(Vector3.Z), rig.trooper());
                var vertices = outline.getTransformedVertices();
                for (int v = 0; v < vertices.length; v += 2) {
                    int next = (v + 2) % vertices.length;
                    for (float fraction : new float[] { 0, .5f }) {
                        var point = new Vector3(vertices[v] + fraction * (vertices[next] - vertices[v]),
                              vertices[v + 1] + fraction * (vertices[next + 1] - vertices[v + 1]), 0)
                              .add(member.translation).mul(instance.transform);
                        // A joined neighbour can own part of the plateau. Rock outcrops can rise above it; the
                        // regression is missing support or a descending bank beneath the formation's footprint.
                        float ground = UnitLandingSupports.ground(scene, point.x, point.y, surfaces);
                        assertTrue(Float.isFinite(ground)
                                    && ground >= tile.elevation() * BoardGeometry.LEVEL - 2 * BoardGeometry.HEX_SCALE,
                              unit.location().coords() + " " + rig.container() + " is unsupported at " + point
                                    + "; ground " + ground);
                    }
                }
            }
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
