/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.units.ConvInfantry;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.SupportTank;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real GLB loading, formation replacement/cache invalidation, and a scale-preserving visual review. */
@Tag("on-demand")
class GpuInfantryMeepleSmokeTest {
    private static final String ROOT = "units/modular/meeples/";
    // CHECKSTYLE IGNORE ForbiddenWords FOR 3 LINES - literal asset filenames
    private static final List<String> DESIGNS = List.of("branth-rider", "camel-horse-rider", "odessan-raxx-rider",
          "elephant-rider", "field-gun", "orca-rider", "vtol-trooper", "mechwarrior", "motorcycle-rider",
          "infantry-trooper", "battle-armor-trooper");

    @Test
    void loadsAllSilhouettesAndRebuildsOnlyChangedFormations() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    var reference = library.modular(ROOT + "infantry-trooper.json");
                    assertNotNull(reference);
                    var instances = new ArrayList<ModelInstance>();
                    for (int index = 0; index < DESIGNS.size(); index++) {
                        String name = DESIGNS.get(index);
                        var asset = library.modular(ROOT + name + ".json");
                        assertNotNull(asset, name);
                        assertEquals("meeple-v1", asset.descriptor().rig());
                        assertNotNull(asset.model().getNode("root"));
                        assertNotNull(asset.model().getMaterial("paint"));
                        assertTrue(asset.triangles() > 0 && asset.triangles() * 6 <= UnitModelDescriptor.MAX_TRIANGLES);
                        if (index < 9) {
                            assertEquals(reference.descriptor().bounds().min().get(1),
                                  asset.descriptor().bounds().min().get(1), .0001f);
                            assertEquals(reference.descriptor().bounds().max().get(1),
                                  asset.descriptor().bounds().max().get(1), .0001f);
                        }
                        // CHECKSTYLE IGNORE ForbiddenWords FOR 1 LINES - literal asset filename
                        if (name.equals("mechwarrior")) {
                            assertEquals(reference.descriptor().bounds().max().get(2),
                                  asset.descriptor().bounds().max().get(2), .0001f);
                        }
                        var instance = new ModelInstance(asset.model());
                        // Three source-sheet columns; the last row holds the two original size references.
                        instance.transform.setToTranslation((1 - index % 3) * 28, 0, (3 - index / 3) * 18);
                        instances.add(instance);
                    }
                    renderReview(batch, instances);
                    library.setDisplayMode(UnitDisplayMode.ALL_MEEPLES, false);
                    verifyMemberHover(library);
                    var tileset = new MekTileset(new File(Configuration.dataDir(), "images/units"));
                    tileset.loadFromFile("mekset.txt");
                    int id = 100;
                    for (String chassis : List.of("Beast Infantry (Branth)", "Beast Infantry (Camel)",
                          "Beast Infantry (Horse)", "Beast Infantry (Odessan Raxx)", "Beast Infantry (Elephant)",
                          // CHECKSTYLE IGNORE ForbiddenWords FOR 1 LINES - existing stock tileset chassis key
                          "Beast Infantry (Orca)", "MechWarrior", "Pilot",
                          "VTOL Infantry Hachiman Taro Enterprise Extraction Force")) {
                        var infantry = new ConvInfantry();
                        infantry.setChassis(chassis);
                        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                        var selection = UnitModelSelection.capture(infantry, -1, false, tileset);
                        var formation = library.get(selection, id++);
                        assertNotNull(formation, chassis);
                        assertEquals(6, formation.instance.nodes.size);
                        assertTrue(formation.rigs().stream().allMatch(UnitRig::meeple));
                        assertTrue(triangles(formation.instance.nodes) > reference.triangles() * 6,
                              "Stock icon must resolve a new silhouette: " + chassis);
                        assertTrue(triangles(formation.instance.nodes) <= UnitModelDescriptor.MAX_TRIANGLES);
                    }
                    var infantry = new ConvInfantry();
                    infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
                    var customTileset = mock(MekTileset.class);
                    when(customTileset.modelFor(infantry, -1)).thenReturn("units/modular/infantry.json");
                    when(customTileset.entryFor(infantry, -1)).thenReturn(customTileset.new MekEntry("Infantry/Miniguncycle.png"));
                    var cycleSelection = UnitModelSelection.capture(infantry, -1, false, customTileset);
                    var cycles = library.get(cycleSelection, id);
                    assertNotNull(cycles);
                    assertEquals(1, cycles.instance.nodes.size, "One complete cycle, with no additional transport");
                    assertEquals(library.modular(ROOT + "motorcycle-rider.json").triangles(), triangles(cycles.instance.nodes));
                    for (String variant : List.of("", "(SRM)")) {
                        var cycle = new SupportTank();
                        cycle.setChassis("Minigun Cycle");
                        cycle.setModel(variant);
                        cycle.setWeight(.35);
                        cycle.setMovementMode(EntityMovementMode.WHEELED);
                        var selectedCycle = UnitModelSelection.capture(cycle, -1, false, tileset);
                        assertEquals("vehicles/miniguncycle.png", selectedCycle.state().structure().iconKey());
                        assertEquals(1, selectedCycle.figures());
                        var vehicle = library.get(selectedCycle, ++id);
                        assertNotNull(vehicle);
                        assertEquals(1, vehicle.instance.nodes.size);
                        assertNotNull(vehicle.instance.getNode("vehicle-0"));
                        assertEquals(library.modular(ROOT + "motorcycle-rider.json").triangles(), triangles(vehicle.instance.nodes));
                        assertSame(vehicle, library.get(selectedCycle, id));
                    }
                    id++;
                    when(customTileset.entryFor(infantry, -1)).thenReturn(customTileset.new MekEntry("Infantry/fieldgun.png"));
                    var gun = infantry.addEquipment(EquipmentType.get("ISAC5"), ConvInfantry.LOC_FIELD_GUNS);
                    infantry.addEquipment(EquipmentType.get("ISAC5"), ConvInfantry.LOC_FIELD_GUNS);
                    var armedSelection = UnitModelSelection.capture(infantry, -1, false, customTileset);
                    var armed = library.get(armedSelection, id);
                    assertNotNull(armed);
                    assertNotSame(cycles, armed);
                    assertSame(armed, library.get(armedSelection, id));
                    int gunTriangles = library.modular(ROOT + "field-gun.json").triangles();
                    assertEquals(2 * gunTriangles + 4 * reference.triangles(), triangles(armed.instance.nodes));
                    gun.setDestroyed(true);
                    var damaged = library.get(UnitModelSelection.capture(infantry, -1, false, customTileset), id);
                    assertNotNull(damaged);
                    assertNotSame(armed, damaged);
                    assertEquals(gunTriangles + 5 * reference.triangles(), triangles(damaged.instance.nodes));
                    // The single-unit icon override belongs only to the meeple display mode.
                    library.setDisplayMode(UnitDisplayMode.MODELS, false);
                    var ordinary = library.get(cycleSelection, ++id);
                    assertNotNull(ordinary);
                    assertEquals(6, ordinary.instance.nodes.size);
                    assertTrue(ordinary.rigs().stream().noneMatch(UnitRig::meeple));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    batch.dispose();
                    library.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Infantry meeple review failed", failure.get()); }
    }

    private static void verifyMemberHover(GpuUnitModels library) {
        var infantry = new ConvInfantry();
        infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
        infantry.setMovementMode(EntityMovementMode.VTOL);
        var tileset = mock(MekTileset.class);
        when(tileset.modelFor(infantry, -1)).thenReturn("units/modular/infantry.json");
        when(tileset.entryFor(infantry, -1)).thenReturn(tileset.new MekEntry("Infantry/Infantry_VTOL_Squad.png"));
        var selection = UnitModelSelection.capture(infantry, -1, false, tileset);
        var unit = hoverUnit(selection, true);
        var model = library.get(selection, unit.id());
        assertNotNull(model);
        var placed = new ModelInstance(model.instance.model);
        var animator = new UnitAnimator();
        float amplitude = UnitAnimator.HOVER_LEVELS * BoardGeometry.level();
        var reference = hoverFrame(animator, model, placed, unit, .61f, 1);
        assertEquals(6, reference.size());
        assertEquals(6, reference.values().stream().map(value -> Math.round(value * 10000)).distinct().count(),
              "Each airborne member has its own phase");
        for (float scale : new float[] { .6f, 1, 1.8f }) {
            var scaled = hoverFrame(animator, model, placed, unit, .61f, scale);
            reference.forEach((member, offset) -> assertEquals(offset, scaled.get(member), .0001f,
                  "World-space bobbing must not grow with display scaling"));
            var repeated = hoverFrame(animator, model, placed, unit, .61f, scale);
            scaled.forEach((member, offset) -> assertEquals(offset, repeated.get(member), .0001f,
                  "A paused frame must not accumulate offsets"));
        }
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (int frame = 0; frame < 60; frame++) {
            float clock = frame * UnitAnimator.HOVER_PERIOD_SECONDS / 60;
            var offsets = hoverFrame(animator, model, placed, unit, clock, 1);
            float shared = UnitAnimator.hoverOffset(clock, unit.id(), unit.part());
            for (float offset : offsets.values()) {
                assertTrue(Math.abs(offset + shared) <= amplitude + .002f, "The shared wave must not be added twice");
                assertTrue(Math.abs(offset) < amplitude * .5f, "Members only lead or lag the formation slightly");
            }
            float first = offsets.get("trooper-0") + shared;
            low = Math.min(low, first);
            high = Math.max(high, first);
        }
        assertTrue(low < -amplitude * .99f && high > amplitude * .99f, "Keep the original bobbing height");
        var nextCycle = hoverFrame(animator, model, placed, unit, .61f + UnitAnimator.HOVER_PERIOD_SECONDS, 1);
        reference.forEach((member, offset) -> assertEquals(offset, nextCycle.get(member), .002f));
        var rebound = hoverFrame(animator, model, new ModelInstance(model.instance.model), unit, .61f, 1);
        reference.forEach((member, offset) -> assertEquals(offset, rebound.get(member), .0001f));
        infantry.setInternal(4, ConvInfantry.LOC_INFANTRY);
        var reducedSelection = UnitModelSelection.capture(infantry, -1, false, tileset);
        var reduced = library.get(reducedSelection, unit.id());
        var survivors = new ModelInstance(reduced.instance.model);
        var survivorOffsets = hoverFrame(animator, reduced, survivors, hoverUnit(reducedSelection, true), .61f, 1);
        assertEquals(2, survivorOffsets.size());
        survivorOffsets.forEach((member, offset) -> assertEquals(reference.get(member), offset, .0001f,
              "Casualties must not reassign survivor phases"));
        var grounded = hoverUnit(reducedSelection, false);
        var firstGround = hoverFrame(animator, reduced, survivors, grounded, .61f, 1);
        var laterGround = hoverFrame(animator, reduced, survivors, grounded, 1.3f, 1);
        firstGround.forEach((member, offset) -> assertEquals(0, offset, .0001f));
        assertEquals(firstGround, laterGround, "Landing removes all member hover offsets");
    }

    private static BoardScene.Unit hoverUnit(BoardScene.UnitModel selection, boolean flying) {
        return new BoardScene.Unit(57, -1, "VTOL infantry", new BoardScene.Waypoint(new Coords(0, 0), flying ? 5 : 0, 0),
              null, false, null, 1, flying, selection, 0);
    }

    private static Map<String, Float> hoverFrame(UnitAnimator animator, GpuUnitModel model, ModelInstance placed,
          BoardScene.Unit unit, float clock, float verticalScale) {
        animator.apply(model, placed, unit, UnitMotion.Sample.STILL, clock, 0, true, 0);
        model.place(placed, new OrthographicCamera(), new Vector3(0, 0, 90), 0, unit);
        placed.transform.scale(1, 1, verticalScale);
        var tile = BoardScenes.newTile(new Coords(0, 0), 0, -1, false, 0, BoardScene.Surface.GRASS, List.of(),
              List.of());
        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(unit), List.of(), -1, "MOVEMENT", List.of());
        animator.groundSupports(scene, unit, UnitMotion.Sample.STILL);
        Map<String, Float> offsets = new LinkedHashMap<>();
        for (var rig : model.rigs()) {
            var root = UnitAnimator.find(placed.getNode(rig.container()).getChildren(), "root");
            offsets.put(rig.container(), root.globalTransform.getTranslation(new Vector3()).z * placed.transform.getScaleZ());
        }
        return offsets;
    }

    private static int triangles(Iterable<Node> nodes) {
        int result = 0;
        for (var node : nodes) {
            for (var part : node.parts) { if (part.enabled) { result += part.meshPart.size / 3; } }
            result += triangles(node.getChildren());
        }
        return result;
    }

    private static void renderReview(ModelBatch batch, List<ModelInstance> instances) {
        var environment = new Environment();
        environment.set(ColorAttribute.createAmbientLight(.6f, .6f, .6f, 1));
        environment.add(new DirectionalLight().set(.8f, .8f, .8f, -.4f, -.7f, -1));
        var camera = new OrthographicCamera(120, 120f * Gdx.graphics.getHeight() / Gdx.graphics.getWidth());
        camera.near = 1;
        camera.far = 1000;
        camera.position.set(12, 200, 43);
        camera.up.set(Vector3.Z);
        camera.lookAt(0, 0, 34);
        camera.update();
        try (var surface = new GpuSceneTestOutput(Gdx.graphics.getWidth(), Gdx.graphics.getHeight())) {
            surface.begin(.12f, .14f, .17f, 1);
            batch.begin(camera);
            for (var instance : instances) { batch.render(instance, environment); }
            batch.end();
            surface.display();
            GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots",
                  "build/gpu-board-review"), "infantry-meeples-scale-review.png"));
        }
    }
}
