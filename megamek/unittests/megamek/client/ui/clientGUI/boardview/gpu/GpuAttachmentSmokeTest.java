/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Coords;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.QuadMek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuAttachmentSmokeTest {
    @Test
    void exteriorSuitsFollowPosedMeksAndVehiclesAndReachTheirResolvedDestination() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var batch = new ModelBatch(GpuUnitShader.provider());
                try { verify(library, batch); }
                catch (Throwable error) { failure.set(error); }
                finally { batch.dispose(); library.dispose(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Exterior attachment review failed", failure.get()); }
    }

    private static void verify(GpuUnitModels library, ModelBatch batch) throws Exception {
        var tileset = new MekTileset(Configuration.unitImagesDir());
        tileset.loadFromFile("mekset.txt");
        var armor = armor(9902);
        var friendlyArmor = armor(9904);
        var camera = new OrthographicCamera(200, 150);
        camera.position.set(150, 200, 100);
        camera.up.set(Vector3.Z);
        camera.lookAt(0, 0, 20);
        camera.update();
        for (Entity carrier : List.of(new BipedMek(), new Tank(), new QuadMek())) {
            carrier.setId(carrier instanceof Tank ? 9903 : carrier instanceof QuadMek ? 9905 : 9901);
            carrier.setWeight(80);
            if (carrier instanceof Tank) { carrier.setMovementMode(EntityMovementMode.TRACKED); }
            for (int loc = 0; loc < carrier.locations(); loc++) { carrier.initializeInternal(10, loc); }
            var hostUnit = unit(carrier, tileset, new Coords(0, 0));
            var passenger = unit(armor, tileset, new Coords(0, 0)).withAttachment(new BoardScene.Attachment(carrier.getId(), true));
            var landed = unit(armor, tileset, new Coords(0, 1));
            var hostModel = library.get(hostUnit.model(), carrier.getId());
            var baModel = library.get(passenger.model(), armor.getId());
            assertNotNull(hostModel);
            assertEquals(6, baModel.rigs().size());
            verifyReleaseAfterArrival(library, camera, hostUnit, passenger, landed);
            var host = new ModelInstance(hostModel.instance.model);
            var ba = new ModelInstance(baModel.instance.model);
            var hostAnimator = new UnitAnimator();
            var baAnimator = new UnitAnimator();
            String hostKey = carrier.getId() + ":-1", baKey = armor.getId() + ":-1";
            Map<String, ModelInstance> instances = Map.of(hostKey, host, baKey, ba);
            Map<String, UnitAnimator> animators = Map.of(hostKey, hostAnimator, baKey, baAnimator);
            var controller = new UnitAttachments();
            var attachedScene = scene(hostUnit, passenger);
            hostAnimator.apply(hostModel, host, hostUnit, UnitMotion.Sample.STILL, 0, 0, true, 0);
            hostModel.place(host, camera, Vector3.Zero, 0, hostUnit);
            reset(baAnimator, baModel, ba, passenger, camera, Vector3.Zero, 0);
            controller.place(attachedScene, library, instances, animators, null, camera, new HashMap<>(), 0);
            var initial = world(ba, "trooper-1");
            host.transform.trn(12, 8, 9).rotate(Vector3.Z, 25);
            reset(baAnimator, baModel, ba, passenger, camera, Vector3.Zero, 0);
            controller.place(attachedScene, library, instances, animators, null, camera, new HashMap<>(), 0);
            assertTrue(world(ba, "trooper-1").getTranslation(new Vector3()).dst(initial.getTranslation(new Vector3())) > 5);
            String name = carrier instanceof Tank ? "vehicle" : carrier instanceof QuadMek ? "quad" : "mek";
            verifySharedCarrier(library, batch, camera, hostUnit,
                  unit(friendlyArmor, tileset, new Coords(0, 0)).withAttachment(new BoardScene.Attachment(carrier.getId(), false)),
                  passenger, landed, name);
            GpuModularUnitModelsSmokeTest.renderFullReview(batch, List.of(host, ba), "exterior-" + name,
                  "Six swarming suits / " + name, 120, 20);
            var release = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, passenger, landed, hostUnit, BoardScene.Release.THROWN));
            var releasedScene = scene(hostUnit, landed);
            Matrix4 releasePose = world(ba, "trooper-1");
            Vector3 previous = releasePose.getTranslation(new Vector3());
            for (int frame = 0; frame <= 120; frame++) {
                release.seconds = UnitAttachmentMotion.DURATION * frame / 120;
                reset(baAnimator, baModel, ba, landed, camera, new Vector3(0, 65, 0), frame / 60f);
                Matrix4 endpoint = world(ba, "trooper-1");
                controller.place(releasedScene, library, instances, animators, release, camera, new HashMap<>(), frame / 60f);
                Matrix4 posed = world(ba, "trooper-1");
                if (frame == 0) { assertArrayEquals(releasePose.val, posed.val, .002f, "Release starts at the last attached pose"); }
                if (frame == 120) { assertArrayEquals(endpoint.val, posed.val, .002f, "Final normal formation is the exact endpoint"); }
                var position = posed.getTranslation(new Vector3());
                assertTrue(position.dst(previous) < 5, "No teleport during release: " + frame);
                previous = position;
                if (frame % 30 == 0 && carrier instanceof BipedMek) {
                    GpuModularUnitModelsSmokeTest.renderReview(batch, List.of(host, ba), "exterior-fall-" + frame, 220, 25);
                }
            }
            var board = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, landed, passenger, hostUnit, BoardScene.Release.BOARD));
            Matrix4 groundStart = world(ba, "trooper-1");
            previous = groundStart.getTranslation(new Vector3());
            for (int frame = 0; frame <= 120; frame++) {
                board.seconds = UnitAttachmentMotion.DURATION * frame / 120;
                reset(baAnimator, baModel, ba, passenger, camera, Vector3.Zero, frame / 60f);
                controller.place(attachedScene, library, instances, animators, board, camera, new HashMap<>(), frame / 60f);
                Matrix4 posed = world(ba, "trooper-1");
                if (frame == 0) { assertArrayEquals(groundStart.val, posed.val, .002f); }
                var position = posed.getTranslation(new Vector3());
                assertTrue(position.dst(previous) < 5, "No teleport while boarding: " + frame);
                previous = position;
            }
            if (carrier instanceof BipedMek) {
                String torso = hostModel.rigs().getFirst().joints().get("torso");
                Matrix4 relative = world(host, torso).inv().mul(world(ba, "trooper-3"));
                var failed = new UnitAttack(UnitPlaybackFixture.attack(hostUnit, passenger, megamek.common.ResolvedAttack.Kind.BRUSH_OFF, false));
                failed.seconds = .35f;
                hostAnimator.attack(hostModel, hostUnit, failed);
                reset(baAnimator, baModel, ba, passenger, camera, Vector3.Zero, 2);
                controller.place(attachedScene, library, instances, animators, null, camera, new HashMap<>(), 2);
                assertArrayEquals(relative.val, world(host, torso).inv().mul(world(ba, "trooper-3")).val, .002f,
                      "A failed brush keeps the suit rigidly attached to the animated torso");
                var water = new BoardScene.Tile(new Coords(0, 0), 0, 2, false, 0, BoardScene.Surface.GRASS, null,
                      List.of(), List.of());
                var waterScene = new BoardScene(0, 1, 1, List.of(water), List.of(hostUnit, passenger), List.of(), -1, "MOVEMENT", List.of());
                var center = BoardGeometry.center(new Coords(0, 0), -2);
                hostModel.place(host, camera, center, 0, hostUnit);
                reset(baAnimator, baModel, ba, passenger, camera, center, 0);
                controller.clear();
                controller.place(waterScene, library, instances, animators, null, camera, new HashMap<>(), 0);
                var washed = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, passenger, null, hostUnit,
                      BoardScene.Release.WATER, new BoardScene.Waypoint(new Coords(0, 0), -2, 0)));
                var splashes = new GpuWaterImpacts();
                int entries = 0;
                try {
                    for (int frame = 0; frame <= 100; frame++) {
                        washed.seconds = UnitAttachmentMotion.DURATION * frame / 100;
                        reset(baAnimator, baModel, ba, landed, camera, center, frame / 60f);
                        controller.place(waterScene, library, instances, animators, washed, camera, new HashMap<>(), frame / 60f);
                        splashes.render(camera, waterScene, Map.of(), instances, Color.WHITE, washed);
                        entries = Math.max(entries, splashes.rippleCount());
                    }
                    assertTrue(entries > 0, "Falling suits emit water-entry spray and foam on the shared clock");
                } finally { splashes.dispose(); }
            }
        }
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    private static void verifyReleaseAfterArrival(GpuUnitModels library, OrthographicCamera camera,
          BoardScene.Unit carrier, BoardScene.Unit passenger, BoardScene.Unit landed) {
        var hostModel = library.get(carrier.model(), carrier.id());
        var suitModel = library.get(passenger.model(), passenger.id());
        var host = new ModelInstance(hostModel.instance.model);
        var suits = new ModelInstance(suitModel.instance.model);
        var hostAnimator = new UnitAnimator();
        var suitAnimator = new UnitAnimator();
        var instances = Map.of(carrier.id() + ":-1", host, passenger.id() + ":-1", suits);
        var animators = Map.of(carrier.id() + ":-1", hostAnimator, passenger.id() + ":-1", suitAnimator);
        for (var kind : List.of(BoardScene.Release.CLIMB_DOWN, BoardScene.Release.THROWN, BoardScene.Release.JUMP)) {
            var attachments = new UnitAttachments();
            reset(hostAnimator, hostModel, host, carrier, camera, Vector3.Zero, 0);
            reset(suitAnimator, suitModel, suits, passenger, camera, Vector3.Zero, 0);
            attachments.place(scene(carrier, passenger), library, instances, animators, null, camera, new HashMap<>(), 0);
            var relative = host.transform.cpy().inv().mul(world(suits, "trooper-1"));
            // Playback may finish travel and start release in one tick, without drawing the arrival attachment.
            host.transform.trn(0, BoardGeometry.height(), 0).rotate(Vector3.Z, 30);
            var expected = host.transform.cpy().mul(relative);
            reset(suitAnimator, suitModel, suits, landed, camera, new Vector3(0, BoardGeometry.height(), 0), 1);
            var release = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, passenger, landed, carrier, kind));
            attachments.place(scene(carrier, landed), library, instances, animators, release, camera, new HashMap<>(), 1);
            assertArrayEquals(expected.val, world(suits, "trooper-1").val, .002f,
                  "Release must start on the arrived carrier, never fly from an earlier frame: " + kind);
        }
    }

    private static void verifySharedCarrier(GpuUnitModels library, ModelBatch batch, OrthographicCamera camera,
          BoardScene.Unit carrier, BoardScene.Unit rider, BoardScene.Unit swarmer, BoardScene.Unit landed, String name) throws Exception {
        var hostModel = library.get(carrier.model(), carrier.id());
        var riderModel = library.get(rider.model(), rider.id());
        var swarmModel = library.get(swarmer.model(), swarmer.id());
        var host = new ModelInstance(hostModel.instance.model);
        var friendly = new ModelInstance(riderModel.instance.model);
        var enemy = new ModelInstance(swarmModel.instance.model);
        friendly.materials.forEach(material -> material.set(ColorAttribute.createDiffuse(
              GpuLinearColors.authored(material, Color.SKY))));
        enemy.materials.forEach(material -> material.set(ColorAttribute.createDiffuse(
              GpuLinearColors.authored(material, Color.SCARLET))));
        var hostAnimator = new UnitAnimator();
        var riderAnimator = new UnitAnimator();
        var swarmAnimator = new UnitAnimator();
        var instances = Map.of(carrier.id() + ":-1", host, rider.id() + ":-1", friendly, swarmer.id() + ":-1", enemy);
        var animators = Map.of(carrier.id() + ":-1", hostAnimator, rider.id() + ":-1", riderAnimator, swarmer.id() + ":-1", swarmAnimator);
        var controller = new UnitAttachments();
        reset(hostAnimator, hostModel, host, carrier, camera, Vector3.Zero, 0);
        reset(riderAnimator, riderModel, friendly, rider, camera, Vector3.Zero, 0);
        controller.place(scene(carrier, rider), library, instances, animators, null, camera, new HashMap<>(), 0);
        var riderPositions = positions(friendly);
        reset(swarmAnimator, swarmModel, enemy, swarmer, camera, Vector3.Zero, 0);
        controller.place(scene(carrier, swarmer), library, instances, animators, null, camera, new HashMap<>(), 0);
        var swarmPositions = positions(enemy);
        var together = scene(carrier, rider, swarmer);
        for (int frame = 0; frame <= 60; frame++) {
            float clock = frame / 30f;
            reset(riderAnimator, riderModel, friendly, rider, camera, Vector3.Zero, clock);
            reset(swarmAnimator, swarmModel, enemy, swarmer, camera, Vector3.Zero, clock);
            controller.place(together, library, instances, animators, null, camera, new HashMap<>(), clock);
            assertPositions(riderPositions, friendly, "A swarm must not relocate friendly riders");
            assertPositions(swarmPositions, enemy, "Friendly riders must not relocate swarmers");
            if (frame == 0) {
                GpuModularUnitModelsSmokeTest.renderFullReview(batch, List.of(host, friendly, enemy), "exterior-shared-" + name,
                      "Blue riders + red swarmers / " + name, 90, 15);
            }
            for (int first = 1; first <= 6; first++) {
                var friendlyBounds = UnitBounds.subtree(friendly.getNode("trooper-" + first)).mul(friendly.transform);
                for (int second = 1; second <= 6; second++) {
                    var enemyBounds = UnitBounds.subtree(enemy.getNode("trooper-" + second)).mul(enemy.transform);
                    assertFalse(friendlyBounds.intersects(enemyBounds), name + " squads overlap: rider " + first + ", swarmer " + second
                          + ", frame " + frame + ": " + friendlyBounds + " / " + enemyBounds);
                    if (second > first) {
                        var otherSwarmer = UnitBounds.subtree(enemy.getNode("trooper-" + first)).mul(enemy.transform);
                        assertFalse(otherSwarmer.intersects(enemyBounds), name + " swarmers overlap: " + first + " / " + second
                              + ", frame " + frame + ": " + otherSwarmer + " / " + enemyBounds);
                    }
                }
            }
        }
        var release = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, swarmer, landed, carrier, BoardScene.Release.THROWN));
        var departure = scene(carrier, rider, landed);
        for (int frame = 0; frame <= 120; frame++) {
            release.seconds = UnitAttachmentMotion.DURATION * frame / 120;
            reset(riderAnimator, riderModel, friendly, rider, camera, Vector3.Zero, frame / 60f);
            reset(swarmAnimator, swarmModel, enemy, landed, camera, new Vector3(0, 65, 0), frame / 60f);
            var endpoint = positions(enemy);
            controller.place(departure, library, instances, animators, release, camera, new HashMap<>(), frame / 60f);
            assertPositions(riderPositions, friendly, "Releasing the swarm must not relocate friendly riders");
            if (frame == 0) { assertPositions(swarmPositions, enemy, "Departure starts at the occupied hostile sockets"); }
            if (frame == 120) { assertPositions(endpoint, enemy, "Departure ends at the resolved formation"); }
        }
        var boarding = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, landed, swarmer, carrier, BoardScene.Release.BOARD));
        var groundStart = positions(enemy);
        for (int frame = 0; frame <= 120; frame++) {
            boarding.seconds = UnitAttachmentMotion.DURATION * frame / 120;
            reset(riderAnimator, riderModel, friendly, rider, camera, Vector3.Zero, frame / 60f);
            reset(swarmAnimator, swarmModel, enemy, swarmer, camera, Vector3.Zero, frame / 60f);
            controller.place(together, library, instances, animators, boarding, camera, new HashMap<>(), frame / 60f);
            assertPositions(riderPositions, friendly, "Boarding swarmers must not relocate friendly riders");
            if (frame == 0) { assertPositions(groundStart, enemy, "Boarding starts at the ground formation"); }
            if (frame == 120) { assertPositions(swarmPositions, enemy, "Boarding ends at the hostile sockets"); }
        }
    }

    private static Map<String, Matrix4> positions(ModelInstance instance) {
        Map<String, Matrix4> result = new HashMap<>();
        for (int member = 1; member <= 6; member++) {
            String node = "trooper-" + member;
            result.put(node, world(instance, node));
        }
        return result;
    }

    private static void assertPositions(Map<String, Matrix4> expected, ModelInstance instance, String message) {
        expected.forEach((node, transform) -> assertArrayEquals(transform.val, world(instance, node).val, .002f, message + ": " + node));
    }

    private static BattleArmor armor(int id) {
        var armor = new BattleArmor();
        armor.setId(id);
        armor.setSquadSize(6);
        for (int member = 1; member <= 6; member++) { armor.initializeInternal(1, member); }
        return armor;
    }

    private static BoardScene scene(BoardScene.Unit... units) {
        var tiles = List.of(new Coords(0, 0), new Coords(0, 1)).stream().map(coords -> new BoardScene.Tile(coords, 0, -1,
              false, 0, BoardScene.Surface.GRASS, null, List.of(), List.of())).toList();
        return new BoardScene(0, 1, 2, tiles, List.of(units), List.of(), -1, "MOVEMENT", List.of());
    }

    private static void reset(UnitAnimator animator, GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit,
          OrthographicCamera camera, Vector3 position, float clock) {
        animator.apply(model, instance, unit, UnitMotion.Sample.STILL, clock, 0, true, 0);
        model.place(instance, camera, position, 0, unit);
    }

    private static Matrix4 world(ModelInstance model, String node) { return model.transform.cpy().mul(model.getNode(node).globalTransform); }

    private static BoardScene.Unit unit(Entity entity, MekTileset tileset, Coords coords) {
        return new BoardScene.Unit(entity.getId(), -1, "Attachment review", new BoardScene.Waypoint(coords, 0, 0), null,
              false, null, entity.height() + 1, false, UnitModelSelection.capture(entity, -1, false, tileset), 0xFFFFFFFF);
    }
}
