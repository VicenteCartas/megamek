/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Matrix4;
import megamek.common.Hex;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.IArmorState;
import megamek.common.equipment.Mounted;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.SupportTank;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual GLBs and playback checkpoints: partial and final losses, materials, transport blasts and complete removal. */
@Tag("on-demand")
class GpuInfantryDeathSmokeTest {
    private record Example(Entity unit, String weapon) { }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void riflesFallCannonsShatterHeatCarbonizesAndTransportsAlwaysExplode(boolean tactical) throws Exception {
        var failure = new AtomicReference<Throwable>();
        Hex[] hexes = new Hex[12 * 12];
        for (int index = 0; index < hexes.length; index++) { hexes[index] = new Hex(0); }
        try (var fixture = GpuBoardFixture.create(new Board(12, 12, hexes))) {
            List<Example> examples = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    fixture.entity.setPosition(new Coords(1, 5));
                    for (String weapon : List.of("InfantryAutoRifle", "ISAC20", "ISMediumLaser", "InfantryPortableFlamer")) {
                        for (int type = 0; type < 3; type++) {
                            Entity unit;
                            if (type == 2) {
                                unit = new MekFileParser(new File(
                                      "testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk")).getEntity();
                            } else {
                                var infantry = new ConvInfantry();
                                infantry.setChassis("Casualty Review");
                                infantry.setModel("Rifle");
                                infantry.setMovementMode(type == 0 ? EntityMovementMode.INF_LEG : EntityMovementMode.INF_MOTORIZED);
                                infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                                unit = infantry;
                            }
                            int index = examples.size();
                            unit.setId(20 + index);
                            unit.setOwner(fixture.player);
                            unit.setPosition(new Coords(3 + index % 4 * 2, 2 + index / 4 * 3));
                            unit.setDeployed(true);
                            fixture.game.addEntity(unit, false);
                            examples.add(new Example(unit, weapon));
                        }
                    }
                    var cycle = new SupportTank();
                    cycle.setChassis("Minigun Cycle");
                    cycle.setModel("");
                    cycle.setWeight(.35);
                    cycle.setMovementMode(EntityMovementMode.WHEELED);
                    cycle.setId(40);
                    cycle.setOwner(fixture.player);
                    cycle.setPosition(new Coords(5, 10));
                    cycle.setDeployed(true);
                    fixture.game.addEntity(cycle, false);
                    examples.add(new Example(cycle, "InfantryPortableFlamer"));
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var nativeGraphics = Gdx.graphics;
                    var fixedGraphics = spy(nativeGraphics);
                    doReturn(0f).when(fixedGraphics).getDeltaTime();
                    Gdx.graphics = fixedGraphics;
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.setReturnAfterPlayback(false);
                        for (var example : examples) {
                            if (!tactical && !(example.unit() instanceof SupportTank)) { lose(view, fixture, example, false, false); }
                            lose(view, fixture, example, true, tactical);
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        view.dispose();
                        Gdx.graphics = nativeGraphics;
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Infantry casualty playback failed", failure.get()); }
    }

    private static void lose(GpuBattleView view, GpuBoardFixture fixture, Example example, boolean fatal, boolean tactical) throws Exception {
        // Exercise each view's representation, including switching from GLBs to Tactical during their deaths.
        view.boardCamera.setTactical(tactical, (BoardScene) field(view, "scene"));
        view.render();
        var before = (BoardScene) field(view, "scene");
        var target = before.units().stream().filter(unit -> unit.id() == example.unit().getId()).findFirst().orElseThrow();
        var attacker = before.units().stream().filter(unit -> unit.id() == 1).findFirst().orElseThrow();
        var instances = (Map<?, ?>) field(view, "unitInstances");
        String key = target.id() + ":" + target.part();
        var standing = (GpuUnitInstance) instances.get(key);
        assertNotNull(standing, key);
        var rigs = standing.visual().rigs();
        assertTrue(tactical ? standing.visual().meeple() : rigs.stream().anyMatch(UnitRig::meeple));
        var weapon = Mounted.createMounted(fixture.entity, EquipmentType.get(example.weapon()));
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(1, attacker.location().coords(), 2, 0, before.boardId()),
              new UnitLocation(target.id(), target.location().coords(), 0, 0, before.boardId()), Targetable.TYPE_ENTITY,
              500, example.weapon(), 4, true, null, ResolvedAttack.Shot.capture(weapon))
              .withImpacts(List.of(new ResolvedAttack.Impact("*", false, 19)));
        var shot = new BoardScene.Combat(result, attacker, target, target.location(), example.unit() instanceof ConvInfantry);
        if (example.weapon().equals("ISMediumLaser")) { laserImpacts(view, standing, shot, key, tactical); }
        SwingUtilities.invokeAndWait(() -> {
            if (example.unit() instanceof ConvInfantry) {
                example.unit().setInternal(fatal ? 0 : 9, ConvInfantry.LOC_INFANTRY);
            } else if (!(example.unit() instanceof SupportTank)) {
                for (int location = fatal ? 1 : 4; location < example.unit().locations(); location++) {
                    example.unit().setInternal(IArmorState.ARMOR_DESTROYED, location);
                }
            }
            if (fatal) { example.unit().setDoomed(true); }
            fixture.source.refresh();
        });
        var after = fixture.source.takeFrame().scene();
        List<BoardScene.Animation> events = new ArrayList<>(List.of(new BoardScene.SceneUpdate(before), shot));
        if (fatal) { events.add(UnitPlaybackFixture.attack(target, target, ResolvedAttack.Kind.DEATH, true)); }
        events.add(new BoardScene.SceneUpdate(after));
        var playback = (UnitPlayback) field(view, "playback");
        playback.accept(events, after, ignored -> false);
        playback.advance(0, UnitMotion.Speed.NORMAL);
        view.render();
        assertNotNull(playback.attack());
        playback.advance((playback.attack().contactSeconds + .001f) / UnitMotion.Speed.NORMAL.rate, UnitMotion.Speed.NORMAL);
        view.render();
        var casualties = (MeepleCasualties) field(view, "casualties");
        assertEquals(1, casualties.casualties().size(), example.weapon() + ": " + key + " fatal=" + fatal);
        var casualty = casualties.casualties().getFirst();
        var survivor = fatal ? null : (GpuUnitInstance) instances.get(key);
        int vehicles = 0, soldiers = 0;
        if (tactical) {
            if (example.unit() instanceof SupportTank) { vehicles = 1; }
            else { soldiers = 1; }
        } else {
            for (var rig : rigs) {
                if (survivor != null && survivor.getNode(rig.container()) != null) { continue; }
                if (rig.transport() || example.unit() instanceof SupportTank) { vehicles++; }
                else { soldiers++; }
            }
        }
        boolean heavy = example.weapon().equals("ISAC20");
        boolean heat = example.weapon().equals("ISMediumLaser") || example.weapon().equals("InfantryPortableFlamer");
        int pieces = 0, intact = 0;
        Map<Node, Matrix4> carbonized = new HashMap<>();
        for (Node node : casualty.instance.nodes) {
            boolean shattered = node.getChildCount() > 0 && node.getChild(0).id.startsWith(MeepleShatter.PIECE);
            if (shattered) {
                pieces++;
                assertTrue(node.getChildCount() > 1, "The actual mesh was split into multiple pieces");
            } else {
                intact++;
                assertEquals(heat, burnt(node), "Only heat changes the figure's material");
                if (heat) { carbonized.put(node, node.localTransform.cpy()); }
            }
        }
        assertEquals(vehicles + (heavy ? soldiers : 0), pieces, "Transport and heavy-hit fragments");
        assertEquals(heavy ? 0 : soldiers, intact, "Only light hits and heat keep intact figures");
        if (survivor != null) {
            for (Node node : survivor.nodes) { assertFalse(burnt(node), "Survivors do not inherit burnt materials"); }
        } else { assertFalse(instances.containsKey(key), "No second body remains"); }
        // Isolate the death effects from the weapon's legitimate impact sparks/flash.
        var effects = (GpuAttackEffects) field(view, "attackEffects");
        effects.update(playback.attacks().stream().filter(UnitAttack::death).toList(),
              (GpuUnitModels) field(view, "unitModels"), Map.of());
        effects.render(view.boardCamera.camera, null, casualties.casualties());
        assertEquals(vehicles, effects.explosionCount(), "Only transports emit internal fireballs");
        var ground = (MeepleDebris.Ground) field(view, "debrisGround");
        casualties.advance(.3f, ground, 1);
        carbonized.forEach((node, pose) -> assertArrayEquals(pose.val, node.localTransform.val, .0001f,
              "Carbonized figures fade in place, without a fall animation"));
        if (!carbonized.isEmpty()) {
            casualties.advance((float) (UnitMotion.Speed.NORMAL.rate * .98) - .3f, ground, 1);
            assertEquals(1, casualty.opacity(), "Burning holds for one real second at normal playback before fading");
            if (pieces == 0) {
                casualties.advance(.06f, ground, 1);
                assertTrue(casualty.opacity() < 1, "Carbonized figures begin fading after the one-second hold");
            }
        }
        view.boardCamera.center(BoardGeometry.center(target.location().coords(), 0));
        view.boardCamera.camera.zoom = .3f;
        view.boardCamera.setTactical(fatal, (BoardScene) field(view, "scene"));
        view.render();
        if (fatal) {
            var bases = (GpuUnitBases) field(view, "unitBases");
            assertTrue(bases.spec(target).destroyed(), "The tactical death marker appears with the casualty");
            assertEquals(casualty.opacity(), ((GpuUnitShader.Shown) bases.badges(target).userData).opacity,
                  "The cross and infantry fade together");
        }
        GpuReviewFrame.save(new File(System.getProperty("megamek.gpu.screenshots"),
              "infantry-deaths/" + (tactical ? "tactical-" : "") + example.weapon() + "-" + target.id()
                    + (fatal ? "-fatal" : "-partial") + ".png"));
        if (tactical && !heat && !heavy && soldiers > 0) {
            casualties.advance(MeepleCasualties.FALL_SECONDS, ground, 1);
            assertEquals(0, GpuMeepleDeathSmokeTest.gap(GpuMeepleDeathSmokeTest.vertices(casualty.instance,
                  casualty.instance.nodes.first()), ground), .25f, "A fallen Tactical infantry token rests on its real surface");
        }
        playback.finish();
        casualties.advance(MeepleCasualties.DEBRIS_SECONDS + UnitAttack.FADE_SECONDS, ground, 1);
        view.render();
        assertTrue(casualties.casualties().isEmpty(), "All figures and fragments fade away");
    }

