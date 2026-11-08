/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.rules.core.CoreRulesManager;
import megamek.common.rules.totalwarfare.TWRulesManager;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityMovementType;
import megamek.common.units.FallSide;
import megamek.common.units.ProneCause;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native checks on real artwork, posed attachment surfaces, shared playback, and every Atlas weapon. */
@Tag("on-demand")
class GpuMeepleAnimationSmokeTest {
    @Test
    void meeplesSharePlaybackEffectsAndAttachmentsWithoutArticulatedJoints() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            var original = fixture.source.takeFrame().scene().units().getFirst();
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setCamouflage(new megamek.common.icons.Camouflage("Word of Blake/", "TerraSec (Camo).png"));
                fixture.view.getTilesetManager().reloadImage(fixture.entity);
                fixture.source.refresh();
            });
            var unit = fixture.source.takeFrame().scene().units().getFirst();
            assertFalse(original.image().equals(unit.image()), "The token's top receives the selected camouflage");
            SwingUtilities.invokeAndWait(() -> {
                for (int location = 0; location < fixture.entity.locations(); location++) { fixture.entity.setArmor(0, location); }
                fixture.view.getTilesetManager().reloadImage(fixture.entity);
                fixture.source.refresh();
            });
            var damaged = fixture.source.takeFrame().scene().units().getFirst();
            assertTrue(damaged.model().state().appearance().bodyStage() != null, "Lost armor shows on the walls");
            assertEquals(unit.image(), damaged.image(), "Damage changes wall appearance but never bakes scars into the top artwork");
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    var textures = new GpuTextures<BoardScene.Pixels>();
                    textures.update(Map.of(unit.image(), unit.image()));
                    var model = GpuUnitModel.meeple(unit.image(), textures.region(unit.image()));
                    for (var mesh : model.instance.model.meshes) {
                        boolean owned = false;
                        for (var resource : model.instance.model.getManagedDisposables()) { owned |= resource == mesh; }
                        assertTrue(owned, "Token disposal owns both cap and wall buffers");
                    }
                    var library = new GpuUnitModels();
                    var damage = new UnitDamageDisplay();
                    var camo = new GpuUnitCamouflage();
                    var effects = new GpuAttackEffects();
                    var jets = new GpuJumpJets();
                    try (var renderer = new GpuPlaybackReview.ReviewRenderer()) {
                        var instance = new GpuUnitInstance(model);
                        verifyPostures(model, instance, unit, renderer);
                        verifyFormsAndSupports(model, instance, unit, renderer);
                        verifyDamage(model, unit, renderer, camo, damage);
                        verifyJump(model, instance, unit, renderer, jets);
                        verifyAttachments(model, instance, unit, renderer, library);
                        library.setDisplayMode(UnitDisplayMode.MEK_MEEPLES, false);
                        verifyCombat(model, instance, unit, renderer, library, effects, fixture);
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        jets.dispose(); effects.dispose(); camo.dispose(); damage.dispose();
                        library.dispose(); model.dispose(); textures.dispose(); Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Meeple animation review failed", failure.get()); }
    }

    private static void verifyPostures(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit,
          GpuPlaybackReview.ReviewRenderer renderer) {
        var ground = BoardGeometry.center(unit.location().coords(), 0);
        MeepleAnimator.place(model, instance, unit, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
        float standing = UnitBounds.world(instance).getDepth(), volume = instance.transform.det();
        var voluntary = unit.at(unit.location().withProneCause(ProneCause.VOLUNTARY));
        MeepleAnimator.place(model, instance, voluntary, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
        assertEquals(standing / 2, UnitBounds.world(instance).getDepth(), .001f);
        assertEquals(1, new Vector3(Vector3.Z).rot(instance.transform).nor().z, .001f);
        renderer.frame(List.of(instance), ground, null, "meeple-voluntary", 0);
        var shortened = instance.transform.cpy();
        MeepleAnimator.place(model, instance, unit.at(unit.location().withHullDown(true)), UnitMotion.Sample.STILL, renderer.camera, ground, 0);
        assertArrayEquals(shortened.val, instance.transform.val, .001f, "Hull-down shares the voluntary-prone pose");
        var rules = Game.rulesManager;
        try {
            for (var system : List.of(new TWRulesManager(), new CoreRulesManager())) {
                Game.rulesManager = system;
                verifyFalls(model, instance, unit, renderer, ground, standing, volume, system instanceof TWRulesManager);
            }
        } finally { Game.rulesManager = rules; }
        var motion = new UnitMotion(unit.location());
        motion.append(List.of(unit.location(), new BoardScene.Waypoint(unit.location().coords().translated(0), 0, 0)),
              EntityMovementType.MOVE_RUN, 0);
        boolean bounced = false;
        while (motion.isMoving()) {
            motion.advance(.035, 1);
            MeepleAnimator.place(model, instance, unit, motion.sample(), renderer.camera, motion.position(), motion.facing());
            bounced |= UnitBounds.world(instance).min.z > 1;
            var paused = instance.transform.cpy();
            MeepleAnimator.place(model, instance, unit, motion.sample(), renderer.camera, motion.position(), motion.facing());
            assertArrayEquals(paused.val, instance.transform.val, .0001f, "Paused movement has no separate animation clock");
        }
        assertTrue(bounced);
        assertEquals(0, MeepleAnimator.bounce(new UnitMotion.Sample(true, EntityMovementType.MOVE_NONE,
              .5f, .2f, 0, 1, 0, 0, ProneCause.NONE)), "A displacement slides instead of walking");
    }

    /**
     * Total Warfare turns a fallen unit's facing to where it fell: the token's top points along it. Core keeps the
     * facing: the top comes down on the side the unit fell on.
     */
    private static void verifyFalls(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit,
          GpuPlaybackReview.ReviewRenderer renderer, Vector3 ground, float standing, float volume, boolean totalWarfare) {
        for (var side : FallSide.values()) {
            float fell = totalWarfare ? 0 : switch (side) {
                case FRONT -> 0;
                case RIGHT -> 90;
                case REAR -> 180;
                case LEFT -> -90;
            };
            for (int facing = 0; facing < 6; facing++) {
                var point = new BoardScene.Waypoint(unit.location().coords(), 0, facing, ProneCause.FORCED).withFallSide(side);
                MeepleAnimator.place(model, instance, unit.at(point), UnitMotion.Sample.STILL, renderer.camera, ground, facing * 60);
                assertEquals(volume, instance.transform.det(), .001f, "A fall rotates the entire solid without squashing it");
                var upper = new Vector3(Vector3.Z).rot(instance.transform).nor();
                var heading = new Vector3(Vector3.Y).rotate(Vector3.Z, -facing * 60);
                assertEquals(1, upper.dot(new Vector3(Vector3.Y).rotate(Vector3.Z, -facing * 60 - fell)), .001f,
                      "Upper end points where the unit fell: " + side + (totalWarfare ? ", Total Warfare" : ", Core"));
                assertEquals(.5f, UnitBounds.world(instance).min.z, .001f);
                if (facing == 0) {
                    renderer.frame(List.of(instance), ground, null, "meeple-fall-" + side + (totalWarfare ? "-tw" : "-core"), 0);
                }
                var toppled = instance.transform.cpy();
                MeepleAnimator.place(model, instance, unit.at(point), UnitMotion.Sample.STILL, renderer.camera, ground, facing * 60, null, true);
                assertEquals(1, new Vector3(Vector3.Z).rot(instance.transform).nor().z, .001f,
                      "Top view presents a readable upright token for either fall outcome");
                assertEquals(1, new Vector3(Vector3.Y).rot(instance.transform).nor().dot(heading), .001f,
                      "Top-view artwork follows the resolved facing, without the face-up pose's extra half turn");
                assertEquals(standing / 2, UnitBounds.world(instance).getDepth(), .001f, "Prone weapons retain their lowered origin");
                renderer.topView = true;
                renderer.frame(List.of(instance), ground, null, "meeple-prone-top-" + side, facing);
                renderer.topView = false;
                MeepleAnimator.place(model, instance, unit.at(point), UnitMotion.Sample.STILL, renderer.camera, ground, facing * 60);
                assertArrayEquals(toppled.val, instance.transform.val, .001f, "Switching back restores the same toppled pose");
            }
        }
    }

    private static BoardScene.Unit form(BoardScene.Unit unit, EntityMovementMode mode, int conversion, boolean airborne) {
        var original = unit.model();
        var state = original.state();
        var structure = state.structure();
        var form = new UnitLocation.Form(conversion, mode, "", airborne, airborne, airborne ? 1 : 0);
        var model = new BoardScene.UnitModel(original.asset(), original.fallback(), original.variant(), original.figures(), 0,
              original.damage(), new UnitModelState(new UnitModelState.Structure(mode, structure.equipment(), structure.members(),
                    structure.activeTroopers(), structure.externalSearchlight(), structure.anatomy(), structure.bodyForm()),
                    state.appearance(), new UnitModelState.Pose(ProneCause.NONE, 0, 0, form)));
        return new BoardScene.Unit(unit.id(), unit.part(), unit.name(), unit.location(), unit.image(), false, null, unit.height(),
              airborne, model, unit.outlineRgb(), unit.footprint());
    }

    private static void verifyFormsAndSupports(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit,
          GpuPlaybackReview.ReviewRenderer renderer) {
        var ground = BoardGeometry.center(unit.location().coords(), 0);
        MeepleAnimator.place(model, instance, unit, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
        float standingHeight = UnitBounds.world(instance).getDepth();
        var moving = new UnitMotion.Sample(true, EntityMovementType.MOVE_RUN, .5f, .125f, 10, 1, 0, 0, ProneCause.NONE);
        var vehicle = form(unit, EntityMovementMode.TRACKED, 1, false);
        var fighter = form(unit, EntityMovementMode.AERODYNE, 2, true);
        var airMek = form(unit, EntityMovementMode.WIGE, 1, true);
        for (var converted : List.of(vehicle, fighter, airMek)) {
            MeepleAnimator.place(model, instance, converted, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
            assertEquals(standingHeight / 2, UnitBounds.world(instance).getDepth(), .001f);
            MeepleAnimator.place(model, instance, converted, moving, renderer.camera, ground, 0);
            assertEquals(.5f, UnitBounds.world(instance).min.z, .001f, "Vehicles and aircraft do not step-bounce");
            float bank = Math.abs(new Vector3(Vector3.Z).rot(instance.transform).nor().x);
            assertTrue(bank > (converted.airborne() ? .1f : .005f));
            assertTrue(bank < (converted.airborne() ? .3f : .04f));
        }
        var conversion = new UnitConversion(new BoardScene.Conversion(0, unit, fighter));
        for (float t : new float[] { 0, .499f, .501f, 1 }) {
            conversion.seconds = UnitConversion.DURATION_SECONDS * t;
            MeepleAnimator.place(model, instance, conversion.displayed(), UnitMotion.Sample.STILL, renderer.camera, ground, 0, conversion, false);
            float height = new Vector3(Vector3.Z).rot(instance.transform).len() * MeepleVisual.HEIGHT;
            assertEquals((2 - conversion.progress()) * standingHeight / 2, height, .001f);
            renderer.frame(List.of(instance), ground, null, "meeple-conversion", (int) (t * 1000));
        }
        var occupied = List.of(unit.location().coords(), unit.location().coords().translated(0), unit.location().coords().translated(3));
        var large = new BoardScene.Unit(unit.id(), -1, unit.name(), unit.location(), unit.image(), false, null, 4, false,
              unit.model(), 0, occupied);
        MeepleAnimator.place(model, instance, unit, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
        float width = UnitBounds.world(instance).getWidth();
        MeepleAnimator.place(model, instance, large, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
        assertTrue(UnitBounds.world(instance).getWidth() > width, "One large token fits the occupied footprint");

        // The captured support plane also covers roofs/bridges; artwork must never move it up to clear scenery.
        for (float elevation : new float[] { 0, 2, 5 }) {
            var plane = BoardGeometry.center(unit.location().coords(), elevation);
            for (var cause : List.of(ProneCause.NONE, ProneCause.VOLUNTARY, ProneCause.FORCED)) {
                var posed = unit.at(new BoardScene.Waypoint(unit.location().coords(), elevation, 0).withProneCause(cause));
                MeepleAnimator.place(model, instance, posed, UnitMotion.Sample.STILL, renderer.camera, plane, 0);
                assertEquals(plane.z + .5f, UnitBounds.world(instance).min.z, .001f,
                      "The token rests on its flat support plane, including when toppled");
                if (cause != ProneCause.FORCED) {
                    assertEquals(1, new Vector3(Vector3.Z).rot(instance.transform).nor().z, .001f);
                }
            }
        }
    }

    private static void verifyDamage(GpuUnitModel model, BoardScene.Unit unit, GpuPlaybackReview.ReviewRenderer renderer,
          GpuUnitCamouflage camo, UnitDamageDisplay damage) {
        var textures = new HashSet<Object>();
        for (float loss : new float[] { 0, .25f, .5f, .75f, 1, 0 }) {
            var instance = new GpuUnitInstance(model);
            MeepleVisual.appearance(instance, model, unit.model().state().appearance(), loss, unit.id(), camo, damage);
            for (var part : instance.getNode(MeepleVisual.ROOT).parts) {
                var overlay = part.material.get(UnitDamageDisplay.Overlay.class, UnitDamageDisplay.Overlay.TYPE);
                if ("paint".equals(part.material.id)) {
                    assertNotNull(part.material.get(GpuUnitCamouflage.Paint.TYPE));
                    if (loss == 0) { assertNull(overlay, "Repair removes the overlay"); }
                    else { assertNotNull(overlay); assertEquals(.5f, overlay.opacity); textures.add(overlay.texture); }
                } else { assertNull(overlay, "Identification artwork never receives damage"); }
            }
            assertFalse(model.instance.getNode(MeepleVisual.ROOT).parts.get(1).material.has(UnitDamageDisplay.Overlay.TYPE));
            var ground = BoardGeometry.center(unit.location().coords(), 0);
            MeepleAnimator.place(model, instance, unit, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
            renderer.frame(List.of(instance), ground, null, "meeple-damage", (int) (loss * 100));
        }
        assertEquals(4, textures.size(), "Each damage band uses its own existing overlay");
    }

    private static void verifyJump(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit,
          GpuPlaybackReview.ReviewRenderer renderer, GpuJumpJets jets) {
        var motion = new UnitMotion(unit.location());
        motion.append(List.of(unit.location(), new BoardScene.Waypoint(unit.location().coords().translated(0, 2), 0, 0)),
              EntityMovementType.MOVE_JUMP, 3);
        double step = motion.remainingSeconds() / 48;
        boolean smoke = false;
        for (int frame = 0; frame <= 48; frame++) {
            if (frame > 0) { motion.advance(step, 1); }
            MeepleAnimator.place(model, instance, unit, motion.sample(), renderer.camera, motion.position(), motion.facing());
            var nozzle = MeepleVisual.nozzle(instance, 1);
            var localNozzle = nozzle.cpy().mul(instance.transform.cpy().inv());
            var localBounds = UnitBounds.local(instance);
            assertEquals(localBounds.min.z, localNozzle.z, .001f, "Nozzle stays on the token's underside during its jump tilt");
            var turned = instance.transform.cpy().rotate(Vector3.Z, 60);
            instance.transform.set(turned);
            assertEquals(0, MeepleVisual.nozzle(instance, 1).dst(localNozzle.mul(turned)), .001f);
            MeepleAnimator.place(model, instance, unit, motion.sample(), renderer.camera, motion.position(), motion.facing());
            jets.beginFrame(); jets.update("meeple", model, instance, unit, motion.sample()); jets.endFrame();
            assertEquals(frame == 48 ? 0 : 2, jets.emitterCount());
            smoke |= jets.smokeCount() > 0;
            if (frame % 12 == 0) { renderer.frame(List.of(instance), motion.position(), null, jets, "meeple-jump", frame); }
            if (frame == 36) { verifyVisibleSmoke(instance, motion.position(), renderer, jets); }
        }
        assertTrue(smoke);
        assertEquals(0, jets.smokeCount(), "Landing clears the same effect timeline");
    }

    private static void verifyVisibleSmoke(ModelInstance instance, Vector3 position,
          GpuPlaybackReview.ReviewRenderer renderer, GpuJumpJets jets) {
        renderer.frame(List.of(instance), position, null, "meeple-jump-no-exhaust", 36);
        renderer.output.pixels().begin();
        var before = Pixmap.createFromFrameBuffer(0, 0, 640, 480);
        renderer.output.pixels().end();
        renderer.frame(List.of(instance), position, null, jets, "meeple-jump", 36);
        renderer.output.pixels().begin();
        var after = Pixmap.createFromFrameBuffer(0, 0, 640, 480);
        renderer.output.pixels().end();
        try {
            int smokePixels = 0, bodySmokePixels = 0;
            for (int y = 0; y < after.getHeight(); y++) {
                for (int x = 0; x < after.getWidth(); x++) {
                    int old = before.getPixel(x, y), color = after.getPixel(x, y);
                    int red = color >>> 24, blue = (color >>> 8) & 255;
                    // Grey smoke must visibly change the image; the bright blue flame cannot satisfy this.
                    if (red > (old >>> 24) + 12 && blue - red < 30) {
                        smokePixels++;
                        // This fixture's brown walls must not look like smoke emitters during descent.
                        if ((old >>> 24) > ((old >>> 8) & 255) + 10) { bodySmokePixels++; }
                    }
                }
            }
            assertTrue(smokePixels > 100, "The late-jump smoke trail must remain visible: " + smokePixels);
            assertTrue(bodySmokePixels < 10, "Smoke must emerge below the token, not through its walls: " + bodySmokePixels);
        } finally { before.dispose(); after.dispose(); }
    }

    private static void verifyAttachments(GpuUnitModel model, GpuUnitInstance host, BoardScene.Unit unit,
          GpuPlaybackReview.ReviewRenderer renderer, GpuUnitModels library) throws Exception {
        var tileset = new MekTileset(Configuration.unitImagesDir());
        tileset.loadFromFile("mekset.txt");
        var armor = new MekFileParser(new File("testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk")).getEntity();
        armor.setId(2);
        var selection = UnitModelSelection.capture(armor, -1, false, tileset);
        var baModel = library.get(selection, 2);
        var ground = BoardGeometry.center(unit.location().coords(), 0);
        for (int combination = 0; combination < 4; combination++) {
            boolean wholeToken = combination >= 2, hostile = combination % 2 == 1;
            var visual = wholeToken ? model : baModel;
            var passenger = new BoardScene.Unit(2, -1, "BA", unit.location(), unit.image(), false, null, 1, false, selection, 0)
                  .withAttachment(new BoardScene.Attachment(unit.id(), hostile));
            var ba = new GpuUnitInstance(visual);
            var animator = new UnitAnimator();
            var controller = new UnitAttachments();
            var instances = Map.<String, ModelInstance>of(unit.id() + ":-1", host, "2:-1", ba);
            String member = wholeToken ? MeepleVisual.ROOT : baModel.rigs().getFirst().container();
            var scene = UnitPlaybackFixture.scene(unit, passenger);
            Matrix4 previous = null;
            for (int frame = 0; frame < 3; frame++) {
                var posture = frame == 2 ? unit.at(unit.location().withProneCause(ProneCause.FORCED).withFallSide(FallSide.FRONT)) : unit;
                MeepleAnimator.place(model, host, posture, UnitMotion.Sample.STILL, renderer.camera, ground.cpy().add(0, 0, frame * 5), frame * 60);
                if (!wholeToken) { animator.apply(visual, ba, passenger, UnitMotion.Sample.STILL, 0, 0, true, 0); }
                if (wholeToken) { MeepleAnimator.place(visual, ba, passenger, UnitMotion.Sample.STILL, renderer.camera, ground, 0); }
                else { visual.place(ba, renderer.camera, ground, 0, passenger); }
                controller.place(scene, library, instances, wholeToken ? Map.of() : Map.of("2:-1", animator),
                      null, renderer.camera, new HashMap<>(), 0);
                var world = ba.transform.cpy().mul(ba.getNode(member).globalTransform);
                if (previous != null) { assertTrue(world.getTranslation(new Vector3()).dst(previous.getTranslation(new Vector3())) > 1); }
                previous = world;
                renderer.viewOffset.y = hostile ? 150 : -150;
                renderer.frame(List.of(host, ba), ground, null, "meeple-riders-" + wholeToken + "-" + hostile, frame);
            }
            var landed = passenger.withAttachment(null).at(new BoardScene.Waypoint(unit.location().coords().translated(3), 0, 0));
            var release = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, passenger, landed, unit, BoardScene.Release.THROWN));
            if (!wholeToken) { animator.apply(visual, ba, landed, UnitMotion.Sample.STILL, 0, 0, true, 0); }
            else { MeepleAnimator.place(visual, ba, landed, UnitMotion.Sample.STILL, renderer.camera, ground, 0); }
            controller.place(UnitPlaybackFixture.scene(unit, landed), library, instances, Map.of(), release, renderer.camera, new HashMap<>(), 0);
            assertArrayEquals(previous.val, ba.transform.cpy().mul(ba.getNode(member).globalTransform).val, .002f,
                  "Release starts from the actual posed token surface");
            release.seconds = UnitAttachmentMotion.DURATION;
            if (wholeToken) { MeepleAnimator.place(visual, ba, landed, UnitMotion.Sample.STILL, renderer.camera, ground, 0); }
            else {
                animator.apply(visual, ba, landed, UnitMotion.Sample.STILL, 0, 0, true, 0);
                visual.place(ba, renderer.camera, ground, 0, landed);
            }
            var resting = ba.transform.cpy().mul(ba.getNode(member).globalTransform);
            controller.place(UnitPlaybackFixture.scene(unit, landed), library, instances, Map.of(), release, renderer.camera, new HashMap<>(), 0);
            assertArrayEquals(resting.val, ba.transform.cpy().mul(ba.getNode(member).globalTransform).val, .002f);
            var boarding = new UnitAttachmentMotion(new BoardScene.AttachmentChange(0, landed, passenger, unit, BoardScene.Release.BOARD));
            controller.place(scene, library, instances, Map.of(), boarding, renderer.camera, new HashMap<>(), 0);
            assertArrayEquals(resting.val, ba.transform.cpy().mul(ba.getNode(member).globalTransform).val, .002f,
                  "Mounting starts at the previous free-standing pose");
            boarding.seconds = UnitAttachmentMotion.DURATION * .999f;
            controller.place(scene, library, instances, Map.of(), boarding, renderer.camera, new HashMap<>(), 0);
            assertTrue(ba.transform.cpy().mul(ba.getNode(member).globalTransform).getTranslation(new Vector3())
                  .dst(previous.getTranslation(new Vector3())) < .01f, "The boarding trajectory reaches its carrier socket");
            if (wholeToken) {
                Matrix4 first = null;
                Vector3 firstGrip = null;
                for (float clock : new float[] { 0, .4f, .4f }) {
                    MeepleAnimator.place(visual, ba, passenger, UnitMotion.Sample.STILL, renderer.camera, ground, 0);
                    controller.place(scene, library, instances, Map.of(), null, renderer.camera, new HashMap<>(), clock);
                    var local = UnitBounds.local(ba);
                    float height = local.getDepth() * UnitAttachments.scale(ba.transform).z;
                    var grip = ba.transform.getTranslation(new Vector3()).add(new Vector3(0, height * .25f, height * .55f)
                          .mul(UnitAttachments.rotation(ba.transform)));
                    if (first != null) {
                        assertEquals(0, grip.dst(firstGrip), .002f, "Hostile rocking stays anchored to its grip");
                        boolean changed = !java.util.Arrays.equals(first.val, ba.transform.val);
                        assertEquals(hostile, changed, "Only hostile riders pulse while friendly riders remain steady");
                    } else { first = ba.transform.cpy(); firstGrip = grip; }
                }
            }
        }
    }

    private static void verifyCombat(GpuUnitModel model, GpuUnitInstance source, BoardScene.Unit unit,
          GpuPlaybackReview.ReviewRenderer renderer, GpuUnitModels library, GpuAttackEffects effects, GpuBoardFixture fixture) {
        var victim = new BoardScene.Unit(3, -1, "Target", new BoardScene.Waypoint(unit.location().coords().translated(0, 2), 0, 3),
              unit.image(), false, null, 2, false, unit.model(), 0);
        var target = new GpuUnitInstance(model);
        var origin = BoardGeometry.center(unit.location().coords(), 0);
        MeepleAnimator.place(model, target, victim, UnitMotion.Sample.STILL, renderer.camera,
              BoardGeometry.center(victim.location().coords(), 0), 180);
        Map<String, ModelInstance> instances = Map.of(unit.id() + ":-1", source, "3:-1", target);
        for (var kind : List.of(ResolvedAttack.Kind.PUSH, ResolvedAttack.Kind.CHARGE, ResolvedAttack.Kind.DFA,
              ResolvedAttack.Kind.RAM, ResolvedAttack.Kind.PUNCH, ResolvedAttack.Kind.KICK, ResolvedAttack.Kind.CLUB)) {
            var attack = new UnitAttack(UnitPlaybackFixture.attack(unit, victim, kind, true));
            for (float seconds : new float[] { 0, attack.approachSeconds * .5f, attack.contactSeconds, attack.duration }) {
                attack.seconds = seconds;
                MeepleAnimator.place(model, source, unit, UnitMotion.Sample.STILL, renderer.camera, origin, 0);
                var rest = source.transform.cpy();
                MeepleAnimator.attacks(unit, source, List.of(attack), instances, false);
                if (seconds == 0 || seconds == attack.duration) { assertArrayEquals(rest.val, source.transform.val, .002f); }
                else { assertTrue(source.transform.getTranslation(new Vector3()).dst(rest.getTranslation(new Vector3())) > .1f); }
                renderer.frame(List.of(source, target), origin, null, "meeple-" + kind, (int) (seconds * 100));
            }
        }
        var prone = unit.at(unit.location().withProneCause(ProneCause.VOLUNTARY));
        var lanceResult = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.CLUB,
              new UnitLocation(unit.id(), unit.location().coords(), 0, 0, 0),
              new UnitLocation(victim.id(), victim.location().coords(), 3, 0, 0), Targetable.TYPE_ENTITY, 0, "Lance", 4, true);
        var lance = new UnitAttack(new BoardScene.Combat(lanceResult, unit, victim, victim.location()));
        var club = new UnitAttack(UnitPlaybackFixture.attack(unit, victim, ResolvedAttack.Kind.CLUB, true));
        lance.seconds = lance.contactSeconds;
        club.seconds = club.contactSeconds;
        MeepleAnimator.place(model, source, unit, UnitMotion.Sample.STILL, renderer.camera, origin, 0);
        MeepleAnimator.attacks(unit, source, List.of(club), instances, false);
        var swing = source.transform.cpy();
        MeepleAnimator.place(model, source, unit, UnitMotion.Sample.STILL, renderer.camera, origin, 0);
        MeepleAnimator.attacks(unit, source, List.of(lance), instances, false);
        assertTrue(source.transform.getTranslation(new Vector3()).dst(swing.getTranslation(new Vector3())) > .1f,
              "A lance thrust uses forward reach instead of the club's sideways swing");
        renderer.frame(List.of(source, target), origin, null, "meeple-lance", 0);
        MeepleAnimator.place(model, source, prone, UnitMotion.Sample.STILL, renderer.camera, origin, 0);
        var lowered = source.transform.cpy();
        var physical = new UnitAttack(UnitPlaybackFixture.attack(prone, victim, ResolvedAttack.Kind.PUNCH, true));
        physical.seconds = physical.contactSeconds;
        MeepleAnimator.attacks(prone, source, List.of(physical), instances, false);
        assertArrayEquals(lowered.val, source.transform.val, .001f, "Prone tokens cannot approach or swing for physical attacks");
        var volley = new ArrayList<UnitAttack>();
        for (var weapon : fixture.entity.getWeaponList()) {
            int index = weapon.getEquipmentNum();
            var result = new ResolvedAttack(new UUID(1, index), ResolvedAttack.Kind.SHOT,
                  new UnitLocation(unit.id(), unit.location().coords(), 0, 0, 0),
                  new UnitLocation(victim.id(), victim.location().coords(), 3, 0, 0), Targetable.TYPE_ENTITY,
                  index, weapon.getType().getInternalName(), weapon.getLocation(), true,
                  ResolvedAttack.captureMounts(fixture.entity, index), ResolvedAttack.Shot.capture(weapon));
            var attack = new UnitAttack(new BoardScene.Combat(result, unit, victim, victim.location()));
            attack.seconds = .3f;
            volley.add(attack);
        }
        effects.update(volley, library, instances);
        MeepleAnimator.attacks(prone, source, volley, instances, false);
        assertArrayEquals(lowered.val, source.transform.val, .001f, "Prone shooting retains its supported pose");
        renderer.camera.viewportWidth = 320;
        renderer.camera.viewportHeight = 240;
        renderer.frame(List.of(source, target), origin, effects, "meeple-volley", 0);
        var center = UnitAttack.center(source, unit.location(), new Vector3());
        var picking = new UnitPicking();
        for (var attack : volley) {
            var points = new ArrayList<Vector3>();
            for (var trace : effects.emissions(attack)) { assertEquals(0, trace.origin().dst(center), .001f); points.add(trace.target()); }
            for (var launch : effects.missileLaunches(attack)) {
                for (var port : launch.origins()) { assertEquals(0, port.dst(center), .001f); }
                points.addAll(List.of(launch.targets()));
            }
            assertFalse(points.isEmpty(), "Every reported gun emits even without model sockets");
            for (var point : points) {
                float first = (float) Math.sqrt(picking.distance(target, new Ray(center, point.cpy().sub(center).nor())));
                assertEquals(first, point.dst(center), .01f, "Shots reach the first exposed surface, never the far wall");
            }
        }
        var scattered = new HashSet<Vector3>();
        for (int seed = 0; seed < 24; seed++) {
            scattered.add(volley.getFirst().hitEndpoint(target, center, seed, 0, 1, new Vector3()));
        }
        assertEquals(24, scattered.size(), "Meeple hits are distributed across the incoming arc");
    }
}
