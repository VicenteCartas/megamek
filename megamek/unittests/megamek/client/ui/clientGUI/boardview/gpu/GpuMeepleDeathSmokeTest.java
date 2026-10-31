/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.equipment.IArmorState;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A destroyed unit's meeple explodes away from its hit, its pieces come to rest on the ground and scenery around and
 * sink away; a meeple formation's lost figures fall, lie and sink. Neither leaves a drawn wreck.
 */
@Tag("on-demand")
class GpuMeepleDeathSmokeTest {
    private static final float FRAME = 1 / 60f;

    @Test
    void meeplesExplodeAndFormationCasualtiesFallThenAllSink() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            List<Entity> units = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    units.add(fixture.entity);
                    units.add(add(fixture, new MekFileParser(new File("testresources/megamek/common/units/Bulldog Medium Tank.blk"))
                          .getEntity(), 2, new Coords(6, 5)));
                    units.add(add(fixture, platoon(EntityMovementMode.INF_LEG), 3, new Coords(4, 5)));
                    units.add(add(fixture, platoon(EntityMovementMode.INF_MOTORIZED), 4, new Coords(5, 6)));
                    units.add(add(fixture, new MekFileParser(new File("testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk"))
                          .getEntity(), 5, new Coords(6, 6)));
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.center(BoardGeometry.center(new Coords(5, 5), 0).add(0, -20, 0));
                        view.boardCamera.zoom(.3f);
                        view.render();
                        capture("before");
                        // One zoom from the explosion to the checks: small scenery the zoom hides is looked through.
                        view.boardCamera.zoom(.6f);
                        var atlas = UnitBounds.world(instance(view, 1)).getCenter(new Vector3());
                        // The Atlas was just hit from the west for 30 damage; nothing is known of the tank's hit.
                        @SuppressWarnings("unchecked")
                        var hits = (Map<Integer, GpuBattleView.Hit>) field(view, "hits");
                        hits.put(1, new GpuBattleView.Hit(new Vector3(30, 0, 0), (float) field(view, "hoverClock")));
                        SwingUtilities.invokeAndWait(() -> {
                            units.get(0).setDoomed(true);
                            units.get(1).setDoomed(true);
                            units.get(2).setInternal(9, ConvInfantry.LOC_INFANTRY);
                            units.get(3).setInternal(9, ConvInfantry.LOC_INFANTRY);
                            units.get(4).setInternal(IArmorState.ARMOR_DESTROYED, 4);
                            units.get(4).setInternal(IArmorState.ARMOR_DESTROYED, 5);
                            fixture.source.refresh();
                        });
                        view.render();
                        var casualties = (MeepleCasualties) field(view, "casualties");
                        var ground = (MeepleDebris.Ground) field(view, "debrisGround");
                        // The two meeples explode; six figures become three: the foot platoon loses three soldiers, the
                        // motorized one a buggy and two riders, the squad two suits.
                        assertEquals(5, casualties.casualties().size());
                        var instances = (Map<?, ?>) field(view, "unitInstances");
                        assertFalse(instances.containsKey("1:-1") || instances.containsKey("2:-1"), "No wreck is drawn");
                        float elapsed = 0;
                        for (float seconds : new float[] { .1f, .25f, .5f, 1, 2 }) {
                            for (; elapsed < seconds; elapsed += FRAME) { casualties.advance(FRAME, ground, 1); }
                            for (var close : List.of(new Coords(5, 5), new Coords(5, 6))) {
                                view.boardCamera.center(BoardGeometry.center(close, 0));
                                view.render();
                                capture(String.format("%.2fs-%d%d", seconds, close.getX(), close.getY()));
                            }
                        }
                        for (; elapsed < MeepleDebris.MAX_SECONDS; elapsed += FRAME) { casualties.advance(FRAME, ground, 1); }
                        view.boardCamera.center(atlas);
                        view.render();
                        capture("settled-iso");
                        assertLanded(casualties, 1, atlas, ground, new Vector3(1, 0, 0));
                        assertLanded(casualties, 2, null, ground, null);
                        view.boardCamera.setIsometric(false);
                        view.render();
                        capture("settled-top");
                        view.boardCamera.setIsometric(true);
                        casualties.advance(MeepleCasualties.LIE_SECONDS + MeepleCasualties.SINK_SECONDS, ground, 1);
                        view.render();
                        assertTrue(casualties.casualties().isEmpty(), "Everything sinks away");
                        capture("gone");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Meeple deaths failed", failure.get()); }
    }

    /**
     * On a bridge deck, under it and in the air: each exploded meeple's pieces come to rest on what lies under them as
     * they see it from the ground they stood on, the deck for the tank on it, the ground for the tank beneath it (never
     * the deck above), the ground around for the VTOL, which they fall to wherever the blast throws them.
     */
    @Test
    void meeplesExplodeOnAndUnderABridgeAndInTheAir() throws Exception {
        var failure = new AtomicReference<Throwable>();
        // Hex 0609: a bridge deck one level above dry ground.
        var bridge = new Coords(5, 8);
        var sky = new Coords(8, 9);
        try (var fixture = GpuBoardFixture.create()) {
            List<Entity> units = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    units.add(add(fixture, unit("Bulldog Medium Tank.blk"), 2, bridge, 1));
                    units.add(add(fixture, unit("Bulldog Medium Tank.blk"), 3, bridge, 0));
                    units.add(add(fixture, unit("SOAR VTOL.blk"), 4, sky, 6));
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.center(BoardGeometry.center(bridge, 0).lerp(BoardGeometry.center(sky, 0), .5f));
                        view.boardCamera.zoom(.25f);
                        view.render();
                        capture("bridge-air-before");
                        // Where each meeple's pieces look for what they land on: just above the ground it stood on.
                        Map<Integer, Float> starts = new HashMap<>();
                        for (int id = 2; id <= 4; id++) {
                            starts.put(id, UnitBounds.world(instance(view, id)).min.z + MeepleDebris.CELL);
                        }
                        assertTrue(starts.get(2) > starts.get(3) + BoardGeometry.level() * .5f, "One tank stands on the deck");
                        assertTrue(starts.get(4) > starts.get(2) + BoardGeometry.level() * 3, "The VTOL flies");
                        var flying = UnitBounds.world(instance(view, 4)).getCenter(new Vector3());
                        SwingUtilities.invokeAndWait(() -> {
                            units.forEach(unit -> unit.setDoomed(true));
                            fixture.source.refresh();
                        });
                        view.render();
                        var casualties = (MeepleCasualties) field(view, "casualties");
                        var ground = (MeepleDebris.Ground) field(view, "debrisGround");
                        assertEquals(3, casualties.casualties().size());
                        float elapsed = 0;
                        for (float seconds : new float[] { .25f, .5f, 1 }) {
                            for (; elapsed < seconds; elapsed += FRAME) { casualties.advance(FRAME, ground, 1); }
                            view.render();
                            capture(String.format("bridge-air-%.2fs", seconds));
                        }
                        for (; elapsed < MeepleDebris.MAX_SECONDS; elapsed += FRAME) { casualties.advance(FRAME, ground, 1); }
                        view.render();
                        capture("bridge-air-settled");
                        for (var casualty : casualties.casualties()) {
                            int id = casualty.unit.id();
                            int onDeck = 0;
                            float spread = 0;
                            var pieces = casualty.instance.nodes.first();
                            for (Node piece : pieces.getChildren()) {
                                var points = vertices(casualty.instance, piece);
                                var center = UnitBounds.subtree(piece).mul(casualty.instance.transform).getCenter(new Vector3());
                                assertRests(points, center, ground, id + " " + piece.id);
                                // A deck covers the piece where the board seen from above stands well over the ground under
                                // the bridges.
                                float above = ground.height(center.x, center.y, Float.POSITIVE_INFINITY, false);
                                float below = ground.height(center.x, center.y, starts.get(3), false);
                                boolean resting = above - below > BoardGeometry.level() * .5f && lowest(points) > above - 5;
                                if (resting) { onDeck++; }
                                assertFalse(id == 3 && resting, piece.id + " of the tank beneath the bridge stays under its deck");
                                spread += Vector3.dst(center.x, center.y, 0, flying.x, flying.y, 0) / pieces.getChildCount();
                            }
                            if (id == 2) { assertTrue(onDeck > 0, "The tank on the deck leaves pieces on it"); }
                            if (id == 4) {
                                assertTrue(spread > BoardGeometry.height() * .5f, "The blast scatters the falling VTOL: " + spread);
                            }
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Meeple deaths on bridges and in the air failed", failure.get()); }
    }

    private static ConvInfantry platoon(EntityMovementMode movement) {
        var infantry = new ConvInfantry();
        infantry.setChassis("Foot Platoon");
        infantry.setModel("Rifle");
        infantry.setMovementMode(movement);
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        return infantry;
    }

    private static Entity add(GpuBoardFixture fixture, Entity entity, int id, Coords position) {
        return add(fixture, entity, id, position, 0);
    }

    private static Entity add(GpuBoardFixture fixture, Entity entity, int id, Coords position, int elevation) {
        entity.setId(id);
        entity.setOwner(fixture.player);
        entity.setPosition(position);
        entity.setElevation(elevation);
        entity.setDeployed(true);
        fixture.game.addEntity(entity, false);
        return entity;
    }

    private static Entity unit(String file) throws Exception {
        return new MekFileParser(new File("testresources/megamek/common/units", file)).getEntity();
    }

    /**
     * The exploded meeple's pieces have come to rest on the ground under them, neither buried nor floating, and within
     * three hexes; with {@code middle}, some flew out of its hex, away from the hit when {@code away} is given.
     */
    private static void assertLanded(MeepleCasualties casualties, int unit, Vector3 middle, MeepleDebris.Ground ground,
          Vector3 away) {
        var casualty = casualties.casualties().stream().filter(candidate -> candidate.unit.id() == unit).findFirst().orElseThrow();
        Node pieces = casualty.instance.nodes.first();
        assertTrue(pieces.getChildCount() >= 4, "A destroyed meeple breaks into several pieces: " + pieces.getChildCount());
        var thrown = new Vector3();
        int outside = 0;
        for (Node piece : pieces.getChildren()) {
            var center = UnitBounds.subtree(piece).mul(casualty.instance.transform).getCenter(new Vector3());
            assertRests(vertices(casualty.instance, piece), center, ground, piece.id);
            if (middle == null) { continue; }
            var offset = center.sub(middle);
            offset.z = 0;
            assertTrue(offset.len() < BoardGeometry.height() * 3, piece.id + " lands near its hex: " + offset.len());
            if (offset.len() > BoardGeometry.height() * .5f) {
                outside++;
                thrown.add(offset.nor());
            }
        }
        if (middle != null) { assertTrue(outside > 0, "Some pieces fly into the hexes around"); }
        if (away != null) { assertTrue(thrown.dot(away) > 0, "Thrown pieces fly away from the hit: " + thrown); }
    }

    /**
     * Touching what is under it, and not sunk into it: its middle stays above the surface there, which the ground gives
     * half a unit up, as units stand on it. The debris samples the board a cell apart, so a step's line is known to a
     * cell: the middle is held to the lowest surface within one.
     */
    private static void assertRests(List<Vector3> points, Vector3 middle, MeepleDebris.Ground ground, String piece) {
        float gap = gap(points, ground);
        assertTrue(gap < 3, piece + " rests on what is under it: " + gap);
        float under = Float.POSITIVE_INFINITY;
        for (float x = -MeepleDebris.CELL; x <= MeepleDebris.CELL; x += MeepleDebris.CELL) {
            for (float y = -MeepleDebris.CELL; y <= MeepleDebris.CELL; y += MeepleDebris.CELL) {
                float height = ground.height(middle.x + x, middle.y + y, middle.z + 1, true);
                if (Float.isFinite(height)) { under = Math.min(under, height); }
            }
        }
        assertTrue(!Float.isFinite(under) || middle.z > under - 1, piece + " is not buried: " + middle + " under " + under
              + ", seen from above " + ground.height(middle.x, middle.y, Float.POSITIVE_INFINITY, false) + ", gap " + gap
              + ", lowest " + points.stream().min(java.util.Comparator.comparingDouble(point -> point.z)).orElseThrow());
    }

    /**
     * The nearest any of the points comes to the surface right under it (negative inside one): near zero for a piece
     * resting on the ground, a deck, a ledge or a rock, also with an edge tucked under a bridge's deck.
     */
    private static float gap(List<Vector3> points, MeepleDebris.Ground ground) {
        float gap = Float.POSITIVE_INFINITY;
        for (Vector3 point : points) {
            float under = ground.height(point.x, point.y, point.z + 1, true);
            if (Float.isFinite(under)) { gap = Math.min(gap, point.z - under); }
        }
        return gap;
    }

    private static float lowest(List<Vector3> points) {
        return (float) points.stream().mapToDouble(point -> point.z).min().orElse(Float.NaN);
    }

    /** The piece's vertices in world space; a broken meeple's meshes are unindexed. */
    private static List<Vector3> vertices(ModelInstance instance, Node piece) {
        List<Vector3> result = new ArrayList<>();
        var world = new Matrix4(instance.transform).mul(piece.globalTransform);
        for (var part : piece.parts) {
            var mesh = part.meshPart.mesh;
            int stride = mesh.getVertexSize() / Float.BYTES;
            int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[part.meshPart.size * stride];
            mesh.getVertices(part.meshPart.offset * stride, vertices.length, vertices);
            for (int at = 0; at < vertices.length; at += stride) {
                result.add(new Vector3(vertices[at + position], vertices[at + position + 1], vertices[at + position + 2]).mul(world));
            }
        }
        return result;
    }

    private static ModelInstance instance(GpuBattleView view, int id) throws Exception {
        return (ModelInstance) ((Map<?, ?>) field(view, "unitInstances")).get(id + ":-1");
    }

    private static void capture(String name) {
        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "meeple-death-" + name + ".png"));
    }
}