    /** Actual mesh contacts, retained after removal: soldier bodies suppress the laser puff, vehicles and BA keep it. */
    private static void laserImpacts(GpuBattleView view, GpuUnitInstance standing, BoardScene.Combat shot,
          String key, boolean tactical) throws Exception {
        var effects = (GpuAttackEffects) field(view, "attackEffects");
        for (Node node : standing.nodes) {
            if (!MeepleVisual.ROOT.equals(node.id) && standing.visual().rigs().stream()
                  .noneMatch(rig -> node.id.equals(rig.container()))) { continue; }
            var victim = new GpuUnitInstance(standing.visual());
            victim.nodes.clear();
            victim.nodes.add(node.copy());
            victim.transform.set(standing.transform);
            victim.calculateTransforms();
            var laser = new UnitAttack(shot);
            laser.seconds = UnitAttack.ANTICIPATION_SECONDS;
            effects.update(laser, null, Map.of(key, victim));
            effects.render(view.boardCamera.camera);
            // The final blow removes the original instance before its impact flash is rendered.
            victim.nodes.clear();
            laser.seconds = laser.contactSeconds + .1f;
            effects.update(laser, null, Map.of());
            effects.render(view.boardCamera.camera);
            boolean soldier = shot.conventionalInfantryTarget() && (tactical || !node.id.startsWith("vehicle-"));
            assertEquals(soldier ? 0 : 1, effects.explosionCount(), "Laser impact on " + node.id);
        }
    }

    private static boolean burnt(Node node) {
        boolean burnt = false;
        for (var part : node.parts) {
            if (!part.enabled || !part.material.id.endsWith(UnitDamageDisplay.WRECKED_SUFFIX)) { continue; }
            assertTrue(part.material.has(UnitDamageDisplay.Overlay.TYPE), "The shared destroyed-armor texture is installed");
            burnt = true;
        }
        for (Node child : node.getChildren()) { burnt |= burnt(child); }
        return burnt;
    }
}
